import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.DoubleFunction;
import java.util.function.IntConsumer;

import javax.swing.Icon;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.colorchooser.AbstractColorChooserPanel;


//カラーパレットの中身。JColorChooserのタブ(周りに大きな枠と余白が付く)の代わりに、
//上の小さな切り替えスイッチでOklab・RGB・Macシステムカラーの3枚を切り替える。
//JColorChooserにはこの1枚だけを渡す(1枚ならタブを出さない)。3枚はそれぞれ
//普通のAbstractColorChooserPanelとして同じchooserに組み込み、選択色の
//変化はupdateChooserで3枚に伝える。最後に選んだ面は次に開いたときもそのまま。
class CompactColorChooserPanel extends AbstractColorChooserPanel {
	private static final long serialVersionUID = 1L;
	private static int lastPage;

	private final AbstractColorChooserPanel[] pages = {
		new OklabColorChooserPanel(), new RgbColorChooserPanel(), new MacSystemColorChooserPanel()
	};

	@Override public String getDisplayName(){ return "Color"; }
	@Override public Icon getSmallDisplayIcon(){ return null; }
	@Override public Icon getLargeDisplayIcon(){ return null; }

	@Override
	public void installChooserPanel(JColorChooser chooser){
		super.installChooserPanel(chooser);
		for(AbstractColorChooserPanel p : pages) p.installChooserPanel(chooser);
	}

	@Override
	public void uninstallChooserPanel(JColorChooser chooser){
		for(AbstractColorChooserPanel p : pages) p.uninstallChooserPanel(chooser);
		super.uninstallChooserPanel(chooser);
	}

	@Override
	protected void buildChooser(){
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		CardLayout cards = new CardLayout();
		JPanel deck = new JPanel(cards);
		deck.setOpaque(false);
		String[] names = new String[pages.length];
		for(int i=0; i<pages.length; i++){
			names[i] = pages[i].getDisplayName();
			pages[i].setOpaque(false);
			deck.add(pages[i], Integer.toString(i));
		}
		SegmentedSwitch tabs = new SegmentedSwitch(names, i -> {
			lastPage = i;
			cards.show(deck, Integer.toString(i));
		});
		tabs.setSelected(lastPage);
		cards.show(deck, Integer.toString(lastPage));
		JPanel top = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		top.setOpaque(false);
		top.add(tabs);
		add(top, BorderLayout.NORTH);
		add(deck, BorderLayout.CENTER);
	}

	//選択色が変わるとJColorChooserはchooserに渡したパネル(この1枚)にだけ
	//知らせるので、中の3枚に伝える。
	@Override
	public void updateChooser(){
		for(AbstractColorChooserPanel p : pages) p.updateChooser();
	}
}


//小さな切り替えスイッチ(角の丸い枠の中に名前を並べ、選んだものを白く浮かせる)。
class SegmentedSwitch extends JComponent {
	private static final long serialVersionUID = 1L;
	private static final int HEIGHT = 20, PADX = 10;
	private final String[] names;
	private final IntConsumer onSelect;
	private int selected;

	SegmentedSwitch(String[] names, IntConsumer onSelect){
		this.names = names;
		this.onSelect = onSelect;
		setFont(ColorPaint.smallFont());
		addMouseListener(new MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){
				int i = indexAt(e.getX());
				if(i>=0 && i!=selected){
					setSelected(i);
					SegmentedSwitch.this.onSelect.accept(i);
				}
			}
		});
	}

	void setSelected(int i){
		selected = Math.max(0, Math.min(names.length-1, i));
		repaint();
	}

	private int[] edges(){
		FontMetrics fm = getFontMetrics(getFont());
		int[] x = new int[names.length+1];
		for(int i=0; i<names.length; i++) x[i+1] = x[i]+fm.stringWidth(names[i])+2*PADX;
		return x;
	}

	private int indexAt(int px){
		int[] x = edges();
		for(int i=0; i<names.length; i++) if(px>=x[i] && px<x[i+1]) return i;
		return -1;
	}

	@Override public Dimension getPreferredSize(){
		int[] x = edges();
		return new Dimension(x[names.length]+1, HEIGHT);
	}

	@Override
	protected void paintComponent(Graphics g){
		Graphics2D g2 = ColorPaint.smooth(g);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int[] x = edges();
		int w = x[names.length], h = HEIGHT-1;
		boolean dark = ColorPaint.isDark(getParent()!=null ? getParent().getBackground() : getBackground());
		g2.setColor(dark ? new Color(255, 255, 255, 22) : new Color(0, 0, 0, 16));
		g2.fill(new RoundRectangle2D.Double(0.5, 0.5, w, h, h, h));
		//選んだもの
		double sx = x[selected]+2, sw = x[selected+1]-x[selected]-3;
		g2.setColor(new Color(0, 0, 0, dark ? 60 : 28));
		g2.fill(new RoundRectangle2D.Double(sx, 3, sw, h-4, h-4, h-4));
		g2.setColor(dark ? new Color(0x6A, 0x6A, 0x6A) : Color.WHITE);
		g2.fill(new RoundRectangle2D.Double(sx, 2, sw, h-4, h-4, h-4));
		//区切り(選んだものの両隣には引かない)
		g2.setColor(dark ? new Color(255, 255, 255, 40) : new Color(0, 0, 0, 30));
		for(int i=1; i<names.length; i++){
			if(i==selected || i==selected+1) continue;
			g2.fillRect(x[i], 5, 1, h-9);
		}
		FontMetrics fm = g2.getFontMetrics(getFont());
		g2.setFont(getFont());
		Color text = UIManager.getColor("Label.foreground");
		for(int i=0; i<names.length; i++){
			g2.setColor(i==selected ? (text!=null ? text : Color.BLACK) : ColorPaint.secondaryText());
			int tx = x[i]+PADX, ty = (h+1+fm.getAscent()-fm.getDescent())/2;
			g2.drawString(names[i], tx, ty);
		}
		g2.dispose();
	}
}


//RGB(と不透明度)を、色の帯の上をつまみで動かすスライダーと数値で選ぶ面。
//JColorChooser標準のRGBタブ(横に大きい)の代わり。
class RgbColorChooserPanel extends AbstractColorChooserPanel {
	private static final long serialVersionUID = 1L;
	private final int[] v = {0, 0, 0, 255}; //R,G,B,A
	private final GradientSlider[] sliders = new GradientSlider[4];
	private final JSpinner[] fields = new JSpinner[4];
	private JTextField hexField;
	private boolean syncing;

	@Override public String getDisplayName(){ return "RGB"; }
	@Override public Icon getSmallDisplayIcon(){ return null; }
	@Override public Icon getLargeDisplayIcon(){ return null; }

	@Override
	protected void buildChooser(){
		setLayout(new GridBagLayout());
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		String[] labels = {"R", "G", "B", "A"};
		String[] tips = {International.tc("color", "Red"), International.tc("color", "Green"),
				International.tc("color", "Blue"), International.tc("menu", "Opacity")};
		int y = OklabColorChooserPanel.header(p, 0, "sRGB");
		for(int i=0; i<4; i++){
			final int ch = i;
			sliders[i] = new GradientSlider(ch==3, t -> gradientColor(ch, t), t -> set(ch, (int)Math.round(t*255)));
			sliders[i].setToolTipText(tips[i]);
			fields[i] = new JSpinner(new SpinnerNumberModel(0, 0, 255, 1));
			((JSpinner.DefaultEditor)fields[i].getEditor()).getTextField().setColumns(3);
			ColorPaint.small(fields[i]);
			fields[i].addChangeListener(e -> { if(!syncing) set(ch, ((Number)fields[ch].getValue()).intValue()); });
			JLabel l = ColorPaint.small(new JLabel(labels[i]));
			l.setToolTipText(tips[i]);
			GridBagConstraints gc = new GridBagConstraints();
			gc.gridy = y;
			gc.gridx = 0; gc.insets = new Insets(1, 0, 1, 4); gc.anchor = GridBagConstraints.EAST;
			p.add(l, gc);
			gc.gridx = 1; gc.insets = new Insets(1, 0, 1, 6); gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
			p.add(sliders[i], gc);
			gc.gridx = 2; gc.insets = new Insets(1, 0, 1, 0); gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
			p.add(fields[i], gc);
			y++;
		}
		hexField = ColorPaint.small(new JTextField(6));
		hexField.setHorizontalAlignment(SwingConstants.RIGHT);
		hexField.addActionListener(e -> hexEdited());
		hexField.addFocusListener(new java.awt.event.FocusAdapter(){
			@Override public void focusLost(java.awt.event.FocusEvent e){ hexEdited(); }
		});
		JLabel hl = ColorPaint.small(new JLabel("#"));
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridy = y;
		gc.gridx = 0; gc.insets = new Insets(6, 0, 0, 4); gc.anchor = GridBagConstraints.EAST;
		p.add(hl, gc);
		gc.gridx = 1; gc.gridwidth = 2; gc.insets = new Insets(6, 0, 0, 0); gc.anchor = GridBagConstraints.WEST;
		p.add(hexField, gc);
		GridBagConstraints outer = new GridBagConstraints();
		outer.fill = GridBagConstraints.HORIZONTAL;
		outer.weightx = 1;
		outer.insets = new Insets(0, 12, 0, 12);
		add(p, outer);
	}

	//スライダーchの帯の位置t(0..1)の色(他の成分は今の値のまま)。
	private Color gradientColor(int ch, double t){
		int c = (int)Math.round(t*255);
		if(ch==3) return new Color(v[0], v[1], v[2], c);
		return new Color(ch==0 ? c : v[0], ch==1 ? c : v[1], ch==2 ? c : v[2]);
	}

	private void set(int ch, int value){
		value = Math.max(0, Math.min(255, value));
		if(v[ch]==value) return;
		v[ch] = value;
		getColorSelectionModel().setSelectedColor(new Color(v[0], v[1], v[2], v[3]));
	}

	private void hexEdited(){
		if(syncing) return;
		String s = hexField.getText().trim();
		if(s.startsWith("#")) s = s.substring(1);
		if(s.length()==3) s = ""+s.charAt(0)+s.charAt(0)+s.charAt(1)+s.charAt(1)+s.charAt(2)+s.charAt(2);
		try{
			if(s.length()!=6) throw new NumberFormatException();
			int rgb = Integer.parseInt(s, 16);
			getColorSelectionModel().setSelectedColor(new Color((rgb>>16)&0xFF, (rgb>>8)&0xFF, rgb&0xFF, v[3]));
		} catch(NumberFormatException e){
			updateChooser(); //読めなければ今の色に戻す
		}
	}

	@Override
	public void updateChooser(){
		Color c = getColorFromModel();
		if(c==null || sliders[0]==null) return;
		v[0] = c.getRed(); v[1] = c.getGreen(); v[2] = c.getBlue(); v[3] = c.getAlpha();
		syncing = true;
		try{
			for(int i=0; i<4; i++){
				sliders[i].setValue(v[i]/255.0, new Color(v[0], v[1], v[2]));
				fields[i].setValue(v[i]);
			}
			if(!hexField.isFocusOwner()) hexField.setText(String.format("%02X%02X%02X", v[0], v[1], v[2]));
		} finally {
			syncing = false;
		}
	}
}


//色の帯の横長スライダー(明度バーを横にしたもの)。帯の色は位置ごとにcolorAtで決め、
//つまみには今の色を塗る。checkerなら帯の下に市松模様を敷く(不透明度用)。
class GradientSlider extends JComponent {
	private static final long serialVersionUID = 1L;
	private static final int TRACK = 10, KNOB = 7, W = 150, PADX = KNOB+2;
	private final boolean checker;
	private final DoubleFunction<Color> colorAt;
	private final java.util.function.DoubleConsumer onChange;
	private double value;
	private Color knobColor = Color.BLACK;

	GradientSlider(boolean checker, DoubleFunction<Color> colorAt, java.util.function.DoubleConsumer onChange){
		this.checker = checker;
		this.colorAt = colorAt;
		this.onChange = onChange;
		setPreferredSize(new Dimension(W+2*PADX, 2*KNOB+6));
		MouseAdapter m = new MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){ pick(e.getX()); }
			@Override public void mouseDragged(MouseEvent e){ pick(e.getX()); }
			@Override public void mouseWheelMoved(MouseWheelEvent e){
				GradientSlider.this.onChange.accept(Math.max(0, Math.min(1, value-e.getPreciseWheelRotation()/255.0)));
			}
		};
		addMouseListener(m);
		addMouseMotionListener(m);
		addMouseWheelListener(m);
	}

	void setValue(double value, Color knob){
		this.value = value;
		this.knobColor = knob;
		repaint();
	}

	private void pick(int px){
		double w = Math.max(1, getWidth()-2*PADX);
		onChange.accept(Math.max(0, Math.min(1, (px-PADX)/w)));
	}

	@Override
	protected void paintComponent(Graphics g){
		Graphics2D g2 = ColorPaint.smooth(g);
		int w = getWidth()-2*PADX, x0 = PADX, y0 = (getHeight()-TRACK)/2;
		ColorPaint.softShadow(g2, k -> new RoundRectangle2D.Double(x0-k, y0-k, w+2*k, TRACK+2*k, TRACK+2*k, TRACK+2*k));
		RoundRectangle2D track = new RoundRectangle2D.Double(x0, y0, w, TRACK, TRACK, TRACK);
		Shape oldClip = g2.getClip();
		g2.clip(track);
		if(checker) ColorPaint.checker(g2, x0, y0, w, TRACK, 5);
		//帯は端の色をつまみの半径ぶん外まで伸ばして塗る(丸い端も同じ色に)
		for(int x=0; x<w; x++){
			g2.setColor(colorAt.apply(w<=1 ? 0 : x/(double)(w-1)));
			g2.fillRect(x0+x, y0, 1, TRACK);
		}
		g2.setClip(oldClip);
		ColorPaint.rim(g2, x0, y0, w, TRACK);
		ColorPaint.drawKnob(g2, x0+value*w, y0+TRACK/2.0, KNOB, checker ? colorAt.apply(value) : knobColor);
		g2.dispose();
	}
}


//カラーパレットの部品で共通の描き方と、小さめの部品の作り方。
final class ColorPaint {
	private ColorPaint(){}

	static Graphics2D smooth(Graphics g){
		Graphics2D g2 = (Graphics2D)g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		return g2;
	}

	static double deviceScale(Graphics2D g2){
		double s = g2.getTransform().getScaleX();
		return s>0 ? s : 1;
	}

	//下に少しずらしたぼかし影(半透明の形を少しずつ広げて重ねる)。
	static void softShadow(Graphics2D g2, DoubleFunction<Shape> grown){
		for(int i=4; i>=1; i--){
			g2.setColor(new Color(0, 0, 0, 8));
			Shape s = grown.apply(i*0.8);
			g2.translate(0, 1.2);
			g2.fill(s);
			g2.translate(0, -1.2);
		}
	}

	//角の丸い帯の縁: 外側に細い暗い線、内側に細い明るい線(どんな色の上でも縁が見えるように)。
	static void rim(Graphics2D g2, double x, double y, double w, double h){
		double r = Math.min(w, h);
		g2.setStroke(new BasicStroke(1f));
		g2.setColor(new Color(0, 0, 0, 50));
		g2.draw(new RoundRectangle2D.Double(x-0.5, y-0.5, w+1, h+1, r+1, r+1));
		g2.setColor(new Color(255, 255, 255, 60));
		g2.draw(new RoundRectangle2D.Double(x+0.5, y+0.5, w-1, h-1, r-1, r-1));
	}

	//colorを中に塗った白い輪のつまみ。
	static void drawKnob(Graphics2D g2, double x, double y, double r, Color color){
		g2.setColor(new Color(0, 0, 0, 45));
		g2.fill(new Ellipse2D.Double(x-r-1, y-r, 2*r+2, 2*r+2));
		g2.setColor(Color.WHITE);
		g2.fill(new Ellipse2D.Double(x-r, y-r, 2*r, 2*r));
		double ir = r-2.5;
		if(color.getAlpha()<255) checker(g2, ir, x, y);
		g2.setColor(color);
		g2.fill(new Ellipse2D.Double(x-ir, y-ir, 2*ir, 2*ir));
		g2.setStroke(new BasicStroke(1f));
		g2.setColor(new Color(0, 0, 0, 70));
		g2.draw(new Ellipse2D.Double(x-r-0.5, y-r-0.5, 2*r+1, 2*r+1));
		g2.setColor(new Color(0, 0, 0, 30));
		g2.draw(new Ellipse2D.Double(x-ir, y-ir, 2*ir, 2*ir));
	}

	private static void checker(Graphics2D g2, double r, double x, double y){
		Shape old = g2.getClip();
		g2.clip(new Ellipse2D.Double(x-r, y-r, 2*r, 2*r));
		checker(g2, (int)Math.floor(x-r), (int)Math.floor(y-r), (int)Math.ceil(2*r)+1, (int)Math.ceil(2*r)+1, 3);
		g2.setClip(old);
	}

	//半透明の色が分かるよう下に敷く市松模様。
	static void checker(Graphics2D g2, int x, int y, int w, int h, int cell){
		g2.setColor(Color.WHITE);
		g2.fillRect(x, y, w, h);
		g2.setColor(new Color(0xD0D0D0));
		for(int cy=y, r=0; cy<y+h; cy+=cell, r++){
			for(int cx=x+(r%2)*cell; cx<x+w; cx+=2*cell) g2.fillRect(cx, cy, cell, cell);
		}
	}

	static boolean isDark(Color c){
		if(c==null) return false;
		return c.getRed()*0.299+c.getGreen()*0.587+c.getBlue()*0.114 < 128;
	}

	static Color secondaryText(){
		Color c = UIManager.getColor("Label.disabledForeground");
		return c!=null ? c : Color.GRAY;
	}

	static boolean aqua(){
		return "Aqua".equals(UIManager.getLookAndFeel().getID());
	}

	static Font smallFont(){
		Font f = UIManager.getFont("Label.font");
		if(f==null) f = new Font(Font.DIALOG, Font.PLAIN, 13);
		return f.deriveFont(aqua() ? 11f : Math.max(10f, f.getSize2D()-1f));
	}

	//小さめの部品にする(macOSではAquaの"small"の大きさ、他では文字を小さく)。
	static <T extends JComponent> T small(T c){
		c.putClientProperty("JComponent.sizeVariant", "small");
		c.setFont(smallFont());
		if(c instanceof JSpinner){
			JComponent ed = ((JSpinner)c).getEditor();
			if(ed instanceof JSpinner.DefaultEditor){
				JTextField tf = ((JSpinner.DefaultEditor)ed).getTextField();
				tf.putClientProperty("JComponent.sizeVariant", "small");
				tf.setFont(smallFont());
			}
		}
		return c;
	}

	//数値欄のまとまりの見出し(小さな太字の灰色)。
	static JLabel header(String text){
		JLabel l = new JLabel(text);
		l.setFont(smallFont().deriveFont(Font.BOLD, smallFont().getSize2D()-1f));
		l.setForeground(secondaryText());
		return l;
	}
}

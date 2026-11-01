import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** 視覚効果の確認(ボタン情報の「視覚効果…」): 効果を選ぶとプレビューで再生し、その効果の visual effect の行を示す。
 *  コマは VEffect.playFrames(カードウインドウと同じ描画)でプレビューへ直接描く。再生は別スレッド */
public class GVisualEffectWindow extends JFrame {
	private static final long serialVersionUID = 1L;
	private static GVisualEffectWindow window;

	/** Effect names as a script writes them, indexed by the number TUtil.getVisualMode returns (0-27 HyperCard, then
	 *  HyperZebra's own from VEffect.FADE). */
	static final String[] EFFECTS = {
		"plain", "barn door open", "barn door close", "dissolve", "venetian blinds", "checkerboard",
		"iris open", "iris close", "scroll left", "scroll right", "scroll up", "scroll down",
		"wipe left", "wipe right", "wipe up", "wipe down", "zoom open", "zoom close",
		"shrink to top", "shrink to bottom", "shrink to center", "stretch from top", "stretch from bottom", "stretch from center",
		"push left", "push right", "push up", "push down",
		"fade", "mosaic", "blur", "block dissolve", "radial wipe", "circle open", "circle close",
		"cross zoom", "page turn left", "page turn right"};
	/** Speed words by speed number 1-5 (3 = no word) */
	static final String[] SPEEDS = {"very fast", "fast", "", "slow", "very slow"};
	/** Images by "to" number: 0 card, 1 black, 2 white, 3 gray, 4 inverse */
	static final String[] IMAGES = {"card", "black", "white", "gray", "inverse"};

	/** The visual effect command for an effect, speed (1-5) and image (0-4) */
	static String scriptLine(int effect, int speed, int to){
		StringBuilder s = new StringBuilder("visual effect ").append(EFFECTS[effect]);
		if(speed>=1 && speed<=5 && !SPEEDS[speed-1].isEmpty()) s.append(' ').append(SPEEDS[speed-1]);
		if(to>0 && to<IMAGES.length) s.append(" to ").append(IMAGES[to]);
		return s.toString();
	}

	static void open(Window owner){
		boolean opened = window==null;
		if(opened) window = new GVisualEffectWindow(owner);
		window.setVisible(true);
		window.toFront();
		if(opened){
			GVisualEffectWindow w = window;
			SwingUtilities.invokeLater(() -> w.play(false));
		}
	}

	private static final int PREVIEW_MAX_W = 480, PREVIEW_MAX_H = 320;
	private final JList<String> list = new JList<String>(EFFECTS);
	private final JComboBox<String> speedBox = new JComboBox<String>();
	private final JComboBox<String> toBox = new JComboBox<String>(IMAGES);
	private final JCheckBox useCard = new JCheckBox(International.t("Start from this card"));
	private final JTextField line = new JTextField();
	private final JButton playAll = new JButton(International.t("Play All"));
	private final Preview preview;
	private final Object playLock = new Object();
	/** Bumped by every new play and by Stop / close: a running play stops at its next step */
	private volatile int generation;
	private volatile boolean playingAll;
	private int lastPlayed = -1;
	private long lastPlayedAt;

	private GVisualEffectWindow(Window owner){
		super(International.t("Visual Effects"));
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosed(WindowEvent e){ stop(); window = null; }
		});

		Dimension size = previewSize();
		preview = new Preview(size.width, size.height);

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setSelectedIndex(3); //dissolve。開いたときに1回再生する(open)
		list.setVisibleRowCount(16);
		list.setCellRenderer(new DefaultListCellRenderer(){
			private static final long serialVersionUID = 1L;
			@Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus){
				JLabel c = (JLabel)super.getListCellRendererComponent(l, value, index, selected, focus);
				//HyperZebra だけの効果は区切り線の下に分ける(HyperCard のスタックでは使えない)
				c.setBorder(index==VEffect.FADE ? BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(1, 0, 0, 0, Color.gray), BorderFactory.createEmptyBorder(2, 4, 1, 4))
					: BorderFactory.createEmptyBorder(1, 4, 1, 4));
				return c;
			}
		});
		list.addListSelectionListener(e -> {
			if(e.getValueIsAdjusting()) return;
			updateLine();
			if(!playingAll && list.getSelectedIndex()>=0) play(false);
		});
		list.addMouseListener(new MouseAdapter(){
			@Override public void mouseClicked(MouseEvent e){
				//選択済みの効果をもう一度クリックしたら再生し直す(選択が変わったときは上で再生済み)
				int i = list.locationToIndex(e.getPoint());
				if(i>=0 && !(i==lastPlayed && System.currentTimeMillis()-lastPlayedAt<400)) play(false);
			}
		});
		JScrollPane listScroll = new JScrollPane(list);
		JLabel hzOnly = new JLabel(International.t("Below the line: HyperZebra only"));
		hzOnly.setFont(hzOnly.getFont().deriveFont(hzOnly.getFont().getSize2D()-2f));
		hzOnly.setForeground(Color.gray);
		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.add(listScroll, BorderLayout.CENTER);
		left.add(hzOnly, BorderLayout.SOUTH);

		speedBox.addItem("very fast");
		speedBox.addItem("fast");
		speedBox.addItem(International.t("normal"));
		speedBox.addItem("slow");
		speedBox.addItem("very slow");
		speedBox.setSelectedIndex(2);
		speedBox.addActionListener(e -> { updateLine(); play(false); });
		toBox.addActionListener(e -> { updateLine(); play(false); });
		useCard.addActionListener(e -> play(false));

		JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		options.add(new JLabel(International.t("Effect speed:")));
		options.add(speedBox);
		options.add(new JLabel(International.t("Ends on:")));
		options.add(toBox);
		options.add(useCard);

		line.setEditable(false);
		line.setFont(new Font(Font.MONOSPACED, Font.PLAIN, line.getFont().getSize()));
		JButton copy = new JButton(International.t("Copy"));
		copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(line.getText()), null));
		JPanel lineRow = new JPanel(new BorderLayout(6, 0));
		lineRow.add(line, BorderLayout.CENTER);
		lineRow.add(copy, BorderLayout.EAST);

		JButton playButton = new JButton(International.t("Play"));
		playButton.addActionListener(e -> play(false));
		playAll.addActionListener(e -> { if(playingAll) stop(); else play(true); });
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
		buttons.add(playAll);
		buttons.add(playButton);
		getRootPane().setDefaultButton(playButton);

		JPanel frame = new JPanel(new BorderLayout());
		frame.setBorder(BorderFactory.createLineBorder(Color.gray));
		frame.add(preview);
		JPanel previewHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		previewHolder.add(frame);

		JPanel right = new JPanel();
		right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
		for(JComponent c : new JComponent[]{previewHolder, options, lineRow, buttons}){
			c.setAlignmentX(0f);
			right.add(c);
			right.add(javax.swing.Box.createVerticalStrut(6));
		}

		JPanel content = new JPanel(new BorderLayout(10, 0));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(left, BorderLayout.WEST);
		content.add(right, BorderLayout.CENTER);
		setContentPane(content);

		updateLine();
		pack();
		setResizable(false);
		setLocationRelativeTo(owner);
	}

	/** The preview has the card's proportions, at most 480 x 320 */
	private static Dimension previewSize(){
		int w = 512, h = 342;
		if(PCARDFrame.pc!=null && PCARDFrame.pc.stack!=null && PCARDFrame.pc.stack.width>0 && PCARDFrame.pc.stack.height>0){
			w = PCARDFrame.pc.stack.width; h = PCARDFrame.pc.stack.height;
		}
		double scale = Math.min(PREVIEW_MAX_W/(double)w, PREVIEW_MAX_H/(double)h);
		return new Dimension(Math.max(64, (int)Math.round(w*scale)), Math.max(48, (int)Math.round(h*scale)));
	}

	private int speed(){ return speedBox.getSelectedIndex()+1; }
	private int to(){ return Math.max(0, toBox.getSelectedIndex()); }

	private void updateLine(){
		int effect = list.getSelectedIndex();
		line.setText(effect<0 ? "" : scriptLine(effect, speed(), to()));
		line.setCaretPosition(0);
	}

	private void stop(){
		generation++;
		setPlayingAll(false);
	}

	private void setPlayingAll(boolean on){
		playingAll = on;
		playAll.setText(on ? International.t("Stop") : International.t("Play All"));
	}

	/** Plays the selected effect, or (all) every effect from the selected one to the last */
	private void play(boolean all){
		int effect = list.getSelectedIndex();
		if(effect<0) return;
		int gen = ++generation;
		lastPlayed = effect; lastPlayedAt = System.currentTimeMillis();
		setPlayingAll(all);
		int w = preview.picture.getWidth(), h = preview.picture.getHeight();
		BufferedImage first = useCard.isSelected() ? cardPicture(w, h) : null;
		if(first==null) first = samplePicture(w, h, false);
		BufferedImage second = samplePicture(w, h, true);
		int speed = speed(), to = to();
		BufferedImage a = first;
		Thread t = new Thread(() -> {
			synchronized(playLock){
				for(int e = effect; e<EFFECTS.length && gen==generation; e++){
					if(all && e!=effect){
						int shown = e;
						SwingUtilities.invokeLater(() -> { if(gen==generation){ list.setSelectedIndex(shown); list.ensureIndexIsVisible(shown); } });
					}
					playOne(gen, e, speed, to, a, second);
					if(!all) break;
					pause(gen, 700);
				}
			}
			SwingUtilities.invokeLater(() -> { if(gen==generation) setPlayingAll(false); });
		}, "Visual effect preview");
		t.setDaemon(true);
		t.start();
	}

	/** One effect: picture a, the effect to the image (b, or black/white/gray/inverse of b), then b as go shows it */
	private void playOne(int gen, int effect, int speed, int to, BufferedImage a, BufferedImage b){
		int w = b.getWidth(), h = b.getHeight();
		BufferedImage target = b;
		if(to==4) target = VEffect.inverted(b);
		else if(to!=0){
			target = new BufferedImage(w, h, BufferedImage.TYPE_INT_BGR);
			Graphics2D tg = target.createGraphics();
			VEffect.fillPlain(tg, to, w, h);
			tg.dispose();
		}
		preview.show(a);
		pause(gen, 350);
		if(gen!=generation) return;
		BufferedImage from = copy(a); //dissolve は始まりの絵に描き込む
		Graphics2D g = preview.screen();
		if(g==null) return;
		try{
			long start = System.currentTimeMillis();
			VEffect.playFrames(effect, g, from, target, start, start+VEffect.effectDuration(effect, speed));
			g.setPaintMode();
			g.drawImage(target, 0, 0, null);
			Toolkit.getDefaultToolkit().sync();
		}finally{
			g.dispose();
		}
		preview.picture = target;
		if(target!=b){
			//to black などの後は、カード移動と同じく効果なしで新しいカードになる
			pause(gen, 450);
			if(gen==generation) preview.show(b);
		}
	}

	private void pause(int gen, long ms){
		long end = System.currentTimeMillis()+ms;
		while(gen==generation){
			long left = end-System.currentTimeMillis();
			if(left<=0) break;
			try{ Thread.sleep(Math.min(left, 50)); }catch(InterruptedException e){ Thread.currentThread().interrupt(); return; }
		}
	}

	private static BufferedImage copy(BufferedImage image){
		BufferedImage c = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_BGR);
		Graphics2D g = c.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return c;
	}

	/** The current card, reduced to the preview (EDT); null without a card window */
	private static BufferedImage cardPicture(int w, int h){
		if(PCARDFrame.pc==null || PCARDFrame.pc.mainPane==null) return null;
		int cw = PCARDFrame.pc.mainPane.getWidth(), ch = PCARDFrame.pc.mainPane.getHeight();
		if(cw<=0 || ch<=0) return null;
		BufferedImage card = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_BGR);
		Graphics2D cg = card.createGraphics();
		try{ VEffect.paintCurrentPanel(cg); }finally{ cg.dispose(); }
		BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_BGR);
		Graphics2D g = small.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(card, 0, 0, w, h, null);
		g.dispose();
		return small;
	}

	/** Sample card A (white, black lines, as a HyperCard card) or B (colour), with a big letter so that the direction of
	 *  scroll/push/wipe is visible */
	static BufferedImage samplePicture(int w, int h, boolean second){
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_BGR);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int unit = Math.max(1, Math.min(w, h)/12);
		if(!second){
			g.setColor(Color.white);
			g.fillRect(0, 0, w, h);
			g.setColor(Color.black);
			g.setStroke(new BasicStroke(Math.max(1, unit/6f)));
			for(int y = h/2+unit; y<h-unit; y+=unit) g.drawLine(w/2+unit/2, y, w-unit, y);
			g.drawRect(unit/2, unit/2, w-unit, h-unit);
			g.fillOval(w/2+unit/2, unit*2, unit*2, unit*2);
			g.drawRect(w/2+unit*3, unit*2, unit*3, unit*2);
		}
		else{
			g.setPaint(new GradientPaint(0, 0, new Color(0x2E6FD8), w, h, new Color(0x7A2BB5)));
			g.fillRect(0, 0, w, h);
			g.setColor(new Color(255, 214, 64));
			for(int i=0; i<5; i++){
				int d = unit*(1+i%3);
				g.fillOval(w/2+unit+i*unit*1, h-unit*2-d-(i%2)*unit*2, d, d);
			}
			g.setColor(new Color(255, 255, 255, 150));
			g.setStroke(new BasicStroke(Math.max(1, unit/4f)));
			for(int x = w/2+unit; x<w-unit; x+=unit*2) g.drawLine(x, unit, x+unit, unit*3);
		}
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, h*2/3));
		String letter = second ? "B" : "A";
		java.awt.FontMetrics fm = g.getFontMetrics();
		g.setColor(second ? Color.white : Color.black);
		g.drawString(letter, unit*2, (h+fm.getAscent()-fm.getDescent())/2);
		g.dispose();
		return image;
	}

	/** The preview screen. A play draws its frames straight onto it (as an effect on the card window); paint shows the
	 *  last picture */
	private static class Preview extends JComponent {
		private static final long serialVersionUID = 1L;
		volatile BufferedImage picture;

		Preview(int w, int h){
			picture = samplePicture(w, h, false);
			Dimension d = new Dimension(w, h);
			setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
		}

		/** A graphics on the picture area (off the EDT too), or null when not on screen */
		Graphics2D screen(){
			Graphics g = getGraphics();
			if(g==null) return null;
			g.clipRect(0, 0, picture.getWidth(), picture.getHeight());
			return (Graphics2D)g;
		}

		void show(BufferedImage image){
			picture = image;
			Graphics2D g = screen();
			if(g==null){ repaint(); return; }
			try{ g.drawImage(image, 0, 0, null); Toolkit.getDefaultToolkit().sync(); }finally{ g.dispose(); }
		}

		@Override protected void paintComponent(Graphics g){
			g.drawImage(picture, 0, 0, null);
		}
	}
}

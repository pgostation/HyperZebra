import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicProgressBarUI;

/**
 * Feedback while a stack is read or written.
 * <ul>
 * <li>Importing a HyperCard stack (or a file's resource fork) shows a small progress window like the host's own: a title
 *     bar, what is being imported, a bar of the platform's usual height and the current step below it
 *     ({@link #open}/{@link #close}). The converters keep using {@code stack.bar}: its setString goes to the step line.
 *     A stack whose forks together are at most {@link #QUIET_BYTES} imports too fast to need the window: the bar is
 *     then kept off screen.</li>
 * <li>Opening, creating and saving a HyperZebra stack only show the wait cursor ({@link #busy}).</li>
 * </ul>
 */
final class StackProgress {
	private StackProgress(){}

	private static final int BAR_WIDTH = 340;

	/** Imports of at most this many bytes (data and resource fork together) show no progress window. */
	static final long QUIET_BYTES = 500L*1024;

	/**
	 * Shows the progress window for importing {@code fileName} ({@code totalBytes} of forks) into {@code stack};
	 * {@code stack.bar} then drives it. Small imports get an off-screen bar instead.
	 */
	static void open(OStack stack, String fileName, String step, long totalBytes){
		close(stack);
		if(java.awt.GraphicsEnvironment.isHeadless()) return;
		if(totalBytes>=0 && totalBytes<=QUIET_BYTES){
			stack.bar = new Bar(null); //変換側はstack.barを使うので、表示しないバーを渡す
			stack.barOffset = 0;
			return;
		}
		Window owner = stack.pcard;
		JDialog dialog = new JDialog(owner, PCARD.AppName);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.setResizable(false);
		dialog.setFocusableWindowState(false); //読み込み中もキー入力を奪わない

		boolean aqua = "Aqua".equals(UIManager.getLookAndFeel().getID());
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(aqua?14:12, 20, aqua?16:14, 20));
		if(!aqua) content.setBackground(new Color(240, 240, 240)); //Windowsのダイアログ地色

		Font base = UIManager.getFont("Label.font");
		if(base==null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
		String name = fileName==null ? "" : fileName;
		JLabel title = new JLabel(International.t("Importing \"{0}\"…", name));
		title.setFont(base.deriveFont(Font.PLAIN, aqua ? 13f : 12f));
		JLabel status = new JLabel(step==null||step.isEmpty() ? " " : step);
		status.setFont(base.deriveFont(Font.PLAIN, aqua ? 11f : 12f));
		status.setForeground(aqua ? new Color(0x80, 0x80, 0x80) : new Color(0x55, 0x55, 0x55));

		Bar bar = new Bar(status);
		if(!aqua) bar.setUI(new ClassicBarUI());
		Dimension pref = bar.getPreferredSize();
		bar.setPreferredSize(new Dimension(BAR_WIDTH, pref.height));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));

		for(JComponent c: new JComponent[]{ title, bar, status }) c.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(title);
		content.add(Box.createVerticalStrut(8));
		content.add(bar);
		content.add(Box.createVerticalStrut(6));
		content.add(status);
		dialog.getContentPane().setLayout(new BorderLayout());
		dialog.getContentPane().add(content, BorderLayout.CENTER);
		dialog.pack();
		dialog.setLocation(location(owner, dialog.getSize()));

		stack.barDialog = dialog;
		stack.bar = bar;
		stack.barOffset = 0;
		dialog.setVisible(true);
		dialog.getRootPane().paintImmediately(0, 0, dialog.getRootPane().getWidth(), dialog.getRootPane().getHeight());
	}

	/** Takes the progress window down (nothing when none is shown). */
	static void close(OStack stack){
		if(stack.barDialog!=null) stack.barDialog.dispose();
		stack.bar = null;
		stack.barDialog = null;
	}

	/** Centred on the upper part of the card window, or of the launcher while the card window is still hidden. */
	private static java.awt.Point location(Window owner, Dimension size){
		Rectangle area = GLauncher.dialogArea(owner);
		if(area==null || area.width<=0 || area.height<=0){
			Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
			return new java.awt.Point(screen.x+(screen.width-size.width)/2, screen.y+(screen.height-size.height)/3);
		}
		return new java.awt.Point(area.x+(area.width-size.width)/2, area.y+Math.max(0, (area.height-size.height)/3));
	}

	/** The converters' bar: setString shows the step below the bar instead of text inside it. */
	static final class Bar extends JProgressBar {
		private static final long serialVersionUID = 1L;
		private final JLabel status;

		Bar(JLabel status){
			super(0, 100);
			this.status = status;
		}

		@Override public void setString(String s){
			if(status==null){ super.setString(s); return; }
			String text = s==null||s.isEmpty() ? " " : s;
			if(text.equals(status.getText())) return; //変換中はブロックごとに同じ文字列が来る(数千枚のカードで毎回同期描画していた)
			status.setText(text);
			status.paintImmediately(0, 0, status.getWidth(), status.getHeight());
		}

		@Override public void paintImmediately(Rectangle r){
			//呼び出し側は親座標のgetBounds()を渡してくるので、常に全体を描く
			super.paintImmediately(0, 0, getWidth(), getHeight());
		}
	}

	/** The Windows common-control look (Metal's bar is a different colour and fills whatever height it is given). */
	static final class ClassicBarUI extends BasicProgressBarUI {
		private static final Color TRACK = new Color(0xE6, 0xE6, 0xE6);
		private static final Color BORDER = new Color(0xBC, 0xBC, 0xBC);
		private static final Color FILL = new Color(0x06, 0xB0, 0x25);

		@Override public Dimension getPreferredSize(JComponent c){
			return new Dimension(BAR_WIDTH, 15);
		}

		@Override public void paint(Graphics g, JComponent c){
			JProgressBar b = (JProgressBar)c;
			int w = c.getWidth(), h = c.getHeight();
			g.setColor(TRACK);
			g.fillRect(0, 0, w, h);
			g.setColor(BORDER);
			g.drawRect(0, 0, w-1, h-1);
			int range = Math.max(1, b.getMaximum()-b.getMinimum());
			int filled = (int)Math.round((w-2)*(double)Math.max(0, Math.min(range, b.getValue()-b.getMinimum()))/range);
			if(filled>0){
				g.setColor(FILL);
				g.fillRect(1, 1, filled, h-2);
			}
		}
	}

	// ------------------------------------------------------------------ wait cursor

	/** Restores what {@link #busy} changed. */
	interface Busy extends AutoCloseable { @Override void close(); }

	/**
	 * Shows the wait cursor over the card window and the launcher until closed. Any thread. Nested uses keep the cursor
	 * until the outermost one closes.
	 */
	static Busy busy(){
		if(java.awt.GraphicsEnvironment.isHeadless()) return () -> {};
		final java.util.List<Component> targets = new java.util.ArrayList<>();
		PCARD pc = PCARD.pc;
		if(pc!=null){ targets.add(pc); if(pc.mainPane!=null) targets.add(pc.mainPane); }
		if(GLauncher.instance!=null) targets.add(GLauncher.instance);
		final Cursor[] before = new Cursor[targets.size()];
		final Cursor wait = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);
		for(int i=0; i<targets.size(); i++){
			Component c = targets.get(i);
			before[i] = c.isCursorSet() ? c.getCursor() : null;
			setCursor(c, wait);
		}
		return () -> {
			for(int i=0; i<targets.size(); i++){
				Component c = targets.get(i);
				if(c.getCursor()==wait) setCursor(c, before[i]); //途中でスクリプト等が変えたカーソルはそのまま
			}
		};
	}

	/** The card pane keeps a handler's cursor for later (MyPanel.setCursor); the wait cursor is not one of those. */
	private static void setCursor(Component c, Cursor cursor){
		if(c instanceof MyPanel) ((MyPanel)c).setCursorNow(cursor);
		else c.setCursor(cursor);
	}
}

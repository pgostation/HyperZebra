import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The window of a stack open behind the card window (HyperCard 2.x: "go … in a new window", Open Stack with "Open stack in
 * new window", a second stack opened from the launcher, the Finder or a drop). As in HyperCard only the frontmost stack is
 * running: HyperZebra shows it in the card window (PCARD.pc), and every other open stack keeps a window like this one,
 * showing its card as it was left. A click on it brings that stack back into the card window, where this window is
 * (suspendStack to the stack on show, resumeStack to the one brought back: TTalk.goToStack2), and the stack left takes
 * a window of its own in turn. Its close box closes the stack (it was saved when it was left). The stacks themselves
 * are remembered in TTalk.windowStacks; this class only keeps their windows, keyed the same way (TTalk.windowStackKey).
 */
final class GStackWindow extends JFrame {
	private static final long serialVersionUID = 1L;

	/** The windows, back to front (EDT). */
	private static final LinkedHashMap<String, GStackWindow> windows = new LinkedHashMap<String, GStackWindow>();

	final String key;
	/** The card window's frame origin relative to the top left of what the picture shows (its root pane). */
	private final Point frameOffset;
	/** A click asked for this stack and it has not come back yet: further clicks wait. */
	private volatile boolean pending;
	/** When the window was put up: an activation right then is the window system's, not the user's. */
	private final long shownAt = System.currentTimeMillis();
	/** When the card window last had its frame re-created (hide/show titleBar): the activation it hands over meanwhile is no click. */
	private static volatile long cardWindowRebuiltAt;

	private GStackWindow(String key, String title, BufferedImage picture, Dimension size, Point frameOffset){
		super(title);
		this.key = key;
		this.frameOffset = frameOffset;
		setAutoRequestFocus(false); // shown behind the card window, which keeps the keys
		setResizable(false);
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		if(PCARD.pc!=null && PCARD.pc.getIconImages()!=null) setIconImages(PCARD.pc.getIconImages());
		JComponent view = new JComponent(){
			private static final long serialVersionUID = 1L;
			@Override protected void paintComponent(Graphics g){ g.drawImage(picture, 0, 0, getWidth(), getHeight(), null); }
		};
		view.setPreferredSize(size);
		view.addMouseListener(new MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){ bringBack(); }
		});
		setContentPane(view);
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosing(WindowEvent e){ closeStack(GStackWindow.this.key); }
			@Override public void windowActivated(WindowEvent e){
				// its title bar was clicked (or it was chosen from the system's window list) while the card window is on
				// show. The card window being hidden or rebuilt (Close Stack, hide titleBar) hands the activation over
				// to a window like this one too, and so may putting this window up: that is no request. A rebuilt card
				// window is on show again when that activation arrives (macOS 27: some 25-40 ms later), so it is told by the time
				PCARD pc = PCARD.pc;
				long now = System.currentTimeMillis();
				if(now-shownAt<500 || now-cardWindowRebuiltAt<1000) return;
				if(pc!=null && pc.isShowing() && !pc.isActive()) bringBack();
			}
		});
	}

	/** Brings this window's stack back into the card window (the script thread does it after what it is running). */
	void bringBack(){
		if(pending) return;
		pending = true;
		TTalk.resumeWindowStack(key);
	}

	/**
	 * The card window's frame was disposed and made again (hide/show titleBar, TTalk.setTitleBarVisible): the window system
	 * activated one of these windows meanwhile, and that activation, handled when the card window is on show again, would
	 * bring that window's stack back (hide titleBar in Other's openStack switched to Main). EDT, after the frame is back.
	 */
	static void cardWindowRebuilt(){ cardWindowRebuiltAt = System.currentTimeMillis(); }

	/** The stack could not be brought back: clicks are taken again. */
	static void notBroughtBack(String key){
		SwingUtilities.invokeLater(() -> { GStackWindow w = windows.get(key); if(w!=null) w.pending = false; });
	}

	/**
	 * The stack on show is about to leave the card window for another stack while staying open: a window showing the card
	 * window as it looks now takes its place (on the screen where the card window is, behind it). Any thread; nothing
	 * happens when there is no screen.
	 */
	static void leave(String key, String title){
		if(key==null || GraphicsEnvironment.isHeadless()) return;
		onEDT(() -> {
			PCARD pc = PCARD.pc;
			if(pc==null || !pc.isDisplayable()) return;
			//a hidden card window (hide window "000-Home" before go … in a new window, Chronicle of Ekan) leaves a hidden
			//window: show window "<that stack>" puts it up later
			boolean shown = pc.isShowing();
			Component content = pc.getRootPane(); //the card and, on Windows/Linux, the menu bar above it
			int w = content.getWidth(), h = content.getHeight();
			if(w<=0 || h<=0) return;
			BufferedImage picture = picture(content, w, h);
			Point frameAt = pc.getLocation();
			Point contentAt = SwingUtilities.convertPoint(content, 0, 0, pc);
			contentAt.translate(frameAt.x, frameAt.y);
			GStackWindow old = windows.remove(key);
			if(old!=null) old.dispose();
			GStackWindow window = new GStackWindow(key, title, picture, new Dimension(w, h), new Point(frameAt.x-contentAt.x, frameAt.y-contentAt.y));
			window.pack(); // the insets are known from here
			Insets in = window.getInsets();
			window.setLocation(contentAt.x-in.left, contentAt.y-in.top);
			windows.put(key, window);
			if(!shown) return;
			window.setVisible(true);
			pc.toFront();
		});
		GLauncher.showIfNothingOpen(); // the dock lists the stacks of the windows too
	}

	/** The card window as it looks now, at the screen's resolution (Retina: twice the pixels). */
	private static BufferedImage picture(Component content, int w, int h){
		double sx = 1, sy = 1;
		GraphicsConfiguration gc = content.getGraphicsConfiguration();
		if(gc!=null){ AffineTransform t = gc.getDefaultTransform(); sx = Math.max(1, t.getScaleX()); sy = Math.max(1, t.getScaleY()); }
		BufferedImage image = new BufferedImage((int)Math.ceil(w*sx), (int)Math.ceil(h*sy), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(content.getBackground()!=null ? content.getBackground() : java.awt.Color.WHITE);
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.scale(sx, sy);
		content.paint(g);
		g.dispose();
		return image;
	}

	/** The stack that was to leave stayed (the other stack could not be opened): its window goes again. */
	static void discard(String key){
		if(key==null) return;
		onEDT(() -> { GStackWindow w = windows.remove(key); if(w!=null) w.dispose(); });
		GLauncher.showIfNothingOpen();
	}

	/** Where the card window goes to show this window's stack again: where the window is now (it may have been dragged). Null: no window. */
	static Point cardWindowPlace(String key){
		if(key==null || GraphicsEnvironment.isHeadless()) return null;
		Point[] at = new Point[1];
		onEDT(() -> {
			GStackWindow w = windows.get(key);
			if(w!=null) at[0] = w.cardWindowPlace();
		});
		return at[0];
	}

	/** The stack of this window is in the card window again: the card window comes to the front and the window goes. */
	static void broughtBack(String key){
		if(key==null) return;
		onEDT(() -> {
			GStackWindow w = windows.remove(key);
			PCARD pc = PCARD.pc;
			if(pc!=null && pc.isShowing()){
				pc.toFront();
				pc.requestFocus();
			}
			if(w!=null) w.dispose();
		});
		GLauncher.showIfNothingOpen();
	}

	/**
	 * A stack opened in a new window: the card window moves down and right when it would hide one of the other windows
	 * exactly (stacks open centred on the screen, so two stacks of a size would be one on the other).
	 */
	static void cascade(){
		if(GraphicsEnvironment.isHeadless()) return;
		onEDT(() -> {
			PCARD pc = PCARD.pc;
			if(pc==null || !pc.isShowing() || windows.isEmpty()) return;
			Point at = pc.getLocation();
			int step = 22;
			for(int tries=0; tries<20 && covers(at); tries++) at = new Point(at.x+step, at.y+step);
			GraphicsConfiguration gc = pc.getGraphicsConfiguration();
			if(gc!=null){
				Rectangle sb = gc.getBounds();
				Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
				int right = sb.x+sb.width-in.right, bottom = sb.y+sb.height-in.bottom;
				if(at.x+pc.getWidth()>right) at.x = Math.max(sb.x+in.left, right-pc.getWidth());
				if(at.y+pc.getHeight()>bottom) at.y = Math.max(sb.y+in.top, bottom-pc.getHeight());
			}
			if(!at.equals(pc.getLocation())) pc.setLocation(at);
		});
	}
	/** Whether the card window placed at {@code at} would be where one of the windows shows its card (EDT). */
	private static boolean covers(Point at){
		for(GStackWindow w : windows.values()){
			Point p = w.cardWindowPlace();
			if(p!=null && Math.abs(p.x-at.x)<8 && Math.abs(p.y-at.y)<8) return true;
		}
		return false;
	}
	/** Where the card window would be to show this window's card where it is (EDT). Null when not on show. */
	private Point cardWindowPlace(){
		if(!isShowing()) return null;
		Point c = getContentPane().getLocationOnScreen();
		return new Point(c.x+frameOffset.x, c.y+frameOffset.y);
	}

	/** show/hide window "<stack>" of a stack open in another window (Chronicle of Ekan shows its 000-Home panel beside 002-Base). */
	static void setShown(String key, boolean shown){
		if(key==null || GraphicsEnvironment.isHeadless()) return;
		onEDT(() -> {
			GStackWindow w = windows.get(key);
			if(w==null || w.isVisible()==shown) return;
			w.setVisible(shown);
			PCARD pc = PCARD.pc;
			if(shown && pc!=null && pc.isShowing()) pc.toFront(); // behind the card window, which keeps the keys
		});
	}
	static boolean isShown(String key){
		boolean[] r = new boolean[1];
		if(key!=null && !GraphicsEnvironment.isHeadless()) onEDT(() -> { GStackWindow w = windows.get(key); r[0] = w!=null && w.isVisible(); });
		return r[0];
	}

	/** The close box of a stack's window, or "Close Stack" in the launcher's dock: the stack closes without coming back. */
	static void closeStack(String key){
		TTalk.forgetWindowStack(key);
		discard(key);
	}

	/** Every stack window goes (the card window closed, HyperZebra quits). */
	static void disposeAll(){
		if(GraphicsEnvironment.isHeadless()) return;
		onEDT(() -> {
			List<GStackWindow> all = new ArrayList<GStackWindow>(windows.values());
			windows.clear();
			for(GStackWindow w : all) w.dispose();
		});
		GLauncher.showIfNothingOpen();
	}

	/** The open windows (tests). */
	static int count(){
		int[] n = new int[1];
		onEDT(() -> n[0] = windows.size());
		return n[0];
	}

	private static void onEDT(Runnable r){
		if(SwingUtilities.isEventDispatchThread()){ r.run(); return; }
		try{ SwingUtilities.invokeAndWait(r); }
		catch(InterruptedException e){ Thread.currentThread().interrupt(); }
		catch(java.lang.reflect.InvocationTargetException e){ e.getCause().printStackTrace(); }
	}
}

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.RepaintManager;

/** {@link CardViews68k} on the desktop: HyperCard's windows as HyperZebra's Swing components show them (CardWindow68k's code before P4). */
final class CardViews68kSwing extends CardViews68k {
	@Override boolean cardOnShow(OStack stack) {
		PCARD pc = PCARD.pc;
		return pc != null && pc.stack == stack && pc.mainPane != null && pc.mainPane.getWidth() > 0 && pc.mainPane.getHeight() > 0;
	}
	@Override Object cardView() { PCARD pc = PCARD.pc; return pc == null ? null : pc.mainPane; }
	@Override int cardScale() { return PCARD.scalePercent(); }
	@Override int textHighlight() {
		java.awt.Color c = javax.swing.UIManager.getColor("TextPane.selectionBackground");
		return c == null ? -1 : c.getRGB() & 0xffffff;
	}
	@Override Object messageView() { GMsg msg = GMsg.msg; return msg == null ? null : (JComponent) msg.getContentPane(); }
	@Override int[] viewSize(Object view) { JComponent c = (JComponent) view; return new int[] {c.getWidth(), c.getHeight()}; }
	@Override boolean viewShowing(Object view) { return ((JComponent) view).isShowing(); }

	@Override int[] capture(Object view, int scale, int[] size, boolean bare) {
		JComponent component = (JComponent) view;
		final BufferedImage[] holder = new BufferedImage[1];
		PCARD.runOnEDT(() -> {
			int w = component.getWidth(), h = component.getHeight(); if (w <= 0 || h <= 0 || !component.isShowing()) return;
			BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image.createGraphics();
			BufferedImage drawn = CardWindow68k.overlay;
			try {
				if (bare) CardWindow68k.overlay = null; // on the event thread: no paint of the card sees it missing
				if (component == (PCARD.pc == null ? null : PCARD.pc.mainPane)) VEffect.paintCurrentPanel(g);
				else { RepaintManager manager = RepaintManager.currentManager(component); boolean buffered = manager.isDoubleBufferingEnabled(); manager.setDoubleBufferingEnabled(false);
					try { component.printAll(g); } finally { manager.setDoubleBufferingEnabled(buffered); } }
			} finally { g.dispose(); if (bare && CardWindow68k.overlay == null) CardWindow68k.overlay = drawn; }
			holder[0] = image;
		});
		return logicalPixels(holder[0], scale, size);
	}
	@Override int[] lockedPicture(Object view, int scale, int width, int height) {
		JComponent component = (JComponent) view;
		final BufferedImage[] holder = new BufferedImage[1];
		PCARD.runOnEDT(() -> {
			BufferedImage frozen = VEffect.oldoff; int w = component.getWidth(), h = component.getHeight();
			if (!HZ.lockedScreen || frozen == null || w <= 0 || h <= 0) return;
			BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image.createGraphics();
			try { g.drawImage(frozen, 0, 0, w, h, null); } finally { g.dispose(); }
			holder[0] = image;
		});
		int[] size = new int[2]; int[] pixels = logicalPixels(holder[0], scale, size);
		return pixels != null && size[0] == width && size[1] == height ? pixels : null;
	}
	private static int[] logicalPixels(BufferedImage image, int scale, int[] size) {
		if (image == null) return null;
		int w = DisplayZoom.unscale(image.getWidth(), scale), h = DisplayZoom.unscale(image.getHeight(), scale); if (w <= 0 || h <= 0) return null;
		int[] pixels = new int[w * h];
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = image.getRGB(Math.min(image.getWidth() - 1, DisplayZoom.scale(x, scale)), Math.min(image.getHeight() - 1, DisplayZoom.scale(y, scale))) & 0xffffff;
		size[0] = w; size[1] = h; return pixels;
	}

	@Override List<Object> pictureWindows() {
		List<Object> out = new ArrayList<Object>();
		List<OWindow> list; synchronized (OWindow.list) { list = new ArrayList<OWindow>(OWindow.list); }
		for (OWindow w : list) { GPictWindow g = w.gpw;
			if (g == null || w.movieHandle != null || g.label == null || !g.isDisplayable()) continue;
			out.add(g); }
		return out;
	}
	@Override String pictureName(Object picture) { GPictWindow g = (GPictWindow) picture; return g.name == null ? "" : g.name; }
	@Override Object pictureView(Object picture) { return ((GPictWindow) picture).label; }
	@Override boolean pictureUndecorated(Object picture) { return ((GPictWindow) picture).isUndecorated(); }
	@Override boolean pictureFramed(Object picture) {
		GPictWindow g = (GPictWindow) picture;
		return g.isUndecorated() && g.getContentPane() instanceof JComponent && ((JComponent) g.getContentPane()).getBorder() != null;
	}
	@Override boolean pictureDisplayable(Object picture) { return ((GPictWindow) picture).isDisplayable(); }
	@Override boolean pictureVisible(Object picture) { return ((GPictWindow) picture).isVisible(); }
	@Override int[] pictureSize(Object picture) {
		BufferedImage bi = ((GPictWindow) picture).label.bi;
		return bi == null ? null : new int[] {bi.getWidth(), bi.getHeight()};
	}
	@Override int[] picturePixels(Object picture, int width, int height) {
		GPictWindow g = (GPictWindow) picture;
		BufferedImage bi = g.label == null ? null : g.label.bi; if (bi == null) return null;
		int[] pixels = new int[width * height];
		for (int y = 0; y < height && y < bi.getHeight(); y++) for (int x = 0; x < width && x < bi.getWidth(); x++) { int p = bi.getRGB(x, y); pixels[y * width + x] = (p >>> 24) < 0x80 ? 0xffffff : p & 0xffffff; }
		return pixels;
	}
	@Override int[] pictureOnScreen(Object picture) {
		MyLabel3 label = ((GPictWindow) picture).label;
		if (label == null || !label.isShowing()) return null;
		java.awt.Point at = label.getLocationOnScreen();
		return new int[] {at.x, at.y, label.getWidth(), label.getHeight()};
	}

	@Override void installMessageGlass() {
		PCARD.runOnEDT(() -> {
			GMsg msg = GMsg.msg; if (msg == null || msg.getGlassPane() instanceof MessageGlass) return;
			JComponent glass = new MessageGlass(); glass.setOpaque(false); msg.setGlassPane(glass); glass.setVisible(true);
		});
	}
	@Override void repaintMessage() { GMsg m = GMsg.msg; if (m != null) m.getGlassPane().repaint(); }
	/** The message box shows {@link CardWindow68k#messageOverlay} through a glass pane that lets mouse events through to the field. */
	private static final class MessageGlass extends JComponent {
		@Override public boolean contains(int x, int y) { return false; } // never the target of a mouse event
		@Override protected void paintComponent(Graphics g) { BufferedImage image = CardWindow68k.messageOverlay; if (image != null) g.drawImage(image, 0, GMsg.BAR_HEIGHT, null); }
	}
	@Override Object installPictureGlass(Object picture) { return PictureGlass.install((GPictWindow) picture); }
	@Override void showPictureOverlay(Object glass, BufferedImage drawn) { if (glass != null) ((PictureGlass) glass).show(drawn); }
	/** A picture window's glass pane: what externals drew into its port, over the picture (at the picture's place and zoom). */
	static final class PictureGlass extends JComponent {
		private static final long serialVersionUID = 1L;
		private final GPictWindow window; volatile BufferedImage image;
		private PictureGlass(GPictWindow window) { this.window = window; }
		static PictureGlass install(GPictWindow window) {
			final PictureGlass[] made = new PictureGlass[1];
			PCARD.runOnEDT(() -> {
				if (window.getGlassPane() instanceof PictureGlass) { made[0] = (PictureGlass) window.getGlassPane(); return; }
				PictureGlass glass = new PictureGlass(window); glass.setOpaque(false); window.setGlassPane(glass); glass.setVisible(true); made[0] = glass;
			});
			return made[0];
		}
		void show(BufferedImage drawn) { image = drawn; repaint(); }
		@Override public boolean contains(int x, int y) { return false; } // clicks go to the picture
		@Override protected void paintComponent(Graphics g) {
			BufferedImage drawn = image; MyLabel3 label = window.label; if (drawn == null || label == null) return;
			java.awt.Point at = javax.swing.SwingUtilities.convertPoint(label, 0, 0, this); double z = PCARD.scale();
			Graphics2D g2 = (Graphics2D) g.create();
			try { g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				g2.drawImage(drawn, at.x - (int) Math.round(label.scrollX * z), at.y - (int) Math.round(label.scrollY * z), (int) Math.round(drawn.getWidth() * z), (int) Math.round(drawn.getHeight() * z), null); }
			finally { g2.dispose(); }
		}
	}
	@Override void repaintCardThroughLock() { PCARD pc = PCARD.pc; if (pc != null && pc.mainPane != null) CardRepaintManager.repaintThroughLock(pc.mainPane); }
	@Override void repaintCard() { PCARD pc = PCARD.pc; if (pc != null && pc.mainPane != null) pc.mainPane.repaint(); }
	@Override void holdScreen() {
		PCARD.runOnEDT(() -> {
			PCARD pc = PCARD.pc; if (pc == null || pc.mainPane == null) return;
			int w = pc.mainPane.getWidth(), h = pc.mainPane.getHeight(); if (w <= 0 || h <= 0) return;
			BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image.createGraphics();
			try { VEffect.paintCurrentPanel(g); } finally { g.dispose(); }
			CardWindow68k.held = image;
		});
	}
	@Override void releaseScreen() {
		PCARD.runOnEDT(() -> {
			CardWindow68k.held = null;
			PCARD pc = PCARD.pc; if (pc != null && pc.mainPane != null) pc.mainPane.repaint();
		});
	}

	@Override void setCardTitle(OStack stack, String title) {
		javax.swing.SwingUtilities.invokeLater(() -> {
			PCARD pc = PCARD.pc; if (pc == null || pc.stack != stack) return;
			pc.setTitle(HZ.editBackground ? "/// " + title + " ///" : title);
		});
	}
	@Override void showCardWindow(OStack stack, boolean visible) {
		javax.swing.SwingUtilities.invokeLater(() -> {
			PCARD pc = PCARD.pc; if (pc == null || pc.stack != stack || pc.isVisible() == visible) return;
			if (!visible) GUI.mouseDown = false; // the hidden window never gets the mouse-up (as TUtil's hide window of the card window)
			pc.setVisible(visible);
		});
	}
	@Override int toPhysical(int logical) { return PCARD.toPhysical(logical); }
	@Override void shapeCardWindow(java.awt.geom.Area shape, QdRegion struc) {
		PCARD.runOnEDT(() -> {
			PCARD pc = PCARD.pc; if (pc == null || pc.mainPane == null || !pc.isUndecorated()) return;
			try {
				if (shape == null) { pc.setShape(null); CardWindow68k.shapeShown = null; return; }
				java.awt.Point origin = javax.swing.SwingUtilities.convertPoint(pc.mainPane, 0, 0, pc);
				shape.transform(java.awt.geom.AffineTransform.getTranslateInstance(origin.x, origin.y));
				pc.setShape(shape); CardWindow68k.shapeShown = struc;
			} catch (UnsupportedOperationException | java.awt.IllegalComponentStateException e) {} // no shaped windows on this system
		});
	}
}

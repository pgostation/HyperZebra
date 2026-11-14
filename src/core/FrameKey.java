/**
 * A Gfx that draws nothing: it sums up the drawing calls the card makes (CardRenderer.screen) into a key, so the player
 * can tell a frame that would come out the same as the last one and not draw or send it (PlayerLoop.draw). An idle handler
 * that locks, shows what already shows and unlocks 60 times a second (MONOKLOCK) sent ~100 frames a second for nothing.
 * Text sizes come from the real Gfx. Reading pixels back, or a picture whose pixels the real Gfx has not taken yet, makes
 * the frame one to draw (unsure).
 */
final class FrameKey implements Gfx {
	private final Gfx real;
	private long key = 1125899906842597L;
	/** the frame can't be told from its calls: draw it */
	boolean unsure;

	FrameKey(Gfx real) { this.real = real; }

	long key() { return key; }
	private void mix(long v) { key = (key ^ v) * 0x100000001B3L + 31; }
	private void op(int code, int a, int b, int c, int d) { mix(code); mix(a); mix(b); mix(c); mix(d); }

	@Override public int width() { return real.width(); }
	@Override public int height() { return real.height(); }
	@Override public void save() { mix(1); }
	@Override public void restore() { mix(2); }
	@Override public void clip(int x, int y, int w, int h) { op(3, x, y, w, h); }
	@Override public void translate(int dx, int dy) { op(4, dx, dy, 0, 0); }
	@Override public void setAlpha(float alpha) { op(5, Float.floatToIntBits(alpha), 0, 0, 0); }
	@Override public void setColor(int argb) { op(6, argb, 0, 0, 0); }
	@Override public void fillRect(int x, int y, int w, int h) { op(7, x, y, w, h); }
	@Override public void fillRoundRect(int x, int y, int w, int h, int arc) { op(8, x, y, w, h); mix(arc); }
	@Override public void fillOval(int x, int y, int w, int h) { op(9, x, y, w, h); }
	@Override public void drawRect(int x, int y, int w, int h) { op(10, x, y, w, h); }
	@Override public void drawRoundRect(int x, int y, int w, int h, int arc) { op(11, x, y, w, h); mix(arc); }
	@Override public void drawOval(int x, int y, int w, int h) { op(12, x, y, w, h); }
	@Override public void drawLine(int x1, int y1, int x2, int y2) { op(13, x1, y1, x2, y2); }
	@Override public void invertRect(int x, int y, int w, int h) { op(14, x, y, w, h); }
	@Override public void invertRoundRect(int x, int y, int w, int h, int arc) { op(15, x, y, w, h); mix(arc); }
	@Override public void invertOval(int x, int y, int w, int h) { op(16, x, y, w, h); }
	@Override public void fillCheckerboard(int x, int y, int w, int h) { op(17, x, y, w, h); }
	@Override public void drawImage(HzImage img, int x, int y) { image(img); op(18, x, y, 0, 0); }
	@Override public void drawImage(HzImage img, int x, int y, int w, int h, boolean smooth) { image(img); op(19, x, y, w, h); mix(smooth ? 1 : 0); }
	/** a picture by which one it is and its changes (HzImage.changed/repainted: the real Gfx's copy is made again then) */
	private void image(HzImage img) {
		if (img == null) { mix(0); return; }
		if (img.drawable == null || img.drawnVersion != img.version) unsure = true;
		mix(System.identityHashCode(img)); mix(img.version); mix(img.width); mix(img.height);
	}
	@Override public void setFont(String name, int size, int style) { real.setFont(name, size, style); mix(name == null ? 0 : name.hashCode()); mix(size); mix(style); }
	@Override public int textWidth(String s) { return real.textWidth(s); }
	@Override public int ascent() { return real.ascent(); }
	@Override public int descent() { return real.descent(); }
	@Override public int leading() { return real.leading(); }
	@Override public void drawText(String s, int x, int y) { op(20, x, y, s == null ? 0 : s.hashCode(), s == null ? 0 : s.length()); }
	@Override public double pixelScale() { return real.pixelScale(); }
	@Override public int[] getPixels(int x, int y, int w, int h) { unsure = true; return new int[Math.max(0, w) * Math.max(0, h)]; }
	@Override public void putPixels(int[] argb, int x, int y, int w, int h) { op(21, x, y, w, h); mix(java.util.Arrays.hashCode(argb)); }
}

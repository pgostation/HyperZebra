/**
 * What the card renderer draws with (P2): a canvas of card pixels. The browser's is the worker's OffscreenCanvas
 * (tools/wasm-core/web-src/CanvasGfx), the JVM's a BufferedImage (tools/wasm-core/Java2DGfx, for checks against the
 * desktop). Colours are 0xAARRGGBB; shapes are filled or framed inside their rectangle, without antialiasing.
 */
interface Gfx {
	int width();
	int height();

	void save();
	void restore();
	/** Drawing from now on only inside this rectangle (and the clips before it). */
	void clip(int x, int y, int w, int h);
	void translate(int dx, int dy);
	/** Opacity of what is drawn from now on, 0-1. */
	void setAlpha(float alpha);

	void setColor(int argb);
	void fillRect(int x, int y, int w, int h);
	/** A rounded rectangle whose corners are arc pixels across. */
	void fillRoundRect(int x, int y, int w, int h, int arc);
	void fillOval(int x, int y, int w, int h);
	/** A one-pixel frame on the edge pixels of the rectangle. */
	void drawRect(int x, int y, int w, int h);
	void drawRoundRect(int x, int y, int w, int h, int arc);
	void drawOval(int x, int y, int w, int h);
	void drawLine(int x1, int y1, int x2, int y2);

	/** The colours under the rectangle inverted (black to white and back): a hilite. */
	void invertRect(int x, int y, int w, int h);
	void invertRoundRect(int x, int y, int w, int h, int arc);
	void invertOval(int x, int y, int w, int h);
	/** The pixels where (card x + card y) is even, in the colour: HyperCard's grey over a disabled button. */
	void fillCheckerboard(int x, int y, int w, int h);

	/** The picture at x,y, unscaled. */
	void drawImage(HzImage img, int x, int y);
	/** The picture into the rectangle, scaled (smooth: interpolated, else nearest pixel). */
	void drawImage(HzImage img, int x, int y, int w, int h, boolean smooth);

	/** The font to write with: a classic Mac font name (HzFonts maps it), its size in card pixels, HyperCard's textStyle
	 *  bits (1 bold, 2 italic; the others are drawn by the renderer). */
	void setFont(String name, int size, int style);
	int textWidth(String s);
	int ascent();
	int descent();
	int leading();
	/** The text with its baseline at y. */
	void drawText(String s, int x, int y);

	/** Screen pixels a card pixel takes (the page's fit to the window and the display's density): pictures drawn from vectors
	 *  (shapes) are drawn at that size to stay sharp. */
	default double pixelScale() { return 1; }

	/** The pixels of the rectangle (ARGB), for visual effects. */
	int[] getPixels(int x, int y, int w, int h);
	void putPixels(int[] argb, int x, int y, int w, int h);
}

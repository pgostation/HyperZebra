/**
 * A picture of the core (P2): ARGB pixels (0xAARRGGBB, rows top to bottom). The card renderer draws these through Gfx;
 * a Gfx keeps its own copy for drawing in {@link #drawable} (an OffscreenCanvas in the browser, a BufferedImage on the
 * JVM), made once and dropped by {@link #changed}.
 */
final class HzImage {
	final int width, height;
	final int[] argb;
	/** the Gfx's own form of these pixels (made on first draw) */
	Object drawable;

	HzImage(int width, int height) { this(width, height, new int[width * height]); }
	HzImage(int width, int height, int[] argb) { this.width = width; this.height = height; this.argb = argb; }

	int get(int x, int y) { return argb[y * width + x]; }
	void set(int x, int y, int c) { argb[y * width + x] = c; }
	/** The pixels changed: the Gfx makes its copy again. */
	void changed() { drawable = null; }
	/** Counts {@link #repainted} calls; {@link #drawnVersion} is the count the Gfx's copy holds. */
	int version, drawnVersion;
	/** The pixels changed and the Gfx's copy takes them in place (pictures redrawn many times a second, as what 68K externals
	 *  draw over the card: a new copy each time made the browser run out of canvases). */
	void repainted() { version++; }
	/** Whether any pixel is not fully opaque. */
	boolean hasTransparency() { for (int c : argb) if ((c >>> 24) != 0xFF) return true; return false; }
}

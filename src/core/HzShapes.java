/**
 * Shapes (OShape) in the browser player, drawn as the desktop's ShapeView draws them: OShape.renderOpaque - its shadows,
 * outlines, fill (colour, linear or radial gradient, texture) and stroke, antialiased and rotated - into a picture of the
 * shape's rectangle with its padding around it, at the screen's pixel scale (up to 4, as OShape.render), then that picture
 * faded by the shape's opacity over the card at (left, top) less the padding. In the browser, java.awt is the web class
 * library's (THzGraphics: the paints, ConvolveOp for blurred shadows, glyph ink areas for text shapes); on the JVM it is
 * Java2D, so PlayerSim and RenderCheck draw them as the desktop does.
 *
 * A shape's picture is kept until something it is drawn from changes (moving it or fading it needs no new one); a picture of
 * the same size is drawn over in place (HzImage.repainted), so an animation does not make a new canvas a frame. The pictures
 * kept are at most KEEP_BYTES, the ones drawn least recently dropped first. Not drawn here: SVG filters (the player reads
 * none: UiHost.readSvgFilter), and a blurred shadow of a rotated shape is placed unrotated (the class library draws images
 * axis-aligned).
 */
final class HzShapes {
	private HzShapes() {}

	static final long KEEP_BYTES = 96L << 20;
	private static final class Kept { String key; HzImage image; int pad; long bytes; }
	private static final java.util.LinkedHashMap<OShape, Kept> kept = new java.util.LinkedHashMap<>(64, 0.75f, true);
	private static long keptBytes;

	static void draw(Gfx g, OShape s) {
		if (s.opacity <= 0 || s.width < 0 || s.height < 0) return;
		double scale = Math.max(1, Math.min(4, g.pixelScale()));
		Kept k = picture(s, scale);
		if (k == null) return;
		int x = s.left - k.pad, y = s.top - k.pad, w = s.width + 2 * k.pad, h = s.height + 2 * k.pad;
		g.save();
		if (s.opacity < 1) g.setAlpha(s.opacity);
		if (k.image.width == w && k.image.height == h) g.drawImage(k.image, x, y);
		else g.drawImage(k.image, x, y, w, h, true);
		g.restore();
	}

	/** The shape's picture at that scale: the kept one, or drawn now */
	private static Kept picture(OShape s, double scale) {
		String key = key(s, scale);
		Kept k = kept.get(s);
		if (k != null && k.key.equals(key)) return k;
		int pad = s.padding();
		int iw = (int) Math.ceil((s.width + 2 * pad) * scale), ih = (int) Math.ceil((s.height + 2 * pad) * scale);
		if (iw <= 0 || ih <= 0) return null;
		if ((long) iw * ih > 16_000_000) { // (as OShape.render: too large to draw apart at this scale) once at 1
			scale = 1; iw = s.width + 2 * pad; ih = s.height + 2 * pad;
			if ((long) iw * ih > 16_000_000) return null;
		}
		java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g2 = bi.createGraphics();
		g2.scale(scale, scale);
		g2.translate(pad, pad);
		try { s.renderOpaque(g2); }
		catch (RuntimeException e) { System.out.println("Shape " + s.id + " not drawn: " + e); }
		finally { g2.dispose(); }
		int[] px = bi.getRGB(0, 0, iw, ih, null, 0, iw);
		if (k != null && k.image.width == iw && k.image.height == ih) {
			System.arraycopy(px, 0, k.image.argb, 0, px.length);
			k.image.repainted();
		} else {
			if (k != null) keptBytes -= k.bytes;
			k = new Kept();
			k.image = new HzImage(iw, ih, px);
			k.bytes = 4L * iw * ih;
			keptBytes += k.bytes;
			kept.put(s, k);
		}
		k.key = key;
		k.pad = pad;
		// the least recently drawn go (this one is the most recent)
		for (java.util.Iterator<java.util.Map.Entry<OShape, Kept>> it = kept.entrySet().iterator(); keptBytes > KEEP_BYTES && it.hasNext(); ) {
			java.util.Map.Entry<OShape, Kept> e = it.next();
			if (e.getValue() == k) break;
			keptBytes -= e.getValue().bytes;
			it.remove();
		}
		return k;
	}

	/** Everything the shape's picture is drawn from (not its place on the card, not its opacity) */
	static String key(OShape s, double scale) {
		StringBuilder b = new StringBuilder(96);
		b.append(scale).append('|').append(s.kind).append('|').append(s.width).append('x').append(s.height).append('|').append(s.rotation)
			.append('|').append(s.pathData).append('|').append(s.text).append('|').append(s.fontFamily).append(s.fontSize).append(s.bold)
			.append('|').append(s.sides).append('|').append(rgb(s.fillColor)).append(rgb(s.strokeColor)).append(s.strokeWidth)
			.append('|').append(rgb(s.gradientStart)).append(rgb(s.gradientEnd)).append(s.radialGradient)
			.append(s.gradientX1).append(',').append(s.gradientY1).append(',').append(s.gradientX2).append(',').append(s.gradientY2)
			.append('|').append(System.identityHashCode(s.texturePng)).append(s.stretchTexture);
		for (OShape.Outline o : s.outlines) b.append("|o").append(o.width).append(rgb(o.color));
		for (OShape.Shadow d : s.shadows) b.append("|s").append(d.dx).append(',').append(d.dy).append(',').append(d.blur).append(',').append(d.spread).append(rgb(d.color));
		return b.toString();
	}
	private static String rgb(java.awt.Color c) { return c == null ? "n;" : Integer.toHexString(c.getRGB()) + ";"; }

	/** Forgets the kept pictures (a stack closed) */
	static void clear() { kept.clear(); keptBytes = 0; }
}

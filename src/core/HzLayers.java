/**
 * A card's or background's paint layers (OCardBase.layers, PaintLayer) in the browser player: its picture with the visible
 * layers over it, bottom first, as the desktop shows them in browse mode (OCardBase.composite = PaintLayers.flatten): each
 * layer's picture (BMAP_<id>_L<n>.png in the stack) blended in with its blend mode and opacity, clipped by the opaque pixels
 * of the layer directly below it (the picture for the first) when clipToBelow; a clip with nothing below shows nothing.
 * The blend modes are the paint tools' (MyPanel.blendPixel): Normal, Blend, Add, Subtract, Multiply, Screen, Darken, Lighten,
 * Difference, Hue, Color, Saturation, Luminosity; a mode mixes with the colour under it as far as that is opaque, then the
 * result goes over it (PaintLayers.blendPixelOver). The flattened picture is kept until the layers or the picture change.
 */
final class HzLayers {
	private HzLayers() {}

	private static final class Kept { HzImage base; int version; HzImage flat; }
	private static final java.util.WeakHashMap<OCardBase, Kept> kept = new java.util.WeakHashMap<>();

	/** The picture as browse mode shows it: with its layers over it, or the picture itself when there are none. */
	static HzImage picture(OCardBase cb) {
		HzImage base = HzImages.picture(cb);
		if (cb == null || cb.layers == null || cb.layers.isEmpty()) return base;
		Kept k = kept.get(cb);
		if (k != null && k.base == base && k.version == cb.layerVersion) return k.flat;
		int w = cb.stack != null ? cb.stack.width : base != null ? base.width : 1, h = cb.stack != null ? cb.stack.height : base != null ? base.height : 1;
		java.util.List<HzImage> pictures = new java.util.ArrayList<>();
		for (PaintLayer l : cb.layers) pictures.add(l.fileName == null || cb.stack == null ? null : HzImages.file(cb.stack, l.fileName));
		HzImage flat = flatten(base, cb.layers, pictures, Math.max(1, w), Math.max(1, h));
		if (k == null) { k = new Kept(); kept.put(cb, k); }
		k.base = base; k.version = cb.layerVersion; k.flat = flat;
		return flat;
	}

	/** base (null: transparent) with the layers over it (pictures[i] is layer i's, null when it has none) */
	static HzImage flatten(HzImage base, java.util.List<PaintLayer> layers, java.util.List<HzImage> pictures, int w, int h) {
		HzImage out = new HzImage(w, h);
		if (base != null) for (int y = 0; y < Math.min(h, base.height); y++) System.arraycopy(base.argb, y * base.width, out.argb, y * w, Math.min(w, base.width));
		HzImage below = base;
		for (int i = 0; i < layers.size(); i++) {
			PaintLayer l = layers.get(i);
			HzImage p = pictures.get(i);
			if (l.visible && p != null && !(l.clipToBelow && below == null)) blendOnto(out, p, l.blend, l.opacity, l.clipToBelow ? below : null);
			below = p;
		}
		return out;
	}

	/** src over dst with the mode and opacity (0-100), only where clip is opaque (scaled by its alpha) when there is one */
	static void blendOnto(HzImage dst, HzImage src, int mode, int opacity, HzImage clip) {
		int w = Math.min(dst.width, src.width), h = Math.min(dst.height, src.height);
		float op = Math.max(0, Math.min(100, opacity)) / 100f;
		float[] hsb = new float[3], hsb2 = new float[3];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int sp = src.argb[y * src.width + x], sa = sp >>> 24;
				if (sa == 0) continue;
				float a = sa / 255f * op;
				if (clip != null) {
					if (x >= clip.width || y >= clip.height) continue;
					a *= (clip.argb[y * clip.width + x] >>> 24) / 255f;
				}
				if (a <= 0f) continue;
				int di = y * dst.width + x;
				dst.argb[di] = blendPixelOver(dst.argb[di], sp, a, mode, hsb, hsb2);
			}
		}
	}

	/** One pixel: the source's colour mixed by the mode with the destination's (as far as that is opaque), then over it with alpha a. */
	static int blendPixelOver(int dp, int sp, float a, int mode, float[] hsb, float[] hsb2) {
		float ad = (dp >>> 24) / 255f;
		int srgb = sp & 0xFFFFFF, drgb = dp & 0xFFFFFF, brgb = srgb;
		if (mode != PaintLayer.NORMAL && ad > 0f) {
			int mixed = mode == BLEND ? srgb : blendPixel(mode, srgb, drgb, hsb, hsb2);
			brgb = ad >= 1f ? mixed : lerpRgb(srgb, mixed, ad);
		}
		float ao = a + ad * (1f - a);
		if (ao <= 0f) return 0;
		float wS = a / ao, wD = ad * (1f - a) / ao;
		int r = Math.round(((brgb >> 16) & 0xFF) * wS + ((drgb >> 16) & 0xFF) * wD);
		int g = Math.round(((brgb >> 8) & 0xFF) * wS + ((drgb >> 8) & 0xFF) * wD);
		int b = Math.round((brgb & 0xFF) * wS + (drgb & 0xFF) * wD);
		return (Math.round(ao * 255) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	// MyPanel.blendMode
	static final int BLEND = 1, ADD = 2, SUBTRACT = 3, MULTIPLY = 4, SCREEN = 5, DARKEN = 6, LIGHTEN = 7, DIFFERENCE = 8,
		HUE = 9, COLOR = 10, SATURATION = 11, LUMINOSITY = 12;

	/** MyPanel.blendPixel at level 100: the mode's colour from the source's and the surface's (RGB, no alpha) */
	static int blendPixel(int mode, int src, int sfc, float[] hsb, float[] sfcHsb) {
		int red = (src >> 16) & 0xFF, green = (src >> 8) & 0xFF, blue = src & 0xFF;
		int sr = (sfc >> 16) & 0xFF, sg = (sfc >> 8) & 0xFF, sb = sfc & 0xFF;
		switch (mode) {
			case ADD: return (clamp(sr + red) << 16) | (clamp(sg + green) << 8) | clamp(sb + blue);
			case SUBTRACT: return (clamp(sr - (255 - red)) << 16) | (clamp(sg - (255 - green)) << 8) | clamp(sb - (255 - blue));
			case MULTIPLY: return (clamp(sr * red / 255) << 16) | (clamp(sg * green / 255) << 8) | clamp(sb * blue / 255);
			case SCREEN: return (clamp(sr + red - 255) << 16) | (clamp(sg + green - 255) << 8) | clamp(sb + blue - 255);
			case DARKEN: return (sr + sg + sb < red + green + blue ? sfc : src) & 0xFFFFFF;
			case LIGHTEN: return (sr + sg + sb > red + green + blue ? sfc : src) & 0xFFFFFF;
			case DIFFERENCE: return (Math.abs(sr - red) << 16) | (Math.abs(sg - green) << 8) | Math.abs(sb - blue);
			case HUE: case COLOR: case SATURATION: case LUMINOSITY: {
				rgbToHsb(red, green, blue, hsb);
				rgbToHsb(sr, sg, sb, sfcHsb);
				if (mode == HUE) sfcHsb[0] = hsb[0];
				else if (mode == COLOR) { sfcHsb[0] = hsb[0]; sfcHsb[1] = hsb[1]; }
				else if (mode == SATURATION) sfcHsb[1] = hsb[1];
				else sfcHsb[2] = hsb[2];
				return hsbToRgb(sfcHsb[0], sfcHsb[1], sfcHsb[2]) & 0xFFFFFF;
			}
			default: return src;
		}
	}

	private static int lerpRgb(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
	}
	private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

	/** java.awt.Color.RGBtoHSB */
	static void rgbToHsb(int r, int g, int b, float[] hsb) {
		int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		float bright = max / 255f, sat = max != 0 ? (float) (max - min) / max : 0, hue;
		if (sat == 0) hue = 0;
		else {
			float rc = (float) (max - r) / (max - min), gc = (float) (max - g) / (max - min), bc = (float) (max - b) / (max - min);
			if (r == max) hue = bc - gc; else if (g == max) hue = 2f + rc - bc; else hue = 4f + gc - rc;
			hue /= 6f;
			if (hue < 0) hue += 1f;
		}
		hsb[0] = hue; hsb[1] = sat; hsb[2] = bright;
	}
	/** java.awt.Color.HSBtoRGB */
	static int hsbToRgb(float hue, float saturation, float brightness) {
		int r = 0, g = 0, b = 0;
		if (saturation == 0) { r = g = b = (int) (brightness * 255f + 0.5f); }
		else {
			float h = (hue - (float) Math.floor(hue)) * 6f, f = h - (float) Math.floor(h);
			float p = brightness * (1f - saturation), q = brightness * (1f - saturation * f), t = brightness * (1f - saturation * (1f - f));
			switch ((int) h) {
				case 0: r = (int) (brightness * 255f + 0.5f); g = (int) (t * 255f + 0.5f); b = (int) (p * 255f + 0.5f); break;
				case 1: r = (int) (q * 255f + 0.5f); g = (int) (brightness * 255f + 0.5f); b = (int) (p * 255f + 0.5f); break;
				case 2: r = (int) (p * 255f + 0.5f); g = (int) (brightness * 255f + 0.5f); b = (int) (t * 255f + 0.5f); break;
				case 3: r = (int) (p * 255f + 0.5f); g = (int) (q * 255f + 0.5f); b = (int) (brightness * 255f + 0.5f); break;
				case 4: r = (int) (t * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (brightness * 255f + 0.5f); break;
				case 5: r = (int) (brightness * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (q * 255f + 0.5f); break;
				default: break;
			}
		}
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Whole-image operations for scripts: imageFilter (a pixel script, TalkPixelScript), imageCombine, imageCreate, imageSize,
 * and the screen effects (the screenFilter and screenOverlay properties) that show the card through a pixel script
 * without changing the stack.
 * Images go in and out as image file data (TalkBinary: PNG out). A decoded picture is kept for the exact string it came
 * from or went out as, so a chain of calls, or the same sprite every frame, is not decoded again.
 */
final class TalkImage {
	private TalkImage() {}

	static final int MAX_SIDE = 16384;
	static final long MAX_PIXELS = 50_000_000L;

	// ---- data <-> pictures ----

	/** The pictures of recent image data, by the identity of the string (a variable gives its own string back) */
	private static final int CACHE = 12;
	private static final String[] cachedData = new String[CACHE];
	private static final BufferedImage[] cachedImage = new BufferedImage[CACHE];
	private static int nextCache;

	private static synchronized BufferedImage cached(String data) {
		for(int i=0; i<CACHE; i++) if(cachedData[i] == data) return cachedImage[i];
		return null;
	}

	private static synchronized void remember(String data, BufferedImage image) {
		cachedData[nextCache] = data;
		cachedImage[nextCache] = image;
		nextCache = (nextCache + 1) % CACHE;
	}

	/** The picture in image data as TYPE_INT_ARGB, not to be changed (it may be shared through the cache) */
	static BufferedImage image(String data, String function) throws xTalkException {
		if(data == null || data.isEmpty()) throw new xTalkException("{0}: not image data (PNG, JPEG, GIF, BMP or PICT)", function);
		BufferedImage hit = cached(data);
		if(hit != null) return hit;
		BufferedImage read = TalkBinary.image(TalkBinary.bytes(data));
		if(read == null) throw new xTalkException("{0}: not image data (PNG, JPEG, GIF, BMP or PICT)", function);
		if((long)read.getWidth() * read.getHeight() > MAX_PIXELS) throw new xTalkException("{0}: the image is too large", function);
		BufferedImage argb = argb(read);
		remember(data, argb);
		return argb;
	}

	static BufferedImage argb(BufferedImage read) {
		if(read.getType() == BufferedImage.TYPE_INT_ARGB) return read;
		BufferedImage argb = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = argb.createGraphics();
		g.drawImage(read, 0, 0, null);
		g.dispose();
		return argb;
	}

	/** A TYPE_INT_ARGB copy no one else changes (for data(): it is kept with the data) */
	static BufferedImage ownCopy(BufferedImage img) {
		BufferedImage out = blank(img.getWidth(), img.getHeight());
		Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return out;
	}

	/** PNG data of the picture (fast compression: these are often steps to the next call), remembered with it */
	static String data(BufferedImage image) throws xTalkException {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(image.getWidth() * image.getHeight() + 1024);
			javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("png").next();
			javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.9f);
			try(javax.imageio.stream.ImageOutputStream ios = new javax.imageio.stream.MemoryCacheImageOutputStream(out)) {
				writer.setOutput(ios);
				writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
			}finally{ writer.dispose(); }
			String data = TalkBinary.string(out.toByteArray());
			remember(data, image);
			return data;
		}catch(IOException e){ throw new xTalkException("arrayToImage: cannot write {0}", "png"); }
	}

	private static int[] pixels(BufferedImage image) { return ((DataBufferInt)image.getRaster().getDataBuffer()).getData(); }

	private static BufferedImage blank(int w, int h) { return new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB); }

	private static BufferedImage copy(BufferedImage src) {
		BufferedImage out = blank(src.getWidth(), src.getHeight());
		System.arraycopy(pixels(src), 0, pixels(out), 0, src.getWidth() * src.getHeight());
		return out;
	}

	// ---- the functions ----

	/** imageFilter(data, script [, width, height]): the pixel script (TalkPixelScript) for every pixel of the result */
	static String imageFilter(String[] params) throws xTalkException {
		String usage = "imageFilter(data, \"put 255 - r into r\" [, width, height])";
		if(params == null || (params.length != 2 && params.length != 4)) throw new xTalkException("Wrong number of arguments for {0}: {1}", "imageFilter", usage);
		BufferedImage src = image(params[0], "imageFilter");
		TalkPixelScript script = TalkPixelScript.compile(params[1]);
		int w = src.getWidth(), h = src.getHeight();
		if(params.length == 4) {
			w = whole(params[2], "imageFilter"); h = whole(params[3], "imageFilter");
			if(w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long)w*h > MAX_PIXELS) throw new xTalkException("imageCreate: the image is too large or empty: {0} x {1}", w, h);
		}
		return data(script.run(src, w, h, 1));
	}

	/** imageCombine(base, top [, x, y | "x,y" [, mode [, opacity]]]): top drawn on base with its top left at x,y */
	static String imageCombine(String[] params) throws xTalkException {
		String usage = "imageCombine(base, top [, x, y [, \"multiply\" [, 50]]])";
		if(params == null || params.length < 2 || params.length > 6) throw new xTalkException("Wrong number of arguments for {0}: {1}", "imageCombine", usage);
		BufferedImage base = image(params[0], "imageCombine"), top = image(params[1], "imageCombine");
		int i = 2, x = 0, y = 0;
		if(i < params.length) {
			if(params[i].contains(",")) {
				String[] p = params[i].split(",");
				if(p.length != 2) throw new xTalkException("Wrong number of arguments for {0}: {1}", "imageCombine", usage);
				x = whole(p[0], "imageCombine"); y = whole(p[1], "imageCombine");
				i++;
			}else {
				if(i+1 >= params.length) throw new xTalkException("Wrong number of arguments for {0}: {1}", "imageCombine", usage);
				x = whole(params[i], "imageCombine"); y = whole(params[i+1], "imageCombine");
				i += 2;
			}
		}
		String mode = i < params.length ? params[i++].trim() : "normal";
		int opacity = i < params.length ? clamp(whole(params[i], "imageCombine"), 0, 100) : 100;
		return data(combine(base, top, x, y, mode, opacity));
	}

	static BufferedImage combine(BufferedImage base, BufferedImage top, int x, int y, String mode, int opacity) throws xTalkException {
		int blend = mode.isEmpty() || mode.equalsIgnoreCase("normal") ? MyPanel.blendMode.Copy : PaintLayer.parseBlend(mode);
		if(blend < 0 || mode.equalsIgnoreCase("blend")) throw new xTalkException("imageCombine: unknown mode {0} (normal, add, subtract, multiply, screen, darken, lighten, difference, hue, color, saturation, luminosity)", mode);
		BufferedImage out = copy(base);
		BufferedImage layer = blank(base.getWidth(), base.getHeight());
		Graphics2D g = layer.createGraphics();
		g.drawImage(top, x, y, null);
		g.dispose();
		PaintLayers.blendOnto(out, layer, blend, opacity, null);
		return out;
	}

	/** imageCreate(width, height [, colour]): clear unless a colour is given */
	static String imageCreate(String[] params) throws xTalkException {
		if(params == null || params.length < 2 || params.length > 3) throw new xTalkException("Wrong number of arguments for {0}: {1}", "imageCreate", "imageCreate(width, height [, \"#FF0000\"])");
		int w = whole(params[0], "imageCreate"), h = whole(params[1], "imageCreate");
		if(w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long)w*h > MAX_PIXELS) throw new xTalkException("imageCreate: the image is too large or empty: {0} x {1}", w, h);
		BufferedImage out = blank(w, h);
		if(params.length > 2 && !params[2].trim().isEmpty()) java.util.Arrays.fill(pixels(out), color(params[2].trim()));
		return data(out);
	}

	/** imageSize(data): "width,height" */
	static String imageSize(String data) throws xTalkException {
		BufferedImage img = image(data, "imageSize");
		return img.getWidth() + "," + img.getHeight();
	}

	private static int whole(String s, String function) throws xTalkException {
		try { return (int)Math.round(Double.parseDouble(s.trim())); }
		catch(NumberFormatException e){ throw new xTalkException("{0}: a number is required here: {1}", function, s); }
	}

	private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
	private static int c255(double v) { return v <= 0 ? 0 : v >= 255 ? 255 : (int)(v + 0.5); }

	// ---- colours ----

	private static final Map<String,Integer> NAMES = Map.ofEntries(
		Map.entry("black", 0xFF000000), Map.entry("white", 0xFFFFFFFF), Map.entry("red", 0xFFFF0000), Map.entry("green", 0xFF00FF00),
		Map.entry("blue", 0xFF0000FF), Map.entry("yellow", 0xFFFFFF00), Map.entry("cyan", 0xFF00FFFF), Map.entry("magenta", 0xFFFF00FF),
		Map.entry("gray", 0xFF808080), Map.entry("grey", 0xFF808080), Map.entry("orange", 0xFFFF8000), Map.entry("purple", 0xFF800080),
		Map.entry("pink", 0xFFFFC0CB), Map.entry("brown", 0xFF8B4513), Map.entry("transparent", 0x00000000), Map.entry("clear", 0x00000000));

	/** #RGB, #RRGGBB, #RRGGBBAA, a name, or "r,g,b[,a]" (0-255) as ARGB */
	static int color(String s) throws xTalkException {
		String t = s.trim().toLowerCase(Locale.ROOT);
		Integer named = NAMES.get(t);
		if(named != null) return named;
		if(t.startsWith("#")) {
			String h = t.substring(1);
			try {
				if(h.length() == 3) { int v = Integer.parseInt(h, 16); return 0xFF000000 | ((v>>8&15)*17)<<16 | ((v>>4&15)*17)<<8 | (v&15)*17; }
				if(h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
				if(h.length() == 8) { long v = Long.parseLong(h, 16); return (int)((v & 0xFF) << 24 | v >>> 8); }
			}catch(NumberFormatException e){ /* below */ }
		}
		String[] p = t.split(",");
		if(p.length == 3 || p.length == 4) {
			try {
				int r = c255(Double.parseDouble(p[0].trim())), g = c255(Double.parseDouble(p[1].trim())), b = c255(Double.parseDouble(p[2].trim()));
				int a = p.length == 4 ? c255(Double.parseDouble(p[3].trim())) : 255;
				return a<<24 | r<<16 | g<<8 | b;
			}catch(NumberFormatException e){ /* below */ }
		}
		throw new xTalkException("Not a colour: {0} (a name, #RRGGBB or r,g,b)", s);
	}

	// ---- screen effects ----

	/** set the screenFilter: the card window shows the card through this pixel script (nothing in the stack changes) */
	static volatile TalkPixelScript screenFilter;
	static volatile String screenFilterText = "";
	/** set the screenOverlay: a picture over the card window, its top left at the card's */
	static volatile BufferedImage screenOverlay;
	static volatile String screenOverlayData = "";

	static void setScreenFilter(String script) throws xTalkException {
		String t = script == null ? "" : script;
		screenFilter = t.trim().isEmpty() || t.trim().equalsIgnoreCase("none") ? null : TalkPixelScript.compile(t);
		screenFilterText = screenFilter == null ? "" : t;
		repaintCard();
	}

	static void setScreenOverlay(String data) throws xTalkException {
		if(data == null || data.isEmpty()) { screenOverlay = null; screenOverlayData = ""; }
		else { screenOverlay = image(data, "screenOverlay"); screenOverlayData = data; }
		repaintCard();
	}

	/** A stack closes: the next one starts without them */
	static void resetScreen() {
		if(screenFilter == null && screenOverlay == null) return;
		screenFilter = null; screenFilterText = "";
		screenOverlay = null; screenOverlayData = "";
		repaintCard();
	}

	private static void repaintCard() {
		PCARD pc = PCARD.pc;
		if(pc != null && pc.mainPane != null) CardRepaintManager.repaintThroughLock(pc.mainPane);
	}

	/** The card window's picture through the screenFilter (GPaintPanel.paint); a script that fails (one that runs too long)
	 *  is dropped, as the card has to be drawn */
	static BufferedImage filterScreen(BufferedImage shown) {
		TalkPixelScript script = screenFilter;
		if(script == null) return shown;
		try { return script.run(shown, shown.getWidth(), shown.getHeight(), PCARD.scale()); }
		catch(xTalkException | RuntimeException e) {
			if(screenFilter == script) { screenFilter = null; screenFilterText = ""; }
			System.err.println("screenFilter: "+e.getMessage());
			return shown;
		}
	}

	/** export screen to <variable>: the card window as it looks, in card pixels */
	static BufferedImage screenImage() {
		final BufferedImage[] shot = new BufferedImage[1];
		PCARD.runOnEDT(() -> {
			PCARD pc = PCARD.pc;
			if(pc == null || pc.mainPane == null) return;
			int w = pc.mainPane.getWidth(), h = pc.mainPane.getHeight();
			if(w <= 0 || h <= 0) return;
			BufferedImage full = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = full.createGraphics();
			try { VEffect.paintCurrentPanel(g); }finally{ g.dispose(); }
			double s = PCARD.scale();
			if(s == 1) { shot[0] = full; return; }
			BufferedImage card = new BufferedImage(Math.max(1, (int)Math.round(w / s)), Math.max(1, (int)Math.round(h / s)), BufferedImage.TYPE_INT_ARGB);
			Graphics2D cg = card.createGraphics();
			cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			cg.drawImage(full, 0, 0, card.getWidth(), card.getHeight(), null);
			cg.dispose();
			shot[0] = card;
		});
		return shot[0];
	}
}

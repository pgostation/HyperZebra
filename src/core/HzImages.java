import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The pictures of a stack as HzImages (P2): card and background bitmaps, icons. Read from the stack's StackSource when
 * the core opened it (a packed .hzb in the browser), else from its folder; PNG through PngDecoder, HyperCard's ICON data
 * as HCResource.decodeICON reads it, cicn as HCResource.decodeCicn, PICT files through HzPict. Kept per stack and file name.
 */
final class HzImages {
	private HzImages() {}

	private static final Map<String, HzImage> cache = new HashMap<>();
	private static final HzImage NONE = new HzImage(0, 0);

	/** A file of the stack's folder (its path inside it), or null. */
	static byte[] bytes(OStack st, String name) {
		if (st == null || name == null || name.isEmpty()) return null;
		StackSource src = HeadlessStacks.sourceOf(st);
		try {
			if (src != null) return src.read(name.replace(File.separatorChar, '/'));
			if (st.file == null) return null;
			File f = new File(st.file.getParentFile(), name);
			return f.isFile() ? Platform.get().readAllBytes(f) : null;
		} catch (java.io.IOException e) {
			return null;
		}
	}

	/** The picture in that file of the stack, or null when there is none it can read. */
	static HzImage file(OStack st, String name) {
		if (st == null || name == null || name.isEmpty()) return null;
		String key = System.identityHashCode(st) + "/" + name;
		HzImage img;
		synchronized (cache) { img = cache.get(key); }
		if (img == null) {
			byte[] b = bytes(st, name);
			String base = name.substring(name.lastIndexOf('/') + 1);
			img = b == null ? null : PngDecoder.isPng(b) ? PngDecoder.decode(b)
				: base.endsWith(".data") && base.startsWith("ICON") ? icon(b)
				: base.endsWith(".data") && base.startsWith("cicn") ? cicn(b)
				: base.toLowerCase().endsWith(".pict") ? HzPict.decode(b, pictCanvas) : null;
			if (img == null) img = NONE;
			synchronized (cache) { cache.put(key, img); }
		}
		return img == NONE ? null : img;
	}

	/** The bitmap of a card or background, or null: the one being painted (HzPaint) while it is */
	static HzImage picture(OCardBase cb) {
		if (cb == null || cb.stack == null) return null;
		HzImage painted = HzPaint.shown(cb);
		return painted != null ? painted : file(cb.stack, cb.bitmapName);
	}
	/** The bitmap as its file has it (HzPaint starts from it) */
	static HzImage pictureFile(OCardBase cb) { return cb == null || cb.stack == null ? null : file(cb.stack, cb.bitmapName); }
	/** A file of the stack was written again (HzPaint): its picture is read again */
	static void forget(OStack st, String name) {
		synchronized (cache) { cache.remove(System.identityHashCode(st) + "/" + name); }
	}

	/** The Gfx HzPict draws a picture's text and lines on (the player's host sets it), or null */
	static HzPict.Canvas pictCanvas;

	/** ICON {@code id} as a button shows it (Rsrc.getDisplayImage): a cicn of that ID in the stack or a stack in use first, as
	 *  Inside Macintosh has colour screens show it, then the ICON; null when neither. */
	static HzImage icon(OStack st, int id) {
		HzImage color = resource(st, id, "cicn");
		return color != null ? color : resource(st, id, "ICON");
	}

	/** The resource of that type and ID of the stack, else of the first stack in use that has one (Rsrc.getFilePathAll) */
	static HzImage resource(OStack st, int id, String type) {
		if (st == null || st.rsrc == null) return null;
		Rsrc.rsrcClass r = st.rsrc.rsrcIdMap.get(type + id);
		if (r != null) return file(st, r.filename);
		if (st.usingStacks != null) for (OStack u : st.usingStacks) {
			if (u == null || u.rsrc == null) continue;
			r = u.rsrc.rsrcIdMap.get(type + id);
			if (r != null) return file(u, r.filename);
		}
		return null;
	}

	/** A cicn resource as HCResource.decodeCicn reads it (the same bit orders and PackBits loop; a mask as alpha), or null */
	static HzImage cicn(byte[] b) {
		try { return decodeCicn(b); } catch (RuntimeException e) { return null; }
	}

	private static int u2(byte[] b, int o) { return ((b[o] & 0xFF) << 8) + (b[o + 1] & 0xFF); }

	private static HzImage decodeCicn(byte[] b) {
		int offset = 4;
		int rowBytes = 0x7FFF & u2(b, offset); offset += 2;
		offset += 4;
		int bottom = u2(b, offset); offset += 2;
		int right = u2(b, offset); offset += 2;
		offset += 2;
		int packType = u2(b, offset); offset += 2;
		int packSize = (int) (((long) (b[offset] & 0xFF) << 24) + ((b[offset + 1] & 0xFF) << 16) + ((b[offset + 2] & 0xFF) << 8) + (b[offset + 3] & 0xFF)); offset += 4;
		offset += 8 + 2;
		int pixelSize = u2(b, offset); offset += 2;
		offset += 2 + 2 + 4 + 4 + 4;
		// maskBMap
		offset += 4;
		int mrowBytes = 0x7FFF & u2(b, offset); offset += 2;
		offset += 8;
		// iconBMap
		offset += 4;
		int irowBytes = 0x7FFF & u2(b, offset); offset += 2;
		offset += 8;
		int[] mask = new int[right * bottom];
		for (int y = 0; y < bottom; y++) {
			byte[] data = new byte[mrowBytes];
			for (int i = 0; i < mrowBytes; i++) { if (offset >= b.length) continue; data[i] = b[offset]; offset++; }
			for (int x = 0; x < right && x / 8 < mrowBytes; x++) {
				int idx = ((data[x / 8] & 0xFF) >> (7 - x % 8)) & 1;
				mask[y * right + x] = idx == 0 ? 0xFF000000 : 0xFFFFFFFF;
			}
		}
		HzImage bi = new HzImage(right, bottom);
		for (int y = 0; y < bottom; y++) {
			byte[] data = new byte[irowBytes];
			for (int i = 0; i < irowBytes; i++) { data[i] = b[offset]; offset++; }
			for (int x = 0; x < right && x / 8 < irowBytes; x++) {
				int idx = ((data[x / 8] & 0xFF) >> (x % 8)) & 1;
				bi.argb[y * right + x] = idx == 0 ? 0xFF000000 : 0xFFFFFFFF;
			}
		}
		if (offset + 1 >= b.length) return bi;
		offset += 4 + 4 + 2;
		int ctSize = u2(b, offset); offset += 2;
		Integer[] palette;
		if (ctSize == 0) palette = new Integer[]{0xFF000000, 0xFFFFFFFF};
		else {
			palette = new Integer[256];
			for (int i = 0; i < ctSize + 1; i++) {
				int value = 0xFF & u2(b, offset); offset += 2;
				int red = u2(b, offset); offset += 2;
				int green = u2(b, offset); offset += 2;
				int blue = u2(b, offset); offset += 2;
				palette[value] = 0xFF000000 | (((red / 256) << 16) + ((green / 256) << 8) + (blue / 256));
			}
		}
		int[] px = new int[right * bottom];
		for (int y = 0; y < bottom; y++) {
			byte[] data = new byte[rowBytes];
			if (packType == 0) {
				for (int i = 0; i < rowBytes && offset < b.length; i++) { data[i] = b[offset]; offset++; }
			} else {
				for (int i = 0; i < packSize; i++) {
					int dsize = 0xFF & b[offset]; offset++;
					int doffset = 0;
					if (dsize >= 128) {
						dsize = 256 - dsize + 1;
						int src = b[offset]; offset++;
						i++;
						for (int j = 0; j < dsize && j + doffset < data.length; j++) data[j + doffset] = (byte) src;
						doffset += dsize;
					} else {
						dsize++;
						for (int j = 0; j < dsize; j++) {
							if (rowBytes <= j + doffset) continue;
							data[j + doffset] = b[offset]; offset++; i++;
						}
						doffset += dsize;
					}
				}
			}
			for (int x = 0; x < right && x * pixelSize / 8 < rowBytes; x++) {
				int idx = data[x * pixelSize / 8] & 0xFF;
				if (pixelSize == 1) idx = (idx >> (7 - x % 8)) & 0x01;
				if (pixelSize == 2) idx = (idx >> (6 - 2 * (x % 4))) & 0x03;
				if (pixelSize == 4) idx = (idx >> (4 - 4 * (x % 2))) & 0x0F;
				if (idx >= palette.length || palette[idx] == null) idx = 0;
				int pixel = palette[idx]; // (palette[0] null: the desktop's NullPointerException, no picture)
				px[y * right + x] = pixel;
			}
		}
		// OCard.makeAlphaImage: the mask's blue byte as alpha
		HzImage out = new HzImage(right, bottom);
		for (int i = 0; i < px.length; i++) out.argb[i] = (px[i] & 0x00FFFFFF) | ((mask[i] & 0xFF) << 24);
		return out;
	}

	/** HyperCard's ICON resource: 32x32 one-bit pixels, the white connected to the edges transparent (as decodeICON). */
	static HzImage icon(byte[] b) {
		HzImage img = new HzImage(32, 32);
		for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
			int i = y * 4 + x / 8, c = i < b.length ? b[i] : 0;
			img.argb[y * 32 + x] = ((c >> (7 - x % 8)) & 1) == 0 ? 0xFFFFFFFF : 0xFF000000;
		}
		// the white reaching an edge goes transparent (a flood from the edges, as clearPixel)
		java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<>();
		for (int k = 0; k < 32; k++) { todo.add(new int[]{k, 0}); todo.add(new int[]{k, 31}); todo.add(new int[]{0, k}); todo.add(new int[]{31, k}); }
		while (!todo.isEmpty()) {
			int[] p = todo.poll();
			int x = p[0], y = p[1];
			if (x < 0 || y < 0 || x >= 32 || y >= 32 || img.argb[y * 32 + x] != 0xFFFFFFFF) continue;
			img.argb[y * 32 + x] = 0x00FFFFFF;
			todo.add(new int[]{x - 1, y}); todo.add(new int[]{x + 1, y}); todo.add(new int[]{x, y - 1}); todo.add(new int[]{x, y + 1});
		}
		return img;
	}

	/** Forgets the pictures of a stack (closed, or its files changed). */
	static void forget(OStack st) {
		String prefix = System.identityHashCode(st) + "/";
		synchronized (cache) { cache.keySet().removeIf(k -> k.startsWith(prefix)); }
	}
}

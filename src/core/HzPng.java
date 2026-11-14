/**
 * A PNG of an HzImage (the browser player's painted pictures, HzPaint): 8-bit RGBA, or with no transparent pixel RGB, each row
 * with the Up filter where it repeats the row above (a painted picture is mostly white), deflated (java.util.zip.Deflater,
 * which TeaVM has). PngDecoder and the desktop (ImageIO) read it.
 */
final class HzPng {
	private HzPng() {}

	static byte[] encode(HzImage img) {
		int w = img.width, h = img.height;
		boolean alpha = img.hasTransparency();
		int bpp = alpha ? 4 : 3, stride = w * bpp;
		byte[] raw = new byte[(stride + 1) * h];
		byte[] prev = new byte[stride], row = new byte[stride];
		int at = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int c = img.argb[y * w + x], k = x * bpp;
				row[k] = (byte) (c >> 16); row[k + 1] = (byte) (c >> 8); row[k + 2] = (byte) c;
				if (alpha) row[k + 3] = (byte) (c >>> 24);
			}
			// None, or Up (the difference from the row above: zeros where it is the same)
			boolean up = y > 0;
			raw[at++] = (byte) (up ? 2 : 0);
			for (int k = 0; k < stride; k++) raw[at++] = up ? (byte) (row[k] - prev[k]) : row[k];
			byte[] t = prev; prev = row; row = t;
		}
		java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
		d.setInput(raw);
		d.finish();
		java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream(Math.max(64, raw.length / 8));
		byte[] buf = new byte[65536];
		while (!d.finished()) {
			int n = d.deflate(buf);
			if (n <= 0 && d.needsInput()) break;
			z.write(buf, 0, n);
		}
		d.end();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(z.size() + 64);
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
		byte[] ihdr = new byte[13];
		be(ihdr, 0, w); be(ihdr, 4, h);
		ihdr[8] = 8; ihdr[9] = (byte) (alpha ? 6 : 2); // 8 bits, RGBA or RGB
		chunk(out, "IHDR", ihdr);
		chunk(out, "IDAT", z.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void be(byte[] b, int at, int v) { b[at] = (byte) (v >>> 24); b[at + 1] = (byte) (v >>> 16); b[at + 2] = (byte) (v >>> 8); b[at + 3] = (byte) v; }
	private static void chunk(java.io.ByteArrayOutputStream out, String type, byte[] data) {
		byte[] len = new byte[4], crc = new byte[4];
		be(len, 0, data.length);
		byte[] t = new byte[] {(byte) type.charAt(0), (byte) type.charAt(1), (byte) type.charAt(2), (byte) type.charAt(3)};
		java.util.zip.CRC32 c = new java.util.zip.CRC32();
		c.update(t, 0, 4);
		c.update(data, 0, data.length);
		be(crc, 0, (int) c.getValue());
		out.write(len, 0, 4); out.write(t, 0, 4); out.write(data, 0, data.length); out.write(crc, 0, 4);
	}
}

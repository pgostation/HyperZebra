/**
 * PICT pictures for the core (P3, colour): the desktop's PictureFile.loadPICT on ARGB pixels instead of a BufferedImage,
 * for AddColor's pictures in the browser player. The same reading, opcode by opcode: the bits opcodes (BitsRect,
 * PackBitsRect, DirectBitsRect and their region forms) with their colour tables, transfer modes, source rectangles, masks
 * and destination places; the frame's resolution; lines and text through a Gfx canvas of the host when one is given (the
 * desktop draws them with Java2D, so these can differ by font and antialiasing); QuickTime images (opcodes $8200/$8201)
 * through HzQtImage, JPEG with the core's JpegDecoder (baseline only) where the desktop uses ImageIO.
 * The pixels are those the desktop's image has: as the picture comes out on white, transparent where it drew nothing (the
 * desktop's inversion mask for PICT buttons is not made here).
 * tools/wasm-core/PictCheck.java compares the two on PICT files.
 */
final class HzPict {
	private HzPict() {}

	/** A Gfx of that size the decoder draws lines and text on, or null for none. */
	interface Canvas { Gfx make(int width, int height); }

	/** A PICT file (a 512-byte header, then the picture), or null when it is no picture it can read. */
	static HzImage decode(byte[] file, Canvas canvas) {
		if (file == null) return null;
		try { return new HzPict.Reader(file, canvas).picture(); }
		catch (ReadPastEnd e) { return null; }
		catch (RuntimeException e) { return null; }
	}

	/** A PICT resource's data (no 512-byte header). */
	static HzImage decodeResource(byte[] picture, Canvas canvas) {
		if (picture == null) return null;
		byte[] file = new byte[512 + picture.length];
		System.arraycopy(picture, 0, file, 512, picture.length);
		return decode(file, canvas);
	}

	/** What the last decode met, for PictCheck: text, lines, QuickTime images (counts) */
	static final int[] seen = new int[3];

	private static final class ReadPastEnd extends RuntimeException {
		ReadPastEnd() { super("not a PICT", null, false, false); }
	}

	private static final class Reader {
		private final byte[] b;
		private int pos, pastEnd;
		private final Canvas canvas;
		Reader(byte[] b, Canvas canvas) { this.b = b; this.canvas = canvas; }

		/** One byte, -1 past the end (as an InputStream; a million of those: no picture) */
		private int read() {
			if (pos < b.length) return b[pos++] & 0xFF;
			if (++pastEnd > (1 << 20)) throw new ReadPastEnd();
			return -1;
		}
		/** PictureFile.readOpcode: 1 or 2 bytes, a byte past the end read as 0xFF */
		private int op(int version) {
			int b0 = version >= 1 ? (byte) read() : 0, b1 = version >= 2 ? (byte) read() : 0;
			if (version == 1) return b0 & 0xFF;
			if (version == 2) return ((b0 & 0xFF) << 8) + (b1 & 0xFF);
			return 0;
		}
		private int readBytes(byte[] out) {
			int got = 0;
			while (got < out.length) {
				if (pos >= b.length) { if (++pastEnd > (1 << 20)) throw new ReadPastEnd(); break; }
				int n = Math.min(out.length - got, b.length - pos);
				System.arraycopy(b, pos, out, got, n);
				pos += n; got += n;
			}
			return got;
		}

		// the picture being drawn: pixels, or the canvas when text or a line went on it last
		private int width, height;
		private int[] pixels;
		private Gfx gfx;
		private boolean gfxNewer, pixelsNewer;
		private int fg = 0xFF000000;

		private int[] px() {
			if (gfxNewer) { pixels = gfx.getPixels(0, 0, width, height); gfxNewer = false; }
			pixelsNewer = true;
			return pixels;
		}
		private Gfx g() {
			if (canvas == null) return null;
			if (gfx == null) { gfx = canvas.make(width, height); if (gfx == null) return null; pixelsNewer = true; }
			if (pixelsNewer) { gfx.putPixels(pixels, 0, 0, width, height); pixelsNewer = false; }
			gfxNewer = true;
			return gfx;
		}

		HzImage picture() {
			int[] frameOrigin = new int[2];
			int[] size = v1Size(frameOrigin);
			if (size == null) return null;
			int[] picFrame = size;
			int[] resolution = {72, 72};
			int version = version();
			if (version == 2) {
				int[] v2 = v2Size(version, frameOrigin, resolution);
				size = v2 != null && v2[0] == -1 && v2[1] == -1 ? picFrame : v2;
			}
			if (size == null) return null;
			if (size[0] <= 0 || size[1] <= 0) return null;
			if ((long) size[0] * size[1] > 64L * 1024 * 1024) return null;
			width = size[0]; height = size[1];
			pixels = new int[width * height];
			int[] stats = seen;
			stats[0] = stats[1] = stats[2] = 0;
			boolean quickTimeClipEmpty = false;
			// a version 1 frame that does not start at 0,0 draws translated (the desktop's g.translate)
			int tx = version == 1 ? -frameOrigin[1] : 0, ty = version == 1 ? -frameOrigin[0] : 0;
			int penX = 0, penY = 0, penWidth = 1, penHeight = 1;
			int textX = 0, textY = 0, fontId = 0, textFace = 0, fontsize = 12;
			final int originX = version == 1 ? 0 : frameOrigin[1], originY = version == 1 ? 0 : frameOrigin[0];
			int opColor = 0x000000, backColor = 0xFFFFFF;
			int stroke = 1;
			boolean[] inverted = null;
			HzImage jpegImage = null;
			int jpegHeight = 0;
			while (true) {
				int opcode = op(version);
				if (opcode == 0x0000) opcode = op(1);
				boolean packbitsFlag = false, fullcolorFlag = false, rgnmaskFlag = false;
				if (opcode == 0x8200 || opcode == 0x8201) {
					// CompressedQuickTime/UncompressedQuickTime (PictureFile.decodeQuickTimeOpcode): placed by its matrix when
					// that lies in the frame, else stacked under the ones before and drawn with the next bits opcode
					int filelen = (op(2) << 16) + op(2);
					byte[] body = new byte[Math.max(0, filelen)];
					int got = readBytes(body);
					stats[2]++;
					HzImage qt = quickTime(body, got);
					if (qt != null) {
						quickTimeClipEmpty = true;
						int[] at = quickTimeOrigin(body);
						if (at != null && at[0] >= 0 && at[1] >= 0 && at[0] < width && at[1] < height) put(px(), qt, at[0] + tx, at[1] + ty);
						else { jpegImage = qt; jpegHeight += qt.height; }
					}
					continue;
				}
				else if (opcode == 0x0090 || opcode == 0x0091) { }
				else if (opcode == 0x0098 || opcode == 0x0099) packbitsFlag = true;
				else if (opcode == 0x009A || opcode == 0x009B) { packbitsFlag = true; fullcolorFlag = true; }
				else if (opcode == 0x00a1) { op(2); int length = op(2); for (int i = 0; i < length; i++) op(1); continue; }
				else if (opcode == 0x00ff || opcode == 0xffff) break;
				else if (opcode == 0x001e) continue;
				else if (opcode == 0x0001) { int length = op(2); for (int i = 0; i < length - 2; i++) op(1); quickTimeClipEmpty = false; continue; }
				else if (opcode == 0x001f) { int cr = op(2), cg = op(2), cb = op(2); opColor = ((cr >> 8) << 16) | ((cg >> 8) << 8) | (cb >> 8); continue; }
				else if (opcode == 0x00a0) { op(2); continue; }
				else if (opcode == 0x0009) { for (int i = 0; i < 8; i++) op(1); continue; }
				else if (opcode == 0x0020 || opcode == 0x0021 || opcode == 0x0022 || opcode == 0x0023) {
					if (opcode == 0x0020 || opcode == 0x0022) { penY = (short) op(2); penX = (short) op(2); }
					int toX, toY;
					if (opcode == 0x0020 || opcode == 0x0021) { toY = (short) op(2); toX = (short) op(2); }
					else { int dh = (byte) op(1), dv = (byte) op(1); toX = penX + dh; toY = penY + dv; }
					if (penWidth > 0 && penHeight > 0 && !quickTimeClipEmpty) {
						stats[1]++;
						line(penX - originX + tx, penY - originY + ty, toX - originX + tx, toY - originY + ty, stroke);
					}
					penX = toX; penY = toY;
					continue;
				}
				else if (opcode == 0x0007) { penHeight = op(2); penWidth = op(2); stroke = Math.max(1, (penWidth + penHeight) / 2); continue; }
				else if (opcode == 0x001a) { int cr = op(2), cg = op(2), cb = op(2); fg = 0xFF000000 | ((cr / 256) << 16) | ((cg / 256) << 8) | (cb / 256); continue; }
				else if (opcode == 0x001b) { int cr = op(2), cg = op(2), cb = op(2); backColor = ((cr >> 8) << 16) | ((cg >> 8) << 8) | (cb >> 8); continue; }
				else if (opcode == 0x002c) { int length = op(2); for (int i = 0; i < length; i++) op(1); continue; }
				else if (opcode == 0x0003) { fontId = (short) op(2); continue; }
				else if (opcode == 0x0004) { textFace = version == 2 ? op(2) >> 8 : op(1); continue; }
				else if (opcode == 0x0005 || opcode == 0x0008 || opcode == 0x0015 || opcode == 0x0016) { op(2); continue; }
				else if (opcode == 0x000d) { fontsize = op(2); if (fontsize <= 0) fontsize = 12; continue; }
				else if (opcode == 0x002d || opcode == 0x002e) { int length = op(2); for (int i = 0; i < length; i++) op(1); continue; }
				else if (opcode >= 0x0028 && opcode <= 0x002b) {
					if (opcode == 0x0028) { textY = (short) op(2); textX = (short) op(2); }
					else if (opcode == 0x0029) textX += op(1);
					else if (opcode == 0x002a) textY += op(1);
					else { textX += op(1); textY += op(1); }
					int count = op(1);
					byte[] t = new byte[count];
					for (int i = 0; i < count; i++) t[i] = (byte) op(1);
					String s = text(t).replaceAll("[\\x00-\\x1f]", "");
					if (!quickTimeClipEmpty && !s.isEmpty()) {
						stats[0]++;
						Gfx gr = g();
						if (gr != null) {
							gr.setColor(fg);
							gr.setFont(fontName(fontId), fontsize, textFace & 3);
							int x = textX - originX + tx, y = textY - originY + ty;
							gr.drawText(s, x, y);
							if ((textFace & 4) != 0) { int w = gr.textWidth(s); gr.drawLine(x, y + 1, x + w - 1, y + 1); }
						}
					}
					continue;
				}
				else continue;

				if (opcode == 0x0091 || opcode == 0x0099 || opcode == 0x009B) rgnmaskFlag = true;
				int[] pix = px();
				if (jpegImage != null) put(pix, jpegImage, tx, jpegHeight - jpegImage.height + ty);
				if (fullcolorFlag) { op(2); op(2); }
				int rowBytesWord = op(2);
				boolean pixMap = fullcolorFlag || (rowBytesWord & 0x8000) != 0;
				int rowBytes = 0x3fff & rowBytesWord;
				int btop = (short) op(2), bleft = (short) op(2), bbottom = (short) op(2), bright = (short) op(2);
				int bpp = 1, packType = 0, cmpCount = 1;
				int[] palette = {0xFFFFFFFF, 0xFF000000};
				int[] palValue = null;
				if (pixMap) {
					op(2);
					packType = op(2);
					op(2); op(2); op(2); op(2); op(2); op(2); op(2);
					bpp = op(2);
					cmpCount = op(2);
					op(2); op(2); op(2); op(2); op(2); op(2); op(2);
					palette = null;
					if (!fullcolorFlag) {
						op(2); op(2); op(2);
						int count = 1 + op(2);
						palette = new int[count];
						int[] pvals = new int[count];
						boolean sparse = false;
						for (int i = 0; i < count; i++) {
							int pidx = op(2);
							pvals[i] = pidx;
							if (pidx != i) sparse = true;
							int cr = op(2) >> 8, cg = op(2) >> 8, cb = op(2) >> 8;
							palette[i] = 0xFF000000 + (cr << 16) + (cg << 8) + cb;
						}
						if (sparse && bpp <= 8) {
							palValue = new int[1 << bpp];
							java.util.Arrays.fill(palValue, -1);
							for (int i = count - 1; i >= 0; i--) if (pvals[i] >= 0 && pvals[i] < palValue.length) palValue[pvals[i]] = i;
						}
					}
				}
				if (rowBytes == 0) rowBytes = (bright - bleft) * bpp / 8;
				int dtop = op(2), dleft = op(2), dbottom = op(2), dright = op(2);
				int ttop = (short) op(2), tleft = (short) op(2), tbottom = (short) op(2), tright = (short) op(2);
				int srcTop = dtop, srcLeft = dleft, srcBottom = dbottom, srcRight = dright;
				boolean clipSource = srcTop >= btop && srcLeft >= bleft && srcBottom <= bbottom && srcRight <= bright;
				boolean framed = ttop >= frameOrigin[0] && tleft >= frameOrigin[1];
				if (framed) {
					int offTop = ttop - frameOrigin[0] + btop - dtop, offLeft = tleft - frameOrigin[1] + bleft - dleft;
					dbottom = tbottom - frameOrigin[0]; dright = tright - frameOrigin[1];
					dtop = offTop; dleft = offLeft;
				}
				if (dright > width || dbottom > height) {
					dright -= dleft; dleft = 0; dbottom -= dtop; dtop = 0;
					if (dright > width || dbottom > height) break;
				}
				int mode = op(2);
				boolean[] mask = null;
				int maskTop = 0, maskLeft = 0, maskWidth = 0, maskHeight = 0;
				if (rgnmaskFlag) {
					int len = op(2);
					maskTop = (short) op(2);
					maskLeft = (short) op(2);
					maskHeight = Math.max(0, (short) op(2) - maskTop);
					maskWidth = Math.max(0, (short) op(2) - maskLeft);
					if ((long) maskWidth * maskHeight > 64L * 1024 * 1024) { maskWidth = 0; maskHeight = 0; }
					mask = new boolean[maskWidth * maskHeight];
					if (len <= 10) java.util.Arrays.fill(mask, true);
					else {
						int[] words = new int[(len - 10) / 2];
						for (int i = 0; i < words.length; i++) words[i] = (short) op(2);
						boolean[] row = new boolean[maskWidth];
						int i = 0, rowV = -1;
						while (i < words.length && words[i] != 32767) {
							int v = words[i++];
							for (int y = Math.max(0, rowV - maskTop); y < Math.min(maskHeight, v - maskTop); y++) System.arraycopy(row, 0, mask, y * maskWidth, maskWidth);
							while (i < words.length && words[i] != 32767) {
								for (int x = Math.max(0, words[i++] - maskLeft); x < maskWidth; x++) row[x] = !row[x];
							}
							i++;
							rowV = v;
						}
						for (int y = Math.max(0, rowV - maskTop); rowV >= 0 && y < maskHeight; y++) System.arraycopy(row, 0, mask, y * maskWidth, maskWidth);
					}
				}
				boolean packed = opcode != 0x0090 && opcode != 0x0091 && rowBytes >= 8 && packType != 1 && packType != 2;
				int w = bright - bleft, h = bbottom - btop;
				int rowLength = (bpp == 32 && packType == 2) ? w * 3 : rowBytes;
				boolean colorSource = pixMap;
				int[] maskOrigin = framed ? frameOrigin : new int[2];
				long dataBytes = 0;
				for (int v = 0; v < h; v++) {
					byte[] data = new byte[Math.max(rowLength, rowBytes)];
					if (packed) {
						int count = rowBytes > 250 ? op(2) : op(1);
						dataBytes += (rowBytes > 250 ? 2 : 1) + count;
						byte[] packedRow = new byte[count];
						readBytes(packedRow);
						unpackBits(packedRow, data, bpp == 16 ? 2 : 1);
					} else {
						byte[] row = new byte[rowLength];
						readBytes(row);
						System.arraycopy(row, 0, data, 0, rowLength);
						dataBytes += rowLength;
					}
					if (clipSource && (btop + v < srcTop || btop + v >= srcBottom)) continue;
					int y = v + dtop;
					if (y < 0 || y >= height || jpegImage != null || quickTimeClipEmpty) continue;
					for (int hh = 0; hh < w; hh++) {
						if (clipSource && (bleft + hh < srcLeft || bleft + hh >= srcRight)) continue;
						int x = hh + dleft;
						if (x < 0 || x >= width) continue;
						if (mask != null) {
							int mx = x + maskOrigin[1] - maskLeft, my = y + maskOrigin[0] - maskTop;
							if (mx < 0 || my < 0 || mx >= maskWidth || my >= maskHeight || !mask[my * maskWidth + mx]) continue;
						}
						int rgb;
						if (bpp == 16) {
							int pix16 = ((0xFF & data[hh * 2]) << 8) + (0xFF & data[hh * 2 + 1]);
							int cr = ((pix16 >> 10) & 0x1F) * 0xFF / 0x1F, cg = ((pix16 >> 5) & 0x1F) * 0xFF / 0x1F, cb = (pix16 & 0x1F) * 0xFF / 0x1F;
							rgb = (cr << 16) | (cg << 8) | cb;
						} else if (bpp == 32) {
							if (packType == 2) rgb = ((0xFF & data[hh * 3]) << 16) | ((0xFF & data[hh * 3 + 1]) << 8) | (0xFF & data[hh * 3 + 2]);
							else if (packed) {
								int plane = cmpCount >= 4 ? w : 0;
								rgb = ((0xFF & data[plane + hh]) << 16) | ((0xFF & data[plane + hh + w]) << 8) | (0xFF & data[plane + hh + w * 2]);
							} else rgb = ((0xFF & data[hh * 4 + 1]) << 16) | ((0xFF & data[hh * 4 + 2]) << 8) | (0xFF & data[hh * 4 + 3]);
						} else {
							int idx;
							if (bpp == 1) idx = (data[hh / 8] >> (7 - hh % 8)) & 0x01;
							else if (bpp == 2) idx = (data[hh / 4] >> (6 - hh % 4 * 2)) & 0x03;
							else if (bpp == 4) idx = (data[hh / 2] >> (4 - hh % 2 * 4)) & 0x0F;
							else idx = data[hh] & 0xFF;
							if (palette == null) continue;
							if (palValue != null && idx < palValue.length && palValue[idx] >= 0) idx = palValue[idx];
							if (idx >= palette.length) idx = 0;
							rgb = palette[idx] & 0xFFFFFF;
						}
						int at = y * width + x, before = pix[at];
						// a pixel the picture inverts where it drew nothing stays the inverted card (PictureFile's inversion mask)
						if (inverted != null && inverted[at] && leavesDestination(rgb, mode, colorSource, opColor, backColor)) continue;
						boolean inverts = ((before >>> 24) == 0 || inverted != null && inverted[at]) && invertsDestination(rgb, mode, colorSource, opColor, backColor);
						if (inverts && inverted != null && inverted[at]) { inverted[at] = false; pix[at] = 0; continue; }
						if (inverts) { if (inverted == null) inverted = new boolean[width * height]; inverted[at] = true; }
						int out = transferPixel(rgb, before, mode, colorSource, opColor, backColor);
						if (out != 0) { pix[at] = out; if (!inverts && inverted != null && (out >>> 24) == 0xFF) inverted[at] = false; }
					}
				}
				if (version == 2 && (dataBytes & 1) != 0) op(1);
			}
			int[] done = px();
			HzImage img = new HzImage(width, height, done);
			return toPictureFrame(img, picFrame, resolution);
		}

		/** An opaque picture onto the pixels at x,y, cut to the frame (Graphics.drawImage) */
		private void put(int[] pix, HzImage img, int x, int y) {
			for (int v = 0; v < img.height; v++) {
				int yy = y + v;
				if (yy < 0 || yy >= height) continue;
				for (int u = 0; u < img.width; u++) {
					int xx = x + u;
					if (xx >= 0 && xx < width) pix[yy * width + xx] = img.argb[v * img.width + u];
				}
			}
		}

		/** A line as the desktop's Java2D stroke draws it: 1 pixel through the canvas, wider ones as squares along it */
		private void line(int x1, int y1, int x2, int y2, int stroke) {
			Gfx gr = g();
			if (gr == null) return;
			gr.setColor(fg);
			if (stroke <= 1) { gr.drawLine(x1, y1, x2, y2); return; }
			int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
			for (int x = x1, y = y1; ; ) {
				gr.fillRect(x - stroke / 2, y - stroke / 2, stroke, stroke);
				if (x == x2 && y == y2) break;
				int e2 = 2 * err;
				if (e2 > -dy) { err -= dy; x += sx; }
				if (e2 < dx) { err += dx; y += sy; }
			}
		}

		private int[] v1Size(int[] origin) {
			for (int i = 0; i < 512; i++) read();
			for (int i = 0; i < 2; i++) read();
			int top = (short) op(2), left = (short) op(2);
			int h = (short) op(2) - top, w = (short) op(2) - left;
			origin[0] = top; origin[1] = left;
			return new int[]{w, h};
		}

		private int version() {
			int opcode = op(2);
			for (int nops = 0; opcode == 0x0000 && nops < 128; nops++) opcode = op(2);
			if (opcode == 0x0011) { opcode = op(2); return opcode == 0x02FF ? 2 : 0; }
			return opcode == 0x1101 ? 1 : 0;
		}

		private int[] v2Size(int version, int[] origin, int[] resolution) {
			int top, left, bottom, right;
			if (op(version) != 0x0C00) return null;
			int form = op(version);
			if (form == 0xfffe) {
				op(version);
				resolution[0] = op(version); op(version);
				resolution[1] = op(version); op(version);
				top = op(version); left = op(version); bottom = op(version); right = op(version);
				op(version); op(version);
			} else if (form == 0xffff) {
				op(version);
				left = op(version); op(version);
				top = op(version); op(version);
				right = op(version); op(version);
				bottom = op(version); op(version);
				op(version); op(version);
			} else {
				for (int i = 0; i < 11; i++) op(version);
				return new int[]{-1, -1};
			}
			top = (short) top; left = (short) left; bottom = (short) bottom; right = (short) right;
			origin[0] = top; origin[1] = left;
			return new int[]{right - left, bottom - top};
		}
	}

	/** A QuickTime opcode's image (PictureFile.decodeQuickTimeOpcode): its version, matrix, matte and mask, then the image
	 *  description and the data, decoded by HzQtImage; null when the codec is not one the core has */
	static HzImage quickTime(byte[] body, int length) {
		try {
			if (length < 70) return null;
			int matrix = 2;
			int matteSize = HzQtImage.u4(body, matrix + 36), maskSize = HzQtImage.u4(body, matrix + 62);
			int cursor = matrix + 66;
			if (matteSize > 0) cursor += matteSize;
			if (maskSize > 0) cursor += maskSize;
			if (cursor + 86 > length) return null;
			int idSize = HzQtImage.u4(body, cursor);
			if (idSize < 86 || cursor + idSize > length) return null;
			byte[] description = java.util.Arrays.copyOfRange(body, cursor, cursor + idSize);
			int dataSize = HzQtImage.u4(description, 44);
			int imageData = cursor + idSize;
			if (dataSize <= 0 || imageData + dataSize > length) dataSize = length - imageData;
			if (dataSize <= 0) return null;
			int[] argb = HzQtImage.decode(description, java.util.Arrays.copyOfRange(body, imageData, imageData + dataSize), HzPict::fileFormat);
			if (argb == null) return null;
			return new HzImage(HzQtImage.u2(description, 32), HzQtImage.u2(description, 34), argb);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The top left the opcode's matrix puts the image at (tx, ty), or null */
	private static int[] quickTimeOrigin(byte[] body) {
		if (body.length < 2 + 36) return null;
		double tx = HzQtImage.u4(body, 2 + 24) / 65536.0, ty = HzQtImage.u4(body, 2 + 28) / 65536.0;
		return new int[]{(int) Math.round(tx), (int) Math.round(ty)};
	}

	/**
	 * A JPEG or PNG image of a QuickTime opcode, as the desktop's ImageIO step gives it (QuickTimeImage68k.hostDecode): opaque,
	 * white where the image is smaller than the description says. JPEG through the core's baseline JpegDecoder (a progressive
	 * one: null), which converts YCbCr as QuickTime does, so its colours can be a few levels from ImageIO's.
	 */
	static int[] fileFormat(byte[] data, int width, int height) {
		int iw, ih;
		int[] src;
		if (PngDecoder.isPng(data)) {
			HzImage png = PngDecoder.decode(data);
			if (png == null) return null;
			iw = png.width; ih = png.height; src = png.argb;
		} else if (data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
			JpegDecoder j;
			try { j = JpegDecoder.decode(data, 0, data.length); } catch (java.io.IOException | RuntimeException e) { return null; }
			iw = j.width; ih = j.height; src = j.rgb;
		} else return null;
		int[] argb = new int[width * height];
		java.util.Arrays.fill(argb, 0xFFFFFFFF);
		int w = Math.min(width, iw), h = Math.min(height, ih);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) argb[y * width + x] = src[y * iw + x] | 0xFF000000;
		return argb;
	}

	/** The text bytes of a picture: Shift_JIS, as the desktop reads them with the Japanese stack encoding it starts with */
	private static String text(byte[] t) {
		try { return new String(t, Platform.get().charset("Shift_JIS")); }
		catch (RuntimeException e) { return new String(t, java.nio.charset.StandardCharsets.ISO_8859_1); }
	}

	/** A font number as the desktop's Fonts68k.familyFor picks the host family, as a classic name HzFonts maps */
	static String fontName(int id) {
		switch (id) {
			case 4: case 22: return "Monaco";
			case 16385: case 16387: return "明朝";
			case 2: case 20: case 16: case 14: case 34: return "Times";
			case 23: return "Symbol";
			case 16384: case 16386: case 16388: case 0: case 1: case 3: return "Osaka";
			case 16436: return "Osaka−等幅";
			default: return "Helvetica";
		}
	}

	/** PictureFile.toPictureFrame: a picture recorded at another resolution, stretched to its frame */
	private static HzImage toPictureFrame(HzImage img, int[] picFrame, int[] resolution) {
		if (resolution[0] == 72 && resolution[1] == 72) return img;
		if (picFrame == null || picFrame[0] <= 0 || picFrame[1] <= 0) return img;
		int sw = img.width, sh = img.height, dw = picFrame[0], dh = picFrame[1];
		if (sw == dw && sh == dh) return img;
		if ((long) dw * dh > 64L * 1024 * 1024) return img;
		HzImage framed = new HzImage(dw, dh);
		for (int v = 0; v < dh; v++) {
			int sy = (int) ((long) v * sh / dh);
			for (int h = 0; h < dw; h++) framed.argb[v * dw + h] = img.argb[sy * sw + (int) ((long) h * sw / dw)];
		}
		return framed;
	}

	/** PictureFile.transferPixel: one bits pixel through its transfer mode; 0 leaves the destination */
	static int transferPixel(int rgb, int destination, int mode, boolean colorSource, int opColor, int backColor) {
		rgb &= 0xFFFFFF;
		mode = booleanTransferMode(mode, colorSource);
		if ((destination >>> 24) == 0xFF) return 0xFF000000 | transferRgb(rgb, destination & 0xFFFFFF, mode, opColor, backColor);
		if (mode == 32) {
			int weight = (((opColor >> 16) & 0xFF) + ((opColor >> 8) & 0xFF) + (opColor & 0xFF)) / 3;
			return weight == 0 ? 0 : (weight << 24) | rgb;
		}
		int onWhite = transferRgb(rgb, 0xFFFFFF, mode, opColor, backColor), onBlack = transferRgb(rgb, 0x000000, mode, opColor, backColor);
		if (onWhite == 0xFFFFFF && onBlack == 0x000000) return 0;
		return 0xFF000000 | onWhite;
	}

	private static boolean invertsDestination(int rgb, int mode, boolean colorSource, int opColor, int backColor) {
		rgb &= 0xFFFFFF;
		mode = booleanTransferMode(mode, colorSource);
		if (mode == 32 || mode == 36) return false;
		return transferRgb(rgb, 0xFFFFFF, mode, opColor, backColor) == 0 && transferRgb(rgb, 0x000000, mode, opColor, backColor) == 0xFFFFFF;
	}

	private static boolean leavesDestination(int rgb, int mode, boolean colorSource, int opColor, int backColor) {
		rgb &= 0xFFFFFF;
		mode = booleanTransferMode(mode, colorSource);
		if (mode == 32) return false;
		return transferRgb(rgb, 0xFFFFFF, mode, opColor, backColor) == 0xFFFFFF && transferRgb(rgb, 0x000000, mode, opColor, backColor) == 0;
	}

	private static int booleanTransferMode(int mode, boolean colorSource) {
		if (mode >= 96 && mode <= 103) mode -= 64;
		if ((mode & 64) != 0) mode &= ~64;
		if (mode >= 8 && mode <= 15) mode -= 8;
		if (!colorSource) {
			switch (mode) {
				case 32: mode = 0; break; case 33: case 37: mode = 3; break; case 34: case 38: case 50: mode = 2; break;
				case 35: case 36: case 39: mode = 1; break; default: break;
			}
		} else if (mode == 50) mode = 2;
		if (mode > 7 && (mode < 32 || mode > 39)) mode = 0;
		return mode;
	}

	private static int transferRgb(int source, int destination, int mode, int opColor, int backColor) {
		if (mode >= 32 && mode <= 39) {
			if (mode == 36) return source == backColor ? destination : source;
			int result = 0;
			for (int shift = 16; shift >= 0; shift -= 8) {
				int s = (source >>> shift) & 0xFF, d = (destination >>> shift) & 0xFF, op = (opColor >>> shift) & 0xFF, r;
				switch (mode) {
					case 32: r = (s * op + d * (255 - op) + 127) / 255; break;
					case 33: r = Math.min(op, s + d); break;
					case 34: r = (s + d) & 0xFF; break;
					case 35: r = Math.max(op, d - s); break;
					case 37: r = Math.max(s, d); break;
					case 38: r = (d - s) & 0xFF; break;
					default: r = Math.min(s, d); break;
				}
				result |= r << shift;
			}
			return result;
		}
		int s = ~source & 0xFFFFFF, d = ~destination & 0xFFFFFF, r;
		if ((mode & 4) != 0) s = ~s & 0xFFFFFF;
		switch (mode & 3) {
			case 1: r = s | d; break;
			case 2: r = s ^ d; break;
			case 3: r = ~s & d; break;
			default: r = s; break;
		}
		return ~r & 0xFFFFFF;
	}

	private static void unpackBits(byte[] packed, byte[] row, int unit) {
		int out = 0;
		for (int i = 0; i < packed.length && out < row.length; ) {
			int flag = packed[i++] & 0xFF;
			if (flag < 128) {
				for (int j = 0; j < (flag + 1) * unit && i < packed.length; j++, i++) if (out < row.length) row[out++] = packed[i];
			} else if (flag > 128) {
				int count = 257 - flag;
				for (int k = 0; k < count; k++) for (int j = 0; j < unit; j++) if (out < row.length && i + j < packed.length) row[out++] = packed[i + j];
				i += unit;
			}
		}
	}
}

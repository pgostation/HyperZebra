/**
 * HyperCard's visual effects on pixel arrays (the browser player; the desktop's VEffect draws the same geometry with Java2D
 * and takes its times from here). A frame is computed from the picture the effect starts on (old), the one it ends on (neu)
 * and the progress t (0..1), so a player can draw as many frames as its time allows. Effects 0-27 are HyperCard's, in
 * VEffect's numbering; HyperZebra's own (28 and up) play as dissolve here.
 */
final class HzEffects {
	private HzEffects() {}

	/** HyperCard J-2.3's own effects (numbers 0-27, the order of GVisualEffectWindow.EFFECTS): how many ticks (1/60 s) each takes
	 *  at the speeds very fast, fast, normal, slow, very slow. Measured in HyperCard J-2.3 in the 68K VM (go with `visual effect X
	 *  <speed>`, the same with the VM unthrottled: it is the effect's own time, not the CPU's; probe groups group_vec27_vfx*). The
	 *  speeds are not one scale: very slow is 6.5 times normal, and checkerboard/zoom open are the long ones, venetian blinds the short. */
	static final int[][] HC_TICKS = {
		{2, 15, 30, 66, 197},     //0 plain / cut: HyperCard paces them too
		{1, 12, 25, 53, 158},     //1 barn door open
		{1, 12, 24, 53, 157},     //2 barn door close
		{3, 17, 32, 69, 203},     //3 dissolve
		{1, 6, 12, 26, 75},       //4 venetian blinds
		{1, 24, 46, 101, 299},    //5 checkerboard
		{1, 13, 26, 57, 169},     //6 iris open
		{1, 13, 26, 57, 168},     //7 iris close
		{2, 9, 18, 40, 118},      //8 scroll left
		{2, 9, 18, 40, 119},      //9 scroll right
		{2, 9, 18, 40, 118},      //10 scroll up
		{2, 9, 18, 40, 119},      //11 scroll down
		{1, 12, 25, 53, 158},     //12 wipe left
		{1, 13, 24, 54, 157},     //13 wipe right
		{1, 10, 20, 42, 126},     //14 wipe up
		{1, 10, 20, 42, 126},     //15 wipe down
		{1, 21, 42, 92, 273},     //16 zoom open (zoom out)
		{1, 7, 13, 27, 77},       //17 zoom close (zoom in)
		{4, 14, 27, 59, 173},     //18 shrink to top
		{4, 14, 27, 59, 173},     //19 shrink to bottom
		{5, 14, 27, 59, 173},     //20 shrink to center
		{3, 15, 28, 61, 180},     //21 stretch from top
		{3, 15, 29, 61, 179},     //22 stretch from bottom
		{3, 15, 28, 61, 180},     //23 stretch from center
		{4, 9, 19, 40, 118},      //24 push left
		{4, 9, 18, 40, 119},      //25 push right
		{3, 10, 18, 40, 119},     //26 push up
		{4, 10, 18, 40, 118}};    //27 push down

	/** The length of an effect at a speed: 1 very fast, 2 fast, 3 normal, 4 slow(ly), 5 very slow(ly) (ms); another
	 *  number plays no frames. HyperCard's effects 0-27 take HyperCard's time (HC_TICKS), HyperZebra's own ones a common table. */
	static long duration(int effect, int speed) {
		if (effect >= 0 && effect < HC_TICKS.length && speed >= 1 && speed <= 5) return Math.round(HC_TICKS[effect][speed - 1] * 1000 / 60.0);
		return duration(speed);
	}

	/** HyperZebra's own effects: 0.1, 0.25, 0.5, 1 and 2 seconds */
	static long duration(int speed) {
		switch (speed) {
			case 1: return 100;
			case 2: return 250;
			case 3: return 500;
			case 4: return 1000;
			case 5: return 2000;
			default: return 0;
		}
	}

	/** dissolve: 16 passes, each the pixels at x = DISSOLVE_X[i] + 8k and y = DISSOLVE_Y[i] + 2k */
	static final int[] DISSOLVE_X = {0, 4, 2, 6, 3, 7, 1, 5, 0, 4, 2, 6, 3, 7, 1, 5};
	static final int[] DISSOLVE_Y = {0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0};

	static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;

	/** The picture an effect ends on: the card (to card, 0), black (1), white (2), gray (3: HyperCard's 50% checker, white at
	 *  the top left) or the card inverted (4) */
	static int[] target(int toEffect, int[] card, int w, int h) {
		if (toEffect == 0) return card;
		int[] p = new int[w * h];
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
			int i = y * w + x;
			p[i] = toEffect == 1 ? BLACK : toEffect == 2 ? WHITE : toEffect == 3 ? (((x + y) & 1) == 0 ? WHITE : BLACK)
				: 0xFF000000 | ~card[i] & 0xFFFFFF;
		}
		return p;
	}

	/** AddColor's own effects (VEffect's numbers: not HyperTalk visual effects, AddColor only), measured in HC J-2.3 + AddColor */
	static final int ADDCOLOR_CHECKER_OPEN = 100, ADDCOLOR_CHECKER_CLOSE = 101, ADDCOLOR_CIRCLE_CHECKER_OPEN = 102,
		ADDCOLOR_IRIS_OPEN = 103, ADDCOLOR_IRIS_CLOSE = 104, ADDCOLOR_COMB_HORIZONTAL = 105, ADDCOLOR_COMB_VERTICAL = 106,
		ADDCOLOR_FROM_TOP_LEFT = 107, ADDCOLOR_FROM_TOP_RIGHT = 108, ADDCOLOR_FROM_BOTTOM_LEFT = 109, ADDCOLOR_FROM_BOTTOM_RIGHT = 110,
		ADDCOLOR_DISSOLVE = 111;

	/**
	 * A frame of an effect played only inside the rectangle x,y,rw,rh of the card (AddColor's: HC J-2.3 "fromLeft" on a
	 * picture at 340,30 wipes from x 340 to its right edge), the rectangle taken as a picture of its own (VEffect.visualEffectIn)
	 */
	static void frameIn(int effect, int[] old, int[] neu, int[] out, int w, int h, int x, int y, int rw, int rh, double t) {
		System.arraycopy(old, 0, out, 0, w * h);
		int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(w, x + rw), y1 = Math.min(h, y + rh);
		if (x0 >= x1 || y0 >= y1) return;
		int cw = x1 - x0, ch = y1 - y0;
		int[] o = new int[cw * ch], n = new int[cw * ch], f = new int[cw * ch];
		for (int r = 0; r < ch; r++) {
			System.arraycopy(old, (y0 + r) * w + x0, o, r * cw, cw);
			System.arraycopy(neu, (y0 + r) * w + x0, n, r * cw, cw);
		}
		frame(effect, o, n, f, cw, ch, t);
		for (int r = 0; r < ch; r++) System.arraycopy(f, r * cw, out, (y0 + r) * w + x0, cw);
	}

	/** The frame at progress t (0..1) of the effect from old to neu, into out (all w x h) */
	static void frame(int effect, int[] old, int[] neu, int[] out, int w, int h, double t) {
		t = Math.max(0, Math.min(1, t));
		System.arraycopy(old, 0, out, 0, w * h);
		if (effect >= ADDCOLOR_CHECKER_OPEN && effect <= ADDCOLOR_DISSOLVE) { addColorFrame(effect, neu, out, w, h, t); return; }
		if (effect >= 28) effect = 3;
		switch (effect) {
			case 1: { int v = (int) (w / 2 * t); copy(neu, out, w, h, w / 2 - v, 0, w / 2 + v, h, w / 2 - v, 0, w / 2 + v, h); break; } // barn door open
			case 2: { int v = (int) (w / 2 * t); copy(neu, out, w, h, 0, 0, v, h, 0, 0, v, h); copy(neu, out, w, h, w - v, 0, w, h, w - v, 0, w, h); break; } // barn door close
			case 3: { // dissolve
				int v = (int) (16 * t);
				for (int i = 0; i < v && i < 16; i++)
					for (int y = DISSOLVE_Y[i]; y < h; y += 2) for (int x = DISSOLVE_X[i]; x < w; x += 8) out[y * w + x] = neu[y * w + x];
				break;
			}
			case 4: { int v = (int) (32 * t); for (int i = 0; i < (h + 31) / 32; i++) copy(neu, out, w, h, 0, 32 * i, w, 32 * i + v, 0, 32 * i, w, 32 * i + v); break; } // venetian blinds
			case 5: { // checkerboard
				int v = (int) (64 * t);
				for (int i = 0; i < (h + 63) / 64; i++) {
					for (int j = 0; j < (w + 63) / 64; j++) copy(neu, out, w, h, j * 64, 64 * i, j * 64 + 32, 64 * i + v, j * 64, 64 * i, j * 64 + 32, 64 * i + v);
					for (int j = 0; j < (w + 63) / 64; j++) copy(neu, out, w, h, j * 64 + 32, 64 * i - 32, j * 64 + 64, 64 * i - 32 + v, j * 64 + 32, 64 * i - 32, j * 64 + 64, 64 * i - 32 + v);
				}
				break;
			}
			case 6: { int a = (int) (w / 2 * t), v = (int) (h / 2 * t); copy(neu, out, w, h, w / 2 - a, h / 2 - v, w / 2 + a, h / 2 + v, w / 2 - a, h / 2 - v, w / 2 + a, h / 2 + v); break; } // iris open
			case 7: { // iris close
				int a = (int) (w / 2 * t), v = (int) (h / 2 * t);
				copy(neu, out, w, h, 0, 0, w, v, 0, 0, w, v);
				copy(neu, out, w, h, 0, h - v, w, h, 0, h - v, w, h);
				copy(neu, out, w, h, 0, v, a, h - v, 0, v, a, h - v);
				copy(neu, out, w, h, w - a, v, w, h - v, w - a, v, w, h - v);
				break;
			}
			case 8: { int v = (int) (w * t); copy(old, out, w, h, 0, 0, w - v, h, v, 0, w, h); copy(neu, out, w, h, w - v, 0, w, h, w - v, 0, w, h); break; } // scroll left
			case 9: { int v = (int) (w * t); copy(old, out, w, h, v, 0, w, h, 0, 0, w - v, h); copy(neu, out, w, h, 0, 0, v, h, 0, 0, v, h); break; } // scroll right
			case 10: { int v = (int) (h * t); copy(old, out, w, h, 0, 0, w, h - v, 0, v, w, h); copy(neu, out, w, h, 0, h - v, w, h, 0, h - v, w, h); break; } // scroll up
			case 11: { int v = (int) (h * t); copy(old, out, w, h, 0, v, w, h, 0, 0, w, h - v); copy(neu, out, w, h, 0, 0, w, v, 0, 0, w, v); break; } // scroll down
			case 12: { int v = (int) (w * t); copy(neu, out, w, h, w - v, 0, w, h, w - v, 0, w, h); break; } // wipe left
			case 13: { int v = (int) (w * t); copy(neu, out, w, h, 0, 0, v, h, 0, 0, v, h); break; } // wipe right
			case 14: { int v = (int) (h * t); copy(neu, out, w, h, 0, h - v, w, h, 0, h - v, w, h); break; } // wipe up
			case 15: { int v = (int) (h * t); copy(neu, out, w, h, 0, 0, w, v, 0, 0, w, v); break; } // wipe down
			case 16: case 17: { // zoom open / close: an inverted frame growing from the centre (or shrinking to it) over the old picture
				double u = effect == 16 ? t : 1 - t;
				int a = (int) (w * u), v = (int) (h * u);
				invertFrame(out, w, h, w / 2 - a / 2, h / 2 - v / 2, a, v);
				break;
			}
			case 18: { int v = (int) (h * (1 - t)); copy(old, out, w, h, 0, 0, w, v, 0, 0, w, h); copy(neu, out, w, h, 0, v, w, h, 0, v, w, h); break; } // shrink to top
			case 19: { int v = (int) (h * t); copy(old, out, w, h, 0, v, w, h, 0, 0, w, h); copy(neu, out, w, h, 0, 0, w, v, 0, 0, w, v); break; } // shrink to bottom
			case 20: { // shrink to center
				int v = (int) (h / 2 * (1 - t));
				copy(old, out, w, h, 0, h / 2 - v, w, h / 2 + v, 0, 0, w, h);
				copy(neu, out, w, h, 0, 0, w, h / 2 - v, 0, 0, w, h / 2 - v);
				copy(neu, out, w, h, 0, h / 2 + v, w, h, 0, h / 2 + v, w, h);
				break;
			}
			case 21: { int v = (int) (h * t); copy(neu, out, w, h, 0, 0, w, v, 0, 0, w, h); break; } // stretch from top
			case 22: { int v = (int) (h * t); copy(neu, out, w, h, 0, h - v, w, h, 0, 0, w, h); break; } // stretch from bottom
			case 23: { int v = (int) (h / 2 * t); copy(neu, out, w, h, 0, h / 2 - v, w, h / 2 + v, 0, 0, w, h); break; } // stretch from center
			case 24: { int v = (int) (w * t); copy(old, out, w, h, 0, 0, w - v, h, v, 0, w, h); copy(neu, out, w, h, w - v, 0, w, h, 0, 0, v, h); break; } // push left
			case 25: { int v = (int) (w * t); copy(old, out, w, h, v, 0, w, h, 0, 0, w - v, h); copy(neu, out, w, h, 0, 0, v, h, w - v, 0, w, h); break; } // push right
			case 26: { int v = (int) (h * t); copy(old, out, w, h, 0, 0, w, h - v, 0, v, w, h); copy(neu, out, w, h, 0, h - v, w, h, 0, 0, w, v); break; } // push up
			case 27: { int v = (int) (h * t); copy(old, out, w, h, 0, v, w, h, 0, 0, w, h - v); copy(neu, out, w, h, 0, 0, w, v, 0, h - v, w, h); break; } // push down
			default: break; // plain: the old picture until the end
		}
	}

	/**
	 * The part of the new picture one of AddColor's own effects shows at progress t, over the old (VEffect.addColorFrame and
	 * checkerOpenFrame, AddColorDissolve). Every one only adds to what it showed before, so a frame is the old picture with its
	 * whole part. Shapes cover the pixels whose centres they hold, as Java2D clips to them.
	 */
	private static void addColorFrame(int effect, int[] neu, int[] out, int w, int h, double t) {
		switch (effect) {
			case ADDCOLOR_CHECKER_OPEN: {
				int cw = w / 4 + 1, ch = h / 4 + 1, ix = (int) (cw * (1 - t) / 2), iy = (int) (ch * (1 - t) / 2);
				for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
					int x0 = j * cw + ix, y0 = i * ch + iy, x1 = Math.min(w, (j + 1) * cw - ix), y1 = Math.min(h, (i + 1) * ch - iy);
					if (x0 < x1 && y0 < y1) copy(neu, out, w, h, x0, y0, x1, y1, x0, y0, x1, y1);
				}
				return;
			}
			case ADDCOLOR_CHECKER_CLOSE: {
				int cw = w / 4 + 1, ch = h / 4 + 1, ix = (int) (cw * t / 2), iy = (int) (ch * t / 2);
				for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
					int j = x / cw, i = y / ch;
					int x0 = j * cw + ix, y0 = i * ch + iy, x1 = (j + 1) * cw - ix, y1 = (i + 1) * ch - iy;
					boolean inCell = x0 < x1 && y0 < y1 && x >= x0 && x < x1 && y >= y0 && y < y1;
					if (!inCell) out[y * w + x] = neu[y * w + x];
				}
				return;
			}
			case ADDCOLOR_CIRCLE_CHECKER_OPEN: {
				int cw = w / 4 + 1, ch = h / 4 + 1, a = cw * 45 / 64, b = ch * 45 / 64;
				double ra = a * t, rb = b * t;
				if (ra <= 0 || rb <= 0) return;
				for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
					double cx = (j + 1) * cw - a, cy = (i + 1) * ch - b;
					ellipse(neu, out, w, h, cx, cy, ra, rb, false);
				}
				return;
			}
			case ADDCOLOR_IRIS_OPEN: case ADDCOLOR_IRIS_CLOSE: {
				double r = Math.max(w, h) * 45 / 64 * (effect == ADDCOLOR_IRIS_OPEN ? t : 1 - t);
				double rr = Math.max(0, 2 * r - 2) / 2; // QuickDraw's oval in the centre ± r covers 2r-2 pixels across
				ellipse(neu, out, w, h, w / 2.0, h / 2.0, rr, rr, effect == ADDCOLOR_IRIS_CLOSE);
				return;
			}
			case ADDCOLOR_COMB_HORIZONTAL: case ADDCOLOR_COMB_VERTICAL: {
				boolean horizontal = effect == ADDCOLOR_COMB_HORIZONTAL;
				int length = horizontal ? w : h, across = horizontal ? h : w;
				int strip = Math.max(1, across / 8), v = (int) (length * t);
				for (int k = 0; k * strip < across; k++) {
					int s0 = k * strip, s1 = Math.min(across, s0 + strip), l0 = k % 2 == 0 ? 0 : length - v, l1 = l0 + v;
					if (horizontal) copy(neu, out, w, h, l0, s0, l1, s1, l0, s0, l1, s1);
					else copy(neu, out, w, h, s0, l0, s1, l1, s0, l0, s1, l1);
				}
				return;
			}
			case ADDCOLOR_DISSOLVE: {
				int[][] order = dissolveOrder(w, h);
				double u = t * DISSOLVE_CELL.length;
				int target = Math.min(DISSOLVE_CELL.length - 1, (int) u);
				for (int p = 0; p <= target; p++) {
					int k = DISSOLVE_CELL[p], cols = (w + k - 1) / k;
					int upto = (int) Math.round(order[p].length * DISSOLVE_SHARE[p] * Math.min(1, p < target ? 1 : u - p));
					for (int c = 0; c < upto; c++) {
						int x = order[p][c] % cols * k, y = order[p][c] / cols * k;
						copy(neu, out, w, h, x, y, x + k, y + k, x, y, x + k, y + k);
					}
				}
				return;
			}
			default: { // fromTopLeft, fromTopRight, fromBottomLeft, fromBottomRight: a rectangle from that corner, in place
				int rw = (int) (w * t), rh = (int) (h * t);
				boolean left = effect == ADDCOLOR_FROM_TOP_LEFT || effect == ADDCOLOR_FROM_BOTTOM_LEFT;
				boolean top = effect == ADDCOLOR_FROM_TOP_LEFT || effect == ADDCOLOR_FROM_TOP_RIGHT;
				int x0 = left ? 0 : w - rw, y0 = top ? 0 : h - rh;
				copy(neu, out, w, h, x0, y0, x0 + rw, y0 + rh, x0, y0, x0 + rw, y0 + rh);
			}
		}
	}

	/** The new picture inside the ellipse round cx,cy with half-axes a,b (outside it with outside) */
	private static void ellipse(int[] neu, int[] out, int w, int h, double cx, double cy, double a, double b, boolean outside) {
		for (int y = 0; y < h; y++) {
			double dy = (y + 0.5 - cy) / b;
			for (int x = 0; x < w; x++) {
				double dx = (x + 0.5 - cx) / a;
				boolean in = a > 0 && b > 0 && dx * dx + dy * dy < 1;
				if (in != outside) out[y * w + x] = neu[y * w + x];
			}
		}
	}

	/** AddColor's dissolve (VEffect.AddColorDissolve): random square cells in four passes of 4, 5, 6 and 7 pixels, each
	 *  showing 18%, 32%, 52% and then all of its grid at an even rate */
	static final int[] DISSOLVE_CELL = {4, 5, 6, 7};
	static final double[] DISSOLVE_SHARE = {0.18, 0.32, 0.52, 1.0};
	private static int[][] dissolveOrder;
	private static int dissolveW = -1, dissolveH = -1;

	private static synchronized int[][] dissolveOrder(int w, int h) {
		if (dissolveOrder != null && dissolveW == w && dissolveH == h) return dissolveOrder;
		java.util.Random random = new java.util.Random(0x41646443L);
		int[][] order = new int[DISSOLVE_CELL.length][];
		for (int i = 0; i < DISSOLVE_CELL.length; i++) {
			int cols = (w + DISSOLVE_CELL[i] - 1) / DISSOLVE_CELL[i], rows = (h + DISSOLVE_CELL[i] - 1) / DISSOLVE_CELL[i];
			int[] cells = new int[cols * rows];
			for (int c = 0; c < cells.length; c++) cells[c] = c;
			for (int c = cells.length - 1; c > 0; c--) { int r = random.nextInt(c + 1), s = cells[c]; cells[c] = cells[r]; cells[r] = s; }
			order[i] = cells;
		}
		dissolveOrder = order; dissolveW = w; dissolveH = h;
		return order;
	}

	/** Graphics.drawImage(src, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2) of a w x h picture onto another: the source rectangle
	 *  scaled to the destination's (nearest pixel, as Java2D draws without interpolation), clipped to both pictures */
	static void copy(int[] src, int[] dst, int w, int h, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2) {
		int dw = dx2 - dx1, dh = dy2 - dy1, sw = sx2 - sx1, sh = sy2 - sy1;
		if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;
		int x0 = Math.max(0, dx1), x1 = Math.min(w, dx2), y0 = Math.max(0, dy1), y1 = Math.min(h, dy2);
		for (int y = y0; y < y1; y++) {
			int sy = sy1 + (int) (((y - dy1) + 0.5) * sh / dh);
			if (sy < 0 || sy >= h) continue;
			for (int x = x0; x < x1; x++) {
				int sx = sx1 + (int) (((x - dx1) + 0.5) * sw / dw);
				if (sx >= 0 && sx < w) dst[y * w + x] = src[sy * w + sx];
			}
		}
	}

	/** drawRect(x, y, rw, rh) in XOR with white: the outline's pixels (rw + 1 by rh + 1) inverted */
	static void invertFrame(int[] p, int w, int h, int x, int y, int rw, int rh) {
		for (int i = x; i <= x + rw; i++) { invert(p, w, h, i, y); if (rh > 0) invert(p, w, h, i, y + rh); }
		for (int j = y + 1; j < y + rh; j++) { invert(p, w, h, x, j); if (rw > 0) invert(p, w, h, x + rw, j); }
	}

	private static void invert(int[] p, int w, int h, int x, int y) {
		if (x >= 0 && y >= 0 && x < w && y < h) p[y * w + x] ^= 0xFFFFFF;
	}

	/** Every pixel inverted (flash) */
	static int[] inverted(int[] p) {
		int[] q = new int[p.length];
		for (int i = 0; i < p.length; i++) q[i] = p[i] ^ 0xFFFFFF;
		return q;
	}
}

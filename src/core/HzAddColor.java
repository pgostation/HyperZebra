import java.awt.Color;
import java.awt.Rectangle;

/**
 * AddColor in the core (P3, colour), as the desktop's built-in AddColor (TXcmd.AddColor) does it: a colour picture of the
 * card's size over the black-and-white card, kept until the next colorCard or remove, and only while its stack is the open
 * one (the desktop keeps it on the OStack). CardRenderer composites it over every card it draws: where the card is white
 * the colour shows, elsewhere the card's black stays on top (multiplied: HC J-2.3 with AddColor keeps field text and the
 * card's picture over "o" pictures too).
 *  - colorCard[,effect[,ticks]]: the background's colour objects (HCbg), then the card's (HCcd): pictures, and buttons,
 *    fields and rectangles filled with their colour and bevel (a transparent part's colour blended half with what is
 *    under it); a card with neither shows in black and white
 *  - colorButton|colorField, cd|bg, id, "r,g,b", bevel / colorRect, cd|bg, "l,t,r,b", "r,g,b", bevel: one object now
 *  - colorPict, cd, name, "l,t" or "l,t,r,b", o|t, effect, ticks: a PICT of the stack (or a stack in use) at its size or
 *    scaled into the rectangle; "t" leaves its white out
 *  - remove, install[,depth], enableObject/disableObject, changeObjectColor
 * Effects play inside the picture's rectangle in AddColor's time (ticks); dissolve shows everything in half its time and
 * then waits out the rest. Not here: ColorizeHC's opaque pictures, PICT files outside the stack (colour object type 5).
 */
final class HzAddColor {
	private HzAddColor() {}

	/** The stack the colour picture belongs to, and the picture (card size, ARGB; null: none) */
	private static OStack owner;
	private static HzImage layer;

	/** What shows the colours: the browser player's loop (null on the JVM's checks: the picture changes, nothing is shown) */
	interface Screen {
		/** the screen as it shows now (the picture an effect starts on) */
		int[] screen();
		/** the card again, at once */
		void redraw();
		/** the card as it is now shown with the effect inside area (null: the whole card) from old, lasting ms */
		void effect(int effect, Rectangle area, int[] old, long ms);
		/** a wait of a handler that keeps the screen and input going */
		void park(long nanos);
	}

	static HzImage layer(OStack st) { return st != null && st == owner ? layer : null; }

	/** Forgets the colours (the stack closed, or a check starts over) */
	static void clear() { owner = null; layer = null; }

	/** The colour picture over the card pixels (w x h) of st, as TXcmd.AddColor.composite */
	static void composite(int[] px, int w, int h, OStack st) {
		HzImage add = layer(st);
		if (add == null) return;
		int aw = add.width, ah = add.height;
		for (int y = 0; y < Math.min(h, ah); y++) {
			int addRow = y * aw, row = y * w;
			for (int x = 0; x < Math.min(w, aw); x++) {
				int b = add.argb[addRow + x];
				int ba = b >>> 24;
				if (ba == 0) continue; // no colour here: the card as it is
				int a = px[row + x];
				if ((a >>> 24) == 0) a = 0xFFFFFFFF; // not drawn: white
				if (a == 0xFFFFFFFF) px[row + x] = ba == 0xFF ? b : over(b, 0xFFFFFFFF);
				else {
					// a transparent PICT over the black-and-white card in srcOr: white to the colour, black stays, grey darkens it
					int pr = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255, pg = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255, pb = ((a & 0xFF) * (b & 0xFF)) / 255;
					px[row + x] = 0xFF000000 | (pr << 16) | (pg << 8) | pb;
				}
			}
		}
	}

	/** The colour picture over the card that g holds, for CardRenderer */
	static void composite(Gfx g, OStack st) {
		if (layer(st) == null) return;
		int w = g.width(), h = g.height();
		int[] px = g.getPixels(0, 0, w, h);
		composite(px, w, h, st);
		g.putPixels(px, 0, 0, w, h);
	}

	// ---------------------------------------------------------------- the command

	/** AddColor with these parameters (TXcmd's: params[0] the command); target evaluates parameters that are expressions */
	static void call(String[] params, OObject target, Screen screen) {
		OStack st = HZ.stack();
		if (st == null || params == null || params.length == 0) return;
		String what = params[0].trim();
		if (what.equalsIgnoreCase("remove")) {
			st.addColorHeapBytes = 0;
			clear();
			if (screen != null) screen.redraw();
		}
		else if (what.equalsIgnoreCase("install")) {
			int depth = params.length > 1 && !params[1].isBlank() ? number(params[1], target) : 8;
			if (depth < 1 || depth > 32) depth = 8;
			st.addColorHeapBytes = (long) st.width * st.height * depth / 8;
		}
		else if (params.length > 1 && what.equalsIgnoreCase("disableObject")) Rsrc.setEnabledACObj((params.length > 2 ? number(params[2], target) : 1) - 1, false);
		else if (params.length > 1 && what.equalsIgnoreCase("enableObject")) Rsrc.setEnabledACObj((params.length > 2 ? number(params[2], target) : 1) - 1, true);
		else if (params.length > 3 && what.equalsIgnoreCase("changeObjectColor")) {
			Color c = color(params[3], target);
			if (c != null) Rsrc.setColorACObj(params[1].trim().toLowerCase().startsWith("b"), number(params[2], target) - 1, c);
		}
		else if (what.equalsIgnoreCase("colorCard")) {
			int ticks = params.length > 2 ? number(params[2], target) : 30;
			int effect = effectNumber(params.length > 1 ? params[1] : "");
			int[] old = effect != 0 && screen != null ? screen.screen() : null;
			OCard card = st.curCard;
			if (card == null) return;
			owner = st;
			layer = colorCardImage(card);
			show(effect, ticks, null, old, screen);
		}
		else if (params.length >= 4 && (what.equalsIgnoreCase("colorButton") || what.equalsIgnoreCase("colorField") || what.equalsIgnoreCase("colorRect"))) {
			colorObject(st, what, params[1], params[2], params[3], params.length >= 5 ? number(params[4], target) : 0, target);
			if (screen != null) screen.redraw();
		}
		else if (what.equalsIgnoreCase("colorPict")) {
			String pict = params.length >= 3 ? params[2] : "";
			int[] at = {0, 0};
			Rectangle r = null;
			if (params.length >= 4) {
				String[] p = params[3].split(",");
				if (p.length == 2) { at[0] = number(p[0], target); at[1] = number(p[1], target); }
				else if (p.length == 4) {
					int left = number(p[0], target), top = number(p[1], target);
					r = new Rectangle(left, top, number(p[2], target) - left, number(p[3], target) - top);
					at = null;
				}
			}
			String mode = params.length >= 5 ? params[4] : "o";
			String effectName = params.length >= 6 ? params[5] : "";
			int ticks = params.length >= 7 ? number(params[6], target) : 0;
			colorPict(st, pict, at, r, mode, effectName, ticks, target, screen);
		}
	}

	/** Shows the new colours: with an effect inside area from old, in AddColor's ticks; without one at once */
	private static void show(int effect, int ticks, Rectangle area, int[] old, Screen screen) {
		if (screen == null) return;
		// the colours go into the window over what 68K externals drew there (TXcmd.AddColor's playEffect, VEffect's path)
		Externals68k x = Externals68k.Runner.current;
		if (x != null) { if (effect != 0 && old != null || area == null) x.cardCopied(); else x.partCopied(area.y, area.x, area.y + area.height, area.x + area.width); }
		if (effect == 0 || old == null) { screen.redraw(); return; }
		long ms = Math.max(0, ticks) * 1000L / 60;
		if (effect != HzEffects.ADDCOLOR_DISSOLVE) { screen.effect(effect, area, old, ms); return; }
		// dissolve shows everything in half its time and then waits out the rest (はいぱあれもん Vol.6, HC J-2.3)
		screen.effect(effect, area, old, ms / 2);
		screen.park((ms - ms / 2) * 1_000_000L);
	}

	/** The colours of a card as colorCard draws them: its background's HCbg objects, then its HCcd objects */
	static HzImage colorCardImage(OCard card) {
		OStack stack = card.stack;
		Rsrc.addcolorClass bgColors = null, cdColors = null;
		if (stack.rsrc != null) for (Rsrc.addcolorClass c : stack.rsrc.addcolorList) {
			if (c.isBg) { if (card.bg != null && c.id == card.bg.id && bgColors == null) bgColors = c; }
			else if (c.id == card.id && cdColors == null) cdColors = c;
		}
		HzImage img = new HzImage(Math.max(1, stack.width), Math.max(1, stack.height));
		if (bgColors != null) drawColorObjects(img, bgColors, card.bg, stack);
		if (cdColors != null) drawColorObjects(img, cdColors, card, stack);
		return img;
	}

	private static void drawColorObjects(HzImage img, Rsrc.addcolorClass list, OCardBase layer, OStack stack) {
		for (Rsrc.addcolorObjClass obj : list.objList) {
			if (!obj.visible) continue;
			if (obj instanceof Rsrc.PictObject picobj) {
				HzImage pict = picobj.file ? null : pictNamed(stack, picobj.name);
				if (pict == null) continue;
				if (picobj.transparent) pict = whiteToClear(pict);
				Rectangle pr = picobj.rect;
				// DrawPicture into the object's rectangle: another size is stretched pixel for pixel
				if (pr.width > 0 && pr.height > 0 && (pr.width != pict.width || pr.height != pict.height)) pict = scaled(pict, pr.width, pr.height);
				drawOver(img, pict, pr.x, pr.y, 1f);
				continue;
			}
			Rectangle area = null;
			boolean see = false; // a transparent button or field: its colour blended half and half with what is below
			if (obj instanceof Rsrc.FldObject fo) {
				OField fld = layer == null ? null : layer.GetFldbyId(fo.id);
				if (fld != null && fld.getVisible()) area = new Rectangle(fld.left, fld.top, fld.width, fld.height);
				see = fld != null && fld.style == 1;
			} else if (obj instanceof Rsrc.BtnObject bo) {
				OButton btn = layer == null ? null : layer.GetBtnbyId(bo.id);
				if (btn != null && btn.getVisible()) area = new Rectangle(btn.left, btn.top, btn.width, btn.height);
				see = btn != null && btn.style == 1;
			} else if (obj instanceof Rsrc.RectObject ro) area = ro.rect;
			if (area == null) continue;
			Color c = obj.runColor != null ? obj.runColor : obj.color;
			if (c == null) continue;
			fillBevelled(img, area, 0xFF000000 | (c.getRGB() & 0xFFFFFF), obj.bevel, see ? 0.5f : 1f);
		}
	}

	/** colorButton/colorField/colorRect: one object's colour over the colours now */
	private static void colorObject(OStack st, String kind, String layerName, String which, String rgb, int bevel, OObject target) {
		OCard card = st.curCard;
		if (card == null) return;
		OCardBase part = layerName.trim().toLowerCase().startsWith("b") ? card.bg : card;
		Rectangle area = null;
		boolean see = false;
		if (kind.equalsIgnoreCase("colorRect")) {
			String[] v = which.split(",");
			if (v.length != 4) return;
			int l = number(v[0], target), t = number(v[1], target);
			area = new Rectangle(l, t, number(v[2], target) - l, number(v[3], target) - t);
		} else if (part != null && kind.equalsIgnoreCase("colorButton")) {
			OButton btn = part.GetBtnbyId(number(which, target));
			if (btn != null && btn.getVisible()) { area = new Rectangle(btn.left, btn.top, btn.width, btn.height); see = btn.style == 1; }
		} else if (part != null) {
			OField fld = part.GetFldbyId(number(which, target));
			if (fld != null && fld.getVisible()) { area = new Rectangle(fld.left, fld.top, fld.width, fld.height); see = fld.style == 1; }
		}
		Color color = color(rgb, target);
		if (area == null || area.width <= 0 || area.height <= 0 || color == null) return;
		HzImage img = layer(st);
		if (img == null) img = new HzImage(Math.max(1, st.width), Math.max(1, st.height));
		fillBevelled(img, area, 0xFF000000 | (color.getRGB() & 0xFFFFFF), bevel, see ? 0.5f : 1f);
		owner = st;
		layer = img;
	}

	/** colorPict: a PICT drawn into the colour picture at its own size, or into the rectangle */
	private static void colorPict(OStack st, String name, int[] at, Rectangle r, String mode, String effectName, int ticks, OObject target, Screen screen) {
		int effect = effectNumber(effectName);
		int[] old = effect != 0 && screen != null ? screen.screen() : null;
		HzImage img = layer(st);
		if (img == null) img = new HzImage(Math.max(1, st.width), Math.max(1, st.height));
		HzImage pict = pictNamed(st, name);
		boolean transparent = mode != null && mode.trim().toLowerCase().startsWith("t");
		if (pict != null && transparent) pict = whiteToClear(pict);
		Rectangle area = null;
		if (pict != null) area = at != null ? new Rectangle(at[0], at[1], pict.width, pict.height) : r == null ? null : new Rectangle(r);
		if (area != null && area.width > 0 && area.height > 0) {
			HzImage placed = area.width != pict.width || area.height != pict.height ? scaled(pict, area.width, area.height) : pict;
			drawOver(img, placed, area.x, area.y, 1f);
		}
		owner = st;
		layer = img;
		show(effect, ticks, area, old, screen);
	}

	/** A PICT by name in the stack or a stack in use (Rsrc.getFilePathAll), read from the stack that has it */
	static HzImage pictNamed(OStack st, String name) {
		if (st == null || st.rsrc == null || name == null || name.isEmpty()) return null;
		Rsrc.rsrcClass r = st.rsrc.findByNameAll(name, "PICT");
		if (r == null) return null;
		java.util.List<OStack> places = new java.util.ArrayList<>();
		places.add(st);
		if (st.usingStacks != null) places.addAll(st.usingStacks);
		for (OStack s : places) {
			if (s == null || s.rsrc == null || s.rsrc.rsrcIdMap.get("PICT" + r.id) != r) continue;
			return HzImages.file(s, r.filename);
		}
		return null;
	}

	/** AddColor's effect names as VEffect numbers (TXcmd.AddColor.effectNumber); 0 = none */
	static int effectNumber(String effect) {
		if (effect == null || effect.trim().isEmpty()) return 0;
		switch (effect.trim().toLowerCase()) {
			case "checkerboardopen": return HzEffects.ADDCOLOR_CHECKER_OPEN;
			case "checkerboardclose": return HzEffects.ADDCOLOR_CHECKER_CLOSE;
			case "circlecheckeropen": return HzEffects.ADDCOLOR_CIRCLE_CHECKER_OPEN;
			case "irisopen": return HzEffects.ADDCOLOR_IRIS_OPEN;
			case "irisclose": return HzEffects.ADDCOLOR_IRIS_CLOSE;
			case "combhorizontal": return HzEffects.ADDCOLOR_COMB_HORIZONTAL;
			case "combvertical": return HzEffects.ADDCOLOR_COMB_VERTICAL;
			case "fromtopleft": return HzEffects.ADDCOLOR_FROM_TOP_LEFT;
			case "fromtopright": return HzEffects.ADDCOLOR_FROM_TOP_RIGHT;
			case "frombottomleft": return HzEffects.ADDCOLOR_FROM_BOTTOM_LEFT;
			case "frombottomright": return HzEffects.ADDCOLOR_FROM_BOTTOM_RIGHT;
			case "dissolve": return HzEffects.ADDCOLOR_DISSOLVE;
			default: break;
		}
		java.util.ArrayList<String> words = new java.util.ArrayList<>();
		for (String w : hyperTalkEffect(effect.trim()).split(" ")) words.add(w);
		try { return 0xFF & TUtil.getVisualMode(words, 0, 0); } catch (xTalkException e) { return 0; }
	}

	/** TXcmd.AddColor.convAddColorEffect: AddColor's names for HyperCard's effects (fromLeft reveals from the left: wipe right) */
	private static String hyperTalkEffect(String e) {
		switch (e.toLowerCase()) {
			case "fromleft": return "wipe right";
			case "fromright": return "wipe left";
			case "fromtop": return "wipe down";
			case "frombottom": return "wipe up";
			case "barndooropen": return "barn door open";
			case "barndoorclose": return "barn door close";
			case "rectopen": return "iris open";
			case "rectclose": return "iris close";
			case "venetianblindshorizontal": case "venetianblindsvertical": case "rakehorizopen": case "rakehorizclose":
			case "rakevertopen": case "rakevertclose": return "venetian blinds";
			default: return e;
		}
	}

	// ---------------------------------------------------------------- pixels

	/**
	 * A colour area with AddColor's bevel (TXcmd.AddColor.fillBevelled): the outer ring keeps the colour, then bevel rings with
	 * the top and left edges halfway to white and the bottom and right ones at three quarters of the colour; alpha below 1
	 * blends it with what is there (Java2D's SRC_OVER at that alpha)
	 */
	static void fillBevelled(HzImage img, Rectangle r, int color, int bevel, float alpha) {
		fill(img, r.x, r.y, r.width, r.height, color, alpha);
		int b = Math.min(bevel, (Math.min(r.width, r.height) - 2) / 2);
		if (b <= 0) return;
		int red = (color >> 16) & 0xFF, green = (color >> 8) & 0xFF, blue = color & 0xFF;
		int light = 0xFF000000 | (((red + 255) / 2) << 16) | (((green + 255) / 2) << 8) | ((blue + 255) / 2);
		int dark = 0xFF000000 | ((red * 3 / 4) << 16) | ((green * 3 / 4) << 8) | (blue * 3 / 4);
		for (int i = 1; i <= b; i++) {
			int x0 = r.x + i, y0 = r.y + i, x1 = r.x + r.width - 1 - i, y1 = r.y + r.height - 1 - i;
			fill(img, x0, y0, x1 - x0 + 1, 1, light, alpha); fill(img, x0, y0, 1, y1 - y0 + 1, light, alpha);
			fill(img, x0 + 1, y1, x1 - x0, 1, dark, alpha); fill(img, x1, y0 + 1, 1, y1 - y0, dark, alpha);
		}
	}

	private static void fill(HzImage img, int x, int y, int w, int h, int color, float alpha) {
		int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(img.width, x + w), y1 = Math.min(img.height, y + h);
		int c = alpha >= 1f ? color : (Math.round(alpha * 255) << 24) | (color & 0xFFFFFF);
		for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
			int i = yy * img.width + xx;
			img.argb[i] = alpha >= 1f ? c : over(c, img.argb[i]);
		}
	}

	/** src drawn over dst (non-premultiplied ARGB, SRC_OVER) */
	static int over(int src, int dst) {
		int sa = src >>> 24;
		if (sa == 0xFF) return src;
		if (sa == 0) return dst;
		int da = dst >>> 24;
		int oa = sa + da * (255 - sa) / 255;
		if (oa == 0) return 0;
		int r = ((((src >> 16) & 0xFF) * sa) + (((dst >> 16) & 0xFF) * da * (255 - sa) / 255)) / oa;
		int g = ((((src >> 8) & 0xFF) * sa) + (((dst >> 8) & 0xFF) * da * (255 - sa) / 255)) / oa;
		int b = (((src & 0xFF) * sa) + ((dst & 0xFF) * da * (255 - sa) / 255)) / oa;
		return (oa << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
	}

	/** pict over img with its top left at x,y */
	private static void drawOver(HzImage img, HzImage pict, int x, int y, float alpha) {
		for (int v = 0; v < pict.height; v++) {
			int yy = y + v;
			if (yy < 0 || yy >= img.height) continue;
			for (int u = 0; u < pict.width; u++) {
				int xx = x + u;
				if (xx < 0 || xx >= img.width) continue;
				int i = yy * img.width + xx;
				img.argb[i] = over(pict.argb[v * pict.width + u], img.argb[i]);
			}
		}
	}

	/** The picture stretched to w x h, nearest pixel (Java2D's sampling at pixel centres) */
	static HzImage scaled(HzImage src, int w, int h) {
		HzImage out = new HzImage(w, h);
		for (int y = 0; y < h; y++) {
			int sy = Math.min(src.height - 1, (int) ((y + 0.5) * src.height / h));
			for (int x = 0; x < w; x++) out.argb[y * w + x] = src.argb[sy * src.width + Math.min(src.width - 1, (int) ((x + 0.5) * src.width / w))];
		}
		return out;
	}

	/** A transparent picture object: its white pixels let what is below show */
	static HzImage whiteToClear(HzImage src) {
		HzImage out = new HzImage(src.width, src.height);
		for (int i = 0; i < src.argb.length; i++) { int p = src.argb[i]; out.argb[i] = (p & 0xFFFFFF) == 0xFFFFFF ? 0 : p; }
		return out;
	}

	// ---------------------------------------------------------------- parameters

	/** A number parameter as TXcmd.externalNumber reads it: a number, else an expression of the stack */
	static int number(String param, OObject target) {
		String p = param == null ? "" : param.trim();
		try { return (int) Math.round(Double.parseDouble(p)); } catch (NumberFormatException e) { }
		OStack st = HZ.stack();
		if (p.isEmpty() || st == null || st.curCard == null) return 0;
		try {
			String v = TTalk.evaluateExternalExpression(p, st.curCard, target != null ? target : st.curCard).trim();
			return (int) Math.round(Double.parseDouble(v));
		} catch (xTalkException | NumberFormatException e) { return 0; }
	}

	/** "r,g,b" of 0-65535 as a colour, null when it is not three numbers */
	private static Color color(String rgb, OObject target) {
		String[] c = rgb.split(",");
		if (c.length != 3) return null;
		return new Color(Math.max(0, Math.min(255, number(c[0], target) / 256)), Math.max(0, Math.min(255, number(c[1], target) / 256)),
			Math.max(0, Math.min(255, number(c[2], target) / 256)));
	}
}

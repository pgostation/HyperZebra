import java.util.ArrayList;
import java.util.List;

/**
 * Draws the current card without Swing (P2: the browser player; the JVM draws the same through Java2DGfx to compare with
 * the desktop). The desktop's rules, from the Swing views they live in:
 * <ul>
 * <li>order (OCardBase.buidParts/stacking, MyLabel): white, the background's bitmap (when its picture shows), the
 *     background's parts, the card's bitmap, the card's parts</li>
 * <li>buttons (RectButton, RoundedCornerButton, RoundButton, FullButtonName, ClassicToggleIcon, MyButton.dimDisabled):
 *     each style's shape and frame, the hilite inverted (transparent and oval: whatever is under them), filled with
 *     hiliteColor when it has one, a transparent icon button inverting only its icon; the name's place; the icon 22 px above
 *     the centre with its name 10 px under it in 9-point Geneva (Osaka for a Japanese stack); grey over a disabled button</li>
 * <li>fields (MyTextArea.paintField, ShadowFieldBorder, RectangleFieldBorder): margins, wrapping (a word with the space
 *     after it; Japanese at any character), alignment, classic line heights, style runs, the five field styles; a scrolling
 *     field's bar is drawn the classic way (the desktop's is the look-and-feel's)</li>
 * </ul>
 * AddColor's colour picture goes over everything (HzAddColor); cicn icons come before ICONs of the same ID (HzImages).
 * PICT buttons (icon -1) show the PICT of the button's name.
 * Shapes are drawn by HzShapes in the parts' order, a card's paint layers over its picture by HzLayers. Not yet: translated texts.
 */
final class CardRenderer {
	private CardRenderer() {}

	static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;
	/** a list field's selected lines and the open field's selection (the desktop: the system's text highlight) */
	static final int HIGHLIGHT = 0xFFB3D7FF;
	/** find's box: the found text of this field, from foundStart up to foundEnd (PlayerUiHost.showFoundBox), drawn as the
	 *  desktop's grey highlight (black at 48 of 255 over what is under the text) */
	static volatile OField foundField;
	static volatile int foundStart, foundEnd;
	/** The button with autoHilite being pressed, while the pointer is in it (PlayerLoop): drawn hilited. */
	static volatile OButton pressed;

	/** The current card of the stack, all of it. */
	static void draw(Gfx g, OStack st) { draw(g, st, true); }
	/** With externals: what 68K externals drew over the card goes on top (CardOverlay). */
	static void draw(Gfx g, OStack st, boolean externals) { draw(g, st, externals, false); }
	private static void draw(Gfx g, OStack st, boolean externals, boolean modalLater) {
		card(g, st);
		HzImage drawn = externals ? CardOverlay.drawn() : null;
		if (drawn != null && st != null && st.curCard != null) g.drawImage(drawn, 0, 0);
		if (externals && st != null && st.curCard != null) for (CardOverlay.Window w : CardOverlay.windows()) if (!(modalLater && w.modal)) window(g, w);
	}
	/** The card as the screen shows it: with the Picture and Palette windows over it (PictureWindows), and an external's
	 *  modal dialog over those */
	static void screen(Gfx g, OStack st) {
		draw(g, st, true, true);
		if (st != null && st.curCard != null) selectionAnts(g, st.curCard);
		if (st != null && st.curCard != null) HzAuthTool.draw(g, st.curCard, Platform.get().nanoTime()); // the button / field tool's frames
		if (st != null && st.curCard != null) for (CardOverlay.Window w : PictureWindows.shown()) window(g, w);
		if (st != null && st.curCard != null) for (CardOverlay.Window w : CardOverlay.windows()) if (w.modal) window(g, w);
	}
	/** The paint selection's marching ants (HzPaint.floating): a dotted frame round it, 4 black 4 white along the edges */
	static void selectionAnts(Gfx g, OCard cd) {
		int[] r = HzPaint.selectionBounds(cd);
		if (r == null) return;
		int l = r[0], t = r[1], w = r[2] - r[0], h = r[3] - r[1];
		if (w <= 0 || h <= 0) return;
		for (int k = 0; k < w; k++) { int c = (k & 4) == 0 ? BLACK : WHITE; g.setColor(c); g.fillRect(l + k, t, 1, 1); g.fillRect(l + k, t + h - 1, 1, 1); }
		for (int k = 0; k < h; k++) { int c = (k & 4) == 0 ? BLACK : WHITE; g.setColor(c); g.fillRect(l, t + k, 1, 1); g.fillRect(l + w - 1, t + k, 1, 1); }
	}
	/** An external's window over the card (P4-5): the frame the host gives it, then its pixels */
	static void window(Gfx g, CardOverlay.Window w) {
		g.save();
		int[] b = w.bounds();
		int pw = w.pixels.width, ph = w.pixels.height;
		if (w.frame == CardOverlay.DIALOG) {
			// System 7's dBoxProc: black 1, white 2, black 2, white 3 round the content
			int fw = b[2] - b[0], fh = b[3] - b[1];
			g.setColor(WHITE); g.fillRect(b[0], b[1], fw, fh);
			g.setColor(BLACK); g.drawRect(b[0], b[1], fw, fh); g.drawRect(b[0] + 3, b[1] + 3, fw - 6, fh - 6); g.drawRect(b[0] + 4, b[1] + 4, fw - 8, fh - 8);
		}
		else if (w.frame != CardOverlay.NO_FRAME) {
			int bar = CardOverlay.barHeight(w.frame);
			if (w.frame == CardOverlay.SHADOW) { g.setColor(BLACK); g.fillRect(b[0] + 2, b[1] + 2, b[2] - b[0] - 2, b[3] - b[1] - 2); }
			g.setColor(WHITE); g.fillRect(b[0], b[1], pw + 2, ph + bar + 2);
			g.setColor(BLACK); g.drawRect(b[0], b[1], pw + 2, ph + bar + 2);
			if (bar > 0) {
				g.drawLine(b[0], w.y - 1, b[0] + pw + 1, w.y - 1);
				// the bar's stripes (System 7: only the window that has the keyboard, and palettes), its close box, and the title
				// in a white gap over them
				if (w.active) for (int y = b[1] + 3; y < w.y - 3; y += 2) g.drawLine(b[0] + 2, y, b[0] + pw - 1, y);
				if (w.closeBox) {
					boolean small = w.frame == CardOverlay.WINDOID_BAR;
					int cx = small ? w.x + 1 : w.x + 7, cy = small ? b[1] + 2 : b[1] + 4, cs = small ? 7 : 11;
					g.setColor(WHITE); g.fillRect(cx - 1, cy, cs + 2, cs);
					g.setColor(BLACK); g.drawRect(cx, cy, cs, cs);
				}
				if (!w.title.isEmpty()) {
					g.setFont(w.frame == CardOverlay.WINDOID_BAR ? "Geneva" : "Chicago", w.frame == CardOverlay.WINDOID_BAR ? 9 : 12, 0);
					int tw = Math.min(pw - 8, g.textWidth(w.title)), tx = w.x + (pw - tw) / 2;
					g.setColor(WHITE); g.fillRect(tx - 4, b[1] + 1, tw + 8, bar - 1);
					g.setColor(BLACK); g.clip(tx, b[1], tw, bar); g.drawText(w.title, tx, w.y - 1 - (bar - g.ascent() + g.descent()) / 2 - g.descent() + 1);
				}
			}
		}
		g.restore();
		g.drawImage(w.pixels, w.x, w.y);
	}
	private static void card(Gfx g, OStack st) {
		g.setAlpha(1);
		g.setColor(WHITE);
		g.fillRect(0, 0, g.width(), g.height());
		if (st == null || st.curCard == null) return;
		OCard cd = st.curCard;
		OBackground bg = cd.bg;
		if (bg != null) {
			if (bg.showPict == null || bg.showPict) { HzImage p = HzLayers.picture(bg); if (p != null) g.drawImage(p, 0, 0); } // (with its paint layers)
			parts(g, bg, cd);
		}
		if (cd.showPict == null || cd.showPict) { HzImage p = HzLayers.picture(cd); if (p != null) g.drawImage(p, 0, 0); }
		parts(g, cd, cd);
		HzAddColor.composite(g, st); // AddColor's colours over it all, the card's black on top
	}

	private static void parts(Gfx g, OCardBase owner, OCard cd) {
		for (OObject o : owner.stacking()) {
			if (!o.isShown()) continue;
			if (o instanceof OButton b) { g.save(); button(g, b.forCard(cd), owner.stack); g.restore(); }
			else if (o instanceof OField f) { g.save(); field(g, f, owner.stack); g.restore(); }
			else if (o instanceof OShape s) HzShapes.draw(g, s); // (in the parts' order: OShape.layer)
		}
	}

	// ---------------------------------------------------------------- buttons

	static int argb(java.awt.Color c, int fallback) { return c == null ? fallback : 0xFF000000 | (c.getRGB() & 0xFFFFFF); }

	static void button(Gfx g, OButton b, OStack st) {
		int x = b.left, y = b.top, w = b.width, h = b.height;
		if (w <= 0 || h <= 0) return;
		// (a check box or radio button held is not hilited: its mark gets a thick frame, toggle)
		boolean held = pressed == b, toggles = b.style == 9 || b.style == 10;
		boolean hilite = b.getHilite() || held && !toggles, enabled = b.enabled;
		int fg = argb(b.color, BLACK), bgc = argb(b.bgColor, WHITE);
		boolean fills = hilite && b.hiliteColor != null && b.style != 9 && b.style != 10;
		// icon -1: HyperCard's PICT button, the picture named as the button (PartViews: by name in the stack and the stacks in
		// use), without the name
		boolean pictButton = b.icon == -1;
		HzImage icon = b.style == 9 || b.style == 10 || b.style == 8 ? null : b.icon > 0 || b.icon < -1 ? HzImages.icon(st, b.icon)
			: pictButton ? HzAddColor.pictNamed(st, b.name) : null;
		g.translate(x, y);
		g.clip(0, 0, w, h);
		// scaleIcon (HyperZebra's "scale icon" with "keep aspect ratio", OButton.paintScaledIcon): the icon fills the button's
		// rectangle (fitted into it when iconAspect), drawn alone - an opaque or rectangle style's body under it, no name, no
		// hilite. Mosslight RPG Engine's 144-pixel object and character pictures in tile-sized buttons showed their middles.
		if (icon != null && !pictButton && b.getScaleIcon() && b.style != 8 && b.style != 9 && b.style != 10) {
			if (b.style == 2 || b.style == 3) {
				g.setColor(hilite && b.hiliteColor != null ? argb(b.hiliteColor, BLACK) : bgc);
				g.fillRect(0, 0, w, h);
				if (b.style == 3) { g.setColor(fg); g.drawRect(0, 0, w - 1, h - 1); }
			}
			int[] r = iconFit(icon.width, icon.height, w, h, b.getIconAspect());
			// as the desktop's display zoom: a whole factor keeps the pixels (pixel art), any other is interpolated
			boolean whole = r[2] % icon.width == 0 && r[3] % icon.height == 0;
			g.drawImage(icon, r[0], r[1], r[2], r[3], !whole);
			return;
		}
		switch (b.style) {
			case 9: case 10: toggle(g, b, w, h, fg, bgc, hilite, held, enabled); break;
			case 8: popup(g, b, w, h, fg, bgc); break;
			default: {
				// the body: opaque styles fill it; a hilite fills it black (or hiliteColor), transparent and oval invert it
				boolean round = b.style == 5 || b.style == 0 || b.style == 6, oval = b.style == 7;
				int arc = b.style == 0 || b.style == 6 ? 12 : 16;
				boolean invertsIconOnly = hilite && icon != null && b.style == 1 && !fills;
				// the desktop draws standard and default buttons with the host's look (Aqua)
				boolean aqua = (b.style == 0 || b.style == 6) && !fills;
				if (aqua && b.style == 6) defaultButton(g, w, h, hilite);
				else if (aqua) pushButton(g, w, h, hilite);
				else if (b.style == 2 || b.style == 3 || b.style == 4 || round) {
					g.setColor(fills ? argb(b.hiliteColor, BLACK) : hilite && !invertsIconOnly ? fg : bgc);
					if (round) g.fillRoundRect(0, 0, w - 1, h - 1, arc); else if (b.style == 2) g.fillRect(0, 0, w, h); else g.fillRect(0, 0, w - 1, h - 1);
				} else if (fills) {
					g.setColor(argb(b.hiliteColor, BLACK));
					if (oval) g.fillOval(0, 0, w - 1, h - 1); else g.fillRect(0, 0, w, h);
				}
				if (!aqua) frame(g, b, w, h, fg, arc);
				boolean inverted = hilite && !fills && !invertsIconOnly && (b.style == 1 || oval);
				int nameColor = aqua ? fg : hilite && !fills && !inverted && !invertsIconOnly ? bgc : fg;
				// the icon
				int iconY = 0;
				if (icon != null) {
					// HyperCard J-2.3 (measured, every style, shadow included): half the difference rounded down, so a 23-pixel
					// button puts a 32-pixel icon at -5 (dividing rounded towards 0: -4); named, the icon is 6 pixels higher
					int ix = Math.floorDiv(w - icon.width, 2);
					iconY = b.showName && !pictButton ? Math.floorDiv(h, 2) - 22
						: pictButton ? (h - icon.height) / 2 // (Swing's centring: an odd-height PICT rounds up)
						: Math.floorDiv(h - icon.height, 2);
					g.drawImage(icon, ix, iconY);
					if (invertsIconOnly || hilite && !fills && !inverted && b.style != 1 && !aqua) invertOpaque(g, icon, ix, iconY);
				}
				if (b.showName && !pictButton) name(g, b, st, w, h, icon != null ? iconY + icon.height + 10 : -1, nameColor, hilite && !fills && !aqua);
				if (inverted) { if (oval) g.invertOval(0, 0, w - 1, h - 1); else g.invertRect(0, 0, w, h); }
				if (!enabled && b.style >= 1 && b.style <= 7 && b.style != 6) { g.setColor(WHITE); g.fillCheckerboard(1, 1, w - 2, h - 2); }
			}
		}
	}

	/** The frame of a button style: rectangle, shadow (two pixels right and down), roundRect (with its shadow), standard
	 *  and default (a rounded frame; default with a thick ring outside it). */
	private static void frame(Gfx g, OButton b, int w, int h, int fg, int arc) {
		g.setColor(fg);
		switch (b.style) {
			case 3: g.drawRect(0, 0, w, h); break;
			case 4:
				g.fillRect(0, 0, w - 1, 1); g.fillRect(0, 0, 1, h - 1);
				g.fillRect(w - 1, 2, 1, h - 2); g.fillRect(w - 2, 1, 1, h - 1);
				g.fillRect(2, h - 1, w - 2, 1); g.fillRect(1, h - 2, w - 1, 1);
				break;
			case 5:
				g.drawRoundRect(0, 0, w - 1, h - 1, arc);
				g.fillRect(arc / 2, h - 1, w - arc, 1); g.fillRect(w - 1, arc / 2, 1, h - arc);
				break;
			case 0: g.drawRoundRect(0, 0, w - 1, h - 1, arc); break;
			case 6:
				g.drawRoundRect(3, 3, w - 7, h - 7, arc - 4);
				g.drawRoundRect(0, 0, w - 1, h - 1, arc + 4); g.drawRoundRect(1, 1, w - 3, h - 3, arc + 2); g.drawRoundRect(2, 2, w - 5, h - 5, arc);
				break;
			default:
		}
	}

	/** Where a scaled icon goes in a w x h button: the whole rectangle, or (preserve) fitted into it and centred (OButton.iconFitBounds) */
	static int[] iconFit(int iw, int ih, int w, int h, boolean preserve) {
		if (!preserve || iw <= 0 || ih <= 0) return new int[] {0, 0, w, h};
		double ratio = Math.min((double) w / iw, (double) h / ih);
		int fw = Math.max(1, (int) Math.round(iw * ratio)), fh = Math.max(1, (int) Math.round(ih * ratio));
		return new int[] {(w - fw) / 2, (h - fh) / 2, fw, fh};
	}

	/** The opaque pixels of an icon at x,y inverted (a hilited icon). */
	private static void invertOpaque(Gfx g, HzImage icon, int x, int y) {
		for (int iy = 0; iy < icon.height; iy++) {
			int run = -1;
			for (int ix = 0; ix <= icon.width; ix++) {
				boolean on = ix < icon.width && (icon.argb[iy * icon.width + ix] >>> 24) >= 0x80;
				if (on && run < 0) run = ix;
				else if (!on && run >= 0) { g.invertRect(x + run, y + iy, ix - run, 1); run = -1; }
			}
		}
	}

	/** The name: centred (or 8 px from the edge), its baseline max(4, descent+leading) under the centre; under an icon in
	 *  9-point Geneva or Osaka on a white box (black when hilited). */
	private static void name(Gfx g, OButton b, OStack st, int w, int h, int iconBaseline, int color, boolean hilited) {
		String text = b.name == null ? "" : StackShown.name(b, b.name);
		if (text.isEmpty()) return;
		if (iconBaseline >= 0) {
			boolean jp = st != null && st.legacyTextEncoding == StackTextEncoding.JAPANESE;
			g.setFont(jp ? "Osaka" : "Geneva", 9, 0);
			int tw = g.textWidth(text), x = w / 2 - tw / 2;
			g.setColor(hilited ? argb(b.color, BLACK) : WHITE);
			g.fillRect(x - 2, iconBaseline - 10, tw + 4, 12);
			g.setColor(hilited ? WHITE : argb(b.color, BLACK));
			g.drawText(text, x, iconBaseline);
			return;
		}
		nameFont(g, b);
		int tw = nameWidth(g, b, text);
		int baseline = h / 2 + Math.max(4, nameDrop(g, b.textFont, b.textSize));
		int x = switch (b.textAlign) { case 0 -> 8; case 2 -> w - 8 - tw; default -> (w - (b.style == 0 || b.style == 6 ? 1 : 0)) / 2 - tw / 2; };
		nameText(g, b, text, x, baseline, color);
	}

	/** A button's name in bold in a font without a bold face (Geneva, Monaco, Osaka) is QuickDraw's smear, as OButton draws it
	 *  (MyTextArea.needsSmear): the plain face twice, 1 px apart, each glyph 1 px wider. Not under an icon (9-point plain). */
	private static boolean nameSmears(OButton b) { return (b.textStyle & 1) != 0 && noBoldFace(b.textFont); }
	private static void nameFont(Gfx g, OButton b) { g.setFont(b.textFont, b.textSize, nameSmears(b) ? b.textStyle & ~1 : b.textStyle); }
	private static int nameWidth(Gfx g, OButton b, String text) {
		int w = g.textWidth(text);
		if (nameSmears(b)) for (int i = 0; i < text.length(); i++) if (g.textWidth(text.substring(i, i + 1)) > 0) w++;
		return w;
	}
	private static void nameText(Gfx g, OButton b, String text, int x, int baseline, int color) {
		if (!nameSmears(b)) { styledText(g, text, x, baseline, b.textStyle, color); return; }
		int start = x;
		for (int i = 0; i < text.length(); i++) {
			String c = text.substring(i, i + 1);
			int cw = g.textWidth(c);
			styledText(g, c, x, baseline, b.textStyle & ~4, color);
			if (cw > 0) { styledText(g, c, x + 1, baseline, b.textStyle & ~4, color); x += cw + 1; }
		}
		if ((b.textStyle & 4) != 0) { g.setColor(color); g.fillRect(start, baseline + 1, x - start, 1); }
	}

	/** Text with HyperCard's drawn styles: underline, outline (the letters' rim in the colour, white inside), shadow. */
	static void styledText(Gfx g, String text, int x, int baseline, int style, int color) {
		if ((style & (8 | 16)) != 0) {
			g.setColor(color);
			int[][] around = (style & 16) != 0 ? new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}, {1, 1}, {2, 1}, {1, 2}} : new int[][]{{-1, 0}, {0, -1}, {1, 0}, {0, 1}};
			for (int[] d : around) g.drawText(text, x + d[0], baseline + d[1]);
			g.setColor(WHITE);
			g.drawText(text, x, baseline);
		} else {
			g.setColor(color);
			g.drawText(text, x, baseline);
		}
		if ((style & 4) != 0) { g.setColor(color); g.fillRect(x, baseline + 1, g.textWidth(text), 1); }
	}

	// ---------------------------------------------------------------- QuickDraw's styles in a field's runs (OField's drawing)

	/** Fonts the desktop's host (macOS) has no bold face of, whose bold OField draws as QuickDraw's smear (Fonts68k.hasBoldFace
	 *  is false for Geneva, Monaco and Hiragino, which Osaka and the Japanese gothic and mincho names map to) */
	static boolean noBoldFace(String font) {
		String f = font == null ? "" : font.trim().toLowerCase();
		return f.isEmpty() || f.equals("geneva") || f.equals("monaco") || f.startsWith("osaka") || f.contains("ゴシック") || f.contains("明朝") || f.contains("mincho")
			|| HzStrikeFont.has(font); //a stack's own strike: no bold face (StackStrikeFont)
	}

	/** OField.smears: bold by drawing every glyph again 1 px to the right (and 1 px wider), for a font without a bold face and
	 *  for outline and shadow, whose rim leaves no room for a bold face's strokes */
	static boolean smears(String font, int style) {
		return (style & 1) != 0 && ((style & (8 | 16)) != 0 || noBoldFace(font));
	}

	/** OField.styleExtra: pixels added to each glyph's advance for outline (+1), shadow (+2), condense (-1), extend (+1) */
	static int styleExtra(int style) {
		int e = 0;
		if ((style & 8) != 0) e++;
		if ((style & 16) != 0) e += 2;
		if ((style & 32) != 0) e--;
		if ((style & 64) != 0) e++;
		return e;
	}

	/** The run's font with the face OField takes (OField.fontStyle): bold only where the font has a bold face of its own */
	static void setRunFont(Gfx g, String font, int size, int style) {
		int face = (style & (8 | 16)) != 0 ? style & 2 : style & 3;
		if (smears(font, style)) face &= ~1;
		g.setFont(font, size, face);
	}

	/** OField.runWidth: the advance of a run with the smear's pixel and the style extras per glyph */
	static int runWidth(Gfx g, String s, String font, int style) {
		int w = g.textWidth(s), add = (smears(font, style) ? 1 : 0) + styleExtra(style);
		if (add != 0) for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= ' ') w += add;
		return w;
	}

	/** A run's text (OField.drawRun): at once, or glyph by glyph for the smear and the style extras */
	static void runText(Gfx g, String s, int x, int baseline, String font, int style, int color) {
		// underlined, or group text while groups are shown: OField's line on the baseline row from the run's start to 1 px past
		// its end (drawLine), a second one under it for outline and shadow; none under an empty run
		boolean under = OField.drawsUnderline(style, HZ.showGroups);
		style &= ~4;
		boolean smear = smears(font, style);
		int extra = styleExtra(style);
		int start = x;
		if (!smear && extra == 0) { styledText(g, s, x, baseline, style, color); x += g.textWidth(s); }
		else for (int k = 0; k < s.length(); k++) {
			String c = s.substring(k, k + 1);
			int cw = s.charAt(k) >= ' ' ? g.textWidth(c) : 0;
			styledText(g, c, x, baseline, style, color);
			if (cw > 0) { if (smear) styledText(g, c, x + 1, baseline, style, color); x += cw + (smear ? 1 : 0) + extra; }
		}
		if (under && x > start) { g.setColor(color); g.fillRect(start, baseline, x - start + 1, (style & (8 | 16)) != 0 ? 2 : 1); }
	}

	/** A check box or radio button: the 12-pixel mark 3 px in, centred, the name 4 px after it. held: pressed with the pointer
	 *  in it, the mark's frame two pixels thick (HyperCard's control tracking; the desktop's Aqua box darkens) */
	private static void toggle(Gfx g, OButton b, int w, int h, int fg, int bgc, boolean on, boolean held, boolean enabled) {
		int size = 12, x = 3, y = (h - size) / 2;
		int c = enabled ? fg : mix(fg, bgc);
		g.setColor(bgc);
		if (b.style == 10) g.fillOval(x, y, size, size); else g.fillRect(x, y, size, size);
		g.setColor(c);
		if (b.style == 10) {
			g.drawOval(x, y, size, size);
			if (held) g.drawOval(x + 1, y + 1, size - 2, size - 2);
			if (on) g.fillOval(x + 3, y + 3, 6, 6);
		} else {
			g.drawRect(x, y, size, size);
			if (held) g.drawRect(x + 1, y + 1, size - 2, size - 2);
			if (on) { g.drawLine(x, y, x + size - 1, y + size - 1); g.drawLine(x + size - 1, y, x, y + size - 1); }
		}
		if (!b.showName || b.name == null || b.name.isEmpty()) return;
		nameFont(g, b);
		nameText(g, b, StackShown.name(b, b.name), x + size + 4, h / 2 + 4, c);
	}

	/** A standard button as the desktop's look-and-feel shows it (measured with RenderCheck): a grey frame, a light vertical
	 *  gradient (darker while it is pressed) */
	private static void pushButton(Gfx g, int w, int h, boolean pressed) {
		int top = pressed ? 0xC4 : 0xE3, bottom = pressed ? 0xD6 : 0xF7;
		for (int y = 1; y < h - 1; y++) {
			int v = h <= 3 ? top : top + (bottom - top) * (y - 1) / (h - 3);
			g.setColor(0xFF000000 | v << 16 | v << 8 | v); g.fillRect(1, y, w - 2, 1);
		}
		g.setColor(0xFFAFAFAF); g.drawRect(0, 0, w, h);
	}

	/** A default button as the desktop's look-and-feel draws it (measured with RenderCheck, FankyMonsters): a flat blue bezel 3
	 *  pixels in, its corners about 6 pixels round; darker while pressed */
	private static void defaultButton(Gfx g, int w, int h, boolean pressed) {
		g.setColor(pressed ? 0xFF0869CE : 0xFF0A82FF);
		g.fillRoundRect(3, 3, Math.max(0, w - 6), Math.max(0, h - 7), 12);
	}

	/** A pop-up as the desktop shows it (its look-and-feel's, measured with RenderCheck): the title titleWidth wide on the left,
	 *  then a white rounded bezel with the chosen line and a blue badge with arrows up and down; a narrow one (the line wider
	 *  than the width less 50) has the small badge and its line from 8 pixels in. */
	private static void popup(Gfx g, OButton b, int w, int h, int fg, int bgc) {
		g.setFont(b.textFont, b.textSize, b.textStyle);
		int left = Math.max(0, Math.min(b.titleWidth, w - 20));
		if (b.showName && left > 0 && b.name != null) { g.setColor(fg); g.drawText(StackShown.name(b, b.name), 2, h / 2 + Math.max(4, nameDrop(g, b.textFont, b.textSize))); }
		String[] lines = b.getText().split("\n", -1);
		int sel = b.selectedLine;
		String chosen = sel >= 1 && sel <= lines.length ? StackShown.item(b, lines[sel - 1]) : "";
		int bw = w - left;
		boolean small = g.textWidth(chosen) > bw - 50;
		int bezelX = left + (small ? 1 : 4), bezelR = left + bw - 6, bezelH = small ? Math.min(h, 18) : Math.min(h, 20);
		g.setColor(WHITE); g.fillRoundRect(bezelX, 0, bezelR - bezelX, bezelH - 1, 10);
		g.setColor(0xFFE8E8E8); g.drawRoundRect(bezelX, 0, bezelR - bezelX - 1, bezelH - 2, 10);
		g.setColor(0xFFC1C1C1); g.fillRect(bezelX + 3, bezelH - 1, bezelR - bezelX - 6, 1);
		int bs = small ? 12 : 16, bx = left + bw - (small ? 20 : 24), by = small ? Math.max(0, (bezelH - bs) / 2 - 0) : 1;
		for (int i = 0; i < bs; i++) { // the badge: blue, lighter at the top
			int r = 0x28 - (0x28 - 0x01) * i / (bs - 1), gg = 0x91 - (0x91 - 0x7B) * i / (bs - 1);
			g.save(); g.clip(bx, by + i, bs, 1); g.setColor(0xFF000000 | r << 16 | gg << 8 | 0xFF); g.fillRoundRect(bx, by, bs, bs, small ? 6 : 8); g.restore();
		}
		g.setColor(WHITE);
		int cx = bx + bs / 2, u = small ? 2 : 3, mid = by + bs / 2;
		for (int i = 0; i < u + 1; i++) { // the arrows: a chevron up above the middle, one down below it
			g.fillRect(cx - 1 - i, mid - 2 - u + i, 1, 1); g.fillRect(cx + i, mid - 2 - u + i, 1, 1);
			g.fillRect(cx - 1 - i, mid + 1 + u - i, 1, 1); g.fillRect(cx + i, mid + 1 + u - i, 1, 1);
		}
		if (!chosen.isEmpty()) {
			g.save(); g.clip(bezelX + 1, 0, Math.max(0, bx - bezelX - 2), bezelH);
			g.setColor(fg); g.drawText(chosen, left + (small ? 8 : 17), small ? h / 2 + 2 : h / 2);
			g.restore();
		}
	}

	private static int mix(int a, int b) {
		return 0xFF000000 | (((((a >> 16) & 255) + ((b >> 16) & 255)) / 2) << 16) | (((((a >> 8) & 255) + ((b >> 8) & 255)) / 2) << 8) | (((a & 255) + (b & 255)) / 2);
	}

	// ---------------------------------------------------------------- fields

	/** A run of one style on a line. */
	static final class Run {
		String text; final String font; final int size, style, color; int x, width, start;
		Run(String text, String font, int size, int style, int color) { this.text = text; this.font = font; this.size = size; this.style = style; this.color = color; }
	}
	/** A line of a field: its runs, ascent and height. */
	static final class Line {
		final List<Run> runs = new ArrayList<>(); int ascent, height, width; int start, end;
	}

	/** The style of the field's text at each character: font, size, style, colour (OField.textStyleAt's rules). */
	/** The field whose text is laid out translated now (StackShown) and the style runs of that text; else a field's own */
	private static OField shownField;
	private static ArrayList<styleClass> shownRuns;
	private static ArrayList<styleClass> runsOf(OField f) { return f == shownField ? shownRuns : f.styleList; }

	static int[] runBounds(OField f, String text) {
		java.util.TreeSet<Integer> b = new java.util.TreeSet<>();
		b.add(0); b.add(text.length());
		ArrayList<styleClass> runs = runsOf(f);
		if (runs != null) for (styleClass r : runs) b.add(Math.max(0, Math.min(text.length(), r.textPosition)));
		int[] out = new int[b.size()]; int i = 0; for (int v : b) out[i++] = v; return out;
	}

	static void field(Gfx g, OField f, OStack st) {
		int x = f.left, y = f.top, w = f.width, h = f.height;
		if (w <= 0 || h <= 0) return;
		int fg = argb(f.color, BLACK), bgc = argb(f.bgColor, WHITE);
		g.translate(x, y);
		g.clip(0, 0, w, h);
		boolean scrolling = f.style == 5, shadow = f.style == 4;
		int innerW = shadow ? w - 2 : w, innerH = shadow ? h - 2 : h;
		if (f.style != 1) { g.setColor(bgc); g.fillRect(0, 0, innerW, innerH); }
		// showLines: OField.paintLineSeparators' light lines one pixel below each fixed line's baseline, across the field (a
		// scrolling field shows none); they were drawn under each line of text from its margin, a line or two off
		if (f.showLines && f.fixedLineHeight && !scrolling && w > 2 && h > 2) {
			int row = f.fixedRowHeight();
			// the text colour a quarter of the way from the background (MyTextArea.lineSeparatorColor)
			int t = fg, b = bgc;
			g.setColor(0xFF000000 | ((((b >> 16) & 255) * 3 + ((t >> 16) & 255)) / 4) << 16 | ((((b >> 8) & 255) * 3 + ((t >> 8) & 255)) / 4) << 8 | (((b & 255) * 3 + (t & 255)) / 4));
			for (int ly = OField.textTopMargin(1, f.wideMargins) + OField.fixedLineAscent(row) + 1; ly < h - 1; ly += row) g.fillRect(1, ly, w - 2, 1);
		}
		// text: inside the frame, under the scroll bar's column. A scrolling field is the desktop's Swing scroll pane: its view
		// (where the text is laid out as in the other styles) starts SCROLL_DX, SCROLL_DY in, inside a thin grey frame
		int textRight = scrolling ? innerW - 16 : innerW;
		g.save();
		if (scrolling) { g.clip(1, 2, Math.max(0, w - 18), Math.max(0, h - 5)); g.translate(SCROLL_DX, SCROLL_DY); }
		else g.clip(1, 1, Math.max(0, textRight - 2), Math.max(0, innerH - 2));
		int contentHeight = text(g, f, st, w, textRight, innerH, fg);
		g.restore();
		g.setColor(fg);
		if (f.style == 3 || f.style == 4) g.drawRect(0, 0, innerW, innerH);
		if (shadow) { g.fillRect(innerW, 3, 2, innerH - 1); g.fillRect(3, innerH, innerW - 1, 2); }
		if (scrolling) { synchronized (contentHeights) { contentHeights.put(f, contentHeight); } scrollPane(g, w, h, f.scroll, contentHeight); }
	}
	/** The height of each scrolling field's text when it was last drawn (its scroll bar's thumb: scrollThumb) */
	private static final java.util.Map<OField, Integer> contentHeights = new java.util.WeakHashMap<>();
	/** Where the scroll bar's track starts in a scrolling field (scrollPane: top 2, one more in) */
	static final int SCROLL_TRACK_TOP = 3;
	/** The height a scrolling field shows of its text */
	static int scrollVisible(OField f) { return f.height - SCROLL_DY - 3; }
	/** A scrolling field's scroll bar thumb as last drawn: {its top in the field, its length, the track's length, the largest
	 *  scroll}; null when it has none (the text fits, or the field was not drawn yet) */
	static int[] scrollThumb(OField f) {
		Integer content;
		synchronized (contentHeights) { content = contentHeights.get(f); }
		int h = f.height, top = 2, bottom = h - 3;
		if (content == null || bottom <= top) return null;
		int area = bottom - top - 4, visible = scrollVisible(f);
		if (content <= visible || area < 18) return null;
		int len = Math.max(18, (int) ((long) area * visible / content)), max = content - visible;
		int at = top + 1 + (int) ((long) (area - len) * Math.max(0, Math.min(f.scroll, max)) / max);
		return new int[] {at, len, area, max};
	}

	/** Where a scrolling field's view starts in its frame (the desktop's JScrollPane, measured with RenderCheck) */
	static final int SCROLL_DX = 2, SCROLL_DY = 2;

	/** Lays out and draws the text; returns the height of all its lines (for the scroll bar). */
	static int text(Gfx g, OField f, OStack st, int w, int right, int h, int fg) {
		String text = f.getText();
		if (text == null) text = "";
		// in HyperZebra's language when the stack has its own translations (StackShown): only the drawing changes
		TranslatedLines shown = StackShown.field(f, text);
		if (shown == null) return textAsShown(g, f, st, w, right, h, fg, text);
		shownField = f; shownRuns = shown.styles;
		try { return textAsShown(g, f, st, w, right, h, fg, shown.text); }
		finally { shownField = null; shownRuns = null; }
	}
	private static int textAsShown(Gfx g, OField f, OStack st, int w, int right, int h, int fg, String text) {
		text = text.replace("\r\n", "\n").replace('\r', '\n');
		int left = f.wideMargins ? 8 : 3, top = f.wideMargins ? 5 : 1;
		int wrapWidth = right - left - (f.wideMargins ? 8 : 4);
		List<Line> lines = fittedLayout(g, f, st, text, f.dontWrap ? Integer.MAX_VALUE : Math.max(1, wrapWidth), top, h);
		int yy = top - (f.style == 5 ? f.scroll : 0), total = 0;
		boolean open = TTalk.openField == f;
		// a locked field's selection that select made (HC 2.x hilites it; DiskLibrary's disk and file lists are shown so: select
		// line n of a locked field, kept by its idle handler) - the field the selection functions speak of, text only, no
		// insertion point; it was drawn for the open field alone
		boolean held = !open && HZ.clickField == f && !f.isListField() && UiHost.get().fieldHasView(f);
		int selStart = open || held ? f.view().getSelectionStart() : 0, selEnd = open || held ? f.view().getSelectionEnd() : 0;
		if (held && selEnd <= selStart) held = false;
		int fixed = Math.max(f.textHeight, f.textSize);
		for (int li = 0; li < lines.size(); li++) {
			Line line = lines.get(li);
			int lh = f.fixedLineHeight ? fixed : line.height, asc = f.fixedLineHeight ? fixed - fixed / 4 : line.ascent;
			if (f.autoSelect && f.selectedLine > 0 && lineNumberOf(text, line.start) == f.selectedLine) {
				g.setColor(HIGHLIGHT); g.fillRect(1, yy, right - 2, lh);
			}
			// centred on the wrap width less one, as OField's own drawing (horizontalMargin + (fldWidth + 1 - lineWidth) / 2)
			int lx = switch (f.textAlign) { case 1 -> left + (wrapWidth + 1 - line.width) / 2; case 2 -> left + wrapWidth - line.width; default -> left; };
			if (open || held) selection(g, line, li + 1 < lines.size() ? lines.get(li + 1).start : text.length() + 1, selStart, selEnd, lx, yy, lh);
			if (foundField == f && foundEnd > foundStart) found(g, line, li + 1 < lines.size() ? lines.get(li + 1).start : text.length() + 1, lx, yy, lh);
			if (yy + lh > 0 && yy < h) for (Run r : line.runs) {
				setRunFont(g, r.font, r.size, r.style);
				runText(g, r.text, lx + r.x, yy + asc, r.font, r.style, r.color);
			}
			yy += lh; total += lh;
		}
		return total + top;
	}

	/** The open field's selection on a line (a highlight), or its insertion point (a line of the text's colour). */
	private static void selection(Gfx g, Line line, int nextStart, int s, int e, int lx, int y, int lh) {
		int lineEnd = nextStart; // the line's characters: from line.start up to the next line's start
		if (s == e) {
			if (s < line.start || s >= lineEnd) return;
			int cx = lx + xOf(g, line, s);
			g.setColor(BLACK); g.fillRect(cx, y, 1, lh);
			return;
		}
		int a = Math.max(s, line.start), z = Math.min(e, lineEnd);
		if (a >= z) return;
		int x1 = lx + xOf(g, line, a), x2 = z >= lineEnd ? lx + line.width + 4 : lx + xOf(g, line, z);
		g.setColor(HIGHLIGHT); g.fillRect(x1, y, Math.max(1, x2 - x1), lh);
	}

	/** find's box on a line: its part of the found text shaded as the desktop's highlight (black at 48 of 255) */
	private static void found(Gfx g, Line line, int nextStart, int lx, int y, int lh) {
		int a = Math.max(foundStart, line.start), z = Math.min(foundEnd, nextStart);
		if (a >= z) return;
		int x1 = lx + xOf(g, line, a), x2 = z >= nextStart ? lx + line.width : lx + xOf(g, line, z);
		g.setColor(BLACK); g.setAlpha(48 / 255f);
		g.fillRect(x1, y, Math.max(1, x2 - x1), lh);
		g.setAlpha(1);
	}

	/** The x of character pos on a line (from the line's left). */
	private static int xOf(Gfx g, Line line, int pos) {
		for (Run r : line.runs) {
			if (pos >= r.start && pos <= r.start + r.text.length()) {
				setRunFont(g, r.font, r.size, r.style);
				return r.x + runWidth(g, r.text.substring(0, pos - r.start), r.font, r.style);
			}
		}
		return line.width;
	}

	/** Where the character pos of a field is drawn (field coordinates): {its left, the bottom of its line} - the selectedLoc */
	static int[] charPlace(Gfx g, OField f, OStack st, int pos) {
		String text = (f.getText() == null ? "" : f.getText()).replace("\r\n", "\n").replace('\r', '\n');
		int left = f.wideMargins ? 8 : 3, top = f.wideMargins ? 5 : 1;
		int right = f.style == 5 ? f.width - 16 : f.style == 4 ? f.width - 2 : f.width;
		int wrapWidth = right - left - (f.wideMargins ? 8 : 4);
		List<Line> lines = fittedLayout(g, f, st, text, f.dontWrap ? Integer.MAX_VALUE : Math.max(1, wrapWidth), top, f.style == 4 ? f.height - 2 : f.height);
		int yy = top - (f.style == 5 ? f.scroll : 0), fixed = Math.max(f.textHeight, f.textSize);
		int dx = f.style == 5 ? SCROLL_DX : 0, dy = f.style == 5 ? SCROLL_DY : 0;
		for (int li = 0; li < lines.size(); li++) {
			Line line = lines.get(li);
			int lh = f.fixedLineHeight ? fixed : line.height;
			int next = li + 1 < lines.size() ? lines.get(li + 1).start : text.length() + 1;
			if (pos < next || li == lines.size() - 1) {
				int lx = switch (f.textAlign) { case 1 -> left + (wrapWidth + 1 - line.width) / 2; case 2 -> left + wrapWidth - line.width; default -> left; };
				return new int[] {dx + lx + xOf(g, line, Math.max(line.start, pos)), dy + yy + lh};
			}
			yy += lh;
		}
		return new int[] {dx + left, dy + top};
	}

	/** The height of all a field's lines as field() lays them out (the text's top margin included): what scrollThumb takes as
	 *  the scrolling field's content */
	static int contentHeight(Gfx g, OField f, OStack st) {
		String own = f.getText() == null ? "" : f.getText();
		TranslatedLines shown = StackShown.field(f, own);
		if (shown != null) { shownField = f; shownRuns = shown.styles; }
		try {
			String text = (shown == null ? own : shown.text).replace("\r\n", "\n").replace('\r', '\n');
			int left = f.wideMargins ? 8 : 3, top = f.wideMargins ? 5 : 1;
			int right = f.style == 5 ? f.width - 16 : f.style == 4 ? f.width - 2 : f.width;
			int wrapWidth = right - left - (f.wideMargins ? 8 : 4);
			List<Line> lines = fittedLayout(g, f, st, text, f.dontWrap ? Integer.MAX_VALUE : Math.max(1, wrapWidth), top, f.style == 4 ? f.height - 2 : f.height);
			int fixed = Math.max(f.textHeight, f.textSize), total = 0;
			for (Line line : lines) total += f.fixedLineHeight ? fixed : line.height;
			return total + top;
		} finally { shownField = null; shownRuns = null; }
	}
	/** The largest scroll a scrolling field takes: its text's height less what it shows (0 when the text fits) */
	static int maxScroll(Gfx g, OField f, OStack st) { return Math.max(0, contentHeight(g, f, st) - scrollVisible(f)); }

	/** The character a click at x,y of a field (field coordinates) puts the insertion point before. */
	static int hitText(Gfx g, OField f, OStack st, int x, int y) {
		String own = f.getText() == null ? "" : f.getText();
		TranslatedLines shown = StackShown.field(f, own);
		if (shown == null) return hitShown(g, f, st, x, y, own);
		shownField = f; shownRuns = shown.styles;
		try { return shown.original(hitShown(g, f, st, x, y, shown.text)); } // the place in the field's own text
		finally { shownField = null; shownRuns = null; }
	}
	private static int hitShown(Gfx g, OField f, OStack st, int x, int y, String own) {
		if (f.style == 5) { x -= SCROLL_DX; y -= SCROLL_DY; }
		String text = own.replace("\r\n", "\n").replace('\r', '\n');
		int left = f.wideMargins ? 8 : 3, top = f.wideMargins ? 5 : 1;
		int right = f.style == 5 ? f.width - 16 : f.style == 4 ? f.width - 2 : f.width;
		int wrapWidth = right - left - (f.wideMargins ? 8 : 4);
		List<Line> lines = fittedLayout(g, f, st, text, f.dontWrap ? Integer.MAX_VALUE : Math.max(1, wrapWidth), top, f.style == 4 ? f.height - 2 : f.height);
		int yy = top - (f.style == 5 ? f.scroll : 0), fixed = Math.max(f.textHeight, f.textSize);
		for (int li = 0; li < lines.size(); li++) {
			Line line = lines.get(li);
			int lh = f.fixedLineHeight ? fixed : line.height;
			boolean last = li == lines.size() - 1;
			if (y < yy + lh || last) {
				int lx = switch (f.textAlign) { case 1 -> left + (wrapWidth + 1 - line.width) / 2; case 2 -> left + wrapWidth - line.width; default -> left; };
				int end = last ? text.length() : Math.max(line.start, lines.get(li + 1).start - 1);
				for (Run r : line.runs) {
					setRunFont(g, r.font, r.size, r.style);
					for (int k = 0; k < r.text.length(); k++) {
						int c0 = runWidth(g, r.text.substring(0, k), r.font, r.style), c1 = runWidth(g, r.text.substring(0, k + 1), r.font, r.style);
						if (x - lx < r.x + (c0 + c1) / 2) return r.start + k;
					}
				}
				return Math.min(end, text.length());
			}
			yy += lh;
		}
		return text.length();
	}

	/** The character under a click at x,y of a field (field coordinates) as OField.hitTestClick gives it on the desktop:
	 *  {its index (-1: no text), 1 when the click is right of it (past its line's end), the lines below the last line (counted
	 *  in its height)}; an empty line gives its return. The clickLine, clickChunk and clickText come from it (TUtil). */
	/**
	 * The line of a scrolling field's text with the character {@code offset} in it, as the field lays it out: {top, bottom} in
	 * the field's text from its top (scroll 0), and whether it is the last line (1) - for select's scroll into view
	 * (WindowUI.scrollSelectionIntoView on the desktop). The field's own text (a translation shown has its own lines).
	 */
	static int[] lineSpan(Gfx g, OField f, OStack st, int offset) {
		String text = (f.getText() == null ? "" : f.getText()).replace("\r\n", "\n").replace('\r', '\n');
		int top = f.wideMargins ? 5 : 1, left = f.wideMargins ? 8 : 3;
		int right = f.style == 5 ? f.width - 16 : f.style == 4 ? f.width - 2 : f.width;
		int wrapWidth = right - left - (f.wideMargins ? 8 : 4);
		List<Line> lines = text.isEmpty() ? List.of() : fittedLayout(g, f, st, text, f.dontWrap ? Integer.MAX_VALUE : Math.max(1, wrapWidth), top, f.style == 4 ? f.height - 2 : f.height);
		int yy = top, fixed = Math.max(f.textHeight, f.textSize);
		for (int li = 0; li < lines.size(); li++) {
			Line line = lines.get(li);
			int lh = Math.max(1, f.fixedLineHeight ? fixed : line.height);
			boolean last = li == lines.size() - 1;
			if (last || offset < lines.get(li + 1).start) return new int[] {yy, yy + lh, last ? 1 : 0};
			yy += lh;
		}
		return new int[] {top, top + Math.max(1, fixed), 1};
	}

	static int[] hitClick(Gfx g, OField f, OStack st, int x, int y) {
		String own = f.getText() == null ? "" : f.getText();
		TranslatedLines shown = StackShown.field(f, own);
		if (shown == null) return hitClickShown(g, f, st, x, y, own);
		shownField = f; shownRuns = shown.styles;
		try {
			int[] r = hitClickShown(g, f, st, x, y, shown.text);
			if (r[0] >= 0) r[0] = shown.original(r[0]);
			return r;
		} finally { shownField = null; shownRuns = null; }
	}
	private static int[] hitClickShown(Gfx g, OField f, OStack st, int x, int y, String own) {
		if (f.style == 5) { x -= SCROLL_DX; y -= SCROLL_DY; }
		String text = own.replace("\r\n", "\n").replace('\r', '\n');
		if (text.isEmpty()) return new int[] {-1, 0, 0};
		int left = f.wideMargins ? 8 : 3, top = f.wideMargins ? 5 : 1;
		int right = f.style == 5 ? f.width - 16 : f.style == 4 ? f.width - 2 : f.width;
		int wrapWidth = right - left - (f.wideMargins ? 8 : 4);
		List<Line> lines = fittedLayout(g, f, st, text, f.dontWrap ? Integer.MAX_VALUE : Math.max(1, wrapWidth), top, f.style == 4 ? f.height - 2 : f.height);
		int yy = top - (f.style == 5 ? f.scroll : 0), fixed = Math.max(f.textHeight, f.textSize);
		for (int li = 0; li < lines.size(); li++) {
			Line line = lines.get(li);
			int lh = Math.max(1, f.fixedLineHeight ? fixed : line.height);
			boolean last = li == lines.size() - 1;
			if (y < yy + lh || last) {
				int below = last && y >= yy + lh ? (y - (yy + lh)) / lh + 1 : 0;
				int lx = switch (f.textAlign) { case 1 -> left + (wrapWidth + 1 - line.width) / 2; case 2 -> left + wrapWidth - line.width; default -> left; };
				int lastChar = -1;
				for (Run r : line.runs) {
					setRunFont(g, r.font, r.size, r.style);
					for (int k = 0; k < r.text.length(); k++) {
						if (x - lx < r.x + runWidth(g, r.text.substring(0, k + 1), r.font, r.style)) return new int[] {r.start + k, 0, below};
						lastChar = r.start + k;
					}
				}
				if (lastChar < 0) return new int[] {Math.min(line.start, text.length()), 0, below}; // an empty line: its return
				return new int[] {lastChar, 1, below};
			}
			yy += lh;
		}
		return new int[] {-1, 0, 0};
	}

	private static int lineNumberOf(String text, int pos) {
		int n = 1;
		for (int i = 0; i < pos && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
		return n;
	}

	/** The font, size, style, colour of the run starting at character pos (the field's where the run says -1). */
	private static Object[] runStyle(OField f, OStack st, int pos, int shrink) {
		String font = f.textFont; int size = f.textSize - shrink, style = f.textStyle, color = argb(f.color, BLACK);
		ArrayList<styleClass> runs = runsOf(f);
		if (runs != null && st != null && st.styleList != null) {
			styleClass cur = null;
			for (styleClass r : runs) if (r.textPosition <= pos && (cur == null || r.textPosition >= cur.textPosition)) cur = r;
			if (cur != null) for (OStack.styleClass e : st.styleList) {
				if (e.id != cur.styleId) continue;
				if (e.style >= 0) style = e.style;
				if (e.size > 0) size = e.size;
				if (e.color >= 0) color = 0xFF000000 | e.color;
				if (e.font >= 0 && st.fontList != null) for (OStack.fontClass fc : st.fontList) if (fc.id == e.font) font = fc.name;
				break;
			}
		}
		return new Object[]{font, size, style, color};
	}

	/** Lines of the text: runs of one style each, broken where they pass the width (a word and the space after it; a
	 *  Japanese character anywhere; a word longer than the line at the character that does not fit). */
	static List<Line> layout(Gfx g, OField f, OStack st, String text, int width) { return layout(g, f, st, text, width, 0); }

	/**
	 * The lines as OField draws them: a HyperCard 2.x stack's field that is not scrolling and whose text runs past its bottom
	 * (a row more than 4 pixels below it with text from there on) takes its own size one point smaller (OField.smallfont: the
	 * host fonts are wider than the classic ones); the runs' own sizes stay
	 */
	static List<Line> fittedLayout(Gfx g, OField f, OStack st, String text, int width, int top, int h) {
		List<Line> lines = layout(g, f, st, text, width, 0);
		// (not for a stack's own strike: its metrics are HyperCard's, OField skips it too)
		if (f.style == 5 || f.textSize <= 6 || HzStrikeFont.has(f.textFont) || st == null || st.createdByVersion == null || !st.createdByVersion.startsWith("2")) return lines;
		int fixed = Math.max(f.textHeight, f.textSize), yy = top;
		for (int li = 0; li < lines.size(); li++) {
			Line line = lines.get(li);
			int lh = f.fixedLineHeight ? fixed : line.height;
			if (li > 0 && yy + lh > h + 4 && inkAfter(text, line.start)) return layout(g, f, st, text, width, 1);
			yy += lh;
		}
		return lines;
	}

	/** Whether the text from there on has anything but line breaks (OField's inkAfter) */
	private static boolean inkAfter(String text, int from) {
		for (int k = Math.max(0, from); k < text.length(); k++) { char ch = text.charAt(k); if (ch != '\r' && ch != '\n') return true; }
		return false;
	}

	static List<Line> layout(Gfx g, OField f, OStack st, String text, int width, int shrink) {
		List<Line> lines = new ArrayList<>();
		int[] bounds = runBounds(f, text);
		Line line = new Line();
		int x = 0;
		for (int k = 0; k + 1 < bounds.length || (k == 0 && text.isEmpty()); k++) {
			int a = bounds[k], z = k + 1 < bounds.length ? bounds[k + 1] : a;
			Object[] s = runStyle(f, st, a, shrink);
			String font = (String) s[0]; int size = (Integer) s[1], style = (Integer) s[2], color = (Integer) s[3];
			setRunFont(g, font, size, style);
			int[] m = metrics(g, font, size);
			int i = a;
			// the run of this style on the line so far: the pieces after it join it and are measured with it as one string, as
			// OField's own drawing measures a line's segment (glyph widths added one by one drifted: HyperPiece 2 card 4 broke
			// "あり|ます" where the desktop breaks "ありま|す")
			Run seg = null;
			while (i <= z) {
				// the next piece: up to a line break, a space (included) or a character that breaks anywhere
				int j = i;
				while (j < z && text.charAt(j) != '\n' && text.charAt(j) != ' ' && !breaksAnywhere(text.charAt(j))) j++;
				if (j < z && (text.charAt(j) == ' ' || breaksAnywhere(text.charAt(j))) && j == i) j++;
				else if (j < z && text.charAt(j) == ' ') j++;
				String piece = text.substring(i, j);
				if (seg != null && (line.runs.isEmpty() || line.runs.get(line.runs.size() - 1) != seg || seg.start + seg.text.length() != i)) seg = null;
				int pw = seg == null ? runWidth(g, piece, font, style) : runWidth(g, seg.text + piece, font, style) - seg.width;
				int trimmed = piece.endsWith(" ") ? runWidth(g, piece.substring(0, piece.length() - 1), font, style) : pw;
				// HyperCard J-2.3 (OField's own drawing): a word wraps when the line up to it with the space after it is wider than
				// the field allows (the last word of the text needs no space); Japanese breaks at any character, but closing
				// punctuation that does not fit stays at the end of the line
				boolean hangs = piece.length() == 1 && "、。，．,.」』）)！？!?ー".indexOf(piece.charAt(0)) >= 0;
				if (x > 0 && x + pw > width && !hangs) {
					// a word that began in the runs before (no space between) wraps whole
					List<Run> carried = i == a ? carryWord(g, line, text, i) : null;
					if (carried == null) { finish(line, lines, x); line = new Line(); line.start = i; x = 0; }
					else {
						Run kept = line.runs.isEmpty() ? null : line.runs.get(line.runs.size() - 1);
						finish(line, lines, kept == null ? 0 : kept.x + kept.width);
						line = new Line(); line.start = carried.get(0).start; x = 0;
						for (Run r : carried) {
							setRunFont(g, r.font, r.size, r.style);
							r.x = x; r.width = runWidth(g, r.text, r.font, r.style); x += r.width;
							int[] rm = metrics(g, r.font, r.size);
							line.ascent = Math.max(line.ascent, rm[0]); line.height = Math.max(line.height, rm[0] + rm[1]);
							line.runs.add(r);
						}
						setRunFont(g, font, size, style);
					}
					seg = null; pw = runWidth(g, piece, font, style);
				}
				// hung at the end of the line, it ends the line there (OField's own drawing, TextEdit): a return after it makes
				// an empty line (HyperPiece 2 card 41's 「ランダム出題」 then a return had one line less than on the desktop)
				boolean hung = hangs && x > 0 && x + pw > width;
				if (!piece.isEmpty()) {
					// a piece wider than a whole line: as many characters as fit
					if (x == 0 && trimmed > width && piece.length() > 1) {
						int n = 1;
						while (n < piece.length() && runWidth(g, piece.substring(0, n + 1), font, style) <= width) n++;
						piece = piece.substring(0, n); pw = runWidth(g, piece, font, style); j = i + n;
						seg = null;
					}
					if (seg != null) { seg.text += piece; seg.width += pw; x = seg.x + seg.width; }
					else {
						Run r = new Run(piece, font, size, style, color);
						r.x = x; r.width = pw; r.start = i; x += pw;
						line.runs.add(r);
						seg = r;
					}
				}
				if (hung && j < text.length()) {
					line.ascent = Math.max(line.ascent, m[0]); line.height = Math.max(line.height, m[0] + m[1]);
					finish(line, lines, x); line = new Line(); line.start = j; x = 0; seg = null;
				}
				line.ascent = Math.max(line.ascent, m[0]); line.height = Math.max(line.height, m[0] + m[1]);
				if (j < z && text.charAt(j) == '\n') { finish(line, lines, x); line = new Line(); line.start = j + 1; x = 0; j++; seg = null; }
				if (j == i) break;
				i = j;
				if (i >= z) break;
			}
			if (text.isEmpty()) break;
		}
		finish(line, lines, x);
		if (lines.size() > 1 && lines.get(lines.size() - 1).runs.isEmpty() && text.endsWith("\n")) { /* the empty line after the last return stays */ }
		return lines;
	}

	/** The start of a word the line ends with that goes on at {@code i} in the next run with no space between (a word in two
	 *  styles: Colorizing HyperCard's plain '"' then "Stack Disclaimer" in group), taken off the line: OField wraps the whole
	 *  word. Null when the text breaks at {@code i} anyway, or the word is all the line has. */
	private static List<Run> carryWord(Gfx g, Line line, String text, int i) {
		if (i <= 0 || i >= text.length()) return null;
		char before = text.charAt(i - 1);
		if (before == ' ' || before == '\n' || breaksAnywhere(before) || breaksAnywhere(text.charAt(i))) return null;
		List<Run> runs = line.runs;
		int n = runs.size(), k = n;
		while (k > 0 && lastBreak(runs.get(k - 1).text) < 0) k--;
		if (k == 0) return null;
		Run r = runs.get(k - 1);
		int b = lastBreak(r.text);
		List<Run> carried = new ArrayList<>();
		if (b + 1 < r.text.length()) {
			Run moved = new Run(r.text.substring(b + 1), r.font, r.size, r.style, r.color);
			moved.start = r.start + b + 1;
			r.text = r.text.substring(0, b + 1);
			setRunFont(g, r.font, r.size, r.style);
			r.width = runWidth(g, r.text, r.font, r.style);
			carried.add(moved);
		}
		for (int m = k; m < n; m++) carried.add(runs.get(m));
		while (runs.size() > k) runs.remove(runs.size() - 1);
		return carried.isEmpty() ? null : carried;
	}
	private static int lastBreak(String s) {
		for (int q = s.length() - 1; q >= 0; q--) { char c = s.charAt(q); if (c == ' ' || breaksAnywhere(c)) return q; }
		return -1;
	}

	private static void finish(Line line, List<Line> lines, int width) {
		line.width = width;
		if (line.height == 0) { line.ascent = 12; line.height = 16; }
		lines.add(line);
	}

	private static boolean breaksAnywhere(char c) {
		return c >= 0x3000 && c < 0xA000 || c >= 0xF900 && c < 0xFB00 || c >= 0xFF00 && c < 0xFFF0;
	}

	/** descent+leading for placing a button's name (FullButtonName.drop): Osaka's classic value, else the font's own */
	static int nameDrop(Gfx g, String font, int size) {
		if (font != null && font.equalsIgnoreCase("Osaka")) return OField.osakaDrop(size);
		return g.descent() + Math.max(0, g.leading());
	}

	/** {ascent, descent+leading} of a font: the classic strike's where HyperCard's are known, else the font's own. */
	static int[] metrics(Gfx g, String font, int size) {
		int[] m = OField.classicMetrics(font, size);
		if (m != null) return m;
		if (font != null && font.equalsIgnoreCase("Osaka")) return new int[]{size, Math.max(2, size / 4)};
		return new int[]{g.ascent(), g.descent() + Math.max(0, g.leading())};
	}

	/** A scrolling field's frame and bar as the desktop's look-and-feel draws them (HyperZebra keeps the host's scroll bar, not
	 *  HyperCard's): a dark top line and grey sides, a 16-pixel light track at the right, a rounded grey thumb, no arrows */
	static void scrollPane(Gfx g, int w, int h, int scroll, int content) {
		g.setColor(0xFF666666); g.fillRect(0, 0, w, 1);
		g.setColor(0xFF999999); g.fillRect(0, 1, 1, h - 2); g.fillRect(w - 1, 1, 1, h - 2); g.fillRect(0, h - 2, w, 1);
		int tx = w - 17, top = 2, bottom = h - 3;
		if (bottom <= top) return;
		g.setColor(0xFFE8E8E8); g.fillRect(tx, top, 1, bottom - top);
		g.setColor(0xFFFAFAFA); g.fillRect(tx + 1, top, 13, bottom - top);
		g.setColor(0xFFEDEDED); g.fillRect(tx + 14, top, 1, bottom - top);
		g.setColor(WHITE); g.fillRect(tx + 15, top, 1, bottom - top);
		int area = bottom - top - 4, visible = h - SCROLL_DY - 3;
		if (content <= visible || area < 18) return;
		int len = Math.max(18, (int) ((long) area * visible / content)), max = content - visible;
		int at = top + 1 + (int) ((long) (area - len) * Math.max(0, Math.min(scroll, max)) / max);
		g.setColor(0xFFC2C2C2); g.fillRoundRect(tx + 4, at, 8, len, 8);
	}
}

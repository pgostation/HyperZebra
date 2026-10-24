import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * The glyphs classic system fonts keep at control codes, which the host fonts standing in for them do not have (a
 * control character draws nothing there). Read from the fonts' strikes: Chicago (and Charcoal, its OS 8 successor) and
 * Osaka have $11 the Command key, $12 a check mark, $13 a diamond and $14 the Apple logo; Geneva, Monaco, Helvetica,
 * New York and the Heisei/細明朝 faces have none. Stacks use them in button names (HomeStack Suite's Apple-menu button,
 * Express Yourself's ◆ buttons) and field text (HyperCard Help's "⌘Q", HyperTalk Reference's "✓"). The text keeps
 * its bytes; only what is drawn and measured changes, one character for one, so offsets and selections stay.
 * Not mapped: Chicago's $10 (a hollow Apple) and Osaka's $1D-$1F (small marks with no clear Unicode counterpart).
 */
final class ClassicGlyphs {
	private ClassicGlyphs(){}

	static final char APPLE = '';
	/** $11..$14 as Unicode: ⌘ ✓ ◆ and the Apple logo (U+F8FF, only in Apple's fonts; see {@link #drawString}) */
	private static final char[] CONTROL = {'⌘', '✓', '◆', APPLE};

	/** Whether the classic font of that name draws $11..$14. */
	static boolean hasControlGlyphs(String font){
		if(font==null) return false;
		String f = font.toLowerCase();
		return f.equals("chicago") || f.equals("charcoal") || f.startsWith("osaka");
	}

	/** One character as the classic font draws it. */
	static char map(String font, char c){
		return c>=0x11 && c<=0x14 && hasControlGlyphs(font) ? CONTROL[c-0x11] : c;
	}

	/** A text as the classic font draws it (the same string when nothing changes). */
	static String map(String font, String text){
		if(text==null || !hasControlGlyphs(font)) return text;
		char[] cs = null;
		for(int i=0; i<text.length(); i++){
			char c = text.charAt(i);
			if(c>=0x11 && c<=0x14){
				if(cs==null) cs = text.toCharArray();
				cs[i] = CONTROL[c-0x11];
			}
		}
		return cs==null ? text : new String(cs);
	}

	/** Draws one piece of text at a pen position. */
	interface Drawer { void draw(Graphics g, String text, int x, int y); }

	/** Whether {@link #drawString} has to draw an Apple logo itself in the graphics' font. */
	static boolean needsAppleShape(Graphics g, String text){
		return text.indexOf(APPLE)>=0 && !g.getFont().canDisplay(APPLE);
	}

	/**
	 * Draws text with {@code drawer}; where the font has no Apple logo (Windows, Linux) each U+F8FF is drawn as a shape
	 * filling the advance the font gives it (its missing glyph's), so the text is laid out as measured.
	 */
	static void drawString(Graphics g, String text, int x, int y, Drawer drawer){
		if(!needsAppleShape(g, text)){ drawer.draw(g, text, x, y); return; }
		FontMetrics fm = g.getFontMetrics();
		int start = 0;
		for(int i=0; i<=text.length(); i++){
			if(i<text.length() && text.charAt(i)!=APPLE) continue;
			if(i>start){ String piece = text.substring(start, i); drawer.draw(g, piece, x, y); x += fm.stringWidth(piece); }
			if(i<text.length()){
				int w = fm.charWidth(APPLE);
				fillApple(g, x, y, w, fm.getAscent());
				x += w;
			}
			start = i+1;
		}
	}

	/** An Apple logo standing on the baseline at x, fitted to a w-wide advance and the font's ascent. */
	private static void fillApple(Graphics g, int x, int y, int w, int ascent){
		double h = Math.max(1, Math.min(ascent*0.9, w*14.0/12/0.85));
		double s = h/14, aw = 12*s;
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.fill(appleShape(x+(w-aw)/2, y-h, s));
		}finally{ g2.dispose(); }
	}

	/** The Apple logo (body with a bite, stem leaf) in a 12x14 box at (x,y), scaled by s. */
	static Shape appleShape(double x, double y, double s){
		Area body = new Area(new Ellipse2D.Double(0.5, 3.6, 6.4, 9.9));
		body.add(new Area(new Ellipse2D.Double(5.1, 3.6, 6.4, 9.9)));
		body.subtract(new Area(new Ellipse2D.Double(9.4, 5.6, 4.4, 4.4))); //the bite
		Path2D leaf = new Path2D.Double();
		leaf.moveTo(6.0, 3.4); leaf.curveTo(6.0, 1.6, 7.2, 0.4, 9.0, 0.2); leaf.curveTo(9.0, 2.0, 7.8, 3.2, 6.0, 3.4); leaf.closePath();
		body.add(new Area(leaf));
		AffineTransform t = new AffineTransform(); t.translate(x, y); t.scale(s, s);
		return t.createTransformedShape(body);
	}
}

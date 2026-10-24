import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import javax.swing.border.AbstractBorder;
import javax.swing.text.JTextComponent;

/**
 * The frame and drop shadow of a shadow-style field, as HyperCard draws them (measured in real HyperCard J-2.3 in the 68K VM):
 * a 1-pixel frame around (left, top, right-2, bottom-2), and a 2-pixel shadow along the right and bottom edges that starts
 * 3 pixels in from the top and the left. The two corners the shadow leaves out (top right, bottom left) are not drawn,
 * so the card shows through there, and the field's colour fills only the inside of the frame ({@link #interior}).
 * The frame takes the field's colour; the shadow is always black.
 */
class ShadowFieldBorder extends AbstractBorder {
	private static final long serialVersionUID = 1L;
	final OField field;

	ShadowFieldBorder(OField field){
		this.field = field;
	}

	/** The card's magnification the component is laid out at. */
	private static double scale(){
		return Math.max(1, PCARD.scale());
	}

	/** Window-pixel offset of logical position v inside a field whose logical edge is at origin (rounded like the card's pixels). */
	private static int phys(int origin, int v){
		return PCARD.toPhysicalLength(origin, v);
	}

	@Override
	public Insets getBorderInsets(Component c, Insets insets){
		double s = scale();
		int line = Math.max(1, (int)Math.round(s));
		int shadow = Math.max(1, (int)Math.round(2*s));
		insets.top = line; insets.left = line;
		insets.bottom = line+shadow; insets.right = line+shadow;
		if(c instanceof JTextComponent){
			//a border of its own replaces the text area's margin: the text being edited starts where the own drawing puts it
			//(left+3, wrapping 4 pixels earlier for the shadow, see MyTextArea.paintComponent)
			Insets m = ((JTextComponent)c).getMargin();
			if(m!=null){
				insets.top = Math.max(insets.top, m.top); insets.left = Math.max(insets.left, m.left);
				insets.bottom = m.bottom+shadow+line; insets.right = m.right+2*shadow;
			}
		}
		return insets;
	}

	/** The inside of the frame of a w×h (in the drawing's own pixels) shadow field, drawn at magnification s. */
	static Rectangle interior(int w, int h, double s){
		int line = Math.max(1, (int)Math.round(s));
		int shadow = Math.max(1, (int)Math.round(2*s));
		return new Rectangle(line, line, Math.max(0, w-shadow-2*line), Math.max(0, h-shadow-2*line));
	}

	@Override
	public void paintBorder(Component c, Graphics g, int x, int y, int w, int h){
		double s = scale();
		int lw = field.width, lh = field.height;
		int fx = field.left, fy = field.top;
		//frame: (0,0)-(width-2,height-2), 1 pixel wide
		int right = phys(fx, lw-2), bottom = phys(fy, lh-2);
		int line = Math.max(1, (int)Math.round(s));
		Color old = g.getColor();
		g.setColor(field.color);
		g.fillRect(x, y, right, line);
		g.fillRect(x, y+bottom-line, right, line);
		g.fillRect(x, y, line, bottom);
		g.fillRect(x+right-line, y, line, bottom);
		//shadow: right edge from 3 below the top, bottom edge from 3 right of the left
		g.setColor(Color.BLACK);
		paintShadow(g, x, y, lw-2, 3, 2, lh-3);
		paintShadow(g, x, y, 3, lh-2, lw-3, 2);
		g.setColor(old);
	}

	/** One shadow band, (lx,ly,lw,lh) in logical pixels relative to the field. */
	private void paintShadow(Graphics g, int x, int y, int lx, int ly, int lw, int lh){
		if(lw<=0 || lh<=0) return;
		int fx = field.left, fy = field.top;
		int px = phys(fx, lx), py = phys(fy, ly);
		int pw = phys(fx, lx+lw)-px, ph = phys(fy, ly+lh)-py;
		if(pw<=0 || ph<=0) return;
		g.fillRect(x+px, y+py, pw, ph);
	}
}

/**
 * The frame of a rectangle-style field as HyperCard draws it: one pixel around the whole field in the field's colour
 * (a grey bevel was drawn before; Colorizing HC's black-and-white copy of the card lost it). The text area keeps its margin
 * inside, as with {@link ShadowFieldBorder}.
 */
class RectangleFieldBorder extends AbstractBorder {
	private static final long serialVersionUID = 1L;
	final OField field;

	RectangleFieldBorder(OField field){
		this.field = field;
	}

	private static int line(){
		return Math.max(1, (int)Math.round(Math.max(1, PCARD.scale())));
	}

	@Override
	public Insets getBorderInsets(Component c, Insets insets){
		int line = line();
		insets.top = insets.left = insets.bottom = insets.right = line;
		if(c instanceof JTextComponent){
			Insets m = ((JTextComponent)c).getMargin();
			if(m!=null){
				insets.top = Math.max(line, m.top); insets.left = Math.max(line, m.left);
				insets.bottom = Math.max(line, m.bottom); insets.right = Math.max(line, m.right);
			}
		}
		return insets;
	}

	@Override
	public void paintBorder(Component c, Graphics g, int x, int y, int w, int h){
		int line = line();
		Color old = g.getColor();
		g.setColor(field.color);
		g.fillRect(x, y, w, line);
		g.fillRect(x, y+h-line, w, line);
		g.fillRect(x, y, line, h);
		g.fillRect(x+w-line, y, line, h);
		g.setColor(old);
	}
}

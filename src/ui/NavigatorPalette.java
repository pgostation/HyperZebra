import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * HyperCard's "Navigator" palette (palette "Navigator", "417,10"): HyperCard keeps it as PLTE 128 in the application, so
 * every stack can open it without having one of its own. The button layout and messages are HyperCard's (PLTE 128: three
 * rows of 23x21 cells from 168,72, each button a doMenu of the Go menu); the picture is drawn here.
 */
final class NavigatorPalette {
	private NavigatorPalette() {}

	static final String NAME = "Navigator";
	/** Where the picture's top left is in the PLTE's coordinates (its buttons are given there). */
	private static final Point ORIGIN = new Point(168, 72);
	/** HyperCard's buttons: top, left, bottom, right and the message. */
	private static final Object[][] BUTTONS = {
		{72, 168, 93, 191, "doMenu \"Back\""}, {72, 192, 93, 215, "doMenu \"Home\""},
		{72, 216, 93, 239, "doMenu \"Help\""}, {72, 240, 93, 263, "doMenu \"Recent\""},
		{94, 168, 115, 191, "doMenu \"First\""}, {94, 192, 115, 215, "doMenu \"Prev\""},
		{94, 216, 115, 239, "doMenu \"Next\""}, {94, 240, 115, 263, "doMenu \"Last\""},
		{116, 168, 137, 204, "doMenu \"Find...\""}, {116, 205, 137, 239, "doMenu \"Message\""},
		{116, 240, 137, 263, "doMenu \"Next window\""},
	};

	/** The palette as a PLTE of {@code rsrc} (not added to it): a windoid without title, buttons hilited while pressed. */
	static Rsrc.PlteClass plte(Rsrc rsrc) {
		Rsrc.PlteClass plte = rsrc.new PlteClass(128, NAME, 2052, true, 0, 128, new Point(ORIGIN));
		for(Object[] b : BUTTONS){
			int top = (Integer)b[0], left = (Integer)b[1], bottom = (Integer)b[2], right = (Integer)b[3];
			plte.objList.add(rsrc.new plteBtnObject(new Rectangle(left, top, right-left, bottom-top), (String)b[4]));
		}
		return plte;
	}

	/** The picture: the cells with their grid lines and a black line drawing in each. */
	static BufferedImage image() {
		BufferedImage bi = new BufferedImage(96, 66, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = bi.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 96, 66);
		g.setColor(Color.BLACK);
		g.fillRect(0, 21, 96, 1);
		g.fillRect(0, 43, 96, 1);
		for(int x : new int[]{23, 47, 71}) g.fillRect(x, 0, 1, 43);
		g.fillRect(36, 44, 1, 22);
		g.fillRect(71, 44, 1, 22);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setStroke(new BasicStroke(1));
		back(g, 0, 0); home(g, 24, 0); help(g, 48, 0); recent(g, 72, 0);
		first(g, 0, 22); prev(g, 24, 22); next(g, 48, 22); last(g, 72, 22);
		find(g, 0, 44); message(g, 37, 44); nextWindow(g, 72, 44);
		g.dispose();
		return bi;
	}

	/** A curved arrow back to the left. */
	private static void back(Graphics2D g, int x, int y) {
		g.drawArc(x+6, y+5, 12, 10, 270, 180);
		g.drawLine(x+12, y+15, x+8, y+15);
		triangleLeft(g, x+4, y+15, 4);
	}

	private static void home(Graphics2D g, int x, int y) {
		Path2D roof = new Path2D.Double();
		roof.moveTo(x+4, y+10); roof.lineTo(x+11, y+3); roof.lineTo(x+18, y+10);
		g.draw(roof);
		g.drawRect(x+6, y+10, 10, 7);
		g.drawRect(x+10, y+13, 2, 4);
	}

	private static void help(Graphics2D g, int x, int y) {
		g.drawArc(x+7, y+3, 8, 7, 0, 210);
		g.drawLine(x+15, y+7, x+11, y+11);
		g.drawLine(x+11, y+11, x+11, y+13);
		g.fillRect(x+10, y+16, 3, 2);
	}

	/** Cards stacked behind each other. */
	private static void recent(Graphics2D g, int x, int y) {
		for(int k=2; k>=0; k--){
			g.setColor(Color.WHITE);
			g.fillRect(x+4+k*3, y+3+k*2, 10, 9);
			g.setColor(Color.BLACK);
			g.drawRect(x+4+k*3, y+3+k*2, 10, 9);
		}
	}

	private static void first(Graphics2D g, int x, int y) {
		g.fillRect(x+6, y+5, 2, 11);
		triangleLeft(g, x+9, y+10, 5);
	}

	private static void prev(Graphics2D g, int x, int y) {
		triangleLeft(g, x+8, y+10, 5);
	}

	private static void next(Graphics2D g, int x, int y) {
		triangleRight(g, x+14, y+10, 5);
	}

	private static void last(Graphics2D g, int x, int y) {
		triangleRight(g, x+13, y+10, 5);
		g.fillRect(x+15, y+5, 2, 11);
	}

	/** A magnifying glass. */
	private static void find(Graphics2D g, int x, int y) {
		g.drawOval(x+11, y+4, 9, 9);
		g.setStroke(new BasicStroke(2));
		g.drawLine(x+19, y+13, x+24, y+18);
		g.setStroke(new BasicStroke(1));
	}

	/** The message box: a long box with a text line. */
	private static void message(Graphics2D g, int x, int y) {
		g.drawRect(x+5, y+6, 23, 9);
		g.drawLine(x+8, y+10, x+18, y+10);
		g.drawLine(x+19, y+8, x+19, y+12);
	}

	/** Two windows, the front one in front. */
	private static void nextWindow(Graphics2D g, int x, int y) {
		g.drawRect(x+3, y+3, 11, 9);
		g.drawLine(x+3, y+5, x+14, y+5);
		g.setColor(Color.WHITE);
		g.fillRect(x+8, y+8, 12, 10);
		g.setColor(Color.BLACK);
		g.drawRect(x+8, y+8, 11, 9);
		g.drawLine(x+8, y+10, x+19, y+10);
	}

	/** A solid triangle pointing left, its tip at x,y. */
	private static void triangleLeft(Graphics2D g, int x, int y, int size) {
		for(int k=0; k<=size; k++) g.drawLine(x+k, y-k, x+k, y+k);
	}

	private static void triangleRight(Graphics2D g, int x, int y, int size) {
		for(int k=0; k<=size; k++) g.drawLine(x-k, y-k, x-k, y+k);
	}
}

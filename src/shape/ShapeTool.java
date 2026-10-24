import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Card authoring tool, selected in the same way as ButtonTool and FieldTool. The selected shape shows its (rotated) frame with four
 * corner handles for resizing, a round handle above it for rotating (Shift snaps to 15°), and, for a gradient fill, the gradient's
 * two points: start and end of a linear one, centre and edge of a radial one.
 */
final class ShapeTool implements authtoolInterface {
    /** Handles: 0–3 corners (top left, top right, bottom left, bottom right), then these. */
    static final int ROTATE = 4, GRADIENT_START = 5, GRADIENT_END = 6;
    /** Distance of the rotation handle above the frame, in screen pixels. */
    private static final int KNOB = 24;
    /** The colour of the panel frame and its tag round a selected shape that is in a panel. */
    private static final Color PANEL = new Color(0x2F6FE0);
    final Canvas canvas = new Canvas();
    private AuthoringUndo.Snapshot before;
    private Point start;
    private Rectangle original;
    private int handle = -1;
    private boolean creating;

    static boolean active() { return AuthTool.tool instanceof ShapeTool; }
    static void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { PCARD.runOnEDT(ShapeTool::refresh); return; }
        if (AuthTool.tool instanceof ShapeTool tool) tool.install();
    }
    void install() {
        if (!ShapeEditor.active(ShapeEditor.selected)) ShapeEditor.selected = null;
        for (OShape shape : PCARD.pc.stack.curCard.shapeList)
            if (shape.view != null) shape.view.setVisible(!PaintTool.editBackground && shape.isShown());
        canvas.setBounds(0, 0, PCARD.toPhysical(PCARD.pc.stack.width), PCARD.toPhysical(PCARD.pc.stack.height));
        PCARD.pc.mainPane.add(canvas, 0);
        canvas.repaint();
        GMenu.updateAuthoringMenus();
    }
    @Override public void end() {
        finish();
        if (canvas.getParent() != null) canvas.getParent().remove(canvas);
        ShapeEditor.selected = null;
        PCARD.pc.mainPane.repaint();
    }
    boolean dragging() { return start != null; }
    private void finish() {
        AuthoringUndo.commit(before);
        before = null;
        start = null;
        original = null;
        creating = false;
        handle = -1;
    }
    static void select(OShape shape) {
        ShapeEditor.selected = ShapeEditor.active(shape) ? shape : null;
        refresh();
    }
    static OShape hit(Point p) {
        OCard card = PCARD.pc.stack.curCard;
        OCardBase[] owners = PaintTool.editBackground ? new OCardBase[]{card.bg} : new OCardBase[]{card, card.bg};
        for (OCardBase owner : owners) {
            if (owner == null) continue;
            for (int i = owner.shapeList.size() - 1; i >= 0; i--) {
                OShape shape = owner.shapeList.get(i);
                if (shape.isShown() && toCard(shape).createTransformedShape(new Rectangle2D.Double(-3, -3, shape.width + 6, shape.height + 6)).contains(p)) return shape;
            }
        }
        return null;
    }
    /** Shape coordinates to card coordinates, rotation included. */
    static AffineTransform toCard(OShape s) {
        AffineTransform t = AffineTransform.getTranslateInstance(s.left, s.top);
        t.concatenate(s.rotationTransform());
        return t;
    }
    private static Point2D card(OShape s, double x, double y) { return toCard(s).transform(new Point2D.Double(x, y), null); }
    private static Point2D local(OShape s, Point2D p) {
        try { return toCard(s).inverseTransform(p, null); } catch (java.awt.geom.NoninvertibleTransformException e) { return new Point2D.Double(); }
    }
    static boolean hasGradient(OShape s) { return s.gradientStart != null && s.gradientEnd != null && s.texturePng == null; }
    /** Card positions of the handles (null for those the shape does not have). */
    static Point2D[] handles(OShape s) {
        Point2D[] h = new Point2D[7];
        h[0] = card(s, 0, 0); h[1] = card(s, s.width, 0); h[2] = card(s, 0, s.height); h[3] = card(s, s.width, s.height);
        double r = Math.toRadians(s.rotation), knob = KNOB / (double) PCARD.scale();
        Point2D top = card(s, s.width / 2.0, 0);
        h[ROTATE] = new Point2D.Double(top.getX() + Math.sin(r) * knob, top.getY() - Math.cos(r) * knob);
        if (hasGradient(s)) {
            double cx = s.gradientX1 * s.width, cy = s.gradientY1 * s.height;
            if (s.radialGradient) {
                cx = Math.max(0, Math.min(s.width, cx)); cy = Math.max(0, Math.min(s.height, cy));
                h[GRADIENT_START] = card(s, cx, cy);
                h[GRADIENT_END] = card(s, cx + Math.max(1, s.gradientX2 * Math.max(s.width, s.height)), cy);
            } else {
                h[GRADIENT_START] = card(s, cx, cy);
                h[GRADIENT_END] = card(s, s.gradientX2 * s.width, s.gradientY2 * s.height);
            }
        }
        return h;
    }
    /** The handle under a card point, gradient points first (they can lie on a corner). */
    private static int handleAt(Point2D p) {
        if (!ShapeEditor.active(ShapeEditor.selected)) return -1;
        Point2D[] h = handles(ShapeEditor.selected);
        double reach = 7.0 / PCARD.scale();
        for (int i : new int[]{GRADIENT_END, GRADIENT_START, ROTATE, 0, 1, 2, 3})
            if (h[i] != null && h[i].distance(p) <= reach) return i;
        return -1;
    }
    void keyDown(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { select(null); e.consume(); return; }
        if (!ShapeEditor.active(ShapeEditor.selected) || dragging()) return;
        int key = e.getKeyCode();
        if (key == KeyEvent.VK_DELETE || key == KeyEvent.VK_BACK_SPACE) { ShapeEditor.deleteSelected(); e.consume(); return; }
        int dx = key == KeyEvent.VK_LEFT ? -1 : key == KeyEvent.VK_RIGHT ? 1 : 0;
        int dy = key == KeyEvent.VK_UP ? -1 : key == KeyEvent.VK_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return;
        AuthoringUndo.Snapshot snapshot = AuthoringUndo.capture();
        OShape s = ShapeEditor.selected;
        if (e.isMetaDown() || e.isControlDown()) s.setSize(s.width + dx, s.height + dy);
        else s.setTopLeft(s.left + dx, s.top + dy);
        AuthoringUndo.commit(snapshot);
        canvas.repaint();
        e.consume();
    }

    /** Rotation handle: the angle from the centre to the pointer (Shift: steps of 15°). */
    static void rotate(OShape s, Point2D p, boolean snap) {
        Point2D c = card(s, s.width / 2.0, s.height / 2.0);
        double a = Math.toDegrees(Math.atan2(p.getY() - c.getY(), p.getX() - c.getX())) + 90;
        if (snap) a = Math.round(a / 15) * 15;
        s.rotation = OShape.normalizeAngle(a);
        changed(s);
    }
    /**
     * Corner handle: the opposite corner stays where it is on the card and the dragged one follows the pointer, measured along the
     * shape's own (rotated) axes. Shift keeps the proportions.
     */
    static void resizeShape(OShape s, int corner, Rectangle from, Point2D p, boolean keepRatio) {
        double sx = (corner & 1) == 0 ? -1 : 1, sy = corner < 2 ? -1 : 1;
        double r = Math.toRadians(s.rotation), cos = Math.cos(r), sin = Math.sin(r);
        double cx = from.x + from.width / 2.0, cy = from.y + from.height / 2.0;
        double ox = -sx * from.width / 2.0, oy = -sy * from.height / 2.0;
        double fx = cx + ox * cos - oy * sin, fy = cy + ox * sin + oy * cos; // fixed corner on the card
        double vx = p.getX() - fx, vy = p.getY() - fy;
        double lx = vx * cos + vy * sin, ly = -vx * sin + vy * cos; // pointer along the shape's axes
        double w = Math.max(1, sx * lx), h = Math.max(1, sy * ly);
        if (keepRatio && from.width > 0 && from.height > 0) {
            double k = Math.max(w / from.width, h / from.height);
            w = Math.max(1, from.width * k); h = Math.max(1, from.height * k);
        }
        int nw = (int) Math.round(w), nh = (int) Math.round(h);
        double hx = sx * nw / 2.0, hy = sy * nh / 2.0;
        double ncx = fx + hx * cos - hy * sin, ncy = fy + hx * sin + hy * cos;
        s.setSize(nw, nh);
        s.setTopLeft((int) Math.round(ncx - nw / 2.0), (int) Math.round(ncy - nh / 2.0));
    }
    /** Gradient handle: moves that point of the gradient (Shift: a linear gradient's direction in steps of 15°). */
    static void moveGradient(OShape s, int which, Point2D p, boolean snap) {
        Point2D l = local(s, p);
        double w = Math.max(1, s.width), h = Math.max(1, s.height);
        if (s.radialGradient) {
            if (which == GRADIENT_START) {
                s.gradientX1 = (float) Math.max(0, Math.min(1, l.getX() / w));
                s.gradientY1 = (float) Math.max(0, Math.min(1, l.getY() / h));
            } else {
                double d = Math.hypot(l.getX() - s.gradientX1 * w, l.getY() - s.gradientY1 * h);
                s.gradientX2 = (float) Math.max(0.01, d / Math.max(w, h));
            }
        } else {
            double x = l.getX(), y = l.getY();
            if (snap) {
                double ax = (which == GRADIENT_START ? s.gradientX2 : s.gradientX1) * w, ay = (which == GRADIENT_START ? s.gradientY2 : s.gradientY1) * h;
                double len = Math.hypot(x - ax, y - ay), a = Math.round(Math.toDegrees(Math.atan2(y - ay, x - ax)) / 15) * 15;
                x = ax + len * Math.cos(Math.toRadians(a)); y = ay + len * Math.sin(Math.toRadians(a));
            }
            if (which == GRADIENT_START) { s.gradientX1 = (float) (x / w); s.gradientY1 = (float) (y / h); }
            else { s.gradientX2 = (float) (x / w); s.gradientY2 = (float) (y / h); }
        }
        changed(s);
    }
    private static void changed(OShape s) {
        if (s.parent instanceof OCardBase owner) owner.changed = true;
        s.repaintView();
    }

    final class Canvas extends JComponent {
        Canvas() {
            setOpaque(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            setToolTipText(GMenu.isMacOSX()
                ? International.tc("shape", "Command-drag to create; double-click for info; Shift-double-click for script; drag the round handle to rotate")
                : International.tc("shape", "Ctrl-drag to create; double-click for info; Shift-double-click for script; drag the round handle to rotate"));
            MouseAdapter mouse = new MouseAdapter() {
                private Point point(MouseEvent e) { return new Point(PCARD.toLogical(e.getX()), PCARD.toLogical(e.getY())); }
                private Point2D exact(MouseEvent e) { return new Point2D.Double(e.getX() / (double) PCARD.scale(), e.getY() / (double) PCARD.scale()); }
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    PCARD.pc.requestFocusInWindow();
                    Point p = point(e);
                    GUI.syncModifiers(e);
                    if (PCARD.editMode != 0) {
                        // Command-Option peek: the click opens the script of the shape under it.
                        boolean peek = GUI.isPeekClick(e);
                        OShape shape = peek ? hit(p) : null;
                        GUI.endPeek();
                        if (peek) { if (shape != null) { select(shape); ScriptEditor.openScriptEditor(PCARD.pc, shape); } return; }
                    }
                    handle = handleAt(exact(e));
                    creating = e.isMetaDown() || e.isControlDown();
                    if (creating) {
                        before = AuthoringUndo.capture();
                        ShapeEditor.selected = ShapeEditor.add(ShapeEditor.currentKind, p.x, p.y, 1, 1);
                        handle = -1;
                    } else if (handle < 0) select(hit(p));
                    if (!ShapeEditor.active(ShapeEditor.selected)) return;
                    if (!creating) before = AuthoringUndo.capture();
                    OShape s = ShapeEditor.selected;
                    start = p;
                    original = new Rectangle(s.left, s.top, s.width, s.height);
                    repaint();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (start == null || !ShapeEditor.active(ShapeEditor.selected)) return;
                    Point p = point(e);
                    OShape s = ShapeEditor.selected;
                    int dx = p.x - start.x, dy = p.y - start.y;
                    if (creating) {
                        if (e.isShiftDown()) { int side = Math.max(Math.abs(dx), Math.abs(dy)); dx = dx < 0 ? -side : side; dy = dy < 0 ? -side : side; }
                        s.setSize(Math.max(1, Math.abs(dx)), Math.max(1, Math.abs(dy)));
                        s.setTopLeft(start.x + Math.min(0, dx), start.y + Math.min(0, dy));
                        if (s.kind.equals("line")) {
                            s.pathData = "M " + (dx < 0 ? s.width : 0) + "," + (dy < 0 ? s.height : 0)
                                + " L " + (dx < 0 ? 0 : s.width) + "," + (dy < 0 ? 0 : s.height);
                            s.repaintView();
                        }
                    } else if (handle == ROTATE) rotate(s, exact(e), e.isShiftDown());
                    else if (handle == GRADIENT_START || handle == GRADIENT_END) moveGradient(s, handle, exact(e), e.isShiftDown());
                    else if (handle >= 0) resizeShape(s, handle, original, exact(e), e.isShiftDown());
                    else s.setTopLeft(original.x + dx, original.y + dy);
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) { finish(); GMenu.updateAuthoringMenus(); repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && !e.isMetaDown() && !e.isControlDown()) {
                        OShape shape = hit(point(e));
                        // Shift-double-click opens the script, as with the button and field tools.
                        if (shape != null && e.isShiftDown()) { select(shape); ScriptEditor.openScriptEditor(PCARD.pc, shape); repaint(); }
                        // A shape in a panel (an imported SVG is one) is one of a group: Panel Info lists them all;
                        // Option-double-click (Alt) opens the shape's own Shape Info.
                        else if (shape != null && shape.panel() != null && !e.isAltDown()) { select(shape); AuthDialog.openAuthDialog(PCARD.pc, "panel", shape.panel()); repaint(); }
                        else if (shape != null) { select(shape); ShapeEditor.open(shape); repaint(); }
                    }
                }
                @Override public void mouseMoved(MouseEvent e) {
                    int h = handleAt(exact(e));
                    int cursor = Cursor.DEFAULT_CURSOR;
                    if (h == ROTATE || h == GRADIENT_START || h == GRADIENT_END) cursor = Cursor.HAND_CURSOR;
                    else if (h >= 0) cursor = cornerCursor(h, ShapeEditor.selected.rotation);
                    setCursor(Cursor.getPredefinedCursor(cursor));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }
        /** The resize cursor nearest to the corner's direction on the card. */
        private int cornerCursor(int corner, float rotation) {
            double base = new double[]{225, 315, 135, 45}[corner] + rotation; // screen angle of the corner from the centre (y down)
            int octant = (int) Math.floorMod(Math.round(base / 45), 8);
            return new int[]{Cursor.E_RESIZE_CURSOR, Cursor.SE_RESIZE_CURSOR, Cursor.S_RESIZE_CURSOR, Cursor.SW_RESIZE_CURSOR,
                Cursor.W_RESIZE_CURSOR, Cursor.NW_RESIZE_CURSOR, Cursor.N_RESIZE_CURSOR, Cursor.NE_RESIZE_CURSOR}[octant];
        }
        @Override protected void paintComponent(Graphics graphics) {
            if (!ShapeEditor.active(ShapeEditor.selected)) return;
            OShape s = ShapeEditor.selected;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = PCARD.scale();
            AffineTransform screen = AffineTransform.getScaleInstance(scale, scale);
            if (s.panel() != null) paintPanel(g, s.panel(), s, screen);
            java.awt.Shape frame = screen.createTransformedShape(s.frame());
            g.setColor(Color.WHITE); g.setStroke(new BasicStroke(1)); g.draw(frame);
            g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{3, 3}, 0));
            g.draw(frame);
            g.setStroke(new BasicStroke(1));
            Point2D[] h = handles(s);
            Point2D top = screen.transform(card(s, s.width / 2.0, 0), null), knob = screen.transform(h[ROTATE], null);
            g.setColor(Color.BLACK); g.draw(new Line2D.Double(top, knob));
            dot(g, knob, 5, Color.WHITE, Color.BLACK);
            for (int i = 0; i < 4; i++) {
                Point2D c = screen.transform(h[i], null);
                g.setColor(Color.WHITE); g.fill(new Rectangle2D.Double(c.getX() - 3, c.getY() - 3, 6, 6));
                g.setColor(Color.BLACK); g.draw(new Rectangle2D.Double(c.getX() - 3, c.getY() - 3, 6, 6));
            }
            if (h[GRADIENT_START] != null) {
                Point2D a = screen.transform(h[GRADIENT_START], null), b = screen.transform(h[GRADIENT_END], null);
                g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3)); g.draw(new Line2D.Double(a, b));
                g.setColor(Color.BLACK); g.setStroke(new BasicStroke(1)); g.draw(new Line2D.Double(a, b));
                dot(g, a, 6, s.gradientStart, Color.BLACK);
                dot(g, b, 6, s.gradientEnd, Color.BLACK);
            }
            g.dispose();
        }
        /**
         * The panel round the selected shape: a frame round all its members (outlined each, while there are not too many) and a
         * tag with its name and number of members, so that a shape of an imported SVG shows it is one of a group.
         */
        private void paintPanel(Graphics2D g, OPanel panel, OShape selected, AffineTransform screen) {
            java.util.ArrayList<OObject> members = panel.members();
            g.setStroke(new BasicStroke(1));
            if (members.size() <= 400) {
                g.setColor(new Color(PANEL.getRed(), PANEL.getGreen(), PANEL.getBlue(), 110));
                for (OObject part : members) {
                    if (part == selected || !part.isShown()) continue;
                    java.awt.Shape frame = part instanceof OShape o ? o.frame() : new Rectangle(part.left, part.top, part.width, part.height);
                    g.draw(screen.createTransformedShape(frame));
                }
            }
            Rectangle2D box = screen.createTransformedShape(new Rectangle(panel.getLeft(), panel.getTop(), panel.getWidth(), panel.getHeight())).getBounds2D();
            box = new Rectangle2D.Double(box.getX() - 4, box.getY() - 4, box.getWidth() + 8, box.getHeight() + 8);
            g.setColor(new Color(255, 255, 255, 180)); g.setStroke(new BasicStroke(3)); g.draw(box);
            g.setColor(PANEL); g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 3}, 0)); g.draw(box);
            String text = PanelChooser.panelLabel(panel);
            g.setFont(getFont() != null ? getFont().deriveFont(java.awt.Font.BOLD, 11f) : new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11));
            java.awt.FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text) + 10, th = fm.getHeight() + 2;
            // Below the frame, clear of the rotation knob above the selected shape (above it when there is no room below).
            double tx = Math.max(0, Math.min(box.getX(), getWidth() - tw)), ty = box.getMaxY() + 1;
            if (ty + th > getHeight()) ty = Math.max(0, box.getY() - th - 1);
            java.awt.geom.RoundRectangle2D tag = new java.awt.geom.RoundRectangle2D.Double(tx, ty, tw, th, 6, 6);
            g.setColor(PANEL); g.fill(tag);
            g.setColor(Color.WHITE); g.drawString(text, (float) tx + 5, (float) ty + 1 + fm.getAscent());
        }
        private void dot(Graphics2D g, Point2D c, double r, Color fill, Color line) {
            Ellipse2D e = new Ellipse2D.Double(c.getX() - r, c.getY() - r, r * 2, r * 2);
            g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(c.getX() - r - 1, c.getY() - r - 1, r * 2 + 2, r * 2 + 2));
            g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue())); g.fill(e);
            g.setColor(line); g.draw(e);
        }
    }
}

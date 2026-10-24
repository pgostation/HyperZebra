import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Icon;

/** Classic bitmap silhouettes magnified with HyperZebra's existing Scale2x+ scaler. */
final class ClassicToolArt {
    static final Color INK = new Color(0x30353B);
    private static final Color PAPER = new Color(0xFFFDF7), BLUE = new Color(0xCBDDE8),
        MINT = new Color(0xD2E3D9), ROSE = new Color(0xEBCFD1), GOLD = new Color(0xEFE0B6),
        LILAC = new Color(0xDCD6E9);
    private static final Map<String, Cursor> CURSORS = new HashMap<>();

    private ClassicToolArt() {}

    static Icon icon(String tool, boolean selected) {
        return new Icon() {
            public int getIconWidth() { return 26; }
            public int getIconHeight() { return 26; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.translate(x, y);
                    quality(g);
                    boolean hover = c instanceof javax.swing.AbstractButton &&
                        ((javax.swing.AbstractButton)c).getModel().isRollover();
                    g.setColor(selected ? new Color(0xDFE9F0) : hover ? new Color(0xF9FBFC) : new Color(0xF3F3F0));
                    g.fill(new RoundRectangle2D.Double(.75,.75,24.5,24.5,6,6));
                    g.setColor(selected ? new Color(0x8CA6B9) : new Color(0xC5C9CA));
                    g.setStroke(new BasicStroke(1));
                    g.draw(new RoundRectangle2D.Double(.75,.75,24.5,24.5,6,6));
                    g.translate(1,1);
                    paint(g,tool,false);
                } finally { g.dispose(); }
            }
        };
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setStroke(new BasicStroke(1.15f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
    }
    private static void shape(Graphics2D g, Shape s, Color fill, boolean cursor) {
        if(cursor){g.setColor(Color.WHITE);g.setStroke(new BasicStroke(3.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(s);}
        if(fill!=null){g.setColor(fill);g.fill(s);}
        g.setColor(INK);g.setStroke(new BasicStroke(1.15f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(s);
    }
    private static Color tint(String tool) {
        switch(tool) {
        case "Browse": case "Pencil": case "Brush": case "Lasso": return GOLD;
        case "Eraser": case "Smudge": return ROSE;
        case "Field": case "Type": case "Oval": case "Shape": return LILAC;
        case "SprayCan": case "RegPoly": case "Stamp": case "MagicWand": return MINT;
        default: return BLUE;
        }
    }

    private static final Map<String, BufferedImage> ARTWORK = new HashMap<>();

    /** The authentic colored bitmap is the input to Scale2x+, including the cursor halo. */
    private static BufferedImage sourceImage(String tool, boolean cursor) {
        ClassicToolBitmaps.Sprite sprite=ClassicToolBitmaps.get(tool,cursor);
        BufferedImage image=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);
        int ox=(24-sprite.width)/2, oy=(24-sprite.height)/2;
        Graphics2D g=image.createGraphics();
        try {
            quality(g);
            if(cursor) {
                g.setColor(Color.WHITE);
                for(int y=0;y<sprite.height;y++)for(int x=0;x<sprite.width;x++)
                    if(sprite.rows[y].charAt(x)=='#'||sprite.paper[y][x]) {
                        g.fillRect(ox+x-1,oy+y,3,1);g.fillRect(ox+x,oy+y-1,1,3);
                    }
            }
            for(int y=0;y<sprite.height;y++)for(int x=0;x<sprite.width;x++) {
                if(sprite.rows[y].charAt(x)=='#') {
                    g.setColor(tool.equals("PaintBucket")&&x>=13&&y>=8 ? new Color(0x9B777D) :
                        tool.equals("Field")&&x>0&&x<sprite.width-1&&y>2&&y<12 ? new Color(0x858092) : INK);
                } else if(sprite.paper[y][x]) {
                    g.setColor(tool.equals("Lasso")||tool.equals("Type") ? PAPER : tint(tool));
                } else continue;
                g.fillRect(ox+x,oy+y,1,1);
            }
        } finally {g.dispose();}
        return image;
    }

    private static synchronized BufferedImage artwork(String tool, boolean cursor, int factor) {
        String key=tool+":"+cursor+":"+factor;
        return ARTWORK.computeIfAbsent(key,ignored->
            SmoothScaler.scaleImage(sourceImage(tool,cursor),factor,
                // The crosshair has no diagonal; keep its short orthogonal ticks square.
                tool.equals("Crosshair")?SmoothScaler.BRESENHAM:SmoothScaler.SCALE2X));
    }

    static void paint(Graphics2D graphics, String tool, boolean cursor) {
        java.awt.geom.AffineTransform transform=graphics.getTransform();
        double sx=Math.hypot(transform.getScaleX(),transform.getShearY());
        double sy=Math.hypot(transform.getShearX(),transform.getScaleY());
        // Work from the original at each display factor, as SmoothZoom does. Never
        // repeatedly scale a previous result. Nearest-neighbor adds no further blur.
        int factor=Math.max(2,(int)Math.ceil(Math.max(sx,sy)));
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(artwork(tool,cursor,factor),0,0,24,24,null);
        } finally {g.dispose();}
    }

    static Point hotspot(String tool) {
        ClassicToolBitmaps.Sprite s=ClassicToolBitmaps.get(tool,true);
        return new Point((24-s.width)/2+s.hotX,(24-s.height)/2+s.hotY);
    }

    static BufferedImage cursorImage(String tool, int width, int height) {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        try {g.scale(width/24.0,height/24.0);paint(g,tool,true);} finally {g.dispose();}
        return image;
    }

    static synchronized Cursor cursor(String tool) {
        if(CURSORS.containsKey(tool))return CURSORS.get(tool);
        int fallback=tool.equals("Browse")||tool.equals("Smudge")?Cursor.HAND_CURSOR:
            tool.equals("IBeam")?Cursor.TEXT_CURSOR:tool.equals("Crosshair")?Cursor.CROSSHAIR_CURSOR:Cursor.DEFAULT_CURSOR;
        Cursor result=Cursor.getPredefinedCursor(fallback);
        if(!GraphicsEnvironment.isHeadless()) {
            Toolkit kit=Toolkit.getDefaultToolkit();Dimension size=kit.getBestCursorSize(24,24);
            if(size.width>0&&size.height>0) {
                Point h=hotspot(tool);
                Image image=new BaseMultiResolutionImage(cursorImage(tool,size.width,size.height),
                    cursorImage(tool,size.width*2,size.height*2),cursorImage(tool,size.width*3,size.height*3));
                result=kit.createCustomCursor(image,new Point(Math.min(size.width-1,(int)Math.round(h.x*size.width/24.0)),
                    Math.min(size.height-1,(int)Math.round(h.y*size.height/24.0))),"classic-"+tool);
            }
        }
        CURSORS.put(tool,result);return result;
    }

    /** Preserve the footprint cue for tools whose size is selected by the user. */
    static Cursor footprint(String tool, int diameter) {
        if(GraphicsEnvironment.isHeadless())return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
        Toolkit kit=Toolkit.getDefaultToolkit();Dimension size=kit.getBestCursorSize(32,32);
        if(size.width==0||size.height==0)return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
        Image[] images=new Image[3];
        for(int scale=1;scale<=3;scale++) {
            BufferedImage image=new BufferedImage(size.width*scale,size.height*scale,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=image.createGraphics();g.scale(scale,scale);quality(g);
            int d=Math.min(Math.min(size.width,size.height)-4,Math.max(2,diameter));
            double x=size.width/2.0-d/2.0,y=size.height/2.0-d/2.0;
            Shape s=tool.equals("Eraser")?new Rectangle2D.Double(x,y,d,d):new Ellipse2D.Double(x,y,d,d);
            shape(g,s,tool.equals("Eraser")?new Color(0xFFF7F7):null,true);
            g.dispose();images[scale-1]=image;
        }
        return kit.createCustomCursor(new BaseMultiResolutionImage(images),new Point(size.width/2,size.height/2),"classic-"+tool);
    }
}

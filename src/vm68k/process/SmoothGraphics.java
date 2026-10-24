import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

/**
 * Java2D drawing into a pixel buffer and into its smooth zoom plane at once ({@link SmoothZoom}): every call goes to the 1x
 * graphics (the buffer, or its staged copy) and again to the plane's, which draws the same in hi-res pixels - text with its
 * outlines at the 1x advances, shapes anti-aliased at the plane's resolution, images magnified by the plane's algorithm.
 * Questions are answered by the 1x graphics, so the code drawing never notices.
 */
final class SmoothGraphics extends Graphics2D {
	private final Graphics2D g,hi;
	/** From the 1x graphics' device space to the plane's pixels. */
	private final AffineTransform toHi;
	final int scale,algorithm;
	/** Shared by the copies {@link #create()} makes: set once anything was drawn. */
	private final boolean[] drew;
	private boolean xor;

	SmoothGraphics(Graphics2D g,Graphics2D hi,AffineTransform toHi,int scale,int algorithm){this(g,hi,toHi,scale,algorithm,new boolean[1]);
		hi.setRenderingHints(g.getRenderingHints());smoothHints();followTransform();hi.setClip(g.getClip());hi.setColor(g.getColor());hi.setFont(g.getFont());}
	private SmoothGraphics(Graphics2D g,Graphics2D hi,AffineTransform toHi,int scale,int algorithm,boolean[] drew){this.g=g;this.hi=hi;this.toHi=toHi;this.scale=scale;this.algorithm=algorithm;this.drew=drew;}
	/** Whether anything was drawn through these graphics (or a copy). */
	boolean drew(){return drew[0];}
	private void mark(){drew[0]=true;}
	/** The plane's graphics take the 1x transform, carried into its pixels. */
	private void followTransform(){AffineTransform t=new AffineTransform(toHi);t.concatenate(g.getTransform());hi.setTransform(t);}
	/** Hi-res drawing is anti-aliased (shapes and text), except in XOR mode, whose partial pixels would not invert back. */
	private void smoothHints(){
		hi.setRenderingHint(RenderingHints.KEY_ANTIALIASING,xor?RenderingHints.VALUE_ANTIALIAS_OFF:RenderingHints.VALUE_ANTIALIAS_ON);
		hi.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,xor?RenderingHints.VALUE_TEXT_ANTIALIAS_OFF:RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		hi.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		hi.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
	}

	// ------------------------------------------------------------------ text

	/** Fonts68k.drawString onto both: the 1x run as ever, and in the plane the hi-res run at the same advances. */
	void drawRun(Font font,String text,int x,int baseline){
		Fonts68k.drawString(g,font,text,x,baseline);
		if(text==null||text.isEmpty())return;mark();
		if(font instanceof BitmapFont68k){BitmapFont68k strike=(BitmapFont68k)font;
			drawHiMask(strike.coverage(strike.encode(text),false,scale,algorithm),x-strike.coverageLeft(),baseline-strike.maskAscent());return;}
		if(font instanceof StyledFont68k){StyledFont68k styled=(StyledFont68k)font;
			drawHiMask(styled.coverage(text,false,scale),x-styled.maskLeft(),baseline-styled.maskAscent());return;}
		Font saved=hi.getFont();Fonts68k.drawPlaced(hi,font,text,x,baseline);hi.setFont(saved);
	}
	/** A hi-res coverage mask in the current colour with its top-left at user point (x,y). */
	private void drawHiMask(BufferedImage coverage,int x,int y){drawHiImage(Fonts68k.tinted(coverage,g.getColor().getRGB()),x,y);}
	/** An image already in plane pixels, its top-left at user point (x,y). */
	private void drawHiImage(BufferedImage image,double x,double y){
		Point2D p=hi.getTransform().transform(new Point2D.Double(x,y),null);
		AffineTransform saved=hi.getTransform();
		hi.setTransform(AffineTransform.getTranslateInstance(Math.round(p.getX()),Math.round(p.getY())));
		try{hi.drawImage(image,0,0,null);}finally{hi.setTransform(saved);}
	}
	@Override public void drawString(String str,int x,int y){g.drawString(str,x,y);mark();Fonts68k.drawPlaced(hi,g.getFont(),str,x,y);hi.setFont(g.getFont());}
	@Override public void drawString(String str,float x,float y){g.drawString(str,x,y);mark();Fonts68k.drawPlaced(hi,g.getFont(),str,x,y);hi.setFont(g.getFont());}
	@Override public void drawString(AttributedCharacterIterator iterator,int x,int y){g.drawString(iterator,x,y);mark();iterator.first();hi.drawString(iterator,x,y);}
	@Override public void drawString(AttributedCharacterIterator iterator,float x,float y){g.drawString(iterator,x,y);mark();iterator.first();hi.drawString(iterator,x,y);}
	@Override public void drawGlyphVector(GlyphVector gv,float x,float y){g.drawGlyphVector(gv,x,y);mark();hi.drawGlyphVector(gv,x,y);}

	// ------------------------------------------------------------------ images

	/** The largest image (pixels) magnified by the plane's algorithm: icons and pictures; a movie frame or a photograph drawn
	 *  every frame is left to Java2D's interpolation (a new magnified image per frame would be megabytes each time). */
	private static final int ALGORITHM_IMAGE_LIMIT=512*512;
	/** An image drawn {@code w x h} at (x,y): magnified by the plane's algorithm when it lands at a whole factor (icons, a
	 *  picture at its own size or doubled), else scaled by Java2D. */
	private void drawImageHi(Image image,int x,int y,int w,int h){
		mark();
		BufferedImage b=image instanceof BufferedImage?(BufferedImage)image:null;
		int type=g.getTransform().getType();
		if(b!=null&&(type&~AffineTransform.TYPE_TRANSLATION)==0&&w>0&&h>0&&b.getWidth()>0&&b.getHeight()>0&&(long)b.getWidth()*b.getHeight()<=ALGORITHM_IMAGE_LIMIT
			&&(long)w*h*scale*scale<=(16L<<20)&&w%b.getWidth()==0&&h%b.getHeight()==0&&w/b.getWidth()==h/b.getHeight()){
			drawHiImage(SmoothScaler.scaleImage(b,w/b.getWidth()*scale,algorithm),x,y);return;}
		hi.drawImage(image,x,y,w,h,null);
	}
	@Override public boolean drawImage(Image img,int x,int y,ImageObserver observer){boolean r=g.drawImage(img,x,y,observer);int w=img.getWidth(null),h=img.getHeight(null);if(w>0&&h>0)drawImageHi(img,x,y,w,h);return r;}
	@Override public boolean drawImage(Image img,int x,int y,int width,int height,ImageObserver observer){boolean r=g.drawImage(img,x,y,width,height,observer);drawImageHi(img,x,y,width,height);return r;}
	@Override public boolean drawImage(Image img,int x,int y,Color bgcolor,ImageObserver observer){boolean r=g.drawImage(img,x,y,bgcolor,observer);mark();hi.drawImage(img,x,y,bgcolor,null);return r;}
	@Override public boolean drawImage(Image img,int x,int y,int width,int height,Color bgcolor,ImageObserver observer){boolean r=g.drawImage(img,x,y,width,height,bgcolor,observer);mark();hi.drawImage(img,x,y,width,height,bgcolor,null);return r;}
	@Override public boolean drawImage(Image img,int dx1,int dy1,int dx2,int dy2,int sx1,int sy1,int sx2,int sy2,ImageObserver observer){
		boolean r=g.drawImage(img,dx1,dy1,dx2,dy2,sx1,sy1,sx2,sy2,observer);
		if(img instanceof BufferedImage&&dx2>dx1&&dy2>dy1&&sx2>sx1&&sy2>sy1&&sx1>=0&&sy1>=0&&sx2<=img.getWidth(null)&&sy2<=img.getHeight(null)){
			drawImageHi(((BufferedImage)img).getSubimage(sx1,sy1,sx2-sx1,sy2-sy1),dx1,dy1,dx2-dx1,dy2-dy1);return r;}
		mark();hi.drawImage(img,dx1,dy1,dx2,dy2,sx1,sy1,sx2,sy2,null);return r;
	}
	@Override public boolean drawImage(Image img,int dx1,int dy1,int dx2,int dy2,int sx1,int sy1,int sx2,int sy2,Color bgcolor,ImageObserver observer){boolean r=g.drawImage(img,dx1,dy1,dx2,dy2,sx1,sy1,sx2,sy2,bgcolor,observer);mark();hi.drawImage(img,dx1,dy1,dx2,dy2,sx1,sy1,sx2,sy2,bgcolor,null);return r;}
	@Override public boolean drawImage(Image img,AffineTransform xform,ImageObserver obs){boolean r=g.drawImage(img,xform,obs);mark();hi.drawImage(img,xform,null);return r;}
	@Override public void drawImage(BufferedImage img,BufferedImageOp op,int x,int y){g.drawImage(img,op,x,y);mark();hi.drawImage(img,op,x,y);}
	@Override public void drawRenderedImage(RenderedImage img,AffineTransform xform){g.drawRenderedImage(img,xform);mark();hi.drawRenderedImage(img,xform);}
	@Override public void drawRenderableImage(RenderableImage img,AffineTransform xform){g.drawRenderableImage(img,xform);mark();hi.drawRenderableImage(img,xform);}

	// ------------------------------------------------------------------ shapes

	@Override public void draw(Shape s){g.draw(s);mark();hi.draw(s);}
	@Override public void fill(Shape s){g.fill(s);mark();hi.fill(s);}
	@Override public void drawLine(int x1,int y1,int x2,int y2){g.drawLine(x1,y1,x2,y2);mark();hi.drawLine(x1,y1,x2,y2);}
	@Override public void fillRect(int x,int y,int width,int height){g.fillRect(x,y,width,height);mark();hi.fillRect(x,y,width,height);}
	@Override public void clearRect(int x,int y,int width,int height){g.clearRect(x,y,width,height);mark();hi.clearRect(x,y,width,height);}
	@Override public void drawRoundRect(int x,int y,int width,int height,int arcWidth,int arcHeight){g.drawRoundRect(x,y,width,height,arcWidth,arcHeight);mark();hi.drawRoundRect(x,y,width,height,arcWidth,arcHeight);}
	@Override public void fillRoundRect(int x,int y,int width,int height,int arcWidth,int arcHeight){g.fillRoundRect(x,y,width,height,arcWidth,arcHeight);mark();hi.fillRoundRect(x,y,width,height,arcWidth,arcHeight);}
	@Override public void drawOval(int x,int y,int width,int height){g.drawOval(x,y,width,height);mark();hi.drawOval(x,y,width,height);}
	@Override public void fillOval(int x,int y,int width,int height){g.fillOval(x,y,width,height);mark();hi.fillOval(x,y,width,height);}
	@Override public void drawArc(int x,int y,int width,int height,int startAngle,int arcAngle){g.drawArc(x,y,width,height,startAngle,arcAngle);mark();hi.drawArc(x,y,width,height,startAngle,arcAngle);}
	@Override public void fillArc(int x,int y,int width,int height,int startAngle,int arcAngle){g.fillArc(x,y,width,height,startAngle,arcAngle);mark();hi.fillArc(x,y,width,height,startAngle,arcAngle);}
	@Override public void drawPolyline(int[] xPoints,int[] yPoints,int nPoints){g.drawPolyline(xPoints,yPoints,nPoints);mark();hi.drawPolyline(xPoints,yPoints,nPoints);}
	@Override public void drawPolygon(int[] xPoints,int[] yPoints,int nPoints){g.drawPolygon(xPoints,yPoints,nPoints);mark();hi.drawPolygon(xPoints,yPoints,nPoints);}
	@Override public void fillPolygon(int[] xPoints,int[] yPoints,int nPoints){g.fillPolygon(xPoints,yPoints,nPoints);mark();hi.fillPolygon(xPoints,yPoints,nPoints);}
	@Override public void copyArea(int x,int y,int width,int height,int dx,int dy){g.copyArea(x,y,width,height,dx,dy);mark();hi.copyArea(x,y,width,height,dx,dy);}
	@Override public boolean hit(Rectangle rect,Shape s,boolean onStroke){return g.hit(rect,s,onStroke);}

	// ------------------------------------------------------------------ state: set on both, answered by the 1x graphics

	@Override public Graphics create(){return new SmoothGraphics((Graphics2D)g.create(),(Graphics2D)hi.create(),toHi,scale,algorithm,drew);}
	@Override public void dispose(){g.dispose();hi.dispose();}
	@Override public GraphicsConfiguration getDeviceConfiguration(){return g.getDeviceConfiguration();}
	@Override public FontRenderContext getFontRenderContext(){return g.getFontRenderContext();}
	@Override public FontMetrics getFontMetrics(Font f){return g.getFontMetrics(f);}
	@Override public Color getColor(){return g.getColor();}
	@Override public void setColor(Color c){g.setColor(c);hi.setColor(c);}
	@Override public void setPaintMode(){g.setPaintMode();hi.setPaintMode();xor=false;smoothHints();}
	@Override public void setXORMode(Color c1){g.setXORMode(c1);hi.setXORMode(c1);xor=true;smoothHints();}
	@Override public Font getFont(){return g.getFont();}
	@Override public void setFont(Font font){g.setFont(font);hi.setFont(font);}
	@Override public Paint getPaint(){return g.getPaint();}
	@Override public void setPaint(Paint paint){g.setPaint(paint);hi.setPaint(paint);}
	@Override public Composite getComposite(){return g.getComposite();}
	@Override public void setComposite(Composite comp){g.setComposite(comp);hi.setComposite(comp);}
	@Override public Stroke getStroke(){return g.getStroke();}
	@Override public void setStroke(Stroke s){g.setStroke(s);hi.setStroke(s);}
	@Override public Color getBackground(){return g.getBackground();}
	@Override public void setBackground(Color color){g.setBackground(color);hi.setBackground(color);}
	@Override public Object getRenderingHint(RenderingHints.Key hintKey){return g.getRenderingHint(hintKey);}
	@Override public void setRenderingHint(RenderingHints.Key hintKey,Object hintValue){g.setRenderingHint(hintKey,hintValue);hi.setRenderingHint(hintKey,hintValue);smoothHints();}
	@Override public void setRenderingHints(Map<?,?> hints){g.setRenderingHints(hints);hi.setRenderingHints(hints);smoothHints();}
	@Override public void addRenderingHints(Map<?,?> hints){g.addRenderingHints(hints);hi.addRenderingHints(hints);smoothHints();}
	@Override public RenderingHints getRenderingHints(){return g.getRenderingHints();}
	@Override public AffineTransform getTransform(){return g.getTransform();}
	@Override public void setTransform(AffineTransform tx){g.setTransform(tx);followTransform();}
	@Override public void transform(AffineTransform tx){g.transform(tx);followTransform();}
	@Override public void translate(int x,int y){g.translate(x,y);followTransform();}
	@Override public void translate(double tx,double ty){g.translate(tx,ty);followTransform();}
	@Override public void rotate(double theta){g.rotate(theta);followTransform();}
	@Override public void rotate(double theta,double x,double y){g.rotate(theta,x,y);followTransform();}
	@Override public void scale(double sx,double sy){g.scale(sx,sy);followTransform();}
	@Override public void shear(double shx,double shy){g.shear(shx,shy);followTransform();}
	@Override public Shape getClip(){return g.getClip();}
	@Override public Rectangle getClipBounds(){return g.getClipBounds();}
	@Override public void clipRect(int x,int y,int width,int height){g.clipRect(x,y,width,height);hi.clipRect(x,y,width,height);}
	@Override public void setClip(int x,int y,int width,int height){g.setClip(x,y,width,height);hi.setClip(x,y,width,height);}
	@Override public void setClip(Shape clip){g.setClip(clip);hi.setClip(clip);}
	@Override public void clip(Shape s){g.clip(s);hi.clip(s);}
}

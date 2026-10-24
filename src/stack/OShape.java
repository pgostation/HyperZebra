import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/** 編集可能なベクター部品。座標はカードの論理ピクセル。 */
public class OShape extends OObject {
	String kind="line"; // rectangle, line, ellipse, regularPolygon, polygon, freehand, bezier, text
	String pathData="";
	String text="Text";
	String fontFamily="SansSerif";
	int fontSize=64;
	boolean bold=true;
	int sides=5;
	Color fillColor=Color.WHITE, strokeColor=Color.BLACK;
	Color gradientStart, gradientEnd;
	boolean radialGradient;
	float gradientX1=0,gradientY1=0,gradientX2=1,gradientY2=0;
	float strokeWidth=2;
	/** Opacity of the whole shape, 0–1 (its effects and filters included). */
	float opacity=1;
	/** Rotation in degrees, clockwise about the centre of the shape's rectangle (left/top/width/height are the unrotated frame). */
	float rotation=0;
	/**
	 * Place among the owner's buttons and fields: the number of parts beneath this shape (OCardBase.stacking). A new shape, or one
	 * from a stack saved before shapes joined the part order, is above every part.
	 */
	int layer=Integer.MAX_VALUE;
	byte[] texturePng;
	boolean stretchTexture;
	final ArrayList<Outline> outlines=new ArrayList<>();
	final ArrayList<Shadow> shadows=new ArrayList<>();
	SvgFilter svgFilter;
	transient ShapeView view;
	private transient BufferedImage textureCache;
	private transient String parsedPathData;
	private transient Shape parsedPath;

	static final class Outline {
		float width; Color color;
		Outline(float width,Color color){this.width=width;this.color=color;}
		@Override public boolean equals(Object other){return other instanceof Outline o&&Float.compare(width,o.width)==0&&java.util.Objects.equals(color,o.color);}
		@Override public int hashCode(){return java.util.Objects.hash(width,color);}
	}
	/** A shadow; spread widens it by that many pixels on every side, as an outline of the same width does (0 = the plain shape). */
	static final class Shadow {
		float dx,dy,blur,spread; Color color;
		Shadow(float dx,float dy,float blur,Color color){this(dx,dy,blur,0,color);}
		Shadow(float dx,float dy,float blur,float spread,Color color){this.dx=dx;this.dy=dy;this.blur=blur;this.spread=spread;this.color=color;}
		@Override public boolean equals(Object other){return other instanceof Shadow s&&Float.compare(dx,s.dx)==0&&Float.compare(dy,s.dy)==0&&Float.compare(blur,s.blur)==0&&Float.compare(spread,s.spread)==0&&java.util.Objects.equals(color,s.color);}
		@Override public int hashCode(){return java.util.Objects.hash(dx,dy,blur,spread,color);}
	}
	OShape(OCardBase owner,int id){
		this.parent=owner;this.id=id;this.objectType="shape";
		left=40;top=40;width=160;height=100;
		scriptList=new ArrayList<>();
	}
	static OShape create(OCardBase owner,int id,String kind){
		OShape shape=new OShape(owner,id);shape.kind=kind;
		if(kind.equals("line")){shape.height=60;shape.fillColor=null;}
		if(kind.equals("text")){shape.text="TEXT";shape.width=240;shape.height=75;shape.strokeColor=null;shape.outlines.add(new Outline(6,Color.BLACK));shape.shadows.add(new Shadow(5,5,2,6,new Color(0,0,0,160)));}
		if(kind.equals("polygon"))shape.pathData="M 0,80 L 60,0 L 160,60 L 100,100 Z";
		if(kind.equals("freehand")){shape.pathData="M 0,60 L 30,30 L 60,70 L 100,20 L 160,50";shape.fillColor=null;}
		if(kind.equals("bezier")){shape.pathData="M 0,80 C 40,0 90,120 160,20";shape.fillColor=null;}
		return shape;
	}
	/** A detached copy (no owner, no view): Shape Info draws its draft with one. */
	OShape duplicate(){
		OShape d=new OShape(null,id);
		d.name=name;d.kind=kind;d.pathData=pathData;d.text=text;d.fontFamily=fontFamily;d.fontSize=fontSize;d.bold=bold;d.sides=sides;
		d.fillColor=fillColor;d.strokeColor=strokeColor;d.gradientStart=gradientStart;d.gradientEnd=gradientEnd;d.radialGradient=radialGradient;
		d.gradientX1=gradientX1;d.gradientY1=gradientY1;d.gradientX2=gradientX2;d.gradientY2=gradientY2;d.strokeWidth=strokeWidth;d.opacity=opacity;d.rotation=rotation;
		d.texturePng=texturePng;d.stretchTexture=stretchTexture;d.svgFilter=svgFilter;
		for(Outline o:outlines)d.outlines.add(new Outline(o.width,o.color));
		for(Shadow s:shadows)d.shadows.add(new Shadow(s.dx,s.dy,s.blur,s.spread,s.color));
		d.left=left;d.top=top;d.width=width;d.height=height;d.setVisible(getVisible());
		return d;
	}
	int getNumber(){return ((OCardBase)parent).shapeList.indexOf(this)+1;}
	Shape geometry(){
		if(kind.equals("rectangle"))return new Rectangle2D.Double(0,0,width,height);
		if(kind.equals("line")){if(!pathData.isEmpty())return parsedPath();Path2D.Double p=new Path2D.Double();p.moveTo(0,0);p.lineTo(width,height);return p;}
		if(kind.equals("ellipse")) return new Ellipse2D.Double(0,0,width,height);
		if(kind.equals("regularPolygon")){
			Path2D.Double p=new Path2D.Double();int n=Math.max(3,sides);
			for(int i=0;i<n;i++){double a=-Math.PI/2+i*2*Math.PI/n,x=width/2.0+width/2.0*Math.cos(a),y=height/2.0+height/2.0*Math.sin(a);if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}p.closePath();return p;
		}
		if(kind.equals("text")){
			Font font=OObject.stackFont(fontFamily,bold?Font.BOLD:Font.PLAIN,Math.max(1,fontSize));
			FontRenderContext frc=new FontRenderContext(null,true,true);
			Shape glyph=font.createGlyphVector(frc,text).getOutline();Rectangle2D b=glyph.getBounds2D();
			if(b.isEmpty()) return new Path2D.Double();
			AffineTransform t=new AffineTransform();t.translate(-b.getX(),-b.getY());glyph=t.createTransformedShape(glyph);
			if(width>0&&height>0){t=new AffineTransform();t.scale(width/b.getWidth(),height/b.getHeight());glyph=t.createTransformedShape(glyph);}
			return glyph;
		}
		return parsedPath();
	}
	private Shape parsedPath(){
		//移動・回転・ヒットテストでは経路の文字列は変わらない。
		//文字列の差分を見れば、スクリプトから直接pathDataを変えた場合も更新できる。
		if(parsedPath==null || !pathData.equals(parsedPathData)){
			parsedPath=ShapePath.parse(pathData);
			parsedPathData=pathData;
		}
		return parsedPath;
	}
	Rectangle2D naturalTextBounds(){
		Font font=OObject.stackFont(fontFamily,bold?Font.BOLD:Font.PLAIN,Math.max(1,fontSize));
		return font.createGlyphVector(new FontRenderContext(null,true,true),text).getOutline().getBounds2D();
	}
	void convertTextToBezier(){
		if(!kind.equals("text")) return;
		pathData=ShapePath.serialize(ShapePath.cubic(geometry()));kind="bezier";changed();repaintView();
	}
	void setPath(Shape path){pathData=ShapePath.serialize(path);kind="bezier";changed();repaintView();}
	void setTexture(byte[] png){texturePng=png;textureCache=null;changed();repaintView();}
	void setTopLeft(int x,int y){int dx=x-left,dy=y-top;if(dx==0&&dy==0)return;left=x;top=y;changed();moveView();if(parent instanceof OCardBase)((OCardBase)parent).movePanelPeers(this,dx,dy);}
	/** A fade needs no new picture of the shape: only its drawing is repeated. */
	void setOpacityValue(float value){
		float v=Math.max(0,Math.min(1,value));if(v==opacity)return;
		boolean visibleBefore=opacity<1;opacity=v;changed();
		if(view==null)return;
		if(visibleBefore!=(opacity<1)||!view.fadeOnly())view.cache=null;
		view.repaint();
	}
	void setSize(int w,int h){
		w=Math.max(1,w);h=Math.max(1,h);
		if(!pathData.isEmpty()&&width>0&&height>0&&(w!=width||h!=height))
			pathData=ShapePath.serialize(AffineTransform.getScaleInstance((double)w/width,(double)h/height).createTransformedShape(ShapePath.parse(pathData)));
		width=w;height=h;changed();updateView();
	}
	@Override public void setVisible(boolean visible){super.setVisible(visible);if(view!=null)view.setVisible(isShown());}
	/** Its panel was shown or hidden, or it joined or left one. */
	@Override void applyShown(){if(view!=null)PCARD.runOnEDTWhenShown(()->{if(view!=null){view.setVisible(isShown());view.repaint();}});}
	private void changed(){if(parent instanceof OCardBase)((OCardBase)parent).changed=true;}
	/** An angle in degrees as -180 (exclusive) to 180. */
	static float normalizeAngle(double degrees){double a=degrees%360;if(a<=-180)a+=360;if(a>180)a-=360;return (float)a;}
	/** Shape coordinates to the rotated drawing (identity when not rotated). */
	AffineTransform rotationTransform(){return AffineTransform.getRotateInstance(Math.toRadians(rotation),width/2.0,height/2.0);}
	/** The rotated rectangle in card coordinates: the selection frame. */
	Shape frame(){AffineTransform t=AffineTransform.getTranslateInstance(left,top);t.concatenate(rotationTransform());return t.createTransformedShape(new Rectangle2D.Double(0,0,width,height));}
	int padding(){float pad=Math.max(8,strokeWidth);
		// A rotated shape reaches beyond its rectangle by up to half the diagonal less half the shorter side.
		if(rotation%360!=0)pad+=(float)((Math.hypot(width,height)-Math.min(width,height))/2);for(Outline o:outlines)pad=Math.max(pad,o.width);for(Shadow s:shadows)pad=Math.max(pad,Math.max(Math.abs(s.dx),Math.abs(s.dy))+Math.max(0,s.spread)+s.blur*3+8);if(svgFilter!=null)pad+=svgFilter.padding();return Math.max(12,(int)Math.ceil(pad));}
	void build(){
		if(PCARD.pc==null||PCARD.pc.mainPane==null)return;
		if(view!=null&&view.getParent()!=null)view.getParent().remove(view);
		// OCardBase.buidParts adds the parts front to back, so each one goes to the end (behind those already there).
		view=new ShapeView(this);updateView();PCARD.pc.mainPane.add(view);view.setVisible(isShown());
	}
	/** After a change of the drawing: the view's bounds and its cached picture. */
	void updateView(){if(view==null)return;view.cache=null;moveView();view.repaint();}
	private void moveView(){if(view==null)return;int pad=padding();view.setBounds(PCARD.toPhysical(left-pad,top-pad,width+2*pad,height+2*pad));}
	void repaintView(){updateView();}
	private Paint fillPaint(){
		if(texturePng!=null){
			try{if(textureCache==null)textureCache=ImageIO.read(new ByteArrayInputStream(texturePng));
				if(textureCache!=null)return new TexturePaint(textureCache,new Rectangle2D.Double(0,0,textureCache.getWidth(),textureCache.getHeight()));}
			catch(Exception ignored){}
		}
		if(gradientStart!=null&&gradientEnd!=null){
			if(radialGradient){float cx=Math.max(0,Math.min(width,gradientX1*width)),cy=Math.max(0,Math.min(height,gradientY1*height));float radius=Math.max(1,gradientX2*Math.max(width,height));return new java.awt.RadialGradientPaint(cx,cy,radius,new float[]{0,1},new Color[]{gradientStart,gradientEnd});}
			float x1=gradientX1*width,y1=gradientY1*height,x2=gradientX2*width,y2=gradientY2*height;
			if(x1==x2&&y1==y2)x2=x1+1;
			return new java.awt.LinearGradientPaint(x1,y1,x2,y2,new float[]{0,1},new Color[]{gradientStart,gradientEnd});
		}
		return fillColor;
	}
	/** Filters, blurred shadows and translucency are slow to draw: the view keeps such a shape's picture until it changes. */
	boolean costly(){
		if(opacity<1||svgFilter!=null&&!svgFilter.primitives.isEmpty())return true;
		for(Shadow s:shadows)if(s.blur>0)return true;
		return false;
	}
	void render(Graphics2D g){
		if(opacity>=1){renderOpaque(g);return;}
		if(opacity<=0)return;
		// Drawn apart and then faded as a whole, so the fill does not show the stroke and outlines through it.
		int pad=padding();double scale=Math.max(1,Math.min(4,Math.abs(g.getTransform().getScaleX())));
		int w=(int)Math.ceil((width+2*pad)*scale),h=(int)Math.ceil((height+2*pad)*scale);
		if(w<=0||h<=0||(long)w*h>16_000_000){renderOpaque(g);return;}
		BufferedImage faded=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D lg=faded.createGraphics();
		lg.scale(scale,scale);lg.translate(pad,pad);renderOpaque(lg);lg.dispose();
		Graphics2D fg=(Graphics2D)g.create();fg.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER,opacity));
		fg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		fg.translate(-pad,-pad);fg.scale(1/scale,1/scale);fg.drawImage(faded,0,0,null);fg.dispose();
	}
	void renderOpaque(Graphics2D g){
		if(svgFilter!=null&&!svgFilter.primitives.isEmpty()){
			// Rotated before filtering, so filter shadows and offsets keep their direction on the card.
			Rectangle2D bounds=rotationTransform().createTransformedShape(geometry().getBounds2D()).getBounds2D();int pad=padding();
			int x=Math.min(0,(int)Math.floor(bounds.getX()))-pad,y=Math.min(0,(int)Math.floor(bounds.getY()))-pad;
			int w=Math.max(width,(int)Math.ceil(bounds.getMaxX()))-x+pad,h=Math.max(height,(int)Math.ceil(bounds.getMaxY()))-y+pad;
			if(w>0&&h>0&&(long)w*h<=12_000_000){
				BufferedImage source=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D local=source.createGraphics();local.translate(-x,-y);local.transform(rotationTransform());renderPlain(local);local.dispose();
				g.drawImage(svgFilter.apply(source),x,y,null);return;
			}
		}
		Graphics2D rotated=(Graphics2D)g.create();rotated.transform(rotationTransform());renderPlain(rotated);rotated.dispose();
	}
	private void renderPlain(Graphics2D g){
		Shape shape=geometry();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		// Shadows fall the same way on the card whatever the rotation: their offset is turned back into shape coordinates.
		double r=Math.toRadians(rotation),cos=Math.cos(r),sin=Math.sin(r);
		for(Shadow shadow:shadows)renderShadow(g,shape,shadow,(float)(shadow.dx*cos+shadow.dy*sin),(float)(-shadow.dx*sin+shadow.dy*cos));
		for(int i=outlines.size()-1;i>=0;i--){Outline o=outlines.get(i);if(o.color==null||o.width<=0)continue;g.setColor(o.color);g.setStroke(new BasicStroke(Math.max(0.1f,o.width)*2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(shape);}
		Paint paint=fillPaint();
		if(stretchTexture&&textureCache!=null){Shape oldClip=g.getClip();g.clip(shape);g.drawImage(textureCache,0,0,width,height,null);g.setClip(oldClip);}
		else if(paint!=null){g.setPaint(paint);g.fill(shape);}
		if(strokeColor!=null&&strokeWidth>0){g.setColor(strokeColor);g.setStroke(new BasicStroke(strokeWidth,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(shape);}
	}
	private static void renderShadow(Graphics2D g,Shape shape,Shadow shadow,float dx,float dy){
		if(shadow.color==null)return;
		if(shadow.spread>0){
			// Filled and stroked as one area, so a translucent shadow does not darken where the two overlap.
			java.awt.geom.Area area=new java.awt.geom.Area(shape);area.add(new java.awt.geom.Area(new BasicStroke(shadow.spread*2,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND).createStrokedShape(shape)));shape=area;
		}
		if(shadow.blur<=0){Graphics2D sg=(Graphics2D)g.create();sg.translate(dx,dy);sg.setColor(shadow.color);sg.fill(shape);sg.dispose();return;}
		Rectangle2D b=shape.getBounds2D();int radius=Math.min(64,Math.max(1,(int)Math.ceil(shadow.blur*2)));
		int x=(int)Math.floor(b.getX())-radius*2,y=(int)Math.floor(b.getY())-radius*2;
		int w=Math.max(1,(int)Math.ceil(b.getMaxX())-x+radius*2),h=Math.max(1,(int)Math.ceil(b.getMaxY())-y+radius*2);
		if((long)w*h>12_000_000){Graphics2D sg=(Graphics2D)g.create();sg.translate(dx,dy);sg.setColor(shadow.color);sg.fill(shape);sg.dispose();return;}
		BufferedImage source=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D sg=source.createGraphics();sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);sg.translate(-x,-y);sg.setColor(shadow.color);sg.fill(shape);sg.dispose();
		float[] weights=new float[radius*2+1];float sum=0,sigma=Math.max(0.5f,shadow.blur);
		for(int i=-radius;i<=radius;i++){float weight=(float)Math.exp(-(i*i)/(2*sigma*sigma));weights[i+radius]=weight;sum+=weight;}
		for(int i=0;i<weights.length;i++)weights[i]/=sum;
		java.awt.image.ConvolveOp horizontal=new java.awt.image.ConvolveOp(new java.awt.image.Kernel(weights.length,1,weights),java.awt.image.ConvolveOp.EDGE_NO_OP,null);
		java.awt.image.ConvolveOp vertical=new java.awt.image.ConvolveOp(new java.awt.image.Kernel(1,weights.length,weights),java.awt.image.ConvolveOp.EDGE_NO_OP,null);
		BufferedImage tmp=horizontal.filter(source,null),blurred=vertical.filter(tmp,null);
		g.drawImage(blurred,Math.round(x+dx),Math.round(y+dy),null);
	}
	/**
	 * Whether a point (shape coordinates, 0,0 at the top left) is on what the shape paints: its fill (a transparent one too; "none"
	 * lets clicks through), its stroke and outlines, at least 6 px wide so thin lines can be clicked. A text shape takes its whole
	 * rectangle, so the gaps between the letters count.
	 */
	boolean hits(double x,double y){
		if(rotation%360!=0){
			java.awt.geom.Point2D p=new java.awt.geom.Point2D.Double(x,y);
			try{rotationTransform().inverseTransform(p,p);}catch(java.awt.geom.NoninvertibleTransformException e){return false;}
			x=p.getX();y=p.getY();
		}
		if(kind.equals("text"))return x>=0&&y>=0&&x<width&&y<height;
		Shape shape=geometry();
		boolean filled=fillColor!=null||texturePng!=null||gradientStart!=null&&gradientEnd!=null;
		if(filled&&shape.contains(x,y))return true;
		float line=strokeColor!=null&&strokeWidth>0?strokeWidth:0;
		for(Outline o:outlines)if(o.color!=null&&o.width>0)line=Math.max(line,o.width*2);
		return line>0&&new BasicStroke(Math.max(6,line),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND).createStrokedShape(shape).contains(x,y);
	}
	/** {@link #hits} for a visible shape, in card coordinates. */
	boolean hitsCard(int x,int y){return isShown()&&hits(x-left,y-top);}
	static final class ShapeView extends JComponent {
		final OShape shape;
		ShapeView(OShape shape){this.shape=shape;setOpaque(false);addMouseListener(GShape.gui);addMouseMotionListener(GShape.gui);}
		// Authoring input belongs to ShapeTool (its overlay lies above every shape) and painting to the paint tools. With the browse
		// tool a shape is a part that takes the clicks on what it paints; elsewhere they reach the card and parts beneath it.
		@Override public boolean contains(int x,int y){
			if(!GShape.receivesMouse())return false;
			double scale=PCARD.scale();int pad=shape.padding();
			return shape.hits(x/scale-pad,y/scale-pad);
		}
		/** The drawn shape for costly() shapes; OShape.updateView drops it. */
		BufferedImage cache;
		/** True when the shape is costly only because it is translucent: its picture is kept opaque and faded as it is drawn, so a fade needs no redraw. */
		boolean fadeOnly(){
			if(shape.opacity>=1||shape.svgFilter!=null&&!shape.svgFilter.primitives.isEmpty())return false;
			for(Shadow s:shape.shadows)if(s.blur>0)return false;
			return true;
		}
		@Override protected void paintComponent(Graphics g){
			if(!shape.costly()){cache=null;paintShape((Graphics2D)g,false);return;}
			boolean fade=fadeOnly();
			if(cache==null||cache.getWidth()!=getWidth()||cache.getHeight()!=getHeight()){
				if(getWidth()<=0||getHeight()<=0)return;
				cache=new BufferedImage(getWidth(),getHeight(),BufferedImage.TYPE_INT_ARGB);
				Graphics2D cg=cache.createGraphics();paintShape(cg,fade);cg.dispose();
			}
			if(!fade){g.drawImage(cache,0,0,null);return;}
			if(shape.opacity<=0)return;
			Graphics2D fg=(Graphics2D)g.create();fg.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER,shape.opacity));
			fg.drawImage(cache,0,0,null);fg.dispose();
		}
		private void paintShape(Graphics2D g,boolean unfaded){Graphics2D g2=(Graphics2D)g.create();double scale=PCARD.scale();int pad=shape.padding();g2.scale(scale,scale);g2.translate(pad,pad);if(unfaded)shape.renderOpaque(g2);else shape.render(g2);g2.dispose();}
	}
	private static String hex(Color c){return c==null?"none":String.format("#%08X",c.getRGB());}
	static Color color(String s){if(s==null||s.isBlank()||s.equalsIgnoreCase("none"))return null;try{
		switch(s.toLowerCase()){
			case "black":return Color.BLACK;case "white":return Color.WHITE;case "red":return Color.RED;
			case "green":return Color.GREEN;case "blue":return Color.BLUE;case "yellow":return Color.YELLOW;
			case "gray":case "grey":return Color.GRAY;case "transparent":return new Color(0,0,0,0);
		}
		if(s.startsWith("#")){String h=s.substring(1);if(h.length()==3)return new Color(Integer.parseInt(""+h.charAt(0)+h.charAt(0),16),Integer.parseInt(""+h.charAt(1)+h.charAt(1),16),Integer.parseInt(""+h.charAt(2)+h.charAt(2),16));
			if(h.length()==6)return new Color(Integer.parseInt(h,16));if(h.length()==8)return new Color((int)Long.parseLong(h,16),true);}
		return Color.decode(s);
	}catch(Exception e){return Color.BLACK;}}
	/*
	 * HyperTalk: what Shape Info edits, as properties (TUtil.getProperty / SetProperty ask here first).
	 * Colours are "#AARRGGBB" (a name, #RGB or #RRGGBB when set), "none" for no colour. Outlines are lines of
	 * "width,color" and shadows lines of "x,y,width,blur,color", in the order Shape Info lists them; the outline… and
	 * shadow… properties are the first of them (setting one adds it when there is none).
	 */
	static final String[] SCRIPT_PROPERTIES={"kind","textfont","textsize","textstyle","sides","polysides",
		"gradientstart","gradientend","gradienttype","gradientangle","gradientpoints","texture","stretchtexture",
		"outlines","outlinewidth","outlinecolor","shadows","shadowoffset","shadowwidth","shadowblur","shadowcolor","filter"};
	/** The value of a shape property, or null when it is not one of these. */
	String scriptProperty(String p){
		Outline o=outlines.isEmpty()?null:outlines.get(0);Shadow s=shadows.isEmpty()?null:shadows.get(0);boolean gradient=gradientStart!=null&&gradientEnd!=null;
		return switch(p){
			case "kind"->kind;
			case "textfont"->fontFamily;
			case "textsize"->Integer.toString(fontSize);
			case "textstyle"->bold?"bold":"plain";
			case "sides","polysides"->Integer.toString(sides);
			case "gradientstart"->gradient?hex(gradientStart):"none";
			case "gradientend"->gradient?hex(gradientEnd):"none";
			case "gradienttype"->!gradient?"none":radialGradient?"radial":"linear";
			case "gradientangle"->num(Math.toDegrees(Math.atan2(gradientY2-gradientY1,gradientX2-gradientX1)));
			case "gradientpoints"->num(gradientX1)+","+num(gradientY1)+","+num(gradientX2)+","+num(gradientY2);
			case "texture"->texturePng==null?"":Base64.getEncoder().encodeToString(texturePng);
			case "stretchtexture"->Boolean.toString(stretchTexture);
			case "outlines"->{StringBuilder b=new StringBuilder();for(Outline e:outlines){if(b.length()>0)b.append('\n');b.append(num(e.width)).append(',').append(hex(e.color));}yield b.toString();}
			case "shadows"->{StringBuilder b=new StringBuilder();for(Shadow e:shadows){if(b.length()>0)b.append('\n');b.append(num(e.dx)).append(',').append(num(e.dy)).append(',').append(num(e.spread)).append(',').append(num(e.blur)).append(',').append(hex(e.color));}yield b.toString();}
			case "outlinewidth"->o==null?"0":num(o.width);
			case "outlinecolor"->o==null?"none":hex(o.color);
			case "shadowoffset"->s==null?"0,0":num(s.dx)+","+num(s.dy);
			case "shadowwidth"->s==null?"0":num(s.spread);
			case "shadowblur"->s==null?"0":num(s.blur);
			case "shadowcolor"->s==null?"none":hex(s.color);
			case "filter"->svgFilter==null||svgFilter.primitives.isEmpty()?"":svgFilter.toSvg();
			default->null;
		};
	}
	/** Sets a shape property; false when it is not one of these. */
	boolean setScriptProperty(String p,String value) throws xTalkException {
		String v=value.trim();
		switch(p){
			case "kind"->setKind(v);
			case "textfont"->{if(v.isEmpty())throw new xTalkException("textFont requires a font name");fontFamily=v;fitText();}
			case "textsize"->{int size=(int)number(p,v);if(size<1)throw new xTalkException("textSize must be at least 1");fontSize=size;fitText();}
			case "textstyle"->{String l=v.toLowerCase();if(!l.matches("(plain|bold)?(\\s*,\\s*(plain|bold))*"))throw new xTalkException("A shape's textStyle must be bold or plain");bold=l.contains("bold");fitText();}
			case "sides","polysides"->{int n=(int)number(p,v);if(n<3||n>100)throw new xTalkException("sides must be between 3 and 100");sides=n;}
			case "gradientstart","gradientend"->{
				Color c=color(v);
				if(c==null){gradientStart=null;gradientEnd=null;break;}
				if(gradientStart==null||gradientEnd==null){gradientStart=fillColor!=null?fillColor:Color.WHITE;gradientEnd=Color.BLACK;}
				if(p.equals("gradientstart"))gradientStart=c;else gradientEnd=c;
			}
			case "gradienttype"->{
				String l=v.toLowerCase();
				if(l.equals("none")||l.isEmpty()){gradientStart=null;gradientEnd=null;break;}
				if(!l.equals("linear")&&!l.equals("radial"))throw new xTalkException("gradientType must be linear, radial or none");
				boolean radial=l.equals("radial"),had=gradientStart!=null&&gradientEnd!=null;
				if(!had){gradientStart=fillColor!=null?fillColor:Color.WHITE;gradientEnd=Color.BLACK;}
				// As Shape Info does: a radial gradient starts at the centre, a linear one runs left to right.
				if(!had||radial!=radialGradient){if(radial){gradientX1=0.5f;gradientY1=0.5f;gradientX2=0.5f;gradientY2=0;}else{gradientX1=0;gradientY1=0.5f;gradientX2=1;gradientY2=0.5f;}}
				radialGradient=radial;
			}
			case "gradientangle"->{double r=Math.toRadians(number(p,v)),dx=Math.cos(r)/2,dy=Math.sin(r)/2;gradientX1=(float)(0.5-dx);gradientY1=(float)(0.5-dy);gradientX2=(float)(0.5+dx);gradientY2=(float)(0.5+dy);}
			case "gradientpoints"->{float[] n=numbers(p,v,4,4);gradientX1=n[0];gradientY1=n[1];gradientX2=n[2];gradientY2=n[3];}
			case "texture"->{
				if(v.isEmpty()||v.equalsIgnoreCase("none")){setTexture(null);break;}
				byte[] png;try{png=Base64.getMimeDecoder().decode(v);}catch(IllegalArgumentException e){throw new xTalkException("texture requires a Base64 image such as PNG");}
				try{if(ImageIO.read(new ByteArrayInputStream(png))==null)throw new java.io.IOException();}catch(java.io.IOException e){throw new xTalkException("Cannot read the texture image");}
				setTexture(png);
			}
			case "stretchtexture"->{if(!v.equalsIgnoreCase("true")&&!v.equalsIgnoreCase("false"))throw new xTalkException("stretchTexture requires true or false");stretchTexture=Boolean.parseBoolean(v);}
			case "outlines"->{ArrayList<Outline> list=new ArrayList<>();for(String line:lines(v)){String[] i=items(line);if(i.length<1||i.length>2)throw new xTalkException("Each outlines line must be \"width,color\"");list.add(new Outline(Math.max(0,number(p,i[0])),i.length>1?color(i[1]):Color.BLACK));}outlines.clear();outlines.addAll(list);}
			case "shadows"->{ArrayList<Shadow> list=new ArrayList<>();for(String line:lines(v)){String[] i=items(line);if(i.length<2||i.length>5)throw new xTalkException("Each shadows line must be \"x,y,width,blur,color\"");
				list.add(new Shadow(number(p,i[0]),number(p,i[1]),i.length>3?Math.max(0,number(p,i[3])):0,i.length>2?Math.max(0,number(p,i[2])):0,i.length>4?color(i[4]):new Color(0,0,0,160)));}shadows.clear();shadows.addAll(list);}
			case "outlinewidth"->firstOutline().width=Math.max(0,number(p,v));
			case "outlinecolor"->firstOutline().color=color(v);
			case "shadowoffset"->{float[] n=numbers(p,v,2,2);Shadow s=firstShadow();s.dx=n[0];s.dy=n[1];}
			case "shadowwidth"->firstShadow().spread=Math.max(0,number(p,v));
			case "shadowblur"->firstShadow().blur=Math.max(0,number(p,v));
			case "shadowcolor"->firstShadow().color=color(v);
			case "filter"->svgFilter=v.isEmpty()||v.equalsIgnoreCase("none")?null:SvgFilter.fromSvgText(v);
			default->{return false;}
		}
		changed();updateView();return true;
	}
	private Outline firstOutline(){if(outlines.isEmpty())outlines.add(new Outline(0,Color.BLACK));return outlines.get(0);}
	private Shadow firstShadow(){if(shadows.isEmpty())shadows.add(new Shadow(0,0,0,0,new Color(0,0,0,160)));return shadows.get(0);}
	/** A text shape takes its new lettering's natural size, as when its text is set. */
	private void fitText(){if(!kind.equals("text"))return;Rectangle2D b=naturalTextBounds();if(!b.isEmpty())setSize(Math.max(1,(int)Math.ceil(b.getWidth())),Math.max(1,(int)Math.ceil(b.getHeight())));}
	private void setKind(String value) throws xTalkException {
		String k=null;for(String known:new String[]{"rectangle","line","ellipse","regularPolygon","polygon","freehand","bezier","text"})if(known.equalsIgnoreCase(value))k=known;
		if(k==null)throw new xTalkException("Shape type must be rectangle, line, ellipse, regularPolygon, polygon, freehand or bezier");
		if(k.equals(kind))return;
		if(k.equals("text"))throw new xTalkException("Cannot change to a text shape");
		if(k.equals("rectangle")||k.equals("ellipse")||k.equals("regularPolygon")){pathData="";kind=k;return;}
		// The path kinds keep what is drawn: a shape without a path gets its outline as one (curves for bezier).
		if(pathData.isEmpty()||kind.equals("text"))pathData=ShapePath.serialize(k.equals("bezier")?ShapePath.cubic(geometry()):geometry());
		kind=k;
	}
	private static String num(double v){return ShapeFilterPanel.num(v);}
	private static float number(String property,String text) throws xTalkException {
		try{float v=Float.parseFloat(text.trim());if(Float.isFinite(v))return v;}catch(NumberFormatException ignored){}
		throw new xTalkException("{0} requires a number", property);
	}
	private static float[] numbers(String property,String text,int min,int max) throws xTalkException {
		String[] parts=items(text);if(parts.length<min||parts.length>max)throw new xTalkException("{0} requires {1} numbers", property, max);
		float[] out=new float[parts.length];for(int i=0;i<parts.length;i++)out[i]=number(property,parts[i]);return out;
	}
	private static String[] items(String line){String[] parts=line.split(",",-1);for(int i=0;i<parts.length;i++)parts[i]=parts[i].trim();return parts;}
	private static ArrayList<String> lines(String text){ArrayList<String> out=new ArrayList<>();for(String line:text.split("\r\n|\r|\n"))if(!line.isBlank())out.add(line);return out;}
	void writeXML(XMLStreamWriter w) throws XMLStreamException {
		w.writeStartElement("shape");
		attr(w,"id",id);attr(w,"name",name);if(panelId!=0)attr(w,"panel",panelId);attr(w,"kind",kind);attr(w,"x",left);attr(w,"y",top);attr(w,"width",width);attr(w,"height",height);
		attr(w,"visible",getVisible());attr(w,"path",pathData);attr(w,"text",text);attr(w,"font",fontFamily);attr(w,"fontSize",fontSize);attr(w,"bold",bold);attr(w,"sides",sides);
		attr(w,"fill",hex(fillColor));attr(w,"stroke",hex(strokeColor));attr(w,"strokeWidth",strokeWidth);
		attr(w,"stretchTexture",stretchTexture);
		if(opacity<1)attr(w,"opacity",opacity);
		if(rotation!=0)attr(w,"rotation",rotation);
		if(parent instanceof OCardBase owner){owner.normalizeLayers();attr(w,"layer",layer);}
		if(gradientStart!=null&&gradientEnd!=null){attr(w,"gradientStart",hex(gradientStart));attr(w,"gradientEnd",hex(gradientEnd));attr(w,"gradientRadial",radialGradient);attr(w,"gradientX1",gradientX1);attr(w,"gradientY1",gradientY1);attr(w,"gradientX2",gradientX2);attr(w,"gradientY2",gradientY2);}
		if(texturePng!=null){w.writeStartElement("texture");w.writeCharacters(Base64.getEncoder().encodeToString(texturePng));w.writeEndElement();}
		for(Outline o:outlines){w.writeEmptyElement("outline");attr(w,"width",o.width);attr(w,"color",hex(o.color));}
		for(Shadow s:shadows){w.writeEmptyElement("shadow");attr(w,"dx",s.dx);attr(w,"dy",s.dy);attr(w,"blur",s.blur);if(s.spread!=0)attr(w,"spread",s.spread);attr(w,"color",hex(s.color));}
		if(svgFilter!=null)svgFilter.writeXML(w);
		if(scriptList!=null&&!String.join("",scriptList).isEmpty()){w.writeStartElement("script");w.writeCharacters(String.join("\n",scriptList));w.writeEndElement();}
		w.writeEndElement();
	}
	private static void attr(XMLStreamWriter w,String key,Object value) throws XMLStreamException {w.writeAttribute(key,String.valueOf(value==null?"":value));}
	static OShape readXML(XMLStreamReader r,OCardBase owner){
		OShape s=new OShape(owner,integer(r,"id",0));s.name=string(r,"name","");s.panelId=XMLRead.panelId(string(r,"panel","0"));s.kind=string(r,"kind","line");
		s.left=integer(r,"x",0);s.top=integer(r,"y",0);s.width=integer(r,"width",100);s.height=integer(r,"height",100);
		s.pathData=string(r,"path","");s.text=string(r,"text","");s.fontFamily=string(r,"font","SansSerif");s.fontSize=integer(r,"fontSize",64);s.bold=Boolean.parseBoolean(string(r,"bold","true"));s.sides=integer(r,"sides",5);
		s.fillColor=color(string(r,"fill","#FFFFFFFF"));s.strokeColor=color(string(r,"stroke","#FF000000"));s.strokeWidth=decimal(r,"strokeWidth",2);
		s.stretchTexture=Boolean.parseBoolean(string(r,"stretchTexture","false"));
		s.opacity=Math.max(0,Math.min(1,decimal(r,"opacity",1)));s.layer=integer(r,"layer",Integer.MAX_VALUE);s.rotation=decimal(r,"rotation",0);
		s.gradientStart=color(string(r,"gradientStart","none"));s.gradientEnd=color(string(r,"gradientEnd","none"));s.radialGradient=Boolean.parseBoolean(string(r,"gradientRadial","false"));
		s.gradientX1=decimal(r,"gradientX1",0);s.gradientY1=decimal(r,"gradientY1",0);s.gradientX2=decimal(r,"gradientX2",1);s.gradientY2=decimal(r,"gradientY2",0);
		boolean visible=Boolean.parseBoolean(string(r,"visible","true"));
		try{while(r.hasNext()){int event=r.next();if(event==XMLStreamConstants.START_ELEMENT){
			if(r.getLocalName().equals("texture"))s.texturePng=Base64.getDecoder().decode(r.getElementText());
			else if(r.getLocalName().equals("outline"))s.outlines.add(new Outline(decimal(r,"width",2),color(string(r,"color","#FF000000"))));
			else if(r.getLocalName().equals("shadow"))s.shadows.add(new Shadow(decimal(r,"dx",2),decimal(r,"dy",2),decimal(r,"blur",0),decimal(r,"spread",0),color(string(r,"color","#80000000"))));
			else if(r.getLocalName().equals("svgFilter"))s.svgFilter=SvgFilter.readXML(r);
			else if(r.getLocalName().equals("script"))s.readScript(r.getElementText());
		}else if(event==XMLStreamConstants.END_ELEMENT&&r.getLocalName().equals("shape"))break;}}
		catch(Exception ignored){}
		s.setVisible(visible);return s;
	}
	@SuppressWarnings("unchecked")
	private void readScript(String script){
		// Keep trailing empty lines, as buttons and fields do.
		scriptList.clear();for(String line:script.split("\n",-1))scriptList.add(line);
		stringList=new ArrayList[scriptList.size()];typeList=new ArrayList[scriptList.size()];
		handlerList=null;handlerLineList=null;wrapFlag=false;
	}
	private static String string(XMLStreamReader r,String key,String fallback){String v=r.getAttributeValue(null,key);return v==null?fallback:v;}
	private static int integer(XMLStreamReader r,String key,int fallback){try{return Integer.parseInt(string(r,key,""));}catch(Exception e){return fallback;}}
	private static float decimal(XMLStreamReader r,String key,float fallback){try{return Float.parseFloat(string(r,key,""));}catch(Exception e){return fallback;}}
}

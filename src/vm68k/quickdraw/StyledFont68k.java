import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A host font carrying the TextFace bits Java cannot express (underline, outline, shadow, condense, extend): the
 * stand-in {@link Fonts68k#font} returns for a family without an installed strike when the face asks for one of
 * them. It renders like its base font (bold and italic are the base's own faces) but measures with the Font
 * Manager's style extras and draws through {@link TextStyle68k}, so outlined Helvetica looks like outlined
 * Geneva did rather than plain.
 */
final class StyledFont68k extends Font {
	final Font base;
	/** The full TextFace requested. */
	final int face;
	private final Metrics metrics=new Metrics();

	StyledFont68k(Font base,int face){this(base,face,0);}
	/** @param fixedAdvance the classic advance of every one-byte character (Monaco's stand-in, {@link Fonts68k#fixedAdvance}), 0 for the base's own */
	StyledFont68k(Font base,int face,int fixedAdvance){super(base);this.base=base;this.face=face&0x7f;this.fixedAdvance=Math.max(0,fixedAdvance);smear=(this.face&TextStyle68k.BOLD)!=0&&!base.isBold();}
	/**
	 * A monospaced classic family's advance for the characters of its own one-byte set, or 0. Calendar draws its month in Monaco 9
	 * and puts the bold day at 12+col*24, the 6-pixel advance; Menlo 9 measured 5 and squeezed the columns under it. Two-byte
	 * characters (a Japanese system's fallback glyphs) keep their own width.
	 */
	final int fixedAdvance;
	private static final boolean[] ONE_BYTE=new boolean[0x10000];
	static{byte[] all=new byte[256];for(int i=0;i<256;i++)all[i]=(byte)i;String roman=new String(all,java.nio.charset.Charset.forName("x-MacRoman"));
		for(int i=0x20;i<roman.length();i++)ONE_BYTE[roman.charAt(i)]=true;ONE_BYTE[0x7f]=false;}
	/** Bold asked of a plain base (a host family without a bold face): every glyph is drawn again one pixel right, as QuickDraw does. */
	private final boolean smear;

	/** Pixels added to every non-empty advance. */
	int extra(){return TextStyle68k.extra(face);}
	private FontMetrics baseMetrics(){return Fonts68k.metrics(base);}
	int charWidth(char c){int w=fixedAdvance>0&&ONE_BYTE[c]?fixedAdvance:baseMetrics().charWidth(c);return w+(w>0?extra():0);}
	int width(String s){int w=0;if(s!=null)for(int i=0;i<s.length();i++)w+=charWidth(s.charAt(i));return w;}
	FontMetrics metrics(){return metrics;}

	/**
	 * Columns and rows of {@link #coverage} around the pen run: the style's own margins, and on the sides room for a glyph's ink
	 * past its advance - the last letter of a run lost its right side otherwise (Wingz draws its text fields word by word in
	 * Geneva 10, which is a host face here: every "to" read "tc"). The painters skip empty mask pixels, so the room draws nothing.
	 */
	private int[] margins(){FontMetrics m=baseMetrics();int[] mg=TextStyle68k.margins(face,m.getAscent(),m.getDescent()).clone();
		int overhang=Math.max(2,(m.getAscent()+m.getDescent())/4);mg[0]+=overhang;mg[2]+=overhang;return mg;}
	/** Columns of {@link #coverage} left of the pen position. */
	int maskLeft(){return margins()[0];}
	/** Rows of {@link #coverage} above the baseline. */
	int maskAscent(){return margins()[1]+baseMetrics().getAscent();}
	/**
	 * The run rendered with the base font character by character at the styled advances, then styled: a
	 * TYPE_BYTE_GRAY coverage image when anti-aliased, else TYPE_BYTE_BINARY (set = ink). The pen sits at column
	 * {@link #maskLeft()}, the baseline at row {@link #maskAscent()}.
	 */
	BufferedImage coverage(String text,boolean antialias){return coverage(text,antialias,false);}
	/** @param interior the hollow of outline/shadow text instead of its rim, same geometry */
	BufferedImage coverage(String text,boolean antialias,boolean interior){
		FontMetrics m=baseMetrics();int ascent=m.getAscent(),descent=m.getDescent();int[] mg=margins();int advance=width(text);
		int w=Math.max(1,mg[0]+advance+mg[2]),h=Math.max(1,mg[1]+ascent+descent+mg[3]);
		final int baseline=mg[1]+ascent,x0=mg[0];
		java.util.function.Consumer<Graphics2D> painter=g->{g.setFont(base);int x=x0;
			if(text!=null)for(int i=0;i<text.length();i++){String c=text.substring(i,i+1);Fonts68k.drawString(g,base,c,x,baseline);if(smear)Fonts68k.drawString(g,base,c,x+1,baseline);x+=charWidth(text.charAt(i));}};
		BufferedImage img;
		if(antialias){img=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
			Graphics2D g=img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
			g.setColor(Color.WHITE);painter.accept(g);g.dispose();}
		else img=Fonts68k.binaryMask(w,h,painter); // aliased rasterization drops the thin stems of small outline fonts
		TextStyle68k.apply(img,face,baseline,mg[0],advance,interior);
		return img;
	}
	/** The run at {@code scale} times the size for the smooth zoom: anti-aliased coverage, {@link #coverage(String,boolean,boolean)}'s geometry times the scale. */
	BufferedImage coverage(String text,boolean interior,int scale){
		if(scale<=1)return coverage(text,true,interior);
		FontMetrics m=baseMetrics();int ascent=m.getAscent(),descent=m.getDescent();int[] mg=margins();int advance=width(text);
		int w=Math.max(1,mg[0]+advance+mg[2]),h=Math.max(1,mg[1]+ascent+descent+mg[3]);
		int baseline=mg[1]+ascent;
		BufferedImage img=new BufferedImage(w*scale,h*scale,BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g=img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.scale(scale,scale);g.setColor(Color.WHITE);
		int x=mg[0];
		if(text!=null)for(int i=0;i<text.length();i++){String c=text.substring(i,i+1);
			for(int pass=0;pass<=(smear?scale:0);pass++)Fonts68k.drawPlaced(g,base,c,x+pass/(double)scale,baseline);
			x+=charWidth(text.charAt(i));}
		g.dispose();
		TextStyle68k.apply(img,face,baseline*scale,mg[0]*scale,advance*scale,interior,scale);
		return img;
	}

	/** Metrics of the base font with the style extras, for host code that measures through {@link FontMetrics}. */
	final class Metrics extends FontMetrics {
		Metrics(){super(StyledFont68k.this);}
		@Override public int getAscent(){return baseMetrics().getAscent();}
		@Override public int getDescent(){return baseMetrics().getDescent();}
		@Override public int getLeading(){return baseMetrics().getLeading();}
		@Override public int getHeight(){return baseMetrics().getHeight();}
		@Override public int getMaxAscent(){return baseMetrics().getMaxAscent();}
		@Override public int getMaxDescent(){return baseMetrics().getMaxDescent();}
		@Override public int getMaxAdvance(){int w=fixedAdvance>0?fixedAdvance:baseMetrics().getMaxAdvance();return w>0?w+Math.max(0,extra()):w;}
		@Override public int charWidth(char c){return StyledFont68k.this.charWidth(c);}
		@Override public int charWidth(int c){return c<0x10000?StyledFont68k.this.charWidth((char)c):0;}
		@Override public int stringWidth(String s){return width(s);}
		@Override public int charsWidth(char[] data,int off,int len){return width(new String(data,off,len));}
		@Override public int[] getWidths(){int[] w=new int[256];for(int i=0;i<256;i++)w[i]=StyledFont68k.this.charWidth((char)i);return w;}
	}

	@Override public boolean equals(Object o){return o instanceof StyledFont68k&&((StyledFont68k)o).base.equals(base)&&((StyledFont68k)o).face==face&&((StyledFont68k)o).fixedAdvance==fixedAdvance;}
	@Override public int hashCode(){return (base.hashCode()*31+face)*31+fixedAdvance;}
	@Override public String toString(){return "StyledFont68k["+base.getFontName()+" "+base.getSize()+" face "+face+(fixedAdvance>0?" advance "+fixedAdvance:"")+"]";}
}

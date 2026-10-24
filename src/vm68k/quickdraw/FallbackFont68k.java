import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;

/**
 * A host font with a stand-in for the characters it has no glyph for. On a Japanese system the Font Manager drew
 * the two-byte characters of a Roman family with the system font's glyphs rather than an empty box, and macOS
 * does the same for a Java2D physical font; Windows and Linux do not, so Helvetica asked for 漢 draws the
 * missing-glyph box (豆腐). This pairs the base font with a Japanese family and measures and draws each
 * character with whichever of the two has it - the base alone, as before, whenever the base covers the run.
 */
final class FallbackFont68k extends Font {
	final Font base,fallback;
	private final Metrics metrics=new Metrics();

	FallbackFont68k(Font base,Font fallback){super(base);this.base=base;this.fallback=fallback;}

	private FontMetrics baseMetrics(){return Fonts68k.metrics(base);}
	private FontMetrics fallbackMetrics(){return Fonts68k.metrics(fallback);}
	/** Whether the base font covers the whole run (then nothing about it changes). */
	private boolean covered(String s){
		if(s==null)return true;
		for(int i=0;i<s.length();i++)if(!base.canDisplay(s.charAt(i)))return false;
		return true;
	}
	int charWidth(char c){return base.canDisplay(c)?baseMetrics().charWidth(c):fallbackMetrics().charWidth(c);}
	int width(String s){
		if(s==null||s.isEmpty())return 0;
		if(covered(s))return baseMetrics().stringWidth(s);
		int w=0;for(int i=0;i<s.length();i++)w+=charWidth(s.charAt(i));return w;
	}
	FontMetrics metrics(){return metrics;}

	/** Draws the run, the characters the base font has no glyph for with the fallback font's. */
	void draw(Graphics2D g,String text,int x,int baseline){
		if(text==null||text.isEmpty())return;
		if(covered(text)){g.setFont(base);g.drawString(text,x,baseline);return;}
		for(int i=0;i<text.length();i++){char c=text.charAt(i);
			g.setFont(base.canDisplay(c)?base:fallback);g.drawString(String.valueOf(c),x,baseline);x+=charWidth(c);}
	}

	/** The base font's line metrics (so a run's box is what it always was) with the per-character widths. */
	final class Metrics extends FontMetrics {
		Metrics(){super(FallbackFont68k.this);}
		@Override public int getAscent(){return baseMetrics().getAscent();}
		@Override public int getDescent(){return baseMetrics().getDescent();}
		@Override public int getLeading(){return baseMetrics().getLeading();}
		@Override public int getHeight(){return baseMetrics().getHeight();}
		@Override public int getMaxAscent(){return baseMetrics().getMaxAscent();}
		@Override public int getMaxDescent(){return baseMetrics().getMaxDescent();}
		@Override public int getMaxAdvance(){return baseMetrics().getMaxAdvance();}
		@Override public int charWidth(char c){return FallbackFont68k.this.charWidth(c);}
		@Override public int charWidth(int c){return c<0x10000?FallbackFont68k.this.charWidth((char)c):0;}
		@Override public int stringWidth(String s){return width(s);}
		@Override public int charsWidth(char[] data,int off,int len){return width(new String(data,off,len));}
		@Override public int[] getWidths(){int[] w=new int[256];for(int i=0;i<256;i++)w[i]=FallbackFont68k.this.charWidth((char)i);return w;}
	}

	@Override public boolean equals(Object o){return o instanceof FallbackFont68k&&((FallbackFont68k)o).base.equals(base)&&((FallbackFont68k)o).fallback.equals(fallback);}
	@Override public int hashCode(){return base.hashCode()*31+fallback.hashCode();}
	@Override public String toString(){return "FallbackFont68k["+base.getFontName()+" "+base.getSize()+" + "+fallback.getFontName()+"]";}
}

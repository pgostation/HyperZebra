import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A classic Macintosh font at one size and face, as the Font Manager would build it: the glyphs of a bitmapped
 * strike ('NFNT'/'FONT' resource, IM Text 4-66) carried by a hosted program or installed in the Fonts folder, the
 * embedded bitmaps of the family's 'sfnt' for two-byte codes, the family's TrueType outlines rasterized for a size
 * that has no bitmap (what System 7 did with its TrueType Chicago), or - for a family with bitmaps only - the
 * nearest strike scaled (the Font Manager's font scaling; with SetFScaleDisable the nearest strike's glyphs at
 * the requested size's spacing). HyperCard 2.2 draws its tool and pattern palettes with the glyphs of a private
 * font (FOND/NFNT 32268), some runtimes ship their own screen fonts, and suitcases dropped on HyperZebra
 * supply Chicago, Osaka and friends for every program. Such a font stands in for a host font wherever
 * {@link Fonts68k} hands out fonts, and the text code paths test for it: glyphs come from the strike, widths from
 * its width table plus the style extras, and the styles the strike does not carry (bold, italic, underline,
 * outline, shadow, condense, extend) are synthesized by {@link TextStyle68k} exactly as QuickDraw did.
 */
final class BitmapFont68k extends Font {
	final int family,size;
	/** The TextFace bits QuickDraw must synthesize (a real bold strike leaves bold out). */
	final int face;
	final int widMax,fRectHeight,ascent,descent,leading;
	/** The single-byte strike (null when the sfnt bitmaps, outlines or a scaled strike serve every code). */
	private final Nfnt nfnt;
	/** The family's sfnt: two-byte glyphs (and single-byte ones without an NFNT) from its bitmaps of this size or its outlines; null for a plain Roman strike. */
	private final SfntBitmaps68k sfnt;private final SfntBitmaps68k.Strike sbit;private final Font outlines;
	/** Font Manager scaling: the strike of another size this one is scaled from, or null. */
	private final BitmapFont68k scaledFrom;
	/** The 'FONT'/'NFNT' record the glyphs come from (the source strike's, for a scaled font), or null (sfnt, host outlines). */
	byte[] strikeData(){BitmapFont68k base=scaledFrom!=null?scaledFrom:this;return base.nfnt==null?null:base.nfnt.bits;}
	/** The point size of that strike. */
	int strikeSize(){return scaledFrom!=null?scaledFrom.size:size;}
	/** With SetFScaleDisable the scaled font keeps the source glyphs and only scales the advances. */
	private final boolean unscaledGlyphs;
	/** How host strings become the byte codes the strike indexes: Shift-JIS for a family with two-byte glyphs. */
	final StackTextEncoding encoding;
	private final Metrics metrics=new Metrics();
	private final Map<Integer,SfntBitmaps68k.Glyph> glyphs=new HashMap<Integer,SfntBitmaps68k.Glyph>();
	private static final FontRenderContext MONO=new FontRenderContext(null,false,false);

	/** The parsed 'FONT'/'NFNT' record. */
	private static final class Nfnt {
		final int firstChar,lastChar,widMax,kernMax,fRectWidth,fRectHeight,ascent,descent,leading,rowWords;
		final byte[] bits;final int imageOffset=26;final int[] loc,owTable;
		Nfnt(byte[] d){
			firstChar=u2(d,2);lastChar=u2(d,4);widMax=u2(d,6);kernMax=(short)u2(d,8);fRectWidth=u2(d,12);fRectHeight=u2(d,14);
			int owTLoc=u2(d,16);ascent=u2(d,18);descent=u2(d,20);leading=u2(d,22);rowWords=u2(d,24);
			bits=d;
			int n=lastChar-firstChar+3;int locAt=26+rowWords*2*fRectHeight,owAt=16+owTLoc*2;
			loc=new int[n];owTable=new int[n];
			for(int i=0;i<n;i++){loc[i]=locAt+i*2+1<d.length?u2(d,locAt+i*2):0;owTable[i]=owAt+i*2+1<d.length?u2(d,owAt+i*2):0xffff;}
		}
		static boolean usable(byte[] d){
			if(d==null||d.length<26)return false;
			int first=u2(d,2),last=u2(d,4),height=u2(d,14),rowWords=u2(d,24);
			return !(last<first||height<=0||height>256||rowWords<=0||26+rowWords*2*height>d.length);
		}
		/** Index into the location/width tables for a character (the missing-character entry when it has no glyph). */
		int indexOf(int c){
			if(c<firstChar||c>lastChar)return lastChar-firstChar+1;
			int i=c-firstChar;return owTable[i]==0xffff?lastChar-firstChar+1:i;
		}
		boolean bit(int col,int row){int bitIndex=row*rowWords*16+col;int byteAt=imageOffset+(bitIndex>>3);return byteAt<bits.length&&(bits[byteAt]&(0x80>>(bitIndex&7)))!=0;}
		/** The glyph of a character (the missing character's for codes without one), or null when even that is absent. */
		SfntBitmaps68k.Glyph glyph(int c){
			int i=indexOf(c);int ow=owTable[i];if(ow==0xffff)return null;
			int offset=(byte)(ow>>8),advance=ow&0xff;int from=loc[i],to=loc[i+1],w=Math.max(0,to-from);
			byte[] pixels=new byte[w*fRectHeight];
			for(int row=0;row<fRectHeight;row++)for(int col=0;col<w;col++)if(bit(from+col,row))pixels[row*w+col]=1;
			return new SfntBitmaps68k.Glyph(w,fRectHeight,kernMax+offset,ascent,advance,pixels);
		}
	}

	private BitmapFont68k(int family,int size,int face,Nfnt nfnt,SfntBitmaps68k sfnt,SfntBitmaps68k.Strike sbit,Font outlines,BitmapFont68k scaledFrom,boolean unscaledGlyphs){
		super(Font.DIALOG,(face&TextStyle68k.BOLD)!=0?Font.BOLD:Font.PLAIN,Math.max(1,size));
		this.family=family;this.size=size;this.face=face&0x7f;this.nfnt=nfnt;this.sfnt=sfnt;this.sbit=sbit;this.outlines=outlines;this.scaledFrom=scaledFrom;this.unscaledGlyphs=unscaledGlyphs;
		if(scaledFrom!=null){
			int from=Math.max(1,scaledFrom.size);
			if(unscaledGlyphs){ascent=scaledFrom.ascent;descent=scaledFrom.descent;leading=scaledFrom.leading;}
			else{ascent=scale(scaledFrom.ascent,size,from);descent=scale(scaledFrom.descent,size,from);leading=scale(scaledFrom.leading,size,from);}
			widMax=scale(scaledFrom.widMax,size,from);fRectHeight=ascent+descent;
		}
		else if(nfnt!=null){widMax=nfnt.widMax;fRectHeight=nfnt.fRectHeight;ascent=nfnt.ascent;descent=nfnt.descent;leading=nfnt.leading;}
		else if(sbit!=null){ascent=Math.max(0,sbit.ascent);descent=Math.max(0,-sbit.descent);fRectHeight=ascent+descent;widMax=sbit.widthMax;leading=0;}
		else{
			java.awt.font.LineMetrics lm=outlines.getLineMetrics("Ag",MONO);
			ascent=Math.max(1,Math.round(lm.getAscent()));descent=Math.max(0,Math.round(lm.getDescent()));leading=Math.max(0,Math.round(lm.getLeading()));
			int max=Fonts68k.metrics(outlines).getMaxAdvance();widMax=max>0?max:Math.max(1,size);fRectHeight=ascent+descent;
		}
		encoding=sfnt!=null&&sfnt.hasTwoByteCodes()?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN;
	}
	private static int scale(int v,int numer,int denom){return (int)Math.round((double)v*numer/denom);}

	/** Parses a 'FONT'/'NFNT' resource; null when the data is not a usable strike. */
	static BitmapFont68k parse(int family,int size,int face,byte[] d){return parse(family,size,face,d,null);}
	/**
	 * A font from an NFNT record (may be null) and/or the family's sfnt (may be null): its embedded bitmaps of this
	 * size, else its outlines rasterized at this size; null when none of them supplies glyphs.
	 */
	static BitmapFont68k parse(int family,int size,int face,byte[] nfntData,SfntBitmaps68k sfnt){
		Nfnt nfnt=Nfnt.usable(nfntData)?new Nfnt(nfntData):null;
		SfntBitmaps68k.Strike sbit=sfnt==null?null:sfnt.strike(size);
		Font outlines=null;
		if(sfnt!=null&&(nfnt==null||sbit==null&&sfnt.hasTwoByteCodes())){Font f=sfnt.outlines();if(f!=null)outlines=f.deriveFont((float)Math.max(1,size));}
		if(nfnt==null&&sbit==null&&outlines==null)return null;
		return new BitmapFont68k(family,size,face,nfnt,sbit==null&&outlines==null?null:sfnt,sbit,outlines,null,false);
	}
	/** The Font Manager's scaling of a strike to another size ({@code unscaledGlyphs}: SetFScaleDisable in effect). */
	static BitmapFont68k scaled(BitmapFont68k source,int size,boolean unscaledGlyphs){
		if(source==null||size==source.size)return source;
		return new BitmapFont68k(source.family,size,source.face,null,source.sfnt,null,null,source,unscaledGlyphs);
	}
	/**
	 * The strike size the Font Manager scales when {@code size} has none: half the size when it exists (clean
	 * pixel doubling), else the nearest smaller, else the nearest larger; 0 when {@code sizes} offers nothing else.
	 */
	static int nearestSize(List<Integer> sizes,int size){
		if(size%2==0&&sizes.contains(size/2))return size/2;
		int below=0,above=0;
		for(int s:sizes){if(s<=0||s==size)continue;if(s<size&&s>below)below=s;if(s>size&&(above==0||s<above))above=s;}
		return below!=0?below:above;
	}
	private static int u2(byte[] d,int at){return (d[at]&0xff)<<8|(d[at+1]&0xff);}

	boolean twoByte(){return encoding==StackTextEncoding.JAPANESE;}
	/** RealFont's answer: the size exists as a strike or from outlines, rather than scaled from another size. */
	boolean real(){return scaledFrom==null;}
	/** Character codes of a byte run: single bytes, or lead+trail pairs (lead in the high byte) for a two-byte family. */
	int[] codes(byte[] text){
		int[] codes=new int[text.length];int n=0;
		for(int i=0;i<text.length;i++){int c=text[i]&0xff;
			if(twoByte()&&Toolbox68k.isJapaneseLead(c)&&i+1<text.length){codes[n++]=c<<8|(text[i+1]&0xff);i++;}else codes[n++]=c;}
		return n==codes.length?codes:java.util.Arrays.copyOf(codes,n);
	}

	/** The glyph of a code from whichever source serves it (cached), or null when none does. */
	private SfntBitmaps68k.Glyph glyphOf(int code){
		synchronized(glyphs){if(glyphs.containsKey(code))return glyphs.get(code);}
		SfntBitmaps68k.Glyph g=null;try{g=loadGlyph(code);}catch(RuntimeException e){g=null;}
		synchronized(glyphs){glyphs.put(code,g);}
		return g;
	}
	private SfntBitmaps68k.Glyph loadGlyph(int code){
		if(scaledFrom!=null){
			SfntBitmaps68k.Glyph s=scaledFrom.glyphOf(code);if(s==null)return null;int from=Math.max(1,scaledFrom.size);
			if(unscaledGlyphs)return new SfntBitmaps68k.Glyph(s.width,s.height,s.bearingX,s.bearingY,scale(s.advance,size,from),s.bits);
			int w=s.width>0?Math.max(1,scale(s.width,size,from)):0,h=s.height>0?Math.max(1,scale(s.height,size,from)):0;
			byte[] pixels=new byte[w*h];
			for(int y=0;y<h;y++){int sy=Math.min(s.height-1,(int)((long)y*s.height/h));for(int x=0;x<w;x++){int sx=Math.min(s.width-1,(int)((long)x*s.width/w));if(s.ink(sx,sy))pixels[y*w+x]=1;}}
			return new SfntBitmaps68k.Glyph(w,h,scale(s.bearingX,size,from),scale(s.bearingY,size,from),scale(s.advance,size,from),pixels);
		}
		if(code<256&&nfnt!=null)return nfnt.glyph(code);
		if(sfnt==null)return null;
		int glyph=code>=256?sfnt.glyphOfTwoByte(code):sfnt.glyphOfRoman(code);
		String s=null;
		if(glyph==0&&code<256){s=encoding.decode(new byte[]{(byte)code});if(!s.isEmpty())glyph=sfnt.glyphOfUnicode(s.charAt(0));}
		if(sbit!=null&&glyph!=0){SfntBitmaps68k.Glyph g=sbit.glyph(glyph);if(g!=null)return classicPlacement(g);}
		if(outlines==null)return null;
		GlyphVector gv;
		if(glyph!=0)gv=outlines.createGlyphVector(MONO,new int[]{glyph});
		else{if(s==null)s=encoding.decode(code>=256?new byte[]{(byte)(code>>8),(byte)code}:new byte[]{(byte)code});
			if(s.isEmpty()||!outlines.canDisplay(s.charAt(0)))return null;gv=outlines.createGlyphVector(MONO,s.substring(0,1));}
		if(gv.getNumGlyphs()==0)return null;
		int advance=outlineAdvance(gv);
		java.awt.Rectangle b=gv.getGlyphPixelBounds(0,MONO,0,0);
		if(b.width<=0||b.height<=0)return new SfntBitmaps68k.Glyph(0,0,0,0,advance,new byte[0]);
		BufferedImage img=new BufferedImage(b.width,b.height,BufferedImage.TYPE_BYTE_BINARY);
		java.awt.Graphics2D g=img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.setColor(java.awt.Color.WHITE);g.drawGlyphVector(gv,-b.x,-b.y);g.dispose();
		byte[] pixels=new byte[b.width*b.height];
		for(int y=0;y<b.height;y++)for(int x=0;x<b.width;x++)if((img.getRGB(x,y)&0xffffff)!=0)pixels[y*b.width+x]=1;
		return new SfntBitmaps68k.Glyph(b.width,b.height,b.x,-b.y,advance,pixels);
	}

	/**
	 * Where a Japanese system puts an embedded-bitmap glyph: one pixel right of the bitmap's own horiBearingX. Measured
	 * against HyperCard on a real Japanese system with the same Osaka suitcase: its 12 ppem kanji (11 pixels wide, bearing
	 * 0, advance 12) have their ink at pen+1 and the 14 ppem ones (12 wide, bearing 1, advance 14) at pen+2, so the cell's
	 * spare column sits on the left. Applied whenever the advance has room for it, so a glyph that fills its cell stays put.
	 */
	private static SfntBitmaps68k.Glyph classicPlacement(SfntBitmaps68k.Glyph g){
		if(g==null||g.width<=0||g.bearingX+1+g.width>g.advance)return g;
		return new SfntBitmaps68k.Glyph(g.width,g.height,g.bearingX+1,g.bearingY,g.advance,g.bits);
	}
	/** The family's TrueType outlines at this size, or null: what an anti-aliased run is drawn with (see {@link #coverage}). */
	private java.awt.Font outlineFont;private boolean outlineFontLoaded;
	synchronized java.awt.Font outlineFont(){
		if(!outlineFontLoaded){outlineFontLoaded=true;
			if(outlines!=null)outlineFont=outlines;
			else if(sfnt!=null){java.awt.Font f=sfnt.outlines();if(f!=null)outlineFont=f.deriveFont((float)Math.max(1,size));}}
		return outlineFont;
	}
	/** Whether the run can be anti-aliased: only a family with outlines (Osaka, TrueType Chicago) has more than pixels to show. */
	boolean smoothable(){return outlineFont()!=null;}
	/** Glyph index of a character code in the family's sfnt (0 = none). */
	private int glyphIndexOf(int code){
		if(sfnt==null)return 0;
		int glyph=code>=256?sfnt.glyphOfTwoByte(code):sfnt.glyphOfRoman(code);
		if(glyph==0&&code<256){String s=encoding.decode(new byte[]{(byte)code});if(!s.isEmpty())glyph=sfnt.glyphOfUnicode(s.charAt(0));}
		return glyph;
	}

	/** Pixels the face adds to every non-empty advance (bold, outline, shadow, extend; condense takes one). */
	int extra(){return TextStyle68k.extra(face);}
	/** Advance width of a character code, style extras included; a two-byte code without a glyph takes an em. */
	int charWidth(int c){
		Integer a=advanceOf(c);int w=a!=null?a:c>=256?Math.max(1,size):0;
		return w+(w>0?extra():0);
	}
	/** Advances of codes measured without their glyph (outline fonts): the Font Manager's width table asks for all 256 codes
	 *  of every font it is built for, and rasterizing each outline glyph for its width took a fifth of HyperCard's start-up. */
	private final Map<Integer,Integer> advances=new HashMap<Integer,Integer>();
	/** The advance {@link #glyphOf} would give the code, or null when no source has a glyph for it. */
	private Integer advanceOf(int code){
		synchronized(glyphs){SfntBitmaps68k.Glyph g=glyphs.get(code);if(g!=null||glyphs.containsKey(code))return g==null?null:g.advance;}
		synchronized(advances){if(advances.containsKey(code))return advances.get(code);}
		Integer a;try{a=loadAdvance(code);}catch(RuntimeException e){a=null;}
		synchronized(advances){advances.put(code,a);}
		return a;
	}
	private Integer loadAdvance(int code){
		if(scaledFrom!=null){Integer s=scaledFrom.advanceOf(code);return s==null?null:scale(s,size,Math.max(1,scaledFrom.size));}
		if(code<256&&nfnt!=null||sfnt==null||outlines==null){SfntBitmaps68k.Glyph g=glyphOf(code);return g==null?null:g.advance;}
		int glyph=code>=256?sfnt.glyphOfTwoByte(code):sfnt.glyphOfRoman(code);
		String s=null;
		if(glyph==0&&code<256){s=encoding.decode(new byte[]{(byte)code});if(!s.isEmpty())glyph=sfnt.glyphOfUnicode(s.charAt(0));}
		if(sbit!=null&&glyph!=0){SfntBitmaps68k.Glyph g=glyphOf(code);return g==null?null:g.advance;}
		GlyphVector gv;
		if(glyph!=0)gv=outlines.createGlyphVector(MONO,new int[]{glyph});
		else{if(s==null)s=encoding.decode(code>=256?new byte[]{(byte)(code>>8),(byte)code}:new byte[]{(byte)code});
			if(s.isEmpty()||!outlines.canDisplay(s.charAt(0)))return null;gv=outlines.createGlyphVector(MONO,s.substring(0,1));}
		if(gv.getNumGlyphs()==0)return null;
		return outlineAdvance(gv);
	}
	/** The first glyph's advance: where the second would go (getGlyphMetrics also works out the outline's bounds). */
	private static int outlineAdvance(GlyphVector gv){return (int)Math.round(gv.getGlyphPosition(1).getX()-gv.getGlyphPosition(0).getX());}
	int width(byte[] text){int w=0;for(int c:codes(text))w+=charWidth(c);return w;}
	int width(String s){return width(encode(s));}
	/**
	 * The strike's byte codes for a host string (the family's script encoding; unmappable characters become '?'). Text reaches
	 * a Roman strike decoded as the session's Japanese: a curly quote (Roman $D5) arrives as halfwidth katakana, which is
	 * given its byte back - QuickDraw draws the strike's own glyph for the byte (Oregon Trail's "You?re" in its own font).
	 */
	byte[] encode(String s){
		if(s==null)s="";byte[] b=encoding.encodeReplacing(s);
		if(encoding!=StackTextEncoding.ROMAN||s.indexOf('?')>=0)return b;
		boolean replaced=false;for(byte x:b)if(x=='?'){replaced=true;break;}
		if(!replaced)return b;
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		for(int i=0;i<s.length();i++){String c=s.substring(i,i+1);byte[] r=encoding.encodeReplacing(c);
			if(r.length==1&&r[0]=='?'){byte[] j=StackTextEncoding.JAPANESE.encodeReplacing(c);if(j.length==1&&j[0]!='?')r=j;}
			out.write(r,0,r.length);}
		return out.toByteArray();
	}

	private int[] margins(){return TextStyle68k.margins(face,ascent,Math.max(descent,fRectHeight-ascent));}
	/** Columns of {@link #mask} left of the pen position (styled ink may spill there). */
	int maskLeft(){return margins()[0];}
	/**
	 * Columns of {@link #coverage} left of the pen position. The outlines it draws at the strike's advances overhang them, so
	 * the image has room on both sides beyond {@link #mask}'s: the last "o" of a run in Geneva 10 lost its right side and
	 * read "c" (Wingz draws its text fields word by word). Empty pixels draw nothing.
	 */
	int coverageLeft(){return maskLeft()+overhang();}
	private int overhang(){return outlineFont()==null?0:Math.max(2,(ascent+Math.max(descent,fRectHeight-ascent))/4);}
	/** Rows of {@link #mask} above the baseline. */
	int maskAscent(){return margins()[1]+ascent;}
	/**
	 * Renders a run as a 1-bit mask (TYPE_BYTE_BINARY, set = ink) with every style of the face applied: the pen sits
	 * at column {@link #maskLeft()}, the baseline at row {@link #maskAscent()}, and the advance box of the run is
	 * {@link #width(byte[])} wide and {@link #fRectHeight} tall from row {@code maskAscent()-ascent}.
	 */
	BufferedImage mask(byte[] text){return mask(text,false);}
	/** @param interior the hollow of outline/shadow text instead of its rim (see {@link TextStyle68k#apply}), same geometry */
	BufferedImage mask(byte[] text,boolean interior){
		int[] codes=codes(text);int advance=width(text);int[] m=margins();int below=Math.max(descent,fRectHeight-ascent);
		int w=Math.max(1,m[0]+advance+m[2]),h=Math.max(1,m[1]+ascent+below+m[3]);
		BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_BINARY);
		int x=m[0],baseline=m[1]+ascent;boolean bold=(face&TextStyle68k.BOLD)!=0;
		for(int c:codes){
			SfntBitmaps68k.Glyph g=glyphOf(c);
			if(g==null){x+=charWidth(c);continue;}
			int left=x+g.bearingX,top=baseline-g.bearingY;
			for(int row=0;row<g.height;row++){int py=top+row;if(py<0||py>=h)continue;
				for(int col=0;col<g.width;col++)if(g.ink(col,row))ink(img,left+col,py,bold);}
			x+=charWidth(c);
		}
		TextStyle68k.apply(img,face,baseline,m[0],advance,interior);
		return img;
	}
	/**
	 * The run as an anti-aliased coverage image (TYPE_BYTE_GRAY, 0-255), geometry identical to {@link #mask}: the
	 * family's outlines rasterized at the strike's advances, so the text is smooth without moving. The strike's own
	 * bitmaps cannot be anti-aliased (they are pixels), so the "Anti-alias text" preference takes the outlines the
	 * suitcase carries beside them - which is what Osaka's kanji need, the bitmaps being the only thing it ever
	 * offered a two-byte code. Falls back to {@link #mask} for a family without outlines.
	 */
	BufferedImage coverage(byte[] text){return coverage(text,false);}
	BufferedImage coverage(byte[] text,boolean interior){
		java.awt.Font out=outlineFont();
		if(out==null)return mask(text,interior);
		int[] codes=codes(text);int advance=width(text);int[] m=margins();int below=Math.max(descent,fRectHeight-ascent),o=overhang();
		int w=Math.max(1,m[0]+advance+m[2]+2*o),h=Math.max(1,m[1]+ascent+below+m[3]);
		BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
		java.awt.Graphics2D g=img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.setColor(java.awt.Color.WHITE);g.setFont(out);
		int x=m[0]+o,baseline=m[1]+ascent;boolean bold=(face&TextStyle68k.BOLD)!=0;
		for(int c:codes){
			int glyph=glyphIndexOf(c);GlyphVector gv=null;String single=null;
			if(glyph!=0)gv=out.createGlyphVector(MONO,new int[]{glyph});
			else{String decoded=encoding.decode(c>=256?new byte[]{(byte)(c>>8),(byte)c}:new byte[]{(byte)c});
				if(!decoded.isEmpty()&&out.canDisplay(decoded.charAt(0)))single=decoded.substring(0,1);}
			for(int pass=0;pass<(bold?2:1);pass++){ // QuickDraw's smeared bold: the glyph again one pixel right
				if(gv!=null)g.drawGlyphVector(gv,x+pass,baseline);
				else if(single!=null)g.drawString(single,x+pass,baseline);}
			x+=charWidth(c);
		}
		g.dispose();
		TextStyle68k.apply(img,face,baseline,m[0]+o,advance,interior);
		return img;
	}
	/**
	 * The run at {@code scale} times the size for the smooth zoom (TYPE_BYTE_GRAY, {@link #mask}'s geometry times the scale): the
	 * family's outlines at the strike's own advances, as {@link #coverage(byte[],boolean)} draws them, or - a family that is only
	 * bitmaps - the 1x mask magnified by {@code algorithm} (SmoothScaler).
	 */
	BufferedImage coverage(byte[] text,boolean interior,int scale,int algorithm){
		if(scale<=1)return coverage(text,interior);
		java.awt.Font out=outlineFont();
		if(out==null)return SmoothScaler.scaleMask(mask(text,interior),scale,algorithm);
		int[] codes=codes(text);int advance=width(text);int[] m=margins();int below=Math.max(descent,fRectHeight-ascent),o=overhang();
		int w=Math.max(1,m[0]+advance+m[2]+2*o),h=Math.max(1,m[1]+ascent+below+m[3]);
		BufferedImage img=new BufferedImage(w*scale,h*scale,BufferedImage.TYPE_BYTE_GRAY);
		java.awt.Graphics2D g=img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.scale(scale,scale);g.setColor(java.awt.Color.WHITE);g.setFont(out);
		java.awt.font.FontRenderContext frc=g.getFontRenderContext();
		int x=m[0]+o,baseline=m[1]+ascent;boolean bold=(face&TextStyle68k.BOLD)!=0;
		for(int c:codes){
			int glyph=glyphIndexOf(c);GlyphVector gv=null;String single=null;
			if(glyph!=0)gv=out.createGlyphVector(frc,new int[]{glyph});
			else{String decoded=encoding.decode(c>=256?new byte[]{(byte)(c>>8),(byte)c}:new byte[]{(byte)c});
				if(!decoded.isEmpty()&&out.canDisplay(decoded.charAt(0)))single=decoded.substring(0,1);}
			// the smeared bold every 1/scale of the pixel it moves, so thin stems do not split in two
			for(int pass=0;pass<=(bold?scale:0);pass++){float px=x+pass/(float)scale;
				if(gv!=null)g.drawGlyphVector(gv,px,baseline);
				else if(single!=null)g.drawString(single,px,baseline);}
			x+=charWidth(c);
		}
		g.dispose();
		TextStyle68k.apply(img,face,baseline*scale,(m[0]+o)*scale,advance*scale,interior,scale);
		return img;
	}
	private static void ink(BufferedImage img,int px,int row,boolean bold){
		int w=img.getWidth();
		if(px>=0&&px<w)img.setRGB(px,row,0xffffff);
		if(bold&&px+1>=0&&px+1<w)img.setRGB(px+1,row,0xffffff);
	}

	FontMetrics metrics(){return metrics;}
	/** Classic FontInfo: {ascent, descent, widMax, leading}, widMax with the style extra as the Font Manager reports it. */
	int[] fontInfo(){return new int[]{ascent,descent,widMax+Math.max(0,extra()),leading};}

	/** Metrics from the strike, for the host-side text code that measures through {@link FontMetrics}. */
	final class Metrics extends FontMetrics {
		Metrics(){super(BitmapFont68k.this);}
		@Override public int getAscent(){return ascent;}
		@Override public int getDescent(){return descent;}
		@Override public int getLeading(){return leading;}
		@Override public int getHeight(){return ascent+descent+leading;}
		@Override public int getMaxAscent(){return ascent;}
		@Override public int getMaxDescent(){return descent;}
		@Override public int getMaxAdvance(){return widMax+Math.max(0,extra());}
		@Override public int charWidth(char c){return width(String.valueOf(c));}
		@Override public int charWidth(int c){return c<0x10000?width(String.valueOf((char)c)):0;}
		@Override public int stringWidth(String s){return width(s);}
		@Override public int charsWidth(char[] data,int off,int len){return width(new String(data,off,len));}
		@Override public int bytesWidth(byte[] data,int off,int len){return width(java.util.Arrays.copyOfRange(data,off,off+len));}
		@Override public int[] getWidths(){int[] w=new int[256];for(int i=0;i<256;i++)w[i]=BitmapFont68k.this.charWidth(i);return w;}
	}

	@Override public boolean equals(Object o){return o==this;}
	@Override public int hashCode(){return System.identityHashCode(this);}
	@Override public String toString(){
		return "BitmapFont68k["+family+"/"+size+"/"+face+(scaledFrom!=null?" scaled from "+scaledFrom.size+(unscaledGlyphs?" (spacing only)":""):"")
			+(nfnt!=null?" NFNT "+nfnt.firstChar+".."+nfnt.lastChar+" "+nfnt.fRectWidth+"x"+nfnt.fRectHeight:"")+(sbit!=null?" sfnt "+sbit.ppem+"ppem":"")+(outlines!=null?" outlines":"")+"]";
	}
}

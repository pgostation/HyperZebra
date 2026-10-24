import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An 'sfnt' resource of a font family: the embedded bitmap strikes of its 'bloc'/'bdat' tables (Apple's names
 * for EBLC/EBDT) that carry hand-tuned pixels for a few point sizes, the TrueType outlines of its 'glyf' table
 * for every other size ({@link #outlines()} hands them to Java2D), and the 'cmap' subtables that map character
 * codes to glyph indices. Japanese system fonts keep their two-byte glyphs here (the 'NFNT' strikes of the
 * family only cover the single-byte codes), so a {@link BitmapFont68k} built from an installed suitcase draws
 * kanji from this table and Roman text from the NFNT.
 */
final class SfntBitmaps68k {
	/** A glyph's pixels: {@code bits[row*width+col]} is 1 for ink; the top-left pixel sits at (bearingX, baseline-bearingY). */
	static final class Glyph {
		final int width,height,bearingX,bearingY,advance;final byte[] bits;
		Glyph(int width,int height,int bearingX,int bearingY,int advance,byte[] bits){this.width=width;this.height=height;this.bearingX=bearingX;this.bearingY=bearingY;this.advance=advance;this.bits=bits;}
		boolean ink(int col,int row){return bits[row*width+col]!=0;}
	}

	/** One point size of the table: line metrics and the glyph images of that size. */
	final class Strike {
		final int ppem,ascent,descent,widthMax;
		private final int[] subtables; // triples (firstGlyph, lastGlyph, offset of the index subtable header within bloc)
		private final Map<Integer,Glyph> glyphs=new HashMap<Integer,Glyph>();
		private Strike(int ppem,int ascent,int descent,int widthMax,int[] subtables){this.ppem=ppem;this.ascent=ascent;this.descent=descent;this.widthMax=widthMax;this.subtables=subtables;}

		/** The image of a glyph index, or null when this size has none. */
		synchronized Glyph glyph(int glyph){
			if(glyphs.containsKey(glyph))return glyphs.get(glyph);
			Glyph g=null;try{g=glyph>0?load(glyph):null;}catch(RuntimeException e){g=null;}
			glyphs.put(glyph,g);return g;
		}
		private Glyph load(int glyph){
			for(int i=0;i+2<subtables.length;i+=3){
				int first=subtables[i],last=subtables[i+1],header=subtables[i+2];
				if(glyph<first||glyph>last)continue;
				int indexFormat=u2(header),imageFormat=u2(header+2),imageData=bdat+u4(header+4);
				int at,length=-1,bigMetrics=-1;
				switch(indexFormat){
					case 1:{int o=u4(header+8+(glyph-first)*4),next=u4(header+8+(glyph-first+1)*4);at=imageData+o;length=next-o;break;}
					case 2:{int size=u4(header+8);bigMetrics=header+12;at=imageData+(glyph-first)*size;length=size;break;}
					case 3:{int o=u2(header+8+(glyph-first)*2),next=u2(header+8+(glyph-first+1)*2);at=imageData+o;length=next-o;break;}
					case 4:{int n=u4(header+8);at=-1;
						for(int j=0;j<n;j++){int id=u2(header+12+j*4);if(id==glyph){at=imageData+u2(header+14+j*4);length=u2(header+18+j*4)-u2(header+14+j*4);break;}}
						if(at<0)return null;break;}
					case 5:{int size=u4(header+8);bigMetrics=header+12;int n=u4(header+20);at=-1;
						for(int j=0;j<n;j++)if(u2(header+24+j*2)==glyph){at=imageData+j*size;length=size;break;}
						if(at<0)return null;break;}
					default:return null;
				}
				if(length==0)return null;
				return image(imageFormat,at,bigMetrics);
			}
			return null;
		}
		/** Decodes one glyph image (EBDT formats 1, 2, 5, 6, 7; composites are not drawn). */
		private Glyph image(int format,int at,int bigMetrics){
			int height,width,bearingX,bearingY,advance;boolean bitAligned;
			switch(format){
				case 1:case 2:height=u1(at);width=u1(at+1);bearingX=s1(at+2);bearingY=s1(at+3);advance=u1(at+4);at+=5;bitAligned=format==2;break;
				case 5:if(bigMetrics<0)return null;height=u1(bigMetrics);width=u1(bigMetrics+1);bearingX=s1(bigMetrics+2);bearingY=s1(bigMetrics+3);advance=u1(bigMetrics+4);bitAligned=true;break;
				case 6:case 7:height=u1(at);width=u1(at+1);bearingX=s1(at+2);bearingY=s1(at+3);advance=u1(at+4);at+=8;bitAligned=format==7;break;
				default:return null;
			}
			if(width<=0||height<=0||width>128||height>128)return new Glyph(Math.max(0,width),Math.max(0,height),bearingX,bearingY,advance,new byte[0]);
			byte[] bits=new byte[width*height];int bit=0;
			for(int row=0;row<height;row++){
				if(!bitAligned)bit=row*((width+7)&~7);
				for(int col=0;col<width;col++,bit++){int b=at+(bit>>3);if(b<d.length&&(d[b]&(0x80>>(bit&7)))!=0)bits[row*width+col]=1;}
			}
			return new Glyph(width,height,bearingX,bearingY,advance,bits);
		}
	}

	private final byte[] d;
	private final int bdat;
	/** Whether the font carries TrueType outlines ('glyf'); {@link #outlines()} loads them on first use. */
	final boolean hasOutlines;
	private java.awt.Font outlineFont;private boolean outlinesLoaded;
	private final int cmapRoman,cmapTwoByte,cmapUnicode; // subtable offsets, -1 when absent
	private final Map<Integer,Strike> strikes=new LinkedHashMap<Integer,Strike>();

	private SfntBitmaps68k(byte[] d,int bloc,int bdat,int cmap,boolean hasOutlines){
		this.d=d;this.bdat=bdat;this.hasOutlines=hasOutlines;
		int roman=-1,twoByte=-1,unicode=-1;
		if(cmap>=0){int n=u2(cmap+2);
			for(int i=0;i<n;i++){int platform=u2(cmap+4+i*8),encoding=u2(cmap+6+i*8),at=cmap+u4(cmap+8+i*8);if(at<0||at+4>d.length)continue;int format=u2(at);
				if(platform==1&&encoding==0&&format==0&&roman<0)roman=at;
				else if(platform==1&&encoding!=0&&format==2&&twoByte<0)twoByte=at;
				else if((platform==3&&(encoding==1||encoding==0)||platform==0)&&format==4&&unicode<0)unicode=at;}}
		cmapRoman=roman;cmapTwoByte=twoByte;cmapUnicode=unicode;
		int n=bloc<0?0:u4(bloc+4);
		for(int i=0;i<n;i++){int size=bloc+8+i*48;if(size+48>d.length)break;
			int arrayOffset=u4(size),count=u4(size+8);int ppem=u1(size+44),depth=u1(size+46);
			if(depth!=1||ppem==0)continue;
			int[] subtables=new int[count*3];
			for(int j=0;j<count;j++){int e=bloc+arrayOffset+j*8;subtables[j*3]=u2(e);subtables[j*3+1]=u2(e+2);subtables[j*3+2]=bloc+arrayOffset+u4(e+4);}
			if(!strikes.containsKey(ppem))strikes.put(ppem,new Strike(ppem,s1(size+16),s1(size+17),u1(size+18),subtables));}
	}

	/** Parses the table directory; null when the font carries neither 1-bit embedded bitmaps nor outlines. */
	static SfntBitmaps68k parse(byte[] d){
		if(d==null||d.length<12)return null;
		try{
			int n=u2(d,4);int bloc=-1,bdat=-1,cmap=-1;boolean glyf=false;
			for(int i=0;i<n;i++){int e=12+i*16;if(e+16>d.length)break;int tag=u4(d,e),offset=u4(d,e+8);if(offset<0||offset>=d.length)continue;
				if(tag==0x626c6f63||tag==0x45424c43)bloc=offset;else if(tag==0x62646174||tag==0x45424454)bdat=offset;else if(tag==0x636d6170)cmap=offset;else if(tag==0x676c7966)glyf=true;} // bloc/EBLC, bdat/EBDT, cmap, glyf
			if(bloc<0||bdat<0){bloc=-1;bdat=-1;}
			if(bloc<0&&!glyf)return null;
			SfntBitmaps68k f=new SfntBitmaps68k(d,bloc,bdat,cmap,glyf);
			return f.strikes.isEmpty()&&!glyf?null:f;
		}catch(RuntimeException e){return null;}
	}

	/**
	 * The TrueType outlines as a Java2D font (1 pt; derive the size), or null when the resource has none or Java
	 * cannot read them. An Apple 'sfnt' is a TrueType file; Java maps Unicode through its Macintosh Roman 'cmap',
	 * but not through a two-byte one, so callers look glyph indices up here and draw glyph vectors by index.
	 */
	synchronized java.awt.Font outlines(){
		if(!outlinesLoaded){outlinesLoaded=true;
			if(hasOutlines)try{outlineFont=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,new java.io.ByteArrayInputStream(d));}
			catch(Exception e){outlineFont=null;}}
		return outlineFont;
	}

	/** The strike of a point size, or null. */
	Strike strike(int ppem){return strikes.get(ppem);}
	int[] sizes(){int[] s=new int[strikes.size()];int i=0;for(int k:strikes.keySet())s[i++]=k;return s;}
	boolean hasTwoByteCodes(){return cmapTwoByte>=0;}

	/** Glyph index of a single-byte code through the Macintosh Roman subtable (0 = none). */
	int glyphOfRoman(int code){
		if(cmapRoman>=0&&code>=0&&code<256)return u1(cmapRoman+6+code); // format 0: a byte per code
		if(cmapTwoByte>=0&&code>=0&&code<256)return glyphOfTwoByte(code);
		return 0;
	}
	/** Glyph index of a two-byte code (lead byte in the high byte) or a single-byte one through the format 2 subtable (0 = none). */
	int glyphOfTwoByte(int code){
		if(cmapTwoByte<0)return 0;
		int hi=(code>>8)&0xff,lo=code&0xff;int keys=cmapTwoByte+6,subHeaders=keys+512;
		if(hi==0){ // single byte: subHeader 0
			int k=u2(keys+lo*2);if(k!=0)return 0;
			return glyphOfSubHeader(subHeaders,lo);
		}
		int k=u2(keys+hi*2);if(k==0)return 0;
		return glyphOfSubHeader(subHeaders+k,lo);
	}
	private int glyphOfSubHeader(int sub,int b){
		int first=u2(sub),count=u2(sub+2),delta=(short)u2(sub+4),rangeOffset=u2(sub+6);
		if(b<first||b>=first+count)return 0;
		int at=sub+6+rangeOffset+(b-first)*2;if(at+2>d.length)return 0;
		int g=u2(at);return g==0?0:(g+delta)&0xffff;
	}
	/** Glyph index of a Unicode character through a format 4 subtable (0 = none). */
	int glyphOfUnicode(int c){
		if(cmapUnicode<0||c<0||c>0xffff)return 0;
		int segX2=u2(cmapUnicode+6),ends=cmapUnicode+14,starts=ends+segX2+2,deltas=starts+segX2,ranges=deltas+segX2;
		for(int i=0;i<segX2/2;i++){int end=u2(ends+i*2);if(c>end)continue;int start=u2(starts+i*2);if(c<start)return 0;
			int delta=u2(deltas+i*2),range=u2(ranges+i*2);
			if(range==0)return (c+delta)&0xffff;
			int at=ranges+i*2+range+(c-start)*2;if(at+2>d.length)return 0;int g=u2(at);return g==0?0:(g+delta)&0xffff;}
		return 0;
	}

	private int u1(int at){return d[at]&0xff;}
	private int s1(int at){return d[at];}
	private int u2(int at){return u2(d,at);}
	private int u4(int at){return u4(d,at);}
	private static int u2(byte[] d,int at){return (d[at]&0xff)<<8|(d[at+1]&0xff);}
	private static int u4(byte[] d,int at){return (d[at]&0xff)<<24|(d[at+1]&0xff)<<16|(d[at+2]&0xff)<<8|(d[at+3]&0xff);}
}

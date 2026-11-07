import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The 'sfnt' of a family the host renders (SystemResources68k gives those families a 'FOND' whose size-0 entry names an
 * 'sfnt' of the family's number). A real System's TrueType family always has one: FrameMaker 5 loads every FOND's sfnt
 * at start-up, listed each missing one as a "Font Installation Error" and quit when none of the system font, the
 * application font or any other family was left ("could not initialize the Font Arrays").
 * <p>
 * The font is a complete, outline-less TrueType file (empty glyphs): one glyph per character code through a Macintosh
 * 'cmap', with the host stand-in's advance widths and line metrics in 2048 units per em, and the family name in 'name'.
 * Programs that measure through it (hmtx, hhea, OS/2) get what HyperZebra draws; nothing here is drawn.
 */
final class SfntStandIn68k {
	private SfntStandIn68k(){}
	private static final int UNITS=2048,GLYPHS=256;

	/** A host-rendered family's measures in 2048 units per em: the advance of each one-byte character code, the line metrics. */
	private static final class Metrics {
		final boolean japanese;final int[] advance=new int[GLYPHS];int widest,average,ascent,descent,gap;
		Metrics(int id){
			japanese=id>=Fonts68k.JAPANESE_SYSTEM_FONT&&id<Fonts68k.JAPANESE_SYSTEM_FONT+512;
			Font font=Fonts68k.hostFont(id,UNITS,0);
			FontRenderContext frc=new FontRenderContext(null,true,true);int sum=0,counted=0;
			for(int c=0;c<GLYPHS;c++){
				if(c<0x20||c==0x7f||japanese&&(c>=0x80&&c<0xa1||c>=0xe0)){advance[c]=c==0?UNITS/2:0;continue;} // controls; Shift-JIS lead bytes
				String s=(japanese?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN).decode(new byte[]{(byte)c},0,1);
				int a=s.isEmpty()?0:(int)Math.round(font.createGlyphVector(frc,s).getGlyphMetrics(0).getAdvanceX());
				advance[c]=Math.max(0,Math.min(0xffff,a));widest=Math.max(widest,advance[c]);if(c>=0x20&&c<0x7f){sum+=advance[c];counted++;}
			}
			average=counted==0?UNITS/2:sum/counted;
			LineMetrics lm=font.getLineMetrics("Hg",frc);
			ascent=Math.round(lm.getAscent());descent=Math.round(lm.getDescent());gap=Math.round(lm.getLeading());
		}
	}
	private static final java.util.Map<Integer,Metrics> metrics=new java.util.concurrent.ConcurrentHashMap<Integer,Metrics>();
	private static Metrics metrics(int id){return metrics.computeIfAbsent(id,Metrics::new);}
	private static boolean monospaced(String name){return name.equals("Monaco")||name.equals("Courier")||name.contains("等幅");}
	/** 4.12 fixed point of a measure in font units. */
	private static int em(int units){return (int)Math.round(units*4096.0/UNITS);}

	/**
	 * The 'FOND' of family {@code id} named {@code name} (Fonts68k.NAMES): the header (family number, characters $20-$FF, the
	 * stand-in's metrics in 4.12 fixed point, monospaced flag), one association entry - size 0, the 'sfnt' of the family's
	 * number - and the family character-width table (IM Text 4-91), plain style. Programs that lay text out by the family
	 * widths read that table: FrameMaker 5 spaced every character by widMax without one.
	 */
	static byte[] fond(int id,String name){
		Metrics m=metrics(id);int first=0x20,last=0xff,widths=last-first+3;
		ByteBuffer b=ByteBuffer.allocate(60+4+2*widths);
		b.putShort((short)(monospaced(name)?0x8000:0)).putShort((short)id).putShort((short)first).putShort((short)last)
			.putShort((short)em(m.ascent)).putShort((short)-em(m.descent)).putShort((short)em(m.gap)).putShort((short)em(m.widest))
			.putInt(60).putInt(0).putInt(0).put(new byte[18]).putInt(0).putShort((short)2)
			.putShort((short)0).putShort((short)0).putShort((short)0).putShort((short)id);          // one association entry: size 0, plain, sfnt id
		b.putShort((short)0).putShort((short)0);                                                     // width table: one style entry, plain
		for(int c=first;c<=last+2;c++)b.putShort((short)em(c<=last?m.advance[c]:m.average));       // ... the last two: missing character and its spare
		return b.array();
	}

	/** The sfnt of family {@code id} named {@code name} (Fonts68k.NAMES). */
	static byte[] build(int id,String name){
		Metrics m=metrics(id);boolean japanese=m.japanese;int[] advance=m.advance;int widest=m.widest,ascent=m.ascent,descent=m.descent,gap=m.gap;
		boolean mono=monospaced(name);
		byte[] familyName=name.getBytes(java.nio.charset.Charset.forName(japanese?"Shift_JIS":"x-MacRoman"));
		String ascii=new String(familyName,StandardCharsets.ISO_8859_1).replaceAll("[^A-Za-z0-9-]","");
		if(ascii.isEmpty())ascii="HyperZebra"+id;

		ByteBuffer head=ByteBuffer.allocate(54);
		head.putInt(0x00010000).putInt(0x00010000).putInt(0).putInt(0x5f0f3cf5).putShort((short)0x000b).putShort((short)UNITS)
			.putLong(0).putLong(0).putShort((short)0).putShort((short)-descent).putShort((short)widest).putShort((short)ascent)
			.putShort((short)0).putShort((short)8).putShort((short)2).putShort((short)0).putShort((short)0);
		ByteBuffer hhea=ByteBuffer.allocate(36);
		hhea.putInt(0x00010000).putShort((short)ascent).putShort((short)-descent).putShort((short)gap).putShort((short)widest)
			.putShort((short)0).putShort((short)0).putShort((short)widest).putShort((short)1).putShort((short)0).putShort((short)0)
			.putLong(0).putShort((short)0).putShort((short)GLYPHS);
		ByteBuffer maxp=ByteBuffer.allocate(32);
		maxp.putInt(0x00010000).putShort((short)GLYPHS).putShort((short)0).putShort((short)0).putShort((short)0).putShort((short)0).putShort((short)2);
		ByteBuffer hmtx=ByteBuffer.allocate(4*GLYPHS);for(int a:advance)hmtx.putShort((short)a).putShort((short)0);
		byte[] loca=new byte[2*(GLYPHS+1)];                          // every glyph empty
		ByteBuffer cmap=ByteBuffer.allocate(12+262);
		cmap.putShort((short)0).putShort((short)1).putShort((short)1).putShort((short)(japanese?1:0)).putInt(12)
			.putShort((short)0).putShort((short)262).putShort((short)0);for(int c=0;c<GLYPHS;c++)cmap.put((byte)c);
		byte[] name1=familyName,name2="Regular".getBytes(StandardCharsets.US_ASCII),name6=ascii.getBytes(StandardCharsets.US_ASCII);
		byte[][] strings={name1,name2,name1,name6};int[] ids={1,2,4,6};
		ByteBuffer nameTable=ByteBuffer.allocate(6+12*4+name1.length*2+name2.length+name6.length);
		nameTable.putShort((short)0).putShort((short)4).putShort((short)(6+12*4));
		for(int i=0,offset=0;i<4;i++){nameTable.putShort((short)1).putShort((short)(japanese?1:0)).putShort((short)(japanese?11:0)).putShort((short)ids[i])
			.putShort((short)strings[i].length).putShort((short)offset);offset+=strings[i].length;}
		for(byte[] s:strings)nameTable.put(s);
		ByteBuffer post=ByteBuffer.allocate(32);
		post.putInt(0x00030000).putInt(0).putShort((short)(-UNITS/10)).putShort((short)(UNITS/20)).putInt(mono?1:0);
		ByteBuffer os2=ByteBuffer.allocate(86);
		os2.putShort((short)1).putShort((short)m.average).putShort((short)400).putShort((short)5).putShort((short)0)
			.putShort((short)(UNITS*13/20)).putShort((short)(UNITS*7/10)).putShort((short)0).putShort((short)(UNITS*7/50))
			.putShort((short)(UNITS*13/20)).putShort((short)(UNITS*7/10)).putShort((short)0).putShort((short)(UNITS*12/25))
			.putShort((short)(UNITS/20)).putShort((short)(UNITS*13/50)).putShort((short)0).put(new byte[10]).putInt(1).putInt(0).putInt(0).putInt(0)
			.put("HZ  ".getBytes(StandardCharsets.US_ASCII)).putShort((short)0x40).putShort((short)0x20).putShort((short)0xff)
			.putShort((short)ascent).putShort((short)-descent).putShort((short)gap).putShort((short)ascent).putShort((short)descent)
			.putInt(japanese?1<<17:1).putInt(0);

		String[] tags={"OS/2","cmap","glyf","head","hhea","hmtx","loca","maxp","name","post"};
		byte[][] tables={os2.array(),cmap.array(),new byte[0],head.array(),hhea.array(),hmtx.array(),loca,maxp.array(),nameTable.array(),post.array()};
		return assemble(tags,tables);
	}

	/** The sfnt file: offset table, table directory (tags in order), tables on 4-byte boundaries, head's checkSumAdjustment set. */
	private static byte[] assemble(String[] tags,byte[][] tables){
		int n=tags.length,entrySelector=31-Integer.numberOfLeadingZeros(n),searchRange=16<<entrySelector;
		ByteBuffer dir=ByteBuffer.allocate(12+16*n);
		dir.putInt(0x00010000).putShort((short)n).putShort((short)searchRange).putShort((short)entrySelector).putShort((short)(16*n-searchRange));
		ByteArrayOutputStream body=new ByteArrayOutputStream();int offset=12+16*n,headAt=-1;
		for(int i=0;i<n;i++){byte[] t=tables[i];
			dir.put(tags[i].getBytes(StandardCharsets.ISO_8859_1)).putInt(checksum(t)).putInt(offset).putInt(t.length);
			if(tags[i].equals("head"))headAt=offset;
			body.write(t,0,t.length);int pad=(4-(t.length&3))&3;for(int p=0;p<pad;p++)body.write(0);offset+=t.length+pad;}
		byte[] font=new byte[offset];System.arraycopy(dir.array(),0,font,0,12+16*n);byte[] b=body.toByteArray();System.arraycopy(b,0,font,12+16*n,b.length);
		int adjust=0xb1b0afba-checksum(font);
		font[headAt+8]=(byte)(adjust>>>24);font[headAt+9]=(byte)(adjust>>>16);font[headAt+10]=(byte)(adjust>>>8);font[headAt+11]=(byte)adjust;
		return font;
	}
	private static int checksum(byte[] b){int sum=0;for(int i=0;i<b.length;i+=4){int w=0;for(int k=0;k<4;k++)w=w<<8|(i+k<b.length?b[i+k]&0xff:0);sum+=w;}return sum;}
}

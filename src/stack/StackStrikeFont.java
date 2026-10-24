import java.awt.Font;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A font only the stack has: the glyphs of its own 'FONT'/'NFNT' strike, for a font name the host has no family for (not
 * even a stand-in, {@link OObject#hostHasFamily}). 東海道森林大爆走 draws its map with the letters of its 32-point "map"
 * font (FOND 129, FONT 16544), whose glyphs are map tiles; a host font in its place showed letters. HyperCard finds such a
 * font in the stack's resource fork (and the stacks in use) like any other.
 * <p>
 * The strike is turned into a TrueType font in memory: every set pixel a square of the glyph's outline, advances from the
 * strike's width table, ascent/descent/leading from its header. At the strike's own size the squares fall on whole pixels,
 * so Java's text paths (measuring, wrapping, drawing, magnification) draw exactly the bitmap; at other sizes the pixels are
 * scaled, as the Font Manager scaled a bitmap-only family. Classic system font names never come here: the host has
 * stand-ins for them ([[classic-card-text]]).
 */
final class StackStrikeFont {
	private StackStrikeFont(){}

	/** Fonts made so far, per resource set: "name/size" → the strike's font at its own size (null = none). */
	private static final Map<Rsrc,Map<String,Font>> fonts = new java.util.WeakHashMap<Rsrc,Map<String,Font>>();
	/** Font names the host has no family for (true) or has (false). */
	private static final Map<String,Boolean> hostLacks = new java.util.concurrent.ConcurrentHashMap<String,Boolean>();

	private static final String FAMILY_PREFIX = "HZ stack ";

	/** Whether {@code font} is a stack's own font (its metrics are the strike's, as on a Mac) */
	static boolean isStackFont(Font font){
		return font!=null && font.getFamily().startsWith(FAMILY_PREFIX);
	}

	/** The current stack's own font {@code name} at {@code size} in Java {@code style}, or null when the host has the family or the stack has no strike of it. */
	static Font font(String name, int style, double size){
		if(name==null || name.isEmpty() || size<=0) return null;
		Boolean lacks = hostLacks.get(name);
		if(lacks==null){ lacks = !OObject.hostHasFamily(name); hostLacks.put(name, lacks); }
		if(!lacks) return null;
		PCARD pc = PCARD.pc;
		OStack stack = pc==null ? null : pc.stack;
		Rsrc rsrc = stack==null ? null : stack.rsrc;
		if(rsrc==null) return null;
		Font base = strikeFont(rsrc, name, (int)Math.round(size));
		if(base==null) return null;
		//bold is QuickDraw's smear (OField.needsSmear: this font has no bold face); italic Java's slant
		return base.deriveFont(style&Font.ITALIC, (float)size);
	}

	private static Font strikeFont(Rsrc rsrc, String name, int size){
		String key = name.toLowerCase()+"/"+size;
		synchronized(fonts){
			Map<String,Font> own = fonts.get(rsrc);
			if(own==null){ own = new java.util.HashMap<String,Font>(); fonts.put(rsrc, own); }
			if(own.containsKey(key)) return own.get(key);
			Font f = null;
			try{ f = build(rsrc, name, size); }
			catch(Exception | Error e){ System.err.println("stack font "+name+" "+size+": "+e); }
			own.put(key, f);
			return f;
		}
	}

	/** The font of the strike for {@code size}: that size's, else the nearest size's (scaled when drawn at {@code size}). */
	private static Font build(Rsrc rsrc, String name, int size) throws java.awt.FontFormatException, IOException {
		int id = strikeId(rsrc, name, size);
		if(id==0){
			int best = 0;
			for(int d=1; d<=127 && best==0; d++){
				if((id = strikeId(rsrc, name, size+d))!=0) best = id;
				else if(size-d>0 && (id = strikeId(rsrc, name, size-d))!=0) best = id;
			}
			id = best;
		}
		if(id==0) return null;
		Rsrc.rsrcClass r = rsrc.getResourceAll(id, "font");
		if(r==null || !(r.optionInfo instanceof Rsrc.FontInfo)) return null;
		BufferedImage image = rsrc.getImage(id, "font");
		if(image==null) return null;
		Strike strike = Strike.of((Rsrc.FontInfo)r.optionInfo, image);
		if(strike==null) return null;
		byte[] ttf = strike.trueType(name, strikeSize(rsrc, name, id));
		Font f = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(ttf));
		return f;
	}

	private static int strikeId(Rsrc rsrc, String name, int size){
		int id = rsrc.fontStrikeIdAll(name, size);
		if(id==0) return 0;
		Rsrc.rsrcClass r = rsrc.getResourceAll(id, "font");
		return r!=null && r.optionInfo instanceof Rsrc.FontInfo ? id : 0;
	}

	/** The point size of strike {@code id}: the size the FOND (or the old FONT numbering) gives it. */
	private static int strikeSize(Rsrc rsrc, String name, int id){
		for(int s=1; s<=255; s++) if(rsrc.fontStrikeIdAll(name, s)==id) return s;
		return Math.max(1, id&127);
	}

	/** One strike's glyphs, read from its imported FontInfo and image. */
	static final class Strike {
		int firstChar, lastChar, kernMax, ascent, descent, leading, height, widMax;
		/** per glyph index (the codes first..last, then the missing glyph): image columns from..to, offset, advance; advance -1 = no glyph */
		int[] from, to, offset, advance;
		boolean[][] bits; //[row][column] of the strike image

		static Strike of(Rsrc.FontInfo info, BufferedImage image){
			if(info.locs==null || info.lastChar<info.firstChar) return null;
			Strike s = new Strike();
			s.firstChar = info.firstChar; s.lastChar = info.lastChar;
			s.kernMax = (short)info.kernMax;
			s.ascent = info.ascent; s.descent = info.descent; s.leading = info.leading; s.widMax = info.widMax;
			s.height = Math.min(image.getHeight(), info.fRectHeight>0 ? info.fRectHeight : image.getHeight());
			int n = info.lastChar-info.firstChar+2; //codes and the missing glyph
			s.from = new int[n]; s.to = new int[n]; s.offset = new int[n]; s.advance = new int[n];
			int[] locs = locs(info, image.getWidth()), ow = owWords(info, image.getWidth());
			for(int i=0; i<n; i++){
				s.from[i] = Math.max(0, Math.min(locs[i], image.getWidth()));
				s.to[i] = Math.max(s.from[i], Math.min(locs[i+1], image.getWidth()));
				s.offset[i] = ow[i]>>8;
				s.advance[i] = ow[i]==0xFFFF ? -1 : ow[i]&0xFF;
			}
			s.bits = new boolean[s.height][image.getWidth()];
			for(int y=0; y<s.height; y++) for(int x=0; x<image.getWidth(); x++){
				int p = image.getRGB(x, y);
				s.bits[y][x] = ((p>>>24)&255)>=128 && (p&0xFFFFFF)<0x808080;
			}
			return s;
		}

		/** The strike's location table, an entry per code, the missing glyph's and the closing one (the image's width when not imported). */
		static int[] locs(Rsrc.FontInfo info, int imageWidth){
			int n = info.lastChar-info.firstChar+3;
			int[] locs = new int[n];
			for(int i=0; i<n; i++) locs[i] = info.locs!=null && i<info.locs.length ? info.locs[i] : imageWidth;
			return locs;
		}

		/**
		 * The strike's offset/width table as words ($FFFF = no glyph), an entry per code, the missing glyph's and the closing
		 * one. Imports before Oct 23 2026 never read it ({@link #legacyWidths}): then each image is its own advance at the
		 * pen, a code without image has no glyph, except the space, a third of the widest glyph.
		 */
		static int[] owWords(Rsrc.FontInfo info, int imageWidth){
			int n = info.lastChar-info.firstChar+3;
			int[] ow = new int[n];
			boolean legacy = legacyWidths(info);
			int[] locs = legacy ? locs(info, imageWidth) : null;
			for(int i=0; i<n; i++){
				if(i==n-1){ ow[i] = 0xFFFF; continue; }
				if(legacy){
					int w = Math.max(0, locs[i+1]-locs[i]), code = info.firstChar+i;
					if(w==0 && i<n-2 && (code==' ' || code==0xCA)) w = Math.max(1, info.widMax/3);
					ow[i] = w==0 ? 0xFFFF : ((-(short)info.kernMax)&0xFF)<<8 | Math.min(255, w);
				}
				else{
					int off = info.offsets!=null && i<info.offsets.length ? info.offsets[i]&0xFF : 0xFF;
					int wid = info.widthes!=null && i<info.widthes.length ? info.widthes[i]&0xFF : 0xFF;
					ow[i] = off<<8 | wid;
				}
			}
			return ow;
		}

		/** Whether the FontInfo's offsets/widths are the location table's high/low bytes (HCResource before Oct 23 2026). */
		static boolean legacyWidths(Rsrc.FontInfo info){
			int n = info.lastChar-info.firstChar+2;
			if(info.offsets==null || info.widthes==null) return true;
			if(info.locs==null || info.locs.length!=n) return false;
			for(int i=0; i<n && i<info.offsets.length && i<info.widthes.length; i++){
				if((byte)info.offsets[i]!=(byte)(info.locs[i]>>8) || (byte)info.widthes[i]!=(byte)info.locs[i]) return false;
			}
			return true;
		}

		/** The outline of glyph {@code i} in pixels (x from the pen, y down from the strike's top row), null when it has no pixels. */
		Area outline(int i){
			Area area = null;
			int dx = kernMax+offset[i];
			for(int y=0; y<height; y++){
				int x = from[i];
				while(x<to[i]){
					if(!bits[y][x]){ x++; continue; }
					int start = x;
					while(x<to[i] && bits[y][x]) x++;
					Area run = new Area(new Rectangle(dx+start-from[i], y, x-start, 1));
					if(area==null) area = run; else area.add(run);
				}
			}
			return area;
		}

		/** The strike as a TrueType font of em {@code size} pixels, named {@code name} (with the size). */
		byte[] trueType(String name, int size){
			int unit = Math.max(1, Math.min(64, 16384/Math.max(1, size))); //font units per pixel
			int unitsPerEm = size*unit;
			int missing = lastChar-firstChar+1;
			//glyph 0 = the missing glyph, then every code that has one
			List<Integer> glyphs = new ArrayList<Integer>();
			glyphs.add(missing);
			TreeMap<Integer,Integer> cmap = new TreeMap<Integer,Integer>();
			Charset macRoman = macRoman();
			for(int c=firstChar; c<=lastChar; c++){
				int i = c-firstChar;
				if(advance[i]<0) continue;
				int g = glyphs.size();
				glyphs.add(i);
				for(int u : unicodes(c, macRoman)) cmap.putIfAbsent(u, g);
			}
			int numGlyphs = glyphs.size();
			ByteArrayOutputStream glyf = new ByteArrayOutputStream();
			int[] loca = new int[numGlyphs+1];
			int[] adv = new int[numGlyphs], lsb = new int[numGlyphs];
			int xMin = 0, yMin = 0, xMax = 0, yMax = 0, maxPoints = 0, maxContours = 0, advMax = 0;
			int minLsb = Integer.MAX_VALUE, minRsb = Integer.MAX_VALUE, maxExtent = 0;
			for(int g=0; g<numGlyphs; g++){
				int i = glyphs.get(g);
				loca[g] = glyf.size();
				adv[g] = Math.max(0, advance[i])*unit;
				advMax = Math.max(advMax, adv[g]);
				Area area = outline(i);
				if(area==null || area.isEmpty()) continue;
				List<int[]> contours = new ArrayList<int[]>();
				List<Integer> cur = new ArrayList<Integer>();
				double[] p = new double[6];
				for(PathIterator it = area.getPathIterator(null); !it.isDone(); it.next()){
					int t = it.currentSegment(p);
					if(t==PathIterator.SEG_MOVETO){ flush(contours, cur); cur.add((int)Math.round(p[0])); cur.add((int)Math.round(p[1])); }
					else if(t==PathIterator.SEG_LINETO){ cur.add((int)Math.round(p[0])); cur.add((int)Math.round(p[1])); }
					else if(t==PathIterator.SEG_CLOSE) flush(contours, cur);
				}
				flush(contours, cur);
				if(contours.isEmpty()) continue;
				int points = 0;
				int gxMin = Integer.MAX_VALUE, gyMin = Integer.MAX_VALUE, gxMax = Integer.MIN_VALUE, gyMax = Integer.MIN_VALUE;
				for(int[] c : contours){
					points += c.length/2;
					for(int k=0; k<c.length; k+=2){
						c[k] *= unit; c[k+1] = (ascent-c[k+1])*unit; //y up from the baseline
						gxMin = Math.min(gxMin, c[k]); gxMax = Math.max(gxMax, c[k]);
						gyMin = Math.min(gyMin, c[k+1]); gyMax = Math.max(gyMax, c[k+1]);
					}
				}
				lsb[g] = gxMin;
				minLsb = Math.min(minLsb, gxMin); minRsb = Math.min(minRsb, adv[g]-gxMax); maxExtent = Math.max(maxExtent, gxMax);
				xMin = Math.min(xMin, gxMin); yMin = Math.min(yMin, gyMin); xMax = Math.max(xMax, gxMax); yMax = Math.max(yMax, gyMax);
				maxPoints = Math.max(maxPoints, points); maxContours = Math.max(maxContours, contours.size());
				DataOutputStream d = new DataOutputStream(glyf);
				try{
					d.writeShort(contours.size()); d.writeShort(gxMin); d.writeShort(gyMin); d.writeShort(gxMax); d.writeShort(gyMax);
					int end = -1;
					for(int[] c : contours){ end += c.length/2; d.writeShort(end); }
					d.writeShort(0); //no instructions
					for(int k=0; k<points; k++) d.writeByte(1); //on-curve, 16-bit deltas
					int last = 0;
					for(int[] c : contours) for(int k=0; k<c.length; k+=2){ d.writeShort(c[k]-last); last = c[k]; }
					last = 0;
					for(int[] c : contours) for(int k=1; k<c.length; k+=2){ d.writeShort(c[k]-last); last = c[k]; }
					while(glyf.size()%4!=0) d.writeByte(0);
				}catch(IOException e){ throw new IllegalStateException(e); }
			}
			loca[numGlyphs] = glyf.size();
			if(minLsb==Integer.MAX_VALUE){ minLsb = 0; minRsb = 0; }
			int asc = ascent*unit, desc = descent*unit, gap = leading*unit;

			TreeMap<String,byte[]> tables = new TreeMap<String,byte[]>();
			Table head = new Table();
			head.i32(0x00010000); head.i32(0x00010000); head.i32(0); head.i32(0x5F0F3CF5); head.i16(0x000B); head.i16(unitsPerEm);
			head.i32(0); head.i32(0); head.i32(0); head.i32(0);
			head.i16(xMin); head.i16(yMin); head.i16(xMax); head.i16(yMax);
			head.i16(0); head.i16(Math.max(1, size)); head.i16(2); head.i16(1); head.i16(0);
			tables.put("head", head.bytes());
			Table hhea = new Table();
			hhea.i32(0x00010000); hhea.i16(asc); hhea.i16(-desc); hhea.i16(gap); hhea.i16(advMax);
			hhea.i16(minLsb); hhea.i16(minRsb); hhea.i16(maxExtent); hhea.i16(1); hhea.i16(0); hhea.i16(0);
			for(int k=0; k<4; k++) hhea.i16(0);
			hhea.i16(0); hhea.i16(numGlyphs);
			tables.put("hhea", hhea.bytes());
			Table maxp = new Table();
			maxp.i32(0x00010000); maxp.i16(numGlyphs); maxp.i16(maxPoints); maxp.i16(maxContours);
			maxp.i16(0); maxp.i16(0); maxp.i16(2); for(int k=0; k<8; k++) maxp.i16(0);
			tables.put("maxp", maxp.bytes());
			Table os2 = new Table();
			os2.i16(1); os2.i16(advMax/2); os2.i16(400); os2.i16(5); os2.i16(0);
			for(int k=0; k<10; k++) os2.i16(0);
			os2.i16(0); for(int k=0; k<10; k++) os2.i8(0);
			for(int k=0; k<4; k++) os2.i32(0);
			os2.i32(0x485A2020); //"HZ  "
			os2.i16(0x40);
			os2.i16(cmap.isEmpty() ? 0 : Math.min(0xFFFF, cmap.firstKey())); os2.i16(cmap.isEmpty() ? 0 : Math.min(0xFFFF, cmap.lastKey()));
			os2.i16(asc); os2.i16(-desc); os2.i16(gap); os2.i16(asc); os2.i16(desc);
			os2.i32(1); os2.i32(0);
			tables.put("OS/2", os2.bytes());
			Table hmtx = new Table();
			for(int g=0; g<numGlyphs; g++){ hmtx.i16(adv[g]); hmtx.i16(lsb[g]); }
			tables.put("hmtx", hmtx.bytes());
			Table locaT = new Table();
			for(int o : loca) locaT.i32(o);
			tables.put("loca", locaT.bytes());
			tables.put("glyf", glyf.toByteArray());
			tables.put("cmap", cmap(cmap));
			tables.put("name", names(FAMILY_PREFIX+name+" "+size));
			Table post = new Table();
			post.i32(0x00030000); post.i32(0); post.i16(-unit); post.i16(unit); post.i32(0); post.i32(0); post.i32(0); post.i32(0); post.i32(0);
			tables.put("post", post.bytes());
			return sfnt(tables);
		}

		private static void flush(List<int[]> contours, List<Integer> cur){
			//drop a closing point equal to the first
			int n = cur.size();
			if(n>=4 && cur.get(0).equals(cur.get(n-2)) && cur.get(1).equals(cur.get(n-1))){ cur.remove(n-1); cur.remove(n-2); }
			if(cur.size()>=6){
				int[] c = new int[cur.size()];
				for(int k=0; k<c.length; k++) c[k] = cur.get(k);
				contours.add(c);
			}
			cur.clear();
		}
	}

	/** The Unicode characters a strike's byte code stands for: itself below $80, else MacRoman, and half-width katakana for $A1-$DF (Japanese stacks' text). */
	private static int[] unicodes(int code, Charset macRoman){
		if(code<0x80) return new int[]{code};
		if(code>0xFF) return new int[0];
		int roman = macRoman==null ? code : new String(new byte[]{(byte)code}, macRoman).charAt(0);
		if(code>=0xA1 && code<=0xDF) return new int[]{roman, 0xFF61+code-0xA1};
		return new int[]{roman};
	}

	private static Charset macRoman(){
		try{ return Charset.forName("x-MacRoman"); }catch(Exception e){ return null; }
	}

	private static byte[] cmap(TreeMap<Integer,Integer> map){
		//format 4, one segment per character, then the closing $FFFF segment
		List<int[]> segs = new ArrayList<int[]>();
		for(Map.Entry<Integer,Integer> e : map.entrySet()) if(e.getKey()<0xFFFF) segs.add(new int[]{e.getKey(), e.getValue()});
		int segCount = segs.size()+1;
		int searchRange = 2*Integer.highestOneBit(segCount);
		int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(segCount));
		Table sub = new Table();
		sub.i16(4); sub.i16(16+segCount*8); sub.i16(0);
		sub.i16(segCount*2); sub.i16(searchRange); sub.i16(entrySelector); sub.i16(segCount*2-searchRange);
		for(int[] s : segs) sub.i16(s[0]);
		sub.i16(0xFFFF);
		sub.i16(0);
		for(int[] s : segs) sub.i16(s[0]);
		sub.i16(0xFFFF);
		for(int[] s : segs) sub.i16((s[1]-s[0])&0xFFFF);
		sub.i16(1);
		for(int k=0; k<segCount; k++) sub.i16(0);
		byte[] subtable = sub.bytes();
		Table t = new Table();
		t.i16(0); t.i16(1); t.i16(3); t.i16(1); t.i32(12);
		t.raw(subtable);
		return t.bytes();
	}

	private static byte[] names(String family){
		String[][] records = {{"1", family}, {"2", "Regular"}, {"3", family}, {"4", family}, {"6", family.replaceAll("[^A-Za-z0-9]", "-")}};
		Table strings = new Table();
		Table t = new Table();
		t.i16(0); t.i16(records.length); t.i16(6+records.length*12);
		for(String[] r : records){
			byte[] s = r[1].getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
			t.i16(3); t.i16(1); t.i16(0x409); t.i16(Integer.parseInt(r[0])); t.i16(s.length); t.i16(strings.size());
			strings.raw(s);
		}
		t.raw(strings.bytes());
		return t.bytes();
	}

	private static byte[] sfnt(TreeMap<String,byte[]> tables){
		int n = tables.size();
		int searchRange = 16*Integer.highestOneBit(n);
		Table t = new Table();
		t.i32(0x00010000); t.i16(n); t.i16(searchRange); t.i16(Integer.numberOfTrailingZeros(Integer.highestOneBit(n))); t.i16(n*16-searchRange);
		int offset = 12+n*16;
		for(Map.Entry<String,byte[]> e : tables.entrySet()){ //TreeMap: tags in order ("OS/2" first)
			byte[] b = e.getValue();
			t.raw(e.getKey().getBytes(java.nio.charset.StandardCharsets.US_ASCII)); t.i32(checksum(b)); t.i32(offset); t.i32(b.length);
			offset += (b.length+3)&~3;
		}
		for(byte[] b : tables.values()){ t.raw(b); for(int k=b.length; k%4!=0; k++) t.i8(0); }
		return t.bytes();
	}

	private static int checksum(byte[] b){
		int sum = 0;
		for(int k=0; k<b.length; k+=4){
			int v = 0;
			for(int j=0; j<4; j++) v = (v<<8)|(k+j<b.length ? b[k+j]&0xFF : 0);
			sum += v;
		}
		return sum;
	}

	/** A big-endian table being written. */
	private static final class Table {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();
		void i8(int v){ out.write(v); }
		void i16(int v){ out.write(v>>8); out.write(v); }
		void i32(int v){ i16(v>>16); i16(v); }
		void raw(byte[] b){ out.write(b, 0, b.length); }
		int size(){ return out.size(); }
		byte[] bytes(){ return out.toByteArray(); }
	}
}

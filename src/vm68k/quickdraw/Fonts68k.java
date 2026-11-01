import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps classic Macintosh font numbers and names to host fonts and metrics. */
final class Fonts68k {
	private static final Map<String,Font> cache=new HashMap<String,Font>();
	/**
	 * Preference: anti-alias hosted application text on every destination, not only 32-bit direct windows.
	 * Host fonts are outline fonts, and rendered 1-bit into an 8-bit screen port or GWorld they are hard to read.
	 * Set from the Preferences dialog (PCARD.setSmoothText68k); -Dhz68k.smoothtext=true forces it for scripted runs.
	 */
	static volatile boolean smoothText=Boolean.getBoolean("hz68k.smoothtext");
	/**
	 * A 1-bit text mask of a host (outline) font: rasterized anti-aliased and thresholded. Java's aliased rasterizer keeps
	 * only the pixels whose centre a stem covers, and the thin stems of a small outline font miss most of them (Helvetica
	 * Neue 9 for Geneva 9 lost its i, l and t in HyperCard's button names); a coverage threshold keeps every stem.
	 * {@code painter} draws the text in white on the graphics it is given (a grey image); the returned mask is TYPE_BYTE_BINARY.
	 */
	static final int BINARY_COVERAGE=96;
	static BufferedImage binaryMask(int width,int height,java.util.function.Consumer<Graphics2D> painter){
		BufferedImage grey=new BufferedImage(Math.max(1,width),Math.max(1,height),BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g=grey.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.setColor(java.awt.Color.WHITE);painter.accept(g);g.dispose();
		BufferedImage mask=new BufferedImage(grey.getWidth(),grey.getHeight(),BufferedImage.TYPE_BYTE_BINARY);java.awt.image.Raster raster=grey.getRaster();
		for(int y=0;y<grey.getHeight();y++)for(int x=0;x<grey.getWidth();x++)if(raster.getSample(x,y,0)>=BINARY_COVERAGE)mask.setRGB(x,y,0xffffff);
		return mask;
	}
	static Object textAntialiasHint(){return smoothText?RenderingHints.VALUE_TEXT_ANTIALIAS_ON:RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;}
	private static final Graphics2D scratch;
	static{scratch=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB).createGraphics();scratch.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);scratch.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);}
	/** Classic font numbers and names (System 7 US and Japanese systems). The Japanese names' dash is Shift-JIS $817C, which decodes
	 *  to U+2212: with U+FF0D they did not encode (Word 5.1's Font menu showed "本明朝?Ｍ") nor match decoded suitcase names. */
	static final LinkedHashMap<Integer,String> NAMES=new LinkedHashMap<Integer,String>();
	static{
		NAMES.put(0,"Chicago");NAMES.put(1,"Geneva");NAMES.put(2,"New York");NAMES.put(3,"Geneva");NAMES.put(4,"Monaco");NAMES.put(5,"Venice");NAMES.put(6,"London");NAMES.put(7,"Athens");
		NAMES.put(8,"San Francisco");NAMES.put(9,"Toronto");NAMES.put(11,"Cairo");NAMES.put(12,"Los Angeles");NAMES.put(13,"Zapf Dingbats");NAMES.put(14,"Bookman");NAMES.put(15,"N Helvetica Narrow");
		NAMES.put(16,"Palatino");NAMES.put(18,"Zapf Chancery");NAMES.put(20,"Times");NAMES.put(21,"Helvetica");NAMES.put(22,"Courier");NAMES.put(23,"Symbol");NAMES.put(24,"Mobile");
		NAMES.put(33,"Avant Garde");NAMES.put(34,"New Century Schlbk");NAMES.put(16384,"Osaka");NAMES.put(16385,"本明朝−Ｍ");NAMES.put(16387,"平成明朝");NAMES.put(16388,"平成角ゴシック");
		NAMES.put(16436,"Osaka−等幅"); // the Japanese itlb's itlbMonoFond (smScriptMonoFondSize): terminal programs keep it as their fixed-width font
	}
	/** The system font of a Japanese system (Osaka): font numbers 0/1/3 resolve to it when the running program is Japanese. */
	static final int JAPANESE_SYSTEM_FONT=16384;
	/** Whether the running program sees a Japanese system (set by Toolbox68k from its text encoding). */
	static volatile boolean japaneseSystem;
	/**
	 * The host fonts that stand for a Roman-script font number (2..16383 but 3; 0/1/3 are Osaka under a Japanese system):
	 * KanjiTalk draws such a font's one-byte characters with its own glyphs, so $A5 in Monaco is its bullet, not the half-width
	 * katakana of the system script (MacYahtzee's dice pips). See {@link #romanScript} and Toolbox68k.decodeFor.
	 */
	/** Host fonts standing in for a Japanese-script family (Osaka, 平成明朝...) when its suitcase is not installed: their classic
	 *  metrics are Osaka's bitmap ones, not the host font's ({@link #fontInfo}). */
	private static final java.util.Set<Font> japaneseFonts=java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Font,Boolean>()));
	private static final java.util.Set<Font> romanFonts=java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Font,Boolean>()));
	/** Whether a font number is of the Roman script under a Japanese system (the ones not resolved to Osaka). */
	static boolean romanScriptNumber(int id){return id>=2&&id!=3&&id<JAPANESE_SYSTEM_FONT;}
	/** Whether {@code font} is a host font made for a Roman-script font number (a strike reads its bytes itself); the caller knows its own system's script. */
	static boolean romanScript(Font font){
		while(font instanceof StyledFont68k)font=((StyledFont68k)font).base;
		return font!=null&&!(font instanceof BitmapFont68k)&&romanFonts.contains(font);
	}
	/** SetFScaleDisable: a size without a strike takes the nearest strike's glyphs at the requested size's spacing instead of scaled pixels. */
	static volatile boolean scaleDisabled;
	private Fonts68k(){}

	/**
	 * A program's own font substitutions (LauncherStore.AppSettings.fontSubstitutions), as the Font Patchin' control panel made
	 * them on a real Mac: text the program sets in one family is drawn and measured in another. The English iTunes 2 draws its
	 * song list in its own Lucida Grande ('FOND' 1024 + 'sfnt'), a Roman font, so the Shift-JIS titles it makes from Unicode
	 * tags came out one Roman glyph per byte ("ç˘ÇÃâÃ" for 桜の歌); with Lucida Grande → Osaka they read. The program still sees
	 * its own font number (the port's txFont, GetFontName); FontScript and FontToScript answer the replacement's script.
	 * Families by name, any case; the font number 0, 1 or 3 of a Japanese System is also Osaka (what it draws). The replacement
	 * {@link #AUTOMATIC} is the Japanese family that suits the original ({@link #automaticReplacement}).
	 * <p>It also records the fonts the program draws text in ({@link #noteDrawn}): users know where the garbled text is, not
	 * its font's name, so "Fix Garbled Text…" (MacApp68k.fixGarbledText) tries these where they clicked.
	 */
	static final class Substitution {
		private volatile Map<String,String> byName=java.util.Collections.emptyMap();
		private volatile java.util.List<String> pairs=java.util.Collections.emptyList();
		private volatile boolean japanese;
		/** font number -> the number drawn instead (itself when not substituted), for the families named so far; a new map with
		 *  every {@link #set} (a resolution under way meanwhile lands in the old one) */
		private volatile Map<Integer,Integer> resolved=new java.util.concurrent.ConcurrentHashMap<Integer,Integer>();
		/** The pairs as the launcher keeps them ("original\treplacement"); the program's System script for 0/1/3. Takes effect at once. */
		void set(java.util.List<String> pairs,boolean japaneseSystem){
			Map<String,String> map=new HashMap<String,String>();java.util.List<String> kept=new java.util.ArrayList<String>();
			if(pairs!=null)for(String p:pairs){int tab=p.indexOf('\t');if(tab>0&&tab<p.length()-1){map.put(p.substring(0,tab).toLowerCase(),p.substring(tab+1));kept.add(p);}}
			byName=map;this.pairs=java.util.Collections.unmodifiableList(kept);japanese=japaneseSystem;resolved=new java.util.concurrent.ConcurrentHashMap<Integer,Integer>();
		}
		/** The pairs in effect, as {@link #set} took them. */
		java.util.List<String> pairs(){return pairs;}
		boolean japaneseSystem(){return japanese;}
		boolean isEmpty(){return byName.isEmpty();}
		int apply(int id){
			Map<Integer,Integer> cache=resolved;Map<String,String> names=byName; // in this order: set() replaces byName first
			if(names.isEmpty())return id;
			Integer known=cache.get(id);if(known!=null)return known;
			String name=nameOf(id);
			String target=names.get(name.toLowerCase());
			if(target==null&&japanese&&(id==0||id==1||id==3))target=names.get(NAMES.get(JAPANESE_SYSTEM_FONT).toLowerCase());
			int to=id;
			if(AUTOMATIC.equals(target))to=automaticReplacement(id);
			else if(target!=null){int n=numberOf(target);if(n!=0||target.equalsIgnoreCase(NAMES.get(0)))to=n;}
			if(!name.isEmpty())cache.put(id,to); // an unnamed number may become a family later (a document's fonts): asked again
			return to;
		}

		/** A font the program drew text in: its number as the program asked for it, its name then, how often, and how often the text looked garbled. */
		static final class Drawn {
			final int id;final String name;volatile int count,garbled;volatile long last;
			Drawn(int id,String name){this.id=id;this.name=name;}
		}
		private final Map<Integer,Drawn> drawn=new java.util.concurrent.ConcurrentHashMap<Integer,Drawn>();
		/**
		 * The program drew {@code text} asking for font {@code id}, drawn in {@code font}. Text "looks garbled" when a Japanese
		 * System's Shift-JIS pairs went to a strike of a Roman family: its glyphs show each byte as a Roman character (a host font
		 * stand-in reads the pairs as Japanese anyway, see Toolbox68k.decodeFor).
		 */
		void noteDrawn(int id,byte[] text,Font font){
			if(text.length==0)return;
			Drawn d=drawn.get(id);
			if(d==null){String name=nameOfDrawn(id);if(name.isEmpty())return;d=drawn.computeIfAbsent(id,k->new Drawn(k,name));}
			d.count++;d.last=System.nanoTime();
			if(japanese&&font instanceof BitmapFont68k&&romanScriptNumber(((BitmapFont68k)font).family)&&hasShiftJisPair(text))d.garbled++;
		}
		private String nameOfDrawn(int id){return japanese&&(id==0||id==1||id==3)?NAMES.get(JAPANESE_SYSTEM_FONT):nameOf(id);}
		/**
		 * The fonts to try for text at a place the user pointed at, most likely first: those whose text looked garbled, then the
		 * most used; not those already substituted, nor (on a Japanese System) its own Japanese families.
		 */
		java.util.List<Drawn> candidates(){
			java.util.List<Drawn> list=new java.util.ArrayList<Drawn>();
			for(Drawn d:drawn.values()){
				if(apply(d.id)!=d.id)continue;
				if(japanese&&(d.id==0||d.id==1||d.id==3||d.id>=JAPANESE_SYSTEM_FONT&&d.id<JAPANESE_SYSTEM_FONT+512))continue;
				list.add(d);
			}
			list.sort((a,b)->a.garbled!=b.garbled?Integer.compare(b.garbled,a.garbled):a.count!=b.count?Integer.compare(b.count,a.count):Long.compare(b.last,a.last));
			return list;
		}
		/** Every font the program drew text in, most used first (the launcher's Fonts tab lists them first). */
		java.util.List<Drawn> drawnFonts(){
			java.util.List<Drawn> list=new java.util.ArrayList<Drawn>(drawn.values());
			list.sort((a,b)->Integer.compare(b.count,a.count));
			return list;
		}
	}
	/** A replacement chosen for the original: a Japanese family like it ({@link #automaticReplacement}). Kept as such in the settings. */
	static final String AUTOMATIC="*";
	/**
	 * The Japanese family to show a family's text in when the user does not choose one: Osaka−等幅 for a monospaced family (its
	 * columns stay), 本明朝−Ｍ for a serif one, else Osaka - what the Japanese System itself uses.
	 */
	static int automaticReplacement(int id){
		String name=nameOf(id).toLowerCase();
		if(id==4||id==22||name.contains("mono")||name.contains("courier")||name.contains("monaco")||name.contains("\u7b49\u5e45"))return 16436;
		if(id==2||id==20||id==16||id==14||id==34||name.contains("times")||name.contains("serif")&&!name.contains("sans")||name.contains("new york")||name.contains("palatino")||name.contains("century")||name.contains("garamond")||name.contains("georgia")||name.contains("mincho")||name.contains("\u660e\u671d"))return 16385;
		return JAPANESE_SYSTEM_FONT;
	}
	/** Whether the bytes hold a complete Shift-JIS two-byte character (a lead $81-$9F/$E0-$FC and a trail $40-$7E/$80-$FC). */
	static boolean hasShiftJisPair(byte[] text){
		for(int i=0;i+1<text.length;i++){int c=text[i]&255,t=text[i+1]&255;
			if((c>=0x81&&c<=0x9f||c>=0xe0&&c<=0xfc)&&(t>=0x40&&t<=0x7e||t>=0x80&&t<=0xfc))return true;}
		return false;
	}
	/** The substitutions of the program whose thread asks (MacApp68k.run sets them; Thread Manager threads it starts inherit them). */
	private static final InheritableThreadLocal<Substitution> substitution=new InheritableThreadLocal<Substitution>();
	static void useSubstitution(Substitution s){if(s==null)substitution.remove();else substitution.set(s);}
	/** The font number the running program's text in {@code id} is drawn with: its own substitution, or {@code id}. */
	static int substitute(int id){Substitution s=substitution.get();return s==null?id:s.apply(id);}
	/** The running program drew {@code text} asking for font {@code id} (QuickDraw's DrawText family, TextEdit): see {@link Substitution#noteDrawn}. */
	static void noteDrawn(int id,byte[] text,Font font){Substitution s=substitution.get();if(s!=null)s.noteDrawn(id,text,font);}

	static String nameOf(int id){
		String name=NAMES.get(id);
		if(name==null)name=programFamilyName(id);
		if(name==null){InstalledFonts68k.Family f=InstalledFonts68k.family(id);name=f==null?null:f.name;}
		return name==null?"":name;
	}
	static int numberOf(String name){
		if(name==null)return 0;
		// GetFNum answers the 'FOND' resource's ID (ROM FontMgr.a GetFNum: GetNamedResource + GetResInfo): Geneva is 3. Number 1 is
		// only applFont's alias, no family of its own - Word 5.1 builds its Font menu with GetFNum + GetFontName, and Geneva came
		// back 1, which a Japanese System names Osaka (ApFontID).
		for(Map.Entry<Integer,String> entry:NAMES.entrySet())if(entry.getKey()!=1&&entry.getValue().equalsIgnoreCase(name))return entry.getKey();
		// the Font Manager finds a family by its 'FOND' name anywhere in the resource chain: Gold Digger calls GetFNum("GD Font")
		// for the FOND 32440 in its own file and stops with "Error -30006" when the number comes back 0
		int own=programFamilyNumber(name);if(own!=0)return own;
		InstalledFonts68k.Family installed=InstalledFonts68k.familyNamed(name);
		if(installed!=null)return installed.id;
		String lower=name.toLowerCase();
		if(lower.contains("osaka")||lower.contains("ゴシック")||lower.contains("gothic"))return 16384;
		if(lower.contains("明朝")||lower.contains("mincho"))return 16385;
		return 0;
	}

	/** Whether {@code name} is exactly a classic font's or an installed family's name (any case), not just like one. */
	static boolean isFontName(String name){
		if(name==null||name.isEmpty())return false;
		for(String n:NAMES.values())if(n.equalsIgnoreCase(name))return true;
		return InstalledFonts68k.familyNamed(name)!=null;
	}

	/** Supplies a program's own bitmapped strikes ('FOND'/'NFNT'/'FONT' resources) for font numbers it defines; see {@link BitmapFont68k}. */
	interface BitmapLookup {
		BitmapFont68k find(int id,int size,int face);
		/** The number of a family the program carries itself (its named 'FOND'), or 0. */
		default int familyNumber(String name){return 0;}
		/** The name of a family the program carries itself, or null. */
		default String familyName(int id){return null;}
	}
	/** The strikes of the running programs (each Toolbox68k registers itself in attach); host fonts serve everything else. */
	private static final java.util.List<java.lang.ref.WeakReference<BitmapLookup>> bitmapLookups=new java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<BitmapLookup>>();
	static void registerBitmapLookup(BitmapLookup lookup){
		for(java.lang.ref.WeakReference<BitmapLookup> r:bitmapLookups){BitmapLookup l=r.get();if(l==lookup)return;if(l==null)bitmapLookups.remove(r);}
		bitmapLookups.add(new java.lang.ref.WeakReference<BitmapLookup>(lookup));
	}
	private static int programFamilyNumber(String name){
		for(java.lang.ref.WeakReference<BitmapLookup> r:bitmapLookups){BitmapLookup l=r.get();if(l==null)continue;int id=l.familyNumber(name);if(id!=0)return id;}
		return 0;
	}
	private static String programFamilyName(int id){
		for(java.lang.ref.WeakReference<BitmapLookup> r:bitmapLookups){BitmapLookup l=r.get();if(l==null)continue;String name=l.familyName(id);if(name!=null)return name;}
		return null;
	}
	private static BitmapFont68k strike(int id,int size,int face){
		for(java.lang.ref.WeakReference<BitmapLookup> r:bitmapLookups){BitmapLookup l=r.get();if(l==null)continue;BitmapFont68k strike=l.find(id,size,face);if(strike!=null)return strike;}
		return null;
	}

	/** Every family name a program can choose from (the classic table plus the installed suitcases), for font menus. */
	static java.util.List<String> allNames(){
		java.util.List<String> names=new java.util.ArrayList<String>();
		for(String n:NAMES.values())if(!names.contains(n))names.add(n);
		for(InstalledFonts68k.Family f:InstalledFonts68k.families())if(!f.name.isEmpty()&&!names.contains(f.name))names.add(f.name);
		return names;
	}
	/**
	 * The TrueType families, as AppendResMenu(menu, 'sfnt') lists them: the classic table (drawn from host outlines, so
	 * IsOutline says true for them too) plus the installed suitcases that carry an 'sfnt'. addDepth 1.0 counts this menu
	 * and refuses to start ("at least one TrueType or Type 1 font") when it is empty.
	 */
	static java.util.List<String> outlineNames(){
		java.util.List<String> names=new java.util.ArrayList<String>();
		for(String n:NAMES.values())if(!names.contains(n))names.add(n);
		for(InstalledFonts68k.Family f:InstalledFonts68k.families())if(!f.name.isEmpty()&&!names.contains(f.name)&&f.hasSfnt())names.add(f.name);
		return names;
	}
	/** The families a program file carries itself (named 'FOND's, else size-0 'FONT's), named as {@link #nameOf} names them while it runs: the launcher's font substitution offers them first. */
	static java.util.List<String> familiesOf(java.io.File program){
		java.util.List<String> names=new java.util.ArrayList<String>();
		try{ResourceFile68k rf=ResourceFile68k.open(program);
			for(ResourceFile68k.Resource r:rf.ofType(ResourceFile68k.fourcc("FOND")))if(r.name!=null&&!r.name.isEmpty()&&!names.contains(r.name))names.add(r.name);
			for(ResourceFile68k.Resource r:rf.ofType(ResourceFile68k.fourcc("FONT")))if((r.id&0x7f)==0&&r.name!=null&&!r.name.isEmpty()&&!names.contains(r.name))names.add(r.name);
		}catch(java.io.IOException|RuntimeException e){}
		return names;
	}

	/**
	 * Java font for a classic font number, size and style (TextFace bits: bold 1, italic 2, underline 4, outline 8, shadow 16, condense 32, extend 64):
	 * the program's own strike, else an installed suitcase's (any size: its bitmaps, its outlines or a scaled strike), else a host
	 * font - wrapped as a {@link StyledFont68k} when the face asks for a style Java has no face for. The family is the one the
	 * program's font substitution draws instead, if any ({@link #substitute}).
	 */
	static Font font(int id,int size,int face){return fontNumbered(substitute(id),size,face);}
	private static Font fontNumbered(int id,int size,int face){
		face&=0x7f;
		if(size<=0)size=12; // txSize 0 means the system font size; some licence dialogs call TextSize(0) before TEStyleNew
		if(!bitmapLookups.isEmpty()){BitmapFont68k strike=strike(id,size,face);if(strike!=null)return strike;}
		BitmapFont68k installed=InstalledFonts68k.strike(id,size,face);if(installed!=null)return installed;
		if(id>=JAPANESE_SYSTEM_FONT&&id<JAPANESE_SYSTEM_FONT+512&&!NAMES.containsKey(id)&&InstalledFonts68k.family(id)==null){ // a Japanese-script number nobody defines (a stack's リュウミンライト−ＫＬ): the Font Manager falls back to that script's system font
			id=JAPANESE_SYSTEM_FONT;installed=InstalledFonts68k.strike(id,size,face);if(installed!=null)return installed;}
		int style=((face&1)!=0?Font.BOLD:0)|((face&2)!=0?Font.ITALIC:0);
		size=Math.max(1,size);
		if(!NAMES.containsKey(id)){InstalledFonts68k.Family f=InstalledFonts68k.family(id);if(f!=null){int known=numberOf(f.name);if(NAMES.containsKey(known))id=known;}} // a suitcase's own number for a classic family: its host substitute
		String key=id+"/"+size+"/"+style+(japaneseSystem?"/J":"");
		Font f;int fixed=fixedAdvance(id,size);
		synchronized(cache){f=cache.get(key);if(f==null){f=withJapaneseFallback(new Font(familyFor(id),style,size));cache.put(key,f);if(romanScriptNumber(id))romanFonts.add(f);
			// under a Japanese system the system and application fonts (0, 1 and 3) are Osaka too: their lines are Osaka's 16
			// pixels at 12 points, not the stand-in's 19 - dialogs laid out for them (Ambrosia's shareware notices, VISE alerts)
			// lost their last line to the item's box
			if(id>=JAPANESE_SYSTEM_FONT&&id<JAPANESE_SYSTEM_FONT+512||japaneseSystem&&(id==0||id==1||id==3))japaneseFonts.add(f);}}
		// a host family without a bold face (Hiragino Mincho ProN has W3 only for Java): Java draws the regular face for BOLD,
		// so the bold is QuickDraw's own smear of the plain glyphs (Netscape's <h1> and <b> in its Japanese Mincho were plain)
		if((style&Font.BOLD)!=0&&!hasBoldFace(f)){Font plain=fontNumbered(id,size,face&~TextStyle68k.BOLD&~TextStyle68k.SYNTHETIC);
			if(plain instanceof StyledFont68k)plain=((StyledFont68k)plain).base;
			String styledKey="fakebold/"+key+"/"+face;synchronized(cache){Font b=cache.get(styledKey);if(b==null){b=new StyledFont68k(plain,face,fixed);cache.put(styledKey,b);}return b;}}
		if(fixed>0){String fixedKey="fixed/"+key+"/"+face;synchronized(cache){Font x=cache.get(fixedKey);if(x==null){x=new StyledFont68k(f,face,fixed);cache.put(fixedKey,x);}return x;}}
		return (face&TextStyle68k.SYNTHETIC)==0?f:styled(f,face);
	}
	/**
	 * The advance of Monaco's one-byte characters when no strike of it is installed and a host family stands in (Menlo): 6 at 9
	 * and 10 points, 7 at 12 as its bitmaps have it, else the outline's 0.6 em. Calendar lays its month out in Monaco 9 columns of
	 * 24 pixels (4 characters) and hit-tests clicks by them: Menlo's 5-pixel advance squeezed the dates and picked the wrong day.
	 */
	static int fixedAdvance(int id,int size){
		if(id!=4)return 0;
		return size==9||size==10?6:size==12?7:Math.max(1,(int)Math.round(0.6*size));
	}
	/**
	 * A host font for a classic font number, never a bitmap strike, paired with a Japanese family when needed: what a
	 * translation (VMTranslation) is drawn with, since a strike only has the characters of the program's own script.
	 */
	static Font hostFont(int id,int size,int face){
		id=substitute(id);
		if(size<=0)size=12;
		int style=((face&1)!=0?Font.BOLD:0)|((face&2)!=0?Font.ITALIC:0);
		String key="host/"+id+"/"+size+"/"+style+(japaneseSystem?"/J":"");
		synchronized(cache){Font f=cache.get(key);if(f==null){f=withJapaneseFallback(new Font(familyFor(id),style,Math.max(1,size)));cache.put(key,f);if(romanScriptNumber(id))romanFonts.add(f);}return f;}
	}
	/** Whether Java draws {@code font} (asked for BOLD) with a real bold face: a physical family whose bold request resolves to
	 *  the same face as its plain one has none (logical fonts such as Dialog always do). */
	private static final Map<String,Boolean> boldFaces=new HashMap<String,Boolean>();
	static boolean hasBoldFace(Font font){
		if(font instanceof BitmapFont68k||font instanceof FallbackFont68k)return true;
		String family=font.getFamily();
		if(family.equals(Font.DIALOG)||family.equals(Font.DIALOG_INPUT)||family.equals(Font.SANS_SERIF)||family.equals(Font.SERIF)||family.equals(Font.MONOSPACED))return true;
		synchronized(boldFaces){Boolean known=boldFaces.get(family);
			if(known==null){known=!font.getFontName().equals(font.deriveFont(Font.PLAIN).getFontName());boldFaces.put(family,known);}
			return known;}
	}
	/** The host font with the synthetic styles of a face (cached per font and face). */
	static Font styled(Font font,int face){
		face&=0x7f;
		if((face&TextStyle68k.SYNTHETIC)==0||font instanceof BitmapFont68k)return font;
		int fixed=0;if(font instanceof StyledFont68k){StyledFont68k s=(StyledFont68k)font;if((s.face|face)==s.face)return s;font=s.base;face|=s.face;fixed=s.fixedAdvance;} // a fixed advance (Monaco) stays with the new face
		String key="styled/"+font.getFontName()+"/"+font.getSize()+"/"+font.getStyle()+"/"+face+"/"+fixed;
		synchronized(cache){Font f=cache.get(key);if(f==null){f=new StyledFont68k(font,face,fixed);cache.put(key,f);}return f;}
	}
	/** RealFont: whether the font exists at the size without scaling (a strike of a hosted or installed family, outlines, or any host font). */
	static boolean realFont(int id,int size){
		Font f=font(id,size,0);
		return !(f instanceof BitmapFont68k)||((BitmapFont68k)f).real();
	}

	/**
	 * The system font (font 0 at 12 points) of a U.S. or a Japanese System, whichever System a running program sees: what
	 * dialog items are measured with when a program's resources are translated (ResourceTranslation).
	 */
	static Font systemFont(boolean japanese){
		BitmapFont68k strike=InstalledFonts68k.strike(japanese?JAPANESE_SYSTEM_FONT:0,12,0);
		if(strike!=null)return strike;
		String family=japanese?japaneseFamily(false):available("Helvetica Neue","Helvetica","Arial");
		return withJapaneseFallback(new Font(family==null?Font.SANS_SERIF:family,Font.PLAIN,12));
	}

	/**
	 * The first of the candidate families the host has, by its own name for it: a Japanese Windows reports its fonts
	 * under Japanese names (\u30e1\u30a4\u30ea\u30aa, \uff2d\uff33 \u30b4\u30b7\u30c3\u30af), so both the user's list and the English one are searched.
	 */
	/** A font face (not a family) by its name, as the Font constructor accepts it - macOS's Osaka-Mono is a face of the Osaka family. */
	private static String availableFace(String name){
		for(Font f:GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts())if(f.getFontName().equalsIgnoreCase(name))return f.getFontName();
		return null;
	}
	private static String available(String... candidates){
		String[] families=GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		String[] english=GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(java.util.Locale.ENGLISH);
		for(String c:candidates){
			for(String f:families)if(f.equalsIgnoreCase(c))return f;
			for(String f:english)if(f.equalsIgnoreCase(c))return f; // Font accepts the English name of a localized family
		}
		return null;
	}
	/**
	 * The host font to draw the characters a family has no glyph for with: the Japanese system font (a Mincho for a
	 * serif family), or the logical sans font when the host has no Japanese family at all. See {@link FallbackFont68k}.
	 */
	private static final Map<Boolean,String> japaneseCache=new HashMap<Boolean,String>();
	static synchronized String japaneseFamily(boolean serif){
		String family=japaneseCache.get(serif);
		if(family==null){
			if(serif)family=available("Hiragino Mincho ProN","Hiragino Mincho Pro","\u30d2\u30e9\u30ae\u30ce\u660e\u671d ProN","YuMincho","Yu Mincho","\u6e38\u660e\u671d","MS Mincho","\uff2d\uff33 \u660e\u671d","Noto Serif CJK JP","Noto Serif JP","IPAMincho");
			if(family==null)family=available("Hiragino Kaku Gothic ProN","Hiragino Sans","\u30d2\u30e9\u30ae\u30ce\u89d2\u30b4 ProN","\u30d2\u30e9\u30ae\u30ce\u89d2\u30b4\u30b7\u30c3\u30af","Osaka","Yu Gothic UI","Yu Gothic","\u6e38\u30b4\u30b7\u30c3\u30af","Meiryo","\u30e1\u30a4\u30ea\u30aa","MS Gothic","\uff2d\uff33 \u30b4\u30b7\u30c3\u30af","MS UI Gothic","Noto Sans CJK JP","Noto Sans JP","IPAGothic","TakaoGothic");
			if(family==null)family=Font.SANS_SERIF; // a logical font is composite and may still reach a Japanese face
			japaneseCache.put(serif,family);
		}
		return family;
	}
	/** Kana and kanji a host font must carry to serve Japanese text without a fallback. */
	private static boolean displaysJapanese(Font font){return font.canDisplay('\u3042')&&font.canDisplay('\u6f22')&&font.canDisplay('\uff21');}
	/** The font, paired with a Japanese family when it cannot draw Japanese itself (Windows and Linux do not fall back). */
	static Font withJapaneseFallback(Font font){
		if(font==null||font instanceof BitmapFont68k||font instanceof FallbackFont68k||displaysJapanese(font))return font;
		Font japanese=new Font(japaneseFamily(isSerif(font)),font.getStyle(),font.getSize());
		return japanese.getFamily().equalsIgnoreCase(font.getFamily())?font:new FallbackFont68k(font,japanese);
	}
	private static boolean isSerif(Font font){
		String family=font.getFamily().toLowerCase();
		return family.contains("times")||family.contains("serif")||family.contains("georgia")||family.contains("palatino")||family.contains("book")||family.contains("mincho");
	}
	private static final Map<String,String> familyCache=new HashMap<String,String>();
	static String familyFor(int id){
		String key=id+(japaneseSystem?"/J":"");
		String family=familyCache.get(key);
		if(family!=null)return family;
		if(japaneseSystem&&(id==0||id==1||id==3)){family=japaneseFamily(false);familyCache.put(key,family);return family;} // a Japanese system's font 0/1/3 is Osaka
		switch(id){
			case 4:case 22:family=available("Menlo","Monaco","Courier New","Courier");if(family==null)family=Font.MONOSPACED;break;
			case 16385:case 16387:family=japaneseFamily(true);break; // \u672c\u660e\u671d\uff0d\uff2d, \u5e73\u6210\u660e\u671d: a Japanese Mincho, not a Roman serif
			case 2:case 20:case 16:case 14:case 34:family=available("Times New Roman","Times","Georgia","Hiragino Mincho ProN","Hiragino Mincho Pro");if(family==null)family=Font.SERIF;break;
			case 23:family=available("Symbol");if(family==null)family=Font.SANS_SERIF;break;
			case 0:family=available("Helvetica Neue","Helvetica","Arial");if(family==null)family=Font.SANS_SERIF;break;
			case 16384:case 16386:case 16388:family=japaneseFamily(false);break;
			case 16436: // Osaka\u2212\u7b49\u5e45: half-width letters, full-width kanji (MacSSH draws its terminal in it; a proportional face misaligned the columns)
				family=availableFace("Osaka-Mono");if(family==null)family=available("Osaka\u2212\u7b49\u5e45","Osaka\uff0d\u7b49\u5e45","MS Gothic","\uff2d\uff33 \u30b4\u30b7\u30c3\u30af","Noto Sans Mono CJK JP","Menlo","Monaco","Courier New");
				if(family==null)family=Font.MONOSPACED;break;
			default:family=available("Helvetica Neue","Helvetica","Arial");if(family==null)family=Font.SANS_SERIF;break;
		}
		familyCache.put(key,family);
		return family;
	}

	static FontMetrics metrics(Font font){
		if(font instanceof BitmapFont68k)return ((BitmapFont68k)font).metrics();
		if(font instanceof StyledFont68k)return ((StyledFont68k)font).metrics();
		if(font instanceof FallbackFont68k)return ((FallbackFont68k)font).metrics();
		synchronized(scratch){return scratch.getFontMetrics(font);}
	}
	static int width(Font font,String text){
		if(font instanceof BitmapFont68k)return ((BitmapFont68k)font).width(text);
		if(font instanceof StyledFont68k)return ((StyledFont68k)font).width(text);
		if(font instanceof FallbackFont68k)return ((FallbackFont68k)font).width(text);
		synchronized(scratch){return scratch.getFontMetrics(font).stringWidth(text);}
	}
	static int charWidth(Font font,char c){
		if(font instanceof BitmapFont68k)return ((BitmapFont68k)font).metrics().charWidth(c);
		if(font instanceof StyledFont68k)return ((StyledFont68k)font).charWidth(c);
		if(font instanceof FallbackFont68k)return ((FallbackFont68k)font).charWidth(c);
		synchronized(scratch){return scratch.getFontMetrics(font).charWidth(c);}
	}
	/**
	 * Draws a string with a host font, with the glyphs of a strike (a 1-bit image in the current colour), or with a
	 * styled host font (its coverage image in the current colour, anti-aliased when the graphics are).
	 */
	static void drawString(Graphics2D g,Font font,String text,int x,int baseline){
		if(g instanceof SmoothGraphics){((SmoothGraphics)g).drawRun(font,text,x,baseline);return;} // the buffer and its smooth zoom plane
		if(font instanceof BitmapFont68k){
			BitmapFont68k strike=(BitmapFont68k)font;byte[] bytes=strike.encode(text);
			boolean outlines=smoothText&&strike.smoothable();BufferedImage mask=outlines?strike.coverage(bytes):strike.mask(bytes);
			g.drawImage(tinted(mask,g.getColor().getRGB()),x-(outlines?strike.coverageLeft():strike.maskLeft()),baseline-strike.maskAscent(),null);return;}
		if(font instanceof StyledFont68k){
			StyledFont68k styled=(StyledFont68k)font;boolean aa=g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)!=RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;
			g.drawImage(tinted(styled.coverage(text,aa),g.getColor().getRGB()),x-styled.maskLeft(),baseline-styled.maskAscent(),null);return;}
		if(font instanceof FallbackFont68k){((FallbackFont68k)font).draw(g,text,x,baseline);return;}
		g.setFont(font);g.drawString(text,x,baseline);
	}
	/**
	 * Draws a plain or fallback host font's run with each character where the 1x advances before it put it (the widths QuickDraw
	 * measures): on graphics with a scale - the smooth zoom's planes - the glyphs keep their places, where a whole-run drawString
	 * would space them by their scaled advances. A FallbackFont68k draws each character with whichever of its fonts has it.
	 */
	static void drawPlaced(Graphics2D g,Font font,String text,double x,double baseline){
		if(text==null||text.isEmpty())return;
		if(font instanceof FallbackFont68k){FallbackFont68k f=(FallbackFont68k)font;double px=x;
			for(int i=0;i<text.length();i++){char c=text.charAt(i);g.setFont(f.base.canDisplay(c)?f.base:f.fallback);g.drawString(String.valueOf(c),(float)px,(float)baseline);px+=f.charWidth(c);}
			return;}
		java.awt.font.GlyphVector gv=font.createGlyphVector(g.getFontRenderContext(),text);
		double px=0;
		if(gv.getNumGlyphs()!=text.length()){g.setFont(font);for(int i=0;i<text.length();i++){g.drawString(text.substring(i,i+1),(float)(x+px),(float)baseline);px+=charWidth(font,text.charAt(i));}return;}
		for(int i=0;i<text.length();i++){gv.setGlyphPosition(i,new java.awt.geom.Point2D.Double(px,0));px+=charWidth(font,text.charAt(i));}
		g.drawGlyphVector(gv,(float)x,(float)baseline);
	}
	/**
	 * A plain or fallback host font's run as anti-aliased coverage at {@code scale} times the size (TYPE_BYTE_GRAY {@code width x height}
	 * 1x pixels times the scale, the pen at {@code x}, the baseline at {@code baseline}): the smooth zoom's version of a text mask.
	 */
	static BufferedImage hiResMask(Font font,String text,int width,int height,int x,int baseline,int scale){
		BufferedImage mask=new BufferedImage(Math.max(1,width*scale),Math.max(1,height*scale),BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g=mask.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		g.scale(scale,scale);g.setColor(java.awt.Color.WHITE);drawPlaced(g,font,text,x,baseline);g.dispose();
		return mask;
	}
	/** An ARGB image of a coverage mask (binary or grey) in one colour, its coverage as alpha. */
	static BufferedImage tinted(BufferedImage mask,int rgb){
		BufferedImage glyphs=new BufferedImage(mask.getWidth(),mask.getHeight(),BufferedImage.TYPE_INT_ARGB);java.awt.image.Raster raster=mask.getRaster();
		boolean binary=mask.getType()==BufferedImage.TYPE_BYTE_BINARY;
		for(int py=0;py<mask.getHeight();py++)for(int px=0;px<mask.getWidth();px++){int c=raster.getSample(px,py,0);if(binary)c=c!=0?255:0;if(c!=0)glyphs.setRGB(px,py,(c<<24)|(rgb&0xffffff));}
		return glyphs;
	}
	/** Draws with the font the metrics were taken from (host code that measures first, then paints). */
	static void drawString(Graphics2D g,FontMetrics metrics,String text,int x,int baseline){drawString(g,metrics.getFont(),text,x,baseline);}
	/**
	 * Osaka's FontInfo as its bitmap strikes give it (9: 10,2,9,0; 10: 11,2,10,1; 12: 12,3,12,1; 14: 14,4,14,1 - the KanjiTalk
	 * suitcase), other sizes in proportion; bold/outline/extend widen by one, shadow by two, condense narrows by one. Without the
	 * suitcase the host font's metrics were Hiragino's (12 pt: 11,2,14,6 - 19-pixel lines in the 'styl' UxWriteST writes).
	 */
	static int[] osakaInfo(int size,int face){
		int ascent,descent,widMax,leading;
		switch(size){
			case 9:ascent=10;descent=2;widMax=9;leading=0;break;
			case 10:ascent=11;descent=2;widMax=10;leading=1;break;
			case 12:ascent=12;descent=3;widMax=12;leading=1;break;
			case 14:ascent=14;descent=4;widMax=14;leading=1;break;
			default:ascent=size;descent=Math.max(1,(size+2)/4);widMax=size;leading=size>=10?Math.max(1,size/12):0;
		}
		if((face&(1|8|64))!=0)widMax++;
		if((face&16)!=0)widMax+=2;
		if((face&32)!=0)widMax--;
		return new int[]{ascent,descent,widMax,leading};
	}
	/** Classic FontInfo: {ascent, descent, widMax, leading}. */
	static int[] fontInfo(Font font){
		if(font instanceof BitmapFont68k)return ((BitmapFont68k)font).fontInfo();
		Font base=font instanceof StyledFont68k?((StyledFont68k)font).base:font;
		if(japaneseFonts.contains(base))return osakaInfo(font.getSize(),font instanceof StyledFont68k?((StyledFont68k)font).face:(font.isBold()?1:0));
		FontMetrics m=metrics(font);
		return new int[]{m.getAscent(),m.getDescent(),m.getMaxAdvance()>0?Math.min(m.getMaxAdvance(),m.charWidth('W')+2):m.charWidth('W'),Math.max(0,m.getLeading())};
	}
}

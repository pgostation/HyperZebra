import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Translation of the text in a stack's pictures - card and background pictures and PICT resources - read with Tesseract
 * (PictureOcr) and translated like the rest of the stack (StackTranslation: the stack's own Languages/<tag>.po, then
 * the machine translators' cache, which "Resource Translation…" and the AI chat fill). The pictures themselves never
 * change: a translated copy is drawn instead - each paragraph read is wiped with the colour around it and its
 * translation written in its place, in the colour of its letters, as large as fits.
 * <p>
 * What Tesseract read is kept by the picture's content (a hash of its pixels) and the languages read in, in
 * <state>/Translations/Pictures, so the same picture in another stack or a copy of the stack needs no second reading,
 * and a picture painted over is read again. A picture without a reading yet is shown as it is while it is read in the
 * background (one at a time); the card is drawn again when its reading and then its translations come.
 * <p>
 * On for a translated stack unless turned off for it (Translation Settings…: "Text in pictures") or with
 * -Dhz.translate.pictures=off; nothing happens where tesseract is not installed. -Dhz.ocr.languages=jpn+eng sets the
 * languages Tesseract reads in (else the stack's language and English); -Dhz.ocr.trace=true prints each line's readings.
 */
final class PictureTranslation {
	private PictureTranslation(){}

	// ---------------------------------------------------------------------------------------------------------------
	// Settings

	/** Whether the text in the stack's pictures is translated (when its translation is on and tesseract is here). */
	static boolean enabledFor(File stack){
		if("off".equals(System.getProperty("hz.translate.pictures")))return false;
		if(stack==null||!LauncherStore.enabled)return true;
		LauncherStore.AppSettings s=LauncherStore.get().settingsFor(stack);
		return s==null||!Boolean.FALSE.equals(s.translatePictures);
	}

	/** Tesseract's languages for a stack written in {@code language}, or null: not installed, or not for that language. */
	static String ocrLanguages(String language){
		String forced=System.getProperty("hz.ocr.languages"); // -Dhz.ocr.languages=jpn_vert+eng: Tesseract's languages for every stack
		if(forced!=null)return forced;
		if(PictureOcr.executable()==null)return null;
		return PictureOcr.languagesFor(language);
	}
	private static volatile boolean looking;
	/** The same without waiting (pictures are drawn on the event thread): null until tesseract was looked for, then {@code found} runs. */
	private static String ocrLanguages(String language,Runnable found){
		if(!PictureOcr.known()&&System.getProperty("hz.ocr.languages")==null){
			if(!looking){
				looking=true;
				Thread th=new Thread(()->{PictureOcr.languages();if(found!=null)found.run();},"Tesseract");
				th.setDaemon(true);th.start();
			}
			return null;
		}
		return ocrLanguages(language);
	}

	/**
	 * What the settings show about reading pictures: the tesseract found and how many languages it reads, or how to
	 * install it: {problem, or null; what was found}. With {@code again} tesseract is looked for anew (it may have been
	 * installed meanwhile). Not on the event thread.
	 */
	static String[] status(boolean again){
		if(again)PictureOcr.reset();
		if(PictureOcr.executable()==null)return new String[]{TesseractInstall.canDownload()
			?International.t("Tesseract is not installed, so the text in pictures is not translated. Download it with Download Tesseract… on the launcher's Translation page.")
			:International.t("Tesseract is not installed, so the text in pictures is not translated. Install the tesseract-ocr packages of your system and check again."),null};
		Set<String> langs=PictureOcr.languages();
		if(langs.isEmpty())return new String[]{International.t("Tesseract {0} has no languages: add them with Languages… on the launcher's Translation page.",PictureOcr.version()),null};
		StringBuilder some=new StringBuilder();
		for(String code:new String[]{"jpn","eng","chi_sim","chi_tra","kor","deu","fra","spa","ita","por"})if(langs.contains(code))some.append(some.length()>0?", ":"").append(code);
		return new String[]{null,International.t("Tesseract {0} reads the text in pictures: {1} languages ({2}…).",PictureOcr.version(),String.valueOf(langs.size()),some.toString())};
	}

	// ---------------------------------------------------------------------------------------------------------------
	// What was read, by picture

	/** The folder of the readings: <state>/Translations/Pictures. */
	static File folder(){return new File(VMTranslation.cacheDirectory(),"Pictures");}

	private static final Map<String,List<PictureOcr.Block>> readings=Collections.synchronizedMap(new HashMap<String,List<PictureOcr.Block>>());

	private static File file(String hash,String langs){return new File(folder(),hash+"."+langs.replace('+','_')+".txt");}

	/** What was read in a picture (by its hash) in these languages: the paragraphs, empty when it has no text; null when not read yet. */
	static List<PictureOcr.Block> reading(String hash,String langs){
		String key=hash+"/"+langs;
		List<PictureOcr.Block> r=readings.get(key);
		if(r!=null)return r;
		File f=file(hash,langs);
		if(!f.isFile())return null;
		try{
			r=parse(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
		}catch(IOException|RuntimeException e){
			return null;
		}
		readings.put(key,r);
		return r;
	}

	/** The version of the reader (PictureOcr): a picture read by an older one is read again (Ultimate Spell's coloured labels, Oct 26 2026). */
	static final int READER=2;

	/** Keeps what was read in a picture. File: a first line "HZOCR <reader version>", then a paragraph a line: x y width height, a tab, its lines joined with tabs. */
	static void store(String hash,String langs,List<PictureOcr.Block> blocks){
		readings.put(hash+"/"+langs,blocks);
		StringBuilder b=new StringBuilder("HZOCR ").append(READER).append(' ').append(langs).append(" tesseract ").append(PictureOcr.version()).append('\n');
		for(PictureOcr.Block k:blocks){
			b.append(k.box.x).append(' ').append(k.box.y).append(' ').append(k.box.width).append(' ').append(k.box.height);
			for(String line:k.lines)b.append('\t').append(line.replace('\t',' ').replace('\n',' ').replace('\r',' '));
			b.append('\n');
		}
		try{
			File f=file(hash,langs);
			f.getParentFile().mkdirs();
			File tmp=new File(f.getPath()+".tmp");
			java.nio.file.Files.write(tmp.toPath(),b.toString().getBytes(StandardCharsets.UTF_8));
			java.nio.file.Files.move(tmp.toPath(),f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}catch(IOException e){
			// kept in memory for this session
		}
	}

	static List<PictureOcr.Block> parse(String text){
		List<PictureOcr.Block> out=new ArrayList<PictureOcr.Block>();
		String[] rows=text.split("\n");
		if(rows.length==0||!rows[0].startsWith("HZOCR "))throw new IllegalArgumentException("not a reading");
		String[] head=rows[0].split(" ");
		if(head.length<2||!head[1].matches("\\d+")||Integer.parseInt(head[1])<READER)throw new IllegalArgumentException("read by an older reader");
		for(int i=1;i<rows.length;i++){
			String[] cols=rows[i].split("\t");
			if(cols.length<2)continue;
			String[] n=cols[0].trim().split(" ");
			if(n.length<4)continue;
			Rectangle box=new Rectangle(Integer.parseInt(n[0]),Integer.parseInt(n[1]),Integer.parseInt(n[2]),Integer.parseInt(n[3]));
			out.add(new PictureOcr.Block(box,new ArrayList<String>(Arrays.asList(cols).subList(1,cols.length))));
		}
		return out;
	}

	/** Forgets every reading (they are made again when wanted). */
	static void clear(){
		readings.clear();
		File[] fs=folder().listFiles();
		if(fs!=null)for(File f:fs)if(f.getName().endsWith(".txt"))f.delete();
		synchronized(memo){memo.clear();}
	}

	/** A picture's content: a hash of its size and its pixels (all fully transparent pixels alike). */
	static String hash(BufferedImage img){
		int w=img.getWidth(),h=img.getHeight();
		java.security.MessageDigest md;
		try{md=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
		int[] row=new int[w];
		byte[] bytes=new byte[w*4];
		md.update((w+"x"+h).getBytes(StandardCharsets.US_ASCII));
		for(int y=0;y<h;y++){
			img.getRGB(0,y,w,1,row,0,w);
			for(int x=0;x<w;x++){
				int p=row[x];
				if((p>>>24)==0)p=0;
				bytes[4*x]=(byte)(p>>>24);bytes[4*x+1]=(byte)(p>>16);bytes[4*x+2]=(byte)(p>>8);bytes[4*x+3]=(byte)p;
			}
			md.update(bytes);
		}
		byte[] d=md.digest();
		StringBuilder b=new StringBuilder();
		for(int i=0;i<12;i++)b.append(String.format("%02x",d[i]&255));
		return b.toString();
	}

	/** A quick look at some of a picture's pixels: a picture drawn into (a script, the paint tools) looks different. */
	private static long sample(BufferedImage img){
		int w=img.getWidth(),h=img.getHeight();
		long s=w*31L+h;
		int step=Math.max(1,(w*h)/4099);
		for(long i=0;i<(long)w*h;i+=step){
			int x=(int)(i%w),y=(int)(i/w);
			s=s*1000003L+img.getRGB(x,y);
		}
		return s;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Reading in the background

	private static final ExecutorService reader=Executors.newSingleThreadExecutor(r->{
		Thread t=new Thread(r,"Picture reading");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
	});
	private static final Set<String> queued=Collections.synchronizedSet(new HashSet<String>());

	/** Reads a picture in the background (once), then calls {@code done}. */
	private static void readLater(BufferedImage img,String hash,String langs,Runnable done){
		if(!queued.add(hash+"/"+langs))return;
		BufferedImage copy=copy(img); // the picture may be drawn into meanwhile
		reader.execute(()->{
			try{
				if(reading(hash,langs)==null)store(hash,langs,PictureOcr.recognize(copy,langs));
				if(done!=null)done.run();
			}catch(IOException|RuntimeException e){
				System.err.println("Picture reading: "+e.getMessage());
			}
		});
	}

	private static BufferedImage copy(BufferedImage img){
		BufferedImage c=new BufferedImage(img.getWidth(),img.getHeight(),BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=c.createGraphics();
		g.setComposite(java.awt.AlphaComposite.Src);
		g.drawImage(img,0,0,null);g.dispose();
		return c;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// What a picture shows

	/** The last translated picture for each picture shown: {sample, hash, langs, translations, translated copy or null (the picture itself: never the key, it would keep it)}. */
	private static final Map<BufferedImage,Object[]> memo=new WeakHashMap<BufferedImage,Object[]>();

	/**
	 * What a picture of the stack shows: a translated copy of it when its text was read and translated, else the
	 * picture itself (reading it and translating its text start then, and the card is drawn again when they come).
	 * The copy is a new image: callers may not draw into it (nor into the picture).
	 */
	static BufferedImage shown(OStack st,BufferedImage img){
		if(img==null||st==null||AuthTool.tool!=null)return img;
		StackTranslation.State state=StackTranslation.state(st);
		if(state==null||state.file==null)return img;
		Map<String,String> own=StackTranslation.ownTranslations(st);
		VMTranslation.Translator t=state.translator!=null&&state.translator.active()?state.translator:null;
		if(t==null&&own==null||!state.pictures)return img;
		String langs=ocrLanguages(t!=null?t.from():StackTranslation.languageGuess(st),state::arrived);
		if(langs==null)return img;
		long sample=sample(img);
		Object[] m;
		synchronized(memo){m=memo.get(img);}
		if(m==null||(Long)m[0]!=sample||!langs.equals(m[2])){
			m=new Object[]{sample,m!=null&&(Long)m[0]==sample?(String)m[1]:hash(img),langs,null,null};
			synchronized(memo){memo.put(img,m);}
		}
		String hash=(String)m[1];
		List<PictureOcr.Block> blocks=reading(hash,langs);
		if(blocks==null){
			readLater(img,hash,langs,state::arrived);
			return img;
		}
		List<String> translations=new ArrayList<String>();
		boolean any=false;
		String from=t!=null?t.from():StackTranslation.languageGuess(st);
		for(PictureOcr.Block b:blocks){
			String text=state.vocabulary().snap(b.text()),tr=own==null?null:StackLanguages.lookup(own,text);
			if(tr==null&&t!=null&&worth(text,from))tr=t.lookup(kindOf(b),text);
			if(changes(text,tr))any=true;else tr=null;
			translations.add(tr);
		}
		if(translations.equals(m[3]))return m[4]==null?img:(BufferedImage)m[4];
		BufferedImage result=any?render(img,blocks,translations):null;
		synchronized(memo){memo.put(img,new Object[]{sample,hash,langs,translations,result});}
		return result==null?img:result;
	}

	/** A card's or background's picture as shown (the picture with its paint layers, translated). */
	static BufferedImage shown(OCardBase base,BufferedImage composite){
		return base==null?composite:shown(base.stack,composite);
	}

	/**
	 * Whether a paragraph read in a picture is worth translating for a stack or program written in {@code from}: not Latin
	 * text in a Japanese, Chinese or Korean one (English words there are meant to be read as they are; a translation only
	 * redraws a styled title in a plain font: "ABORT" as "Abort").
	 */
	static boolean worth(String text,String from){
		if(!VMTranslation.Translator.cjk(from))return true;
		for(int i=0;i<text.length();i++)if(PictureOcr.wide(text.charAt(i)))return true;
		return false;
	}
	/** A translation that changes what is shown (not the text again, nor in other capitals). */
	static boolean changes(String text,String tr){
		return tr!=null&&!tr.trim().isEmpty()&&!tr.trim().equalsIgnoreCase(text.trim());
	}

	/**
	 * The texts a stack or program has as text (field lines, button names, resource strings, a picture's text opcodes):
	 * a Japanese or Chinese line read in a picture one character off one of them is that text. Tesseract misreads small
	 * bitmap kana and kanji by a stroke (すばやさ read as すばやき, 運の良さ as 連の良さ) and the translation of the
	 * misreading is nonsense, while a game's status labels are usually its strings too.
	 */
	static final class Vocabulary {
		private final Set<String> all=new HashSet<String>();
		private final Map<Integer,List<String>> byLength=new HashMap<Integer,List<String>>();
		static Vocabulary of(List<VMTranslation.ProgramText> texts){
			Vocabulary v=new Vocabulary();
			for(VMTranslation.ProgramText g:texts)for(String t:g.lines)v.add(t);
			return v;
		}
		void add(String text){
			if(text==null)return;
			for(String line:text.split("\r\n|\r|\n")){
				String l=line.trim();
				if(l.length()<2||l.length()>40||!wide(l)||!all.add(l))continue;
				byLength.computeIfAbsent(l.replace(" ","").length(),k->new ArrayList<String>()).add(l);
			}
		}
		/**
		 * The text the reading is a misreading of (as long, one character other, two in a long line; only one such text),
		 * else the reading. Never a character more or less: エネミーユニット is not エネミーユニット数.
		 */
		String snap(String reading){
			if(!wide(reading)||all.contains(reading))return reading;
			String core=reading.replace(" ","");
			int len=core.length();
			if(len<3)return reading;
			int most=len<=8?1:2;
			String found=null;int best=most+1;boolean tie=false;
			List<String> same=byLength.get(len);
			if(same!=null)for(String v:same){
				String c=v.replace(" ","");
				int d=0;
				for(int i=0;i<len&&d<=most;i++)if(c.charAt(i)!=core.charAt(i))d++;
				if(d<best){best=d;found=v;tie=false;}else if(d==best&&!v.equals(found))tie=true;
			}
			return found!=null&&!tie?found:reading;
		}
		private static boolean wide(String s){for(int i=0;i<s.length();i++)if(PictureOcr.wide(s.charAt(i)))return true;return false;}
	}

	/** One line short: a label or a button's word (dialog words); else a paragraph of text. */
	static VMTranslation.Kind kindOf(PictureOcr.Block b){
		return b.lines.size()==1&&b.text().length()<=30?VMTranslation.Kind.UI:VMTranslation.Kind.TEXT;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Drawing the translation

	/** The picture with each paragraph that has a translation (null: none) wiped and the translation written in its place. */
	static BufferedImage render(BufferedImage img,List<PictureOcr.Block> blocks,List<String> translations){return render(img,blocks,translations,true,null);}
	/**
	 * @param smooth the translation's letters smoothed (else black and white pixels only: a 1-bit picture of a program)
	 * @param wiped given the places wiped and written into, when not null
	 */
	static BufferedImage render(BufferedImage img,List<PictureOcr.Block> blocks,List<String> translations,boolean smooth,List<Rectangle> wiped){
		int w=img.getWidth(),h=img.getHeight();
		BufferedImage out=copy(img);
		Graphics2D g=out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,smooth?RenderingHints.VALUE_TEXT_ANTIALIAS_ON:RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		// labels of one size are written in one size: their boxes differ by the letters in them (kana are lower than kanji)
		List<Integer> heights=new ArrayList<Integer>();
		for(PictureOcr.Block b:blocks)if(b.lines.size()==1)heights.add(b.box.height);
		Collections.sort(heights);
		int typical=heights.isEmpty()?0:heights.get(heights.size()/2);
		for(int i=0;i<blocks.size();i++){
			String tr=translations.get(i);
			if(tr==null)continue;
			PictureOcr.Block b=blocks.get(i);
			Rectangle r=b.box.intersection(new Rectangle(0,0,w,h));
			if(r.isEmpty())continue;
			boolean usual=b.lines.size()==1&&Math.abs(r.height-typical)*4<=typical;
			float start=startSize(usual?new Rectangle(r.x,r.y,r.width,typical):r,b.lines.size());
			int bg=background(img,r),fg=foreground(img,r,bg);
			List<Rectangle> others=new ArrayList<Rectangle>();
			for(int k=0;k<blocks.size();k++)if(k!=i){Rectangle o=new Rectangle(blocks.get(k).box);o.grow(2,2);others.add(o);}
			Rectangle area=fit(img,r,bg,start,tr,others);
			Rectangle wipe=area.union(r);wipe.grow(1,1);wipe=wipe.intersection(new Rectangle(0,0,w,h));
			int place=wiped==null?-1:wiped.size();
			if(wiped!=null)wiped.add(wipe);
			g.setComposite(java.awt.AlphaComposite.Src);
			g.setColor(new Color(bg,true));
			g.fillRect(wipe.x,wipe.y,wipe.width,wipe.height);
			g.setComposite(java.awt.AlphaComposite.SrcOver);
			g.setColor(new Color(fg,true));
			boolean column=false; // a label in a column of labels starting where it starts: written from there, not centred
			for(PictureOcr.Block o:blocks)if(o!=b&&Math.abs(o.box.x-r.x)<=2&&Math.abs(o.box.y-r.y)<=r.height*3)column=true;
			Rectangle drawn=draw(g,tr,area,start,r,column);
			if(place>=0)wiped.set(place,wipe.union(drawn).intersection(new Rectangle(0,0,w,h))); // descenders below the box too
		}
		g.dispose();
		return out;
	}

	/** The colour most pixels have just outside a box: what the text is written on. */
	static int background(BufferedImage img,Rectangle r){
		Map<Integer,Integer> count=new HashMap<Integer,Integer>();
		int w=img.getWidth(),h=img.getHeight();
		for(int d=1;d<=2;d++){
			for(int x=r.x-d;x<r.x+r.width+d;x++){add(count,img,x,r.y-d,w,h);add(count,img,x,r.y+r.height-1+d,w,h);}
			for(int y=r.y-d;y<r.y+r.height+d;y++){add(count,img,r.x-d,y,w,h);add(count,img,r.x+r.width-1+d,y,w,h);}
		}
		return most(count,0xFFFFFFFF);
	}
	/** The colour of the letters: the commonest inside the box that is far from the background. */
	static int foreground(BufferedImage img,Rectangle r,int bg){
		Map<Integer,Integer> count=new HashMap<Integer,Integer>();
		for(int y=r.y;y<r.y+r.height;y++)for(int x=r.x;x<r.x+r.width;x++){
			int p=img.getRGB(x,y);
			if(distance(p,bg)>=96)count.merge(p,1,Integer::sum);
		}
		int fallback=luminance(bg)<128&&(bg>>>24)>=128?0xFFFFFFFF:0xFF000000;
		int fg=most(count,fallback);
		return (fg>>>24)<128?fallback:fg|0xFF000000;
	}
	private static void add(Map<Integer,Integer> count,BufferedImage img,int x,int y,int w,int h){
		if(x<0||y<0||x>=w||y>=h)return;
		int p=img.getRGB(x,y);
		if((p>>>24)==0)p=0;
		count.merge(p,1,Integer::sum);
	}
	private static int most(Map<Integer,Integer> count,int fallback){
		int best=fallback,n=0;
		for(Map.Entry<Integer,Integer> e:count.entrySet())if(e.getValue()>n){n=e.getValue();best=e.getKey();}
		return best;
	}
	private static int luminance(int p){return ((p>>16&255)*30+(p>>8&255)*59+(p&255)*11)/100;}
	private static int distance(int a,int b){
		if((a>>>24)<128||(b>>>24)<128)return (a>>>24)<128&&(b>>>24)<128?0:255;
		return Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));
	}

	/** The font a translation is written in: the system's sans serif (it has every script), {@code size} pixels. */
	static Font font(float size){return new Font(Font.SANS_SERIF,Font.PLAIN,1).deriveFont(size);}

	/** The original's letter size: a line's height (the marks of its letters, ascenders to descenders) is about the font's size. */
	private static float startSize(Rectangle r,int lines){
		float lineHeight=lines<=1?r.height:r.height/(float)lines;
		return Math.max(8f,lines<=1?lineHeight*1.1f:lineHeight*0.9f);
	}
	private static float minSize(String text,float start){
		boolean cjk=false;
		for(int i=0;i<text.length();i++)if(PictureOcr.wide(text.charAt(i))){cjk=true;break;}
		return Math.min(start,Math.max(cjk?10f:7f,start*0.55f));
	}

	/**
	 * Where a translation goes: the paragraph's box, or - when the translation does not fit in it at the original's size -
	 * the box widened to the right and then lengthened downwards into the plain background beside it (up to twice), never
	 * into another paragraph's box.
	 */
	static Rectangle fit(BufferedImage img,Rectangle r,int bg,float start,String tr,List<Rectangle> others){
		if(fits(tr,r,start))return r;
		Rectangle grown=new Rectangle(r);
		int w=img.getWidth(),h=img.getHeight();
		while(grown.width<r.width*2&&grown.x+grown.width<w&&plainColumn(img,grown.x+grown.width+1,grown.y-1,grown.y+grown.height+1,bg)&&clear(grown.x,grown.y,grown.width+1,grown.height,others))grown.width++;
		if(fits(tr,grown,start))return grown;
		while(grown.height<r.height*2+start&&grown.y+grown.height<h&&plainRow(img,grown.y+grown.height+1,grown.x-1,grown.x+grown.width+1,bg)&&clear(grown.x,grown.y,grown.width,grown.height+1,others))grown.height++;
		return grown;
	}
	/** A place that runs into no other paragraph (their translations go there). */
	private static boolean clear(int x,int y,int w,int h,List<Rectangle> others){
		for(Rectangle o:others)if(o.intersects(x,y,w,h))return false;
		return true;
	}
	private static boolean plainColumn(BufferedImage img,int x,int y0,int y1,int bg){
		if(x>=img.getWidth())return false;
		for(int y=Math.max(0,y0);y<=Math.min(img.getHeight()-1,y1);y++)if(distance(img.getRGB(x,y),bg)>=96)return false;
		return true;
	}
	private static boolean plainRow(BufferedImage img,int y,int x0,int x1,int bg){
		if(y>=img.getHeight())return false;
		for(int x=Math.max(0,x0);x<=Math.min(img.getWidth()-1,x1);x++)if(distance(img.getRGB(x,y),bg)>=96)return false;
		return true;
	}
	private static boolean fits(String tr,Rectangle area,float size){
		Font f=font(size);
		List<String> lines=VMTranslation.wrap(tr,f,area.width);
		FontMetrics fm=metrics(f);
		if(lines.size()*fm.getHeight()>area.height+fm.getDescent()+1)return false;
		for(String l:lines)if(fm.stringWidth(l)>area.width+1)return false;
		return true;
	}
	private static final BufferedImage scratch=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
	private static FontMetrics metrics(Font f){
		Graphics2D g=scratch.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		FontMetrics fm=g.getFontMetrics(f);g.dispose();return fm;
	}

	/**
	 * Writes a translation into its area: the original's letter size, smaller until it fits (to about half), wrapped,
	 * centred up and down; one line is centred across the original's box when that holds it (not {@code left}: a label
	 * of a column), else starts at its left.
	 * Lines that would still run out of the area are made fewer and squeezed across. Returns where it drew (its descenders
	 * may hang below the area).
	 */
	private static Rectangle draw(Graphics2D g,String tr,Rectangle area,float start,Rectangle original,boolean left){
		float size=start,min=minSize(tr,size);
		while(size>min&&!fits(tr,area,size))size=Math.max(min,size-0.5f);
		Font f=font(size);
		g.setFont(f);
		FontMetrics fm=g.getFontMetrics();
		List<String> lines=VMTranslation.wrap(tr,f,area.width);
		int room=Math.max(1,(area.height+fm.getDescent()+1)/fm.getHeight());
		if(lines.size()>room){ // no room below (the picture's edge, a drawing): fewer, longer lines, squeezed across
			int lo=area.width,hi=Math.max(area.width,fm.stringWidth(tr));
			while(hi<100000&&VMTranslation.wrap(tr,f,hi).size()>room)hi*=2; // (widths are measured in fractions)
			while(lo<hi){int mid=(lo+hi)>>>1;if(VMTranslation.wrap(tr,f,mid).size()<=room)hi=mid;else lo=mid+1;}
			lines=VMTranslation.wrap(tr,f,lo);
		}
		int total=lines.size()*fm.getHeight();
		int y=area.y+Math.max(0,(area.height-total)/2)+fm.getAscent();
		Rectangle drawn=new Rectangle(area);
		for(String line:lines){
			int lw=fm.stringWidth(line);
			int x=area.x;
			if(lines.size()==1&&lw<=original.width&&!left)x=original.x+(original.width-lw)/2;
			drawn.add(new Rectangle(x,y-fm.getAscent(),Math.min(lw,area.width)+1,fm.getAscent()+fm.getDescent()+1));
			if(lw>area.width){ // a word too long for the line: squeezed across
				java.awt.geom.AffineTransform at=g.getTransform();
				g.translate(x,y);g.scale(area.width/(double)lw,1);
				g.drawString(line,0,0);
				g.setTransform(at);
			}else g.drawString(line,x,y);
			y+=fm.getHeight();
		}
		return drawn;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Translating ahead

	/** Progress of reading a stack's pictures: how many are done of how many. */
	interface Progress {void pictures(int done,int total);}

	/**
	 * Reads the text in all of a stack's pictures (card and background pictures, its own PICT resources), those not
	 * read before, and returns the paragraphs to translate: one-line labels as dialog words, longer text as text.
	 * Empty when its pictures are not translated (turned off, no tesseract, no languages for {@code from}).
	 */
	static List<VMTranslation.ProgramText> aheadTexts(OStack st,File file,String from,Progress progress){
		List<VMTranslation.ProgramText> out=new ArrayList<VMTranslation.ProgramText>();
		String langs=ocrLanguages(from);
		if(st==null||langs==null||!enabledFor(file))return out;
		List<java.util.function.Supplier<BufferedImage>> pictures=pictures(st);
		LinkedHashSet<String> ui=new LinkedHashSet<String>(),text=new LinkedHashSet<String>();
		Set<String> seen=new HashSet<String>();
		Vocabulary vocabulary=Vocabulary.of(StackTranslation.stackTexts(st));
		for(int i=0;i<pictures.size();i++){
			if(progress!=null)progress.pictures(i,pictures.size());
			BufferedImage img;
			try{img=pictures.get(i).get();}catch(RuntimeException e){img=null;}
			if(img==null||img.getWidth()<8||img.getHeight()<8)continue;
			String hash=hash(img);
			if(!seen.add(hash))continue;
			List<PictureOcr.Block> blocks=reading(hash,langs);
			if(blocks==null){
				try{blocks=PictureOcr.recognize(img,langs);}catch(IOException e){continue;}
				store(hash,langs,blocks);
			}
			for(PictureOcr.Block b:blocks)if(worth(b.text(),from))(kindOf(b)==VMTranslation.Kind.UI?ui:text).add(vocabulary.snap(b.text()));
		}
		if(progress!=null)progress.pictures(pictures.size(),pictures.size());
		if(!ui.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.UI,new ArrayList<String>(ui),false));
		if(!text.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.TEXT,new ArrayList<String>(text),false));
		return out;
	}

	/** A stack's pictures, each read when wanted: its backgrounds' and cards' pictures, then its own PICT resources. */
	static List<java.util.function.Supplier<BufferedImage>> pictures(OStack st){
		List<java.util.function.Supplier<BufferedImage>> out=new ArrayList<java.util.function.Supplier<BufferedImage>>();
		List<OCardBase> bases=new ArrayList<OCardBase>();
		try{bases.addAll(new ArrayList<OBackground>(st.bgCacheList));bases.addAll(new ArrayList<OCard>(st.cdCacheList));}catch(RuntimeException ignored){}
		for(OCardBase base:bases)out.add(()->picture(base));
		Rsrc rsrc=st.rsrc;
		if(rsrc!=null){
			int n=rsrc.getRsrcCount("PICT");
			for(int i=0;i<n;i++){
				Rsrc.rsrcClass r=rsrc.getRsrcByIndex("PICT",i);
				if(r==null)continue;
				int id=r.id;
				out.add(()->rsrc.getImage(id,"PICT"));
			}
		}
		return out;
	}

	/** A card's or background's picture: the one shown (with its paint layers) when it is loaded, else read from the stack's folder. */
	static BufferedImage picture(OCardBase base){
		if(base.pict!=null)return base.composite();
		if(base.bitmapName==null||base.stack==null||base.stack.file==null)return null;
		File f=new File(base.stack.file.getParent(),base.bitmapName);
		if(!f.isFile())return null;
		BufferedImage img=PictureFile.loadPbmCached(f.getPath());
		if(img!=null)return img;
		try{return PictureCache.read(f,"imageio",StackProtection::readImage);}catch(IOException e){return null;}
	}
}

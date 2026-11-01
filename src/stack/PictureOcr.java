import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the text in a picture with Tesseract (PictureTranslation): the tesseract program installed on the machine
 * (Homebrew/MacPorts on macOS, the UB Mannheim installer on Windows, the distribution's package on Linux), run as a
 * separate process on a PNG of the picture - nothing leaves the machine. Which one: -Dhz.tesseract=path, then a
 * "tesseract" folder beside HyperZebra, then the usual places and the PATH (the newest version found). Its languages are
 * its own traineddata files; a "tessdata" folder in HyperZebra's state folder adds more (Tesseract 4 and later).
 * <p>
 * Old Mac pictures have small bitmap text (9-12 pixels), black or coloured, on white, colour panels or drawings: the
 * picture becomes ink on white by each pixel's difference from the background around it (flatten), its lines are found
 * among letter-sized marks (lineCandidates), each line is enlarged three ways and read alone, and what is read comes
 * back as paragraphs (Block) in the picture's own pixels.
 */
final class PictureOcr {
	private PictureOcr(){}

	/** Text Tesseract found: a paragraph (its lines' box) and its lines as read. */
	static final class Block {
		final Rectangle box;
		/** The lines read, top to bottom. */
		final List<String> lines;
		Block(Rectangle box,List<String> lines){this.box=box;this.lines=lines;}
		/** The paragraph as one text to translate: lines joined with a space (none between CJK characters, a hyphen at a line's end joins the word). */
		String text(){
			StringBuilder b=new StringBuilder();
			for(String l:lines){
				if(b.length()>0){
					char last=b.charAt(b.length()-1),first=l.isEmpty()?' ':l.charAt(0);
					if(last=='-'&&b.length()>1&&Character.isLetter(b.charAt(b.length()-2))&&Character.isLowerCase(first))b.setLength(b.length()-1);
					else if(!(wide(last)&&wide(first)))b.append(' ');
				}
				b.append(l);
			}
			return b.toString();
		}
		@Override public String toString(){return box.x+","+box.y+","+box.width+","+box.height+" "+lines;}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The tesseract program

	private static File executable;
	private static String version;
	private static Set<String> languages;
	private static boolean searched;

	/** Why pictures cannot be read here (no tesseract found), or null. */
	static String unavailableReason(){
		return executable()==null?"Tesseract is not installed.":null;
	}

	/** The tesseract program (the newest one found), or null. */
	static synchronized File executable(){
		if(searched)return executable;
		searched=true;
		String forced=System.getProperty("hz.tesseract");
		List<File> candidates=new ArrayList<File>();
		if(forced!=null){if(forced.equals("none"))return null;candidates.add(new File(forced));}
		boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");
		String exe=windows?"tesseract.exe":"tesseract";
		File dist=AppPaths.distributionDirectory();
		if(dist!=null){candidates.add(new File(new File(dist,"tesseract"),exe));candidates.add(new File(new File(new File(dist,"tesseract"),"bin"),exe));}
		if(windows){
			for(String env:new String[]{"ProgramFiles","ProgramFiles(x86)","ProgramW6432"}){
				String d=System.getenv(env);
				if(d!=null)candidates.add(new File(new File(d,"Tesseract-OCR"),exe));
			}
			String local=System.getenv("LOCALAPPDATA");
			if(local!=null){candidates.add(new File(local,"Programs\\Tesseract-OCR\\"+exe));candidates.add(new File(local,"Tesseract-OCR\\"+exe));}
		}else{
			for(String d:new String[]{"/opt/homebrew/bin","/usr/local/bin","/opt/local/bin","/usr/bin","/snap/bin"})candidates.add(new File(d,exe));
		}
		String path=System.getenv("PATH");
		if(path!=null)for(String d:path.split(File.pathSeparator))if(!d.isEmpty())candidates.add(new File(d,exe));
		Set<String> seen=new HashSet<String>();
		int[] best=null;
		for(File f:candidates){
			if(!f.isFile()||!f.canExecute())continue;
			String key;
			try{key=f.getCanonicalPath();}catch(IOException e){key=f.getAbsolutePath();}
			if(!seen.add(key))continue;
			String v=versionOf(f);
			if(v==null)continue;
			int[] n=numbers(v);
			if(forced!=null&&f.equals(new File(forced))){executable=f;version=v;break;}
			if(best==null||compare(n,best)>0){best=n;executable=f;version=v;}
		}
		return executable;
	}

	/** The version of the tesseract in use ("5.5.0"), or null. */
	static synchronized String version(){executable();return version;}

	/** Tesseract 4 and later: --psm, --tessdata-dir, the LSTM engine. */
	static boolean modern(){String v=version();return v!=null&&numbers(v)[0]>=4;}

	private static String versionOf(File exe){
		String out=run(Arrays.asList(exe.getPath(),"--version"),10);
		if(out==null)return null;
		Matcher m=Pattern.compile("tesseract\\s+v?(\\d+(?:\\.\\d+)*)").matcher(out);
		return m.find()?m.group(1):null;
	}
	private static int[] numbers(String v){
		String[] p=v.split("\\.");
		int[] n=new int[3];
		for(int i=0;i<3&&i<p.length;i++)try{n[i]=Integer.parseInt(p[i]);}catch(NumberFormatException ignored){}
		return n;
	}
	private static int compare(int[] a,int[] b){for(int i=0;i<3;i++)if(a[i]!=b[i])return Integer.compare(a[i],b[i]);return 0;}

	/** HyperZebra's own folder of extra traineddata files (used when it has any, Tesseract 4 and later). */
	static File extraTessdata(){return new File(AppPaths.stateDirectory(),"tessdata");}
	private static boolean useExtra(){
		File[] f=extraTessdata().listFiles((d,n)->n.endsWith(".traineddata"));
		return f!=null&&f.length>0&&modern();
	}

	/** The languages tesseract can read ("eng", "jpn", …). */
	static synchronized Set<String> languages(){
		if(languages!=null)return languages;
		Set<String> out=new TreeSet<String>();
		File exe=executable();
		if(exe!=null){
			List<String> cmd=new ArrayList<String>(Arrays.asList(exe.getPath()));
			if(useExtra()){cmd.add("--tessdata-dir");cmd.add(extraTessdata().getPath());}
			cmd.add("--list-langs");
			String list=run(cmd,15);
			if(list!=null){
				boolean after=false;
				for(String line:list.split("\\R")){
					line=line.trim();
					if(line.toLowerCase(Locale.ROOT).startsWith("list of available languages")){after=true;continue;}
					if(after&&line.matches("[A-Za-z_]+(?:/[A-Za-z_]+)?")&&!line.equals("osd")&&!line.equals("equ"))out.add(line);
				}
			}
		}
		languages=Collections.unmodifiableSet(out);
		return languages;
	}

	/** Whether tesseract and its languages were looked for already (asking does not wait). */
	static synchronized boolean known(){return searched&&(executable==null||languages!=null);}

	/** Forgets what was found (the settings page looks again after the user installed tesseract or languages). */
	static synchronized void reset(){searched=false;executable=null;version=null;languages=null;}

	/** Tesseract's language for a language code of the translation ("ja" → "jpn"), or null when it has none. */
	static String tesseractLanguage(String code){
		if(code==null)return null;
		String c=code.toLowerCase(Locale.ROOT);
		if(c.startsWith("zh"))return c.contains("hant")||c.endsWith("tw")||c.endsWith("hk")?"chi_tra":"chi_sim";
		String base=c.split("[-_]")[0];
		switch(base){
			case "ja":return "jpn";case "en":return "eng";case "de":return "deu";case "fr":return "fra";case "es":return "spa";
			case "it":return "ita";case "pt":return "por";case "nl":return "nld";case "sv":return "swe";case "da":return "dan";
			case "no":case "nb":return "nor";case "fi":return "fin";case "ko":return "kor";case "ru":return "rus";case "pl":return "pol";
			case "cs":return "ces";case "hu":return "hun";case "tr":return "tur";case "el":return "ell";case "uk":return "ukr";
			case "ar":return "ara";case "he":return "heb";case "th":return "tha";case "vi":return "vie";case "id":return "ind";
			case "ca":return "cat";case "ro":return "ron";case "hr":return "hrv";case "sk":return "slk";case "sl":return "slv";
			default:return null;
		}
	}

	/**
	 * The languages to read a picture in for text written in {@code language}: its own and English (Japanese pictures have
	 * Latin words too), as many as are installed; null when its own is not installed.
	 */
	static String languagesFor(String language){
		String own=tesseractLanguage(language);
		Set<String> have=languages();
		if(own==null||!have.contains(own))return null;
		return own.equals("eng")||!have.contains("eng")?own:own+"+eng";
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Reading a picture

	/** Lines read in one picture at most (a busy drawing has many letter-sized marks). */
	static final int MAX_LINES=150;
	/** Each line is read three times, enlarged three ways - {scale, Scale2x first (1) or not (0)} - and the surest reading kept: none is always better. */
	private static final int[][] WAYS={{2,0},{3,0},{4,1}};
	/** The white border round a line image (Tesseract reads letters at the very edge badly). */
	private static final int BORDER=10;

	/**
	 * The paragraphs of text Tesseract reads in a picture, in the picture's pixels; empty when it reads none.
	 * Tesseract's own page layout loses labels in boxes and reads letters into drawings, so the lines are found here
	 * ({@link #lineCandidates}: letter-sized marks side by side) and Tesseract reads each one alone, all in one run.
	 * @param langs Tesseract's languages ("jpn+eng")
	 */
	static List<Block> recognize(BufferedImage picture,String langs) throws IOException{
		File exe=executable();
		if(exe==null)throw new IOException("Tesseract is not installed.");
		BufferedImage flat=flatten(picture);
		int w=flat.getWidth(),h=flat.getHeight();
		boolean[] ink=ink(flat);
		List<Rectangle> candidates=lineCandidates(ink,w,h);
		if(candidates.isEmpty())return new ArrayList<Block>();
		File dir=TempFiles.createDirectory("ocr");
		try{
			StringBuilder list=new StringBuilder();
			List<Rectangle> areas=new ArrayList<Rectangle>(); // each page's part of the picture
			List<int[]> columns=new ArrayList<int[]>(); // and the picture's column of each of its columns (gaps closed up)
			for(int[] way:WAYS)for(int i=0;i<candidates.size();i++){
				Rectangle area=padded(candidates.get(i),w,h);
				File png=new File(dir,String.format("line%d-%03d.png",way[0],i));
				int[] cols=closedUp(ink,w,area);
				javax.imageio.ImageIO.write(crop(flat,area,cols,way[0],way[1]==1),"png",png);
				columns.add(cols);
				list.append(png.getPath()).append('\n');
				areas.add(area);
			}
			File listFile=new File(dir,"lines.txt");
			java.nio.file.Files.write(listFile.toPath(),list.toString().getBytes(StandardCharsets.UTF_8));
			List<String> cmd=new ArrayList<String>(Arrays.asList(exe.getPath(),listFile.getPath(),new File(dir,"out").getPath()));
			if(useExtra()){cmd.add("--tessdata-dir");cmd.add(extraTessdata().getPath());}
			cmd.add("-l");cmd.add(langs);
			cmd.add(modern()?"--psm":"-psm");cmd.add("7"); // one line of text
			if(langs.startsWith("jpn")||langs.startsWith("chi")||langs.startsWith("kor")){cmd.add("-c");cmd.add("preserve_interword_spaces=1");}
			cmd.add("hocr");
			if(run(cmd,300)==null)throw new IOException("Tesseract failed.");
			File out=new File(dir,"out.hocr");
			if(!out.isFile())out=new File(dir,"out.html"); // Tesseract 3.02 and older
			if(!out.isFile())throw new IOException("Tesseract wrote nothing.");
			String hocr=new String(java.nio.file.Files.readAllBytes(out.toPath()),StandardCharsets.UTF_8);
			List<List<Word>> pages=pageWords(hocr);
			List<Rectangle> boxes=new ArrayList<Rectangle>();List<String> lines=new ArrayList<String>();
			int n=candidates.size();
			for(int i=0;i<n;i++){
				List<Reading> all=new ArrayList<Reading>();
				for(int k=0;k<WAYS.length;k++){
					int page=k*n+i;
					if(page>=pages.size())continue;
					Reading r=reading(pages.get(page),areas.get(page),columns.get(page),WAYS[k][0],candidates.get(i),ink,w);
					if(r!=null)all.add(r);
				}
				Reading best=best(all);
				if(TRACE){StringBuilder d=new StringBuilder();for(Reading r:all)d.append(r.text).append(' ').append(r.conf).append(" | ");System.err.println("ocr "+candidates.get(i)+" "+d);}
				if(best!=null&&accepted(best,all)){boxes.add(best.box);lines.add(best.text);}
			}
			return paragraphs(boxes,lines);
		}finally{
			File[] fs=dir.listFiles();
			if(fs!=null)for(File f:fs)f.delete();
			dir.delete();
		}
	}

	private static final boolean TRACE=Boolean.getBoolean("hz.ocr.trace");

	/** Two readings of a line are much the same: at most a third of the characters differ (spaces aside). */
	static boolean similar(String a,String b){
		a=a.replace(" ","");b=b.replace(" ","");
		return distance(a,b)*3<=Math.max(a.length(),b.length());
	}
	/** How many characters must change to make one text the other (Levenshtein). */
	static int distance(String a,String b){
		int[] prev=new int[b.length()+1],cur=new int[b.length()+1];
		for(int j=0;j<=b.length();j++)prev[j]=j;
		for(int i=1;i<=a.length();i++){
			cur[0]=i;
			for(int j=1;j<=b.length();j++)cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
			int[] t=prev;prev=cur;cur=t;
		}
		return prev[b.length()];
	}

	/** A word Tesseract read: its text, how sure it is (0-100) and where, in the line image. */
	static final class Word {
		final String text;final int conf;final Rectangle box;
		Word(String text,int conf,Rectangle box){this.text=text;this.conf=conf;this.box=box;}
	}
	/** A line read: its text, how sure (the words' mean, by characters) and where in the picture. */
	private static final class Reading {
		final String text;final int conf;final Rectangle box;
		Reading(String text,int conf,Rectangle box){this.text=text;this.conf=conf;this.box=box;}
	}

	/**
	 * A line's reading from its words: unsure words at its ends dropped (a drawing beside the text is read as junk
	 * letters), the box narrowed to the words kept; null when what is left does not read as text (isText).
	 */
	private static Reading reading(List<Word> words,Rectangle area,int[] cols,int scale,Rectangle candidate,boolean[] ink,int w){
		List<Word> ws=new ArrayList<Word>(words);
		while(!ws.isEmpty()&&(junkAtEnd(ws.get(0),false)||icon(ws,0,1)))ws.remove(0);
		while(!ws.isEmpty()&&(junkAtEnd(ws.get(ws.size()-1),true)||icon(ws,ws.size()-1,-1)))ws.remove(ws.size()-1);
		if(ws.isEmpty())return null;
		int confSum=0,chars=0;
		List<String> texts=new ArrayList<String>();
		Rectangle box=null;
		for(Word wd:ws){
			confSum+=wd.conf*wd.text.length();chars+=wd.text.length();
			if(wd.conf<30&&!cjk(wd.text))continue;
			texts.add(wd.text);
			int c0=Math.max(0,Math.min(cols.length-1,(wd.box.x-BORDER)/scale)),c1=Math.max(0,Math.min(cols.length-1,(wd.box.x+wd.box.width-BORDER+scale-1)/scale));
			Rectangle r=new Rectangle(cols[c0],area.y+(wd.box.y-BORDER)/scale,cols[c1]-cols[c0]+1,(wd.box.height+scale-1)/scale+1);
			box=box==null?r:box.union(r);
		}
		String line=joinWords(texts);
		if(box==null||!isText(line,confSum,chars))return null;
		// the words' box is rough: the line's dark pixels across it are exact (a drawing left out beside it no longer makes it taller)
		int x0=Math.max(box.x,candidate.x),x1=Math.min(box.x+box.width,candidate.x+candidate.width);
		int tx0=Integer.MAX_VALUE,ty0=Integer.MAX_VALUE,tx1=-1,ty1=-1;
		for(int y=candidate.y;y<candidate.y+candidate.height;y++)for(int x=x0;x<x1;x++)if(ink[y*w+x]){
			if(x<tx0)tx0=x;if(x>tx1)tx1=x;if(y<ty0)ty0=y;if(y>ty1)ty1=y;
		}
		if(tx1<0)return null;
		return new Reading(line,confSum/chars,new Rectangle(tx0,ty0,tx1-tx0+1,ty1-ty0+1));
	}

	/**
	 * The surest reading of a line - unless it is only a piece of a longer reading, nearly as sure, that begins or ends
	 * with it: "ON" of "中心から描く ON", "OF F" of "中心から描く 0FF" (a sure piece read alone lost the rest).
	 */
	private static Reading best(List<Reading> all){
		Reading best=null;
		for(Reading r:all)if(best==null||r.conf>best.conf)best=r;
		if(best==null)return null;
		String core=best.text.replace(" ","");
		Reading whole=best;
		for(Reading r:all){
			String c=r.text.replace(" ","");
			if(c.length()<core.length()+3||r.conf<best.conf-15||c.length()<=whole.text.replace(" ","").length())continue;
			if(similar(core,c.substring(c.length()-core.length()))||similar(core,c.substring(0,core.length())))whole=r;
		}
		return whole;
	}

	/**
	 * Whether the surest reading of a line is text: marks of a drawing read as different junk each way, text reads much
	 * the same twice (or once, surely enough).
	 * <ul>
	 * <li>Latin: a word of three letters or fewer read the same twice, surely (specks of a drawing read as "as", "we");
	 * up to six letters read the same twice; longer text read much the same twice, or once surely.</li>
	 * <li>Japanese, Chinese, Korean: two kanji or more read fairly surely once (a label: 経験値, 現在位置 - drawings are
	 * hardly read as kanji); kana only read the same twice or once very surely (a rounded font on a dark texture read
	 * three ways as みぎかとのり, みぎがと, みぎかと; lines in a drawing as にミ, こい).</li>
	 * </ul>
	 */
	private static boolean accepted(Reading best,List<Reading> all){
		String core=best.text.replace(" ","");
		int same=0,surelySame=0;boolean similarOne=false;
		for(Reading r:all){
			if(r==best)continue;
			if(r.text.replace(" ","").equals(core)){same++;if(r.conf>=85)surelySame++;}
			if(similar(best.text,r.text))similarOne=true;
		}
		int letters=letters(best.text),han=0;
		boolean cjk=false;
		for(int i=0;i<best.text.length();i++){char c=best.text.charAt(i);if(wide(c))cjk=true;if(Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)han++;}
		if(cjk){
			if(han>=2&&best.conf>=78)return true;
			if(same>0||best.conf>=90&&letters>=3)return true;
			return letters>6&&similarOne;
		}
		if(letters<=3)return best.conf>=85&&surelySame>0;
		if(letters<=6)return same>0;
		return similarOne||best.conf>=88;
	}

	private static int letters(String text){int n=0;for(int i=0;i<text.length();i++)if(Character.isLetter(text.charAt(i)))n++;return n;}

	/**
	 * A word at a line's start or end that is a drawing beside the text read as letters: no letter or digit in it (a
	 * radio button's circle read as 〇) or read unsurely - but not Japanese or Chinese characters (the end of a word
	 * read unsurely is still the word's: left there it would stand beside its translation) nor closing punctuation.
	 */
	private static boolean junkAtEnd(Word w,boolean last){
		if(last&&closing(w.text))return false;
		if(!lettered(w.text))return true;
		return w.conf<60&&!cjk(w.text);
	}
	/** One Japanese or Chinese character alone beside a Latin word at a line's end: an icon read as a character (a folder as 画, 口). */
	private static boolean icon(List<Word> ws,int at,int towards){
		int next=at+towards;
		if(next<0||next>=ws.size())return false;
		String w=ws.get(at).text;
		return w.length()==1&&cjk(w)&&!cjk(ws.get(next).text);
	}
	private static boolean cjk(String word){for(int i=0;i<word.length();i++)if(wide(word.charAt(i)))return true;return false;}

	/** A word with a letter or a digit in it (not a radio button's circle read as 〇, nor a box's edge read as |). */
	private static boolean lettered(String word){
		for(int i=0;i<word.length();i++)if(Character.isLetterOrDigit(word.charAt(i)))return true;
		return false;
	}
	/** Punctuation that ends a sentence or a label: kept at a line's end. */
	private static boolean closing(String word){return word.matches("[.,:;!?…。、：！？」』）)]+");}

	/** A line's part of the picture: the line with a margin. */
	private static Rectangle padded(Rectangle r,int w,int h){
		int pad=Math.max(2,r.height/4);
		int x0=Math.max(0,r.x-pad),y0=Math.max(0,r.y-pad),x1=Math.min(w,r.x+r.width+pad),y1=Math.min(h,r.y+r.height+pad);
		return new Rectangle(x0,y0,x1-x0,y1-y0);
	}

	/**
	 * The picture as ink on white: each pixel as dark as it differs from the background around it (the commonest colour
	 * near it), so light or coloured letters on a dark or coloured panel are as black as black letters on white; its
	 * transparent pixels white; dithered areas and fill patterns whitened (clearPatterns).
	 */
	static BufferedImage flatten(BufferedImage picture){
		int w=picture.getWidth(),h=picture.getHeight();
		int[] px=new int[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=picture.getRGB(x,y);px[y*w+x]=(p>>>24)<128?0xFFFFFF:p&0xFFFFFF;}
		int[] bg=backgrounds(px,w,h);
		BufferedImage flat=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
		for(int i=0;i<w*h;i++){
			int p=px[i],b=bg[i];
			int d=Math.abs((p>>16&255)-(b>>16&255))+Math.abs((p>>8&255)-(b>>8&255))+Math.abs((p&255)-(b&255));
			int v=255-Math.min(255,d*255/192); // a difference of 96 (of 765) is half way: ink
			flat.setRGB(i%w,i/w,v<<16|v<<8|v);
		}
		clearPatterns(flat);
		return flat;
	}

	/** Tiles the background is found in (each from its own and its eight neighbours' pixels). */
	private static final int TILE=16;
	/** The background at each pixel: the commonest colour of the 3x3 tiles around its tile (colours a little rounded). */
	static int[] backgrounds(int[] px,int w,int h){
		int tw=(w+TILE-1)/TILE,th=(h+TILE-1)/TILE;
		List<Map<Integer,Integer>> tiles=new ArrayList<Map<Integer,Integer>>();
		for(int t=0;t<tw*th;t++)tiles.add(new HashMap<Integer,Integer>());
		for(int y=0;y<h;y++)for(int x=0;x<w;x++)tiles.get(y/TILE*tw+x/TILE).merge(px[y*w+x]&0xFCFCFC,1,Integer::sum);
		int[] mode=new int[tw*th];
		for(int ty=0;ty<th;ty++)for(int tx=0;tx<tw;tx++){
			Map<Integer,Integer> sum=new HashMap<Integer,Integer>();
			for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
				int x=tx+dx,y=ty+dy;
				if(x<0||y<0||x>=tw||y>=th)continue;
				for(Map.Entry<Integer,Integer> e:tiles.get(y*tw+x).entrySet())sum.merge(e.getKey(),e.getValue(),Integer::sum);
			}
			int best=0xFFFFFF,n=-1;
			for(Map.Entry<Integer,Integer> e:sum.entrySet())if(e.getValue()>n){n=e.getValue();best=e.getKey();}
			mode[ty*tw+tx]=best;
		}
		int[] out=new int[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[y*w+x]=mode[y/TILE*tw+x/TILE];
		return out;
	}

	private static int luminance(int p){return ((p>>16&255)*30+(p>>8&255)*59+(p&255)*11)/100;}

	/** The ink of a flattened picture: its dark pixels. */
	static boolean[] ink(BufferedImage flat){
		int w=flat.getWidth(),h=flat.getHeight();
		boolean[] ink=new boolean[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(luminance(flat.getRGB(x,y))<128)ink[y*w+x]=true;
		return ink;
	}

	/**
	 * Where lines of text may be: marks the size of letters (connected dark pixels 5 to 48 pixels high, not long lines nor
	 * hollow boxes) close beside each other at the same height, top to bottom. Tesseract decides which are text.
	 */
	static List<Rectangle> lineCandidates(boolean[] ink,int w,int h){
		// connected marks (8 neighbours)
		int[] label=new int[w*h];
		List<int[]> marks=new ArrayList<int[]>(); // {x0,y0,x1,y1,pixels}
		int[] stack=new int[w*h];
		for(int start=0;start<w*h;start++){
			if(!ink[start]||label[start]!=0)continue;
			int id=marks.size()+1,sp=0,x0=w,y0=h,x1=-1,y1=-1,count=0;
			stack[sp++]=start;label[start]=id;
			while(sp>0){
				int i=stack[--sp],x=i%w,y=i/w;
				count++;
				if(x<x0)x0=x;if(x>x1)x1=x;if(y<y0)y0=y;if(y>y1)y1=y;
				for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
					int nx=x+dx,ny=y+dy;
					if(nx<0||ny<0||nx>=w||ny>=h)continue;
					int j=ny*w+nx;
					if(ink[j]&&label[j]==0){label[j]=id;stack[sp++]=j;}
				}
			}
			marks.add(new int[]{x0,y0,x1+1,y1+1,count});
		}
		// letter-sized ones, and the small marks between and over them (ー, dots, dakuten)
		List<int[]> letters=new ArrayList<int[]>(),small=new ArrayList<int[]>();
		for(int[] m:marks){
			int mw=m[2]-m[0],mh=m[3]-m[1];
			if(mh<5){if(mw<=14)small.add(m);continue;}
			if(mh>48||mw>6*mh)continue;
			double density=m[4]/(double)(mw*mh);
			if(mw>2.5*mh&&density<0.25)continue; // a box or a line, not letters run together
			if(mw>=8&&mh>=8&&density<0.12)continue; // a frame
			if(density>=0.85&&mw*5>=mh*3)continue; // a solid block (pixel art), not a letter
			letters.add(m);
		}
		letters.sort((a,b)->Integer.compare(a[0],b[0]));
		int n=letters.size();
		int[] parent=new int[n];
		for(int i=0;i<n;i++)parent[i]=i;
		for(int i=0;i<n;i++){
			int[] a=letters.get(i);
			for(int j=i+1;j<n;j++){
				int[] b=letters.get(j);
				if(b[0]-a[2]>48)break;
				if(beside(a,b,a[3]-a[1],b[3]-b[1],0.9))parent[find(parent,i)]=find(parent,j);
			}
		}
		// lines: {x0,y0,x1,y1,median height,marks}
		Map<Integer,List<int[]>> groups=new LinkedHashMap<Integer,List<int[]>>();
		for(int i=0;i<n;i++)groups.computeIfAbsent(find(parent,i),k->new ArrayList<int[]>()).add(letters.get(i));
		List<int[]> lines=new ArrayList<int[]>();
		for(List<int[]> g:groups.values()){
			int[] l={w,h,0,0,0,g.size()};
			int[] heights=new int[g.size()];
			for(int k=0;k<g.size();k++){int[] m=g.get(k);l[0]=Math.min(l[0],m[0]);l[1]=Math.min(l[1],m[1]);l[2]=Math.max(l[2],m[2]);l[3]=Math.max(l[3],m[3]);heights[k]=m[3]-m[1];}
			Arrays.sort(heights);
			l[4]=heights[heights.length/2];
			if(l[3]-l[1]>l[4]*2.5)continue;
			lines.add(l);
		}
		// small marks within a line's height and close to it make it longer (never taller: the dots of a dotted frame
		// would lead it down the frame); then lines that came close join
		for(int[] m:small){
			for(int[] l:lines){
				if(m[1]<l[1]-1||m[3]>l[3]+1)continue;
				int gap=Math.max(l[0]-m[2],m[0]-l[2]);
				if(gap>l[4]*0.6)continue;
				l[0]=Math.min(l[0],m[0]);l[2]=Math.max(l[2],m[2]);l[5]++;
				break;
			}
		}
		lines.sort((a,b)->Integer.compare(a[0],b[0]));
		for(boolean merged=true;merged;){
			merged=false;
			for(int i=0;i<lines.size()&&!merged;i++)for(int j=i+1;j<lines.size();j++){
				int[] a=lines.get(i),b=lines.get(j);
				if(b[0]-a[2]>48)break;
				// two single characters the same size join across a wider gap: characters spaced out (命　中, a full-width space between)
				boolean single=a[2]-a[0]<=a[4]*1.3&&b[2]-b[0]<=b[4]*1.3;
				double gap=single&&Math.abs(a[4]-b[4])*4<=Math.max(a[4],b[4])?1.4:0.9;
				if(!beside(new int[]{a[0],a[1],a[2],a[3]},new int[]{b[0],b[1],b[2],b[3]},a[4],b[4],gap)&&!within(a,b)&&!within(b,a))continue;
				a[0]=Math.min(a[0],b[0]);a[1]=Math.min(a[1],b[1]);a[2]=Math.max(a[2],b[2]);a[3]=Math.max(a[3],b[3]);a[4]=Math.max(a[4],b[4]);a[5]+=b[5];
				lines.remove(j);merged=true;break;
			}
		}
		List<Rectangle> out=new ArrayList<Rectangle>();
		for(int[] l:lines){
			if(l[5]<2&&l[2]-l[0]<2*l[4])continue; // one mark: a word run together, not a lone shape
			if(l[3]-l[1]<6)continue; // smaller than any text meant to be read
			out.add(new Rectangle(l[0],l[1],l[2]-l[0],l[3]-l[1]));
		}
		out.sort((a,b)->a.y!=b.y?Integer.compare(a.y,b.y):Integer.compare(a.x,b.x));
		if(out.size()>MAX_LINES){
			List<Rectangle> widest=new ArrayList<Rectangle>(out);
			widest.sort((a,b)->Integer.compare(b.width,a.width));
			Set<Rectangle> keep=new HashSet<Rectangle>(widest.subList(0,MAX_LINES));
			out.removeIf(r->!keep.contains(r));
		}
		return out;
	}
	/**
	 * A line that is part of another's row: within its height and close beside it - the pieces of a kanji stacked
	 * one over the other (命: its roof, then 口 and 卩) are lines of their own until they join the character beside them.
	 */
	private static boolean within(int[] part,int[] line){
		int h=line[3]-line[1];
		if((part[3]-part[1])*10>h*6||part[1]<line[1]-2||part[3]>line[3]+2)return false; // a piece: much lower (text beside an icon is not)
		int gap=Math.max(part[0]-line[2],line[0]-part[2]);
		return gap>=0&&gap<=h*1.5; // beside it, not inside it (a label in a button's frame)
	}

	/** Two marks (or lines, by their letters' height) side by side in one line of text: as high, at the same height, a space apart at most. */
	private static boolean beside(int[] a,int[] b,int ah,int bh,double most){
		int big=Math.max(ah,bh),little=Math.min(ah,bh);
		int gap=Math.max(b[0]-a[2],a[0]-b[2]);
		if(gap>big*most||big>little*2.2)return false;
		int overlap=Math.min(a[3],b[3])-Math.max(a[1],b[1]);
		return overlap>=Math.min(a[3]-a[1],b[3]-b[1])*0.5;
	}
	private static int find(int[] parent,int i){while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i];}return i;}

	/**
	 * The picture's columns a line image keeps: blank runs wider than a third of the line's height closed up to that
	 * (characters spaced out, 魔　力, are read as nothing with the full-width space between them).
	 */
	static int[] closedUp(boolean[] ink,int w,Rectangle area){
		int most=Math.max(2,area.height/3);
		int[] keep=new int[area.width];int n=0,blank=0;
		for(int x=area.x;x<area.x+area.width;x++){
			boolean any=false;
			for(int y=area.y;y<area.y+area.height&&!any;y++)any=ink[y*w+x];
			blank=any?0:blank+1;
			if(blank<=most)keep[n++]=x;
		}
		return Arrays.copyOf(keep,Math.max(1,n));
	}

	/** Part of the picture (its columns {@code cols}), gray, enlarged {@code scale} times (with Scale2x first: smooth diagonals) on a white border. */
	static BufferedImage crop(BufferedImage flat,Rectangle area,int[] cols,int scale,boolean scale2x){
		BufferedImage part=new BufferedImage(cols.length,area.height,BufferedImage.TYPE_INT_RGB);
		for(int i=0;i<cols.length;i++)for(int y=0;y<area.height;y++)part.setRGB(i,y,flat.getRGB(cols[i],area.y+y));
		int width=cols.length;
		if(scale2x)part=epx(part);
		BufferedImage out=new BufferedImage(width*scale+2*BORDER,area.height*scale+2*BORDER,BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g=out.createGraphics();
		g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,out.getWidth(),out.getHeight());
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(part,BORDER,BORDER,BORDER+width*scale,BORDER+area.height*scale,0,0,part.getWidth(),part.getHeight(),null);
		g.dispose();
		return out;
	}

	private static final Pattern WORD=Pattern.compile("<(\\w+)\\s+class=['\"]ocrx_word['\"][^>]*?title=['\"]([^'\"]*)['\"][^>]*>");
	private static final Pattern CONF=Pattern.compile("x_wconf (\\d+)");

	private static final Pattern BBOX=Pattern.compile("bbox (\\d+) (\\d+) (\\d+) (\\d+)");

	/** The words of each page of Tesseract's hOCR (one page per line image). */
	static List<List<Word>> pageWords(String hocr){
		List<List<Word>> out=new ArrayList<List<Word>>();
		String[] pages=hocr.split("class=['\"]ocr_page['\"]");
		for(int p=1;p<pages.length;p++){
			String page=pages[p];
			Matcher m=WORD.matcher(page);
			List<Word> words=new ArrayList<Word>();
			int at=0;
			while(m.find(at)){
				Matcher c=CONF.matcher(m.group(2)),b=BBOX.matcher(m.group(2));
				int conf=c.find()?Integer.parseInt(c.group(1)):0;
				Rectangle box=new Rectangle();
				if(b.find()){int x0=Integer.parseInt(b.group(1)),y0=Integer.parseInt(b.group(2));box=new Rectangle(x0,y0,Integer.parseInt(b.group(3))-x0,Integer.parseInt(b.group(4))-y0);}
				int end=page.indexOf("</"+m.group(1)+">",m.end());
				if(end<0)end=m.end();
				String word=unescape(page.substring(m.end(),end).replaceAll("<[^>]*>","")).trim();
				at=Math.max(m.end(),end);
				if(!word.isEmpty())words.add(new Word(word,conf,box));
			}
			out.add(words);
		}
		return out;
	}

	/** Lines read put together into paragraphs: a line goes on the one above when it is as high, starts close below it and is in the same column. */
	static List<Block> paragraphs(List<Rectangle> pieces,List<String> texts){
		// pieces of one row first (a line found in two, split at the wide space after "、" or between words)
		List<Rectangle> rowBoxes=new ArrayList<Rectangle>();List<String> rowTexts=new ArrayList<String>();
		List<Integer> order=new ArrayList<Integer>();
		for(int i=0;i<pieces.size();i++)order.add(i);
		order.sort((a,b)->pieces.get(a).x-pieces.get(b).x);
		for(int i:order){
			Rectangle r=pieces.get(i);
			int into=-1;
			for(int k=0;k<rowBoxes.size();k++){
				Rectangle p=rowBoxes.get(k);
				int big=Math.max(p.height,r.height),small=Math.min(p.height,r.height);
				int overlap=Math.min(p.y+p.height,r.y+r.height)-Math.max(p.y,r.y),gap=r.x-(p.x+p.width);
				if(overlap>=small*0.7&&big-small<=big*0.3&&gap>=-2&&gap<=big*1.6){into=k;break;}
			}
			if(into<0){rowBoxes.add(new Rectangle(r));rowTexts.add(texts.get(i));}
			else{
				rowBoxes.get(into).add(r);
				String a=rowTexts.get(into),b=texts.get(i);
				rowTexts.set(into,a+(wide(a.charAt(a.length()-1))&&wide(b.charAt(0))?"":" ")+b);
			}
		}
		order.clear();
		for(int i=0;i<rowBoxes.size();i++)order.add(i);
		order.sort((a,b)->rowBoxes.get(a).y!=rowBoxes.get(b).y?rowBoxes.get(a).y-rowBoxes.get(b).y:rowBoxes.get(a).x-rowBoxes.get(b).x);
		List<Rectangle> boxes=new ArrayList<Rectangle>();List<String> lines=new ArrayList<String>();
		for(int i:order){boxes.add(rowBoxes.get(i));lines.add(rowTexts.get(i));}
		List<Block> out=new ArrayList<Block>();
		List<Rectangle> last=new ArrayList<Rectangle>();
		for(int i=0;i<boxes.size();i++){
			Rectangle r=boxes.get(i);
			int into=-1;
			for(int k=out.size()-1;k>=0;k--){
				Rectangle p=last.get(k);
				int big=Math.max(p.height,r.height);
				int gap=r.y-(p.y+p.height);
				boolean column=Math.abs(r.x-p.x)<=big*1.5||Math.min(r.x+r.width,p.x+p.width)-Math.max(r.x,p.x)>=Math.min(r.width,p.width)/2;
				if(Math.abs(p.height-r.height)<=big*0.35&&gap>=-big/3&&gap<=big*0.6&&column){into=k;break;} // labels one under another stand further apart than a paragraph's lines
			}
			if(into<0){out.add(new Block(new Rectangle(r),new ArrayList<String>(Collections.singletonList(lines.get(i)))));last.add(r);}
			else{Block b=out.get(into);b.box.add(r);b.lines.add(lines.get(i));last.set(into,r);}
		}
		return out;
	}

	/**
	 * Whitens dithered areas and fill patterns (gray of a 1-bit picture, bricks, sky): Tesseract reads letters into them.
	 * A dark pixel is a pattern's when most dark pixels around it stand alone or touch only corners all round (a
	 * checkerboard) - letters' strokes, even one pixel wide and slanting, have dark neighbours along them.
	 */
	static void clearPatterns(BufferedImage img){
		int w=img.getWidth(),h=img.getHeight();
		boolean[] dark=new boolean[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=img.getRGB(x,y);dark[y*w+x]=((p>>16&255)+(p>>8&255)+(p&255))<384;}
		// a lone dark pixel: no dark neighbour left, right, above or below
		int[] lone=new int[(w+1)*(h+1)]; // summed-area table of lone dark pixels
		int[] all=new int[(w+1)*(h+1)];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){
			int i=y*w+x,l=0,a=0;
			if(dark[i]){
				a=1;
				int n=(x>0&&dark[i-1]?1:0)+(x+1<w&&dark[i+1]?1:0)+(y>0&&dark[i-w]?1:0)+(y+1<h&&dark[i+w]?1:0);
				int d=(x>0&&y>0&&dark[i-w-1]?1:0)+(x+1<w&&y>0&&dark[i-w+1]?1:0)+(x>0&&y+1<h&&dark[i+w-1]?1:0)+(x+1<w&&y+1<h&&dark[i+w+1]?1:0);
				if(n==0&&(d==0||d>=3))l=1; // a dot alone, or a checkerboard's
			}
			lone[(y+1)*(w+1)+x+1]=l+lone[y*(w+1)+x+1]+lone[(y+1)*(w+1)+x]-lone[y*(w+1)+x];
			all[(y+1)*(w+1)+x+1]=a+all[y*(w+1)+x+1]+all[(y+1)*(w+1)+x]-all[y*(w+1)+x];
		}
		int r=3;
		int white=0xFFFFFF;
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){
			if(!dark[y*w+x])continue;
			int x0=Math.max(0,x-r),y0=Math.max(0,y-r),x1=Math.min(w,x+r+1),y1=Math.min(h,y+r+1);
			int nl=lone[y1*(w+1)+x1]-lone[y0*(w+1)+x1]-lone[y1*(w+1)+x0]+lone[y0*(w+1)+x0];
			int na=all[y1*(w+1)+x1]-all[y0*(w+1)+x1]-all[y1*(w+1)+x0]+all[y0*(w+1)+x0];
			if(na>=6&&nl*2>na)img.setRGB(x,y,white);
		}
	}
	/** Scale2x (EPX): twice the size with the corners of a pixel's diagonal neighbours filled in - bitmap letters stay letters. */
	static BufferedImage epx(BufferedImage in){
		int w=in.getWidth(),h=in.getHeight();
		BufferedImage out=new BufferedImage(w*2,h*2,BufferedImage.TYPE_INT_RGB);
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){
			int p=in.getRGB(x,y),a=y>0?in.getRGB(x,y-1):p,b=x+1<w?in.getRGB(x+1,y):p,c=x>0?in.getRGB(x-1,y):p,d=y+1<h?in.getRGB(x,y+1):p;
			int p1=p,p2=p,p3=p,p4=p;
			if(c==a&&c!=d&&a!=b)p1=a;
			if(a==b&&a!=c&&b!=d)p2=b;
			if(d==c&&d!=b&&c!=a)p3=c;
			if(b==d&&b!=a&&d!=c)p4=d;
			out.setRGB(2*x,2*y,p1);out.setRGB(2*x+1,2*y,p2);out.setRGB(2*x,2*y+1,p3);out.setRGB(2*x+1,2*y+1,p4);
		}
		return out;
	}

	/** Words of a line joined: a space between them, none between CJK characters. */
	private static String joinWords(List<String> words){
		StringBuilder b=new StringBuilder();
		for(String w:words){
			if(b.length()>0&&!(wide(b.charAt(b.length()-1))&&wide(w.charAt(0))))b.append(' ');
			b.append(w);
		}
		return b.toString().replaceAll("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。])\\s+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。])","");
	}

	/**
	 * Whether a line read is text worth translating: sure enough on the whole, and with letters - two in a row (a word,
	 * not a lone letter from a drawing) or a CJK character; most of it letters, digits and ordinary punctuation.
	 */
	static boolean isText(String line,int confSum,int chars){
		if(line.isEmpty()||chars==0)return false;
		if(confSum/chars<60)return false;
		int letters=0,odd=0,run=0,bestRun=0;boolean cjk=false;
		for(int i=0;i<line.length();i++){
			char ch=line.charAt(i);
			if(Character.isLetter(ch)){letters++;run++;bestRun=Math.max(bestRun,run);if(wide(ch))cjk=true;}
			else{run=0;if(!Character.isDigit(ch)&&!Character.isWhitespace(ch)&&".,:;!?'\"()-…・、。「」『』（）！？：".indexOf(ch)<0)odd++;}
		}
		if(!cjk&&bestRun<2)return false;
		if(cjk&&letters<2)return false;
		// lines, grids and dots read as letters: ー一ニ二・|Il over and over
		String core=line.replaceAll("[\\sー一ニ二三エ工ロ口・|Il1\\-_=~.。、,:;]","");
		if(core.length()<2)return false;
		int[] counts=new int[65536];int most=0;
		for(int i=0;i<line.length();i++)if(!Character.isWhitespace(line.charAt(i)))most=Math.max(most,++counts[line.charAt(i)]);
		if(most*2>line.replace(" ","").length()&&most>2)return false;
		return odd*3<=line.length()&&letters*2>=line.replace(" ","").length();
	}

	static boolean wide(char c){
		Character.UnicodeScript s;
		try{s=Character.UnicodeScript.of(c);}catch(IllegalArgumentException e){return false;}
		return s==Character.UnicodeScript.HAN||s==Character.UnicodeScript.HIRAGANA||s==Character.UnicodeScript.KATAKANA
			||s==Character.UnicodeScript.HANGUL||c=='ー'||c=='、'||c=='。'||c>=0xFF01&&c<=0xFF60;
	}

	private static String unescape(String s){
		if(s.indexOf('&')<0)return s;
		Matcher m=Pattern.compile("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);").matcher(s);
		StringBuilder b=new StringBuilder();
		while(m.find()){
			String e=m.group(1),r;
			if(e.startsWith("#x"))r=new String(Character.toChars(Integer.parseInt(e.substring(2),16)));
			else if(e.startsWith("#"))r=new String(Character.toChars(Integer.parseInt(e.substring(1))));
			else r=e.equals("amp")?"&":e.equals("lt")?"<":e.equals("gt")?">":e.equals("quot")?"\"":"'";
			m.appendReplacement(b,Matcher.quoteReplacement(r));
		}
		m.appendTail(b);
		return b.toString();
	}

	/** Runs a command and returns what it printed (stdout and stderr), or null when it failed or took too long. */
	private static String run(List<String> cmd,int seconds){
		try{
			ProcessBuilder pb=new ProcessBuilder(cmd).redirectErrorStream(true);
			pb.environment().put("OMP_THREAD_LIMIT","1"); // Tesseract's threads are slower than one for small pictures, and HyperZebra runs one at a time
			Process p=pb.start();
			p.getOutputStream().close();
			byte[] out=p.getInputStream().readAllBytes();
			if(!p.waitFor(seconds,TimeUnit.SECONDS)){p.destroyForcibly();return null;}
			return p.exitValue()==0?new String(out,StandardCharsets.UTF_8):null;
		}catch(IOException e){
			return null;
		}catch(InterruptedException e){
			Thread.currentThread().interrupt();
			return null;
		}
	}
}

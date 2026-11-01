import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Kaleidoscope 2.x colour schemes for the natively drawn controls: when the Kaleidoscope control panel (creator 'Acid') is in
 * the virtual System Folder's Control Panels and its preferences name a scheme ('alis' 128 of "Kaleidoscope Preferences",
 * written by the panel's Select Scheme...), push buttons, the default ring, scroll bars and progress bars are drawn from the
 * scheme's 'cicn' elements, as Kaleidoscope's own definitions draw them. The real Kaleidoscope patches the System's CDEFs from
 * its start-up INIT; here every program's controls are Java ones, so the scheme is read and drawn here instead.
 * <p>
 * The rules were measured against the real engine (its kDEF code run in the control panel's own process with
 * -Dhz68k.guestcdef=true, controls made with the debug command {@code newcontrol}):
 * <ul>
 * <li>A frame element (push button -10240..-10238, default ring -10232/-10231) is cut by its 'cinf' (corner size, side
 * thickness, tile sides, background / text / embossing pixels; the control panel's 'cinf' 0 and 1 are the defaults): corners
 * as they are, sides stretched or tiled, the inside filled with the background pixel's colour, the title in the text pixel's
 * colour over its embossing one 1 pixel down and right.</li>
 * <li>Scroll bars use the track cicns (vertical -8280..-8273, horizontal -8288..-8281, small ones -8270..-8255): a single cicn
 * is up arrow, track, down arrow (arrows as long as the bar is thick); the "double headed" one has both arrows at each end and
 * is used from a length of 8 arrows on. The thumb (-10208/-10207 vertical, -10206/-10205 horizontal) keeps its size, its
 * position rounded; the track is drawn on each side of it from the cicn's track section (start rows, then its run of equal
 * rows repeated, then end rows). A pressed arrow comes from the "pressed" cicn; an inactive bar is the inactive track alone;
 * one with nothing to scroll is the "nothing" cicn without a thumb.</li>
 * <li>The progress bar (-10224 track, -10223 fill) keeps the cicn's height, top aligned: the track is drawn from the end of
 * the filled part, the fill (its frame columns, then its own start, middle and end) from the left over it.</li>
 * <li>Accent colours (the panel's Accent Color pop-up, schemes whose 'Colr' 129 says they have them: Apple platinum,
 * Al-Azul): the preferences' 'Acid' 128 byte 24 turns them on, byte 25 is the colour (the menu item - 1: Lavender 0 .. Silver
 * 7, Aquamarine 9 .. Teal 18). The colour's own elements replace the progress fill (-10223 + i, -10080 + i - 9) and the
 * thumbs (-10208 + 4i + k, -9472 + 4(i - 9) + k; k = vertical, pressed, horizontal, pressed), as its menu highlight
 * (-12287 + i, -12256 + i - 9) and the panel's 'clut' 300 + i ramp; the second set only where the scheme has it (cicn -12256),
 * else the first colour, as the engine does.</li>
 * </ul>
 * Elements a scheme lacks come from the control panel file (its default look), else the native drawing is used.
 * -Dhz68k.kaleidoscope=false turns the schemes off.
 */
final class KaleidoscopeScheme68k {
	static final boolean ENABLED=!"false".equals(System.getProperty("hz68k.kaleidoscope"));
	/** "Scroll Bars have Double Arrows" (the panel's General Options; the real engine draws them in every case measured). */
	static final boolean DOUBLE_ARROWS=!"false".equals(System.getProperty("hz68k.kaleidoscope.doublearrows"));

	static final int PUSH_INACTIVE=-10240,PUSH=-10239,PUSH_PRESSED=-10238,RING_INACTIVE=-10232,RING=-10231,
		PROGRESS_TRACK=-10224,PROGRESS_FILL=-10223,
		THUMB_V=-10208,THUMB_V_PRESSED=-10207,THUMB_H=-10206,THUMB_H_PRESSED=-10205,
		SMALL_THUMB_V=-8252,SMALL_THUMB_V_PRESSED=-8251,SMALL_THUMB_H=-8250,SMALL_THUMB_H_PRESSED=-8249;
	/** Track cicns, by {small 0/1}{vertical 0/1}: inactive, nothing to scroll, normal, pressed, double nothing, double, double pressed. */
	private static final int[][][] TRACKS={
		{{-8288,-8287,-8286,-8285,-8283,-8282,-8281},{-8280,-8279,-8278,-8277,-8275,-8274,-8273}},
		{{-8270,-8269,-8268,-8267,-8265,-8264,-8263},{-8262,-8261,-8260,-8259,-8257,-8256,-8255}}};
	private static final int T_INACTIVE=0,T_NOTHING=1,T_NORMAL=2,T_PRESSED=3,T_DOUBLE_NOTHING=4,T_DOUBLE=5,T_DOUBLE_PRESSED=6;
	static final int IN_UP=20,IN_DOWN=21,IN_THUMB=129;

	private static final int CICN=0x6369636e,CINF=0x63696e66,ALIS=0x616c6973;

	/** A decoded 'cicn': ARGB pixels (mask applied). */
	static final class Img {
		final int w,h;final int[] argb;final BufferedImage image;
		Img(int w,int h,int[] argb){this.w=w;this.h=h;this.argb=argb;image=new BufferedImage(Math.max(1,w),Math.max(1,h),BufferedImage.TYPE_INT_ARGB);if(w>0&&h>0)image.setRGB(0,0,w,h,argb,0,w);}
		int pixel(int y,int x){return x>=0&&y>=0&&x<w&&y<h?argb[y*w+x]:0;}
		/** Row (vertical) or column of pixels, for finding the run of equal lines along a strip. */
		boolean sameLine(boolean rows,int a,int b,int from,int to){
			for(int i=from;i<to;i++)if(rows?argb[a*w+i]!=argb[b*w+i]:argb[i*w+a]!=argb[i*w+b])return false;return true;
		}
		/** Whether column {@code a} of this image equals column {@code b} of {@code other} (over the shorter height). */
		boolean sameLineAt(Img other,int a,int b){
			for(int y=0;y<Math.min(h,other.h);y++)if(argb[y*w+a]!=other.argb[y*other.w+b])return false;return true;
		}
	}
	/** A 'cinf': how a frame element is cut and where its colours are sampled. */
	static final class Cinf {
		final int corner,side;final boolean tileSides;final int anchor,pattern,bgY,bgX,textY,textX,embossY,embossX;
		Cinf(int corner,int side,boolean tileSides,int anchor,int pattern,int bgY,int bgX,int textY,int textX,int embossY,int embossX){
			this.corner=corner;this.side=side;this.tileSides=tileSides;this.anchor=anchor;this.pattern=pattern;this.bgY=bgY;this.bgX=bgX;this.textY=textY;this.textX=textX;this.embossY=embossY;this.embossX=embossX;}
		static Cinf parse(byte[] d){
			if(d==null||d.length<18)return null;
			return new Cinf(d[0]&255,d[1]&255,d[2]!=0,d[3]&255,s16(d,4),s16(d,6),s16(d,8),s16(d,10),s16(d,12),s16(d,14),s16(d,16));
		}
	}
	private static final Cinf DEFAULT_PUSH=new Cinf(6,6,false,0,0,6,6,8,8,7,9),DEFAULT_RING=new Cinf(8,4,false,0,0,0,0,0,0,0,0);

	private final ResourceFile68k scheme,panel;
	// the program's thread draws controls with them, the EDT the frame round a game screen
	private final Map<Integer,Img> images=new java.util.concurrent.ConcurrentHashMap<Integer,Img>();
	private final java.util.Set<Integer> missing=java.util.concurrent.ConcurrentHashMap.newKeySet();
	final String name;
	/** The accent colour in use (0 Lavender, the scheme's own elements; see the class comment). */
	final int accent;
	/** A scheme read from these files ({@link #active} finds them; tests make them in memory). */
	KaleidoscopeScheme68k(ResourceFile68k scheme,ResourceFile68k panel,String name){this(scheme,panel,name,0);}
	/** ... with the accent colour of the preferences (menu item - 1), when the scheme has accent colours. */
	KaleidoscopeScheme68k(ResourceFile68k scheme,ResourceFile68k panel,String name,int accent){
		this.scheme=scheme;this.panel=panel;this.name=name;
		ResourceFile68k.Resource colr=scheme.get(COLR,129);byte[] d=colr==null?null:colr.data();
		boolean has=d!=null&&d.length>3&&d[3]!=0;
		// Aquamarine..Teal are a second set later schemes added (the engine checks for its menu highlight -12256 first)
		if(!has||accent<0||accent==8||accent>18||accent>8&&scheme.get(CICN,-12256)==null)accent=0;
		this.accent=accent;
	}
	/** The accent colour's element for this base element ID (progress fill, thumbs), or the ID itself. */
	int accented(int id){
		int i=accent;if(i==0)return id;
		if(id==PROGRESS_FILL)return i<8?id+i:-10080+i-9;
		if(id>=THUMB_V&&id<=THUMB_H_PRESSED)return i<8?id+4*i:-9472+4*(i-9)+(id-THUMB_V);
		if(id==MENU_HIGHLIGHT)return i<8?id+i:-12256+i-9;
		return id;
	}
	static final int MENU_HIGHLIGHT=-12287;
	/** Preferences 'Acid' 128: byte 24 accent colours on, byte 25 the colour. */
	static int accentOf(byte[] acid){return acid!=null&&acid.length>25&&acid[24]!=0?acid[25]&255:0;}

	// ------------------------------------------------------------------ the active scheme

	private static KaleidoscopeScheme68k cached;private static String cachedKey="";private static long checkedAt;private static File cachedSystemFolder,cachedPanel;

	/** Tests: the scheme in use whatever the System Folder holds (null: the one Kaleidoscope would use). */
	static volatile KaleidoscopeScheme68k testScheme;
	/** The scheme Kaleidoscope would use in this System Folder, or null (not installed, none chosen, turned off). */
	static KaleidoscopeScheme68k active(Toolbox68k tb){
		if(testScheme!=null)return testScheme;
		if(!ENABLED||tb==null||tb.files==null)return null;
		File system=tb.files.systemFolder;if(system==null)return null;
		// a black-and-white screen (a compact-Mac program's 1-bit screen) keeps the standard look: the scheme's colour pieces
		// reduced to 1 bit left MacPaint's About box with a black blot where its OK button's title should be
		try{if(tb.api!=null&&tb.api.quickDraw().screenDepth()==1)return null;}catch(M68kCpuException e){}
		long now=System.currentTimeMillis();
		synchronized(KaleidoscopeScheme68k.class){
			// the preferences are looked at on every call (a few stats): the panel redraws its window right after writing the
			// new choice, and a second's delay left its buttons in the old scheme; the Control Panels folder every 2 seconds
			if(!system.equals(cachedSystemFolder)||now-checkedAt>=2000){checkedAt=now;cachedSystemFolder=system;cachedPanel=controlPanel(system);}
			try{
				File panelFile=cachedPanel;
				File prefs=new File(new File(system,"Preferences"),"Kaleidoscope Preferences");
				if(panelFile==null||!prefs.isFile()){cached=null;cachedKey="";return null;}
				String stamp=prefs.lastModified()+"|"+forkLength(prefs);
				if(liveAccent>=0&&!stamp.equals(liveAccentPrefs))liveAccent=-1; // written since: the file says
				String key=panelFile+"|"+panelFile.lastModified()+"|"+stamp+"|"+liveAccent;
				if(key.equals(cachedKey))return cached;
				cachedKey=key;cached=null;
				ResourceFile68k p=ResourceFile68k.open(prefs);ResourceFile68k.Resource alias=p==null?null:p.get(ALIS,128);
				File schemeFile=alias==null?null:tb.files.aliasTarget(alias.data(),prefs);
				if(schemeFile==null||!schemeFile.isFile())return null;
				ResourceFile68k s=ResourceFile68k.open(schemeFile);if(s==null)return null;
				ResourceFile68k.Resource acid=p.get(ResourceFile68k.fourcc("Acid"),128);
				cached=new KaleidoscopeScheme68k(s,ResourceFile68k.open(panelFile),schemeFile.getName(),liveAccent>=0?liveAccent:accentOf(acid==null?null:acid.data()));
				if(Mac68kApi.TRACE)System.err.println("[68K] Kaleidoscope scheme "+schemeFile+" accent "+cached.accent);
				return cached;
			}catch(IOException|RuntimeException e){
				if(Mac68kApi.TRACE)System.err.println("[68K] Kaleidoscope scheme not read: "+e);
				cached=null;return null;
			}
		}
	}
	/** The accent colour picked in the open panel (which writes its preferences only when closed), for the preferences as they were then. */
	private static int liveAccent=-1;private static String liveAccentPrefs="";
	/**
	 * The panel's Accent Color pop-up (MENU -14334) chose item {@code item}: programs draw in that colour at once, as the
	 * real engine reads the panel's settings in memory; until the preferences change (the panel writes them on closing).
	 */
	static void popupChosen(Toolbox68k tb,int menuId,int item){
		if(menuId!=ACCENT_MENU||!ENABLED||tb==null||tb.files==null||tb.app==null||tb.files.systemFolder==null)return;
		File system=tb.files.systemFolder,panelFile=controlPanel(system);
		if(panelFile==null||!panelFile.equals(tb.app.file))return;
		File prefs=new File(new File(system,"Preferences"),"Kaleidoscope Preferences");
		synchronized(KaleidoscopeScheme68k.class){liveAccent=item-1;liveAccentPrefs=prefs.lastModified()+"|"+forkLength(prefs);cachedKey="";}
	}
	static final int ACCENT_MENU=-14334;
	private static long forkLength(File f){File fork=new File(f,"..namedfork/rsrc");return fork.length()+f.length();}
	/** The Kaleidoscope control panel (type 'cdev', creator 'Acid') in Control Panels, which loads its INIT at start-up. */
	static File controlPanel(File system){
		File[] files=new File(system,"Control Panels").listFiles();if(files==null)return null;
		for(File f:files){
			if(!f.isFile())continue;
			byte[] info=FinderInfo68k.read(f);
			if(info!=null&&info.length>=8&&ResourceFile68k.fourcc("cdev")==u32(info,0)&&ResourceFile68k.fourcc("Acid")==u32(info,4))return f;
		}
		return null;
	}

	// ------------------------------------------------------------------ installing (schemes dropped on HyperZebra)

	/** A Kaleidoscope scheme file: type 'Colr', creator 'Acid'. */
	static boolean isSchemeFile(File file){
		if(file==null||!file.isFile())return false;
		byte[] info=FinderInfo68k.read(file);
		return info!=null&&info.length>=8&&ResourceFile68k.fourcc("Colr")==u32(info,0)&&ResourceFile68k.fourcc("Acid")==u32(info,4);
	}
	/**
	 * The schemes a dropped folder holds directly: the Kaleidoscope Scheme Archive's downloads expand into a folder with the
	 * scheme, a read me and sometimes desktop pictures. Empty for anything else (a Mac OS 9 package is a program).
	 */
	static java.util.List<File> schemesIn(File folder){
		java.util.List<File> schemes=new java.util.ArrayList<File>();
		File[] files=folder==null||!folder.isDirectory()||MacApp68k.programOf(folder)!=folder?null:folder.listFiles();
		if(files==null)return schemes;
		java.util.Arrays.sort(files,java.util.Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
		for(File f:files)if(!f.getName().startsWith(".")&&isSchemeFile(f))schemes.add(f);
		return schemes;
	}
	/**
	 * Where Kaleidoscope keeps schemes: the "Kaleidoscope Schemes" folder of Apple Menu Items, whose submenu of the Apple
	 * menu switches schemes (the panel's own help suggests keeping them there; its Select Scheme... can still open one anywhere).
	 */
	static File schemesFolder(File system){return new File(new File(system,"Apple Menu Items"),"Kaleidoscope Schemes");}
	/** Copies a scheme into the virtual System Folder's {@link #schemesFolder}; returns the installed file. */
	static File install(File source) throws IOException {return install(source,QuickTimeService.systemFolder());}
	static File install(File source,File system) throws IOException {return Extensions68k.copyInto(source,schemesFolder(system));}

	// ------------------------------------------------------------------ elements

	/** The scheme's element, else the control panel's own (its default look); null when neither has it. */
	Img element(int id){return element(id,true);}
	private Img element(int id,boolean fallback){
		Integer key=fallback?id:id^0x40000000;
		Img img=images.get(key);if(img!=null||missing.contains(key))return img;
		int aid=accented(id);
		ResourceFile68k.Resource r=aid!=id?scheme.get(CICN,aid):null;
		if(r==null)r=scheme.get(CICN,id);
		if(r==null&&fallback&&panel!=null)r=panel.get(CICN,id);
		img=r==null?null:decodeCicn(r.data());
		if(img==null)missing.add(key);else images.put(key,img);
		return img;
	}
	private static final int PPAT=0x70706174,COLR=0x436f6c72;
	/** The scheme's 'Colr' 129 flag "Unified scroll bar track" (Sherbet, Aqua III): the track runs on under the thumb unbroken. */
	boolean unifiedTrack(){ResourceFile68k.Resource r=scheme.get(COLR,129);byte[] d=r==null?null:r.data();return d!=null&&d.length>10&&d[9]!=0;}
	/** The scheme's 'ppat' of that ID (tracks and button insides are filled with the pattern of their cicn's ID), or null. */
	Img pattern(int id){
		Integer key=id^0x20000000;
		Img img=images.get(key);if(img!=null||missing.contains(key))return img;
		ResourceFile68k.Resource r=scheme.get(PPAT,id);
		img=r==null?null:decodePpat(r.data());
		if(img==null)missing.add(key);else images.put(key,img);
		return img;
	}
	/** A 'ppat': type 1 (PixMap, pixels, colour table at the offsets of its header) or the 8x8 black-and-white pattern. */
	static Img decodePpat(byte[] b){
		try{
			int type=s16(b,0),map=u32(b,2),data=u32(b,6);
			if(type!=1&&type!=2){int[] argb=new int[64];for(int y=0;y<8;y++)for(int x=0;x<8;x++)argb[y*8+x]=(b[20+y]>>(7-x)&1)!=0?0xff000000:0xffffffff;return new Img(8,8,argb);}
			int rowBytes=u16(b,map+4)&0x3fff,top=s16(b,map+6),left=s16(b,map+8),bottom=s16(b,map+10),right=s16(b,map+12),pixelSize=u16(b,map+32),table=u32(b,map+42);
			int w=right-left,h=bottom-top;if(w<=0||h<=0||w>512||h>512)return null;
			Map<Integer,Integer> colours=new HashMap<Integer,Integer>();
			int flags=u16(b,table+4),n=u16(b,table+6)+1;
			for(int i=0,p=table+8;i<n;i++,p+=8)colours.put((flags&0x8000)!=0?i:u16(b,p),0xff000000|(b[p+2]&255)<<16|(b[p+4]&255)<<8|(b[p+6]&255));
			int[] argb=new int[w*h];
			for(int y=0;y<h;y++)for(int x=0;x<w;x++){
				if(pixelSize<=8){int bit=pixelSize*x;int v=(b[data+y*rowBytes+bit/8]&255)>>(8-pixelSize-bit%8)&((1<<pixelSize)-1);Integer c=colours.get(v);argb[y*w+x]=c==null?0xff000000:c;}
				else if(pixelSize==16){int v=u16(b,data+y*rowBytes+2*x);argb[y*w+x]=0xff000000|((v>>10&31)*255/31)<<16|((v>>5&31)*255/31)<<8|(v&31)*255/31;}
				else{int o=data+y*rowBytes+4*x;argb[y*w+x]=0xff000000|(b[o+1]&255)<<16|(b[o+2]&255)<<8|(b[o+3]&255);}
			}
			return new Img(w,h,argb);
		}catch(ArrayIndexOutOfBoundsException e){return null;}
	}
	/** The pattern tiled over a rectangle, its origin at (ax, ay). */
	private static void fillPattern(Graphics2D g,Img pat,int x,int y,int w,int h,int ax,int ay){
		if(w<=0||h<=0)return;
		int x0=ax+Math.floorDiv(x-ax,pat.w)*pat.w,y0=ay+Math.floorDiv(y-ay,pat.h)*pat.h;
		Shape clip=g.getClip();g.clipRect(x,y,w,h);
		try{for(int ty=y0;ty<y+h;ty+=pat.h)for(int tx=x0;tx<x+w;tx+=pat.w)g.drawImage(pat.image,tx,ty,null);}
		finally{g.setClip(clip);}
	}
	/** A line of the strip mostly clear: the scheme marks with it where its track's pattern goes (Antique). */
	private static int separator(Img img,boolean vertical,int from,int to){
		int across=vertical?img.w:img.h;
		for(int i=from;i<to;i++){int clear=0;for(int j=0;j<across;j++)if((img.pixel(vertical?i:j,vertical?j:i)>>>24)==0)clear++;if(clear*2>across)return i;}
		return -1;
	}
	/**
	 * A track strip: with a separator line, the lines before it at the start, the lines after it at the end and the pattern
	 * (anchored at the control's corner) between; otherwise {@link #strip}.
	 */
	private static void trackStrip(Graphics2D g,Img img,Img pat,boolean vertical,int from,int to,int x,int y,int along,int across,int ax,int ay){
		int k=separator(img,vertical,from,to);
		if(k<0){strip(g,img,vertical,from,to,x,y,along,across);return;}
		int start=k-from,end=to-k-1;
		if(pat!=null){if(vertical)fillPattern(g,pat,x,y,across,along,ax,ay);else fillPattern(g,pat,x,y,along,across,ax,ay);}
		else if(start>0)for(int p=start;p<along-end;p++)sliceAlong(g,img,vertical,k-1,1,x,y,p,1,across);
		int s=Math.min(start,along);sliceAlong(g,img,vertical,from,s,x,y,0,s,across);
		int e=Math.min(end,along);sliceAlong(g,img,vertical,to-e,e,x,y,along-e,e,across);
	}
	private Cinf cinf(int id,Cinf fallback){
		ResourceFile68k.Resource r=scheme.get(CINF,id);
		Cinf c=r==null?null:Cinf.parse(r.data());
		return c!=null?c:fallback;
	}
	private Cinf panelCinf(int id,Cinf fallback){ResourceFile68k.Resource r=panel==null?null:panel.get(CINF,id);Cinf c=r==null?null:Cinf.parse(r.data());return c!=null?c:fallback;}

	/** A 'cicn' (IconFamily: PixMap, mask BitMap, icon BitMap, then mask, bitmap, colour table and pixel data). */
	static Img decodeCicn(byte[] b){
		try{
			int rowBytes=u16(b,4)&0x3fff,top=s16(b,6),left=s16(b,8),bottom=s16(b,10),right=s16(b,12),pixelSize=u16(b,32);
			int w=right-left,h=bottom-top;if(w<=0||h<=0||w>1024||h>1024)return null;
			int maskRow=u16(b,54),bitRow=u16(b,68);
			int p=82;int mask=p;p+=maskRow*h;p+=bitRow*h;
			int flags=u16(b,p+4),n=u16(b,p+6)+1;p+=8;
			Map<Integer,Integer> colours=new HashMap<Integer,Integer>();
			for(int i=0;i<n;i++){int v=u16(b,p);int c=0xff000000|(b[p+2]&255)<<16|(b[p+4]&255)<<8|(b[p+6]&255);colours.put((flags&0x8000)!=0?i:v,c);p+=8;}
			int data=p;int[] argb=new int[w*h];
			for(int y=0;y<h;y++)for(int x=0;x<w;x++){
				boolean opaque=maskRow==0||(b[mask+y*maskRow+x/8]>>(7-x%8)&1)!=0;
				if(!opaque)continue;
				int v;
				if(pixelSize<=8){int bit=pixelSize*x;v=(b[data+y*rowBytes+bit/8]&255)>>(8-pixelSize-bit%8)&((1<<pixelSize)-1);Integer c=colours.get(v);argb[y*w+x]=c==null?0xff000000:c;}
				else if(pixelSize==16){v=u16(b,data+y*rowBytes+2*x);argb[y*w+x]=0xff000000|((v>>10&31)*255/31)<<16|((v>>5&31)*255/31)<<8|(v&31)*255/31;}
				else{int o=data+y*rowBytes+4*x;argb[y*w+x]=0xff000000|(b[o+1]&255)<<16|(b[o+2]&255)<<8|(b[o+3]&255);}
			}
			return new Img(w,h,argb);
		}catch(ArrayIndexOutOfBoundsException e){return null;}
	}

	// ------------------------------------------------------------------ drawing primitives

	private static void blit(Graphics2D g,Img img,int sx,int sy,int sw,int sh,int dx,int dy,int dw,int dh){
		if(sw<=0||sh<=0||dw<=0||dh<=0)return;
		g.drawImage(img.image,dx,dy,dx+dw,dy+dh,sx,sy,sx+sw,sy+sh,null);
	}
	/** Source region tiled over the destination, starting at its top left. */
	private static void tile(Graphics2D g,Img img,int sx,int sy,int sw,int sh,int dx,int dy,int dw,int dh){
		if(sw<=0||sh<=0||dw<=0||dh<=0)return;
		Shape clip=g.getClip();g.clipRect(dx,dy,dw,dh);
		try{for(int y=dy;y<dy+dh;y+=sh)for(int x=dx;x<dx+dw;x+=sw)g.drawImage(img.image,x,y,x+sw,y+sh,sx,sy,sx+sw,sy+sh,null);}
		finally{g.setClip(clip);}
	}
	private static void prepare(Graphics2D g){
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
	}

	/** A frame element cut by its 'cinf' over (x,y,w,h); the inside filled with the background pixel's colour. */
	/**
	 * The pattern anchor adds to the corner size and side thickness and moves the background pixel (Antique's 9x9 buttons,
	 * corner 3 + anchor 3: 6-pixel corners that overlap in the cicn, the rivet of its middle in each of them); with no room
	 * left between the corners the line just past them is the side.
	 */
	private static void frame(Graphics2D g,Img img,Cinf c,int x,int y,int w,int h){
		int iw=img.w,ih=img.h,cor=Math.min(c.corner+c.anchor,Math.min(iw,ih)),side=Math.min(c.side+c.anchor,Math.min(iw,ih));
		// the inside
		if(c.bgY>0||c.bgX>0){int bg=img.pixel(c.bgY+c.anchor,c.bgX+c.anchor);if((bg>>>24)!=0){g.setColor(new Color(bg,true));g.fillRect(x+side,y+side,w-2*side,h-2*side);}}
		// sides: between the corners, stretched (or tiled)
		// stretched sides come from their first line past the corner: Aqua III's tabs have the text pixel sample inside the
		// bottom side, which a stretch of the whole middle drew as a line through the title
		int sx=Math.min(cor,iw-1),sy=Math.min(cor,ih-1),midW=c.tileSides?Math.max(1,iw-2*cor):1,midH=c.tileSides?Math.max(1,ih-2*cor):1,dw=w-2*cor,dh=h-2*cor;
		if(dw>0){
			if(c.tileSides){tile(g,img,sx,0,midW,side,x+cor,y,dw,side);tile(g,img,sx,ih-side,midW,side,x+cor,y+h-side,dw,side);}
			else{blit(g,img,sx,0,midW,side,x+cor,y,dw,side);blit(g,img,sx,ih-side,midW,side,x+cor,y+h-side,dw,side);}
		}
		if(dh>0){
			if(c.tileSides){tile(g,img,0,sy,side,midH,x,y+cor,side,dh);tile(g,img,iw-side,sy,side,midH,x+w-side,y+cor,side,dh);}
			else{blit(g,img,0,sy,side,midH,x,y+cor,side,dh);blit(g,img,iw-side,sy,side,midH,x+w-side,y+cor,side,dh);}
		}
		// corners as they are (halved when the frame is smaller than two of them)
		int lw=Math.min(cor,w/2),rw=Math.min(cor,w-lw),th=Math.min(cor,h/2),bh=Math.min(cor,h-th);
		blit(g,img,0,0,lw,th,x,y,lw,th);
		blit(g,img,iw-rw,0,rw,th,x+w-rw,y,rw,th);
		blit(g,img,0,ih-bh,lw,bh,x,y+h-bh,lw,bh);
		blit(g,img,iw-rw,ih-bh,rw,bh,x+w-rw,y+h-bh,rw,bh);
	}

	/**
	 * A strip of {@code img} (the lines {@code from}..{@code to} along the axis: rows when vertical) drawn over a length: its
	 * first lines, the run of equal lines repeated, its last lines at the end. Lines are cut across from {@code across}.
	 */
	private static void strip(Graphics2D g,Img img,boolean vertical,int from,int to,int x,int y,int along,int across){
		int n=to-from;if(n<=0||along<=0)return;
		int[] run=equalRun(img,vertical,from,to);int a=run[0],b=run[1];
		int start=a-from,end=to-b,tileLen=b-a;
		if(along<=start+end){ // too short for the whole caps: the start then the end, the end on top
			int s=Math.min(start,along);sliceAlong(g,img,vertical,from,s,x,y,0,s,across);
			int e=Math.min(end,along);sliceAlong(g,img,vertical,to-e,e,x,y,along-e,e,across);
			return;
		}
		sliceAlong(g,img,vertical,from,start,x,y,0,start,across);
		for(int p=start;p<along-end;p+=tileLen){int len=Math.min(tileLen,along-end-p);sliceAlong(g,img,vertical,a,len,x,y,p,len,across);}
		sliceAlong(g,img,vertical,b,end,x,y,along-end,end,across);
	}
	/** {@code len} lines of the image from line {@code src} to position {@code at} along the strip at (x,y). */
	private static void sliceAlong(Graphics2D g,Img img,boolean vertical,int src,int len,int x,int y,int at,int dlen,int across){
		if(len<=0)return;
		if(vertical)blit(g,img,0,src,Math.min(across,img.w),len,x,y+at,Math.min(across,img.w),dlen);
		else blit(g,img,src,0,len,Math.min(across,img.h),x+at,y,dlen,Math.min(across,img.h));
	}
	/** The longest run of equal consecutive lines in [from, to): {start, end}; a single middle line when none repeats. */
	private static int[] equalRun(Img img,boolean vertical,int from,int to){
		int across=vertical?img.w:img.h;int bestA=(from+to)/2,bestB=bestA+1;
		for(int i=from;i<to;){
			int j=i+1;while(j<to&&img.sameLine(vertical,i,j,0,across))j++;
			if(j-i>bestB-bestA){bestA=i;bestB=j;}
			i=j;
		}
		return new int[]{bestA,Math.min(bestB,to)};
	}

	// ------------------------------------------------------------------ push buttons and the default ring

	/** CDEF 0's push button in the scheme; false when the scheme (and the panel) has no such element. */
	boolean drawPushButton(Graphics2D g,int left,int top,int right,int bottom,String title,boolean pressed,boolean disabled,FontMetrics fm){
		int id=disabled?PUSH_INACTIVE:pressed?PUSH_PRESSED:PUSH;Img img=element(id);if(img==null)return false;
		Cinf c=cinf(id,panelCinf(0,DEFAULT_PUSH));
		prepare(g);
		int w=right-left,h=bottom-top;if(w<=0||h<=0)return true;
		frame(g,img,c,left,top,w,h);
		int text=img.pixel(c.textY,c.textX),emboss=c.embossY>0||c.embossX>0?img.pixel(c.embossY,c.embossX):0,bg=img.pixel(c.bgY,c.bgX);
		Shape clip=g.getClip();g.clip(new java.awt.Rectangle(left-1000,top,w+2000,h));
		try{
			String[] lines=title.split("\r",-1);int lh=fm.getHeight();int y=top+(h-lh*lines.length)/2+fm.getAscent();
			for(String line:lines){
				int x=left+(w-fm.stringWidth(line))/2;
				if((emboss>>>24)!=0&&emboss!=bg){g.setColor(new Color(emboss));Fonts68k.drawString(g,fm,line,x+1,y+1);}
				g.setColor(new Color((text>>>24)!=0?text:0xff000000));Fonts68k.drawString(g,fm,line,x,y);
				y+=lh;
			}
		}finally{g.setClip(clip);}
		return true;
	}
	/** The default ring round a push button (outside its rectangle); false without the element. */
	boolean drawDefaultRing(Graphics2D g,int left,int top,int right,int bottom,boolean disabled){
		int id=disabled?RING_INACTIVE:RING;Img img=element(id);if(img==null)return false;
		Cinf c=cinf(id,panelCinf(1,DEFAULT_RING));
		prepare(g);int o=c.side;
		frame(g,img,new Cinf(c.corner,c.side,c.tileSides,c.anchor,0,0,0,0,0,0,0),left-o,top-o,right-left+2*o,bottom-top+2*o);
		return true;
	}

	// ------------------------------------------------------------------ scroll bars

	/** A scroll bar's layout along its length: the arrows ({start, end, part}), the track and the thumb (0..0 without one). */
	static final class Bar {
		final boolean vertical;final int length;final int[][] arrows;final int trackStart,trackEnd,thumbStart,thumbEnd;
		final boolean doubled,small;
		Bar(boolean vertical,int length,int[][] arrows,int trackStart,int trackEnd,int thumbStart,int thumbEnd,boolean doubled,boolean small){
			this.vertical=vertical;this.length=length;this.arrows=arrows;this.trackStart=trackStart;this.trackEnd=trackEnd;this.thumbStart=thumbStart;this.thumbEnd=thumbEnd;this.doubled=doubled;this.small=small;}
		/** For a bar of the cicn's thickness {@code a} drawn {@code t} thick: the layout it is shrunk from. */
		Bar virtual;
		/** Double arrows put together from the single cicn (the scheme has no double one). */
		boolean composite;
		Bar scaled(int len,int t,int a){
			int[][] arr=new int[arrows.length][];for(int i=0;i<arrows.length;i++)arr[i]=new int[]{map(arrows[i][0],t,a,len),map(arrows[i][1],t,a,len),arrows[i][2]};
			Bar b=new Bar(vertical,len,arr,map(trackStart,t,a,len),map(trackEnd,t,a,len),map(thumbStart,t,a,len),map(thumbEnd,t,a,len),doubled,small);
			b.virtual=this;return b;
		}
		private static int map(int p,int t,int a,int len){return Math.min(len,(int)Math.ceil(p*(double)t/a));}
		boolean hasThumb(){return thumbEnd>thumbStart;}
		int part(int pos){
			for(int[] a:arrows)if(pos>=a[0]&&pos<a[1])return a[2];
			if(!hasThumb())return 0;
			if(pos<thumbStart)return 22;if(pos>=thumbEnd)return 23;return IN_THUMB;
		}
		/** Centre of the first arrow of that part. */
		int arrowCentre(int part){for(int[] a:arrows)if(a[2]==part)return (a[0]+a[1])/2;return part==IN_UP?0:length-1;}
	}
	private int[] trackIds(boolean small,boolean vertical){return TRACKS[small?1:0][vertical?1:0];}
	/** The small set for a bar no thicker than its cicns (11 pixels); a thicker one has the regular cicns, cut off at its edge. */
	private boolean useSmall(int thickness,boolean vertical){Img small=element(trackIds(true,vertical)[T_NORMAL],false);return small!=null&&thickness<=(vertical?small.w:small.h);}

	/** The scheme's layout of a scroll bar; null when the scheme has no track for it (the native bar is drawn). */
	Bar bar(int left,int top,int right,int bottom,int min,int max,int value){
		boolean vertical=bottom-top>=right-left;int length=vertical?bottom-top:right-left,thickness=vertical?right-left:bottom-top;
		boolean small=useSmall(thickness,vertical);int[] ids=trackIds(small,vertical);
		Img normal=element(ids[T_NORMAL],false);if(normal==null)return null;
		int a=vertical?normal.w:normal.h,single=vertical?normal.h:normal.w,section=single-2*a;
		if(thickness<a&&thickness>0&&!small){ // a bar thinner than the cicns: the bar a cicn thick, shrunk across and along
			int lv=(int)Math.ceil(length*(double)a/thickness);
			Bar vb=vertical?bar(0,0,a,lv,min,max,value):bar(0,0,lv,a,min,max,value);
			return vb==null?null:vb.scaled(length,thickness,a);
		}
		if(section<1||length<2*a)return null;
		Img dbl=DOUBLE_ARROWS?element(ids[T_DOUBLE],false):null;
		int[][] arrows;int trackStart,trackEnd;boolean doubled=false;
		// the double cicn's arrows share their frame lines: a-1 long each, the rest of it is the track section
		int dlen=dbl==null?0:vertical?dbl.h:dbl.w,arrowD=a-1;
		// without a double cicn (Aqua III) the pairs are put together from the single cicn's arrows
		boolean composite=DOUBLE_ARROWS&&dbl==null&&length>=8*a&&single>2*a;
		if(composite||dbl!=null&&arrowD>0&&dlen>4*arrowD&&length>=8*a){
			doubled=true;int pair=2*arrowD;
			arrows=new int[][]{{0,arrowD,IN_UP},{arrowD,pair,IN_DOWN},{length-pair,length-arrowD,IN_UP},{length-arrowD,length,IN_DOWN}};
			trackStart=pair;trackEnd=length-pair;
		}else{
			arrows=new int[][]{{0,a,IN_UP},{length-a,length,IN_DOWN}};trackStart=a;trackEnd=length-a;
		}
		int thumbStart=0,thumbEnd=0;
		Img thumb=element(thumbId(small,vertical,false),false);
		if(thumb!=null&&max>min){
			// the thumb's own frame lines lie over the arrows' at both ends of its travel; the position is a Fixed fraction of it,
			// rounded (FixRatio, then FixRound: 94.497 -> 94 where a double's rounding gives 95)
			// (put-together pairs end in a track line of their own, so the thumb stays between them: Aqua III)
			int over=composite?0:1,tl=vertical?thumb.h:thumb.w,travel=trackEnd-trackStart+2*over-tl;
			if(travel>=0){int v=Math.max(min,Math.min(max,value));long frac=((long)(v-min)<<16)/(max-min);thumbStart=trackStart-over+(int)((travel*frac+0x8000)>>16);thumbEnd=thumbStart+tl;}
		}
		Bar result=new Bar(vertical,length,arrows,trackStart,trackEnd,thumbStart,thumbEnd,doubled,small);result.composite=composite;
		return result;
	}
	/** The thickness of the regular cicns a bar is laid out with. */
	private int thicknessOf(Bar b){Img n=element(trackIds(b.small,b.vertical)[T_NORMAL],false);return n==null?16:b.vertical?n.w:n.h;}
	/**
	 * A scheme with thumbs but no scroll bar tracks of its own (Apple platinum, whose bars are the standard ones) puts its
	 * thumb, in the accent colour, over the standard bar's proportional one: the end lines as they are (up to the first line
	 * that repeats), the middle (the grip) centred, the repeating line between; stretched across to the bar's thickness.
	 */
	boolean drawThumbOver(Graphics2D g,int x,int y,int w,int h,boolean vertical,boolean pressed){
		Img t=element(thumbId(false,vertical,pressed),false);if(t==null&&pressed)t=element(thumbId(false,vertical,false),false);
		if(t==null||w<=0||h<=0)return false;
		prepare(g);
		int n=vertical?t.h:t.w,across=vertical?t.w:t.h,len=vertical?h:w,thick=vertical?w:h;
		if(len<n||n<4){if(vertical)blit(g,t,0,0,t.w,t.h,x,y,w,h);else blit(g,t,0,0,t.w,t.h,x,y,w,h);return true;}
		int s=1;while(s<n-2&&!t.sameLine(vertical,s,s+1,0,across))s++;
		int e=n-2;while(e>s+1&&!t.sameLine(vertical,e,e-1,0,across))e--;
		int tail=n-e,mid=e-s-1,at=Math.max(s+1,(len-mid)/2);
		for(int p=s+1;p<len-tail;p++)line(g,t,vertical,p<len/2?s:e,x,y,p,thick);
		for(int i=0;i<mid&&at+i<len-tail;i++)line(g,t,vertical,s+1+i,x,y,at+i,thick);
		for(int i=0;i<=s;i++)line(g,t,vertical,i,x,y,i,thick);
		for(int i=0;i<tail;i++)line(g,t,vertical,e+i,x,y,len-tail+i,thick);
		return true;
	}
	private static void line(Graphics2D g,Img t,boolean vertical,int src,int x,int y,int at,int thick){
		if(vertical)blit(g,t,0,src,t.w,1,x,y+at,thick,1);else blit(g,t,src,0,1,t.h,x+at,y,1,thick);
	}
	private static int thumbId(boolean small,boolean vertical,boolean pressed){
		return small?(vertical?(pressed?SMALL_THUMB_V_PRESSED:SMALL_THUMB_V):(pressed?SMALL_THUMB_H_PRESSED:SMALL_THUMB_H)):(vertical?(pressed?THUMB_V_PRESSED:THUMB_V):(pressed?THUMB_H_PRESSED:THUMB_H));
	}

	/**
	 * CDEF 1's scroll bar in the scheme; false when the scheme has no track for it. In an inactive window the bar is the
	 * inactive track; an inactive control (hilite 255) or one with nothing to scroll is the "nothing to scroll" one.
	 */
	boolean drawScrollBar(Graphics2D g,int left,int top,int right,int bottom,int value,int min,int max,int hilite,boolean disabled,boolean windowActive){
		Bar bar=bar(left,top,right,bottom,min,max,value);if(bar==null)return false;
		prepare(g);
		if(bar.virtual!=null){ // drawn a cicn thick and shrunk (nearest pixel, as CopyBits stretches)
			Bar vb=bar.virtual;int vw=vb.vertical?thicknessOf(vb):vb.length,vh=vb.vertical?vb.length:thicknessOf(vb);
			BufferedImage off=new BufferedImage(vw,vh,BufferedImage.TYPE_INT_ARGB);Graphics2D og=off.createGraphics();
			try{drawScrollBar(og,0,0,vw,vh,value,min,max,hilite,disabled,windowActive);}finally{og.dispose();}
			g.drawImage(off,left,top,right,bottom,0,0,vw,vh,null);
			return true;
		}
		boolean v=bar.vertical;int across=v?right-left:bottom-top;int[] ids=trackIds(bar.small,v);
		if(!windowActive||disabled||max<=min){
			Img plain=element(ids[!windowActive?T_INACTIVE:bar.doubled?T_DOUBLE_NOTHING:T_NOTHING],false);
			if(plain==null&&bar.doubled)plain=element(ids[T_NOTHING],false);
			if(plain==null)plain=element(ids[T_INACTIVE],false);
			if(plain==null)return false;
			int plainId=ids[!windowActive?T_INACTIVE:bar.doubled?T_DOUBLE_NOTHING:T_NOTHING];
			Img pat=pattern(plainId);if(pat==null)pat=pattern(ids[!windowActive?T_INACTIVE:T_NOTHING]);
			trackStrip(g,plain,pat,v,0,v?plain.h:plain.w,left,top,bar.length,across,left,top);
			return true;
		}
		boolean dbl=bar.doubled&&!bar.composite;
		Img normal=element(ids[dbl?T_DOUBLE:T_NORMAL],false),pressed=element(ids[dbl?T_DOUBLE_PRESSED:T_PRESSED],false);
		drawArrowsAndTrack(g,normal,pressed==null?normal:pressed,bar,left,top,across,hilite,true);
		if(bar.hasThumb()){
			Img thumb=element(thumbId(bar.small,v,hilite==IN_THUMB),false);if(thumb==null)thumb=element(thumbId(bar.small,v,false),false);
			if(v)blit(g,thumb,0,0,thumb.w,thumb.h,left,top+bar.thumbStart,thumb.w,thumb.h);
			else blit(g,thumb,0,0,thumb.w,thumb.h,left+bar.thumbStart,top,thumb.w,thumb.h);
		}
		return true;
	}
	/** The arrows (pressed ones from {@code pressedImg}) and the track on both sides of the thumb. */
	/**
	 * The arrows (the pressed one from {@code pressedImg}) and the track: the whole track from the cicn's section (from the
	 * pressed cicn while any part is pressed: Onyx lights its track), then the single cicn's leading lines again just past the
	 * thumb and its trailing lines just before it, the shadows it casts (Ice's track restarts there, Sherbet's runs on
	 * unbroken under its round thumb, Onyx's recessed track is framed again round it).
	 */
	private void drawArrowsAndTrack(Graphics2D g,Img img,Img pressedImg,Bar bar,int left,int top,int across,int hilite,boolean thumb){
		boolean v=bar.vertical;boolean pressed=hilite!=0&&hilite!=255;
		if(bar.composite){drawComposite(g,img,pressedImg,bar,left,top,across,hilite,thumb);return;}
		for(int[] a:bar.arrows){ // each arrow from its place in the cicn: the leading ones from its start, the others from its end
			int size=a[1]-a[0];boolean atStart=a[0]<bar.trackStart;
			Img from=hilite==a[2]?pressedImg:img;int len=v?from.h:from.w;
			sliceAlong(g,from,v,atStart?a[0]:len-(bar.length-a[0]),size,left,top,a[0],size,across);
		}
		Img track=pressed?pressedImg:img;int len=v?track.h:track.w;
		int s0=bar.doubled?bar.arrows[1][1]:bar.arrows[0][1],s1=len-(bar.length-bar.trackEnd);
		int[] ids=trackIds(bar.small,v);
		Img pat=pattern(ids[bar.doubled?(pressed?T_DOUBLE_PRESSED:T_DOUBLE):(pressed?T_PRESSED:T_NORMAL)]);
		if(pat==null)pat=pattern(ids[pressed?T_PRESSED:T_NORMAL]);
		if(bar.trackEnd>bar.trackStart){
			if(v)trackStrip(g,track,pat,true,s0,s1,left,top+bar.trackStart,bar.trackEnd-bar.trackStart,across,left,top);
			else trackStrip(g,track,pat,false,s0,s1,left+bar.trackStart,top,bar.trackEnd-bar.trackStart,across,left,top);
		}
		if(!thumb||!bar.hasThumb()||separator(track,v,s0,s1)>=0||unifiedTrack())return;
		// the single cicn's track section again round the thumb: its leading lines just past it, its trailing ones just before
		Img single=element(trackIds(bar.small,v)[pressed?T_PRESSED:T_NORMAL],false);if(single==null)single=track;
		int a=v?single.w:single.h,t0=a,t1=(v?single.h:single.w)-a;
		if(t1<=t0)return;
		int[] run=equalRun(single,v,t0,t1);
		int cap=Math.min(run[0]-t0,bar.trackEnd-bar.thumbEnd);
		if(cap>0)sliceAlong(g,single,v,t0,cap,left,top,bar.thumbEnd,cap,across);
		int end=Math.min(t1-run[1],bar.thumbStart-bar.trackStart);
		if(end>0)sliceAlong(g,single,v,t1-end,end,left,top,bar.thumbStart-end,end,across);
	}

	/**
	 * Double arrows from the single cicn (measured on Aqua III, 65 long, a=16): at the start its up arrow, then its down arrow
	 * without the first and last lines; at the end its up arrow without the first line, then its down arrow without the first;
	 * the track between from the section widened by a line at each end.
	 */
	private void drawComposite(Graphics2D g,Img img,Img pressedImg,Bar bar,int left,int top,int across,int hilite,boolean thumb){
		boolean v=bar.vertical;int a=v?img.w:img.h,len=v?img.h:img.w,pair=2*(a-1),L=bar.length;
		Img up=hilite==IN_UP?pressedImg:img,down=hilite==IN_DOWN?pressedImg:img;
		sliceAlong(g,up,v,0,a,left,top,0,a,across);
		sliceAlong(g,down,v,len-a+1,a-2,left,top,a,a-2,across);
		Img track=hilite!=0&&hilite!=255?pressedImg:img;int s0=a-1,s1=len-a+1;
		if(bar.trackEnd>bar.trackStart){
			Img pat=pattern(trackIds(bar.small,v)[hilite!=0&&hilite!=255?T_PRESSED:T_NORMAL]);
			if(v)trackStrip(g,track,pat,true,s0,s1,left,top+bar.trackStart,bar.trackEnd-bar.trackStart,across,left,top);
			else trackStrip(g,track,pat,false,s0,s1,left+bar.trackStart,top,bar.trackEnd-bar.trackStart,across,left,top);
		}
		sliceAlong(g,up,v,1,a-1,left,top,L-pair,a-1,across);
		sliceAlong(g,down,v,len-a+1,a-1,left,top,L-(a-1),a-1,across);
		if(!thumb||!bar.hasThumb()||separator(track,v,s0,s1)>=0||unifiedTrack())return;
		int[] run=equalRun(track,v,a,len-a);int cap=Math.min(run[0]-a,bar.trackEnd-bar.thumbEnd);
		if(cap>0)sliceAlong(g,track,v,a,cap,left,top,bar.thumbEnd,cap,across);
		int end=Math.min(len-a-run[1],bar.thumbStart-bar.trackStart);
		if(end>0)sliceAlong(g,track,v,len-a-end,end,left,top,bar.thumbStart-end,end,across);
	}

	// ------------------------------------------------------------------ progress bars

	static final int PROGRESS_FRAME_2=-10080,PROGRESS_FILL_2=-10079,PROGRESS_TRACK_2=-10078,PROGRESS_FRAME_2_INACTIVE=-10077,PROGRESS_FILL_2_INACTIVE=-10076,PROGRESS_TRACK_2_INACTIVE=-10075;
	/** Whether the scheme has a progress bar of either format. */
	boolean hasProgressBar(){return newProgressBar()||element(PROGRESS_TRACK)!=null&&element(PROGRESS_FILL)!=null;}
	/** The 2.3 elements: a frame unlike the old fill (Apple platinum's and Aqua III's -10080..-10071 are accent colour fills of
	 *  the old kind, the size of -10223). */
	private boolean newProgressBar(){
		Img frame=element(PROGRESS_FRAME_2,false),fill=element(PROGRESS_FILL,false);
		return frame!=null&&element(PROGRESS_TRACK_2,false)!=null&&(fill==null||fill.w!=frame.w||fill.h!=frame.h);
	}
	/** The determinate progress bar in the scheme; false without its track and fill. */
	boolean drawProgressBar(Graphics2D g,int left,int top,int right,int bottom,int value,int min,int max,boolean disabled){
		if(newProgressBar())return drawProgressBar2(g,left,top,right,bottom,value,min,max,disabled);
		Img track=element(PROGRESS_TRACK),fill=element(PROGRESS_FILL);
		if(track==null||fill==null)return false;
		prepare(g);
		// the frame columns the fill shares with the track at each end
		int f=0;while(f<Math.min(track.w,fill.w)/2&&track.sameLineAt(fill,track.w-1-f,fill.w-1-f))f++;
		if(f==0){ // no frame of its own (Aqua III): 2 pixels in from each side, a taller cicn centred on the control
			int h=track.h,y=top+Math.min(0,Math.floorDiv(bottom-top-h,2)),l=left+2,w=right-left-4;if(w<=0)return true;
			int range=max-min,n=range<=0?0:(int)((long)w*(Math.max(min,Math.min(max,value))-min)/range);
			Shape clip=g.getClip();g.clipRect(left,top,right-left,bottom-top);
			try{
				strip(g,track,false,0,track.w,l+n,y,w-n,h);
				if(n>0){ // its first and last columns at the ends, the ones between repeated
					int cap=Math.min(1,fill.w/2),mid=Math.max(1,fill.w-2*cap);
					if(n>2*cap){tile(g,fill,cap,0,mid,h,l+cap,y,n-2*cap,h);blit(g,fill,0,0,cap,h,l,y,cap,h);}
					blit(g,fill,fill.w-Math.min(cap,n),0,Math.min(cap,n),h,l+n-Math.min(cap,n),y,Math.min(cap,n),h);
				}
			}finally{g.setClip(clip);}
			return true;
		}
		int w=right-left,h=Math.min(bottom-top,track.h);if(w<=0||h<=0)return true;
		int inner=w-2*f,range=max-min,n=range<=0?0:(int)((long)inner*(Math.max(min,Math.min(max,value))-min)/range);
		Shape clip=g.getClip();g.clipRect(left,top,w,h);
		try{
			strip(g,track,false,0,track.w,left+n,top,w-n,h);
			if(n>0){
				int own0=f,own1=fill.w-f;int[] run=equalRun(fill,false,own0,own1);int start=run[0]-own0,end=own1-run[1];
				sliceAlong(g,fill,false,0,f,left,top,0,f,h);
				if(n>start+end)strip(g,fill,false,own0,own1,left+f,top,n,h);
				else{ // the start dropped: the middle, the end on the right
					for(int p=0;p<n-Math.min(end,n);p++)sliceAlong(g,fill,false,run[0],1,left+f,top,p,1,h);
					int e=Math.min(end,n);sliceAlong(g,fill,false,own1-e,e,left+f,top,n-e,e,h);
				}
			}
		}finally{g.setClip(clip);}
		return true;
	}

	/**
	 * The Kaleidoscope 2.3 progress bar (Antique, Scherzo!, Apple platinum): a frame (-10080, cut by its 'cinf', its inside
	 * clear) round the track (-10078: its start and end columns, the middle tiled) with the filled part from the left
	 * (-10079 the same way), all the frame's height, top aligned; -10077..-10075 when inactive. Not checked against the real
	 * engine, which drew only the frame in the VM.
	 */
	private boolean drawProgressBar2(Graphics2D g,int left,int top,int right,int bottom,int value,int min,int max,boolean disabled){
		Img frame=element(disabled?PROGRESS_FRAME_2_INACTIVE:PROGRESS_FRAME_2,false),track=element(disabled?PROGRESS_TRACK_2_INACTIVE:PROGRESS_TRACK_2,false),fill=element(disabled?PROGRESS_FILL_2_INACTIVE:PROGRESS_FILL_2,false);
		if(frame==null)frame=element(PROGRESS_FRAME_2,false);if(track==null)track=element(PROGRESS_TRACK_2,false);if(fill==null)fill=element(PROGRESS_FILL_2,false);
		if(frame==null||track==null)return false;
		prepare(g);
		Cinf fc=cinf(disabled?PROGRESS_FRAME_2_INACTIVE:PROGRESS_FRAME_2,cinf(PROGRESS_FRAME_2,new Cinf(2,2,true,0,0,0,0,0,0,0,0)));
		int w=right-left,h=Math.min(bottom-top,frame.h);if(w<=0||h<=0)return true;
		int side=fc.side,ix=left+side,iy=top+side,iw=w-2*side,ih=Math.min(track.h,h-2*side);
		Shape clip=g.getClip();g.clipRect(left,top,w,h);
		try{
			if(iw>0&&ih>0){
				capped(g,track,cinf(PROGRESS_TRACK_2,null),ix,iy,iw,ih);
				int range=max-min,n=range<=0||fill==null?0:(int)((long)iw*(Math.max(min,Math.min(max,value))-min)/range);
				if(n>0)capped(g,fill,cinf(PROGRESS_FILL_2,null),ix,iy,n,Math.min(fill.h,ih));
			}
			frame(g,frame,new Cinf(fc.corner,fc.side,fc.tileSides,0,0,0,0,0,0,0,0),left,top,w,h);
		}finally{g.setClip(clip);}
		return true;
	}
	/** An element laid along a length: its start and end columns (the 'cinf' corner size, else a third of it), the middle tiled. */
	private static void capped(Graphics2D g,Img img,Cinf c,int x,int y,int w,int h){
		int cap=c!=null?Math.min(c.corner,img.w/2):img.w/3,mid=Math.max(1,img.w-2*cap);
		if(w<=2*cap){int l=w/2;blit(g,img,0,0,l,h,x,y,l,h);blit(g,img,img.w-(w-l),0,w-l,h,x+l,y,w-l,h);return;}
		tile(g,img,cap,0,mid,h,x+cap,y,w-2*cap,h);
		blit(g,img,0,0,cap,h,x,y,cap,h);blit(g,img,img.w-cap,0,cap,h,x+w-cap,y,cap,h);
	}

	// ------------------------------------------------------------------ check boxes and radio buttons

	static final int CHECK_EMPTY=-9504,CHECK_ON=-9501,CHECK_MIXED=-9498,RADIO_OFF=-9492,RADIO_ON=-9489,RADIO_MIXED=-9486; // +0 inactive, +1 active, +2 pressed
	private static final Color DISABLED_INK=new Color(0x88,0x88,0x88);
	/**
	 * CDEF 0's check box or radio button (also the Appearance ones) in the scheme; false without its cicns. The box is
	 * centred on a 17-pixel square at the left and vertically, the title starts 18 pixels in (measured on Scherzo! and
	 * Aqua III: 13x11, 12x12 and 15x17 boxes).
	 */
	boolean drawCheckOrRadio(Graphics2D g,int left,int top,int right,int bottom,String title,int value,boolean radio,boolean pressed,boolean disabled,FontMetrics fm,Color background){
		int base=radio?(value==2?RADIO_MIXED:value!=0?RADIO_ON:RADIO_OFF):(value==2?CHECK_MIXED:value!=0?CHECK_ON:CHECK_EMPTY);
		int state=disabled?0:pressed?2:1;
		Img img=element(base+state,false);if(img==null)img=element(base+1,false);
		Img icon=img==null?oldIndicator(radio,value,state):null;if(img==null&&icon==null)return false;
		prepare(g);
		// without a title only the box's part (as ControlManager68k.drawCheckOrRadio): ApplicationMenu 4.0's check boxes are
		// title-less and as wide as the static texts drawn in them before the controls - the labels vanished under the erase
		if(background!=null){g.setColor(background);g.fillRect(left-1,top,title==null||title.isEmpty()?Math.min(right-left+1,18):right-left+1,bottom-top);}
		if(icon!=null)blit(g,icon,0,0,icon.w,icon.h,left-1,top+Math.floorDiv(bottom-top-12,2),icon.w,icon.h);
		else{int x=left+(17-img.w)/2,y=top+Math.floorDiv(bottom-top-img.h,2);blit(g,img,0,0,img.w,img.h,x,y,img.w,img.h);}
		if(title!=null&&!title.isEmpty()){ // a disabled title is grey text over the background: half its colour
			int bg=background==null?0xffffff:background.getRGB()&0xffffff;
			g.setColor(disabled?new Color((bg>>17&0x7f)<<16|(bg>>9&0x7f)<<8|(bg>>1&0x7f)):Color.BLACK);
			String[] lines=title.split("\r",-1);int lh=fm.getHeight();int ty=top+(bottom-top-lh*lines.length)/2+fm.getAscent();
			for(String line:lines){Fonts68k.drawString(g,fm,line,left+18,ty);ty+=lh;}
		}
		return true;
	}
	/** The part of a check box or radio button its value change redraws (the box), for the indicator-only redraw. */
	int checkBoxWidth(boolean radio){Img img=element(radio?RADIO_ON+1:CHECK_ON+1,false);if(img==null&&oldIndicator(radio,1,1)!=null)return 17;return img==null?0:Math.max(17,(17-img.w)/2+img.w);}

	static final int OLD_CHECK=-10240,OLD_RADIO=-10224;
	/**
	 * The check box or radio button of an older scheme (Sherbet, Ice, Onyx, System 7, Al-Azul): 'ics8' icons with their 'ics#'
	 * masks, -10240 (check) / -10224 (radio) + 4 x state (0 inactive, 1 active, 2 pressed) + value (0 off, 1 on, 2 mixed); the
	 * control panel's own (no mixed ones) when the scheme has none. The 12-pixel box is the icon's columns 2.. and rows 0..,
	 * drawn 1 pixel left of the control and centred on its height (Sherbet's in ResEdit's Open dialog, real engine).
	 */
	private Img oldIndicator(boolean radio,int value,int state){
		int base=radio?OLD_RADIO:OLD_CHECK,v=Math.min(2,Math.max(0,value));
		int[] tries={base+4*state+v,base+4+v,base+4*state+Math.min(v,1),base+4+Math.min(v,1)};
		for(ResourceFile68k file:new ResourceFile68k[]{scheme,panel}){
			if(file==null)continue;
			if(file==panel&&file.get(ICS8,base+4)==null)continue;
			for(int id:tries){Img img=icon(file,id);if(img!=null)return img;}
			if(file==scheme&&scheme.get(ICS8,base+4)!=null)return null;
		}
		return null;
	}
	private static final int ICS8=0x69637338,ICS_MASK=0x69637323;
	private Img icon(ResourceFile68k file,int id){
		Integer key=id^(file==scheme?0x10000000:0x08000000);
		Img img=images.get(key);if(img!=null||missing.contains(key))return img;
		ResourceFile68k.Resource r=file.get(ICS8,id),m=file.get(ICS_MASK,id);
		if(r!=null&&r.data().length>=256){BufferedImage b=FinderIcons68k.decodeIcs8(r.data(),m==null?null:m.data());int[] argb=b.getRGB(0,0,16,16,null,0,16);img=new Img(16,16,argb);}
		if(img==null)missing.add(key);else images.put(key,img);
		return img;
	}

	// ------------------------------------------------------------------ pop-up buttons

	static final int POPUP_TEXT=-8208,POPUP_ARROW=-8205,POPUP_GLYPH_LARGE=-8199,POPUP_GLYPH_SMALL=-8196; // +0 inactive, +1 active, +2 pressed
	/** The arrow part's width (Sherbet's folder pop-up in the System's Standard File dialog, real engine). */
	private static final int POPUP_ARROW_WIDTH=22;
	/** Whether the scheme draws pop-up buttons. */
	boolean hasPopup(){return element(POPUP_TEXT+1,false)!=null&&element(POPUP_ARROW+1,false)!=null;}
	/**
	 * A pop-up menu button (the box right of its title): the text part and the arrow part each a frame of its own element,
	 * the arrow glyph in the arrow part's middle, the current item in the text pixel's colour. Returns that
	 * colour (a caller drawing its own contents uses it: Standard File's folder pop-up), or null without the elements.
	 */
	Color drawPopupBox(Graphics2D g,int left,int top,int right,int bottom,String text,boolean pressed,boolean disabled,FontMetrics fm){
		int state=disabled?0:pressed?2:1;
		Img body=element(POPUP_TEXT+state,false),arrow=element(POPUP_ARROW+state,false);
		if(body==null)body=element(POPUP_TEXT+1,false);if(arrow==null)arrow=element(POPUP_ARROW+1,false);
		if(body==null||arrow==null)return null;
		prepare(g);
		int w=right-left,h=bottom-top;if(w<=0||h<=0)return Color.BLACK;
		Cinf bc=cinf(POPUP_TEXT+state,cinf(POPUP_TEXT+1,new Cinf(3,3,false,0,0,4,4,7,7,0,0))),ac=cinf(POPUP_ARROW+state,cinf(POPUP_ARROW+1,new Cinf(3,3,false,0,0,4,4,0,0,0,0)));
		// the two parts are frames of their own side by side, the arrow part 22 pixels wide (its shadow column included) over
		// the text part's last column (Sherbet: the arrow part's dark left side is the divider; Ice, Onyx: one shared line)
		int aw=Math.min(w/2,POPUP_ARROW_WIDTH),split=right-aw;
		frame(g,body,bc,left,top,split-left+1,h);
		frame(g,arrow,ac,split,top,aw,h);
		Img glyph=element((h>=18?POPUP_GLYPH_LARGE:POPUP_GLYPH_SMALL)+state,false);if(glyph==null)glyph=element(POPUP_GLYPH_LARGE+state,false);
		if(glyph!=null)blit(g,glyph,0,0,glyph.w,glyph.h,split+(aw-1-glyph.w)/2,top+(h-glyph.h)/2,glyph.w,glyph.h);
		int ink=body.pixel(bc.textY,bc.textX);Color inkColor=disabled?DISABLED_INK:new Color((ink>>>24)!=0?ink:0xff000000);
		if(text!=null&&!text.isEmpty()){
			int room=split-left-bc.side-6;
			if(fm.stringWidth(text)>room){int n=text.length();while(n>0&&fm.stringWidth(text.substring(0,n)+"\u2026")>room)n--;text=text.substring(0,n)+"\u2026";}
			g.setColor(inkColor);
			Shape c2=g.getClip();g.clipRect(left,top,split-left,h);
			try{Fonts68k.drawString(g,fm,text,left+bc.side+4,top+(h-fm.getHeight())/2+fm.getAscent());}finally{g.setClip(c2);}
		}
		return inkColor;
	}

	// ------------------------------------------------------------------ tabs

	static final int TAB_REAR=-9984,TAB_FRONT=-9981,TAB_PANE=-9978,SMALL_TAB_REAR=-9976,SMALL_TAB_FRONT=-9973,SMALL_TAB_PANE=-9970;
	/** Whether the scheme has the tabs of that size. */
	boolean hasTabs(boolean small){return element((small?SMALL_TAB_REAR:TAB_REAR)+1,false)!=null&&element((small?SMALL_TAB_FRONT:TAB_FRONT)+1,false)!=null&&element((small?SMALL_TAB_PANE:TAB_PANE)+1,false)!=null;}
	/**
	 * One tab (top tabs): {@code style} 0 normal, 1 pressed, 2 disabled (behind), 3 front, 4 front disabled - the tab's cicn
	 * cut by its 'cinf' to the tab's width at its own height, its bottom on the pane's top edge. Returns the title's colour.
	 */
	Color drawTab(Graphics2D g,int left,int bottom,int right,int style,boolean small){
		int id=style>=3?(small?SMALL_TAB_FRONT:TAB_FRONT)+(style==4?0:1):(small?SMALL_TAB_REAR:TAB_REAR)+(style==2?0:style==1?2:1);
		Img img=element(id,false);if(img==null)img=element(style>=3?(small?SMALL_TAB_FRONT:TAB_FRONT)+1:(small?SMALL_TAB_REAR:TAB_REAR)+1,false);
		if(img==null)return Color.BLACK;
		prepare(g);Cinf c=cinf(id,new Cinf(Math.min(8,img.w/3),Math.min(8,img.w/3),false,0,0,img.h/2,img.w/2,0,0,0,0));
		int h=img.h,y=bottom-h+(style>=3?1:0);
		frame(g,img,new Cinf(c.corner,c.side,c.tileSides,c.anchor,0,c.bgY,c.bgX,0,0,0,0),left,y,right-left,h);
		int ink=c.textY>0||c.textX>0?img.pixel(c.textY,c.textX):0;
		return style==2||style==4?DISABLED_INK:new Color((ink>>>24)!=0?ink:0xff000000);
	}
	/** The tab control's pane (below the tabs): its frame element, the inside in its background pattern or pixel's colour. */
	boolean drawTabPane(Graphics2D g,int top,int left,int bottom,int right,boolean active,boolean small){
		int id=(small?SMALL_TAB_PANE:TAB_PANE)+(active?1:0);Img img=element(id,false);if(img==null)img=element((small?SMALL_TAB_PANE:TAB_PANE)+1,false);if(img==null)return false;
		prepare(g);Cinf c=cinf(id,new Cinf(3,3,false,0,0,4,4,0,0,0,0));
		Img pat=c.pattern!=0?pattern(c.pattern):null;
		if(pat!=null)fillPattern(g,pat,left+c.side,top+c.side,right-left-2*c.side,bottom-top-2*c.side,left,top);
		frame(g,img,pat!=null?new Cinf(c.corner,c.side,c.tileSides,c.anchor,0,0,0,0,0,0,0):c,left,top,right-left,bottom-top);
		return true;
	}

	// ------------------------------------------------------------------ sliders

	/** The first ID of a slider's elements: inactive, normal, pressed track, the thumbs (inactive, normal, pressed, ghost), ticks. */
	private static int sliderBase(boolean horizontal,int dir){return horizontal?(dir==2?-10144:dir==1?-10138:-10132):(dir==2?-10128:dir==1?-10122:-10116);}
	/** {thumb length along the track, thumb thickness, track inset (its cap)}, or null when the scheme has no such slider. */
	int[] sliderMetrics(boolean horizontal,int dir){
		int base=sliderBase(horizontal,dir);Img track=element(base+1,false),thumbs=element(base+3,false);if(track==null||thumbs==null)return null;
		Cinf c=cinf(base+1,new Cinf(5,16,true,0,0,0,0,0,0,0,0));
		return horizontal?new int[]{thumbs.w,thumbs.h/4,c.corner}:new int[]{thumbs.h,thumbs.w/4,c.corner};
	}
	/**
	 * An Appearance slider (Aqua III, Scherzo!, Ice measured): the track cut by its 'cinf' along the whole control, centred on
	 * the thumb's thickness; the thumb (the sheet's 0 inactive, 1 normal, 2 pressed, 3 ghost) at the control's top or left.
	 */
	boolean drawSlider(Graphics2D g,int top,int left,int bottom,int right,boolean horizontal,int dir,int thumbStart,int ghostStart,boolean on,boolean pressed){
		int base=sliderBase(horizontal,dir);Img track=element(base+(on?pressed?2:1:0),false),thumbs=element(base+3,false);
		if(track==null)track=element(base+1,false);if(track==null||thumbs==null)return false;
		prepare(g);int[] m=sliderMetrics(horizontal,dir);int tl=m[0],tt=m[1];
		Cinf c=cinf(base+1,new Cinf(5,16,true,0,0,0,0,0,0,0,0));
		if(horizontal){int ty=top+(tt-track.h)/2;stripCapped(g,track,c.corner,true,left,ty,right-left,track.h);}
		else{int tx=left+(tt-track.w)/2;stripCapped(g,track,c.corner,false,tx,top,track.w,bottom-top);}
		int index=on?pressed?2:1:0;
		if(ghostStart>=0)drawThumb(g,thumbs,3,horizontal,tl,tt,ghostStart,top,left);
		drawThumb(g,thumbs,index,horizontal,tl,tt,thumbStart,top,left);
		return true;
	}
	private static void drawThumb(Graphics2D g,Img sheet,int index,boolean horizontal,int tl,int tt,int at,int top,int left){
		if(horizontal)blit(g,sheet,0,index*tt,tl,tt,at,top,tl,tt);else blit(g,sheet,index*tt,0,tt,tl,left,at,tt,tl);
	}
	/**
	 * A track element along a length: {@code cap} lines at each end, the lines between them tiled - when the caps overlap in
	 * the cicn (Aqua III: 16 long, caps of 10) the one line just past the first cap.
	 */
	private static void stripCapped(Graphics2D g,Img img,int cap,boolean horizontal,int x,int y,int w,int h){
		int len=horizontal?img.w:img.h,along=horizontal?w:h;cap=Math.min(cap,len);int from=Math.min(cap,len-1),mid=Math.max(1,len-2*cap);
		int left=Math.min(cap,along/2),right=Math.min(cap,along-left);
		if(horizontal){tile(g,img,from,0,mid,img.h,x+left,y,along-left-right,h);blit(g,img,0,0,left,img.h,x,y,left,h);blit(g,img,len-right,0,right,img.h,x+along-right,y,right,h);}
		else{tile(g,img,0,from,img.w,mid,x,y+left,w,along-left-right);blit(g,img,0,0,img.w,left,x,y,w,left);blit(g,img,0,len-right,img.w,right,x,y+along-right,w,right);}
	}

	// ------------------------------------------------------------------ bevel buttons, placards, headers, little arrows, triangles

	static final int BEVEL_SMALL=-10176,BEVEL_NORMAL=-10167,BEVEL_LARGE=-10158,PLACARD=-9792,HEADER=-9568,LITTLE_ARROWS=-10048,
		TRIANGLE_RIGHT=-10112,TRIANGLE_DOWN=-10104,TRIANGLE_LEFT=-10096;
	/**
	 * A frame element of the scheme over a rectangle (cut by its 'cinf', the inside in its background pixel's colour or its
	 * pattern): bevel buttons (size base, then off inactive/normal/pressed, on inactive/normal/pressed), placards (inactive,
	 * normal, pressed), window headers (inactive, active). Returns the text pixel's colour, or null without the element.
	 */
	Color drawFrameElement(Graphics2D g,int id,int top,int left,int bottom,int right){
		Img img=element(id,false);if(img==null)return null;
		prepare(g);Cinf c=cinf(id,new Cinf(Math.min(4,img.w/3),Math.min(4,img.w/3),false,0,0,img.h/2,img.w/2,0,0,0,0));
		Img pat=c.pattern!=0&&c.pattern!=id?pattern(c.pattern):null;
		if(pat!=null)fillPattern(g,pat,left+c.side,top+c.side,right-left-2*c.side,bottom-top-2*c.side,left,top);
		frame(g,img,pat!=null?new Cinf(c.corner,c.side,c.tileSides,c.anchor,0,0,0,c.textY,c.textX,0,0):c,left,top,right-left,bottom-top);
		int ink=c.textY>0||c.textX>0?img.pixel(c.textY,c.textX):0xff000000;
		return new Color((ink>>>24)!=0?ink:0xff000000);
	}
	/** The bevel button's element: {@code size} 0 small, 1 normal, 2 large; {@code state} 0 inactive, 1 normal, 2 pressed. */
	static int bevelId(int size,boolean on,int state){return (size==0?BEVEL_SMALL:size==1?BEVEL_NORMAL:BEVEL_LARGE)+(on?3:0)+state;}
	/** A whole-image element (little arrows, disclosure triangles) centred in a rectangle; false without it. */
	boolean drawCentred(Graphics2D g,int id,int top,int left,int bottom,int right){
		Img img=element(id,false);if(img==null)return false;
		prepare(g);blit(g,img,0,0,img.w,img.h,left+(right-left-img.w)/2,top+(bottom-top-img.h)/2,img.w,img.h);return true;
	}

	// ------------------------------------------------------------------ dialog and alert backgrounds

	static final int DIALOG_BACKGROUND=-9776,DIALOG_BACKGROUND_INACTIVE=-9775;
	/** The colour a dialog or alert window's content gets (its 'cinf' background pixel), or -1 when the scheme has none. */
	int dialogBackground(){
		Img img=element(DIALOG_BACKGROUND,false);if(img==null)return -1;
		Cinf c=cinf(DIALOG_BACKGROUND,new Cinf(0,0,false,0,0,1,1,0,0,0,0));
		int p=img.pixel(Math.max(0,c.bgY),Math.max(0,c.bgX));if((p>>>24)==0)p=img.pixel(img.h/2,img.w/2);
		return (p>>>24)==0?-1:p&0xffffff;
	}
	/** The 'ppat' that fills a dialog's content (Aqua III's pin stripes), raw, or null. */
	byte[] dialogBackgroundPattern(){
		Cinf c=cinf(DIALOG_BACKGROUND,null);int id=c!=null&&c.pattern!=0?c.pattern:DIALOG_BACKGROUND;
		ResourceFile68k.Resource r=scheme.get(PPAT,id);return r==null?null:r.data();
	}

	// ---- document windows: programs' own windows (WindowManager68k.themedWdef) and the frame round a full-screen program's
	// screen (MacAppHost.updateScreenBar, ScreenWindowFrame)

	private static final int WND=0x776e6423,CLUT=0x636c7574;
	/** K2 windows: the frame cicns (-14335 active, -14336 inactive) cut by 'wnd#' -14336, the widgets' pressed looks side by side
	 *  in -14330 and the grow box -14333 / -14334. K1 windows: a 16x16 miniature window (-14332 active, -14336 inactive) with
	 *  racing stripes -14331 and the grow box -14330 / -14334. */
	static final int WINDOW_ACTIVE=-14335,WINDOW_INACTIVE=-14336,WIDGETS_PRESSED=-14330,GROW_ACTIVE=-14333,GROW_INACTIVE=-14334,
		K1_WINDOW_ACTIVE=-14332,K1_STRIPES=-14331,K1_GROW_ACTIVE=-14330;
	/** Title bar widgets a window has (bits). */
	static final int CLOSE=1,ZOOM=2,COLLAPSE=4;

	/** A window frame laid out for a content size: where its parts go, in structure-local pixels (the content at the outsets). */
	static final class FrameLayout {
		final int contentWidth,contentHeight,width,height,widgets;final int[] outsets;
		/** The widgets' boxes {top, left, bottom, right}, a scheme may give a window several of each. */
		final java.util.List<int[]> close=new java.util.ArrayList<int[]>(),zoom=new java.util.ArrayList<int[]>();
		/** Where the title is written {top, left, bottom, right}, or null without a title. */
		int[] title;
		FrameLayout(int[] outsets,int w,int h,int widgets){this.outsets=outsets.clone();contentWidth=w;contentHeight=h;width=w+outsets[1]+outsets[3];height=h+outsets[0]+outsets[2];this.widgets=widgets;}
		int[] content(){return new int[]{outsets[0],outsets[1],outsets[0]+contentHeight,outsets[1]+contentWidth};}
	}
	/** A document window frame of the scheme. */
	interface WindowFrame extends ScreenWindowFrame.Look {
		FrameLayout layout(int w,int h,String title,int widgets,Font font);
		/** The frame (ARGB, structure-sized, the content left clear), {@code pressed} = the widget drawn pressed (0 = none). */
		BufferedImage paint(FrameLayout layout,String title,boolean active,int pressed,Font font);
		/** The grow box stamped over the content's bottom-right corner (its top left 15 pixels in from it), or null. */
		Img growBox(boolean active);
		@Override default BufferedImage paint(int w,int h,String title,boolean active){Font f=ScreenWindowFrame.titleFont();return paint(layout(w,h,title,0,f),title,active,0,f);}
	}
	private WindowFrame documentWindow;private boolean documentWindowRead;
	/** The scheme's document window frame, or null when neither the scheme nor the panel has one. */
	synchronized WindowFrame documentWindow(){
		if(!documentWindowRead){documentWindowRead=true;
			ResourceFile68k.Resource wnd=scheme.get(WND,WINDOW_INACTIVE);
			if(wnd!=null&&element(WINDOW_ACTIVE,false)!=null){Wnd layout=Wnd.parse(wnd.data());if(layout!=null)documentWindow=new K2Window(layout);}
			if(documentWindow==null&&element(K1_WINDOW_ACTIVE)!=null)documentWindow=new K1Window();
		}
		return documentWindow;
	}

	/** A 'wnd#' (the scheme's own template, TMPL 1240): rectangles by part (0 content, 1 close, 2 zoom, 3 collapse, 4 title),
	 *  then for the top, bottom, left and right sides a list of sections, each a part code and the coordinate it ends at. */
	static final class Wnd {
		final java.util.List<int[]> rects=new java.util.ArrayList<int[]>(); // {part, top, left, bottom, right}
		@SuppressWarnings("unchecked") final java.util.List<int[]>[] sides=new java.util.List[4]; // {part, border}: top, bottom, left, right
		static Wnd parse(byte[] d){
			try{Wnd w=new Wnd();int p=0;
				int n=s16(d,p)+1;p+=2;for(int i=0;i<n;i++,p+=10)w.rects.add(new int[]{s16(d,p),s16(d,p+2),s16(d,p+4),s16(d,p+6),s16(d,p+8)});
				for(int s=0;s<4;s++){n=s16(d,p)+1;p+=2;w.sides[s]=new java.util.ArrayList<int[]>();for(int i=0;i<n;i++,p+=4)w.sides[s].add(new int[]{s16(d,p),s16(d,p+2)});}
				return w.rect(0)==null?null:w;
			}catch(RuntimeException e){return null;}
		}
		int[] rect(int part){for(int[] r:rects)if(r[0]==part)return r;return null;}
	}
	/** Section part codes: 0 not drawn, 1 as it is, 5 the title, 6 beside the title, 8 stretched (tiled when wider than a
	 *  pixel), 18 scaled; 2/3/4 the close, zoom and collapse box sections and 15/16/17 the ones for a window without them. */
	private static final int SEC_NULL=0,SEC_TITLE=5,SEC_STRETCH=8,SEC_STRETCH_ALT=13,SEC_SCALE=18;
	private static boolean stretches(int code){return code==SEC_STRETCH||code==SEC_STRETCH_ALT||code==SEC_SCALE;}
	/** The widget a section code is for (with it: 2/3/4, without it: 15/16/17), else 0. */
	private static int widgetWith(int code){return code==2?CLOSE:code==3?ZOOM:code==4?COLLAPSE:0;}
	private static int widgetWithout(int code){return code==15?CLOSE:code==16?ZOOM:code==17?COLLAPSE:0;}
	/** The widget bit of a 'wnd#' rectangle part (1 close, 2 zoom, 3 collapse), else 0. */
	private static int widgetOfRect(int part){return part==1?CLOSE:part==2?ZOOM:part==3?COLLAPSE:0;}

	/**
	 * Lays a side's sections ({code, from, to} in the cicn) out over {@code length} pixels: the source coordinate for each, -1
	 * where nothing is drawn. Fixed sections keep their size; a stretch section takes the room left (a null section right after
	 * it belongs to it: "stretch the first pixel, the rest does not draw"), the title section is {@code titleWidth} wide and
	 * centred on the side when the stretches allow. {@code titleSpan} receives the title's place, {@code starts} (when given)
	 * each section's.
	 */
	static int[] layoutSections(java.util.List<int[]> sections,int length,int titleWidth,int[] titleSpan){return layoutSections(sections,length,titleWidth,titleSpan,null);}
	static int[] layoutSections(java.util.List<int[]> sections,int length,int titleWidth,int[] titleSpan,int[] starts){
		int fixed=0,stretchCount=0,titleAt=-1;
		java.util.List<int[]> parts=new java.util.ArrayList<int[]>(); // {code, from, to, kind: 0 fixed 1 stretch 2 title 3 absorbed null}
		for(int[] s:sections){
			int code=s[0],len=s[2]-s[1];
			if(code==SEC_NULL&&!parts.isEmpty()&&parts.get(parts.size()-1)[3]>=1&&parts.get(parts.size()-1)[3]!=2){parts.add(new int[]{code,s[1],s[2],3});continue;}
			if(code==SEC_TITLE&&titleWidth>=0&&titleAt<0){titleAt=parts.size();parts.add(new int[]{code,s[1],s[2],2});continue;}
			if(stretches(code)){stretchCount++;parts.add(new int[]{code,s[1],s[2],1});continue;}
			fixed+=len;parts.add(new int[]{code,s[1],s[2],0});
		}
		int title=titleAt<0?0:Math.max(0,Math.min(titleWidth,length-fixed));
		int extra=Math.max(0,length-fixed-title);
		int[] share=new int[parts.size()];
		if(stretchCount>0){
			int leftCount=0,leftFixed=0;
			if(titleAt>=0)for(int i=0;i<titleAt;i++){if(parts.get(i)[3]==1)leftCount++;else if(parts.get(i)[3]==0)leftFixed+=parts.get(i)[2]-parts.get(i)[1];}
			int rightCount=stretchCount-leftCount;
			int leftExtra=titleAt<0?extra:leftCount==0?0:rightCount==0?extra:Math.max(0,Math.min(extra,(length-title)/2-leftFixed));
			int rightExtra=extra-leftExtra;
			int li=0,ri=0;
			for(int i=0;i<parts.size();i++){if(parts.get(i)[3]!=1)continue;
				boolean left=titleAt<0||i<titleAt;int count=left?(titleAt<0?stretchCount:leftCount):rightCount,pool=left?leftExtra:rightExtra,k=left?li++:ri++;
				share[i]=pool/count+(k<pool%count?1:0);}
		}
		int[] map=new int[Math.max(0,length)];java.util.Arrays.fill(map,-1);
		int at=0;
		for(int i=0;i<parts.size();i++){
			int[] s=parts.get(i);int src=s[2]-s[1],dst=s[3]==0?src:s[3]==1?share[i]:s[3]==2?title:0;
			if(starts!=null&&i<starts.length)starts[i]=at;
			if(s[3]==2&&titleSpan!=null){titleSpan[0]=at;titleSpan[1]=at+dst;}
			for(int k=0;k<dst;k++,at++){
				if(at>=length||s[0]==SEC_NULL||src<=0)continue;
				map[at]=s[3]==0?s[1]+k:s[0]==SEC_SCALE?s[1]+(int)((long)k*src/Math.max(1,dst)):s[1]+k%src;
			}
		}
		return map;
	}
	/** A side's list as sections {code, from, to} clipped to [lo, hi) of the cicn, shifted to start at 0. The sections of the
	 *  widgets the window lacks are left out (2/3/4, or any section holding only such boxes), as are the "without" sections
	 *  (15/16/17) of those it has. */
	private static java.util.List<int[]> sections(java.util.List<int[]> side,int lo,int hi,java.util.List<int[]> rects,boolean horizontal,int widgets){
		java.util.List<int[]> out=new java.util.ArrayList<int[]>();int from=0;
		for(int[] e:side){int to=Math.min(hi,Math.max(from,e[1]));int code=e[0];
			int a=Math.max(from,lo),b=Math.min(to,hi);
			if(b>a){boolean keep;
				if(widgetWith(code)!=0)keep=(widgets&widgetWith(code))!=0;
				else if(widgetWithout(code)!=0)keep=(widgets&widgetWithout(code))==0;
				else{int held=0;if(horizontal)for(int[] r:rects)if(widgetOfRect(r[0])!=0&&r[2]>=a&&r[4]<=b)held|=widgetOfRect(r[0]);keep=held==0||(held&widgets)!=0;}
				if(keep)out.add(new int[]{code,a-lo,b-lo});}
			from=to;}
		if(from<hi)out.add(new int[]{1,Math.max(from,lo)-lo,hi-lo}); // the rest of the cicn as it is
		return out;
	}
	/** Colour {@code value} of a 'clut' (the scheme's header colours: 2 = text), or the default. */
	private int clutColour(int id,int value,int fallback){
		ResourceFile68k.Resource r=scheme.get(CLUT,id);byte[] d=r==null?null:r.data();if(d==null||d.length<8)return fallback;
		int n=s16(d,6)+1;for(int i=0;i<n&&8+i*8+8<=d.length;i++){int o=8+i*8;if(u16(d,o)==value)return 0xff000000|(u16(d,o+2)>>8)<<16|(u16(d,o+4)>>8)<<8|(u16(d,o+6)>>8);}
		return fallback;
	}
	private static void drawTitle(Graphics2D g,Font font,String title,int x,int top,int bottom,int width,int ink,int emboss){
		if(title.isEmpty()||width<=0)return;
		FontMetrics fm=Fonts68k.metrics(font);
		int tw=Fonts68k.width(font,title),tx=x+Math.max(0,(width-tw)/2),base=top+(bottom-top-fm.getAscent()-fm.getDescent())/2+fm.getAscent();
		Shape clip=g.getClip();g.clipRect(x,top,width,bottom-top);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());
		if(emboss!=0){g.setColor(new Color(emboss,true));Fonts68k.drawString(g,font,title,tx+1,base+1);}
		g.setColor(new Color(ink,true));Fonts68k.drawString(g,font,title,tx,base);
		g.setClip(clip);
	}
	private static int titleWidth(Font font,String title){return title.isEmpty()?0:Fonts68k.width(font,title)+2;}

	/** A K2 document window: the frame cicn laid out by the scheme's 'wnd#'; widgets as the cicn shows them in their sections. */
	private final class K2Window implements WindowFrame {
		final Wnd wnd;final int[] content;final int[] outsets;
		K2Window(Wnd wnd){this.wnd=wnd;Img a=element(WINDOW_ACTIVE,false);int[] c=wnd.rect(0);
			content=new int[]{c[1],c[2],c[3],c[4]};outsets=new int[]{c[1],c[2],a.h-c[3],a.w-c[4]};}
		@Override public int[] outsets(){return outsets.clone();}
		private Img image(boolean active){Img img=active?element(WINDOW_ACTIVE,false):element(WINDOW_INACTIVE,false);return img!=null?img:element(WINDOW_ACTIVE,false);}
		/** The top side's x map of the last layout (cached with it). */
		private FrameLayout lastLayout;private int[] lastTopX;
		@Override public synchronized FrameLayout layout(int w,int h,String title,int widgets,Font font){
			Img img=image(true);FrameLayout l=new FrameLayout(outsets,w,h,widgets);
			java.util.List<int[]> top=sections(wnd.sides[0],0,img.w,wnd.rects,true,widgets);
			int[] span={-1,-1},starts=new int[top.size()];
			lastTopX=layoutSections(top,l.width,titleWidth(font,title),span,starts);lastLayout=l;
			for(int i=0;i<top.size();i++){int[] s=top.get(i);
				for(int[] r:wnd.rects){int kind=widgetOfRect(r[0]);if(kind==0||(widgets&kind)==0||r[2]<s[1]||r[4]>s[2])continue;
					int x=starts[i]+r[2]-s[1];int[] box={r[1],x,r[3],x+r[4]-r[2]};
					if(kind==CLOSE)l.close.add(box);else if(kind==ZOOM)l.zoom.add(box);}}
			int[] tr=wnd.rect(4);
			if(span[1]>span[0])l.title=new int[]{tr==null?0:tr[1],span[0],tr==null?outsets[0]:tr[3],span[1]};
			else if(tr!=null&&!title.isEmpty()){ // no title section (afo-buttons): the title starts at its rectangle, up to the next widget
				int x=-1;for(int i=0;i<top.size()&&x<0;i++){int[] s=top.get(i);if(tr[2]>=s[1]&&tr[2]<s[2])x=starts[i]+tr[2]-s[1];}
				if(x>=0){int end=l.width-outsets[3];for(int[] b:l.close)if(b[1]>x)end=Math.min(end,b[1]-4);for(int[] b:l.zoom)if(b[1]>x)end=Math.min(end,b[1]-4);
					int width=Math.min(titleWidth(font,title),end-x);if(width>0)l.title=new int[]{tr[1],x,tr[3],x+width};}}
			return l;
		}
		@Override public BufferedImage paint(FrameLayout l,String title,boolean active,int pressed,Font font){
			Img img=image(active);
			int T=outsets[0],L=outsets[1],B=outsets[2],R=outsets[3],w=l.contentWidth,h=l.contentHeight,W=l.width,H=l.height;
			BufferedImage out=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB);int[] px=new int[W*H];
			int[] topX;synchronized(this){topX=lastLayout==l?lastTopX:null;}
			if(topX==null){FrameLayout again=layout(w,h,title,l.widgets,font);synchronized(this){topX=lastTopX;}l.close.clear();l.close.addAll(again.close);l.zoom.clear();l.zoom.addAll(again.zoom);l.title=again.title;}
			int[] bottomX=layoutSections(sections(wnd.sides[1],0,img.w,wnd.rects,true,l.widgets),W,-1,null);
			int[] leftY=layoutSections(sections(wnd.sides[2],content[0],content[2],wnd.rects,false,l.widgets),h,-1,null);
			int[] rightY=layoutSections(sections(wnd.sides[3],content[0],content[2],wnd.rects,false,l.widgets),h,-1,null);
			for(int y=0;y<T;y++)for(int x=0;x<W;x++)if(topX[x]>=0)px[y*W+x]=img.pixel(y,topX[x]);
			for(int y=0;y<B;y++)for(int x=0;x<W;x++)if(bottomX[x]>=0)px[(H-B+y)*W+x]=img.pixel(content[2]+y,bottomX[x]);
			for(int y=0;y<h;y++){int sy=leftY[y];if(sy>=0)for(int x=0;x<L;x++)px[(T+y)*W+x]=img.pixel(content[0]+sy,x);
				sy=rightY[y];if(sy>=0)for(int x=0;x<R;x++)px[(T+y)*W+L+w+x]=img.pixel(content[0]+sy,content[3]+x);}
			// a pressed widget: its cell of the pressed-widgets strip (close, zoom, collapse side by side) over each of its boxes
			Img down=pressed!=0&&active?element(WIDGETS_PRESSED,false):null;
			if(down!=null){int cell=pressed==CLOSE?0:pressed==ZOOM?1:2;
				for(int[] box:pressed==CLOSE?l.close:l.zoom){int bw=box[3]-box[1],bh=box[2]-box[0],cw=Math.max(1,down.w/3);
					for(int y=0;y<bh&&y<down.h;y++)for(int x=0;x<bw&&x<cw;x++){int p=down.pixel(y,cell*cw+x);int dx=box[1]+x,dy=box[0]+y;if((p>>>24)!=0&&dx>=0&&dy>=0&&dx<W&&dy<H)px[dy*W+dx]=p;}}}
			out.setRGB(0,0,W,H,px,0,W);
			if(l.title!=null){Graphics2D g=out.createGraphics();
				try{drawTitle(g,font,title,l.title[1],l.title[0],l.title[2],l.title[3]-l.title[1],clutColour(active?-14336:-14335,2,active?0xff000000:0xff888888),0);}finally{g.dispose();}}
			return out;
		}
		@Override public Img growBox(boolean active){Img g=element(active?GROW_ACTIVE:GROW_INACTIVE,false);return g!=null?g:element(GROW_ACTIVE,false);}
	}

	/** A K1 document window: the 16x16 miniature window stretched round the content - six pixels of frame on the left, right
	 *  and bottom, the top band drawn out to the Platinum title bar (22 pixels) by repeating its title text row - and the
	 *  racing stripes stretched along the title bar between the title and the widgets. The title takes the colour of the two
	 *  text pixels in that row, embossed with the pixel after them when it differs from the row's background; the widgets are
	 *  Platinum boxes in the window's own colours. */
	private final class K1Window implements WindowFrame {
		static final int BORDER=6,TITLE_BAR=22;
		@Override public int[] outsets(){return new int[]{TITLE_BAR,BORDER,BORDER,BORDER};}
		private int attributes(int widgets){return ((widgets&CLOSE)!=0?0x20:0)|((widgets&ZOOM)!=0?0x18:0);}
		@Override public FrameLayout layout(int w,int h,String title,int widgets,Font font){
			FrameLayout l=new FrameLayout(outsets(),w,h,widgets&(CLOSE|ZOOM));int[] c=l.content();int attr=attributes(widgets);
			int[] close=AppearanceManager68k.Platinum.widget(0,c,attr,0),zoom=AppearanceManager68k.Platinum.widget(0,c,attr,1);
			if(close!=null)l.close.add(close);if(zoom!=null)l.zoom.add(zoom);
			if(!title.isEmpty()){FontMetrics fm=Fonts68k.metrics(font);l.title=AppearanceManager68k.Platinum.titleText(0,c,attr,titleWidth(font,title),fm.getAscent()+fm.getDescent());}
			return l;
		}
		@Override public BufferedImage paint(FrameLayout l,String title,boolean active,int pressed,Font font){
			Img img=active?element(K1_WINDOW_ACTIVE):element(WINDOW_INACTIVE);if(img==null)img=element(K1_WINDOW_ACTIVE);
			int W=l.width,H=l.height,iw=img.w,ih=img.h,mid=Math.min(BORDER,iw-1);
			// the text row: the first one under the top edge with pixels unlike its background between the borders
			int textRow=3,textX=-1;
			for(int y=1;y<BORDER-1&&textX<0;y++)for(int x=BORDER+1;x<iw-BORDER;x++)if(img.pixel(y,x)!=img.pixel(y,mid)){textRow=y;textX=x;break;}
			int bg=img.pixel(textRow,mid),ink=textX<0?0xff000000:img.pixel(textRow,textX),emboss=textX<0?0:img.pixel(textRow,textX+2);
			if(emboss==bg)emboss=0;
			int below=BORDER-1-textRow; // rows of the top band after the text row
			BufferedImage out=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB);int[] px=new int[W*H];
			for(int y=0;y<H;y++){
				int sy=y<textRow?y:y<TITLE_BAR-below?textRow:y<TITLE_BAR?BORDER-(TITLE_BAR-y):y<H-BORDER?BORDER:ih-(H-y);
				boolean contentRow=y>=TITLE_BAR&&y<H-BORDER;
				for(int x=0;x<W;x++){
					if(contentRow&&x>=BORDER&&x<W-BORDER)continue;
					int sx=x<BORDER?x:x>=W-BORDER?iw-(W-x):mid;
					px[y*W+x]=sy==textRow&&sx>=BORDER&&sx<iw-BORDER?bg:img.pixel(sy,sx);
				}
			}
			out.setRGB(0,0,W,H,px,0,W);
			Img stripes=active?element(K1_STRIPES):null;
			if(stripes!=null){Img pat=pattern(K1_STRIPES);int sy=1+(TITLE_BAR-2-stripes.h)/2;
				// the stripes run between the title and the widgets, each piece with its own ends
				java.util.List<int[]> gaps=new java.util.ArrayList<int[]>();
				if(l.title!=null)gaps.add(new int[]{l.title[1]-6,l.title[3]+6});
				for(int[] b:l.close)gaps.add(new int[]{b[1]-3,b[3]+3});for(int[] b:l.zoom)gaps.add(new int[]{b[1]-3,b[3]+3});
				gaps.sort((a,b)->Integer.compare(a[0],b[0]));
				int from=BORDER-2;for(int[] gap:gaps){stripePiece(out,stripes,pat,from,gap[0],sy);from=Math.max(from,gap[1]);}
				stripePiece(out,stripes,pat,from,W-(BORDER-2),sy);}
			Graphics2D g=out.createGraphics();
			try{
				if(active){int outline=img.pixel(0,0),light=img.pixel(1,1),shadow=img.pixel(ih-2,iw-2);
					for(int[] b:l.close)widget(g,b,0,pressed==CLOSE,outline,bg,light,shadow);
					for(int[] b:l.zoom)widget(g,b,1,pressed==ZOOM,outline,bg,light,shadow);}
				if(l.title!=null)drawTitle(g,font,title,l.title[1],1,TITLE_BAR-2,l.title[3]-l.title[1],active?ink:0xff888888,active?emboss:0);
			}finally{g.dispose();}
			return out;
		}
		/** A Platinum widget box in the window's colours: outline, face, bevel (inverted and darkened when pressed) and glyph. */
		private void widget(Graphics2D g,int[] r,int kind,boolean pressed,int outline,int face,int light,int shadow){
			int t=r[0],l=r[1],b=r[2],ri=r[3],s=ri-l;
			g.setColor(new Color(pressed?shadow:face,true));g.fillRect(l,t,s,b-t);
			g.setColor(new Color(outline,true));g.drawRect(l,t,s-1,b-t-1);
			g.setColor(new Color(pressed?shadow:light,true));g.drawLine(l+1,t+1,ri-3,t+1);g.drawLine(l+1,t+1,l+1,b-3);
			g.setColor(new Color(pressed?light:shadow,true));g.drawLine(l+2,b-2,ri-2,b-2);g.drawLine(ri-2,t+2,ri-2,b-2);
			if(kind==1){g.setColor(new Color(outline,true));g.drawRect(l+2,t+2,s/2,s/2);}
		}
		/** The stripes over [from, to): both ends as they are, the middle column stretched; holes in the mask show the pattern. */
		private void stripePiece(BufferedImage out,Img s,Img pat,int from,int to,int top){
			int len=to-from,half=s.w/2;if(len<=0)return;
			for(int y=0;y<s.h&&top+y<out.getHeight();y++)for(int i=0;i<len;i++){
				int sx=i<half?i:i>=len-(s.w-half-1)?s.w-(len-i):half;if(sx<0||sx>=s.w)continue;
				int p=s.pixel(y,sx);
				if((p>>>24)==0&&pat!=null)p=pat.pixel(y%pat.h,i%pat.w);
				if((p>>>24)!=0&&from+i>=0&&from+i<out.getWidth())out.setRGB(from+i,top+y,p);
			}
		}
		@Override public Img growBox(boolean active){Img g=element(active?K1_GROW_ACTIVE:GROW_INACTIVE);return g!=null?g:element(K1_GROW_ACTIVE);}
	}

	private static int u16(byte[] b,int o){return (b[o]&255)<<8|(b[o+1]&255);}
	private static int s16(byte[] b,int o){return (short)u16(b,o);}
	private static int u32(byte[] b,int o){return u16(b,o)<<16|u16(b,o+2);}
}

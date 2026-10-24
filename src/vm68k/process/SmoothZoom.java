import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smooth zoom ("スムーズ拡大") for Mac programs, mainly games: while their screen is shown magnified (the zoom setting, or a
 * Retina display), the pixel buffers they draw into - the windows' and the offscreen GWorlds behind them - get hi-res
 * "planes" {@code s} times their size (s = 2..4, the display pixels per screen pixel rounded up). Programs still see and
 * read only their own 1x pixels; the planes are what the host shows.
 * <ul>
 * <li>Text is drawn into the plane again at s times the size (outlines at the same advances, so nothing moves), and so is
 *     host Java2D drawing (TextEdit, TextBox, controls: {@link SmoothGraphics}).</li>
 * <li>CopyBits/CopyMask/CopyDeepMask/ScrollRect carry the hi-res blocks of what they copy, so text drawn offscreen reaches
 *     the window smooth.</li>
 * <li>Everything else - icons, pictures, sprites, shapes, pixels a program writes itself - is scaled up from the 1x pixels
 *     by the chosen algorithm ({@link SmoothScaler}: Bresenham, bicubic, the enhanced Scale2x, or auto = Scale2x for pixel art and
 *     bicubic for dithered or photographic parts).</li>
 * </ul>
 * How a plane stays true to its buffer: {@link Plane#snap} keeps, per pixel, the 1x colour its block was made for. Whenever
 * an area is synced (before hi-res drawing there, and by the host before it shows a window), every pixel whose colour no
 * longer matches is scaled up again, with its neighbours within the algorithm's reach - whatever changed it. Hi-res drawing
 * pins its rows (the host's refresh leaves them alone meanwhile), writes the blocks, and commits: the new 1x colours are taken
 * as what those blocks stand for, and the blocks are marked {@link Plane#own} (not to be scaled again because a neighbour
 * changed). A program drawing over hi-res text at 1x changes the 1x colours, so that part is scaled up again - always
 * consistent, at worst blockier.
 */
final class SmoothZoom {
	static final int OFF=0,BRESENHAM=SmoothScaler.BRESENHAM,BICUBIC=SmoothScaler.BICUBIC,SCALE2X=SmoothScaler.SCALE2X,AUTO=SmoothScaler.AUTO;
	/** The choices in the order the pop-ups list them (the stored values stay 0..4 whatever the order). */
	static final int[] CHOICES={OFF,AUTO,SCALE2X,BICUBIC,BRESENHAM};
	static boolean valid(int choice){for(int c:CHOICES)if(c==choice)return true;return false;}
	/** The English label of a choice (translated by International.dialogText). */
	static String label(int choice){switch(choice){
		case AUTO:return "Auto (pixel art + smooth)";case SCALE2X:return "Pixel art (Scale2x+)";case BICUBIC:return "Smooth (bicubic)";
		case BRESENHAM:return "Text only (pictures as dots)";default:return "Off";}}
	/** A stored choice ("0".."4", or a name: off, bresenham, bicubic, scale2x, auto; hqx, the choice 3 once was, is read as scale2x), or {@code fallback}. */
	static int parse(String value,int fallback){
		if(value==null)return fallback;String v=value.trim().toLowerCase();
		switch(v){case "off":case "none":case "false":return OFF;case "bresenham":return BRESENHAM;case "bicubic":return BICUBIC;case "scale2x":case "scale2x+":case "hqx":return SCALE2X;case "auto":return AUTO;default:break;}
		try{int c=Integer.parseInt(v);return valid(c)?c:fallback;}catch(NumberFormatException e){return fallback;}
	}
	/** -Dhz68k.smooth=off|bresenham|bicubic|scale2x|auto: the algorithm of every program for this run, whatever the settings say (scripts, screenshots). */
	static final Integer OVERRIDE=System.getProperty("hz68k.smooth")==null?null:parse(System.getProperty("hz68k.smooth"),OFF);
	/** -Dhz68k.smoothscale=2..4: the planes' scale whatever the zoom and the display are (headless tests, screenshots). */
	static final int SCALE_OVERRIDE=Math.max(0,Math.min(4,Integer.getInteger("hz68k.smoothscale",0)));
	/** -Dhz68k.smoothstats=true: every 2 s, how many pixels the planes compared and scaled up, and the time it took (tuning). */
	private static final boolean STATS=Boolean.getBoolean("hz68k.smoothstats");
	private static final java.util.concurrent.atomic.AtomicLong statCompared=new java.util.concurrent.atomic.AtomicLong(),statScaled=new java.util.concurrent.atomic.AtomicLong(),statNanos=new java.util.concurrent.atomic.AtomicLong();
	private static volatile long statSince=System.nanoTime();
	private static void stats(long compared,long scaled,long nanos){
		if(!STATS)return;statCompared.addAndGet(compared);statScaled.addAndGet(scaled);statNanos.addAndGet(nanos);
		long now=System.nanoTime();if(now-statSince<2_000_000_000L)return;statSince=now;
		System.err.println("[smooth] 2 s: compared "+statCompared.getAndSet(0)+" px, scaled "+statScaled.getAndSet(0)+" px, "+statNanos.getAndSet(0)/1_000_000+" ms");
	}
	/** The planes' scale for a magnification ({@code percent}) on a display of device scale {@code device}: the display pixels per
	 *  screen pixel rounded up, at most 4 (the largest factor the scalers do in one pass). 1 means no planes: a screen shown at its own size has nothing to gain. */
	static int scaleFor(int percent,double device){
		if(SCALE_OVERRIDE>0)return SCALE_OVERRIDE;
		double physical=percent/100.0*Math.max(1,device);
		return Math.max(1,Math.min(4,(int)Math.ceil(physical-0.01)));
	}

	final M68kMemory memory;
	SmoothZoom(M68kMemory memory){this.memory=memory;}
	private volatile int scale=1,algorithm=OFF;
	int scale(){return scale;}
	int algorithm(){return algorithm;}
	/** Whether drawing goes into planes at all. */
	boolean active(){return scale>1&&algorithm!=OFF;}
	/** Sets the scale and algorithm; when either changes every plane is dropped (true: the program should redraw, for its text to be hi-res again). */
	synchronized boolean configure(int newScale,int newAlgorithm){
		newScale=Math.max(1,Math.min(4,newScale));if(!valid(newAlgorithm))newAlgorithm=OFF;
		if(newScale==scale&&newAlgorithm==algorithm)return false;
		boolean had=!planes.isEmpty();for(Plane p:planes.values())p.unwatch();planes.clear();planePixels=0;scale=newScale;algorithm=newAlgorithm;generation++;
		return had||newScale>1&&newAlgorithm!=OFF;
	}
	/** Bumped whenever the planes are dropped (the host drops what it showed of them). */
	private volatile int generation;
	int generation(){return generation;}

	private final Map<Integer,Plane> planes=new HashMap<Integer,Plane>();
	/** Hi-res pixels of all planes, against {@link #BUDGET}: least recently used offscreen planes go first. */
	private long planePixels;
	private static final long BUDGET=Math.max(16L<<20,Runtime.getRuntime().maxMemory()/3/4);
	/** The hi-res pixels all planes may take together (a third of the heap). */
	static long budget(){return BUDGET;}
	/** The largest plane (hi-res pixels) made for an offscreen buffer: a sprite sheet the size of a poster stays 1x. */
	private static final long OFFSCREEN_LIMIT=Math.min(BUDGET/2,48L<<20);

	/**
	 * The plane of the buffer at {@code base} ({@code width x height} pixels of {@code depth} bits, rows of {@code rowBytes}),
	 * made when {@code create} (null when the smooth zoom is off, the buffer is too large or the budget is spent). A plane of the
	 * same address with another geometry (a resized window, a new GWorld in freed memory) is replaced.
	 */
	synchronized Plane plane(int base,int rowBytes,int width,int height,int depth,boolean create){
		if(!active()||base<=0||width<=0||height<=0||rowBytes<=0)return null;
		Plane p=planes.get(base);
		if(p!=null&&p.rowBytes==rowBytes&&p.width==width&&p.height==height&&p.depth==depth){p.lastUse=System.nanoTime();return p;}
		if(!create)return null;
		if(depth!=1&&depth!=2&&depth!=4&&depth!=8&&depth!=16&&depth!=32)return null;
		if((long)width*depth>(long)rowBytes*8||(long)base+(long)rowBytes*height>memory.raw().length)return null;
		if(p!=null){planes.remove(base);planePixels-=p.hiPixels();p.unwatch();
			// a window's buffer seen with another size than its plane's: two callers disagreeing would make planes over and over
			if(p.window&&p.rowBytes==rowBytes&&warnedBases.add(base))System.err.println("[smooth] window plane at $"+Integer.toHexString(base)+" replaced: "+p.width+"x"+p.height+"/"+p.depth+" -> "+width+"x"+height+"/"+depth);}
		int s=scale;long pixels=(long)width*height*s*s;
		if(pixels>BUDGET||pixels>Integer.MAX_VALUE-8)return null;
		boolean tracked=memory.tracked(base)&&memory.tracked(base+rowBytes*height-1);
		if(!tracked&&pixels>OFFSCREEN_LIMIT)return null;
		while(planePixels+pixels>BUDGET&&evictOne()){}
		if(planePixels+pixels>BUDGET)return null;
		p=new Plane(base,rowBytes,width,height,depth,s,algorithm);planes.put(base,p);planePixels+=pixels;
		return p;
	}
	private final java.util.Set<Integer> warnedBases=new java.util.HashSet<Integer>();
	/** Drops the least recently used plane that is no window's; false when there is none. */
	private boolean evictOne(){
		Plane oldest=null;for(Plane p:planes.values())if(!p.window&&(oldest==null||p.lastUse<oldest.lastUse))oldest=p;
		if(oldest==null)return false;planes.remove(oldest.base);planePixels-=oldest.hiPixels();oldest.unwatch();return true;
	}
	/** Forgets the plane of the buffer at {@code base} (its window or GWorld was disposed). */
	synchronized void forget(int base){Plane p=planes.remove(base);if(p!=null){planePixels-=p.hiPixels();p.unwatch();}}
	/** Debug (commands.txt `planes [dir]`): every plane with its geometry and hi-res pixel count, its image written to {@code dir}. */
	synchronized String describe(java.io.File dir){
		StringBuilder sb=new StringBuilder();
		for(Plane p:planes.values()){int own=0;for(byte b:p.own)if(b!=0)own++;
			sb.append(String.format("%n  $%X %dx%d/%d rb %d s %d%s own %d",p.base,p.width,p.height,p.depth,p.rowBytes,p.s,p.window?" window":"",own));
			if(dir!=null)try{javax.imageio.ImageIO.write(p.image(),"png",new java.io.File(dir,String.format("plane-%X.png",p.base)));}catch(Exception e){}}
		return sb.toString();
	}
	/** The plane at {@code base} whatever its geometry, or null. */
	synchronized Plane existing(int base){return planes.get(base);}

	private static final java.lang.invoke.VarHandle BITS=java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
	/** A hi-res rendering of text for {@link Plane}s: the coverage image at {@code scale} times the size of the 1x mask, same origin. */
	interface Text {BufferedImage render(int scale);}

	/** What a {@link Plane#sync} is for: the host's refresh, hi-res drawing about to go there, or the source of a copy. */
	static final int HOST=0,DRAW=1,READ=2;
	/** How long a pin holds the host's refresh off at most (a surface left open by an exception must not freeze rows). */
	private static final long PIN_LIMIT=1_000_000_000L;

	/** The hi-res plane of one pixel buffer. Blocks: pixel (x,y)'s is the s x s square at (x*s, y*s) of {@link #hi}. */
	final class Plane {
		final int base,rowBytes,width,height,depth,s,algorithm;
		/** Per pixel: the 1x colour (0xRRGGBB) its block stands for. */
		final int[] snap;
		/** The hi-res pixels, (width*s) x (height*s), 0xRRGGBB. */
		final int[] hi;
		/** Per pixel: 1 = its block was drawn at hi-res (text, hi-res blocks copied in), so a neighbour's change does not scale it again. */
		final byte[] own;
		/** Write serial up to which each row was compared whole (-1: compare again); used for tracked buffers (windows, the screen). */
		private final long[] rowSerial;
		/** Per row: {@link #version} of the last change of its blocks (the host shows rows newer than what it showed). */
		final long[] rowVersion;
		private volatile long version;
		/** Scratch of the scaling: pixels to scale again, and each row's marked column range. */
		private final byte[] redo;private final int[] redoLeft,redoRight;private int redoTop=Integer.MAX_VALUE,redoBottom=-1;
		/** The colours indexed pixels were converted through (null: direct pixels). */
		private int[] palette;
		/** A window's buffer or the screen's: kept while it lives, colours through the screen's table. */
		volatile boolean window;
		volatile long lastUse=System.nanoTime();
		private boolean synced;
		/** The buffer's bytes as of the last look (only bytes that changed since are converted and compared). */
		private final byte[] rawSnap;
		/** Rows to compare pixel by pixel (the first look, other colours, a row the host's refresh skipped while pinned then). */
		private final boolean[] fullRow;
		private final List<long[]> pins=new ArrayList<long[]>();
		private BufferedImage image;
		private int[] row;
		/** A bit per byte of the buffer written since the last look ({@link M68kMemory#watchBuffer}), once the plane has blocks drawn at hi-res. */
		private volatile long[] written;

		Plane(int base,int rowBytes,int width,int height,int depth,int s,int algorithm){
			this.base=base;this.rowBytes=rowBytes;this.width=width;this.height=height;this.depth=depth;this.s=s;this.algorithm=algorithm;
			snap=new int[width*height];hi=new int[width*s*height*s];own=new byte[width*height];
			rowSerial=new long[height];Arrays.fill(rowSerial,-1);rowVersion=new long[height];
			redo=new byte[width*height];redoLeft=new int[height];redoRight=new int[height];Arrays.fill(redoLeft,Integer.MAX_VALUE);Arrays.fill(redoRight,-1);
			row=new int[width+8];rawSnap=new byte[rowBytes*height];fullRow=new boolean[height];
		}
		long hiPixels(){return (long)hi.length;}
		void unwatch(){long[] w=written;if(w!=null){written=null;memory.unwatchBuffer(w);}}
		/**
		 * Row {@code y}'s bytes written since the last look: hi-res blocks there are released even when the 1x colour came out the
		 * same (pixel comparison misses that: HyperCard wipes its offscreen card picture white with its own blitters, and the
		 * previous stack's glyph edges stayed over the new stack's blank card).
		 */
		private void releaseWritten(int y,long[] bits){
			int from=y*rowBytes,to=from+rowBytes;
			for(int i=from>>>6,last=(to-1)>>>6;i<=last;i++){
				if(bits[i]==0)continue;
				long mask=-1L;if(i==from>>>6)mask&=-1L<<(from&63);if(i==last&&(to&63)!=0)mask&=-1L>>>(64-(to&63));
				long got=(long)BITS.getAndBitwiseAnd(bits,i,~mask)&mask;
				while(got!=0){int b=(i<<6)+Long.numberOfTrailingZeros(got)-from;got&=got-1;
					int p0=b*8/depth,p1=Math.min(width,(b*8+7)/depth+1);if(p0<width)disown(y,p0,p1);}
			}
		}
		/** Forgets row {@code y}'s written bytes {@code b0..b1-1} (the 1x side of a hi-res drawing that was just committed). */
		private void clearWritten(int y,int b0,int b1,long[] bits){
			int from=y*rowBytes+b0,to=y*rowBytes+b1;
			for(int i=from>>>6,last=(to-1)>>>6;i<=last;i++){
				long mask=-1L;if(i==from>>>6)mask&=-1L<<(from&63);if(i==last&&(to&63)!=0)mask&=-1L>>>(64-(to&63));
				if((bits[i]&mask)!=0)BITS.getAndBitwiseAnd(bits,i,~mask);
			}
		}
		/** The plane as an image (TYPE_INT_RGB over {@link #hi}: what is drawn into it is the plane). */
		synchronized BufferedImage image(){
			if(image==null){java.awt.image.DataBufferInt data=new java.awt.image.DataBufferInt(hi,hi.length);
				java.awt.image.WritableRaster raster=java.awt.image.Raster.createPackedRaster(data,width*s,height*s,width*s,new int[]{0xff0000,0xff00,0xff},null);
				image=new BufferedImage(new java.awt.image.DirectColorModel(24,0xff0000,0xff00,0xff),raster,false,null);}
			return image;
		}
		/** Java2D over the plane, in hi-res pixels (the caller sets its transform). */
		Graphics2D graphics(){return image().createGraphics();}

		/** Pins rows {@code y0..y1-1} while hi-res drawing is under way there: the host's refresh does not scale them meanwhile. */
		long[] pin(int y0,int y1){long[] p={y0,y1,System.nanoTime()};synchronized(pins){pins.add(p);}lastUse=p[2];return p;}
		void unpin(long[] p){if(p!=null)synchronized(pins){pins.remove(p);}}
		private boolean pinned(int y){
			synchronized(pins){if(pins.isEmpty())return false;long now=System.nanoTime();
				for(long[] p:pins)if(y>=p[0]&&y<p[1]&&now-p[2]<PIN_LIMIT)return true;}
			return false;
		}

		/** The 1x colours of pixels {@code x..x+n-1} of row {@code y}, through {@code colors} for indexed pixels. */
		private void read(int y,int x,int n,int[] out,int[] colors){
			byte[] raw=memory.raw();int rowStart=base+y*rowBytes;
			if(rowStart<0||rowStart+rowBytes>raw.length){Arrays.fill(out,0,n,0xffffff);return;}
			int bit=x*depth,lead=depth<8?(bit&7)/depth:0;
			if(lead==0){MacAppHost.readPixels(raw,rowStart+(bit>>>3),depth,colors,n,out,0);return;}
			int[] tmp=row.length>=n+lead?row:(row=new int[n+lead+8]);
			MacAppHost.readPixels(raw,rowStart+(bit>>>3),depth,colors,n+lead,tmp,0);System.arraycopy(tmp,lead,out,0,n);
		}
		/** The colours for converting indexed pixels: the given table, or QuickDraw's reading of a table-less bitmap (0 white, else black). */
		private int[] colors(int[] given){
			if(depth>8)return null;
			if(given!=null&&given.length>=1<<depth)return given;
			int[] c=new int[1<<depth];Arrays.fill(c,0);c[0]=0xffffff;
			if(given!=null)System.arraycopy(given,0,c,0,Math.min(given.length,c.length));
			return c;
		}

		/**
		 * Brings {@code x0..x1-1} x {@code y0..y1-1} up to date with the buffer: pixels whose colour no longer matches their block are
		 * marked, with their neighbours within the algorithm's reach unless those were drawn at hi-res, and scaled up again where
		 * hi-res drawing is about to go over them ({@link #DRAW}) and everywhere at the host's refresh: the program's own thread
		 * never waits for scaling it does not need (an offscreen buffer's other blocks are never shown at all - CopyBits carries
		 * only the hi-res ones).
		 * {@code colors}: the table indexed pixels are shown through. {@code mode}: {@link #HOST} (the host's refresh, which leaves
		 * pinned rows alone), {@link #DRAW} or {@link #READ} (a copy's source).
		 */
		synchronized void sync(int x0,int y0,int x1,int y1,int[] colors,int mode){
			colors=colors(colors);boolean full=!synced;
			if(colors!=null&&!Arrays.equals(colors,palette)){palette=colors.clone();Arrays.fill(rowSerial,-1);full=true;} // other colours: every pixel is compared again
			if(full)Arrays.fill(fullRow,true);
			if(!synced){synced=true;x0=0;y0=0;x1=width;y1=height;} // the first sync takes the whole buffer, so edges never scale against unknown neighbours
			int ax0=x0,ay0=y0,ax1=x1,ay1=y1;
			if(full){x0=0;y0=0;x1=width;y1=height;}
			x0=Math.max(0,x0);y0=Math.max(0,y0);x1=Math.min(width,x1);y1=Math.min(height,y1);if(x1<=x0||y1<=y0)return;
			boolean whole=x0==0&&x1==width&&memory.tracked(base);
			long now=memory.writeSerial(),started=STATS?System.nanoTime():0,compared=0;
			byte[] raw=memory.raw();int[] values=scratch(width);
			for(int y=y0;y<y1;y++){
				if(mode==HOST&&pinned(y))continue; // hi-res drawing under way there: its commit takes what it drew
				if(written!=null)releaseWritten(y,written);
				int rs=base+y*rowBytes,so=y*rowBytes;if(rs<0||rs+rowBytes>raw.length)continue;
				if(!fullRow[y]&&whole&&rowSerial[y]>=0&&memory.chunkSerial(rs,rowBytes)<=rowSerial[y])continue;
				if(fullRow[y]){compare(y,0,width,values);System.arraycopy(raw,rs,rawSnap,so,rowBytes);compared+=width;fullRow[y]=false;}
				else{ // only the bytes that changed since the last look are converted and compared
					int b0=(x0*depth)>>>3,b1=Math.min(rowBytes,(x1*depth+7)>>>3),at=b0;
					while(at<b1){
						int m=Arrays.mismatch(raw,rs+at,rs+b1,rawSnap,so+at,so+b1);if(m<0)break;
						int first=at+m,last=first+1;
						while(last<b1){if(raw[rs+last]!=rawSnap[so+last]){last++;continue;}int k=last+1;while(k<b1&&k-last<16&&raw[rs+k]==rawSnap[so+k])k++;if(k>=b1||k-last>=16)break;last=k;}
						System.arraycopy(raw,rs+first,rawSnap,so+first,last-first);
						int p0=first*8/depth,p1=Math.min(width,(last*8+depth-1)/depth);
						compare(y,p0,p1,values);compared+=p1-p0;at=last;
					}
				}
				if(whole)rowSerial[y]=now;
			}
			long scaled=mode==HOST?rescale(0,0,width,height,true):mode==DRAW?rescale(ax0,ay0,ax1,ay1,false):0;
			if(STATS)stats(compared,scaled,System.nanoTime()-started);
		}
		/** Compares pixels {@code x0..x1-1} of row {@code y} with what their blocks stand for; marks those that changed. */
		private void compare(int y,int x0,int x1,int[] values){
			int n=x1-x0;if(n<=0)return;read(y,x0,n,values,palette);
			int o=y*width+x0;
			for(int i=0;i<n;){
				if(snap[o+i]==values[i]){i++;continue;}
				int start=i;while(i<n&&snap[o+i]!=values[i]){snap[o+i]=values[i];own[o+i]=0;i++;}
				markChanged(y,x0+start,x0+i);
			}
		}
		private int[] values;
		private int[] scratch(int n){if(values==null||values.length<n)values=new int[n+8];return values;}

		/**
		 * After hi-res drawing in {@code x0..x1-1} x {@code y0..y1-1} (its blocks written under a pin): the pixels' new 1x colours are
		 * what their blocks stand for. {@code drawn} (per pixel of the area, row-major; null = {@code all}): the block was drawn at
		 * hi-res - it becomes {@link #own}; any other pixel whose colour changed is marked to be scaled up again, like a change found by
		 * {@link #sync}.
		 */
		synchronized void commit(int x0,int y0,int x1,int y1,byte[] drawn,boolean all){
			int ax0=x0,ay0=y0,aw=x1-x0;
			x0=Math.max(0,x0);y0=Math.max(0,y0);x1=Math.min(width,x1);y1=Math.min(height,y1);if(x1<=x0||y1<=y0)return;
			int n=x1-x0;int[] values=scratch(width);long v=++version;byte[] raw=memory.raw();
			int b0=(x0*depth)>>>3,b1=Math.min(rowBytes,(x1*depth+7)>>>3);
			if(written==null&&(all||drawn!=null))written=memory.watchBuffer(base,rowBytes*height);long[] bits=written;
			for(int y=y0;y<y1;y++){
				read(y,x0,n,values,palette!=null?palette:colors(null));
				int rs=base+y*rowBytes;if(rs>=0&&rs+rowBytes<=raw.length)System.arraycopy(raw,rs+b0,rawSnap,y*rowBytes+b0,b1-b0);
				if(bits!=null)clearWritten(y,b0,b1,bits);
				int o=y*width+x0,d=(y-ay0)*aw+(x0-ax0),run=-1;
				for(int i=0;i<=n;i++){
					boolean changed=false;
					if(i<n){
						if(drawn!=null?drawn[d+i]!=0:all){snap[o+i]=values[i];own[o+i]=1;}
						else if(snap[o+i]!=values[i]){snap[o+i]=values[i];own[o+i]=0;changed=true;}
					}
					if(changed){if(run<0)run=i;}
					else if(run>=0){markChanged(y,x0+run,x0+i);run=-1;}
				}
				// the edge pixels sharing the bytes copied above (packed depths) are compared, so the byte snapshot never runs ahead of the colours
				if(depth<8){int perByte=8/depth,l=b0*perByte,r=Math.min(width,b1*perByte);if(l<x0)compare(y,l,x0,values);if(r>x1)compare(y,x1,r,values);}
				rowVersion[y]=v;
			}
		}

		/** Marks pixels {@code x0..x1-1} of row {@code y} changed: they, and their neighbours within reach, are scaled again by {@link #rescale}. */
		private void markChanged(int y,int x0,int x1){
			int r=SmoothScaler.reach(algorithm);int l=Math.max(0,x0-r),rt=Math.min(width,x1+r);
			for(int yy=Math.max(0,y-r);yy<=Math.min(height-1,y+r);yy++){
				Arrays.fill(redo,yy*width+l,yy*width+rt,(byte)1);
				if(l<redoLeft[yy])redoLeft[yy]=l;if(rt>redoRight[yy])redoRight[yy]=rt;
				if(yy<redoTop)redoTop=yy;if(yy>redoBottom)redoBottom=yy;
			}
		}
		/**
		 * Scales up the marked pixels inside {@code x0..x1-1} x {@code y0..y1-1} that were not drawn at hi-res, row by row (in parallel for
		 * large changes); marks elsewhere, and in rows pinned against the host's refresh, wait for a later pass. Returns how many
		 * pixels were looked at.
		 */
		private long rescale(int x0,int y0,int x1,int y1,boolean host){
			if(redoBottom<redoTop)return 0;
			final int top=Math.max(redoTop,y0),bottom=Math.min(redoBottom,y1-1),ax0=x0,ax1=x1;if(top>bottom)return 0;
			long area=0;for(int y=top;y<=bottom;y++){int l=Math.max(redoLeft[y],x0),r=Math.min(redoRight[y],x1);if(r>l)area+=r-l;}
			if(area==0)return 0;
			final long v=++version;
			java.util.function.IntConsumer work=y->{
				int l0=redoLeft[y],r0=redoRight[y];if(r0<=l0||host&&pinned(y))return;
				int l=Math.max(l0,ax0),r=Math.min(r0,ax1);if(r<=l)return;
				int o=y*width;
				for(int x=l;x<r;){
					if(redo[o+x]==0){x++;continue;}
					if(own[o+x]!=0){redo[o+x]=0;x++;continue;}
					int start=x;while(x<r&&redo[o+x]!=0&&own[o+x]==0){redo[o+x]=0;x++;}
					SmoothScaler.scaleRun(algorithm,snap,width,height,y,start,x,s,hi,width*s,false);
				}
				if(l<=l0&&r>=r0){redoLeft[y]=Integer.MAX_VALUE;redoRight[y]=-1;}
				rowVersion[y]=v;
			};
			if(area*s*s>(1<<18)&&bottom-top>=8)java.util.stream.IntStream.rangeClosed(top,bottom).parallel().forEach(work);
			else for(int y=top;y<=bottom;y++)work.accept(y);
			int t=Integer.MAX_VALUE,b=-1;for(int y=redoTop;y<=redoBottom;y++)if(redoRight[y]>redoLeft[y]){if(y<t)t=y;b=y;}
			redoTop=t;redoBottom=b;
			return area;
		}
		/** Pixels {@code x0..x1-1} of row {@code y} were written at 1x: their hi-res blocks no longer stand for them (scaled up again). */
		synchronized void disown(int y,int x0,int x1){
			int o=y*width,run=-1;
			for(int x=x0;x<=x1;x++){boolean was=x<x1&&own[o+x]!=0;if(was){own[o+x]=0;if(run<0)run=x;}else if(run>=0){markChanged(y,run,x);run=-1;}}
		}
		/** Whether any pixel of the area was drawn at hi-res (copying it carries something the 1x pixels do not show). */
		synchronized boolean ownsAny(int x0,int y0,int x1,int y1){
			x0=Math.max(0,x0);y0=Math.max(0,y0);x1=Math.min(width,x1);y1=Math.min(height,y1);
			for(int y=y0;y<y1;y++){int o=y*width;for(int x=x0;x<x1;x++)if(own[o+x]!=0)return true;}
			return false;
		}
	}
}

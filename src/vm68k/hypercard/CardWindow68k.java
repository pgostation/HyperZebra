import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.RepaintManager;

/**
 * HyperCard's windows as a 68K external sees them: the card window and, while it is shown, the message box.
 *
 * HyperCard enters an XCMD with thePort set to the card window, and externals draw straight into it: UxFlash inverts a
 * rectangle of the card, ANIMATE blits frames, and many XFCNs only GetPort/FrontWindow to find out where the card is.
 * Others walk the low-memory WindowList for a window by title (UxFlashMsg flashes the window titled "message").
 * HyperZebra's card and message box are Swing components, not windows of the 68K VM, so the external gets a window
 * port per window, of the component's size, whose pixels start as a snapshot of it. Whatever it draws there is shown
 * over the component ({@link #overlay} on the card, painted by MyPanel; a glass pane on the message box) while the
 * external runs - flushed at every scheduler poll, so an animation that waits between frames is seen frame by frame -
 * and pixels that still differ from the snapshot when the external returns stay until the component is redrawn from
 * scratch, as pixels drawn into the real window would.
 *
 * The window records exist from the start (UxFlashMsg walks WindowList before its first trap), but the ports are
 * filled with the snapshots only at the external's first QuickDraw or Window Manager trap: a string XFCN never pays
 * for them.
 */
final class CardWindow68k {
	/** What the current external has drawn over the card, in card (logical) pixels; null when nothing. Read by MyPanel.paint. */
	static volatile BufferedImage overlay;
	/** The same for the message box (its glass pane paints it). */
	static volatile BufferedImage messageOverlay;
	/** Counts the times HyperCard put its card back into the window over what externals drew ({@link #cardRedrawn},
	 *  {@link #cardCopied}): screen memory a kept VM still holds from before is no copy of the window any more. */
	private static volatile int cardCopies;
	/** The video driver's gamma ramps an external set (cscSetGamma: Best of HC Externals' Fade), 768 entries as
	 *  {@link QuickDraw68k#gammaLookup}, or null for linear: the card window is shown through them (MyPanel.paint), as the screen
	 *  of a Mac is, until an external sets another table or the stack closes. */
	static volatile int[] gamma;
	/** The stack on show when {@link #overlay} / {@link #messageOverlay} were drawn: they go when it closes ({@link #stackClosing}). */
	private static volatile OStack overlayStack,messageOverlayStack;
	private static final boolean DEBUG=Boolean.getBoolean("hz68k.cardwin");

	/**
	 * One HyperCard window: its record in the VM, the pixels the external sees, the snapshot it started from and what is
	 * shown of it. The message box has a buffer of its own. The card window is the screen, as on a real Mac: its port's PixMap
	 * describes the screen device with the window at its place on it ({@link QuickDraw68k#describeScreenPort}), so drawing
	 * through the port and writes straight into screen memory (HyperTint) both land there.
	 */
	private final class Surface {
		final int record,width,height;final JComponent component;final int scale;final boolean message; // scale: percent (100 = 1x)
		/** A window of the picture command (null for the card and the message box); like the message box it has a buffer of its
		 *  own ({@link #own}) and shows what externals draw there through a glass pane ({@link #glass}). */
		final GPictWindow picture;final boolean own;PictureGlass glass;
		/** The rows of the component above the window's port: the message box's title bar is the windoid's frame, not its
		 *  content (UxFlashMsg inverts the portRect of the window titled "message": the bar and its close box flashed too). */
		final int top;
		/** The message box's own buffer {address, rowBytes}; for the card the screen rows under it, {address, length}, once tracked. */
		int base,rowBytes;int[] screenRows;
		/** The card as HyperZebra draws it, without what externals drew over it ({@link #snapshot}); null until then. */
		int[] original;
		/** What the window showed of the card then: {@link #original}, or the picture of the locked screen. */
		int[] shownPixels;
		/** The port's pixels as they read back right after the bare card was put there (the reference for what an external drew),
		 *  and the card window's place on the screen. */
		int[] bareBack;int screenLeft,screenTop;
		/** The pixels HyperCard's own update put there as its 1-bit card ({@link #redrawCard}), null when none since the bare card
		 *  went into the screen: only there is a pixel equal to that 1-bit card the card itself rather than drawing. */
		boolean[] hcCopied;
		/** Where HyperCard's 1-bit card ({@link #redrawCard}) is black: {@link CardWindow68k#hcInk} of {@link #original} then. */
		boolean[] hcBlack;int[] hcBlackOf;
		/** The bare card, place and screen depth/colour table the screen memory was last filled from (see {@link #snapshot}). */
		int[] lastBare;int lastLeft,lastTop,lastDepth,lastCopies;
		/** The part of the card under an external's windows in front of it (card coordinates), null when none: not the card's to
		 *  draw. A movie window floating over the card turned white when the card went back into screen memory under it. */
		QdRegion covered;
		/** {@link #frontKey} when {@link #covered} was last worked out: a window shown, hidden, moved or sent behind the card since
		 *  changes it ({@link CardWindow68k#followWindows}). */
		long frontKey;
		/** {@link #covered} when the bare card last went into the screen ({@link #snapshot}). */
		QdRegion coveredAtWrite;
		long shownSerial;final BufferedImage[] shown=new BufferedImage[2];int shownIndex;
		Surface(String title,JComponent component,int scale,boolean message) throws M68kCpuException {this(title,component,scale,message,null);}
		Surface(String title,JComponent component,int scale,boolean message,GPictWindow picture) throws M68kCpuException {
			this.component=component;this.scale=scale;this.message=message;this.picture=picture;own=message||picture!=null;top=message?GMsg.BAR_HEIGHT:0;
			if(message){width=GMsg.WIDTH;height=GMsg.HEIGHT-top;} // the box's size also while it is hidden (never laid out yet)
			else if(picture!=null){java.awt.image.BufferedImage bi=picture.label.bi;width=Math.max(1,bi==null?DisplayZoom.unscale(component.getWidth(),scale):bi.getWidth());height=Math.max(1,bi==null?DisplayZoom.unscale(component.getHeight(),scale):bi.getHeight());}
			else{width=Math.max(1,DisplayZoom.unscale(component.getWidth(),scale));height=Math.max(1,DisplayZoom.unscale(component.getHeight(),scale)-top);}
			record=tb.hyperCardWindowRecords&&picture==null?(message?Xcmd68k.MESSAGE_WINDOW_RECORD:Xcmd68k.CARD_WINDOW_RECORD):memory.allocate(WindowManager68k.WINDOW_SIZE,4);memory.fill(record,WindowManager68k.WINDOW_SIZE,0);
			qd.newWindowPort(record,width,height,true,true);
			// windowKind: HyperCard's own kinds - $1E a stack window, 8 a palette such as the message box (UDI's UxGetWindows finds
			// the stacks by $1E; the card window was a palette to it), and visible
			// (HC J-2.3 measured: the picture command's windoid, rect and shadow windows 8 as the palettes, its dialog, plain and zoom
			// ones 20; HyperZebra's picture windows with a title bar are windoids - their windowStyle - and only dialog/plain have a frame)
			memory.writeWord(record+108,picture==null?(message?8:0x1e):picture.isUndecorated()&&picture.getContentPane() instanceof JComponent&&((JComponent)picture.getContentPane()).getBorder()!=null?20:8);
			memory.writeByte(record+110,shown()?1:0);
			// goAwayFlag (HC J-2.3 measured): 1 for the message box and every picture window, 0 for the stack window. CompileIt!
			// externals read +112 as "visible": UxDraw's `Port message` drew on the card instead of the message box
			memory.writeByte(record+112,message||picture!=null?1:0);
			memory.writeLong(record+134,tb.api.createHandle(tb.readPascalBytesOf(title))); // titleHandle: externals find the message box by it
			int[] px=qd.windowPortPixels(record);base=px[0];rowBytes=px[1];
		}
		/** Whether the window is shown (its record's visible). */
		boolean shown(){return picture!=null?picture.isVisible():component.isShowing();}
		/** The card port describes the screen at the card window's current place (the screen's depth or the window may have changed). */
		void placeOnScreen() throws M68kCpuException {
			if(own)return;
			int[] place=screenPlace();screenLeft=place[0];screenTop=place[1];
			qd.describeScreenPort(record,screenTop,screenLeft);
			// the Window Manager's visRgn: the card less the windows in front of it, so drawing through the card port (HyperCard's
			// update CopyBits, HyperTint's bitsProc) leaves them alone as on a real Mac
			QdRegion all=QdRegion.rect(0,0,height,width),front=frontWindows(),before=covered;
			frontKey=frontKey();covered=front.isEmpty()?null:front;
			qd.setVisRgn(record,covered==null?all:QdRegion.combine(all,covered,QdRegion.DIFF));
			placeRegions(this);
			uncover(before);
		}
		/** The external windows in front of the card, in card coordinates: every shown window of the external but those the host
		 *  keeps behind the card (MacAppHost.behindHyperCard: one created behind it, sent there by `doMenu "next window"` -
		 *  xWindowFrame's backdrop - or one the card window was activated over, until it is activated again). Windoids (Movie's "floating" ones) stay over the card, and so do a modal dialog and a document
		 *  XWindow, as the frontmost windows on a Mac: Progress's dialog showed the next card's text in place of its own once the
		 *  card went back into screen memory under it, and xWindowFrame's full-screen frame let a PaintRect through the card port
		 *  blacken the card for good (the Window Manager clips it away with the card's visRgn). */
		private QdRegion frontWindows(){
			QdRegion r=QdRegion.empty();MacAppHost host=tb.hosted()?tb.host():null;
			for(WindowManager68k.Window w:new ArrayList<WindowManager68k.Window>(tb.windows.order)){
				if(!w.visible||w.service||w.width<=0||w.height<=0||tb.isDesktopWindow(w.record)||host!=null&&host.behindHyperCard(w.record))continue;
				int t=w.top-screenTop,l=w.left-screenLeft;
				r=QdRegion.combine(r,QdRegion.rect(t,l,t+w.height,l+w.width),QdRegion.UNION);
			}
			return r.isEmpty()?r:QdRegion.combine(r,QdRegion.rect(0,0,height,width),QdRegion.SECT);
		}
		/** What {@link #frontWindows} depends on, as one number: the external's windows, shown or not, where, behind the card or not. */
		long frontKey(){
			long k=1;MacAppHost host=tb.hosted()?tb.host():null;
			for(WindowManager68k.Window w:tb.windows.order){
				k=k*31+w.record;k=k*31+(w.visible?1:0);k=k*31+w.top;k=k*31+w.left;k=k*31+w.width;k=k*31+w.height;
				if(host!=null&&host.behindHyperCard(w.record))k=k*31+7;
			}
			return k;
		}
		/**
		 * A window in front of the card went away from (part of) {@code before}, the part it covered: the Window Manager gives the
		 * card window an update there and HyperCard copies its card back - into screen memory, which still held the window's
		 * pixels (seen as drawing on the card by the next flush). Outside a run of the external the next {@link #snapshot} puts
		 * the whole card back.
		 */
		private void uncover(QdRegion before) throws M68kCpuException {
			if(before==null)return;
			QdRegion gone=covered==null?before:QdRegion.combine(before,covered,QdRegion.DIFF);
			if(gone.isEmpty())return;
			if(original==null||bareBack==null||screenLeft!=lastLeft||screenTop!=lastTop){lastBare=null;return;}
			QdRegion keep=QdRegion.combine(QdRegion.rect(0,0,height,width),gone,QdRegion.DIFF);
			if(DEBUG)System.err.println("[cardwin] uncovered "+java.util.Arrays.toString(gone.bounds()));
			writeCard(screenTop,screenLeft,width,height,shownPixels!=null?shownPixels:original,keep.isEmpty()?null:keep);
			int[] back=qd.readScreenRgb(screenTop,screenLeft,width,height);
			if(back!=null)for(int[] r:gone.rects())for(int y=Math.max(0,r[0]);y<Math.min(height,r[2]);y++)for(int x=Math.max(0,r[1]);x<Math.min(width,r[3]);x++)bareBack[y*width+x]=back[y*width+x];
			// An external draws the card window at every copy of the card (ColorizeHC's CopyBits patch, HyperTint's bitsProc): on a
			// Mac it colours that update too (Card Control Palette: ShowList's dialog left a white hole in ColorizeHC's backdrop until
			// the card was opened again). One of this VM gets HyperCard's update of that part after the call (updateInvalidated); for
			// one in another VM its drawing as the window showed it when this run began goes back there - bareBack keeps the bare
			// card, so it is drawing to the next flush.
			int[] g=gone.bounds();
			if(drawingHooked())invalidate(g[0],g[1],g[2],g[3]);
			else if(drawnAtStart!=null&&Xcmd68k.cardDrawingHooked(stack))writeCard(screenTop,screenLeft,width,height,drawnAtStart,keep.isEmpty()?null:keep);
			coveredAtWrite=covered;
		}
		/** The card with the externals' drawing ({@link #overlay}) as it went into the screen at the last {@link #snapshot} that put it
		 *  there, null when there was no drawing. */
		int[] drawnAtStart;
		/**
		 * Fills the port with the window as it looks now; false when it is not showing (any more). For the card the reference
		 * is the card as HyperZebra draws it without the externals' drawing ({@link #bareBack}: what the screen gives back of
		 * it), and the port then shows that drawing on top: a later run that leaves those pixels alone keeps them on the card
		 * (HyperTint's picture stays while HTLock and the other calls touch other parts), one that puts the card's own pixels
		 * back clears them.
		 */
		boolean snapshot() throws M68kCpuException {
			int[] size=new int[2];int[] pixels=picture!=null?picturePixels(picture,width,height):capture(component,scale,size,!message);
			if(picture!=null){size[0]=width;size[1]=height;}
			if(pixels==null||size[0]!=width||size[1]-top!=height)return false;
			if(top>0)pixels=java.util.Arrays.copyOfRange(pixels,top*width,(top+height)*width);
			original=pixels;
			if(own){
				qd.writePortRgb(record,width,height,pixels);
				memory.trackBuffer(base,rowBytes*height);
				if(message)installGlassPane();else glass=PictureGlass.install(picture);
			}else{
				placeOnScreen();
				// what the window shows: the card, or while the screen is locked the picture of it taken then
				int[] locked=lockedPicture(component,scale,width,height);if(locked!=null)pixels=locked;
				shownPixels=pixels;
				// The screen under the card already holds this card with the externals' drawing (the last run left it so, and
				// HyperZebra drew nothing new meanwhile): it stays. Putting the bare card there again at every call and event
				// showed the black-and-white card between HyperTint's colour pictures, many times a second.
				int depth=qd.screenDepth()<<24^qd.screenTableSeed(); // the depth and the colour table the pixels were put there with
				// Not when HyperCard has put its card back meanwhile (go, unlock screen): the external's earlier drawing in screen
				// memory came back with its next drawing (UxMaruPeke's erased × beside the new ○)
				// nor when a window in front of the card has gone from where it was then (its pixels are still in screen memory)
				boolean same=bareBack!=null&&lastBare!=null&&java.util.Arrays.equals(pixels,lastBare)&&screenLeft==lastLeft&&screenTop==lastTop&&depth==lastDepth&&lastCopies==cardCopies&&!uncovered(coveredAtWrite,covered);
				if(!same){
					if(DEBUG)System.err.println("[cardwin] bare card into the screen at "+screenLeft+","+screenTop+" keeping "+(covered==null?"nothing":covered.rects().size()+" rects, first "+java.util.Arrays.toString(covered.rects().get(0))));
					writeCard(screenTop,screenLeft,width,height,pixels,covered);
					bareBack=qd.readScreenRgb(screenTop,screenLeft,width,height);hcCopied=null;
					BufferedImage drawn=overlay;drawnAtStart=null;
					if(bareBack!=null&&drawn!=null&&drawn.getWidth()==width&&drawn.getHeight()==height){
						int[] begin=pixels.clone();
						for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=drawn.getRGB(x,y);if((p>>>24)!=0)begin[y*width+x]=p&0xffffff;}
						writeCard(screenTop,screenLeft,width,height,begin,covered);drawnAtStart=begin;
					}
					lastBare=pixels;lastLeft=screenLeft;lastTop=screenTop;lastDepth=depth;lastCopies=cardCopies;coveredAtWrite=covered;
				}
				screenRows=bareBack==null?null:qd.screenRows(screenTop,height);
				if(screenRows!=null)memory.trackBuffer(screenRows[0],screenRows[1]);
			}
			shownSerial=memory.writeSerial();
			return true;
		}
		void flush(long now) throws M68kCpuException {
			if(original==null)return;
			int[] rgb,reference;
			if(own){
				if(memory.chunkSerial(base,rowBytes*height)<=shownSerial)return;
				rgb=qd.readPortRgb(record,width,height);reference=original;
			}else{
				if(screenRows==null||memory.chunkSerial(screenRows[0],screenRows[1])<=shownSerial)return;
				rgb=qd.readScreenRgb(screenTop,screenLeft,width,height);reference=bareBack;
				if(rgb==null||reference==null)return;
			}
			int[] bare=own?null:original;
			BufferedImage image=shown[shownIndex];
			if(image==null)image=shown[shownIndex]=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
			shownIndex^=1;
			int[] argb=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
			boolean any=false;
			QdRegion front=own?null:covered; // a window's own pixels there are not drawing on the card
			// HyperCard's own copy of the card (redrawCard) is black and white: where an external put that back (ColorizeHC copies
			// the whole card into the window) the card's text is HyperZebra's, not drawing - kept, it stayed over the text put
			// there later (G'S LAIR's message box showed the previous message under the new one). Only where that copy went:
			// elsewhere white or black drawn over the card is drawing (PgColorX's white rectangle let the card's anti-aliased
			// text show through it)
			boolean[] copied=hcCopied,black=hcBlack;if(black==null||black.length!=argb.length)copied=null;
			for(int i=0;i<argb.length;i++){int p=rgb[i]&0xffffff;if(p!=reference[i]&&(bare==null||copied==null||!copied[i]||p!=(black[i]?0:0xffffff))&&(front==null||!front.contains(i%width,i/width))){argb[i]=0xff000000|p;any=true;}else argb[i]=0;}
			shownSerial=now;
			if(DEBUG)System.err.println("[cardwin] flush "+(message?"message":"card")+" any="+any+" t="+System.currentTimeMillis()%100000);
			// the stack whose card is on show; while a stack closes that is an empty one already (the last flush of its externals
			// as their VMs end): the drawing stays the closing stack's
			PCARD pc=PCARD.pc;OStack shown=pc==null||pc.stack==null||pc.stack.curCard==null?null:pc.stack;
			if(message){messageOverlay=any?image:null;if(shown!=null)messageOverlayStack=shown;GMsg m=GMsg.msg;if(m!=null)m.getGlassPane().repaint();}
			else if(picture!=null){if(glass!=null)glass.show(any?image:null);}
			// shown at once while the screen is locked too: lock screen stops only HyperCard's own redraw of the card, an external
			// draws straight into the window (ANIMATE's intro, run from openCard under the closeCard's lock screen, never showed)
			else{overlay=any?image:null;if(shown!=null)overlayStack=shown;if(pc!=null&&pc.mainPane!=null)CardRepaintManager.repaintThroughLock(pc.mainPane);}
		}
		/** Stops watching the pixels (the snapshot is dropped). */
		void untrack(){
			if(original==null)return;
			if(own)memory.untrackBuffer(base,rowBytes*height);
			else if(screenRows!=null)memory.untrackBuffer(screenRows[0],screenRows[1]);
			screenRows=null;original=null;
		}
		void close(){untrack();}
	}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final QuickDraw68k qd;
	private final List<Surface> surfaces=new ArrayList<Surface>();
	private final OStack stack;
	/** The card window's record: thePort on entry and FrontWindow() for an external without windows of its own. */
	final int record;
	private boolean closed,snapshots;

	/** Opens the windows for an external of {@code stack}, or returns null when no card is on show. A card window the stack hid
	 *  (hide card window) is there too, invisible, as in HyperCard: 天気部予報's UxRgnCard runs between hide and show card window
	 *  and reshapes the window's regions through thePort (with no record it read a port at 0 and failed at $FFFFFF). */
	static CardWindow68k open(Toolbox68k tb,OStack stack) throws M68kCpuException {
		PCARD pc=PCARD.pc;if(pc==null||pc.stack!=stack||pc.mainPane==null||pc.mainPane.getWidth()<=0||pc.mainPane.getHeight()<=0)return null;
		return new CardWindow68k(tb,stack);
	}

	/** _CopyBits as it was when the card window was made, before the externals ran ({@link #drawingHooked}). */
	private final int copyBitsAtOpen;
	private CardWindow68k(Toolbox68k tb,OStack stack) throws M68kCpuException {
		this.tb=tb;this.memory=tb.memory;this.qd=tb.api.quickDraw();this.stack=stack;
		copyBitsAtOpen=tb.cpu==null?0:tb.cpu.patchedTrap(0xa8ec);
		gammaSeen=qd.gammaVersion();seedGamma();
		Surface card=new Surface(stack.getWindowTitle(),PCARD.pc.mainPane,PCARD.scalePercent(),false);surfaces.add(card);record=card.record;
		card.placeOnScreen();
		// HyperCard's message box is in the WindowList also while it is hidden (UxMsgBar finds it by its title "message"; made only
		// when it showed as this VM started, it answered "Not exist msg window" for good when the box came up later). Its title is
		// "Message" (HyperCard J-2.3 measured, Oct 24 2026): Palette XFCN walks the list to the window titled exactly "Message"
		// without looking for the list's end, and ran off it into an endless loop on "message"
		GMsg msg=GMsg.msg;
		if(msg!=null)surfaces.add(new Surface("Message",(JComponent)msg.getContentPane(),100,true));
		addPictures();
		qd.setPortTo(tb.cpu,record);
	}

	/** {@link #cardCopies} when the card's port was last filled. */
	private int snapshotCopies;
	/** The external is about to draw: the ports take the windows' current pixels (once - again for the card when HyperCard
	 *  has drawn another card into the window meanwhile: UxCardToPICS goes to each card with SendCardMessage and copies the
	 *  card port's pixels, and every frame was the first card). */
	void snapshot() throws M68kCpuException {
		if(closed)return;
		followWindows();
		if(snapshots){
			if(snapshotCopies==cardCopies)return;
			snapshotCopies=cardCopies;Surface card=surfaces.get(0);card.untrack();card.snapshot();
			if(DEBUG)System.err.println("[cardwin] card snapshot again after HyperCard drew the card");
			return;
		}
		snapshots=true;snapshotCopies=cardCopies;if(DEBUG)System.err.println("[cardwin] snapshot t="+System.currentTimeMillis()%100000);
		messageOverlay=null; // drawing left by an earlier external on the message box is part of its snapshot now (the card's stays an overlay, see Surface.snapshot)
		for(Surface s:surfaces)s.snapshot();
	}

	/** The windows of the picture command, as HyperCard 2.x makes them (its built-in Picture XCMD's windows are in the WindowList):
	 *  UxDraw's `Port "<picture window>"` draws into one, UxCardImage2Res captures one ("Not found window" before). A picture
	 *  window opened since the VM was made joins at the next call; one closed meanwhile leaves the chain. */
	private void addPictures() throws M68kCpuException {
		java.util.Set<GPictWindow> have=new java.util.HashSet<GPictWindow>();
		for(Surface s:surfaces)if(s.picture!=null)have.add(s.picture);
		List<OWindow> list;synchronized(OWindow.list){list=new ArrayList<OWindow>(OWindow.list);}
		for(OWindow w:list){GPictWindow g=w.gpw;
			if(g==null||w.movieHandle!=null||g.label==null||!g.isDisplayable()||have.contains(g))continue;
			// a windoid in front of the message box, the other styles behind it, all in front of the card (HC J-2.3: the WindowList
			// starts with the picture windoid; records() lists the surfaces in reverse)
			Surface s=new Surface(g.name==null?"":g.name,g.label,PCARD.scalePercent(),false,g);
			int at=surfaces.size();if(g.isUndecorated())while(at>1&&(surfaces.get(at-1).message||surfaces.get(at-1).picture!=null&&!surfaces.get(at-1).picture.isUndecorated()))at--;
			surfaces.add(at,s);}
	}
	/** Picture windows closed since the last call leave the WindowList; new ones join it. True when the list changed. */
	private boolean syncPictures() throws M68kCpuException {
		boolean changed=false;
		for(java.util.Iterator<Surface> i=surfaces.iterator();i.hasNext();){Surface s=i.next();
			if(s.picture!=null&&!s.picture.isDisplayable()){s.close();i.remove();changed=true;}}
		int before=surfaces.size();addPictures();
		return changed||surfaces.size()!=before;
	}
	/** The picture's own pixels (what the window shows of it at 1x, before it is scrolled), {@code width} x {@code height}. */
	private static int[] picturePixels(GPictWindow picture,int width,int height){
		java.awt.image.BufferedImage bi=picture.label==null?null:picture.label.bi;if(bi==null)return null;
		int[] pixels=new int[width*height];
		for(int y=0;y<height&&y<bi.getHeight();y++)for(int x=0;x<width&&x<bi.getWidth();x++){int p=bi.getRGB(x,y);pixels[y*width+x]=(p>>>24)<0x80?0xffffff:p&0xffffff;}
		return pixels;
	}

	/** The records of the windows, front to back, for the WindowList chain. */
	List<Integer> records(){List<Integer> r=new ArrayList<Integer>();for(int i=surfaces.size()-1;i>=0;i--)r.add(surfaces.get(i).record);return r;} // the message box floats above the card

	/** The component's pixels in logical coordinates (it is drawn at {@code scale} percent of that), {width,height} in {@code size};
	 *  {@code bare}: the card without the externals' {@link #overlay}. */
	private static int[] capture(JComponent component,int scale,int[] size,boolean bare){
		final BufferedImage[] holder=new BufferedImage[1];
		PCARD.runOnEDT(() -> {
			int w=component.getWidth(),h=component.getHeight();if(w<=0||h<=0||!component.isShowing())return;
			BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
			Graphics2D g=image.createGraphics();
			BufferedImage drawn=overlay;
			try{
				if(bare)overlay=null; // on the event thread: no paint of the card sees it missing
				if(component==(PCARD.pc==null?null:PCARD.pc.mainPane))VEffect.paintCurrentPanel(g);
				else{RepaintManager manager=RepaintManager.currentManager(component);boolean buffered=manager.isDoubleBufferingEnabled();manager.setDoubleBufferingEnabled(false);
					try{component.printAll(g);}finally{manager.setDoubleBufferingEnabled(buffered);}}
			}finally{g.dispose();if(bare&&overlay==null)overlay=drawn;}
			holder[0]=image;
		});
		return logicalPixels(holder[0],scale,size);
	}
	/** While the screen is locked with a picture of it (VEffect.oldoff), that picture in logical pixels: the card window still
	 *  shows the card as it was when the lock began, and an external drawing there (ANIMATE under openCard's lock) draws over
	 *  that, not over the card HyperCard keeps offscreen. Null when the screen is not locked so. */
	private static int[] lockedPicture(JComponent component,int scale,int width,int height){
		final BufferedImage[] holder=new BufferedImage[1];
		PCARD.runOnEDT(() -> {
			BufferedImage frozen=VEffect.oldoff;int w=component.getWidth(),h=component.getHeight();
			if(!PCARD.lockedScreen||frozen==null||w<=0||h<=0)return;
			BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
			Graphics2D g=image.createGraphics();
			try{g.drawImage(frozen,0,0,w,h,null);}finally{g.dispose();}
			holder[0]=image;
		});
		int[] size=new int[2];int[] pixels=logicalPixels(holder[0],scale,size);
		return pixels!=null&&size[0]==width&&size[1]==height?pixels:null;
	}
	private static int[] logicalPixels(BufferedImage image,int scale,int[] size){
		if(image==null)return null;
		int w=DisplayZoom.unscale(image.getWidth(),scale),h=DisplayZoom.unscale(image.getHeight(),scale);if(w<=0||h<=0)return null;
		int[] pixels=new int[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels[y*w+x]=image.getRGB(Math.min(image.getWidth()-1,DisplayZoom.scale(x,scale)),Math.min(image.getHeight()-1,DisplayZoom.scale(y,scale)))&0xffffff;
		size[0]=w;size[1]=h;return pixels;
	}

	/** The message box shows {@link #messageOverlay} through a glass pane that lets mouse events through to the field. */
	private static void installGlassPane(){
		PCARD.runOnEDT(() -> {
			GMsg msg=GMsg.msg;if(msg==null||msg.getGlassPane() instanceof MessageGlass)return;
			JComponent glass=new MessageGlass();glass.setOpaque(false);msg.setGlassPane(glass);glass.setVisible(true);
		});
	}
	private static final class MessageGlass extends JComponent {
		@Override public boolean contains(int x,int y){return false;} // never the target of a mouse event
		@Override protected void paintComponent(Graphics g){BufferedImage image=messageOverlay;if(image!=null)g.drawImage(image,0,GMsg.BAR_HEIGHT,null);}
	}

	/** A picture window's glass pane: what externals drew into its port, over the picture (at the picture's place and zoom). */
	static final class PictureGlass extends JComponent {
		private static final long serialVersionUID=1L;
		private final GPictWindow window;volatile BufferedImage image;
		private PictureGlass(GPictWindow window){this.window=window;}
		static PictureGlass install(GPictWindow window){
			final PictureGlass[] made=new PictureGlass[1];
			PCARD.runOnEDT(() -> {
				if(window.getGlassPane() instanceof PictureGlass){made[0]=(PictureGlass)window.getGlassPane();return;}
				PictureGlass glass=new PictureGlass(window);glass.setOpaque(false);window.setGlassPane(glass);glass.setVisible(true);made[0]=glass;
			});
			return made[0];
		}
		void show(BufferedImage drawn){image=drawn;repaint();}
		@Override public boolean contains(int x,int y){return false;} // clicks go to the picture
		@Override protected void paintComponent(Graphics g){
			BufferedImage drawn=image;MyLabel3 label=window.label;if(drawn==null||label==null)return;
			java.awt.Point at=javax.swing.SwingUtilities.convertPoint(label,0,0,this);double z=PCARD.scale();
			Graphics2D g2=(Graphics2D)g.create();
			try{g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				g2.drawImage(drawn,at.x-(int)Math.round(label.scrollX*z),at.y-(int)Math.round(label.scrollY*z),(int)Math.round(drawn.getWidth()*z),(int)Math.round(drawn.getHeight()*z),null);}
			finally{g2.dispose();}
		}
	}

	/**
	 * HyperZebra's card goes into screen memory at the card window's place: as HyperCard's own update would put it there, not as an
	 * external's drawing. The Window Manager's windows must not take those pixels for theirs: Yennie's listDialog (DLOG made behind
	 * every window, raised once drawn) showed the card in its content, the host having adopted the card written under it as
	 * direct screen drawing (MacAppHost.adoptDeviceWrites).
	 */
	private void writeCard(int top,int left,int width,int height,int[] pixels,QdRegion keep) throws M68kCpuException {
		qd.writeScreenRgb(top,left,width,height,pixels,keep);
		int[] f=qd.mainDeviceFrame();if(f==null)return;
		int base=f[0],rowBytes=f[1],depth=f[2],devLeft=f[3],devTop=f[4],devRight=f[5],devBottom=f[6];if(depth<=0)return;
		for(int v=0;v<height;v++){int gy=top+v;if(gy<devTop||gy>=devBottom)continue;int row=base+(gy-devTop)*rowBytes;
			for(int x=0;x<width;){if(keep!=null&&keep.contains(x,v)){x++;continue;}int run=x;while(run<width&&(keep==null||!keep.contains(run,v)))run++;
				int gx0=Math.max(devLeft,left+x),gx1=Math.min(devRight,left+run);
				if(gx1>gx0){int o0=row+((gx0-devLeft)*depth>>>3),o1=row+(((gx1-devLeft)*depth+7)>>>3);memory.forgetDeviceWrites(o0,o1-o0);}
				x=run;}}
	}

	/** Whether some of {@code before} (a region windows covered) is not covered by {@code now}. */
	private static boolean uncovered(QdRegion before,QdRegion now){
		if(before==null||before.isEmpty())return false;
		return now==null||!QdRegion.combine(before,now,QdRegion.DIFF).isEmpty();
	}

	/** A window of the external was shown, hidden, moved or sent behind the card since the card's visRgn was worked out (NewXWindow
	 *  and then a PaintRect through the card port in the same call: xWindowFrame): the card port follows. */
	private void followWindows() throws M68kCpuException {
		Surface card=surfaces.get(0);
		if(card.frontKey!=card.frontKey())card.placeOnScreen();
	}

	/** Shows what the external has drawn since the last flush; a no-op while the buffers are untouched. */
	void flush() throws M68kCpuException {
		if(closed)return;
		followWindows();followGamma();
		long now=memory.writeSerial();
		for(Surface s:surfaces){
			if(now==s.shownSerial)continue; // nothing tracked was written since, or not this window
			// HyperCard put its card back over the external's drawing since the port was filled (UxPlayMovie's own unlock screen
			// after its last frame): the screen memory holds the old drawing, which is not on the card any more
			if(!s.own&&snapshotCopies!=cardCopies)continue;
			s.flush(now);}
	}

	/** Between two runs of a persistent external: its drawing is shown, the snapshots are dropped so the next drawing starts
	 *  from the windows as they look then (the records and ports stay - the external may hold the card's WindowPtr). */
	void refresh() throws M68kCpuException {
		if(closed)return;
		flush();
		for(Surface s:surfaces)s.untrack();
		snapshots=false;placed=false; // the window may have moved meanwhile
	}

	/**
	 * PaintOne/PaintBehind reached the card window over the global rectangle given (the clobbered region's box): HyperCard gets
	 * an update event there and copies its card back, so the bare card goes back into the screen and the next flush finds no
	 * drawing of the externals in that part.
	 */
	void repaint(int top,int left,int bottom,int right) throws M68kCpuException {
		if(closed)return;
		snapshot();
		Surface card=surfaces.get(0);if(card.original==null||card.bareBack==null)return;
		int t=Math.max(0,top-card.screenTop),l=Math.max(0,left-card.screenLeft),b=Math.min(card.height,bottom-card.screenTop),r=Math.min(card.width,right-card.screenLeft);
		if(b<=t||r<=l)return;
		QdRegion keep=QdRegion.combine(QdRegion.rect(0,0,card.height,card.width),QdRegion.rect(t,l,b,r),QdRegion.DIFF);
		if(card.covered!=null)keep=QdRegion.combine(keep,card.covered,QdRegion.UNION); // the external's windows in front stay
		writeCard(card.screenTop,card.screenLeft,card.width,card.height,card.original,keep.isEmpty()?null:keep);
	}

	/** The external returned: show its last drawing and stop tracking the buffers (the VM goes away with them). */
	void close(){
		if(closed)return;
		try{flush();}catch(M68kCpuException ignored){}
		followGamma();
		closed=true;for(Surface s:surfaces)s.close();
	}

	/** {@link QuickDraw68k#gammaVersion} of this VM's driver when the card window last took its table. */
	private int gammaSeen;
	/** The external changed the video driver's gamma table: the card window shows the screen through it. */
	private void followGamma(){
		int version=qd.gammaVersion();if(version==gammaSeen)return;
		gammaSeen=version;gamma=qd.gammaLookup();gammaStack=stack;
		if(DEBUG)System.err.println("[cardwin] gamma "+(gamma==null?"linear":"128 -> "+gamma[128]));
		PCARD pc=PCARD.pc;if(pc!=null&&pc.mainPane!=null)CardRepaintManager.repaintThroughLock(pc.mainPane);
	}
	/** The stack whose external set {@link #gamma}. */
	private static volatile OStack gammaStack;
	/** A VM made while a gamma table is in force starts with it in its driver: there is one video driver, and the external that
	 *  faded the screen out may fade it back in from another VM (cscGetGamma gives it the table it left). */
	private void seedGamma(){
		int[] table=gamma;if(table==null)return;
		try{
			int t=memory.allocate(12+768,2);byte[] b=new byte[12+768];
			b[7]=3;b[8]=1;b[11]=8; // gChanCnt 3, gDataCnt 256, gDataWidth 8
			for(int i=0;i<768;i++)b[12+i]=(byte)table[i];
			memory.load(t,b);try{qd.setGamma(t);}finally{memory.free(t);} // the driver keeps a copy
		}catch(M68kCpuException e){if(DEBUG)System.err.println("[cardwin] gamma not seeded: "+e.getMessage());}
		gammaSeen=qd.gammaVersion();
	}

	/** The card was redrawn from scratch (another card, a resize): nothing an external drew is on it any more - unless an
	 *  external draws the card window itself whenever HyperCard copies the card there (HyperTint), which then has its say. */
	static void cardRedrawn(){
		PCARD pc=PCARD.pc;
		if(pc!=null&&pc.stack!=null&&Xcmd68k.redrawHookedCards(pc.stack))return;
		overlay=null;cardCopies++;
	}

	/**
	 * HyperCard copied its whole card into the window again - unlock screen, a change of tool - so what an external drew there
	 * is gone (UxMaruPeke: "lock screen / unlock screen [with dissolve] erases the ○×"), unless an external draws the window at
	 * every such copy (HyperTint, ColorizeHC: the caller has it redraw). The caller repaints.
	 */
	static void cardCopied(){
		PCARD pc=PCARD.pc;
		if(overlay==null||pc!=null&&pc.stack!=null&&Xcmd68k.cardDrawingHooked(pc.stack))return;
		overlay=null;overlayStack=null;cardCopies++;
		if(pc!=null&&pc.mainPane!=null)pc.mainPane.repaint();
	}

	/** HyperCard copied a part's rectangle of its card into the window (card coordinates; a part shown or hidden): what an
	 *  external drew there is gone, the rest stays. */
	static void partCopied(int top,int left,int bottom,int right){
		BufferedImage drawn=overlay;PCARD pc=PCARD.pc;
		if(drawn==null||pc!=null&&pc.stack!=null&&Xcmd68k.cardDrawingHooked(pc.stack))return;
		int t=Math.max(0,top),l=Math.max(0,left),b=Math.min(drawn.getHeight(),bottom),r=Math.min(drawn.getWidth(),right);
		if(b<=t||r<=l)return;
		BufferedImage kept=new BufferedImage(drawn.getWidth(),drawn.getHeight(),BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=kept.createGraphics();
		try{g.drawImage(drawn,0,0,null);g.setComposite(java.awt.AlphaComposite.Clear);g.fillRect(l,t,r-l,b-t);}finally{g.dispose();}
		overlay=kept;cardCopies++;
		if(pc!=null&&pc.mainPane!=null)pc.mainPane.repaint();
	}

	/** A part of the card on show moved or changed size (set the loc/rect/topLeft... of a visible button or field) while the screen
	 *  is not locked: HyperCard copies its old and new rectangles of the card into the window, and what an external drew there is
	 *  gone (PgColorX's card "部品の移動": a button dragged over the colour picture erases that part of it). */
	static void partMoved(OObject part,int oldTop,int oldLeft,int oldBottom,int oldRight){
		PCARD pc=PCARD.pc;
		if(overlay==null||PCARD.lockedScreen||pc==null||pc.stack==null||pc.stack.curCard==null||!part.getVisible())return;
		if(part.parent!=pc.stack.curCard&&part.parent!=pc.stack.curCard.bg)return;
		partCopied(oldTop,oldLeft,oldBottom,oldRight);
		partCopied(part.top,part.left,part.top+part.height,part.left+part.width);
	}

	/** The message box was hidden: shown again it is drawn afresh, without what externals drew in it (UxMsgBar's bar, UxSubMsg's
	 *  text: "hiding the message box erases the bar"). */
	static void messageBoxHidden(){
		if(messageOverlay==null)return;
		messageOverlay=null;messageOverlayStack=null;
		GMsg m=GMsg.msg;if(m!=null)m.getGlassPane().repaint();
	}

	/** {@code stack} closed: what its externals drew over the card and the message box goes with it. The overlay outlived the
	 *  stack (closing G'S LAIR on its first card, where ColorizeHC draws the title, left the title over the next stack opened:
	 *  only a go to another card cleared it). A stack that has the card already (go to another stack) keeps its own drawing. */
	static void stackClosing(OStack stack){
		if(overlay!=null&&Xcmd68k.sameStack(overlayStack,stack)){overlay=null;overlayStack=null;PCARD pc=PCARD.pc;if(pc!=null&&pc.mainPane!=null)pc.mainPane.repaint();}
		if(messageOverlay!=null&&Xcmd68k.sameStack(messageOverlayStack,stack)){messageOverlay=null;messageOverlayStack=null;GMsg m=GMsg.msg;if(m!=null)m.getGlassPane().repaint();}
		// a stack that faded the screen out and closed does not leave the next one dark
		if(gamma!=null&&Xcmd68k.sameStack(gammaStack,stack)){gamma=null;gammaStack=null;PCARD pc=PCARD.pc;if(pc!=null&&pc.mainPane!=null)pc.mainPane.repaint();}
	}

	/** The card window as it was shown before a change that an external redraws ({@link #holdScreen}); MyPanel paints it
	 *  instead of the card while not null. */
	static volatile BufferedImage held;

	/** The screen keeps showing what it shows now until {@link #releaseScreen}: HyperCard draws a part shown or hidden into
	 *  its copy of the card first and the window gets it only through CopyBits, which the external hooked. */
	static void holdScreen(){
		PCARD.runOnEDT(() -> {
			PCARD pc=PCARD.pc;if(pc==null||pc.mainPane==null)return;
			int w=pc.mainPane.getWidth(),h=pc.mainPane.getHeight();if(w<=0||h<=0)return;
			BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
			Graphics2D g=image.createGraphics();
			try{VEffect.paintCurrentPanel(g);}finally{g.dispose();}
			held=image;
		});
	}
	static void releaseScreen(){
		PCARD.runOnEDT(() -> {
			held=null;
			PCARD pc=PCARD.pc;if(pc!=null&&pc.mainPane!=null)pc.mainPane.repaint();
		});
	}

	// ------------------------------------------------------------------ HyperCard's own drawing of the card

	private boolean placed;private int placeLeft,placeTop;
	/** Each call of an external: the card port describes the screen as it is now (SetMode changed its depth, the window moved). */
	void syncScreenPort() throws M68kCpuException {
		if(closed)return;
		surfaces.get(0).placeOnScreen();
		if(syncPictures())tb.windows.refreshLinks();
		for(Surface s:surfaces)memory.writeByte(s.record+110,s.shown()?1:0); // the window shown or hidden since
	}

	/**
	 * SetWTitle of the card window (Rinaldi's WindName: GetPort, SetWTitle): the record's title, and HyperZebra's window takes it
	 * as HyperCard's does, its name included (HC 2.2 measured: the name of cd window, the windows; kept by go). The message box
	 * has no title to change. False when {@code w} is none of these windows.
	 */
	boolean setTitle(int w,String title) throws M68kCpuException {
		if(!isWindowRecord(w))return false;
		if(w!=record||closed)return true;
		int old=memory.readLong(w+134);if(old!=0)tb.api.disposeHandleAndFree(old);
		memory.writeLong(w+134,tb.api.createHandle(tb.readPascalBytesOf(title)));
		stack.cardWindowTitle=title; // the script's next line already names the window so
		javax.swing.SwingUtilities.invokeLater(()->{
			PCARD pc=PCARD.pc;if(pc==null||pc.stack!=stack)return;
			pc.setTitle(PaintTool.editBackground?"/// "+title+" ///":title);
		});
		return true;
	}
	/** GetWTitle of one of these windows: the title in its record. */
	String title(int w) throws M68kCpuException {
		int h=memory.readLong(w+134);if(h==0)return "";int p=memory.readLong(h);
		return p==0?"":tb.readPascal(p);
	}

	/** True for the record of one of these windows (the card window, the message box). */
	boolean isWindowRecord(int w){
		for(Surface s:surfaces)if(s.record==w)return true;
		return false;
	}

	/** {left, top} of the card window on the screen the external sees (LocalToGlobal in its port; where its pixels are in screen memory). */
	int[] screenPlace(){
		if(!placed){placed=true;
			try{java.awt.Rectangle r=TUtil.cardWindowRect();java.awt.Point p=tb.host().toGuest(new java.awt.Point(r.x,r.y));placeLeft=p.x;placeTop=p.y;}
			catch(RuntimeException e){placeLeft=placeTop=0;}}
		return new int[]{placeLeft,placeTop};
	}

	/**
	 * SizeWindow of the card window: HyperCard's window shows that much of the card, as "set the rect of the card window" makes it
	 * (a width or height of 0 or less is ignored, as by the Window Manager). The port keeps its size for the rest of this call.
	 */
	void sizeWindow(int width,int height){
		if(closed||width<=0||height<=0||PCARD.pc==null||PCARD.pc.mainPane==null)return;
		java.awt.Rectangle r=TUtil.cardWindowRect();
		TUtil.setCardWindowRect(new java.awt.Rectangle(r.x,r.y,PCARD.toPhysical(width),PCARD.toPhysical(height)));
		placed=false;
	}
	/** The card window's content on the screen the external sees (global {top, left, bottom, right}), null while it is hidden:
	 *  FindWindow answers it for a point there (Drag Window.CIT's windowDrag, Grow Stack's dragGrowWindow and Mask It!'s growBox
	 *  take the clickLoc to FindWindow, then DragWindow/GrowWindow the window it gives; with none they did nothing). */
	int[] contentRect(){
		if(closed)return null;Surface card=surfaces.get(0);if(!card.shown())return null;
		int[] place=screenPlace();return new int[]{place[1],place[0],place[1]+card.height,place[0]+card.width};
	}
	/** HideWindow/ShowWindow of the card window: HyperZebra's card window hides or shows as by `hide/show card window` (HideHyper:
	 *  FrontWindow, HideWindow - "the HyperCard window will disappear"; nothing happened). */
	void showWindow(boolean visible) throws M68kCpuException {
		if(closed)return;
		memory.writeByte(record+110,visible?1:0);
		javax.swing.SwingUtilities.invokeLater(()->{
			PCARD pc=PCARD.pc;if(pc==null||pc.stack!=stack||pc.isVisible()==visible)return;
			if(!visible)GUI.mouseDown=false; // the hidden window never gets the mouse-up (as TUtil's hide window of the card window)
			pc.setVisible(visible);
		});
	}
	/** MoveWindow of the card window: its content's top left goes to the global point (h, v) the external sees. */
	void moveWindow(int h,int v){
		if(closed||PCARD.pc==null||PCARD.pc.mainPane==null)return;
		java.awt.Rectangle r=TUtil.cardWindowRect();java.awt.Point at=tb.host().toHost(h,v);
		TUtil.setCardWindowRect(new java.awt.Rectangle(at.x,at.y,r.width,r.height));
		placed=false;
	}

	// ------------------------------------------------------------------ the window's shape

	/** The rectangle last written into the card record's strucRgn and contRgn (global), null before: what tells the window's
	 *  own regions from a shape an external gave them. */
	private int[] regionRect;
	/** The strucRgn HyperZebra's window was last shaped by ({@link #applyShape}, global), null while it has no shape. */
	private static volatile QdRegion shapeShown;
	/** show titleBar took the shape away (a frame with decorations has none). */
	static void shapeDropped(){shapeShown=null;}
	/** The card record's strucRgn and contRgn: the window's rectangle on the screen (global, as the Window Manager keeps them), kept
	 *  up with the window while they are that rectangle. A region an external put there (UDI's UxRgnCard) stays. */
	private void placeRegions(Surface card) throws M68kCpuException {
		int[] rect={card.screenTop,card.screenLeft,card.screenTop+card.height,card.screenLeft+card.width};
		QdRegion now=QdRegion.rect(rect[0],rect[1],rect[2],rect[3]);
		for(int offset:new int[]{114,118}){
			int handle=memory.readLong(card.record+offset);
			if(handle==0){handle=tb.api.createHandle(new byte[10]);memory.writeLong(card.record+offset,handle);}
			else if(regionRect!=null&&!qd.regionShapeOf(handle).sameShape(QdRegion.rect(regionRect[0],regionRect[1],regionRect[2],regionRect[3])))continue;
			qd.writeRegionShape(handle,now);
		}
		regionRect=rect;
	}
	/**
	 * The external returned: a strucRgn it made other than the window's rectangle is the shape of HyperZebra's window, as the
	 * Window Manager shows a window only in its structure region. 天気部予報 hides the card window and its title bar, and UDI's
	 * UxRgnCard sets the region to the card's opaque parts (GetMaskAndData, BitMapToRegion): only the weather girl's box floats
	 * on the desktop. The rectangle again (UxRgnCard false) gives the whole window back. Only a window without a title bar
	 * takes a shape (the frame's decorations are the host's); show titleBar drops it ({@link TTalk#setTitleBarVisible}).
	 */
	void applyShape() throws M68kCpuException {
		if(closed||regionRect==null)return;
		Surface card=surfaces.get(0);
		int handle=memory.readLong(card.record+114);if(handle==0)return;
		QdRegion struc=qd.regionShapeOf(handle);
		boolean whole=struc.isEmpty()||struc.sameShape(QdRegion.rect(regionRect[0],regionRect[1],regionRect[2],regionRect[3]));
		QdRegion shown=shapeShown;
		if(whole?shown==null:shown!=null&&struc.sameShape(shown))return; // as it is (most calls: no shape at all)
		java.awt.geom.Area area=null;
		if(!whole){
			area=new java.awt.geom.Area();
			for(int[] r:struc.rects()){
				int t=DisplayZoom.scale(r[0]-card.screenTop,card.scale),l=DisplayZoom.scale(r[1]-card.screenLeft,card.scale);
				int b=DisplayZoom.scale(r[2]-card.screenTop,card.scale),rt=DisplayZoom.scale(r[3]-card.screenLeft,card.scale);
				if(b>t&&rt>l)area.add(new java.awt.geom.Area(new java.awt.Rectangle(l,t,rt-l,b-t)));
			}
		}
		final java.awt.geom.Area shape=area;
		PCARD.runOnEDT(() -> {
			PCARD pc=PCARD.pc;if(pc==null||pc.mainPane==null||!pc.isUndecorated())return;
			try{
				if(shape==null){pc.setShape(null);shapeShown=null;return;}
				java.awt.Point origin=javax.swing.SwingUtilities.convertPoint(pc.mainPane,0,0,pc);
				shape.transform(java.awt.geom.AffineTransform.getTranslateInstance(origin.x,origin.y));
				pc.setShape(shape);shapeShown=struc;
			}catch(UnsupportedOperationException|java.awt.IllegalComponentStateException e){} // no shaped windows on this system
		});
	}

	/**
	 * The card ({@code width} x {@code height} RGB pixels) as it is in HyperCard's 1-bit copy of it: true where it is black.
	 * HyperCard's card is 1-bit, so all of its text ink is black there; HyperZebra draws the text anti-aliased with host fonts,
	 * and a thin stroke (a 9-point "l", the "ll" that runs together into a grey smear, Mincho's horizontal strokes) is lighter
	 * than half grey: by darkness alone ColorizeHC's coloured copy of "Hello world" read "He o wor d". So besides what is darker
	 * than half grey, the darkest grey pixel across a stroke one or two pixels wide is black too: one with clearly lighter
	 * pixels on both sides of the stroke in its row or column. Flat light areas and the edges of wider ones, and coloured pixels
	 * (a colour stack's light colours, a picture's) stay white.
	 */
	static boolean[] hcInk(int[] rgb,int width,int height){
		int n=width*height;
		int[] lum=new int[n];boolean[] grey=new boolean[n];
		for(int i=0;i<n;i++){
			int p=rgb[i],r=p>>16&255,g=p>>8&255,b=p&255;
			lum[i]=(r*30+g*59+b*11)/100;
			grey[i]=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=INK_CHROMA;
		}
		boolean[] black=new boolean[n];
		for(int y=0;y<height;y++)for(int x=0;x<width;x++){
			int i=y*width+x,v=lum[i];
			black[i]=v<128||v<INK_LIGHTEST&&grey[i]&&(strokeMiddle(lum,i,1,x,width)||strokeMiddle(lum,i,width,y,height));
		}
		return black;
	}
	/** Ink lighter than this is no stroke (an anti-aliased glyph's faint fringe), nor a pixel more colourful than INK_CHROMA;
	 *  the pixels on both sides of a stroke are at least INK_CONTRAST lighter than all of it. */
	private static final int INK_LIGHTEST=224,INK_CHROMA=32,INK_CONTRAST=16;
	/** {@code lum[i]} is the darkest (the first of two equal) of a stroke one or two pixels across along the line ({@code step}
	 *  apart, {@code pos} of {@code length}; outside the card counts as white). */
	private static boolean strokeMiddle(int[] lum,int i,int step,int pos,int length){
		int v=lum[i];
		int before=pos>0?lum[i-step]:255,after=pos<length-1?lum[i+step]:255;
		if(before-v>=INK_CONTRAST&&after-v>=INK_CONTRAST)return true; // one pixel across
		// two across: with the pixel after it (as dark or lighter) or the one before (lighter)
		if(after>=v){int beyond=pos<length-2?lum[i+2*step]:255;if(before-after>=INK_CONTRAST&&beyond-after>=INK_CONTRAST)return true;}
		if(before>v){int beyond=pos>1?lum[i-2*step]:255;if(after-before>=INK_CONTRAST&&beyond-before>=INK_CONTRAST)return true;}
		return false;
	}

	/** HyperCard's offscreen copy of the card (a 1-bit BitMap record, its rectangles and pixels) and the _CopyBits stub. */
	private int hcBitMap,hcPixels,hcStub,hcRowBytes;

	/** An external put its own bitsProc on the card window's port (HyperTint) or patched _CopyBits (ColorizeHC, with
	 *  SetTrapAddress): it draws its colour picture whenever HyperCard copies the card into the window. */
	boolean drawingHooked() throws M68kCpuException {
		if(closed||tb.cpu==null)return false;
		if(tb.cpu.patchedTrap(0xa8ec)!=copyBitsAtOpen)return true;
		// the card port's colours are not black on white: HyperCard's CopyBits of its card into the window goes through them, so
		// the whole card comes out in them at every such copy (HyperThings' CardColor and Tbx's ForeColor/BackColor + lock/unlock
		// screen: red on yellow; HyperZebra's own redraw ignored them and only an InvalRect update showed the colours)
		if(!qd.plainColors(record))return true;
		int procs=memory.readLong(record+104);if(procs==0)return false;
		int proc=memory.readLong(procs+8*4);
		return proc!=0&&proc!=tb.cpu.trapRomAddress(QuickDraw68k.stdProcTrap(8));
	}

	/**
	 * HyperCard's update of the card window once an external returns (or hands it the update event with SendHCEvent): what is
	 * invalid in the card window ({@link #invalidate}: InvalRect/InvalRgn on the card port) is copied anew from HyperCard's
	 * card and is valid again, as after BeginUpdate/EndUpdate. The copy goes through the port's colours as HyperCard's CopyBits
	 * does: PgFlashX swaps the card port's fore and back colours and invalidates the card, and the card blinks inverted until the
	 * next call swaps them back. True when there was something to update.
	 */
	boolean updateInvalidated(M68kCpu cpu) throws M68kException {
		int[] r=invalid;invalid=null;
		if(closed||r==null)return false;
		if(DEBUG)System.err.println("[cardwin] update "+r[0]+","+r[1]+","+r[2]+","+r[3]);
		redrawCard(cpu,r[0],r[1],r[2],r[3]);
		return true;
	}
	/** The bounds of what is invalid in the card window (card coordinates), null when nothing. The card window is no window of
	 *  the VM's Window Manager (HyperZebra draws the card), so its update region is kept here. */
	private int[] invalid;
	/** InvalRect/InvalRgn on the card port: HyperCard updates that part after the call ({@link #updateInvalidated}). */
	void invalidate(int top,int left,int bottom,int right){
		if(bottom<=top||right<=left)return;
		int[] r=invalid;
		invalid=r==null?new int[]{top,left,bottom,right}:new int[]{Math.min(r[0],top),Math.min(r[1],left),Math.max(r[2],bottom),Math.max(r[3],right)};
	}
	/** ValidRect/ValidRgn on the card port: nothing is left to update once it covers what was invalid. */
	void validate(int top,int left,int bottom,int right){
		int[] r=invalid;
		if(r!=null&&top<=r[0]&&left<=r[1]&&bottom>=r[2]&&right>=r[3])invalid=null;
	}

	/**
	 * HyperCard redraws (part of) the card window as it does for an update event: CopyBits from its 1-bit offscreen copy of
	 * the card into the window's port, srcCopy, through the trap dispatcher, so a patched _CopyBits and the port's bitsProc
	 * see it. HyperZebra draws the card itself, so this is only done for an external that hooked that drawing
	 * ({@link #drawingHooked}): HyperTint's HTLock forceFalse sends HyperCard an update event (SendHCEvent) and paints the
	 * picture it prepared from its bitsProc. The rectangle is in card coordinates.
	 */
	void redrawCard(M68kCpu cpu,int top,int left,int bottom,int right) throws M68kException {
		if(closed)return;
		snapshot();
		Surface card=surfaces.get(0);int[] bare=card.original;if(bare==null)return;
		int w=card.width,h=card.height;
		top=Math.max(0,top);left=Math.max(0,left);bottom=Math.min(h,bottom);right=Math.min(w,right);
		if(bottom<=top||right<=left)return;
		if(hcBitMap==0||hcRowBytes!=((w+15)/16)*2){
			hcRowBytes=((w+15)/16)*2;
			hcBitMap=memory.allocate(14+16,2);hcPixels=memory.allocate(hcRowBytes*h,2);
			// the call's return address is kept aside so _CopyBits finds its arguments on top of the stack, as after a caller's
			// own pushes: MOVE.L (A7)+,slot; _CopyBits; MOVE.L slot,-(A7); RTS
			hcStub=memory.allocate(20,2);int slot=hcStub+16;
			memory.writeWord(hcStub,0x23df);memory.writeLong(hcStub+2,slot);memory.writeWord(hcStub+6,0xa8ec);
			memory.writeWord(hcStub+8,0x2f39);memory.writeLong(hcStub+10,slot);memory.writeWord(hcStub+14,0x4e75);
		}
		// the card in black and white, as HyperCard keeps it
		boolean[] black=card.hcBlack;
		if(black==null||card.hcBlackOf!=bare){black=hcInk(bare,w,h);card.hcBlack=black;card.hcBlackOf=bare;}
		byte[] bits=new byte[hcRowBytes*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(black[y*w+x])bits[y*hcRowBytes+(x>>3)]|=(byte)(0x80>>>(x&7));
		memory.load(hcPixels,bits);
		memory.writeLong(hcBitMap,hcPixels);memory.writeWord(hcBitMap+4,hcRowBytes);
		memory.writeWord(hcBitMap+6,0);memory.writeWord(hcBitMap+8,0);memory.writeWord(hcBitMap+10,h);memory.writeWord(hcBitMap+12,w);
		int rect=hcBitMap+14;
		for(int r=rect;r<rect+16;r+=8){memory.writeWord(r,top);memory.writeWord(r+2,left);memory.writeWord(r+4,bottom);memory.writeWord(r+6,right);}
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;int savedPort=qd.currentPortAddress();
		try{
			qd.setPortTo(cpu,record);
			cpu.pushLong(hcBitMap);cpu.pushLong(record+2);cpu.pushLong(rect);cpu.pushLong(rect+8);cpu.pushWord(0);cpu.pushLong(0);
			cpu.callPascal(hcStub,22);
		}finally{
			System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;
			if(savedPort!=0)qd.setPortTo(cpu,savedPort);
		}
		// the pixels that are HyperCard's 1-bit card now (Surface.flush shows HyperZebra's card there)
		int[] now=card.message?null:qd.readScreenRgb(card.screenTop,card.screenLeft,w,h);
		if(now!=null){boolean[] copied=card.hcCopied;if(copied==null||copied.length!=w*h)copied=new boolean[w*h];
			for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){int i=y*w+x;copied[i]=(now[i]&0xffffff)==(black[i]?0:0xffffff);}
			card.hcCopied=copied;}
		if(DEBUG)System.err.println("[cardwin] HyperCard redraw "+top+","+left+","+bottom+","+right);
		flush();
	}
}

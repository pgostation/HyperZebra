import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The screen HyperCard externals see: a classic screen one size up from the card window, round it, instead of the whole
 * display. An external that covers "the screen" (ColorCover's black window, xWindowFrame's backdrop) made a 512x342 card
 * float in the middle of a 2560x1440 black display - on a Mac of the time the cover was a small screen's worth round the card.
 * The externals' VMs made from then on get the smallest screen of {@link #SIZES} after the one the card window fits (card
 * pixels): up to 512x384 a 640x480 screen, up to 640x480 800x600, up to 800x600 1024x768 and so on, magnified with the card
 * and laid round the card window, its frame centred below the screen's menu-bar band. A card window bigger than all but the
 * last, or a screen that does not fit the display with its frame, leaves them the whole display.
 *
 * All the externals' hosts with that screen share it ({@link #join}): one place on the display, moved together. While one of
 * their windows covers the screen it gets the frame the game screens of programs have (ScreenWindowFrame): dragging it moves the
 * screen with the card window and the externals' windows, a double click on its title bar centres them on the display. The card
 * window moves on the screen as on a Mac; dragged past its edge, the screen follows it (the card window stays on it).
 * -Dhz.xcmdscreen=false: externals see the whole display as before.
 */
final class ExternalScreenArea {
	private ExternalScreenArea(){}
	static final boolean ENABLED=!"false".equals(System.getProperty("hz.xcmdscreen"));
	/** Classic screen sizes {width, height}: a card window that fits one gets the next (512x384 is the 12" display's). */
	static final int[][] SIZES={{512,384},{640,480},{800,600},{1024,768},{1152,870},{1280,1024},{1600,1200}};
	/** The screen externals see for a card window of that size (card pixels), {width, height}; null: the display. */
	static int[] sizeFor(int cardWidth,int cardHeight){
		for(int i=0;i+1<SIZES.length;i++)if(cardWidth<=SIZES[i][0]&&cardHeight<=SIZES[i][1])return SIZES[i+1].clone();
		return null;
	}

	private static final List<MacAppHost> hosts=new CopyOnWriteArrayList<MacAppHost>();
	// the scripts see the externals' screen too while one is up (TUtil.scriptsFrame): Process Monitor's resizeHCWindow rect goes
	// back into `set the rect of card window`, and Semi-Modal Window takes its window rect from `the rect of card window`
	static{TUtil.scriptsFrame=ExternalScreenArea::scriptsFrame;}
	private static TUtil.ExternalFrame scriptsFrame(){
		for(MacAppHost h:hosts){Rectangle s=h.externalScreen();if(s!=null)return new TUtil.ExternalFrame(s,h::toGuestRect);}
		return keptFrame();
	}
	/**
	 * No external's VM is up (before the stack's first external call; between the calls of externals whose VMs end each time),
	 * but the current stack (or a stack it uses) has 68K externals: the scripts still see the screen those externals get, the
	 * one the next VM joins ({@link #join} keeps its origin). Semi-Modal Window Demo's first click took `the rect of card window`
	 * before any external ran, in display coordinates (1024,529,...): its XWindow then lay "off the screen" of the 640x480 the
	 * external sees. A stack without 68K externals keeps the display's coordinates.
	 */
	private static TUtil.ExternalFrame keptFrame(){
		if(!ENABLED||java.awt.GraphicsEnvironment.isHeadless())return null;
		PCARD pc=PCARD.pc;if(pc==null||pc.stack==null||!pc.isShowing()||!hasExternals(pc.stack))return null;
		Rectangle card;try{card=TUtil.cardWindowRect();}catch(RuntimeException e){return null;}
		int pct=MacAppHost.externalPercent();
		int[] size=sizeFor(DisplayZoom.unscale(card.width,pct),DisplayZoom.unscale(card.height,pct));if(size==null)return null;
		Point o;
		synchronized(ExternalScreenArea.class){
			if(!hosts.isEmpty())return null; // one joined meanwhile: the next ask gets its screen
			o=around(pc,card,pct,size[0],size[1]);if(o==null)return null;
			origin=o;percent=pct;width=size[0];height=size[1];
		}
		final Point at=o;
		return new TUtil.ExternalFrame(new Rectangle(0,0,size[0],size[1]),r->{
			int x=DisplayZoom.unscale(r.x-at.x,pct),y=DisplayZoom.unscale(r.y-at.y,pct);
			return new Rectangle(x,y,DisplayZoom.unscale(r.width,pct),DisplayZoom.unscale(r.height,pct));});
	}
	/** Whether {@code stack} or one of the stacks it uses has 68K externals (XCMD/XFCN; lower-case PowerPC ones do not run in HC stacks). */
	static boolean hasExternals(OStack stack){
		java.util.List<OStack> stacks=new java.util.ArrayList<OStack>();stacks.add(stack);if(stack.usingStacks!=null)stacks.addAll(stack.usingStacks);
		for(OStack st:stacks){Rsrc r=st==null?null:st.rsrc!=null?st.rsrc:st.closedResources;if(r==null)continue;
			for(Rsrc.xcmdClass x:r.getXcmdResources())if(x.platform!=null&&x.platform.toLowerCase().contains("68k"))return true;}
		return false;
	}
	/** The screen round the card window when no host holds it (guarded by the class): the last one moved as little as needed to
	 *  hold the card window again, when it has this size and magnification (what the scripts or the last VM saw stays), else laid
	 *  round the card window anew. */
	private static Point around(PCARD pc,Rectangle card,int pct,int sw,int sh){
		if(origin!=null&&percent==pct&&width==sw&&height==sh){
			Rectangle hold=held(pc,card,pct,sw,sh);Rectangle usable=MacAppHost.usableAreaAround(hold);
			int[] f=outsets(pct);if(usable==null||len(sw,pct)+f[1]+f[3]<=usable.width&&len(sh,pct)+f[0]+f[2]<=usable.height)return keepOn(origin,hold,usable,pct,sw,sh);
		}
		return placeAround(pc,card,pct,sw,sh);
	}
	/** The screen's origin on the display (host pixels), its size (screen pixels) and its magnification; guarded by the class. */
	private static Point origin;private static int percent=100,width,height;
	// EDT only below
	private static ScreenWindowFrame frame;
	private static ComponentAdapter cardListener;private static Window listenedCard;
	private static java.beans.PropertyChangeListener focusListener;
	private static Point dragOrigin,dragCard;
	/** The screen is being moved with the card window: the card window's own move reports are this class's placing until then. */
	private static long movingUntil;
	private static javax.swing.Timer settle;
	private static final long QUIET_NANOS=400_000_000L;

	/**
	 * A new external host: its screen when it gets one round the card window - origin on the display (host pixels) and size
	 * (screen pixels): the shared one, or one laid round the card window now - else null (the whole display). {@code pct}: the
	 * card's magnification the host shows the external at.
	 */
	static Rectangle join(MacAppHost host,int pct){
		if(!ENABLED||java.awt.GraphicsEnvironment.isHeadless())return null;
		PCARD pc=PCARD.pc;if(pc==null||pc.stack==null)return null;
		Rectangle card;try{card=TUtil.cardWindowRect();}catch(RuntimeException e){return null;}
		int[] size=sizeFor(DisplayZoom.unscale(card.width,pct),DisplayZoom.unscale(card.height,pct));if(size==null)return null;
		Point o;
		synchronized(ExternalScreenArea.class){
			if(origin!=null&&percent==pct&&width==size[0]&&height==size[1]&&!hosts.isEmpty())o=origin;
			else{o=around(pc,card,pct,size[0],size[1]);if(o==null)return null;origin=o;percent=pct;width=size[0];height=size[1];}
			hosts.add(host);
		}
		final Point moved=o;
		javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){listen();for(MacAppHost h:hosts)if(h!=host)h.moveExternalScreenTo(moved);update();}});
		return new Rectangle(o.x,o.y,size[0],size[1]);
	}
	/** The host went away (its VM ended). */
	static void leave(MacAppHost host){
		if(!hosts.remove(host))return;
		javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){if(hosts.isEmpty())stopListening();update();}});
	}
	/** The host's card zoom changed ({@link MacAppHost#followStackZoom}): the screen at that magnification, round the card window again. */
	static Point rezoomed(MacAppHost host,int pct){
		PCARD pc=PCARD.pc;Point o;
		synchronized(ExternalScreenArea.class){
			if(pct==percent&&origin!=null)return origin;
			Rectangle card;try{card=TUtil.cardWindowRect();}catch(RuntimeException e){card=null;}
			o=pc==null||card==null?null:placeAround(pc,card,pct,width,height);
			if(o==null)o=origin;
			origin=o;percent=pct;
		}
		final Point moved=o;
		javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){for(MacAppHost h:hosts)if(h!=host)h.moveExternalScreenTo(moved);placeFrame();}});
		return o;
	}

	private static int len(int v,int pct){return DisplayZoom.scale(v,pct);}
	private static int[] outsets(int pct){int[] o=ScreenWindowFrame.PLATINUM.outsets();for(int i=0;i<4;i++)o[i]=len(o[i],pct);return o;}
	/** What the screen keeps on it: the card window with its title bar when that fits below the menu-bar band, else the card part. */
	private static Rectangle held(PCARD pc,Rectangle card,int pct,int sw,int sh){
		Rectangle win=pc.getBounds();
		if(pc.isShowing()&&win.contains(card)&&win.width<=len(sw,pct)&&win.height<=len(sh-MacAppHost.MENU_BAR_HEIGHT,pct))return win;
		return card;
	}
	/** The screen laid round the card window: the window centred below the menu-bar band, the frame on the display. */
	private static Point placeAround(PCARD pc,Rectangle card,int pct,int sw,int sh){
		Rectangle hold=held(pc,card,pct,sw,sh);
		int w=len(sw,pct),h=len(sh,pct),band=len(MacAppHost.MENU_BAR_HEIGHT,pct);
		Point want=new Point(hold.x+hold.width/2-w/2,hold.y+hold.height/2-band-(h-band)/2);
		Rectangle usable=MacAppHost.usableAreaAround(hold);if(usable==null)usable=MacAppHost.usableDisplayBounds();
		int[] f=outsets(pct);
		if(w+f[1]+f[3]>usable.width||h+f[0]+f[2]>usable.height)return null; // no room on the display: the display as before
		return keepOn(want,hold,usable,pct,sw,sh);
	}
	/**
	 * {@code want} moved as little as needed for the screen to hold {@code hold} below its menu-bar band, with its frame on
	 * {@code usable}. When both cannot be had the band gives way first (the card window under it), then the card window (at the
	 * very edge of the display it sticks out of the screen: macOS keeps a window's frame off its menu bar, so a title bar put
	 * above the display came down over the card window's own and took its clicks).
	 */
	static Point keepOn(Point want,Rectangle hold,Rectangle usable,int pct,int sw,int sh){
		int w=len(sw,pct),h=len(sh,pct),band=len(MacAppHost.MENU_BAR_HEIGHT,pct);int[] f=outsets(pct);
		int[] dx=usable==null?null:new int[]{usable.x+f[1],usable.x+usable.width-w-f[3]},dy=usable==null?null:new int[]{usable.y+f[0],usable.y+usable.height-h-f[2]};
		int x=range(want.x,new int[]{hold.x+hold.width-w,hold.x},null,dx);
		int y=range(want.y,new int[]{hold.y+hold.height-h,hold.y-band},new int[]{hold.y+hold.height-h,hold.y},dy);
		return new Point(x,y);
	}
	/** {@code v} within {@code strict} (or else {@code loose}) and {@code display} when they meet; else as near {@code loose} as {@code display} allows. */
	private static int range(int v,int[] strict,int[] loose,int[] display){
		for(int[] r:new int[][]{strict,loose}){
			if(r==null||r[0]>r[1])continue;
			if(display==null)return clamp(v,r[0],r[1]);
			int a=Math.max(r[0],display[0]),b=Math.min(r[1],display[1]);if(a<=b)return clamp(v,a,b);
		}
		int[] r=loose!=null&&loose[0]<=loose[1]?loose:strict;
		if(r[0]<=r[1])v=clamp(v,r[0],r[1]); // as near holding the card window as the display allows
		return display!=null&&display[0]<=display[1]?clamp(v,display[0],display[1]):v;
	}
	private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

	/** Moves every external's screen to {@code o} (EDT). */
	private static void moveTo(Point o){
		synchronized(ExternalScreenArea.class){if(o.equals(origin))return;origin=o;}
		for(MacAppHost h:hosts)h.moveExternalScreenTo(o);
		placeFrame();
	}
	/** The card window moved or changed size: the screen follows it as far as needed to keep it (EDT). */
	private static void cardMoved(){
		if(hosts.isEmpty()||movingUntil!=0&&System.nanoTime()-movingUntil<0)return;
		PCARD pc=PCARD.pc;Point o;int pct,sw,sh;synchronized(ExternalScreenArea.class){o=origin;pct=percent;sw=width;sh=height;}
		if(pc==null||!pc.isShowing()||o==null)return;
		Rectangle card;try{card=TUtil.cardWindowRect();}catch(RuntimeException e){return;}
		Rectangle hold=held(pc,card,pct,sw,sh);
		moveTo(keepOn(o,hold,MacAppHost.usableAreaAround(hold),pct,sw,sh));
	}
	private static void listen(){
		PCARD pc=PCARD.pc;
		if(listenedCard!=pc){
			if(listenedCard!=null&&cardListener!=null)listenedCard.removeComponentListener(cardListener);
			if(cardListener==null)cardListener=new ComponentAdapter(){
				@Override public void componentMoved(ComponentEvent e){cardMoved();}
				@Override public void componentResized(ComponentEvent e){cardMoved();}
				@Override public void componentShown(ComponentEvent e){cardMoved();update();}
				@Override public void componentHidden(ComponentEvent e){update();}
			};
			listenedCard=pc;if(pc!=null)pc.addComponentListener(cardListener);
		}
		if(focusListener==null){focusListener=new java.beans.PropertyChangeListener(){@Override public void propertyChange(java.beans.PropertyChangeEvent e){if(frame!=null&&frame.isShowing())placeFrame();}};
			java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow",focusListener);}
	}
	private static void stopListening(){
		if(listenedCard!=null&&cardListener!=null)listenedCard.removeComponentListener(cardListener);listenedCard=null;
		if(focusListener!=null){java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("activeWindow",focusListener);focusListener=null;}
		if(frame!=null){frame.dispose();frame=null;}
		// the origin stays: the scripts of a stack with externals go on seeing that screen (keptFrame), and the next VM joins it
	}

	/** Shows or hides the frame round the screen: shown while a window of an external covers it and the card window shows (EDT). */
	static void update(){
		PCARD pc=PCARD.pc;boolean show=false;
		if(!hosts.isEmpty()&&pc!=null&&pc.isShowing())for(MacAppHost h:hosts)if(h.fullScreenWindowShown()){show=true;break;}
		if(!show){if(frame!=null)frame.hide();return;}
		if(frame==null)frame=new ScreenWindowFrame(title(),new ScreenWindowFrame.Mover(){
			@Override public void pressed(){PCARD c=PCARD.pc;if(c!=null&&c.isShowing()&&java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()!=c)c.toFront();}
			@Override public void dragStarted(){synchronized(ExternalScreenArea.class){dragOrigin=origin;}PCARD c=PCARD.pc;dragCard=c==null?null:c.getLocation();}
			@Override public void draggedBy(int dx,int dy){
				if(dragOrigin==null)return;
				Rectangle usable=null;try{java.awt.PointerInfo pi=java.awt.MouseInfo.getPointerInfo();if(pi!=null){Point p=pi.getLocation();usable=MacAppHost.usableAreaAround(new Rectangle(p.x,p.y,1,1));}}catch(Exception ignored){}
				moveWithCard(onDisplay(new Point(dragOrigin.x+dx,dragOrigin.y+dy),usable));}
			@Override public void dragEnded(){dragOrigin=null;dragCard=null;}
			@Override public void centre(){
				Point o;int pct,sw,sh;synchronized(ExternalScreenArea.class){o=origin;pct=percent;sw=width;sh=height;}if(o==null)return;
				Rectangle usable=MacAppHost.usableAreaAround(new Rectangle(o.x,o.y,len(sw,pct),len(sh,pct)));if(usable==null)return;
				dragOrigin=o;PCARD c=PCARD.pc;dragCard=c==null?null:c.getLocation();
				moveWithCard(onDisplay(new Point(usable.x+(usable.width-len(sw,pct))/2,usable.y+(usable.height-len(sh,pct))/2),usable));
				dragOrigin=null;dragCard=null;}
		});
		placeFrame();
	}
	/** The screen's origin moved onto {@code usable} with its frame (the display under the pointer, else the one it is on). */
	private static Point onDisplay(Point o,Rectangle usable){
		int pct,sw,sh;synchronized(ExternalScreenArea.class){pct=percent;sw=width;sh=height;}
		int w=len(sw,pct),h=len(sh,pct);int[] f=outsets(pct);
		if(usable==null)usable=MacAppHost.usableAreaAround(new Rectangle(o.x,o.y,w,h));
		if(usable==null)return o;
		return new Point(Math.max(usable.x+f[1],Math.min(usable.x+usable.width-w-f[3],o.x)),Math.max(usable.y+f[0],Math.min(usable.y+usable.height-h-f[2],o.y)));
	}
	/** Moves the screen to {@code o} and the card window by as much from where the drag started (EDT). */
	private static void moveWithCard(Point o){
		movingUntil=System.nanoTime()+QUIET_NANOS;
		moveTo(o);
		PCARD c=PCARD.pc;
		if(c!=null&&dragCard!=null&&dragOrigin!=null){Point to=new Point(dragCard.x+o.x-dragOrigin.x,dragCard.y+o.y-dragOrigin.y);if(!c.getLocation().equals(to))c.setLocation(to);}
		// once the moves are over, the card window's place counts again (it was put on the screen; a late report changes nothing)
		if(settle==null){settle=new javax.swing.Timer((int)(QUIET_NANOS/1_000_000L),new java.awt.event.ActionListener(){@Override public void actionPerformed(java.awt.event.ActionEvent e){movingUntil=0;cardMoved();}});settle.setRepeats(false);}
		settle.restart();
	}
	private static String title(){PCARD pc=PCARD.pc;String n=pc!=null&&pc.stack!=null?pc.stack.name:null;return n==null?"":n;}
	private static void placeFrame(){
		ScreenWindowFrame f=frame;if(f==null||hosts.isEmpty())return;
		Point o;int pct,sw,sh;synchronized(ExternalScreenArea.class){o=origin;pct=percent;sw=width;sh=height;}if(o==null)return;
		Window active=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		boolean on=active!=null&&(active==PCARD.pc||f.owns(active));
		if(!on&&active!=null)for(MacAppHost h:hosts)if(h.ownsWindow(active)){on=true;break;}
		f.show(new Rectangle(o.x,o.y,len(sw,pct),len(sh,pct)),sw,sh,pct,ScreenWindowFrame.PLATINUM,on);
	}

	/** Test hooks: the shared screen's origin (null when none) and the frame's title bar while shown. */
	static Point originForTest(){synchronized(ExternalScreenArea.class){return origin==null?null:new Point(origin);}}
	static Rectangle titleBarForTest(){ScreenWindowFrame f=frame;return f!=null?f.titleBarBounds():null;}
	/** Test hook: drags the frame by host pixels as the user would (EDT). */
	static void dragForTest(int dx,int dy){
		synchronized(ExternalScreenArea.class){dragOrigin=origin;}PCARD c=PCARD.pc;dragCard=c==null?null:c.getLocation();
		if(dragOrigin!=null)moveWithCard(onDisplay(new Point(dragOrigin.x+dx,dragOrigin.y+dy),null));
		dragOrigin=null;dragCard=null;
	}
}

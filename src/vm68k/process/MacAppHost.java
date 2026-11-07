import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.color.ColorSpace;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * Swing side of a running 68K application ("Wine-like" hosting).
 *
 * Every classic window becomes a native frame or dialog whose content is a
 * BufferedImage mapped directly over the window's PixMap inside VM memory, so
 * both the emulated QuickDraw and native Java2D drawing (controls, dialogs,
 * TextEdit) end up in the same pixels. Mouse and keyboard input is translated
 * to classic EventRecords, the menu bar is a native JMenuBar whose selections
 * are reported through synthetic menu-bar events, and dropped files are turned
 * into Open Documents Apple events.
 */
final class MacAppHost {
	static final int MENU_BAR_HEIGHT=20;
	/** Global coordinates used for synthetic menu-bar mouse events (a host menu choice, a host close box): a point IN the menu
	 *  bar, as a real click there has. Flash 4's Windows layer turns inMenuBar into WM_NCLBUTTONDOWN/HTMENU and only calls
	 *  MenuSelect when the point is in the bar; at -30000,-30000 no menu command ever reached it. The made-up events are told
	 *  from real clicks by their menuId (EventManager68k.Event), not by this point. */
	static final int MENU_EVENT_V=1,MENU_EVENT_H=1;

	interface Listener {
		void postMouse(boolean down,int globalH,int globalV,int modifiers,long when);
		void postKey(int what,int keyCode,int charCode,int modifiers);
		void postMenuSelection(int menuId,int item);
		void postMenuKey(int menuId,int item,int charCode,int modifiers);
		/** The user opened menu {@code menuId} of the host bar: the program may update it first (its MBarHook, see
		 *  MenuManager68k.prepareOpening); true when it did so within {@code timeoutMillis}. */
		default boolean menuOpening(int menuId,long timeoutMillis){return false;}
		void postActivate(int window,boolean active);
		void postUpdate(int window);
		void postWindowMoved(int window,int globalLeft,int globalTop);
		/** The host moved a window onto the display by itself ({@link MacAppHost#keepOnDisplay}), not the user. */
		default void postWindowPlaced(int window,int globalLeft,int globalTop){postWindowMoved(window,globalLeft,globalTop);}
		void postWindowResized(int window,int width,int height);
		void postGoAway(int window);
		/** The host item that stands for tearing a picture menu off (see {@link MenuModel#tearOff}) was chosen. */
		void postTearOff(int menuId);
		void postZoom(int window);
		void postOpenDocuments(List<File> files);
		/** Files dragged from the host's file manager over a window (DragManager68k.EXTERNAL_MOVE/EXIT/DROP, global coordinates;
		 *  {@code files} null while the host does not tell them during the drag). A drop no receive handler takes opens the files. */
		default void postExternalDrag(int phase,int globalH,int globalV,List<File> files,int modifiers){if(phase==DragManager68k.EXTERNAL_DROP&&files!=null&&!files.isEmpty())postOpenDocuments(files);}
		void postQuit();
		/** A dialog of the program now waits for the user (ModalDialog/Alert, a file dialog): HyperZebra's quit notes it (EventManager68k). */
		default void dialogShown(){}
		void postSuspendResume(boolean resume);
		/** Mouse wheel over a window: {@code steps} lines (positive = down/right) toward the nearest scroll bar. */
		void postWheel(int globalH,int globalV,int steps,boolean horizontal);
		/** Global {x, y, height} of the active TextEdit insertion point, or null (input method windows go there). */
		int[] textCaret();
		/** Text from the host input method for the Text Services Manager's active document: {@code committed} is final,
		 *  {@code composing} still being entered ({@code styles}: one TSM hilite style per character of it, {@code caret}
		 *  the caret's character index within it). True when an inline-input document took it - the host then neither
		 *  types the text as key events nor shows its own composition window. */
		boolean postInputMethodText(String committed,String composing,int[] styles,int caret);
	}

	final class HostWindow {
		final int record;JFrame frame;JDialog dialog;Window window;Canvas canvas;
		int procId;boolean visible,decorated,resizable,closable;String title="";
		/** Created visible by the program, shown by the host in a moment (see {@link #isFrontmostVisible}). */
		volatile boolean showPending;
		/** The frame kind has a grow region (documentProc/zoomDocProc): the host frame becomes resizable once the program draws its grow icon. */
		boolean growable;
		/** In Carbon's floating layer (WindowManager68k.Window.floatingLayer): kept over the documents, active along with the
		 *  front document instead of taking the activation from it. */
		boolean floatingLayer;
		/** A Process Manager system window (WindowManager68k.createSystemWindow, A-Dock's dock): above every program's windows
		 *  and never focused - clicking it leaves the front program active. */
		boolean systemWindow;
		/** The window's frame is drawn by the program's own WDEF into the buffer: the host shows it bare, at the structure's size. */
		boolean customFrame;
		/** A standard-frame window covering the program's whole screen, title bar out of sight above it (MacPaint's desk with
		 *  the tool and pattern palettes): shown bare at its own place, under the macOS menu bar as under the Mac's, never
		 *  moved onto the usable area - a native title bar would push the palettes away from the document window over them. */
		boolean backdrop;
		/** The part of a backdrop an external's screen round the card window shows, host pixels from the content's top-left
		 *  ({@link MacAppHost#screenCut}), else null: all of it. */
		Dimension screenCut;
		/** The window has been shown at least once (until then a backdrop is decided again when it is sized or moved). */
		boolean everShown;
		/** Direct screen writes are not taken into this window's buffer (the Finder's desktop: its zoom rectangles and drag
		 *  outlines are XORed on the screen and would otherwise stay behind in pieces). */
		boolean ignoresDeviceWrites;
		/** An external's window sent behind HyperCard's windows (SendBehind to the back or behind the card window): the host
		 *  keeps it under the card window (a native window this host does not stack) and never focuses it. */
		boolean behindHyperCard;
		/** HyperCard's card window was brought in front of this external's window by the user or the OS (a click on the card, a
		 *  switch back to HyperZebra with the card the key window): the window is behind the card as on a Mac, so the card's drawing
		 *  is not clipped under it (CardWindow68k: Dartmouth's ListWindow, CalendarPallete's windows beside a card the user
		 *  clicks). Unlike {@link #behindHyperCard} it stays focusable: activated again (clicked), it is in front again. */
		volatile boolean underCard;
		/** A floating XWindoid of a HyperCard external (Movie XCMD's movie windows): over the card window, but HyperCard's card
		 *  window stays the active one and keeps the keyboard - the host never focuses it. */
		boolean windoid;
		/** Outline of a custom frame whose structure region is not a rectangle (frame-relative), else null. */
		java.awt.Shape shape;
		BufferedImage image;int imageBase,imageWidth,imageHeight,imageRowBytes;
		/** Bits per pixel of the buffer: 32 (xRGB) or the indexed/16-bit depth of the hosted screen device (image is null then). */
		int imageDepth=32;
		int contentLeft,contentTop,width,height;boolean ignoreMoves;
		/** The user put the window where it is (a native drag, or DragWindow of a frame drawn in the window): like a native frame
		 *  it may lie anywhere on the display, beyond the program's game screen; a move by the program itself clears it. */
		boolean userPlaced;
		/** Until then (System.nanoTime) the user drags the window's frame drawn in it (DragWindow): the native window's move
		 *  reports are this host's own placing, late on macOS. */
		volatile long userDragUntil;
		/** Where the native frame sits relative to the program's idea of the window (virtual pixels): non-zero for a window
		 *  the program placed on its screen with the title bar out of sight (content at the top edge), which the display
		 *  cannot show there, and for one the user dragged past the program's screen ({@link #placeForProgram}). The program
		 *  keeps its position; points over this window are shifted back by it. */
		int dispX,dispY;
		/** Where {@link #keepOnDisplay} last took the window from (virtual content top-left), until the program's next move. */
		int[] keptFrom;
		/** The program moved the window back where {@link #keepOnDisplay} had taken it from: it stays there, beyond the screen's
		 *  edge as on a real Mac, and out of sight when wholly beyond it. 何かon林檎 hides a titled helper window below its 640x480
		 *  screen and hangs a balloon over the right edge with MoveWindow, and moved them back at every event (white box at the
		 *  bottom left, balloon over the ghost, ~30 MoveWindow calls a second). */
		boolean insisted;
		/** Program-initiated resizes not yet applied on the EDT; componentResized events seen meanwhile still carry the old size. */
		volatile int resizesPending;
		/** Memory write serial when the window was last shown: direct screen writes older than that stay under it. */
		volatile long shownSerial;
		/** {@link #menuVersion} of the menu bar this window carries (EDT only): a hidden window keeps a stale bar until it is shown. */
		int menuBarVersion=-1;
		/** The menu bar laid over the classic menu-bar band of a backdrop (EDT only; see {@link #overlaysMenuBar}), else null. */
		JMenuBar overlayBar;
		/** Rows of the title bar at the top of a frame the host draws in the buffer (a Kaleidoscope scheme's document window),
		 *  else 0: where the menu bar sits inside the window, it goes under them ({@link MacAppHost#setTitleBand}). */
		volatile int titleBand;
		/** The title bar shown above the in-window menu bar (EDT only), else null; a press in it is followed with the shift. */
		TitleStrip titleStrip;volatile boolean titleStripPressed;
		/** Host-side copy of the window pixels as packed RGB (EDT only): rows are re-converted from VM memory only when
		 *  their 256-byte chunks (or the device PixMap tiles under them) were written after {@link #displaySerial}. */
		BufferedImage display;int[] displayPixels;long displaySerial;int[] displayClut;boolean displayComposite;
		/** {@link QuickDraw68k#gammaVersion()} the display copy was converted with: a new gamma table (a game's fade) reconverts everything. */
		int displayGamma;
		/** Colour table buffers refilled every refresh (no allocation per tick). */
		int[] paletteScratch,devicePaletteScratch;
		/** Write serial of the window's own drawing per 32x32 tile of the current band (scratch for the compositor). */
		long[] bandSerial;
		/** Scratch of {@link #adoptDeviceWrites(HostWindow)} (emulation thread): band serials and colour tables. */
		long[] adoptBand;int[] adoptPaletteScratch,adoptWindowPaletteScratch;long adoptedSerial;
		/** Write serial up to which the window's own drawing was copied into the device PixMap ({@link #publishWindowDrawing}). */
		long publishedSerial;int[] publishPaletteScratch,publishDevicePaletteScratch;
		/** An activate event has been posted for this window and no deactivate since. */
		boolean activePosted;
		/** Smooth zoom (SmoothZoom, EDT only): the buffer's plane, shown instead of {@link #display}; its row versions as last shown;
		 *  the 1x pixels before gamma (what screen writes composited over the window are scaled up from); the image shown - the
		 *  plane itself, or a copy when composited screen writes, a gamma table or a grey display change what is shown. */
		SmoothZoom.Plane smoothPlane;long[] smoothShown;int[] smoothBase;BufferedImage smoothImage;int[] smoothPixels;
		HostWindow(int record){this.record=record;}
		Window window(){return window;}
	}

	/** Direct screen writes: once a program has called SwapMMUMode, windows show the main device PixMap that covers them. */
	private volatile QuickDraw68k screenSource;
	void directScreenAccess(QuickDraw68k quickDraw){screenSource=quickDraw;}
	/**
	 * A direct-screen "transaction": QuickTime's decompressors bracket their blit into screen memory with SwapMMUMode
	 * (to 32-bit mode and back). While one is open the periodic adoption and the display's device composite hold off,
	 * so a frame that takes many polls to write reaches the window whole instead of in bands (tearing at 512x342).
	 * Programs that switch to 32-bit mode once and keep writing (games) must not freeze: a transaction expires after
	 * {@link #TRANSACTION_LIMIT_NANOS} and ends at the next event call.
	 */
	private volatile boolean directTransaction;private volatile long directTransactionStart;
	private static final long TRANSACTION_LIMIT_NANOS=250_000_000L;
	void swapMMUMode(){if(directTransaction)endDirectTransaction();else{directTransaction=true;directTransactionStart=System.nanoTime();}}
	void endDirectTransaction(){if(!directTransaction)return;directTransaction=false;if(!headless&&memory.deviceWritten()&&!java.awt.EventQueue.isDispatchThread())adoptDeviceWrites();}
	boolean directTransactionActive(){if(!directTransaction)return false;if(System.nanoTime()-directTransactionStart>TRANSACTION_LIMIT_NANOS){directTransaction=false;return false;}return true;}
	/** The device PixMap to composite over windows: after SwapMMUMode, or once anything was written into it (a window's
	 *  PixMap record names it as the window's pixels, so programs write there without SwapMMUMode - WonderCrackers). */
	private QuickDraw68k screenSource(){QuickDraw68k screen=screenSource;return screen!=null?screen:memory.deviceWritten()?quickDraw:null;}
	/** The VM's QuickDraw, for the screen colour table indexed window buffers are shown through. */
	volatile QuickDraw68k quickDraw;
	/** The main screen's colour table as packed RGB (a grey ramp when there is none yet). */
	int[] devicePalette(int depth){return devicePalette(depth,null);}
	/** The main screen's colour table as packed RGB (a grey ramp when there is none yet); {@code reuse} is filled when it fits. */
	int[] devicePalette(int depth,int[] reuse){
		QuickDraw68k qd=quickDraw;int[] clut=null;try{if(qd!=null)clut=qd.mainDevicePalette(reuse);}catch(M68kCpuException e){clut=null;}
		if(clut==null){clut=reuse!=null&&reuse.length==1<<depth?reuse:new int[1<<depth];for(int i=0;i<clut.length;i++){int v=255-i*255/(clut.length-1);clut[i]=v<<16|v<<8|v;}}return clut;
	}
	final class Canvas extends JComponent {
		private static final long serialVersionUID=1L;
		final HostWindow owner;
		Canvas(HostWindow owner){this.owner=owner;setOpaque(true);setFocusable(true);setBackground(Color.WHITE);enableInputMethods(true);}
		/** An active input-method client: composed text is shown by {@link #showComposition}, committed text typed into the program. */
		@Override public InputMethodRequests getInputMethodRequests(){return inputMethodRequests;}
		@Override protected void paintComponent(Graphics g){paintWindow(g);}
		/** The title rows shown above the menu bar ({@link TitleStrip}) are repainted with the canvas rows they show. */
		@Override public void repaint(long tm,int x,int y,int width,int height){
			super.repaint(tm,x,y,width,height);TitleStrip strip=owner==null?null:owner.titleStrip;
			if(strip!=null&&strip.isVisible()&&y<strip.getHeight())strip.repaint(tm,x,y,width,Math.min(height,strip.getHeight()-y));
		}
		void paintWindow(Graphics g){
			BufferedImage image=owner.image;int depth=owner.imageDepth;
			if(image==null&&depth==32){g.setColor(Color.WHITE);g.fillRect(0,0,getWidth(),getHeight());return;}
			// The display copy is kept up to date by the refresh timer (refreshDisplay); an expose only blits it.
			if(owner.display==null)refreshDisplay(owner);
			BufferedImage display=owner.display;
			if(display==null){g.setColor(Color.WHITE);g.fillRect(0,0,getWidth(),getHeight());return;}
			BufferedImage smooth=owner.smoothImage;SmoothZoom.Plane plane=owner.smoothPlane;
			if(smooth!=null&&plane!=null&&plane.width==display.getWidth()&&plane.height==display.getHeight()){ // the smooth zoom's hi-res plane
				Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,smoothInterpolation(g2,plane.s));g2.drawImage(smooth,0,0,hostLen(display.getWidth()),hostLen(display.getHeight()),null);
				if(paintDumpDir!=null)dumpImage(smooth,String.format("paint-%06X-smooth",owner.record));}
			else if(scPct==100)g.drawImage(display,0,0,null);
			else{Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,interpolation(g2));g2.drawImage(display,0,0,hostLen(display.getWidth()),hostLen(display.getHeight()),null);}
			if(paintDumpDir!=null){try{java.io.File dump=new java.io.File(paintDumpDir,String.format("paint-%06X.png",owner.record)),partial=new java.io.File(paintDumpDir,String.format("paint-%06X.part",owner.record));javax.imageio.ImageIO.write(display,"png",partial);java.nio.file.Files.move(partial.toPath(),dump.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);System.err.println("[host] paint dump $"+Integer.toHexString(owner.record)+" "+display.getWidth()+"x"+display.getHeight()+" depth "+depth+" base $"+Integer.toHexString(owner.imageBase)+" canvas "+getWidth()+"x"+getHeight());}catch(Exception e){}}
			int dw=hostLen(display.getWidth()),dh=hostLen(display.getHeight());
			if(getWidth()>dw||getHeight()>dh){g.setColor(Color.WHITE);g.fillRect(dw,0,getWidth()-dw,getHeight());g.fillRect(0,dh,getWidth(),getHeight()-dh);}
			Image overlay=overlayFor(owner);if(overlay!=null){if(scPct==100)g.drawImage(overlay,0,0,null);else g.drawImage(overlay,0,0,hostLen(overlay.getWidth(null)),hostLen(overlay.getHeight(null)),null);}
		}
		@Override public Dimension getPreferredSize(){
			Dimension d=new Dimension(hostLen(owner.width),hostLen(owner.height)),cut=owner.screenCut;
			if(cut!=null){d.width=Math.min(d.width,cut.width);d.height=Math.min(d.height,cut.height);}
			return d;
		}
	}
	/**
	 * The title bar of a frame drawn in the buffer, shown above the in-window menu bar (Windows, Linux): a window in a Kaleidoscope
	 * scheme's frame had its menus over the title bar, a native frame has them under it. The strip shows the canvas's first rows
	 * (the canvas keeps its place, those rows under the strip and the bar); the pointer over it is the program's one bar height
	 * further down, where the rows it shows are ({@link #pastTitleBand}).
	 */
	final class TitleStrip extends JComponent {
		private static final long serialVersionUID=1L;
		final HostWindow owner;
		TitleStrip(HostWindow owner){this.owner=owner;setOpaque(true);setFocusable(false);
			MouseAdapter forward=new MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){owner.titleStripPressed=true;forward(e);}
				@Override public void mouseReleased(MouseEvent e){forward(e);owner.titleStripPressed=false;}
				@Override public void mouseMoved(MouseEvent e){forward(e);}
				@Override public void mouseDragged(MouseEvent e){forward(e);}
			};
			addMouseListener(forward);addMouseMotionListener(forward);
		}
		/** The event as the canvas's, at the canvas row shown under the pointer (on the screen: one bar height further down). */
		private void forward(MouseEvent e){Canvas canvas=owner.canvas;if(canvas==null)return;
			canvas.dispatchEvent(new MouseEvent(canvas,e.getID(),e.getWhen(),e.getModifiersEx(),e.getX(),e.getY(),e.getXOnScreen(),e.getYOnScreen()+canvas.getY()-getY(),e.getClickCount(),e.isPopupTrigger(),e.getButton()));}
		@Override protected void paintComponent(Graphics g){Canvas canvas=owner.canvas;if(canvas!=null)canvas.paintWindow(g);}
	}
	/** The root pane's own layout, then the menu bar moved under the title rows of a frame drawn in the window ({@link TitleStrip}). */
	private final class TitleBandLayout implements java.awt.LayoutManager2 {
		final HostWindow hw;final java.awt.LayoutManager2 base;
		TitleBandLayout(HostWindow hw,java.awt.LayoutManager2 base){this.hw=hw;this.base=base;}
		@Override public void layoutContainer(java.awt.Container parent){base.layoutContainer(parent);placeTitleBand(hw);}
		@Override public void addLayoutComponent(String name,java.awt.Component comp){base.addLayoutComponent(name,comp);}
		@Override public void addLayoutComponent(java.awt.Component comp,Object constraints){base.addLayoutComponent(comp,constraints);}
		@Override public void removeLayoutComponent(java.awt.Component comp){base.removeLayoutComponent(comp);}
		@Override public Dimension preferredLayoutSize(java.awt.Container parent){return base.preferredLayoutSize(parent);}
		@Override public Dimension minimumLayoutSize(java.awt.Container parent){return base.minimumLayoutSize(parent);}
		@Override public Dimension maximumLayoutSize(java.awt.Container target){return base.maximumLayoutSize(target);}
		@Override public float getLayoutAlignmentX(java.awt.Container target){return base.getLayoutAlignmentX(target);}
		@Override public float getLayoutAlignmentY(java.awt.Container target){return base.getLayoutAlignmentY(target);}
		@Override public void invalidateLayout(java.awt.Container target){base.invalidateLayout(target);}
	}
	/** Some window shows its title rows above its menu bar ({@link TitleStrip}). */
	private volatile boolean anyTitleBand;
	/**
	 * The rows of the title bar at the top of a window whose frame the host draws in its buffer (a Kaleidoscope scheme's document
	 * window; 0: none). On platforms without a screen menu bar they are shown above the in-window menu bar, as a native title bar is.
	 */
	void setTitleBand(final int record,final int rows){
		final HostWindow hw=window(record);if(hw==null||headless||GMenu.isMacOSX()||hw.titleBand==rows)return;
		hw.titleBand=rows;
		runOnEdt(new Runnable(){@Override public void run(){if(!(hw.window instanceof javax.swing.RootPaneContainer))return;((javax.swing.RootPaneContainer)hw.window).getRootPane().invalidate();hw.window.validate();if(needsShape(hw))applyShape(hw);}},false);
	}
	/** Lays out the title strip and the bar under it after the root pane's layout (EDT). */
	private void placeTitleBand(HostWindow hw){
		if(!(hw.window instanceof javax.swing.RootPaneContainer))return;
		javax.swing.JRootPane root=((javax.swing.RootPaneContainer)hw.window).getRootPane();JMenuBar bar=root.getJMenuBar();
		Canvas canvas=hw.canvas;int band=canvas==null?0:Math.min(hostLen(hw.titleBand),canvas.getHeight());
		if(band<=0||bar==null||!bar.isVisible()||bar.getParent()!=root.getLayeredPane()){if(hw.titleStrip!=null&&hw.titleStrip.isVisible())hw.titleStrip.setVisible(false);return;}
		JLayeredPane layers=root.getLayeredPane();
		if(hw.titleStrip==null)hw.titleStrip=new TitleStrip(hw);
		if(hw.titleStrip.getParent()!=layers)layers.add(hw.titleStrip,JLayeredPane.PALETTE_LAYER);
		if(layers.getLayer(bar)!=JLayeredPane.PALETTE_LAYER)layers.setLayer(bar,JLayeredPane.PALETTE_LAYER); // over the canvas's title rows
		Rectangle b=bar.getBounds();bar.setLocation(b.x,b.y+band);
		hw.titleStrip.setBounds(b.x,b.y,canvas.getWidth(),band);
		if(!hw.titleStrip.isVisible())hw.titleStrip.setVisible(true);
		anyTitleBand=true;
	}
	/** The title strip showing in the window, else null (EDT). */
	private static TitleStrip shownTitleStrip(HostWindow hw){TitleStrip s=hw.titleStrip;return s!=null&&s.isVisible()&&s.getParent()!=null?s:null;}
	/** A host point over a title strip (or anywhere while the button pressed there is held) moved down onto the canvas rows that
	 *  strip shows: where the program sees them. */
	private Point pastTitleBand(Point host){
		for(HostWindow hw:windowsFrontToBack()){TitleStrip s=hw.titleStrip;Canvas canvas=hw.canvas;java.awt.Window win=hw.window;
			if(s==null||canvas==null||win==null||!s.isShowing())continue;
			try{Point at=s.getLocationOnScreen();int shift=canvas.getY()-s.getY();
				if(hw.titleStripPressed&&mouseDown||host.x>=at.x&&host.y>=at.y&&host.x<at.x+s.getWidth()&&host.y<at.y+s.getHeight())return new Point(host.x,host.y+shift);}catch(Exception ignored){}}
		return host;
	}

	/**
	 * Brings the window's display copy up to date with VM memory and returns the changed row range {first, last+1},
	 * or null when nothing changed. EDT only. Only rows whose chunks were written since the previous refresh are
	 * converted (straight into the display's DataBufferInt); a colour table change or a new display converts everything.
	 */
	int[] refreshDisplay(HostWindow hw){
		if(!PERF)return refreshSmooth(hw,refreshDisplay0(hw));long t=System.nanoTime();int[] r=refreshSmooth(hw,refreshDisplay0(hw));long ms=(System.nanoTime()-t)/1000000;if(ms>=5)System.err.println("[perf] refresh $"+Integer.toHexString(hw.record)+" "+ms+" ms rows="+(r==null?"none":r[0]+".."+r[1]));return r;}
	int[] refreshDisplay0(HostWindow hw){
		int w=hw.imageWidth,h=hw.imageHeight,depth=hw.imageDepth,base=hw.imageBase,rowBytes=hw.imageRowBytes;
		if(w<=0||h<=0)return null;
		byte[] raw=memory.raw();
		if(base<=0||base+(long)rowBytes*h>raw.length)return null;
		boolean full=false;
		SmoothZoom smooth=smoothZoom();
		if(smooth!=null&&smooth.active()){if(hw.smoothBase==null||hw.smoothBase.length!=w*h){hw.smoothBase=new int[w*h];full=true;}}else hw.smoothBase=null;
		if(hw.display==null||hw.display.getWidth()!=w||hw.display.getHeight()!=h){
			hw.display=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
			hw.displayPixels=((java.awt.image.DataBufferInt)hw.display.getRaster().getDataBuffer()).getData();full=true;
		}
		int[] clut=depth<=8?devicePalette(depth,hw.paletteScratch):null;
		if(!java.util.Arrays.equals(clut,hw.displayClut)){hw.displayClut=clut==null?null:clut.clone();full=true;}
		if(clut!=null)hw.paletteScratch=clut;
		QuickDraw68k qd=quickDraw;int[] gamma=qd==null?null:qd.gammaLookup();int gammaVersion=qd==null?0:qd.gammaVersion();boolean gray=qd!=null&&qd.grayDisplay();
		if(gammaVersion!=hw.displayGamma){hw.displayGamma=gammaVersion;full=true;}
		QuickDraw68k screen=screenSource();int[] frame=null;int[] deviceClut=null;
		if(screen!=null){try{frame=screen.mainDeviceFrame();deviceClut=screen.mainDevicePalette(hw.devicePaletteScratch);if(deviceClut!=null)hw.devicePaletteScratch=deviceClut;}catch(M68kCpuException e){frame=null;}}
		boolean composite=frame!=null&&!directTransactionActive(); // mid-transaction the buffer (last whole frame) is shown, not the half-written screen
		if(composite!=hw.displayComposite){hw.displayComposite=composite;full=true;}
		long since=hw.displaySerial,now=memory.writeSerial();
		if(!full&&memory.chunkSerial(base,rowBytes*h)<=since&&!(composite&&deviceChangedSince(frame,hw,since)))return null;
		int[] pixels=hw.displayPixels;int first=-1,last=-1;
		int tileColumns=(w+31)>>>5;if(composite&&(hw.bandSerial==null||hw.bandSerial.length!=tileColumns))hw.bandSerial=new long[tileColumns];
		int bandRow=0;boolean bandDirty=false,bandComputed=false;
		for(int y=0;y<h;y++){
			int rowStart=base+y*rowBytes;
			if(composite&&(y&31)==0){bandRow=y;bandDirty=deviceBandChangedSince(frame,hw,y,since);bandComputed=false;}
			boolean dirty=full||bandDirty||memory.chunkSerial(rowStart,rowBytes)>since;
			if(!dirty)continue;
			readPixels(raw,rowStart,depth,clut,w,pixels,y*w);
			if(composite){if(!bandComputed){windowBandSerials(hw,bandRow,h);bandComputed=true;}compositeRow(frame,deviceClut,hw,y,pixels);}
			if(hw.smoothBase!=null)System.arraycopy(pixels,y*w,hw.smoothBase,y*w,w);
			if(gray)applyGray(pixels,y*w,w);if(gamma!=null)applyGamma(gamma,pixels,y*w,w);
			if(first<0)first=y;last=y;
		}
		hw.displaySerial=now;
		return first<0?null:new int[]{first,last+1};
	}

	// ------------------------------------------------------------------ smooth zoom (SmoothZoom)

	/** This program's planes (null before its QuickDraw is known). */
	SmoothZoom smoothZoom(){QuickDraw68k qd=quickDraw;return qd==null?null:qd.smooth;}
	/**
	 * Brings the window's smooth zoom image up to date after {@link #refreshDisplay0} (EDT): the plane of its buffer is synced with
	 * the buffer (what changed is scaled up, hi-res drawing stays), and shown as it is - or, when screen writes are composited over
	 * the window or a gamma table or grey display applies, copied with those (composited pixels scaled up from the 1x display).
	 * Returns the rows to repaint ({@code changed}, the 1x refresh's, merged in), or null.
	 */
	private int[] refreshSmooth(HostWindow hw,int[] changed){
		SmoothZoom smooth=smoothZoom();int w=hw.imageWidth,h=hw.imageHeight,depth=hw.imageDepth;
		SmoothZoom.Plane p=smooth==null||!smooth.active()||hw.display==null||hw.smoothBase==null?null:smooth.plane(hw.imageBase,hw.imageRowBytes,w,h,depth,true);
		if(p==null){if(hw.smoothPlane==null)return changed;hw.smoothPlane=null;hw.smoothImage=null;hw.smoothPixels=null;hw.smoothShown=null;return new int[]{0,h};}
		p.window=true;
		if(p!=hw.smoothPlane){hw.smoothPlane=p;hw.smoothShown=new long[h];java.util.Arrays.fill(hw.smoothShown,-1);hw.smoothImage=null;hw.smoothPixels=null;}
		p.sync(0,0,w,h,depth<=8?hw.displayClut:null,SmoothZoom.HOST);
		QuickDraw68k qd=quickDraw;int[] gamma=qd==null?null:qd.gammaLookup();boolean gray=qd!=null&&qd.grayDisplay();
		int first=changed==null?-1:changed[0],last=changed==null?-1:changed[1]-1,s=p.s,stride=w*s;
		if(!hw.displayComposite&&gamma==null&&!gray){ // the plane is what is shown
			if(hw.smoothImage!=p.image()){hw.smoothImage=p.image();hw.smoothPixels=null;first=0;last=h-1;}
			for(int y=0;y<h;y++)if(p.rowVersion[y]!=hw.smoothShown[y]){hw.smoothShown[y]=p.rowVersion[y];if(first<0||y<first)first=y;if(y>last)last=y;}
			return first<0?null:new int[]{first,last+1};
		}
		if(hw.smoothPixels==null){hw.smoothImage=new BufferedImage(stride,h*s,BufferedImage.TYPE_INT_RGB);hw.smoothPixels=((java.awt.image.DataBufferInt)hw.smoothImage.getRaster().getDataBuffer()).getData();java.util.Arrays.fill(hw.smoothShown,-1);}
		int[] out=hw.smoothPixels,base=hw.smoothBase,snap=p.snap,hi=p.hi;
		for(int y=0;y<h;y++){
			boolean redo=p.rowVersion[y]!=hw.smoothShown[y]||changed!=null&&y>=changed[0]&&y<changed[1];if(!redo)continue;
			hw.smoothShown[y]=p.rowVersion[y];int o=y*w;
			for(int x=0;x<w;){
				int start=x;
				if(base[o+x]==snap[o+x]){while(x<w&&base[o+x]==snap[o+x])x++;for(int j=0;j<s;j++){int r=(y*s+j)*stride;System.arraycopy(hi,r+start*s,out,r+start*s,(x-start)*s);}}
				else{while(x<w&&base[o+x]!=snap[o+x])x++;SmoothScaler.scaleRun(p.algorithm,base,w,h,y,start,x,s,out,stride,false);}
			}
			for(int j=0;j<s;j++){int r=(y*s+j)*stride;if(gray)applyGray(out,r,stride);if(gamma!=null)applyGamma(gamma,out,r,stride);}
			if(first<0||y<first)first=y;if(y>last)last=y;
		}
		return first<0?null:new int[]{first,last+1};
	}
	/** Nearest neighbour when the plane's pixels are the display's own (its scale is the magnification on the device), else bilinear. */
	private Object smoothInterpolation(Graphics2D g,int s){double physical=scale()*g.getTransform().getScaleX();
		return Math.abs(physical-s)<1e-6?java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR:java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR;}
	/** The paint dump (-Dhz68k... paintDumpDir) of another image: written whole, then moved into place. */
	private static void dumpImage(BufferedImage image,String name){
		try{java.io.File dump=new java.io.File(paintDumpDir,name+".png"),partial=new java.io.File(paintDumpDir,name+".part");javax.imageio.ImageIO.write(image,"png",partial);
			java.nio.file.Files.move(partial.toPath(),dump.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){}
	}
	/** The smooth zoom choice of the preference (PCARD "smoothZoom68k"): SmoothZoom.OFF, BRESENHAM, BICUBIC, SCALE2X or AUTO. */
	static volatile int smoothZoom=SmoothZoom.OFF;
	/** The smooth zoom algorithm of a program: -Dhz68k.smooth, else its own launcher setting, else the preference. */
	static int smoothAlgorithm(MacApp68k app){
		if(SmoothZoom.OVERRIDE!=null)return SmoothZoom.OVERRIDE;
		Integer own=app==null?null:app.smoothZoomOverride;return own!=null&&SmoothZoom.valid(own)?own:smoothZoom;
	}
	/** The largest device scale of the displays (2 on a Retina display): the planes' scale counts the display's own pixels. */
	static double deviceScale(){
		try{double best=1;for(java.awt.GraphicsDevice d:java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())best=Math.max(best,d.getDefaultConfiguration().getDefaultTransform().getScaleX());return best;}
		catch(Exception e){return 1;}
	}
	/**
	 * Sets the program's planes up for its magnification and smooth zoom setting. {@code redraw}: a change while it runs - every
	 * window is drawn again by the program (update events), so its text goes into the new planes hi-res; until then they show its
	 * pixels scaled up.
	 */
	void configureSmooth(boolean redraw){
		QuickDraw68k qd=quickDraw;if(qd==null)return;
		int algorithm=external?SmoothZoom.OFF:smoothAlgorithm(owner);
		int s=algorithm==SmoothZoom.OFF?1:headless?SmoothZoom.SCALE_OVERRIDE:SmoothZoom.scaleFor(scPct,deviceScale());
		// a screen-sized plane per window, its copy for the display and an offscreen buffer or two: smaller planes rather than none on a large screen
		while(s>1&&(long)Math.max(1,screenWidth)*Math.max(1,screenHeight)*s*s*3>SmoothZoom.budget())s--;
		if(!qd.smooth.configure(s,algorithm))return;
		if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){
			java.util.List<HostWindow> all;synchronized(windows){all=new java.util.ArrayList<HostWindow>(windows.values());}
			for(HostWindow hw:all){hw.smoothPlane=null;hw.smoothImage=null;hw.smoothPixels=null;if(hw.canvas!=null)hw.canvas.repaint();}
			screenSmoothPlane=null;screenSmoothImage=null;if(screenCanvas!=null)screenCanvas.repaint();
		}},false);
		MacApp68k app=owner;final Toolbox68k tb=app==null?null:app.toolbox;
		if(redraw&&tb!=null)tb.laterAtEvent(new Runnable(){@Override public void run(){try{tb.windows.redrawAll();}catch(M68kCpuException e){}}});
	}
	/** The smooth zoom setting changed while the program runs (its own, or the preference it follows). */
	void resmooth(){runOnEdt(new Runnable(){@Override public void run(){configureSmooth(true);}},false);}

	/** Expands one row of a window buffer (xRGB, 16-bit or indexed through {@code clut}) to packed RGB at {@code row[offset]}. */
	static void readPixels(byte[] raw,int rowStart,int depth,int[] clut,int width,int[] row,int offset){
		if(depth==32){for(int x=0,o=rowStart,p=offset;x<width;x++,o+=4,p++)row[p]=(raw[o+1]&0xff)<<16|(raw[o+2]&0xff)<<8|(raw[o+3]&0xff);return;}
		if(depth==16){for(int x=0,o=rowStart,p=offset;x<width;x++,o+=2,p++){int v=(raw[o]&0xff)<<8|(raw[o+1]&0xff);row[p]=RGB555[v&0x7fff];}return;}
		int[] table=clut==null?new int[1<<depth]:clut;int mask=(1<<depth)-1;
		if(depth==8){for(int x=0,o=rowStart,p=offset;x<width;x++,o++,p++){int v=raw[o]&0xff;row[p]=v<table.length?table[v]:0;}return;}
		if(depth==4){int x=0,p=offset,o=rowStart;for(;x+1<width;x+=2,o++){int b=raw[o]&0xff;int hi=b>>>4,lo=b&15;row[p++]=hi<table.length?table[hi]:0;row[p++]=lo<table.length?table[lo]:0;}
			if(x<width){int v=(raw[o]&0xff)>>>4;row[p]=v<table.length?table[v]:0;}return;}
		for(int x=0;x<width;x++){int bit=x*depth,value=(raw[rowStart+(bit>>>3)]&0xff)>>>(8-depth-(bit&7))&mask;row[offset+x]=value<table.length?table[value]:0;}
	}
	static void readPixels(byte[] raw,int rowStart,int depth,int[] clut,int width,int[] row){readPixels(raw,rowStart,depth,clut,width,row,0);}
	/** Ramps {@code width} packed-RGB pixels at {@code row[offset]} through the video driver's gamma table (see {@link QuickDraw68k#gammaLookup()}). */
	static void applyGamma(int[] lut,int[] row,int offset,int width){for(int p=offset,end=offset+width;p<end;p++){int rgb=row[p];row[p]=lut[rgb>>>16&255]<<16|lut[256+(rgb>>>8&255)]<<8|lut[512+(rgb&255)];}}
	/** The video driver's luminance mapping (cscSetGray, {@link QuickDraw68k#grayDisplay()}): each pixel as the grey of its luminance (30/59/11). */
	static void applyGray(int[] row,int offset,int width){for(int p=offset,end=offset+width;p<end;p++){int rgb=row[p];int y=((rgb>>>16&255)*30+(rgb>>>8&255)*59+(rgb&255)*11+50)/100;row[p]=y<<16|y<<8|y;}}
	/** 16-bit (xRGB 1:5:5:5) pixel values expanded to packed RGB. */
	private static final int[] RGB555=new int[32768];
	static{for(int v=0;v<32768;v++)RGB555[v]=(v>>>10&31)*255/31<<16|(v>>>5&31)*255/31<<8|(v&31)*255/31;}

	/** Byte offset and length of the device PixMap bytes under window row {@code y}, or null when the row is off the device. */
	private int[] deviceSpan(int[] frame,HostWindow hw,int y){
		int base=frame[0],rowBytes=frame[1],depth=frame[2],left=frame[3],top=frame[4],right=frame[5],bottom=frame[6];
		int gy=hw.contentTop+y;if(gy<top||gy>=bottom)return null;
		int gx0=Math.max(hw.contentLeft,left),gx1=Math.min(hw.contentLeft+hw.imageWidth,right);if(gx0>=gx1)return null;
		int rowStart=base+(gy-top)*rowBytes,from=rowStart+((gx0-left)*depth>>>3),to=rowStart+(((gx1-left)*depth+7)>>>3);
		return new int[]{from,Math.max(1,to-from)};
	}
	/** True when any device byte under the window was written after {@code since} (sampled per 32-row band). */
	private boolean deviceChangedSince(int[] frame,HostWindow hw,long since){
		if(memory.deviceSerial()<=since)return false; // no device write at all since then
		for(int y=0;y<hw.imageHeight;y+=32)if(deviceBandChangedSince(frame,hw,y,since))return true;return false;
	}
	private boolean deviceBandChangedSince(int[] frame,HostWindow hw,int y,long since){
		if(memory.deviceSerial()<=since)return false;
		int top=frame[4],end=Math.min(hw.imageHeight,y+32);
		// device tiles are 32 rows tall: sampling the band's first device row and the tile boundary inside it covers every tile under the band
		for(int r=y;r<end;r++){int dy=hw.contentTop+r-top;if(dy<0||(r!=y&&(dy&31)!=0))continue;
			int[] span=deviceSpan(frame,hw,r);if(span!=null&&memory.maxTileSerial(span[0],span[1])>since)return true;}
		return false;
	}
	/** Write serial of the window's own pixels per 32-pixel-wide tile of the 32-row band starting at {@code bandRow}. */
	private void windowBandSerials(HostWindow hw,int bandRow,int h){
		long[] band=hw.bandSerial;int depth=hw.imageDepth,base=hw.imageBase,rowBytes=hw.imageRowBytes;
		java.util.Arrays.fill(band,0);
		for(int y=bandRow;y<Math.min(h,bandRow+32);y++){int rowStart=base+y*rowBytes;
			for(int t=0;t<band.length;t++){int px=t<<5;int from=depth<=8?rowStart+(px*depth>>>3):depth==16?rowStart+px*2:rowStart+px*4,length=depth<=8?(32*depth+7)>>>3:depth==16?64:128;
				long s=memory.chunkSerial(from,length);if(s>band[t])band[t]=s;}}
	}
	/** Replaces the pixels of window row {@code y} with the main device PixMap pixels at the same global position. */
	private void compositeRow(int[] frame,int[] clut,HostWindow hw,int y,int[] pixels){
		int base=frame[0],rowBytes=frame[1],depth=frame[2],left=frame[3],top=frame[4],right=frame[5],bottom=frame[6];
		int gy=hw.contentTop+y;if(gy<top||gy>=bottom)return;int rowBase=base+(gy-top)*rowBytes;
		byte[] raw=memory.raw();long[] bits=memory.deviceWriteBits();int deviceStart=memory.deviceStart();if(bits==null)return;
		long shown=hw.shownSerial;long[] band=hw.bandSerial;int w=hw.imageWidth,p=y*w;
		for(int x=0;x<w;x++){int gx=hw.contentLeft+x;if(gx<left||gx>=right)continue;int px=gx-left,rgb;
			// Only pixels the program wrote after this window was shown, and after the window's own drawing there
			// last changed, replace that drawing (a picture drawn over a directly cleared screen stays on top).
			long since=shown;int t=x>>>5;if(t<band.length&&band[t]>since)since=band[t];
			if((x&31)==0&&x+32<=w&&gx+32<=right){int bytes=depth<=8?(32*depth+7)>>>3:depth==16?64:128,o=depth<=8?rowBase+(px*depth>>>3):depth==16?rowBase+px*2:rowBase+px*4;if(memory.maxTileSerial(o,bytes)<=since){x+=31;continue;}} // the next 32 pixels untouched since then
			if(depth<=8){int bit=px*depth,o=rowBase+(bit>>>3);if(o<0||o>=raw.length||!written(bits,deviceStart,o,since))continue;int value=(raw[o]&0xff)>>>(8-depth-(bit&7))&((1<<depth)-1);rgb=clut==null||value>=clut.length?0:clut[value];}
			else if(depth==16){int o=rowBase+px*2;if(o+1>=raw.length||!written(bits,deviceStart,o,since))continue;int v=(raw[o]&0xff)<<8|(raw[o+1]&0xff);rgb=RGB555[v&0x7fff];}
			else{int o=rowBase+px*4;if(o+3>=raw.length||!written(bits,deviceStart,o,since))continue;rgb=(raw[o+1]&0xff)<<16|(raw[o+2]&0xff)<<8|(raw[o+3]&0xff);}
			pixels[p+x]=rgb;}
	}
	private boolean written(long[] bits,int deviceStart,int address,long since){
		int o=address-deviceStart;if(o<0||(o>>>6)>=bits.length||(bits[o>>>6]&(1L<<(o&63)))==0)return false;
		return memory.tileSerialAt(address)>since;
	}

	/**
	 * Direct screen writes under a window become the window's own pixels. On a real Mac a window's pixels ARE the
	 * screen memory: HyperCard's visual effects write the new card straight into the device PixMap and then draw the
	 * card's fields into the window port on top of it. Hosted windows keep private buffers, and compositing alone
	 * showed the buffer (the previous card) wherever the program drew after the effect. So every device tile written
	 * after the window's own drawing there is copied into the buffer, ordered with the program's writes: called on
	 * the emulation thread before QuickDraw reads or draws a window's buffer and from the scheduler poll.
	 * Returns true when anything was copied.
	 */
	boolean adoptDeviceWrites(int record){HostWindow hw=window(record);return hw!=null&&adoptDeviceWrites(hw);}
	boolean adoptDeviceWrites(){
		if(headless||!memory.deviceWritten()||directTransactionActive())return false;boolean any=false;
		for(HostWindow hw:windowsFrontToBack())if(adoptDeviceWrites(hw))any=true;return any;
	}
	private long lastRefresh;

	// ------------------------------------------------------------ display pacing

	/**
	 * Frames reach the host windows in two ways. The refresh timer ticks at the display's refresh rate (60 Hz, 120 Hz on a
	 * ProMotion display; -Dhz68k.fps=N to choose) and shows whatever changed since the previous tick; a tick that finds
	 * nothing changed costs a scan of the write serials. And the program tells when a frame is done ({@link #frameReady}):
	 * a CopyBits onto a window or the screen - the blit of a double-buffered game, QuickDraw 3D's draw context swapping
	 * its back buffer - or an event call, the end of a pass of the program's loop. The frame is then shown at once instead of
	 * at the next tick, so a game running at 30 or 60 frames a second is shown frame by frame, with no frame repeated or
	 * dropped by the beat between its rate and a fixed timer's (the old 33 ms timer showed at most 30 and juddered).
	 * A tick that falls while a blit onto the screen is under way waits for it: the blit's own frameReady shows it whole.
	 */
	static final int DISPLAY_RATE=displayRate();
	static final long FRAME_NANOS=1_000_000_000L/DISPLAY_RATE;
	private static int displayRate(){
		Integer chosen=Integer.getInteger("hz68k.fps");if(chosen!=null&&chosen>0)return Math.max(10,Math.min(240,chosen));
		int rate=60;
		try{if(!java.awt.GraphicsEnvironment.isHeadless()){int r=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode().getRefreshRate();
			if(r!=java.awt.DisplayMode.REFRESH_RATE_UNKNOWN&&r>0)rate=r;}}catch(Throwable t){}
		return Math.max(30,Math.min(144,rate));
	}
	/** When a present last showed something (EDT), the write serial it saw, and whether a present from frameReady is queued. */
	private volatile long lastPresentNanos,lastPresentSerial=-1;
	private final java.util.concurrent.atomic.AtomicBoolean presentQueued=new java.util.concurrent.atomic.AtomicBoolean();
	/** Start of the blit onto a window or the screen under way (0: none); set on the emulation thread by QuickDraw's CopyBits. */
	private volatile long blitStart;
	private static final long BLIT_WAIT_NANOS=100_000_000L;
	void blitBegin(){blitStart=System.nanoTime();}
	void blitEnd(){blitStart=0;frameReady();}
	/** An event call (WaitNextEvent, GetNextEvent): no blit spans it, and a program's pass of drawing is over. */
	void eventCall(){endDirectTransaction();frameReady();}
	/**
	 * A mouse poll (Button, GetMouse, StillDown): a game that draws straight into screen memory (Dark Forces) reads its input
	 * once a pass of its loop, so what was written before the poll is a whole frame. It is shown now, and while the program keeps
	 * polling the refresh ticks leave screen writes alone - a tick in the middle of the render would show half a new frame.
	 */
	private volatile long lastPollNanos;
	void mousePoll(){lastPollNanos=System.nanoTime();frameReady();}
	private static final long POLL_FRAME_NANOS=100_000_000L;
	private static final java.util.concurrent.ScheduledExecutorService PRESENT_DELAY=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"68K display pacing");t.setDaemon(true);return t;});
	/**
	 * The program finished drawing a frame (emulation thread): shows it now, or half a display frame after the previous
	 * present when that was more recent (a program blitting many sprites a frame straight onto its window is not repainted
	 * for each). Nothing is queued when nothing was written since the last present.
	 */
	void frameReady(){
		if(headless||disposed||memory.writeSerial()==lastPresentSerial)return;
		if(!presentQueued.compareAndSet(false,true))return;
		Runnable r=new Runnable(){@Override public void run(){presentQueued.set(false);present(true);}};
		long wait=lastPresentNanos+FRAME_NANOS/2-System.nanoTime();
		if(wait<=0)java.awt.EventQueue.invokeLater(r);
		else PRESENT_DELAY.schedule(new Runnable(){@Override public void run(){java.awt.EventQueue.invokeLater(r);}},wait,java.util.concurrent.TimeUnit.NANOSECONDS);
	}
	/** -Dhz68k.fpsstats=true: prints every 2 s how many presents showed a change (the frame rate seen), and how many came from frames/ticks. */
	private static final boolean FPS_STATS=Boolean.getBoolean("hz68k.fpsstats");
	private long statsStart;private int statsShown,statsFrames,statsTicks;private volatile int statsUnsettled;private long statsMaxGap,statsLastShown;
	private void countPresent(boolean frame,boolean shown,long now){
		if(statsStart==0)statsStart=now;if(frame)statsFrames++;else statsTicks++;
		if(shown){statsShown++;if(statsLastShown!=0)statsMaxGap=Math.max(statsMaxGap,now-statsLastShown);statsLastShown=now;}
		if(now-statsStart>=2_000_000_000L){double sec=(now-statsStart)/1e9;
			System.err.printf("[host] %s: display %.1f fps shown (%d frame presents, %d ticks at %d Hz, %d shown while still being written, longest gap %d ms)%n",applicationName,statsShown/sec,statsFrames,statsTicks,DISPLAY_RATE,statsUnsettled,statsMaxGap/1_000_000);
			statsStart=now;statsShown=statsFrames=statsTicks=statsUnsettled=0;statsMaxGap=0;}
	}
	/**
	 * A refresh tick (pacing thread). A program copying its frame into screen memory with its own loop (Marathon) says nothing
	 * when the copy is done, and a tick in the middle of it would show half a new frame over half the old one: while tracked
	 * writes are still coming in the tick waits for a pause of {@link #SETTLE_NANOS} in them, up to three quarters of a frame
	 * (a program that never stops writing is shown then).
	 */
	private void tick(){
		if(disposed)return;
		long serial=memory.writeSerial();
		if(serial==lastPresentSerial){postTick();return;} // nothing written: the tick still follows colour table and gamma changes
		if(System.nanoTime()-lastPollNanos<POLL_FRAME_NANOS)return; // the program marks its frames with its polls: shown at the next one (the ticks resume when it stops polling)
		settleStart=System.nanoTime();settleSerial=serial;
		PRESENT_DELAY.schedule(writesSettled,SETTLE_NANOS,java.util.concurrent.TimeUnit.NANOSECONDS);
	}
	private static final long SETTLE_NANOS=1_500_000L;
	private long settleStart,settleSerial;
	private final Runnable writesSettled=new Runnable(){@Override public void run(){
		if(disposed)return;long serial=memory.writeSerial();
		if(serial==settleSerial||System.nanoTime()-settleStart>=FRAME_NANOS*3/4){if(FPS_STATS&&serial!=settleSerial)statsUnsettled++;postTick();return;}
		settleSerial=serial;PRESENT_DELAY.schedule(this,SETTLE_NANOS,java.util.concurrent.TimeUnit.NANOSECONDS);}};
	private void postTick(){if(tickQueued.compareAndSet(false,true))java.awt.EventQueue.invokeLater(new Runnable(){@Override public void run(){tickQueued.set(false);present(false);}});}
	/** Whether one of the program's Mac windows is showing on the screen (not the parked off-screen ones some programs keep). */
	private boolean macWindowOnScreen(){
		for(HostWindow hw:windowsFrontToBack())if(hw.visible&&hw.window!=null&&hw.window.isVisible()&&hw.window.getX()>-4000)return true;
		return false;
	}
	/** Shows what changed (EDT): from the refresh timer, or from {@link #frameReady} ({@code frame}). */
	private void present(boolean frame){
		if(disposed)return;
		long start=blitStart;if(!frame&&start!=0&&System.nanoTime()-start<BLIT_WAIT_NANOS)return; // the blit's end shows it
		long now=System.nanoTime();
		if(Toolbox68k.SLOW_TRAP_NANOS>0&&!frame){if(lastRefresh!=0&&now-lastRefresh>=Toolbox68k.SLOW_TRAP_NANOS+FRAME_NANOS)System.err.println("[host] refresh tick late by "+(now-lastRefresh-FRAME_NANOS)/1_000_000+" ms (EDT busy)");lastRefresh=now;}
		long serial=memory.writeSerial();
		boolean shown=repaintAll();if(shown)lastPresentNanos=now;
		lastPresentSerial=serial;
		if(FRAME_DUMP!=null&&shown)dumpPresented(frame);
		if(FPS_STATS)countPresent(frame,shown,now);
		updateOverlayBars();
		if(Toolbox68k.SLOW_TRAP_NANOS>0){long took=System.nanoTime()-now;if(took>=Toolbox68k.SLOW_TRAP_NANOS)System.err.println("[host] refresh took "+took/1_000_000+" ms");}
	}
	/** Debug aid (-Dhz68k.framedump=dir): while the file dir/go exists, every present that showed something saves the screen as dir/NNNNN-tick|frame.png (at most 200). */
	private static final java.io.File FRAME_DUMP=System.getProperty("hz68k.framedump")==null?null:new java.io.File(System.getProperty("hz68k.framedump"));
	private int framesDumped;
	private void dumpPresented(boolean frame){
		BufferedImage shot=screenFrame!=null&&screenFrame.isShowing()?screenImage:null;
		if(shot==null||framesDumped>=200||!new java.io.File(FRAME_DUMP,"go").exists())return;
		try{BufferedImage copy=new BufferedImage(shot.getWidth(),shot.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics g=copy.getGraphics();g.drawImage(shot,0,0,null);g.dispose();
			javax.imageio.ImageIO.write(copy,"png",new java.io.File(FRAME_DUMP,String.format("%05d-%s.png",framesDumped++,frame?"frame":"tick")));}catch(Exception ignored){}
	}
	private static final boolean PERF=Boolean.getBoolean("hz68k.perf"); // -Dhz68k.perf=true: report slow adoption/refresh passes
	boolean adoptDeviceWrites(HostWindow hw){
		if(!PERF)return adoptDeviceWrites0(hw);long t=System.nanoTime();boolean r=adoptDeviceWrites0(hw);long ms=(System.nanoTime()-t)/1000000;if(ms>=5)System.err.println("[perf] adopt $"+Integer.toHexString(hw.record)+" "+ms+" ms copied="+r);return r;}
	void setIgnoresDeviceWrites(int record,boolean ignore){HostWindow hw=window(record);if(hw!=null)hw.ignoresDeviceWrites=ignore;}
	boolean adoptDeviceWrites0(HostWindow hw){
		if(headless||hw.ignoresDeviceWrites||!hw.visible||hw.imageBase<=0||java.awt.EventQueue.isDispatchThread())return false;
		QuickDraw68k screen=screenSource();if(screen==null)return false;
		int[] frame;int[] deviceClut;try{frame=screen.mainDeviceFrame();deviceClut=screen.mainDevicePalette(hw.adoptPaletteScratch);}catch(M68kCpuException e){return false;}
		if(frame==null)return false;if(deviceClut!=null)hw.adoptPaletteScratch=deviceClut;
		int w=hw.imageWidth,h=hw.imageHeight,depth=hw.imageDepth,base=hw.imageBase,rowBytes=hw.imageRowBytes;
		byte[] raw=memory.raw();if(base+(long)rowBytes*h>raw.length)return false;
		long[] bits=memory.deviceWriteBits();int deviceStart=memory.deviceStart();if(bits==null)return false;
		int deviceBase=frame[0],deviceRowBytes=frame[1],deviceDepth=frame[2],left=frame[3],top=frame[4],right=frame[5],bottom=frame[6];
		int[] windowClut=depth<=8?devicePalette(depth,hw.adoptWindowPaletteScratch):null;if(windowClut!=null)hw.adoptWindowPaletteScratch=windowClut;
		int tileColumns=(w+31)>>>5,bands=(h+31)>>>5;long[] band=hw.adoptBand;if(band==null||band.length!=tileColumns*bands)band=hw.adoptBand=new long[tileColumns*bands];
		// device tiles examined by the previous pass need no second look: only writes after it can be newer than the window's drawing
		long shown=hw.shownSerial,examined=Math.max(shown,hw.adoptedSerial);boolean any=false;
		if(!deviceChangedSince(frame,hw,examined))return false;
		long beforeAdoption=memory.writeSerial();hw.adoptedSerial=beforeAdoption;
		// The window's own drawing per 32x32 tile, sampled for the whole window before anything is copied: a 256-byte
		// chunk straddles two rows when the buffer is not chunk-aligned, so the stamp of one band's copies would
		// otherwise make the next band's older device tiles look overdrawn.
		// Device pixels under a visible window in front belong to that window (on a real Mac the screen memory there IS the
		// front window): the window behind must not adopt them, or it shows the front window's content once exposed.
		java.util.List<int[]> covers=new java.util.ArrayList<int[]>();
		for(HostWindow front:windowsFrontToBack()){if(front==hw)break;if(front.visible&&front.imageBase>0)covers.add(new int[]{front.contentLeft,front.contentTop,front.contentLeft+front.imageWidth,front.contentTop+front.imageHeight});}
		java.util.Arrays.fill(band,0);boolean[] bandChanged=new boolean[bands];
		for(int b=0;b<bands;b++){int bandRow=b<<5;if(!(bandChanged[b]=deviceBandChangedSince(frame,hw,bandRow,examined)))continue;
			for(int y=bandRow,end=Math.min(h,bandRow+32);y<end;y++){int rowStart=base+y*rowBytes;
				for(int t=0;t<tileColumns;t++){int px=t<<5;int from=depth<=8?rowStart+(px*depth>>>3):depth==16?rowStart+px*2:rowStart+px*4,length=depth<=8?(32*depth+7)>>>3:depth==16?64:128;
					long s=memory.chunkSerial(from,length);if(s>band[b*tileColumns+t])band[b*tileColumns+t]=s;}}}
		for(int b=0;b<bands;b++){
			if(!bandChanged[b])continue;
			int bandRow=b<<5,bandEnd=Math.min(h,bandRow+32);
			int firstRow=-1,lastRow=-1;
			for(int t=0;t<tileColumns;t++){
				long since=Math.max(shown,band[b*tileColumns+t]);int x0=t<<5,x1=Math.min(w,x0+32),rowEnd=Math.min(hw.contentLeft+w,right);
				for(int y=bandRow;y<bandEnd;y++){
					int gy=hw.contentTop+y;if(gy<top||gy>=bottom)continue;int deviceRow=deviceBase+(gy-top)*deviceRowBytes,rowStart=base+y*rowBytes;
					int gx0=Math.max(hw.contentLeft+x0,left),gx1=Math.min(hw.contentLeft+x1,right);if(gx0>=gx1)continue;
					int o0=deviceRow+((gx0-left)*deviceDepth>>>3),o1=deviceRow+(((gx1-left)*deviceDepth+7)>>>3);
					long tileSerial=memory.maxTileSerial(o0,Math.max(1,o1-o0));
					if(tileSerial<=since){if(Mac68kApi.TRACE&&tileSerial>examined&&y==bandRow)System.err.println("[host] adopt $"+Integer.toHexString(hw.record)+" band "+bandRow+" tile "+t+" kept: device "+tileSerial+" <= window "+band[b*tileColumns+t]+" shown "+shown);continue;}
					boolean copied=false;int tileFrom=0,tileTo=0;long tileValue=0; // the device tile of the last written byte (32 bytes of this row)
					for(int gx=gx0;gx<gx1;gx++){int px=gx-left,x=gx-hw.contentLeft;
						int o=deviceDepth<=8?deviceRow+(px*deviceDepth>>>3):deviceDepth==16?deviceRow+px*2:deviceRow+px*4,rel=o-deviceStart;
						if(o<0||o>=raw.length||rel<0||(rel>>>6)>=bits.length||(bits[rel>>>6]&(1L<<(rel&63)))==0)continue;
						if(o<tileFrom||o>=tileTo){tileFrom=memory.tileStart(o);tileTo=tileFrom+32;tileValue=memory.tileSerialAt(o);}
						if(tileValue<=since)continue;
						if(!covers.isEmpty()&&coveredBy(covers,gx,gy))continue;
						if(Mac68kApi.TRACE&&!copied)System.err.println("[host] adopt $"+Integer.toHexString(hw.record)+" row "+y+" from device $"+Integer.toHexString(o)+" at "+gx+","+gy+" device serial "+memory.maxTileSerial(o,4)+" > "+since);
						// adopted: only a new write may bring these bytes back over the window's drawing. Below 8 bits a byte holds several
						// pixels: it is forgotten with its LAST pixel, or the pixels after the first were skipped as unwritten (Bee.B.'s
						// 16-grey screen came out as black/white stripes). A 32-pixel tile of a window that is not byte-aligned on the
						// screen ends inside a byte, whose rest belongs to the next tile: only the window's right edge ends one early
						// (MacPaint, its window dragged off the 8-pixel grid, lost a few pixels of every stroke each 32 - vertical stripes).
						if(deviceDepth>=8||((px+1)*deviceDepth&7)==0||gx==rowEnd-1)memory.forgetDeviceWrites(o,deviceDepth<=8?1:deviceDepth==16?2:4);
										if(deviceDepth==depth){
							if(depth>=8){int n=depth>>>3,d=depth<=8?rowStart+x:depth==16?rowStart+x*2:rowStart+x*4;for(int i=0;i<n;i++)raw[d+i]=raw[o+i];}
							else{int shift=8-depth-(px*depth&7),value=(raw[o]&0xff)>>>shift&((1<<depth)-1);putPixel(raw,rowStart,depth,x,value);}
						}else{
							int rgb;
							if(deviceDepth<=8){int value=(raw[o]&0xff)>>>(8-deviceDepth-(px*deviceDepth&7))&((1<<deviceDepth)-1);rgb=deviceClut==null||value>=deviceClut.length?0:deviceClut[value];}
							else if(deviceDepth==16){int v=(raw[o]&0xff)<<8|(raw[o+1]&0xff);rgb=RGB555[v&0x7fff];}
							else rgb=(raw[o+1]&0xff)<<16|(raw[o+2]&0xff)<<8|(raw[o+3]&0xff);
							if(depth==32){int d=rowStart+x*4;raw[d]=(byte)0xff;raw[d+1]=(byte)(rgb>>>16);raw[d+2]=(byte)(rgb>>>8);raw[d+3]=(byte)rgb;}
							else if(depth==16){int v=(rgb>>>19&31)<<10|(rgb>>>11&31)<<5|(rgb>>>3&31),d=rowStart+x*2;raw[d]=(byte)(v>>>8);raw[d+1]=(byte)v;}
							else putPixel(raw,rowStart,depth,x,nearestIndex(windowClut,rgb));
						}
						copied=true;}
					if(copied){if(firstRow<0)firstRow=y;lastRow=y;}
				}
			}
			if(firstRow>=0){memory.touch(base+firstRow*rowBytes,(lastRow-firstRow+1)*rowBytes);any=true;
				if(Mac68kApi.TRACE)System.err.println("[host] adopt $"+Integer.toHexString(hw.record)+" band "+bandRow+" rows "+firstRow+".."+lastRow+" examined "+examined);}
		}
		// pixels adopted from the device need no publishing back into it (publishWindowDrawing); drawing still unpublished keeps its turn
		if(any&&hw.publishedSerial>=beforeAdoption)hw.publishedSerial=memory.writeSerial();
		return any;
	}
	private static boolean coveredBy(java.util.List<int[]> rects,int gx,int gy){for(int[] r:rects)if(gx>=r[0]&&gx<r[2]&&gy>=r[1]&&gy<r[3])return true;return false;}
	private static void putPixel(byte[] raw,int rowStart,int depth,int x,int value){
		if(depth==8){raw[rowStart+x]=(byte)value;return;}
		int bit=x*depth,o=rowStart+(bit>>>3),shift=8-depth-(bit&7),mask=((1<<depth)-1)<<shift;raw[o]=(byte)((raw[o]&~mask)|(value<<shift&mask));
	}
	private static int nearestIndex(int[] clut,int rgb){
		if(clut==null)return 0;int best=0,bestDistance=Integer.MAX_VALUE,r=rgb>>>16&255,g=rgb>>>8&255,b=rgb&255;
		for(int i=0;i<clut.length;i++){int c=clut[i],dr=(c>>>16&255)-r,dg=(c>>>8&255)-g,db=(c&255)-b,d=dr*dr+dg*dg+db*db;if(d<bestDistance){bestDistance=d;best=i;}}
		return best;
	}

	/**
	 * The other direction of {@link #adoptDeviceWrites}: once a program has written to the screen memory itself, the pixels
	 * its windows get through QuickDraw are copied into the device PixMap under them as well, so that the screen memory it
	 * reads back holds what the screen shows - on a real Mac a window's pixels ARE the screen memory. Bee.B. draws its
	 * backdrop PICT into its window and then copies the screen memory into its own frame buffers; here the device still
	 * held the title text it had drawn directly, and the game played over "HIGH SCORES". Called after every trap by
	 * Toolbox68k.handle (emulation thread); rows are copied only when their 256-byte chunks were written since the last
	 * pass, and never where a visible window in front covers them. The copies are plain array writes (no write stamps), so
	 * the adoption pass does not take them for the program's own screen writes.
	 */
	/** The write serial and window layout of the last complete publishing pass. */
	private long publishCheckedSerial=-1;private int publishCheckedLayout;
	void publishWindowDrawing(){
		if(headless||!memory.deviceWritten()||directTransactionActive()||java.awt.EventQueue.isDispatchThread())return;
		// Nothing tracked was written since the last pass: every window's newest chunk is already published. Without this
		// each trap scanned the chunk serials of every visible window (a megabyte of 32-bit pixels is 5000 chunks) - most of
		// the time ResEdit's colour pop-up spent learning its MDEF's layout.
		long serial=memory.writeSerial();java.util.List<HostWindow> order=windowsFrontToBack();int layout=1;
		for(HostWindow hw:order)layout=layout*31+(hw.visible?1:0)+hw.record*7+hw.imageBase*13+hw.contentLeft*17+hw.contentTop*19+hw.imageRowBytes*23+hw.imageHeight;
		if(serial==publishCheckedSerial&&layout==publishCheckedLayout)return;
		QuickDraw68k screen=screenSource();if(screen==null)return;
		int[] frame;try{frame=screen.mainDeviceFrame();}catch(M68kCpuException e){return;}
		if(frame==null||frame[0]<=0)return;
		byte[] raw=memory.raw();int deviceBase=frame[0],deviceRowBytes=frame[1],deviceDepth=frame[2],left=frame[3],top=frame[4],right=frame[5],bottom=frame[6];
		if(deviceBase+(long)deviceRowBytes*(bottom-top)>raw.length)return;
		publishCheckedSerial=serial;publishCheckedLayout=layout;
		java.util.List<HostWindow> frontToBack=order;java.util.List<int[]> covers=new java.util.ArrayList<int[]>();
		int[] deviceClut=null;boolean deviceClutRead=false;
		for(HostWindow hw:frontToBack){
			if(!hw.visible||hw.imageBase<=0){continue;}
			int w=hw.imageWidth,h=hw.imageHeight,depth=hw.imageDepth,base=hw.imageBase,rowBytes=hw.imageRowBytes;
			if(base+(long)rowBytes*h<=raw.length){
				long newest=memory.chunkSerial(base,rowBytes*h);
				if(newest>hw.publishedSerial){
					long since=hw.publishedSerial;hw.publishedSerial=memory.writeSerial();
					int[] windowClut=depth<=8?devicePalette(depth,hw.publishPaletteScratch):null;if(windowClut!=null)hw.publishPaletteScratch=windowClut;
					if(!deviceClutRead){deviceClutRead=true;if(deviceDepth<=8){try{deviceClut=screen.mainDevicePalette(hw.publishDevicePaletteScratch);}catch(M68kCpuException e){deviceClut=null;}if(deviceClut!=null)hw.publishDevicePaletteScratch=deviceClut;}}
					int gx0=Math.max(hw.contentLeft,left),gx1=Math.min(hw.contentLeft+w,right);int copiedRows=0,skippedRows=0;
					if(gx0<gx1)for(int y=0;y<h;y++){
						int gy=hw.contentTop+y;if(gy<top||gy>=bottom)continue;
						int rowStart=base+y*rowBytes;long rowSerial=memory.chunkSerial(rowStart,rowBytes);if(rowSerial<=since)continue;
						int deviceRow=deviceBase+(gy-top)*deviceRowBytes;
						// the program's own writes there since the window drew win, as they would on a real screen
						int d0=deviceRow+((gx0-left)*deviceDepth>>>3),d1=deviceRow+(((gx1-left)*deviceDepth+7)>>>3);
						if(memory.maxTileSerial(d0,Math.max(1,d1-d0))>rowSerial){skippedRows++;continue;}
						copiedRows++;
						boolean aligned=depth==deviceDepth&&depth>=8;
						if(aligned&&covers.isEmpty()){int n=depth>>>3;System.arraycopy(raw,rowStart+(gx0-hw.contentLeft)*n,raw,deviceRow+(gx0-left)*n,(gx1-gx0)*n);continue;}
						for(int gx=gx0;gx<gx1;gx++){
							if(!covers.isEmpty()&&coveredBy(covers,gx,gy))continue;
							int x=gx-hw.contentLeft,px=gx-left;
							if(depth==deviceDepth){
								if(depth>=8){int n=depth>>>3;System.arraycopy(raw,rowStart+x*n,raw,deviceRow+px*n,n);}
								else{int shift=8-depth-(x*depth&7),value=(raw[rowStart+(x*depth>>>3)]&0xff)>>>shift&((1<<depth)-1);putPixel(raw,deviceRow,depth,px,value);}
							}else{
								int rgb;
								if(depth<=8){int value=(raw[rowStart+(x*depth>>>3)]&0xff)>>>(8-depth-(x*depth&7))&((1<<depth)-1);rgb=windowClut==null||value>=windowClut.length?0:windowClut[value];}
								else if(depth==16){int o=rowStart+x*2,v=(raw[o]&0xff)<<8|(raw[o+1]&0xff);rgb=RGB555[v&0x7fff];}
								else{int o=rowStart+x*4;rgb=(raw[o+1]&0xff)<<16|(raw[o+2]&0xff)<<8|(raw[o+3]&0xff);}
								if(deviceDepth==32){int d=deviceRow+px*4;raw[d]=(byte)0xff;raw[d+1]=(byte)(rgb>>>16);raw[d+2]=(byte)(rgb>>>8);raw[d+3]=(byte)rgb;}
								else if(deviceDepth==16){int v=(rgb>>>19&31)<<10|(rgb>>>11&31)<<5|(rgb>>>3&31),d=deviceRow+px*2;raw[d]=(byte)(v>>>8);raw[d+1]=(byte)v;}
								else putPixel(raw,deviceRow,deviceDepth,px,nearestIndex(deviceClut,rgb));
							}
						}
					}
					if(Mac68kApi.TRACE&&(copiedRows>0||skippedRows>0))System.err.println("[host] publish $"+Integer.toHexString(hw.record)+" rows copied "+copiedRows+" skipped "+skippedRows+" since "+since);
				}
			}
			covers.add(new int[]{hw.contentLeft,hw.contentTop,hw.contentLeft+w,hw.contentTop+h});
		}
	}

	/** Debug (commands.txt `dirty`): per window, the write serials of its buffer and of the device bytes under it. */
	String describeWindowSerials(){
		StringBuilder sb=new StringBuilder();QuickDraw68k screen=screenSource();int[] frame=null;try{if(screen!=null)frame=screen.mainDeviceFrame();}catch(M68kCpuException e){frame=null;}
		for(HostWindow hw:windowsFrontToBack()){if(!hw.visible||hw.imageBase<=0)continue;
			long buffer=memory.chunkSerial(hw.imageBase,hw.imageRowBytes*hw.imageHeight),device=0;
			if(frame!=null)for(int y=0;y<hw.imageHeight;y++){int[] span=deviceSpan(frame,hw,y);if(span!=null)device=Math.max(device,memory.maxTileSerial(span[0],span[1]));}
			sb.append(String.format("%n  window $%06X '%s' at %d,%d %dx%d depth %d buffer $%X rowBytes %d: buffer serial %d, device serial under it %d, shown %d, adopted %d, displayed %d",hw.record,hw.title,hw.contentLeft,hw.contentTop,hw.imageWidth,hw.imageHeight,hw.imageDepth,hw.imageBase,hw.imageRowBytes,buffer,device,hw.shownSerial,hw.adoptedSerial,hw.displaySerial));}
		return sb.toString();
	}

	/** Debug (commands.txt `paintdump <dir>`): every paint writes the composed canvas image there. */
	static volatile java.io.File paintDumpDir;
	private static final boolean HOST_TRACE=Mac68kApi.TRACE||Boolean.getBoolean("hz68k.hosttrace"); // -Dhz68k.hosttrace=true: window geometry events only
	private final Listener listener;
	private final M68kMemory memory;
	private volatile BufferedImage applicationIcon;
	private final String applicationName;
	private final Map<Integer,HostWindow> windows=new HashMap<Integer,HostWindow>();
	private final List<HostWindow> order=new ArrayList<HostWindow>();
	private final java.util.concurrent.ScheduledFuture<?> refresh;
	private final java.util.concurrent.atomic.AtomicBoolean tickQueued=new java.util.concurrent.atomic.AtomicBoolean();
	private int menuVersion;
	private List<MenuModel> menuBar=new ArrayList<MenuModel>();
	private Cursor cursor=Cursor.getDefaultCursor();
	private boolean cursorHidden;
	volatile boolean mouseDown;volatile int mouseGlobalH,mouseGlobalV;volatile int modifierState;
	private HostWindow frontWindow;
	private final AWTEventListener globalMouse;
	private volatile boolean disposed;
	private final Map<Integer,Image> overlays=new HashMap<Integer,Image>();
	/** Stand-in frame for a program that has a menu bar but no visible window while waiting for a command or drop. */
	private JFrame standIn;private int standInMenuVersion=-1;
	/** Pending show of the stand-in: it appears only after STAND_IN_DELAY_MS with still no window, so a program that opens its window right after its menu bar never flashes it. */
	private javax.swing.Timer standInTimer;
	private static final int STAND_IN_DELAY_MS=Integer.getInteger("hz68k.standInDelay",700);
	/** Headless hosts (VMs without an application or stack, e.g. tests) keep windows in memory only. */
	final boolean headless;

	/** One menu of the application's menu bar (built from MENU handles by the Menu Manager). */
	static final class MenuModel {
		final int id;final String title;final boolean enabled;final List<ItemModel> items=new ArrayList<ItemModel>();boolean apple;
		/** An external's menu in HyperCard's bar: the HyperCard menu it stands after (null: before them all, or none in the bar). */
		Object after;
		/** How many items of the menu are left out before the first one here (the Help menu's system items): item 1 is number itemBase+1. */
		int itemBase;
		MenuModel(int id,String title,boolean enabled){this.id=id;this.title=title;this.enabled=enabled;}
		/** A menu drawn by the program's own MDEF (HyperCard's Tools and Patterns menus): its rendering, and the items the MDEF
		 *  reports in it as {item, top, left, bottom, right}; each becomes a host item showing its slice of the picture. */
		java.awt.image.BufferedImage image;List<int[]> pictureItems;
		/** The text size the program's MDEF set before handing the menu to the standard definition (Infini-D 2.0.1's view
		 *  pop-up: 9), or 0 for the menu font. Only pop-ups use it; the menu bar keeps the host's menu font. */
		int fontSize;
		/** Text of a host item added under a picture menu that shows the menu as a palette (HyperCard's tear-off menus cannot be
		 *  dragged off a host menu), or null. */
		String tearOff;
		/** Whether the host menu built from {@code other} would look and behave the same as one built from this model. */
		/**
		 * Whether a host menu built from {@code other} can be brought up to this model in place: the same menus, items, separators,
		 * submenus, command keys, text styles and icons, so only titles, item texts, marks, enabled states and help texts differ.
		 */
		boolean sameLayout(MenuModel other){
			if(other==null||id!=other.id||apple!=other.apple||fontSize!=other.fontSize||itemBase!=other.itemBase||image!=null||other.image!=null||!java.util.Objects.equals(tearOff,other.tearOff)||items.size()!=other.items.size())return false;
			for(int i=0;i<items.size();i++){ItemModel a=items.get(i),b=other.items.get(i);
				if(a.separator!=b.separator||a.command!=b.command||a.modifiers!=b.modifiers||a.iconKey!=b.iconKey||a.style!=b.style||a.submenuId!=b.submenuId||(a.submenu==null)!=(b.submenu==null))return false; // a new text style is a new font: built anew
				if(a.submenu!=null&&!a.submenu.sameLayout(b.submenu))return false;}
			return true;
		}
		boolean sameAs(MenuModel other){
			if(other==null||id!=other.id||enabled!=other.enabled||apple!=other.apple||fontSize!=other.fontSize||!java.util.Objects.equals(title,other.title)||!java.util.Objects.equals(tearOff,other.tearOff)||items.size()!=other.items.size())return false;
			for(int i=0;i<items.size();i++)if(!items.get(i).sameAs(other.items.get(i)))return false;
			if((image==null)!=(other.image==null))return false;
			if(image!=null){
				if(image.getWidth()!=other.image.getWidth()||image.getHeight()!=other.image.getHeight())return false;
				if((pictureItems==null)!=(other.pictureItems==null))return false;
				if(pictureItems!=null){if(pictureItems.size()!=other.pictureItems.size())return false;for(int i=0;i<pictureItems.size();i++)if(!java.util.Arrays.equals(pictureItems.get(i),other.pictureItems.get(i)))return false;}
				int w=image.getWidth(),h=image.getHeight();int[] a=image.getRGB(0,0,w,h,null,0,w),b=other.image.getRGB(0,0,w,h,null,0,w);if(!java.util.Arrays.equals(a,b))return false;
			}
			return true;
		}
	}
	static final class ItemModel {
		String text;boolean enabled=true,checked;char command;int mark;int submenuId;int style;boolean separator;
		/** The mark character as the menu font shows it (a check mark, a diamond, √, #...), or null for none or a submenu's ID. */
		String markText;
		/** SetMenuItemModifiers bits: shift 1, option 2, control 4, no Command 8. */
		int modifiers;
		/** The item's icon (ICON/cicn/SICN 256+n), or null. */
		java.awt.image.BufferedImage icon;int iconKey;
		/** Balloon Help text of the item (null: none, or balloons off) shown as the host item's tooltip. */
		String help;
		MenuModel submenu;
		boolean sameAs(ItemModel o){
			return o!=null&&java.util.Objects.equals(text,o.text)&&enabled==o.enabled&&checked==o.checked&&command==o.command&&mark==o.mark&&java.util.Objects.equals(markText,o.markText)&&submenuId==o.submenuId&&style==o.style&&separator==o.separator&&iconKey==o.iconKey
				&&java.util.Objects.equals(help,o.help)&&(submenu==null?o.submenu==null:submenu.sameAs(o.submenu));
		}
	}

	MacAppHost(Listener listener,M68kMemory memory,String applicationName){this(listener,memory,applicationName,false);}

	MacAppHost(Listener listener,M68kMemory memory,String applicationName,boolean headless){this(listener,memory,applicationName,headless,false);}

	/** {@code external}: the host of a HyperCard external, whose screen is HyperCard's (see {@link #screenBounds(boolean)}). */
	MacAppHost(Listener listener,M68kMemory memory,String applicationName,boolean headless,boolean external){
		this.listener=listener;this.memory=memory;this.applicationName=applicationName;this.headless=headless;this.external=external;
		if(headless){scPct=100;origin=new Point(0,0);screenRect=null;limitedExternal=false;}
		else{
			int pct=external?externalPercent():100;
			Rectangle round=external?ExternalScreenArea.join(this,pct):null; // a classic screen one size up round the card window
			limitedExternal=round!=null;
			Rectangle screen=round!=null?new Rectangle(0,0,round.width,round.height):screenBounds(external);
			scPct=external?pct:scaleFor(screen);origin=round!=null?round.getLocation():external?displayOrigin():screenOriginFor(screen,scPct);
			screenRect=new Rectangle(origin.x,origin.y,hostLen(screen.width),hostLen(screen.height));screenWidth=screen.width;screenHeight=screen.height;}
		if(headless){globalMouse=null;refresh=null;return;}
		MacFocusRecovery.install();
		// ticks from the pacing thread at the exact rate (a Swing Timer of 16 ms fired about 51 times a second), one queued at a time
		refresh=PRESENT_DELAY.scheduleAtFixedRate(new Runnable(){@Override public void run(){tick();}},FRAME_NANOS,FRAME_NANOS,java.util.concurrent.TimeUnit.NANOSECONDS);
		globalMouse=new AWTEventListener(){@Override public void eventDispatched(AWTEvent event){
			if(event instanceof MouseEvent){MouseEvent me=(MouseEvent)event;
				if(me.getID()==MouseEvent.MOUSE_RELEASED&&(me.getModifiersEx()&InputEvent.BUTTON1_DOWN_MASK)==0)mouseDown=false;
				if(me.getID()==MouseEvent.MOUSE_DRAGGED||me.getID()==MouseEvent.MOUSE_MOVED||me.getID()==MouseEvent.MOUSE_RELEASED||me.getID()==MouseEvent.MOUSE_PRESSED){
					try{Point p=toGuest(me.getLocationOnScreen());mouseGlobalH=p.x;mouseGlobalV=p.y;}catch(Exception ignored){}
					modifierState=hostModifiers(me.getModifiersEx());
					TabletInput.observe(me); // pen or mouse: the VM's ".Wacom" driver reads it (WacomTablet68k)
				}
			}
			if(event instanceof KeyEvent){KeyEvent ke=(KeyEvent)event;boolean press=ke.getID()==KeyEvent.KEY_PRESSED;
				if(press||ke.getID()==KeyEvent.KEY_RELEASED){int held=ModifierKeys.held(ke.getKeyCode(),ke.getKeyLocation());synchronized(hostKeysHeld){if(press)hostKeysHeld.add(held);else hostKeysHeld.remove(held);}}
				modifierState=hostModifiers(ke.getModifiersEx());
				// Key map for GetKeys: pressed until the matching release (macOS auto-repeat sends no releases in between).
				// With the program's own modifier keys the modifiers' codes come from modifierState alone (EventManager68k.writeKeys).
				if((press||ke.getID()==KeyEvent.KEY_RELEASED)&&!(ownModifierKeys()!=null&&(isModifierKey(ke)||isOwnModifierKey(ke)))){
					int code=macKeyCode(ke.getKeyCode(),ke.getKeyLocation());if(code>=0)synchronized(keysDown){keysDown[code]=press;}}
			}
			// An external's key map follows the keys of all of HyperZebra's windows, so only a key let go while another
			// application has the keyboard sends no release: K-0Fighters' KStealKeyX (GetKeys) saw it held for the rest of the
			// battle. Every key and modifier is up again then (a key still held comes back with its auto-repeat), as the card's
			// key[] (GUI.cardDeactivated). A program's own windows release their keys when they are deactivated.
			if(external&&event.getID()==WindowEvent.WINDOW_DEACTIVATED&&((WindowEvent)event).getOppositeWindow()==null){releaseAllKeys();modifierState&=0x80;}
			if(external&&event.getID()==WindowEvent.WINDOW_ACTIVATED&&((WindowEvent)event).getWindow()==PCARD.pc)cardActivated();
		}};
		Toolkit.getDefaultToolkit().addAWTEventListener(globalMouse,AWTEvent.MOUSE_EVENT_MASK|AWTEvent.MOUSE_MOTION_EVENT_MASK|AWTEvent.KEY_EVENT_MASK|AWTEvent.WINDOW_EVENT_MASK);
	}
	/** The zoom setting changed while the program runs: the screen is magnified by the new choice at once. The program's idea of
	 *  the screen stays as it is; only host pixels change, so every window is sized and placed again. */
	void rezoom(final MacApp68k app){
		if(headless||external||SCREEN_OVERRIDE!=null||screenWidth<=0||screenHeight<=0)return;
		runOnEdt(new Runnable(){@Override public void run(){
			Rectangle screen=new Rectangle(0,0,screenWidth,screenHeight);
			int pct=scaleFor(screen,app);if(pct==scPct)return;
			scPct=pct;origin=screenOriginFor(screen,pct,app);screenRect=new Rectangle(origin.x,origin.y,hostLen(screenWidth),hostLen(screenHeight));
			configureSmooth(true); // the planes' scale follows the magnification
			relayoutWindows();
		}},true);
	}
	/** The game screen's place setting (centre or top left) changed while the program runs: the screen moves there at once. */
	void reposition(final MacApp68k app){
		if(headless||external||SCREEN_OVERRIDE!=null||screenWidth<=0||screenHeight<=0||!gameScreen())return;
		runOnEdt(new Runnable(){@Override public void run(){
			if(disposed)return;
			Point o=screenOriginFor(new Rectangle(0,0,screenWidth,screenHeight),scPct,app);
			if(screenBar!=null&&screenBar.isShowing()){o=originWithFrame(o,usableAreaAround(screenRect));if(o==null)return;} // its frame stays on the display
			moveScreenTo(o);
		}},false);
	}
	/**
	 * The magnification of a HyperCard external's screen: the card window's zoom. An external draws on the card in card pixels
	 * and puts its windows at card coordinates (Myst's movies at "the topleft of card button marker2"), so its screen is the
	 * display at the card's scale - a 2x card had its movies at half size, at half their distance from the card's corner.
	 */
	static int externalPercent(){PCARD pc=PCARD.pc;return pc!=null&&pc.isShowing()&&!java.awt.GraphicsEnvironment.isHeadless()?PCARD.scalePercent():100;}
	/** An external's card window is opened (EDT or not): its windows follow the zoom of the stack it now serves (another stack
	 *  of a shared external VM, a zoom changed in the settings). The screen the external sees keeps its size. */
	void followStackZoom(){
		if(headless||!external||SCREEN_OVERRIDE!=null)return;
		int pct=externalPercent();if(pct==scPct)return;
		// at once: the card window about to be placed reads its place through toGuest; the native windows follow on the EDT
		scPct=pct;if(limitedExternal)origin=ExternalScreenArea.rezoomed(this,pct);screenRect=new Rectangle(origin.x,origin.y,hostLen(screenWidth),hostLen(screenHeight));
		runOnEdt(new Runnable(){@Override public void run(){relayoutWindows();}},false);
	}
	/** Whether the screen size is fixed for the whole run ({@code -Dhz68k.screen}): a program cannot switch the display mode then. */
	static boolean screenSizeFixed(){return SCREEN_OVERRIDE!=null;}
	/**
	 * The program's screen changed size while it runs (a game switching the display mode, see MacApp68k.changeScreenSize):
	 * {@code screen} is the new one. Magnification and position are worked out again for it - a game screen is centred and
	 * magnified by the zoom setting, the full screen is back at the display's origin - and every window is placed again.
	 */
	void screenResized(final MacApp68k app,final Rectangle screen){
		if(headless||external||SCREEN_OVERRIDE!=null)return;
		runOnEdt(new Runnable(){@Override public void run(){
			int pct=scaleFor(screen,app);
			scPct=pct;origin=screenOriginFor(screen,pct,app);screenWidth=screen.width;screenHeight=screen.height;
			screenRect=new Rectangle(origin.x,origin.y,hostLen(screen.width),hostLen(screen.height));
			configureSmooth(true);
			relayoutWindows();
		}},true);
	}
	/** Every host window and the direct-screen frame sized and placed again after the magnification or the screen changed (on the EDT). */
	private void relayoutWindows(){
		java.util.List<HostWindow> all;synchronized(windows){all=new java.util.ArrayList<HostWindow>(windows.values());}
		for(HostWindow hw:all){
			if(hw.window==null||hw.canvas==null)continue;
			packWindow(hw);placeWindow(hw);hw.canvas.revalidate();hw.canvas.repaint();
		}
		if(screenFrame!=null&&screenCanvas!=null&&screenImage!=null){
			int w=screenImage.getWidth(),h=screenImage.getHeight();
			screenCanvas.setPreferredSize(new Dimension(hostLen(w),hostLen(h)));screenCanvas.setSize(hostLen(w),hostLen(h));screenFrame.pack();
			if(screenFrameGeometry!=null)screenFrame.setLocation(Math.max(0,hostX(screenFrameGeometry[3])),Math.max(0,hostY(screenFrameGeometry[4])-screenFrame.getInsets().top));
			screenCanvas.repaint();
		}
		updateScreenBar();
	}
	// ------------------------------------------------------------ the game screen's window frame

	/*
	 * A full-screen program on a game screen - Woden's black backdrop over its whole 640x480 screen, centred on the display - has
	 * nothing the user can take hold of to move it. It gets a Mac OS 9 window frame round the screen (ScreenWindowFrame, titled
	 * with the program's name): Apple Platinum, or the document window of the Kaleidoscope scheme the virtual System Folder uses.
	 * Dragging it moves the whole screen, every window with it; a double click on its title bar centres the screen again. Only the
	 * host's picture moves: the program's idea of its screen and of its windows' places stays the same. A program that lays
	 * ordinary windows out on its screen (iTunes) gets no frame - its windows are windows of their own on the display. Neither
	 * does one shown on the display itself, nor a HyperCard external's windows, which live among HyperCard's.
	 * -Dhz68k.screenbar=false: never.
	 */
	private static final boolean SCREEN_BAR=!"false".equals(System.getProperty("hz68k.screenbar"));
	private ScreenWindowFrame screenBar;
	/** Draws the frame active while one of the program's windows is the active window (the frame itself never is). */
	private java.beans.PropertyChangeListener screenBarFocus;
	/** The screen's origin when the frame was pressed (EDT only). */
	private Point screenDragOrigin;
	/** The screen is being moved: until then (System.nanoTime) the native windows' move reports are this host's own placing,
	 *  possibly late and stale on macOS, not the user's drags - each would have told the program its window moved. */
	private volatile long screenMovingUntil;
	private javax.swing.Timer screenMoveSettle;
	private static final long SCREEN_MOVE_QUIET_NANOS=400_000_000L;
	private boolean screenMoving(){long until=screenMovingUntil;return until!=0&&System.nanoTime()-until<0;}
	private static boolean userDragging(HostWindow hw){long until=hw.userDragUntil;return until!=0&&System.nanoTime()-until<0;}
	/** The program sees a game screen (its own size, the preference or -Dhz68k.screen) placed by this host, not the display. */
	private boolean gameScreen(){
		if(SCREEN_OVERRIDE!=null)return true;
		MacApp68k app=owner;
		return (app!=null&&app.screenSizeOverride!=null?parseScreenOverride(app.screenSizeOverride):virtualScreen)!=null;
	}
	/**
	 * A shown window of the program covers its whole screen (a backdrop, a full-screen game window). A window with a title bar
	 * of its own does not count, however large: the user moves it by that. ClarisWorks' document window grown to its largest
	 * (640x460 on a 640x480 screen) sits right under the screen's top on a zoomed screen - the native title bar is fewer
	 * program pixels than the menu bar - and the frame came up round the screen, outside the window.
	 */
	boolean fullScreenWindowShown(){
		for(HostWindow hw:windowsFrontToBack())if(hw.visible&&hw.window!=null&&hw.window.isVisible()&&(hw.backdrop||!hw.decorated&&coversScreen(hw.contentLeft,hw.contentTop,hw.width,hw.height)))return true;
		return false;
	}
	/** The frame's look: the Kaleidoscope scheme's document window when one is in use and has one, else Apple Platinum. */
	private ScreenWindowFrame.Look screenLook(){
		MacApp68k app=owner;KaleidoscopeScheme68k scheme=app==null?null:KaleidoscopeScheme68k.active(app.toolbox);
		ScreenWindowFrame.Look look=scheme==null?null:scheme.documentWindow();
		return look!=null?look:ScreenWindowFrame.PLATINUM;
	}
	/** The frame's widths outside the screen in host pixels {top, left, bottom, right}. */
	private int[] screenFrameOutsets(ScreenWindowFrame.Look look){int[] o=look.outsets();for(int i=0;i<4;i++)o[i]=hostLen(o[i]);return o;}
	/** The screen's origin moved as little as needed for the screen in its frame to lie on {@code usable}; null when they do not fit there. */
	private Point originWithFrame(Point o,Rectangle usable,int[] f){
		int w=hostLen(screenWidth),h=hostLen(screenHeight);
		if(usable==null||w+f[1]+f[3]>usable.width||h+f[0]+f[2]>usable.height)return null;
		return new Point(Math.max(usable.x+f[1],Math.min(usable.x+usable.width-w-f[3],o.x)),Math.max(usable.y+f[0],Math.min(usable.y+usable.height-h-f[2],o.y)));
	}
	private Point originWithFrame(Point o,Rectangle usable){return originWithFrame(o,usable,screenFrameOutsets(screenBar!=null?screenLookShown:screenLook()));}
	/** The look the frame was last shown with (EDT only). */
	private ScreenWindowFrame.Look screenLookShown=ScreenWindowFrame.PLATINUM;
	/** Shows, places or hides the frame as the program's windows and screen now ask (EDT only). */
	private void updateScreenBar(){
		if(limitedExternal&&!headless){ExternalScreenArea.update();return;}
		if(headless||external)return;
		Point at=null;ScreenWindowFrame.Look look=null;
		if(SCREEN_BAR&&!disposed&&!processHidden&&!saverMode&&screenRect!=null&&!(screenFrame!=null&&screenFrame.isVisible())&&gameScreen()&&fullScreenWindowShown()){
			look=screenLook();at=originWithFrame(origin,usableAreaAround(screenRect),screenFrameOutsets(look));}
		if(at==null){if(screenBar!=null)screenBar.hide();return;}
		screenLookShown=look;
		if(!at.equals(origin))moveScreenTo(at); // centred with no room for the frame: in just enough
		if(screenBar==null){screenBar=new ScreenWindowFrame(applicationName,new ScreenWindowFrame.Mover(){
			@Override public void pressed(){if(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()!=null)focusFrontWindow();} // from the background: MacFocusRecovery
			@Override public void dragStarted(){screenDragOrigin=origin;}
			@Override public void draggedBy(int dx,int dy){
				if(screenDragOrigin==null)return;
				// onto the display under the pointer: dragged across to another display, the screen follows it there
				Rectangle usable=null;try{PointerInfo pi=MouseInfo.getPointerInfo();if(pi!=null){Point p=pi.getLocation();usable=usableAreaAround(new Rectangle(p.x,p.y,1,1));}}catch(Exception ignored){}
				if(usable==null)usable=usableAreaAround(screenRect);
				Point o=originWithFrame(new Point(screenDragOrigin.x+dx,screenDragOrigin.y+dy),usable);if(o!=null)moveScreenTo(o);}
			@Override public void dragEnded(){screenDragOrigin=null;}
			@Override public void centre(){
				Rectangle screen=new Rectangle(0,0,screenWidth,screenHeight);Point o=originWithFrame(screenOriginFor(screen,scPct,owner),usableAreaAround(screenRect));
				if(o!=null)moveScreenTo(o);}
		});
			screenBarFocus=new java.beans.PropertyChangeListener(){@Override public void propertyChange(java.beans.PropertyChangeEvent e){if(screenBar!=null&&screenBar.isShowing())placeScreenBar();}};
			java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow",screenBarFocus);}
		placeScreenBar();
	}
	/** The program's front window (not a backdrop behind others, not a floater) brought forward with the keyboard focus, as a
	 *  click on it would: for a click on the frame round the screen, which never takes the focus itself. */
	private HostWindow frontFocusable(){
		for(HostWindow hw:windowsFrontToBack())if(hw.visible&&hw.window!=null&&hw.window.isVisible()&&!hw.behindHyperCard&&!hw.floatingLayer&&!hw.systemWindow&&hw.window.getFocusableWindowState())return hw;
		return null;
	}
	/** The native window of the program's front window that can take the focus (MacFocusRecovery), or null. EDT. */
	Window frontFocusableWindow(){HostWindow hw=frontFocusable();return hw==null?null:hw.window;}
	private void focusFrontWindow(){
		HostWindow front=frontFocusable();
		if(HOST_TRACE)System.err.println("[host] frame round the screen pressed: focus to "+(front==null?"-":"$"+Integer.toHexString(front.record))+" (active window "+java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()+")");
		if(front==null)return;
		restack();
		front.window.toFront();if(front.canvas!=null)front.canvas.requestFocus();
	}
	private void placeScreenBar(){
		ScreenWindowFrame frame=screenBar;Rectangle r=screenRect;if(frame==null||r==null)return;
		Window active=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		frame.show(r,screenWidth,screenHeight,scPct,screenLookShown,active!=null&&ownsWindow(active));
	}
	/** Moves the whole screen to host position {@code o}: every window goes along, the program notices nothing (EDT only). */
	private void moveScreenTo(Point o){
		if(o.equals(origin))return;
		screenMovingUntil=System.nanoTime()+SCREEN_MOVE_QUIET_NANOS;
		origin=o;screenRect=new Rectangle(o.x,o.y,hostLen(screenWidth),hostLen(screenHeight));
		placeAllWindows();
		if(screenBar!=null&&screenBar.isShowing())placeScreenBar();
		// once the moves are over, the native windows are put where they belong once more (a late report from macOS may have
		// left one at an earlier place) and their own moves count again
		if(screenMoveSettle==null){screenMoveSettle=new javax.swing.Timer((int)(SCREEN_MOVE_QUIET_NANOS/2_000_000L),new ActionListener(){@Override public void actionPerformed(ActionEvent e){
			if(disposed)return;screenMovingUntil=System.nanoTime()+SCREEN_MOVE_QUIET_NANOS;placeAllWindows();}});screenMoveSettle.setRepeats(false);}
		screenMoveSettle.restart();
	}
	private void placeAllWindows(){
		java.util.List<HostWindow> all;synchronized(windows){all=new java.util.ArrayList<HostWindow>(windows.values());}
		for(HostWindow hw:all)if(hw.window!=null)placeWindow(hw);
		if(screenFrame!=null&&screenFrameGeometry!=null)screenFrame.setLocation(Math.max(0,hostX(screenFrameGeometry[3])),Math.max(0,hostY(screenFrameGeometry[4])-screenFrame.getInsets().top));
	}
	/**
	 * Where the user may drag a window the Window Manager drags itself (a frame drawn in the window: Kaleidoscope's, a floating
	 * window's), in the program's global coordinates {top, left, bottom, right}: the usable area of every display, as for a
	 * native frame - not the program's DragWindow bounds, which on a game screen (640x480) kept such windows in the middle of the
	 * display while native ones went anywhere. Null without a display.
	 */
	int[] userDragArea(){
		if(headless||screenRect==null)return null;
		Rectangle all=null;
		try{for(java.awt.GraphicsDevice gd:java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
			java.awt.GraphicsConfiguration gc=gd.getDefaultConfiguration();Rectangle b=gc.getBounds();Insets si=Toolkit.getDefaultToolkit().getScreenInsets(gc);
			Rectangle u=new Rectangle(b.x+si.left,b.y+si.top,b.width-si.left-si.right,b.height-si.top-si.bottom);all=all==null?u:all.union(u);}}catch(Exception e){return null;}
		if(all==null)return null;
		return new int[]{virtualY(all.y),virtualX(all.x),virtualY(all.y+all.height),virtualX(all.x+all.width)};
	}
	/** Test hook: the frame's title bar (its top edge, corners included) while the frame is shown, else null. */
	Rectangle screenBarBounds(){ScreenWindowFrame b=screenBar;return b!=null?b.titleBarBounds():null;}
	/** Test hook: the frame's widths in host pixels {top, left, bottom, right}. */
	int[] screenBarOutsets(){return screenFrameOutsets(screenLookShown);}
	/** Test hook: drags the frame by host pixels {@code dx},{@code dy} as the user would (EDT). */
	void dragScreenBar(int dx,int dy){if(screenBar==null)return;Point o=originWithFrame(new Point(origin.x+dx,origin.y+dy),usableAreaAround(screenRect));if(o!=null)moveScreenTo(o);}

	void setApplicationIcon(BufferedImage icon){applicationIcon=icon;}

	String applicationName(){return applicationName;}
	Listener listener(){return listener;}
	/** Mouse button state: the host's for real windows, HyperZebra's card for headless VMs. */
	/** The button: an external also sees the presses on HyperCard's own windows (Button() is global on a Mac). */
	boolean isMouseDown(){return headless?GUI.mouseDown:external?mouseDown||GUI.mouseDown:mouseDown;}
	/** Whether the button was down as HyperTalk last entered an external (its outermost call or a window event): see popUpMenuSelect. */
	private volatile boolean mouseDownAtExternalEntry=true;
	void externalEntered(){mouseDownAtExternalEntry=isMouseDown();}
	int modifiers(){return headless?0:modifierState;}
	/** Caps Lock as the host has it locked now (read at most every 250 ms): GetKeys key $39. Only key events set the other keys,
	 *  so a Caps Lock locked before HyperZebra came forward was never seen (XTernal Collection's CapsLockDown). */
	boolean capsLocked(){
		if(headless)return false;long now=System.nanoTime();
		if(now-capsLockReadAt>250_000_000L||capsLockReadAt==0){capsLockReadAt=now;
			try{capsLock=java.awt.Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK);}catch(RuntimeException e){capsLock=false;}}
		return capsLock;
	}
	private volatile boolean capsLock;private volatile long capsLockReadAt;
	/** Currently pressed keys by Mac virtual key code (GetKeys / KeyMap). */
	private final boolean[] keysDown=new boolean[128];
	boolean isKeyDown(int keyCode){if(keyCode<0||keyCode>=128)return false;synchronized(keysDown){return keysDown[keyCode];}}
	/** Fills a 16-byte KeyMap (bit n of byte n/8 = key code n). */
	void keyMap(byte[] map){synchronized(keysDown){for(int i=0;i<128;i++)if(keysDown[i])map[i>>>3]|=(byte)(1<<(i&7));}}
	void releaseAllKeys(){synchronized(keysDown){java.util.Arrays.fill(keysDown,false);}synchronized(hostKeysHeld){hostKeysHeld.clear();}suspendSystemHotKeys(false);}
	/** Host keys held (ModifierKeys.held), for the program's own modifier keys. */
	private final java.util.Set<Integer> hostKeysHeld=new java.util.HashSet<Integer>();
	private ModifierKeys ownModifierKeys(){MacApp68k app=owner;return app==null?null:app.modifierKeysOverride;}
	/** A key the program's own settings made Command, Option or Control: it types nothing itself. */
	private boolean isOwnModifierKey(KeyEvent e){ModifierKeys own=ownModifierKeys();return own!=null&&own.isSource(e.getKeyCode(),e.getKeyLocation());}
	/** The Mac modifiers (event-record bits) for an event's extended modifiers, through the program's own modifier keys if it has them. */
	int hostModifiers(int ex){
		ModifierKeys own=ownModifierKeys();if(own==null)return macModifiers(ex);
		int m=(ex&InputEvent.BUTTON1_DOWN_MASK)==0?0x80:0;
		synchronized(hostKeysHeld){m|=own.modifiers(hostKeysHeld,GMenu.isMacOSX());}
		return m;
	}
	/** macOS takes Control-arrow (Mission Control, Spaces), Control-number and friends before the window sees them, but
	 *  games such as Woden steer with Control and the arrows: while Control is held in a program window the system's global
	 *  hot keys are off (NativeHotKeys, ffm/, loaded by reflection), so Command-Tab, Spotlight and screenshots still work.
	 *  The preference (PCARD.controlKeysToPrograms68k) or the program's own setting can leave them to macOS. */
	volatile MacApp68k owner;
	private boolean controlKeysToProgram(){MacApp68k app=owner;Boolean own=app==null?null:app.controlKeysOverride;return own!=null?own:PCARD.controlKeysToPrograms68k;}
	private static java.lang.reflect.Method hotKeySuspend;private static boolean hotKeysChecked,hotKeysSuspended;
	static synchronized void suspendSystemHotKeys(boolean suspend){
		if(suspend==hotKeysSuspended)return;
		if(!hotKeysChecked){hotKeysChecked=true;
			if(GMenu.isMacOSX()&&!"false".equals(System.getProperty("hz68k.suspendhotkeys")))
				try{hotKeySuspend=Class.forName("NativeHotKeys").getMethod("suspend",boolean.class);}catch(Throwable t){hotKeySuspend=null;}}
		if(hotKeySuspend==null)return;
		try{if((Boolean)hotKeySuspend.invoke(null,suspend))hotKeysSuspended=suspend;}catch(Throwable t){hotKeySuspend=null;}
	}
	/** Debug aid (commands.txt `keymap <code> down|up`): presses or releases a key in the GetKeys map. */
	void setKeyState(int keyCode,boolean down){if(keyCode>=0&&keyCode<128)synchronized(keysDown){keysDown[keyCode]=down;}}

	static int macModifiers(int ex){
		int m=0;
		if((ex&InputEvent.BUTTON1_DOWN_MASK)==0)m|=0x80;
		if((ex&InputEvent.META_DOWN_MASK)!=0||(!GMenu.isMacOSX()&&(ex&InputEvent.CTRL_DOWN_MASK)!=0))m|=0x100;
		if((ex&InputEvent.SHIFT_DOWN_MASK)!=0)m|=0x200;
		if((ex&InputEvent.ALT_DOWN_MASK)!=0)m|=0x800;
		if((ex&InputEvent.CTRL_DOWN_MASK)!=0)m|=0x1000;
		return m;
	}

	// ------------------------------------------------------------ screen

	/**
	 * The screen programs see: the host display up to the largest screen of the time (the whole display when chosen), or a
	 * "game screen" - a classic size such as 640x480 (PCARD preference
	 * screenSize68k, {@code -Dhz68k.screen=WIDTHxHEIGHT} for scripts and tests) that games lay themselves out for. A game
	 * screen is shown magnified by the zoom setting ({@link #screenScale}) and centred on the display when it fits; window positions
	 * and mouse coordinates are translated between the two by {@link #hostX}/{@link #virtualX} and friends.
	 */
	static Rectangle screenBounds(){return screenBounds(false);}
	/**
	 * {@code external}: the screen a HyperCard external sees. That is HyperCard's screen, the display: its windows sit among
	 * HyperCard's at HyperCard's coordinates (the screenRect used to size backdrops and the card window used with
	 * SendBehind), so the game-screen preference (a smaller screen centred on the display) does not apply to it.
	 */
	static Rectangle screenBounds(boolean external){
		if(SCREEN_OVERRIDE!=null)return new Rectangle(SCREEN_OVERRIDE);
		Rectangle v=external?null:effectiveVirtualScreen();if(v!=null)return new Rectangle(v);
		// The display: its usable area (no macOS menu bar, no Dock) under a classic menu-bar band, so that a program
		// laying its windows out for screenBits.bounds or GrayRgn does not put them under the Dock or the menu bar.
		Rectangle usable=usableDisplayBounds();
		Rectangle display=new Rectangle(0,0,usable.width,usable.height+hiddenMenuBand());
		if(external){int pct=externalPercent();return pct==100?display:new Rectangle(0,0,DisplayZoom.unscale(display.width,pct),DisplayZoom.unscale(display.height,pct));}
		if(DISPLAY_SCREEN||wholeDisplayChosen())return display;
		// Unless a size was chosen, no larger than the largest screen of the time (the Apple 21" colour display for 68K programs,
		// the 21" Studio Display for PowerPC ones): programs size buffers and windows from the screen (Fractal Contours asks for
		// 14 MB on a 1920-pixel one), and none met a wider one.
		boolean ppc=runsPowerPc();
		return new Rectangle(0,0,Math.min(display.width,ppc?PPC_ERA_SCREEN_WIDTH:ERA_SCREEN_WIDTH),Math.min(display.height,ppc?PPC_ERA_SCREEN_HEIGHT:ERA_SCREEN_HEIGHT));
	}
	/** The largest screen of the 68K Macintosh era, the default limit of the screen programs see (see {@link #screenBounds}). */
	static final int ERA_SCREEN_WIDTH=1152,ERA_SCREEN_HEIGHT=870;
	/** The largest screen of the PowerPC era (the Apple Studio Display 21", Multiple Scan 21): the limit for PowerPC programs. */
	static final int PPC_ERA_SCREEN_WIDTH=1600,PPC_ERA_SCREEN_HEIGHT=1200;
	/** Whether the program on the current thread runs PowerPC code. */
	private static boolean runsPowerPc(){MacApp68k app=MacApp68k.forCurrentThread();return app!=null&&app.powerPC;}
	/** -Dhz68k.displayscreen=true: programs see the whole display, as before the era limit. */
	static final boolean DISPLAY_SCREEN=Boolean.getBoolean("hz68k.displayscreen");
	/** The screen size setting (preference screenSize68k, a program's own launcher setting) that shows programs the whole display. */
	static final String WHOLE_DISPLAY="display";
	/** The preference asks for the whole display (set by PCARD, like {@link #virtualScreen}). */
	static volatile boolean wholeDisplayPreferred;
	/** Whether the whole display was chosen for the program on the current thread: its own setting, else the preference. */
	static boolean wholeDisplayChosen(){
		MacApp68k app=MacApp68k.forCurrentThread();
		return app!=null&&app.screenSizeOverride!=null?WHOLE_DISPLAY.equals(app.screenSizeOverride):wholeDisplayPreferred;
	}
	/** The host display's own size. */
	static Rectangle displayBounds(){
		// headless (the tests that run programs without a display): the largest screen of the time. Without a game screen in the
		// preferences the screen size was asked of a toolkit that has none, and the program failed at launch.
		if(java.awt.GraphicsEnvironment.isHeadless())return new Rectangle(0,0,ERA_SCREEN_WIDTH,ERA_SCREEN_HEIGHT);
		Rectangle r=new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
		try{Rectangle b=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();if(b.width>0)r=b;}catch(Exception ignored){}
		return r;
	}
	/** The part of the main display windows may occupy (without the macOS menu bar and the Dock), in display coordinates. */
	static Rectangle usableDisplayBounds(){
		try{Rectangle u=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();if(u.width>0&&u.height>0)return u;}catch(Exception ignored){}
		return displayBounds();
	}
	/** The usable area of the display showing (most of) a native window's frame, or null when the frame is on no display at all. */
	static Rectangle usableAreaAround(Rectangle frame){
		try{
			java.awt.GraphicsDevice best=null;long bestArea=0;
			for(java.awt.GraphicsDevice gd:java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
				Rectangle b=gd.getDefaultConfiguration().getBounds(),i=b.intersection(frame);
				if(i.isEmpty())continue;long area=(long)i.width*i.height;if(area>bestArea){bestArea=area;best=gd;}}
			if(best==null)return null;
			java.awt.GraphicsConfiguration gc=best.getDefaultConfiguration();Rectangle b=gc.getBounds();Insets si=Toolkit.getDefaultToolkit().getScreenInsets(gc);
			return new Rectangle(b.x+si.left,b.y+si.top,b.width-si.left-si.right,b.height-si.top-si.bottom);
		}catch(Exception e){return null;}
	}
	/** The game screen (PCARD preference; null = the display) and how it is magnified (a DisplayZoom choice: 1x, up to
	 *  125/150/200%, or 1x or 2x). */
	static volatile Rectangle virtualScreen;static volatile int screenScale=DisplayZoom.ONE;
	static Rectangle parseScreenSize(String value){return parseScreenOverride(value);}
	/** The game screen for the program on the current thread: its own launcher setting when it has one, else the preference. */
	static Rectangle effectiveVirtualScreen(){
		MacApp68k app=MacApp68k.forCurrentThread();
		return app!=null&&app.screenSizeOverride!=null?parseScreenOverride(app.screenSizeOverride):virtualScreen;
	}
	/** The zoom choice for the program on the current thread (its own setting, else the preference). */
	static int effectiveScreenScale(){
		MacApp68k app=MacApp68k.forCurrentThread();
		return app!=null&&app.screenScaleOverride!=null?app.screenScaleOverride:screenScale;
	}
	/** Magnification in percent of the screen programs see: only a game screen is ever magnified ({@code -Dhz68k.scale=N} with
	 *  -Dhz68k.screen). "Up to" choices take the largest 5% step that fits the usable display; with "1x or 2x" a screen that
	 *  would not fit at 2x is shown at 1x this time (the setting stays), and the launcher says so. */
	static int scaleFor(Rectangle screen){return scaleFor(screen,MacApp68k.forCurrentThread());}
	/** {@link #scaleFor(Rectangle)} for a given program (null: the preference alone). */
	static int scaleFor(Rectangle screen,MacApp68k app){
		if(SCREEN_OVERRIDE!=null)return 100*Math.max(1,Math.min(4,Integer.getInteger("hz68k.scale",1)));
		if((app!=null&&app.screenSizeOverride!=null?parseScreenOverride(app.screenSizeOverride):virtualScreen)==null)return 100;
		int choice=app!=null&&app.screenScaleOverride!=null?app.screenScaleOverride:screenScale;
		if(!DisplayZoom.valid(choice))choice=DisplayZoom.ONE;
		if(choice==DisplayZoom.ONE)return 100;
		Rectangle usable=usableDisplayBounds();
		int percent=DisplayZoom.percent(choice,DisplayZoom.fit(screen.width,screen.height,0,0,usable));
		if(choice==DisplayZoom.ONE_OR_TWO&&percent==100){
			String note=(app==null?"":"\""+app.name+"\": ")+"screen "+screen.width+"x"+screen.height+" at 2x does not fit the display; shown at 1x this time";
			System.err.println("[68K] "+note);
			GLauncher.note(app,2);
		}
		return percent;
	}
	/** A game screen is placed at the usable display area's top left instead of its centre (PCARD preference screenTopLeft68k). */
	static volatile boolean screenTopLeft;
	/** Whether the program's game screen goes to the top left: its own launcher setting, else the preference. */
	static boolean screenTopLeftFor(MacApp68k app){return app!=null&&app.screenTopLeftOverride!=null?app.screenTopLeftOverride:screenTopLeft;}
	/** Host position of the screen's origin: the display's usable area starts right under the classic menu-bar band; a game screen is
	 *  centred in the usable display area (or at its top left, {@link #screenTopLeftFor}) when it fits there, else it starts at the
	 *  display's origin as before. */
	static Point screenOriginFor(Rectangle screen,int percent){return screenOriginFor(screen,percent,MacApp68k.forCurrentThread());}
	static Point screenOriginFor(Rectangle screen,int percent,MacApp68k app){
		if(SCREEN_OVERRIDE==null&&(app!=null&&app.screenSizeOverride!=null?parseScreenOverride(app.screenSizeOverride):virtualScreen)==null)return displayOrigin();
		Rectangle usable;try{usable=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();}catch(Exception e){return new Point(0,0);}
		int w=DisplayZoom.scale(screen.width,percent),h=DisplayZoom.scale(screen.height,percent);
		if(w>usable.width||h>usable.height)return new Point(0,0);
		if(screenTopLeftFor(app))return new Point(usable.x,usable.y);
		return new Point(usable.x+(usable.width-w)/2,usable.y+(usable.height-h)/2);
	}
	/** The host of a HyperCard external: its windows live among HyperCard's own native windows, which it does not stack. */
	final boolean external;
	/** An external's host whose screen is the classic one round the card window ({@link ExternalScreenArea}), not the display. */
	final boolean limitedExternal;
	/** The size of the screen an external sees when it is not the display ({@link #limitedExternal}), else null. */
	Rectangle externalScreen(){return limitedExternal?new Rectangle(0,0,screenWidth,screenHeight):null;}
	/** The externals' shared screen moved ({@link ExternalScreenArea}): this host's windows go along (EDT). */
	void moveExternalScreenTo(Point o){if(!disposed)moveScreenTo(o);}
	/** Host position of the display-sized screen's origin: the usable area starts right under the classic menu-bar band. */
	static Point displayOrigin(){Rectangle usable=usableDisplayBounds();return new Point(usable.x,usable.y-hiddenMenuBand());}
	/** How much of the classic menu-bar band lies above the usable display: on macOS the band sits under the screen menu bar,
	 *  which shows the program's menus. Elsewhere nothing would show them there - the band is on the display, and a window
	 *  covering the screen carries its menu bar over it ({@link #overlaysMenuBar}): F1GP's full-screen window had its menus
	 *  and the top of its picture above the display. */
	static int hiddenMenuBand(){return GMenu.isMacOSX()?MENU_BAR_HEIGHT:0;}
	/** This host's screen geometry, fixed at launch (the program's idea of the screen must not move under it). */
	volatile int scPct;volatile Point origin;
	/** The size of the screen the program sees (0 when headless). */
	int screenWidth,screenHeight;
	/** The magnification as a factor (1, 1.25, 2...). */
	double scale(){return scPct/100.0;}
	/** A screen length in host pixels (rounded half up, exact: the inverse of {@link #virtualLen}). */
	int hostLen(int v){return DisplayZoom.scale(v,scPct);}
	/** The screen coordinate whose host pixels contain host offset {@code v}. */
	int virtualLen(int v){return DisplayZoom.unscale(v,scPct);}
	/** Nearest neighbour when the magnification is whole on the device (pixel-perfect), else bilinear. */
	Object interpolation(Graphics2D g){return DisplayZoom.interpolation(scPct%100==0?scale():scale()*g.getTransform().getScaleX());}
	/** The screen the program sees in host coordinates (null when headless). */
	volatile Rectangle screenRect;
	int hostX(int x){return origin.x+hostLen(x);}
	int hostY(int y){return origin.y+hostLen(y);}
	int virtualX(int x){return virtualLen(x-origin.x);}
	int virtualY(int y){return virtualLen(y-origin.y);}
	Point toVirtual(Point host){return new Point(virtualX(host.x),virtualY(host.y));}
	/** Some window is shown away from the program's position for it ({@link HostWindow#dispX}). */
	private volatile boolean anyDisplaced;
	/** A host screen point as the program's global point: over a displaced window, shifted back into that window. */
	Point toGuest(Point host){
		if(anyTitleBand)host=pastTitleBand(host);
		Point p=toVirtual(host);if(!anyDisplaced)return p;
		for(HostWindow hw:windowsFrontToBack()){if(hw.canvas==null||!hw.canvas.isShowing())continue;
			try{Point c=hw.canvas.getLocationOnScreen();if(host.x>=c.x&&host.y>=c.y&&host.x<c.x+hw.canvas.getWidth()&&host.y<c.y+hw.canvas.getHeight())return new Point(p.x-hw.dispX,p.y-hw.dispY);}catch(Exception ignored){}}
		return p;
	}
	/** A display rectangle as the program's global rectangle: its top-left through {@link #toGuest(Point)}, its size unzoomed. */
	Rectangle toGuestRect(Rectangle host){Point p=toGuest(new Point(host.x,host.y));return new Rectangle(p.x,p.y,virtualLen(host.width),virtualLen(host.height));}
	/** The host screen point showing a global point of the program (inside a displaced window: where that window is shown). */
	Point toHost(int gx,int gy){
		if(anyDisplaced)for(HostWindow hw:windowsFrontToBack())if(hw.visible&&gx>=hw.contentLeft&&gy>=hw.contentTop&&gx<hw.contentLeft+hw.width&&gy<hw.contentTop+hw.height)return new Point(hostX(gx+hw.dispX),hostY(gy+hw.dispY));
		return new Point(hostX(gx),hostY(gy));
	}
	/** The window the last press came from when it was at this global point and windows are shown displaced, else 0: two
	 *  windows apart on the display may overlap where the program sees them ({@link #placeForProgram}), and FindWindow must
	 *  answer the one clicked. */
	int pressedWindowAt(int h,int v){HostWindow hw=lastPressWindow;Point p=lastPressPoint;return anyDisplaced&&hw!=null&&p!=null&&p.x==h&&p.y==v?hw.record:0;}
	/** The program's global position of a canvas's top-left corner. */
	private Point canvasOrigin(HostWindow hw){Point p=toVirtual(hw.canvas.getLocationOnScreen());return new Point(p.x-hw.dispX,p.y-hw.dispY);}

	private static final Rectangle SCREEN_OVERRIDE=parseScreenOverride(System.getProperty("hz68k.screen"));
	static Rectangle parseScreenOverride(String value){
		if(value==null)return null;String[] p=value.trim().toLowerCase().split("x");
		try{if(p.length==2){int w=Integer.parseInt(p[0].trim()),h=Integer.parseInt(p[1].trim());if(w>0&&h>0)return new Rectangle(0,0,w,h);}}catch(NumberFormatException ignored){}
		return null;
	}

	/**
	 * Where a synthesized click (a wheel turn made into scroll-arrow clicks) is, while the program handles it: programs that
	 * track a click themselves ask GetMouse where the pointer is (Resorcerer's lists) - the real pointer, elsewhere, made the
	 * wheel toggle the "mark" of whatever row it was over. Set when the mouse-down is delivered, dropped with its mouse-up
	 * or half a second later (a program that flushes the up).
	 */
	private volatile Point syntheticPointer;private volatile long syntheticPointerUntil;
	void holdSyntheticPointer(Point p){syntheticPointerUntil=System.nanoTime()+500_000_000L;syntheticPointer=p;}
	/** Current global mouse position, polled without events. */
	Point mouseLocation(){
		Point synthetic=syntheticPointer;if(synthetic!=null&&System.nanoTime()<syntheticPointerUntil)return new Point(synthetic);
		if(headless)return new Point(GUI.mouseH,GUI.mouseV);
		if(injectedMouse!=null)return new Point(injectedMouse);
		return warped(pointerLocation());
	}
	/** The pointer in the program's global coordinates NOT pinned to its screen: for the windows the Window Manager drags for the
	 *  user ({@link #userDragArea}), which may go beyond a game screen as native frames do. */
	Point unpinnedMouseLocation(){
		if(headless||injectedMouse!=null||screenRect==null)return mouseLocation();
		try{PointerInfo info=MouseInfo.getPointerInfo();if(info!=null)return toVirtual(anyTitleBand?pastTitleBand(info.getLocation()):info.getLocation());}catch(Exception ignored){}
		return mouseLocation();
	}
	private Point pointerLocation(){
		try{PointerInfo info=MouseInfo.getPointerInfo();if(info!=null){Point host=info.getLocation();Point p=pinToScreen(host,toGuest(host));mouseGlobalH=p.x;mouseGlobalV=p.y;return p;}}catch(Exception ignored){}
		return new Point(mouseGlobalH,mouseGlobalV);
	}
	/** Pins a polled pointer position into the screen the program sees, like the ROM's CrsrPin: the host pointer can leave a game
	 *  screen (or sit in the Dock), a Mac pointer never leaves its screens - Altair walks the device list for the one under the
	 *  mouse at start-up and quits with "a monitor ... could not be found" when none contains it.
	 *  The program's own windows extend that screen: the user can drag one partly beyond it (the era screen is smaller than the
	 *  display), and its part out there is still clicked - HyperCard tracks a button press with StillDown/GetMouse, and a pointer
	 *  pinned to the screen's edge was outside the button, so buttons near the display's edge did nothing. While the button is
	 *  held after a press in such a window, the pointer may also roam the area the screen and that window span. */
	private Point pinToScreen(Point host,Point p){
		if(screenRect==null)return p;
		int w=screenWidth,h=screenHeight;
		if(p.x>=0&&p.y>=0&&p.x<w&&p.y<h)return p;
		for(HostWindow hw:windowsFrontToBack()){java.awt.Window win=hw.window;if(hw.visible&&win!=null&&win.isShowing()&&win.getBounds().contains(host))return p;}
		HostWindow pressed=lastPressWindow;java.awt.Window pw=pressed!=null?pressed.window:null;
		if(mouseDown&&pw!=null&&pw.isShowing()){
			Rectangle area=screenRect.union(pw.getBounds());
			Point c=new Point(Math.max(area.x,Math.min(area.x+area.width-1,host.x)),Math.max(area.y,Math.min(area.y+area.height-1,host.y)));
			if(!c.equals(host))p=toGuest(c);
			return p;
		}
		return new Point(Math.max(0,Math.min(w-1,p.x)),Math.max(0,Math.min(h-1,p.y)));
	}
	/**
	 * Mouse warping: a program that stores a new RawMouse and calls the cursor task (JCrsrTask, $8EE) expects the
	 * pointer to jump there (games keep it centred and read the deltas). The host pointer is moved with Robot when
	 * the OS allows it; whatever distance remains becomes an offset between the host pointer and the emulated mouse.
	 */
	private volatile int warpH,warpV;
	private java.awt.Robot robot;private boolean robotFailed;
	private Point warped(Point p){return warpH==0&&warpV==0?p:new Point(p.x+warpH,p.y+warpV);}
	void warpMouse(int h,int v){
		warpSerial++;
		try{
		if(headless){GUI.mouseH=h;GUI.mouseV=v;return;}
		if(injectedMouse!=null){injectedMouse=new Point(h,v);return;}
		if(!robotFailed)try{if(robot==null)robot=new java.awt.Robot();Point hp=toHost(h,v);robot.mouseMove(hp.x,hp.y);}catch(Exception e){robotFailed=true;}
		Point actual=pointerLocation();warpH=h-actual.x;warpV=v-actual.y;
		}finally{warpSerial++;}
	}
	/** Counts the program's pointer warps (twice each: before and after the move), so a reader of pointer movement can skip the jump. */
	private volatile int warpSerial;
	int warpSerial(){return warpSerial;}
	/** The pointer's position as the program's global point without the screen pin and the warp offset: its differences are the
	 *  movement of the hand (InputSprocket's mouse deltas), also while the program re-centres the pointer or the pointer is off the screen. */
	Point rawPointerLocation(){
		if(headless)return new Point(GUI.mouseH,GUI.mouseV);
		if(injectedMouse!=null)return new Point(injectedMouse);
		try{PointerInfo info=MouseInfo.getPointerInfo();if(info!=null)return toGuest(info.getLocation());}catch(Exception ignored){}
		return new Point(mouseGlobalH,mouseGlobalV);
	}

	// ------------------------------------------------------------ windows

	/** Creates a BufferedImage that aliases a 32-bit PixMap stored in VM memory. */
	BufferedImage imageOver(int base,int width,int height,int rowBytes){return imageOver(base,width,height,rowBytes,false);}
	/** {@code alpha}: the pad byte is the image's alpha - for a drawing that stores alpha itself (a graphics importer's srcCopy
	 *  of a PNG into a 32-bit GWorld keeps the PNG's alpha there, as QuickTime does); never for drawing that blends. */
	BufferedImage imageOver(int base,int width,int height,int rowBytes,boolean alpha){
		if(width<=0||height<=0||base<=0)return null;
		// A PixMap the program built itself may hold fewer bytes a row than its own bounds need (rowBytes arithmetic that
		// wrapped a word, see QuickDraw68k.SCREEN_WIDTH_HEADROOM). Java2D cannot alias such a buffer - asking it to threw
		// out of the trap and stopped the program - so the caller falls back to QuickDraw, which reads and writes those
		// rows through the bounds-checked memory and merely draws what the record describes.
		if((long)width*4>rowBytes)return null;
		byte[] raw=memory.raw();
		if(base+rowBytes*(long)height>raw.length)return null;
		DataBufferByte buffer=new DataBufferByte(raw,rowBytes*height,base);
		// Pixels are stored as xRGB. The pad byte is not alpha: QuickDraw writes 0 there (programs may keep their own marks in
		// it, see QuickDraw68k.Bitmap.pixelValue), so Java2D sees opaque RGB, blends over it and leaves the byte alone.
		// (A plain ComponentSampleModel: an interleaved raster of 3 bands 4 bytes apart cannot make a Graphics - "Incorrect pixel stride".)
		if(alpha){WritableRaster raster=Raster.createInterleavedRaster(buffer,width,height,rowBytes,4,new int[]{1,2,3,0},new Point(0,0));
			return new BufferedImage(new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),new int[]{8,8,8,8},true,false,java.awt.Transparency.TRANSLUCENT,DataBuffer.TYPE_BYTE),raster,false,null);}
		WritableRaster raster=Raster.createWritableRaster(new java.awt.image.ComponentSampleModel(DataBuffer.TYPE_BYTE,width,height,4,rowBytes,new int[]{1,2,3}),buffer,new Point(0,0));
		ComponentColorModel model=new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),new int[]{8,8,8},false,false,java.awt.Transparency.OPAQUE,DataBuffer.TYPE_BYTE);
		return new BufferedImage(model,raster,false,null);
	}

	/** Windows the program creates visible, which the Window Manager shows right after creating them (see {@link HostWindow#showPending}). */
	private final java.util.Set<Integer> showSoon=java.util.concurrent.ConcurrentHashMap.newKeySet();
	void willShow(int record){showSoon.add(record);}
	HostWindow createWindow(final int record,final int left,final int top,final int width,final int height,final String title,final int procId,final boolean visible,final boolean goAway,final int pixelBase,final int rowBytes,final int depth){
		return createWindow(record,left,top,width,height,title,procId,visible,goAway,pixelBase,rowBytes,depth,false);
	}
	/** {@code customFrame}: left/top/width/height are the structure region a custom WDEF draws into the buffer, shown as a bare window. */
	HostWindow createWindow(final int record,final int left,final int top,final int width,final int height,final String title,final int procId,final boolean visible,final boolean goAway,final int pixelBase,final int rowBytes,final int depth,final boolean customFrame){
		final HostWindow hw=new HostWindow(record);hw.showPending=visible||showSoon.remove(record);
		if(headless){hw.procId=procId;hw.title=title;hw.contentLeft=left;hw.contentTop=top;hw.width=width;hw.height=height;hw.decorated=true;hw.image=depth==32?imageOver(pixelBase,width,height,rowBytes):null;hw.imageDepth=depth;hw.imageBase=pixelBase;hw.imageWidth=width;hw.imageHeight=height;hw.imageRowBytes=rowBytes;synchronized(windows){windows.put(record,hw);order.add(0,hw);}return hw;}
		hw.procId=procId;hw.title=title;hw.contentLeft=left;hw.contentTop=top;hw.width=width;hw.height=height;hw.closable=goAway;
		int wdef=procId>>>4;int kind=procId&15;
		if(HOST_TRACE)System.err.println("[host] createWindow $"+Integer.toHexString(record)+" procID "+procId+" '"+title+"'");
		// documentProc 0, dBoxProc 1, plainDBox 2, altDBoxProc 3, noGrowDocProc 4, movableDBoxProc 5, zoomDocProc 8, zoomNoGrow 12, rDocProc 16
		hw.customFrame=customFrame;
		hw.backdrop=!customFrame&&coversScreen(left,top,width,height);
		hw.decorated=!customFrame&&!hw.backdrop&&!(wdef==0&&(kind==1||kind==2||kind==3));
		// A real window can only be sized by a program that handles inGrow, and such a program calls DrawGrowIcon; until it
		// does the host frame is fixed, so a program that ignores grow hits cannot be sized or zoomed natively.
		hw.growable=!customFrame&&wdef==0&&(kind==0||kind==8);hw.resizable=false;
		// Appearance Manager windows (procID 1024-1031, WDEF 64) carry their grow box in the frame and never call DrawGrowIcon: the grow variants (odd kinds) are sizable from the start (Classilla's browser windows are 1031)
		if(!customFrame&&wdef==64&&kind<8&&(kind&1)!=0){hw.growable=true;hw.resizable=true;}
		hw.image=depth==32?imageOver(pixelBase,width,height,rowBytes):null;hw.imageDepth=depth;hw.imageBase=pixelBase;hw.imageWidth=width;hw.imageHeight=height;hw.imageRowBytes=rowBytes;
		memory.trackBuffer(pixelBase,rowBytes*height);
		synchronized(windows){windows.put(record,hw);order.add(0,hw);}
		runOnEdt(new Runnable(){@Override public void run(){buildWindow(hw);if(visible)showWindow(record);}},true);
		return hw;
	}

	/** True when a window's content covers the whole screen the program sees, its top at most the menu bar's height down. */
	private boolean coversScreen(int left,int top,int width,int height){
		if(screenRect==null)return false;int w=screenWidth,h=screenHeight;
		return left<=0&&top<=MENU_BAR_HEIGHT&&left+width>=w&&top+height>=h-MENU_BAR_HEIGHT;
	}

	private void buildWindow(final HostWindow hw){
		hw.canvas=new Canvas(hw);
		boolean dialogKind=(hw.procId&15)==1||(hw.procId&15)==2||(hw.procId&15)==3||(hw.procId&15)==5;
		if(hw.decorated&&!dialogKind){
			JFrame frame=new JFrame(hw.title);frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			frame.setResizable(hw.resizable);frame.setContentPane(hw.canvas);hw.frame=frame;hw.window=frame;
			installMenuBarAtBuild(hw);
		}else if(!menuBar.isEmpty()||hw.backdrop){
			// Dialog-kind windows of an application with a menu bar (games run in a plainDBox) are frames as well:
			// only a JFrame's menu bar reaches the macOS screen menu bar, and the Mac menu bar stays up over dialogs.
			// A backdrop is one from the start: MacPaint opens its desk before SetMenuBar, and a dialog rebuilt as a frame at
			// its first activation swallowed the click that activated it (the first tool picked did not take).
			JFrame frame=new JFrame(hw.title);frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			if(!hw.decorated)frame.setUndecorated(true);
			frame.setResizable(hw.resizable);frame.setContentPane(hw.canvas);hw.frame=frame;hw.window=frame;
			installMenuBarAtBuild(hw);
		}else{
			JDialog dialog=new JDialog((JFrame)null,hw.title,false);dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
			if(!hw.decorated){dialog.setUndecorated(true);}
			dialog.setResizable(hw.resizable);dialog.setContentPane(hw.canvas);hw.dialog=dialog;hw.window=dialog;
		}
		hw.canvas.setPreferredSize(new Dimension(hw.width,hw.height));
		if(hw.decorated)applyTitleButtons(hw);
		// A frame a custom WDEF draws is all the program's: no macOS drop shadow, whose rim showed as a grey outline around
		// Bryce 2's dialogs (they draw their own shadow over the screen pixels the window took up). Read when the peer is made.
		if(hw.customFrame&&hw.window instanceof javax.swing.RootPaneContainer)((javax.swing.RootPaneContainer)hw.window).getRootPane().putClientProperty("Window.shadow",Boolean.FALSE);
		if(hw.customFrame&&!GMenu.isMacOSX()&&hw.window instanceof javax.swing.RootPaneContainer){ // its title rows may go above the in-window menu bar
			javax.swing.JRootPane root=((javax.swing.RootPaneContainer)hw.window).getRootPane();
			if(root.getLayout() instanceof java.awt.LayoutManager2)root.setLayout(new TitleBandLayout(hw,(java.awt.LayoutManager2)root.getLayout()));}
		updateScreenCut(hw);
		hw.window.pack();
		if(applicationIcon!=null)hw.window.setIconImage(applicationIcon);
		placeWindow(hw);
		if(needsShape(hw))applyShape(hw);
		installListeners(hw);
		hw.window.setFocusableWindowState(!hw.windoid);
		applySystemWindow(hw); // a window rebuilt (dialog to frame) stays a system window
	}

	/** A window without a close box (goAwayFlag false, or a movable modal dialog) gets none on the host title bar either.
	 *  macOS AWT reads these root-pane properties when it makes the native window; with close, minimize and zoom all off
	 *  it hides the three title-bar buttons altogether (a Mac window never has a minimize button, and a zoom box only
	 *  with a zoomDocProc). Other platforms ignore them. */
	private static void applyTitleButtons(HostWindow hw){
		int wdef=hw.procId>>>4,kind=hw.procId&15;
		boolean closeBox=hw.closable&&!(wdef==0&&kind==5);if(closeBox)return;
		JRootPane root=hw.frame!=null?hw.frame.getRootPane():hw.dialog.getRootPane();
		root.putClientProperty("Window.closeable",Boolean.FALSE);
		if(!(wdef==0&&(kind&8)!=0)){root.putClientProperty("Window.minimizable",Boolean.FALSE);root.putClientProperty("Window.zoomable",Boolean.FALSE);}
	}
	/** The program drew a grow icon (DrawGrowIcon): it handles inGrow, so its document window may be sized natively from now on. */
	void setWindowResizable(final int record,final boolean resizable){
		final HostWindow hw;synchronized(windows){hw=windows.get(record);}
		if(hw==null||!hw.growable||hw.resizable==resizable)return;
		hw.resizable=resizable;if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){if(hw.frame!=null)hw.frame.setResizable(resizable);else if(hw.dialog!=null)hw.dialog.setResizable(resizable);}},false);
	}
	/**
	 * The part of a backdrop on an external's screen round the card window ({@link #limitedExternal}) when it reaches past that
	 * screen's right or bottom edge, host pixels from the content's top-left; else null. A Mac shows nothing beyond its screen,
	 * and this screen is not the display: UxBack sizes its cover by HyperCard's "the screenRect" (the display), and its grey went
	 * on past the frame down to the display's bottom-right corner (Saint Nicholas Society's "hide the desktop"). The same for a
	 * program's own screen: Amorphium's 2048x2048 backdrop on its 800x600 screen covered the display far past the screen.
	 */
	private Dimension screenCut(HostWindow hw){
		if(!hw.backdrop||screenRect==null)return null;
		int w=screenRect.x+screenRect.width-hostX(hw.contentLeft+hw.dispX),h=screenRect.y+screenRect.height-hostY(hw.contentTop+hw.dispY);
		if(w>=hostLen(hw.width)&&h>=hostLen(hw.height))return null;
		return new Dimension(Math.max(1,w),Math.max(1,h));
	}
	private void updateScreenCut(HostWindow hw){hw.screenCut=screenCut(hw);}
	/** pack() that also works on a shown non-resizable window: macOS pins such a window's native min/max size to its current size. */
	private void packWindow(HostWindow hw){
		boolean resizable=hw.frame!=null?hw.frame.isResizable():hw.dialog!=null&&hw.dialog.isResizable();
		if(!resizable){if(hw.frame!=null)hw.frame.setResizable(true);else if(hw.dialog!=null)hw.dialog.setResizable(true);}
		updateScreenCut(hw);
		// A window larger than the usable screen (a full-screen game on a display with a menu bar) is refused by macOS:
		// show as much of the port as fits, from its top-left corner.
		Dimension wanted=hw.canvas.getPreferredSize();java.awt.Rectangle usable=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();Insets in=hw.window.getInsets();
		int maxW=usable.width-in.left-in.right,maxH=usable.height-in.top-in.bottom;
		if(hw.backdrop&&screenRect!=null){maxW=Math.max(maxW,screenRect.x+screenRect.width-hostX(hw.contentLeft));maxH=Math.max(maxH,screenRect.y+screenRect.height-hostY(hw.contentTop));} // bare, it may reach under the menu bar and down to the screen's bottom edge
		if(wanted.width>maxW||wanted.height>maxH){hw.canvas.setPreferredSize(new Dimension(Math.min(wanted.width,maxW),Math.min(wanted.height,maxH)));if(hostX(hw.contentLeft)<usable.x)hw.contentLeft=virtualX(usable.x);if(hostY(hw.contentTop)<usable.y)hw.contentTop=virtualY(usable.y);}
		hw.canvas.invalidate();hw.window.invalidate(); // Swing caches the frame's preferred size from the last pack; the canvas reports the new port size
		hw.window.pack();
		if(hw.window.isShowing()){Dimension want=hw.window.getPreferredSize();if(!hw.window.getSize().equals(want)){hw.window.setSize(want);hw.window.validate();}}
		if(!resizable){if(hw.frame!=null)hw.frame.setResizable(false);else if(hw.dialog!=null)hw.dialog.setResizable(false);}
		if(hw.shape==null&&needsShape(hw))applyShape(hw); // the background outline of a bare window follows its new size
	}
	private void placeWindow(HostWindow hw){
		Insets in=hw.window.getInsets();
		hw.ignoreMoves=true;
		// On Windows (and other platforms without a screen menu bar), JFrame's
		// JMenuBar is inside the frame.  contentLeft/contentTop are the classic
		// port origin, so move the outer frame up by the menu height to keep the
		// canvas at that origin.  Without this, mouse events read one menu-bar
		// height below the point the user clicked. The height is where the root
		// pane actually laid the content out (validated first: the bar may have
		// just been installed), not the bar's preferred height - on macOS the bar
		// lives in the screen menu bar and takes no room in the frame at all.
		int menuHeight=hw.frame!=null?menuBarHeight(hw):0;
		if(hw.userPlaced&&(hw.dispX!=0||hw.dispY!=0)){ // the user's place beyond the screen stays; a new size may change the program's
			if(placeForProgram(hw,hw.contentLeft+hw.dispX,hw.contentTop+hw.dispY))listener.postWindowPlaced(hw.record,hw.contentLeft,hw.contentTop);}
		else{hw.dispX=hw.dispY=0;if(hw.window.isVisible()&&!hw.backdrop&&!hw.insisted)keepOnDisplay(hw,in,menuHeight);}
		hw.window.setLocation(hostX(hw.contentLeft+hw.dispX)-in.left,hostY(hw.contentTop+hw.dispY)-in.top-menuHeight);
		if(!java.util.Objects.equals(screenCut(hw),hw.screenCut))packWindow(hw); // a backdrop moved over its screen's edge shows another part
		if(hw.overlayBar!=null)layoutOverlayBar(hw);
		if(HOST_TRACE)System.err.println("[host] placeWindow $"+Integer.toHexString(hw.record)+" content "+hw.contentLeft+","+hw.contentTop+" "+hw.width+"x"+hw.height+(hw.backdrop?" backdrop":"")+(hw.decorated?"":" bare")+" insets "+in.top+"/"+in.left+" menu "+menuHeight+" -> frame "+hw.window.getBounds()+" (screen "+screenRect+", origin "+origin+", "+scPct+"%)");
		hw.ignoreMoves=false;
	}
	/** A window shown partly beyond the screen the program sees (a position saved on a larger screen, a dialog laid out for a
	 *  taller one, a title bar under the macOS menu bar, a game screen smaller than the program assumed) is moved onto it,
	 *  native frame included: for the program the Window Manager moved the window, and it learns the new position as it
	 *  would a drag. The area is the screen's part of the usable display (no macOS menu bar or Dock). A bare window entirely
	 *  off the display stays there (programs park windows out of sight on purpose) unless it is just beyond the screen's right or
	 *  bottom edge ({@link #savedOnLargerScreen}); a titled one is brought onto the screen,
	 *  since macOS would drag part of its title bar onto the display anyway. One larger than the area keeps its top-left corner. */
	private void keepOnDisplay(HostWindow hw,Insets in,int menuHeight){
		int x=hostX(hw.contentLeft)-in.left,y=hostY(hw.contentTop)-in.top-menuHeight;
		// a bare frame's size is the one this host set (the AWT size can still be the previous one until the resize lands: Excel
		// 2.2's formula bar measured 803 pixels wide instead of 666 and was moved 135 pixels to the left)
		Dimension size=hw.decorated?hw.window.getSize():new Dimension(hostLen(hw.width),hostLen(hw.height));if(size.width<=0||size.height<=0)return;
		Rectangle usable=usableAreaAround(new Rectangle(x,y,size.width,size.height));
		if(usable==null){if((!hw.decorated&&!savedOnLargerScreen(hw))||parkedFar(hw)||screenRect==null)return;usable=usableAreaAround(screenRect);if(usable==null)return;}
		// An external's classic screen round a small card window (limitedExternal) is no edge for its windows: on the Mac of the
		// stack's author the screen was larger. UxMovie opens its movie at the card's lower right (315,175 on a 416x240 card): the
		// 640x480 screen moved it up over the card's buttons; it stays where the program put it while the display shows it
		if(screenRect!=null&&!hw.userPlaced&&!limitedExternal){Rectangle onScreen=usable.intersection(screenRect);if(!onScreen.isEmpty())usable=onScreen;}
		int nx=x,ny=y;
		if(x+size.width>usable.x+usable.width)nx=usable.x+usable.width-size.width;
		if(y+size.height>usable.y+usable.height)ny=usable.y+usable.height-size.height;
		if(nx<usable.x)nx=usable.x;if(ny<usable.y)ny=usable.y;
		if(nx==x&&ny==y)return;
		// A frame the program draws itself (no host title bar) may overhang the display as on a real Mac while its content is
		// on it: Excel 2.2 lays its formula and status bars with their 1-pixel frames just off the screen edges, and moving them
		// for the program shifted its layout (once, measured with the size from before the frame was built, by 136 pixels).
		if(!hw.decorated){int slack=Math.max(3,hostLen(3)); // a frame line or a shadow, not a title bar out of reach
			if(usable.x-x<=slack&&usable.y-y<=slack&&x+size.width-(usable.x+usable.width)<=slack&&y+size.height-(usable.y+usable.height)<=slack)return;}
		int dx=virtualShift(nx-x),dy=virtualShift(ny-y);
		// Content the program laid over the menu bar on purpose, entirely on its screen (the "full-screen window" trick: title
		// bar out of sight above the top edge) stays where it is for the program, as on a real Mac - Chirac Attack moves its
		// window to 0,0 and hit-tests clicks in GLOBAL coordinates against its buttons. Only the native frame moves, and
		// points over it are shifted back. Other windows sticking out are moved for the program too, as after a drag.
		if(screenRect!=null&&hw.contentTop<MENU_BAR_HEIGHT&&hw.contentLeft>=0&&hw.contentTop>=0&&hw.contentLeft+hw.width<=screenWidth&&hw.contentTop+hw.height<=screenHeight){
			if(HOST_TRACE)System.err.println("[host] window $"+Integer.toHexString(hw.record)+" at "+hw.contentLeft+","+hw.contentTop+" shown "+dx+","+dy+" away (frame does not fit the usable display there)");
			hw.dispX=dx;hw.dispY=dy;anyDisplaced=true;return;}
		if(HOST_TRACE)System.err.println("[host] window $"+Integer.toHexString(hw.record)+" at "+hw.contentLeft+","+hw.contentTop+" sticks out of "+usable+": moved by "+dx+","+dy+" (frame "+x+","+y+" "+size.width+"x"+size.height+", insets "+in.top+"/"+in.bottom+", menu "+menuHeight+")");
		hw.keptFrom=new int[]{hw.contentLeft,hw.contentTop};
		hw.contentLeft+=dx;hw.contentTop+=dy;
		listener.postWindowPlaced(hw.record,hw.contentLeft,hw.contentTop);
	}
	/** Whether a window at {@code left},{@code top} (content, with a title bar's room above it) lies wholly beyond the program's screen. */
	private boolean offScreen(HostWindow hw,int left,int top){
		return screenWidth>0&&screenHeight>0&&!new Rectangle(left,top-TITLE_ROOM,hw.width,hw.height+TITLE_ROOM).intersects(new Rectangle(0,0,screenWidth,screenHeight));
	}
	private static final int TITLE_ROOM=20;
	/**
	 * Where the program sees a window the user put at {@code shownX},{@code shownY} (its content in virtual pixels): there, unless
	 * that lies beyond the program's screen - the display is larger than the era or game screen, a Mac's is not. Then the
	 * program's window stays at the nearest place on its screen and the native frame is shown {@link HostWindow#dispX} away.
	 * Programs only draw where their windows are on a screen: HyperCard intersects the card window with every GDevice's
	 * rect and draws only those parts, so a card dragged past the screen's right edge lost its part out there (and one
	 * entirely beyond it neither drew nor took clicks). Returns whether the program's position changed.
	 */
	private boolean placeForProgram(HostWindow hw,int shownX,int shownY){
		int[] at=programPlace(hw,shownX,shownY);int x=at[0],y=at[1];
		hw.dispX=shownX-x;hw.dispY=shownY-y;if(hw.dispX!=0||hw.dispY!=0)anyDisplaced=true;
		boolean changed=x!=hw.contentLeft||y!=hw.contentTop;hw.contentLeft=x;hw.contentTop=y;return changed;
	}
	/** Where the program keeps a window shown at {@code shownX},{@code shownY}: the same place, or the nearest on its screen. */
	private int[] programPlace(HostWindow hw,int shownX,int shownY){
		if(screenRect==null||hw.backdrop)return new int[]{shownX,shownY};
		return new int[]{onScreenAxis(shownX,hw.width,screenWidth),onScreenAxis(shownY,hw.height,screenHeight)};
	}
	/** {@link #programPlace(HostWindow,int,int)} for the Window Manager, which drags the frames drawn in windows itself. */
	int[] programPlace(int record,int shownX,int shownY){HostWindow hw=window(record);return hw==null?new int[]{shownX,shownY}:programPlace(hw,shownX,shownY);}
	/** How far from the program's position the window is shown, {h, v}. */
	int[] displacement(int record){HostWindow hw=window(record);return hw==null?new int[2]:new int[]{hw.dispX,hw.dispY};}
	/** A window's start on one axis moved the least to lie within 0..{@code screen} (one larger than the screen: to cover it). */
	private static int onScreenAxis(int at,int length,int screen){return length>=screen?Math.max(screen-length,Math.min(0,at)):Math.max(0,Math.min(screen-length,at));}
	/** A bare window off the display right of or below the screen, no farther than a big classic Mac screen reaches: a position the
	 *  program saved on a larger screen (Kingyo2 keeps its bowl's h/v in its preferences; from a 1x session it lay at 952,52, out
	 *  of sight on the 640x480 screen shown 2x) rather than one parked out of sight, which lies far away or at negative coordinates. */
	private static boolean savedOnLargerScreen(HostWindow hw){
		return hw.contentLeft>=0&&hw.contentTop>=0&&hw.contentLeft<SAVED_POSITION_REACH&&hw.contentTop<SAVED_POSITION_REACH;
	}
	private static final int SAVED_POSITION_REACH=2048;
	/** A window the program put out of sight on purpose, titled or not: at least {@link #SAVED_POSITION_REACH} away. Word 5.1
	 *  builds its dialogs at h+2048, shows them there and then moves them to where they belong, measured from where it put them;
	 *  brought onto the display (and told so), its Character, Find and Table dialogs came back at the screen's left edge. */
	private static boolean parkedFar(HostWindow hw){
		return hw.contentLeft>=SAVED_POSITION_REACH||hw.contentTop>=SAVED_POSITION_REACH||hw.contentLeft+hw.width<=-SAVED_POSITION_REACH||hw.contentTop+hw.height<=-SAVED_POSITION_REACH;
	}
	/** A host-pixel shift as whole virtual pixels, rounded away from zero (a magnified game screen moves in steps of {@link #scale}). */
	private int virtualShift(int hostDelta){int a=Math.abs(hostDelta),v=(int)((a*100L+scPct-1)/scPct);return hostDelta>=0?v:-v;}

	private double wheelAccumulator;

	// ------------------------------------------------------------ input methods (Japanese etc.)

	/** The host input method is composing: raw key events are its business until it commits. */
	private volatile boolean composing;
	private javax.swing.JWindow compositionWindow;private javax.swing.JLabel compositionLabel;
	private final InputMethodRequests inputMethodRequests=new InputMethodRequests(){
		@Override public Rectangle getTextLocation(TextHitInfo offset){int[] c=listener.textCaret();if(c!=null)return new Rectangle(c[0],c[1],1,c[2]);Point p=mouseLocation();return new Rectangle(p.x,p.y,1,16);}
		@Override public TextHitInfo getLocationOffset(int x,int y){return null;}
		@Override public int getInsertPositionOffset(){return 0;}
		@Override public AttributedCharacterIterator getCommittedText(int begin,int end,AttributedCharacterIterator.Attribute[] attributes){return new AttributedString("").getIterator();}
		@Override public int getCommittedTextLength(){return 0;}
		@Override public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes){return null;}
		@Override public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes){return new AttributedString("").getIterator();}
	};
	/** Types committed input-method text: each character becomes the key-down events a Japanese System would post
	 *  (one per byte of its two-byte code), with key-ups in between so none is mistaken for auto-repeat. */
	void postText(String text){
		int mods=modifierState|0x80;
		for(int i=0;i<text.length();i++){
			char c=text.charAt(i);
			byte[] bytes=c=='\n'?new byte[]{13}:c<0x80?new byte[]{(byte)c}:encoding.encodeReplacing(String.valueOf(c));
			for(byte b:bytes){listener.postKey(3,0,b&0xff,mods);listener.postKey(4,0,b&0xff,mods);}
		}
	}
	/** Debug aid (commands.txt `ime <recordHex> <composing>|- [committed]`): dispatches an InputMethodEvent as the host input method would. */
	void injectInputMethod(int record,final String composing,final String committed){
		HostWindow hw=window(record);if(hw==null||hw.canvas==null)return;final Canvas canvas=hw.canvas;
		runOnEdt(new Runnable(){@Override public void run(){
			AttributedString text=new AttributedString(committed+composing);
			TextHitInfo caret=TextHitInfo.leading(composing.length());
			canvas.dispatchEvent(new InputMethodEvent(canvas,InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,committed.length()+composing.length()==0?null:text.getIterator(),committed.length(),composing.isEmpty()?null:caret,composing.isEmpty()?null:caret));
		}},true);
	}
	/** Shows the text being composed at the program's insertion point (the program itself knows nothing of it yet). */
	private void showComposition(HostWindow hw,String text){
		composing=!text.isEmpty();
		if(text.isEmpty()){if(compositionWindow!=null)compositionWindow.setVisible(false);return;}
		if(compositionWindow==null||compositionWindow.getOwner()!=hw.window){
			if(compositionWindow!=null)compositionWindow.dispose();
			compositionWindow=new javax.swing.JWindow(hw.window);compositionWindow.setFocusableWindowState(false);
			compositionLabel=new javax.swing.JLabel();compositionLabel.setOpaque(true);compositionLabel.setBackground(Color.WHITE);compositionLabel.setForeground(Color.BLACK);
			compositionLabel.setBorder(javax.swing.BorderFactory.createLineBorder(Color.GRAY));
			compositionWindow.getContentPane().add(compositionLabel);
		}
		int[] c=listener.textCaret();int x,y,h;
		if(c!=null){Point at=toHost(c[0],c[1]);x=at.x;y=at.y;h=hostLen(c[2]);}else{Point p=hw.canvas.isShowing()?hw.canvas.getLocationOnScreen():new Point(0,0);x=p.x+4;y=p.y+4;h=16;}
		compositionLabel.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,Math.max(9,h-4)));
		compositionLabel.setText("<html><u>"+text.replace("&","&amp;").replace("<","&lt;")+"</u></html>");
		compositionWindow.pack();compositionWindow.setLocation(x,y+(h-compositionWindow.getHeight())/2);
		if(!compositionWindow.isVisible())compositionWindow.setVisible(true);
		if(Mac68kApi.TRACE)System.err.println("[68K] composition window '"+text+"' at "+compositionWindow.getX()+","+compositionWindow.getY()+" caret "+(c==null?"unknown":c[0]+","+c[1]+"x"+c[2]));
	}

	/** A mouse event on a window's canvas as the program's global point. */
	private Point inWindow(HostWindow hw,MouseEvent e){Point p=toVirtual(e.getLocationOnScreen());return new Point(p.x-hw.dispX,p.y-hw.dispY);}
	private void installListeners(final HostWindow hw){
		final Canvas canvas=hw.canvas;
		MouseAdapter mouse=new MouseAdapter(){
			@Override public void mousePressed(MouseEvent e){canvas.requestFocusInWindow();if(!SwingUtilities.isLeftMouseButton(e)&&!(SwingUtilities.isRightMouseButton(e)))return;
				if(fontPick){if(SwingUtilities.isLeftMouseButton(e)){fontPickRelease=true;endFontPick();MacApp68k app=owner;if(app!=null)app.fixGarbledText(hw.record,imageLen(e.getX()),imageLen(e.getY()));}return;} // the click "Fix Garbled Text…" waits for: never the program's
				if(injectedMouse!=null&&!isInjected(e))injectedMouse=null; // a real press ends a debug-injected pointer position
				mouseDown=true;Point p=warped(inWindow(hw,e));int mods=hostModifiers(e.getModifiersEx())&~0x80;if(SwingUtilities.isRightMouseButton(e))mods|=0x1000;
				if(provisional==hw)provisionalClicked=true; // the OS raised this background window for the click: the program decides whether it comes forward
				else if(hw.backdrop&&!isFrontmostVisible(hw)){ // raised by macOS without an activation (it cannot take the focus): the same, held till the release
					if(provisional!=null)settleProvisional();
					provisional=hw;provisionalClicked=true;if(provisionalTimer!=null){provisionalTimer.stop();provisionalTimer=null;}
					if(HOST_TRACE)System.err.println("[host] press on backdrop $"+Integer.toHexString(hw.record)+" behind the front window: held until the click is through");}
				lastPressWindow=hw;lastPressPoint=p;lastPressNanos=System.nanoTime();
				listener.postMouse(true,p.x,p.y,mods,e.getWhen());}
			@Override public void mouseReleased(MouseEvent e){if(fontPickRelease){fontPickRelease=false;return;}mouseDown=false;Point p=warped(inWindow(hw,e));listener.postMouse(false,p.x,p.y,hostModifiers(e.getModifiersEx())|0x80,e.getWhen());
				if(provisional==hw&&provisionalClicked)settleProvisional();}
			@Override public void mouseMoved(MouseEvent e){Point p=inWindow(hw,e);mouseGlobalH=p.x;mouseGlobalV=p.y;}
			@Override public void mouseDragged(MouseEvent e){Point p=inWindow(hw,e);mouseGlobalH=p.x;mouseGlobalV=p.y;}
			@Override public void mouseWheelMoved(MouseWheelEvent e){
				// classic programs know nothing of wheels: like the old wheel-driver extensions, the wheel clicks the arrows of the
				// nearest scroll bar (the Event Manager synthesizes the clicks); trackpads report fractional notches, so accumulate
				double units=e.getPreciseWheelRotation()*(e.getScrollType()==MouseWheelEvent.WHEEL_UNIT_SCROLL?e.getScrollAmount():3);
				if(units==0)return;wheelAccumulator+=units;int steps=(int)wheelAccumulator;if(steps==0)return;wheelAccumulator-=steps;
				Point p=inWindow(hw,e);listener.postWheel(p.x,p.y,steps,e.isShiftDown());
			}
		};
		canvas.addMouseListener(mouse);canvas.addMouseMotionListener(mouse);canvas.addMouseWheelListener(mouse);
		canvas.addKeyListener(keyAdapter());
		canvas.setFocusTraversalKeysEnabled(false);
		canvas.addInputMethodListener(new InputMethodListener(){
			@Override public void inputMethodTextChanged(InputMethodEvent e){
				AttributedCharacterIterator it=e.getText();int committed=e.getCommittedCharacterCount();
				StringBuilder done=new StringBuilder(),pending=new StringBuilder();
				if(it!=null){int i=0;for(char c=it.first();c!=AttributedCharacterIterator.DONE;c=it.next(),i++)(i<committed?done:pending).append(c);}
				if(Mac68kApi.TRACE)System.err.println("[68K] input method: committed '"+done+"' composing '"+pending+"'");
				int[] styles=new int[pending.length()];
				if(it!=null){int i=0;for(char c=it.first();c!=AttributedCharacterIterator.DONE;c=it.next(),i++){if(i<committed)continue;
					Object hl=it.getAttribute(java.awt.font.TextAttribute.INPUT_METHOD_HIGHLIGHT);int style=2;
					if(hl instanceof java.awt.im.InputMethodHighlight){java.awt.im.InputMethodHighlight h=(java.awt.im.InputMethodHighlight)hl;style=h.getState()==java.awt.im.InputMethodHighlight.CONVERTED_TEXT?(h.isSelected()?5:4):(h.isSelected()?3:2);}
					styles[i-committed]=style;}}
				TextHitInfo caret=e.getCaret();int caretIndex=caret==null?pending.length():Math.max(0,Math.min(pending.length(),caret.getInsertionIndex()));
				if(listener.postInputMethodText(done.toString(),pending.toString(),styles,caretIndex)){composing=pending.length()>0;if(compositionWindow!=null)compositionWindow.setVisible(false);e.consume();return;}
				if(done.length()>0)postText(done.toString());
				showComposition(hw,pending.toString());
				e.consume();
			}
			@Override public void caretPositionChanged(InputMethodEvent e){e.consume();}
		});
		hw.window.addWindowListener(new WindowAdapter(){
			@Override public void windowClosing(WindowEvent e){listener.postGoAway(hw.record);}
			@Override public void windowActivated(WindowEvent e){MacApp68k app=owner;if(app!=null&&!hw.systemWindow){MacApp68k.activeApplication=app;MacApp68k.finderFront=false;}setProgramActive(true);
				if(hw.underCard&&pointerOver(hw))hw.underCard=false; // clicked: in front of the card again, as HyperCard brings an external's window forward
				if(osActivated(hw)){canvas.requestFocusInWindow();updateMenuBarFor(hw);reinstallBareFrameBar(hw);}}
			@Override public void windowDeactivated(WindowEvent e){releaseAllKeys();showComposition(hw,"");
				// Focus moving to another of the program's windows is no deactivation yet: a click in a window behind reaches the
				// program as a mouseDown only, and the activate/deactivate pair comes when it selects that window (activateHost).
				// MacPaint restores its document from its backup buffer on every activate event: a palette click lost the last stroke.
				if(!isProgramWindow(e.getOppositeWindow())){deactivateHost(hw);setProgramActive(false);}}
		});
		installGeometryListeners(hw);
	}
	/** Keyboard events of a window canvas (or the screen stand-in) as Mac key-down/key-up events. */
	/** Shift, Command, Option, Control and Caps Lock alone post no key events on a real Mac (only the modifiers field and
	 *  GetKeys see them); ResEdit's type-select took a Shift press as an empty key and selected the first type. */
	private static boolean isModifierKey(KeyEvent e){
		switch(e.getKeyCode()){case KeyEvent.VK_SHIFT:case KeyEvent.VK_CONTROL:case KeyEvent.VK_ALT:case KeyEvent.VK_META:case KeyEvent.VK_CAPS_LOCK:case KeyEvent.VK_ALT_GRAPH:return true;default:return false;}
	}
	private KeyAdapter keyAdapter(){
		return new KeyAdapter(){
			private boolean ownModifierTyping;
			@Override public void keyPressed(KeyEvent e){
				if(fontPick&&e.getKeyCode()==KeyEvent.VK_ESCAPE){endFontPick();e.consume();return;}
				if(e.getKeyCode()==KeyEvent.VK_CONTROL&&controlKeysToProgram())suspendSystemHotKeys(true);
				if(isOwnModifierKey(e)){ownModifierTyping=true;return;} // its character (keyTyped) is not typed either
				ownModifierTyping=false;
				if(composing||isModifierKey(e))return;
				int mods=hostModifiers(e.getModifiersEx())|(mouseDown?0:0x80);
				int keyCode=macKeyCode(e.getKeyCode(),e.getKeyLocation());int ch=macChar(e);
				if(ch<0&&keyCode<0)return;
				if((mods&0x100)!=0&&(ch=='q'||ch=='Q')){if(commandQJustDelivered()){e.consume();return;}noteCommandQDelivered();}
				if((mods&0x100)!=0&&ch>0&&menuKeyFor(ch)!=null){int[] mi=menuKeyFor(ch);listener.postMenuKey(mi[0],mi[1],ch,mods);e.consume();return;}
				listener.postKey(3,keyCode<0?0:keyCode,ch<0?0:ch,mods);
				if(e.getKeyCode()==KeyEvent.VK_TAB)e.consume();
			}
			@Override public void keyReleased(KeyEvent e){
				if(e.getKeyCode()==KeyEvent.VK_CONTROL)suspendSystemHotKeys(false);
				if(composing||isModifierKey(e)||isOwnModifierKey(e))return;
				int keyCode=macKeyCode(e.getKeyCode(),e.getKeyLocation());int ch=macChar(e);
				listener.postKey(4,keyCode<0?0:keyCode,ch<0?0:ch,hostModifiers(e.getModifiersEx())|(mouseDown?0:0x80));
			}
			@Override public void keyTyped(KeyEvent e){
				if(ownModifierTyping){ownModifierTyping=false;return;}
				char c=e.getKeyChar();
				// Two-byte (IME) input arrives only as keyTyped characters.
				if(c>=0x80&&c!=KeyEvent.CHAR_UNDEFINED){byte[] bytes=MacAppHost.this.encoding.encodeReplacing(String.valueOf(c));int mods=hostModifiers(e.getModifiersEx())|0x80;
					for(byte b:bytes)listener.postKey(3,0,b&0xff,mods);}
			}
		};
	}
	private void installGeometryListeners(final HostWindow hw){
		final Canvas canvas=hw.canvas;
		hw.window.addComponentListener(new ComponentAdapter(){ // events of a window replaced by rebuildAsFrame carry its stale geometry
			@Override public void componentMoved(ComponentEvent e){if(hw.ignoreMoves||canvas!=hw.canvas||!hw.window.isShowing()||screenMoving()||!hw.decorated&&userDragging(hw))return;
				// macOS pulls a titled frame toward the display; a window the program parked far away stays there for the program
				if(parkedFar(hw)){if(HOST_TRACE)System.err.println("[host] componentMoved $"+Integer.toHexString(hw.record)+" ignored: parked at "+hw.contentLeft+","+hw.contentTop);return;}
				// shown where it already was (the host placed a displaced frame): the program's position stays
				Point p=toVirtual(hw.canvas.getLocationOnScreen());if(p.x!=hw.contentLeft+hw.dispX||p.y!=hw.contentTop+hw.dispY){placeForProgram(hw,p.x,p.y);hw.userPlaced=true;}
				listener.postWindowMoved(hw.record,hw.contentLeft,hw.contentTop);}
			@Override public void componentResized(ComponentEvent e){if(hw.ignoreMoves||canvas!=hw.canvas||hw.resizesPending>0||!hw.window.isShowing())return;int w=virtualLen(canvas.getWidth()),h=virtualLen(canvas.getHeight());if(w==hw.width&&h==hw.height||hw.screenCut!=null&&canvas.getSize().equals(canvas.getPreferredSize()))return;
				if(!hw.resizable){ // nobody but the program can size this window: macOS occasionally reports the pre-pack bounds after a SizeWindow, so restore them
					if(HOST_TRACE)System.err.println("[host] componentResized $"+Integer.toHexString(hw.record)+" "+w+"x"+h+" ignored, repacking to "+hw.width+"x"+hw.height);
					hw.ignoreMoves=true;try{canvas.setPreferredSize(new Dimension(hw.width,hw.height));packWindow(hw);placeWindow(hw);}finally{hw.ignoreMoves=false;}return;}
				if(HOST_TRACE)System.err.println("[host] componentResized $"+Integer.toHexString(hw.record)+" "+w+"x"+h+" (was "+hw.width+"x"+hw.height+")");listener.postWindowResized(hw.record,w,h);}
		});
		new DropTarget(canvas,new DropTargetAdapter(){
			/** The dragged files, once known (the host tells them at the drop, macOS also during the drag: NativeDragPasteboard). */
			private List<File> dragged;private boolean asked;
			@Override public void dragEnter(DropTargetDragEvent e){dragged=null;asked=false;dragOver(e);}
			@Override public void dragOver(DropTargetDragEvent e){
				if(!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){e.rejectDrag();return;}
				e.acceptDrag(DnDConstants.ACTION_COPY);
				if(!asked){dragged=draggedFiles(e);asked=true;}
				Point p=dragPoint(hw,e.getLocation());listener.postExternalDrag(DragManager68k.EXTERNAL_MOVE,p.x,p.y,dragged,modifierState|0x80);
			}
			@Override public void dragExit(java.awt.dnd.DropTargetEvent e){dragged=null;listener.postExternalDrag(DragManager68k.EXTERNAL_EXIT,0,0,null,modifierState|0x80);}
			@SuppressWarnings("unchecked")
			@Override public void drop(DropTargetDropEvent e){
				dragged=null;
				try{
					if(!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){e.rejectDrop();listener.postExternalDrag(DragManager68k.EXTERNAL_EXIT,0,0,null,modifierState|0x80);return;}
					e.acceptDrop(DnDConstants.ACTION_COPY);
					List<File> files=(List<File>)e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
					e.dropComplete(true);
					Point p=dragPoint(hw,e.getLocation());
					// a window with Drag Manager receive handlers (a dock like DragThing) gets the files as a drag from the Finder;
					// elsewhere they are opened as documents, as before
					if(!files.isEmpty())listener.postExternalDrag(DragManager68k.EXTERNAL_DROP,p.x,p.y,expandDroppedDocuments(files),modifierState|0x80);
					else listener.postExternalDrag(DragManager68k.EXTERNAL_EXIT,0,0,null,modifierState|0x80);
				}catch(Exception ex){e.dropComplete(false);listener.postExternalDrag(DragManager68k.EXTERNAL_EXIT,0,0,null,modifierState|0x80);}
			}
		});
	}
	/** Where a host drag is over a window, in the program's global coordinates; the pointer the program polls (GetMouse) follows it. */
	private Point dragPoint(HostWindow hw,Point inCanvas){
		Point screen=new Point(inCanvas);SwingUtilities.convertPointToScreen(screen,hw.canvas);
		Point v=toVirtual(screen);Point p=warped(new Point(v.x-hw.dispX,v.y-hw.dispY));holdSyntheticPointer(p);return p;
	}
	/** The files of a drag still going on, or null when the host tells them only at the drop. */
	@SuppressWarnings("unchecked")
	private static List<File> draggedFiles(DropTargetDragEvent e){
		if(GMenu.isMacOSX()){ // Java gives an outside drag's data only at the drop on macOS: the drag pasteboard tells it (ffm/)
			try{List<String> paths=(List<String>)Class.forName("NativeDragPasteboard").getMethod("files").invoke(null);
				if(paths==null)return null;List<File> r=new ArrayList<File>();for(String p:paths)r.add(new File(p));return r.isEmpty()?null:r;}
			catch(Throwable t){return null;}
		}
		try{List<File> files=(List<File>)e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);return files==null||files.isEmpty()?null:files;}
		catch(Throwable t){return null;}
	}
	/** ZIP files dropped on a hosted 68K application must be expanded before the
	 * Open Documents event is built; otherwise the guest sees the archive itself
	 * and applications commonly mistake it for an image/resource file. */
	private List<File> expandDroppedDocuments(List<File> files){
		for(File f:files)Sandbox.chosenByUser(applicationName,f,false); // dropped by the user: the program's to change, their folders to read
		List<File> expanded=new ArrayList<File>();
		for(File file:files){
			if(!ZipExpander.isZip(file)){expanded.add(file);continue;}
			try{ZipExpander.Result result=ZipExpander.expand(file);expanded.add(result.opened);}
			catch(IOException ex){
				if(HOST_TRACE)System.err.println("[host] cannot expand dropped ZIP "+file+": "+ex.getMessage());
				expanded.add(file);
			}
		}
		return expanded;
	}

	StackTextEncoding encoding=StackTextEncoding.ROMAN;

	HostWindow window(int record){synchronized(windows){return windows.get(record);}}
	/** Whether the window holds the activation (its activate event was the last one posted for it). */
	boolean isActiveWindow(int record){HostWindow hw=window(record);return hw!=null&&hw.activePosted;}
	/** Whether an external's window was sent behind HyperCard's card window (a backdrop), see {@link #placeBehind}. */
	boolean behindHyperCard(int record){HostWindow hw=window(record);return hw!=null&&(hw.behindHyperCard||hw.underCard);}
	/**
	 * (EDT) HyperCard's card window became the active window - the user clicked it, or the OS gave it the keyboard back: the OS
	 * put it in front of this external's document windows, and the host's frame order is what the screen shows. Those that
	 * overlap it are behind the card from now on ({@link HostWindow#underCard}) until they are activated again. Windoids,
	 * floating-layer and system windows stay over the card, and so does a modal dialog (HyperCard cannot come in front of it).
	 */
	void cardActivated(){
		java.awt.Window card=PCARD.pc;if(card==null||!card.isShowing())return;
		Rectangle bounds=card.getBounds();HostWindow modal=activeModalWindow();
		java.util.List<HostWindow> all;synchronized(windows){all=new ArrayList<HostWindow>(windows.values());}
		for(HostWindow hw:all){
			if(hw.window==null||!hw.visible||!hw.window.isVisible()||hw.windoid||hw.floatingLayer||hw.systemWindow||hw.behindHyperCard||hw==modal||hw.window.isAlwaysOnTop())continue;
			if(!hw.underCard&&hw.window.getBounds().intersects(bounds)){hw.underCard=true;if(HOST_TRACE)System.err.println("[host] $"+Integer.toHexString(hw.record)+" behind the card window (card activated)");}
		}
	}
	/** Stacks a window the card was brought in front of ({@link HostWindow#underCard}) right under the card window again, focusable. */
	private static void raiseUnderCard(HostWindow hw){
		hw.window.toFront();
		java.awt.Window card=PCARD.pc;if(card!=null&&card.isShowing())card.toFront();
	}
	private String knownRecords(){StringBuilder sb=new StringBuilder();synchronized(windows){for(Integer r:windows.keySet())sb.append(' ').append(Integer.toHexString(r));}return sb.toString().trim();}

	/** Debug aid: while set, {@link #mouseLocation} reports this point instead of the real pointer. */
	private volatile Point injectedMouse;
	/** Debug aid: synthesizes a click at window-local coordinates (used with -Dhz68k.snapshot's command file). */
	/** Debug aid (commands.txt `activate <recordHex>`): activates a window as a click on it would in the real Finder. */
	void injectActivate(int record){HostWindow hw=window(record);if(hw!=null)activateHost(hw);}
	/** Debug aid (commands.txt `deactivate <recordHex>`): the deactivate event another application coming to the front posts. */
	void injectDeactivate(int record){HostWindow hw=window(record);if(hw!=null){deactivateHost(hw);runOnEdt(new Runnable(){@Override public void run(){setProgramActive(false);}},true);}}
	/** Debug aid (commands.txt `movewin <recordHex> <dx> <dy>`): drags the native frame by that many host pixels, as the user moving it does. */
	void injectWindowMove(int record,final int dx,final int dy){
		final HostWindow hw=window(record);if(hw==null||hw.window==null)return;
		runOnEdt(new Runnable(){@Override public void run(){hw.window.setLocation(hw.window.getX()+dx,hw.window.getY()+dy);}},true);
	}
	/** Debug aid (commands.txt `osclick <recordHex> <x> <y>`): a real click on a background window as macOS delivers it - the window is
	 *  activated first, then the press and release reach its canvas (window-local coordinates). */
	void injectOsClick(int record,int x,int y){
		final HostWindow hw=window(record);if(hw==null||hw.canvas==null||!hw.canvas.isShowing())return;
		runOnEdt(new Runnable(){@Override public void run(){hw.window.toFront();setProgramActive(true);osActivated(hw);}},true);
		Point p=canvasOrigin(hw);int sx=p.x+x,sy=p.y+y;
		injectAwtMouse(MouseEvent.MOUSE_PRESSED,sx,sy);injectAwtMouse(MouseEvent.MOUSE_RELEASED,sx,sy);
	}

	/**
	 * Makes {@code hw} the active window: deactivate event for the previous one, activate for this one (each
	 * posted once; the AWT focus listener, ShowWindow/SelectWindow and the debug command all come through here).
	 */
	/** When this host last raised windows itself (restack): OS activations that follow are echoes, not user choices. */
	private volatile long programmaticRaise;
	/**
	 * A background window the OS just brought forward (the user clicked it), not yet adopted as the front window. On a
	 * Mac a click in a window behind others only reaches the program as a mouseDown; the window comes forward when the
	 * program calls SelectWindow, and stays behind when it does not (Woden's full-screen backdrop, whose clicks the
	 * program ignores). So the activation is held until the click has been through the program: SelectWindow settles it
	 * in favour of the window, the mouse-up without one restores the program's stacking order. An activation that no
	 * click follows (a title-bar click, which the program never sees; an app switch) stands after a short wait.
	 */
	private javax.swing.Timer settleCheck;
	/**
	 * macOS can order a clicked window front once more after the host put the stacking back (at the mouse-up, which a window
	 * disabled under a modal dialog never reports: MacPaint's pattern editor, opened by a double-click on its desk, went
	 * under the desk or the document clicked meanwhile), so the order is restored again a moment later (EDT).
	 */
	private void recheckOrder(){
		if(settleCheck!=null)settleCheck.stop();
		settleCheck=new javax.swing.Timer(250,new ActionListener(){@Override public void actionPerformed(ActionEvent e){settleCheck=null;if(provisional==null&&!disposed)restack();}});
		settleCheck.setRepeats(false);settleCheck.start();
	}
	private HostWindow provisional;private boolean provisionalClicked;private volatile HostWindow lastPressWindow;private volatile Point lastPressPoint;private long lastPressNanos;private javax.swing.Timer provisionalTimer;
	private boolean osActivated(final HostWindow hw){ // EDT
		if(HOST_TRACE)System.err.println("[host] OS activated $"+Integer.toHexString(hw.record)+" (front $"+(frontWindow==null?"-":Integer.toHexString(frontWindow.record))+", order "+describeOrder()+")");
		if(restoreModalWindow(hw))return false;
		if(isFrontmostVisible(hw)||!hw.visible||hw==frontWindow&&onlyFloatersAhead(hw)){activateHost(hw);return true;}
		// The last window activated, now behind windows the program put in front of it without selecting them: the OS giving key
		// status back to it, not a user's choice. Bryce 2's backdrop was the only window when it came up (so the active one); its
		// palettes and document were brought in front by BringToFront, and the OS's re-activation raised the black backdrop over them all.
		if(hw==frontWindow){if(HOST_TRACE)System.err.println("[host] activation of $"+Integer.toHexString(hw.record)+" (the active window, behind others): restoring the stacking order");restack();return true;}
		if(System.nanoTime()-programmaticRaise<500_000_000L){activateHost(hw);return true;} // an echo: activateHost ignores it
		// A user activates a background window with the pointer on it (its title bar or content). With the pointer elsewhere the
		// OS is echoing this host's own raising - toFront's activations arrive late and out of order while the program is busy
		// (HyperCard opening a big stack: the Home window's echo came 500 ms after the restack, the program was handed an
		// activate event for Home and brought it back over the newly opened stack). The program's stacking is restored instead.
		if(!pointerOver(hw)){if(HOST_TRACE)System.err.println("[host] activation of $"+Integer.toHexString(hw.record)+" without the pointer on it: an echo, restoring the stacking order");restack();return false;}
		if(provisional==hw)return true; // macOS repeats the activation (key and main window): the click already under way stays counted
		if(provisional!=null&&provisional!=hw)settleProvisional();
		// The press can reach the canvas before the activation does: it was the click that raised the window.
		boolean pressed=lastPressWindow==hw&&System.nanoTime()-lastPressNanos<500_000_000L;
		if(pressed&&!mouseDown){provisional=hw;settleProvisional();return false;} // and it is over already: the program did not select the window
		provisional=hw;provisionalClicked=pressed;
		if(HOST_TRACE)System.err.println("[host] activation of $"+Integer.toHexString(hw.record)+" held until the click is through");
		if(provisionalTimer!=null)provisionalTimer.stop();
		provisionalTimer=new javax.swing.Timer(400,new ActionListener(){@Override public void actionPerformed(ActionEvent e){
			if(provisional!=hw||provisionalClicked)return;
			// No click came: a title-bar click (which only a decorated window has, and which the program never sees) or the OS
			// activating a window on its own (a late echo of showing it, an app switch). A bare window has no title bar to
			// click, so for it the activation is the OS's doing, not the user's: the program's stacking order is restored.
			if(hw.decorated){provisional=null;activateHost(hw);}
			else settleProvisional();}});
		provisionalTimer.setRepeats(false);provisionalTimer.start();return true;
	}
	/** True when the mouse pointer is over the window's frame (title bar included); false when its position is unknown. */
	private static boolean pointerOver(HostWindow hw){
		try{java.awt.PointerInfo pi=java.awt.MouseInfo.getPointerInfo();if(pi==null||hw.window==null)return false;return hw.window.getBounds().contains(pi.getLocation());}catch(RuntimeException e){return false;}
	}
	/** The click in the held window is over and the program did not select it: put the windows back as the program has them. */
	private void settleProvisional(){ // EDT
		HostWindow hw=provisional;provisional=null;if(provisionalTimer!=null){provisionalTimer.stop();provisionalTimer=null;}
		if(hw==null)return;
		if(HOST_TRACE)System.err.println("[host] $"+Integer.toHexString(hw.record)+" not selected by the program: restoring the stacking order");
		restack();recheckOrder();
	}
	/** Makes an external's window a floating XWindoid (see {@link HostWindow#windoid}); it is in the floating layer too, so it
	 *  never becomes the front document the activation follows. */
	void setWindoid(int record){
		final HostWindow hw=window(record);if(hw==null)return;hw.windoid=true;hw.floatingLayer=true;if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null)setFocusable(hw,false);}},false);
	}
	/** Puts a window in Carbon's floating layer (see {@link HostWindow#floatingLayer}). */
	void setFloatingLayer(int record){HostWindow hw=window(record);if(hw!=null)hw.floatingLayer=true;}
	/** Makes a window a system window (see {@link HostWindow#systemWindow}). */
	void setSystemWindow(int record){
		final HostWindow hw=window(record);if(hw==null)return;hw.systemWindow=true;hw.floatingLayer=true;if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){applySystemWindow(hw);}},false);
	}
	private static void applySystemWindow(HostWindow hw){if(hw.window==null||!hw.systemWindow)return;hw.window.setFocusableWindowState(false);hw.window.setAlwaysOnTop(true);}
	void activateHost(HostWindow hw){
		// the OS activating a window BringToFront just raised: the Window Manager changed the stacking only, the active window stays
		if(System.nanoTime()-quietRaise<500_000_000L&&hw!=frontWindow){if(HOST_TRACE)System.err.println("[host] activation of $"+Integer.toHexString(hw.record)+" ignored (BringToFront)");return;}
		if(hw.floatingLayer){ // over the documents, active with them: the front document stays the active window
			synchronized(windows){order.remove(hw);order.add(0,hw);}
			if(!hw.activePosted){hw.activePosted=true;listener.postActivate(hw.record,true);}
			return;
		}
		HostWindow previous=frontWindow;
		boolean echo=System.nanoTime()-programmaticRaise<500_000_000L&&!isFrontmostVisible(hw);
		if(echo){if(HOST_TRACE)System.err.println("[host] activation of $"+Integer.toHexString(hw.record)+" ignored (programmatic raise)");return;}
		hw.underCard=false; // the active window is in front of the card
		if(provisional==hw){provisional=null;if(provisionalTimer!=null)provisionalTimer.stop();}
		// In front of the other visible windows only: hidden windows ahead of it keep their place. HyperCard anchors its card
		// and picture windows behind its (hidden) palette windoids with NewWindow(behind)/SendBehind and never SelectWindows
		// them; a window jumped to the very front here would leave every window the program later places behind that anchor
		// behind it (a stack opened "in a new window" could otherwise appear behind the Home window).
		// Only ever forward: a window already ahead of the first visible one keeps its place (iText's palette, activated with its
		// document still hidden behind it, went to the very back, and the document came up in front of it when shown).
		// Appearance floating windows (WDEF 66/67) stay over a document the host activates: REALbasic keeps its palettes ahead
		// with SendBehind(document, last palette) only when the front document changes, so a document activated by the host
		// (back from another application) covered the tool palette for good.
		boolean floats=floats(hw);
		frontWindow=hw;synchronized(windows){int old=order.indexOf(hw);order.remove(hw);int at=order.size();for(int i=0;i<order.size();i++){HostWindow o=order.get(i);if(o.visible&&(floats||!floats(o))){at=i;break;}}if(old>=0&&old<at)at=old;order.add(at,hw);}
		if(HOST_TRACE)System.err.println("[host] activateHost $"+Integer.toHexString(hw.record)+" -> "+describeOrder());
		if(previous!=null&&previous!=hw)deactivateHost(previous);
		if(!hw.activePosted){hw.activePosted=true;listener.postActivate(hw.record,true);}
	}
	/** True when no visible window of the host's stacking list is in front of {@code hw} (hidden ones do not count). */
	/** Whether only floating windows (see {@link #floats}) are visible in front of {@code hw}: activating it leaves the stacking as it is. */
	private boolean onlyFloatersAhead(HostWindow hw){synchronized(windows){for(HostWindow w:order){if(w==hw)return true;if(w.visible&&!floats(w)&&!w.floatingLayer)return false;}}return false;}
	/** Whether no visible document window is in front of {@code hw}. A window the program has just created visible counts before
	 *  its frame is up: the OS activating the window that was in front (a modal dialog just closed - Wingz's Open dialog) is
	 *  dispatched before the new frame shows, and was taken for the user's choice; Wingz then brought its old sheet back
	 *  over the document it had just opened. */
	private boolean isFrontmostVisible(HostWindow hw){synchronized(windows){for(HostWindow w:order){if(w==hw)return true;if((w.visible||w.showPending)&&!w.floatingLayer&&!outOfSight(w))return false;}}return false;} // a window out of sight covers nothing
	void deactivateHost(HostWindow hw){if(hw.activePosted){hw.activePosted=false;listener.postActivate(hw.record,false);}}

	/** ModalDialog runs a guest-side modal loop, but dialog-kind windows with a Mac menu bar must be host JFrames.
	 *  Track the guest modality explicitly so a title-bar click cannot leave another application window in front. */
	private final List<Integer> modalWindows=new ArrayList<Integer>();
	void beginModalWindow(final int record){
		synchronized(modalWindows){modalWindows.add(record);}
		listener.dialogShown();
		if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){
			HostWindow modal=window(record);if(modal==null||modal.window==null)return;
			if(provisional!=null&&provisional!=modal)settleProvisional(); // the click that opened it (MacPaint: a double-click on a pattern) ends inside the dialog, never on the window it pressed
			refreshDialogMenuBars();applyModalWindowState();
			programmaticRaise=System.nanoTime();modal.window.toFront();if(modal.canvas!=null)modal.canvas.requestFocusInWindow();activateHost(modal);
		}},true);
	}
	void endModalWindow(final int record){
		synchronized(modalWindows){for(int i=modalWindows.size()-1;i>=0;i--)if(modalWindows.get(i)==record){modalWindows.remove(i);break;}}
		if(!headless)runOnEdt(new Runnable(){@Override public void run(){refreshDialogMenuBars();applyModalWindowState();restack();}},false);
	}
	/** ModalDialog/Alert is running, or the program waits for a dialog the host shows for it ({@link #ask}): it waits for the user. */
	boolean modalWindowUp(){if(asking.get()>0)return true;synchronized(modalWindows){return !modalWindows.isEmpty();}}
	private HostWindow activeModalWindow(){
		synchronized(modalWindows){for(int i=modalWindows.size()-1;i>=0;i--){HostWindow modal=window(modalWindows.get(i));if(modal!=null&&modal.visible)return modal;}}
		return null;
	}
	/** A Linux window manager may leave a raised JFrame active even after another frame's toFront(). Disabling every
	 *  non-modal host window gives the guest ModalDialog native modality on those desktops too. */
	private void applyModalWindowState(){ // EDT
		HostWindow modal=activeModalWindow();
		// A backdrop stays enabled: macOS raises it over the dialog when it is clicked, disabled or not, and only an enabled one
		// reports the press that lets the stacking be restored (the program's ModalDialog ignores the click, as on a Mac).
		for(HostWindow hw:windowsFrontToBack())if(hw.window!=null)hw.window.setEnabled(modal==null||hw==modal||hw.backdrop);
	}
	/** EDT: restores the modal window and rejects activation of {@code requested}. */
	private boolean restoreModalWindow(HostWindow requested){
		HostWindow modal=activeModalWindow();if(modal==null||modal==requested)return false;
		// While a pop-up menu is open, re-raising the dialog (toFront/activate) makes AWT ungrab and close the pop-up at once, and the
		// OS' activation echo of that brings it round again: the pop-up flashed open and shut. The dialog is raised once it is gone.
		if(currentPopup!=null||currentPicturePopup!=null){if(HOST_TRACE)System.err.println("[host] activation of $"+Integer.toHexString(requested.record)+" ignored while a pop-up menu is open");return true;}
		if(HOST_TRACE)System.err.println("[host] activation of $"+Integer.toHexString(requested.record)+" blocked by modal $"+Integer.toHexString(modal.record));
		if(provisional==requested){provisional=null;if(provisionalTimer!=null)provisionalTimer.stop();}
		// Outside macOS the activation that follows this host's own raising is an echo of it: raising the dialog again is
		// enough, while a delayed restack would raise (and so activate) the windows behind it once more, and round it goes.
		boolean echo=!GMenu.isMacOSX()&&System.nanoTime()-programmaticRaise<500_000_000L;
		programmaticRaise=System.nanoTime();
		if(modal.window!=null&&modal.window.isVisible()){modal.window.toFront();if(modal.canvas!=null)modal.canvas.requestFocusInWindow();}
		activateHost(modal);if(!echo)recheckOrder();
		return true;
	}

	/**
	 * The window is now the frontmost visible one. When this Java process is not the OS's active application (the
	 * program was launched from a script or a test), AWT never activates the frame, but the Window Manager still
	 * owes the program an activate event; some applications ignore typing until their document window is activated.
	 */
	private void activateIfFrontmost(HostWindow hw){
		if(hw.window==null||!hw.visible||hw.window.isActive()||hw.behindHyperCard)return;
		synchronized(windows){for(HostWindow other:order){if(other==hw)break;if(other.visible&&!other.floatingLayer)return;}}
		activateHost(hw);
	}
	/** Test aid: moves the emulated pointer to a screen point and presses or releases the button there. */
	void simulateMouse(int sx,int sy,boolean down){
		injectedMouse=new Point(sx,sy);mouseGlobalH=sx;mouseGlobalV=sy;
		if(down==mouseDown)return;
		mouseDown=down;listener.postMouse(down,sx,sy,down?modifierState&~0x80:modifierState|0x80,System.currentTimeMillis());
	}
	void injectClick(int record,int x,int y,int clicks){injectClick(record,x,y,clicks,0);}
	/** As above with extra Mac modifier bits (commands.txt `click <record> <x> <y> [shift] [cmd] [option]`). */
	void injectClick(int record,int x,int y,int clicks,int extraModifiers){
		HostWindow hw=window(record);if(hw==null||hw.canvas==null||!hw.canvas.isShowing()){System.err.println("[68K debug] click: window $"+Integer.toHexString(record)+(hw==null?" unknown (known: "+knownRecords()+")":hw.canvas==null?" has no canvas":" not showing"));return;}
		Point p=canvasOrigin(hw);int sx=p.x+x,sy=p.y+y;injectedMouse=new Point(sx,sy);
		for(int i=0;i<clicks;i++){
			mouseGlobalH=sx;mouseGlobalV=sy;mouseDown=true;listener.postMouse(true,sx,sy,(modifierState|extraModifiers)&~0x80,System.currentTimeMillis());
			try{Thread.sleep(60);}catch(InterruptedException ignored){}
			mouseDown=false;listener.postMouse(false,sx,sy,modifierState|extraModifiers|0x80,System.currentTimeMillis());
			try{Thread.sleep(60);}catch(InterruptedException ignored){}
		}
	}
	/** Debug aid: dispatches an AWT mouse event (MOUSE_PRESSED / MOUSE_DRAGGED / MOUSE_RELEASED) at screen coordinates through the
	 *  host window under the point, so Swing-level behaviour (popup menus) can be driven from the command file. */
	private Canvas awtPressCanvas;private boolean injectingAwt;
	private boolean isInjected(MouseEvent e){return injectingAwt;}
	void injectAwtMouse(final int id,final int sx,final int sy){
		Canvas target=awtPressCanvas;
		Point hp=toHost(sx,sy);final int hx=hp.x,hy=hp.y; // the command speaks the program's screen coordinates
		if(id==MouseEvent.MOUSE_PRESSED||target==null){target=null;for(HostWindow hw:windowsFrontToBack()){if(hw.canvas==null||!hw.canvas.isShowing())continue;Point p=hw.canvas.getLocationOnScreen();if(hx>=p.x&&hy>=p.y&&hx<p.x+hw.canvas.getWidth()&&hy<p.y+hw.canvas.getHeight()){target=hw.canvas;break;}}}
		if(target==null)return;final Canvas canvas=target;awtPressCanvas=id==MouseEvent.MOUSE_RELEASED?null:canvas;
		injectedMouse=new Point(sx,sy); // control tracking follows the injected pointer, not the real one
		runOnEdt(new Runnable(){@Override public void run(){Point p=canvas.getLocationOnScreen();int mods=id==MouseEvent.MOUSE_RELEASED?0:InputEvent.BUTTON1_DOWN_MASK;
			injectingAwt=true;try{canvas.dispatchEvent(new MouseEvent(canvas,id,System.currentTimeMillis(),mods,hx-p.x,hy-p.y,hx,hy,1,false,MouseEvent.BUTTON1));}finally{injectingAwt=false;}
			if(Mac68kApi.TRACE)System.err.println("[68K debug] awt mouse "+id+" at "+(hx-p.x)+","+(hy-p.y)+" in window "+Integer.toHexString(canvas.owner.record));}},false);
		try{Thread.sleep(80);}catch(InterruptedException ignored){}
	}
	private JPopupMenu currentPopup;private List<JMenuItem> currentPopupItems;
	/** Debug aid (commands.txt `popup <item>`): chooses an item (1-based, 0 = dismiss) in the pop-up menu PopUpMenuSelect is showing. */
	/** Debug/test aid: a pop-up menu of the program (PopUpMenuSelect) is open for {@link #injectPopupChoice}. */
	boolean popupOpen(){return currentPopup!=null||currentPicturePopup!=null;}
	void injectPopupChoice(final int item){injectPopupChoice(item,0);}
	/** As above, {@code subitem} (1-based) of the hierarchical item {@code item} when not 0 (commands.txt `popup <item>.<subitem>`). */
	void injectPopupChoice(final int item,final int subitem){
		runOnEdt(new Runnable(){@Override public void run(){JPopupMenu popup=currentPopup;List<JMenuItem> items=currentPopupItems;
			if(Mac68kApi.TRACE)System.err.println("[68K debug] popup choice "+item+(subitem!=0?"."+subitem:"")+(popup==null?currentPicturePopup!=null?" (picture pop-up)":" (no pop-up menu open)":" visible="+popup.isVisible()));
			if(popup==null){java.util.function.IntConsumer picture=currentPicturePopup;if(picture!=null)picture.accept(item);return;}
			JMenuItem mi=items!=null&&item>=1&&item<=items.size()?items.get(item-1):null;
			if(subitem!=0){java.awt.Component c=mi instanceof javax.swing.JMenu&&subitem<=((javax.swing.JMenu)mi).getMenuComponentCount()?((javax.swing.JMenu)mi).getMenuComponent(subitem-1):null;mi=c instanceof JMenuItem&&!(c instanceof javax.swing.JMenu)?(JMenuItem)c:null;}
			if(mi!=null)mi.doClick();popup.setVisible(false);javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();}},false);
		try{Thread.sleep(80);}catch(InterruptedException ignored){}
	}
	/** Debug aid: synthesizes a key press (character) for the front window. */
	void injectKey(int record,int ch){injectKey(record,ch,modifierState);}
	/** As above with explicit Mac modifier bits (commands.txt `key <record> <char|0xNN> [cmd] [shift] [option]`). */
	void injectKey(int record,int ch,int modifiers){int code=keyCodeForChar(ch);listener.postKey(3,code,ch,modifiers);try{Thread.sleep(30);}catch(InterruptedException ignored){}listener.postKey(4,code,ch,modifiers);}

	/** Virtual key code a US keyboard would send for the character (programs like HyperCard test the key code of Return/Enter/arrows, not the character). */
	static int keyCodeForChar(int ch){
		switch(ch){
			case 13:return 36;case 3:return 76;case 9:return 48;case 8:return 51;case 0x7f:return 117;case 0x1b:return 53;case ' ':return 49;
			case 0x1c:return 123;case 0x1d:return 124;case 0x1e:return 126;case 0x1f:return 125;case 1:return 115;case 4:return 119;case 0x0b:return 116;case 0x0c:return 121;
			case ',':case '<':return 43;case '.':case '>':return 47;case '/':case '?':return 44;case ';':case ':':return 41;case '\'':case '"':return 39;
			case '[':case '{':return 33;case ']':case '}':return 30;case '\\':case '|':return 42;case '`':case '~':return 50;case '-':case '_':return 27;case '=':case '+':return 24;
		}
		int upper=Character.toUpperCase(ch);
		if(upper>='A'&&upper<='Z')return macKeyCode(KeyEvent.VK_A+(upper-'A'),KeyEvent.KEY_LOCATION_STANDARD);
		String shifted=")!@#$%^&*(";int digit=ch>='0'&&ch<='9'?ch-'0':shifted.indexOf(ch);
		if(digit>=0)return macKeyCode(KeyEvent.VK_0+digit,KeyEvent.KEY_LOCATION_STANDARD);
		return 0;
	}

	/** Debug aid (-Dhz68k.snapshot=dir): writes every visible window's pixels as PNG files named by window record. */
	void snapshot(java.io.File dir){
		java.util.List<HostWindow> all;synchronized(windows){all=new java.util.ArrayList<HostWindow>(windows.values());}
		dir.mkdirs();
		final JFrame si=standIn;
		if(si!=null&&si.isShowing()){try{final BufferedImage img=new BufferedImage(si.getWidth(),si.getHeight(),BufferedImage.TYPE_INT_RGB);
			SwingUtilities.invokeAndWait(new Runnable(){@Override public void run(){Graphics g=img.getGraphics();si.paint(g);g.dispose();}});
			javax.imageio.ImageIO.write(img,"png",new java.io.File(dir,"standin.png"));}catch(Exception ignored){}}
		final BufferedImage screenShot=screenFrame!=null&&screenFrame.isShowing()?screenImage:null;
		if(screenShot!=null){try{javax.imageio.ImageIO.write(screenShot,"png",new java.io.File(dir,"screen.png"));}catch(Exception ignored){}}
		final Window popup=picturePopupWindow;
		if(popup!=null&&popup.isShowing()){try{final BufferedImage img=new BufferedImage(popup.getWidth(),popup.getHeight(),BufferedImage.TYPE_INT_RGB);
			SwingUtilities.invokeAndWait(new Runnable(){@Override public void run(){Graphics g=img.getGraphics();popup.paint(g);g.dispose();}});
			javax.imageio.ImageIO.write(img,"png",new java.io.File(dir,"popup.png"));
			java.nio.file.Files.write(new java.io.File(dir,"popup.txt").toPath(),(popup.getX()+" "+popup.getY()+" "+popup.getWidth()+" "+popup.getHeight()).getBytes("UTF-8"));}catch(Exception ignored){}}
		for(HostWindow hw:all){
			if(!hw.visible||hw.imageBase<=0)continue;
			int w=hw.imageWidth,h=hw.imageHeight;byte[] raw=memory.raw();int base=hw.imageBase,rowBytes=hw.imageRowBytes;
			if(base+(long)rowBytes*h>raw.length)continue;
			BufferedImage img=new BufferedImage(Math.max(1,w),Math.max(1,h),BufferedImage.TYPE_INT_RGB);int[] row=new int[w];int[] clut=hw.imageDepth<=8?devicePalette(hw.imageDepth):null;
			int[] gamma=quickDraw==null?null:quickDraw.gammaLookup();boolean gray=quickDraw!=null&&quickDraw.grayDisplay();
			for(int y=0;y<h;y++){readPixels(raw,base+y*rowBytes,hw.imageDepth,clut,w,row);if(gray)applyGray(row,0,w);if(gamma!=null)applyGamma(gamma,row,0,w);img.setRGB(0,y,w,1,row,0,w);}
			try{javax.imageio.ImageIO.write(img,"png",new java.io.File(dir,String.format("win-%06X.png",hw.record)));
				java.awt.Point p=hw.canvas!=null&&hw.canvas.isShowing()?canvasOrigin(hw):null;
				if(p!=null)java.nio.file.Files.write(new java.io.File(dir,String.format("win-%06X.txt",hw.record)).toPath(),(p.x+" "+p.y+" "+w+" "+h+" depth "+hw.imageDepth+" z "+windowsFrontToBack().indexOf(hw)+" "+hw.title).getBytes("UTF-8"));
			}catch(Exception ignored){}
		}
	}
	List<HostWindow> windowsFrontToBack(){synchronized(windows){return new ArrayList<HostWindow>(order);}}
	HostWindow frontWindow(){return frontWindow;}
	/** The Drag Manager's outline of the region being dragged: a translucent bare window following the mouse (screen coordinates). */
	private javax.swing.JWindow dragOutline;
	void showDragOutline(final int[] rect){
		if(headless||rect==null||rect[2]<=0||rect[3]<=0)return;
		javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){
			if(dragOutline!=null)dragOutline.dispose();
			javax.swing.JWindow w=new javax.swing.JWindow();w.setFocusableWindowState(false);w.setAlwaysOnTop(true);
			try{w.setBackground(new Color(0,0,0,0));}catch(RuntimeException ignored){}
			javax.swing.JPanel panel=new javax.swing.JPanel(){@Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g;g2.setColor(new Color(0x60,0x60,0x60,0x50));g2.fillRect(0,0,getWidth(),getHeight());g2.setColor(new Color(0x30,0x30,0x30,0xc0));g2.setStroke(new java.awt.BasicStroke(1,java.awt.BasicStroke.CAP_BUTT,java.awt.BasicStroke.JOIN_MITER,1,new float[]{3,3},0));g2.drawRect(0,0,getWidth()-1,getHeight()-1);}};
			panel.setOpaque(false);w.getContentPane().add(panel);w.setSize(Math.min(hostLen(rect[2]),4000),Math.min(hostLen(rect[3]),4000));w.setLocation(hostX(rect[0]),hostY(rect[1]));w.setVisible(true);dragOutline=w;}});
	}
	void moveDragOutline(final int x,final int y){if(headless)return;javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){if(dragOutline!=null)dragOutline.setLocation(hostX(x),hostY(y));}});}
	/** Moves and resizes the outline (GrowWindow tracking shows the frame the window would get); program screen coordinates
	 *  like {@link #showDragOutline} (they were used as host pixels: with the program's screen centred on the display the
	 *  outline jumped to the display's top-left corner, like a second window, at the first move of the mouse). */
	void setDragOutline(final int x,final int y,final int width,final int height){if(headless)return;javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){if(dragOutline!=null){dragOutline.setBounds(hostX(x),hostY(y),Math.max(1,Math.min(hostLen(width),4000)),Math.max(1,Math.min(hostLen(height),4000)));dragOutline.repaint();}}});}
	/** Gives a bare (custom-framed) window the outline of its structure region, relative to the frame's top-left; null makes it rectangular again. */
	void setWindowShape(final int record,final java.awt.Shape shape){
		final HostWindow hw=window(record);if(hw==null)return;hw.shape=shape;
		if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){applyShape(hw);}},false);
	}
	/** Whether one of the program's windows is the OS's active window (EDT). */
	private boolean programActive=true;
	/**
	 * The program came to the front or went to the background (another application, or a window of HyperZebra's own, took the
	 * focus). Where the menu bar sits inside the window (Windows, Linux) a shaped window - Kingyo2's goldfish bowl on the desktop -
	 * carries a strip of menu bar above its outline; in the background the strip is left out of the outline, so only the shape
	 * shows, as the Mac's menu bar belongs to the front application only. A rectangular window without a title bar (DragThing's
	 * docks) loses the strip the same way. The bar keeps its place in the layout: the content does not move (EDT).
	 */
	void setProgramActive(boolean active){
		if(programActive==active)return;programActive=active;
		listener.postSuspendResume(active); // the Process Manager's suspend/resume (programs that asked for them)
		if(GMenu.isMacOSX()||headless)return;
		for(HostWindow hw:windowsFrontToBack())if(hw.window!=null&&!hw.decorated)applyShape(hw);
	}
	/** The menu bar shown inside a window without a title bar (platforms without a screen menu bar), or null. */
	private static JMenuBar inWindowMenuBar(HostWindow hw){
		if(hw.decorated||hw.window==null)return null;
		JMenuBar bar=hw.frame!=null?hw.frame.getJMenuBar():hw.dialog!=null?hw.dialog.getJMenuBar():null;
		return bar!=null&&bar.getMenuCount()>0?bar:null;
	}
	/** Whether the window's outline has to be set again after its size or menu bar changed: it has a shape of its own, or it is
	 *  a bare window in the background (whose menu-bar strip is left out, see {@link #applyShape}). */
	private boolean needsShape(HostWindow hw){return !hw.decorated&&(hw.shape!=null||!programActive&&!GMenu.isMacOSX());}
	/** Hands the window its structure outline (scaled to host pixels); a window rebuilt as a frame gets it again (EDT). */
	private void applyShape(HostWindow hw){
		if(hw.window==null||hw.decorated)return;java.awt.Shape shape=hw.shape;
		if(shape!=null){
			if(scPct!=100)shape=java.awt.geom.AffineTransform.getScaleInstance(scale(),scale()).createTransformedShape(shape);
			// Where the menu bar sits inside the window (platforms without a screen menu bar) the canvas starts below it: the outline
			// moves down with the canvas, and the bar stays showing above it (Kingyo2's bowl was cut one bar height too high) while
			// the program is in front (see setProgramActive).
			JMenuBar bar=hw.frame!=null?hw.frame.getJMenuBar():hw.dialog!=null?hw.dialog.getJMenuBar():null;
			hw.window.validate();Point at=SwingUtilities.convertPoint(hw.canvas,0,0,hw.window);TitleStrip strip=shownTitleStrip(hw);
			if(strip!=null){ // the title rows above the bar, the rest below it, the bar between them in the background too (it is the window's own, as a native frame's)
				int band=strip.getHeight();Point st=SwingUtilities.convertPoint(strip,0,0,hw.window);
				java.awt.geom.Area top=new java.awt.geom.Area(shape),rest=new java.awt.geom.Area(shape);
				top.intersect(new java.awt.geom.Area(new Rectangle(-1<<20,-1<<20,1<<21,(1<<20)+band)));rest.intersect(new java.awt.geom.Area(new Rectangle(-1<<20,band,1<<21,1<<20)));
				java.awt.geom.Area area=new java.awt.geom.Area(java.awt.geom.AffineTransform.getTranslateInstance(st.x,st.y).createTransformedShape(top));
				area.add(new java.awt.geom.Area(java.awt.geom.AffineTransform.getTranslateInstance(at.x,at.y).createTransformedShape(rest)));
				area.add(new java.awt.geom.Area(SwingUtilities.convertRectangle(bar.getParent(),bar.getBounds(),hw.window)));
				shape=area;
			}
			else if(at.x!=0||at.y!=0||bar!=null){
				java.awt.geom.Area area=new java.awt.geom.Area(java.awt.geom.AffineTransform.getTranslateInstance(at.x,at.y).createTransformedShape(shape));
				if(bar!=null&&bar.isVisible()&&bar.getParent()!=null&&programActive)area.add(new java.awt.geom.Area(SwingUtilities.convertRectangle(bar.getParent(),bar.getBounds(),hw.window)));
				shape=area;
			}
		}
		else if(!programActive&&hw.canvas!=null&&inWindowMenuBar(hw)!=null&&shownTitleStrip(hw)==null){ // a rectangular bare window in the background: the content only
			hw.window.validate();shape=SwingUtilities.convertRectangle(hw.canvas.getParent(),hw.canvas.getBounds(),hw.window);
		}
		try{hw.window.setShape(shape);}catch(RuntimeException e){if(HOST_TRACE)System.err.println("[host] setShape failed: "+e);}
	}
	void hideDragOutline(){if(headless)return;javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){if(dragOutline!=null){dragOutline.dispose();dragOutline=null;}}});}
	/** Balloon Help: a small window with the message next to the tip (screen coordinates); one at a time. */
	private javax.swing.JWindow balloon;
	void showBalloon(final String text,final int tipX,final int tipY){
		showBalloon(()->new javax.swing.JLabel("<html><div style='width:180px'>"+text.replace("&","&amp;").replace("<","&lt;").replace("\r","<br>").replace("\n","<br>")+"</div></html>"),tipX,tipY);
	}
	/** A balloon showing a picture (a PICT message, or styled text drawn in its fonts and colours). */
	void showBalloonImage(final java.awt.image.BufferedImage image,final int tipX,final int tipY){
		showBalloon(()->new javax.swing.JLabel(new javax.swing.ImageIcon(image)),tipX,tipY);
	}
	private void showBalloon(final java.util.function.Supplier<javax.swing.JLabel> content,final int tipX,final int tipY){
		if(headless)return;final int x=hostX(tipX),y=hostY(tipY);
		javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){
			if(balloon!=null)balloon.dispose();
			javax.swing.JWindow w=new javax.swing.JWindow();w.setFocusableWindowState(false);w.setAlwaysOnTop(true);
			javax.swing.JLabel label=content.get();
			label.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(Color.BLACK),javax.swing.BorderFactory.createEmptyBorder(6,8,6,8)));
			label.setOpaque(true);label.setBackground(new Color(0xff,0xff,0xcc));w.getContentPane().add(label);w.pack();
			java.awt.Rectangle screen=w.getGraphicsConfiguration()==null?new java.awt.Rectangle(0,0,1600,1200):w.getGraphicsConfiguration().getBounds();
			int left=x+8,top=y+8;if(left+w.getWidth()>screen.x+screen.width)left=x-8-w.getWidth();if(top+w.getHeight()>screen.y+screen.height)top=y-8-w.getHeight();
			w.setLocation(Math.max(screen.x,left),Math.max(screen.y,top));w.setVisible(true);balloon=w;}});
	}
	void hideBalloon(){javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){if(balloon!=null){balloon.dispose();balloon=null;}}});}
	/** KeyScript: turns the host input method's composition on (Japanese) or off (Roman) for the program's windows. */
	/** Asks the host input method to commit what it is composing (FixTSMDocument): the committed text follows as an input-method event. */
	void endComposition(){
		runOnEdt(new Runnable(){@Override public void run(){for(HostWindow hw:windowsFrontToBack()){if(hw.canvas==null||!hw.canvas.isFocusOwner())continue;try{java.awt.im.InputContext ic=hw.canvas.getInputContext();if(ic!=null)ic.endComposition();}catch(RuntimeException ignored){}}}},false);
	}
	void setCompositionEnabled(final boolean on){
		compositionEnabled=on;
		javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){
			for(HostWindow hw:windowsFrontToBack()){if(hw.canvas==null)continue;try{java.awt.im.InputContext ic=hw.canvas.getInputContext();if(ic!=null)ic.setCompositionEnabled(on);}catch(UnsupportedOperationException|IllegalStateException ignored){}}}});
	}
	volatile boolean compositionEnabled=true;

	void showWindow(final int record){
		final HostWindow hw=window(record);if(hw==null)return;boolean was=hw.visible;hw.visible=true;hw.everShown=true;hw.showPending=false;hw.shownSerial=memory.writeSerial();
		// what the screen held there before belongs to no one now (PaintOne paints the frame and erases the content): frames of the
		// windows behind, drawn while this one was still hidden, came back as its own pixels once a later write in the same
		// device tile made them look new (Resorcerer's editors showed the file windows' borders in their margins)
		if(!was)forgetDeviceWritesUnder(hw);
		// nor are the screen writes before it a screen saver at work: Uninvited fills the whole screen with its desktop pattern
		// and then shows its windows - counted as drawing round windows already up, it put the stale screen over them for good
		if(!was)saverQuietSerial=Math.max(saverQuietSerial,memory.deviceSerial());
		if(record==quietRaisedHidden){quietRaisedHidden=0;quietRaise=0;}
		runOnEdt(new Runnable(){@Override public void run(){if(processHidden&&!hw.systemWindow)return; // shown while its program is hidden: it appears with the program
			if(hw.window!=null&&!hw.window.isVisible()&&!outOfSight(hw)){updateMenuBarFor(hw);hw.ignoreMoves=true;setFocusable(hw,!hw.behindHyperCard&&!(hw.backdrop&&!isFrontmostVisible(hw)));hw.window.setVisible(true);placeWindow(hw);hw.ignoreMoves=false;if(!hw.behindHyperCard)hw.canvas.requestFocusInWindow();restack();}refreshDialogMenuBars();applyModalWindowState();updateStandIn();activateIfFrontmost(hw);}},false);
	}
	/** A titled window with no content to speak of (NewWindow with an empty rect gets a 1-pixel port) and its title bar above the
	 *  screen's top or under the menu bar: nothing of it shows on a real Mac. AQUAZONE 1.5 opens such a "New Window" at 0,0; its
	 *  native frame, moved onto the display, stood at the screen's corner with its own close button and took the keyboard. */
	private boolean outOfSight(HostWindow hw){return hw.insisted&&offScreen(hw,hw.contentLeft,hw.contentTop)||hw.decorated&&!hw.backdrop&&(hw.width<=1||hw.height<=1)&&hw.contentTop<=MENU_BAR_HEIGHT||beforeScreen(hw);}
	/** A titled window wholly left of or above the program's one screen, where no Mac had a display: out of sight as there (macOS
	 *  would drag its native frame partly onto the display). Battle Planets 1.5 shows a 200x200 window with an "Encounter" button
	 *  at -1000,-1000; its frame came onto the display at the top left. Bare windows parked off the display are left where they are. */
	private static boolean beforeScreen(HostWindow hw){return hw.decorated&&!hw.backdrop&&(hw.contentLeft+hw.width<=0||hw.contentTop+hw.height<=0);}
	/** Shows or hides the native frame of a visible window that came into or went out of sight (SizeWindow, MoveWindow); EDT only. */
	private void syncOutOfSight(HostWindow hw){
		if(hw.window==null||!hw.visible||(processHidden&&!hw.systemWindow))return;
		boolean hide=outOfSight(hw);
		if(hide&&hw.window.isVisible()){hw.window.setVisible(false);restack();}
		else if(!hide&&!hw.window.isVisible())showWindow(hw.record);
	}
	/** Device write serial when the program last hid or disposed a window: the screen stand-in shows only what was drawn on the
	 *  screen after that, not the window frames a WDEF mirrored into the device while the windows were up. */
	private volatile long deviceBaseline;
	/** Set once the extensions have run; until then the screen stand-in stays off (see {@link #extensionsLoaded}). */
	private volatile boolean extensionsDone;
	boolean extensionsDone(){return extensionsDone;}
	/** The extensions have run: what their INITs put on the screen (their icons along the bottom, as at boot) does not count as
	 *  the program drawing on a screen without windows - the stand-in showed a display-sized black frame for a moment at every
	 *  launch while they drew them. */
	/** The INITs have run: what they drew on the screen (their icons) is neither the program's direct drawing for the stand-in nor
	 *  pixels a window may adopt later - a window shown over them starts white, as PaintOne leaves it on a real Mac. */
	void extensionsLoaded(){deviceBaseline=memory.deviceSerial();memory.forgetAllDeviceWrites();memory.resetDeviceWritten();extensionsDone=true;}
	/**
	 * A window going away (HideWindow, DisposeWindow) takes the screen writes under it along: on a real Mac PaintBehind repaints
	 * what it uncovers. Device bytes nobody adopted yet - a WDEF frame drawn into the Window Manager port just before
	 * CloseDialog, before the window's adoption pass - would otherwise be adopted by the window behind as its own drawing
	 * (Resorcerer: the closed editor's title stayed in the file window's header). Pixels under a visible window in front stay theirs.
	 */
	private void forgetDeviceWritesUnder(HostWindow hw){
		if(headless||!hw.visible||hw.imageBase<=0||!memory.deviceWritten())return;
		QuickDraw68k screen=screenSource();if(screen==null)return;int[] frame;try{frame=screen.mainDeviceFrame();}catch(M68kCpuException e){return;}if(frame==null)return;
		int deviceBase=frame[0],rowBytes=frame[1],depth=frame[2],left=frame[3],top=frame[4],right=frame[5],bottom=frame[6];
		java.util.List<int[]> covers=new java.util.ArrayList<int[]>();
		for(HostWindow front:windowsFrontToBack()){if(front==hw)break;if(front.visible&&front.imageBase>0)covers.add(new int[]{front.contentLeft,front.contentTop,front.contentLeft+front.imageWidth,front.contentTop+front.imageHeight});}
		int x0=Math.max(left,hw.contentLeft),x1=Math.min(right,hw.contentLeft+hw.imageWidth);if(x0>=x1)return;
		for(int gy=Math.max(top,hw.contentTop),end=Math.min(bottom,hw.contentTop+hw.imageHeight);gy<end;gy++){int row=deviceBase+(gy-top)*rowBytes;
			for(int gx=x0;gx<x1;){int run=gx;while(run<x1&&!coveredBy(covers,run,gy))run++;
				if(run>gx){int o0=row+((gx-left)*depth>>>3),o1=row+(((run-left)*depth+7)>>>3);memory.forgetDeviceWrites(o0,o1-o0);}
				while(run<x1&&coveredBy(covers,run,gy))run++;gx=run;}}
	}
	void hideWindow(final int record){
		final HostWindow hw=window(record);if(hw==null)return;forgetDeviceWritesUnder(hw);hw.visible=false;hw.showPending=false;deviceBaseline=memory.deviceSerial();
		runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null)hw.window.setVisible(false);refreshDialogMenuBars();updateStandIn();updateBackdropFocus(windowsFrontToBack());
			// hiding the active window activates the next visible one (HideWindow semantics)
			if(hw.activePosted){deactivateHost(hw);if(hw.floatingLayer)return;
				HostWindow next=null;synchronized(windows){for(HostWindow other:order)if(other!=hw&&other.visible&&!other.floatingLayer){next=other;break;}}if(next!=null)activateIfFrontmost(next);}}},false);
	}
	void disposeWindow(final int record){
		final HostWindow hw0=window(record);if(hw0!=null&&hw0.visible)forgetDeviceWritesUnder(hw0);
		final HostWindow hw;synchronized(windows){hw=windows.remove(record);if(hw!=null)order.remove(hw);}
		if(hw==null)return;overlays.remove(record);deviceBaseline=memory.deviceSerial();
		if(!headless)memory.untrackBuffer(hw.imageBase,hw.imageRowBytes*hw.imageHeight);
		runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null){hw.window.setVisible(false);hw.window.dispose();}refreshDialogMenuBars();updateStandIn();updateBackdropFocus(windowsFrontToBack());}},false);
	}

	/**
	 * A classic program with no window still has its menu bar on the Mac screen; here the menus hang off
	 * windows, so while the program shows none, a small frame named after it carries the menu bar, takes
	 * dropped documents (Open Documents Apple event) and quits the program when closed. Runs on the EDT.
	 */
	/** Set once the program waited for events with neither a window nor a menu bar (see {@link #programWaiting}). */
	private volatile boolean waitingWithoutUi;
	/**
	 * The program asks for its next event: one that has neither a window nor a menu bar by then is a drop-only program
	 * (FastAction plays the movies dropped on its Finder icon and puts up no menus at all), and the stand-in is the only thing
	 * it can be reached by - to drop movies on or to close. Cheap: called at every WaitNextEvent/GetNextEvent.
	 */
	void programWaiting(){
		if(waitingWithoutUi||headless||disposed||!menuBar.isEmpty()||!extensionsDone)return;
		for(HostWindow hw:windowsFrontToBack())if(hw.visible)return;
		waitingWithoutUi=true;runOnEdt(new Runnable(){@Override public void run(){updateStandIn();}},false);
	}
	/**
	 * ShowHideProcess (Process Manager OSDispatch $60; A-Dock's "Hide" item): the program's windows - and its stand-in - leave
	 * the screen without the program being told (they stay visible to its Window Manager), and come back when it is shown again
	 * or made the front process.
	 */
	private volatile boolean processHidden;
	boolean processHidden(){return processHidden;}
	void setProcessHidden(final boolean hidden){
		if(processHidden==hidden)return;processHidden=hidden;if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){
			for(HostWindow hw:windowsFrontToBack())if(hw.window!=null&&hw.visible&&!hw.systemWindow)hw.window.setVisible(!hidden&&!outOfSight(hw));
			if(hidden){cancelStandInTimer();if(standIn!=null&&standIn.isVisible())standIn.setVisible(false);updateScreenBar();}
			else{restack();updateStandIn();HostWindow front=frontWindow;if(front!=null&&front.visible)activateIfFrontmost(front);}
		}},false);
	}
	private void updateStandIn(){updateStandIn0();updateScreenBar();}
	private void updateStandIn0(){
		if(headless||disposed)return;
		if(processHidden){cancelStandInTimer();if(standIn!=null&&standIn.isVisible())standIn.setVisible(false);return;}
		boolean anyVisible=false;for(HostWindow hw:windowsFrontToBack())if(hw.visible&&hw.window!=null&&hw.window.isVisible()){anyVisible=true;break;}
		if(!anyVisible&&updateScreenFrame()){if(standIn!=null&&standIn.isVisible())standIn.setVisible(false);return;}
		if(saverMode)return; // the screen frame stands in front of the windows on purpose
		if(screenFrame!=null&&screenFrame.isVisible())screenFrame.setVisible(false);
		boolean wanted=!anyVisible&&(!menuBar.isEmpty()||waitingWithoutUi);
		if(!wanted){cancelStandInTimer();if(standIn!=null&&standIn.isVisible())standIn.setVisible(false);return;}
		if(standIn==null||!standIn.isVisible()){
			// Not showing yet: wait a moment first. Most programs set their menu bar and open a window within a few
			// milliseconds of each other, and the frame flashing in between is just visual noise.
			if(standInTimer==null){
				standInTimer=new javax.swing.Timer(STAND_IN_DELAY_MS,new java.awt.event.ActionListener(){@Override public void actionPerformed(java.awt.event.ActionEvent e){standInTimer=null;showStandInNow();}});
				standInTimer.setRepeats(false);standInTimer.start();
			}
			return;
		}
		showStandInNow();
	}
	private void cancelStandInTimer(){if(standInTimer!=null){standInTimer.stop();standInTimer=null;}}
	/** Builds (once) and shows the stand-in if the program still has a menu bar and no visible window; EDT only. */
	private void showStandInNow(){
		if(headless||disposed||processHidden)return;
		boolean anyVisible=false;for(HostWindow hw:windowsFrontToBack())if(hw.visible&&hw.window!=null&&hw.window.isVisible()){anyVisible=true;break;}
		if(anyVisible||(menuBar.isEmpty()&&!waitingWithoutUi)||(screenFrame!=null&&screenFrame.isVisible()))return;
		if(standIn==null){
			standIn=new JFrame(applicationName);if(applicationIcon!=null)standIn.setIconImage(applicationIcon);standIn.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			JLabel label=new JLabel("<html><div style='text-align:center'>"+International.t("{0} is running.", escapeHtml(applicationName))+"<br>"+International.t("Use its menus, or drop documents you want to open here.")+"<br>"+International.t("Closing this window quits the application.")+"</div></html>",JLabel.CENTER);
			label.setBorder(javax.swing.BorderFactory.createEmptyBorder(8,32,24,32));
			// The program's Finder icon (icl8) at 2x above the text, so the frame is recognizable as that program's drop target.
			javax.swing.JPanel content=new javax.swing.JPanel(new java.awt.BorderLayout());
			if(applicationIcon!=null){
				java.awt.image.BufferedImage big=new java.awt.image.BufferedImage(applicationIcon.getWidth()*2,applicationIcon.getHeight()*2,java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D g=big.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				g.drawImage(applicationIcon,0,0,big.getWidth(),big.getHeight(),null);g.dispose();
				JLabel icon=new JLabel(new javax.swing.ImageIcon(big),JLabel.CENTER);icon.setBorder(javax.swing.BorderFactory.createEmptyBorder(24,32,0,32));
				content.add(icon,java.awt.BorderLayout.NORTH);
			}else label.setBorder(javax.swing.BorderFactory.createEmptyBorder(24,32,24,32));
			content.add(label,java.awt.BorderLayout.CENTER);
			standIn.setContentPane(content);
			standIn.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){listener.postQuit();}});
			new DropTarget(content,new DropTargetAdapter(){
				@SuppressWarnings("unchecked")
				@Override public void drop(DropTargetDropEvent e){
					try{
						if(!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)){e.rejectDrop();return;}
						e.acceptDrop(DnDConstants.ACTION_COPY);
						List<File> files=(List<File>)e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
						e.dropComplete(true);
						if(!files.isEmpty())listener.postOpenDocuments(expandDroppedDocuments(files));
					}catch(Exception ex){e.dropComplete(false);}
				}
			});
			standIn.pack();standIn.setLocation(40,60);
		}
		if(standInMenuVersion!=menuVersion){if(!patchMenuBar(standIn.getJMenuBar()))standIn.setJMenuBar(buildMenuBar());standInMenuVersion=menuVersion;standIn.revalidate();}
		if(!standIn.isVisible())standIn.setVisible(true);
	}
	private static String escapeHtml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

	/*
	 * The screen stand-in: a program that draws straight into screen memory without ever opening a window (some games
	 * paints its title into screenBits and waits for a click) would otherwise show nothing. While no window is visible and
	 * the program has written to the main device, a frame shows the device's pixels and feeds its clicks and keys to
	 * the program as if it were the screen.
	 */
	private JFrame screenFrame;private JComponent screenCanvas;private BufferedImage screenImage;private long screenSerial=-1;private int[] screenClut;private int screenMenuVersion=-1;private int screenGamma;
	private int[] screenFrameGeometry; // {base,rowBytes,depth,left,top,right,bottom} the image was built for
	/** Shows or refreshes the screen stand-in when the program draws directly on the device; false when it is not wanted. EDT only. */
	private boolean updateScreenFrame(){
		QuickDraw68k screen=screenSource();if(!extensionsDone||screen==null||!memory.deviceWritten()||memory.deviceSerial()<=deviceBaseline)return false;
		int[] frame;try{frame=screen.mainDeviceFrame();}catch(M68kCpuException e){return false;}
		if(frame==null)return false;int w=frame[5]-frame[3],h=frame[6]-frame[4];if(w<=0||h<=0||w>4096||h>4096)return false;
		if(screenFrame==null){
			screenFrame=new JFrame(applicationName);if(applicationIcon!=null)screenFrame.setIconImage(applicationIcon);screenFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			screenFrame.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){listener.postQuit();}
				@Override public void windowActivated(WindowEvent e){if(screenCanvas!=null)screenCanvas.requestFocusInWindow();}
				@Override public void windowDeactivated(WindowEvent e){releaseAllKeys();}});
			screenCanvas=new JComponent(){private static final long serialVersionUID=1L;@Override protected void paintComponent(Graphics g){
				BufferedImage smooth=screenSmoothImage;SmoothZoom.Plane plane=screenSmoothPlane;
				if(smooth!=null&&plane!=null){Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,smoothInterpolation(g2,plane.s));g2.drawImage(smooth,0,0,getWidth(),getHeight(),null);}
				else if(screenImage!=null)g.drawImage(screenImage,0,0,getWidth(),getHeight(),null);}};
			screenCanvas.setOpaque(true);screenCanvas.setFocusable(true);screenCanvas.setFocusTraversalKeysEnabled(false);screenCanvas.addKeyListener(keyAdapter());
			MouseAdapter mouse=new MouseAdapter(){
				private Point at(MouseEvent e){int[] f=screenFrameGeometry;return new Point((f==null?0:f[3])+virtualLen(e.getX()),(f==null?0:f[4])+virtualLen(e.getY()));}
				@Override public void mousePressed(MouseEvent e){screenCanvas.requestFocusInWindow();if(!SwingUtilities.isLeftMouseButton(e)&&!SwingUtilities.isRightMouseButton(e))return;
					if(fontPick){fontPickRelease=true;endFontPick();garbledTextNotFound();return;} // the whole-screen view has no window images to compare: "Fix Garbled Text…" works in windows
					Point p=at(e);mouseDown=true;mouseGlobalH=p.x;mouseGlobalV=p.y;int mods=hostModifiers(e.getModifiersEx())&~0x80;if(SwingUtilities.isRightMouseButton(e))mods|=0x1000;listener.postMouse(true,p.x,p.y,mods,e.getWhen());}
				@Override public void mouseReleased(MouseEvent e){if(fontPickRelease){fontPickRelease=false;return;}Point p=at(e);mouseDown=false;mouseGlobalH=p.x;mouseGlobalV=p.y;listener.postMouse(false,p.x,p.y,hostModifiers(e.getModifiersEx())|0x80,e.getWhen());}
				@Override public void mouseMoved(MouseEvent e){Point p=at(e);mouseGlobalH=p.x;mouseGlobalV=p.y;}
				@Override public void mouseDragged(MouseEvent e){Point p=at(e);mouseGlobalH=p.x;mouseGlobalV=p.y;}
			};
			screenCanvas.addMouseListener(mouse);screenCanvas.addMouseMotionListener(mouse);
			screenFrame.getContentPane().add(screenCanvas);
		}
		if(screenImage==null||screenImage.getWidth()!=w||screenImage.getHeight()!=h){
			screenImage=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);screenSerial=-1;screenCanvas.setPreferredSize(new Dimension(hostLen(w),hostLen(h)));screenCanvas.setSize(hostLen(w),hostLen(h));
			screenFrame.pack();screenFrame.setLocation(Math.max(0,hostX(frame[3])),Math.max(0,hostY(frame[4])-screenFrame.getInsets().top));}
		screenFrameGeometry=frame;
		if(screenMenuVersion!=menuVersion){if(!patchMenuBar(screenFrame.getJMenuBar())){screenFrame.setJMenuBar(buildMenuBar());screenFrame.revalidate();screenFrame.pack();}screenMenuVersion=menuVersion;}
		if(!screenFrame.isVisible()){screenFrame.setVisible(true);screenCanvas.requestFocusInWindow();}
		refreshScreenFrame(screen,frame);
		return true;
	}
	/** Converts the device rows written since the last refresh into the stand-in's image. EDT only. */
	private void refreshScreenFrame(QuickDraw68k screen,int[] frame){
		if(screenImage==null)return;int base=frame[0],rowBytes=frame[1],depth=frame[2],w=screenImage.getWidth(),h=screenImage.getHeight();
		byte[] raw=memory.raw();if(base<=0||base+(long)rowBytes*h>raw.length)return;
		int[] clut=null;try{clut=depth<=8?screen.mainDevicePalette(screenClut):null;}catch(M68kCpuException e){return;}
		boolean full=screenSerial<0||(clut!=null&&!java.util.Arrays.equals(clut,screenClut));if(clut!=null)screenClut=clut.clone();
		int[] gamma=screen.gammaLookup();boolean gray=screen.grayDisplay();int gammaVersion=screen.gammaVersion();if(gammaVersion!=screenGamma){screenGamma=gammaVersion;full=true;}
		int[] pixels=((java.awt.image.DataBufferInt)screenImage.getRaster().getDataBuffer()).getData();int first=-1,last=-1;long now=memory.writeSerial();
		for(int y=0;y<h;y++){int rowStart=base+y*rowBytes;if(!full&&memory.chunkSerial(rowStart,rowBytes)<=screenSerial)continue;readPixels(raw,rowStart,depth,clut,w,pixels,y*w);if(gray)applyGray(pixels,y*w,w);if(gamma!=null)applyGamma(gamma,pixels,y*w,w);if(first<0)first=y;last=y;}
		screenSerial=now;
		int[] smooth=refreshScreenSmooth(frame,clut,gamma,gray,full);if(smooth!=null){if(first<0||smooth[0]<first)first=smooth[0];last=Math.max(last,smooth[1]-1);}
		if(first>=0)screenCanvas.repaint(0,hostLen(first)-1,screenCanvas.getWidth(),hostLen(last+1)-hostLen(first)+2);
	}
	/** The stand-in's smooth zoom: the device's plane (what programs write there is all scaled up), shown as it is or through the gamma table. */
	private SmoothZoom.Plane screenSmoothPlane;private BufferedImage screenSmoothImage;private int[] screenSmoothPixels;private long[] screenSmoothShown;
	/** Brings {@link #screenSmoothImage} up to date (EDT); the rows to repaint, or null. */
	private int[] refreshScreenSmooth(int[] frame,int[] clut,int[] gamma,boolean gray,boolean full){
		SmoothZoom smooth=smoothZoom();int w=screenImage.getWidth(),h=screenImage.getHeight();
		SmoothZoom.Plane p=smooth==null||!smooth.active()?null:smooth.plane(memory.physical(frame[0]),frame[1],w,h,frame[2],true);
		if(p==null){if(screenSmoothPlane==null)return null;screenSmoothPlane=null;screenSmoothImage=null;screenSmoothPixels=null;return new int[]{0,h};}
		p.window=true;
		if(p!=screenSmoothPlane){screenSmoothPlane=p;screenSmoothShown=new long[h];screenSmoothImage=null;screenSmoothPixels=null;full=true;}
		if(full)java.util.Arrays.fill(screenSmoothShown,-1);
		p.sync(0,0,w,h,clut,SmoothZoom.HOST);
		int s=p.s,stride=w*s,first=-1,last=-1;
		boolean plain=gamma==null&&!gray;
		if(plain&&screenSmoothImage!=p.image()){screenSmoothImage=p.image();screenSmoothPixels=null;java.util.Arrays.fill(screenSmoothShown,-1);}
		if(!plain&&screenSmoothPixels==null){screenSmoothImage=new BufferedImage(stride,h*s,BufferedImage.TYPE_INT_RGB);screenSmoothPixels=((java.awt.image.DataBufferInt)screenSmoothImage.getRaster().getDataBuffer()).getData();java.util.Arrays.fill(screenSmoothShown,-1);}
		for(int y=0;y<h;y++){
			if(p.rowVersion[y]==screenSmoothShown[y])continue;screenSmoothShown[y]=p.rowVersion[y];
			if(!plain)for(int j=0;j<s;j++){int r=(y*s+j)*stride;System.arraycopy(p.hi,r,screenSmoothPixels,r,stride);if(gray)applyGray(screenSmoothPixels,r,stride);if(gamma!=null)applyGamma(gamma,screenSmoothPixels,r,stride);}
			if(first<0)first=y;last=y;
		}
		return first<0?null:new int[]{first,last+1};
	}
	void setTitle(final int record,final String title){
		final HostWindow hw=window(record);if(hw==null)return;hw.title=title;showTitle(hw);
	}
	private void showTitle(final HostWindow hw){
		final String title=hw==markedTitle?"/// "+hw.title+" ///":hw.title;
		runOnEdt(new Runnable(){@Override public void run(){if(hw.frame!=null)hw.frame.setTitle(title);else if(hw.dialog!=null)hw.dialog.setTitle(title);}},false);
	}
	/** The window whose host title shows "/// title ///": HyperCard's background mode, which real HyperCard shows by hatching the menu bar (MenuManager68k.showMenuBarHatching). */
	private volatile HostWindow markedTitle;
	/** Marks the front titled document window's host title (or no window's); the program's own title (GetWTitle) is unchanged. */
	void markFrontTitle(boolean marked){
		HostWindow target=null;
		if(marked)for(HostWindow hw:windowsFrontToBack())if(hw.visible&&hw.decorated&&hw.frame!=null&&!hw.systemWindow&&!hw.floatingLayer){target=hw;break;}
		HostWindow old=markedTitle;if(target==old)return;
		markedTitle=target;if(old!=null&&window(old.record)==old)showTitle(old);if(target!=null)showTitle(target);
	}
	/**
	 * A window moved: screen writes made before belong to what was on the screen there, not to the window. iTunes drags its
	 * window with an XOR outline drawn on the screen; the part of it outside the window was written to the device only, and
	 * once the window was moved over those pixels the adoption pass took the outline (drawn and erased again there) for the
	 * window's own drawing. Writes pending at the old position are adopted first; then the device bytes under the new
	 * position are forgotten - a later write elsewhere in the same 32x32 tile (the next drag's outline) would otherwise make
	 * a stale byte look new, and a black dotted line stayed inside the window.
	 */
	void moveWindow(final int record,final int left,final int top){moveWindow(record,left,top,false);}
	/** {@code byUser}: the user drags the window (DragWindow of a frame drawn in the window), see {@link HostWindow#userPlaced}. */
	void moveWindow(final int record,final int left,final int top,boolean byUser){moveWindow(record,left,top,byUser,0,0);}
	/** The frame the Window Manager re-placed, not moved (it was rebuilt or resized): where the user dragged it, it stays. */
	void moveFrame(int record,int left,int top){HostWindow hw=window(record);if(hw!=null&&hw.userPlaced)moveWindow(record,left,top,true,hw.dispX,hw.dispY);else moveWindow(record,left,top,false);}
	/** {@code dispX},{@code dispY}: for a window the user drags, how far from {@code left},{@code top} its frame is shown ({@link #placeForProgram}). */
	void moveWindow(final int record,final int left,final int top,boolean byUser,int dispX,int dispY){
		final HostWindow hw=window(record);if(hw==null)return;hw.userPlaced=byUser;
		// The frame the user drags goes exactly there: no displacement from before is added to it. A stale native move report had
		// made JumpStart's 643x505 Kaleidoscope-framed window (wider than the 640x480 screen) displaced, and every further step
		// of the drag added that displacement again - the window ran ahead of the pointer.
		if(byUser){hw.dispX=dispX;hw.dispY=dispY;if(dispX!=0||dispY!=0)anyDisplaced=true;hw.userDragUntil=System.nanoTime()+SCREEN_MOVE_QUIET_NANOS;}
		int[] kept=hw.keptFrom;hw.keptFrom=null;
		hw.insisted=!byUser&&(hw.insisted&&left==hw.contentLeft&&top==hw.contentTop||kept!=null&&left==kept[0]&&top==kept[1]);
		if((left!=hw.contentLeft||top!=hw.contentTop)&&hw.visible)adoptDeviceWrites(hw);
		hw.contentLeft=left;hw.contentTop=top;if(hw.visible)movedOverScreen(hw);
		reconsiderBackdrop(hw);
		runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null){placeWindow(hw);syncOutOfSight(hw);}updateScreenBar();}},false);
	}
	/**
	 * A window not shown yet is a backdrop or not by the place and size it has now: QuickHelp creates its help windows hidden
	 * over the whole screen and sizes them before showing them - kept as backdrops they had no title bar, and the game-screen
	 * frame came up round the screen. Shown windows keep what they are.
	 */
	private void reconsiderBackdrop(final HostWindow hw){
		if(hw.everShown||hw.customFrame)return;
		boolean backdrop=coversScreen(hw.contentLeft,hw.contentTop,hw.width,hw.height);
		if(backdrop==hw.backdrop)return;
		int wdef=hw.procId>>>4,kind=hw.procId&15;
		hw.backdrop=backdrop;hw.decorated=!backdrop&&!(wdef==0&&(kind==1||kind==2||kind==3));
		if(HOST_TRACE)System.err.println("[host] reconsiderBackdrop $"+Integer.toHexString(hw.record)+" -> "+(backdrop?"backdrop":"window"));
		if(!headless)runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null)rebuildWindow(hw);}},false);
	}
	/** The user moved the window natively (its position is already the new one): as {@link #moveWindow}. */
	void windowMovedByUser(int record){HostWindow hw=window(record);if(hw!=null&&hw.visible)movedOverScreen(hw);}
	private void movedOverScreen(HostWindow hw){
		hw.shownSerial=memory.writeSerial();
		QuickDraw68k screen=screenSource();int[] frame;try{frame=screen==null?null:screen.mainDeviceFrame();}catch(M68kCpuException e){frame=null;}
		if(frame!=null&&frame[0]>0)for(int y=0;y<hw.imageHeight;y++){int[] span=deviceSpan(frame,hw,y);if(span!=null)memory.forgetDeviceWrites(span[0],span[1]);}
	}
	/** Re-applies the Window Manager's stacking order to the native windows (back to front); EDT only. */
	private void restackLater(){runOnEdt(new Runnable(){@Override public void run(){restack();}},false);}
	/** Raises this program's windows over everything else (the launcher's dock): the screen frame or stand-in, then the Mac windows in their order. */
	void bringToFront(){
		runOnEdt(new Runnable(){@Override public void run(){
			if(screenFrame!=null&&screenFrame.isVisible()){screenFrame.toFront();if(screenCanvas!=null)screenCanvas.requestFocusInWindow();}
			if(standIn!=null&&standIn.isVisible())standIn.toFront();
			restack();
		}},false);
	}
	private void restack(){
		programmaticRaise=System.nanoTime();
		java.util.List<HostWindow> frontToBack=windowsFrontToBack();
		updateBackdropFocus(frontToBack);
		HostWindow first=null;for(HostWindow hw:frontToBack)if(hw.window!=null&&hw.window.isVisible()&&hw.visible&&!hw.behindHyperCard&&!hw.underCard){first=hw;break;}
		for(int i=frontToBack.size()-1;i>=0;i--){HostWindow hw=frontToBack.get(i);if(hw.window!=null&&hw.window.isVisible()&&hw.visible){if(hw.behindHyperCard)raiseUnderHyperCard(hw);else if(hw.underCard)raiseUnderCard(hw);else if(hw!=first&&!GMenu.isMacOSX())raiseWithoutActivating(hw);else hw.window.toFront();}}
		HostWindow front=frontToBack.isEmpty()?null:frontToBack.get(0);if(front!=null&&front.canvas!=null&&!front.behindHyperCard&&!front.underCard)front.canvas.requestFocusInWindow();
	}
	/** An Appearance floating window (WDEF 66 or 67 procIDs, 1056-1087, or System 7.5's WDEF 124, 1984-1999). */
	private static boolean floats(HostWindow hw){int wdef=hw.procId>>4;return wdef==66||wdef==67||wdef==124;}
	void selectWindow(final int record){
		final HostWindow hw=window(record);if(hw==null)return;hw.behindHyperCard=false;hw.underCard=false;
		synchronized(windows){order.remove(hw);int at=0;if(!hw.floatingLayer)while(at<order.size()&&order.get(at).floatingLayer)at++;order.add(at,hw);}
		if(HOST_TRACE)System.err.println("[host] selectWindow $"+Integer.toHexString(record)+" -> "+describeOrder());
		runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null&&hw.window.isVisible()){setFocusable(hw,true);hw.window.toFront();hw.canvas.requestFocusInWindow();}
			if(provisional==hw)activateHost(hw); // the program answered the click by selecting the window: the held activation stands
			else activateIfFrontmost(hw);}},false);
	}
	/**
	 * Stacks the window where the Window Manager put it (SendBehind, NewWindow's behind argument): behind = -1 in
	 * front, 0 (or a window this host does not know) at the back, else right behind that window. Some externals
	 * create a full-screen backdrop hidden, SendBehind it behind the card window and only then show it; the
	 * host's own stacking list has to follow, or the next restack raises the backdrop over the card.
	 */
	/** When the last BringToFront restacked the windows (System.nanoTime). */
	private volatile long quietRaise;
	/** BringToFront: {@link #placeBehind} without the activation the OS gives the window it raises (see {@link #activateHost}). */
	void bringToFront(int record,int behind){HostWindow hw=window(record);quietRaisedHidden=hw!=null&&!hw.visible?record:0;quietRaise=System.nanoTime();placeBehind(record,behind);}
	/** The window the last BringToFront raised while it was hidden (0 when it was visible): the ShowWindow that follows makes it
	 *  the active window, as the ROM's ShowWindow of the front window does - Resorcerer opens each file window with GetNewDialog
	 *  (invisible), BringToFront, ShowWindow, and its lists stayed inactive (no selection shown) when that activation was ignored. */
	private volatile int quietRaisedHidden;
	void placeBehind(final int record,final int behind){
		final HostWindow hw=window(record);if(hw==null)return;
		final boolean wasFront=hw==frontWindow;
		synchronized(windows){
			order.remove(hw);HostWindow ref=behind==-1||behind==0?null:windows.get(behind);
			if(behind==-1)order.add(0,hw);else if(ref==null)order.add(hw);else order.add(Math.min(order.size(),order.indexOf(ref)+1),hw);
			// an external's window sent to the back, or behind one of HyperCard's (a record this host does not know), goes under the card window
			hw.behindHyperCard=external&&behind!=-1&&(ref==null||ref.behindHyperCard); // behind a window that is behind the card: behind the card too
			if(behind==-1)hw.underCard=false; // brought in front by the program
		}
		if(HOST_TRACE)System.err.println("[host] placeBehind $"+Integer.toHexString(record)+" behind $"+Integer.toHexString(behind)+" -> "+describeOrder());
		runOnEdt(new Runnable(){@Override public void run(){
			if(hw.window!=null&&hw.window.isVisible()&&hw.visible){if(hw.behindHyperCard)raiseUnderHyperCard(hw);else if(behind==0)hw.window.toBack();else restack();}
			// the active window sent behind another visible one hands activation to the new front window
			if(wasFront&&hw.activePosted){HostWindow next=null;synchronized(windows){for(HostWindow other:order){if(other==hw)break;if(other.visible){next=other;break;}}}if(next!=null){deactivateHost(hw);activateIfFrontmost(next);}}}},false);
	}
	/** Stacks an external's window right under HyperCard's windows (EDT): over every other program's, as on a Mac where the
	 *  active application's windows are all in front, with the card window (and the windows it owns: palettes, the message
	 *  box) back over it. Never focused - HyperCard's front window keeps the keyboard, and toFront then only orders. */
	/** Windows and Linux activate a window that toFront() raises (macOS only orders it): a window behind the front one is raised
	 *  unfocusable, so that restacking hands the OS activation to the front window alone. Raising the windows behind a modal
	 *  dialog activated them one by one, each activation was refused by the dialog and restacked again: the dialog flickered
	 *  behind the document window for good and never kept the keyboard (EDT). */
	private static void raiseWithoutActivating(HostWindow hw){
		boolean focusable=hw.window.getFocusableWindowState();
		if(focusable)hw.window.setFocusableWindowState(false);
		hw.window.toFront();
		if(focusable)hw.window.setFocusableWindowState(true);
	}
	/**
	 * `doMenu "next window"` put HyperCard's card window in front of {@code w}, one of this host's windows: it stays behind the
	 * card from now on and takes no focus (xWindowFrame's black backdrop around Myst's card; when the 256-colour alert closed,
	 * macOS activated the backdrop over the card and the stack sat black, deaf to clicks). False when {@code w} is not ours. EDT.
	 */
	boolean sendBehindHyperCard(java.awt.Window w){
		HostWindow found=null;synchronized(windows){for(HostWindow hw:windows.values())if(hw.window==w){found=hw;break;}}
		if(found==null)return false;
		found.behindHyperCard=true;
		if(found.window.isVisible()&&found.visible)raiseUnderHyperCard(found);else setFocusable(found,false);
		return true;
	}
	/**
	 * `doMenu "next window"` with the program's shown windows that surround the card window (a backdrop: ColorCover's black
	 * cover, xWindowFrame's frame): they go behind the card, as the first window the item sends back. The native window may not
	 * be up yet - ColorCover calls the item from inside the XCMD right after ShowWindow, and Daisuki♡Ver2 opened over another
	 * stack (card window active) stayed black. False when none. EDT.
	 */
	boolean sendBackdropsBehindHyperCard(Rectangle card){
		java.util.List<HostWindow> all;synchronized(windows){all=new ArrayList<HostWindow>(windows.values());}
		boolean any=false;
		for(HostWindow hw:all)if(hw.visible&&!hw.behindHyperCard&&hw.window!=null&&hw.window.getBounds().contains(card)){sendBehindHyperCard(hw.window);any=true;}
		return any;
	}
	private void raiseUnderHyperCard(HostWindow hw){
		setFocusable(hw,false);hw.window.toFront();
		Window card=PCARD.pc;if(card!=null&&card.isShowing())card.toFront();
	}
	/**
	 * A backdrop with another of the program's windows in front of it cannot take the focus (EDT). A click on it then reaches the
	 * program without macOS activating and raising it: clicked twice within half a second (MacPaint's tool, then its pattern),
	 * the second activation read as the echo of the restack that followed the first and left the desk over the document.
	 */
	private void updateBackdropFocus(java.util.List<HostWindow> frontToBack){
		boolean covered=false;
		for(HostWindow hw:frontToBack){if(hw.window==null||!hw.visible||outOfSight(hw))continue;if(hw.backdrop&&!hw.behindHyperCard)setFocusable(hw,!covered);covered=true;}
	}
	/** Changes the native window's focusability only when it differs: the peer restyles the NSWindow on every call. */
	private static void setFocusable(HostWindow hw,boolean focusable){focusable&=!hw.windoid;if(hw.window.getFocusableWindowState()!=focusable)hw.window.setFocusableWindowState(focusable);}
	/** Debug aid (commands.txt `focusinfo`): which host window has the focus and which may take it. */
	String describeFocus(){
		final StringBuilder sb=new StringBuilder();
		runOnEdt(new Runnable(){@Override public void run(){
			java.awt.KeyboardFocusManager k=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();
			sb.append("active=").append(k.getActiveWindow()==null?"null":k.getActiveWindow().getName()).append(" focusOwner=").append(k.getFocusOwner()==null?"null":k.getFocusOwner().getClass().getSimpleName());
			for(HostWindow hw:windowsFrontToBack())if(hw.window!=null)sb.append(" | $").append(Integer.toHexString(hw.record)).append(hw.window.isVisible()?"":" hidden").append(" focusableState=").append(hw.window.getFocusableWindowState()).append(" focusable=").append(hw.window.isFocusableWindow()).append(" focused=").append(hw.window.isFocused()).append(hw.backdrop?" backdrop":"").append(" name=").append(hw.window.getName()).append(hw.frame==null?" dialog":" menus="+(hw.frame.getJMenuBar()==null?"none":String.valueOf(hw.frame.getJMenuBar().getMenuCount())));
		}},true);
		return sb.toString();
	}
	private String describeOrder(){StringBuilder sb=new StringBuilder();synchronized(windows){for(HostWindow w:order)sb.append(sb.length()==0?"$":" $").append(Integer.toHexString(w.record)).append(w.visible?"":"(hidden)");}return sb.toString();}
	void sendBehind(final int record){placeBehind(record,0);}
	/** Resizes the host window and remaps the image over the (new) pixel buffer. */
	void resizeWindow(final int record,final int width,final int height,final int pixelBase,final int rowBytes,final int depth){
		final HostWindow hw=window(record);if(hw==null)return;
		if(HOST_TRACE)System.err.println("[host] resizeWindow $"+Integer.toHexString(record)+" "+width+"x"+height+" base $"+Integer.toHexString(pixelBase)+" depth "+depth);
		if(!headless)memory.untrackBuffer(hw.imageBase,hw.imageRowBytes*hw.imageHeight);
		hw.width=width;hw.height=height;hw.image=depth==32?imageOver(pixelBase,width,height,rowBytes):null;hw.imageDepth=depth;hw.imageBase=pixelBase;hw.imageWidth=width;hw.imageHeight=height;hw.imageRowBytes=rowBytes;hw.resizesPending++;
		if(!headless)memory.trackBuffer(pixelBase,rowBytes*height);
		reconsiderBackdrop(hw);
		runOnEdt(new Runnable(){@Override public void run(){try{if(hw.window==null)return;hw.ignoreMoves=true;hw.canvas.setPreferredSize(new Dimension(width,height));packWindow(hw);placeWindow(hw);hw.ignoreMoves=false;hw.canvas.repaint();restack();syncOutOfSight(hw);updateScreenBar();if(HOST_TRACE)System.err.println("[host] after pack $"+Integer.toHexString(record)+" frame "+hw.window.getBounds()+" canvas "+hw.canvas.getSize());}finally{hw.resizesPending--;}}},false);
	}
	void remapImage(final int record,final int pixelBase,final int rowBytes){
		HostWindow hw=window(record);if(hw==null)return;
		if(!headless)memory.untrackBuffer(hw.imageBase,hw.imageRowBytes*hw.imageHeight);
		hw.image=imageOver(pixelBase,hw.width,hw.height,rowBytes);hw.imageBase=pixelBase;hw.imageRowBytes=rowBytes;
		if(!headless)memory.trackBuffer(pixelBase,rowBytes*hw.height);
	}
	void setOverlay(int record,Image image){if(image==null)overlays.remove(record);else overlays.put(record,image);HostWindow hw=window(record);if(hw!=null&&hw.canvas!=null)hw.canvas.repaint();}
	Image overlayFor(HostWindow hw){return overlays.get(hw.record);}

	/** Refresh tick (EDT): converts the rows that changed since the previous tick and repaints just those; true when anything changed. */
	boolean repaintAll(){
		if(disposed)return false;boolean changed=false;
		if(screenFrame!=null&&screenFrame.isVisible()){QuickDraw68k screen=screenSource();int[] frame=null;try{if(screen!=null)frame=screen.mainDeviceFrame();}catch(M68kCpuException e){frame=null;}if(frame!=null){refreshScreenFrame(screen,frame);changed=true;}
			if(saverMode){checkSaverEnd(frame);
				if(saverMode){boolean windowUp=macWindowOnScreen();
					if(windowUp&&screenFrame.isAlwaysOnTop()){screenFrame.setAlwaysOnTop(false);restack();} // a game that draws the whole screen opened an alert (Dark Forces' "Are you sure you want to quit?"): over the screen, not under it
					else if(!windowUp&&!screenFrame.isAlwaysOnTop()){screenFrame.setAlwaysOnTop(true);screenFrame.toFront();}
					else if(!windowUp&&!screenFrame.isFocused())screenFrame.toFront();}}} // the program's windows, activated, come forward again on macOS: the saver's screen stays over them
		else if(extensionsDone&&screenSource()!=null&&memory.deviceWritten()&&memory.deviceSerial()>deviceBaseline){boolean anyVisible=false;for(HostWindow hw:windowsFrontToBack())if(hw.visible&&hw.window!=null&&hw.window.isVisible()){anyVisible=true;break;}if(!anyVisible)updateStandIn();else checkSaverStart();} // direct drawing began with no window open
		for(HostWindow hw:windowsFrontToBack()){if(hw.canvas==null||!hw.visible||!hw.canvas.isShowing())continue;
			int[] rows;try{rows=refreshDisplay(hw);}catch(RuntimeException e){rows=null;hw.canvas.repaint();}
			if(rows!=null){hw.canvas.repaint(0,hostLen(rows[0])-1,hw.canvas.getWidth(),hostLen(rows[1])-hostLen(rows[0])+2);changed=true;}}
		return changed;
	}
	// ------------------------------------------------------------------ screen-saver mode

	/**
	 * A program painting (nearly) the whole screen outside its windows while they are up is a screen saver at work (After Dark's
	 * modules, run from its patched SystemTask/GetNextEvent, draw through a port covering the screen): the screen frame then
	 * shows the device in front of the windows, as the real screen looked, until the program draws in a window again - the
	 * saver's wake-up invalidates the windows, and their redraw is what ends the mode.
	 */
	private boolean saverMode;private long saverWindowSerial=-1,saverWindowStamp;
	boolean screenSaverMode(){return saverMode;}
	private static final double SAVER_COVERAGE=0.9;
	/** The part of the screen that must lie outside the windows for a saver to be told apart: Kid Pix's windows leave only a
	 *  corner of its 640x480 screen (5 tiles of 300), and the one dimming pass of its Wacky TV wrote that whole "outside". */
	private static final double SAVER_MIN_OUTSIDE=0.1;
	/** Tile bands (32 rows) with a tile outside every visible window written since {@code since}, over the bands that have such
	 *  tiles, then the tiles outside the windows and all the screen's tiles: {written, total, outsideTiles, allTiles}. */
	private int[] outsideWindowBands(int[] frame,long since){
		int base=frame[0],rowBytes=frame[1],depth=frame[2],left=frame[3],top=frame[4],right=frame[5],bottom=frame[6];
		java.util.List<int[]> covers=new java.util.ArrayList<int[]>();
		// the windows the program has up, whether or not the native frame is showing yet: MacPaint draws its full-screen desk right
		// after ShowWindow, and a refresh tick in between took those writes for a screen saver's
		for(HostWindow hw:windowsFrontToBack())if(hw.visible)covers.add(new int[]{hw.contentLeft,hw.contentTop,hw.contentLeft+hw.imageWidth,hw.contentTop+hw.imageHeight});
		int pixelsPerTile=Math.max(1,32*8/Math.max(1,depth)),written=0,total=0,outside=0,all=0;
		for(int y=top;y<bottom;y+=32){
			boolean any=false,hit=false;
			for(int x=left;x<right;x+=pixelsPerTile){
				all++;int x1=Math.min(right,x+pixelsPerTile),y1=Math.min(bottom,y+32);boolean covered=false;
				for(int[] c:covers)if(x<c[2]&&x1>c[0]&&y<c[3]&&y1>c[1]){covered=true;break;}
				if(covered)continue;any=true;outside++;
				if(!hit&&memory.tileSerialAt(base+(y-top)*rowBytes+((x-left)*depth>>>3))>since)hit=true;
			}
			if(any){total++;if(hit)written++;}
		}
		return new int[]{written,total,outside,all};
	}
	private void checkSaverStart(){
		long now=memory.deviceSerial();
		// Outlines a program XORs on the screen while the button is down (iTunes' own grow and drag outlines, DragGrayRgn,
		// GrowWindow's wGrow) cross most rows of the screen but are no saver: writes up to the button's release never count
		// (the screen frame came up in front of iTunes after a resize or a move, titled with its name, and stayed).
		if(isMouseDown()){saverQuietSerial=now;saverWindowSerial=-1;return;}
		long stamp=System.currentTimeMillis();if(saverWindowSerial<0||stamp-saverWindowStamp>500){saverWindowSerial=saverWindowPrevious;saverWindowPrevious=now;saverWindowStamp=stamp;} // writes of roughly the last half second to second count
		if(saverWindowSerial<0)return;
		QuickDraw68k screen=screenSource();int[] frame;try{frame=screen.mainDeviceFrame();}catch(M68kCpuException e){return;}
		if(frame==null)return;
		int[] bands=outsideWindowBands(frame,Math.max(Math.max(saverWindowSerial,deviceBaseline),saverQuietSerial));
		if(bands[1]==0||bands[0]<bands[1]*SAVER_COVERAGE||bands[2]<bands[3]*SAVER_MIN_OUTSIDE)return;
		saverMode=true;saverRestored=false;
		if(HOST_TRACE)System.err.println("[host] screen saver drawing: "+bands[0]+" of "+bands[1]+" bands outside the windows written");
		if(updateScreenFrame()){screenFrame.setAlwaysOnTop(true);screenFrame.toFront();if(screenCanvas!=null)screenCanvas.requestFocusInWindow();}
		updateScreenBar();
	}
	private long saverWindowPrevious;
	/** Device write serial when the mouse button was last seen down or a window was last shown: screen writes before it are
	 *  tracking outlines or the screen made ready for the windows, not a saver. */
	private volatile long saverQuietSerial;
	/** The program painted the desktop behind its windows (PaintOne/PaintBehind): a screen saver waking up restores the screen so. */
	private volatile boolean saverRestored;
	void screenSaverRestored(){if(saverMode)saverRestored=true;}
	/** The saver is over once the program restored the desktop; the windows redraw from their update events. */
	private void checkSaverEnd(int[] frame){
		if(!saverRestored)return;
		saverMode=false;saverRestored=false;saverWindowSerial=-1;
		if(Mac68kApi.TRACE)System.err.println("[host] screen saver over: the desktop was painted back");
		screenFrame.setAlwaysOnTop(false);screenFrame.setVisible(false);
		deviceBaseline=memory.deviceSerial();memory.forgetAllDeviceWrites();
		restack();updateScreenBar();
	}

	/** Debug aid: repaints every window from scratch on the next tick. */
	void invalidateDisplays(){for(HostWindow hw:windowsFrontToBack())hw.display=null;}
	void repaintNow(int record){final HostWindow hw=window(record);if(hw!=null&&hw.canvas!=null)runOnEdt(new Runnable(){@Override public void run(){try{refreshDisplay(hw);}catch(RuntimeException e){}hw.canvas.paintImmediately(0,0,hw.canvas.getWidth(),hw.canvas.getHeight());}},false);}

	// ------------------------------------------------------------ Fix Garbled Text

	/** "Fix Garbled Text…" waits for the click on the garbled text (its release is swallowed too), and then tries fonts there (MacApp68k.fixGarbledText). */
	private volatile boolean fontPick,fontSearching;private boolean fontPickRelease;private javax.swing.JWindow fontPickHint;
	/** "Fix Garbled Text…": the pointer becomes a cross and the next click in one of the program's windows names the place (Esc gives up). */
	void pickGarbledText(){
		runOnEdt(new Runnable(){@Override public void run(){
			fontPick=true;applyCursor();
			HostWindow front=frontWindow;if(front==null||front.canvas==null||!front.canvas.isShowing())return;
			if(fontPickHint!=null)fontPickHint.dispose();
			JLabel hint=new JLabel(International.t("Click the garbled text. (Esc cancels)"));
			hint.setOpaque(true);hint.setBackground(new Color(0xffffe1));hint.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(Color.GRAY),javax.swing.BorderFactory.createEmptyBorder(3,6,3,6)));
			fontPickHint=new javax.swing.JWindow(front.window);fontPickHint.setFocusableWindowState(false);fontPickHint.getContentPane().add(hint);fontPickHint.pack();
			Point at=front.canvas.getLocationOnScreen();fontPickHint.setLocation(at.x+8,at.y+8);fontPickHint.setVisible(true);}},false);
	}
	private void endFontPick(){fontPick=false;if(fontPickHint!=null){fontPickHint.dispose();fontPickHint=null;}applyCursor();}
	/** The program's fonts are being tried: a busy pointer meanwhile. */
	void fontSearching(boolean busy){fontSearching=busy;applyCursor();}
	/** Pixels of the program's image from host (canvas) pixels: the zoom undone. */
	private int imageLen(int host){return (int)((long)host*100/scPct);}
	/** The window buffer's pixels (xRGB) in a rectangle of its image, clipped to it; null when it has none. */
	int[] windowPixels(int record,int x,int y,int w,int h){
		HostWindow hw=window(record);if(hw==null||hw.imageBase<=0)return null;
		int left=Math.max(0,x),top=Math.max(0,y),right=Math.min(hw.imageWidth,x+w),bottom=Math.min(hw.imageHeight,y+h);
		byte[] raw=memory.raw();if(right<=left||bottom<=top||hw.imageBase+(long)hw.imageRowBytes*bottom>raw.length)return null;
		int[] clut=hw.imageDepth<=8?devicePalette(hw.imageDepth):null;int[] row=new int[hw.imageWidth],out=new int[(right-left)*(bottom-top)];
		for(int r=top;r<bottom;r++){readPixels(raw,hw.imageBase+r*hw.imageRowBytes,hw.imageDepth,clut,hw.imageWidth,row);System.arraycopy(row,left,out,(r-top)*(right-left),right-left);}
		return out;
	}
	/** "Fix Garbled Text…" found the font: what changed, and a way back. */
	void garbledTextFixed(final String font,final String replacement,final Runnable undo){
		runOnEdt(new Runnable(){@Override public void run(){
			String ok=International.t("OK"),back=International.t("Undo the Change");
			int choice=javax.swing.JOptionPane.showOptionDialog(ownerFrame(),International.t("Text in {0} is now shown in {1}.",font,replacement),International.t("Fix Garbled Text"),
				javax.swing.JOptionPane.DEFAULT_OPTION,javax.swing.JOptionPane.INFORMATION_MESSAGE,null,new Object[]{ok,back},ok);
			if(choice==1)undo.run();}},false);
	}
	void garbledTextNotFound(){
		runOnEdt(new Runnable(){@Override public void run(){
			javax.swing.JOptionPane.showMessageDialog(ownerFrame(),International.t("No text a font change fixes was found there. Click right on the garbled characters."),International.t("Fix Garbled Text"),javax.swing.JOptionPane.INFORMATION_MESSAGE);}},false);
	}

	// ------------------------------------------------------------ cursor

	void setCursor(final Cursor c){cursor=c;cursorSetByProgram=true;applyCursor();}
	/** Whether the program set a cursor (an external's ShowCursor alone gives HyperCard's back), and the card's before a HideCursor. */
	private volatile boolean cursorSetByProgram;private Cursor cardCursorBeforeHide;
	void setCursorHidden(boolean hidden){cursorHidden=hidden;applyCursor();}
	/** One queued cursor update at a time: programs call SetCursor on every idle (HyperCard's script editor does), and each
	 *  host setCursor of a new custom cursor registers its images with the window server on the AppKit thread; a backlog of
	 *  those starved every other AppKit call (menu bar, screen insets) and froze the program. */
	private final java.util.concurrent.atomic.AtomicBoolean cursorPending=new java.util.concurrent.atomic.AtomicBoolean();
	private void applyCursor(){
		if(!cursorPending.compareAndSet(false,true))return;
		runOnEdt(new Runnable(){@Override public void run(){cursorPending.set(false);Cursor effective=fontSearching?Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR):fontPick?Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR):cursorHidden?blankCursor():cursor;
			for(HostWindow hw:windowsFrontToBack())if(hw.canvas!=null&&hw.canvas.getCursor()!=effective)hw.canvas.setCursor(effective);
			// an external's SetCursor/SetCCursor/HideCursor is HyperCard's cursor, over the card as `set cursor` (XTernal Collection's
			// ColorCursor animation showed nothing: an external's VM often has no window of its own); HyperTalk's idle puts the browse
			// cursor back once the handlers are done, as HyperCard does. ShowCursor without a cursor of the external's own shows
			// the one HyperCard had
			PCARD pc=PCARD.pc;
			if(external&&!headless&&pc!=null&&pc.isShowing()){Cursor target;
				if(cursorHidden){if(cardCursorBeforeHide==null)cardCursorBeforeHide=pc.getCursor();target=blankCursor();}
				else{target=cursorSetByProgram?cursor:cardCursorBeforeHide;cardCursorBeforeHide=null;}
				if(target!=null&&pc.getCursor()!=target)pc.setCursor(target);}}},false);
	}
	private static Cursor blank;
	static Cursor blankCursor(){
		if(blank==null){BufferedImage img=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);blank=Toolkit.getDefaultToolkit().createCustomCursor(img,new Point(0,0),"blank");}
		return blank;
	}
	/** Builds a host cursor from classic CURS data (16x16 1-bit data + mask + hotspot). */
	static Cursor cursorFromData(byte[] data){
		if(data==null||data.length<68)return Cursor.getDefaultCursor();
		// the same CURS gives the same Cursor object, so repeated SetCursor calls are no-ops for the host (see applyCursor)
		String key=new String(data,0,68,java.nio.charset.StandardCharsets.ISO_8859_1);
		synchronized(cursorCache){Cursor c=cursorCache.get(key);if(c==null){c=buildCursor(data);cursorCache.put(key,c);}return c;}
	}
	private static final java.util.Map<String,Cursor> cursorCache=new java.util.LinkedHashMap<String,Cursor>(16,0.75f,true){
		@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Cursor> e){return size()>64;}};
	/** A colour cursor: the 16x16 colours where the 1-bit mask is set; outside it a Mac XORs the colours that are not white with
	 *  the screen - drawn in their own colours here, as they look on a white card (OxSetCCursor's crsr 132/133 have an empty
	 *  mask: the pointer vanished) - and else the 1-bit data's black as usual. */
	static Cursor colorCursor(int[] colors,byte[] data){
		StringBuilder key=new StringBuilder("c").append(new String(data,0,68,java.nio.charset.StandardCharsets.ISO_8859_1));for(int c:colors)key.append((char)(c>>>12)).append((char)(c&0xfff));
		if(java.awt.GraphicsEnvironment.isHeadless())return null;
		synchronized(cursorCache){Cursor c=cursorCache.get(key.toString());if(c!=null)return c;
			Dimension best=Toolkit.getDefaultToolkit().getBestCursorSize(16,16);int w=Math.max(16,best.width),h=Math.max(16,best.height);
			BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
			for(int y=0;y<16;y++){int bits=(data[y*2]&0xff)<<8|(data[y*2+1]&0xff),mask=(data[32+y*2]&0xff)<<8|(data[32+y*2+1]&0xff);
				for(int x=0;x<16;x++){int bit=1<<(15-x),color=colors[y*16+x]&0xffffff;
					if((mask&bit)!=0||color!=0xffffff)img.setRGB(x,y,0xff000000|color);else if((bits&bit)!=0)img.setRGB(x,y,0xff000000);}}
			int hotV=(short)((data[64]&0xff)<<8|(data[65]&0xff)),hotH=(short)((data[66]&0xff)<<8|(data[67]&0xff));
			try{c=Toolkit.getDefaultToolkit().createCustomCursor(img,new Point(Math.max(0,Math.min(15,hotH)),Math.max(0,Math.min(15,hotV))),"crsr");}catch(Exception e){c=cursorFromData(data);}
			cursorCache.put(key.toString(),c);return c;}
	}
	private static Cursor buildCursor(byte[] data){
		if(java.awt.GraphicsEnvironment.isHeadless())return null; // no cursors without a display (headless tests)
		Dimension best=Toolkit.getDefaultToolkit().getBestCursorSize(16,16);
		int w=Math.max(16,best.width),h=Math.max(16,best.height);
		BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);int opaque=0;
		for(int y=0;y<16;y++){int bits=(data[y*2]&0xff)<<8|(data[y*2+1]&0xff);int mask=(data[32+y*2]&0xff)<<8|(data[32+y*2+1]&0xff);opaque|=bits|mask;
			for(int x=0;x<16;x++){int bit=1<<(15-x);if((mask&bit)!=0)img.setRGB(x,y,(bits&bit)!=0?0xff000000:0xffffffff);else if((bits&bit)!=0)img.setRGB(x,y,0xff000000);}} // data outside the mask inverts the screen on a Mac; drawn black here
		if(opaque==0)return Cursor.getDefaultCursor(); // nothing to show (a cursor read through a nil handle): the arrow beats a vanished cursor
		int hotV=(short)((data[64]&0xff)<<8|(data[65]&0xff)),hotH=(short)((data[66]&0xff)<<8|(data[67]&0xff));
		try{return Toolkit.getDefaultToolkit().createCustomCursor(img,new Point(Math.max(0,Math.min(15,hotH)),Math.max(0,Math.min(15,hotV))),"curs");}
		catch(Exception e){return Cursor.getDefaultCursor();}
	}

	// ------------------------------------------------------------ menus

	void setMenuBar(List<MenuModel> menus){
		// Programs adjust their menus (EnableItem/DisableItem/CheckItem) on every pass of their event loop, so most flushes carry
		// the same menu bar. Rebuilding the host JMenuBar anyway tears down the native screen menus of macOS while the user is
		// pulling one down (the menu could vanish or freeze open), so an unchanged bar is kept.
		if(menuBar!=null&&menuBar.size()==menus.size()){boolean same=true;for(int i=0;i<menus.size()&&same;i++)same=menus.get(i).sameAs(menuBar.get(i));if(same)return;}
		menuBar=menus;menuVersion++;
		if(Mac68kApi.TRACE){StringBuilder b=new StringBuilder("[68K] menu bar:");for(MenuModel m:menus){b.append(' ').append(m.title).append('[');for(ItemModel i:m.items)b.append(i.separator?"-":i.text).append(i.enabled?"":"(off)").append(',');b.append(']');}System.err.println(b);}
		runOnEdt(new Runnable(){@Override public void run(){if(openMenus>0){rebuildWhenClosed=true;return;}rebuildMenuBars();}},false);
	}
	/** Host menus open right now (a JMenu of the bar is showing): a whole new bar would tear the open one down, so the rebuild
	 *  waits until it closes and the open menu gets its new items in place ({@link #refillOpenMenu}). */
	private int openMenus;private boolean rebuildWhenClosed;
	/** Whether the program changes menus while they are pulled down (MenuManager68k.updatesMenusOnOpening). */
	volatile boolean menusUpdateOnOpening;
	private void rebuildMenuBars(){
		{java.util.List<HostWindow> backToFront=windowsFrontToBack();java.util.Collections.reverse(backToFront); // re-shown back to front so the stacking order survives
			// Only the windows on screen are rebuilt: a hidden window gets the current bar when it is shown (updateMenuBarFor), which keeps a
			// program with many hidden windows from building a native menu tree for each one on every menu change.
			for(HostWindow hw:backToFront){if(hw.window==null||!hw.window.isVisible()||patchWindowBar(hw))continue;if(hw.dialog!=null&&!menuBar.isEmpty())rebuildAsFrame(hw);else applyMenuBar(hw);hw.window.revalidate();hw.window.repaint();}updateStandIn();}
	}
	/**
	 * The program recalculated a standard window's regions for another variant of WDEF 0 (HyperCard's `hide titleBar` calls the
	 * card window's WDEF with plainDBox, `show titleBar` with its own zoomNoGrow again): the native frame gains or loses its
	 * title bar. The content stays where it is on the screen.
	 */
	void setWindowVariant(final int record,final int kind){
		final HostWindow hw;synchronized(windows){hw=windows.get(record);}
		if(hw==null||hw.customFrame||(hw.procId>>>4)!=0||(hw.procId&15)==kind)return;
		hw.procId=(hw.procId&~15)|kind;
		final boolean decorated=!hw.backdrop&&!(kind==1||kind==2||kind==3);
		if(HOST_TRACE)System.err.println("[host] setWindowVariant $"+Integer.toHexString(record)+" variant "+kind+(decorated==hw.decorated?"":decorated?" -> titled":" -> bare"));
		if(decorated==hw.decorated)return;
		hw.decorated=decorated;if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null)rebuildWindow(hw);}},false);
	}
	/** The program put a definition of its own in a standard window's windowDefProc (WindowManager68k.swappedDefinition): the
	 *  native window becomes a bare one whose frame that definition draws into the buffer. */
	void setCustomFrame(final int record){
		final HostWindow hw;synchronized(windows){hw=windows.get(record);}
		if(hw==null||hw.customFrame)return;
		if(HOST_TRACE)System.err.println("[host] setCustomFrame $"+Integer.toHexString(record));
		hw.customFrame=true;hw.decorated=false;hw.growable=false;hw.resizable=false;
		if(headless)return;
		runOnEdt(new Runnable(){@Override public void run(){if(hw.window!=null)rebuildWindow(hw);}},false);
	}
	/** A dialog-kind window created before the application installed its menu bar becomes a frame so the menus show up. */
	private void rebuildAsFrame(HostWindow hw){
		if(HOST_TRACE)System.err.println("[host] rebuildAsFrame $"+Integer.toHexString(hw.record)+" "+hw.width+"x"+hw.height+" image "+(hw.image!=null));
		rebuildWindow(hw);
	}
	/** Replaces the native window by one built for the window's current kind (frame or dialog, decorated or bare). */
	private void rebuildWindow(HostWindow hw){
		boolean wasVisible=hw.window.isVisible();hw.window.setVisible(false);hw.window.dispose();hw.frame=null;hw.dialog=null;hw.window=null;hw.canvas=null;hw.menuBarVersion=-1;
		buildWindow(hw);if(hw.menuBarVersion!=menuVersion)applyMenuBar(hw);if(wasVisible){hw.ignoreMoves=true;hw.window.setVisible(true);placeWindow(hw);hw.ignoreMoves=false;hw.canvas.requestFocusInWindow();
			restack();} // the new frame came up in front of everything: MacPaint's desk, rebuilt when the menu bar arrived, hid the document window
	}
	/** Brings a window's menu bar up to date (the bar of a hidden window is left stale: see {@link #setMenuBar}). */
	private void updateMenuBarFor(HostWindow hw){if(hw.window!=null&&hw.menuBarVersion!=menuVersion&&!patchWindowBar(hw)){if(hw.dialog!=null&&!menuBar.isEmpty())rebuildAsFrame(hw);else applyMenuBar(hw);hw.window.revalidate();}}
	/**
	 * The menu bar of a frame just built. On macOS a frame's bar only reaches the screen menu bar, so a window built hidden
	 * (every window is; the program shows it afterwards) gets its bar when it is shown - a program that keeps a hundred hidden
	 * windows (a HyperCard stack with a picture window per character) would otherwise build a hundred native menu trees.
	 * Elsewhere the bar is part of the frame and sizes it, so it is installed before pack.
	 */
	private void installMenuBarAtBuild(HostWindow hw){if(!GMenu.isMacOSX())applyMenuBar(hw);}
	/** Installs the current menu bar on a window: frames always carry one, dialogs only while the application has menus. */
	private void applyMenuBar(HostWindow hw){
		hw.menuBarVersion=menuVersion;
		if(hw.frame!=null&&overlaysMenuBar(hw)){
			if(hw.frame.getJMenuBar()!=null){hw.frame.setJMenuBar(null);packWindow(hw);}
			JLayeredPane layers=hw.frame.getLayeredPane();if(hw.overlayBar!=null)layers.remove(hw.overlayBar);
			hw.overlayBar=carriesMenuBar(hw)?buildMenuBar():null;
			if(hw.overlayBar!=null){layers.add(hw.overlayBar,JLayeredPane.PALETTE_LAYER);anyOverlayBar=true;}
			placeWindow(hw);layers.repaint();return;
		}
		if(hw.frame!=null){int before=menuBarHeight(hw);hw.frame.setJMenuBar(carriesMenuBar(hw)?buildMenuBar():null);if(menuBarHeight(hw)!=before)packWindow(hw);placeWindow(hw);if(needsShape(hw))applyShape(hw);return;} // a bar of another height (one installed after pack) would eat into the canvas
		if(hw.dialog==null)return;JMenuBar bar=buildMenuBar();
		hw.dialog.setJMenuBar(bar.getMenuCount()==0||!carriesMenuBar(hw)?null:bar); // shown in-window on platforms without a screen menu bar
		if(needsShape(hw))applyShape(hw);
	}
	/**
	 * Whether the window's menu bar lies over its content instead of above it: a backdrop (a window covering the screen, menu-bar
	 * band included) on a platform without a screen menu bar. On a Mac the menu bar covers the top of such a window; a bar above
	 * the content would sit above the display with the content's top under it (F1GP showed neither its menus nor the top of
	 * its picture). The bar takes the band's place, over the first rows of the content, as the Mac's does.
	 */
	private boolean overlaysMenuBar(HostWindow hw){return !GMenu.isMacOSX()&&hw.backdrop&&hw.contentTop<MENU_BAR_HEIGHT&&hw.contentTop+hw.height>MENU_BAR_HEIGHT;}
	/** A game that takes the whole screen sets MBarHeight to 0 and draws over the menu bar (F1GP's lap timer): the overlaid bars
	 *  step aside meanwhile, as the Mac's menu bar, which is only pixels on its screen, is overdrawn (EDT, each refresh). */
	private boolean anyOverlayBar;
	private void updateOverlayBars(){
		if(!anyOverlayBar)return;boolean shown;try{shown=memory.readWord(0xbaa)!=0;}catch(M68kCpuException e){shown=true;} // MBarHeight (this program's own low memory)
		for(HostWindow hw:windowsFrontToBack())if(hw.overlayBar!=null&&hw.overlayBar.isVisible()!=shown){hw.overlayBar.setVisible(shown);if(hw.canvas!=null)hw.canvas.repaint();}
	}
	/** Puts a backdrop's overlaid menu bar over the menu-bar band of the screen (EDT). */
	private void layoutOverlayBar(HostWindow hw){
		JMenuBar bar=hw.overlayBar;if(bar==null||hw.canvas==null)return;
		int top=hostLen(0-hw.contentTop),h=hostLen(MENU_BAR_HEIGHT);
		Point at=SwingUtilities.convertPoint(hw.canvas,0,top,hw.frame.getLayeredPane());
		bar.setBounds(at.x,at.y,hw.canvas.getWidth()>0?hw.canvas.getWidth():hostLen(hw.width),h);bar.revalidate();
	}
	/** dBoxProc, plainDBox, altDBoxProc or movableDBoxProc: on macOS such a window is a frame only so the screen menu bar stays up. */
	private static boolean dialogKind(HostWindow hw){int kind=hw.procId&15;return !hw.backdrop&&(kind==1||kind==2||kind==3||kind==5);}
	/**
	 * Whether a window carries the menu bar. On macOS every frame does (only the front one's reaches the screen menu bar); elsewhere
	 * the bar sits inside the window, and a copy on every alert and Standard File dialog is not what a Mac shows. A dialog-kind window
	 * carries it there only while it stands in for the program's main window: outside ModalDialog (whose loop refuses menus anyway)
	 * and with no document window or backdrop on screen - a game that runs in a plainDBox keeps its menus.
	 */
	private boolean carriesMenuBar(HostWindow hw){
		if(GMenu.isMacOSX())return true;
		boolean palette=isPalette(hw);
		if(!dialogKind(hw)&&!palette)return true;
		synchronized(modalWindows){if(modalWindows.contains(hw.record))return false;}
		for(HostWindow other:windowsFrontToBack())if(other!=hw&&other.visible&&other.window!=null&&!dialogKind(other)&&!isPalette(other))return false;
		return true;
	}
	/** A floating window or windoid (HyperCard's message box, tool palettes): never a program's main window, so it carries no menu bar while a document window is up. */
	private static boolean isPalette(HostWindow hw){return !hw.backdrop&&(hw.floatingLayer||floats(hw)||hw.customFrame);}
	/** EDT, platforms without a screen menu bar: gives or takes the in-window bar of shown dialog-kind windows after a window came,
	 *  went or entered/left ModalDialog (see {@link #carriesMenuBar}). */
	private void refreshDialogMenuBars(){
		if(GMenu.isMacOSX()||headless)return;
		for(HostWindow hw:windowsFrontToBack()){
			if(hw.window==null||!hw.window.isVisible()||!(dialogKind(hw)||isPalette(hw)))continue;
			JMenuBar bar=hw.frame!=null?hw.frame.getJMenuBar():hw.dialog!=null?hw.dialog.getJMenuBar():null;
			boolean has=bar!=null&&bar.getMenuCount()>0,wants=!menuBar.isEmpty()&&carriesMenuBar(hw);
			if(has!=wants){applyMenuBar(hw);hw.window.revalidate();hw.window.repaint();}
		}
	}

	/** The room a frame's menu bar takes above the content (validated first: the bar may have just been installed): the in-frame bar of
	 *  platforms without a screen menu bar, or the strip an Aqua bar still keeps. */
	private int menuBarHeight(HostWindow hw){if(hw.frame.getJMenuBar()==null)return 0;hw.frame.validate();return Math.max(0,hw.frame.getContentPane().getY());}
	/**
	 * A borderless frame (a backdrop) never becomes the key window on macOS, and AppKit puts a window's bar in the screen menu bar
	 * when it becomes key or when the bar is installed while it is the main window: a program whose only window covers the
	 * screen (AQUAZONE 1.5's tank) showed no menus at all. Once such a frame is active (main by then, measured) its bar is
	 * installed again. EDT only.
	 */
	private void reinstallBareFrameBar(final HostWindow hw){
		if(!GMenu.isMacOSX()||hw.frame==null||hw.decorated)return;
		for(int delay:new int[]{150,700}){ // once it is main (it may take a moment after the activation), and again in case it was not yet
			javax.swing.Timer later=new javax.swing.Timer(delay,e->{if(HOST_TRACE)System.err.println("[host] bare frame $"+Integer.toHexString(hw.record)+" active: menu bar again (focused "+hw.window.isFocused()+", menus open "+openMenus+")");
				if(!disposed&&hw.frame!=null&&hw.window.isVisible()&&hw.window.isFocused()&&openMenus==0){applyMenuBar(hw);hw.window.revalidate();}}); // the Aqua bar reaches the frame at layout
			later.setRepeats(false);later.start();
		}
	}
	private JMenuBar buildMenuBar(){
		JMenuBar bar=new JMenuBar();
		for(MenuModel model:menuBar){if(model.apple&&model.items.isEmpty()&&!hasHostAppleItems())continue;bar.add(buildMenu(model));}
		bar.putClientProperty(BUILT_FROM,menuBar);
		return bar;
	}
	/** Client property of a host JMenuBar: the menu list ({@link #menuBar}) it shows. */
	private static final String BUILT_FROM="hz.menus";
	/**
	 * Brings a host bar up to the current menu list in place when only titles, texts, marks, enabled states or help texts changed
	 * (EDT). Programs enable and disable items on every selection change; building the native menus again costs AppKit about a
	 * second per window for a big bar (Adobe PageMill's font and colour submenus: some 500 items), and it ran for every window on
	 * screen each time - dialogs took seconds to appear and windows stayed blank meanwhile. False: the bar must be built anew.
	 */
	@SuppressWarnings("unchecked")
	private boolean patchMenuBar(JMenuBar bar){
		if(bar==null||!(bar.getClientProperty(BUILT_FROM) instanceof List))return false;
		List<MenuModel> built=(List<MenuModel>)bar.getClientProperty(BUILT_FROM),current=menuBar;
		if(built==current)return true;
		if(built.size()!=current.size())return false;
		for(int i=0;i<current.size();i++)if(!current.get(i).sameLayout(built.get(i)))return false;
		int k=0;
		for(MenuModel model:current){
			if(model.apple&&model.items.isEmpty()&&!hasHostAppleItems())continue;
			if(k>=bar.getMenuCount())return false;JMenu menu=bar.getMenu(k++);
			if(menu==null||!Integer.valueOf(model.id).equals(menu.getClientProperty("hz.menuId")))return false;
			if(!model.apple&&!java.util.Objects.equals(menu.getText(),model.title))menu.setText(model.title);
			if(menu.isEnabled()!=model.enabled)menu.setEnabled(model.enabled);
			if(!patchItems(menu,model))return false;
		}
		bar.putClientProperty(BUILT_FROM,current);
		bar.revalidate();bar.repaint();
		return true;
	}
	/** The items of a host menu built by {@link #fillMenu} brought up to {@code model} (same layout). */
	private boolean patchItems(JMenu menu,MenuModel model){
		Object host=menu.getClientProperty(HOST_ITEMS);
		if(menu.getMenuComponentCount()!=model.items.size()+(host instanceof Integer?(Integer)host:0))return false;
		for(int i=0;i<model.items.size();i++){
			ItemModel item=model.items.get(i);java.awt.Component c=menu.getMenuComponent(i);
			if(item.separator){if(!(c instanceof JSeparator))return false;continue;}
			if(item.submenu!=null){
				if(!(c instanceof JMenu))return false;JMenu sub=(JMenu)c;
				if(!java.util.Objects.equals(sub.getText(),item.text))sub.setText(item.text);
				boolean on=item.enabled&&model.enabled;if(sub.isEnabled()!=on)sub.setEnabled(on);
				if(!patchItems(sub,item.submenu))return false;
				continue;
			}
			if(!(c instanceof JMenuItem)||c instanceof JMenu)return false;JMenuItem mi=(JMenuItem)c;
			String text=markPrefix(item)+item.text;if(!java.util.Objects.equals(mi.getText(),text))mi.setText(text);
			boolean on=item.enabled&&model.enabled;if(mi.isEnabled()!=on)mi.setEnabled(on);
			if(!java.util.Objects.equals(mi.getToolTipText(),item.help))mi.setToolTipText(item.help);
		}
		return true;
	}
	/** The bar a window shows brought up to date in place (see {@link #patchMenuBar}); false when it has to be installed anew. */
	private boolean patchWindowBar(HostWindow hw){
		if(hw.window==null||(hw.dialog!=null&&!menuBar.isEmpty())||!carriesMenuBar(hw))return false; // a dialog becomes a frame; a bar that comes or goes
		if(hw.frame!=null&&overlaysMenuBar(hw)!=(hw.overlayBar!=null))return false; // the bar moves into or out of the content
		JMenuBar bar=hw.overlayBar!=null?hw.overlayBar:hw.frame!=null?hw.frame.getJMenuBar():hw.dialog!=null?hw.dialog.getJMenuBar():null;
		if(!patchMenuBar(bar))return false;
		hw.menuBarVersion=menuVersion;return true;
	}
	private JMenu buildMenu(final MenuModel model){
		// the Apple menu's title: the Apple logo glyph (U+F8FF) only exists in Apple's fonts, so other platforms draw a small apple icon
		final JMenu menu=new JMenu(model.apple&&!GMenu.isMacOSX()?"":model.apple?"":model.title);menu.setEnabled(model.enabled);
		if(model.apple&&!GMenu.isMacOSX()){menu.setIcon(appleMenuIcon(menu.getForeground()));menu.setDisabledIcon(appleMenuIcon(UIManager.getColor("MenuItem.disabledForeground")));}
		if(model.image!=null&&model.pictureItems!=null){addPictureItems(menu,model);return menu;}
		menu.putClientProperty("hz.menuId",model.id);
		menu.addMenuListener(new javax.swing.event.MenuListener(){
			@Override public void menuSelected(javax.swing.event.MenuEvent e){openMenus++;
				// a program that fills its menus when they are pulled down (Flash 4's Windows layer: WM_INITMENUPOPUP from its MBarHook)
				// or when the menu bar is clicked gets that chance before the menu shows; its new items replace the open menu's. A menu
				// whose items are all disabled may just be waiting for that click (Wingz disables them all at startup).
				if((menusUpdateOnOpening||allItemsDisabled(model.id))&&listener.menuOpening(model.id,500))refillOpenMenu(menu,model.id);}
			@Override public void menuDeselected(javax.swing.event.MenuEvent e){menuClosed();}
			@Override public void menuCanceled(javax.swing.event.MenuEvent e){}});
		fillMenu(menu,model);
		return menu;
	}
	/** Debug aid (commands.txt `menuopen <menuID>`): opens and closes that menu of a window's host bar as the user would - its
	 *  menu listeners run - and returns the items it then holds. */
	String debugOpenMenu(final int id){
		final StringBuilder out=new StringBuilder();
		try{javax.swing.SwingUtilities.invokeAndWait(new Runnable(){@Override public void run(){
			for(HostWindow hw:windowsFrontToBack()){
				JMenuBar bar=hw.frame!=null?hw.frame.getJMenuBar():hw.dialog!=null?hw.dialog.getJMenuBar():null;if(bar==null)continue;
				for(int i=0;i<bar.getMenuCount();i++){JMenu m=bar.getMenu(i);if(m==null||!Integer.valueOf(id).equals(m.getClientProperty("hz.menuId")))continue;
					javax.swing.event.MenuEvent e=new javax.swing.event.MenuEvent(m);long start=System.nanoTime();
					for(javax.swing.event.MenuListener l:m.getMenuListeners())l.menuSelected(e);
					out.append((System.nanoTime()-start)/1_000_000).append(" ms: ");
					for(int k=0;k<m.getItemCount();k++){debugMenuItem(out,m.getItem(k));out.append(" | ");}
					for(javax.swing.event.MenuListener l:m.getMenuListeners())l.menuDeselected(e);
					return;}
			}
			out.append("no host menu ").append(id);}});}
		catch(Exception e){out.append(e);}
		return out.toString();
	}
	/** One item of {@link #debugOpenMenu}: a submenu is opened too and its items follow in [..]. */
	private static void debugMenuItem(StringBuilder out,JMenuItem it){
		out.append(it==null?"-":it.getText()+(it.isEnabled()?"":"(off)"));
		if(!(it instanceof JMenu))return;
		JMenu sub=(JMenu)it;javax.swing.event.MenuEvent e=new javax.swing.event.MenuEvent(sub);
		for(javax.swing.event.MenuListener l:sub.getMenuListeners())l.menuSelected(e);
		out.append(" [");
		for(int j=0;j<sub.getItemCount();j++){out.append(j>0?", ":"");debugMenuItem(out,sub.getItem(j));}
		out.append("]");
		for(javax.swing.event.MenuListener l:sub.getMenuListeners())l.menuDeselected(e);
	}
	private void menuClosed(){
		if(openMenus>0)openMenus--;
		if(openMenus==0&&rebuildWhenClosed){rebuildWhenClosed=false;rebuildMenuBars();}
	}
	/** The open menu {@code id} gets the items of the bar the program just sent ({@link #setMenuBar}). */
	/** Whether the menu of the current bar is enabled but has no enabled item. */
	private boolean allItemsDisabled(int id){
		for(MenuModel m:menuBar){if(m.id!=id)continue;if(!m.enabled)return false;
			for(ItemModel i:m.items)if(!i.separator&&i.enabled)return false;return !m.items.isEmpty();}
		return false;
	}
	private void refillOpenMenu(JMenu menu,int id){
		MenuModel current=null;for(MenuModel m:menuBar)if(m.id==id){current=m;break;}
		if(current==null||(current.image!=null&&current.pictureItems!=null))return;
		menu.removeAll();menu.setEnabled(current.enabled);fillMenu(menu,current);
		menu.revalidate();if(menu.isPopupMenuVisible())menu.getPopupMenu().pack();
	}
	private void fillMenu(JMenu menu,final MenuModel model){
		int number=model.itemBase;
		for(final ItemModel item:model.items){
			number++;final int itemNumber=number;
			if(item.separator){menu.add(new JSeparator());continue;}
			// a hierarchical item keeps its text style (LimitedHyperMenu's bold "Icons" and bold-italic "Style" came out plain)
			if(item.submenu!=null){JMenu sub=buildMenu(item.submenu);sub.setText(item.text);sub.setEnabled(item.enabled&&model.enabled);applyItemStyle(sub,item.style);menu.add(sub);continue;}
			JMenuItem mi=new JMenuItem(markPrefix(item)+item.text);mi.setEnabled(item.enabled&&model.enabled);if(item.help!=null)mi.setToolTipText(item.help);applyItemStyle(mi,item.style);
			if(item.icon!=null)mi.setIcon(new javax.swing.ImageIcon(item.icon));
			int command=commandMask(),mask=(item.modifiers&8)!=0?0:command;
			if((item.modifiers&1)!=0)mask|=InputEvent.SHIFT_DOWN_MASK;
			if((item.modifiers&2)!=0)mask|=InputEvent.ALT_DOWN_MASK;
			if((item.modifiers&4)!=0&&command!=InputEvent.CTRL_DOWN_MASK)mask|=InputEvent.CTRL_DOWN_MASK;
			if(item.command!=0&&item.command!=0x1b&&item.command!=0x1c&&item.command!=0x1d&&(mask&command)!=0){
				int keyCode=KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(item.command));if(keyCode!=KeyEvent.VK_UNDEFINED)mi.setAccelerator(KeyStroke.getKeyStroke(keyCode,mask));}
			mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){
				if(item.command!=0&&isKeyboardTriggered(e)){
					if(ownModifierKeys()!=null&&(modifierState&0x100)==0)return; // the accelerator's key is no longer Command (the program's own modifier keys changed)
					listener.postMenuKey(model.id,itemNumber,Character.toLowerCase(item.command),0x100|0x80);}
				else listener.postMenuSelection(model.id,itemNumber);}});
			menu.add(mi);
		}
		if(model.apple)addHostAppleItems(menu,!model.items.isEmpty());
	}
	/** Client property of the host Apple menu: how many of HyperZebra's own items follow the program's (see {@link #addHostAppleItems}). */
	private static final String HOST_ITEMS="hz.hostItems";
	/** Client property of the host Apple menu: its Recent Documents submenu. */
	private static final String HOST_DOCS="hz.recentDocs";
	/** Whether the Apple menu gets HyperZebra's own items: a program launched in the VM (not a HyperCard external, a service VM or a test). */
	private boolean hasHostAppleItems(){MacApp68k app=owner;return !headless&&!external&&app!=null&&!app.service&&app.file!=null;}
	/**
	 * HyperZebra's own items under the program's Apple menu, as the launcher's dock menu has them: the dock, the documents the
	 * program opened (handed to it as Open Documents events; disabled for a program that takes none while it runs), its Application
	 * Settings and Resource Translation (when this Mac can translate). They act host-side: the program never sees them.
	 */
	private void addHostAppleItems(final JMenu menu,boolean separator){
		final MacApp68k app=owner;
		if(!hasHostAppleItems()){menu.putClientProperty(HOST_ITEMS,null);return;}
		int before=menu.getMenuComponentCount();
		if(separator&&!(before>0&&menu.getMenuComponent(before-1) instanceof JSeparator))menu.add(new JSeparator()); // the program's list may end in one (no desk accessories after it)
		JMenuItem mi;
		if(GLauncher.enabled())menu.add(GLauncher.instance.dockMenu());
		if(LauncherStore.enabled){
			final JMenu docs=new JMenu(International.t("Recent Documents"));
			fillRecentDocuments(docs,app);
			menu.add(docs);
			boolean listening=menu.getClientProperty(HOST_DOCS)!=null;menu.putClientProperty(HOST_DOCS,docs); // the menu refilled in place keeps its listener
			if(!listening)menu.addMenuListener(new javax.swing.event.MenuListener(){ // the list may have grown since the bar was built
				@Override public void menuSelected(javax.swing.event.MenuEvent e){Object o=menu.getClientProperty(HOST_DOCS);if(o instanceof JMenu){((JMenu)o).removeAll();fillRecentDocuments((JMenu)o,app);}}
				@Override public void menuDeselected(javax.swing.event.MenuEvent e){}
				@Override public void menuCanceled(javax.swing.event.MenuEvent e){}});
		}
		menu.add(mi=new JMenuItem(International.t("Application Settings…")));
		mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){GAppSettingsDialog.show(ownerFrame(),app.file);}});
		if(app.japaneseSystem()){ // Japanese text comes out garbled in a Roman font only on a Japanese System (HyperZebra's language, or the program's own System language)
			menu.add(mi=new JMenuItem(International.t("Fix Garbled Text…")));
			mi.setToolTipText(International.t("Click garbled text to show it in a Japanese font"));
			mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){pickGarbledText();}});
		}
		if(VMTranslation.unavailableReason()==null){
			menu.add(mi=new JMenuItem(International.t("Resource Translation…")));
			mi.setToolTipText(International.t("Translates its menus and dialogs into a copy of its resources."));
			mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){GTranslateAheadDialog.show(ownerFrame(),app.file);}});
		}
		menu.add(mi=new JMenuItem(International.t("Resource Translation with AI Chat…")));
		mi.setToolTipText(International.t("Copy a request into ChatGPT or another AI chat and paste its answer back."));
		mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){GLlmTranslationDialog.show(ownerFrame(),app.file);}});
		menu.putClientProperty(HOST_ITEMS,menu.getMenuComponentCount()-before);
	}
	private static void fillRecentDocuments(JMenu docs,final MacApp68k app){
		JMenuItem mi;List<File> documents=LauncherStore.get().documentsOf(app.file);
		if(documents.isEmpty()){docs.add(mi=new JMenuItem(International.t("None")));mi.setEnabled(false);return;}
		boolean takes=app.acceptsDocumentsWhileRunning();
		for(final File d:documents){
			docs.add(mi=new JMenuItem(LauncherStore.displayName(d)));
			mi.setToolTipText(d.getPath());mi.setEnabled(takes&&d.exists());
			mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){if(d.exists()&&!app.finished)app.openDocuments(java.util.Collections.singletonList(d));}});
		}
	}
	/** The frame of the program's front window, owner of the dialogs its Apple menu opens. */
	private java.awt.Frame ownerFrame(){Window w=anyWindow();return w instanceof java.awt.Frame?(java.awt.Frame)w:null;}
	/** A 12x14 apple silhouette (body with a bite, stem leaf) in one colour, standing in for Chicago's $14 glyph on hosts without it. */
	static Icon appleMenuIcon(Color color){
		if(color==null)color=Color.BLACK;
		final int w=12,h=14,scale=4; // drawn at 4x and downsampled for smooth edges
		java.awt.image.BufferedImage big=new java.awt.image.BufferedImage(w*scale,h*scale,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=big.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);g.fill(ClassicGlyphs.appleShape(0,0,scale));g.dispose();
		java.awt.image.BufferedImage small=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2=small.createGraphics();g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.drawImage(big,0,0,w,h,null);g2.dispose();
		return new ImageIcon(small);
	}
	/** A custom-MDEF menu on the host: one item per region the MDEF reports, showing that region of the MDEF's drawing (the
	 *  screen menu bar of macOS renders icons but no custom components, so a picture menu becomes a column of picture items). */
	private void addPictureItems(JMenu menu,final MenuModel model){
		final java.awt.image.BufferedImage image=model.image;
		for(int[] r:model.pictureItems){
			final int itemNumber=r[0];int top=Math.max(0,r[1]),left=Math.max(0,r[2]),bottom=Math.min(image.getHeight(),r[3]),right=Math.min(image.getWidth(),r[4]);
			if(bottom<=top||right<=left)continue;
			JMenuItem mi=new JMenuItem(new javax.swing.ImageIcon(image.getSubimage(left,top,right-left,bottom-top)));mi.setEnabled(model.enabled);
			mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){listener.postMenuSelection(model.id,itemNumber);}});
			menu.add(mi);
		}
		if(model.tearOff!=null){
			menu.add(new JSeparator());JMenuItem mi=new JMenuItem(model.tearOff);mi.setEnabled(model.enabled);
			mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){listener.postTearOff(model.id);}});
			menu.add(mi);
		}
	}
	private boolean isKeyboardTriggered(ActionEvent e){
		int mask=commandMask(),old=mask==InputEvent.ALT_DOWN_MASK?ActionEvent.ALT_MASK:mask==InputEvent.SHIFT_DOWN_MASK?ActionEvent.SHIFT_MASK:0;
		return (e.getModifiers()&(ActionEvent.META_MASK|ActionEvent.CTRL_MASK|old))!=0;
	}
	/** The host modifier of the menus' Command-key accelerators: Command's own key when the program has one and it is a
	 *  host modifier, none when it is an ordinary key (the key listener posts those menu keys), else Command's default key. */
	private int commandMask(){
		ModifierKeys own=ownModifierKeys();
		if(own==null||own.vk[ModifierKeys.COMMAND]<0)return GMenu.isMacOSX()?InputEvent.META_DOWN_MASK:InputEvent.CTRL_DOWN_MASK;
		switch(own.vk[ModifierKeys.COMMAND]){
			case KeyEvent.VK_CONTROL:return InputEvent.CTRL_DOWN_MASK;case KeyEvent.VK_ALT:return InputEvent.ALT_DOWN_MASK;
			case KeyEvent.VK_META:return InputEvent.META_DOWN_MASK;case KeyEvent.VK_SHIFT:return InputEvent.SHIFT_DOWN_MASK;default:return 0;
		}
	}

	/** Finds the menu/item whose command key matches; used for Cmd-key presses before the app sees them. */
	int[] menuKeyFor(int ch){
		char lower=Character.toLowerCase((char)ch);
		for(MenuModel model:menuBar){if(!model.enabled)continue;int n=0;for(ItemModel item:model.items){n++;if(item.enabled&&item.command!=0&&Character.toLowerCase(item.command)==lower)return new int[]{model.id,n};}}
		return null;
	}
	/** The Command-key equivalent of a menu item of the bar (0 none). */
	char menuCommand(int menuId,int item){
		List<MenuModel> bar=menuBar;
		for(MenuModel model:bar)if(model.id==menuId&&item>=1&&item<=model.items.size()){ItemModel im=model.items.get(item-1);return im.command>=0x20?im.command:0;}
		return 0;
	}

	/** Whether {@code w} (or a window it is owned by) is one of this program's: a Mac window, the stand-in or the screen frame. */
	/** True when {@code w} is the native window of one of the program's Mac windows. */
	private boolean isProgramWindow(Window w){if(w==null)return false;if(screenBar!=null&&screenBar.owns(w))return true; /* the frame round the screen hands the focus straight back */synchronized(windows){for(HostWindow hw:order)if(hw.window==w)return true;}return false;}
	boolean ownsWindow(Window w){
		for(;w!=null;w=w.getOwner()){
			if(w==standIn||w==screenFrame||(screenBar!=null&&screenBar.owns(w)))return true;
			synchronized(windows){for(HostWindow hw:order)if(hw.window==w)return true;}
		}
		return false;
	}
	/** When a Cmd-Q last went to a program, by its window's key listener or by {@link #postCommandQ}: macOS may hand the one
	 *  keystroke to both that listener and the application menu's "Quit HyperZebra", in either order. */
	private static volatile long commandQDeliveredAt;
	/** True within a second of a Cmd-Q delivered to a program (the second route of the same keystroke is dropped). */
	static boolean commandQJustDelivered(){return System.currentTimeMillis()-commandQDeliveredAt<1000;}
	static void noteCommandQDelivered(){commandQDeliveredAt=System.currentTimeMillis();}
	/** A Cmd-key the OS took before the canvas key listener could (macOS answers Cmd-Q with the application menu's
	 *  "Quit HyperZebra"): delivered as {@link #keyAdapter} would have - the program's menu item with that command key,
	 *  else a plain key-down/key-up pair. */
	void postCommandKey(char ch){
		int mods=0x100|(mouseDown?0:0x80);
		int[] mi=menuKeyFor(ch);
		if(mi!=null){listener.postMenuKey(mi[0],mi[1],ch,mods);return;}
		int code=keyCodeForChar(ch);listener.postKey(3,code,ch,mods);listener.postKey(4,code,ch,mods);
	}
	/** Cmd-Q meant for the program: its Cmd-Q menu item, or the bare keystroke for programs that read keys themselves -
	 *  and without a Cmd-Q item also what closing the stand-in does (a Quit item without the shortcut, else the 'quit'
	 *  Apple event), so that Cmd-Q quits MacWrite 2.2 too. */
	void postCommandQ(){
		noteCommandQDelivered();
		boolean menuKey=menuKeyFor('q')!=null;
		postCommandKey('q');
		if(!menuKey)listener.postQuit();
	}

	/** Shows a popup menu at global coordinates and blocks until an item is chosen; returns item number or 0. */
	/** The frontmost showing window whose canvas holds the program's global point, or null. */
	private HostWindow hostWindowAt(int globalH,int globalV){
		Point hp=toHost(globalH,globalV);
		for(HostWindow hw:windowsFrontToBack()){if(hw.canvas==null||!hw.canvas.isShowing())continue;Point p=hw.canvas.getLocationOnScreen();
			if(hp.x>=p.x&&hp.y>=p.y&&hp.x<p.x+hw.canvas.getWidth()&&hp.y<p.y+hw.canvas.getHeight())return hw;}
		return null;
	}
	int popUpMenuSelect(final MenuModel model,final int globalLeft,final int globalTop,final int initialItem){
		if(headless)return 0;
		// the Menu Manager shows a pop-up while the button is down: an external that calls it with the button up (UxPop from a
		// mouseUp handler: "on mouseUp の中で使ってもメニューは出てきません") gets nothing at once, no menu waiting for a click. Only
		// when the button was already up as the external was entered: one released during the call (a quick click on a mouseDown
		// button) keeps the menu up for the next click, as before
		if(external&&!isMouseDown()&&!mouseDownAtExternalEntry){if(Mac68kApi.TRACE)System.err.println("[68K] PopUpMenuSelect "+model.title+" with the button up: 0");return 0;}
		if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder();for(ItemModel item:model.items){sb.append(sb.length()==0?"":"|").append(item.separator?"-":(item.checked?"*":"")+(item.enabled?"":"(")+(item.style!=0?"<"+item.style:"")+item.text);
				if(item.submenu!=null){sb.append(" {");for(ItemModel sub:item.submenu.items)sb.append(sub.separator?"-":(sub.checked?"*":"")+sub.text).append(';');sb.append('}');}}System.err.println("[68K] PopUpMenuSelect "+model.title+" at "+globalLeft+","+globalTop+" item "+initialItem+" ["+sb+"]"+(model.image!=null?" picture "+model.image.getWidth()+"x"+model.image.getHeight()+" "+(model.pictureItems==null?0:model.pictureItems.size())+" items":""));}
		if(model.image!=null&&model.pictureItems!=null)return popUpPictureMenu(model,globalLeft,globalTop);
		final AtomicReference<Integer> result=new AtomicReference<Integer>(0);
		final Object lock=new Object();final boolean[] done={false};
		runOnEdt(new Runnable(){@Override public void run(){
			final JPopupMenu popup=new JPopupMenu();int number=0;final long[] shownAt={0};final int[] reshown={0};final Point[] shownWhere={null};final java.awt.Component[] shownInvoker={null};final List<JMenuItem> menuItems=new ArrayList<JMenuItem>();
			for(final ItemModel item:model.items){number++;final int itemNumber=number;
				if(item.separator){popup.addSeparator();if(scPct>100)magnify(popup.getComponent(popup.getComponentCount()-1));menuItems.add(null);continue;}
				// a hierarchical item (cmd $1B, the submenu's ID as its mark): The Yennie eXternals' metaMenu "ICONS" -> its icon menu
				if(item.submenu!=null){JMenu sub=popupSubmenu(item.text,item.submenu,result,scPct,0);sub.setEnabled(item.enabled);applyItemStyle(sub,item.style);popup.add(sub);menuItems.add(sub);continue;}
				JMenuItem mi=new JMenuItem(item.text);mi.setEnabled(item.enabled);
				if(model.fontSize>0&&mi.getFont()!=null)mi.setFont(mi.getFont().deriveFont((float)model.fontSize)); // in the MDEF's size; magnify scales it with the screen
				decoratePopupItem(mi,item);
				if(scPct>100)magnify(mi);
				mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){result.set(itemNumber);}});popup.add(mi);menuItems.add(mi);}
			popup.addPopupMenuListener(new PopupMenuListener(){
				@Override public void popupMenuWillBecomeVisible(PopupMenuEvent e){}
				@Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e){
					// macOS activates another window of the program as the pop-up opens over a dialog, and AWT answers that window change
					// with an "ungrab" that closes the pop-up before anyone touched it: bring it back (a few times, only just after opening).
					AWTEvent cur=java.awt.EventQueue.getCurrentEvent();
					if(cur!=null&&cur.getClass().getSimpleName().equals("UngrabEvent")&&reshown[0]<5&&System.nanoTime()-shownAt[0]<1_500_000_000L&&!done[0]){
						reshown[0]++;final Point where=shownWhere[0];final java.awt.Component inv=shownInvoker[0];
						javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){if(!done[0])popup.show(inv,where.x,where.y);}});
						return;}
					synchronized(lock){done[0]=true;lock.notifyAll();}currentPopup=null;currentPopupItems=null;}
				@Override public void popupMenuCanceled(PopupMenuEvent e){}
			});
			// the window under the menu's corner: the front one may be a floating palette elsewhere (Infini-D 2.0.1's Toolbox while
			// a view's title-bar pop-up opens), and the fit-inside-the-window clamp below then threw the menu to that palette's edge
			HostWindow hw=hostWindowAt(globalLeft,globalTop);if(hw==null)hw=frontWindow;
			// the front window may be hidden (A-Dock's tooltip window, its dock's menus open above the dock): any shown window will do
			if(hw==null||hw.canvas==null||!hw.canvas.isShowing())for(HostWindow other:windowsFrontToBack())if(other.canvas!=null&&other.canvas.isShowing()){hw=other;break;}
			JComponent invoker=hw!=null&&hw.canvas!=null&&hw.canvas.isShowing()?hw.canvas:null;
			// an external without a window on the screen (The Yennie eXternals' metaMenu XFCN) pops its menu up over HyperCard's
			// card window; its hidden window threw here and the external waited for the menu forever
			if(invoker==null&&owner==null&&PCARD.pc!=null&&PCARD.pc.mainPane!=null&&PCARD.pc.mainPane.isShowing())invoker=PCARD.pc.mainPane;
			// popUpItem goes at `top`, the menu moved up by the items above it (UxPop's buttons open with their current item over the
			// button, the menu's first item was there)
			int itemTop=popupItemTop(popup,initialItem);
			if(invoker!=null){Point p=invoker.getLocationOnScreen();currentPopup=popup;currentPopupItems=menuItems;Point at=toHost(globalLeft,globalTop);int px=at.x-p.x,py=at.y-p.y-itemTop;
				// A pop-up that fits inside the window is a lightweight component, not a window of its own: a heavyweight one is closed by
				// AWT ("ungrab") whenever the OS changes the active window, which on macOS happens as it opens over a modal dialog.
				popup.setLightWeightPopupEnabled(true);
				javax.swing.JRootPane root=SwingUtilities.getRootPane(invoker);Dimension pd=popup.getPreferredSize();
				// A menu larger than its window (A-Dock's menus over its 50-pixel-high dock, a system window) cannot be a component
				// inside it: it becomes a window of its own at the requested place.
				if(root!=null&&(pd.height>root.getHeight()||pd.width>root.getWidth()))popup.setLightWeightPopupEnabled(false);
				else if(root!=null){Point rp=SwingUtilities.convertPoint(invoker,px,py,root);
					int fitY=Math.max(0,Math.min(rp.y,root.getHeight()-pd.height)),fitX=Math.max(0,Math.min(rp.x,root.getWidth()-pd.width));
					px+=fitX-rp.x;py+=fitY-rp.y;}
				shownAt[0]=System.nanoTime();shownWhere[0]=new Point(px,py);shownInvoker[0]=invoker;popup.show(invoker,px,py);
				if(Mac68kApi.TRACE)System.err.println("[host] pop-up over "+(hw==null?"?":Integer.toHexString(hw.record))+" root "+(root==null?null:root.getSize())+" menu "+pd+" at "+px+","+py+" lightweight "+popup.isLightWeightPopupEnabled()+" showing "+popup.isShowing());}
			else{synchronized(lock){done[0]=true;lock.notifyAll();}}
		}},false);
		// interrupt-time work goes on while the menu is up, as on a Mac (VocalWriter's music stopped under its Quantize pop-up)
		MacApp68k app=owner;Toolbox68k tb=app==null?null:app.toolbox;
		for(;;){
			synchronized(lock){if(done[0])break;try{lock.wait(tb==null?100:5);}catch(InterruptedException e){break;}if(done[0])break;}
			if(tb!=null)tb.serviceWhileBlocked();
		}
		// the action listener fires after the popup hides; give it a moment
		try{Thread.sleep(30);}catch(InterruptedException ignored){}
		return result.get();
	}

	/** How far below the pop-up's top edge item {@code item} (1-based) begins; 0 for item 0 (the menu's top at `top`). */
	static int popupItemTop(JPopupMenu popup,int item){
		if(item<=0)return 0;java.awt.Insets in=popup.getInsets();int top=in==null?0:in.top;
		for(int i=0;i<Math.min(item-1,popup.getComponentCount());i++)top+=popup.getComponent(i).getPreferredSize().height;
		return top;
	}

	/** A submenu of a pop-up: choosing one of its items gives (its menu ID << 16) | item, as PopUpMenuSelect returns it. */
	private JMenu popupSubmenu(String title,final MenuModel model,final AtomicReference<Integer> result,int scPct,int depth){
		JMenu menu=new JMenu(title);if(scPct>100)magnify(menu);int number=0;
		for(final ItemModel item:model.items){number++;final int itemNumber=number;
			if(item.separator){menu.addSeparator();continue;}
			if(item.submenu!=null&&depth<4){JMenu sub=popupSubmenu(item.text,item.submenu,result,scPct,depth+1);sub.setEnabled(item.enabled);applyItemStyle(sub,item.style);menu.add(sub);continue;}
			JMenuItem mi=new JMenuItem(item.text);mi.setEnabled(item.enabled&&model.enabled);decoratePopupItem(mi,item);if(scPct>100)magnify(mi);
			mi.addActionListener(new ActionListener(){@Override public void actionPerformed(ActionEvent e){result.set((model.id<<16)|itemNumber);}});
			menu.add(mi);
		}
		return menu;
	}

	/** The item's mark before its text: the check mark, or another mark character (a diamond, √, ◊, a bullet...). */
	static String markPrefix(ItemModel item){
		if(item.checked)return "✓ ";
		if(item.mark==0||item.submenuId!=0||item.command==0x1b)return "";
		// the character the Menu Manager draws in the menu font, decoded by the Menu Manager (SimplePop's "!√I'm marked" and
		// "!◊So am I" came out as bullets: only ASCII marks were kept)
		if(item.markText!=null&&!item.markText.isEmpty()&&!item.markText.equals("\u0012"))return item.markText+" ";
		if(item.mark==0x12)return "✓ ";
		if(item.mark==0x13)return "◆ ";
		if(item.mark==0x11)return "⌘ ";
		if(item.mark==0x14)return "\uf8ff ";
		if(item.mark>0x20&&item.mark<0x7f)return (char)item.mark+" ";
		return "• "; // $A5 and the other bullets of the fonts
	}
	/** An item's text style as the Menu Manager draws it: bold, italic, underline, condensed, extended (outline and shadow need
	 *  the item's own drawing, see {@link #decoratePopupItem}). Plain leaves the look and feel's font alone. */
	static void applyItemStyle(JMenuItem mi,int style){
		if(style==0||mi.getFont()==null)return;
		java.util.Map<java.awt.font.TextAttribute,Object> a=new java.util.HashMap<java.awt.font.TextAttribute,Object>();
		if((style&1)!=0)a.put(java.awt.font.TextAttribute.WEIGHT,java.awt.font.TextAttribute.WEIGHT_BOLD);
		if((style&2)!=0)a.put(java.awt.font.TextAttribute.POSTURE,java.awt.font.TextAttribute.POSTURE_OBLIQUE);
		if((style&4)!=0)a.put(java.awt.font.TextAttribute.UNDERLINE,java.awt.font.TextAttribute.UNDERLINE_ON);
		if((style&32)!=0)a.put(java.awt.font.TextAttribute.WIDTH,java.awt.font.TextAttribute.WIDTH_CONDENSED);
		if((style&64)!=0)a.put(java.awt.font.TextAttribute.WIDTH,java.awt.font.TextAttribute.WIDTH_EXTENDED);
		mi.setFont(mi.getFont().deriveFont(a));
	}
	/** A pop-up item as the Menu Manager draws it: its icon, its style (bold, italic, underline, outline, condensed...), its
	 *  Command key (UxPop's icon menus, UxPopDir's bold title came out as plain text with a check mark). */
	private void decoratePopupItem(JMenuItem mi,ItemModel item){
		applyItemStyle(mi,item.style);
		// the mark in its column before the icon, as the Menu Manager draws it (a check mark came after UxPop's icons), and outline
		// and shadow text hollow (Swing has no such face: they were bold) - both drawn by the item's icon
		boolean ownText=(item.style&(8|16))!=0;String mark=markPrefix(item);
		if(item.icon!=null||ownText){PopupItemIcon icon=new PopupItemIcon(mi,mark,item.icon,ownText?item.text:null,item.style);mi.setIcon(icon);mi.setDisabledIcon(icon);mi.setText(ownText?"":item.text);}
		else mi.setText(mark+item.text);
		if(item.command>=0x20&&item.command!=0x1b){int keyCode=KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(item.command));
			if(keyCode!=KeyEvent.VK_UNDEFINED)mi.setAccelerator(KeyStroke.getKeyStroke(keyCode,commandMask()));} // shown only: a pop-up's keys do nothing
	}

	/** A pop-up item's mark column (kept for every item with an icon, so the icons line up), icon and text it draws itself
	 *  (outline/shadow hollow), in the item's font and colours as it paints. */
	static final class PopupItemIcon implements javax.swing.Icon {
		private final JMenuItem item;private final String mark,text;private final java.awt.image.BufferedImage image;private final int style;
		PopupItemIcon(JMenuItem item,String mark,java.awt.image.BufferedImage image,String text,int style){this.item=item;this.mark=mark==null?"":mark;this.image=image;this.text=text;this.style=style;}
		private java.awt.FontMetrics metrics(){return item.getFontMetrics(item.getFont());}
		private int markWidth(){return mark.isEmpty()&&image==null?0:metrics().stringWidth(mark.isEmpty()?"✓ ":mark);}
		private int textWidth(){return text==null?0:metrics().stringWidth(text)+((style&16)!=0?2:(style&8)!=0?1:0);}
		@Override public int getIconWidth(){return markWidth()+(image==null?0:image.getWidth()+(text!=null?4:0))+textWidth();}
		@Override public int getIconHeight(){return Math.max(image==null?0:image.getHeight(),text==null&&mark.isEmpty()?0:metrics().getHeight());}
		@Override public void paintIcon(java.awt.Component c,Graphics g,int x,int y){
			Graphics2D g2=(Graphics2D)g.create();
			try{
				g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				java.awt.Color fg=!item.isEnabled()?javax.swing.UIManager.getColor("MenuItem.disabledForeground"):item.isArmed()||item.isSelected()?javax.swing.UIManager.getColor("MenuItem.selectionForeground"):item.getForeground();
				if(fg==null)fg=item.isEnabled()?java.awt.Color.BLACK:java.awt.Color.GRAY;
				java.awt.FontMetrics fm=metrics();int h=getIconHeight(),baseline=y+(h-fm.getHeight())/2+fm.getAscent();
				g2.setFont(item.getFont());g2.setColor(fg);
				if(!mark.isEmpty())g2.drawString(mark,x,baseline);
				int at=x+markWidth();
				if(image!=null){if(!item.isEnabled())g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER,0.4f));
					g2.drawImage(image,at,y+(h-image.getHeight())/2,null);g2.setComposite(java.awt.AlphaComposite.SrcOver);at+=image.getWidth()+(text!=null?4:0);}
				if(text!=null&&!text.isEmpty()){
					if((style&(8|16))==0)g2.drawString(text,at,baseline); // in the item's font: bold, italic, underlined...
					else{java.awt.Shape outline=new java.awt.font.TextLayout(text,item.getFont(),g2.getFontRenderContext()).getOutline(java.awt.geom.AffineTransform.getTranslateInstance(at,baseline));
						g2.setStroke(new java.awt.BasicStroke(1f));g2.draw(outline);
						if((style&16)!=0){g2.translate(1,1);g2.draw(outline);}} // shadow: the outline again, a pixel down and right
				}
			}finally{g2.dispose();}
		}
	}

	/** A pop-up item on a magnified screen: {@link #scale} times the look-and-feel size, like the rest of the program's drawing. */
	private void magnify(java.awt.Component c){
		Dimension d=c.getPreferredSize();
		java.awt.Font f=c.getFont();if(f!=null)c.setFont(f.deriveFont((float)(f.getSize2D()*scale())));
		// the look and feel's insets and check-mark column stay 1x, so fix the whole row at scale() times its 1x size
		c.setPreferredSize(new Dimension(Math.max(hostLen(d.width),c.getPreferredSize().width),hostLen(d.height)));
	}

	/** Debug hook for a picture pop-up (commands.txt `popup <item>` while one is up). */
	private volatile java.util.function.IntConsumer currentPicturePopup;
	/** The view of the live pop-up on screen (repainted when the MDEF redrew its highlight). */
	private volatile javax.swing.JComponent liveView;
	/** The picture pop-up on screen (snapshots write it as popup.png). */
	private volatile Window picturePopupWindow;
	/**
	 * A custom-MDEF pop-up: the MDEF's drawing in a bare window at the requested screen
	 * position, tracked the way the ROM tracks a menu - the item under the pointer is hilited (inverted), releasing the button
	 * over an item chooses it, releasing elsewhere (or pressing outside the menu, or Escape) chooses nothing. When the button is
	 * already up when the menu appears (a click, not a press), the next press-and-release inside picks the item instead.
	 */
	private int popUpPictureMenu(final MenuModel model,final int globalLeft,final int globalTop){return popUpPictureMenu(model.image,model.pictureItems,null,globalLeft,globalTop);}
	/** A custom MDEF asked on demand (emulation thread): the item at a point of its picture - its mChooseMsg, which also
	 *  draws its own highlight - and the picture as it now looks. */
	interface LiveMenu { int choose(int x,int y) throws M68kException; java.awt.image.BufferedImage image(); }
	/**
	 * A custom-MDEF pop-up shown at once from the MDEF's picture: the item under the pointer is asked of the MDEF itself
	 * (mChooseMsg at that point, answered here on the emulation thread, which waits for the pop-up anyway) and the picture
	 * it redraws with its own highlight is shown. Learning the whole layout first took a thousand MDEF calls (ResEdit's
	 * colour palettes opened a noticeable moment after the click).
	 */
	int popUpLiveMenu(java.awt.image.BufferedImage image,LiveMenu live,int globalLeft,int globalTop){
		if(headless)return 0;
		return popUpPictureMenu(image,java.util.Collections.<int[]>emptyList(),live,globalLeft,globalTop);
	}
	private int popUpPictureMenu(final java.awt.image.BufferedImage firstImage,final List<int[]> items,final LiveMenu live,final int globalLeft,final int globalTop){
		final AtomicReference<Integer> result=new AtomicReference<Integer>(0);
		final Object lock=new Object();final boolean[] done={false};
		final boolean pressedAtOpen=mouseDown;
		// live menus: the latest pointer position to ask about (x<<16|y, -1 = none) and where the button was released
		final java.util.concurrent.atomic.AtomicLong asked=new java.util.concurrent.atomic.AtomicLong(-1);final long[] released={-1};
		final java.awt.image.BufferedImage[] shown={firstImage};
		runOnEdt(new Runnable(){@Override public void run(){
			final java.awt.image.BufferedImage image=firstImage;
			HostWindow hw=frontWindow;final Window owner=hw!=null?hw.window:null;
			final javax.swing.JWindow w=new javax.swing.JWindow(owner);w.setFocusableWindowState(false);w.setAlwaysOnTop(true);
			final int[] hilited={0};
			final javax.swing.JComponent view=new javax.swing.JComponent(){@Override protected void paintComponent(Graphics g){
				java.awt.image.BufferedImage image=shown[0];
				if(scPct==100)g.drawImage(image,0,0,null);else{((Graphics2D)g).setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,interpolation((Graphics2D)g));g.drawImage(image,0,0,hostLen(image.getWidth()),hostLen(image.getHeight()),null);}
				if(hilited[0]!=0)for(int[] r:items)if(r[0]==hilited[0]){g.setXORMode(Color.WHITE);g.setColor(Color.BLACK);g.fillRect(hostLen(r[2]),hostLen(r[1]),hostLen(r[4])-hostLen(r[2]),hostLen(r[3])-hostLen(r[1]));g.setPaintMode();}}};
			view.setPreferredSize(new Dimension(hostLen(image.getWidth()),hostLen(image.getHeight())));w.getContentPane().add(view);w.pack();
			Rectangle screen=owner!=null&&owner.getGraphicsConfiguration()!=null?owner.getGraphicsConfiguration().getBounds():new Rectangle(0,0,1600,1200);
			Point at=toHost(globalLeft,globalTop);int left=Math.max(screen.x,Math.min(at.x,screen.x+screen.width-w.getWidth())),top=Math.max(screen.y,Math.min(at.y,screen.y+screen.height-w.getHeight()));
			w.setLocation(left,top);
			final Runnable[] finish=new Runnable[1];
			final AWTEventListener tracker=new AWTEventListener(){@Override public void eventDispatched(AWTEvent event){
				if(event instanceof KeyEvent){if(((KeyEvent)event).getID()==KeyEvent.KEY_PRESSED&&((KeyEvent)event).getKeyCode()==KeyEvent.VK_ESCAPE)finish[0].run();return;}
				if(!(event instanceof MouseEvent))return;MouseEvent me=(MouseEvent)event;
				Point p;try{p=me.getLocationOnScreen();}catch(Exception e){return;}
				int x=virtualLen(p.x-w.getX()),y=virtualLen(p.y-w.getY());boolean inside=x>=0&&y>=0&&x<virtualLen(w.getWidth())&&y<virtualLen(w.getHeight());
				if(live!=null){ // the emulation thread asks the MDEF; it draws its own highlight
					switch(me.getID()){
						case MouseEvent.MOUSE_MOVED:case MouseEvent.MOUSE_DRAGGED:asked.set(inside?(long)x<<16|y&0xffff:-2);synchronized(lock){lock.notifyAll();}break;
						case MouseEvent.MOUSE_PRESSED:if(!inside)finish[0].run();break;
						case MouseEvent.MOUSE_RELEASED:if(pressedAtOpen||inside){released[0]=inside?(long)x<<16|y&0xffff:-1;finish[0].run();}break;
						default:break;
					}
					return;}
				int under=0;if(inside)for(int[] r:items)if(y>=r[1]&&x>=r[2]&&y<r[3]&&x<r[4]){under=r[0];break;}
				switch(me.getID()){
					case MouseEvent.MOUSE_MOVED:case MouseEvent.MOUSE_DRAGGED:if(under!=hilited[0]){hilited[0]=under;view.repaint();}break;
					case MouseEvent.MOUSE_PRESSED:if(!inside)finish[0].run();break;
					case MouseEvent.MOUSE_RELEASED:if(pressedAtOpen||inside){result.set(under);finish[0].run();}break;
					default:break;
				}}};
			finish[0]=new Runnable(){@Override public void run(){if(done[0])return;Toolkit.getDefaultToolkit().removeAWTEventListener(tracker);currentPicturePopup=null;w.dispose();synchronized(lock){done[0]=true;lock.notifyAll();}}};
			currentPicturePopup=new java.util.function.IntConsumer(){@Override public void accept(int item){released[0]=-1;result.set(item);finish[0].run();}};
			Toolkit.getDefaultToolkit().addAWTEventListener(tracker,AWTEvent.MOUSE_EVENT_MASK|AWTEvent.MOUSE_MOTION_EVENT_MASK|AWTEvent.KEY_EVENT_MASK);
			w.setVisible(true);picturePopupWindow=w;liveView=view;
		}},false);
		int hilited=0;
		for(;;){
			long at;
			synchronized(lock){if(done[0])break;at=asked.getAndSet(-1);if(at==-1){try{lock.wait(100);}catch(InterruptedException e){break;}continue;}}
			if(live==null)continue;
			try{int item=at==-2?live.choose(-1,-1):live.choose((int)(at>>>16),(int)(at&0xffff));
				if(item!=hilited){hilited=item;final java.awt.image.BufferedImage now=live.image();
					javax.swing.SwingUtilities.invokeLater(new Runnable(){@Override public void run(){shown[0]=now;javax.swing.JComponent v=liveView;if(v!=null)v.repaint();}});}}
			catch(M68kException e){if(Mac68kApi.TRACE)System.err.println("[68K] live MDEF: "+e);}
		}
		liveView=null;
		if(live!=null&&released[0]>=0)try{result.set(live.choose((int)(released[0]>>>16),(int)(released[0]&0xffff)));}catch(M68kException e){result.set(0);}
		picturePopupWindow=null;
		if(Mac68kApi.TRACE)System.err.println("[68K] picture pop-up -> item "+result.get());
		return result.get();
	}

	// ------------------------------------------------------------ keys

	static int macKeyCode(int vk,int location){
		switch(vk){
			case KeyEvent.VK_A:return 0;case KeyEvent.VK_S:return 1;case KeyEvent.VK_D:return 2;case KeyEvent.VK_F:return 3;case KeyEvent.VK_H:return 4;case KeyEvent.VK_G:return 5;
			case KeyEvent.VK_Z:return 6;case KeyEvent.VK_X:return 7;case KeyEvent.VK_C:return 8;case KeyEvent.VK_V:return 9;case KeyEvent.VK_B:return 11;case KeyEvent.VK_Q:return 12;
			case KeyEvent.VK_W:return 13;case KeyEvent.VK_E:return 14;case KeyEvent.VK_R:return 15;case KeyEvent.VK_Y:return 16;case KeyEvent.VK_T:return 17;
			case KeyEvent.VK_1:return 18;case KeyEvent.VK_2:return 19;case KeyEvent.VK_3:return 20;case KeyEvent.VK_4:return 21;case KeyEvent.VK_6:return 22;case KeyEvent.VK_5:return 23;
			case KeyEvent.VK_EQUALS:return 24;case KeyEvent.VK_9:return 25;case KeyEvent.VK_7:return 26;case KeyEvent.VK_MINUS:return 27;case KeyEvent.VK_8:return 28;case KeyEvent.VK_0:return 29;
			case KeyEvent.VK_CLOSE_BRACKET:return 30;case KeyEvent.VK_O:return 31;case KeyEvent.VK_U:return 32;case KeyEvent.VK_OPEN_BRACKET:return 33;case KeyEvent.VK_I:return 34;case KeyEvent.VK_P:return 35;
			case KeyEvent.VK_ENTER:return location==KeyEvent.KEY_LOCATION_NUMPAD?76:36;case KeyEvent.VK_L:return 37;case KeyEvent.VK_J:return 38;case KeyEvent.VK_QUOTE:return 39;case KeyEvent.VK_K:return 40;
			case KeyEvent.VK_SEMICOLON:return 41;case KeyEvent.VK_BACK_SLASH:return 42;case KeyEvent.VK_COMMA:return 43;case KeyEvent.VK_SLASH:return 44;case KeyEvent.VK_N:return 45;case KeyEvent.VK_M:return 46;
			case KeyEvent.VK_PERIOD:return 47;case KeyEvent.VK_TAB:return 48;case KeyEvent.VK_SPACE:return 49;case KeyEvent.VK_BACK_QUOTE:return 50;case KeyEvent.VK_BACK_SPACE:return 51;
			case KeyEvent.VK_ESCAPE:return 53;case KeyEvent.VK_META:return 55;case KeyEvent.VK_SHIFT:return 56;case KeyEvent.VK_CAPS_LOCK:return 57;case KeyEvent.VK_ALT:return 58;case KeyEvent.VK_CONTROL:return 59;
			case KeyEvent.VK_DECIMAL:return 65;case KeyEvent.VK_MULTIPLY:return 67;case KeyEvent.VK_ADD:return 69;case KeyEvent.VK_CLEAR:return 71;case KeyEvent.VK_DIVIDE:return 75;case KeyEvent.VK_SUBTRACT:return 78;
			case KeyEvent.VK_NUMPAD0:return 82;case KeyEvent.VK_NUMPAD1:return 83;case KeyEvent.VK_NUMPAD2:return 84;case KeyEvent.VK_NUMPAD3:return 85;case KeyEvent.VK_NUMPAD4:return 86;
			case KeyEvent.VK_NUMPAD5:return 87;case KeyEvent.VK_NUMPAD6:return 88;case KeyEvent.VK_NUMPAD7:return 89;case KeyEvent.VK_NUMPAD8:return 91;case KeyEvent.VK_NUMPAD9:return 92;
			case KeyEvent.VK_F5:return 96;case KeyEvent.VK_F6:return 97;case KeyEvent.VK_F7:return 98;case KeyEvent.VK_F3:return 99;case KeyEvent.VK_F8:return 100;case KeyEvent.VK_F9:return 101;
			case KeyEvent.VK_F11:return 103;case KeyEvent.VK_F13:return 105;case KeyEvent.VK_F14:return 107;case KeyEvent.VK_F10:return 109;case KeyEvent.VK_F12:return 111;case KeyEvent.VK_F15:return 113;
			case KeyEvent.VK_HELP:case KeyEvent.VK_INSERT:return 114;case KeyEvent.VK_HOME:return 115;case KeyEvent.VK_PAGE_UP:return 116;case KeyEvent.VK_DELETE:return 117;case KeyEvent.VK_F4:return 118;
			case KeyEvent.VK_END:return 119;case KeyEvent.VK_F2:return 120;case KeyEvent.VK_PAGE_DOWN:return 121;case KeyEvent.VK_F1:return 122;
			case KeyEvent.VK_LEFT:return 123;case KeyEvent.VK_RIGHT:return 124;case KeyEvent.VK_DOWN:return 125;case KeyEvent.VK_UP:return 126;
			default:return -1;
		}
	}

	int macChar(KeyEvent e){
		switch(e.getKeyCode()){
			case KeyEvent.VK_ENTER:return e.getKeyLocation()==KeyEvent.KEY_LOCATION_NUMPAD?3:13;
			case KeyEvent.VK_TAB:return 9;case KeyEvent.VK_BACK_SPACE:return 8;case KeyEvent.VK_DELETE:return 0x7f;case KeyEvent.VK_ESCAPE:return 0x1b;
			case KeyEvent.VK_LEFT:return 0x1c;case KeyEvent.VK_RIGHT:return 0x1d;case KeyEvent.VK_UP:return 0x1e;case KeyEvent.VK_DOWN:return 0x1f;
			case KeyEvent.VK_HOME:return 1;case KeyEvent.VK_END:return 4;case KeyEvent.VK_PAGE_UP:return 0x0b;case KeyEvent.VK_PAGE_DOWN:return 0x0c;case KeyEvent.VK_HELP:case KeyEvent.VK_INSERT:return 5;
			case KeyEvent.VK_CLEAR:return 0x1b;case KeyEvent.VK_SHIFT:case KeyEvent.VK_CONTROL:case KeyEvent.VK_ALT:case KeyEvent.VK_META:case KeyEvent.VK_CAPS_LOCK:return -1;
			default:break;
		}
		if(e.getKeyCode()>=KeyEvent.VK_F1&&e.getKeyCode()<=KeyEvent.VK_F15)return 0x10;
		char c=e.getKeyChar();
		if(c==KeyEvent.CHAR_UNDEFINED)return -1;
		if((e.getModifiersEx()&(InputEvent.META_DOWN_MASK|InputEvent.CTRL_DOWN_MASK))!=0&&c<0x20&&e.getKeyCode()>=KeyEvent.VK_A&&e.getKeyCode()<=KeyEvent.VK_Z)c=(char)(Character.toLowerCase((char)e.getKeyCode()));
		if(c>=0x80){byte[] bytes=encoding.encodeReplacing(String.valueOf(c));if(bytes.length==1)return bytes[0]&0xff;return -1;}
		return c;
	}

	// ------------------------------------------------------------ utilities

	void runOnEdt(Runnable r,boolean wait){
		if(headless)return;
		if(SwingUtilities.isEventDispatchThread()){r.run();return;}
		try{if(wait)SwingUtilities.invokeAndWait(r);else SwingUtilities.invokeLater(r);}
		catch(InterruptedException e){Thread.currentThread().interrupt();}
		catch(InvocationTargetException e){e.getCause().printStackTrace();}
	}

	/** Runs a blocking UI interaction (file dialogs, alerts) on the EDT and returns its result. */
	<T> T ask(final java.util.concurrent.Callable<T> c){
		if(headless)return null;
		final AtomicReference<T> result=new AtomicReference<T>();
		asking.incrementAndGet();listener.dialogShown();
		try{runOnEdt(new Runnable(){@Override public void run(){try{result.set(c.call());}catch(Exception e){e.printStackTrace();}}},true);}
		finally{asking.decrementAndGet();}
		return result.get();
	}
	/** Dialogs {@link #ask} has up (the program's thread waits for them). */
	private final java.util.concurrent.atomic.AtomicInteger asking=new java.util.concurrent.atomic.AtomicInteger();

	Window anyWindow(){HostWindow hw=frontWindow;if(hw!=null&&hw.window!=null)return hw.window;for(HostWindow w:windowsFrontToBack())if(w.window!=null)return w.window;return null;}

	void dispose(){
		disposed=true;if(refresh!=null)refresh.cancel(false);
		if(limitedExternal)ExternalScreenArea.leave(this);
		if(globalMouse!=null)Toolkit.getDefaultToolkit().removeAWTEventListener(globalMouse);
		final List<HostWindow> all=windowsFrontToBack();
		synchronized(windows){windows.clear();order.clear();}
		runOnEdt(new Runnable(){@Override public void run(){for(HostWindow hw:all)if(hw.window!=null){hw.window.setVisible(false);hw.window.dispose();}cancelStandInTimer();if(standIn!=null){standIn.setVisible(false);standIn.dispose();standIn=null;}
			if(screenMoveSettle!=null){screenMoveSettle.stop();screenMoveSettle=null;}if(screenBarFocus!=null){java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("activeWindow",screenBarFocus);screenBarFocus=null;}if(screenBar!=null){screenBar.dispose();screenBar=null;}
			if(screenFrame!=null){screenFrame.setAlwaysOnTop(false);screenFrame.setVisible(false);screenFrame.dispose();screenFrame=null;} /* the full-screen frame of a program that draws the screen itself (Dark Forces): left behind it stayed black over the launcher */}},false);
	}
}

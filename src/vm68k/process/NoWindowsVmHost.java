import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Cursor;
import java.awt.Image;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.awt.Window;
import java.awt.event.KeyEvent;


/**
 * A VM host without host windows (P4, the browser player): every VmHost call answers as for nothing shown - no windows of
 * the host's, no menus, no pointer - except the window records the Window Manager makes (kept so it finds them again) and
 * the screen's colour table (the main device's, as MacAppHost gives it). PlayerVmHost adds the player's input.
 * Generated from VmHost's methods (tools/wasm-core/rewrites/vmhost_base.py), then the few answers above by hand.
 */
class NoWindowsVmHost implements VmHost {
	protected final VmHostListener listener;
	protected final M68kMemory memory;
	protected final String name;
	protected final boolean headless, external;
	protected QuickDraw68k quickDraw;
	protected MacApp68k owner;
	/** A host window as the Window Manager asked for it (its record and kind; nothing is shown). */
	static final class Win implements VmWindow {
		final int record, procId;
		Win(int record, int procId) { this.record = record; this.procId = procId; }
		public int record() { return record; }
		public int procId() { return procId; }
		public boolean decorated() { return false; }
		public boolean customFrame() { return false; }
	}
	protected final java.util.Map<Integer, Win> windows = new java.util.HashMap<Integer, Win>();
	NoWindowsVmHost(VmHostListener listener, M68kMemory memory, String name, boolean headless, boolean external) {
		this.listener = listener; this.memory = memory; this.name = name; this.headless = headless; this.external = external;
	}

	@Override public boolean headless(){return headless;}
	@Override public boolean external(){return external;}
	@Override public void setQuickDraw(QuickDraw68k quickDraw){this.quickDraw=quickDraw;}
	@Override public void setOwner(MacApp68k owner){this.owner=owner;}
	@Override public void setMenusUpdateOnOpening(boolean on){}
	@Override public boolean adoptDeviceWrites(int record){return false;}
	@Override public boolean adoptDeviceWrites(){return false;}
	@Override public Window anyWindow(){return null;}
	@Override public <T> T ask(final java.util.concurrent.Callable<T> c){return null;}
	@Override public void beginModalWindow(final int record){}
	@Override public boolean behindHyperCard(int record){return false;}
	@Override public void bringToFront(){}
	@Override public void bringToFront(int record,int behind){}
	@Override public boolean capsLocked(){return false;}
	@Override public void configureSmooth(boolean redraw){}
	@Override public VmWindow createWindow(final int record,final int left,final int top,final int width,final int height,final String title,final int procId,final boolean visible,final boolean goAway,final int pixelBase,final int rowBytes,final int depth){Win w=new Win(record,procId);windows.put(record,w);return w;}
	@Override public VmWindow createWindow(final int record,final int left,final int top,final int width,final int height,final String title,final int procId,final boolean visible,final boolean goAway,final int pixelBase,final int rowBytes,final int depth,final boolean customFrame){Win w=new Win(record,procId);windows.put(record,w);return w;}
	@Override public int[] devicePalette(int depth){return devicePalette(depth,null);}
	@Override public int[] devicePalette(int depth,int[] reuse){QuickDraw68k qd=quickDraw;int[] clut=null;try{if(qd!=null)clut=qd.mainDevicePalette(reuse);}catch(M68kCpuException e){clut=null;}
		if(clut==null){clut=reuse!=null&&reuse.length==1<<depth?reuse:new int[1<<depth];for(int i=0;i<clut.length;i++){int v=255-i*255/(clut.length-1);clut[i]=v<<16|v<<8|v;}}return clut;}
	@Override public void directScreenAccess(QuickDraw68k quickDraw){}
	@Override public int[] displacement(int record){return new int[2];}
	@Override public void dispose(){}
	@Override public void disposeWindow(final int record){windows.remove(record);}
	@Override public void endComposition(){}
	@Override public void endModalWindow(final int record){}
	@Override public void eventCall(){}
	@Override public boolean extensionsDone(){return false;}
	@Override public void externalEntered(){}
	@Override public Rectangle externalScreen(){return null;}
	@Override public void followStackZoom(){}
	@Override public void hideBalloon(){}
	@Override public void hideDragOutline(){}
	@Override public void hideWindow(final int record){}
	@Override public void holdSyntheticPointer(Point p){}
	@Override public int hostModifiers(int ex){return 0;}
	@Override public BufferedImage imageOver(int base,int width,int height,int rowBytes){return null;}
	@Override public BufferedImage imageOver(int base,int width,int height,int rowBytes,boolean alpha){return null;}
	@Override public boolean isActiveWindow(int record){return false;}
	@Override public boolean isKeyDown(int keyCode){return false;}
	@Override public boolean isMouseDown(){return false;}
	@Override public void keyMap(byte[] map){}
	@Override public int macChar(KeyEvent e){return 0;}
	@Override public void markFrontTitle(boolean marked){}
	@Override public char menuCommand(int menuId,int item){return 0;}
	@Override public boolean modalWindowUp(){return false;}
	@Override public int modifiers(){return 0;}
	@Override public Point mouseLocation(){return new Point(0,0);}
	@Override public void mousePoll(){}
	@Override public void moveDragOutline(final int x,final int y){}
	@Override public void moveFrame(int record,int left,int top){}
	@Override public void moveWindow(final int record,final int left,final int top){}
	@Override public void moveWindow(final int record,final int left,final int top,boolean byUser){}
	@Override public void moveWindow(final int record,final int left,final int top,boolean byUser,int dispX,int dispY){}
	@Override public void placeBehind(final int record,final int behind){}
	@Override public int popUpLiveMenu(java.awt.image.BufferedImage image,LiveMenu68k live,int globalLeft,int globalTop){return 0;}
	@Override public int popUpMenuSelect(final MenuModel68k model,final int globalLeft,final int globalTop,final int initialItem){return 0;}
	@Override public void postText(String text){}
	@Override public int pressedWindowAt(int h,int v){return 0;}
	@Override public int[] programPlace(int record,int shownX,int shownY){return new int[]{shownX,shownY};}
	@Override public void programWaiting(){}
	@Override public void publishWindowDrawing(){}
	@Override public void repaintNow(int record){}
	@Override public void resizeWindow(final int record,final int width,final int height,final int pixelBase,final int rowBytes,final int depth){}
	@Override public void screenResized(final MacApp68k app,final Rectangle screen){}
	@Override public void screenSaverRestored(){}
	@Override public void selectWindow(final int record){}
	@Override public boolean sendBackdropsBehindHyperCard(Rectangle card){return false;}
	@Override public boolean sendBehindHyperCard(java.awt.Window w){return false;}
	@Override public void setApplicationIcon(BufferedImage icon){}
	@Override public void setCompositionEnabled(final boolean on){}
	@Override public void setCursor(final Cursor c){}
	@Override public void setCustomFrame(final int record){}
	@Override public void setDragOutline(final int x,final int y,final int width,final int height){}
	@Override public void setFloatingLayer(int record){}
	@Override public void setIgnoresDeviceWrites(int record,boolean ignore){}
	@Override public void setMenuBar(List<MenuModel68k> menus){}
	@Override public void setOverlay(int record,Image image){}
	@Override public void setSystemWindow(int record){}
	@Override public void setTitle(final int record,final String title){}
	@Override public void setTitleBand(final int record,final int rows){}
	@Override public void setTitleBand(final int record,final int rows,final int left,final int right){}
	@Override public void setWindoid(int record){}
	@Override public void setWindowResizable(final int record,final boolean resizable){}
	@Override public void setWindowShape(final int record,final java.awt.Shape shape){}
	@Override public void setWindowVariant(final int record,final int kind){}
	@Override public void showBalloon(final String text,final int tipX,final int tipY){}
	@Override public void showBalloonImage(final java.awt.image.BufferedImage image,final int tipX,final int tipY){}
	@Override public void showDragOutline(final int[] rect){}
	@Override public void showWindow(final int record){}
	@Override public void swapMMUMode(){}
	@Override public Point toGuest(Point host){return host;}
	@Override public Rectangle toGuestRect(Rectangle host){return host;}
	@Override public Point toHost(int gx,int gy){return new Point(gx,gy);}
	@Override public Point unpinnedMouseLocation(){return mouseLocation();}
	@Override public int[] userDragArea(){return null;}
	@Override public void warpMouse(int h,int v){}
	@Override public void willShow(int record){}
	@Override public VmWindow window(int record){return windows.get(record);}
	@Override public void windowMovedByUser(int record){}
	@Override public void blitBegin(){}
	@Override public void blitEnd(){}
	@Override public void deactivateHost(VmWindow hw){}
	@Override public String debugOpenMenu(final int id){return null;}
	@Override public String describeFocus(){return null;}
	@Override public String describeWindowSerials(){return null;}
	@Override public void dragScreenBar(int dx,int dy){}
	@Override public void extensionsLoaded(){}
	@Override public Window frontFocusableWindow(){return null;}
	@Override public VmWindow frontWindow(){return null;}
	@Override public void injectActivate(int record){}
	@Override public void injectAwtMouse(final int id,final int sx,final int sy){}
	@Override public void injectClick(int record,int x,int y,int clicks){}
	@Override public void injectClick(int record,int x,int y,int clicks,int extraModifiers){}
	@Override public void injectDeactivate(int record){}
	@Override public void injectInputMethod(int record,final String composing,final String committed){}
	@Override public void injectKey(int record,int ch){}
	@Override public void injectKey(int record,int ch,int modifiers){}
	@Override public void injectOsClick(int record,int x,int y){}
	@Override public void injectPopupChoice(final int item){}
	@Override public void injectPopupChoice(final int item,final int subitem){}
	@Override public void injectWindowMove(int record,final int dx,final int dy){}
	@Override public void invalidateDisplays(){}
	@Override public VmHostListener listener(){return listener;}
	@Override public boolean ownsWindow(Window w){return false;}
	@Override public void postCommandQ(){}
	@Override public boolean processHidden(){return false;}
	@Override public Point rawPointerLocation(){return mouseLocation();}
	@Override public Rectangle screenBarBounds(){return null;}
	@Override public void setKeyState(int keyCode,boolean down){}
	@Override public void setProcessHidden(final boolean hidden){}
	@Override public void simulateMouse(int sx,int sy,boolean down){}
	@Override public SmoothZoom smoothZoom(){return quickDraw==null?null:quickDraw.smooth;}
	@Override public void snapshot(java.io.File dir){}
	@Override public int warpSerial(){return 0;}
	@Override public void fontSearching(boolean busy){}
	@Override public void garbledTextFixed(final String font,final String replacement,final Runnable undo){}
	@Override public void garbledTextNotFound(){}
	@Override public void reposition(final MacApp68k app){}
	@Override public void resmooth(){}
	@Override public void rezoom(final MacApp68k app){}
	@Override public void setCursorHidden(boolean hidden){}
	@Override public int[] windowPixels(int record,int x,int y,int w,int h){return null;}
}

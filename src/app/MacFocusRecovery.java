import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * macOS (seen on macOS 27 with JDK 26): HyperZebra brought forward by a click on one of its windows becomes the active
 * application with NO key window - the click reaches the window, but it never gets the focus, and toFront/requestFocus are
 * ignored afterwards. Menus stay grey (the screen menu bar is the focused frame's), keys go nowhere, and a Mac program's window
 * stays inactive (Woden: arrow keys lost after switching away and clicking back). Activating from the Dock or with Command-Tab
 * is not affected. Showing a window does work: a 1x1 bare frame is shown for a moment, and once it has the focus the window that
 * was clicked takes it over (or, for one that cannot take the focus - a Mac program's backdrop, the frame round a game screen -
 * the program's front window).
 */
final class MacFocusRecovery {
	private MacFocusRecovery(){}
	private static boolean installed;
	/** -Dhz.focusrecovery=false: off. */
	private static final boolean ENABLED=!"false".equals(System.getProperty("hz.focusrecovery"));

	/** Watches the presses in HyperZebra's windows (once; macOS only). */
	static synchronized void install(){
		if(installed||!ENABLED||!GMenu.isMacOSX()||java.awt.GraphicsEnvironment.isHeadless())return;installed=true;
		Toolkit.getDefaultToolkit().addAWTEventListener(e->{
			if(e.getID()!=MouseEvent.MOUSE_PRESSED||KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()!=null)return;
			Object source=e.getSource();Window w=source instanceof Window?(Window)source:source instanceof Component?SwingUtilities.getWindowAncestor((Component)source):null;
			if(w!=null&&w.isShowing())later(w);
		},AWTEvent.MOUSE_EVENT_MASK);
		// a click on a window that cannot take the focus (a Mac program's backdrop) is not delivered at all: the activation is
		// the only sign, and the window under the pointer (else the last one focused) gets the focus
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow",e->{if(e.getNewValue() instanceof Window)lastActive=new java.lang.ref.WeakReference<Window>((Window)e.getNewValue());});
		try{if(java.awt.Desktop.isDesktopSupported()&&java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_EVENT_FOREGROUND))
			java.awt.Desktop.getDesktop().addAppEventListener(new java.awt.desktop.AppForegroundListener(){
				@Override public void appRaisedToForeground(java.awt.desktop.AppForegroundEvent e){SwingUtilities.invokeLater(()->later(null));}
				@Override public void appMovedToBackground(java.awt.desktop.AppForegroundEvent e){}});}catch(RuntimeException ignored){}
	}
	private static java.lang.ref.WeakReference<Window> lastActive=new java.lang.ref.WeakReference<Window>(null);
	/** The window under the pointer (a Mac program's preferred: Java cannot tell which of several is in front), else the last active one. */
	private static Window underPointer(){
		java.awt.Point p;try{java.awt.PointerInfo pi=java.awt.MouseInfo.getPointerInfo();if(pi==null)return lastActive.get();p=pi.getLocation();}catch(RuntimeException e){return lastActive.get();}
		Window any=null;
		for(Window w:Window.getWindows()){if(!w.isShowing()||!w.getBounds().contains(p))continue;if(MacApp68k.applicationOf(w)!=null)return w;if(any==null)any=w;}
		Window last=lastActive.get();return any!=null?any:last!=null&&last.isShowing()?last:null;
	}
	/** The activation is under way when the press arrives: once it is over, if still no window has the focus, it is handed over
	 *  ({@code pressed} null: to the window under the pointer). */
	/** A hand-over is scheduled or under way (the press and the activation both ask for one): one at a time. EDT only. */
	private static boolean busy;
	private static final boolean TRACE=Boolean.getBoolean("hz.focustrace")||Boolean.getBoolean("hz68k.hosttrace");
	private static void trace(String s){if(TRACE)System.err.println("[focus] "+s);}
	private static void later(final Window pressed){
		trace("later("+(pressed==null?"activation":pressed.getName())+") busy="+busy);
		if(busy)return;busy=true;
		javax.swing.Timer t=new javax.swing.Timer(250,ev->{
			if(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()!=null){trace("already focused: "+KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow().getName());busy=false;return;}
			Window clicked=pressed!=null?pressed:underPointer();
			final Window target=clicked==null||!clicked.isShowing()?null:target(clicked);trace("clicked "+(clicked==null?"-":clicked.getName())+" -> target "+(target==null?"-":target.getName()));if(target==null){busy=false;return;}
			final JFrame helper=new JFrame();helper.setUndecorated(true);helper.setBounds(-10,-10,1,1);
			helper.addWindowFocusListener(new WindowAdapter(){@Override public void windowGainedFocus(WindowEvent e){
				trace("helper focused; to "+target.getName());helper.dispose();target.toFront();focusIn(target);busy=false;}});
			helper.setVisible(true);
			javax.swing.Timer cleanup=new javax.swing.Timer(1500,e2->{if(helper.isDisplayable()){trace("helper never focused");helper.dispose();}busy=false;});cleanup.setRepeats(false);cleanup.start();
		});
		t.setRepeats(false);t.start();
	}
	/** The window that should have the focus: the clicked one when it can take it, else its Mac program's front window. */
	private static Window target(Window clicked){
		if(clicked.isFocusableWindow())return clicked;
		MacApp68k app=MacApp68k.applicationOf(clicked);
		Window front=app==null||app.host==null?null:app.host.frontFocusableWindow();
		return front!=null?front:null;
	}
	private static void focusIn(Window w){
		Component c=w.getMostRecentFocusOwner();
		if(c==null&&w instanceof JFrame)c=((JFrame)w).getContentPane();
		if(c!=null)c.requestFocus();else w.requestFocus();
	}
}

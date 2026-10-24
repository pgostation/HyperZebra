import java.awt.AWTException;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Game controllers as a keyboard and mouse. Neither HyperCard stacks nor 68K programs know controllers (the era's
 * games read GetKeys / key events and the mouse), so every control of a pad is bound to a key, a mouse click or the
 * pointer, and the host presses that key for the focused window: 68K programs see it through MacAppHost's key listeners
 * and GetKeys map, stacks through GUI's key handling (keyDown, arrowKey...). Nothing is delivered while HyperZebra
 * is not the active application, and every held control is released when it stops being active.
 * <p>
 * Layers:
 * <ul>
 * <li>a {@link Source} enumerates the {@link Device}s of the platform: {@code NativeGamepadHid} (macOS, IOKit HID through
 *     the Foreign Function API, Java 22+), {@code NativeGamepadXInput} (Windows, XInput) and {@link GamepadLinux}
 *     (/dev/input/js*); each device fills a {@link State} when polled;</li>
 * <li>{@link Engine} turns the states into control edges ("button 3" pressed, "x-" beyond the dead zone, "hat up"...),
 *     looks each control up in the {@link Mapping} that applies (the front 68K program's or the open stack's own one from
 *     LauncherStore, else the preference) and drives an {@link Injector}; it also auto-repeats held keys and moves the pointer for stick axes bound
 *     to it. The engine is pure logic with an injected clock, so GamepadHostTest runs it with a fake device and injector;</li>
 * <li>{@link AwtInjector} posts synthetic KeyEvents to the focus owner and moves/clicks the pointer with a Robot.</li>
 * </ul>
 * Control names ({@link Mapping}): {@code button N} (1-based), {@code hat up/down/left/right}, and each axis in both
 * directions {@code x-}, {@code x+}, {@code y-}, ... ({@link #AXIS_NAMES}; sticks rest at 0, triggers at -1). Bindings:
 * a key by its KeyEvent VK name, {@code click} (mouse button), {@code mouse h} / {@code mouse v} (an axis moves the pointer).
 * The polling thread starts from PCARD.main ({@link #start()}) unless -Dhz.gamepad=false or the display is headless.
 */
final class GamepadHost {
	static final boolean TRACE=Boolean.getBoolean("hz.gamepad.trace");
	static final int MAX_BUTTONS=32, AXES=8;
	/** Axis order of {@link State#axes}: sticks X/Y (left), Z/Rz or Rx/Ry (right, depends on the pad), Rx/Ry, then the triggers. */
	static final String[] AXIS_NAMES={"x","y","z","rx","ry","rz","lt","rt"};
	static final String[] HAT_NAMES={"hat up","hat down","hat left","hat right"};
	/** Stick deflection that counts as a key press, and the smaller one it must fall under to release (hysteresis). */
	static final float PRESS_THRESHOLD=0.5f, RELEASE_THRESHOLD=0.35f;
	/** Pointer motion: deflection ignored, and pixels per second at full deflection (the curve is quadratic). */
	static final float MOUSE_DEAD_ZONE=0.15f, MOUSE_SPEED=600f;
	static final long REPEAT_DELAY_MS=500, REPEAT_INTERVAL_MS=50, POLL_INTERVAL_MS=8;

	/** What a device reports when polled. */
	static final class State {
		final boolean[] buttons=new boolean[MAX_BUTTONS];
		/** -1..1 per {@link #AXIS_NAMES} entry. */
		final float[] axes=new float[AXES];
		/** Hat switch: 0 = up, clockwise in eighths (2 = right, 4 = down, 6 = left), -1 = centred. */
		int hat=-1;
		void clear(){java.util.Arrays.fill(buttons,false);java.util.Arrays.fill(axes,0f);hat=-1;}
	}
	interface Device {
		String name();
		/** Fills {@code into} with the current state; false once the device is gone. Called on the polling thread. */
		boolean poll(State into);
	}
	interface Source {
		/** The devices present now (hot-plugging is the source's business); called on the polling thread. */
		List<Device> devices();
	}
	/** Where the engine's presses go. */
	interface Injector {
		/** Whether the application takes input now (its window is active); false releases everything. */
		boolean active();
		/** The mapping for whatever is in front now. */
		Mapping mapping();
		void press(Binding b);
		void release(Binding b);
		/** Auto-repeat of a held key binding. */
		void repeat(Binding b);
		void moveMouse(float dx,float dy);
	}
	/** Told of controls as they go down, for the mapping editor's "press a control" (any thread). */
	interface ControlListener { void controlPressed(Device device,String control); }

	// ------------------------------------------------------------------ bindings and mappings

	static final class Binding {
		static final int KEY=0, CLICK=1, MOUSE_H=2, MOUSE_V=3;
		final int kind; final int vk;
		private Binding(int kind,int vk){this.kind=kind;this.vk=vk;}
		static Binding key(int vk){return new Binding(KEY,vk);}
		static final Binding click=new Binding(CLICK,0), mouseH=new Binding(MOUSE_H,0), mouseV=new Binding(MOUSE_V,0);
		boolean isKey(){return kind==KEY;}
		boolean isMouseAxis(){return kind==MOUSE_H||kind==MOUSE_V;}
		/** The stored form: a VK name (without "VK_"), {@code click}, {@code mouse h} or {@code mouse v}. */
		String format(){
			switch(kind){case CLICK:return "click";case MOUSE_H:return "mouse h";case MOUSE_V:return "mouse v";default:return vkName(vk);}
		}
		static Binding parse(String s){
			if(s==null)return null;s=s.trim();
			if(s.equalsIgnoreCase("click"))return click;
			if(s.equalsIgnoreCase("mouse h"))return mouseH;
			if(s.equalsIgnoreCase("mouse v"))return mouseV;
			int vk=vkOf(s);return vk<0?null:key(vk);
		}
		/** What the user sees. */
		String describe(){
			switch(kind){
				case CLICK:return GPreferencesDialog.dialogText("Mouse click");
				case MOUSE_H:return GPreferencesDialog.dialogText("Mouse pointer (horizontal)");
				case MOUSE_V:return GPreferencesDialog.dialogText("Mouse pointer (vertical)");
				default:return keyText(vk);
			}
		}
		/** The key's name in words (macOS's getKeyText draws symbols for Space, Escape and the arrows). */
		static String keyText(int vk){
			String n;
			switch(vk){
				case KeyEvent.VK_SPACE:n="Space";break;case KeyEvent.VK_ESCAPE:n="Escape";break;case KeyEvent.VK_ENTER:n="Return";break;case KeyEvent.VK_TAB:n="Tab";break;
				case KeyEvent.VK_BACK_SPACE:n="Delete";break;case KeyEvent.VK_DELETE:n="Forward Delete";break;
				case KeyEvent.VK_LEFT:n="Left arrow";break;case KeyEvent.VK_RIGHT:n="Right arrow";break;case KeyEvent.VK_UP:n="Up arrow";break;case KeyEvent.VK_DOWN:n="Down arrow";break;
				case KeyEvent.VK_SHIFT:n="Shift";break;case KeyEvent.VK_CONTROL:n="Control";break;case KeyEvent.VK_ALT:n="Option";break;case KeyEvent.VK_META:n="Command";break;
				default:return KeyEvent.getKeyText(vk);
			}
			return GPreferencesDialog.dialogText(n);
		}
		@Override public boolean equals(Object o){return o instanceof Binding&&((Binding)o).kind==kind&&((Binding)o).vk==vk;}
		@Override public int hashCode(){return kind*1000+vk;}
		@Override public String toString(){return format();}
	}

	private static Map<String,Integer> vkByName;private static Map<Integer,String> nameByVk;
	private static synchronized void loadVkNames(){
		if(vkByName!=null)return;
		Map<String,Integer> byName=new HashMap<String,Integer>();Map<Integer,String> byVk=new HashMap<Integer,String>();
		for(Field f:KeyEvent.class.getFields()){
			if(!Modifier.isStatic(f.getModifiers())||f.getType()!=int.class||!f.getName().startsWith("VK_"))continue;
			try{int v=f.getInt(null);String n=f.getName().substring(3);byName.put(n,v);if(!byVk.containsKey(v))byVk.put(v,n);}catch(IllegalAccessException ignored){}
		}
		vkByName=byName;nameByVk=byVk;
	}
	static String vkName(int vk){loadVkNames();String n=nameByVk.get(vk);return n!=null?n:Integer.toString(vk);}
	static int vkOf(String name){
		loadVkNames();Integer v=vkByName.get(name.toUpperCase(java.util.Locale.ROOT));
		if(v!=null)return v;
		try{return Integer.parseInt(name);}catch(NumberFormatException e){return -1;}
	}

	/** Control name → binding. Formatted as {@code control=binding;control=binding;...}. */
	static final class Mapping {
		final LinkedHashMap<String,Binding> bindings=new LinkedHashMap<String,Binding>();
		Binding get(String control){return bindings.get(control);}
		void put(String control,Binding b){if(b==null)bindings.remove(control);else bindings.put(control,b);}
		boolean isEmpty(){return bindings.isEmpty();}
		Mapping copy(){Mapping m=new Mapping();m.bindings.putAll(bindings);return m;}
		String format(){
			StringBuilder sb=new StringBuilder();
			for(Map.Entry<String,Binding> e:bindings.entrySet()){if(sb.length()>0)sb.append(';');sb.append(e.getKey()).append('=').append(e.getValue().format());}
			return sb.toString();
		}
		static Mapping parse(String s){
			Mapping m=new Mapping();if(s==null)return m;
			for(String item:s.split(";")){
				int eq=item.indexOf('=');if(eq<0)continue;
				String control=item.substring(0,eq).trim(),value=item.substring(eq+1).trim();
				if(control.isEmpty()||!isControlName(control))continue;
				Binding b=Binding.parse(value);if(b!=null)m.bindings.put(control,b);
			}
			return m;
		}
		/** Face buttons 1-4 → Space, Escape, Return, Tab; the hat and the left stick → arrows; 5/6 (shoulders) → Z / X;
		 *  7/8 (select / start) → Escape / Return; the right stick → the pointer, its button (10) → click. */
		static Mapping defaults(){
			Mapping m=new Mapping();
			m.put("button 1",Binding.key(KeyEvent.VK_SPACE));m.put("button 2",Binding.key(KeyEvent.VK_ESCAPE));
			m.put("button 3",Binding.key(KeyEvent.VK_ENTER));m.put("button 4",Binding.key(KeyEvent.VK_TAB));
			m.put("button 5",Binding.key(KeyEvent.VK_Z));m.put("button 6",Binding.key(KeyEvent.VK_X));
			m.put("button 7",Binding.key(KeyEvent.VK_ESCAPE));m.put("button 8",Binding.key(KeyEvent.VK_ENTER));
			m.put("button 10",Binding.click);
			m.put("hat up",Binding.key(KeyEvent.VK_UP));m.put("hat down",Binding.key(KeyEvent.VK_DOWN));
			m.put("hat left",Binding.key(KeyEvent.VK_LEFT));m.put("hat right",Binding.key(KeyEvent.VK_RIGHT));
			m.put("x-",Binding.key(KeyEvent.VK_LEFT));m.put("x+",Binding.key(KeyEvent.VK_RIGHT));
			m.put("y-",Binding.key(KeyEvent.VK_UP));m.put("y+",Binding.key(KeyEvent.VK_DOWN));
			m.put("rx+",Binding.mouseH);m.put("ry+",Binding.mouseV);m.put("z+",Binding.mouseH);m.put("rz+",Binding.mouseV);
			return m;
		}
	}
	static boolean isControlName(String c){
		if(c.startsWith("button ")){try{int n=Integer.parseInt(c.substring(7));return n>=1&&n<=MAX_BUTTONS;}catch(NumberFormatException e){return false;}}
		for(String h:HAT_NAMES)if(h.equals(c))return true;
		return axisOf(c)>=0;
	}
	/** The axis index of "x-" / "rz+", else -1. */
	static int axisOf(String control){
		if(control.length()<2)return -1;
		char sign=control.charAt(control.length()-1);if(sign!='-'&&sign!='+')return -1;
		String name=control.substring(0,control.length()-1);
		for(int i=0;i<AXIS_NAMES.length;i++)if(AXIS_NAMES[i].equals(name))return i;
		return -1;
	}
	/** Every control name, for the editor. */
	static List<String> allControls(){
		List<String> r=new ArrayList<String>();
		for(int i=1;i<=MAX_BUTTONS;i++)r.add("button "+i);
		Collections.addAll(r,HAT_NAMES);
		for(String a:AXIS_NAMES){r.add(a+"-");r.add(a+"+");}
		return r;
	}
	/** The control name as shown: "Button 3", "Hat up", "Left stick left"... */
	static String describeControl(String c){
		if(c.startsWith("button "))return GPreferencesDialog.dialogText("Button")+" "+c.substring(7);
		for(int i=0;i<HAT_NAMES.length;i++)if(HAT_NAMES[i].equals(c))return GPreferencesDialog.dialogText(new String[]{"Hat up","Hat down","Hat left","Hat right"}[i]);
		int axis=axisOf(c);if(axis<0)return c;
		String dir=GPreferencesDialog.dialogText(c.endsWith("-")?"-":"+");
		return GPreferencesDialog.dialogText("Axis")+" "+AXIS_NAMES[axis].toUpperCase(java.util.Locale.ROOT)+" "+dir;
	}

	// ------------------------------------------------------------------ preferences

	private static final java.util.prefs.Preferences prefs=java.util.prefs.Preferences.userNodeForPackage(GamepadHost.class);
	static volatile boolean enabled=prefs.getBoolean("enabled",true);
	private static volatile Mapping preferenceMapping;
	static synchronized Mapping preferenceMapping(){
		if(preferenceMapping==null){String s=prefs.get("mapping",null);preferenceMapping=s==null?Mapping.defaults():Mapping.parse(s);}
		return preferenceMapping;
	}
	static synchronized void setPreferenceMapping(Mapping m){preferenceMapping=m.copy();prefs.put("mapping",m.format());}
	static void setEnabled(boolean on){enabled=on;prefs.putBoolean("enabled",on);}

	// ------------------------------------------------------------------ the engine

	/** Presses and releases as the states change; one instance per polling thread. */
	static final class Engine {
		interface Clock { long now(); }
		private final Injector injector;private final Clock clock;
		private final List<ControlListener> listeners=new ArrayList<ControlListener>();
		/** Per device: the controls down now, each with the binding it pressed (null = unbound), so a release finds the
		 *  same binding even when the mapping changed meanwhile (another program came to the front). */
		private final Map<Device,Map<String,Binding>> down=new HashMap<Device,Map<String,Binding>>();
		/** Held key bindings with their press time and last repeat, for auto-repeat; a binding held by several controls counts once. */
		private final Map<Binding,long[]> held=new LinkedHashMap<Binding,long[]>();
		private final Map<Binding,Integer> holders=new HashMap<Binding,Integer>();
		private final State state=new State();
		private long lastTick=-1;
		private boolean wasActive;
		/** The bindings editor is open: controls reach the listeners but nothing is pressed. */
		private volatile boolean captureOnly;
		Engine(Injector injector,Clock clock){this.injector=injector;this.clock=clock;}
		synchronized void setCaptureOnly(boolean on){if(on&&!captureOnly)releaseAll();captureOnly=on;}
		synchronized void addListener(ControlListener l){listeners.add(l);}
		synchronized void removeListener(ControlListener l){listeners.remove(l);}
		/** The controls down on any device now (the editor's live view). */
		synchronized Set<String> controlsDown(){Set<String> r=new LinkedHashSet<String>();for(Map<String,Binding> s:down.values())r.addAll(s.keySet());return r;}

		/** One poll of every device. */
		synchronized void tick(List<Device> devices){
			long now=clock.now();float dt=lastTick<0?0f:(now-lastTick)/1000f;lastTick=now;
			boolean active=injector.active();
			if(!active){if(wasActive)releaseAll();wasActive=false;return;}
			wasActive=true;
			Mapping current=injector.mapping();if(current==null)current=Mapping.defaults();
			float mouseDx=0,mouseDy=0;
			Set<Device> seen=new HashSet<Device>();
			for(Device d:devices){
				state.clear();
				if(!d.poll(state))continue;
				seen.add(d);
				Map<String,Binding> was=down.get(d);if(was==null){was=new LinkedHashMap<String,Binding>();down.put(d,was);}
				List<String> is=new ArrayList<String>();
				for(int i=0;i<MAX_BUTTONS;i++)if(state.buttons[i])is.add("button "+(i+1));
				if(state.hat>=0){int h=state.hat&7;if(h==7||h==0||h==1)is.add("hat up");if(h>=1&&h<=3)is.add("hat right");if(h>=3&&h<=5)is.add("hat down");if(h>=5&&h<=7)is.add("hat left");}
				for(int a=0;a<AXES;a++){
					float v=state.axes[a];
					String neg=AXIS_NAMES[a]+"-",pos=AXIS_NAMES[a]+"+";
					// an axis bound to the pointer is analog: no key edges, motion instead
					Binding bn=current.get(neg),bp=current.get(pos);
					Binding mouse=bp!=null&&bp.isMouseAxis()?bp:bn!=null&&bn.isMouseAxis()?bn:null;
					if(mouse!=null){
						float m=Math.abs(v)<=MOUSE_DEAD_ZONE?0f:(Math.abs(v)-MOUSE_DEAD_ZONE)/(1f-MOUSE_DEAD_ZONE);
						float px=Math.signum(v)*m*m*MOUSE_SPEED*dt;
						if(mouse.kind==Binding.MOUSE_H)mouseDx+=px;else mouseDy+=px;
						continue;
					}
					if(v<=-PRESS_THRESHOLD||(v<=-RELEASE_THRESHOLD&&was.containsKey(neg)))is.add(neg);
					if(v>=PRESS_THRESHOLD||(v>=RELEASE_THRESHOLD&&was.containsKey(pos)))is.add(pos);
				}
				// releases before presses: a game that keeps the last key seen gets the new direction
				for(Map.Entry<String,Binding> e:was.entrySet())if(!is.contains(e.getKey()))released(e.getValue());
				Map<String,Binding> next=new LinkedHashMap<String,Binding>();
				for(String c:is){
					if(was.containsKey(c))next.put(c,was.get(c));
					else{Binding b=captureOnly?null:current.get(c);if(b!=null&&b.isMouseAxis())b=null;next.put(c,b);pressed(d,c,b);}
				}
				down.put(d,next);
			}
			// devices that went away release what they held
			for(Device d:new ArrayList<Device>(down.keySet()))if(!seen.contains(d)){for(Binding b:down.remove(d).values())released(b);}
			if((mouseDx!=0||mouseDy!=0)&&!captureOnly)injector.moveMouse(mouseDx,mouseDy);
			// auto-repeat of held keys
			for(Map.Entry<Binding,long[]> e:held.entrySet()){
				long[] t=e.getValue();
				if(now-t[0]>=REPEAT_DELAY_MS&&now-t[1]>=REPEAT_INTERVAL_MS){t[1]=now;injector.repeat(e.getKey());}
			}
		}
		private void pressed(Device d,String control,Binding b){
			List<ControlListener> copy=new ArrayList<ControlListener>(listeners);
			for(ControlListener l:copy)l.controlPressed(d,control);
			if(b==null||captureOnly)return;
			Integer n=holders.get(b);
			if(n==null||n==0){holders.put(b,1);injector.press(b);if(b.isKey())held.put(b,new long[]{clock.now(),clock.now()});}
			else holders.put(b,n+1);
		}
		private void released(Binding b){
			if(b==null)return;
			Integer n=holders.get(b);if(n==null||n==0)return;
			if(n==1){holders.remove(b);held.remove(b);injector.release(b);}else holders.put(b,n-1);
		}
		/** Everything up (focus lost, engine stopped). */
		synchronized void releaseAll(){
			for(Binding b:new ArrayList<Binding>(holders.keySet())){if(holders.get(b)>0)injector.release(b);}
			holders.clear();held.clear();down.clear();
		}
	}

	// ------------------------------------------------------------------ the AWT injector

	/** Synthetic key events to the focus owner; the pointer through a Robot. */
	static final class AwtInjector implements Injector {
		private Robot robot;private boolean robotTried;
		/** Modifier keys held through bindings, as extended modifier bits for the events that follow. */
		private int modifiers;
		private float mouseRemX,mouseRemY;
		@Override public boolean active(){return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()!=null;}
		private String appMappingText;private Mapping appMapping;
		@Override public Mapping mapping(){
			Window w=KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
			MacApp68k app=w==null?null:MacApp68k.applicationOf(w);
			String own=null;
			if(app!=null&&LauncherStore.enabled){
				LauncherStore.AppSettings s=LauncherStore.get().settingsFor(app.file);
				if(s!=null)own=s.gamepadMapping;
			}
			else if(w!=null&&PCARD.pc!=null&&(w==PCARD.pc||w.getOwner()==PCARD.pc))own=StackSettings.gamepadMapping(); // the card window (or its palettes): the open stack's own
			if(own!=null){
				if(!own.equals(appMappingText)){appMappingText=own;appMapping=Mapping.parse(appMappingText);}
				return appMapping;
			}
			return preferenceMapping();
		}
		@Override public void press(Binding b){
			if(b.kind==Binding.CLICK){Robot r=robot();if(r!=null)r.mousePress(InputEvent.BUTTON1_DOWN_MASK);return;}
			int m=modifierBit(b.vk);if(m!=0)modifiers|=m;
			postKey(KeyEvent.KEY_PRESSED,b.vk);
		}
		@Override public void release(Binding b){
			if(b.kind==Binding.CLICK){Robot r=robot();if(r!=null)r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);return;}
			int m=modifierBit(b.vk);if(m!=0)modifiers&=~m;
			postKey(KeyEvent.KEY_RELEASED,b.vk);
		}
		@Override public void repeat(Binding b){if(b.isKey()&&modifierBit(b.vk)==0)postKey(KeyEvent.KEY_PRESSED,b.vk);}
		@Override public void moveMouse(float dx,float dy){
			Robot r=robot();if(r==null)return;
			mouseRemX+=dx;mouseRemY+=dy;int ix=Math.round(mouseRemX),iy=Math.round(mouseRemY);
			if(ix==0&&iy==0)return;
			mouseRemX-=ix;mouseRemY-=iy;
			PointerInfo pi=MouseInfo.getPointerInfo();if(pi==null)return;
			Point p=pi.getLocation();Rectangle bounds=pi.getDevice().getDefaultConfiguration().getBounds();
			int x=Math.max(bounds.x,Math.min(bounds.x+bounds.width-1,p.x+ix)),y=Math.max(bounds.y,Math.min(bounds.y+bounds.height-1,p.y+iy));
			r.mouseMove(x,y);
		}
		private Robot robot(){
			if(!robotTried){robotTried=true;try{robot=new Robot();robot.setAutoDelay(0);}catch(AWTException|SecurityException e){System.err.println("[gamepad] no Robot for the pointer: "+e.getMessage());}}
			return robot;
		}
		static int modifierBit(int vk){
			switch(vk){case KeyEvent.VK_SHIFT:return InputEvent.SHIFT_DOWN_MASK;case KeyEvent.VK_CONTROL:return InputEvent.CTRL_DOWN_MASK;
				case KeyEvent.VK_ALT:return InputEvent.ALT_DOWN_MASK;case KeyEvent.VK_META:return InputEvent.META_DOWN_MASK;default:return 0;}
		}
		/** The character a key press types, or CHAR_UNDEFINED. */
		char keyChar(int vk){
			boolean shift=(modifiers&InputEvent.SHIFT_DOWN_MASK)!=0;
			switch(vk){
				case KeyEvent.VK_ENTER:return '\n';case KeyEvent.VK_TAB:return '\t';case KeyEvent.VK_SPACE:return ' ';case KeyEvent.VK_ESCAPE:return 27;
				case KeyEvent.VK_BACK_SPACE:return '\b';case KeyEvent.VK_DELETE:return 127;
				case KeyEvent.VK_MINUS:return shift?'_':'-';case KeyEvent.VK_EQUALS:return shift?'+':'=';case KeyEvent.VK_COMMA:return shift?'<':',';
				case KeyEvent.VK_PERIOD:return shift?'>':'.';case KeyEvent.VK_SLASH:return shift?'?':'/';case KeyEvent.VK_SEMICOLON:return shift?':':';';
				case KeyEvent.VK_QUOTE:return shift?'"':'\'';case KeyEvent.VK_OPEN_BRACKET:return shift?'{':'[';case KeyEvent.VK_CLOSE_BRACKET:return shift?'}':']';
				case KeyEvent.VK_BACK_SLASH:return shift?'|':'\\';case KeyEvent.VK_BACK_QUOTE:return shift?'~':'`';
				default:break;
			}
			if(vk>=KeyEvent.VK_A&&vk<=KeyEvent.VK_Z)return shift?(char)vk:Character.toLowerCase((char)vk);
			if(vk>=KeyEvent.VK_0&&vk<=KeyEvent.VK_9)return shift?")!@#$%^&*(".charAt(vk-KeyEvent.VK_0):(char)vk;
			if(vk>=KeyEvent.VK_NUMPAD0&&vk<=KeyEvent.VK_NUMPAD9)return (char)('0'+vk-KeyEvent.VK_NUMPAD0);
			return KeyEvent.CHAR_UNDEFINED;
		}
		private void postKey(final int id,final int vk){
			final int mods=modifiers;
			EventQueue.invokeLater(new Runnable(){public void run(){
				KeyboardFocusManager kfm=KeyboardFocusManager.getCurrentKeyboardFocusManager();
				Component target=kfm.getFocusOwner();
				if(target==null)target=kfm.getActiveWindow();
				if(target==null)return;
				long when=System.currentTimeMillis();
				char c=keyChar(vk);
				int location=vk>=KeyEvent.VK_NUMPAD0&&vk<=KeyEvent.VK_NUMPAD9?KeyEvent.KEY_LOCATION_NUMPAD:modifierBit(vk)!=0?KeyEvent.KEY_LOCATION_LEFT:KeyEvent.KEY_LOCATION_STANDARD;
				EventQueue q=Toolkit.getDefaultToolkit().getSystemEventQueue();
				q.postEvent(new KeyEvent(target,id,when,mods,vk,c,location));
				// a press of a character key also types it (KEY_TYPED), unless Command/Control make it a shortcut
				if(id==KeyEvent.KEY_PRESSED&&c!=KeyEvent.CHAR_UNDEFINED&&(mods&(InputEvent.META_DOWN_MASK|InputEvent.CTRL_DOWN_MASK))==0)
					q.postEvent(new KeyEvent(target,KeyEvent.KEY_TYPED,when,mods,KeyEvent.VK_UNDEFINED,c,KeyEvent.KEY_LOCATION_UNKNOWN));
			}});
		}
	}

	// ------------------------------------------------------------------ the polling thread

	private static GamepadHost instance;
	private final Source source;final Engine engine;
	private volatile boolean running=true;
	private final Thread thread;
	private volatile List<Device> lastDevices=Collections.emptyList();

	private GamepadHost(Source source,Injector injector){
		this.source=source;
		engine=new Engine(injector,new Engine.Clock(){public long now(){return System.currentTimeMillis();}});
		thread=new Thread(new Runnable(){public void run(){loop();}},"Gamepad poll");
		thread.setDaemon(true);
	}
	private void loop(){
		long next=System.currentTimeMillis();
		while(running){
			try{
				if(enabled){List<Device> devices=source.devices();lastDevices=devices;engine.tick(devices);}
				else if(!engine.controlsDown().isEmpty())engine.releaseAll();
			}catch(Throwable t){if(TRACE)t.printStackTrace();}
			next+=POLL_INTERVAL_MS;long wait=next-System.currentTimeMillis();
			if(wait<0){next=System.currentTimeMillis();wait=0;}
			try{Thread.sleep(wait);}catch(InterruptedException e){return;}
		}
		engine.releaseAll();
	}

	/** Starts polling the platform's controllers (once; nothing without a display, with -Dhz.gamepad=false or when no source exists). */
	static synchronized void start(){
		if(instance!=null||"false".equals(System.getProperty("hz.gamepad"))||GraphicsEnvironment.isHeadless())return;
		Source source=platformSource();
		if(source==null){if(TRACE)System.err.println("[gamepad] no controller source on this platform / JDK");return;}
		instance=new GamepadHost(source,new AwtInjector());
		instance.thread.start();
	}
	static synchronized GamepadHost get(){return instance;}
	/** Names of the controllers seen at the last poll (the settings dialog). */
	static List<String> deviceNames(){
		GamepadHost h=get();List<String> r=new ArrayList<String>();
		if(h!=null)for(Device d:h.lastDevices)r.add(d.name());
		return r;
	}
	/** Whether controllers can be read at all here (a source exists). */
	static boolean available(){return get()!=null;}

	private static Source platformSource(){
		String os=System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
		String cls=os.startsWith("mac")?"NativeGamepadHid":os.startsWith("windows")?"NativeGamepadXInput":null;
		if(cls!=null){
			try{return (Source)Class.forName(cls).getDeclaredConstructor().newInstance();}
			catch(Throwable t){if(TRACE)System.err.println("[gamepad] "+cls+" not available: "+t);return null;}
		}
		if(os.contains("linux"))return new GamepadLinux();
		return null;
	}
}

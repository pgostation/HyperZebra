import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game controllers on Linux through the joystick interface (/dev/input/js*): each device is read by its own thread
 * (8-byte js_event records: time, value, type, number) into a state the polling thread copies. Axes 0-5 are the
 * generic X, Y, Z, Rx, Ry, Rz of {@link GamepadHost#AXIS_NAMES}, axes 6/7 the hat (xpad and most HID pads), buttons by
 * number. Devices are re-scanned every two seconds for hot-plugging; a read error means the device is gone.
 * <p>
 * The kernel also gives pointing devices with absolute coordinates a js node (VirtualBox/VMware USB tablets and mouse
 * integration, some graphics tablets): their X/Y follow the pointer and their buttons are the mouse buttons, so read as
 * a pad they would press keys and click by themselves. {@link #isGameController} keeps only real controllers.
 */
final class GamepadLinux implements GamepadHost.Source {
	private static final long RESCAN_MS=2000;
	private final Map<String,Joystick> open=new LinkedHashMap<String,Joystick>();
	/** js nodes found to be pointers (path → device name), so a skipped device is not checked or traced again. */
	private final Map<String,String> skipped=new LinkedHashMap<String,String>();
	private long lastScan=-RESCAN_MS;

	@Override public List<GamepadHost.Device> devices(){
		long now=System.currentTimeMillis();
		if(now-lastScan>=RESCAN_MS){
			lastScan=now;
			for(int i=0;i<16;i++){
				File f=new File("/dev/input/js"+i);String key=f.getPath();
				Joystick j=open.get(key);
				if(j!=null&&j.gone){open.remove(key);j=null;}
				if(j!=null)continue;
				if(!f.exists()){skipped.remove(key);continue;}
				File sys=new File("/sys/class/input/js"+i);String devName=sysName(sys);
				if(devName.equals(skipped.get(key)))continue;
				skipped.remove(key);
				if(!isGameController(sys,new File("/run/udev/data"))){
					skipped.put(key,devName);
					if(GamepadHost.TRACE)System.err.println("[gamepad] "+f+" ("+devName+") is a pointing device, ignored");
					continue;
				}
				if(f.canRead()){try{open.put(key,new Joystick(f,i));}catch(IOException e){if(GamepadHost.TRACE)System.err.println("[gamepad] "+f+": "+e);}}
			}
		}
		return new ArrayList<GamepadHost.Device>(open.values());
	}

	private static String sysName(File jsSys){
		try{return new String(Files.readAllBytes(new File(jsSys,"device/name").toPath()),StandardCharsets.UTF_8).trim();}catch(IOException|RuntimeException e){return "";}
	}

	private static final int BTN_LEFT=0x110,BTN_JOYSTICK=0x120,BTN_GAMEPAD_LAST=0x13f,BTN_TOOL_PEN=0x140,BTN_TOOL_FINGER=0x145,
		BTN_TOUCH=0x14a,BTN_STYLUS=0x14b,BTN_TRIGGER_HAPPY=0x2c0,BTN_TRIGGER_HAPPY_LAST=0x2e7;

	/**
	 * Whether the js node whose sysfs directory is {@code jsSys} (/sys/class/input/jsN) is a game controller rather
	 * than a mouse, tablet or touchpad. udev's classification (ID_INPUT_JOYSTICK / ID_INPUT_MOUSE… in
	 * {@code udevData}/c&lt;major&gt;:&lt;minor&gt;) decides when present; otherwise the input device's key capabilities:
	 * joystick/gamepad buttons (BTN_JOYSTICK…BTN_GAMEPAD, BTN_TRIGGER_HAPPY) make a controller, mouse, pen or touch
	 * buttons or a mouse handler (mouseN beside jsN) a pointer. Unknown is a controller, as before.
	 */
	static boolean isGameController(File jsSys,File udevData){
		String dev=readTrimmed(new File(jsSys,"dev"));
		if(dev!=null&&udevData!=null){
			String props=readTrimmed(new File(udevData,"c"+dev));
			if(props!=null){
				if(props.contains("E:ID_INPUT_JOYSTICK=1"))return true;
				for(String k:new String[]{"MOUSE","TABLET","TOUCHPAD","TOUCHSCREEN"})if(props.contains("E:ID_INPUT_"+k+"=1"))return false;
			}
		}
		File input=new File(jsSys,"device");
		long[] keys=bitmap(readTrimmed(new File(input,"capabilities/key")));
		if(anyBit(keys,BTN_JOYSTICK,BTN_GAMEPAD_LAST)||anyBit(keys,BTN_TRIGGER_HAPPY,BTN_TRIGGER_HAPPY_LAST))return true;
		for(int b:new int[]{BTN_LEFT,BTN_TOOL_PEN,BTN_TOOL_FINGER,BTN_TOUCH,BTN_STYLUS})if(anyBit(keys,b,b))return false;
		String[] handlers=input.list();
		if(handlers!=null)for(String h:handlers)if(h.startsWith("mouse"))return false;
		return true;
	}
	private static String readTrimmed(File f){
		try{return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8).trim();}catch(IOException|RuntimeException e){return null;}
	}
	/**
	 * A sysfs capability bitmap ("hex words, most significant first", leading zero words left out) as 64-bit words,
	 * least significant first. The kernel prints longs: 64-bit words when any word is wider than 8 digits or the JVM is
	 * 64-bit, else 32-bit.
	 */
	static long[] bitmap(String text){
		if(text==null||text.isEmpty())return new long[0];
		String[] w=text.split("\\s+");
		boolean wide="64".equals(System.getProperty("sun.arch.data.model"));
		for(String s:w)if(s.length()>8)wide=true;
		int bits=wide?64:32;
		long[] out=new long[(w.length*bits+63)/64];
		for(int i=0;i<w.length;i++){
			long v;try{v=Long.parseUnsignedLong(w[w.length-1-i],16);}catch(NumberFormatException e){continue;}
			int at=i*bits;out[at/64]|=v<<(at%64);
		}
		return out;
	}
	private static boolean anyBit(long[] map,int from,int to){
		for(int b=from;b<=to;b++)if(b/64<map.length&&(map[b/64]>>>(b%64)&1)!=0)return true;
		return false;
	}

	static final class Joystick implements GamepadHost.Device {
		private final String name;private final InputStream in;
		private final GamepadHost.State state=new GamepadHost.State();
		private int hatX,hatY;
		volatile boolean gone;
		Joystick(File f,int index) throws IOException {
			in=new FileInputStream(f);
			String n=null;
			try{n=new String(Files.readAllBytes(new File("/sys/class/input/js"+index+"/device/name").toPath()),StandardCharsets.UTF_8).trim();}catch(IOException|RuntimeException ignored){}
			name=n==null||n.isEmpty()?"Joystick "+index:n;
			Thread t=new Thread(new Runnable(){public void run(){read();}},"Gamepad "+f.getName());
			t.setDaemon(true);t.start();
		}
		@Override public String name(){return name;}
		@Override public boolean poll(GamepadHost.State into){
			if(gone)return false;
			synchronized(state){System.arraycopy(state.buttons,0,into.buttons,0,into.buttons.length);System.arraycopy(state.axes,0,into.axes,0,into.axes.length);into.hat=state.hat;}
			return true;
		}
		private void read(){
			byte[] e=new byte[8];
			try{
				while(true){
					int got=0;while(got<8){int n=in.read(e,got,8-got);if(n<0)throw new IOException("closed");got+=n;}
					int value=(short)((e[4]&0xff)|(e[5]&0xff)<<8);int type=e[6]&0x7f,number=e[7]&0xff;
					synchronized(state){
						if(type==1){if(number<GamepadHost.MAX_BUTTONS)state.buttons[number]=value!=0;}
						else if(type==2){
							if(number<6)state.axes[number]=Math.max(-1f,Math.min(1f,value/32767f));
							else if(number==6||number==7){if(number==6)hatX=Integer.signum(value);else hatY=Integer.signum(value);state.hat=hatOf(hatX,hatY);}
						}
					}
				}
			}catch(IOException ex){gone=true;try{in.close();}catch(IOException ignored){}}
		}
		static int hatOf(int x,int y){
			if(x==0&&y==0)return -1;
			if(y<0)return x<0?7:x>0?1:0;
			if(y>0)return x<0?5:x>0?3:4;
			return x<0?6:2;
		}
	}
}

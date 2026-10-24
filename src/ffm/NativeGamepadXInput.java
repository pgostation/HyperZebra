import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * Game controllers on Windows through XInput (Foreign Function API, Java 22+): XInputGetState for the four user slots.
 * Buttons 1-10 are A, B, X, Y, LB, RB, Back, Start, left stick, right stick; the d-pad is the hat; the sticks are
 * X/Y and Rx/Ry, the triggers lt/rt (rest at -1). A slot that returns ERROR_DEVICE_NOT_CONNECTED is retried every second.
 * Compiled separately by build.sh with --release 22 and loaded by name from GamepadHost.
 */
public final class NativeGamepadXInput implements GamepadHost.Source {
	private static final int ERROR_DEVICE_NOT_CONNECTED=1167;
	private static final long RETRY_MS=1000;
	private static final MethodHandle GET_STATE;
	private static final Arena ARENA=Arena.global();
	static {
		SymbolLookup xinput=null;Throwable last=null;
		for(String lib:new String[]{"xinput1_4.dll","xinput1_3.dll","xinput9_1_0.dll"}){
			try{xinput=SymbolLookup.libraryLookup(lib,ARENA);break;}catch(Throwable t){last=t;}
		}
		if(xinput==null)throw new IllegalStateException("XInput not found",last);
		// DWORD XInputGetState(DWORD dwUserIndex, XINPUT_STATE *pState)
		GET_STATE=Linker.nativeLinker().downcallHandle(xinput.find("XInputGetState").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.ADDRESS));
	}
	private final Slot[] slots={new Slot(0),new Slot(1),new Slot(2),new Slot(3)};

	public NativeGamepadXInput(){}

	@Override public List<GamepadHost.Device> devices(){
		List<GamepadHost.Device> r=new ArrayList<GamepadHost.Device>();
		long now=System.currentTimeMillis();
		for(Slot s:slots){
			if(!s.connected&&now-s.lastTry>=RETRY_MS){s.lastTry=now;s.connected=s.read();}
			if(s.connected)r.add(s);
		}
		return r;
	}

	static final class Slot implements GamepadHost.Device {
		final int index;boolean connected;long lastTry=-RETRY_MS;
		/** XINPUT_STATE: dwPacketNumber, then XINPUT_GAMEPAD {wButtons, bLeftTrigger, bRightTrigger, sThumbLX, sThumbLY, sThumbRX, sThumbRY}. */
		private final MemorySegment state=ARENA.allocate(16);
		Slot(int index){this.index=index;}
		@Override public String name(){return "XInput controller "+(index+1);}
		boolean read(){
			try{int result=(int)GET_STATE.invokeExact(index,state);return result==0;}
			catch(Throwable t){return false;}
		}
		@Override public boolean poll(GamepadHost.State into){
			if(!connected||!read()){connected=false;return false;}
			int buttons=state.get(ValueLayout.JAVA_SHORT,4)&0xffff;
			int lt=state.get(ValueLayout.JAVA_BYTE,6)&0xff,rt=state.get(ValueLayout.JAVA_BYTE,7)&0xff;
			short lx=state.get(ValueLayout.JAVA_SHORT,8),ly=state.get(ValueLayout.JAVA_SHORT,10),rx=state.get(ValueLayout.JAVA_SHORT,12),ry=state.get(ValueLayout.JAVA_SHORT,14);
			int[] order={0x1000,0x2000,0x4000,0x8000,0x100,0x200,0x20,0x10,0x40,0x80}; // A B X Y LB RB Back Start L3 R3
			for(int i=0;i<order.length;i++)into.buttons[i]=(buttons&order[i])!=0;
			boolean up=(buttons&1)!=0,down=(buttons&2)!=0,left=(buttons&4)!=0,right=(buttons&8)!=0;
			into.hat=up?(right?1:left?7:0):down?(right?3:left?5:4):right?2:left?6:-1;
			into.axes[0]=lx/32767f;into.axes[1]=-ly/32767f; // XInput's Y grows upward; the generic axis grows downward like HID
			into.axes[3]=rx/32767f;into.axes[4]=-ry/32767f;
			into.axes[6]=lt/255f*2f-1f;into.axes[7]=rt/255f*2f-1f;
			for(int a=0;a<into.axes.length;a++)into.axes[a]=Math.max(-1f,Math.min(1f,into.axes[a]));
			return true;
		}
	}
}

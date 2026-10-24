import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game controllers on macOS through IOKit's HID manager (Foreign Function API, Java 22+): the manager matches the
 * joystick, game pad and multi-axis controller usages of the generic desktop page only (opening keyboards or mice would
 * ask for the Input Monitoring permission), the devices it lists are polled element by element with IOHIDDeviceGetValue
 * on GamepadHost's thread, and the manager is scheduled on that thread's run loop, pumped once per scan, so devices
 * plugged in later appear. Elements: buttons by usage on the Button page, axes X, Y, Z, Rx, Ry, Rz and the hat switch of
 * the generic desktop page, Accelerator / Brake of the simulation page as the triggers. Compiled separately by build.sh
 * with --release 22 and loaded by name from GamepadHost.
 */
public final class NativeGamepadHid implements GamepadHost.Source {
	private static final int UTF8=0x08000100, kCFNumberIntType=9;
	private static final int kIOHIDElementTypeInput_Misc=1, kIOHIDElementTypeInput_Button=2, kIOHIDElementTypeInput_Axis=3;
	private static final long RESCAN_MS=1000;
	private static final Linker LINKER=Linker.nativeLinker();
	private static final Arena ARENA=Arena.global();
	private static final MethodHandle MANAGER_CREATE,SET_MATCHING_MULTIPLE,MANAGER_OPEN,COPY_DEVICES,SCHEDULE,DEVICE_GET_PROPERTY,COPY_ELEMENTS,
		ELEMENT_TYPE,ELEMENT_PAGE,ELEMENT_USAGE,ELEMENT_MIN,ELEMENT_MAX,DEVICE_GET_VALUE,VALUE_INTEGER,
		STRING_CREATE,NUMBER_CREATE,DICTIONARY_CREATE,ARRAY_CREATE,SET_COUNT,SET_VALUES,ARRAY_COUNT,ARRAY_AT,STRING_GET_CSTRING,RETAIN,RELEASE,
		RUNLOOP_CURRENT,RUNLOOP_RUN;
	private static final MemorySegment DICT_KEY_CALLBACKS,DICT_VALUE_CALLBACKS,ARRAY_CALLBACKS,DEFAULT_MODE,PRODUCT_KEY;
	static {
		SymbolLookup io=SymbolLookup.libraryLookup("/System/Library/Frameworks/IOKit.framework/IOKit",ARENA);
		SymbolLookup cf=SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",ARENA);
		ValueLayout.OfInt I=ValueLayout.JAVA_INT;ValueLayout.OfLong L=ValueLayout.JAVA_LONG;java.lang.foreign.AddressLayout A=ValueLayout.ADDRESS;
		MANAGER_CREATE=LINKER.downcallHandle(io.find("IOHIDManagerCreate").orElseThrow(),FunctionDescriptor.of(A,A,I));
		SET_MATCHING_MULTIPLE=LINKER.downcallHandle(io.find("IOHIDManagerSetDeviceMatchingMultiple").orElseThrow(),FunctionDescriptor.ofVoid(A,A));
		MANAGER_OPEN=LINKER.downcallHandle(io.find("IOHIDManagerOpen").orElseThrow(),FunctionDescriptor.of(I,A,I));
		COPY_DEVICES=LINKER.downcallHandle(io.find("IOHIDManagerCopyDevices").orElseThrow(),FunctionDescriptor.of(A,A));
		SCHEDULE=LINKER.downcallHandle(io.find("IOHIDManagerScheduleWithRunLoop").orElseThrow(),FunctionDescriptor.ofVoid(A,A,A));
		DEVICE_GET_PROPERTY=LINKER.downcallHandle(io.find("IOHIDDeviceGetProperty").orElseThrow(),FunctionDescriptor.of(A,A,A));
		COPY_ELEMENTS=LINKER.downcallHandle(io.find("IOHIDDeviceCopyMatchingElements").orElseThrow(),FunctionDescriptor.of(A,A,A,I));
		ELEMENT_TYPE=LINKER.downcallHandle(io.find("IOHIDElementGetType").orElseThrow(),FunctionDescriptor.of(I,A));
		ELEMENT_PAGE=LINKER.downcallHandle(io.find("IOHIDElementGetUsagePage").orElseThrow(),FunctionDescriptor.of(I,A));
		ELEMENT_USAGE=LINKER.downcallHandle(io.find("IOHIDElementGetUsage").orElseThrow(),FunctionDescriptor.of(I,A));
		ELEMENT_MIN=LINKER.downcallHandle(io.find("IOHIDElementGetLogicalMin").orElseThrow(),FunctionDescriptor.of(L,A));
		ELEMENT_MAX=LINKER.downcallHandle(io.find("IOHIDElementGetLogicalMax").orElseThrow(),FunctionDescriptor.of(L,A));
		DEVICE_GET_VALUE=LINKER.downcallHandle(io.find("IOHIDDeviceGetValue").orElseThrow(),FunctionDescriptor.of(I,A,A,A));
		VALUE_INTEGER=LINKER.downcallHandle(io.find("IOHIDValueGetIntegerValue").orElseThrow(),FunctionDescriptor.of(L,A));
		STRING_CREATE=LINKER.downcallHandle(cf.find("CFStringCreateWithCString").orElseThrow(),FunctionDescriptor.of(A,A,A,I));
		NUMBER_CREATE=LINKER.downcallHandle(cf.find("CFNumberCreate").orElseThrow(),FunctionDescriptor.of(A,A,L,A));
		DICTIONARY_CREATE=LINKER.downcallHandle(cf.find("CFDictionaryCreate").orElseThrow(),FunctionDescriptor.of(A,A,A,A,L,A,A));
		ARRAY_CREATE=LINKER.downcallHandle(cf.find("CFArrayCreate").orElseThrow(),FunctionDescriptor.of(A,A,A,L,A));
		SET_COUNT=LINKER.downcallHandle(cf.find("CFSetGetCount").orElseThrow(),FunctionDescriptor.of(L,A));
		SET_VALUES=LINKER.downcallHandle(cf.find("CFSetGetValues").orElseThrow(),FunctionDescriptor.ofVoid(A,A));
		ARRAY_COUNT=LINKER.downcallHandle(cf.find("CFArrayGetCount").orElseThrow(),FunctionDescriptor.of(L,A));
		ARRAY_AT=LINKER.downcallHandle(cf.find("CFArrayGetValueAtIndex").orElseThrow(),FunctionDescriptor.of(A,A,L));
		STRING_GET_CSTRING=LINKER.downcallHandle(cf.find("CFStringGetCString").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_BYTE,A,A,L,I));
		RETAIN=LINKER.downcallHandle(cf.find("CFRetain").orElseThrow(),FunctionDescriptor.of(A,A));
		RELEASE=LINKER.downcallHandle(cf.find("CFRelease").orElseThrow(),FunctionDescriptor.ofVoid(A));
		RUNLOOP_CURRENT=LINKER.downcallHandle(cf.find("CFRunLoopGetCurrent").orElseThrow(),FunctionDescriptor.of(A));
		RUNLOOP_RUN=LINKER.downcallHandle(cf.find("CFRunLoopRunInMode").orElseThrow(),FunctionDescriptor.of(I,A,ValueLayout.JAVA_DOUBLE,ValueLayout.JAVA_BYTE));
		DICT_KEY_CALLBACKS=cf.find("kCFTypeDictionaryKeyCallBacks").orElseThrow();
		DICT_VALUE_CALLBACKS=cf.find("kCFTypeDictionaryValueCallBacks").orElseThrow();
		ARRAY_CALLBACKS=cf.find("kCFTypeArrayCallBacks").orElseThrow();
		DEFAULT_MODE=cf.find("kCFRunLoopDefaultMode").orElseThrow().reinterpret(A.byteSize()).get(A,0);
		PRODUCT_KEY=cfString("Product");
	}

	private MemorySegment manager;
	private final Map<Long,HidDevice> devices=new LinkedHashMap<Long,HidDevice>();
	private long lastScan=-RESCAN_MS;
	private boolean failed;

	public NativeGamepadHid(){}

	private static MemorySegment cfString(String s){
		try{return (MemorySegment)STRING_CREATE.invokeExact(MemorySegment.NULL,ARENA.allocateFrom(s),UTF8);}catch(Throwable t){throw new RuntimeException(t);}
	}
	private static MemorySegment cfNumber(int v){
		try{MemorySegment p=ARENA.allocate(ValueLayout.JAVA_INT);p.set(ValueLayout.JAVA_INT,0,v);return (MemorySegment)NUMBER_CREATE.invokeExact(MemorySegment.NULL,(long)kCFNumberIntType,p);}catch(Throwable t){throw new RuntimeException(t);}
	}
	/** {DeviceUsagePage: page, DeviceUsage: usage} */
	private static MemorySegment matching(int page,int usage) throws Throwable {
		MemorySegment keys=ARENA.allocate(ValueLayout.ADDRESS,2),values=ARENA.allocate(ValueLayout.ADDRESS,2);
		keys.setAtIndex(ValueLayout.ADDRESS,0,cfString("DeviceUsagePage"));keys.setAtIndex(ValueLayout.ADDRESS,1,cfString("DeviceUsage"));
		values.setAtIndex(ValueLayout.ADDRESS,0,cfNumber(page));values.setAtIndex(ValueLayout.ADDRESS,1,cfNumber(usage));
		return (MemorySegment)DICTIONARY_CREATE.invokeExact(MemorySegment.NULL,keys,values,2L,DICT_KEY_CALLBACKS,DICT_VALUE_CALLBACKS);
	}

	/** Creates and opens the manager on the calling (polling) thread. */
	private void open() throws Throwable {
		manager=(MemorySegment)MANAGER_CREATE.invokeExact(MemorySegment.NULL,0);
		if(manager.address()==0)throw new IllegalStateException("IOHIDManagerCreate failed");
		MemorySegment dicts=ARENA.allocate(ValueLayout.ADDRESS,3);
		dicts.setAtIndex(ValueLayout.ADDRESS,0,matching(0x01,0x04)); // joystick
		dicts.setAtIndex(ValueLayout.ADDRESS,1,matching(0x01,0x05)); // game pad
		dicts.setAtIndex(ValueLayout.ADDRESS,2,matching(0x01,0x08)); // multi-axis controller
		MemorySegment array=(MemorySegment)ARRAY_CREATE.invokeExact(MemorySegment.NULL,dicts,3L,ARRAY_CALLBACKS);
		SET_MATCHING_MULTIPLE.invokeExact(manager,array);
		MemorySegment runLoop=(MemorySegment)RUNLOOP_CURRENT.invokeExact();
		SCHEDULE.invokeExact(manager,runLoop,DEFAULT_MODE);
		int result=(int)MANAGER_OPEN.invokeExact(manager,0);
		if(result!=0&&GamepadHost.TRACE)System.err.println("[gamepad] IOHIDManagerOpen: 0x"+Integer.toHexString(result));
	}

	@Override public List<GamepadHost.Device> devices(){
		if(failed)return new ArrayList<GamepadHost.Device>();
		try{
			if(manager==null)open();
			// deliver the manager's notifications (hot-plugging) without blocking
			int ignored=(int)RUNLOOP_RUN.invokeExact(DEFAULT_MODE,0.0,(byte)1);
			long now=System.currentTimeMillis();
			if(now-lastScan>=RESCAN_MS){lastScan=now;scan();}
		}catch(Throwable t){
			failed=true;System.err.println("[gamepad] IOKit HID not available: "+t);
		}
		return new ArrayList<GamepadHost.Device>(devices.values());
	}

	private void scan() throws Throwable {
		MemorySegment set=(MemorySegment)COPY_DEVICES.invokeExact(manager);
		Map<Long,HidDevice> seen=new LinkedHashMap<Long,HidDevice>();
		if(set.address()!=0){
			long n=(long)SET_COUNT.invokeExact(set);
			if(n>0){
				MemorySegment refs=ARENA.allocate(ValueLayout.ADDRESS,n);
				SET_VALUES.invokeExact(set,refs);
				for(long i=0;i<n;i++){
					MemorySegment dev=refs.getAtIndex(ValueLayout.ADDRESS,i);
					HidDevice d=devices.get(dev.address());
					if(d==null){d=new HidDevice(dev);if(GamepadHost.TRACE)System.err.println("[gamepad] "+d.name()+": "+d.buttons.size()+" buttons, "+d.axisCount()+" axes"+(d.hat!=null?", hat":""));}
					seen.put(dev.address(),d);
				}
			}
			RELEASE.invokeExact(set);
		}
		for(Map.Entry<Long,HidDevice> e:devices.entrySet())if(!seen.containsKey(e.getKey()))e.getValue().close();
		devices.clear();devices.putAll(seen);
	}

	/** One controller: its element references, retained, and their ranges. */
	static final class HidDevice implements GamepadHost.Device {
		private final MemorySegment device;private final String name;
		/** Button elements by index (usage 1 → 0). */
		final List<MemorySegment> buttons=new ArrayList<MemorySegment>();
		/** Axis elements per GamepadHost.AXIS_NAMES entry, with logical min/max; null where the pad has none. */
		final MemorySegment[] axes=new MemorySegment[GamepadHost.AXES];final long[] axisMin=new long[GamepadHost.AXES],axisMax=new long[GamepadHost.AXES];
		MemorySegment hat;long hatMin,hatMax;
		private final MemorySegment valueOut=ARENA.allocate(ValueLayout.ADDRESS);
		private boolean gone;
		HidDevice(MemorySegment dev) throws Throwable {
			device=(MemorySegment)RETAIN.invokeExact(dev);
			name=productName(dev);
			MemorySegment elements=(MemorySegment)COPY_ELEMENTS.invokeExact(dev,MemorySegment.NULL,0);
			if(elements.address()!=0){
				long n=(long)ARRAY_COUNT.invokeExact(elements);
				for(long i=0;i<n;i++){
					MemorySegment el=(MemorySegment)ARRAY_AT.invokeExact(elements,i);
					int type=(int)ELEMENT_TYPE.invokeExact(el);
					if(type!=kIOHIDElementTypeInput_Misc&&type!=kIOHIDElementTypeInput_Button&&type!=kIOHIDElementTypeInput_Axis)continue;
					int page=(int)ELEMENT_PAGE.invokeExact(el),usage=(int)ELEMENT_USAGE.invokeExact(el);
					long min=(long)ELEMENT_MIN.invokeExact(el),max=(long)ELEMENT_MAX.invokeExact(el);
					int axis=-1;
					if(page==0x09){ // buttons, by usage; the first element of a usage wins (pads repeat some in other collections)
						if(usage>=1&&usage<=GamepadHost.MAX_BUTTONS){while(buttons.size()<usage)buttons.add(null);if(buttons.get(usage-1)==null)buttons.set(usage-1,(MemorySegment)RETAIN.invokeExact(el));}
						continue;
					}
					if(page==0x01){
						switch(usage){case 0x30:axis=0;break;case 0x31:axis=1;break;case 0x32:axis=2;break;case 0x33:axis=3;break;case 0x34:axis=4;break;case 0x35:axis=5;break;
							case 0x39:if(hat==null){hat=(MemorySegment)RETAIN.invokeExact(el);hatMin=min;hatMax=max;}continue;
							default:continue;}
					}else if(page==0x02){ // simulation controls: accelerator / brake are the triggers of Xbox-style pads
						if(usage==0xC4)axis=6;else if(usage==0xC5)axis=7;else continue;
					}else continue;
					if(axis>=0&&axes[axis]==null&&max>min){axes[axis]=(MemorySegment)RETAIN.invokeExact(el);axisMin[axis]=min;axisMax[axis]=max;}
				}
				RELEASE.invokeExact(elements);
			}
		}
		int axisCount(){int n=0;for(MemorySegment a:axes)if(a!=null)n++;return n;}
		private static String productName(MemorySegment dev) throws Throwable {
			MemorySegment s=(MemorySegment)DEVICE_GET_PROPERTY.invokeExact(dev,PRODUCT_KEY);
			if(s.address()==0)return "Game controller";
			MemorySegment buf=ARENA.allocate(256);
			byte ok=(byte)STRING_GET_CSTRING.invokeExact(s,buf,256L,UTF8);
			if(ok==0)return "Game controller";
			String n=buf.getString(0).trim();return n.isEmpty()?"Game controller":n;
		}
		private long value(MemorySegment element) throws Throwable {
			int result=(int)DEVICE_GET_VALUE.invokeExact(device,element,valueOut);
			if(result!=0)throw new IllegalStateException("IOHIDDeviceGetValue 0x"+Integer.toHexString(result));
			MemorySegment v=valueOut.get(ValueLayout.ADDRESS,0);
			return v.address()==0?0:(long)VALUE_INTEGER.invokeExact(v);
		}
		@Override public String name(){return name;}
		@Override public boolean poll(GamepadHost.State into){
			if(gone)return false;
			try{
				for(int i=0;i<buttons.size()&&i<GamepadHost.MAX_BUTTONS;i++){MemorySegment b=buttons.get(i);if(b!=null)into.buttons[i]=value(b)!=0;}
				for(int a=0;a<axes.length;a++){
					if(axes[a]==null)continue;
					long v=value(axes[a]);
					into.axes[a]=Math.max(-1f,Math.min(1f,(float)((v-axisMin[a])*2.0/(axisMax[a]-axisMin[a])-1.0)));
				}
				if(hat!=null){
					long v=value(hat);long range=hatMax-hatMin+1;
					if(v<hatMin||v>hatMax)into.hat=-1;
					else if(range==8)into.hat=(int)(v-hatMin);
					else if(range==4)into.hat=(int)(v-hatMin)*2;
					else into.hat=-1;
				}
				return true;
			}catch(Throwable t){
				// the pad was unplugged (the manager's next scan drops it) or it cannot be read: not a device any more
				if(GamepadHost.TRACE)System.err.println("[gamepad] "+name+": "+t);
				gone=true;return false;
			}
		}
		void close(){
			try{
				for(MemorySegment b:buttons)if(b!=null)RELEASE.invokeExact(b);
				for(MemorySegment a:axes)if(a!=null)RELEASE.invokeExact(a);
				if(hat!=null)RELEASE.invokeExact(hat);
				RELEASE.invokeExact(device);
			}catch(Throwable ignored){}
		}
	}
}

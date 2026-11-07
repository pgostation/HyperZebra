import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

/**
 * Windows pen tablets through Windows Ink (Windows 8+): a WH_GETMESSAGE hook on AWT's toolkit thread (the thread of the
 * SunAwt* windows of this process) sees each WM_POINTER* message as AWT takes it from the queue, before DefWindowProc
 * makes the mouse message AWT turns into a MouseEvent. For a pen pointer GetPointerPenInfo gives the pressure (0-1024),
 * tilt (degrees, -90..90: X positive to the right, Y toward the user), the eraser/inverted flags, contact and the location
 * in physical screen pixels, which go to {@link TabletInput#record}.
 * Tablets whose driver has Windows Ink switched off (WinTab only) give no pressure. Compiled separately by build.sh with
 * --release 22 and loaded by reflection from TabletInput.
 */
public final class NativeTabletWindows {
	private static final int WH_GETMESSAGE=3,PM_REMOVE=1;
	private static final int WM_POINTERUPDATE=0x245,WM_POINTERDOWN=0x246,WM_POINTERUP=0x247,WM_POINTERENTER=0x249,WM_POINTERLEAVE=0x24A;
	private static final int PT_PEN=3,POINTER_FLAG_INCONTACT=0x4,PEN_FLAG_INVERTED=0x2,PEN_FLAG_ERASER=0x4,PEN_MASK_PRESSURE=0x1,PEN_MASK_TILT_X=0x4,PEN_MASK_TILT_Y=0x8;
	private static final Arena ARENA=Arena.global();
	private static final AddressLayout A=ValueLayout.ADDRESS;
	private static MethodHandle CALL_NEXT,GET_TYPE,GET_PEN_INFO,THREAD_PROCESS,CLASS_NAME;
	/** POINTER_INPUT_TYPE and POINTER_PEN_INFO (120 bytes): read only on the toolkit thread */
	private static final MemorySegment TYPE=ARENA.allocate(4),PEN=ARENA.allocate(128,8);
	private static int toolkitThread,processId;
	private static boolean started;

	private NativeTabletWindows(){}

	/** Hooks AWT's toolkit thread. True when the hook is in place. */
	public static synchronized boolean start() throws Throwable {
		if(started)return true;
		Linker linker=Linker.nativeLinker();
		SymbolLookup user32=SymbolLookup.libraryLookup("user32.dll",ARENA),kernel32=SymbolLookup.libraryLookup("kernel32.dll",ARENA);
		// BOOL GetPointerType(UINT32 pointerId, POINTER_INPUT_TYPE *pointerType), BOOL GetPointerPenInfo(UINT32, POINTER_PEN_INFO *)
		GET_TYPE=linker.downcallHandle(user32.find("GetPointerType").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,A));
		GET_PEN_INFO=linker.downcallHandle(user32.find("GetPointerPenInfo").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,A));
		// LRESULT CallNextHookEx(HHOOK, int nCode, WPARAM, LPARAM)
		CALL_NEXT=linker.downcallHandle(user32.find("CallNextHookEx").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_LONG,A,ValueLayout.JAVA_INT,ValueLayout.JAVA_LONG,A));
		// DWORD GetWindowThreadProcessId(HWND, LPDWORD), int GetClassNameW(HWND, LPWSTR, int)
		THREAD_PROCESS=linker.downcallHandle(user32.find("GetWindowThreadProcessId").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,A,A));
		CLASS_NAME=linker.downcallHandle(user32.find("GetClassNameW").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,A,A,ValueLayout.JAVA_INT));
		MethodHandle enumWindows=linker.downcallHandle(user32.find("EnumWindows").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,A,ValueLayout.JAVA_LONG));
		MethodHandle setHook=linker.downcallHandle(user32.find("SetWindowsHookExW").orElseThrow(),FunctionDescriptor.of(A,ValueLayout.JAVA_INT,A,A,ValueLayout.JAVA_INT));
		MethodHandle currentProcess=linker.downcallHandle(kernel32.find("GetCurrentProcessId").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT));
		processId=(int)currentProcess.invokeExact();

		// AWT's toolkit thread: the thread of this process's SunAwtToolkit/SunAwtFrame/... windows
		MethodHandles.Lookup lookup=MethodHandles.lookup();
		try(Arena arena=Arena.ofConfined()){
			MemorySegment callback=linker.upcallStub(lookup.findStatic(NativeTabletWindows.class,"window",MethodType.methodType(int.class,MemorySegment.class,long.class)),
				FunctionDescriptor.of(ValueLayout.JAVA_INT,A,ValueLayout.JAVA_LONG),arena);
			int ignored=(int)enumWindows.invokeExact(callback,0L);
		}
		if(toolkitThread==0)return false;
		MemorySegment proc=linker.upcallStub(lookup.findStatic(NativeTabletWindows.class,"hook",MethodType.methodType(long.class,int.class,long.class,MemorySegment.class)),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG,ValueLayout.JAVA_INT,ValueLayout.JAVA_LONG,A),ARENA);
		// a thread hook of this process: no module handle
		MemorySegment hook=(MemorySegment)setHook.invokeExact(WH_GETMESSAGE,proc,MemorySegment.NULL,toolkitThread);
		if(hook.address()==0)return false;
		TabletInput.physicalPixels=true;
		started=true;
		return true;
	}

	/** EnumWindows callback: stops at the first AWT window of this process. */
	private static int window(MemorySegment hwnd,long param){
		try(Arena arena=Arena.ofConfined()){
			MemorySegment pid=arena.allocate(4);
			int thread=(int)THREAD_PROCESS.invokeExact(hwnd,pid);
			if(pid.get(ValueLayout.JAVA_INT,0)!=processId)return 1;
			MemorySegment name=arena.allocate(512);
			int n=(int)CLASS_NAME.invokeExact(hwnd,name,256);
			if(n>0&&name.getString(0,StandardCharsets.UTF_16LE).startsWith("SunAwt")){toolkitThread=thread;return 0;}
		}catch(Throwable ignored){}
		return 1;
	}

	/** GetMsgProc(int code, WPARAM removal, LPARAM MSG*): on the toolkit thread. Must never throw. */
	private static long hook(int code,long removal,MemorySegment msg){
		if(code>=0&&removal==PM_REMOVE)try{
			MemorySegment m=msg.reinterpret(48); // MSG: hwnd, message (8), wParam (16), lParam (24), time, pt
			int message=m.get(ValueLayout.JAVA_INT,8);
			if(message==WM_POINTERUPDATE||message==WM_POINTERDOWN||message==WM_POINTERUP||message==WM_POINTERENTER||message==WM_POINTERLEAVE){
				int id=(int)(m.get(ValueLayout.JAVA_LONG,16)&0xffff);
				if((int)GET_TYPE.invokeExact(id,TYPE)!=0&&TYPE.get(ValueLayout.JAVA_INT,0)==PT_PEN&&(int)GET_PEN_INFO.invokeExact(id,PEN)!=0){
					// POINTER_PEN_INFO: POINTER_INFO {type, id, frameId, pointerFlags (12), sourceDevice, hwndTarget, ptPixelLocation (32), ...} (96),
					// then penFlags (96), penMask (100), pressure (104), rotation, tiltX, tiltY
					int flags=PEN.get(ValueLayout.JAVA_INT,12),penFlags=PEN.get(ValueLayout.JAVA_INT,96),penMask=PEN.get(ValueLayout.JAVA_INT,100);
					if((penMask&PEN_MASK_PRESSURE)!=0){
						boolean contact=(flags&POINTER_FLAG_INCONTACT)!=0&&message!=WM_POINTERUP&&message!=WM_POINTERLEAVE;
						boolean tilt=(penMask&(PEN_MASK_TILT_X|PEN_MASK_TILT_Y))==(PEN_MASK_TILT_X|PEN_MASK_TILT_Y);
						TabletInput.record(PEN.get(ValueLayout.JAVA_INT,32),PEN.get(ValueLayout.JAVA_INT,36),PEN.get(ValueLayout.JAVA_INT,104)/1024f,
							(penFlags&(PEN_FLAG_ERASER|PEN_FLAG_INVERTED))!=0,contact,
							tilt?PEN.get(ValueLayout.JAVA_INT,112):Float.NaN,tilt?PEN.get(ValueLayout.JAVA_INT,116):Float.NaN);
					}
				}
			}
		}catch(Throwable ignored){}
		try{return (long)CALL_NEXT.invokeExact(MemorySegment.NULL,code,removal,msg);}catch(Throwable t){return 0;}
	}
}

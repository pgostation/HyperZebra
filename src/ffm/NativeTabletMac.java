import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * macOS pen tablets: an NSEvent local monitor (+[NSEvent addLocalMonitorForEventsMatchingMask:handler:]) sees each mouse
 * event before AWT turns it into a MouseEvent, and passes the tablet ones (subtype NSEventSubtypeTabletPoint, and
 * NSEventTypeTabletPoint) to {@link TabletInput#record} with their pressure and CGEvent location (global points, the same as
 * Java's screen coordinates) and tilt ([NSEvent tilt], -1..1 each way, made degrees as Qt does: x60, Y turned so that
 * positive is toward the user). Proximity events tell which end of the pen is used (NSPointingDeviceTypeEraser). The handler
 * is a global Objective-C block literal whose invoke function is an upcall stub. Compiled separately by build.sh with
 * --release 22 and loaded by reflection from TabletInput.
 */
public final class NativeTabletMac {
	private static final long LEFT_DOWN=1,LEFT_UP=2,MOVED=5,LEFT_DRAGGED=6,TABLET_POINT=23,TABLET_PROXIMITY=24; // NSEventType
	private static final short SUBTYPE_TABLET_POINT=1,SUBTYPE_TABLET_PROXIMITY=2; // NSEventSubtype
	private static final long DEVICE_ERASER=3; // NSPointingDeviceTypeEraser
	private static final int BLOCK_IS_GLOBAL=1<<28;
	private static final Arena ARENA=Arena.global();
	private static final AddressLayout A=ValueLayout.ADDRESS;
	private static MethodHandle GET_CLASS,REGISTER_SEL,MSG_LONG,MSG_SHORT,MSG_FLOAT,MSG_BOOL,MSG_ID,MSG_POINT,MSG_ADD_MONITOR,CG_LOCATION,DISPATCH_ASYNC;
	private static MemorySegment selType,selSubtype,selPressure,selTilt,selCGEvent,selEntering,selDeviceType,block,mainQueue,addWork;
	/** Tilt degrees of [NSEvent tilt] = 1 (the full tilt the tablet reports) */
	private static final float TILT_DEGREES=60f;
	/** CGEventGetLocation returns a CGPoint by value: always into this one segment (only the AppKit thread reads it) */
	private static final SegmentAllocator POINT=SegmentAllocator.prefixAllocator(ARENA.allocate(16,8)),TILT=SegmentAllocator.prefixAllocator(ARENA.allocate(16,8));
	private static boolean started;

	private NativeTabletMac(){}

	/** Installs the monitor (on the main thread, through the main dispatch queue). True when it could be set up. */
	public static synchronized boolean start() throws Throwable {
		if(started)return true;
		Linker linker=Linker.nativeLinker();
		SymbolLookup objc=SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib",ARENA);
		SymbolLookup system=SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib",ARENA);
		SymbolLookup cg=SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",ARENA);
		SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit",ARENA); // NSEvent (AWT has loaded it already)
		GET_CLASS=linker.downcallHandle(objc.find("objc_getClass").orElseThrow(),FunctionDescriptor.of(A,A));
		REGISTER_SEL=linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),FunctionDescriptor.of(A,A));
		MemorySegment send=objc.find("objc_msgSend").orElseThrow();
		MSG_LONG=linker.downcallHandle(send,FunctionDescriptor.of(ValueLayout.JAVA_LONG,A,A));
		MSG_SHORT=linker.downcallHandle(send,FunctionDescriptor.of(ValueLayout.JAVA_SHORT,A,A));
		MSG_FLOAT=linker.downcallHandle(send,FunctionDescriptor.of(ValueLayout.JAVA_FLOAT,A,A));
		MSG_BOOL=linker.downcallHandle(send,FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,A,A));
		MSG_ID=linker.downcallHandle(send,FunctionDescriptor.of(A,A,A));
		// NSPoint -[NSEvent tilt]: two doubles, returned in registers on arm64 and x86_64 (objc_msgSend, not _stret)
		MSG_POINT=linker.downcallHandle(send,FunctionDescriptor.of(MemoryLayout.structLayout(ValueLayout.JAVA_DOUBLE,ValueLayout.JAVA_DOUBLE),A,A));
		MSG_ADD_MONITOR=linker.downcallHandle(send,FunctionDescriptor.of(A,A,A,ValueLayout.JAVA_LONG,A));
		// CGPoint CGEventGetLocation(CGEventRef event)
		CG_LOCATION=linker.downcallHandle(cg.find("CGEventGetLocation").orElseThrow(),
			FunctionDescriptor.of(MemoryLayout.structLayout(ValueLayout.JAVA_DOUBLE,ValueLayout.JAVA_DOUBLE),A));
		// void dispatch_async_f(dispatch_queue_t queue, void *context, dispatch_function_t work)
		DISPATCH_ASYNC=linker.downcallHandle(system.find("dispatch_async_f").orElseThrow(),FunctionDescriptor.ofVoid(A,A,A));
		mainQueue=system.find("_dispatch_main_q").orElseThrow(); // dispatch_get_main_queue()
		selType=sel("type");selSubtype=sel("subtype");selPressure=sel("pressure");selTilt=sel("tilt");selCGEvent=sel("CGEvent");
		selEntering=sel("isEnteringProximity");selDeviceType=sel("pointingDeviceType");

		// the handler block: struct Block_literal {isa, flags, reserved, invoke, descriptor}, descriptor {reserved, size}
		MethodHandles.Lookup lookup=MethodHandles.lookup();
		MemorySegment invoke=linker.upcallStub(lookup.findStatic(NativeTabletMac.class,"handle",MethodType.methodType(MemorySegment.class,MemorySegment.class,MemorySegment.class)),
			FunctionDescriptor.of(A,A,A),ARENA);
		MemorySegment descriptor=ARENA.allocate(16,8);
		descriptor.set(ValueLayout.JAVA_LONG,0,0);descriptor.set(ValueLayout.JAVA_LONG,8,32);
		block=ARENA.allocate(32,8);
		block.set(A,0,system.find("_NSConcreteGlobalBlock").orElseThrow());
		block.set(ValueLayout.JAVA_INT,8,BLOCK_IS_GLOBAL);
		block.set(ValueLayout.JAVA_INT,12,0);
		block.set(A,16,invoke);
		block.set(A,24,descriptor);
		addWork=linker.upcallStub(lookup.findStatic(NativeTabletMac.class,"addMonitor",MethodType.methodType(void.class,MemorySegment.class)),
			FunctionDescriptor.ofVoid(A),ARENA);
		DISPATCH_ASYNC.invokeExact(mainQueue,MemorySegment.NULL,addWork);
		started=true;
		return true;
	}

	/** On the main thread: +[NSEvent addLocalMonitorForEventsMatchingMask:handler:] (the monitor stays for the process). */
	private static void addMonitor(MemorySegment context){
		try{
			long mask=(1L<<LEFT_DOWN)|(1L<<LEFT_UP)|(1L<<MOVED)|(1L<<LEFT_DRAGGED)|(1L<<TABLET_POINT)|(1L<<TABLET_PROXIMITY);
			MemorySegment monitor=(MemorySegment)MSG_ADD_MONITOR.invokeExact(cls("NSEvent"),sel("addLocalMonitorForEventsMatchingMask:handler:"),mask,block);
			if(monitor.address()==0)TabletInput.available=false;
		}catch(Throwable t){TabletInput.available=false;}
	}

	/** The block's invoke function: NSEvent *(^)(NSEvent *event). Returns the event unchanged. Must never throw. */
	private static MemorySegment handle(MemorySegment self,MemorySegment event){
		try{
			long type=(long)MSG_LONG.invokeExact(event,selType);
			if(type==TABLET_PROXIMITY){proximity(event);return event;}
			boolean contact;
			if(type==TABLET_POINT)contact=true; // pressure 0 = lifted
			else{
				// subtype only for mouse events (other kinds raise an Objective-C exception)
				short subtype=(short)MSG_SHORT.invokeExact(event,selSubtype);
				if(subtype==SUBTYPE_TABLET_PROXIMITY){proximity(event);return event;}
				if(subtype!=SUBTYPE_TABLET_POINT)return event; // a mouse or trackpad
				contact=type==LEFT_DOWN||type==LEFT_DRAGGED;
			}
			float pressure=(float)MSG_FLOAT.invokeExact(event,selPressure);
			MemorySegment cgEvent=(MemorySegment)MSG_ID.invokeExact(event,selCGEvent);
			if(cgEvent.address()==0)return event;
			MemorySegment p=(MemorySegment)CG_LOCATION.invokeExact(POINT,cgEvent);
			if(type==TABLET_POINT)contact=pressure>0;
			MemorySegment t=(MemorySegment)MSG_POINT.invokeExact(TILT,event,selTilt);
			float tiltX=(float)(t.get(ValueLayout.JAVA_DOUBLE,0)*TILT_DEGREES),tiltY=(float)(-t.get(ValueLayout.JAVA_DOUBLE,8)*TILT_DEGREES);
			TabletInput.record(p.get(ValueLayout.JAVA_DOUBLE,0),p.get(ValueLayout.JAVA_DOUBLE,8),pressure,false,contact,tiltX,tiltY);
		}catch(Throwable ignored){}
		return event;
	}

	private static void proximity(MemorySegment event) throws Throwable {
		boolean entering=(boolean)MSG_BOOL.invokeExact(event,selEntering);
		long device=(long)MSG_LONG.invokeExact(event,selDeviceType);
		TabletInput.proximity(entering,device==DEVICE_ERASER);
	}

	private static MemorySegment cls(String name) throws Throwable {
		try(Arena arena=Arena.ofConfined()){return (MemorySegment)GET_CLASS.invokeExact(arena.allocateFrom(name));}
	}
	private static MemorySegment sel(String name) throws Throwable {
		try(Arena arena=Arena.ofConfined()){return (MemorySegment)REGISTER_SEL.invokeExact(arena.allocateFrom(name));}
	}
}

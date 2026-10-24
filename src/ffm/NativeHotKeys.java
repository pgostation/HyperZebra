import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * macOS global hot keys (Mission Control's Control-arrow Space switching, Control-number desktops, ...) switched off for
 * this process's WindowServer connection through CoreGraphics' CGSSetGlobalHotKeyOperatingMode, the call SDL's keyboard
 * grab uses. The mode belongs to the connection, so it ends with the process even when it is killed. Compiled separately
 * by build.sh with --release 22 and loaded by reflection from MacAppHost.
 */
public final class NativeHotKeys {
	private static final int ENABLE=0,DISABLE=1; // CGSGlobalHotKeyOperatingMode
	private static final MethodHandle MAIN_CONNECTION,SET_MODE;
	static {
		Linker linker=Linker.nativeLinker();
		SymbolLookup coreGraphics=SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",Arena.global());
		// CGSConnectionID CGSMainConnectionID(void)
		MAIN_CONNECTION=linker.downcallHandle(coreGraphics.find("CGSMainConnectionID").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT));
		// CGError CGSSetGlobalHotKeyOperatingMode(CGSConnectionID connection, CGSGlobalHotKeyOperatingMode mode)
		SET_MODE=linker.downcallHandle(coreGraphics.find("CGSSetGlobalHotKeyOperatingMode").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));
	}
	private NativeHotKeys(){}

	/** Suspends (true) or restores (false) the system's global hot keys; false on error. */
	public static boolean suspend(boolean suspend){
		try{int connection=(int)MAIN_CONNECTION.invokeExact();return (int)SET_MODE.invokeExact(connection,suspend?DISABLE:ENABLE)==0;}
		catch(Throwable t){return false;}
	}
}

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Changes the process's working directory (Foreign Function API, Java 22+), which Java itself cannot do. Compiled separately
 * by build.sh with --release 22 and loaded by reflection from HyperZebra, which restarts the program in that directory
 * when this class is missing: a whole second start of the JVM.
 */
public final class NativeChdir {
	private NativeChdir(){}

	/** True when the working directory is now {@code path}. */
	public static boolean chdir(String path) throws Throwable {
		Linker linker=Linker.nativeLinker();
		try(Arena arena=Arena.ofConfined()){
			if(System.getProperty("os.name","").startsWith("Windows")){
				// BOOL SetCurrentDirectoryW(LPCWSTR path)
				MethodHandle set=linker.downcallHandle(linker.defaultLookup().find("SetCurrentDirectoryW").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS));
				return (int)set.invokeExact(arena.allocateFrom(path,StandardCharsets.UTF_16LE))!=0;
			}
			// int chdir(const char *path)
			MethodHandle chdir=linker.downcallHandle(linker.defaultLookup().find("chdir").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS));
			return (int)chdir.invokeExact(arena.allocateFrom(path))==0;
		}
	}
}

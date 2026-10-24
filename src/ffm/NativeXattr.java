import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Extended attributes through libc's getxattr/setxattr (Foreign Function API, Java 22+). Compiled separately by build.sh
 * with --release 22 and loaded by reflection from FinderInfo68k, which falls back to the xattr tool when this class is
 * missing: a process per file made an Installer's disk search (one GetCatInfo per file) take hours on a large home folder.
 */
public final class NativeXattr {
	private static final MethodHandle GETXATTR,SETXATTR;
	static {
		Linker linker=Linker.nativeLinker();
		// macOS: ssize_t getxattr(const char *path, const char *name, void *value, size_t size, u_int32_t position, int options)
		GETXATTR=linker.downcallHandle(linker.defaultLookup().find("getxattr").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));
		// int setxattr(const char *path, const char *name, void *value, size_t size, u_int32_t position, int options)
		SETXATTR=linker.downcallHandle(linker.defaultLookup().find("setxattr").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));
	}
	private NativeXattr(){}

	/** The attribute's bytes, or null when the file has none (or on any error). */
	public static byte[] get(String path,String name){
		try(Arena arena=Arena.ofConfined()){
			MemorySegment p=arena.allocateFrom(path),n=arena.allocateFrom(name);
			long size=(long)GETXATTR.invokeExact(p,n,MemorySegment.NULL,0L,0,0);
			if(size<=0||size>1<<20)return null;
			MemorySegment value=arena.allocate(size);
			long got=(long)GETXATTR.invokeExact(p,n,value,size,0,0);
			if(got<0)return null;
			return value.asSlice(0,got).toArray(ValueLayout.JAVA_BYTE);
		}catch(Throwable t){return null;}
	}

	/** Sets the attribute; false on error. */
	public static boolean set(String path,String name,byte[] bytes){
		try(Arena arena=Arena.ofConfined()){
			MemorySegment p=arena.allocateFrom(path),n=arena.allocateFrom(name),value=arena.allocateFrom(ValueLayout.JAVA_BYTE,bytes);
			return (int)SETXATTR.invokeExact(p,n,value,(long)bytes.length,0,0)==0;
		}catch(Throwable t){return false;}
	}
}

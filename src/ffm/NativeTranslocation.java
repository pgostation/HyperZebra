import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Gatekeeper's App Translocation through Security.framework (Foreign Function API, Java 22+). A quarantined app opened
 * where its archive was expanded runs from a read-only copy (/private/var/folders/…/AppTranslocation/…/d/X.app) that has
 * nothing beside it, so Support Files and ExtractorApps next to the real app were not found. Compiled
 * separately by build.sh and loaded by reflection from AppPaths, which keeps the path it has when this class is missing.
 */
public final class NativeTranslocation {
	private static final MethodHandle URL_CREATE,URL_PATH,RELEASE,IS_TRANSLOCATED,ORIGINAL;
	static {
		Linker linker=Linker.nativeLinker();
		Arena global=Arena.global();
		SymbolLookup cf=SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",global);
		SymbolLookup security=SymbolLookup.libraryLookup("/System/Library/Frameworks/Security.framework/Security",global);
		// CFURLRef CFURLCreateFromFileSystemRepresentation(CFAllocatorRef, const UInt8 *buffer, CFIndex bufLen, Boolean isDirectory)
		URL_CREATE=linker.downcallHandle(cf.find("CFURLCreateFromFileSystemRepresentation").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG,ValueLayout.JAVA_BYTE));
		// Boolean CFURLGetFileSystemRepresentation(CFURLRef, Boolean resolveAgainstBase, UInt8 *buffer, CFIndex maxBufLen)
		URL_PATH=linker.downcallHandle(cf.find("CFURLGetFileSystemRepresentation").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_BYTE,ValueLayout.ADDRESS,ValueLayout.JAVA_BYTE,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG));
		RELEASE=linker.downcallHandle(cf.find("CFRelease").orElseThrow(),FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		// Boolean SecTranslocateIsTranslocatedURL(CFURLRef path, bool *isTranslocated, CFErrorRef *error)
		IS_TRANSLOCATED=linker.downcallHandle(security.find("SecTranslocateIsTranslocatedURL").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_BYTE,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS));
		// CFURLRef SecTranslocateCreateOriginalPathForURL(CFURLRef translocatedPath, CFErrorRef *error)
		ORIGINAL=linker.downcallHandle(security.find("SecTranslocateCreateOriginalPathForURL").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS));
	}
	private NativeTranslocation(){}

	/** Where the translocated path really is, or null when it is not translocated (or on any error). */
	public static String originalPath(String path){
		MemorySegment url=MemorySegment.NULL,original=MemorySegment.NULL;
		try(Arena arena=Arena.ofConfined()){
			byte[] bytes=path.getBytes(StandardCharsets.UTF_8);
			url=(MemorySegment)URL_CREATE.invokeExact(MemorySegment.NULL,arena.allocateFrom(ValueLayout.JAVA_BYTE,bytes),(long)bytes.length,(byte)1);
			if(url.address()==0)return null;
			MemorySegment flag=arena.allocate(ValueLayout.JAVA_BYTE);
			if((byte)IS_TRANSLOCATED.invokeExact(url,flag,MemorySegment.NULL)==0||flag.get(ValueLayout.JAVA_BYTE,0)==0)return null;
			original=(MemorySegment)ORIGINAL.invokeExact(url,MemorySegment.NULL);
			if(original.address()==0)return null;
			MemorySegment buffer=arena.allocate(4096);
			if((byte)URL_PATH.invokeExact(original,(byte)1,buffer,4096L)==0)return null;
			return buffer.getString(0);
		}catch(Throwable t){return null;}
		finally {
			try{
				if(url.address()!=0)RELEASE.invokeExact(url);
				if(original.address()!=0)RELEASE.invokeExact(original);
			}catch(Throwable ignored){}
		}
	}
}

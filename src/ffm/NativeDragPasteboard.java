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
 * macOS: the files under a drag that is still going on, read from the system's drag pasteboard (NSPasteboardNameDrag)
 * through the Objective-C runtime (Foreign Function API, Java 22+). Java hands a drop target the dragged data only once it
 * is dropped ("No drop current" before), so the launcher could not tell during the drag whether a program opens the
 * files. Compiled separately by build.sh with --release 22 and loaded by reflection from GLauncher.
 */
public final class NativeDragPasteboard {
	private static final MethodHandle GET_CLASS,REGISTER_SEL,POOL_PUSH,POOL_POP,MSG_ID,MSG_ID_ID,MSG_ID_PTR,MSG_LONG,MSG_ID_LONG,MSG_PTR,MSG_BOOL_ID;
	static {
		Linker linker=Linker.nativeLinker();
		SymbolLookup objc=SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib",Arena.global());
		SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit",Arena.global()); // NSPasteboard (AWT has loaded it already)
		ValueLayout A=ValueLayout.ADDRESS;
		GET_CLASS=linker.downcallHandle(objc.find("objc_getClass").orElseThrow(),FunctionDescriptor.of(A,A));
		REGISTER_SEL=linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),FunctionDescriptor.of(A,A));
		POOL_PUSH=linker.downcallHandle(objc.find("objc_autoreleasePoolPush").orElseThrow(),FunctionDescriptor.of(A));
		POOL_POP=linker.downcallHandle(objc.find("objc_autoreleasePoolPop").orElseThrow(),FunctionDescriptor.ofVoid(A));
		MemorySegment send=objc.find("objc_msgSend").orElseThrow();
		MSG_ID=linker.downcallHandle(send,FunctionDescriptor.of(A,A,A));
		MSG_ID_ID=linker.downcallHandle(send,FunctionDescriptor.of(A,A,A,A));
		MSG_ID_PTR=linker.downcallHandle(send,FunctionDescriptor.of(A,A,A,A));
		MSG_LONG=linker.downcallHandle(send,FunctionDescriptor.of(ValueLayout.JAVA_LONG,A,A));
		MSG_ID_LONG=linker.downcallHandle(send,FunctionDescriptor.of(A,A,A,ValueLayout.JAVA_LONG));
		MSG_PTR=linker.downcallHandle(send,FunctionDescriptor.of(A,A,A));
		MSG_BOOL_ID=linker.downcallHandle(send,FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,A,A,A));
	}
	private NativeDragPasteboard(){}

	/** The paths of the files on the drag pasteboard, or null when there are none (or on any error). */
	public static List<String> files(){
		MemorySegment pool=null;
		try(Arena arena=Arena.ofConfined()){
			pool=(MemorySegment)POOL_PUSH.invokeExact();
			MemorySegment pasteboard=(MemorySegment)MSG_ID_ID.invokeExact(cls(arena,"NSPasteboard"),sel(arena,"pasteboardWithName:"),string(arena,"Apple CFPasteboard drag"));
			if(pasteboard.address()==0)return null;
			List<String> r=new ArrayList<String>();
			// NSFilenamesPboardType: the paths, which the pasteboard makes from the items' file URLs
			MemorySegment names=(MemorySegment)MSG_ID_ID.invokeExact(pasteboard,sel(arena,"propertyListForType:"),string(arena,"NSFilenamesPboardType"));
			if(names.address()!=0&&isKind(arena,names,"NSArray"))
				for(long i=0,n=(long)MSG_LONG.invokeExact(names,sel(arena,"count"));i<n;i++){
					MemorySegment s=(MemorySegment)MSG_ID_LONG.invokeExact(names,sel(arena,"objectAtIndex:"),i);
					if(s.address()!=0&&isKind(arena,s,"NSString"))r.add(utf8(arena,s));
				}
			if(r.isEmpty()){ // each item's public.file-url (a file reference URL from the Finder: filePathURL makes it a path)
				MemorySegment items=(MemorySegment)MSG_ID.invokeExact(pasteboard,sel(arena,"pasteboardItems"));
				if(items.address()!=0)for(long i=0,n=(long)MSG_LONG.invokeExact(items,sel(arena,"count"));i<n;i++){
					MemorySegment item=(MemorySegment)MSG_ID_LONG.invokeExact(items,sel(arena,"objectAtIndex:"),i);
					MemorySegment text=(MemorySegment)MSG_ID_ID.invokeExact(item,sel(arena,"stringForType:"),string(arena,"public.file-url"));
					if(text.address()==0)continue;
					MemorySegment url=(MemorySegment)MSG_ID_ID.invokeExact(cls(arena,"NSURL"),sel(arena,"URLWithString:"),text);
					if(url.address()==0)continue;
					MemorySegment fileUrl=(MemorySegment)MSG_ID.invokeExact(url,sel(arena,"filePathURL"));
					if(fileUrl.address()==0)continue;
					MemorySegment path=(MemorySegment)MSG_ID.invokeExact(fileUrl,sel(arena,"path"));
					if(path.address()!=0)r.add(utf8(arena,path));
				}
			}
			return r.isEmpty()?null:r;
		}catch(Throwable t){return null;}
		finally{if(pool!=null)try{POOL_POP.invokeExact(pool);}catch(Throwable ignored){}}
	}

	private static MemorySegment cls(Arena arena,String name) throws Throwable {return (MemorySegment)GET_CLASS.invokeExact(arena.allocateFrom(name));}
	private static MemorySegment sel(Arena arena,String name) throws Throwable {return (MemorySegment)REGISTER_SEL.invokeExact(arena.allocateFrom(name));}
	private static MemorySegment string(Arena arena,String s) throws Throwable {return (MemorySegment)MSG_ID_PTR.invokeExact(cls(arena,"NSString"),sel(arena,"stringWithUTF8String:"),arena.allocateFrom(s));}
	private static boolean isKind(Arena arena,MemorySegment object,String className) throws Throwable {
		return (boolean)MSG_BOOL_ID.invokeExact(object,sel(arena,"isKindOfClass:"),cls(arena,className));
	}
	private static String utf8(Arena arena,MemorySegment string) throws Throwable {
		MemorySegment p=(MemorySegment)MSG_PTR.invokeExact(string,sel(arena,"UTF8String"));
		return p.address()==0?"":p.reinterpret(Long.MAX_VALUE).getString(0);
	}
}

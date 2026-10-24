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
 * The display names of the Mac's MIDI destinations and sources through CoreMIDI (Foreign Function API, Java 22+):
 * the JDK's own CoreMIDI provider reports every device as "Unknown name" on current macOS, so MidiHost68k asks here,
 * by reflection, and matches the names to the JDK's devices by index (both enumerate MIDIGetDestination /
 * MIDIGetSource in order). Compiled separately by build.sh with --release 22; missing on older JDKs and other systems.
 */
public final class NativeCoreMidi {
	private static final MethodHandle NUMBER_OF_DESTINATIONS,DESTINATION,NUMBER_OF_SOURCES,SOURCE,GET_STRING_PROPERTY,CFSTRING_GET_CSTRING,CFRELEASE;
	private static final MemorySegment DISPLAY_NAME_KEY;
	private static final int UTF8=0x08000100;
	static {
		Linker linker=Linker.nativeLinker();
		Arena arena=Arena.global();
		SymbolLookup coreMidi=SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreMIDI.framework/CoreMIDI",arena);
		SymbolLookup coreFoundation=SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",arena);
		NUMBER_OF_DESTINATIONS=linker.downcallHandle(coreMidi.find("MIDIGetNumberOfDestinations").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_LONG));
		DESTINATION=linker.downcallHandle(coreMidi.find("MIDIGetDestination").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_LONG));
		NUMBER_OF_SOURCES=linker.downcallHandle(coreMidi.find("MIDIGetNumberOfSources").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_LONG));
		SOURCE=linker.downcallHandle(coreMidi.find("MIDIGetSource").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_LONG));
		// OSStatus MIDIObjectGetStringProperty(MIDIObjectRef obj, CFStringRef propertyID, CFStringRef *str)
		GET_STRING_PROPERTY=linker.downcallHandle(coreMidi.find("MIDIObjectGetStringProperty").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS));
		// kMIDIPropertyDisplayName is a CFStringRef variable: the key is the pointer stored there
		DISPLAY_NAME_KEY=coreMidi.find("kMIDIPropertyDisplayName").orElseThrow().reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS,0);
		// Boolean CFStringGetCString(CFStringRef, char *buffer, CFIndex bufferSize, CFStringEncoding)
		CFSTRING_GET_CSTRING=linker.downcallHandle(coreFoundation.find("CFStringGetCString").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_BYTE,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG,ValueLayout.JAVA_INT));
		CFRELEASE=linker.downcallHandle(coreFoundation.find("CFRelease").orElseThrow(),FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
	}
	private NativeCoreMidi(){}

	/** Display names of the destinations (outputs, {@code output}) or sources, in CoreMIDI's order; null on any failure. */
	public static List<String> names(boolean output){
		try(Arena arena=Arena.ofConfined()){
			long count=output?(long)NUMBER_OF_DESTINATIONS.invokeExact():(long)NUMBER_OF_SOURCES.invokeExact();
			List<String> names=new ArrayList<String>();
			MemorySegment out=arena.allocate(ValueLayout.ADDRESS),buffer=arena.allocate(512);
			for(long i=0;i<count;i++){
				int endpoint=output?(int)DESTINATION.invokeExact(i):(int)SOURCE.invokeExact(i);
				String name="";
				out.set(ValueLayout.ADDRESS,0,MemorySegment.NULL);
				int status=(int)GET_STRING_PROPERTY.invokeExact(endpoint,DISPLAY_NAME_KEY,out);
				MemorySegment string=out.get(ValueLayout.ADDRESS,0);
				if(status==0&&!string.equals(MemorySegment.NULL)){
					byte ok=(byte)CFSTRING_GET_CSTRING.invokeExact(string,buffer,512L,UTF8);
					if(ok!=0)name=buffer.getString(0);
					CFRELEASE.invokeExact(string);
				}
				names.add(name);
			}
			return names;
		}catch(Throwable t){return null;}
	}
}

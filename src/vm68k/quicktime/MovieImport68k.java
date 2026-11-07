/**
 * The native movie import component for Standard MIDI Files ('eat ' 'Midi') of the native Movie Toolbox, present only when no
 * QuickTime extension is installed. The native toolbox reads a MIDI file as a music movie itself (NewMovieFromFile,
 * ConvertFileToMovieFile), so the component is what programs look for first: All Midi 1.1.2 finds and opens it, gives it to
 * ConvertFileToMovieFile as the import to use, and quit with a beep when FindNextComponent found none (it asks for
 * manufacturer 'musi', QuickTime Music's).
 * Selectors are QuickTimeComponents.h's (kMovieImportHandleSelect = 1 ...).
 */
final class MovieImport68k implements Components68k.Native {
	static final int EAT=0x65617420,MIDI=0x4d696469,MUSI=0x6d757369;
	static final int BAD_SELECTOR=0x80008002;

	static final int SDEC=0x73646563,MP3=0x2e6d7033,FHG=0x66686720;

	/**
	 * Also 'sdec' '.mp3', what QuickTime 4 brought and the native sound and movie paths decode themselves (Mp3Decoder): Age of
	 * Empires II looks for the component at start and offers to install QuickTime when it is not there.
	 */
	static void register(Components68k components,Toolbox68k tb){components.registerNative(EAT,MIDI,MUSI,0,new MovieImport68k());components.registerNative(SDEC,MP3,FHG,0,new MovieImport68k());}

	/** The import itself (MovieImportFile/Handle and their settings) is not offered: the toolbox's own readers do it. */
	@Override public int call(int ci,int what,int p) throws M68kException {
		if(Mac68kApi.TRACE)System.err.println("[68K] MovieImport selector "+what);
		return BAD_SELECTOR;
	}
}

/**
 * A movie's sound as the Movie Toolbox plays it (QuickTime68k): the desktop's through Java Sound and the MIDI synthesizer
 * (MovieSoundHost68k, Toolbox68kDesktopParts), the browser player's as a voice of the 68K mixer (MovieSoundMixer68k).
 */
interface MovieSound68k {
	/** Plays from the position */
	void start();
	void stop();
	/** Where it plays from next (microseconds into the movie); a playing sound goes on from there */
	void position(long micros);
	/** The movie's volume, 0..256 (full); the Mac's output volume (SetSoundVol) is on top of it (the mixer's own, or applied here) */
	void level(int level);
	void close();
	/** Its length (microseconds) */
	long lengthMicros();
}

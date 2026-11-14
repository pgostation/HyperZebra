/**
 * The movies of HyperCard 2.4's built-in Movie XCMD in the browser player (MovieWindows): what reads a movie file and plays
 * its sound. The core has no decoders (QuickTimeFile, QuickTimeVideo, the 68K mixer are the 68K player's), so the 68K player
 * sets {@link #current} (MoviePlayer68k); the core player has none, and the movie command does nothing there.
 */
abstract class MoviePlayer {
	/** The player's, or null */
	static volatile MoviePlayer current;

	/** The movie in the file, or null when it is not one this can show or play */
	abstract Movie open(java.io.File file);

	/** One opened movie */
	interface Movie {
		boolean hasVideo();
		int width();
		int height();
		/** The picture at that time (the same image while the frame does not change), null without video */
		HzImage frameAt(long millis);
		long durationMillis();
		/** Its time units a second (QuickTime's time scale; 600 when it has none) */
		int timeScale();
		/** Frames a second (stepFwd / stepRev) */
		double frameRate();
		/** Its sound from that time on (none: nothing) */
		void play(long fromMillis);
		void stop();
		/** 0..256 */
		void volume(int level);
		void close();
	}
}

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * A movie as HyperZebra's own decoders read it (QuickTimeFile, QuickTimeSound, QuickTimeVideo, Mp3Decoder): its frames (decoded
 * as they are shown), its sound, its time scale and length. The browser player's Movie Toolbox (QuickTime68k through
 * Toolbox68kParts) plays these; the desktop's TQTMovie.MovieAsset adds what the desktop has besides (ffmpeg's frames and
 * sound, QuickTime music as a MIDI sequence).
 */
class MovieData {
	/** Frames converted ahead of time (ffmpeg's, on the desktop); null or empty: none */
	BufferedImage[] frames;
	double fps;
	/** the sound decoded by QuickTimeSound */
	QuickTimeSound.Pcm sound;
	/** an MP3 file (MPEG audio Layer III), decoded while it plays */
	Mp3Decoder.Stream mp3;
	/** the video, decoded frame by frame as it is shown */
	QuickTimeVideo video;
	/** the movie header's ('mvhd') time scale (0: not known), and its length in those units */
	int timeScale, duration;
	/** QuickTime music ('musi' tracks) as a MIDI sequence (QTMusicConverter): the desktop's MIDI synthesizer, the browser player's
	 *  MidiSynth68k play it */
	javax.sound.midi.Sequence musicSequence;

	boolean hasAudio() { return sound != null || mp3 != null; }
	/** QuickTime music ('musi') it can play */
	boolean hasMusic() { return musicSequence != null; }
	boolean hasVideo() { return video != null || (frames != null && frames.length > 0); }
	int frameWidth() { return video != null ? video.width() : frames[0].getWidth(); }
	int frameHeight() { return video != null ? video.height() : frames[0].getHeight(); }
	/** Frames (video samples of the first video track, or the converted frames). */
	int frameCount() { return video != null ? video.frameCount() : frames == null ? 0 : frames.length; }
	/** Frames a second: the converted rate, or the video's average. */
	double frameRate() {
		if (video != null) { long ms = video.durationMillis(); return ms > 0 ? Math.max(1, video.frameCount()) * 1000.0 / ms : 10.0; }
		return fps > 0 ? fps : 10.0;
	}
	/** The picture at {@code ms} milliseconds into the movie (the same image while the frame does not change). */
	BufferedImage frameAtMillis(long ms) {
		if (video != null) { synchronized (video) { try { return video.frameAt(ms * video.timeScale() / 1000L); } catch (IOException e) { System.err.println("QTMovie: " + e.getMessage()); return video.lastFrame(); } } }
		if (frames == null || frames.length == 0) return null;
		return frames[(int) Math.max(0, Math.min(frames.length - 1, (long) (ms / 1000.0 * frameRate())))];
	}

	/** The default time scale of a QuickTime movie (units per second) when the file does not say. */
	static final int DEFAULT_TIME_SCALE = 600;
	private static final java.util.Map<String, MovieData> cache = new java.util.HashMap<>();

	/** A movie file read by the own decoders only, kept per file (the browser player's movies) */
	static synchronized MovieData cached(File file) {
		String key = file.getAbsolutePath();
		MovieData d = cache.get(key);
		if (d == null) { d = decode(file); cache.put(key, d); }
		return d;
	}

	/** A movie file (or an MP3) by the own decoders only: what TQTMovie's conversion does without ffmpeg and MIDI */
	static MovieData decode(File file) {
		MovieData d = new MovieData();
		if (Mp3Decoder.looksLikeMp3(file)) {
			try {
				d.mp3 = Mp3Decoder.scan(file);
				d.timeScale = DEFAULT_TIME_SCALE;
				d.duration = (int) Math.min(Integer.MAX_VALUE, d.mp3.totalSamples() * DEFAULT_TIME_SCALE / d.mp3.sampleRate());
				return d;
			} catch (IOException e) { /* not an MP3 after all */ }
		}
		QuickTimeFile movie;
		try { movie = QuickTimeFile.open(file); }
		catch (IOException | RuntimeException e) { System.out.println("Movie: not read as a QuickTime movie: " + e.getMessage()); return d; }
		if (movie.timeScale > 0) { d.timeScale = movie.timeScale; d.duration = (int) Math.min(Integer.MAX_VALUE, movie.duration); }
		boolean video = false;
		for (QuickTimeFile.Track t : movie.tracks) if (t.enabled() && t.media != null && t.media.video() && t.media.samples() > 0) video = true;
		if (QuickTimeSound.canDecode(movie)) {
			try { d.sound = QuickTimeSound.movie(movie); }
			catch (IOException | RuntimeException e) { System.err.println("Movie: sound not decoded: " + e.getMessage()); }
		}
		boolean music = false;
		for (QuickTimeFile.Track t : movie.tracks) if (t.media != null && "musi".equals(QuickTimeFile.typeName(t.media.handlerType))) music = true;
		if (video && QuickTimeVideo.canDecode(movie)) d.video = new QuickTimeVideo(movie); // (the file stays open: frames are read as shown)
		else movie.close();
		if (music) {
			try { d.musicSequence = QTMusicConverter.convert(file); }
			catch (Exception e) { System.err.println("Movie: music not read: " + e.getMessage()); }
		}
		return d;
	}
}

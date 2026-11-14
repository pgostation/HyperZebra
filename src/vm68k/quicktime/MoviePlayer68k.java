import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The browser 68K player's movies for HyperCard 2.4's built-in Movie XCMD (MovieWindows in the core): the movie as
 * HyperZebra's own decoders read it (MovieData: QuickTimeFile, QuickTimeVideo, QuickTimeSound, Mp3Decoder, QuickTime music
 * through QTMusicConverter), its sound a voice of the 68K mixer (MovieSoundMixer68k, with MidiSynth68k for music).
 * PlayerExternals68k sets it up.
 */
final class MoviePlayer68k extends MoviePlayer {
	@Override Movie open(File file) {
		MovieData d = MovieData.cached(file);
		if (d == null || !d.hasVideo() && !d.hasAudio() && !d.hasMusic()) return null;
		return new Opened(d);
	}

	private static final class Opened implements Movie {
		final MovieData data;
		final MovieSound68k sound;
		private BufferedImage lastFrame;
		private HzImage lastImage;
		Opened(MovieData data) { this.data = data; this.sound = MovieSoundMixer68k.of(data); }

		@Override public boolean hasVideo() { return data.hasVideo(); }
		@Override public int width() { return data.hasVideo() ? data.frameWidth() : 0; }
		@Override public int height() { return data.hasVideo() ? data.frameHeight() : 0; }
		@Override public HzImage frameAt(long millis) {
			if (!data.hasVideo()) return null;
			BufferedImage f = data.frameAtMillis(millis);
			if (f == null) return lastImage;
			if (f != lastFrame) { // a new picture only when the frame changed
				int w = f.getWidth(), h = f.getHeight();
				int[] px = f.getRGB(0, 0, w, h, null, 0, w);
				for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
				lastImage = new HzImage(w, h, px);
				lastFrame = f;
			}
			return lastImage;
		}
		@Override public long durationMillis() {
			if (data.timeScale > 0 && data.duration > 0) return data.duration * 1000L / data.timeScale;
			if (data.video != null) return data.video.durationMillis();
			if (data.frames != null && data.frames.length > 0) return (long) (data.frames.length * 1000.0 / data.frameRate());
			return sound == null ? 0 : sound.lengthMicros() / 1000L;
		}
		@Override public int timeScale() { return data.timeScale > 0 ? data.timeScale : MovieData.DEFAULT_TIME_SCALE; }
		@Override public double frameRate() { return data.hasVideo() ? data.frameRate() : 10; }
		@Override public void play(long fromMillis) {
			if (sound == null) return;
			sound.stop();
			sound.position(Math.max(0, fromMillis) * 1000L);
			sound.start();
		}
		@Override public void stop() { if (sound != null) sound.stop(); }
		@Override public void volume(int level) { if (sound != null) sound.level(level); }
		@Override public void close() { if (sound != null) sound.close(); }
	}
}

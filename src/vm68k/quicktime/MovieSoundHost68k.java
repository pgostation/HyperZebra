import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * The desktop's movie sound (Toolbox68kDesktopParts; what QuickTime68k did itself before the browser player had one): a Clip of
 * the decoded sound, an MP3 streamed as it plays, QuickTime music on the MIDI synthesizer. Their volume follows the Mac's
 * output volume here, as they do not go through the 68K mixer.
 */
final class MovieSoundHost68k implements MovieSound68k {
	private Clip clip;
	private StreamingSound stream; // an MP3, decoded while it plays
	private Sequencer sequencer;
	private Synthesizer synthesizer;

	/** The sound of a movie (null: none it can play) */
	static MovieSound68k of(MovieData asset) {
		MovieSoundHost68k s = new MovieSoundHost68k();
		if (asset.mp3 != null) s.stream = StreamingSound.of(asset.mp3);
		else if (asset.hasAudio() && asset instanceof TQTMovie.MovieAsset) {
			try { AudioInputStream in = HostSampleRate.forHost(((TQTMovie.MovieAsset) asset).audioStream()); s.clip = AudioSystem.getClip(); s.clip.open(in); } // (an 11127 Hz track broke the host's audio after a minute)
			catch (Exception e) { s.clip = null; }
		}
		if (s.clip == null && s.stream == null && asset instanceof TQTMovie.MovieAsset && ((TQTMovie.MovieAsset) asset).musicSequence != null) {
			Synthesizer synth = null; Sequencer sequencer = null;
			try {
				try { synth = MidiSystem.getSynthesizer(); synth.open(); sequencer = MidiSystem.getSequencer(false); sequencer.open(); sequencer.getTransmitter().setReceiver(synth.getReceiver()); }
				catch (Exception direct) { if (sequencer != null) sequencer.close(); if (synth != null) synth.close(); synth = null; sequencer = MidiSystem.getSequencer(); sequencer.open(); } // only a pre-wired default sequencer
				sequencer.setSequence(((TQTMovie.MovieAsset) asset).musicSequence); s.sequencer = sequencer; s.synthesizer = synth;
			} catch (Exception e) { System.err.println("[qt] MIDI playback unavailable: " + e); s.sequencer = null; if (synth != null) synth.close(); }
		}
		return s.clip == null && s.stream == null && s.sequencer == null ? null : s;
	}

	/** ConvertMovieToFile to a Standard MIDI File: the movie's music (QTMusicConverter) written by Java's MIDI; an OSErr */
	static int writeMidi(MovieData asset, java.io.File movie, java.io.File output) {
		javax.sound.midi.Sequence sequence = null;
		try { sequence = asset instanceof TQTMovie.MovieAsset && ((TQTMovie.MovieAsset) asset).musicSequence != null ? ((TQTMovie.MovieAsset) asset).musicSequence : movie != null ? QTMusicConverter.convert(movie) : null; }
		catch (java.io.IOException | javax.sound.midi.InvalidMidiDataException | RuntimeException e) { sequence = null; }
		if (sequence == null) return -2003; // cantFindHandler: no music in the movie
		try {
			int[] types = MidiSystem.getMidiFileTypes(sequence); int fileType = types.length == 0 ? 0 : types[0]; for (int t : types) if (t == 1) fileType = 1;
			MidiSystem.write(sequence, fileType, output);
		} catch (java.io.IOException | IllegalArgumentException e) { return -36; }
		return 0;
	}

	@Override public void start() { try { if (clip != null) clip.start(); if (stream != null) stream.start(); if (sequencer != null) sequencer.start(); } catch (Exception e) {} }
	@Override public void position(long micros) {
		try {
			if (clip != null) clip.setMicrosecondPosition(Math.min(micros, clip.getMicrosecondLength()));
			if (stream != null) stream.setMicrosecondPosition(micros);
			if (sequencer != null) sequencer.setMicrosecondPosition(Math.min(micros, sequencer.getMicrosecondLength()));
		} catch (Exception e) {}
	}
	@Override public void stop() { try { if (clip != null) clip.stop(); if (stream != null) stream.stop(); if (sequencer != null && sequencer.isRunning()) sequencer.stop(); } catch (Exception e) {} }
	@Override public void close() {
		stop();
		try { if (clip != null) clip.close(); if (stream != null) stream.close(); if (sequencer != null) sequencer.close(); if (synthesizer != null) synthesizer.close(); } catch (Exception e) {}
		clip = null; stream = null; sequencer = null; synthesizer = null;
	}
	@Override public long lengthMicros() {
		if (clip != null) return clip.getMicrosecondLength();
		if (stream != null) return stream.getMicrosecondLength();
		if (sequencer != null) return sequencer.getMicrosecondLength();
		return 0;
	}
	@Override public void level(int volume) {
		//scaled by the Mac's sound volume (SetSoundVol / SetDefaultOutputVolume), which every sound of the Mac follows
		int level = Math.round(Math.max(0, Math.min(256, volume)) * (SoundMixer68k.gainLeft() + SoundMixer68k.gainRight()) / 2);
		try {
			if (stream != null) stream.setLevel(level);
			if (clip != null && clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
				float dB = level == 0 ? gain.getMinimum() : (float) (20 * Math.log10(level / 256.0));
				gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
			}
			if (synthesizer != null) { int value = TQTMovie.midiVolume(level); for (MidiChannel channel : synthesizer.getChannels()) if (channel != null) channel.controlChange(7, value); }
		} catch (Exception e) {}
	}
}

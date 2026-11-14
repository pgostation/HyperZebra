import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** The desktop's fallback of {@link MaceConverter} (found by name): MACE its own decoder declines, converted by an installed
 *  ffmpeg into 16-bit PCM WAV in a temporary file that Java Sound then reads (moved here unchanged for the browser player,
 *  which runs no programs). */
final class MaceFfmpeg implements MaceConverter.Fallback, MaceConverter.MaceFfmpegCheck {
	private static Boolean ffmpegAvailable;
	public MaceFfmpeg() {}
	@Override public boolean available() { return isFfmpegAvailable(); }

	static boolean isFfmpegAvailable(){
		if(ffmpegAvailable==null){
			try {
				Process p = new ProcessBuilder("ffmpeg", "-version").start();
				p.getInputStream().readAllBytes();
				p.getErrorStream().readAllBytes();
				ffmpegAvailable = p.waitFor()==0;
			} catch (Exception e) {
				ffmpegAvailable = false;
			}
		}
		return ffmpegAvailable;
	}

	@Override public AudioInputStream convert(byte[] aifc) throws IOException {
		if(!isFfmpegAvailable()) return null;
		File in = TempFiles.createFile("hyperzebra-mace-", ".aifc");
		File out = new File(in.getPath()+".wav");
		try {
			Files.write(in.toPath(), aifc);
			ArrayList<String> cmd = new ArrayList<String>();
			cmd.add("ffmpeg"); cmd.add("-y"); cmd.add("-i"); cmd.add(in.getPath());
			cmd.add("-acodec"); cmd.add("pcm_s16le"); cmd.add("-f"); cmd.add("wav"); cmd.add(out.getPath());
			Process p = new ProcessBuilder(cmd).start();
			p.getInputStream().readAllBytes();
			p.getErrorStream().readAllBytes();
			int status;
			try { status = p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
			if(status!=0 || !out.isFile()) return null;
			byte[] wav = Files.readAllBytes(out.toPath());
			try {
				return AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
			} catch (UnsupportedAudioFileException e) {
				return null;
			}
		} finally {
			in.delete();
			out.delete();
		}
	}

}

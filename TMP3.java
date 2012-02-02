import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

public class TMP3 extends Thread {
	private AudioFormat decodedFormat;
	private AudioInputStream in;
	private AudioInputStream din;
	private SourceDataLine line;
	private File file;
	String name;
	private boolean end_flag = false;
	private boolean pause_flag = false;
	private boolean playing_flag = false;
	
    
	public TMP3(String filename, String title) {
    	name = "";
		try {
			file = new File(filename);
	    	if((title+".mp3").contains(file.getName())) name = file.getName().substring(0,file.getName().length()-4);
	    	else name = file.getName();
			in = AudioSystem.getAudioInputStream(file);
			din = null;
			AudioFormat baseFormat = in.getFormat();
			decodedFormat = new AudioFormat(
					AudioFormat.Encoding.PCM_SIGNED, baseFormat.getSampleRate(), 16, baseFormat.getChannels(),
					baseFormat.getChannels() * 2, baseFormat.getSampleRate(), false);
			din = AudioSystem.getAudioInputStream(decodedFormat, in);
			pause_flag = true;
			//スレッド側で再生する
			start();
		} catch (Exception e) {
			// Handle exception.
			e.printStackTrace();
		}
	}
	
	public boolean mp3ok(){
		if(this.decodedFormat==null ||this.din==null){
			return false;
		}
		return true;
	}
	
	public boolean mp3play(){
		if(this.decodedFormat==null || this.din==null){
			return false;
		}
		pause_flag = false;
		return true;
	}
	
	public void mp3stop(){
		end_flag = true;
	}

	public void setVolume(int vol){
		if(vol <= 256 && line != null){
			try{
				FloatControl control;
				control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
				control.setValue((float)Math.log10(vol/256.0F) * 20);
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			}
		}
	}

	public void setTime(int time){
		if(time == 0){
			pause_flag = true;
		}
	}

	public boolean isPlaying(){
		return playing_flag;
	}
	
	//受け取ったメッセージを実行するスレッド
	@Override
	public void run() {
        this.setName("MP3 play");
		while(!end_flag){
			if(pause_flag){
				while(pause_flag && !end_flag){
					try { Thread.sleep(500); } catch (InterruptedException e) { }
				}
				continue;
			}
			playing_flag = true;
			try {
				rawplay();
			} catch (IOException e) {
				e.printStackTrace();
			}
			playing_flag = false;
			pause_flag = true;
			
			try {
				// Stop
				din.close();
				in.close();
				in = AudioSystem.getAudioInputStream(file);
				din = AudioSystem.getAudioInputStream(decodedFormat, in);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (UnsupportedAudioFileException e) {
				e.printStackTrace();
			}
		}
		
		//停止
		try {
			line.close();
			line = null;
			din.close();
			in.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void rawplay(/*AudioFormat targetFormat, AudioInputStream din*/)
			throws IOException {
		byte[] data = new byte[8192];
		
		try {
			line = getLine(decodedFormat);
		} catch (LineUnavailableException e1) {
			e1.printStackTrace();
		}
		if (line != null) {
			// Start
			line.start();
			int nBytesRead = 0/*, nBytesWritten = 0*/;
			while (nBytesRead != -1 && !end_flag && !pause_flag) {
				nBytesRead = din.read(data, 0, data.length);
				if (nBytesRead != -1)
					/*nBytesWritten =*/ line.write(data, 0, nBytesRead);
			}
			line.drain();//再生が終わるまで待つ
			line.stop();
		}
	}

	private SourceDataLine getLine(AudioFormat audioFormat)
			throws LineUnavailableException {
		SourceDataLine res = null;
		DataLine.Info info = new DataLine.Info(SourceDataLine.class,
				audioFormat);
		res = (SourceDataLine) AudioSystem.getLine(info);
		res.open(audioFormat, 524288);//512KBくらいあればOK?
		return res;
	}
}
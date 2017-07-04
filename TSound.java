import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;


public class TSound implements LineListener {
    static private double note_freq[] = new double[96];
    private ArrayList<Clip> clipList = new ArrayList<Clip>();
    private ArrayList<String> nameList = new ArrayList<String>();
    private boolean nowPlaying = false;
    private Clip nowClip = null;
    public String name = "";
	static boolean use = true;
    
    private void setNoteFreq() {
	    if(note_freq[0] != 1.0) {
	        // 半音(2の12乗根)を計算
	        double r = Math.pow(2.0, 1.0 / 12.0);
	        note_freq[0] = 1.0;
	        for (int i = 1; i < 12; i++) {
	            note_freq[i] = note_freq[i-1] * r;
	        }
	        for (int i = 12; i < 96; i++) {
	            note_freq[i] = note_freq[i-12] * 2.0;
	        }
	    }
    }

    public boolean Play(OStack stack, String soundRsrc, String[] neiro, int tempo, int vol)
	throws xTalkException {
    	if(!use) return true;
    	
    	if(note_freq[0] != 1.0) setNoteFreq();
    	
    	//3連符
    	if(neiro[5].contains("3")){
    		if(neiro[5].contains("....")) neiro[5] = "....";
    		else if(neiro[5].contains("...")) neiro[5] = "...";
    		else if(neiro[5].contains("..")) neiro[5] = "..";
    		else if(neiro[5].contains(".")) neiro[5] = ".";
    		else neiro[5] = "";
    		Play(stack, soundRsrc, neiro, tempo, vol);
    		Play(stack, soundRsrc, neiro, tempo, vol);
    	}
    	
    	//長さ計算(秒)
    	double length = 0.0;
		if(neiro[4].equalsIgnoreCase("w")) length = 240.0/tempo;
		else if(neiro[4].equalsIgnoreCase("h")) length = 120.0/tempo;
		else if(neiro[4].equalsIgnoreCase("q")) length = 60.0/tempo;
		else if(neiro[4].equalsIgnoreCase("e")) length = 30.0/tempo;
		else if(neiro[4].equalsIgnoreCase("s")) length = 15.0/tempo;
		else if(neiro[4].equalsIgnoreCase("t")) length = 7.5/tempo;
		else if(neiro[4].equalsIgnoreCase("x")) length = 3.75/tempo;
		else if(!neiro[1].equals("")) length = 60.0/tempo;
		if(neiro[5].equals("....")) length *= 1.5*1.5*1.5*1.5;
		if(neiro[5].equals("...")) length *= 1.5*1.5*1.5;
		if(neiro[5].equals("..")) length *= 1.5*1.5;//1.75倍じゃないようだ。
		if(neiro[5].equals(".")) length *= 1.5;

		AudioInputStream sound = null;
		try {
			int rsrcid = stack.rsrc.getRsrcIdAll(soundRsrc, "sound");
			String path = stack.rsrc.getFilePathAll(rsrcid, "sound");
			sound = AudioSystem.getAudioInputStream(new File(path/*stack.file.getParent()+File.separatorChar+fileName*/));
		} catch (UnsupportedAudioFileException e) {
			//throw new xTalkException("サウンド\""+soundRsrc+"\"の音声ファイルに対応していません");
			return false;
		} catch (Exception e) {
			/*String path = (PCARDFrame.pc.stack.file.getParent()+File.separatorChar+"resource"+File.separatorChar+soundRsrc);
			try {
	    		sound = AudioSystem.getAudioInputStream(new File(path+".wav"));
			} catch (Exception e2) {
				try {
					sound = AudioSystem.getAudioInputStream(new File(path+".aiff"));
				} catch (Exception e3) {*/
					String cpath = ("resource"+File.separatorChar+soundRsrc);
					try {
			    		sound = AudioSystem.getAudioInputStream(new File(cpath+".wav"));
					} catch (FileNotFoundException e4) {
						throw new xTalkException("サウンド\""+soundRsrc+"\"がありません");
					} catch (IOException e4) {
						throw new xTalkException("サウンド\""+soundRsrc+"\"を開けません");
					} catch (UnsupportedAudioFileException e4) {
						throw new xTalkException("サウンド\""+soundRsrc+"\"の音声ファイルに対応していません");
					}
				//}
			//}
		}
		
		try{
			AudioFormat fileFormat = sound.getFormat();
			
			Clip clip;
			if(neiro[1].equals("") && length==0.0){
				DataLine.Info di = new DataLine.Info(Clip.class, fileFormat);
				clip = (Clip) AudioSystem.getLine(di);
				clip.open(sound);
			}else{
				AudioFormat format  = null;
				int code = 0;
				if(neiro[1].equalsIgnoreCase("c")) code = 0;
				else if(neiro[1].equalsIgnoreCase("d")) code = 2;
				else if(neiro[1].equalsIgnoreCase("e")) code = 4;
				else if(neiro[1].equalsIgnoreCase("f")) code = 5;
				else if(neiro[1].equalsIgnoreCase("g")) code = 7;
				else if(neiro[1].equalsIgnoreCase("a")) code = 9;
				else if(neiro[1].equalsIgnoreCase("b")) code = 11;
				if(neiro[2].equals("#")) code += 1;
				if(neiro[2].equals("b")) code -= 1;
				if(neiro[3].equals("")) code += 48;
				else code += 12*Integer.valueOf(neiro[3]);
	
				byte[] voiceData = new byte[(int) (sound.getFrameLength()*fileFormat.getFrameSize())];
				sound.read(voiceData,0,voiceData.length);
				sound.close();
				sound = null;
				
				int newRate = (int) (fileFormat.getSampleRate()*note_freq[code]/note_freq[48]);
				int newFrameRate = (int) (fileFormat.getFrameRate()*note_freq[code]/note_freq[48]);
				int newSampRate = newRate;
				if(newSampRate < 8000) newSampRate = 8000;
				if(newSampRate > 48000) newSampRate = 48000;
				if(newFrameRate < 8000) newFrameRate = 8000;
				if(newFrameRate > 48000) newFrameRate = 48000;
				int newLength = fileFormat.getFrameSize()*(int)(voiceData.length/fileFormat.getFrameSize()*((float)newSampRate/newRate));

				format = new AudioFormat(fileFormat.getEncoding(), newSampRate, fileFormat.getSampleSizeInBits(),
						fileFormat.getChannels(), fileFormat.getFrameSize(), newFrameRate, fileFormat.isBigEndian());

				//長さ
				int nLength = voiceData.length;
				if( length!=0.0 || neiro[1].equalsIgnoreCase("r") ){
					nLength = (int)(length*format.getSampleRate())*format.getFrameSize();
				}

				byte[] newBuffer = new byte[nLength];
				
				if(newRate == newSampRate && nLength == newLength){
					DataLine.Info di = new DataLine.Info(Clip.class, format);
					clip = (Clip) AudioSystem.getLine(di);
					clip.open(format, voiceData, 0, voiceData.length);
				} else {
					if( !neiro[1].equalsIgnoreCase("r")) {
						for(int i=0; i<newBuffer.length/fileFormat.getFrameSize(); i++){
							if(((int)(i*((float)newRate/newSampRate))*fileFormat.getFrameSize())+fileFormat.getFrameSize() >= voiceData.length) break;
							for(int j=0; j<fileFormat.getFrameSize(); j++){
								newBuffer[i*fileFormat.getFrameSize()+j] = voiceData[((int)(i*((float)newRate/newSampRate))*fileFormat.getFrameSize())+j];
							}
						}
					}
					DataLine.Info di = new DataLine.Info(Clip.class, format);
					clip = (Clip) AudioSystem.getLine(di);
					clip.open(format, newBuffer, 0, newBuffer.length);
				}
			}

			if(vol != 100){
				try{
					FloatControl control = (FloatControl)clip.getControl(FloatControl.Type.MASTER_GAIN);
					control.setValue((float)Math.log10(vol/100.0F) * 20);
				} catch (IllegalArgumentException e) {
					//no msg
				}
			}
			
			if(!nowPlaying) {
				nowPlaying = true;
				clip.start();
				nowClip = clip;
				if(sound!=null) sound.close();
				clip.addLineListener(this);
			}
			else {
				clipList.add(clip);
				nameList.add(soundRsrc);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (LineUnavailableException e) {
			e.printStackTrace();
		}
		
		return true;
    }

    public void PlayStop() {
    	if(nowClip!=null) {
			clipList.clear();
			nameList.clear();
    		nowClip.stop();
			nowClip = null;
    		name = "";
    	}
    }
	//todo: 音量調整
    
	@Override
	public void update(LineEvent event) {
        if (event.getType() == LineEvent.Type.STOP) {
            Clip clip = (Clip) event.getSource();
            clip.stop();
            if(clipList.size()==0){
				nowPlaying = false;
				nowClip = null;
	    		name = "";
            }else{
            	nowClip = clipList.get(0);
            	name = nameList.get(0);
				clipList.remove(0);
				nameList.remove(0);
				nowPlaying = true;
				nowClip.start();
				nowClip.addLineListener(this);
            }
        }
        if (event.getType() == LineEvent.Type.CLOSE) {
			nowPlaying = false;
			clipList.clear();
			nameList.clear();
			nowClip = null;
    		name = "";
        }
    }
}
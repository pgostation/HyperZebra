import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * Sound input (the SPB routines of _SoundDispatch group $14) on the host's default capture line: one device, "Built-in",
 * recording 8- or 16-bit mono/stereo PCM at the rate the program sets (22050 Hz by default). SPBRecord fills the
 * program's buffer (asynchronously through the emulation thread's deferred queue, so the completion routine and the
 * parameter block are only touched between the program's own instructions); the level meter, SPBGetDeviceInfo/
 * SetDeviceInfo, the sound header helpers and the SndRecord dialog are here too.
 */
final class SoundInput68k {
	private static final int NO_ERR=0,SI_NO_SOUND_IN_HARDWARE=-220,SI_BAD_SOUND_IN_DEVICE=-221,SI_INVALID_SAMPLE_RATE=-225,SI_INVALID_SAMPLE_SIZE=-226,SI_DEVICE_BUSY=-227,SI_BAD_REF_NUM=-229,SI_UNKNOWN_INFO_TYPE=-231,USER_CANCELED=-128,ABORT_ERR=-27;
	private final M68kMemory memory;private final Mac68kApi macApi;
	private final Map<Integer,Device> devices=new HashMap<Integer,Device>();
	private int nextReference=0x5000;

	/** An opened input device: its format and, while recording, the recorder. */
	private static final class Device {
		final int reference;double rate=22050;int sampleSize=8,channels=1;volatile Recorder recorder;volatile int level;boolean levelMeter;int quality=0x676f6f64; // 'good'
		Device(int reference){this.reference=reference;}
		AudioFormat format(){return new AudioFormat((float)rate,sampleSize,channels,sampleSize==16,true);}
		int frameBytes(){return sampleSize/8*channels;}
	}

	SoundInput68k(M68kMemory memory,Mac68kApi macApi){this.memory=memory;this.macApi=macApi;}

	void shutDown(){for(Device d:devices.values()){Recorder r=d.recorder;if(r!=null)r.stop();}}

	boolean dispatch(M68kCpu cpu,int selector,int argBytes) throws M68kCpuException {
		int sp=cpu.a[7];int result=NO_ERR;
		switch(selector){
			case 0x00:cpu.a[7]+=argBytes;memory.writeLong(cpu.a[7],0x01008000);return true; // SPBVersion: 1.0
			case 0x0c:case 0x10:break; // SPBSignInDevice / SPBSignOutDevice: the one device is always signed in
			case 0x14:{int iconPtr=memory.readLong(sp),namePtr=memory.readLong(sp+4),count=(short)memory.readWord(sp+8); // SPBGetIndexedDevice
				if(count==1){if(namePtr!=0)macApi.application().writePascal(namePtr,"Built-in",255);if(iconPtr!=0)memory.writeLong(iconPtr,0);}else result=SI_BAD_SOUND_IN_DEVICE;break;}
			case 0x18:{int refPtr=memory.readLong(sp); // SPBOpenDevice(deviceName, permission, VAR inRefNum): any name opens the device
				// the microphone question waits for SPBRecord: ScreenMovie opens the device at start-up (to offer sound with its
				// captures) and sat behind the Sandbox dialog before it had shown a window
				if(!TSound.use||!hostHasInput()){result=SI_NO_SOUND_IN_HARDWARE;if(refPtr!=0)memory.writeLong(refPtr,0);break;}
				Device d=new Device(nextReference++);devices.put(d.reference,d);if(refPtr!=0)memory.writeLong(refPtr,d.reference);break;}
			case 0x1c:{Device d=devices.remove(memory.readLong(sp));if(d==null)result=SI_BAD_REF_NUM;else{Recorder r=d.recorder;if(r!=null)r.stop();}break;} // SPBCloseDevice
			case 0x20:{boolean async=memory.readByte(sp)!=0;int spb=memory.readLong(sp+2);cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],record(cpu,spb,async));return true;} // SPBRecord(inParamPtr, asynchFlag)
			case 0x24:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],-50);return true; // SPBRecordToFile: not offered (programs record to memory and write themselves)
			case 0x28:case 0x2c:{Device d=devices.get(memory.readLong(sp));if(d==null)result=SI_BAD_REF_NUM;else{Recorder r=d.recorder;if(r!=null)r.paused=selector==0x28;}break;} // SPBPauseRecording / SPBResumeRecording
			case 0x30:{Device d=devices.get(memory.readLong(sp));if(d==null)result=SI_BAD_REF_NUM;else{Recorder r=d.recorder;if(r!=null)r.stop();}break;} // SPBStopRecording
			case 0x34:{ // SPBGetRecordingStatus(inRefNum, VAR status, VAR meter, VAR totalSamples, VAR samplesRecorded, VAR totalMsecs, VAR msecsRecorded)
				int msRecPtr=memory.readLong(sp),msTotalPtr=memory.readLong(sp+4),samplesRecPtr=memory.readLong(sp+8),samplesTotalPtr=memory.readLong(sp+12),meterPtr=memory.readLong(sp+16),statusPtr=memory.readLong(sp+20);
				Device d=devices.get(memory.readLong(sp+24));if(d==null){result=SI_BAD_REF_NUM;break;}
				Recorder r=d.recorder;boolean active=r!=null&&!r.finished;int frameBytes=Math.max(1,d.frameBytes());
				long totalBytes=r==null?0:r.target,recorded=r==null?0:r.recorded();
				if(statusPtr!=0)memory.writeWord(statusPtr,active?(r.paused?2:1):0);
				if(meterPtr!=0)memory.writeWord(meterPtr,d.level);
				if(samplesTotalPtr!=0)memory.writeLong(samplesTotalPtr,(int)(totalBytes/frameBytes));if(samplesRecPtr!=0)memory.writeLong(samplesRecPtr,(int)(recorded/frameBytes));
				if(msTotalPtr!=0)memory.writeLong(msTotalPtr,(int)(totalBytes*1000/frameBytes/d.rate));if(msRecPtr!=0)memory.writeLong(msRecPtr,(int)(recorded*1000/frameBytes/d.rate));
				break;}
			case 0x38:{int infoData=memory.readLong(sp),type=memory.readLong(sp+4);Device d=devices.get(memory.readLong(sp+8));result=d==null?SI_BAD_REF_NUM:getInfo(d,type,infoData);break;} // SPBGetDeviceInfo
			case 0x3c:{int infoData=memory.readLong(sp),type=memory.readLong(sp+4);Device d=devices.get(memory.readLong(sp+8));result=d==null?SI_BAD_REF_NUM:setInfo(d,type,infoData);break;} // SPBSetDeviceInfo
			case 0x40:{int valuePtr=memory.readLong(sp);Device d=devices.get(memory.readLong(sp+4));if(d==null)result=SI_BAD_REF_NUM;else if(valuePtr!=0)memory.writeLong(valuePtr,(int)(memory.readLong(valuePtr)*d.rate*d.frameBytes()/1000));break;} // SPBMillisecondsToBytes
			case 0x44:{int valuePtr=memory.readLong(sp);Device d=devices.get(memory.readLong(sp+4));if(d==null)result=SI_BAD_REF_NUM;else if(valuePtr!=0)memory.writeLong(valuePtr,(int)(memory.readLong(valuePtr)*1000/(d.rate*Math.max(1,d.frameBytes()))));break;} // SPBBytesToMilliseconds
			case 0x04:{int handlePtr=memory.readLong(sp);result=sndRecord(handlePtr);break;} // SndRecord(filterProc, corner, quality, VAR sndHandle)
			case 0x08:{int fRefNum=(short)memory.readWord(sp);result=sndRecordToFile(fRefNum);break;} // SndRecordToFile(filterProc, corner, quality, fRefNum)
			case 0x48:result=setupSndHeader(sp);break;
			case 0x4c:result=setupAiffHeader(sp);break;
			case 0x58:result=parseAiffHeader(sp);break;
			case 0x5c:result=parseSndHeader(sp);break;
			case 0x50:{int namePtr=memory.readLong(sp);if(namePtr!=0)macApi.application().writePascal(namePtr,"Built-in",255);break;} // private: the default input device's name (the Sound control panel's choice; Sound Recording Demo's SPBGetDefaultDevice)
			default:result=SI_UNKNOWN_INFO_TYPE;break;
		}
		cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],result);return true;
	}

	private static boolean hostHasInput(){
		try{return AudioSystem.isLineSupported(new javax.sound.sampled.DataLine.Info(TargetDataLine.class,new AudioFormat(22050,8,1,false,true)));}catch(RuntimeException e){return false;}
	}

	private int getInfo(Device d,int type,int infoData) throws M68kCpuException {
		if(infoData==0)return -50;
		switch(type){
			case 0x73726174:memory.writeLong(infoData,(int)Math.round(d.rate*65536));return NO_ERR;       // srat: Fixed
			case 0x7373697a:memory.writeWord(infoData,d.sampleSize);return NO_ERR;                        // ssiz
			case 0x6368616e:memory.writeWord(infoData,d.channels);return NO_ERR;                          // chan
			case 0x636f6d70:memory.writeLong(infoData,0x4e4f4e45);return NO_ERR;                          // comp: 'NONE'
			case 0x6c65766c:memory.writeWord(infoData,d.level);return NO_ERR;                             // levl
			case 0x6c6d6574:memory.writeWord(infoData,d.levelMeter?1:0);memory.writeWord(infoData+2,d.levelMeter?d.level:0);return NO_ERR; // lmet: on/off, then the level 0..255 (Sound Recording Demo's meter)
			case 0x6c6d6163:for(int i=0;i<d.channels;i++)memory.writeWord(infoData+2*i,d.levelMeter?d.level:0);return NO_ERR; // lmac (siActiveLevels): one level per channel
			case 0x696e7074:case 0x736f7572:memory.writeWord(infoData,1);return NO_ERR;                   // inpt, sour (siInputSource, Sound.h): the one source
			case 0x736e616d:{byte[] n="Built-in".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);byte[] t=new byte[3+n.length];t[1]=1;t[2]=(byte)n.length;System.arraycopy(n,0,t,3,n.length);memory.writeLong(infoData,macApi.createHandle(t));return NO_ERR;} // snam (siInputSourceNames): a Handle to a STR# list
			case 0x63686163:memory.writeLong(infoData,(1<<d.channels)-1);return NO_ERR;                   // chac (siActiveChannels): channel bits
			case 0x63686176:memory.writeWord(infoData,2);return NO_ERR;                                   // chav (siChannelAvailable)
			case 0x6173796e:memory.writeWord(infoData,1);return NO_ERR;                                   // asyn: recording runs asynchronously
			case 0x64636f6e:memory.writeWord(infoData,1);return NO_ERR;                                   // dcon (siDeviceConnected): siDeviceIsConnected
			case 0x6f707464:memory.writeWord(infoData,0);return NO_ERR;                                   // optd (siOptionsDialog): no options dialog
			case 0x7175616c:memory.writeLong(infoData,d.quality);return NO_ERR;                           // qual
			case 0x61676320:case 0x706c7468:case 0x74776f73:case 0x636f6e74:memory.writeWord(infoData,0);return NO_ERR;   // agc, plth (playthrough), twos
			case 0x6761696e:memory.writeLong(infoData,0x00010000);return NO_ERR;                          // gain: Fixed 1.0
			case 0x6e616d65:macApi.application().writePascal(infoData,"Built-in",255);return NO_ERR;      // name
			// The lists are SoundInfoList records {count (short), Handle to the array} at infoData - the count is not in the Handle
			// (Rinaldi's RecordingRates read the Handle's address as rates, RecordIt crashed at $FFFFFF)
			case 0x73726176:{byte[] t=new byte[4*3];int[] rates={11025,22050,44100};for(int i=0;i<3;i++)putLong(t,i*4,rates[i]<<16);memory.writeWord(infoData,3);memory.writeLong(infoData+2,macApi.createHandle(t));return NO_ERR;} // srav: Fixed rates
			case 0x73736176:{byte[] t={0,8,0,16};memory.writeWord(infoData,2);memory.writeLong(infoData+2,macApi.createHandle(t));return NO_ERR;} // ssav: sample sizes (shorts)
			case 0x636d6176:{byte[] t=new byte[4];putLong(t,0,0x4e4f4e45);memory.writeWord(infoData,1);memory.writeLong(infoData+2,macApi.createHandle(t));return NO_ERR;} // cmav: compression types
			case 0x6e636861:memory.writeWord(infoData,2);return NO_ERR;                                   // ncha? number of channels available
			default:return SI_UNKNOWN_INFO_TYPE;
		}
	}
	/** The recording qualities of siRecordingQuality: {rate, sample size}, null for an unknown one. */
	private static int[] quality(int q){
		switch(q){
			case 0x63642020:case 0x62657374:return new int[]{44100,16}; // 'cd  ' (siCDQuality, what Audacity asks for), 'best'
			case 0x62657472:return new int[]{22050,16}; // 'betr'
			case 0x676f6f64:return new int[]{22050,8};  // 'good'
			default:return null;
		}
	}
	private int setInfo(Device d,int type,int infoData) throws M68kCpuException {
		switch(type){
			case 0x73726174:{double rate=Integer.toUnsignedLong(memory.readLong(infoData))/65536.0;if(rate<4000||rate>96000)return SI_INVALID_SAMPLE_RATE;d.rate=rate;return NO_ERR;}
			case 0x7373697a:{int size=memory.readWord(infoData);if(size!=8&&size!=16)return SI_INVALID_SAMPLE_SIZE;d.sampleSize=size;return NO_ERR;}
			case 0x6368616e:{int n=memory.readWord(infoData);if(n!=1&&n!=2)return -50;d.channels=n;return NO_ERR;}
			case 0x636f6d70:return memory.readLong(infoData)==0x4e4f4e45?NO_ERR:SI_UNKNOWN_INFO_TYPE;
			case 0x61676320:case 0x706c7468:case 0x6761696e:case 0x74776f73:case 0x636f6e74:return NO_ERR; // ... cont (siContinuous): the recorder always streams
			case 0x6c6d6574:d.levelMeter=memory.readWord(infoData)!=0;return NO_ERR;                     // lmet (siLevelMeterOnOff): Kingyo2 turns the meter on before it listens
			case 0x696e7074:case 0x736f7572:return NO_ERR;                                                // inpt, sour (siInputSource): the one source
			case 0x7175616c:{int q=memory.readLong(infoData);int[] f=quality(q); // qual (siRecordingQuality): Audacity 0.94 sets it right after opening and treats an error as "null input device"
				if(f==null)return SI_UNKNOWN_INFO_TYPE;d.rate=f[0];d.sampleSize=f[1];d.channels=1;d.quality=q;return NO_ERR;}
			default:return SI_UNKNOWN_INFO_TYPE;
		}
	}

	// ------------------------------------------------------------------ recording

	/** SPB: inRefNum 0, count 4, milliseconds 8, bufferLength 12, bufferPtr 16, completionRoutine 20, interruptRoutine 24, userLong 28, error 32. */
	private int record(M68kCpu cpu,int spb,boolean async) throws M68kCpuException {
		if(spb==0)return -50;
		int refused=recordRefused(spb);
		// A request that cannot start ends at once with its error in the SPB too (not over a recording still running in this very block)
		if(refused!=NO_ERR&&refused!=SI_NO_SOUND_IN_HARDWARE){if(refused!=SI_DEVICE_BUSY||devices.get(memory.readLong(spb)).recorder.spb!=spb)memory.writeWord(spb+32,refused);return refused;}
		Device d=devices.get(memory.readLong(spb));
		// The microphone not allowed (the Sandbox) - something a Mac never answers once the device is open: an asynchronous request
		// is accepted and completes with the error, its completion routine called as for a recording that failed. The Sound Recording
		// Demo's SPBRecordToHandleSync ignores SPBRecord's result and waits for its completion routine; it spun until the VM's
		// instruction limit stopped it. A synchronous one answers the error.
		if(refused!=NO_ERR){
			memory.writeWord(spb+32,refused);if(!async)return refused;
			Recorder r=new Recorder(d,spb,0,0);r.finished=true;r.error=refused;d.recorder=r;
			macApi.application().later(new Runnable(){@Override public void run(){try{if(d.recorder==r)r.finishAsync();}catch(M68kCpuException e){throw Toolbox68k.wrap(e);}}});
			return NO_ERR;}
		int count=memory.readLong(spb+4),milliseconds=memory.readLong(spb+8),bufferLength=memory.readLong(spb+12),buffer=memory.readLong(spb+16);
		long target=buffer==0?Long.MAX_VALUE:Math.max(0,bufferLength);
		if(count>0)target=Math.min(target,count);
		if(milliseconds>0)target=Math.min(target,(long)(milliseconds*d.rate/1000)*d.frameBytes());
		if(buffer!=0&&target<=0){memory.writeLong(spb+4,0);memory.writeLong(spb+8,0);memory.writeWord(spb+32,0);return NO_ERR;}
		Recorder r=new Recorder(d,spb,buffer,target);d.recorder=r;
		if(!TSound.use){r.finished=true;finish(r,ABORT_ERR);return NO_ERR;}
		memory.writeWord(spb+32,1); // in progress: the error field stays positive until the recording is complete
		Thread t=new Thread(r,"68K sound input");t.setDaemon(true);t.start();
		if(async)return NO_ERR;
		while(!r.finished)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();r.stop();break;}
		r.deliverNow();r.finishAsync();return NO_ERR;
	}
	/** Why SPBRecord cannot start (no such device, it records already, the microphone is not allowed), else noErr. */
	private int recordRefused(int spb) throws M68kCpuException {
		Device d=devices.get(memory.readLong(spb));if(d==null)return SI_BAD_REF_NUM;
		if(d.recorder!=null&&!d.recorder.finished)return SI_DEVICE_BUSY;
		if(TSound.use&&!Sandbox.allowOther(macApi.application().name,Sandbox.USE_MICROPHONE,""))return SI_NO_SOUND_IN_HARDWARE; // recording is what reaches the microphone
		return NO_ERR;
	}
	/** Writes the final counts into the SPB and runs the completion routine (pascal proc(inParamPtr), A0 = the block). */
	private void finish(Recorder r,int error) throws M68kCpuException {
		long recorded=r.delivered;Device d=r.device;
		memory.writeLong(r.spb+4,(int)recorded);memory.writeLong(r.spb+8,(int)(recorded*1000/Math.max(1,d.frameBytes())/d.rate));memory.writeWord(r.spb+32,error);
		int proc=memory.readLong(r.spb+20);if(proc==0)return;
		Toolbox68k tb=macApi.application();M68kCpu cpu=tb.cpu;int[] dd=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;cpu.a[0]=r.spb;cpu.pushLong(r.spb);
		try{cpu.callPascal(proc,4);}catch(M68kException e){throw Toolbox68k.wrap(e);}finally{System.arraycopy(dd,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
	}

	private final class Recorder implements Runnable {
		final Device device;final int spb,buffer;final long target;
		private final ByteArrayOutputStream captured=new ByteArrayOutputStream();
		volatile boolean stopped,finished,paused;volatile int error;
		/** Bytes copied into the program's buffer so far (emulation thread). */
		long delivered;
		Recorder(Device device,int spb,int buffer,long target){this.device=device;this.spb=spb;this.buffer=buffer;this.target=target;}
		synchronized long recorded(){return Math.max(delivered,captured.size());}
		void stop(){stopped=true;}
		/** Emulation thread: what the host captured since the last delivery goes into the buffer and the SPB's counts. */
		synchronized void deliverNow() throws M68kCpuException {
			byte[] all=captured.toByteArray();if(all.length<=delivered)return;
			int n=(int)Math.min(all.length-delivered,target==Long.MAX_VALUE?all.length-delivered:target-delivered);
			if(buffer!=0&&n>0)memory.load((int)(buffer+delivered),java.util.Arrays.copyOfRange(all,(int)delivered,(int)delivered+n));
			delivered+=n;memory.writeLong(spb+4,(int)delivered);memory.writeLong(spb+8,(int)(delivered*1000/Math.max(1,device.frameBytes())/device.rate));
		}
		@Override public void run(){
			TargetDataLine line=null;
			try{
				AudioFormat format=device.format();line=AudioSystem.getTargetDataLine(format);line.open(format);line.start();
				byte[] chunk=new byte[Math.max(256,(int)(format.getFrameRate()*format.getFrameSize()/20))];long total=0;
				while(!stopped&&total<target){
					if(paused){try{Thread.sleep(10);}catch(InterruptedException e){break;}continue;}
					int n=line.read(chunk,0,(int)Math.min(chunk.length,target-total));if(n<=0)break;
					synchronized(this){captured.write(chunk,0,n);}total+=n;device.level=meter(chunk,n,format);
					macApi.application().later(new Runnable(){@Override public void run(){try{deliverNow();}catch(M68kCpuException e){throw Toolbox68k.wrap(e);}}});
				}
				if(stopped&&total<target)error=ABORT_ERR;
			}catch(LineUnavailableException|IllegalArgumentException|SecurityException unavailable){error=SI_NO_SOUND_IN_HARDWARE;if(Mac68kApi.TRACE)System.err.println("[68K] sound input: "+unavailable);
			}finally{if(line!=null)try{line.close();}catch(RuntimeException ignored){}finished=true;device.level=0;
				macApi.application().later(new Runnable(){@Override public void run(){try{deliverNow();if(device.recorder==Recorder.this)finishAsync();}catch(M68kCpuException e){throw Toolbox68k.wrap(e);}}});}
		}
		private boolean completed;
		/** The completion routine once, from the emulation thread (a synchronous SPBRecord already ran it). */
		void finishAsync() throws M68kCpuException {if(completed)return;completed=true;finish(this,error);}
	}
	private static int meter(byte[] chunk,int n,AudioFormat format){
		int peak=0;boolean sixteen=format.getSampleSizeInBits()==16;
		for(int i=0;i+1<n;i+=sixteen?2:1){int v=sixteen?Math.abs((short)((chunk[i]&0xff)<<8|chunk[i+1]&0xff))>>7:Math.abs((chunk[i]&0xff)-128)*2;if(v>peak)peak=v;}
		return Math.min(255,peak);
	}

	// ------------------------------------------------------------------ SndRecord

	/** SndRecord: a small host dialog records until Stop, the result is a 'snd ' (8-bit 22 kHz) in a new Handle; -128 when cancelled. */
	private int sndRecord(int handlePtr) throws M68kCpuException {
		byte[] pcm=recordWithDialog();if(pcm==null)return USER_CANCELED;
		if(handlePtr!=0)memory.writeLong(handlePtr,macApi.createHandle(sndResource(pcm,22050,8,1)));return NO_ERR;
	}
	/** SndRecordToFile: the same dialog, the recording written as an AIFF into the open file. */
	private int sndRecordToFile(int fRefNum) throws M68kCpuException {
		byte[] pcm=recordWithDialog();if(pcm==null)return USER_CANCELED;
		try{return macApi.fileManager().writeReference(fRefNum,0,aiff(pcm,22050,8,1))?NO_ERR:-51;}catch(IOException e){return -36;}
	}
	private byte[] recordWithDialog(){
		if(!TSound.use||!hostHasInput()||java.awt.GraphicsEnvironment.isHeadless())return null;
		// the Sandbox decides first, as for SPBRecord: Rinaldi's SoundRecord XCMD reached the microphone with access denied
		if(!Sandbox.allowOther(macApi.application().name,Sandbox.USE_MICROPHONE,""))return null;
		final AudioFormat format=new AudioFormat(22050,8,1,false,true);final ByteArrayOutputStream out=new ByteArrayOutputStream();
		final boolean[] recording=new boolean[1],stop=new boolean[1],started=new boolean[1];final int[] choice={-1};
		// the line opens at the first click on Record, not when the dialog appears (the microphone stays closed if it is cancelled)
		final Thread capture=new Thread(new Runnable(){@Override public void run(){
			try{TargetDataLine line=AudioSystem.getTargetDataLine(format);line.open(format);line.start();byte[] chunk=new byte[2205];
				while(!stop[0]){int n=line.read(chunk,0,chunk.length);if(n<=0)break;if(recording[0])synchronized(out){out.write(chunk,0,n);}}
				line.close();}catch(LineUnavailableException|RuntimeException e){}}},"68K SndRecord");
		capture.setDaemon(true);
		try{
			javax.swing.SwingUtilities.invokeAndWait(new Runnable(){@Override public void run(){
				final javax.swing.JDialog dialog=new javax.swing.JDialog((java.awt.Frame)null,"Record",true);
				final javax.swing.JLabel status=new javax.swing.JLabel("Click Record to start.");
				javax.swing.JButton record=new javax.swing.JButton("Record"),stopButton=new javax.swing.JButton("Stop"),save=new javax.swing.JButton("Save"),cancel=new javax.swing.JButton("Cancel");
				stopButton.setEnabled(false);save.setEnabled(false);
				record.addActionListener(e->{synchronized(out){out.reset();}recording[0]=true;if(!started[0]){started[0]=true;capture.start();}status.setText("Recording...");record.setEnabled(false);stopButton.setEnabled(true);});
				stopButton.addActionListener(e->{recording[0]=false;status.setText("Recorded "+(out.size()/22050.0)+" s.");stopButton.setEnabled(false);record.setEnabled(true);save.setEnabled(true);});
				save.addActionListener(e->{choice[0]=1;dialog.dispose();});cancel.addActionListener(e->{choice[0]=0;dialog.dispose();});
				javax.swing.JPanel buttons=new javax.swing.JPanel();buttons.add(record);buttons.add(stopButton);buttons.add(save);buttons.add(cancel);
				dialog.getContentPane().add(status,java.awt.BorderLayout.NORTH);dialog.getContentPane().add(buttons,java.awt.BorderLayout.SOUTH);
				dialog.pack();dialog.setLocationRelativeTo(null);dialog.setVisible(true);}});
		}catch(InterruptedException|java.lang.reflect.InvocationTargetException e){choice[0]=0;}
		stop[0]=true;recording[0]=false;
		if(choice[0]!=1)return null;synchronized(out){return out.toByteArray();}
	}

	// ------------------------------------------------------------------ headers

	/** A format 1 'snd ' with a bufferCmd and a standard (8-bit mono) or extended sound header around the samples. */
	static byte[] sndResource(byte[] pcm,int rate,int sampleSize,int channels){
		byte[] header=soundHeader(rate,sampleSize,channels,pcm.length,0x3c);
		byte[] r=new byte[20+header.length+pcm.length];
		putWord(r,0,1);putWord(r,2,1);putWord(r,4,5);putLong(r,6,0);putWord(r,10,1);putWord(r,12,0x8051);putWord(r,14,0);putLong(r,16,20);
		System.arraycopy(header,0,r,20,header.length);System.arraycopy(pcm,0,r,20+header.length,pcm.length);return r;
	}
	/** SoundHeader (22 bytes) for 8-bit mono, else an ExtSoundHeader (64 bytes); numBytes counts sample bytes. */
	static byte[] soundHeader(int rate,int sampleSize,int channels,int numBytes,int baseFrequency){
		int fixedRate=rate<<16;
		if(sampleSize==8&&channels==1){byte[] h=new byte[22];putLong(h,0,0);putLong(h,4,numBytes);putLong(h,8,fixedRate);putLong(h,12,0);putLong(h,16,0);h[20]=0;h[21]=(byte)baseFrequency;return h;}
		byte[] h=new byte[64];int frames=numBytes/Math.max(1,sampleSize/8*channels);
		putLong(h,0,0);putLong(h,4,channels);putLong(h,8,fixedRate);putLong(h,12,0);putLong(h,16,0);h[20]=(byte)0xff;h[21]=(byte)baseFrequency;putLong(h,22,frames);
		double80(h,26,rate); // AIFFSampleRate: 80-bit float
		putLong(h,36,0);putLong(h,40,0);putLong(h,44,0);putWord(h,48,sampleSize);return h;
	}
	/** A minimal AIFF (FORM/COMM/SSND) around PCM samples. */
	static byte[] aiff(byte[] pcm,int rate,int sampleSize,int channels){
		int frames=pcm.length/Math.max(1,sampleSize/8*channels);byte[] a=new byte[54+pcm.length+(pcm.length&1)];
		putLong(a,0,0x464f524d);putLong(a,4,a.length-8);putLong(a,8,0x41494646);
		putLong(a,12,0x434f4d4d);putLong(a,16,18);putWord(a,20,channels);putLong(a,22,frames);putWord(a,26,sampleSize);double80(a,28,rate);
		putLong(a,38,0x53534e44);putLong(a,42,pcm.length+8);putLong(a,46,0);putLong(a,50,0);System.arraycopy(pcm,0,a,54,pcm.length);return a;
	}
	/** The 72-byte AIFF-C header SetupAIFFHeader writes for a compression type other than 'NONE': FVER, COMM with the type and an empty name, SSND (offset and blockSize 0) whose numBytes of data follow. */
	static byte[] aifcHeader(int rate,int sampleSize,int channels,int numBytes,int frames,int compression){
		byte[] a=new byte[72];
		putLong(a,0,0x464f524d);putLong(a,4,72-8+numBytes);putLong(a,8,0x41494643);           // 'FORM' 'AIFC'
		putLong(a,12,0x46564552);putLong(a,16,4);putLong(a,20,0xa2805140);                     // 'FVER' AIFC version 1
		putLong(a,24,0x434f4d4d);putLong(a,28,24);putWord(a,32,channels);putLong(a,34,frames);putWord(a,38,sampleSize);double80(a,40,rate);
		putLong(a,50,compression);                                                              // compressionType, empty compressionName (padded)
		putLong(a,56,0x53534e44);putLong(a,60,numBytes+8);                                      // 'SSND'
		return a;
	}
	/** SetupSndHeader(sndHandle, numChannels, sampleRate, sampleSize, compressionType, baseFrequency, numBytes, VAR headerLen). */
	private int setupSndHeader(int sp) throws M68kCpuException {
		int headerLenPtr=memory.readLong(sp),numBytes=memory.readLong(sp+4),baseFrequency=(short)memory.readWord(sp+8),compression=memory.readLong(sp+10),sampleSize=(short)memory.readWord(sp+14);
		int rate=memory.readLong(sp+16)>>>16,channels=(short)memory.readWord(sp+20),handle=memory.readLong(sp+22);
		if(handle==0||compression!=0x4e4f4e45&&compression!=0)return -50;
		byte[] header=soundHeader(rate,sampleSize,channels,numBytes,baseFrequency);byte[] r=new byte[20+header.length];
		putWord(r,0,1);putWord(r,2,1);putWord(r,4,5);putLong(r,6,0);putWord(r,10,1);putWord(r,12,0x8051);putWord(r,14,0);putLong(r,16,20);System.arraycopy(header,0,r,20,header.length);
		if(macApi.handleSize(handle)<r.length)macApi.resizeHandle(handle,r.length);memory.load(memory.readLong(handle),r);
		if(headerLenPtr!=0)memory.writeWord(headerLenPtr,r.length);return NO_ERR;
	}
	/** SetupAIFFHeader(fRefNum, numChannels, sampleRate, sampleSize, compressionType, numBytes, numFrames): written at the file's mark. */
	private int setupAiffHeader(int sp) throws M68kCpuException {
		int numBytes=memory.readLong(sp+4),compression=memory.readLong(sp+8),sampleSize=(short)memory.readWord(sp+12),rate=memory.readLong(sp+14)>>>16,channels=(short)memory.readWord(sp+18),fRefNum=(short)memory.readWord(sp+20);
		byte[] a;
		if(compression==0x4e4f4e45||compression==0){
			a=aiff(new byte[0],rate,sampleSize,channels);int frames=numBytes/Math.max(1,sampleSize/8*channels);
			putLong(a,4,54+numBytes-8);putLong(a,22,frames);putLong(a,42,numBytes+8);
		}else{
			// any other type makes an AIFF-C file (FVER, COMM with the compression type and an empty name, SSND), as the Sound
			// Manager does - Amadeus II 2.4 writes its scratch file as 16-bit 'twos' and quit at start-up when this said paramErr
			int frames=memory.readLong(sp);if(frames==0)frames=numBytes/Math.max(1,sampleSize/8*channels);
			a=aifcHeader(rate,sampleSize,channels,numBytes,frames,compression);
		}
		try{return macApi.fileManager().writeReference(fRefNum,-1,a)?NO_ERR:-51;}catch(IOException e){return -36;}
	}
	/** ParseAIFFHeader(fRefNum, VAR sndInfo: SoundComponentData, VAR numFrames, VAR dataOffset). */
	private int parseAiffHeader(int sp) throws M68kCpuException {
		int dataOffsetPtr=memory.readLong(sp),numFramesPtr=memory.readLong(sp+4),info=memory.readLong(sp+8),fRefNum=(short)memory.readWord(sp+12);
		byte[] head;try{head=macApi.fileManager().readReference(fRefNum,0,4096);}catch(IOException e){head=null;}
		if(head==null||head.length<12||MacApp68k.u4(head,0)!=0x464f524d)return -50;
		int channels=0,sampleSize=0,frames=0;double rate=0;long dataOffset=-1;int p=12;
		while(p+8<=head.length){int id=MacApp68k.u4(head,p),size=MacApp68k.u4(head,p+4);
			if(id==0x434f4d4d&&p+26<=head.length){channels=MacApp68k.u2(head,p+8);frames=MacApp68k.u4(head,p+10);sampleSize=MacApp68k.u2(head,p+14);rate=readDouble80(head,p+16);}
			else if(id==0x53534e44){dataOffset=p+16+MacApp68k.u4(head,p+8);break;}
			p+=8+size+(size&1);}
		if(dataOffset<0)return -50;
		if(info!=0){memory.writeLong(info,0);memory.writeLong(info+4,sampleSize==16?0x74776f73:0x72617720);memory.writeWord(info+8,channels);memory.writeWord(info+10,sampleSize);memory.writeLong(info+12,(int)Math.round(rate*65536));memory.writeLong(info+16,frames);memory.writeLong(info+20,0);memory.writeLong(info+24,0);}
		if(numFramesPtr!=0)memory.writeLong(numFramesPtr,frames);if(dataOffsetPtr!=0)memory.writeLong(dataOffsetPtr,(int)dataOffset);return NO_ERR;
	}
	/** ParseSndHeader(sndHandle, VAR sndInfo, VAR numFrames, VAR dataOffset). */
	private int parseSndHeader(int sp) throws M68kCpuException {
		int dataOffsetPtr=memory.readLong(sp),numFramesPtr=memory.readLong(sp+4),info=memory.readLong(sp+8),handle=memory.readLong(sp+12);
		byte[] snd=macApi.handleContents(handle);if(snd==null||snd.length<8)return -50;
		int format=MacApp68k.u2(snd,0),commands,count;if(format==1){commands=4+MacApp68k.u2(snd,2)*6+2;count=MacApp68k.u2(snd,commands-2);}else if(format==2){commands=6;count=MacApp68k.u2(snd,4);}else return -50;
		int header=-1;for(int i=0;i<count&&commands+i*8+8<=snd.length;i++){int cmd=MacApp68k.u2(snd,commands+i*8)&0x3fff;if(cmd==80||cmd==81){header=MacApp68k.u4(snd,commands+i*8+4);break;}}
		if(header<0||header+22>snd.length)return -50;
		int encoding=snd[header+20]&0xff,channels=1,sampleSize=8,frames,dataOffset;double rate=Integer.toUnsignedLong(MacApp68k.u4(snd,header+8))/65536.0;
		if(encoding==0xff||encoding==0xfe){if(header+64>snd.length)return -50;channels=MacApp68k.u4(snd,header+4);frames=MacApp68k.u4(snd,header+22);sampleSize=MacApp68k.u2(snd,header+(encoding==0xff?48:62));dataOffset=header+64;}
		else{frames=MacApp68k.u4(snd,header+4);dataOffset=header+22;}
		if(info!=0){memory.writeLong(info,0);memory.writeLong(info+4,sampleSize==16?0x74776f73:0x72617720);memory.writeWord(info+8,channels);memory.writeWord(info+10,sampleSize);memory.writeLong(info+12,(int)Math.round(rate*65536));memory.writeLong(info+16,frames);memory.writeLong(info+20,0);memory.writeLong(info+24,0);}
		if(numFramesPtr!=0)memory.writeLong(numFramesPtr,frames);if(dataOffsetPtr!=0)memory.writeLong(dataOffsetPtr,dataOffset);return NO_ERR;
	}

	private static void putWord(byte[] b,int o,int v){b[o]=(byte)(v>>8);b[o+1]=(byte)v;}
	private static void putLong(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}
	/** IEEE 754 80-bit extended (the AIFF sample rate), from a double. */
	static void double80(byte[] b,int o,double value){
		if(value==0){java.util.Arrays.fill(b,o,o+10,(byte)0);return;}
		int exponent=Math.getExponent(value);long mantissa=Double.doubleToLongBits(value)&0xfffffffffffffL|0x10000000000000L; // 53 bits
		long fraction=mantissa<<11; // 64-bit with the integer bit at the top
		putWord(b,o,(exponent+16383)|(value<0?0x8000:0));putLong(b,o+2,(int)(fraction>>>32));putLong(b,o+6,(int)fraction);
	}
	static double readDouble80(byte[] b,int o){
		int exponent=MacApp68k.u2(b,o);long high=MacApp68k.u4(b,o+2)&0xffffffffL,low=MacApp68k.u4(b,o+6)&0xffffffffL;
		boolean negative=(exponent&0x8000)!=0;exponent&=0x7fff;if(exponent==0&&high==0&&low==0)return 0;
		double fraction=(high*4294967296.0+low)/Math.pow(2,63);double value=fraction*Math.pow(2,exponent-16383);return negative?-value:value;
	}
}

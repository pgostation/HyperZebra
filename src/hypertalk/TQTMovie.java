import java.awt.image.BufferedImage;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

//QTMovie XCMDの疑似再生。
//
//classic Mac OSの「自己完結型ムービー」は、データフォークに'mdat'（実サンプル）だけを持ち、
//ムービーヘッダ本体('moov'アトム。トラック/サンプルテーブルなど)はリソースフォーク側に
//入っていることがある。
//このクラスは、
//  1) データフォークとリソースフォーク内の'moov'を1つのファイルに再構成し、
//  2) ffmpegが使えれば、それでフレーム画像(qtrle等)や音声を抽出し、
//  3) QTMovie Direct,<handle>,Idle が呼ばれるたびに経過時間から再生位置を計算して
//     コマ送り表示する
//ことで、実際に映像として再生する。QuickTime Musicトラック'musi'はQTMAイベントを
//Java MIDIシーケンスへ変換し、内蔵シンセサイザーで再生する。それ以外の形式で
//ffmpegもデコードできない場合は、従来通りスクリプトを止めないためのハンドルだけを返す。
public class TQTMovie {

	private static Boolean ffmpegAvailable;

	/** -Dhz68k.xcmdmovie=native: only HyperZebra's own decoders (no installed QuickTime, no ffmpeg). */
	static final boolean NATIVE_ONLY="native".equalsIgnoreCase(System.getProperty("hz68k.xcmdmovie",""));

	private static boolean isFfmpegAvailable(){
		if(NATIVE_ONLY)return false;
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

	//変換済みムービーのアセット（フレーム画像・フレームレート・音声）。
	//同じファイルを何度開いても変換し直さずに済むよう、ファイルパスをキーにキャッシュする。
	static class MovieAsset {
		BufferedImage[] frames; //空なら映像なし
		double fps;
		File audioFile; //nullなら音声なし（ffmpegで取り出した音声）
		QuickTimeSound.Pcm sound; //自前でデコードした音声（あればaudioFileより優先）
		Mp3Decoder.Stream mp3; //MP3ファイル(MPEG audio Layer III): 再生しながら自前でデコードする
		boolean hasAudio(){return sound!=null||audioFile!=null||mp3!=null;}
		QuickTimeVideo video; //自前でデコードする映像（あればframesの代わり）
		boolean hasVideo(){return video!=null||(frames!=null&&frames.length>0);}
		int frameWidth(){return video!=null?video.width():frames[0].getWidth();}
		int frameHeight(){return video!=null?video.height():frames[0].getHeight();}
		/** Frames (video samples of the first video track, or the converted frames). */
		int frameCount(){return video!=null?video.frameCount():frames==null?0:frames.length;}
		/** Frames a second: the converted rate, or the video's average. */
		double frameRate(){
			if(video!=null){long ms=video.durationMillis();return ms>0?Math.max(1,video.frameCount())*1000.0/ms:10.0;}
			return fps>0?fps:10.0;
		}
		/** The picture at {@code ms} milliseconds into the movie (the same image while the frame does not change). */
		BufferedImage frameAtMillis(long ms){
			if(video!=null){synchronized(video){try{return video.frameAt(ms*video.timeScale()/1000L);}catch(IOException e){System.err.println("QTMovie: "+e.getMessage());return video.lastFrame();}}}
			if(frames==null||frames.length==0)return null;
			return frames[(int)Math.max(0,Math.min(frames.length-1,(long)(ms/1000.0*frameRate())))];
		}
		/** The movie's sound as a stream: our own decoding, else ffmpeg's extraction. */
		AudioInputStream audioStream() throws Exception {return sound!=null?sound.stream():mp3!=null?mp3.pcm(0):AudioSystem.getAudioInputStream(audioFile);}
		Sequence musicSequence; //QuickTime Music Architecture ('musi')
		int timeScale; //ムービーヘッダ('mvhd')の時間スケール(0なら不明)
		int duration;  //同じくtimeScale単位の長さ
		boolean converted; //ffmpegでの変換を試みたか（結果が空でも再試行しない）
	}
	private static final HashMap<String,MovieAsset> assetCache = new HashMap<String,MovieAsset>();

	//68K Movie Toolbox (QuickTime68k)から使う：変換済みアセットをキャッシュ経由で取得する。
	static synchronized MovieAsset assetFor(File movieFile){
		MovieAsset asset = assetCache.get(movieFile.getAbsolutePath());
		if(asset==null){ asset = convert(movieFile); assetCache.put(movieFile.getAbsolutePath(), asset); }
		return asset;
	}

	//再生中のムービー1つ分の状態。
	private static class Playback {
		MovieAsset asset;
		boolean loop;
		long startTime;
		int lastFrame = -1;
		GPictWindow window;
		OWindow scriptWindow;
		Clip audioClip;
		StreamingSound audioStream; // an MP3, decoded while it plays (a Clip would hold the whole song)
		Sequencer musicSequencer;
		Synthesizer musicSynthesizer;
		int volume=256;
		String logPrefix;
		Timer idleTimer;
		volatile boolean finished;
		volatile boolean playing;
		QuickTimeService.GuestMovie guest; // playing through the installed QuickTime (service VM) instead of converted frames
		File file; // the movie file (the movie property of the window)
		long positionMs; // where a paused movie stands (converted movies; the guest keeps its own time)
		String name;
		int id;
		boolean direct; // QTMovie OpenMovie,direct: drawn straight onto the card (no window frame), clicks belong to the card
	}
	private static int movieIdSeq = 0;
	/** The default time scale of a QuickTime movie (units per second) when the file does not say. */
	static final int DEFAULT_TIME_SCALE = 600;

	/** Whether movies can be played at all: the installed QuickTime (service VM), ffmpeg, or the own decoders when they are
	 *  asked for (-Dhz68k.xcmdmovie=native; they cover sound and music, not video yet). the version of QuickTime is 0 otherwise. */
	static boolean available(){
		return QuickTimeService.wanted() || isFfmpegAvailable() || NATIVE_ONLY;
	}
	private static final HashMap<String,Playback> active = new HashMap<String,Playback>();
	private static int handleSeq = 0;

	//----------------------------------------------------------
	//QTMovie OpenMovie,<mode>,"<名前>","<左,上>"[,loop]
	//----------------------------------------------------------
	public static String openMovie(String name, int x, int y, boolean loop){
		return openMovie(name,x,y,loop,null);
	}

	/** {@code mode} "direct": the XCMD draws the movie straight into the card window at card position x,y, so it has no
	 *  window frame or title bar and clicks on it are clicks on the card (Hyper Lemon closes its movies in mouseUp). */
	public static String openMovie(String name, int x, int y, boolean loop, String mode){
		File movieFile = findMovieFile(name);
		if(movieFile==null){
			System.out.println("QTMovie: \""+name+"\"が見つかりません");
			return newHandle();
		}
		boolean direct=mode!=null && mode.trim().equalsIgnoreCase("direct");
		if(!direct)return openMovieFile(movieFile,name,x,y,loop,false);
		String handle=openMovieFile(movieFile,name,x,y,loop,false,"rect",true);
		Playback pb=active.get(handle);
		if(pb!=null){pb.direct=true;if(pb.window!=null)forwardClicksToCard(pb.window);}
		return handle;
	}

	/** A direct movie's clicks go to the card underneath it, as when the XCMD draws into the card window. */
	private static void forwardClicksToCard(final GPictWindow window){
		MouseAdapter forward=new MouseAdapter(){
			@Override public void mousePressed(MouseEvent event){redispatch(event);}
			@Override public void mouseReleased(MouseEvent event){redispatch(event);}
			@Override public void mouseClicked(MouseEvent event){redispatch(event);}
			@Override public void mouseDragged(MouseEvent event){redispatch(event);}
			private void redispatch(MouseEvent event){redispatchToWindow(event,PCARD.pc);}
		};
		window.label.addMouseListener(forward);
		window.label.addMouseMotionListener(forward);
	}

	/** Hands a click on a direct movie to the card window at the same screen point. It goes to the window itself, whose lightweight
	 *  dispatcher picks the part under the point as for a real click (a button or field with listeners, else the window, where GUI
	 *  sends mouseDown/mouseUp to the card). Dispatching to the deepest component lost the click on the card picture's label,
	 *  which has no mouse listener (Hyper Lemon's movies did not close). */
	static void redispatchToWindow(MouseEvent event,java.awt.Window window){
		if(window==null || !window.isShowing())return;
		java.awt.Point screen=event.getLocationOnScreen();
		java.awt.Point p=new java.awt.Point(screen);
		SwingUtilities.convertPointFromScreen(p,window);
		window.dispatchEvent(new MouseEvent(window,event.getID(),event.getWhen(),event.getModifiersEx(),p.x,p.y,
			screen.x,screen.y,event.getClickCount(),event.isPopupTrigger(),event.getButton()));
	}

	/** Opens a movie already resolved by another XCMD (notably Movie XCMD). */
	public static String openMovieFile(File movieFile,String name,int x,int y,boolean loop,
			boolean automaticIdle){
		return openMovieFile(movieFile,name,x,y,loop,automaticIdle,"windoid",true);
	}

	/** Movie XCMD / open movie: {@code style} is the window style parameter (rect, borderless, shadow, dialog, plain, windoid, tallWindoid, zoom;
	 *  x,y &lt; 0 means "let the window style place it": centred on the card window), {@code visible} the visible parameter. */
	public static String openMovieFile(File movieFile,String name,int x,int y,boolean loop,
			boolean automaticIdle,String style,boolean visible){
		String handle=newHandle();
		String logPrefix=automaticIdle?"Movie XCMD":"QTMovie";
		if(movieFile==null || !movieFile.isFile()) {
			System.err.println(logPrefix+": open rejected because the file is missing");
			return handle;
		}
		if(!Sandbox.allowFile(movieFile, Sandbox.Access.READ, Sandbox.scriptRequester())) {
			System.err.println(logPrefix+": open rejected: access not allowed");
			return handle;
		}
		if(name==null || name.isEmpty()) name=movieFile.getName();
		System.out.println(logPrefix+": opening "+movieFile.getAbsolutePath()+" loop="+loop);

		if(QuickTimeService.wanted()){
			// The installed QuickTime plays the movie itself (real codecs, Sound Manager, music synthesizer); ffmpeg only when it cannot open the file.
			QuickTimeService service=QuickTimeService.get();
			QuickTimeService.GuestMovie guest=service==null?null:service.open(movieFile,loop,!automaticIdle);
			if(guest!=null)return openGuest(guest,handle,name,x,y,loop,automaticIdle,logPrefix,style,visible);
			System.out.println(logPrefix+": the installed QuickTime could not open the movie; converting with ffmpeg instead");
		}

		MovieAsset asset = assetCache.get(movieFile.getAbsolutePath());
		if(asset==null){
			System.out.println(logPrefix+": asset cache miss; converting movie");
			asset = convert(movieFile);
			assetCache.put(movieFile.getAbsolutePath(), asset);
		}else System.out.println(logPrefix+": asset cache hit");
		System.out.println(logPrefix+": asset frames="+asset.frameCount()+(asset.video!=null?" (own decoder)":"")+
			" fps="+asset.fps+" audio="+(asset.sound!=null?"own decoder "+asset.sound.channels+"ch "+asset.sound.rate+"Hz "+asset.sound.millis()+"ms":asset.audioFile==null?"none":asset.audioFile.getAbsolutePath())+
			" midi="+describeSequence(asset.musicSequence));

		Playback pb = new Playback();
		pb.asset = asset;
		pb.loop = loop;
		pb.logPrefix=logPrefix;
		pb.startTime = System.currentTimeMillis();
		pb.playing=!automaticIdle;
		pb.file=movieFile;
		pb.name=name;
		pb.id=++movieIdSeq;

		if(asset.hasVideo()){
			pb.window = new GPictWindow(PCARD.pc, name, asset.frameAtMillis(0), windowStyle(style), false);
			placeWindow(pb.window,x,y);
			addMovieMouseListener(pb,handle);
			pb.window.setVisible(visible);
		}
		if(asset.mp3!=null){
			pb.audioStream = StreamingSound.of(asset.mp3);
			pb.audioStream.setLoop(loop);
			if(!automaticIdle) pb.audioStream.start();
			System.out.println(logPrefix+": MP3 audio prepared (decoded while it plays)");
		}else if(asset.hasAudio()){
			try {
				AudioInputStream ais = HostSampleRate.forHost(asset.audioStream()); // a plain rate: an 11127 Hz track broke the host's audio after a minute
				Clip clip = AudioSystem.getClip();
				clip.open(ais);
				if(!automaticIdle){if(loop)clip.loop(Clip.LOOP_CONTINUOUSLY);else clip.start();}
				pb.audioClip = clip;
				System.out.println(logPrefix+": PCM audio prepared, running="+clip.isRunning());
			} catch (Exception e) {
				System.err.println(logPrefix+": PCM audio start failed: "+e.getClass().getName()+": "+e.getMessage());
			}
		}
		if(!asset.hasAudio() && asset.musicSequence!=null){
			try {
				startMusic(pb,logPrefix,!automaticIdle);
			} catch (Exception e) {
				System.err.println(logPrefix+": MIDI start failed: "+e.getClass().getName()+": "+e.getMessage());
			}
		}

		if(!asset.hasVideo() && !asset.hasAudio() && asset.musicSequence==null){
			System.out.println("QTMovie: \""+name+"\"は現在のところ再生に対応していない形式です"
				+"（ffmpegでもデコードできないコーデックの可能性があります）");
		}

		active.put(handle, pb);
		if(automaticIdle){registerScriptWindow(pb,handle);}
		if(automaticIdle && pb.window!=null && pb.asset.hasVideo()){
			pb.idleTimer=new Timer(15,event -> idle(handle));
		}
		return handle;
	}

	/** window "name" / movie N of the scripts: the Movie XCMD's windows are HyperTalk objects, the QTMovie XCMD's are not. */
	private static void registerScriptWindow(Playback pb,String handle){
		pb.scriptWindow=new OWindow(pb.name,handle);
		pb.scriptWindow.gpw=pb.window; // loc/rect/visible of the window come from the picture window
		pb.scriptWindow.id=pb.id;
		System.out.println(pb.logPrefix+": registered window name=\""+pb.name+"\" id="+pb.id);
		if(PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null)
			TTalk.CallMessage("openMovie",new String[]{pb.name,Integer.toString(pb.id)},PCARD.pc.stack.curCard);
	}

	/** The Movie XCMD window styles as GPictWindow knows them: rect/borderless/shadow have no frame, dialog/plain a drawn one, the rest a title bar. */
	private static String windowStyle(String style){
		if(style==null)return "windoid";
		String s=style.trim().toLowerCase(java.util.Locale.ROOT);
		if(s.equals("borderless"))return "rect";
		if(s.equals("rect")||s.equals("shadow")||s.equals("dialog")||s.equals("plain"))return s;
		return "windoid";
	}

	/** Puts the movie window at card-window coordinates x,y; negative coordinates keep GPictWindow's centred placement. */
	private static void placeWindow(GPictWindow window,int x,int y){
		if(x<0||y<0)return;
		java.awt.Point origin=TUtil.windowOrigin();
		window.setLocation(origin.x+PCARD.toPhysical(x), origin.y+PCARD.toPhysical(y));
	}

	/** Clicks in the movie: a finished one-shot movie closes (the old behaviour); the Movie XCMD also tells the card (mouseDownInMovie / mouseUpInMovie). */
	private static void addMovieMouseListener(final Playback pb,final String handle){
		pb.window.label.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent event) { movieMouseMessage(pb,"mouseDownInMovie",event); }
			@Override
			public void mouseReleased(MouseEvent event) { movieMouseMessage(pb,"mouseUpInMovie",event); }
			@Override
			public void mouseClicked(MouseEvent event) {
				if(!pb.direct && movieCanCloseOnClick(pb.loop, pb.finished)) dispose(handle);
			}
		});
	}

	private static void movieMouseMessage(Playback pb,String message,MouseEvent event){
		if(pb.scriptWindow==null || PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return;
		TTalk.CallMessage(message,new String[]{pb.name,Integer.toString(pb.id),PCARD.toLogical(event.getX())+","+PCARD.toLogical(event.getY()),Long.toString(currentTime(pb))},PCARD.pc.stack.curCard);
	}

	/** A movie opened in the QuickTime service: its window shows the frames the service copies out of the movie's GWorld. */
	private static String openGuest(final QuickTimeService.GuestMovie guest,final String handle,String name,int x,int y,boolean loop,boolean automaticIdle,String logPrefix){
		return openGuest(guest,handle,name,x,y,loop,automaticIdle,logPrefix,"windoid",true);
	}

	private static String openGuest(final QuickTimeService.GuestMovie guest,final String handle,String name,int x,int y,boolean loop,boolean automaticIdle,String logPrefix,String style,boolean visible){
		final Playback pb=new Playback();
		pb.guest=guest;pb.loop=loop;pb.logPrefix=logPrefix;pb.startTime=System.currentTimeMillis();pb.playing=!automaticIdle;
		pb.file=guest.file;pb.name=name;pb.id=++movieIdSeq;
		MovieAsset asset=new MovieAsset();asset.converted=true;asset.timeScale=guest.timeScale;asset.duration=(int)Math.min(Integer.MAX_VALUE,guest.durationUnits);pb.asset=asset;
		System.out.println(logPrefix+": QuickTime service plays it: "+guest.width+"x"+guest.height+" duration="+guest.durationMillis()+"ms");
		if(guest.hasVideo()){
			BufferedImage first=guest.frame();
			if(first==null){first=new BufferedImage(Math.max(1,guest.width),Math.max(1,guest.height),BufferedImage.TYPE_INT_RGB);}
			pb.window=new GPictWindow(PCARD.pc,name,first,windowStyle(style),false);
			placeWindow(pb.window,x,y);
			addMovieMouseListener(pb,handle);
			pb.window.setVisible(visible);
		}
		active.put(handle,pb);
		if(automaticIdle){registerScriptWindow(pb,handle);}
		pb.idleTimer=new Timer(15,event -> idle(handle));
		if(!automaticIdle)pb.idleTimer.start(); // QTMovie stacks call Idle themselves, but the frames should move even when they do not
		return handle;
	}

	/** Explicitly connects Java's sequencer to a synthesizer for reliable MIDI output. */
	private static void startMusic(Playback pb,String logPrefix,boolean startImmediately) throws Exception {
		Synthesizer synth=null;
		Sequencer sequencer=null;
		try {
			synth=MidiSystem.getSynthesizer();synth.open();
			sequencer=MidiSystem.getSequencer(false);sequencer.open();
			sequencer.getTransmitter().setReceiver(synth.getReceiver());
			System.out.println(logPrefix+": MIDI output connected explicitly: sequencer="+
				sequencer.getDeviceInfo().getName()+" synthesizer="+synth.getDeviceInfo().getName());
		} catch(Exception directFailure) {
			System.err.println(logPrefix+": explicit MIDI connection failed: "+directFailure.getClass().getName()+": "+directFailure.getMessage());
			if(sequencer!=null)sequencer.close();
			if(synth!=null)synth.close();
			// Some Java distributions only expose a pre-wired default sequencer.
			sequencer=MidiSystem.getSequencer();sequencer.open();
			synth=null;
			System.out.println(logPrefix+": using default MIDI sequencer="+sequencer.getDeviceInfo().getName());
		}
		sequencer.setSequence(pb.asset.musicSequence);
		sequencer.addMetaEventListener(message -> {
			if(message.getType()==0x2F && !pb.loop) markFinished(pb);
		});
		if(pb.loop)sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
		if(startImmediately)sequencer.start();
		pb.musicSequencer=sequencer;
		pb.musicSynthesizer=synth;
		applyMusicVolume(pb);
		System.out.println(logPrefix+": MIDI prepared, running="+sequencer.isRunning()+
			" tickLength="+sequencer.getTickLength()+" microsecondLength="+sequencer.getMicrosecondLength());
	}

	static void play(String handle) {
		Playback pb=active.get(handle);if(pb==null){System.err.println("Movie XCMD: Play ignored for unknown handle="+handle);return;}
		pb.finished=false;pb.playing=true;pb.startTime=System.currentTimeMillis()-pb.positionMs;pb.positionMs=0;
		if(pb.guest!=null){pb.guest.play();if(pb.idleTimer!=null)pb.idleTimer.start();System.out.println(pb.logPrefix+": Play handle="+handle+" (QuickTime service)");return;}
		if(pb.audioClip!=null){if(pb.audioClip.getMicrosecondPosition()>=pb.audioClip.getMicrosecondLength())pb.audioClip.setMicrosecondPosition(0);
			if(pb.loop)pb.audioClip.loop(Clip.LOOP_CONTINUOUSLY);else pb.audioClip.start();}
		if(pb.audioStream!=null){if(pb.audioStream.getMicrosecondPosition()>=pb.audioStream.getMicrosecondLength())pb.audioStream.setMicrosecondPosition(0);
			pb.audioStream.setLoop(pb.loop);pb.audioStream.start();}
		if(pb.musicSequencer!=null){if(pb.musicSequencer.getTickPosition()>=pb.musicSequencer.getTickLength())pb.musicSequencer.setTickPosition(0);pb.musicSequencer.start();}
		if(pb.idleTimer!=null)pb.idleTimer.start();
		System.out.println(pb.logPrefix+": Play handle="+handle+" midiRunning="+
			(pb.musicSequencer!=null&&pb.musicSequencer.isRunning()));
	}

	/** set the currTime: {@code time} is in the movie's time units (the timeScale per second); the movie stops there. */
	static void setCurrentTime(String handle,long time) {
		Playback pb=active.get(handle);if(pb==null){System.err.println("Movie XCMD: currTime ignored for unknown handle="+handle);return;}
		long position=Math.max(0,Math.min(time,duration(pb)));
		if(pb.guest!=null){pb.guest.setTimeUnits(position);pb.playing=false;pb.finished=false;if(pb.idleTimer!=null)pb.idleTimer.stop();
			final BufferedImage f=pb.guest.frame();if(f!=null&&pb.window!=null){pb.window.label.bi=f;pb.window.label.repaint();}
			System.out.println(pb.logPrefix+": currTime="+time+" handle="+handle+" (QuickTime service)");return;}
		long ms=position*1000L/timeScale(pb);
		if(pb.audioClip!=null){pb.audioClip.stop();pb.audioClip.setMicrosecondPosition(Math.min(ms*1000L,pb.audioClip.getMicrosecondLength()));}
		if(pb.audioStream!=null){pb.audioStream.stop();pb.audioStream.setMicrosecondPosition(ms*1000L);}
		if(pb.musicSequencer!=null){pb.musicSequencer.stop();pb.musicSequencer.setMicrosecondPosition(Math.min(ms*1000L,pb.musicSequencer.getMicrosecondLength()));}
		pb.playing=false;pb.finished=false;pb.lastFrame=-1;pb.positionMs=ms;pb.startTime=System.currentTimeMillis()-ms;
		if(pb.idleTimer!=null)pb.idleTimer.stop();
		showFrameAt(pb,ms);
		System.out.println(pb.logPrefix+": currTime="+time+" handle="+handle+" (stopped and repositioned)");
	}

	/** pause: the movie stops where it is (play resumes there). */
	static void pause(String handle) {
		Playback pb=active.get(handle);if(pb==null)return;
		if(pb.guest!=null){pb.guest.stop();pb.playing=false;if(pb.idleTimer!=null)pb.idleTimer.stop();return;}
		if(pb.playing){pb.positionMs=Math.max(0,Math.min(System.currentTimeMillis()-pb.startTime,durationMillis(pb)));}
		pb.playing=false;
		if(pb.audioClip!=null)pb.audioClip.stop();
		if(pb.audioStream!=null)pb.audioStream.stop();
		if(pb.musicSequencer!=null)pb.musicSequencer.stop();
		if(pb.idleTimer!=null)pb.idleTimer.stop();
	}

	/** rewind: back to the start, paused. */
	static void rewind(String handle) { setCurrentTime(handle,0); }

	/** stepFwd / stepRev: one frame on from where the movie stands, paused there. */
	static void step(String handle,int frames) {
		Playback pb=active.get(handle);if(pb==null)return;
		pause(handle);
		double fps=pb.asset!=null?pb.asset.frameRate():10.0;
		long units=Math.round(timeScale(pb)/fps)*frames;
		setCurrentTime(handle,currentTime(pb)+units);
	}

	private static void showFrameAt(Playback pb,long ms){
		if(pb.asset==null||!pb.asset.hasVideo()||pb.window==null)return;
		BufferedImage image=pb.asset.frameAtMillis(ms);if(image==null)return;
		pb.window.label.bi=image;pb.window.label.repaint();
	}

	/** the rate: 1 while playing, 0 when stopped (the Movie XCMD's default rate is 1). */
	static int rate(String handle) {
		Playback pb=active.get(handle);if(pb!=null&&pb.guest!=null)return pb.guest.playing?1:0;return pb!=null&&pb.playing?1:0;
	}

	/** set the rate: 0 pauses, anything else plays. */
	static void setRate(String handle,double rate) { if(rate==0)pause(handle);else play(handle); }

	static int timeScale(Playback pb){
		if(pb.guest!=null&&pb.guest.timeScale>0)return pb.guest.timeScale;
		return pb.asset!=null&&pb.asset.timeScale>0?pb.asset.timeScale:DEFAULT_TIME_SCALE;
	}
	static long durationMillis(Playback pb){
		if(pb.guest!=null)return pb.guest.durationMillis();
		if(pb.asset==null)return 0;
		if(pb.asset.timeScale>0&&pb.asset.duration>0)return pb.asset.duration*1000L/pb.asset.timeScale;
		if(pb.asset.video!=null)return pb.asset.video.durationMillis();
		if(pb.asset.frames!=null&&pb.asset.frames.length>0)return (long)(pb.asset.frames.length*1000.0/(pb.asset.fps>0?pb.asset.fps:10.0));
		if(pb.audioClip!=null)return pb.audioClip.getMicrosecondLength()/1000L;
		if(pb.audioStream!=null)return pb.audioStream.getMicrosecondLength()/1000L;
		if(pb.musicSequencer!=null)return pb.musicSequencer.getMicrosecondLength()/1000L;
		return 0;
	}
	static long duration(Playback pb){ return durationMillis(pb)*timeScale(pb)/1000L; }
	static long currentTime(Playback pb){
		if(pb.guest!=null)return pb.guest.timeMillis()*timeScale(pb)/1000L;
		long ms=pb.playing?System.currentTimeMillis()-pb.startTime:pb.positionMs;
		if(pb.finished)ms=durationMillis(pb);
		return Math.max(0,Math.min(ms,durationMillis(pb)))*timeScale(pb)/1000L;
	}

	/** the currTime / duration / timeScale / startTime / endTime / loop / soundVolume / movie of a movie window; null when the property is not one of these. */
	static String property(String handle,String property){
		Playback pb=active.get(handle);if(pb==null)return null;
		switch(property){
			case "currtime": return Long.toString(currentTime(pb));
			case "duration": case "endtime": return Long.toString(duration(pb));
			case "starttime": return "0";
			case "timescale": return Integer.toString(timeScale(pb));
			case "loop": return Boolean.toString(pb.loop);
			case "loopispalindrome": return "false";
			case "soundvolume": case "volume": return Integer.toString(pb.volume);
			case "movie": return pb.file==null?"":pb.file.getAbsolutePath();
		}
		return null;
	}

	static void setLoop(String handle,boolean loop){
		Playback pb=active.get(handle);if(pb==null)return;
		pb.loop=loop;
		if(pb.musicSequencer!=null)pb.musicSequencer.setLoopCount(loop?Sequencer.LOOP_CONTINUOUSLY:0);
		if(pb.audioClip!=null&&pb.playing){if(loop)pb.audioClip.loop(Clip.LOOP_CONTINUOUSLY);else{pb.audioClip.loop(0);pb.audioClip.start();}}
		if(pb.audioStream!=null)pb.audioStream.setLoop(loop);
	}

	/** The movie windows of the scripts (Movie XCMD / open movie) in the order they were opened: movie 1, movie 2 … */
	static ArrayList<OWindow> scriptWindows(){
		ArrayList<OWindow> list=new ArrayList<OWindow>();
		for(OWindow w:OWindow.list)if(w.movieHandle!=null&&active.containsKey(w.movieHandle))list.add(w);
		return list;
	}

	/** movie "name": the window name, else the file name / path of the movie in the window (HyperCard 2.4). */
	static OWindow findScriptWindow(String ref){
		if(ref==null)return null;
		for(OWindow w:scriptWindows())if(ref.equalsIgnoreCase(w.name))return w;
		for(OWindow w:scriptWindows()){
			Playback pb=active.get(w.movieHandle);
			if(pb==null||pb.file==null)continue;
			String path=pb.file.getAbsolutePath();
			if(ref.equalsIgnoreCase(pb.file.getName())||ref.equalsIgnoreCase(path)||TTalk.convertFromMacPath(ref).equalsIgnoreCase(path))return w;
		}
		return null;
	}

	static void setVolume(String handle,int volume) {
		Playback pb=active.get(handle);if(pb==null){System.err.println("Movie XCMD: volume ignored for unknown handle="+handle);return;}
		pb.volume=Math.max(0,Math.min(256,volume));
		if(pb.guest!=null){pb.guest.setVolume(pb.volume);return;}
		applyMusicVolume(pb);
		System.out.println(pb.logPrefix+": volume="+pb.volume+" midiVolume="+midiVolume(pb.volume));
	}

	private static String describeSequence(Sequence sequence) {
		if(sequence==null)return "none";
		int notes=0;
		for(javax.sound.midi.Track track:sequence.getTracks())for(int i=0;i<track.size();i++){
			javax.sound.midi.MidiMessage message=track.get(i).getMessage();
			if(message instanceof javax.sound.midi.ShortMessage){javax.sound.midi.ShortMessage shortMessage=(javax.sound.midi.ShortMessage)message;
				if(shortMessage.getCommand()==javax.sound.midi.ShortMessage.NOTE_ON&&shortMessage.getData2()>0)notes++;}
		}
		return "tracks="+sequence.getTracks().length+", ticks="+sequence.getTickLength()+", notes="+notes;
	}

	static int midiVolume(int volume) {return Math.max(0,Math.min(127,(volume*127+128)/256));}

	private static void applyMusicVolume(Playback pb) {
		if(pb.musicSynthesizer==null)return;
		int value=midiVolume(pb.volume);
		for(javax.sound.midi.MidiChannel channel:pb.musicSynthesizer.getChannels())
			if(channel!=null)channel.controlChange(7,value);
	}

	private static String newHandle(){return "qtmovie"+(++handleSeq);}

	//----------------------------------------------------------
	//QTMovie Direct,<handle>,Idle
	//----------------------------------------------------------
	public static void idle(String handle){
		Playback pb = active.get(handle);
		if(pb==null)return;
		if(pb.guest!=null){
			BufferedImage f=pb.guest.frame();
			if(f!=null&&pb.window!=null&&f!=pb.window.label.bi){pb.window.label.bi=f;pb.window.label.repaint();}
			if(pb.playing&&pb.guest.done&&!pb.loop)markFinished(pb);
			return;
		}
		if(!pb.playing || !pb.asset.hasVideo() || pb.window==null) return;

		long elapsedMs = System.currentTimeMillis()-pb.startTime;
		long length = durationMillis(pb);
		if(length>0 && elapsedMs>=length){
			if(pb.loop){
				pb.startTime = System.currentTimeMillis();
				elapsedMs = 0;
			}else{
				elapsedMs = length-1;
				markFinished(pb);
			}
		}
		BufferedImage image = pb.asset.frameAtMillis(elapsedMs);
		if(image!=null && image!=pb.window.label.bi){
			pb.window.label.bi = image;
			pb.window.label.repaint();
		}
	}

	static boolean movieCanCloseOnClick(boolean loop, boolean finished) {
		return !loop && finished;
	}

	private static void markFinished(Playback pb) {
		if(pb.finished) return;
		pb.finished = true;
		pb.playing=false;
		if(pb.idleTimer!=null)pb.idleTimer.stop();
		SwingUtilities.invokeLater(() -> {
			if(pb.window==null || !pb.window.isDisplayable()) return;
			pb.window.label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			pb.window.label.setToolTipText(International.t("Click to close"));
		});
	}

	/** Closes every movie (with its sound and window): the stack that opened them closed back to the launcher. */
	static void disposeAll(){
		ArrayList<String> handles = new ArrayList<String>(active.keySet());
		for(String h: handles){
			try{ dispose(h); }catch(RuntimeException e){ System.err.println("Movie playback: dispose "+h+" at stack close: "+e); }
		}
	}

	//----------------------------------------------------------
	//QTMovie Direct,<handle>,Dispose
	//----------------------------------------------------------
	public static void dispose(String handle){
		Playback pb = active.remove(handle);
		if(pb==null) {System.err.println("Movie playback: dispose ignored for unknown handle="+handle);return;}
		System.out.println(pb.logPrefix+": disposing handle="+handle+" midiPosition="+
			(pb.musicSequencer==null?"none":pb.musicSequencer.getTickPosition()));
		if(pb.guest!=null)pb.guest.dispose();
		if(pb.audioClip!=null){
			pb.audioClip.stop();
			pb.audioClip.close();
		}
		if(pb.audioStream!=null)pb.audioStream.close();
		if(pb.musicSequencer!=null){
			pb.musicSequencer.stop();
			pb.musicSequencer.close();
		}
		if(pb.musicSynthesizer!=null)pb.musicSynthesizer.close();
		if(pb.idleTimer!=null)pb.idleTimer.stop();
		if(pb.window!=null){
			pb.window.dispose();
		}
		if(pb.scriptWindow!=null){
			pb.scriptWindow.removeMovieRegistration();
			if(PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null)
				TTalk.CallMessage("closeMovie",new String[]{pb.name,Integer.toString(pb.id)},PCARD.pc.stack.curCard);
		}
	}

	//----------------------------------------------------------
	//ファイル探索（.hzbフォルダ、またはその親フォルダ＝変換前スタックと同じ場所）
	//----------------------------------------------------------
	private static File findMovieFile(String name){
		if(name==null || name.isEmpty() || PCARD.pc.stack==null || PCARD.pc.stack.file==null) return null;
		File hzbDir = PCARD.pc.stack.file.getParentFile();
		if(hzbDir==null) return null;
		File[] candidateDirs = { hzbDir, StackBundle.folderAround(PCARD.pc.stack.file) };
		for(File dir : candidateDirs){
			if(dir==null || !dir.isDirectory()) continue;
			File[] files = dir.listFiles();
			if(files==null) continue;
			for(File f : files){
				if(f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
			}
		}
		return null;
	}

	/** The file's data begins with a Standard MIDI File header ("MThd", length 6). */
	static boolean isStandardMidiFile(File file){
		if(file==null || !file.isFile() || file.length()<14) return false;
		try(java.io.InputStream in = new java.io.FileInputStream(file)){
			byte[] head = in.readNBytes(8);
			return head.length==8 && head[0]=='M' && head[1]=='T' && head[2]=='h' && head[3]=='d' && head[4]==0 && head[5]==0 && head[6]==0 && head[7]==6;
		}catch(IOException e){ return false; }
	}

	//----------------------------------------------------------
	//変換本体
	//----------------------------------------------------------
	private static MovieAsset convert(File movieFile){
		MovieAsset asset = new MovieAsset();
		asset.converted = true;
		//a Standard MIDI File opens as a music-only movie, as QuickTime's MIDI import does (BladeStory -BlackHeart-'s MIDI XFCN
		//converts its .mid files with ConvertFileToMovieFile and plays the result)
		if(isStandardMidiFile(movieFile)){
			try {
				asset.musicSequence = MidiSystem.getSequence(movieFile);
				asset.timeScale = DEFAULT_TIME_SCALE;
				asset.duration = (int)Math.min(Integer.MAX_VALUE, asset.musicSequence.getMicrosecondLength()*DEFAULT_TIME_SCALE/1000000L);
				System.out.println("QTMovie converter: Standard MIDI File: "+describeSequence(asset.musicSequence));
				return asset;
			} catch (Exception e) {
				System.out.println("QTMovie converter: not read as a MIDI file: "+e.getMessage());
				asset.musicSequence = null;
			}
		}
		//MP3ファイルは中身で見分ける(QuickTime 4のMP3読み込みと同じく、音声トラックだけのムービーとして開く)。
		//時間スケールはQuickTimeが読み込んだムービーの既定値600
		if(Mp3Decoder.looksLikeMp3(movieFile)){
			try {
				asset.mp3 = Mp3Decoder.scan(movieFile);
				asset.timeScale = DEFAULT_TIME_SCALE;
				asset.duration = (int)Math.min(Integer.MAX_VALUE, asset.mp3.totalSamples()*DEFAULT_TIME_SCALE/asset.mp3.sampleRate());
				System.out.println("QTMovie converter: MP3 decoded natively: "+asset.mp3.channels()+"ch "+asset.mp3.sampleRate()+"Hz "+asset.mp3.millis()+"ms");
				return asset;
			} catch (IOException e) {
				System.out.println("QTMovie converter: not read as MP3: "+e.getMessage());
			}
		}
		QuickTimeFile movie = null;
		try { movie = QuickTimeFile.open(movieFile); }
		catch (IOException | RuntimeException e) { System.out.println("QTMovie converter: not read as a QuickTime movie: "+e.getMessage()); }
		if(movie!=null && movie.timeScale>0){asset.timeScale=movie.timeScale;asset.duration=(int)Math.min(Integer.MAX_VALUE,movie.duration);}
		try {
			asset.musicSequence = QTMusicConverter.convert(movieFile);
			System.out.println("QTMovie converter: MIDI="+describeSequence(asset.musicSequence));
		} catch (Exception e) {
			System.err.println("QTMovie converter: MIDI conversion failed: "+e.getClass().getName()+": "+e.getMessage());
		}
		//音声は自前のデコーダ(QuickTimeSound: raw/twos/sowt/ulaw/alaw/MACEなど)で。対応していない形式だけffmpegに任せる
		boolean ownSound = false, video = movie==null, sound = movie==null;
		if(movie!=null){
			for(QuickTimeFile.Track t : movie.tracks) if(t.enabled() && t.media!=null && t.media.video() && t.media.samples()>0) video = true;
			sound = !QuickTimeSound.soundTracks(movie).isEmpty();
			if(QuickTimeSound.canDecode(movie)){
				try {
					asset.sound = QuickTimeSound.movie(movie);
					ownSound = asset.sound!=null;
					System.out.println("QTMovie converter: sound decoded natively: "+(asset.sound==null?"none":asset.sound.channels+"ch "+asset.sound.rate+"Hz "+asset.sound.millis()+"ms"));
				} catch (IOException | RuntimeException e) {
					System.err.println("QTMovie converter: own sound decoding failed: "+e.getMessage());
				}
			}
			//映像も自前のデコーダ(QuickTimeVideo: raw/rle/smc/rpza/cvid)で。フレームは表示するときにその場でデコードする
			if(video && QuickTimeVideo.canDecode(movie)){
				asset.video = new QuickTimeVideo(movie);
				System.out.println("QTMovie converter: video decoded natively: "+asset.video.width()+"x"+asset.video.height());
				video = false; //ffmpegは要らない(movieは開いたまま、サンプルを読みに行く)
			}else movie.close();
		}
		if(!video && (ownSound || !sound)) return asset; //ffmpegに頼るものが無い
		if(!isFfmpegAvailable()) {System.out.println("QTMovie converter: ffmpeg unavailable");return asset;}
		System.out.println("QTMovie converter: ffmpeg available");

		File workDir = getCacheDir(movieFile);
		if(workDir==null) return asset;

		File frameDir = new File(workDir, "frames");
		File audioOut = new File(workDir, "audio.wav");
		File doneMarker = new File(workDir, ".converted");

		try {
			if(doneMarker.isFile()){
				//前回このスタックを開いたときに変換済みのキャッシュ（.hzbフォルダ内）があるので、
				//ffmpegを再実行せずそれを読み込む
				String cached = Files.readString(doneMarker.toPath()).trim();
				if(!cached.isEmpty()){
					try{ asset.fps = Double.parseDouble(cached); }catch(NumberFormatException e){ asset.fps = 10.0; }
				}
				loadConvertedAsset(asset, frameDir, ownSound ? null : audioOut);
				return asset;
			}

			File probeTarget = movieFile;
			if(!ffprobeHasStream(probeTarget, "v") && !ffprobeHasStream(probeTarget, "a")){
				//データフォークだけではムービーヘッダが無い（'moov'がリソースフォーク側にある
				//classic QuickTimeの自己完結型ムービーの可能性）ので、再構成を試みる
				File combined = reconstructFlatMovie(movieFile, new File(workDir, "combined.mov"));
				if(combined!=null) probeTarget = combined;
			}

			if(ffprobeHasStream(probeTarget, "v")){
				double fps = ffprobeFrameRate(probeTarget);
				if(fps<=0) fps = 10.0;
				frameDir.mkdirs();
				runFfmpeg("-i", probeTarget.getPath(), "-vf", "fps="+fps,
					new File(frameDir, "frame_%05d.png").getPath());
				asset.fps = fps;
			}

			if(!ownSound && ffprobeHasStream(probeTarget, "a")){
				runFfmpeg("-i", probeTarget.getPath(), "-vn", "-acodec", "pcm_s16le", audioOut.getPath());
			}

			loadConvertedAsset(asset, frameDir, ownSound ? null : audioOut);

			//再構成用に作った中間ファイルはキャッシュに残す必要が無いので消しておく
			File combinedFile = new File(workDir, "combined.mov");
			if(combinedFile.isFile()) combinedFile.delete();

			//変換済みマーカー（次回はここから読み直すだけで済む）
			Files.writeString(doneMarker.toPath(), Double.toString(asset.fps));
		} catch (Exception e) {
			System.err.println("QTMovie: 変換中にエラーが発生しました: "+e.getMessage());
		}
		return asset;
	}

	//変換済みキャッシュフォルダの内容をMovieAssetに読み込む。
	private static void loadConvertedAsset(MovieAsset asset, File frameDir, File audioOut) throws IOException {
		if(frameDir.isDirectory()){
			File[] frameFiles = frameDir.listFiles((dir, n) -> n.endsWith(".png"));
			if(frameFiles!=null && frameFiles.length>0){
				java.util.Arrays.sort(frameFiles);
				ArrayList<BufferedImage> images = new ArrayList<BufferedImage>();
				for(File f : frameFiles){
					BufferedImage bi = ImageIO.read(f);
					if(bi!=null) images.add(bi);
				}
				if(!images.isEmpty()){
					asset.frames = images.toArray(new BufferedImage[0]);
				}
			}
		}
		if(audioOut!=null && audioOut.isFile() && audioOut.length()>0){
			asset.audioFile = audioOut;
		}
	}

	//ffmpegでの変換結果を置くフォルダ。開いているスタックの.hzbフォルダの中に、
	//動画ファイルごとのサブフォルダとして永続的に置く（アプリを再起動しても再変換しなくて済む）。
	//スタックのフォルダが分からない場合だけ、システムのtempにフォールバックする。
	private static File getCacheDir(File movieFile){
		File hzbDir = (PCARD.pc.stack!=null && PCARD.pc.stack.file!=null) ?
			PCARD.pc.stack.file.getParentFile() : null;
		if(hzbDir==null || !hzbDir.isDirectory()){
			try {
				return TempFiles.createDirectory("hyperzebra_qtmovie_");
			} catch (IOException e) {
				return null;
			}
		}
		//a stack file's (.hzb ZIP) working folder goes at quit and the cache is not carried in the file: kept beside the state
		File packed = StackBundle.packedOf(hzbDir);
		File dir = packed!=null ? new File(StackBundle.cacheFolder(packed, StackBundle.MOVIE_CACHE_KIND), movieFile.getName())
			: new File(new File(hzbDir, "_qtmovie_cache"), movieFile.getName());
		dir.mkdirs();
		return dir.isDirectory() ? dir : null;
	}

	//データフォーク('mdat'で始まる)とリソースフォークの'moov'を連結し、
	//ffmpegが読める「自己完結型」ムービーファイルを作る。
	private static File reconstructFlatMovie(File movieFile, File outFile) {
		try {
			byte[] dataFork = Files.readAllBytes(movieFile.toPath());
			if(dataFork.length<8 || dataFork[4]!='m' || dataFork[5]!='d' || dataFork[6]!='a' || dataFork[7]!='t'){
				return null; //想定外の形式
			}
			//ムービーリソース（'moov'リソース、圧縮'cmov'は展開済み）はQuickTimeFileで読む
			QuickTimeFile movie = QuickTimeFile.open(movieFile);
			movie.close();
			if(!movie.source.startsWith("resource")) return null;
			byte[] moov = movie.moov;

			//mdatアトムのサイズを、実際のデータフォーク長で書き直す
			//（0のままだと「ファイル終端まで」の意味になり、後ろに続けるmoovまで飲み込んでしまう）
			byte[] patched = dataFork.clone();
			int size = patched.length;
			patched[0]=(byte)((size>>24)&0xFF);
			patched[1]=(byte)((size>>16)&0xFF);
			patched[2]=(byte)((size>>8)&0xFF);
			patched[3]=(byte)(size&0xFF);

			try (FileOutputStream fos = new FileOutputStream(outFile)) {
				fos.write(patched);
				fos.write(moov);
			}
			return outFile;
		} catch (IOException e) {
			return null;
		}
	}

	private static boolean ffprobeHasStream(File file, String streamType){
		try {
			Process p = new ProcessBuilder("ffprobe", "-v", "error",
				"-select_streams", streamType+":0",
				"-show_entries", "stream=codec_type",
				"-of", "csv=p=0", file.getPath()).start();
			String out = new String(p.getInputStream().readAllBytes());
			p.getErrorStream().readAllBytes();
			p.waitFor();
			return out.trim().length()>0;
		} catch (Exception e) {
			return false;
		}
	}

	private static double ffprobeFrameRate(File file){
		try {
			Process p = new ProcessBuilder("ffprobe", "-v", "error",
				"-select_streams", "v:0",
				"-show_entries", "stream=r_frame_rate",
				"-of", "default=noprint_wrappers=1:nokey=1", file.getPath()).start();
			String out = new String(p.getInputStream().readAllBytes()).trim();
			p.getErrorStream().readAllBytes();
			p.waitFor();
			String[] parts = out.split("/");
			if(parts.length==2){
				double num = Double.parseDouble(parts[0]);
				double den = Double.parseDouble(parts[1]);
				if(den>0) return num/den;
			}else if(parts.length==1 && !parts[0].isEmpty()){
				return Double.parseDouble(parts[0]);
			}
		} catch (Exception e) {
			//無視してデフォルト値を使う
		}
		return 0;
	}

	private static void runFfmpeg(String... args) throws IOException, InterruptedException {
		ArrayList<String> cmd = new ArrayList<String>();
		cmd.add("ffmpeg");
		cmd.add("-y");
		for(String a : args) cmd.add(a);
		Process p = new ProcessBuilder(cmd).start();
		p.getInputStream().readAllBytes();
		p.getErrorStream().readAllBytes();
		p.waitFor();
	}
}

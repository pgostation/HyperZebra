import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * HyperZebra's own player of a classic Mac sound or movie file ({@link MediaDocument}), for a drop that no known Mac
 * program opens - the way {@link GTextDocumentWindow} shows a ReadMe: a QuickTime movie with its video, sound and music
 * tracks, a sound file's 'snd ' resources (a menu picks one), AIFF/AIFC/WAVE, SoundEdit, MP3 and MIDI files. Play/pause,
 * a position slider, loop and volume; Space plays and pauses, the arrow keys step (a frame of a movie, a second of a
 * sound), Home goes to the start. A sampled sound can be saved as WAVE and a movie's music as a MIDI file, for the
 * host's own programs; a video frame can be copied. One window per file; closing it stops what plays.
 */
final class GMediaPlayerWindow extends JFrame {
	private static final long serialVersionUID = 1L;
	private static final Map<File,GMediaPlayerWindow> open = new HashMap<File,GMediaPlayerWindow>();

	/** Shows the file in its window (made on the first call, brought to the front later) and starts playing it. */
	static void show(File f) throws IOException {
		File key = f.getAbsoluteFile();
		GMediaPlayerWindow w = open.get(key);
		if(w==null){
			MediaDocument.Kind kind = MediaDocument.kindOf(f, DocumentOpeners.typeOf(f));
			if(kind==null) throw new IOException(text("This is no sound or movie file."));
			w = new GMediaPlayerWindow(f, kind);
			open.put(key, w);
			java.awt.Window near = PCARD.pc!=null && PCARD.pc.isShowing() ? PCARD.pc : null;
			w.setLocationRelativeTo(near);
		}
		w.setVisible(true);
		w.setState(JFrame.NORMAL);
		w.toFront();
	}

	/** Tests: the window showing the file, or null. */
	static GMediaPlayerWindow of(File f){ return open.get(f.getAbsoluteFile()); }

	private static String text(String key){ return PCARD.pc!=null && PCARD.pc.intl!=null ? PCARD.pc.intl.getDialogText(key) : key; }

	final File file;
	final MediaDocument.Kind kind;
	private final List<MediaDocument.Sound> sounds;
	/** What plays now; null while it loads (or when it could not be read). */
	TQTMovie.MovieAsset asset;
	private QuickTimeSound.Pcm hostSound; // the asset's samples at a rate the host plays as they are
	private StreamingSound audio;
	private Sequencer sequencer;
	private Synthesizer synthesizer;
	private long durationMs, positionMs, startedAt;
	private boolean playing;
	private int level = 256;
	private boolean updatingSlider;
	private BufferedImage frame;
	private int loads; // the number of the load in progress (an older one finishing late is dropped)

	private final JButton play = new JButton("▶");
	private final JSlider slider = new JSlider(0, 1000, 0);
	private final JLabel time = new JLabel("0:00.0 / 0:00.0"), info = new JLabel(" "), message = new JLabel("", SwingConstants.CENTER);
	private final JCheckBox loop = new JCheckBox(text("Loop"));
	private final JSlider volume = new JSlider(0, 256, 256);
	private final JButton saveWav = new JButton(text("Save as WAVE…")), saveMidi = new JButton(text("Save as MIDI…")), copyFrame = new JButton(text("Copy Frame"));
	private final JComboBox<MediaDocument.Sound> soundMenu;
	private final JPanel screen = new JPanel(new BorderLayout()){
		private static final long serialVersionUID = 1L;
		@Override protected void paintComponent(Graphics g){
			super.paintComponent(g);
			if(frame==null) return;
			// the frame as large as fits, its proportions kept; whole multiples stay sharp
			double s = Math.min(getWidth()/(double)frame.getWidth(), getHeight()/(double)frame.getHeight());
			int w = (int)Math.round(frame.getWidth()*s), h = (int)Math.round(frame.getHeight()*s);
			Graphics2D g2 = (Graphics2D)g;
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, s==Math.rint(s) || s<1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.drawImage(frame, (getWidth()-w)/2, (getHeight()-h)/2, w, h, null);
		}
	};
	private final Timer ticker = new Timer(15, e -> tick());

	private GMediaPlayerWindow(File file, MediaDocument.Kind kind){
		super(LauncherStore.displayName(file));
		this.file = file;
		this.kind = kind;
		sounds = kind==MediaDocument.Kind.SOUND_RESOURCES ? MediaDocument.sounds(file) : java.util.Collections.<MediaDocument.Sound>emptyList();
		soundMenu = new JComboBox<MediaDocument.Sound>(sounds.toArray(new MediaDocument.Sound[0]));
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		if(PCARD.pc!=null && PCARD.pc.getIconImage()!=null) setIconImage(PCARD.pc.getIconImage());
		addWindowListener(new WindowAdapter(){ @Override public void windowClosed(WindowEvent e){ open.remove(file.getAbsoluteFile()); release(); } });
		int menu = GMenu.isMacOSX() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
		key(KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), this::dispose);
		key(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), this::toggle);
		key(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), () -> step(-1));
		key(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), () -> step(1));
		key(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), () -> seek(0));
		key(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, menu), this::pause);

		play.setToolTipText(text("Play / Pause (Space)"));
		play.setFocusable(false);
		play.addActionListener(e -> toggle());
		slider.setFocusable(false);
		slider.addChangeListener(e -> { if(!updatingSlider && durationMs>0) seek(slider.getValue()*durationMs/slider.getMaximum()); });
		time.setFont(time.getFont().deriveFont(java.awt.Font.PLAIN, Math.max(11f, time.getFont().getSize2D()-1)));
		loop.setFocusable(false);
		loop.addActionListener(e -> { if(audio!=null && soundOnly()) audio.setLoop(loop.isSelected()); });
		volume.setFocusable(false);
		volume.setPreferredSize(new Dimension(90, volume.getPreferredSize().height));
		volume.setToolTipText(text("Volume"));
		volume.addChangeListener(e -> { level = volume.getValue(); applyLevel(); });
		saveWav.setToolTipText(text("Save a copy the host's programs play"));
		saveWav.addActionListener(e -> saveAsWave());
		saveMidi.setToolTipText(text("Save the music as a Standard MIDI File"));
		saveMidi.addActionListener(e -> saveAsMidi());
		copyFrame.addActionListener(e -> { if(frame!=null) copyImage(frame); });
		soundMenu.setFocusable(false);
		soundMenu.addActionListener(e -> load((MediaDocument.Sound)soundMenu.getSelectedItem()));

		screen.add(message, BorderLayout.CENTER); // a movie's frames go on black (show), a sound's name on the window's colour
		screen.addMouseListener(new MouseAdapter(){ @Override public void mousePressed(MouseEvent e){ if(asset!=null && asset.hasVideo()) toggle(); } });

		JPanel transport = new JPanel(new BorderLayout(6, 0));
		transport.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 8));
		transport.add(play, BorderLayout.WEST);
		transport.add(slider, BorderLayout.CENTER);
		transport.add(time, BorderLayout.EAST);
		JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		options.add(loop);
		options.add(new JLabel("🔈"));
		options.add(volume);
		options.add(saveWav);
		options.add(saveMidi);
		options.add(copyFrame);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(transport, BorderLayout.NORTH);
		bottom.add(options, BorderLayout.CENTER);
		info.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
		info.setForeground(Color.GRAY);
		bottom.add(info, BorderLayout.SOUTH);
		JPanel content = new JPanel(new BorderLayout());
		if(sounds.size()>1){
			JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
			top.add(new JLabel(text("Sound:")));
			top.add(soundMenu);
			content.add(top, BorderLayout.NORTH);
		}
		content.add(screen, BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);
		setContentPane(content);
		setSize(new Dimension(Math.max(520, bottom.getPreferredSize().width+16), 240));
		load(sounds.isEmpty() ? null : sounds.get(0));
	}

	private void key(KeyStroke stroke, Runnable action){ getRootPane().registerKeyboardAction(e -> action.run(), stroke, JComponent.WHEN_IN_FOCUSED_WINDOW); }

	/** Decodes the file (or the sound of a sound file) away from the event thread, then shows it and plays it. */
	private void load(final MediaDocument.Sound sound){
		release();
		asset = null; frame = null; durationMs = 0; positionMs = 0;
		final int load = ++loads;
		message.setIcon(null);
		message.setText(text("Loading…"));
		message.setVisible(true);
		enableControls();
		new SwingWorker<TQTMovie.MovieAsset,Void>(){
			private String note = "";
			private List<String> formats = java.util.Collections.emptyList();
			@Override protected TQTMovie.MovieAsset doInBackground() throws Exception {
				formats = MediaDocument.formats(file, kind, sound);
				TQTMovie.MovieAsset a = MediaDocument.load(file, kind, sound);
				if(kind==MediaDocument.Kind.MOVIE && !a.hasVideo()){
					List<String> codecs = MediaDocument.undecodedVideo(file);
					if(!codecs.isEmpty()) note = text("The video can't be decoded")+" ('"+String.join("', '", codecs)+"')";
				}
				if(kind==MediaDocument.Kind.MOVIE && !a.hasAudio()){
					List<String> codecs = MediaDocument.undecodedSound(file);
					if(!codecs.isEmpty()) note += (note.isEmpty() ? "" : "\n")+text("The sound can't be decoded")+" ('"+String.join("', '", codecs)+"')";
				}
				return a;
			}
			@Override protected void done(){
				if(load!=loads || !isDisplayable()) return;
				showFormats(formats, note);
				try{ show(get(), note); }
				catch(Exception e){
					Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause()!=null ? e.getCause() : e;
					message.setText(text("Can't play the file.")+(cause.getMessage()==null ? "" : " "+cause.getMessage()));
				}
			}
		}.execute();
	}

	private void show(TQTMovie.MovieAsset a, String note){
		asset = a;
		if(!a.hasVideo() && !a.hasAudio() && a.musicSequence==null){
			message.setText(note.isEmpty() ? text("Nothing in the file can be played.") : "<html><center>"+escape(note).replace("\n", "<br>")+"</center></html>");
			enableControls();
			return;
		}
		durationMs = duration(a);
		if(a.sound!=null){ hostSound = MediaDocument.forHost(a.sound); audio = StreamingSound.of(hostSound); }
		else if(a.mp3!=null) audio = StreamingSound.of(a.mp3);
		else if(a.audioFile!=null){
			try{ hostSound = MediaDocument.forHost(MediaDocument.pcm(AudioSystem.getAudioInputStream(a.audioFile))); audio = StreamingSound.of(hostSound); }
			catch(Exception e){ System.err.println("[media player] "+e); }
		}
		if(audio!=null && soundOnly()) audio.setLoop(loop.isSelected());
		if(a.musicSequence!=null){
			try{
				synthesizer = MidiSystem.getSynthesizer(); synthesizer.open();
				sequencer = MidiSystem.getSequencer(false); sequencer.open();
				sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
				sequencer.setSequence(a.musicSequence);
			}catch(Exception e){
				System.err.println("[media player] MIDI: "+e);
				if(sequencer!=null) sequencer.close();
				if(synthesizer!=null) synthesizer.close();
				sequencer = null; synthesizer = null;
			}
		}
		applyLevel();
		if(a.hasVideo()){
			message.setVisible(false);
			screen.setBackground(Color.BLACK);
			frame = a.frameAtMillis(0);
			int w = a.frameWidth(), h = a.frameHeight(), scale = w<=320 && h<=240 ? 2 : 1;
			java.awt.Rectangle screenArea = getGraphicsConfiguration()!=null ? getGraphicsConfiguration().getBounds() : new java.awt.Rectangle(0, 0, 1280, 800);
			int chrome = getContentPane().getPreferredSize().height-screen.getPreferredSize().height+getInsets().top+getInsets().bottom;
			while(scale>1 && (w*scale>screenArea.width-80 || h*scale+chrome>screenArea.height-80)) scale--;
			double fit = Math.min(1, Math.min((screenArea.width-80)/(double)w, (screenArea.height-80-chrome)/(double)h));
			setSize(new Dimension(Math.max(getWidth(), (int)(w*scale*fit)+16), (int)(h*scale*fit)+chrome));
			setLocationRelativeTo(PCARD.pc!=null && PCARD.pc.isShowing() ? PCARD.pc : null);
		}
		else{
			message.setText(LauncherStore.displayName(file)+(sounds.size()>1 ? " — "+soundMenu.getSelectedItem() : ""));
			message.setIcon(new ImageIcon(GLauncher.documentIconOf(file, 32)));
			message.setIconTextGap(10);
		}
		enableControls();
		updateTime();
		screen.repaint();
		play();
	}

	/** How the media are stored, a line each ({@link MediaDocument#formats}), and why something does not play; the window grows to show them. */
	private void showFormats(List<String> formats, String note){
		StringBuilder b = new StringBuilder("<html>");
		for(String line : formats) b.append(escape(line)).append("<br>");
		if(!note.isEmpty()) b.append(escape(note).replace("\n", "<br>"));
		info.setText(formats.isEmpty() && note.isEmpty() ? " " : b.toString());
		info.setToolTipText(null);
		getContentPane().validate();
		int need = getContentPane().getPreferredSize().height-screen.getPreferredSize().height+getInsets().top+getInsets().bottom+80;
		if(getHeight()<need) setSize(getWidth(), need);
	}
	private static String escape(String s){ return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

	private static long duration(TQTMovie.MovieAsset a){
		if(a.timeScale>0 && a.duration>0) return a.duration*1000L/a.timeScale;
		if(a.video!=null) return a.video.durationMillis();
		if(a.sound!=null) return a.sound.millis();
		if(a.mp3!=null) return a.mp3.millis();
		if(a.musicSequence!=null) return a.musicSequence.getMicrosecondLength()/1000L;
		if(a.frames!=null && a.frames.length>0) return (long)(a.frames.length*1000.0/a.frameRate());
		return 0;
	}

	/** Only a sampled sound plays (it loops by itself, without a gap). */
	private boolean soundOnly(){ return asset!=null && !asset.hasVideo() && audio!=null && sequencer==null; }

	private void enableControls(){
		boolean ready = asset!=null && durationMs>0;
		play.setEnabled(ready);
		slider.setEnabled(ready);
		saveWav.setVisible(asset!=null && (asset.sound!=null || hostSound!=null));
		saveMidi.setVisible(asset!=null && asset.musicSequence!=null && kind!=MediaDocument.Kind.MIDI); // a MIDI file is one already
		copyFrame.setVisible(asset!=null && asset.hasVideo());
	}

	// ------------------------------------------------------------------ playing

	void toggle(){ if(playing) pause(); else play(); }

	void play(){
		if(asset==null || durationMs<=0 || playing) return;
		if(positionMs>=durationMs) positionMs = 0;
		startedAt = System.currentTimeMillis()-positionMs;
		if(audio!=null){ audio.setMicrosecondPosition(positionMs*1000L); audio.start(); }
		if(sequencer!=null){ sequencer.setMicrosecondPosition(positionMs*1000L); sequencer.start(); applyLevel(); }
		playing = true;
		play.setText("❚❚");
		ticker.start();
	}

	void pause(){
		if(!playing) return;
		positionMs = Math.min(durationMs, currentMs());
		stopParts();
		playing = false;
		play.setText("▶");
		ticker.stop();
		updateTime();
	}

	private void stopParts(){
		if(audio!=null) audio.stop();
		if(sequencer!=null) sequencer.stop();
	}

	/** Goes to {@code ms} into the file, playing on from there when it was playing. */
	void seek(long ms){
		boolean was = playing;
		if(playing){ stopParts(); playing = false; ticker.stop(); }
		positionMs = Math.max(0, Math.min(durationMs, ms));
		showFrame(positionMs);
		updateTime();
		if(was) play();
		else play.setText("▶");
	}

	/** A frame on or back (a movie), else a second. */
	private void step(int direction){
		if(asset==null) return;
		long by = asset.hasVideo() ? Math.max(1, Math.round(1000/asset.frameRate())) : 1000;
		long at = playing ? currentMs() : positionMs;
		if(asset.hasVideo()) pause();
		seek(at+direction*by);
	}

	/** Where playing stands: the sound line's position for a sound alone (exact, and it loops by itself), else the clock's. */
	long currentMs(){
		if(!playing) return positionMs;
		if(soundOnly()) return audio.getMicrosecondPosition()/1000L;
		if(sequencer!=null && audio==null && !asset.hasVideo()) return sequencer.getMicrosecondPosition()/1000L;
		return System.currentTimeMillis()-startedAt;
	}

	private void tick(){
		if(!playing) return;
		long t = currentMs();
		if(t>=durationMs && !(soundOnly() && loop.isSelected())){
			if(loop.isSelected()){ stopParts(); playing = false; positionMs = 0; play(); t = 0; }
			else{ stopParts(); playing = false; ticker.stop(); positionMs = durationMs; play.setText("▶"); t = durationMs; }
		}
		showFrame(Math.min(t, Math.max(0, durationMs-1)));
		updateTime(t);
	}

	private void showFrame(long ms){
		if(asset==null || !asset.hasVideo()) return;
		BufferedImage f = asset.frameAtMillis(ms);
		if(f!=null && f!=frame){ frame = f; screen.repaint(); }
	}

	private void updateTime(){ updateTime(playing ? currentMs() : positionMs); }
	private void updateTime(long ms){
		ms = Math.max(0, Math.min(durationMs, ms));
		time.setText(clock(ms)+" / "+clock(durationMs));
		updatingSlider = true;
		slider.setValue(durationMs>0 ? (int)(ms*slider.getMaximum()/durationMs) : 0);
		updatingSlider = false;
	}
	private static String clock(long ms){ return String.format(Locale.ROOT, "%d:%02d.%d", ms/60000, ms/1000%60, ms/100%10); }

	private void applyLevel(){
		if(audio!=null) audio.setLevel(level);
		if(synthesizer!=null) for(MidiChannel channel : synthesizer.getChannels()) if(channel!=null) channel.controlChange(7, TQTMovie.midiVolume(level));
	}

	/** Stops and lets go of the sound line, the synthesizer and the movie's files. */
	private void release(){
		ticker.stop();
		playing = false;
		if(audio!=null){ audio.close(); audio = null; }
		if(sequencer!=null){ sequencer.stop(); sequencer.close(); sequencer = null; }
		if(synthesizer!=null){ synthesizer.close(); synthesizer = null; }
		if(asset!=null && asset.video!=null) asset.video.close();
		hostSound = null;
		play.setText("▶");
	}

	// ------------------------------------------------------------------ saving

	private File chooseTarget(String title, String extension){
		FileDialog dialog = new FileDialog(this, title, FileDialog.SAVE);
		File dir = file.getAbsoluteFile().getParentFile();
		if(dir!=null) dialog.setDirectory(dir.getPath());
		String name = LauncherStore.displayName(file);
		if(sounds.size()>1 && soundMenu.getSelectedItem()!=null) name += " "+soundMenu.getSelectedItem();
		int dot = name.lastIndexOf('.');
		if(dot>0 && name.length()-dot<=5) name = name.substring(0, dot);
		dialog.setFile(name.replace('/', '-').replace(':', '-')+extension);
		dialog.setVisible(true);
		if(dialog.getFile()==null) return null;
		File target = new File(dialog.getDirectory(), dialog.getFile());
		if(target.getAbsoluteFile().equals(file.getAbsoluteFile())){
			new GDialog(this, text("Choose another name: the original stays as it is."), null, "OK", null, null);
			return null;
		}
		return target;
	}

	/** Writes the sound as a 16-bit WAVE file at its own rate. */
	private void saveAsWave(){
		QuickTimeSound.Pcm p = asset==null ? null : asset.sound!=null ? asset.sound : hostSound;
		if(p==null) return;
		File target = chooseTarget(text("Save as WAVE…"), ".wav");
		if(target==null) return;
		try{
			javax.sound.sampled.AudioFormat little = new javax.sound.sampled.AudioFormat(p.rate, 16, p.channels, true, false);
			AudioSystem.write(AudioSystem.getAudioInputStream(little, p.stream()), AudioFileFormat.Type.WAVE, target);
		}catch(IOException | RuntimeException ex){ new GDialog(this, text("Can't save the file.")+"\n"+ex.getMessage(), null, "OK", null, null); }
	}

	/** Writes a movie's music track as a Standard MIDI File. */
	private void saveAsMidi(){
		if(asset==null || asset.musicSequence==null) return;
		File target = chooseTarget(text("Save as MIDI…"), ".mid");
		if(target==null) return;
		try{
			int[] types = MidiSystem.getMidiFileTypes(asset.musicSequence);
			if(types.length==0) throw new IOException("MIDI");
			int type = types[types.length-1];
			MidiSystem.write(asset.musicSequence, type, target);
		}catch(IOException | RuntimeException ex){ new GDialog(this, text("Can't save the file.")+"\n"+ex.getMessage(), null, "OK", null, null); }
	}

	/** The frame on the host's clipboard. */
	private static void copyImage(final BufferedImage img){
		java.awt.datatransfer.Transferable t = new java.awt.datatransfer.Transferable(){
			public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors(){ return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor}; }
			public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f){ return java.awt.datatransfer.DataFlavor.imageFlavor.equals(f); }
			public Object getTransferData(java.awt.datatransfer.DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException {
				if(!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f);
				return img;
			}
		};
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
	}

	@Override public void dispose(){ release(); super.dispose(); }

	/** Tests: whether it plays now. */
	boolean isPlaying(){ return playing; }
}

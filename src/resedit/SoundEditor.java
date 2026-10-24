import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

class SoundUI {
	static String text(String english) {
		if(PCARDFrame.pc != null && PCARDFrame.pc.intl != null)
			return PCARDFrame.pc.intl.getText(english);
		return english;
	}
}

/** Sound-resource list used by both Sound... and the resource type index. */
class SoundTypeEditor extends TableTypeEditor {
	private static final long serialVersionUID = 1L;
	private Clip previewClip;

	SoundTypeEditor(PCARDFrame pc, OObject object) {
		super(pc, "SND", object, new String[]{"ID", SoundUI.text("Name"), SoundUI.text("Duration"), SoundUI.text("Format")});

		//再生だけはメニューに無いのでボタンで
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
		controls.add(button(SoundUI.text("Play"), e -> playSelected()));
		controls.add(button(SoundUI.text("Stop"), e -> stopPreview()));
		getContentPane().add(controls, BorderLayout.SOUTH);

		setSize(680, 460);
		setLocationRelativeTo(pc);
		open(pc, 0);
		setVisible(true);
	}

	private JButton button(String text, java.awt.event.ActionListener listener) {
		JButton button = new JButton(text);
		button.addActionListener(listener);
		return button;
	}

	@Override String typeTitle() { return SoundUI.text("Sound Editor"); }

	@Override void open(PCARDFrame pc, int scroll) {
		stopPreview();
		super.open(pc, scroll);
	}

	@Override Object[] rowFor(Rsrc.rsrcClass resource) {
		String duration = "—";
		String format = "—";
		try {
			SoundData data = SoundData.read(new File(rsrc.getFilePathAll(resource.id, "SND")));
			duration = String.format("%.2f s", data.durationSeconds());
			format = Math.round(data.sampleRate)+" Hz / "+data.channels+" ch";
		} catch(Exception error) {
			format = "Unreadable";
		}
		return new Object[]{resource.id, resource.name, duration, format};
	}

	private Rsrc.rsrcClass selectedResource() {
		int id = primarySelectedId();
		return id==0 ? null : rsrc.getResource1(id, "SND");
	}

	private void playSelected() {
		Rsrc.rsrcClass resource = selectedResource();
		if(resource == null) return;
		try {
			SoundData data = SoundData.read(new File(rsrc.getFilePathAll(resource.id, "SND")));
			stopPreview();
			previewClip = data.openClip();
			previewClip.start();
		} catch(Exception error) {
			showError("The sound could not be played.", error);
		}
	}

	@Override void createNewResource() {
		new SoundResourceEditor(this, null);
	}

	@Override void openResource(int id) {
		Rsrc.rsrcClass resource = rsrc.getResource1(id, "SND");
		if(resource != null) new SoundResourceEditor(this, resource);
	}

	private void showError(String message, Exception error) {
		JOptionPane.showMessageDialog(this, message+"\n"+error.getMessage(),
			"Sound Editor", JOptionPane.ERROR_MESSAGE);
	}

	private void stopPreview() {
		if(previewClip != null) {
			previewClip.stop();
			previewClip.close();
			previewClip = null;
		}
	}

	@Override void maybeExit() {
		stopPreview();
		super.maybeExit();
	}
}

/** Editor for one sound resource: select a range, then cut/copy/paste it or insert silence. */
class SoundResourceEditor extends JFrame {
	private static final long serialVersionUID = 1L;
	private final SoundTypeEditor owner;
	private Rsrc.rsrcClass resource;
	private SoundData sound;
	private final ArrayDeque<SoundData> undo = new ArrayDeque<SoundData>();
	private final WaveformPanel waveform = new WaveformPanel();
	private final JLabel status = new JLabel(" ");
	private Clip previewClip;
	private final JSpinner gain = new JSpinner(new SpinnerNumberModel(100, 0, 400, 5));
	private final JSpinner pitch = new JSpinner(new SpinnerNumberModel(0, -36, 36, 1));
	private final JSpinner silence = new JSpinner(new SpinnerNumberModel(0.50, 0.01, 10.0, 0.05));
	//コピーした範囲はエディタ間で共有する(別のサウンドへ貼り付けられるように)
	private static SoundData clipboard;

	SoundResourceEditor(SoundTypeEditor owner, Rsrc.rsrcClass resource) {
		this.owner = owner;
		this.resource = resource;
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent e) { closeEditor(); }
		});
		if(owner.session != null) owner.session.attach(this);
		setLayout(new BorderLayout(8, 8));
		waveform.setPreferredSize(new Dimension(760, 200));
		waveform.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(waveform, BorderLayout.CENTER);

		//上段: 再生と全体/選択範囲への音量・音階の適用
		JPanel playRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
		playRow.add(button(SoundUI.text("Play"), e -> play()));
		playRow.add(button(SoundUI.text("Stop"), e -> stop()));
		playRow.add(new JLabel(SoundUI.text("Pitch")));
		playRow.add(pitch);
		playRow.add(new JLabel(SoundUI.text("semitones")));
		playRow.add(new JLabel(SoundUI.text("Volume")));
		playRow.add(gain);
		playRow.add(new JLabel("%"));
		playRow.add(button(SoundUI.text("Apply"), e -> applyGain()));

		//下段: 波形上でドラッグした範囲の削除・複製と、クリック位置への挿入
		JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
		editRow.add(button(SoundUI.text("Select All"), e -> waveform.selectAll()));
		editRow.add(button(SoundUI.text("Cut Selection"), e -> cutSelection()));
		editRow.add(button(SoundUI.text("Copy Selection"), e -> copySelection()));
		editRow.add(button(SoundUI.text("Paste"), e -> paste()));
		editRow.add(button(SoundUI.text("Insert Silence"), e -> insertSilence()));
		editRow.add(silence);
		editRow.add(new JLabel(SoundUI.text("sec")));
		editRow.add(button(SoundUI.text("Undo"), e -> undo()));

		JPanel edit = new JPanel(new GridLayout(2, 1));
		edit.add(playRow);
		edit.add(editRow);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(edit, BorderLayout.NORTH);
		JPanel save = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		save.add(status);
		//作業用コピーへ反映する(スタックへはリソースエディタの保存(Cmd-S)で)
		save.add(button(SoundUI.text("Apply to Resource"), e -> save()));
		save.add(button(SoundUI.text("Close"), e -> closeEditor()));
		bottom.add(save, BorderLayout.SOUTH);
		add(bottom, BorderLayout.SOUTH);

		try {
			if(resource == null) {
				sound = SoundData.generate(440.0, 0.5, "Sine", 44100.0F);
			} else {
				String path = owner.rsrc.getFilePathAll(resource.id, "SND");
				sound = SoundData.read(new File(path));
			}
			setSound(sound);
		} catch(Exception error) {
			JOptionPane.showMessageDialog(owner, "The sound could not be opened.\n"+error.getMessage(),
				"Sound Editor", JOptionPane.ERROR_MESSAGE);
			dispose();
			return;
		}
		setTitle(resource == null ? SoundUI.text("New Sound") : resource.id+" "+resource.name);
		pack();
		setLocationRelativeTo(owner);
		setVisible(true);
	}

	private JButton button(String text, java.awt.event.ActionListener listener) {
		JButton button = new JButton(text);
		button.addActionListener(listener);
		return button;
	}

	private void setSound(SoundData data) {
		sound = data;
		waveform.setSound(data);
		status.setText(String.format("%.2f s   %d Hz   %d ch",
			data.durationSeconds(), Math.round(data.sampleRate), data.channels));
	}

	private void rememberUndo() {
		undo.push(sound.copy());
		while(undo.size() > 20) undo.removeLast();
	}

	private void cutSelection() {
		int start = waveform.selectionStart();
		int end = waveform.selectionEnd();
		if(end <= start) return;
		rememberUndo();
		clipboard = sound.slice(start, end);
		setSound(sound.cut(start, end));
		waveform.setSelection(start, start);
	}

	private void copySelection() {
		int start = waveform.selectionStart();
		int end = waveform.selectionEnd();
		if(end <= start) return;
		clipboard = sound.slice(start, end);
	}

	/** 選択範囲があればそれを置き換え、なければクリック位置(キャレット)に挿入する。 */
	private void paste() {
		if(clipboard == null) return;
		insertAtSelection(clipboard);
	}

	private void insertSilence() {
		double seconds = ((Number)silence.getValue()).doubleValue();
		int frames = Math.max(1, (int)Math.round(seconds*sound.sampleRate));
		insertAtSelection(new SoundData(sound.sampleRate, sound.channels, new short[frames*sound.channels]));
	}

	private void insertAtSelection(SoundData piece) {
		int start = waveform.selectionStart();
		int end = waveform.selectionEnd();
		rememberUndo();
		SoundData base = end > start ? sound.cut(start, end) : sound;
		SoundData inserted = base.insert(start, piece);
		setSound(inserted);
		//貼り付けた範囲を選択状態にして、そのまま再生や再編集ができるようにする
		waveform.setSelection(start, start+(inserted.frameCount()-base.frameCount()));
	}

	private void applyGain() {
		rememberUndo();
		int start = waveform.selectionStart();
		int end = waveform.selectionEnd();
		if(end <= start) { start = 0; end = sound.frameCount(); }
		setSound(sound.amplify(start, end, ((Number)gain.getValue()).doubleValue()/100.0));
	}

	private void undo() {
		if(!undo.isEmpty()) setSound(undo.pop());
	}

	private void play() {
		try {
			stop();
			int start = waveform.selectionStart();
			int end = waveform.selectionEnd();
			SoundData playback = end > start ? sound.slice(start, end) : sound;
			playback = playback.shiftPitch(((Number)pitch.getValue()).intValue());
			previewClip = playback.openClip();
			previewClip.start();
		} catch(Exception error) {
			JOptionPane.showMessageDialog(this, "The sound could not be played.\n"+error.getMessage(),
				"Sound Editor", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void stop() {
		if(previewClip != null) {
			previewClip.stop();
			previewClip.close();
			previewClip = null;
		}
	}

	//ICON/cicn等のグラフィックリソースと同じく、編集後もMac本来のsnd
	//リソース形式(拡張子.data)のまま保存する。以前はここで.wavとして
	//保存していたため、エクスポートのたびに(せっかく再現できる本物のsnd
	//形式ではなく)このアプリ独自の変換を通すことになっていた。
	//書き込み先はリソースエディタの作業用コピー(スタックには保存(Cmd-S)で書き戻される)。
	private void save() {
		try {
			byte[] sndData = HCResourceExport.sound(sound);
			if(resource == null) {
				int id = owner.rsrc.getNewResourceId("SND");
				String filename = owner.rsrc.freeFileName("SND_"+id+".data", null);
				writeRawSoundResource(filename, sndData);
				owner.rsrc.addResource(id, "SND", "Sound "+id, filename);
				resource = owner.rsrc.getResource1(id, "SND");
			} else {
				String filename = owner.rsrc.freeFileName("SND_"+resource.id+".data", resource);
				writeRawSoundResource(filename, sndData);
				resource.filename = filename;
				owner.rsrc.markChanged();
			}
			setTitle(resource.id+" "+resource.name);
			owner.reload();
			owner.selectIds(new int[]{resource.id});
			status.setText(status.getText()+"   "+SoundUI.text("Applied"));
		} catch(Exception error) {
			JOptionPane.showMessageDialog(this, "The sound could not be saved.\n"+error.getMessage(),
				"Sound Editor", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void writeRawSoundResource(String filename, byte[] data) throws IOException {
		try(java.io.FileOutputStream fos = new java.io.FileOutputStream(owner.rsrc.fileToWrite(filename))){
			fos.write(data);
		}
	}

	private void closeEditor() {
		if(owner.session != null && !owner.session.confirmClose(this)) return;
		stop();
		dispose();
	}
}

/** Immutable interleaved 16-bit PCM data used by the editor and its tests. */
class SoundData {
	final float sampleRate;
	final int channels;
	final short[] samples;

	SoundData(float sampleRate, int channels, short[] samples) {
		if(sampleRate <= 0 || channels <= 0 || samples == null || samples.length % channels != 0)
			throw new IllegalArgumentException("Invalid PCM sound data");
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.samples = samples.clone();
	}

	static SoundData read(File file) throws Exception {
		try(AudioInputStream original = TSound.openAudioInputStream(file)) {
			AudioFormat source = original.getFormat();
			AudioFormat pcmFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
				source.getSampleRate(), 16, source.getChannels(), source.getChannels()*2,
				source.getSampleRate(), false);
			try(AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, original)) {
				byte[] bytes = readAll(pcm);
				int sampleCount = bytes.length/2;
				short[] samples = new short[sampleCount - sampleCount%pcmFormat.getChannels()];
				for(int i=0; i<samples.length; i++)
					samples[i] = (short)((bytes[i*2]&0xFF) | (bytes[i*2+1]<<8));
				return new SoundData(pcmFormat.getSampleRate(), pcmFormat.getChannels(), samples);
			}
		}
	}

	private static byte[] readAll(AudioInputStream stream) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[16384];
		for(int count; (count=stream.read(buffer)) >= 0;) {
			if(count > 0) output.write(buffer, 0, count);
		}
		return output.toByteArray();
	}

	int frameCount() { return samples.length/channels; }
	double durationSeconds() { return frameCount()/sampleRate; }
	SoundData copy() { return new SoundData(sampleRate, channels, samples); }

	SoundData slice(int startFrame, int endFrame) {
		int start = clampFrame(startFrame);
		int end = Math.max(start, clampFrame(endFrame));
		short[] result = new short[(end-start)*channels];
		System.arraycopy(samples, start*channels, result, 0, result.length);
		return new SoundData(sampleRate, channels, result.length == 0 ? new short[channels] : result);
	}

	SoundData cut(int startFrame, int endFrame) {
		int start = clampFrame(startFrame);
		int end = Math.max(start, clampFrame(endFrame));
		if(start == end) return copy();
		short[] result = new short[samples.length-(end-start)*channels];
		System.arraycopy(samples, 0, result, 0, start*channels);
		System.arraycopy(samples, end*channels, result, start*channels,
			samples.length-end*channels);
		if(result.length == 0) result = new short[channels];
		return new SoundData(sampleRate, channels, result);
	}

	/**
	 * frame位置にpieceを挿入する。pieceのサンプルレート・チャンネル数が違う場合は
	 * このサウンドの形式に合わせて変換する(別のサウンドから貼り付けたとき用)。
	 */
	SoundData insert(int frame, SoundData piece) {
		SoundData source = piece.convertTo(sampleRate, channels);
		int at = clampFrame(frame)*channels;
		short[] result = new short[samples.length+source.samples.length];
		System.arraycopy(samples, 0, result, 0, at);
		System.arraycopy(source.samples, 0, result, at, source.samples.length);
		System.arraycopy(samples, at, result, at+source.samples.length, samples.length-at);
		return new SoundData(sampleRate, channels, result);
	}

	/** サンプルレートは線形補間で、チャンネル数はミックス/複製で合わせる。 */
	SoundData convertTo(float targetRate, int targetChannels) {
		if(targetRate == sampleRate && targetChannels == channels) return this;
		int sourceFrames = frameCount();
		double ratio = sampleRate/targetRate;
		int resultFrames = Math.max(1, (int)Math.round(sourceFrames/ratio));
		short[] result = new short[resultFrames*targetChannels];
		for(int frame=0; frame<resultFrames; frame++) {
			double sourcePosition = frame*ratio;
			int first = Math.min(sourceFrames-1, (int)sourcePosition);
			int second = Math.min(sourceFrames-1, first+1);
			double fraction = sourcePosition-first;
			for(int channel=0; channel<targetChannels; channel++) {
				double value = 0;
				if(channels >= targetChannels) {
					//多→少: 余ったチャンネルは均等に混ぜる(ステレオ→モノなど)
					int perTarget = channels/targetChannels;
					for(int k=0; k<perTarget; k++) {
						int c = channel*perTarget+k;
						int a = samples[first*channels+c];
						int b = samples[second*channels+c];
						value += a+(b-a)*fraction;
					}
					value /= perTarget;
				} else {
					//少→多: 同じチャンネルを繰り返す(モノ→ステレオなど)
					int c = channel%channels;
					int a = samples[first*channels+c];
					int b = samples[second*channels+c];
					value = a+(b-a)*fraction;
				}
				result[frame*targetChannels+channel] = (short)Math.round(value);
			}
		}
		return new SoundData(targetRate, targetChannels, result);
	}

	SoundData amplify(int startFrame, int endFrame, double gain) {
		short[] result = samples.clone();
		int start = clampFrame(startFrame)*channels;
		int end = Math.max(start, clampFrame(endFrame)*channels);
		for(int i=start; i<end; i++) {
			long value = Math.round(result[i]*gain);
			result[i] = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
		}
		return new SoundData(sampleRate, channels, result);
	}

	SoundData shiftPitch(int semitones) {
		if(semitones == 0) return copy();
		double ratio = Math.pow(2.0, semitones/12.0);
		int sourceFrames = frameCount();
		int resultFrames = Math.max(1, (int)Math.round(sourceFrames/ratio));
		short[] result = new short[resultFrames*channels];
		for(int frame=0; frame<resultFrames; frame++) {
			double sourcePosition = frame*ratio;
			int first = Math.min(sourceFrames-1, (int)sourcePosition);
			int second = Math.min(sourceFrames-1, first+1);
			double fraction = sourcePosition-first;
			for(int channel=0; channel<channels; channel++) {
				int a = samples[first*channels+channel];
				int b = samples[second*channels+channel];
				result[frame*channels+channel] = (short)Math.round(a+(b-a)*fraction);
			}
		}
		return new SoundData(sampleRate, channels, result);
	}

	private int clampFrame(int frame) { return Math.max(0, Math.min(frameCount(), frame)); }

	static SoundData generate(double frequency, double seconds, String wave, float sampleRate) {
		int frames = Math.max(1, (int)Math.round(seconds*sampleRate));
		short[] samples = new short[frames];
		long noiseState = 0x1234ABCDL;
		int fade = Math.min(frames/2, Math.max(1, Math.round(sampleRate*0.005F)));
		for(int i=0; i<frames; i++) {
			double phase = (i*frequency/sampleRate)%1.0;
			double value;
			if("Square".equals(wave)) value = phase < 0.5 ? 1.0 : -1.0;
			else if("Triangle".equals(wave)) value = 1.0-4.0*Math.abs(phase-0.5);
			else if("Noise".equals(wave)) {
				noiseState = (noiseState*1664525L+1013904223L)&0xFFFFFFFFL;
				value = ((noiseState>>>8)/(double)0xFFFFFF)*2.0-1.0;
			} else value = Math.sin(phase*Math.PI*2.0);
			double envelope = Math.min(1.0, Math.min((i+1)/(double)fade, (frames-i)/(double)fade));
			samples[i] = (short)Math.round(value*envelope*Short.MAX_VALUE*0.8);
		}
		return new SoundData(sampleRate, 1, samples);
	}

	byte[] pcmBytes() {
		byte[] bytes = new byte[samples.length*2];
		for(int i=0; i<samples.length; i++) {
			bytes[i*2] = (byte)samples[i];
			bytes[i*2+1] = (byte)(samples[i]>>8);
		}
		return bytes;
	}

	Clip openClip() throws Exception {
		Clip clip = AudioSystem.getClip();
		byte[] bytes = pcmBytes();
		clip.open(format(), bytes, 0, bytes.length);
		return clip;
	}

	private AudioFormat format() {
		return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels,
			channels*2, sampleRate, false);
	}

	void writeWave(File destination) throws Exception {
		File parent = destination.getParentFile();
		if(parent == null || !parent.isDirectory()) throw new IOException("Sound folder is not available");
		File temporary = File.createTempFile("hyperzebra-sound-", ".wav", parent);
		try {
			byte[] bytes = pcmBytes();
			try(AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(bytes),
				format(), frameCount())) {
				AudioSystem.write(input, AudioFileFormat.Type.WAVE, temporary);
			}
			try {
				Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch(java.nio.file.AtomicMoveNotSupportedException error) {
				Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			if(temporary.exists()) temporary.delete();
		}
	}
}

class WaveformPanel extends JPanel {
	private static final long serialVersionUID = 1L;
	private SoundData sound;
	private int anchor;
	private int selectionStart;
	private int selectionEnd;

	WaveformPanel() {
		setOpaque(true);
		setBackground(new Color(28, 31, 36));
		addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				anchor = frameAt(e.getX());
				selectionStart = anchor;
				selectionEnd = anchor;
				repaint();
			}
		});
		addMouseMotionListener(new MouseMotionAdapter() {
			@Override public void mouseDragged(MouseEvent e) {
				int frame = frameAt(e.getX());
				selectionStart = Math.min(anchor, frame);
				selectionEnd = Math.max(anchor, frame);
				repaint();
			}
		});
	}

	void setSound(SoundData sound) {
		this.sound = sound;
		selectionStart = selectionEnd = 0;
		repaint();
	}

	int selectionStart() { return selectionStart; }
	int selectionEnd() { return selectionEnd; }

	void setSelection(int start, int end) {
		if(sound == null) return;
		int frames = sound.frameCount();
		selectionStart = Math.max(0, Math.min(frames, Math.min(start, end)));
		selectionEnd = Math.max(0, Math.min(frames, Math.max(start, end)));
		repaint();
	}

	void selectAll() {
		if(sound != null) setSelection(0, sound.frameCount());
	}

	private int frameAt(int x) {
		if(sound == null || getWidth() <= 1) return 0;
		return Math.max(0, Math.min(sound.frameCount(),
			(int)Math.round(x*(double)sound.frameCount()/getWidth())));
	}

	@Override protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		if(sound == null || sound.frameCount() == 0) return;
		Graphics2D g = (Graphics2D)graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int middle = getHeight()/2;
		if(selectionEnd > selectionStart) {
			int x1 = (int)(selectionStart*(long)getWidth()/sound.frameCount());
			int x2 = (int)(selectionEnd*(long)getWidth()/sound.frameCount());
			g.setColor(new Color(55, 105, 170));
			g.fillRect(x1, 0, Math.max(1, x2-x1), getHeight());
		}
		g.setColor(new Color(95, 103, 113));
		g.drawLine(0, middle, getWidth(), middle);
		g.setColor(new Color(88, 220, 170));
		g.setStroke(new BasicStroke(1.2F));
		for(int x=0; x<getWidth(); x++) {
			int start = x*sound.frameCount()/getWidth();
			int end = Math.max(start+1, (x+1)*sound.frameCount()/getWidth());
			int peak = 0;
			for(int frame=start; frame<end && frame<sound.frameCount(); frame++) {
				for(int channel=0; channel<sound.channels; channel++)
					peak = Math.max(peak, Math.abs((int)sound.samples[frame*sound.channels+channel]));
			}
			int height = (int)(peak/(double)Short.MAX_VALUE*(middle-5));
			g.drawLine(x, middle-height, x, middle+height);
		}
		if(selectionEnd == selectionStart && selectionStart > 0) {
			//ドラッグせずクリックだけしたときの挿入位置(キャレット)
			int x = (int)(selectionStart*(long)getWidth()/sound.frameCount());
			g.setColor(new Color(255, 200, 90));
			g.drawLine(x, 0, x, getHeight());
		}
		g.setColor(Color.WHITE);
		g.drawString(String.format("%.2f s", sound.durationSeconds()), 10, 18);
		if(selectionEnd > selectionStart) {
			double begin = selectionStart/sound.sampleRate;
			double end = selectionEnd/sound.sampleRate;
			g.drawString(String.format("Selection %.3f – %.3f s", begin, end), 10, getHeight()-10);
		}
		g.dispose();
	}
}

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * "Resource Translation…" of a program (the launcher's dock and history menus): its resource strings are translated before it
 * runs (VMTranslation.Ahead), with a progress bar and the time left, and then written as its translated resources
 * (ResourceTranslation: menus, dialogs and strings in the target language, dialogs made larger where needed, kept apart
 * from the program file), which it runs with from its next launch. Not modal: the launcher can be used meanwhile; one
 * window per program. Closing or Stop keeps what was translated so far (and writes the resources from it).
 * Of a HyperZebra stack (the launcher's menus, the stack's File menu): its button names and field texts
 * (StackTranslation.ahead), and the stack's translation is turned on when it is done.
 */
final class GTranslateAheadDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	private static final Map<String, GTranslateAheadDialog> open = new HashMap<String, GTranslateAheadDialog>();

	private final String id;
	private final File app;
	/** A HyperZebra stack, not a program. */
	private final boolean stack;
	private final JLabel heading = new JLabel(" ");
	private final JProgressBar bar = new JProgressBar();
	private final JLabel detail = new JLabel(" ");
	private final JButton button = new JButton(text("Stop"));
	/** Shown when macOS's fast translation model is not downloaded (it then translates with its slow high-fidelity one). */
	private final JButton fastButton = new JButton(text("Download the Fast Model…"));
	private volatile VMTranslation.Ahead ahead;
	private volatile boolean stopped;
	/** A program's translated resources are being written. */
	private volatile boolean writing;
	private Timer timer;
	private long started;

	static void show(Frame owner, File app) {
		String id = LauncherStore.identity(app);
		GTranslateAheadDialog d = open.get(id);
		if(d==null){ d = new GTranslateAheadDialog(owner, app, id); open.put(id, d); }
		d.setVisible(true);
		d.toFront();
	}

	private GTranslateAheadDialog(Frame owner, final File app, String id) {
		super(owner, text("Resource Translation")+" - "+LauncherStore.displayName(app), false);
		this.id = id; this.app = app; this.stack = StackTranslation.isStackFile(app) || StackTranslation.openStack(app)!=null;
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));
		heading.setText(text(stack ? "Finding the stack's language…" : "Finding the program's language…"));
		bar.setIndeterminate(true);
		bar.setStringPainted(true);
		bar.setString("");
		detail.setFont(detail.getFont().deriveFont(detail.getFont().getSize2D()-1f));
		for(javax.swing.JComponent c: new javax.swing.JComponent[]{heading, bar, detail}){ c.setAlignmentX(LEFT_ALIGNMENT); body.add(c); body.add(javax.swing.Box.createVerticalStrut(8)); }
		bar.setPreferredSize(new java.awt.Dimension(380, bar.getPreferredSize().height));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		fastButton.setVisible(false);
		buttons.add(fastButton);
		buttons.add(button);
		button.addActionListener(e -> { if(finished()) dispose(); else stop(); });
		add(body, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosed(WindowEvent e){ stop(); open.remove(GTranslateAheadDialog.this.id); }
		});
		pack();
		setResizable(false);
		if(owner!=null && owner.isShowing()) setLocationRelativeTo(owner); else setLocationRelativeTo(null);

		String reason = VMTranslation.unavailableReason();
		if(reason!=null){ end(text("Not available:")+" "+text(reason), null); return; }
		Thread t = new Thread(() -> {
			try{
				PictureTranslation.Progress pictures = (done, total) -> SwingUtilities.invokeLater(() -> readingPictures(done, total));
				VMTranslation.Ahead a = stack ? StackTranslation.ahead(app, pictures) : VMTranslation.Ahead.start(app, pictures);
				SwingUtilities.invokeLater(() -> begin(a));
			}catch(Exception ex){
				SwingUtilities.invokeLater(() -> end(text(stack ? "The stack cannot be read." : "The program's resources cannot be read."), stack ? null : ex.getMessage()));
			}
		}, "Translate ahead");
		t.setDaemon(true);
		t.start();
	}

	/** Reading the text in the stack's pictures or the program's PICT resources (PictureTranslation, PictTranslation) before its texts go: picture {@code done} of {@code total}. */
	private void readingPictures(int done, int total) {
		if(finished() || ahead!=null || total==0) return;
		heading.setText(text("Reading the text in the pictures…"));
		bar.setIndeterminate(false);
		bar.setMaximum(total);
		bar.setValue(done);
		bar.setString(done+" / "+total);
	}

	private void begin(VMTranslation.Ahead a) {
		if(stopped){ a.translator.closed = true; return; }
		ahead = a;
		String into = text("Translating into {0} ({1})").replace("{0}", VMTranslation.languageName(a.to)).replace("{1}", VMTranslation.engineShortName(a.engine));
		if(a.sameLanguage()){ end(text(stack ? "The stack is already in {0}." : "The program is already in {0}.").replace("{0}", VMTranslation.languageName(a.to)), null); return; }
		if(!stack && !ResourceTranslation.supports(a.to)){ // translated only through its resources (no translation as it is shown)
			a.translator.closed = true;
			end(text("{0} cannot be written into a program's resources.").replace("{0}", VMTranslation.languageName(a.to)), null); return; }
		if(a.sent==0){
			if(!stack){ finishProgram(a, 0, false); return; }
			stackDone(); end(text("All texts are translated already."), text("{0} texts").replace("{0}", String.valueOf(a.texts))+"  "+text(SHOWN)); return; }
		heading.setText(into);
		bar.setIndeterminate(false);
		bar.setMaximum(a.sent);
		started = System.currentTimeMillis();
		timer = new Timer(250, e -> progress());
		timer.start();
		progress();
		offerFastModel(a);
	}

	/** Many times faster with macOS's low-latency model: when it can be downloaded and is not, a button asks macOS for it (the
	 *  texts still to go are translated with it once it is there). */
	private void offerFastModel(VMTranslation.Ahead a) {
		Thread t = new Thread(() -> {
			VMTranslation.Status st = VMTranslation.status(a.from, a.to);
			if(st==null || !st.fastMissing()) return;
			SwingUtilities.invokeLater(() -> {
				if(finished() || writing) return;
				fastButton.setToolTipText(text("The rest is translated many times faster once it is downloaded."));
				for(java.awt.event.ActionListener l: fastButton.getActionListeners()) fastButton.removeActionListener(l);
				fastButton.addActionListener(e -> { VMTranslation.prepare(a.from, a.to); fastButton.setEnabled(false); });
				fastButton.setVisible(true);
				pack();
			});
		}, "Translation status");
		t.setDaemon(true);
		t.start();
	}

	private void progress() {
		VMTranslation.Ahead a = ahead;
		if(a==null) return;
		int done = Math.max(0, a.sent-a.translator.waiting());
		bar.setValue(done);
		bar.setString(done+" / "+a.sent);
		if(done>=a.sent){
			int failed = a.translator.failures.get();
			VMTranslation.saveNow();
			MacApp68k running = stack ? null : MacApp68k.runningFrom(app);
			if(running!=null) running.translationChanged(); // a running copy shows the new translations
			if(failed>=a.sent && (stack || a.sent==a.texts)) end(text("Nothing could be translated. Try again later."), text("The translator in macOS did not answer."));
			else if(!stack) finishProgram(a, failed, false);
			else{
				stackDone();
				end(text("Done. The stack's buttons and fields are shown translated."),
					failed>0 ? text("{0} texts could not be translated.").replace("{0}", String.valueOf(failed))+" "+text("Run it again to translate them.") : stack ? text(SHOWN) : null);
			}
			return;
		}
		long elapsed = System.currentTimeMillis()-started;
		if(done>0 && elapsed>3000){
			long left = elapsed*(a.sent-done)/done/1000;
			detail.setText(left<60 ? text("About {0} seconds left").replace("{0}", String.valueOf(Math.max(5, (left+4)/5*5)))
				: text("About {0} minutes left").replace("{0}", String.valueOf((left+59)/60)));
		}else detail.setText(text("Estimating the time left…"));
		int failed = a.translator.failures.get();
		if(failed>0) detail.setText(detail.getText()+"  "+text("Not translated: {0}").replace("{0}", String.valueOf(failed)));
	}

	private static final String SHOWN = "File > Translation Settings… turns it off.";

	/** A stack's texts are in the cache: its translation is turned on (an open stack shows them at once). */
	private void stackDone() {
		if(stack) StackTranslation.setOn(app, true);
	}

	private boolean finished() { return text("Close").equals(button.getText()); }

	private void end(String message, String more) {
		if(timer!=null) timer.stop();
		heading.setText(message);
		detail.setText(more==null ? " " : more);
		if(bar.isIndeterminate()){ bar.setIndeterminate(false); bar.setValue(bar.getMaximum()); bar.setString(""); }
		button.setText(text("Close"));
		fastButton.setVisible(false);
		pack();
	}

	/** Stops sending texts; the batch with the translator still comes back and is kept, with everything translated before. */
	private void stop() {
		if(finished() && ahead==null || writing) return;
		stopped = true;
		VMTranslation.Ahead a = ahead;
		if(a==null && !finished() && isDisplayable()){ end(text("Stopped."), null); return; }
		if(a!=null && !finished()){
			a.translator.closed = true;
			VMTranslation.later(0, VMTranslation::saveNow);
			int done = Math.max(0, a.sent-a.translator.waiting());
			if(!stack){
				if(isDisplayable()) finishProgram(a, 0, true);
				else VMTranslation.later(0, () -> { try{ if(a.makeResources().file!=null) ResourceTranslation.use(app); }catch(java.io.IOException|RuntimeException ignored){} }); // closed: written all the same
				return;
			}
			if(isDisplayable()){
				end(text("Stopped. What was translated is kept."), done+" / "+a.sent);
			}
		}
	}

	/**
	 * A program's texts are translated (or as many as were before Stop): its translated resources are written from them on
	 * another thread, and the program is set to run with them.
	 */
	private void finishProgram(VMTranslation.Ahead a, int failed, boolean stoppedEarly) {
		if(timer!=null) timer.stop();
		writing = true; ahead = null;
		heading.setText(text("Writing the translated resources…"));
		bar.setIndeterminate(true); bar.setString("");
		button.setEnabled(false);
		Thread t = new Thread(() -> {
			ResourceTranslation.Built built = null; String error = null;
			try{ VMTranslation.saveNow(); built = a.makeResources(); }
			catch(java.io.IOException|RuntimeException ex){ error = ex.getMessage()==null ? ex.toString() : ex.getMessage(); }
			final ResourceTranslation.Built b = built; final String e = error;
			SwingUtilities.invokeLater(() -> programDone(a, b, e, failed, stoppedEarly));
		}, "Translated resources");
		t.setDaemon(true);
		t.start();
	}

	private void programDone(VMTranslation.Ahead a, ResourceTranslation.Built b, String error, int failed, boolean stoppedEarly) {
		button.setEnabled(true);
		writing = false;
		if(error!=null){ end(text("The translated resources could not be written."), error); return; }
		java.util.List<String> notes = new java.util.ArrayList<String>();
		String heading;
		if(b.unsupported!=null){
			heading = text("{0} cannot be written into a program's resources.").replace("{0}", VMTranslation.languageName(b.unsupported));
		}else if(b.file==null){
			heading = text("There was nothing to translate into its resources.");
		}else{
			ResourceTranslation.use(app);
			heading = text(stoppedEarly ? "Stopped. The program runs with what is translated from its next launch." : "Done. The program runs with its translated resources from its next launch.");
			notes.add(text("{0} resources were translated ({1} texts). The program file itself is not changed.").replace("{0}", String.valueOf(b.resources)).replace("{1}", String.valueOf(b.texts)));
			if(b.grown>0) notes.add(text("{0} dialogs were made larger for the translation.").replace("{0}", String.valueOf(b.grown)));
			if(b.needsLanguage!=null) notes.add(text("They need a {0} System: set its System language to Standard or {0} in its Application Settings.").replace("{0}", text(b.needsLanguage.equals("Japanese") ? "Japanese (KanjiTalk)" : "English (U.S.)")));
			notes.add(text("To run it with its own resources again, turn Translated resources off in its Application Settings."));
		}
		if(failed>0) notes.add(text("{0} texts could not be translated.").replace("{0}", String.valueOf(failed))+" "+text("Run it again to translate them."));
		MacApp68k running = MacApp68k.runningFrom(app);
		if(running!=null){
			running.translationChanged(); // a running copy shows the new translations as it draws them
			if(b.file!=null) notes.add(text("The running program uses them from its next launch."));
		}
		end(heading, notes.isEmpty() ? null : GPreferencesDialog.wrap(String.join(" ", notes)));
	}

	private static String text(String key){ return GPreferencesDialog.dialogText(key); }
}

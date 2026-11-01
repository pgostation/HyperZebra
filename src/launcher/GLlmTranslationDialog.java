import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * "Resource Translation with AI Chat…" of a program or a stack (LlmTranslation): a request with the texts as JSON to copy
 * into ChatGPT or another AI chat, and a box the JSON it answers is pasted into. A long program goes in parts, chosen
 * in the pop-up (the next part is shown once one is done). When every text is translated - or with the button, with
 * what is translated so far - a program's translated resources are written (it runs with them from its next launch),
 * a stack is shown translated. Not modal; one window per program or stack. Works without the translators of macOS.
 */
final class GLlmTranslationDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	private static final Map<String, GLlmTranslationDialog> open = new HashMap<String, GLlmTranslationDialog>();
	/** The part size chosen last (this session). */
	private static int sizeChoice = 1;
	private static final int[] SIZES = {LlmTranslation.SMALL, LlmTranslation.MEDIUM, LlmTranslation.LARGE};
	/** The width the explanations and messages wrap at (CSS px: Swing draws them about a third wider). */
	private static final int TEXT_WIDTH = 460;

	private final String id;
	private final File file;
	private final boolean stack;
	private final JComboBox<String> fromCombo, toCombo, sizeCombo, partCombo = new JComboBox<String>();
	private final JCheckBox againBox = new JCheckBox(International.t("Also texts translated already"));
	private final JTextArea request = new JTextArea(), answer = new JTextArea();
	private final JLabel requestInfo = new JLabel(" "), status = new JLabel(" "), copied = new JLabel(" ");
	private final JButton copyButton = new JButton(International.t("Copy")), pasteButton = new JButton(International.t("Paste and Apply")),
		applyButton = new JButton(International.t("Apply")), finishButton, closeButton = new JButton(International.t("Close"));
	private LlmTranslation session;
	private List<List<LlmTranslation.Item>> parts = new ArrayList<List<LlmTranslation.Item>>();
	/** Translations kept since the resources were last written (or the stack turned on). */
	private int unwritten;
	private boolean busy, filling;
	/** Counts the collections started: one finishing after another was started is dropped. */
	private int generation;

	static void show(Frame owner, File file) {
		String id = LauncherStore.identity(file);
		GLlmTranslationDialog d = open.get(id);
		if(d==null){ d = new GLlmTranslationDialog(owner, file, id); open.put(id, d); }
		d.setVisible(true);
		d.toFront();
	}

	private GLlmTranslationDialog(Frame owner, File file, String id) {
		super(owner, International.t("Resource Translation with AI Chat")+" - "+LauncherStore.displayName(file), false);
		this.id = id; this.file = file;
		stack = StackTranslation.isStackFile(file) || StackTranslation.openStack(file)!=null;
		finishButton = new JButton(International.t(stack ? "Show the Translation" : "Write the Translated Resources"));
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBorder(BorderFactory.createEmptyBorder(12, 16, 4, 16));
		JLabel intro = new JLabel(html(International.t("Translate with an AI chat such as ChatGPT, Claude or Gemini: 1. Copy the request and paste it into the chat. 2. Copy the JSON the chat answers, paste it below and click Apply. A long program is split into parts: do them one by one (a new chat for each part is fine)."), TEXT_WIDTH));
		intro.setAlignmentX(LEFT_ALIGNMENT);
		north.add(intro);
		north.add(Box.createVerticalStrut(8));

		JPanel options = new JPanel(new GridBagLayout());
		options.setAlignmentX(LEFT_ALIGNMENT);
		GridBagConstraints c = new GridBagConstraints();
		c.anchor = GridBagConstraints.WEST; c.insets = new Insets(2, 0, 2, 6);
		String[] targetNames = new String[VMTranslation.TARGETS.length];
		for(int k=0; k<targetNames.length; k++) targetNames[k] = VMTranslation.languageName(VMTranslation.TARGETS[k]);
		String[] fromNames = new String[targetNames.length+1];
		fromNames[0] = International.t(stack ? "Automatic (the stack's language)" : "Automatic (the program's language)");
		System.arraycopy(targetNames, 0, fromNames, 1, targetNames.length);
		fromCombo = new JComboBox<String>(fromNames);
		toCombo = new JComboBox<String>(targetNames);
		VMTranslation.Config cfg = stack ? StackTranslation.configFor(file) : VMTranslation.settingsFor(file);
		java.util.List<String> targets = java.util.Arrays.asList(VMTranslation.TARGETS);
		fromCombo.setSelectedIndex(VMTranslation.AUTO.equals(cfg.from) || targets.indexOf(cfg.from)<0 ? 0 : targets.indexOf(cfg.from)+1);
		toCombo.setSelectedIndex(Math.max(0, targets.indexOf(cfg.to)));
		sizeCombo = new JComboBox<String>(new String[]{
			International.t("Small (for free plans)"), International.t("Standard"), International.t("Large (fewer parts)")});
		sizeCombo.setSelectedIndex(sizeChoice);
		c.gridx = 0; options.add(new JLabel(International.t("From:")), c);
		c.gridx++; options.add(fromCombo, c);
		c.gridx++; options.add(new JLabel(International.t("Translate into:")), c);
		c.gridx++; options.add(toCombo, c);
		c.gridx = 0; c.gridy = 1; options.add(new JLabel(International.t("Part size:")), c);
		c.gridx++; options.add(sizeCombo, c);
		c.gridx++; c.gridwidth = 2; options.add(againBox, c);
		north.add(options);

		// the request of the chosen part, and the answer pasted
		request.setEditable(false);
		for(JTextArea a: new JTextArea[]{request, answer}){
			a.setLineWrap(true); a.setWrapStyleWord(true);
			a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(11, a.getFont().getSize()-1)));
		}
		JPanel top = new JPanel(new BorderLayout(0, 4));
		JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		topBar.add(new JLabel(International.t("Request:")));
		topBar.add(partCombo);
		topBar.add(copyButton);
		topBar.add(copied);
		top.add(topBar, BorderLayout.NORTH);
		top.add(new JScrollPane(request), BorderLayout.CENTER);
		top.add(requestInfo, BorderLayout.SOUTH);
		JPanel bottomPane = new JPanel(new BorderLayout(0, 4));
		JPanel answerBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		answerBar.add(new JLabel(International.t("The AI chat's answer (JSON):")));
		answerBar.add(pasteButton);
		answerBar.add(applyButton);
		bottomPane.add(answerBar, BorderLayout.NORTH);
		bottomPane.add(new JScrollPane(answer), BorderLayout.CENTER);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottomPane);
		split.setResizeWeight(0.6);
		split.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));

		JPanel south = new JPanel(new BorderLayout(0, 6));
		south.setBorder(BorderFactory.createEmptyBorder(4, 16, 10, 16));
		south.add(status, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.add(finishButton);
		buttons.add(closeButton);
		south.add(buttons, BorderLayout.SOUTH);

		add(north, BorderLayout.NORTH);
		add(split, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		copyButton.addActionListener(e -> copy());
		pasteButton.addActionListener(e -> pasteAndApply());
		applyButton.addActionListener(e -> apply(answer.getText()));
		finishButton.addActionListener(e -> finish(false));
		closeButton.addActionListener(e -> dispose());
		partCombo.addActionListener(e -> { if(!filling) showPart(); });
		sizeCombo.addActionListener(e -> { sizeChoice = sizeCombo.getSelectedIndex(); makeParts(0); });
		againBox.addActionListener(e -> restart());
		fromCombo.addActionListener(e -> { storeLanguages(); restart(); });
		toCombo.addActionListener(e -> { storeLanguages(); restart(); });
		addWindowListener(new WindowAdapter(){
			@Override public void windowClosed(WindowEvent e){
				open.remove(GLlmTranslationDialog.this.id);
				if(unwritten>0 && session!=null && !busy) finishQuietly(session); // what was translated is not lost
			}
		});

		setSize(new Dimension(720, 720));
		setMinimumSize(new Dimension(640, 480));
		if(owner!=null && owner.isShowing()) setLocationRelativeTo(owner); else setLocationRelativeTo(null);
		restart();
	}

	private static String html(String text, int width) {
		return "<html><div style='width:"+width+"px'>"+text.replace("&","&amp;").replace("<","&lt;")+"</div></html>";
	}

	/** The languages chosen here become the program's or stack's own translation settings (its translated resources go by them). */
	private void storeLanguages() {
		if(!LauncherStore.enabled) return;
		LauncherStore.AppSettings s = LauncherStore.get().settingsFor(file);
		if(s==null) s = new LauncherStore.AppSettings();
		int i = fromCombo.getSelectedIndex();
		s.translateFrom = i==0 ? null : VMTranslation.TARGETS[i-1];
		String to = VMTranslation.TARGETS[toCombo.getSelectedIndex()];
		s.translateTo = to.equals(PCARD.translateTo68k) ? null : to;
		LauncherStore.get().putSettings(file, s);
		if(stack) StackTranslation.settingsChanged();
	}

	/** Collects the texts anew (another language, or with the texts translated already) on another thread. */
	private void restart() {
		if(session!=null && unwritten>0) finishQuietly(session);
		session = null; unwritten = 0;
		parts = new ArrayList<List<LlmTranslation.Item>>();
		setBusy(true);
		filling = true; partCombo.removeAllItems(); filling = false;
		request.setText(""); requestInfo.setText(" ");
		status.setForeground(null);
		status.setText(International.t(stack ? "Finding the stack's language…" : "Finding the program's language…"));
		final boolean again = againBox.isSelected();
		final int gen = ++generation;
		Thread t = new Thread(() -> {
			LlmTranslation s = null; String error = null;
			try{ s = LlmTranslation.start(file, again, (done, total) -> SwingUtilities.invokeLater(() -> {
				if(gen==generation && session==null && total>0) status.setText(International.t("Reading the text in the pictures…")+" "+done+" / "+total); })); }
			catch(Exception ex){ error = ex.getMessage(); }
			final LlmTranslation got = s; final String e = error;
			SwingUtilities.invokeLater(() -> { if(gen==generation) started(got, e); });
		}, "AI chat translation");
		t.setDaemon(true);
		t.start();
	}

	private void started(LlmTranslation s, String error) {
		if(!isDisplayable()) return;
		setBusy(false);
		if(s==null){ problem(International.t(stack ? "The stack cannot be read." : "The program's resources cannot be read.")+(error==null ? "" : " "+error)); return; }
		VMTranslation.Ahead a = s.ahead;
		if(a.sameLanguage()){ problem(International.t(stack ? "The stack is already in {0}." : "The program is already in {0}.", VMTranslation.languageName(a.to))); return; }
		if(!stack && !ResourceTranslation.supports(a.to)){ problem(International.t("{0} cannot be written into a program's resources.", VMTranslation.languageName(a.to))); return; }
		session = s;
		if(fromCombo.getSelectedIndex()==0) fromCombo.setToolTipText(VMTranslation.languageName(a.from));
		if(s.items.isEmpty()){
			problem(International.t("All texts are translated already.")+" "+International.t("To translate them anew, check \"Also texts translated already\"."));
			status.setForeground(null);
			return;
		}
		makeParts(0);
	}

	/** Splits the texts at the chosen size and shows the first part not done from {@code from} on. */
	private void makeParts(int from) {
		if(session==null) return;
		parts = session.parts(SIZES[sizeCombo.getSelectedIndex()]);
		refreshParts(from);
	}

	private void refreshParts(int from) {
		filling = true;
		partCombo.removeAllItems();
		int first = -1;
		for(int i=0; i<parts.size(); i++){
			int left = session.left(parts.get(i)).size();
			String name = International.t("Part {0} of {1}", String.valueOf(i+1), String.valueOf(parts.size()));
			partCombo.addItem(left==0 ? name+" ✓" : name+"  ("+International.t("{0} texts", String.valueOf(left))+")");
			if(first<0 && i>=from && left>0) first = i;
		}
		if(first<0) for(int i=0; i<parts.size(); i++) if(session.left(parts.get(i)).size()>0){ first = i; break; }
		filling = false;
		partCombo.setSelectedIndex(first<0 ? 0 : first);
		showPart();
		progress();
	}

	private void showPart() {
		int i = partCombo.getSelectedIndex();
		if(session==null || i<0 || i>=parts.size()){ request.setText(""); return; }
		List<LlmTranslation.Item> left = session.left(parts.get(i));
		if(left.isEmpty()){
			request.setText(International.t("This part is done."));
			requestInfo.setText(" ");
			copyButton.setEnabled(false);
		}else{
			request.setText(session.request(parts.get(i), i+1, parts.size()));
			requestInfo.setText(International.t("{0} texts, {1} characters", String.valueOf(left.size()), String.valueOf(request.getText().length())));
			copyButton.setEnabled(!busy);
		}
		request.setCaretPosition(0);
		copied.setText(" ");
	}

	private void copy() {
		String text = request.getText();
		if(text.isEmpty()) return;
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		copied.setText(International.t("Copied. Paste it into the AI chat."));
		Timer t = new Timer(4000, e -> copied.setText(" "));
		t.setRepeats(false);
		t.start();
	}

	private void pasteAndApply() {
		String text = null;
		try{ text = (String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor); }
		catch(Exception ignored){}
		if(text==null || text.isBlank()){ problem(International.t("The clipboard has no text. Copy the AI chat's answer first.")); return; }
		answer.setText(text);
		answer.setCaretPosition(0);
		apply(text);
	}

	private void apply(String text) {
		if(session==null || busy) return;
		LlmTranslation.Result r = session.apply(text);
		if(r.noJson){ problem(International.t("No translations were found in it. Paste the JSON the AI chat answered (its code block).")); return; }
		unwritten += r.kept;
		List<String> notes = new ArrayList<String>();
		notes.add(International.t("{0} translations kept.", String.valueOf(r.kept)));
		if(!r.problems.isEmpty()){
			StringBuilder b = new StringBuilder();
			for(int k=0; k<Math.min(3, r.problems.size()); k++) b.append(k>0 ? "; " : "").append(r.problems.get(k));
			if(r.problems.size()>3) b.append("; …");
			notes.add(International.t("{0} could not be used ({1}): copy the part again to ask for them anew.", String.valueOf(r.problems.size()), b.toString()));
		}
		if(r.unknown>0) notes.add(International.t("{0} ids were not in the request.", String.valueOf(r.unknown)));
		int i = Math.max(0, partCombo.getSelectedIndex());
		if(session.allDone()){ finish(true); return; }
		boolean partDone = i<parts.size() && session.left(parts.get(i)).isEmpty();
		if(partDone){
			answer.setText("");
			refreshParts(i+1);
			notes.add(International.t("Next: copy part {0}.", String.valueOf(partCombo.getSelectedIndex()+1)));
		}else{
			refreshParts(i);
			if(r.problems.isEmpty() && r.kept>0) notes.add(International.t("Some texts of this part are still missing: copy the part again for them."));
		}
		status.setForeground(r.kept==0 ? warning() : null);
		status.setText(html(String.join(" ", notes)+" "+progressText(), TEXT_WIDTH));
	}

	private String progressText() {
		return session==null ? "" : International.t("Translated: {0} / {1}", String.valueOf(session.doneCount()), String.valueOf(session.items.size()));
	}

	private void progress() {
		status.setForeground(null);
		status.setText(html(progressText(), TEXT_WIDTH));
		finishButton.setEnabled(session!=null && unwritten>0 && !busy);
	}

	private void problem(String message) {
		status.setForeground(warning());
		status.setText(html(message, TEXT_WIDTH));
	}

	private static Color warning() { return new Color(0xb0, 0x40, 0x00); }

	private void setBusy(boolean b) {
		busy = b;
		for(javax.swing.JComponent x: new javax.swing.JComponent[]{copyButton, pasteButton, applyButton, finishButton, partCombo, sizeCombo, againBox, fromCombo, toCombo})
			x.setEnabled(!b);
		if(!b) finishButton.setEnabled(session!=null && unwritten>0);
	}

	/** Writes a program's translated resources (or turns the stack's translation on) on another thread and says what came of it. */
	private void finish(boolean all) {
		final LlmTranslation s = session;
		if(s==null) return;
		setBusy(true);
		status.setForeground(null);
		status.setText(International.t(stack ? "Turning the stack's translation on…" : "Writing the translated resources…"));
		Thread t = new Thread(() -> {
			LlmTranslation.Finished f = null; String error = null;
			try{ f = s.finish(); }
			catch(java.io.IOException|RuntimeException ex){ error = ex.getMessage()==null ? ex.toString() : ex.getMessage(); }
			final LlmTranslation.Finished done = f; final String e = error;
			SwingUtilities.invokeLater(() -> finished(s, done, e, all));
		}, "Translated resources");
		t.setDaemon(true);
		t.start();
	}

	private void finished(LlmTranslation s, LlmTranslation.Finished f, String error, boolean all) {
		if(!isDisplayable()) return;
		setBusy(false);
		if(error!=null){ problem(International.t("The translated resources could not be written.")+" "+error); return; }
		unwritten = 0;
		finishButton.setEnabled(false);
		refreshParts(0);
		List<String> notes = new ArrayList<String>();
		if(all) notes.add(International.t("All texts are translated."));
		if(stack){
			notes.add(International.t("Done. The stack's buttons and fields are shown translated."));
			notes.add(International.t("File > Translation Settings… turns it off."));
		}else if(f.built.unsupported!=null){
			notes.add(International.t("{0} cannot be written into a program's resources.", VMTranslation.languageName(f.built.unsupported)));
		}else if(f.built.file==null){
			notes.add(International.t("There was nothing to translate into its resources."));
		}else{
			notes.add(International.t("Done. The program runs with its translated resources from its next launch."));
			notes.add(International.t("{0} resources were translated ({1} texts). The program file itself is not changed.", String.valueOf(f.built.resources), String.valueOf(f.built.texts)));
			if(f.built.grown>0) notes.add(International.t("{0} dialogs were made larger for the translation.", String.valueOf(f.built.grown)));
			if(f.built.needsLanguage!=null) notes.add(International.t("They need a {0} System: set its System language to Standard or {0} in its Application Settings.",
				International.t(f.built.needsLanguage.equals("Japanese") ? "Japanese (KanjiTalk)" : "English (U.S.)")));
			if(MacApp68k.runningFrom(file)!=null) notes.add(International.t("The running program uses them from its next launch."));
		}
		if(!s.allDone()) notes.add(progressText());
		status.setForeground(null);
		status.setText(html(String.join(" ", notes), TEXT_WIDTH));
	}

	/** The window closed with translations not written yet: they are written all the same. */
	private static void finishQuietly(LlmTranslation s) {
		Thread t = new Thread(() -> { try{ s.finish(); }catch(java.io.IOException|RuntimeException ignored){} }, "Translated resources");
		t.setDaemon(true);
		t.start();
	}
}

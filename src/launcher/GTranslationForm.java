import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The translation settings (the launcher's Translation page; VMTranslation, StackTranslation): the language programs
 * ("Resource Translation…") and stacks (File > Translation Settings…) are translated into, the engine (named by what it is good for, with when to choose it under it), and what this computer can do - "not available" with the reason on a computer without the translators,
 * a language that still has to be downloaded (with a button for macOS's download sheet), Apple Intelligence turned off.
 */
final class GTranslationForm extends JPanel {
	private static final long serialVersionUID = 1L;
	final JComboBox<String> targetCombo, engineCombo;
	private final JLabel status = new JLabel(" ");
	/** When to choose the selected engine. */
	private final JLabel engineHelp = new JLabel(" ");
	private final JButton download = new JButton(GPreferencesDialog.dialogText("Download Languages…"));
	private final String[] targets = VMTranslation.TARGETS;

	GTranslationForm() {
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
		String reason = VMTranslation.unavailableReason();

		//翻訳はMacアプリだけのものではない(スタックも翻訳表示できる)ので、Macアプリ設定とは別のページ。
		//プログラムはリソース翻訳(「リソース翻訳…」)、スタックはボタン名とフィールドの表示。ここは両方の既定値(個別設定で上書きできる)
		JLabel intro = new JLabel(GPreferencesDialog.wrap(GPreferencesDialog.dialogText("Programs are translated by Resource Translation… (their menus, dialogs and strings); stacks show their button names and fields translated (File > Translation Settings…). These are the defaults; a program or stack can have its own.")));
		intro.setBorder(BorderFactory.createEmptyBorder(16, 16, 4, 16));
		intro.setAlignmentX(LEFT_ALIGNMENT);
		add(intro);

		JPanel row = GPreferencesDialog.row();
		row.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
		row.add(new JLabel(GPreferencesDialog.dialogText("Translate into:")));
		String[] names = new String[targets.length];
		int selected = 0;
		for(int i=0; i<targets.length; i++){ names[i] = VMTranslation.languageName(targets[i]); if(targets[i].equals(PCARD.translateTo68k)) selected = i; }
		targetCombo = new JComboBox<String>(names);
		targetCombo.setSelectedIndex(selected);
		row.add(targetCombo);
		add(row);

		//方式は選択肢の名前で「何がよくなるか」を示し、選んだ方式をいつ選ぶかを下に常に出す
		row = GPreferencesDialog.row();
		row.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
		row.add(new JLabel(GPreferencesDialog.dialogText("Engine:")));
		String[] engines = new String[VMTranslation.ENGINES.length];
		int engine = 0;
		for(int i=0; i<engines.length; i++){ engines[i] = VMTranslation.engineLabel(VMTranslation.ENGINES[i]); if(VMTranslation.ENGINES[i].equals(PCARD.translateEngine68k)) engine = i; }
		engineCombo = new JComboBox<String>(engines);
		engineCombo.setSelectedIndex(engine);
		row.add(engineCombo);
		add(row);
		JPanel helpRow = GPreferencesDialog.row();
		helpRow.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
		engineHelp.setFont(engineHelp.getFont().deriveFont(engineHelp.getFont().getSize2D()-1f));
		helpRow.add(engineHelp);
		add(helpRow);
		showEngineHelp();
		engineCombo.addActionListener(e -> showEngineHelp());

		JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT)) {
			private static final long serialVersionUID = 1L;
			@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
		};
		statusRow.setAlignmentX(LEFT_ALIGNMENT);
		statusRow.setBorder(BorderFactory.createEmptyBorder(0, 16, 8, 16));
		status.setFont(status.getFont().deriveFont(status.getFont().getSize2D()-1f));
		statusRow.add(status);
		download.setVisible(false);
		download.addActionListener(e -> {
			String to = targets[targetCombo.getSelectedIndex()];
			VMTranslation.prepare(to.startsWith("en") ? "ja" : "en", to);
		});
		statusRow.add(download);
		add(statusRow);

		if(reason!=null){
			engineCombo.setEnabled(false); // the language still counts for Resource Translation with AI Chat…
			showStatus(GPreferencesDialog.wrap(GPreferencesDialog.dialogText("Not available:")+" "+GPreferencesDialog.dialogText(reason)+" "+International.t("Resource Translation with AI Chat… works without it.")), true);
		}else{
			ActionListener refresh = e -> refreshStatus();
			targetCombo.addActionListener(refresh); engineCombo.addActionListener(refresh);
			refreshStatus();
		}
	}

	/** Calls {@code l} when anything changes (the live form of the launcher stores at once). */
	void addChangeListener(ActionListener l) { targetCombo.addActionListener(l); engineCombo.addActionListener(l); }

	void apply() {
		boolean on = PCARD.translate68k; // not shown: a program uses its translated resources when its own setting turns them on
		String to = targets[targetCombo.getSelectedIndex()], engine = VMTranslation.unavailableReason()!=null ? PCARD.translateEngine68k : VMTranslation.ENGINES[engineCombo.getSelectedIndex()];
		if(on!=PCARD.translate68k || !to.equals(PCARD.translateTo68k) || !engine.equals(PCARD.translateEngine68k)) PCARD.setTranslation68k(on, to, engine);
	}

	private void showEngineHelp() {
		engineHelp.setText(GPreferencesDialog.wrap(VMTranslation.engineHelp(VMTranslation.ENGINES[engineCombo.getSelectedIndex()])));
	}

	private int refreshes;
	/** What the translators can do for the chosen language and engine, asked of the helper on another thread. */
	private void refreshStatus() {
		final int ticket = ++refreshes;
		final String to = targets[targetCombo.getSelectedIndex()], engine = VMTranslation.ENGINES[engineCombo.getSelectedIndex()];
		showStatus(GPreferencesDialog.dialogText("Checking…"), false);
		Thread t = new Thread(() -> {
			VMTranslation.Status s = VMTranslation.status(to.startsWith("en") ? "ja" : "en", to);
			SwingUtilities.invokeLater(() -> { if(ticket==refreshes) showStatus(s, to, engine); });
		}, "Translation status");
		t.setDaemon(true); t.start();
	}
	private void showStatus(VMTranslation.Status s, String to, String engine) {
		download.setVisible(false);
		if(s==null){ showStatus(GPreferencesDialog.dialogText("The translation helper does not answer."), true); return; }
		StringBuilder b = new StringBuilder();
		boolean problem = false;
		String language = VMTranslation.languageName(to);
		switch(s.translation){
			case "installed":
				b.append(language).append(": ").append(GPreferencesDialog.dialogText("ready"));
				if(s.fastMissing()){ b.append(" (").append(GPreferencesDialog.dialogText("the fast model is not downloaded: translating is slow")).append(")"); download.setVisible(true); problem = true; }
				break;
			case "supported": b.append(language).append(": ").append(GPreferencesDialog.dialogText("the language has to be downloaded")); download.setVisible(true); problem = true; break;
			default: b.append(language).append(": ").append(GPreferencesDialog.dialogText("not supported by the translator")); problem = true; break;
		}
		if(!engine.equals(VMTranslation.ENGINE_TRANSLATION)){
			b.append(" / Apple Intelligence: ");
			switch(s.llm){
				case "available": b.append(GPreferencesDialog.dialogText("ready")); break;
				case "appleIntelligenceNotEnabled": b.append(GPreferencesDialog.dialogText("turned off in System Settings (the translation model is used)")); problem = true; break;
				case "deviceNotEligible": b.append(GPreferencesDialog.dialogText("not available on this Mac (the translation model is used)")); problem = true; break;
				case "modelNotReady": b.append(GPreferencesDialog.dialogText("still downloading (the translation model is used)")); problem = true; break;
				default: b.append(GPreferencesDialog.dialogText("not available (the translation model is used)")); problem = true; break;
			}
		}
		showStatus(b.toString(), problem);
	}
	private void showStatus(String text, boolean problem) {
		status.setText(text);
		status.setForeground(problem ? new Color(0xb0, 0x40, 0x00) : Color.GRAY);
	}
}

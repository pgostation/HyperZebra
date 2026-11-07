import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * One program's or one stack's own translation settings (VMTranslation, ResourceTranslation, StackTranslation): the
 * language it is translated into, the language it is written in (automatic: found from its texts), the engine - each
 * either "Follow the translation settings" (the launcher's Translation page) or its own - and
 * <ul>
 * <li>a stack: whether its button names and fields are shown translated, and the text in its pictures (PictureTranslation);</li>
 * <li>a program: whether it runs with its translated resources (made by "Resource Translation…") and deleting them, and
 * whether Resource Translation… translates the text in its PICT resources (PictTranslation).</li>
 * </ul>
 * "Resource Translation…" (macOS's translators) and "AI Chat…" (GLlmTranslationDialog) at the bottom store the settings and
 * start it. Opened from the launcher's menus of a program or
 * stack, the Translation page, and a stack's File menu. Stored in LauncherStore like the Application Settings.
 */
final class GTranslationSettingsDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	static final String FOLLOW_TRANSLATION = "Follow the translation settings";
	private final File file;
	private final boolean stack;
	private final LauncherStore.AppSettings current;
	/** A stack's "Show translation" and "Text in pictures" (null for a program). */
	private JComboBox<String> showCombo, picturesCombo;
	private final JComboBox<String> toCombo, fromCombo, engineCombo;
	/** A program's translated resources: use / don't use; null when it has none. */
	private JComboBox<String> resourcesCombo;
	private boolean ok, ahead, chat;

	private GTranslationSettingsDialog(Window owner, File file, LauncherStore.AppSettings current) {
		super(owner, GPreferencesDialog.dialogText("Translation Settings")+" - "+LauncherStore.displayName(file), ModalityType.APPLICATION_MODAL);
		this.file = file; this.current = current;
		stack = StackTranslation.isStackFile(file) || !MacApp68k.isApplication(file);
		String unavailable = VMTranslation.unavailableReason();
		boolean mac = VMTranslation.osHasTranslators(); // elsewhere the engine and Resource Translation… are not shown at all
		getContentPane().setLayout(new BorderLayout());
		JPanel grid = new JPanel(new GridBagLayout());
		grid.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
		getContentPane().add(grid, BorderLayout.CENTER);
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 4, 4);

		JLabel intro = new JLabel(GPreferencesDialog.wrap(GPreferencesDialog.dialogText(stack
			? "The stack's button names and fields are shown translated; what scripts read and what is saved stays as it is."
			: mac ? "The program runs with translated copies of its menus, dialogs and strings, made by Resource Translation…; the program file is never changed."
			: "The program runs with translated copies of its menus, dialogs and strings, made by Resource Translation with AI Chat…; the program file is never changed.")));
		c.gridwidth = 2; grid.add(intro, c); c.gridwidth = 1; c.gridy++;

		if(stack){
			String on = GPreferencesDialog.dialogText("On"), off = GPreferencesDialog.dialogText("Off");
			showCombo = GAppSettingsDialog.plainCombo(new String[]{off, on});
			showCombo.setSelectedIndex(Boolean.TRUE.equals(current.translate) ? 1 : 0);
			GAppSettingsDialog.addRow(grid, c, "Show translation:", showCombo, "(at once)");
		}
		{
			//絵の中の文字: スタックはカード・背景のピクチャとPICTをTesseractで読んで訳した写しを表示する(PictureTranslation)。
			//プログラムはPICTリソースを「リソース翻訳…」で訳したリソースに入れる(PictTranslation)。どちらも元の絵は変わらない
			picturesCombo = GAppSettingsDialog.plainCombo(new String[]{International.t("Translate (read with Tesseract)"), International.t("Don't translate")});
			picturesCombo.setSelectedIndex(Boolean.FALSE.equals(current.translatePictures) ? 1 : 0);
			JLabel pictureNote = GAppSettingsDialog.addRow(grid, c, "Text in pictures:", picturesCombo, "Checking…");
			Thread t = new Thread(() -> {
				String[] s = PictureTranslation.status(false);
				javax.swing.SwingUtilities.invokeLater(() -> {
					pictureNote.setText(GPreferencesDialog.wrap(s[0]!=null ? s[0] : International.t(stack ? "(the pictures stay as they are: a translated copy is shown)" : mac ? "(its PICT resources, by Resource Translation…; the program file is never changed)" : "(its PICT resources, by Resource Translation with AI Chat…; the program file is never changed)")));
					if(s[0]!=null) pictureNote.setForeground(new java.awt.Color(0xb0, 0x40, 0x00));
					Dimension p = getPreferredSize();
					setSize(Math.max(getWidth(), p.width), Math.max(getHeight(), p.height));
				});
			}, "Tesseract status");
			t.setDaemon(true); t.start();
		}
		String[] targetNames = new String[VMTranslation.TARGETS.length];
		for(int k=0; k<targetNames.length; k++) targetNames[k] = VMTranslation.languageName(VMTranslation.TARGETS[k]);
		java.util.List<String> targets = java.util.Arrays.asList(VMTranslation.TARGETS), engines = java.util.Arrays.asList(VMTranslation.ENGINES);
		toCombo = GAppSettingsDialog.followCombo(FOLLOW_TRANSLATION, targetNames, VMTranslation.languageName(PCARD.translateTo68k),
			current.translateTo==null ? -1 : targets.indexOf(current.translateTo));
		GAppSettingsDialog.addRow(grid, c, "Translate into:", toCombo, null);
		fromCombo = GAppSettingsDialog.followCombo(stack ? "Automatic (the stack's language)" : "Automatic (the program's language)", targetNames, null,
			current.translateFrom==null || VMTranslation.AUTO.equals(current.translateFrom) ? -1 : targets.indexOf(current.translateFrom));
		GAppSettingsDialog.addRow(grid, c, "From:", fromCombo, null);
		String[] engineNames = new String[VMTranslation.ENGINES.length];
		for(int k=0; k<engineNames.length; k++) engineNames[k] = VMTranslation.engineLabel(VMTranslation.ENGINES[k]);
		engineCombo = GAppSettingsDialog.followCombo(FOLLOW_TRANSLATION, engineNames, VMTranslation.engineLabel(PCARD.translateEngine68k),
			current.translateEngine==null ? -1 : engines.indexOf(current.translateEngine));
		if(mac) GAppSettingsDialog.addRow(grid, c, "Engine:", engineCombo, null);
		if(!mac) engineCombo.setEnabled(false); // not stored
		else if(unavailable==null){
			//選んだ方式(「翻訳設定に従う」ならその方式)をいつ選ぶか
			JLabel engineHelp = GAppSettingsDialog.note("");
			Runnable showHelp = () -> { int k = engineCombo.getSelectedIndex();
				engineHelp.setText(GPreferencesDialog.wrap(VMTranslation.engineHelp(k==0 ? PCARD.translateEngine68k : VMTranslation.ENGINES[k-1]))); };
			showHelp.run(); engineCombo.addActionListener(e -> showHelp.run());
			c.gridx = 1; grid.add(engineHelp, c); c.gridy++;
		}else{
			engineCombo.setEnabled(false); // the languages still count for Resource Translation with AI Chat…
			JLabel n = GAppSettingsDialog.note(GPreferencesDialog.dialogText("Not available:")+" "+GPreferencesDialog.dialogText(unavailable)
				+" "+International.t("Resource Translation with AI Chat… works without it."));
			n.setForeground(new java.awt.Color(0xb0, 0x40, 0x00));
			c.gridx = 1; grid.add(n, c); c.gridy++;
		}

		//翻訳したリソース(ResourceTranslation、「リソース翻訳…」で作る): 使う/使わない(元のリソースで動く)と削除。プログラムのファイルは変わらない
		if(!stack){
			java.util.List<String> translatedInto = ResourceTranslation.languages(file);
			if(!translatedInto.isEmpty()){
				resourcesCombo = GAppSettingsDialog.plainCombo(new String[]{GPreferencesDialog.dialogText("Use"), GPreferencesDialog.dialogText("Don't use (its own resources)")});
				resourcesCombo.setSelectedIndex(Boolean.FALSE.equals(current.translatedResources) ? 1 : 0);
				JButton delete = new JButton(GPreferencesDialog.dialogText("Delete…"));
				JPanel resourcesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
				resourcesRow.add(resourcesCombo); resourcesRow.add(javax.swing.Box.createHorizontalStrut(8)); resourcesRow.add(delete);
				StringBuilder names = new StringBuilder();
				for(String language: translatedInto) names.append(names.length()>0 ? ", " : "").append(VMTranslation.languageName(language));
				JLabel n = GAppSettingsDialog.addRow(grid, c, "Translated resources:", resourcesRow,
					GPreferencesDialog.dialogText("({0}; from the next launch; the program file is never changed)").replace("{0}", names));
				delete.addActionListener(e -> {
					int answer = javax.swing.JOptionPane.showConfirmDialog(this, GPreferencesDialog.dialogText("Delete the translated resources of this program? It runs with its own from its next launch."),
						GPreferencesDialog.dialogText("Translated resources:"), javax.swing.JOptionPane.OK_CANCEL_OPTION);
					if(answer!=javax.swing.JOptionPane.OK_OPTION) return;
					ResourceTranslation.delete(file);
					resourcesCombo.setEnabled(false); delete.setEnabled(false);
					n.setText(GPreferencesDialog.dialogText(mac ? "(deleted; Resource Translation… makes them again)" : "(deleted; Resource Translation with AI Chat… makes them again)"));
				});
			}else{
				JLabel none = new JLabel(GPreferencesDialog.dialogText(mac ? "None yet: Resource Translation… makes them." : "None yet: Resource Translation with AI Chat… makes them."));
				GAppSettingsDialog.addRow(grid, c, "Translated resources:", none, null);
			}
		}

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
		JButton aheadButton = new JButton(GPreferencesDialog.dialogText("Resource Translation…"));
		aheadButton.setToolTipText(GPreferencesDialog.dialogText(stack ? "Translates the stack's button names and fields now." : "Translates its menus and dialogs into a copy of its resources."));
		aheadButton.setEnabled(unavailable==null && file.exists());
		aheadButton.addActionListener(e -> { ok = true; ahead = true; dispose(); });
		JButton chatButton = new JButton(International.t("AI Chat…"));
		chatButton.setToolTipText(International.t("Resource Translation with AI Chat…")+" "+International.t("Copy a request into ChatGPT or another AI chat and paste its answer back."));
		chatButton.setEnabled(file.exists());
		chatButton.addActionListener(e -> { ok = true; chat = true; dispose(); });
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); if(mac) left.add(aheadButton); left.add(chatButton);
		if(owner instanceof Frame || owner==null) bottom.add(left, BorderLayout.WEST); // not over the Application Settings: its window would block the progress window
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton cancelBtn = new JButton("Cancel"), okBtn = new JButton("OK");
		cancelBtn.addActionListener(e -> dispose());
		okBtn.addActionListener(e -> { ok = true; dispose(); });
		buttons.add(cancelBtn); buttons.add(okBtn);
		bottom.add(buttons, BorderLayout.EAST);
		getContentPane().add(bottom, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(okBtn);

		pack();
		Dimension size = getSize();
		if(size.width < 480) size.width = 480;
		setSize(size);
		if(owner!=null && owner.isShowing()) setLocationRelativeTo(owner); else setLocationRelativeTo(null);
		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
	}

	/** Stores the dialog's choices (OK or Resource Translation…) and lets a running program or the open stack follow them. */
	private void store() {
		LauncherStore store = LauncherStore.get();
		LauncherStore.AppSettings s = current.copy();
		int i = toCombo.getSelectedIndex(); s.translateTo = i==0 ? null : VMTranslation.TARGETS[i-1];
		i = fromCombo.getSelectedIndex(); s.translateFrom = i==0 ? null : VMTranslation.TARGETS[i-1];
		if(engineCombo.isEnabled()){ i = engineCombo.getSelectedIndex(); s.translateEngine = i==0 ? null : VMTranslation.ENGINES[i-1]; }
		if(showCombo!=null) s.translate = showCombo.getSelectedIndex()==1 ? Boolean.TRUE : null;
		if(picturesCombo!=null) s.translatePictures = picturesCombo.getSelectedIndex()==1 ? Boolean.FALSE : null;
		if(resourcesCombo!=null && resourcesCombo.isEnabled()) s.translatedResources = resourcesCombo.getSelectedIndex()==1 ? Boolean.FALSE : null;
		store.putSettings(file, s);
		if(stack) StackTranslation.setOn(file, Boolean.TRUE.equals(s.translate)); // also redraws the open stack in the new language
		else{ MacApp68k running = MacApp68k.runningFrom(file); if(running!=null) running.translationChanged(); }
	}

	/** Shows the translation settings of a program or a stack (its folder or file); OK stores them. */
	static void show(Window owner, File file) {
		if(file==null) return;
		LauncherStore.AppSettings current = LauncherStore.get().settingsFor(file);
		if(current==null) current = new LauncherStore.AppSettings();
		GTranslationSettingsDialog dialog = new GTranslationSettingsDialog(owner, file, current);
		if(!dialog.ok) return;
		dialog.store();
		if(dialog.ahead) GTranslateAheadDialog.show(owner instanceof Frame ? (Frame)owner : null, file);
		if(dialog.chat) GLlmTranslationDialog.show(owner instanceof Frame ? (Frame)owner : null, file);
	}
}

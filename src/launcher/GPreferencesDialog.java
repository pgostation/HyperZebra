import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * HyperZebraアプリの設定画面。2つの入口がある:
 * <ul>
 * <li>設定(SETTINGS / {@link SettingsForm}): HyperZebra全体に効くもの。表示言語、拡大表示
 *     (等倍/最大125%・150%・200%拡大/等倍または2倍。スタックと68Kアプリのゲーム画面の拡大を一体で)、ファイル等へのアクセス確認。</li>
 * <li>68Kアプリ設定(VM / {@link VmForm}): 68Kアプリだけに効くもの。CPU速度、ファイルダイアログの広さ、文字のアンチエイリアス、
 *     テキスト/PICTファイルの保存形式、画面の色数・大きさ(速度・ダイアログ・色数・大きさはアプリ個別設定 GAppSettingsDialog で上書きできる)。</li>
 * </ul>
 * フォームはこのモーダルダイアログ(スタックウインドウのメニューから: OKで反映)と、ランチャーのメインエリア
 * (GLauncher: 変更するたびに反映)の両方に置かれる。
 * いずれもjava.util.prefs.Preferencesで永続化され(PCARD.setZoomModeEnabled/PCARD.setLanguage/PCARD.setSmoothText68k など)、
 * 言語と拡大表示は次にスタックを開き直すかアプリを再起動すると反映される(ライブ反映はしない)。アンチエイリアスと速度は即反映。
 */
class GPreferencesDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	static final int SETTINGS = 0, VM = 1;
	private static String clicked = "";
	private final SettingsForm settingsForm;
	private final VmForm vmForm;
	static final int[] depthValues = {32, 16, 8, 4, 1}; // some late-1980s games require a 4-bit, 16-colour screen
	//screenComboの並びと、PCARD.screenSize68kに保存する値(空 = 当時の最大の画面まで、"display" = ディスプレイ全体)。
	static final String[] screenSizes = {"", MacAppHost.WHOLE_DISPLAY, "512x342", "640x480", "800x600", "1024x768", "1152x870", "1280x1024", "1600x1200"};

	/** The names of the game screen's places: index 0 the centre of the display, 1 its top left (PCARD.screenTopLeft68k). */
	static String[] screenPlaceNames() { return new String[]{dialogText("Centre of the display"), dialogText("Top left of the display")}; }
	static String screenPlaceTip() { return dialogText("Where a chosen screen size smaller than the display is placed (at once, also while the program runs)."); }

	/** The names of the colour depths in depthValues order. */
	static String[] depthNames() {
		return new String[]{dialogText("Full color"), dialogText("Thousands of colors"), dialogText("256 colors"), dialogText("16 colors"), dialogText("Black and white")};
	}
	/** The names of the screen sizes in screenSizes order. */
	static String[] screenNames() { return screenNames(null); }
	/** The screen sizes' names; "largest screen of the time" shows its size for 68K programs (false), PowerPC ones (true) or both (null). */
	static String[] screenNames(Boolean ppc) {
		String era = ppc==null ? "68K "+MacAppHost.ERA_SCREEN_WIDTH+"x"+MacAppHost.ERA_SCREEN_HEIGHT+", PowerPC "+MacAppHost.PPC_ERA_SCREEN_WIDTH+"x"+MacAppHost.PPC_ERA_SCREEN_HEIGHT
			: ppc ? MacAppHost.PPC_ERA_SCREEN_WIDTH+"x"+MacAppHost.PPC_ERA_SCREEN_HEIGHT : MacAppHost.ERA_SCREEN_WIDTH+"x"+MacAppHost.ERA_SCREEN_HEIGHT;
		String[] names = new String[screenSizes.length];
		for(int i=0; i<screenSizes.length; i++) names[i] = screenSizes[i].isEmpty() ? dialogText("Largest screen of the time")+" ("+era+")"
			: screenSizes[i].equals(MacAppHost.WHOLE_DISPLAY) ? dialogText("Whole display") : screenSizes[i];
		return names;
	}

	/**
	 * The general settings' controls ("Settings": what applies to all of HyperZebra, stacks and 68K programs alike, and is
	 * not overridden per program): the language, zoom, access confirmation, anti-aliased text, the host forms of
	 * text and picture files. {@code live}: every change is stored at once and the note under the controls says when a
	 * restart is needed (the launcher); otherwise {@link #apply()} stores everything (the dialog's OK).
	 */
	static class SettingsForm extends JPanel {
		private static final long serialVersionUID = 1L;
		final JComboBox<String> languageCombo, zoomCombo, printDpiCombo;
		final JCheckBox confirmAccessCheck, talkJitCheck;
		final JLabel note = new JLabel(" ");
		/** Told when the language was changed in live mode (the launcher rebuilds itself in the new language). */
		Runnable languageChanged;

		SettingsForm(boolean live) {
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			JPanel languagePanel = row();
			languagePanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
			languagePanel.add(new JLabel(dialogText("Language:")));
			languageCombo = languageCombo();
			languagePanel.add(languageCombo);
			add(languagePanel);

			// one zoom setting for everything: stacks and the 68K game screen (1x, up to 125/150/200%, or pixel-perfect 1x or 2x)
			JPanel zoomPanel = row();
			zoomPanel.setBorder(BorderFactory.createEmptyBorder(4, 16, 0, 16));
			zoomPanel.add(new JLabel(dialogText("Zoom:")));
			zoomCombo = zoomCombo(PCARD.zoomChoice); // PCARD.main keeps screenScale68k in step with it
			zoomCombo.setToolTipText(dialogText("Up to: as large as fits, smooth. 1x or 2x: sharp pixels."));
			zoomPanel.add(zoomCombo);
			add(zoomPanel);

			confirmAccessCheck = new JCheckBox(dialogText("Confirm access outside the file's folder"));
			confirmAccessCheck.setToolTipText(dialogText("Asks before files outside the folder, the network, other programs or the microphone are used."));
			confirmAccessCheck.setSelected(Sandbox.enabled);
			confirmAccessCheck.setBorder(BorderFactory.createEmptyBorder(8, 16, 0, 16));
			add(confirmAccessCheck);

			talkJitCheck = new JCheckBox(dialogText("HyperTalk JIT compiler"));
			talkJitCheck.setToolTipText(dialogText("Runs scripts faster. Off: the interpreter only."));
			talkJitCheck.setSelected(PCARD.talkJit);
			talkJitCheck.setBorder(BorderFactory.createEmptyBorder(4, 16, 0, 16));
			add(talkJitCheck);

			// the resolution cards print at, and the highest one 68K programs may pick through PrGeneral
			JPanel printPanel = row();
			printPanel.setBorder(BorderFactory.createEmptyBorder(4, 16, 8, 16));
			printPanel.add(new JLabel(dialogText("Print resolution:")));
			printDpiCombo = new JComboBox<String>(printDpiNames());
			int dpiIndex = 0;
			for(int i=0; i<PCARD.PRINT_DPI_CHOICES.length; i++) if(PCARD.PRINT_DPI_CHOICES[i]==PCARD.printDpi) dpiIndex = i;
			printDpiCombo.setSelectedIndex(dpiIndex);
			printDpiCombo.setToolTipText(dialogText("Mac programs can choose up to this resolution."));
			printPanel.add(printDpiCombo);
			add(printPanel);

			note.setBorder(BorderFactory.createEmptyBorder(4, 16, 16, 16));
			add(note);
			add(javax.swing.Box.createVerticalGlue()); // in the launcher the page may be taller than the controls
			for(java.awt.Component c: getComponents()) if(c instanceof JLabel || c instanceof JCheckBox){ ((javax.swing.JComponent)c).setAlignmentX(LEFT_ALIGNMENT); c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height)); } // rows keep their height (row() does the same for the pop-up rows); the glue takes the rest
			if(live){
				ActionListener now = new ActionListener(){ public void actionPerformed(ActionEvent e){
					boolean language = e.getSource()==languageCombo && !selectedLanguage(languageCombo).equals(PCARD.language);
					if(apply()) note.setText(dialogText("Reopen the stack or restart HyperZebra to apply this change."));
					if(language && languageChanged!=null) languageChanged.run();
				}};
				languageCombo.addActionListener(now);
				zoomCombo.addActionListener(now); confirmAccessCheck.addActionListener(now); talkJitCheck.addActionListener(now); printDpiCombo.addActionListener(now);
			}
		}

		/** Stores what changed; true when a change only takes effect after the stack is reopened or HyperZebra restarted. */
		boolean apply() {
			boolean changed = false;
			//アクセスの確認はすぐ反映される(すでに許可・拒否した答えはセッション中そのまま)
			boolean newConfirmAccess = confirmAccessCheck.isSelected();
			if(newConfirmAccess != Sandbox.enabled){
				Sandbox.setEnabled(newConfirmAccess);
			}
			//HyperTalkのJITは実行中のスクリプトにもすぐ(次の文で)反映される
			if(talkJitCheck.isSelected() != PCARD.talkJit) PCARD.setTalkJit(talkJitCheck.isSelected());
			//拡大表示: スタックと68Kアプリのゲーム画面の両方、開いているものにもすぐ反映される
			int newZoom = DisplayZoom.CHOICES[zoomCombo.getSelectedIndex()];
			if(newZoom != PCARD.zoomChoice){
				PCARD.setZoomChoice(newZoom);
				PCARD.applyZoomLive();
			}
			if(newZoom != PCARD.screenScale68k){
				PCARD.setScreen68k(PCARD.screenSize68k, newZoom);
				MacApp68k.zoomChangedAll();
			}
			//印刷解像度は次の印刷から反映される
			int newPrintDpi = PCARD.PRINT_DPI_CHOICES[printDpiCombo.getSelectedIndex()];
			if(newPrintDpi != PCARD.printDpi){
				PCARD.setPrintDpi(newPrintDpi);
			}
			if(applyLanguage(languageCombo)) changed = true;
			return changed;
		}
	}

	/** The zoom pop-up: the DisplayZoom choices in their order, {@code choice} selected. */
	static JComboBox<String> zoomCombo(int choice) {
		String[] names = new String[DisplayZoom.CHOICES.length];
		int index = 0;
		for(int i=0; i<names.length; i++){
			names[i] = dialogText(DisplayZoom.label(DisplayZoom.CHOICES[i]));
			if(DisplayZoom.CHOICES[i]==choice) index = i;
		}
		JComboBox<String> combo = new JComboBox<String>(names);
		combo.setSelectedIndex(index);
		return combo;
	}

	/** The names of the smooth zoom choices in SmoothZoom.CHOICES order. */
	static String[] smoothNames() {
		String[] names = new String[SmoothZoom.CHOICES.length];
		for(int i=0; i<names.length; i++) names[i] = dialogText(SmoothZoom.label(SmoothZoom.CHOICES[i]));
		return names;
	}
	/** The smooth zoom pop-ups' tooltip. */
	static String smoothTip() {
		return dialogText("How pictures are enlarged when zoomed. If unsure, choose Auto.");
	}
	/** The names of the print resolutions in PCARD.PRINT_DPI_CHOICES order. */
	static String[] printDpiNames() {
		String[] names = new String[PCARD.PRINT_DPI_CHOICES.length];
		for(int i=0; i<names.length; i++) names[i] = PCARD.PRINT_DPI_CHOICES[i]+" dpi"+(PCARD.PRINT_DPI_CHOICES[i]==72 ? " "+dialogText("(as on a Macintosh)") : "");
		return names;
	}

	/**
	 * The language pop-up: every language there is a catalog for (International.availableLanguages(), English first), by its
	 * own name, the current one selected. The language is a setting of all of HyperZebra - menus, dialogs, the launcher.
	 */
	static JComboBox<String> languageCombo() {
		java.util.List<String> tags = International.availableLanguages();
		String[] displayNames = new String[tags.size()];
		int selectedIndex = 0;
		for(int i=0; i<displayNames.length; i++){
			displayNames[i] = International.displayName(tags.get(i));
			if(tags.get(i).equals(PCARD.language)) selectedIndex = i;
		}
		JComboBox<String> combo = new JComboBox<String>(displayNames);
		combo.putClientProperty(LANGUAGE_TAGS, tags);
		combo.setSelectedIndex(selectedIndex);
		return combo;
	}
	private static final String LANGUAGE_TAGS = "hz.languageTags";
	/** The language tag of the pop-up's choice. */
	@SuppressWarnings("unchecked")
	static String selectedLanguage(JComboBox<String> combo) {
		java.util.List<String> tags = (java.util.List<String>)combo.getClientProperty(LANGUAGE_TAGS);
		int i = combo.getSelectedIndex();
		return tags==null || i<0 || i>=tags.size() ? PCARD.language : tags.get(i);
	}
	/** Stores the pop-up's language; true when it changed (the stack window's menus follow at the next start). */
	static boolean applyLanguage(JComboBox<String> combo) {
		String newLanguage = selectedLanguage(combo);
		if(newLanguage.equals(PCARD.language)) return false;
		PCARD.setLanguage(newLanguage);
		return true;
	}

	/**
	 * The VM settings' controls: the defaults every 68K program starts with and its own settings (GAppSettingsDialog) can
	 * override - the speed, the file dialogs, the screen's colours and size. {@code live} stores each change at once.
	 */
	static class VmForm extends JPanel {
		private static final long serialVersionUID = 1L;
		final JCheckBox realSpeedCheck, ppcJitCheck, wideFileDialogsCheck, controlKeysCheck, smoothTextCheck, utf8TextFilesCheck, pngPictureFilesCheck, gamepadCheck;
		final JComboBox<String> depthCombo, screenCombo, screenPlaceCombo, smoothCombo;
		/** MIDI: what the Apple MIDI Driver's Modem Port and Printer Port are wired to on the host (MidiHost68k). */
		final JComboBox<String> midiOutModemCombo, midiOutPrinterCombo, midiInModemCombo, midiInPrinterCombo;
		/** AppleTalk of 68K programs: the LAN (LocalTalk over UDP), this process only, or off (AppleTalk68k). */
		final JComboBox<String> appleTalkCombo;
		private static final String[] APPLETALK_VALUES = { AppleTalk68k.UDP, AppleTalk68k.LOCAL, AppleTalk68k.OFF };
		private final java.util.List<String> midiOutputValues = new java.util.ArrayList<String>(), midiInputValues = new java.util.ArrayList<String>();

		VmForm(boolean live) {
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			// short labels; the long explanations are the tooltips
			realSpeedCheck = new JCheckBox(dialogText("Real machine speed (68040 / iMac G3)"));
			realSpeedCheck.setToolTipText(dialogText("Runs at the speed of a 68040 Mac / an iMac G3. Off: some games run too fast."));
			realSpeedCheck.setSelected(PCARD.realSpeed68k);
			realSpeedCheck.setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 16));
			add(realSpeedCheck);

			ppcJitCheck = new JCheckBox(dialogText("PowerPC JIT compiler"));
			ppcJitCheck.setToolTipText(dialogText("Runs PowerPC programs faster. Off: the interpreter only."));
			ppcJitCheck.setSelected(PCARD.ppcJit);
			ppcJitCheck.setBorder(BorderFactory.createEmptyBorder(8, 16, 0, 16));
			add(ppcJitCheck);

			wideFileDialogsCheck = new JCheckBox(dialogText("Wide file dialogs"));
			wideFileDialogsCheck.setToolTipText(dialogText("Wide file dialogs for Mac programs (date, size, kind and sorting)"));
			wideFileDialogsCheck.setSelected(PCARD.wideFileDialogs68k);
			wideFileDialogsCheck.setBorder(BorderFactory.createEmptyBorder(8, 16, 0, 16));
			add(wideFileDialogsCheck);

			controlKeysCheck = new JCheckBox(dialogText("Control-key shortcuts go to the program"));
			controlKeysCheck.setToolTipText(dialogText("Control-arrow and Control-number go to the program, not to macOS (for games)."));
			controlKeysCheck.setSelected(PCARD.controlKeysToPrograms68k);
			controlKeysCheck.setBorder(BorderFactory.createEmptyBorder(8, 16, 0, 16));
			if(GMenu.isMacOSX()) add(controlKeysCheck);

			smoothTextCheck = new JCheckBox(dialogText("Anti-alias text"));
			smoothTextCheck.setToolTipText(dialogText("Always anti-alias text in Mac applications"));
			smoothTextCheck.setSelected(PCARD.smoothText68k);
			smoothTextCheck.setBorder(BorderFactory.createEmptyBorder(8, 16, 0, 16));
			add(smoothTextCheck);

			utf8TextFilesCheck = new JCheckBox(dialogText("Save new text files as UTF-8"));
			utf8TextFilesCheck.setToolTipText(dialogText("Text files Mac programs create are saved as UTF-8."));
			utf8TextFilesCheck.setSelected(PCARD.utf8TextFiles68k);
			utf8TextFilesCheck.setBorder(BorderFactory.createEmptyBorder(8, 16, 0, 16));
			add(utf8TextFilesCheck);

			pngPictureFilesCheck = new JCheckBox(dialogText("PICT files as PNG, PNG as PICT"));
			pngPictureFilesCheck.setToolTipText(dialogText("Save PICT files that Mac programs create as PNG, and show them PNG files as PICT"));
			pngPictureFilesCheck.setSelected(PCARD.pngPictureFiles68k);
			pngPictureFilesCheck.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
			add(pngPictureFilesCheck);

			//ゲームコントローラ: 各ボタン/スティックを割り当てたキー・クリック・ポインタ移動として前面のスタック/68Kアプリへ届ける
			JPanel gamepadPanel = row();
			gamepadPanel.setBorder(BorderFactory.createEmptyBorder(0, 11, 8, 16));
			gamepadCheck = new JCheckBox(dialogText("Game controllers as keys and mouse"));
			gamepadCheck.setToolTipText(dialogText("Controller buttons and sticks press keys, click and move the pointer."));
			gamepadCheck.setSelected(GamepadHost.enabled);
			gamepadPanel.add(gamepadCheck);
			JButton gamepadButton = new JButton(dialogText("Bindings…"));
			gamepadButton.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
				GamepadHost.Mapping m = GGamepadDialog.show(SwingUtilities.getWindowAncestor(VmForm.this), dialogText("Game Controller"), GamepadHost.preferenceMapping());
				if(m!=null) GamepadHost.setPreferenceMapping(m);
			}});
			gamepadPanel.add(gamepadButton);
			add(gamepadPanel);


			JPanel depthPanel = row();
			depthPanel.setBorder(BorderFactory.createEmptyBorder(8, 16, 0, 16));
			depthPanel.add(new JLabel(dialogText("Screen colors:")));
			depthCombo = new JComboBox<String>(depthNames());
			int depthIndex = 0;
			for(int i=0; i<depthValues.length; i++) if(depthValues[i]==PCARD.screenDepth68k) depthIndex = i;
			depthCombo.setSelectedIndex(depthIndex);
			depthCombo.setToolTipText(dialogText("Old programs always get 256 colors."));
			depthPanel.add(depthCombo);
			add(depthPanel);

			JPanel screenPanel = row();
			screenPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
			screenPanel.add(new JLabel(dialogText("Screen size:")));
			screenCombo = new JComboBox<String>(screenNames());
			int screenIndex = 0;
			for(int i=0; i<screenSizes.length; i++) if(screenSizes[i].equals(PCARD.screenSize68k)) screenIndex = i;
			screenCombo.setSelectedIndex(screenIndex);
			screenCombo.setToolTipText(dialogText("Most games are made for 640x480."));
			screenPanel.add(screenCombo);
			add(screenPanel);

			//ディスプレイより小さい画面(640x480など)を置く基本位置: 中央か左上(すぐ反映)
			JPanel placePanel = row();
			placePanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
			placePanel.add(new JLabel(dialogText("Screen position:")));
			screenPlaceCombo = new JComboBox<String>(screenPlaceNames());
			screenPlaceCombo.setSelectedIndex(PCARD.screenTopLeft68k ? 1 : 0);
			placePanel.setToolTipText(screenPlaceTip()); screenPlaceCombo.setToolTipText(screenPlaceTip());
			placePanel.add(screenPlaceCombo);
			add(placePanel);

			//スムーズ拡大: 拡大表示の解像度で文字・オフスクリーンを描き、アイコン・ピクチャは選んだ方法で拡大する(主にゲーム向け、すぐ反映)
			JPanel smoothPanel = row();
			smoothPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
			smoothPanel.add(new JLabel(dialogText("Smooth zoom:")));
			smoothCombo = new JComboBox<String>(smoothNames());
			int smoothIndex = 0;
			for(int i=0; i<SmoothZoom.CHOICES.length; i++) if(SmoothZoom.CHOICES[i]==PCARD.smoothZoom68k) smoothIndex = i;
			smoothCombo.setSelectedIndex(smoothIndex);
			smoothPanel.setToolTipText(smoothTip()); smoothCombo.setToolTipText(smoothTip());
			smoothPanel.add(smoothCombo);
			add(smoothPanel);

			//MIDI: モデムポート/プリンタポートの出力先と入力元(内蔵音源・なし・自動・ホストの機器)
			midiOutputValues.add(MidiHost68k.SYNTH); midiOutputValues.add(MidiHost68k.NONE); midiOutputValues.addAll(MidiHost68k.outputDeviceNames());
			midiInputValues.add(MidiHost68k.NONE); midiInputValues.add(MidiHost68k.AUTO); midiInputValues.addAll(MidiHost68k.inputDeviceNames());
			midiOutModemCombo = midiCombo(midiOutputValues, PCARD.midiOutModem68k);
			midiOutPrinterCombo = midiCombo(midiOutputValues, PCARD.midiOutPrinter68k);
			midiInModemCombo = midiCombo(midiInputValues, PCARD.midiInModem68k);
			midiInPrinterCombo = midiCombo(midiInputValues, PCARD.midiInPrinter68k);
			String midiTip = dialogText("Built-in synthesizer (General MIDI) or a MIDI device connected to this computer.");
			JPanel midiOutPanel = row();
			midiOutPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
			midiOutPanel.add(new JLabel(dialogText("MIDI out (modem / printer port):")));
			midiOutPanel.add(midiOutModemCombo); midiOutPanel.add(midiOutPrinterCombo);
			midiOutPanel.setToolTipText(midiTip); midiOutModemCombo.setToolTipText(midiTip); midiOutPrinterCombo.setToolTipText(midiTip);
			add(midiOutPanel);
			JPanel midiInPanel = row();
			midiInPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
			midiInPanel.add(new JLabel(dialogText("MIDI in (modem / printer port):")));
			midiInPanel.add(midiInModemCombo); midiInPanel.add(midiInPrinterCombo);
			midiInModemCombo.setToolTipText(midiTip); midiInPrinterCombo.setToolTipText(midiTip);
			add(midiInPanel);
			//AppleTalk: LAN(LocalTalk over UDP)・このHyperZebra内だけ・切(次に起動する68Kアプリから)
			JPanel appleTalkPanel = row();
			appleTalkPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
			appleTalkPanel.add(new JLabel(dialogText("AppleTalk:")));
			appleTalkCombo = new JComboBox<String>(new String[]{ dialogText("Local network (LocalTalk over UDP)"), dialogText("This computer only"), dialogText("Off") });
			int appleTalkIndex = 0;
			for(int i=0; i<APPLETALK_VALUES.length; i++) if(APPLETALK_VALUES[i].equals(PCARD.appleTalk68k)) appleTalkIndex = i;
			appleTalkCombo.setSelectedIndex(appleTalkIndex);
			String appleTalkTip = dialogText("Local network: reaches HyperZebra on other computers. Applies to programs launched next.");
			appleTalkPanel.setToolTipText(appleTalkTip); appleTalkCombo.setToolTipText(appleTalkTip);
			appleTalkPanel.add(appleTalkCombo);
			add(appleTalkPanel);
			add(javax.swing.Box.createVerticalGlue());
			for(java.awt.Component c: getComponents()) if(c instanceof JLabel || c instanceof JCheckBox){ ((javax.swing.JComponent)c).setAlignmentX(LEFT_ALIGNMENT); c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height)); }
			if(live){
				ActionListener now = new ActionListener(){ public void actionPerformed(ActionEvent e){ apply(); }};
				for(javax.swing.AbstractButton b: new javax.swing.AbstractButton[]{realSpeedCheck, ppcJitCheck, wideFileDialogsCheck, controlKeysCheck, smoothTextCheck, utf8TextFilesCheck, pngPictureFilesCheck, gamepadCheck}) b.addActionListener(now);
				depthCombo.addActionListener(now); screenCombo.addActionListener(now); screenPlaceCombo.addActionListener(now); smoothCombo.addActionListener(now);
				midiOutModemCombo.addActionListener(now); midiOutPrinterCombo.addActionListener(now); midiInModemCombo.addActionListener(now); midiInPrinterCombo.addActionListener(now);
				appleTalkCombo.addActionListener(now);
			}
		}

		/** A combo of the MIDI choices, showing the fixed ones by name; a stored device that is not present now is kept as its own entry. */
		private JComboBox<String> midiCombo(java.util.List<String> values, String current) {
			if(!values.contains(current)) values.add(current);
			String[] names = new String[values.size()];
			for(int i=0; i<names.length; i++){
				String v = values.get(i);
				names[i] = v.equals(MidiHost68k.SYNTH) ? dialogText("Built-in synthesizer") : v.equals(MidiHost68k.NONE) ? dialogText("None") : v.equals(MidiHost68k.AUTO) ? dialogText("Automatic") : v;
			}
			JComboBox<String> combo = new JComboBox<String>(names);
			combo.setSelectedIndex(values.indexOf(current));
			return combo;
		}

		/** Stores what changed. */
		void apply() {
			//CPUの速さは実行中のプログラムにもすぐ反映される
			boolean newRealSpeed = realSpeedCheck.isSelected();
			if(newRealSpeed != PCARD.realSpeed68k){
				PCARD.setRealSpeed68k(newRealSpeed);
			}
			//PowerPCのJITは実行中のプログラムにもすぐ(次のポーリングで)反映される
			if(ppcJitCheck.isSelected() != PCARD.ppcJit) PCARD.setPpcJit(ppcJitCheck.isSelected());
			//ファイルダイアログの広さは次に開くダイアログから反映される
			boolean newWideFileDialogs = wideFileDialogsCheck.isSelected();
			if(newWideFileDialogs != PCARD.wideFileDialogs68k){
				PCARD.setWideFileDialogs68k(newWideFileDialogs);
			}
			//Controlキーのショートカットは次にControlを押したときから反映される
			boolean newControlKeys = controlKeysCheck.isSelected();
			if(newControlKeys != PCARD.controlKeysToPrograms68k){
				PCARD.setControlKeysToPrograms68k(newControlKeys);
			}
			//68Kアプリの文字のアンチエイリアスは描画のたびに参照されるので、開き直し不要で即反映される
			boolean newSmoothText = smoothTextCheck.isSelected();
			if(newSmoothText != PCARD.smoothText68k){
				PCARD.setSmoothText68k(newSmoothText);
			}
			//テキストファイルの保存形式は次に閉じるファイルから反映される
			boolean newUtf8TextFiles = utf8TextFilesCheck.isSelected();
			if(newUtf8TextFiles != PCARD.utf8TextFiles68k){
				PCARD.setUtf8TextFiles68k(newUtf8TextFiles);
			}
			//PICT/PNGの変換は次に開く・閉じるファイルから反映される
			boolean newPngPictureFiles = pngPictureFilesCheck.isSelected();
			if(newPngPictureFiles != PCARD.pngPictureFiles68k){
				PCARD.setPngPictureFiles68k(newPngPictureFiles);
			}
			//ゲームコントローラはすぐ反映される(切ると押されていたキーは離される)
			if(gamepadCheck.isSelected() != GamepadHost.enabled) GamepadHost.setEnabled(gamepadCheck.isSelected());
			//画面の色数は次に起動する68Kアプリから反映される(起動中のアプリはそのまま)
			int newDepth = depthValues[depthCombo.getSelectedIndex()];
			if(newDepth != PCARD.screenDepth68k){
				PCARD.setScreenDepth68k(newDepth);
			}
			//画面の大きさも次に起動する68Kアプリから(拡大は「設定」の拡大表示と一体)
			String newScreen = screenSizes[screenCombo.getSelectedIndex()];
			if(!newScreen.equals(PCARD.screenSize68k)){
				PCARD.setScreen68k(newScreen, PCARD.screenScale68k);
			}
			//画面の位置は起動中のアプリにもすぐ反映される(画面がその位置へ移る)
			boolean newTopLeft = screenPlaceCombo.getSelectedIndex()==1;
			if(newTopLeft != PCARD.screenTopLeft68k) PCARD.setScreenTopLeft68k(newTopLeft);
			//スムーズ拡大は起動中のアプリにもすぐ反映される(アプリが描き直した文字から高解像度になる)
			int newSmooth = SmoothZoom.CHOICES[smoothCombo.getSelectedIndex()];
			if(newSmooth != PCARD.smoothZoom68k){
				PCARD.setSmoothZoom68k(newSmooth);
			}
			//MIDIの接続先はすぐ反映される(開いている機器は次の送受信で差し替わる)
			String outModem = midiOutputValues.get(midiOutModemCombo.getSelectedIndex()), outPrinter = midiOutputValues.get(midiOutPrinterCombo.getSelectedIndex());
			String inModem = midiInputValues.get(midiInModemCombo.getSelectedIndex()), inPrinter = midiInputValues.get(midiInPrinterCombo.getSelectedIndex());
			if(!outModem.equals(PCARD.midiOutModem68k) || !outPrinter.equals(PCARD.midiOutPrinter68k) || !inModem.equals(PCARD.midiInModem68k) || !inPrinter.equals(PCARD.midiInPrinter68k)){
				PCARD.setMidi68k(outModem, outPrinter, inModem, inPrinter);
			}
			//AppleTalkは次に起動する68Kアプリから
			String appleTalk = APPLETALK_VALUES[appleTalkCombo.getSelectedIndex()];
			if(!appleTalk.equals(PCARD.appleTalk68k)) PCARD.setAppleTalk68k(appleTalk);
		}
	}

	private GPreferencesDialog(Frame owner, int mode) {
		super(owner, dialogText(mode==VM ? "Mac Application Settings" : "Settings"), true);
		getContentPane().setLayout(new BorderLayout());
		settingsForm = mode==SETTINGS ? new SettingsForm(false) : null;
		vmForm = mode==VM ? new VmForm(false) : null;
		getContentPane().add("North", mode==SETTINGS ? settingsForm : vmForm);

		JPanel btmPanel = new JPanel();
		getContentPane().add("South", btmPanel);
		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(this);
		btmPanel.add(cancelBtn);
		JButton okBtn = new JButton("OK");
		okBtn.addActionListener(this);
		btmPanel.add(okBtn);
		getRootPane().setDefaultButton(okBtn);

		pack();
		Dimension size = getSize();
		if(size.width < 420) size.width = 420;
		setSize(size);
		if(owner != null && owner.isShowing()){
			setLocation(owner.getX() + owner.getWidth() / 2 - size.width / 2,
				owner.getY() + owner.getHeight() / 2 - size.height / 2);
		}
		else{
			setLocationRelativeTo(null);
		}

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				okBtn.requestFocus();
			}
		});

		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
	}

	/** Long texts are wrapped (HTML) so the forms stay under about 500 pixels wide (the HTML width comes out wider than the number) - the launcher's middle at its default width. */
	static String wrap(String text) {
		return "<html><div style='width:370px'>"+text.replace("&","&amp;").replace("<","&lt;")+"</div></html>";
	}

	/**
	 * A row of a label and a pop-up. Its height follows its current preferred height: fixing it to a number measured at
	 * construction cut the top of the pop-ups, whose real height the look and feel settles only once they are shown.
	 */
	static JPanel row() {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT)) {
			private static final long serialVersionUID = 1L;
			@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
		};
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	static String dialogText(String key) {
		return PCARD.pc!=null && PCARD.pc.intl!=null ? PCARD.pc.intl.getDialogText(key) : key;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		this.dispose();
	}

	/** The general settings (the former "Preferences…"). */
	static void show(Frame owner) { showSettings(owner); }

	/**
	 * 「設定」を表示する。OKが押され、値が変わっていれば設定を保存し、
	 * 反映には開き直し/再起動が必要な旨を案内する。
	 */
	static void showSettings(Frame owner) {
		GPreferencesDialog dialog = new GPreferencesDialog(owner, SETTINGS);
		if(!clicked.equals("OK")) return;
		if(dialog.settingsForm.apply()){
			//言語を切り替えた直後は、切り替え前の言語のintlしか無いので、
			//このメッセージ自体は古い(切り替え前の)言語で出る。次回起動時に
			//反映されることを伝える案内なので、これで矛盾は無い。
			new GDialog(owner, dialogText("Reopen the stack or restart HyperZebra to apply this change."), null, "OK", null, null);
		}
	}

	/** VM設定(68Kアプリの共通の既定値)を表示し、OKなら保存する。 */
	static void showVM(Frame owner) {
		GPreferencesDialog dialog = new GPreferencesDialog(owner, VM);
		if(!clicked.equals("OK")) return;
		dialog.vmForm.apply();
	}
}

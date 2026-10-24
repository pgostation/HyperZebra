import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/**
 * One program's own settings (the launcher's dock: "Application Settings…"): the processor of a fat program (hidden: SHOW_PROCESSOR), the screen colours, screen size,
 * zoom, wide file dialogs, Control-key shortcuts (macOS), the CPU speed (unlimited, a machine of the time, or a MIPS rate in tenths), the game controller bindings
 * and the host keys for Command, Option and Control (ModifierKeys), each either the shared value - "Follow the Mac application
 * settings" (the VM settings), "Follow the settings" (zoom, in HyperZebra's Settings) or "Standard" (the processor and the
 * modifier keys, which have no shared setting), shown with its current value - or an override. Colours, size and 2x apply at the program's next launch,
 * wide dialogs from the next dialog it opens, the Control keys, the speed, the bindings and the modifier keys at once, also to a running instance. Stored in LauncherStore; the VM settings
 * (GPreferencesDialog VM) stay the defaults for every other program.
 * <p>The rows are many, so they are grouped in tabs (Screen and Zoom, Speed and System, Keyboard and Controller, Files and Access);
 * a row with this program's own value has a bold label and its tab a dot, and "Use Shared Settings for All" puts every
 * row back. Translation is not here: "Translation Settings…" opens GTranslationSettingsDialog (stacks have it too).
 * <p>A HyperZebra stack has the same dialog as "Individual Stack Settings…" ({@link #isStack}) with the rows that mean
 * something for a stack: its zoom, its speed limit (StackSpeed: HyperTalk, the card display and its XCMDs at the speed
 * HyperCard had on a machine of the time; standard: unlimited), Control-key shortcuts (standard: off), the game controller
 * bindings, the keys for Command, Option and Control, and its access outside its folders with the places it was allowed;
 * all apply at once to the open stack (StackSettings) and the rest of its stored settings are kept.
 */
class GAppSettingsDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	private static String clicked = "";
	private final JComboBox<String> scaleCombo, gamepadCombo, accessCombo;
	/** The rows a stack does not have (null in a stack's dialog): screen colours and size, smooth zoom. */
	private JComboBox<String> depthCombo, screenCombo, screenPlaceCombo, smoothCombo;
	/** The speed: a program's (Speed and System) or a stack's speed limit (Speed). */
	private JComboBox<String> speedCombo;
	/** The dialog is a HyperZebra stack's (Individual Stack Settings): zoom, speed, keyboard and controller, access only. */
	private final boolean stack;
	/** Wide file dialogs (null while the row is hidden: SHOW_WIDE_DIALOGS). */
	private JComboBox<String> wideCombo;
	/** Control-key shortcuts to the program (macOS only; null elsewhere). */
	private JComboBox<String> controlKeysCombo;
	/** The processor a fat program runs on (null for other programs). */
	private JComboBox<String> cpuCombo;
	private JComboBox<String> systemCombo, languageCombo;
	/** Files outside HyperZebra HD: standard (its folder to read, other changes asked for) or read-only (Sandbox); null for a stack. */
	private JComboBox<String> filesCombo;
	/** The folders the program was allowed to use ("level\tpath", LauncherStore.AppSettings.grants); Remove takes one out. */
	private final javax.swing.DefaultListModel<String> grantModel = new javax.swing.DefaultListModel<String>();
	private JSpinner mipsSpinner;
	private boolean syncingSpeed;

	/** The speed presets: machines of the time (rough instruction rates of the emulation, not benchmarks). 32 = the VM's
	 *  "real Macintosh (68040)" speed (stored as realSpeed=true, as before); 2 = the old "Classic Macintosh" choice. */
	static final double[] SPEED_MIPS = {MacApp68k.CLASSIC_MIPS, 4, 8, 12, 20, Toolbox68k.DEFAULT_MIPS, 64, 128};
	static final String[] SPEED_NAMES = {"Classic Macintosh (68000)", "Macintosh II (68020)", "Macintosh IIci (68030)", "Macintosh IIfx (68030)",
		"Quadra 700 (68040, 25 MHz)", "Real Macintosh (68040)", "Twice a real Macintosh", "Four times a real Macintosh"};
	/** The presets of a program that runs PowerPC code, in PowerPC instructions a second: Power Macintoshes from the 601 to the
	 *  G3 iMac. PpcRuntime.REAL_MIPS (the iMac) is the "real machine" pace (stored as realSpeed=true like the 68K one). */
	static final double[] PPC_SPEED_MIPS = {60, 100, 132, PpcRuntime.REAL_MIPS, 2*PpcRuntime.REAL_MIPS, 4*PpcRuntime.REAL_MIPS};
	static final String[] PPC_SPEED_NAMES = {"Power Macintosh 6100 (601, 60 MHz)", "Power Macintosh 8100 (601, 100 MHz)",
		"Power Macintosh 9500 (604, 132 MHz)", "Real Power Macintosh (iMac G3, 233 MHz)", "Twice a real Power Macintosh", "Four times a real Power Macintosh"};
	static double[] presets(boolean ppc){ return ppc ? PPC_SPEED_MIPS : SPEED_MIPS; }
	static String[] presetNames(boolean ppc){ return ppc ? PPC_SPEED_NAMES : SPEED_NAMES; }
	/** The "real machine" rate: a 68040 Macintosh, or a G3 iMac for PowerPC code. */
	static double realMips(boolean ppc){ return ppc ? PpcRuntime.REAL_MIPS : Toolbox68k.DEFAULT_MIPS; }
	static final double MIN_MIPS = 0.1, MAX_MIPS = 2000;
	/** The speed pop-up's entries: follow the VM, unlimited, the presets, then "Custom speed" (the MIPS field's value). */
	static final int SPEED_FOLLOW = 0, SPEED_UNLIMITED = 1, SPEED_PRESET = 2;

	/** The speed pop-up entry for stored settings. */
	static int speedChoice(LauncherStore.AppSettings s){ return speedChoice(s, false); }
	static int speedChoice(LauncherStore.AppSettings s, boolean ppc){
		double[] mips = presets(ppc);
		if(s.mips!=null){ for(int k=0; k<mips.length; k++) if(Math.abs(mips[k]-s.mips)<0.05) return SPEED_PRESET+k; return SPEED_PRESET+mips.length; }
		if(s.realSpeed==null) return SPEED_FOLLOW;
		if(!s.realSpeed) return SPEED_UNLIMITED;
		for(int k=0; k<mips.length; k++) if(mips[k]==realMips(ppc)) return SPEED_PRESET+k;
		return SPEED_FOLLOW;
	}
	/** Stores a speed pop-up entry (with the MIPS field's value for "Custom speed") into {@code s}. */
	static void applySpeed(int choice, double typed, LauncherStore.AppSettings s){ applySpeed(choice, typed, s, false); }
	static void applySpeed(int choice, double typed, LauncherStore.AppSettings s, boolean ppc){
		double[] mips = presets(ppc);
		s.realSpeed = null; s.mips = null;
		if(choice==SPEED_UNLIMITED) s.realSpeed = Boolean.FALSE;
		else if(choice>=SPEED_PRESET){
			double rate = choice<SPEED_PRESET+mips.length ? mips[choice-SPEED_PRESET] : Math.max(MIN_MIPS, Math.min(MAX_MIPS, Math.round(typed*10)/10.0));
			if(rate==realMips(ppc)) s.realSpeed = Boolean.TRUE; else s.mips = rate;
		}
	}
	/** The preset's name for a rate (localized), or null when no preset runs at it. */
	static String speedName(double mips){ return speedName(mips, false); }
	static String speedName(double rate, boolean ppc){
		double[] mips = presets(ppc);String[] names = presetNames(ppc);
		for(int k=0; k<mips.length; k++) if(Math.abs(mips[k]-rate)<0.05) return GPreferencesDialog.dialogText(names[k]);
		return null;
	}
	/** Whether the speed controls currently show PowerPC rates (the program runs its PowerPC code). */
	private boolean speedPpc;
	/** The shared (VM) speed as it applies to this kind of program. */
	private static double vmRate(boolean ppc){ double v = Toolbox68k.mips; return v<=0 ? 0 : ppc ? v*PpcRuntime.REAL_MIPS/Toolbox68k.DEFAULT_MIPS : v; }
	/** The speed pop-up's entries for 68K or PowerPC rates. */
	private static String[] speedItems(boolean ppc){
		double[] mips = presets(ppc);String[] names = presetNames(ppc);
		String[] speedNames = new String[mips.length+2];
		speedNames[0] = GPreferencesDialog.dialogText("Unlimited");
		for(int k=0; k<mips.length; k++) speedNames[k+1] = GPreferencesDialog.dialogText(names[k])+" - "+LauncherStore.mipsText(mips[k])+" MIPS";
		speedNames[speedNames.length-1] = GPreferencesDialog.dialogText("Custom speed");
		return speedNames;
	}
	private static String vmSpeedName(boolean ppc){
		double r = vmRate(ppc);
		return r<=0 ? GPreferencesDialog.dialogText("Unlimited") : speedName(r, ppc)!=null ? speedName(r, ppc) : LauncherStore.mipsText(r)+" MIPS";
	}
	/** Refills the speed pop-up for the other processor, keeping "follow", "unlimited" and "real machine". */
	private void switchSpeedKind(boolean ppc){
		if(ppc==speedPpc) return;
		LauncherStore.AppSettings probe = new LauncherStore.AppSettings();
		applySpeed(speedCombo.getSelectedIndex(), ((Number)mipsSpinner.getValue()).doubleValue(), probe, speedPpc);
		boolean real = Boolean.TRUE.equals(probe.realSpeed);if(probe.mips!=null){ probe.mips = null; } // another processor's rate means nothing here
		speedPpc = ppc;
		String[] items = speedItems(ppc);
		syncingSpeed = true;
		try{
			speedCombo.removeAllItems();
			speedCombo.addItem(GPreferencesDialog.dialogText(FOLLOW_VM)+": "+vmSpeedName(ppc));
			for(String item : items) speedCombo.addItem(item);
			probe.realSpeed = real ? Boolean.TRUE : probe.realSpeed;
			speedCombo.setSelectedIndex(speedChoice(probe, ppc));
			mipsSpinner.setValue(real ? realMips(ppc) : vmRate(ppc)>0 ? vmRate(ppc) : realMips(ppc));
		}finally{ syncingSpeed = false; }
	}

	/** The program's own bindings being edited (null = follow the preference). */
	private GamepadHost.Mapping gamepadMapping;
	/** The program's own keys for Command, Option and Control being edited, their pop-ups and key fields. */
	private final ModifierKeys modifierKeys = new ModifierKeys();
	private final JComboBox<?>[] modifierCombos = new JComboBox<?>[3];
	private final JTextField[] modifierFields = new JTextField[3];

	/** A field that takes the next key pressed in it (any key, Tab and the modifiers too) as the Mac modifier's key. */
	private JTextField modifierKeyField(final int modifier){
		final JTextField field = new JTextField(modifierKeyText(modifier), 12);
		field.setEditable(false);
		field.setFocusTraversalKeysEnabled(false);
		field.setToolTipText(GPreferencesDialog.dialogText("Click here, then press the key"));
		field.getAccessibleContext().setAccessibleName(GPreferencesDialog.dialogText(new String[]{"Command key:", "Option key:", "Control key:"}[modifier]));
		field.addKeyListener(new KeyAdapter(){
			@Override public void keyPressed(KeyEvent e){
				e.consume();
				if(e.getKeyCode()==KeyEvent.VK_UNDEFINED) return;
				modifierKeys.set(modifier, e.getKeyCode(), e.getKeyLocation());
				field.setText(modifierKeyText(modifier));
				modifierCombos[modifier].setSelectedIndex(1);
			}
			@Override public void keyReleased(KeyEvent e){ e.consume(); }
			@Override public void keyTyped(KeyEvent e){ e.consume(); }
		});
		return field;
	}
	private String modifierKeyText(int modifier){
		return modifierKeys.vk[modifier]<0 ? GPreferencesDialog.dialogText("Press a key") : ModifierKeys.keyName(modifierKeys.vk[modifier], modifierKeys.location[modifier]);
	}

	/** The tabs' rows: each tab's pop-ups whose first entry is the shared value (any other entry = this program's own). */
	private final java.util.List<java.util.List<JComboBox<?>>> tabRows = new java.util.ArrayList<java.util.List<JComboBox<?>>>();
	private final javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
	private final java.util.List<String> tabTitles = new java.util.ArrayList<String>();
	/** The tab shown when the dialog was last closed (this session). */
	private static int lastTab;
	private static final String ROW_LABEL = "hz.rowLabel";

	/** A new tab's grid; the rows {@link #combo} adds next are counted as this tab's. */
	private JPanel newTab(String title, GridBagConstraints c) {
		JPanel grid = new JPanel(new GridBagLayout());
		grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel left = new JPanel(new BorderLayout()), top = new JPanel(new BorderLayout()); // the rows stay at the top left of a larger tab
		left.add(grid, BorderLayout.WEST);
		top.add(left, BorderLayout.NORTH);
		tabs.addTab(GPreferencesDialog.dialogText(title), top);
		tabTitles.add(GPreferencesDialog.dialogText(title));
		tabRows.add(new java.util.ArrayList<JComboBox<?>>());
		c.gridx = 0; c.gridy = 0; c.gridwidth = 1; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 4, 4);
		return grid;
	}
	/** Counts the pop-up as a row of the tab being built (its label turns bold while it has this program's own value). */
	private <T extends JComboBox<?>> T row(T combo) {
		tabRows.get(tabRows.size()-1).add(combo);
		combo.addActionListener(e -> markOwnValues());
		return combo;
	}
	/** Bold labels for the rows set for this program only, and a dot on the tabs that have some. */
	private void markOwnValues() {
		for(int t=0; t<tabRows.size(); t++){
			int own = 0;
			for(JComboBox<?> combo: tabRows.get(t)){
				boolean set = combo.getSelectedIndex()>0;
				if(set) own++;
				Object label = combo.getClientProperty(ROW_LABEL);
				if(label instanceof JLabel){ JLabel l = (JLabel)label; l.setFont(l.getFont().deriveFont(set ? java.awt.Font.BOLD : java.awt.Font.PLAIN)); }
			}
			tabs.setTitleAt(t, own>0 ? tabTitles.get(t)+" •" : tabTitles.get(t));
		}
	}

	private GAppSettingsDialog(Frame owner, File app, LauncherStore.AppSettings current) {
		super(owner, GPreferencesDialog.dialogText(isStack(app) ? "Individual Stack Settings" : "Application Settings")+" - "+LauncherStore.displayName(app), true);
		stack = isStack(app);
		getContentPane().setLayout(new BorderLayout());
		GridBagConstraints c = new GridBagConstraints();

		//項目が多いので、画面/速度とシステム/キーボードとコントローラ/ファイルとアクセスのタブに分ける。翻訳は「翻訳設定…」(別のダイアログ)
		//スタックは拡大表示/速度/キーボードとコントローラ/ファイルとアクセスだけ(色・画面の大きさはMacアプリのもの。速度はスタックの速度制限)
		JLabel intro = new JLabel(GPreferencesDialog.wrap(GPreferencesDialog.dialogText(stack
			? "Each item follows the shared setting, or has a value for this stack only. Items set for this stack are in bold; their tabs have a dot."
			: "Each item follows the shared setting, or has a value for this program only. Items set for this program are in bold; their tabs have a dot.")));
		intro.setBorder(BorderFactory.createEmptyBorder(14, 18, 6, 18));
		getContentPane().add(intro, BorderLayout.NORTH);
		JPanel middle = new JPanel(new BorderLayout());
		middle.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		middle.add(tabs, BorderLayout.CENTER);
		getContentPane().add(middle, BorderLayout.CENTER);

		// ---- Screen
		JPanel grid = newTab(stack ? "Zoom" : "Screen and Zoom", c);
		String on = GPreferencesDialog.dialogText("On"), off = GPreferencesDialog.dialogText("Off");
		if(!stack){
			String[] depthNames = GPreferencesDialog.depthNames();
			int vmDepth = 0;
			for(int i=0; i<GPreferencesDialog.depthValues.length; i++) if(GPreferencesDialog.depthValues[i]==PCARD.screenDepth68k) vmDepth = i;
			depthCombo = combo(grid, c, "Screen colors:", depthNames, depthNames[vmDepth],
				current.depth==null ? -1 : indexOf(GPreferencesDialog.depthValues, current.depth), "(at the next launch)");

			String[] screenNames = GPreferencesDialog.screenNames(MacApp68k.runsAsPowerPc(app, current)); // the largest screen of its time: 68K or PowerPC
			int vmScreen = 0;
			for(int i=0; i<GPreferencesDialog.screenSizes.length; i++) if(GPreferencesDialog.screenSizes[i].equals(PCARD.screenSize68k)) vmScreen = i;
			int screenIndex = -1;
			if(current.screenSize!=null) for(int i=0; i<GPreferencesDialog.screenSizes.length; i++) if(GPreferencesDialog.screenSizes[i].equals(current.screenSize)) screenIndex = i;
			screenCombo = combo(grid, c, "Screen size:", screenNames, screenNames[vmScreen], screenIndex, "(at the next launch)");
			//ディスプレイより小さい画面を置く基本位置(中央・左上)。実行中のアプリにもすぐ反映
			String[] placeNames = GPreferencesDialog.screenPlaceNames();
			screenPlaceCombo = combo(grid, c, "Screen position:", placeNames, placeNames[PCARD.screenTopLeft68k ? 1 : 0],
				current.screenTopLeft==null ? -1 : current.screenTopLeft ? 1 : 0, "(at once; for a screen size smaller than the display)");
			screenPlaceCombo.setToolTipText(GPreferencesDialog.screenPlaceTip());
		}

		//拡大表示はMacアプリ設定ではなく「設定」の項目
		String[] zoomNames = new String[DisplayZoom.CHOICES.length];
		int zoomIndex = -1;
		for(int i=0; i<zoomNames.length; i++){
			zoomNames[i] = GPreferencesDialog.dialogText(DisplayZoom.label(DisplayZoom.CHOICES[i]));
			if(current.screenScale!=null && current.screenScale==DisplayZoom.CHOICES[i]) zoomIndex = i;
		}
		scaleCombo = combo(grid, c, "Zoom:", FOLLOW_SETTINGS, zoomNames, GPreferencesDialog.dialogText(DisplayZoom.label(stack ? PCARD.zoomChoice : PCARD.screenScale68k)),
			zoomIndex, stack ? "(at once; up to: as large as fits the display; 1x or 2x: 1x when 2x does not fit)" : "(up to: as large as fits the display; 1x or 2x: 1x when 2x does not fit)");
		if(!stack){
			//スムーズ拡大: 拡大表示のときに文字・オフスクリーンを拡大後の解像度で描き、アイコン・ピクチャは選んだ方法で拡大する(すぐ反映)
			String[] smoothNames = GPreferencesDialog.smoothNames();
			int smoothIndex = current.smoothZoom==null ? -1 : indexOf(SmoothZoom.CHOICES, current.smoothZoom);
			smoothCombo = combo(grid, c, "Smooth zoom:", smoothNames, GPreferencesDialog.dialogText(SmoothZoom.label(PCARD.smoothZoom68k)),
				smoothIndex, "(at once; when the screen is shown magnified or on a Retina display)");
			smoothCombo.setToolTipText(GPreferencesDialog.smoothTip());

			// ---- Speed and System
			grid = newTab("Speed and System", c);
			//実行速度: 制限なし、当時の機種相当のプリセット、またはMIPS値を直接指定(0.1刻み)。PowerPCで動くアプリはPower Macintoshの機種と速さ
			speedRow(grid, c, MacApp68k.runsAsPowerPc(app, current), current, "(at once, also while the program runs; 1 MIPS = a million instructions a second)");
			//ファットアプリ(68KとPowerPCの両方のコードを持つ)はどちらのコードで動かすかを選べる。いまは隠している(SHOW_PROCESSORで戻せる)
			if(SHOW_PROCESSOR && MacApp68k.isFatApplication(app)){
				cpuCombo = combo(grid, c, "Processor:", STANDARD, new String[]{"68K", "PowerPC"}, MacApp68k.fatRunsPowerPc() ? "PowerPC" : "68K",
					current.powerPC==null ? -1 : current.powerPC ? 1 : 0, "(at the next launch; the program has code for both)");
				cpuCombo.addActionListener(e -> {
					int i = cpuCombo.getSelectedIndex();
					switchSpeedKind(i==0 ? MacApp68k.fatRunsPowerPc() : i==2);
				});
			}
			//プログラムに見せるシステムのバージョン(Gestalt sysv): 標準はMac OS 9.2.2、古いプログラムにはSystem 7.5.5も選べる
			int[] versions = Toolbox68k.SYSTEM_VERSION_CHOICES;
			String[] versionNames = new String[versions.length];
			for(int k=0; k<versions.length; k++) versionNames[k] = Toolbox68k.systemVersionName(versions[k]);
			if(SHOW_SYSTEM_VERSION)
			systemCombo = combo(grid, c, "System version:", STANDARD, versionNames, Toolbox68k.systemVersionName(Toolbox68k.MAC_OS_9_VERSION),
				current.systemVersion==null ? -1 : indexOf(versions, current.systemVersion), "(at the next launch)");
			//プログラムに見せるシステムの言語: 標準はHyperZebraの表示言語(日本語なら漢字Talk)。英語版専用のアプリ(QuarkXPress 3.31 など
			//itl0の地域が日本だと起動しない)には英語(U.S.)のシステムを選べる
			languageCombo = combo(grid, c, "System language:", STANDARD, new String[]{GPreferencesDialog.dialogText("English (U.S.)"), GPreferencesDialog.dialogText("Japanese (KanjiTalk)")},
				GPreferencesDialog.dialogText(Toolbox68k.applicationEncoding()==StackTextEncoding.JAPANESE ? "Japanese (KanjiTalk)" : "English (U.S.)"),
				current.language==null ? -1 : LANGUAGES.indexOf(current.language), "(at the next launch; some English-only programs refuse a Japanese System)");
			if(SHOW_WIDE_DIALOGS)
			wideCombo = combo(grid, c, "Wide file dialogs:", new String[]{on, off}, PCARD.wideFileDialogs68k ? on : off,
				current.wideDialogs==null ? -1 : current.wideDialogs ? 0 : 1, "(from the next dialog)");
		}

		// ---- Speed (a stack: HyperTalk, the card display and its XCMDs at the speed HyperCard had on a Macintosh of the time)
		if(stack){
			grid = newTab("Speed", c);
			speedRow(grid, c, false, current, "(at once; scripts, the card display and XCMDs/XFCNs run about as fast as HyperCard did on that Macintosh)");
		}

		// ---- Keyboard and Controller
		grid = newTab("Keyboard and Controller", c);
		//Control+矢印などをmacOS(Mission Control)に渡すか、プログラムに渡すか
		//スタックの標準はオフ(Control+矢印はMission Control)。共通の設定はMacアプリのもの
		if(GMenu.isMacOSX() && stack)
			controlKeysCombo = combo(grid, c, "Control-key shortcuts to the stack:", STANDARD, new String[]{on, off}, off,
				current.controlKeys==null ? -1 : current.controlKeys ? 0 : 1, "(at once; off leaves Control-arrow to Mission Control)");
		else if(GMenu.isMacOSX())
			controlKeysCombo = combo(grid, c, "Control-key shortcuts to the program:", new String[]{on, off}, PCARD.controlKeysToPrograms68k ? on : off,
				current.controlKeys==null ? -1 : current.controlKeys ? 0 : 1, "(at once; off leaves Control-arrow to Mission Control)");
		//ゲームコントローラの割り当て: 設定に従うか、このアプリだけの割り当て(「編集…」で開く)
		gamepadMapping = current.gamepadMapping==null ? null : GamepadHost.Mapping.parse(current.gamepadMapping);
		String own = GPreferencesDialog.dialogText(stack ? "This stack's own" : "This program's own");
		int gamepadRow = c.gridy;
		gamepadCombo = combo(grid, c, "Game controller:", new String[]{own}, GPreferencesDialog.dialogText("the shared bindings"),
			gamepadMapping==null ? -1 : 0, "(at once)");
		final JButton editBindings = new JButton(GPreferencesDialog.dialogText("Edit…"));
		editBindings.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
			GamepadHost.Mapping initial = gamepadMapping!=null ? gamepadMapping : GamepadHost.preferenceMapping();
			GamepadHost.Mapping m = GGamepadDialog.show(GAppSettingsDialog.this, GPreferencesDialog.dialogText("Game Controller")+" - "+LauncherStore.displayName(app), initial);
			if(m!=null){ gamepadMapping = m; gamepadCombo.setSelectedIndex(1); }
		}});
		int after = c.gridy; c.gridx = 2; c.gridy = gamepadRow; grid.add(editBindings, c); c.gridx = 1; c.gridy = after;
		//Command/Option/Controlキーに使うキー: 既定(WindowsではCtrlがCommandとControlを兼ねる)か、このアプリ専用のキー(クリックしてキーを押す)
		ModifierKeys ownKeys = current.modifierKeys==null ? new ModifierKeys() : ModifierKeys.parse(current.modifierKeys);
		String[] labels = {"Command key:", "Option key:", "Control key:"};
		for(int i=0; i<3; i++){
			if(ownKeys.vk[i]>=0) modifierKeys.set(i, ownKeys.vk[i], ownKeys.location[i]);
			int row = c.gridy;
			modifierCombos[i] = combo(grid, c, labels[i], STANDARD, new String[]{own}, ModifierKeys.defaultKeyName(i), ownKeys.vk[i]>=0 ? 0 : -1, i<2 ? null : stack ? "(at once; a key given to Command, Option or Control types nothing itself in this stack)" : "(at once; a key given to Command, Option or Control types nothing itself in this program)");
			modifierFields[i] = modifierKeyField(i);
			after = c.gridy; c.gridx = 2; c.gridy = row; grid.add(modifierFields[i], c); c.gridx = 1; c.gridy = after;
		}

		// ---- Files and Access
		grid = newTab("Files and Access", c);
		//フォルダ外へのアクセス(Sandbox): 設定に従う/確認する/常に許可/常に拒否
		accessCombo = combo(grid, c, "Confirm access outside the folder:", FOLLOW_SETTINGS, new String[]{GPreferencesDialog.dialogText("Confirm"), GPreferencesDialog.dialogText("Always allow"), GPreferencesDialog.dialogText("Always deny")},
			GPreferencesDialog.dialogText(Sandbox.enabled ? "On" : "Off"), current.accessPolicy==null ? -1 : "ask".equals(current.accessPolicy) ? 0 : "allow".equals(current.accessPolicy) ? 1 : 2,
			"(at once; the \"Always\" box of the access dialog sets this)");
		//フォルダのファイル(Sandbox): 標準=自分のフォルダは読むだけ(他のファイルの変更・削除は確認)、読み取り専用=何も変更しない
		//(スタックは自分の保存先に書くので、この項目は無い)
		if(!stack){
			filesCombo = row(plainCombo(new String[]{GPreferencesDialog.dialogText("Standard: read its folder, ask before changing other files"),
				GPreferencesDialog.dialogText("Read-only: change nothing outside HyperZebra HD")}));
			filesCombo.setSelectedIndex(Boolean.TRUE.equals(current.readOnly) ? 1 : 0);
			addRow(grid, c, "Files:", filesCombo, "(at the next launch; read-only still saves what you pick in its Save dialog)");
		}
		//「そのフォルダ以下を許可」と答えた場所: このアプリだけのもの。取り消せる
		for(String g: current.grants) grantModel.addElement(g);
		if(!grantModel.isEmpty()){
			final javax.swing.JList<String> grantList = new javax.swing.JList<String>(grantModel);
			grantList.setVisibleRowCount(Math.min(4, grantModel.size()));
			grantList.setCellRenderer(new javax.swing.DefaultListCellRenderer(){
				private static final long serialVersionUID = 1L;
				@Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focus){
					return super.getListCellRendererComponent(list, grantText(String.valueOf(value)), index, selected, focus);
				}
			});
			final JButton remove = new JButton(GPreferencesDialog.dialogText("Remove"));
			remove.setEnabled(false);
			grantList.addListSelectionListener(e -> remove.setEnabled(grantList.getSelectedIndex()>=0));
			remove.addActionListener(e -> { for(String g: grantList.getSelectedValuesList()) grantModel.removeElement(g); });
			c.gridx = 0; c.anchor = GridBagConstraints.NORTHWEST; grid.add(new JLabel(GPreferencesDialog.dialogText("Allowed places:")), c);
			c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; grid.add(new javax.swing.JScrollPane(grantList), c); c.fill = GridBagConstraints.NONE;
			c.gridx = 2; grid.add(remove, c); c.anchor = GridBagConstraints.WEST; c.gridy++;
		}

		markOwnValues();
		tabs.setSelectedIndex(Math.max(0, Math.min(lastTab, tabs.getTabCount()-1)));
		tabs.addChangeListener(e -> lastTab = tabs.getSelectedIndex());

		//下段: 左に「すべて共通の設定に戻す」と「翻訳設定…」(翻訳は別のダイアログ)、右に「キャンセル」「OK」
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		JButton reset = new JButton(GPreferencesDialog.dialogText("Use Shared Settings for All"));
		reset.setToolTipText(GPreferencesDialog.dialogText("Every item follows the shared setting again (allowed places stay)."));
		reset.addActionListener(e -> { for(java.util.List<JComboBox<?>> rows: tabRows) for(JComboBox<?> combo: rows) if(combo.isEnabled()) combo.setSelectedIndex(0); });
		left.add(reset);
		JButton translation = new JButton(GPreferencesDialog.dialogText("Translation Settings…"));
		translation.setToolTipText(GPreferencesDialog.dialogText(stack ? "The language this stack is translated into" : "The language this program is translated into, and its translated resources"));
		translation.addActionListener(e -> GTranslationSettingsDialog.show(this, app));
		left.add(translation);
		bottom.add(left, BorderLayout.WEST);
		JPanel btmPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(this);
		btmPanel.add(cancelBtn);
		JButton okBtn = new JButton("OK");
		okBtn.addActionListener(this);
		btmPanel.add(okBtn);
		bottom.add(btmPanel, BorderLayout.EAST);
		getContentPane().add(bottom, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(okBtn);

		pack();
		Dimension size = getSize();
		if(size.width < 560) size.width = 560;
		setSize(size);
		if(owner!=null && owner.isShowing()) setLocationRelativeTo(owner); else setLocationRelativeTo(null);
		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
	}

	/** The speed row: the pop-up (follow / unlimited / the machines / custom) and the MIPS field beside it. A stack's pop-up
	 *  has no "Unlimited" entry: its standard (the first entry) is unlimited, as stacks follow no shared speed. */
	private void speedRow(JPanel grid, GridBagConstraints c, boolean ppc, LauncherStore.AppSettings current, String note) {
		speedPpc = ppc;
		int speedRow = c.gridy;
		String[] items = speedItems(speedPpc);
		if(stack){
			items = java.util.Arrays.copyOfRange(items, 1, items.length);
			speedCombo = combo(grid, c, "Speed:", STANDARD, items, GPreferencesDialog.dialogText("Unlimited"), shownIndex(speedChoice(current, false))-1, note);
		}
		else speedCombo = combo(grid, c, "Speed:", items, vmSpeedName(speedPpc), speedChoice(current, speedPpc)-1, note);
		double vm = stack ? 0 : vmRate(speedPpc);
		double shown = current.mips!=null ? current.mips : Boolean.TRUE.equals(current.realSpeed) ? realMips(speedPpc) : vm>0 ? vm : realMips(speedPpc);
		mipsSpinner = new JSpinner(new SpinnerNumberModel(Math.max(MIN_MIPS, Math.min(MAX_MIPS, shown)), MIN_MIPS, MAX_MIPS, 0.5));
		mipsSpinner.setEditor(new JSpinner.NumberEditor(mipsSpinner, "0.0"));
		((JSpinner.DefaultEditor)mipsSpinner.getEditor()).getTextField().setColumns(5);
		mipsSpinner.getAccessibleContext().setAccessibleName(GPreferencesDialog.dialogText("Speed:")+" MIPS");
		JPanel mipsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		mipsPanel.add(mipsSpinner); mipsPanel.add(new JLabel("MIPS"));
		speedCombo.addActionListener(e -> {
			if(syncingSpeed) return;
			int choice = speedChoiceShown();double[] mips = presets(speedPpc);
			double rate = choice==SPEED_FOLLOW && !stack ? vmRate(speedPpc) : choice>=SPEED_PRESET && choice<SPEED_PRESET+mips.length ? mips[choice-SPEED_PRESET] : -1;
			syncingSpeed = true;
			try{ if(rate>0) mipsSpinner.setValue(rate); }finally{ syncingSpeed = false; }
		});
		mipsSpinner.addChangeListener(e -> {
			if(syncingSpeed) return;
			double rate = ((Number)mipsSpinner.getValue()).doubleValue();double[] mips = presets(speedPpc);
			int choice = SPEED_PRESET+mips.length; // typed: a preset when it matches one, else "Custom speed"
			for(int k=0; k<mips.length; k++) if(Math.abs(mips[k]-rate)<0.05) choice = SPEED_PRESET+k;
			syncingSpeed = true;
			try{ speedCombo.setSelectedIndex(shownIndex(choice)); }finally{ syncingSpeed = false; }
		});
		int afterSpeed = c.gridy; c.gridx = 2; c.gridy = speedRow; grid.add(mipsPanel, c); c.gridx = 1; c.gridy = afterSpeed;
	}
	/** The speed pop-up's entry as a program's speed choice (SPEED_FOLLOW, SPEED_UNLIMITED, a preset, custom); a stack's first
	 *  entry, its unlimited standard, is SPEED_FOLLOW (nothing stored). */
	private int speedChoiceShown(){ int i = speedCombo.getSelectedIndex(); return stack ? (i==0 ? SPEED_FOLLOW : i+1) : i; }
	/** The pop-up entry that shows a program's speed choice. */
	private int shownIndex(int choice){ return stack ? (choice<=SPEED_UNLIMITED ? 0 : choice-1) : choice; }

	/** A row: label, a pop-up whose first entry is "Follow the Mac application settings: <current>", and a note on when it applies. */
	private JComboBox<String> combo(JPanel grid, GridBagConstraints c, String label, String[] choices, String vmValue, int selected, String note) {
		return combo(grid, c, label, FOLLOW_VM, choices, vmValue, selected, note);
	}
	/** Where a row's shared value comes from: the Mac Application Settings, HyperZebra's Settings, or neither (the standard value). */
	/** The Processor row (fat programs: 68K or PowerPC code) is hidden; fat programs run their PowerPC code unless -Dhzppc.fat=false. */
	static final boolean SHOW_PROCESSOR = false;
	/** The System version and Wide file dialogs rows are hidden too; an earlier choice is kept. */
	static final boolean SHOW_SYSTEM_VERSION = false, SHOW_WIDE_DIALOGS = false;
	static final String FOLLOW_VM = "Follow the Mac application settings", FOLLOW_SETTINGS = "Follow the settings", STANDARD = "Standard";
	/** As above, with the first entry "<follow>: <current>"; the row counts for its tab's marks. */
	private JComboBox<String> combo(JPanel grid, GridBagConstraints c, String label, String follow, String[] choices, String vmValue, int selected, String note) {
		JComboBox<String> combo = row(followCombo(follow, choices, vmValue, selected));
		addRow(grid, c, label, combo, note);
		return combo;
	}
	/** A pop-up whose first entry is "<follow>: <shared value>" (just "<follow>" without one), then the choices; {@code selected} -1 = the first. */
	static JComboBox<String> followCombo(String follow, String[] choices, String sharedValue, int selected) {
		String[] items = new String[choices.length+1];
		items[0] = GPreferencesDialog.dialogText(follow)+(sharedValue==null ? "" : ": "+sharedValue);
		System.arraycopy(choices, 0, items, 1, choices.length);
		JComboBox<String> combo = new JComboBox<String>(items);
		combo.setSelectedIndex(selected<0 ? 0 : selected+1);
		return combo;
	}
	static JComboBox<String> plainCombo(String[] items) { return new JComboBox<String>(items); }
	/** A smaller label: the notes under the rows. */
	static JLabel note(String text) {
		JLabel n = new JLabel(text);
		n.setFont(n.getFont().deriveFont(n.getFont().getSize2D()-1f));
		return n;
	}
	/** Adds "label  control" and the note under the control (none for null: rows sharing the next row's note); returns the note. */
	static JLabel addRow(JPanel grid, GridBagConstraints c, String label, javax.swing.JComponent control, String note) {
		JLabel l = new JLabel(GPreferencesDialog.dialogText(label));
		l.setLabelFor(control);
		control.putClientProperty(ROW_LABEL, l);
		c.gridx = 0; grid.add(l, c);
		c.gridx = 1; grid.add(control, c);
		c.gridy++;
		if(note==null) return null;
		JLabel n = note(GPreferencesDialog.dialogText(note));
		c.gridx = 1; c.gridwidth = 2; grid.add(n, c); c.gridwidth = 1; // over the buttons beside the pop-ups too: they stay next to them
		c.gridy++;
		return n;
	}
	/** Whether the settings are a HyperZebra stack's (a stack folder or its _stack.xml / toc.xml) rather than a program's. */
	static boolean isStack(File f){ return StackTranslation.isStackFile(f); }
	/** The System language choices as LauncherStore.AppSettings.language keeps them, in the order of the row. */
	static final java.util.List<String> LANGUAGES = java.util.List.of("English", "Japanese");
	private static int indexOf(int[] values, int value) { for(int i=0; i<values.length; i++) if(values[i]==value) return i; return -1; }
	/** A saved grant as shown: "Read: /path", "Change: /path", "Change and delete: /path". */
	static String grantText(String saved){
		int tab = saved.indexOf('\t');
		if(tab<0) return saved;
		Sandbox.Level level = Sandbox.Level.parse(saved.substring(0, tab));
		String what = level==Sandbox.Level.DELETE ? "Change and delete" : level==Sandbox.Level.WRITE ? "Change" : "Read";
		return GPreferencesDialog.dialogText(what)+": "+saved.substring(tab+1);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		dispose();
	}

	/** Shows the dialog for {@code app}; OK stores the settings and applies the live ones to a running instance. */
	static void show(Frame owner, File app) {
		LauncherStore store = LauncherStore.get();
		LauncherStore.AppSettings current = store.settingsFor(app);
		if(current==null) current = new LauncherStore.AppSettings();
		GAppSettingsDialog dialog = new GAppSettingsDialog(owner, app, current);
		if(!clicked.equals("OK")) return;
		LauncherStore.AppSettings latest = store.settingsFor(app); // Translation Settings… may have stored its part meanwhile
		LauncherStore.AppSettings s = latest!=null ? latest.copy() : new LauncherStore.AppSettings(); // the translation settings are kept
		s.grants.clear();
		int i = dialog.scaleCombo.getSelectedIndex(); s.screenScale = i==0 ? null : DisplayZoom.CHOICES[i-1];
		if(!dialog.stack){
			i = dialog.depthCombo.getSelectedIndex(); s.depth = i==0 ? null : GPreferencesDialog.depthValues[i-1];
			i = dialog.screenCombo.getSelectedIndex(); s.screenSize = i==0 ? null : GPreferencesDialog.screenSizes[i-1];
			i = dialog.screenPlaceCombo.getSelectedIndex(); s.screenTopLeft = i==0 ? null : i==2;
			i = dialog.smoothCombo.getSelectedIndex(); s.smoothZoom = i==0 ? null : SmoothZoom.CHOICES[i-1];
			if(dialog.wideCombo!=null){ i = dialog.wideCombo.getSelectedIndex(); s.wideDialogs = i==0 ? null : i==1; }
			else s.wideDialogs = current.wideDialogs;
			if(dialog.cpuCombo!=null){ i = dialog.cpuCombo.getSelectedIndex(); s.powerPC = i==0 ? null : i==2; }
			else s.powerPC = current.powerPC; // the row is hidden: an earlier choice is kept
			if(dialog.systemCombo!=null){ i = dialog.systemCombo.getSelectedIndex(); s.systemVersion = i==0 ? null : Toolbox68k.SYSTEM_VERSION_CHOICES[i-1]; }
			else s.systemVersion = current.systemVersion;
			i = dialog.languageCombo.getSelectedIndex(); s.language = i==0 ? null : LANGUAGES.get(i-1);
		}
		try{ dialog.mipsSpinner.commitEdit(); }catch(java.text.ParseException ignored){} // a rate typed and then OK pressed at once
		applySpeed(dialog.speedChoiceShown(), ((Number)dialog.mipsSpinner.getValue()).doubleValue(), s, dialog.speedPpc);
		if(dialog.controlKeysCombo!=null){ i = dialog.controlKeysCombo.getSelectedIndex(); s.controlKeys = i==0 ? null : i==1; }
		else s.controlKeys = current.controlKeys; // set on a Mac: kept
		i = dialog.accessCombo.getSelectedIndex(); s.accessPolicy = i==0 ? null : new String[]{null, "ask", "allow", "deny"}[i];
		i = dialog.gamepadCombo.getSelectedIndex(); s.gamepadMapping = i==0 || dialog.gamepadMapping==null ? null : dialog.gamepadMapping.format();
		ModifierKeys keys = new ModifierKeys();
		for(int k=0; k<3; k++) if(dialog.modifierCombos[k].getSelectedIndex()!=0) keys.set(k, dialog.modifierKeys.vk[k], dialog.modifierKeys.location[k]);
		s.modifierKeys = keys.isEmpty() ? null : keys.format();
		if(dialog.filesCombo!=null) s.readOnly = dialog.filesCombo.getSelectedIndex()==1 ? Boolean.TRUE : null;
		for(int k=0; k<dialog.grantModel.size(); k++) s.grants.add(dialog.grantModel.get(k));
		store.putSettings(app, s);
		for(String g: current.grants) if(!s.grants.contains(g)) Sandbox.forgetGrant(app, g); //取り消した場所は今すぐ使えなくなる
		if(dialog.stack){ StackSettings.changed(app); return; } //開いているスタックには拡大表示・キーもすぐ反映
		//the speed, the dialog layout, the zoom and the smooth zoom reach a running instance; the screen size is fixed at launch
		MacApp68k running = MacApp68k.runningFrom(app);
		if(running!=null){ running.realSpeedOverride = s.realSpeed; running.mipsOverride = s.mips; running.wideDialogsOverride = s.wideDialogs; running.controlKeysOverride = s.controlKeys; running.modifierKeysOverride = MacApp68k.modifierKeys(s); running.zoomChanged(s.screenScale); running.screenPlaceChanged(s.screenTopLeft); running.smoothChanged(s.smoothZoom); running.translationChanged(); }
	}
}

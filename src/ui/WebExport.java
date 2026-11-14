import java.awt.Cursor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.swing.JFileChooser;

/**
 * File menu "Export for Web…": a folder that plays the open stack in a web browser once it is put on a web server (the
 * browser player of tools/wasm-core: the HyperTalk core as WebAssembly in a Web Worker, the card drawn on a canvas).
 * The folder holds the player's files (Support Files/WebPlayer of the distribution; build/wasm-core/web-player when run
 * from the project), the stack packed as <its name>.hzb with the Home icons its buttons use (index.html names it), and a README. Changes made while
 * playing are not saved. A stack with 68K external commands gets the player with the 68K VM too (player-68k.wasm, named on
 * the page as data-wasm). What the player does not do yet is listed in the README.
 */
final class WebExport {
	private WebExport() {}

	/** The player's files, in the order the README names them (index.html is the player's page) */
	static final String[] PLAYER_FILES = {"index.html", "player.js", "worker.js", "sw.js", "player.wasm", "wasm-gc-runtime.min.js", "TeaVM-LICENSE.txt"};
	/** The player with the 68K VM (P4: the stack's own 68K XCMDs and XFCNs run in the browser), copied only for a stack that
	 *  has 68K externals and named on the page (data-wasm); a player folder may lack it */
	static final String PLAYER_68K = "player-68k.wasm";
	/** HyperZebra's language catalogs beside the player (lang/<tag>.po and index.txt), copied when the player folder has them */
	static final String LANG_FOLDER = "lang";
	private static final Pattern EXTERNAL_68K = Pattern.compile("<externalcommand\\b[^>]*\\bplatform=\"[^\"]*68k[^\"]*\"");

	/** The web player's folder: Support Files/WebPlayer beside the application, or the project's build of it; null if none */
	static File playerFolder() {
		File dist = AppPaths.distributionDirectory();
		File[] places = {
			new File(dist, "Support Files/WebPlayer"),
			new File(dist, "wasm-core/web-player"),              // build/HyperZebra.jar: build/wasm-core/web-player
			new File(dist, "build/wasm-core/web-player"),        // build/classes run from the project folder
		};
		for (File f : places) if (hasPlayer(f)) return f;
		return null;
	}

	static boolean hasPlayer(File dir) {
		if (dir == null || !dir.isDirectory()) return false;
		for (String n : PLAYER_FILES) if (!new File(dir, n).isFile()) return false;
		return true;
	}

	/** What the page shows besides the card */
	static final class Options {
		/** The stack's menu bar above the card (off: none; the Command keys still choose its items) */
		boolean menuBar = true;
		/** The player built again with the scripts compiled ahead of time (WebAotBuild), when this copy has the compiler (the menu
		 *  command's choice, on by default there; off for the export's other callers, which want it quick) */
		boolean compileScripts = false;
		/** The keys of the game pad the page shows on touch screens (data-gamepad): null finds them in the stack
		 *  (gamepadKeys), empty shows none */
		List<String> gamepad;
		/** The speed limit the page plays at, in million 68K instructions a second (data-mips; 0 = unlimited): null takes the
		 *  stack's own (Individual Stack Settings: Speed, StackSpeed), as the stack runs on the desktop */
		Double mips;
		/** Set by the export: what compiling the scripts did (null: not tried) */
		WebAotBuild.Outcome compiled;
	}
	/** The choices made the last time (the next export starts with them) */
	private static boolean lastMenuBar = true, lastCompile = true;

	/** The menu command: asks for the new folder's name and place (and whether the page has the menu bar), writes it, says what was done */
	static void exportFromMenu() {
		OStack stack = PCARD.pc == null ? null : PCARD.pc.stack;
		if (stack == null || stack.file == null) return;
		International intl = PCARD.pc.intl;
		File player = playerFolder();
		if (player == null) {
			new GDialog(PCARD.pc, intl.getDialogText("The web player is not in this copy of HyperZebra.") + "\r"
				+ intl.getDialogText("It belongs in the folder Support Files/WebPlayer beside the application."), null, "OK", null, null);
			return;
		}
		if (PCARD.pc.tool != null) PaintTool.saveCdPictures();
		JFileChooser chooser = new JFileChooser();
		File suggestedDirectory = GMenuBrowse.stackChooserDirectory(stack.file);
		if (suggestedDirectory != null) chooser.setCurrentDirectory(suggestedDirectory);
		String name = stack.name == null || stack.name.isEmpty() ? "Untitled" : stack.name.replace('/', '_').replace(':', '_');
		chooser.setSelectedFile(new File(name + " Web"));
		chooser.setDialogTitle(intl.getDialogText("Export for Web"));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		javax.swing.JCheckBox menuBar = new javax.swing.JCheckBox(intl.getDialogText("Show the menu bar on the page"), lastMenuBar);
		// the scripts compiled ahead of time into the page's player (WebAotBuild): faster, the same results; the export takes longer
		boolean canCompile = WebAotBuild.available(player, false) || WebAotBuild.available(player, true);
		javax.swing.JCheckBox compile = new javax.swing.JCheckBox(intl.getDialogText("Compile the scripts (faster in the browser)"), lastCompile && canCompile);
		compile.setEnabled(canCompile);
		// an optional part of the distribution (jpackage.sh --with-web-compiler): Support Files/WebPlayer/compiler
		if (!canCompile) compile.setToolTipText(intl.getDialogText("Needs the script compiler, which this copy does not have"));
		javax.swing.JPanel accessory = new javax.swing.JPanel(new java.awt.BorderLayout());
		accessory.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 12, 8, 8));
		javax.swing.JPanel checks = new javax.swing.JPanel(new java.awt.GridLayout(0, 1));
		checks.add(menuBar);
		checks.add(compile);
		// the game pad a phone or tablet shows (it has no keyboard): the keys the stack reads as they are held, found in it, or
		// the ones the author chooses (a stack whose external reads the keyboard itself names none: the arrows, Space and Return
		// are only a guess), typed as their names or pressed; empty: no game pad. Remembered for this stack
		List<String> found = foundGamepadKeys(stack);
		String keptKeys = keptGamepadKeys(stack);
		javax.swing.JTextField padKeys = new javax.swing.JTextField(keptKeys != null ? keptKeys : String.join(", ", found), 16);
		padKeys.setToolTipText(intl.getDialogText("up, down, left, right, space, return, shift, option, command, control, a letter or digit; empty: no game pad"));
		javax.swing.JToggleButton press = new javax.swing.JToggleButton(intl.getDialogText("Press Keys…"));
		press.setToolTipText(intl.getDialogText("Press the keys to add them; click again when done"));
		java.awt.KeyEventDispatcher recorder = e -> {
			if (!press.isSelected()) return false;
			if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
				String t = gamepadToken(e);
				if (t != null) {
					List<String> now = new ArrayList<>(parseGamepadKeys(padKeys.getText()));
					if (!now.contains(t)) { now.add(t); padKeys.setText(String.join(", ", now)); }
				}
			}
			return true; // (no key reaches the dialog while the keys are pressed: Escape, Return, Command-letters are keys too)
		};
		java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(recorder);
		press.addActionListener(e -> press.setText(press.isSelected() ? intl.getDialogText("Done") : intl.getDialogText("Press Keys…")));
		javax.swing.JPanel padRow = new javax.swing.JPanel(new java.awt.BorderLayout(6, 0));
		padRow.add(padKeys, java.awt.BorderLayout.CENTER);
		padRow.add(press, java.awt.BorderLayout.EAST);
		checks.add(new javax.swing.JLabel(intl.getDialogText("Game pad keys (phones and tablets):")));
		checks.add(padRow);
		// a release without the compiler can download it (the one built with its player: WebAotBuild.download)
		WebAotBuild.Download download = canCompile ? null : WebAotBuild.download(player);
		if (download != null) {
			javax.swing.JButton get = new javax.swing.JButton(International.t("Download the Script Compiler ({0})…", download.sizeText()));
			get.addActionListener(e -> downloadCompiler(get, player, () -> {
				compile.setEnabled(true);
				compile.setSelected(true);
				compile.setToolTipText(null);
				get.setVisible(false);
			}));
			checks.add(get);
		}
		accessory.add(checks, java.awt.BorderLayout.NORTH);
		chooser.setAccessory(accessory);
		int answer;
		try {
			answer = PCARD.showFileChooser(chooser, PCARD.pc, true, false);
		} finally {
			java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(recorder);
		}
		if (answer != JFileChooser.APPROVE_OPTION) return;
		lastMenuBar = menuBar.isSelected();
		if (canCompile) lastCompile = compile.isSelected();
		Options options = new Options();
		options.menuBar = lastMenuBar;
		options.compileScripts = canCompile && compile.isSelected();
		List<String> chosen = parseGamepadKeys(padKeys.getText());
		// (the keys as found: found again with the other stacks it takes along; the author's own: kept for the next export)
		keepGamepadKeys(stack, chosen.equals(found) ? null : String.join(", ", chosen));
		options.gamepad = chosen.equals(found) ? null : chosen;
		File out = chooser.getSelectedFile();
		if (out == null) return;
		if (out.exists()) {
			new GDialog(PCARD.pc, intl.getDialogText("A file or folder with that name already exists. Choose another name.") + "\r" + out.getPath(), null, "OK", null, null);
			return;
		}
		PCARD.pc.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		try {
			List<String> notes = options.compileScripts ? exportWithProgress(stack, player, out, options) : export(stack, player, out, options);
			StringBuilder m = new StringBuilder(intl.getDialogText("The stack was exported for the web."));
			m.append('\r').append(out.getPath());
			m.append('\r').append(intl.getDialogText("Put the folder on a web server and open index.html there (opened as a file, it does not run)."));
			for (String n : notes) m.append('\r').append("• ").append(n);
			String tryIt = intl.getDialogText("Try in Browser");
			GDialog.clicked = "";
			new GDialog(PCARD.pc, m.toString(), null, "OK", null, tryIt);
			if (tryIt.equals(GDialog.clicked)) startTestServer(out);
		} catch (IOException e) {
			new GDialog(PCARD.pc, intl.getDialogText("Could not export the stack for the web.") + "\r" + e.getMessage(), null, "OK", null, null);
		} finally {
			PCARD.pc.setCursor(Cursor.getDefaultCursor());
		}
	}

	/** Downloads the script compiler while a window shows how far it is (with Cancel); then done on the event thread, or says why not */
	private static void downloadCompiler(java.awt.Component near, File player, Runnable done) {
		java.awt.Window owner = javax.swing.SwingUtilities.getWindowAncestor(near);
		javax.swing.JDialog window = new javax.swing.JDialog(owner, International.t("Script Compiler"), java.awt.Dialog.ModalityType.DOCUMENT_MODAL);
		window.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
		javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 10));
		panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 20, 16, 20));
		javax.swing.JLabel label = new javax.swing.JLabel(International.t("Downloading the script compiler…"));
		javax.swing.JProgressBar bar = new javax.swing.JProgressBar(0, 1000);
		bar.setIndeterminate(true);
		bar.setPreferredSize(new java.awt.Dimension(360, bar.getPreferredSize().height));
		javax.swing.JButton cancel = new javax.swing.JButton(International.t("Cancel"));
		boolean[] cancelled = {false};
		cancel.addActionListener(e -> { cancelled[0] = true; cancel.setEnabled(false); });
		javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
		buttons.add(cancel);
		panel.add(label, java.awt.BorderLayout.NORTH);
		panel.add(bar, java.awt.BorderLayout.CENTER);
		panel.add(buttons, java.awt.BorderLayout.SOUTH);
		window.setContentPane(panel);
		window.pack();
		window.setResizable(false);
		window.setLocationRelativeTo(owner);
		String[] problem = new String[1];
		Thread t = new Thread(() -> {
			try {
				problem[0] = WebAotBuild.install(player, new TesseractInstall.Progress() {
					public void report(String text, double fraction) {
						javax.swing.SwingUtilities.invokeLater(() -> {
							label.setText(text);
							bar.setIndeterminate(fraction < 0);
							if (fraction >= 0) bar.setValue((int) Math.round(fraction * 1000));
						});
					}
					public boolean cancelled() { return cancelled[0]; }
				});
			} catch (RuntimeException e) {
				problem[0] = e.toString();
			} finally {
				javax.swing.SwingUtilities.invokeLater(window::dispose);
			}
		}, "web-compiler-download");
		t.start();
		window.setVisible(true); //until the download has disposed of it
		if (problem[0] == null) done.run();
		else if (!cancelled[0]) javax.swing.JOptionPane.showMessageDialog(owner, problem[0], International.t("Script Compiler"), javax.swing.JOptionPane.WARNING_MESSAGE);
	}

	/** export while a window says the scripts are being compiled (TeaVM takes 15-30 s): the export runs on its own thread, the
	 *  window is modal so nothing else is done meanwhile */
	private static List<String> exportWithProgress(OStack stack, File player, File out, Options options) throws IOException {
		International intl = PCARD.pc.intl;
		javax.swing.JDialog window = new javax.swing.JDialog(PCARD.pc, intl.getDialogText("Export for Web"), java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		window.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
		javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 10));
		panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 20, 16, 20));
		panel.add(new javax.swing.JLabel(intl.getDialogText("Compiling the scripts for the web…")), java.awt.BorderLayout.NORTH);
		javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
		bar.setIndeterminate(true);
		panel.add(bar, java.awt.BorderLayout.CENTER);
		window.setContentPane(panel);
		window.pack();
		window.setResizable(false);
		window.setLocationRelativeTo(PCARD.pc);
		Object[] result = new Object[1];
		Thread t = new Thread(() -> {
			try { result[0] = export(stack, player, out, options); }
			catch (Throwable e) { result[0] = e; }
			finally { javax.swing.SwingUtilities.invokeLater(window::dispose); }
		}, "web-export");
		t.start();
		window.setVisible(true); //returns once the export has disposed of it
		try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		if (result[0] instanceof IOException e) throw e;
		if (result[0] instanceof RuntimeException e) throw e;
		if (result[0] instanceof Error e) throw e;
		@SuppressWarnings("unchecked") List<String> notes = (List<String>) result[0];
		return notes;
	}

	private static WebTestServer testServer;
	private static javax.swing.JDialog testServerWindow;

	/** Serves {@code folder} on this computer only, opens the page in the browser, and shows a small window whose Stop button
	 *  (or closing it, or quitting) ends the server. */
	static void startTestServer(File folder) {
		International intl = PCARD.pc.intl;
		stopTestServer();
		try {
			testServer = WebTestServer.start(folder);
		} catch (IOException e) {
			new GDialog(PCARD.pc, intl.getDialogText("Could not start the test server.") + "\r" + e.getMessage(), null, "OK", null, null);
			return;
		}
		final WebTestServer server = testServer;
		javax.swing.JDialog w = new javax.swing.JDialog((java.awt.Frame) null, intl.getDialogText("Test Server"), false);
		w.setAlwaysOnTop(true);
		javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.BorderLayout(8, 8));
		p.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 16, 12, 16));
		p.add(new javax.swing.JLabel("<html>" + intl.getDialogText("The test server is running (this computer only).") + "<br>" + server.url() + "</html>"), java.awt.BorderLayout.CENTER);
		javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
		javax.swing.JButton open = new javax.swing.JButton(intl.getDialogText("Open in Browser"));
		javax.swing.JButton stop = new javax.swing.JButton(intl.getDialogText("Stop Server"));
		open.addActionListener(e -> browse(server.url()));
		stop.addActionListener(e -> stopTestServer());
		buttons.add(open);
		buttons.add(stop);
		p.add(buttons, java.awt.BorderLayout.SOUTH);
		w.setContentPane(p);
		w.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
		w.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosing(java.awt.event.WindowEvent e) { stopTestServer(); } });
		w.pack();
		w.setLocationRelativeTo(null);
		w.setVisible(true);
		testServerWindow = w;
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop)); // quitting ends it too
		browse(server.url());
	}

	static void stopTestServer() {
		if (testServer != null) { testServer.stop(); testServer = null; }
		if (testServerWindow != null) { testServerWindow.dispose(); testServerWindow = null; }
	}

	private static void browse(String url) {
		try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); } catch (Exception ignored) {}
	}

	/**
	 * Writes the folder {@code out} (which must not exist): the player's files from {@code player}, the stack's unsaved
	 * changes saved first and the stack packed as stackFileName (with the icons its buttons use from Home or the stacks it
	 * uses), README.txt. Returns notes for the user (icons that could not be found). Nothing is left at out when it fails.
	 */
	static List<String> export(OStack stack, File player, File out) throws IOException { return export(stack, player, out, new Options()); }
	static List<String> export(OStack stack, File player, File out, Options options) throws IOException {
		File host = StackBundle.host(stack.file); // …/X.hzb/_stack.xml, the packed file's place for a single-file stack
		if (host != null && host.getName().equals("_stack.xml")) host = host.getParentFile();
		return export(stack, player, out, StackBundle.folderAround(stack.file), host == null ? null : host.getAbsoluteFile(), options);
	}

	/** export, with the folder the stack is in (where the other stacks of its work are) and the stack's own file or folder there */
	static List<String> export(OStack stack, File player, File out, File around, File self) throws IOException { return export(stack, player, out, around, self, new Options()); }
	static List<String> export(OStack stack, File player, File out, File around, File self, Options options) throws IOException {
		if (out.exists()) throw new IOException("It exists already: " + out.getPath());
		if (!hasPlayer(player)) throw new IOException("The web player's files are missing: " + player);
		List<String> notes = new ArrayList<>();
		File work = TempFiles.createDirectory("webexport");
		Path parent = out.getAbsoluteFile().getParentFile().toPath();
		Path temp = Files.createTempDirectory(parent, "." + out.getName() + ".");
		try {
			File source = stack.file.getAbsoluteFile().getParentFile();
			String stackFile = stack.file.getName();
			XMLwrite writer = stack.saveXML;
			Object lock = writer != null ? writer : stack;
			synchronized (lock) { // the autosave does not write while the files are copied
				if (writer != null && !writer.saveIfNeeded()) throw new IOException(writer.lastError != null ? writer.lastError : "The stack could not be saved.");
				copyStackFolder(source.toPath(), work.toPath());
			}
			// a stack with a password has its _stack.xml (and pictures) sealed, which the browser player cannot open
			if (StackProtection.sealsXml(stack)) {
				StackProtection.unsealCopy(work, stack);
				if (stack.getPrivateAccess()) notes.add(dialogText("The stack's Private Access does not apply in the browser: anyone who has the page can open the stack."));
			}
			notes.addAll(addUsedIcons(stack, new File(work, stackFile)));
			addUsedSounds(stack, new File(work, stackFile));
			String file = stackFileName(stack);
			// the other stacks of the work (start using stack "X", go stack "X"): beside it, unsealed and packed as they are named
			List<String> others = new ArrayList<>();
			Set<String> powerPc = new LinkedHashSet<>(powerPcOnlyExternals(work));
			boolean othersHave68k = false;
			String mainXml = new String(Files.readAllBytes(new File(work, stackFile).toPath()), StandardCharsets.UTF_8);
			for (File other : otherStacks(around, self, file, mainXml)) {
				File otherWork = new File(work.getParentFile(), work.getName() + "-" + others.size());
				// beside it, or in its folder there (DATA/fvr2_engine.hzb: the scripts give "DATA:fvr2_engine")
				String place = other.getParentFile().getAbsoluteFile().equals(around.getAbsoluteFile()) ? other.getName() : other.getParentFile().getName() + "/" + other.getName();
				try {
					if (other.isDirectory()) copyStackFolder(other.toPath(), otherWork.toPath());
					else unzip(other, otherWork);
					StackProtection.unsealCopy(otherWork);
					othersHave68k |= has68kExternals(new File(otherWork, "_stack.xml"));
					powerPc.addAll(powerPcOnlyExternals(otherWork));
					File packed = temp.resolve(place).toFile();
					packed.getParentFile().mkdirs();
					StackBundle.packFolder(otherWork, packed);
					others.add(place);
				} catch (IOException e) {
					notes.add(String.format(dialogText("A stack this stack uses was left out: %s"), other.getName() + " (" + e.getMessage() + ")"));
					Files.deleteIfExists(temp.resolve(place));
				} finally {
					deleteTree(otherWork.toPath());
				}
			}
			// Home, when the stack calls handlers only Home has (its utilities: lastHCItem, setUserLevelFive…) or names stack Home
			// itself (Window Tools reads and sets the script of stack Home); the player opens it as Home, in use by the stack
			// (HeadlessStacks.openPlayerStack)
			OStack home = HZ.home;
			if (home != null && home.file != null && home != stack && !others.contains(HOME_FILE)) {
				File homeFolder = home.file.getAbsoluteFile().getParentFile();
				File homeXml = new File(homeFolder, "_stack.xml");
				List<String> texts = new ArrayList<>();
				texts.add(mainXml);
				for (String o : others) { String x = stackXml(temp.resolve(o).toFile()); if (x != null) texts.add(x); }
				if (homeXml.isFile() && (namesHomeStack(texts) || !homeHandlersCalled(new String(Files.readAllBytes(homeXml.toPath()), StandardCharsets.UTF_8), texts).isEmpty())) {
					File homeWork = new File(work.getParentFile(), work.getName() + "-home");
					try {
						copyStackFolder(homeFolder.toPath(), homeWork.toPath());
						StackProtection.unsealCopy(homeWork);
						StackBundle.packFolder(homeWork, temp.resolve(HOME_FILE).toFile());
						others.add(HOME_FILE);
					} finally {
						deleteTree(homeWork.toPath());
					}
				}
			}
			// the movies, sounds and other files beside it the scripts name (GoldCatcher's GCM1.Movie: FPass & ":GCM" & n & ".Movie"),
			// with their resource fork and Finder info as an AppleDouble ._name file (a movie's 'moov' is often in its fork)
			List<String> stackTexts = new ArrayList<>();
			stackTexts.add(mainXml);
			for (String o : others) { String x = stackXml(temp.resolve(o).toFile()); if (x != null) stackTexts.add(x); }
			long total = 0;
			for (File media : namedFiles(around, scriptText(withoutOwnFiles(String.join("\n", stackTexts))), stack.name == null ? null : stack.name.trim())) {
				if (total + media.length() > MAX_FILES_BYTES) { notes.add(String.format(dialogText("A file this stack uses was left out: %s"), media.getName() + " (" + (media.length() >> 20) + " MB)")); continue; }
				String place = media.getParentFile().getAbsoluteFile().equals(around.getAbsoluteFile()) ? media.getName() : media.getParentFile().getName() + "/" + media.getName();
				File copy = temp.resolve(place).toFile();
				copy.getParentFile().mkdirs();
				Files.copy(media.toPath(), copy.toPath());
				total += media.length();
				others.add(place);
				byte[] fork = ResourceFile68k.readForkBytes(media, null), info = FinderInfo68k.read(media);
				boolean noInfo = info == null; if (info != null) { noInfo = true; for (byte b : info) if (b != 0) noInfo = false; }
				if ((fork == null || fork.length == 0) && noInfo) continue;
				String sidecar = (place.contains("/") ? place.substring(0, place.lastIndexOf('/') + 1) : "") + "._" + media.getName();
				Files.write(temp.resolve(sidecar), ResourceFile68k.AppleDouble.build(fork == null ? new byte[0] : fork, noInfo ? null : java.util.Arrays.copyOf(info, 32))); // (FInfo, then FXInfo)
				others.add(sidecar);
			}
			// the folders the scripts name, made in the browser though nothing of theirs went (RAVEN's FolderCheck: "BGM Movies")
			for (File folder : namedFolders(around, scriptText(withoutOwnFiles(String.join("\n", stackTexts))))) {
				String prefix = folder.getName() + "/";
				boolean any = false;
				for (String o : others) any |= o.startsWith(prefix);
				if (!any) { others.add(prefix); Files.createDirectories(temp.resolve(folder.getName())); }
			}
			// HyperCard 2.4's built-in Movie XCMD (open movie, Movie f,…) plays in the 68K player too (MoviePlayer68k's decoders)
			boolean has68k = has68kExternals(new File(work, stackFile)) || othersHave68k || usesBuiltInMovie(String.join("\n", stackTexts));
			boolean with68k = has68k && new File(player, PLAYER_68K).isFile();
			if (!powerPc.isEmpty()) notes.add(String.format(dialogText("These external commands need PowerPC code, which the web player does not run: %s"), String.join(", ", powerPc)));
			if (has68k && !with68k) notes.add(dialogText("The stack's 68K external commands need the 68K web player, which is not in this copy of HyperZebra: they do nothing in the browser."));
			addCachedTranslations(stack, work);
			StackBundle.packFolder(work, temp.resolve(file).toFile());
			for (String n : PLAYER_FILES) Files.copy(new File(player, n).toPath(), temp.resolve(n), StandardCopyOption.COPY_ATTRIBUTES);
			if (with68k) Files.copy(new File(player, PLAYER_68K).toPath(), temp.resolve(PLAYER_68K), StandardCopyOption.COPY_ATTRIBUTES);
			// the scripts compiled ahead of time into the player the page loads (the 68K one when it has that); the prebuilt one
			// when it cannot be done
			if (options.compileScripts && WebAotBuild.available(player, with68k)) {
				List<String> scripts = new ArrayList<>();
				for (String x : stackTexts) scripts.addAll(WebAotBuild.scripts(x));
				options.compiled = WebAotBuild.build(player, scripts, with68k, temp.resolve(with68k ? PLAYER_68K : "player.wasm").toFile());
				if (options.compiled.failure != null)
					notes.add(String.format(dialogText("The scripts were not compiled (they run as they are, more slowly): %s"), options.compiled.failure));
			}
			// HyperZebra's language catalogs: the page shows the stack in the viewer's language (its menus, the words of its dialogs,
			// the stack's own Languages/*.po translations)
			File langs = new File(player, LANG_FOLDER);
			if (langs.isDirectory()) copyStackFolder(langs.toPath(), temp.resolve(LANG_FOLDER));
			// the page opens the stack by its own name (HyperCard names a stack after its file), with the 68K player when it needs it
			Path index = temp.resolve("index.html");
			String page = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
			// the CD the stack came on, by the name its scripts give it ("Cosmic Osmo CD:Cosmic Osmo Files:CO.Resource"): the
			// stack's folder in the browser (namedVolumes)
			List<String> volumes = namedVolumes(around, withoutOwnFiles(String.join("\n", stackTexts)).replace("&quot;", "\"").replace("&amp;", "&"));
			List<String> gamepad = options.gamepad != null ? options.gamepad : gamepadKeys(work, stackTexts);
			// the stack's speed limit (StackSpeed: its scripts, its card display and its XCMDs at the pace of a Mac of the time)
			double mips = options.mips != null ? Math.max(0, options.mips) : speedLimitOf(stack);
			String attributes = " data-stack=\"" + htmlAttribute(file) + "\"" + (with68k ? " data-wasm=\"" + PLAYER_68K + "\"" : "")
				+ (others.isEmpty() ? "" : " data-others=\"" + htmlAttribute(String.join("|", others)) + "\"")
				+ (volumes.isEmpty() ? "" : " data-volumes=\"" + htmlAttribute(String.join("|", volumes)) + "\"")
				+ (options.menuBar ? "" : " data-menubar=\"hidden\"") // (the page has no menu bar)
				// the keys of the virtual game pad a touch screen shows, for a stack that reads held keys (gamepadKeys)
				+ (gamepad.isEmpty() ? "" : " data-gamepad=\"" + htmlAttribute(String.join(",", gamepad)) + "\"")
				+ (mips > 0 ? " data-mips=\"" + mipsText(mips) + "\"" : "");
			Files.write(index, page.replaceFirst("<html( [^>]*)?>", "<html$1" + java.util.regex.Matcher.quoteReplacement(attributes) + ">").getBytes(StandardCharsets.UTF_8));
			Files.write(temp.resolve("README.txt"), readme(stack, with68k, options.compiled != null && options.compiled.failure == null, !gamepad.isEmpty(), mips).getBytes(StandardCharsets.UTF_8));
			Files.move(temp, out.toPath());
		} finally {
			deleteTree(temp);
			deleteTree(work.toPath());
		}
		return notes;
	}

	private static final Pattern EXTERNAL = Pattern.compile("<externalcommand\\b([^>]*)>");
	private static String attribute(String attrs, String name) {
		Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
		return m.find() ? m.group(1) : "";
	}
	/**
	 * The externals of a stack folder whose code is PowerPC only: listed for PowerPC alone (macppc), or with a 68K half that
	 * only reports it needs a Power Macintosh (a "safe fat" one: PgColorX 1.22 answers "68K is unsupported"). The desktop
	 * runs their PowerPC code; the web player runs 68K code only. One with no code (platform "java", answered by HyperZebra
	 * itself) is not one.
	 */
	static List<String> powerPcOnlyExternals(File folder) throws IOException {
		File xml = new File(folder, "_stack.xml");
		if (!xml.isFile()) return new ArrayList<>();
		java.util.Map<String, Boolean> runs = new java.util.LinkedHashMap<>(); // name (type) -> has 68K code that works
		for (Matcher m = EXTERNAL.matcher(new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8)); m.find(); ) {
			String a = m.group(1), name = attribute(a, "name"), platform = attribute(a, "platform").toLowerCase(java.util.Locale.ROOT);
			String key = name + " (" + attribute(a, "type") + ")";
			if (!platform.contains("68k") && !platform.contains("ppc")) continue; // no code: HyperZebra's own ("java": Mosslight's KStealKeyX)
			boolean works = false;
			if (platform.contains("68k")) {
				File code = new File(folder, attribute(a, "file"));
				works = !code.isFile() || !PpcRuntime.powerPcOnlyFat(Files.readAllBytes(code.toPath()));
			}
			runs.merge(key, works, Boolean::logicalOr);
		}
		List<String> out = new ArrayList<>();
		for (java.util.Map.Entry<String, Boolean> e : runs.entrySet()) if (!e.getValue()) out.add(e.getKey().replaceFirst(" \\(.*\\)$", ""));
		return out;
	}

	/** Home's file beside the stack in the export (the player opens a stack of that name as Home) */
	static final String HOME_FILE = "Home.hzb";
	private static final Pattern HANDLER = Pattern.compile("(?im)^[ \\t]*(?:on|function)[ \\t]+([a-z_][a-z0-9_]*)");
	private static final Pattern SCRIPT = Pattern.compile("(?s)<script>(.*?)</script>");

	/**
	 * Home's handlers that a stack's scripts call (a statement that starts with the name, or name( for a function) and do not
	 * define themselves. HyperCard's own messages do not count (Home handling openStack is not why a stack needs it).
	 */
	static Set<String> homeHandlersCalled(String homeXml, List<String> stackXmls) {
		Set<String> homes = new LinkedHashSet<>(), own = new LinkedHashSet<>(), called = new LinkedHashSet<>();
		for (Matcher m = HANDLER.matcher(scripts(homeXml)); m.find(); ) homes.add(m.group(1).toLowerCase(java.util.Locale.ROOT));
		StringBuilder all = new StringBuilder();
		for (String x : stackXmls) all.append(scripts(x)).append('\n');
		String text = all.toString().toLowerCase(java.util.Locale.ROOT);
		for (Matcher m = HANDLER.matcher(text); m.find(); ) own.add(m.group(1));
		for (String h : homes) {
			if (own.contains(h) || TUtil.isSystemMessage(h)) continue;
			String q = Pattern.quote(h);
			if (Pattern.compile("(?m)^[ \\t]*" + q + "(?![a-z0-9_])").matcher(text).find() || Pattern.compile("(?<![a-z0-9_])" + q + "[ \\t]*\\(").matcher(text).find()) called.add(h);
		}
		return called;
	}
	private static final Pattern HOME_STACK = Pattern.compile("(?<![a-z0-9_])stack[ \\t]+(?:\"home\"|home)(?![a-z0-9_])");
	/** Whether a stack's scripts name stack Home as an object (the script of stack Home, stack "Home"): HyperCard always has one */
	static boolean namesHomeStack(List<String> stackXmls) {
		for (String x : stackXmls) if (HOME_STACK.matcher(scripts(x).toLowerCase(java.util.Locale.ROOT)).find()) return true;
		return false;
	}
	/** The scripts of a stack file, one after another (the text between <script> and </script>, entities as they are read) */
	private static String scripts(String xml) {
		StringBuilder b = new StringBuilder();
		for (Matcher m = SCRIPT.matcher(xml); m.find(); ) b.append(m.group(1).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")).append('\n');
		return b.toString();
	}

	/** The files beside a stack that go with it come to at most this much */
	static final long MAX_FILES_BYTES = 256L << 20;
	private static final java.util.Set<String> MEDIA_EXTENSIONS = java.util.Set.of("mov", "moov", "movie", "qt", "mp3", "mid", "midi", "aif", "aiff", "aifc", "wav", "snd");
	private static final java.util.Set<String> MEDIA_TYPES = java.util.Set.of("MooV", "Midi", "AIFF", "AIFC", "sfil", "MPG3", "WAVE");

	/**
	 * The files (not stacks) beside a stack, or in a folder beside it, that its scripts name: the name as it is ("Original.TTR",
	 * "BGM:Title"), or for a movie or sound file a numbered name whose other parts the scripts give ("GCM" and ".Movie" for
	 * GCM1.Movie: a script puts the number in while it runs). {@code text} is the scripts' text as scriptText gives it, with
	 * the stack's own files left out (withoutOwnFiles). Not a stack's source: a file with the name of the stack (stackName,
	 * may be null) beside it, or one beside a .hzb of its name (Train Set beside Train Set.hzb: the raw HyperCard stack it
	 * was converted from), though a script gives that name (Train Set's getStackName: return "Train Set").
	 */
	static List<File> namedFiles(File around, String text) { return namedFiles(around, text, null); }
	static List<File> namedFiles(File around, String text, String stackName) {
		List<File> found = new ArrayList<>();
		if (around == null || !around.isDirectory()) return found;
		List<File> candidates = new ArrayList<>();
		java.io.FileFilter plain = f -> f.isFile() && !f.getName().startsWith(".") && !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") && !f.getName().equals("Icon\r");
		File[] beside = around.listFiles(plain);
		if (beside != null) candidates.addAll(java.util.Arrays.asList(beside));
		File[] folders = around.listFiles(f -> f.isDirectory() && !f.getName().startsWith(".") && !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") && !f.getName().equals("_unpacked"));
		if (folders != null) for (File folder : folders) { File[] in = folder.listFiles(plain); if (in != null) candidates.addAll(java.util.Arrays.asList(in)); }
		java.util.Collections.sort(candidates);
		for (File f : candidates) {
			String name = f.getName().toLowerCase(java.util.Locale.ROOT);
			boolean inFolder = !f.getParentFile().getAbsoluteFile().equals(around.getAbsoluteFile());
			String path = inFolder ? f.getParentFile().getName().toLowerCase(java.util.Locale.ROOT) + ":" + name : name;
			if (!inFolder && stackName != null && f.getName().equalsIgnoreCase(stackName) || new File(f.getParentFile(), f.getName() + ".hzb").exists()) continue;
			boolean named = inFolder && namesFolder(text, f.getParentFile().getName()) || text.contains("\"" + name + "\"") || text.contains(":" + name + "\"") || inFolder && text.contains(path + "\"")
				// the whole text of a field or button, or a line of it (M.J.DASH's song list: cd fld "songs" holds justice-gu.Movie)
				|| text.contains(">" + name + "<") || text.contains(">" + name + "\n") || text.contains("\n" + name + "<") || text.contains("\n" + name + "\n");
			if (!named && isMedia(f)) {
				// the parts between the numbers, each long enough to mean something, all in the scripts
				String[] parts = name.split("[0-9]+");
				boolean all = parts.length > 1 || !name.equals(parts[0]);
				for (String part : parts) if (part.length() >= 2 && !text.contains(part)) all = false; else if (part.length() < 2 && !part.isEmpty()) all = false;
				named = all && parts.length > 0;
			}
			if (named) found.add(f);
		}
		return found;
	}
	/**
	 * A stack file's text without the names of the stack's own files in its folder: its <media> entries (Train Set's
	 * <file>vers_2.data</file>, which is not a file of "2.4 Player Resources" beside it), the pictures of cards and
	 * backgrounds (<bitmap>), its patterns, its externals' file="…". What is left is the scripts and the objects' texts.
	 */
	static String withoutOwnFiles(String xml) {
		return OWN_FILES.matcher(xml).replaceAll("");
	}
	private static final Pattern OWN_FILES = Pattern.compile("(?s)<media>.*?</media>|<(bitmap|pattern|file)>[^<]*</\\1>| file=\"[^\"]*\"");
	private static boolean isMedia(File f) {
		String n = f.getName();
		int dot = n.lastIndexOf('.');
		if (dot > 0 && MEDIA_EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) return true;
		byte[] info = FinderInfo68k.read(f);
		return info != null && info.length >= 4 && MEDIA_TYPES.contains(new String(info, 0, 4, StandardCharsets.ISO_8859_1));
	}

	/** At most this many other stacks go with a stack */
	static final int MAX_OTHER_STACKS = 30;

	/**
	 * The other stacks of a stack's work: the stacks beside it (.hzb in its folder) whose names its scripts give, quoted
	 * ("DD lib") or after the word stack (go stack Items), the stacks in a folder beside it that the scripts give by that
	 * folder and name ("DATA:fvr2_engine": FROMINE VR2 and KYMG keep their engines there), and the stacks those name in
	 * turn. Names a script puts together while it runs are not known here. self (the stack's own file or folder) and a
	 * stack with the main stack's name are left out.
	 */
	static List<File> otherStacks(File around, File self, String mainFile, String mainXml) {
		List<File> found = new ArrayList<>();
		java.io.FileFilter stackFile = f -> { String n = f.getName(); return n.toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") && !n.startsWith("._") && !n.startsWith("."); };
		File[] beside = around == null ? null : around.listFiles(stackFile);
		if (beside == null) return found;
		List<File> list = new ArrayList<>(java.util.Arrays.asList(beside));
		File[] folders = around.listFiles(f -> f.isDirectory() && !stackFile.accept(f) && !f.getName().startsWith(".") && !f.getName().equals("_unpacked"));
		if (folders != null) for (File folder : folders) { File[] in = folder.listFiles(stackFile); if (in != null) list.addAll(java.util.Arrays.asList(in)); }
		File[] all = list.toArray(new File[0]);
		java.util.Arrays.sort(all);
		Set<File> left = new LinkedHashSet<>();
		for (File f : all) if (self == null || !f.getAbsoluteFile().equals(self)) left.add(f);
		java.util.ArrayDeque<String> texts = new java.util.ArrayDeque<>();
		texts.add(scriptText(mainXml));
		while (!texts.isEmpty() && found.size() < MAX_OTHER_STACKS) {
			String text = texts.poll();
			for (java.util.Iterator<File> it = left.iterator(); it.hasNext() && found.size() < MAX_OTHER_STACKS; ) {
				File f = it.next();
				String base = f.getName().substring(0, f.getName().length() - 4);
				boolean inFolder = !f.getParentFile().getAbsoluteFile().equals(around.getAbsoluteFile());
				if (!inFolder && f.getName().equalsIgnoreCase(mainFile)) { it.remove(); continue; }
				if (inFolder ? !text.contains((f.getParentFile().getName() + ":" + base).toLowerCase(java.util.Locale.ROOT) + "\"")
						&& !namesFolder(text, f.getParentFile().getName())
					: !namesStack(text, base.toLowerCase(java.util.Locale.ROOT))) continue;
				it.remove();
				found.add(f);
				String xml = stackXml(f);
				if (xml != null) texts.add(scriptText(xml));
			}
		}
		return found;
	}
	/**
	 * Whether a script text gives a folder's name as a path part ("Resource Stacks", ":Maps:", the list
	 * "Resource Stacks,BGM Movies,Maps,Saved Games"): between quotes, colons or commas. RAVEN -Wings ZERO- finds its
	 * resource and map stacks by listing such folders while it runs (MtFilesInFldr(getPath("Res"),"T:STAK")), so all that
	 * is in them goes with it.
	 */
	static boolean namesFolder(String text, String folder) {
		String name = folder.toLowerCase(java.util.Locale.ROOT);
		if (name.length() < 3) return false;
		for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
			int end = at + name.length();
			char before = at == 0 ? ' ' : text.charAt(at - 1), after = end >= text.length() ? ' ' : text.charAt(end);
			// (":Maps:" ends the string there: "DATA:fvr2_engine" names one stack in DATA, not all of it)
			if ("\":,".indexOf(before) >= 0 && (after == '"' || after == ',' || after == ':' && end + 1 < text.length() && text.charAt(end + 1) == '"')) return true;
		}
		return false;
	}
	/**
	 * The volume names the scripts give before a path to the files beside the stack: "Cosmic Osmo CD" from
	 * "Cosmic Osmo CD:Cosmic Osmo Files:CO.Resource", where Cosmic Osmo Files/CO.Resource (or CO.Resource.hzb, its conversion)
	 * is there. A CD-ROM title checks for its CD by such a path ("Can't find required resources…" and quits); the browser
	 * has no such disk, so the page names the stack's folder so (data-volumes, PlayerUiHost.pageVolumes). Only a name with a
	 * path after it that is found there counts, so a volume the scripts merely mention ("Macintosh HD:System Folder:") is
	 * not one. In the scripts' own case (the first seen).
	 */
	static List<String> namedVolumes(File around, String text) {
		List<String> found = new ArrayList<>();
		if (around == null || !around.isDirectory()) return found;
		for (int at = text.indexOf('"'); at >= 0; ) {
			int end = text.indexOf('"', at + 1);
			if (end < 0) break;
			String s = text.substring(at + 1, end);
			at = text.indexOf('"', end + 1);
			int colon = s.indexOf(':');
			if (colon <= 0 || colon > 27 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) continue;
			String volume = s.substring(0, colon), rest = s.substring(colon + 1);
			while (rest.endsWith(":")) rest = rest.substring(0, rest.length() - 1);
			if (rest.isEmpty() || volume.trim().isEmpty() || volume.equalsIgnoreCase("Macintosh HD") || volume.equalsIgnoreCase(FileSystem68k.BOOT_VOLUME_NAME) || volume.equalsIgnoreCase("Home")) continue;
			boolean known = false;
			for (String v : found) known |= v.equalsIgnoreCase(volume);
			if (known || !existsBeside(around, rest.split(":"))) continue;
			found.add(volume);
		}
		return found;
	}
	/** Whether the Mac path parts name a file or folder in that folder (each part in any case; the last as name or name.hzb) */
	private static boolean existsBeside(File folder, String[] parts) {
		File at = folder;
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i].replace('/', ':'); // (a "/" in a Mac name is a ":" in the host's)
			if (part.isEmpty()) return false;
			File[] in = at.listFiles();
			if (in == null) return false;
			File next = null;
			for (File f : in) {
				String n = f.getName();
				if (n.equalsIgnoreCase(part) || i == parts.length - 1 && n.equalsIgnoreCase(part + ".hzb")) { next = f; break; }
			}
			if (next == null) return false;
			at = next;
		}
		return true;
	}
	/** The folders beside a stack whose names its scripts give (namesFolder): they go with it, empty ones too */
	static List<File> namedFolders(File around, String text) {
		List<File> found = new ArrayList<>();
		File[] folders = around == null ? null : around.listFiles(f -> f.isDirectory() && !f.getName().startsWith(".")
			&& !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb") && !f.getName().equals("_unpacked"));
		if (folders == null) return found;
		java.util.Arrays.sort(folders);
		for (File f : folders) if (namesFolder(text, f.getName())) found.add(f);
		return found;
	}
	/** A stack file's text as scripts give names: lower case, quotes as they are */
	private static String scriptText(String xml) {
		return xml.replace("&quot;", "\"").replace("&amp;", "&").toLowerCase(java.util.Locale.ROOT);
	}
	/** Whether a script text gives that stack name: "name", or stack name / stack "name" ending there */
	static boolean namesStack(String text, String name) {
		if (name.isEmpty()) return false;
		if (text.contains("\"" + name + "\"")) return true;
		// a path ending in it: "Cosmic Osmo CD:Cosmic Osmo Help" (go to the stack on the CD the work came on)
		if (text.contains(":" + name + "\"")) return true;
		String key = "stack " + name;
		for (int at = text.indexOf(key); at >= 0; at = text.indexOf(key, at + 1)) {
			int end = at + key.length();
			if (end >= text.length() || !Character.isLetterOrDigit(text.charAt(end))) return true;
		}
		return false;
	}
	/** The _stack.xml of a stack folder or a single-file .hzb, unsealed with HyperZebra's key; null when it cannot be read */
	private static String stackXml(File stack) {
		try {
			byte[] data;
			if (stack.isDirectory()) {
				File x = new File(stack, "_stack.xml");
				if (!x.isFile()) return null;
				data = Files.readAllBytes(x.toPath());
			} else {
				try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(stack)) {
					java.util.zip.ZipEntry e = z.getEntry("_stack.xml");
					if (e == null) return null;
					try (java.io.InputStream in = z.getInputStream(e)) { data = in.readAllBytes(); }
				}
			}
			if (StackProtection.isSealed(data)) {
				if (StackProtection.needsPassword(data)) return null;
				data = StackProtection.unseal(data, null);
			}
			return new String(data, StandardCharsets.UTF_8);
		} catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
			return null;
		}
	}
	/** A single-file .hzb's entries into a folder */
	private static void unzip(File zip, File folder) throws IOException {
		try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
			for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
				String n = e.getName();
				if (e.isDirectory() || n.contains("..") || n.equals("mimetype")) continue;
				File f = new File(folder, n);
				f.getParentFile().mkdirs();
				Files.copy(z, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static final Pattern MOVIE_COMMAND = Pattern.compile("(?im)^[ \\t]*(?:open[ \\t]+movie|movie)[ \\t]+\\S");
	private static final Pattern SCRIPT_ELEMENT = Pattern.compile("(?s)<script>(.*?)</script>");
	/** Whether the stack XML's scripts (not its fields' text) open movies with HyperCard 2.4's own Movie XCMD (open movie f /
	 *  Movie f,style,…) */
	static boolean usesBuiltInMovie(String xml) {
		if (xml == null) return false;
		for (Matcher m = SCRIPT_ELEMENT.matcher(xml); m.find(); ) if (MOVIE_COMMAND.matcher(m.group(1)).find()) return true;
		return false;
	}

	/**
	 * The keys of the page's virtual game pad (player.html shows it on touch screens: a phone has no keyboard), for a stack
	 * that reads keys as they are held: one that calls KStealKeyX (its keys from the strings it is given, the most used first:
	 * "^op^cm^au^ad" = option, command, up, down), one whose 68K externals call GetKeys ($A976: they poll the keyboard), or
	 * one with an arrowKey handler. Tokens: up down left right space return tab esc delete fwddelete shift option control
	 * command, num0…num9 (the keypad), a letter, a digit or another character (comma for ","). Empty: no pad.
	 */
	static List<String> gamepadKeys(File folder, List<String> stackXmls) throws IOException {
		StringBuilder all = new StringBuilder();
		for (String x : stackXmls) all.append(scripts(x)).append('\n');
		String text = all.toString();
		boolean steal = STEAL_CALL.matcher(text).find(), getKeys = false;
		for (String x : stackXmls) for (Matcher m = EXTERNAL.matcher(x); m.find(); ) if (attribute(m.group(1), "name").toLowerCase(java.util.Locale.ROOT).contains("stealkey")) steal = true;
		// the stack's own 68K externals that poll the keyboard (GetKeys at a word boundary in their code)
		File xml = new File(folder, "_stack.xml");
		if (xml.isFile()) {
			for (Matcher m = EXTERNAL.matcher(new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8)); m.find() && !getKeys; ) {
				String a = m.group(1);
				if (!attribute(a, "platform").toLowerCase(java.util.Locale.ROOT).contains("68k")) continue;
				File code = new File(folder, attribute(a, "file"));
				if (!code.isFile() || code.length() > (4 << 20)) continue;
				byte[] b = Files.readAllBytes(code.toPath());
				for (int i = 0; i + 1 < b.length; i += 2) if (b[i] == (byte) 0xA9 && b[i + 1] == (byte) 0x76) { getKeys = true; break; }
			}
		}
		boolean arrowKey = ARROWKEY_HANDLER.matcher(text).find();
		if (!steal && !getKeys && !arrowKey) return new ArrayList<>();
		// the keys named in KStealKeyX's strings (its calls' literals, and any string of its ^xx names), each counted
		java.util.Map<String, Integer> count = new java.util.LinkedHashMap<>();
		if (steal) {
			for (Matcher m = STEAL_LITERAL.matcher(text); m.find(); ) for (String t : stealTokens(m.group(1))) count.merge(t, 1, Integer::sum);
			for (Matcher m = STEAL_QUOTED.matcher(text); m.find(); ) if (STEAL_NAME.matcher(m.group(1)).find() && !STEAL_LITERAL_AT.matcher(text.substring(Math.max(0, m.start() - 24), m.start() + 1)).find())
				for (String t : stealTokens(m.group(1))) count.merge(t, 1, Integer::sum);
		}
		List<String> out = new ArrayList<>(count.keySet());
		// the most used first (a key in every call is the game's main one), then as they first came
		List<String> order = new ArrayList<>(out);
		out.sort((p, q) -> count.get(q) - count.get(p) != 0 ? count.get(q) - count.get(p) : order.indexOf(p) - order.indexOf(q));
		if (out.isEmpty()) out.addAll(steal || getKeys ? List.of("up", "down", "left", "right", "space", "return") : List.of("up", "down", "left", "right"));
		return out;
	}
	/** The game pad's keys found in the stack as it is on the disk (the export dialog's first choice), or none */
	private static List<String> foundGamepadKeys(OStack stack) {
		try {
			File folder = stack.file.getAbsoluteFile().getParentFile();
			File xml = stack.file.isFile() ? stack.file : new File(folder, "_stack.xml");
			if (!xml.isFile()) return new ArrayList<>();
			return gamepadKeys(folder, List.of(new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new ArrayList<>();
		}
	}
	private static final java.util.prefs.Preferences PAD_PREFS = java.util.prefs.Preferences.userRoot().node("HyperZebra/WebExport/gamepad");
	private static String padKey(OStack stack) {
		return Integer.toHexString(stack.file.getAbsoluteFile().getParentFile().getPath().hashCode());
	}
	/** The keys the author chose for this stack's game pad the last time (null: the ones found in it) */
	private static String keptGamepadKeys(OStack stack) {
		try { return PAD_PREFS.get(padKey(stack), null); } catch (RuntimeException e) { return null; }
	}
	private static void keepGamepadKeys(OStack stack, String keys) {
		try { if (keys == null) PAD_PREFS.remove(padKey(stack)); else PAD_PREFS.put(padKey(stack), keys); } catch (RuntimeException e) {}
	}
	private static final java.util.Set<String> PAD_NAMES = java.util.Set.of("up", "down", "left", "right", "space", "return", "enter", "tab", "esc",
		"delete", "fwddelete", "comma", "shift", "option", "command", "control");
	private static final String PAD_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789;=-./`[\\]'";
	/** The game pad's keys as typed in the export dialog (names, commas or spaces between): the ones the page knows, each once */
	static List<String> parseGamepadKeys(String text) {
		List<String> out = new ArrayList<>();
		for (String t : (text == null ? "" : text).split("[\\s,]+")) {
			t = t.toLowerCase(java.util.Locale.ROOT);
			if (t.equals("cmd")) t = "command"; else if (t.equals("opt") || t.equals("alt")) t = "option"; else if (t.equals("ctrl")) t = "control";
			else if (t.equals("escape")) t = "esc";
			boolean ok = PAD_NAMES.contains(t) || t.matches("num[0-9]") || t.length() == 1 && PAD_CHARACTERS.indexOf(t.charAt(0)) >= 0;
			if (ok && !out.contains(t)) out.add(t);
		}
		return out;
	}
	/** A key pressed in the export dialog as a game pad key name (null: none the pad has) */
	static String gamepadToken(java.awt.event.KeyEvent e) {
		int k = e.getKeyCode();
		boolean numpad = e.getKeyLocation() == java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD;
		if (k >= java.awt.event.KeyEvent.VK_NUMPAD0 && k <= java.awt.event.KeyEvent.VK_NUMPAD9) return "num" + (k - java.awt.event.KeyEvent.VK_NUMPAD0);
		if (numpad && k >= java.awt.event.KeyEvent.VK_0 && k <= java.awt.event.KeyEvent.VK_9) return "num" + (k - java.awt.event.KeyEvent.VK_0);
		if (k >= java.awt.event.KeyEvent.VK_A && k <= java.awt.event.KeyEvent.VK_Z) return String.valueOf((char) ('a' + k - java.awt.event.KeyEvent.VK_A));
		if (k >= java.awt.event.KeyEvent.VK_0 && k <= java.awt.event.KeyEvent.VK_9) return String.valueOf((char) ('0' + k - java.awt.event.KeyEvent.VK_0));
		switch (k) {
			case java.awt.event.KeyEvent.VK_UP: case java.awt.event.KeyEvent.VK_KP_UP: return "up";
			case java.awt.event.KeyEvent.VK_DOWN: case java.awt.event.KeyEvent.VK_KP_DOWN: return "down";
			case java.awt.event.KeyEvent.VK_LEFT: case java.awt.event.KeyEvent.VK_KP_LEFT: return "left";
			case java.awt.event.KeyEvent.VK_RIGHT: case java.awt.event.KeyEvent.VK_KP_RIGHT: return "right";
			case java.awt.event.KeyEvent.VK_SPACE: return "space";
			case java.awt.event.KeyEvent.VK_ENTER: return numpad ? "enter" : "return";
			case java.awt.event.KeyEvent.VK_TAB: return "tab";
			case java.awt.event.KeyEvent.VK_ESCAPE: return "esc";
			case java.awt.event.KeyEvent.VK_BACK_SPACE: return "delete";
			case java.awt.event.KeyEvent.VK_DELETE: return "fwddelete";
			case java.awt.event.KeyEvent.VK_SHIFT: return "shift";
			case java.awt.event.KeyEvent.VK_ALT: return "option";
			case java.awt.event.KeyEvent.VK_META: return "command";
			case java.awt.event.KeyEvent.VK_CONTROL: return "control";
			case java.awt.event.KeyEvent.VK_COMMA: return "comma";
			case java.awt.event.KeyEvent.VK_SEMICOLON: return ";";
			case java.awt.event.KeyEvent.VK_EQUALS: return "=";
			case java.awt.event.KeyEvent.VK_MINUS: return "-";
			case java.awt.event.KeyEvent.VK_PERIOD: return ".";
			case java.awt.event.KeyEvent.VK_SLASH: return "/";
			case java.awt.event.KeyEvent.VK_BACK_QUOTE: return "`";
			case java.awt.event.KeyEvent.VK_OPEN_BRACKET: return "[";
			case java.awt.event.KeyEvent.VK_BACK_SLASH: return "\\";
			case java.awt.event.KeyEvent.VK_CLOSE_BRACKET: return "]";
			case java.awt.event.KeyEvent.VK_QUOTE: return "'";
			default: return null;
		}
	}
	private static final Pattern STEAL_CALL = Pattern.compile("(?i)stealkey[a-z0-9]*[ \\t]*\\(");
	private static final Pattern STEAL_LITERAL = Pattern.compile("(?i)stealkey[a-z0-9]*[ \\t]*\\([ \\t]*\"([^\"\\r\\n]*)\"");
	private static final Pattern STEAL_LITERAL_AT = Pattern.compile("(?i)stealkey[a-z0-9]*[ \\t]*\\([ \\t]*\"$");
	private static final Pattern STEAL_QUOTED = Pattern.compile("\"([^\"\\r\\n]*)\"");
	private static final Pattern STEAL_NAME = Pattern.compile("\\^(?:au|ad|al|ar|re|sh|sf|op|ct|cm|es|de|dl|t[0-9])");
	private static final Pattern ARROWKEY_HANDLER = Pattern.compile("(?im)^[ \\t]*on[ \\t]+arrowkey\\b");
	/** KStealKeyX's key string as pad tokens (HzKStealKeyX's names; Caps Lock is no key to press) */
	static List<String> stealTokens(String keys) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < keys.length(); i++) {
			char c = keys.charAt(i);
			String t = null;
			if (c == '^' && i + 2 < keys.length()) {
				String k = keys.substring(i + 1, i + 3).toLowerCase(java.util.Locale.ROOT);
				i += 2;
				switch (k) {
					case "au": t = "up"; break; case "ad": t = "down"; break; case "al": t = "left"; break; case "ar": t = "right"; break;
					case "re": t = "return"; break; case "sh": case "sf": t = "shift"; break; case "op": t = "option"; break;
					case "ct": t = "control"; break; case "cm": t = "command"; break; case "es": t = "esc"; break;
					case "de": t = "delete"; break; case "dl": t = "fwddelete"; break;
					default: if (k.charAt(0) == 't' && Character.isDigit(k.charAt(1))) t = "num" + k.charAt(1);
				}
			} else if (c == ' ') t = "space";
			else if (c == ',') t = "comma";
			else if (c > 32 && c < 127) t = String.valueOf(Character.toLowerCase(c));
			if (t != null && !out.contains(t)) out.add(t);
		}
		return out;
	}

	/** Whether the stack file lists externals of 68K code (platform mac68k; a fat one with PowerPC code too counts) */
	static boolean has68kExternals(File xml) throws IOException {
		return EXTERNAL_68K.matcher(new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8)).find();
	}

	private static String dialogText(String english) {
		return PCARD.pc != null && PCARD.pc.intl != null ? PCARD.pc.intl.getDialogText(english) : english;
	}

	/** The packed stack's name in the folder: the stack's name (characters a web address or a file system dislikes as _)
	 *  and .hzb; stack.hzb for a stack without a name */
	static String stackFileName(OStack stack) {
		String n = stack.name == null ? "" : stack.name.trim();
		n = n.replaceAll("[\\\\/:*?\"<>|#%&+\\p{Cntrl}]", "_");
		if (n.isEmpty() || n.startsWith(".")) n = "stack";
		return n + ".hzb";
	}

	private static String htmlAttribute(String s) {
		return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** The stack's folder without what a packed stack leaves out (the movie cache, unfinished writes, the working folder's mark,
	 *  the mimetype of a folder unpacked from a .hzb by hand) */
	/**
	 * The stack's machine and AI-chat translations (StackTranslation's cache, which the browser does not have) into the copy
	 * as its own translations, Languages/<tag>.po: the page shows the stack in the viewer's language (?lang=en) as
	 * HyperZebra does with Show translation on. A catalog the stack has already keeps its entries; the others are added to it.
	 */
	static void addCachedTranslations(OStack stack, File work) throws IOException {
		java.util.Map<String, java.util.Map<String, String>> all = StackTranslation.cachedTranslations(stack);
		for (java.util.Map.Entry<String, java.util.Map<String, String>> lang : all.entrySet()) {
			File po = new File(new File(work, StackLanguages.FOLDER), lang.getKey() + ".po");
			java.util.Map<String, String> own = java.util.Collections.emptyMap();
			if (po.isFile()) {
				try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(po), StandardCharsets.UTF_8)) { own = International.Catalog.read(r).entries; }
			}
			StringBuilder sb = new StringBuilder();
			if (!po.isFile()) {
				sb.append("# ").append(stack.name == null ? "" : stack.name.trim()).append(": machine/AI translation (HyperZebra's translation cache), written by Export for Web\n");
				sb.append("msgid \"\"\nmsgstr \"\"\n\"Language: ").append(lang.getKey()).append("\\n\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n");
			} else sb.append("\n# machine/AI translation (HyperZebra's translation cache), added by Export for Web\n");
			int added = 0;
			for (java.util.Map.Entry<String, String> e : lang.getValue().entrySet()) {
				if (own.containsKey(e.getKey())) continue;
				sb.append("\nmsgid ").append(International.Catalog.quote(e.getKey())).append("\nmsgstr ").append(International.Catalog.quote(e.getValue())).append('\n');
				added++;
			}
			if (added == 0) continue;
			po.getParentFile().mkdirs();
			Files.write(po.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		}
	}

	static void copyStackFolder(Path from, Path to) throws IOException {
		Files.createDirectories(to);
		try (Stream<Path> all = Files.walk(from)) {
			for (Path p : (Iterable<Path>) all::iterator) {
				if (p.equals(from)) continue;
				Path rel = from.relativize(p);
				String n = p.getFileName().toString(), top = rel.getName(0).toString();
				if (top.equals("_qtmovie_cache") || rel.getNameCount() == 1 && (n.equals("bundle.properties") || n.equals("mimetype"))) continue; // (packing writes the mimetype)
				if (n.equals(".DS_Store") || n.equals("Thumbs.db") || n.matches("(?s).+\\.-?[0-9]+\\.tmp")) continue;
				Path q = to.resolve(rel.toString());
				if (Files.isDirectory(p)) Files.createDirectories(q);
				else Files.copy(p, q, StandardCopyOption.COPY_ATTRIBUTES);
			}
		}
	}

	private static final Pattern ICON_OF_BUTTON = Pattern.compile("<icon>\\s*(-?\\d+)\\s*</icon>");
	private static final Pattern ICON_MEDIA = Pattern.compile("<media>\\s*<id>\\s*(-?\\d+)\\s*</id>\\s*<type>\\s*ICON\\s*</type>");

	/**
	 * The icons the stack's buttons show that are not its own (HyperCard's standard ones live in Home): their files copied
	 * into the stack's folder and listed in its stack file, so that the browser, which has no Home, finds them. Icons a
	 * script sets while it runs are not known here.
	 */
	static List<String> addUsedIcons(OStack stack, File xml) throws IOException {
		List<String> notes = new ArrayList<>();
		String text = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
		Set<Integer> own = new LinkedHashSet<>(), used = new LinkedHashSet<>();
		for (Matcher m = ICON_MEDIA.matcher(text); m.find(); ) own.add(Integer.parseInt(m.group(1)));
		for (Matcher m = ICON_OF_BUTTON.matcher(text); m.find(); ) { int id = Integer.parseInt(m.group(1)); if (id != 0 && id != -1) used.add(id); } // (-1: a button that shows its picture)
		StringBuilder media = new StringBuilder();
		List<Integer> missing = new ArrayList<>();
		File dir = xml.getParentFile();
		for (int id : used) {
			if (own.contains(id)) continue;
			String path = stack.rsrc == null ? null : stack.rsrc.getFilePathAll(id, "ICON");
			File f = path == null ? null : new File(path);
			if (f == null || !f.isFile()) { missing.add(id); continue; }
			String name = "ICON_" + id + ".data";
			for (int k = 2; new File(dir, name).exists(); k++) name = "ICON_" + id + "_" + k + ".data";
			Files.copy(f.toPath(), new File(dir, name).toPath());
			media.append("\t<media>\n\t\t<id>").append(id).append("</id>\n\t\t<type>ICON</type>\n\t\t<name></name>\n\t\t<file>")
				.append(name).append("</file>\n\t\t<attrs>32</attrs>\n\t</media>\n");
		}
		if (media.length() > 0) {
			int end = text.lastIndexOf("</");
			if (end < 0) throw new IOException("The stack file has no end: " + xml.getPath());
			text = text.substring(0, end) + media + text.substring(end);
			Files.write(xml.toPath(), text.getBytes(StandardCharsets.UTF_8));
		}
		if (!missing.isEmpty()) notes.add(String.format(dialogText("Icons not found (the buttons show none): %s"), missing.toString()));
		return notes;
	}

	private static final Pattern PLAY_NAME = Pattern.compile("(?i)\\bplay\\s+(?:\"|&quot;)([^\"&\\r\\n]+)(?:\"|&quot;)|(?i)\\bplay\\s+([A-Za-z_][A-Za-z0-9_]*)");
	private static final Pattern QUOTED = Pattern.compile("(?:\"|&quot;)([^\"&<\\r\\n]{1,63}?)(?:\"|&quot;)");
	private static final Pattern SND_MEDIA = Pattern.compile("<media>\\s*<id>\\s*(-?\\d+)\\s*</id>\\s*<type>\\s*SND\\s*</type>\\s*<name>([^<]*)</name>");

	/**
	 * The sounds the scripts play by name ("play \"boing\"", "play harpsichord") that are not the stack's own but a stack in
	 * use has (HyperCard's Harpsichord, Boing and flute are Home's): their files copied into the stack's folder and listed,
	 * as addUsedIcons does for icons. When a word after play names no sound (a variable), the quoted words of the scripts
	 * that name one are taken instead. Names a script builds from pieces while it runs are not known here.
	 */
	static void addUsedSounds(OStack stack, File xml) throws IOException {
		if (stack.rsrc == null) return;
		String text = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
		Set<String> own = new LinkedHashSet<>(), used = new LinkedHashSet<>();
		Set<Integer> ownIds = new LinkedHashSet<>();
		for (Matcher m = SND_MEDIA.matcher(text); m.find(); ) { ownIds.add(Integer.parseInt(m.group(1))); own.add(m.group(2).trim().toLowerCase(java.util.Locale.ROOT)); }
		boolean byVariable = false;
		for (Matcher m = PLAY_NAME.matcher(text); m.find(); ) {
			String n = (m.group(1) != null ? m.group(1) : m.group(2)).trim();
			String low = n.toLowerCase(java.util.Locale.ROOT);
			if (!n.isEmpty() && !low.equals("stop") && !low.equals("movie") && !own.contains(low)) used.add(n);
			if (m.group(2) != null && !own.contains(low) && stack.rsrc.findByNameAll(n, "SND") == null) byVariable = true;
		}
		// play inst … with the name in a variable (HyperMusic: put "flute" into inst): the scripts' quoted words that name a
		// sound of a stack in use are taken as well
		if (byVariable) for (Matcher s = SCRIPT.matcher(text); s.find(); )
			for (Matcher q = QUOTED.matcher(s.group(1)); q.find(); ) {
				String n = q.group(1).trim();
				if (!n.isEmpty() && !own.contains(n.toLowerCase(java.util.Locale.ROOT)) && stack.rsrc.findByNameAll(n, "SND") != null) used.add(n);
			}
		StringBuilder media = new StringBuilder();
		File dir = xml.getParentFile();
		for (String n : used) {
			Rsrc.rsrcClass r = stack.rsrc.findByNameAll(n, "SND");
			String path = r == null ? null : stack.rsrc.getFilePathAll(n, "SND");
			File f = path == null ? null : new File(path);
			if (f == null || !f.isFile() || ownIds.contains(r.id)) continue;
			String ext = f.getName().contains(".") ? f.getName().substring(f.getName().lastIndexOf('.')) : ".data";
			String file = "SND_" + r.id + ext;
			for (int k = 2; new File(dir, file).exists(); k++) file = "SND_" + r.id + "_" + k + ext;
			Files.copy(f.toPath(), new File(dir, file).toPath());
			ownIds.add(r.id); own.add(n.toLowerCase(java.util.Locale.ROOT));
			media.append("\t<media>\n\t\t<id>").append(r.id).append("</id>\n\t\t<type>SND</type>\n\t\t<name>").append(htmlAttribute(r.name == null ? n : r.name))
				.append("</name>\n\t\t<file>").append(file).append("</file>\n\t\t<attrs>32</attrs>\n\t</media>\n");
		}
		if (media.length() == 0) return;
		int end = text.lastIndexOf("</");
		text = text.substring(0, end) + media + text.substring(end);
		Files.write(xml.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	/** README.txt of the folder (English, as the release READMEs); with68k: the folder has the 68K player */
	/** The stack's own speed limit (Individual Stack Settings: Speed) in MIPS, 0 when it has none */
	static double speedLimitOf(OStack stack) {
		java.io.File f = StackTranslation.fileOf(stack);
		return f == null ? 0 : StackSpeed.rateOf(StackSettings.of(f));
	}
	/** A rate as the page's attribute gives it: 12, 2.5 */
	static String mipsText(double mips) {
		return mips == Math.rint(mips) ? Long.toString((long) mips) : java.math.BigDecimal.valueOf(mips).stripTrailingZeros().toPlainString();
	}

	static String readme(OStack stack, boolean with68k) { return readme(stack, with68k, false); }
	static String readme(OStack stack, boolean with68k, boolean compiled) { return readme(stack, with68k, compiled, false); }
	static String readme(OStack stack, boolean with68k, boolean compiled, boolean gamepad) { return readme(stack, with68k, compiled, gamepad, 0); }
	static String readme(OStack stack, boolean with68k, boolean compiled, boolean gamepad, double mips) {
		String name = stack.name == null || stack.name.isEmpty() ? "the stack" : stack.name;
		List<String> lines = new ArrayList<>(List.of(
			"This folder plays " + name + " in a web browser (HyperZebra web player).",
			"",
			"How to use it",
			"- Put the whole folder on a web server and open index.html there.",
			"  Opened from the disk (file://), it does not run: browsers do not run web workers and WebAssembly from files.",
			"  To try it on your own computer: open a terminal in this folder, run  python3 -m http.server 8000",
			"  and open http://localhost:8000/ in the browser.",
			"- No server settings are needed: sw.js gives the page the isolation it needs (it reloads once the first time).",
			"- Needs a recent browser with WebAssembly GC: Chrome or Edge 119, Firefox 120, Safari 18.2 or later.",
			"",
			"What it does",
			"- Clicks, typing (also Japanese input), the message box, the menus, answer and ask, wait, idle, visual effects,",
			"  play (the stack's sounds and notes), beep, colour (AddColor), PICT pictures, shapes, paint layers,",
			"  the other stacks this one goes to.",
			"- Command-period (Ctrl-period) stops a running handler.",
			"- A stack with translations (Languages/<tag>.po) has a language button (a globe at the right of the menu bar):",
			"  Original shows it as it is, the others in that language (HyperZebra's menus and dialogs too). The choice is kept",
			"  for the page; index.html?lang=en or ?lang=original chooses in a link; <html data-langmenu=\"off\"> leaves it out.",
			"- Changes are kept in the browser, for this page, as HyperCard keeps a stack's changes: the stack opens again",
			"  as it was left (a stack that can't be modified keeps only what its scripts save with \"save stack\").",
			"  File > Save a Copy... downloads the stack with its changes. index.html?reset forgets them; a new export of",
			"  the stack starts afresh; <html data-save=\"off\"> in index.html keeps nothing.",
			"- A page without the menu bar (left out at export, or hidden by the stack) has a + button at its top right:",
			"  it shows the menus over the card (File > Save a Copy... and the tools too) until it is pressed again."));
		if (with68k) lines.addAll(List.of(
			"- The stack's own 68K external commands (XCMD/XFCN) run as 68K code: the keys they read, what they draw on the",
			"  card, their windows over the card."));
		if (compiled) lines.addAll(List.of(
			"- The stack's scripts were compiled into the player when it was exported (" + (with68k ? PLAYER_68K : "player.wasm") + "): the same",
			"  results as the interpreter, faster. A script changed while it plays (set the script) runs interpreted."));
		if (gamepad) lines.addAll(List.of(
			"- The stack reads keys as they are held (a game): on a touch screen (a phone, a tablet) the page shows a game pad",
			"  under or beside the card, its keys those chosen at the export (data-gamepad in index.html lists them: up, down,",
			"  left, right, space, return, shift, option, command, a letter...; change it to choose others). index.html?gamepad=1 shows",
			"  it on any screen, ?gamepad=0 never; its small button hides it and shows it again."));
		if (mips > 0) lines.addAll(List.of(
			"- The stack plays at the speed limit of its Individual Stack Settings (" + mipsText(mips) + " MIPS, about the speed of a Mac",
			"  of its time): its scripts, its card display and its external commands. <html data-mips=\"...\"> in index.html sets",
			"  another rate (0 or no attribute: as fast as it goes); index.html?prop=hz.stack.mips=0 plays it unlimited in a link."));
		lines.addAll(List.of(
			"",
			"Not yet",
			"- Speech, the paint tools, shapes' SVG filters, PowerPC external commands"
				+ (with68k ? "." : ", 68K external commands, movies."),
			"",
			"Files",
			"- index.html, player.js, worker.js, sw.js: the page and its scripts",
			"- player.wasm: HyperZebra's HyperTalk core, built with TeaVM"));
		if (with68k) lines.add("- " + PLAYER_68K + ": the same with HyperZebra's 68K Macintosh emulation, for the stack's external commands");
		lines.addAll(List.of(
			"- wasm-gc-runtime.min.js: TeaVM's runtime (Apache License 2.0, see TeaVM-LICENSE.txt)",
			"- " + stackFileName(stack) + ": the stack",
			""));
		return String.join("\n", lines);
	}

	static void deleteTree(Path p) {
		if (p == null || !Files.exists(p)) return;
		try (Stream<Path> all = Files.walk(p)) {
			all.sorted(java.util.Comparator.reverseOrder()).forEach(q -> { try { Files.delete(q); } catch (IOException ignored) {} });
		} catch (IOException ignored) {}
	}
}

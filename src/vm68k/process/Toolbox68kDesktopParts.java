/** Toolbox68kParts of the desktop: every area, as Toolbox68k always made them (P4: left out of the browser player's VM). */
final class Toolbox68kDesktopParts extends Toolbox68kParts {
	Toolbox68kDesktopParts() {}
	@Override CommToolbox68k ctb(Toolbox68k tb) { return new CommToolbox68k(tb); }
	@Override AppleEvents68k appleEvents(Toolbox68k tb) { return new AppleEvents68k(tb); }
	@Override StandardFile68k standardFile(Toolbox68k tb) { return new StandardFile68k(tb); }
	@Override EditionManager68k editions(Toolbox68k tb) { return new EditionManager68k(tb); }
	@Override SoundDriver68k soundDriver(Toolbox68k tb) { return new SoundDriver68k(tb); }
	@Override MacTCP68k macTcp(Toolbox68k tb) { return new MacTCP68k(tb); }
	@Override AppleTalk68k appleTalk(Toolbox68k tb) { return new AppleTalk68k(tb); }
	@Override DeskAccessories68k desk(Toolbox68k tb) { return new DeskAccessories68k(tb); }
	@Override QuickTime68k quickTime(Toolbox68k tb) { return new QuickTime68k(tb); }
	@Override ImageCompression68k imageCompression(Toolbox68k tb) { return new ImageCompression68k(tb); }
	@Override Extensions68k extensions(Toolbox68k tb) { return new Extensions68k(tb); }
	@Override HelpManager68k help(Toolbox68k tb) { return new HelpManagerDesktop68k(tb); }
	@Override PrintManager68k print(Toolbox68k tb) { return new PrintManager68k(tb); }
	@Override DragManager68k drag(Toolbox68k tb) { return new DragManager68k(tb); }
	@Override AdbManager68k adb(Toolbox68k tb) { return new AdbManager68k(tb); }
	@Override TextServices68k textServices(Toolbox68k tb) { return new TextServices68k(tb); }
	@Override AppearanceManager68k appearance(Toolbox68k tb) { return new AppearanceManager68k(tb); }
	@Override CursorDevices68k cursorDevices(Toolbox68k tb) { return new CursorDevices68k(tb); }
	@Override ScrapManager68k scrap(M68kMemory memory, Mac68kApi api) { return new ScrapManager68kHost(memory, api); }
	@Override SoundManager68k sound(M68kMemory memory, Mac68kApi api) { return new SoundManager68k(memory, api); }
	@Override AppearanceControls68k appearanceControls(Toolbox68k tb, ControlManager68k controls) { return new AppearanceControls68k(tb, controls); }
	@Override OpenTransport openTransport(Toolbox68k tb) { return new OpenTransport(tb); }
	@Override boolean openTransportInstalled() { return true; }
	@Override VmHost host(VmHostListener listener, M68kMemory memory, String name, boolean headless, boolean external) { return new MacAppHost(listener, memory, name, headless, external); }
	@Override boolean headlessDisplay() { return java.awt.GraphicsEnvironment.isHeadless(); }
	@Override MacIo68k io(Toolbox68k tb, M68kMemory memory) throws M68kCpuException { return new MacIo68k(tb, memory, memory.allocate(MacIo68k.LENGTH, 2)); }
	@Override MacApp68k launch(java.io.File target, java.util.List<java.io.File> documents) throws java.io.IOException { return MacApp68k.launch(target, documents); }
	@Override MacApp68k openDeskAccessory(java.io.File file, String name) throws java.io.IOException { return MacApp68k.openDeskAccessory(file, name); }
	@Override void internetConfig(Components68k components, Toolbox68k tb) { InternetConfig68k.register(components, tb); }
	@Override void quickTimeComponents(Components68k components, Toolbox68k tb) { components.registerQuickTime(); }
	@Override MovieData movieData(java.io.File file) { return TQTMovie.assetFor(file); }
	@Override MovieSound68k movieSound(MovieData data) { return MovieSoundHost68k.of(data); }
	@Override int movieToMidiFile(MovieData data, java.io.File movie, java.io.File output) { return MovieSoundHost68k.writeMidi(data, movie, output); }

	// ---- the host's files and process
	@Override FileLock68k lock(java.io.RandomAccessFile f) throws java.io.IOException { java.nio.channels.FileLock l = f.getChannel().lock(); return l::close; }
	@Override boolean emptiesReadWriteOpen() { return false; }	@Override void sync(java.io.RandomAccessFile f) throws java.io.IOException { f.getFD().sync(); }
	@Override long pid() { return ProcessHandle.current().pid(); }
	@Override boolean alive(long pid) { return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }
	@Override java.io.File codeLocation() {
		try { return new java.io.File(FileSystem68k.class.getProtectionDomain().getCodeSource().getLocation().toURI()); } catch (Exception | Error e) { return null; }
	}
	/** Walks under way, by the folders they walk; the threads they run on (daemons: a walk stuck in a host folder never keeps
	 *  HyperZebra from quitting). */
	private static final java.util.Map<String, java.util.concurrent.Future<Object>> CREATOR_WALKS = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<Object>>();
	private static final java.util.concurrent.ExecutorService WALKERS = java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "PBDTGetAPPL walk"); t.setDaemon(true); return t; });
	@Override java.io.File creatorWalk(String cacheKey, java.util.ArrayDeque<java.io.File[]> queue, long deadline, boolean[] settled, int index,
			java.util.function.Predicate<java.io.File[]> ownFolder, java.util.function.Supplier<Object> mainWalk,
			java.util.function.Function<java.util.ArrayDeque<java.io.File[]>, Object> ownWalk, java.util.function.BiFunction<Object, Integer, java.io.File> pick) {
		java.util.concurrent.Future<Object> done = CREATOR_WALKS.get(cacheKey); if (done != null && done.isDone()) CREATOR_WALKS.remove(cacheKey, done); // its index is out of date
		java.util.concurrent.Future<Object> walk = CREATOR_WALKS.computeIfAbsent(cacheKey, k -> WALKERS.submit(mainWalk::get));
		// the program's own folder (then the folder holding it) is walked on its own as well and a match there answers at once:
		// the whole walk (every volume, the host root) often outlasts the wait, and Caliban's isAPPL("RSED") said false with
		// ResEdit right beside the stack
		if (index <= 1) for (java.io.File[] first : queue) { // ioIndex 0 and 1 both ask for the newest (isAPPL passes 0)
			if (walk.isDone() || !ownFolder.test(first)) break;
			java.util.ArrayDeque<java.io.File[]> own = new java.util.ArrayDeque<java.io.File[]>(); own.add(first);
			java.util.concurrent.Future<Object> mine = WALKERS.submit(() -> ownWalk.apply(own));
			try { java.io.File result = pick.apply(mine.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS), 1); if (result != null) return result; }
			catch (java.util.concurrent.TimeoutException e) { mine.cancel(true); if (settled != null) settled[0] = false; return null; }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); if (settled != null) settled[0] = false; return null; }
			catch (java.util.concurrent.ExecutionException ignored) {}
		}
		try { Object found = walk.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS); CREATOR_WALKS.remove(cacheKey, walk); return pick.apply(found, index); }
		catch (java.util.concurrent.TimeoutException e) { if (settled != null) settled[0] = false; return null; }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); if (settled != null) settled[0] = false; return null; }
		catch (java.util.concurrent.ExecutionException e) { CREATOR_WALKS.remove(cacheKey, walk); return null; }
	}
	@Override Timer68k timer(String threadName) {
		java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, threadName); t.setDaemon(true); return t; });
		return timer::schedule;
	}

	// ---- the host's UI
	@Override void beep() { java.awt.Toolkit.getDefaultToolkit().beep(); }
	/** SoundInput68k's SndRecord dialog, moved here unchanged (P4 sound: the browser has no Swing) */
	@Override byte[] recordWithDialog() {
		final javax.sound.sampled.AudioFormat format=new javax.sound.sampled.AudioFormat(22050,8,1,false,true);final java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		final boolean[] recording=new boolean[1],stop=new boolean[1],started=new boolean[1];final int[] choice={-1};
		// the line opens at the first click on Record, not when the dialog appears (the microphone stays closed if it is cancelled)
		final Thread capture=new Thread(new Runnable(){@Override public void run(){
			try{javax.sound.sampled.TargetDataLine line=javax.sound.sampled.AudioSystem.getTargetDataLine(format);line.open(format);line.start();byte[] chunk=new byte[2205];
				while(!stop[0]){int n=line.read(chunk,0,chunk.length);if(n<=0)break;if(recording[0])synchronized(out){out.write(chunk,0,n);}}
				line.close();}catch(javax.sound.sampled.LineUnavailableException|RuntimeException e){}}},"68K SndRecord");
		capture.setDaemon(true);
		try{
			javax.swing.SwingUtilities.invokeAndWait(new Runnable(){@Override public void run(){
				final javax.swing.JDialog dialog=new javax.swing.JDialog((java.awt.Frame)null,"Record",true);
				final javax.swing.JLabel status=new javax.swing.JLabel("Click Record to start.");
				javax.swing.JButton record=new javax.swing.JButton("Record"),stopButton=new javax.swing.JButton("Stop"),save=new javax.swing.JButton("Save"),cancel=new javax.swing.JButton("Cancel");
				stopButton.setEnabled(false);save.setEnabled(false);
				record.addActionListener(e->{synchronized(out){out.reset();}recording[0]=true;if(!started[0]){started[0]=true;capture.start();}status.setText("Recording...");record.setEnabled(false);stopButton.setEnabled(true);});
				stopButton.addActionListener(e->{recording[0]=false;status.setText("Recorded "+(out.size()/22050.0)+" s.");stopButton.setEnabled(false);record.setEnabled(true);save.setEnabled(true);});
				save.addActionListener(e->{choice[0]=1;dialog.dispose();});cancel.addActionListener(e->{choice[0]=0;dialog.dispose();});
				javax.swing.JPanel buttons=new javax.swing.JPanel();buttons.add(record);buttons.add(stopButton);buttons.add(save);buttons.add(cancel);
				dialog.getContentPane().add(status,java.awt.BorderLayout.NORTH);dialog.getContentPane().add(buttons,java.awt.BorderLayout.SOUTH);
				dialog.pack();dialog.setLocationRelativeTo(null);dialog.setVisible(true);}});
		}catch(InterruptedException|java.lang.reflect.InvocationTargetException e){choice[0]=0;}
		stop[0]=true;recording[0]=false;
		if(choice[0]!=1)return null;synchronized(out){return out.toByteArray();}
	}
	@Override String hostClipboardText() {
		try {
			java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
			if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) return (String) cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
		} catch (Exception ignored) {}
		return null;
	}
	@Override void setHostClipboardText(String text) {
		try { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null); } catch (Exception ignored) {}
	}
	@Override void notification(String title, String message) {
		javax.swing.SwingUtilities.invokeLater(new Runnable() { @Override public void run() { javax.swing.JOptionPane.showMessageDialog(null, message, title, javax.swing.JOptionPane.INFORMATION_MESSAGE); } });
	}
	@Override int chooseFromList(String prompt, java.util.List<String> names) {
		if (java.awt.GraphicsEnvironment.isHeadless()) return -1;
		final int[] picked = {-1};
		Runnable show = () -> {
			Object[] values = names.toArray();
			Object answer = javax.swing.JOptionPane.showInputDialog(null, prompt, "", javax.swing.JOptionPane.QUESTION_MESSAGE, null, values, values[0]);
			picked[0] = answer == null ? -1 : names.indexOf(answer);
		};
		if (javax.swing.SwingUtilities.isEventDispatchThread()) show.run();
		else try { javax.swing.SwingUtilities.invokeAndWait(show); } catch (Exception e) { return -1; }
		return picked[0];
	}
	/** The palettes HideHCPalettes hid, which ShowHCPalettes brings back (only those). */
	private static final java.util.List<java.awt.Window> hiddenPalettes = new java.util.ArrayList<java.awt.Window>();
	@Override void showHyperCardPalettes(boolean show) {
		synchronized (hiddenPalettes) {
			if (show) { for (java.awt.Window w : hiddenPalettes) w.setVisible(true); hiddenPalettes.clear(); return; }
			java.awt.Window tools = PCARD.pc != null && PCARD.pc.toolbar != null ? PCARD.pc.toolbar.tb : null;
			for (java.awt.Window w : new java.awt.Window[] {GMsg.msg, tools}) if (w != null && w.isVisible() && !hiddenPalettes.contains(w)) { w.setVisible(false); hiddenPalettes.add(w); }
		}
	}
	@Override void watchCardKeys(java.util.function.Consumer<Object> note) {
		try {
			java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
				if (e.getID() != java.awt.event.KeyEvent.KEY_PRESSED || !(e.getSource() instanceof java.awt.Component)) return;
				java.awt.Component c = (java.awt.Component) e.getSource(); PCARD pc = PCARD.pc;
				java.awt.Window w = c instanceof java.awt.Window ? (java.awt.Window) c : javax.swing.SwingUtilities.getWindowAncestor(c);
				if (pc != null && w == pc) note.accept(e);
			}, java.awt.AWTEvent.KEY_EVENT_MASK);
		} catch (RuntimeException e) {} // no toolkit: no keys to watch
	}
	@Override boolean modifierKey(Object event) {
		if (!(event instanceof java.awt.event.KeyEvent e)) return false;
		switch (e.getKeyCode()) { case java.awt.event.KeyEvent.VK_SHIFT: case java.awt.event.KeyEvent.VK_CONTROL: case java.awt.event.KeyEvent.VK_ALT: case java.awt.event.KeyEvent.VK_META: case java.awt.event.KeyEvent.VK_CAPS_LOCK: return true; default: return false; }
	}
	@Override int[] cardKey(Object event, VmHost host) {
		if (!(event instanceof java.awt.event.KeyEvent k)) return super.cardKey(event, host);
		return new int[] {MacAppHost.macKeyCode(k.getKeyCode(), k.getKeyLocation()), host.macChar(k), host.hostModifiers(k.getModifiersEx())};
	}
	@Override java.awt.image.BufferedImage readImage(byte[] data) {
		try { return data == null ? null : javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data)); } catch (java.io.IOException e) { return null; }
	}
	@Override java.awt.image.BufferedImage readImage(java.io.File file) throws java.io.IOException { return javax.imageio.ImageIO.read(file); }
	@Override boolean writePng(java.awt.image.BufferedImage image, java.io.File file) throws java.io.IOException { return javax.imageio.ImageIO.write(image, "png", file); }
	@Override void parallelRows(int top, int bottom, java.util.function.IntConsumer work) { java.util.stream.IntStream.rangeClosed(top, bottom).parallel().forEach(work); }
	@Override void spinWait() { Thread.onSpinWait(); }
	@Override void atQuit(Runnable work, String name) { Runtime.getRuntime().addShutdownHook(new Thread(work, name)); }
	@Override java.awt.image.BufferedImage loadPict(java.io.File file) { return PictureFile.loadPICT(file.getPath()); }
	@Override boolean writePng(java.awt.image.BufferedImage image, java.io.OutputStream out) throws java.io.IOException { return javax.imageio.ImageIO.write(image, "png", out); }
	@Override HelperProcess68k startProcess(java.io.File program) throws java.io.IOException {
		Process p = new ProcessBuilder(program.getAbsolutePath()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		return new HelperProcess68k() {
			public boolean alive() { return p.isAlive(); }
			public java.io.OutputStream stdin() { return p.getOutputStream(); }
			public java.io.InputStream stdout() { return p.getInputStream(); }
			public void destroy() { p.destroy(); }
		};
	}
	@Override boolean canExecute(java.io.File f) { return f.canExecute(); }
}

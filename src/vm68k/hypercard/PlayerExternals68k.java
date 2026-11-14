import java.io.File;

/**
 * A stack's 68K XCMDs and XFCNs in the browser player (P4, Externals68k): what TXcmd.run68kExternal does on the desktop -
 * the VM per call (Xcmd68k.execute), the nesting limit, Command-period, passFlag, AbortScript - with the external's code
 * read from the stack's files, which the player unpacked into its in-memory file system (HeadlessStacks.unpack).
 */
final class PlayerExternals68k implements Externals68k {
	private int nesting;
	/** The card window's views are made with it: the player draws what externals draw over the card from the start (CardOverlay);
	 *  the VMs' sound is mixed on the player's loop (SoundMixer68k's driven mode, {@link #audio}). */
	PlayerExternals68k() { CardViews68k.IMPL.getClass(); SoundMixer68k.driven = true; MoviePlayer.current = new MoviePlayer68k(); } // the built-in Movie XCMD too

	// ---- sound (P4 sound): the mix, a little ahead of now, to the page
	/** How far ahead of now the mix goes (the page plays it then), and the least it sends at a time. A voice is over once its last
	 *  frame is mixed, so a synchronous SndPlay returns this much before it is heard to end (the desktop's line buffer: 46 ms) */
	private static final double LEAD_SECONDS = 0.08;
	private static final int MIN_FRAMES = 512, MAX_FRAMES = 4096;
	private final short[] mixed = new short[MAX_FRAMES * 2];
	private boolean streaming;
	private long streamStart, framesSent, lastSound, lastAudioCheck;
	/** A stream stops after this much silence (the next sound starts a new one, at once) */
	private static final long SILENCE_NANOS = 400_000_000L;
	@Override public boolean audio() {
		long now = System.nanoTime();
		SoundMixer68k m = SoundMixer68k.get();
		if (!streaming) {
			if (!m.sounding()) { if (SoundMixer68k.watching()) m.render(mixed, 0); return false; }
			streaming = true; streamStart = now; framesSent = 0; lastSound = now;
		}
		long due = (long) ((now - streamStart) / 1e9 * SoundMixer68k.RATE + LEAD_SECONDS * SoundMixer68k.RATE);
		PlayerHost host = PlayerInput.host;
		while (due - framesSent >= MIN_FRAMES) {
			int n = (int) Math.min(MAX_FRAMES, due - framesSent);
			if (m.render(mixed, n)) lastSound = now;
			if (host != null) host.audio(mixed, n, SoundMixer68k.RATE);
			framesSent += n;
		}
		if (now - lastSound > SILENCE_NANOS && !m.sounding()) streaming = false;
		return streaming;
	}
	/** audio() at most every 5 ms (the running external's slices call it) */
	private void audioIfDue() { long now = System.nanoTime(); if (now - lastAudioCheck >= 5_000_000L) { lastAudioCheck = now; audio(); } }
	static final int MAX_EXTERNAL_NESTING = 16;

	@Override public boolean call(OStack owner, String name, String[] params, boolean isFunc, Result result) throws xTalkException {
		// (a stack that was left keeps its resources for a handler of it still running: TXcmd.resourcesOf)
		Rsrc res = owner.rsrc != null ? owner.rsrc : owner.closedResources;
		Rsrc.xcmdClass external = res == null ? null : res.getXcmd(name, isFunc ? "function" : "command");
		String platform = external == null || external.platform == null ? "" : external.platform.toLowerCase();
		if (!platform.contains("68k")) return false; // PowerPC code only: no PowerPC here
		if (nesting >= MAX_EXTERNAL_NESTING) throw new xTalkException("Too much handler recursion: {0} [{1}]", name, ScriptDebugger.frameTrail(6)); // as TXcmd says it
		nesting++;
		PlayerUiHost.abortAfterExternal = false;
		try {
			M68kCpu.hostAbort = () -> { audioIfDue(); return TTalk.stop; }; // Command-period stops a running external too; its sound plays on meanwhile
			File resource = Xcmd68k.resourceFile(owner, external);
			Xcmd68k.passedLastCall(); // a stale note from an earlier call
			OStack shown = HZ.stack();
			result.theResult = Xcmd68k.execute(resource, params, owner.legacyTextEncoding, shown != null ? shown : owner, external);
			result.ret = Xcmd68k.passedLastCall() ? 1 : 0; // passFlag: the message goes on
			showWindows();
			if (PlayerUiHost.abortAfterExternal) { PlayerUiHost.abortAfterExternal = false; TTalk.stop = true; throw new xTalkException("user abort."); }
		} catch (M68kMacApiException e) {
			throw new xTalkException("{0}: Called an unsupported Mac API ({1})", name, e.getMessage());
		} catch (M68kAbortException e) {
			throw new xTalkException("{0}: Aborted", name);
		} catch (M68kException e) {
			throw new xTalkException(name + ": " + e.getMessage());
		} catch (java.io.IOException e) {
			throw new xTalkException("{0}: Cannot read the XCMD/XFCN resource ({1})", name, e.getMessage());
		} finally {
			nesting--;
		}
		return true;
	}

	// the card window (P4-4): HyperCard's copies of the card into it, as the desktop's UiHost passes them to CardWindow68k
	@Override public void cardRedrawn() { CardWindow68k.cardRedrawn(); }
	@Override public void cardCopied() { CardWindow68k.cardCopied(); }
	@Override public void partCopied(int top, int left, int bottom, int right) { CardWindow68k.partCopied(top, left, bottom, right); }
	@Override public void partMoved(OObject part, int top, int left, int bottom, int right) { CardWindow68k.partMoved(part, top, left, bottom, right); }
	@Override public void holdScreen() { CardWindow68k.holdScreen(); }
	@Override public void nextWindow() { if (PlayerVmHost.sendBackdropsBehindCard(HZ.stack() == null ? 0 : HZ.stack().width, HZ.stack() == null ? 0 : HZ.stack().height)) showWindows(); }
	@Override public void releaseScreen() { CardWindow68k.releaseScreen(); }
	@Override public boolean redrawHookedCards(OStack stack) { return Xcmd68k.redrawHookedCards(stack); }
	@Override public boolean redrawHookedCards(OStack stack, int top, int left, int bottom, int right) { return Xcmd68k.redrawHookedCards(stack, top, left, bottom, right); }
	@Override public boolean cardDrawingHooked(OStack stack) { return Xcmd68k.cardDrawingHooked(stack); }
	@Override public void messageBoxHidden() { CardWindow68k.messageBoxHidden(); }
	@Override public void stackClosing(OStack stack) { Xcmd68k.stackClosing(stack); CardWindow68k.stackClosing(stack); }

	// the kept VMs' events (P4-5)
	@Override public boolean hasSessions() { return Xcmd68k.hasSessions(); }
	@Override public boolean hasWindowSessions() { return Xcmd68k.hasWindowSessions(); }
	@Override public Xcmd68k.ExternalWindow findWindow(String name) { return Xcmd68k.findWindow(name); }
	@Override public Xcmd68k.ExternalWindow findWindowById(int id) { return Xcmd68k.findWindowById(id); }
	@Override public OXWindow newWindow(Xcmd68k.ExternalWindow w) { return new OXWindow(w); }
	@Override public boolean takeWindowClick() { return Xcmd68k.hasSessions() && Xcmd68k.takeWindowClick(); }
	@Override public void pump() { Toolbox68k.PARTS.runDue(); Xcmd68k.pump(); showWindows(); }
	@Override public void pumpIfDue() { Xcmd68k.pumpIfDue(); showWindows(); }
	@Override public void pumpWhileWaiting() { Toolbox68k.PARTS.runDue(); Xcmd68k.pumpWhileWaiting(); showWindows(); }
	@Override public boolean pressAt(boolean down, int x, int y, int modifiers) { boolean taken = PlayerVmHost.pressAt(down, x, y, KeyCodes68k.macModifiers(modifiers, down)); if (taken) showWindows(); return taken; }
	@Override public boolean pointerAt(int x, int y) { boolean taken = PlayerVmHost.pointerAt(x, y); if (taken) showWindows(); return taken; }
	@Override public boolean key(int[] e) {
		int code = e[4], location = e.length > 6 ? e[6] & 1 : 0;
		return PlayerVmHost.keyTo(e[0] == PlayerHost.KEY_UP ? 4 : 3, KeyCodes68k.macKeyCodeOfDom(code, location), KeyCodes68k.macCharOfDom(code, location, e[5]), KeyCodes68k.macModifiers(e[3], PlayerInput.mouseDown()));
	}
	@Override public java.io.File existingFile(java.io.File folder, String name) { return FileManager68k.existingOrUnarchived(folder, name); }
	/** The external windows changed (made, moved, drawn in): the card shows them (also while the screen is locked: they are windows of their own) */
	private static void showWindows() { if (PlayerVmHost.windowsChanged()) PlayerLoop.externalDrew(true); }
}

/**
 * The browser player keeps what a stack's scripts and its user change, as HyperCard does: the stack's _stack.xml
 * (StackXml, the desktop's serializer) goes to the page (PlayerHost.saveStack), which keeps it in the browser's storage for
 * that page (player.js: IndexedDB). When the page opens again, the kept _stack.xml is read in place of the stack's own
 * (HeadlessStacks.saved), as long as the exported stack is the same (its fingerprint: a new export starts afresh).
 *
 * When, as the desktop's autosave (XMLwrite.saveThread): changes are looked for once a second while no handler runs; a
 * stack with changes is written then, but while an idle handler keeps running (a game) at most every 10 seconds. A stack
 * that can't be modified is not written (HyperCard's cantModify; Mosslight RPG Engine unlocks itself for its save slot
 * and writes with "save stack"). "save [this] stack" writes now; the page asks for a write (PlayerHost.FLUSH) when it is
 * hidden or closed. File > Save a Copy… hands the page the stack as an .hzb (its files, the _stack.xml as it is now) to
 * download.
 *
 * A large stack takes a while to write (the whole _stack.xml each time: Mosslight RPG Engine's 9.5 MB about 0.2 s in the
 * browser), and the player stops meanwhile: the next autosave waits at least SPACING times as long as the last write took
 * (at most a twentieth of the time goes to saving). "save stack" and the page's FLUSH write at once all the same.
 */
final class PlayerSaving {
	private PlayerSaving() {}

	static final long CHECK_NANOS = 1_000_000_000L, BUSY_SAVE_NANOS = 10_000_000_000L;
	/** The next autosave comes no sooner than this many times the last write's time after it */
	static final int SPACING = 20;
	private static long lastCheck, unsavedSince;
	/** When the last write ended and how long it took (the host's clock) */
	static long lastWriteEnd, lastWriteNanos;
	/** Stacks written, for tests */
	static int writes;

	/** A turn of the player's loop with nothing running (PlayerLoop; busy: an idle handler ran in the last 2 seconds): writes
	 *  the stacks whose changes are due. */
	static void idle(PlayerHost host, long now, boolean busy) {
		if (now - lastCheck < CHECK_NANOS) return;
		lastCheck = now;
		boolean any = false;
		for (OStack s : HeadlessStacks.openedStacks()) if (due(s)) { any = true; break; }
		if (!any) { unsavedSince = 0; return; }
		// an idle handler that keeps running (busy): its changes are written now and then, not each second
		if (unsavedSince == 0) unsavedSince = now;
		if (busy && now - unsavedSince < BUSY_SAVE_NANOS) return;
		// a large stack: not before the time its last write took, SPACING times over, has passed since
		if (lastWriteNanos > 0 && now - lastWriteEnd < SPACING * lastWriteNanos) return;
		unsavedSince = 0;
		flush(host);
	}

	/** Writes every stack with changes now (the page is hidden or closing) */
	static void flush(PlayerHost host) {
		long started = host.nanoTime();
		boolean wrote = false;
		for (OStack s : HeadlessStacks.openedStacks()) if (due(s)) wrote |= save(host, s);
		// (all the stacks written this time: the next autosave's spacing)
		if (wrote) { lastWriteEnd = host.nanoTime(); lastWriteNanos = lastWriteEnd - started; }
	}

	/** Whether the stack has changes it may keep */
	private static boolean due(OStack s) {
		if (s == null || s.cdCacheList == null || HeadlessStacks.originOf(s) == null) return false;
		if (s.cantModify && !s.userEdited) return false;
		return StackXml.needsSave(s) || HzPaint.hasUnsaved(s);
	}

	/** save [this] stack: the stack written now (unless it can't be modified: XMLwrite.saveStackNow's rule) */
	static boolean saveNow(PlayerHost host, OStack s) {
		if (s == null || HeadlessStacks.originOf(s) == null) return false;
		if (s.cantModify && !s.userEdited) return true;
		return save(host, s);
	}

	private static boolean save(PlayerHost host, OStack s) {
		HeadlessStacks.Origin o = HeadlessStacks.originOf(s);
		if (host == null || o == null) return false;
		boolean userEdits = s.userEdited;
		s.userEdited = false;
		java.util.ArrayList<OCardBase> cleared = new java.util.ArrayList<>();
		byte[] xml;
		// the painted pictures into the stack's files first (a picture of its own named in the stack file)
		try { HzPaint.flush(s); }
		catch (RuntimeException e) { System.out.println("Could not keep a painted picture: " + e); }
		try { xml = StackXml.write(s, cleared); }
		catch (XmlException | RuntimeException e) {
			// not written: the changes stay marked, for the next try
			System.out.println("Could not keep the stack " + o.name + ": " + e);
			if (Boolean.getBoolean("hz.savetrace")) e.printStackTrace(System.out);
			s.changed = true;
			if (userEdits) s.userEdited = true;
			for (OCardBase part : cleared) part.changed = true;
			return false;
		}
		java.util.Map<String, byte[]> files = HeadlessStacks.writtenFiles(s);
		if (files.isEmpty()) host.saveStack(o.name, o.fingerprint, xml); else host.saveStack(o.name, o.fingerprint, xml, files);
		// and read so when the stack opens again in this session (go home, go back): the page's copy reaches the player only
		// when the page starts (WebMain), so Home opened again had the script Window Tools had installed in it gone
		synchronized (HeadlessStacks.saved) { HeadlessStacks.saved.put(o.name, new HeadlessStacks.Saved(o.fingerprint, xml, files)); }
		writes++;
		return true;
	}

	/** A stack left for another (TTalk.goToStack2 → UiHost.savedBeforeLeave): its changes written now, as the desktop writes
	 *  them before it goes - a handler that changes it and goes on (no idle turn between) left them to an autosave that found
	 *  the stack emptied (clean) */
	static void beforeLeave(PlayerHost host, OStack s) { if (due(s)) save(host, s); }

	/** A stack about to be read again (HeadlessStacks.open, by its storage name): what a copy of it in use has not written yet,
	 *  first (Home in use, its script set by Window Tools' installer, then go home). The stacks left were written as they went */
	static void beforeOpen(PlayerHost host, String store) {
		java.util.LinkedHashSet<OStack> inUse = new java.util.LinkedHashSet<>();
		OStack cur = HZ.stack();
		if (cur != null) { inUse.add(cur); if (cur.usingStacks != null) inUse.addAll(cur.usingStacks); }
		if (HZ.home != null) inUse.add(HZ.home);
		for (OStack s : inUse) {
			HeadlessStacks.Origin o = HeadlessStacks.originOf(s);
			if (o != null && o.name.equals(store) && due(s)) save(host, s);
		}
	}

	/** File > Save a Copy…: the stack's files with its _stack.xml as it is now, packed as an .hzb, to the page to download */
	static boolean saveACopy(PlayerHost host, OStack s) {
		StackSource source = s == null ? null : HeadlessStacks.sourceOf(s);
		if (host == null || source == null) return false;
		java.util.LinkedHashMap<String, byte[]> files = new java.util.LinkedHashMap<>();
		try {
			HzPaint.flush(s);
			source = HeadlessStacks.sourceOf(s);
			byte[] xml = StackXml.write(s, new java.util.ArrayList<>());
			s.changed = true; // (the copy is not the kept stack: its changes are still to be kept)
			files.put("mimetype", "application/x-hyperzebra-stack".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			files.put("_stack.xml", xml);
			for (String path : source.paths()) {
				if (path.equals("_stack.xml") || path.equals("mimetype")) continue;
				byte[] b = source.read(path);
				if (b != null) files.put(path, b);
			}
		} catch (XmlException | java.io.IOException | RuntimeException e) {
			System.out.println("Could not make a copy of the stack: " + e);
			return false;
		}
		HeadlessStacks.Origin o = HeadlessStacks.originOf(s);
		String name = o != null ? o.name.substring(o.name.lastIndexOf('/') + 1) : (s.name == null || s.name.isEmpty() ? "Stack" : s.name) + ".hzb";
		if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")) name += ".hzb";
		host.download(name, zip(files));
		return true;
	}

	/** A ZIP of the files, stored (not compressed: pictures are compressed already), the first entry first as given */
	static byte[] zip(java.util.Map<String, byte[]> files) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		java.io.ByteArrayOutputStream central = new java.io.ByteArrayOutputStream();
		int count = 0;
		for (java.util.Map.Entry<String, byte[]> e : files.entrySet()) {
			byte[] name = e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8), data = e.getValue();
			java.util.zip.CRC32 crc = new java.util.zip.CRC32();
			crc.update(data, 0, data.length);
			int offset = out.size(), c = (int) crc.getValue();
			// local header: version 2.0, flags (bit 11: UTF-8 names), stored, no time
			le(out, 0x04034b50, 4); le(out, 20, 2); le(out, 0x0800, 2); le(out, 0, 2); le(out, 0, 2); le(out, 0x21, 2);
			le(out, c, 4); le(out, data.length, 4); le(out, data.length, 4); le(out, name.length, 2); le(out, 0, 2);
			out.write(name, 0, name.length); out.write(data, 0, data.length);
			le(central, 0x02014b50, 4); le(central, 20, 2); le(central, 20, 2); le(central, 0x0800, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0x21, 2);
			le(central, c, 4); le(central, data.length, 4); le(central, data.length, 4); le(central, name.length, 2); le(central, 0, 2); le(central, 0, 2);
			le(central, 0, 2); le(central, 0, 2); le(central, 0, 4); le(central, offset, 4);
			central.write(name, 0, name.length);
			count++;
		}
		int at = out.size(), size = central.size();
		byte[] c = central.toByteArray();
		out.write(c, 0, c.length);
		le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2); le(out, count, 2); le(out, count, 2); le(out, size, 4); le(out, at, 4); le(out, 0, 2);
		return out.toByteArray();
	}
	private static void le(java.io.ByteArrayOutputStream o, int v, int bytes) { for (int i = 0; i < bytes; i++) o.write((v >>> (8 * i)) & 0xFF); }
}

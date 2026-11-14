import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Opening a stack in the headless core (P1 S10): what OStack.openStackFileInThread does on the desktop (there it opens a
 * file, a packed .hzb, a ZIP, a disk image, a 68K program or a HyperCard stack it converts; all of that stays the desktop's).
 * The core reads an .hzb, a folder or a ZIP, through {@link StackSource}; in the browser the files are in TeaVM's own file
 * system, where the page put them. The rest is the desktop's: the same tables, the same first card, the same messages.
 */
final class HeadlessStacks {
	private HeadlessStacks() {}

	private static final java.util.Map<OStack, StackSource> sources = new java.util.WeakHashMap<>();
	/** The files a stack was read from (its pictures and resources are read there too), or null for a desktop stack. */
	static StackSource sourceOf(OStack s) { synchronized (sources) { return sources.get(s); } }
	/** The stacks opened here, the one opened last at the end (PlayerSaving writes the ones with changes) */
	static java.util.List<OStack> openedStacks() { synchronized (sources) { return new ArrayList<>(sources.keySet()); } }

	/** A stack's _stack.xml the browser kept (PlayerSaving): read in place of the stack's own when the stack is the one it was
	 *  saved from (the same fingerprint); by storage name */
	static final java.util.Map<String, Saved> saved = new java.util.HashMap<>();
	static final class Saved {
		final String fingerprint; final byte[] xml;
		/** the stack's other files the player wrote (its painted pictures, HzPaint), by path */
		final java.util.Map<String, byte[]> files;
		Saved(String fingerprint, byte[] xml) { this(fingerprint, xml, null); }
		Saved(String fingerprint, byte[] xml, java.util.Map<String, byte[]> files) {
			this.fingerprint = fingerprint; this.xml = xml; this.files = files == null ? new java.util.LinkedHashMap<>() : files;
		}
	}
	/** The files the player wrote into a stack (HzPaint's pictures, and the ones kept from before), by path: they go to the
	 *  page with the stack (PlayerSaving) and are read in place of the stack's own */
	private static final java.util.Map<OStack, java.util.Map<String, byte[]>> written = new java.util.WeakHashMap<>();
	static java.util.Map<String, byte[]> writtenFiles(OStack s) {
		synchronized (sources) { java.util.Map<String, byte[]> w = written.get(s); return w == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(w); }
	}
	/** A file of the stack written (a painted picture): read from now on in place of the stack's own */
	static void writeFile(OStack s, String path, byte[] bytes) {
		synchronized (sources) {
			StackSource src = sources.get(s);
			if (src == null) return;
			java.util.Map<String, byte[]> w = written.get(s);
			if (w == null) { w = new java.util.LinkedHashMap<>(); written.put(s, w); sources.put(s, overlay(src, w)); }
			w.put(path, bytes);
		}
	}
	/** source with the files written over its own */
	private static StackSource overlay(StackSource source, java.util.Map<String, byte[]> w) {
		return new StackSource() {
			@Override String name() { return source.name(); }
			@Override byte[] read(String path) throws IOException { synchronized (sources) { byte[] b = w.get(path); if (b != null) return b; } return source.read(path); }
			@Override Iterable<String> paths() {
				java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
				for (String p : source.paths()) all.add(p);
				synchronized (sources) { all.addAll(w.keySet()); }
				return all;
			}
		};
	}
	/** Where an opened stack came from: its storage name and the fingerprint of the _stack.xml it was given */
	static final class Origin {
		final String name, fingerprint; final boolean restored;
		Origin(String name, String fingerprint, boolean restored) { this.name = name; this.fingerprint = fingerprint; this.restored = restored; }
	}
	private static final java.util.Map<OStack, Origin> origins = new java.util.WeakHashMap<>();
	static Origin originOf(OStack s) { synchronized (sources) { return origins.get(s); } }
	/** The name a stack is kept under: its path in the player's /stacks folder ("DATA/fvr2_engine.hzb"), else its file's name */
	static String storageName(File f) {
		String p = f.getAbsolutePath().replace('\\', '/');
		int at = p.indexOf("/stacks/");
		return at >= 0 ? p.substring(at + "/stacks/".length()) : f.getName();
	}
	/** The _stack.xml a stack was given, as a short text: changing the exported stack (the author's new version) leaves
	 *  what was kept of the old one aside */
	static String fingerprint(byte[] xml) {
		if (xml == null) return "0";
		java.util.zip.CRC32 c = new java.util.zip.CRC32();
		c.update(xml, 0, xml.length);
		return xml.length + "-" + Long.toHexString(c.getValue());
	}
	/** source, with _stack.xml as kept */
	private static StackSource with(StackSource source, byte[] xml) {
		return new StackSource() {
			@Override String name() { return source.name(); }
			@Override byte[] read(String path) throws IOException { return path.equals("_stack.xml") ? xml : source.read(path); }
			@Override Iterable<String> paths() { return source.paths(); }
		};
	}

	/**
	 * The 68K player (P4): a packed stack (.hzb ZIP) is also written out as a folder of its files (_unpacked/<name>.hzb beside
	 * it, in the browser's in-memory file system), the stack's path that folder: a 68K external's code, and the resource files
	 * its VM opens, are read from files.
	 */
	static boolean unpack;
	/** A path in a folder unpack made, as the same path under its packed .hzb (…/_unpacked/X.hzb/_stack.xml is …/X.hzb/_stack.xml),
	 *  else null */
	static File hostOf(File f) {
		if (f == null) return null;
		java.util.ArrayList<String> rel = new java.util.ArrayList<>();
		for (File d = f.getAbsoluteFile(); d != null; d = up(d)) {
			File parent = up(d);
			if (parent != null && parent.getName().equals("_unpacked") && up(parent) != null) {
				File packed = new File(up(parent), d.getName());
				if (!packed.isFile()) return null;
				File out = packed;
				for (int i = rel.size() - 1; i >= 0; i--) out = new File(out, rel.get(i));
				return out;
			}
			rel.add(d.getName());
			if (rel.size() > 64) return null;
		}
		return null;
	}
	/** The folder a path is in; null above the root (TeaVM's File gives the root again there, never null: a walk up never ended) */
	private static File up(File d) {
		File p = d.getParentFile();
		return p == null || p.getPath().equals(d.getPath()) || p.getPath().isEmpty() ? null : p;
	}

	/** The packed .hzb a folder unpack made (…/_unpacked/X.hzb, or a file in it) came from, else null */
	static File packedOf(File f) {
		int steps = 0;
		for (File d = f == null ? null : f.getAbsoluteFile(); d != null && steps++ < 64; d = up(d)) {
			File parent = up(d);
			if (parent != null && parent.getName().equals("_unpacked") && up(parent) != null) {
				File packed = new File(up(parent), d.getName());
				return packed.isFile() ? packed : null;
			}
		}
		return null;
	}

	private static File unpacked(File hzb, StackSource source) {
		File dir = new File(new File(hzb.getAbsoluteFile().getParentFile(), "_unpacked"), hzb.getName());
		if (new File(dir, "_stack.xml").isFile()) return dir;
		try {
			for (String path : source.paths()) {
				File out = new File(dir, path.replace('/', File.separatorChar));
				File parent = out.getParentFile();
				if (parent != null) parent.mkdirs();
				try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) { o.write(source.read(path)); }
			}
			return dir;
		} catch (IOException e) {
			System.err.println("Could not unpack " + hzb + ": " + e.getMessage());
			return hzb;
		}
	}

	/** The browser player's stack (WebMain, PlayerSim): Home first when the page gave it beside the stack (Export for Web packs
	 *  Home when the stack calls its handlers), so that the stack has it in use; then the stack. False when the stack does not open. */
	static boolean openPlayerStack(OStack s, String inpath) {
		File f = new File(inpath).getAbsoluteFile(), home = new File(f.getParentFile(), "Home.hzb");
		if (HZ.home == null && home.exists() && !home.equals(f)) {
			OStack h = new OStack(null);
			h.rsrc = new Rsrc(h);
			if (open(h, home.getPath(), true)) HZ.home = h;
		}
		return open(s, inpath, false);
	}

	/** OStack.openStackFileInThread: the stack at inpath read into s (its scripts' tables only, when it is only used). */
	static boolean open(OStack s, String inpath, boolean isUsingOnly) {
		File f = new File(inpath);
		// a stack's _stack.xml (a path the desktop keeps): its .hzb
		if (f.getName().equals("_stack.xml") && f.getParentFile() != null) f = f.getParentFile();
		if (!f.exists()) { s.name = ""; return false; }
		StackSource source;
		try { source = StackSource.open(f); }
		catch (IOException e) { System.err.println("Could not open the stack file " + inpath + ": " + e.getMessage()); s.name = ""; return false; }
		// what the browser kept of it (the page gave it: WebMain), when it is the same stack
		String store = storageName(f), print;
		try { print = fingerprint(source.read("_stack.xml")); }
		catch (IOException e) { print = "0"; }
		if (PlayerLoop.current != null) PlayerSaving.beforeOpen(PlayerLoop.current.host, store);
		Saved kept;
		synchronized (saved) { kept = saved.get(store); }
		boolean restored = kept != null && kept.fingerprint.equals(print);
		if (restored) source = with(source, kept.xml);
		java.util.Map<String, byte[]> keptFiles = restored && !kept.files.isEmpty() ? new java.util.LinkedHashMap<>(kept.files) : null;
		if (keptFiles != null) source = overlay(source, keptFiles);
		synchronized (sources) {
			sources.put(s, source); origins.put(s, new Origin(store, print, restored));
			if (keptFiles != null) written.put(s, keptFiles); else written.remove(s);
		}
		if (unpack && f.isFile()) f = unpacked(f, source);
		String xml = new File(f, "_stack.xml").getPath();
		s.path = xml;
		s.file = new File(xml);
		s.scriptList = new ArrayList<String>();
		if (!XMLRead.readToc(source, s) || s.cdCacheList.size() == 0) { s.name = ""; return false; }
		s.path = xml;
		s.buildStackFile(isUsingOnly);
		return true;
	}

	/** The end of OStack.buildStackFile for a stack that is opened, not only used: the desktop's window aside. */
	static void opened(OStack s) {
		if (HZ.home != null) s.usingStacks.add(HZ.home);
		String sepStr = "" + File.separatorChar;
		if (File.separatorChar == '\\') sepStr = "\\\\";
		String[] strArray = s.path.split(sepStr);
		if (strArray.length - 2 >= 0) s.name = strArray[strArray.length - 2].replaceFirst("(?i)\\.hzb$", "");
		else s.name = s.path;
		s.name = Platform.get().normalize(OStack.macNameOfHostName(s.name, s.legacyTextEncoding), UnicodeNorm.Form.NFC);
		s.windowNominal = null;
		if (s.scroll != null) s.scroll = new java.awt.Point(0, 0);
		if (s.cardIdList.size() >= 1) {
			try {
				s.curCard = OCard.getOCard(s, s.cardIdList.get(0), false);
				s.curCard.bg = OBackground.getOBackground(s, s.curCard, s.curCard.bgid, false);
				s.curCard.parent = s.curCard.bg;
			} catch (xTalkException e) {
				e.printStackTrace();
				s.curCard = s.cdCacheList.get(0);
				try {
					s.curCard.bg = OBackground.getOBackground(s, s.curCard, s.curCard.bgid, false);
					s.curCard.parent = s.curCard.bg;
				} catch (xTalkException e2) {
					e2.printStackTrace();
				}
			}
			HZ.lockedScreen = false;
			s.recordRecentCard(s.curCard);
			TTalk.CallMessage("startUp", s.curCard);
			TTalk.CallMessage("openStack", s.curCard);
			TTalk.CallMessage("openBackground", s.curCard);
			TTalk.CallMessage("openCard", s.curCard);
		}
	}
}

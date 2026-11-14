import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The files of a stack as the core reads them (P1 S9): an .hzb folder, an .hzb ZIP in memory (what the browser has: the
 * bytes of a file the page was given), or files in memory; by path inside the stack, '/'-separated ("_stack.xml",
 * "ICON_30001.png"). The desktop keeps opening its stacks from files (StackBundle unpacks a ZIP into a working folder).
 */
abstract class StackSource {
	/** The stack's file name (the .hzb, or the .xml), for messages and the stack's name. */
	abstract String name();
	/** The bytes of a file of the stack, or null when it has none of that path. */
	abstract byte[] read(String path) throws IOException;
	/** The paths of the stack's files. */
	abstract Iterable<String> paths();

	/** An .hzb ZIP: its entries, read now. */
	static StackSource zip(byte[] zip, String name) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
				if (e.isDirectory()) continue;
				String path = e.getName().replace('\\', '/');
				// a ZIP made of the folder itself: "Name.hzb/_stack.xml" is "_stack.xml"
				int slash = path.indexOf('/');
				if (slash > 0 && path.substring(0, slash).toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")) path = path.substring(slash + 1);
				if (path.startsWith("__MACOSX/") || path.isEmpty()) continue;
				files.put(path, in.readAllBytes());
			}
		}
		return memory(files, name);
	}

	/** Files in memory. */
	static StackSource memory(final Map<String, byte[]> files, final String name) {
		return new StackSource() {
			@Override String name() { return name; }
			@Override byte[] read(String path) { return files.get(path); }
			@Override Iterable<String> paths() { return files.keySet(); }
		};
	}

	/** An .hzb folder (a JVM; the browser has no files): read when asked. */
	static StackSource folder(final File dir) {
		return new StackSource() {
			@Override String name() { return dir.getName(); }
			@Override byte[] read(String path) throws IOException {
				File f = new File(dir, path.replace('/', File.separatorChar));
				return f.isFile() ? Platform.get().readAllBytes(f) : null;
			}
			@Override Iterable<String> paths() {
				java.util.List<String> out = new java.util.ArrayList<>();
				String[] names = dir.list();
				if (names != null) for (String n : names) if (new File(dir, n).isFile()) out.add(n);
				return out;
			}
		};
	}

	/** An .hzb by its path on a JVM: a folder or a ZIP file. */
	static StackSource open(File hzb) throws IOException {
		if (hzb.isDirectory()) return folder(hzb);
		return zip(Platform.get().readAllBytes(hzb), hzb.getName());
	}
}

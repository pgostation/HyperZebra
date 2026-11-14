import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** {@link Files68k} on the desktop: java.nio.file, as the 68K VM called it before P4. */
final class Files68kNio extends Files68k {
	@Override byte[] doRead(File f) throws IOException { return Files.readAllBytes(f.toPath()); }
	@Override void doWrite(File f, byte[] b) throws IOException { Files.write(f.toPath(), b); }
	@Override void doAppend(File f, byte[] b) throws IOException { Files.write(f.toPath(), b, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
	@Override boolean doIsSymbolicLink(File f) { return Files.isSymbolicLink(f.toPath()); }
	@Override void doDelete(File f) throws IOException { Files.delete(f.toPath()); }
	@Override void doMove(File from, File to, boolean replace) throws IOException {
		if (replace) Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); else Files.move(from.toPath(), to.toPath());
	}
	@Override void doCopyWithAttributes(File from, File to) throws IOException { Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES); }
	@Override java.io.InputStream doIn(File f) throws IOException { return Files.newInputStream(f.toPath()); }
	@Override java.util.List<String> doLines(File f, java.nio.charset.Charset cs) throws IOException { return Files.readAllLines(f.toPath(), cs); }
	@Override long[] doTimes(File f) throws IOException {
		java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
		return new long[] {a.creationTime().toMillis(), a.lastModifiedTime().toMillis()};
	}
	@Override Object doFileKey(File f) throws IOException { return Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes.class).fileKey(); }
	@Override void doSetModified(File f, long millis) throws IOException { Files.setLastModifiedTime(f.toPath(), java.nio.file.attribute.FileTime.fromMillis(millis)); }
	@Override void doSetCreated(File f, long millis) throws IOException { Files.setAttribute(f.toPath(), "basic:creationTime", java.nio.file.attribute.FileTime.fromMillis(millis)); }
	@Override boolean doSetWritable(File f, boolean writable, boolean ownerOnly) { return f.setWritable(writable, ownerOnly); }
	@Override long doTotalSpace(File f) { return f.getTotalSpace(); }
	@Override long doUsableSpace(File f) { return f.getUsableSpace(); }
}

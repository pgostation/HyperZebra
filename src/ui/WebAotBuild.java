import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Export for Web: the player built again for the stack, with its scripts compiled ahead of time. TalkJitAot compiles every
 * block of the exported stacks' scripts with TalkJit's compiler into classes; TeaVM (in the player folder's compiler
 * subfolder, with the classes the prebuilt player was built from) builds them with the core into the page's player.wasm or
 * player-68k.wasm. So the code runs in the same Wasm module as the interpreter it falls back to, without crossing into
 * JavaScript, and is the code of the JIT the desktop runs: what it does not take is the interpreter's, statement by statement.
 * <p>Without the compiler folder, or when anything fails, the export keeps the prebuilt player: the same results, interpreted.
 */
final class WebAotBuild {
	private WebAotBuild() {}

	/** In the player folder (build/wasm-core/web-player/compiler, Support Files/WebPlayer/compiler): tools/wasm-core/build.sh makes it */
	static final String FOLDER = "compiler";
	private static final String TEAVM = "teavm.jar", TOOL = "tool.jar", WEB = "web.jar", CORE = "core.jar", CORE_68K = "core68k.jar",
		CLASSLIB = "classlib.jar", SERVICES = "services";

	/** The compiler can build that player (player-68k.wasm when with68k) */
	static boolean available(File player, boolean with68k) { return folder(player, with68k) != null; }

	/** The compiler for this player: in the player folder (a release made with it, the project's build), else the one downloaded
	 *  for it into the state folder (WebCompiler/<the first 12 of its SHA-256>/compiler); null if none */
	static File folder(File player, boolean with68k) {
		File here = new File(player, FOLDER);
		if (complete(here, with68k)) return here;
		Download d = download(player);
		if (d == null) return null;
		File got = new File(d.place(), FOLDER);
		return complete(got, with68k) ? got : null;
	}

	private static boolean complete(File c, boolean with68k) {
		for (String n : new String[]{TEAVM, TOOL, WEB, with68k ? CORE_68K : CORE, CLASSLIB}) if (!new File(c, n).isFile()) return false;
		return new File(c, SERVICES).isDirectory();
	}

	// ---- the compiler as a download (a release without it: jpackage.sh/release-jar.sh without --with-web-compiler)

	/** Beside the player (tools/wasm-core/build.sh writes it): url, sha256 and size of the ZIP of this player's compiler folder */
	static final String PROPERTIES = "compiler.properties";

	/** Where this player's compiler comes from; place() is where it goes */
	record Download(String url, String sha256, long size) {
		File place() { return new File(new File(AppPaths.stateDirectory(), "WebCompiler"), sha256.substring(0, 12)); }
		/** "37 MB" */
		String sizeText() { return Math.max(1, Math.round(size / 1048576.0)) + " MB"; }
	}

	/** The download of this player's compiler, or null (no compiler.properties: an old or a hand-made player folder).
	 *  -Dhz.webcompiler.url replaces where it comes from (the SHA-256 stays the player's). */
	static Download download(File player) {
		File f = new File(player, PROPERTIES);
		if (!f.isFile()) return null;
		java.util.Properties p = new java.util.Properties();
		try (java.io.Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) { p.load(r); }
		catch (IOException e) { return null; }
		String url = System.getProperty("hz.webcompiler.url", p.getProperty("url", "")).trim(), sha = p.getProperty("sha256", "").trim().toLowerCase(java.util.Locale.ROOT);
		long size;
		try { size = Long.parseLong(p.getProperty("size", "0").trim()); } catch (NumberFormatException e) { size = 0; }
		if (url.isEmpty() || !sha.matches("[0-9a-f]{64}")) return null;
		return new Download(url, sha, size);
	}

	/**
	 * Downloads this player's compiler, checks it is exactly the one built with the player (its SHA-256), and unpacks it into
	 * the state folder; older downloads for other players are removed. Another thread: TesseractInstall.Progress reports.
	 * @return null when it is there, else what went wrong
	 */
	static String install(File player, TesseractInstall.Progress progress) {
		Download d = download(player);
		if (d == null) return International.t("This copy of HyperZebra does not say where its script compiler is.");
		File zip = null;
		try {
			zip = TempFiles.createFile("webcompiler", ".zip");
			TesseractInstall.download(d.url(), zip, International.t("Downloading the script compiler ({0})…", d.sizeText()), progress);
			if (progress.cancelled()) return International.t("Cancelled.");
			if (!d.sha256().equals(TesseractInstall.sha256(zip)))
				return International.t("The downloaded script compiler is not the one of this HyperZebra (its checksum differs).");
			progress.report(International.t("Unpacking the script compiler…"), -1);
			File place = d.place(), part = new File(place.getParentFile(), place.getName() + ".part");
			WebExport.deleteTree(part.toPath());
			Files.createDirectories(part.toPath());
			try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
				String root = part.getCanonicalPath() + File.separator;
				for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
					String name = e.getName();
					if (!name.startsWith(FOLDER + "/") || name.contains("/._")) continue;
					File out = new File(part, name);
					if (!out.getCanonicalPath().startsWith(root)) throw new IOException("bad entry " + name);
					if (e.isDirectory()) { out.mkdirs(); continue; }
					out.getParentFile().mkdirs();
					Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			if (!complete(new File(part, FOLDER), false)) { WebExport.deleteTree(part.toPath()); return International.t("The download has no script compiler."); }
			WebExport.deleteTree(place.toPath());
			Files.move(part.toPath(), place.toPath());
			// one compiler is enough: the ones of other HyperZebra versions go
			File[] others = place.getParentFile().listFiles();
			if (others != null) for (File o : others) if (!o.getName().equals(place.getName())) WebExport.deleteTree(o.toPath());
			return null;
		} catch (IOException e) {
			return International.t("Downloading failed: {0}", e.getMessage() == null ? e.toString() : e.getMessage());
		} finally {
			if (zip != null) zip.delete();
		}
	}

	/** The scripts of a stack's _stack.xml as the player reads them (each script element's text, OObject.setScript's text) */
	static List<String> scripts(String xml) throws IOException {
		List<String> out = new ArrayList<>();
		try {
			XmlIn r = XmlText.decodingReader(Platform.get().xmlIn(new StringReader(xml)));
			while (r.hasNext()) {
				if (r.next() == XmlIn.START_ELEMENT && r.getLocalName().equals("script")) out.add(r.getElementText());
			}
		} catch (XmlException e) {
			throw new IOException(e.getMessage());
		}
		return out;
	}

	/** What a build did */
	static final class Outcome {
		/** null: built; else why the prebuilt player is kept */
		String failure;
		String summary = "";
		long millis;
	}

	/**
	 * Builds the player into {@code wasm} (overwriting the prebuilt copy there) with the scripts compiled. Runs TeaVM in this
	 * process (15-30 s, under 1 GB of heap). Leaves {@code wasm} as it was when it fails.
	 */
	static Outcome build(File player, Collection<String> scripts, boolean with68k, File wasm) {
		Outcome o = new Outcome();
		long t0 = System.currentTimeMillis();
		File work = null;
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		try {
			work = TempFiles.createDirectory("webaot");
			File gen = new File(work, "classes"), out = new File(work, "out");
			TalkJitAot.Output compiled = TalkJitAot.compile(new LinkedHashSet<>(scripts), gen);
			o.summary = compiled.toString();
			if (compiled.scripts == 0) { o.failure = "no handler to compile"; return o; }
			File c = folder(player, with68k);
			if (c == null) { o.failure = "no script compiler"; return o; }
			File teavm = new File(c, TEAVM);
			// TeaVM and the build tool on their own loader: nothing of the application's own classes (TeaVM reads the player's)
			try (URLClassLoader tools = new URLClassLoader(new URL[]{new File(c, TOOL).toURI().toURL(), teavm.toURI().toURL()}, ClassLoader.getPlatformClassLoader())) {
				// the core's slow String calls go to the class library's HzFast, as build.sh does with the core's classes
				tools.loadClass("WasmRewrite").getMethod("main", String[].class).invoke(null, (Object) new String[]{gen.getPath()});
				Method m = tools.loadClass("CoreBuild").getMethod("build", String.class, File.class, String.class, List.class, File.class, String.class,
					boolean.class, PrintStream.class);
				List<File> path = List.of(gen, new File(c, WEB), new File(c, with68k ? CORE_68K : CORE), new File(c, CLASSLIB), teavm);
				String name = with68k ? WebExport.PLAYER_68K : "player.wasm";
				PrintStream logStream = new PrintStream(log, true, StandardCharsets.UTF_8);
				Object[] result = new Object[1];
				Throwable[] thrown = new Throwable[1];
				// TeaVM recurses deeply (build.sh runs it with -Xss16m)
				Thread t = new Thread(null, () -> {
					try { result[0] = m.invoke(null, with68k ? "Web68kMain" : "WebMain", out, name, path, new File(c, SERVICES), "FULL", Boolean.getBoolean("hz.aot.names"), logStream); }
					catch (Throwable e) { thrown[0] = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e; }
				}, "web-aot-build", 256L << 20);
				t.start();
				t.join();
				if (thrown[0] != null) { o.failure = thrown[0].toString(); return o; }
				File built = new File(out, name);
				if (!Boolean.TRUE.equals(result[0]) || !built.isFile()) { o.failure = lastLines(log.toString(StandardCharsets.UTF_8), 3); return o; }
				Files.copy(built.toPath(), wasm.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable e) {
			if (e instanceof InvocationTargetException ite && ite.getCause() != null) e = ite.getCause();
			o.failure = e.toString();
		} finally {
			o.millis = System.currentTimeMillis() - t0;
			if (work != null) WebExport.deleteTree(work.toPath());
		}
		return o;
	}

	private static String lastLines(String text, int n) {
		String[] lines = text.trim().split("\n");
		StringBuilder b = new StringBuilder();
		for (int i = Math.max(0, lines.length - n); i < lines.length; i++) b.append(b.length() == 0 ? "" : " / ").append(lines[i].trim());
		return b.toString();
	}
}

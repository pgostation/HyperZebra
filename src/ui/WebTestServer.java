import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A throw-away web server for trying a folder made by "Export for Web…": it serves one folder on this computer only
 * (127.0.0.1, a free port), read-only, until stop() or the end of the application. http://localhost:<port>/ is a secure
 * context, so the player's service worker (sw.js) and WebAssembly work there.
 */
final class WebTestServer {
	private static final Map<String, String> TYPES = Map.ofEntries(
		Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
		Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("wasm", "application/wasm"),
		Map.entry("json", "application/json"), Map.entry("css", "text/css; charset=utf-8"),
		Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("xml", "application/xml"),
		Map.entry("po", "text/plain; charset=utf-8"), Map.entry("png", "image/png"), Map.entry("svg", "image/svg+xml"),
		Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("mid", "audio/midi"));

	private final File root;
	private final HttpServer server;

	private WebTestServer(File root, HttpServer server) { this.root = root; this.server = server; }

	/** Starts serving {@code folder} on a free port of the loopback address. */
	static WebTestServer start(File folder) throws IOException {
		File root = folder.getCanonicalFile();
		HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		WebTestServer w = new WebTestServer(root, s);
		s.createContext("/", w::handle);
		s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "WebTestServer"); t.setDaemon(true); return t; }));
		s.start();
		return w;
	}

	String url() { return "http://localhost:" + server.getAddress().getPort() + "/"; }

	void stop() { server.stop(0); }

	private void handle(HttpExchange ex) throws IOException {
		try {
			String m = ex.getRequestMethod();
			if (!m.equals("GET") && !m.equals("HEAD")) { send(ex, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), true); return; }
			String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
			File f = new File(root, path).getCanonicalFile();
			if (f.isDirectory()) f = new File(f, "index.html");
			// nothing outside the folder (../ and symbolic links)
			if (!f.getPath().startsWith(root.getPath() + File.separator) || !f.isFile()) { send(ex, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8), m.equals("HEAD")); return; }
			String name = f.getName();
			String type = TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
			ex.getResponseHeaders().set("Cache-Control", "no-store"); // always the files just exported
			send(ex, 200, type, Files.readAllBytes(f.toPath()), m.equals("HEAD"));
		} catch (RuntimeException e) {
			send(ex, 500, "text/plain", "Error".getBytes(StandardCharsets.UTF_8), true);
		}
	}

	private static void send(HttpExchange ex, int code, String type, byte[] body, boolean headOnly) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		if (headOnly) { ex.sendResponseHeaders(code, -1); ex.close(); return; }
		ex.sendResponseHeaders(code, body.length);
		try (OutputStream o = ex.getResponseBody()) { o.write(body); }
	}
}

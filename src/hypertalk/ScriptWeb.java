import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/** URL "http(s)://…", "file:…" and "binfile:…" for the HyperTalk get URL / put URL commands and URL expressions. */
final class ScriptWeb {
	private static final int MAX_BYTES = 16 * 1024 * 1024;
	private static final int MAX_REDIRECTS = 5;
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private ScriptWeb() {}

	/** The file of a "file:" or "binfile:" URL (LiveCode): file:/path, file:///path or a name beside the stack; null for others */
	static java.io.File localFile(String address) {
		String a = address.trim();
		String rest;
		if(a.regionMatches(true, 0, "binfile:", 0, 8)) rest = a.substring(8);
		else if(a.regionMatches(true, 0, "file:", 0, 5)) rest = a.substring(5);
		else return null;
		if(rest.startsWith("///")) rest = rest.substring(2);
		else if(rest.startsWith("//localhost/")) rest = rest.substring(11);
		if(rest.contains("%")) {
			try { rest = java.net.URLDecoder.decode(rest.replace("+", "%2B"), StandardCharsets.UTF_8); }
			catch(IllegalArgumentException e) { /* a name with a % of its own */ }
		}
		return new java.io.File(TTalk.resolveFilePath(rest));
	}

	static boolean isBinary(String address) { return address.trim().regionMatches(true, 0, "binfile:", 0, 8); }

	/** URL "binfile:…" (the bytes, TalkBinary) or "file:…" (text as read from file reads it) */
	private static String readLocal(java.io.File file, boolean binary) throws xTalkException {
		if(!file.isFile()) throw new xTalkException("No such file: {0}", file.getPath());
		if(!Sandbox.allowFile(file, Sandbox.Access.READ, Sandbox.scriptRequester())) throw new xTalkException("File access was denied: {0}", file.getPath());
		if(file.length() > TalkBinary.MAX_BYTES) throw new xTalkException("The file is too large: {0}", file.getPath());
		byte[] b;
		try { b = java.nio.file.Files.readAllBytes(file.toPath()); }
		catch(IOException e) { throw new xTalkException("Cannot read the file: {0}", e.getMessage()); }
		if(binary) return TalkBinary.string(b);
		OpenFile f = new OpenFile();
		f.sniff(file);
		String text = f.decode(b);
		if(!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
		return text.replace("\r\n", "\n").replace('\r', '\n');
	}

	/** put … into|after URL "file:…"|"binfile:…" */
	static void store(String address, String value, boolean append) throws xTalkException {
		java.io.File file = localFile(address);
		if(file == null) throw new xTalkException("put into URL writes only file: and binfile: URLs");
		if(file.isDirectory()) throw new xTalkException("Can't create that file.");
		java.io.File parent = file.getAbsoluteFile().getParentFile();
		if(parent != null && !parent.isDirectory()) throw new xTalkException("Can't create that file.");
		if(!Sandbox.allowFile(file, Sandbox.Access.WRITE, Sandbox.scriptRequester())) throw new xTalkException("File access was denied: {0}", file.getPath());
		boolean created = !file.exists();
		byte[] b;
		if(isBinary(address)) b = TalkBinary.bytes(value);
		else {
			OpenFile f = new OpenFile();
			if(!created) f.sniff(file);
			b = f.encode(value.replace("\r\n", "\n").replace("\n", f.utf8 ? f.newline : "\r"));
		}
		try {
			if(append) java.nio.file.Files.write(file.toPath(), b, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			else java.nio.file.Files.write(file.toPath(), b);
		}catch(IOException e) { throw new xTalkException("Cannot write the file: {0}", e.getMessage()); }
		if(created && !isBinary(address)) FinderInfo68k.markNewTextFile(file);
	}

	static String fetch(String address) throws xTalkException {
		java.io.File local = localFile(address);
		if(local != null) return readLocal(local, isBinary(address));
		URI uri;
		try { uri = new URI(address.trim()); }
		catch(NullPointerException | URISyntaxException e) { throw new xTalkException("Invalid URL"); }
		for(int redirects=0; redirects<=MAX_REDIRECTS; redirects++) {
			validate(uri);
			int port=uri.getPort();
			if(port<0) port=uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
			if(!Sandbox.allowNetwork(Sandbox.scriptRequester(), Sandbox.NET_CONNECT, uri.getHost(), port))
				throw new xTalkException("URL access was denied: {0}", uri.getHost());
			try {
				HttpRequest request=HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(30))
					.header("Accept", "text/plain, application/json, */*")
					.GET().build();
				HttpResponse<InputStream> response=CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
				try(InputStream body=response.body()) {
					int status=response.statusCode();
					if(status==301 || status==302 || status==303 || status==307 || status==308) {
						String location=response.headers().firstValue("Location").orElse(null);
						if(location==null) throw new xTalkException("Missing redirect destination (HTTP {0})", status);
						if(redirects==MAX_REDIRECTS) throw new xTalkException("Too many redirects");
						uri=uri.resolve(location);
						continue;
					}
					if(status<200 || status>=300) throw new xTalkException("Could not fetch URL (HTTP {0})", status);
					ByteArrayOutputStream bytes=new ByteArrayOutputStream();
					byte[] buffer=new byte[8192];
					for(int count; (count=body.read(buffer))!=-1;) {
						if(bytes.size()+count>MAX_BYTES) throw new xTalkException("URL content is too large (16 MB limit)");
						bytes.write(buffer, 0, count);
					}
					return decode(bytes.toByteArray(), response.headers().firstValue("Content-Type").orElse(""));
				}
			}catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new xTalkException("Fetching URL was interrupted");
			}catch(IOException | IllegalArgumentException e) {
				throw new xTalkException("Could not fetch URL: {0}", e.getMessage());
			}
		}
		throw new xTalkException("Too many redirects");
	}

	private static void validate(URI uri) throws xTalkException {
		String scheme=uri.getScheme();
		if(scheme==null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) ||
				uri.getHost()==null || uri.getUserInfo()!=null)
			throw new xTalkException("An HTTP or HTTPS URL is required");
	}

	private static String decode(byte[] bytes, String contentType) throws xTalkException {
		Charset charset=StandardCharsets.UTF_8;
		for(String parameter:contentType.split(";")) {
			String part=parameter.trim();
			if(part.toLowerCase(Locale.ROOT).startsWith("charset=")) {
				String name=part.substring(8).trim().replace("\"", "");
				try { charset=Charset.forName(name); }
				catch(Exception e) { throw new xTalkException("Unknown character encoding: {0}", name); }
			}
		}
		int offset=0;
		if(bytes.length>=3 && (bytes[0]&255)==0xef && (bytes[1]&255)==0xbb && (bytes[2]&255)==0xbf) {
			charset=StandardCharsets.UTF_8;
			offset=3;
		}
		String type=contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
		boolean declaredCharset=contentType.toLowerCase(Locale.ROOT).contains("charset=");
		//pictures, sounds, archives …: the bytes as binary data (TalkBinary), as LiveCode gives them
		if(!declaredCharset && (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/") ||
				type.equals("application/octet-stream") || type.equals("application/zip") || type.equals("application/gzip") ||
				type.equals("application/pdf") || type.equals("application/x-gzip")))
			return TalkBinary.string(bytes);
		try {
			return charset.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes, offset, bytes.length-offset)).toString();
		}catch(CharacterCodingException e) {
			if(declaredCharset) throw new xTalkException("Cannot read URL content as text");
			return TalkBinary.string(bytes);//no text: the bytes
		}
	}
}

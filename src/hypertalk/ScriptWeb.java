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

/** HTTP text retrieval for the HyperTalk get URL / put URL commands. */
final class ScriptWeb {
	private static final int MAX_BYTES = 16 * 1024 * 1024;
	private static final int MAX_REDIRECTS = 5;
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private ScriptWeb() {}

	static String fetch(String address) throws xTalkException {
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
		try {
			return charset.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes, offset, bytes.length-offset)).toString();
		}catch(CharacterCodingException e) { throw new xTalkException("Cannot read URL content as text"); }
	}
}

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * An Internet location file of a classic Mac: what the Finder of Mac OS 8.5 on makes from a URL dragged out of a browser
 * or mail ('ilht' for http, 'ilma' mailto, 'ilft' ftp, 'ilnw' news, 'ilfi' file, 'ilaf' AFP...), or the URL clipping of
 * URL Clerk and its kind ('clpT'). The data fork is empty; the URL is the 'url ' 256 resource, else the 'TEXT' 256 one. A
 * modern host's Finder no longer opens them (they show as empty documents), so HyperZebra reads the URL itself.
 */
final class InternetLocation {
	private InternetLocation(){}

	static final int URL = ResourceFile68k.fourcc("url "), URL_CLIPPING = ResourceFile68k.fourcc("clpT");
	/** The first two letters of the types of the Finder's Internet location files. */
	private static final int IL = ResourceFile68k.fourcc("il\0\0")>>>16;

	/** The URL the file holds when it is an Internet location (by its type, with an empty data fork), else null. */
	static String url(File f, int type){
		if(f==null || !f.isFile() || f.length()!=0 || (type>>>16!=IL && type!=URL_CLIPPING)) return null;
		try{
			ResourceFile68k rf = ResourceFile68k.open(f);
			ResourceFile68k.Resource r = rf.get(URL, TextDocument.CLIPPING_ID);
			if(r==null) r = rf.get(TextDocument.TEXT, TextDocument.CLIPPING_ID);
			return r==null ? null : url(r.data());
		}catch(IOException | RuntimeException e){ return null; }
	}
	/** The URL in the bytes: their first line, trimmed, when it has a scheme ("http:", "mailto:"...) and no spaces. */
	static String url(byte[] b){
		String s = new String(b, StandardCharsets.ISO_8859_1);
		int end = s.length();
		for(int i=0; i<s.length(); i++){ char c = s.charAt(i); if(c=='\r' || c=='\n' || c==0){ end = i; break; } }
		s = s.substring(0, end).trim();
		if(s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length()-1).trim();
		if(s.toUpperCase(java.util.Locale.ROOT).startsWith("URL:")) s = s.substring(4).trim();
		int colon = s.indexOf(':');
		if(colon<2 || s.indexOf(' ')>=0 || s.indexOf('\t')>=0) return null;
		for(int i=0; i<colon; i++){ char c = s.charAt(i); if(!(Character.isLetterOrDigit(c) && c<0x80) && c!='+' && c!='-' && c!='.') return null; }
		return s;
	}
}

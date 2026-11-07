import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tesseract downloaded later from the launcher's Translation page (PictureOcr reads the text in pictures with it):
 * HyperZebra does not ship it.
 * <ul>
 * <li>macOS: HyperZebra's own build of the unmodified upstream sources (tools/release/build-tesseract-mac.sh, one ZIP for
 * Apple silicon and Intel, with its licenses) from the HyperZebra project's GitHub release, checked by its SHA-256 and
 * unpacked into the state folder's "Tesseract" folder.</li>
 * <li>Windows: the Tesseract installer of UB Mannheim (its latest GitHub release), run with its own window; it installs
 * into Program Files, where PictureOcr looks.</li>
 * <li>Elsewhere: the system's packages (nothing is downloaded).</li>
 * </ul>
 * The languages (traineddata) come from Tesseract's own tessdata_fast models into the state folder's "tessdata" folder,
 * or from files the user has. -Dhz.tesseract.url / -Dhz.tessdata.url replace where they come from.
 */
final class TesseractInstall {
	private TesseractInstall(){}

	/** The version of HyperZebra's macOS build and where it is published. */
	static final String MAC_VERSION="5.5.4";
	static final String MAC_URL="https://github.com/pgostation/HyperZebra/releases/download/tesseract-"+MAC_VERSION+"/tesseract-"+MAC_VERSION+"-mac.zip";
	/** The SHA-256 of that ZIP (printed by build-tesseract-mac.sh). */
	static final String MAC_SHA256="90d2edb647a741a8180c5187e7f7f6670c0558593524a38a46109fbc804fd827";
	/** UB Mannheim's releases (the Windows installer is their asset tesseract-ocr-w64-setup-….exe). */
	static final String WINDOWS_RELEASES="https://api.github.com/repos/UB-Mannheim/tesseract/releases/latest";
	/** The models downloaded (tessdata_fast: the ones the picture reading was tuned with). */
	static final String TESSDATA_URL="https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0/";
	/** The languages installed with Tesseract (more from the languages dialog). */
	static final String[] FIRST_LANGUAGES={"eng","jpn"};

	/** The languages of tessdata_fast 4.1.0 and their sizes in KB (osd and equ are not text languages). */
	static final String[] AVAILABLE=("afr:2590 amh:5341 ara:1398 asm:1997 aze:3442 aze_cyrl:1898 bel:3605 ben:835 bod:1920 bos:2439 "
		+"bre:6186 bul:1635 cat:1119 ceb:699 ces:3706 chi_sim:2411 chi_sim_vert:1882 chi_tra:2311 chi_tra_vert:1781 chr:366 cos:2245 "
		+"cym:2157 dan:2519 deu:1489 div:1732 dzo:439 ell:1386 eng:4016 enm:3028 epo:4617 est:4353 eus:5054 fao:3359 fas:421 fil:1798 "
		+"fin:7681 fra:1103 frk:6272 frm:1977 fry:1861 gla:2996 gle:1154 glg:2494 grc:2193 guj:1385 hat:1930 heb:938 hin:1096 hrv:4007 "
		+"hun:5172 hye:3382 iku:2736 ind:1096 isl:2225 ita:2638 ita_old:3209 jav:2912 jpn:2413 jpn_vert:2966 kan:3523 kat:2465 "
		+"kat_old:413 kaz:4623 khm:1413 kir:9695 kmr:3484 kor:1638 kor_vert:1087 lao:6237 lat:3112 lav:2653 lit:3080 ltz:2545 mal:5152 "
		+"mar:2068 mkd:1562 mlt:2254 mon:2086 mri:842 msa:1706 mya:4531 nep:979 nld:5908 nor:3525 oci:6173 ori:1445 pan:486 pol:4653 "
		+"por:1936 pus:1730 que:4908 ron:2320 rus:3771 san:12113 sin:1686 slk:4323 slv:2933 snd:1654 spa:2240 spa_old:2826 sqi:1830 "
		+"srp:2099 srp_latn:3204 sun:1337 swa:2116 swe:4069 syr:2155 tam:3162 tat:1047 tel:2704 tgk:2541 tha:1047 tir:369 ton:925 "
		+"tur:4443 uig:2728 ukr:3735 urd:1365 uzb:6319 uzb_cyrl:1526 vie:518 yid:532 yor:940").split(" ");

	/** What a long download says and asks while it runs (another thread: the callee hands it to the event thread). */
	interface Progress {
		/** @param fraction 0-1, or -1 when not known */
		void report(String text,double fraction);
		boolean cancelled();
	}

	static boolean mac(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("mac");}
	static boolean windows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");}
	/** Whether Tesseract can be downloaded here (macOS, Windows). */
	static boolean canDownload(){return mac()||windows();}

	/** The folder of the downloaded Tesseract (macOS): <state>/Tesseract. */
	static File folder(){return new File(AppPaths.stateDirectory(),"Tesseract");}
	/** The downloaded tesseract program, or null. */
	static File executable(){
		File f=new File(new File(folder(),"bin"),windows()?"tesseract.exe":"tesseract");
		return f.isFile()?f:null;
	}
	/** Whether {@code exe} is the downloaded one (its languages are only those of the state folder). */
	static boolean downloaded(File exe){
		File own=executable();
		if(own==null||exe==null)return false;
		try{return own.getCanonicalFile().equals(exe.getCanonicalFile());}catch(IOException e){return own.equals(exe);}
	}

	/** How big the program download is, for asking first ("about 9 MB"). */
	static String programSize(){return mac()?"~10 MB":"~50 MB";}

	/**
	 * Downloads and installs Tesseract and the first languages ({@link #FIRST_LANGUAGES}). macOS: unpacked into the state
	 * folder. Windows: UB Mannheim's installer is started and this waits for it to finish.
	 * @return null when it is done, or what went wrong
	 */
	static String installProgram(Progress p){
		try{
			if(mac()){
				String url=System.getProperty("hz.tesseract.url",MAC_URL);
				File zip=TempFiles.createFile("tesseract",".zip");
				try{
					download(url,zip,International.t("Downloading Tesseract…"),p);
					if(System.getProperty("hz.tesseract.url")==null&&!MAC_SHA256.isEmpty()&&!MAC_SHA256.equalsIgnoreCase(sha256(zip)))
						return International.t("The downloaded Tesseract is damaged (its checksum differs).");
					unpack(zip,folder());
				}finally{zip.delete();}
				File exe=executable();
				if(exe==null)return International.t("The download has no tesseract program.");
				exe.setExecutable(true);
			}else if(windows()){
				p.report(International.t("Looking for the Tesseract installer…"),-1);
				String url=System.getProperty("hz.tesseract.url");
				if(url==null){
					String json=text(WINDOWS_RELEASES);
					Matcher m=Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]*tesseract-ocr-w64-setup-[^\"]*\\.exe)\"").matcher(json);
					if(!m.find())return International.t("The Tesseract installer was not found.");
					url=m.group(1);
				}
				File dir=TempFiles.createDirectory("tesseract");
				File setup=new File(dir,url.substring(url.lastIndexOf('/')+1));
				download(url,setup,International.t("Downloading Tesseract…"),p);
				if(p.cancelled())return International.t("Cancelled.");
				p.report(International.t("The Tesseract installer is open: install it, then come back."),-1);
				// Start-Process: the installer asks for administrator rights itself (ShellExecute), which a direct start cannot
				Process pr=new ProcessBuilder("powershell","-NoProfile","-NonInteractive","-Command",
					"Start-Process -FilePath '"+setup.getPath().replace("'","''")+"' -Wait").redirectErrorStream(true).start();
				pr.getInputStream().transferTo(OutputStream.nullOutputStream());
				pr.waitFor();
				setup.delete();dir.delete();
			}else return International.t("Install the tesseract-ocr packages of your system.");
			if(p.cancelled())return International.t("Cancelled.");
			PictureOcr.reset();
			List<String> missing=new ArrayList<String>();
			Set<String> have=PictureOcr.languages();
			for(String l:FIRST_LANGUAGES)if(!have.contains(l))missing.add(l);
			String r=installLanguages(missing,p);
			PictureOcr.reset();
			return r;
		}catch(IOException e){
			return International.t("Downloading failed: {0}",e.getMessage()==null?e.toString():e.getMessage());
		}catch(InterruptedException e){
			Thread.currentThread().interrupt();
			return International.t("Cancelled.");
		}
	}

	/** Downloads languages into the state folder's tessdata. @return null when done, or what went wrong */
	static String installLanguages(List<String> codes,Progress p){
		File dir=PictureOcr.extraTessdata();
		dir.mkdirs();
		String base=System.getProperty("hz.tessdata.url",TESSDATA_URL);
		try{
			for(int i=0;i<codes.size();i++){
				if(p.cancelled())return International.t("Cancelled.");
				String code=codes.get(i);
				if(!code.matches("[A-Za-z_]+"))continue;
				File to=new File(dir,code+".traineddata");
				File part=new File(dir,code+".traineddata.part");
				download(base+code+".traineddata",part,International.t("Downloading {0} ({1}/{2})…",languageName(code),String.valueOf(i+1),String.valueOf(codes.size())),p);
				if(p.cancelled()){part.delete();return International.t("Cancelled.");}
				Files.move(part.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);
			}
			return null;
		}catch(IOException e){
			return International.t("Downloading failed: {0}",e.getMessage()==null?e.toString():e.getMessage());
		}finally{
			PictureOcr.reset();
		}
	}

	/** Copies traineddata files the user has into the state folder's tessdata. @return null, or what went wrong */
	static String addFiles(File[] files){
		File dir=PictureOcr.extraTessdata();
		dir.mkdirs();
		try{
			for(File f:files){
				if(!f.getName().endsWith(".traineddata"))return International.t("{0} is not a Tesseract language file (.traineddata).",f.getName());
				Files.copy(f.toPath(),new File(dir,f.getName()).toPath(),StandardCopyOption.REPLACE_EXISTING);
			}
			return null;
		}catch(IOException e){
			return e.getMessage()==null?e.toString():e.getMessage();
		}finally{
			PictureOcr.reset();
		}
	}

	/** Removes a language from the state folder's tessdata (the system's own stay). */
	static void remove(String code){
		new File(PictureOcr.extraTessdata(),code+".traineddata").delete();
		PictureOcr.reset();
	}

	/** The languages in the state folder's tessdata. */
	static Set<String> added(){
		Set<String> out=new TreeSet<String>();
		File[] f=PictureOcr.extraTessdata().listFiles((d,n)->n.endsWith(".traineddata"));
		if(f!=null)for(File x:f)out.add(x.getName().substring(0,x.getName().length()-".traineddata".length()));
		return out;
	}

	/** Size in KB of a tessdata_fast language, or 0. */
	static int sizeKB(String code){
		for(String a:AVAILABLE){int c=a.indexOf(':');if(a.substring(0,c).equals(code))return Integer.parseInt(a.substring(c+1));}
		return 0;
	}
	static List<String> availableCodes(){
		List<String> out=new ArrayList<String>();
		for(String a:AVAILABLE)out.add(a.substring(0,a.indexOf(':')));
		return out;
	}

	private static Map<String,String> iso3;
	/** A Tesseract language's name in HyperZebra's language ("jpn_vert" → "Japanese (vertical)"). */
	static synchronized String languageName(String code){
		if(iso3==null){
			iso3=new HashMap<String,String>();
			for(String two:Locale.getISOLanguages())try{iso3.put(new Locale(two).getISO3Language(),two);}catch(MissingResourceException ignored){}
		}
		Locale ui=Locale.forLanguageTag(International.language());
		String[] parts=code.split("_");
		String base=parts[0];
		String name;
		switch(base){
			case "chi":name=International.t(code.contains("tra")?"Chinese (Traditional)":"Chinese (Simplified)");break;
			case "frk":name=International.t("German (Fraktur)");break;
			case "enm":name=International.t("Middle English");break;
			case "frm":name=International.t("Middle French");break;
			case "grc":name=International.t("Ancient Greek");break;
			case "kmr":name=International.t("Kurdish (Kurmanji)");break;
			default:
				String two=iso3.get(base);
				Locale l=two!=null?new Locale(two):Locale.forLanguageTag(base);
				name=l.getDisplayLanguage(ui);
				if(name==null||name.isEmpty()||name.equals(base))name=code;
		}
		for(int i=1;i<parts.length;i++){
			switch(parts[i]){
				case "vert":name+=" "+International.t("(vertical)");break;
				case "old":name+=" "+International.t("(old)");break;
				case "cyrl":name+=" "+International.t("(Cyrillic)");break;
				case "latn":name+=" "+International.t("(Latin)");break;
				default:break;
			}
		}
		return name;
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static HttpClient client(){
		return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
	}
	private static HttpRequest request(String url){
		return HttpRequest.newBuilder(URI.create(url)).header("User-Agent","HyperZebra/"+PCARD.longVersion).timeout(Duration.ofMinutes(10)).GET().build();
	}

	private static String text(String url) throws IOException,InterruptedException{
		HttpResponse<String> r=client().send(request(url),HttpResponse.BodyHandlers.ofString());
		if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode()+" "+url);
		return r.body();
	}

	/** Downloads {@code url} into {@code to}, reporting how far it is. */
	static void download(String url,File to,String what,Progress p) throws IOException{
		p.report(what,-1);
		HttpResponse<InputStream> r;
		try{r=client().send(request(url),HttpResponse.BodyHandlers.ofInputStream());}
		catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
		try(InputStream in=r.body()){
			if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode()+" "+url);
			long total=r.headers().firstValueAsLong("Content-Length").orElse(-1),done=0;
			long shown=0;
			try(OutputStream out=new FileOutputStream(to)){
				byte[] buf=new byte[65536];
				for(int n;(n=in.read(buf))>0;){
					if(p.cancelled())break;
					out.write(buf,0,n);
					done+=n;
					if(done-shown>=256*1024){shown=done;p.report(what,total>0?(double)done/total:-1);}
				}
			}
			if(!p.cancelled()&&total>0&&done!=total)throw new IOException("incomplete download "+url);
		}
		if(p.cancelled())to.delete();
	}

	static String sha256(File f) throws IOException{
		try(InputStream in=new java.io.FileInputStream(f)){
			MessageDigest md=MessageDigest.getInstance("SHA-256");
			byte[] buf=new byte[65536];
			for(int n;(n=in.read(buf))>0;)md.update(buf,0,n);
			StringBuilder b=new StringBuilder();
			for(byte x:md.digest())b.append(String.format("%02x",x&0xff));
			return b.toString();
		}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
	}

	/** Unpacks the ZIP's top folder ("tesseract/…") into {@code into}, replacing what was there. */
	private static void unpack(File zip,File into) throws IOException{
		File part=new File(into.getParentFile(),into.getName()+".part");
		deleteTree(part);
		part.mkdirs();
		try(ZipInputStream in=new ZipInputStream(new java.io.FileInputStream(zip))){
			for(ZipEntry e;(e=in.getNextEntry())!=null;){
				String name=e.getName();
				if(name.startsWith("__MACOSX/")||name.contains("/._"))continue;
				int slash=name.indexOf('/');
				if(slash<0)continue;
				name=name.substring(slash+1);
				if(name.isEmpty())continue;
				File f=new File(part,name);
				if(!f.getCanonicalPath().startsWith(part.getCanonicalPath()+File.separator))throw new IOException("bad entry "+e.getName());
				if(e.isDirectory()){f.mkdirs();continue;}
				f.getParentFile().mkdirs();
				Files.copy(in,f.toPath(),StandardCopyOption.REPLACE_EXISTING);
				if(name.startsWith("bin/"))f.setExecutable(true);
			}
		}
		deleteTree(into);
		if(!part.renameTo(into))throw new IOException("cannot move "+part);
	}
	private static void deleteTree(File f){
		File[] in=f.listFiles();
		if(in!=null)for(File x:in)deleteTree(x);
		f.delete();
	}
}

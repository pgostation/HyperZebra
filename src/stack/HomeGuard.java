import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The last line of defence for the Home stack, whose scripts run in every stack. The {@link Sandbox} and the {@link StackGuard}
 * ask before Home's code changes, but a user can say yes by mistake, and a tool outside HyperZebra asks nobody. Each time
 * HyperZebra itself saves a Home whose code is new, it keeps a copy and a fingerprint of the code, in a hidden folder no hosted
 * code can reach; Home found at start-up or at a save with other code than that was changed behind its back, and the user is
 * told: at start-up with the copy to go back to, at a save the copy in memory is what stays. Field text and the like are not
 * code: they change freely.
 * <p>
 * One state folder can serve several Homes with code of their own (the project tree's, the one a packaged app unpacks), so
 * each has its own record and copies: one Home is no changed version of another.
 */
final class HomeGuard {
	private HomeGuard(){}

	private static final String FOLDER = ".home-guard", RECORD = "scripts.sha256", BACKUPS = "Backups";
	private static final int BACKUPS_KEPT = 5, PLACE_LENGTH = 16;
	/** Elements of _stack.xml that run: scripts, and the externals. */
	private static final Set<String> CODE = Set.of("script", "externalcommand", "xcmd");
	/** Tests answer here instead of a dialog: given the changed handlers, true goes back to the copy. */
	static volatile Predicate<String> decider;

	static File folder(){ return new File(AppPaths.stateDirectory(), FOLDER); }
	/** The record and copies of the Home in use. */
	static File place(){ return new File(folder(), sha256(Sandbox.canonical(HomeStack.folder())).substring(0, PLACE_LENGTH)); }
	private static File record(){ return new File(place(), RECORD); }
	private static File backups(){ return new File(place(), BACKUPS); }
	private static File homeFile(){ return new File(HomeStack.folder(), "_stack.xml"); }

	/** Before Home is read for the first time in this run. */
	static synchronized void startUp(){
		if(inactive()) return;
		File home = homeFile();
		if(!home.isFile()) return;
		String known = recorded(), now = fingerprint(home);
		if(known==null){ keep(home, now); return; }
		if(known.equals(now)) return;
		File copy = latestCopy();
		String changes = copy==null ? "" : StackGuard.changedHandlers(code(copy), code(home));
		Boolean back = goesBack(changes, copy!=null);
		if(back==null) return; //nobody to ask: it stays on record as changed
		if(back){
			try{ Files.copy(copy.toPath(), home.toPath(), StandardCopyOption.REPLACE_EXISTING); }
			catch(IOException e){ e.printStackTrace(); }
		}
		else keep(home, now);
	}

	/** HyperZebra has just made Home, from its own copy: what it holds is the known code, whatever Home was there before. */
	static synchronized void installed(){
		if(inactive() || !homeFile().isFile()) return;
		keep(homeFile(), fingerprint(homeFile()));
	}

	/** Home is about to be saved over {@code file}: what is there should be what HyperZebra left. */
	static synchronized void beforeSave(File file){
		if(inactive() || !isHome(file) || !file.isFile()) return;
		String known = recorded();
		if(known==null || known.equals(fingerprint(file))) return;
		System.err.println("[home guard] Home was changed outside HyperZebra: "+file);
		if(decider!=null){ decider.test(""); return; }
		if(java.awt.GraphicsEnvironment.isHeadless()) return;
		javax.swing.SwingUtilities.invokeLater(() -> StackGuard.choose(International.t("Home Stack Changed"),
				International.t("Something outside HyperZebra changed the Home stack's file while it was open. HyperZebra's own copy has been saved over that change."), "OK"));
	}

	/** Home was saved to {@code file}: its code, if new, is the code to compare with from now on. */
	static synchronized void saved(File file){
		if(inactive() || !isHome(file)) return;
		String now = fingerprint(file);
		if(!now.equals(recorded())) keep(file, now);
	}

	private static boolean inactive(){ return decider==null && Sandbox.allowsEverything(); }
	private static boolean isHome(File file){ return Sandbox.canonical(file).equals(Sandbox.canonical(homeFile())); }

	/** True goes back, false keeps what is there, null when there is nobody to ask. */
	private static Boolean goesBack(String changes, boolean hasCopy){
		if(decider!=null) return decider.test(changes);
		if(java.awt.GraphicsEnvironment.isHeadless()){ System.err.println("[home guard] Home was changed outside HyperZebra"); return null; }
		String text = International.t("The Home stack's scripts were changed outside HyperZebra. Scripts in Home run in every stack.");
		if(!changes.isEmpty()) text += "\n\n"+changes;
		if(!hasCopy){
			StackGuard.choose(International.t("Home Stack Changed"), text, International.t("Keep Home As It Is"));
			return false;
		}
		return StackGuard.choose(International.t("Home Stack Changed"), text, International.t("Restore the Last Copy"), International.t("Keep Home As It Is"))==0;
	}

	/** This Home's code is the known one: a copy of it, and its fingerprint. */
	private static void keep(File home, String print){
		try{
			File copy = new File(backups(), new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new java.util.Date()));
			Files.createDirectories(copy.toPath());
			Files.copy(home.toPath(), new File(copy, home.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
			Files.write(record().toPath(), print.getBytes(StandardCharsets.US_ASCII));
			List<File> copies = copies();
			for(int i=0; i<copies.size()-BACKUPS_KEPT; i++) deleteCopy(copies.get(i));
		}catch(IOException e){
			System.err.println("[home guard] could not keep Home: "+e);
		}
	}

	private static void deleteCopy(File copy){
		File[] files = copy.listFiles();
		if(files!=null) for(File f : files) f.delete();
		copy.delete();
	}

	/** The copies, oldest first (their names are their dates). */
	private static List<File> copies(){
		File[] all = backups().listFiles(File::isDirectory);
		List<File> copies = new ArrayList<File>(all==null ? List.of() : Arrays.asList(all));
		copies.sort(Comparator.comparing(File::getName));
		return copies;
	}

	private static File latestCopy(){
		List<File> copies = copies();
		if(copies.isEmpty()) return null;
		File copy = new File(copies.get(copies.size()-1), "_stack.xml");
		return copy.isFile() ? copy : null;
	}

	private static String recorded(){
		try{ return record().isFile() ? new String(Files.readAllBytes(record().toPath()), StandardCharsets.US_ASCII).trim() : null; }
		catch(IOException e){ return null; }
	}

	/** The fingerprint of a stack file's code. */
	static String fingerprint(File xml){ return sha256(code(xml)); }

	private static String sha256(String text){
		try{
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] hash = sha.digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for(byte b : hash) hex.append(String.format("%02x", b));
			return hex.toString();
		}catch(NoSuchAlgorithmException e){
			throw new IllegalStateException(e);
		}
	}

	/** The scripts and externals of a stack file; the whole file when it cannot be taken apart (a sealed one). */
	private static String code(File xml){
		byte[] bytes;
		try{ bytes = Files.readAllBytes(xml.toPath()); }
		catch(IOException e){ return ""; }
		StringBuilder code = new StringBuilder();
		try{
			XMLStreamReader reader = XmlSafe.input().createXMLStreamReader(new ByteArrayInputStream(bytes));
			int depth = 0;
			while(reader.hasNext()){
				int event = reader.next();
				if(event==XMLStreamConstants.START_ELEMENT){
					if(depth==0 && !CODE.contains(reader.getLocalName())) continue;
					depth++;
					for(int i=0; i<reader.getAttributeCount(); i++) code.append(reader.getAttributeValue(i)).append('\n');
				}
				else if(event==XMLStreamConstants.END_ELEMENT){
					if(depth>0 && --depth==0) code.append('\n');
				}
				else if(depth>0 && reader.hasText()) code.append(reader.getText());
			}
			return code.toString();
		}catch(XMLStreamException | RuntimeException e){
			return new String(bytes, StandardCharsets.ISO_8859_1);
		}
	}
}

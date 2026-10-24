import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * Stack protection as HyperCard's Protect Stack dialog has it: password, private access and user level limit.
 *
 * <p>Passwords are kept as HyperCard keeps them - the 32-bit hash at STAK 0x44 - so a password set in HyperCard works
 * here and a password set here works after "Export HyperCard Stack…". The hash is HyperCard's: the password without
 * case and accents, hashed with the old Mac OS Random function, and that hash hashed again as a 4-character string.
 * A Private Access HyperCard stack has STAK 0x18-0x49 XORed with a stream seeded from the first hash, so importing one
 * asks for its password.
 *
 * <p>A .hzb folder of a stack with a password keeps its _stack.xml sealed (AES-GCM), so scripts and card text cannot be
 * read by opening the folder. A Private Access stack is sealed with a key made from its password (the password is
 * needed to open it, as in HyperCard); a stack with only a password is sealed with a key HyperZebra itself can make,
 * which keeps the contents out of plain sight without asking anything on open. The card and background pictures
 * (and their paint layers) are sealed too, with a random key of the stack kept inside the sealed _stack.xml.
 */
final class StackProtection {
	private StackProtection(){}

	//---- HyperCard's password hash ----

	/** The Random function of the first Mac OS, which HyperCard uses for its password hash. */
	static long macRandom(long x){
		long h = (x & 0xFFFFFFFFL) * 0x41A7L;
		h += h >>> 31;
		return h & 0x7FFFFFFFL;
	}

	/** HyperCard ignores case and accents in passwords. */
	static String normalize(String password){
		if(password==null) return "";
		String s = Normalizer.normalize(password, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
	}

	static byte[] passwordBytes(String password, StackTextEncoding encoding){
		String s = normalize(password);
		return (encoding==null ? StackTextEncoding.ROMAN : encoding).encodeReplacing(s);
	}

	/** HyperCard's hash of a string (the first hash; it seeds the Private Access header encryption). */
	static int hash(byte[] string){
		long x = 0;
		int c0 = string.length>0 ? string[0]&0xFF : 0;
		long s = c0 + string.length;
		if(s>0xFF) s &= 0xFF;
		else if(c0>0x80) s |= 0xFFFFFF00L;
		for(byte b : string){
			for(int bit=7; bit>=0; bit--){
				s = macRandom(s);
				if(((b>>bit)&1)!=0) x += s;
			}
		}
		if(x==0) return 0x42696C6C; //'Bill'
		return (int)x;
	}

	/** The value HyperCard stores at STAK 0x44: the hash of the first hash taken as a 4-character string. */
	static int passwordHash(int firstHash){
		return hash(new byte[]{(byte)(firstHash>>24), (byte)(firstHash>>16), (byte)(firstHash>>8), (byte)firstHash});
	}

	static int passwordHash(String password, StackTextEncoding encoding){
		return passwordHash(hash(passwordBytes(password, encoding)));
	}

	static boolean checkPassword(int storedHash, String password, StackTextEncoding encoding){
		if(storedHash==0 || password==null) return false;
		if(passwordHash(password, encoding)==storedHash) return true;
		//a password typed with an input method that the stack's encoding cannot hold: try Mac OS Roman too
		return encoding!=StackTextEncoding.ROMAN && passwordHash(password, StackTextEncoding.ROMAN)==storedHash;
	}

	//---- Private Access STAK header ----

	static final int ENCRYPTED_OFFSET = 0x18, ENCRYPTED_LENGTH = 0x32;
	static final int FLAG_PRIVATE_ACCESS = 1<<13;

	/** XORs STAK 0x18-0x49 with the stream of a first hash. The same call encrypts and decrypts. */
	static void xorHeader(byte[] data, int stakOffset, int firstHash){
		long h = firstHash & 0xFFFFFFFFL;
		for(int i=0; i<10; i++) h = macRandom(h);
		for(int i=ENCRYPTED_OFFSET; i<=ENCRYPTED_OFFSET+ENCRYPTED_LENGTH-4; i+=2){
			h = macRandom(h);
			int at = stakOffset+i;
			data[at]   ^= (byte)(h>>24);
			data[at+1] ^= (byte)(h>>16);
			data[at+2] ^= (byte)(h>>8);
			data[at+3] ^= (byte)h;
		}
	}

	static boolean isPrivateAccess(byte[] data, int stakOffset){
		if(data.length<stakOffset+0x50) return false;
		int flags = ((data[stakOffset+0x4C]&0xFF)<<8)|(data[stakOffset+0x4D]&0xFF);
		return (flags & FLAG_PRIVATE_ACCESS)!=0;
	}

	/** Decrypts a Private Access STAK header in place when the password is right; leaves it as it was otherwise. */
	static boolean decryptHeader(byte[] data, int stakOffset, String password, StackTextEncoding encoding){
		StackTextEncoding[] tries = encoding==StackTextEncoding.ROMAN || encoding==null
			? new StackTextEncoding[]{StackTextEncoding.ROMAN} : new StackTextEncoding[]{encoding, StackTextEncoding.ROMAN};
		for(StackTextEncoding enc : tries){
			int first = hash(passwordBytes(password, enc));
			byte[] copy = Arrays.copyOfRange(data, stakOffset, stakOffset+0x50);
			xorHeader(copy, 0, first);
			int stored = ByteBuffer.wrap(copy, 0x44, 4).getInt();
			if(stored==passwordHash(first)){
				System.arraycopy(copy, 0, data, stakOffset, 0x50);
				return true;
			}
		}
		return false;
	}

	//---- User level ----

	static final String[] LEVEL_NAMES = {"Browsing", "Typing", "Painting", "Authoring", "Scripting"};

	/** The stack's limit (HyperCard stores 0 for "no limit"). */
	static int stackLimit(OStack stack){
		if(stack==null || stack.userLevel<1 || stack.userLevel>5) return 5;
		return stack.userLevel;
	}

	/** The user level in effect: the user's level, lowered to the limit of the current stack. */
	static int effectiveUserLevel(){
		PCARD pc = PCARD.pc;
		int level = PCARD.userLevel;
		if(pc!=null) level = Math.min(level, stackLimit(pc.stack));
		return Math.max(1, Math.min(5, level));
	}

	/** Whether the user may open scripts from the card (peek clicks, Command-Option-C/B/S, Script… buttons). */
	static boolean canOpenScripts(){
		PCARD pc = PCARD.pc;
		return effectiveUserLevel()>=5 && (pc==null || pc.stack==null || !pc.stack.cantPeek);
	}

	/** Lowest user level at which a tool can be chosen from the Tools menu or tool palette. */
	static int levelForTool(String tool){
		if(tool==null) return 1;
		String t = tool.trim().toLowerCase(Locale.ROOT);
		if(t.equals("browse")) return 1;
		if(t.equals("button") || t.equals("field") || t.equals("shape")) return 4;
		return 3; //paint tools
	}

	//---- Sealed _stack.xml ----

	static final byte[] MAGIC = "HZBLOCK1".getBytes(StandardCharsets.US_ASCII);
	static final int MODE_APP = 0, MODE_PASSWORD = 1;
	private static final int SALT = 16, IV = 12, ITERATIONS = 120000;
	private static final SecureRandom random = new SecureRandom();

	static boolean isSealed(byte[] data){
		if(data==null || data.length<MAGIC.length+1+SALT+IV) return false;
		for(int i=0; i<MAGIC.length; i++) if(data[i]!=MAGIC[i]) return false;
		return true;
	}

	static boolean needsPassword(byte[] data){
		return isSealed(data) && data[MAGIC.length]==MODE_PASSWORD;
	}

	private static SecretKeySpec key(int mode, byte[] salt, String password) throws GeneralSecurityException {
		if(mode==MODE_PASSWORD){
			PBEKeySpec spec = new PBEKeySpec(normalize(password).toCharArray(), salt, ITERATIONS, 256);
			try {
				return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
			} finally {
				spec.clearPassword();
			}
		}
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		sha.update("HyperZebra sealed stack v1".getBytes(StandardCharsets.US_ASCII));
		sha.update(salt);
		return new SecretKeySpec(sha.digest(), "AES");
	}

	/** Seals XML bytes; with a password (Private Access) the key comes from it, otherwise from HyperZebra. */
	static byte[] seal(byte[] plain, String privatePassword) throws GeneralSecurityException {
		int mode = privatePassword!=null ? MODE_PASSWORD : MODE_APP;
		byte[] salt = new byte[SALT], iv = new byte[IV];
		random.nextBytes(salt);
		random.nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, key(mode, salt, privatePassword), new GCMParameterSpec(128, iv));
		cipher.updateAAD(MAGIC);
		byte[] body = cipher.doFinal(plain);
		ByteArrayOutputStream out = new ByteArrayOutputStream(body.length+64);
		out.write(MAGIC, 0, MAGIC.length);
		out.write(mode);
		out.write(salt, 0, SALT);
		out.write(iv, 0, IV);
		out.write(body, 0, body.length);
		return out.toByteArray();
	}

	/** Opens sealed bytes; throws when the password is wrong or the data was changed. */
	static byte[] unseal(byte[] data, String password) throws GeneralSecurityException {
		int at = MAGIC.length;
		int mode = data[at++];
		byte[] salt = Arrays.copyOfRange(data, at, at+SALT); at += SALT;
		byte[] iv = Arrays.copyOfRange(data, at, at+IV); at += IV;
		if(mode==MODE_PASSWORD && password==null) throw new GeneralSecurityException("password needed");
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, key(mode, salt, password), new GCMParameterSpec(128, iv));
		cipher.updateAAD(MAGIC);
		return cipher.doFinal(data, at, data.length-at);
	}

	/** Whether a stack's _stack.xml is written sealed, and with which password (null: HyperZebra's key). */
	static boolean sealsXml(OStack stack){
		return stack!=null && stack.getPasswordHash()!=0;
	}

	static String sealPassword(OStack stack){
		return stack.getPrivateAccess() && stack.unlockedPassword!=null ? stack.unlockedPassword : null;
	}

	//---- Sealed pictures ----
	//カード・バックグラウンドのピクチャ(BMAP_*.png)、ペイントレイヤー、resource/cdp・bgp も封印する。
	//鍵はスタックごとの乱数(contentKey)で、封印した_stack.xmlの中にだけ書く。パスワードを変えても絵は
	//書き直さずに済み、PBKDF2を絵ごとに回さないので速い。

	static final int MODE_CONTENT = 2;
	/** Content keys of open stacks by folder, so a loader that only has the file finds its key. */
	private static final java.util.Map<String, byte[]> contentKeys = new java.util.concurrent.ConcurrentHashMap<>();

	private static String folderKey(File folder){
		return folder.getAbsoluteFile().toPath().normalize().toString();
	}

	static File folderOf(OStack stack){
		if(stack==null || stack.file==null) return null;
		File f = stack.file.getAbsoluteFile();
		return f.isDirectory() ? f : f.getParentFile();
	}

	/** The stack's content key: made when the stack has a password and has none yet; null without a password. */
	static synchronized byte[] contentKey(OStack stack){
		if(stack==null || !sealsXml(stack)) return null;
		if(stack.contentKey==null){
			stack.contentKey = new byte[32];
			random.nextBytes(stack.contentKey);
			stack.changed = true; //_stack.xmlにも書く
		}
		registerContentKey(stack);
		return stack.contentKey;
	}

	/** Makes the key of a stack known to picture loaders (after reading its _stack.xml); forgets it without one. */
	static void registerContentKey(OStack stack){
		File folder = folderOf(stack);
		if(folder!=null) registerContentKey(folder, sealsXml(stack) ? stack.contentKey : null);
	}

	static void registerContentKey(File folder, byte[] key){
		if(key!=null) contentKeys.put(folderKey(folder), key);
		else contentKeys.remove(folderKey(folder));
	}

	/** The key for a file in a stack folder (or a folder inside one), null when it has none. */
	static byte[] keyForFile(File file){
		if(contentKeys.isEmpty()) return null;
		for(File dir = file.getAbsoluteFile().getParentFile(); dir!=null; dir = dir.getParentFile()){
			byte[] key = contentKeys.get(folderKey(dir));
			if(key!=null) return key;
		}
		return null;
	}

	static byte[] sealContent(byte[] plain, byte[] key) throws GeneralSecurityException {
		byte[] salt = new byte[SALT], iv = new byte[IV];
		random.nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
		cipher.updateAAD(MAGIC);
		byte[] body = cipher.doFinal(plain);
		ByteArrayOutputStream out = new ByteArrayOutputStream(body.length+64);
		out.write(MAGIC, 0, MAGIC.length);
		out.write(MODE_CONTENT);
		out.write(salt, 0, SALT);
		out.write(iv, 0, IV);
		out.write(body, 0, body.length);
		return out.toByteArray();
	}

	static byte[] unsealContent(byte[] data, byte[] key) throws GeneralSecurityException {
		if(!isSealed(data) || data[MAGIC.length]!=MODE_CONTENT) throw new GeneralSecurityException("not a sealed picture");
		int at = MAGIC.length+1+SALT;
		byte[] iv = Arrays.copyOfRange(data, at, at+IV); at += IV;
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
		cipher.updateAAD(MAGIC);
		return cipher.doFinal(data, at, data.length-at);
	}

	/** Reads a picture of a stack folder, sealed or not (a sealed one needs its stack open: null otherwise). */
	static BufferedImage readImage(File file) throws IOException {
		byte[] data = Files.readAllBytes(file.toPath());
		if(isSealed(data)){
			byte[] key = keyForFile(file);
			if(key==null) return null;
			try { data = unsealContent(data, key); }
			catch(GeneralSecurityException e){ throw new IOException("sealed picture could not be opened: "+file.getName(), e); }
		}
		return ImageIO.read(new ByteArrayInputStream(data));
	}

	/** Writes a picture into a stack folder: sealed when the stack has a password. */
	static void writeImage(OStack stack, BufferedImage image, File file) throws IOException {
		byte[] key = stack!=null ? contentKey(stack) : keyForFile(file);
		if(key==null){
			ImageIO.write(image, "png", file);
			return;
		}
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(image, "png", png);
		try { Files.write(file.toPath(), sealContent(png.toByteArray(), key)); }
		catch(GeneralSecurityException e){ throw new IOException(e); }
	}

	/** The picture files of a stack: card and background pictures, their paint layers, the old color pictures. */
	static java.util.List<File> pictureFiles(OStack stack){
		java.util.LinkedHashSet<File> files = new java.util.LinkedHashSet<>();
		File folder = folderOf(stack);
		if(folder==null) return new java.util.ArrayList<>();
		java.util.List<OCardBase> owners = new java.util.ArrayList<>();
		if(stack.cdCacheList!=null) owners.addAll(stack.cdCacheList);
		if(stack.bgCacheList!=null) owners.addAll(stack.bgCacheList);
		for(OCardBase o : owners){
			if(o.bitmapName!=null) files.add(new File(folder, o.bitmapName));
			if(o.layers!=null) for(PaintLayer l : o.layers) if(l.fileName!=null) files.add(new File(folder, l.fileName));
			String kind = o instanceof OBackground ? "bgp" : "cdp";
			for(String ext : new String[]{".png", ".jpeg", "_img.png", "_mask.png"}) files.add(new File(folder, "resource/"+kind+o.id+ext));
		}
		java.util.List<File> result = new java.util.ArrayList<>();
		for(File f : files) if(f.isFile()) result.add(f);
		return result;
	}

	/**
	 * Seals (the stack got a password) or opens (it lost it) every picture file of a stack in place.
	 * Byte for byte: the pictures are not decoded again.
	 */
	static void resealPictures(OStack stack, byte[] key, boolean seal){
		if(key==null) return;
		for(File f : pictureFiles(stack)){
			try {
				byte[] data = Files.readAllBytes(f.toPath());
				boolean sealed = isSealed(data);
				if(seal==sealed) continue;
				byte[] out = seal ? sealContent(data, key) : unsealContent(data, key);
				XMLwrite.writeAtomically(f, out);
				PictureCache.invalidate(f);
			} catch(IOException | GeneralSecurityException e){
				System.err.println("picture "+f+" could not be "+(seal?"sealed":"opened")+": "+e);
			}
		}
	}

	//---- Password prompt ----

	/** Test hook: when set, prompts answer with this instead of showing a dialog (null there means Cancel). */
	static java.util.function.Function<String,String> promptHook;

	/** Asks for a password; null when cancelled. */
	static String askPassword(String message){
		if(promptHook!=null) return promptHook.apply(message);
		if(java.awt.GraphicsEnvironment.isHeadless()) return null;
		final String[] answer = new String[1];
		Runnable ask = () -> {
			JPasswordField field = new JPasswordField(24);
			field.addAncestorListener(new javax.swing.event.AncestorListener(){
				public void ancestorAdded(javax.swing.event.AncestorEvent e){ SwingUtilities.invokeLater(field::requestFocusInWindow); }
				public void ancestorRemoved(javax.swing.event.AncestorEvent e){}
				public void ancestorMoved(javax.swing.event.AncestorEvent e){}
			});
			Object[] content = {message, field};
			int r = JOptionPane.showConfirmDialog(PCARD.pc!=null && PCARD.pc.isShowing() ? PCARD.pc : null, content,
				PCARD.AppName, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if(r==JOptionPane.OK_OPTION) answer[0] = new String(field.getPassword());
		};
		if(SwingUtilities.isEventDispatchThread()) ask.run();
		else {
			try { SwingUtilities.invokeAndWait(ask); }
			catch(Exception e){ return null; }
		}
		return answer[0];
	}

	static String text(String english){
		return PCARD.pc!=null && PCARD.pc.intl!=null ? PCARD.pc.intl.getDialogText(english) : english;
	}

	/** A stack's name for prompts, without the ".hzb" of its folder. */
	static String displayName(OStack stack){
		String n = stack.name;
		if((n==null || n.isEmpty()) && stack.file!=null && stack.file.getAbsoluteFile().getParentFile()!=null) n = stack.file.getAbsoluteFile().getParentFile().getName();
		return n==null ? "" : n.replaceFirst("(?i)\\.hzb$", "");
	}

	/**
	 * Asks until the password of a stack is right. Returns the password, or null when the user cancelled.
	 * {@code check} says whether a typed password is right.
	 */
	static String askUntilRight(String stackName, java.util.function.Predicate<String> check){
		String message = text("Password for stack")+" \""+stackName+"\":";
		for(int tries=0; tries<10; tries++){
			String typed = askPassword(message);
			if(typed==null) return null;
			if(check.test(typed)) return typed;
			java.awt.Toolkit.getDefaultToolkit().beep();
			message = text("Wrong password. Try again.")+"\n"+text("Password for stack")+" \""+stackName+"\":";
			if(promptHook!=null && tries>=2) return null;
		}
		return null;
	}

	/**
	 * After a stack was read: a Private Access stack needs its password before it opens (a sealed one was already
	 * unlocked while reading). False when the user did not give it.
	 */
	static boolean unlockOnOpen(OStack stack){
		if(!stack.getPrivateAccess() || stack.getPasswordHash()==0 || stack.unlockedPassword!=null) return true;
		String pw = askUntilRight(displayName(stack), p -> checkPassword(stack.getPasswordHash(), p, stack.legacyTextEncoding));
		if(pw==null) return false;
		stack.unlockedPassword = pw;
		return true;
	}
}

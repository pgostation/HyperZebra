import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the extensions in the System Folder may run. An INIT there starts inside every program, which is what made a virus
 * a resident one: Scores' hidden files, SevenDust's "Graphics Accelerator", INIT 1984 and INIT-M in the extensions they found. A list of
 * the code of the extensions the user has (state folder, closed to hosted code: {@link #folder}) tells new and changed ones from
 * those: they do not run until the user says so, once, in one question however many there are (an installer brings dozens).
 * The first run takes what is there as the user's, but for what {@link VirusScan68k} knows. Only code counts: extensions keep their
 * settings in their own files.
 */
final class ExtensionGuard {
	private ExtensionGuard(){}

	private static final int CFRG=ResourceFile68k.fourcc("cfrg"),THNG=ResourceFile68k.fourcc("thng");
	/** Code seen this session that the user refused, and infected files by path: not asked about again until the next run. */
	private static final Set<String> refused=Collections.synchronizedSet(new HashSet<String>());
	/** Infected files the user chose to load, this session. */
	private static final Set<String> keptInfected=Collections.synchronizedSet(new HashSet<String>());

	static File folder(){return new File(AppPaths.stateDirectory(),".vm-guard");}
	private static File list(){return new File(folder(),"extensions.sha256");}

	/** Of the extensions about to load in {@code program}, the ones that stay out. */
	static synchronized Set<File> review(List<File> extensions,String program){
		Set<File> held=new HashSet<File>();
		if(Sandbox.allowsEverything())return held;
		boolean first=!list().isFile();
		Set<String> known=first?new HashSet<String>():read();
		List<File> unknown=new ArrayList<File>();List<String> codes=new ArrayList<String>();
		for(File file:extensions){
			String code;
			try{
				ResourceFile68k rf=ResourceFile68k.open(file);
				VirusScan68k.Finding found=VirusScan68k.scan(rf,file.getName());
				String path=Sandbox.canonical(file);
				if(found!=null&&!keptInfected.contains(path)){
					if(!refused.contains(path)&&Sandbox.allowInfectedExtension(file.getName(),found.name))keptInfected.add(path);
					else{refused.add(path);held.add(file);continue;}
				}
				code=digest(file,rf);
			}catch(IOException|RuntimeException e){held.add(file);System.err.println("[extensions] can't read "+file+": "+e);continue;}
			if(code==null||known.contains(code))continue; // nothing to run, or what the user has
			if(refused.contains(code)){held.add(file);continue;}
			if(first){known.add(code);codes.add(code);continue;}
			unknown.add(file);codes.add(code);
		}
		if(first){write(codes,true);return held;}
		if(unknown.isEmpty())return held;
		StringBuilder names=new StringBuilder();
		for(File file:unknown)names.append(names.length()==0?"":"\n").append(file.getName());
		if(Sandbox.allowNewExtensions(program,names.toString()))write(codes,false);
		else{refused.addAll(codes);held.addAll(unknown);}
		return held;
	}

	/** The user put this extension in place (a drop on the launcher). */
	static synchronized void approve(File file){
		if(Sandbox.allowsEverything())return;
		try{String code=digest(file,ResourceFile68k.open(file));if(code!=null&&list().isFile()&&!read().contains(code))write(Collections.singletonList(code),false);}
		catch(IOException|RuntimeException e){System.err.println("[extensions] can't note "+file+": "+e);}
	}

	/**
	 * What {@code file} can run: its code resources, the whole of a component file (a 'thng' names code of any type) and a fragment's
	 * data fork, as a hash; null when it has no code.
	 */
	static String digest(File file,ResourceFile68k rf) throws IOException {
		boolean all=FinderInfo68k.type(file)==THNG||!rf.ofType(THNG).isEmpty();
		List<ResourceFile68k.Resource> code=new ArrayList<ResourceFile68k.Resource>();
		for(ResourceFile68k.Resource r:rf.ordered)if(all||ProgramGuard68k.isCode(r.type))code.add(r);
		if(code.isEmpty())return null;
		code.sort(Comparator.<ResourceFile68k.Resource>comparingInt(r->r.type).thenComparingInt(r->r.id));
		MessageDigest sha;
		try{sha=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
		for(ResourceFile68k.Resource r:code){sha.update(ResourceFile68k.typeName(r.type).getBytes(StandardCharsets.ISO_8859_1));sha.update((byte)(r.id>>8));sha.update((byte)r.id);sha.update(r.data());}
		if(!rf.ofType(CFRG).isEmpty())sha.update(Files.readAllBytes(file.toPath()));
		StringBuilder hex=new StringBuilder();
		for(byte b:sha.digest())hex.append(String.format("%02x",b&0xff));
		return hex.toString();
	}

	private static Set<String> read(){
		Set<String> known=new HashSet<String>();
		try{known.addAll(Files.readAllLines(list().toPath(),StandardCharsets.UTF_8));}catch(IOException e){System.err.println("[extensions] can't read the list: "+e);}
		return known;
	}
	private static void write(List<String> codes,boolean create){
		try{
			Files.createDirectories(folder().toPath());
			Files.write(list().toPath(),codes,StandardCharsets.UTF_8,StandardOpenOption.CREATE,create?StandardOpenOption.TRUNCATE_EXISTING:StandardOpenOption.APPEND);
		}catch(IOException e){System.err.println("[extensions] can't write the list: "+e);}
	}
}

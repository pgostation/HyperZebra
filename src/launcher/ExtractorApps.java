import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The expander programs shipped with HyperZebra in their distributed form (the "ExtractorApps" folder beside the
 * program: StuffIt Expander's installer as MacBinary, Compact Pro's self-extracting archive as BinHex, MacLHA's as
 * MacBinary). When an archive is dropped and no known program opens it, the user is offered the one expander its format
 * needs ({@link #kitsFor}: all of them only when the format cannot be told): its file is copied into "Extractor Apps" in
 * HyperZebra HD (BinHex decoded to MacBinary on the way) and run in the 68K VM without the user
 * ({@link MacApp68k#launchUnattended}: dialogs take their default button, Save dialogs the offered name in that folder,
 * nothing outside HyperZebra HD is written). The expander programs found afterwards are registered in the launcher's
 * history and dock, and the archive that started it all is opened again, so it goes to its expander.
 */
final class ExtractorApps {
	private ExtractorApps(){}

	/** The expanders HyperZebra ships: the archive file types each one opens, the program signatures that are this expander
	 *  (the first one the shipped program's), and words of its distributed file's name. */
	enum Kit {
		STUFFIT("StuffIt Expander",new String[]{"SIT!","SITD","SIT5","SIT2"},new String[]{"SITx","SIT!"},"aladdin","stuffit"),
		COMPACT_PRO("Compact Pro",new String[]{"PACT"},new String[]{"CPCT"},"compact"),
		MACLHA("MacLHA",new String[]{"LHA ","LHa "},new String[]{"LARC"},"lha");
		final String title;final int[] types;private final int[] signatures;private final String[] fileWords;
		Kit(String title,String[] types,String[] signatures,String... fileWords){
			this.title=title;this.types=new int[types.length];this.signatures=new int[signatures.length];
			for(int i=0;i<types.length;i++)this.types[i]=ResourceFile68k.fourcc(types[i]);
			for(int i=0;i<signatures.length;i++)this.signatures[i]=ResourceFile68k.fourcc(signatures[i]);
			this.fileWords=fileWords;
		}
		/** Whether the program is this expander: its signature, or its bundle claims one of this expander's archive types and
		 *  it is not another kit's program (Compact Pro claims SIT! too, but expands no StuffIt archive of today). */
		boolean openedBy(File app){
			FinderIcons68k.Bundle b=FinderIcons68k.bundleOf(app);
			for(int s:signatures)if(b.signature==s)return true;
			for(Kit k:values())if(k!=this)for(int s:k.signatures)if(b.signature==s)return false;
			for(int t:types)if(b.claims(t))return true;
			return false;
		}
		/** This expander's file in the distribution folder, or null. */
		File distributedFile(File folder){
			if(folder==null)return null;
			for(File f:distributedFiles(folder)){String n=f.getName().toLowerCase();for(String w:fileWords)if(n.contains(w))return f;}
			return null;
		}
	}

	/** The distributed files, where the program keeps them: beside the working directory (the project tree) or in it (a packaged app). */
	static File distribution(){
		File beside=new File(AppPaths.distributionDirectory(),"ExtractorApps");
		if(beside.isDirectory()&&distributedFiles(beside).length>0)return beside;
		for(String path:new String[]{"ExtractorApps","../ExtractorApps","resource/ExtractorApps"}){
			File f=new File(path);
			if(f.isDirectory()&&distributedFiles(f).length>0)return f;
		}
		return null;
	}
	private static File[] distributedFiles(File folder){
		File[] files=folder.listFiles((d,n)->!n.startsWith(".")&&new File(d,n).isFile());
		if(files==null)return new File[0];
		java.util.Arrays.sort(files);
		return files;
	}
	/** Where the expanders are installed: a folder of HyperZebra HD (programs there are trusted like everything the user opened). */
	static File installFolder(){return new File(AppPaths.stateDirectory(),"Extractor Apps");}

	// ------------------------------------------------------------------ which expander an archive needs

	private static final int APPL=ResourceFile68k.fourcc("APPL"),STUFFIT_CREATOR=ResourceFile68k.fourcc("aust"),COMPACT_PRO_CREATOR=ResourceFile68k.fourcc("EXTR");

	/**
	 * The expanders the archive needs, told by its file type (a MacBinary wrapper's too), its creator for a self-extracting
	 * archive, the start of its data and last its name: StuffIt and BinHex need StuffIt Expander, Compact Pro needs Compact Pro,
	 * LHA needs MacLHA. Every one of them when nothing tells.
	 */
	static Set<Kit> kitsFor(File archive){
		int type=DocumentOpeners.typeOf(archive);
		for(Kit k:Kit.values())for(int t:k.types)if(t==type)return EnumSet.of(k);
		if(type==APPL){int creator=FinderInfo68k.creator(archive);
			if(creator==STUFFIT_CREATOR)return EnumSet.of(Kit.STUFFIT);
			if(creator==COMPACT_PRO_CREATOR)return EnumSet.of(Kit.COMPACT_PRO);}
		if(DocumentOpeners.isBinHex(archive))return EnumSet.of(Kit.STUFFIT); // the StuffIt programs decode BinHex
		Kit byData=byData(archive);
		if(byData!=null)return EnumSet.of(byData);
		if(BinHex.looksLikeBinHex(archive))return EnumSet.of(Kit.STUFFIT);
		String name=archive.getName().toLowerCase();
		if(name.endsWith(".bin"))name=name.substring(0,name.length()-4);
		if(name.endsWith(".sit")||name.endsWith(".sitx")||name.endsWith(".sea"))return EnumSet.of(Kit.STUFFIT);
		if(name.endsWith(".cpt"))return EnumSet.of(Kit.COMPACT_PRO);
		if(name.endsWith(".lzh")||name.endsWith(".lha"))return EnumSet.of(Kit.MACLHA);
		return EnumSet.allOf(Kit.class);
	}
	/** The format by the first bytes of the data fork (inside a MacBinary wrapper too), or null. */
	private static Kit byData(File archive){
		byte[] b;long length;
		try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(archive,"r")){
			long[] forks=ResourceFile68k.MacBinary.headerForkLengths(archive);
			long start=forks!=null?128:0;length=forks!=null?forks[0]:in.length();
			in.seek(start);b=new byte[(int)Math.min(16,Math.max(0,length))];in.readFully(b);
		}catch(IOException e){return null;}
		if(b.length<14)return null;
		String head=new String(b,0,8,java.nio.charset.StandardCharsets.ISO_8859_1);
		// StuffIt 1.5-4.5: a 4-byte signature ("SIT!", "ST46"...) and "rLau" at 10; StuffIt 5 and StuffIt X begin "StuffIt"
		if(b[10]=='r'&&b[11]=='L'&&b[12]=='a'&&b[13]=='u'||head.startsWith("StuffIt"))return Kit.STUFFIT;
		// an LHA header: size, checksum, then the method "-lh5-" (or "-lz4-", "-lhd-"...)
		if(b[2]=='-'&&b[3]=='l'&&(b[4]=='h'||b[4]=='z')&&b[6]=='-')return Kit.MACLHA;
		// Compact Pro: magic 1, volume 1, then the offset of the catalog inside the archive
		long catalog=(b[4]&0xffL)<<24|(b[5]&0xff)<<16|(b[6]&0xff)<<8|(b[7]&0xff);
		if(b[0]==1&&b[1]==1&&catalog>=8&&catalog<length)return Kit.COMPACT_PRO;
		return null;
	}

	/** The archive's own expander among the known programs - StuffIt Expander for a .sit.hqx even when Compact Pro (which
	 *  claims SIT! too) is known - or null when its format cannot be told, none is known, or several are (the user chooses). */
	static File ownExpander(File archive){
		Set<Kit> kits=kitsFor(archive);
		if(kits.size()!=1)return null;
		Kit kit=kits.iterator().next();File found=null;
		for(File app:DocumentOpeners.knownPrograms(LauncherStore.get()))if(kit.openedBy(app)){if(found!=null)return null;found=app;}
		return found;
	}
	/** Whether the archive's format is told and no known program is its own expander (installing or registering it comes first). */
	static boolean ownExpanderMissing(File archive){
		Set<Kit> kits=kitsFor(archive);
		if(kits.size()!=1)return false;
		Kit kit=kits.iterator().next();
		for(File app:DocumentOpeners.knownPrograms(LauncherStore.get()))if(kit.openedBy(app))return false;
		return true;
	}

	// ------------------------------------------------------------------ installed expanders

	/** Whether any known program opens archives (bundle claims an archive type). */
	static boolean anyExpanderKnown(){
		for(File app:DocumentOpeners.knownPrograms(LauncherStore.get()))if(isExpander(app))return true;
		return false;
	}
	static boolean isExpander(File app){
		FinderIcons68k.Bundle b=FinderIcons68k.bundleOf(app);
		for(int t:DocumentOpeners.ARCHIVE_TYPES)if(b.claims(t))return true;
		return false;
	}

	/**
	 * The expander programs installed so far for these kits: applications under the install folder (the self-extracting
	 * archives leave theirs there) and in the folders at the top of HyperZebra HD (StuffIt's installer puts its own folder there).
	 * Canonical files, like the launcher's lists (/var/... on the Mac is /private/var/...).
	 */
	static List<File> installedExpanders(Set<Kit> kits){
		List<File> r=new ArrayList<File>();
		File install=canonical(installFolder());
		collect(install,0,3,kits,r);
		File disk=canonical(FileSystem68k.bootVolumeDirectory());
		File[] top=disk.listFiles();
		if(top!=null){java.util.Arrays.sort(top);
			for(File f:top)if(f.isDirectory()&&!f.getName().startsWith(".")&&!f.equals(install)&&!f.getName().equals("System Folder")&&!f.getName().equals("Trash"))collect(f,0,1,kits,r);}
		return r;
	}
	private static File canonical(File f){try{return f.getCanonicalFile();}catch(IOException e){return f.getAbsoluteFile();}}
	private static void collect(File folder,int depth,int maxDepth,Set<Kit> kits,List<File> r){
		File[] files=folder.listFiles();
		if(files==null||depth>maxDepth)return;
		java.util.Arrays.sort(files);
		for(File f:files){
			if(f.getName().startsWith("."))continue;
			if(f.isDirectory())collect(f,depth+1,maxDepth,kits,r);
			else if(!isSelfExtracting(f)&&MacApp68k.isApplication(f))for(Kit k:kits)if(k.openedBy(f)){r.add(f);break;}
		}
	}
	/** Whether the program is one of the expanders HyperZebra ships (StuffIt Expander, Compact Pro, MacLHA: told by its
	 *  creator, else its bundle's signature, wherever it sits). They run at host speed unless the user set a speed for them
	 *  (MacApp68k.mips): a slow CPU only makes the user wait for the files. The creator first: StuffIt Expander 5.5 is 'SITx'
	 *  but its BNDL says 'SIT!', as StuffIt Deluxe's does. */
	static boolean shippedExpander(File app){
		int s=FinderInfo68k.creator(app);
		if(s==0||s==0x3F3F3F3F)s=FinderIcons68k.bundleOf(app).signature;
		if(s==0)return false;
		for(Kit k:Kit.values())if(k.signatures[0]==s)return true;
		return false;
	}
	/** Self-extracting archives and installers are programs too, but not expanders to keep. */
	private static boolean isSelfExtracting(File f){
		String n=f.getName().toLowerCase();
		return n.contains(".sea")||n.endsWith(".bin")||n.endsWith(".hqx")||n.contains("installer");
	}
	/** The kits none of the installed programs opens for. */
	static Set<Kit> missing(Set<Kit> kits,List<File> installed){
		Set<Kit> r=EnumSet.noneOf(Kit.class);
		for(Kit k:kits){boolean have=false;for(File app:installed)if(k.openedBy(app)){have=true;break;}if(!have)r.add(k);}
		return r;
	}
	/** The kits whose distributed file is there. */
	static Set<Kit> available(Set<Kit> kits){
		Set<Kit> r=EnumSet.noneOf(Kit.class);
		File source=distribution();
		for(Kit k:kits)if(k.distributedFile(source)!=null)r.add(k);
		return r;
	}
	/** "StuffIt Expander", "StuffIt Expander, Compact Pro, MacLHA": the kits' names for the dialog. */
	static String titles(Set<Kit> kits){
		StringBuilder sb=new StringBuilder();
		for(Kit k:kits){if(sb.length()>0)sb.append(", ");sb.append(k.title);}
		return sb.toString();
	}

	/** Registers the installed expanders for these kits with the launcher (history and dock) and returns them. */
	static List<File> register(Set<Kit> kits){
		List<File> found=installedExpanders(kits);
		LauncherStore store=LauncherStore.get();
		for(File app:found){store.recordOpened(app,LauncherStore.KIND_APP);store.addToDock(app);}
		return found;
	}

	// ------------------------------------------------------------------ installing

	/**
	 * Copies the kits' distributed files into the install folder (BinHex decoded to MacBinary) and returns the programs to run,
	 * in order: the self-extracting archives and installers among the copies.
	 */
	static List<File> prepare(Set<Kit> kits) throws IOException {
		File source=distribution();
		if(source==null)throw new IOException("the ExtractorApps folder is not there");
		File target=installFolder();target.mkdirs();
		List<File> toRun=new ArrayList<File>();
		for(Kit k:kits){
			File f=k.distributedFile(source);
			if(f==null)continue;
			File copy;
			if(BinHex.looksLikeBinHex(f))copy=BinHex.decodeToMacBinary(f,target);
			else{copy=new File(target,f.getName());Files.copy(f.toPath(),copy.toPath(),StandardCopyOption.REPLACE_EXISTING);}
			if(MacApp68k.isApplication(copy))toRun.add(copy);
		}
		return toRun;
	}

	/** Runs the installers without the user, one after another (each launched when the previous finished), then calls back. */
	static void runAll(final List<File> programs,final Runnable whenDone){
		if(programs.isEmpty()){whenDone.run();return;}
		final File first=programs.get(0);
		final List<File> rest=programs.subList(1,programs.size());
		try{
			final MacApp68k app=MacApp68k.launchUnattended(first);
			MacApp68k.addListener(new MacApp68k.Listener(){
				public void applicationLaunched(MacApp68k a){}
				public void applicationFinished(MacApp68k a){if(a!=app)return;MacApp68k.removeListener(this);runAll(rest,whenDone);}
			});
		}catch(IOException e){
			System.err.println("[extractor apps] "+first.getName()+": "+e.getMessage());
			runAll(rest,whenDone);
		}
	}
}

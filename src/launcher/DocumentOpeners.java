import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Which 68K programs can open a document dropped on HyperZebra (the launcher's window, the card window) away from any
 * program's icon: the way the Finder chooses, by the programs' bundles ('FREF' file types) and the document's creator,
 * among the programs the launcher knows (the dock, the history, the programs that opened documents before), the one
 * the user chose for the file type first ("always open this kind of file with…"), then the one that opened this very
 * document, then the creator's own program.
 * <p>
 * Archives (StuffIt, Compact Pro, LHA, BinHex, MacBinary-wrapped ones) are documents like any other: the expander
 * registered for them opens - and expands - them; that is left to the programs.
 */
final class DocumentOpeners {
	private DocumentOpeners(){}

	/** Archive file types: StuffIt (SIT!, SITD, SIT5, SIT2), Compact Pro (PACT), LHA (LHA , LHa ), BinHex ('TEXT' with creator BnHq is caught by name). */
	static final int[] ARCHIVE_TYPES={0x53495421,0x53495444,0x53495435,0x53495432,0x50414354,0x4c484120,0x4c486120};
	private static final String[] ARCHIVE_SUFFIXES={".sit",".sitx",".cpt",".lzh",".lha",".hqx",".sea"};
	private static final int BINHEX_CREATOR=0x426e4871; // BnHq

	/** Whether the file is an archive an expander program opens (by file type, or by name when the host knows no type). */
	static boolean isArchive(File f){
		if(f==null||!f.isFile())return false;
		int type=typeOf(f);
		for(int t:ARCHIVE_TYPES)if(t==type)return true;
		//a HyperCard stack named like an archive ("Maya Calendar 1.71-1.sea", as an expander renames a stack beside its archive)
		if(type==0x5354414b||startsWithStak(f))return false;
		if(isBinHex(f))return true;
		String name=f.getName().toLowerCase();
		if(name.endsWith(".bin"))name=name.substring(0,name.length()-4); // "x.sit.bin": a MacBinary wrapper the host reads through
		for(String s:ARCHIVE_SUFFIXES)if(name.endsWith(s))return true;
		return false;
	}

	/** The data fork starts with a HyperCard stack header (size, then "STAK"). */
	private static boolean startsWithStak(File f){
		try(java.io.InputStream in=new java.io.FileInputStream(f)){
			byte[] b=in.readNBytes(8);
			return b.length==8&&b[4]=='S'&&b[5]=='T'&&b[6]=='A'&&b[7]=='K';
		}catch(java.io.IOException e){return false;}
	}

	/** A BinHex file: 'TEXT' of creator BnHq, or named .hqx. */
	static boolean isBinHex(File f){return f!=null&&f.isFile()&&(FinderInfo68k.creator(f)==BINHEX_CREATOR||f.getName().toLowerCase().endsWith(".hqx"));}

	/** The document's file type (0 when the host knows none and cannot guess one). */
	static int typeOf(File f){return FinderInfo68k.type(f);}

	/**
	 * The programs that can open the document, best first: the one chosen for its file type, the one that opened it
	 * last, the creator's program, then every known program whose bundle claims the type (a program claiming any file
	 * ('****') only when nothing claims the type itself). Programs that no longer exist are left out.
	 */
	static List<File> candidates(File document){return candidates(document,typeOf(document),true);}
	/**
	 * {@code type}: the document's type as the programs are to see it (a ReadMe without Finder info is 'TEXT');
	 * {@code anyType} false leaves out the programs that claim any file ('****': resource editors), which no other fits.
	 */
	static List<File> candidates(File document,int type,boolean anyType){
		List<File> r=new ArrayList<File>();
		if(document==null||!document.isFile())return r;
		int creator=FinderInfo68k.creator(document);
		int[] types=PictureFile68k.isHostImageType(type)?new int[]{type,PictureFile68k.PICT} // programs see a host picture as a PICT
			:isBinHex(document)?new int[]{ARCHIVE_TYPES[0],type}:new int[]{type}; // the StuffIt programs decode BinHex (their bundles claim SIT!), before a text editor
		LauncherStore store=LauncherStore.get();
		add(r,type==0?null:store.openerFor(type));
		add(r,store.openerOf(document));
		List<File> known=knownPrograms(store);
		if(creator!=0&&creator!=0x3F3F3F3F)for(File app:known)if(FinderIcons68k.bundleOf(app).signature==creator)add(r,app);
		for(int t:types)if(t!=0)for(File app:known)if(FinderIcons68k.bundleOf(app).claims(t))add(r,app);
		if(r.isEmpty()&&anyType)for(File app:known)if(FinderIcons68k.bundleOf(app).claims(FinderIcons68k.ANY_TYPE))add(r,app);
		return r;
	}

	/** The one candidate for an archive that expands it (its bundle claims an archive type), null when there is none or
	 *  several (the user chooses then): the launcher hands a .sit.hqx to StuffIt Expander instead of asking between it and every
	 *  text editor (BinHex is 'TEXT'). */
	static File expanderFor(File archive){
		File found=null;
		for(File app:candidates(archive)){FinderIcons68k.Bundle b=FinderIcons68k.bundleOf(app);boolean expands=false;for(int t:ARCHIVE_TYPES)if(b.claims(t))expands=true;
			if(!expands)continue;if(found!=null)return null;found=app;}
		return found;
	}
	/** Whether a known program expands archives: its bundle claims an archive type. A BinHex file is 'TEXT', so text editors are
	 *  candidates for it too - they are no expander (the launcher then offers to install one first). */
	static boolean expanderKnown(){
		for(File app:knownPrograms(LauncherStore.get())){FinderIcons68k.Bundle b=FinderIcons68k.bundleOf(app);for(int t:ARCHIVE_TYPES)if(b.claims(t))return true;}
		return false;
	}

	/**
	 * Whether the program opens the document when it is dropped on the program's icon, the way the Finder decides
	 * whether to highlight a program under a drag: the document's creator is the program's signature, the program's
	 * bundle claims the document's type (or any file, '****'), the user chose the program for this type, or the program
	 * opened this very document before. A document with no known type is opened by none.
	 */
	static boolean opens(File app,File document){
		if(app==null||document==null||!document.isFile())return false;
		FinderIcons68k.Bundle bundle=FinderIcons68k.bundleOf(app);
		int type=typeOf(document),creator=FinderInfo68k.creator(document);
		if(bundle.signature!=0&&creator==bundle.signature)return true;
		if(bundle.claims(FinderIcons68k.ANY_TYPE))return true;
		int[] types=PictureFile68k.isHostImageType(type)?new int[]{type,PictureFile68k.PICT}:isBinHex(document)?new int[]{ARCHIVE_TYPES[0],type}:new int[]{type};
		for(int t:types)if(t!=0&&bundle.claims(t))return true;
		LauncherStore store=LauncherStore.get();
		return same(type==0?null:store.openerFor(type),app)||same(store.openerOf(document),app);
	}
	/**
	 * Whether the program opens documents at all, for a drag whose files are not known yet (Windows, Linux: Java gives the
	 * data only at the drop): its bundle claims a file type besides its own, it opened documents before, or the user
	 * chose it for a kind of file. A game with no document types opens none.
	 */
	static boolean opensDocuments(File app){
		if(app==null||!app.isFile())return false;
		if(FinderIcons68k.bundleOf(app).claimsDocuments())return true;
		LauncherStore store=LauncherStore.get();
		if(!store.documentsOf(app).isEmpty())return true;
		for(File f:store.chosenOpeners())if(same(f,app))return true;
		return false;
	}
	private static boolean same(File a,File b){return a!=null&&b!=null&&a.getAbsoluteFile().equals(b.getAbsoluteFile());}

	/** The program to use without asking: the one chosen for the file type, else the only candidate there is (null otherwise). */
	static File obvious(File document){
		int type=typeOf(document);
		File chosen=type==0?null:LauncherStore.get().openerFor(type);
		if(chosen!=null)return chosen;
		List<File> c=candidates(document);
		return c.size()==1?c.get(0):null;
	}

	/** Every program the launcher knows: the dock, the history and the openers of past documents (each once, existing files only). */
	static List<File> knownPrograms(LauncherStore store){
		List<File> r=new ArrayList<File>();
		for(File f:store.allDockItems())add(r,f);
		for(LauncherStore.Item it:store.recentItems())add(r,it.isApp()?it.file():it.appFile());
		for(File f:store.chosenOpeners())add(r,f);
		return r;
	}
	/** The known programs that open HyperCard stacks: HyperCard itself ('WILD') and the players whose bundles claim 'STAK'. */
	static List<File> stackPrograms(){
		List<File> r=new ArrayList<File>();
		int stak=ResourceFile68k.fourcc("STAK"),wild=ResourceFile68k.fourcc("WILD");
		for(File app:knownPrograms(LauncherStore.get())){FinderIcons68k.Bundle b=FinderIcons68k.bundleOf(app);if(b.signature==wild||b.claims(stak))r.add(app);}
		return r;
	}
	private static void add(List<File> r,File app){
		if(app==null||!app.isFile())return;
		for(File f:r)if(f.getAbsoluteFile().equals(app.getAbsoluteFile()))return;
		if(!MacApp68k.isApplication(app))return;
		r.add(app);
	}
}

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bitmapped fonts installed for hosted programs, as the Fonts folder of a System 7 System Folder: every
 * suitcase in the virtual System Folder's "Fonts" folder (and in the folders named by
 * {@code -Dhz68k.fonts=dir[:dir...]}) is read at startup, its families answer {@link Fonts68k#font} before the
 * host fonts do (at every size: their strikes, their TrueType outlines, or the nearest strike scaled), and its
 * 'FOND'/'NFNT'/'FONT'/'sfnt' resources are visible to programs through the System file. A suitcase dropped on
 * HyperZebra is copied here by {@link #install}.
 * <p>
 * A family is a 'FOND' (name, family number, the size/style table naming its strikes) or, for pre-FOND
 * suitcases, the 'FONT' resources numbered family*128+size. Two-byte glyphs of Japanese families come from the
 * embedded bitmaps of the family's 'sfnt' ({@link SfntBitmaps68k}).
 */
final class InstalledFonts68k {
	static final int FOND=0x464f4e44,NFNT=0x4e464e54,FONT=0x464f4e54,SFNT=0x73666e74,FBIT=0x66626974;
	/** Resource types copied into the System file; 'fbit' is the KanjiTalk two-byte font descriptor (its glyphs live in the sfnt). */
	private static final int[] TYPES={FOND,NFNT,FONT,SFNT,FBIT};
	/** Finder types of the files a Fonts folder holds: suitcases, System 7 single bitmap/TrueType font files, 'fbit' descriptors. */
	private static final int[] FILE_TYPES={ResourceFile68k.fourcc("FFIL"),ResourceFile68k.fourcc("ffil"),ResourceFile68k.fourcc("tfil"),FBIT};
	/** What a suitcase may carry besides fonts (version, Finder bundle and icons, strings); anything else marks a System file, a stack or a program. */
	private static final int[] COMPANION_TYPES={ResourceFile68k.fourcc("vers"),ResourceFile68k.fourcc("FREF"),ResourceFile68k.fourcc("BNDL"),
		ResourceFile68k.fourcc("ICN#"),ResourceFile68k.fourcc("icl4"),ResourceFile68k.fourcc("icl8"),ResourceFile68k.fourcc("ics#"),ResourceFile68k.fourcc("ics4"),ResourceFile68k.fourcc("ics8"),
		ResourceFile68k.fourcc("SICN"),ResourceFile68k.fourcc("STR "),ResourceFile68k.fourcc("STR#"),ResourceFile68k.fourcc("FCMT")};
	private static boolean contains(int[] types,int type){for(int t:types)if(t==type)return true;return false;}

	/** One font family of an installed suitcase. */
	static final class Family {
		final int id;final String name;final File file;final ResourceFile68k resources;
		/** Font association entries {size, style, resource id}; size 0 names the family's 'sfnt'. */
		final List<int[]> association=new ArrayList<int[]>();
		private SfntBitmaps68k sfnt;private boolean sfntParsed;
		Family(int id,String name,File file,ResourceFile68k resources){this.id=id;this.name=name;this.file=file;this.resources=resources;}
		/** The point sizes with a bitmap strike (NFNT/FONT sizes whose resource exists, then the sfnt's embedded sizes). */
		List<Integer> sizes(){
			List<Integer> sizes=new ArrayList<Integer>();
			for(int[] a:association)if(a[0]>0&&!sizes.contains(a[0])&&strikeData(a[2])!=null)sizes.add(a[0]);
			SfntBitmaps68k s=sfnt();if(s!=null)for(int size:s.sizes())if(!sizes.contains(size))sizes.add(size);
			Collections.sort(sizes);return sizes;
		}
		/** Whether the family has TrueType outlines (every size is real, as with System 7's TrueType Chicago). */
		boolean hasOutlines(){SfntBitmaps68k s=sfnt();return s!=null&&s.outlines()!=null;}
		/** The family associates an 'sfnt' (a size-0 entry): a TrueType family for AppendResMenu 'sfnt'. */
		boolean hasSfnt(){for(int[] a:association)if(a[0]==0&&resources.get(SFNT,a[2])!=null)return true;return false;}
		private byte[] strikeData(int strikeId){
			if(strikeId==0)return null;
			ResourceFile68k.Resource r=resources.get(NFNT,strikeId);if(r==null)r=resources.get(FONT,strikeId);
			return r==null?null:r.data();
		}
		synchronized SfntBitmaps68k sfnt(){
			if(!sfntParsed){sfntParsed=true;
				for(int[] a:association)if(a[0]==0){ResourceFile68k.Resource r=resources.get(SFNT,a[2]);if(r!=null){sfnt=SfntBitmaps68k.parse(r.data());if(sfnt!=null)break;}}}
			return sfnt;
		}
		/**
		 * The font of a size and face, as the Font Manager builds it: the exact style's NFNT, else the plain one with
		 * the style synthesised; a size without a strike takes the family's outlines, else the nearest strike scaled
		 * ({@link Fonts68k#scaleDisabled}: its glyphs at the requested spacing); null when the family has no glyphs at all.
		 */
		BitmapFont68k strike(int size,int face){return strike(size,face,true);}
		private BitmapFont68k strike(int size,int face,boolean scale){
			int exact=0,exactStyle=0,plain=0;
			for(int[] a:association){if(a[0]!=size)continue;if(a[1]==(face&7)){exact=a[2];exactStyle=a[1];}else if(a[1]==0)plain=a[2];}
			BitmapFont68k font=BitmapFont68k.parse(id,size,exact!=0?face&~exactStyle:face,strikeData(exact!=0?exact:plain),sfnt());
			if(font!=null)return font;
			// a classic Roman family the System has as TrueType, which the host font stands in for: a suitcase holding only some
			// of its strikes does not make the other sizes scaled bitmaps ("Equation Editor Fonts" carries Times 7 plain and
			// italics: Times 14 came out a blown-up 7 and RealFont said no - DOCMaker's "fonts not available: Times 14")
			if(scale&&!hasOutlines()&&id>=0&&id<Fonts68k.JAPANESE_SYSTEM_FONT&&Fonts68k.NAMES.containsKey(id))return null;
			int from=scale?BitmapFont68k.nearestSize(sizes(),size):0;
			return from==0?null:BitmapFont68k.scaled(strike(from,face,false),size,Fonts68k.scaleDisabled);
		}
		@Override public String toString(){return name+" ("+id+") "+sizes()+" from "+file.getName();}
	}

	private static volatile List<Family> families;
	private static final Map<String,Object> strikes=new ConcurrentHashMap<String,Object>();
	private static final Object NO_STRIKE=new Object();
	private InstalledFonts68k(){}

	/** The Fonts folder of the virtual System Folder (the same one FileSystem68k hands to programs asking for kFontsFolderType). */
	static File folder(){
		File home=new File(System.getProperty("user.home"));
		return new File(new File(new File(home,"Library"+File.separator+"Application Support"+File.separator+"HyperZebra"),"System Folder"),"Fonts");
	}
	/** The folders scanned: the System Folder's Fonts folder, then any named by -Dhz68k.fonts. */
	static List<File> folders(){
		List<File> folders=new ArrayList<File>();folders.add(folder());
		String extra=System.getProperty("hz68k.fonts");
		if(extra!=null)for(String path:extra.split(File.pathSeparator))if(!path.trim().isEmpty())folders.add(new File(path.trim()));
		return folders;
	}

	static List<Family> families(){List<Family> f=families;if(f==null){reload();f=families;}return f;}
	/** Re-reads the folders (after an install, or when a test points -Dhz68k.fonts elsewhere). */
	static synchronized void reload(){
		List<Family> found=new ArrayList<Family>();
		for(File folder:folders()){
			File[] files=folder.listFiles();if(files==null)continue;
			java.util.Arrays.sort(files,java.util.Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
			for(File file:files){
				if(!file.isFile()||file.getName().startsWith(".")||file.getName().endsWith(".DS_Store"))continue;
				if(!isFontFile(file)){System.err.println("[68K] "+file.getName()+" in "+folder.getName()+" is not a font suitcase - ignored");continue;}
				try{read(file,found);}
				catch(Exception e){System.err.println("[68K] font file "+file.getName()+" skipped: "+e.getMessage());}
			}
		}
		families=Collections.unmodifiableList(found);strikes.clear();
		if(Mac68kApi.TRACE)for(Family f:found)System.err.println("[68K] installed font "+f);
	}
	private static void read(File file,List<Family> into) throws IOException {
		ResourceFile68k rf=ResourceFile68k.open(file);
		for(ResourceFile68k.Resource fond:rf.ofType(FOND)){
			byte[] d=fond.data();if(d.length<54)continue;
			Family family=new Family(fond.id,fond.name==null?"":fond.name,file,rf);
			int n=(short)MacApp68k.u2(d,52)+1;
			for(int i=0;i<n&&54+i*6+5<d.length;i++)family.association.add(new int[]{MacApp68k.u2(d,54+i*6),MacApp68k.u2(d,56+i*6),(short)MacApp68k.u2(d,58+i*6)});
			if(!family.association.isEmpty())add(into,family);
		}
		// pre-FOND suitcases: FONT family*128 carries the name, FONT family*128+size the strikes
		for(ResourceFile68k.Resource r:rf.ofType(FONT)){
			if(r.id<0||r.id%128!=0||r.name==null||r.name.isEmpty())continue;
			int id=r.id/128;if(family(into,id)!=null||familyNamed(into,r.name)!=null)continue;
			Family family=new Family(id,r.name,file,rf);
			for(ResourceFile68k.Resource s:rf.ofType(FONT))if(s.id/128==id&&s.id%128!=0)family.association.add(new int[]{s.id%128,0,s.id});
			if(!family.association.isEmpty())add(into,family);
		}
	}
	/**
	 * Adds a family unless one of the same number or name is already listed - except that a listed family without a
	 * single usable strike or outline (a System file whose FOND points at strikes that were moved out) gives way.
	 */
	private static void add(List<Family> into,Family family){
		Family same=family(into,family.id);if(same==null)same=familyNamed(into,family.name);
		if(same==null){into.add(family);return;}
		if(same.sizes().isEmpty()&&!same.hasOutlines()&&!(family.sizes().isEmpty()&&!family.hasOutlines()))into.set(into.indexOf(same),family);
	}
	private static Family family(List<Family> list,int id){for(Family f:list)if(f.id==id)return f;return null;}
	private static Family familyNamed(List<Family> list,String name){if(name==null)return null;for(Family f:list)if(f.name.equalsIgnoreCase(name))return f;return null;}
	static Family family(int id){return family(families(),id);}
	static Family familyNamed(String name){return familyNamed(families(),name);}

	/**
	 * The installed strike for a classic font number, size and face, or null: the family with that number, else the
	 * family named like it (Chicago is font 0 whatever number its suitcase carries; on a Japanese system 0/1/3 are Osaka).
	 */
	static BitmapFont68k strike(int id,int size,int face){
		if(families().isEmpty())return null;
		String key=id+"/"+size+"/"+(face&0x7f)+"/"+(Fonts68k.japaneseSystem?"J":"R")+(Fonts68k.scaleDisabled?"u":"s");
		Object cached=strikes.get(key);
		if(cached==null){
			Family family=family(id);
			if(family==null&&Fonts68k.japaneseSystem&&(id==0||id==1||id==3))family=familyNamed(Fonts68k.nameOf(Fonts68k.JAPANESE_SYSTEM_FONT));
			if(family==null){String name=Fonts68k.NAMES.get(id==3?1:id);if(name!=null)family=familyNamed(name);}
			BitmapFont68k strike=null;
			if(family!=null)try{strike=family.strike(size,face&0x7f);}catch(RuntimeException e){strike=null;}
			if(Mac68kApi.TRACE&&strike!=null)System.err.println("[68K] installed font "+id+" size "+size+" face "+face+" -> "+strike);
			cached=strike==null?NO_STRIKE:strike;strikes.put(key,cached);
		}
		return cached==NO_STRIKE?null:(BitmapFont68k)cached;
	}

	/** Copies of the installed font resources for the synthetic System file (first suitcase wins a type/ID clash). */
	static List<ResourceFile68k.Resource> systemResources(){
		List<ResourceFile68k.Resource> result=new ArrayList<ResourceFile68k.Resource>();List<ResourceFile68k> seen=new ArrayList<ResourceFile68k>();
		for(Family f:families()){
			if(seen.contains(f.resources))continue;seen.add(f.resources);
			for(int type:TYPES)for(ResourceFile68k.Resource r:f.resources.ofType(type))result.add(new ResourceFile68k.Resource(type,r.id,r.name,r.attributes&~ResourceFile68k.RES_CHANGED,r.data()));
		}
		return result;
	}

	/**
	 * Whether a host file is a font suitcase: what a drop on HyperZebra installs and what the Fonts folders yield.
	 * Decided like the Finder, by file type ('FFIL', 'ffil', 'tfil', 'fbit') when the host knows one - a System
	 * file ('zsys'), a stack or a program carries FONT resources without being a suitcase - and for a file without
	 * Finder info by its contents: font resources with nothing beyond a suitcase's version/bundle/icon bookkeeping.
	 */
	static boolean isFontFile(File file){
		if(file==null||!file.isFile())return false;
		int fileType=FinderInfo68k.type(file);
		if(fileType!=0&&!contains(FILE_TYPES,fileType))return false;
		try{ResourceFile68k rf=ResourceFile68k.open(file);
			boolean fonts=false;
			for(int type:rf.types()){
				if(contains(TYPES,type))fonts=true;
				else if(fileType==0&&!contains(COMPANION_TYPES,type))return false;
			}
			return fonts;
		}catch(IOException e){return false;}
	}

	/**
	 * Copies a suitcase into the Fonts folder (data fork, then the resource fork the way this platform stores forks;
	 * MacBinary containers are copied whole) and re-reads the folders. Returns the names of the families it holds
	 * (the file name for a companion file without families, such as an 'fbit' descriptor).
	 */
	static List<String> install(File source) throws IOException {
		File folder=folder();folder.mkdirs();
		File target=new File(folder,source.getName());
		if(!target.getAbsoluteFile().equals(source.getAbsoluteFile())){
			ResourceFile68k probe=new ResourceFile68k(source);byte[] fork=ResourceFile68k.readForkBytes(source,probe);
			if(fork==null)throw new IOException("no resource fork: "+source.getName());
			if(probe.storage==ResourceFile68k.Storage.MACBINARY)Files.copy(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
			else{Files.copy(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);ResourceFile68k.writeForkBytes(target,fork,ResourceFile68k.Storage.NONE);}
		}
		reload();
		List<String> names=new ArrayList<String>();
		for(Family f:families())if(f.file.getAbsoluteFile().equals(target.getAbsoluteFile()))names.add(f.name);
		if(names.isEmpty())names.add(target.getName());
		return names;
	}
}

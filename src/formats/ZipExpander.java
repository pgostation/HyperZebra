import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Expands a ZIP archive so that its Macintosh files come out whole on any host.
 * The Finder's "Compress" stores each file's resource fork and Finder info as an
 * AppleDouble entry under "__MACOSX/" (mirroring the folder tree, "._name" per
 * file); Windows and Linux extractors leave that tree behind as a plain folder.
 * Here every such entry becomes the file's own sidecar "._name" beside it
 * (or, on macOS with native forks, the real resource fork and Finder info), so
 * the stack loaders, the 68K Resource Manager and File Manager find it where
 * they look. HyperZebra's own portable stack packages use the same layout.
 * Info-ZIP's MacZip (and the Mac archivers built on it) keeps a resource fork as
 * its own entry under "XtraStuf.mac/" (mirroring the path) and the type and
 * creator in each entry's Macintosh extra field (0x334d): those are applied the
 * same way.
 */
final class ZipExpander {
	private ZipExpander(){}

	static final class Result {
		/** The folder the entries were written into. */
		final File base;
		/** What to open: the archive's single top-level item, or {@link #base} when it holds several. */
		final File opened;
		/** Number of resource forks / Finder infos applied from __MACOSX entries. */
		final int sidecars;
		Result(File base,File opened,int sidecars){this.base=base;this.opened=opened;this.sidecars=sidecars;}
	}

	private static final String MACOSX="__MACOSX/";
	/** MacZip's folder of resource forks: "XtraStuf.mac/Folder/file" is the fork of "Folder/file". */
	private static final String XTRASTUF="XtraStuf.mac/";

	/** Whether a name looks like a ZIP archive. */
	static boolean isZip(File file){return file.isFile()&&file.getName().toLowerCase().endsWith(".zip");}

	/** Expands {@code zip} beside itself (into a folder named after it unless it holds a single item that does not exist yet). */
	static Result expand(File zip) throws IOException {return expand(zip,zip.getAbsoluteFile().getParentFile());}

	static Result expand(File zip,File destination) throws IOException {
		try(ZipFile archive=openZip(zip)){
			// top-level items decide where the archive lands (the Finder zips a folder as "Folder/…", a file as "file")
			Set<String> topLevel=new LinkedHashSet<String>();
			for(Enumeration<? extends ZipEntry> e=archive.entries();e.hasMoreElements();){
				String name=normalize(e.nextElement().getName());
				if(name==null||name.startsWith(MACOSX)||name.startsWith(XTRASTUF)||isJunk(name))continue;
				int slash=name.indexOf('/');topLevel.add(slash<0?name:name.substring(0,slash));
			}
			if(topLevel.isEmpty())throw new IOException("The archive is empty.");
			File base,opened;
			String single=topLevel.size()==1?topLevel.iterator().next():null;
			if(single!=null&&!new File(destination,single).exists()){base=destination;opened=new File(destination,single);}
			else{
				String stem=zip.getName();stem=stem.substring(0,stem.length()-4);if(stem.isEmpty())stem="Archive";
				base=new File(destination,stem);for(int i=2;base.exists();i++)base=new File(destination,stem+" "+i);
				if(!base.mkdirs())throw new IOException("Can't create the folder "+base.getPath());
				opened=single!=null?new File(base,single):base;
			}
			Path root=base.getAbsoluteFile().toPath().normalize();
			List<Object[]> sidecars=new ArrayList<Object[]>();
			java.util.Map<File,byte[][]> macZip=new java.util.LinkedHashMap<File,byte[][]>(); // file -> {resource fork, type+creator}
			for(Enumeration<? extends ZipEntry> e=archive.entries();e.hasMoreElements();){
				ZipEntry entry=e.nextElement();String name=normalize(entry.getName());
				if(name==null||isJunk(name))continue;
				boolean metadata=name.startsWith(MACOSX),xtraStuf=name.startsWith(XTRASTUF);
				if(metadata)name=name.substring(MACOSX.length());
				if(xtraStuf)name=name.substring(XTRASTUF.length());
				if(name.isEmpty())continue;
				File target=new File(base,name.replace('/',File.separatorChar));
				if(!target.getAbsoluteFile().toPath().normalize().startsWith(root))throw new IOException("The archive contains an unsafe path: "+entry.getName());
				byte[] typeCreator=macZipTypeCreator(entry.getExtra());
				if(!metadata&&(xtraStuf||typeCreator!=null)){
					byte[][] mac=macZip.computeIfAbsent(target,k->new byte[2][]);
					if(typeCreator!=null&&(mac[1]==null||!xtraStuf))mac[1]=typeCreator;
					if(xtraStuf){if(!entry.isDirectory())mac[0]=readEntry(archive,entry);continue;}
				}
				if(metadata){
					if(entry.isDirectory())continue;
					String leaf=target.getName();
					if(!leaf.startsWith("._"))continue;
					sidecars.add(new Object[]{new File(target.getParentFile(),leaf.substring(2)),readEntry(archive,entry)});
					continue;
				}
				if(entry.isDirectory()){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("Can't create the folder "+target.getPath());continue;}
				File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Can't create the folder "+parent.getPath());
				try(InputStream in=archive.getInputStream(entry)){Files.copy(in,target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
				if(entry.getTime()>0)target.setLastModified(entry.getTime());
			}
			int applied=0;
			for(java.util.Map.Entry<File,byte[][]> mac:macZip.entrySet()){
				byte[] fork=mac.getValue()[0],typeCreator=mac.getValue()[1];
				if(!mac.getKey().isFile()||(fork==null&&typeCreator==null))continue;
				byte[] info=new byte[32];if(typeCreator!=null)System.arraycopy(typeCreator,0,info,0,8);
				if(applySidecar(mac.getKey(),ResourceFile68k.AppleDouble.build(fork!=null?fork:new byte[0],info)))applied++;
			}
			for(Object[] sidecar:sidecars)if(applySidecar((File)sidecar[0],(byte[])sidecar[1]))applied++;
			// Finder archives commonly contain one folder whose contents are the
			// actual classic application. Return that application so a drop onto
			// HyperZebra or a hosted 68K window launches it directly.
			File document=opened;
			if(opened.isDirectory()){
				File nested=findApplication(opened,3);
				if(nested!=null)document=nested;
			}
			return new Result(base,document,applied);
		}
	}
	private static File findApplication(File directory,int depth){
		if(depth<0)return null;
		File[] children=directory.listFiles();if(children==null)return null;
		java.util.Arrays.sort(children,(a,b)->a.getName().compareToIgnoreCase(b.getName()));
		for(File child:children)if(child.isFile()&&MacApp68k.isApplication(child))return child;
		if(depth==0)return null;
		for(File child:children)if(child.isDirectory()&&!child.getName().equals("__MACOSX")){File found=findApplication(child,depth-1);if(found!=null)return found;}
		return null;
	}

	/**
	 * The type and creator in an entry's Info-ZIP Macintosh extra field (header 0x334d: size, attribute length 4,
	 * flags 2, fdType 4, fdCreator 4, …), or null without one (or with an empty type, as MacZip writes for folders).
	 */
	static byte[] macZipTypeCreator(byte[] extra){
		if(extra==null)return null;
		for(int i=0;i+4<=extra.length;){
			int id=(extra[i]&0xff)|(extra[i+1]&0xff)<<8,size=(extra[i+2]&0xff)|(extra[i+3]&0xff)<<8;
			if(id==0x334d&&size>=14&&i+4+14<=extra.length){
				byte[] typeCreator=java.util.Arrays.copyOfRange(extra,i+4+6,i+4+14);
				return isZero(typeCreator)?null:typeCreator;
			}
			i+=4+size;
		}
		return null;
	}

	/** Gives {@code file} the fork and Finder info held in an AppleDouble entry: natively on macOS, else as its "._" sidecar. */
	static boolean applySidecar(File file,byte[] appleDouble){
		if(appleDouble==null||appleDouble.length<26)return false;
		byte[] fork=HCResource.extractResourceForkFromAppleDouble(appleDouble),finderInfo=ResourceFile68k.AppleDouble.finderInfo(appleDouble);
		if(fork==null&&finderInfo==null)return false;
		if(ResourceFile68k.nativeForks()&&file.isFile()){
			boolean ok=true;
			if(fork!=null&&fork.length>0){
				try{Files.write(new File(file.getPath()+File.separator+"..namedfork"+File.separator+"rsrc").toPath(),fork);}
				catch(IOException e){ok=false;}
			}
			if(ok&&finderInfo!=null&&(finderInfo[0]!=0||finderInfo[4]!=0)){byte[] info=new byte[16];System.arraycopy(finderInfo,0,info,0,16);FinderInfo68k.write(file,info);}
			if(ok)return true;
		}
		if(!file.exists())return false; // a sidecar without its file is dropped rather than left as an orphan
		if(file.isDirectory()&&(fork==null||fork.length==0)&&(finderInfo==null||isZero(finderInfo)))return false;
		try{Files.write(ResourceFile68k.AppleDouble.sidecarOf(file).toPath(),appleDouble);return true;}
		catch(IOException e){return false;}
	}

	private static boolean isZero(byte[] b){for(byte v:b)if(v!=0)return false;return true;}

	/** UTF-8 (the Finder, modern tools), else the Japanese Windows code page, else CP437 - whichever decodes every name. */
	private static ZipFile openZip(File zip) throws IOException {
		IOException failure=null;
		for(Charset charset:candidateCharsets()){
			ZipFile archive=null;
			try{archive=new ZipFile(zip,charset);for(Enumeration<? extends ZipEntry> e=archive.entries();e.hasMoreElements();)e.nextElement().getName();return archive;}
			catch(IllegalArgumentException|IOException e){if(archive!=null)archive.close();failure=e instanceof IOException?(IOException)e:new IOException(e.getMessage(),e);}
		}
		throw failure!=null?failure:new IOException("Can't read the archive.");
	}
	private static List<Charset> candidateCharsets(){
		List<Charset> list=new ArrayList<Charset>();list.add(StandardCharsets.UTF_8);
		for(String name:new String[]{"windows-31j","IBM437"})if(Charset.isSupported(name))list.add(Charset.forName(name));
		return list;
	}

	private static byte[] readEntry(ZipFile archive,ZipEntry entry) throws IOException {try(InputStream in=archive.getInputStream(entry)){return in.readAllBytes();}}

	/** Forward slashes, no leading "/" or "./", or null for names that leave the archive. */
	private static String normalize(String name){
		String n=name.replace('\\','/');
		while(n.startsWith("/")||n.startsWith("./"))n=n.substring(n.startsWith("/")?1:2);
		if(n.isEmpty()||n.startsWith("../")||n.contains("/../")||n.endsWith("/..")||n.equals(".."))return null;
		return n;
	}
	/** Finder droppings that carry nothing a stack or program needs. */
	private static boolean isJunk(String name){
		if(name.contains("Icon\r Resources/")||name.contains("._Icon\r Resources/"))return true;
		String leaf=name.endsWith("/")?name.substring(0,name.length()-1):name;int slash=leaf.lastIndexOf('/');leaf=slash<0?leaf:leaf.substring(slash+1);
		return leaf.equals(".DS_Store")||leaf.equals("._.DS_Store")||leaf.equals("Thumbs.db")||leaf.equals("desktop.ini")||leaf.equals("Icon\r")||leaf.equals("._Icon\r")||leaf.equals("Icon\r Resources")||leaf.equals("._Icon\r Resources");
	}
}

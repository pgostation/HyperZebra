import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.Normalizer;

/**
 * Reads the Mac side of files that Apple's PC Exchange kept on a DOS (FAT) disk: a folder's "FINDER.DAT" holds one 92-byte
 * record per file - the Mac long name (a Pascal string in 32 bytes), FInfo and FXInfo (16 bytes each), dates, then the
 * DOS 8.3 name (11 bytes, space padded) - and its "RESOURCE.FRK" folder holds each file's resource fork under the DOS name.
 * A floppy copied this way (or such a folder kept in an archive or a git repository) has stacks whose icons, sounds and
 * externals are only there. Read only: a fork or Finder info written later goes where the host keeps its own.
 */
final class PcExchange {
	private PcExchange(){}

	static final int RECORD=92;

	/** The resource fork PC Exchange kept for {@code file}, or null when its folder has none for it. */
	static byte[] resourceFork(File file){
		File fork=forkFile(file);
		if(fork==null)return null;
		try{byte[] data=Files.readAllBytes(fork.toPath());return data.length>=16?data:null;}catch(IOException e){return null;}
	}

	/** The 16 bytes of FInfo PC Exchange recorded for {@code file}, or null. */
	static byte[] finderInfo(File file){
		byte[] record=record(file);
		if(record==null)return null;
		byte[] info=java.util.Arrays.copyOfRange(record,32,48);
		return info[0]!=0||info[4]!=0?info:null;
	}

	/** RESOURCE.FRK/&lt;DOS name&gt; for {@code file}, or null. */
	static File forkFile(File file){
		if(file==null||!file.isFile())return null;
		File folder=file.getAbsoluteFile().getParentFile();
		File forks=folder==null?null:child(folder,"RESOURCE.FRK");
		if(forks==null||!forks.isDirectory())return null;
		byte[] record=record(file);
		String dosName=record!=null?dosName(record):file.getName(); // without FINDER.DAT the file keeps its DOS name
		File fork=child(forks,dosName);
		return fork!=null&&fork.isFile()?fork:null;
	}

	/** The FINDER.DAT record of {@code file} (matched by its Mac long name or its DOS name), or null. */
	static byte[] record(File file){
		if(file==null)return null;
		File folder=file.getAbsoluteFile().getParentFile();
		File finderDat=folder==null?null:child(folder,"FINDER.DAT");
		if(finderDat==null||!finderDat.isFile()||finderDat.length()<RECORD||finderDat.length()>1<<20)return null;
		byte[] all;
		try{all=Files.readAllBytes(finderDat.toPath());}catch(IOException e){return null;}
		String name=Normalizer.normalize(file.getName(),Normalizer.Form.NFC);
		for(int at=0;at+RECORD<=all.length;at+=RECORD){
			byte[] record=java.util.Arrays.copyOfRange(all,at,at+RECORD);
			if(dosName(record).equalsIgnoreCase(name))return record;
			int length=record[0]&0xff;
			if(length<1||length>31)continue;
			byte[] longName=java.util.Arrays.copyOfRange(record,1,1+length);
			for(StackTextEncoding encoding:new StackTextEncoding[]{StackTextEncoding.ROMAN,StackTextEncoding.JAPANESE})
				if(Normalizer.normalize(encoding.decode(longName),Normalizer.Form.NFC).replace('/',':').equals(name))return record;
		}
		return null;
	}

	/** "HYPERM~1.5" from the record's space-padded 8+3 name. */
	static String dosName(byte[] record){
		String base=new String(record,80,8,java.nio.charset.StandardCharsets.ISO_8859_1).trim();
		String extension=new String(record,88,3,java.nio.charset.StandardCharsets.ISO_8859_1).trim();
		return extension.isEmpty()?base:base+"."+extension;
	}

	/** {@code name} in {@code folder}, matched without regard to case as on a FAT disk, or null. */
	private static File child(File folder,String name){
		if(name.isEmpty())return null;
		File exact=new File(folder,name);
		if(exact.exists())return exact;
		String[] names=listing(folder);
		if(names!=null)for(String n:names)if(n.equalsIgnoreCase(name))return new File(folder,n);
		return null;
	}

	/** The names in {@code folder}, kept for a second: a catalog walk (PBCatSearch over a volume) asks for FINDER.DAT and RESOURCE.FRK
	 *  of every file in a folder, and listing the folder each time made a search of a few thousand files take half a minute. */
	private static File listedFolder;private static String[] listedNames;private static long listedAt;
	private static synchronized String[] listing(File folder){
		long now=System.currentTimeMillis();
		if(folder.equals(listedFolder)&&now-listedAt<1000)return listedNames;
		String[] names=folder.list();
		listedFolder=folder;listedNames=names;listedAt=now;
		return names;
	}
}

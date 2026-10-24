import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a real dual-fork Macintosh file: the native resource fork on macOS, and
 * elsewhere (Windows, Linux, or a volume without fork support) an AppleDouble
 * "._name" sidecar next to the data fork, which is what macOS itself writes on
 * such volumes and what the loaders here read back. A ".zip" destination packs
 * both into a ZIP with the __MACOSX/._name companion instead.
 */
final class HCStackFileWriter {
	static final class Result {
		final File file;
		final boolean zip;
		/** The AppleDouble sidecar written next to {@link #file}, or null for a native fork or ZIP. */
		final File sidecar;
		Result(File file,boolean zip){this(file,zip,null);}
		Result(File file,boolean zip,File sidecar){this.file=file;this.zip=zip;this.sidecar=sidecar;}
	}

	static Result write(File requested,byte[] dataFork,byte[] resourceFork)throws IOException{
		File absolute=requested.getAbsoluteFile(),parent=absolute.getParentFile();
		if(parent==null||(!parent.exists()&&!parent.mkdirs()))throw new IOException("The destination folder does not exist.");
		if(absolute.getName().toLowerCase().endsWith(".zip")){
			String stackName=absolute.getName().substring(0,absolute.getName().length()-4);
			if(stackName.isEmpty())stackName="HyperCard Stack";
			writeZip(absolute,stackName,dataFork,resourceFork);
			return new Result(absolute,true);
		}
		File temporary=File.createTempFile("hyperzebra-stack-",".tmp",parent);
		try{
			Files.write(temporary.toPath(),dataFork);
			if(ResourceFile68k.nativeForks()&&writeMacMetadata(temporary,resourceFork)){
				move(temporary.toPath(),absolute.toPath());
				Files.deleteIfExists(sidecarFor(absolute).toPath()); // a stale sidecar would shadow nothing on macOS, but confuses copies to other hosts
				return new Result(absolute,false);
			}
			// No native forks here: data fork file + AppleDouble sidecar, written first so a reader never sees a stack without resources.
			File sidecar=sidecarFor(absolute),sidecarTemporary=File.createTempFile("hyperzebra-stack-","._tmp",parent);
			try{
				Files.write(sidecarTemporary.toPath(),ResourceFile68k.AppleDouble.build(resourceFork,finderInfo()));
				move(sidecarTemporary.toPath(),sidecar.toPath());
			}finally{Files.deleteIfExists(sidecarTemporary.toPath());}
			move(temporary.toPath(),absolute.toPath());
			return new Result(absolute,false,sidecar);
		}finally{Files.deleteIfExists(temporary.toPath());}
	}

	/** The AppleDouble companion of a file: "._" + name in the same folder. */
	static File sidecarFor(File file){File absolute=file.getAbsoluteFile();return new File(absolute.getParentFile(),"._"+absolute.getName());}

	private static boolean writeMacMetadata(File file,byte[] resourceFork){
		try{
			Path resource=Path.of(file.getPath(),"..namedfork","rsrc");
			Files.write(resource,resourceFork);
			if(!java.util.Arrays.equals(resourceFork,Files.readAllBytes(resource)))return false;
			File xattr=new File("/usr/bin/xattr");
			if(!xattr.canExecute())return false;
			String hex=toHex(finderInfo());
			Process write=new ProcessBuilder(xattr.getPath(),"-wx","com.apple.FinderInfo",hex,file.getPath())
				.redirectErrorStream(true).start();
			if(write.waitFor()!=0)return false;
			Process read=new ProcessBuilder(xattr.getPath(),"-px","com.apple.FinderInfo",file.getPath())
				.redirectErrorStream(true).start();
			String actual=new String(read.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII)
				.replaceAll("\\s","").toUpperCase();
			return read.waitFor()==0&&actual.startsWith(hex.substring(0,16));
		}catch(Exception e){return false;}
	}

	private static String toHex(byte[] bytes){StringBuilder text=new StringBuilder(bytes.length*2);for(byte value:bytes)text.append(String.format("%02X",value&255));return text.toString();}

	static byte[] finderInfo(){byte[] info=new byte[32];byte[] codes={'S','T','A','K','W','I','L','D'};System.arraycopy(codes,0,info,0,codes.length);return info;}

	private static void writeZip(File destination,String stackName,byte[] dataFork,byte[] resourceFork)throws IOException{
		File parent=destination.getAbsoluteFile().getParentFile();File temp=File.createTempFile("hyperzebra-stack-",".zip",parent);
		boolean moved=false;
		try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temp.toPath()))){
			zip.putNextEntry(new ZipEntry(stackName));zip.write(dataFork);zip.closeEntry();
			zip.putNextEntry(new ZipEntry("__MACOSX/._"+stackName));zip.write(HCResourceExport.appleDouble(resourceFork));zip.closeEntry();
		}
		try{move(temp.toPath(),destination.toPath());moved=true;}finally{if(!moved)Files.deleteIfExists(temp.toPath());}
	}

	private static void move(Path source,Path destination)throws IOException{
		try{Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
		catch(AtomicMoveNotSupportedException e){Files.move(source,destination,StandardCopyOption.REPLACE_EXISTING);}
	}
}

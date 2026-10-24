import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes AppleDouble "._name" sidecars for files that keep their resource fork and
 * Finder info in the macOS file system (the Tools menu of the launcher, macOS only).
 *
 * A file copied from a Mac to Windows or Linux by scp, a web upload, a cloud drive
 * or a plain zip loses its resource fork: those hosts have no forks, and only the
 * Finder's own copies to SMB/FAT volumes and its Compress command carry the fork
 * along (as "._name" files or a "__MACOSX" folder). HyperZebra on those hosts reads
 * the fork from a "._name" sidecar beside the file ({@link ResourceFile68k.AppleDouble}),
 * so this converter makes that sidecar in advance from the native fork and the
 * com.apple.FinderInfo attribute, for one file or every file under a folder. The
 * originals are not touched; the sidecars travel with them.
 */
final class AppleDoubleConverter {
	private AppleDoubleConverter(){}

	/** What a run did: sidecars written, sidecars already up to date, files with nothing to carry, and failures with their reasons. */
	static final class Report {
		int written,unchanged,skipped,failed;
		final List<File> sidecars=new ArrayList<File>();
		final List<String> errors=new ArrayList<String>();
		int seen(){return written+unchanged+skipped+failed;}
	}

	/** Told about each file as it is examined (from the converting thread). */
	interface Progress {void examined(File file,Report report);}

	/** Converts every target: a file gets its sidecar, a folder is walked (its own Finder info included). */
	static Report convert(List<File> targets,Progress progress){
		Report report=new Report();
		for(File target:targets)if(target!=null)walk(target,report,progress);
		return report;
	}

	private static void walk(File file,Report report,Progress progress){
		String name=file.getName();
		if(name.startsWith("._")||name.equals(".DS_Store")||name.equals("__MACOSX"))return;
		if(Files.isSymbolicLink(file.toPath()))return;
		if(file.isDirectory()){
			convertOne(file,report); // a folder's Finder info: custom icon, label, invisible flag
			if(progress!=null)progress.examined(file,report);
			File[] children=file.listFiles();
			if(children==null){report.failed++;report.errors.add(file.getPath()+": "+text("cannot list the folder"));return;}
			Arrays.sort(children);
			for(File child:children)walk(child,report,progress);
			return;
		}
		if(!file.isFile())return;
		convertOne(file,report);
		if(progress!=null)progress.examined(file,report);
	}

	/** Writes (or refreshes) the sidecar of one file or folder and accounts for it in the report; the sidecar written, or null. */
	static File convertOne(File file,Report report){
		try{
			byte[] fork=file.isFile()?nativeFork(file):new byte[0];
			byte[] info=FinderInfo68k.nativeFinderInfo(file);
			if(info!=null&&allZero(info))info=null;
			if(fork.length==0&&info==null){report.skipped++;return null;}
			byte[] sidecarBytes=ResourceFile68k.AppleDouble.build(fork,info);
			File sidecar=ResourceFile68k.AppleDouble.sidecarOf(file);
			if(sidecar.isFile()&&sidecar.length()==sidecarBytes.length&&Arrays.equals(Files.readAllBytes(sidecar.toPath()),sidecarBytes)){report.unchanged++;return sidecar;}
			Files.write(sidecar.toPath(),sidecarBytes);
			report.written++;report.sidecars.add(sidecar);
			return sidecar;
		}catch(IOException e){
			report.failed++;report.errors.add(file.getPath()+": "+(e.getMessage()==null?e.toString():e.getMessage()));
			return null;
		}
	}

	/** The bytes of a file's native resource fork (empty without one). */
	static byte[] nativeFork(File file) throws IOException {
		File nativeFork=ResourceFile68k.nativeForkFile(file);
		if(nativeFork==null)return new byte[0];
		try(FileInputStream input=new FileInputStream(nativeFork)){return input.readAllBytes();}
		catch(java.io.FileNotFoundException e){return new byte[0];} // no fork
	}

	private static boolean allZero(byte[] b){for(byte x:b)if(x!=0)return false;return true;}

	/** A one-paragraph summary of a run for the launcher's dialog. */
	static String summary(Report report){
		StringBuilder s=new StringBuilder();
		s.append(text("Sidecars written")).append(": ").append(report.written).append('\n');
		s.append(text("Already up to date")).append(": ").append(report.unchanged).append('\n');
		s.append(text("Nothing to carry (no resource fork or Finder info)")).append(": ").append(report.skipped).append('\n');
		if(report.failed>0){
			s.append(text("Failed")).append(": ").append(report.failed).append('\n');
			int shown=0;for(String error:report.errors){if(shown++==10){s.append("…\n");break;}s.append("  ").append(error).append('\n');}
		}
		return s.toString();
	}

	private static String text(String key){return PCARD.pc!=null&&PCARD.pc.intl!=null?PCARD.pc.intl.getDialogText(key):key;}
}

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** An in-place "restore" of a disk image onto a volume folder, standing in for Apple Software Restore (see Toolbox68k.launch). */
final class AsrRestore {
	private AsrRestore(){}

	/** Mounts {@code image} and copies the files of its root into {@code target}, replacing items of the same name (folders merge). */
	static void restore(File image,File target,String requester) throws IOException {
		if(target==null||!target.isDirectory())throw new IOException("no target volume");
		if(!Sandbox.allowFile(target,Sandbox.Access.WRITE,requester))throw new IOException("write refused");
		DiskImages.Mounted m=DiskImages.mount(image,null);
		File[] items=m.folder.listFiles();
		if(items==null)throw new IOException("empty image");
		for(File item:items){
			String n=item.getName();
			if(n.equals("Desktop DB")||n.equals("Desktop DF")||n.equals("Desktop Folder")||n.equals("Trash")||n.equals("Temporary Items")||n.startsWith("._")||n.equals(".DS_Store"))continue;
			copy(item,new File(target,n));
		}
		System.err.println("[ASR] restored "+image.getName()+" onto "+target);
	}

	private static void copy(File from,File to) throws IOException {
		if(from.isDirectory()){
			if(!to.isDirectory()&&!to.mkdirs())throw new IOException("cannot create "+to);
			byte[] info=FinderInfo68k.stored(from);
			if(info!=null)try{FinderInfo68k.write(to,info);}catch(RuntimeException ignored){}
			File[] children=from.listFiles();
			if(children!=null)for(File c:children){String n=c.getName();if(n.startsWith("._")||n.equals(".DS_Store"))continue;copy(c,new File(to,n));}
			return;
		}
		if(to.exists()){to.setWritable(true);Files.delete(to.toPath());}
		WorkingCopies.copyFile(from,to);
	}
}

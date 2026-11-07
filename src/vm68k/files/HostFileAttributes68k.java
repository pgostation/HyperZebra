import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;

/**
 * What the Finder keeps of a file beside its forks and Finder info - its lock, its creation date, its comment - on the host file
 * itself, so that what a program sets outlasts the 68K VM that set it and the host's Finder shows it. Rinaldi's externals (checked
 * Oct 2026) set them one VM at a time: SetFinderLock's lock was gone for the next external (EraseFile then deleted the "locked"
 * file), SetFInfo's creation dates and FinderComments' comments were never seen again.
 *
 * - The lock is macOS's Locked (the uchg flag the Finder's Locked checkbox sets), elsewhere the read-only attribute / no write
 *   permission; a file the host will not let anyone write reads back as locked (ioFlAttrib bit 0), as it already did. On macOS a
 *   file of the user's that only its permissions keep read-only ({@link #readOnlyCopy}: a game folder copied from its CD keeps
 *   r--r--r--) is no locked file: the Finder does not show it Locked, and on a Mac the copy on the hard disk would be writable.
 * - The creation date is the host's birth time where the host keeps one.
 * - The comment is the Spotlight comment attribute the Finder writes (com.apple.metadata:kMDItemFinderComment, a binary property
 *   list holding one string) on macOS, and the AppleDouble sidecar's Comment entry (ID 4) where the host has no forks.
 */
final class HostFileAttributes68k {
	private HostFileAttributes68k(){}

	/** True when the host keeps the file from being written (the Finder's Locked, read-only): the file is locked to programs. */
	static boolean locked(File file){return file!=null&&file.isFile()&&!file.canWrite()&&!readOnlyCopy(file);}

	/**
	 * True for a file of the user's that is read-only by its permissions alone (no owner write bit) on macOS, where the Finder's
	 * Locked is the uchg flag instead: UltraHyperDisc's games, copied from the CD with r--r--r--, stopped at their own files -
	 * Space Joust and Killer Box Virus with "-45" (fLckdErr), StarGates with "Please run this program from a Hard-disk.".
	 */
	static boolean readOnlyCopy(File file){
		if(file==null||!GMenu.isMacOSX()||!file.isFile()||file.canWrite())return false;
		try{PosixFileAttributes a=Files.readAttributes(file.toPath(),PosixFileAttributes.class);
			return !a.permissions().contains(PosixFilePermission.OWNER_WRITE)&&a.owner().getName().equals(System.getProperty("user.name"));}
		catch(IOException|UnsupportedOperationException|SecurityException e){return false;}
	}

	/** True when the file can be written, or can be once {@link #makeWritable} gives a {@link #readOnlyCopy} its write permission back. */
	static boolean mayBecomeWritable(File file){return file!=null&&(file.canWrite()||readOnlyCopy(file));}

	/** Makes a {@link #readOnlyCopy} writable for its owner (what a program writing it finds on a Mac); false when the file stays unwritable (Locked, or the host refused). */
	static boolean makeWritable(File file){
		if(file==null||file.canWrite())return file!=null;
		if(!readOnlyCopy(file)||!file.setWritable(true,true)||!file.canWrite())return false;
		System.err.println("[file] "+file.getName()+": read-only copy made writable for the program");
		return true;
	}

	/** Locks or unlocks the file on the host; false when the host refused. */
	static boolean setLocked(File file,boolean locked){
		if(file==null||!file.exists())return false;
		if(GMenu.isMacOSX()){
			try{Process p=new ProcessBuilder("/usr/bin/chflags",locked?"uchg":"nouchg",file.getPath()).redirectErrorStream(true).start();
				p.getInputStream().readAllBytes();if(p.waitFor()==0)return true;}
			catch(IOException e){}catch(InterruptedException e){Thread.currentThread().interrupt();}
		}
		return file.setWritable(!locked);
	}

	/** Sets the host file's creation (birth) time; false where the host keeps none or refused. */
	static boolean setCreationTime(File file,long millis){
		try{Files.getFileAttributeView(file.toPath(),BasicFileAttributeView.class).setTimes(null,null,FileTime.fromMillis(millis));return true;}
		catch(IOException|UnsupportedOperationException|SecurityException e){return false;}
	}

	// ------------------------------------------------------------------ Finder comments

	static final String COMMENT_ATTRIBUTE="com.apple.metadata:kMDItemFinderComment";

	/** The file's Finder comment, empty when it has none. */
	static String comment(File file){
		if(file==null||!file.exists())return "";
		if(ResourceFile68k.nativeForks()){byte[] plist=FinderInfo68k.attribute(file,COMMENT_ATTRIBUTE);String s=plist==null?null:plistString(plist);return s==null?"":s;}
		File sidecar=ResourceFile68k.AppleDouble.findSidecar(file);
		if(sidecar==null)return "";
		try{byte[] c=ResourceFile68k.AppleDouble.comment(Files.readAllBytes(sidecar.toPath()));return c==null?"":new String(c,StandardCharsets.UTF_8);}
		catch(IOException e){return "";}
	}

	/** Sets (an empty comment removes) the file's Finder comment; false when it could not be stored. */
	static boolean setComment(File file,String comment){
		if(file==null||!file.exists())return false;
		if(ResourceFile68k.nativeForks())return comment.isEmpty()?FinderInfo68k.removeAttribute(file,COMMENT_ATTRIBUTE):FinderInfo68k.setAttribute(file,COMMENT_ATTRIBUTE,plist(comment));
		try{
			File found=ResourceFile68k.AppleDouble.findSidecar(file),sidecar=ResourceFile68k.AppleDouble.sidecarOf(file);
			byte[] data=found==null?null:Files.readAllBytes(found.toPath());
			byte[] fork=data==null?null:HCResource.extractResourceForkFromAppleDouble(data),info=data==null?null:ResourceFile68k.AppleDouble.finderInfo(data);
			if(fork==null&&info==null&&comment.isEmpty())return true;
			Files.write(sidecar.toPath(),ResourceFile68k.AppleDouble.build(fork==null?new byte[0]:fork,info,comment.isEmpty()?null:comment.getBytes(StandardCharsets.UTF_8)));
			return true;
		}catch(IOException e){return false;}
	}

	/** A binary property list ("bplist00") holding one string, as the Finder writes a comment. */
	static byte[] plist(String s){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		out.writeBytes("bplist00".getBytes(StandardCharsets.US_ASCII));
		boolean ascii=s.chars().allMatch(c->c<0x80);
		byte[] body=ascii?s.getBytes(StandardCharsets.US_ASCII):s.getBytes(StandardCharsets.UTF_16BE);
		int count=ascii?body.length:body.length/2;
		if(count<15)out.write((ascii?0x50:0x60)|count);
		else{out.write((ascii?0x50:0x60)|15);
			if(count<0x100){out.write(0x10);out.write(count);}else if(count<0x10000){out.write(0x11);out.write(count>>8);out.write(count);}
			else{out.write(0x12);for(int k=24;k>=0;k-=8)out.write(count>>k);}}
		out.writeBytes(body);
		int table=out.size();
		out.write(8); // the offset table: the one object is at 8
		byte[] trailer=new byte[32];trailer[6]=1;trailer[7]=1;trailer[15]=1; // offsetIntSize 1, objectRefSize 1, one object, top object 0
		for(int k=0;k<8;k++)trailer[24+k]=(byte)((long)table>>(56-8*k));
		out.writeBytes(trailer);
		return out.toByteArray();
	}
	/** The string a binary property list holds at its top, or null when it holds no string. */
	static String plistString(byte[] p){
		try{
			if(p.length<40||!new String(p,0,8,StandardCharsets.US_ASCII).equals("bplist00"))return null;
			int t=p.length-32,offsetSize=p[t+6]&0xff;int top=(int)be(p,t+16,8),table=(int)be(p,t+24,8);
			int at=(int)be(p,table+top*offsetSize,offsetSize);
			int marker=p[at]&0xff,kind=marker>>4;long n=marker&15;at++;
			if(kind!=5&&kind!=6)return null;
			if(n==15){int m=p[at]&0xff;int size=1<<(m&15);n=be(p,at+1,size);at+=1+size;}
			return kind==5?new String(p,at,(int)n,StandardCharsets.ISO_8859_1):new String(p,at,(int)n*2,StandardCharsets.UTF_16BE);
		}catch(IndexOutOfBoundsException e){return null;}
	}
	private static long be(byte[] b,int at,int size){long v=0;for(int i=0;i<size;i++)v=v<<8|(b[at+i]&0xff);return v;}
}

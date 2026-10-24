import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Reads and writes classic Finder information (type, creator, flags) for host
 * files: MacBinary headers, AppleDouble sidecars, and on macOS the
 * com.apple.FinderInfo extended attribute (through the xattr tool, since Java's
 * attribute view cannot reach Apple's namespace).
 */
final class FinderInfo68k {
	private FinderInfo68k(){}

	/** 16 bytes of Finder info, or null when the host knows nothing about the file. */
	static byte[] read(File file){
		byte[] info=stored(file);if(info==null)return guess(file);
		// Finder info with flags but no type (a .hzb stack file carries only the custom-icon flag its icon gave it): the type and
		// creator are the extension's guess, the flags stay
		if(info.length>=8&&MacApp68k.u4(info,0)==0&&MacApp68k.u4(info,4)==0){byte[] guessed=guess(file);if(guessed!=null){info=info.clone();System.arraycopy(guessed,0,info,0,8);}}
		return info;}
	/** The Finder info recorded for the file (MacBinary header, AppleDouble sidecar, extended attribute), without guessing. */
	static byte[] stored(File file){
		try{
			if(file==null||!file.exists())return null;
			ResourceFile68k.MacBinary mb=file.isFile()?ResourceFile68k.MacBinary.read(file):null;
			if(mb!=null){byte[] info=new byte[16];System.arraycopy(mb.header,65,info,0,8);info[8]=mb.header[73];info[9]=mb.header[101];System.arraycopy(mb.header,75,info,10,4);return info;}
			byte[] afp=file.isFile()?afpInfo(file):null; // Windows: the stream write() keeps up to date, so it goes before any sidecar
			if(afp!=null&&(afp[16]!=0||afp[20]!=0))return java.util.Arrays.copyOfRange(afp,16,32);
			File sidecar=ResourceFile68k.AppleDouble.findSidecar(file);
			if(sidecar!=null){byte[] fi=ResourceFile68k.AppleDouble.finderInfo(Files.readAllBytes(sidecar.toPath()));if(fi!=null&&(fi[0]!=0||fi[4]!=0)){byte[] info=new byte[16];System.arraycopy(fi,0,info,0,16);return info;}}
			if(GMenu.isMacOSX()){
				byte[] attribute=nativeAttribute(file,FINDER_INFO_ATTRIBUTE);
				if(attribute!=null){if(attribute.length>=16){byte[] info=new byte[16];System.arraycopy(attribute,0,info,0,16);return info;}}
				else if(!nativeXattrAvailable()){ // the xattr tool: a process per file, only without the Foreign Function helper
					Process p=new ProcessBuilder("xattr","-px",FINDER_INFO_ATTRIBUTE,file.getPath()).redirectErrorStream(true).start();
					String out=new String(p.getInputStream().readAllBytes(),"US-ASCII");p.waitFor();
					String hex=out.replaceAll("[^0-9A-Fa-f]","");
					if(p.exitValue()==0&&hex.length()>=32){byte[] info=new byte[16];for(int i=0;i<16;i++)info[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return info;}
				}
			}
			byte[] pcExchange=file.isFile()?PcExchange.finderInfo(file):null; // a DOS disk's FINDER.DAT
			if(pcExchange!=null)return pcExchange;
		}catch(Exception ignored){}
		return null;
	}

	/** Type/creator guessed from the extension so applications can filter ordinary host files. */
	static byte[] guess(File file){
		if(file==null)return null;
		String name=file.getName().toLowerCase();String type=null,creator="????";
		if(file.isDirectory())return null;
		if(name.endsWith(".txt")||name.endsWith(".text")||name.endsWith(".htm")||name.endsWith(".html")||name.endsWith(".c")||name.endsWith(".h")||name.endsWith(".p")||name.endsWith(".java")){type="TEXT";creator="ttxt";}
		else if(name.endsWith(".pict")||name.endsWith(".pct")){type="PICT";creator="ttxt";}
		else if(name.endsWith(".mov")||name.endsWith(".moov")||name.endsWith(".qt")){type="MooV";creator="TVOD";}
		else if(name.endsWith(".sit")){type="SIT!";creator="SIT!";}
		else if(name.endsWith(".cpt")){type="PACT";creator="CPCT";}
		else if(name.endsWith(".hqx")){type="TEXT";creator="BnHq";}
		else if(name.endsWith(".bin")){type="BINA";creator="????";}
		else if(name.endsWith(".snd")||name.endsWith(".aif")||name.endsWith(".aiff")){type="AIFF";creator="SCPL";}
		else if(name.endsWith(".gif")){type="GIFf";creator="ogle";}
		else if(name.endsWith(".jpg")||name.endsWith(".jpeg")){type="JPEG";creator="ogle";}
		else if(name.endsWith(".png")){type="PNGf";creator="ogle";}
		else if(name.endsWith(".mid")||name.endsWith(".midi")){type="Midi";creator="TVOD";}
		else if(MacApp68k.isApplication(file)){type="APPL";creator="????";}
		else if(isHyperCardStack(file)){type="STAK";creator="WILD";} // stacks copied without Finder info (HyperCard opens only 'STAK' files)
		else if(StackBundle.hasHzbExtension(file)&&StackBundle.isPacked(file)){type="STAK";creator="WILD";} // a HyperZebra stack file is HyperCard's stack for externals: Files(folder,"STAK"), SFGetFile with a 'STAK' filter (DevStack's Files, CompactStacks) never saw one; opened by that name it is the stack's stand-in (StackFile68k)
		if(type==null)return null;
		byte[] info=new byte[16];for(int i=0;i<4;i++){info[i]=(byte)type.charAt(i);info[4+i]=(byte)creator.charAt(i);}return info;
	}

	/** A HyperCard stack file starts with its STAK block: size, then the 'STAK' block type. */
	private static boolean isHyperCardStack(File file){
		if(!file.isFile()||file.length()<64)return false;
		try(java.io.InputStream in=Files.newInputStream(file.toPath())){byte[] head=in.readNBytes(8);
			return head.length==8&&(head[4]=='S'&&head[5]=='T'&&head[6]=='A'&&head[7]=='K'||
				head[0]=='H'&&head[1]=='C'&&head[2]=='S'&&head[3]=='A'&&head[4]=='p'&&head[5]=='w'&&head[6]=='p'&&head[7]=='c');} // "HCSApwpc": a PowerPC code fragment in front of the stack
		catch(IOException e){return false;}
	}

	static void write(File file,byte[] info){
		if(file==null||info==null||!file.exists())return;
		try{
			ResourceFile68k.MacBinary mb=file.isFile()?ResourceFile68k.MacBinary.read(file):null;
			if(mb!=null){System.arraycopy(info,0,mb.header,65,8);mb.header[73]=info[8];mb.header[101]=info[9];System.arraycopy(info,10,mb.header,75,4);mb.write(file);return;}
			if(ResourceFile68k.hasMacStreams(file)){ // Windows: into the AFP_AfpInfo stream beside the file's fork, the rest of the record kept
				byte[] afp=afpInfo(file);if(afp==null)afp=AFP_INFO_TEMPLATE.clone();
				System.arraycopy(info,0,afp,16,Math.min(16,info.length));
				try(java.io.FileOutputStream out=new java.io.FileOutputStream(ResourceFile68k.ntfsStream(file,AFP_INFO_STREAM))){out.write(afp);return;}
				catch(IOException e){/* the sidecar below */}
			}
			if(ResourceFile68k.nativeForks()){
				byte[] attribute=new byte[32];System.arraycopy(info,0,attribute,0,16);
				if(writeNative(file,attribute))return;
			}
			File sidecar=ResourceFile68k.AppleDouble.sidecarOf(file),found=ResourceFile68k.AppleDouble.findSidecar(file);
			byte[] fork=new byte[0],comment=null;
			if(found!=null){byte[] data=Files.readAllBytes(found.toPath());byte[] existing=HCResource.extractResourceForkFromAppleDouble(data);if(existing!=null)fork=existing;comment=ResourceFile68k.AppleDouble.comment(data);}
			byte[] fi=new byte[32];System.arraycopy(info,0,fi,0,16);
			Files.write(sidecar.toPath(),ResourceFile68k.AppleDouble.build(fork,fi,comment));
		}catch(Exception ignored){}
	}

	/**
	 * The 32 bytes (FInfo + FXInfo) of a file's or folder's own com.apple.FinderInfo attribute on macOS (its AFP_AfpInfo
	 * stream on Windows), or null when there is none - nothing is guessed from the extension here (AppleDoubleConverter
	 * copies exactly what the Finder holds).
	 */
	static byte[] nativeFinderInfo(File file){
		if(ResourceFile68k.WINDOWS){byte[] afp=afpInfo(file);return afp==null?null:java.util.Arrays.copyOfRange(afp,16,48);}
		if(!GMenu.isMacOSX()||file==null||!file.exists())return null;
		try{
			byte[] attribute=nativeAttribute(file,FINDER_INFO_ATTRIBUTE);
			if(attribute==null&&!nativeXattrAvailable()){
				Process p=new ProcessBuilder("xattr","-px",FINDER_INFO_ATTRIBUTE,file.getPath()).redirectErrorStream(true).start();
				String out=new String(p.getInputStream().readAllBytes(),"US-ASCII");p.waitFor();
				String hex=out.replaceAll("[^0-9A-Fa-f]","");
				if(p.exitValue()!=0||hex.length()<32)return null;
				attribute=new byte[hex.length()/2];for(int i=0;i<attribute.length;i++)attribute[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
			}
			if(attribute==null||attribute.length<16)return null;
			byte[] info=new byte[32];System.arraycopy(attribute,0,info,0,Math.min(32,attribute.length));return info;
		}catch(Exception e){return null;}
	}

	/** macOS: sets a file's com.apple.FinderInfo attribute (the 32 bytes of FInfo + FXInfo) and nothing else - no sidecar when it cannot. */
	static boolean writeNative(File file,byte[] info32){
		if(!GMenu.isMacOSX()||file==null||!file.exists()||info32==null||info32.length!=32)return false;
		if(nativeXattrAvailable())return setNativeAttribute(file,FINDER_INFO_ATTRIBUTE,info32);
		try{
			StringBuilder hex=new StringBuilder();for(int i=0;i<32;i++)hex.append(String.format("%02X",info32[i]&0xff));
			Process p=new ProcessBuilder("xattr","-wx",FINDER_INFO_ATTRIBUTE,hex.toString(),file.getPath()).redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();p.waitFor();
			return p.exitValue()==0;
		}catch(Exception e){return false;}
	}

	/** A text file HyperTalk's `open file` just made gets HyperCard's type and creator, TEXT/ttxt (HC 2.4 and J-2.3 measured):
	 *  externals filter by type (Claris XTND's import hid it). Only where the Finder info lives with the file (macOS). */
	static void markNewTextFile(File file){
		if(!ResourceFile68k.nativeForks()||file==null||!file.isFile()||stored(file)!=null)return;
		byte[] info=new byte[32];info[0]='T';info[1]='E';info[2]='X';info[3]='T';info[4]='t';info[5]='t';info[6]='x';info[7]='t';
		writeNative(file,info);
	}

	static int type(File file){byte[] info=read(file);return info==null?0:MacApp68k.u4(info,0);}

	private static final String FINDER_INFO_ATTRIBUTE="com.apple.FinderInfo";
	/**
	 * Windows keeps a Mac client's Finder info (SMB, Services for Macintosh) in this NTFS stream: the 60-byte AfpInfo record -
	 * signature "AFP\0", version, reserved, backup time, then the 32 bytes of FInfo + FXInfo at offset 16, ProDOS info.
	 */
	private static final String AFP_INFO_STREAM="AFP_AfpInfo";
	/** An AfpInfo record with no Finder info yet, as the SMB server writes it (little-endian version 1.0, backup time "never"). */
	private static final byte[] AFP_INFO_TEMPLATE={'A','F','P',0, 0,0,1,0, 0,0,0,0, 0,0,0,(byte)0x80, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0};
	/** Windows: a file's or folder's AfpInfo record, or null (other hosts, no stream, not a record). */
	private static byte[] afpInfo(File file){
		if(!ResourceFile68k.WINDOWS||file==null||!file.exists())return null;
		File stream=ResourceFile68k.ntfsStream(file,AFP_INFO_STREAM);
		if(stream.length()<48)return null;
		try(java.io.FileInputStream in=new java.io.FileInputStream(stream)){
			byte[] afp=in.readNBytes(4096);
			return afp.length>=48&&afp[0]=='A'&&afp[1]=='F'&&afp[2]=='P'&&afp[3]==0?afp:null;
		}catch(IOException e){return null;}
	}
	/** NativeXattr (ffm/, compiled only on Java 22+ and loaded by reflection) reads extended attributes through libc. */
	private static java.lang.reflect.Method nativeGet,nativeSet;private static boolean nativeChecked;
	private static synchronized boolean nativeXattrAvailable(){
		if(!nativeChecked){nativeChecked=true;
			try{Class<?> c=Class.forName("NativeXattr");nativeGet=c.getMethod("get",String.class,String.class);nativeSet=c.getMethod("set",String.class,String.class,byte[].class);
				nativeGet.invoke(null,"/","nonexistent.attribute.probe");} // fails here, not later, when the linker is unusable
			catch(Throwable t){nativeGet=null;nativeSet=null;}}
		return nativeGet!=null;
	}
	private static byte[] nativeAttribute(File file,String name){
		if(!nativeXattrAvailable())return null;
		try{return (byte[])nativeGet.invoke(null,file.getPath(),name);}catch(Throwable t){return null;}
	}
	private static boolean setNativeAttribute(File file,String name,byte[] value){
		if(!nativeXattrAvailable())return false;
		try{return (Boolean)nativeSet.invoke(null,file.getPath(),name,value);}catch(Throwable t){return false;}
	}
	static int creator(File file){byte[] info=read(file);return info==null?0:MacApp68k.u4(info,4);}

	/** macOS: an extended attribute of the file (null: none), through libc or else the xattr tool. */
	static byte[] attribute(File file,String name){
		if(!GMenu.isMacOSX()||file==null||!file.exists())return null;
		if(nativeXattrAvailable())return nativeAttribute(file,name);
		try{Process p=new ProcessBuilder("xattr","-px",name,file.getPath()).redirectErrorStream(true).start();
			String out=new String(p.getInputStream().readAllBytes(),"US-ASCII");p.waitFor();
			String hex=out.replaceAll("[^0-9A-Fa-f]","");if(p.exitValue()!=0||hex.isEmpty())return null;
			byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return b;}
		catch(Exception e){return null;}
	}
	/** macOS: sets an extended attribute of the file; false when it could not. */
	static boolean setAttribute(File file,String name,byte[] value){
		if(!GMenu.isMacOSX()||file==null||!file.exists())return false;
		if(nativeXattrAvailable())return setNativeAttribute(file,name,value);
		try{StringBuilder hex=new StringBuilder();for(byte b:value)hex.append(String.format("%02X",b&0xff));
			Process p=new ProcessBuilder("xattr","-wx",name,hex.toString(),file.getPath()).redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();p.waitFor();return p.exitValue()==0;}
		catch(Exception e){return false;}
	}
	/** macOS: removes an extended attribute of the file (true also when it had none). */
	static boolean removeAttribute(File file,String name){
		if(!GMenu.isMacOSX()||file==null||!file.exists())return false;
		if(attribute(file,name)==null)return true;
		try{Process p=new ProcessBuilder("xattr","-d",name,file.getPath()).redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();p.waitFor();return p.exitValue()==0;}
		catch(Exception e){return false;}
	}
}

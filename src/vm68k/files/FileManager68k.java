import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sandboxed synchronous subset of the classic File Manager parameter-block API. */
final class FileManager68k {
	private static final int NO_ERR=0,NSV_ERR=-35,IO_ERR=-36,BD_NAM_ERR=-37,EOF_ERR=-39,POS_ERR=-40,FNF_ERR=-43,F_LCKD_ERR=-45,F_BSY_ERR=-47,DUP_FN_ERR=-48,PARAM_ERR=-50,RF_NUM_ERR=-51,PERM_ERR=-54,WR_PERM_ERR=-61,DIR_NF_ERR=-120,BAD_MOV_ERR=-122,FID_NOT_FOUND_ERR=-1300,FID_EXISTS=-1301,NOT_A_FILE_ERR=-1302,AFP_RANGE_NOT_LOCKED=-5020,AFP_RANGE_OVERLAP=-5021;
	private static final int IO_RESULT=16,IO_NAME_PTR=18,IO_VREF_NUM=22,IO_REF_NUM=24,IO_PERMISSION=27,IO_MISC=28,IO_BUFFER=32,IO_REQ_COUNT=36,IO_ACT_COUNT=40,IO_POS_MODE=44,IO_POS_OFFSET=46;
	private static final int IO_DIR_INDEX=28,IO_FL_ATTRIB=30,IO_FL_FNDR_INFO=32,IO_FL_NUM=48,IO_FL_LG_LEN=54,IO_FL_PY_LEN=58,IO_FL_R_LG_LEN=64,IO_FL_R_PY_LEN=68,IO_FL_CR_DAT=72,IO_FL_MD_DAT=76;
	private static final int IO_VOL_INDEX=28,IO_V_CR_DATE=30,IO_V_LS_MOD=34,IO_V_ATTRIB=38,IO_V_NUM_FILES=40,IO_V_AL_BLK_SIZE=48,IO_V_CLUMP_SIZE=52,IO_V_NEXT_FILE=58,IO_V_FREE_BLOCKS=62;
	private static final int IO_FCB_INDEX=28,IO_FCB_FILE_NUMBER=32,IO_FCB_FLAGS=36,IO_FCB_EOF=40,IO_FCB_PHYSICAL=44,IO_FCB_POSITION=48,IO_FCB_VREF=52,IO_FCB_CLUMP=54,IO_FCB_PARENT=58;
	private final M68kMemory memory;
	private final File root;
	private final StackTextEncoding encoding;
	/** Mounted volumes; index i is vRefNum -(i+1). An external's first volume is its stack's folder (FileSystem68k mounts the rest). */
	final List<File> volumes=new ArrayList<File>();
	final List<String> volumeNames=new ArrayList<String>();
	private int defaultVolumeRef=-1;
	/** Resource forks and Finder information are written back to the host (programs and externals alike). */
	private boolean persistent;
	private final Map<Integer,OpenFile> files=new LinkedHashMap<Integer,OpenFile>();
	private final Map<String,Metadata> metadata=new HashMap<String,Metadata>();
	private final Map<String,ResourceFork> resourceForks=new HashMap<String,ResourceFork>();
	/** MacBinary containers seen so far (null value: plain file) and the temp files holding their unpacked data forks. */
	private final Map<String,ResourceFile68k.MacBinary> macBinaries=new HashMap<String,ResourceFile68k.MacBinary>();
	private final Map<String,File> dataForks=new HashMap<String,File>();
	/** Host text files seen through a converted copy (see {@link TextFile68k}), keyed like {@link #dataForks}; a view without storage records that the file needed none. */
	private final Map<String,HostView> views=new HashMap<String,HostView>();
	/** Files the program created (or emptied) and wrote that were not text or picture files by type when closed: converted once a SetFInfo (or a rename) makes them 'TEXT' or 'PICT'. */
	private final java.util.Set<String> untypedNewFiles=new java.util.HashSet<String>();
	/** New files {@link #convertNewFile} wrote to the host as UTF-8: {the program's own bytes, the bytes written}, kept so a program that
	 *  retypes the file as something other than 'TEXT' gets its bytes back (see {@link #revertRetypedText}). */
	private final Map<String,byte[][]> utf8NewFiles=new HashMap<String,byte[][]>();
	/** Files the program created on the host: a program among them gets its bundle's icon as its host Finder icon when this program ends ({@link #giveNewProgramsIcons}). */
	private final java.util.Set<String> createdFiles=new java.util.LinkedHashSet<String>();
	/** Whether text files a program creates are written to the host as UTF-8 with LF line ends (the Preferences dialog: PCARD.utf8TextFiles68k; -Dhz68k.utf8text=false turns it off). */
	static volatile boolean utf8TextFiles=!"false".equals(System.getProperty("hz68k.utf8text"));
	/** Whether host PNG files are shown to programs as PICT files and PICT files they create are saved as PNG (PCARD.pngPictureFiles68k; -Dhz68k.pngpict=false turns it off). */
	static volatile boolean pngPictureFiles=!"false".equals(System.getProperty("hz68k.pngpict"));
	/** Plays a PICT for {@link #writeBack} and {@link #convertNewFile}: the program's own QuickDraw when there is one, else the host decoder. */
	private java.util.function.Function<byte[],java.awt.image.BufferedImage> pictureRenderer=PictureFile68k::renderWithHost;
	void setPictureRenderer(java.util.function.Function<byte[],java.awt.image.BufferedImage> renderer){pictureRenderer=renderer;}
	private final Map<Integer,String> fileIdReferences=new HashMap<Integer,String>();
	private final Map<String,List<RangeLock>> rangeLocks=new HashMap<String,List<RangeLock>>();
	private final Map<Integer,File> directories=new HashMap<Integer,File>();
	private File blessedFolder;
	/** The System Folder of the startup disk, reported as HGetVInfo's ioVFndrInfo[0] for the volume holding it. */
	void setBlessedFolder(File folder){blessedFolder=folder;}
	private final Map<Integer,WorkingDirectory> workingDirectories=new LinkedHashMap<Integer,WorkingDirectory>();
	private int nextReference=2;
	/** Refnum sharing with the Resource Manager (see {@link #shareReferences}): resource files and data forks live in one FCB space on a real Mac. */
	private java.util.function.IntSupplier referenceAllocator;
	private java.util.function.IntFunction<File> resourceFileLookup;
	private int nextCatalogId=SESSION_IDS,nextWorkingReference=-32760;
	/** Who is asking, for the {@link Sandbox} dialogs: the hosted program, or the stack whose XCMD this is. */
	private String requester="XCMD";
	void setRequester(String name){if(name!=null)requester=name;}
	String requester(){return requester;}

	/** Draws refnums from {@code allocator} (shared with the Resource Manager) and answers PBGetFCBInfo for resource files via {@code resourceFiles}. */
	void shareReferences(java.util.function.IntSupplier allocator,java.util.function.IntFunction<File> resourceFiles){referenceAllocator=allocator;resourceFileLookup=resourceFiles;}
	/**
	 * The System file's refNum (SysMap) and the file it is: the Resource Manager's System file is built in and has no file of its
	 * own, so PBGetFCBInfo of SysMap names "System" in the virtual System Folder. Dartmouth's SystemFolder XFCN finds the folder
	 * that way (it answered "Error -51").
	 */
	private java.util.function.IntSupplier systemFileReference;private File systemResourceFile;
	void shareSystemFile(java.util.function.IntSupplier reference,File file){systemFileReference=reference;systemResourceFile=file;}
	/** The refNums of the resource files the Resource Manager holds open: indexed PBGetFCBInfo walks them with the forks opened here. */
	private java.util.function.Supplier<java.util.Collection<Integer>> resourceFileReferences;
	void shareResourceFileReferences(java.util.function.Supplier<java.util.Collection<Integer>> references){resourceFileReferences=references;}
	/** Whether the Resource Manager opened the file behind a refNum with write permission (its fork is then read/write here too). */
	private java.util.function.IntPredicate resourceFileWritable;
	void shareResourceFileWritable(java.util.function.IntPredicate writable){resourceFileWritable=writable;}
	/**
	 * The Resource Manager closed {@code reference}: the fork handle attached to it goes, and what the program wrote through it
	 * goes into the file. MPW's ILink opens its output with FSpOpenResFile(fsRdWrPerm) and then lays the fork out itself with
	 * GetEOF/SetFPos/Read/Write/SetEOF on that refNum (the Resource Manager's map never changes), as a real Mac's FCB allows.
	 */
	void resourceFileClosed(int reference){
		OpenFile open=files.get(reference);if(open==null||!open.attached)return;
		files.remove(reference);
		try{open.file.close();if(persistent&&open.writable&&open.dirty){ResourceFork fork=resourceForkFor(open.path);ResourceFile68k.writeForkBytes(open.path,Files.readAllBytes(fork.storage.toPath()),ResourceFile68k.Storage.NONE);}}
		catch(IOException ignored){}
	}
	/** The owner of the FCB table hears about forks opened and closed here. */
	interface FcbListener{void opened(int reference,File path,boolean resourceFork,boolean writable,long eof);void closed(int reference);}
	private FcbListener fcbListener;
	void setFcbListener(FcbListener listener){fcbListener=listener;}
	/** Hears (old path, new path) of every object renamed or moved here, so FSRefs made for it keep naming it. */
	private java.util.function.BiConsumer<File,File> moveListener;
	void setMoveListener(java.util.function.BiConsumer<File,File> listener){moveListener=listener;}
	int volumeIndexOf(File path) throws IOException {return volumeIndexFor(path);}
	private int allocateReference(){return referenceAllocator!=null?referenceAllocator.getAsInt():nextReference++;}
	/** The open file behind {@code reference}. A refNum the Resource Manager opened is an FCB too: resource editors read a file's resource fork
	 *  through the refNum OpenResFile returned (GetEOF/SetFPos/Read), so such a reference is attached lazily as a read-only fork. */
	/** The Resource Manager rewrote {@code path}'s fork: the handles {@link #openFileFor} attached to it read the old bytes
	 *  (a named fork opened before the rewrite keeps showing the fork it opened), so they are reopened on next use. */
	void resourceForkRewritten(File path){
		String key;try{key=canonical(path).getPath();}catch(IOException e){return;}
		boolean programOpen=false;
		for(java.util.Iterator<Map.Entry<Integer,OpenFile>> it=files.entrySet().iterator();it.hasNext();){
			OpenFile open=it.next().getValue();String openKey;try{openKey=canonical(open.path).getPath();}catch(IOException e){continue;}
			if(!openKey.equals(key)||!open.resourceFork)continue;
			if(open.attached){it.remove();try{open.file.close();}catch(IOException ignored){}}else programOpen=true;
		}
		// the fork's working copy (resourceForkFor) was taken before the rewrite; one the program has open itself stays
		if(!programOpen){ResourceFork fork=resourceForks.remove(key);if(fork!=null)fork.storage.delete();}
	}
	private OpenFile openFileFor(int reference){OpenFile open=files.get(reference);File path=resourceFileLookup==null?null:resourceFileLookup.apply(reference);
		if(open!=null&&(!open.attached||(path!=null&&path.equals(open.path))))return open;
		if(open!=null){files.remove(reference);try{open.file.close();}catch(IOException ignored){}} // the refNum went to another resource file since
		if(path==null||!path.isFile())return null;
		// read/write when the Resource Manager has it open for writing (asked of the sandbox at the first write)
		boolean writable=resourceFileWritable!=null&&resourceFileWritable.test(reference)&&path.canWrite();
		try{open=new OpenFile(new RandomAccessFile(resourceForkFor(path).storage,writable?"rw":"r"),path,writable,true,true);open.askAtWrite=writable;}catch(IOException e){return null;}files.put(reference,open);return open;}
	/** The name a program sees for {@code path}: the header name of a MacBinary container, else the host name (see {@link #programName}). */
	String macNameOf(File path) throws IOException {ResourceFile68k.MacBinary mb=macBinaryFor(path);String name=mb!=null&&mb.nameBytes.length>0?encoding.decode(mb.nameBytes):programName(path.getName());
		if(encoding.encodeReplacing(name).length<=HFS_NAME_MAX)return name;
		return shortHfsName(encoding,name,path.isDirectory()?directoryId(path):fileNumber(path));}
	/** The longest name (in the program's encoding) an HFS call hands out: programs keep names in Str31 buffers. */
	static final int HFS_NAME_MAX=31;
	/**
	 * A host name longer than 31 bytes, as HFS calls report it: as much of the name as fits, '#', the file number in hex and the
	 * extension - the form Mac OS X gave such names through the HFS API. {@link #resolveBytes} finds the file again by it.
	 * PageMaker 6's installer walks every folder with indexed GetCatInfo into a Str31: a 40-byte Shift-JIS name overran it.
	 */
	static String shortHfsName(StackTextEncoding encoding,String name,int id){
		String suffix="#"+Integer.toHexString(id).toUpperCase(java.util.Locale.ROOT);
		int dot=name.lastIndexOf('.');String extension=dot>0&&name.length()-dot<=6?name.substring(dot):"";
		String base=extension.isEmpty()?name:name.substring(0,dot);
		int budget=HFS_NAME_MAX-encoding.encodeReplacing(suffix+extension).length;
		StringBuilder kept=new StringBuilder();int used=0;
		for(int i=0;i<base.length();){int cp=base.codePointAt(i);String c=new String(Character.toChars(cp));int n=encoding.encodeReplacing(c).length;
			if(used+n>budget)break;kept.append(c);used+=n;i+=Character.charCount(cp);}
		return kept+suffix+extension;
	}
	/**
	 * The name a program sees for a host file name. Host names are Unicode, but a tool that unpacked a Japanese archive
	 * without knowing its encoding stored the Shift-JIS name bytes as if they were Mac OS Roman (for example, a Japanese file name
	 * arrives as "ÉtÉäÅ[É}ÉbÉv01"). Such a name cannot be written in the program's encoding at all (it would reach the
	 * program as "?t???[?}?b?v01", which names nothing), so when every character is a Mac OS Roman one and those bytes read
	 * cleanly in the program's encoding, the program gets the name the file had on the Mac; {@link #resolveBytes} finds
	 * the host file again by the same re-reading. The same holds the other way round: a file a Japanese program wrote
	 * as "QuickTime\uff6a" (Mac OS Roman $AA = \u2122 read as Shift-JIS) is "QuickTime\u2122" to a Roman program - a real Mac
	 * only ever compares the bytes, so a name must never degrade to "QuickTime?" (the QuickTime INIT reopens itself by the
	 * name its FCB reports and gave up with "installation failed" when HyperZebra's language was English).
	 */
	String programName(String hostName){return programName(encoding,hostName);}
	static String programName(StackTextEncoding encoding,String hostName){
		if(File.separatorChar=='\\')hostName=decodeWindowsName(hostName);
		// A ':' in a host name is the '/' of the Mac name (macOS displays it that way).
		// must not reach the program as a two-component path (HyperCard: "can't open stack ... error -43"); resolveName maps it back.
		hostName=hostName.replace(':','/').replace('\u2400','\0'); // U+2400 is a NUL of the Mac name (see hostNul)
		if(isAscii(hostName))return hostName;
		// macOS keeps names as they were written, often decomposed (an unpacked "はいぱあれもん" has ぱ as は + U+309A): the
		// program's encoding has only the composed letter (Shift-JIS had no combining marks, the name became "はいぱ?あれもん")
		hostName=Normalizer.normalize(hostName,Normalizer.Form.NFC);
		String unpacked=archivedName(hostName);if(unpacked!=null)hostName=unpacked;
		try{encoding.encode(hostName);return hostName;}catch(java.nio.charset.CharacterCodingException e){}
		// Japanese and Mac OS Roman come first in the enumeration: the encodings host names here were written in.
		for(StackTextEncoding other:StackTextEncoding.values()){
			if(other==encoding)continue;
			byte[] raw;try{raw=other.encode(hostName);}catch(java.nio.charset.CharacterCodingException|RuntimeException e){continue;}
			String decoded;try{decoded=encoding.decode(raw);if(decoded.indexOf('\ufffd')>=0||!Arrays.equals(encoding.encode(decoded),raw))continue;}catch(RuntimeException|java.nio.charset.CharacterCodingException e){continue;}
			return decoded;
		}
		return hostName;
	}
	private static boolean isAscii(String s){for(int i=0;i<s.length();i++)if(s.charAt(i)>=0x80)return false;return true;}
	/** The Mac name of a host file as the user reads it: Windows escapes undone and ':' back to '/', in no program encoding (a Roman disc's "AmandaStories®" stays "®", never "ｨ"). */
	static String displayName(String hostName){
		if(File.separatorChar=='\\')hostName=decodeWindowsName(hostName);
		return Normalizer.normalize(hostName.replace(':','/'),Normalizer.Form.NFC);
	}
	/** The host file name for a Mac name (escaped on Windows): disk images written into host folders use it. */
	static String hostFileName(String name){return windowsName(name);}
	/** Escape Mac file-name characters that Win32 cannot put in a path. The private-use marker is escaped too. */
	private static String windowsName(String name){
		if(File.separatorChar!='\\')return name;
		StringBuilder out=new StringBuilder();
		for(int i=0;i<name.length();i++){
			char c=name.charAt(i);
			boolean invalid=c<32||c=='<'||c=='>'||c==':'||c=='"'||c=='/'||c=='\\'||c=='|'||c=='?'||c=='*'||c=='\uF000'||((c=='.'||c==' ')&&i==name.length()-1);
			if(invalid){out.append('\uF000');String hex=String.format(java.util.Locale.ROOT,"%04X",(int)c);out.append(hex);}else out.append(c);
		}
		String base=name.split("\\.",2)[0];
		if(base.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))out.insert(0,"\uF000R");
		return out.toString();
	}
	/** Services for Macintosh (and macOS over SMB) store the Mac characters Windows forbids in names as private-use characters:
	 *  " * / < > ? \ | as U+F020-F027 (a Mac folder "Ux/1999" reaches Windows as "Ux\uF0221999"). */
	private static final String SFM_ASCII="\"*/<>?\\|";
	/** The Mac characters of a text holding SFM private-use characters: a Windows path from "ask file" handed to an external
	 *  (the characters are not in any Mac encoding and became '?'). */
	static String macCharsOfSfm(String text){
		if(text==null||File.separatorChar!='\\')return text;
		StringBuilder out=null;
		for(int i=0;i<text.length();i++){
			char c=text.charAt(i);
			if(c>='\uF020'&&c<='\uF027'){if(out==null)out=new StringBuilder(text.substring(0,i));out.append(SFM_ASCII.charAt(c-'\uF020'));}
			else if(out!=null)out.append(c);
		}
		return out==null?text:out.toString();
	}
	/** One component of a Windows path back from an external: the characters Windows forbids are SFM private-use characters. */
	private static String sfmCharsOf(String component){
		StringBuilder out=new StringBuilder(component.length());
		for(int i=0;i<component.length();i++){char c=component.charAt(i);int k=SFM_ASCII.indexOf(c);out.append(k>=0&&c!='\\'?(char)('\uF020'+k):c);}
		return out.toString();
	}
	private static String decodeWindowsName(String name){
		if(name.startsWith("\uF000R"))name=name.substring(2);
		StringBuilder out=new StringBuilder();
		for(int i=0;i<name.length();i++){
			if(name.charAt(i)=='\uF000'&&i+4<name.length()){
				try{out.append((char)Integer.parseInt(name.substring(i+1,i+5),16));i+=4;continue;}catch(NumberFormatException ignored){}
			}
			out.append(name.charAt(i));
		}
		return out.toString();
	}
	FileManager68k(M68kMemory memory,OStack stack){this.memory=memory;encoding=stack==null?StackTextEncoding.ROMAN:stack.legacyTextEncoding;File source=stack==null?null:stack.file;
		if(source==null&&stack!=null&&stack.path!=null&&!stack.path.isEmpty())source=new File(stack.path);root=source==null?null:convertedFrom(source.isDirectory()?source:source.getParentFile());
		if(root!=null)Sandbox.trustStackFolder(root); // the default directory (FileSystem68k mounts the volumes holding it)
		// what an external changes stays, as in HyperCard: G-XCollection's MtSetFTC sets a file's type and creator, MtWriteFile's
		// creator, MtZapRsrc empties a resource fork (all of it vanished with the external's VM before)
		persistent=true;
		stackShadows=new LinkedHashMap<String,StackFile68k.Shadow>();}
	/** Whether {@code path} is the stand-in of a HyperZebra stack (see {@link StackFile68k}): it stays, fBsyErr, as an open stack file
	 *  would on a Mac - the stack itself is not deleted through it. */
	private boolean isStackStandIn(File path) throws IOException {
		if(stackShadows==null||stackShadows.isEmpty())return false;
		File canonical=path.getCanonicalFile();
		for(StackFile68k.Shadow s:stackShadows.values())if(s.file.getCanonicalFile().equals(canonical))return true;
		return false;
	}
	/** XCMD mode: the stand-ins of HyperZebra stacks the external named by path (see {@link StackFile68k}), by stack; null for programs. */
	private Map<String,StackFile68k.Shadow> stackShadows;
	/** XCMD mode: the stand-in file for the HyperZebra stack {@code path} names (made at the first use in this VM), else {@code path}. */
	private File stackShadow(File path){
		if(stackShadows==null)return path;
		File bundle=StackFile68k.stackAt(path);if(bundle==null)return path;
		StackFile68k.Shadow s=stackShadows.get(bundle.getPath());
		if(s==null||!s.file.isFile()){s=StackFile68k.make(bundle,path.getName(),requester);if(s==null)return path;stackShadows.put(bundle.getPath(),s);}
		return s.file;
	}
	/** The stack a stand-in file stands for, or null when {@code file} is no stand-in of this VM. */
	OStack stackOfShadow(File file){
		if(stackShadows==null||file==null)return null;
		for(StackFile68k.Shadow s:stackShadows.values())if(s.file.equals(file)||s.file.getAbsoluteFile().equals(file.getAbsoluteFile()))return s.stack;
		return null;
	}
	/** After each external call: what it changed in the stand-ins goes into their stacks, and those it has no longer open go. */
	void syncStackShadows(){
		if(stackShadows==null||stackShadows.isEmpty())return;
		for(java.util.Iterator<StackFile68k.Shadow> it=stackShadows.values().iterator();it.hasNext();){
			StackFile68k.Shadow s=it.next();
			try{StackFile68k.sync(s,requester);}catch(RuntimeException e){System.err.println("[xcmd] stand-in of "+s.bundle+": "+e);}
			boolean busy;try{busy=s.file.exists()&&isBusy(s.file);}catch(IOException e){busy=false;}
			if(!busy){it.remove();removeState(s.file.getAbsolutePath());try{removeState(s.file.getCanonicalPath());}catch(IOException e){}StackFile68k.discard(s);} // made again from the stack at the next use
		}
	}

	/**
	 * The folder of the HyperCard stack a .hzb was converted from ("CO.Intro.hzb" beside "CO.Intro"): that is where HyperCard
	 * kept the stack, so its externals look for their companion files there (MacroMind's PlayMovie opens "MacroMind Player" and
	 * "Sounds" next to the stack; Cosmic Osmo's SysDir walked up to "CO.Intro.hzb:"). Other folders are kept.
	 */
	/** The folder of the stack whose externals this File Manager serves (null for a program) */
	File stackFolder(){return root;}
	static File convertedFrom(File folder){
		File raw=convertedStackFile(folder);
		return raw!=null?raw.getParentFile():folder;
	}
	/** The HyperCard stack file a .hzb folder was converted from ("CO.Intro" for "CO.Intro.hzb" or "CO.Intro 2.hzb"), or null. */
	static File convertedStackFile(File folder){
		folder=StackBundle.host(folder); // a stack file (.hzb ZIP) is beside the HyperCard stack it came from, not its working folder
		if(folder==null||!folder.getName().endsWith(".hzb"))return null;
		File parent=folder.getParentFile();if(parent==null)return null;
		String base=folder.getName().substring(0,folder.getName().length()-4);
		File raw=new File(parent,base);
		if(!raw.isFile())raw=new File(parent,base.replaceFirst(" [0-9]+$",""));
		return raw.isFile()&&OStack.looksLikeHyperCardStack(raw.getPath())?raw:null;
	}

	/** Roots of the volumes that are locked (mounted disk images): nothing on them can be created, changed or deleted. */
	private final java.util.Set<File> lockedRoots=new java.util.HashSet<File>();
	/** wPrErr: the volume is write-protected (a locked disk image, like a CD or a floppy with its tab open). */
	static final int WPR_ERR=-44;
	private static File canon(File f){try{return f.getCanonicalFile();}catch(IOException e){return f.getAbsoluteFile();}}
	/** Marks the volume at {@code directory} locked. */
	void lockVolume(File directory){lockedRoots.add(canon(directory));}
	/** Whether {@code path} lies on a locked volume. */
	boolean onLockedVolume(File path){
		if(lockedRoots.isEmpty()||path==null)return false;
		try{int index=volumeIndexFor(path);return index>=0&&lockedRoots.contains(canon(volumes.get(index)));}catch(IOException e){return false;}
	}
	/**
	 * The capacity of a volume: the size of its disk image when it is a mounted one (a floppy is 1.44 MB - installers refuse to run
	 * from anything over 2 MB "hard disk", as QuarkXPress 3.31's does), else what the host disk has.
	 */
	long volumeTotalSpace(File volume){
		if(!lockedRoots.isEmpty()){
			File c=canon(volume);
			for(DiskImages.Mounted m:DiskImages.mounted())if(m.capacity>0&&canon(m.folder).equals(c))return m.capacity;
		}
		return volume.getTotalSpace();
	}
	/** Whether the volume with index {@code index} (vRefNum -(index+1)) is locked. */
	boolean volumeLocked(int index){return index>=0&&index<volumes.size()&&!lockedRoots.isEmpty()&&lockedRoots.contains(canon(volumes.get(index)));}
	/** Mounts a host folder as one more volume, after the ones there are (a disk image mounted while the program runs); its index, or -1. */
	int mountVolume(File directory,String name,boolean locked){
		if(directory==null||!directory.isDirectory()||name==null||name.isEmpty())return -1;
		for(int i=0;i<volumes.size();i++)if(canon(volumes.get(i)).equals(canon(directory)))return i;
		volumes.add(directory);volumeNames.add(name);if(locked)lockVolume(directory);
		return volumes.size()-1;
	}

	/** Volumes on a drive a program added (_AddDrive) and mounted through its own driver: volume index -> {drive, driver refNum}. */
	final Map<Integer,int[]> guestDrives=new HashMap<Integer,int[]>();
	private final java.util.Set<File> opticalRoots=new java.util.HashSet<File>();
	/**
	 * Drives of the disk images mounted while a program runs: volume index -> drive number. A Mac numbers its removable SCSI and
	 * image drives from 5 up (1-4 belong to the floppy and HD20 drives): Apple Installer 3.4's "Please insert the disk" dialog
	 * hands the diskEvt's drive number back as its item hit, and item 4 is its Stop - Adobe Dimensions' Disk 2, mounted as
	 * drive 4, ended the installation as "cancelled" (or "not the correct disk").
	 */
	private final Map<Integer,Integer> imageDrives=new HashMap<Integer,Integer>();
	/**
	 * The drive number of volume {@code index}: the program's drive, a disk image's own (see {@link #imageDrives}), or index + 5
	 * for the host volumes - fixed disks, never in the floppy drives 1 and 2: VISE installers take a volume in drive 1 or 2 for a
	 * floppy, and Master Tracks Pro 6's ejected "HyperZebra HD" (drive 1) again and again waiting for its Disk#2 there.
	 */
	int driveOf(int index){int[] d=guestDrives.get(index);if(d!=null)return d[0];Integer image=imageDrives.get(index);return image!=null?image:index+FIRST_FIXED_DRIVE;}
	static final int FIRST_FIXED_DRIVE=5;
	/** The index of the volume in drive {@code drive}, or -1. */
	int volumeIndexOfDrive(int drive){for(int i=0;i<volumes.size();i++)if(driveOf(i)==drive)return i;return -1;}
	/** Gives the disk image mounted as volume {@code index} the first free drive number from 5 up and answers it. */
	int assignImageDrive(int index){
		java.util.Set<Integer> used=new java.util.HashSet<Integer>();
		for(int i=0;i<volumes.size();i++)if(i!=index)used.add(driveOf(i));
		int drive=5;while(used.contains(drive))drive++;
		imageDrives.put(index,drive);return drive;
	}
	/** The driver refNum of volume {@code index}: the program's driver, or the disk image's fixed/optical driver. */
	int driverRefOf(int index){int[] d=guestDrives.get(index);return d!=null?d[1]:opticalVolume(index)?CD_DRIVER_REF:DISK_DRIVER_REF;}
	boolean opticalVolume(int index){
		if(index<0||index>=volumes.size())return false;
		File root=canon(volumes.get(index));
		if(opticalRoots.contains(root))return true;
		for(DiskImages.Mounted image:DiskImages.mounted())if(image.optical&&canon(image.folder).equals(root)){opticalRoots.add(root);return true;}
		return false;
	}

	/** Mounts one more host folder as a volume after the ones there are (an external's HyperZebra HD and host disks). */
	void addVolume(File directory,String name){
		if(root==null||directory==null||!directory.isDirectory()||name==null||name.isEmpty())return;
		for(String n:volumeNames)if(sameMacName(n,name))return;
		volumes.add(directory);volumeNames.add(name);
	}

	/** Application mode: several host directories become volumes; the first is the default (boot) volume. */
	FileManager68k(M68kMemory memory,StackTextEncoding encoding,List<File> mountedVolumes,List<String> names){this.memory=memory;this.encoding=encoding;
		for(int i=0;i<mountedVolumes.size();i++){volumes.add(mountedVolumes.get(i));volumeNames.add(names.get(i));}root=volumes.isEmpty()?null:volumes.get(0);persistent=true;}

	/** Whether {@code path} lies on a mounted volume (the boot volume's vRefNum is -1 too, so {@link #volumeRefNumFor} cannot tell). */
	boolean onVolume(File path){try{return volumeIndexFor(path)>=0;}catch(IOException e){return false;}}
	boolean volumeRoot(File path){try{return isVolumeRoot(path.getCanonicalFile());}catch(IOException e){return false;}}
	/** vRefNum (-1 based) of the volume containing {@code path}, or -1. */
	int volumeRefNumFor(File path) throws IOException {int index=volumeIndexFor(path);return index<0?-1:-(index+1);}
	private int volumeIndexFor(File path) throws IOException {String p=path.getCanonicalPath();int best=-1,bestLength=-1;for(int i=0;i<volumes.size();i++){String v=volumes.get(i).getCanonicalPath();if((p.equals(v)||p.startsWith(v.endsWith(File.separator)?v:v+File.separator))&&v.length()>bestLength){best=i;bestLength=v.length();}}return best;}
	/** The host folder of a volume (-1 = the startup volume, 0 = the default one). */
	File volumeRoot(int volumeReference) throws IOException {return volumeRootFor(volumeReference);}
	private File volumeRootFor(int volumeReference) throws IOException {int ref=(short)volumeReference;if(ref<0&&ref>=-volumes.size())return volumes.get(-ref-1).getCanonicalFile();if(ref==0)ref=defaultVolumeRef;if(ref<0&&ref>=-volumes.size())return volumes.get(-ref-1).getCanonicalFile();WorkingDirectory working=workingDirectories.get(ref);if(working!=null){File d=directories.get(working.directoryId);if(d!=null){int index=volumeIndexFor(d);if(index>=0)return volumes.get(index).getCanonicalFile();}}return root==null?null:root.getCanonicalFile();}
	/**
	 * The volume a GetVolInfo/Eject names by ioVRefNum alone: 0 the default volume, a volume reference number (-1, -2 ...), a
	 * working directory, or a drive number (5, 6 ... - the drive of volume n is n + 4, a disk image's its own); null when none of them exists, where the
	 * File Manager answers nsvErr. {@link #volumeRootFor} falls back to the startup volume instead, which open calls rely on.
	 */
	File namedVolume(int volumeReference) throws IOException {
		int ref=(short)volumeReference;
		if(ref>0)for(Map.Entry<Integer,int[]> e:guestDrives.entrySet())if(e.getValue()[0]==ref&&e.getKey()<volumes.size())return volumes.get(e.getKey()).getCanonicalFile();
		if(ref>0)for(Map.Entry<Integer,Integer> e:imageDrives.entrySet())if(e.getValue()==ref&&e.getKey()<volumes.size())return volumes.get(e.getKey()).getCanonicalFile();
		if(ref==0||ref<0&&ref>=-volumes.size())return volumeRootFor(ref);
		if(workingDirectories.containsKey(ref))return volumeRootFor(ref);
		if(ref>0){int i=volumeIndexOfDrive(ref);if(i>=0)return volumes.get(i).getCanonicalFile();}
		return null;
	}
	private boolean isVolumeRoot(File canonical) throws IOException {return canonicalVolumes().contains(canonical);}
	/** The volumes' canonical roots, resolved once (a disk search asks for every entry it meets). */
	private java.util.Set<File> canonicalVolumeRoots;private int canonicalVolumeCount=-1;
	private java.util.Set<File> canonicalVolumes() throws IOException {
		if(canonicalVolumeRoots==null||canonicalVolumeCount!=volumes.size()){java.util.Set<File> set=new java.util.HashSet<File>();for(File v:volumes)set.add(v.getCanonicalFile());canonicalVolumeRoots=set;canonicalVolumeCount=volumes.size();}
		return canonicalVolumeRoots;
	}
	String volumeNameOf(File path){try{return volumeNameFor(path);}catch(IOException e){return path.getName();}}
	/** A volume's name as the program reads it: in its own encoding, as {@link #programName} gives file names (a Roman disk's "®" is the byte $A8). */
	private String volumeNameFor(File path) throws IOException {int index=volumeIndexFor(path);return index<0?(root==null?"":root.getName()):programName(volumeNames.get(index));}
	/**
	 * The index of the volume a program names {@code name} (no colon), or -1: by its host name or by the name the program reads
	 * ({@link #programName}). A Roman disk "AmandaStories®" is "AmandaStoriesｨ" to a program under the Japanese system - the
	 * same byte $A8 - and AmandaStories' MacroMind Player opens its first movie by the full path "Amandastories®:...".
	 */
	int volumeIndexNamed(String name){
		for(int i=0;i<volumeNames.size();i++)if(sameMacName(volumeNames.get(i),name)||sameMacName(programName(volumeNames.get(i)),name))return i;
		return -1;
	}
	/** Public helpers for the application layer. */
	/** Driver refNum reported for every host volume (unit 38, the last SCSI disk driver slot); FileSystem68k answers its Status calls.
	 *  It must differ from QuickDraw68k.VIDEO_DRIVER_REF (-33): both were -33 for a while and the disk driver swallowed the video
	 *  driver's SetEntries Control calls (Arashi loads its palette that way and showed a black screen). */
	static final int DISK_DRIVER_REF=-39;
	static final int CD_DRIVER_REF=-38;
	/** The allocation block size a host volume is described with: 4 KB, doubled until {@link #MAX_VOLUME_BLOCKS} blocks cover the
	 *  disk, but never past the 32 KB of a 2 GB HFS volume - the block counts are 16-bit fields, and programs multiply them by the
	 *  block size in 32-bit arithmetic (programs choosing the volume with the most free bytes otherwise saw negative products for a
	 *  32 MB block size), and some take the block size itself for 16 bits (MacPaint 1.5 read a 64 KB one as 0: "The disk is almost
	 *  full"). Past 16 KB it is 32256 bytes (63 x 512; HFS block sizes are any multiple of 512), not 32 KB: that is still a positive
	 *  INTEGER, and some programs multiply it by ioVFrBlk with MULS.W - The Writing Center read 32768 as -32768, so the free space
	 *  came out negative and every Save said "There is not enough space left on the disk". A big host disk is therefore described
	 *  as about 1 GB (32767 blocks of 32256 bytes). */
	/** A fork's physical length: whole allocation blocks (GetFInfo's physical sizes, FolderSize's on-disk total equalled the logical ones). */
	static long physical(long length,int block){return (length+block-1)/block*block;}
	/** The allocation block size of the volume the file is on (the ioVAlBlkSiz GetVolInfo reports for it), by volume. */
	private final Map<Integer,Integer> allocationBlocks=new HashMap<Integer,Integer>();
	private int allocationBlockFor(File path) throws IOException {
		int index=volumeIndexFor(path);File volume=index<0?root:volumes.get(index);if(volume==null)return 512;
		Integer block=allocationBlocks.get(index);
		if(block==null){block=volumeBlockSize(volumeTotalSpace(volume));allocationBlocks.put(index,block);}
		return block;
	}
	static int volumeBlockSize(long totalBytes){int blockSize=4096;while(totalBytes/blockSize>MAX_VOLUME_BLOCKS&&blockSize<0x4000)blockSize*=2;return totalBytes/blockSize>MAX_VOLUME_BLOCKS?MAX_BLOCK_SIZE:blockSize;}
	/** The biggest allocation block size reported: the largest multiple of 512 a signed 16-bit multiply still reads as positive. */
	static final int MAX_BLOCK_SIZE=0x7e00;
	/** Most blocks a volume's total/free counts (ioVNmAlBlks, ioVFrBlk, vcbNmAlBlks, vcbFreeBks) report: Pascal declares them INTEGER,
	 *  and programs sign-extend them - FlipBookMaker 1.1 multiplies `movea.w ioVFrBlk` by the block size, so 65535 free blocks read as
	 *  -1 and every project was "out of disk space". A real volume only got there when more than half of a big disk was free. */
	static final int MAX_VOLUME_BLOCKS=0x7fff;
	/** A block count of {@code bytes} in blocks of {@code blockSize}, pinned to {@link #MAX_VOLUME_BLOCKS}. */
	static int volumeBlocks(long bytes,int blockSize){return (int)Math.min(MAX_VOLUME_BLOCKS,bytes/blockSize);}
	int directoryIdFor(File directory) throws IOException {return directoryId(directory);}
	int parentDirectoryIdFor(File path) throws IOException {return parentDirectoryId(path);}
	File directoryForId(int directoryId,int volumeReference) throws IOException {return directoryFor(directoryId,volumeReference);}
	File resolveClassicPath(File currentDirectory,String name) throws IOException {
		File result=resolveName(currentDirectory,name);
		if(result==null||result.exists()||isAscii(name))return result;
		// The name is text the program's bytes were decoded to (CreateResFile/OpenResFile take a path string): again as those
		// bytes, so the other Mac encodings are tried too - a Roman "Utilities ƒ" folder reads "Utilities ﾄ" in Japanese.
		byte[] bytes;try{bytes=encoding.encode(name);}catch(java.nio.charset.CharacterCodingException|RuntimeException e){return result;}
		File again=resolveBytes(currentDirectory,bytes);
		return again!=null&&(again.exists()||again.getParentFile()!=null&&again.getParentFile().isDirectory())?again:result;}
	/** ioWDProcID of the working directories made here for the program (its launch folder, SetVol targets): the Finder's 'ERIK', so
	 *  that a program's own OpenWD with another procID gets a WD of its own and its CloseWD cannot take the launch WD away -
	 *  MPj Astro opened and closed a WD on its folder with procID 0 and lost its default directory (data file "not found"). */
	static final int FINDER_WD_PROC_ID=0x4552494B;
	int workingDirectoryRefFor(File directory) throws IOException {File canonical=directory.getCanonicalFile();if(isVolumeRoot(canonical))return volumeRefNumFor(canonical);int id=directoryId(canonical);int reference=findWorkingReference(id,FINDER_WD_PROC_ID);if(reference==0){reference=nextWorkingReference++;workingDirectories.put(reference,new WorkingDirectory(id,FINDER_WD_PROC_ID));}return reference;}
	Metadata metadataOf(File path) throws IOException {return metadataFor(path);}
	void setDefaultDirectory(File directory) throws IOException {defaultVolumeRef=workingDirectoryRefFor(directory);searchFirst=directory.getCanonicalFile();}
	/** The program's own folder: PBCatSearch visits it before the rest of its volume. */
	private File searchFirst;
	/** The folder holding the program's own folder, when that is not a volume's root: walked right after it for a creator. */
	private File nearFolder(){
		File near=searchFirst==null?null:searchFirst.getParentFile();
		if(near==null||!near.isDirectory())return null;
		String c=Sandbox.canonical(near);for(File v:volumes)if(Sandbox.canonical(v).equals(c))return null;
		return near;
	}
	/** vRefNum (volume or working directory) of the default directory. */
	int defaultVolumeRef(){return defaultVolumeRef;}
	/** Directory ID of the default directory (2 for a volume root). */
	int defaultDirectoryId() throws IOException {WorkingDirectory working=workingDirectories.get(defaultVolumeRef);return working==null?2:working.directoryId;}
	StackTextEncoding encoding(){return encoding;}
	boolean isPersistent(){return persistent;}

	boolean handle(M68kCpu cpu,int opcode) throws M68kCpuException {
		int pb=cpu.a[0];
		/* a refused path or a volume that is not mounted is a result code for every call, never a Java exception out of the trap:
		   FileList("Nowhere:") had its PBOpenWD throw NoSuchVolume and the whole HyperTalk handler stopped without a word */
		try{return dispatch(cpu,opcode);}catch(SecurityException e){if(Boolean.getBoolean("hz68k.trace"))System.err.println("[file] $"+Integer.toHexString(opcode&0xffff)+": "+e.getMessage());return finish(cpu,pb,securityError(e));}
	}
	private boolean dispatch(M68kCpu cpu,int opcode) throws M68kCpuException {
		int trap=opcode&0xff;
		switch(trap){
			case 0x00:return open(cpu,(opcode&0x0200)!=0);
			case 0x01:return close(cpu);
			case 0x02:return read(cpu);
			case 0x03:return write(cpu);
			case 0x07:return getVolumeInfo(cpu,(opcode&0x0200)!=0);
			case 0x08:return create(cpu,(opcode&0x0200)!=0);
			case 0x09:return delete(cpu,(opcode&0x0200)!=0);
			case 0x0a:return openResourceFork(cpu,(opcode&0x0200)!=0);
			case 0x0b:return rename(cpu,(opcode&0x0200)!=0);
			case 0x0c:return getFileInfo(cpu,(opcode&0x0200)!=0);
			case 0x0d:return setFileInfo(cpu,(opcode&0x0200)!=0);
			case 0x10:return allocate(cpu);
			case 0x11:return getEof(cpu);
			case 0x12:return setEof(cpu);
			case 0x13:return flush(cpu,false);
			case 0x45:return flush(cpu,true);
			case 0x60:return hfsDispatch(cpu,(opcode&0x0200)!=0);
			case 0x14:return getVolume(cpu,(opcode&0x0200)!=0);
			case 0x15:return setVolume(cpu,(opcode&0x0200)!=0);
			case 0x18:return getPosition(cpu);
			case 0x41:return setFileLock(cpu,(opcode&0x0200)!=0,true);
			case 0x42:return setFileLock(cpu,(opcode&0x0200)!=0,false);
			case 0x44:return setPosition(cpu);
			default:return false;
		}
	}

	private boolean hfsDispatch(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {switch(cpu.d[0]&0xffff){case 1:return openWorkingDirectory(cpu);case 2:return closeWorkingDirectory(cpu);case 5:return moveCatalogEntry(cpu);case 6:return createDirectory(cpu);case 7:return getWorkingDirectoryInfo(cpu);case 8:return getFileControlBlockInfo(cpu);case 9:return getCatalogInfo(cpu);case 10:return setCatalogInfo(cpu);case 0x10:return lockRange(cpu,true);case 0x11:return lockRange(cpu,false);case 0x14:return createFileIdReference(cpu);case 0x15:return deleteFileIdReference(cpu);case 0x16:return resolveFileIdReference(cpu);case 0x1a:{int dirId=memory.readLong(cpu.a[0]+48);return openDataFork(cpu,hierarchical||dirId==2||directories.containsKey(dirId));}/* PBHOpenDF: QuickTime passes ioDirID through $A060 too, so a known directory ID counts even without the HFS bit; Speedometer's PBOpenDF ($A060, ParamBlockRec) leaves garbage there and means the working directory */case 0x1b:return makeFileSpec(cpu);case 0x12:return extendedVolumeInfo(cpu);case 0x30:return getVolumeParameters(cpu);default:return false;}}
	/** PBXGetVolInfo ($A060 selector $12): PBHGetVInfo's XVolumeParam with the volume's size and free space in 64-bit bytes
	 *  (ioVTotalBytes at 122, ioVFreeBytes at 130) - Acrobat 5 checks the startup volume's free space before it spools a page
	 *  and refused to print ("not enough space") when the call failed. */
	private boolean extendedVolumeInfo(M68kCpu cpu) throws M68kCpuException {
		int pb=cpu.a[0];getVolumeInfo(cpu,true);
		if((short)memory.readWord(pb+16)!=NO_ERR)return true;
		try{File volume=volumeRootFor((short)memory.readWord(pb+IO_VREF_NUM));
			long total=volume==null?0:volumeTotalSpace(volume),free=volume==null||volumeLocked(volumeIndexFor(volume))?0:volume.getUsableSpace();
			memory.writeLong(pb+122,(int)(total>>>32));memory.writeLong(pb+126,(int)total);memory.writeLong(pb+130,(int)(free>>>32));memory.writeLong(pb+134,(int)free);}
		catch(IOException e){return finish(cpu,pb,IO_ERR);}
		return true;
	}
	private boolean createDirectory(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],result;if(root==null)return finish(cpu,pb,NSV_ERR);try {File parent=baseFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM),memory.readLong(pb+IO_NAME_PTR));if(parent==null)result=FNF_ERR;else {File path=resolveFrom(parent,memory.readLong(pb+IO_NAME_PTR));if(path==null)result=BD_NAM_ERR;else {result=createFolder(path);/* ioNamePtr may be a partial pathname below ioDirID: AppleScript creates ":Scripting Additions:Dialects" from the Extensions folder */if(result==NO_ERR)memory.writeLong(pb+48,directoryId(path));}}}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** Creates the folder {@code path} (PBDirCreate, FSCreateDirectoryUnicode); a result code. */
	int createFolder(File path) throws IOException {if(path.exists())return DUP_FN_ERR;if(onLockedVolume(path))return WPR_ERR;if(path.getParentFile()==null||!path.getParentFile().isDirectory())return DIR_NF_ERR;Sandbox.checkFile(path,Sandbox.Access.CREATE,requester);return path.mkdir()?NO_ERR:path.getParentFile().canWrite()?IO_ERR:WPR_ERR/* the host folder is not writable (the root of the host disk): a locked volume to the program, not an I/O error - Sniffer Dog's installer reported success after -36 */;}
	private boolean openWorkingDirectory(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],result;if(root==null)return finish(cpu,pb,NSV_ERR);try {File directory=directoryFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM));
		// ioNamePtr may name the directory (a partial pathname from ioWDDirID: applications may open a support folder in the System Folder; a volume
		// name alone stands for the volume); a leaf name that is no folder is ignored, as programs leave file names in the block from earlier calls; a pathname that names nothing is fnfErr (Illustrator 8 probes ...:CMS and recursed forever when it "existed")
		int namePtr=memory.readLong(pb+IO_NAME_PTR);if(directory!=null&&namePtr!=0&&memory.readByte(namePtr)>0){File named=resolveFrom(directory,namePtr);if(named!=null&&named.isDirectory())directory=named;else if((named==null||!named.exists())&&namesPath(namePtr))directory=null;}
		if(directory==null||!directory.isDirectory())result=FNF_ERR;else if(isVolumeRoot(directory.getCanonicalFile())){memory.writeWord(pb+IO_VREF_NUM,volumeRefNumFor(directory));result=NO_ERR;}else {int id=directoryId(directory),process=memory.readLong(pb+28),reference=findWorkingReference(id,process);if(reference==0){reference=nextWorkingReference++;workingDirectories.put(reference,new WorkingDirectory(id,process));}memory.writeWord(pb+IO_VREF_NUM,reference);memory.writeLong(pb+48,id);result=NO_ERR;}}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** Whether the Pascal string at {@code namePtr} is a partial or full pathname (has a colon), as opposed to a leaf name left in the block. */
	private boolean namesPath(int namePtr) throws M68kCpuException {int n=memory.readByte(namePtr);for(int i=0;i<n;i++)if(memory.readByte(namePtr+1+i)==':')return true;return false;}
	private boolean closeWorkingDirectory(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_VREF_NUM),result;if(reference==-1)result=NO_ERR;else result=workingDirectories.remove(reference)==null?RF_NUM_ERR:NO_ERR;return finish(cpu,pb,result);}
	private boolean getWorkingDirectoryInfo(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],index=(short)memory.readWord(pb+26),reference=(short)memory.readWord(pb+IO_VREF_NUM),result=NO_ERR;WorkingDirectory value=null;try{if(index>0){int at=1;for(WorkingDirectory candidate:workingDirectories.values())if(at++==index){value=candidate;break;}memory.writeWord(pb+IO_VREF_NUM,-1);}else if(reference<0&&reference>=-Math.max(1,volumes.size()))value=new WorkingDirectory(2,0);else value=workingDirectories.get(reference);
		if(value==null)result=RF_NUM_ERR;else {File d=value.directoryId==2?volumeRootFor(reference):directories.get(value.directoryId);int output=memory.readLong(pb+IO_NAME_PTR);if(output!=0)writePascal(output,d==null?root.getName():volumeNameFor(d));memory.writeLong(pb+28,value.processId);memory.writeWord(pb+32,d==null?-1:volumeRefNumFor(d));memory.writeLong(pb+48,value.directoryId);}}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean getCatalogInfo(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],index=(short)memory.readWord(pb+IO_DIR_INDEX),result;if(root==null)return finish(cpu,pb,NSV_ERR);try {File base=directoryFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM)),path;if(base==null&&index==0&&memory.readLong(pb+48)==1){/* ioDirID 1 (fsRtParID) is the parent of every volume root: the name is the volume (Classilla's profile code asks for "Home" that way and quit on -43) */File volume=volumeNamedByCatalogName(memory.readLong(pb+IO_NAME_PTR));/* only the volume ioVRefNum names when it names one: Fontographer 4's installer asks (Install Disk 1, fsRtParID, "Disk 2") whether Disk 2 is in the drive yet, and a noErr for the mounted Disk 2 made it look for Disk 2's files on Disk 1 */int asked=(short)memory.readWord(pb+IO_VREF_NUM);if(volume!=null&&asked!=0){File askedRoot=volumeRootFor(asked);if(askedRoot!=null&&volumeIndexFor(askedRoot)!=volumeIndexFor(volume))volume=null;}if(volume!=null){writeCatalogInfo(pb,volume);return finish(cpu,pb,NO_ERR);}}if(base==null&&index==0&&(namesFullPath(memory.readLong(pb+IO_NAME_PTR))||hostColonPath(memory.readLong(pb+IO_NAME_PTR))))base=volumeRootFor(0);/* a full pathname ignores ioDirID: Pro Tools' installer asks for "HD:Digidesign:Pro Tools:" with the file number a previous call left there */if(base==null)return finish(cpu,pb,FNF_ERR);if(index>0){File[] entries=sortedEntries(base);if(index>entries.length)return finish(cpu,pb,FNF_ERR);path=entries[index-1].getCanonicalFile();}
		else if(index<0){path=base;}else{int namePtr=memory.readLong(pb+IO_NAME_PTR);path=namePtr==0||memory.readByte(namePtr)==0?base:resolveFrom(base,namePtr);} // index 0 with no name: the directory ioDirID itself (IM Files 2-183); some applications ask for their own folder that way
		// ioNamePtr is an input for index 0 and stays as it is (some programs look up a data folder and then append file
		// names to that same string); indexed and directory (-1) lookups return the name.
		if(path==null||!path.exists())result=FNF_ERR;else {int output=memory.readLong(pb+IO_NAME_PTR);if(output!=0&&index!=0)writePascal(output,isVolumeRoot(path.getCanonicalFile())?volumeNameFor(path):macNameOf(path));if(index==0)materializeView(path);writeCatalogInfo(pb,path);result=NO_ERR;}}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private File volumeNamedByCatalogName(int namePtr) throws M68kCpuException {if(namePtr==0||memory.readByte(namePtr)==0)return null;String n=encoding.decode(memory.readBytes(namePtr+1,memory.readByte(namePtr)));if(n.endsWith(":"))n=n.substring(0,n.length()-1);int i=volumeIndexNamed(n);return i<0?null:volumes.get(i);}
	private boolean setCatalogInfo(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],index=(short)memory.readWord(pb+IO_DIR_INDEX),result;if(root==null)return finish(cpu,pb,NSV_ERR);try {File base=index<0?directoryFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM)):baseFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM),memory.readLong(pb+IO_NAME_PTR)),path;if(base==null)result=FNF_ERR;else {int namePtr=memory.readLong(pb+IO_NAME_PTR);path=index<0||namePtr==0||memory.readByte(namePtr)==0?base:resolveFrom(base,namePtr);/* no name: the directory ioDirID itself, as for PBGetCatInfo (Core Collection's ChangeType touches the parent folder's date with the block GetCatInfo filled and reported "Could not change type." on -43) */if(path==null||!path.exists())result=FNF_ERR;else result=setCatalogEntry(path,(memory.readByte(pb+IO_FL_ATTRIB)&1)!=0,memory.readBytes(pb+32,16),memory.readBytes(pb+84,16),memory.readLong(pb+72),memory.readLong(pb+76),memory.readLong(pb+80));}}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/**
	 * Catalog information a program sets (PBSetCatInfo, FSSetCatalogInfo): the lock, Finder information (16 bytes and the extended
	 * 16), creation/modification/backup dates as Mac local-time seconds. Null (Finder info, lock, backup date) or 0 (creation and
	 * modification dates) leaves a field alone; the modification date also goes to the host file. A result code.
	 */
	int setCatalogEntry(File path,Boolean locked,byte[] finderInfo,byte[] extendedFinderInfo,int created,int modified,Integer backup) throws IOException {if(onLockedVolume(path))return WPR_ERR;Sandbox.checkFile(path,Sandbox.Access.WRITE,requester);Metadata value=metadataFor(path);if(locked!=null){value.locked=locked;if(persistent&&path.isFile()&&HostFileAttributes68k.locked(path)!=locked)HostFileAttributes68k.setLocked(path,locked);}if(extendedFinderInfo!=null)System.arraycopy(extendedFinderInfo,0,value.extendedFinderInfo,0,16);if(finderInfo!=null){int oldType=path.isFile()?typeOf(path):0;System.arraycopy(finderInfo,0,value.finderInfo,0,16);if(persistent)FinderInfo68k.write(path,hostFolderSafe(path,hostFinderInfo(path,value.finderInfo)));if(untypedNewFiles.contains(canonical(path).getPath()))convertNewFile(path);revertRetypedText(path,oldType);}if(backup!=null)value.backupDate=backup;if(modified!=0){value.modificationDate=modified;setHostModificationDate(path,modified);}if(created!=0&&created!=value.creationDate){value.creationDate=created;if(persistent)setHostCreationDate(path,created);}return NO_ERR;}
	/** Selector $1A is PBOpenDF ($A060, a ParamBlockRec without ioDirID) or PBHOpenDF ($A260, HParamBlockRec): bit 9 of the trap word tells them apart like OpenRF/HOpenRF. */
	private boolean openDataFork(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;if(root==null)return finish(cpu,pb,NSV_ERR);try {File path=resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical);result=openPath(pb,path,false);traceOpen(pb,hierarchical,path,result);}catch(SecurityException e){result=securityError(e);}catch(IOException e){if(Mac68kApi.TRACE)e.printStackTrace();result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean openResourceFork(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;if(root==null)return finish(cpu,pb,NSV_ERR);try {File base=hierarchical?baseFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM),memory.readLong(pb+IO_NAME_PTR)):directoryFor(0,(short)memory.readWord(pb+IO_VREF_NUM)),path=resolveFrom(base,memory.readLong(pb+IO_NAME_PTR));result=openPath(pb,path,true);}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** Writes the FSSpec of {@code target} (which may not exist yet) at {@code spec}, as FSMakeFSSpec would; returns the result code. */
	int writeFSSpec(int spec,File target) throws M68kCpuException {
		memory.fill(spec,70,0);if(root==null)return NSV_ERR;
		try{File parent=target.getCanonicalFile().getParentFile();if(!target.exists()&&(parent==null||!parent.isDirectory()))return DIR_NF_ERR;
			memory.writeWord(spec,volumeRefNumFor(target));memory.writeLong(spec+2,parentDirectoryId(target));
			writePascal(spec+6,isVolumeRoot(target.getCanonicalFile())?volumeNameFor(target):target.getName().isEmpty()?root.getName():macNameOf(target),63);
			return target.exists()?NO_ERR:FNF_ERR;
		}catch(IOException e){return IO_ERR;}
	}
	private boolean makeFileSpec(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],spec=memory.readLong(pb+IO_MISC),result;if(spec==0)return finish(cpu,pb,PARAM_ERR);if(root==null){memory.fill(spec,70,0);return finish(cpu,pb,NSV_ERR);}try {int namePointer=memory.readLong(pb+IO_NAME_PTR);File base=baseFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM),memory.readLong(pb+IO_NAME_PTR));File target=base==null?null:namePointer==0||memory.readByte(namePointer)==0?base:resolveFrom(base,namePointer);/* the name is read before the spec is cleared: it may live inside the output spec (何かon林檎's shaped-window WDEF calls FSMakeFSSpec(vRef, dirID, spec.name, &spec)) */memory.fill(spec,70,0);if(base==null)return finish(cpu,pb,DIR_NF_ERR);if(target==null)return finish(cpu,pb,BD_NAM_ERR);File parent=target.getCanonicalFile().getParentFile();if(!target.exists()&&(parent==null||!parent.isDirectory()))return finish(cpu,pb,DIR_NF_ERR);memory.writeWord(spec,volumeRefNumFor(target));memory.writeLong(spec+2,parentDirectoryId(target));writePascal(spec+6,isVolumeRoot(target.getCanonicalFile())?volumeNameFor(target):target.getName().isEmpty()?root.getName():macNameOf(target),63);result=target.exists()?NO_ERR:FNF_ERR;}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean createFileIdReference(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],result;if(root==null)return finish(cpu,pb,NSV_ERR);try {File base=baseFor(memory.readLong(pb+48),(short)memory.readWord(pb+IO_VREF_NUM),memory.readLong(pb+IO_NAME_PTR)),path=resolveFrom(base,memory.readLong(pb+IO_NAME_PTR));if(path==null||!path.exists())result=FNF_ERR;else if(!path.isFile())result=NOT_A_FILE_ERR;else {int id=fileNumber(path);memory.writeLong(pb+54,id);String key=path.getCanonicalPath();result=fileIdReferences.containsKey(id)?FID_EXISTS:NO_ERR;fileIdReferences.put(id,key);}}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean deleteFileIdReference(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],id=memory.readLong(pb+54),result=fileIdReferences.remove(id)==null?FID_NOT_FOUND_ERR:NO_ERR;return finish(cpu,pb,result);}
	private boolean resolveFileIdReference(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],id=memory.readLong(pb+54),result;String key=fileIdReferences.get(id);if(key==null)return finish(cpu,pb,FID_NOT_FOUND_ERR);try {File path=new File(key);if(!path.isFile()){fileIdReferences.remove(id);result=FID_NOT_FOUND_ERR;}else {int output=memory.readLong(pb+IO_NAME_PTR);if(output!=0)writePascal(output,macNameOf(path));memory.writeLong(pb+48,parentDirectoryId(path));memory.writeWord(pb+IO_VREF_NUM,volumeRefNumFor(path));result=NO_ERR;}}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/**
	 * PBLockRange/PBUnlockRange. A range may reach past the end of the file (AFP byte-range locks; ioReqCount -1 runs to the
	 * largest offset): CodeWarrior 5 locks 0..$7FFFFFFF on every source file it opens, and with eofErr it opened an empty editor.
	 */
	private boolean lockRange(M68kCpu cpu,boolean lock) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),count=memory.readLong(pb+IO_REQ_COUNT),result=NO_ERR;OpenFile open=files.get(reference);if(open==null)result=RF_NUM_ERR;else if(count<-1)result=PARAM_ERR;else try {long start=rangeStart(open.file,pb),end=count==-1?Long.MAX_VALUE:start+count;if(start<0)result=PARAM_ERR;else if(count!=0){String key=rangeKey(open);List<RangeLock> locks=rangeLocks.computeIfAbsent(key,ignored->new ArrayList<RangeLock>());if(lock){for(RangeLock value:locks)if(value.overlaps(start,end)){result=value.reference==reference?AFP_RANGE_OVERLAP:F_LCKD_ERR;break;}if(result==NO_ERR)locks.add(new RangeLock(reference,start,end));}else {RangeLock found=null;for(RangeLock value:locks)if(value.reference==reference&&value.start==start&&value.end==end){found=value;break;}if(found==null)result=AFP_RANGE_NOT_LOCKED;else {locks.remove(found);if(locks.isEmpty())rangeLocks.remove(key);}}}}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean getVolumeParameters(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],buffer=memory.readLong(pb+IO_BUFFER),requested=memory.readLong(pb+IO_REQ_COUNT),result;memory.writeLong(pb+IO_ACT_COUNT,0);if(root==null)result=NSV_ERR;else if(requested<0||(requested>0&&buffer==0))result=PARAM_ERR;else {int attributes=(1<<6)|(1<<7)|(1<<10)|(1<<12)|(1<<20)|(1<<28)|(1<<29);
			/* bHasDesktopMgr (12): the desktop database calls (FileSystem68k.hfsExtra) serve every volume; without it the System 7.5
			   Finder keeps an old-style "Desktop" resource file at each volume root - on the Home volume that name is the user's
			   own Desktop folder */
			/* bNoDeskItems (20) keeps the Finder from putting a volume's Trash and desktop items on the desktop: the host folders
			   mounted as volumes cannot take a Trash folder, but the startup disk (the one holding the System Folder) has one */
			try{File volume=namedVolume((short)memory.readWord(pb+IO_VREF_NUM));if(volume!=null&&blessedFolder!=null&&canonical(blessedFolder.getParentFile()).equals(canonical(volume)))attributes&=~(1<<20);}catch(IOException ignored){}
			attributes=Integer.getInteger("hz68k.volattrs",attributes);/* bHasFileIDs, bHasCatSearch (PBCatSearch lives in FileSystem68k): without it installers may walk every folder of every volume with GetCatInfo.
			   Never bNoSysDir (17) / bNoBootBlks (19): those mark a server volume that cannot hold a System Folder, and Aladdin InstallerMaker 1.x (After Dark 2.0 "Double-Click Me to Install") refuses such a disk (its explanation falls through to "is damaged"). */byte[] data=ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).putShort((short)2).putInt(attributes).putInt(0).putInt(0).putInt(0).putShort((short)0).array();int actual=Math.min(requested,data.length);for(int i=0;i<actual;i++)memory.writeByte(buffer+i,data[i]);memory.writeLong(pb+IO_ACT_COUNT,actual);result=NO_ERR;}return finish(cpu,pb,result);}
	private boolean moveCatalogEntry(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],result;if(root==null)return finish(cpu,pb,NSV_ERR);try {int volume=(short)memory.readWord(pb+IO_VREF_NUM);File sourceDirectory=baseFor(memory.readLong(pb+48),volume,memory.readLong(pb+IO_NAME_PTR)),source=resolveFrom(sourceDirectory,memory.readLong(pb+IO_NAME_PTR));File volumeRoot=memory.readLong(pb+36)==1?volumeNamedByCatalogName(memory.readLong(pb+28)):null;/* ioNewDirID 1 (fsRtParID) with a volume's name is that volume's root (dfXFCNs fMove(file,"Home:")) */File destinationBase=volumeRoot!=null?volumeRoot:baseFor(memory.readLong(pb+36),volume,memory.readLong(pb+28)),destinationDirectory=volumeRoot!=null||memory.readLong(pb+28)==0||memory.readByte(memory.readLong(pb+28))==0?destinationBase:resolveFrom(destinationBase,memory.readLong(pb+28)); /* an empty ioNewName is the folder ioNewDirID itself, as NIL is (FSpCatMove to an FSSpec of {vRefNum, dirID, ""} from FindFolder: A-Dock's installer moving the old A-Dock to the Trash) */if(source==null||!source.exists())result=FNF_ERR;else if(destinationDirectory==null||!destinationDirectory.isDirectory())result=BD_NAM_ERR;else result=moveObject(source,destinationDirectory);}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** Moves {@code source} into the folder {@code destinationDirectory} (same volume, same name); a result code. PBCatMove and FSMoveObject share it. */
	int moveObject(File source,File destinationDirectory) throws IOException {if(onLockedVolume(source)||onLockedVolume(destinationDirectory))return WPR_ERR;if(metadataFor(destinationDirectory).locked)return F_LCKD_ERR;Sandbox.checkFile(source,Sandbox.Access.WRITE,requester);Sandbox.checkFile(destinationDirectory,Sandbox.Access.WRITE,requester);File destination=new File(destinationDirectory,source.getName()).getCanonicalFile(),canonicalSource=source.getCanonicalFile();String sourcePath=canonicalSource.getPath(),destinationDirectoryPath=destinationDirectory.getCanonicalPath();if(canonicalSource.equals(root.getCanonicalFile())||(source.isDirectory()&&(destinationDirectoryPath.equals(sourcePath)||destinationDirectoryPath.startsWith(sourcePath+File.separator))))return BAD_MOV_ERR;if(destination.equals(canonicalSource))return NO_ERR;if(destination.exists())return DUP_FN_ERR;boolean file=canonicalSource.isFile();if(canonicalSource.renameTo(destination)){if(file)ResourceFile68k.AppleDouble.moveWith(canonicalSource,destination);rebaseState(sourcePath,destination.getPath());if(moveListener!=null)moveListener.accept(canonicalSource,destination);return NO_ERR;}return IO_ERR;}
	/** Whether {@code reference} is a resource file the Resource Manager holds open (the System file included). */
	private boolean isResourceFileReference(int reference){
		if(resourceFileLookup!=null&&resourceFileLookup.apply(reference)!=null)return true;
		return systemResourceFile!=null&&systemFileReference!=null&&reference==systemFileReference.getAsInt();
	}
	private boolean getFileControlBlockInfo(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],index=(short)memory.readWord(pb+IO_FCB_INDEX),result=NO_ERR,reference=(short)memory.readWord(pb+IO_REF_NUM);OpenFile open=null;
		if(index>0){reference=indexedControlBlock(index,(short)memory.readWord(pb+IO_VREF_NUM));if(reference==0)return finish(cpu,pb,RF_NUM_ERR);open=files.get(reference);}else open=files.get(reference);
		if(open==null){ // a resource file opened by the Resource Manager is an open resource fork too (programs locate themselves through CurResFile + PBGetFCBInfo)
			File path=resourceFileLookup==null?null:resourceFileLookup.apply(reference);
			if(path==null&&systemResourceFile!=null&&systemFileReference!=null&&reference==systemFileReference.getAsInt())path=systemResourceFile;
			if(path==null)result=RF_NUM_ERR;else try {int output=memory.readLong(pb+IO_NAME_PTR);if(output!=0)writePascal(output,macNameOf(path));memory.writeWord(pb+IO_REF_NUM,reference);memory.writeLong(pb+IO_FCB_FILE_NUMBER,fileNumber(path));memory.writeWord(pb+IO_FCB_FLAGS,0x0200|(path.canWrite()?0x0100:0));memory.writeWord(pb+38,0);long length=path.isFile()?resourceForkFor(path).storage.length():0;memory.writeLong(pb+IO_FCB_EOF,(int)Math.min(0x7fffffffL,length));memory.writeLong(pb+IO_FCB_PHYSICAL,(int)Math.min(0x7fffffffL,length));memory.writeLong(pb+IO_FCB_POSITION,0);memory.writeWord(pb+IO_FCB_VREF,volumeRefNumFor(path));memory.writeLong(pb+IO_FCB_CLUMP,0);memory.writeLong(pb+IO_FCB_PARENT,parentDirectoryId(path));}catch(IOException e){result=IO_ERR;}
			return finish(cpu,pb,result);}
		try {int output=memory.readLong(pb+IO_NAME_PTR);if(output!=0)writePascal(output,macNameOf(open.path));memory.writeWord(pb+IO_REF_NUM,reference);memory.writeLong(pb+IO_FCB_FILE_NUMBER,fileNumber(open.path));memory.writeWord(pb+IO_FCB_FLAGS,(open.writable?0x0100:0)|(open.resourceFork?0x0200:0));memory.writeWord(pb+38,0);long length=open.file.length();memory.writeLong(pb+IO_FCB_EOF,(int)Math.min(0x7fffffffL,length));memory.writeLong(pb+IO_FCB_PHYSICAL,(int)Math.min(0x7fffffffL,length));memory.writeLong(pb+IO_FCB_POSITION,(int)open.file.getFilePointer());memory.writeWord(pb+IO_FCB_VREF,volumeRefNumFor(open.path));memory.writeLong(pb+IO_FCB_CLUMP,0);memory.writeLong(pb+IO_FCB_PARENT,parentDirectoryId(open.path));}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}

	/**
	 * The refNum of the {@code index}th open fork (1-based) for indexed PBGetFCBInfo, 0 past the end. The FCB table holds every
	 * open fork in refNum order, the resource forks the Resource Manager opened included; a non-zero {@code volume} counts only
	 * the forks on that volume. Acrobat Reader 5 finds its plug-ins' language files (and so their dialog and panel names) this way.
	 */
	private int indexedControlBlock(int index,int volume){
		java.util.TreeSet<Integer> references=new java.util.TreeSet<Integer>(files.keySet());
		if(resourceFileReferences!=null)references.addAll(resourceFileReferences.get());
		File wanted=null;
		if(volume!=0)try{wanted=namedVolume(volume);if(wanted==null)return 0;wanted=wanted.getCanonicalFile();}catch(IOException e){return 0;}
		int at=0;
		for(int reference:references){
			OpenFile open=files.get(reference);File path=open!=null?open.path:resourceFileLookup==null?null:resourceFileLookup.apply(reference);
			if(path==null)continue;
			if(wanted!=null)try{File root=namedVolume(volumeRefNumFor(path));if(root==null||!root.getCanonicalFile().equals(wanted))continue;}catch(IOException e){continue;}
			if(++at==index)return reference;
		}
		return 0;
	}

	// ------------------------------------------------------------------ cores of the HFS Plus API (FileSystem68k's FSRef calls)

	/**
	 * The object called {@code macName} in {@code directory}: the entry that answers to that Mac name (a '/' in it is the host's
	 * ':', a MacBinary container answers to its header name, a host name shown under another spelling to its program name), else
	 * the host file a new object of that name gets. Null for a name no object can have (empty, with ':').
	 */
	File childNamed(File directory,String macName) throws IOException {
		if(macName!=null)macName=hostNul(macName);
		if(root==null||directory==null||macName==null||macName.isEmpty()||macName.indexOf(':')>=0||unsafe(macName))return null;
		File result=resolveHostComponent(directory,windowsName(macName.replace('/',':')));
		if(!result.exists()){File container=macBinaryNamed(directory,macName);if(container!=null)result=container;
			else{File[] entries=directory.listFiles();if(entries!=null)for(File entry:entries)if(!hiddenFromCatalog(entry)&&sameMacName(programName(entry.getName()),macName)){result=entry;break;}}} // a host name the program knows by another spelling
		File canonical=result.getCanonicalFile();if(volumeIndexFor(canonical)<0)throw new SecurityException("path outside mounted volumes");return canonical;
	}
	/**
	 * The Unicode name the HFS Plus calls report for {@code path}: a volume's name for its root, a MacBinary container's header
	 * name, else the host name itself ('/' for the host's ':'). Unlike {@link #programName} no re-reading in other encodings:
	 * a Unicode name need not fit the program's script ("Data é" stays so for a Japanese program).
	 */
	String catalogNameOf(File path) throws IOException {
		if(isVolumeRoot(canonical(path)))return volumeNameFor(path);
		ResourceFile68k.MacBinary mb=path.isFile()?macBinaryFor(path):null;if(mb!=null&&mb.nameBytes.length>0)return encoding.decode(mb.nameBytes);
		String name=path.getName();if(File.separatorChar=='\\')name=decodeWindowsName(name);return name.replace(':','/');
	}
	/** {data, resource} fork lengths as GetCatInfo reports them. */
	long[] forkLengthsOf(File path) throws IOException {return path.isFile()?forkLengths(path):new long[]{0,0};}
	/** A folder's entries as indexed GetCatInfo walks them (catalog order, no host-only items; the Sandbox asked first). */
	File[] catalogListing(File directory) throws IOException {return sortedEntries(directory);}
	/**
	 * Exchanges the contents of two files (PBExchangeFiles, FSExchangeObjects): the host files trade names, their sidecars with
	 * them, and each name keeps the Finder information it had. Open forks keep reading the data they had open. A result code.
	 */
	int exchangeObjects(File a,File b) throws IOException {
		if(a==null||b==null||!a.exists()||!b.exists())return FNF_ERR;if(!a.isFile()||!b.isFile())return NOT_A_FILE_ERR;
		if(canonical(a).equals(canonical(b)))return NO_ERR;if(onLockedVolume(a)||onLockedVolume(b))return WPR_ERR;if(metadataFor(a).locked||metadataFor(b).locked)return F_LCKD_ERR;
		Sandbox.checkFile(a,Sandbox.Access.WRITE,requester);Sandbox.checkFile(b,Sandbox.Access.WRITE,requester);
		byte[] infoA=persistent?FinderInfo68k.stored(a):null,infoB=persistent?FinderInfo68k.stored(b):null;
		File temp=new File(a.getParentFile(),".hz-exchange-"+System.nanoTime());
		if(!a.renameTo(temp))return IO_ERR;ResourceFile68k.AppleDouble.moveWith(a,temp);
		if(!b.renameTo(a)){temp.renameTo(a);ResourceFile68k.AppleDouble.moveWith(temp,a);return IO_ERR;}ResourceFile68k.AppleDouble.moveWith(b,a);
		if(!temp.renameTo(b))return IO_ERR;ResourceFile68k.AppleDouble.moveWith(temp,b);
		if(infoA!=null)FinderInfo68k.write(a,infoA);if(infoB!=null)FinderInfo68k.write(b,infoB);
		String keyA=canonical(a).getPath(),keyB=canonical(b).getPath();Metadata metaA=metadata.get(keyA),metaB=metadata.get(keyB);removeState(keyA);removeState(keyB);
		if(metaA!=null)metadata.put(keyA,metaA);if(metaB!=null)metadata.put(keyB,metaB); // names keep their Finder info and file numbers; cached fork copies are dropped
		return NO_ERR;
	}
	/** Empties a fork of a closed file (FSDeleteFork: the data and resource forks of an HFS file always exist); a result code. */
	int emptyFork(File path,boolean resourceFork) throws IOException {
		if(path==null||!path.exists())return FNF_ERR;if(!path.isFile())return NOT_A_FILE_ERR;if(metadataFor(path).locked)return F_LCKD_ERR;if(isBusy(path))return F_BSY_ERR;
		Sandbox.checkFile(path,Sandbox.Access.WRITE,requester);String key=canonical(path).getPath();
		if(resourceFork){if(forkLengths(path)[1]>0)ResourceFile68k.writeForkBytes(path,new byte[0],ResourceFile68k.Storage.NONE);ResourceFork fork=resourceForks.remove(key);if(fork!=null)fork.storage.delete();return NO_ERR;}
		ResourceFile68k.MacBinary mb=persistent?macBinaryFor(path):null;
		if(mb!=null){mb.dataFork=new byte[0];mb.write(path);}else try(RandomAccessFile file=new RandomAccessFile(path,"rw")){file.setLength(0);}
		File data=dataForks.remove(key);if(data!=null)data.delete();HostView view=views.remove(key);if(view!=null&&view.storage!=null)view.storage.delete();
		return NO_ERR;
	}
	/** The refNums of the forks opened through the File Manager, in FCB order (FSGetForkCBInfo's iteration). */
	List<Integer> openForkReferences(){List<Integer> result=new ArrayList<Integer>();for(Map.Entry<Integer,OpenFile> entry:files.entrySet())if(!entry.getValue().attached)result.add(entry.getKey());return result;}
	/** An open fork: its file, whether it is the resource fork and writable, its mark and length. */
	/** The open file behind a refNum (an identity: a file closed and opened again under the same refNum is another). */
	Object openFile(int reference){return files.get(reference);}
	static final class ForkState {File path;boolean resourceFork,writable;long mark,length;}
	/** The state of the fork open as {@code reference} (a Resource Manager refNum too), or null. */
	ForkState forkState(int reference){OpenFile open=openFileFor(reference);if(open==null)return null;ForkState s=new ForkState();s.path=open.path;s.resourceFork=open.resourceFork;s.writable=open.writable;try{s.mark=open.file.getFilePointer();s.length=open.file.length();}catch(IOException e){return null;}return s;}
	/**
	 * Moves the mark of an open fork, positionMode as the classic calls (fsAtMark 0, fsFromStart 1, fsFromLEOF 2, fsFromMark 3) with
	 * a 64-bit offset. Past the end only when {@code pastEnd} (a write then extends the fork there); otherwise the mark stops at
	 * the end with eofErr. A result code.
	 */
	int seekFork(int reference,int mode,long offset,boolean pastEnd) throws IOException {OpenFile open=openFileFor(reference);if(open==null)return RF_NUM_ERR;long length=open.file.length(),mark=open.file.getFilePointer(),target=(mode&3)==0?mark:(mode&3)==1?offset:(mode&3)==2?length+offset:mark+offset;if(target<0)return POS_ERR;if(target>length&&!pastEnd){open.file.seek(length);return EOF_ERR;}open.file.seek(target);return NO_ERR;}
	/** Sets the length of an open fork (the mark moves back to a shorter end); a result code. */
	int setForkLength(int reference,long length) throws IOException {OpenFile open=openFileFor(reference);if(open==null)return RF_NUM_ERR;if(!mayWrite(open))return WR_PERM_ERR;if(length<0)return POS_ERR;open.file.setLength(length);if(open.file.getFilePointer()>length)open.file.seek(length);open.dirty=true;return NO_ERR;}

	private boolean open(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;try {File path=resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical);result=openPath(pb,path,false);traceOpen(pb,hierarchical,path,result);}catch(SecurityException e){result=securityError(e);}catch(IOException e){if(Mac68kApi.TRACE)e.printStackTrace();result=IO_ERR;}return finish(cpu,pb,result);}
	private void traceOpen(int pb,boolean hierarchical,File path,int result) throws M68kCpuException {if(!Mac68kApi.TRACE)return;int name=memory.readLong(pb+IO_NAME_PTR);System.err.println("[file] open vRefNum="+(short)memory.readWord(pb+IO_VREF_NUM)+" dirID="+(hierarchical?memory.readLong(pb+48):0)+" name="+(name==0?"":encoding.decode(memory.readBytes(name+1,memory.readByte(name))))+" -> "+path+" ("+result+")");}
	private int openPath(int pb,File path,boolean resourceFork) throws IOException,M68kCpuException {int reference=openFork(path,resourceFork,memory.readByte(pb+IO_PERMISSION));if(reference<0)return reference;memory.writeWord(pb+IO_REF_NUM,reference);return NO_ERR;}
	/** Opens a fork of {@code path} with ioPermssn {@code permission} (1 = read only, anything else read/write); the refNum (> 0) or a result code (< 0). PBHOpenDF/PBHOpenRF and FSOpenFork share it. */
	int openFork(File path,boolean resourceFork,int permission) throws IOException {if(path==null)return root==null?NSV_ERR:BD_NAM_ERR;if(!path.isFile())return FNF_ERR;if(onLockedVolume(path)){if(permission==0)permission=1;/* fsCurPerm: what the volume allows */else if(permission!=1)return WPR_ERR;}if(Sandbox.readOnlyFor(path,requester)){if(permission==0)permission=1;/* opened read-only (the launcher): like a locked file */else if(permission!=1)return PERM_ERR;}boolean askAtWrite=permission!=1&&!Sandbox.allowedWithoutAsking(path,Sandbox.Level.WRITE,requester);/* many programs open their files read-write only to read them: asked at the first write */Sandbox.checkFile(path,permission==1||askAtWrite?Sandbox.Access.READ:Sandbox.Access.READ_WRITE,requester);Metadata value=metadataFor(path);if(value.locked&&(permission&3)==0)permission=1;/* fsCurPerm: what the locked file allows */if(permission!=1&&value.locked)return F_LCKD_ERR;int reference=allocateReference();File backing=resourceFork?resourceForkFor(path).storage:dataForkFor(path);if((permission&3)==0&&!backing.canWrite())permission=1;/* fsCurPerm (low two bits 0; a block with a stray byte there, like Dark Forces' PBHOpenDF, still means that) on a file the host keeps read-only (a game's data copied from its CD): what the file allows */HostView view=resourceFork?null:views.get(canonical(path).getPath());if(view!=null&&view.storage==null)view=null;RandomAccessFile file=new RandomAccessFile(backing,permission==1?"r":"rw");OpenFile open=new OpenFile(file,path,permission!=1,resourceFork);open.askAtWrite=askAtWrite;open.view=view;if(view!=null)view.open++;else if(!resourceFork&&file.length()==0)open.fresh=true;files.put(reference,open);if(fcbListener!=null)fcbListener.opened(reference,path,resourceFork,permission!=1,file.length());return reference;}
	/** Opens a read-only reference on a byte image (a resource fork image handed to a program's own loader). */
	int openBytes(byte[] contents) throws IOException {File temp=TempFiles.createFile("hyperzebra-fork-",".bin");Files.write(temp.toPath(),contents);int reference=allocateReference();files.put(reference,new OpenFile(new RandomAccessFile(temp,"r"),temp,false,false));return reference;}
	/** Reads {@code length} bytes at {@code offset} of an open reference (the Resource Manager's own reads); null when the reference is unknown. */
	byte[] readReference(int reference,long offset,int length) throws IOException {OpenFile open=files.get(reference);if(open==null)return null;byte[] result=new byte[Math.max(0,length)];open.file.seek(offset);int actual=0;while(actual<result.length){int count=open.file.read(result,actual,result.length-actual);if(count<0)break;actual+=count;}return actual==result.length?result:java.util.Arrays.copyOf(result,actual);}
	/** Length of an open file (-1: not open here). */
	long referenceLength(int reference){OpenFile open=files.get(reference);if(open==null)return -1;try{return open.file.length();}catch(IOException e){return -1;}}
	/** Writes at {@code offset} (-1: the current mark) of an open file; false when it is not open or not writable. */
	boolean writeReference(int reference,long offset,byte[] data) throws IOException {OpenFile open=files.get(reference);if(open==null||!mayWrite(open))return false;if(offset>=0)open.file.seek(offset);open.file.write(data);open.dirty=true;return true;}
	void closeReference(int reference){OpenFile open=files.remove(reference);if(open==null)return;try{open.file.close();}catch(IOException ignored){}open.path.delete();if(fcbListener!=null)fcbListener.closed(reference);}
	private boolean close(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),result=NO_ERR;OpenFile open=files.get(reference);if(open==null)result=RF_NUM_ERR;else try{closeOpenFile(reference,open);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** Closes {@code open}: the handle, then what it wrote goes to the host - a resource fork, a MacBinary data fork, a converted copy ({@link #writeBack}) or a new text/picture file ({@link #convertNewFile}). */
	/** Only a fork written or resized through this handle goes back into the file: macBinify (HyperBBS) and Prototyping Toolkit's FileCopy
	 *  open their source's resource fork with fsCurPerm and only read it, and rewriting it on Close moved the host file's modification date. */
	private void closeOpenFile(int reference,OpenFile open) throws IOException {files.remove(reference);releaseRangeLocks(reference);open.file.close();if(!open.attached&&fcbListener!=null)fcbListener.closed(reference);if(persistent&&open.resourceFork&&open.writable){if(open.dirty){ResourceFork fork=resourceForkFor(open.path);ResourceFile68k.writeForkBytes(open.path,Files.readAllBytes(fork.storage.toPath()),ResourceFile68k.Storage.NONE);}}else if(open.view!=null){open.view.open--;if(open.writable&&open.dirty)writeBack(open);}else if(open.writable&&!open.resourceFork){ResourceFile68k.MacBinary mb=persistent?macBinaryFor(open.path):null;if(mb!=null){if(open.dirty){mb.dataFork=Files.readAllBytes(dataForkFor(open.path).toPath());mb.write(open.path);}}else if(open.dirty&&(open.fresh||untypedNewFiles.contains(canonical(open.path).getPath())))convertNewFile(open.path);else if(open.dirty&&open.path.isFile()&&open.path.length()<=TextFile68k.LIMIT&&(typeOf(open.path)==TEXT_TYPE||typeOf(open.path)==0))ProgramFiles.record(canonical(open.path).getPath(),hostStamp(open.path));}} // a text file the program rewrote as it is (no converted copy) stays its bytes
	private boolean read(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),requested=memory.readLong(pb+IO_REQ_COUNT),buffer=memory.readLong(pb+IO_BUFFER),result=NO_ERR;OpenFile open=openFileFor(reference);memory.writeLong(pb+IO_ACT_COUNT,0);
		// ioPosMode bit 7 = newline mode: the read stops after the newline character in the mode's high byte (HyperCard's
		// "read from file until return" reads 512 bytes in newline mode $0D80; Roulette v4.5 compares the first line with a key)
		int posMode=memory.readWord(pb+IO_POS_MODE);boolean newline=(posMode&0x80)!=0;int newlineChar=posMode>>>8&0xff;
		if(open==null)result=RF_NUM_ERR;else if(requested<0||(requested>0&&buffer==0))result=PARAM_ERR;else try {result=position(open.file,pb);long start=open.file.getFilePointer();if(result==NO_ERR&&isRangeLocked(open,reference,start,start+requested))result=F_LCKD_ERR;if(result==NO_ERR){int actual=0;boolean stopped=false;byte[] chunk=new byte[Math.min(8192,Math.max(1,requested))];while(actual<requested&&!stopped){int count=open.file.read(chunk,0,Math.min(chunk.length,requested-actual));if(count<0)break;int used=count;if(newline)for(int i=0;i<count;i++)if((chunk[i]&0xff)==newlineChar){used=i+1;stopped=true;break;}for(int i=0;i<used;i++)memory.writeByte(buffer+actual+i,chunk[i]);actual+=used;if(stopped)open.file.seek(start+actual);}memory.writeLong(pb+IO_ACT_COUNT,actual);memory.writeLong(pb+IO_POS_OFFSET,(int)open.file.getFilePointer());if(actual<requested&&!stopped)result=EOF_ERR;}}
		catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean write(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),requested=memory.readLong(pb+IO_REQ_COUNT),buffer=memory.readLong(pb+IO_BUFFER),result=NO_ERR;OpenFile open=openFileFor(reference);memory.writeLong(pb+IO_ACT_COUNT,0);
		if(open==null)result=RF_NUM_ERR;else if(!mayWrite(open))result=WR_PERM_ERR;else if(requested<0||(requested>0&&buffer==0))result=PARAM_ERR;else try {result=position(open.file,pb);long start=open.file.getFilePointer();if(result==NO_ERR&&isRangeLocked(open,reference,start,start+requested))result=F_LCKD_ERR;if(result==NO_ERR){byte[] chunk=new byte[Math.min(8192,Math.max(1,requested))];int actual=0;while(actual<requested){int count=Math.min(chunk.length,requested-actual);for(int i=0;i<count;i++)chunk[i]=(byte)memory.readByte(buffer+actual+i);open.file.write(chunk,0,count);actual+=count;}open.dirty=true;memory.writeLong(pb+IO_ACT_COUNT,actual);memory.writeLong(pb+IO_POS_OFFSET,(int)open.file.getFilePointer());}}
		catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}

	private boolean create(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;try {File path=resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical);result=createFile(path);traceOpen(pb,hierarchical,path,result);}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** Creates the empty file {@code path} (PBHCreate, FSCreateFileUnicode); a result code. */
	int createFile(File path) throws IOException {if(path==null)return root==null?NSV_ERR:BD_NAM_ERR;if(path.exists())return DUP_FN_ERR;if(onLockedVolume(path))return WPR_ERR;if(path.getParentFile()==null||!path.getParentFile().isDirectory())return DIR_NF_ERR/* a folder of the path is missing (Rinaldi's FullWrite: "Folder not found") */;Sandbox.checkFile(path,Sandbox.Access.CREATE,requester);if(path.createNewFile()){metadataFor(path);if(persistent)createdFiles.add(canonical(path).getPath());return NO_ERR;}return path.getParentFile().canWrite()?IO_ERR:WPR_ERR;}
	private boolean delete(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;try {File path=resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical);result=path==null?(root==null?NSV_ERR:BD_NAM_ERR):deleteObject(path,true)/* PBDelete removes an empty folder too on HFS (Rinaldi's RemoveFolder: "Unknown #-54") */;}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** Deletes a file or an empty folder (a folder only when {@code folders}); a result code. PBDelete, PBHDelete and FSDeleteObject share it. */
	int deleteObject(File path,boolean folders) throws IOException {if(!path.exists())return FNF_ERR;if(onLockedVolume(path))return WPR_ERR;if(metadataFor(path).locked)return F_LCKD_ERR;if(folders)clearHostLeftovers(path);if(isBusy(path)||isStackStandIn(path))return F_BSY_ERR;if(path.isDirectory()&&!folders)return PERM_ERR;Sandbox.checkFile(path,Sandbox.Access.DELETE,requester);String key=path.getCanonicalPath();if(DeletedFiles.delete(path,requester)/* the user's own files go to HyperZebra HD's Deleted Files, not away for good */){ResourceFile68k.AppleDouble.deleteWith(path);removeState(key);return NO_ERR;}return IO_ERR;}
	/** A folder a program sees as empty may still hold the host's own .DS_Store and "._" sidecars; they go before the folder is deleted (else fBsyErr). */
	private void clearHostLeftovers(File folder){if(!folder.isDirectory())return;File[] all=folder.listFiles();if(all==null||all.length==0)return;for(File f:all){String n=f.getName();if(!f.isFile()||!(n.equals(".DS_Store")||n.startsWith("._")))return;}if(!Sandbox.allowFile(folder,Sandbox.Access.DELETE,requester))return;for(File f:all)f.delete();}
	private boolean rename(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;try {File from=resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical),to=from==null?null:newNameIn(from,memory.readLong(pb+IO_MISC));result=from==null||to==null?(root==null?NSV_ERR:BD_NAM_ERR):renameObject(from,to);}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/**
	 * The new name of a rename (ioMisc) as a file beside {@code from}: only its last component counts (FullRename hands over the
	 * whole partial path, ":fix:new name" - resolved below the folder it gave I/O error -36), and a name that differs from the
	 * old one only in case keeps the case asked for (it found the old file itself: dupFNErr).
	 */
	private File newNameIn(File from,int namePointer) throws IOException,M68kCpuException {
		File folder=from.getParentFile();
		if(namePointer==0||folder==null)return null;
		int length=memory.readByte(namePointer);if(length==0)return null;
		byte[] bytes=memory.readBytes(namePointer+1,length);
		int end=bytes.length;if(end>1&&bytes[end-1]==':')end--;
		int colon=-1;for(int i=end-1;i>=0&&colon<0;i--)if(bytes[i]==':')colon=i;
		byte[] leaf=Arrays.copyOfRange(bytes,colon+1,end);
		if(leaf.length==0)return null;
		File to=resolveBytes(folder,leaf);
		if(to!=null&&to.exists()&&canonical(to).equals(canonical(from))){
			String asked=decodeName(leaf);
			if(!unsafe(asked)&&!asked.equals(macNameOf(from)))to=new File(folder,windowsName(asked.replace('/',':')));
		}
		return to;
	}
	/** Renames {@code from} to {@code to} (same folder); a result code. PBHRename and FSRenameUnicode share it. */
	int renameObject(File from,File to) throws IOException {if(!from.exists())return FNF_ERR;if(onLockedVolume(from))return WPR_ERR;if(metadataFor(from).locked)return F_LCKD_ERR;if(to.exists()&&(from.getName().equals(to.getName())||!canonical(to).equals(canonical(from))))return DUP_FN_ERR;/* another spelling of the same name (case only, on a case-insensitive host) renames in place */Sandbox.checkFile(from,Sandbox.Access.WRITE,requester);Sandbox.checkFile(to,Sandbox.Access.WRITE,requester);String oldKey=from.getCanonicalPath();boolean file=from.isFile();if(from.renameTo(to)){if(file)ResourceFile68k.AppleDouble.moveWith(from,to);rebaseState(oldKey,to.getCanonicalPath());if(moveListener!=null)moveListener.accept(new File(oldKey),to.getCanonicalFile());if(file&&untypedNewFiles.contains(to.getCanonicalPath()))convertNewFile(to)/* a new text file renamed to its .txt name (a safe save) is typed by the name now */;return NO_ERR;}return IO_ERR;}
	/** GetFileInfo/HGetFileInfo (and FSpGetFInfo through it): a folder is fnfErr, as on HFS (only file records are looked up) -
	 *  Apple's HFSCopy finds the folders of a path it has to create that way, and with every folder "a file" it created none. */
	private boolean getFileInfo(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;try {File path=fileForInfo(pb,hierarchical);if(path==null||!path.exists()||path.isDirectory())result=FNF_ERR;else {if((short)memory.readWord(pb+IO_DIR_INDEX)<=0)materializeView(path);writeFileInfo(pb,path);result=NO_ERR;}}catch(IndexOutOfBoundsException e){result=FNF_ERR;}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean setFileInfo(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result;try {File path=resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical);if(path==null)result=root==null?NSV_ERR:BD_NAM_ERR;else if(!path.exists()||path.isDirectory())result=FNF_ERR;/* a folder too, as GetFileInfo (cSetFileInfo wrote a file type over a host folder's window rect) */else if(onLockedVolume(path))result=WPR_ERR;else {Sandbox.checkFile(path,Sandbox.Access.WRITE,requester);byte[] finderInfo=new byte[16];for(int i=0;i<finderInfo.length;i++)finderInfo[i]=(byte)memory.readByte(pb+IO_FL_FNDR_INFO+i);Metadata value=metadataFor(path);int oldType=typeOf(path);value.finderInfo=finderInfo;if(persistent)FinderInfo68k.write(path,hostFinderInfo(path,finderInfo));if(untypedNewFiles.contains(canonical(path).getPath()))convertNewFile(path);revertRetypedText(path,oldType);int created=memory.readLong(pb+IO_FL_CR_DAT),modified=memory.readLong(pb+IO_FL_MD_DAT);if(modified!=0){value.modificationDate=modified;setHostModificationDate(path,modified);}if(created!=0&&created!=value.creationDate){value.creationDate=created;if(persistent)setHostCreationDate(path,created);}/* after the modification date: macOS moves a birth time later than the new modification time back to it */result=NO_ERR;}}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean setFileLock(M68kCpu cpu,boolean hierarchical,boolean locked) throws M68kCpuException {int pb=cpu.a[0],result;try {File path=resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical);if(path==null)result=root==null?NSV_ERR:BD_NAM_ERR;else if(!path.exists())result=FNF_ERR;else if(onLockedVolume(path))result=WPR_ERR;else {Sandbox.checkFile(path,Sandbox.Access.WRITE,requester);metadataFor(path).locked=locked;if(persistent&&path.isFile()&&HostFileAttributes68k.locked(path)!=locked)HostFileAttributes68k.setLocked(path,locked);/* the Finder's Locked on the host: the lock outlasts this VM (Rinaldi's SetFinderLock, then EraseFile in another external's VM) */result=NO_ERR;}}catch(SecurityException e){result=securityError(e);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}

	private boolean allocate(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],requested=memory.readLong(pb+IO_REQ_COUNT),reference=(short)memory.readWord(pb+IO_REF_NUM),result=NO_ERR;OpenFile open=files.get(reference);memory.writeLong(pb+IO_ACT_COUNT,0);if(open==null)result=RF_NUM_ERR;else if(!mayWrite(open))result=WR_PERM_ERR;else if(requested<0)result=PARAM_ERR;else {memory.writeLong(pb+IO_ACT_COUNT,requested);}return finish(cpu,pb,result);}
	private boolean getEof(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),result=NO_ERR;OpenFile open=openFileFor(reference);if(open==null)result=RF_NUM_ERR;else try{memory.writeLong(pb+IO_MISC,(int)open.file.length());}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	// A Resource Manager fork (attached, read-only here) takes SetEOF as the read/write path OpenResFile opens on a real Mac would:
	// THINK C 5's project writer lays the fork out itself in the in-memory map (free-space reuse, header lengths) and sets the
	// EOF to the map's end before UpdateResFile. The Resource Manager here rewrites the whole fork from its resources when it
	// updates the file, so the size is its business: the call succeeds and leaves the host file alone.
	private boolean setEof(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),length=memory.readLong(pb+IO_MISC),result=NO_ERR;OpenFile open=openFileFor(reference);if(open==null)result=RF_NUM_ERR;else if(open.attached&&!open.dirty&&length>=0&&(sameLength(open,length)||open.path.canWrite()))result=NO_ERR;else if(!mayWrite(open))result=WR_PERM_ERR;else if(length<0)result=POS_ERR;else try{open.file.setLength(length);if(open.file.getFilePointer()>length)open.file.seek(length);open.dirty=true;}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean getPosition(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),result=NO_ERR;OpenFile open=openFileFor(reference);if(open==null)result=RF_NUM_ERR;else try{memory.writeLong(pb+IO_POS_OFFSET,(int)open.file.getFilePointer());}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean setPosition(M68kCpu cpu) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),result;OpenFile open=openFileFor(reference);if(open==null)result=RF_NUM_ERR;else try{int mode=memory.readWord(pb+IO_POS_MODE)&3,offset=memory.readLong(pb+IO_POS_OFFSET);long target=mode==0?open.file.getFilePointer():mode==1?offset:mode==2?open.file.length()+offset:open.file.getFilePointer()+offset;
		if(target<0)result=POS_ERR;else if(target>open.file.length()){open.file.seek(open.file.length());result=EOF_ERR;}else {open.file.seek(target);result=NO_ERR;}memory.writeLong(pb+IO_POS_OFFSET,(int)open.file.getFilePointer());}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	/** FlushFile/FlushVol. A resource file the Resource Manager holds open is an open fork too: Onslaught 1.0.2 flushes its settings
	 *  file by that refNum after UpdateResFile and reported "an internal error has occurred (Toolbox Code: -51)" at every start. */
	private boolean flush(M68kCpu cpu,boolean fileOnly) throws M68kCpuException {int pb=cpu.a[0],reference=(short)memory.readWord(pb+IO_REF_NUM),result=NO_ERR;if(fileOnly){OpenFile open=files.get(reference);if(open==null)result=isResourceFileReference(reference)?NO_ERR:RF_NUM_ERR;else try{open.file.getFD().sync();if(open.view!=null&&open.writable&&open.dirty)writeBack(open);}catch(IOException e){result=IO_ERR;}}else try{syncViews(true);}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}

	private boolean getVolume(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],result=root==null?NSV_ERR:NO_ERR;if(result==NO_ERR)try{File d=volumeRootFor(defaultVolumeRef);WorkingDirectory working=workingDirectories.get(defaultVolumeRef);if(working!=null&&directories.get(working.directoryId)!=null)d=directories.get(working.directoryId);int output=memory.readLong(pb+IO_NAME_PTR);/* the volume's name even when the default is a working directory (only ioVRefNum names the folder): SoftWindows puts it in front of the partial path the installer saved (":SoftWindows:SoftWindows Hard Disk File C"), and with the folder's name drive C: was not found */if(output!=0)writePascal(output,volumeNameFor(d));memory.writeWord(pb+IO_VREF_NUM,working!=null?defaultVolumeRef:volumeRefNumFor(d));/* HGetVol also fills ioWDProcID/ioWDVRefNum/ioWDDirID - PBGetVol ($A014) not: the 1984 Font Mover passes a 24-byte block on the stack, and its return address became ioWDProcID 0 */if(hierarchical){memory.writeLong(pb+28,0);memory.writeWord(pb+32,volumeRefNumFor(d));memory.writeLong(pb+48,directoryId(d));}}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean getVolumeInfo(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0],index=(short)memory.readWord(pb+IO_VOL_INDEX),result=root==null||index>volumes.size()?NSV_ERR:NO_ERR;if(result==NO_ERR)try {File volume;if(index>0)volume=volumes.get(index-1);else{int namePtr=memory.readLong(pb+IO_NAME_PTR);int ref=(short)memory.readWord(pb+IO_VREF_NUM);volume=null;/* a negative ioVolIndex names the volume by the pathname in ioNamePtr first, whatever ioVRefNum holds (Netscape 2.02 saves its bookmarks, then asks for "HyperZebra HD:" with vRefNum -32768) */if((ref==0||index<0)&&namePtr!=0&&memory.readByte(namePtr)>0){String n=encoding.decode(memory.readBytes(namePtr+1,memory.readByte(namePtr)));int colon=n.indexOf(':');if(colon>0)n=n.substring(0,colon);int named=volumeIndexNamed(n);if(named>=0)volume=volumes.get(named);
					/* a full pathname ("Disk:...") names its volume: one not mounted is nsvErr, not ioVRefNum's volume. Apple Installer 3 asks
					   for its next floppy so ("Win Install 2:") and took the startup disk it was given for that disk ("the wrong disk") */
					if(volume==null&&colon>0)return finish(cpu,pb,NSV_ERR);}if(volume==null)volume=namedVolume(ref);if(volume==null)return finish(cpu,pb,NSV_ERR);}int output=memory.readLong(pb+IO_NAME_PTR);if(output!=0)writePascal(output,volumeNameFor(volume));memory.writeWord(pb+IO_VREF_NUM,volumeRefNumFor(volume));File root=volume;BasicFileAttributes attributes=Files.readAttributes(root.toPath(),BasicFileAttributes.class);int created=macDate(attributes.creationTime().toMillis()),modified=macDate(attributes.lastModifiedTime().toMillis());if(Boolean.getBoolean("hz68k.bbdebug"))System.err.println("[bbdebug] volume list "+root);File[] entries=root.listFiles();if(Boolean.getBoolean("hz68k.bbdebug"))System.err.println("[bbdebug] volume done "+root);int fileCount=0;if(entries!=null)for(File entry:entries)if(entry.isFile())fileCount++;
		memory.writeLong(pb+IO_V_CR_DATE,created);memory.writeLong(pb+IO_V_LS_MOD,modified);boolean lockedVolume=volumeLocked(volumeIndexFor(volume));memory.writeWord(pb+IO_V_ATTRIB,lockedVolume?0x0080:0);/* bit 7: locked by hardware - a disk image mounted read-only, as a CD */memory.writeWord(pb+IO_V_NUM_FILES,Math.min(0xffff,fileCount));memory.writeWord(pb+42,0);memory.writeWord(pb+44,512);int blockSize=volumeBlockSize(volumeTotalSpace(root));memory.writeWord(pb+46,volumeBlocks(volumeTotalSpace(root),blockSize));memory.writeLong(pb+IO_V_AL_BLK_SIZE,(int)blockSize);memory.writeLong(pb+IO_V_CLUMP_SIZE,(int)blockSize);memory.writeWord(pb+56,0);memory.writeLong(pb+IO_V_NEXT_FILE,fileCount+2);memory.writeWord(pb+IO_V_FREE_BLOCKS,lockedVolume?0:volumeBlocks(root.getUsableSpace(),blockSize));if(hierarchical){/* HGetVInfo extras (HVolumeParam is 122 bytes; a plain PBGetVInfo block is only 64 and writing past it smashes the caller's frame).
			   ioVSigWord must read 'BD': installers reject any other signature as "not HFS" and leave the block size at 0 (StuffIt InstallerMaker then divides by it). */
			int dirCount=0;if(entries!=null)for(File entry:entries)if(entry.isDirectory())dirCount++;
			memory.writeWord(pb+64,0x4244);memory.writeWord(pb+66,driveOf(volumeIndexFor(volume)));memory.writeWord(pb+68,driverRefOf(volumeIndexFor(volume)));/* ioVDrvInfo/ioVDRefNum: host volumes hang off one fixed-disk driver; image drivers may ask it for a DrvSts before mounting */memory.writeWord(pb+70,0);memory.writeLong(pb+72,0);memory.writeWord(pb+76,0);memory.writeLong(pb+78,0);memory.writeLong(pb+82,fileCount);memory.writeLong(pb+86,dirCount);for(int i=90;i<122;i+=2)memory.writeWord(pb+i,0);if(blessedFolder!=null&&blessedFolder.isDirectory()&&canonical(blessedFolder.getParentFile()).equals(canonical(volume)))memory.writeLong(pb+90,directoryId(blessedFolder));/* ioVFndrInfo[0]: the blessed System Folder, how pre-FindFolder programs locate it (After Dark's installer) */}}catch(IOException e){result=IO_ERR;}return finish(cpu,pb,result);}
	private boolean setVolume(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {int pb=cpu.a[0];if(root==null)return finish(cpu,pb,NSV_ERR);try{int ref=(short)memory.readWord(pb+IO_VREF_NUM),dirId=hierarchical?memory.readLong(pb+48):0;int namePtr=memory.readLong(pb+IO_NAME_PTR);
		if(namePtr!=0&&memory.readByte(namePtr)>0){String full=encoding.decode(memory.readBytes(namePtr+1,memory.readByte(namePtr))),n=full;if(n.endsWith(":"))n=n.substring(0,n.length()-1);int index=volumeIndexNamed(n);if(index>=0){/* the bare volume name with a working directory on that volume is what GetVol handed out (the volume's name + the WD's
				   refNum): GetVol(name,ref) ... SetVol(name,ref) restores that folder, not the volume's root. ResEdit does so after reading its
				   preferences, then opens its own file by name to check itself ("ResEdit appears to be damaged" when that fails) */
				WorkingDirectory working=n.equals(full)&&dirId==0?workingDirectories.get(ref):null;File folder=working==null?null:directories.get(working.directoryId);if(folder!=null&&volumeIndexFor(folder)==index){defaultVolumeRef=ref;return finish(cpu,pb,NO_ERR);}
				defaultVolumeRef=-(index+1);return finish(cpu,pb,NO_ERR);}if(n.indexOf(':')>=0){/* resolve the untrimmed name: "::" is the parent folder (Descent II does ":Players:" then "::"); trimmed, it would read ":" = here */File d;try{d=resolveName(n.startsWith(":")?directoryFor(dirId,ref):volumeRootFor(ref),n.startsWith(":")?full:n);}catch(SecurityException e){/* a volume or path that is not mounted: nsvErr, not a crash */if(Boolean.getBoolean("hz68k.trace"))System.err.println("[file] SetVol \""+full+"\": "+e.getMessage());return finish(cpu,pb,NSV_ERR);}if(d!=null&&d.isDirectory()){defaultVolumeRef=workingDirectoryRefFor(d);return finish(cpu,pb,NO_ERR);}return finish(cpu,pb,NSV_ERR);}
			/* A name without colons that is not a mounted volume is a partial pathname: the File Manager then goes by ioVRefNum/ioDirID.
			   Some resource editors restore their default directory with SetVol(name, wdRefNum) using exactly what GetVol returned.
			   HSetVol with a directory ID: the folder of that name in it - Alpha 6.0 restores its own folder from its FSSpec
			   (HSetVol("alpha6.0", vRefNum, parID)); taking the parent, it could not find ":Tcl:SystemCode:AlphaBits.tcl".
			   Without one, the folder of that name in the default directory: NASCAR Racing goes HSetVol(full path of its Data folder)
			   then HSetVol("SOUND", 0, 0) and opens SOUND.DAT by name ("the sound effects could not be loaded" while it stayed in Data). */
			if(hierarchical){File base=directoryFor(dirId,ref);File d=null;try{d=base==null?null:resolveName(base,":"+n);}catch(SecurityException e){d=null;}
				if(d!=null&&d.isDirectory()){defaultVolumeRef=workingDirectoryRefFor(d);return finish(cpu,pb,NO_ERR);}}}
		if(dirId!=0&&dirId!=2){File d=directoryById(dirId);if(d==null)return finish(cpu,pb,DIR_NF_ERR);defaultVolumeRef=workingDirectoryRefFor(d);return finish(cpu,pb,NO_ERR);}
		if(ref==0)return finish(cpu,pb,NO_ERR);if(volumeRootFor(ref)==null)return finish(cpu,pb,NSV_ERR);defaultVolumeRef=ref;return finish(cpu,pb,NO_ERR);}catch(IOException e){return finish(cpu,pb,IO_ERR);}}
	private void writeCatalogInfo(int pb,File path) throws IOException,M68kCpuException {Metadata value=metadataFor(path);int parentId=parentDirectoryId(path);if(path.isFile()){writeFileInfo(pb,path);memory.writeLong(pb+80,value.backupDate);for(int i=0;i<16;i++)memory.writeByte(pb+84+i,value.extendedFinderInfo[i]);memory.writeLong(pb+100,parentId);memory.writeLong(pb+104,0);return;}memory.writeByte(pb+IO_FL_ATTRIB,0x10|(value.locked||!path.canWrite()?1:0));memory.writeByte(pb+31,0);for(int i=0;i<16;i++)memory.writeByte(pb+32+i,value.finderInfo[i]);if((value.finderInfo[8]&0x04)!=0&&!new File(path,"Icon\r").exists())memory.writeByte(pb+40,value.finderInfo[8]&0xfb);/* kHasCustomIcon without the "Icon\r" file that holds the icon (host folders carry stray flag bits): the Finder opens that file for such a folder and gives up with fnfErr */memory.writeLong(pb+48,directoryId(path));File[] entries=catalogEntries(path);memory.writeWord(pb+52,entries==null?0:Math.min(0xffff,entries.length));/* ioDrNmFls counts what an indexed GetCatInfo will hand out (no .DS_Store, sidecars, packages): After Dark's installer walks the System Folder from that count downwards and gives up at the first -43 */memory.fill(pb+54,18,0);memory.writeLong(pb+72,value.creationDate);memory.writeLong(pb+76,value.modificationDate);memory.writeLong(pb+80,value.backupDate);for(int i=0;i<16;i++)memory.writeByte(pb+84+i,value.extendedFinderInfo[i]);memory.writeLong(pb+100,parentId);/* a DirInfo ends at ioDrParID (104 bytes): no ioFlClpSiz, SuperPaint 3.0's Save keeps one on its stack (smashed return address) */}
	private File directoryFor(int directoryId,int volumeReference) throws IOException {if(root==null)return null;if(directoryId==2)return volumeRootFor(volumeReference);if(directoryId!=0)return directoryById(directoryId);/* vRefNum 0 = the default directory (SetVol / app folder at launch), which may itself be a working directory */if((short)volumeReference==0)volumeReference=defaultVolumeRef;WorkingDirectory working=workingDirectories.get((int)(short)volumeReference);return working==null?volumeRootFor(volumeReference):directories.get(working.directoryId);}
	private int directoryId(File directory) throws IOException {File canonical=canonical(directory);if(isVolumeRoot(canonical))return 2;Integer known=directoryIds.get(canonical);if(known!=null)return known;int result=catalogId(canonical);directories.put(result,canonical);directoryIds.put(canonical,result);return result;}
	/**
	 * A catalog node ID for a folder that stays the same from one session to the next, as HFS's does (programs keep {vRefNum,
	 * dirID} of a folder in their preferences - MacBeat's BMS root folder was "not found" at the next launch when IDs were
	 * handed out in the order folders were met): the folder's host inode gets a small number of its own ({@link CatalogIds}).
	 * Files, and folders without an inode, take the next number of this session.
	 */
	private int catalogId(File canonical){
		long inode=inodeOf(canonical);int id=inode<0?-1:CatalogIds.idFor(inode);
		return id>0?id:sessionCatalogId();
	}
	private int sessionCatalogId(){return nextCatalogId++;}
	private static long inodeOf(File file){
		try{Object key=Files.readAttributes(file.toPath(),BasicFileAttributes.class).fileKey();if(key==null)return -1;
			String k=key.toString();int at=k.indexOf("ino=");if(at<0)return -1;int end=at+4;while(end<k.length()&&Character.isDigit(k.charAt(end)))end++;
			return Long.parseLong(k.substring(at+4,end));}
		catch(IOException|RuntimeException e){return -1;}
	}
	/**
	 * Folder IDs kept across sessions: host inode -> a number of its own, below {@link #SESSION_IDS} (the session counter's
	 * range), in CatalogIDs.txt of the state directory, shared by every VM of the JVM and, under a file lock, by other running
	 * copies. The numbers stay small like real HFS IDs: REALbasic programs write a folder's ID with Str(), which keeps 7
	 * significant digits - an inode came out as "2.578982e+8", and 何かon林檎's shaped-window WDEF read that back as dirID 2,
	 * found no mask and left every window rectangular.
	 */
	static final class CatalogIds {
		private static final int FIRST=100;
		private static final Map<Long,Integer> byInode=new HashMap<Long,Integer>();
		private static final Map<Integer,Long> byId=new HashMap<Integer,Long>();
		private static long readOffset;private static int next=FIRST;
		private static File file(){return new File(AppPaths.stateDirectory(),"CatalogIDs.txt");}
		/** The folder ID of a host inode, a new one when it has none yet; -1 when the table cannot be kept. */
		static synchronized int idFor(long inode){
			Integer id=byInode.get(inode);if(id!=null)return id;
			try(RandomAccessFile f=new RandomAccessFile(file(),"rw");java.nio.channels.FileLock lock=f.getChannel().lock()){
				readFrom(f);id=byInode.get(inode);if(id!=null)return id;
				while(byId.containsKey(next))next++;if(next>=SESSION_IDS)return -1;
				int fresh=next;f.seek(f.length());f.write((inode+" "+fresh+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));readOffset=f.length();
				add(inode,fresh);return fresh;}
			catch(IOException|RuntimeException e){return -1;}
		}
		/** The inode a folder ID was given to in this or an earlier session, or -1. */
		static synchronized long inodeOf(int id){
			if(!byId.containsKey(id)){try(RandomAccessFile f=new RandomAccessFile(file(),"rw");java.nio.channels.FileLock lock=f.getChannel().lock()){readFrom(f);}catch(IOException|RuntimeException e){return -1;}}
			Long inode=byId.get(id);return inode==null?-1:inode;
		}
		/** Takes in the lines added since the last read (by this JVM or another copy); a line cut short is read next time. */
		private static void readFrom(RandomAccessFile f) throws IOException {
			long length=f.length();if(length<=readOffset)return;
			byte[] b=new byte[(int)Math.min(length-readOffset,64<<20)];f.seek(readOffset);f.readFully(b);
			int lineStart=0;
			for(int i=0;i<b.length;i++){if(b[i]!='\n')continue;
				String[] parts=new String(b,lineStart,i-lineStart,java.nio.charset.StandardCharsets.US_ASCII).trim().split(" ");lineStart=i+1;
				if(parts.length==2)try{add(Long.parseLong(parts[0]),Integer.parseInt(parts[1]));}catch(NumberFormatException ignored){}}
			readOffset+=lineStart;
		}
		private static void add(long inode,int id){
			if(id<FIRST||id>=SESSION_IDS||byInode.containsKey(inode)||byId.containsKey(id))return;
			byInode.put(inode,id);byId.put(id,inode);if(id>=next)next=id+1;
		}
	}
	/**
	 * Host stamps of files as a program last wrote them itself, its bytes as they are (a new file it created, or one it rewrote
	 * without a converted copy): such a file reads back unchanged, whatever its line ends, in this and later sessions - kept in
	 * ProgramFiles.txt of the state directory (one "stamp path" line per write; the last line of a path counts), shared like
	 * {@link CatalogIds}. 何かon林檎 writes its decrypted dictionaries as CRLF 'TEXT' temp files and parses what it reads back (a
	 * CR-only copy lost every entry); MacSSH's lsh writes known_hosts as an LF 'TEXT' file and, at the next start, got it back
	 * with CR line ends - its "; ACL for host" comment ran to the end of the file ("sexp: Unexpected EOF") and no connection
	 * to a known host started. A file the host changes since gets a new stamp and is converted like any host text file.
	 */
	static final class ProgramFiles {
		/** A table larger than this is rewritten with only the entries whose files are still as recorded. */
		private static final long COMPACT_AT=512*1024;
		private static final Map<String,Long> stamps=new HashMap<String,Long>();
		/** The stamp that marks a file as no longer the program's own bytes (converted to the host form): no host file has it. */
		static final long CONVERTED=Long.MIN_VALUE;
		private static long readOffset;
		private static File file(){return new File(AppPaths.stateDirectory(),"ProgramFiles.txt");}
		/** Whether the program's last write left {@code key} (a canonical path) with this host stamp. */
		static synchronized boolean wrote(String key,long stamp){
			Long known=stamps.get(key);if(known!=null&&known==stamp)return true;
			File f=file();if(!f.isFile())return false;
			try(RandomAccessFile r=new RandomAccessFile(f,"rw");java.nio.channels.FileLock lock=r.getChannel().lock()){readFrom(r);}
			catch(IOException|RuntimeException e){return false;}
			known=stamps.get(key);return known!=null&&known==stamp;
		}
		/** Records the program's write of {@code key}: from now on its bytes are read back as they are while the stamp holds. */
		static synchronized void record(String key,long stamp){
			Long known=stamps.get(key);if(known!=null&&known==stamp||key.indexOf('\n')>=0)return;
			try(RandomAccessFile r=new RandomAccessFile(file(),"rw");java.nio.channels.FileLock lock=r.getChannel().lock()){
				readFrom(r);stamps.put(key,stamp);
				if(r.length()>COMPACT_AT){ // keep what still describes a file as the program left it
					StringBuilder kept=new StringBuilder();
					for(java.util.Iterator<Map.Entry<String,Long>> it=stamps.entrySet().iterator();it.hasNext();){Map.Entry<String,Long> e=it.next();File p=new File(e.getKey());
						if(p.isFile()&&hostStamp(p)==e.getValue())kept.append(e.getValue()).append(' ').append(e.getKey()).append('\n');else it.remove();}
					byte[] b=kept.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);r.setLength(0);r.write(b);readOffset=b.length;return;}
				r.seek(r.length());r.write((stamp+" "+key+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));readOffset=r.length();}
			catch(IOException|RuntimeException e){stamps.put(key,stamp);} // not kept for later sessions, still for this one
		}
		/** Forgets what this JVM read, as a new session starts (tests). */
		static synchronized void forget(){stamps.clear();readOffset=0;}
		/** Takes in the lines added since the last read (by this JVM or another copy); a line cut short is read next time. */
		private static void readFrom(RandomAccessFile r) throws IOException {
			long length=r.length();if(length<readOffset){stamps.clear();readOffset=0;} // compacted by another copy
			if(length<=readOffset)return;
			byte[] b=new byte[(int)Math.min(length-readOffset,64<<20)];r.seek(readOffset);r.readFully(b);
			int lineStart=0;
			for(int i=0;i<b.length;i++){if(b[i]!='\n')continue;
				String line=new String(b,lineStart,i-lineStart,java.nio.charset.StandardCharsets.UTF_8);lineStart=i+1;
				int space=line.indexOf(' ');if(space>0)try{stamps.put(line.substring(space+1),Long.parseLong(line.substring(0,space)));}catch(NumberFormatException ignored){}}
			readOffset+=lineStart;
		}
	}
	/** Catalog IDs of the session counter (files, folders without an inode) start here; kept folder IDs stay below. */
	static final int SESSION_IDS=5000000;
	/** Directory IDs asked for that no folder had this session and a volume walk did not find: not searched again. */
	private final java.util.Set<Integer> missingDirectories=new java.util.HashSet<Integer>();
	/**
	 * The folder a directory ID names. An ID not handed out this session may be one a program remembered from an earlier
	 * session (see {@link #catalogId}): the mounted volumes are walked for the folder with that ID's inode (breadth first,
	 * bounded).
	 */
	private File directoryById(int id) throws IOException {
		File known=directories.get(id);if(known!=null||id<16||id>=SESSION_IDS||missingDirectories.contains(id))return known;
		long inode=CatalogIds.inodeOf(id);
		java.util.ArrayDeque<File> queue=new java.util.ArrayDeque<File>(volumes);int visited=0;
		if(inode>=0)while(!queue.isEmpty()&&visited<20000){File d=queue.poll();visited++;File[] entries=d.listFiles();if(entries==null)continue;
			for(File e:entries){if(!e.isDirectory()||e.getName().startsWith("."))continue;
				File c=e.getCanonicalFile();if(volumeIndexFor(c)<0)continue;
				if(inodeOf(c)==inode&&directoryId(c)==id)return c;queue.add(c);}}
		missingDirectories.add(id);return null;
	}
	/**
	 * Canonical form of a path, remembered for a moment: one catalog call resolves the same path four or five times
	 * (its metadata, MacBinary and fork-copy keys, its parent's directory id), and each resolution walks the path on disk.
	 */
	private final java.util.LinkedHashMap<String,File> canonicals=new java.util.LinkedHashMap<String,File>(256,0.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,File> e){return size()>512;}};
	private long canonicalsCleared;
	private File canonical(File path) throws IOException {
		long now=System.currentTimeMillis();if(now-canonicalsCleared>2000L){canonicals.clear();canonicalsCleared=now;}
		String key=path.getPath();File c=canonicals.get(key);if(c==null){c=path.getCanonicalFile();canonicals.put(key,c);}return c;
	}
	/** Reverse of {@link #directories}: a disk search meets every folder of a volume, and a linear scan per lookup made it quadratic. */
	private final Map<File,Integer> directoryIds=new HashMap<File,Integer>();
	/** Desktop database stand-in (PBDTGetAPPL): the {@code index}-th (0/1 = newest) application file with {@code creator} on the
	 *  mounted volumes, found by walking their folders (limited depth and count; only files carrying a resource fork or MacBinary
	 *  header are inspected, as reading Finder information is costly). Null when there is none. */
	File findApplication(int creator,int index) {return findWithCreator(creator,index,APPLICATION,null,false,CREATOR_WALK_WAIT_MS,null);}
	/** The same on one volume only (its index in the volumes): every volume has its own desktop database (Rinaldi's DocCreator asks
	 *  each volume's, and listed the one program once per volume with that volume's name in front of its path). */
	File findApplication(int creator,int index,int volume) {
		if(volume<0||volume>=volumes.size())return null;
		File app=findWithCreator(creator,index,APPLICATION,volumes.get(volume),false,CREATOR_WALK_WAIT_MS,null);
		try{return app!=null&&volumeIndexFor(app)==volume?app:null;}catch(IOException e){return null;}
	}
	/**
	 * The file whose bundle gives the icons of that creator's files, as the Desktop Database has them: its application,
	 * or else any file with that creator and a bundle (a desk accessory's 'dfil' file, a control panel).
	 */
	File findBundleOwner(int creator) {return findBundleOwner(creator,null);}
	/**
	 * The same, for the icons the Desktop Database hands out (PBDTGetIcon): one walk for both kinds of owner, of the mounted
	 * volumes only (and the program's folder), waited for a short moment. The Finder or UxGetFileIcon asks for every file it
	 * shows; two full waits per creator over every folder the sandbox allows (the whole host disk when it allows all) took about
	 * 4 s for each creator without a program, such as 'ttxt' with no SimpleText. {@code settled}[0] says whether the answer is
	 * final (false: the walk goes on and its answer is there for a later call).
	 */
	File findBundleOwner(int creator,boolean[] settled) {return findWithCreator(creator,1,BUNDLE_OWNER,null,true,DESKTOP_ICON_WAIT_MS,settled);}
	/** What findWithCreator looks for: the application, any file with a bundle, or the application else any bundle file. */
	private static final int APPLICATION=0,ANY_BUNDLE=1,BUNDLE_OWNER=2;
	/** How long a desktop database icon lookup waits for its walk (-Dhz68k.dticonms). */
	private static final long DESKTOP_ICON_WAIT_MS=Long.getLong("hz68k.dticonms",300L);
	/**
	 * What walks of the volumes found, shared by the VMs of this process: by the folders walked, every file with a creator that
	 * is an application or has a bundle ({File, application?, bundle?} by creator) and when the walk ended. One walk answers
	 * every creator: the Desktop Database calls of a program (PBDTGetAPPL at QuickTime's start, PBDTGetIcon for every file
	 * UxGetFileIcon or the Finder shows) asked about one creator after another, and each new creator walked the volumes again -
	 * about 2 s each when a volume is the host disk ('ttxt', 'RUN3', 'RBv2' with no program anywhere). An index is used for
	 * CREATOR_CACHE_MS; the Desktop Database calls that tell of a new program (PBDTAddAPPL, PBDTAddIcon, PBDTReset) drop it.
	 */
	private static final java.util.Map<String,Object[]> CREATOR_CACHE=new java.util.concurrent.ConcurrentHashMap<String,Object[]>();
	private static final long CREATOR_CACHE_MS=30_000;
	/** Forgets what the walks found (a program was added to a Desktop Database). */
	static void forgetCreatorWalks(){CREATOR_CACHE.clear();}
	private File findWithCreator(int creator,int index,int kind,File within,boolean volumesOnly,long waitMs,boolean[] settled) {
		if(settled!=null)settled[0]=true;
		java.util.ArrayDeque<File[]> queue=new java.util.ArrayDeque<File[]>();
		String inside=within==null?null:Sandbox.canonical(within);
		java.util.function.Predicate<File> wanted=f->inside==null||(Sandbox.canonical(f)+File.separator).startsWith(inside.endsWith(File.separator)?inside:inside+File.separator);
		// the program's own folder first: the helpers a program launches by creator usually sit beside it (Adobe PageMill's
		// QuickHelp viewer in its Help folder), and a real Desktop Database knows them however big the volume is
		if(searchFirst!=null&&searchFirst.isDirectory()&&Sandbox.allowedWithoutAsking(searchFirst,requester)&&wanted.test(searchFirst))queue.add(new File[]{searchFirst,null,null,null,null,null,null});
		// then the folder holding it (not a volume, walked anyway): a companion often sits one level up - Analog Helper 68k, in a
		// folder of its own inside "Analog 3.2 ƒ", asks every volume for 'MAG^' (Analog, beside that folder), and the limited
		// walk of the host disk never got that deep: "cannot find a copy of Analog 3 on any mounted disk"
		File near=nearFolder();
		if(near!=null&&Sandbox.allowedWithoutAsking(near,requester)&&wanted.test(near))queue.add(new File[]{near,null,null,null,null,null,null});
		// only where the program may look without asking: BBEdit calls PBDTGetAPPL at start-up, and a permission dialog for
		// listing the whole host disk came up before its first window (and blocked it until answered)
		for(File v:volumes)if(Sandbox.allowedWithoutAsking(v,requester)&&wanted.test(v))queue.add(new File[]{v,null,null,null,null,null,null});
		if(!volumesOnly)for(File r:Sandbox.allowedRoots(requester)){boolean covered=false;for(File[] q:queue)if(Sandbox.canonical(r).startsWith(Sandbox.canonical(q[0])))covered=true;if(!covered&&r.isDirectory()&&wanted.test(r))queue.add(new File[]{r,null,null,null,null,null,null});}
		StringBuilder key=new StringBuilder();for(File[] q:queue)key.append('|').append(q[0].getPath());
		String cacheKey=key.toString();
		Object[] cached=CREATOR_CACHE.get(cacheKey);long now=System.currentTimeMillis();
		if(cached!=null&&now-(Long)cached[1]<CREATOR_CACHE_MS){
			@SuppressWarnings("unchecked") Map<Integer,List<Object[]>> found=(Map<Integer,List<Object[]>>)cached[0];
			File f=pickWithCreator(found,creator,index,kind);if(f==null)return null;
			byte[] info=f.isFile()?FinderInfo68k.read(f):null;if(info!=null&&MacApp68k.u4(info,4)==creator)return f;
			CREATOR_CACHE.remove(cacheKey,cached); // moved or changed since: walked again
		}
		// The walk runs beside the program and is waited for a moment only: a slow or sleeping host volume (an external disk
		// spinning up, a huge folder) held every program's start in the QuickTime extension's PBDTGetAPPL for over a minute.
		// A walk that takes longer answers that there is none and leaves its index in the cache for the next call.
		long deadline=System.nanoTime()+waitMs*1_000_000L;
		java.util.concurrent.Future<Map<Integer,List<Object[]>>> done=CREATOR_WALKS.get(cacheKey);if(done!=null&&done.isDone())CREATOR_WALKS.remove(cacheKey,done); // its index is out of date
		java.util.concurrent.Future<Map<Integer,List<Object[]>>> walk=CREATOR_WALKS.computeIfAbsent(cacheKey,k->WALKERS.submit(()->{
			Map<Integer,List<Object[]>> result=walkCreators(queue);CREATOR_CACHE.put(k,new Object[]{result,System.currentTimeMillis()});return result;}));
		// the program's own folder (then the folder holding it) is walked on its own as well and a match there answers at once:
		// the whole walk (every volume, the host root) often outlasts the wait, and Caliban's isAPPL("RSED") said false with
		// ResEdit right beside the stack
		if(index<=1)for(File[] first:queue){ // ioIndex 0 and 1 both ask for the newest (isAPPL passes 0)
			if(walk.isDone()||!first[0].equals(searchFirst)&&!first[0].equals(near))break;
			java.util.ArrayDeque<File[]> own=new java.util.ArrayDeque<File[]>();own.add(first);
			java.util.concurrent.Future<Map<Integer,List<Object[]>>> ownWalk=WALKERS.submit(()->walkCreators(own));
			try{File result=pickWithCreator(ownWalk.get(Math.max(1,deadline-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS),creator,1,kind);if(result!=null)return result;}
			catch(java.util.concurrent.TimeoutException e){ownWalk.cancel(true);if(settled!=null)settled[0]=false;return null;}
			catch(InterruptedException e){Thread.currentThread().interrupt();if(settled!=null)settled[0]=false;return null;}
			catch(java.util.concurrent.ExecutionException ignored){}
		}
		try{Map<Integer,List<Object[]>> found=walk.get(Math.max(1,deadline-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);CREATOR_WALKS.remove(cacheKey,walk);return pickWithCreator(found,creator,index,kind);}
		catch(java.util.concurrent.TimeoutException e){if(settled!=null)settled[0]=false;return null;}
		catch(InterruptedException e){Thread.currentThread().interrupt();if(settled!=null)settled[0]=false;return null;}
		catch(java.util.concurrent.ExecutionException e){CREATOR_WALKS.remove(cacheKey,walk);return null;}
	}
	/** Walks under way, by the folders they walk; the threads they run on (daemons: a walk stuck in a host folder never keeps
	 *  HyperZebra from quitting). */
	private static final java.util.Map<String,java.util.concurrent.Future<Map<Integer,List<Object[]>>>> CREATOR_WALKS=new java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.Future<Map<Integer,List<Object[]>>>>();
	private static final java.util.concurrent.ExecutorService WALKERS=java.util.concurrent.Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"PBDTGetAPPL walk");t.setDaemon(true);return t;});
	/** How long a program waits for a walk (-Dhz68k.dtwalkms). */
	private static final long CREATOR_WALK_WAIT_MS=Long.getLong("hz68k.dtwalkms",2000L);
	/** The {@code index}-th (0/1 = newest) file of a walk's index with that creator and kind (the application first for BUNDLE_OWNER). */
	private static File pickWithCreator(Map<Integer,List<Object[]>> found,int creator,int index,int kind){
		List<Object[]> all=found==null?null:found.get(creator);if(all==null)return null;
		List<File> files=new ArrayList<File>();
		for(int pass=0;pass<2&&files.isEmpty();pass++)for(Object[] e:all){boolean application=(Boolean)e[1],bundle=(Boolean)e[2];
			if(kind==APPLICATION?application:kind==ANY_BUNDLE?bundle:pass==0?application:bundle)files.add((File)e[0]);}
		files.sort((a,b)->Long.compare(b.lastModified(),a.lastModified()));
		int i=Math.max(0,index-1);return i<files.size()?files.get(i):null;
	}
	/**
	 * Each starting folder is walked on its own, with its own budget: in one shared breadth-first walk the host root ("Macintosh
	 * HD", walked when the program may read everything) used up the budget on its first levels, and an application four folders
	 * down HyperZebra HD was never reached.
	 */
	/**
	 * The host's own system folders, never walked for a creator when a volume is the host disk: no classic program lives there,
	 * and /usr/bin alone (900 files, each with a fork or sidecar test and a Finder info read that lists the folder for a PC
	 * Exchange FINDER.DAT) took most of the 2 s a walk of "Macintosh HD" took.
	 */
	private static final java.util.Set<String> HOST_SYSTEM_FOLDERS=java.util.Set.of("/System","/usr","/bin","/sbin","/dev","/cores","/etc","/var","/proc","/sys","/private/etc","/private/var","/Library/Apple");
	private Map<Integer,List<Object[]>> walkCreators(java.util.ArrayDeque<File[]> roots) {
		Map<Integer,List<Object[]>> found=new HashMap<Integer,List<Object[]>>();java.util.Set<File> seen=new java.util.HashSet<File>();
		for(File[] root:roots){
			java.util.ArrayDeque<File[]> queue=new java.util.ArrayDeque<File[]>();queue.add(root);int visited=0;
			while(!queue.isEmpty()&&visited<5000){if(Thread.currentThread().isInterrupted())return found;
				File[] entry=queue.poll();File dir=entry[0];int depth=0;for(File f:entry)if(f!=null)depth++;
				File[] children=dir.listFiles();if(children==null)continue;
				for(File child:children){visited++;String n=child.getName();
					if(child.isDirectory()){if(n.startsWith(".")||n.equals("__MACOSX")||HOST_SYSTEM_FOLDERS.contains(child.getPath()))continue;if(depth<6)queue.add(new File[]{child,dir,null,null,null,null,null});continue;}
					if(!child.isFile()||n.startsWith("._"))continue;
					boolean candidate=ResourceFile68k.nativeForkLength(child)>0||ResourceFile68k.AppleDouble.findSidecar(child)!=null||n.toLowerCase().endsWith(".bin");
					if(!candidate)continue;
					// the recorded info only: a guess (from the name, or by opening the resource fork to see whether it is a program)
					// never gives a creator with a bundle, and guessing every file with a fork made a walk of the host disk take seconds
					byte[] info=FinderInfo68k.stored(child);if(info==null)continue;
					boolean application=MacApp68k.u4(info,0)==0x4150504c,bundle=(MacApp68k.u2(info,8)&0x2000)!=0; // kHasBundle
					if(!application&&!bundle)continue;
					File c;try{c=child.getCanonicalFile();}catch(IOException e){c=child;}
					if(seen.add(c))found.computeIfAbsent(MacApp68k.u4(info,4),k->new ArrayList<Object[]>()).add(new Object[]{c,application,bundle});}}
		}
		return found;
	}
	int parentIdOf(File path) throws IOException {return parentDirectoryId(path);}
	/**
	 * The files and folders of a volume in a fixed walk order (breadth first, limited), for PBCatSearch's continuation index.
	 * The program's own folder comes first when it is on that volume: a real catalog search sees the whole disk at once, a
	 * limited walk of a host volume (the home folder, the root) would never reach a folder several levels down - MacBeat
	 * looks for the ".bms" songs next to it with PBCatSearch over every volume and found none.
	 */
	List<File> catalogWalk(int volumeReference,int limit){
		List<File> result=new ArrayList<File>();File root;try{root=volumeRootFor(volumeReference);}catch(IOException e){return result;}if(root==null||!Sandbox.allowFile(root,Sandbox.Access.LIST,requester))return result;
		File first=null;
		try{if(searchFirst!=null&&searchFirst.isDirectory()&&volumeIndexFor(searchFirst)==volumeIndexFor(root)&&!canonical(root).equals(searchFirst)&&Sandbox.allowFile(searchFirst,Sandbox.Access.LIST,requester))first=searchFirst;}catch(IOException ignored){}
		if(first!=null){result.add(first);walk(first,null,result,limit);}
		walk(root,first,result,limit);
		return result;
	}
	private void walk(File top,File skip,List<File> result,int limit){
		java.util.ArrayDeque<File> queue=new java.util.ArrayDeque<File>();queue.add(top);
		while(!queue.isEmpty()&&result.size()<limit){File dir=queue.poll();File[] children=catalogEntries(dir);if(children==null)continue;java.util.Arrays.sort(children);
			for(File child:children){if(skip!=null&&child.equals(skip))continue;result.add(child);if(child.isDirectory())queue.add(child);if(result.size()>=limit)break;}}
	}
	/** The walk of the search in progress (ioCatPosition continues it): rebuilding it for every call made a search that asks
	 *  for one match at a time quadratic. */
	private List<File> lastWalk;private int lastWalkVolume;private long lastWalkTime;
	/**
	 * PBCatSearch: the catalog entries matching the criteria in ioSearchInfo1/2 under ioSearchBits, written as FSSpecs into
	 * ioMatchPtr (up to ioReqMatchCount); ioCatPosition keeps the walk index so a second call continues. noErr while the
	 * buffer filled up before the walk ended, eofErr (-39) once the whole volume was seen.
	 */
	int catSearch(int pb,FileSystem68k files) throws M68kCpuException {
		int matchPtr=memory.readLong(pb+24),requested=memory.readLong(pb+28),bits=memory.readLong(pb+36),info1=memory.readLong(pb+40),info2=memory.readLong(pb+44);
		int position=memory.readLong(pb+52)==0?0:memory.readLong(pb+56);int volume=(short)memory.readWord(pb+22);
		List<File> entries;
		if(position>0&&lastWalk!=null&&lastWalkVolume==volume&&System.currentTimeMillis()-lastWalkTime<60000)entries=lastWalk;
		else{entries=catalogWalk(volume,20000);lastWalk=entries;lastWalkVolume=volume;}
		lastWalkTime=System.currentTimeMillis();int found=0;boolean negate=(bits&0x4000)!=0;
		String wantedName=null;if((bits&3)!=0&&info1!=0){int namePtr=memory.readLong(info1+IO_NAME_PTR);if(namePtr!=0){int n=memory.readByte(namePtr);wantedName=encoding.decode(memory.readBytes(namePtr+1,n));}}
		int i=position;
		try{
			for(;i<entries.size()&&found<requested;i++){File f=entries.get(i);boolean match=true;
				if(wantedName!=null){String name=macNameOf(f);match=(bits&2)!=0?name.equalsIgnoreCase(wantedName):name.toLowerCase().contains(wantedName.toLowerCase());}
				if(match&&(bits&4)!=0&&info1!=0&&info2!=0){int attributes=(f.isDirectory()?0x10:0)|(f.canWrite()?0:1),mask=memory.readByte(info2+IO_FL_ATTRIB);match=(attributes&mask)==(memory.readByte(info1+IO_FL_ATTRIB)&mask);}
				if(match&&(bits&8)!=0&&info1!=0&&info2!=0&&!f.isDirectory()){byte[] info=metadataFor(f).finderInfo;for(int k=0;k<16&&match;k++){int mask=memory.readByte(info2+IO_FL_FNDR_INFO+k);if(((info[k]&0xff)&mask)!=(memory.readByte(info1+IO_FL_FNDR_INFO+k)&mask))match=false;}}
				if(match&&(bits&0x20)!=0&&info1!=0&&info2!=0&&!f.isDirectory()){long length=f.length();match=length>=(memory.readLong(info1+IO_FL_LG_LEN)&0xffffffffL)&&length<=(memory.readLong(info2+IO_FL_LG_LEN)&0xffffffffL);}
				if(match&&(bits&0x200)!=0&&info1!=0&&info2!=0){long created=metadataFor(f).creationDate&0xffffffffL;match=created>=(memory.readLong(info1+IO_FL_CR_DAT)&0xffffffffL)&&created<=(memory.readLong(info2+IO_FL_CR_DAT)&0xffffffffL);}
				if(match&&(bits&0x400)!=0&&info1!=0&&info2!=0){long modified=macDate(f.lastModified())&0xffffffffL;match=modified>=(memory.readLong(info1+IO_FL_MD_DAT)&0xffffffffL)&&modified<=(memory.readLong(info2+IO_FL_MD_DAT)&0xffffffffL);}
				if(match&&(bits&0x2000)!=0&&info1!=0){match=parentDirectoryId(f)==memory.readLong(info1+100);}
				if(match!=negate){if(matchPtr!=0)files.writeFileSpec(matchPtr+found*70,f);found++;}
			}
		}catch(IOException e){/* the walk stops at the unreadable entry */}
		memory.writeLong(pb+32,found);memory.writeLong(pb+52,i<entries.size()?1:0);memory.writeLong(pb+56,i);
		return i<entries.size()?NO_ERR:-39;
	}
	private int parentDirectoryId(File path) throws IOException {File canonical=canonical(path);if(isVolumeRoot(canonical))return 1;File parent=canonical.getParentFile();if(parent==null)return 1;if(isVolumeRoot(parent))return 2;return directoryId(parent);}
	private int findWorkingReference(int directoryId,int processId){for(Map.Entry<Integer,WorkingDirectory> entry:workingDirectories.entrySet()){WorkingDirectory value=entry.getValue();if(value.directoryId==directoryId&&value.processId==processId)return entry.getKey();}return 0;}
	private File fileForInfo(int pb,boolean hierarchical) throws IOException,M68kCpuException {int index=(short)memory.readWord(pb+IO_DIR_INDEX);if(index<=0)return resolveForParameterBlock(pb,memory.readLong(pb+IO_NAME_PTR),hierarchical);if(root==null)throw new IndexOutOfBoundsException(); /* 0 or negative: by ioNamePtr (IM Files, PBHGetFInfo; Director 4 passes -1) */File base=directoryFor(hierarchical?memory.readLong(pb+48):0,(short)memory.readWord(pb+IO_VREF_NUM));File[] entries=base==null?null:sortedEntries(base);File result=null;for(File entry:entries)if(entry.isFile()&&--index==0){result=entry.getCanonicalFile();break;}if(result==null)throw new IndexOutOfBoundsException();int output=memory.readLong(pb+IO_NAME_PTR);if(output==0)throw new IOException("missing indexed-name buffer");writePascal(output,macNameOf(result));return result;} // use the name known to the program; a differently encoded host name would not match (-51)
	/**
	 * ioFlAttrib's open-fork bits for {@code path}: 2 resource fork open (the Resource Manager's files too), 3 data fork open,
	 * 7 either. Externals test them before closing a file they opened: Free Fighters -NEXT-'s UxReadTXTRes XFCN does
	 * OpenResFile(stack) + PBGetFInfo and calls CloseResFile unless bit 2 says the fork was open already - it got HyperCard's own
	 * refNum of the running stack and closed it, and HyperCard's next external call failed ("外部命令 (XCMD) の読み込みに失敗しました").
	 */
	private int openForkAttributes(File path){
		File canonical;try{canonical=path.getCanonicalFile();}catch(IOException e){return 0;}
		int bits=0;
		for(OpenFile open:files.values())if(open.path!=null&&sameFile(open.path,canonical))bits|=open.resourceFork?0x04:0x08;
		if((bits&0x04)==0&&resourceFileReferences!=null&&resourceFileLookup!=null)for(int reference:resourceFileReferences.get()){File f=resourceFileLookup.apply(reference);if(f!=null&&sameFile(f,canonical)){bits|=0x04;break;}}
		return bits==0?0:bits|0x80;
	}
	/** ioFRefNum of GetFileInfo/GetCatInfo: an access path open to the file (the lowest reference number), 0 when it is not open.
	 *  The Aladdin Transaction Engine waits, polling GetCatInfo, until its " Aladdin Transaction Info " file has none (it spun
	 *  forever on the stale word the parameter block held). */
	private int openReference(File path){
		File canonical;try{canonical=path.getCanonicalFile();}catch(IOException e){return 0;}
		int found=0;
		for(java.util.Map.Entry<Integer,OpenFile> e:files.entrySet())if(e.getValue().path!=null&&sameFile(e.getValue().path,canonical)&&(found==0||e.getKey()<found))found=e.getKey();
		if(found==0&&resourceFileReferences!=null&&resourceFileLookup!=null)for(int reference:resourceFileReferences.get()){File f=resourceFileLookup.apply(reference);if(f!=null&&sameFile(f,canonical)){found=reference;break;}}
		return found;
	}
	private static boolean sameFile(File file,File canonical){if(file.equals(canonical))return true;try{return file.getCanonicalFile().equals(canonical);}catch(IOException e){return false;}}
	private void writeFileInfo(int pb,File path) throws IOException,M68kCpuException {Metadata value=metadataFor(path);int attributes=(path.isDirectory()?0x10:0)|(value.locked||!path.canWrite()&&!onLockedVolume(path)||path.isFile()&&Sandbox.readOnlyFor(path,requester)?1:0);/* a locked disk image's files are read-only on the host, but locked is the volume, not each file */if(path.isFile())attributes|=openForkAttributes(path);memory.writeByte(pb+IO_FL_ATTRIB,attributes);if(path.isFile())memory.writeWord(pb+24,openReference(path));/* ioFRefNum */memory.writeByte(pb+31,0);for(int i=0;i<16;i++)memory.writeByte(pb+IO_FL_FNDR_INFO+i,value.finderInfo[i]);memory.writeLong(pb+IO_FL_NUM,value.fileNumber);memory.writeWord(pb+52,0);long[] lengths=path.isFile()?forkLengths(path):new long[]{0,0};long length=lengths[0],resourceLength=lengths[1];int block=path.isFile()?allocationBlockFor(path):1;memory.writeLong(pb+IO_FL_LG_LEN,(int)Math.min(0x7fffffffL,length));memory.writeLong(pb+IO_FL_PY_LEN,(int)Math.min(0x7fffffffL,physical(length,block)));memory.writeWord(pb+62,0);memory.writeLong(pb+IO_FL_R_LG_LEN,(int)Math.min(0x7fffffffL,resourceLength));memory.writeLong(pb+IO_FL_R_PY_LEN,(int)Math.min(0x7fffffffL,physical(resourceLength,block)));memory.writeLong(pb+IO_FL_CR_DAT,value.creationDate);memory.writeLong(pb+IO_FL_MD_DAT,value.modificationDate);}
	/**
	 * Entries a program never meets when it walks a folder: the host's hidden files, its packages (an .app is one item to
	 * the Finder and thousands of files to a disk search), the system directories at the root of a host volume and a
	 * user's Library, /Users (the Home volume) and /Volumes (volumes of their own). Paths still resolve to them (the
	 * startup volume itself lives under ~/Library). Programs that search
	 * every mounted volume for their files, such as graphics libraries or programs to update, walked
	 * the whole host disk otherwise and never came back.
	 */
	private static final java.util.Set<String> SYSTEM_ROOT_DIRECTORIES=new java.util.HashSet<String>(Arrays.asList(
		"System","Library","private","usr","bin","sbin","etc","var","tmp","dev","cores","opt","home","net","Users","Volumes",
		"Windows","Program Files","Program Files (x86)","ProgramData","$Recycle.Bin","System Volume Information","Recovery","proc","sys","boot","lib","lib64","run","snap","srv"));
	private static final String[] PACKAGE_EXTENSIONS={".app",".framework",".bundle",".kext",".plugin",".xcodeproj",".xcworkspace",".photoslibrary",".musiclibrary",".tvlibrary",".lproj",".appex",".qlgenerator",".mdimporter",".prefpane",".component",".vst",".vst3",".pkg",".mpkg"};
	/** HyperZebra's own state at the top of the startup disk (it is HyperZebra's state folder): not the Mac's files - Apple's
	 *  FolderContents of "HyperZebra HD:" listed CatalogIDs.txt, launcher.properties, Open Stacks… */
	private static final java.util.Set<String> STATE_ENTRIES=new java.util.HashSet<String>(Arrays.asList(
		"CatalogIDs.txt","ProgramFiles.txt","launcher.properties","launcher-appearance.properties","resource_trash","resource_staging","resource_clipboard",
		"Open Stacks","Stack Icons","Working Copies","Languages","Translations","Deleted Files","Desktop DB","Desktop DF"));
	static boolean hiddenFromCatalog(File entry){
		String n=entry.getName();
		if(n.startsWith(".")||n.equals("__MACOSX"))return true;
		if(STATE_ENTRIES.contains(n)&&entry.getParentFile()!=null&&entry.getParentFile().equals(FileSystem68k.bootVolumeDirectory()))return true;
		if(!entry.isDirectory())return false;
		if(Files.isSymbolicLink(entry.toPath()))return true; // a linked folder would be walked twice, or for ever when it links upward
		String lower=n.toLowerCase();for(String ext:PACKAGE_EXTENSIONS)if(lower.endsWith(ext))return true;
		File parent=entry.getParentFile();if(parent==null)return false;
		if(parent.getParentFile()==null&&SYSTEM_ROOT_DIRECTORIES.contains(n))return true; // root of a host volume
		if(n.equals("Library")){File grand=parent.getParentFile();
			if(parent.equals(new File(System.getProperty("user.home")))||grand!=null&&grand.getName().equals("Users")&&grand.getParentFile()!=null&&grand.getParentFile().getParentFile()==null)return true;}
		return false;
	}
	static File[] catalogEntries(File directory){
		if(Boolean.getBoolean("hz68k.bbdebug"))System.err.println("[bbdebug] catalog list "+directory);File[] all=directory.listFiles();if(Boolean.getBoolean("hz68k.bbdebug"))System.err.println("[bbdebug] catalog done "+directory);if(all==null)return null;
		List<File> kept=new ArrayList<File>(all.length);for(File f:all)if(!hiddenFromCatalog(f))kept.add(f);
		return kept.toArray(new File[0]);
	}
	/** A directory's entries in HFS catalog order (case-insensitive by name), kept for a moment: indexed GetCatInfo/GetFileInfo walk
	 *  a folder one entry per call, and listing and sorting it afresh each time made a disk search quadratic. */
	private final Map<String,Object[]> listings=new HashMap<String,Object[]>();
	private File[] sortedEntries(File base) throws IOException {
		Sandbox.checkFile(base,Sandbox.Access.LIST,requester);
		String key=canonical(base).getPath();long modified=base.lastModified(),now=System.currentTimeMillis();
		Object[] cached=listings.get(key);
		if(cached!=null&&(Long)cached[0]==modified&&now-(Long)cached[1]<2000L)return (File[])cached[2];
		File[] entries=catalogEntries(base);if(entries==null)throw new IOException("cannot enumerate directory");
		Arrays.sort(entries,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
		if(listings.size()>64)listings.clear();
		listings.put(key,new Object[]{modified,now,entries});return entries;
	}
	private Metadata metadataFor(File path) throws IOException {String key=canonical(path).getPath();Metadata value=metadata.get(key);if(value!=null){value.modificationDate=macDate(path.lastModified());return value;}long created=path.lastModified();try{created=Files.readAttributes(path.toPath(),BasicFileAttributes.class).creationTime().toMillis();}catch(IOException ignored){}value=new Metadata(macDate(created),macDate(path.lastModified()),path.isDirectory()?directoryId(path):sessionCatalogId());/* externals see the host's Finder info too: MacroMind's PlayMovie takes only files whose type is 'VWMD' */{byte[] info=FinderInfo68k.read(path);if(info!=null)System.arraycopy(info,0,value.finderInfo,0,16);}value.hostType=MacApp68k.u4(value.finderInfo,0);if(pngPictureFiles&&PictureFile68k.isHostImageType(value.hostType)&&path.isFile()){value.finderInfo[0]='P';value.finderInfo[1]='I';value.finderInfo[2]='C';value.finderInfo[3]='T';}if(persistent&&HostFileAttributes68k.locked(path)&&!onLockedVolume(path))value.locked=true;/* a file the host keeps locked (the Finder's Locked, read-only) is locked here: an external locked it in another VM */metadata.put(key,value);return value;}
	private int fileNumber(File path) throws IOException {return metadataFor(path).fileNumber;}
	int fileNumberFor(File path) throws IOException {return fileNumber(path);}
	/** The file in {@code directory} whose file number this session is {@code number} (a file renamed since its number was handed out), or null. */
	File fileNumbered(File directory,int number){
		if(directory==null||number<=0)return null;File[] children=directory.listFiles();if(children==null)return null;
		for(File child:children)try{if(child.isFile()&&metadata.containsKey(canonical(child).getPath())&&fileNumber(child)==number)return child;}catch(IOException ignored){}
		return null;
	}
	/** The creation date GetVolInfo reports for the volume holding {@code path} (alias records carry it and QuickTime's data handlers compare it). */
	int volumeCreationDateFor(File path) throws IOException {int index=volumeIndexFor(path);File volume=index<0?root:volumes.get(index);if(volume==null)return 0;return macDate(Files.readAttributes(volume.toPath(),BasicFileAttributes.class).creationTime().toMillis());}
	private ResourceFork resourceForkFor(File path) throws IOException {String key=canonical(path).getPath();ResourceFork value=resourceForks.get(key);if(value!=null)return value;File storage=TempFiles.createFile("hyperzebra-rsrc-",".bin");byte[] contents=ResourceFile68k.readForkBytes(path,null);if(contents!=null&&contents.length>0)Files.write(storage.toPath(),contents);value=new ResourceFork(storage);resourceForks.put(key,value);return value;}
	/** The MacBinary file in {@code directory} whose header carries {@code name} ("Name.bin" first, then any container in the folder). */
	private File macBinaryNamed(File directory,String name) throws IOException {
		if(directory==null||name==null)return null;
		File direct=new File(directory,name+".bin");
		if(direct.isFile()&&macBinaryFor(direct)!=null)return direct;
		File[] entries=directory.listFiles();if(entries==null)return null;
		byte[] wanted=encoding.encodeReplacing(name);
		for(File entry:entries){
			if(!entry.isFile()||entry.length()<128||!ResourceFile68k.MacBinary.headerLooksLikeMacBinary(entry))continue;
			byte[] header=ResourceFile68k.MacBinary.headerNameBytes(entry);
			if(header==null)continue;
			// names are compared in the program's own text encoding (Japanese archives carry MacJapanese names)
			if((Arrays.equals(header,wanted)||encoding.decode(header).equalsIgnoreCase(name))&&macBinaryFor(entry)!=null)return entry;
		}
		return null;
	}
	/** The MacBinary container wrapping {@code path}, or null for an ordinary host file (checked once per file). */
	private ResourceFile68k.MacBinary macBinaryFor(File path) throws IOException {String key=canonical(path).getPath();if(macBinaries.containsKey(key))return macBinaries.get(key);ResourceFile68k.MacBinary mb=ResourceFile68k.MacBinary.headerLooksLikeMacBinary(path)?ResourceFile68k.MacBinary.read(path):null;macBinaries.put(key,mb);return mb;}
	/** The file backing the data fork: the host file itself, or a temp copy of the fork unpacked from a MacBinary container. */
	private static final int TEXT_TYPE=0x54455854;
	/** The file's type as the program sees it: what SetFInfo set this session, else the host's Finder information, else the extension's guess (which a rename can change). */
	private int typeOf(File path) throws IOException {int type=MacApp68k.u4(metadataFor(path).finderInfo,0);if(type==0){byte[] info=persistent?FinderInfo68k.guess(path):FinderInfo68k.read(path);if(info!=null)type=MacApp68k.u4(info,0);}return type;}
	private static long hostStamp(File path){return path.lastModified()*31+path.length();}
	/** The type the program is shown for {@code file} (what SetFInfo set this session, a host picture as 'PICT'): the Standard File dialogs filter and answer with it. */
	int shownType(File file){try{if(!file.isFile())return 0;int type=MacApp68k.u4(metadataFor(file).finderInfo,0);return type!=0?type:typeOf(file);}catch(IOException e){return FinderInfo68k.type(file);}}
	/** The host file's own type (PNGf for a PNG the program sees as PICT), from its Finder information or the extension. */
	private int hostTypeOf(File path) throws IOException {Metadata value=metadataFor(path);if(value.hostType!=0)return value.hostType;byte[] info=persistent?FinderInfo68k.guess(path):FinderInfo68k.read(path);return info==null?0:MacApp68k.u4(info,0);}
	/** Finder information as the host keeps it: a program's 'PICT' on a file the host holds as a PNG stays the host's type. */
	/**
	 * A program never hides a host folder: that Finder flag (kIsInvisible $4000) keeps the host's own value on the host side, while
	 * the program still sees what it set. The System 7.5 Finder marks the "Desktop" file it keeps at every volume root invisible -
	 * on the Home volume that name is the user's own Desktop folder, and macOS hid it. kHasCustomIcon ($0400) is the program's
	 * to set only where the folder holds the "Icon\r" file with the icon (StuffIt Expander restoring a folder from an archive, as
	 * The Unarchiver does); without that file the host's value stays, a stray flag would show a blank icon.
	 */
	private static byte[] hostFolderSafe(File path,byte[] info){
		if(!path.isDirectory())return info;
		byte[] current=FinderInfo68k.read(path);int hostFlags=current==null||current.length<9?0:current[8]&0xff;
		int hostOwned=new File(path,"Icon\r").isFile()?0x40:0x44;
		byte[] out=info.clone();out[8]=(byte)((out[8]&~hostOwned)|(hostFlags&hostOwned));return out;
	}
	private byte[] hostFinderInfo(File path,byte[] info) throws IOException {int hostType=hostTypeOf(path);if(!pngPictureFiles||MacApp68k.u4(info,0)!=PictureFile68k.PICT||!PictureFile68k.isHostImageType(hostType))return info;byte[] out=info.clone();out[0]=(byte)(hostType>>>24);out[1]=(byte)(hostType>>>16);out[2]=(byte)(hostType>>>8);out[3]=(byte)hostType;return out;}
	/** A file the program asks about by name (not a folder walk) gets its converted copy first, so the length it hears is the one it will read; host pictures grow into PICTs. */
	private void materializeView(File path) throws IOException {if(pngPictureFiles&&path.isFile()&&PictureFile68k.isHostImageType(hostTypeOf(path))&&macBinaryFor(path)==null)viewFor(path);}
	/**
	 * The converted copy of a host text file, made on first use and kept while the host file stays as it was (a copy
	 * some handle still has open is the truth regardless); null when the file is handed over as it is. Only a 'TEXT'
	 * file, or an untyped one that is plainly UTF-8, is looked at (see {@link TextFile68k#sniff}).
	 */
	private HostView viewFor(File path) throws IOException {
		String key=canonical(path).getPath();HostView view=views.get(key);long stamp=hostStamp(path);
		if(view!=null&&(view.open>0||view.stamp==stamp))return view.storage==null?null:view;
		if(view!=null&&view.storage!=null)view.storage.delete();
		view=new HostView(stamp);views.put(key,view);
		int type=typeOf(path);
		if(pngPictureFiles&&PictureFile68k.isHostImageType(hostTypeOf(path))&&path.length()>0&&path.length()<=PictureFile68k.LIMIT){
			byte[] host=Files.readAllBytes(path.toPath()),program=PictureFile68k.toProgram(host);
			if(program!=null){
				File storage=TempFiles.createFile("hyperzebra-pict-",".bin");Files.write(storage.toPath(),program);
				view.storage=storage;view.picture=true;view.original=program;
				if(Mac68kApi.TRACE)System.err.println("[file] picture view of "+path+" ("+host.length+" -> "+program.length+" bytes of PICT)");
			}
		}
		else if((type==TEXT_TYPE||type==0)&&path.length()>0&&path.length()<=TextFile68k.LIMIT&&!ProgramFiles.wrote(key,stamp)){
			byte[] host=Files.readAllBytes(path.toPath());TextFile68k.Form form=TextFile68k.sniff(host,type==TEXT_TYPE);
			if(form!=null){
				byte[] program=TextFile68k.toProgram(host,form,encoding);
				File storage=TempFiles.createFile("hyperzebra-text-",".bin");Files.write(storage.toPath(),program);
				view.storage=storage;view.form=form;view.original=program;
				if(Mac68kApi.TRACE)System.err.println("[file] text view of "+path+" ("+(form.utf8?"UTF-8":"legacy")+(form.bom?" BOM":"")+", "+(form.newline.equals("\n")?"LF":form.newline.equals("\r\n")?"CRLF":"CR")+", "+host.length+" -> "+program.length+" bytes)");
			}
		}
		return view.storage==null?null:view;
	}
	/**
	 * Every converted text file written since (see {@link HostView}) reaches the host: at FlushVol, and at each turn of
	 * the program's event loop, since a document window keeps its file open until it closes (some editors save with a
	 * SetEOF/Write and goes on) while the user expects the host file to be current after a Save.
	 */
	/** Closes every file the program left open (ExitToShell): what it wrote reaches the host, converted like at its own Close. */
	/**
	 * The programs this program created (StuffIt Expander restoring them from an archive) get the icon of their bundle as
	 * their custom icon, for the host Finder, which reads no 'BNDL' (see {@link ProgramFileIcon68k}). Called when the program
	 * ends, after {@link #closeAll}: every fork and Finder info it wrote is on the host by then.
	 */
	void giveNewProgramsIcons(){for(String path:createdFiles)ProgramFileIcon68k.give(new File(path));createdFiles.clear();}
	void closeAll(){for(Integer reference:new ArrayList<Integer>(files.keySet())){OpenFile open=files.get(reference);if(open.attached){if(open.dirty)resourceFileClosed(reference);continue;}try{closeOpenFile(reference,open);}catch(IOException ignored){}}}
	void syncViews(){try{syncViews(false);}catch(IOException e){if(Mac68kApi.TRACE)System.err.println("[file] text sync failed: "+e);}}
	private void syncViews(boolean force) throws IOException {
		for(OpenFile open:files.values()){
			if(!open.writable||!open.dirty||open.resourceFork)continue;
			if(open.view==null&&open.fresh&&!open.binary&&utf8TextFiles)promoteNewText(open);
			if(open.view!=null&&(force||System.nanoTime()-open.view.synced>250_000_000L))writeBack(open); // a program writing a long file piecemeal between events is not rewritten on the host at every turn
		}
	}
	/**
	 * A file the program is creating and has written text into: from now on it is kept like a host UTF-8 file, the
	 * handle moving to a converted copy at the same mark, so the host file is UTF-8 while the program goes on writing.
	 * Untyped files wait for their SetFInfo (see {@link #convertNewFile}); bytes that are not text end the watch.
	 */
	private void promoteNewText(OpenFile open) throws IOException {
		if(typeOf(open.path)!=TEXT_TYPE||open.file.length()>TextFile68k.LIMIT)return;
		byte[] program=Files.readAllBytes(open.path.toPath());
		if(!TextFile68k.looksLikeText(program)){open.binary=true;return;}
		if(encoding.decode(program).indexOf('\uFFFD')>=0)return; // a half-written two-byte character: look again later
		if(TextFile68k.toHostNew(program,encoding)==null){open.binary=true;return;} // LF or CRLF line ends would not read back as written (MacSSH's known_hosts, kept open): its own bytes, recorded at Close
		String key=canonical(open.path).getPath();HostView stale=views.get(key);if(stale!=null&&stale.storage!=null)stale.storage.delete();
		HostView view=new HostView(0);view.form=new TextFile68k.Form(true,false,"\n");view.original=new byte[0];view.open=1;
		view.storage=TempFiles.createFile("hyperzebra-text-",".bin");Files.write(view.storage.toPath(),program);
		long mark=open.file.getFilePointer();open.file.close();open.file=new RandomAccessFile(view.storage,"rw");open.file.seek(mark);
		views.put(key,view);open.view=view;open.fresh=false;untypedNewFiles.remove(key);
		if(Mac68kApi.TRACE)System.err.println("[file] "+open.path+" is a new text file: kept as UTF-8 from now on");
	}
	/** The host file gets what the program wrote to its converted copy, back in the host file's own form (nothing when the program left the text as it was). */
	private void writeBack(OpenFile open) throws IOException {
		HostView view=open.view;byte[] program=Files.readAllBytes(view.storage.toPath());
		if(!Arrays.equals(program,view.original)){
			if(view.picture)writePicture(open.path,program);else Files.write(open.path.toPath(),TextFile68k.toHost(program,view.form,encoding));
			view.original=program;}
		view.stamp=hostStamp(open.path);view.synced=System.nanoTime();open.dirty=false;
	}
	/**
	 * The PICT file a program wrote goes to the host as a PNG under the same name (type PNGf, the program's creator - prvw
	 * when it gave none); bytes that are not a picture the renderer can play stay a plain PICT file, typed so.
	 */
	private void writePicture(File path,byte[] program) throws IOException {
		byte[] picture=PictureFile68k.pictureOf(program);java.awt.image.BufferedImage image=null;
		if(picture!=null)try{image=pictureRenderer.apply(program);}catch(RuntimeException e){image=null;}
		if(image==null&&picture!=null)image=PictureFile68k.renderWithHost(program);
		Metadata value=metadataFor(path);byte[] info;
		if(image!=null){Files.write(path.toPath(),PictureFile68k.png(image));info=PictureFile68k.savedFinderInfo(value.finderInfo,PictureFile68k.PNGF,MacApp68k.u4(PictureFile68k.PNG_FINDER_INFO,4));}
		else{Files.write(path.toPath(),program);info=PictureFile68k.savedFinderInfo(value.finderInfo,PictureFile68k.PICT,0x3F3F3F3F);}
		value.hostType=MacApp68k.u4(info,0);
		if(persistent)FinderInfo68k.write(path,info);
		if(image!=null){byte[] shown=info.clone();shown[0]='P';shown[1]='I';shown[2]='C';shown[3]='T';value.finderInfo=shown;} // the program keeps seeing a PICT
		if(Mac68kApi.TRACE)System.err.println("[file] "+path+(image!=null?" written as PNG ("+image.getWidth()+"x"+image.getHeight()+")":" kept as PICT (not rendered)"));
	}
	/**
	 * A file the program created and filled with text (in its own encoding, CR line ends) is written to the host as
	 * UTF-8 with LF when the preference says so; then the program reads it back through a converted copy like any
	 * other host text file. A file still untyped waits for the SetFInfo that makes it 'TEXT' (some programs set the
	 * type after closing); anything else - a data file, bytes that are not text - stays as written.
	 */
	private void convertNewFile(File path) throws IOException {
		String key=canonical(path).getPath();
		if(path.isFile())ProgramFiles.record(key,hostStamp(path)); // a conversion below takes this back (ProgramFiles.CONVERTED): CR->LF keeps the length and
		// often the millisecond, so the stamp stayed and the program got the LF bytes back as its own (Drill Designer's Restore read for CRs at EOF forever)
		if((!utf8TextFiles&&!pngPictureFiles)||!path.isFile()||path.length()>TextFile68k.LIMIT){untypedNewFiles.remove(key);return;}
		int type=typeOf(path);
		if(!(type==TEXT_TYPE&&utf8TextFiles)&&!(type==PictureFile68k.PICT&&pngPictureFiles)){untypedNewFiles.add(key);if(Mac68kApi.TRACE)System.err.println("[file] "+path+" closed as a new file of type "+AppleEvents68k.fourcc(type)+": conversion waits for its type");return;} // untyped, or typed as something else for now: some programs retype an exported PICT after closing it
		untypedNewFiles.remove(key);
		for(OpenFile other:files.values())if(!other.resourceFork&&canonical(other.path).getPath().equals(key))return; // still open elsewhere: its close converts
		byte[] program=Files.readAllBytes(path.toPath());
		if(type==PictureFile68k.PICT){if(PictureFile68k.pictureOf(program)!=null){writePicture(path,program);ProgramFiles.record(key,ProgramFiles.CONVERTED);HostView stale=views.remove(key);if(stale!=null&&stale.storage!=null)stale.storage.delete();}return;}
		byte[] host=TextFile68k.toHostNew(program,encoding);
		if(host==null||Arrays.equals(host,program))return;
		Files.write(path.toPath(),host);HostView stale=views.remove(key);if(stale!=null&&stale.storage!=null)stale.storage.delete();
		ProgramFiles.record(key,ProgramFiles.CONVERTED);
		utf8NewFiles.put(key,new byte[][]{program,host});
		if(Mac68kApi.TRACE)System.err.println("[file] "+path+" written as UTF-8 ("+program.length+" -> "+host.length+" bytes)");
	}
	/**
	 * A new file written to the host as UTF-8 text that the program then retypes as something other than 'TEXT' is not text
	 * to it any more, so it would read the UTF-8 bytes back as they are: REALbasic writes its preferences through a text
	 * stream and types the file 'pref' after closing it (長谷川ドラマー塾 showed its saved names as mojibake). The file gets the
	 * program's own bytes again, unless the host file changed since.
	 */
	private void revertRetypedText(File path,int oldType) throws IOException {
		String key=canonical(path).getPath();byte[][] written=utf8NewFiles.get(key);
		if(typeOf(path)==TEXT_TYPE||!path.isFile())return;
		for(OpenFile other:files.values())if(!other.resourceFork&&canonical(other.path).getPath().equals(key))return;
		if(written!=null){
			utf8NewFiles.remove(key);
			if(Arrays.equals(Files.readAllBytes(path.toPath()),written[1])){
				Files.write(path.toPath(),written[0]);HostView stale=views.remove(key);if(stale!=null&&stale.storage!=null)stale.storage.delete();
				if(Mac68kApi.TRACE)System.err.println("[file] "+path+" retyped "+AppleEvents68k.fourcc(typeOf(path))+": the program's own bytes again");
				return;
			}
		}
		// Any other 'TEXT' file the program saw converted (a host UTF-8 or LF file, or one an earlier VM converted - each external
		// call has a VM of its own, so G-XCollection's MtWriteFile and MtSetFTC "ttro" never share the note above): from now on the
		// program reads it as it is, so it holds the bytes the program read, as on a Mac (MtCopyFile/MtSwapFile copied UTF-8 mojibake)
		if(oldType!=TEXT_TYPE||path.length()==0||path.length()>TextFile68k.LIMIT||ProgramFiles.wrote(key,hostStamp(path)))return;
		byte[] host=Files.readAllBytes(path.toPath());TextFile68k.Form form=TextFile68k.sniff(host,true);
		if(form==null)return;
		byte[] program=TextFile68k.toProgram(host,form,encoding);
		if(Arrays.equals(program,host))return;
		Files.write(path.toPath(),program);ProgramFiles.record(key,hostStamp(path));
		HostView stale=views.remove(key);if(stale!=null&&stale.storage!=null)stale.storage.delete();
		if(Mac68kApi.TRACE)System.err.println("[file] "+path+" retyped "+AppleEvents68k.fourcc(typeOf(path))+": now the program's form of its text");
	}
	/**
	 * {data, resource} fork lengths for catalog information without unpacking anything: a fork already materialized for
	 * an open file (possibly written since) reports its copy, else the host file, its native resource fork or the
	 * MacBinary header's counts. GetCatInfo used to unpack every file it was asked about - a disk search left a temp
	 * file per file it passed (80,000 of them) and took a millisecond each.
	 */
	private long[] forkLengths(File path) throws IOException {
		String key=canonical(path).getPath();File data=dataForks.get(key);ResourceFork fork=resourceForks.get(key);
		if(data==null){HostView view=views.get(key);if(view!=null&&view.storage!=null)data=view.storage;}
		// a host text file reads as its converted copy (Mac Japanese, CR): its catalog length is that copy's from the first look on,
		// not the UTF-8 length until the file is opened - Netscape Communicator compared the bookmark file's length it saw at
		// start-up with the one after reading it and asked at every launch whether to reload the "changed" bookmarks
		if(data==null&&path.isFile()&&path.length()>0&&path.length()<=TextFile68k.LIMIT&&typeOf(path)==TEXT_TYPE&&macBinaries.get(key)==null){
			HostView view=viewFor(path);if(view!=null&&view.storage!=null)data=view.storage;}
		ResourceFile68k.MacBinary mb=macBinaries.get(key);long[] header=mb!=null?new long[]{mb.dataFork.length,mb.resourceFork.length}:macBinaries.containsKey(key)?null:ResourceFile68k.MacBinary.headerForkLengths(path);
		long dataLength=data!=null?data.length():header!=null?header[0]:path.length();
		long resourceLength=fork!=null?fork.storage.length():header!=null?header[1]:ResourceFile68k.nativeForkLength(path);
		if(resourceLength==0&&fork==null&&header==null)resourceLength=ResourceFile68k.AppleDouble.forkLength(ResourceFile68k.AppleDouble.findSidecar(path)); // AppleDouble sidecar (Windows, Linux, fork-less volumes)
		return new long[]{dataLength,resourceLength};
	}
	private File dataForkFor(File path) throws IOException {ResourceFile68k.MacBinary mb=macBinaryFor(path);if(mb==null){HostView view=viewFor(path);return view!=null?view.storage:path;}String key=canonical(path).getPath();File storage=dataForks.get(key);if(storage!=null)return storage;storage=TempFiles.createFile("hyperzebra-data-",".bin");Files.write(storage.toPath(),mb.dataFork);dataForks.put(key,storage);return storage;}
	private long rangeStart(RandomAccessFile file,int pb) throws IOException,M68kCpuException {int mode=memory.readWord(pb+IO_POS_MODE)&3,offset=memory.readLong(pb+IO_POS_OFFSET);return mode==0?file.getFilePointer():mode==1?offset:mode==2?file.length()+offset:file.getFilePointer()+offset;}
	private String rangeKey(OpenFile open) throws IOException {return open.path.getCanonicalPath()+(open.resourceFork?"\0R":"\0D");}
	private boolean isRangeLocked(OpenFile open,int reference,long start,long end) throws IOException {if(end<=start)return false;List<RangeLock> locks=rangeLocks.get(rangeKey(open));if(locks!=null)for(RangeLock value:locks)if(value.reference!=reference&&value.overlaps(start,end))return true;return false;}
	private void releaseRangeLocks(int reference){rangeLocks.entrySet().removeIf(entry->{entry.getValue().removeIf(value->value.reference==reference);return entry.getValue().isEmpty();});}
	/** Files open outside the File Manager's table: resource files of the Resource Manager, the running program's own file. */
	private java.util.function.Predicate<File> openElsewhere=f->false;
	void setOpenElsewhere(java.util.function.Predicate<File> p){openElsewhere=p;}
	/** A file with an open fork cannot be deleted (fBsyErr), as on the Mac - a program deleting its own running file (an
	 *  installer's quit-time cleanup after a failed temp copy) is refused, not carried out on the host. */
	private boolean isBusy(File path) throws IOException {File canonical=path.getCanonicalFile();for(OpenFile open:files.values())if(open.path.getCanonicalFile().equals(canonical))return true;if(!canonical.isDirectory()&&openElsewhere.test(canonical))return true;if(!canonical.isDirectory())return false;File[] children=canonical.listFiles();if(children==null||children.length>0)return true;for(WorkingDirectory working:workingDirectories.values()){File directory=directories.get(working.directoryId);if(directory!=null&&directory.getCanonicalFile().equals(canonical))return true;}return false;}
	private void removeState(String path){metadata.remove(path);ResourceFork fork=resourceForks.remove(path);if(fork!=null)fork.storage.delete();HostView view=views.remove(path);if(view!=null&&view.storage!=null)view.storage.delete();untypedNewFiles.remove(path);utf8NewFiles.remove(path);createdFiles.remove(path);macBinaries.remove(path);File data=dataForks.remove(path);if(data!=null)data.delete();directories.entrySet().removeIf(entry->entry.getValue().getPath().equals(path));directoryIds.keySet().removeIf(file->file.getPath().equals(path));/* both directions: a folder made again at that path (Pro Tools' installer after a cancelled install) was handed the deleted folder's ID, which no longer led anywhere (-43) */fileIdReferences.entrySet().removeIf(entry->entry.getValue().equals(path));rangeLocks.keySet().removeIf(key->key.startsWith(path+"\0"));}
	private void rebaseState(String oldRoot,String newRoot) throws IOException {Map<String,Metadata> movedMetadata=new HashMap<String,Metadata>();for(Map.Entry<String,Metadata> entry:metadata.entrySet())movedMetadata.put(rebasePath(entry.getKey(),oldRoot,newRoot),entry.getValue());metadata.clear();metadata.putAll(movedMetadata);Map<String,ResourceFork> movedForks=new HashMap<String,ResourceFork>();for(Map.Entry<String,ResourceFork> entry:resourceForks.entrySet())movedForks.put(rebasePath(entry.getKey(),oldRoot,newRoot),entry.getValue());resourceForks.clear();resourceForks.putAll(movedForks);macBinaries.clear();for(File data:dataForks.values())data.delete();dataForks.clear();Map<String,HostView> movedViews=new HashMap<String,HostView>();for(Map.Entry<String,HostView> entry:views.entrySet())movedViews.put(rebasePath(entry.getKey(),oldRoot,newRoot),entry.getValue());views.clear();views.putAll(movedViews);java.util.Set<String> movedUntyped=new java.util.HashSet<String>();for(String untyped:untypedNewFiles)movedUntyped.add(rebasePath(untyped,oldRoot,newRoot));untypedNewFiles.clear();untypedNewFiles.addAll(movedUntyped);java.util.Set<String> movedCreated=new java.util.LinkedHashSet<String>();for(String created:createdFiles)movedCreated.add(rebasePath(created,oldRoot,newRoot));createdFiles.clear();createdFiles.addAll(movedCreated);Map<String,byte[][]> movedUtf8=new HashMap<String,byte[][]>();for(Map.Entry<String,byte[][]> entry:utf8NewFiles.entrySet())movedUtf8.put(rebasePath(entry.getKey(),oldRoot,newRoot),entry.getValue());utf8NewFiles.clear();utf8NewFiles.putAll(movedUtf8);Map<String,List<RangeLock>> movedLocks=new HashMap<String,List<RangeLock>>();for(Map.Entry<String,List<RangeLock>> entry:rangeLocks.entrySet()){String key=entry.getKey(),suffix=key.substring(key.length()-2);movedLocks.put(rebasePath(key.substring(0,key.length()-2),oldRoot,newRoot)+suffix,entry.getValue());}rangeLocks.clear();rangeLocks.putAll(movedLocks);for(Map.Entry<Integer,String> entry:fileIdReferences.entrySet())entry.setValue(rebasePath(entry.getValue(),oldRoot,newRoot));for(Map.Entry<Integer,File> entry:directories.entrySet())entry.setValue(new File(rebasePath(entry.getValue().getCanonicalPath(),oldRoot,newRoot)));directoryIds.clear();for(Map.Entry<Integer,File> entry:directories.entrySet())directoryIds.put(entry.getValue(),entry.getKey());for(OpenFile open:files.values())open.path=new File(rebasePath(open.path.getCanonicalPath(),oldRoot,newRoot));}
	private static String rebasePath(String path,String oldRoot,String newRoot){return path.equals(oldRoot)?newRoot:path.startsWith(oldRoot+File.separator)?newRoot+path.substring(oldRoot.length()):path;}
	// Mac file dates are local time on the VM clock (like ReadDateTime, shifted from 2040 on); host dates are UTC
	static int macDate(long millis){return (int)Toolbox68k.macSeconds(Math.max(0,millis));}
	private static void setHostModificationDate(File path,int macDate){long millis=Toolbox68k.hostMillis(Integer.toUnsignedLong(macDate));if(millis>=0)path.setLastModified(millis);}
	/** The host file's creation (birth) time from a Mac date (SetFInfo, SetCatInfo: Rinaldi's SetFInfo "e=" kept it in memory only). */
	private static void setHostCreationDate(File path,int macDate){long millis=Toolbox68k.hostMillis(Integer.toUnsignedLong(macDate));if(millis>=0)HostFileAttributes68k.setCreationTime(path,millis);}
	private int position(RandomAccessFile file,int pb) throws IOException,M68kCpuException {int mode=memory.readWord(pb+IO_POS_MODE)&3,offset=memory.readLong(pb+IO_POS_OFFSET);long position=mode==0?file.getFilePointer():mode==1?offset:mode==2?file.length()+offset:file.getFilePointer()+offset;if(position<0)return POS_ERR;file.seek(position);return NO_ERR;}
	private boolean finish(M68kCpu cpu,int pb,int result) throws M68kCpuException {memory.writeWord(pb+IO_RESULT,result);cpu.d[0]=(cpu.d[0]&0xffff0000)|(result&0xffff);return true;}

	private File resolveForParameterBlock(int pb,int namePointer,boolean hierarchical) throws IOException,M68kCpuException {
		return resolveFrom(baseFor(hierarchical?memory.readLong(pb+48):0,(short)memory.readWord(pb+IO_VREF_NUM),namePointer),namePointer);}
	/**
	 * The folder a parameter block's name is taken from: the one ioDirID/ioVRefNum name, else - when they name none - the default
	 * directory for a name that stands on its own: a full pathname, or HyperTalk's colon form of a host path (see
	 * {@link #hostColonPath}). A meaningless ioDirID must not fail those: HyperCard's "open file" calls HOpenDF with the pathname
	 * and whatever was left in ioDirID, and G-XCollection's MtCreateFldr calls PBDirCreate with the 32768 its PBHGetVInfo left there
	 * (ioVAlBlkSiz).
	 */
	private File baseFor(int directoryId,int volumeReference,int namePointer) throws IOException,M68kCpuException {
		File directory=directoryFor(directoryId,volumeReference);
		if(directory!=null&&directory.isDirectory()||namePointer==0||root==null)return directory;
		int length=memory.readByte(namePointer);if(length==0)return directory;
		String name=decodeName(memory.readBytes(namePointer+1,length));
		// a full pathname names its volume: an ioDirID left from another call does not matter, even one that names a folder gone
		// since (a session folder ID an earlier external made and deleted)
		return !name.startsWith(":")&&name.indexOf(':')>0||hostColonPath(name)||stackShadows!=null&&hostSlashPath(name)?directoryFor(0,0):directory;}
	/** Whether {@code name} is an absolute host path with a folder ("/Users/me/x.txt", as "answer file" gives) to something that
	 *  exists or can be made. An HFS name may start with '/' too, but not with a whole host path whose folder exists. */
	static boolean hostSlashPath(String name){
		if(File.separatorChar!='/'||name==null||!name.startsWith("/")||name.lastIndexOf('/')<=0||name.indexOf(':')>=0)return false;
		File f=new File(name);
		return f.exists()||f.getParentFile()!=null&&f.getParentFile().isDirectory();
	}
	private boolean hostColonPath(int namePointer) throws M68kCpuException {
		if(namePointer==0||memory.readByte(namePointer)==0)return false;
		return hostColonPath(decodeName(memory.readBytes(namePointer+1,memory.readByte(namePointer))));}
	/**
	 * Whether {@code name} is HyperTalk's colon form of an absolute host path (":Users:me:Stacks:work:" - "the long name of this
	 * stack" gives those) to something that exists or can be made: its folder exists. HFS reads it as a partial pathname.
	 */
	static boolean hostColonPath(String name){
		if(File.separatorChar!='/'||name==null||!name.startsWith(":"))return false;
		String body=name.substring(1,name.endsWith(":")?name.length()-1:name.length());
		if(body.isEmpty()||body.indexOf(':')<0)return false;
		StringBuilder host=new StringBuilder();
		for(String part:body.split(":",-1)){if(part.isEmpty()||part.equals(".")||part.equals(".."))return false;host.append('/').append(part.replace('/',':'));}
		File f=new File(host.toString());
		return f.exists()||f.getParentFile()!=null&&f.getParentFile().isDirectory();
	}
	/** A full pathname whose volume is not mounted: nsvErr (-35), not the permErr of a refused path - Rinaldi's GetPrivileges and
	 *  SyncTimeWithServer report "Volume not found" for it (permErr made SyncTimeWithServer retry Create for 5 seconds). */
	static final class NoSuchVolume extends SecurityException {NoSuchVolume(String volume){super("unknown volume "+volume);}}
	/** The result code of a refused path: nsvErr for an unknown volume, else permErr (outside the mounted volumes, the sandbox). */
	static int securityError(SecurityException e){return e instanceof NoSuchVolume?NSV_ERR:PERM_ERR;}
	/** A full pathname ("Volume:folder"), which names its volume and so ignores ioVRefNum/ioDirID. */
	private boolean namesFullPath(int namePointer) throws M68kCpuException {if(namePointer==0)return false;int length=memory.readByte(namePointer);if(length==0||memory.readByte(namePointer+1)==':')return false;for(int i=1;i<=length;i++)if(memory.readByte(namePointer+i)==':')return true;return false;}
	private File resolveFrom(File currentDirectory,int namePointer) throws IOException,M68kCpuException {if(root==null||currentDirectory==null||namePointer==0)return null;int length=memory.readByte(namePointer);if(length==0)return null;byte[] bytes=new byte[length];for(int i=0;i<length;i++)bytes[i]=(byte)memory.readByte(namePointer+1+i);return resolveBytes(currentDirectory,bytes);}
	/**
	 * Resolves a name as the program passed it. When the name decoded in the program's encoding names nothing on
	 * disk, the other Mac encodings are tried: the host file was named by whatever tool unpacked it (an English
	 * a program's data-file name may arrive with Mac OS Roman $AA, which the Japanese encoding reads as a half-width
	 * katakana), whereas on a real Mac both sides only ever compare the raw bytes.
	 */
	File resolveBytes(File currentDirectory,byte[] bytes) throws IOException {
		String name=decodeName(bytes);if(unsafe(name))return null;
		File result=resolveName(currentDirectory,name);
		// the short form of a long host name (see shortHfsName), as a leaf name in this folder
		if(result!=null&&!result.exists()&&bytes.length<=HFS_NAME_MAX&&name.indexOf('#')>0&&name.indexOf(':')<0&&currentDirectory.isDirectory()){
			File[] entries=currentDirectory.listFiles();
			if(entries!=null)for(File entry:entries)if(!hiddenFromCatalog(entry)&&entry.getName().length()>=HFS_NAME_MAX/2&&sameMacName(macNameOf(entry),name))return entry.getCanonicalFile();}
		if(result==null||result.exists()||isAscii(bytes))return result;
		for(StackTextEncoding other:StackTextEncoding.values()){
			if(other==encoding)continue;
			String alternative;try{alternative=other.decode(bytes);}catch(RuntimeException e){continue;}
			if(alternative.equals(name)||unsafe(alternative)||alternative.indexOf('\ufffd')>=0)continue;
			try{File candidate=resolveName(currentDirectory,alternative);if(candidate!=null&&candidate.exists())return candidate;}catch(SecurityException e){}
		}
		// A file still to be created in a folder only another encoding reaches (Double-XX/Lite builds "...:Double-XX/Lite ƒ:TinyEdit"
		// under a Japanese session): the folder part resolved on its own, the new leaf name in the program's encoding.
		// (A Shift-JIS trail byte is never ':', so splitting the bytes there is safe.)
		int colon=-1;for(int i=bytes.length-2;i>0&&colon<0;i--)if(bytes[i]==':')colon=i;
		File parentOfResult=result.getParentFile();
		if(colon>0&&(parentOfResult==null||!parentOfResult.isDirectory())){
			File parent;try{parent=resolveBytes(currentDirectory,Arrays.copyOf(bytes,colon+1));}catch(SecurityException e){parent=null;}
			String leaf=decodeName(Arrays.copyOfRange(bytes,colon+1,bytes.length));if(leaf.endsWith(":"))leaf=leaf.substring(0,leaf.length()-1);
			if(parent!=null&&parent.isDirectory()&&!leaf.isEmpty()&&!unsafe(leaf))return resolveName(parent,leaf);
		}
		return result;
	}
	/** '/' is an ordinary character of a Mac name (it becomes ':' in the host name, see {@link #programName}), never a host path separator
	 *  here. So is a backslash except on Windows, where it separates host folders: a Shift-JIS name with the trail byte $5C (能, 表, ソ) read as
	 *  Mac OS Roman has one before the other encodings are tried - an English stack's FolderSize of the System Folder (its
	 *  Extensions:スクリプティング機能追加) recursed until the VM crashed, GetDInfo said "File not found". */
	private static boolean unsafe(String name){return File.separatorChar=='\\'&&name.indexOf('\\')>=0||name.indexOf('\0')>=0;}
	private static boolean isAscii(byte[] bytes){for(byte b:bytes)if(b<0)return false;return true;}
	/** HFS names are case-insensitive. Normalization also makes names extracted as NFC/NFD interchangeable on Linux. */
	private static boolean sameMacName(String a,String b){
		return Normalizer.normalize(a,Normalizer.Form.NFC).equalsIgnoreCase(Normalizer.normalize(b,Normalizer.Form.NFC));
	}
	private static File resolveHostComponent(File directory,String name){
		File exact=new File(directory,name);if(exact.exists())return exact;
		File[] entries=directory.listFiles();if(entries!=null)for(File entry:entries)if(sameMacName(entry.getName(),name))return entry;
		String unpacked=unarchiverName(name);
		if(unpacked!=null&&entries!=null)for(File entry:entries)if(sameMacName(entry.getName(),unpacked))return entry;
		if(entries!=null)for(String other:otherReadings(name)){
			for(File entry:entries)if(sameMacName(entry.getName(),other))return entry;
			String otherUnpacked=unarchiverName(other);
			if(otherUnpacked!=null)for(File entry:entries)if(sameMacName(entry.getName(),otherUnpacked))return entry;
		}
		return exact;
	}
	/**
	 * The same name bytes read in the other of the two encodings host names here come in: a stack opened as Japanese asks for
	 * "Brｿderbund MooV" (the byte $BF of Myst Preview's "Brøderbund", read as Shift-JIS), whose file is "Brøderbund MooV" - on a
	 * Mac both are the same bytes on the disk. Empty for an ASCII name or one that is no valid string in the other encoding.
	 */
	static List<String> otherReadings(String name){
		List<String> out=new ArrayList<String>();
		boolean ascii=true;for(int i=0;i<name.length();i++)if(name.charAt(i)>=0x80){ascii=false;break;}
		if(ascii)return out;
		StackTextEncoding[][] pairs={{StackTextEncoding.JAPANESE,StackTextEncoding.ROMAN},{StackTextEncoding.ROMAN,StackTextEncoding.JAPANESE}};
		for(StackTextEncoding[] pair:pairs){
			try{byte[] bytes=pair[0].encode(name);String read=pair[1].decode(bytes,0,bytes.length);
				if(read.indexOf('\ufffd')<0&&!read.equals(name)&&!out.contains(read))out.add(read);}
			catch(java.nio.charset.CharacterCodingException|RuntimeException e){}
		}
		return out;
	}
	/**
	 * The host name an archive tool gives a file whose UTF-8 name it took for Mac OS Roman ("Brøderbund MooV" unpacked as
	 * "Br√∏derbund MooV" - The Unarchiver did this with Myst Preview's StuffIt 5 archive, whose names are UTF-8), or null when
	 * the name is plain ASCII. On a real Mac the program would not find such a file either; here it finds the file it means.
	 */
	static String unarchiverName(String name){
		boolean ascii=true;for(int i=0;i<name.length();i++)if(name.charAt(i)>=0x80){ascii=false;break;}
		if(ascii)return null;
		try{return new String(name.getBytes(java.nio.charset.StandardCharsets.UTF_8),"x-MacRoman");}catch(java.io.UnsupportedEncodingException e){return null;}
	}
	/**
	 * The reverse of {@link #unarchiverName}: the name a host file "Br√∏derbund MooV" had in the archive ("Brøderbund MooV",
	 * byte $BF on the Mac), or null when its Mac OS Roman bytes are no strict UTF-8 with a non-ASCII letter. The Movie XCMD
	 * titled its window by the file's name, "Brﾃｸderbund MooV" (bytes $C3 $B8) for a Japanese stack, and Myst Preview's
	 * playBroderbund found no window "Brｿderbund MooV" - the logo never played.
	 */
	static String archivedName(String hostName){
		if(isAscii(hostName))return null;
		try{
			byte[] bytes=StackTextEncoding.ROMAN.encode(hostName);
			String utf8=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
				.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
			return isAscii(utf8)||utf8.equals(hostName)?null:Normalizer.normalize(utf8,Normalizer.Form.NFC);
		}catch(java.nio.charset.CharacterCodingException|RuntimeException e){return null;}
	}
	/** {@code name} in {@code directory}: the file of that name, else the one an archive tool named as {@link #unarchiverName} does; null when neither exists. */
	static File existingOrUnarchived(File directory,String name){
		if(directory==null||name==null)return null;
		File exact=new File(directory,name);if(exact.exists())return exact;
		String unpacked=unarchiverName(name);
		if(unpacked!=null){File mangled=new File(directory,unpacked);if(mangled.exists())return mangled;}
		for(String other:otherReadings(name)){
			File f=new File(directory,other);if(f.exists())return f;
			String otherUnpacked=unarchiverName(other);if(otherUnpacked!=null&&(f=new File(directory,otherUnpacked)).exists())return f;
		}
		return null;
	}
	/** One component of a path: the host entry of that name, else the long host name whose short HFS form ({@link #shortHfsName})
	 *  it is - a full path this File Manager handed out (GetFilePath, FSMakeFSSpec walks) names long folders that way. */
	private File resolveComponent(File directory,String part) throws IOException {
		File found=resolveHostComponent(directory,windowsName(part.replace('/',':')));
		if(found.exists()||part.indexOf('#')<=0||encoding.encodeReplacing(part).length>HFS_NAME_MAX||!directory.isDirectory())return found;
		File[] entries=directory.listFiles();
		if(entries!=null)for(File entry:entries)if(!hiddenFromCatalog(entry)&&entry.getName().length()>=HFS_NAME_MAX/2&&sameMacName(macNameOf(entry),part))return entry;
		return found;
	}
	private File resolveName(File currentDirectory,String name) throws IOException {if(root==null||currentDirectory==null||name==null||name.isEmpty())return null;
		// A Windows path that HyperTalk handed to an external ("ask file" gives "C:\folder\file"): the drive is a volume
		// (in Mac Japanese the backslash byte reads as a yen sign)
		String hostName=File.separatorChar=='\\'&&name.matches("(?s)[A-Za-z]:\u00a5.*")?name.replace('\u00a5','\\'):name;
		if(TTalk.isWindowsHostPath(hostName)&&!hostName.startsWith("\\\\")&&hostName.charAt(2)=='\\'){
			// a component may carry Mac characters Windows forbids (macCharsOfSfm made them readable for the external)
			String[] parts=hostName.substring(3).split("\\\\",-1);StringBuilder rebuilt=new StringBuilder(hostName.substring(0,3));
			for(int i=0;i<parts.length;i++){if(i>0)rebuilt.append('\\');rebuilt.append(sfmCharsOf(parts[i]));}
			hostName=rebuilt.toString();
		}
		if(TTalk.isWindowsHostPath(hostName)){File hostFile=new File(hostName).getCanonicalFile();if(volumeIndexFor(hostFile)<0)throw new SecurityException("path outside mounted volumes");return hostFile;}
		// an external given a Mac or Linux host path ("answer file" puts "/Users/me/Stacks/x.txt" into it, and G-XCollection's
		// installer hands that to MtCRX): the file there
		if(stackShadows!=null&&hostSlashPath(name)){File hostFile=new File(name).getCanonicalFile();if(volumeIndexFor(hostFile)<0)throw new SecurityException("path outside mounted volumes");return stackShadow(hostFile);}
		boolean relative=name.startsWith(":");String[] components=name.split(":",-1);int first=relative?1:0;File result=currentDirectory;if(!relative&&components.length>1){int index=volumeIndexNamed(components[0]);if(index>=0)result=volumes.get(index);
			else{ // a game that hard-codes its CD's name ("Dark Forces:Dark Forces Data:...") run from the folder it was copied to: that folder, or one above the current directory, stands in for the disc
				File stand=null;for(File up=currentDirectory.getCanonicalFile();up!=null&&stand==null;up=up.getParentFile())if(sameMacName(macNameOf(up),components[0])&&volumeIndexFor(up)>=0)stand=up;
				if(stand==null)throw new NoSuchVolume(components[0]);result=stand;}
			first=1;}for(int i=first;i<components.length;i++){String part=components[i];if(part.equals(".")||part.equals(".."))throw new SecurityException("unsafe classic path");if(part.isEmpty()){if(i==components.length-1)continue;File parent=result.getCanonicalFile().getParentFile();if(parent==null||isVolumeRoot(result.getCanonicalFile()))throw new SecurityException("unsafe classic path");result=parent;continue;}result=resolveComponent(result,part);}
		// A file that only exists as a MacBinary container ("Name" -> "Name.bin") answers to the name in its header, so
		// self-extracting archives and other programs that open themselves by their GetAppParms name find their forks.
		if(!result.exists()){File container=macBinaryNamed(result.getParentFile(),result.getName());if(container!=null)result=container;}
		// HyperTalk's own paths are host paths with ':' for '/' ("the long name of this stack" = stack ":Users:me:Stacks:X.hzb"),
		// so an external that builds a file path from them (ヤスコDEけいさん's UxMovie: item 1 to -2 of the stack's path & ":BGM:")
		// passes ":Users:me:Stacks:BGM:song" - relative to HFS, but meant from the host root; taken so when it names nothing relative.
		// A folder path ends in ':' (Free Fighters -NEXT-'s MtFilesInFldr gets item 1 to -2 of the stack's path & ":Resource Stacks:")
		int lastComponent=components.length>1&&components[components.length-1].isEmpty()?components.length-2:components.length-1;
		if(relative&&!result.exists()&&File.separatorChar=='/'&&lastComponent>=2){
			StringBuilder host=new StringBuilder();for(int i=1;i<=lastComponent;i++){if(components[i].isEmpty()){host=null;break;}host.append('/').append(components[i].replace('/',':'));}
			// The same goes for a file or folder still to be made in a host folder that exists, where the relative reading has no
			// folder either (G-XCollection's MtWriteFile/MtCreateFldr given getFldrPath(the stack's path) & "work:": dirNFErr -43)
			if(host!=null){File absolute=new File(host.toString()),parent=absolute.getParentFile(),relativeParent=result.getParentFile();
				boolean creating=!absolute.exists()&&parent!=null&&parent.isDirectory()&&(relativeParent==null||!relativeParent.isDirectory());
				if((absolute.exists()||creating)&&volumeIndexFor(absolute.getCanonicalFile())>=0)result=absolute;}}
		File canonical=result.getCanonicalFile();if(volumeIndexFor(canonical)<0)throw new SecurityException("path outside mounted volumes");return stackShadow(canonical);}
	/**
	 * File names arrive in the program's text encoding. A name that is well-formed multi-byte UTF-8 is taken as
	 * UTF-8 instead: some self-extracting archives store names that way and, lacking the Text Encoding
	 * Converter, hand them to the File Manager unconverted (Mac Japanese text is practically never valid UTF-8).
	 */
	String decodeName(byte[] bytes){
		int utf8=utf8Length(bytes);
		if(utf8>0)return hostNul(new String(bytes,0,utf8,java.nio.charset.StandardCharsets.UTF_8));
		return hostNul(encoding.decode(bytes));
	}
	/**
	 * A NUL is an ordinary character of an HFS name but cannot be in a host name: it is kept as U+2400 SYMBOL FOR NULL, as Mac OS X
	 * shows such names ({@link #programName} maps it back). ShrinkWrap's installer creates "<NUL>Aladdin Transaction Info<NUL>" in the
	 * System Folder and gave up with "Installation did not complete successfully" when that was bdNamErr.
	 */
	private static String hostNul(String name){return name.indexOf('\0')<0?name:name.replace('\0','\u2400');}
	/** Length of the well-formed UTF-8 prefix when the bytes are multi-byte UTF-8 (a sequence cut off by the 31-byte name limit is dropped), else 0. */
	private static int utf8Length(byte[] b){
		boolean multi=false;int i=0;
		while(i<b.length){
			int c=b[i]&0xff;
			if(c<0x80){i++;continue;}
			int n=c>=0xc2&&c<=0xdf?1:c>=0xe0&&c<=0xef?2:c>=0xf0&&c<=0xf4?3:-1;
			if(n<0)return 0;
			if(i+n>=b.length)return multi?i:0; // truncated final character
			for(int k=1;k<=n;k++)if((b[i+k]&0xc0)!=0x80)return 0;
			i+=n+1;multi=true;
		}
		return multi?b.length:0;
	}
	private void writePascal(int pointer,String value) throws M68kCpuException {writePascal(pointer,value,255);}
	private void writePascal(int pointer,String value,int maximumLength) throws M68kCpuException {byte[] bytes=encoding.encodeReplacing(value);int length=Math.min(maximumLength,bytes.length);memory.writeByte(pointer,length);for(int i=0;i<length;i++)memory.writeByte(pointer+1+i,bytes[i]);}

	static String name(int trap){switch(trap){case 0x00:return "Open";case 0x01:return "Close";case 0x02:return "Read";case 0x03:return "Write";case 0x07:return "GetVolInfo";case 0x08:return "Create";case 0x09:return "Delete";case 0x0a:return "OpenRF";case 0x0b:return "Rename";case 0x0c:return "GetFileInfo";case 0x0d:return "SetFileInfo";case 0x10:return "Allocate";case 0x11:return "GetEOF";case 0x12:return "SetEOF";case 0x13:return "FlushVol";case 0x14:return "GetVol";case 0x15:return "SetVol";case 0x18:return "GetFPos";case 0x41:return "SetFLock";case 0x42:return "RstFLock";case 0x44:return "SetFPos";case 0x45:return "FlushFile";case 0x60:return "HFSDispatch";default:return null;}}
	private static boolean sameLength(OpenFile open,long length){try{return open.file.length()==length;}catch(IOException e){return false;}}
	/** Whether the program may write through this open file: asked for (once) at the first write when it was opened without the permission. */
	private boolean mayWrite(OpenFile open){
		if(!open.writable)return false;
		if(open.askAtWrite){if(!Sandbox.allowFile(open.path,Sandbox.Access.WRITE,requester))return false;open.askAtWrite=false;}
		return true;
	}
	/** The data fork the program opened last (still open) when it is {@code length} bytes long, or null (the file behind a RAM disk). */
	File lastOpenDataForkOfLength(long length){
		OpenFile last=null;
		for(OpenFile f:files.values())if(!f.resourceFork&&f.path!=null)last=f;
		return last!=null&&last.view==null&&last.path.isFile()&&last.path.length()==length?last.path:null;
	}
	private static final class OpenFile {RandomAccessFile file;File path;final boolean writable,resourceFork,attached;
		/** {@code view}: the converted text copy this handle reads and writes (a fresh file gets one when its text is first synced, see {@link #promoteNewText}); {@code fresh}: a data fork empty when opened (a file the program is creating); {@code dirty}: written or resized through this handle; {@code binary}: a fresh file seen holding bytes that are not text, or text that would not read back as written - it keeps the program's bytes. */
		HostView view;boolean fresh,dirty,binary;
		/** Opened for writing where the program may not write without asking (Sandbox): asked at its first write. */
		boolean askAtWrite;
		OpenFile(RandomAccessFile file,File path,boolean writable,boolean resourceFork){this(file,path,writable,resourceFork,false);}
		/** {@code attached}: a Resource Manager refNum answered through {@link #openFileFor}, not a file the program opened here. */
		OpenFile(RandomAccessFile file,File path,boolean writable,boolean resourceFork,boolean attached){this.file=file;this.path=path;this.writable=writable;this.resourceFork=resourceFork;this.attached=attached;}}
	static final class Metadata {byte[] finderInfo=new byte[16],extendedFinderInfo=new byte[16];int creationDate,modificationDate,backupDate,fileNumber;boolean locked;
		/** The host's own type when it differs from the one shown (a PNG shown as PICT); 0 = the same. */
		int hostType;Metadata(int creationDate,int modificationDate,int fileNumber){this.creationDate=creationDate;this.modificationDate=modificationDate;this.fileNumber=fileNumber;}}
	private static final class ResourceFork {final File storage;ResourceFork(File storage){this.storage=storage;}}
	/** A host text file's converted copy: {@code storage} holds the program's form (null: the file needed no conversion), {@code original} what the copy held before the program wrote it, {@code stamp} the host file's size and date when the copy was made (a change from outside makes a new copy), {@code open} the handles on it. */
	private static final class HostView {File storage;TextFile68k.Form form;boolean picture;byte[] original;long stamp,synced;int open;HostView(long stamp){this.stamp=stamp;}}
	private static final class RangeLock {final int reference;final long start,end;RangeLock(int reference,long start,long end){this.reference=reference;this.start=start;this.end=end;}boolean overlaps(long otherStart,long otherEnd){return start<otherEnd&&otherStart<end;}}
	private static final class WorkingDirectory {final int directoryId,processId;WorkingDirectory(int directoryId,int processId){this.directoryId=directoryId;this.processId=processId;}}
}

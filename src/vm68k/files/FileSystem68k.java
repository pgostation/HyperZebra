import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File system view of a hosted application: the host volumes, a virtual
 * System Folder (Preferences, Extensions, MacTCP DNR), FSSpec helpers, the
 * Device Manager entry points used by drivers such as MacTCP, and the extra
 * HFSDispatch selectors the sandboxed XCMD File Manager does not offer.
 */
final class FileSystem68k {
	private final Toolbox68k tb;
	private final M68kMemory memory;
	final FileManager68k fileManager;
	final File systemFolder;
	/** Name of the startup disk seen by programs: the folder that holds the virtual System Folder. */
	static final String BOOT_VOLUME_NAME="HyperZebra HD";
	private final Map<Integer,String> drivers=new HashMap<Integer,String>();
	{drivers.put(QuickDraw68k.VIDEO_DRIVER_REF,".Display_Video_HyperZebra");drivers.put(FileManager68k.DISK_DRIVER_REF,DISK_DRIVER);drivers.put(FileManager68k.CD_DRIVER_REF,".AppleCD");drivers.put(-5,".Sony");} // the main screen's driver; fixed disks; optical images; the floppy driver
	private static final String DISK_DRIVER=".HyperZebra_Disk";
	private int nextDriverRef=-40;

	/** The invisible desktop database files at the root of a startup disk (see the constructor; FileManager68k keeps them out of folder walks). */
	static final String[] DESKTOP_DATABASE_FILES={"Desktop DB","Desktop DF"};
	/** The startup disk "HyperZebra HD": the folder holding the virtual System Folder. */
	static File bootVolumeDirectory(){
		return new File(new File(System.getProperty("user.home")),"Library"+File.separator+"Application Support"+File.separator+"HyperZebra");
	}

	/**
	 * The host folder behind a volume name of the File Manager that is not a Windows drive letter: "HyperZebra HD" (the startup
	 * disk), "Home", and on the Mac and Linux "Macintosh HD" (the root). HyperTalk reads a path an external built with these
	 * ("HyperZebra HD:System Folder:Preferences:Blah Blob Data" from FindFolder) through this. Null for any other name.
	 */
	static File hostVolumeNamed(String name){
		if(name==null)return null;
		if(name.equalsIgnoreCase(BOOT_VOLUME_NAME))return bootVolumeDirectory();
		if(name.equalsIgnoreCase("Home"))return new File(System.getProperty("user.home"));
		if(name.equalsIgnoreCase("Macintosh HD")&&File.separatorChar=='/')return new File("/");
		//a mounted disk image ("Cosmic Osmo CD:Cosmic Osmo Files:CO.Resource" once the CD's .iso is mounted), then a disk the host mounted
		for(DiskImages.Mounted m:DiskImages.mounted())if(m.name.equalsIgnoreCase(name)&&m.folder.isDirectory())return m.folder;
		if(GMenu.isMacOSX()&&!name.isEmpty()&&name.indexOf('/')<0){File v=new File("/Volumes",name);if(v.isDirectory())return v;}
		//the first volume of an open stack's externals: its folder, under the folder's own name (FileManager68k(memory, stack)). A path an
		//external built by walking up the catalog names it ("田中求之:work:" from G-XCollection's MtSelectFldr), and the stack then
		//opens that path with "open file"
		if(PCARD.pc!=null&&PCARD.pc.stack!=null&&!name.isEmpty()){
			List<OStack> stacks=new ArrayList<OStack>();stacks.add(PCARD.pc.stack);
			if(PCARD.pc.stack.usingStacks!=null)stacks.addAll(PCARD.pc.stack.usingStacks);
			for(OStack stack:stacks){File folder=externalsVolume(stack);if(folder!=null&&folder.isDirectory()&&sameVolumeName(folder.getName(),name))return folder;}
		}
		return null;
	}
	/** The folder an external of {@code stack} has as its first volume (see FileManager68k(memory, stack)), or null. */
	static File externalsVolume(OStack stack){
		if(stack==null)return null;
		File source=stack.file!=null?stack.file:stack.path!=null&&!stack.path.isEmpty()?new File(stack.path):null;
		if(source==null)return null;
		File folder=source.isDirectory()?source:source.getParentFile();
		return folder==null?null:FileManager68k.convertedFrom(folder);
	}
	private static boolean sameVolumeName(String a,String b){
		return java.text.Normalizer.normalize(a,java.text.Normalizer.Form.NFC).equalsIgnoreCase(java.text.Normalizer.normalize(b,java.text.Normalizer.Form.NFC));
	}

	/**
	 * The volumes classic paths are named by outside a program's own: the startup disk "HyperZebra HD", the host's disks
	 * ("Macintosh HD" for the root, or the drive letters), "Home" and the disk images mounted. An external's File Manager mounts
	 * these, and HyperTalk writes paths with them ({@link #macPathOf}), so both name a file alike.
	 */
	static void namingVolumes(List<File> volumes,List<String> names){
		File boot=bootVolumeDirectory();
		if(boot.isDirectory()){volumes.add(boot);names.add(BOOT_VOLUME_NAME);}
		if(!Boolean.getBoolean("hz68k.bootonly"))hostVolumes(volumes,names);
		for(DiskImages.Mounted image:DiskImages.mounted())if(image.folder.isDirectory()){volumes.add(image.folder);names.add(image.name);}
	}

	/**
	 * A host file as a classic full path: the volume that holds it most closely ({@link #namingVolumes}), then its folders,
	 * "Home:Documents:Stacks:Game" - HyperCard's own form (J-2.3: the long name "HyperZebra HD:HCProbe:Home", and the same path
	 * from an external that walks the catalog). A ':' in a host name is a '/' in the Mac name, as the File Manager shows it.
	 * Null when no volume holds the file.
	 */
	static String macPathOf(File file){
		if(file==null)return null;
		File f;try{f=file.getCanonicalFile();}catch(IOException e){f=file.getAbsoluteFile();}
		List<File> volumes=new ArrayList<File>();List<String> names=new ArrayList<String>();namingVolumes(volumes,names);
		String p=f.getPath();int best=-1;String bestRoot=null;
		for(int i=0;i<volumes.size();i++){
			String v;try{v=volumes.get(i).getCanonicalPath();}catch(IOException e){continue;}
			String prefix=v.endsWith(File.separator)?v:v+File.separator;
			if((p.equals(v)||p.startsWith(prefix))&&(bestRoot==null||v.length()>bestRoot.length())){best=i;bestRoot=v;}
		}
		if(best<0)return null;
		StringBuilder out=new StringBuilder(names.get(best));
		String rest=p.equals(bestRoot)?"":p.substring(bestRoot.endsWith(File.separator)?bestRoot.length():bestRoot.length()+1);
		for(String part:rest.split(java.util.regex.Pattern.quote(File.separator)))if(!part.isEmpty())out.append(':').append(part.replace(':','/'));
		if(rest.isEmpty())out.append(':'); // a volume itself ends with its colon
		return java.text.Normalizer.normalize(out.toString(),java.text.Normalizer.Form.NFC);
	}

	/** The host's disks as programs see them: Windows drive letters ("C", as in HyperTalk's "C:folder:file"), or the root as
	 *  "Macintosh HD", then the user's home folder as "Home". */
	private static void hostVolumes(List<File> volumes,List<String> names){
		File home=new File(System.getProperty("user.home"));
		File[] roots=File.listRoots();
		if(GMenu.isMacOSX()||roots==null||roots.length==0){volumes.add(new File("/"));names.add("Macintosh HD");}
		else{for(File root:roots){String n=root.getPath().replace("\\","").replace(":","");if(n.isEmpty())n="Macintosh HD";volumes.add(root);names.add(n);}}
		if(home.isDirectory()){volumes.add(home);names.add("Home");}
	}

	/**
	 * The files FSRefs stand for (HFS Plus API, FSRef = 80 opaque bytes): an FSRef made here holds 'hzFR' and the index of its
	 * file in this list, so equal files give equal FSRefs and the reference survives the file being renamed by the host.
	 */
	private final List<File> fsRefFiles=new ArrayList<File>();
	/** Writes the FSRef of {@code f} (80 bytes) at {@code ref}. */
	void writeFSRef(int ref,File f) throws M68kCpuException {
		if(ref==0)return;File c;try{c=f.getCanonicalFile();}catch(IOException e){c=f.getAbsoluteFile();}
		int i=fsRefFiles.indexOf(c);if(i<0){i=fsRefFiles.size();fsRefFiles.add(c);}
		memory.fill(ref,80,0);memory.writeLong(ref,0x687a4652);memory.writeLong(ref+4,i);
	}
	/** The file an FSRef stands for, or null when it is not one made here. */
	File fsRefFile(int ref) throws M68kCpuException {
		if(ref==0||memory.readLong(ref)!=0x687a4652)return null;int i=memory.readLong(ref+4);return i>=0&&i<fsRefFiles.size()?fsRefFiles.get(i):null;
	}
	FileSystem68k(Toolbox68k tb){
		this.tb=tb;memory=tb.memory;
		systemFolder=new File(bootVolumeDirectory(),"System Folder");
		if(tb.app==null){
			// An external sees the volumes HyperTalk names paths by (namingVolumes: HyperZebra HD, the host's disks, Home, disk
			// images; still behind the Sandbox), so the paths HyperCard hands around work both ways, and its stack's folder is the
			// default directory on the volume that holds it, as HyperCard's folder is on a Mac: an external that builds a path from
			// the File Manager (PBGetFCBInfo, then PBGetCatInfo up to the root) gets what `the long name` says. The folder used to
			// be a volume of its own ("<folder>:<stack>"), which no HyperTalk path matched (Dartmouth's ResInStack compares the
			// two) and above which an external could not go. FindFolder gave ":::Users:...:Preferences:" (a folder on no volume)
			// that "open file" could not use, and a path from "ask file" ("C:\...\t.txt") was on no volume either (UxWriteST).
			fileManager=new FileManager68k(memory,tb.stack);
			try{new File(systemFolder,"Preferences").mkdirs();}catch(Exception ignored){}
			List<File> more=new ArrayList<File>();List<String> moreNames=new ArrayList<String>();
			namingVolumes(more,moreNames);
			for(int i=0;i<more.size();i++)fileManager.addVolume(more.get(i),moreNames.get(i));
			for(DiskImages.Mounted image:DiskImages.mounted())fileManager.lockVolume(image.folder);
			File stackFolder=fileManager.stackFolder();
			if(stackFolder!=null){ // on no volume (-Dhz68k.bootonly): a volume of its own as before
				if(!fileManager.onVolume(stackFolder))fileManager.addVolume(stackFolder,stackFolder.getName());
				try{fileManager.setDefaultDirectory(stackFolder);}catch(IOException ignored){}
			}
			fileManager.setBlessedFolder(systemFolder);
			fileManager.setRequester(Sandbox.scriptRequester());
			fileManager.shareReferences(tb::allocateFileReference,tb.resources::fileOf);fileManager.shareResourceFileReferences(tb.resources::openReferences);fileManager.shareResourceFileWritable(tb.resources::writableReference);
			fileManager.shareSystemFile(tb.resources::systemRefNum,systemFile());
			fileManager.setMoveListener(this::rebaseFSRefs);
			fileManager.setOpenElsewhere(this::openElsewhere);
			// an external's PICT file too: the host decoder knows no shapes or colours (xDraw's savePictFile wrote an empty PNG)
			fileManager.setPictureRenderer(this::renderPicture);
			return;
		}
		List<File> volumes=new ArrayList<File>();List<String> names=new ArrayList<String>();
		// The startup disk (vRefNum -1, BootDrive, sysVRefNum) is the folder holding the virtual System Folder, so installers
		// that put their files "on the startup disk" find a writable root
		// next to the System Folder, as on a real Mac. It nests inside the Home volume; the longest match wins in volumeIndexFor.
		File bootVolume=systemFolder.getParentFile();
		try{bootVolume.mkdirs();}catch(Exception ignored){}
		// Every Mac volume has "Desktop DB" and "Desktop DF" at its root; Toast CD-ROM Pro looks for the startup disk's before it accepts a
		// list of files (FSMakeFSSpec -> fnfErr dropped the list: "Result Code = -43"). Empty files do: the Finder makes its database anew.
		if(bootVolume.isDirectory())for(String n:DESKTOP_DATABASE_FILES){File d=new File(bootVolume,n);try{if(!d.exists())d.createNewFile();}catch(Exception ignored){}}
		if(bootVolume.isDirectory()){volumes.add(bootVolume);names.add(BOOT_VOLUME_NAME);}
		// Debug aid: -Dhz68k.bootonly=true mounts the startup disk alone (a program under test cannot reach the host's folders)
		boolean bootOnly=Boolean.getBoolean("hz68k.bootonly");
		if(!bootOnly)hostVolumes(volumes,names);
		// External and network disks are volumes of their own, as on a Mac (the walk of Macintosh HD leaves /Volumes out:
		// its "Macintosh HD" link back to / could send a disk search round in circles)
		File[] mounted=new File("/Volumes").listFiles();
		if(GMenu.isMacOSX()&&mounted!=null&&!bootOnly){java.util.Arrays.sort(mounted);
			for(File v:mounted){String n=v.getName();
				if(n.startsWith(".")||n.startsWith("com.apple.")||!v.isDirectory()||java.nio.file.Files.isSymbolicLink(v.toPath())||!v.canRead()||names.contains(n))continue;
				volumes.add(v);names.add(n);}}
		// disk images the user mounted (DiskImages): locked volumes after the host's
		List<File> images=new ArrayList<File>();
		for(DiskImages.Mounted image:DiskImages.mounted())if(image.folder.isDirectory()&&!names.contains(image.name)){volumes.add(image.folder);names.add(image.name);images.add(image.folder);}
		try{new File(systemFolder,"Preferences").mkdirs();new File(systemFolder,"Extensions").mkdirs();new File(systemFolder,"Control Panels").mkdirs();new File(systemFolder,"Fonts").mkdirs();}catch(Exception ignored){}
		ensureSystemFile();
		fileManager=new FileManager68k(memory,tb.encoding,volumes,names);
		for(File image:images)fileManager.lockVolume(image);
		fileManager.setBlessedFolder(systemFolder);
		fileManager.setRequester(tb.name);
		fileManager.shareReferences(tb::allocateFileReference,tb.resources::fileOf);fileManager.shareResourceFileReferences(tb.resources::openReferences);fileManager.shareResourceFileWritable(tb.resources::writableReference);
		fileManager.shareSystemFile(tb.resources::systemRefNum,systemFile());
		fileManager.setMoveListener(this::rebaseFSRefs);
		fileManager.setOpenElsewhere(this::openElsewhere);
		fileManager.setPictureRenderer(this::renderPicture); // a PICT the program saved is played by its own QuickDraw (fonts, patterns) before it becomes a PNG
		fileManager.setFcbListener(new FileManager68k.FcbListener(){
			@Override public void opened(int reference,File path,boolean resourceFork,boolean writable,long eof){tb.describeFileControlBlock(reference,path,resourceFork,writable,eof);}
			@Override public void closed(int reference){tb.releaseFileReference(reference);}});
		File appDir=tb.app.file.getAbsoluteFile().getParentFile();
		try{if(appDir!=null)fileManager.setDefaultDirectory(appDir);}catch(IOException ignored){}
	}

	/** Routes OS file/device traps; returns false when the trap is not a file call. */
	boolean handle(M68kCpu cpu,int opcode) throws M68kException {
		int trap=opcode&0xff;
		boolean async=(opcode&0x0400)!=0;
		if(trap<=0x06){
			int pb=cpu.a[0];
			if(tb.devices.handle(cpu,opcode,pb,trap,async))return true; // drivers the program installed (DrvrInstall)
			if(trap==0x00&&(opcode&0x0200)==0){int name=memory.readLong(pb+18);if(name!=0&&memory.readByte(name)>0&&memory.readByte(name+1)=='.'){return openDriver(cpu,pb,tb.readPascal(name),async);}}
			int ref=(short)memory.readWord(pb+24);
			if(trap>=0x02&&ref==SoundDriver68k.REF_NUM)return tb.soundDriver.handle(cpu,pb,trap,async); // .Sound: the ROM glue uses its refNum without opening it
			if(trap>=0x01&&(ref==AppleTalk68k.MPP_REF||ref==AppleTalk68k.ATP_REF||ref==AppleTalk68k.XPP_REF||ref==AppleTalk68k.DSP_REF))return tb.appleTalk.handle(cpu,pb,trap,async); // .MPP/.ATP/.XPP: the AppleTalk glue uses the fixed refNums too
			if(drivers.containsKey(ref)){
				String driver=drivers.get(ref);int result;
				if(trap==0x01){drivers.remove(ref);if(isSerialDriver(driver))tb.devices.setRomUnitOpened(ref,false);result=0;finish(cpu,pb,result,async);return true;}
				if(driver.equalsIgnoreCase(".IPP")){return tb.macTcp.control(cpu,pb,trap,async);}
				if(driver.startsWith(".Display")&&(trap==0x04||trap==0x05)){result=tb.quickDraw.videoDriver(pb,trap==0x05);if(Mac68kApi.TRACE)System.err.println("[68K] video driver "+(trap==0x05?"Status":"Control")+" csCode "+(short)memory.readWord(pb+26)+" -> "+result);finish(cpu,pb,result,async);return true;}
				if(isSerialDriver(driver))return serial(cpu,pb,trap,async);
				if(driver.equals(DISK_DRIVER)||driver.equals(".AppleCD"))return diskDriver(cpu,pb,trap,async,driver.equals(".AppleCD"));
				if(driver.equalsIgnoreCase(".Sony")&&trap>=0x02&&trap<=0x05){ // no floppy drives: every drive number is nsDrvErr; image drivers may ask .Sony whether a volume's drive is a floppy
					if(Mac68kApi.TRACE)System.err.println("[68K] .Sony "+(trap==0x04?"Control":trap==0x05?"Status":"Prime")+" drive "+(short)memory.readWord(pb+22)+" csCode "+(short)memory.readWord(pb+26)+" -> nsDrvErr");
					finish(cpu,pb,-56,async);return true;}
				result=trap==0x06?0:trap==0x02||trap==0x03?-19:-17; // readErr/writErr/controlErr
				finish(cpu,pb,result,async);return true;
			}
			if(trap==0x04||trap==0x05||trap==0x06){if(Mac68kApi.TRACE)System.err.println("[68K] "+(trap==0x04?"Control":trap==0x05?"Status":"KillIO")+" on unknown refNum "+ref+" (ioVRefNum "+(short)memory.readWord(pb+22)+", csCode "+(short)memory.readWord(pb+26)+") -> "+(ref>=0?"badUnitErr":"rfNumErr"));finish(cpu,pb,ref>=0?-21:-51,async);return true;} // refNum 0 and up is no driver unit at all: badUnitErr
		}
		if(trap==0x0b&&!fileManager.guestDrives.isEmpty()&&renameAddedVolume(cpu,(opcode&0x0200)!=0,async))return true;
		if(trap==0x60||trap==0xa8){int selector=cpu.d[0]&0xffff;if(hfsPlusParamBlock(cpu,trap,selector,async))return true;} // the HFS Plus parameter-block calls ($A2A8: PBReadFork/PBWriteFork)
		if(trap==0x60){int selector=cpu.d[0]&0xffff;if(hfsExtra(cpu,selector,async))return true;}
		switch(trap){ // volume-level calls on the one mounted (host folder) volume
			case 0x0f:{ // MountVol: volOnLinErr, every volume is already mounted - except a disk image inserted while running:
				// a program that takes its diskEvt from GetOSEvent (no automatic mount there) mounts the drive itself and ejects
				// the disk again on an error (VISE installers asking for the next disk). That drive answers noErr + its vRefNum.
				int pb=cpu.a[0],drive=(short)memory.readWord(pb+22);
				if(addedDrives.containsKey(drive)){int[] mounted=mountAddedDrive(cpu,drive);if(mounted[1]!=0)memory.writeWord(pb+22,mounted[1]);finish(cpu,pb,mounted[0],async);return true;}
				int onDrive=drive>0?fileManager.volumeIndexOfDrive(drive):-1;
				if(onDrive>=0&&fileManager.volumeLocked(onDrive)){memory.writeWord(pb+22,-(onDrive+1));finish(cpu,pb,0,async);return true;}
				finish(cpu,pb,-55,async);return true;}
			case 0x0e:finish(cpu,cpu.a[0],-47,async);return true;   // UnmountVol: fBsyErr, the volume stays
			case 0x17:case 0x35:{ // Eject / OffLine: host volumes stay mounted, like a fixed disk; a volume or drive that does not exist is nsvErr
				int result;try{result=fileManager.namedVolume(memory.readWord(cpu.a[0]+22))!=null?0:-35;}catch(java.io.IOException e){result=-36;}
				finish(cpu,cpu.a[0],result,async);return true;}
			case 0x43:finish(cpu,cpu.a[0],0,async);return true;     // SetFVers: version numbers are not kept
			default:break;
		}
		if(!isFileTrap(trap))return false;
		int pb=cpu.a[0];
		int selector=cpu.d[0]&0xffff;
		boolean handled=fileManager.handle(cpu,opcode);
		if(Mac68kApi.TRACE){int name=memory.readLong(pb+18);String n="";try{if(name!=0)n=tb.readPascal(name);}catch(M68kCpuException e){}
			System.err.println("[file] "+(FileManager68k.name(trap)==null?"$"+Integer.toHexString(opcode):FileManager68k.name(trap))+(trap==0x60?" #"+selector:"")+" vRefNum="+(short)memory.readWord(pb+22)+" dirID="+memory.readLong(pb+48)+" refNum="+(short)memory.readWord(pb+24)+" idx/misc="+(short)memory.readWord(pb+28)+" name="+n+(trap==0x02||trap==0x03||trap==0x44?" buffer=$"+Integer.toHexString(memory.readLong(pb+32))+" req="+memory.readLong(pb+36)+" act="+memory.readLong(pb+40)+" posMode="+memory.readWord(pb+44)+" posOffset="+memory.readLong(pb+46):"")+" -> "+(short)memory.readWord(pb+16));}
		if(!handled){finish(cpu,pb,-50,async);return true;}
		if(async)completion(cpu,pb);
		return true;
	}

	private static boolean isFileTrap(int trap){return trap<=0x15||trap==0x18||trap==0x41||trap==0x42||trap==0x44||trap==0x45||trap==0x60;}

	void finish(M68kCpu cpu,int pb,int result,boolean async) throws M68kException {
		memory.writeWord(pb+16,result);cpu.d[0]=(cpu.d[0]&0xffff0000)|(result&0xffff);
		if(async)completion(cpu,pb);
	}
	/** Calls the ioCompletion routine of an asynchronous parameter block (A0 = pb, D0 = result). */
	void completion(M68kCpu cpu,int pb) throws M68kException {
		int proc=memory.readLong(pb+12);if(proc==0)return;
		int[] d=cpu.d.clone(),a=cpu.a.clone();
		cpu.a[0]=pb;cpu.d[0]=(short)memory.readWord(pb+16);
		try{cpu.call(proc);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}

	private boolean openDriver(M68kCpu cpu,int pb,String name,boolean async) throws M68kException {
		int ref;
		if(name.equalsIgnoreCase(".MPP")||name.equalsIgnoreCase(".ATP")||name.equalsIgnoreCase(".XPP")||name.equalsIgnoreCase(".DSP"))return tb.appleTalk.open(cpu,pb,name,async);
		if(name.equalsIgnoreCase(".IPP"))ref=tb.macTcp.driverRefNum();
		else if(standardDriverRef(name)!=0)ref=standardDriverRef(name);
		else if(!isAppleDriver(name)&&!hasDriverResource(name)){
			// nothing installed under that name and no 'DRVR' to load: dInstErr, as from the ROM. Extensions open their own
			// driver to see whether it is already in place - QuicKeys' INIT took the old success for "engine loaded", never
			// installed its 'CDRV' engine, and its control panel came up with everything dimmed.
			if(Mac68kApi.TRACE)System.err.println("[68K] OpenDriver "+name+" -> dInstErr (not installed)");
			finish(cpu,pb,-26,async);return true;}
		else{
			// a program's own 'DRVR' in the resource chain (MacinTalk 1's ".SPEECH" or FPlay's ".SndDriver" in a HyperCard stack):
			// the ROM installs it at the unit its resource ID names and runs its open routine - MacinTalk's glue then calls
			// Control on -(ID+1) itself, and the generic refNum below left it "Control on unknown refNum" and jumping to 0
			if(!isAppleDriver(name)&&installDriverResource(name))return tb.devices.handle(cpu,0xa000,pb,0x00,async);
			ref=nextDriverRef--;if(ref==AppleTalk68k.DSP_REF)ref=nextDriverRef--;
		}
		drivers.put(ref,name);memory.writeWord(pb+24,ref);
		if(isSerialDriver(name))tb.devices.setRomUnitOpened(ref,true);
		if(Mac68kApi.TRACE)System.err.println("[68K] OpenDriver "+name+" -> "+ref);
		finish(cpu,pb,0,async);return true;
	}
	/**
	 * Drivers of Apple's system software a Mac of the era had in its unit table (ROM, System file, Apple extensions): their
	 * Open still succeeds with a refNum here, whose calls are answered generically, though the VM does not run them.
	 */
	private static final String[] APPLE_DRIVER_PREFIXES={".Sony",".Sound",".AIn",".AOut",".BIn",".BOut",".MPP",".ATP",".XPP",".DSP",".IPP",
		".Print",".EDisk",".Backlight",".Display",".Video",".AppleCD",".ENET",".TOKN",".FDDI",".SCSI",".ATA",".Keyboard",".AFPTranslator"};
	private static boolean isAppleDriver(String name){
		for(String p:APPLE_DRIVER_PREFIXES)if(name.regionMatches(true,0,p,0,p.length()))return true;
		return false;
	}
	/** Whether the resource chain has a 'DRVR' of that name, which the ROM would install and open. */
	private boolean hasDriverResource(String name){
		try{return tb.resources.findNamed(0x44525652,name,false)!=null;}catch(RuntimeException e){return false;}
	}
	/** Loads, locks and installs the resource chain's 'DRVR' of that name at unit = its ID; false when there is none or the unit is taken. */
	private boolean installDriverResource(String name){
		try{
			ResourceFile68k.Resource r=tb.resources.findNamed(0x44525652,name,false);
			if(r==null||r.id<0||r.id>=memory.readWord(DeviceManager68k.UNIT_NTRY_CNT))return false;
			int handle=tb.resources.handleFor(r);
			if(handle==0||memory.readLong(handle)==0)return false;
			tb.api.setHandleState(handle,tb.api.handleState(handle)|0x80); // HLock: a driver is called through its master pointer
			boolean ok=tb.devices.installDeskAccessory(handle,~r.id)!=0;
			if(Mac68kApi.TRACE)System.err.println("[68K] OpenDriver "+name+": 'DRVR' "+r.id+(ok?" installed":" unit taken"));
			return ok;
		}catch(M68kCpuException|RuntimeException e){return false;}
	}
	/** Unit-table refNums of the ROM/System drivers (IM II-192); some programs use them without checking OpenDriver's result. */
	private static int standardDriverRef(String name){
		if(name.equalsIgnoreCase(".AppleCD"))return FileManager68k.CD_DRIVER_REF;
		if(name.equalsIgnoreCase(".Sony"))return -5;if(name.equalsIgnoreCase(".Sound"))return -4;
		if(name.equalsIgnoreCase(".AIn"))return -6;if(name.equalsIgnoreCase(".AOut"))return -7;
		if(name.equalsIgnoreCase(".BIn"))return -8;if(name.equalsIgnoreCase(".BOut"))return -9;
		if(name.equalsIgnoreCase(".MPP"))return -10;if(name.equalsIgnoreCase(".ATP"))return -11;
		return 0;
	}
	/** Whether a serial driver (.AIn/.AOut/.BIn/.BOut) is open under {@code refNum}: the PowerPC Serial glue's calls go to it then. */
	boolean serialDriverOpen(int refNum){String name=drivers.get(refNum);return name!=null&&isSerialDriver(name);}
	private static boolean isSerialDriver(String name){return name.equalsIgnoreCase(".AIn")||name.equalsIgnoreCase(".AOut")||name.equalsIgnoreCase(".BIn")||name.equalsIgnoreCase(".BOut");}
	/**
	 * Serial ports with nothing connected: Control calls (SerReset/SerSetBuf/SerHShake...) succeed, Status reports an empty
	 * input buffer, writes are swallowed, and an asynchronous Read stays pending forever (ioResult = 1, no completion) like
	 * a real port with no incoming bytes - link-play code may re-issue its 1-byte read from the completion routine,
	 * so completing it with an error recursed until the Java stack overflowed.
	 */
	private boolean serial(M68kCpu cpu,int pb,int trap,boolean async) throws M68kException {
		int csCode=(short)memory.readWord(pb+26);
		if(Mac68kApi.TRACE)System.err.println("[68K] serial "+drivers.get((int)(short)memory.readWord(pb+24))+" "+(trap==0x02?"Read":trap==0x03?"Write":trap==0x04?"Control":trap==0x05?"Status":"KillIO")+(trap>=0x04?" csCode "+csCode:"")+(async?" async":""));
		switch(trap){
			case 0x02:if(async){memory.writeWord(pb+16,1);cpu.d[0]=(cpu.d[0]&0xffff0000);return true;}
				memory.writeLong(pb+40,0);finish(cpu,pb,0,false);return true;
			case 0x03:memory.writeLong(pb+40,memory.readLong(pb+36));finish(cpu,pb,0,async);return true;
			case 0x05:for(int i=0;i<18;i++)memory.writeByte(pb+28+i,0);finish(cpu,pb,0,async);return true; // SerGetBuf (2): 0 bytes waiting; SerStatus (8): all clear
			default:finish(cpu,pb,0,async);return true;
		}
	}

	/**
	 * Host-volume drivers: Status csCode 8 (kDriveStatus, and 6 as before) describes fixed disks or removable, read-only CDs.
	 * Control csCode 23 (kDriveInfo, also answered on Status) and DriverGestalt agree with the volume's driver identity.
	 * MacBench uses these facts to find the CD containing its video tests, even after opening a movie on that volume.
	 */
	private boolean diskDriver(M68kCpu cpu,int pb,int trap,boolean async,boolean optical) throws M68kException {
		int csCode=(short)memory.readWord(pb+26);
		int info=optical?0x00000b01:DRIVE_INFO,driverRef=optical?FileManager68k.CD_DRIVER_REF:FileManager68k.DISK_DRIVER_REF;
		if(Mac68kApi.TRACE)System.err.println("[68K] disk driver "+(trap==0x04?"Control":trap==0x05?"Status":"call "+trap)+" csCode "+csCode);
		switch(trap){
			case 0x05:
				if(csCode==6||csCode==8){int drive=(short)memory.readWord(pb+22);memory.writeWord(pb+28,0);memory.writeByte(pb+30,optical?0x80:0);memory.writeByte(pb+31,optical?2:8);memory.writeByte(pb+32,1);memory.writeByte(pb+33,0xff); // track, writeProt, diskInPlace (8: fixed, 2: removable), installed, sides
					memory.writeLong(pb+34,0);memory.writeWord(pb+38,1);memory.writeWord(pb+40,drive>0?drive:1);memory.writeWord(pb+42,driverRef);memory.writeWord(pb+44,0);memory.writeByte(pb+46,0xff);memory.writeByte(pb+47,0);memory.writeWord(pb+48,0); // qLink, qType, dQDrive, dQRefNum, dQFSID, twoSideFmt, needsFlush, diskErrs
					finish(cpu,pb,0,async);return true;}
				if(csCode==23){memory.writeLong(pb+28,info);finish(cpu,pb,0,async);return true;}
				if(csCode==43){ // DriverGestalt: csParam = selector, csParam+4 = response (Civilization II asks which drive is its CD)
					int selector=memory.readLong(pb+28),answer;
					switch(selector){
						case 0x64657674:answer=optical?0x6364726d:0x6469736b;break; // 'devt': 'cdrm' for optical images, 'disk' for fixed disks
						case 0x696e7466:answer=0x73637369;break; // 'intf': 'scsi'
						case 0x73796e63:answer=0x01000000;break; // 'sync': the driver completes calls synchronously
						case 0x76657273:answer=0x01008000;break; // 'vers': 1.0 final
						default:finish(cpu,pb,-18,async);return true;
					}
					memory.writeLong(pb+32,answer);finish(cpu,pb,0,async);return true;}
				finish(cpu,pb,-18,async);return true; // statusErr
			case 0x04:
				if(optical&&csCode>=100&&csCode<=127)return cdAudio(cpu,pb,csCode,async);
				if(csCode==21||csCode==22){memory.writeLong(pb+28,driveIcon());finish(cpu,pb,0,async);return true;} // physical/media icon: csParam = pointer to ICN# data (+ location string)
				if(csCode==23){memory.writeLong(pb+28,info);finish(cpu,pb,0,async);return true;} // kDriveInfo: csParam = the drive's type and attributes
				if(csCode>=100&&csCode<=127){finish(cpu,pb,-17,async);return true;} // the AppleCD audio calls on a hard disk: controlErr (Apple's CD Audio Toolkit took a disk for a playing CD)
				finish(cpu,pb,0,async);return true;
			case 0x02:case 0x03:finish(cpu,pb,-19,async);return true; // raw block access is not offered
			default:finish(cpu,pb,0,async);return true;
		}
	}

	/**
	 * The audio calls of the Apple CD-ROM driver (Control csCodes 100-127). An empty drive answers offLinErr as the real
	 * driver does: Audion polls ReadTOC and, given noErr with csParam untouched, showed an "Unrecognized CD" with tracks.
	 * A mounted CD image is a data disc of one track: its table of contents, the head at the start of that track and no
	 * audio play status. ReadAudioVolume/AudioControl (the drive's volume) need no disc; other calls are accepted.
	 */
	private boolean cdAudio(M68kCpu cpu,int pb,int csCode,boolean async) throws M68kException {
		if(csCode==112){memory.writeByte(pb+28,0xff);memory.writeByte(pb+29,0xff);finish(cpu,pb,0,async);return true;} // ReadAudioVolume: left, right
		if(csCode==109){finish(cpu,pb,0,async);return true;} // AudioControl
		int disc=opticalDisc((short)memory.readWord(pb+22));
		if(disc<0){finish(cpu,pb,-65,async);return true;} // offLinErr: no disc in the drive
		switch(csCode){
			case 100:{ // ReadTOC: csParam = type
				int type=memory.readWord(pb+28);
				if(type==1){memory.writeByte(pb+28,0x01);memory.writeByte(pb+29,0x01);memory.writeWord(pb+30,0);break;} // first and last track (BCD)
				if(type==2){writeMsf(pb+28,leadOut(disc));memory.writeByte(pb+31,0);break;} // lead-out start
				if(type==3){ // csParam+2 = buffer, +6 = its size, +8 = first track wanted (BCD): 4-byte entries control, M, S, F
					int buffer=memory.readLong(pb+30),size=memory.readWord(pb+34),track=memory.readByte(pb+36)&0xff;
					if(track<=1&&size>=4){memory.writeByte(buffer,4);writeMsf(buffer+1,150);} // track 1: data (control 4) at 00:02:00
					break;}
				finish(cpu,pb,-50,async);return true;}
			case 101: // ReadTheQSubcode: control, track, index, relative M S F, absolute M S F
				memory.writeByte(pb+28,4);memory.writeByte(pb+29,0x01);memory.writeByte(pb+30,0x01);writeMsf(pb+31,0);writeMsf(pb+34,150);break;
			case 107: // AudioStatus: status (5 = no audio play), play mode (stereo), control, absolute M S F
				memory.writeByte(pb+28,5);memory.writeByte(pb+29,9);memory.writeByte(pb+30,4);writeMsf(pb+31,150);break;
			default:break;
		}
		finish(cpu,pb,0,async);return true;
	}
	/** The CD image the driver call addresses (its drive number, 0 = the first CD drive) as a volume index, -1 for none. */
	private int opticalDisc(int drive){
		for(int i=0;i<fileManager.volumes.size();i++)
			if(fileManager.opticalVolume(i)&&fileManager.driverRefOf(i)==FileManager68k.CD_DRIVER_REF&&(drive==0||fileManager.driveOf(i)==drive))return i;
		return -1;
	}
	/** Frame (1/75 s) where volume {@code index}'s lead-out starts: its 2048-byte blocks after the 2 s pregap, at most 80 minutes. */
	private int leadOut(int index){
		long capacity=650L*1024*1024;
		try{File root=fileManager.volumes.get(index).getCanonicalFile();
			for(DiskImages.Mounted m:DiskImages.mounted())if(m.folder.getCanonicalFile().equals(root)&&m.capacity>0){capacity=m.capacity;break;}}
		catch(IOException e){}
		return 150+(int)Math.min(capacity/2048,80L*60*75-150);
	}
	/** Writes a frame count as BCD minutes, seconds and frames. */
	private void writeMsf(int at,int frames) throws M68kException {
		int[] msf={frames/(60*75),frames/75%60,frames%75};
		for(int i=0;i<3;i++)memory.writeByte(at+i,msf[i]/10<<4|msf[i]%10);
	}

	/** kDriveInfo of a host volume: internal (bit 8 clear), SCSI (bit 9), fixed (bit 10), type 1 (unspecified hard disk). */
	static final int DRIVE_INFO=0x00000601;
	private int driveIcon;
	/**
	 * Control csCodes 21/22 hand back a pointer to a 32x32 icon and its mask (256 bytes), followed by the location
	 * string Get Info shows (System 7). Installers draw it next to "Install on:"; leaving csParam alone made them plot
	 * whatever the parameter block held. The picture is our own: a plain drive case with a light.
	 */
	private int driveIcon() throws M68kException {
		if(driveIcon!=0)return driveIcon;
		String where="HyperZebra";
		int p=memory.allocate(256+1+where.length(),2);
		for(int y=0;y<32;y++){
			int icon=0,mask=0;
			if(y>=10&&y<=22){
				mask=0x7ffffffe;
				icon=(y==10||y==22)?0x7ffffffe:0x40000002;
				if(y==20)icon|=0x00000ff2;            // grille
				if(y==18||y==19)icon|=0x0c000000;     // light
				if(y==21)icon=0x5ffffffa;             // base shadow
			}
			memory.writeLong(p+y*4,icon);memory.writeLong(p+128+y*4,mask);
		}
		memory.writeByte(p+256,where.length());
		for(int i=0;i<where.length();i++)memory.writeByte(p+257+i,where.charAt(i));
		return driveIcon=p;
	}

	/** Programs found by creator for the desktop database calls (null: none on the volumes), looked up once per creator; a walk
	 *  that outlasted its short wait is not remembered as "none", so a later call gets what it found. */
	private final java.util.Map<Integer,java.util.Optional<File>> applications=new java.util.HashMap<Integer,java.util.Optional<File>>();
	private File applicationFor(int creator){
		java.util.Optional<File> app=applications.get(creator);
		if(app==null){boolean[] settled={true};app=java.util.Optional.ofNullable(fileManager.findBundleOwner(creator,settled));if(settled[0]||app.isPresent())applications.put(creator,app);}
		return app.orElse(null);
	}
	/** HyperZebra's own program: the .app bundle of a packaged HyperZebra, else the jar or the classes folder it runs from; null when unknown. */
	static File hyperZebraProgram(){
		String launcher=System.getProperty("jpackage.app-path");
		if(launcher!=null){File f=new File(launcher).getAbsoluteFile();for(File p=f;p!=null;p=p.getParentFile())if(p.getName().endsWith(".app"))return p;return f;}
		try{return new File(FileSystem68k.class.getProtectionDomain().getCodeSource().getLocation().toURI());}catch(Exception|Error e){return null;}
	}
	/** ioDTRefNum handed out by PBDTGetPath: the desktop database of volume i (the volumes' order) is DESKTOP_REF-i. */
	private static final int DESKTOP_REF=-32700;
	/** The volume (index) a PBDTGetPath names by ioNamePtr ("Disk:" or a path on it) or else ioVRefNum; -1 when there is none. */
	private int desktopVolume(int pb) throws M68kException {
		try{
			int namePtr=memory.readLong(pb+18);
			if(namePtr!=0&&memory.readByte(namePtr)>0){String n=tb.readPascal(namePtr);int colon=n.indexOf(':');
				if(colon>0)return fileManager.volumeIndexNamed(n.substring(0,colon));}
			File volume=fileManager.namedVolume((short)memory.readWord(pb+22));
			return volume==null?-1:fileManager.volumeIndexOf(volume);
		}catch(IOException e){return -1;}
	}
	/** The volume of a desktop database reference (an ioDTRefNum), or the startup volume for one this File Manager did not hand out. */
	private int desktopVolumeOf(int dtRef){int index=DESKTOP_REF-dtRef;return index>=0&&index<fileManager.volumes.size()?index:0;}
	/** The file or folder a desktop-database comment call names: ioNamePtr in ioDirID on the database's volume. */
	private File commentTarget(int pb) throws M68kException,IOException {
		int volume=desktopVolumeOf((short)memory.readWord(pb+24));int namePtr=memory.readLong(pb+18);
		return fileFor(-(volume+1),memory.readLong(pb+48),namePtr==0?"":tb.readPascal(namePtr));
	}
	private boolean hfsExtra(M68kCpu cpu,int selector,boolean async) throws M68kException {
		int pb=cpu.a[0];
		switch(selector){
			case 0x17:{ // PBExchangeFiles: swap contents of two files (FSExchangeObjects shares it)
				try{File a=fileFor((short)memory.readWord(pb+22),memory.readLong(pb+48),tb.readPascal(memory.readLong(pb+18)));File b=fileFor((short)memory.readWord(pb+22),memory.readLong(pb+36),tb.readPascal(memory.readLong(pb+28)));
					if(a==null||b==null||!a.isFile()||!b.isFile()){finish(cpu,pb,-43,async);return true;}
					finish(cpu,pb,fileManager.exchangeObjects(a,b),async);}catch(IOException e){finish(cpu,pb,-36,async);}catch(SecurityException e){finish(cpu,pb,FileManager68k.securityError(e),async);}
				return true;}
			case 0x18:finish(cpu,pb,fileManager.catSearch(pb,this),async);return true; // PBCatSearch
			case 0x0b:finish(cpu,pb,0,async);return true; // PBSetVInfo: the host volume keeps its own dates, name and attributes; accepted (the Finder sets them after copies)
			case 0x0e:case 0x0f:case 0x0c:case 0x0d:case 0x11+0x100:finish(cpu,pb,-50,async);return true;
			case 0x20:case 0x2e:{int volume=desktopVolume(pb);if(volume<0){finish(cpu,pb,-35,async);return true;} // PBDTGetPath / PBDTOpenInform: the volume's ioDTRefNum
				memory.writeWord(pb+24,DESKTOP_REF-volume);if(selector==0x2e)memory.writeLong(pb+28,0);finish(cpu,pb,0,async);return true;}
			case 0x21:case 0x22:case 0x25:case 0x26:case 0x2b:case 0x2c: // CloseDown/AddIcon/AddAPPL/RemoveAPPL/Flush/Reset: accepted, nothing is stored
				// a program was added or removed (the Finder copied one): what the creator walks found is out of date
				if(selector==0x25||selector==0x26||selector==0x2c){FileManager68k.forgetCreatorWalks();applications.clear();}
				finish(cpu,pb,0,async);return true;
			case 0x28:case 0x29:case 0x2a:{ // PBDTSetComment / PBDTRemoveComment / PBDTGetComment: the Finder comment of the file the host keeps (Rinaldi's FinderComments)
				try{File f=commentTarget(pb);
					if(f==null||!f.exists()){finish(cpu,pb,-43,async);return true;}
					if(selector==0x2a){byte[] c=tb.encoding.encodeReplacing(HostFileAttributes68k.comment(f));if(c.length==0){finish(cpu,pb,-5012,async);return true;}
						int buffer=memory.readLong(pb+32),n=Math.min(c.length,200);if(buffer!=0&&n>0)memory.load(buffer,java.util.Arrays.copyOf(c,n)); // ioDTReqCount is no input of GetComment (IM Files): Core Collection's GetComment leaves it unset and got nothing
						memory.writeLong(pb+40,n);finish(cpu,pb,0,async);return true;}
					Sandbox.checkFile(f,Sandbox.Access.WRITE,fileManager.requester());
					String comment="";
					if(selector==0x28){int buffer=memory.readLong(pb+32),n=Math.min(200,Math.max(0,memory.readLong(pb+36)));comment=buffer==0||n==0?"":tb.decode(memory.readBytes(buffer,n));} // comments hold 200 characters
					finish(cpu,pb,HostFileAttributes68k.setComment(f,comment)?0:-5000,async);}
				catch(IOException e){finish(cpu,pb,-36,async);}catch(SecurityException e){finish(cpu,pb,FileManager68k.securityError(e),async);}
				return true;}
			case 0x23:{ // PBDTGetIcon(ioDTRefNum, ioDTBuffer, ioDTReqCount, ioIconType, ioFileCreator, ioFileType) -> icon bits, ioDTActCount
				// from the bundle of the program with that creator (found on the volumes like PBDTGetAPPL): the Finder draws every
				// program and document with it
				int buffer=memory.readLong(pb+32),request=memory.readLong(pb+36),iconType=memory.readByte(pb+45),creator=memory.readLong(pb+52),fileType=memory.readLong(pb+56);
				int[] resType={0,0x49434e23,0x69636c34,0x69636c38,0x69637323,0x69637334,0x69637338}; // ICN# icl4 icl8 ics# ics4 ics8
				byte[] icon=iconType<1||iconType>6?null:creator==0x4d414353?SystemIcons68k.systemFileIcon(fileType,resType[iconType]) // 'MACS': the System's own files (the Finder...)
					:creator==0x57494c44?SystemIcons68k.hyperCardIcon(fileType,resType[iconType]) // 'WILD': HyperZebra is the HyperCard here (its own pictures)
					:FinderIcons68k.bundleIcon(applicationFor(creator),fileType,resType[iconType]);
				if(icon==null){finish(cpu,pb,-5012,async);return true;}
				int n=Math.min(Math.max(0,request),icon.length);if(buffer!=0&&n>0)memory.load(buffer,java.util.Arrays.copyOf(icon,n));
				memory.writeLong(pb+40,n);memory.writeLong(pb+28,0);finish(cpu,pb,0,async);return true;}
			case 0x24:finish(cpu,pb,-5012,async);return true; // GetIconInfo: afpItemNotFound
			case 0x2d:memory.writeLong(pb+64,0);memory.writeLong(pb+68,0);finish(cpu,pb,0,async);return true; // PBDTGetInfo: an empty database
			case 0x27:{ // PBDTGetAPPL(ioDTRefNum, ioIndex, ioFileCreator) -> ioNamePtr, ioAPPLParID: the volume is searched for the application
				int creator=memory.readLong(pb+52),index=(short)memory.readWord(pb+26),namePtr=memory.readLong(pb+18);
				int volume=desktopVolumeOf((short)memory.readWord(pb+24));
				File app=fileManager.findApplication(creator,index,volume);
				// HyperCard ('WILD') for an external when no HyperCard is on the volumes: HyperZebra's own program is the HyperCard here
				// (GetAppPath("WILD") and applicationPath("WILD") found nothing; a program's VM - the Finder - keeps the walk's answer)
				if(app==null&&creator==0x57494c44&&index<=1&&tb.app==null&&tb.stack!=null){File hz=hyperZebraProgram();try{if(hz!=null&&hz.exists()&&fileManager.volumeIndexOf(hz)==volume)app=hz;}catch(IOException e){}}
				if(Mac68kApi.TRACE)System.err.println("[file] PBDTGetAPPL '"+AppleEvents68k.fourcc(creator)+"' #"+index+" -> "+app);
				if(app==null){finish(cpu,pb,-5012,async);return true;}
				try{if(namePtr!=0)tb.writePascal(namePtr,fileManager.macNameOf(app),63);memory.writeLong(pb+100,fileManager.parentIdOf(app));finish(cpu,pb,0,async);}
				catch(IOException e){finish(cpu,pb,-36,async);}
				return true;}
			case 0x2f:case 0x31:case 0x32:case 0x33:case 0x34:case 0x35:case 0x36:finish(cpu,pb,-50,async);return true;
			case 0x60:finish(cpu,pb,-50,async);return true;
			default:return false;
		}
	}

	// ------------------------------------------------------------------ HighLevelFSDispatch ($AA52): FSSpec routines

	/** FSMakeFSSpec/FSpOpenDF/FSpOpenRF/FSpCreate/FSpDirCreate/FSpDelete/FSpGet(Set)FInfo/FSpSet(Rst)FLock/FSpRename/FSpCatMove/FSpExchangeFiles. */
	boolean highLevelDispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;int sp=cpu.a[7];int pb=memory.allocate(128,4);int result;
		try{
			switch(selector){
				case 1:{int spec=memory.readLong(sp),name=memory.readLong(sp+4),dirId=memory.readLong(sp+8),vRef=(short)memory.readWord(sp+12);cpu.a[7]+=14;
					memory.writeLong(pb+18,name);memory.writeWord(pb+22,vRef);memory.writeLong(pb+48,dirId);memory.writeLong(pb+28,spec);result=hfs(cpu,pb,0x1b);break;}
				case 2:case 3:{int refPtr=memory.readLong(sp),perm=memory.readByte(sp+4),spec=memory.readLong(sp+6);cpu.a[7]+=10; /* SignedByte: the high byte of its stack word (dfXFCNs fCopy opened the copy read-only) */
					fromSpec(pb,spec);memory.writeByte(pb+27,perm);result=selector==2?hfs(cpu,pb,0x1a):osTrap(cpu,pb,0xa20a);if(refPtr!=0)memory.writeWord(refPtr,memory.readWord(pb+24));break;}
				case 4:{int script=memory.readWord(sp),type=memory.readLong(sp+2),creator=memory.readLong(sp+6),spec=memory.readLong(sp+10);cpu.a[7]+=14;
					fromSpec(pb,spec);result=osTrap(cpu,pb,0xa208);
					if(result==0){fromSpec(pb,spec);memory.writeWord(pb+28,0);result=osTrap(cpu,pb,0xa20c);if(result==0){memory.writeLong(pb+32,type);memory.writeLong(pb+36,creator);fromSpecKeepInfo(pb,spec);result=osTrap(cpu,pb,0xa20d);}}break;}
				case 5:{int dirPtr=memory.readLong(sp),script=memory.readWord(sp+4),spec=memory.readLong(sp+6);cpu.a[7]+=10;fromSpec(pb,spec);result=hfs(cpu,pb,0x06);if(dirPtr!=0)memory.writeLong(dirPtr,memory.readLong(pb+48));break;}
				case 6:{int spec=memory.readLong(sp);cpu.a[7]+=4;fromSpec(pb,spec);result=osTrap(cpu,pb,0xa209);break;}
				case 7:{int info=memory.readLong(sp),spec=memory.readLong(sp+4);cpu.a[7]+=8;fromSpec(pb,spec);memory.writeWord(pb+28,0);result=osTrap(cpu,pb,0xa20c);if(result==0&&info!=0)memory.copy(pb+32,info,16);break;}
				case 8:{int info=memory.readLong(sp),spec=memory.readLong(sp+4);cpu.a[7]+=8;fromSpec(pb,spec);memory.writeWord(pb+28,0);result=osTrap(cpu,pb,0xa20c);if(result==0){memory.copy(info,pb+32,16);fromSpecKeepInfo(pb,spec);result=osTrap(cpu,pb,0xa20d);}break;}
				case 9:case 10:{int spec=memory.readLong(sp);cpu.a[7]+=4;fromSpec(pb,spec);result=osTrap(cpu,pb,selector==9?0xa241:0xa242);break;}
				case 11:{int newName=memory.readLong(sp),spec=memory.readLong(sp+4);cpu.a[7]+=8;fromSpec(pb,spec);memory.writeLong(pb+28,newName);result=osTrap(cpu,pb,0xa20b);break;}
				case 12:{int dest=memory.readLong(sp),source=memory.readLong(sp+4);cpu.a[7]+=8;fromSpec(pb,source);memory.writeLong(pb+28,dest+6);memory.writeLong(pb+36,memory.readLong(dest+2));result=hfs(cpu,pb,0x05);break;}
				case 13:{int perm=memory.readByte(sp),spec=memory.readLong(sp+2);cpu.a[7]+=6; // FSpOpenResFile
					File file;try{file=fileForSpec(spec);}catch(IOException e){file=null;}
					result=tb.resources.openFile(file,perm);break;}
				case 14:{int script=memory.readWord(sp),type=memory.readLong(sp+2),creator=memory.readLong(sp+6),spec=memory.readLong(sp+10);cpu.a[7]+=14; // FSpCreateResFile
					File file;try{file=fileForSpec(spec);}catch(IOException e){file=null;}
					tb.resources.createFile(file);
					if(file!=null&&file.exists()&&(short)memory.readWord(0xa60)==0){/* dupFNErr: an existing resource file keeps its type and creator */fromSpec(pb,spec);memory.writeWord(pb+28,0);if(osTrap(cpu,pb,0xa20c)==0){memory.writeLong(pb+32,type);memory.writeLong(pb+36,creator);fromSpecKeepInfo(pb,spec);osTrap(cpu,pb,0xa20d);}}
					return true;} // procedure: no result slot
				case 15:{int dest=memory.readLong(sp),source=memory.readLong(sp+4);cpu.a[7]+=8;fromSpec(pb,source);memory.writeLong(pb+28,dest+6);memory.writeLong(pb+36,memory.readLong(dest+2));result=hfs(cpu,pb,0x17);break;}
				default:if(selector>0xff){result=hfsPlusCall(cpu,selector);break;} // the HFS Plus API: parameter words in the high byte
					throw new M68kMacApiException(cpu.pc,0xaa52,"HighLevelFSDispatch selector "+selector);
			}
		}finally{memory.free(pb);}
		memory.writeWord(cpu.a[7],result);
		cpu.d[0]=(short)result; // the OSErr is in D0 as well: dfXFCNs fMove tests D0 after FSpCatMove (always "Move Error" before)
		return true;
	}
	private void fromSpec(int pb,int spec) throws M68kCpuException {memory.fill(pb,128,0);memory.writeLong(pb+18,spec+6);memory.writeWord(pb+22,memory.readWord(spec));memory.writeLong(pb+48,memory.readLong(spec+2));}
	private void fromSpecKeepInfo(int pb,int spec) throws M68kCpuException {memory.writeLong(pb+18,spec+6);memory.writeWord(pb+22,memory.readWord(spec));memory.writeLong(pb+48,memory.readLong(spec+2));memory.writeLong(pb+12,0);memory.writeWord(pb+16,0);}
	/** Runs an OS file trap synchronously on a parameter block; returns ioResult. */
	int osTrap(M68kCpu cpu,int pb,int opcode) throws M68kException {int savedA0=cpu.a[0],savedD0=cpu.d[0];cpu.a[0]=pb;handle(cpu,opcode);cpu.a[0]=savedA0;cpu.d[0]=savedD0;return (short)memory.readWord(pb+16);}
	private int hfs(M68kCpu cpu,int pb,int selector) throws M68kException {int savedA0=cpu.a[0],savedD0=cpu.d[0];cpu.a[0]=pb;cpu.d[0]=selector;handle(cpu,0xa260);cpu.a[0]=savedA0;cpu.d[0]=savedD0;return (short)memory.readWord(pb+16);}

	// ------------------------------------------------------------------ HFS Plus API (FSRef calls, Mac OS 9 / CarbonLib)

	private static final int NO_ERR=0,BD_NAM_ERR=-37,EOF_ERR=-39,FNF_ERR=-43,DUP_FN_ERR=-48,PARAM_ERR=-50,RF_NUM_ERR=-51,PERM_ERR=-54,NSV_ERR=-35,IO_ERR=-36,WR_PERM_ERR=-61,DIR_NF_ERR=-120,NOT_A_FILE_ERR=-1302,DIFF_VOL_ERR=-1303;
	private static final int ERR_FS_BAD_FSREF=-1401,ERR_FS_BAD_FORK_NAME=-1402,ERR_FS_BAD_INFO_BITMAP=-1405,ERR_FS_MISSING_CAT_INFO=-1406,ERR_FS_NOT_A_FOLDER=-1407,ERR_FS_FORK_NOT_FOUND=-1409,ERR_FS_NAME_TOO_LONG=-1410,ERR_FS_MISSING_NAME=-1411,ERR_FS_BAD_POS_MODE=-1412,ERR_FS_BAD_ALLOC_FLAGS=-1413,ERR_FS_NO_MORE_ITEMS=-1417,ERR_FS_BAD_ITEM_COUNT=-1418,ERR_FS_BAD_SEARCH_PARAMS=-1419,ERR_FS_REFS_DIFFERENT=-1420,ERR_FS_FORK_EXISTS=-1421,ERR_FS_BAD_ITERATOR_FLAGS=-1422,ERR_FS_ITERATOR_NOT_FOUND=-1423,ERR_FS_ITERATOR_NOT_SUPPORTED=-1424;
	/** kFSCatInfoSettableInfo: what FSSetCatalogInfo takes (text encoding, node flags, dates, permissions, Finder info). */
	private static final int CAT_INFO_SETTABLE=0x1fe3,CAT_INFO_RESERVED=0xfffc0000;
	/** FSCatalogInfo, FSVolumeInfo, FSForkInfo, HFSUniStr255, FSSpec and FSRef sizes (68K alignment). */
	private static final int CAT_INFO_SIZE=144,VOLUME_INFO_SIZE=126,FORK_INFO_SIZE=48,UNI_STR_SIZE=512,SPEC_SIZE=70,REF_SIZE=80;
	/** The allocation block size FSGetVolumeInfo reports; physical fork sizes are rounded to it. */
	private static final int HFS_PLUS_BLOCK=4096;
	private static final String RESOURCE_FORK_NAME="RESOURCE_FORK";

	private int l(int p) throws M68kCpuException {return memory.readLong(p);}
	private int w(int p) throws M68kCpuException {return memory.readWord(p);}
	private long read64(int p) throws M68kCpuException {return ((long)memory.readLong(p)<<32)|(memory.readLong(p+4)&0xffffffffL);}
	private void write64(int p,long v) throws M68kCpuException {if(p==0)return;memory.writeLong(p,(int)(v>>>32));memory.writeLong(p+4,(int)v);}
	/** A UniChar string (length in characters), or null for none. */
	private String unicode(int length,int p) throws M68kCpuException {if(p==0||length<=0)return null;StringBuilder sb=new StringBuilder(Math.min(length,1024));for(int i=0;i<length&&i<1024;i++)sb.append((char)memory.readWord(p+2*i));return sb.toString();}
	/** Writes an HFSUniStr255 (length word, then up to 255 UniChars). */
	private void writeUniStr255(int p,String s) throws M68kCpuException {if(p==0)return;int n=Math.min(255,s.length());memory.writeWord(p,n);for(int i=0;i<n;i++)memory.writeWord(p+2+2*i,s.charAt(i));}
	/** Writes a UTCDateTime (highSeconds, lowSeconds, fraction) for a Mac local-time date as the classic calls keep them. */
	private void writeUtc(int p,int macLocal) throws M68kCpuException {long utc=macLocal==0?0:Math.max(0,Integer.toUnsignedLong(macLocal)-Toolbox68k.localOffsetSeconds());memory.writeWord(p,(int)(utc>>>32));memory.writeLong(p+2,(int)utc);memory.writeWord(p+6,0);}
	/** A UTCDateTime as a Mac local-time date (0 stays 0: "never"). */
	private int readUtc(int p) throws M68kCpuException {long utc=((long)memory.readWord(p)<<32)|(memory.readLong(p+2)&0xffffffffL);return utc==0?0:(int)(utc+Toolbox68k.localOffsetSeconds());}
	/** The existing object an FSRef names, null for an FSRef not made here (errFSBadFSRef). */
	private File refFile(int ref) throws M68kCpuException {return fsRefFile(ref);}
	/** The result for an FSRef naming no usable object: errFSBadFSRef, or fnfErr once it is gone. */
	private int refError(int ref) throws M68kCpuException {File f=fsRefFile(ref);return f==null?ERR_FS_BAD_FSREF:f.exists()?NO_ERR:FNF_ERR;}
	/** An FSRef's object follows it when a program renames or moves it here, by the HFS Plus or the classic calls (a real FSRef holds the node ID, which survives both). */
	/** A PICT a program or external saves, played by the VM's own QuickDraw (null when it cannot be). */
	private java.awt.image.BufferedImage renderPicture(byte[] picture){
		try{QuickDraw68k qd=tb.api.quickDraw();return qd==null?null:qd.renderPicture(PictureFile68k.pictureOf(picture));}
		catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[file] picture not rendered: "+e);return null;}
	}

	private void rebaseFSRefs(File from,File to){
		String old=from.getPath(),now=to.getPath();
		for(int i=0;i<fsRefFiles.size();i++){String p=fsRefFiles.get(i).getPath();if(p.equals(old))fsRefFiles.set(i,to);else if(p.startsWith(old+File.separator))fsRefFiles.set(i,new File(now+p.substring(old.length())));}
	}
	/** Checks a new object name: errFSMissingName, errFSNameTooLong, or bdNamErr (':'); 0 when it can be used. */
	private static int nameError(String name,int length){if(name==null)return ERR_FS_MISSING_NAME;if(length>255)return ERR_FS_NAME_TOO_LONG;if(name.indexOf(':')>=0)return BD_NAM_ERR;return NO_ERR;}
	private static int toResult(Exception e){return e instanceof SecurityException?PERM_ERR:IO_ERR;}

	/**
	 * Writes the fields of an FSCatalogInfo {@code whichInfo} asks for (the others stay as they are): node flags, volume, parent and
	 * node IDs, dates as UTC, Finder information, fork sizes, valence, all from the File Manager's own record of the object.
	 */
	private void writeCatalogInfo(int p,File f,int whichInfo) throws IOException,M68kCpuException {
		if(p==0||whichInfo==0)return;
		FileManager68k fm=fileManager;boolean dir=f.isDirectory();FileManager68k.Metadata m=fm.metadataOf(f);
		if((whichInfo&0x0002)!=0)memory.writeWord(p,(dir?0x10:0)|(m.locked||!f.canWrite()?1:0));      // nodeFlags: kFSNodeIsDirectoryMask, kFSNodeLockedMask
		if((whichInfo&0x0004)!=0)memory.writeWord(p+2,fm.volumeRefNumFor(f));
		if((whichInfo&0x0008)!=0)memory.writeLong(p+4,fm.parentIdOf(f));
		if((whichInfo&0x0010)!=0)memory.writeLong(p+8,dir?fm.directoryIdFor(f):m.fileNumber);
		if((whichInfo&0x10000)!=0)memory.writeByte(p+12,0);                                            // sharingFlags: nothing mounted or shared
		if((whichInfo&0x20000)!=0)memory.writeByte(p+13,0);                                            // userPrivileges: full access on a local volume
		if((whichInfo&0x0020)!=0)writeUtc(p+16,m.creationDate);
		if((whichInfo&0x0040)!=0)writeUtc(p+24,m.modificationDate);
		if((whichInfo&0x0080)!=0)writeUtc(p+32,m.modificationDate);                                    // attributeModDate: HFS keeps one date
		if((whichInfo&0x0100)!=0)writeUtc(p+40,m.modificationDate);                                    // accessDate
		if((whichInfo&0x0200)!=0)writeUtc(p+48,m.backupDate);
		if((whichInfo&0x0400)!=0)memory.fill(p+56,16,0);                                              // permissions: Mac OS X only
		if((whichInfo&0x0800)!=0){memory.load(p+72,m.finderInfo);if(dir&&(m.finderInfo[8]&0x04)!=0&&!new File(f,"Icon\r").exists())memory.writeByte(p+80,m.finderInfo[8]&0xfb);} // no kHasCustomIcon without its "Icon\r" file (as GetCatInfo)
		if((whichInfo&0x1000)!=0)memory.load(p+88,m.extendedFinderInfo);
		if((whichInfo&0xc000)!=0){long[] sizes=fm.forkLengthsOf(f);
			if((whichInfo&0x4000)!=0){write64(p+104,sizes[0]);write64(p+112,(sizes[0]+HFS_PLUS_BLOCK-1)/HFS_PLUS_BLOCK*HFS_PLUS_BLOCK);}
			if((whichInfo&0x8000)!=0){write64(p+120,sizes[1]);write64(p+128,(sizes[1]+HFS_PLUS_BLOCK-1)/HFS_PLUS_BLOCK*HFS_PLUS_BLOCK);}}
		if((whichInfo&0x2000)!=0){File[] entries=dir?FileManager68k.catalogEntries(f):null;memory.writeLong(p+136,entries==null?0:entries.length);}
		if((whichInfo&0x0001)!=0)memory.writeLong(p+140,tb.encoding==StackTextEncoding.JAPANESE?1:0); // textEncodingHint: kTextEncodingMacJapanese / MacRoman
	}
	/** Applies the settable fields of an FSCatalogInfo (lock, dates, Finder information; the rest is ignored as the host cannot keep it). */
	private int applyCatalogInfo(File f,int whichInfo,int p) throws IOException,M68kCpuException {
		whichInfo&=CAT_INFO_SETTABLE;if(whichInfo==0)return NO_ERR;if(p==0)return ERR_FS_MISSING_CAT_INFO;
		Boolean locked=(whichInfo&0x0002)!=0?(memory.readWord(p)&1)!=0:null;
		byte[] finderInfo=(whichInfo&0x0800)!=0?memory.readBytes(p+72,16):null,extended=(whichInfo&0x1000)!=0?memory.readBytes(p+88,16):null;
		int created=(whichInfo&0x0020)!=0?readUtc(p+16):0,modified=(whichInfo&0x0040)!=0?readUtc(p+24):0;Integer backup=(whichInfo&0x0200)!=0?readUtc(p+48):null;
		return fileManager.setCatalogEntry(f,locked,finderInfo,extended,created,modified,backup);
	}

	// ---- FSRefs

	/** FSpMakeFSRef / PBMakeFSRef: the FSRef of an existing object named by an FSSpec (or vRefNum, dirID, name). */
	private int makeFSRef(File f,int newRef) throws M68kCpuException {if(f==null)return DIR_NF_ERR;if(!f.exists())return FNF_ERR;writeFSRef(newRef,f);return NO_ERR;}
	/** The object (vRefNum, dirID, name) names, existing or not; a volume's root for (vRefNum, fsRtParID 1, its name); null when the folder is unknown. */
	private File fileForNameBytes(int vRef,int dirId,int namePtr) throws IOException,M68kCpuException {
		if(dirId==1)return fileManager.namedVolume(vRef);
		File base=fileManager.directoryForId(dirId,vRef);if(base==null)return null;
		if(namePtr==0||memory.readByte(namePtr)==0)return base;
		try{return fileManager.resolveBytes(base,memory.readBytes(namePtr+1,memory.readByte(namePtr)));}catch(SecurityException e){return null;}
	}
	/** FSMakeFSRefUnicode: the FSRef of an existing object by its parent's FSRef and Unicode name. */
	private int makeFSRefUnicode(int parentRef,int length,int name,int newRef) throws M68kCpuException {
		File parent=refFile(parentRef);if(parent==null)return ERR_FS_BAD_FSREF;if(!parent.isDirectory())return parent.exists()?DIR_NF_ERR:FNF_ERR;
		String n=unicode(length,name);if(n==null)return ERR_FS_MISSING_NAME;
		try{File f=fileManager.childNamed(parent,n);if(f==null)return BD_NAM_ERR;if(!f.exists())return FNF_ERR;writeFSRef(newRef,f);return NO_ERR;}catch(IOException|SecurityException e){return toResult(e);}
	}
	private int compareFSRefs(int a,int b) throws M68kCpuException {File fa=refFile(a),fb=refFile(b);if(fa==null||fb==null)return ERR_FS_BAD_FSREF;return fa.equals(fb)?NO_ERR:ERR_FS_REFS_DIFFERENT;}

	// ---- objects

	/** FSCreateFileUnicode / FSCreateDirectoryUnicode: a new file or folder in the parent, catalog information applied, its FSRef, FSSpec and (a folder) ID returned. */
	private int createUnicode(boolean folder,int parentRef,int length,int name,int whichInfo,int catInfo,int newRef,int newSpec,int newDirId) throws M68kCpuException {
		File parent=refFile(parentRef);if(parent==null)return ERR_FS_BAD_FSREF;if(!parent.isDirectory())return parent.exists()?DIR_NF_ERR:FNF_ERR;
		String n=unicode(length,name);int bad=nameError(n,length);if(bad!=NO_ERR)return bad;if((whichInfo&CAT_INFO_RESERVED)!=0)return ERR_FS_BAD_INFO_BITMAP;
		try{File f=fileManager.childNamed(parent,n);if(f==null)return BD_NAM_ERR;if(f.exists())return DUP_FN_ERR;
			int result=folder?fileManager.createFolder(f):fileManager.createFile(f);if(result!=NO_ERR)return result;
			if(catInfo!=0)applyCatalogInfo(f,whichInfo,catInfo);
			writeFSRef(newRef,f);if(newSpec!=0)fileManager.writeFSSpec(newSpec,f);if(folder&&newDirId!=0)memory.writeLong(newDirId,fileManager.directoryIdFor(f));
			return NO_ERR;
		}catch(IOException|SecurityException e){return toResult(e);}
	}
	private int deleteObject(int ref) throws M68kCpuException {
		File f=refFile(ref);if(f==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;
		if(fileManager.volumeRoot(f))return PERM_ERR; // a volume's root folder is not deleted
		try{return fileManager.deleteObject(f,true);}catch(IOException|SecurityException e){return toResult(e);}
	}
	private int moveObject(int ref,int destinationRef,int newRef) throws M68kCpuException {
		File f=refFile(ref),destination=refFile(destinationRef);if(f==null||destination==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;if(!destination.isDirectory())return destination.exists()?ERR_FS_NOT_A_FOLDER:DIR_NF_ERR;
		try{if(fileManager.volumeRefNumFor(f)!=fileManager.volumeRefNumFor(destination))return DIFF_VOL_ERR;
			File moved=new File(destination.getCanonicalFile(),f.getName());int result=fileManager.moveObject(f,destination);
			if(result==NO_ERR)writeFSRef(newRef,moved);return result;
		}catch(IOException|SecurityException e){return toResult(e);}
	}
	private int exchangeObjects(int ref,int destinationRef) throws M68kCpuException {
		File a=refFile(ref),b=refFile(destinationRef);if(a==null||b==null)return ERR_FS_BAD_FSREF;
		try{return fileManager.exchangeObjects(a,b);}catch(IOException|SecurityException e){return toResult(e);}
	}
	private int renameUnicode(int ref,int length,int name,int newRef) throws M68kCpuException {
		File f=refFile(ref);if(f==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;
		String n=unicode(length,name);int bad=nameError(n,length);if(bad!=NO_ERR)return bad;
		if(fileManager.volumeRoot(f)){writeFSRef(newRef,f);return NO_ERR;} // volume names stay the host's (as PBSetVInfo)
		try{File to=fileManager.childNamed(f.getParentFile(),n);if(to==null)return BD_NAM_ERR;
			if(to.equals(f)&&to.getName().equals(f.getName())){writeFSRef(newRef,f);return NO_ERR;} // the name it has
			int result=fileManager.renameObject(f,to);if(result!=NO_ERR)return result;
			writeFSRef(newRef,new File(f.getParentFile(),to.getName()));return NO_ERR;
		}catch(IOException|SecurityException e){return toResult(e);}
	}
	/** FSGetCatalogInfo: catalog information, the name (HFSUniStr255), the FSSpec and the parent folder's FSRef of an object. */
	private int getCatalogInfo(int ref,int whichInfo,int catInfo,int outName,int spec,int parentRef) throws M68kCpuException {
		File f=refFile(ref);if(f==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;if((whichInfo&CAT_INFO_RESERVED)!=0)return ERR_FS_BAD_INFO_BITMAP;
		try{writeCatalogInfo(catInfo,f,whichInfo);if(outName!=0)writeUniStr255(outName,fileManager.catalogNameOf(f));
			if(spec!=0)fileManager.writeFSSpec(spec,f);
			if(parentRef!=0){File parent=f.getParentFile();if(parent==null||fileManager.volumeRoot(f))memory.fill(parentRef,REF_SIZE,0);else writeFSRef(parentRef,parent);} // a volume's root has no parent object
			return NO_ERR;
		}catch(IOException|SecurityException e){return toResult(e);}
	}
	private int setCatalogInfo(int ref,int whichInfo,int catInfo) throws M68kCpuException {
		File f=refFile(ref);if(f==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;if((whichInfo&CAT_INFO_RESERVED)!=0)return ERR_FS_BAD_INFO_BITMAP;
		try{return applyCatalogInfo(f,whichInfo,catInfo);}catch(IOException|SecurityException e){return toResult(e);}
	}

	// ---- iterators

	/** An FSIterator: a folder's entries (flat) or the whole tree below it (kFSIterateSubtree, FSCatalogSearch), walked once. */
	private static final class Iterator {final File container;final boolean subtree;List<File> entries;int index;final long stamp;Iterator(File container,boolean subtree){this.container=container;this.subtree=subtree;stamp=container.lastModified();}}
	private final Map<Integer,Iterator> iterators=new HashMap<Integer,Iterator>();
	private int openIterator(int containerRef,int flags,int iteratorPtr) throws M68kException {
		if(iteratorPtr!=0)memory.writeLong(iteratorPtr,0);
		File f=refFile(containerRef);if(f==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;if(!f.isDirectory())return ERR_FS_NOT_A_FOLDER;
		if((flags&~1)!=0)return ERR_FS_BAD_ITERATOR_FLAGS;if(iteratorPtr==0)return PARAM_ERR;
		int id=memory.allocate(4,4);memory.writeLong(id,0x687a4974); // 'hzIt': the opaque FSIterator is a block of its own
		iterators.put(id,new Iterator(f,(flags&1)!=0));memory.writeLong(iteratorPtr,id);return NO_ERR;
	}
	private int closeIterator(int id) throws M68kCpuException {if(iterators.remove(id)==null)return ERR_FS_ITERATOR_NOT_FOUND;memory.free(id);return NO_ERR;}
	/** The entries an iterator walks, listed on first use: a folder's in catalog order, or (subtree) the folders below breadth first. */
	private List<File> entriesOf(Iterator it) throws IOException {
		if(it.entries!=null)return it.entries;
		if(!it.subtree){it.entries=new ArrayList<File>(java.util.Arrays.asList(fileManager.catalogListing(it.container)));return it.entries;}
		List<File> walk;int vRef=fileManager.volumeRefNumFor(it.container);
		if(fileManager.volumeRoot(it.container))walk=fileManager.catalogWalk(vRef,20000);
		else{walk=new ArrayList<File>();java.util.ArrayDeque<File> queue=new java.util.ArrayDeque<File>();queue.add(it.container);
			while(!queue.isEmpty()&&walk.size()<20000){File[] children=fileManager.catalogListing(queue.poll());for(File child:children){walk.add(child);if(child.isDirectory())queue.add(child);}}}
		return it.entries=walk;
	}
	/** Writes the {@code n}th result of a bulk call: catalog information, FSRef, FSSpec and name, into whichever arrays were given. */
	private void writeBulkEntry(File f,int n,int whichInfo,int catInfos,int refs,int specs,int names) throws IOException,M68kCpuException {
		if(catInfos!=0)writeCatalogInfo(catInfos+n*CAT_INFO_SIZE,f,whichInfo);
		if(refs!=0)writeFSRef(refs+n*REF_SIZE,f);
		if(specs!=0)fileManager.writeFSSpec(specs+n*SPEC_SIZE,f);
		if(names!=0)writeUniStr255(names+n*UNI_STR_SIZE,fileManager.catalogNameOf(f));
	}
	/**
	 * FSGetCatalogInfoBulk (searchParams 0) and FSCatalogSearch: up to {@code maximum} objects of the iteration (matching the search),
	 * noErr while some came back, errFSNoMoreItems once the iteration is over (with none).
	 */
	private int catalogBulk(int id,int searchParams,int maximum,int actualPtr,int changedPtr,int whichInfo,int catInfos,int refs,int specs,int names) throws M68kException {
		if(actualPtr!=0)memory.writeLong(actualPtr,0);if(changedPtr!=0)memory.writeByte(changedPtr,0);
		Iterator it=iterators.get(id);if(it==null)return ERR_FS_ITERATOR_NOT_FOUND;
		if(maximum<=0)return ERR_FS_BAD_ITEM_COUNT;if((whichInfo&CAT_INFO_RESERVED)!=0)return ERR_FS_BAD_INFO_BITMAP;
		if(searchParams==0&&it.subtree)return ERR_FS_ITERATOR_NOT_SUPPORTED; // Mac OS 9 lists folders flat only; subtrees are for searches
		Search search=null;if(searchParams!=0){search=new Search(searchParams);if(search.bad)return ERR_FS_BAD_SEARCH_PARAMS;}
		int n=0;
		try{List<File> entries=entriesOf(it);int scratch=search!=null&&search.needsInfo?memory.allocate(CAT_INFO_SIZE,4):0;if(scratch!=0)memory.fill(scratch,CAT_INFO_SIZE,0);
			try{while(n<maximum&&it.index<entries.size()){File f=entries.get(it.index++);if(!f.exists())continue;
				if(search!=null&&!search.matches(f,scratch))continue;
				writeBulkEntry(f,n,whichInfo,catInfos,refs,specs,names);n++;}}
			finally{if(scratch!=0)memory.free(scratch);}
		}catch(IOException|SecurityException e){return toResult(e);}
		if(actualPtr!=0)memory.writeLong(actualPtr,n);
		if(changedPtr!=0&&it.container.lastModified()!=it.stamp)memory.writeByte(changedPtr,1);
		return n>0?NO_ERR:ERR_FS_NO_MORE_ITEMS;
	}
	/**
	 * FSSearchParams (searchTime, searchBits, searchNameLength, searchName, searchInfo1, searchInfo2): the name (fsSBPartialName 1,
	 * fsSBFullName 2) and the catalog fields of the classic PBCatSearch bits, with FSCatalogInfo values/masks (info1) and upper
	 * bounds/masks (info2); fsSBNegate (0x4000) inverts the match.
	 */
	private final class Search {
		final int bits,info1,info2;final String name;boolean bad,needsInfo;
		Search(int p) throws M68kCpuException {bits=memory.readLong(p+4);name=unicode(memory.readLong(p+8),memory.readLong(p+12));info1=memory.readLong(p+16);info2=memory.readLong(p+20);
			if((bits&3)!=0&&name==null)bad=true;needsInfo=(bits&~0x4003)!=0;if(needsInfo&&(info1==0||info2==0))bad=true;}
		boolean matches(File f,int scratch) throws IOException,M68kCpuException {
			boolean match=true;
			if((bits&3)!=0){String n=fileManager.catalogNameOf(f);match=(bits&2)!=0?n.equalsIgnoreCase(name):n.toLowerCase().contains(name.toLowerCase());}
			if(match&&needsInfo){writeCatalogInfo(scratch,f,0x3ffff&~0x0001);boolean dir=f.isDirectory();
				if((bits&0x0004)!=0)match=masked(scratch,0,2);                          // fsSBFlAttrib: nodeFlags value/mask
				if(match&&(bits&0x0008)!=0)match=masked(scratch,72,16);                 // fsSBFlFndrInfo / fsSBDrUsrWds
				if(match&&(bits&0x1000)!=0)match=masked(scratch,88,16);                 // fsSBFlXFndrInfo / fsSBDrFndrInfo
				if(match&&(bits&0x0010)!=0)match=dir&&range(scratch,136,4);             // fsSBDrNmFls: valence
				if(match&&(bits&0x0020)!=0)match=!dir&&range(scratch,104,8);            // fsSBFlLgLen
				if(match&&(bits&0x0040)!=0)match=!dir&&range(scratch,112,8);            // fsSBFlPyLen
				if(match&&(bits&0x0080)!=0)match=!dir&&range(scratch,120,8);            // fsSBFlRLgLen
				if(match&&(bits&0x0100)!=0)match=!dir&&range(scratch,128,8);            // fsSBFlRPyLen
				if(match&&(bits&0x0200)!=0)match=range(scratch,16,6);                   // fsSBFlCrDat (highSeconds, lowSeconds)
				if(match&&(bits&0x0400)!=0)match=range(scratch,24,6);                   // fsSBFlMdDat
				if(match&&(bits&0x0800)!=0)match=range(scratch,48,6);                   // fsSBFlBkDat
				if(match&&(bits&0x2000)!=0)match=range(scratch,4,4);                    // fsSBFlParID
				if(match&&(bits&0x8000)!=0)match=range(scratch,8,4);                    // fsSBNodeID
				if(match&&(bits&0x10000)!=0)match=range(scratch,32,6);                  // fsSBAttributeModDate
				if(match&&(bits&0x20000)!=0)match=range(scratch,40,6);}                 // fsSBAccessDate
			return match!=((bits&0x4000)!=0);
		}
		/** Whether the object's bytes at {@code at} equal info1's under info2's mask. */
		private boolean masked(int scratch,int at,int n) throws M68kCpuException {for(int i=0;i<n;i++)if(((memory.readByte(scratch+at+i)^memory.readByte(info1+at+i))&memory.readByte(info2+at+i))!=0)return false;return true;}
		/** Whether the object's big-endian unsigned field at {@code at} lies between info1's and info2's. */
		private boolean range(int scratch,int at,int n) throws M68kCpuException {return compare(scratch+at,info1+at,n)>=0&&compare(scratch+at,info2+at,n)<=0;}
		private int compare(int a,int b,int n) throws M68kCpuException {for(int i=0;i<n;i++){int x=memory.readByte(a+i),y=memory.readByte(b+i);if(x!=y)return x<y?-1:1;}return 0;}
	}

	// ---- forks

	/** Which fork a fork name names: 0 the data fork (empty name), 1 the resource fork ("RESOURCE_FORK"), -1 another (named forks do not exist here). */
	private int forkKind(int length,int name) throws M68kCpuException {if(length<=0)return 0;String n=unicode(length,name);return RESOURCE_FORK_NAME.equals(n)?1:-1;}
	/** FSOpenFork: a refNum the classic calls (FSRead, SetFPos, GetEOF, FSClose) take as well. */
	private int openFork(int ref,int nameLength,int name,int permission,int refNumPtr) throws M68kCpuException {
		if(refNumPtr!=0)memory.writeWord(refNumPtr,0);
		File f=refFile(ref);if(f==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;if(f.isDirectory())return NOT_A_FILE_ERR;
		if(nameLength>0&&name==0)return ERR_FS_BAD_FORK_NAME;int kind=forkKind(nameLength,name);if(kind<0)return ERR_FS_FORK_NOT_FOUND;
		if(permission==0)permission=f.canWrite()?3:1; // fsCurPerm: whatever access the file allows
		else if(permission==4)permission=3;            // fsRdWrShPerm: shared read/write
		try{int r=fileManager.openFork(f,kind==1,permission);if(r<0)return r;if(refNumPtr!=0)memory.writeWord(refNumPtr,r);return NO_ERR;}
		catch(IOException|SecurityException e){return toResult(e);}
	}
	/**
	 * FSReadFork/FSWriteFork: the mark is placed (64-bit positionMode/positionOffset; cache hint bits ignored, newline mode is
	 * errFSBadPosMode), then the classic Read/Write trap moves the bytes, so range locks, text-file views and eofErr behave alike.
	 */
	private int forkIO(M68kCpu cpu,boolean write,int refNum,int mode,long offset,int count,int buffer,int[] actual) throws M68kException {
		actual[0]=0;if((mode&0xff80)!=0)return ERR_FS_BAD_POS_MODE;if(count<0||count>0&&buffer==0)return PARAM_ERR;
		try{int result=fileManager.seekFork(refNum,mode&3,offset,true);if(result!=NO_ERR)return result;}catch(IOException e){return IO_ERR;}
		int pb=memory.allocate(80,4);
		try{memory.fill(pb,80,0);memory.writeWord(pb+24,refNum);memory.writeLong(pb+32,buffer);memory.writeLong(pb+36,count);memory.writeWord(pb+44,0);
			int result=osTrap(cpu,pb,write?0xa003:0xa002);actual[0]=memory.readLong(pb+40);return result;}
		finally{memory.free(pb);}
	}
	private int getForkPosition(int refNum,boolean size,int resultPtr) throws M68kCpuException {FileManager68k.ForkState s=fileManager.forkState(refNum);if(s==null)return RF_NUM_ERR;write64(resultPtr,size?s.length:s.mark);return NO_ERR;}
	private int setForkPosition(int refNum,int mode,long offset) {if((mode&0xff80)!=0)return ERR_FS_BAD_POS_MODE;try{return fileManager.seekFork(refNum,mode&3,offset,false);}catch(IOException e){return IO_ERR;}}
	/** FSSetForkSize: the new end relative to the start, the end or the mark, as positionMode says. */
	private int setForkSize(int refNum,int mode,long offset) {
		if((mode&0xff80)!=0)return ERR_FS_BAD_POS_MODE;FileManager68k.ForkState s=fileManager.forkState(refNum);if(s==null)return RF_NUM_ERR;
		long size=(mode&3)==0?s.mark:(mode&3)==1?offset:(mode&3)==2?s.length+offset:s.mark+offset;
		try{return fileManager.setForkLength(refNum,size);}catch(IOException e){return IO_ERR;}
	}
	/** FSAllocateFork: host files grow on demand, so the space asked for is simply granted. */
	private int allocateFork(int refNum,int flags,long request,int actualPtr) throws M68kCpuException {
		write64(actualPtr,0);if((flags&0xfff8)!=0)return ERR_FS_BAD_ALLOC_FLAGS;FileManager68k.ForkState s=fileManager.forkState(refNum);if(s==null)return RF_NUM_ERR;if(!s.writable)return WR_PERM_ERR;
		write64(actualPtr,Math.max(0,request));return NO_ERR;
	}
	/** FSFlushFork / FSCloseFork through the classic FlushFile / Close traps. */
	private int forkCall(M68kCpu cpu,int refNum,int trap) throws M68kException {int pb=memory.allocate(64,4);try{memory.fill(pb,64,0);memory.writeWord(pb+24,refNum);return osTrap(cpu,pb,trap);}finally{memory.free(pb);}}
	/** FSCreateFork: the data and resource forks always exist, so creating one succeeds while it is still empty (BOOM creates the data fork of a file it just made) and is forkExists once it holds data; other named forks are not supported. */
	private int createFork(int ref,int nameLength,int name) throws M68kCpuException {int bad=refError(ref);if(bad!=NO_ERR)return bad;if(nameLength>0&&name==0)return ERR_FS_BAD_FORK_NAME;int kind=forkKind(nameLength,name);if(kind<0)return ERR_FS_BAD_FORK_NAME;try{return fileManager.forkLengthsOf(refFile(ref))[kind]>0?ERR_FS_FORK_EXISTS:NO_ERR;}catch(IOException|SecurityException e){return toResult(e);}}
	/** FSDeleteFork: the data or resource fork is emptied (an HFS file keeps both). */
	private int deleteFork(int ref,int nameLength,int name) throws M68kCpuException {
		int bad=refError(ref);if(bad!=NO_ERR)return bad;if(nameLength>0&&name==0)return ERR_FS_BAD_FORK_NAME;int kind=forkKind(nameLength,name);if(kind<0)return ERR_FS_FORK_NOT_FOUND;
		try{return fileManager.emptyFork(refFile(ref),kind==1);}catch(IOException|SecurityException e){return toResult(e);}
	}
	/**
	 * FSIterateForks: the data fork, then the resource fork when it holds anything; the CatPositionRec keeps the place
	 * (initialize = 0 starts, the next index is kept in its first private word).
	 */
	private int iterateForks(int ref,int iterator,int forkName,int sizePtr,int physicalPtr) throws M68kCpuException {
		File f=refFile(ref);if(f==null)return ERR_FS_BAD_FSREF;if(!f.exists())return FNF_ERR;if(f.isDirectory())return NOT_A_FILE_ERR;if(iterator==0)return PARAM_ERR;
		int index=memory.readLong(iterator)==0?0:memory.readWord(iterator+4);
		long[] sizes;try{sizes=fileManager.forkLengthsOf(f);}catch(IOException e){return IO_ERR;}
		if(index==1&&sizes[1]==0)index=2;if(index>1)return ERR_FS_NO_MORE_ITEMS;
		memory.writeLong(iterator,1);memory.writeWord(iterator+4,index+1);
		writeUniStr255(forkName,index==0?"":RESOURCE_FORK_NAME);write64(sizePtr,sizes[index]);write64(physicalPtr,(sizes[index]+HFS_PLUS_BLOCK-1)/HFS_PLUS_BLOCK*HFS_PLUS_BLOCK);
		return NO_ERR;
	}
	/**
	 * FSGetForkCBInfo: the open fork {@code desired}, or (desired 0) the next open fork after {@code *iterator} on {@code volume}
	 * (0: any), with its FSForkInfo, FSRef and fork name; rfNumErr when there is none (the end of an iteration).
	 */
	private int getForkCBInfo(int desired,int volume,int iteratorPtr,int actualPtr,int forkInfo,int ref,int forkName) throws M68kCpuException {
		int refNum=desired;FileManager68k.ForkState s=null;
		try{
			if(desired!=0)s=fileManager.forkState(desired);
			else{if(iteratorPtr==0)return PARAM_ERR;List<Integer> open=fileManager.openForkReferences();int at=(short)memory.readWord(iteratorPtr);
				while(at>=0&&at<open.size()){FileManager68k.ForkState candidate=fileManager.forkState(open.get(at++));if(candidate!=null&&(volume==0||fileManager.volumeRefNumFor(candidate.path)==volume)){s=candidate;refNum=open.get(at-1);break;}}
				memory.writeWord(iteratorPtr,at);}
			if(s==null)return RF_NUM_ERR;
			if(actualPtr!=0)memory.writeWord(actualPtr,refNum);
			if(forkInfo!=0){memory.fill(forkInfo,FORK_INFO_SIZE,0);memory.writeByte(forkInfo,(s.writable?1:0)|(s.resourceFork?2:0)); // FCB flags: write allowed, resource fork
				memory.writeByte(forkInfo+1,s.writable?3:1);memory.writeWord(forkInfo+2,fileManager.volumeRefNumFor(s.path));memory.writeLong(forkInfo+8,fileManager.metadataOf(s.path).fileNumber);
				memory.writeLong(forkInfo+12,s.resourceFork?0xff:0);write64(forkInfo+16,s.mark);write64(forkInfo+24,s.length);write64(forkInfo+32,(s.length+HFS_PLUS_BLOCK-1)/HFS_PLUS_BLOCK*HFS_PLUS_BLOCK);} // forkID: kHFSDataForkType / kHFSResourceForkType
			if(ref!=0)writeFSRef(ref,s.path);
			writeUniStr255(forkName,s.resourceFork?RESOURCE_FORK_NAME:"");
			return NO_ERR;
		}catch(IOException e){return IO_ERR;}
	}

	// ---- volumes

	/**
	 * FSGetVolumeInfo: the volume {@code volumeIndex} (1-based) or, for index 0, the one {@code volume} names - the same volumes,
	 * names, vRefNums and dates PBHGetVInfo reports, with the 64-bit byte counts and 32-bit block counts of HFS Plus
	 * (signature 'H+'), the name as an HFSUniStr255 and the root folder's FSRef.
	 */
	private int getVolumeInfo(int volume,int volumeIndex,int actualPtr,int whichInfo,int info,int namePtr,int rootRef) throws M68kCpuException {
		if(actualPtr!=0)memory.writeWord(actualPtr,0);if((whichInfo&0xffff0000)!=0)return ERR_FS_BAD_INFO_BITMAP;
		try{File root;
			if(volumeIndex>0){if(volumeIndex>fileManager.volumes.size())return NSV_ERR;root=fileManager.volumes.get(volumeIndex-1).getCanonicalFile();}
			else{root=fileManager.namedVolume(volume);if(root==null)return NSV_ERR;}
			int vRef=fileManager.volumeRefNumFor(root),index=fileManager.volumeIndexOf(root);
			if(actualPtr!=0)memory.writeWord(actualPtr,vRef);
			if(info!=0&&whichInfo!=0){
				java.nio.file.attribute.BasicFileAttributes attributes=java.nio.file.Files.readAttributes(root.toPath(),java.nio.file.attribute.BasicFileAttributes.class);
				long total=fileManager.volumeTotalSpace(root),free=fileManager.volumeLocked(index)?0:root.getUsableSpace();
				if((whichInfo&0x0001)!=0)writeUtc(info,fileManager.volumeCreationDateFor(root));
				if((whichInfo&0x0002)!=0)writeUtc(info+8,FileManager68k.macDate(attributes.lastModifiedTime().toMillis()));
				if((whichInfo&0x0004)!=0)writeUtc(info+16,0);
				if((whichInfo&0x0008)!=0)writeUtc(info+24,0);
				if((whichInfo&0x0030)!=0){File[] entries=root.listFiles();int files=0,folders=0;if(entries!=null)for(File e:entries){if(e.isFile())files++;else if(e.isDirectory())folders++;} // as PBHGetVInfo counts them
					if((whichInfo&0x0010)!=0)memory.writeLong(info+32,files);if((whichInfo&0x0020)!=0)memory.writeLong(info+36,folders);}
				if((whichInfo&0x0040)!=0){write64(info+40,total);write64(info+48,free);}
				if((whichInfo&0x0080)!=0){memory.writeLong(info+56,HFS_PLUS_BLOCK);memory.writeLong(info+60,(int)Math.min(0xffffffffL,total/HFS_PLUS_BLOCK));memory.writeLong(info+64,(int)Math.min(0xffffffffL,free/HFS_PLUS_BLOCK));}
				if((whichInfo&0x0100)!=0)memory.writeLong(info+68,0);
				if((whichInfo&0x0200)!=0)memory.writeLong(info+72,HFS_PLUS_BLOCK);
				if((whichInfo&0x0400)!=0)memory.writeLong(info+76,HFS_PLUS_BLOCK);
				if((whichInfo&0x0800)!=0)memory.writeLong(info+80,0x10000);                                   // nextCatalogID
				if((whichInfo&0x1000)!=0){memory.fill(info+84,32,0);File boot=systemFolder.getParentFile();     // finderInfo[0]: the blessed System Folder
					if(boot!=null&&systemFolder.isDirectory()&&boot.getCanonicalFile().equals(root))memory.writeLong(info+84,fileManager.directoryIdFor(systemFolder));}
				if((whichInfo&0x2000)!=0)memory.writeWord(info+116,fileManager.volumeLocked(index)?0x0080:0);  // flags (ioVAtrb): kHFSVolumeHardwareLockBit for a mounted disk image
				if((whichInfo&0x4000)!=0){memory.writeWord(info+118,0);memory.writeWord(info+120,0x482b);}    // filesystemID 0 (the File Manager's own), signature 'H+'
				if((whichInfo&0x8000)!=0){memory.writeWord(info+122,fileManager.driveOf(index));memory.writeWord(info+124,fileManager.driverRefOf(index));} // drive and driver as PBHGetVInfo
			}
			if(namePtr!=0)writeUniStr255(namePtr,fileManager.volumeNameOf(root));
			if(rootRef!=0)writeFSRef(rootRef,root);
			return NO_ERR;
		}catch(IOException e){return IO_ERR;}
	}
	/** FSSetVolumeInfo: accepted for a mounted volume; the host keeps its own dates, Finder information and flags (as PBSetVInfo). */
	private int setVolumeInfo(int volume,int whichInfo) {if((whichInfo&0xffff0000)!=0)return ERR_FS_BAD_INFO_BITMAP;try{return fileManager.namedVolume(volume)==null?NSV_ERR:NO_ERR;}catch(IOException e){return IO_ERR;}}

	// ---- the two entry points

	/**
	 * _HighLevelFSDispatch selectors of the HFS Plus API (selector word: parameter words << 8 | routine), Pascal arguments on the
	 * stack (an SInt64/UInt64 by address). Pops the arguments; returns the OSErr for the result slot.
	 */
	private int hfsPlusCall(M68kCpu cpu,int selector) throws M68kException {
		int sp=cpu.a[7];cpu.a[7]+=(selector>>>8)*2;
		switch(selector&0xff){
			case 0x18:writeUniStr255(l(sp),"");return NO_ERR;                                                        // FSGetDataForkName
			case 0x19:writeUniStr255(l(sp),RESOURCE_FORK_NAME);return NO_ERR;                                        // FSGetResourceForkName
			case 0x1a:{int spec=l(sp+4);if(spec==0)return PARAM_ERR;File f;try{f=fileForNameBytes((short)w(spec),l(spec+2),spec+6);}catch(IOException e){return IO_ERR;}return makeFSRef(f,l(sp));} // FSpMakeFSRef(source, newRef)
			case 0x1b:return makeFSRefUnicode(l(sp+16),l(sp+12),l(sp+8),l(sp));                                      // FSMakeFSRefUnicode(parentRef, nameLength, name, textEncodingHint, newRef)
			case 0x1c:return createUnicode(false,l(sp+24),l(sp+20),l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp),0);        // FSCreateFileUnicode(parentRef, nameLength, name, whichInfo, catalogInfo, newRef, newSpec)
			case 0x1d:return createUnicode(true,l(sp+28),l(sp+24),l(sp+20),l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp));  // FSCreateDirectoryUnicode(... newRef, newSpec, newDirID)
			case 0x1e:return deleteObject(l(sp));                                                                    // FSDeleteObject(ref)
			case 0x1f:return moveObject(l(sp+8),l(sp+4),l(sp));                                                      // FSMoveObject(ref, destDirectory, newRef)
			case 0x20:return renameUnicode(l(sp+16),l(sp+12),l(sp+8),l(sp));                                         // FSRenameUnicode(ref, nameLength, name, textEncodingHint, newRef)
			case 0x21:return exchangeObjects(l(sp+4),l(sp));                                                         // FSExchangeObjects(ref, destRef)
			case 0x22:return getCatalogInfo(l(sp+20),l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp));                       // FSGetCatalogInfo(ref, whichInfo, catalogInfo, outName, fsSpec, parentRef)
			case 0x23:return setCatalogInfo(l(sp+8),l(sp+4),l(sp));                                                  // FSSetCatalogInfo(ref, whichInfo, catalogInfo)
			case 0x24:return openIterator(l(sp+8),l(sp+4),l(sp));                                                    // FSOpenIterator(container, iteratorFlags, iterator)
			case 0x25:return closeIterator(l(sp));                                                                   // FSCloseIterator(iterator)
			case 0x26:return catalogBulk(l(sp+32),0,l(sp+28),l(sp+24),l(sp+20),l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp)); // FSGetCatalogInfoBulk(iterator, maximumObjects, actualObjects, containerChanged, whichInfo, catalogInfos, refs, specs, names)
			case 0x27:return catalogBulk(l(sp+36),l(sp+32),l(sp+28),l(sp+24),l(sp+20),l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp)); // FSCatalogSearch(iterator, searchCriteria, ...)
			case 0x28:return openFork(l(sp+14),l(sp+10),l(sp+6),(byte)memory.readByte(sp+4),l(sp));                  // FSOpenFork(ref, forkNameLength, forkName, SInt8 permissions, forkRefNum)
			case 0x29:case 0x2a:{int[] actual=new int[1];int offsetPtr=l(sp+12);                                     // FSReadFork/FSWriteFork(forkRefNum, positionMode, &positionOffset, requestCount, buffer, actualCount)
				int result=forkIO(cpu,(selector&0xff)==0x2a,(short)w(sp+18),w(sp+16),offsetPtr==0?0:read64(offsetPtr),l(sp+8),l(sp+4),actual);
				if(l(sp)!=0)memory.writeLong(l(sp),actual[0]);return result;}
			case 0x2b:case 0x2d:return getForkPosition((short)w(sp+4),(selector&0xff)==0x2d,l(sp));                   // FSGetForkPosition/FSGetForkSize(forkRefNum, SInt64*)
			case 0x2c:case 0x2e:{int offsetPtr=l(sp);long offset=offsetPtr==0?0:read64(offsetPtr);                   // FSSetForkPosition/FSSetForkSize(forkRefNum, positionMode, &positionOffset)
				return (selector&0xff)==0x2c?setForkPosition((short)w(sp+6),w(sp+4),offset):setForkSize((short)w(sp+6),w(sp+4),offset);}
			case 0x2f:{int requestPtr=l(sp+4);return allocateFork((short)w(sp+16),w(sp+14),requestPtr==0?0:read64(requestPtr),l(sp));} // FSAllocateFork(forkRefNum, flags, positionMode, &positionOffset, &requestCount, actualCount)
			case 0x30:return forkCall(cpu,(short)w(sp),0xa045);                                                      // FSFlushFork(forkRefNum)
			case 0x31:return forkCall(cpu,(short)w(sp),0xa001);                                                      // FSCloseFork(forkRefNum)
			case 0x32:return getForkCBInfo((short)w(sp+22),(short)w(sp+20),l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp));  // FSGetForkCBInfo(desiredRefNum, volume, iterator, actualRefNum, forkInfo, ref, outForkName)
			case 0x33:return getVolumeInfo((short)w(sp+24),l(sp+20),l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp));        // FSGetVolumeInfo(volume, volumeIndex, actualVolume, whichInfo, info, volumeName, rootDirectory)
			case 0x34:return setVolumeInfo((short)w(sp+8),l(sp+4));                                                  // FSSetVolumeInfo(volume, whichInfo, info)
			case 0x35:return compareFSRefs(l(sp+4),l(sp));                                                           // FSCompareFSRefs(ref1, ref2)
			case 0x36:return createFork(l(sp+8),l(sp+4),l(sp));                                                      // FSCreateFork(ref, forkNameLength, forkName)
			case 0x37:return deleteFork(l(sp+8),l(sp+4),l(sp));                                                      // FSDeleteFork(ref, forkNameLength, forkName)
			case 0x38:return iterateForks(l(sp+16),l(sp+12),l(sp+8),l(sp+4),l(sp));                                  // FSIterateForks(ref, forkIterator, forkName, forkSize, forkPhysicalSize)
			default:cpu.a[7]=sp;throw new M68kMacApiException(cpu.pc,0xaa52,"HighLevelFSDispatch selector $"+Integer.toHexString(selector));
		}
	}

	/**
	 * The parameter-block forms: HFSDispatch ($A260) selectors $1D/$1E (FSVolumeInfoParam), $53-$5D, $6E-$7F (FSRefParam,
	 * FSForkIOParam, FSCatalogBulkParam, FSForkCBInfoParam) and $A2A8 selectors $51/$52 (PBReadFork/PBWriteFork). They share
	 * the implementations of the Pascal forms. False for any other selector.
	 */
	private boolean hfsPlusParamBlock(M68kCpu cpu,int trap,int selector,boolean async) throws M68kException {
		int pb=cpu.a[0],result;
		if(trap==0xa8){
			if(selector!=0x51&&selector!=0x52){finish(cpu,pb,PARAM_ERR,async);return true;}
			int[] actual=new int[1];result=forkIO(cpu,selector==0x52,(short)w(pb+24),w(pb+44),read64(pb+46),l(pb+36),l(pb+32),actual);memory.writeLong(pb+40,actual[0]);
			finish(cpu,pb,result,async);return true;
		}
		switch(selector){
			// FSVolumeInfoParam: ioVRefNum 22, volumeIndex 24, whichInfo 28, volumeInfo 32, volumeName 36, ref 40
			case 0x1d:{int vref=memory.allocate(2,2);try{result=getVolumeInfo((short)w(pb+22),l(pb+24),vref,l(pb+28),l(pb+32),l(pb+36),l(pb+40));if(result==NO_ERR)memory.writeWord(pb+22,memory.readWord(vref));}finally{memory.free(vref);}break;}
			case 0x1e:result=setVolumeInfo((short)w(pb+22),l(pb+28));break;
			// FSForkIOParam: forkRefNum 24, permissions 27, ref 28, buffer 32, requestCount 36, actualCount 40, positionMode 44, positionOffset 46,
			// allocationFlags 54, allocationAmount 56, forkNameLength 64, forkName 68, forkIterator 72, outForkName 88
			case 0x53:case 0x55:result=getForkPosition((short)w(pb+24),selector==0x55,pb+46);break;
			case 0x54:result=setForkPosition((short)w(pb+24),w(pb+44),read64(pb+46));break;
			case 0x56:result=setForkSize((short)w(pb+24),w(pb+44),read64(pb+46));break;
			case 0x57:result=allocateFork((short)w(pb+24),w(pb+54),read64(pb+56),pb+56);break;
			case 0x58:result=forkCall(cpu,(short)w(pb+24),0xa045);break;
			case 0x59:result=forkCall(cpu,(short)w(pb+24),0xa001);break;
			case 0x79:result=openFork(l(pb+28),l(pb+64),l(pb+68),(byte)memory.readByte(pb+27),pb+24);break;
			case 0x7d:result=createFork(l(pb+28),l(pb+64),l(pb+68));break;
			case 0x7e:result=deleteFork(l(pb+28),l(pb+64),l(pb+68));break;
			case 0x7f:result=iterateForks(l(pb+28),pb+72,l(pb+88),pb+46,pb+56);break;
			// FSForkCBInfoParam: desiredRefNum 18, volumeRefNum 20, iterator 22, actualRefNum 24, ref 26, forkInfo 30, forkName 34
			case 0x5a:result=getForkCBInfo((short)w(pb+18),(short)w(pb+20),pb+22,pb+24,l(pb+30),l(pb+26),l(pb+34));break;
			// FSCatalogBulkParam: containerChanged 18, iteratorFlags 20, iterator 24, container 28, maximumItems 32, actualItems 36, whichInfo 40,
			// catalogInfo 44, refs 48, specs 52, names 56, searchParams 60
			case 0x78:result=openIterator(l(pb+28),l(pb+20),pb+24);break;
			case 0x5b:result=closeIterator(l(pb+24));break;
			case 0x5c:result=catalogBulk(l(pb+24),0,l(pb+32),pb+36,pb+18,l(pb+40),l(pb+44),l(pb+48),l(pb+52),l(pb+56));break;
			case 0x5d:result=l(pb+60)==0?ERR_FS_BAD_SEARCH_PARAMS:catalogBulk(l(pb+24),l(pb+60),l(pb+32),pb+36,pb+18,l(pb+40),l(pb+44),l(pb+48),l(pb+52),l(pb+56));break;
			// FSRefParam: ioNamePtr 18, ioVRefNum 22, ref 28, whichInfo 32, catInfo 36, nameLength 40, name 44, ioDirID 48, spec 52, parentRef 56,
			// newRef 60, textEncodingHint 64, outName 68
			case 0x6e:{File f;try{f=fileForNameBytes((short)w(pb+22),l(pb+48),l(pb+18));}catch(IOException e){f=null;}result=makeFSRef(f,l(pb+60));break;}
			case 0x7a:result=makeFSRefUnicode(l(pb+28),l(pb+40),l(pb+44),l(pb+60));break;
			case 0x7c:result=compareFSRefs(l(pb+28),l(pb+56));break;
			case 0x70:result=createUnicode(false,l(pb+28),l(pb+40),l(pb+44),l(pb+32),l(pb+36),l(pb+60),l(pb+52),0);break;
			case 0x71:result=createUnicode(true,l(pb+28),l(pb+40),l(pb+44),l(pb+32),l(pb+36),l(pb+60),l(pb+52),pb+48);break;
			case 0x72:result=deleteObject(l(pb+28));break;
			case 0x73:result=moveObject(l(pb+28),l(pb+56),l(pb+60));break;
			case 0x74:result=renameUnicode(l(pb+28),l(pb+40),l(pb+44),l(pb+60));break;
			case 0x75:result=exchangeObjects(l(pb+28),l(pb+56));break;
			case 0x76:result=getCatalogInfo(l(pb+28),l(pb+32),l(pb+36),l(pb+68),l(pb+52),l(pb+56));break;
			case 0x77:result=setCatalogInfo(l(pb+28),l(pb+32),l(pb+36));break;
			default:return false;
		}
		finish(cpu,pb,result,async);return true;
	}

	// ------------------------------------------------------------------ helpers for other managers

	int workingDirectoryFor(File directory){try{return fileManager.workingDirectoryRefFor(directory);}catch(IOException e){return -1;}}
	int volumeRefNumFor(File file) throws IOException {return fileManager.volumeRefNumFor(file);}
	/** The startup disk's volume reference number (the volume holding the System Folder). */
	int startupVolumeRefNum(){try{return fileManager.volumeRefNumFor(systemFolder);}catch(IOException e){return -1;}}
	int directoryIdFor(File directory) throws IOException {return fileManager.directoryIdFor(directory);}
	int fileType(File file){return FinderInfo68k.type(file);}
	int fileCreator(File file){return FinderInfo68k.creator(file);}

	/** File for an FSSpec (vRefNum, parID, name). */
	File fileForSpec(int spec) throws IOException {
		if(spec==0)return null;
		int vRef,dirId;byte[] name;
		try{vRef=(short)memory.readWord(spec);dirId=memory.readLong(spec+2);byte[] raw=tb.readPascalBytes(spec+6);name=java.util.Arrays.copyOfRange(raw,1,raw.length);}catch(M68kCpuException e){return null;}
		if(dirId==1&&vRef!=0)return fileManager.namedVolume(vRef); // a volume's root: (vRefNum, fsRtParID, its name) - Disk Copy resolves the volume it mounted so
		File base=fileManager.directoryForId(dirId,vRef);
		if(base==null)return null;
		if(name.length==0)return base;
		try{return fileManager.resolveBytes(base,name);}catch(SecurityException e){return null;}
	}
	/** An FSSpec as (vRefNum, parID, "name") for trace output. */
	String describeSpec(int spec){
		try{return "("+(short)memory.readWord(spec)+", "+memory.readLong(spec+2)+", \""+tb.readPascal(spec+6)+"\")";}catch(M68kCpuException e){return "(unreadable FSSpec)";}
	}
	File fileFor(int vRef,int dirId,String name) throws IOException {
		File base=fileManager.directoryForId(dirId,vRef);
		if(base==null)return null;
		if(name==null||name.isEmpty())return base;
		try{return fileManager.resolveClassicPath(base,name);}catch(SecurityException e){return null;}
	}
	File fileForClassicPath(String path) throws IOException {
		if(path==null||path.isEmpty())return null;
		File base=fileManager.directoryForId(0,0);
		try{return fileManager.resolveClassicPath(base,path);}catch(SecurityException e){return null;}
	}
	/**
	 * The "poor man's search path" of the classic File Manager: an open by a bare name (no colon) on the default directory that
	 * finds nothing there tries the blessed System Folder next. MacinTalk 1 is installed by dropping its driver file into the
	 * System Folder, and its glue does OpenResFile("MacinTalk") (MacinTalk Stack, HyperMacintalk).
	 */
	File searchSystemFolder(String name,File found){
		if(found!=null&&found.exists())return found;
		if(name==null||name.isEmpty()||name.indexOf(':')>=0||systemFolder==null)return found;
		try{File f=fileManager.resolveClassicPath(systemFolder,name);if(f!=null&&f.exists())return f;}catch(IOException|SecurityException e){}
		return found;
	}
	void writeFileSpec(int spec,File file) throws IOException,M68kCpuException {
		if(spec==0||file==null)return;
		File parent=file.getAbsoluteFile().getParentFile();
		memory.writeWord(spec,fileManager.volumeRefNumFor(file));
		memory.writeLong(spec+2,parent==null?2:fileManager.parentDirectoryIdFor(file));
		tb.writePascal(spec+6,fileManager.volumeRoot(file)?fileManager.volumeNameOf(file):fileManager.macNameOf(file),63); // a volume's root: (vRefNum, fsRtParID, volume name); a MacBinary container is known by its header name
	}

	/** The "System" file of the virtual System Folder: {@code System Folder:System} (type zsys/MACS, a 'vers' 1 resource). */
	File systemFile(){return new File(systemFolder,"System");}
	/**
	 * Pre-System 7 installers (Aladdin InstallerMaker 1.x: After Dark 2.0's "Double-Click Me to Install") find the target
	 * disk's System Folder through HGetVInfo's ioVFndrInfo[0], look for the System and Finder files in it under the names
	 * of the boot blocks (SysResName $AD8 / FinderName $2E0 when those cannot be read) and open the System to read its
	 * 'vers' resource, so stub System and Finder files with that resource are kept on disk; OpenResFile on the System stub
	 * hands out the built-in System file ({@link ResourceManager68k#openFile}), the stubs carry no resources a program would miss.
	 */
	private void ensureSystemFile(){
		// 'zsys' is the System 7 System file ('ZSYS' was System 6's): Apple's Installer 4 scripts name it by type and creator
		// (QuickDraw 3D 1.5.3's "special-macs:System" zsys/MACS) and a stub of the old type made them report no System 7.1.2
		File system=systemFile();
		ensureStub(system,"zsysMACS");
		byte[] info=system.exists()?FinderInfo68k.stored(system):null;
		if(info!=null&&info.length>=8&&new String(info,0,8,java.nio.charset.StandardCharsets.ISO_8859_1).equals("ZSYSMACS")){info[0]='z';info[1]='s';info[2]='y';info[3]='s';FinderInfo68k.write(system,info);}
		ensureStub(new File(systemFolder,"Finder"),"FNDRMACS");
		// Mac OS 9 OpenGL games check the installed version from the 'vers' of Extensions:OpenGLEngine (Last Contact: FSpOpenResFile,
		// "Need OpenGL version 1.2.1 or newer" when it is missing); the native OpenGL answers as 1.2.1, so a stub says so.
		// It has no 'cfrg', so the Code Fragment Manager never takes it for a library.
		if(PpcOpenGL.NATIVE)ensureStub(new File(new File(systemFolder,"Extensions"),"OpenGLEngine"),"shlbcfmg",OPENGL_VERS);
		// Internet Config's application (1.x, with Anarchie 1.6) looks for Extensions:Internet Config Extension by name and offers
		// to install it when it is missing, although the component (InternetConfig68k) answers; Mac OS 9 has the file. No INIT code.
		if(!"false".equals(System.getProperty("hz68k.internetconfig")))ensureStub(new File(new File(systemFolder,"Extensions"),"Internet Config Extension"),"INITICAp",INTERNET_CONFIG_VERS);
	}
	/** 'vers' 2.0 final: the Internet Config InternetConfig68k reports (ICCGetVersion). */
	private static final byte[] INTERNET_CONFIG_VERS={2,0,(byte)0x80,0,0,0,3,'2','.','0',3,'2','.','0'};
	/** 'vers' 1.2.1 final: Apple OpenGL for Mac OS 9 as {@link PpcOpenGL} reports it. */
	private static final byte[] OPENGL_VERS={1,0x21,(byte)0x80,0,0,0,5,'1','.','2','.','1',5,'1','.','2','.','1'};
	private static void ensureStub(File file,String typeAndCreator){ensureStub(file,typeAndCreator,new byte[]{7,0x55,(byte)0x80,0,0,0,5,'7','.','5','.','5',5,'7','.','5','.','5'});}
	private static void ensureStub(File file,String typeAndCreator,byte[] vers){
		if(file.exists())return;
		try{
			ResourceFile68k f=new ResourceFile68k(file);
			f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("vers"),1,null,0,vers));
			f.write();
			byte[] info=new byte[16];System.arraycopy(typeAndCreator.getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,info,0,8); // type, creator, no Finder flags
			FinderInfo68k.write(file,info);
		}catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[file] "+file.getName()+" stub not created: "+e);}
	}

	/** The startup disk's "Desktop Folder" (System 7), made on first use: the desktop a Finder running here manages. */
	File finderDesktopFolder(){File f=new File(systemFolder.getParentFile(),"Desktop Folder");if(!f.exists())f.mkdirs();return f.isDirectory()?f:null;}
	/** Open outside the File Manager: a resource file of the Resource Manager, or the running program's own file. */
	private boolean openElsewhere(File f){
		if(tb.resources!=null&&tb.resources.isOpenFile(f))return true;
		if(tb.stack!=null&&TTalk.isOpenFile(f))return true; // a HyperTalk "open file" of the stack's scripts: an external cannot delete it (fBsyErr)
		try{return tb.app!=null&&tb.app.file!=null&&tb.app.file.getCanonicalFile().equals(f.getCanonicalFile());}catch(IOException e){return false;}
	}
	File specialFolder(int type,boolean create){
		File f;boolean onDemand=false; // a System Folder subfolder that is only there once something put it there
		switch(type){
			case 0x6d616373:f=systemFolder;break;                       // macs
			case 0x70726566:f=new File(systemFolder,"Preferences");break; // pref
			case 0x70726f66:f=new File(systemFolder,"ColorSync Profiles");break; // prof: where ColorSync68k looks
			case 0x6578746e:case 0x65787420:case 0x65787461:f=new File(systemFolder,"Extensions");break; // extn (kExtensionFolderType), exta
			case 0x6374726c:f=new File(systemFolder,"Control Panels");break; // ctrl
			// temp: a "Temporary Items" folder at the root of the startup disk, as on a real Mac. The host's own temporary directory
			// held 80,000 entries here and every name lookup in it took seconds (輪芸 saves its preferences through a temporary file at quit).
			// It is there from start-up on System 7, so it is made whatever createFolder says (Sam's FindFolder XFCN asks kDontCreateFolder)
			case 0x74656d70:f=new File(systemFolder.getParentFile(),"Temporary Items");create=true;break;
			// trsh/empt: the startup disk's "Trash" folder at its root, as System 7 keeps it (the Finder looks for it in the root
			// directory and gives up at start-up without it). Never the host's own Trash: a program emptying the Trash would
			// delete what the user threw away outside HyperZebra.
			case 0x74727368:case 0x656d7074:f=new File(systemFolder.getParentFile(),"Trash");create=true;break;
			case 0x6465736b:f=new File(new File(System.getProperty("user.home")),"Desktop");if(!f.isDirectory()){File d=finderDesktopFolder();if(d!=null)f=d;}break; // desk; without a host Desktop the startup disk's Desktop Folder (always there on System 7)
			case 0x646f6373:f=new File(new File(System.getProperty("user.home")),"Documents");break; // docs
			case 0x616d6e75:f=new File(systemFolder,"Apple Menu Items");break; // amnu
			case 0x73747274:f=new File(systemFolder,"Startup Items");break; // strt
			case 0x666f6e74:f=new File(systemFolder,"Fonts");break;       // font
			case 0x70726e74:case 0x70706466:f=new File(systemFolder,"PrintMonitor Documents");break; // prnt (kPrintMonitorDocsFolderType)
			case 0x73686466:f=new File(systemFolder,"Shutdown Items");break;   // shdf
			// Mac OS 8/9 folders (installers of that time put files there: Install iTunes 2 its Control Strip module)
			case 0x73646576:f=new File(systemFolder,"Control Strip Modules");break; // sdev
			case 0xc4686c70:f=new File(systemFolder,"Help");break;             // ƒhlp: kHelpFolderType
			case 0x61737570:f=new File(systemFolder,"Application Support");break; // asup
			case 0xc4736372:f=new File(systemFolder,"Scripting Additions");break; // ƒscr
			case 0x61707072:f=new File(systemFolder,"Appearance");break;       // appr
			case 0x74686d65:f=new File(new File(systemFolder,"Appearance"),"Theme Files");break; // thme
			// QuickTime 6's installer (VISE) resolves these before it installs anything; one that comes back fnfErr is
			// "This installation requires a System Folder on the destination disk" (1008:1,-43)
			case 0x73707266:f=new File(systemFolder,"Preferences");break;      // sprf: kSystemPreferencesFolderType
			case 0x7363746c:f=new File(systemFolder,"Control Panels");break;   // sctl: kSystemControlPanelFolderType
			case 0xc46e6574:f=new File(systemFolder,"Internet Plug-Ins");break; // ƒnet: kInternetPlugInFolderType
			case 0x69737366:f=new File(systemFolder,"Internet Search Sites");break; // issf
			case 0x636d6e75:f=new File(systemFolder,"Contextual Menu Items");break; // cmnu
			// Extensions Manager's folders for turned-off items (System 7.5 'fld#'; Folders.h kExtensionDisabledFolderType...):
			// AIM 4.3's French installer (VISE) asks 'extD' and 'strD' with kCreateFolder before installing its extensions
			case 0x65787444:f=new File(systemFolder,"Extensions (Disabled)");onDemand=true;break;        // extD
			case 0x63747244:f=new File(systemFolder,"Control Panels (Disabled)");onDemand=true;break;    // ctrD
			case 0x6d616344:f=new File(systemFolder,"System Extensions (Disabled)");onDemand=true;break; // macD
			case 0x73747244:f=new File(systemFolder,"Startup Items (Disabled)");onDemand=true;break;     // strD
			case 0x73686444:f=new File(systemFolder,"Shutdown Items (Disabled)");onDemand=true;break;    // shdD
			case 0x66617673:f=new File(systemFolder,"Favorites");break;        // favs
			case 0xc4746578:f=new File(systemFolder,"Text Encodings");break;   // ƒtex
			// qtex: "QuickTime Extensions" in the Extensions folder (the name QuickTime's own AddFolderDescriptor gives it), made when asked
			case 0x71746578:f=new File(new File(systemFolder,"Extensions"),"QuickTime Extensions");break;
			// apps: the "Applications" folder at the root of the startup disk, only made when asked
			case 0x61707073:f=new File(systemFolder.getParentFile(),"Applications");break;
			default:return null;
		}
		// the System Folder's own subfolders always exist on a real Mac (UxFindFolder("amnu") returned nothing for a missing
		// "Apple Menu Items"), so they are made on first use whatever createFolder says; other folders only when asked
		if(!f.exists()&&(create||!onDemand&&systemFolder.equals(f.getParentFile())))f.mkdirs();
		if(type==0x74656d70&&f.isDirectory())rescueTemporaryItems(f);
		return f.exists()?f:null;
	}

	/** When this JVM loaded the file system: what Temporary Items held before is left from an earlier run. */
	private static final long STARTED=System.currentTimeMillis();
	private static final java.util.Set<String> RESCUED=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	/**
	 * Temporary Items as a Mac starts up with it: what earlier runs left there moves to "Rescued items from <disk>" in the
	 * Trash, once per run (the startup disk's folder kept everything for good, and 「謎」-REMIX-'s MIDI XFCN, which looks for a
	 * free MIDI_XCMD_TEMP.1 … .9 and cuts .10 back to .1, hung at the open of the stack after nine runs).
	 */
	private void rescueTemporaryItems(File folder){
		if(!RESCUED.add(folder.getAbsolutePath()))return;
		File[] left=folder.listFiles(x->x.lastModified()<STARTED&&!x.getName().startsWith("."));
		if(left==null||left.length==0)return;
		File rescued=new File(new File(folder.getParentFile(),"Trash"),"Rescued items from "+fileManager.volumeNameOf(folder));
		if(!rescued.isDirectory()&&!rescued.mkdirs())return;
		for(File x:left){File to=new File(rescued,x.getName());for(int i=2;to.exists();i++)to=new File(rescued,x.getName()+" "+i);if(!x.renameTo(to)&&Mac68kApi.TRACE)System.err.println("[file] could not rescue "+x);}
	}

	/**
	 * Alias records in the real 'alis' layout (Inside Macintosh: Files, "Alias Record Format"): the fixed part with the
	 * volume and file names, ids, type and creator, then variable-length information records - the parent folder name (0),
	 * the absolute path (2) and a private record (type $485A 'HZ') carrying the UTF-8 host path. QuickTime's data handlers
	 * parse these fields themselves (the old opaque 'HZAL' record made them reject every data reference); {@link #resolveAlias}
	 * prefers the private record and falls back to the absolute path, so aliases written by real programs resolve too.
	 */
	static final int ALIAS_HOST_PATH_RECORD=0x485a;
	byte[] makeAlias(File file){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		try{
			File f=file.getAbsoluteFile();File parent=f.getParentFile();
			boolean folder=f.isDirectory();
			String volumeName=fileManager.volumeNameOf(f),fileName=fileManager.macNameOf(f),parentName=parent==null?"":fileManager.macNameOf(parent);
			int parentId=parent==null?2:fileManager.parentDirectoryIdFor(f),fileNumber=folder?fileManager.directoryIdFor(f):fileManager.fileNumberFor(f);
			byte[] info=FinderInfo68k.read(f);int type=0,creator=0;if(info!=null&&!folder){type=MacApp68k.u4(info,0);creator=MacApp68k.u4(info,4);}
			out.write(new byte[4]);                 // userType
			out.write(new byte[2]);                 // aliasSize, patched below
			putShort(out,2);                        // version
			putShort(out,folder?1:0);               // kind: file / folder
			putPascal(out,volumeName,27);           // volume name (Str27)
			putLong(out,fileManager.volumeCreationDateFor(f)); // volume creation date (checked against GetVolInfo's)
			putShort(out,0x4244);                   // volume signature 'BD'
			putShort(out,0);                        // volume type: fixed hard disk
			putLong(out,parentId);                  // parent directory id
			putPascal(out,fileName,63);             // file name (Str63)
			putLong(out,fileNumber);                // file number / directory id
			putLong(out,0);                         // file creation date
			putLong(out,type);putLong(out,creator); // type, creator
			putShort(out,-1);putShort(out,-1);      // nlvlFrom, nlvlTo: unknown
			putLong(out,0);                         // volume attributes
			putShort(out,0);                        // volume filesystem id
			out.write(new byte[10]);                // reserved
			byte[] parentBytes=pascalBytes(parentName,255);putShort(out,0);putShort(out,parentBytes.length);out.write(parentBytes);if((parentBytes.length&1)!=0)out.write(0);
			byte[] pathBytes=tb.encoding.encode(classicPath(f));if(pathBytes.length>0xffff)pathBytes=java.util.Arrays.copyOf(pathBytes,0xffff);
			putShort(out,2);putShort(out,pathBytes.length);out.write(pathBytes);if((pathBytes.length&1)!=0)out.write(0);
			byte[] host=f.getPath().getBytes(StandardCharsets.UTF_8);putShort(out,ALIAS_HOST_PATH_RECORD);putShort(out,host.length);out.write(host);if((host.length&1)!=0)out.write(0);
			putShort(out,-1);putShort(out,0);       // end of the information records
		}catch(Exception e){out.reset();byte[] host=file.getAbsolutePath().getBytes(StandardCharsets.UTF_8);out.write(new byte[0x96],0,0x96);putShort(out,ALIAS_HOST_PATH_RECORD);putShort(out,host.length);out.write(host,0,host.length);if((host.length&1)!=0)out.write(0);putShort(out,-1);putShort(out,0);}
		byte[] alias=out.toByteArray();alias[4]=(byte)(alias.length>>>8);alias[5]=(byte)alias.length;
		return alias;
	}
	private static void putShort(java.io.ByteArrayOutputStream out,int v){out.write(v>>>8);out.write(v);}
	private static void putLong(java.io.ByteArrayOutputStream out,int v){out.write(v>>>24);out.write(v>>>16);out.write(v>>>8);out.write(v);}
	private byte[] pascalBytes(String s,int max){byte[] b;try{b=tb.encoding.encode(s);}catch(Exception e){b=s.getBytes(StandardCharsets.ISO_8859_1);}if(b.length>max)b=java.util.Arrays.copyOf(b,max);byte[] p=new byte[b.length+1];p[0]=(byte)b.length;System.arraycopy(b,0,p,1,b.length);return p;}
	private void putPascal(java.io.ByteArrayOutputStream out,String s,int max){byte[] p=pascalBytes(s,max);out.write(p,0,p.length);out.write(new byte[max+1-p.length],0,max+1-p.length);}
	File resolveAlias(byte[] alias){File f=aliasTarget(alias);return f!=null&&f.exists()?f:null;}
	/**
	 * Where an alias points even when the target itself is gone: the file named in a folder that exists (null when not even
	 * the folder can be found). The Alias Manager answers such aliases with fnfErr and a valid FSSpec (Inside Macintosh:
	 * Files, ResolveAlias / MatchAlias) - QuickTime's alias data handler relies on it when FlattenMovieData creates its file.
	 */
	File aliasTarget(byte[] alias){return aliasTarget(alias,null);}
	/** As {@link #aliasTarget(byte[])}, and relative to {@code from} (ResolveAlias's fromFile) when the absolute search fails. */
	File aliasTarget(byte[] alias,File from){
		File f=resolveExisting(alias);if(f!=null)return f;
		File relative=from==null?null:relativeAliasTarget(alias,from);if(relative!=null&&relative.exists())return relative;
		if(alias==null||alias.length<0x96||(alias[0]=='H'&&alias[1]=='Z'&&alias[2]=='A'&&alias[3]=='L'))return null;
		// a file renamed in its folder since the alias was made: the Alias Manager finds it by its file number (OpenDoc saves
		// a document by renaming it aside and resolving its alias - "The file was not found." and the document was gone)
		if(MacApp68k.u2(alias,8)==0)try{File dir=fileManager.directoryForId(MacApp68k.u4(alias,0x2e),0);File renamed=fileManager.fileNumbered(dir,MacApp68k.u4(alias,0x72));if(renamed!=null)return renamed;}catch(IOException ignored){}
		String host=null;
		for(int at=0x96;at+4<=alias.length;){int type=(short)MacApp68k.u2(alias,at),length=MacApp68k.u2(alias,at+2);at+=4;if(type==-1||at+length>alias.length)break;
			if(type==ALIAS_HOST_PATH_RECORD)host=new String(alias,at,length,StandardCharsets.UTF_8);at+=length+(length&1);}
		if(host!=null){File h=new File(host);if(h.getParentFile()!=null&&h.getParentFile().isDirectory())return h;}
		try{int parentId=MacApp68k.u4(alias,0x2e);String name=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,0x33,0x33+(alias[0x32]&0xff)));
			File dir=fileManager.directoryForId(parentId,0);if(dir!=null&&dir.isDirectory()&&!name.isEmpty())return new File(dir,name);}catch(Exception ignored){}
		return null;
	}
	/** Whether the folder an alias's target should be in is missing (ResolveAlias: dirNFErr, not fnfErr). */
	boolean aliasFolderMissing(byte[] alias){
		if(alias==null||alias.length<0x96||(alias[0]=='H'&&alias[1]=='Z'&&alias[2]=='A'&&alias[3]=='L'))return false;
		String host=null,path=null;
		for(int at=0x96;at+4<=alias.length;){int type=(short)MacApp68k.u2(alias,at),length=MacApp68k.u2(alias,at+2);at+=4;if(type==-1||at+length>alias.length)break;
			if(type==ALIAS_HOST_PATH_RECORD)host=new String(alias,at,length,StandardCharsets.UTF_8);
			else if(type==2)path=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,at,at+length));
			at+=length+(length&1);}
		File f=host!=null?new File(host):null;
		if(f==null&&path!=null)try{f=fileForClassicPath(path);}catch(IOException e){return false;}
		File parent=f==null?null:f.getParentFile();
		return parent!=null&&!parent.isDirectory();
	}
	/**
	 * The relative search of an alias made on another Mac (IM: More Macintosh Toolbox, Alias Manager): nlvlFrom ($82) levels up
	 * from fromFile's folder to the common ancestor, then the last nlvlTo ($84) names of the full path (record 2), or of the
	 * parent folder name (record 0) and file name. Nanosaur's skeleton files point at their 3DMF models this way.
	 */
	private File relativeAliasTarget(byte[] alias,File from){
		if(alias==null||alias.length<0x96)return null;
		int levelsFrom=(short)MacApp68k.u2(alias,0x82),levelsTo=(short)MacApp68k.u2(alias,0x84);
		if(levelsFrom<1||levelsTo<1)return null;
		String path=null,parentName=null;
		for(int at=0x96;at+4<=alias.length;){int type=(short)MacApp68k.u2(alias,at),length=MacApp68k.u2(alias,at+2);at+=4;if(type==-1||at+length>alias.length)break;
			if(type==2)path=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,at,at+length));
			else if(type==0)parentName=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,at,at+length));
			at+=length+(length&1);}
		String name=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,0x33,0x33+Math.min(63,alias[0x32]&0xff)));
		java.util.List<String> parts=new java.util.ArrayList<String>();
		if(path!=null){for(String p:path.split(":"))if(!p.isEmpty())parts.add(p);}
		else if(levelsTo==1)parts.add(name);
		else if(levelsTo==2&&parentName!=null){parts.add(parentName);parts.add(name);}
		if(parts.size()<levelsTo)return null;
		File dir=from.getAbsoluteFile().getParentFile();
		for(int i=1;i<levelsFrom&&dir!=null;i++)dir=dir.getParentFile();
		if(dir==null)return null;
		StringBuilder relative=new StringBuilder();
		for(String p:parts.subList(parts.size()-levelsTo,parts.size()))relative.append(':').append(p);
		try{return fileManager.resolveClassicPath(dir,relative.toString());}catch(IOException|SecurityException e){return null;}
	}
	private File resolveExisting(byte[] alias){
		if(alias==null)return null;
		if(alias.length>=8&&alias[0]=='H'&&alias[1]=='Z'&&alias[2]=='A'&&alias[3]=='L'){ // the previous private format, still found in saved preferences
			int n=MacApp68k.u4(alias,4);if(n<0||8+n>alias.length)return null;
			File f=new File(new String(alias,8,n,StandardCharsets.UTF_8));return f.exists()?f:null;}
		if(alias.length<0x96)return null;
		String path=null,host=null;
		for(int at=0x96;at+4<=alias.length;){int type=(short)MacApp68k.u2(alias,at),length=MacApp68k.u2(alias,at+2);at+=4;if(type==-1||at+length>alias.length)break;
			if(type==ALIAS_HOST_PATH_RECORD)host=new String(alias,at,length,StandardCharsets.UTF_8);
			else if(type==2)path=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,at,at+length));
			at+=length+(length&1);}
		if(host!=null){File f=new File(host);if(f.exists())return f;}
		if(path!=null)try{File f=fileForClassicPath(path);if(f!=null&&f.exists())return f;}catch(IOException ignored){}
		// no usable record: the fixed part still names the file and its parent directory id
		try{int parentId=MacApp68k.u4(alias,0x2e);String name=tb.encoding.decode(java.util.Arrays.copyOfRange(alias,0x33,0x33+(alias[0x32]&0xff)));
			File dir=fileManager.directoryForId(parentId,0);if(dir!=null){File f=new File(dir,name);if(f.exists())return f;}}catch(Exception ignored){}
		return null;
	}

	/**
	 * Volume control blocks (IM: Files, VCB) for every mounted volume, chained from VCBQHdr ($356) with DefVCBPtr ($352)
	 * naming the first: QuickTime's alias data handler walks the queue itself to list the volumes it can serve (an empty
	 * queue made it reject every file). Only the fields programs read are filled: vcbVRefNum, vcbVN, the signature,
	 * block counts and the driver numbers.
	 */
	private int[] volumeControlBlocks=new int[0];
	/** The VCB describing the volume of {@code file} (the first volume's for null or unknown files); 0 before the blocks exist. */
	int volumeControlBlockFor(File file){
		if(volumeControlBlocks.length==0)return 0;
		int index=0;if(file!=null)try{int i=fileManager.volumeIndexOf(file);if(i>=0)index=i;}catch(IOException ignored){}
		return index<volumeControlBlocks.length?volumeControlBlocks[index]:volumeControlBlocks[0];
	}
	void writeVolumeControlBlocks(){
		// externals too: Lightbox's GetVInfo XFCN finds a volume's drive in the drive queue ("???" for removable/ejectable
		// without it, and Hyped's "if c = 0 and r" then stopped on a non-boolean)
		try{
			volumeControlBlocks=new int[0];
			memory.writeWord(0x356,0);memory.writeLong(0x358,0);memory.writeLong(0x35c,0); // VCBQHdr: qFlags, qHead, qTail
			memory.writeWord(0x308,0);memory.writeLong(0x30a,0);memory.writeLong(0x30e,0); // DrvQHdr
			for(int i=0;i<fileManager.volumes.size();i++)appendVolumeControlBlock(i);
			memory.writeLong(0x352,volumeControlBlocks.length>0?volumeControlBlocks[0]:0); // DefVCBPtr
		}catch(M68kCpuException ignored){}
	}
	/** The VCB of volume {@code i} at the tail of the VCB queue, and its drive at the tail of the drive queue. */
	private void appendVolumeControlBlock(int i) throws M68kCpuException {
		File volume=fileManager.volumes.get(i);String name=fileManager.volumeNames.get(i);boolean locked=fileManager.volumeLocked(i);
		int vcb=memory.allocate(178,4);memory.fill(vcb,178,0);
		memory.writeWord(vcb+4,0);                                   // qType
		memory.writeWord(vcb+8,0x4244);                              // vcbSigWord 'BD'
		// vcbCrDate / vcbLsMod as GetVolInfo reports them: the System 7.5 Finder compares its volumes' creation dates with
		// the VCBs' every second and rebuilds a volume that "changed" (closing all its windows)
		try{memory.writeLong(vcb+10,fileManager.volumeCreationDateFor(volume));memory.writeLong(vcb+14,FileManager68k.macDate(volume.lastModified()));}catch(java.io.IOException ignored){}
		memory.writeWord(vcb+18,locked?0x0080:0);                    // vcbAtrb: a mounted disk image is locked (by "hardware", as a CD)
		long total=fileManager.volumeTotalSpace(volume),free=locked?0:volume.getFreeSpace();int blockSize=FileManager68k.volumeBlockSize(total);
		memory.writeWord(vcb+26,FileManager68k.volumeBlocks(total,blockSize)); // vcbNmAlBlks
		memory.writeLong(vcb+28,blockSize);memory.writeLong(vcb+32,blockSize); // vcbAlBlkSiz, vcbClpSiz
		memory.writeLong(vcb+38,100);                                // vcbNxtCNID
		memory.writeWord(vcb+42,FileManager68k.volumeBlocks(free,blockSize)); // vcbFreeBks
		tb.writePascal(vcb+44,fileManager.programName(name),27);     // vcbVN, as GetVolInfo names the volume
		memory.writeWord(vcb+72,fileManager.driveOf(i));memory.writeWord(vcb+74,fileManager.driverRefOf(i)); // vcbDrvNum, vcbDRefNum: the fixed-disk driver of the drive queue and GetVolInfo (the Finder asks it for the volume icon)
		memory.writeWord(vcb+76,0x485a);memory.writeWord(vcb+78,-(i+1)); // vcbFSID 'HZ' (an external file system: nobody computes disk blocks from our FCBs), vcbVRefNum
		int tail=memory.readLong(0x35c);
		if(tail!=0)memory.writeLong(tail,vcb);else memory.writeLong(0x358,vcb);
		memory.writeLong(0x35c,vcb);
		int[] grown=java.util.Arrays.copyOf(volumeControlBlocks,i+1);grown[i]=vcb;volumeControlBlocks=grown;
		if(fileManager.guestDrives.containsKey(i))return; // the program's own drive is in the queue already (_AddDrive)
		// The drive queue (DrvQHdr $308): one drive per volume, fixed disks or removable CDs (write-protected for a disk image).
		// Some programs walk it for a disk to hold its backup file and reported "no unlocked disk inserted" while the queue was empty.
		int block=memory.allocate(4+18,4);memory.fill(block,22,0);int element=block+4;
		memory.writeByte(block,locked?0x80:0);memory.writeByte(block+1,fileManager.opticalVolume(i)?2:8); // flags: write-protected; removable optical media or fixed disk
		memory.writeWord(element+4,1);                               // qType 1: dQDrvSz2 holds the high word of the size
		memory.writeWord(element+6,fileManager.driveOf(i));          // dQDrive (vcbDrvNum)
		memory.writeWord(element+8,fileManager.driverRefOf(i));     // dQRefNum: fixed disk or CD driver, as in the VCB
		memory.writeWord(element+10,0);                              // dQFSID: the File Manager's own
		long sectors=Math.min(0x7fffffffL,total/512);
		memory.writeWord(element+12,(int)(sectors&0xffff));memory.writeWord(element+14,(int)(sectors>>>16)); // dQDrvSz, dQDrvSz2
		int driveTail=memory.readLong(0x30e);
		if(driveTail!=0)memory.writeLong(driveTail,element);else memory.writeLong(0x30a,element);
		memory.writeLong(0x30e,element);
	}

	/** Drives the program added with _AddDrive: drive number -> its drive queue element. */
	private final Map<Integer,Integer> addedDrives=new HashMap<Integer,Integer>();
	/** The locked copies of the volumes mounted from added drives (released when the program quits). */
	private final List<DiskImages.Mounted> guestCopies=new ArrayList<DiskImages.Mounted>();
	/** The raw image file behind an added drive (its data fork is the whole disk), when the program had one open at _AddDrive. */
	private final Map<Integer,File> addedDriveFiles=new HashMap<Integer,File>();
	/**
	 * _AddDrive ($A04E): D0 = drive number in the high word, driver refNum in the low word, A0 = the program's drive queue
	 * element, which goes at the tail of the drive queue. A disk-image utility adds a drive for its own driver this way and
	 * then mounts it ({@link #mountAddedDrive}).
	 */
	boolean addDrive(M68kCpu cpu) throws M68kCpuException {
		int element=cpu.a[0],drive=cpu.d[0]>>>16,refNum=(short)cpu.d[0];
		memory.writeLong(element,0);memory.writeWord(element+6,drive);memory.writeWord(element+8,refNum); // qLink, dQDrive, dQRefNum
		int tail=memory.readLong(0x30e);
		if(tail!=0)memory.writeLong(tail,element);else memory.writeLong(0x30a,element);
		memory.writeLong(0x30e,element);
		addedDrives.put(drive,element);
		// a driver that keeps a raw disk image in memory (Disk Copy's .HDI when it cannot reach the file's disk blocks) has the
		// image file open: its data fork is the disk, and what the VM itself writes to the drive goes there too (initializeAddedDrive)
		long bytes=driveBlocks(element)*512;File backing=bytes>0?fileManager.lastOpenDataForkOfLength(bytes):null;
		if(backing!=null)addedDriveFiles.put(drive,backing);else addedDriveFiles.remove(drive);
		if(Mac68kApi.TRACE)System.err.println("[68K] AddDrive "+drive+" driver "+refNum+" element $"+Integer.toHexString(element)+" size "+driveBlocks(element)+" blocks"+(backing==null?"":", image file "+backing.getName()));
		cpu.d[0]=0;
		return true;
	}
	/** The size of a drive in 512-byte blocks from its queue element (dQDrvSz, and dQDrvSz2 for qType 1), 0 when not given. */
	private long driveBlocks(int element) throws M68kCpuException {
		long blocks=memory.readWord(element+12)&0xffff;
		if(memory.readWord(element+4)==1)blocks|=(long)(memory.readWord(element+14)&0xffff)<<16;
		return blocks;
	}
	/**
	 * The program is quitting - for its drives the end of the system: each drive it added gets the eject call (Control csCode 7)
	 * the Finder or shutdown would send. Disk Copy's image driver keeps a mounted image in memory and writes it back to the image
	 * file then; a new image stayed all zeros otherwise. Errors are ignored.
	 */
	void ejectAddedDrives(M68kCpu cpu){
		if(addedDrives.isEmpty())return;
		int pb;try{pb=memory.allocate(80,4);}catch(M68kCpuException e){return;}
		try{
			for(Map.Entry<Integer,Integer> e:new java.util.TreeMap<Integer,Integer>(addedDrives).entrySet()){
				try{
					int refNum=(short)memory.readWord(e.getValue()+8);
					memory.fill(pb,80,0);memory.writeWord(pb+22,e.getKey());memory.writeWord(pb+26,7); // ioVRefNum = drive, csCode ejectCode
					int result=tb.devices.call(cpu,refNum,DeviceManager68k.CONTROL,pb,0xa004);
					if(Mac68kApi.TRACE)System.err.println("[68K] quit: eject drive "+e.getKey()+" (driver "+refNum+") -> "+result);
				}catch(M68kException|RuntimeException ignored){}
			}
			addedDrives.clear();
		}finally{memory.free(pb);}
		for(DiskImages.Mounted copy:guestCopies)DiskImages.release(copy); // deleted once the program has ended
		guestCopies.clear();
	}
	/** Whether {@code drive} is one the program added with _AddDrive. */
	boolean isAddedDrive(int drive){return addedDrives.containsKey(drive);}
	/**
	 * Mounts a drive the program added: reads the whole disk through the program's driver (Read calls from block 0, as the
	 * File Manager would read an HFS volume), copies its volume to a locked folder and mounts that: a read-only volume whose
	 * VCB names the program's drive and driver. Returns {result, vRefNum (0 when not mounted)}: volOnLinErr with the
	 * vRefNum when it is mounted already, noMacDskErr when the disk holds no volume (a new, zeroed disk image).
	 */
	int[] mountAddedDrive(M68kCpu cpu,int drive) throws M68kException {
		for(Map.Entry<Integer,int[]> e:fileManager.guestDrives.entrySet())
			if(e.getValue()[0]==drive&&e.getKey()<fileManager.volumes.size())return new int[]{-55,-(e.getKey()+1)}; // volOnLinErr
		int element=addedDrives.get(drive),refNum=(short)memory.readWord(element+8);
		long blocks=driveBlocks(element);
		long limit=blocks>0?blocks*512:0x7fffffffL;
		final int chunk=64*1024;
		java.io.ByteArrayOutputStream disk=new java.io.ByteArrayOutputStream((int)Math.min(limit,64L<<20));
		int buffer=memory.allocate(chunk,4),request=memory.allocate(80,4);
		int result=0;
		try{
			for(long at=0;at<limit;at+=chunk){
				int n=(int)Math.min(chunk,limit-at);
				result=driveIo(cpu,drive,refNum,request,buffer,at,n,false);
				int actual=memory.readLong(request+40);
				if(result!=0||actual<=0){if(blocks==0&&at>0)result=0;break;} // a drive of unknown size: read until it ends
				disk.write(memory.readBytes(buffer,Math.min(actual,n)),0,Math.min(actual,n));
				if(actual<n)break;
			}
		}finally{memory.free(buffer);memory.free(request);}
		if(Mac68kApi.TRACE)System.err.println("[68K] MountVol drive "+drive+": read "+disk.size()+" bytes through driver "+refNum+" -> "+result);
		if(result!=0)return new int[]{result,0};
		DiskImages.Mounted copy;
		try{copy=DiskImages.extractDrive("drive "+drive,new DiskImage.BytesSource(disk.toByteArray()));}
		catch(IOException e){if(Mac68kApi.TRACE)System.err.println("[68K] MountVol drive "+drive+": "+e.getMessage());return new int[]{-57,0};} // noMacDskErr
		int index=fileManager.mountVolume(copy.folder,copy.name,true);
		if(index<0){DiskImages.release(copy);return new int[]{-36,0};}
		fileManager.guestDrives.put(index,new int[]{drive,refNum});guestCopies.add(copy);
		appendVolumeControlBlock(index);
		if(memory.readLong(0x352)==0)memory.writeLong(0x352,volumeControlBlocks[0]);
		if(Mac68kApi.TRACE)System.err.println("[68K] MountVol drive "+drive+": \""+copy.name+"\" ("+copy.description+", "+copy.files+" files) as volume "+(-(index+1)));
		return new int[]{0,-(index+1)};
	}
	/** One Read or Write of {@code count} bytes at {@code position} on an added drive, through its driver; {@code request} is an 80-byte block. */
	private int driveIo(M68kCpu cpu,int drive,int refNum,int request,int buffer,long position,int count,boolean write) throws M68kException {
		memory.fill(request,80,0);
		memory.writeWord(request+22,drive);memory.writeLong(request+32,buffer);memory.writeLong(request+36,count); // ioVRefNum, ioBuffer, ioReqCount
		memory.writeWord(request+44,1);memory.writeLong(request+46,(int)position);                                 // ioPosMode fsFromStart, ioPosOffset
		return tb.devices.call(cpu,refNum,DeviceManager68k.PRIME,request,write?0xa003:0xa002);
	}
	/**
	 * Rename/HRename of the root of a volume on a drive the program added (Disk Copy names a new image's volume after the
	 * file: FSpRename of (vRefNum, fsRtParID, "untitled")). The volume here is a locked copy; an empty one - just initialized -
	 * is initialized again under the new name through the driver and renamed; one with files stays as it is (wPrErr).
	 * False when the call is not for such a volume.
	 */
	private boolean renameAddedVolume(M68kCpu cpu,boolean hierarchical,boolean async) throws M68kException {
		int pb=cpu.a[0],vRef=(short)memory.readWord(pb+22),dirId=hierarchical?memory.readLong(pb+48):0,namePtr=memory.readLong(pb+18);
		File target;try{target=fileForNameBytes(vRef,dirId,namePtr);}catch(IOException e){return false;}
		if(target==null||!fileManager.volumeRoot(target))return false;
		int index;try{index=fileManager.volumeIndexOf(target);}catch(IOException e){return false;}
		int[] drive=fileManager.guestDrives.get(index);if(drive==null)return false;
		int newName=memory.readLong(pb+28);
		byte[] name=newName==0?new byte[0]:java.util.Arrays.copyOfRange(tb.readPascalBytes(newName),1,1+memory.readByte(newName));
		if(name.length>0&&name[name.length-1]==':')name=java.util.Arrays.copyOf(name,name.length-1);
		boolean colon=false;for(byte b:name)if(b==':')colon=true;
		int result;
		if(name.length==0||name.length>27||colon)result=-37; // bdNamErr
		else{
			String[] entries=target.list((d,n)->!n.startsWith("."));
			if(entries!=null&&entries.length>0)result=-44; // wPrErr: a volume with files keeps its name (the copy is locked)
			else if((result=initializeAddedDrive(cpu,drive[0],name))==0){
				fileManager.volumeNames.set(index,tb.decode(name));
				if(index<volumeControlBlocks.length){memory.fill(volumeControlBlocks[index]+44,28,0);tb.writePascal(volumeControlBlocks[index]+44,tb.decode(name),27);}
			}
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] Rename volume \""+fileManager.volumeNames.get(index)+"\" on drive "+drive[0]+" to \""+tb.decode(name)+"\" -> "+result);
		finish(cpu,pb,result,async);return true;
	}

	/**
	 * DIZero on a drive the program added: an empty HFS volume named {@code name} ({@link HfsFormat}) written through the
	 * program's driver - the structures only, as DIZero does. Returns the result of the writes (paramErr for a disk too small).
	 */
	int initializeAddedDrive(M68kCpu cpu,int drive,byte[] name) throws M68kException {
		int element=addedDrives.get(drive),refNum=(short)memory.readWord(element+8);
		long blocks=driveBlocks(element);
		HfsFormat format;
		try{format=new HfsFormat(blocks,name,FileManager68k.macDate(System.currentTimeMillis()));}catch(IllegalArgumentException e){return -50;}
		int buffer=memory.allocate(HfsFormat.SECTOR*64,4),request=memory.allocate(80,4);
		try{
			// runs of consecutive sectors in one Write each
			for(int i=0;i<format.sectors.size();){
				long first=(Long)format.sectors.get(i)[0];int n=0;
				while(i+n<format.sectors.size()&&n<64&&(Long)format.sectors.get(i+n)[0]==first+n){memory.load(buffer+n*HfsFormat.SECTOR,(byte[])format.sectors.get(i+n)[1]);n++;}
				int result=driveIo(cpu,drive,refNum,request,buffer,first*HfsFormat.SECTOR,n*HfsFormat.SECTOR,true);
				if(result!=0)return result;
				i+=n;
			}
		}finally{memory.free(buffer);memory.free(request);}
		File backing=addedDriveFiles.get(drive);
		if(backing!=null&&backing.length()==blocks*512&&Sandbox.allowFile(backing,Sandbox.Access.WRITE,tb.name)){ // the image file gets the same sectors
			try(java.io.RandomAccessFile out=new java.io.RandomAccessFile(backing,"rw")){for(Object[] s:format.sectors){out.seek((Long)s[0]*HfsFormat.SECTOR);out.write((byte[])s[1]);}}
			catch(IOException e){if(Mac68kApi.TRACE)System.err.println("[68K] DIZero drive "+drive+": "+backing+": "+e.getMessage());}
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] DIZero drive "+drive+": \""+tb.decode(name)+"\", "+format.allocationBlocks+" allocation blocks of "+format.allocationBlockSize+(backing==null?"":" (also "+backing.getName()+")"));
		return 0;
	}

	/**
	 * A disk image mounted while the program runs (on the emulation thread, at an event call): one more locked volume, its VCB
	 * and drive, and a disk-inserted event (message: the drive number, result noErr in the high word) as the Disk Driver
	 * posts when a disk goes in; the Finder puts the disk on its desktop.
	 */
	void mountImage(DiskImages.Mounted image){
		if(image==null||!image.folder.isDirectory())return;
		int before=fileManager.volumes.size();
		for(String n:fileManager.volumeNames)if(n.equalsIgnoreCase(image.name))return;
		int index=fileManager.mountVolume(image.folder,image.name,true);
		if(index<before)return;
		if(tb.app==null)return;
		int drive=fileManager.assignImageDrive(index);
		try{appendVolumeControlBlock(index);if(memory.readLong(0x352)==0)memory.writeLong(0x352,volumeControlBlocks[0]);}catch(M68kCpuException e){return;}
		tb.events.postApplicationEvent(EventManager68k.DISK_EVT,drive);
	}

	/** GetAliasInfo: the names recorded in an alias (volume, file, parent folder and the folders above it). */
	String aliasInfo(byte[] alias,int index){
		if(alias==null||alias.length<0x96)return "";
		if(index==-1)return tb.encoding.decode(java.util.Arrays.copyOfRange(alias,11,11+Math.min(27,alias[10]&0xff)));
		if(index<-1)return "";
		if(index==0)return tb.encoding.decode(java.util.Arrays.copyOfRange(alias,0x33,0x33+Math.min(63,alias[0x32]&0xff)));
		File f=resolveAlias(alias);
		if(f!=null){File p=f.getAbsoluteFile();for(int i=0;i<index&&p!=null;i++)p=p.getParentFile();
			if(p==null||fileManager.volumeRoot(p))return "";try{return fileManager.macNameOf(p);}catch(IOException e){return p.getName();}}
		if(index==1){for(int at=0x96;at+4<=alias.length;){int type=(short)MacApp68k.u2(alias,at),length=MacApp68k.u2(alias,at+2);at+=4;if(type==-1||at+length>alias.length)break;
			if(type==0&&length>0)return tb.encoding.decode(java.util.Arrays.copyOfRange(alias,at+1,at+1+Math.min(length-1,alias[at]&0xff)));at+=length+(length&1);}}
		return "";
	}

	/** A Macintosh full pathname ("Volume:Folder:File") in the names a program sees (no trailing colon). */
	String macFullPath(File file) throws IOException {
		List<String> parts=new ArrayList<String>();
		for(File f=file.getAbsoluteFile();f!=null;f=f.getParentFile()){
			if(fileManager.volumeRoot(f)){parts.add(0,fileManager.volumeNameOf(f));return String.join(":",parts);}
			parts.add(0,fileManager.macNameOf(f));
		}
		return classicPath(file);
	}
	/** A Finder-style path (Volume:Folder:File) for display. */
	String classicPath(File file){
		try{StringBuilder sb=new StringBuilder();File f=file.getAbsoluteFile();List<String> parts=new ArrayList<String>();
			while(f!=null&&fileManager.volumeRefNumFor(f)!=-1||f!=null){parts.add(0,f.getName().isEmpty()?"":f.getName());File p=f.getParentFile();if(p==null)break;f=p;if(fileManager.volumeRefNumFor(f)!=-1&&f.getParentFile()==null)break;}
			for(String part:parts){if(part.isEmpty())continue;sb.append(part).append(':');}
			return sb.length()>0?sb.substring(0,sb.length()-1):file.getName();
		}catch(IOException e){return file.getName();}
	}
}

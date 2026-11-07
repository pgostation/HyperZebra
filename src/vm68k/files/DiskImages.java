import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Disk images mounted for Mac programs. Mounting reads the image ({@link DiskImage}) and writes its volume, both forks and
 * the Finder information of every file, into a hidden cache folder in the state directory; that folder then becomes a
 * locked (read-only) volume of every 68K/PowerPC program launched afterwards, and of those already running (they get a
 * disk-inserted event). Nothing is ever written back to the image. The extracted copy lives only while it is needed: it is
 * deleted when the disk is unmounted (or, when a running program still has it, once that program quits), when HyperZebra
 * quits, and - for copies a crash left behind - at the next start. "Extract" writes the same tree into a folder the user
 * chooses, as ordinary writable files.
 */
final class DiskImages {
	private DiskImages(){}

	/** A mounted image: its volume name as programs see it and the host folder that holds the volume. */
	static final class Mounted {
		final File image,folder;final String name,description;final int files;
		/** Size of the volume in bytes (a floppy is 1.44 MB, a CD 600 MB): what the File Manager reports as its capacity. */
		final long capacity;
		/** Optical images need a CD driver identity even when their HFS contents look like a hard disk. */
		final boolean optical;
		Mounted(File image,File folder,String name,String description,int files,long capacity){this.image=image;this.folder=folder;this.name=name;this.description=description;this.files=files;this.capacity=capacity;
			String leaf=image.getName().toLowerCase(Locale.ROOT);
			optical=leaf.endsWith(".toast")||leaf.endsWith(".iso")||leaf.endsWith(".cdr")||leaf.endsWith(".cue")||description.startsWith("CD (")||description.contains("ISO 9660");}
		@Override public String toString(){return name;}
	}

	/** Progress of a long extraction: files done out of all, the name at hand. */
	interface Progress{void update(int done,int total,String name);}

	private static final List<Mounted> mounted=Collections.synchronizedList(new ArrayList<Mounted>());
	/** The extracted files of locked volumes whose entry carried {@link DiskImage.Entry#attributes}: canonical path -> bits. */
	private static final java.util.Map<String,Integer> fileAttributes=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
	/** The ioFlAttrib bits besides the lock and the open forks that {@code file} of a mounted image has on its volume (0 for any other file). */
	static int attributesOf(File file){if(fileAttributes.isEmpty()||file==null)return 0;Integer bits=fileAttributes.get(canonical(file).getPath());return bits==null?0:bits;}
	/** The file numbers (catalog node IDs) the extracted files of mounted images had on their volume: canonical path -> ID. */
	private static final java.util.Map<String,Integer> fileIds=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
	/** The file number {@code file} of a mounted image has on its volume, or 0 (any other file): a master disk is told from a
	 *  copy by it - Autoscore's installer keeps its own file number, parent ID and the volume's date ("not a valid copy of your
	 *  Autoscore master disk" with the session's numbers). */
	static int fileIdOf(File file){if(fileIds.isEmpty()||file==null)return 0;Integer id=fileIds.get(canonical(file).getPath());return id==null?0:id;}
	private static boolean purged;

	/** The images mounted now, in mounting order. */
	static List<Mounted> mounted(){synchronized(mounted){return new ArrayList<Mounted>(mounted);}}
	/** The mounted image read from {@code image}, or null. */
	static Mounted mountedFrom(File image){
		File a=canonical(image);
		for(Mounted m:mounted())if(canonical(m.image).equals(a))return m;
		return null;
	}

	/** Whether {@code file} is a disk image that can be mounted (by its contents, whatever its name). */
	static boolean recognizes(File file){return DiskImage.recognizes(file);}
	/** A disk-image kind that is recognised but not read yet, or null. */
	static String unsupportedKind(File file){return DiskImage.unsupportedKind(file);}

	/**
	 * Mounts {@code image} (or answers the mount there already is). Slow for big images: call it away from the event thread.
	 * Running programs get the volume at their next event call.
	 */
	static Mounted mount(File image,Progress progress) throws IOException {
		Mounted existing=mountedFrom(image);if(existing!=null)return existing;
		File root=new File(cacheRoot(),Long.toString(ProcessHandle.current().pid()));
		synchronized(DiskImages.class){if(!purged){purged=true;purgeStale();}}
		installCleanup();
		try(DiskImage disk=DiskImage.open(image)){
			StackTextEncoding encoding=encodingOf(disk.volume);
			String name=uniqueVolumeName(volumeName(disk.volume,encoding));
			File slot;synchronized(DiskImages.class){root.mkdirs();int n=1;do{slot=new File(root,"disk"+n++);}while(slot.exists());slot.mkdirs();}
			File folder=new File(slot,hostName(name));
			try{extract(disk.volume,encoding,folder,progress,true);}
			catch(IOException|RuntimeException e){deleteTree(slot);throw e;}
			Mounted m=new Mounted(image,folder,name,disk.container+" / "+disk.volume.kind,disk.volume.root.fileCount(),disk.source.length());
			mounted.add(m);
			for(MacApp68k app:MacApp68k.runningApplications()){
				final Toolbox68k tb=app.toolbox;if(tb==null)continue;
				tb.laterAtEvent(new Runnable(){@Override public void run(){tb.files.mountImage(m);}});
			}
			return m;
		}
	}

	/**
	 * Copies the volume on a disk a program reads through its own driver (Disk Copy's image driver: _AddDrive then
	 * MountVol) to a locked folder, for that program alone - it is not in {@link #mounted()}, so neither the launcher nor
	 * other programs see it. {@link #release} gives it up; quitting HyperZebra removes it like the other copies.
	 */
	static Mounted extractDrive(String label,DiskImage.Source disk) throws IOException {
		File root=processFolder();
		synchronized(DiskImages.class){if(!purged){purged=true;purgeStale();}}
		installCleanup();
		try(DiskImage image=DiskImage.ofDisk(label,disk)){
			StackTextEncoding encoding=encodingOf(image.volume);
			String name=volumeName(image.volume,encoding);
			File slot;synchronized(DiskImages.class){root.mkdirs();int n=1;do{slot=new File(root,"drive"+n++);}while(slot.exists());slot.mkdirs();}
			File folder=new File(slot,hostName(name));
			try{extract(image.volume,encoding,folder,null,true);}
			catch(IOException|RuntimeException e){deleteTree(slot);throw e;}
			return new Mounted(new File(slot,label),folder,name,image.container+" / "+image.volume.kind,image.volume.root.fileCount(),disk.length());
		}
	}
	/** An empty, writable folder for a volume a program has just zeroed on its own drive (DIZero on its RAM disk); {@link #release} as for {@link #extractDrive}. */
	static Mounted emptyDrive(String label,String name,long capacity){
		File root=processFolder();
		installCleanup();
		File slot;synchronized(DiskImages.class){root.mkdirs();int n=1;do{slot=new File(root,"drive"+n++);}while(slot.exists());slot.mkdirs();}
		File folder=new File(slot,hostName(name));folder.mkdirs();
		return new Mounted(new File(slot,label),folder,name,"zeroed drive",0,capacity);
	}
	/** Gives up a copy made by {@link #extractDrive}: deleted once no running program has it as a volume. */
	static void release(Mounted m){
		synchronized(released){released.add(m);}
		deleteReleased();
	}

	/**
	 * Takes a disk away from programs launched from now on. Programs running now keep seeing it until they quit (a real Mac
	 * would ask them to let go first); its cache folder is removed the next time HyperZebra mounts an image after a restart.
	 */
	static void unmount(Mounted m){
		mounted.remove(m);
		synchronized(released){released.add(m);}
		deleteReleased();
	}
	/** Unmounts every disk; their copies go now or when the programs using them quit. */
	static void unmountAll(){for(Mounted m:mounted())unmount(m);}

	/** Unmounted disks whose copies are not deleted yet (a running program still has the volume). */
	private static final List<Mounted> released=new ArrayList<Mounted>();
	private static boolean cleanupInstalled;
	/** Deletes the copies of unmounted disks no running program uses any more. */
	static void deleteReleased(){
		List<Mounted> now;synchronized(released){now=new ArrayList<Mounted>(released);}
		for(Mounted m:now){
			if(inUse(m))continue;
			deleteTree(m.folder.getParentFile());
			synchronized(released){released.remove(m);}
		}
	}
	/** Whether a running program has the disk among its volumes. */
	static boolean inUse(Mounted m){
		File folder=canonical(m.folder);
		for(MacApp68k app:MacApp68k.runningApplications()){
			if(app.finished)continue;
			Toolbox68k tb=app.toolbox;if(tb==null||tb.files==null)continue;
			for(File v:new ArrayList<File>(tb.files.fileManager.volumes))if(canonical(v).equals(folder))return true;
		}
		return false;
	}
	/** Program ends retry the pending deletions; quitting HyperZebra removes this process's copies altogether. */
	private static synchronized void installCleanup(){
		if(cleanupInstalled)return;
		cleanupInstalled=true;
		MacApp68k.addListener(new MacApp68k.Listener(){
			@Override public void applicationLaunched(MacApp68k app){}
			@Override public void applicationFinished(MacApp68k app){Thread t=new Thread(DiskImages::deleteReleased,"Disk image cleanup");t.setDaemon(true);t.start();}
		});
		Runtime.getRuntime().addShutdownHook(new Thread(()->deleteTree(processFolder()),"Disk image cleanup at quit"));
	}
	private static File processFolder(){return new File(cacheRoot(),Long.toString(ProcessHandle.current().pid()));}

	/**
	 * At start-up (in the background): copies left by a HyperZebra that did not quit normally. Cheap when there are none.
	 */
	static void purgeStaleInBackground(){
		if(!new File(AppPaths.stateDirectory(),".Disk Images").isDirectory())return;
		Thread t=new Thread(()->{synchronized(DiskImages.class){if(!purged){purged=true;purgeStale();}}},"Disk image cleanup at start");
		t.setDaemon(true);t.start();
	}
	/** Bytes the extracted copies take now (all of them: mounted, waiting for a program to quit, left over). */
	static long extractedBytes(){return sizeOf(cacheRoot());}
	static long sizeOf(File f){
		if(!f.exists())return 0;
		if(f.isFile()){long n=f.length();if(ResourceFile68k.nativeForks())n+=ResourceFile68k.nativeForkLength(f);return n;}
		long n=0;File[] children=f.listFiles();if(children!=null)for(File c:children)if(!Files.isSymbolicLink(c.toPath()))n+=sizeOf(c);return n;
	}
	/**
	 * Unmounts every disk and deletes every extracted copy that nothing uses: this process's, and any another HyperZebra left.
	 * Copies of running programs' disks follow when they quit. Returns how many bytes are still kept for running programs.
	 */
	static long deleteAllExtracted(){
		unmountAll();
		synchronized(DiskImages.class){purgeStale();}
		deleteReleased();
		return extractedBytes();
	}

	/** Writes the volume in {@code image} into a new folder inside {@code parent} (named after the volume); returns that folder. */
	static File extractTo(File image,File parent,Progress progress) throws IOException {
		try(DiskImage disk=DiskImage.open(image)){
			StackTextEncoding encoding=encodingOf(disk.volume);
			String name=hostName(volumeName(disk.volume,encoding));if(name.isEmpty())name="Disk";
			File folder=new File(parent,name);for(int i=2;folder.exists();i++)folder=new File(parent,name+" "+i);
			extract(disk.volume,encoding,folder,progress,false);
			return folder;
		}
	}

	/**
	 * Removes the mounted volumes of HyperZebra processes that are gone (one folder per process id, so a second HyperZebra - or
	 * a test run - never takes a disk away from one that is running).
	 */
	private static void purgeStale(){
		File[] slots=cacheRoot().listFiles();if(slots==null)return;
		long self=ProcessHandle.current().pid();
		for(File slot:slots){
			long pid;try{pid=Long.parseLong(slot.getName());}catch(NumberFormatException e){deleteTree(slot);continue;}
			if(pid==self){ // this process: only what no mounted or still-used disk lives in
				File[] disks=slot.listFiles();if(disks==null)continue;
				for(File disk:disks)if(!inUseHere(disk))deleteTree(disk);
				continue;
			}
			if(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))deleteTree(slot);
		}
	}

	/** Whether a "diskN" folder of this process holds a mounted disk or one a running program still uses. */
	private static boolean inUseHere(File disk){
		File d=canonical(disk);
		for(Mounted m:mounted())if(canonical(m.folder.getParentFile()).equals(d))return true;
		synchronized(released){for(Mounted m:released)if(canonical(m.folder.getParentFile()).equals(d))return true;}
		return false;
	}

	/** Where mounted volumes live: hidden in the state directory (programs and the File Manager skip dot folders). */
	static File cacheRoot(){return new File(AppPaths.stateDirectory(),".Disk Images");}

	// ------------------------------------------------------------------ names

	/**
	 * The encoding of a volume's names: Japanese when more of its names read as Shift-JIS with two-byte characters than there
	 * are names that are neither Shift-JIS nor UTF-8, else Mac OS Roman. ("QuickTime™" is Roman: $AA alone is a half-width kana
	 * in Shift-JIS.) Each name is still read on its own by {@link #decodeName}: a Japanese system disk holds English programs'
	 * names and names copied from Mac OS X as UTF-8 bytes.
	 */
	static StackTextEncoding encodingOf(DiskImage.Volume volume){
		List<byte[]> names=new ArrayList<byte[]>();names.add(volume.name);collectNames(volume.root,names);
		int japanese=0,other=0;
		for(byte[] n:names){
			boolean high=false;for(byte b:n)if((b&0x80)!=0){high=true;break;}
			if(!high)continue;
			if(readsAsJapanese(n)){if(hasDoubleByte(n))japanese++;}
			else if(!readsAsUtf8(n))other++;
		}
		return japanese>0&&japanese>=other?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN;
	}
	/** A name in the volume's encoding; on a Japanese volume a name that is not Shift-JIS is UTF-8 (cut short at 31 bytes) or Roman. */
	static String decodeName(byte[] name,StackTextEncoding encoding){
		if(encoding!=StackTextEncoding.JAPANESE||readsAsJapanese(name))return encoding.decode(name);
		if(readsAsUtf8(name))return utf8(name);
		return StackTextEncoding.ROMAN.decode(name);
	}
	/** Whether a name holds UTF-8 multi-byte characters only (the last one may be cut off by the 31-byte limit). */
	private static boolean readsAsUtf8(byte[] n){
		boolean multi=false;
		for(int i=0;i<n.length;){
			int b=n[i]&0xff,extra=b<0x80?0:b>=0xc2&&b<=0xdf?1:b>=0xe0&&b<=0xef?2:b>=0xf0&&b<=0xf4?3:-1;
			if(extra<0)return false;
			if(extra>0)multi=true;
			for(int k=1;k<=extra;k++){if(i+k>=n.length)return multi;if((n[i+k]&0xc0)!=0x80)return false;}
			i+=extra+1;
		}
		return multi;
	}
	private static String utf8(byte[] n){
		int end=n.length;
		for(int i=0;i<n.length;){int b=n[i]&0xff,extra=b<0x80?0:b<0xe0?1:b<0xf0?2:3;if(i+extra>=n.length){end=i;break;}i+=extra+1;}
		return new String(n,0,end,java.nio.charset.StandardCharsets.UTF_8);
	}
	private static void collectNames(DiskImage.Entry e,List<byte[]> out){for(DiskImage.Entry c:e.children){if(c.unicodeName==null)out.add(c.name);collectNames(c,out);}}
	/** The volume's name: Unicode on HFS Plus, else its bytes in the volume's encoding. */
	static String volumeName(DiskImage.Volume volume,StackTextEncoding encoding){return volume.unicodeName!=null?volume.unicodeName:decodeName(volume.name,encoding);}
	private static boolean readsAsJapanese(byte[] n){
		for(int i=0;i<n.length;i++){
			int b=n[i]&0xff;
			if(lead(b)){if(i+1>=n.length)return false;int t=n[i+1]&0xff;if(t<0x40||t==0x7f||t>0xfc)return false;i++;}
			else if(b>=0xfd)return false;
		}
		try{String s=StackTextEncoding.JAPANESE.decode(n);return s.indexOf('�')<0;}catch(RuntimeException e){return false;}
	}
	private static boolean hasDoubleByte(byte[] n){for(int i=0;i+1<n.length;i++)if(lead(n[i]&0xff))return true;return false;}
	private static boolean lead(int b){return b>=0x81&&b<=0x9f||b>=0xe0&&b<=0xfc;}

	/**
	 * The host name of a Mac name: '/' becomes ':' (the macOS convention, read back by FileManager68k.programName), and on
	 * Windows the characters it forbids are escaped the way the File Manager escapes them.
	 */
	static String hostName(String macName){
		String n=macName.replace('\0',' ').replace('/',':');
		if(n.equals(".")||n.equals(".."))n=n.replace('.','_');
		return FileManager68k.hostFileName(n);
	}

	/** A volume name no mounted disk (host disk or image) uses yet. */
	private static String uniqueVolumeName(String name){
		if(name==null||name.isEmpty())name="Untitled";
		List<String> taken=new ArrayList<String>();
		taken.add(FileSystem68k.BOOT_VOLUME_NAME.toLowerCase(Locale.ROOT));taken.add("macintosh hd");taken.add("home");
		File[] volumes=new File("/Volumes").listFiles();if(volumes!=null)for(File v:volumes)taken.add(v.getName().toLowerCase(Locale.ROOT));
		for(Mounted m:mounted())taken.add(m.name.toLowerCase(Locale.ROOT));
		String candidate=name;
		for(int i=2;taken.contains(candidate.toLowerCase(Locale.ROOT));i++){String suffix=" "+i;candidate=(name.length()+suffix.length()>27?name.substring(0,27-suffix.length()):name)+suffix;}
		return candidate;
	}

	// ------------------------------------------------------------------ writing the tree

	private static void extract(DiskImage.Volume volume,StackTextEncoding encoding,File folder,Progress progress,boolean readOnly) throws IOException {
		if(!folder.mkdirs()&&!folder.isDirectory())throw new IOException("Can't create the folder "+folder.getPath());
		int[] done={0};int total=volume.root.fileCount();
		writeChildren(volume,volume.root,encoding,folder,progress,done,total,readOnly);
		if(volume.root.finderInfo[8]!=0||volume.root.finderInfo[9]!=0)FinderInfo68k.write(folder,volume.root.finderInfo);
		setDates(folder,volume.created!=0?volume.created:volume.root.created,volume.root.modified!=0?volume.root.modified:volume.modified);
	}
	private static void writeChildren(DiskImage.Volume volume,DiskImage.Entry parent,StackTextEncoding encoding,File dir,Progress progress,int[] done,int total,boolean readOnly) throws IOException {
		for(DiskImage.Entry e:parent.children){
			String name=e.unicodeName!=null?e.unicodeName:decodeName(e.name,encoding);
			File target=new File(dir,hostName(name));
			for(int i=2;target.exists();i++)target=new File(dir,hostName(name)+" #"+i); // two names one host folds together
			if(e.folder){
				if(!target.mkdir())throw new IOException("Can't create the folder "+target.getPath());
				writeChildren(volume,e,encoding,target,progress,done,total,readOnly);
				if(!isZero(e.finderInfo))FinderInfo68k.write(target,e.finderInfo);
				setDates(target,e.created,e.modified);
				continue;
			}
			if(progress!=null)progress.update(done[0],total,name);
			try(OutputStream out=new BufferedOutputStream(new FileOutputStream(target),1<<16)){volume.copyFork(e,false,out);}
			byte[] fork=e.resourceLength>0?volume.readFork(e,true):null;
			writeMacInfo(target,fork,e.finderInfo);
			setDates(target,e.created,e.modified);
			if(readOnly)target.setWritable(false,false);
			if(readOnly&&e.attributes!=0)fileAttributes.put(canonical(target).getPath(),e.attributes);
			if(e.id>0)fileIds.put(canonical(target).getPath(),e.id);
			done[0]++;
		}
	}
	/** A file's resource fork and Finder info: natively on macOS, else in its AppleDouble "._" sidecar. */
	private static void writeMacInfo(File file,byte[] fork,byte[] finderInfo) throws IOException {
		boolean info=!isZero(finderInfo);
		if(ResourceFile68k.nativeForks()){
			if(fork!=null&&fork.length>0)try(FileOutputStream out=new FileOutputStream(ResourceFile68k.nativeForkFile(file))){out.write(fork);}
			if(info)FinderInfo68k.write(file,finderInfo);
			return;
		}
		if((fork==null||fork.length==0)&&!info)return;
		byte[] fi=new byte[32];System.arraycopy(finderInfo,0,fi,0,16);
		Files.write(ResourceFile68k.AppleDouble.sidecarOf(file).toPath(),ResourceFile68k.AppleDouble.build(fork==null?new byte[0]:fork,fi));
	}
	/** Host dates from Mac dates (local seconds since 1904, the image's own: not the VM clock's shift); 0 leaves a date alone. */
	private static void setDates(File file,int created,int modified){
		try{
			if(modified!=0)Files.setLastModifiedTime(file.toPath(),FileTime.fromMillis(hostMillis(modified)));
			if(created!=0)Files.setAttribute(file.toPath(),"basic:creationTime",FileTime.fromMillis(hostMillis(created)));
		}catch(IOException|RuntimeException ignored){}
	}
	static long hostMillis(int macDate){return (Integer.toUnsignedLong(macDate)-2_082_844_800L-Toolbox68k.localOffsetSeconds())*1000L;}
	private static boolean isZero(byte[] b){for(byte v:b)if(v!=0)return false;return true;}

	private static File canonical(File f){try{return f.getCanonicalFile();}catch(IOException e){return f.getAbsoluteFile();}}
	/** Removes a folder and everything in it (read-only files included). */
	static void deleteTree(File f){
		if(f==null||!f.exists())return;
		if(!fileAttributes.isEmpty()){String prefix=canonical(f).getPath();fileAttributes.keySet().removeIf(k->k.equals(prefix)||k.startsWith(prefix+File.separator));}
		if(!fileIds.isEmpty()){String prefix=canonical(f).getPath();fileIds.keySet().removeIf(k->k.equals(prefix)||k.startsWith(prefix+File.separator));}
		if(f.isDirectory()&&!Files.isSymbolicLink(f.toPath())){File[] children=f.listFiles();if(children!=null)for(File c:children)deleteTree(c);}
		f.setWritable(true);f.delete();
	}

	// ------------------------------------------------------------------ the user's side

	private static String text(String key){return PCARD.pc!=null&&PCARD.pc.intl!=null?PCARD.pc.intl.getDialogText(key):key;}
	private static java.awt.Frame owner(){return PCARD.pc;}

	/**
	 * A file the user opened or dropped: a disk image is mounted (in the background, progress in the launcher) and what is on
	 * it offered; a kind of image not read yet is reported. False when the file is not a disk image at all.
	 */
	static boolean openFromUser(final File file){
		if(file==null||!file.isFile())return false;
		final Mounted already=mountedFrom(file);
		if(already!=null){javax.swing.SwingUtilities.invokeLater(()->{if(!TTalk.offerMountedDisk(already.folder))showMounted(already,false);});return true;}
		if(!recognizes(file)){
			final String kind=unsupportedKind(file);
			if(kind==null)return false;
			javax.swing.SwingUtilities.invokeLater(()->new GDialog(owner(),text("This kind of disk image can't be opened yet.")+"\n\""+file.getName()+"\" ("+kind+")",null,"OK",null,null));
			return true;
		}
		final String title=text("Mounting the disk image")+" \""+file.getName()+"\"";
		GLauncher.showStatus(title+"…");
		Thread worker=new Thread(()->{
			long[] last={0};
			try{
				Mounted m=mount(file,(done,total,name)->{long now=System.currentTimeMillis();if(now-last[0]<200)return;last[0]=now;GLauncher.showStatus(title+"… "+done+"/"+total);});
				javax.swing.SwingUtilities.invokeLater(()->{GLauncher.showStatus("");if(!TTalk.offerMountedDisk(m.folder))showMounted(m,true);}); // "Where is …?" open: the disk goes there
			}catch(IOException|RuntimeException e){
				javax.swing.SwingUtilities.invokeLater(()->{GLauncher.showStatus("");new GDialog(owner(),text("Can't mount the disk image.")+"\n"+file.getName()+"\n"+e.getMessage(),null,"OK",null,null);});
			}
		},"Disk image "+file.getName());
		worker.setDaemon(true);worker.start();
		return true;
	}

	/** Tells the user a disk is mounted and offers what to do with it: show its contents, open the one program (or stack) on it. */
	private static void showMounted(Mounted m,boolean fresh){
		File program=soleItem(m.folder,true),stack=program==null?soleItem(m.folder,false):null;
		String message=(fresh?text("The disk \"%1\" was mounted (locked).").replace("%1",m.name):text("The disk \"%1\" is mounted (locked).").replace("%1",m.name))
			+"\n"+text("Mac programs see it from now on, the ones running now included.")
			+"\n"+m.files+" "+text("files")+" · "+m.description;
		String show=text("Show Contents"),open=null;
		if(program!=null)open=text("Launch %1").replace("%1",FileManager68k.displayName(program.getName()));
		else if(stack!=null)open=text("Open %1").replace("%1",FileManager68k.displayName(stack.getName()));
		new GDialog(owner(),message,null,show,open,"OK");
		String clicked=GDialog.clicked;
		if(show.equals(clicked)){if(!OStack.revealInFileManager(m.folder))new GDialog(owner(),m.folder.getPath(),null,"OK",null,null);}
		else if(open!=null&&open.equals(clicked))PCARD.openFiles(Collections.singletonList(program!=null?program:stack));
	}
	/** The single program (else the single stack) in the top folder of a disk, or in the one folder that holds everything; null when there are several or none. */
	private static File soleItem(File volume,boolean program){
		for(int depth=0;depth<2&&volume!=null;depth++){
			File[] children=FileManager68k.catalogEntries(volume);if(children==null)return null;
			File found=null;int count=0;List<File> folders=new ArrayList<File>();
			for(File c:children){
				if(c.isDirectory()){folders.add(c);continue;}
				if(isInvisible(c))continue;
				boolean match=program?MacApp68k.isApplication(c)&&!isSystemSoftware(c):OStack.looksLikeHyperCardStack(c.getPath());
				if(match){found=c;count++;}
			}
			if(count==1)return found;
			if(count>1)return null;
			List<File> visible=new ArrayList<File>();for(File f:folders)if(!isInvisible(f))visible.add(f);
			volume=visible.size()==1&&children.length-folders.size()<=3?visible.get(0):null;
		}
		return null;
	}
	private static boolean isInvisible(File f){byte[] info=FinderInfo68k.stored(f);return info!=null&&(info[8]&0x40)!=0;}
	/** The System, the Finder and other system files are programs by their resources, not what the user wants launched. */
	private static boolean isSystemSoftware(File f){
		int type=FinderInfo68k.type(f);
		String t=ResourceFile68k.typeName(type);
		return t.equals("ZSYS")||t.equals("FNDR")||t.equals("INIT")||t.equals("cdev")||t.equals("RDEV")||t.equals("PRER")||t.equals("appe")||t.equals("dfil")||t.equals("thng");
	}

	/** Fills the launcher's "Disk Images" menu: each mounted disk (show, unmount), then extraction. */
	static void fillMenu(javax.swing.JMenu menu){
		menu.removeAll();
		List<Mounted> list=mounted();
		if(list.isEmpty()){javax.swing.JMenuItem none=new javax.swing.JMenuItem(text("No disk image is mounted"));none.setEnabled(false);menu.add(none);}
		for(final Mounted m:list){
			javax.swing.JMenu disk=new javax.swing.JMenu(m.name);
			javax.swing.JMenuItem item=new javax.swing.JMenuItem(text("Show Contents"));
			item.addActionListener(e->{if(!OStack.revealInFileManager(m.folder))new GDialog(owner(),m.folder.getPath(),null,"OK",null,null);});disk.add(item);
			item=new javax.swing.JMenuItem(text("Unmount"));
			item.addActionListener(e->{boolean used=inUse(m);unmount(m);
				GLauncher.showStatus((used?text("The disk \"%1\" was unmounted. Programs running now keep it until they quit; its extracted files are deleted then."):text("The disk \"%1\" was unmounted and its extracted files deleted.")).replace("%1",m.name));});disk.add(item);
			item=new javax.swing.JMenuItem(m.image.getName());item.setEnabled(false);disk.add(item);
			menu.add(disk);
		}
		menu.addSeparator();
		javax.swing.JMenuItem delete=new javax.swing.JMenuItem(text("Delete Extracted Disk Data…"));
		delete.addActionListener(e->deleteFromUser());
		menu.add(delete);
		javax.swing.JMenuItem extract=new javax.swing.JMenuItem(text("Extract Disk Image to Folder…"));
		extract.addActionListener(e->extractFromUser());
		menu.add(extract);
	}

	/** Asks, then unmounts every disk and deletes the extracted copies (what running programs use goes when they quit). */
	static void deleteFromUser(){
		long bytes=extractedBytes();
		String size=humanSize(bytes);
		if(bytes==0&&mounted().isEmpty()){new GDialog(owner(),text("There is no extracted disk data."),null,"OK",null,null);return;}
		String delete=text("Delete");
		new GDialog(owner(),text("Unmount all disk images and delete their extracted files (%1)?").replace("%1",size)+"\n"
			+text("The image files themselves are not touched. Disks that running programs use are deleted when those programs quit."),null,text("Cancel"),null,delete);
		if(!delete.equals(GDialog.clicked))return;
		long kept=deleteAllExtracted();
		GLauncher.showStatus(kept==0?text("Deleted the extracted disk data."):text("Deleted the extracted disk data; %1 stays until the programs using it quit.").replace("%1",humanSize(kept)));
	}
	static String humanSize(long bytes){
		if(bytes<1024)return bytes+" B";
		if(bytes<1024*1024)return (bytes+1023)/1024+" KB";
		if(bytes<1024L*1024*1024)return String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0);
		return String.format(Locale.ROOT,"%.2f GB",bytes/1073741824.0);
	}

	/** Asks for a disk image and writes its contents into a new folder beside it, as ordinary (writable) files. */
	static void extractFromUser(){
		javax.swing.JFileChooser chooser=new javax.swing.JFileChooser();
		chooser.setDialogTitle(text("Extract Disk Image to Folder…"));
		chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
		FileChooserPlaces.attach(chooser);
		if(PCARD.showFileChooser(chooser,null,false)!=javax.swing.JFileChooser.APPROVE_OPTION)return;
		final File image=chooser.getSelectedFile();
		if(image==null)return;
		if(!recognizes(image)){
			String kind=unsupportedKind(image);
			new GDialog(owner(),kind!=null?text("This kind of disk image can't be opened yet.")+"\n\""+image.getName()+"\" ("+kind+")":text("This is not a disk image HyperZebra can read.")+"\n"+image.getName(),null,"OK",null,null);
			return;
		}
		final String title=text("Extracting the disk image")+" \""+image.getName()+"\"";
		GLauncher.showStatus(title+"…");
		Thread worker=new Thread(()->{
			try{
				File folder=extractTo(image,image.getAbsoluteFile().getParentFile(),(done,total,name)->GLauncher.showStatus(title+"… "+done+"/"+total));
				javax.swing.SwingUtilities.invokeLater(()->{GLauncher.showStatus("");if(!OStack.revealInFileManager(folder))new GDialog(owner(),text("The disk image was extracted to:")+"\n"+folder.getPath(),null,"OK",null,null);});
			}catch(IOException|RuntimeException e){
				javax.swing.SwingUtilities.invokeLater(()->{GLauncher.showStatus("");new GDialog(owner(),text("Can't extract the disk image.")+"\n"+e.getMessage(),null,"OK",null,null);});
			}
		},"Extract "+image.getName());
		worker.setDaemon(true);worker.start();
	}
}

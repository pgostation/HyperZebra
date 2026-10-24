import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource Manager for hosted applications: a chain of open resource files
 * (application, documents, preferences, a synthetic "System" file with the
 * standard cursors and patterns), read/write access, and the classic
 * search order (current file first, then the files opened before it).
 */
final class ResourceManager68k {
	static final int NO_ERR=0,RES_NOT_FOUND=-192,RES_FILE_NOT_FOUND=-193,ADD_RES_FAILED=-194,ADD_REF_FAILED=-195,RMV_RES_FAILED=-196,RMV_REF_FAILED=-197,RES_ATTR_ERR=-198,MAP_READ_ERR=-199,MEM_FULL_ERR=-108,EOF_ERR=-39,FNF_ERR=-43,PERM_ERR=-54,OP_WR_ERR=-49,DUP_FN_ERR=-48;
	private static final int RES_ERR_ADDRESS=0x0a60;
	/** Low-memory globals of the map chain: TopMapHndl, SysMapHndl, SysMap, CurMap. */
	private static final int TOP_MAP_HNDL=0x0a50,SYS_MAP_HNDL=0x0a54,SYS_MAP=0x0a58,CUR_MAP=0x0a5a;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Open files by refNum; iteration order is open order. */
	private final Map<Integer,ResourceFile68k> files=new LinkedHashMap<Integer,ResourceFile68k>();
	private final Map<Integer,ResourceFile68k.Resource> byHandle=new HashMap<Integer,ResourceFile68k.Resource>();
	private int current;
	private int applicationRefNum,systemRefNum;
	final ResourceFile68k system;
	private boolean loadResources=true;
	private int error;

	ResourceManager68k(Toolbox68k tb){
		this.tb=tb;memory=tb.memory;
		ResourceFile68k system=SystemResources68k.build(tb.encoding,tb.baseSystemVersion);system.writable=false;
		// Externals see a System whose Chooser was never used: the printer STR -8192 names for programs is HyperZebra's own
		// QuickDraw device, not a driver file in Extensions (and there is no 'alis' -8192 to go with it). Rinaldi's CurrPrinter
		// opened the named driver and reported "Printer driver file is missing"; ChoosePrinter could not set the 'alis'.
		if(tb.app==null){ResourceFile68k.Resource chosen=system.get(ResourceFile68k.fourcc("STR "),SystemResources68k.CHOSEN_PRINTER);if(chosen!=null)system.remove(chosen);}
		systemRefNum=open(system);this.system=system;
		if(tb.app!=null)applicationRefNum=open(tb.app.resources);
		else{ // XCMD mode: HyperCard's own resources, the stacks in use, then the current stack (searched first)
			open(HyperCardResources68k.build(tb.encoding)); // HyperCard's dialogs externals borrow (UxAnswer's DLOG 135)
			if(tb.stack!=null)for(OStack using:tb.stack.usingStacks){int ref=open(Mac68kResourceManager.forStack(using));stacksInUse.put(stackKey(using),ref);stackFolders.put(ref,folderOf(using));}
			applicationRefNum=open(Mac68kResourceManager.forStack(tb.stack));
			stackFolders.put(applicationRefNum,folderOf(tb.stack));
		}
		current=applicationRefNum;
	}

	/**
	 * XCMD mode: the VM goes on for another stack ({@link Xcmd68k#stackClosing} hands a kept VM to the stack now open, whose
	 * externals were loaded into it): that stack's resources become the current file in place of the closed stack's, whose
	 * loaded resources go as CloseResFile lets them go. Only the VM's stack used to change, the closed stack's file stayed
	 * current: Dartmouth's RInstall, run in its own stack again after Install's pop card, found no XFCN "LeafName" (-192).
	 */
	void switchStack(OStack stack) throws M68kCpuException {
		if(tb.app!=null||stack==null)return;
		int old=applicationRefNum;
		int ref=open(Mac68kResourceManager.forStack(stack));
		stackFolders.put(ref,folderOf(stack));
		applicationRefNum=ref;
		if(old!=ref&&files.containsKey(old)){stackFolders.remove(old);closeResFile(old);}
		setCurrent(ref);relinkMaps();
	}

	/** XCMD mode: the folder (.hzb) of the stack behind each stack resource file this VM has open. */
	private final Map<Integer,File> stackFolders=new java.util.HashMap<Integer,File>();
	private static File folderOf(OStack stack){
		if(stack==null)return null;File f=stack.file!=null?stack.file:stack.path!=null?new File(stack.path):null;
		return f==null?null:(f.isDirectory()?f:f.getParentFile()).getAbsoluteFile();
	}
	/** XCMD mode: the open resource file of the stack kept in {@code folder} (an external's own stack, which may be a stack in
	 *  use rather than the one this VM was made for), else the VM's stack's. */
	int refNumForStackFolder(File folder){
		if(folder!=null){File f=folder.getAbsoluteFile();for(Map.Entry<Integer,File> e:stackFolders.entrySet())if(f.equals(e.getValue())&&files.containsKey(e.getKey()))return e.getKey();}
		return applicationRefNum;
	}

	/** XCMD mode: the resource files of the stacks in use, by stack path, as this VM has them open. */
	private final Map<String,Integer> stacksInUse=new LinkedHashMap<String,Integer>();
	private static String stackKey(OStack stack){return stack.path!=null?stack.path:String.valueOf(System.identityHashCode(stack));}
	/**
	 * XCMD mode, before each call of an external whose VM lives on: HyperCard's resource chain follows "start using" and
	 * "stop using" (a used stack's resource fork is open below the current stack's for as long as it is in use). Myst Preview's
	 * slide show does start using stack "DemoPictures" and HyperTint finds its pictures there by name; the kept VM still had
	 * the chain of its first call and drew nothing.
	 */
	void syncStacksInUse() throws M68kCpuException {
		if(tb.app!=null||tb.stack==null)return;
		java.util.Set<String> wanted=new java.util.LinkedHashSet<String>();
		for(OStack using:tb.stack.usingStacks){if(using==null||using==tb.stack)continue;String key=stackKey(using);wanted.add(key);
			if(!stacksInUse.containsKey(key)){int ref=open(Mac68kResourceManager.forStack(using));belowApplication.add(ref);stacksInUse.put(key,ref);stackFolders.put(ref,folderOf(using));relinkMaps();}}
		for(java.util.Iterator<Map.Entry<String,Integer>> it=stacksInUse.entrySet().iterator();it.hasNext();){Map.Entry<String,Integer> e=it.next();
			if(!wanted.contains(e.getKey())){it.remove();closeResFile(e.getValue());}}
	}

	/** Changes the current resource file and mirrors it into CurMap ($A5A) for programs that read the global. */
	private void setCurrent(int ref) throws M68kCpuException {current=ref;if(mirrorsMaps())memory.writeWord(CUR_MAP,current);}
	int applicationRefNum(){return applicationRefNum;}
	int systemRefNum(){return systemRefNum;}
	int currentRefNum(){return current;}
	boolean isOpen(int ref){return files.containsKey(ref);}
	/** Whether {@code f} is open as a resource file (the running program's own file among them). */
	boolean isOpenFile(File f){try{File c=f.getCanonicalFile();for(ResourceFile68k r:files.values())if(r.file!=null&&r.file.getCanonicalFile().equals(c))return true;}catch(java.io.IOException ignored){}return false;}
	void useResFile(int ref) throws M68kCpuException {if(files.containsKey(ref))setCurrent(ref);}
	/** The refNum of the file a resource Handle was loaded from (HomeResFile), 0 for a non-resource Handle. */
	int homeRefNumOf(int handle){ResourceFile68k.Resource r=byHandle.get(handle);ResourceFile68k f=r==null?null:fileOf(r);return f==null?0:f.refNum;}
	/** A resource of one specific open file. */
	ResourceFile68k.Resource inFile(int ref,int type,int id){ResourceFile68k f=files.get(ref);return f==null?null:f.get(type,id);}
	/** Get1IndResource in file {@code ref} (1-based), without changing the current file: the Handle, or 0. */
	int indexedInFile(int ref,int type,int index) throws M68kCpuException {ResourceFile68k f=files.get(ref);if(f==null)return 0;List<ResourceFile68k.Resource> l=f.ofType(type);return index<1||index>l.size()?0:handleFor(l.get(index-1));}
	/** Files that stay open for the whole session (loaded extensions: their components' code lives there) and Handles that survive their file's closing (component code). */
	private final java.util.Set<Integer> pinnedFiles=new java.util.HashSet<Integer>(),pinnedHandles=new java.util.HashSet<Integer>();
	void pin(int ref){pinnedFiles.add(ref);}
	boolean isPinned(int ref){return pinnedFiles.contains(ref);}
	void pinHandle(int handle){pinnedHandles.add(handle);}
	private int open(ResourceFile68k file){int ref=tb.allocateFileReference();file.refNum=ref;files.put(ref,file);syncMap(file);relinkMaps();describe(file);return ref;}
	private void describe(ResourceFile68k f){long eof=0;if(f.file!=null&&f.file.isFile())eof=f.file.length();tb.describeFileControlBlock(f.refNum,f.file,true,f.writable,eof);}
	/** Fills the FCB entries of the files opened before the volume control blocks existed. */
	void describeOpenFiles(){for(ResourceFile68k f:files.values())describe(f);}

	// ------------------------------------------------------------------ in-memory maps
	// A real Resource Manager keeps every open file's map in a Handle chained from TopMapHndl, and some programs read
	// those structures directly: some resource editors locate a file's map by refNum (CurMap, then the mNext chain) right after
	// OpenResFile and takes HandToHand copies of it. The images are mirrored here for applications: rebuilt when a
	// file's resource list changes, patched in place when a resource Handle is loaded/released or attributes change.
	/** The maps live in guest memory (TopMapHndl, CurMap, SysMapHndl) for programs and externals alike: MacroMind's PlayMovie
	 *  XCMD makes the file on top of the chain current with UseResFile((**TopMapHndl).refNum) */
	private boolean mirrorsMaps(){return true;}
	/** Debug (commands.txt `rmap <refNum>`): the program's in-memory map of an open file, entry by entry. */
	String describeGuestMap(int refNum){
		ResourceFile68k f=files.get(refNum);if(f==null||f.mapHandle==0)return " no map";
		StringBuilder sb=new StringBuilder();
		try{int map=memory.readLong(f.mapHandle);
			sb.append(String.format(" header data %d map %d dataLen %d mapLen %d attrs %04X%n",memory.readLong(map),memory.readLong(map+4),memory.readLong(map+8),memory.readLong(map+12),memory.readWord(map+22)));
			int typeList=map+memory.readWord(map+24),types=(short)memory.readWord(typeList)+1;
			for(int i=0;i<types;i++){int e=typeList+2+8*i,type=memory.readLong(e),count=memory.readWord(e+4)+1,ref=typeList+memory.readWord(e+6);
				for(int j=0;j<count;j++){int r=ref+12*j;int h=memory.readLong(r+8);
					sb.append(String.format("  %s %d attrs %02X offset %d handle $%X%s%n",ResourceFile68k.typeName(type),(short)memory.readWord(r),memory.readByte(r+4),memory.readLong(r+4)&0xffffff,h,h!=0&&memory.readLong(h)==0?" (empty)":""));}}
		}catch(M68kCpuException e){sb.append(" ").append(e);}
		return sb.toString();
	}
	private void syncMap(ResourceFile68k f){
		if(!mirrorsMaps())return;
		try{byte[] image=f.buildMap(0);
			if(f.mapHandle==0){f.mapHandle=tb.api.createHandle(image);tb.api.setHandleState(f.mapHandle,0x80);}else tb.api.loadHandle(f.mapHandle,image);
		}catch(M68kCpuException e){f.mapHandle=0;}
	}
	/** Chains the maps most-recently-opened first (the System file last) and refreshes the low-memory globals. */
	private void relinkMaps(){
		if(!mirrorsMaps())return;
		// the same order as the search chain, so TopMapHndl is the application's map, not the last extension file the loader
		// opened (some projector-style applications take their own refNum from the top map)
		try{List<ResourceFile68k> all=ordered();int next=0;
			for(ResourceFile68k f:all){if(f.mapHandle==0)continue;int map=memory.readLong(f.mapHandle);
				// a detached map stays out of the chain with no link, as the program (or DetachResourceFile) left it
				if(detached.contains(f.refNum)){if(map!=0)memory.writeLong(map+16,0);continue;}
				if(map!=0)memory.writeLong(map+16,next);next=f.mapHandle;}
			memory.writeLong(TOP_MAP_HNDL,next);
			ResourceFile68k sys=files.get(systemRefNum);memory.writeLong(SYS_MAP_HNDL,sys==null?0:sys.mapHandle);memory.writeWord(SYS_MAP,systemRefNum);memory.writeWord(CUR_MAP,current);
		}catch(M68kCpuException ignored){}
	}
	/**
	 * Programs may relink the maps themselves (mNext at map+16, TopMapHndl): HyperCard's "start using" moves a stack's map
	 * just BELOW the current stack's, so the stacks in use are searched after the current stack and before HyperCard itself
	 * (Home > Edit > Audio... runs Audio Help's AudioPalette XCMD, which GetResources its GCOD code from the same file).
	 * The ROM searches along those links, so a consistent relinked chain becomes the search order here too.
	 */
	private void adoptGuestChain() throws M68kCpuException {
		// Runs before every resource trap: when the chain of map Handles is the one seen last time, nothing was relinked.
		// (Building the lookup below for every call was half of the time a custom MDEF spent in its item probes.)
		int n=files.size();if(seenChain.length!=n+1)seenChain=new int[n+1];
		int count=0;boolean unchanged=seenFiles==n;
		for(int h=memory.readLong(TOP_MAP_HNDL);h!=0&&count<=n;count++){if(count<n+1&&seenChain[count]!=h){unchanged=false;seenChain[count]=h;}int map=memory.readLong(h);if(map==0)break;h=memory.readLong(map+16);}
		if(count!=seenCount){unchanged=false;seenCount=count;}
		seenFiles=n;if(unchanged)return;
		Map<Integer,ResourceFile68k> byMap=new HashMap<Integer,ResourceFile68k>();
		boolean lost=false;
		for(ResourceFile68k f:files.values()){if(f.mapHandle==0){lost=true;continue;}byMap.put(f.mapHandle,f);}
		if(byMap.isEmpty())return;
		List<ResourceFile68k> top=new ArrayList<ResourceFile68k>(files.size());
		int h=memory.readLong(TOP_MAP_HNDL);
		while(h!=0){ResourceFile68k f=byMap.get(h);if(f==null&&lost&&(f=copiedMap(h))!=null)byMap.put(h,f);if(f==null||top.contains(f))return;top.add(f);int map=memory.readLong(h);if(map==0)return;h=memory.readLong(map+16);}
		// A chain that ends cleanly but leaves maps out: the program unlinked them (OpenDocLib takes its library file's map out
		// of the chain right after opening it and links it on top only while it reads its own resources, failing with
		// paramErr when the map is still linked). Those files are detached until the program links them in again; the
		// System file must still be there (otherwise it is a program mid-edit: keep ours).
		java.util.Set<Integer> out=new java.util.HashSet<Integer>();
		for(ResourceFile68k f:files.values())if(!top.contains(f)&&f.mapHandle!=0)out.add(f.refNum); // one whose map was disposed stays where it was
		if(out.contains(systemRefNum))return;
		boolean detachChanged=!out.equals(detached);
		if(detachChanged){detached.clear();detached.addAll(out);if(Mac68kApi.TRACE)System.err.println("[res] maps out of the program's chain: "+out);}
		List<ResourceFile68k> ours=ordered();ours.removeIf(f->out.contains(f.refNum));boolean same=ours.size()==top.size();
		for(int i=0;i<ours.size()&&same;i++)same=ours.get(i)==top.get(top.size()-1-i);
		if(same)return;
		List<Map.Entry<Integer,ResourceFile68k>> entries=new ArrayList<Map.Entry<Integer,ResourceFile68k>>(files.entrySet());
		files.clear();boolean aboveApplication=false;belowApplication.clear();
		for(Map.Entry<Integer,ResourceFile68k> e:entries)if(out.contains(e.getKey()))files.put(e.getKey(),e.getValue()); // detached: out of the search anyway
		for(int i=top.size()-1;i>=0;i--){ResourceFile68k f=top.get(i);
			for(Map.Entry<Integer,ResourceFile68k> e:entries)if(e.getValue()==f){files.put(e.getKey(),f);
				if(e.getKey()==applicationRefNum)aboveApplication=true;
				else if(!aboveApplication&&e.getKey()!=systemRefNum){belowApplication.add(e.getKey());raisedByProgram.remove(e.getKey());}
				break;}}
		if(Mac68kApi.TRACE)System.err.println("[res] adopted the program's map chain order");
	}
	/** The map Handles along the guest chain at the last adoptGuestChain, their count and the number of open files then. */
	private int[] seenChain=new int[0];private int seenCount=-1,seenFiles=-1;
	/** Writes a resource's current Handle and attributes into its map entry. */
	private void patchMapEntry(ResourceFile68k.Resource r){
		if(!mirrorsMaps()||r==null||r.mapEntry<0)return;
		ResourceFile68k f=fileOf(r);if(f==null||f.mapHandle==0)return;
		try{int map=memory.readLong(f.mapHandle);if(map==0)return;memory.writeByte(map+r.mapEntry+4,r.mapAttributes()&0xff);memory.writeLong(map+r.mapEntry+8,r.handle);}catch(M68kCpuException ignored){}
	}
	private void patchMapAttributes(ResourceFile68k f){
		if(!mirrorsMaps()||f==null||f.mapHandle==0)return;
		try{int map=memory.readLong(f.mapHandle);if(map!=0)memory.writeWord(map+22,f.attributes|(f.writable?0:0x80));}catch(M68kCpuException ignored){}
	}
	/**
	 * A program disposed a file's map Handle: Kaleidoscope's INIT copies its scheme file's map into the system heap
	 * (HandToHand), disposes the original and links the copy into the chain in its place. The disposed one must not be
	 * written any more (the next block allocated there - a dialog's pen PixPat - was overwritten by map entries and the
	 * control panel died drawing a line); the copy is taken up when it turns up in the chain ({@link #adoptGuestChain}).
	 */
	void handleDisposed(int handle){
		if(handle==0)return;
		for(ResourceFile68k f:files.values())if(f.mapHandle==handle){f.mapHandle=0;if(Mac68kApi.TRACE)System.err.println("[res] map Handle of refNum "+f.refNum+" disposed by the program");}
	}
	/** An unknown map Handle in the program's chain: the copy of the map of an open file that lost its own (by its refNum). */
	private ResourceFile68k copiedMap(int h) throws M68kCpuException {
		int map=memory.readLong(h);if(map==0)return null;
		ResourceFile68k f=files.get((int)(short)memory.readWord(map+20));
		if(f==null||f.mapHandle!=0)return null;
		f.mapHandle=h;if(Mac68kApi.TRACE)System.err.printf("[res] refNum %d takes the program's copy of its map $%x%n",f.refNum,h);
		return f;
	}
	private void disposeMap(ResourceFile68k f){if(f.mapHandle!=0){tb.api.disposeHandleAndFree(f.mapHandle);f.mapHandle=0;}for(ResourceFile68k.Resource r:f.ordered)r.mapEntry=-1;}
	/** The host file behind an open resource file refNum (null for the built-in System file or an unknown refNum). */
	String describeFile(ResourceFile68k.Resource r){ResourceFile68k f=fileOf(r);return f==null?"?":"refNum "+f.refNum+" "+(f.file==null?"(built-in)":f.file.getName());}
	File fileOf(int refNum){ResourceFile68k f=files.get(refNum);return f==null?null:f.file;}
	/** The refNums of the open resource files that are host files (the FCB table's resource forks; the built-in System has none). */
	java.util.Collection<Integer> openReferences(){java.util.List<Integer> r=new java.util.ArrayList<Integer>();for(Map.Entry<Integer,ResourceFile68k> e:files.entrySet())if(e.getValue().file!=null)r.add(e.getKey());return r;}

	boolean implemented(int trap){return name(trap)!=null;}
	static String name(int trap){
		switch(trap){
			case 0x99b:return "SetResLoad";case 0x99c:return "CountResources";case 0x80d:return "Count1Resources";case 0x99d:return "GetIndResource";case 0x80e:return "Get1IndResource";case 0x9a0:return "GetResource";
			case 0x81f:return "Get1Resource";case 0x9a1:return "GetNamedResource";case 0x820:return "Get1NamedResource";case 0x9a2:return "LoadResource";case 0x9a3:return "ReleaseResource";case 0x992:return "DetachResource";
			case 0x9c1:return "UniqueID";case 0x810:return "Unique1ID";case 0x9a8:return "GetResInfo";case 0x9a6:return "GetResAttrs";case 0x9a5:return "SizeResource";case 0x99e:return "CountTypes";case 0x81c:return "Count1Types";
			case 0x99f:return "GetIndType";case 0x80f:return "Get1IndType";case 0x821:return "MaxSizeRsrc";case 0x9c5:return "RsrcMapEntry";case 0x80c:return "RGetResource";case 0x995:return "InitResources";case 0x996:return "RsrcZoneInit";
			case 0x9af:return "ResError";case 0x9f6:return "GetResFileAttrs";case 0x9f7:return "SetResFileAttrs";case 0x9a9:return "SetResInfo";case 0x9a7:return "SetResAttrs";case 0x9aa:return "ChangedResource";
			case 0x9ab:return "AddResource";case 0x9ac:return "AddReference";case 0x9ae:return "RmveReference";case 0x9ad:return "RemoveResource";case 0x999:return "UpdateResFile";case 0x9b0:return "WriteResource";case 0x993:return "SetResPurge";case 0x9b1:return "CreateResFile";
			case 0x9c4:return "OpenRFPerm";case 0x997:return "OpenResFile";case 0x99a:return "CloseResFile";case 0x994:return "CurResFile";case 0x9a4:return "HomeResFile";case 0x998:return "UseResFile";
			case 0x822:return "ResourceDispatch";case 0x81a:return "HOpenResFile";case 0x81b:return "HCreateResFile";
			case 0x9b8:return "GetPattern";case 0x9b9:return "GetCursor";case 0x9ba:return "GetString";case 0x9bb:return "GetIcon";case 0x9bc:return "GetPicture";case 0x81d+0x1000:return null;
			default:return null;
		}
	}

	/**
	 * Programs write CurMap directly instead of calling UseResFile, and relink the maps themselves (TopMapHndl, mNext); the ROM
	 * reads them on every call. Runs before every resource trap and before the opens and closes that arrive from elsewhere
	 * (FSpOpenResFile through _HighLevelFSDispatch, the Component Manager, Carbon's FSOpenResFile): Microsoft Internet Library
	 * opens its own file, puts TopMapHndl back with LMSetTopMapHndl to keep the map out of the chain and calls FSpOpenResFile
	 * next - opened without this step, the map was linked in again, closing the next file made it the current file, and
	 * Internet Explorer 5.1 missed its own default toolbar ('MIME' 1, Get1Resource) and quit with an error at start-up.
	 */
	private void followGuest() throws M68kCpuException {
		if(!mirrorsMaps())return;
		int curMap=(short)memory.readWord(CUR_MAP);
		if(curMap!=current&&files.containsKey(curMap))current=curMap;
		adoptGuestChain();
	}

	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		followGuest();
		boolean handled=handleTrap(cpu,trap);
		if(handled&&mirrorsMaps())memory.writeWord(CUR_MAP,current);
		// The ROM's StdExit (ResourceMgr.a) leaves D0 = ResErr (high word 0) and A0 = 0. EGWord 5.0's save routine
		// returns DetachResource's D0 as its own result: the Handle's low word read as an error (-27890, nothing saved).
		if(handled&&exitsThroughStdExit(trap)){cpu.d[0]=memory.readWord(RES_ERR_ADDRESS)&0xffff;cpu.a[0]=0;}
		return handled;
	}
	/** ResError, CurResFile, SetResLoad and SetResPurge return through RsClrEx (D0/A0 left alone); the dispatcher and the Get... utilities are not this exit's.
	 *  LoadResource leaves A0 alone too: the LDEF stub every Communications Toolbox tool carries does `move.l a0,-(sp);
	 *  _LoadResource; movea.l a0,a1` and later jumps through (a1) - with A0 = 0 Serial Tool's port list jumped to $01000000. */
	private static boolean exitsThroughStdExit(int trap){
		switch(trap){case 0x9af:case 0x994:case 0x99b:case 0x993:case 0x822:case 0x9b8:case 0x9b9:case 0x9ba:case 0x9bb:case 0x9bc:case 0x9a2:return false;}
		return name(trap)!=null;
	}
	private boolean handleTrap(M68kCpu cpu,int trap) throws M68kException {
		switch(trap){
			// SetResLoad: Boolean in the high-order byte. ResErr stays as it was: Rinaldi's FullResList/FullResChange call OpenRFPerm,
			// then SetResLoad(true), then ResError - a cleared error made them list (and renumber) the stack instead of the file
			case 0x99b:loadResources=memory.readByte(cpu.a[7])!=0;cpu.a[7]+=2;return true;
			case 0x99c:case 0x80d:{int type=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeWord(cpu.a[7],entries(type,trap==0x80d).size());setError(NO_ERR);return true;}
			case 0x99d:case 0x80e:{int index=(short)memory.readWord(cpu.a[7]),type=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;List<ResourceFile68k.Resource> list=entries(type,trap==0x80e);
				if(list.isEmpty()&&type==DNRP&&trap==0x80e)list=system==null?list:system.ofType(type); // see DNRP
				memory.writeLong(cpu.a[7],handleFor(index>=1&&index<=list.size()?list.get(index-1):null));return true;}
			case 0x9a0:case 0x81f:{int id=(short)memory.readWord(cpu.a[7]),type=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;ResourceFile68k.Resource r=find(type,id,trap==0x81f);int h=getResource(r);if(h==0&&Mac68kApi.TRACE)System.err.println("[68K] GetResource "+ResourceFile68k.typeName(type)+" "+id+" failed: "+(r==null?"not found":"ResErr "+(short)memory.readWord(0xa60)));
				else if(Mac68kApi.TRACE)System.err.println("[68K] GetResource "+ResourceFile68k.typeName(type)+" "+id+" -> $"+Integer.toHexString(h)+" data $"+Integer.toHexString(memory.readLong(h))+" size "+tb.api.handleSize(h)+" current "+current);
				memory.writeLong(cpu.a[7],h);return true;}
			case 0x80c:{int id=(short)memory.readWord(cpu.a[7]),type=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;memory.writeLong(cpu.a[7],getResource(find(type,id,false)));return true;}
			case 0x9a1:case 0x820:{int name=memory.readLong(cpu.a[7]),type=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;String n=resourceName(name);ResourceFile68k.Resource r=findNamed(type,n,trap==0x820);if(Mac68kApi.TRACE)System.err.println("[res] "+(trap==0x820?"Get1NamedResource ":"GetNamedResource ")+ResourceFile68k.typeName(type)+" \""+n+"\" -> "+(r==null?"not found":r.id));memory.writeLong(cpu.a[7],handleFor(r));if(r==null&&!typeExists(type,trap==0x820))setError(NO_ERR);return true;}
			case 0x9a2:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}if(memory.readLong(h)==0)tb.api.loadHandle(h,data(r));setError(NO_ERR);return true;}
			case 0x9a3:case 0x992:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.remove(h);if(r==null){setError(RES_NOT_FOUND);return true;}r.handle=0;patchMapEntry(r);if(trap==0x992)tb.api.setResourceBit(h,false);if(trap==0x9a3){tb.menus.resourceReleased(h);if(!tb.isLoadedSegment(h))tb.api.disposeHandleAndFree(h);}setError(NO_ERR);return true;}
			case 0x9c1:case 0x810:{int type=memory.readLong(cpu.a[7]);cpu.a[7]+=4;List<ResourceFile68k> files=indexed(trap==0x810);
				// The lowest free ID from 128; asked again for the same type, the next free one after the last answer. The ROM draws
				// IDs at random, so a program looping until it gets one it has not handed out itself gets there: DragThing wants an
				// ID none of its unsaved docks holds and looped forever on 128.
				// From the third call in a row for one type the IDs are drawn at random, as the ROM does: Caliban's MakeTextRes asks up to
				// 500 times for one above 8191 and made TEXT ID 0 when counting up from 128 never got there.
				int id=128;while(inAny(files,type,id))id++;
				uniqueCalls=type==lastUniqueType?uniqueCalls+1:1;
				if(type==lastUniqueType&&uniqueCalls>2){for(int n=0;n<1000;n++){int r=128+uniqueRandom.nextInt(32768-128);if(!inAny(files,type,r)){id=r;break;}}}
				else if(type==lastUniqueType&&id<=lastUniqueId){id=lastUniqueId<32767?lastUniqueId+1:128;for(int n=0;n<32768&&inAny(files,type,id);n++)id=id<32767?id+1:128;}
				lastUniqueType=type;lastUniqueId=id;memory.writeWord(cpu.a[7],id);setError(NO_ERR);return true;}
			case 0x9a8:{int name=memory.readLong(cpu.a[7]),typePtr=memory.readLong(cpu.a[7]+4),idPtr=memory.readLong(cpu.a[7]+8),h=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16;ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}if(idPtr!=0)memory.writeWord(idPtr,r.id);if(typePtr!=0)memory.writeLong(typePtr,r.type);if(name!=0)writeResourceName(name,r.name);setError(NO_ERR);return true;}
			case 0x9a6:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);memory.writeWord(cpu.a[7],r==null?0:r.mapAttributes());setError(r==null?RES_NOT_FOUND:NO_ERR);return true;}
			// SizeResource/MaxSizeRsrc: a loaded resource reports its Handle size (some startup stubs decompress
			// CODE resources in place and its self-check sums the expanded sizes), an unloaded one the size on disk
			case 0x9a5:case 0x821:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);memory.writeLong(cpu.a[7],r==null?0:memory.readLong(h)!=0?tb.api.handleSize(h):r.length());setError(r==null?RES_NOT_FOUND:NO_ERR);return true;}
			case 0x99e:case 0x81c:{memory.writeWord(cpu.a[7],types(trap==0x81c).size());setError(NO_ERR);return true;}
			case 0x99f:case 0x80f:{int index=(short)memory.readWord(cpu.a[7]),typePtr=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;List<Integer> t=types(trap==0x80f);if(index<1||index>t.size()){if(typePtr!=0)memory.writeLong(typePtr,0);setError(RES_NOT_FOUND);return true;} /* out of range: four NUL characters (THINK C's Build Application counts types until it gets them) */ if(typePtr!=0)memory.writeLong(typePtr,t.get(index-1));setError(NO_ERR);return true;}
			// RsrcMapEntry: offset of the resource's reference-list entry in its file's map (the Finder's segment loader adds it to
			// the map's data offset and reads CODE lengths straight from the fork)
			case 0x9c5:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);int entry=r!=null&&r.mapEntry>=0?r.mapEntry:0;
				memory.writeLong(cpu.a[7],entry);setError(r==null?RES_NOT_FOUND:NO_ERR);return true;}
			case 0x995:memory.writeWord(cpu.a[7],applicationRefNum);return true;
			case 0x996:return true;
			// ResError reports the low-memory ResErr word: programs store into it themselves (some resource editors force resNotFound there
			// after a nil Get1Resource, working around the type-absent noErr quirk below)
			case 0x9af:memory.writeWord(cpu.a[7],(short)memory.readWord(RES_ERR_ADDRESS));return true;
			case 0x9f6:{int ref=memory.readWord(cpu.a[7]);cpu.a[7]+=2;ResourceFile68k f=files.get(ref);memory.writeWord(cpu.a[7],f==null?0:f.attributes|(f.writable?0:0x80));setError(f==null?RES_FILE_NOT_FOUND:NO_ERR);return true;}
			case 0x9f7:{int attrs=memory.readWord(cpu.a[7]),ref=memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;ResourceFile68k f=files.get(ref);if(f!=null){f.attributes=attrs;patchMapAttributes(f);}setError(f==null?RES_FILE_NOT_FOUND:NO_ERR);return true;}
			case 0x9a9:{int name=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}
				if((r.attributes&0x08)!=0){setError(-198);return true;} /* resProtected: resAttrErr, ID and name unchanged (FullResChange renumbered a protected ICON) */ResourceFile68k f=fileOf(r);if(f!=null){f.byKey.remove(r.key());r.id=id;if(name!=0)r.name=resourceName(name);f.byKey.put(r.key(),r);f.dirty=true;syncMap(f);relinkMaps();}setError(NO_ERR);return true;}
			case 0x9a7:{int attrs=memory.readWord(cpu.a[7]),h=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}r.attributes=attrs&~ResourceFile68k.RES_CHANGED;if((attrs&ResourceFile68k.RES_CHANGED)!=0)r.changed=true; /* the map's resChanged bit; clearing it keeps a pending write here */ patchMapEntry(r);ResourceFile68k f=fileOf(r);if(f!=null)f.dirty=true;setError(NO_ERR);return true;}
			case 0x9aa:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}if((r.attributes&0x08)!=0){setError(-198);return true;} /* resProtected: resAttrErr, left unchanged */r.changed=true;captureHandle(r);ResourceFile68k f=fileOf(r);if(f!=null){f.dirty=true;syncMap(f);relinkMaps();}setError(f!=null&&!f.writable&&f!=system?PERM_ERR:NO_ERR);return true;}
			case 0x9ab:return addResource(cpu);
			// The 1984 reference calls (MacWrite 2.2 records the fonts a document uses): a reference is a map entry for (type of the
			// handle's resource, id) with a name and no data, in the current resource file; a duplicate or an unknown handle is addRefFailed.
			case 0x9ac:{int name=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4),h=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;ResourceFile68k f=files.get(current);ResourceFile68k.Resource source=byHandle.get(h);
				if(f==null||source==null||f.get(source.type,id)!=null){setError(ADD_REF_FAILED);return true;}
				f.add(new ResourceFile68k.Resource(source.type,id,name==0?null:resourceName(name),0,new byte[0]));f.dirty=true;syncMap(f);relinkMaps();setError(NO_ERR);return true;}
			case 0x9ae:{int h=pop(cpu);ResourceFile68k f=files.get(current);ResourceFile68k.Resource source=byHandle.get(h);ResourceFile68k.Resource ref=f==null||source==null?null:f.get(source.type,source.id);
				if(ref==null||ref==source){setError(RMV_REF_FAILED);return true;}f.remove(ref);f.dirty=true;syncMap(f);relinkMaps();setError(NO_ERR);return true;}
			case 0x9ad:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);if(r==null||(r.attributes&0x08)!=0){setError(RMV_RES_FAILED);return true;} /* a protected resource stays */byHandle.remove(h);ResourceFile68k f=fileOf(r);if(f!=null){f.remove(r);syncMap(f);relinkMaps();}r.handle=0;r.mapEntry=-1;setError(NO_ERR);return true;}
			case 0x999:{int ref=memory.readWord(cpu.a[7]);cpu.a[7]+=2;ResourceFile68k f=files.get(ref);if(f==null){setError(RES_FILE_NOT_FOUND);return true;}update(f);return true;}
			case 0x9b0:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}captureHandle(r);ResourceFile68k f=fileOf(r);if(f!=null)update(f);return true;}
			case 0x993:cpu.a[7]+=2;return true; // SetResPurge: leaves ResErr alone, as SetResLoad does
			case 0x9b1:case 0x81b:return createResFile(cpu,trap==0x81b);
			case 0x997:case 0x9c4:case 0x81a:return openResFile(cpu,trap);
			case 0x99a:{int ref=memory.readWord(cpu.a[7]);cpu.a[7]+=2;closeResFile(ref);return true;}
			case 0x994:memory.writeWord(cpu.a[7],current);if(Mac68kApi.TRACE)System.err.println("[res] CurResFile -> "+current);return true;
			case 0x9a4:{int h=pop(cpu);ResourceFile68k.Resource r=byHandle.get(h);ResourceFile68k f=r==null?null:fileOf(r);memory.writeWord(cpu.a[7],f==null?-1:f.refNum);setError(f==null?RES_NOT_FOUND:NO_ERR);return true;}
			case 0x998:{int ref=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;if(Mac68kApi.TRACE)System.err.println("[res] UseResFile "+ref+" (was "+current+")");if(ref==0)ref=systemRefNum;if(files.containsKey(ref)){current=ref;setError(NO_ERR);}else setError(RES_FILE_NOT_FOUND);return true;}
			case 0x822:return resourceDispatch(cpu);
			case 0x9b8:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],getResource(find(0x50415420,id,false)));return true;}
			case 0x9b9:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],getResource(find(0x43555253,id,false)));return true;}
			case 0x9ba:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],getResource(find(0x53545220,id,false)));return true;}
			case 0x9bb:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],getResource(find(0x49434f4e,id,false)));return true;}
			case 0x9bc:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],getResource(find(0x50494354,id,false)));return true;}
			default:return false;
		}
	}

	private int pop(M68kCpu cpu) throws M68kCpuException {int v=memory.readLong(cpu.a[7]);cpu.a[7]+=4;return v;}
	private void setError(int e) throws M68kCpuException {error=e;memory.writeWord(RES_ERR_ADDRESS,e);}

	// ------------------------------------------------------------------ lookup

	/** Search chain: current file, then files opened earlier. */
	/**
	 * Files opened outside the program's own chain of opens - extensions' files, components' files, QuickTime's
	 * instrument data - rank as if they had been opened before the application, the way INIT-time opens are on a real Mac.
	 * They are opened here after the application (INITs run at launch), and an Indeo Video component's STR# 1024 otherwise
	 * shadowed Souls in the System's own STR# 1024 whenever a later-opened data file was the current file.
	 */
	private final java.util.Set<Integer> belowApplication=new java.util.HashSet<Integer>();
	/** Files the program opened itself although the loader (an extension, a component) had them open below the application:
	 *  they moved above it for the program, as a fresh OpenResFile does on a real Mac where an INIT closes its file after
	 *  loading (a program can open the Speech Manager for its 'ttvd' voices; with the extension's copy current the whole
	 *  chain above it - the program's own DLOGs and STR#s - was out of reach). CloseResFile puts them back below. */
	private final java.util.Set<Integer> raisedByProgram=new java.util.HashSet<Integer>();
	/** Every open file from the bottom of the search chain to the top: the files opened outside the chain (extensions the
	 *  loader opened after the application file) sit below the application, as on a real Mac where INITs load before it. */
	private List<ResourceFile68k> ordered(){
		List<ResourceFile68k> all=new ArrayList<ResourceFile68k>(files.size()),below=new ArrayList<ResourceFile68k>();
		for(Map.Entry<Integer,ResourceFile68k> e:files.entrySet())if(e.getKey()!=systemRefNum&&e.getKey()!=applicationRefNum&&belowApplication.contains(e.getKey()))below.add(e.getValue());
		boolean placed=false;
		for(Map.Entry<Integer,ResourceFile68k> e:files.entrySet()){if(below.contains(e.getValue()))continue;if(e.getKey()==applicationRefNum){all.addAll(below);placed=true;}all.add(e.getValue());}
		if(!placed)all.addAll(below); // no application file: they keep their place
		return all;
	}
	/** Files the program took out of the search chain (Carbon's DetachResourceFile, FSpOpenOrphanResFile): still open, searched only while current. */
	private final java.util.Set<Integer> detached=new java.util.HashSet<Integer>();
	/**
	 * Extension files whose INITs have run: INIT 31 closes an extension's file once its INITs return (the Component Manager
	 * opens a component's file again when it needs its code), so its resources are out of every program's reach. The loader
	 * keeps them open for that on-demand loading, out of the search like {@link #detached} files (searched only while
	 * current). iTunes looks up a 'thng' 128 it does not have and registers its built-in 'TVOD' artwork decompressor only
	 * when that is NIL; the Internet Config Extension's 'thng' 128 answered, and every picture of its window failed to decode.
	 * A program's own OpenResFile of such a file brings it back ({@link #reopenedFromLoader}) until it closes it again.
	 */
	private final java.util.Set<Integer> closedByLoader=new java.util.HashSet<Integer>(),reopenedFromLoader=new java.util.HashSet<Integer>();
	/** The extension file whose INITs are running (their own OpenResFile of it keeps it open, see {@link #initsDone}). */
	private int initFile=-1;private boolean initReopened;
	void initsStarting(int ref){initFile=ref;initReopened=false;}
	/** The INITs of a file the loader opened have run: the file leaves the search chain unless an INIT opened it itself. */
	void initsDone(int ref){boolean keep=initFile==ref&&initReopened;initFile=-1;if(!keep&&files.containsKey(ref)&&ref!=systemRefNum&&ref!=applicationRefNum)closedByLoader.add(ref);}
	/** A program (or a component's OpenComponentResFile) opens a loader-closed file: it is searched again until closed. */
	private void reopenFromLoader(int ref){if(closedByLoader.remove(ref))reopenedFromLoader.add(ref);}
	private void backToLoader(int ref){if(reopenedFromLoader.remove(ref))closedByLoader.add(ref);}
	/** Carbon's InsertResourceFile(refNum, where): 0 below the System file, 1 below the application, 2 above the application,
	 *  4 above all maps (the Carbon chain layout, approximated by the loader's below/above-application split). */
	int insertResourceFile(int ref,int where) throws M68kCpuException {
		if(!files.containsKey(ref)||ref==systemRefNum||ref==applicationRefNum)return RES_FILE_NOT_FOUND;
		detached.remove(ref);
		if(where<=1){belowApplication.add(ref);raisedByProgram.remove(ref);}else{belowApplication.remove(ref);toTop(ref);}
		relinkMaps();return NO_ERR;
	}
	/** Carbon's DetachResourceFile: the file stays open but leaves the chain; a current detached file hands over to the application. */
	int detachResourceFile(int ref) throws M68kCpuException {
		if(!files.containsKey(ref)||ref==systemRefNum||ref==applicationRefNum)return RES_FILE_NOT_FOUND;
		detached.add(ref);if(current==ref)setCurrent(applicationRefNum);relinkMaps();return NO_ERR;
	}
	/** Carbon's GetTopResourceFile/GetNextResourceFile: the refNums of the search chain from the top down (detached and
	 *  loader-closed files are not in it). */
	List<Integer> chainFromTop(){
		List<ResourceFile68k> all=ordered();List<Integer> l=new ArrayList<Integer>(all.size());
		for(int i=all.size()-1;i>=0;i--){int ref=all.get(i).refNum;if(!detached.contains(ref)&&!closedByLoader.contains(ref))l.add(ref);}
		return l;
	}
	/** Opens a file outside the chain (FSpOpenOrphanResFile); it becomes current as OpenResFile's file does. */
	int openOrphan(File file) throws M68kCpuException {int ref=openFile(file);if(ref>=0&&ref!=systemRefNum&&ref!=applicationRefNum){detached.add(ref);relinkMaps();}return ref;}
	/** Carbon's FSpOpenDataForkAsResourceFile: a resource map kept in the file's data fork, opened (or reused) like OpenResFile's file; -1 with ResErr set. */
	int openDataForkFile(File file,int permission) throws M68kCpuException {
		followGuest();
		if(file==null||!file.isFile()){setError(FNF_ERR);return -1;}
		if(!Sandbox.allowFile(file,Sandbox.Access.READ,tb.name)){setError(PERM_ERR);return -1;}
		try{
			String path=file.getCanonicalPath();
			for(ResourceFile68k f:files.values())if(f.storage==ResourceFile68k.Storage.DATA&&f.file!=null&&f.file.getCanonicalPath().equals(path)){setCurrent(f.refNum);setError(NO_ERR);return f.refNum;}
			if(file.length()==0){setError(EOF_ERR);return -1;}
			ResourceFile68k rf=ResourceFile68k.openDataFork(file);
			rf.writable=permission!=FS_RD_PERM&&file.canWrite();
			int ref=open(rf);setCurrent(ref);setError(NO_ERR);return ref;
		}catch(IOException e){setError(MAP_READ_ERR);return -1;}
	}
	/** The refNum of an open resource file whose host file is {@code file} (either fork), or -1: FSResourceFileAlreadyOpen. */
	int refNumOf(File file){
		if(file==null)return -1;
		try{String path=file.getCanonicalPath();for(ResourceFile68k f:files.values())if(f.file!=null&&f.file.getCanonicalPath().equals(path))return f.refNum;}catch(IOException e){return -1;}
		return -1;
	}
	private List<ResourceFile68k> chain(boolean currentOnly){
		List<ResourceFile68k> result=new ArrayList<ResourceFile68k>();
		ResourceFile68k cur=files.get(current);
		if(cur!=null)result.add(cur);
		if(currentOnly)return result;
		List<ResourceFile68k> all=ordered();
		if(!detached.isEmpty())for(Integer ref:detached){ResourceFile68k f=files.get(ref);if(f!=null&&f!=cur)all.remove(f);}
		if(!closedByLoader.isEmpty())for(Integer ref:closedByLoader){ResourceFile68k f=files.get(ref);if(f!=null&&f!=cur)all.remove(f);}
		int index=all.indexOf(cur);
		for(int i=(index<0?all.size():index)-1;i>=0;i--)result.add(all.get(i));
		return result;
	}

	private static boolean inAny(List<ResourceFile68k> files,int type,int id){for(ResourceFile68k f:files)if(f.get(type,id)!=null)return true;return false;}
	ResourceFile68k.Resource find(int type,int id,boolean currentOnly){for(ResourceFile68k f:chain(currentOnly)){ResourceFile68k.Resource r=f.get(type,id);if(r!=null)return r;}return null;}
	ResourceFile68k.Resource find(int type,int id){return find(type,id,false);}
	/** In the open file {@code refNum} only, else in any open file, the most recently opened first (null when none has it). */
	ResourceFile68k.Resource findInOpenFiles(int refNum,int type,int id){
		ResourceFile68k f=files.get(refNum);ResourceFile68k.Resource r=f==null?null:f.get(type,id);if(r!=null)return r;
		List<ResourceFile68k> all=ordered();for(int i=all.size()-1;i>=0;i--){r=all.get(i).get(type,id);if(r!=null)return r;}
		return null;
	}
	/**
	 * GetResource, Get1Resource, RGetResource and the ROM routines built on them (GetPicture, GetCursor, GetString, GetIcon,
	 * GetPattern): a resource that is not there gives NIL with ResError noErr, not resNotFound as Inside Macintosh says. The
	 * ROM's StdEntry clears ResErr and the not-found path only exits; the System 7 patch that would set resNotFound (Apple's
	 * ResourceMgrPatches.a, "it broke MultiPlan") is compiled out. Programs rely on it: Classic Gin Rummy checks whether a
	 * card has a MRGN with Get1Resource and quits on any ResError ("Error checking if resource exists"), Gin Rummy 2.0 reads
	 * PICTs until GetPicture returns NIL and treats a nonzero ResError as fatal. Errors while loading a found resource
	 * (memFullErr ...) are still reported.
	 */
	private int getResource(ResourceFile68k.Resource r) throws M68kCpuException {int h=handleFor(r);if(r==null)setError(NO_ERR);return h;}
	/** GetNamedResource/Get1NamedResource quirk (IM: More Macintosh Toolbox): when the TYPE is absent from every searched map, ResError is noErr, not resNotFound. */
	private boolean typeExists(int type,boolean currentOnly){for(ResourceFile68k f:chain(currentOnly))if(!f.ofType(type).isEmpty())return true;return false;}
	/**
	 * Resource names are kept byte for byte as Mac Roman strings (the codec ResourceFile68k reads the map with), whatever the
	 * program's text encoding: under the Japanese encoding a name such as "\u00a5Splash" would otherwise decode to a different
	 * character than the map's and GetNamedResource could never match it (including named picture resources).
	 */
	String resourceName(int pascal) throws M68kCpuException {byte[] b=tb.readPascalBytes(pascal);return StackTextEncoding.ROMAN.decode(b,1,b.length-1);}
	void writeResourceName(int pascal,String name) throws M68kCpuException {
		if(pascal==0)return;byte[] b=StackTextEncoding.ROMAN.encodeReplacing(name==null?"":name);int n=Math.min(255,b.length);
		memory.writeByte(pascal,n);for(int i=0;i<n;i++)memory.writeByte(pascal+1+i,b[i]);
	}
	ResourceFile68k.Resource findNamed(int type,String name,boolean currentOnly){for(ResourceFile68k f:chain(currentOnly)){ResourceFile68k.Resource r=f.getNamed(type,name);if(r!=null)return r;}return null;}
	/** The type and ID UniqueID/Unique1ID answered last (see there). */
	private int lastUniqueType,lastUniqueId,uniqueCalls;
	private final java.util.Random uniqueRandom=new java.util.Random();
	/** The files CountResources/GetIndResource/CountTypes/GetIndType/UniqueID look through: every open resource file from the
	 *  top of the chain (TopMapHndl) whatever the current file - only their 1-deep forms stop at the current file (THINK
	 *  Pascal's resource copy counted from CurMap after UseResFile of an older file and missed the files opened since).
	 *  Detached and loader-closed files are not in that chain (searched only while current, as in {@link #chain}): Nisus
	 *  Writer 4.03 lists its segments with GetIndResource('CODE', i) and took the Apple Guide extension's CODE 6 for its own,
	 *  then jumped into a stub placed at that resource's odd length. */
	private List<ResourceFile68k> indexed(boolean currentOnly){
		if(currentOnly)return chain(true);
		List<ResourceFile68k> all=ordered();java.util.Collections.reverse(all);
		ResourceFile68k cur=files.get(current);
		all.removeIf(f->f!=cur&&(detached.contains(f.refNum)||closedByLoader.contains(f.refNum)));
		return all;
	}
	/**
	 * 'dnrp': the MacTCP Domain Name Resolver. The DNR glue linked into programs (dnr.c's OpenResolver) opens the resource fork
	 * of the file of type 'cdev' and creator 'ztcp' in the Control Panels or System Folder - MacTCP, or Open Transport's TCP/IP
	 * control panel - and takes Get1IndResource('dnrp', 1) from it, the current file. The VM has no such control panel; its
	 * resolver lives in the System file (MacTCP68k.installDnr), so a 'dnrp' the current file lacks is the System's. Anarchie
	 * 2.0 (68K) gave up with "Failed to startup (-192)".
	 */
	private static final int DNRP=0x646e7270;
	private List<ResourceFile68k.Resource> entries(int type,boolean currentOnly){List<ResourceFile68k.Resource> result=new ArrayList<ResourceFile68k.Resource>();for(ResourceFile68k f:indexed(currentOnly))result.addAll(f.ofType(type));return result;}
	private List<Integer> types(boolean currentOnly){List<Integer> result=new ArrayList<Integer>();for(ResourceFile68k f:indexed(currentOnly))for(int t:f.types())if(!result.contains(t))result.add(t);return result;}
	List<ResourceFile68k.Resource> resourcesOfFile(int ref,int type){ResourceFile68k f=files.get(ref);return f==null?new ArrayList<ResourceFile68k.Resource>():f.ofType(type);}
	List<String> resourceNames(int type){List<String> names=new ArrayList<String>();for(ResourceFile68k.Resource r:entries(type,false))if(r.name!=null&&!r.name.isEmpty()&&!names.contains(r.name))names.add(r.name);return names;}
	/** Whether a resource comes from the built-in System file rather than the program or a stack. */
	boolean isSystem(ResourceFile68k.Resource r){return fileOf(r)==system;}
	private ResourceFile68k fileOf(ResourceFile68k.Resource r){for(ResourceFile68k f:files.values())if(f.byKey.get(r.key())==r)return f;return null;}

	/**
	 * The bytes of a resource as a program sees them. Programs whose resources are compressed (including self-extracting
	 * self-extracting archives and compressed applications) patch OS trap $A0FC, the ROM routine that reads
	 * a resource's data into a Handle, with a decompression engine. The real Resource Manager reads every resource
	 * through that routine, so once a patch is installed each resource is fed through it the same way: A2 =
	 * reference-list entry, A4 = pointer to the map pointer, the data read back with _Read from the map's file
	 * refnum (or by chaining to the original routine, Toolbox68k.readResourceData), the result Handle stored at 8(A2).
	 */
	byte[] data(ResourceFile68k.Resource r) throws M68kCpuException {
		byte[] data=r.data();
		if((r.attributes&ResourceFile68k.RES_COMPRESSED)!=0&&MacResourceDecompressor.isCompressed(data)){ // a 'dcmp' the built-in ones don't cover
			byte[] out=guestDecompress(data);if(out!=null){r.setData(out);r.attributes&=~ResourceFile68k.RES_COMPRESSED;data=out;}}
		if(r.expanded||data.length==0)return data;
		int engine=tb.cpu.patchedTrap(0xa0fc);
		if(engine==0)return data;
		if(Mac68kApi.TRACE&&data.length>=12&&data[0]=='A'&&data[1]=='D'&&data[2]=='C'&&data[3]=='R')System.err.println("[68K] ADCR resource "+ResourceFile68k.typeName(r.type)+" "+r.id);
		byte[] image=new byte[4+data.length];image[0]=(byte)(data.length>>>24);image[1]=(byte)(data.length>>>16);image[2]=(byte)(data.length>>>8);image[3]=(byte)data.length;System.arraycopy(data,0,image,4,data.length);
		int reference;try{reference=tb.files.fileManager.openBytes(image);}catch(IOException e){return data;}
		int block=memory.allocate(0x40,4);
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int savedResErr=memory.readWord(0xa60);
		try{
			int map=block,entry=block+0x20,cell=block+0x30;
			memory.fill(block,0x40,0);
			memory.writeLong(map,0);memory.writeWord(map+0x14,reference);            // map: data offset, file refnum
			memory.writeWord(entry,r.id);memory.writeWord(entry+2,-1);memory.writeLong(entry+4,(r.attributes&0xff)<<24);memory.writeLong(entry+8,0);
			memory.writeLong(cell,map);
			cpu.a[2]=entry;cpu.a[3]=entry;cpu.a[4]=cell;
			cpu.call(engine);
			int h=memory.readLong(entry+8);
			if(h!=0){byte[] out=tb.api.handleContents(h);tb.api.disposeHandleAndFree(h);if(out!=null&&out.length>0){r.setData(out);r.expanded=true;
				if(Mac68kApi.TRACE&&out.length!=data.length)System.err.println("[68K] $A0FC engine expanded "+ResourceFile68k.typeName(r.type)+" "+r.id+": "+data.length+" -> "+out.length+" bytes");
				String dump=System.getProperty("hz68k.snapshot");if(dump!=null&&out.length!=data.length)try{java.nio.file.Files.write(new File(dump,ResourceFile68k.typeName(r.type).trim()+"-"+r.id+".bin").toPath(),out);}catch(IOException ignored){}
				return out;}}
			if(Mac68kApi.TRACE)System.err.println("[68K] $A0FC engine did not expand "+ResourceFile68k.typeName(r.type)+" "+r.id+" (ResErr "+(short)memory.readWord(0xa60)+")");
		}catch(M68kException e){
			if(Mac68kApi.TRACE)System.err.println("[68K] $A0FC engine failed on "+ResourceFile68k.typeName(r.type)+" "+r.id+": "+e.getMessage());
		}finally{
			System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);memory.writeWord(0xa60,savedResErr);
			memory.free(block);tb.files.fileManager.closeReference(reference);
		}
		return data;
	}

	/**
	 * The bytes of a resource as its loaded Handle holds them now, else as {@link #data} reads them: the Toolbox builds from the
	 * same Handle GetResource hands out, so a program's edit shows (Installer 4 sets a DLOG's itemsID before GetNewDialog).
	 */
	byte[] currentData(ResourceFile68k.Resource r) throws M68kCpuException {
		if(r.handle!=0&&byHandle.get(r.handle)==r&&memory.readLong(r.handle)!=0){byte[] live=tb.api.handleContents(r.handle);if(live!=null)return live;}
		return data(r);
	}

	/** Returns the (shared) Handle of a resource, loading it on first use. */
	int handleFor(ResourceFile68k.Resource r) throws M68kCpuException {
		if(r==null){setError(RES_NOT_FOUND);return 0;}
		if(r.handle!=0&&byHandle.get(r.handle)==r){
			// a Handle left empty by an earlier SetResLoad(false) lookup is filled in now that loading is on
			if(loadResources&&memory.readLong(r.handle)==0){try{tb.api.loadHandle(r.handle,data(r));}catch(M68kCpuException e){setError(MEM_FULL_ERR);return 0;}}
			if(Mac68kApi.TRACE&&r.type==0x434f4445)System.err.println("[68K] CODE "+r.id+" data at $"+Integer.toHexString(memory.readLong(r.handle)));
			setError(NO_ERR);return r.handle;}
		try{
			int h=loadResources?tb.api.createHandle(data(r)):tb.api.createEmptyHandle(r.length());
			tb.api.setHandleState(h,0x20|((r.attributes&ResourceFile68k.RES_LOCKED)!=0?0x80:0)|((r.attributes&ResourceFile68k.RES_PURGEABLE)!=0?0x40:0));
			r.handle=h;byHandle.put(h,r);patchMapEntry(r);setError(NO_ERR);
			if(Mac68kApi.TRACE&&r.type==0x434f4445)System.err.println("[68K] CODE "+r.id+" data at $"+Integer.toHexString(memory.readLong(h)));
			return h;
		}catch(M68kCpuException e){setError(MEM_FULL_ERR);return 0;}
	}

	/** True when the Handle belongs to a loaded resource (GetResource/GetMenu...), i.e. ReleaseResource applies to it. */
	boolean isResourceHandle(int handle){return byHandle.containsKey(handle);}
	/** How many resources are loaded (their Handles alive): an external that leaves some behind keeps state in them. */
	int loadedCount(){return byHandle.size();}

	/** ReleaseResource for a Handle obtained through handleFor; returns false when it is not a resource Handle. */
	boolean release(int handle){
		ResourceFile68k.Resource r=byHandle.remove(handle);if(r==null)return false;
		r.handle=0;patchMapEntry(r);
		// a loaded CODE segment stays in memory: UnloadSeg is a no-op here, so its jump-table entries still point into it
		if(!tb.isLoadedSegment(handle))tb.api.disposeHandleAndFree(handle);
		return true;
	}

	/** HLock/HandToHand on an unloaded resource Handle load it first; returns false when not a resource. */
	boolean loadIfResource(int handle) throws M68kCpuException {
		ResourceFile68k.Resource r=byHandle.get(handle);
		if(r==null){setError(RES_NOT_FOUND);return false;}
		if(memory.readLong(handle)==0){try{tb.api.loadHandle(handle,data(r));}catch(M68kCpuException e){setError(MEM_FULL_ERR);return false;}}
		setError(NO_ERR);return true;
	}

	/** A private copy of a resource (for records the program owns, such as windows and menus). */
	/** The same for the Toolbox's own lookups, which leave the program's ResError alone (Sargo Noidz checks ResError after
	 *  GetNewDialog/DisposeDialog, and the Palette Manager's search for 'pltt' 0 had left resNotFound there). */
	int detachedResourceQuietly(int type,int id) throws M68kCpuException {
		int saved=error;try{return detachedResource(type,id);}finally{setError(saved);}
	}
	int detachedResource(int type,int id) throws M68kCpuException {
		ResourceFile68k.Resource r=find(type,id,false);
		if(r==null){setError(RES_NOT_FOUND);return 0;}
		try{int h=tb.api.createHandle(data(r));setError(NO_ERR);return h;}catch(M68kCpuException e){setError(MEM_FULL_ERR);return 0;}
	}
	/** As above with GetResource's not-found rule (NIL, ResError noErr - see getResource): the GetIcon/GetPicture the Dialog
	 *  Manager does for icon and picture items. Canvas 3.5's tool palettes have picture items with PICT IDs it never had (it
	 *  draws them itself) and check ResError after GetNewDialog: resNotFound was "Error loading palette" (-192) at start-up. */
	int detachedResourceOrNil(int type,int id) throws M68kCpuException {
		int h=detachedResource(type,id);if(h==0&&error==RES_NOT_FOUND)setError(NO_ERR);return h;
	}

	/** IUGetIntl: the itl0/itl1 of the system script (the Japanese ones for a Japanese program, when present). */
	int internationalResource(int id) throws M68kCpuException {
		int type=id==0?0x69746c30:0x69746c31;
		ResourceFile68k.Resource r=tb.encoding==StackTextEncoding.JAPANESE?find(type,SystemResources68k.JAPANESE_ITL,false):null;
		return handleFor(r!=null?r:find(type,0,false));
	}

	/** IUSetIntl(refNum, theID, intlHandle): a copy of the handle's data becomes the file's itl0 (theID 0) or itl1 resource 0,
	 *  replacing one already there (MacWrite writes its localization data into every document it saves). */
	void setInternational(int refNum,int theID,int h) throws M68kCpuException {
		ResourceFile68k f=files.get(refNum);
		if(f==null){setError(RES_FILE_NOT_FOUND);return;}
		if(h==0){setError(ADD_RES_FAILED);return;}
		int type=theID==0?0x69746c30:0x69746c31;
		ResourceFile68k.Resource old=f.get(type,0);
		if(old!=null){if(old.handle!=0)byHandle.remove(old.handle);f.remove(old);}
		byte[] data=tb.api.handleContents(h);if(data==null)data=new byte[0];
		ResourceFile68k.Resource r=new ResourceFile68k.Resource(type,0,null,0,data);
		r.changed=true;f.add(r);f.dirty=true;
		syncMap(f);relinkMaps();
		setError(NO_ERR);
	}

	/** The host file behind an open resource file's refNum, or null (not open, or a fork without a host file). */
	File fileOfRefNum(int refNum){ResourceFile68k f=files.get(refNum);return f==null?null:f.file;}
	/** Puts {@code data} into the open file {@code refNum} as type/id, replacing a resource already there, as AddResource of a
	 *  detached handle would (written by UpdateResFile/CloseResFile). Answers the Resource Manager result code. */
	int putResource(int refNum,int type,int id,byte[] data) throws M68kCpuException {
		ResourceFile68k f=files.get(refNum);
		if(f==null)return RES_FILE_NOT_FOUND;
		if(!f.writable)return PERM_ERR;
		ResourceFile68k.Resource old=f.get(type,id);
		if(old!=null){if(old.handle!=0)byHandle.remove(old.handle);f.remove(old);}
		ResourceFile68k.Resource r=new ResourceFile68k.Resource(type,id,null,0,data);
		r.changed=true;f.add(r);f.dirty=true;
		syncMap(f);relinkMaps();
		return NO_ERR;
	}
	/** Names a resource of the open file {@code refNum} (AddMovieResource's resName); an empty name leaves it unnamed. */
	void setResourceName(int refNum,int type,int id,String name){
		ResourceFile68k f=files.get(refNum);ResourceFile68k.Resource r=f==null?null:f.get(type,id);if(r==null)return;
		r.name=name==null||name.isEmpty()?null:name;r.changed=true;f.dirty=true;
	}
	/** The data of a resource of the open file {@code refNum} (handle contents when loaded), or null. */
	byte[] resourceData(int refNum,int type,int id) throws M68kCpuException {ResourceFile68k f=files.get(refNum);ResourceFile68k.Resource r=f==null?null:f.get(type,id);if(r==null)return null;captureHandle(r);return data(r);}
	/** Removes a resource from the open file {@code refNum} (RmveResource); false when it has none. */
	boolean removeResource(int refNum,int type,int id){
		ResourceFile68k f=files.get(refNum);ResourceFile68k.Resource r=f==null?null:f.get(type,id);if(r==null)return false;
		if(r.handle!=0)byHandle.remove(r.handle);f.remove(r);syncMap(f);relinkMaps();return true;
	}
	/** The lowest resource ID from 128 that the open file {@code refNum} does not use for {@code type} (Unique1ID). */
	int unusedId(int refNum,int type){ResourceFile68k f=files.get(refNum);int id=128;while(f!=null&&id<32767&&f.get(type,id)!=null)id++;return id;}

	/** IUGetItlTable: {handle, offset, length} of a table in the script's itl2 (codes 0-1) or itl4 (2-4), or null when the
	 *  System file has no such resource. */
	int[] internationalTable(int script,int tableCode) throws M68kCpuException {
		int type=tableCode<=1?ResourceFile68k.fourcc("itl2"):ResourceFile68k.fourcc("itl4");
		ResourceFile68k.Resource r=null;
		if(script==1||(script==0&&tb.encoding==StackTextEncoding.JAPANESE))r=find(type,SystemResources68k.JAPANESE_ITL,false);
		if(r==null)r=find(type,0,false);
		if(r==null)return null;
		int h=handleFor(r);if(h==0)return null;int p=memory.readLong(h);
		if(tableCode<=1)return null; // no itl2 word-selection/wrap tables in the synthetic System file
		// NItl4Rec: unTokenOffset 32 / defPartsOffset 36 / whtSpListOffset 40, their lengths at 58 / 60 / 62
		int slot=tableCode==2?1:tableCode==3?0:2;
		return new int[]{h,memory.readLong(p+32+4*slot),memory.readWord(p+58+2*slot)&0xffff};
	}

	/** Copies the current Handle contents into the resource (ChangedResource/WriteResource). */
	private void captureHandle(ResourceFile68k.Resource r) throws M68kCpuException {
		if(r.handle==0)return;byte[] data=tb.api.handleContents(r.handle);if(data!=null)r.setData(data);
	}

	// ------------------------------------------------------------------ files

	private boolean openResFile(M68kCpu cpu,int trap) throws M68kCpuException {
		File file=null;String asked=null;int perm=FS_CUR_PERM;
		try{
			if(trap==0x997){int name=pop(cpu);asked=tb.readPascal(name);file=tb.files.fileForClassicPath(asked);}
			else if(trap==0x9c4){perm=memory.readByte(cpu.a[7]);int vRef=(short)memory.readWord(cpu.a[7]+2),name=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;asked=tb.readPascal(name);file=tb.files.fileFor(vRef,0,asked);} // name 4 + vRefNum 2 + permission byte (a word on the stack) = 8; popping 10 shifted the caller's saved registers (Speedometer's GDHandle came back <<16)
			else{perm=memory.readByte(cpu.a[7]);int name=memory.readLong(cpu.a[7]+2),dirId=memory.readLong(cpu.a[7]+6),vRef=(short)memory.readWord(cpu.a[7]+10);cpu.a[7]+=12;asked=tb.readPascal(name)+" in "+vRef+"/"+dirId;file=tb.files.fileFor(vRef,dirId,tb.readPascal(name));}
		}catch(IOException e){file=null;}
		int ref=openFile(file,perm);
		if(Mac68kApi.TRACE)System.err.println("[res] "+name(trap)+" -> "+(file==null?"(unresolved)":file.getPath())+(asked!=null?" '"+asked+"'":"")+" refNum "+ref+" err "+error);
		memory.writeWord(cpu.a[7],ref);return true;
	}

	static final int FS_CUR_PERM=0,FS_RD_PERM=1;
	/** Files opened read-only (fsRdPerm): the File Manager answers writes through their refNum with wrPermErr. */
	private final java.util.Set<Integer> readOnlyRefs=new java.util.HashSet<Integer>();
	/** Whether {@code ref} is a resource file opened with write permission (FileManager68k reads and writes its fork through the refNum). */
	boolean writableReference(int ref){ResourceFile68k f=files.get(ref);return f!=null&&f.file!=null&&f.writable&&f.stack==null&&!readOnlyRefs.contains(ref);} // a stack's file is written through the Resource Manager only
	/** Opens (or reuses) a resource file; returns its refNum or -1. */
	int openFile(File file) throws M68kCpuException {return openFile(file,FS_CUR_PERM);}
	/**
	 * Opens a resource file with the File Manager {@code permission} (OpenRFPerm/HOpenResFile/FSpOpenResFile). A file that is
	 * already open is reused - the File Manager answers a second write path with opWrErr and the Resource Manager hands back
	 * the open file - except for a read-only request on a file the program opened itself: read-only paths can be had any number
	 * of times, so that gets a fresh path with a map of its own, and closing it leaves the first one open. Acrobat Reader 5 checks
	 * each plug-in's signature through such a second path and closes it; with one shared refNum the plug-in's own resource
	 * file was gone, and the plug-ins looked for their language files next to whatever file was current.
	 */
	int openFile(File file,int permission) throws M68kCpuException {
		followGuest();
		// a stack's own path ("the long name of this stack": its .hzb without the extension) where no HyperCard file it was converted
		// from lies: the stack's resource file (G-XCollection's installer copies its XCMDs out of MtCRX pathThis())
		if(file!=null&&!file.exists())for(ResourceFile68k f:files.values())if(f.stack!=null&&Mac68kResourceManager.isStackPath(f.stack,file)){setCurrent(f.refNum);setError(NO_ERR);return f.refNum;}
		// the stand-in of a stack named by path (StackFile68k) whose resources this VM has open already (the stack on show, one in
		// use): that file, so what the external adds is seen at once
		OStack named=tb.files==null?null:tb.files.fileManager.stackOfShadow(file);
		if(named!=null)for(ResourceFile68k f:files.values())if(f.stack!=null&&(f.stack==named||Xcmd68k.sameStack(f.stack,named))){setCurrent(f.refNum);setError(NO_ERR);return f.refNum;}
		if(file==null||!file.isFile()){setError(FNF_ERR);return -1;}
		if(!Sandbox.allowFile(file,Sandbox.Access.READ,tb.name)){setError(PERM_ERR);return -1;}
		try{
			String path=file.getCanonicalPath();
			if(tb.files!=null&&tb.app!=null&&path.equals(tb.files.systemFile().getCanonicalPath())){setCurrent(systemRefNum);setError(NO_ERR);return systemRefNum;} // "System Folder:System" on disk is a stub: its resources are the built-in System file's
			for(ResourceFile68k f:files.values())if(f.file!=null&&f.storage!=ResourceFile68k.Storage.DATA&&f.file.getCanonicalPath().equals(path)){
				if(permission==FS_RD_PERM&&openedByProgram(f.refNum))break;
				// a map the program took out of the chain is no file it can open again: a code fragment's own read-only path
				// (CFM library resources: OpenDocLib, Cyberdog's editors) - another OpenResFile gets a path and a map of its own
				// on top of the chain (Cyberdog opens "Cyberdog Editors" again and GetMenus its MENUs through the chain)
				if(detached.contains(f.refNum)&&openedByProgram(f.refNum))break;
				if(f.refNum==initFile)initReopened=true;else reopenFromLoader(f.refNum);
				if(belowApplication.remove(f.refNum)){raisedByProgram.add(f.refNum);toTop(f.refNum);relinkMaps();}
				setCurrent(f.refNum);setError(NO_ERR);return f.refNum;}
			ResourceFile68k rf=ResourceFile68k.open(file);
			if(rf.storage==ResourceFile68k.Storage.NONE&&rf.ordered.isEmpty()){setError(EOF_ERR);return -1;}
			rf.writable=file.canWrite();
			int ref=open(rf);if(permission==FS_RD_PERM)readOnlyRefs.add(ref);setCurrent(ref);setError(NO_ERR);return ref;
		}catch(IOException e){setError(MAP_READ_ERR);return -1;}
	}

	/** A file the program opened itself (not the application, the System, or a file a loader holds for an extension or component). */
	private boolean openedByProgram(int ref){
		return ref!=applicationRefNum&&ref!=systemRefNum&&ref!=initFile&&!belowApplication.contains(ref)&&!raisedByProgram.contains(ref)
			&&!pinnedFiles.contains(ref)&&!closedByLoader.contains(ref)&&!reopenedFromLoader.contains(ref);
	}

	/** The extensions have loaded: whatever they opened (component files, their own resource files) sits below the
	 *  application from now on, as INIT-opened files do on a real Mac - and the application's map is the top map again. */
	void extensionsLoaded(){
		for(Integer ref:files.keySet())if(ref!=systemRefNum&&ref!=applicationRefNum)belowApplication.add(ref);
		current=applicationRefNum;relinkMaps();
	}
	int openFileNoChain(File file) throws M68kCpuException {int saved=current;int ref=openFile(file);setCurrent(saved);if(ref>=0){belowApplication.add(ref);raisedByProgram.remove(ref);relinkMaps();}return ref;}

	/** OpenComponentResFile on a component file the loader already holds open: on a real Mac that is a fresh OpenResFile,
	 *  so the copy sits ABOVE the application (its chain still reaches the program's own resources) until CloseResFile puts
	 *  it back below and hands the current file down the chain. HyperCard J-2.3 opens the AppleScript OSA component at
	 *  start-up; the component closes its file with a plain CloseResFile, and with the pinned extension's copy left current
	 *  and outside the chain HyperCard could not find its own 'WTLK' resources and quit. */
	void raiseForProgram(int ref) throws M68kCpuException {
		if(!files.containsKey(ref))return;
		reopenFromLoader(ref);
		if(belowApplication.remove(ref)){raisedByProgram.add(ref);toTop(ref);relinkMaps();}
		setCurrent(ref);
	}
	/**
	 * A loader-held file the program opens again becomes the top map, as a fresh OpenResFile's map would: searches from it reach
	 * every file the program opened, and closing it hands the current file to the one below (the previous top). The Speech
	 * Manager reopens its extension file for each voice; left in its old place (just above the application) its close made
	 * the application current, and Ultima III's pictures in "Ultima Graphics" were out of reach (-192).
	 */
	private void toTop(int ref){ResourceFile68k f=files.remove(ref);if(f!=null)files.put(ref,f);}

	void closeResFile(int ref) throws M68kCpuException {
		followGuest();
		if(ref==applicationRefNum||ref==systemRefNum||ref==0){setError(NO_ERR);return;}
		// Closing the current file makes the next file down the chain current (the ROM follows the map's mNext), not the
		// application's: some programs open auxiliary art and sound files, then preferences, close the
		// preferences and looks its colour pictures up from there - with the application current they were out of reach.
		int next=applicationRefNum;if(current==ref){List<ResourceFile68k> c=chain(false);if(c.size()>1)for(Map.Entry<Integer,ResourceFile68k> e:files.entrySet())if(e.getValue()==c.get(1)){next=e.getKey();break;}}
		if(raisedByProgram.remove(ref)&&files.containsKey(ref)){belowApplication.add(ref);backToLoader(ref);if(current==ref)setCurrent(next);relinkMaps();setError(NO_ERR);return;} // the loader's file stays open for its owner
		if(pinnedFiles.contains(ref)){backToLoader(ref);if(current==ref)setCurrent(next);setError(NO_ERR);return;} // an extension's file never closes, but the program's current file moves on
		ResourceFile68k f=files.remove(ref);belowApplication.remove(ref);detached.remove(ref);closedByLoader.remove(ref);reopenedFromLoader.remove(ref);
		if(f==null){setError(RES_FILE_NOT_FOUND);return;}
		if(f.dirty)try{flush(f);}catch(IOException e){}
		readOnlyRefs.remove(ref);if(tb.files!=null)tb.files.fileManager.resourceFileClosed(ref);
		for(ResourceFile68k.Resource r:f.ordered)if(r.handle!=0){byHandle.remove(r.handle);if(!pinnedHandles.contains(r.handle))tb.api.disposeHandleAndFree(r.handle);r.handle=0;}
		if(current==ref)setCurrent(next);
		disposeMap(f);relinkMaps();tb.releaseFileReference(ref);
		setError(NO_ERR);
	}

	private boolean createResFile(M68kCpu cpu,boolean hierarchical) throws M68kCpuException {
		File file=null;
		try{
			if(!hierarchical){int name=pop(cpu);file=tb.files.fileForClassicPath(tb.readPascal(name));}
			else{int name=memory.readLong(cpu.a[7]),dirId=memory.readLong(cpu.a[7]+4),vRef=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;file=tb.files.fileFor(vRef,dirId,tb.readPascal(name));}
		}catch(IOException e){file=null;}
		createFile(file);return true;
	}

	/** Creates an empty resource fork for the file (CreateResFile/HCreateResFile/FSpCreateResFile); sets ResErr. */
	void createFile(File file) throws M68kCpuException {
		if(file==null){setError(FNF_ERR);return;}
		// a folder has no resource fork: an FSSpec FSMakeFSSpec left naming the parent folder (dirNFErr) is no file to make
		// (UxCardToPICS went on with noErr and then reported "Can't open file" instead of "Can't create file")
		if(file.isDirectory()){setError(-1302);return;} // notAFileErr
		if(!Sandbox.allowFile(file,Sandbox.Access.WRITE,tb.name)){setError(PERM_ERR);return;}
		try{
			if(!file.exists())java.nio.file.Files.write(file.toPath(),new byte[0]);
			byte[] existing=ResourceFile68k.readForkBytes(file,null);
			// a fork that holds a map already is left alone with dupFNErr (Inside Macintosh: More Toolbox, CreateResFile): Disk Copy 6.3
			// calls HCreateResFile on its prefs file at every start and took noErr for "new prefs" - licence again, settings lost
			if(existing!=null&&existing.length>=16){setError(-48);return;}
			ResourceFile68k rf=new ResourceFile68k(file);rf.write();setError(NO_ERR);
		}catch(IOException e){setError(OP_WR_ERR);}
	}

	private boolean addResource(M68kCpu cpu) throws M68kCpuException {
		int name=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4),type=memory.readLong(cpu.a[7]+6),h=memory.readLong(cpu.a[7]+10);cpu.a[7]+=14;
		ResourceFile68k f=files.get(current);
		if(f==null||h==0||byHandle.containsKey(h)){setError(ADD_RES_FAILED);return true;}
		byte[] data=tb.api.handleContents(h);if(data==null)data=new byte[0];
		ResourceFile68k.Resource r=new ResourceFile68k.Resource(type,id,name==0?null:resourceName(name),0,data);
		r.handle=h;r.changed=true;f.add(r);f.dirty=true;byHandle.put(h,r);
		tb.api.setHandleState(h,tb.api.handleState(h)|0x20);
		syncMap(f);relinkMaps();
		setError(NO_ERR);return true;
	}

	private void update(ResourceFile68k f) throws M68kCpuException {
		for(ResourceFile68k.Resource r:f.ordered)if(r.changed)captureHandle(r);
		// The built-in System file takes changes for as long as the program runs and is never written (Font/DA Mover's copies
		// into the System: ChangedResource/UpdateResFile answering permErr ended it with "a serious problem")
		if(f==system){setError(NO_ERR);return;}
		if(!f.writable){setError(PERM_ERR);return;}
		try{flush(f);setError(NO_ERR);}catch(IOException e){setError(OP_WR_ERR);}
		// the map in memory describes the fork as just written: THINK C 5 puts offsets of its own into it before
		// UpdateResFile and reads the lengths of unloaded resources from the fork at the map's offsets afterwards
		syncMap(f);relinkMaps();
	}
	private void flush(ResourceFile68k f) throws IOException {if(f.stack!=null){storeInStack(f);return;}if(f.file==null||!f.writable)return;if(!Sandbox.allowFile(f.file,Sandbox.Access.WRITE,tb.name))throw new IOException("write not allowed");f.write();tb.files.fileManager.resourceForkRewritten(f.file);}
	void flushAll(){for(ResourceFile68k f:files.values()){if(f.stack!=null){storeInStack(f);continue;}if(f.dirty&&f.writable&&f.file!=null&&Sandbox.allowFile(f.file,Sandbox.Access.WRITE,tb.name))try{for(ResourceFile68k.Resource r:f.ordered)if(r.changed)captureHandle(r);f.write();}catch(Exception ignored){}}}
	/** XCMD mode, after each external call: what it added to or changed in a stack's resource file goes into the stack, as
	 *  HyperCard's open stack has it at once (an external need not call UpdateResFile: MtMakeIcon's ICON is a button icon next). */
	void storeStackFiles(){
		for(ResourceFile68k f:new ArrayList<ResourceFile68k>(files.values()))if(f.stack!=null)storeInStack(f);
		if(tb.files!=null)tb.files.fileManager.syncStackShadows(); // stacks named by path (StackFile68k)
	}
	/** A stack's resource file ({@link Mac68kResourceManager#forStack}) is the stack's resources: changes go there, never into the HyperCard file it was converted from. */
	private void storeInStack(ResourceFile68k f){
		boolean pending=f.dirty;for(ResourceFile68k.Resource r:f.ordered)if(r.changed){pending=true;break;}
		if(!pending)return;
		try{if(Mac68kResourceManager.storeChanges(f,r->{try{captureHandle(r);}catch(M68kCpuException e){}return r.fileData();})&&Mac68kApi.TRACE)System.err.println("[res] stored into stack "+f.stack.name);}
		catch(RuntimeException e){System.err.println("[res] could not store into stack "+f.stack.name+": "+e);}
	}

	/** HyperZebra's private _ResourceDispatch selector ('HZD' + 'dcmp' id) used by the System file's 'dcmp' stubs. */
	static final int DCMP_SELECTOR=0x485a4400;
	/** Private _ResourceDispatch selectors of the System's WDEF 0 ('HZW') and CDEF 0/1 ('HZC' + id) stubs (SystemResources68k.nativeDefStub). */
	static final int WDEF_SELECTOR=0x485a5700,CDEF_SELECTOR=0x485a4300;
	/** Private _ResourceDispatch selector of the System's MDEF 0 stub ('HZM', SystemResources68k.nativeMdefStub). */
	static final int MDEF_SELECTOR=0x485a4d00;
	/** Debug aid: -Dhz68k.dcmptrace=true logs where the System 'dcmp' stubs expand data (programs that load their own segments). */
	private static final boolean DCMP_TRACE=Boolean.getBoolean("hz68k.dcmptrace");
	/**
	 * Debug aid: -Dhz68k.segtrace=hexprefix[,hexprefix]: instruction-traces (pctrace) whatever the 'dcmp' stubs expand when it begins
	 * with one of those byte strings, wherever the program's own segment loader puts it (the System 7.5 Finder moves its
	 * segments around); a range is dropped once something else is expanded over it.
	 */
	private static final java.util.List<String> SEGMENT_TRACE=new java.util.ArrayList<String>();
	static{for(String t:System.getProperty("hz68k.segtrace","").split(","))if(!t.trim().isEmpty())SEGMENT_TRACE.add(t.trim().toLowerCase());}
	private static final java.util.List<int[]> tracedSegments=new java.util.ArrayList<int[]>();
	static boolean tracingSegments(){return !SEGMENT_TRACE.isEmpty();}
	static synchronized void followSegments(int at,byte[] data){
		int end=at+data.length;boolean changed=false;
		for(java.util.Iterator<int[]> it=tracedSegments.iterator();it.hasNext();){int[] r=it.next();if(r[0]<end&&at<r[1]){it.remove();changed=true;}}
		String head=java.util.HexFormat.of().formatHex(data,0,Math.min(32,data.length));
		for(int i=0;i<SEGMENT_TRACE.size();i++)if(head.startsWith(SEGMENT_TRACE.get(i))){tracedSegments.add(new int[]{at,end,i});changed=true;System.err.printf("[segtrace] #%d at $%X-$%X%n",i,at,end);}
		if(changed){StringBuilder sb=new StringBuilder();for(int[] r:tracedSegments){if(sb.length()>0)sb.append(',');sb.append(Integer.toHexString(r[0])).append('-').append(Integer.toHexString(r[1]));}M68kCpu.setPcTrace(sb.toString());}
	}
	private boolean resourceDispatch(M68kCpu cpu) throws M68kCpuException {
		if((cpu.d[0]&~0xff)==DCMP_SELECTOR)return runDecompressor(cpu,cpu.d[0]&0xff);
		if((cpu.d[0]&~0xff)==WDEF_SELECTOR||(cpu.d[0]&~0xff)==CDEF_SELECTOR){ // (varCode, target, message, param): LONGINT under the stub's return address
			int sp=cpu.a[7],param=memory.readLong(sp+4),message=(short)memory.readWord(sp+8),target=memory.readLong(sp+10),varCode=(short)memory.readWord(sp+14);
			int result=(cpu.d[0]&~0xff)==WDEF_SELECTOR?tb.windows.standardWdef(varCode,target,message,param):tb.controls.standardCdef(cpu.d[0]&0xff,varCode,target,message,param);
			if(Mac68kApi.TRACE)System.err.printf("[68K] %s 0 variant %d target $%x message %d param $%x -> %d%n",(cpu.d[0]&~0xff)==WDEF_SELECTOR?"WDEF":"CDEF",varCode,target,message,param,result);
			memory.writeLong(sp+16,result);return true;}
		if(cpu.d[0]==MDEF_SELECTOR){ // (message, theMenu, menuRect, hitPt, whichItem) under the stub's return address
			int sp=cpu.a[7];
			tb.menus.standardMdef((short)memory.readWord(sp+20),memory.readLong(sp+16),memory.readLong(sp+12),memory.readLong(sp+8),memory.readLong(sp+4));
			if(Mac68kApi.TRACE)System.err.printf("[68K] MDEF 0 message %d menu $%x%n",(short)memory.readWord(sp+20),memory.readLong(sp+16));
			return true;}
		int selector=cpu.d[0]&0xf;
		switch(selector){
			case 0:{ // private GetMap(refNum): Handle - the file's in-memory map (the Finder pairs it with RsrcMapEntry)
				int ref=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;ResourceFile68k f=files.get(ref);
				memory.writeLong(cpu.a[7],f==null?0:f.mapHandle);setError(f==null||f.mapHandle==0?RES_FILE_NOT_FOUND:NO_ERR);return true;}
			case 1:{ // ReadPartialResource(handle, offset, buffer, count)
				int count=memory.readLong(cpu.a[7]),buffer=memory.readLong(cpu.a[7]+4),offset=memory.readLong(cpu.a[7]+8),h=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16;
				ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}
				byte[] rd=r.data();int n=Math.max(0,Math.min(count,rd.length-offset));if(n>0)memory.load(buffer,java.util.Arrays.copyOfRange(rd,offset,offset+n));setError(n<count?EOF_ERR:NO_ERR);return true;}
			case 2:{int count=memory.readLong(cpu.a[7]),buffer=memory.readLong(cpu.a[7]+4),offset=memory.readLong(cpu.a[7]+8),h=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16;
				ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}
				byte[] rd=r.data();if(offset+count>rd.length)rd=java.util.Arrays.copyOf(rd,offset+count);System.arraycopy(memory.readBytes(buffer,count),0,rd,offset,count);r.setData(rd);r.changed=true;setError(NO_ERR);return true;}
			case 3:{int size=memory.readLong(cpu.a[7]),h=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;ResourceFile68k.Resource r=byHandle.get(h);if(r==null){setError(RES_NOT_FOUND);return true;}r.setData(java.util.Arrays.copyOf(r.data(),Math.max(0,size)));r.changed=true;setError(NO_ERR);return true;}
			case 0xa:{ // GetNextFOND(fondHandle): Handle - the FOND after that one in the resource chain (NIL = the first), NIL after the last.
				// Aldus FreeHand 4 walks the fonts this way at start-up and loops until it gets NIL.
				int h=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
				List<ResourceFile68k.Resource> fonds=entries(0x464f4e44,false);int next=0;
				if(h!=0){ResourceFile68k.Resource current=byHandle.get(h);next=current==null?fonds.size():fonds.indexOf(current)+1;}
				memory.writeLong(cpu.a[7],handleFor(next>=0&&next<fonds.size()?fonds.get(next):null));setError(NO_ERR);return true;}
			case 0xe:{ // selector -2, private: open a resource file by FSSpec without adding it to the search chain (spec, permission) : refNum.
				// QuickTime's instrument components open "QuickTime Musical Instruments" this way to read their sample data.
				int permission=memory.readByte(cpu.a[7]),spec=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;int ref=-1;
				try{java.io.File f=tb.files.fileForSpec(spec);if(f!=null&&f.isFile())ref=openFileNoChain(f);}catch(java.io.IOException e){ref=-1;}
				memory.writeWord(cpu.a[7],ref);setError(ref<0?RES_FILE_NOT_FOUND:NO_ERR);return true;}
			default:setError(-4);return true;
		}
	}
	/**
	 * The body of SystemResources68k.dcmpStub: the stack holds the caller's D0, the stub's return address and
	 * (workingSize, working, destination, source). The data is expanded into the destination, D0 restored and the four
	 * arguments removed, so the stub's RTS returns to the caller as the Pascal convention requires.
	 */
	/**
	 * A compressed resource whose 'dcmp' is not one of the built-in 0-2 (a program's own decompressor, as ResEdit carries):
	 * the program's 'dcmp' code runs as dcmp(source after the 18-byte header, destination, working, workingSize), the
	 * convention the System's own 'dcmp' stubs follow. Null when there is no such 'dcmp' or it fails.
	 */
	private byte[] guestDecompress(byte[] data) throws M68kCpuException {
		int type=(data[6]&0xff)<<8|(data[7]&0xff),length=(data[8]&0xff)<<24|(data[9]&0xff)<<16|(data[10]&0xff)<<8|(data[11]&0xff);
		int id=(short)(type==0x0901?(data[12]&0xff)<<8|(data[13]&0xff):(data[14]&0xff)<<8|(data[15]&0xff));
		if(length<0||length>64*1024*1024)return null;
		ResourceFile68k.Resource dcmp=find(0x64636d70,id);if(dcmp==null||system!=null&&system.byKey.get(dcmp.key())==dcmp)return null;
		int code=handleFor(dcmp);if(code==0)return null;if(memory.readLong(code)==0)loadIfResource(code);tb.api.setHandleState(code,tb.api.handleState(code)|0x80);
		M68kCpu cpu=tb.cpu;int destination=0,working=0;int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{
			// In place, as the Resource Manager expands: one block of the expanded size plus the header's expansion slack, the
			// compressed data (after the 18-byte header) at its end and the output growing from its start. A 'dcmp' may check
			// that the output never overtakes the input (REALbasic's 'dcmp' 200 calls _Debugger when it does, which it did at
			// once when the output had a block of its own above the input).
			int packed=data.length-18,slack=type==0x0801?(data[13]&0xff)*256:0;
			int size=Math.max(length+slack,packed)+256;
			destination=memory.allocate(size,2);int source=destination+size-packed;memory.load(source,java.util.Arrays.copyOfRange(data,18,data.length));
			int workingSize=length+4096;working=memory.allocate(workingSize,2);
			cpu.a[7]-=INTERRUPT_SLACK;cpu.pushLong(source);cpu.pushLong(destination);cpu.pushLong(working);cpu.pushLong(workingSize);
			cpu.callPascal(memory.readLong(code),16);
			if(Mac68kApi.TRACE||DCMP_TRACE)System.err.printf("[68K] program 'dcmp' %d expanded a resource to %d bytes%n",id,length);
			return memory.readBytes(destination,length);
		}catch(M68kException e){System.err.println("[68K] program 'dcmp' "+id+" failed: "+e.getMessage());return null;}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);
			if(destination!=0)memory.free(destination);if(working!=0)memory.free(working);}
	}
	private static final int INTERRUPT_SLACK=256;

	private boolean runDecompressor(M68kCpu cpu,int id) throws M68kCpuException {
		int sp=cpu.a[7],savedD0=memory.readLong(sp),ret=memory.readLong(sp+4),destination=memory.readLong(sp+16),source=memory.readLong(sp+20);
		try{
			int header=source-18;byte[] head=header>=0?memory.readBytes(header,18):null;
			int length=head!=null&&MacResourceDecompressor.isCompressed(head)?((head[8]&0xff)<<24|(head[9]&0xff)<<16|(head[10]&0xff)<<8|(head[11]&0xff)):-1;
			int start=length>=0?header:source,available=memory.size()-start;
			int span=(int)Math.min(available,length>=0?(long)length*3+4096:65536L);
			byte[] out=length>=0?MacResourceDecompressor.decompress(memory.readBytes(start,span))
				:MacResourceDecompressor.expandBody(id,memory.readBytes(start,span));
			memory.load(destination,out);setError(NO_ERR);
			if(Mac68kApi.TRACE||DCMP_TRACE)System.err.printf("[68K] 'dcmp' %d expanded %d bytes to $%x (%s)%n",id,out.length,destination,java.util.HexFormat.of().formatHex(out,0,Math.min(16,out.length)));
			if(!SEGMENT_TRACE.isEmpty())followSegments(destination,out);
		}catch(java.io.IOException e){System.err.println("[68K] 'dcmp' "+id+" at $"+Integer.toHexString(source)+": "+e.getMessage());setError(-186);} // CantDecompress
		cpu.d[0]=savedD0;memory.writeLong(sp+20,ret);cpu.a[7]=sp+20;
		return true;
	}
}

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Display Manager's display modes ($ABEB, selectors 0x11 DMSetDisplayMode, 0x12 DMCheckDisplayMode, 0x36
 * DMNewDisplayModeList, 0x37 DMGetIndexedDisplayModeFromList, 0x2C DMDisposeList, 0x3E DMGetDisplayMode; the other selectors are
 * Toolbox68k.displayDispatch's). Every program has a screen of its own here (MacAppHost.screenBounds), so a game that switches the
 * display mode (RezLib, which Airburst uses, GameSprockets-less games) changes the size of that screen only: the modes offered
 * are the usual sizes of the time up to the program's full screen, each at 8, 16 and 32 bits, as PpcGameSprockets offers them to
 * DrawSprocket. A mode's ID is {@link #FIRST_MODE_ID} + its index (sizes ascending); the depth modes are $80 (1 bit) ... $85 (32 bits).
 * Structures after Displays.h / Video.h (Universal Interfaces 3.4).
 */
final class DisplayManager68k {
	static final int FIRST_MODE_ID=0x100;
	static final int NO_ERR=0,GEN_ERR=-6220,DISPLAY_NOT_FOUND=-6228,PARAM_ERR=-50;
	private static final int[] DEPTHS={8,16,32};
	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** List tokens handed out by DMNewDisplayModeList. */
	private final Set<Integer> lists=new HashSet<Integer>();

	DisplayManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/**
	 * The sizes offered: the usual ones that fit the full screen, then the full screen itself. 640 x 400 (a DrawSprocket size) is left
	 * out, as Apple's multiple-scan displays list none: WaterRace keeps only modes larger in both width and height than the one before,
	 * so 640 x 400 hid 640 x 480 from its resolution menu.
	 */
	private List<int[]> modes(){
		int[] full=tb.app==null?new int[]{640,480}:tb.app.fullScreen();
		List<int[]> sizes=new ArrayList<int[]>();
		for(int[] m:PpcGameSprockets.MODE_SIZES)if(m[0]<=full[0]&&m[1]<=full[1]&&!(m[0]==full[0]&&m[1]==full[1])&&!(m[0]==640&&m[1]==400))sizes.add(m);
		sizes.add(full);
		sizes.sort((a,b)->Long.compare((long)a[0]*a[1],(long)b[0]*b[1]));
		return sizes;
	}
	/** The index of the mode of the screen the program has now, or -1. */
	private int currentMode(List<int[]> modes){
		java.awt.Rectangle screen=MacAppHost.screenBounds();
		for(int i=0;i<modes.size();i++)if(modes.get(i)[0]==screen.width&&modes.get(i)[1]==screen.height)return i;
		return -1;
	}
	private int currentDepth() throws M68kCpuException {
		int[] f=tb.api.quickDraw().mainDeviceFrame();return f==null?32:f[2];
	}
	static int depthMode(int depth){return 0x80+Integer.numberOfTrailingZeros(Math.max(1,depth));}
	private static int depthOfMode(int value){return value>=0x80&&value<=0x85?1<<(value-0x80):value;}

	/** DMNewDisplayModeList(displayID, flags, reserved, VAR count, VAR list): the list is a token; its entries come from the modes. */
	int newModeList(int sp) throws M68kCpuException {
		int listPtr=memory.readLong(sp),countPtr=memory.readLong(sp+4),displayId=memory.readLong(sp+16);
		if(displayId!=1)return DISPLAY_NOT_FOUND;
		int token=memory.allocate(16,4);memory.writeLong(token,0x687a444d); // 'hzDM'
		lists.add(token);
		int count=modes().size();
		if(countPtr!=0)memory.writeLong(countPtr,count);
		if(listPtr!=0)memory.writeLong(listPtr,token);
		return NO_ERR;
	}
	/** DMDisposeList(list). */
	int disposeList(int sp) throws M68kCpuException {
		int token=memory.readLong(sp);
		if(lists.remove(token)){memory.free(token);java.util.Map<Integer,Integer> built=entries.remove(token);if(built!=null)for(int block:built.values())memory.free(block);}
		return NO_ERR;
	}

	// layout of the scratch block handed to the iterator. The switch infos and VPBlocks (42 bytes, packed) are contiguous arrays as the
	// real Display Manager builds them: RezLib reads only the first DMDepthInfoRec and indexes its two pointers by depth.
	private static final int ENTRY=0,SWITCH=32,RESOLUTION=48,TIMING=80,DEPTH_BLOCK=100,DEPTH_RECS=120,DEPTH_SWITCH=180,VP_BLOCKS=228,VP_SIZE=42,NAME=364,DISPLAY_INFO=396,SCRATCH=480;

	/** The entry blocks built for each list (list token -> mode index -> block); they stay until the list is disposed: the pointers in an entry point into its block. */
	private final java.util.Map<Integer,java.util.Map<Integer,Integer>> entries=new java.util.HashMap<Integer,java.util.Map<Integer,Integer>>();

	/**
	 * DMGetIndexedDisplayModeFromList(list, index, reserved, listIterator, userData): the iterator is called with the entry of mode
	 * {@code index} (0-based); without an iterator (RezLib passes none) the entry is copied to the buffer userData points to.
	 */
	int indexedMode(int sp) throws M68kException {
		int userData=memory.readLong(sp),callback=memory.readLong(sp+4),index=memory.readLong(sp+12),token=memory.readLong(sp+16);
		if(!lists.contains(token))return PARAM_ERR;
		List<int[]> modes=modes();
		if(index<0||index>=modes.size())return PARAM_ERR;
		java.util.Map<Integer,Integer> built=entries.get(token);
		if(built==null){built=new java.util.HashMap<Integer,Integer>();entries.put(token,built);}
		Integer block=built.get(index);
		if(block==null){block=buildEntry(modes,index);built.put(index,block);}
		if(callback!=0){
			M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
			cpu.pushLong(userData);cpu.pushLong(index);cpu.pushLong(block+ENTRY);
			try{cpu.callPascal(callback,12);}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
		}else if(userData!=0)memory.copy(block+ENTRY,userData,32);
		return NO_ERR;
	}
	private int buildEntry(List<int[]> modes,int index) throws M68kCpuException {
		int[] size=modes.get(index);int id=FIRST_MODE_ID+index,def=currentMode(modes);
		int p=memory.allocate(SCRATCH,4);
		int bestDepth=DEPTHS[DEPTHS.length-1];
		// VDSwitchInfoRec of the mode: the depth it comes up in and its ID
		writeSwitch(p+SWITCH,depthMode(bestDepth),id);
		// VDResolutionInfoRec
		memory.writeLong(p+RESOLUTION,0);memory.writeLong(p+RESOLUTION+4,id);memory.writeLong(p+RESOLUTION+8,size[0]);memory.writeLong(p+RESOLUTION+12,size[1]);
		memory.writeLong(p+RESOLUTION+16,60<<16);memory.writeWord(p+RESOLUTION+20,depthMode(bestDepth));memory.writeLong(p+RESOLUTION+22,0);memory.writeLong(p+RESOLUTION+26,0);
		// VDTimingInfoRec: a built-in, valid, safe mode ('decl' timing format); the one the program has now is the default
		int flags=1|2|8|(index==def?4:0)|(1<<10);
		memory.writeLong(p+TIMING,id);memory.writeLong(p+TIMING+4,0);memory.writeLong(p+TIMING+8,0x6465636c);memory.writeLong(p+TIMING+12,id);memory.writeLong(p+TIMING+16,flags);
		// DMDepthInfoBlockRec: the depths, each with its switch info and VPBlock
		memory.writeLong(p+DEPTH_BLOCK,DEPTHS.length);memory.writeLong(p+DEPTH_BLOCK+4,p+DEPTH_RECS);memory.writeLong(p+DEPTH_BLOCK+8,0);memory.writeLong(p+DEPTH_BLOCK+12,0);memory.writeLong(p+DEPTH_BLOCK+16,0);
		for(int i=0;i<DEPTHS.length;i++){
			int d=DEPTHS[i],rec=p+DEPTH_RECS+i*20,sw=p+DEPTH_SWITCH+i*16,vp=p+VP_BLOCKS+i*VP_SIZE;
			writeSwitch(sw,depthMode(d),id);
			memory.writeLong(vp,0);memory.writeWord(vp+4,(size[0]*d+7)/8);memory.writeWord(vp+6,0);memory.writeWord(vp+8,0);memory.writeWord(vp+10,size[1]);memory.writeWord(vp+12,size[0]);
			memory.writeWord(vp+14,0);memory.writeWord(vp+16,0);memory.writeLong(vp+18,0);memory.writeLong(vp+22,72<<16);memory.writeLong(vp+26,72<<16);
			memory.writeWord(vp+30,d<=8?0:16);memory.writeWord(vp+32,d);memory.writeWord(vp+34,d<=8?1:3);memory.writeWord(vp+36,d==8?8:d==16?5:8);memory.writeLong(vp+38,0);
			memory.writeLong(rec,sw);memory.writeLong(rec+4,vp);memory.writeLong(rec+8,0);memory.writeLong(rec+12,0);memory.writeLong(rec+16,0);
		}
		// DMDisplayModeListEntryRec (Displays.h): flags, switch info, resolution info, timing info, depth block, version one, name, display info
		memory.writeLong(p+ENTRY,0);memory.writeLong(p+ENTRY+4,p+SWITCH);memory.writeLong(p+ENTRY+8,p+RESOLUTION);memory.writeLong(p+ENTRY+12,p+TIMING);
		// the mode's name ("640 x 480"), as the Display Manager names the modes it lists: Ferazel's Wand copies it with a routine
		// that asserts on NIL and quit at its first mode; and the version one DMDisplayTimingInfoRec (version, attributes, relative
		// quality, relative default, 16 reserved longs), zeroed
		byte[] name=(size[0]+" x "+size[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		memory.writeByte(p+NAME,name.length);for(int i=0;i<name.length;i++)memory.writeByte(p+NAME+1+i,name[i]);
		memory.fill(p+DISPLAY_INFO,SCRATCH-DISPLAY_INFO,0);
		memory.writeLong(p+ENTRY+16,p+DEPTH_BLOCK);memory.writeLong(p+ENTRY+20,1);memory.writeLong(p+ENTRY+24,p+NAME);memory.writeLong(p+ENTRY+28,p+DISPLAY_INFO);
		return p;
	}
	private void writeSwitch(int p,int mode,int id) throws M68kCpuException {
		memory.writeWord(p,mode);memory.writeLong(p+2,id);memory.writeWord(p+6,0);memory.writeLong(p+8,0);memory.writeLong(p+12,0);
	}

	/** DMGetDisplayMode(gd, VAR VDSwitchInfoRec): the depth mode and the mode ID of the screen now, and its pixels. */
	int displayMode(int sp) throws M68kCpuException {
		int info=memory.readLong(sp);
		if(info==0)return NO_ERR;
		List<int[]> modes=modes();int cur=currentMode(modes);
		int[] f=tb.api.quickDraw().mainDeviceFrame();
		memory.writeWord(info,depthMode(currentDepth()));memory.writeLong(info+2,cur<0?0x80:FIRST_MODE_ID+cur);memory.writeWord(info+6,0);
		memory.writeLong(info+8,f==null?0:f[0]);memory.writeLong(info+12,0);
		return NO_ERR;
	}

	/**
	 * DMCheckDisplayMode(gd, modeID, depthMode, VAR switchFlags, reserved, VAR modeValid). A valid mode needs no confirmation
	 * (kNoSwitchConfirmBit 0: WaterRace takes only modes with that bit, not modeValid); a depth the mode lacks sets kDepthNotAvailableBit 1.
	 */
	int checkMode(int sp) throws M68kCpuException {
		int validPtr=memory.readLong(sp),flagsPtr=memory.readLong(sp+8),depthMode=memory.readLong(sp+12),id=memory.readLong(sp+16);
		boolean known=modeIndex(id)>=0,depthOk=depthMode==0||supported(depthOfMode(depthMode)),valid=known&&depthOk;
		if(flagsPtr!=0)memory.writeLong(flagsPtr,(valid?1:0)|(known&&!depthOk?2:0));
		if(validPtr!=0)memory.writeByte(validPtr,valid?1:0);
		return NO_ERR;
	}
	private int modeIndex(int id){int i=id-FIRST_MODE_ID;return i>=0&&i<modes().size()?i:-1;}
	private static boolean supported(int depth){for(int d:DEPTHS)if(d==depth)return true;return depth==1||depth==2||depth==4;}

	/** DMSetDisplayMode(gd, modeID, VAR depthMode, reserved, displayState): the program's screen takes the mode's size and the depth. */
	int setMode(int sp) throws M68kException {
		int depthPtr=memory.readLong(sp+8),id=memory.readLong(sp+12);
		List<int[]> modes=modes();int index=modeIndex(id);
		if(index<0)return GEN_ERR;
		int wanted=depthPtr==0?0:depthOfMode(memory.readLong(depthPtr));
		if(wanted!=0&&!supported(wanted))return GEN_ERR;
		int[] size=modes.get(index);
		if(tb.app==null||!tb.app.setScreenMode(size[0],size[1]))return GEN_ERR;
		if(wanted!=0&&!tb.api.quickDraw().switchMainDepth(wanted))return GEN_ERR;
		if(depthPtr!=0)memory.writeLong(depthPtr,depthMode(currentDepth()));
		return NO_ERR;
	}
}

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stateful, memory-safe subset of classic black-and-white QuickDraw. */
final class QuickDraw68k {
	private static final int PPAT_TYPE=0x70706174;
	private static final int CLUT_TYPE=0x636c7574;
	private static final int NO_ERR=0,PARAM_ERR=-50;
	private static final int C_RANGE_ERR=-153,C_PROTECT_ERR=-154,C_DEV_ERR=-155,C_DEPTH_ERR=-157;
	private static final int PIXELS_PURGEABLE=1<<6,PIXELS_LOCKED=1<<7;
	private static final int GRAF_PORT_SIZE=108;
	private static final int PORT_BITS=2,GRAF_VARS=8,PORT_RECT=16,VIS_RGN=24,CLIP_RGN=28;
	private static final int BK_PAT=32,FILL_PAT=40,PN_LOC=48,PN_SIZE=52,PN_MODE=56;
	private static final int PN_PAT=58,PN_VIS=66,TX_FONT=68,TX_FACE=70,TX_MODE=72;
	private static final int TX_SIZE=74,SP_EXTRA=76,FG_COLOR=80,BK_COLOR=84,COLR_BIT=88;
	private final M68kMemory memory;
	private final Mac68kApi macApi;
	private final Map<Integer,PortState> ports=new HashMap<Integer,PortState>();
	private int currentPort;
	private int qdGlobals;
	/** Where the QuickDraw globals are now (thePort's address), 0 before InitGraf. */
	int globalsPointer(){return liveGlobals();}
	/**
	 * The QuickDraw globals as the ROM finds them on every call: through 0(A5). InitGraf's pointer stays right while the program
	 * keeps its A5, but code may borrow an A5 world for one InitGraf - Fontographer 4's installer reads the screen size by pointing
	 * A5 at a stack frame, calling InitGraf(@local) and putting its own A5 back. Keeping that pointer made every later SetPort
	 * write thePort into the dead frame (over a caller's saved A4: the install list went to the wrong object, "error -99").
	 * An empty or implausible 0(A5) keeps the last globals (a program before its own InitGraf, see forgetExtensionGlobals).
	 */
	private int liveGlobals(){
		if(qdGlobals==0)return 0;
		Toolbox68k tb=macApi.application();M68kCpu cpu=tb==null?null:tb.cpu;if(cpu==null)return qdGlobals;
		int a5=cpu.a[5];if(a5<=0||(a5&1)!=0||a5+4>memory.size())return qdGlobals;
		try{int g=memory.readLong(a5);if(g!=qdGlobals&&g>=0x1000&&(g&1)==0&&g+4<=memory.size())qdGlobals=g;}catch(M68kCpuException e){}
		return qdGlobals;
	}
	/** OpenRgn state: the port whose rgnSave is set (drawing in other ports does not define the region), the lines drawn
	 *  so far (h0,v0,h1,v1 each) and the framed shapes, XORed together the way the ROM collects inversion points. */
	private boolean regionOpen;private int openRegionPort;
	private final List<int[]> openRegionLines=new ArrayList<int[]>();
	private QdRegion openRegionShape;
	private int openPolygon;
	private final List<QdPoint> polygonPoints=new ArrayList<QdPoint>();
	private int openPicture;
	private Rect pictureFrame;
	private ByteArrayOutputStream pictureData;
	private boolean playingPicture;
	/** The picture that was recording when the current DrawPicture started (0: none). Its recording is paused while the other
	 *  picture plays (the played picture is recorded as one image), but a picture the program opens meanwhile records: SuperPaint
	 *  3.0's bitsProc turns each bitmap of a document it reads into a draw-layer picture that way (they came out empty). */
	private int playbackRecording;
	/** Filled while a picture plays for the Picture Utilities (GetPictInfo): what the picture is made of. */
	static final class PictureSurvey {
		int depth,textCount,lineCount,rectCount,rRectCount,ovalCount,arcCount,polyCount,regionCount,bitMapCount,pixMapCount,commentCount;
		int hRes=72<<16,vRes=72<<16;int[] colorTable; // the first indexed PixMap's colours, in table order
		int[] frame; // picFrame top, left, bottom, right
		java.awt.image.BufferedImage image;
		void count(int opcode){
			if(opcode>=0x20&&opcode<=0x23)lineCount++;else if(opcode>=0x28&&opcode<=0x2b)textCount++;
			else if(opcode>=0x30&&opcode<=0x3f)rectCount++;else if(opcode>=0x40&&opcode<=0x4f)rRectCount++;else if(opcode>=0x50&&opcode<=0x5f)ovalCount++;
			else if(opcode>=0x60&&opcode<=0x6f)arcCount++;else if(opcode>=0x70&&opcode<=0x7f)polyCount++;else if(opcode>=0x80&&opcode<=0x8f)regionCount++;
			else if(opcode==0xa0||opcode==0xa1)commentCount++;}
	}
	private PictureSurvey survey;
	/**
	 * What a picture holds, for GetPictInfo: it is played into a 32-bit offscreen (so its colours can be counted) while
	 * the opcodes are tallied; the depth is the deepest PixMap (1 for bitmaps or a picture without any).
	 */
	PictureSurvey surveyPicture(byte[] picture) throws M68kCpuException {
		PictureSurvey s=new PictureSurvey();survey=s;
		if(picture.length>=10)s.frame=new int[]{(short)((picture[2]&0xff)<<8|picture[3]&0xff),(short)((picture[4]&0xff)<<8|picture[5]&0xff),(short)((picture[6]&0xff)<<8|picture[7]&0xff),(short)((picture[8]&0xff)<<8|picture[9]&0xff)};
		try{s.image=renderPictureAtDepth(picture,32);}finally{survey=null;}
		if(s.depth==0)s.depth=1;return s;
	}
	private int activePixPat;
	/** Seeds handed out by GetCTSeed start at minSeed (1024) as in Color QuickDraw: smaller seeds mark the standard tables (seed = clut
	 *  resource ID), which QuickTime's SetImageDescriptionCTable stores by ID alone - a GIF's palette seeded 1 came back as the 1-bit table. */
	private int colorTableSeed=1024;
	private int quickDrawError;
	private final Map<Integer,BitSet> protectedEntries=new HashMap<Integer,BitSet>();
	private final Map<Integer,BitSet> reservedEntries=new HashMap<Integer,BitSet>();
	private final Map<Integer,GWorldState> gWorlds=new HashMap<Integer,GWorldState>();
	/** Whether {@code port} is an offscreen GWorld (made by NewGWorld), not a window's port. */
	boolean isGWorld(int port){return gWorlds.containsKey(port);}
	private final Map<Integer,PixelMapState> offscreenPixMaps=new HashMap<Integer,PixelMapState>();
	private final Map<Integer,PixelMapState> offscreenPixMapData=new HashMap<Integer,PixelMapState>();
	private final Map<Integer,DynamicDeviceState> dynamicDevices=new HashMap<Integer,DynamicDeviceState>();
	private int currentGDevice;
	private int mainGDevice,mainDevicePixMap;
	/** Depth of the main screen device hosted programs see (1, 4, 8, 16 or 32; PCARD preference "screenDepth68k", -Dhz68k.depth for scripts, which also takes 2). */
	static volatile int screenDepth=parseScreenDepth(System.getProperty("hz68k.depth"),32);
	static int parseScreenDepth(String value,int fallback){if(value==null)return fallback;try{int d=Integer.parseInt(value.trim());return d==1||d==2||d==4||d==8||d==16||d==32?d:fallback;}catch(NumberFormatException e){return fallback;}}
	/**
	 * Largest screen rowBytes a 68K program can be given. Real Macintosh screens never exceeded it (a 1152-pixel monitor
	 * at 32 bits is 4608), and programs rely on it: HyperCard 2.2's dissolve effect (CODE 32) steps its screen pointer by
	 * 4*rowBytes kept in a word and added with ADDA.W (move.w rowBytes,d6; asl.w #2,d6; adda.w d6,a1) - with a 2560-pixel
	 * host screen at 32 bits (10240 bytes a row) the step is negative and the new card lands above the window
	 * (otherwise card images can remain stale). So the screen hosted programs see is no wider than this allows at the
	 * preferred depth ({@link #maxScreenWidth}): a wide host display shows up as a screen that ends before its right
	 * edge (1920 pixels at 32 bits, see {@link #maxScreenWidth}) rather than as a shallower one, keeping full colour.
	 */
	static final int MAX_SCREEN_ROW_BYTES=8191;
	/**
	 * Pixels of a screen-sized PixMap that a program may add around the screen before its own rowBytes arithmetic has to
	 * hold: {@code width * pixelSize} is a word to 68K code, and it wraps at 65536. ClarisWorks 2.1 sizes the buffer of a
	 * zoomed document window at screen width + 44 and computes ((width * pixelSize + 31) / 32) * 4 in 16 bits, so a
	 * 2046-pixel screen at 32 bits gave it a 2090-pixel buffer, 66880 wrapped to 1344, and rowBytes 168 instead of 8360 -
	 * a PixMap far too narrow for its own bounds, which its spreadsheet then drew through. No real Macintosh screen came
	 * near the wrap (the widest was 1152 pixels), so the emulated one stays below it with room for such slack.
	 */
	static final int SCREEN_WIDTH_HEADROOM=128;
	/** Widest screen whose rowBytes (rounded up to even, as PixMaps are made) stays within {@link #MAX_SCREEN_ROW_BYTES} and whose
	 *  width leaves {@link #SCREEN_WIDTH_HEADROOM} pixels below the word wrap of {@code width * depth}: 1920 pixels at 32 bits, 3968 at 16, 8064 at 8.
	 *  Always even, like every real Macintosh screen: stacks centre their card window with (item 3 of the screenRect - width) / 2 and HyperCard
	 *  refuses a fractional location (some openStack handlers report "integer expected" on a 2047-pixel screen). */
	static int maxScreenWidth(int depth){return maxScreenWidth(depth,false);}
	/** {@code powerPC}: the widest screen for a PowerPC program. Its code keeps rowBytes and {@code width * depth} in 32 bits,
	 *  so neither 68K limit applies; only the PixMap format's own does (rowBytes is 14 bits, at most {@link #PPC_MAX_SCREEN_ROW_BYTES}):
	 *  4094 pixels at 32 bits, 8190 at 16. */
	static int maxScreenWidth(int depth,boolean powerPC){
		int bits=Math.max(1,depth);
		if(powerPC)return PPC_MAX_SCREEN_ROW_BYTES*8/bits&~1;
		return Math.min((MAX_SCREEN_ROW_BYTES&~1)*8/bits,0x10000/bits-SCREEN_WIDTH_HEADROOM)&~1;
	}
	/** Largest rowBytes a PixMap can hold (the top two bits of the word are flags): the screen limit for PowerPC programs. */
	static final int PPC_MAX_SCREEN_ROW_BYTES=0x3ffe;
	/** Whether this VM's program runs PowerPC code (its screen may be wider, see {@link #maxScreenWidth(int,boolean)}). */
	private boolean powerPcProgram(){Toolbox68k tb=macApi.application();return tb!=null&&tb.app!=null&&tb.app.powerPC;}
	/**
	 * rowBytes of the pixels NewGWorld allocates for a 68K program: 32-Bit QuickDraw's QDOffscreen gives every row one long of slack
	 * for realigning in UpdateGWorld, ((width*pixelSize+30)/32+1)*4 (GWorld.a allocateBuffer; the 1993 Cyclone ROMs also round it
	 * up to 16 bytes). Color VETTE! (1991) hard-codes 260 for its 512-pixel 4-bit world and draws the road with that stride, so
	 * tight 256-byte rows sheared the whole game screen.
	 */
	static int gWorldRowBytes(int width,int depth){return (int)((((long)width*depth+30)/32+1)*4);}
	/**
	 * rowBytes of the pixels NewGWorld allocates: 68K programs {@link #gWorldRowBytes}; classic PowerPC programs that rounded up
	 * to 16 bytes, as Mac OS 8/9's QuickDraw gives it; Carbon programs Mac OS X's (tight, 32-bit rows padded below). Iconographer
	 * asks for a world 16 bytes narrower than its icon (half as wide for 16- and 32-byte rows), so that the padding brings
	 * rowBytes to the icon's own, then widens the bounds and restrides the rows: with tight rows its 8-bit masks ran 512 bytes
	 * past their pixels over their own PixMap (bounds 0,0,0,0 - Generate Mask did nothing).
	 */
	private int ownGWorldRowBytes(int width,int depth){
		if(!powerPcProgram())return gWorldRowBytes(width,depth);
		if(carbonPowerPcProgram())return (width*depth+15)/16*2;
		return (gWorldRowBytes(width,depth)+15)&~15;
	}
	/** A PowerPC program that imports CarbonLib ('cbon' alone tells nothing: every PowerPC program sees CarbonLib 1.6 installed). */
	private boolean carbonPowerPcProgram(){Toolbox68k tb=macApi.application();return tb!=null&&tb.app!=null&&tb.app.powerPC&&tb.app.ppc!=null&&tb.app.ppc.libraries.carbon!=null&&tb.app.ppc.libraries.carbon.carbonProgram();}
	private boolean powerPcScreen(){Toolbox68k tb=macApi.application();return tb!=null&&tb.app!=null&&tb.app.powerPC;}
	/**
	 * The depth preference as it stood when this VM first looked at the screen. The screen's width follows from it, and
	 * both must hold still while a program runs (it caches screenBits.bounds and the device record): a preference changed
	 * in the meantime applies to the next program, as the preferences dialog says.
	 */
	private int preferredScreenDepth;
	private int preferredScreenDepth(){if(preferredScreenDepth==0)preferredScreenDepth=screenDepth;return preferredScreenDepth;}
	/** Fixes this VM's screen depth before the main device exists (MacApp68k lowers it for programs that predate direct devices). */
	void setPreferredScreenDepth(int depth){preferredScreenDepth=depth;}
	/** The main device's depth: the preference, unless its PixMap would not fit the guest heap (externals run in a
	 *  16 MB memory; a 32-bit PixMap of a large display alone is that big) - then the deepest depth that leaves room. */
	private int hostedScreenDepth(){
		Rect screen=screenRect();int width=Math.max(1,screen.right-screen.left);long pixels=(long)width*Math.max(1,screen.bottom-screen.top);
		int preferred=preferredScreenDepth(),depth=preferred;
		while(depth>1&&pixels*depth/8+1024*1024>memory.availableBytes())depth=depth==32?16:depth==16?8:depth==8?4:depth==4?2:1;
		if(depth!=preferred&&depth!=reportedScreenDepth){reportedScreenDepth=depth;System.err.println("[68K] screen depth "+preferred+" lowered to "+depth+" (a "+width+"x"+(screen.bottom-screen.top)+" device PixMap does not fit the guest memory)");}
		return depth;
	}
	private int reportedScreenDepth;

	QuickDraw68k(M68kMemory memory,Mac68kApi macApi) {this.memory=memory;this.macApi=macApi;smooth=new SmoothZoom(memory);}
	/** The smooth zoom's hi-res planes of this VM's pixel buffers (the host configures it; off unless the screen is shown magnified). */
	final SmoothZoom smooth;

	/** The traps that may run guest code (colour matching procedures); dispatched before {@link #handle}. */
	boolean handleWithCallbacks(M68kCpu cpu,int trap) throws M68kException {
		switch(trap) {
			case 0xa50:return seedCFill(cpu);
			case 0xa4f:return calcCMask(cpu);
			default:return false;
		}
	}
	boolean handle(M68kCpu cpu,int trap) throws M68kCpuException {
		switch(trap) {
			case 0x86d:case 0x86f:return initializePort(cpu);
			case 0x86e:return initializeGlobals(cpu);
			case 0x870:return convertPoint(cpu,true);
			case 0x871:return convertPoint(cpu,false);
			case 0x873:return setPort(cpu);
			case 0x874:return getPort(cpu);
			case 0x875:return setPortBits(cpu);
			case 0x876:return portSize(cpu);
			case 0x877:return movePortTo(cpu);
			case 0x878:return setOrigin(cpu);
			case 0x879:return setClip(cpu);
			case 0x87a:return getClip(cpu);
			case 0x87b:return clipRect(cpu);
			case 0x87c:return setPattern(cpu,BK_PAT);
			case 0x87d:return closePort(cpu);
			case 0x87e:return addSubPoint(cpu,true);
			case 0x87f:return addSubPoint(cpu,false);
			case 0x880:return setPoint(cpu);
			case 0x881:return equalPoint(cpu);
			case 0x862:return setLong(cpu,FG_COLOR);
			case 0x863:return setLong(cpu,BK_COLOR);
			case 0x864:return discard(cpu,2);
			case 0x883:return drawChar(cpu);
			case 0x884:return drawString(cpu);
			case 0x885:return drawText(cpu,false);
			case 0x886:return drawText(cpu,true);
			case 0x887:return setWord(cpu,TX_FONT);
			case 0x888:return setTextFace(cpu);
			case 0x889:return setWord(cpu,TX_MODE);
			case 0x88a:return setWord(cpu,TX_SIZE);
			case 0x88b:return getFontInfo(cpu);
			case 0x88c:return stringWidth(cpu);
			case 0x88d:return charWidth(cpu);
			case 0x88e:return setLong(cpu,SP_EXTRA);
			case 0x891:return lineTo(cpu,false);
			case 0x892:return lineTo(cpu,true);
			case 0x893:return movePen(cpu,false);
			case 0x894:return movePen(cpu,true);
			case 0x896:return penVisibility(cpu,-1);
			case 0x897:return penVisibility(cpu,1);
			case 0x898:return getPenState(cpu);
			case 0x899:return setPenState(cpu);
			case 0x89a:return getPen(cpu);
			case 0x89b:return penSize(cpu);
			case 0x89c:return setWord(cpu,PN_MODE);
			case 0x89d:return setPattern(cpu,PN_PAT);
			case 0x89e:return penNormal(cpu);
			case 0x8a1:return drawRectShape(cpu,4,0,0);
			case 0x8a2:return drawRectShape(cpu,4,0,1);
			case 0x8a3:return drawRectShape(cpu,4,0,2);
			case 0x8a4:return drawRectShape(cpu,4,0,3);
			case 0x8a5:return drawRectShape(cpu,8,4,4);
			case 0x8a6:return equalRect(cpu);
			case 0x8a7:return setRect(cpu);
			case 0x8a8:return offsetInsetRect(cpu,false);
			case 0x8a9:return offsetInsetRect(cpu,true);
			case 0x8aa:return intersectRect(cpu);
			case 0x8ab:return unionRect(cpu);
			case 0x8ac:return pointsToRect(cpu);
			case 0x8ad:return pointInRect(cpu);
			case 0x8ae:return emptyRect(cpu);
			case 0x8b0:return drawRoundRectShape(cpu,8,4,0);
			case 0x8b1:return drawRoundRectShape(cpu,8,4,1);
			case 0x8b2:return drawRoundRectShape(cpu,8,4,2);
			case 0x8b3:return drawRoundRectShape(cpu,8,4,3);
			case 0x8b4:return drawRoundRectShape(cpu,12,8,4);
			case 0x8b7:return drawOvalShape(cpu,4,0,0);
			case 0x8b8:return drawOvalShape(cpu,4,0,1);
			case 0x8b9:return drawOvalShape(cpu,4,0,2);
			case 0x8ba:return drawOvalShape(cpu,4,0,3);
			case 0x8bb:return drawOvalShape(cpu,8,4,4);
			case 0x8be:return drawArcShape(cpu,8,4,0);
			case 0x8bf:return drawArcShape(cpu,8,4,1);
			case 0x8c0:return drawArcShape(cpu,8,4,2);
			case 0x8c1:return drawArcShape(cpu,8,4,3);
			case 0x8c2:return drawArcShape(cpu,12,8,4);
			case 0x8c6:return drawPolygon(cpu,4,0,0);
			case 0x8c7:return drawPolygon(cpu,4,0,1);
			case 0x8c8:return drawPolygon(cpu,4,0,2);
			case 0x8c9:return drawPolygon(cpu,4,0,3);
			case 0x8ca:return drawPolygon(cpu,8,4,4);
			case 0x8cb:return openPolygon(cpu);
			case 0x8cc:return closePolygon();
			case 0x8cd:return killPolygon(cpu);
			case 0x8ce:return offsetPolygon(cpu);
			case 0x8d2:return drawRegionShape(cpu,4,0,0);
			case 0x8d3:return drawRegionShape(cpu,4,0,1);
			case 0x8d4:return drawRegionShape(cpu,4,0,2);
			case 0x8d5:return drawRegionShape(cpu,4,0,3);
			case 0x8d6:return drawRegionShape(cpu,8,4,4);
			case 0x8d8:return newRegion(cpu);
			case 0x8d9:return disposeRegion(cpu);
			case 0x8da:return openRegion();
			case 0x8db:return closeRegion(cpu);
			case 0x8dc:return copyRegion(cpu);
			case 0x8dd:return setEmptyRegion(cpu);
			case 0x8de:return setRectRegion(cpu);
			case 0x8df:return rectRegion(cpu);
			case 0x8e0:return offsetInsetRegion(cpu,false);
			case 0x8e1:return offsetInsetRegion(cpu,true);
			case 0x8e2:return emptyRegion(cpu);
			case 0x8e3:return equalRegion(cpu);
			case 0x8e4:return combineRegion(cpu,0);
			case 0x8e5:return combineRegion(cpu,1);
			case 0x8e6:return combineRegion(cpu,2);
			case 0x8e7:return combineRegion(cpu,3);
			case 0x8e8:return pointInRegion(cpu);
			case 0x8e9:return rectInRegion(cpu);
			case 0xb29:return rectRegionTest(cpu);
			case 0xb27:return pixMapBlit(cpu);
			// $AB0F: another 32-Bit QuickDraw internal entry QuickTime calls with every register saved, taking only the condition codes
			// back (SGT afterwards). What it tests is unknown; "not greater" keeps QuickTime on its ordinary drawing path.
			case 0xb0f:cpu.sr=(cpu.sr&~(M68kCpu.FLAG_N|M68kCpu.FLAG_V|M68kCpu.FLAG_C))|M68kCpu.FLAG_Z;return true;
			case 0x817:return copyMask(cpu);
			case 0x839:return seedFill(cpu);
			case 0x838:return calcMask(cpu);
			case 0xa51:return copyDeepMask(cpu);
			case 0xa37:return getSubTable(cpu);
			case 0x8ec:return copyBits(cpu);
			case 0x8ef:return scrollRect(cpu);
			case 0x8f3:return openPicture(cpu);
			case 0x8f4:return closePicture();
			case 0x8f5:return killPicture(cpu);
			case 0x8f6:return drawPicture(cpu);
			case 0xa00:case 0xa01:return initializeColorPort(cpu);
			case 0xa02:return closePort(cpu);
			case 0xa03:return newPixMap(cpu);
			case 0xa04:return disposePixMap(cpu);
			case 0xa05:return copyPixMap(cpu);
			case 0xa06:return setPortPix(cpu);
			case 0xa07:return newPixPat(cpu);
			case 0xa08:return disposePixPat(cpu);
			case 0xa09:return copyPixPat(cpu);
			case 0xa0a:return setPortPixPat(cpu,PN_PAT);
			case 0xa0b:return setPortPixPat(cpu,BK_PAT);
			case 0xa0c:return getPixPat(cpu);
			case 0xa0d:return makeRgbPat(cpu);
			case 0xa0e:return fillColorRect(cpu);
			case 0xa0f:return fillColorOval(cpu);
			case 0xa10:return fillColorRoundRect(cpu);
			case 0xa11:return fillColorArc(cpu);
			case 0xa12:return fillColorRegion(cpu);
			case 0xa13:return fillColorPolygon(cpu);
			case 0xa14:return setRgbColor(cpu,true);
			case 0xa15:return setRgbColor(cpu,false);
			case 0xa16:return setColorPixel(cpu);
			case 0xa17:return getColorPixel(cpu);
			case 0xa18:return getColorTable(cpu);
			case 0xa19:return getRgbColor(cpu,true);
			case 0xa1a:return getRgbColor(cpu,false);
			case 0xa21:return setGrafVarsColor(cpu,0);
			case 0xa22:return setGrafVarsColor(cpu,6);
			case 0xa24:return disposeColorTable(cpu);
			case 0xa27:return getMaxDevice(cpu);
			case 0xa28:return getColorTableSeed(cpu);
			case 0xa29:return getDeviceResult(cpu,0);
			case 0xa2a:return getDeviceResult(cpu,0);
			case 0xa2b:return getNextDevice(cpu);
			case 0xa2c:return testDeviceAttribute(cpu);
			case 0xa2d:return setDeviceAttribute(cpu);
			case 0xa2e:return initializeGraphicsDevice(cpu);
			case 0xa2f:return newGraphicsDevice(cpu);
			case 0xa30:return disposeGraphicsDevice(cpu);
			case 0xa31:return setGraphicsDevice(cpu);
			case 0xa32:return getDeviceResult(cpu,1);
			case 0xa33:return colorToIndex(cpu);
			case 0xa34:return indexToColor(cpu);
			case 0xa35:return invertColor(cpu);
			case 0xa36:return realColor(cpu);
			case 0xa39:return makeInverseTable(cpu);
			case 0xa3a:case 0xa3b:case 0xa4c:case 0xa4d:cpu.a[7]+=4;return true; // AddSearch/AddComp/DelSearch/DelComp(procPtr): custom colour matching is not run; colours map to the nearest entry
			case 0xa3c:cpu.a[7]+=2;return true; // SetClientID(id)
			case 0xa3d:return markColorEntry(cpu,protectedEntries);
			case 0xa3e:return markColorEntry(cpu,reservedEntries);
			case 0xa3f:return setColorEntries(cpu);
			case 0xa49:return saveEntries(cpu);
			case 0xa4a:return restoreEntries(cpu);
			case 0xa40:return getQuickDrawError(cpu);
			case 0xaa2:return paletteManagerDispatch(cpu);
			case 0xb1d:case 0xb03:return quickDrawExtensions(cpu); // $AB03 is the 32-Bit QuickDraw trap old programs probe
			default:return false;
		}
	}

	private boolean quickDrawExtensions(M68kCpu cpu) throws M68kCpuException {
		switch(cpu.d[0]&0xffff) {
			case 0:return newGWorld(cpu);
			case 1:return lockPixels(cpu,true);
			case 2:return lockPixels(cpu,false);
			case 3:return updateGWorld(cpu);
			case 4:return disposeGWorld(cpu);
			case 5:return getGWorld(cpu);
			case 6:return setGWorld(cpu);
			case 7:return colorTableChanged(cpu);
			case 8:case 9:case 10:return discard(cpu,4); // cache invalidation hints
			case 11:return setPixelsPurgeable(cpu,true);
			case 12:return setPixelsPurgeable(cpu,false);
			case 13:return getPixelsState(cpu);
			case 14:return setPixelsState(cpu);
			case 15:return getPixBaseAddress(cpu);
			case 16:return newScreenBuffer(cpu,false);
			case 17:return disposeScreenBuffer(cpu);
			case 18:return getGWorldDevice(cpu);
			case 19:return quickDrawDone(cpu);
			case 20:memory.writeLong(cpu.a[7],0x00010003);return true;
			case 21:return newScreenBuffer(cpu,true);
			case 22:return pixMap32Bit(cpu);
			case 23:return getGWorldPixMap(cpu);
			case 24:{int pixMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int data=pixMap==0?0:memory.readLong(pixMap);memory.writeLong(cpu.a[7],data==0?0:memory.readWord(data+4)&0x3fff);return true;} // GetPixRowBytes
			default:return false;
		}
	}

	/** PMgrDispatch selector as a word: compiler glue may pass MOVE.W #$0A13 (argument bytes in the high byte), while other headers
	 *  passes MOVE.L #$000A0013 (argument bytes in the high word); some callers stop on the second form. */
	static int paletteSelector(M68kCpu cpu){int d0=cpu.d[0];int high=(d0>>>16)&0xff;return high!=0&&(d0&0xff00)==0?high<<8|(d0&0xff):d0&0xffff;}
	private boolean paletteManagerDispatch(M68kCpu cpu) throws M68kCpuException {
		switch(paletteSelector(cpu)) {
			case 0x0a13:return setDeviceDepth(cpu); // the selector word carries the argument byte count in its high byte
			case 0x0a14:return hasDeviceDepth(cpu);
			default:return false;
		}
	}

	private boolean hasDeviceDepth(M68kCpu cpu) throws M68kCpuException {
		int flags=(short)memory.readWord(cpu.a[7]),whichFlags=(short)memory.readWord(cpu.a[7]+2);
		int depth=depthOfMode((short)memory.readWord(cpu.a[7]+4)),device=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
		// the answer is the mode ID ($80-$85) for the depth, which SetDepth takes back (Marathon saves and restores the mode)
		boolean color=(whichFlags&1)==0||((flags&1)!=0);int result=validDevice(device)&&supportedDepth(depth)&&(!color?depth<=8:true)&&deviceRowFits(device,depth)?videoMode(depth):0;
		memory.writeWord(cpu.a[7],result);return true;
	}

	private boolean setDeviceDepth(M68kCpu cpu) throws M68kCpuException {
		int flags=(short)memory.readWord(cpu.a[7]),whichFlags=(short)memory.readWord(cpu.a[7]+2);
		int depth=depthOfMode((short)memory.readWord(cpu.a[7]+4)),device=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;int error=C_DEV_ERR;
		if(validDevice(device)){int data=memory.readLong(device);boolean color=(whichFlags&1)==0?(memory.readWord(data+20)&1)!=0:(flags&1)!=0;
			if(supportedDepth(depth)&&(!color?depth<=8:true)&&deviceRowFits(device,depth)){replaceDevicePixMap(device,depth,color);if(device==mainGDevice)setGrayMode(!color);error=NO_ERR;}else error=C_DEPTH_ERR;}
		if(Mac68kApi.TRACE){int[] f=mainDeviceFrame();System.err.println("[68K] SetDepth $"+Integer.toHexString(device)+" depth "+depth+" -> "+error+(f==null?"":" pixels at $"+Integer.toHexString(f[0])+" rowBytes "+f[1]+" bounds "+f[3]+","+f[4]+","+f[5]+","+f[6]));}
		memory.writeWord(cpu.a[7],error);return true;
	}

	/** The main screen takes {@code depth} (a colour device, as SetDepth with the colour flag): the Display Manager's DMSetDisplayMode. False when the depth is not offered. */
	boolean switchMainDepth(int depth) throws M68kCpuException {
		if(mainGDevice==0||!supportedDepth(depth)||!deviceRowFits(mainGDevice,depth))return false;
		PixelMapState main=offscreenPixMaps.get(mainDevicePixMap);if(main!=null&&main.depth==depth)return true;
		replaceDevicePixMap(mainGDevice,depth,true);setGrayMode(false);return true;
	}

	private boolean validDevice(int device) throws M68kCpuException {
		return device!=0&&(device==mainGDevice||dynamicDevices.containsKey(device))&&macApi.handleSize(device)>=62&&memory.readLong(device)!=0;
	}

	/** SetDepth/HasDepth take a depth or a video mode ID ($80 = 1 bit ... $85 = 32 bits, what gdMode holds). */
	private static int depthOfMode(int value){return value>=0x80&&value<=0x85?1<<(value-0x80):value;}
	private boolean supportedDepth(int depth) {return depth==1||depth==2||depth==4||depth==8||depth==16||depth==32;}
	/** A screen mode exists only when its rows stay within {@link #MAX_SCREEN_ROW_BYTES}: a screen 8 bits deep on a wide display has no 32-bit mode, as a video card without the memory for it. */
	private boolean deviceRowFits(int device,int depth) throws M68kCpuException {int data=memory.readLong(device);Rect bounds=readRect(data+34);return bounds.right-bounds.left<=maxScreenWidth(depth,powerPcScreen());}

	/**
	 * Hosted windows draw through the main device's colour table (see {@link #createWindowPixMap}); SetDepth gives the device a new
	 * PixMap and table, so windows of the same depth that stay as they are must follow it - SetEntries then reaches the table
	 * their colour matching reads (HAVOC opens its window first and calls SetDepth 8 afterwards: the title picture was matched
	 * against the old table and came out in the wrong colours).
	 */
	private void retargetWindowTables(PixelMapState device) throws M68kCpuException {
		int table=device.colorTable;if(table==0||device.depth>8)return;
		for(PixelMapState state:offscreenPixMaps.values()){
			if(!state.sharedColorTable||state.windowPort==0||state.colorTable==table||state.depth!=device.depth)continue;
			int data=memory.readLong(state.pixMap);if(data!=0)memory.writeLong(data+42,table);state.colorTable=table;}
	}
	private void replaceDevicePixMap(int device,int depth,boolean color) throws M68kCpuException {
		int data=memory.readLong(device);Rect bounds=readRect(data+34);if(bounds.empty())bounds=new Rect(0,0,342,512);
		int requestedTable=!color&&depth<=8?standardColorTable(depth+32):0;
		PixelMapState old;if(device==mainGDevice)old=offscreenPixMaps.get(mainDevicePixMap);else{DynamicDeviceState state=dynamicDevices.get(device);old=state==null?null:state.pixels;}
		PixelMapState replacement=createOffscreenPixMap(depth,bounds,requestedTable,SCREEN_MEMORY);if(requestedTable!=0)macApi.disposeHandleAndFree(requestedTable);
		// A real screen keeps its PixMap record and its memory across SetDepth (only rowBytes, pixelSize and the table
		// change), and programs cache both: HyperCard 2.2 keeps the record pointer of the screen it started on for its
		// visual effects, so a fresh record here made SetMonitor(8) from a 16-bit screen scroll the next card through a
		// dead record (16-bit row stride into 8-bit memory; the jump table got overwritten). Reuse the old Handles.
		int formerBase=old==null||memory.readLong(old.pixMap)==0?0:memory.readLong(memory.readLong(old.pixMap));
		if(old!=null&&memory.readLong(old.pixMap)!=0){
			int newData=memory.readLong(replacement.pixMap),oldData=memory.readLong(old.pixMap);
			offscreenPixMaps.remove(replacement.pixMap);offscreenPixMapData.remove(newData);
			memory.load(oldData,memoryBytes(newData,50));macApi.disposeHandleAndFree(replacement.pixMap);
			replacement.pixMap=old.pixMap;offscreenPixMaps.put(old.pixMap,replacement);offscreenPixMapData.put(oldData,replacement);
			// The former pixels must really be freed (disposeHandle only forgets the Handle): a screen buffer is megabytes, and
			// Speedometer's Color QuickDraw test switches 32->1->32->2->32->4->32->8 bits and ran out of heap on the fourth round.
			if(old.colorTable!=0&&!old.sharedColorTable)macApi.disposeHandleAndFree(old.colorTable);macApi.disposeHandleAndFree(old.pixels);old=null;
		}
		exposeDevicePixels(replacement);
		if(device==mainGDevice){int former=mainDevicePixMap;mainDevicePixMap=replacement.pixMap;writeScreenRow(replacement);retargetScreenPortCopies(formerBase);retargetScreenPorts(former,replacement.pixMap);retargetWindowTables(replacement);}
		else {DynamicDeviceState state=dynamicDevices.get(device);if(state!=null)state.pixels=replacement;}
		memory.writeWord(data+4,depth>=16?2:0);int deviceFlags=memory.readWord(data+20);
		memory.writeWord(data+20,color?deviceFlags|1:deviceFlags&~1);memory.writeLong(data+22,replacement.pixMap);memory.writeLong(data+42,videoMode(depth));
		if(old!=null)disposeOffscreenPixMap(old);
		refreshDeviceInverseTable(device);
		if(device==mainGDevice){Toolbox68k tb=macApi.application();if(tb!=null&&tb.hosted()&&tb.windows!=null)tb.windows.screenDepthChanged();}
	}

	/** The screen belongs to a hosted program (else it is the classic 512x342 of a VM not yet attached to one). */
	private boolean hostedScreen(){Toolbox68k tb=macApi.application();return tb!=null&&tb.hosted();}
	/** The screen size depends on which Toolbox68k hosts the VM; a main GDevice created before that was known is resized here. */
	void refreshMainDeviceBounds() throws M68kCpuException {
		if(mainGDevice==0)return;int data=memory.readLong(mainGDevice);Rect screen=screenRect();boolean hosted=hostedScreen();
		// a hosted game screen may be the classic 512x342 itself (-Dhz68k.screen=512x342): the size alone does not tell the device made before the host was known
		if(readRect(data+34).equals(screen)){PixelMapState main=offscreenPixMaps.get(mainDevicePixMap);if(!hosted||main!=null&&main.depth==hostedScreenDepth())return;}
		writeRect(data+34,screen.top,screen.left,screen.bottom,screen.right);
		int depth=hosted?hostedScreenDepth():32;memory.writeWord(data+4,hosted&&depth<=8?0:2);memory.writeWord(data+10,4);replaceDevicePixMap(mainGDevice,depth,true); // gdResPref: inverse-table resolution 3..5 (programs pass it to MakeITable; 8 made it fail with cRangeErr)
	}

	/**
	 * The screen the program sees changed size (a game switching the display mode, MacApp68k.changeScreenSize): the main device
	 * gets the new bounds and a PixMap of that size at the depth it has now, and screenBits and the screen ports follow.
	 */
	void screenResized() throws M68kCpuException {
		if(mainGDevice==0||!hostedScreen())return;
		int data=memory.readLong(mainGDevice);Rect screen=screenRect();PixelMapState main=offscreenPixMaps.get(mainDevicePixMap);
		if(main==null||readRect(data+34).equals(screen))return;
		boolean color=(memory.readWord(data+20)&1)!=0;
		writeRect(data+34,screen.top,screen.left,screen.bottom,screen.right);
		replaceDevicePixMap(mainGDevice,main.depth,color);
		int globals=liveGlobals();if(globals!=0){writeRect(globals-116,screen.top,screen.left,screen.bottom,screen.right);writeScreenBits();}
	}

	/** A screen device's PixMap carries the real pixel pointer in baseAddr: programs write to screen memory through it. */
	private void exposeDevicePixels(PixelMapState state) throws M68kCpuException {
		int map=memory.readLong(state.pixMap),pixels=memory.readLong(state.pixels);if(map!=0&&pixels!=0)memory.writeLong(map,pixels);
		if(pixels!=0)memory.trackWrites(pixels,state.rowBytes*(state.bounds.bottom-state.bounds.top),state.rowBytes);
	}
	/** ScreenRow ($106): the main screen's rowBytes, which pre-Gestalt games read straight from low memory to address the screen;
	 *  some take its log2 for the row stride and loop forever if it is zero. */
	private void writeScreenRow(PixelMapState main) throws M68kCpuException {memory.writeWord(0x106,main.rowBytes);memory.writeLong(0x824,guestScreenBase(memory.readLong(main.pixels)));writeScreenBits();}
	/**
	 * High address bits added to the screen's base where the program reads it (ScrnBase, screenBits, classic windows' portBits),
	 * 0 for none. Sierra's Macintosh AGI draws in colour only when screenBits.baseAddr is NuBus video ($Fxxxxxxx, a Mac II's slot
	 * screen); guest addresses wrap at the memory size, so $F3B01328 reaches the same bytes as $03B01328. Set by MacApp68k for
	 * such programs (colorOnlyOnSlotScreen). Host code takes bitmap bases through M68kMemory.physical.
	 */
	private int screenAddressAlias;
	void setScreenAddressAlias(int bits) throws M68kCpuException {screenAddressAlias=bits;int base=memory.readLong(0x824);if(base!=0)memory.writeLong(0x824,guestScreenBase(memory.physical(base)));}
	/** A screen base address as the program is shown it (see {@link #screenAddressAlias}). */
	int guestScreenBase(int base){if(screenAddressAlias==0||base==0)return base;int aliased=base|screenAddressAlias;return memory.physical(aliased)==base?aliased:base;}

	/** Driver reference number of the main screen's video driver (gdRefNum); see {@link #videoDriver}. */
	static final int VIDEO_DRIVER_REF=-33;

	/** Control/Status calls to the screen's video driver (IM Devices, "Video Driver"): programs that animate the CLUT
	 *  call _Control with cscSetEntries on gdRefNum instead of SetEntries. Returns the result code. */
	int videoDriver(int pb,boolean status) throws M68kCpuException {
		int code=(short)memory.readWord(pb+26),record=memory.readLong(pb+28);ensureMainGDevice(); // csParam holds a pointer to the VD*Record
		int mapData=memory.readLong(mainDevicePixMap),table=mapData==0?0:memory.readLong(mapData+42),tableData=table==0?0:memory.readLong(table);
		if(record==0)return status?-18:-17;
		if(status){
			switch(code){
				case 2:{int depth=memory.readWord(mapData+32);memory.writeWord(record,depth<=1?128:depth==2?129:depth==4?130:depth==8?131:depth==16?132:133);memory.writeWord(record+6,0);memory.writeLong(record+8,memory.readLong(mapData));return 0;} // cscGetMode: VDPageInfo csMode/csPage/csBaseAddr
				case 3:{int specs=memory.readLong(record),start=(short)memory.readWord(record+4),count=(short)memory.readWord(record+6); // cscGetEntries: VDSetEntryRecord
					if(tableData==0||specs==0)return -18;for(int i=0;i<=count;i++){int index=start<0?memory.readWord(specs+i*8):start+i;int entry=findColorEntry(table,index);if(entry!=0)memory.copy(entry+2,specs+i*8+2,6);}return 0;}
				case 4:memory.writeWord(record+6,1);return 0; // cscGetPages: VDPageInfo csPage
				case 5:memory.writeLong(record+8,memory.readLong(mapData));return 0; // cscGetBaseAddr: VDPageInfo csBaseAddr
				case 6:memory.writeByte(record,grayMode?1:0);return 0; // cscGetGray: VDGrayRecord csMode (1 = luminance mapping on)
				case 7:memory.writeByte(record,1);return 0; // cscGetInterrupt: disabled
				case 8:memory.writeLong(record,ensureGammaTable());return 0; // cscGetGamma: VDGammaRecord csGTable -> the driver's current table
				case 9:memory.writeByte(record,memory.readWord(mapData+32)<=8?131:133);return 0; // cscGetDefaultMode
				default:return -18; // statusErr
			}
		}
		switch(code){
			case 0:case 1:case 5:case 7:case 9:return 0; // reset, KillIO, GrayPage, SetInterrupt, SetDefaultMode
			case 2:return setVideoMode(record,mapData); // cscSetMode: VDPageInfo csMode -> csBaseAddr
			case 6:setGrayMode(memory.readByte(record)!=0);return 0; // cscSetGray: VDGrayRecord csMode
			case 4:return setGamma(memory.readLong(record)); // cscSetGamma: VDGammaRecord csGTable
			case 3:case 8:{int specs=memory.readLong(record),start=(short)memory.readWord(record+4),count=(short)memory.readWord(record+6); // cscSetEntries / cscDirectSetEntries: VDSetEntryRecord
				if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder("[68K] video driver SetEntries specs $"+Integer.toHexString(specs)+" table $"+Integer.toHexString(table)+" data $"+Integer.toHexString(tableData)+" start "+start+" count "+count+":");if(specs!=0)for(int i=0;i<=Math.min(count,5);i++)sb.append(String.format(" [%d]=%04x/%04x/%04x",(short)memory.readWord(specs+i*8),memory.readWord(specs+i*8+2),memory.readWord(specs+i*8+4),memory.readWord(specs+i*8+6)));System.err.println(sb);}
				if(tableData==0||specs==0||count<0)return -17;
				for(int i=0;i<=count;i++){int index=start<0?memory.readWord(specs+i*8):start+i;int entry=findColorEntry(table,index);if(entry!=0)memory.copy(specs+i*8+2,entry+2,6);}
				return 0;} // the driver knows nothing of QuickDraw: ctSeed stays, so tables copied from it still match
			default:return -17; // controlErr
		}
	}

	/**
	 * cscSetMode: programs that switch depths through the driver (the pre-Palette Manager way, as the Monitors control
	 * panel of System 6 did) expect the screen to follow. The driver's modes are $80.. by depth; the main device's PixMap
	 * is rebuilt as SetDepth does it (same record, same memory) and csBaseAddr answers the frame buffer.
	 */
	private int setVideoMode(int record,int mapData) throws M68kCpuException {
		int mode=memory.readWord(record);if(mode<0x80||mode>0x85)return -17;int depth=1<<(mode-0x80);
		if(!supportedDepth(depth)||!deviceRowFits(mainGDevice,depth))return -17;
		if(memory.readWord(mapData+32)!=depth){int data=memory.readLong(mainGDevice);replaceDevicePixMap(mainGDevice,depth,(memory.readWord(data+20)&1)!=0);}
		memory.writeLong(record+8,memory.readLong(memory.readLong(mainDevicePixMap)));memory.writeWord(record+6,0);
		if(Mac68kApi.TRACE)System.err.println("[68K] video driver SetMode $"+Integer.toHexString(mode)+" -> "+depth+" bits");
		return 0;
	}

	/** The driver's luminance mapping (cscSetGray): at 8 bits and less the CLUT reaches the monitor as greys, the colour table itself unchanged. */
	private volatile boolean grayMode;
	boolean grayMode(){return grayMode;}
	/** Luminance mapping applies in the indexed modes only (a direct-colour screen has no CLUT to map); the host shows it through {@link #gammaVersion}. */
	boolean grayDisplay(){if(!grayMode)return false;try{int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);return map!=0&&memory.readWord(map+32)<=8;}catch(M68kCpuException e){return false;}}
	void setGrayMode(boolean gray){if(gray!=grayMode){grayMode=gray;gammaVersion++;if(Mac68kApi.TRACE)System.err.println("[68K] video driver SetGray "+gray);}}

	// ------------------------------------------------------------------ gamma table (IM Devices, "Video Driver": cscGetGamma / cscSetGamma)

	/*
	 * Games fade the screen in and out by scaling the video driver's gamma table (cscGetGamma for the current table, a
	 * scaled copy to cscSetGamma every tick, the original back at the end): the CLUT stays, the hardware ramps every
	 * pixel through the table on its way to the monitor. The host does the same when it converts the screen for display
	 * (MacAppHost.applyGamma), so the fade works at every depth, direct colour included.
	 */
	/** The driver's current GammaTbl (a pointer block, IM Devices: gVersion, gType, gFormulaSize, gChanCnt, gDataCnt, gDataWidth, formula, data); 0 until asked for. */
	private int gammaTable;
	/** Per-channel 8-bit ramps (red 0..255, green 256..511, blue 512..767) the host shows the screen through, or null for the linear default. */
	private volatile int[] gammaLookup;
	/** Incremented on every gamma change: the host reconverts what it shows when the version differs from the one it converted with. */
	private volatile int gammaVersion;
	int gammaVersion(){return gammaVersion;}
	int[] gammaLookup(){return gammaLookup;}
	/** The driver's default table: one 8-bit channel of 256 linear entries (the host display is the reference, so linear = as drawn). */
	private static byte[] linearGammaTable(){byte[] t=new byte[12+256];putWord(t,6,1);putWord(t,8,256);putWord(t,10,8);for(int i=0;i<256;i++)t[12+i]=(byte)i;return t;}
	private int ensureGammaTable() throws M68kCpuException {
		if(gammaTable==0){byte[] t=linearGammaTable();gammaTable=memory.allocate(t.length,2);memory.load(gammaTable,t);}return gammaTable;
	}
	/** Installs the GammaTbl at {@code table} (NIL: the linear default) as the driver's table and the host's ramps. */
	int setGamma(int table) throws M68kCpuException {
		if(table==0){byte[] t=linearGammaTable();if(gammaTable!=0)gammaTable=memory.reallocate(gammaTable,t.length);else gammaTable=memory.allocate(t.length,2);memory.load(gammaTable,t);
			if(gammaLookup!=null){gammaLookup=null;gammaVersion++;}return 0;}
		int formulaSize=memory.readWord(table+4),channels=memory.readWord(table+6),count=memory.readWord(table+8),width=memory.readWord(table+10);
		if((channels!=1&&channels!=3)||count<1||count>4096||width<1||width>16||formulaSize>4096)return -17; // controlErr
		int entryBytes=width<=8?1:2,data=table+12+formulaSize,size=12+formulaSize+channels*count*entryBytes,max=(1<<width)-1;
		int[] lookup=new int[768];boolean linear=true;
		for(int c=0;c<3;c++){int channel=data+(channels==1?0:c)*count*entryBytes;
			for(int v=0;v<256;v++){int i=count==256?v:v*count>>>8,raw=entryBytes==1?memory.readByte(channel+i)&0xff:memory.readWord(channel+i*2)&0xffff;
				int out=width==8?raw:Math.min(255,(raw*255+max/2)/max);lookup[c*256+v]=out;if(out!=v)linear=false;}}
		if(gammaTable==0)gammaTable=memory.allocate(size,2);else if(table!=gammaTable)gammaTable=memory.reallocate(gammaTable,size);
		if(table!=gammaTable)memory.copy(table,gammaTable,size);
		if(Mac68kApi.TRACE)System.err.println("[68K] video driver SetGamma $"+Integer.toHexString(table)+" channels "+channels+" count "+count+" width "+width+(linear?" (linear)":" e.g. 128 -> "+lookup[128]+"/"+lookup[384]+"/"+lookup[640]));
		gammaLookup=linear?null:lookup;gammaVersion++;return 0;
	}

	/** gdMode: the video driver's mode for a depth, firstVidMode $80 (1 bit) .. sixthVidMode $85 (32 bits); some programs take gdMode > $80 as "colour screen". */
	static int videoMode(int depth){return depth<=1?0x80:depth==2?0x81:depth==4?0x82:depth==8?0x83:depth==16?0x84:0x85;}
	private int ensureMainGDevice() throws M68kCpuException {
		if(mainGDevice!=0)return mainGDevice;Rect bounds=screenRect();
		boolean hosted=hostedScreen();
		int depth=hosted?hostedScreenDepth():32;
		PixelMapState pixels=createOffscreenPixMap(depth,bounds,0,SCREEN_MEMORY);mainDevicePixMap=pixels.pixMap;exposeDevicePixels(pixels);writeScreenRow(pixels);
		byte[] device=new byte[62];putWord(device,0,VIDEO_DRIVER_REF);/* gdRefNum: programs may set the CLUT through the video driver */putWord(device,4,hosted&&depth<=8?0:2);putWord(device,10,4);putLong(device,6,macApi.createHandle(new byte[4])); // gdResPref 4: inverse-table resolution (3..5); gdITable: never NIL on a real Mac (QuickTime's NewGWorld patch resizes the device's table)
		putWord(device,20,(1<<0)|(1<<7)|(1<<8)|(1<<11)|(1<<13)|(1<<14)|(1<<15));putLong(device,22,pixels.pixMap);
		putRect(device,34,bounds);putLong(device,42,videoMode(depth));mainGDevice=macApi.createHandle(device);if(currentGDevice==0)currentGDevice=mainGDevice;
		refreshDeviceInverseTable(mainGDevice);
		Toolbox68k tb=macApi.application();if(tb!=null&&tb.hosted()&&tb.windows!=null)tb.windows.describePixMaps();return mainGDevice;
	}

	private boolean getDeviceResult(M68kCpu cpu,int current) throws M68kCpuException {
		int main=ensureMainGDevice();memory.writeLong(cpu.a[7],current!=0?currentGDevice:main);return true;
	}

	private boolean getNextDevice(M68kCpu cpu) throws M68kCpuException {
		int device=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int data=device==0?0:memory.readLong(device);
		memory.writeLong(cpu.a[7],data==0?0:memory.readLong(data+30));return true;
	}

	private boolean getMaxDevice(M68kCpu cpu) throws M68kCpuException {
		int rectPointer=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int device=ensureMainGDevice();
		if(rectPointer!=0&&intersection(readRect(rectPointer),screenRect()).empty())device=0;memory.writeLong(cpu.a[7],device);return true;
	}

	private boolean testDeviceAttribute(M68kCpu cpu) throws M68kCpuException {
		int attribute=(short)memory.readWord(cpu.a[7]),device=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;boolean value=false;
		if(attribute>=0&&attribute<16&&device!=0){int data=memory.readLong(device);if(data!=0)value=(memory.readWord(data+20)&1<<attribute)!=0;}
		writeBoolean(cpu.a[7],value);return true;
	}

	private boolean setDeviceAttribute(M68kCpu cpu) throws M68kCpuException {
		boolean value=memory.readByte(cpu.a[7])!=0;int attribute=(short)memory.readWord(cpu.a[7]+2),device=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(attribute>=0&&attribute<16&&device!=0){int data=memory.readLong(device);if(data!=0){int flags=memory.readWord(data+20);
			memory.writeWord(data+20,value?flags|1<<attribute:flags&~(1<<attribute));}}return true;
	}

	private boolean setGraphicsDevice(M68kCpu cpu) throws M68kCpuException {
		int device=memory.readLong(cpu.a[7]);cpu.a[7]+=4;currentGDevice=device==0?ensureMainGDevice():device;return true;
	}

	private boolean newGraphicsDevice(M68kCpu cpu) throws M68kCpuException {
		int mode=memory.readLong(cpu.a[7]),refNum=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6;
		// mode -1: no video driver, the program fills the PixMap in itself. The ROM hands it a default BLACK-AND-WHITE PixMap
		// (pixelType 0, 1 bit, cmpCount 1); Strategic Conquest 3 keeps pixelType/cmpCount and only sets pixelSize 4, rowBytes,
		// bounds, baseAddr and the table, so a direct-colour template left an impossible "direct 4-bit" record.
		Rect bounds=new Rect(0,0,342,512);PixelMapState pixels=createOffscreenPixMap(mode==-1?1:32,bounds,0,0);
		// That PixMap is the program's from then on: it puts a buffer of its own in baseAddr (Tesserae: NewPtr, OpenCPort, draws
		// its tiles and CopyBits from gdPMap^^), so it is read from the record like any program-built PixMap, not as a buffer
		// QuickDraw owns (the pixels Handle QuickDraw allocated stayed blank and every tile came out white).
		if(mode==-1){offscreenPixMaps.remove(pixels.pixMap);offscreenPixMapData.remove(memory.readLong(pixels.pixMap));}
		int inverseTable=macApi.createHandle(new byte[2]);byte[] device=new byte[62];
		putLong(device,6,inverseTable);putLong(device,22,pixels.pixMap);putRect(device,34,bounds);
		int handle=macApi.createHandle(device);dynamicDevices.put(handle,new DynamicDeviceState(pixels,inverseTable));
		initializeGraphicsDevice(handle,refNum,mode);memory.writeLong(cpu.a[7],handle);return true;
	}

	private boolean initializeGraphicsDevice(M68kCpu cpu) throws M68kCpuException {
		int device=memory.readLong(cpu.a[7]),mode=memory.readLong(cpu.a[7]+4),refNum=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;
		initializeGraphicsDevice(device,refNum,mode);return true;
	}

	private void initializeGraphicsDevice(int device,int refNum,int mode) throws M68kCpuException {
		if(device==0||macApi.handleSize(device)<62)return;int data=memory.readLong(device);if(data==0)return;
		if(device==mainGDevice){
			// A video sResource's modes are numbered from $80 upwards by depth (1, 2, 4, 8, 16, 32 bits): InitGDevice on the
			// screen device with another mode is the pre-Palette Manager way to switch depths (some programs change colours
			// to 16 for you with mode $82 and checks the depth again).
			int previous=memory.readLong(data+42);memory.writeWord(data,refNum);memory.writeLong(data+42,mode);
			if(mode!=previous&&mode>=0x80&&mode<=0x85){int depth=1<<(mode-0x80);if(supportedDepth(depth)&&deviceRowFits(device,depth))replaceDevicePixMap(device,depth,(memory.readWord(data+20)&1)!=0);} // keeps gdDevType: Prototyping Toolkit's SetColorState sets gray with SetDeviceAttribute before InitGDevice
			return;}
		DynamicDeviceState state=dynamicDevices.get(device);if(state==null){Rect bounds=new Rect(0,0,342,512);
			PixelMapState pixels=createOffscreenPixMap(32,bounds,0,0);int inverseTable=macApi.createHandle(new byte[2]);
			state=new DynamicDeviceState(pixels,inverseTable);dynamicDevices.put(device,state);}
		memory.fill(data,62,0);
		memory.writeWord(data,refNum);memory.writeWord(data+2,0);memory.writeWord(data+4,state.pixels.depth<=8?0:2); // gdType: clutType / directType
		memory.writeLong(data+6,state.inverseTable);
		memory.writeWord(data+10,4);memory.writeLong(data+12,0);memory.writeLong(data+16,0);
		memory.writeWord(data+20,(1<<0)|(1<<10)|(1<<14));memory.writeLong(data+22,state.pixels.pixMap);
		memory.writeLong(data+26,0);memory.writeLong(data+30,0);writeRect(data+34,state.pixels.bounds);
		memory.writeLong(data+42,mode);memory.fill(data+46,16,0);
	}

	private boolean disposeGraphicsDevice(M68kCpu cpu) throws M68kCpuException {
		int device=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(device==0||device==mainGDevice)return true;
		if(currentGDevice==device)currentGDevice=ensureMainGDevice();DynamicDeviceState state=dynamicDevices.remove(device);if(state!=null){
			disposeOffscreenPixMap(state.pixels);
			if(state.inverseTable!=0)macApi.disposeHandle(state.inverseTable);macApi.disposeHandle(device);
		}else macApi.disposeHandle(device);return true;
	}

	/** GWorldFlags noNewDevice: NewGWorld uses aGDevice instead of making a device for the world. */
	private static final int NO_NEW_DEVICE=1<<1,USE_TEMP_MEM=1<<2;
	/** UpdateGWorld flags that ask for the image to be kept (clipped, stretched or dithered) when the world changes. */
	private static final int CLIP_PIX=1<<28,STRETCH_PIX=1<<29,DITHER_PIX=1<<30;
	boolean newGWorld(M68kCpu cpu) throws M68kCpuException {
		int flags=memory.readLong(cpu.a[7]),device=memory.readLong(cpu.a[7]+4),table=memory.readLong(cpu.a[7]+8),givenDevice=device;
		int boundsPointer=memory.readLong(cpu.a[7]+12),depth=(short)memory.readWord(cpu.a[7]+16),destination=memory.readLong(cpu.a[7]+18);
		cpu.a[7]+=22;int error=PARAM_ERR;
		if(destination!=0&&boundsPointer!=0){if(device==0)device=ensureMainGDevice();Rect bounds=gWorldBounds(readRect(boundsPointer),depth);int actualDepth=normalizedGWorldDepth(depth,device);
			// pixelDepth 0: the world takes the screen's depth AND its colour table (seed included, so CopyBits copies indexes) -
			// Some programs fill such worlds with pixels from a palette loaded into the screen table; standard 'clut' 8 recolours them.
			if(depth==0&&table==0&&actualDepth<=8)table=deviceColorTable(device);
			if(actualDepth==0)error=C_DEPTH_ERR;else if(validGWorldBounds(bounds,actualDepth)){
				GWorldState state=createGWorld(actualDepth,bounds,table,device,flags,0,0,givenDevice==0&&(flags&NO_NEW_DEVICE)==0);memory.writeLong(destination,state.port);error=NO_ERR;
				if((flags&NO_NEW_DEVICE)!=0&&givenDevice!=0)state.sharedDevice=givenDevice; // noNewDevice: it draws through aGDevice
				if(Mac68kApi.TRACE)System.err.println("[68K] NewGWorld depth "+depth+" -> "+actualDepth+" bounds "+bounds.left+","+bounds.top+","+bounds.right+","+bounds.bottom);}}
		if(destination!=0&&error!=NO_ERR)memory.writeLong(destination,0);memory.writeWord(cpu.a[7],error);
		if(Mac68kApi.TRACE&&error!=NO_ERR)System.err.println("[68K] NewGWorld failed "+error+": depth "+depth+" bounds "+(boundsPointer==0?"NIL":readRect(boundsPointer).toString())+" destination $"+Integer.toHexString(destination)+" device $"+Integer.toHexString(device)+" flags $"+Integer.toHexString(flags)+" free "+memory.availableBytes());
		return true;
	}

	/**
	 * With pixelDepth 0 the boundsRect is a screen rectangle that only chooses the device and the pixel alignment: the
	 * world itself gets local coordinates (0,0,h,w) for its portRect and PixMap bounds, as the ROM does (Executor's
	 * NewGWorld mirrors it). Some runtimes pass the stage's global rectangle and then draw and CopyBits with local rectangles;
	 * a 1993 MacTech animation sample does the same. With an explicit depth the rectangle is kept as given.
	 */
	private static Rect gWorldBounds(Rect bounds,int depth){
		if(depth!=0||(bounds.top==0&&bounds.left==0))return bounds;
		return new Rect(0,0,bounds.bottom-bounds.top,bounds.right-bounds.left);
	}

	/** The colour table of a GDevice's PixMap (0 when it has none). */
	private int deviceColorTable(int device) throws M68kCpuException {
		int data=device==0?0:memory.readLong(device),pixMap=data==0?0:memory.readLong(data+22),map=pixMap==0?0:memory.readLong(pixMap);
		return map==0?0:memory.readLong(map+42);
	}
	private int normalizedGWorldDepth(int depth,int device) throws M68kCpuException {
		if(depth==0){int selected=device!=0?device:currentGDevice;if(selected!=0){int data=memory.readLong(selected),pixMap=data==0?0:memory.readLong(data+22);
			int map=pixMap==0?0:memory.readLong(pixMap);if(map!=0){int deviceDepth=memory.readWord(map+32);if(deviceDepth==1||deviceDepth==2||deviceDepth==4||deviceDepth==8||deviceDepth==16||deviceDepth==32)return deviceDepth;}}
			return currentPort!=0&&isColorPort(currentPort)?currentPixelSize():32;}
		return depth==1||depth==2||depth==4||depth==8||depth==16||depth==32?depth:0;
	}

	private boolean validGWorldBounds(Rect bounds,int depth) {
		long width=(long)bounds.right-bounds.left,height=(long)bounds.bottom-bounds.top;
		long rowBytes=ownGWorldRowBytes((int)Math.min(width,0x10000),depth),pixelBytes=rowBytes*height;
		return width>0&&height>0&&rowBytes>0&&rowBytes<0x4000&&pixelBytes<=Integer.MAX_VALUE&&pixelBytes+4096<=memory.availableBytes();
	}

	/**
	 * NewGWorldFromPtr (CarbonLib 1.1): a GWorld over the caller's own pixel buffer - baseAddr is {@code buffer} and rowBytes the
	 * caller's, so what QuickDraw draws lands in the caller's memory and what the caller writes there is what CopyBits reads.
	 * The buffer stays the caller's (DisposeGWorld leaves it alone, see {@link PixelMapState#externalPixels}). Returns the QDErr;
	 * the port goes to {@code destination}.
	 */
	int newGWorldFromPtr(int destination,int depth,int boundsPointer,int table,int device,int flags,int buffer,int rowBytes) throws M68kCpuException {
		if(destination==0)return PARAM_ERR;memory.writeLong(destination,0);
		if(boundsPointer==0||buffer==0)return PARAM_ERR;
		if(depth!=1&&depth!=2&&depth!=4&&depth!=8&&depth!=16&&depth!=32)return C_DEPTH_ERR;
		Rect bounds=readRect(boundsPointer);long width=(long)bounds.right-bounds.left,height=(long)bounds.bottom-bounds.top;
		if(width<=0||height<=0||rowBytes<(width*depth+7)/8||rowBytes>=0x4000||(rowBytes&1)!=0)return PARAM_ERR;
		int givenDevice=device;if(device==0)device=ensureMainGDevice();
		GWorldState state=createGWorld(depth,bounds,table,device,flags&~1,buffer,rowBytes,givenDevice==0&&(flags&NO_NEW_DEVICE)==0);memory.writeLong(destination,state.port);
		if((flags&NO_NEW_DEVICE)!=0&&givenDevice!=0)state.sharedDevice=givenDevice;
		return NO_ERR;
	}

	private GWorldState createGWorld(int depth,Rect bounds,int requestedTable,int device,int flags) throws M68kCpuException {return createGWorld(depth,bounds,requestedTable,device,flags,0,0,false);}
	/** {@code buffer}: the caller's pixels (NewGWorldFromPtr) with their {@code rowBytes}; 0 for pixels of the world's own.
	 *  {@code ownDevice}: the world gets a GDevice of its own even though {@code device} (the one its depth and table come from) is given. */
	private GWorldState createGWorld(int depth,Rect bounds,int requestedTable,int device,int flags,int buffer,int rowBytes,boolean ownDevice) throws M68kCpuException {
		PixelMapState map=createOffscreenPixMap(depth,bounds,requestedTable,flags,buffer,rowBytes,true);int port=memory.allocate(GRAF_PORT_SIZE,2);
		// a GWorld made for an explicit GDevice keeps using it (programs get their own device back from GetGWorldDevice); one made
		// with no device gets a GDevice of its own, as NewGWorld does without noNewDevice. Strata StudioPro 1.1 matches its
		// background colour with GetGWorldDevice + SetGDevice + Color2Index and fills an 8-bit world with the index: on the
		// main (32-bit) device it got $FFFFFF, whose low byte is black in the world's table.
		int offscreenDevice=device!=0&&!ownDevice?device:createOffscreenDevice(map.pixMap,depth,bounds);
		initializeGWorldPort(port,map.pixMap,bounds,offscreenDevice);GWorldState state=new GWorldState(port,map.pixMap,map.pixels,map.colorTable,device,offscreenDevice,depth,map.rowBytes,bounds);
		state.pixelState=map.pixelState;state.externalPixels=map.externalPixels;state.flags=flags;gWorlds.put(port,state);registerOffscreenPixMap(state);return state;
	}

	/** Every GWorld owns a GDevice of its own, as on a real Mac (GetGWorldDevice; the port's private GrafVars keep it at +$1A, where
	 *  QuickTime's NewGWorld patch finds it to shrink the inverse table of a direct-colour GWorld). It is not in the device list. */
	private int createOffscreenDevice(int pixMap,int depth,Rect bounds) throws M68kCpuException {
		byte[] device=new byte[62];putWord(device,4,depth>=16?2:0); // gdType: clutType / directType
		putLong(device,6,macApi.createHandle(new byte[4]));putWord(device,10,4); // gdITable (filled below), gdResPref
		putWord(device,20,(depth>1?1:0)|(1<<14)); // gdFlags: gdDevType colour, noDriver
		putLong(device,22,pixMap);putRect(device,34,bounds);putLong(device,42,-1);int handle=macApi.createHandle(device);refreshDeviceInverseTable(handle);return handle;
	}
	private void disposeOffscreenDevice(int device) throws M68kCpuException {
		if(device==0)return;int data=memory.readLong(device);if(data!=0){int table=memory.readLong(data+6);if(table!=0)macApi.disposeHandle(table);}macApi.disposeHandle(device);
	}

	/**
	 * createOffscreenPixMap flag for a screen device's pixels: a quarter of the frame more is allocated behind the visible rows,
	 * as video memory goes on past the screen on a real Mac. Programs that draw straight to the screen from their window's
	 * global position write past the last row when the window hangs over the bottom edge: Revenge of Monsters centres its
	 * 640x480 window below the menu bar on a 640x480 screen and its dissolve wrote 20 rows into the heap behind the screen
	 * (its own PowerPC globals), then into low memory through the clobbered screen pointer, and stopped right after its logo.
	 */
	private static final int SCREEN_MEMORY=1<<30;
	private PixelMapState createOffscreenPixMap(int depth,Rect bounds,int requestedTable,int flags) throws M68kCpuException {return createOffscreenPixMap(depth,bounds,requestedTable,flags,0,0,false);}
	/** {@code gworld}: the pixels of a GWorld (NewGWorld), which get Mac OS X's row padding; screen devices and windows keep tight rows. */
	private PixelMapState createOffscreenPixMap(int depth,Rect bounds,int requestedTable,int flags,int buffer,int givenRowBytes,boolean gworld) throws M68kCpuException {
		int width=bounds.right-bounds.left,height=bounds.bottom-bounds.top,rowBytes=buffer!=0?givenRowBytes:gworld?ownGWorldRowBytes(width,depth):(width*depth+15)/16*2;
		// Mac OS X's NewGWorld pads 32-bit rows by 16 bytes; AstroSquid (FutureBASIC, Carbon) hard-codes the stride (width+4)*4 when it
		// reads the pixels for its OpenGL textures, so a tight row skews the image diagonally. Only GWorlds: a screen's PixMap
		// (DrawSprocket/Display Manager contexts) is width*4, as the video driver gives it.
		if(gworld&&buffer==0&&depth==32&&carbonPowerPcProgram())rowBytes+=16;
		int table=depth<=8?validColorTable(requestedTable)?copyHandle(requestedTable):standardColorTable(depth):directColorTable(depth);
		// baseAddr carries the pixel pointer, as a real GWorld's does once its pixels are locked; many games read it
		// straight from the record after LockPixels instead of calling GetPixBaseAddr; the Handle stays in the PixelMapState
		// (for a caller's buffer a Handle of no block of its own whose master pointer is that buffer)
		int slack=(flags&SCREEN_MEMORY)!=0?rowBytes*(height/4):0;
		int pixels;if(buffer!=0){pixels=macApi.createEmptyHandle(rowBytes*height);memory.writeLong(pixels,buffer);}else pixels=macApi.createHandle(new byte[rowBytes*height+slack]);
		byte[] pixMap=new byte[50];putLong(pixMap,0,memory.readLong(pixels));
		putWord(pixMap,4,0x8000|rowBytes);putRect(pixMap,6,bounds);putLong(pixMap,22,0x00480000);putLong(pixMap,26,0x00480000);
		putWord(pixMap,30,depth>=16?16:0);putWord(pixMap,32,depth);putWord(pixMap,34,depth>=16?3:1);
		putWord(pixMap,36,depth==16?5:depth>=32?8:depth);putLong(pixMap,42,table);int pixMapHandle=macApi.createHandle(pixMap);
		PixelMapState state=new PixelMapState(pixMapHandle,pixels,table,depth,rowBytes,bounds);if((flags&1)!=0)state.pixelState|=PIXELS_PURGEABLE;
		state.externalPixels=buffer!=0;registerOffscreenPixMap(state);return state;
	}

	/**
	 * The pmTable of a direct (16/32-bit) PixMap: Color QuickDraw never leaves it NIL - a direct GDevice or GWorld carries a
	 * dummy ColorTable whose ctSeed is the pixel size and that has no entries. Programs copy the main device's table without
	 * looking at its depth (Bert 1.1: HandToHand of pmTable, MakeITable, SetEntries) and went through a NIL Handle otherwise.
	 * Everything here takes a PixMap's depth, not its table, to tell a direct one.
	 */
	private int directColorTable(int depth) throws M68kCpuException {
		byte[] table=new byte[8];putLong(table,0,depth);putWord(table,4,0);putWord(table,6,0xffff); // ctSeed, ctFlags, ctSize -1: no entries
		return macApi.createHandle(table);
	}

	private void registerOffscreenPixMap(PixelMapState state) throws M68kCpuException {
		offscreenPixMaps.put(state.pixMap,state);offscreenPixMapData.put(memory.readLong(state.pixMap),state);
	}

	private int copyHandle(int source) throws M68kCpuException {
		int size=macApi.handleSize(source),data=source==0?0:memory.readLong(source);return data==0?0:macApi.createHandle(memoryBytes(data,size));
	}

	/**
	 * Application windows: initializes the CGrafPort at {@code port} with a fresh 32-bit
	 * PixMap of the given size (local coordinates start at 0,0) and returns the PixMap Handle.
	 * baseAddr holds the real pixel pointer so programs may poke pixels directly.
	 */
	int newWindowPort(int port,int width,int height) throws M68kCpuException {return newWindowPort(port,width,height,false);}
	/**
	 * {@code classic}: the window was made with NewWindow/GetNewWindow, whose port is a classic GrafPort on a real Mac.
	 * The port stays a CGrafPort inside (window buffers are colour), but the classic record's portBits.bounds - the screen
	 * in local coordinates, at offset 8 - is kept where a program expecting a GrafPort reads it (HyperCard 2.2 takes the
	 * window's global position from it for its visual effects, and wrote its effect frames off into the heap without it);
	 * the grafVars Handle that a CGrafPort keeps at offset 8 moves to the {@link PortState}. See {@link #describeWindowPixMap}.
	 */
	int newWindowPort(int port,int width,int height,boolean classic) throws M68kCpuException {return newWindowPort(port,width,height,classic,false);}
	/** {@code direct}: a 32-bit buffer whatever the screen depth - HyperCard's windows as an external sees them hold the host's
	 *  RGB pixels, and inverting twice must give them back exactly (an indexed buffer would round each pass to the palette). */
	int newWindowPort(int port,int width,int height,boolean classic,boolean direct) throws M68kCpuException {
		Rect bounds=new Rect(0,0,Math.max(1,height),Math.max(1,width));
		PixelMapState map=direct?createOffscreenPixMap(32,bounds,0,0):createWindowPixMap(bounds);map.windowPort=port;
		int mapData=memory.readLong(map.pixMap);memory.writeLong(mapData,memory.readLong(map.pixels));
		fillWhiteOpaque(map);
		initializeGWorldPort(port,map.pixMap,bounds,0);
		memory.writeLong(port+CLIP_RGN,regionHandle(-32767,-32767,32767,32767));
		PortState state=ports.get(port);if(state!=null)state.clip=new Rect(-32767,-32767,32767,32767);
		// a classic GrafPort to the program: bkPat/fillPat/pnPat, not Handles - InitPort's white bkPat and black pnPat (PgColorX makes
		// its mask ports as copies of HyperCard's card port and paints into them before any PenNormal: the first mask was empty)
		if(classic){disposeDefaultPixPats(port);memory.fill(port+BK_PAT,8,0);memory.fill(port+PN_PAT,8,0xff);}
		if(classic&&state!=null){state.classicWindow=true;classicWindowPorts.add(port);state.grafVars=memory.readLong(port+GRAF_VARS);writeRect(port+GRAF_VARS,bounds.top,bounds.left,bounds.bottom,bounds.right);}
		return map.pixMap;
	}

	/**
	 * Programs written for the Mac Plus draw into a window by writing the screen memory at its portBits: on a real Mac the classic
	 * GrafPort of a NewWindow window has portBits = screenBits - baseAddr the screen (ScrnBase), rowBytes the screen's, bounds the
	 * screen in local coordinates. Space Quest (1987) takes baseAddr + 6*rowBytes + 4 right after GetNewWindow and copies its room
	 * pictures there itself, 64 bytes a row. Hosted classic windows keep a CGrafPort layout inside (buffer Handle + $C000), so for
	 * such programs ({@link #setScreenPortBits}, chosen by MacApp68k.readsScreenBase) the record shows the screen BitMap while the
	 * program's own code runs and gets its inner layout back for the length of every trap ({@link #enterToolbox}). Their direct
	 * writes land in the main device, which the windows over it adopt.
	 */
	private boolean screenPortBits;private final java.util.Set<Integer> classicWindowPorts=new java.util.LinkedHashSet<Integer>();private boolean guestPortBitsShown;
	void setScreenPortBits(boolean on){screenPortBits=on;}
	/**
	 * Outermost trap entry: classic windows get their inner portBits back. A program that stored a BitMap of its own there
	 * meanwhile (baseAddr/rowBytes/bounds written straight into the record, no SetPortBits) pointed the port at it - MacPaint
	 * renders its tool cursors that way, DrawChar into a 16x16 BitMap through the desk window's port (baseAddr and rowBytes
	 * stored by hand, the bounds by SetRect on portBits.bounds), and puts the saved screen BitMap back the same way - so the
	 * stores count as a SetPortBits.
	 */
	void enterToolbox() throws M68kCpuException {
		if(!guestPortBitsShown)return;guestPortBitsShown=false;
		for(int port:classicWindowPorts){PortState state=ports.get(port);if(state==null||state.shownBits==null)continue;
			byte[] bitmap=memoryBytes(port+PORT_BITS,14);boolean poked=!java.util.Arrays.equals(bitmap,state.shownBits);byte[] shown=state.shownBits;
			memory.load(port+PORT_BITS,state.internalBits);state.internalBits=null;state.shownBits=null;
			if(!poked)continue;
			if(Mac68kApi.TRACE)System.err.println("[68K] portBits of $"+Integer.toHexString(port)+" stored by the program: "+hex(shown)+" -> "+hex(bitmap));
			int base=getLong(bitmap,0);
			if(state.portBitsHandle!=0&&base!=0&&base!=memory.readLong(0x824)&&(getWord(bitmap,4)&0xc000)==0){memory.load(memory.readLong(state.portBitsHandle),bitmap);continue;} // another BitMap of its own
			memory.load(port+PORT_BITS+6,java.util.Arrays.copyOfRange(shown,6,14)); // the bounds that go with the window buffer, which SetPortBits keeps to put back
			int handle=macApi.createHandle(bitmap);try{setWindowPortBits(port,memory.readLong(handle));}finally{macApi.disposeHandleAndFree(handle);}}
	}
	private static int getLong(byte[] b,int at){return (b[at]&0xff)<<24|(b[at+1]&0xff)<<16|(b[at+2]&0xff)<<8|(b[at+3]&0xff);}
	private static int getWord(byte[] b,int at){return (b[at]&0xff)<<8|(b[at+1]&0xff);}
	private static String hex(byte[] b){StringBuilder sb=new StringBuilder();for(byte x:b)sb.append(String.format("%02X",x&0xff));return sb.toString();}
	/**
	 * Outermost trap exit: classic windows show the program a classic portBits - the screen BitMap while they draw into their
	 * own buffer, the program's BitMap while SetPortBits (or its own stores) point them at one. The bounds stay where the
	 * record keeps them, and a trap that changed them there (SetRect on portBits.bounds) changed the BitMap's too.
	 */
	void leaveToolbox() throws M68kCpuException {
		if(!screenPortBits||classicWindowPorts.isEmpty()||mainDevicePixMap==0)return;
		int device=memory.readLong(mainDevicePixMap);if(device==0)return;
		PixelMapState deviceState=offscreenPixMapData.get(device);int base=guestScreenBase(deviceState!=null?memory.readLong(deviceState.pixels):memory.readLong(device)),rowBytes=memory.readWord(device+4)&0x3fff;
		for(int port:classicWindowPorts){PortState state=ports.get(port);if(state==null||state.internalBits!=null||!isColorPort(port))continue;
			int handle=memory.readLong(port+PORT_BITS),shownBase=base,shownRowBytes=rowBytes;PixelMapState buffer=offscreenPixMaps.get(handle);
			if(handle!=0&&handle==state.portBitsHandle){int own=memory.readLong(handle);memory.copy(port+PORT_BITS+6,own+6,8);shownBase=memory.readLong(own);shownRowBytes=memory.readWord(own+4);}
			else if(buffer==null||buffer.windowPort!=port)continue;
			state.internalBits=memoryBytes(port+PORT_BITS,6);memory.writeLong(port+PORT_BITS,shownBase);memory.writeWord(port+PORT_BITS+4,shownRowBytes);state.shownBits=memoryBytes(port+PORT_BITS,14);guestPortBitsShown=true;}
	}

	/** Where a classic-layout window's record says it is on the screen - {left, top} read back from portBits.bounds the way
	 *  {@link #describeClassicWindowBounds} writes it - or null for other ports. Programs that place their windows by hand
	 *  (HyperCard offsets the bounds itself) are read through this. */
	int[] classicWindowPosition(int port) throws M68kCpuException {
		PortState state=ports.get(port);if(state==null||!state.classicWindow)return null;
		Rect screen=screenRect();int top=(short)memory.readWord(port+GRAF_VARS),left=(short)memory.readWord(port+GRAF_VARS+2);
		return new int[]{screen.left+state.originH-left,screen.top+state.originV-top};
	}

	/** Classic-layout windows: portBits.bounds at offset 8 is the screen in the window's local coordinates (local = global - window position + origin). */
	private void describeClassicWindowBounds(int port,WindowManager68k.Window w) throws M68kCpuException {
		PortState state=ports.get(port);if(state==null||!state.classicWindow)return;
		Rect screen=screenRect();int dv=state.originV-w.top,dh=state.originH-w.left;
		if(Mac68kApi.TRACE)System.err.println("[68K] classic bounds of $"+Integer.toHexString(port)+": origin "+state.originH+","+state.originV+" window "+w.left+","+w.top+" -> top "+(screen.top+dv)+" left "+(screen.left+dh));
		writeRect(port+GRAF_VARS,screen.top+dv,screen.left+dh,screen.bottom+dv,screen.right+dh);
	}

	/** Window buffers start white (32-bit: pad byte $FF, which nothing reads as alpha any more - see Bitmap.pixelValue). */
	private void fillWhiteOpaque(PixelMapState map) throws M68kCpuException {
		int base=memory.readLong(map.pixels);int length=map.rowBytes*(map.bounds.bottom-map.bounds.top);
		if(map.depth==32){memory.fill(base,length,0xff);return;}
		if(map.depth==16){for(int o=0;o<length;o+=2)memory.writeWord(base+o,0x7fff);return;}
		int white=readBitmapRaw(memory.readLong(map.pixMap)).nearestColor(0xffffff),bits=map.depth,value=0;for(int i=0;i<8;i+=bits)value=value<<bits|white;
		memory.fill(base,length,value);
	}

	/**
	 * Hosted windows live on the main screen device, so their buffers take its depth and share its colour table: on an
	 * indexed screen (SetDepth 8) the pixels are CLUT indices, and palette animation through SetEntries or the
	 * video driver changes what is shown, as on a real Macintosh. Direct-colour screens keep 32-bit buffers.
	 */
	private PixelMapState createWindowPixMap(Rect bounds) throws M68kCpuException {
		int depth=windowDepth();
		if(depth==32)return createOffscreenPixMap(32,bounds,0,0);
		int deviceMap=memory.readLong(mainDevicePixMap),table=deviceMap==0?0:memory.readLong(deviceMap+42);
		PixelMapState map=createOffscreenPixMap(depth,bounds,0,0);
		if(depth<=8&&table!=0){int mapData=memory.readLong(map.pixMap);if(map.colorTable!=0)macApi.disposeHandleAndFree(map.colorTable);
			memory.writeLong(mapData+42,table);PixelMapState shared=new PixelMapState(map.pixMap,map.pixels,table,map.depth,map.rowBytes,map.bounds);shared.pixelState=map.pixelState;shared.sharedColorTable=true;
			registerOffscreenPixMap(shared);return shared;}
		return map;
	}

	/** Bytes held by window pixel buffers: a real Mac's windows draw into screen memory, outside the application partition
	 *  (see {@link Mac68kApi#hostReserve}). */
	long windowBufferBytes(){
		long bytes=0;
		for(PixelMapState s:offscreenPixMaps.values())if(s.windowPort!=0&&!s.externalPixels)bytes+=(long)s.rowBytes*Math.max(0,s.bounds.bottom-s.bounds.top);
		return bytes;
	}

	/** Depth of new window buffers: that of the hosted main screen device (32 when there is none yet or the VM has a real screen). */
	int windowDepth() throws M68kCpuException {
		Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted()||mainDevicePixMap==0)return 32;
		int map=memory.readLong(mainDevicePixMap);if(map==0)return 32;int depth=memory.readWord(map+32);return depth==8||depth==16||depth==4||depth==2||depth==1?depth:32;
	}

	/**
	 * On a real Macintosh a window port has no pixels of its own: its portPixMap describes the screen (baseAddr = screen
	 * memory, rowBytes = the screen's, bounds = the screen in the window's local coordinates, so bounds.topLeft is minus the
	 * window's global position plus the origin). Programs that draw straight into screen memory take the base address from
	 * there and add global coordinates (WonderCrackers computes screenBase + globalV * screenWidth + globalH); others use the
	 * bounds to learn the window's position. Hosted windows keep their private buffers for QuickDraw - the {@link PixelMapState}
	 * carries that geometry and every internal reader takes it from there - while the guest-visible record is made to describe
	 * the main device like the ROM would. The direct writes then land in the device PixMap, which MacAppHost composites over
	 * the window, and GetPixBaseAddr on the window's PixMap answers the screen address. Called whenever the window is
	 * registered, moved, resized or the screen device changes; a no-op until the window buffer has the device's depth.
	 */
	void describeWindowPixMap(int port) throws M68kCpuException {
		Toolbox68k tb=macApi.application();if(tb==null||tb.windows==null)return;
		WindowManager68k.Window w=tb.windows.window(port);if(w==null)return;
		// the classic bounds carry the window's position whether or not a host shows it: ShowWindow reads them back
		// (adoptRecordGeometry), and an XWindoid opened with no stack (App68kXWindowTest) went to 0,0 without them
		describeClassicWindowBounds(port,w);if(!tb.hosted()||mainDevicePixMap==0)return;
		int mapHandle=memory.readLong(port+PORT_BITS);PixelMapState state=mapHandle==0?null:offscreenPixMaps.get(mapHandle);
		if(state==null||state.windowPort!=port)return;
		int device=memory.readLong(mainDevicePixMap);if(device==0||memory.readWord(device+32)!=state.depth)return;
		PixelMapState deviceState=offscreenPixMapData.get(device);int base=deviceState!=null?memory.readLong(deviceState.pixels):memory.readLong(device);
		int data=memory.readLong(mapHandle);if(data==0||base==0)return;
		Rect screen=readRect(device+6);PortState ps=ports.get(port);int dv=(ps==null?0:ps.originV)-w.top,dh=(ps==null?0:ps.originH)-w.left; // local = global - window position + origin
		memory.writeLong(data,base);memory.writeWord(data+4,0x8000|(memory.readWord(device+4)&0x3fff));
		writeRect(data+6,screen.top+dv,screen.left+dh,screen.bottom+dv,screen.right+dh);
		state.screenDescribed=true;
	}

	/** The pixel buffer geometry of a PixMap: the record's, unless it is a window buffer described as the screen. */
	private Rect pixMapBounds(int data,PixelMapState state) throws M68kCpuException {return state!=null&&state.screenDescribed?state.bounds:readRect(data+6);}
	private int pixMapRowBytes(int data,PixelMapState state) throws M68kCpuException {return state!=null&&state.screenDescribed?state.rowBytes:memory.readWord(data+4)&0x3fff;}

	/** Replaces the pixel buffer of a window port after a resize; returns the new PixMap Handle. */
	int resizeWindowPort(int port,int width,int height) throws M68kCpuException {return resizeWindowPort(port,width,height,0xffffff);}
	int resizeWindowPort(int port,int width,int height,int contentColor) throws M68kCpuException {return resizeWindowPort(port,width,height,contentColor,null);}
	/** {@code frame}: local bounds {top,left,bottom,right} of the buffer when it is larger than the content (a window whose frame a
	 *  custom WDEF draws: the structure region in local coordinates, negative above and left of the content); null for the content alone. */
	int resizeWindowPort(int port,int width,int height,int contentColor,int[] frame) throws M68kCpuException {
		int oldMap=memory.readLong(port+PORT_BITS);PixelMapState old=offscreenPixMaps.get(oldMap);
		Rect bounds=frame!=null&&frame[2]>frame[0]&&frame[3]>frame[1]?new Rect(frame[0],frame[1],frame[2],frame[3]):new Rect(0,0,Math.max(1,height),Math.max(1,width));
		PixelMapState map;
		try{map=createWindowPixMap(bounds);}
		catch(M68kCpuException e){
			// Both buffers do not fit at once (a screen-sized window may be opened in a 192 KB heap and the
			// host then shrinks it): give up the old pixels, the program repaints on the update event that follows.
			if(old==null)throw e;
			freeOffscreenPixMap(old);old=null;map=createWindowPixMap(bounds);
		}
		map.windowPort=port;
		// The new buffer is filled and gets the old pixels while both records are still apart: the copy used to run after the
		// new record had moved into the old Handle, so it read the new (white) buffer - every resize lost the window's content,
		// and what the program did not invalidate stayed blank (SoundApp's playlist header after a grow, PowerPlant)
		{int newData=memory.readLong(map.pixMap);memory.writeLong(newData,memory.readLong(map.pixels));
			int originV=0,originH=0;PortState state=ports.get(port);if(state!=null){originV=state.originV;originH=state.originH;}
			if(originV!=0||originH!=0){offsetRectAt(newData+6,originH,originV);map.bounds=new Rect(bounds.top+originV,bounds.left+originH,bounds.bottom+originV,bounds.right+originH);}
			fillWhiteOpaque(map);memory.writeLong(port+PORT_BITS,map.pixMap);if(contentColor!=0xffffff)fillWindowPort(port,contentColor);
			if(old!=null)copyOffscreenPixels(old,map);}
		// A window's portPixMap Handle never changes on a real Mac (SetDepth rewrites the record in place), and programs
		// keep the record pointer: HyperCard 2.2 reads the screen base for its effects from the record it took at
		// startup, so a fresh Handle here left it drawing through a freed record (jump table overwritten with white
		// pixels after SetMonitor(8) on a 16-bit screen). The new record moves into the old Handle.
		if(old!=null&&oldMap!=0&&memory.readLong(oldMap)!=0){
			int newData=memory.readLong(map.pixMap),oldData=memory.readLong(oldMap);
			offscreenPixMaps.remove(map.pixMap);offscreenPixMapData.remove(newData);
			memory.load(oldData,memoryBytes(newData,50));macApi.disposeHandleAndFree(map.pixMap);
			map.pixMap=oldMap;offscreenPixMaps.put(oldMap,map);offscreenPixMapData.put(oldData,map);
			offscreenPixMapData.remove(oldData);offscreenPixMapData.put(oldData,map); // the record pointer now names the new state
		}
		memory.writeLong(port+PORT_BITS,map.pixMap);
		int originV=0,originH=0;PortState state=ports.get(port);if(state!=null){originV=state.originV;originH=state.originH;}
		if(old!=null){if(old.pixMap==map.pixMap)freeOffscreenPixels(old);else freeOffscreenPixMap(old);}
		writeRect(port+PORT_RECT,originV,originH,originV+height,originH+width);
		int visible=memory.readLong(port+VIS_RGN);if(visible!=0&&!readRegionRect(visible).empty())writeRegionRect(visible,new Rect(originV,originH,originV+height,originH+width)); // a never-shown window keeps its empty visRgn (see setWindowPortVisible)
		describeWindowPixMap(port);
		return map.pixMap;
	}

	/**
	 * The visRgn of a window port as the Window Manager keeps it: empty while the window has never been shown (drawing into
	 * an invisible window has no effect - MacDraw 0.9 redraws its hidden Clipboard window whenever the scrap changes and
	 * painted a white band over the document), the whole port once ShowWindow has computed it. A program that gives a
	 * never-shown window a visRgn of its own (see {@link #hiddenWindowScreenBitmap}) draws through that.
	 */
	void setWindowPortVisible(int port,boolean visible) throws M68kCpuException {
		int region=memory.readLong(port+VIS_RGN);if(region==0)return;
		writeRegionRect(region,visible?readRect(port+PORT_RECT):new Rect(0,0,0,0));
	}

	/** Fills a window's whole buffer with a colour at the buffer's own depth (an indexed buffer takes the nearest table entry). */
	/** Fills a window's port with an 8x8 one-bit pattern in two colours (1 bits foreground), aligned to global coordinates. */
	void fillWindowPattern(int port,byte[] pattern,int fore,int back) throws M68kCpuException {
		int mapHandle=port==0?0:memory.readLong(port+PORT_BITS);if(mapHandle==0)return;int map=memory.readLong(mapHandle);if(map==0)return;
		Bitmap bits=readBitmapRaw(map);int height=bits.bounds.bottom-bits.bounds.top,width=bits.bounds.right-bits.bounds.left;if(bits.base==0||height<=0||width<=0)return;
		if(!(bits.direct&&bits.pixelSize==32)){fillWindowPort(port,back);return;}
		for(int y=0;y<height;y++){int row=pattern[(y+bits.bounds.top)&7]&0xff,o=bits.base+y*bits.rowBytes;
			for(int x=0;x<width;x++,o+=4)memory.writeLong(o,0xff000000|((row&(0x80>>>((x+bits.bounds.left)&7)))!=0?fore:back));}
	}
	void fillWindowPort(int port,int rgb) throws M68kCpuException {
		int mapHandle=port==0?0:memory.readLong(port+PORT_BITS);if(mapHandle==0)return;int map=memory.readLong(mapHandle);if(map==0)return;
		Bitmap bits=readBitmapRaw(map);int height=bits.bounds.bottom-bits.bounds.top,width=bits.bounds.right-bits.bounds.left;if(bits.base==0||height<=0||width<=0)return;
		if(bits.direct&&bits.pixelSize==32){for(int y=0;y<height;y++){int o=bits.base+y*bits.rowBytes;for(int x=0;x<width;x++,o+=4)memory.writeLong(o,0xff000000|rgb);}return;}
		if(bits.direct){int v=(rgb>>>19&31)<<10|(rgb>>>11&31)<<5|(rgb>>>3&31);for(int y=0;y<height;y++){int o=bits.base+y*bits.rowBytes;for(int x=0;x<width;x++,o+=2)memory.writeWord(o,v);}return;}
		int index=bits.nearestColor(rgb),value=0;for(int i=0;i<8;i+=bits.pixelSize)value=value<<bits.pixelSize|index;
		for(int y=0;y<height;y++)memory.fill(bits.base+y*bits.rowBytes,(width*bits.pixelSize+7)>>>3,value);
	}

	/** Fills a 32-bit window port with a colour tile ({@code argb}, {@code pw} x {@code ph}, from the port's top left); false
	 *  for other depths (the caller fills the colour). A Kaleidoscope scheme's dialog background (Aqua III's pin stripes). */
	boolean fillWindowPortTile(int port,int[] argb,int pw,int ph) throws M68kCpuException {
		int mapHandle=port==0?0:memory.readLong(port+PORT_BITS);if(mapHandle==0||pw<=0||ph<=0)return false;int map=memory.readLong(mapHandle);if(map==0)return false;
		Bitmap bits=readBitmapRaw(map);int height=bits.bounds.bottom-bits.bounds.top,width=bits.bounds.right-bits.bounds.left;
		if(bits.base==0||height<=0||width<=0||!bits.direct||bits.pixelSize!=32)return false;
		for(int y=0;y<height;y++){int o=bits.base+y*bits.rowBytes,row=(y%ph)*pw;for(int x=0;x<width;x++,o+=4)memory.writeLong(o,0xff000000|argb[row+x%pw]);}
		return true;
	}
	/** A PixPat Handle made from the bytes of a 'ppat' resource (as GetPixPat makes one), or 0. */
	int pixPatFromResource(byte[] data) throws M68kCpuException {
		int handle=macApi.createHandle(data);if(!expandPixPatResource(handle)){macApi.disposeHandle(handle);return 0;}return handle;
	}
	/** Makes {@code pattern} the background pattern (bkPixPat) of a colour port. */
	void setBackPixPat(int port,int pattern) throws M68kCpuException {if(isColorPort(port)&&pattern!=0)memory.writeLong(port+BK_PAT,pattern);}

	/** Pixel pointer and rowBytes of a window port's PixMap: {base,rowBytes,width,height}. */
	int[] windowPortPixels(int port) throws M68kCpuException {
		int mapHandle=memory.readLong(port+PORT_BITS);if(mapHandle==0)return null;int map=memory.readLong(mapHandle);if(map==0)return null;
		PixelMapState state=offscreenPixMaps.get(mapHandle);int base=state!=null?memory.readLong(state.pixels):memory.readLong(map);
		Rect bounds=pixMapBounds(map,state);int depth=(memory.readWord(map+4)&0x8000)!=0?memory.readWord(map+32):1;return new int[]{base,pixMapRowBytes(map,state),bounds.right-bounds.left,bounds.bottom-bounds.top,depth};
	}

	/** {base,rowBytes,width,height,boundsTop,boundsLeft} of a 32-bit direct port, or null for other ports. */
	/** {base,rowBytes,width,height,boundsTop,boundsLeft,depth} of a colour port's PixMap at any depth (window buffers of an indexed or 16-bit screen), or null. */
	int[] colorPortPixels(int port) throws M68kCpuException {
		if(port==0||!isColorPort(port))return null;
		int mapHandle=memory.readLong(port+PORT_BITS);if(mapHandle==0)return null;int map=memory.readLong(mapHandle);if(map==0)return null;
		int rowWord=memory.readWord(map+4);if((rowWord&0xc000)!=0x8000)return null;int depth=memory.readWord(map+32);
		if(depth!=1&&depth!=2&&depth!=4&&depth!=8&&depth!=16&&depth!=32)return null;
		PixelMapState state=offscreenPixMaps.get(mapHandle);int base=state!=null?memory.readLong(state.pixels):memory.readLong(map);
		Rect bounds=pixMapBounds(map,state);return new int[]{base,pixMapRowBytes(map,state),bounds.right-bounds.left,bounds.bottom-bounds.top,bounds.top,bounds.left,depth};
	}
	int[] directPortPixels(int port) throws M68kCpuException {
		if(port==0||!isColorPort(port))return null;
		int mapHandle=memory.readLong(port+PORT_BITS);if(mapHandle==0)return null;int map=memory.readLong(mapHandle);if(map==0)return null;
		int rowWord=memory.readWord(map+4);if((rowWord&0xc000)!=0x8000)return null;
		if(memory.readWord(map+30)!=16||memory.readWord(map+32)!=32)return null;
		PixelMapState state=offscreenPixMaps.get(mapHandle);int base=state!=null?memory.readLong(state.pixels):memory.readLong(map);
		Rect bounds=pixMapBounds(map,state);return new int[]{base,pixMapRowBytes(map,state),bounds.right-bounds.left,bounds.bottom-bounds.top,bounds.top,bounds.left};
	}

	/**
	 * The current port's clip in local coordinates. It is read from the port's clipRgn Handle on every use, not
	 * cached: programs install their own regions in the port record and edit them in place (some keep a
	 * clip stack that way, without SetClip). While a picture plays, the picture's clip applies instead.
	 */
	private Rect currentClip() throws M68kCpuException {
		if(currentPort==0)return new Rect(-32767,-32767,32767,32767);
		PortState state=ports.get(currentPort);
		if(playingPicture&&state!=null)return state.clip;
		int handle=memory.readLong(currentPort+CLIP_RGN);
		if(handle==0||memory.readLong(handle)==0)return new Rect(-32767,-32767,32767,32767);
		return readRegionRect(handle);
	}

	/** Visible/clip rectangle of the current port in local coordinates: {top,left,bottom,right}. */
	int[] currentDrawableRect() throws M68kCpuException {
		if(currentPort==0)return new int[]{0,0,0,0};
		Rect clip=currentClip();
		Rect visible=readRegionRect(memory.readLong(currentPort+VIS_RGN));Rect r=intersection(clip,visible);
		return new int[]{r.top,r.left,r.bottom,r.right};
	}

	/**
	 * The current port's clipRgn and visRgn as a Java2D shape in local coordinates when either is not a plain rectangle, else
	 * null (the box of {@link #currentDrawableRect} says it all). Java2D surfaces (text, TETextBox) are clipped with it as
	 * QuickDraw's own pixel loops are by {@link #regionsAllow}: PlayerPRO's digital view, partly covered by windows in front
	 * (its visRgn not a rectangle), had every TETextBox erase skip the covered part and the text go there anyway.
	 */
	java.awt.Shape currentRegionClip() throws M68kCpuException {
		if(!clipHasShape())return null;
		QdRegion clip=regionShape(memory.readLong(currentPort+CLIP_RGN)),visible=regionShape(memory.readLong(currentPort+VIS_RGN));
		if(clip==regionClipKey[0]&&visible==regionClipKey[1]&&regionClipArea!=null)return regionClipArea;
		java.awt.geom.Area area=null;
		for(QdRegion r:new QdRegion[]{clip,visible}){if(r==null)continue;
			java.awt.geom.Area a=new java.awt.geom.Area();for(int[] q:r.rects())a.add(new java.awt.geom.Area(new java.awt.Rectangle(q[1],q[0],q[3]-q[1],q[2]-q[0])));
			if(area==null)area=a;else area.intersect(a);}
		regionClipKey[0]=clip;regionClipKey[1]=visible;regionClipArea=area;
		return area;
	}
	private final QdRegion[] regionClipKey=new QdRegion[2];private java.awt.geom.Area regionClipArea;

	/** Host text rendering for non-direct ports: draws set pixels of a mask at (h,top) with the pen colour and text mode. */
	void drawMask(BufferedImage mask,int h,int top,int mode) throws M68kCpuException {drawMask(mask,h,top,mode,null);}
	/** @param box the character boxes of the text as local {left, top, width, height}: see {@link #drawCoverageMask(BufferedImage,int,int,int,int[])} */
	void drawMask(BufferedImage mask,int h,int top,int mode,int[] box) throws M68kCpuException {drawMask(mask,h,top,mode,box,null);}
	/** @param hiRes the same text for the smooth zoom's plane (see {@link #smoothTextBegin}), or null */
	void drawMask(BufferedImage mask,int h,int top,int mode,int[] box,SmoothZoom.Text hiRes) throws M68kCpuException {
		if(Mac68kApi.TRACE)System.err.println("[68K] drawMask "+mask.getWidth()+"x"+mask.getHeight()+" at "+h+","+top+" mode "+mode);
		if(currentPort==0)return;Bitmap bitmap=readCurrentBitmap();boolean clearToo=box!=null&&boxActs(mode);
		int[] area=clearToo?union(h,top,mask.getWidth(),mask.getHeight(),box):new int[]{h,top,h+mask.getWidth(),top+mask.getHeight()};
		SmoothText hi=smoothTextBegin(bitmap,area,hiRes);
		try{
			for(int tv=area[1];tv<area[3];tv++)for(int th=area[0];th<area[2];th++){int x=th-h,y=tv-top;
				boolean bit=x>=0&&y>=0&&x<mask.getWidth()&&y<mask.getHeight()&&(mask.getRGB(x,y)&0xffffff)!=0;
				if(!bit&&!(clearToo&&inBox(th,tv,box)))continue;
				if(portAllows(th,tv))bitmap.setInkRgb(th,tv,transferBit(bit,bitmap.getInkRgb(th,tv),mode,bitmap.pixelSize));}
			smoothTextDraw(hi,bitmap,h,top,mode,box,clearToo?SMOOTH_CLEAR_IN_BOX:SMOOTH_CLEAR_NONE,area);
		}finally{smoothTextEnd(hi);}
	}
	/** notSrcCopy/notSrcOr/notSrcXor/notSrcBic: the source is inverted, so the clear bits of a glyph box are the ones that act. */
	private static boolean invertedSource(int mode){return (mode&~3)==4;}
	/** The clear pixels of the character boxes act as well: the inverted-source modes, and srcCopy, which paints them in the
	 *  background colour (Dialoger's group-box label "Resource Type" is drawn in srcCopy over its FrameRoundRect line, which
	 *  showed through the text on a non-direct port; the direct-port path paints the box). */
	private static boolean boxActs(int mode){return mode==0||invertedSource(mode);}
	private static boolean inBox(int h,int v,int[] box){return h>=box[0]&&h<box[0]+box[2]&&v>=box[1]&&v<box[1]+box[3];}
	/** {left, top, right, bottom} covering a mask at (h,top) and a {left, top, width, height} box. */
	private static int[] union(int h,int top,int width,int height,int[] box){
		return new int[]{Math.min(h,box[0]),Math.min(top,box[1]),Math.max(h+width,box[0]+box[2]),Math.max(top+height,box[1]+box[3])};}

	/** Text through a 1-bit glyph mask with a QuickDraw text mode: srcCopy/notSrcCopy paint the clear bits with the background too. */
	void drawGlyphMask(BufferedImage mask,int h,int top,int mode) throws M68kCpuException {drawGlyphMask(mask,h,top,mode,null);}
	/**
	 * @param box the character boxes of the run as local {left, top, width, height}: with srcCopy/notSrcCopy only the
	 *        clear bits inside it take the background (styled ink spills beyond the boxes; the spill is OR-ed like srcOr)
	 */
	void drawGlyphMask(BufferedImage mask,int h,int top,int mode,int[] box) throws M68kCpuException {drawGlyphMask(mask,h,top,mode,box,null);}
	/** @param hiRes the same text for the smooth zoom's plane (see {@link #smoothTextBegin}), or null */
	void drawGlyphMask(BufferedImage mask,int h,int top,int mode,int[] box,SmoothZoom.Text hiRes) throws M68kCpuException {
		if(Mac68kApi.TRACE)System.err.println("[68K] drawGlyphMask "+mask.getWidth()+"x"+mask.getHeight()+" at "+h+","+top+" mode "+mode+(box==null?"":" box "+java.util.Arrays.toString(box)));
		if(currentPort==0)return;Bitmap bitmap=readCurrentBitmap();boolean paintClear=(mode&3)==0||invertedSource(mode);
		int[] area={h,top,h+mask.getWidth(),top+mask.getHeight()};
		SmoothText hi=smoothTextBegin(bitmap,area,hiRes);
		try{
			for(int y=0;y<mask.getHeight();y++)for(int x=0;x<mask.getWidth();x++){boolean bit=(mask.getRGB(x,y)&0xffffff)!=0;int th=h+x,tv=top+y;
				if(!bit&&(!paintClear||box!=null&&(th<box[0]||th>=box[0]+box[2]||tv<box[1]||tv>=box[1]+box[3])))continue;
				if(portAllows(th,tv))bitmap.setInkRgb(th,tv,transferBit(bit,bitmap.getInkRgb(th,tv),mode,bitmap.pixelSize));}
			smoothTextDraw(hi,bitmap,h,top,mode,box,!paintClear?SMOOTH_CLEAR_NONE:box==null?SMOOTH_CLEAR_ALL:SMOOTH_CLEAR_IN_BOX,area);
		}finally{smoothTextEnd(hi);}
	}

	/**
	 * Like {@link #drawMask} for an anti-aliased mask: each pixel of the (TYPE_BYTE_GRAY) mask is a coverage 0-255,
	 * and a partly covered pixel blends what the text mode would leave there (0) with what it would paint (255).
	 * Indexed destinations get the nearest palette colour of the blend (the 8-bit system palette has 16 greys).
	 */
	void drawCoverageMask(BufferedImage mask,int h,int top,int mode) throws M68kCpuException {drawCoverageMask(mask,h,top,mode,null);}
	/**
	 * @param box the character boxes of the text as local {left, top, width, height}, or null: with an inverted-source mode
	 *        (notSrcOr/notSrcXor/notSrcBic) the uncovered pixels inside it act too - REALbasic draws a selected browser item
	 *        in notSrcBic over a black background colour, which gives white letters on a black box.
	 */
	void drawCoverageMask(BufferedImage mask,int h,int top,int mode,int[] box) throws M68kCpuException {drawCoverageMask(mask,h,top,mode,box,null);}
	/** @param hiRes the same text for the smooth zoom's plane (see {@link #smoothTextBegin}), or null */
	void drawCoverageMask(BufferedImage mask,int h,int top,int mode,int[] box,SmoothZoom.Text hiRes) throws M68kCpuException {
		if(Mac68kApi.TRACE)System.err.println("[68K] drawCoverageMask "+mask.getWidth()+"x"+mask.getHeight()+" at "+h+","+top+" mode "+mode);
		if(currentPort==0)return;Bitmap bitmap=readCurrentBitmap();java.awt.image.Raster raster=mask.getRaster();boolean clearToo=box!=null&&boxActs(mode);
		int[] area=clearToo?union(h,top,mask.getWidth(),mask.getHeight(),box):new int[]{h,top,h+mask.getWidth(),top+mask.getHeight()};
		SmoothText hi=smoothTextBegin(bitmap,area,hiRes);
		try{
			for(int tv=area[1];tv<area[3];tv++)for(int th=area[0];th<area[2];th++){int x=th-h,y=tv-top;
				int coverage=x>=0&&y>=0&&x<mask.getWidth()&&y<mask.getHeight()?raster.getSample(x,y,0):0;if(coverage==0&&!(clearToo&&inBox(th,tv,box)))continue;
				if(!portAllows(th,tv))continue;int destination=bitmap.getInkRgb(th,tv),on=transferBit(true,destination,mode,bitmap.pixelSize);
				if(coverage>=255){bitmap.setInkRgb(th,tv,on);continue;}
				int off=transferBit(false,destination,mode,bitmap.pixelSize);bitmap.setInkRgb(th,tv,blendInk(off,on,coverage));}
			smoothTextDraw(hi,bitmap,h,top,mode,box,clearToo?SMOOTH_CLEAR_IN_BOX:SMOOTH_CLEAR_NONE,area);
		}finally{smoothTextEnd(hi);}
	}

	/** Per-channel mix of two inks: weight 0 gives {@code from}, 255 gives {@code to}. */
	private static int blendInk(int from,int to,int weight){
		int result=0;for(int shift=0;shift<=16;shift+=8){int a=from>>>shift&255,b=to>>>shift&255;result|=((a*(255-weight)+b*weight+127)/255)<<shift;}return result;
	}

	/** Mixes an RGB colour into one pixel of the current port with the given opacity (0-255): anti-aliased edges of staged host drawing. */
	void blendRgb(int h,int v,int rgb,int alpha) throws M68kCpuException {
		if(currentPort==0||!portAllows(h,v))return;Bitmap bitmap=readCurrentBitmap();
		bitmap.setInkRgb(h,v,blendInk(bitmap.getInkRgb(h,v),~rgb&0xffffff,alpha));
	}

	/** Fills a local rectangle with a solid RGB colour in the current port (any depth). */
	void fillRgb(int top,int left,int bottom,int right,int rgb) throws M68kCpuException {
		if(currentPort==0)return;Bitmap bitmap=readCurrentBitmap();int ink=~rgb&0xffffff;Clip clip=clipContext(bitmap);
		Rect area=intersection(new Rect(top,left,bottom,right),clip.bounds);
		for(int v=area.top;v<area.bottom;v++){if(clip.shaped()){for(int h=area.left;h<area.right;h++)if(clip.allows(h,v))bitmap.setInkRgb(h,v,ink);}
			else bitmap.fillSpan(v,area.left,area.right,0xff,ink,ink);}
	}

	/** The RGB pixels of a local rectangle of the current port (row-major; the Drag Manager keeps what its hilite covers). */
	int[] readRgb(int top,int left,int bottom,int right) throws M68kCpuException {
		int w=Math.max(0,right-left),h=Math.max(0,bottom-top);int[] pixels=new int[w*h];if(currentPort==0||w==0||h==0)return pixels;Bitmap bitmap=readCurrentBitmap();
		for(int v=0;v<h;v++)for(int x=0;x<w;x++)pixels[v*w+x]=~bitmap.getInkRgb(left+x,top+v)&0xffffff;return pixels;
	}
	/** The RGB pixels (0xRRGGBB, row-major) of a rectangle of a PixMap or BitMap, in its own coordinates: what CompressImage
	 *  compresses (the GWorld UxCardToMovie copies each card into). */
	int[] readBitmapRgb(int pointer,int top,int left,int bottom,int right) throws M68kCpuException {
		int w=Math.max(0,right-left),h=Math.max(0,bottom-top);int[] pixels=new int[w*h];if(pointer==0||w==0||h==0)return pixels;Bitmap bitmap=readBitmap(pointer);
		for(int v=0;v<h;v++)for(int x=0;x<w;x++)pixels[v*w+x]=~bitmap.getInkRgb(left+x,top+v)&0xffffff;return pixels;
	}
	/** Whether QuickDraw knows the port (opened and not closed: a GWorld disposed since is not). */
	boolean knownPort(int port){return ports.containsKey(port);}
	/** Puts pixels read by {@link #readRgb} back. */
	void writeRgb(int top,int left,int bottom,int right,int[] pixels) throws M68kCpuException {
		int w=Math.max(0,right-left),h=Math.max(0,bottom-top);if(currentPort==0||w==0||h==0||pixels.length<w*h)return;Bitmap bitmap=readCurrentBitmap();
		for(int v=0;v<h;v++)for(int x=0;x<w;x++)bitmap.setInkRgb(left+x,top+v,~pixels[v*w+x]&0xffffff);
	}
	/** The RGB pixels of {@code port}'s own buffer (any depth, never routed to the screen): the card window shown over HyperZebra's card. */
	int[] readPortRgb(int port,int width,int height) throws M68kCpuException {
		int[] pixels=new int[width*height];Bitmap bitmap=readBitmapRaw(bitmapPointer(port));
		for(int v=0;v<height;v++)for(int x=0;x<width;x++)pixels[v*width+x]=~bitmap.getInkRgb(x,v)&0xffffff;return pixels;
	}
	/** Fills {@code port}'s own buffer from RGB pixels (the card snapshot an external starts from). */
	void writePortRgb(int port,int width,int height,int[] pixels) throws M68kCpuException {
		Bitmap bitmap=readBitmapRaw(bitmapPointer(port));
		for(int v=0;v<height;v++)for(int x=0;x<width;x++)bitmap.setInkRgb(x,v,~pixels[v*width+x]&0xffffff);
	}
	/**
	 * The main screen device's pixels in a global rectangle as RGB (white outside the screen), or null without a device. On a
	 * real Mac HyperCard's card window is that memory, and some externals draw the card there themselves: HyperTint's blitter
	 * writes its colour pictures straight into the screen at the card window's place.
	 */
	int[] readScreenRgb(int top,int left,int width,int height) throws M68kCpuException {
		int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(map==0)return null;
		Bitmap bitmap=readBitmapRaw(map);int[] pixels=new int[width*height];
		for(int v=0;v<height;v++)for(int x=0;x<width;x++)pixels[v*width+x]=~bitmap.getInkRgb(left+x,top+v)&0xffffff;
		return pixels;
	}
	/**
	 * HyperCard's card window as an external sees it on a real Mac: a window port whose PixMap is the screen - baseAddr the
	 * screen memory, rowBytes, pixel size and colour table the screen's, bounds the screen in the window's local coordinates.
	 * HyperTint computes its blits from that record (8-bit pixels at the card's place in screen memory); a 32-bit buffer of
	 * the card's own size sent its visual effects through the rest of memory, code included. Makes {@code port} (made by
	 * {@link #newWindowPort}) such a port, its own buffer given up, with the window at global ({@code left},{@code top});
	 * called again whenever the screen or the window's place may have changed. False without a screen device.
	 */
	boolean describeScreenPort(int port,int top,int left) throws M68kCpuException {
		int device=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(device==0)return false;
		int mapHandle=memory.readLong(port+PORT_BITS);if(mapHandle==0)return false;
		PixelMapState state=offscreenPixMaps.get(mapHandle);
		int data=memory.readLong(mapHandle);if(data==0)return false;
		if(state!=null){ // the private buffer goes; the PixMap Handle stays the port's
			forgetPlane(state);offscreenPixMaps.remove(mapHandle);offscreenPixMapData.remove(data);
			if(state.colorTable!=0&&!state.sharedColorTable)macApi.disposeHandleAndFree(state.colorTable);
			macApi.disposeHandleAndFree(state.pixels);
		}
		for(int i=0;i<50;i+=2)memory.writeWord(data+i,memory.readWord(device+i)); // the device's PixMap record (its table Handle is shared)
		Rect screen=readRect(device+6);
		writeRect(data+6,screen.top-top,screen.left-left,screen.bottom-top,screen.right-left);
		return true;
	}
	/** Puts RGB pixels into the main screen device at a global rectangle (what the screen shows there: the card under its window). */
	void writeScreenRgb(int top,int left,int width,int height,int[] pixels) throws M68kCpuException {writeScreenRgb(top,left,width,height,pixels,null);}
	/** The same, leaving the pixels of {@code keep} (in the rectangle's own coordinates) alone: windows in front of the card. */
	void writeScreenRgb(int top,int left,int width,int height,int[] pixels,QdRegion keep) throws M68kCpuException {
		int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(map==0)return;
		Bitmap bitmap=readBitmapRaw(map);
		for(int v=0;v<height;v++)for(int x=0;x<width;x++){if(keep!=null&&keep.contains(x,v))continue;bitmap.setInkRgb(left+x,top+v,~pixels[v*width+x]&0xffffff);}
	}
	/** Sets a port's visRgn (the Window Manager's job for a window: what of it is not covered by windows in front). */
	void setVisRgn(int port,QdRegion region) throws M68kCpuException {int vis=memory.readLong(port+24);if(vis!=0)writeRegion(vis,region);}
	/** The main screen device's pixel size (0 without one). */
	int screenDepth() throws M68kCpuException {int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);return map==0?0:memory.readWord(map+32);}
	/** The seed of the main screen device's colour table (changes with SetEntries/palettes; 0 without one). */
	int screenTableSeed() throws M68kCpuException {int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);int table=map==0?0:memory.readLong(map+42);int data=table==0?0:memory.readLong(table);return data==0?0:memory.readLong(data);}
	/** The screen memory holding the global rows top..top+height-1: {address, length}, or null without a device (or rows off it). */
	int[] screenRows(int top,int height) throws M68kCpuException {
		int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(map==0)return null;
		int base=memory.readLong(map),rowBytes=memory.readWord(map+4)&0x3fff,boundsTop=(short)memory.readWord(map+6),boundsBottom=(short)memory.readWord(map+10);
		int first=Math.max(top,boundsTop),last=Math.min(top+height,boundsBottom);if(base==0||rowBytes==0||last<=first)return null;
		return new int[]{base+(first-boundsTop)*rowBytes,(last-first)*rowBytes};
	}
	/** SetPort as a program would call it. */
	void setPortTo(M68kCpu cpu,int port) throws M68kCpuException {int saved=cpu.a[7];cpu.a[7]-=4;memory.writeLong(cpu.a[7],port);setPort(cpu);cpu.a[7]=saved;}
	/** Inverts a local rectangle in the current port (InvertRect without the picture recording). */
	void invertRect(int top,int left,int bottom,int right) throws M68kCpuException {rasterRect(new Rect(top,left,bottom,right),3,0);}

	/** Whether (h,v) is inside the current port's clip and visible regions. */
	boolean visibleAt(int h,int v) throws M68kCpuException {return currentPort!=0&&portAllows(h,v);}
	int currentPortDepth() throws M68kCpuException {return currentPort==0?32:isColorPort(currentPort)?currentPixelSize():1;}

	/** Disposes the PixMap created by {@link #newWindowPort}. */
	void disposeWindowPort(int port) throws M68kCpuException {
		int mapHandle=memory.readLong(port+PORT_BITS);PixelMapState state=offscreenPixMaps.get(mapHandle);
		if(state!=null)disposeOffscreenPixMap(state);
		int visible=memory.readLong(port+VIS_RGN),clip=memory.readLong(port+CLIP_RGN),vars=grafVarsOf(port);
		if(visible!=0)macApi.disposeHandle(visible);if(clip!=0)macApi.disposeHandle(clip);if(vars!=0)macApi.disposeHandle(vars);
		ports.remove(port);classicWindowPorts.remove(port);disposeDefaultPixPats(port);if(currentPort==port)setCurrentPort(0);
	}

	/** Current text state for host-side text drawing: {font,face,mode,size}. */
	int[] textState() throws M68kCpuException {
		if(currentPort==0)return new int[]{0,0,1,12};
		return new int[]{memory.readWord(currentPort+TX_FONT),memory.readByte(currentPort+TX_FACE),memory.readWord(currentPort+TX_MODE),memory.readWord(currentPort+TX_SIZE)}; // txFace is a Style byte (its second byte is padding)
	}
	/** The current port's foreground colour as GetForeColor gives it (three 16-bit components): rgbFgColor of a colour port, the
	 *  old-style fgColor mapped of a GrafPort. TEStyleNew starts its first style with it. */
	int[] foregroundRgb48() throws M68kCpuException {
		if(currentPort==0)return new int[3];
		if(isColorPort(currentPort))return new int[]{memory.readWord(currentPort+36),memory.readWord(currentPort+38),memory.readWord(currentPort+40)};
		int rgb=quickDrawColor(memory.readLong(currentPort+FG_COLOR));return new int[]{(rgb>>>16&255)*257,(rgb>>>8&255)*257,(rgb&255)*257};
	}
	int[] penLocation() throws M68kCpuException {if(currentPort==0)return new int[]{0,0};return new int[]{(short)memory.readWord(currentPort+PN_LOC),(short)memory.readWord(currentPort+PN_LOC+2)};}
	void setPenLocation(int v,int h) throws M68kCpuException {if(currentPort!=0)writePoint(currentPort+PN_LOC,v,h);}
	/** Foreground/background colors of the current port as RGB ints. */
	int foregroundRgb() throws M68kCpuException {return currentPort==0?0:~portForegroundInk()&0xffffff;}
	/** The current colour port's foreground as RGBForeColor leaves it (rgbFgColor, fgColor, the palette index), for {@link #restoreForeground}; null for other ports. */
	Object[] saveForeground() throws M68kCpuException {
		if(currentPort==0||!isColorPort(currentPort))return null;PortState ps=ports.get(currentPort);
		return new Object[]{currentPort,memoryBytes(currentPort+36,6),memory.readLong(currentPort+FG_COLOR),ps==null?-1:ps.fgIndex,ps==null?-1:ps.fgValue};
	}
	/** RGBForeColor with 16-bit components, as TextEdit sets a style run's colour before it draws the run. */
	void setForegroundRgb16(int red,int green,int blue) throws M68kCpuException {
		if(currentPort==0||!isColorPort(currentPort))return;
		memory.writeWord(currentPort+36,red);memory.writeWord(currentPort+38,green);memory.writeWord(currentPort+40,blue);
		PortState ps=ports.get(currentPort);if(ps!=null)ps.fgIndex=-1;updatePortColorValue(true);
	}
	void restoreForeground(Object[] saved) throws M68kCpuException {
		if(saved==null)return;int port=(Integer)saved[0];memory.load(port+36,(byte[])saved[1]);memory.writeLong(port+FG_COLOR,(Integer)saved[2]);
		PortState ps=ports.get(port);if(ps!=null){ps.fgIndex=(Integer)saved[3];ps.fgValue=(Integer)saved[4];}
	}
	int backgroundRgb() throws M68kCpuException {return currentPort==0?0xffffff:~portBackgroundInk()&0xffffff;}
	/** What EraseRect paints at (h,v) in the current port: the background pattern there (bkPat/bkPixPat), not only the background colour. */
	int eraseRgb(int h,int v) throws M68kCpuException {return currentPort==0?0xffffff:~patternInk(currentPort+BK_PAT,h,v)&0xffffff;}
	/** {foreground, background} RGB of {@code port} whether or not it is the current port: TextEdit draws with the colours of its
	 *  own port (inPort), and the Dialog Manager redraws an edit field while another port may be current. */
	int[] colorsOf(int port) throws M68kCpuException {
		if(port==0||!ports.containsKey(port))return new int[]{0,0xffffff};
		int saved=currentPort;try{currentPort=port;return new int[]{foregroundRgb(),backgroundRgb()};}finally{currentPort=saved;}
	}
	int[] clipRect() throws M68kCpuException {Rect c=currentClip();return new int[]{c.top,c.left,c.bottom,c.right};}
	int[] portOrigin(){PortState state=currentPort==0?null:ports.get(currentPort);return state==null?new int[]{0,0}:new int[]{state.originV,state.originH};}
	boolean isColorPortAddress(int port) throws M68kCpuException {return port!=0&&isColorPort(port);}

	private void initializeGWorldPort(int port,int pixMapHandle,Rect bounds,int offscreenDevice) throws M68kCpuException {
		memory.fill(port,GRAF_PORT_SIZE,0);memory.writeLong(port+PORT_BITS,pixMapHandle);memory.writeWord(port+PORT_BITS+4,0xc000);
		byte[] grafVars=newGrafVars(32);putLong(grafVars,26,offscreenDevice); // the private GrafVars extension: the GWorld's GDevice
		memory.writeLong(port+GRAF_VARS,macApi.createHandle(grafVars));writeRect(port+PORT_RECT,bounds);
		memory.writeLong(port+VIS_RGN,regionHandle(bounds.top,bounds.left,bounds.bottom,bounds.right));
		// The clipRgn stays wide open as OpenCPort leaves it (the ROM's NewGWorld only replaces the PixMap and portRect; Executor
		// does the same): Altair calls SetOrigin on its board world so its local coordinates match the window and draws the
		// whole world - a clip made from boundsRect does not move with the origin and clipped the right and bottom edges away.
		memory.writeLong(port+CLIP_RGN,regionHandle(-32767,-32767,32767,32767));
		writeRgb(port+36,0,0,0);writeRgb(port+42,0xffff,0xffff,0xffff);writePoint(port+PN_LOC,0,0);writePoint(port+PN_SIZE,1,1);
		memory.writeWord(port+PN_MODE,8);memory.writeWord(port+TX_MODE,1);memory.writeWord(port+TX_SIZE,12);
		memory.writeLong(port+FG_COLOR,33);memory.writeLong(port+BK_COLOR,30);ports.put(port,new PortState());installDefaultPixPats(port);
	}

	/**
	 * OpenCPort gives a colour port PixPats of its own - pnPixPat (+58) and fillPixPat (+62) black, bkPixPat (+32) white - and
	 * programs read them: iTunes saves the background pattern as the 8 bytes of (**bkPixPat).patData around its drawing and
	 * restores them with BackPat; with NIL there it read low memory ($00...01) and erased its dialogs dotted.
	 */
	private void installDefaultPixPats(int port) throws M68kCpuException {
		byte[] black={-1,-1,-1,-1,-1,-1,-1,-1};int[] slots={PN_PAT,BK_PAT,COLOR_FILL_PIX_PAT};int[] handles=new int[3];
		for(int i=0;i<3;i++){handles[i]=newPatternPixPat(slots[i]==BK_PAT?new byte[8]:black);memory.writeLong(port+slots[i],handles[i]);}
		defaultPixPats.put(port,handles);
	}
	/** CGrafPort.fillPixPat: a picture's FillPat/FillPixPat goes there in a colour port ({@link #fillSlot}); +40, the classic fillPat,
	 *  is rgbFgColor.blue/rgbBkColor.red in a CGrafPort (UxBack's "TEXT" tiles came out cyan, the red of the background colour
	 *  being a byte of the PixPat Handle written over it). */
	private static final int COLOR_FILL_PIX_PAT=62;
	/** Where the current port keeps the fill pattern pictures draw with: fillPixPat in a colour port, fillPat in a classic one. */
	private int fillSlot() throws M68kCpuException {return currentPort!=0&&isColorPort(currentPort)?currentPort+COLOR_FILL_PIX_PAT:currentPort+FILL_PAT;}
	/** The PixPat Handle in the colour port's pattern slot at {@code pattern} (pnPixPat, bkPixPat, fillPixPat - or a Handle a program
	 *  put at the classic fillPat offset), 0 for any other pattern address. */
	private int slotHandle(int pattern) throws M68kCpuException {
		return pattern==currentPort+PN_PAT||pattern==currentPort+BK_PAT||pattern==currentPort+FILL_PAT||pattern==currentPort+COLOR_FILL_PIX_PAT?memory.readLong(pattern):0;
	}
	/** Disposes the PixPats {@link #installDefaultPixPats} made for {@code port}. */
	private void disposeDefaultPixPats(int port) throws M68kCpuException {
		int[] handles=defaultPixPats.remove(port);if(handles==null)return;
		for(int handle:handles)if(macApi.isHandle(handle)){int data=memory.readLong(handle);if(data!=0&&memory.readWord(data)==0)disposePatternData(data);macApi.disposeHandle(handle);}
	}
	/**
	 * A pattern PixPat (patType 0) as BackPat/PenPat make one for a colour port: the 8 bytes in pat1Data and, as in the ROM, in
	 * the patData Handle too (patXValid -1) - programs take the pattern from **patData.
	 */
	private int newPatternPixPat(byte[] bits) throws M68kCpuException {byte[] pattern=new byte[28];putLong(pattern,6,macApi.createHandle(bits.clone()));putWord(pattern,14,0xffff);
		System.arraycopy(bits,0,pattern,20,8);return macApi.createHandle(pattern);}
	/** Puts new pattern bytes into a type-0 PixPat record (pat1Data and patData). */
	private void setPatternPixPat(int data,byte[] bits) throws M68kCpuException {
		memory.load(data+20,bits);int patData=memory.readLong(data+6);
		if(patData!=0&&macApi.isHandle(patData)){if(macApi.handleSize(patData)>=8&&memory.readLong(patData)!=0)memory.load(memory.readLong(patData),bits);else macApi.loadHandle(patData,bits.clone());}
		else memory.writeLong(data+6,macApi.createHandle(bits.clone()));
		memory.writeWord(data+14,0xffff);
	}
	private void disposePatternData(int data) throws M68kCpuException {int patData=memory.readLong(data+6);if(patData!=0&&macApi.isHandle(patData))macApi.disposeHandle(patData);}
	private final java.util.Map<Integer,int[]> defaultPixPats=new java.util.HashMap<Integer,int[]>();

	private boolean lockPixels(M68kCpu cpu,boolean lock) throws M68kCpuException {
		int pixMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;PixelMapState state=offscreenPixMaps.get(pixMap);
		if(state!=null)state.pixelState=lock?state.pixelState|PIXELS_LOCKED:state.pixelState&~PIXELS_LOCKED;
		// A GWorld's baseAddr holds its pixels Handle while they are unlocked and the pointer once LockPixels has locked them;
		// drawing here goes by the state's Handle either way. Strata StudioPro 1.1 fills an unlocked GWorld through
		// **baseAddr (an inline "pixels Handle" accessor) - with the pointer there it read the first pixels as an address
		// and wrote its rows over low memory.
		// A world in temporary memory (useTempMem) keeps the pointer: QuickTime's ICM makes its previous-frame world with
		// keepLocal|useTempMem, locks it once to erase it and copies its PixMap for the codec afterwards without locking it again;
		// with the Handle there the 'rle ' compressor wrote frame 2 over the master pointers and its own code (Infini-D 3.0
		// rendering an animation to a QuickTime movie).
		if(state instanceof GWorldState&&!state.externalPixels&&!state.screenDescribed&&state.windowPort==0){
			boolean pointer=lock||(((GWorldState)state).flags&USE_TEMP_MEM)!=0;
			int data=memory.readLong(pixMap);if(data!=0&&memory.readLong(state.pixels)!=0)memory.writeLong(data,pointer?memory.readLong(state.pixels):state.pixels);}
		if(lock){int data=pixMap==0?0:memory.readLong(pixMap);writeBoolean(cpu.a[7],data!=0&&memory.readLong(data)!=0);}return true;
	}

	private boolean updateGWorld(M68kCpu cpu) throws M68kCpuException {
		int flags=memory.readLong(cpu.a[7]),device=memory.readLong(cpu.a[7]+4),table=memory.readLong(cpu.a[7]+8),givenDevice=device;
		int boundsPointer=memory.readLong(cpu.a[7]+12),depth=(short)memory.readWord(cpu.a[7]+16),destination=memory.readLong(cpu.a[7]+18);
		cpu.a[7]+=22;int result=0,oldPort=destination==0?0:memory.readLong(destination);GWorldState old=gWorlds.get(oldPort);
		if(device==0)device=old!=null?old.device:ensureMainGDevice();int actualDepth=normalizedGWorldDepth(depth,device);
		// pixelDepth 0 and no table: the world follows the screen's current colour table, as NewGWorld gave it (Marathon's
		// back buffer kept the intro's colours after the screen clut changed)
		if(depth==0&&table==0&&actualDepth<=8)table=deviceColorTable(device);
		if(destination==0||boundsPointer==0||actualDepth==0){result=0x80000000;}
		else {Rect bounds=gWorldBounds(readRect(boundsPointer),depth);if(!validGWorldBounds(bounds,actualDepth))result=0x80000000;
			// only the colour table differs: the world stays where it is (port, PixMap, pixels). Flashback fades by changing its
			// table and calling UpdateGWorld every frame, and keeps drawing into the GWorldPtr it had: a reallocated port left it
			// a freed record (PaintPoly read a clipRgn from garbage). The pixel values stay as they are unless clipPix,
			// stretchPix or ditherPix asks for the image to be kept (mapPix): Flashback's indices follow its palette, and mapping
			// them by colour while the table faded from black turned the whole image black and then into the wrong colours
			else if(old!=null&&old.depth==actualDepth&&sameRect(old.bounds,bounds)&&old.device==device){
				if(tableChanged(old,table)){boolean remap=(flags&(CLIP_PIX|STRETCH_PIX|DITHER_PIX))!=0;replaceGWorldColorTable(old,table,remap);if(remap)result=1<<16;}}
			else if(old==null||old.depth!=actualDepth||!sameRect(old.bounds,bounds)||old.device!=device||tableChanged(old,table)){
				boolean wasCurrent=old!=null&&currentPort==old.port;GWorldState replacement=createGWorld(actualDepth,bounds,table,device,flags,0,0,old!=null?old.offscreenDevice!=old.device:givenDevice==0&&(flags&NO_NEW_DEVICE)==0);if(old!=null)replacement.sharedDevice=old.sharedDevice;
				if(old!=null){replacement.pixelState=old.pixelState;copyOffscreenPixels(old,replacement);}memory.writeLong(destination,replacement.port);
				result=1<<20;if(old==null||old.depth!=actualDepth)result|=1<<17;if(old==null||old.rowBytes!=replacement.rowBytes)result|=1<<19;
				if(actualDepth<=8&&table!=0)result|=1<<16;if(old!=null)disposeGWorldState(old);if(wasCurrent){currentGDevice=replacement.offscreenDevice;setCurrentPort(replacement.port);}}}
		if(Mac68kApi.TRACE)System.err.println("[68K] UpdateGWorld port $"+Integer.toHexString(oldPort)+" depth "+depth+" -> "+actualDepth+(boundsPointer==0?"":" bounds "+readRect(boundsPointer).left+","+readRect(boundsPointer).top+","+readRect(boundsPointer).right+","+readRect(boundsPointer).bottom)+" table $"+Integer.toHexString(table)+" -> $"+Integer.toHexString(result)+(destination==0?"":" port $"+Integer.toHexString(memory.readLong(destination))+(gWorlds.get(memory.readLong(destination))==null?"":" pixels handle $"+Integer.toHexString(gWorlds.get(memory.readLong(destination)).pixels))));
		memory.writeLong(cpu.a[7],result);return true;
	}

	private void copyOffscreenPixels(PixelMapState source,PixelMapState destination) throws M68kCpuException {
		Rect overlap=intersection(source.bounds,destination.bounds);if(overlap.empty())return;
		Bitmap sourceBits=readBitmapRaw(memory.readLong(source.pixMap)),destinationBits=readBitmapRaw(memory.readLong(destination.pixMap));
		for(int v=overlap.top;v<overlap.bottom;v++)for(int h=overlap.left;h<overlap.right;h++)
			destinationBits.setInkRgb(h,v,sourceBits.getInkRgb(h,v));
	}

	/** UpdateGWorld with a new colour table only: the table's contents go into the world's own table Handle; {@code remap}: the
	 *  pixels keep their colours through it (else they keep their values). */
	private void replaceGWorldColorTable(GWorldState state,int table,boolean remap) throws M68kCpuException {
		Rect b=state.bounds;int width=b.right-b.left,height=b.bottom-b.top;int[] colours=remap?new int[Math.max(0,width*height)]:null;
		if(remap){Bitmap before=readBitmapRaw(memory.readLong(state.pixMap));
			for(int v=0;v<height;v++)for(int h=0;h<width;h++)colours[v*width+h]=before.getInkRgb(b.left+h,b.top+v);}
		int size=macApi.handleSize(table);
		if(state.colorTable==0||state.sharedColorTable||macApi.setHandleSize(state.colorTable,size)!=0){
			state.colorTable=copyHandle(table);state.sharedColorTable=false;memory.writeLong(memory.readLong(state.pixMap)+42,state.colorTable);}
		else memory.copy(memory.readLong(table),memory.readLong(state.colorTable),size);
		seededPalettes.remove(state.colorTable); // same Handle and maybe the same seed, other colours
		if(state.offscreenDevice!=state.device)refreshDeviceInverseTable(state.offscreenDevice);
		if(!remap)return;Bitmap after=readBitmapRaw(memory.readLong(state.pixMap));
		for(int v=0;v<height;v++)for(int h=0;h<width;h++)after.setInkRgb(b.left+h,b.top+v,colours[v*width+h]);
	}

	private boolean tableChanged(GWorldState state,int requestedTable) throws M68kCpuException {
		if(state.depth>8)return false;if(requestedTable==0)return false;
		int size=macApi.handleSize(requestedTable);if(size!=macApi.handleSize(state.colorTable))return true;
		int a=memory.readLong(requestedTable),b=memory.readLong(state.colorTable);if(a==0||b==0)return a!=b;
		for(int i=0;i<size;i++)if(memory.readByte(a+i)!=memory.readByte(b+i))return true;return false;
	}

	/** A 32-bit offscreen port (white) for running guest drawing code on the host's behalf (menu definition procedures);
	 *  the caller sets it as the current port, and disposes it with {@link #disposeScratchPort}. */
	int newScratchPort(int width,int height) throws M68kCpuException {
		Rect bounds=new Rect(0,0,Math.max(1,height),Math.max(1,width));
		GWorldState state=createGWorld(32,bounds,0,0,0);
		int base=memory.readLong(state.pixels);memory.fill(base,state.rowBytes*(bounds.bottom-bounds.top),0xff);
		return state.port;
	}
	void disposeScratchPort(int port) throws M68kCpuException {GWorldState state=gWorlds.get(port);if(state!=null)disposeGWorldState(state);}
	/** The pixels of a port made by {@link #newScratchPort}. */
	BufferedImage scratchPortImage(int port) throws M68kCpuException {
		int[] px=colorPortPixels(port);if(px==null)return null;
		BufferedImage image=new BufferedImage(px[2],px[3],BufferedImage.TYPE_INT_RGB);int[] row=new int[px[2]];byte[] raw=memory.raw();
		for(int y=0;y<px[3];y++){MacAppHost.readPixels(raw,px[0]+y*px[1],px[6],null,px[2],row);image.setRGB(0,y,px[2],1,row,0,px[2]);}
		return image;
	}

	/** The part {top, left, bottom, right} (in the port's pixels) of a port made by {@link #newScratchPort}. */
	BufferedImage scratchPortImage(int port,int top,int left,int bottom,int right) throws M68kCpuException {
		int[] px=colorPortPixels(port);if(px==null)return null;
		int x0=Math.max(0,left),x1=Math.min(px[2],right),y0=Math.max(0,top),y1=Math.min(px[3],bottom);if(x1<=x0||y1<=y0)return null;
		BufferedImage image=new BufferedImage(x1-x0,y1-y0,BufferedImage.TYPE_INT_RGB);int[] row=new int[px[2]];byte[] raw=memory.raw();
		for(int y=y0;y<y1;y++){MacAppHost.readPixels(raw,px[0]+y*px[1],px[6],null,px[2],row);image.setRGB(0,y-y0,x1-x0,1,row,x0,px[2]);}
		return image;
	}

	private boolean disposeGWorld(M68kCpu cpu) throws M68kCpuException {
		int port=memory.readLong(cpu.a[7]);cpu.a[7]+=4;GWorldState state=gWorlds.get(port);if(state!=null)disposeGWorldState(state);return true;
	}

	private void disposeGWorldState(GWorldState state) throws M68kCpuException {
		gWorlds.remove(state.port);ports.remove(state.port);disposeDefaultPixPats(state.port);
		macApi.disposeHandle(memory.readLong(state.port+VIS_RGN));macApi.disposeHandle(memory.readLong(state.port+CLIP_RGN));
		macApi.disposeHandle(memory.readLong(state.port+GRAF_VARS));disposeOffscreenPixMap(state);
		if(state.offscreenDevice!=state.device){if(currentGDevice==state.offscreenDevice)currentGDevice=0;disposeOffscreenDevice(state.offscreenDevice);}
		if(currentPort==state.port){setCurrentPort(0);currentGDevice=0;}
		if(memory.isAllocated(state.port))memory.free(state.port); // the CGrafPort record itself (DisposeGWorld disposes the port too)
	}

	/** Window buffers are owned by the host alone, so their memory is returned to the heap (a screen-sized 32-bit buffer is many MB). */
	/** Frees a replaced window buffer's pixels and colour table but not its PixMap record, which the replacement took over. */
	private void freeOffscreenPixels(PixelMapState state) throws M68kCpuException {
		forgetPlane(state);
		if(state.colorTable!=0&&!state.sharedColorTable)macApi.disposeHandleAndFree(state.colorTable);macApi.disposeHandleAndFree(state.pixels);
	}
	private void freeOffscreenPixMap(PixelMapState state) throws M68kCpuException {
		forgetPlane(state);
		offscreenPixMaps.remove(state.pixMap);offscreenPixMapData.remove(memory.readLong(state.pixMap));
		if(state.colorTable!=0&&!state.sharedColorTable)macApi.disposeHandleAndFree(state.colorTable);macApi.disposeHandleAndFree(state.pixMap);macApi.disposeHandleAndFree(state.pixels);
	}

	/** The smooth zoom forgets the plane of pixels about to be freed (new pixels in the same memory start afresh). */
	private void forgetPlane(PixelMapState state) throws M68kCpuException {int base=state.pixels==0?0:memory.readLong(state.pixels);if(base!=0)smooth.forget(memory.physical(base));}
	private void disposeOffscreenPixMap(PixelMapState state) throws M68kCpuException {
		forgetPlane(state);
		offscreenPixMaps.remove(state.pixMap);int data=memory.readLong(state.pixMap);offscreenPixMapData.remove(data);
		// only the table this PixMap was made with, and only while it is still the PixMap's table: some programs dispose the
		// default table of a NewGDevice PixMap itself and puts a copy of its own in (Principia Off-Screen Graphics recipe) -
		// disposing the original again handed its master pointer out twice
		int table=data==0?0:memory.readLong(data+42);
		if(state.externalPixels)memory.writeLong(state.pixels,0); // the caller's buffer (NewGWorldFromPtr) is not freed with the Handle
		if(state.colorTable!=0&&!state.sharedColorTable&&table==state.colorTable)macApi.disposeHandle(state.colorTable);macApi.disposeHandle(state.pixMap);macApi.disposeHandle(state.pixels);
	}

	private boolean newScreenBuffer(M68kCpu cpu,boolean temporary) throws M68kCpuException {
		int pixMapDestination=memory.readLong(cpu.a[7]),deviceDestination=memory.readLong(cpu.a[7]+4);
		boolean purgeable=memory.readByte(cpu.a[7]+8)!=0;int boundsPointer=memory.readLong(cpu.a[7]+10);cpu.a[7]+=14;int error=PARAM_ERR;
		if(pixMapDestination!=0&&boundsPointer!=0){if(currentGDevice==0)currentGDevice=ensureMainGDevice();Rect bounds=readRect(boundsPointer);
			int depth=normalizedGWorldDepth(0,currentGDevice);
			if(validGWorldBounds(bounds,depth)){PixelMapState state=createOffscreenPixMap(depth,bounds,0,purgeable?1:0);
				memory.writeLong(pixMapDestination,state.pixMap);if(deviceDestination!=0)memory.writeLong(deviceDestination,currentGDevice);error=NO_ERR;}}
		if(error!=NO_ERR){if(pixMapDestination!=0)memory.writeLong(pixMapDestination,0);if(deviceDestination!=0)memory.writeLong(deviceDestination,0);}
		memory.writeWord(cpu.a[7],error);return true;
	}

	private boolean disposeScreenBuffer(M68kCpu cpu) throws M68kCpuException {
		int pixMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;PixelMapState state=offscreenPixMaps.get(pixMap);
		if(state!=null&&!(state instanceof GWorldState)&&pixMap!=mainDevicePixMap)disposeOffscreenPixMap(state);return true;
	}

	private boolean getGWorld(M68kCpu cpu) throws M68kCpuException {
		int deviceDestination=memory.readLong(cpu.a[7]),portDestination=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(currentGDevice==0)currentGDevice=ensureMainGDevice();
		if(deviceDestination!=0)memory.writeLong(deviceDestination,currentGDevice);if(portDestination!=0)memory.writeLong(portDestination,currentPort);return true;
	}

	private boolean setGWorld(M68kCpu cpu) throws M68kCpuException {
		int device=memory.readLong(cpu.a[7]),port=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;portSets++;
		if(port!=0&&!ports.containsKey(port))ports.put(port,stateFromMemory(port));GWorldState world=gWorlds.get(port);
		currentGDevice=device!=0?device:world!=null?world.offscreenDevice:ensureMainGDevice();setCurrentPort(port);return true;
	}

	private boolean colorTableChanged(M68kCpu cpu) throws M68kCpuException {int table=memory.readLong(cpu.a[7]);cpu.a[7]+=4;changeColorTableSeed(table);return true;}
	private boolean setPixelsPurgeable(M68kCpu cpu,boolean purgeable) throws M68kCpuException {int pixMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		PixelMapState state=offscreenPixMaps.get(pixMap);if(state!=null)state.pixelState=purgeable?state.pixelState|PIXELS_PURGEABLE:state.pixelState&~PIXELS_PURGEABLE;return true;}
	private boolean getPixelsState(M68kCpu cpu) throws M68kCpuException {int pixMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		PixelMapState state=offscreenPixMaps.get(pixMap);memory.writeLong(cpu.a[7],state==null?0:state.pixelState);return true;}
	private boolean setPixelsState(M68kCpu cpu) throws M68kCpuException {int value=memory.readLong(cpu.a[7]),pixMap=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		PixelMapState state=offscreenPixMaps.get(pixMap);if(state!=null)state.pixelState=value&(PIXELS_PURGEABLE|PIXELS_LOCKED);return true;}
	private boolean getPixBaseAddress(M68kCpu cpu) throws M68kCpuException {int pixMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		int data=pixMap==0?0:memory.readLong(pixMap);PixelMapState state=offscreenPixMaps.get(pixMap);
		memory.writeLong(cpu.a[7],state!=null&&!state.screenDescribed?memory.readLong(state.pixels):data==0?0:unlockedGWorldPixels(memory.readLong(data)));return true;}
	/**
	 * A baseAddr that is an unlocked GWorld's pixels Handle (see {@link #lockPixels}) found in some other PixMap - a copy made
	 * with CopyPixMap - stands for the pixels themselves, as in the GWorld's own record: QuickTime's ICM copies the
	 * destination GWorld's PixMap and asks GetPixBaseAddr for the copy, and its codec wrote a whole frame over the master
	 * pointer and the code behind it (Infini-D 3.0 rendering an animation to a QuickTime movie). Anything else is returned as is.
	 */
	private int unlockedGWorldPixels(int base) throws M68kCpuException {
		if(base==0||gWorlds.isEmpty()||!macApi.isHandle(base))return base;
		for(GWorldState g:gWorlds.values())if(g.pixels==base&&!g.externalPixels){int p=memory.readLong(base);return p!=0?p:base;}
		return base;
	}
	private boolean getGWorldDevice(M68kCpu cpu) throws M68kCpuException {int port=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		// a window or other plain port answers the current device (IM Imaging 6-38); NIL made QuickTime's text media handler take
		// its offscreen for a 1-bit one (DesktopMovie: dark red karaoke text on black became an all-black box)
		GWorldState state=gWorlds.get(port);memory.writeLong(cpu.a[7],state!=null?state.offscreenDevice:memory.readLong(0xcc8));return true;}
	private boolean quickDrawDone(M68kCpu cpu) throws M68kCpuException {cpu.a[7]+=4;writeBoolean(cpu.a[7],true);return true;}
	private boolean pixMap32Bit(M68kCpu cpu) throws M68kCpuException {int pixMap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int data=pixMap==0?0:memory.readLong(pixMap);
		writeBoolean(cpu.a[7],data!=0&&(memory.readWord(data+4)&0x8000)!=0);return true;}
	private boolean getGWorldPixMap(M68kCpu cpu) throws M68kCpuException {int port=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		memory.writeLong(cpu.a[7],port!=0&&isColorPort(port)?memory.readLong(port+PORT_BITS):0);return true;}

	/** The virtual screen: the host screen for hosted programs (no wider than the main device's rowBytes allows at the preferred depth, see {@link #MAX_SCREEN_ROW_BYTES}), the classic 512x342 otherwise. */
	Rect screenRect(){
		Toolbox68k tb=macApi.application();
		if(tb!=null&&tb.hosted()){java.awt.Rectangle r=tb.isApplication()?MacAppHost.screenBounds(false):tb.externalScreenBounds();boolean ppc=powerPcScreen(); // PowerPC: only the PixMap format's own rowBytes limit
			int width=Math.min(ppc?r.width:Math.min(4096,r.width),maxScreenWidth(preferredScreenDepth(),ppc))&~1;
			if(width<r.width&&width!=reportedScreenWidth){reportedScreenWidth=width;System.err.println("[68K] screen width "+r.width+" limited to "+width+" pixels: "+(ppc?"a PixMap row must stay within "+PPC_MAX_SCREEN_ROW_BYTES+" bytes":"a PixMap row must stay within "+MAX_SCREEN_ROW_BYTES+" bytes and width*depth within a word")+" at "+preferredScreenDepth()+" bits");}
			return new Rect(0,0,Math.min(4096,r.height),width);}
		return new Rect(0,0,342,512);
	}
	private int reportedScreenWidth;
	/** The virtual screen as {top, left, bottom, right}, for the managers that describe it to programs (WMgrPort, GrayRgn, dialog placement). */
	int[] screenBounds(){Rect r=screenRect();return new int[]{r.top,r.left,r.bottom,r.right};}

	/** InitGraf: fills the QuickDraw globals (thePort, white..dkGray, arrow, screenBits, randSeed) below the pointer. */
	private boolean initializeGlobals(M68kCpu cpu) throws M68kCpuException {
		int pointer=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		memory.writeByte(0x8f3,0); // QDExist: QuickDraw is initialized
		return initializeGlobals(cpu,pointer);
	}
	/** The globals at {@code pointer} (thePort's address), as InitGraf lays them out; also used for an external's A5 world. */
	boolean initializeGlobals(M68kCpu cpu,int pointer) throws M68kCpuException {
		qdGlobals=pointer;
		if(qdGlobals==0)return true;
		if(cpu.a[5]!=0)memory.writeLong(cpu.a[5],qdGlobals); // the ROM keeps the globals pointer at 0(A5); programs read it to reach gray & co. ((A5)-126+offset)
		memory.writeLong(qdGlobals,currentPort);
		// thePort occupies the 4 bytes at the pointer; the other globals lie below it:
		// white -8, black -16, gray -24, ltGray -32, dkGray -40, arrow -108, screenBits -122, randSeed -126.
		memory.fill(qdGlobals-8,8,0x00);memory.fill(qdGlobals-16,8,0xff); // white = all 0 bits, black = all 1 bits (HyperCard erases its card buffer with qd.white)
		byte[] gray={(byte)0xaa,0x55,(byte)0xaa,0x55,(byte)0xaa,0x55,(byte)0xaa,0x55};memory.load(qdGlobals-24,gray);
		byte[] ltGray={(byte)0x88,0x22,(byte)0x88,0x22,(byte)0x88,0x22,(byte)0x88,0x22};memory.load(qdGlobals-32,ltGray);
		byte[] dkGray={(byte)0x77,(byte)0xdd,(byte)0x77,(byte)0xdd,(byte)0x77,(byte)0xdd,(byte)0x77,(byte)0xdd};memory.load(qdGlobals-40,dkGray);
		int[] arrowData={0x0000,0x4000,0x6000,0x7000,0x7800,0x7c00,0x7e00,0x7f00,0x7f80,0x7c00,0x6c00,0x4600,0x0600,0x0300,0x0300,0x0000};
		int[] arrowMask={0xc000,0xe000,0xf000,0xf800,0xfc00,0xfe00,0xff00,0xff80,0xffc0,0xffe0,0xfe00,0xef00,0xcf00,0x8780,0x0780,0x0380};
		int arrow=qdGlobals-108;
		for(int i=0;i<16;i++){memory.writeWord(arrow+i*2,arrowData[i]);memory.writeWord(arrow+32+i*2,arrowMask[i]);}
		memory.writeWord(arrow+64,1);memory.writeWord(arrow+66,1); // hotspot
		Rect screen=screenRect();
		memory.writeLong(qdGlobals-122,0);memory.writeWord(qdGlobals-118,(screen.right+7)/8&~1);writeRect(qdGlobals-116,screen.top,screen.left,screen.bottom,screen.right); // screenBits
		memory.writeLong(qdGlobals-126,memory.readLong(0x156)); // randSeed
		// the first private globals: wideOpen (-130), a Handle to the wide-open region - its master pointer wideMaster (-134)
		// and the region wideData (-144) lie in the globals too. Key Caps restores its clip with SetClip(wideOpen).
		memory.writeLong(qdGlobals-130,qdGlobals-134);memory.writeLong(qdGlobals-134,qdGlobals-144);
		memory.writeWord(qdGlobals-144,10);writeRect(qdGlobals-142,-32767,-32767,32767,32767);
		Toolbox68k tb=macApi.application();if(tb!=null&&tb.hosted()&&tb.isApplication()){ensureMainGDevice();writeScreenBits();}
		return true;
	}
	/**
	 * screenBits of a hosted program as Color QuickDraw's InitGraf leaves it: baseAddr = the main screen's pixels (ScrnBase),
	 * rowBytes = its rowBytes (ScreenRow). Games draw their sprites straight into screen memory through it - Clash of the Dark
	 * composites its fighters at screenBits.baseAddr + y*rowBytes + x every frame; with baseAddr 0 they went into low memory
	 * and the arena stayed empty. Followed on SetDepth ({@link #writeScreenRow}).
	 */
	private void writeScreenBits() throws M68kCpuException {
		int globals=liveGlobals();if(globals==0||mainDevicePixMap==0)return;Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted()||!tb.isApplication())return;
		int base=memory.readLong(0x824),rowBytes=memory.readWord(0x106)&0x3fff;if(base==0||rowBytes==0)return;
		memory.writeLong(globals-122,base);memory.writeWord(globals-118,rowBytes);
	}

	/**
	 * Forgets the globals of an extension's InitGraf. INITs run before the program, each with a temporary A5 world, often on
	 * the stack (Speech Manager's): SetPort kept writing thePort there for a program that never calls InitGraf itself, over
	 * its stack frames (Prometheus lost its frame pointer and jumped to 0).
	 */
	void forgetExtensionGlobals(){qdGlobals=0;}

	/** Where Random keeps its seed: the QuickDraw global randSeed once InitGraf has run (programs assign it directly -
	 *  WonderCrackers descrambles its sprite sheets with a fixed seed), before that the low-memory RndSeed. */
	int randSeedAddress(){int globals=liveGlobals();return globals!=0?globals-126:0x156;}

	private boolean initializePort(M68kCpu cpu) throws M68kCpuException {
		int port=memory.readLong(cpu.a[7]);cpu.a[7]+=4;initialize(port);setCurrentPort(port);rememberScreenPort(port);return true;
	}

	/** InitCPort/OpenCPort. A NIL port is ignored and thePort stays as it was: 六角大王 5.5 passes a still-empty GWorld field
	 *  (InitCPort(NIL), then NewGWorld into that field) and relies on GetGWorld still answering its window afterwards - taking
	 *  NIL as thePort made its SetGWorld/GetPort/CopyBits land on a screen port in global coordinates (the model view shifted
	 *  by the window position and cut up). */
	private boolean initializeColorPort(M68kCpu cpu) throws M68kCpuException {
		int port=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(port==0)return true;initializeColor(port);setCurrentPort(port);rememberScreenPort(port);return true;
	}

	/**
	 * OpenPort/OpenCPort give a program a port whose bits are the screen. Hosted applications have no screen
	 * bitmap: what they draw there in global coordinates lands in whichever host window covers that pixel
	 * (some games paint the whole frame into such a port over a full-screen window).
	 */
	private final java.util.Set<Integer> screenBitmaps=new java.util.HashSet<Integer>();
	/** Makes a port draw on the screen: its PixMap becomes the main device's, so drawing lands in the windows under it
	 *  (the Window Manager port, where custom WDEFs draw their frames). */
	void makeScreenPort(int port) throws M68kCpuException {
		ensureMainGDevice();if(mainDevicePixMap==0)return;
		if(isColorPort(port))memory.writeLong(port+PORT_BITS,mainDevicePixMap);rememberScreenPort(port); // a classic port keeps its screenBits-style BitMap (see initialize)
		Rect screen=screenRect();writeRect(port+PORT_RECT,screen.top,screen.left,screen.bottom,screen.right); // the whole screen is visible and drawable
		if(!isColorPort(port))writeRect(port+PORT_BITS+6,screen); // (a classic port's BitMap bounds are the screen's too)
		int visible=memory.readLong(port+VIS_RGN);if(visible!=0)writeRegionRect(visible,screen);
	}
	/** A classic GrafPort on the screen, as OpenPort makes one: 8-byte pnPat (black) and bkPat (white) at the GrafPort offsets,
	 *  which is what a WDEF written for the black-and-white Window Manager port (WMgrPort, $9DE) reads from it. */
	void initializeClassicScreenPort(int port) throws M68kCpuException {initialize(port);rememberScreenPort(port);}
	private void rememberScreenPort(int port) throws M68kCpuException {
		Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted())return;
		screenBitmaps.add(bitmapPointer(port));
	}

	/**
	 * Where a screen port's local coordinates lie on the screen, {h, v} to add (LocalToGlobal: global = local - bounds.topLeft,
	 * IM I-193; the bounds include the origin, which SetOrigin moves them by), for the ports MovePortTo places by their bounds:
	 * OpenPort ports and OpenCPort ports with their own PixMap copy. Null for other ports (windows, GWorlds), which the Window
	 * Manager places, and for a classic port whose bounds are the screen's.
	 */
	int[] screenPortOffset(int port) throws M68kCpuException {
		if(port==0)return null;
		if(!isColorPort(port)){Rect bounds=readRect(port+PORT_BITS+6);return bounds.top==0&&bounds.left==0?null:new int[]{-bounds.left,-bounds.top};}
		int mapHandle=memory.readLong(port+PORT_BITS);if(mapHandle==0||!portPixMapCopies.containsKey(mapHandle))return null;
		int data=memory.readLong(mapHandle),device=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(data==0)return null;
		Rect bounds=readRect(data+6);int top=bounds.top,left=bounds.left;
		if(device!=0){Rect screen=readRect(device+6);top-=screen.top;left-=screen.left;}
		return new int[]{-left,-top};
	}

	/** Counts SetPort/SetGWorld calls: the Window Manager tells whether a program chose a port itself during an update. */
	int portSets;
	private boolean setPort(M68kCpu cpu) throws M68kCpuException {
		int port=memory.readLong(cpu.a[7]);cpu.a[7]+=4;portSets++;
		if(port!=0&&!ports.containsKey(port))ports.put(port,stateFromMemory(port));
		setCurrentPort(port);return true;
	}

	private boolean getPort(M68kCpu cpu) throws M68kCpuException {
		if(currentPort==0){int port=memory.allocate(GRAF_PORT_SIZE,2);initialize(port);setCurrentPort(port);}
		int destination=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(destination!=0)memory.writeLong(destination,currentPort);return true;
	}

	private void setCurrentPort(int port) throws M68kCpuException {
		currentPort=port;int globals=liveGlobals();if(globals!=0)memory.writeLong(globals,port);
	}

	int currentPortAddress() {return currentPort;}
	void forgetExternalPort(int port) throws M68kCpuException {
		ports.remove(port);if(currentPort==port)setCurrentPort(0);
	}

	private void initialize(int port) throws M68kCpuException {
		if(port==0)return;memory.fill(port,GRAF_PORT_SIZE,0);Rect screen=screenRect();
		memory.writeWord(port+PORT_BITS+4,(screen.right+15)/16*2);writeRect(port+PORT_BITS+6,screen);
		writeRect(port+PORT_RECT,screen); // OpenPort: portRect is the screen (programs use it as a window bounds)
		memory.writeLong(port+VIS_RGN,regionHandle(screen.top,screen.left,screen.bottom,screen.right));
		memory.writeLong(port+CLIP_RGN,regionHandle(-32767,-32767,32767,32767));
		memory.fill(port+BK_PAT,8,0);memory.fill(port+FILL_PAT,8,0xff);
		writePoint(port+PN_LOC,0,0);writePoint(port+PN_SIZE,1,1);
		memory.writeWord(port+PN_MODE,8);memory.fill(port+PN_PAT,8,0xff);
		memory.writeWord(port+TX_MODE,1);memory.writeWord(port+TX_SIZE,12);memory.writeLong(port+FG_COLOR,33);
		memory.writeLong(port+BK_COLOR,30);ports.put(port,new PortState());
	}

	private void initializeColor(int port) throws M68kCpuException {
		if(port==0)return;memory.fill(port,GRAF_PORT_SIZE,0);Rect screen=screenRect();
		// OpenCPort: the port gets its OWN copy of the current device's PixMap (NewPixMap + CopyPixMap in the ROM). Programs take
		// its baseAddr/rowBytes to write pixels directly, and some rewrite baseAddr/rowBytes/bounds of that copy to turn
		// the port into an offscreen buffer - sharing the device's Handle here made every screen port read their buffer.
		ensureMainGDevice();int device=currentGDevice!=0?currentGDevice:mainGDevice;int deviceData=device==0?0:memory.readLong(device);
		int devicePixMap=deviceData==0?0:memory.readLong(deviceData+22);if(devicePixMap==0)devicePixMap=mainDevicePixMap;
		int pixMapHandle=devicePixMap==0?0:copyPixMapHandle(devicePixMap);
		if(pixMapHandle!=0)portPixMapCopies.put(pixMapHandle,memory.readLong(memory.readLong(pixMapHandle)+42));
		memory.writeLong(port+PORT_BITS,pixMapHandle);memory.writeWord(port+PORT_BITS+4,0xc000);
		memory.writeLong(port+GRAF_VARS,macApi.createHandle(newGrafVars(26)));
		writeRect(port+PORT_RECT,screen);memory.writeLong(port+VIS_RGN,regionHandle(screen.top,screen.left,screen.bottom,screen.right));
		memory.writeLong(port+CLIP_RGN,regionHandle(-32767,-32767,32767,32767));writeRgb(port+36,0,0,0);writeRgb(port+42,0xffff,0xffff,0xffff);
		writePoint(port+PN_LOC,0,0);writePoint(port+PN_SIZE,1,1);memory.writeWord(port+PN_MODE,8);memory.writeWord(port+TX_MODE,1);
		memory.writeWord(port+TX_SIZE,12);memory.writeLong(port+FG_COLOR,33);memory.writeLong(port+BK_COLOR,30);ports.put(port,new PortState());installDefaultPixPats(port);
	}

	/** The PixMap Handles OpenCPort made for ports (disposed with the port), each with the colour table Handle made for it. */
	private final java.util.Map<Integer,Integer> portPixMapCopies=new java.util.HashMap<Integer,Integer>();
	/** A new PixMap Handle with the record and colour table of {@code source} copied (CopyPixMap semantics). */
	private int copyPixMapHandle(int source) throws M68kCpuException {
		int data=memory.readLong(source);if(data==0)return 0;byte[] copy=new byte[50];for(int i=0;i<copy.length;i++)copy[i]=(byte)memory.readByte(data+i);
		int table=memory.readLong(data+42),tableSize=table==0?0:macApi.handleSize(table);
		if(table!=0&&tableSize>0){int tableData=memory.readLong(table);byte[] colors=new byte[tableSize];for(int i=0;i<tableSize;i++)colors[i]=(byte)memory.readByte(tableData+i);putLong(copy,42,macApi.createHandle(colors));}
		return macApi.createHandle(copy);
	}
	/** After SetDepth the screen ports' PixMap copies follow the screen (record copied again; their own colour table stays). */
	private void retargetScreenPortCopies(int formerBase) throws M68kCpuException {
		int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(map==0||formerBase==0)return;
		for(int copy:portPixMapCopies.keySet()){int data=memory.readLong(copy);if(data==0||memory.readLong(data)!=formerBase)continue;
			int table=memory.readLong(data+42);memory.copy(map,data,50);memory.writeLong(data+42,table);}
	}
	/** Ports that draw through the device's own PixMap Handle (see {@link #makeScreenPort}: the Window Manager ports) follow a
	 *  SetDepth to the replacement Handle before the old one is disposed. */
	private void retargetScreenPorts(int former,int current) throws M68kCpuException {
		if(former==0||current==0||former==current)return;int formerData=memory.readLong(former),currentData=memory.readLong(current);
		for(int port:new ArrayList<Integer>(ports.keySet()))if(port!=0&&isColorPort(port)&&memory.readLong(port+PORT_BITS)==former)memory.writeLong(port+PORT_BITS,current);
		if(formerData!=0&&screenBitmaps.remove(formerData)&&currentData!=0)screenBitmaps.add(currentData);
	}
	private void disposePortPixMapCopy(int port) throws M68kCpuException {
		int handle=memory.readLong(port+PORT_BITS);Integer ownTable=portPixMapCopies.remove(handle);if(ownTable==null)return;
		// Only the colour table made for the port goes with it (CloseCPort disposes the PixMap alone: its table is the device's).
		// Coloring Book 3.2 installs a CTab of its own in an offscreen port's pmTable, closes the port and later passes that
		// CTab to CTab2Palette: disposing it let a NewHandle reuse it and text became the screen's colours.
		int data=memory.readLong(handle);if(data!=0)screenBitmaps.remove(data);
		if(ownTable!=0&&macApi.handleSize(ownTable)>0)macApi.disposeHandle(ownTable);
		macApi.disposeHandle(handle);
	}
	/** True when a screen port's PixMap still describes the screen: a program that pointed its baseAddr at its own buffer
	 *  an offscreen OpenCPort rewritten that way draws there, not on the screen. */
	/**
	 * A program's own copy of qd.screenBits (a BitMap record with the screen's baseAddr and bounds): on a colour screen
	 * CopyBits into it draws on the screen at the screen's depth, not as 1-bit pixels. The same for a copy of an OpenPort
	 * port's portBits, whose baseAddr is 0 here (the port itself is routed to the screen, see {@link #rememberScreenPort}):
	 * as a plain BitMap it wrote 1-bit pixels over low memory. Sail Away copies its screen port's portBits into a local
	 * and CopyBits its gauges' 1-bit buffers there, clipped to the gauge windows (they stayed white).
	 */
	private boolean copiesScreenBits(int bitmap) throws M68kCpuException {
		if(mainDevicePixMap==0||(memory.readWord(bitmap+4)&0x8000)!=0)return false;
		int device=memory.readLong(mainDevicePixMap);if(device==0)return false;
		int base=memory.readLong(bitmap);if(base!=0&&base!=memory.readLong(device))return false;
		Toolbox68k tb=macApi.application();if(base==0&&(tb==null||!tb.hosted()))return false;
		// the size of the screen (a MovePortTo'd port's copy has its bounds moved; see movePortTo)
		Rect bounds=readRect(bitmap+6),screen=readRect(device+6);
		return bounds.bottom-bounds.top==screen.bottom-screen.top&&bounds.right-bounds.left==screen.right-screen.left;
	}
	private boolean describesScreen(int pixMap) throws M68kCpuException {
		int base=memory.readLong(pixMap);if(base==0||mainDevicePixMap==0)return true;
		int device=memory.readLong(mainDevicePixMap);return device==0||base==memory.readLong(device);
	}
	/**
	 * NewPixMap: "all fields of the PixMap are copied from the current device's PixMap except the color table" (IM V-70) -
	 * pixelSize included. MacBrickout 3.1 only sets baseAddr, rowBytes and bounds on its 640x460 offscreen maps, so a 1-bit
	 * record made its 8-bit buffers 1-bit (white bricks on the pale yellow of index 1). The table is a copy of the device's.
	 */
	private boolean newPixMap(M68kCpu cpu) throws M68kCpuException {
		int handle=newPixMapHandle();
		int device=memory.readLong(0xcc8),deviceData=device==0?0:memory.readLong(device),devicePixMap=deviceData==0?0:memory.readLong(deviceData+22);
		int source=devicePixMap==0?0:memory.readLong(devicePixMap);
		if(source!=0){int data=memory.readLong(handle),table=memory.readLong(data+42);
			for(int i=0;i<42;i++)memory.writeByte(data+i,memory.readByte(source+i));
			int sourceTable=memory.readLong(source+42),size=sourceTable==0?0:macApi.handleSize(sourceTable);
			if(size>=8&&memory.readLong(sourceTable)!=0){byte[] copy=new byte[size];int from=memory.readLong(sourceTable);for(int i=0;i<size;i++)copy[i]=(byte)memory.readByte(from+i);macApi.loadHandle(table,copy);}}
		memory.writeLong(cpu.a[7],handle);return true;}
	/** A 1-bit black-and-white PixMap with its own two-entry colour table (what NewPixMap hands out). */
	private int newPixMapHandle() throws M68kCpuException {byte[] table=new byte[24];putWord(table,6,1);putWord(table,8,0);
		putWord(table,10,0xffff);putWord(table,12,0xffff);putWord(table,14,0xffff);putWord(table,16,1);int tableHandle=macApi.createHandle(table);
		byte[] pixMap=new byte[50];putWord(pixMap,4,0x8000);putLong(pixMap,22,0x00480000);putLong(pixMap,26,0x00480000);
		putWord(pixMap,32,1);putWord(pixMap,34,1);putWord(pixMap,36,1);putLong(pixMap,42,tableHandle);
		return macApi.createHandle(pixMap);}
	private boolean disposePixMap(M68kCpu cpu) throws M68kCpuException {int handle=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(handle!=0){int data=memory.readLong(handle);
		if(data!=0){int table=memory.readLong(data+42);if(table!=0&&macApi.handleSize(table)>0)macApi.disposeHandle(table);}macApi.disposeHandle(handle);}return true;}
	private boolean copyPixMap(M68kCpu cpu) throws M68kCpuException {int destination=memory.readLong(cpu.a[7]),source=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(source==0||destination==0)return true;int sourceData=memory.readLong(source);if(sourceData==0)return true;byte[] copy=new byte[50];
		for(int i=0;i<copy.length;i++)copy[i]=(byte)memory.readByte(sourceData+i);int sourceTable=memory.readLong(sourceData+42),tableSize=macApi.handleSize(sourceTable);
		if(sourceTable!=0&&tableSize>0){int tableData=memory.readLong(sourceTable);byte[] table=new byte[tableSize];for(int i=0;i<tableSize;i++)table[i]=(byte)memory.readByte(tableData+i);
			putLong(copy,42,macApi.createHandle(table));}
		// CopyPixMap updates the existing record. QuickTime locks this Handle and caches
		// *destination before copying; replacing its block leaves that PixMapPtr dangling.
		int destinationData=memory.readLong(destination);
		if(destinationData!=0&&macApi.handleSize(destination)>=copy.length)memory.load(destinationData,copy);
		else macApi.loadHandle(destination,copy);
		return true;}
	/** SetPortPix replaces portPixMap of a CGrafPort; on an old-style GrafPort it does nothing, as in Color QuickDraw (SetPortBits
	 *  is that port's call). SuperPaint 3.0 keeps a 1-bit document's offscreen port an old port drawing into a BitMap and calls
	 *  SetPortPix on it unconditionally in one place: turning the port into a colour port pointed it at an 8-bit PixMap with
	 *  the BitMap's 64-byte rows, and the black-and-white sample opened as horizontal smears. */
	private boolean setPortPix(M68kCpu cpu) throws M68kCpuException {int handle=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&handle!=0&&isColorPort(currentPort))memory.writeLong(currentPort+PORT_BITS,handle);return true;}
	/** NewPixPat: patType 1 with a real patMap (NewPixMap) and empty patData/patXData Handles, patXValid -1 - programs fill the
	 *  parts in themselves (an editor may BlockMove a PixMap into patMap and resize patData). */
	private boolean newPixPat(M68kCpu cpu) throws M68kCpuException {byte[] pattern=new byte[28];putWord(pattern,0,1);putLong(pattern,2,newPixMapHandle());
		putLong(pattern,6,macApi.createHandle(new byte[0]));putLong(pattern,10,macApi.createHandle(new byte[0]));putWord(pattern,14,0xffff);for(int i=20;i<28;i++)pattern[i]=(byte)0xaa;
		memory.writeLong(cpu.a[7],macApi.createHandle(pattern));return true;}
	private boolean disposePixPat(M68kCpu cpu) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(pattern!=0){int data=memory.readLong(pattern);if(data!=0){int type=(short)memory.readWord(data);
			if(type==1||type==3)disposePixPatParts(data);else if(type==0)disposePatternData(data);}macApi.disposeHandle(pattern);}return true;}
	private void disposePixPatParts(int data) throws M68kCpuException {int mapHandle=memory.readLong(data+2),pixelHandle=memory.readLong(data+6);
		int expandedData=memory.readLong(data+10),expandedMap=memory.readLong(data+16);
		if(mapHandle!=0&&macApi.handleSize(mapHandle)>=50){int map=memory.readLong(mapHandle),table=map==0?0:memory.readLong(map+42);
			if(table!=0&&macApi.handleSize(table)>0)macApi.disposeHandle(table);macApi.disposeHandle(mapHandle);}
		if(pixelHandle!=0&&pixelHandle!=mapHandle&&macApi.handleSize(pixelHandle)>0)macApi.disposeHandle(pixelHandle);
		if(expandedData!=0&&expandedData!=pixelHandle&&expandedData!=mapHandle&&macApi.handleSize(expandedData)>0)macApi.disposeHandle(expandedData);
		if(expandedMap!=0&&expandedMap!=mapHandle&&expandedMap!=pixelHandle&&expandedMap!=expandedData&&macApi.handleSize(expandedMap)>0)macApi.disposeHandle(expandedMap);}
	/** CopyPixPat: the contents of the source pattern go into the DESTINATION's own patMap/patData Handles (deep copy, as the
	 *  ROM does). Sharing the source's Handles instead left the copy dangling once the source was disposed or emptied:
	 *  QuickTime's movie controller copies its four animated patterns and then writes 16 phases through the copy's patData. */
	private boolean copyPixPat(M68kCpu cpu) throws M68kCpuException {int destination=memory.readLong(cpu.a[7]),source=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(source==0||destination==0||source==destination)return true;int data=memory.readLong(source);if(data==0)return true;
		int size=macApi.handleSize(source);if(size<28)size=28;byte[] copy=memory.readBytes(data,size);
		int target=memory.readLong(destination);if(target==0||macApi.handleSize(destination)<28){macApi.loadHandle(destination,new byte[Math.max(28,size)]);target=memory.readLong(destination);}
		int mapHandle=memory.readLong(target+2),pixelHandle=memory.readLong(target+6),expandedData=memory.readLong(target+10);
		if(mapHandle==0||macApi.handleSize(mapHandle)<50)mapHandle=newPixMapHandle();
		if(pixelHandle==0||pixelHandle==mapHandle)pixelHandle=macApi.createHandle(new byte[0]);
		int sourceMap=memory.readLong(data+2),sourceData=memory.readLong(data+6);
		if(sourceMap!=0&&sourceMap!=mapHandle&&memory.readLong(sourceMap)!=0&&macApi.handleSize(sourceMap)>=50){ // the PixMap and its own copy of the colour table
			byte[] map=memory.readBytes(memory.readLong(sourceMap),50);int table=memory.readLong(memory.readLong(mapHandle)+42),sourceTable=memory.readLong(memory.readLong(sourceMap)+42);
			byte[] colours=sourceTable!=0?macApi.handleContents(sourceTable):null;
			if(colours!=null){if(table==0||table==sourceTable)table=macApi.createHandle(colours);else macApi.loadHandle(table,colours);}
			putLong(map,42,table);macApi.loadHandle(mapHandle,map);}
		if(sourceData!=0&&sourceData!=pixelHandle){byte[] pixels=macApi.handleContents(sourceData);macApi.loadHandle(pixelHandle,pixels==null?new byte[0]:pixels);}
		putLong(copy,2,mapHandle);putLong(copy,6,pixelHandle);putLong(copy,10,expandedData);putWord(copy,14,0xffff);putLong(copy,16,0); // expanded data is stale
		memory.load(target,java.util.Arrays.copyOf(copy,Math.min(copy.length,macApi.handleSize(destination))));return true;}
	private boolean setPortPixPat(M68kCpu cpu,int offset) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&isColorPort(currentPort)){memory.writeLong(currentPort+offset,pattern);recordPicturePixPat(offset==PN_PAT?0x13:0x12,pattern);}return true;}
	private boolean getPixPat(M68kCpu cpu) throws M68kCpuException {int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		memory.writeLong(cpu.a[7],pixPatResource(id));return true;}
	/** GetPixPat: a new PixPat from 'ppat' id (0 when there is none or it is malformed). */
	int pixPatResource(int id) throws M68kCpuException {
		int handle=macApi.detachedResource(PPAT_TYPE,id);if(handle!=0&&!expandPixPatResource(handle)){macApi.disposeHandle(handle);handle=0;}
		return handle;}
	private boolean expandPixPatResource(int handle) throws M68kCpuException {int size=macApi.handleSize(handle),resource=memory.readLong(handle);
		if(resource==0||size<28)return false;int type=(short)memory.readWord(resource);if(type!=1&&type!=3)return type==0||type==2;
		int mapOffset=memory.readLong(resource+2),pixelOffset=memory.readLong(resource+6);
		if(!resourceRange(size,mapOffset,50))return false;int mapAddress=resource+mapOffset,rowBytes=memory.readWord(mapAddress+4)&0x3fff;
		Rect bounds=readRect(mapAddress+6);long pixelLength=(long)rowBytes*Math.max(0,bounds.bottom-bounds.top);
		if(pixelLength>Integer.MAX_VALUE||!resourceRange(size,pixelOffset,(int)pixelLength))return false;
		int tableOffset=memory.readLong(mapAddress+42),tableLength=0;
		if(tableOffset!=0){if(!resourceRange(size,tableOffset,8))return false;int last=(short)memory.readWord(resource+tableOffset+6);
			if(last< -1)return false;long length=8L+(long)(last+1)*8L;if(length>Integer.MAX_VALUE||!resourceRange(size,tableOffset,(int)length))return false;tableLength=(int)length;}
		byte[] map=memoryBytes(mapAddress,50),pixels=memoryBytes(resource+pixelOffset,(int)pixelLength);int tableHandle=0;
		if(tableLength>0)tableHandle=macApi.createHandle(memoryBytes(resource+tableOffset,tableLength));
		putLong(map,0,0);putLong(map,42,tableHandle);int mapHandle=macApi.createHandle(map),pixelHandle=macApi.createHandle(pixels);
		byte[] pattern=memoryBytes(resource,28);putLong(pattern,2,mapHandle);putLong(pattern,6,pixelHandle);putLong(pattern,10,0);putWord(pattern,14,0xffff);putLong(pattern,16,0);
		macApi.loadHandle(handle,pattern);return true;}
	private boolean resourceRange(int size,int offset,int length) {return offset>=0&&length>=0&&offset<=size&&length<=size-offset;}
	private byte[] memoryBytes(int address,int length) throws M68kCpuException {byte[] bytes=new byte[length];
		for(int i=0;i<length;i++)bytes[i]=(byte)memory.readByte(address+i);return bytes;}
	private boolean makeRgbPat(M68kCpu cpu) throws M68kCpuException {int color=memory.readLong(cpu.a[7]),pattern=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(pattern!=0&&color!=0){int data=memory.readLong(pattern);if(data!=0){memory.writeWord(data,2);memory.copy(color,data+20,6);int ink=~readRgb(color)&0xffffff;
			for(int i=26;i<28;i++)memory.writeByte(data+i,ink==0?0:0xff);}}return true;}
	private boolean fillColorRect(M68kCpu cpu) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]),rect=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(rect!=0)rasterColorShape(pattern,0,readRect(rect),0,0);return true;}
	private boolean fillColorOval(M68kCpu cpu) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]),rect=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(rect!=0)rasterColorShape(pattern,1,readRect(rect),0,0);return true;}
	private boolean fillColorRoundRect(M68kCpu cpu) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]),height=(short)memory.readWord(cpu.a[7]+4),width=(short)memory.readWord(cpu.a[7]+6);
		int rect=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;if(rect!=0)rasterColorShape(pattern,2,readRect(rect),width,height);return true;}
	private boolean fillColorArc(M68kCpu cpu) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]),arc=(short)memory.readWord(cpu.a[7]+4),start=(short)memory.readWord(cpu.a[7]+6);
		int rect=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;if(rect!=0)rasterColorShape(pattern,3,readRect(rect),start,arc);return true;}
	private boolean fillColorRegion(M68kCpu cpu) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]),region=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(region!=0)rasterColorShape(pattern,0,readRegionRect(region),0,0);return true;}
	private boolean fillColorPolygon(M68kCpu cpu) throws M68kCpuException {int pattern=memory.readLong(cpu.a[7]),polygon=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(polygon==0)return true;int saved=activePixPat;activePixPat=pattern==0?SOLID_FOREGROUND_PIXPAT:pattern;try{rasterPolygon(readPolygon(polygon),4,0);}finally{activePixPat=saved;}return true;}
	/** FillC*(shape, nil): nothing to read through a nil PixPat Handle (the ROM would read low memory); treated as a solid
	 *  fill in the foreground colour, which is what bitmap editors mean by it; an ICON editor may hand BitEdit no
	 *  fill pattern for 1-bit images (a real Mac takes the black-and-white path then, see {@link #readCurrentBitmap}). */
	private static final int SOLID_FOREGROUND_PIXPAT=-1;
	private void rasterColorShape(int pattern,int shape,Rect rect,int first,int second) throws M68kCpuException {int saved=activePixPat;activePixPat=pattern==0?SOLID_FOREGROUND_PIXPAT:pattern;
		if(Mac68kApi.TRACE&&pattern!=0)System.err.println("[68K] FillC pixPat "+describePixPat(pattern));
		if(pattern!=0&&recordingPicture()){recordPicturePixPat(0x14,pattern);if(shape==2)recordPictureOvalSize(first,second);pictureOpcode(shape==0?0x34:shape==1?0x54:shape==2?0x44:0x64);pictureRect(rect);if(shape==3){pictureWord(first);pictureWord(second);}}
		try{if(shape==0)rasterRect(rect,4,0);else if(shape==1)rasterOval(rect,4,0);else if(shape==2)rasterRoundRect(rect,first,second,4,0);
			else rasterArc(rect,first,second,4,0);}finally{activePixPat=saved;}}
	private boolean setRgbColor(M68kCpu cpu,boolean foreground) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&color!=0&&isColorPort(currentPort)){memory.copy(color,currentPort+(foreground?36:42),6);PortState ps=ports.get(currentPort);if(ps!=null){if(foreground)ps.fgIndex=-1;else ps.bkIndex=-1;}
			updatePortColorValue(foreground);recordPictureRgb(foreground?0x1a:0x1b,color);}
		else if(currentPort!=0&&color!=0){int value=classicColorOf(memory.readWord(color),memory.readWord(color+2),memory.readWord(color+4)); // System 7: a GrafPort gets one of the eight colours
			memory.writeLong(currentPort+(foreground?FG_COLOR:BK_COLOR),value);recordPictureLongState(foreground?0x0e:0x0f,value);}
		if(Mac68kApi.TRACE&&color!=0)System.err.println("[68K] "+(foreground?"RGBForeColor ":"RGBBackColor ")+"port $"+Integer.toHexString(currentPort)+" "+Integer.toHexString(readRgb(color))+" raw "+Integer.toHexString(memory.readWord(color))+","+Integer.toHexString(memory.readWord(color+2))+","+Integer.toHexString(memory.readWord(color+4)));return true;}
	private boolean getRgbColor(M68kCpu cpu,boolean foreground) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(color==0)return true;
		if(currentPort!=0&&isColorPort(currentPort))memory.copy(currentPort+(foreground?36:42),color,6);
		else{int value=currentPort==0?0:memory.readLong(currentPort+(foreground?FG_COLOR:BK_COLOR)),rgb=quickDrawColor(value);
			writeRgb(color,(rgb>>>16&255)*257,(rgb>>>8&255)*257,(rgb&255)*257);}return true;}
	private boolean setGrafVarsColor(M68kCpu cpu,int offset) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&color!=0&&isColorPort(currentPort)){int variables=grafVarsOf(currentPort);
			if(variables==0){variables=macApi.createHandle(new byte[26]);setGrafVarsOf(currentPort,variables);}
			int data=memory.readLong(variables);if(data!=0)memory.copy(color,data+offset,6);}return true;}
	private boolean setColorPixel(M68kCpu cpu) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);int v=(short)memory.readWord(cpu.a[7]+4),h=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;
		if(currentPort!=0&&color!=0)readCurrentBitmap().setInkRgb(h,v,~readRgb(color)&0xffffff);return true;}
	/** The RGB of a pixel of the current port as GetCPixel reads it, or -1 when there is no port or the point is outside. */
	int currentPortRgb(int h,int v) throws M68kCpuException {
		if(currentPort==0)return -1;
		Bitmap bitmap=readCurrentBitmap();
		if(!bitmap.contains(h,v))return -1;
		return bitmap.throughDevice?bitmap.matchPalette[bitmap.getIndex(h,v)&bitmap.matchPalette.length-1]:~bitmap.getInkRgb(h,v)&0xffffff;
	}
	private boolean getColorPixel(M68kCpu cpu) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);int v=(short)memory.readWord(cpu.a[7]+4),h=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;
		if(color!=0){int rgb=0xffffff;
			if(currentPort!=0){Bitmap bitmap=readCurrentBitmap();
				// the pixel value is looked up in theGDevice's table (a bitmap editor may read its icon under its own device, whose
				// table lists black at 0, to decide between painting and erasing)
				rgb=bitmap.throughDevice&&bitmap.contains(h,v)?bitmap.matchPalette[bitmap.getIndex(h,v)&bitmap.matchPalette.length-1]:~bitmap.getInkRgb(h,v)&0xffffff;}
			writeRgb(color,(rgb>>>16&255)*257,(rgb>>>8&255)*257,(rgb&255)*257);}return true;}
	private boolean getColorTable(M68kCpu cpu) throws M68kCpuException {int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		int handle=standardColorTable(id);if(handle==0){handle=macApi.detachedResource(CLUT_TYPE,id);if(!validColorTable(handle)){if(handle!=0)macApi.disposeHandle(handle);handle=0;}
			else memory.writeLong(memory.readLong(handle),id);}
		memory.writeLong(cpu.a[7],handle);return true;}
	private boolean disposeColorTable(M68kCpu cpu) throws M68kCpuException {int handle=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(handle!=0){protectedEntries.remove(handle);reservedEntries.remove(handle);macApi.disposeHandle(handle);}return true;}
	private boolean getColorTableSeed(M68kCpu cpu) throws M68kCpuException {memory.writeLong(cpu.a[7],colorTableSeed++);return true;}
	private boolean colorToIndex(M68kCpu cpu) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		memory.writeLong(cpu.a[7],color==0?0:colorIndex(readRgb16(color)));return true;}
	private boolean indexToColor(M68kCpu cpu) throws M68kCpuException {int color=memory.readLong(cpu.a[7]),index=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(color!=0){long rgb=indexColor(index);writeRgb(color,(int)(rgb>>>32)&0xffff,(int)(rgb>>>16)&0xffff,(int)rgb&0xffff);}return true;}
	private boolean invertColor(M68kCpu cpu) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(color!=0)writeRgb(color,~memory.readWord(color)&0xffff,~memory.readWord(color+2)&0xffff,~memory.readWord(color+4)&0xffff);return true;}
	private boolean realColor(M68kCpu cpu) throws M68kCpuException {int color=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		writeBoolean(cpu.a[7],color!=0&&indexColor(colorIndex(readRgb16(color)))==readRgb16(color));return true;}
	/**
	 * MakeITable(cTabH, iTabH, res): fills an inverse table (iTabSeed, iTabRes, then 2^(3*res) bytes indexed by the
	 * top {@code res} bits of each of R, G, B) with the nearest entry of the colour table. Paint applications
	 * module builds one for its 8-bit offscreen buffers. NIL arguments mean the current device's tables.
	 */
	private boolean makeInverseTable(M68kCpu cpu) throws M68kCpuException {
		int res=(short)memory.readWord(cpu.a[7]),inverse=memory.readLong(cpu.a[7]+2),table=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
		quickDrawError=0;
		// NIL tables / resolution 0: theGDevice's pmTable, gdITable and gdResPref (IM V-142; MakeITable(nil,nil,0) after changing the clut)
		int gd=currentGDevice!=0?currentGDevice:mainGDevice,gdData=gd==0?0:memory.readLong(gd);
		if(table==0)table=deviceTable(gdData);
		if(table==0)table=currentColorTable();
		if(inverse==0&&gdData!=0)inverse=memory.readLong(gdData+6);
		if(res==0&&gdData!=0)res=memory.readWord(gdData+10);
		if(inverse==0)return true;
		if(res<=0||res>5){quickDrawError=C_RANGE_ERR;return true;}
		fillInverseTable(inverse,table,res);
		return true;
	}
	/** The pmTable of a GDevice's PixMap (0 when there is none). */
	private int deviceTable(int gdData) throws M68kCpuException {int map=gdData==0?0:memory.readLong(gdData+22),mapData=map==0?0:memory.readLong(map);return mapData==0?0:memory.readLong(mapData+42);}
	/**
	 * Fills an inverse table Handle (iTabSeed, iTabRes, then 2^(3*res) bytes indexed by the top {@code res} bits of each of
	 * R, G, B) with the nearest entry of a colour table. The seed is the table's ctSeed, so a program that compares the two
	 * (SuperPaint 3.0's paint compositing) finds it current. A direct device's table has no entries: its inverse table maps to
	 * the standard 8-bit colours, which is what a program composing an 8-bit buffer through theGDevice's table needs.
	 */
	private void fillInverseTable(int inverse,int table,int res) throws M68kCpuException {
		int data=table==0?0:memory.readLong(table);int last=data==0?-1:(short)memory.readWord(data+6),count=Math.min(256,last+1);
		int seed=data==0?0:memory.readLong(data);
		int standard=0;if(count<=0){standard=standardColorTable(8);data=memory.readLong(standard);count=256;}
		int[] index=new int[count],reds=new int[count],greens=new int[count],blues=new int[count];
		for(int i=0;i<count;i++){int entry=data+8+i*8;index[i]=memory.readWord(entry);reds[i]=memory.readWord(entry+2);greens[i]=memory.readWord(entry+4);blues[i]=memory.readWord(entry+6);}
		if(standard!=0)macApi.disposeHandleAndFree(standard);
		String key=res+":"+Arrays.hashCode(index)+":"+Arrays.hashCode(reds)+":"+Arrays.hashCode(greens)+":"+Arrays.hashCode(blues);
		byte[] cells=inverseCells.get(key);
		if(cells==null){
			int levels=1<<res;cells=new byte[levels*levels*levels];
			for(int r=0;r<levels;r++)for(int g=0;g<levels;g++)for(int b=0;b<levels;b++){
				int red=r*0xffff/(levels-1),green=g*0xffff/(levels-1),blue=b*0xffff/(levels-1);
				long bestDistance=Long.MAX_VALUE;int best=0;
				for(int i=0;i<count;i++){int dr=red-reds[i],dg=green-greens[i],db=blue-blues[i];long distance=(long)dr*dr+(long)dg*dg+(long)db*db;if(distance<bestDistance){bestDistance=distance;best=index[i];}}
				cells[(r<<(2*res))+(g<<res)+b]=(byte)best;
			}
			if(inverseCells.size()>16)inverseCells.clear();inverseCells.put(key,cells);
		}
		byte[] bytes=new byte[6+cells.length];putLong(bytes,0,seed);putWord(bytes,4,res);System.arraycopy(cells,0,bytes,6,cells.length);
		if(macApi.handleSize(inverse)<bytes.length)macApi.resizeHandle(inverse,bytes.length);
		int target=memory.readLong(inverse);if(target!=0)memory.load(target,bytes);
	}
	private final Map<String,byte[]> inverseCells=new HashMap<String,byte[]>();
	/** A GDevice's gdITable built for its PixMap's table at gdResPref (a real InitGDevice/NewGWorld leaves a usable one). */
	private void refreshDeviceInverseTable(int device) throws M68kCpuException {
		int data=device==0?0:memory.readLong(device);if(data==0)return;int inverse=memory.readLong(data+6);if(inverse==0)return;
		int res=memory.readWord(data+10);fillInverseTable(inverse,deviceTable(data),res>=1&&res<=5?res:4);
	}

	private boolean markColorEntry(M68kCpu cpu,Map<Integer,BitSet> states) throws M68kCpuException {boolean enabled=memory.readByte(cpu.a[7])!=0;
		int index=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;quickDrawError=0;int table=deviceColorTable(),entry=findColorEntry(table,index);
		if(table==0){quickDrawError=C_DEV_ERR;return true;}if(entry==0){quickDrawError=C_RANGE_ERR;return true;}
		BitSet bits=states.get(table);if(bits==null){bits=new BitSet(256);states.put(table,bits);}
		if(enabled&&bits.get(index&0xffff)){quickDrawError=C_PROTECT_ERR;return true;}bits.set(index&0xffff,enabled);changeColorTableSeed(table);return true;}
	private boolean setColorEntries(M68kCpu cpu) throws M68kCpuException {int source=memory.readLong(cpu.a[7]);int count=(short)memory.readWord(cpu.a[7]+4);
		int start=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;quickDrawError=0;int table=deviceColorTable();
		if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder();for(int i=0;i<=Math.min(count,255)&&source!=0;i++){if(i>=6&&i<count-1){if(i==6)sb.append(" ..");continue;}sb.append(' ').append(Integer.toHexString(memory.readWord(source+i*8+2)>>8<<16|memory.readWord(source+i*8+4)>>8<<8|memory.readWord(source+i*8+6)>>8));}
			System.err.println("[68K] SetEntries start "+start+" count "+count+" table $"+Integer.toHexString(table)+" colours"+sb);}
		if(table==0){quickDrawError=C_DEV_ERR;return true;}if(source==0||count<0||count>=256){quickDrawError=C_RANGE_ERR;return true;}int entries=count+1;
		int[] destinations=new int[entries];for(int i=0;i<entries;i++){int index=start==-1?(short)memory.readWord(source+i*8):start+i;
			destinations[i]=findColorEntry(table,index);if(destinations[i]==0){quickDrawError=C_RANGE_ERR;return true;}
			BitSet protectedBits=protectedEntries.get(table);if(protectedBits!=null&&protectedBits.get(index&0xffff)){quickDrawError=C_PROTECT_ERR;return true;}}
		for(int i=0;i<entries;i++)memory.copy(source+i*8+2,destinations[i]+2,6);changeColorTableSeed(table);
		if(entries<=SMALL_SET_ENTRIES&&start>=0&&start+entries<=256){int[] counts=entryChanges.computeIfAbsent(table,t->new int[256]);
			for(int i=0;i<entries;i++){if(counts[start+i]==ANIMATED_CHANGES-1)animatedCache=null;if(counts[start+i]<Integer.MAX_VALUE)counts[start+i]++;}}
		return true;}
	/** How often a small SetEntries (at most {@link #SMALL_SET_ENTRIES} entries) rewrote each entry of a device table: the slots a
	 *  colour cycle works on, set up that way before the cycle starts (Factory 1.1 sets 128-144 once, draws its status bar, then cycles). */
	private final Map<Integer,int[]> entryChanges=new HashMap<Integer,int[]>();
	/** Entries of the main device's table the program sets one by one with small SetEntries (colour-cycle slots), or null. Colours the
	 *  table does not hold (antialiased text edges) must not land on them: Factory 1.1's status texts flickered with its conveyor belts. */
	boolean[] animatedEntries() throws M68kCpuException {if(entryChanges.isEmpty())return null;int table=deviceColorTable();
		if(animatedCache!=null&&animatedCacheTable==table)return animatedCache.length==0?null:animatedCache;
		int[] counts=table==0?null:entryChanges.get(table);boolean[] result=new boolean[256];boolean any=false;
		if(counts!=null)for(int i=0;i<256;i++)if(counts[i]>=ANIMATED_CHANGES){result[i]=true;any=true;}
		animatedCache=any?result:new boolean[0];animatedCacheTable=table;return any?result:null;}
	private static final int ANIMATED_CHANGES=1,SMALL_SET_ENTRIES=32;private boolean[] animatedCache;private int animatedCacheTable;
	/**
	 * SaveEntries(srcTable, resultTable, selection) (IM Imaging With QuickDraw, "Color QuickDraw"): copies the entries a
	 * ReqListRec names (reqLSize = count - 1, then the indexes) out of srcTable - NIL: theGDevice's table - into resultTable
	 * in the order asked, each ColorSpec's value the index it came from. Programs that change a few screen colours for a
	 * while (a fade of their own, a colour cycle) keep the originals this way and put them back with RestoreEntries.
	 */
	private boolean saveEntries(M68kCpu cpu) throws M68kCpuException {
		int selection=memory.readLong(cpu.a[7]),result=memory.readLong(cpu.a[7]+4),source=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;quickDrawError=0;
		if(source==0)source=deviceColorTable();
		if(source==0||!validColorTable(source)){quickDrawError=C_DEV_ERR;return true;}
		if(result==0||selection==0){quickDrawError=C_RANGE_ERR;return true;}
		int count=(short)memory.readWord(selection)+1;if(count<1||count>256){quickDrawError=C_RANGE_ERR;return true;}
		byte[] table=new byte[8+count*8];putLong(table,0,memory.readLong(memory.readLong(source)));putWord(table,6,count-1);
		for(int i=0;i<count;i++){int index=memory.readWord(selection+2+i*2),entry=findColorEntry(source,index);putWord(table,8+i*8,index);
			if(entry==0){quickDrawError=C_RANGE_ERR;memory.writeWord(selection+2+i*2,-1);continue;} // colReqErr: this one was not there
			for(int k=2;k<8;k+=2)putWord(table,8+i*8+k,memory.readWord(entry+k));}
		if(macApi.handleSize(result)!=table.length)macApi.resizeHandle(result,table.length);memory.load(memory.readLong(result),table);
		if(Mac68kApi.TRACE)System.err.println("[68K] SaveEntries "+count+" entries of $"+Integer.toHexString(source)+" -> "+quickDrawError);
		return true;
	}
	/** RestoreEntries(srcTable, dstTable, selection): srcTable's entries, in order, go to the indexes the selection names in
	 *  dstTable (NIL: theGDevice's table, which the screen shows at once); protection is not checked - the colours are put back. */
	private boolean restoreEntries(M68kCpu cpu) throws M68kCpuException {
		int selection=memory.readLong(cpu.a[7]),destination=memory.readLong(cpu.a[7]+4),source=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;quickDrawError=0;
		if(destination==0)destination=deviceColorTable();
		if(destination==0||!validColorTable(destination)){quickDrawError=C_DEV_ERR;return true;}
		if(source==0||!validColorTable(source)||selection==0){quickDrawError=C_RANGE_ERR;return true;}
		int count=(short)memory.readWord(selection)+1,available=(short)memory.readWord(memory.readLong(source)+6)+1;
		if(count<1||count>256||count>available){quickDrawError=C_RANGE_ERR;return true;}
		boolean changed=false;int data=memory.readLong(source);
		for(int i=0;i<count;i++){int index=(short)memory.readWord(selection+2+i*2);if(index<0)continue;int entry=findColorEntry(destination,index);
			if(entry==0){quickDrawError=C_RANGE_ERR;continue;}memory.copy(data+8+i*8+2,entry+2,6);changed=true;}
		if(changed)changeColorTableSeed(destination);
		if(Mac68kApi.TRACE)System.err.println("[68K] RestoreEntries "+count+" entries into $"+Integer.toHexString(destination)+" -> "+quickDrawError);
		return true;
	}
	/** RestoreDeviceClut(gd): the device's colour table back to the standard one of its depth (NIL: every device) -
	 *  Loony Labyrinth puts the screen back that way after its own colours. */
	void restoreDeviceClut(int device) throws M68kCpuException {
		java.util.List<Integer> devices=new java.util.ArrayList<Integer>();
		if(device!=0)devices.add(device);else{if(mainGDevice!=0)devices.add(mainGDevice);devices.addAll(dynamicDevices.keySet());}
		for(int gd:devices){
			int table=deviceColorTable(gd);if(table==0||!validColorTable(table))continue;
			int data=memory.readLong(gd),pixMap=data==0?0:memory.readLong(data+22),map=pixMap==0?0:memory.readLong(pixMap);if(map==0)continue;
			int depth=memory.readWord(map+32);if(depth>8)continue;
			int standard=standardColorTable(depth);if(standard==0)continue;
			int from=memory.readLong(standard),to=memory.readLong(table);
			int n=Math.min((short)memory.readWord(from+6),(short)memory.readWord(to+6))+1;
			for(int i=0;i<n;i++)memory.copy(from+8+i*8+2,to+8+i*8+2,6);
			macApi.disposeHandle(standard);changeColorTableSeed(table);
		}
	}
	private boolean getQuickDrawError(M68kCpu cpu) throws M68kCpuException {memory.writeWord(cpu.a[7],quickDrawError);return true;}
	/**
	 * Palette Manager activation on an indexed main screen: entries with pmExplicit (bit 3) usage go to their own index;
	 * a palette with at least as many entries as the table replaces every entry but the first (white) and last (black).
	 * Those two stay even when the palette makes them explicit: "the Palette Manager will not overwrite white (the first
	 * entry) or black (the last entry)" (IM Advanced Color Imaging). Glimmer's second palette is explicit and inverted
	 * (black at 0, white at 255); loading it turned its black window background, index 255, white.
	 * Nothing happens on a direct-colour screen. The seed changes as after SetEntries.
	 */
	boolean loadDeviceColors(int[] rgb,int[] usage) throws M68kCpuException {return loadDeviceColors(rgb,usage,null);}
	boolean loadDeviceColors(int[] rgb,int[] usage,int[] tolerance) throws M68kCpuException {
		if(mainDevicePixMap==0)return false;int map=memory.readLong(mainDevicePixMap);if(map==0)return false;int depth=memory.readWord(map+32);if(depth>8)return false;
		int table=memory.readLong(map+42);if(table==0)return false;int size=1<<depth;boolean explicit=false;for(int u:usage)if((u&8)!=0)explicit=true;
		if(!explicit&&depth<=4&&tolerantPalette(usage))return correlateTolerant(table,depth,rgb,usage,tolerance);
		if(!explicit&&rgb.length<size)return false;boolean changed=false;
		for(int i=0;i<Math.min(rgb.length,size);i++){
			boolean own=(usage[i]&8)!=0;if(i==0||i==size-1||explicit&&!own)continue;
			int entry=findColorEntry(table,i);if(entry==0)continue;
			int red=(rgb[i]>>>16&255)*257,green=(rgb[i]>>>8&255)*257,blue=(rgb[i]&255)*257;
			if(memory.readWord(entry+2)!=red||memory.readWord(entry+4)!=green||memory.readWord(entry+6)!=blue){memory.writeWord(entry+2,red);memory.writeWord(entry+4,green);memory.writeWord(entry+6,blue);changed=true;}}
		if(changed){changeColorTableSeed(table);if(Mac68kApi.TRACE)System.err.println("[68K] palette activated: "+rgb.length+" entries loaded into the screen colour table");}
		return changed;
	}
	/** A palette of tolerant colours (pmTolerant, usage bit 1) and no animated ones (pmAnimated, bit 2). */
	private static boolean tolerantPalette(int[] usage){boolean tolerant=false;for(int u:usage){if((u&4)!=0)return false;if((u&2)!=0)tolerant=true;}return tolerant;}
	/**
	 * The Palette Manager's allocation of tolerant colours on a 16-colour (or smaller) screen, as its Correlate routine does it
	 * (QuickDraw/PaletteMgr.a: AnalyzeDev, Correlate, GimmeIndex): white and black stay put; every other tolerant entry, in palette
	 * order, takes the closest screen entry (Color2Index) when that is within its tolerance; otherwise it overwrites that closest
	 * entry if no earlier entry took it, or else the next free index counting down from the one below black. The screen starts
	 * from the standard table of its depth - colours of palettes no longer in front are scattered back to it. So the palette's
	 * colours land in slots of their own choosing, not palette index = pixel value: Color VETTE! draws its road scene in the
	 * slots System 6 gives its 'pltt' 131 (sky blue 78B5FF, entry 15, in slot 4) and copies the pixels by index (it forces the
	 * GWorld's ctSeed to the screen's). Closeness is the city-block distance, which reproduces the slots measured on System
	 * 6.0.8 for all four of VETTE!'s 16-colour palettes (the Amiga port's captures).
	 */
	/** The screen colours (0xRRGGBB, index = pixel value) after {@link #correlateTolerant}'s allocation of {@code rgb} into {@code device}. */
	static int[] allocateTolerant(int[] device,int[] rgb,int[] usage,int[] tolerance){
		int size=device.length;device=device.clone();boolean[] taken=new boolean[size];taken[0]=taken[size-1]=true;int next=size-2;
		for(int e=0;e<rgb.length;e++){
			if((usage[e]&2)==0)continue;int color=rgb[e]&0xffffff;if(color==0||color==0xffffff)continue; // black and white are always there
			int closest=0,best=Integer.MAX_VALUE;
			for(int i=0;i<size;i++){int c=device[i],d=Math.abs((c>>>16)-(color>>>16))+Math.abs((c>>>8&255)-(color>>>8&255))+Math.abs((c&255)-(color&255));if(d<best){best=d;closest=i;}}
			int c=device[closest],delta=Math.max(Math.abs((c>>>16)-(color>>>16)),Math.max(Math.abs((c>>>8&255)-(color>>>8&255)),Math.abs((c&255)-(color&255))))*257;
			if(delta<=(tolerance==null||e>=tolerance.length?0:tolerance[e]&0xffff)){taken[closest]=true;continue;}
			if(taken[closest]){closest=-1;while(next>=1){int i=next--;if(!taken[i]){closest=i;break;}}if(closest<0)break;}
			device[closest]=color;taken[closest]=true;
		}
		return device;
	}
	private boolean correlateTolerant(int table,int depth,int[] rgb,int[] usage,int[] tolerance) throws M68kCpuException {
		int size=1<<depth,standard=standardColorTable(depth);if(standard==0)return false;
		int[] device=new int[size];int from=memory.readLong(standard);
		for(int i=0;i<size;i++)device[i]=(memory.readWord(from+8+i*8+2)>>>8)<<16|(memory.readWord(from+8+i*8+4)>>>8)<<8|memory.readWord(from+8+i*8+6)>>>8;
		macApi.disposeHandle(standard);
		device=allocateTolerant(device,rgb,usage,tolerance);
		boolean changed=false;
		for(int i=1;i<size-1;i++){int entry=findColorEntry(table,i);if(entry==0)continue;
			int red=(device[i]>>>16&255)*257,green=(device[i]>>>8&255)*257,blue=(device[i]&255)*257;
			if(memory.readWord(entry+2)!=red||memory.readWord(entry+4)!=green||memory.readWord(entry+6)!=blue){memory.writeWord(entry+2,red);memory.writeWord(entry+4,green);memory.writeWord(entry+6,blue);changed=true;}}
		if(changed){changeColorTableSeed(table);if(Mac68kApi.TRACE)System.err.println("[68K] palette activated: "+rgb.length+" tolerant entries allocated in the "+size+"-colour screen table");}
		return changed;
	}
	private int findColorEntry(int handle,int index) throws M68kCpuException {if(handle==0)return 0;int data=memory.readLong(handle);
		if(data==0)return 0;int last=(short)memory.readWord(data+6),count=Math.min(256,last+1);for(int i=0;i<count;i++){
			int entry=data+8+i*8;if(memory.readWord(entry)==(index&0xffff))return entry;}return 0;}
	void changeColorTableSeed(int handle) throws M68kCpuException {if(handle!=0){int data=memory.readLong(handle);if(data!=0){memory.writeLong(data,colorTableSeed++);
		// QuickDraw rebuilds the device's inverse table now: colour matching follows these entries until the next seed change
		seedsOf(handle).put(memory.readLong(data),readColorTable(handle,8));}}}

	private boolean validColorTable(int handle) throws M68kCpuException {if(handle==0||macApi.handleSize(handle)<8)return false;int data=memory.readLong(handle);
		if(data==0)return false;int last=(short)memory.readWord(data+6);return last>=-1&&8L+(long)(last+1)*8L<=macApi.handleSize(handle);}
	private int standardColorTable(int id) throws M68kCpuException {int depth=id;
		if(id==34||id==36||id==40)depth=id-32;else if(id==65||id==66||id==68||id==72)depth=id-64;
		else if(id!=1&&id!=2&&id!=4&&id!=8)return 0;
		int count=1<<depth;int[] colors=new int[count];
		if(id>=32&&id<64){for(int i=0;i<count;i++){int gray=count==1?255:255-i*255/(count-1);colors[i]=gray<<16|gray<<8|gray;}}
		else if(depth==1){colors[0]=0xffffff;colors[1]=0;}
		else if(depth==2){colors[0]=0xffffff;colors[1]=0xff0000;colors[2]=0x00ff00;colors[3]=0;}
		else if(depth==4){ // the ROM's 'clut' 4 (the System file's 'clut' 127 and 5 carry the same entries: yellow $FC00 $F37D $052F...)
			int[] classic={0xffffff,0xfcf305,0xff6402,0xdd0806,0xf20884,0x4600a5,0x0000d4,0x02abea,
			0x1fb714,0x006411,0x562c05,0x90713a,0xc0c0c0,0x808080,0x404040,0};System.arraycopy(classic,0,colors,0,colors.length);}
		else {for(int i=0;i<count;i++)colors[i]=MacSystemPalette.rgb(i);} // classic order: 0 = white, 255 = black
		byte[] table=new byte[8+count*8];putLong(table,0,id);putWord(table,6,count-1); // a standard table's seed is its ID
		for(int i=0;i<count;i++){int offset=8+i*8,color=colors[i];putWord(table,offset,i);putWord(table,offset+2,(color>>>16&255)*257);
			putWord(table,offset+4,(color>>>8&255)*257);putWord(table,offset+6,(color&255)*257);}return macApi.createHandle(table);}
	private int currentPixelSize() throws M68kCpuException {if(currentPort==0||!isColorPort(currentPort))return 1;int map=bitmapPointer(currentPort);return map==0?1:memory.readWord(map+32);}
	private boolean currentDirect() throws M68kCpuException {if(currentPort==0||!isColorPort(currentPort))return false;int map=bitmapPointer(currentPort);return map!=0&&memory.readWord(map+32)>8;}
	private int currentColorTable() throws M68kCpuException {if(currentPort==0||!isColorPort(currentPort))return 0;int map=bitmapPointer(currentPort);return map==0?0:memory.readLong(map+42);}
	/** The colour table SetEntries/ProtectEntry/ReserveEntry work on: theGDevice's (IM V-143), whatever port is current - some games
	 *  loads its palette with the classic Window Manager port current, whose BitMap has no table at all; the port's table is the fallback. */
	private int deviceColorTable() throws M68kCpuException {
		int device=currentGDevice!=0?currentGDevice:mainGDevice;
		if(device!=0){int data=memory.readLong(device);int pixMap=data==0?0:memory.readLong(data+22);int map=pixMap==0?0:memory.readLong(pixMap);
			if(map!=0&&memory.readWord(map+32)<=8){int table=memory.readLong(map+42);if(table!=0)return table;}}
		return currentColorTable();
	}
	/** The PixMap Color2Index/Index2Color/RealColor work through: theGDevice's (a program that SetGDevices a device of its own -
	 *  QuickTime's GIF codec builds one around the picture's colour table - gets indexes into that table), else the port's. */
	private int devicePixMapData() throws M68kCpuException {
		if(currentGDevice!=0&&currentGDevice!=mainGDevice){int device=memory.readLong(currentGDevice);int pixMap=device==0?0:memory.readLong(device+22);int map=pixMap==0?0:memory.readLong(pixMap);if(map!=0)return map;}
		if(currentPort==0||!isColorPort(currentPort))return 0;return bitmapPointer(currentPort);
	}
	/** The pixel value of an 0xRRGGBB colour on theGDevice when that is a direct device (as Color2Index gives it), else -1. */
	int directPixelValue(int rgb) throws M68kCpuException {int map=devicePixMapData();if(map==0||memory.readWord(map+32)<=8)return -1;
		return colorIndex((long)((rgb>>>16&255)*257)<<32|(long)((rgb>>>8&255)*257)<<16|(rgb&255)*257);}
	private int colorIndex(long rgb16) throws M68kCpuException {int map=devicePixMapData();int size=map==0?1:memory.readWord(map+32);boolean direct=map!=0&&size>8;
		int red=(int)(rgb16>>>32)&0xffff,green=(int)(rgb16>>>16)&0xffff,blue=(int)rgb16&0xffff;if(direct){
		if(size==16)return (red>>>11&31)<<10|(green>>>11&31)<<5|(blue>>>11&31);
		return (red>>>8)<<16|(green>>>8)<<8|blue>>>8;}
		int handle=map==0?0:memory.readLong(map+42),data=handle==0?0:memory.readLong(handle),last=data==0?-1:(short)memory.readWord(data+6),count=Math.min(256,last+1);
		if(count<=0){int gray=(red*299+green*587+blue*114)/1000;return gray<0x8000?1:0;}
		long bestDistance=Long.MAX_VALUE;int best=0;
		BitSet reserved=reservedEntries.get(handle);for(int i=0;i<count;i++){int entry=data+8+i*8,index=memory.readWord(entry);if(reserved!=null&&reserved.get(index))continue;
			int dr=red-memory.readWord(entry+2),dg=green-memory.readWord(entry+4),db=blue-memory.readWord(entry+6);
			long distance=(long)dr*dr+(long)dg*dg+(long)db*db;if(distance<bestDistance){bestDistance=distance;best=index;}}return best;}
	private long indexColor(int index) throws M68kCpuException {int map=devicePixMapData();int size=map==0?1:memory.readWord(map+32);boolean direct=map!=0&&size>8;if(direct){
		if(size==16)return expand5(index>>>10&31)<<32|expand5(index>>>5&31)<<16|expand5(index&31); // the ROM repeats the 5 bits: 19 -> 40167 (Caliban's mappedColor), not 19*65535/31 = 40166
		return (long)(index>>>16&255)*257<<32|(long)(index>>>8&255)*257<<16|(long)(index&255)*257;}
		int handle=map==0?0:memory.readLong(map+42),data=handle==0?0:memory.readLong(handle),last=data==0?-1:(short)memory.readWord(data+6),count=Math.min(256,last+1);
		for(int i=0;i<count;i++){int entry=data+8+i*8;if(memory.readWord(entry)==(index&0xffff))return readRgb16(entry+2);}
		if(count>0&&index>=0&&index<count)return readRgb16(data+8+index*8+2);long value=index==0?0xffffL:0;return value<<32|value<<16|value;}
	private static long expand5(int c){return c<<11|c<<6|c<<1|c>>>4;}
	private long readRgb16(int pointer) throws M68kCpuException {return (long)memory.readWord(pointer)<<32|(long)memory.readWord(pointer+2)<<16|memory.readWord(pointer+4);}

	private int regionHandle(int top,int left,int bottom,int right) throws M68kCpuException {
		byte[] bytes=new byte[10];putWord(bytes,0,10);putWord(bytes,2,top);putWord(bytes,4,left);
		putWord(bytes,6,bottom);putWord(bytes,8,right);return macApi.createHandle(bytes);
	}

	private PortState stateFromMemory(int port) throws M68kCpuException {
		PortState state=new PortState();
		state.originV=(short)memory.readWord(port+PORT_RECT);
		state.originH=(short)memory.readWord(port+PORT_RECT+2);return state;
	}

	private boolean closePort(M68kCpu cpu) throws M68kCpuException {
		int port=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(port!=0){boolean color=isColorPort(port);macApi.disposeHandle(memory.readLong(port+VIS_RGN));macApi.disposeHandle(memory.readLong(port+CLIP_RGN));
			if(color){int variables=grafVarsOf(port);if(variables!=0)macApi.disposeHandle(variables);disposePortPixMapCopy(port);disposeDefaultPixPats(port);}}
		ports.remove(port);
		if(currentPort==port)setCurrentPort(0);return true;
	}

	private boolean setPortBits(M68kCpu cpu) throws M68kCpuException {
		int bitmap=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort==0||bitmap==0)return true;
		if(!isColorPort(currentPort)){int target=bitmapPointer(currentPort);memory.copy(bitmap,target,14);
			// A port opened with OpenPort draws on the screen until the program points it at its own buffer (HyperCard
			// renders cards through such a port and SetPortBits); screenBits carries the screen's pixels (ScrnBase), 0 before a device exists.
			int base=memory.readLong(target);if(base==0||base==memory.readLong(0x824))rememberScreenPort(currentPort);else screenBitmaps.remove(target);} // SetPortBits(screenBits): back on the screen
		else setWindowPortBits(currentPort,bitmap);
		return true;
	}

	/**
	 * SetPortBits on a window port. To the program a NewWindow/GetNewWindow window is a classic GrafPort, inside it is a
	 * CGrafPort (see {@link #newWindowPort}): the 14 bytes of "portBits" hold the buffer's PixMap Handle, the version word
	 * $C000 and the classic portBits.bounds. Programs point the port at an offscreen BitMap of their own, draw or copy the
	 * window into it, and put back a copy of the original portBits (Beehave Beehive keeps the board that way; ignoring the
	 * call erased the window instead of the buffer). The BitMap gets a Handle of its own in the PixMap slot - the drawing
	 * primitives read a plain BitMap through it (1 bit, no host window behind it) - and its bounds go where the classic
	 * bounds live; a copy of our own layout (version word $C000), or a BitMap describing the screen, restores the buffer.
	 */
	private void setWindowPortBits(int port,int bitmap) throws M68kCpuException {
		PortState state=ports.get(port);if(state==null||!state.classicWindow)return; // a CGrafPort proper: SetPortPix is the call for those
		int rowWord=memory.readWord(bitmap+4);boolean ourLayout=(rowWord&0xc000)==0xc000;
		int previous=state.portBitsHandle;state.portBitsHandle=0;
		if(previous==0)state.windowBits=memoryBytes(port+PORT_BITS,14); // the port holds the window buffer: what a screen BitMap restores
		if(ourLayout)memory.copy(bitmap,port+PORT_BITS,14);
		else if(memory.readLong(bitmap)==0||bitmap==liveGlobals()-122||describesScreen(bitmap)||memory.readLong(bitmap)==memory.readLong(0x824))memory.load(port+PORT_BITS,state.windowBits);
		else{int handle=macApi.createHandle(memoryBytes(bitmap,14));memory.writeLong(port+PORT_BITS,handle);memory.copy(bitmap+6,port+PORT_BITS+6,8);state.portBitsHandle=handle;}
		if(previous!=0)macApi.disposeHandleAndFree(previous);
		if(Mac68kApi.TRACE)System.err.println("[68K] SetPortBits on window port $"+Integer.toHexString(port)+": "+(state.portBitsHandle!=0?"offscreen BitMap base $"+Integer.toHexString(memory.readLong(bitmap))+" rowBytes "+(rowWord&0x3fff)+" bounds "+readRect(bitmap+6):"window buffer restored"));
	}

	private boolean portSize(M68kCpu cpu) throws M68kCpuException {
		int height=(short)memory.readWord(cpu.a[7]),width=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;
		if(currentPort!=0){int top=(short)memory.readWord(currentPort+PORT_RECT);
			int left=(short)memory.readWord(currentPort+PORT_RECT+2);
			writeRect(currentPort+PORT_RECT,top,left,top+height,left+width);}return true;
	}

	private boolean movePortTo(M68kCpu cpu) throws M68kCpuException {
		int top=(short)memory.readWord(cpu.a[7]),left=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;
		if(currentPort==0)return true;
		Toolbox68k tb=macApi.application();
		if(tb!=null&&tb.hosted()&&tb.windows!=null&&tb.windows.window(currentPort)!=null){
			// On a window MovePortTo places the window at (leftGlobal, topGlobal) like MoveWindow, its local coordinates
			// untouched: some programs position an invisible window or dialog with MovePortTo,
			// ShowWindow it and then draw into its portRect / let the Dialog Manager draw the items at their DITL rectangles.
			tb.windows.move(currentPort,left,top);return true;}
		Rect rect=readRect(currentPort+PORT_RECT);
		if(!isColorPort(currentPort)){
			// IM I-170: portBits.bounds moves so that the portRect's top left lies at (leftGlobal, topGlobal); the local
			// coordinates stay. Sail Away opens a port per gauge, MovePortTo's it over its gauge window and CopyBits to
			// local (0,0) of a copy of that portBits - with the portRect moved instead, the gauges were drawn at the
			// screen's top left and clipped away.
			int bits=bitmapPointer(currentPort);Rect bounds=readRect(bits+6);
			int dh=(rect.left-left)-bounds.left,dv=(rect.top-top)-bounds.top;offsetRectAt(bits+6,dh,dv);return true;}
		// An OpenCPort port has a PixMap of its own (a copy of the screen's): its bounds move the same way, absolutely. HyperTint
		// opens a screen port and MovePortTo's it over the card window for every visual effect; moving the portRect (the local
		// coordinates) instead put each frame of the effect a window's offset further down the screen, then into memory past it.
		int mapHandle=memory.readLong(currentPort+PORT_BITS);
		if(mapHandle!=0&&portPixMapCopies.containsKey(mapHandle)){
			int data=memory.readLong(mapHandle);
			if(data!=0){Rect bounds=readRect(data+6);offsetRectAt(data+6,(rect.left-left)-bounds.left,(rect.top-top)-bounds.top);return true;}
		}
		int height=rect.bottom-rect.top,width=rect.right-rect.left; // another colour port (a GWorld's): its portRect moves instead
		writeRect(currentPort+PORT_RECT,top,left,top+height,left+width);return true;
	}

	private boolean setOrigin(M68kCpu cpu) throws M68kCpuException {
		int v=(short)memory.readWord(cpu.a[7]),h=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;
		applyOrigin(h,v);return true;
	}
	private void applyOrigin(int h,int v) throws M68kCpuException {
		if(currentPort!=0){PortState state=state();state.originV=v;state.originH=h;
			// Classic semantics: portRect.topLeft becomes (h,v) and the bitmap bounds shift with it,
			// so local coordinates grow by the new origin and global = local - origin.
			int bitmap=bitmapPointer(currentPort);Rect portRect=readRect(currentPort+PORT_RECT);
			int dv=v-portRect.top,dh=h-portRect.left;offsetRectAt(bitmap+6,dh,dv);
			// an open picture records the move (Origin, $000C dh dv), so what is drawn after it plays back where it was drawn: Wingz's
			// page preview records the page with each chart drawn under a SetOrigin to its place - they all came out at the corner
			if(recordingPicture()&&(dh!=0||dv!=0)){pictureOpcode(0x0c);pictureWord(dh);pictureWord(dv);}
			PixelMapState buffer=offscreenPixMapData.get(bitmap);if(buffer!=null&&buffer.windowPort!=0)buffer.bounds=offset(buffer.bounds,dh,dv);
			if(state.classicWindow)offsetRectAt(currentPort+GRAF_VARS,dh,dv); // the classic portBits.bounds move with the origin as well
			offsetRectAt(currentPort+PORT_RECT,dh,dv);
			// The visRgn moves with the coordinate system (it stays put on screen); the clipRgn is NOT offset (IM I-166):
			// a program that wants its clip to stay in place offsets it itself (SetOrigin + OffsetRgn(clip)).
			int visible=memory.readLong(currentPort+VIS_RGN);
			if(visible!=0)offsetRegionHandle(visible,dh,dv);}
	}
	/**
	 * OffsetRgn as the ROM does it: the rgnBBox moves even when the region is empty (NewRgn's (0,0,0,0) becomes (v,h,v,h)).
	 * PowerPlant's StEmptyVisRgn swaps an empty NewRgn in as the visRgn, and at the end offsets the saved visRgn by how far
	 * that empty box moved - a SetOrigin in between (Flash 4's SalesAgent dialog, LScroller showing its scroll bar) left the
	 * real visRgn 270 pixels off when the empty box stayed at 0,0, and everything below the picture was clipped away.
	 */
	/** OffsetRgn of a region Handle (the Window Manager's DragGrayRgn leaves theRgn where it was dropped). */
	void offsetRegion(int handle,int dh,int dv) throws M68kCpuException {offsetRegionHandle(handle,dh,dv);}
	private void offsetRegionHandle(int handle,int dh,int dv) throws M68kCpuException {
		if(dh==0&&dv==0)return;QdRegion shape=readRegion(handle);int data=memory.readLong(handle);
		if(!shape.isEmpty()||data==0){writeRegion(handle,shape.offset(dh,dv));return;}
		regionCache.remove(handle);memory.writeWord(data,10);offsetRectAt(data+2,dh,dv);
	}

	private boolean convertPoint(M68kCpu cpu,boolean localToGlobal) throws M68kCpuException {
		int pointer=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(pointer==0)return true;
		PortState state=state();int multiplier=localToGlobal?-1:1;
		writePoint(pointer,(short)memory.readWord(pointer)+multiplier*state.originV,
			(short)memory.readWord(pointer+2)+multiplier*state.originH);return true;
	}

	private boolean clipRect(M68kCpu cpu) throws M68kCpuException {
		int rect=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&rect!=0){PortState state=state();state.clip=readRect(rect);
			writeRegionRect(memory.readLong(currentPort+CLIP_RGN),state.clip);recordPictureClip(state.clip);}return true;
	}

	private boolean setClip(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		// The port's clipRgn takes the region's SHAPE, not just its box; some programs clip to a frame ring
		// DiffRgn(portRect, board) and paints its texture through it - a box clip textured over the whole board.
		if(currentPort!=0&&region!=0){PortState state=state();QdRegion shape=readRegion(region);state.clip=readRegionRect(region);
			int clip=memory.readLong(currentPort+CLIP_RGN);if(clip!=0)writeRegion(clip,shape);}return true;
	}

	private boolean getClip(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&region!=0)writeRegionRect(region,currentClip());return true;
	}

	private boolean movePen(M68kCpu cpu,boolean relative) throws M68kCpuException {
		int v=(short)memory.readWord(cpu.a[7]),h=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;
		if(currentPort!=0){if(relative){v+=(short)memory.readWord(currentPort+PN_LOC);h+=(short)memory.readWord(currentPort+PN_LOC+2);}
			writePoint(currentPort+PN_LOC,v,h);if(openPolygon!=0)recordPolygonPoint(h,v);}return true;
	}

	private boolean lineTo(M68kCpu cpu,boolean relative) throws M68kCpuException {
		int oldV=currentPort==0?0:(short)memory.readWord(currentPort+PN_LOC);
		int oldH=currentPort==0?0:(short)memory.readWord(currentPort+PN_LOC+2);
		if(openPolygon!=0&&polygonPoints.isEmpty())recordPolygonPoint(oldH,oldV);
		movePen(cpu,relative);if(currentPort!=0){int newV=(short)memory.readWord(currentPort+PN_LOC);
			int newH=(short)memory.readWord(currentPort+PN_LOC+2);
			if(openPolygon==0){recordPictureLine(oldH,oldV,newH,newV);rasterLine(oldH,oldV,newH,newV);}
			recordOpenRegionLine(oldH,oldV,newH,newV);
			if(openPolygon==0)noteMenuBarLine(Math.min(oldV,newV));}
		return true;
	}

	/** A patterned line drawn over the menu bar in the Window Manager port: HyperCard's background-mode hatching ({@link MenuManager68k#menuBarPatternDrawn}). */
	private void noteMenuBarLine(int top) throws M68kCpuException {
		Toolbox68k tb=macApi.application();
		if(tb==null||!tb.hosted()||tb.windows==null||tb.menus==null||!tb.windows.isWMgrPort(currentPort)||top<0||top>=(short)memory.readWord(0xbaa))return;
		if(!isColorPort(currentPort)){long pattern=((long)memory.readLong(currentPort+PN_PAT)<<32)|(memory.readLong(currentPort+PN_PAT+4)&0xffffffffL);if(pattern==0||pattern==-1)return;} // a solid pen is not hatching
		tb.menus.menuBarPatternDrawn();
	}

	/** HidePen/ShowPen: the ROM's SUBQ/ADDQ.W #1,pnVis with no cap - pnVis may go above 0. Anthony's RecordPict does ShowPen;
	 *  OpenPicture (1, 0: drawn AND recorded) ... ClosePicture; HidePen; capped at 0 it left the card port at -1 and every
	 *  later drawing external (MakeBarXCMD, KillRect) drew nothing. */
	private boolean penVisibility(M68kCpu cpu,int amount) throws M68kCpuException {
		if(currentPort!=0)memory.writeWord(currentPort+PN_VIS,(short)memory.readWord(currentPort+PN_VIS)+amount);return true;
	}

	/**
	 * A colour port keeps pnPixPat and fillPixPat where a GrafPort keeps pnPat, so PenState.pnPat gets the pen PixPat's 1-bit
	 * pat1Data and SetPenState applies it as PenPat does - never the Handles themselves. iTunes takes the pen state in an
	 * offscreen GWorld and sets it in its window (then restores the window's own PixPat with PenPixPat): copied Handles left
	 * the window with the GWorld's PixPats, freed by DisposeGWorld and reused for other data ("unsupported PixPat type -1").
	 */
	private boolean getPenState(M68kCpu cpu) throws M68kCpuException {
		int destination=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(currentPort==0||destination==0)return true;
		if(!isColorPort(currentPort)||pokedPattern(currentPort+PN_PAT)){memory.copy(currentPort+PN_LOC,destination,18);return true;}
		memory.copy(currentPort+PN_LOC,destination,10);
		int handle=memory.readLong(currentPort+PN_PAT),data=handle!=0&&macApi.isHandle(handle)?memory.readLong(handle):0;
		if(data!=0)memory.copy(data+20,destination+10,8);else memory.fill(destination+10,8,0xff); // no PixPat: the pen draws black
		return true;
	}

	private boolean setPenState(M68kCpu cpu) throws M68kCpuException {
		int source=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(currentPort==0||source==0)return true;
		if(!isColorPort(currentPort)||pokedPattern(currentPort+PN_PAT)){memory.copy(source,currentPort+PN_LOC,18);return true;}
		memory.copy(source,currentPort+PN_LOC,10);
		storePattern(PN_PAT,source+10);return true;
	}

	private boolean getPen(M68kCpu cpu) throws M68kCpuException {
		int destination=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&destination!=0)memory.copy(currentPort+PN_LOC,destination,4);return true;
	}

	private boolean penSize(M68kCpu cpu) throws M68kCpuException {
		int height=(short)memory.readWord(cpu.a[7]),width=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;
		if(currentPort!=0){writePoint(currentPort+PN_SIZE,height,width);recordPicturePointState(0x07,width,height);}return true;
	}

	private boolean penNormal(M68kCpu cpu) throws M68kCpuException {
		if(currentPort!=0){writePoint(currentPort+PN_SIZE,1,1);memory.writeWord(currentPort+PN_MODE,8);
			if(isColorPort(currentPort)){int handle=memory.readLong(currentPort+PN_PAT),data=handle!=0&&macApi.isHandle(handle)&&macApi.handleSize(handle)>=28?memory.readLong(handle):0;
				if(data!=0&&memory.readWord(data)==0)setPatternPixPat(data,new byte[]{-1,-1,-1,-1,-1,-1,-1,-1});else memory.writeLong(currentPort+PN_PAT,0);} // PenPat(black): a pattern PixPat is reused as the ROM does
			else memory.fill(currentPort+PN_PAT,8,0xff);}return true;
	}

	private boolean setWord(M68kCpu cpu,int offset) throws M68kCpuException {
		int value=memory.readWord(cpu.a[7]);cpu.a[7]+=2;if(currentPort!=0){memory.writeWord(currentPort+offset,value);
			int opcode=offset==TX_FONT?0x03:offset==TX_MODE?0x05:offset==PN_MODE?0x08:offset==TX_SIZE?0x0d:-1;
			if(opcode>=0)recordPictureWordState(opcode,value);}return true;
	}

	private boolean setLong(M68kCpu cpu,int offset) throws M68kCpuException {
		int value=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(currentPort!=0){memory.writeLong(currentPort+offset,value);
			if(offset==FG_COLOR||offset==BK_COLOR)oldStyleColorRgb(offset,value);
			int opcode=offset==SP_EXTRA?0x06:offset==FG_COLOR?0x0e:offset==BK_COLOR?0x0f:-1;
			if(opcode>=0)recordPictureLongState(opcode,value);}return true;
	}

	/** ForeColor/BackColor (and a picture's FgColor/BkColor) in a colour port: rgbFgColor/rgbBkColor become the old colour's RGB. */
	private void oldStyleColorRgb(int offset,int value) throws M68kCpuException {
		if(!isColorPort(currentPort))return;int rgb=quickDrawColor(value),target=currentPort+(offset==FG_COLOR?36:42);
		writeRgb(target,(rgb>>>16&255)*257,(rgb>>>8&255)*257,(rgb&255)*257);PortState ps=ports.get(currentPort);if(ps!=null){if(offset==FG_COLOR){ps.fgIndex=-1;ps.fgValue=-1;}else{ps.bkIndex=-1;ps.bkValue=-1;}}
	}

	private boolean setTextFace(M68kCpu cpu) throws M68kCpuException {
		int face=memory.readWord(cpu.a[7]);cpu.a[7]+=2;if(currentPort!=0){memory.writeByte(currentPort+TX_FACE,face);
			recordPictureByteState(0x04,face);}return true;
	}

	private int characterWidth() throws M68kCpuException {
		int size=currentPort==0?12:(short)memory.readWord(currentPort+TX_SIZE);return Math.max(1,(Math.max(1,size)+1)/2);
	}

	private boolean drawChar(M68kCpu cpu) throws M68kCpuException {
		int character=memory.readWord(cpu.a[7])&0xff;cpu.a[7]+=2;rasterText(new int[]{character});return true;
	}

	private boolean drawString(M68kCpu cpu) throws M68kCpuException {
		int string=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(string==0)return true;int length=memory.readByte(string);
		rasterText(readTextBytes(string+1,length));return true;
	}

	private boolean drawText(M68kCpu cpu,boolean returnWidth) throws M68kCpuException {
		int count=(short)memory.readWord(cpu.a[7]),first=(short)memory.readWord(cpu.a[7]+2);
		int text=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int length=Math.max(0,count),width=length*characterWidth();
		if(returnWidth)memory.writeWord(cpu.a[7],width);else if(text!=0)rasterText(readTextBytes(text+Math.max(0,first),length));return true;
	}

	private boolean stringWidth(M68kCpu cpu) throws M68kCpuException {
		int string=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		memory.writeWord(cpu.a[7],(string==0?0:memory.readByte(string))*characterWidth());return true;
	}

	private boolean charWidth(M68kCpu cpu) throws M68kCpuException {cpu.a[7]+=2;memory.writeWord(cpu.a[7],characterWidth());return true;}

	private int[] readTextBytes(int pointer,int length) throws M68kCpuException {
		int[] result=new int[length];for(int i=0;i<length;i++)result[i]=memory.readByte(pointer+i);return result;
	}

	private void rasterText(int[] characters) throws M68kCpuException {
		if(currentPort==0){return;}int h=(short)memory.readWord(currentPort+PN_LOC+2),v=(short)memory.readWord(currentPort+PN_LOC);
		if(Mac68kApi.TRACE){StringBuilder b=new StringBuilder();for(int c:characters)b.append((char)(c&0xff));System.err.println("[68K] picture text at "+h+","+v+" font "+memory.readWord(currentPort+TX_FONT)+" size "+memory.readWord(currentPort+TX_SIZE)+" face "+memory.readByte(currentPort+TX_FACE)+" clip "+currentClip()+" \""+b+"\"");}
		Toolbox68k tb=macApi.application(); // hosted: the real fonts (picture playback text used to come out in the fallback face); drawBytes records too
		if(tb!=null&&tb.hosted()&&tb.quickDraw!=null){byte[] bytes=new byte[characters.length];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)characters[i];
			boolean was=tb.quickDraw.inPicture;tb.quickDraw.inPicture=true;try{tb.quickDraw.drawBytes(bytes);}finally{tb.quickDraw.inPicture=was;}return;}
		recordPictureText(h,v,characters);
		int width=characterWidth();boolean hidden=penHidden();for(int character:characters){if(!hidden)rasterCharacter(character,h,v,width);h+=width;}
		memory.writeWord(currentPort+PN_LOC+2,h);
	}

	private void rasterCharacter(int character,int h,int baseline,int advance) throws M68kCpuException {
		int size=Math.max(1,(short)memory.readWord(currentPort+TX_SIZE));int face=memory.readByte(currentPort+TX_FACE);
		int style=((face&1)!=0?Font.BOLD:0)|((face&2)!=0?Font.ITALIC:0);
		BufferedImage glyph=new BufferedImage(Math.max(1,advance),Math.max(2,size+4),BufferedImage.TYPE_BYTE_BINARY);
		Graphics2D graphics=glyph.createGraphics();graphics.setFont(new Font(Font.SANS_SERIF,style,size));
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		graphics.setColor(Color.WHITE);FontMetrics metrics=graphics.getFontMetrics();int ascent=metrics.getAscent();
		char display=character>=32&&character<127?(char)character:'?';graphics.drawString(String.valueOf(display),0,ascent);graphics.dispose();
		Bitmap bitmap=readCurrentBitmap();int top=baseline-ascent,mode=(short)memory.readWord(currentPort+TX_MODE);
		for(int y=0;y<glyph.getHeight();y++)for(int x=0;x<glyph.getWidth();x++)if((glyph.getRGB(x,y)&0xffffff)!=0) {
			int targetH=h+x,targetV=top+y;if(portAllows(targetH,targetV))
				bitmap.setInkRgb(targetH,targetV,transferBit(true,bitmap.getInkRgb(targetH,targetV),mode,bitmap.pixelSize));
		}
		if((face&4)!=0)for(int x=0;x<advance;x++)if(portAllows(h+x,baseline+1))
			bitmap.setInkRgb(h+x,baseline+1,transferBit(true,bitmap.getInkRgb(h+x,baseline+1),mode,bitmap.pixelSize));
	}

	private boolean getFontInfo(M68kCpu cpu) throws M68kCpuException {
		int info=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(info==0)return true;
		int size=currentPort==0?12:Math.max(1,(short)memory.readWord(currentPort+TX_SIZE));
		memory.writeWord(info,Math.max(1,size*3/4));memory.writeWord(info+2,Math.max(1,size/4));
		memory.writeWord(info+4,characterWidth());memory.writeWord(info+6,0);return true;
	}

	private boolean setPattern(M68kCpu cpu,int offset) throws M68kCpuException {
		int source=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(currentPort!=0&&source!=0){storePattern(offset,source);
			if(Mac68kApi.TRACE)System.err.println("[68K] pattern "+(offset==BK_PAT?"bk":offset==PN_PAT?"pn":"fill")+" "+String.format("%08x%08x",memory.readLong(source),memory.readLong(source+4))+" port $"+Integer.toHexString(currentPort));
			recordPicturePattern(offset==BK_PAT?0x02:offset==PN_PAT?0x09:0x0a,source);}return true;
	}

	/** Stores the 8-byte pattern at {@code source} as the current port's pen/background/fill pattern.
	 * A colour port keeps a PixPat Handle in that slot (pnPixPat/bkPixPat/fillPixPat), so the bytes go into a
	 * pattern-type PixPat: the existing one is reused when it is of type 0 (as PenPat does in the ROM), else a new one is made.
	 * Returns the Handle created, or 0 when none was. */
	private int storePattern(int offset,int source) throws M68kCpuException {
		if(!isColorPort(currentPort)){memory.copy(source,currentPort+offset,8);return 0;}
		if(offset==FILL_PAT)offset=COLOR_FILL_PIX_PAT; // a CGrafPort's fillPixPat: +40 is rgbFgColor.blue/rgbBkColor.red there
		int handle=memory.readLong(currentPort+offset);
		if(handle!=0&&macApi.isHandle(handle)&&macApi.handleSize(handle)>=28){int data=memory.readLong(handle);
			if(data!=0&&memory.readWord(data)==0){setPatternPixPat(data,memory.readBytes(source,8));return 0;}}
		handle=newPatternPixPat(memory.readBytes(source,8));memory.writeLong(currentPort+offset,handle);return handle;
	}
	/** Whether the pattern slot of the current colour port holds a poked classic Pattern rather than a PixPat Handle: a
	 *  program written for classic GrafPorts stores its 8 bytes straight into thePort->bkPat (Factory puts qd.black there for
	 *  its tutorial dialog, a classic port on a real Mac where a DLOG without 'dctb' gets one). The bytes are read as that
	 *  Pattern then instead of being followed as a Handle (the all-ones black pattern was read at $FFFFFFFF). */
	private boolean pokedPattern(int pattern) throws M68kCpuException {
		if(pattern!=currentPort+PN_PAT&&pattern!=currentPort+BK_PAT&&pattern!=currentPort+FILL_PAT)return false;
		int handle=memory.readLong(pattern);return handle!=0&&!macApi.isHandle(handle);
	}

	private boolean setPoint(M68kCpu cpu) throws M68kCpuException {
		int v=(short)memory.readWord(cpu.a[7]),h=(short)memory.readWord(cpu.a[7]+2),pointer=memory.readLong(cpu.a[7]+4);
		cpu.a[7]+=8;if(pointer!=0)writePoint(pointer,v,h);return true;
	}

	private boolean addSubPoint(M68kCpu cpu,boolean add) throws M68kCpuException {
		int destination=memory.readLong(cpu.a[7]),packed=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(destination!=0){int sign=add?1:-1;writePoint(destination,
			(short)memory.readWord(destination)+sign*(short)(packed>>>16),
			(short)memory.readWord(destination+2)+sign*(short)packed);}return true;
	}

	private boolean equalPoint(M68kCpu cpu) throws M68kCpuException {
		int second=memory.readLong(cpu.a[7]),first=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		writeBoolean(cpu.a[7],first==second);return true;
	}

	private boolean setRect(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],bottom=(short)memory.readWord(sp),right=(short)memory.readWord(sp+2);
		int top=(short)memory.readWord(sp+4),left=(short)memory.readWord(sp+6),pointer=memory.readLong(sp+8);
		cpu.a[7]+=12;if(pointer!=0)writeRect(pointer,top,left,bottom,right);return true;
	}

	private boolean offsetInsetRect(M68kCpu cpu,boolean inset) throws M68kCpuException {
		int sp=cpu.a[7],dv=(short)memory.readWord(sp),dh=(short)memory.readWord(sp+2),pointer=memory.readLong(sp+4);
		cpu.a[7]+=8;if(pointer==0)return true;
		Rect rect=readRect(pointer);
		if(inset)writeRect(pointer,rect.top+dv,rect.left+dh,rect.bottom-dv,rect.right-dh);
		else writeRect(pointer,rect.top+dv,rect.left+dh,rect.bottom+dv,rect.right+dh);return true;
	}

	private boolean intersectRect(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp),second=memory.readLong(sp+4),first=memory.readLong(sp+8);
		cpu.a[7]+=12;Rect a=readRect(first),b=readRect(second);
		if(Mac68kApi.TRACE)System.err.println("[68K] SectRect ("+a.top+","+a.left+","+a.bottom+","+a.right+") & ("+b.top+","+b.left+","+b.bottom+","+b.right+")");
		Rect result=new Rect(Math.max(a.top,b.top),Math.max(a.left,b.left),Math.min(a.bottom,b.bottom),Math.min(a.right,b.right));
		boolean intersects=!result.empty();if(!intersects)result=new Rect(0,0,0,0);writeRect(destination,result);writeBoolean(cpu.a[7],intersects);return true;
	}

	private boolean unionRect(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp),second=memory.readLong(sp+4),first=memory.readLong(sp+8);cpu.a[7]+=12;
		Rect a=readRect(first),b=readRect(second);writeRect(destination,new Rect(Math.min(a.top,b.top),Math.min(a.left,b.left),
			Math.max(a.bottom,b.bottom),Math.max(a.right,b.right)));return true;
	}

	private boolean pointsToRect(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],destination=memory.readLong(sp),second=memory.readLong(sp+4),first=memory.readLong(sp+8);cpu.a[7]+=12;
		int v1=(short)(first>>>16),h1=(short)first,v2=(short)(second>>>16),h2=(short)second;
		writeRect(destination,Math.min(v1,v2),Math.min(h1,h2),Math.max(v1,v2),Math.max(h1,h2));return true;
	}

	private boolean pointInRect(M68kCpu cpu) throws M68kCpuException {
		int rectPointer=memory.readLong(cpu.a[7]),point=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;Rect rect=readRect(rectPointer);
		int v=(short)(point>>>16),h=(short)point;writeBoolean(cpu.a[7],v>=rect.top&&v<rect.bottom&&h>=rect.left&&h<rect.right);return true;
	}

	private boolean equalRect(M68kCpu cpu) throws M68kCpuException {
		int second=memory.readLong(cpu.a[7]),first=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		writeBoolean(cpu.a[7],readRect(first).equals(readRect(second)));return true;
	}

	private boolean emptyRect(M68kCpu cpu) throws M68kCpuException {
		int pointer=memory.readLong(cpu.a[7]);cpu.a[7]+=4;writeBoolean(cpu.a[7],readRect(pointer).empty());return true;
	}

	private boolean newRegion(M68kCpu cpu) throws M68kCpuException {
		memory.writeLong(cpu.a[7],regionHandle(0,0,0,0));return true;
	}

	private boolean disposeRegion(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(region!=0)macApi.disposeHandle(region);return true;
	}

	/** OpenRgn: like the ROM's it hides the pen (HidePen) - the lines and frames that define the region are not drawn (Key Caps frames its key shapes this way). */
	private boolean openRegion() throws M68kCpuException {
		regionOpen=true;openRegionPort=currentPort;openRegionLines.clear();openRegionShape=QdRegion.empty();
		if(currentPort!=0)memory.writeWord(currentPort+PN_VIS,(short)memory.readWord(currentPort+PN_VIS)-1);
		return true;
	}

	/**
	 * CloseRgn(dstRgn): the lines and framed shapes drawn since OpenRgn define the region the way the ROM's inversion
	 * points do - every line contributes its crossing with each pixel row and the region is the even-odd fill of them
	 * (a lasso path outlines its interior, nested framed rectangles leave a hole), framed shapes XOR their interiors.
	 */
	private boolean closeRegion(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		if(region!=0){QdRegion shape=openRegionShape==null?QdRegion.empty():openRegionShape;
			if(!openRegionLines.isEmpty())shape=QdRegion.combine(shape,linesRegion(openRegionLines),QdRegion.XOR);
			writeRegion(region,shape);}
		if(regionOpen&&openRegionPort!=0)memory.writeWord(openRegionPort+PN_VIS,(short)memory.readWord(openRegionPort+PN_VIS)+1); // ShowPen (uncapped, as the ROM)
		regionOpen=false;openRegionLines.clear();openRegionShape=null;return true;
	}
	/** The even-odd fill of a set of line segments: crossings at each pixel row's centre, paired left to right. */
	private static QdRegion linesRegion(List<int[]> lines) {
		int top=Integer.MAX_VALUE,bottom=Integer.MIN_VALUE;
		for(int[] l:lines){if(l[1]==l[3])continue;top=Math.min(top,Math.min(l[1],l[3]));bottom=Math.max(bottom,Math.max(l[1],l[3]));}
		if(top>=bottom)return QdRegion.empty();
		List<int[]> rows=new ArrayList<int[]>();int[] crossings=new int[16];
		for(int v=top;v<bottom;v++){int n=0;
			for(int[] l:lines){int v0=l[1],v1=l[3];if(v0==v1||v<Math.min(v0,v1)||v>=Math.max(v0,v1))continue;
				double x=l[0]+(double)(l[2]-l[0])*((v+0.5)-v0)/(v1-v0);
				if(n==crossings.length)crossings=Arrays.copyOf(crossings,n*2);crossings[n++]=(int)Math.ceil(x-0.5);}
			Arrays.sort(crossings,0,n);int[] xs=new int[n];int m=0;
			for(int i=0;i+1<n;i+=2){int l=crossings[i],r=crossings[i+1];if(l>=r)continue;if(m>0&&xs[m-1]==l)xs[m-1]=r;else{xs[m++]=l;xs[m++]=r;}}
			rows.add(Arrays.copyOf(xs,m));}
		return QdRegion.fromRows(top,rows);
	}

	/**
	 * BitMapToRegion(region, bitMap): OSErr - the black pixels of a bitmap become the region (a PixMap, or a CGrafPort's
	 * portBits whose rowBytes flag $C000 leads to the PixMap Handle, is read the same way). WDEFs of shaped windows draw
	 * a mask picture into a 1-bit GWorld and convert it into the structure region (Kingyo2's goldfish bowl).
	 */
	int bitMapToRegion(int region,int bits) throws M68kCpuException {
		if(region==0||bits==0)return -50; // paramErr
		Bitmap map=readBitmapRaw(bits);Rect bounds=map.bounds;
		if(map.base==0||bounds.empty()){writeRegion(region,QdRegion.empty());return 0;}
		List<int[]> rows=new ArrayList<int[]>();int[] xs=new int[8];
		for(int v=bounds.top;v<bounds.bottom;v++){int n=0;boolean in=false;
			for(int h=bounds.left;h<=bounds.right;h++){boolean inside=h<bounds.right&&map.get(h,v);
				if(inside!=in){if(n==xs.length)xs=Arrays.copyOf(xs,n*2);xs[n++]=h;in=inside;}}
			rows.add(Arrays.copyOf(xs,n));}
		QdRegion shape=QdRegion.fromRows(bounds.top,rows);
		if(shape.encode().length>32767)return -500; // rgnTooBigErr
		writeRegion(region,shape);
		if(Mac68kApi.TRACE)System.err.println("[68K] BitMapToRegion "+bounds+" "+map.pixelSize+"-bit -> "+java.util.Arrays.toString(shape.bounds())+" $"+Integer.toHexString(region));
		return 0;
	}

	private boolean copyRegion(M68kCpu cpu) throws M68kCpuException {
		int destination=memory.readLong(cpu.a[7]),source=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(Mac68kApi.TRACE)System.err.println("[68K] CopyRgn "+readRegionRect(source)+" -> $"+Integer.toHexString(destination));
		if(source!=0&&destination!=0)writeRegion(destination,readRegion(source));return true;
	}

	private boolean setEmptyRegion(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(region!=0)writeRegionRect(region,new Rect(0,0,0,0));return true;
	}

	private boolean setRectRegion(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],bottom=(short)memory.readWord(sp),right=(short)memory.readWord(sp+2);
		int top=(short)memory.readWord(sp+4),left=(short)memory.readWord(sp+6),region=memory.readLong(sp+8);cpu.a[7]+=12;
		if(region!=0)writeRegionRect(region,new Rect(top,left,bottom,right));return true;
	}

	private boolean rectRegion(M68kCpu cpu) throws M68kCpuException {
		int rect=memory.readLong(cpu.a[7]),region=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(rect!=0&&region!=0)writeRegionRect(region,readRect(rect));return true;
	}

	private boolean offsetInsetRegion(M68kCpu cpu,boolean inset) throws M68kCpuException {
		int sp=cpu.a[7],dv=(short)memory.readWord(sp),dh=(short)memory.readWord(sp+2),region=memory.readLong(sp+4);cpu.a[7]+=8;
		if(region==0)return true;if(!inset){offsetRegionHandle(region,dh,dv);return true;}
		writeRegion(region,readRegion(region).inset(dh,dv));return true;
	}

	private boolean emptyRegion(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]);cpu.a[7]+=4;writeBoolean(cpu.a[7],readRegionRect(region).empty());return true;
	}

	private boolean equalRegion(M68kCpu cpu) throws M68kCpuException {
		int second=memory.readLong(cpu.a[7]),first=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		writeBoolean(cpu.a[7],readRegion(first).sameShape(readRegion(second)));return true;
	}

	private boolean combineRegion(M68kCpu cpu,int operation) throws M68kCpuException {
		int destination=memory.readLong(cpu.a[7]),second=memory.readLong(cpu.a[7]+4),first=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
		QdRegion result=QdRegion.combine(readRegion(first),readRegion(second),operation);
		if(Mac68kApi.TRACE)System.err.println("[68K] "+(operation==0?"SectRgn":operation==1?"UnionRgn":"DiffRgn")+" "+readRegionRect(first)+" , "+readRegionRect(second)+" -> "+java.util.Arrays.toString(result.bounds()));
		writeRegion(destination,result);return true;
	}

	private boolean pointInRegion(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]),point=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		int v=(short)(point>>>16),h=(short)point;writeBoolean(cpu.a[7],readRegion(region).contains(h,v));return true;
	}

	private boolean rectInRegion(M68kCpu cpu) throws M68kCpuException {
		int region=memory.readLong(cpu.a[7]),rect=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		boolean inside=!QdRegion.combine(regionOf(readRect(rect)),readRegion(region),QdRegion.SECT).isEmpty();
		if(Mac68kApi.TRACE)System.err.println("[68K] RectInRgn "+readRect(rect)+" in "+readRegionRect(region)+" -> "+inside);
		writeBoolean(cpu.a[7],inside);return true;
	}

	/**
	 * $AB29, an undocumented System 7.5 QuickDraw test QuickTime's video drawing uses to decide whether a frame needs
	 * region clipping: pascal Boolean (RgnHandle rgn, const Rect *r, short selector). Selector 1 answers TRUE when clipping
	 * to the region would cut the rectangle, i.e. the rectangle does not lie entirely inside the region; on FALSE QuickTime
	 * clips to a plain rectangle (or not at all) and its codecs take their fast, mask-less paths. Other selectors are treated
	 * the same way.
	 */
	private boolean rectRegionTest(M68kCpu cpu) throws M68kCpuException {
		int selector=(short)memory.readWord(cpu.a[7]),rect=memory.readLong(cpu.a[7]+2),region=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
		Rect r=readRect(rect);boolean inside=region!=0&&QdRegion.combine(regionOf(r),readRegion(region),QdRegion.DIFF).isEmpty()&&!r.empty();
		if(Mac68kApi.TRACE)System.err.println("[68K] $AB29 selector "+selector+": "+r+" within "+readRegionRect(region)+" -> "+inside+" (clipping needed: "+!inside+")");
		writeBoolean(cpu.a[7],!inside);return true;
	}

	/**
	 * $AB27, the undocumented 32-Bit QuickDraw pixel transfer QuickTime's 'raw ' codec draws through (the only known
	 * caller; the signature was read off its calling sequences): pascal long (PixMapPtr src, long 0, PixMapPtr dst,
	 * const Rect *srcRect, long 0, const Rect *dstRect, short mode, long 0, RgnHandle rgn1, RgnHandle rgn2, RgnHandle rgn3,
	 * short 0). It is CopyBits between two PixMap records - the codec's source PixMap over its decoded rows and the copy
	 * QuickTime made of the destination port's PixMap - clipped to all three regions (the port's clip and visible regions
	 * and the movie's mask, all in destination coordinates); the zero arguments are presumably the mask PixMap and its
	 * rectangle of a CopyDeepMask-style transfer, never used by the codec.
	 */
	private boolean pixMapBlit(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7];
		int rgn3=memory.readLong(sp+2),rgn2=memory.readLong(sp+6),rgn1=memory.readLong(sp+10),mode=(short)memory.readWord(sp+18);
		int dstRect=memory.readLong(sp+20),srcRect=memory.readLong(sp+28),dst=memory.readLong(sp+32),src=memory.readLong(sp+40);
		cpu.a[7]+=44;
		if(Mac68kApi.TRACE)System.err.println(String.format("[68K] $AB27 blit src %08X %s -> dst %08X %s mode %d regions %s %s %s",src,src==0?null:readRect(src+6),dst,dst==0?null:readRect(dst+6),mode,rgn1==0?null:readRegionRect(rgn1),rgn2==0?null:readRegionRect(rgn2),rgn3==0?null:readRegionRect(rgn3)));
		if(src==0||dst==0||srcRect==0||dstRect==0){memory.writeLong(cpu.a[7],-50);return true;}
		// The three regions become CopyBits' one mask region (a temporary Handle when they clip anything).
		QdRegion clip=null;
		for(int rgn:new int[]{rgn1,rgn2,rgn3}){if(rgn==0)continue;QdRegion r=readRegion(rgn);clip=clip==null?r:QdRegion.combine(clip,r,QdRegion.SECT);}
		int mask=0;
		if(clip!=null){Rect d=readRect(dstRect);if(!QdRegion.combine(regionOf(d),clip,QdRegion.DIFF).isEmpty()){mask=macApi.createHandle(new byte[10]);writeRegion(mask,clip);}}
		int frame=cpu.a[7]-22;
		memory.writeLong(frame+18,src);memory.writeLong(frame+14,dst);memory.writeLong(frame+10,srcRect);memory.writeLong(frame+6,dstRect);memory.writeWord(frame+4,mode);memory.writeLong(frame,mask);
		cpu.a[7]=frame;
		try{copyBits(cpu);}finally{cpu.a[7]=frame+22;if(mask!=0)macApi.disposeHandleAndFree(mask);}
		memory.writeLong(cpu.a[7],0);return true;
	}

	private Rect readRegionRect(int handle) throws M68kCpuException {
		if(handle==0)return new Rect(0,0,0,0);int data=memory.readLong(handle);
		return data==0?new Rect(0,0,0,0):readRect(data+2);
	}

	private void writeRegionRect(int handle,Rect rect) throws M68kCpuException {
		if(handle==0)return;int data=memory.readLong(handle);if(data==0)return;
		regionCache.remove(handle);
		memory.writeWord(data,10);writeRect(data+2,rect.empty()?new Rect(0,0,0,0):rect);
	}

	/** The full shape of a region Handle (see {@link QdRegion}); empty for NIL. */
	private QdRegion readRegion(int handle) throws M68kCpuException {
		if(handle==0)return QdRegion.empty();int data=memory.readLong(handle);
		return data==0?QdRegion.empty():QdRegion.decode(memory,data);
	}

	/** BeginUpdate: the port's visRgn becomes its intersection with the (port-local) update rectangle; returns the old shape for EndUpdate. */
	QdRegion clipVisRgn(int port,int top,int left,int bottom,int right) throws M68kCpuException {
		int vis=memory.readLong(port+24);if(vis==0)return null;
		QdRegion old=readRegion(vis);writeRegion(vis,QdRegion.combine(old,QdRegion.rect(top,left,bottom,right),0));return old;
	}
	/** EndUpdate: the visRgn BeginUpdate narrowed goes back to its shape. */
	void restoreVisRgn(int port,QdRegion saved) throws M68kCpuException {int vis=memory.readLong(port+24);if(vis!=0&&saved!=null)writeRegion(vis,saved);}

	/** Stores a region in its Handle in the ROM format, growing the Handle for scan-line data. */
	/** Stores a region shape into a region Handle (the Window Manager moving a WDEF's regions). */
	void writeRegionShape(int handle,QdRegion region) throws M68kCpuException {writeRegion(handle,region);}
	private void writeRegion(int handle,QdRegion region) throws M68kCpuException {
		if(handle==0)return;byte[] bytes=region.encode();
		regionCache.remove(handle);
		int data=memory.readLong(handle);
		if(data==0||macApi.handleSize(handle)<bytes.length){macApi.resizeHandle(handle,bytes.length);data=memory.readLong(handle);}
		memory.load(data,bytes);
	}

	/** Stores {@code region} in a region Handle (TEGetHiliteRgn and other managers building shapes). */
	void setRegion(int handle,QdRegion region) throws M68kCpuException {writeRegion(handle,region);}
	private static Rect rectOf(int[] b){return new Rect(b[0],b[1],b[2],b[3]);}
	private static QdRegion regionOf(Rect r){return QdRegion.rect(r.top,r.left,r.bottom,r.right);}

	/** Decoded non-rectangular regions by Handle, validated against the record's data pointer, size and box. */
	private final java.util.LinkedHashMap<Integer,Object[]> regionCache=new java.util.LinkedHashMap<Integer,Object[]>(16,0.75f,true){
		@Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer,Object[]> eldest){return size()>32;}};

	/** Whether (h,v) lies inside the region; rectangular regions (rgnSize 10) are covered by the box tests. */
	private boolean regionAllows(int handle,int h,int v) throws M68kCpuException {
		QdRegion shape=regionShape(handle);return shape==null||shape.contains(h,v);
	}

	/** True when the current port's clip or visible region is not a plain rectangle (per-pixel tests needed). */
	private boolean clipHasShape() throws M68kCpuException {
		if(currentPort==0)return false;
		for(int field:new int[]{CLIP_RGN,VIS_RGN}){int handle=memory.readLong(currentPort+field);if(handle==0)continue;int data=memory.readLong(handle);if(data!=0&&memory.readWord(data)>10)return true;}
		return false;
	}

	/** Per-pixel clip/visRgn membership for the current port (shape only; callers test the boxes). */
	private boolean regionsAllow(int h,int v) throws M68kCpuException {
		return regionAllows(memory.readLong(currentPort+CLIP_RGN),h,v)&&regionAllows(memory.readLong(currentPort+VIS_RGN),h,v);
	}

	private Rect intersection(Rect a,Rect b) {
		Rect result=new Rect(Math.max(a.top,b.top),Math.max(a.left,b.left),Math.min(a.bottom,b.bottom),Math.min(a.right,b.right));
		return result.empty()?new Rect(0,0,0,0):result;
	}

	private Rect union(Rect a,Rect b) {
		if(a.empty())return b;if(b.empty())return a;
		return new Rect(Math.min(a.top,b.top),Math.min(a.left,b.left),Math.max(a.bottom,b.bottom),Math.max(a.right,b.right));
	}

	private Rect offset(Rect rect,int dh,int dv) {
		return new Rect(rect.top+dv,rect.left+dh,rect.bottom+dv,rect.right+dh);
	}

	private boolean boundsContains(Rect outer,Rect inner) {
		return inner.empty()||(!outer.empty()&&outer.top<=inner.top&&outer.left<=inner.left&&outer.bottom>=inner.bottom&&outer.right>=inner.right);
	}

	private boolean drawRectShape(M68kCpu cpu,int bytes,int rectOffset,int verb) throws M68kCpuException {
		int pattern=verb==4?memory.readLong(cpu.a[7]):0;
		int pointer=memory.readLong(cpu.a[7]+rectOffset);cpu.a[7]+=bytes;
		if(pointer!=0){Rect bounds=readRect(pointer);recordPictureShape(0x30+verb,bounds,0,0,pattern,verb);
			rasterRect(bounds,verb,pattern);recordOpenRegion(bounds);}return true;
	}

	private boolean drawOvalShape(M68kCpu cpu,int bytes,int rectOffset,int verb) throws M68kCpuException {
		int pattern=verb==4?memory.readLong(cpu.a[7]):0;
		int pointer=memory.readLong(cpu.a[7]+rectOffset);cpu.a[7]+=bytes;
		if(pointer!=0){Rect bounds=readRect(pointer);recordPictureShape(0x50+verb,bounds,0,0,pattern,verb);
			rasterOval(bounds,verb,pattern);if(regionOpen)recordOpenRegion(ovalRegion(bounds));}return true;
	}

	private boolean drawRoundRectShape(M68kCpu cpu,int bytes,int rectOffset,int verb) throws M68kCpuException {
		int pattern=verb==4?memory.readLong(cpu.a[7]):0;
		int ovalHeight=(short)memory.readWord(cpu.a[7]+(verb==4?4:0));
		int ovalWidth=(short)memory.readWord(cpu.a[7]+(verb==4?6:2));
		int pointer=memory.readLong(cpu.a[7]+rectOffset);cpu.a[7]+=bytes;
		if(pointer!=0){Rect bounds=readRect(pointer);recordPictureOvalSize(ovalWidth,ovalHeight);
			recordPictureShape(0x40+verb,bounds,0,0,pattern,verb);
			rasterRoundRect(bounds,ovalWidth,ovalHeight,verb,pattern);if(regionOpen)recordOpenRegion(roundRectRegion(bounds,ovalWidth,ovalHeight));}return true;
	}

	private boolean drawArcShape(M68kCpu cpu,int bytes,int rectOffset,int verb) throws M68kCpuException {
		int pattern=verb==4?memory.readLong(cpu.a[7]):0;
		int arcAngle=(short)memory.readWord(cpu.a[7]+(verb==4?4:0));
		int startAngle=(short)memory.readWord(cpu.a[7]+(verb==4?6:2));
		int pointer=memory.readLong(cpu.a[7]+rectOffset);cpu.a[7]+=bytes;
		if(pointer!=0){Rect bounds=readRect(pointer);recordPictureShape(0x60+verb,bounds,startAngle,arcAngle,pattern,verb);
			rasterArc(bounds,startAngle,arcAngle,verb,pattern);
			if(regionOpen&&arcAngle!=0)recordOpenRegion(arcRegion(bounds,startAngle,arcAngle));}return true;
	}

	private boolean drawRegionShape(M68kCpu cpu,int bytes,int regionOffset,int verb) throws M68kCpuException {
		int pattern=verb==4?memory.readLong(cpu.a[7]):0;
		int region=memory.readLong(cpu.a[7]+regionOffset);cpu.a[7]+=bytes;
		if(region==0)return true;
		QdRegion shape=readRegion(region);Rect bounds=rectOf(shape.bounds());recordPictureRegion(0x80+verb,region,pattern,verb);
		if(Mac68kApi.TRACE)System.err.println("[68K] region verb "+verb+" bounds "+bounds+" rects "+shape.rects().size()+" clip "+currentClip()+" drawable "+java.util.Arrays.toString(currentDrawableRect()));
		rasterRegion(shape,verb,pattern);recordOpenRegion(shape);return true;
	}
	/** Paints a region shape with a verb (0 frame ... 4 fill): rectangles go the rectangle way, FrameRgn paints the outline
	 *  (the region minus its inset by the pen size) with the pen. */
	private void rasterRegion(QdRegion shape,int verb,int pattern) throws M68kCpuException {
		if(penHidden())return;
		if(shape.isRect()){rasterRect(rectOf(shape.bounds()),verb,pattern);return;}
		List<int[]> rects;int rectVerb=verb;
		if(verb==0){
			if(currentPort==0||(short)memory.readWord(currentPort+PN_VIS)<0)return;
			int height=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE)),width=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE+2));
			rects=QdRegion.combine(shape,shape.inset(width,height),QdRegion.DIFF).rects();rectVerb=1;
		}else rects=shape.rects();
		// one drawing operation: a highlight (HiliteMode's bit cleared) covers every rectangle of the region, not only the first
		// one - Excel 2.2 inverts its selection outline (8 rectangles) in highlight mode, and the rest came out plainly inverted
		int hiliteMode=memory.readByte(HILITE_MODE);boolean drew=false;
		for(int[] r:rects){
			if((hiliteMode&0x80)==0)memory.writeByte(HILITE_MODE,hiliteMode);
			rasterRect(rectOf(r),rectVerb,pattern);
			if((hiliteMode&0x80)==0&&(memory.readByte(HILITE_MODE)&0x80)!=0)drew=true;
		}
		if((hiliteMode&0x80)==0)memory.writeByte(HILITE_MODE,drew?hiliteMode|0x80:hiliteMode);
	}
	/** A picture's region, scaled like its rectangles when the picture is drawn into a frame of another size. */
	private QdRegion mapRegion(QdRegion shape,Rect frame,Rect destination){
		if(frame.right-frame.left==destination.right-destination.left&&frame.bottom-frame.top==destination.bottom-destination.top)return shape.offset(destination.left-frame.left,destination.top-frame.top);
		QdRegion result=QdRegion.empty();
		for(int[] r:shape.rects()){Rect m=mapRect(rectOf(r),frame,destination);if(!m.empty())result=QdRegion.combine(result,QdRegion.rect(m.top,m.left,m.bottom,m.right),QdRegion.UNION);}
		return result;
	}

	private boolean openPolygon(M68kCpu cpu) throws M68kCpuException {
		byte[] initial=new byte[10];putWord(initial,0,10);openPolygon=macApi.createHandle(initial);
		polygonPoints.clear();memory.writeLong(cpu.a[7],openPolygon);return true;
	}

	private boolean closePolygon() throws M68kCpuException {
		if(openPolygon==0)return true;int count=polygonPoints.size(),size=10+count*4;byte[] bytes=new byte[size];
		putWord(bytes,0,size);Rect bounds=polygonBounds(polygonPoints);
		putWord(bytes,2,bounds.top);putWord(bytes,4,bounds.left);putWord(bytes,6,bounds.bottom);putWord(bytes,8,bounds.right);
		for(int i=0;i<count;i++){QdPoint point=polygonPoints.get(i);putWord(bytes,10+i*4,point.v);putWord(bytes,12+i*4,point.h);}
		macApi.loadHandle(openPolygon,bytes);openPolygon=0;polygonPoints.clear();return true;
	}

	private void recordPolygonPoint(int h,int v) {
		if(!polygonPoints.isEmpty()){QdPoint last=polygonPoints.get(polygonPoints.size()-1);if(last.h==h&&last.v==v)return;}
		polygonPoints.add(new QdPoint(h,v));
	}

	private boolean killPolygon(M68kCpu cpu) throws M68kCpuException {
		int polygon=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(polygon!=0)macApi.disposeHandle(polygon);
		if(openPolygon==polygon){openPolygon=0;polygonPoints.clear();}return true;
	}

	private boolean offsetPolygon(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],dv=(short)memory.readWord(sp),dh=(short)memory.readWord(sp+2),polygon=memory.readLong(sp+4);cpu.a[7]+=8;
		if(polygon==0)return true;int data=memory.readLong(polygon),size=memory.readWord(data),count=Math.max(0,(size-10)/4);
		offsetRectAt(data+2,dh,dv);for(int i=0;i<count;i++){int point=data+10+i*4;
			writePoint(point,(short)memory.readWord(point)+dv,(short)memory.readWord(point+2)+dh);}return true;
	}

	private boolean drawPolygon(M68kCpu cpu,int bytes,int polygonOffset,int verb) throws M68kCpuException {
		int pattern=verb==4?memory.readLong(cpu.a[7]):0;
		int polygon=memory.readLong(cpu.a[7]+polygonOffset);cpu.a[7]+=bytes;if(polygon==0)return true;
		List<QdPoint> points=readPolygon(polygon);if(points.size()<2)return true;
		recordPicturePolygon(0x70+verb,polygon,pattern,verb);rasterPolygon(points,verb,pattern);
		if(regionOpen)recordOpenRegion(polygonRegion(points));return true;
	}

	private List<QdPoint> readPolygon(int handle) throws M68kCpuException {
		List<QdPoint> points=new ArrayList<QdPoint>();int data=memory.readLong(handle);if(data==0)return points;
		int size=memory.readWord(data);if(size<10)return points;int count=(size-10)/4;
		if(count>8192)throw new M68kCpuException(data,"polygon has too many points");
		for(int i=0;i<count;i++){int point=data+10+i*4;points.add(new QdPoint((short)memory.readWord(point+2),(short)memory.readWord(point)));}
		return points;
	}

	private Rect polygonBounds(List<QdPoint> points) {
		if(points.isEmpty())return new Rect(0,0,0,0);int top=points.get(0).v,left=points.get(0).h,bottom=top,right=left;
		for(QdPoint point:points){top=Math.min(top,point.v);left=Math.min(left,point.h);bottom=Math.max(bottom,point.v);right=Math.max(right,point.h);}
		return new Rect(top,left,bottom,right);
	}

	/** A framed shape drawn while a region is open in the current port: its interior goes into the definition (XOR). */
	private void recordOpenRegion(QdRegion shape) {
		if(regionOpen&&currentPort==openRegionPort&&!shape.isEmpty())openRegionShape=QdRegion.combine(openRegionShape==null?QdRegion.empty():openRegionShape,shape,QdRegion.XOR);
	}
	private void recordOpenRegion(Rect bounds) {if(!bounds.empty())recordOpenRegion(regionOf(bounds));}
	private void recordOpenRegionLine(int h0,int v0,int h1,int v1) {
		if(regionOpen&&currentPort==openRegionPort)openRegionLines.add(new int[]{h0,v0,h1,v1});
	}
	/** The pixels of {@code bounds} passing {@code test} as a region (ovals, round rectangles, arcs, polygons). */
	private QdRegion regionOfShape(Rect bounds,PixelTest test) {
		if(bounds.empty())return QdRegion.empty();List<int[]> rows=new ArrayList<int[]>();int[] xs=new int[8];
		for(int v=bounds.top;v<bounds.bottom;v++){int n=0;boolean in=false;
			for(int h=bounds.left;h<=bounds.right;h++){boolean inside=h<bounds.right&&test.inside(h,v);
				if(inside!=in){if(n==xs.length)xs=Arrays.copyOf(xs,n*2);xs[n++]=h;in=inside;}}
			rows.add(Arrays.copyOf(xs,n));}
		return QdRegion.fromRows(bounds.top,rows);
	}
	private interface PixelTest {boolean inside(int h,int v);}
	private QdRegion ovalRegion(final Rect rect) {
		final double radiusX=(rect.right-rect.left)/2.0,radiusY=(rect.bottom-rect.top)/2.0,centerX=rect.left+radiusX,centerY=rect.top+radiusY;
		return regionOfShape(rect,new PixelTest(){@Override public boolean inside(int h,int v){double dx=(h+0.5-centerX)/radiusX,dy=(v+0.5-centerY)/radiusY;return dx*dx+dy*dy<=1.0;}});
	}
	private QdRegion roundRectRegion(final Rect rect,final int ovalWidth,final int ovalHeight) {
		return regionOfShape(rect,new PixelTest(){@Override public boolean inside(int h,int v){return insideRoundRect(h,v,rect,ovalWidth,ovalHeight);}});
	}
	private QdRegion arcRegion(final Rect rect,final int startAngle,final int arcAngle) {
		final double radiusX=(rect.right-rect.left)/2.0,radiusY=(rect.bottom-rect.top)/2.0,centerX=rect.left+radiusX,centerY=rect.top+radiusY;
		return regionOfShape(rect,new PixelTest(){@Override public boolean inside(int h,int v){double dx=(h+0.5-centerX)/radiusX,dy=(v+0.5-centerY)/radiusY;if(dx*dx+dy*dy>1.0)return false;
			double angle=Math.toDegrees(Math.atan2(dx,-dy));if(angle<0)angle+=360.0;return angleInSweep(angle,startAngle,arcAngle);}});
	}
	private QdRegion polygonRegion(final List<QdPoint> points) {
		return regionOfShape(polygonBounds(points),new PixelTest(){@Override public boolean inside(int h,int v){return pointInPolygon(h+0.5,v+0.5,points);}});
	}

	/** Colour QuickDraw matches colours through theGDevice's inverse table, not the destination PixMap's own table. When a
	 *  program has switched to a device of its own (SetGDevice; an icon editor may draw into an 8-bit PixMap
	 *  through a device carrying the icon's colours at the icon's pixel values), an indexed destination matches through
	 *  that device's table. With the main device current the destination's table stays in use: programs drawing offscreen
	 *  buffers with their own tables rely on that here (a real GWorld has a device of its own) - except for a GWorld made with
	 *  noNewDevice for the main device, which has none: Glimmer draws its messages into such a world (NIL cTable, so the
	 *  standard 'clut' 8 as its table) and copies the bytes to the screen itself, so they must be the screen palette's indexes. */
	private void matchThroughCurrentDevice(Bitmap destination) throws M68kCpuException {
		if(destination.direct||currentGDevice==0||currentGDevice==mainGDevice&&destination.sharedDevice!=mainGDevice)return;
		int device=memory.readLong(currentGDevice);if(device==0)return;int pixMap=memory.readLong(device+22);if(pixMap==0)return;
		int map=memory.readLong(pixMap);if(map==0)return;int table=memory.readLong(map+42);
		int depth=memory.readWord(map+32);
		if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder();int td=table==0?0:memory.readLong(table);if(td!=0){int n=(short)memory.readWord(td+6);sb.append(" size ").append(n).append(" flags ").append(Integer.toHexString(memory.readWord(td+4)));for(int i=0;i<=Math.min(n,5);i++)sb.append(String.format(" [%d:%04x %04x,%04x,%04x]",i,memory.readWord(td+8+i*8),memory.readWord(td+10+i*8),memory.readWord(td+12+i*8),memory.readWord(td+14+i*8)));}
			System.err.println("[68K] matching through device $"+Integer.toHexString(currentGDevice)+" table $"+Integer.toHexString(table)+" depth "+depth+" (destination table $"+Integer.toHexString(destination.colorTable)+" depth "+destination.pixelSize+")"+sb);}
		if(table==0||table==destination.colorTable)return;
		if(depth!=destination.pixelSize||depth>8)return;
		destination.matchPalette=seededPalette(table,depth,readColorTable(table,depth));destination.throughDevice=true;
	}

	/**
	 * Colour QuickDraw colorizes a 1-bit source in a colour port: 1 bits take the foreground colour and 0 bits the background
	 * colour before the transfer and the colour matching (IM VI 17-22). A bitmap editor may copy an
	 * ICON into a 1-bit PixMap under a GDevice of its own whose table lists black at 0 and white at 1, with the foreground set
	 * to the colour of index 1 (white) and the background to that of index 0 (black) so that the bits land unchanged: matched
	 * without colorizing, black went to 0 and the whole editor showed inverted. Black on white (the default) changes nothing,
	 * so sources keep their own tables then; other bitmaps are returned as they are.
	 */
	private Bitmap colorized(Bitmap source) throws M68kCpuException {
		if(source.pixelSize!=1||source.direct||source.getClass()!=Bitmap.class||currentPort==0||!isColorPort(currentPort))return source;
		int foreground=~portForegroundInk()&0xffffff,background=~portBackgroundInk()&0xffffff;
		if(foreground==0&&background==0xffffff)return source;
		return new Bitmap(source.base,source.rowBytes,source.bounds,1,false,new int[]{background,foreground});
	}
	/**
	 * The colours CopyBits colorizes a deeper source with: {foreground ink, background ink}, or null for black on white (or
	 * an index-coded port colour). Colour QuickDraw colorizes sources of every depth, not only bitmaps (Inside Macintosh:
	 * Imaging with QuickDraw, "Boolean transfer modes with color pixels": srcCopy/srcOr/srcBic give the source's black the
	 * foreground and its white the background colour); srcXor ignores the colours. MetaCard 2.5 colours its brush strokes so:
	 * a black-on-white stroke image copied in srcOr under a blue foreground; they painted black.
	 */
	private int[] colorizingInks(Bitmap source,int mode) throws M68kCpuException {
		int m=mode&~0x40;
		if(source.pixelSize==1||m>7||(m&3)==2||currentPort==0||!isColorPort(currentPort))return null;
		int foreground=portForegroundInk(),background=portBackgroundInk();
		if(((foreground|background)&INDEX_INK)!=0)return null;
		// a 'wctb'/'dctb' content colour is the Window Manager's erase colour, not the port's rgbBkColor (see
		// transparentBackgroundRgb): Ultimate Spell's battle dialog (dctb 134/135, purple) is the port while it srcCopies its
		// 32-bit scene buffers - colorized by the purple, the restored sprite rectangles and the message box went dark
		if(transparentBackgroundRgb()==0xffffff)background=0;
		if(foreground==0xffffff&&background==0)return null;
		return new int[]{foreground,background};
	}

	/** Charges the throttle (see Toolbox68k.throttle) for a blit of {@code area} at the bitmap's depth: one instruction equivalent per byte. */
	private static void chargeBlit(M68kCpu cpu,Rect area,Bitmap bitmap){
		cpu.charge(Math.max(64L,(long)(area.right-area.left)*(area.bottom-area.top)*Math.max(1,bitmap.pixelSize)/8));
	}
	/**
	 * CopyBits. A blit onto a window's pixels or the screen is a frame reaching the display (a double-buffered game's,
	 * QuickDraw 3D's back buffer): the host holds its refresh tick off while it runs and shows the result as soon as it
	 * is done (MacAppHost.frameReady), so frames are shown whole and when they are ready.
	 */
	private boolean copyBits(M68kCpu cpu) throws M68kCpuException {
		try{boolean result=copyBits0(cpu);SmoothCopy carry=pendingSmoothCopy;pendingSmoothCopy=null;if(carry!=null)carry.end();return result;}
		finally{SmoothCopy carry=pendingSmoothCopy;pendingSmoothCopy=null;if(carry!=null)carry.abandon();
			MacAppHost host=screenBlitHost;if(host!=null){screenBlitHost=null;host.blitEnd();}}
	}
	/** The host a CopyBits under way draws onto the screen of (null: none, or into an offscreen buffer). */
	private MacAppHost screenBlitHost;
	private void screenBlitBegins(Bitmap destination){
		Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted())return;
		if(!(destination instanceof ScreenBitmap)&&!memory.tracked(destination.base))return;
		screenBlitHost=tb.host();screenBlitHost.blitBegin();
	}
	private boolean copyBits0(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],maskRegion=memory.readLong(sp),mode=(short)memory.readWord(sp+4);
		int destinationRect=memory.readLong(sp+6),sourceRect=memory.readLong(sp+10);
		int destinationBits=memory.readLong(sp+14),sourceBits=memory.readLong(sp+18);cpu.a[7]+=22;
		if(sourceBits==0||destinationBits==0||sourceRect==0||destinationRect==0)return true;
		Bitmap source=colorized(readBitmap(sourceBits)),destination=readBitmap(destinationBits);
		Rect sourceArea=readRect(sourceRect),destinationArea=readRect(destinationRect);
		matchThroughCurrentDevice(destination);screenBlitBegins(destination);
		if(Mac68kApi.TRACE)System.err.println("[68K] CopyBits cur $"+Integer.toHexString(currentPort)+" bk "+Integer.toHexString(~portBackgroundInk()&0xffffff)+" src $"+Integer.toHexString(sourceBits)+" rowBytes="+source.rowBytes+" depth="+source.pixelSize+" bounds="+source.bounds.left+","+source.bounds.top+","+source.bounds.right+","+source.bounds.bottom+" base=$"+Integer.toHexString(source.base)+" rect="+sourceArea.left+","+sourceArea.top+","+sourceArea.right+","+sourceArea.bottom+" -> dst $"+Integer.toHexString(destinationBits)+" base=$"+Integer.toHexString(destination.base)+" bounds="+destination.bounds+" depth="+destination.pixelSize+" clip "+currentClip()+" rect="+destinationArea.left+","+destinationArea.top+","+destinationArea.right+","+destinationArea.bottom+" mode="+mode+" srcPalette="+(source.palette==null?"direct":describePalette(source.palette))+" dstPalette="+(destination.palette==null?"direct":describePalette(destination.palette))+" tables $"+Integer.toHexString(source.colorTable)+"/$"+Integer.toHexString(destination.colorTable)+" seeds "+tableSeed(source.colorTable)+"/"+tableSeed(destination.colorTable));
		int sourceWidth=sourceArea.right-sourceArea.left,sourceHeight=sourceArea.bottom-sourceArea.top;
		int destinationWidth=destinationArea.right-destinationArea.left,destinationHeight=destinationArea.bottom-destinationArea.top;
		if(sourceWidth<=0||sourceHeight<=0||destinationWidth<=0||destinationHeight<=0)return true;
		recordPictureBits(source,sourceArea,destinationArea,mode,maskRegion);
		Toolbox68k app=macApi.application();
		if(source instanceof ScreenBitmap&&!(destination instanceof ScreenBitmap)&&app!=null&&app.windows!=null)app.windows.screenRead();
		if(app!=null&&app.quickDraw!=null&&app.app!=null&&app.app.translator!=null){ // translations put into an offscreen world later are copied on the same way (VMTranslation)
			Rect copied=maskRegion==0?destinationArea:intersection(destinationArea,readRegionRect(maskRegion)); // a mask region: its bounds (ウルティメイト・スペル passes one per row)
			if(!copied.empty())app.quickDraw.noteCopy(sourceBits-PORT_BITS,new int[]{copied.top-destinationArea.top+sourceArea.top,copied.left-destinationArea.left+sourceArea.left,copied.bottom-destinationArea.top+sourceArea.top,copied.right-destinationArea.left+sourceArea.left},
				destinationBits-PORT_BITS,new int[]{copied.top,copied.left,copied.bottom,copied.right},mode);}
		// Pattern modes (patCopy 8 .. notPatBic 15) handed to CopyBits act as the matching source modes, as in the ROM
		// Some programs blit every frame with patCopy; normalized here so the row paths below recognize srcCopy.
		if((mode&~0x40)>=8&&(mode&~0x40)<=15)mode-=8;
		int[] colorize=colorizingInks(source,mode); // a deeper source under non-default colours: pixel by pixel, colorized
		// Same indexed depth and the same colour table: real QuickDraw copies the pixel values untouched, which is what
		// programs rely on when they animate the table afterwards.
		// A source table of palette entries (ctFlags bit 14) gives each pixel value its screen index, whatever the colours are now.
		int[] paletteMap=colorize==null?paletteIndexMap(source,destination):null;
		boolean byIndex=colorize==null&&(mode&~0x40)==0&&!source.direct&&!destination.direct&&(paletteMap!=null||source.pixelSize==destination.pixelSize&&source.pixelSize>1&&(sameColorTable(source,destination)||drawnThroughScreen(source,destination)));
		Rect mask=maskRegion==0?null:readRegionRect(maskRegion);
		Rect drawingArea=intersection(destinationArea,destination.bounds);
		if(mask!=null)drawingArea=intersection(drawingArea,mask);
		boolean clipped=currentPort!=0&&isCurrentBitmapArgument(destinationBits);
		if(clipped)drawingArea=intersection(drawingArea,currentClipBounds(destination));
		if(drawingArea.empty())return true;
		if(sourceWidth==destinationWidth&&sourceHeight==destinationHeight&&(long)sourceWidth*sourceHeight>16_777_216L){
			// An unscaled copy bigger than any bitmap (JHUG Boid XCMD: a boid flying at the viewer gets a sprite rect thousands of
			// pixels on a side): only the part that lands in the drawing area is read, the rest is clipped away as in the ROM
			int dx=sourceArea.left-destinationArea.left,dy=sourceArea.top-destinationArea.top;
			destinationArea=drawingArea;sourceArea=new Rect(drawingArea.top+dy,drawingArea.left+dx,drawingArea.bottom+dy,drawingArea.right+dx);
			sourceWidth=destinationWidth=drawingArea.right-drawingArea.left;sourceHeight=destinationHeight=drawingArea.bottom-drawingArea.top;
		}
		long pixels=(long)sourceWidth*sourceHeight;
		if(pixels>16_777_216L)throw new M68kCpuException(sourceRect,"CopyBits source rectangle is too large");
		if((long)(drawingArea.right-drawingArea.left)*(drawingArea.bottom-drawingArea.top)>16_777_216L)
			throw new M68kCpuException(destinationRect,"CopyBits destination rectangle is too large");
		chargeBlit(cpu,drawingArea,destination); // a real 68040 spent about an instruction per byte moved; the speed throttle paces the program by it
		boolean shaped=clipped&&clipHasShape();QdRegion maskShape=maskRegion==0?null:readRegion(maskRegion);if(maskShape!=null&&maskShape.isRect())maskShape=null;
		pendingSmoothCopy=smoothCopyBegin(source,sourceArea,destination,destinationArea,drawingArea,mode,shaped,maskShape,null); // hi-res blocks the copy carries (SmoothZoom)
		// Unscaled, unshaped srcCopy moves whole rows: raw bytes for the same depth and colour table (or direct to direct),
		// through a 256-entry index map between indexed depths, or through the source colours into a direct PixMap.
		boolean dither=mode==64&&!destination.direct&&destination.pixelSize<=8&&(source.direct||source.pixelSize>destination.pixelSize); // see ditherCopy below
		boolean srcCopy=colorize==null&&(mode&~0x40)==0&&!dither,rows=!NO_ROW_COPY&&srcCopy&&(!source.direct&&(!destination.direct||destination.pixelSize>=16)||source.direct&&destination.direct&&source.pixelSize==destination.pixelSize);
		if(rows&&!shaped&&maskShape==null&&source.base!=0&&destination.base!=0&&sourceWidth==destinationWidth&&sourceHeight==destinationHeight
			&&sourceArea.left>=source.bounds.left&&sourceArea.right<=source.bounds.right&&sourceArea.top>=source.bounds.top&&sourceArea.bottom<=source.bounds.bottom
			&&!(source instanceof ScreenBitmap)&&!(destination instanceof ScreenBitmap)){
			int[] indexMap=null,sourceColors=null;
			if(!source.direct){int[] colors=source.palette!=null?source.palette:new int[]{0xffffff,0};
				if(destination.direct)sourceColors=colors;
				else if(paletteMap!=null)indexMap=source.pixelSize==destination.pixelSize&&identity(paletteMap)?null:paletteMap;
				else if(!byIndex&&!(source.pixelSize==destination.pixelSize&&sameColorTable(source,destination)))indexMap=indexMap(colors,destination,source.pixelSize==destination.pixelSize);}
			if(VERIFY_ROW_COPY){verifyRowCopy(source,sourceArea,destination,destinationArea,drawingArea,indexMap,sourceColors,mode,byIndex);return true;}
			copyRows(source,sourceArea,destination,destinationArea,drawingArea,indexMap,sourceColors);
			if(Mac68kApi.TRACE)System.err.println("[68K] CopyBits drawingArea "+drawingArea+" clipped="+clipped+" by rows"+(indexMap!=null?" (index map)":sourceColors!=null?" (to direct)":" (raw)"));
			return true;
		}
		// Unscaled srcCopy of a direct PixMap onto the screen (a double-buffered game's frame, QuickDraw 3D RAVE's back buffer):
		// row by row into each window's buffer and the device, instead of routing every pixel.
		if(srcCopy&&!NO_ROW_COPY&&destination instanceof ScreenBitmap&&!shaped&&maskShape==null&&source.direct&&(source.pixelSize==32||source.pixelSize==16)
			&&source.base!=0&&sourceWidth==destinationWidth&&sourceHeight==destinationHeight&&!(source instanceof ScreenBitmap)){
			((ScreenBitmap)destination).copyRgbRows(source,sourceArea,destinationArea,drawingArea);
			if(Mac68kApi.TRACE)System.err.println("[68K] CopyBits drawingArea "+drawingArea+" onto the screen by rows");
			return true;
		}
		// The source is snapshotted as RGB before writing: source and destination may overlap (scrolling within one PixMap).
		// The source is read by address arithmetic like the ROM does, not clipped to its bounds (see Bitmap.getIndexUnclipped).
		int[] snapshot=null;
		if(!byIndex){snapshot=snapshotScratch=ints(snapshotScratch,(int)pixels);
			for(int y=0;y<sourceHeight;y++)for(int x=0;x<sourceWidth;x++)
				snapshot[y*sourceWidth+x]=source.getInkRgbUnclipped(sourceArea.left+x,sourceArea.top+y);}
		// srcOr/srcXor/srcBic (and the not- forms) between indexed PixMaps work on the pixel values, the source matched into the
		// destination's table first: a dark grey ORed over a colour gives an entry near black, not a mix of the two colours.
		// PUGO_GT ORs its road over the 8-bit "人工大陸のシルエット" picture; on a Mac the shapes show black on the road.
		int booleanMode=mode&~0x40;int[] sourceIndexes=null;
		if(colorize==null&&booleanMode>=1&&booleanMode<=7&&booleanMode!=4&&!source.direct&&!destination.direct&&source.pixelSize>1&&destination.pixelSize>1
			&&!(source instanceof ScreenBitmap)&&!(destination instanceof ScreenBitmap)){
			boolean shared=source.pixelSize==destination.pixelSize&&(sameColorTable(source,destination)||drawnThroughScreen(source,destination));
			sourceIndexes=new int[(int)pixels];
			for(int y=0;y<sourceHeight;y++)for(int x=0;x<sourceWidth;x++)
				sourceIndexes[y*sourceWidth+x]=paletteMap!=null?paletteMap[source.getIndexUnclipped(sourceArea.left+x,sourceArea.top+y)]:shared?source.getIndexUnclipped(sourceArea.left+x,sourceArea.top+y):destination.nearestColor(~snapshot[y*sourceWidth+x]&0xffffff);
		}
		// A one-bit source in srcOr/srcXor/srcBic (and the not- forms) on a colour destination: a set bit gives the foreground
		// colour (srcOr), inverts (srcXor) or gives the background colour (srcBic) and a clear bit leaves the pixel (Inside
		// Macintosh: Imaging With QuickDraw, "Boolean transfer modes with color pixels"), as transferBit does for pictures.
		// ORing the colorized inks drew a white foreground as nothing: SoftWindows writes Windows' captions and selected icon
		// titles so, white glyphs in srcOr, and they were missing.
		boolean[] oneBitSource=null;
		if(colorize==null&&sourceIndexes==null&&source.pixelSize==1&&!source.direct&&destination.pixelSize>1&&booleanMode>=1&&booleanMode<=7&&booleanMode!=4){
			oneBitSource=new boolean[(int)pixels];
			for(int y=0;y<sourceHeight;y++)for(int x=0;x<sourceWidth;x++)oneBitSource[y*sourceWidth+x]=source.getIndexUnclipped(sourceArea.left+x,sourceArea.top+y)!=0;
		}
		// ditherCopy into fewer colours (a 1-bit or indexed destination from a deeper source): the source's colours with their
		// error diffused, as QuickDraw dithers; it was srcCopy here, a hard threshold at half luminance (Rinaldi's PictToClip "d=1")
		if(dither&&snapshot!=null&&colorize==null){
			ditherCopy(snapshot,sourceWidth,sourceHeight,destination,destinationArea,drawingArea,shaped,maskShape);
			return true;
		}
		int changed=0,skipped=0;
		for(int v=drawingArea.top;v<drawingArea.bottom;v++)for(int h=drawingArea.left;h<drawingArea.right;h++) {
			if(shaped&&!regionsAllow(h,v)){skipped++;continue;}if(maskShape!=null&&!maskShape.contains(h,v))continue;
			changed++;
			int sx=(int)(((long)(h-destinationArea.left)*sourceWidth)/destinationWidth);
			int sy=(int)(((long)(v-destinationArea.top)*sourceHeight)/destinationHeight);
			if(byIndex){int value=source.getIndexUnclipped(sourceArea.left+sx,sourceArea.top+sy);destination.setIndex(h,v,paletteMap!=null?paletteMap[value]:value);continue;}
			if(sourceIndexes!=null){int s=sourceIndexes[sy*sourceWidth+sx],d=destination.getIndex(h,v);if((booleanMode&4)!=0)s=~s;
				destination.setIndex(h,v,(booleanMode&3)==1?s|d:(booleanMode&3)==2?s^d:~s&d);continue;}
			if(oneBitSource!=null){destination.setInkRgb(h,v,transferBit(oneBitSource[sy*sourceWidth+sx],destination.getInkRgb(h,v),booleanMode,destination.pixelSize));continue;}
			int sourcePixel=snapshot[sy*sourceWidth+sx],destinationPixel=destination.getInkRgb(h,v);
			if(colorize!=null)sourcePixel=(sourcePixel&colorize[0]|~sourcePixel&colorize[1])&0xffffff;
			destination.setInkRgb(h,v,transferInk(sourcePixel,destinationPixel,mode,destination));
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] CopyBits drawingArea "+drawingArea+" clipped="+clipped+" shaped="+shaped+" changed="+changed+" skipped="+skipped);
		return true;
	}

	/**
	 * ditherCopy of a source snapshot (inks: inverted RGB, {@code sw} x {@code sh}) into {@code area} of an indexed or 1-bit
	 * destination: each pixel takes the nearest colour the destination has (black or white at one bit) and the difference goes on
	 * to the pixels right and below (Floyd-Steinberg: 7/16 right, 3/16 below left, 5/16 below, 1/16 below right).
	 */
	private void ditherCopy(int[] inks,int sw,int sh,Bitmap d,Rect destinationArea,Rect area,boolean shaped,QdRegion maskShape) throws M68kCpuException {
		int w=area.right-area.left,dw=destinationArea.right-destinationArea.left,dh=destinationArea.bottom-destinationArea.top;
		int[] table=d.matchPalette!=null?d.matchPalette:d.palette;
		float[][] here={new float[w+2],new float[w+2],new float[w+2]},below={new float[w+2],new float[w+2],new float[w+2]};
		for(int v=area.top;v<area.bottom;v++){
			int sy=(int)(((long)(v-destinationArea.top)*sh)/dh);
			for(int x=0;x<w;x++){
				int h=area.left+x,sx=(int)(((long)(h-destinationArea.left)*sw)/dw),rgb=~inks[sy*sw+sx]&0xffffff;
				float r=clamp((rgb>>>16&255)+here[0][x+1]),g=clamp((rgb>>>8&255)+here[1][x+1]),b=clamp((rgb&255)+here[2][x+1]);
				int chosen,index=-1;
				if(d.pixelSize==1||table==null)chosen=(r*30+g*59+b*11)/100<128?0:0xffffff;
				else{index=d.nearestColor(Math.round(r)<<16|Math.round(g)<<8|Math.round(b));chosen=index>=0&&index<table.length?table[index]&0xffffff:0;}
				if(!(shaped&&!regionsAllow(h,v))&&!(maskShape!=null&&!maskShape.contains(h,v))){
					if(index>=0)d.setIndex(h,v,index);else d.set(h,v,chosen==0);}
				float[] error={r-(chosen>>>16&255),g-(chosen>>>8&255),b-(chosen&255)};
				for(int c=0;c<3;c++){here[c][x+2]+=error[c]*7/16;below[c][x]+=error[c]*3/16;below[c][x+1]+=error[c]*5/16;below[c][x+2]+=error[c]/16;}
			}
			float[][] t=here;here=below;below=t;for(float[] row:below)java.util.Arrays.fill(row,0);
		}
	}
	private static float clamp(float value){return value<0?0:value>255?255:value;}

	/**
	 * The source index -> destination index map of a row copy between indexed PixMaps with different tables, or null when
	 * every colour maps back to its own index (plain byte copy). Recent maps are kept per destination match table (a seeded
	 * snapshot, so identity suffices) and source colours (compared by value): a game blitting between several GWorlds
	 * (for example, three tables and many CopyBits per frame) would otherwise redo 256 colour matches per call, and the direct-mapped
	 * match cache thrashes where the colours of the different tables collide.
	 */
	private int[] indexMap(int[] colors,Bitmap destination,boolean sameDepth) {
		int[] match=destination.matchPalette!=null?destination.matchPalette:destination.palette;
		for(int[][] entry:indexMapCache)if(entry!=null&&entry[0]==match&&entry[3][0]==(sameDepth?1:0)&&java.util.Arrays.equals(entry[1],colors))return entry[2];
		int[] indexMap=new int[colors.length];boolean identity=sameDepth;
		for(int i=0;i<colors.length;i++){indexMap[i]=destination.nearestColor(colors[i]);if(indexMap[i]!=i)identity=false;}
		if(identity)indexMap=null;
		indexMapCache[indexMapNext]=new int[][]{match,colors.clone(),indexMap,{sameDepth?1:0}};indexMapNext=(indexMapNext+1)%indexMapCache.length;
		return indexMap;
	}
	private final int[][][] indexMapCache=new int[8][][];private int indexMapNext;

	/** -Dhz68k.norowcopy=true: debug aid, CopyBits always goes pixel by pixel (to tell row-copy bugs from other drawing problems). */
	private static final boolean NO_ROW_COPY=Boolean.getBoolean("hz68k.norowcopy");
	/** -Dhz68k.verifyrowcopy=true: debug aid, every row copy is checked against the pixel-by-pixel result (mismatches go to stderr). */
	private static final boolean VERIFY_ROW_COPY=Boolean.getBoolean("hz68k.verifyrowcopy");
	private void verifyRowCopy(Bitmap source,Rect sourceArea,Bitmap destination,Rect destinationArea,Rect drawingArea,int[] indexMap,int[] sourceColors,int mode,boolean byIndex) throws M68kCpuException {
		int height=destination.bounds.bottom-destination.bounds.top,length=destination.rowBytes*height;
		byte[] before=memory.readBytes(destination.base,length);
		copyRows(source,sourceArea,destination,destinationArea,drawingArea,indexMap,sourceColors);
		byte[] rows=memory.readBytes(destination.base,length);
		memory.load(destination.base,before);
		int sourceWidth=sourceArea.right-sourceArea.left,sourceHeight=sourceArea.bottom-sourceArea.top;
		int[] snapshot=new int[sourceWidth*sourceHeight];
		if(!byIndex)for(int y=0;y<sourceHeight;y++)for(int x=0;x<sourceWidth;x++)snapshot[y*sourceWidth+x]=source.getInkRgb(sourceArea.left+x,sourceArea.top+y);
		for(int v=drawingArea.top;v<drawingArea.bottom;v++)for(int h=drawingArea.left;h<drawingArea.right;h++){int sx=h-destinationArea.left,sy=v-destinationArea.top;
			if(byIndex){destination.setIndex(h,v,source.getIndex(sourceArea.left+sx,sourceArea.top+sy));continue;}
			destination.setInkRgb(h,v,transferInk(snapshot[sy*sourceWidth+sx],destination.getInkRgb(h,v),mode,destination));}
		byte[] generic=memory.readBytes(destination.base,length);int mismatches=0,first=-1;
		for(int i=0;i<length;i++)if(rows[i]!=generic[i]){if(first<0)first=i;mismatches++;}
		if(mismatches>0)System.err.println("[68K] CopyBits row copy MISMATCH: "+mismatches+" bytes, first at row "+(first/destination.rowBytes)+" byte "+(first%destination.rowBytes)+" src depth "+source.pixelSize+" bounds "+source.bounds+" rect "+sourceArea+" -> dst depth "+destination.pixelSize+" bounds "+destination.bounds+" rect "+destinationArea+" drawing "+drawingArea+(indexMap!=null?" (index map)":sourceColors!=null?" (to direct)":" (raw)")+" byIndex="+byIndex);
	}
	/** Scratch buffers reused across drawing calls (the emulation thread is the only caller). */
	private byte[] copyScratch=new byte[0],rowScratch=new byte[0],spanScratch=new byte[0],readRowScratch=new byte[0],writeRowScratch=new byte[0];private int[] rgbRowScratch=new int[0],ownerScratch=new int[0];private int[] valueScratch=new int[0],snapshotScratch=new int[0],maskSourceScratch=new int[0];private boolean[] maskScratch=new boolean[0];
	private static byte[] bytes(byte[] current,int size){return current.length>=size?current:new byte[Math.max(size,current.length*2)];}
	private static int[] ints(int[] current,int size){return current.length>=size?current:new int[Math.max(size,current.length*2)];}

	/**
	 * Row-wise unscaled copy for {@link #copyBits}. The source rows are read into a scratch buffer first, so overlapping
	 * copies (scrolling within one PixMap) are safe. {@code indexMap} converts indexed pixel values (null = keep them),
	 * {@code sourceColors} expands indexed pixels into a 16/32-bit destination; with neither the bytes are copied as they
	 * are (partial edge bytes of packed depths keep their other pixels, unaligned packed rows go pixel by pixel).
	 */
	private void copyRows(Bitmap source,Rect sourceArea,Bitmap destination,Rect destinationArea,Rect drawingArea,int[] indexMap,int[] sourceColors) throws M68kCpuException {
		int width=drawingArea.right-drawingArea.left,height=drawingArea.bottom-drawingArea.top;if(width<=0||height<=0)return;
		for(int v=drawingArea.top;v<drawingArea.bottom;v++)destination.disownSpan(v,drawingArea.left,drawingArea.right); // the rows land through memory.load
		int sps=source.pixelSize,dps=destination.pixelSize;
		int sx0=drawingArea.left-destinationArea.left+sourceArea.left-source.bounds.left,sy=drawingArea.top-destinationArea.top+sourceArea.top;
		int sb0=(sx0*sps)>>>3,sourceSpan=(((sx0+width)*sps+7)>>>3)-sb0;
		byte[] rows=copyScratch=bytes(copyScratch,sourceSpan*height);
		int sourceRow=source.base+(sy-source.bounds.top)*source.rowBytes+sb0;
		for(int y=0;y<height;y++,sourceRow+=source.rowBytes)memory.read(sourceRow,rows,y*sourceSpan,sourceSpan);
		int dx0=drawingArea.left-destination.bounds.left,db0=(dx0*dps)>>>3,destinationSpan=(((dx0+width)*dps+7)>>>3)-db0;
		int destinationRow=destination.base+(drawingArea.top-destination.bounds.top)*destination.rowBytes+db0,drb=destination.rowBytes;
		int headBits=(dx0*dps)&7,tailBits=(8-(((dx0+width)*dps)&7))&7; // bits of the first/last destination byte that belong to other pixels
		int headMask=headBits==0?0:(0xff<<(8-headBits))&0xff,tailMask=tailBits==0?0:(1<<tailBits)-1;
		boolean raw=indexMap==null&&sourceColors==null;
		if(raw&&sps>=8){for(int y=0;y<height;y++)memory.load(destinationRow+y*drb,rows,y*sourceSpan,sourceSpan);return;}
		byte[] row=rowScratch=bytes(rowScratch,destinationSpan);
		if(raw&&((sx0*sps)&7)==headBits){ // packed rows with the same bit alignment: whole bytes, masked edges
			for(int y=0;y<height;y++){int address=destinationRow+y*drb;System.arraycopy(rows,y*sourceSpan,row,0,destinationSpan);
				if(headMask!=0)row[0]=(byte)(memory.readByte(address)&headMask|row[0]&~headMask);
				if(tailMask!=0)row[destinationSpan-1]=(byte)(memory.readByte(address+destinationSpan-1)&tailMask|row[destinationSpan-1]&~tailMask);
				memory.load(address,row,0,destinationSpan);}
			return;
		}
		int[] values=valueScratch=ints(valueScratch,width);int sourceMask=(1<<sps)-1,sourceBit=(sx0*sps)&7,destinationMask=(1<<Math.min(dps,8))-1;
		for(int y=0;y<height;y++){
			int so=y*sourceSpan,address=destinationRow+y*drb;
			for(int x=0;x<width;x++){int bit=sourceBit+x*sps,v=(rows[so+(bit>>>3)]&0xff)>>>(8-sps-(bit&7))&sourceMask;values[x]=indexMap!=null&&v<indexMap.length?indexMap[v]:v;}
			if(sourceColors!=null){
				for(int x=0,o=0;x<width;x++){int v=values[x],rgb=v<sourceColors.length?sourceColors[v]:0;
					if(dps==16){int value=(rgb>>>19&31)<<10|(rgb>>>11&31)<<5|(rgb>>>3&31);row[o++]=(byte)(value>>>8);row[o++]=(byte)value;}
					else{row[o++]=(byte)0xff;row[o++]=(byte)(rgb>>>16);row[o++]=(byte)(rgb>>>8);row[o++]=(byte)rgb;}}
			}else{
				java.util.Arrays.fill(row,0,destinationSpan,(byte)0);
				if(headMask!=0)row[0]=(byte)(memory.readByte(address)&headMask);
				if(tailMask!=0)row[destinationSpan-1]|=(byte)(memory.readByte(address+destinationSpan-1)&tailMask);
				for(int x=0;x<width;x++){int bit=headBits+x*dps,o=bit>>>3,shift=8-dps-(bit&7);row[o]|=(byte)((values[x]&destinationMask)<<shift);}
			}
			memory.load(address,row,0,destinationSpan);
		}
	}

	/** Two indexed PixMaps share a colour table when it is the same Handle or the tables carry the same seed (ctSeed). */
	private String tableSeed(int table) throws M68kCpuException {int data=table==0?0:memory.readLong(table);return data==0?"-":"$"+Integer.toHexString(memory.readLong(data));}
	/**
	 * A GWorld made with noNewDevice for the main device draws through the screen's table (see {@link #matchThroughCurrentDevice}):
	 * its pixel values are screen CLUT indexes whatever table the PixMap record names, so a CopyBits onto the screen moves them
	 * as they are (HAVOC draws its menu pictures into such worlds after SetEntries and blits them to the window).
	 */
	private boolean drawnThroughScreen(Bitmap source,Bitmap destination) throws M68kCpuException {
		if(mainGDevice==0||source.sharedDevice!=mainGDevice)return false;
		if(destination instanceof ScreenBitmap)return true;
		int screenTable=deviceColorTable(mainGDevice);
		return destination.sharedDevice==mainGDevice||screenTable!=0&&destination.colorTable==screenTable;
	}
	private boolean sameColorTable(Bitmap a,Bitmap b) throws M68kCpuException {
		if(a.colorTable==0||b.colorTable==0)return false;if(a.colorTable==b.colorTable)return true;
		int da=memory.readLong(a.colorTable),db=memory.readLong(b.colorTable);return da!=0&&db!=0&&memory.readLong(da)==memory.readLong(db);
	}

	/** Copy source pixels where the corresponding 1-bit mask pixel is set. */
	private boolean copyMask(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],destinationRect=memory.readLong(sp),maskRect=memory.readLong(sp+4),sourceRect=memory.readLong(sp+8);
		int destinationBits=memory.readLong(sp+12),maskBits=memory.readLong(sp+16),sourceBits=memory.readLong(sp+20);cpu.a[7]+=24;
		if(sourceBits==0||maskBits==0||destinationBits==0||sourceRect==0||maskRect==0||destinationRect==0)return true;
		Bitmap source=colorized(readBitmap(sourceBits)),mask=readBitmap(maskBits),destination=readBitmap(destinationBits);
		Rect sourceArea=readRect(sourceRect),maskArea=readRect(maskRect),destinationArea=readRect(destinationRect);
		matchThroughCurrentDevice(destination); // a paint bucket copies the flooded area under the editor's device
		int sourceWidth=sourceArea.right-sourceArea.left,sourceHeight=sourceArea.bottom-sourceArea.top;
		int maskWidth=maskArea.right-maskArea.left,maskHeight=maskArea.bottom-maskArea.top;
		int destinationWidth=destinationArea.right-destinationArea.left,destinationHeight=destinationArea.bottom-destinationArea.top;
		if(sourceWidth<=0||sourceHeight<=0||maskWidth<=0||maskHeight<=0||destinationWidth<=0||destinationHeight<=0)return true;
		long sourcePixels=(long)sourceWidth*sourceHeight,maskPixels=(long)maskWidth*maskHeight;
		if(sourcePixels>16_777_216L||maskPixels>16_777_216L)throw new M68kCpuException(sourceRect,"CopyMask bitmap is too large");
		if(Mac68kApi.TRACE)System.err.println("[68K] CopyMask src $"+Integer.toHexString(sourceBits)+" depth "+source.pixelSize+" rect "+sourceArea+" mask $"+Integer.toHexString(maskBits)+" depth "+mask.pixelSize+" base $"+Integer.toHexString(mask.base)+" bounds "+mask.bounds+" rect "+maskArea+" -> dst $"+Integer.toHexString(destinationBits)+" depth "+destination.pixelSize+" rect "+destinationArea);
		// Same indexed depth and colour table: pixel values move untouched, as in CopyBits. Factory 1.1's conveyor tiles hold
		// entries 128-135 (all one tan but one black, rotated by SetEntries); matched by colour they all became 128 and the black.
		int[] paletteMap=paletteIndexMap(source,destination); // palette-index source tables (ctFlags bit 14), as in CopyBits
		boolean byIndex=!source.direct&&!destination.direct&&mask.pixelSize==1
			&&(paletteMap!=null||source.pixelSize==destination.pixelSize&&source.pixelSize>1&&(sameColorTable(source,destination)||drawnThroughScreen(source,destination)));
		int[] sourceSnapshot=maskSourceScratch=ints(maskSourceScratch,(int)sourcePixels);boolean[] maskSnapshot=maskScratch=maskScratch.length>=maskPixels?maskScratch:new boolean[(int)Math.max(maskPixels,maskScratch.length*2L)];
		for(int v=0;v<sourceHeight;v++)for(int h=0;h<sourceWidth;h++)sourceSnapshot[v*sourceWidth+h]=byIndex?(paletteMap!=null?paletteMap[source.getIndex(sourceArea.left+h,sourceArea.top+v)]:source.getIndex(sourceArea.left+h,sourceArea.top+v)):source.getInkRgb(sourceArea.left+h,sourceArea.top+v);
		// A deep mask (a PixMap deeper than 1 bit) weights the copy as CopyDeepMask does: black copies the source, white keeps
		// the destination, greys blend. iTunes draws its LCD apple as a black PICT through the antialiased grey "AppleMask";
		// thresholded, it came out as a solid black, jagged apple.
		int[] weights=null;
		if(mask.pixelSize>1){weights=new int[(int)maskPixels];
			for(int v=0;v<maskHeight;v++)for(int h=0;h<maskWidth;h++){int ink=mask.getInkRgb(maskArea.left+h,maskArea.top+v);int w=(299*(ink>>>16&255)+587*(ink>>>8&255)+114*(ink&255))/1000;weights[v*maskWidth+h]=w;maskSnapshot[v*maskWidth+h]=w>0;}}
		else for(int v=0;v<maskHeight;v++)for(int h=0;h<maskWidth;h++)maskSnapshot[v*maskWidth+h]=mask.get(maskArea.left+h,maskArea.top+v);
		if(Mac68kApi.TRACE){int on=0;for(int i=0;i<maskPixels;i++)if(maskSnapshot[i])on++;System.err.println("[68K] CopyMask mask covers "+on+" of "+maskPixels+" pixels");}
		Rect drawingArea=intersection(destinationArea,destination.bounds);
		boolean clipped=currentPort!=0&&isCurrentBitmapArgument(destinationBits);
		if(clipped)drawingArea=intersection(drawingArea,currentClipBounds(destination));
		if((long)(drawingArea.right-drawingArea.left)*(drawingArea.bottom-drawingArea.top)>16_777_216L)throw new M68kCpuException(destinationRect,"CopyMask destination rectangle is too large");
		boolean shaped=clipped&&clipHasShape();
		// hi-res blocks go where the mask copies the source whole (SmoothZoom); blended pixels are scaled up from their 1x colours
		final boolean[] maskOn=maskSnapshot;final int[] maskWeights=weights;final int mw=maskWidth,mh=maskHeight,dw=destinationWidth,dh=destinationHeight;final Rect to=destinationArea;
		SmoothCopy carry=smoothCopyBegin(source,sourceArea,destination,destinationArea,drawingArea,0,shaped,null,(th,tv)->{
			int mx=(int)(((long)(th-to.left)*mw)/dw),my=(int)(((long)(tv-to.top)*mh)/dh);return maskOn[my*mw+mx]&&(maskWeights==null||maskWeights[my*mw+mx]>=255);});
		try{
			for(int v=drawingArea.top;v<drawingArea.bottom;v++)for(int h=drawingArea.left;h<drawingArea.right;h++) {
				if(shaped&&!regionsAllow(h,v))continue;
				int sx=(int)(((long)(h-destinationArea.left)*sourceWidth)/destinationWidth),sy=(int)(((long)(v-destinationArea.top)*sourceHeight)/destinationHeight);
				int mx=(int)(((long)(h-destinationArea.left)*maskWidth)/destinationWidth),my=(int)(((long)(v-destinationArea.top)*maskHeight)/destinationHeight);
				if(!maskSnapshot[my*maskWidth+mx])continue;
				int ink=sourceSnapshot[sy*sourceWidth+sx],weight=weights==null?255:weights[my*maskWidth+mx];
				if(byIndex){destination.setIndex(h,v,ink);continue;}
				destination.setInkRgb(h,v,weight<255?mixInk(ink,destination.getInkRgb(h,v),weight):ink);
			}
			if(carry!=null){SmoothCopy c=carry;carry=null;c.end();}
		}finally{if(carry!=null)carry.abandon();}
		return true;
	}

	/**
	 * CopyDeepMask(srcBits, maskBits, dstBits, srcRect, maskRect, dstRect, mode, maskRgn): CopyBits through a mask whose
	 * pixels weight the copy - black copies the (mode-transferred) source, white keeps the destination, greys of a deep
	 * mask blend between them - and clipped to maskRgn.
	 */
	private boolean copyDeepMask(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],maskRegion=memory.readLong(sp),mode=(short)memory.readWord(sp+4),destinationRect=memory.readLong(sp+6),maskRect=memory.readLong(sp+10),sourceRect=memory.readLong(sp+14);
		int destinationBits=memory.readLong(sp+18),maskBits=memory.readLong(sp+22),sourceBits=memory.readLong(sp+26);cpu.a[7]+=30;
		if(sourceBits==0||maskBits==0||destinationBits==0||sourceRect==0||maskRect==0||destinationRect==0)return true;
		Bitmap source=colorized(readBitmap(sourceBits)),mask=readBitmap(maskBits),destination=readBitmap(destinationBits);
		Rect sourceArea=readRect(sourceRect),maskArea=readRect(maskRect),destinationArea=readRect(destinationRect);
		matchThroughCurrentDevice(destination);
		int sourceWidth=sourceArea.right-sourceArea.left,sourceHeight=sourceArea.bottom-sourceArea.top;
		int maskWidth=maskArea.right-maskArea.left,maskHeight=maskArea.bottom-maskArea.top;
		int destinationWidth=destinationArea.right-destinationArea.left,destinationHeight=destinationArea.bottom-destinationArea.top;
		if(sourceWidth<=0||sourceHeight<=0||maskWidth<=0||maskHeight<=0||destinationWidth<=0||destinationHeight<=0)return true;
		long sourcePixels=(long)sourceWidth*sourceHeight,maskPixels=(long)maskWidth*maskHeight;
		if(sourcePixels>16_777_216L||maskPixels>16_777_216L)throw new M68kCpuException(sourceRect,"CopyDeepMask bitmap is too large");
		int[] sourceSnapshot=maskSourceScratch=ints(maskSourceScratch,(int)sourcePixels);int[] weights=new int[(int)maskPixels];
		for(int v=0;v<sourceHeight;v++)for(int h=0;h<sourceWidth;h++)sourceSnapshot[v*sourceWidth+h]=source.getInkRgb(sourceArea.left+h,sourceArea.top+v);
		for(int v=0;v<maskHeight;v++)for(int h=0;h<maskWidth;h++){int ink=mask.getInkRgb(maskArea.left+h,maskArea.top+v);weights[v*maskWidth+h]=(299*(ink>>>16&255)+587*(ink>>>8&255)+114*(ink&255))/1000;}
		Rect drawingArea=intersection(destinationArea,destination.bounds);
		if(maskRegion!=0)drawingArea=intersection(drawingArea,readRegionRect(maskRegion));
		boolean clipped=currentPort!=0&&isCurrentBitmapArgument(destinationBits);
		if(clipped)drawingArea=intersection(drawingArea,currentClipBounds(destination));
		if(Mac68kApi.TRACE)System.err.println("[68K] CopyDeepMask src "+sourceArea+" depth "+source.pixelSize+" mask "+maskArea+" depth "+mask.pixelSize+" -> dst "+destinationArea+" depth "+destination.pixelSize+" bounds "+destination.bounds+" mode "+mode+" drawingArea "+drawingArea+(clipped?" clip "+currentClipBounds(destination)+" visRgn "+readRegion(memory.readLong(currentPort+VIS_RGN)).rects()+" clipRgn "+readRegion(memory.readLong(currentPort+CLIP_RGN)).rects():""));
		if(drawingArea.empty())return true;
		if((long)(drawingArea.right-drawingArea.left)*(drawingArea.bottom-drawingArea.top)>16_777_216L)throw new M68kCpuException(destinationRect,"CopyDeepMask destination rectangle is too large");
		boolean shaped=clipped&&clipHasShape();QdRegion maskShape=maskRegion==0?null:readRegion(maskRegion);if(maskShape!=null&&maskShape.isRect())maskShape=null;
		final int[] maskWeights=weights;final int mw=maskWidth,mh=maskHeight,dw=destinationWidth,dh=destinationHeight;final Rect to=destinationArea;
		SmoothCopy carry=smoothCopyBegin(source,sourceArea,destination,destinationArea,drawingArea,mode,shaped,maskShape,(th,tv)->{
			int mx=(int)(((long)(th-to.left)*mw)/dw),my=(int)(((long)(tv-to.top)*mh)/dh);return maskWeights[my*mw+mx]>=255;});
		try{
			for(int v=drawingArea.top;v<drawingArea.bottom;v++)for(int h=drawingArea.left;h<drawingArea.right;h++) {
				if(shaped&&!regionsAllow(h,v))continue;if(maskShape!=null&&!maskShape.contains(h,v))continue;
				int mx=(int)(((long)(h-destinationArea.left)*maskWidth)/destinationWidth),my=(int)(((long)(v-destinationArea.top)*maskHeight)/destinationHeight);
				int weight=weights[my*maskWidth+mx];if(weight==0)continue;
				int sx=(int)(((long)(h-destinationArea.left)*sourceWidth)/destinationWidth),sy=(int)(((long)(v-destinationArea.top)*sourceHeight)/destinationHeight);
				int before=destination.getInkRgb(h,v),after=transferInk(sourceSnapshot[sy*sourceWidth+sx],before,mode,destination);
				if(weight<255)after=mixInk(after,before,weight);
				destination.setInkRgb(h,v,after);
			}
			if(carry!=null){SmoothCopy c=carry;carry=null;c.end();}
		}finally{if(carry!=null)carry.abandon();}
		return true;
	}
	private static int mixInk(int a,int b,int weight){int r=((a>>>16&255)*weight+(b>>>16&255)*(255-weight))/255,g=((a>>>8&255)*weight+(b>>>8&255)*(255-weight))/255,bl=((a&255)*weight+(b&255)*(255-weight))/255;return r<<16|g<<8|bl;}

	/** GetSubTable(myColors, iTabRes, targetTbl): the value field of each entry of myColors becomes the index of its nearest
	 *  colour in targetTbl (NIL: the current device's table). */
	private boolean getSubTable(M68kCpu cpu) throws M68kCpuException {
		int target=memory.readLong(cpu.a[7]),table=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;
		int data=table==0?0:memory.readLong(table);if(data==0)return true;
		int count=(short)memory.readWord(data+6)+1;
		int targetData=target==0?0:memory.readLong(target);int targetCount=targetData==0?0:(short)memory.readWord(targetData+6)+1;
		for(int i=0;i<count;i++){int entry=data+8+i*8;int red=memory.readWord(entry+2),green=memory.readWord(entry+4),blue=memory.readWord(entry+6);int index;
			if(targetData==0)index=colorIndex((long)red<<32|(long)green<<16|blue);
			else{long best=Long.MAX_VALUE;index=0;for(int j=0;j<targetCount;j++){int t=targetData+8+j*8;int dr=red-memory.readWord(t+2),dg=green-memory.readWord(t+4),db=blue-memory.readWord(t+6);long d=(long)dr*dr+(long)dg*dg+(long)db*db;if(d<best){best=d;index=memory.readWord(t);}}}
			memory.writeWord(entry,index);}
		return true;
	}

	private boolean scrollRect(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],updateRegion=memory.readLong(sp),dv=(short)memory.readWord(sp+4);
		int dh=(short)memory.readWord(sp+6),rectPointer=memory.readLong(sp+8);cpu.a[7]+=12;
		if(currentPort==0||rectPointer==0){if(updateRegion!=0)writeRegionRect(updateRegion,new Rect(0,0,0,0));return true;}
		Bitmap bitmap=readCurrentBitmap();
		Rect area=intersection(readRect(rectPointer),currentClipBounds(bitmap));
		if(area.empty()){if(updateRegion!=0)writeRegionRect(updateRegion,new Rect(0,0,0,0));return true;}
		int width=area.right-area.left,height=area.bottom-area.top;
		if((long)width*height>16_777_216L)throw new M68kCpuException(rectPointer,"ScrollRect rectangle is too large");
		chargeBlit(cpu,area,bitmap);
		Rect exposed=null;boolean shaped=clipHasShape();
		// the hi-res blocks of what stays move along (SmoothZoom: scrolled text keeps its smooth glyphs)
		Rect stays=intersection(area,new Rect(area.top+dv,area.left+dh,area.bottom+dv,area.right+dh));
		SmoothCopy carry=stays.empty()?null:smoothCopyBegin(bitmap,new Rect(stays.top-dv,stays.left-dh,stays.bottom-dv,stays.right-dh),bitmap,stays,stays,0,shaped,null,null);
		try{
		// Rectangular clip in an ordinary PixMap: move the overlapping part as whole rows (copyRows snapshots the source
		// rows first, so the overlap is safe) and erase the uncovered strips with the background pattern. The per-pixel
		// path below converts every pixel through RGB; Speedometer's Graf test scrolls 1328 times and spent a fifth of
		// its run in it.
		if(!shaped&&bitmap.base!=0&&!(bitmap instanceof ScreenBitmap)&&!NO_ROW_COPY&&area.equals(intersection(area,bitmap.bounds))){
			Rect moved=intersection(area,new Rect(area.top+dv,area.left+dh,area.bottom+dv,area.right+dh)); // destination of the pixels that stay
			if(!moved.empty())copyRows(bitmap,new Rect(moved.top-dv,moved.left-dh,moved.bottom-dv,moved.right-dh),bitmap,moved,moved,null,null);
			if(dv!=0){Rect strip=dv>0?new Rect(area.top,area.left,Math.min(area.bottom,area.top+dv),area.right):new Rect(Math.max(area.top,area.bottom+dv),area.left,area.bottom,area.right);rasterRect(strip,2,0);exposed=strip;}
			if(dh!=0){Rect strip=dh>0?new Rect(area.top,area.left,area.bottom,Math.min(area.right,area.left+dh)):new Rect(area.top,Math.max(area.left,area.right+dh),area.bottom,area.right);rasterRect(strip,2,0);exposed=exposed==null?strip:union(exposed,strip);}
			if(updateRegion!=0)writeRegionRect(updateRegion,exposed==null?new Rect(0,0,0,0):exposed);return true;
		}
		int[] snapshot=new int[width*height];
		for(int v=area.top;v<area.bottom;v++)for(int h=area.left;h<area.right;h++)
			snapshot[(v-area.top)*width+h-area.left]=bitmap.getInkRgb(h,v);
		for(int v=area.top;v<area.bottom;v++)for(int h=area.left;h<area.right;h++) {
			if(shaped&&!regionsAllow(h,v))continue;
			int sourceH=h-dh,sourceV=v-dv;
			if(contains(area,sourceH,sourceV))bitmap.setInkRgb(h,v,snapshot[(sourceV-area.top)*width+sourceH-area.left]);
			else {bitmap.setInkRgb(h,v,patternInk(currentPort+BK_PAT,h,v));
				Rect pixel=new Rect(v,h,v+1,h+1);exposed=exposed==null?pixel:union(exposed,pixel);}
		}
		if(updateRegion!=0)writeRegionRect(updateRegion,exposed==null?new Rect(0,0,0,0):exposed);return true;
		}finally{if(carry!=null)carry.end();}
	}

	/** Trap word of the standard bottleneck procedure at QDProcs/CQDProcs index {@code i} (textProc ... putPicProc, opcodeProc). */
	static int stdProcTrap(int i){int[] traps={0x882,0x890,0x8a0,0x8af,0x8b6,0x8bd,0x8c5,0x8d1,0x8eb,0x8f1,0x8ed,0x8ee,0x8f0,0xbf8};return 0xa000|traps[i];}
	/** The current port's own procedure at a bottleneck index (0: none installed, or the standard one). */
	int customBottleneck(int index) throws M68kCpuException {
		if(currentPort==0)return 0;int procs=memory.readLong(currentPort+104);if(procs==0)return 0;
		int proc=memory.readLong(procs+index*4);M68kCpu cpu=macApi.application().cpu;
		return proc==0||cpu==null||proc==cpu.trapRomAddress(stdProcTrap(index))?0:proc;
	}
	/** PicComment/StdComment: a comment opcode in the picture being recorded (ShortComment without data, LongComment with). */
	void recordPictureComment(int kind,byte[] data){
		if(openPicture==0||pictureData==null)return;
		// the opcode in the picture's own form: a word in a version 2 picture (some PICT exports open with
		// PicComments; a byte opcode there threw the player off by one and the file could not be played back)
		if(data.length==0){pictureOpcode(0xa0);pictureWord(kind);return;}
		pictureOpcode(0xa1);pictureWord(kind);pictureWord(data.length);pictureData.write(data,0,data.length);if(pictureVersion2&&(data.length&1)!=0)pictureByte(0);
	}
	/** StdPutPic: raw opcode bytes appended to the picture being recorded. */
	void appendPictureBytes(byte[] bytes){if(openPicture!=0&&pictureData!=null)pictureData.write(bytes,0,bytes.length);}
	/** The program's putPicProc while it records a picture (0: the data goes into the Handle), and how much of pictureData it has seen. */
	private int spoolProc,spooled;private boolean spooling,spoolClosing;
	/** The largest piece a putPicProc gets in one call (its byteCount is an INTEGER). */
	private static final int SPOOL_PIECE=0x4000;
	/**
	 * A picture recorded with the port's own putPicProc (spooling to a file, IM Imaging "Spooling a Picture"): every
	 * opcode chunk from the version opcode on goes to the procedure as StdPutPic would receive it; the 10-byte header
	 * (picSize, picFrame) stays in the Handle alone, as the getPicProc side never supplies it either - Strata StudioPro 1.1
	 * leaves 10 bytes for it after the file's 512-byte header and writes the Handle's header there after ClosePicture.
	 * Called after each trap so the chunks follow the drawing calls that produced them; ClosePicture's end opcode is the
	 * last chunk.
	 */
	void flushPictureSpool(M68kCpu cpu) throws M68kException {
		if(spoolProc==0||spooling||pictureData==null)return;
		if(pictureData.size()>spooled){byte[] all=pictureData.toByteArray();byte[] chunk=java.util.Arrays.copyOfRange(all,spooled,all.length);spooled=all.length;spool(cpu,chunk);}
		if(spoolClosing){spoolProc=0;spooled=0;spoolClosing=false;openPicture=0;pictureFrame=null;pictureData=null;}
	}
	private void spool(M68kCpu cpu,byte[] chunk) throws M68kException {
		if(chunk.length==0)return;spooling=true;
		if(Mac68kApi.TRACE)System.err.println("[68K] putPicProc $"+Integer.toHexString(spoolProc)+" gets "+chunk.length+" bytes: "+java.util.HexFormat.of().formatHex(chunk,0,Math.min(chunk.length,48)));
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr; // the program is between two of its own instructions: nothing may change
		// byteCount is an INTEGER: a CopyBits of a large PixMap goes in pieces (543 KB handed over at once reached Strata
		// StudioPro as 19022 bytes, its low word)
		try{for(int at=0;at<chunk.length;at+=SPOOL_PIECE){int length=Math.min(SPOOL_PIECE,chunk.length-at);
				System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]-=(length+1)&~1;int data=cpu.a[7];memory.load(data,java.util.Arrays.copyOfRange(chunk,at,at+length));
				cpu.pushLong(data);cpu.pushWord(length);cpu.callPascal(spoolProc,6);}}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;spooling=false;}
	}

	private boolean openPicture(M68kCpu cpu) throws M68kCpuException {
		int framePointer=memory.readLong(cpu.a[7]);cpu.a[7]+=4;pictureFrame=framePointer==0?new Rect(0,0,0,0):readRect(framePointer);
		// A colour port records a version 2 picture, as Color QuickDraw does: word opcodes, PixPats and colour bitmaps kept
		// Some programs paint with PenPixPat into a recorded picture and play it into a GWorld; a version 1
		// recording reduced every texture to the same grey dither).
		// A NewWindow window is a basic GrafPort to the program (a CGrafPort only inside), so it records version 1 like a real Mac.
		PortState recording=currentPort==0?null:ports.get(currentPort);
		pictureVersion2=colorPictureRequested||currentPort!=0&&isColorPort(currentPort)&&!(recording!=null&&recording.classicWindow);
		byte[] initial=new byte[13];putWord(initial,0,13);putRect(initial,2,pictureFrame);initial[10]=0x11;initial[11]=0x01;initial[12]=(byte)0xff;
		spoolProc=customBottleneck(12);spooled=0;spoolClosing=false;pictureData=new ByteArrayOutputStream();
		if(spoolProc!=0){initial=new byte[10];putWord(initial,0,10);putRect(initial,2,pictureFrame);} // the Handle gets the header alone; the procedure collects the opcodes
		openPicture=macApi.createHandle(initial);resetPictureTextState();picturePenH=1;picturePenV=1;pictureClip=null;
		memory.writeLong(cpu.a[7],openPicture);
		// OpenPicture hides the pen (ClosePicture shows it again): what is recorded is not drawn on the screen as well
		pictureRecordingPort=currentPort;if(currentPort!=0){memory.writeWord(currentPort+PN_VIS,(short)memory.readWord(currentPort+PN_VIS)-1);memory.writeLong(currentPort+PIC_SAVE,openPicture);}
		if(pictureVersion2){pictureWord(0x0011);pictureWord(0x02ff);pictureWord(0x0c00);pictureWord(0xfffe);pictureWord(0);pictureLong(0x00480000);pictureLong(0x00480000);pictureRect(pictureFrame);pictureLong(0);}
		else{pictureData.write(0x11);pictureData.write(0x01);}
		return true;
	}
	private boolean pictureVersion2;
	/**
	 * OpenCPicture: an extended version 2 picture whatever the port (its parameter block starts with the srcRect, OpenPicture's
	 * frame). xDraw records with it in HyperCard's card window, a classic port: the version 1 picture it got held RGBFgCol
	 * opcodes no version 1 reader takes.
	 */
	boolean openColorPicture(M68kCpu cpu) throws M68kCpuException {colorPictureRequested=true;try{return openPicture(cpu);}finally{colorPictureRequested=false;}}
	private boolean colorPictureRequested;
	/** An opcode: a byte in a version 1 picture, an even-aligned word in a version 2 one. */
	private void pictureOpcode(int opcode){if(pictureVersion2){if((pictureData.size()&1)!=0)pictureByte(0);pictureWord(opcode);}else pictureByte(opcode);}

	private int pictureRecordingPort;
	/** ShowPen for the port OpenPicture hid the pen of. */
	private void showPictureRecordingPen() throws M68kCpuException {
		int port=pictureRecordingPort;pictureRecordingPort=0;
		if(port!=0)memory.writeWord(port+PN_VIS,(short)memory.readWord(port+PN_VIS)+1); // uncapped: ShowPen;OpenPicture;...;ClosePicture leaves pnVis 1
		for(int p:new int[]{port,currentPort})if(p!=0&&openPicture!=0&&memory.readLong(p+PIC_SAVE)==openPicture)memory.writeLong(p+PIC_SAVE,0);
	}
	private boolean closePicture() throws M68kCpuException {
		if(openPicture==0)return true;showPictureRecordingPen();pictureOpcode(0xff);int size=10+pictureData.size();
		if(spoolProc!=0){spoolClosing=true;return true;} // the trap's flush hands the rest (and the end opcode) to the putPicProc
		// picSize is the low word of the real size (IM V: pictures over 32 KB are measured with GetHandleSize)
		byte[] bytes=new byte[size];putWord(bytes,0,size&0xffff);putRect(bytes,2,pictureFrame);
		byte[] commands=pictureData.toByteArray();System.arraycopy(commands,0,bytes,10,commands.length);macApi.loadHandle(openPicture,bytes);
		openPicture=0;pictureFrame=null;pictureData=null;return true;
	}

	private boolean killPicture(M68kCpu cpu) throws M68kCpuException {
		int picture=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(picture!=0)macApi.disposeHandle(picture);
		if(openPicture==picture){showPictureRecordingPen();openPicture=0;pictureFrame=null;pictureData=null;spoolProc=0;spooled=0;spoolClosing=false;}return true;
	}

	/** The Window Manager's own update of a window with a picture (SetWindowPic): the picture drawn into the window's port
	 *  rectangle, clipped as the port is, the program's current port left alone. */
	void drawWindowPicture(int port,int picture) throws M68kCpuException {
		int data=picture==0?0:memory.readLong(picture);if(port==0||data==0)return;
		int size=Math.max(memory.readWord(data),macApi.handleSize(picture));if(size<11)return;
		int saved=currentPort;
		try{setCurrentPort(port);playPicture(data,size,readRect(data+2),readRect(port+16));}finally{setCurrentPort(saved);}
	}
	/** DrawPicture of the Picture {@code picture} (the bytes of a PICT without the file header) into {@code dest}
	 *  ({top,left,bottom,right}) of {@code port}, with that port and {@code device} current as after SetGWorld (the 'PICT'
	 *  graphics importer of the native Movie Toolbox); the program's current port and device are left alone. */
	void drawPictureInto(int port,int device,byte[] picture,int[] dest) throws M68kCpuException {
		if(port==0||picture==null||picture.length<11)return;
		int handle=macApi.createHandle(picture),savedPort=currentPort,savedDevice=currentGDevice;GWorldState world=gWorlds.get(port);
		try{currentGDevice=device!=0?device:world!=null?world.offscreenDevice:currentGDevice!=0?currentGDevice:ensureMainGDevice();setCurrentPort(port);
			int data=memory.readLong(handle);playPicture(data,picture.length,readRect(data+2),new Rect(dest[0],dest[1],dest[2],dest[3]));}
		finally{setCurrentPort(savedPort);currentGDevice=savedDevice;macApi.disposeHandleAndFree(handle);}
	}

	/** DrawPicture calls and icons plotted (PlotIcon, PlotCIcon, icon suites: {@link HostQuickDraw68k#drawImage}) so far: the Menu
	 *  Manager tells a picture MDEF from a text one by whether its mDrawMsg draws any (MenuManager68k.graphicalMdef). */
	int imageDraws;
	int imageDraws(){return imageDraws;}
	private boolean drawPicture(M68kCpu cpu) throws M68kCpuException {
		int destinationPointer=memory.readLong(cpu.a[7]),picture=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
		if(currentPort==0||picture==0||destinationPointer==0)return true;int data=memory.readLong(picture);if(data==0)return true;
		int size=memory.readWord(data),handleSize=macApi.handleSize(picture);if(handleSize>size)size=handleSize;
		// With a getPicProc installed the picture's opcodes come from it, whatever the Handle holds: Director 7 draws a 10-byte
		// Handle (picSize and picFrame) and spools a JPEG wrapped in a QuickTime opcode through its procedure (Last Call's bar).
		int spooled=0;
		{int[] in=spoolPictureIn(cpu,data,size);if(in!=null){spooled=in[0];size=in[1];}}
		// Real QuickDraw does not check the size; a record with no opcodes draws nothing.
		if(size<11){if(spooled!=0)memory.free(spooled);return true;}
		if(PICTURE_TRACE&&size>2000){try{java.nio.file.Files.write(new java.io.File(System.getProperty("java.io.tmpdir"),"hz-pict-"+Integer.toHexString(picture)+"-"+size+".pict").toPath(),memoryBytes(data,size));}catch(Exception ignored){}}
		if(Mac68kApi.TRACE)System.err.println("[68K] DrawPicture $"+Integer.toHexString(picture)+" frame "+readRect(data+2)+" -> "+readRect(destinationPointer)+" clip "+currentClip()+" vis "+readRegionRect(memory.readLong(currentPort+VIS_RGN))+" origin "+state().originH+","+state().originV);
		Rect frame=readRect(data+2),destination=readRect(destinationPointer);imageDraws++;
		// A picture drawn while another one records goes into the recording (QuickDraw plays it through the bottlenecks, which
		// record), here as its rendered image in the destination rectangle; with the pen hidden by OpenPicture nothing reaches
		// the screen (StdBits: "IS PNVIS >= 0 ? NO, QUIT"). Word 5.1's Print Preview records each page this way with the
		// document's pictures inside; they used to land on the screen at page coordinates and were missing from the pages.
		// With the pen shown again (xDraw's savePic replays the stack's PICT into its recording) the play below records the
		// picture's own opcodes through the bottlenecks: the rendered image as well put a 512x342 opaque bitmap ahead of them
		// (700 KB in a version 2 picture, and white over whatever the picture is later drawn on).
		if(recordingPicture()&&currentPort==pictureRecordingPort&&(short)memory.readWord(currentPort+PN_VIS)<0){
			// a picture of the recording's own version drawn in its own frame goes in as its opcodes, as QuickDraw records it (xDraw's
			// savePic replays the stack's PICT into each new one: the rendered image made that 700 KB a click)
			if(recordPictureOpcodes(memoryBytes(spooled!=0?spooled:data,size),frame,destination)){if(spooled!=0)memory.free(spooled);return true;}
			BufferedImage image=null;
			try{image=renderPictureAtDepth(memoryBytes(spooled!=0?spooled:data,size),32);}catch(M68kCpuException e){image=null;}
			if(image!=null){recordPictureImage(image,destination);if(spooled!=0)memory.free(spooled);return true;}
		}
		pictureCpu=cpu;try{playPicture(spooled!=0?spooled:data,size,frame,destination);}finally{pictureCpu=null;if(spooled!=0)memory.free(spooled);}return true;
	}
	/**
	 * The opcodes of a picture of the recording's version drawn unscaled in its own frame, into the open picture (after its
	 * version opcode and header, up to OpEndPic); false when they cannot go in as they are (another version, a moved or
	 * scaled frame, no OpEndPic at the end).
	 */
	private boolean recordPictureOpcodes(byte[] picture,Rect frame,Rect destination){
		if(picture.length<14||!frame.equals(destination))return false;
		boolean v2=(picture[10]&0xff)==0x00&&(picture[11]&0xff)==0x11&&(picture[12]&0xff)==0x02&&(picture[13]&0xff)==0xff;
		boolean v1=(picture[10]&0xff)==0x11&&(picture[11]&0xff)==0x01;
		if(v2!=pictureVersion2||!v1&&!v2)return false;
		int from,to;
		if(v2){
			if(picture.length<42||(picture[14]&0xff)!=0x0c||(picture[15]&0xff)!=0x00)return false; // HeaderOp $0C00 and its 24 bytes
			from=40;to=-1;for(int at=picture.length-2;at>=from;at--)if(((at-from)&1)==0&&picture[at]==0&&(picture[at+1]&0xff)==0xff){to=at;break;}
			if(to<0)return false;
			if((pictureData.size()&1)!=0)pictureByte(0);
		}else{
			from=12;to=-1;for(int at=picture.length-1;at>=from;at--)if((picture[at]&0xff)==0xff){to=at;break;}
			if(to<0)return false;
		}
		pictureData.write(picture,from,to-from);
		resetPictureTextState(); // the copied opcodes may have set the text state: the next text records it again
		return true;
	}
	/** A rendered picture into the open one: DirectBitsRect (srcCopy) in a version 2 picture, a 1-bit BitsRect (srcOr) in version 1. */
	private void recordPictureImage(BufferedImage image,Rect destination){
		int width=image.getWidth(),height=image.getHeight();Rect source=new Rect(0,0,height,width);
		if(pictureVersion2){
			int rowBytes=width*4;if((long)rowBytes*height>16_777_216L||rowBytes>0x3fff)return;
			pictureOpcode(0x9a);pictureLong(0x000000ff);
			pictureWord(0x8000|rowBytes);pictureRect(source);pictureWord(0);pictureWord(1);pictureLong(0);pictureLong(0x00480000);pictureLong(0x00480000);
			pictureWord(16);pictureWord(32);pictureWord(3);pictureWord(8);pictureLong(0);pictureLong(0);pictureLong(0);
			pictureRect(source);pictureRect(destination);pictureWord(0);
			for(int y=0;y<height;y++)for(int x=0;x<width;x++)pictureLong(image.getRGB(x,y)&0xffffff);
			return;
		}
		int rowBytes=((width+15)/16)*2;if(rowBytes>0x3fff)return;
		pictureOpcode(0x90);pictureWord(rowBytes);pictureRect(source);pictureRect(source);pictureRect(destination);pictureWord(1);
		for(int y=0;y<height;y++)for(int column=0;column<rowBytes;column++){
			int value=0;for(int bit=0;bit<8;bit++){int x=column*8+bit;if(x<width){int rgb=image.getRGB(x,y);int luma=((rgb>>16&255)*30+(rgb>>8&255)*59+(rgb&255)*11)/100;if(luma<128)value|=0x80>>>bit;}}
			pictureByte(value);
		}
	}
	/**
	 * A picture played back through the port's own getPicProc (IM Imaging "Spooling a Picture"): the Handle holds only the
	 * 10-byte header and the opcodes come from the procedure, which reads them from wherever the program keeps them.
	 * Bert 1.1 and Bikaka 1.4 do exactly that - SetStdCProcs, their own getPicProc, a 10-byte Handle filled by
	 * ReadPartialResource - and the player, reading the Handle, found nothing but the header ("unsupported PICT v1
	 * opcode"). The whole body is pulled into a buffer of picSize bytes before the play, in the small chunks StdGetPic
	 * would ask for, so the program's file position ends up past the picture as it would on a real Mac. Returns the
	 * buffer (the caller frees it) or 0 when the port has no getPicProc of its own.
	 */
	/**
	 * DrawPicture with the port's getPicProc installed: QuickDraw takes the picture's opcodes from the procedure, one by one up
	 * to OpEndPic, and only picSize/picFrame from the Handle. The opcodes are read the same way into a buffer here (null: no
	 * getPicProc; else {buffer, size}). A version 1 picture, or an opcode whose length is not worked out below, is read up
	 * to the Handle's picSize as before.
	 */
	private int[] spoolPictureIn(M68kCpu cpu,int data,int size) throws M68kCpuException {
		int proc=customBottleneck(11);if(proc==0)return null;
		PictureSpool in=new PictureSpool(cpu,proc,data);
		try{
			in.read(2);
			if(in.word(10)==0x0011){in.read(2);if(in.word(12)!=0x02ff)in.readTo(size);else in.readVersion2(size);}
			else in.readTo(size);
		}finally{in.restore();}
		int buffer=memory.allocate(Math.max(in.length,12),2);memory.load(buffer,java.util.Arrays.copyOf(in.bytes,in.length));
		return new int[]{buffer,in.length};
	}
	/** The bytes of a spooled picture as its getPicProc hands them over (the 10-byte header comes from the Handle). */
	private final class PictureSpool {
		final M68kCpu cpu;final int proc;final int[] d,a;final int sr;byte[] bytes=new byte[4096];int length=10;int scratch;
		PictureSpool(M68kCpu cpu,int proc,int data) throws M68kCpuException {
			this.cpu=cpu;this.proc=proc;d=cpu.d.clone();a=cpu.a.clone();sr=cpu.sr; // the program is between two of its own instructions: nothing may change
			for(int i=0;i<10;i++)bytes[i]=(byte)memory.readByte(data+i);
		}
		void restore() throws M68kCpuException {System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;if(scratch!=0){memory.free(scratch);scratch=0;}}
		int word(int at){return (bytes[at]&255)<<8|bytes[at+1]&255;}
		int signedWord(int at){return (short)word(at);}
		int longAt(int at){return word(at)<<16|word(at+2);}
		/** {@code count} more bytes from the getPicProc (pictures over 16 MB are not read further). */
		void read(int count) throws M68kCpuException {
			if(count<=0)return;if((long)length+count>16*1024*1024)throw new M68kCpuException(proc,"spooled picture over 16 MB");
			if(length+count>bytes.length)bytes=java.util.Arrays.copyOf(bytes,Math.max(bytes.length*2,length+count));
			if(scratch==0)scratch=memory.allocate(SPOOL_CHUNK,2);
			for(int done=0;done<count;){int chunk=Math.min(SPOOL_CHUNK,count-done);
				cpu.pushLong(scratch);cpu.pushWord(chunk);
				try{cpu.callPascal(proc,6);}catch(M68kCpuException e){throw e;}catch(M68kException e){throw new M68kCpuException(proc,"getPicProc failed: "+e.getMessage());}
				for(int i=0;i<chunk;i++)bytes[length+done+i]=(byte)memory.readByte(scratch+i);done+=chunk;}
			length+=count;
		}
		void readTo(int size) throws M68kCpuException {if(size>length)read(size-length);}
		/** The opcodes of a version 2 picture up to OpEndPic; an opcode of unknown length ends with the rest up to picSize. */
		void readVersion2(int size) throws M68kCpuException {
			for(int guard=0;guard<1_000_000;guard++){
				if((length&1)!=0)read(1);
				read(2);int op=word(length-2);if(op==0x00ff)return;
				int n=fixedLength(op);
				if(n>=0){read(n);continue;}
				switch(op){
					case 0x01:case 0x70:case 0x71:case 0x72:case 0x73:case 0x74:case 0x75:case 0x76:case 0x77:
					case 0x80:case 0x81:case 0x82:case 0x83:case 0x84:case 0x85:case 0x86:case 0x87:
						read(2);read(word(length-2)-2);continue; // region / polygon: the size word counts itself
					case 0x28:read(5);read(bytes[length-1]&255);continue;  // LongText
					case 0x29:case 0x2a:read(2);read(bytes[length-1]&255);continue; // DHText, DVText
					case 0x2b:read(3);read(bytes[length-1]&255);continue;  // DHDVText
					case 0xa1:read(4);read(word(length-2));continue;     // LongComment
					case 0x90:case 0x91:case 0x98:case 0x99:readBits(op,false);continue;
					case 0x9a:case 0x9b:readBits(op,true);continue;
					default:
						if(op<=0x2f&&op>=0x24||op>=0x92&&op<=0x97||op>=0x9c&&op<=0x9f||op>=0xa2&&op<=0xaf||op==0x2c||op==0x2d||op==0x2e||op==0x2f){read(2);read(word(length-2));continue;}
						if(op>=0xd0&&op<=0xfe||op>=0x8100){read(4);int len=longAt(length-4);if(len<0)return;read(len);continue;}
						readTo(size);return;
				}
			}
		}
		/** Opcodes with a fixed data length (Imaging With QuickDraw A-22..A-32), -1 for the others. */
		private int fixedLength(int op){
			if(op>=0x0100&&op<=0x01ff)return 2;
			if(op>=0x0200&&op<=0x7fff)return (op>>8)*2; // HeaderOp $0C00: 24
			if(op>=0x8000&&op<=0x80ff)return 0;
			switch(op){
				case 0x00:case 0x17:case 0x18:case 0x19:case 0x1c:case 0x1e:return 0;
				case 0x04:return 1;
				case 0x03:case 0x05:case 0x08:case 0x0d:case 0x11:case 0x15:case 0x16:case 0x23:case 0xa0:return 2;
				case 0x06:case 0x07:case 0x0b:case 0x0c:case 0x0e:case 0x0f:case 0x21:return 4;
				case 0x1a:case 0x1b:case 0x1d:case 0x1f:case 0x22:return 6;
				case 0x02:case 0x09:case 0x0a:case 0x10:case 0x20:return 8;
				default:break;
			}
			if(op>=0x30&&op<=0x37||op>=0x40&&op<=0x47||op>=0x50&&op<=0x57)return 8; // rect, rrect, oval
			if(op>=0x38&&op<=0x3f||op>=0x48&&op<=0x4f||op>=0x58&&op<=0x5f||op>=0x78&&op<=0x7f||op>=0x88&&op<=0x8f||op>=0xb0&&op<=0xcf)return 0; // ...SameRect, reserved
			if(op>=0x60&&op<=0x67)return 12; // arc
			if(op>=0x68&&op<=0x6f)return 4;  // sameArc
			return -1;
		}
		/** BitsRect/BitsRgn/PackBitsRect/PackBitsRgn ({@code direct} false) and DirectBitsRect/DirectBitsRgn: header, then the rows. */
		private void readBits(int op,boolean direct) throws M68kCpuException {
			int packType=0,rowBytes,top,bottom,left,right;
			if(direct)read(4); // baseAddr
			read(2);rowBytes=word(length-2);boolean pixMap=direct||(rowBytes&0x8000)!=0;rowBytes&=0x3fff;
			read(8);top=signedWord(length-8);left=signedWord(length-6);bottom=signedWord(length-4);right=signedWord(length-2);
			if(pixMap){read(36);packType=word(length-36+2);
				if(!direct){read(8);int ctSize=word(length-2);read((ctSize+1)*8);}}
			read(18); // srcRect, dstRect, mode
			if(op==0x91||op==0x99||op==0x9b){read(2);read(word(length-2)-2);} // mask region
			int rows=Math.max(0,bottom-top),width=Math.max(0,right-left);
			if(rowBytes<8||packType==1){read(rows*rowBytes);return;}
			if(direct&&packType==2){read(rows*width*3);return;}
			for(int r=0;r<rows;r++){if(rowBytes>250){read(2);read(word(length-2));}else{read(1);read(bytes[length-1]&255);}}
		}
	}
	private static final int SPOOL_CHUNK=512;
	/** The CPU of the DrawPicture in progress (null while a picture is rendered for the host): the player hands shapes to the
	 *  port's own bottleneck procedures through it. */
	private M68kCpu pictureCpu;
	/**
	 * DrawPicture goes through the port's grafProcs like the drawing traps do (IM I-197): a program installs its own polyProc
	 * or lineProc to pick the shapes out of a picture instead of drawing them - Arashi traces its title logo this way (a PICT
	 * of polygons played into a 16x5 scratch port whose polyProc collects the segments). Returns true when the procedure took
	 * the call; the standard procedures (SetStdProcs) and a NIL grafProcs leave the player drawing itself.
	 */
	private int pictureBottleneck(int index){
		if(pictureCpu==null||currentPort==0)return 0;
		try{int procs=memory.readLong(currentPort+104);if(procs==0)return 0;
			int proc=memory.readLong(procs+index*4);return proc==0||proc==pictureCpu.trapRomAddress(stdProcTrap(index))?0:proc;}
		catch(M68kCpuException e){return 0;}
	}
	private boolean picturePolygonProc(int verb,List<QdPoint> points) throws M68kCpuException {
		int proc=pictureBottleneck(6);if(proc==0)return false;
		int count=points.size(),size=10+count*4;byte[] bytes=new byte[size];putWord(bytes,0,size);Rect bounds=polygonBounds(points);
		putWord(bytes,2,bounds.top);putWord(bytes,4,bounds.left);putWord(bytes,6,bounds.bottom);putWord(bytes,8,bounds.right);
		for(int i=0;i<count;i++){QdPoint point=points.get(i);putWord(bytes,10+i*4,point.v);putWord(bytes,12+i*4,point.h);}
		int polygon=macApi.createHandle(bytes);
		try{callBottleneck(proc,new int[]{verb<<8},new int[]{polygon});}finally{macApi.disposeHandle(polygon);}return true;
	}
	/**
	 * A bits opcode of a picture played into a port whose bitsProc is the program's own (IM I-197, V-88): QuickDraw hands it
	 * the unpacked image - a BitMap, or a PixMap with the picture's colour table - with the source and (mapped) destination
	 * rectangles, the mode and the mask region, and the procedure draws it (usually by calling StdBits itself). HyperTint's
	 * HTAddPict "transparent" swaps the mode there: Myst's falling page and its cursor came out on white squares. False when
	 * the port draws through the standard procedures.
	 */
	private boolean pictureBitsProc(byte[][] rows,int rowBytes,Rect bounds,boolean pixMap,boolean direct,int pixelSize,int componentCount,int[] paletteRgb,Rect sourceRect,Rect target,int mode,Rect mask) throws M68kCpuException {
		int proc=pictureBottleneck(8);if(proc==0)return false;
		int height=rows.length;List<Integer> blocks=new ArrayList<Integer>();List<Integer> handles=new ArrayList<Integer>();
		try{
			int pixels=memory.allocate(Math.max(2,rowBytes*height),4);blocks.add(pixels);
			for(int row=0;row<height;row++)memory.load(pixels+row*rowBytes,rows[row]);
			int bits;
			if(pixMap){
				bits=memory.allocate(50,4);blocks.add(bits);memory.fill(bits,50,0);
				int entries=direct||paletteRgb==null?0:paletteRgb.length;byte[] table=new byte[8+entries*8];
				putLong(table,0,pictureTableSeed);putWord(table,6,entries==0?0xffff:entries-1);
				for(int i=0;i<entries;i++){int rgb=paletteRgb[i];putWord(table,8+i*8,i);putWord(table,10+i*8,(rgb>>>16&255)*257);putWord(table,12+i*8,(rgb>>>8&255)*257);putWord(table,14+i*8,(rgb&255)*257);}
				int ctab=macApi.createHandle(table);handles.add(ctab);
				memory.writeLong(bits,pixels);memory.writeWord(bits+4,rowBytes|0x8000);writeRect(bits+6,bounds);
				memory.writeLong(bits+22,72<<16);memory.writeLong(bits+26,72<<16); // hRes, vRes
				memory.writeWord(bits+30,direct?16:0);memory.writeWord(bits+32,pixelSize); // pixelType, pixelSize
				memory.writeWord(bits+34,direct?3:1);memory.writeWord(bits+36,direct?(pixelSize==16?5:8):pixelSize);memory.writeLong(bits+42,ctab); // cmpCount, cmpSize, pmTable
			}else{bits=memory.allocate(14,2);blocks.add(bits);memory.writeLong(bits,pixels);memory.writeWord(bits+4,rowBytes);writeRect(bits+6,bounds);}
			int rects=memory.allocate(16,2);blocks.add(rects);writeRect(rects,sourceRect);writeRect(rects+8,target);
			int maskRgn=0;if(mask!=null){maskRgn=macApi.createHandle(new byte[10]);handles.add(maskRgn);writeRegion(maskRgn,QdRegion.rect(mask.top,mask.left,mask.bottom,mask.right));}
			M68kCpu cpu=pictureCpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
			try{cpu.pushLong(bits);cpu.pushLong(rects);cpu.pushLong(rects+8);cpu.pushWord(mode);cpu.pushLong(maskRgn);cpu.callPascal(proc,18);}
			catch(M68kCpuException e){throw e;}catch(M68kException e){throw new M68kCpuException(proc,"bitsProc failed: "+e.getMessage());}
			finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
			if(Mac68kApi.TRACE)System.err.println("[68K] PICT bits through the port's bitsProc $"+Integer.toHexString(proc)+" mode "+mode);
			return true;
		}finally{for(int h:handles)macApi.disposeHandleAndFree(h);for(int b:blocks)memory.free(b);}
	}
	/** A PicComment of a picture played into a port whose commentProc is the program's own (IM I-189): the procedure gets the kind,
	 *  the data size and a Handle to the data. SuperPaint 3.0 tells the layers of a document it reads apart by its comments. */
	private void pictureCommentProc(int kind,int at,int size) throws M68kCpuException {
		int proc=pictureBottleneck(9);if(proc==0)return;
		int handle=size>0?macApi.createHandle(memoryBytes(at,size)):0;
		try{callBottleneck(proc,new int[]{kind,size},new int[]{handle});}finally{if(handle!=0)macApi.disposeHandle(handle);}
		if(Mac68kApi.TRACE)System.err.println("[68K] PICT comment "+kind+" ("+size+" bytes) through the port's commentProc $"+Integer.toHexString(proc));
	}
	private boolean pictureLineProc(int h1,int v1,int h2,int v2) throws M68kCpuException {
		int proc=pictureBottleneck(1);if(proc==0)return false;
		writePoint(currentPort+PN_LOC,v1,h1);callBottleneck(proc,new int[0],new int[]{v2<<16|h2&0xffff});writePoint(currentPort+PN_LOC,v2,h2);return true;
	}
	/** Calls a bottleneck procedure with word arguments followed by long arguments (Pascal order), the program's registers kept. */
	private void callBottleneck(int proc,int[] words,int[] longs) throws M68kCpuException {
		M68kCpu cpu=pictureCpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr;
		try{for(int w:words)cpu.pushWord(w);for(int l:longs)cpu.pushLong(l);cpu.callPascal(proc,words.length*2+longs.length*4);}
		catch(M68kCpuException e){throw e;}catch(M68kException e){throw new M68kCpuException(proc,"bottleneck procedure failed: "+e.getMessage());}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
	}

	/**
	 * Plays a Picture (the bytes of a PICT resource or scrap item) into a temporary 32-bit GWorld the size of its frame
	 * and returns the pixels, so the Scrap Manager can hand a program's PICT to the host clipboard. Text, patterns and
	 * bitmaps come out exactly as DrawPicture would draw them in a window. Returns null for an empty frame, a frame too
	 * large for an offscreen buffer, or a picture the player rejects (the program's scrap keeps the picture either way).
	 */
	BufferedImage renderPicture(byte[] picture) throws M68kCpuException {return renderPictureAtDepth(picture,32);}
	/** Test aid: the picture played into an offscreen world of the given depth (1, 8 or 32), read back as RGB. */
	BufferedImage renderPictureAtDepth(byte[] picture,int depth) throws M68kCpuException {
		if(picture==null||picture.length<11)return null;
		Rect frame=new Rect((short)((picture[2]&0xff)<<8|picture[3]&0xff),(short)((picture[4]&0xff)<<8|picture[5]&0xff),(short)((picture[6]&0xff)<<8|picture[7]&0xff),(short)((picture[8]&0xff)<<8|picture[9]&0xff));
		int width=frame.right-frame.left,height=frame.bottom-frame.top;if(width<=0||height<=0)return null;
		Rect bounds=new Rect(0,0,height,width);if(!validGWorldBounds(bounds,depth))return null;
		int device=ensureMainGDevice(),savedPort=currentPort,savedDevice=currentGDevice;
		GWorldState world=createGWorld(depth,bounds,0,device,0);fillWhiteOpaque(world);int handle=macApi.createHandle(picture);
		try {
			currentGDevice=device;setCurrentPort(world.port);
			playPicture(memory.readLong(handle),picture.length,frame,bounds);
			BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
			int[] pixels=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
			Bitmap bitmap=readCurrentBitmap();
			for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels[y*width+x]=~bitmap.getInkRgb(x,y)&0xffffff;
			return image;
		} finally {
			setCurrentPort(savedPort);currentGDevice=savedDevice;macApi.disposeHandleAndFree(handle);
			disposeGWorldState(world); // frees the world's Handles (pixels, PixMap, regions, grafVars), its offscreen device and the port
		}
	}

	private void recordPictureLine(int h1,int v1,int h2,int v2) {
		if(!recordingPicture())return;recordPicturePenState();pictureOpcode(0x20);picturePoint(h1,v1);picturePoint(h2,v2);
	}
	/** The pen size and the clip rectangle the open picture has recorded (null: none yet, the picture's wide-open clip). */
	private int picturePenH=1,picturePenV=1;private Rect pictureClip;
	/**
	 * Before recorded drawing, the port's pen size and clip where they differ from what the picture holds, as QuickDraw compares
	 * the port with the picture's state: they may be set before OpenPicture (UxProgressBar sets PenSize 8,1 and ClipRect, then
	 * records its barber-pole stripes: they played back as hairlines spilling out of the bar).
	 */
	private void recordPicturePenState(){
		if(currentPort==0||playingPicture)return;
		try{
			int v=(short)memory.readWord(currentPort+PN_SIZE),h=(short)memory.readWord(currentPort+PN_SIZE+2);
			if(h!=picturePenH||v!=picturePenV)recordPicturePointState(0x07,h,v);
			Rect clip=currentClip();boolean wide=clip.top<=-32767&&clip.left<=-32767&&clip.bottom>=32767&&clip.right>=32767;
			if(pictureClip==null?!wide:!pictureClip.equals(clip))recordPictureClip(clip);
		}catch(M68kCpuException e){}
	}

	/** Text drawn by the host text path while a picture records: the LongText opcode at the pen position. */
	void recordText(int h,int v,byte[] text){if(!recordingPicture()||text.length==0)return;int[] c=new int[text.length];for(int i=0;i<c.length;i++)c[i]=text[i]&0xff;recordPictureText(h,v,c);}
	/** The text state the open picture has recorded so far (picSave's txFont/txFace/txMode/txSize/spExtra): font, face, mode,
	 *  size, space extra. */
	private final int[] pictureTextState=new int[5];
	private void resetPictureTextState(){pictureTextState[0]=0;pictureTextState[1]=0;pictureTextState[2]=1;pictureTextState[3]=0;pictureTextState[4]=0;}
	/**
	 * Before recorded text, opcodes for the port's text state where it differs from what the picture holds, as QuickDraw's text
	 * recording compares the port with picSave: programs store txFont/txSize/txFace into the port directly (Word 5.1 records each
	 * page for Print Preview that way; without this every line played back in the default font and size).
	 */
	private void recordPictureTextState() throws M68kCpuException {
		if(currentPort==0)return;
		int font=memory.readWord(currentPort+TX_FONT),face=memory.readByte(currentPort+TX_FACE),mode=memory.readWord(currentPort+TX_MODE);
		int size=memory.readWord(currentPort+TX_SIZE),extra=memory.readLong(currentPort+SP_EXTRA);
		if(font!=pictureTextState[0])recordPictureWordState(0x03,font);
		if(face!=pictureTextState[1])recordPictureByteState(0x04,face);
		if(mode!=pictureTextState[2])recordPictureWordState(0x05,mode);
		if(extra!=pictureTextState[4])recordPictureLongState(0x06,extra);
		if(size!=pictureTextState[3])recordPictureWordState(0x0d,size);
	}
	private void recordPictureText(int h,int v,int[] characters) {
		if(!recordingPicture())return;int offset=0,currentH=h;
		try{recordPictureTextState();}catch(M68kCpuException e){}
		while(offset<characters.length){int count=Math.min(255,characters.length-offset);pictureOpcode(0x28);picturePoint(currentH,v);pictureByte(count);
			for(int i=0;i<count;i++)pictureByte(characters[offset+i]);offset+=count;currentH+=count*safeCharacterWidth();}
	}

	private void recordPictureShape(int opcode,Rect rect,int startAngle,int arcAngle,int pattern,int verb) throws M68kCpuException {
		if(!recordingPicture())return;recordPicturePenState();if(verb==4)recordPicturePattern(0x0a,pattern);pictureOpcode(opcode);pictureRect(rect);
		if((opcode&0xf0)==0x60){pictureWord(startAngle);pictureWord(arcAngle);}
	}

	private void recordPictureOvalSize(int width,int height) {
		if(!recordingPicture())return;pictureOpcode(0x0b);picturePoint(width,height);
	}

	private void recordPicturePolygon(int opcode,int handle,int pattern,int verb) throws M68kCpuException {
		if(!recordingPicture())return;recordPicturePenState();if(verb==4)recordPicturePattern(0x0a,pattern);pictureOpcode(opcode);pictureHandleData(handle);
	}

	private void recordPictureRegion(int opcode,int handle,int pattern,int verb) throws M68kCpuException {
		if(!recordingPicture())return;recordPicturePenState();if(verb==4)recordPicturePattern(0x0a,pattern);pictureOpcode(opcode);pictureHandleData(handle);
	}

	private void recordPictureBits(Bitmap source,Rect sourceRect,Rect destinationRect,int mode,int maskRegion) throws M68kCpuException {
		if(!recordingPicture())return;int width=sourceRect.right-sourceRect.left,height=sourceRect.bottom-sourceRect.top;
		if(width<=0||height<=0)return;
		if(pictureVersion2&&source.pixelSize>1){recordPictureColorBits(source,sourceRect,destinationRect,mode,maskRegion);return;}
		int rowBytes=((width+15)/16)*2;pictureOpcode(maskRegion==0?0x90:0x91);
		pictureWord(rowBytes);pictureRect(sourceRect);pictureRect(sourceRect);pictureRect(destinationRect);pictureWord(mode);
		if(maskRegion!=0)pictureHandleData(maskRegion);
		for(int v=sourceRect.top;v<sourceRect.bottom;v++)for(int column=0;column<rowBytes;column++) {
			int value=0;for(int bit=0;bit<8;bit++){int h=sourceRect.left+column*8+bit;if(h<sourceRect.right&&source.get(h,v))value|=0x80>>>bit;}
			pictureByte(value);
		}
	}

	/**
	 * An icon PlotIcon/PlotIconID/PlotCIcon draws into the port's pixels is recorded too while a picture is open there, as the
	 * CopyBits of its bits (srcCopy into the rectangle; a masked icon through its mask as the mask region, BitsRgn/DirectBitsRgn):
	 * Rinaldi's ICNToICON plotted an icon into OpenCPicture and got a picture of a header and EndPic only. Black-and-white icons
	 * are 1-bit BitsRect/Rgn, colour ones a version 2 picture's DirectBitsRect/Rgn.
	 */
	void recordPictureImage(BufferedImage image,int top,int left,int bottom,int right) throws M68kCpuException {
		if(!recordingPicture()||image==null)return;
		int w=image.getWidth(),h=image.getHeight(),dw=right-left,dh=bottom-top;if(w<=0||h<=0||dw<=0||dh<=0)return;
		boolean oneBit=true,masked=false;
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int argb=image.getRGB(x,y);if(argb>>>24==0){masked=true;continue;}int rgb=argb&0xffffff;if(rgb!=0&&rgb!=0xffffff)oneBit=false;}
		int maskHandle=0;
		if(masked){QdRegion mask=QdRegion.empty(); // the opaque pixels, in destination coordinates
			for(int y=0;y<dh;y++){int sy=y*h/dh;for(int x=0;x<dw;){int sx=x*w/dw;if(image.getRGB(sx,sy)>>>24==0){x++;continue;}int end=x;while(end<dw&&image.getRGB(end*w/dw,sy)>>>24!=0)end++;
				mask=QdRegion.combine(mask,QdRegion.rect(top+y,left+x,top+y+1,left+end),QdRegion.UNION);x=end;}}
			maskHandle=macApi.createHandle(new byte[10]);writeRegion(maskHandle,mask);}
		try{
			Rect source=new Rect(0,0,h,w),destination=new Rect(top,left,bottom,right);
			if(oneBit||!pictureVersion2){
				int rowBytes=((w+15)/16)*2;pictureOpcode(maskHandle==0?0x90:0x91);
				pictureWord(rowBytes);pictureRect(source);pictureRect(source);pictureRect(destination);pictureWord(0); // srcCopy
				if(maskHandle!=0)pictureHandleData(maskHandle);
				for(int y=0;y<h;y++)for(int column=0;column<rowBytes;column++){int value=0;
					for(int bit=0;bit<8;bit++){int x=column*8+bit;if(x>=w)continue;int argb=image.getRGB(x,y),rgb=argb&0xffffff;
						if(argb>>>24!=0&&((rgb>>16&255)*30+(rgb>>8&255)*59+(rgb&255)*11)/100<128)value|=0x80>>>bit;}
					pictureByte(value);}
			}else{
				int rowBytes=w*4;
				pictureOpcode(maskHandle==0?0x9a:0x9b);pictureLong(0x000000ff);
				pictureWord(0x8000|rowBytes);pictureRect(source);pictureWord(0);pictureWord(1);pictureLong(0);pictureLong(0x00480000);pictureLong(0x00480000);
				pictureWord(16);pictureWord(32);pictureWord(3);pictureWord(8);pictureLong(0);pictureLong(0);pictureLong(0);
				pictureRect(source);pictureRect(destination);pictureWord(0);
				if(maskHandle!=0)pictureHandleData(maskHandle);
				for(int y=0;y<h;y++)for(int x=0;x<w;x++){int argb=image.getRGB(x,y);pictureLong(argb>>>24==0?0xffffff:argb&0xffffff);}
			}
		}finally{if(maskHandle!=0)macApi.disposeHandleAndFree(maskHandle);}
	}

	/** A colour source of CopyBits in a version 2 picture: DirectBitsRect/Rgn ($009A/$009B), 32-bit unpacked. */
	private void recordPictureColorBits(Bitmap source,Rect sourceRect,Rect destinationRect,int mode,int maskRegion) throws M68kCpuException {
		int width=sourceRect.right-sourceRect.left,height=sourceRect.bottom-sourceRect.top,rowBytes=width*4;
		if((long)rowBytes*height>16_777_216L||rowBytes>0x3fff)return;
		pictureOpcode(maskRegion==0?0x9a:0x9b);pictureLong(0x000000ff);
		pictureWord(0x8000|rowBytes);pictureRect(sourceRect);pictureWord(0);pictureWord(1);pictureLong(0);pictureLong(0x00480000);pictureLong(0x00480000);
		pictureWord(16);pictureWord(32);pictureWord(3);pictureWord(8);pictureLong(0);pictureLong(0);pictureLong(0); // pixelType, pixelSize, cmpCount, cmpSize, planeBytes, pmTable, pmReserved
		pictureRect(sourceRect);pictureRect(destinationRect);pictureWord(mode);
		if(maskRegion!=0)pictureHandleData(maskRegion);
		for(int v=sourceRect.top;v<sourceRect.bottom;v++)for(int h=sourceRect.left;h<sourceRect.right;h++)pictureLong(source.contains(h,v)?~source.getInkRgb(h,v)&0xffffff:0xffffff);
	}

	private void recordPictureClip(Rect rect) {
		if(!recordingPicture())return;pictureOpcode(0x01);pictureWord(10);pictureRect(rect);pictureClip=rect;
	}

	private void recordPictureByteState(int opcode,int value) {if(recordingPicture()){pictureOpcode(opcode);pictureByte(value);if(opcode==0x04)pictureTextState[1]=value&0xff;}}
	private void recordPictureWordState(int opcode,int value) {if(recordingPicture()){pictureOpcode(opcode);pictureWord(value);
		if(opcode==0x03)pictureTextState[0]=value&0xffff;else if(opcode==0x05)pictureTextState[2]=value&0xffff;else if(opcode==0x0d)pictureTextState[3]=value&0xffff;}}
	private void recordPictureLongState(int opcode,int value) {if(recordingPicture()){pictureOpcode(opcode);pictureLong(value);if(opcode==0x06)pictureTextState[4]=value;}}
	private void recordPicturePointState(int opcode,int h,int v) {if(recordingPicture()){pictureOpcode(opcode);picturePoint(h,v);if(opcode==0x07){picturePenH=h;picturePenV=v;}}}
	private void recordPicturePattern(int opcode,int pointer) throws M68kCpuException {
		if(!recordingPicture()||pointer==0)return;pictureOpcode(opcode);for(int i=0;i<8;i++)pictureByte(memory.readByte(pointer+i));
	}
	/** RGBForeColor/RGBBackColor while a picture records: RGBFgCol $1A / RGBBkCol $1B. */
	private void recordPictureRgb(int opcode,int color) throws M68kCpuException {
		if(!recordingPicture()||color==0)return;
		if(!pictureVersion2){ // a version 1 picture has only the eight classic colours: FgColor $0E / BkColor $0F with the nearest
			int r=memory.readWord(color),g=memory.readWord(color+2),b=memory.readWord(color+4);
			pictureOpcode(opcode==0x1a?0x0e:0x0f);pictureLong(nearestClassicColor(r,g,b));return;}
		pictureOpcode(opcode);for(int i=0;i<6;i++)pictureByte(memory.readByte(color+i));
	}
	/** The classic QuickDraw colour (blackColor 33 … yellowColor 69) nearest an RGB colour of 16-bit components. */
	private static int nearestClassicColor(int r,int g,int b){
		int[][] colors={{33,0,0,0},{30,0xffff,0xffff,0xffff},{205,0xffff,0,0},{341,0,0xffff,0},{409,0,0,0xffff},{273,0,0xffff,0xffff},{137,0xffff,0,0xffff},{69,0xffff,0xffff,0}};
		int best=33;long bestDistance=Long.MAX_VALUE;
		for(int[] c:colors){long dr=r-c[1],dg=g-c[2],db=b-c[3];long d=dr*dr+dg*dg+db*db;if(d<bestDistance){bestDistance=d;best=c[0];}}
		return best;
	}
	/**
	 * PenPixPat/BackPixPat/FillC* while a version 2 picture records: PnPixPat $13 / BkPixPat $12 / FillPixPat $14 with the
	 * pattern's data (type 1: PixMap without baseAddr, colour table, unpacked pixels; type 2: RGB colour) - a version 1
	 * recording keeps its 1-bit pat1Data through the plain pattern opcodes.
	 */
	private void recordPicturePixPat(int opcode,int handle) throws M68kCpuException {
		if(!recordingPicture()||handle==0)return;int data=memory.readLong(handle);if(data==0||macApi.handleSize(handle)<28)return;
		int type=memory.readWord(data);
		if(!pictureVersion2||type==0||type==3){recordPicturePattern(opcode==0x12?0x02:opcode==0x13?0x09:0x0a,data+20);return;}
		if(type==2){pictureOpcode(opcode);pictureWord(2);for(int i=0;i<8;i++)pictureByte(memory.readByte(data+20+i));for(int i=0;i<6;i++)pictureByte(memory.readByte(data+20+i));return;}
		int map=memory.readLong(data+2),pixels=memory.readLong(data+6);if(map==0||pixels==0)return;int mapData=memory.readLong(map),pixelData=memory.readLong(pixels);if(mapData==0||pixelData==0)return;
		int rowBytes=memory.readWord(mapData+4)&0x3fff;Rect bounds=readRect(mapData+6);int height=bounds.bottom-bounds.top;if(rowBytes<=0||height<=0||(long)rowBytes*height>macApi.handleSize(pixels))return;
		int table=memory.readLong(mapData+42),tableData=table==0?0:memory.readLong(table);int last=tableData==0?-1:(short)memory.readWord(tableData+6);if(last<-1||last>255)last=-1;
		pictureOpcode(opcode);pictureWord(1);for(int i=0;i<8;i++)pictureByte(memory.readByte(data+20+i));
		for(int i=4;i<50;i++)pictureByte(i==16||i==17?(i==17?1:0):memory.readByte(mapData+i)); // packType 1: rows unpacked
		pictureLong(tableData==0?0:memory.readLong(tableData));pictureWord(0);pictureWord(last);
		for(int i=0;i<=last;i++)for(int k=0;k<8;k++)pictureByte(memory.readByte(tableData+8+i*8+k));
		for(int i=0;i<rowBytes*height;i++)pictureByte(memory.readByte(pixelData+i));
	}

	private void pictureHandleData(int handle) throws M68kCpuException {
		int data=memory.readLong(handle),size=data==0?0:memory.readWord(data);for(int i=0;i<size;i++)pictureByte(memory.readByte(data+i));
	}

	/**
	 * Drawing goes into the open picture when the current port's picSave holds it, as in Color QuickDraw: OpenPicture sets the
	 * port's picSave and ClosePicture clears it. SuperPaint 3.0 spools its save through a putPicProc that draws a progress bar
	 * in another window (recorded too, every spooled chunk recorded the next one: a save that never ended), and reading a
	 * document it copies picSave into a second port and draws the draw layer's pictures there (the pictures came out empty
	 * when only the port that opened the picture recorded).
	 */
	private boolean recordingPicture() {
		if(openPicture==0||pictureData==null||playingPicture&&openPicture==playbackRecording||spoolClosing)return false;
		if(currentPort==0)return pictureRecordingPort==0;
		try{return memory.readLong(currentPort+PIC_SAVE)==openPicture;}catch(M68kCpuException e){return false;}
	}
	/** GrafPort/CGrafPort picSave. */
	private static final int PIC_SAVE=92;
	boolean pictureIsOpen() {return openPicture!=0&&pictureData!=null&&!(playingPicture&&openPicture==playbackRecording);}
	/**
	 * SwapPortPicSaveHandle / SwapPortRegionSaveHandle / SwapPortPolySaveHandle (CarbonLib), {@code kind} 0 picture, 1 region, 2
	 * polygon: the definition open in {@code port} is suspended - drawing stops recording into it - and a token for it returned
	 * (the picture's or polygon's Handle, a Handle standing for the region), 0 when none is open there; a {@code token} from an
	 * earlier swap resumes its definition. The definitions live here; of the port's fields only picSave follows (it decides what records).
	 */
	int swapDefinition(int port,int kind,int token) throws M68kCpuException {
		int old=0;
		if(kind==0&&openPicture!=0&&pictureData!=null&&pictureRecordingPort==port){old=openPicture;suspendedDefinitions.put(old,new Object[]{0,pictureData});if(port!=0&&memory.readLong(port+PIC_SAVE)==old)memory.writeLong(port+PIC_SAVE,0);openPicture=0;pictureData=null;}
		else if(kind==1&&regionOpen&&openRegionPort==port&&port!=0){old=macApi.createHandle(new byte[4]);suspendedDefinitions.put(old,new Object[]{1,port});openRegionPort=0;}
		else if(kind==2&&openPolygon!=0){old=openPolygon;suspendedDefinitions.put(old,new Object[]{2,new ArrayList<QdPoint>(polygonPoints)});openPolygon=0;polygonPoints.clear();}
		Object[] s=token==0?null:suspendedDefinitions.get(token);
		if(s!=null&&(Integer)s[0]==kind){suspendedDefinitions.remove(token);
			if(kind==0){openPicture=token;pictureData=(ByteArrayOutputStream)s[1];pictureRecordingPort=port;if(port!=0)memory.writeLong(port+PIC_SAVE,token);}
			else if(kind==1){openRegionPort=(Integer)s[1];macApi.disposeHandle(token);}
			else{openPolygon=token;polygonPoints.clear();@SuppressWarnings("unchecked") List<QdPoint> p=(List<QdPoint>)s[1];polygonPoints.addAll(p);}}
		return old;
	}
	private final java.util.Map<Integer,Object[]> suspendedDefinitions=new java.util.HashMap<Integer,Object[]>();
	/** Whether {@code token} stands for a definition {@link #swapDefinition} suspended. */
	boolean isSuspendedDefinition(int token){return token!=0&&suspendedDefinitions.containsKey(token);}
	/**
	 * Records the current port's content (its portRect) into the open picture as one bitmap, so drawing done outside
	 * QuickDraw's own traps still ends up in a recorded picture. The Dialog Manager renders items with the host's Java2D
	 * (buttons, edit frames, icons); when a program records DrawDialog into a picture and plays it back scaled, an
	 * DLOG/DITL editor draws its mini-screen preview exactly this way - the picture would otherwise be empty. No effect
	 * when no picture is open.
	 */
	void recordPortSnapshot() throws M68kCpuException {
		if(!recordingPicture()||currentPort==0)return;
		Bitmap bitmap=readCurrentBitmap();Rect rect=intersection(readRect(currentPort+PORT_RECT),bitmap.bounds);
		if(rect.empty())return;
		recordPictureBits(bitmap,rect,rect,0,0);
	}
	/**
	 * As {@link #recordPortSnapshot()}, for one rectangle of the current port (local coordinates): a control the Control Manager
	 * drew with Java2D. QuickHelp records its toolbar (Appearance bevel buttons) into a picture, plays that into a GWorld and
	 * copies it over the window - without this the buttons came out blank.
	 */
	void recordPortSnapshot(int top,int left,int bottom,int right) throws M68kCpuException {
		if(!recordingPicture()||currentPort==0)return;
		Bitmap bitmap=readCurrentBitmap();Rect rect=intersection(intersection(new Rect(top,left,bottom,right),readRect(currentPort+PORT_RECT)),bitmap.bounds);
		if(rect.empty())return;
		recordPictureBits(bitmap,rect,rect,0,0);
	}
	private int safeCharacterWidth() {try{return characterWidth();}catch(M68kCpuException e){return 1;}}
	private void pictureByte(int value) {pictureData.write(value&0xff);}
	private void pictureWord(int value) {pictureByte(value>>>8);pictureByte(value);}
	private void pictureLong(int value) {pictureWord(value>>>16);pictureWord(value);}
	private void picturePoint(int h,int v) {pictureWord(v);pictureWord(h);}
	private void pictureRect(Rect rect) {pictureWord(rect.top);pictureWord(rect.left);pictureWord(rect.bottom);pictureWord(rect.right);}

	/**
	 * The source rectangle of an extended version 2 picture (IM: Imaging With QuickDraw, "Picture Opcodes"): after the
	 * $0011 $02FF version opcode comes HeaderOp $0C00 with 24 bytes - version $FFFE, reserved, hRes and vRes (Fixed dpi)
	 * and the picture's bounding rectangle AT THAT RESOLUTION. The drawing commands are in that rectangle's coordinates,
	 * while picFrame is the same rectangle scaled to 72 dpi, so DrawPicture maps srcRect (not picFrame) onto its
	 * destination: Barney Carnage 2.0 stores its screens as 288 dpi pictures (picFrame 359x149, srcRect 1436x596) and
	 * drew four times too large and off-screen - a blank white window - while the frame was taken as the source space.
	 * Null for the plain $FFFF header (whose Fixed bounding box is picFrame) and for anything that does not parse.
	 */
	private Rect extendedSourceRect(int data,int size) throws M68kCpuException {
		if(size<40||readPictureWord(data+14)!=0x0c00||readPictureWord(data+16)!=0xfffe)return null;
		Rect source=readPictureRect(data+28);
		return source.right>source.left&&source.bottom>source.top?source:null;
	}

	/** A picture must identify its version before drawing. Allow the NOP padding used by Infini-D and the
	 * comments before VersionOp in MacBench's pictures, but do not interpret other data as version 1 commands.
	 * MacBench's CD Script G includes a header-only picture (picSize 380); the next script command is not PICT data. */
	private boolean hasPictureVersion(int data,int size) throws M68kCpuException {
		int cursor=10;
		while(cursor<size){
			int op=memory.readByte(data+cursor++);
			if(op==0)continue;
			if(op==0x11){
				if(cursor>=size)return false;
				int version=memory.readByte(data+cursor++);
				return version==1||version==2&&cursor<size&&memory.readByte(data+cursor)==0xff;
			}
			if(op==0xa0){if(size-cursor<2)return false;cursor+=2;continue;}
			if(op==0xa1){if(size-cursor<4)return false;int count=readPictureWord(data+cursor+2);cursor+=4;if(count>size-cursor)return false;cursor+=count;continue;}
			return false;
		}
		return false;
	}
	private void playPicture(int data,int size,Rect frame,Rect destination) throws M68kCpuException {
		if(!hasPictureVersion(data,size))return;
		byte[] savedPort=new byte[GRAF_PORT_SIZE];for(int i=0;i<GRAF_PORT_SIZE;i++)savedPort[i]=(byte)memory.readByte(currentPort+i);
		PortState portState=state();int savedOriginV=portState.originV,savedOriginH=portState.originH;Rect savedClip=currentClip(),savedStateClip=portState.clip;portState.clip=savedClip;QdRegion savedPictureClip=portState.pictureClip;
		int savedFgIndex=portState.fgIndex,savedBkIndex=portState.bkIndex,savedFgValue=portState.fgValue,savedBkValue=portState.bkValue;
		int cursor=data+10,end=data+size,ovalWidth=0,ovalHeight=0,lastH=0,lastV=0;Rect[] lastRects=new Rect[4];
		// DHText/DVText/DHDVText are offsets from where the LAST TEXT started, a position the recorder keeps apart from the pen:
		// a picture opens with a ShortLine at (-1,-32767) (the recorder's pen), and eDOC pages then place every indented line
		// with DHDVText - taken from the pen they landed 32000 pixels to the left.
		int textH=0,textV=0;Rect clipBeforeQuickTime=null; // the clip a QuickTime image opcode emptied (see 0x8200)
		pictureMatte=null;
		java.util.List<Integer> patternHandles=new java.util.ArrayList<>(); // PixPats made for the picture's pattern opcodes in a colour port; freed with the port restore
		java.util.Map<Integer,Integer> pictureFonts=new java.util.HashMap<Integer,Integer>(); // fontName opcodes: the picture's font ids -> this System's font numbers
		boolean version2=size>=14&&readPictureWord(data+10)==0x0011&&readPictureWord(data+12)==0x02ff;
		if(version2){Rect source=extendedSourceRect(data,size);if(source!=null)frame=source;} // an extended version 2 picture draws in its header's srcRect, not in picFrame
		boolean wasPlaying=playingPicture;int wasRecording=playbackRecording;
		playingPicture=true;playbackRecording=openPicture;
		try {
			resetPictureState(patternHandles);
			while(cursor<end){int opcode;if(version2){opcode=readPictureWord(cursor);cursor+=2;}else opcode=memory.readByte(cursor++);
				if(opcode==0xff)break;
				if(survey!=null)survey.count(opcode);
				if(PICTURE_TRACE)System.err.println("[68K] picture opcode $"+Integer.toHexString(opcode)+" at "+(cursor-data-(version2?2:1))+" clip "+portState.clip+" pen "+(short)memory.readWord(currentPort+PN_LOC+2)+","+(short)memory.readWord(currentPort+PN_LOC));
				switch(opcode) {
					case 0x00:break;
					case 0x01:{int regionSize=readPictureWord(cursor);if(regionSize<10)throw pictureError(cursor,"invalid clip region");
						Rect clip=mapRect(readPictureRect(cursor+2),frame,destination);portState.clip=intersection(savedClip,clip);clipBeforeQuickTime=null;
						// a non-rectangular clip (an oval, a rounded frame) clips the shapes as a region when the picture is drawn at its own size
						portState.pictureClip=null;
						if(regionSize>10&&frame.right-frame.left==destination.right-destination.left&&frame.bottom-frame.top==destination.bottom-destination.top)
							portState.pictureClip=QdRegion.decode(memory,cursor).offset(destination.left-frame.left,destination.top-frame.top);
						cursor+=regionSize;break;}
					case 0x02:{int h=storePattern(BK_PAT,cursor);if(h!=0)patternHandles.add(h);cursor+=8;break;}
					case 0x03:{int id=readPictureWord(cursor);cursor+=2;Integer mapped=pictureFonts.get(id);memory.writeWord(currentPort+TX_FONT,mapped!=null?mapped:id);break;}
					case 0x04:memory.writeByte(currentPort+TX_FACE,memory.readByte(cursor++));break;
					case 0x05:memory.writeWord(currentPort+TX_MODE,readPictureWord(cursor));cursor+=2;break;
					case 0x06:memory.writeLong(currentPort+SP_EXTRA,readPictureLong(cursor));cursor+=4;break;
					case 0x07:{int v=(short)readPictureWord(cursor),h=(short)readPictureWord(cursor+2);cursor+=4;
						writePoint(currentPort+PN_SIZE,scaleV(v,frame,destination),scaleH(h,frame,destination));break;}
					case 0x08:memory.writeWord(currentPort+PN_MODE,readPictureWord(cursor));cursor+=2;break;
					case 0x09:{int h=storePattern(PN_PAT,cursor);if(h!=0)patternHandles.add(h);cursor+=8;break;}
					case 0x0a:{int h=storePattern(FILL_PAT,cursor);if(h!=0)patternHandles.add(h);cursor+=8;break;}
					case 0x0b:ovalHeight=(short)readPictureWord(cursor);ovalWidth=(short)readPictureWord(cursor+2);cursor+=4;break;
					case 0x0c:{ // Origin dh, dv: recorded by a SetOrigin while the picture was open; what follows is in the moved system
						// (Ingemar's skiing game's title picture offsets every text block this way - they all landed on its first line)
						int dh=(short)readPictureWord(cursor),dv=(short)readPictureWord(cursor+2);cursor+=4;
						frame=new Rect(frame.top+dv,frame.left+dh,frame.bottom+dv,frame.right+dh);break;}
					case 0x0d:{int value=(short)readPictureWord(cursor);cursor+=2;
						memory.writeWord(currentPort+TX_SIZE,Math.max(1,scaleV(value,frame,destination)));break;}
					// FgColor/BkColor: old-style colours as ForeColor/BackColor take them, not pixel values (see pokedColorValue); a colour
					// port gets their RGB as ForeColor gives it (a RecordPict of MakeBarXCMD's 385 played back black: only the eight
					// constants were seen through the fgColor field)
					case 0x0e:{int value=readPictureLong(cursor);cursor+=4;memory.writeLong(currentPort+FG_COLOR,value);oldStyleColorRgb(FG_COLOR,value);PortState ps=ports.get(currentPort);if(ps!=null)ps.fgValue=-1;break;}
					case 0x0f:{int value=readPictureLong(cursor);cursor+=4;memory.writeLong(currentPort+BK_COLOR,value);oldStyleColorRgb(BK_COLOR,value);PortState ps=ports.get(currentPort);if(ps!=null)ps.bkValue=-1;break;}
					case 0x10:cursor+=8;break;
					case 0x11: // VersionOp; a version 1 stream that reaches "$11 $02" goes on in words (the $FF after it is the alignment byte):
						// Infini-D 1.0.2 reserves 40 bytes after the file header instead of 10, so 30 NOPs precede its $0011 $02FF
						if(version2)cursor+=2;else if(memory.readByte(cursor++)==2)version2=true;
						break;
					case 0x12:case 0x13:case 0x14:{int patEnd=skipPicturePixPat(cursor);int h=playPicturePixPat(opcode==0x12?BK_PAT:opcode==0x13?PN_PAT:FILL_PAT,cursor,patEnd);if(h!=0)patternHandles.add(h);cursor=patEnd;break;}
					case 0x15:case 0x16:cursor+=2;break;
					// RGBFgCol/RGBBkCol act as RGBForeColor/RGBBackColor: an index colour the port had (PmForeColor) no longer wins.
					// DOCMaker's gradient buttons came out in the page's black and white, black behind their labels.
					case 0x1a:case 0x1b:if(isColorPort(currentPort)){boolean fore=opcode==0x1a;memory.copy(cursor,currentPort+(fore?36:42),6);
						if(portState!=null){if(fore)portState.fgIndex=-1;else portState.bkIndex=-1;}updatePortColorValue(fore);}cursor+=6;break;
					// HiliteColor / OpColor / DefHilite / HiliteMode: the port's rgbHiliteColor, rgbOpColor (arithmetic modes), HiliteRGB
					// again, and the next drawing as a highlight - as the picture's recorder set them
					case 0x1d:case 0x1f:{int vars=isColorPort(currentPort)?grafVarsOf(currentPort):0;int varsData=vars==0?0:memory.readLong(vars);
						if(varsData!=0)memory.copy(cursor,varsData+(opcode==0x1d?6:0),6);cursor+=6;break;}
					case 0x1e:{int vars=isColorPort(currentPort)?grafVarsOf(currentPort):0;int varsData=vars==0?0:memory.readLong(vars);if(varsData!=0)memory.copy(HILITE_RGB,varsData+6,6);break;}
					case 0x1c:memory.writeByte(HILITE_MODE,memory.readByte(HILITE_MODE)&0x7f);break;
					case 0x29:{int dh=memory.readByte(cursor),count=memory.readByte(cursor+1);cursor+=2;int[] text=readTextBytes(cursor,count);cursor+=count;
						textH+=dh;writePoint(currentPort+PN_LOC,mapV(textV,frame,destination),mapH(textH,frame,destination));rasterText(text);break;}
					case 0x2a:{int dv=memory.readByte(cursor),count=memory.readByte(cursor+1);cursor+=2;int[] text=readTextBytes(cursor,count);cursor+=count;
						textV+=dv;writePoint(currentPort+PN_LOC,mapV(textV,frame,destination),mapH(textH,frame,destination));rasterText(text);break;}
					case 0x2b:{int dh=memory.readByte(cursor),dv=memory.readByte(cursor+1),count=memory.readByte(cursor+2);cursor+=3;int[] text=readTextBytes(cursor,count);cursor+=count;
						textH+=dh;textV+=dv;writePoint(currentPort+PN_LOC,mapV(textV,frame,destination),mapH(textH,frame,destination));rasterText(text);break;}
					case 0x2c:{int recordSize=readPictureWord(cursor);if(recordSize>=3){ // fontName: dataLength, oldFontID, name - the id the picture's TxFont opcodes use
							int id=readPictureWord(cursor+2),length=Math.min(memory.readByte(cursor+4),recordSize-3);byte[] name=memory.readBytes(cursor+5,length);
							int number=Fonts68k.numberOf(StackTextEncoding.ROMAN.decode(name));if(number!=0||id==0)pictureFonts.put(id,number);}
						cursor+=2+recordSize;break;}
					case 0x2e:{int recordSize=readPictureWord(cursor);cursor+=2+recordSize;break;}
					case 0x2d:cursor+=10;break;
					case 0xa0:pictureCommentProc(readPictureWord(cursor),cursor,0);cursor+=2;break;
					case 0xa1:{int commentSize=readPictureWord(cursor+2);if(commentSize<0)throw pictureError(cursor,"invalid PICT comment length");pictureCommentProc(readPictureWord(cursor),cursor+4,commentSize);cursor+=4+commentSize;break;}
					case 0x20:{int v1=(short)readPictureWord(cursor),h1=(short)readPictureWord(cursor+2);
						int v2=(short)readPictureWord(cursor+4),h2=(short)readPictureWord(cursor+6);cursor+=8;
						pictureLine(mapH(h1,frame,destination),mapV(v1,frame,destination),mapH(h2,frame,destination),mapV(v2,frame,destination));
						lastH=h2;lastV=v2;break;}
					case 0x21:{int v=(short)readPictureWord(cursor),h=(short)readPictureWord(cursor+2);cursor+=4;
						pictureLine(mapH(lastH,frame,destination),mapV(lastV,frame,destination),mapH(h,frame,destination),mapV(v,frame,destination));lastH=h;lastV=v;break;}
					case 0x22:{int v=(short)readPictureWord(cursor),h=(short)readPictureWord(cursor+2);
						int dh=(byte)memory.readByte(cursor+4),dv=(byte)memory.readByte(cursor+5);cursor+=6;
						pictureLine(mapH(h,frame,destination),mapV(v,frame,destination),mapH(h+dh,frame,destination),mapV(v+dv,frame,destination));lastH=h+dh;lastV=v+dv;break;}
					case 0x23:{int dh=(byte)memory.readByte(cursor),dv=(byte)memory.readByte(cursor+1);cursor+=2;
						pictureLine(mapH(lastH,frame,destination),mapV(lastV,frame,destination),mapH(lastH+dh,frame,destination),mapV(lastV+dv,frame,destination));lastH+=dh;lastV+=dv;break;}
					case 0x28:{int v=(short)readPictureWord(cursor),h=(short)readPictureWord(cursor+2),count=memory.readByte(cursor+4);cursor+=5;
						int[] text=readTextBytes(cursor,count);cursor+=count;writePoint(currentPort+PN_LOC,mapV(v,frame,destination),mapH(h,frame,destination));rasterText(text);textH=h;textV=v;break;}
					default:
						if(opcode==0x90||opcode==0x91||opcode==0x98||opcode==0x99||opcode==0x9a||opcode==0x9b){cursor=playPictureBits(cursor,opcode,frame,destination);break;}
						if(opcode>=0x30&&opcode<=0x34){Rect rect=mapRect(readPictureRect(cursor),frame,destination);cursor+=8;lastRects[0]=rect;rasterRect(rect,opcode-0x30,fillSlot());break;}
						if(opcode>=0x38&&opcode<=0x3c){if(lastRects[0]!=null)rasterRect(lastRects[0],opcode-0x38,fillSlot());break;}
						if(opcode>=0x40&&opcode<=0x44){Rect rect=mapRect(readPictureRect(cursor),frame,destination);cursor+=8;lastRects[1]=rect;
							rasterRoundRect(rect,scaleH(ovalWidth,frame,destination),scaleV(ovalHeight,frame,destination),opcode-0x40,fillSlot());break;}
						if(opcode>=0x48&&opcode<=0x4c){if(lastRects[1]!=null)rasterRoundRect(lastRects[1],scaleH(ovalWidth,frame,destination),scaleV(ovalHeight,frame,destination),opcode-0x48,fillSlot());break;}
						if(opcode>=0x50&&opcode<=0x54){Rect rect=mapRect(readPictureRect(cursor),frame,destination);cursor+=8;lastRects[2]=rect;rasterOval(rect,opcode-0x50,fillSlot());break;}
						if(opcode>=0x58&&opcode<=0x5c){if(lastRects[2]!=null)rasterOval(lastRects[2],opcode-0x58,fillSlot());break;}
						if(opcode>=0x60&&opcode<=0x64){Rect rect=mapRect(readPictureRect(cursor),frame,destination);int start=(short)readPictureWord(cursor+8),arc=(short)readPictureWord(cursor+10);cursor+=12;
							lastRects[3]=rect;rasterArc(rect,start,arc,opcode-0x60,fillSlot());break;}
						if(opcode>=0x68&&opcode<=0x6c){int start=(short)readPictureWord(cursor),arc=(short)readPictureWord(cursor+2);cursor+=4;
							if(lastRects[3]!=null)rasterArc(lastRects[3],start,arc,opcode-0x68,fillSlot());break;}
						if(opcode>=0x70&&opcode<=0x74){int recordSize=readPictureWord(cursor);List<QdPoint> points=readPicturePolygon(cursor,recordSize,frame,destination);cursor+=recordSize;
							if(!picturePolygonProc(opcode-0x70,points))rasterPolygon(points,opcode-0x70,fillSlot());break;}
						if(opcode>=0x80&&opcode<=0x84){int recordSize=readPictureWord(cursor);if(recordSize<10)throw pictureError(cursor,"invalid picture region");
							QdRegion shape=mapRegion(QdRegion.decode(memory,cursor),frame,destination);cursor+=recordSize;rasterRegion(shape,opcode-0x80,fillSlot());break;}
						if(version2&&opcode>=0x0100&&opcode<=0x7fff){cursor+=(opcode>>>8)*2;break;}
						if(version2&&opcode>=0x8000&&opcode<=0x80ff)break;
						if(version2&&opcode>=0x8100){int length=readPictureLong(cursor);if(length<0)throw pictureError(cursor,"invalid PICT extended-opcode length");
							// After drawing its image QuickTime empties the clip until the picture's next clip opcode: what follows is
							// the placeholder for Macs without QuickTime (a 1-bit "QuickTime PICT" icon), which Movie Cleaner Lite's
							// splash drew stretched over its JPEG.
							// The next QuickTime image draws in the clip again (Movie Cleaner's splash is two JPEG bands in a row).
							if(opcode==0x8200){if(clipBeforeQuickTime!=null){portState.clip=clipBeforeQuickTime;clipBeforeQuickTime=null;}pictureMatte=null;
								if(playQuickTimePicture(cursor+4,length,frame,destination)){clipBeforeQuickTime=portState.clip;portState.clip=new Rect(0,0,0,0);}}
							else if(opcode==0x8201)readUncompressedQuickTime(cursor+4,length);
							cursor+=4+length;break;}
						throw pictureError(cursor-(version2?2:1),String.format("unsupported PICT%s opcode $%0"+(version2?"4":"2")+"X",version2?" v2":" v1",opcode));
				}
				if(version2&&(cursor&1)!=0)cursor++;
				// picSize is the low word of the length: a picture over 64 KB whose Handle does not say more (SndToy 7 points a
				// Handle of its own at the PICT data it keeps, 16-bit pixels 355x357) plays on to opEndPic as QuickDraw does.
				while(cursor>end&&size<0x10000&&end-data<0x1000000)end+=0x10000;
				if(cursor>end)throw pictureError(end,"PICT opcode extends beyond picSize");
			}
		} finally {
			for(int i=0;i<GRAF_PORT_SIZE;i++)memory.writeByte(currentPort+i,savedPort[i]);
			for(int h:patternHandles)macApi.disposeHandle(h);
			portState.originV=savedOriginV;portState.originH=savedOriginH;portState.clip=savedStateClip;portState.pictureClip=savedPictureClip;portState.fgIndex=savedFgIndex;portState.bkIndex=savedBkIndex;portState.fgValue=savedFgValue;portState.bkValue=savedBkValue;playingPicture=wasPlaying;playbackRecording=wasRecording;
		}
	}

	/**
	 * A PixPat opcode's data as the port's pattern: type 1 is rebuilt as a 'ppat'-layout block for {@link #expandPixPatResource},
	 * type 2 as an RGB pattern, type 0 as a plain pattern. Returns the PixPat Handle made (freed with the port restore), 0 if none.
	 */
	private int playPicturePixPat(int offset,int cursor,int end) throws M68kCpuException {
		int patType=readPictureWord(cursor);
		if(!isColorPort(currentPort)||patType==0){int h=storePattern(offset,cursor+2);return h;}
		if(offset==FILL_PAT)offset=COLOR_FILL_PIX_PAT;
		if(patType==2){byte[] pattern=new byte[28];putWord(pattern,0,2);for(int i=0;i<8;i++)pattern[20+i]=(byte)memory.readByte(cursor+2+i);for(int i=0;i<6;i++)pattern[20+i]=(byte)memory.readByte(cursor+10+i);
			int rgb=readRgb(cursor+10);int ink=~rgb&0xffffff;pattern[26]=pattern[27]=(byte)(ink==0?0:0xff);int h=macApi.createHandle(pattern);memory.writeLong(currentPort+offset,h);return h;}
		if(patType!=1)return 0;
		int mapStart=cursor+10,rowBytes=readPictureWord(mapStart)&0x3fff;Rect bounds=readPictureRect(mapStart+2);int packType=readPictureWord(mapStart+12);
		int tableStart=mapStart+46,count=(short)readPictureWord(tableStart+6)+1,pixelStart=tableStart+8+count*8,height=bounds.bottom-bounds.top;
		if(rowBytes<=0||height<=0||count<0||count>256)return 0;
		byte[] pixels=new byte[rowBytes*height];
		if(rowBytes<8||packType==1){if(pixelStart+pixels.length>end)return 0;for(int i=0;i<pixels.length;i++)pixels[i]=(byte)memory.readByte(pixelStart+i);}
		else{int at=pixelStart;for(int row=0;row<height;row++){int length=rowBytes>250?readPictureWord(at):memory.readByte(at);at+=rowBytes>250?2:1;byte[] line=new byte[rowBytes];decodeBytePackBits(at,at+length,line);System.arraycopy(line,0,pixels,row*rowBytes,rowBytes);at+=length;}}
		byte[] block=new byte[28+50+pixels.length+8+count*8];
		putWord(block,0,1);putLong(block,2,28);putLong(block,6,78);for(int i=0;i<8;i++)block[20+i]=(byte)memory.readByte(cursor+2+i);
		for(int i=4;i<50;i++)block[28+i]=(byte)memory.readByte(mapStart+i-4);putLong(block,28+42,78+pixels.length);putWord(block,28+16,0);
		System.arraycopy(pixels,0,block,78,pixels.length);
		for(int i=0;i<8+count*8;i++)block[78+pixels.length+i]=(byte)memory.readByte(tableStart+i);
		int h=macApi.createHandle(block);if(!expandPixPatResource(h)){macApi.disposeHandleAndFree(h);return 0;}
		memory.writeLong(currentPort+offset,h);return h;
	}
	/** -Dhz68k.pictrace=true logs every opcode the picture player executes. */
	private static final boolean PICTURE_TRACE=Boolean.getBoolean("hz68k.pictrace");
	private int skipPicturePixPat(int cursor) throws M68kCpuException {
		int patType=readPictureWord(cursor);cursor+=2+8;
		if(patType==2)return cursor+6;
		if(patType!=1)throw pictureError(cursor,"unsupported PICT PixPat type "+patType);
		int rowBytes=readPictureWord(cursor);Rect bounds=readPictureRect(cursor+2);int pixelSize=readPictureWord(cursor+26);
		int packType=readPictureWord(cursor+12);cursor+=46;
		int colorCount=readPictureWord(cursor+6)+1;cursor+=8+colorCount*8;
		int height=bounds.bottom-bounds.top,rowBytesValue=rowBytes&0x3fff;
		if(rowBytesValue<8||packType==1)cursor+=rowBytesValue*height;
		else for(int row=0;row<height;row++){int length=rowBytesValue>250?readPictureWord(cursor):memory.readByte(cursor);cursor+=(rowBytesValue>250?2:1)+length;}
		return cursor;
	}

	/** A picture line: the port's own lineProc when it has one, else drawn. */
	private void pictureLine(int h1,int v1,int h2,int v2) throws M68kCpuException {
		if(!pictureLineProc(h1,v1,h2,v2))rasterLine(h1,v1,h2,v2);
	}
	private List<QdPoint> readPicturePolygon(int pointer,int size,Rect frame,Rect destination) throws M68kCpuException {
		List<QdPoint> points=new ArrayList<QdPoint>();if(size<10)return points;int count=(size-10)/4;
		for(int i=0;i<count;i++){int point=pointer+10+i*4;points.add(new QdPoint(mapH((short)readPictureWord(point+2),frame,destination),
			mapV((short)readPictureWord(point),frame,destination)));}return points;
	}

	/**
	 * The state a picture's playback starts from: OpenPicture records a pen or text setting only where it differs from these
	 * defaults (patCopy, a 1x1 black pen, font 0 plain at size 0, srcOr), so drawing must begin with them, not with whatever the
	 * port had. DOCMaker's window was left in a notPat mode and its gradient buttons (lines of lighter and lighter patterns)
	 * came out inverted, black behind their labels. The port itself is restored when the picture ends.
	 */
	private void resetPictureState(java.util.List<Integer> patternHandles) throws M68kCpuException {
		memory.writeWord(currentPort+PN_MODE,8);memory.writeWord(currentPort+PN_SIZE,1);memory.writeWord(currentPort+PN_SIZE+2,1);
		int black=memory.allocate(8,2);
		// a new PixPat: the port's own must not be rewritten in place; the fill pattern starts black (an empty fillPixPat draws so)
		try{memory.fill(black,8,0xff);if(isColorPort(currentPort)){memory.writeLong(currentPort+PN_PAT,0);memory.writeLong(currentPort+COLOR_FILL_PIX_PAT,0);}
			int h=storePattern(PN_PAT,black);if(h!=0)patternHandles.add(h);}finally{memory.free(black);}
		memory.writeWord(currentPort+TX_FONT,0);memory.writeByte(currentPort+TX_FACE,0);memory.writeWord(currentPort+TX_MODE,1);
		memory.writeWord(currentPort+TX_SIZE,0);memory.writeLong(currentPort+SP_EXTRA,0);
	}

	private int pictureTableSeed;
	/**
	 * CompressedQuickTime (0x8200) / UncompressedQuickTime (0x8201) picture data: version, a 3x3 Fixed matrix, matte and
	 * mask sizes, the transfer mode and source rectangle, then an ImageDescription and the compressed image. The image is
	 * decoded on the host (JPEG/PNG/GIF/TIFF through ImageIO, 'raw ' and 'rle ' natively - see {@link QuickTimeImage68k})
	 * and drawn where the matrix puts the source rectangle. Codecs the host cannot decode leave the area untouched.
	 */
	private boolean playQuickTimePicture(int data,int length,Rect frame,Rect destination) throws M68kCpuException {
		if(length<70)return false;
		int version=readPictureWord(data);int matrix=data+2;
		int matteSize=readPictureLong(matrix+36),mode=readPictureWord(matrix+48);Rect source=readPictureRect(matrix+50);int maskSize=readPictureLong(matrix+62);
		int cursor=matrix+66;
		if(matteSize>0)cursor+=matteSize; // matte: its own description and data, unused
		if(maskSize>0)cursor+=maskSize;
		int end=data+length;if(cursor+86>end)return false;
		int idSize=readPictureLong(cursor);if(idSize<86||cursor+idSize>end)return false;
		byte[] description=memory.readBytes(cursor,idSize);int dataSize=MacApp68k.u4(description,44);
		int imageData=cursor+idSize;if(dataSize<=0||imageData+dataSize>end)dataSize=end-imageData;if(dataSize<=0)return false;
		int[] argb=QuickTimeImage68k.decode(description,memory.readBytes(imageData,dataSize));
		if(argb==null){if(Mac68kApi.TRACE)System.err.println("[68K] PICT QuickTime data: codec "+QuickTimeImage68k.codecName(description)+" not decoded");return false;}
		int width=MacApp68k.u2(description,32),height=MacApp68k.u2(description,34);
		if(source.empty())source=new Rect(0,0,height,width);
		// the matrix maps the source rectangle into picture space: a Fixed 3x3 with scale in a/d and the offset in tx/ty
		double a=readPictureLong(matrix)/65536.0,d=readPictureLong(matrix+16)/65536.0,tx=readPictureLong(matrix+24)/65536.0,ty=readPictureLong(matrix+28)/65536.0;
		if(a==0)a=1;if(d==0)d=1;
		Rect placed=new Rect((int)Math.round(source.top*d+ty),(int)Math.round(source.left*a+tx),(int)Math.round(source.bottom*d+ty),(int)Math.round(source.right*a+tx));
		if(Mac68kApi.TRACE)System.err.println("[68K] PICT QuickTime data: "+QuickTimeImage68k.codecName(description)+" "+width+"x"+height+" version "+version+" source "+source+" -> "+placed+" mode "+mode);
		rasterImage(argb,width,height,source,mapRect(placed,frame,destination),mode);
		return true;
	}
	/** The matte of an UncompressedQuickTime opcode (grey ARGB, width, height) for the bits opcode that follows it, or null. */
	private int[] pictureMatte;private int pictureMatteWidth,pictureMatteHeight;
	/**
	 * UncompressedQuickTime ($8201): version, a 3x3 Fixed matrix, matteSize, matteRect, the matte (an ImageDescription and
	 * its data), then the image itself as the picture's next ordinary bits opcode. The matte - a grey image, black where the
	 * image shows and white where the background stays, greys blending - applies to that bits opcode. QuickMooV's logo is
	 * two colour bands under an 'smc ' matte of its lettering.
	 */
	private void readUncompressedQuickTime(int data,int length) throws M68kCpuException {
		pictureMatte=null;if(length<2+36+4+8)return;
		int matteSize=readPictureLong(data+2+36),matte=data+2+36+4+8,end=data+length;
		if(matteSize<86||matte+matteSize>end)return;
		int idSize=readPictureLong(matte);if(idSize<86||idSize>matteSize)return;
		byte[] description=memory.readBytes(matte,idSize);int dataSize=Math.min(MacApp68k.u4(description,44),matteSize-idSize);if(dataSize<=0)return;
		int[] argb=QuickTimeImage68k.decode(description,memory.readBytes(matte+idSize,dataSize));if(argb==null)return;
		pictureMatte=argb;pictureMatteWidth=MacApp68k.u2(description,32);pictureMatteHeight=MacApp68k.u2(description,34);
		if(Mac68kApi.TRACE)System.err.println("[68K] PICT UncompressedQuickTime matte "+QuickTimeImage68k.codecName(description)+" "+pictureMatteWidth+"x"+pictureMatteHeight);
	}
	/** Draws host pixels (ARGB rows) into the current port: the source rectangle of the image stretched over the destination,
	 *  through the transfer mode and the port's clip; transparent source pixels leave the destination alone. */
	private void rasterImage(int[] argb,int width,int height,Rect source,Rect destination,int mode) throws M68kCpuException {
		if(currentPort==0||width<=0||height<=0)return;Bitmap bitmap=readCurrentBitmap();
		int sourceWidth=source.right-source.left,sourceHeight=source.bottom-source.top,destinationWidth=destination.right-destination.left,destinationHeight=destination.bottom-destination.top;
		if(sourceWidth<=0||sourceHeight<=0||destinationWidth<=0||destinationHeight<=0)return;
		Rect area=intersection(destination,currentClipBounds(bitmap));if(area.empty())return;boolean shaped=clipHasShape();
		for(int v=area.top;v<area.bottom;v++)for(int h=area.left;h<area.right;h++){
			if(shaped&&!regionsAllow(h,v))continue;
			int sx=source.left+(int)(((long)(h-destination.left)*sourceWidth)/destinationWidth),sy=source.top+(int)(((long)(v-destination.top)*sourceHeight)/destinationHeight);
			if(sx<0||sy<0||sx>=width||sy>=height)continue;int pixel=argb[sy*width+sx];if((pixel>>>24)==0)continue;
			int ink=~pixel&0xffffff,m=mode&~0x40;bitmap.setInkRgb(h,v,m==0||m==8?ink:transferInk(ink,bitmap.getInkRgb(h,v),m,bitmap));
		}
	}

	private int playPictureBits(int cursor,int opcode,Rect frame,Rect pictureDestination) throws M68kCpuException {
		boolean direct=opcode==0x9a||opcode==0x9b;if(direct)cursor+=4;pictureTableSeed=0;
		int rowWord=readPictureWord(cursor),rowBytes=rowWord&0x3fff;boolean pixMap=(rowWord&0x8000)!=0;
		if((rowWord&0x4000)!=0)throw pictureError(cursor,"invalid PICT rowBytes flags");
		Rect bounds=readPictureRect(cursor+2);int pixelType=0,pixelSize=1,packType=1,componentCount=1;boolean[] palette=null;int[] paletteRgb=null;
		if(survey!=null){if(pixMap){survey.pixMapCount++;int size=readPictureWord(cursor+28);if(size>survey.depth){survey.depth=size;survey.hRes=readPictureLong(cursor+18);survey.vRes=readPictureLong(cursor+22);/* rowBytes, bounds, pmVersion, packType, packSize, then hRes at +18 (Rinaldi's PictInfos showed "0,72") */}}else{survey.bitMapCount++;if(survey.depth<1)survey.depth=1;}}
		if(pixMap){packType=readPictureWord(cursor+12);pixelType=readPictureWord(cursor+26);pixelSize=readPictureWord(cursor+28);
			componentCount=readPictureWord(cursor+30);int componentSize=readPictureWord(cursor+32);cursor+=46;
			// 32-bit pictures with an alpha plane (cmpCount 4) carry it in the pixel's top byte / a fourth plane
			if(direct){if(pixelType!=16||!((pixelSize==16&&componentCount==3&&componentSize==5)||(pixelSize==32&&(componentCount==3||componentCount==4)&&componentSize==8)))
					throw pictureError(cursor-46,"unsupported direct-color PICT PixMap layout ("+pixelType+"/"+pixelSize+"/"+componentCount+"x"+componentSize+")");
				if(packType==0)packType=pixelSize==16?3:4;
				if(packType<1||packType>4||(pixelSize==16&&packType!=1&&packType!=3)||(pixelSize==32&&packType==3))
					throw pictureError(cursor-46,"unsupported direct-color PICT packing type");
			}else {if(pixelType!=0||!(pixelSize==1||pixelSize==2||pixelSize==4||pixelSize==8))
					throw pictureError(cursor-46,"unsupported indexed PICT PixMap layout");
				palette=new boolean[1<<pixelSize];paletteRgb=new int[1<<pixelSize];
				for(int i=1;i<palette.length;i++){palette[i]=true;int g=255-i*255/(palette.length-1);paletteRgb[i]=g<<16|g<<8|g;}paletteRgb[0]=0xffffff;
				int lastIndex=readPictureWord(cursor+6),count=lastIndex==0xffff?0:lastIndex+1;pictureTableSeed=readPictureLong(cursor);
				if(count>256)throw pictureError(cursor,"PICT color table is too large");
				// PixMap colour tables are indexed by position; the value field is often 0 for every entry.
				for(int i=0;i<count;i++){int entry=cursor+8+i*8,value=i;
					int red=readPictureWord(entry+2),green=readPictureWord(entry+4),blue=readPictureWord(entry+6);
					if(value<palette.length){palette[value]=(299L*red+587L*green+114L*blue)<32_768_000L;paletteRgb[value]=(red>>>8)<<16|(green>>>8)<<8|(blue>>>8);}}
				cursor+=8+count*8;
				if(survey!=null&&survey.colorTable==null){survey.colorTable=new int[count];for(int i=0;i<count&&i<paletteRgb.length;i++)survey.colorTable[i]=paletteRgb[i];}}
		}else {if(direct)throw pictureError(cursor,"DirectBits requires a PixMap");cursor+=10;}
		Rect sourceRect=readPictureRect(cursor),destinationRect=readPictureRect(cursor+8);
		int mode=(short)readPictureWord(cursor+16);cursor+=18;Rect mask=null,maskSource=null;QdRegion maskShape=null;
		// BitsRgn: the mask region clips the copy to its shape, not just its bounding box - Factory 1.1's truck and loading dock
		// pictures are rectangles of white around the shape their region cuts out
		if(opcode==0x91||opcode==0x99||opcode==0x9b){int regionSize=readPictureWord(cursor);if(regionSize<10)throw pictureError(cursor,"invalid BitsRgn mask");
			maskSource=readPictureRect(cursor+2);mask=mapRect(maskSource,frame,pictureDestination);if(regionSize>10)maskShape=QdRegion.decode(memory,cursor);cursor+=regionSize;}
		int height=bounds.bottom-bounds.top;if(rowBytes<=0||height<=0)return cursor;
		if((long)rowBytes*height>16_777_216L)throw pictureError(cursor,"PICT bitmap is too large");
		byte[][] rows=new byte[height][rowBytes];boolean packed=(opcode==0x98||opcode==0x99)&&rowBytes>=8;
		for(int row=0;row<height;row++) {
			if(direct){int width=Math.max(0,bounds.right-bounds.left);
				if(packType==2){byte[] components=new byte[width*3];for(int i=0;i<components.length;i++)components[i]=(byte)memory.readByte(cursor++);
					componentsToXrgb(components,rows[row],width,false,0);continue;}
				if((packType==3||packType==4)&&rowBytes>=8){int packedLength=rowBytes>250?readPictureWord(cursor):memory.readByte(cursor);cursor+=rowBytes>250?2:1;
					int packedEnd=cursor+packedLength;
					if(packType==3)decodeWordPackBits(cursor,packedEnd,rows[row]);
					else {int planes=componentCount==4?4:3;byte[] components=new byte[width*planes];decodeBytePackBits(cursor,packedEnd,components);componentsToXrgb(components,rows[row],width,true,planes-3);}
					cursor=packedEnd;continue;}
				for(int i=0;i<rowBytes;i++)rows[row][i]=(byte)memory.readByte(cursor++);continue;
			}
			if(!packed){for(int i=0;i<rowBytes;i++)rows[row][i]=(byte)memory.readByte(cursor++);continue;}
			int packedLength=rowBytes>250?readPictureWord(cursor):memory.readByte(cursor);cursor+=rowBytes>250?2:1;
			int packedEnd=cursor+packedLength;decodeBytePackBits(cursor,packedEnd,rows[row]);cursor=packedEnd;
		}
		Rect target=mapRect(destinationRect,frame,pictureDestination),drawing=intersection(target,currentClipBounds(readCurrentBitmap()));
		if(mask!=null)drawing=intersection(drawing,mask);int targetWidth=target.right-target.left,targetHeight=target.bottom-target.top;
		int sourceWidth=sourceRect.right-sourceRect.left,sourceHeight=sourceRect.bottom-sourceRect.top;if(target.empty()||sourceWidth<=0||sourceHeight<=0)return cursor;
		Bitmap destination=readCurrentBitmap();
		boolean color=destination.pixelSize>1&&(direct||paletteRgb!=null);
		// 32-bit to 32-bit srcCopy (plain or dithered) moves whole pixels, unused high byte included, as a blit does
		boolean rawCopy=direct&&pixelSize==32&&destination.direct&&destination.pixelSize==32&&(mode==0||mode==64);
		// An indexed image whose colour table has the destination's seed is copied by pixel value, as QuickDraw does.
		int[] matte=pictureMatte;pictureMatte=null;int boundsWidth=Math.max(1,bounds.right-bounds.left),boundsHeight=Math.max(1,bounds.bottom-bounds.top);
		boolean byIndex=false;if(matte==null&&!direct&&pixMap&&!destination.direct&&destination.pixelSize==pixelSize&&mode==0&&destination.colorTable!=0){int data=memory.readLong(destination.colorTable);byIndex=data!=0&&pictureTableSeed!=0&&memory.readLong(data)==pictureTableSeed;}
		if(Mac68kApi.TRACE)System.err.println("[68K] t="+(System.currentTimeMillis()/100%10000)+" PICT bits opcode $"+Integer.toHexString(opcode)+" depth "+pixelSize+" bounds "+bounds.left+","+bounds.top+","+bounds.right+","+bounds.bottom+" -> target "+target.left+","+target.top+","+target.right+","+target.bottom+" drawing "+drawing.left+","+drawing.top+","+drawing.right+","+drawing.bottom+" mode "+mode+" dst depth "+destination.pixelSize+" color "+color+" byIndex "+byIndex+" pictSeed $"+Integer.toHexString(pictureTableSeed)+" dstSeed $"+(destination.colorTable==0?"-":Integer.toHexString(memory.readLong(memory.readLong(destination.colorTable))))+(paletteRgb==null?"":" palette "+describePalette(paletteRgb))+" dstBase $"+Integer.toHexString(destination.base)+" port $"+Integer.toHexString(currentPort)+" dstTable $"+Integer.toHexString(destination.colorTable)+" throughDevice "+destination.throughDevice+" match "+(destination.matchPalette==null?"null":describePalette(destination.matchPalette)+(destination.matchPalette.length>4?" [3]="+Integer.toHexString(destination.matchPalette[3])+" [4]="+Integer.toHexString(destination.matchPalette[4]):"")+(destination.matchPalette.length>128?" [128]="+Integer.toHexString(destination.matchPalette[128]):"")));
		if(matte==null&&pictureBitsProc(rows,rowBytes,bounds,pixMap,direct,pixelSize,componentCount,paletteRgb,sourceRect,target,mode,mask))return cursor;
		int maskWidth=mask==null?1:Math.max(1,mask.right-mask.left),maskHeight=mask==null?1:Math.max(1,mask.bottom-mask.top);
		for(int v=drawing.top;v<drawing.bottom;v++)for(int h=drawing.left;h<drawing.right;h++) {
			if(maskShape!=null&&!maskShape.contains(maskSource.left+(int)((long)(h-mask.left)*(maskSource.right-maskSource.left)/maskWidth),
				maskSource.top+(int)((long)(v-mask.top)*(maskSource.bottom-maskSource.top)/maskHeight)))continue;
			int sx=sourceRect.left+(int)((long)(h-target.left)*sourceWidth/targetWidth);
			int sy=sourceRect.top+(int)((long)(v-target.top)*sourceHeight/targetHeight),pixel=sx-bounds.left;
			boolean source=false;int rgb=0xffffff;boolean have=false;
			if(sy>=bounds.top&&sy<bounds.bottom&&pixel>=0) {
				byte[] row=rows[sy-bounds.top];
				if(direct){source=directPixelIsBlack(row,pixel,pixelSize);if(color){rgb=directPixelRgb(row,pixel,pixelSize);have=true;}}
				else {int bit=pixel*pixelSize;if(bit+pixelSize<=rowBytes*8) {int packedByte=row[bit>>>3]&0xff;
					int value=packedByte>>>(8-pixelSize-(bit&7))&((1<<pixelSize)-1);source=pixMap?palette[value]:value!=0;if(color&&paletteRgb!=null){rgb=paletteRgb[value];have=true;}}}
			}
			if(byIndex&&sy>=bounds.top&&sy<bounds.bottom&&pixel>=0){int bit=pixel*pixelSize;if(bit+pixelSize<=rowBytes*8){byte[] row=rows[sy-bounds.top];destination.setIndex(h,v,(row[bit>>>3]&0xff)>>>(8-pixelSize-(bit&7))&((1<<pixelSize)-1));}continue;}
			if(matte!=null&&color&&have){ // the UncompressedQuickTime matte: black = the image, white = what is there
				int mx=(int)((long)pixel*pictureMatteWidth/boundsWidth),my=(int)((long)(sy-bounds.top)*pictureMatteHeight/boundsHeight);
				int weight=mx>=0&&my>=0&&mx<pictureMatteWidth&&my<pictureMatteHeight?255-(matte[my*pictureMatteWidth+mx]&0xff):255;
				if(weight==0)continue;
				if(weight<255){int ink=~rgb&0xffffff,under=destination.getInkRgb(h,v),mixed=0;
					for(int shift=0;shift<24;shift+=8)mixed|=(((ink>>>shift&255)*weight+(under>>>shift&255)*(255-weight))/255)<<shift;
					destination.setInkRgb(h,v,mixed);continue;}}
			if(rawCopy&&have){byte[] row=rows[sy-bounds.top];int o=pixel*4;if(o+3<row.length){destination.setArgb(h,v,(row[o]&0xff)<<24|rgb);continue;}}
			if(color&&have){int ink=~rgb&0xffffff;destination.setInkRgb(h,v,mode==0?ink:transferInk(ink,destination.getInkRgb(h,v),mode,destination));}
			// a one-bit bitmap in srcOr/srcXor/srcBic (and their not- forms) leaves the colour under its clear bits: ClarisWorks
			// lays its tool panel picture over a grey (cccccc) column and hilites the chosen tool by swapping that grey
			else if(destination.pixelSize>1&&(mode&3)!=0&&mode>=0&&mode<=15)destination.setInkRgb(h,v,transferBit(source,destination.getInkRgb(h,v),mode,destination.pixelSize));
			// a one-bit bitmap in an arithmetic mode on a colour destination: its foreground/background colours through the mode,
			// as transferInk does for patterns (it took mode & 7 as a boolean mode before)
			else if(destination.pixelSize>1&&(mode<0||mode>15))destination.setInkRgb(h,v,transferBit(source,destination.getInkRgb(h,v),mode,destination.pixelSize));
			else destination.set(h,v,transfer(source,destination.get(h,v),mode));
		}return cursor;
	}

	private static String describePalette(int[] paletteRgb) {
		StringBuilder text=new StringBuilder();int shown=Math.min(3,paletteRgb.length);
		for(int i=0;i<shown;i++){if(i>0)text.append(',');text.append(Integer.toHexString(paletteRgb[i]));}
		if(paletteRgb.length>shown)text.append("..").append(Integer.toHexString(paletteRgb[paletteRgb.length-1]));return text.toString();
	}

	private void decodeBytePackBits(int cursor,int end,byte[] output) throws M68kCpuException {
		int out=0;while(cursor<end&&out<output.length){int control=(byte)memory.readByte(cursor++);
			if(control>=0){int count=control+1;for(int i=0;i<count&&cursor<end;i++){int value=memory.readByte(cursor++);if(out<output.length)output[out++]=(byte)value;}}
			else if(control!=-128&&cursor<end){int value=memory.readByte(cursor++),count=1-control;while(count-->0&&out<output.length)output[out++]=(byte)value;}}
	}

	private void decodeWordPackBits(int cursor,int end,byte[] output) throws M68kCpuException {
		int out=0;while(cursor<end&&out+1<output.length){int control=(byte)memory.readByte(cursor++);
			if(control>=0){int count=control+1;for(int i=0;i<count&&cursor+1<end;i++){int high=memory.readByte(cursor++),low=memory.readByte(cursor++);
					if(out+1<output.length){output[out++]=(byte)high;output[out++]=(byte)low;}}}
			else if(control!=-128&&cursor+1<end){int high=memory.readByte(cursor++),low=memory.readByte(cursor++),count=1-control;
				while(count-->0&&out+1<output.length){output[out++]=(byte)high;output[out++]=(byte)low;}}}
	}

	/** Planar rows (packType 4) carry one plane per component, an alpha plane first when there are four; skip = planes before red. */
	private void componentsToXrgb(byte[] components,byte[] output,int width,boolean planar,int skip) {
		for(int pixel=0;pixel<width&&pixel*4+3<output.length;pixel++){int source=planar?width*skip+pixel:pixel*3;
			output[pixel*4]=planar&&skip==1?components[pixel]:0; // a four-component picture keeps its alpha plane in the unused byte
			output[pixel*4+1]=components[source];output[pixel*4+2]=components[planar?width*(skip+1)+pixel:source+1];
			output[pixel*4+3]=components[planar?width*(skip+2)+pixel:source+2];}
	}

	private int directPixelRgb(byte[] row,int pixel,int pixelSize) {
		if(pixelSize==16){int offset=pixel*2;if(offset+1>=row.length)return 0xffffff;int value=(row[offset]&0xff)<<8|row[offset+1]&0xff;
			int red=value>>>10&31,green=value>>>5&31,blue=value&31;return (red*255/31)<<16|(green*255/31)<<8|(blue*255/31);}
		int offset=pixel*4;if(offset+3>=row.length)return 0xffffff;return (row[offset+1]&0xff)<<16|(row[offset+2]&0xff)<<8|(row[offset+3]&0xff);
	}

	private boolean directPixelIsBlack(byte[] row,int pixel,int pixelSize) {
		if(pixelSize==16){int offset=pixel*2;if(offset+1>=row.length)return false;int value=(row[offset]&0xff)<<8|row[offset+1]&0xff;
			int red=value>>>10&31,green=value>>>5&31,blue=value&31;return 299*red+587*green+114*blue<15_500;}
		int offset=pixel*4;if(offset+3>=row.length)return false;int red=row[offset+1]&0xff,green=row[offset+2]&0xff,blue=row[offset+3]&0xff;
		return 299*red+587*green+114*blue<127_500;
	}

	private int readPictureWord(int pointer) throws M68kCpuException {return memory.readByte(pointer)<<8|memory.readByte(pointer+1);}
	private int readPictureLong(int pointer) throws M68kCpuException {return readPictureWord(pointer)<<16|readPictureWord(pointer+2);}
	private Rect readPictureRect(int pointer) throws M68kCpuException {return new Rect((short)readPictureWord(pointer),
		(short)readPictureWord(pointer+2),(short)readPictureWord(pointer+4),(short)readPictureWord(pointer+6));}

	private void copyPictureBytes(int source,int destination,int length) throws M68kCpuException {
		for(int i=0;i<length;i++)memory.writeByte(destination+i,memory.readByte(source+i));
	}

	private Rect mapRect(Rect rect,Rect source,Rect destination) {
		return new Rect(mapV(rect.top,source,destination),mapH(rect.left,source,destination),
			mapV(rect.bottom,source,destination),mapH(rect.right,source,destination));
	}
	private int mapH(int value,Rect source,Rect destination) {int width=source.right-source.left;
		return width==0?destination.left:destination.left+(int)((long)(value-source.left)*(destination.right-destination.left)/width);}
	private int mapV(int value,Rect source,Rect destination) {int height=source.bottom-source.top;
		return height==0?destination.top:destination.top+(int)((long)(value-source.top)*(destination.bottom-destination.top)/height);}
	private int scaleH(int value,Rect source,Rect destination) {int width=source.right-source.left;
		return width==0?value:(int)((long)value*(destination.right-destination.left)/width);}
	private int scaleV(int value,Rect source,Rect destination) {int height=source.bottom-source.top;
		return height==0?value:(int)((long)value*(destination.bottom-destination.top)/height);}
	private M68kCpuException pictureError(int address,String detail) {return new M68kCpuException(address,detail);}

	/** HidePen's effect: while pnVis is negative the shape, line and text bottlenecks draw nothing (they still record into an
	 *  open picture, region or polygon - OpenPicture/OpenRgn/OpenPoly hide the pen for that); CopyBits is not affected. */
	boolean penHidden() throws M68kCpuException {return currentPort!=0&&(short)memory.readWord(currentPort+PN_VIS)<0;}
	private void rasterLine(int x0,int y0,int x1,int y1) throws M68kCpuException {
		if(currentPort==0||(short)memory.readWord(currentPort+PN_VIS)<0)return;
		Bitmap bitmap=readCurrentBitmap();int penHeight=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE));
		int penWidth=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE+2));
		int dx=Math.abs(x1-x0),stepX=x0<x1?1:-1,dy=-Math.abs(y1-y0),stepY=y0<y1?1:-1,error=dx+dy;Fill fill=new Fill(bitmap,1,0);
		// a wide pen paints the shape it sweeps once, as QuickDraw does: stamped at every step, a 12-pixel xor line came out
		// hollow (the stamps cancelled) and a blend line many times blended (PgColorX "Line" in xor/blend)
		int left=Math.min(x0,x1),top=Math.min(y0,y1),width=dx+penWidth,height=-dy+penHeight;
		boolean[] swept=(penWidth>1||penHeight>1)&&(long)width*height<=16_000_000L?new boolean[width*height]:null;
		while(true){for(int pv=0;pv<penHeight;pv++)for(int ph=0;ph<penWidth;ph++)
			if(swept!=null)swept[(y0+pv-top)*width+(x0+ph-left)]=true;else fill.pixel(x0+ph,y0+pv);
			if(x0==x1&&y0==y1)break;int twice=error*2;
			if(twice>=dy){error+=dy;x0+=stepX;}if(twice<=dx){error+=dx;y0+=stepY;}}
		if(swept!=null)for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(swept[y*width+x])fill.pixel(left+x,top+y);
	}

	private void rasterRect(Rect rect,int verb,int suppliedPattern) throws M68kCpuException {
		if(currentPort==0||rect.empty()||penHidden())return;Bitmap bitmap=readCurrentBitmap();
		if(Mac68kApi.TRACE&&verb!=0)System.err.println("[68K] t="+(System.currentTimeMillis()/100%10000)+" rect verb "+verb+" "+rect+" port $"+Integer.toHexString(currentPort)+" bits $"+Integer.toHexString(memory.readLong(currentPort+PORT_BITS))+" back "+Integer.toHexString(~portBackgroundInk()&0xffffff)+" bkPat "+Long.toHexString(memory.readLong(currentPort+BK_PAT))+Long.toHexString(memory.readLong(currentPort+BK_PAT+4))+" pnPat "+Long.toHexString(memory.readLong(currentPort+PN_PAT))+Long.toHexString(memory.readLong(currentPort+PN_PAT+4))+" pnMode "+memory.readWord(currentPort+PN_MODE)+" pixPat "+Integer.toHexString(activePixPat)+" penPixPat "+Integer.toHexString(memory.readLong(currentPort+PN_PAT))+" clip "+currentClip());
		if(verb==0){if((short)memory.readWord(currentPort+PN_VIS)<0)return;
			int height=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE));
			int width=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE+2));
			Fill fill=new Fill(bitmap,1,0);Rect area=intersection(rect,fill.clip.bounds);if(area.empty())return;
			// only the border rows and columns are visited (a frame around a full-screen window used to test every pixel)
			for(int v=area.top;v<area.bottom;v++){
				if(v<rect.top+height||v>=rect.bottom-height){fill.span(v,area.left,area.right);continue;}
				fill.span(v,area.left,Math.min(area.right,rect.left+width));fill.span(v,Math.max(area.left,rect.right-width),area.right);}
			return;
		}
		Fill fill=new Fill(bitmap,verb,suppliedPattern);Rect area=intersection(rect,fill.clip.bounds);
		fill.smoothInvert(area);
		try{for(int v=area.top;v<area.bottom;v++)fill.span(v,area.left,area.right);}finally{fill.end();}
	}

	private void rasterOval(Rect rect,int verb,int suppliedPattern) throws M68kCpuException {
		if(currentPort==0||rect.empty()||penHidden())return;
		Bitmap bitmap=readCurrentBitmap();Fill fill=new Fill(bitmap,verb,suppliedPattern);double radiusX=(rect.right-rect.left)/2.0,radiusY=(rect.bottom-rect.top)/2.0;
		double centerX=rect.left+radiusX,centerY=rect.top+radiusY;
		int penHeight=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE));
		int penWidth=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE+2));
		double innerRadiusX=Math.max(0,radiusX-penWidth),innerRadiusY=Math.max(0,radiusY-penHeight);
		fill.smoothInvert(rect);
		try{for(int v=rect.top;v<rect.bottom;v++)for(int h=rect.left;h<rect.right;h++) {
			double dx=(h+0.5-centerX)/radiusX,dy=(v+0.5-centerY)/radiusY;
			if(dx*dx+dy*dy>1.0)continue;
			if(verb==0&&innerRadiusX>0&&innerRadiusY>0) {
				double innerDx=(h+0.5-centerX)/innerRadiusX,innerDy=(v+0.5-centerY)/innerRadiusY;
				if(innerDx*innerDx+innerDy*innerDy<=1.0)continue;
			}
			fill.pixel(h,v);
		}}finally{fill.end();}
	}

	private void rasterRoundRect(Rect rect,int ovalWidth,int ovalHeight,int verb,int suppliedPattern) throws M68kCpuException {
		if(currentPort==0||rect.empty()||penHidden())return;
		Bitmap bitmap=readCurrentBitmap();Fill fill=new Fill(bitmap,verb,suppliedPattern);ovalWidth=Math.max(0,Math.min(ovalWidth,rect.right-rect.left));
		ovalHeight=Math.max(0,Math.min(ovalHeight,rect.bottom-rect.top));
		int penHeight=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE));
		int penWidth=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE+2));
		Rect inner=new Rect(rect.top+penHeight,rect.left+penWidth,rect.bottom-penHeight,rect.right-penWidth);
		int innerOvalWidth=Math.max(0,ovalWidth-2*penWidth),innerOvalHeight=Math.max(0,ovalHeight-2*penHeight);
		fill.smoothInvert(rect);
		try{for(int v=rect.top;v<rect.bottom;v++)for(int h=rect.left;h<rect.right;h++) {
			if(!insideRoundRect(h,v,rect,ovalWidth,ovalHeight))continue;
			if(verb==0&&!inner.empty()&&insideRoundRect(h,v,inner,innerOvalWidth,innerOvalHeight))continue;
			fill.pixel(h,v);
		}}finally{fill.end();}
	}

	private boolean insideRoundRect(int h,int v,Rect rect,int ovalWidth,int ovalHeight) {
		if(!contains(rect,h,v))return false;if(ovalWidth<=0||ovalHeight<=0)return true;
		double radiusX=ovalWidth/2.0,radiusY=ovalHeight/2.0,x=h+0.5,y=v+0.5;
		if(x>=rect.left+radiusX&&x<=rect.right-radiusX)return true;
		if(y>=rect.top+radiusY&&y<=rect.bottom-radiusY)return true;
		double centerX=x<rect.left+radiusX?rect.left+radiusX:rect.right-radiusX;
		double centerY=y<rect.top+radiusY?rect.top+radiusY:rect.bottom-radiusY;
		double dx=(x-centerX)/radiusX,dy=(y-centerY)/radiusY;return dx*dx+dy*dy<=1.0;
	}

	private void rasterArc(Rect rect,int startAngle,int arcAngle,int verb,int suppliedPattern) throws M68kCpuException {
		if(currentPort==0||rect.empty()||arcAngle==0||penHidden())return;
		Bitmap bitmap=readCurrentBitmap();Fill fill=new Fill(bitmap,verb,suppliedPattern);double radiusX=(rect.right-rect.left)/2.0,radiusY=(rect.bottom-rect.top)/2.0;
		double centerX=rect.left+radiusX,centerY=rect.top+radiusY;
		int penHeight=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE));
		int penWidth=Math.max(1,(short)memory.readWord(currentPort+PN_SIZE+2));
		double innerRadiusX=Math.max(0,radiusX-penWidth),innerRadiusY=Math.max(0,radiusY-penHeight);
		fill.smoothInvert(rect);
		try{for(int v=rect.top;v<rect.bottom;v++)for(int h=rect.left;h<rect.right;h++) {
			double pixelX=h+0.5-centerX,pixelY=v+0.5-centerY;
			double dx=pixelX/radiusX,dy=pixelY/radiusY;if(dx*dx+dy*dy>1.0)continue;
			double angle=Math.toDegrees(Math.atan2(dx,-dy));if(angle<0)angle+=360.0;
			if(!angleInSweep(angle,startAngle,arcAngle))continue;
			if(verb==0&&innerRadiusX>0&&innerRadiusY>0) {
				double innerDx=pixelX/innerRadiusX,innerDy=pixelY/innerRadiusY;
				if(innerDx*innerDx+innerDy*innerDy<=1.0)continue;
			}
			fill.pixel(h,v);
		}}finally{fill.end();}
	}

	private void rasterPolygon(List<QdPoint> points,int verb,int suppliedPattern) throws M68kCpuException {
		if(currentPort==0||penHidden())return;if(verb==0){
			// FramePoly plays back the polygon's lines: an open polygon stays open (only Paint/Fill/Erase/Invert close it) - the
			// closing edge drawn here left a stray line under The Fool's Errand's open shapes
			for(int i=0;i+1<points.size();i++){QdPoint first=points.get(i),second=points.get(i+1);
				rasterLine(first.h,first.v,second.h,second.v);}
			if(points.size()==1)rasterLine(points.get(0).h,points.get(0).v,points.get(0).h,points.get(0).v);return;}
		Rect bounds=polygonBounds(points);Bitmap bitmap=readCurrentBitmap();Fill fill=new Fill(bitmap,verb,suppliedPattern);
		fill.smoothInvert(new Rect(bounds.top,bounds.left,bounds.bottom+1,bounds.right+1));
		try{for(int v=bounds.top;v<=bounds.bottom;v++)for(int h=bounds.left;h<=bounds.right;h++)
			if(pointInPolygon(h+0.5,v+0.5,points))fill.pixel(h,v);}finally{fill.end();}
	}

	private boolean pointInPolygon(double h,double v,List<QdPoint> points) {
		boolean inside=false;for(int i=0,j=points.size()-1;i<points.size();j=i++) {
			QdPoint a=points.get(i),b=points.get(j);
			if((a.v>v)!=(b.v>v)&&h<(double)(b.h-a.h)*(v-a.v)/(b.v-a.v)+a.h)inside=!inside;
		}return inside;
	}

	private boolean angleInSweep(double angle,int startAngle,int arcAngle) {
		if(Math.abs((long)arcAngle)>=360)return true;double start=((startAngle%360)+360)%360;
		if(arcAngle>0)return (angle-start+360.0)%360.0<arcAngle;
		return (start-angle+360.0)%360.0<-arcAngle;
	}

	/** The current port's clip for one drawing operation: the rectangle every pixel must lie in, plus the region shapes when they are not plain rectangles. */
	private final class Clip {
		final Rect bounds;final QdRegion clip,vis;
		Clip(Rect bounds,QdRegion clip,QdRegion vis){this.bounds=bounds;this.clip=clip;this.vis=vis;}
		boolean shaped(){return clip!=null||vis!=null;}
		boolean allows(int h,int v){return h>=bounds.left&&h<bounds.right&&v>=bounds.top&&v<bounds.bottom&&(clip==null||clip.contains(h,v))&&(vis==null||vis.contains(h,v));}
	}
	private Clip clipContext(Bitmap bitmap) throws M68kCpuException {
		if(currentPort==0)return new Clip(new Rect(0,0,0,0),null,null);
		QdRegion clipShape=regionShape(memory.readLong(currentPort+CLIP_RGN));PortState ps=ports.get(currentPort);
		if(ps!=null&&ps.pictureClip!=null)clipShape=clipShape==null?ps.pictureClip:QdRegion.combine(clipShape,ps.pictureClip,QdRegion.SECT); // a picture's region clip while it plays
		return new Clip(currentClipBounds(bitmap),clipShape,regionShape(memory.readLong(currentPort+VIS_RGN)));
	}

	/**
	 * Drawing state of one shape operation - bitmap, clip, pattern and transfer mode - resolved once instead of per
	 * pixel. Plain one-bit patterns (and type 0/2 PixPats) drawn with a mode that ignores the destination (patCopy,
	 * notPatCopy) are filled by the span: the two inks are matched to the colour table once and whole rows are written
	 * in one memory load; everything else (pattern modes that read the destination, arithmetic modes, colour PixPats,
	 * shaped clips) goes pixel by pixel through {@link #patternTransfer} with the cached state.
	 */
	/** HiliteRGB ($DA0), the highlight colour new colour ports copy into rgbHiliteColor, and HiliteMode ($938), whose bit 7 a
	 *  program clears (BitClr(HiliteMode, pHiliteBit)) to make the next invert or XOR drawing highlight instead. */
	static final int HILITE_RGB=0xda0,HILITE_MODE=0x938;
	/** A GrafVars record (rgbOpColor, rgbHiliteColor = HiliteRGB, ...). */
	private byte[] newGrafVars(int size) throws M68kCpuException {byte[] v=new byte[size];for(int i=0;i<6;i++)v[6+i]=(byte)memory.readByte(HILITE_RGB+i);return v;}

	private final class Fill {
		final Bitmap bitmap;final Clip clip;final int verb,mode,pattern,pixPat;
		/** The 8 pattern rows for one-bit patterns (all ones for a solid colour PixPat); null for colour PixPats. */
		final byte[] bits;
		/** Inks written for set/clear pattern bits when {@link #simple}. */
		final int onInk,offInk;final boolean simple;
		/** A FillC* call with a nil PixPat: solid foreground colour (see {@link #SOLID_FOREGROUND_PIXPAT}). */
		final boolean solidForeground;
		final boolean hilite;private boolean hiliteArmed;
		Fill(Bitmap bitmap,int verb,int suppliedPattern) throws M68kCpuException {
			this.bitmap=bitmap;clip=clipContext(bitmap);this.verb=verb;
			pattern=verb==1||verb==0?currentPort+PN_PAT:verb==2?currentPort+BK_PAT:suppliedPattern;
			mode=verb==0||verb==1?(short)memory.readWord(currentPort+PN_MODE):8;
			solidForeground=verb==4&&activePixPat==SOLID_FOREGROUND_PIXPAT;
			pixPat=activePixPat>0&&verb==4?activePixPat:0;
			int handle=pixPat;boolean colorPort=currentPort!=0&&isColorPort(currentPort);boolean poked=handle==0&&colorPort&&pokedPattern(pattern);
			if(handle==0&&colorPort&&!poked)handle=slotHandle(pattern);
			byte[] b=null;int solid=-1;
			if(solidForeground)b=ALL_ONES;
			else if(poked)b=memory.readBytes(pattern,8);
			else if(handle!=0){int data=memory.readLong(handle);
				if(data==0)b=ALL_ONES;
				else{int type=(short)memory.readWord(data);
					if(type==0)b=memory.readBytes(data+20,8);
					else if(type==2){b=ALL_ONES;solid=~readRgb(data+20)&0xffffff;}}}
			else if(colorPort&&(pattern==currentPort+BK_PAT))b=ALL_ZEROS;
			else if(colorPort&&(pattern==currentPort+PN_PAT||pattern==currentPort+FILL_PAT||pattern==currentPort+COLOR_FILL_PIX_PAT))b=ALL_ONES;
			else b=pattern==0?ALL_ZEROS:memory.readBytes(pattern,8);
			bits=alignedPattern(b);
			int pixelSize=bitmap.pixelSize;
			// hilite: an invert (or an XOR drawing) right after the program cleared HiliteMode's bit swaps the background and the
			// highlight colour (transfer mode 50), and QuickDraw sets the bit again - once it draws (ColorMap): a shape clipped away
			// entirely leaves the bit clear for the next call. Excel 2.2 clears it once, then inverts its selection through four
			// InvertRgn calls, the first three empty; setting it at the first one made the real one a plain invert (stray outlines)
			hilite=colorPort&&(verb==3||((verb==0||verb==1)&&(mode==10||mode==2)))&&(memory.readByte(HILITE_MODE)&0x80)==0;
			hiliteArmed=hilite;
			boolean independent=b!=null&&verb!=3&&mode>=0&&mode<=15&&(mode&3)==0;
			simple=independent;
			if(!independent){onInk=offInk=0;return;}
			if(solid>=0){onInk=offInk=transferInk(solid,0,mode,pixelSize);}
			else{onInk=transferBit(true,0,mode,pixelSize);offInk=transferBit(false,0,mode,pixelSize);}
		}
		/** One pixel through the general path (shapes, pattern modes that read the destination). */
		void pixel(int h,int v) throws M68kCpuException {
			if(!clip.allows(h,v))return;
			if(simple){bitmap.setInkRgb(h,v,(bits[v&7]&(0x80>>>(h&7)))!=0?onInk:offInk);return;}
			int destination=bitmap.getInkRgb(h,v);
			if(hilite){if(hiliteArmed){hiliteArmed=false;memory.writeByte(HILITE_MODE,memory.readByte(HILITE_MODE)|0x80);}
				if(verb==3||bits==null||(bits[v&7]&(0x80>>>(h&7)))!=0){boolean owned=plane!=null&&verb==3&&bitmap.owned(h,v);bitmap.setInkRgb(h,v,transferInk(0xffffff,destination,50,bitmap));if(owned)invertBlock(h,v);}return;}
			if(verb==3){boolean owned=plane!=null&&bitmap.owned(h,v);bitmap.setInkRgb(h,v,~destination&0xffffff);if(owned)invertBlock(h,v);return;}
			if(solidForeground){bitmap.setInkRgb(h,v,transferBit(true,destination,mode,bitmap.pixelSize));return;}
			bitmap.setInkRgb(h,v,patternTransfer(pattern,pixPat,h,v,destination,mode,bitmap.pixelSize));
		}
		/** An invert over a buffer with a smooth zoom plane: the blocks drawn at hi-res of the pixels inverted are inverted (or
		 *  hilited) the same way, so selected text keeps its smooth glyphs; other pixels are scaled up from their new colours. */
		private SmoothZoom.Plane plane;private long[] pin;private int[] planeArea;private java.util.BitSet inverted;
		/** Before an invert (verb 3) over {@code extent}: the buffer's plane, if it has one, is pinned and brought up to date there. */
		void smoothInvert(Rect extent) throws M68kCpuException {
			if(verb!=3||!smooth.active()||extent.empty())return;
			SmoothZoom.Plane p=smoothPlane(bitmap,false);if(p==null)return;
			int[] r=planeRect(p,bitmap,extent.left,extent.top,extent.right,extent.bottom);if(r==null)return;
			int[] colors=smoothColors(p,planeColors(bitmap));
			pin=p.pin(r[1],r[3]);plane=p;planeArea=r;
			p.sync(r[0],r[1],r[2],r[3],colors,SmoothZoom.DRAW);
		}
		private void invertBlock(int h,int v) throws M68kCpuException {
			int x=h-bitmap.bounds.left,y=v-bitmap.bounds.top;int[] r=planeArea;if(x<r[0]||y<r[1]||x>=r[2]||y>=r[3])return;
			SmoothZoom.Plane p=plane; // (only blocks that were hi-res before the 1x write: a block scaled up is scaled again from the new pixel)
			int s=p.s,stride=p.width*s;
			for(int j=0;j<s;j++){int o=(y*s+j)*stride+x*s;
				for(int i=0;i<s;i++)p.hi[o+i]=hilite?~rgbInk(transferInk(0xffffff,~p.hi[o+i]&0xffffff,50,bitmap),bitmap)&0xffffff:~p.hi[o+i]&0xffffff;}
			if(inverted==null)inverted=new java.util.BitSet();inverted.set((y-r[1])*(r[2]-r[0])+(x-r[0]));
		}
		/** After the shape: the plane takes the new 1x colours, the inverted blocks as drawn at hi-res. */
		void end(){
			if(plane==null)return;
			try{int[] r=planeArea;byte[] drawn=null;
				if(inverted!=null){drawn=new byte[(r[2]-r[0])*(r[3]-r[1])];for(int i=inverted.nextSetBit(0);i>=0;i=inverted.nextSetBit(i+1))drawn[i]=1;}
				plane.commit(r[0],r[1],r[2],r[3],drawn,false);}
			finally{plane.unpin(pin);plane=null;}
		}
		/** Row {@code v} from {@code left} to {@code right} (exclusive), already inside the clip bounds. */
		void span(int v,int left,int right) throws M68kCpuException {
			if(left>=right)return;
			if(!simple||clip.shaped()){for(int h=left;h<right;h++)pixel(h,v);return;}
			bitmap.fillSpan(v,left,right,bits[v&7]&0xff,onInk,offInk);
		}
	}
	private static final byte[] ALL_ONES={-1,-1,-1,-1,-1,-1,-1,-1},ALL_ZEROS=new byte[8];

	/** The decoded region when it is not a plain rectangle (rectangular regions are covered by the box tests), else null. */
	/** The full shape of a region Handle (empty for NIL), for the other managers. */
	QdRegion regionShapeOf(int handle) throws M68kCpuException {return readRegion(handle);}
	private QdRegion regionShape(int handle) throws M68kCpuException {
		if(handle==0)return null;int data=memory.readLong(handle);if(data==0)return null;
		int size=memory.readWord(data);if(size<=10)return null;
		Object[] entry=regionCache.get(handle);
		if(entry==null||(Integer)entry[0]!=data||(Integer)entry[1]!=size||!((Rect)entry[2]).equals(readRect(data+2))){
			entry=new Object[]{data,size,readRect(data+2),QdRegion.decode(memory,data)};regionCache.put(handle,entry);}
		return (QdRegion)entry[3];
	}



	/**
	 * QDSetPatternOrigin / QDGetPatternOrigin (NQD 8.5, CarbonLib 1.3), for the current port: a pattern pixel at local (h, v) is the
	 * pattern's pixel at (h + origin.h, v + origin.v), so after SetOrigin(10, 10) an origin of (-10, -10) keeps patterns aligned with
	 * the content's top left. {@link #anyPatternOrigin} keeps the per-pixel paths free of the lookup until a program sets one.
	 */
	void setPatternOrigin(int h,int v){PortState s=currentPort==0?null:ports.get(currentPort);if(s==null)return;s.patternOriginH=h;s.patternOriginV=v;if(h!=0||v!=0)anyPatternOrigin=true;}
	int[] patternOrigin(){PortState s=currentPort==0?null:ports.get(currentPort);return s==null?new int[2]:new int[]{s.patternOriginH,s.patternOriginV};}
	private boolean anyPatternOrigin;
	private PortState patternOriginState(){if(!anyPatternOrigin||currentPort==0)return null;PortState s=ports.get(currentPort);return s==null||s.patternOriginH==0&&s.patternOriginV==0?null:s;}
	/** The 8 rows of a one-bit pattern moved by the current port's pattern origin (rows indexed by local v, bits by local h). */
	private byte[] alignedPattern(byte[] b){
		PortState s=b==null?null:patternOriginState();if(s==null)return b;
		byte[] out=new byte[8];int shift=s.patternOriginH&7;
		for(int y=0;y<8;y++){int row=b[(y+s.patternOriginV)&7]&0xff;out[y]=(byte)(row<<shift|row>>>(8-shift));}
		return out;
	}

	/** Transfers one pattern pixel: one-bit patterns go through {@link #transferBit}, colour PixPats through {@link #transferInk}. */
	private int patternTransfer(int pattern,int pixPat,int h,int v,int destination,int mode,int pixelSize) throws M68kCpuException {
		if(anyPatternOrigin){PortState s=patternOriginState();if(s!=null){h+=s.patternOriginH;v+=s.patternOriginV;}}
		int handle=pixPat;
		if(handle==0&&currentPort!=0&&isColorPort(currentPort)){if(pokedPattern(pattern))return transferBit((memory.readByte(pattern+(v&7))&(0x80>>>(h&7)))!=0,destination,mode,pixelSize);
			handle=slotHandle(pattern);}
		if(handle!=0){int data=memory.readLong(handle);
			if(data==0)return transferBit(true,destination,mode,pixelSize);
			if((short)memory.readWord(data)==0)return transferBit((memory.readByte(data+20+(v&7))&(0x80>>>(h&7)))!=0,destination,mode,pixelSize);
			return transferInk(pixelPatternInk(handle,h,v),destination,mode,pixelSize);}
		return transferBit(patternBit(pattern,h,v),destination,mode,pixelSize);
	}

	/**
	 * Transfers a one-bit source pixel (a glyph or pattern bit). On colour destinations the Boolean modes treat the bit
	 * as a mask selecting the foreground or background colour (Inside Macintosh: Imaging with QuickDraw, table 4-2):
	 * srcOr paints the foreground colour where the bit is set even when that colour is white, which is how programs
	 * draw white text over coloured backgrounds. One-bit destinations and the arithmetic modes use {@link #transferInk}.
	 */
	private int transferBit(boolean bit,int destination,int mode,int pixelSize) throws M68kCpuException {
		// the XOR modes ignore the foreground and background colours at every depth: a set bit inverts the destination pixel.
		// Infini-D 2.0.1's Workshop paints its 1-bit GWorld black and draws its grid and profile in patXor under
		// ForeColor(whiteColor); colourised, white is index 0 there and nothing showed.
		if(mode>=0&&mode<=15&&(mode&3)==2){boolean on=(mode&4)!=0?!bit:bit;return on?~destination&0xffffff:destination&0xffffff;}
		// a classic port's 1-bit bitmap too (ColorMap): srcOr/patOr leave the 0 bits alone whatever bkColor is
		if((pixelSize>1||currentPort!=0&&!isColorPort(currentPort))&&mode>=0&&mode<=15){boolean on=(mode&4)!=0?!bit:bit;
			switch(mode&3){case 0:return on?portForegroundInk():portBackgroundInk();case 1:return on?portForegroundInk():destination&0xffffff;
				case 2:return on?~destination&0xffffff:destination&0xffffff;default:return on?portBackgroundInk():destination&0xffffff;}}
		return transferInk(bit?portForegroundInk():portBackgroundInk(),destination,mode,pixelSize);
	}

	private int patternInk(int pattern,int h,int v) throws M68kCpuException {
		if(anyPatternOrigin){PortState s=patternOriginState();if(s!=null){h+=s.patternOriginH;v+=s.patternOriginV;}}
		if(currentPort!=0&&isColorPort(currentPort)&&!pokedPattern(pattern)){int handle=slotHandle(pattern);
			if(handle!=0)return pixelPatternInk(handle,h,v);}
		return patternBit(pattern,h,v)?portForegroundInk():portBackgroundInk();
	}

	private int pixelPatternInk(int handle,int h,int v) throws M68kCpuException {if(handle==0)return portForegroundInk();int data=memory.readLong(handle);
		if(data==0)return portForegroundInk();int type=(short)memory.readWord(data);if(type==2)return ~readRgb(data+20)&0xffffff;
		if(type==0)return (memory.readByte(data+20+(v&7))&(0x80>>>(h&7)))!=0?portForegroundInk():portBackgroundInk();
		if(type==1||type==3){int mapHandle=memory.readLong(data+2),pixelHandle=memory.readLong(data+6);if(mapHandle==0)return portForegroundInk();
			int map=memory.readLong(mapHandle),pixels=pixelHandle==0?0:memory.readLong(pixelHandle);if(map==0)return portForegroundInk();Bitmap pattern=patternMapBitmap(map,pixels);
			int width=pattern.bounds.right-pattern.bounds.left,height=pattern.bounds.bottom-pattern.bounds.top;if(width<=0||height<=0)return portForegroundInk();
			int x=pattern.bounds.left+Math.floorMod(h-pattern.bounds.left,width),y=pattern.bounds.top+Math.floorMod(v-pattern.bounds.top,height);return pattern.getInkRgb(x,y);}
		throw new M68kCpuException(data,"unsupported PixPat type "+type);}

	/** For the trace: a PixPat's type, patMap layout, pattern bytes and table colours. */
	private String describePixPat(int handle) throws M68kCpuException {
		int data=memory.readLong(handle);if(data==0)return "$"+Integer.toHexString(handle)+" empty";int type=(short)memory.readWord(data);
		StringBuilder s=new StringBuilder("$"+Integer.toHexString(handle)+" type "+type);
		if(type!=1&&type!=3)return s.toString();
		int mapHandle=memory.readLong(data+2),pixelHandle=memory.readLong(data+6),map=mapHandle==0?0:memory.readLong(mapHandle);
		if(map!=0)s.append(" rowBytes $"+Integer.toHexString(memory.readWord(map+4))+" bounds "+readRect(map+6)+" pixelSize "+memory.readWord(map+32));
		int pixels=pixelHandle==0?0:memory.readLong(pixelHandle);
		if(pixels!=0){s.append(" data");int n=Math.min(16,macApi.handleSize(pixelHandle));for(int i=0;i<n;i++)s.append(String.format(" %02X",memory.readByte(pixels+i)));}
		if(map!=0&&memory.readWord(map+32)<=8)s.append(" colors "+java.util.Arrays.toString(java.util.Arrays.stream(readColorTable(memory.readLong(map+42),memory.readWord(map+32))).mapToObj(Integer::toHexString).toArray()));
		return s.toString();
	}

	/**
	 * A PixPat's patMap: the ROM reads it as the PixMap it is declared as, whatever bit 15 of its rowBytes says. Wingz 1.0 writes
	 * a plain rowBytes 1 into the map NewPixPat made (1-bit, a two-entry table) and sets both table colours before each fill - its
	 * cells are white over an FFFF0000 00000000 pattern; read as a BitMap the bits came out as black stripes over the sheet.
	 */
	private Bitmap patternMapBitmap(int map,int pixels) throws M68kCpuException {
		int rowWord=memory.readWord(map+4),pixelSize=memory.readWord(map+32);
		if((rowWord&0x8000)!=0||!(pixelSize==1||pixelSize==2||pixelSize==4||pixelSize==8||pixelSize==16||pixelSize==32))return readBitmap(map,pixels);
		boolean direct=pixelSize==16||pixelSize==32;int table=direct?0:memory.readLong(map+42);
		Bitmap bitmap=new Bitmap(pixels,rowWord&0x3fff,readRect(map+6),pixelSize,direct,direct?null:readColorTable(table,pixelSize));bitmap.colorTable=table;
		return bitmap;
	}

	private boolean patternBit(int pattern,int h,int v) throws M68kCpuException {
		if(currentPort!=0&&isColorPort(currentPort)&&!pokedPattern(pattern)){if(pattern==currentPort+BK_PAT)return false;
			if(pattern==currentPort+PN_PAT||pattern==currentPort+FILL_PAT||pattern==currentPort+COLOR_FILL_PIX_PAT)return true;}
		if(pattern==0)return false;return (memory.readByte(pattern+(v&7))&(0x80>>>(h&7)))!=0;
	}

	private boolean portAllows(int h,int v) throws M68kCpuException {
		if(currentPort==0)return false;return contains(currentClipBounds(currentBitmapBounds()),h,v)&&regionsAllow(h,v);
	}

	private Rect currentClipBounds(Bitmap bitmap) throws M68kCpuException {return currentClipBounds(bitmap.bounds);}
	private Rect currentClipBounds(Rect bitmapBounds) throws M68kCpuException {
		// A port on the screen (OpenPort/OpenCPort) is not clipped to its portRect, as QuickDraw clips only to the bits, the
		// visRgn and the clipRgn: xDraw (HyperGL) sizes its screen port to the card with PortSize, then SetOrigin's it over the
		// card window - its drawing below and right of the moved portRect was lost
		Rect area=screenBitmaps.contains(bitmapPointer(currentPort))?bitmapBounds:intersection(bitmapBounds,readRect(currentPort+PORT_RECT));
		Rect r=intersection(intersection(area,readRegionRect(memory.readLong(currentPort+VIS_RGN))),currentClip());
		return hostClip==null?r:intersection(r,hostClip);
	}
	/**
	 * An extra clip (local coordinates) for host-side drawing that goes pixel by pixel through {@link #portAllows}:
	 * TextEdit keeps its text inside the view rectangle this way without touching the port's own clip region.
	 * {@link #setHostClip} returns the previous value for {@link #restoreHostClip}.
	 */
	private Rect hostClip;
	Object setHostClip(int top,int left,int bottom,int right){Rect previous=hostClip;Rect r=new Rect(top,left,bottom,right);hostClip=previous==null?r:intersection(previous,r);return previous;}
	void restoreHostClip(Object previous){hostClip=(Rect)previous;}
	/** Bounds of the current port's bitmap without reading its colour table (see {@link #readBitmap}). */
	private Rect currentBitmapBounds() throws M68kCpuException {
		int pointer=bitmapPointer(currentPort);if(pointer==0)return new Rect(0,0,0,0);
		if((memory.readWord(pointer+4)&0xc000)==0xc000){int handle=memory.readLong(pointer);if(handle==0)return new Rect(0,0,0,0);pointer=memory.readLong(handle);if(pointer==0)return new Rect(0,0,0,0);}
		return pixMapBounds(pointer,offscreenPixMapData.get(pointer));
	}

	private boolean contains(Rect rect,int h,int v) {return h>=rect.left&&h<rect.right&&v>=rect.top&&v<rect.bottom;}

	/** An arithmetic mode (or hilite, ditherCopy) in a 1-bit port: the boolean mode it acts as (Inside Macintosh: Imaging,
	 *  arithmetic modes in a basic graphics port). Without it a bitmap in addMax drew as notSrcBic (mode & 7). */
	private static int oneBitMode(int mode) {
		if(mode>=96&&mode<=103)mode-=64; // text maskMode combined with an arithmetic mode
		mode=arithmeticPatternMode(mode);
		switch(mode){case 32:case 64:return 0;case 33:case 37:return 3;case 34:case 38:case 50:return 2;case 35:case 36:case 39:return 1;default:return mode;}
	}
	private boolean transfer(boolean source,boolean destination,int mode) {
		mode=oneBitMode(mode);
		int normalized=mode>=8&&mode<=15?mode-8:mode;
		if((normalized&4)!=0)source=!source;
		switch(normalized&3){case 1:return source||destination;case 2:return source!=destination;
			case 3:return !source&&destination;default:return source;}
	}
	/** {@link #transferInk} onto {@code target}: hilite mode (50) there compares pixel VALUES at the target's depth, as QuickDraw
	 *  does (Color2Index of the background and highlight colours), not RGBs - on a 16-bit screen HiliteRGB #CCCCFF reads back as
	 *  #CDCDFF, so the Yennie colorHilite XFCN's second InvertRect matched neither colour and its button stayed light blue. */
	private int transferInk(int source,int destination,int mode,Bitmap target) throws M68kCpuException {
		if(mode!=50||target.pixelSize==1)return transferInk(source,destination,mode,target.pixelSize);
		int background=portBackgroundInk(),backgroundValue=target.pixelValue(background);if(target.pixelValue(source)==backgroundValue)return destination&0xffffff;
		int highlight=~grafVarsColor(6,0)&0xffffff,value=target.pixelValue(destination);
		return value==backgroundValue?highlight:value==target.pixelValue(highlight)?(target.direct?rgbInk(background,target):background):destination&0xffffff;}
	/**
	 * An arithmetic mode with the pattern bit (8) set, 40-47: Color QuickDraw takes bit 5 as "arithmetic", bit 3 as "pattern" and
	 * bits 0-2 as the operation, so PenMode(45) paints in addMax (37). Color PolyTools' drawCPoly turns its drawing mode index into
	 * PenMode 47..40 and paints its regions so; as boolean modes (45 = notPatOr...) its yellow Texas came out solid blue.
	 */
	private static int arithmeticPatternMode(int mode){return mode>=40&&mode<=47?mode-8:mode;}
	private int transferInk(int source,int destination,int mode,int pixelSize) throws M68kCpuException {
		if(mode>=96&&mode<=103)mode-=64; // text maskMode combined with an arithmetic mode
		mode=arithmeticPatternMode(mode);
		if(pixelSize==1)mode=oneBitMode(mode);
		if(mode==64)return source&0xffffff;
		if(mode==50){int sourceRgb=~source&0xffffff,background=~portBackgroundInk()&0xffffff;
			if(sourceRgb==background)return destination&0xffffff;int destinationRgb=~destination&0xffffff,highlight=grafVarsColor(6,0);
			if(destinationRgb==background)return ~highlight&0xffffff;if(destinationRgb==highlight)return ~background&0xffffff;return destination&0xffffff;}
		if(mode>=32&&mode<=39){int sourceRgb=~source&0xffffff,destinationRgb=~destination&0xffffff,result=0;
			if(mode==36)return sourceRgb==transparentBackgroundRgb()?destination&0xffffff:source&0xffffff;
			for(int shift=16;shift>=0;shift-=8){int s=sourceRgb>>>shift&255,d=destinationRgb>>>shift&255;
				result|=arithmeticComponent(s,d,mode,shift)<<shift;}return ~result&0xffffff;}
		int normalized=mode>=8&&mode<=15?mode-8:mode;if((normalized&4)!=0)source=~source;
		switch(normalized&3){case 1:return (source|destination)&0xffffff;case 2:return (source^destination)&0xffffff;
			case 3:return (~source&destination)&0xffffff;default:return source&0xffffff;}}
	private int arithmeticComponent(int source,int destination,int mode,int shift) throws M68kCpuException {switch(mode){
		case 32:{int weight=operationColorComponent(shift,128)*257;return (source*weight+destination*(65535-weight)+32767)/65535;}
		case 33:return Math.min(operationColorComponent(shift,255),source+destination);
		case 34:return source+destination&255;
		// the sub modes take the source FROM the destination (Inside Macintosh V: "subtracts the source from the destination"): a
		// grey PaintRect in subPin darkens what is there - Netscape Communicator shades its status bar and toolbar bevels so,
		// and source-minus-destination painted them black
		case 35:return Math.max(operationColorComponent(shift,0),destination-source);
		case 37:return Math.max(source,destination);
		case 38:return destination-source&255;
		case 39:return Math.min(source,destination);
		default:return source;}}
	private int operationColorComponent(int shift,int fallback) throws M68kCpuException {if(currentPort==0||!isColorPort(currentPort))return fallback;
		return grafVarsColorComponent(0,shift,fallback);}
	private int grafVarsColor(int offset,int fallback) throws M68kCpuException {int red=grafVarsColorComponent(offset,16,fallback>>>16&255);
		int green=grafVarsColorComponent(offset,8,fallback>>>8&255),blue=grafVarsColorComponent(offset,0,fallback&255);return red<<16|green<<8|blue;}
	private int grafVarsColorComponent(int offset,int shift,int fallback) throws M68kCpuException {if(currentPort==0||!isColorPort(currentPort))return fallback;
		int variables=grafVarsOf(currentPort);if(variables==0)return fallback;int data=memory.readLong(variables);
		return data==0?fallback:memory.readWord(data+offset+(16-shift)/8*2)>>>8;}

	/** Whether {@code bitmap} (rowBytes word $C000) is a program's own BitMap copied from a classic window rather than a colour
	 *  port's portBits: its baseAddr is no Memory Manager handle and it is not the portBits of a port. */
	private boolean classicWindowCopy(int bitmap) throws M68kCpuException {
		if(ports.containsKey(bitmap-PORT_BITS)||classicWindowPorts.isEmpty())return false;
		int base=memory.readLong(bitmap);return base!=0&&!macApi.isHandle(base);
	}
	private Bitmap readBitmap(int pointer) throws M68kCpuException {return readBitmap(pointer,0,true);}
	private Bitmap readBitmap(int pointer,int baseOverride) throws M68kCpuException {return readBitmap(pointer,baseOverride,true);}
	/** The pixels behind a bitmap, never routed to the screen: for the buffer management of window ports (fills, copies on resize). */
	private Bitmap readBitmapRaw(int pointer) throws M68kCpuException {return readBitmap(pointer,0,false);}
	/** The screen as a port's BitMap or PixMap {@code pointer} reaches it: the windows over each pixel and the device, or null. */
	private Bitmap screenBitmap(int pointer) throws M68kCpuException {
		int[] windows=macApi.application()==null?null:macApi.application().windows.screenWindows();
		// with no window up the drawing still lands in the device pixels, which the host shows as the screen (some games
		// writes its "low on memory" notice on the Window Manager port before it has any window)
		// a classic port's bounds say where its local coordinates lie on the screen (MovePortTo): global = local - bounds.topLeft
		// (an OpenCPort PixMap's bounds move with MovePortTo too, relative to the screen's own)
		int boundsTop=0,boundsLeft=0;if((memory.readWord(pointer+4)&0x8000)==0){boundsTop=(short)memory.readWord(pointer+6);boundsLeft=(short)memory.readWord(pointer+8);}
		else{int device=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(device!=0&&device!=pointer){boundsTop=(short)memory.readWord(pointer+6)-(short)memory.readWord(device+6);boundsLeft=(short)memory.readWord(pointer+8)-(short)memory.readWord(device+8);}}
		if(windows!=null){ScreenBitmap screen=new ScreenBitmap(pointer,windows,-boundsLeft,-boundsTop);if(windows.length>0||screen.device!=null)return screen;}
		return null;
	}
	private Bitmap readBitmap(int pointer,int baseOverride,boolean routed) throws M68kCpuException {
		final int record=pointer; // the record the caller passed: a port's portBits or PixMap, or a copy the program made of one
		if(routed&&baseOverride==0&&(screenBitmaps.contains(pointer)&&describesScreen(pointer)||copiesScreenBits(pointer))){Bitmap screen=screenBitmap(pointer);if(screen!=null)return screen;}
		int rowWord=memory.readWord(pointer+4);
		if(rowWord==0xc000&&classicWindowCopy(pointer)){
			// A BitMap a program built from a classic window's portBits: on a real Mac that record holds the screen BitMap, so the
			// program's rowBytes is the screen's; here the record's inner CGrafPort layout ($C000 = portVersion) leaked into it.
			// Fractal Contours allocates rowBytes*height for its save buffer and CopyBits the window into it (it crashed with
			// "PixMap Handle is empty"). Read it as the 1-bit BitMap the program meant, with the screen's rowBytes.
			Rect bounds=readRect(pointer+6);int[] frame=mainDeviceFrame();int rowBytes=frame!=null&&frame[1]>0?frame[1]:((bounds.right-bounds.left+15)>>>4)*2;
			return new Bitmap(baseOverride!=0?baseOverride:memory.readLong(pointer),rowBytes,bounds,1,false,null);}
		if((rowWord&0xc000)==0xc000){int handle=memory.readLong(pointer);if(handle==0)throw new M68kCpuException(pointer,"CGrafPort has no PixMap Handle");
			pointer=memory.readLong(handle);if(pointer==0)throw new M68kCpuException(handle,"PixMap Handle is empty");rowWord=memory.readWord(pointer+4);
			// a screen port's portBits as CopyBits takes them (&port->portBits): its PixMap is the screen's (Canvas 3.5.4 saves the
			// screen under its pop-up palettes so and puts it back - read raw, the device pixels were black and so was the window)
			if(routed&&baseOverride==0&&screenBitmaps.contains(pointer)&&describesScreen(pointer)){Bitmap screen=screenBitmap(pointer);if(screen!=null)return screen;}}
		PixelMapState world=offscreenPixMapData.get(pointer);
		// A copy of a window's portBits or PixMap the program made itself (on the stack) reaches the same pixels: a never-shown
		// window's are the screen there as well. VISE's list definition copies thePort's portBits and CopyBits its check boxes
		// through the copy into the invisible installer dialog; kept in the buffer, they showed between the rows of the list
		// once the dialog came up.
		if(routed&&baseOverride==0&&world==null&&(rowWord&0x8000)!=0){PixelMapState owner=windowBufferAt(memory.readLong(pointer));
			if(owner!=null){Bitmap screen=hiddenWindowScreenBitmap(memory.readLong(owner.pixMap),owner.windowPort,true);if(screen!=null)return screen;}}
		if(routed&&baseOverride==0&&world!=null&&world.windowPort!=0){Bitmap screen=hiddenWindowScreenBitmap(pointer,world.windowPort,record!=world.windowPort+2&&record!=pointer);if(screen!=null)return screen;
			screen=otherWindowScreenBitmap(pointer,world.windowPort);if(screen!=null)return screen;
			// what the program wrote straight into the screen under this window is part of its pixels before it reads or draws them,
			// and so is host drawing still staged for a 16-bit/indexed buffer
			Toolbox68k tb=macApi.application();if(tb!=null){tb.quickDraw.flushSurfaces();if(tb.hosted()&&memory.deviceWritten())tb.host().adoptDeviceWrites(world.windowPort);}}
		int base=baseOverride!=0?baseOverride:world!=null?memory.readLong(world.pixels):unlockedGWorldPixels(memory.readLong(pointer));
		if(world!=null&&world.screenDescribed)rowWord=0x8000|world.rowBytes; // the record describes the screen; the buffer is the state's
		Rect bounds=pixMapBounds(pointer,world);
		if((rowWord&0x8000)==0)return new Bitmap(base,rowWord&0x3fff,bounds,1,false,null);
		if((rowWord&0x4000)!=0)throw new M68kCpuException(pointer,"unsupported extended PixMap rowBytes");
		int pixelType=memory.readWord(pointer+30),pixelSize=memory.readWord(pointer+32);boolean direct=pixelSize==16||pixelSize==32; // QuickDraw goes by pixelSize: 16/32-bit pixels are direct whatever pixelType says (some programs build a 32-bit PixMap with pixelType 0, cmpCount 1), and Tetris 2000 builds its 8-bit offscreen with pixelType 16, cmpCount 3
		if(!((!direct&&(pixelSize==1||pixelSize==2||pixelSize==4||pixelSize==8))||
			(direct&&(pixelSize==16||pixelSize==32))))throw new M68kCpuException(pointer,"unsupported PixMap pixel layout (pixelType "+pixelType+", pixelSize "+pixelSize+", rowBytes $"+Integer.toHexString(rowWord)+", cmpCount "+memory.readWord(pointer+34)+", cmpSize "+memory.readWord(pointer+36)+")");
		int[] entries=direct?null:paletteEntries(memory.readLong(pointer+42),pixelSize);
		int[] palette=direct?null:entries!=null?paletteEntryColors(entries):readColorTable(memory.readLong(pointer+42),pixelSize);
		Bitmap bitmap=new Bitmap(base,rowWord&0x3fff,bounds,pixelSize,direct,palette);bitmap.colorTable=direct?0:memory.readLong(pointer+42);if(!direct)bitmap.matchPalette=seededPalette(bitmap.colorTable,pixelSize,palette);
		bitmap.paletteEntries=entries;
		if(world instanceof GWorldState)bitmap.sharedDevice=((GWorldState)world).sharedDevice;
		return bitmap;
	}

	/** The current port's pixels for the drawing primitives; like CopyBits they match colours through theGDevice's table when a
	 *  program has switched to a device of its own. A bitmap editor (device table: black at 0, white at 1; its 1-bit
	 *  images hold the ICON bits unchanged, so under that device a set bit is "white") rules its fat-bits grid with the
	 *  background colour black = index 0 and paints with the pencil in patBic (background white = index 1); the magnified
	 *  image then goes to the screen with the default colours, which colorize 1 to black and 0 to white (see colorized). */
	private Bitmap readCurrentBitmap() throws M68kCpuException {Bitmap bitmap=readBitmap(bitmapPointer(currentPort));matchThroughCurrentDevice(bitmap);return bitmap;}
	private boolean isCurrentBitmapArgument(int pointer) throws M68kCpuException {return pointer==bitmapPointer(currentPort)||(isColorPort(currentPort)&&pointer==currentPort+PORT_BITS);}
	boolean colorPort(int port) throws M68kCpuException {return isColorPort(port);}
	private boolean isColorPort(int port) throws M68kCpuException {return port!=0&&(memory.readWord(port+PORT_BITS+4)&0xc000)==0xc000;}
	/** The grafVars Handle of a colour port: in the record at offset 8, except for classic-layout windows (see {@link #newWindowPort}). */
	private int grafVarsOf(int port) throws M68kCpuException {PortState state=ports.get(port);return state!=null&&state.classicWindow?state.grafVars:memory.readLong(port+GRAF_VARS);}
	private void setGrafVarsOf(int port,int handle) throws M68kCpuException {PortState state=ports.get(port);if(state!=null&&state.classicWindow)state.grafVars=handle;else memory.writeLong(port+GRAF_VARS,handle);}
	private int bitmapPointer(int port) throws M68kCpuException {if(!isColorPort(port))return port+PORT_BITS;
		int handle=memory.readLong(port+PORT_BITS);return handle==0?0:memory.readLong(handle);}
	/**
	 * The colour table's entries as of its current seed (QuickDraw's inverse table): taken when the seed changes, or on the first
	 * use of a seed a program wrote itself. A few earlier seeds are kept as well, so a program that puts an old seed back gets the
	 * matching of that seed again, as the ROM's inverse table (iTabSeed) still fits it: Glimmer saves ctSeed, fades the CLUT to
	 * black with SetEntries, writes the saved seed back and draws its title pictures while the screen is black - on a real Mac
	 * they match the palette and appear as the fade comes up; matched against the black entries they were solid white boxes.
	 */
	private final Map<Integer,java.util.LinkedHashMap<Integer,int[]>> seededPalettes=new HashMap<Integer,java.util.LinkedHashMap<Integer,int[]>>();
	private static final int REMEMBERED_SEEDS=8;
	private java.util.LinkedHashMap<Integer,int[]> seedsOf(int handle){
		return seededPalettes.computeIfAbsent(handle,h->new java.util.LinkedHashMap<Integer,int[]>(16,0.75f,true){
			@Override protected boolean removeEldestEntry(Map.Entry<Integer,int[]> eldest){return size()>REMEMBERED_SEEDS;}});
	}
	private int[] seededPalette(int handle,int pixelSize,int[] live) throws M68kCpuException {
		if(handle==0)return live;int data=memory.readLong(handle);if(data==0)return live;int seed=memory.readLong(data);
		java.util.LinkedHashMap<Integer,int[]> seeds=seedsOf(handle);int[] snapshot=seeds.get(seed);
		if(snapshot==null){snapshot=live.clone();seeds.put(seed,snapshot);}
		return snapshot.length==live.length?snapshot:java.util.Arrays.copyOf(snapshot,live.length);
	}
	private int[] readColorTable(int handle,int pixelSize) throws M68kCpuException {return readColorTable(handle,pixelSize,null);}
	private int[] readColorTable(int handle,int pixelSize,int[] reuse) throws M68kCpuException {int count=1<<pixelSize;int[] colors=reuse!=null&&reuse.length==count?reuse:new int[count];
		for(int i=0;i<count;i++){int gray=count==1?255:255-i*255/(count-1);colors[i]=gray<<16|gray<<8|gray;}
		if(handle==0)return colors;int data=memory.readLong(handle);if(data==0)return colors;int last=memory.readWord(data+6);
		if(last==0xffff)return colors;int entries=Math.min(256,last+1);boolean device=(memory.readWord(data+4)&0x8000)!=0;
		// A PixMap table's value fields are the pixel values (a cicn may list 0, 1, 3 and skip 2); a device table (ctFlags bit 15,
		// Some editors build one with every value field 0) is positional, as are PixMap tables whose values are not a usable set
		// (all zero, duplicates, out of range).
		boolean byValue=!device;if(byValue){boolean[] seen=new boolean[count];for(int i=0;i<entries&&byValue;i++){int v=memory.readWord(data+8+i*8);if(v>=count||seen[v])byValue=false;else seen[v]=true;}}
		for(int i=0;i<entries;i++){int entry=data+8+i*8,index=byValue?memory.readWord(entry):i;
			if(index<count)colors[index]=(memory.readWord(entry+2)>>>8)<<16|(memory.readWord(entry+4)>>>8)<<8|memory.readWord(entry+6)>>>8;}return colors;}
	/**
	 * The palette entry numbers, by pixel value, of an indexed PixMap whose colour table has ctFlags bit 14 set; null for other
	 * tables. Such a table "uses indexes that refer to a palette" (Technical Note QD16): its value fields are entries of the
	 * palette of the window drawn into, taken in table order (colorSpec n is pixel value n), not colours - CopyBits gets their
	 * screen indices from the Palette Manager. Dark Castle keeps its offscreen frame in one and fades the screen with SetEntries,
	 * mirroring the colours into it; matched by colour, its CopyBits during the black fade made every pixel black and the
	 * menu stayed black once the colours came back.
	 */
	private int[] paletteEntries(int table,int pixelSize) throws M68kCpuException {
		if(table==0||pixelSize>8||macApi.handleSize(table)<8)return null;int data=memory.readLong(table);if(data==0)return null;
		if((memory.readWord(data+4)&0xc000)!=0x4000)return null;
		int last=(short)memory.readWord(data+6),count=1<<pixelSize;if(last<0)return null;
		int[] entries=new int[count];for(int i=0;i<count;i++)entries[i]=i<=last?memory.readWord(data+8+i*8):i;
		return entries;
	}
	/** The colours of palette entries on the screen: here the Palette Manager puts entry n of an activated palette at screen index
	 *  n ({@link #loadDeviceColors}), so they are the main screen's colours at those indices. */
	private int[] paletteEntryColors(int[] entries) throws M68kCpuException {
		int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap),depth=map==0?8:memory.readWord(map+32);
		int[] screen=readColorTable(map==0||depth>8?0:memory.readLong(map+42),depth>8?8:depth),colors=new int[entries.length];
		for(int i=0;i<entries.length;i++)colors[i]=screen[Math.min(entries[i],screen.length-1)];
		return colors;
	}
	private static boolean identity(int[] map){for(int i=0;i<map.length;i++)if(map[i]!=i)return false;return true;}
	/** Source pixel value -> destination pixel value for a palette-index source ({@link #paletteEntries}) drawn into indexed pixels
	 *  (the entry's screen index, as {@link #paletteEntryColors} says), or null. */
	private static int[] paletteIndexMap(Bitmap source,Bitmap destination){
		if(source.paletteEntries==null||destination.direct)return null;int top=(1<<destination.pixelSize)-1;int[] map=new int[source.paletteEntries.length];
		for(int i=0;i<map.length;i++)map[i]=Math.min(source.paletteEntries[i],top);
		return map;
	}

	/**
	 * A pixel value the program wrote into the colour port's fgColor/bkColor itself, or -1. Color QuickDraw draws with those
	 * fields; rgbFgColor/rgbBkColor only make them. Flashback paints its intro's polygons by poking a colour index into
	 * (*thePort).fgColor between GetForeColor and RGBForeColor; drawn with the RGB colour (black) the intro stayed black.
	 * Only after {@link #updatePortColorValue} stored a value (ForeColor's classic constants are left to the RGB colour) and
	 * only on indexed pixels, where the value is an index.
	 */
	private int pokedColorValue(PortState ps,boolean foreground) throws M68kCpuException {
		int stored=ps==null?-1:foreground?ps.fgValue:ps.bkValue;if(stored<0)return -1;
		int value=memory.readLong(currentPort+(foreground?FG_COLOR:BK_COLOR));if(value==stored)return -1;
		int pixMap=bitmapPointer(currentPort);if(pixMap==0)return -1;int depth=memory.readWord(pixMap+32);
		return depth>=1&&depth<=8&&value>=0&&value<(1<<depth)?value:-1;
	}
	private int portForegroundInk() throws M68kCpuException {if(currentPort==0)return 0xffffff;if(!isColorPort(currentPort))return classicInk(FG_COLOR);PortState ps=ports.get(currentPort);
		int poked=pokedColorValue(ps,true);if(poked>=0)return INDEX_INK|poked;if(ps!=null&&ps.fgIndex>=0)return INDEX_INK|ps.fgIndex;
		int classic=memory.readLong(currentPort+FG_COLOR);if(isClassicColor(classic)&&(ps==null||ps.fgValue!=classic)&&classicPatternOverColors())return ~quickDrawColor(classic)&0xffffff;
		return ~readRgb(currentPort+36)&0xffffff;}
	/** The colour the transparent transfer mode (36) leaves out of the source: the port's background colour, except that the
	 *  content colour of a 'wctb'/'dctb' is the Window Manager's erase colour and not the port's rgbBkColor on a real Mac (which stays
	 *  white unless the program sets it) - Ultimate Spell blits sprites with mode 36 while its yellow command dialog is the port. */
	private int transparentBackgroundRgb() throws M68kCpuException {int background=~portBackgroundInk()&0xffffff;
		PortState ps=currentPort==0?null:ports.get(currentPort);
		return ps!=null&&ps.windowBkRgb==background?0xffffff:background;}
	/** WindowManager68k.setContentColor has just put {@code rgb} into the window port's background: remember it as the Window Manager's. */
	void noteWindowBackground(int port,int rgb){PortState ps=ports.get(port);if(ps!=null)ps.windowBkRgb=rgb;}
	private int portBackgroundInk() throws M68kCpuException {if(currentPort==0)return 0;if(!isColorPort(currentPort))return classicInk(BK_COLOR);PortState ps=ports.get(currentPort);
		int poked=pokedColorValue(ps,false);if(poked>=0)return INDEX_INK|poked;if(ps!=null&&ps.bkIndex>=0)return INDEX_INK|ps.bkIndex;
		int classic=memory.readLong(currentPort+BK_COLOR);if(isClassicColor(classic)&&(ps==null||ps.bkValue!=classic)&&classicPatternOverColors())return ~quickDrawColor(classic)&0xffffff;
		return ~readRgb(currentPort+42)&0xffffff;}
	/**
	 * The ink of a classic GrafPort's fgColor or bkColor: QuickDraw's ColorMap takes bit colrBit (normally 0) of the colour as the
	 * pixel - set for blackColor and every colour but whiteColor - so a basic port draws in black and white only. Color QuickDraw
	 * keeps that for a GrafPort's 1-bit bitmap (IM V: drawing in an old port is colorized by fgColor/bkColor). Canvas 3.5 builds
	 * the mask for its Gradient Fill tool in a 1-bit OpenPort with RGBForeColor(white), RGBBackColor(black) and a zero pen
	 * pattern: the pattern's 0 bits are the background colour, so PaintRect sets every bit of the mask; drawn black on white
	 * regardless, the mask stayed empty and Gear's blue blueprint never showed.
	 */
	private int classicInk(int field) throws M68kCpuException {
		int bit=memory.readWord(currentPort+COLR_BIT)&31;return (memory.readLong(currentPort+field)>>>bit&1)!=0?0xffffff:0;
	}
	/** The classic colour constant RGBForeColor/RGBBackColor give a GrafPort: the high bit of each component picks one of the eight
	 *  colours (Inside Macintosh: Imaging with QuickDraw, RGBForeColor, "If the current port is defined by a GrafPort record"). */
	private static int classicColorOf(int red,int green,int blue){
		switch((red&0x8000)>>>13|(green&0x8000)>>>14|(blue&0x8000)>>>15){case 0:return 33;case 1:return 409;case 2:return 341;case 3:return 273;
			case 4:return 205;case 5:return 137;case 6:return 69;default:return 30;}
	}
	/** One of QuickDraw's eight classic colour constants (IM I: whiteColor ... blueColor), as ForeColor/BackColor take them. */
	/** Whether {@code port} draws plain black on white: rgbFgColor black and rgbBkColor white in a colour port, fgColor blackColor and
	 *  bkColor whiteColor in a classic one. Reads only the record (the event thread asks it of the card window's port). */
	boolean plainColors(int port) throws M68kCpuException {
		if(port==0)return true;
		if(!isColorPort(port))return memory.readLong(port+FG_COLOR)==33&&memory.readLong(port+BK_COLOR)==30;
		PortState ps=ports.get(port);if(ps!=null&&(ps.fgIndex>=0||ps.bkIndex>=0))return false;
		return readRgb(port+36)==0&&readRgb(port+42)==0xffffff;
	}
	private static boolean isClassicColor(int value){return value==30||value==33||value==69||value==137||value==205||value==273||value==341||value==409;}
	/**
	 * True when a program has written a classic GrafPort's patterns over this colour port's RGB colour fields. A GrafPort
	 * keeps bkPat at +32 and fillPat at +40, exactly where a CGrafPort keeps bkPixPat, rgbFgColor and rgbBkColor, so a
	 * program written before Color QuickDraw that assigns thePort->fillPat leaves rgbFgColor as (0,0,$FFFF): Billiard
	 * Parlour (1985) drew its whole table blue on white. Its pen colours come from fgColor/bkColor at +80/+84 instead -
	 * the fields both layouts share, which ForeColor/BackColor and {@link #updatePortColorValue} keep up to date.
	 */
	private boolean classicPatternOverColors() throws M68kCpuException {
		return pokedPattern(currentPort+FILL_PAT)||pokedPattern(currentPort+BK_PAT);
	}
	/** PmForeColor/PmBackColor of an animated/explicit entry (HostQuickDraw68k): the current port draws colour-table index {@code index}; -1 clears it. */
	void setPortColorIndex(boolean foreground,int index) throws M68kCpuException {if(currentPort==0)return;PortState ps=state();if(foreground)ps.fgIndex=index;else ps.bkIndex=index;updatePortColorValue(foreground);}
	/** A CGrafPort's fgColor/bkColor hold the PIXEL VALUE of rgbFgColor/rgbBkColor on the port's device (Color2Index); programs that
	 *  write pixels themselves read them there - some games paint offscreen with (*thePort).fgColor after PmForeColor, and
	 *  got the untouched blackColor constant 33 everywhere. */
	private void updatePortColorValue(boolean foreground) throws M68kCpuException {
		if(currentPort==0||!isColorPort(currentPort))return;
		try{Bitmap bitmap=readCurrentBitmap();PortState ps=state();
			int ink=foreground?portForegroundInk():portBackgroundInk();int value=bitmap.pixelValue(ink);if(bitmap.direct)value&=0xffffff;
			memory.writeLong(currentPort+(foreground?FG_COLOR:BK_COLOR),value);if(foreground)ps.fgValue=value;else ps.bkValue=value;}
		catch(M68kCpuException e){/* a port without usable bits keeps its old value */}
	}
	/**
	 * The RGB of an old-style colour long (IM I-173: bit 5 black, 6 yellow, 7 magenta, 8 cyan - planes of ink, as on the
	 * ImageWriter ribbon), as Color QuickDraw maps ForeColor/BackColor: black ink, or each of cyan/magenta/yellow takes
	 * away red/green/blue. The eight constants are just combinations (blueColor 409 = cyan+magenta); Anthony's
	 * MakeBarXCMD and Draw XCMD paint with the other ones (385 = cyan+magenta+normal = blue, 193 = magenta+yellow = red,
	 * 1 = no ink = white): mapped to black by a table of the eight, the demo's rainbow was eight black bars.
	 */
	static int quickDrawColor(int value) {if((value&0x20)!=0)return 0;
		return ((value&0x100)!=0?0:0xff0000)|((value&0x80)!=0?0:0x00ff00)|((value&0x40)!=0?0:0x0000ff);}
	private int readRgb(int pointer) throws M68kCpuException {return (memory.readWord(pointer)>>>8)<<16|(memory.readWord(pointer+2)>>>8)<<8|memory.readWord(pointer+4)>>>8;}
	private void writeRgb(int pointer,int red,int green,int blue) throws M68kCpuException {memory.writeWord(pointer,red);memory.writeWord(pointer+2,green);memory.writeWord(pointer+4,blue);}

	private boolean discard(M68kCpu cpu,int bytes) {cpu.a[7]+=bytes;return true;}
	private PortState state() throws M68kCpuException {
		PortState result=ports.get(currentPort);if(result==null){result=stateFromMemory(currentPort);ports.put(currentPort,result);}return result;
	}

	private void offsetRectAt(int pointer,int dh,int dv) throws M68kCpuException {
		Rect rect=readRect(pointer);writeRect(pointer,rect.top+dv,rect.left+dh,rect.bottom+dv,rect.right+dh);
	}
	private Rect readRect(int pointer) throws M68kCpuException {return new Rect((short)memory.readWord(pointer),
		(short)memory.readWord(pointer+2),(short)memory.readWord(pointer+4),(short)memory.readWord(pointer+6));}
	private void writeRect(int pointer,Rect rect) throws M68kCpuException {writeRect(pointer,rect.top,rect.left,rect.bottom,rect.right);}
	private void writeRect(int pointer,int top,int left,int bottom,int right) throws M68kCpuException {
		memory.writeWord(pointer,top);memory.writeWord(pointer+2,left);memory.writeWord(pointer+4,bottom);memory.writeWord(pointer+6,right);
	}
	private void writePoint(int pointer,int v,int h) throws M68kCpuException {memory.writeWord(pointer,v);memory.writeWord(pointer+2,h);}
	private void writeBoolean(int pointer,boolean value) throws M68kCpuException {memory.writeWord(pointer,0);memory.writeByte(pointer,value?1:0);}
	private boolean sameRect(Rect a,Rect b) {return a.top==b.top&&a.left==b.left&&a.bottom==b.bottom&&a.right==b.right;}
	private static void putWord(byte[] bytes,int offset,int value){bytes[offset]=(byte)(value>>>8);bytes[offset+1]=(byte)value;}
	private static void putLong(byte[] bytes,int offset,int value){putWord(bytes,offset,value>>>16);putWord(bytes,offset+2,value);}
	private static void putRect(byte[] bytes,int offset,Rect rect){putWord(bytes,offset,rect.top);putWord(bytes,offset+2,rect.left);
		putWord(bytes,offset+4,rect.bottom);putWord(bytes,offset+6,rect.right);}

	static String name(int trap) {
		switch(trap) {
			case 0x862:return "ForeColor";case 0x863:return "BackColor";case 0x864:return "ColorBit";
			case 0x86d:return "InitPort";case 0x86e:return "InitGraf";case 0x86f:return "OpenPort";
			case 0x870:return "LocalToGlobal";case 0x871:return "GlobalToLocal";case 0x873:return "SetPort";
			case 0x874:return "GetPort";case 0x875:return "SetPortBits";case 0x876:return "PortSize";
			case 0x877:return "MovePortTo";case 0x878:return "SetOrigin";case 0x879:return "SetClip";
			case 0x87a:return "GetClip";case 0x87b:return "ClipRect";
			case 0x87c:return "BackPat";case 0x87d:return "ClosePort";case 0x87e:return "AddPt";
			case 0x87f:return "SubPt";case 0x880:return "SetPt";case 0x881:return "EqualPt";
			case 0x883:return "DrawChar";case 0x884:return "DrawString";case 0x885:return "DrawText";
			case 0x886:return "TextWidth";case 0x887:return "TextFont";case 0x888:return "TextFace";
			case 0x889:return "TextMode";case 0x88a:return "TextSize";case 0x88b:return "GetFontInfo";
			case 0x88c:return "StringWidth";case 0x88d:return "CharWidth";case 0x88e:return "SpaceExtra";
			case 0x891:return "LineTo";case 0x892:return "Line";case 0x893:return "MoveTo";case 0x894:return "Move";
			case 0x896:return "HidePen";case 0x897:return "ShowPen";case 0x898:return "GetPenState";
			case 0x899:return "SetPenState";case 0x89a:return "GetPen";case 0x89b:return "PenSize";
			case 0x89c:return "PenMode";case 0x89d:return "PenPat";case 0x89e:return "PenNormal";
			case 0x8a1:return "FrameRect";case 0x8a2:return "PaintRect";case 0x8a3:return "EraseRect";
			case 0x8a4:return "InvertRect";case 0x8a5:return "FillRect";case 0x8a6:return "EqualRect";
			case 0x8a7:return "SetRect";case 0x8a8:return "OffsetRect";case 0x8a9:return "InsetRect";
			case 0x8aa:return "SectRect";case 0x8ab:return "UnionRect";case 0x8ac:return "Pt2Rect";
			case 0x8ad:return "PtInRect";case 0x8ae:return "EmptyRect";
			case 0x8b0:return "FrameRoundRect";case 0x8b1:return "PaintRoundRect";case 0x8b2:return "EraseRoundRect";
			case 0x8b3:return "InvertRoundRect";case 0x8b4:return "FillRoundRect";
			case 0x8b7:return "FrameOval";case 0x8b8:return "PaintOval";case 0x8b9:return "EraseOval";
			case 0x8ba:return "InvertOval";case 0x8bb:return "FillOval";
			case 0x8be:return "FrameArc";case 0x8bf:return "PaintArc";case 0x8c0:return "EraseArc";
			case 0x8c1:return "InvertArc";case 0x8c2:return "FillArc";
			case 0x8c6:return "FramePoly";case 0x8c7:return "PaintPoly";case 0x8c8:return "ErasePoly";
			case 0x8c9:return "InvertPoly";case 0x8ca:return "FillPoly";case 0x8cb:return "OpenPoly";
			case 0x8cc:return "ClosePoly";case 0x8cd:return "KillPoly";case 0x8ce:return "OffsetPoly";
			case 0x8d2:return "FrameRgn";case 0x8d3:return "PaintRgn";case 0x8d4:return "EraseRgn";
			case 0x8d5:return "InvertRgn";case 0x8d6:return "FillRgn";case 0x8d7:return "BitMapToRegion";case 0x8d8:return "NewRgn";
			case 0x8d9:return "DisposeRgn";case 0x8da:return "OpenRgn";case 0x8db:return "CloseRgn";
			case 0x8dc:return "CopyRgn";case 0x8dd:return "SetEmptyRgn";case 0x8de:return "SetRectRgn";
			case 0x8df:return "RectRgn";case 0x8e0:return "OffsetRgn";case 0x8e1:return "InsetRgn";
			case 0x8e2:return "EmptyRgn";case 0x8e3:return "EqualRgn";case 0x8e4:return "SectRgn";
			case 0x8e5:return "UnionRgn";case 0x8e6:return "DiffRgn";case 0x8e7:return "XorRgn";
			case 0x8e8:return "PtInRgn";case 0x8e9:return "RectInRgn";case 0xb29:return "RectRegionTest";case 0xb27:return "PixMapBlit";case 0xb0f:return "QDInternalTest";case 0x8ec:return "CopyBits";
			case 0x817:return "CopyMask";case 0x839:return "SeedFill";case 0x838:return "CalcMask";case 0xa50:return "SeedCFill";case 0xa4f:return "CalcCMask";
			case 0x8ef:return "ScrollRect";
			case 0x8f3:return "OpenPicture";case 0x8f4:return "ClosePicture";case 0x8f5:return "KillPicture";
			case 0x8f6:return "DrawPicture";
			case 0xa00:return "OpenCPort";case 0xa01:return "InitCPort";case 0xa02:return "CloseCPort";
			case 0xa03:return "NewPixMap";case 0xa04:return "DisposPixMap";case 0xa05:return "CopyPixMap";case 0xa06:return "SetPortPix";
			case 0xa07:return "NewPixPat";case 0xa08:return "DisposPixPat";case 0xa09:return "CopyPixPat";
			case 0xa0a:return "PenPixPat";case 0xa0b:return "BackPixPat";case 0xa0c:return "GetPixPat";case 0xa0d:return "MakeRGBPat";
			case 0xa0e:return "FillCRect";case 0xa0f:return "FillCOval";case 0xa10:return "FillCRoundRect";
			case 0xa11:return "FillCArc";case 0xa12:return "FillCRgn";case 0xa13:return "FillCPoly";
			case 0xa14:return "RGBForeColor";case 0xa15:return "RGBBackColor";case 0xa16:return "SetCPixel";
			case 0xa17:return "GetCPixel";case 0xa18:return "GetCTable";case 0xa19:return "GetForeColor";case 0xa1a:return "GetBackColor";
			case 0xa1b:return "GetCCursor";case 0xa1c:return "SetCCursor";case 0xa1d:return "AllocCursor";case 0xa1e:return "GetCIcon";case 0xa1f:return "PlotCIcon";case 0xa25:return "DisposeCIcon";case 0xa26:return "DisposeCCursor";
			case 0xa21:return "OpColor";case 0xa22:return "HiliteColor";case 0xa24:return "DisposCTable";case 0xa28:return "GetCTSeed";
			case 0xa27:return "GetMaxDevice";case 0xa29:return "GetDeviceList";case 0xa2a:return "GetMainDevice";
			case 0xa2b:return "GetNextDevice";case 0xa2c:return "TestDeviceAttribute";case 0xa2d:return "SetDeviceAttribute";
			case 0xa2e:return "InitGDevice";case 0xa2f:return "NewGDevice";case 0xa30:return "DisposeGDevice";
			case 0xa31:return "SetGDevice";case 0xa32:return "GetGDevice";
			case 0xa33:return "Color2Index";case 0xa34:return "Index2Color";case 0xa35:return "InvertColor";case 0xa36:return "RealColor";case 0xa39:return "MakeITable";
			case 0xa3a:return "AddSearch";case 0xa3b:return "AddComp";case 0xa3c:return "SetClientID";case 0xa4c:return "DelSearch";case 0xa4d:return "DelComp";case 0xa3d:return "ProtectEntry";case 0xa3e:return "ReserveEntry";case 0xa3f:return "SetEntries";case 0xa40:return "QDError";
			case 0xaa2:return "PMgrDispatch";case 0xb1d:return "QDExtensions";case 0xb03:return "ThirtyTwoBitQD";case 0xa51:return "CopyDeepMask";case 0xa37:return "GetSubTable";
			default:return null;
		}
	}

	private static final class PortState {int originV,originH;Rect clip=new Rect(-32767,-32767,32767,32767);
		/** A playing picture's non-rectangular clip region (port coordinates), or null. */
		QdRegion pictureClip;
		/** A NewWindow (not NewCWindow) window: the record keeps the classic portBits.bounds at offset 8 (see {@link #newWindowPort}); its grafVars Handle lives here instead. */
		boolean classicWindow;int grafVars;
		/** SetPortBits on a classic window (see {@link #setWindowPortBits}): the Handle standing in for the program's BitMap while it is the port's target (0 otherwise), and the window's own 14 bytes to put back. */
		int portBitsHandle;byte[] windowBits;
		/** The record's own 6 bytes (buffer Handle, $C000) while the program sees the screen BitMap there (see {@link #leaveToolbox}). */
		byte[] internalBits;
		/** The 14 portBits bytes {@link #leaveToolbox} left in the record for the program: different at the next trap = the program stored a BitMap there itself. */
		byte[] shownBits;
		/** PmForeColor/PmBackColor of an animated or explicit palette entry: draw that colour-table INDEX (the entry the Palette Manager reserved
		 *  for it, which our activation maps 1:1), not the nearest colour - some games paint with palette indices whose colours
		 *  are all black until AnimatePalette fades them in. -1: none, the RGB colours rule. */
		int fgIndex=-1,bkIndex=-1;int windowBkRgb=-1;
		/** The pixel values {@link #updatePortColorValue} last stored in fgColor/bkColor (-1: none): such a value is no classic colour
		 *  constant even when it equals one - Factory 1.1's RGBForeColor(#EEEEEE) matched entry 137 (= magentaColor) and its
		 *  offscreen wall came out magenta, because the rgbFgColor bytes at +40 look like a poked fillPat. */
		int fgValue=-1,bkValue=-1;
		/** QDSetPatternOrigin (NQD 8.5 / CarbonLib 1.3): added to the local point a pattern pixel is taken for (see {@link #setPatternOrigin}). */
		int patternOriginH,patternOriginV;}
	/** An ink carrying a colour-table index instead of an inverted RGB: bit 30 set, index in the low byte (see PortState.fgIndex). */
	static final int INDEX_INK=0x40000000;
	private static class PixelMapState {
		int pixMap;final int pixels,depth,rowBytes;int colorTable,pixelState;
		/** Bounds of the pixel buffer in the port's local coordinates (a window's move with SetOrigin). */
		Rect bounds;
		/** A window buffer whose guest-visible PixMap record describes the screen instead (see {@link #describeWindowPixMap}). */
		boolean screenDescribed;
		/** The colour table belongs to the main device (hosted windows on an indexed screen): never disposed with the PixMap. */
		boolean sharedColorTable;
		/** The window port this buffer backs (0 for GWorlds and other offscreen PixMaps). */
		int windowPort;
		/** The pixels are the caller's buffer (NewGWorldFromPtr): the pixels Handle only points at them and frees nothing. */
		boolean externalPixels;
		PixelMapState(int pixMap,int pixels,int colorTable,int depth,int rowBytes,Rect bounds){this.pixMap=pixMap;this.pixels=pixels;
			this.colorTable=colorTable;this.depth=depth;this.rowBytes=rowBytes;this.bounds=bounds;}
	}
	private static final class GWorldState extends PixelMapState {
		final int port,device,offscreenDevice; // device: the GDevice given to NewGWorld; offscreenDevice: the GWorld's own
		/** The aGDevice of a GWorld made with noNewDevice (0 otherwise): it has no device of its own, so colours drawn into it
		 *  are matched through that device's table while it is current (see {@link #matchThroughCurrentDevice}). */
		int sharedDevice;
		/** The GWorldFlags NewGWorld/UpdateGWorld got (useTempMem decides what UnlockPixels leaves in baseAddr, see lockPixels). */
		int flags;
		GWorldState(int port,int pixMap,int pixels,int colorTable,int device,int offscreenDevice,int depth,int rowBytes,Rect bounds){super(pixMap,pixels,colorTable,depth,rowBytes,bounds);
			this.port=port;this.device=device;this.offscreenDevice=offscreenDevice;}
	}
	private static final class DynamicDeviceState {
		final int inverseTable;PixelMapState pixels;
		DynamicDeviceState(PixelMapState pixels,int inverseTable){this.pixels=pixels;this.inverseTable=inverseTable;}
	}
	private static final class QdPoint {final int h,v;QdPoint(int h,int v){this.h=h;this.v=v;}}
	/**
	 * A window that was created invisible and never shown has, on a real Mac, a port whose bits are the screen and
	 * whose visRgn is whatever OpenPort left there (the whole screen): drawing into it lands on the screen at the
	 * window's position, over every visible window. Some game runtimes rely on this and blit their frames into
	 * such a window after setting its visRgn to the dirty rectangle themselves. Hosted windows draw into private
	 * buffers, so such a port is routed like the OpenPort screen port instead; once shown (or hidden after being
	 * shown, when the real visRgn is empty) the window's own buffer is used again.
	 */
	/** The window buffer whose pixels start at {@code base}, or null. */
	private PixelMapState windowBufferAt(int base) throws M68kCpuException {
		if(base==0)return null;
		for(PixelMapState state:offscreenPixMapData.values())if(state.windowPort!=0&&state.pixels!=0&&memory.readLong(state.pixels)==base)return state;
		return null;
	}
	private Bitmap hiddenWindowScreenBitmap(int pixMap,int port) throws M68kCpuException {return hiddenWindowScreenBitmap(pixMap,port,false);}
	/** @param deviceAlone with no window on the screen the drawing still goes to the screen device (a copy of the PixMap is no port
	 *  that could keep its drawing for later) */
	private Bitmap hiddenWindowScreenBitmap(int pixMap,int port,boolean deviceAlone) throws M68kCpuException {
		// While a picture records, a never-shown window is a real offscreen buffer to draw into and read back (an editor's
		// DLOG/DITL editor records DrawDialog of an invisible dialog to build its mini-screen), not a stand-in for the screen.
		if(recordingPicture())return null;
		Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted())return null;
		WindowManager68k.Window w=tb.windows.window(port);if(w==null||w.visible||w.everShown)return null;
		int[] windows=tb.windows.screenWindows();if(windows.length==0&&!deviceAlone)return null;
		Rect bounds=pixMapBounds(pixMap,offscreenPixMapData.get(pixMap));return new ScreenBitmap(pixMap,windows,w.left-bounds.left+w.frameLeft,w.top-bounds.top+w.frameTop);
	}

	/**
	 * On a real Mac every window's portBits IS the screen, only with the window's local coordinates: a program that hands
	 * CopyBits another window's portBits while a different port is current draws on the screen at that window's position,
	 * clipped by the current port - not into that window. Some programs show a menu as a full-screen dialog in front of
	 * its main window and blits the castle picture through the main window's portBits every frame; with private window
	 * buffers the picture landed in the covered window. Such a destination is routed to the windows covering each pixel.
	 */
	private Bitmap otherWindowScreenBitmap(int pixMap,int port) throws M68kCpuException {
		if(port==currentPort||currentPort==0||recordingPicture()||buildingScreenBitmap)return null;
		Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted())return null;
		WindowManager68k.Window w=tb.windows.window(port),current=tb.windows.window(currentPort);if(w==null||!w.visible||current!=null&&!current.visible)return null; // the current port: another window, or a port of the program's own opened on the screen
		int[] windows=tb.windows.screenWindows();if(windows.length==0||windows[0]==port)return null; // the window is frontmost: its own buffer is the screen there
		// local = global - window position + origin; the buffer's bounds start at the origin, or above/left of it by the frame a custom WDEF draws
		Rect bounds=pixMapBounds(pixMap,offscreenPixMapData.get(pixMap));return new ScreenBitmap(pixMap,windows,w.left-bounds.left+w.frameLeft,w.top-bounds.top+w.frameTop);
	}
	/**
	 * ShowWindow with PaintWhite FALSE ($9DC): the Window Manager does not erase the content, so on a real Mac the window shows
	 * the screen that was under it until the program draws - and a program may read those pixels back through its port (Bryce 2
	 * darkens them for the drop shadows of its dialogs). The buffer takes the visible windows behind it, and the device pixels
	 * where none is (white while nothing was ever written there).
	 */
	void fillWindowFromScreen(int port) throws M68kCpuException {
		Toolbox68k tb=macApi.application();if(tb==null||!tb.hosted())return;
		WindowManager68k.Window w=tb.windows.window(port);int pixMap=bitmapPointer(port);if(w==null||pixMap==0)return;
		Bitmap own=readBitmapRaw(pixMap);if(own.base==0||own.rowBytes<=0||!own.direct&&own.pixelSize>8)return;
		int[] all=tb.windows.screenWindows(),others=new int[all.length];int n=0;
		for(int i=0;i<all.length;i+=3)if(all[i]!=port){others[n]=all[i];others[n+1]=all[i+1];others[n+2]=all[i+2];n+=3;}
		ScreenBitmap screen=new ScreenBitmap(pixMap,java.util.Arrays.copyOf(others,n),w.left-own.bounds.left+w.frameLeft,w.top-own.bounds.top+w.frameTop);
		boolean deviceShown=memory.deviceWritten();int width=own.bounds.right-own.bounds.left;if(width<=0)return;int[] rgb=new int[width];
		for(int v=own.bounds.top;v<own.bounds.bottom;v++){
			for(int h=own.bounds.left;h<own.bounds.right;h++){int gh=h+screen.dh,gv=v+screen.dv;
				rgb[h-own.bounds.left]=screen.target(gh,gv)<0&&!deviceShown?0xffffff:~screen.getInkRgb(h,v)&0xffffff;}
			own.setRgbRow(v,own.bounds.left,rgb,0,width);}
	}
	/** Set while a ScreenBitmap reads its target windows' buffers (those must not be routed again). */
	private boolean buildingScreenBitmap;

	/**
	 * The screen port's bits for a hosted application: every pixel is routed to the frontmost window covering it,
	 * and mirrored into the main device PixMap (the real screen memory, which programs may also write directly).
	 * Coordinates are the port's local ones; {@code dh,dv} take them to global (0 for the screen port itself).
	 */
	private final class ScreenBitmap extends Bitmap {
		private final Bitmap[] targets;private final int[] lefts,tops;private final Bitmap device;private final int dh,dv;
		ScreenBitmap(int pointer,int[] windows) throws M68kCpuException {this(pointer,windows,0,0);}
		ScreenBitmap(int pointer,int[] windows,int dh,int dv) throws M68kCpuException {
			super(1,1,pixMapBounds(pointer,offscreenPixMapData.get(pointer)),32,true,null);int n=windows.length/3;this.dh=dh;this.dv=dv;
			targets=new Bitmap[n];lefts=new int[n];tops=new int[n];
			// each window's own buffer: a window behind another is no "other window's portBits" to route again (a custom WDEF
			// drawing its frame in the Window Manager port while a dialog was in front landed in the frame shifted by the frame's size)
			boolean building=buildingScreenBitmap;buildingScreenBitmap=true;
			try{for(int i=0;i<n;i++){int port=windows[i*3];Bitmap b;try{b=port==0?null:readBitmap(bitmapPointer(port));}catch(M68kCpuException e){b=null;}
				targets[i]=b;lefts[i]=windows[i*3+1];tops[i]=windows[i*3+2];}}
			finally{buildingScreenBitmap=building;}
			Bitmap d=null;try{int[] frame=mainDeviceFrame();int map=mainDevicePixMap==0?0:memory.readLong(mainDevicePixMap);if(map!=0&&frame!=null&&frame[0]!=0)d=readBitmap(map,frame[0]);}catch(M68kCpuException e){d=null;}device=d; // baseOverride bypasses the screen routing
		}
		/**
		 * The rows of {@code drawingArea} from a direct source (unscaled): each row's pixels go to the windows covering them
		 * (front to back, a run per window) and to the device, a row at a time.
		 */
		void copyRgbRows(Bitmap source,Rect sourceArea,Rect destinationArea,Rect drawingArea) throws M68kCpuException {
			int width=drawingArea.right-drawingArea.left;
			int[] rgb=rgbRowScratch=ints(rgbRowScratch,width),owner=ownerScratch=ints(ownerScratch,width);
			int sx0=drawingArea.left-destinationArea.left+sourceArea.left;
			for(int v=drawingArea.top;v<drawingArea.bottom;v++){
				source.readRgbRow(v-destinationArea.top+sourceArea.top,sx0,rgb,width);
				int gv=v+dv,gl=drawingArea.left+dh;
				java.util.Arrays.fill(owner,0,width,-1);
				for(int i=targets.length-1;i>=0;i--){Bitmap b=targets[i];if(b==null||b.base==0||b.rowBytes<=0)continue;
					int top=b.bounds.top+tops[i],bottom=b.bounds.bottom+tops[i];if(gv<top||gv>=bottom)continue;
					int from=Math.max(0,b.bounds.left+lefts[i]-gl),to=Math.min(width,b.bounds.right+lefts[i]-gl);
					if(from<to)java.util.Arrays.fill(owner,from,to,i);}
				for(int x=0;x<width;){int i=owner[x],end=x+1;while(end<width&&owner[end]==i)end++;
					if(i>=0)targets[i].setRgbRow(gv-tops[i],gl+x-lefts[i],rgb,x,end-x);
					if(device!=null){boolean was=memory.mirrorDevice(i>=0);try{device.setRgbRow(gv,gl+x,rgb,x,end-x);}finally{memory.mirrorDevice(was);}}
					x=end;}
			}
		}
		/** Index of the frontmost visible window covering global (gh,gv). */
		private int target(int gh,int gv){for(int i=0;i<targets.length;i++){Bitmap b=targets[i];if(b!=null&&b.contains(gh-lefts[i],gv-tops[i]))return i;}return -1;}
		@Override boolean contains(int h,int v){return QuickDraw68k.this.contains(bounds,h,v);}
		@Override int getInkRgb(int h,int v) throws M68kCpuException {int gh=h+dh,gv=v+dv,i=target(gh,gv);return i>=0?targets[i].getInkRgb(gh-lefts[i],gv-tops[i]):device!=null?device.getInkRgb(gh,gv):0;}
		@Override void setInkRgb(int h,int v,int ink) throws M68kCpuException {int gh=h+dh,gv=v+dv,i=target(gh,gv);if(i>=0)targets[i].setInkRgb(gh-lefts[i],gv-tops[i],ink);
			if(device!=null){boolean was=memory.mirrorDevice(i>=0);try{device.setInkRgb(gh,gv,ink);}finally{memory.mirrorDevice(was);}}}
		@Override void setArgb(int h,int v,int argb) throws M68kCpuException {int gh=h+dh,gv=v+dv,i=target(gh,gv);if(i>=0)targets[i].setArgb(gh-lefts[i],gv-tops[i],argb);
			if(device!=null){boolean was=memory.mirrorDevice(i>=0);try{device.setArgb(gh,gv,argb);}finally{memory.mirrorDevice(was);}}}
		/**
		 * Each window gets the part of the span it covers (front to back, later windows only where no earlier one is); the device
		 * gets it all. Device pixels under a window are the window's own drawing mirrored (see M68kMemory.mirrorDevice): only the
		 * runs no window covers count as the program's writes to the screen.
		 */
		@Override void fillSpan(int v,int left,int right,int patternRow,int onInk,int offInk) throws M68kCpuException {
			left=Math.max(left,bounds.left);right=Math.min(right,bounds.right);if(left>=right)return;int gv=v+dv;
			for(int h=left;h<right;){int gh=h+dh,i=target(gh,gv);int end=h+1;while(end<right&&target(end+dh,gv)==i)end++;
				if(i>=0){
					// pattern bits are indexed by the port-local h: rotate the row so the window-local x lines up
					int shift=(lefts[i]-dh)&7;int row=(patternRow<<shift|patternRow>>>(8-shift))&0xff;
					targets[i].fillSpan(gv-tops[i],gh-lefts[i],end+dh-lefts[i],row,onInk,offInk);}
				if(device!=null){int shift=(-dh)&7;boolean was=memory.mirrorDevice(i>=0);
					try{device.fillSpan(gv,h+dh,end+dh,(patternRow<<shift|patternRow>>>(8-shift))&0xff,onInk,offInk);}finally{memory.mirrorDevice(was);}}
				h=end;}
		}
	}

	/** The colours of an indexed colour port's PixMap, from its own table (an offscreen GWorld's may differ from the screen's), or null. */
	int[] colorPortPalette(int port) throws M68kCpuException {
		if(port==0||!isColorPort(port))return null;int map=bitmapPointer(port);if(map==0)return null;
		int depth=memory.readWord(map+32),table=memory.readLong(map+42);if(depth>8||table==0||memory.readLong(table)==0)return null;
		return readColorTable(table,depth);
	}

	/** The main device PixMap as {pixel base, rowBytes, depth, left, top, right, bottom}, or null (for direct-screen display). */
	int[] mainDeviceFrame() throws M68kCpuException {
		if(mainDevicePixMap==0)return null;int map=memory.readLong(mainDevicePixMap);if(map==0)return null;
		PixelMapState state=offscreenPixMapData.get(map);int base=state!=null?memory.readLong(state.pixels):memory.readLong(map);
		Rect b=readRect(map+6);return new int[]{base,memory.readWord(map+4)&0x3fff,memory.readWord(map+32),b.left,b.top,b.right,b.bottom};
	}
	/** The main device colour table as packed RGB (indexed depths), or null for direct colour. */
	int[] mainDevicePalette() throws M68kCpuException {return mainDevicePalette(null);}
	/** As {@link #mainDevicePalette()}, filling {@code reuse} instead of allocating when it has the right size (the host asks 30 times a second). */
	int[] mainDevicePalette(int[] reuse) throws M68kCpuException {
		if(mainDevicePixMap==0)return null;int map=memory.readLong(mainDevicePixMap);if(map==0)return null;int depth=memory.readWord(map+32);
		return depth>8?null:readColorTable(memory.readLong(map+42),depth,reuse!=null&&reuse.length==1<<depth?reuse:null);
	}

	// ------------------------------------------------------------------ seed fills and lasso masks (IM I-195, IM V-72)

	/**
	 * SeedFill(srcPtr, dstPtr, srcRow, dstRow, height, words, seedH, seedV): the mask of the white pixels a paint-bucket
	 * fill starting at the seed reaches (1 = filled); a seed on a black pixel fills nothing. Only {@code words} words of
	 * every destination row are written.
	 */
	private boolean seedFill(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],seedV=(short)memory.readWord(sp),seedH=(short)memory.readWord(sp+2),words=(short)memory.readWord(sp+4),height=(short)memory.readWord(sp+6);
		int dstRow=(short)memory.readWord(sp+8),srcRow=(short)memory.readWord(sp+10),dst=memory.readLong(sp+12),src=memory.readLong(sp+16);cpu.a[7]+=20;
		if(Mac68kApi.TRACE)System.err.println("[68K] SeedFill src $"+Integer.toHexString(src)+"/"+srcRow+" dst $"+Integer.toHexString(dst)+"/"+dstRow+" "+words+" words x "+height+" seed "+seedH+","+seedV);
		if(src==0||dst==0||words<=0||height<=0)return true;
		int width=words*16;boolean[] boundary=readMaskBits(src,srcRow,width,height);
		boolean[] mask=maskScratch=booleans(maskScratch,width*height);java.util.Arrays.fill(mask,0,width*height,false);
		floodFrom(boundary,mask,width,height,seedH,seedV);
		writeMaskBits(dst,dstRow,width,height,mask);return true;
	}
	/**
	 * CalcMask(srcPtr, dstPtr, srcRow, dstRow, height, words): the lasso mask - 1 for every pixel a fill from outside the
	 * image cannot reach (the black pixels and the white areas they enclose).
	 */
	private boolean calcMask(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],words=(short)memory.readWord(sp),height=(short)memory.readWord(sp+2),dstRow=(short)memory.readWord(sp+4),srcRow=(short)memory.readWord(sp+6);
		int dst=memory.readLong(sp+8),src=memory.readLong(sp+12);cpu.a[7]+=16;
		if(Mac68kApi.TRACE)System.err.println("[68K] CalcMask src $"+Integer.toHexString(src)+"/"+srcRow+" dst $"+Integer.toHexString(dst)+"/"+dstRow+" "+words+" words x "+height);
		if(src==0||dst==0||words<=0||height<=0)return true;
		int width=words*16;boolean[] boundary=readMaskBits(src,srcRow,width,height);
		boolean[] mask=maskScratch=booleans(maskScratch,width*height);lassoMask(boundary,mask,width,height);
		writeMaskBits(dst,dstRow,width,height,mask);return true;
	}
	/**
	 * SeedCFill(srcBits, dstBits, srcRect, dstRect, seedH, seedV, matchProc, matchData): SeedFill for a bitmap or pixel map.
	 * Pixels of the seed pixel's colour are the ones the fill flows through, every other colour bounds it; a matchProc
	 * (a Color2Index search procedure; theGDevice's gdRefCon points at a MatchRec of the seed colour and matchData) classifies the colours instead: position 0 =
	 * fillable, anything else = boundary. The seed point is in the source's coordinate system, like srcRect.
	 */
	private boolean seedCFill(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7],matchData=memory.readLong(sp),matchProc=memory.readLong(sp+4),seedV=(short)memory.readWord(sp+8),seedH=(short)memory.readWord(sp+10);
		int dstRect=memory.readLong(sp+12),srcRect=memory.readLong(sp+16),dstBits=memory.readLong(sp+20),srcBits=memory.readLong(sp+24);cpu.a[7]+=28;
		if(srcBits==0||dstBits==0||srcRect==0||dstRect==0)return true;
		Bitmap source=readBitmap(srcBits),destination=readBitmap(dstBits);Rect sourceArea=readRect(srcRect),destinationArea=readRect(dstRect);
		int width=sourceArea.right-sourceArea.left,height=sourceArea.bottom-sourceArea.top;
		if(Mac68kApi.TRACE)System.err.println("[68K] SeedCFill src bounds "+source.bounds+" depth "+source.pixelSize+" rect "+sourceArea+" dst bounds "+destination.bounds+" depth "+destination.pixelSize+" rect "+destinationArea+" seed "+seedH+","+seedV+" matchProc $"+Integer.toHexString(matchProc));
		if(width<=0||height<=0||(long)width*height>16_777_216L)return true;
		// programs pass the seed in the source's local coordinates; one that measured it from the rectangle's corner is
		// recognisable by a seed outside the rectangle that lands inside it when offset
		if(!contains(sourceArea,seedH,seedV)&&contains(sourceArea,sourceArea.left+seedH,sourceArea.top+seedV)){seedH+=sourceArea.left;seedV+=sourceArea.top;}
		boolean[] boundary=colorBoundary(cpu,source,sourceArea,matchProc,matchData,0,seedH,seedV);
		boolean[] mask=maskScratch=booleans(maskScratch,width*height);java.util.Arrays.fill(mask,0,width*height,false);
		floodFrom(boundary,mask,width,height,seedH-sourceArea.left,seedV-sourceArea.top);
		writeMask(destination,destinationArea,width,height,mask);return true;
	}
	/**
	 * CalcCMask(srcBits, dstBits, srcRect, dstRect, seedRGB, matchProc, matchData): CalcMask for a bitmap or pixel map;
	 * seedRGB is the colour of the pixels that are NOT filled (IM: Imaging with QuickDraw): the mask gets 1s for the pixels of
	 * exactly that colour and for everything enclosed by outlines of it - Kingyo2 draws its sprites with black outlines and
	 * lassoes their silhouettes with seedRGB black.
	 */
	private boolean calcCMask(M68kCpu cpu) throws M68kException {
		int sp=cpu.a[7],matchData=memory.readLong(sp),matchProc=memory.readLong(sp+4),seedRgb=memory.readLong(sp+8);
		int dstRect=memory.readLong(sp+12),srcRect=memory.readLong(sp+16),dstBits=memory.readLong(sp+20),srcBits=memory.readLong(sp+24);cpu.a[7]+=28;
		if(srcBits==0||dstBits==0||srcRect==0||dstRect==0)return true;
		Bitmap source=readBitmap(srcBits),destination=readBitmap(dstBits);Rect sourceArea=readRect(srcRect),destinationArea=readRect(dstRect);
		int width=sourceArea.right-sourceArea.left,height=sourceArea.bottom-sourceArea.top;
		int background=seedRgb==0?0xffffff:readRgb(seedRgb);
		if(Mac68kApi.TRACE)System.err.println("[68K] CalcCMask src bounds "+source.bounds+" depth "+source.pixelSize+" rect "+sourceArea+" dst bounds "+destination.bounds+" depth "+destination.pixelSize+" rect "+destinationArea+" seed rgb "+Integer.toHexString(background)+" matchProc $"+Integer.toHexString(matchProc));
		if(width<=0||height<=0||(long)width*height>16_777_216L)return true;
		boolean[] boundary=colorBoundary(cpu,source,sourceArea,matchProc,matchData,background,Integer.MIN_VALUE,0,true);
		boolean[] mask=maskScratch=booleans(maskScratch,width*height);lassoMask(boundary,mask,width,height);
		writeMask(destination,destinationArea,width,height,mask);return true;
	}

	/** The 1-bit image at {@code base} (rowBytes {@code rowBytes}) as booleans, true for a set (black) pixel. */
	private boolean[] readMaskBits(int base,int rowBytes,int width,int height) throws M68kCpuException {
		boolean[] bits=maskBoundaryScratch=booleans(maskBoundaryScratch,width*height);
		for(int y=0;y<height;y++){int row=base+y*rowBytes;for(int x=0;x<width;x+=8){int b=memory.readByte(row+(x>>>3));for(int i=0;i<8;i++)bits[y*width+x+i]=(b&(0x80>>>i))!=0;}}
		return bits;
	}
	private void writeMaskBits(int base,int rowBytes,int width,int height,boolean[] mask) throws M68kCpuException {
		byte[] row=rowScratch=bytes(rowScratch,width>>>3);
		for(int y=0;y<height;y++){for(int x=0;x<width;x+=8){int b=0;for(int i=0;i<8;i++)if(mask[y*width+x+i])b|=0x80>>>i;row[x>>>3]=(byte)b;}memory.load(base+y*rowBytes,row,0,width>>>3);}
	}
	/** Writes a mask into a 1-bit destination rectangle: 1 (black) where the mask is set, 0 elsewhere. */
	private void writeMask(Bitmap destination,Rect area,int width,int height,boolean[] mask) throws M68kCpuException {
		int rows=Math.min(height,area.bottom-area.top),columns=Math.min(width,area.right-area.left);
		for(int y=0;y<rows;y++)for(int x=0;x<columns;x++){int h=area.left+x,v=area.top+y;if(!destination.contains(h,v))continue;
			if(destination.pixelSize==1)destination.setIndex(h,v,mask[y*width+x]?1:0);else destination.set(h,v,!mask[y*width+x]);}
	}
	/**
	 * Which pixels of {@code area} bound a colour fill. Without a matchProc a pixel is fillable when it has the seed's
	 * colour: that of the pixel at ({@code seedH},{@code seedV}) (raw value for indexed sources), or {@code backgroundRgb}
	 * when seedH is MIN_VALUE. With one, the procedure (pascal Boolean proc(const RGBColor*, long* position), theGDevice's
	 * gdRefCon pointing at a MatchRec {seed red, green, blue, matchData}) is asked once per distinct colour; position 0 means fillable, a FALSE result falls back to
	 * the colour comparison.
	 */
	private boolean[] colorBoundary(M68kCpu cpu,Bitmap source,Rect area,int matchProc,int matchData,int backgroundRgb,int seedH,int seedV) throws M68kException {return colorBoundary(cpu,source,area,matchProc,matchData,backgroundRgb,seedH,seedV,false);}
	/** {@code seedIsBoundary}: the given colour bounds the fill (CalcCMask's seedRGB) instead of being the colour the fill flows through (SeedCFill's seed pixel). */
	private boolean[] colorBoundary(M68kCpu cpu,Bitmap source,Rect area,int matchProc,int matchData,int backgroundRgb,int seedH,int seedV,boolean seedIsBoundary) throws M68kException {
		int width=area.right-area.left,height=area.bottom-area.top;boolean[] boundary=maskBoundaryScratch=booleans(maskBoundaryScratch,width*height);
		boolean bySeed=seedH!=Integer.MIN_VALUE,byIndex=bySeed&&!source.direct&&matchProc==0;
		int seedIndex=byIndex&&source.contains(seedH,seedV)?source.getIndex(seedH,seedV):-1;
		int seedRgb=bySeed?(source.contains(seedH,seedV)?~source.getInkRgb(seedH,seedV)&0xffffff:-1):backgroundRgb;
		java.util.Map<Integer,Boolean> decided=matchProc==0?null:new java.util.HashMap<Integer,Boolean>();
		// while the matchProc runs, gdRefCon points at a MatchRec - the seed's red, green, blue and matchData (IM: Imaging With
		// QuickDraw 4-90): Iconographer's Generate Mask compares each colour with that seed (white), and with matchData itself
		// there it compared with the bytes at address 0 and masked the whole icon
		int device=currentGDevice!=0?memory.readLong(currentGDevice):0;int savedRefCon=device==0?0:memory.readLong(device+26);
		int matchRec=device!=0&&matchProc!=0?memory.allocate(10,2):0;
		if(matchRec!=0){int c=Math.max(0,seedRgb);writeRgb(matchRec,(c>>>16&255)*0x101,(c>>>8&255)*0x101,(c&255)*0x101);memory.writeLong(matchRec+6,matchData);memory.writeLong(device+26,matchRec);}
		try{
			for(int y=0;y<height;y++)for(int x=0;x<width;x++){int h=area.left+x,v=area.top+y;boolean bounds;
				if(!source.contains(h,v))bounds=true;
				else if(byIndex)bounds=source.getIndex(h,v)!=seedIndex;
				else{int rgb=~source.getInkRgb(h,v)&0xffffff;
					Boolean known=decided==null?null:decided.get(rgb);
					if(known!=null)bounds=known;
					else{boolean plain=seedIsBoundary?rgb==seedRgb:rgb!=seedRgb;bounds=matchProc!=0?matchesBoundary(cpu,matchProc,rgb,plain):plain;if(decided!=null)decided.put(rgb,bounds);}}
				boundary[y*width+x]=bounds;}
		}finally{if(matchRec!=0){memory.writeLong(device+26,savedRefCon);memory.free(matchRec);}}
		return boundary;
	}
	/** Asks a colour search procedure where a colour goes in a 1-bit table: position 1 (black) bounds the fill. */
	private boolean matchesBoundary(M68kCpu cpu,int proc,int rgb,boolean fallback) throws M68kException {
		int[] d=cpu.d.clone(),a=cpu.a.clone();int savedSp=cpu.a[7];
		try{
			cpu.a[7]-=12;int color=cpu.a[7],position=color+6;writeRgb(color,(rgb>>>16&255)*0x101,(rgb>>>8&255)*0x101,(rgb&255)*0x101);memory.writeLong(position,fallback?1:0);
			cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.pushLong(color);cpu.pushLong(position);
			cpu.callPascal(proc,8);boolean handled=memory.readByte(cpu.a[7])!=0;
			return handled?memory.readLong(position)!=0:fallback;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=savedSp;}
	}
	/** Scan-line flood of the non-boundary pixels 4-connected to the seed into {@code mask} (nothing for a seed outside or on a boundary). */
	private void floodFrom(boolean[] boundary,boolean[] mask,int width,int height,int seedX,int seedY) {
		if(seedX<0||seedY<0||seedX>=width||seedY>=height||boundary[seedY*width+seedX]||mask[seedY*width+seedX])return;
		int[] stack=floodScratch=ints(floodScratch,1024);int top=0;stack[top++]=seedY*width+seedX;mask[seedY*width+seedX]=true;
		while(top>0){int p=stack[--top],y=p/width,x=p-y*width,row=y*width;int left=x,right=x;
			while(left>0&&!boundary[row+left-1]&&!mask[row+left-1]){left--;mask[row+left]=true;}
			while(right+1<width&&!boundary[row+right+1]&&!mask[row+right+1]){right++;mask[row+right]=true;}
			for(int dy=-1;dy<=1;dy+=2){int ny=y+dy;if(ny<0||ny>=height)continue;int next=ny*width;boolean inRun=false;
				for(int nx=left;nx<=right;nx++){boolean open=!boundary[next+nx]&&!mask[next+nx];
					if(open&&!inRun){if(top==stack.length)stack=floodScratch=java.util.Arrays.copyOf(stack,top*2);stack[top++]=next+nx;mask[next+nx]=true;}
					inRun=open;}}}
	}
	/** The lasso mask: every pixel a flood from the image's edges through non-boundary pixels does not reach. */
	private void lassoMask(boolean[] boundary,boolean[] mask,int width,int height) {
		java.util.Arrays.fill(mask,0,width*height,false);
		for(int x=0;x<width;x++){floodFrom(boundary,mask,width,height,x,0);floodFrom(boundary,mask,width,height,x,height-1);}
		for(int y=0;y<height;y++){floodFrom(boundary,mask,width,height,0,y);floodFrom(boundary,mask,width,height,width-1,y);}
		for(int i=0;i<width*height;i++)mask[i]=!mask[i];
	}
	private boolean[] maskBoundaryScratch=new boolean[0];private int[] floodScratch=new int[0];
	private static boolean[] booleans(boolean[] current,int size){return current.length>=size?current:new boolean[Math.max(size,current.length*2)];}

	// ------------------------------------------------------------------ smooth zoom (SmoothZoom): the hi-res planes of the buffers drawn into

	/** The plane of a bitmap's pixels, or null: the smooth zoom is off, or the bitmap is routed to the screen (the windows' planes
	 *  scale up what lands in their buffers), has no pixels of its own, or is too large. {@code create}: make one for it. */
	private SmoothZoom.Plane smoothPlane(Bitmap b,boolean create){
		if(!smooth.active()||b==null||b instanceof ScreenBitmap||b.base==0||b.rowBytes<=0)return null;
		int w=b.bounds.right-b.bounds.left,h=b.bounds.bottom-b.bounds.top;if(w<=0||h<=0)return null;
		return smooth.plane(b.base,b.rowBytes,w,h,b.pixelSize,create);
	}
	/** The colours a plane reads indexed pixels through: the screen's table for a window's buffer (as the host shows it), else the bitmap's own. */
	int[] smoothColors(SmoothZoom.Plane p,int[] own) throws M68kCpuException {
		if(p.depth>8)return null;
		if(p.window){int[] device=mainDevicePalette();if(device!=null&&device.length>=1<<p.depth)return device;}
		return own;
	}
	/** The bitmap's own colours for its plane (a colorized 1-bit source's are the port's, not the buffer's). */
	private static int[] planeColors(Bitmap b){return b.direct||b.pixelSize==1?null:b.palette;}
	/** The plane rectangle {x0,y0,x1,y1} of a local rectangle of a bitmap, or null when it misses the buffer. */
	private static int[] planeRect(SmoothZoom.Plane p,Bitmap b,int left,int top,int right,int bottom){
		int x0=Math.max(0,left-b.bounds.left),y0=Math.max(0,top-b.bounds.top),x1=Math.min(p.width,right-b.bounds.left),y1=Math.min(p.height,bottom-b.bounds.top);
		return x1>x0&&y1>y0?new int[]{x0,y0,x1,y1}:null;
	}

	/** Text going into a plane: see {@link #smoothTextBegin}. */
	private static final class SmoothText {final SmoothZoom.Plane plane;final int[] rect;final long[] pin;final SmoothZoom.Text text;
		SmoothText(SmoothZoom.Plane plane,int[] rect,long[] pin,SmoothZoom.Text text){this.plane=plane;this.rect=rect;this.pin=pin;this.text=text;}}
	/** Which uncovered pixels of a hi-res text mask act (as in the 1x painters): none, those inside the character boxes, all. */
	private static final int SMOOTH_CLEAR_NONE=0,SMOOTH_CLEAR_IN_BOX=1,SMOOTH_CLEAR_ALL=2;
	/**
	 * Text about to be drawn at 1x over the local {@code area} {left,top,right,bottom} of {@code bitmap}: with the smooth zoom on, the
	 * bitmap's plane (made now if it has none: a GWorld the text goes into keeps it hi-res for the CopyBits to the window) is
	 * pinned and brought up to date there, and {@link #smoothTextDraw} then draws {@code text} into it. Null when there is no plane.
	 */
	private SmoothText smoothTextBegin(Bitmap bitmap,int[] area,SmoothZoom.Text text) throws M68kCpuException {
		if(text==null||!smooth.active())return null;
		SmoothZoom.Plane p=smoothPlane(bitmap,true);if(p==null)return null;
		int[] r=planeRect(p,bitmap,area[0],area[1],area[2],area[3]);if(r==null)return null;
		int[] colors=smoothColors(p,planeColors(bitmap));
		long[] pin=p.pin(r[1],r[3]);
		try{p.sync(r[0],r[1],r[2],r[3],colors,SmoothZoom.DRAW);}catch(RuntimeException e){p.unpin(pin);throw e;}
		return new SmoothText(p,r,pin,text);
	}
	/**
	 * The text of {@link #smoothTextBegin} drawn into its plane after the 1x drawing: every block of the 1x {@code area} the port
	 * lets draw takes the hi-res coverage with the text mode and the port's colours, as the 1x painters do per pixel; then the
	 * area is committed as drawn at hi-res.
	 */
	private void smoothTextDraw(SmoothText t,Bitmap bitmap,int h,int top,int mode,int[] box,int clear,int[] area) throws M68kCpuException {
		if(t==null)return;
		SmoothZoom.Plane p=t.plane;BufferedImage mask=t.text.render(p.s);
		if(mask!=null){
			int s=p.s,stride=p.width*s,mw=mask.getWidth(),mh=mask.getHeight();java.awt.image.Raster raster=mask.getRaster();boolean binary=mask.getType()==BufferedImage.TYPE_BYTE_BINARY;
			int fg=rgbInk(portForegroundInk(),bitmap),bg=rgbInk(portBackgroundInk(),bitmap);int[] row=new int[s];
			for(int tv=area[1];tv<area[3];tv++)for(int th=area[0];th<area[2];th++){
				int bx=th-bitmap.bounds.left,by=tv-bitmap.bounds.top;if(bx<t.rect[0]||by<t.rect[1]||bx>=t.rect[2]||by>=t.rect[3])continue;
				if(!portAllows(th,tv))continue;
				boolean acts=clear==SMOOTH_CLEAR_ALL||clear==SMOOTH_CLEAR_IN_BOX&&box!=null&&inBox(th,tv,box);
				int mx0=(th-h)*s;
				for(int j=0;j<s;j++){int my=(tv-top)*s+j,o=(by*s+j)*stride+bx*s;
					if(my>=0&&my<mh&&mx0>=0&&mx0+s<=mw)raster.getSamples(mx0,my,s,1,0,row);
					else for(int i=0;i<s;i++){int mx=mx0+i;row[i]=my>=0&&my<mh&&mx>=0&&mx<mw?raster.getSample(mx,my,0):0;}
					for(int i=0;i<s;i++){int c=row[i];if(binary&&c!=0)c=255;if(c==0&&!acts)continue;
						int d=~p.hi[o+i]&0xffffff,on=hiTransfer(true,d,mode,fg,bg);
						p.hi[o+i]=~(c>=255?on:blendInk(hiTransfer(false,d,mode,fg,bg),on,c))&0xffffff;}}
			}
		}
		p.commit(t.rect[0],t.rect[1],t.rect[2],t.rect[3],null,mask!=null);
	}
	private static void smoothTextEnd(SmoothText t){if(t!=null)t.plane.unpin(t.pin);}
	/** An ink as plain RGB ink (a colour table index ink through the bitmap's colours). */
	private static int rgbInk(int ink,Bitmap bitmap){return (ink&INDEX_INK)!=0?bitmap.indexInkToRgbInk(ink):ink&0xffffff;}
	/** {@link #transferBit} onto a hi-res (direct RGB) pixel, with the port's colours worked out once. */
	private int hiTransfer(boolean bit,int destination,int mode,int fg,int bg) throws M68kCpuException {
		if(mode>=0&&mode<=15){boolean on=(mode&4)!=0?!bit:bit;
			switch(mode&3){case 0:return on?fg:bg;case 1:return on?fg:destination;case 2:return on?~destination&0xffffff:destination;default:return on?bg:destination;}}
		return transferInk(bit?fg:bg,destination,mode,32);
	}

	/** Whether a copy wrote a destination pixel (local coordinates) with the source's colour unblended. */
	private interface CopiedTest {boolean test(int h,int v) throws M68kCpuException;}
	/** The CopyBits under way whose hi-res blocks {@link #copyBits} carries over once the 1x copy is done. */
	private SmoothCopy pendingSmoothCopy;
	/**
	 * A copy about to run: when the source's plane holds hi-res blocks in what is copied (text drawn into an offscreen GWorld),
	 * the destination's plane - made now if needed - is pinned and brought up to date, and {@link SmoothCopy#end} carries the
	 * blocks over after the 1x copy (through the transfer mode, at hi-res). Null otherwise: the destination's plane, if any,
	 * scales up whatever the copy changes, as for any drawing.
	 */
	private SmoothCopy smoothCopyBegin(Bitmap source,Rect sourceArea,Bitmap destination,Rect destinationArea,Rect drawingArea,int mode,boolean shaped,QdRegion maskShape,CopiedTest test) throws M68kCpuException {
		if(!smooth.active()||drawingArea.empty())return null;
		SmoothZoom.Plane from=smoothPlane(source,false);if(from==null)return null;
		int[] fr=planeRect(from,source,sourceArea.left,sourceArea.top,sourceArea.right,sourceArea.bottom);if(fr==null)return null;
		from.sync(fr[0],fr[1],fr[2],fr[3],smoothColors(from,planeColors(source)),SmoothZoom.READ);
		if(!from.ownsAny(fr[0],fr[1],fr[2],fr[3]))return null;
		SmoothZoom.Plane to=smoothPlane(destination,true);if(to==null)return null;
		int[] r=planeRect(to,destination,drawingArea.left,drawingArea.top,drawingArea.right,drawingArea.bottom);if(r==null)return null;
		int[] colors=smoothColors(to,planeColors(destination));
		// within one plane (ScrollRect) the rows read from stay pinned too: the host must not scale them anew before end() reads them
		long[] pin=from==to?to.pin(Math.min(r[1],fr[1]),Math.max(r[3],fr[3])):to.pin(r[1],r[3]),fromPin=from==to?null:from.pin(fr[1],fr[3]);
		try{to.sync(r[0],r[1],r[2],r[3],colors,SmoothZoom.DRAW);}catch(RuntimeException e){to.unpin(pin);from.unpin(fromPin);throw e;}
		return new SmoothCopy(source,sourceArea,destination,destinationArea,drawingArea,mode,shaped,maskShape,test,from,to,r,pin,fromPin);
	}
	/** A copy carrying hi-res blocks from one plane to another (or within one: ScrollRect). */
	private final class SmoothCopy {
		final Bitmap source,destination;final Rect sourceArea,destinationArea,area;final int mode;final boolean shaped;final QdRegion maskShape;final CopiedTest test;
		final SmoothZoom.Plane from,to;final int[] rect;final long[] pin,fromPin;
		SmoothCopy(Bitmap source,Rect sourceArea,Bitmap destination,Rect destinationArea,Rect area,int mode,boolean shaped,QdRegion maskShape,CopiedTest test,
				SmoothZoom.Plane from,SmoothZoom.Plane to,int[] rect,long[] pin,long[] fromPin){
			this.source=source;this.sourceArea=sourceArea;this.destination=destination;this.destinationArea=destinationArea;this.area=area;this.mode=mode;this.shaped=shaped;
			this.maskShape=maskShape;this.test=test;this.from=from;this.to=to;this.rect=rect;this.pin=pin;this.fromPin=fromPin;
			// which source blocks are hi-res, as of now: the 1x copy releases those it writes over (within one plane, ScrollRect's own source)
			ownedTop=Math.max(0,sourceArea.top-source.bounds.top);int bottom=Math.min(from.height,sourceArea.bottom-source.bounds.top);
			owned=java.util.Arrays.copyOfRange(from.own,ownedTop*from.width,Math.max(ownedTop,bottom)*from.width);}
		private final byte[] owned;private final int ownedTop;
		/** Unpins without carrying anything (the 1x copy failed). */
		void abandon(){to.unpin(pin);from.unpin(fromPin);}
		/**
		 * After the 1x copy: every destination pixel the copy wrote whose source pixel's block was drawn at hi-res takes the source's
		 * hi-res pixels (scaled like the 1x copy when the rectangles differ), through the transfer mode; the rest of the area is
		 * committed as for any drawing (what the copy changed is scaled up from its 1x colours).
		 */
		void end() throws M68kCpuException {
			try{
				int s=to.s,stride=to.width*s,fromStride=from.width*s;
				int sw=sourceArea.right-sourceArea.left,sh=sourceArea.bottom-sourceArea.top,dw=destinationArea.right-destinationArea.left,dh=destinationArea.bottom-destinationArea.top;
				boolean whole=sw==dw&&sh==dh;
				int[] src=from.hi;int srcOffset=0;
				if(from==to){ // a copy within one plane reads the blocks as they were
					int y0=Math.max(0,sourceArea.top-source.bounds.top),y1=Math.min(from.height,sourceArea.bottom-source.bounds.top);if(y1<=y0)return;
					srcOffset=y0*s*fromStride;src=java.util.Arrays.copyOfRange(from.hi,srcOffset,y1*s*fromStride);}
				int[] tint=source.pixelSize==1&&source.palette!=null&&source.palette.length>=2&&!(source.palette[0]==0xffffff&&source.palette[1]==0)?new int[]{source.palette[0],source.palette[1]}:null;
				boolean copy=(mode&~0x40)==0||mode==64;
				int aw=rect[2]-rect[0];byte[] drawn=new byte[aw*(rect[3]-rect[1])];boolean any=false;
				for(int v=area.top;v<area.bottom;v++)for(int h=area.left;h<area.right;h++){
					int tx=h-destination.bounds.left,ty=v-destination.bounds.top;if(tx<rect[0]||ty<rect[1]||tx>=rect[2]||ty>=rect[3])continue;
					if(shaped&&!regionsAllow(h,v)||maskShape!=null&&!maskShape.contains(h,v)||test!=null&&!test.test(h,v))continue;
					long dx=h-destinationArea.left,dy=v-destinationArea.top;
					int fx=sourceArea.left+(int)(dx*sw/dw)-source.bounds.left,fy=sourceArea.top+(int)(dy*sh/dh)-source.bounds.top;
					int oi=(fy-ownedTop)*from.width+fx;
					if(fx<0||fy<0||fx>=from.width||fy>=from.height||oi<0||oi>=owned.length||owned[oi]==0)continue; // scaled up from its 1x pixel: the destination scales its own
					for(int j=0;j<s;j++){
						int sy=whole?fy*s+j:(int)((sourceArea.top-source.bounds.top)*(long)s+(dy*s+j)*sh/dh);int so=sy*fromStride-srcOffset;
						if(sy<0||sy>=from.height*s||so<0||so+fromStride>src.length)continue;
						int o=(ty*s+j)*stride+tx*s;
						for(int i=0;i<s;i++){
							int sx=whole?fx*s+i:(int)((sourceArea.left-source.bounds.left)*(long)s+(dx*s+i)*sw/dw);if(sx<0||sx>=fromStride)continue;
							int sp=src[so+sx];if(tint!=null){int lum=(299*(sp>>>16&255)+587*(sp>>>8&255)+114*(sp&255))/1000;sp=SmoothScaler.mix(tint[0],tint[1],(255-lum)*256/255);}
							to.hi[o+i]=copy?sp:~transferInk(~sp&0xffffff,~to.hi[o+i]&0xffffff,mode,32)&0xffffff;}}
					drawn[(ty-rect[1])*aw+tx-rect[0]]=1;any=true;
				}
				to.commit(rect[0],rect[1],rect[2],rect[3],any?drawn:null,false);
			}finally{abandon();}
		}
	}

	private class Bitmap {
		final int base,rowBytes,pixelSize;final boolean direct;final int[] palette;final Rect bounds;
		/** Colour table Handle of an indexed PixMap (0 for bitmaps and direct PixMaps). */
		int colorTable;
		/** Palette entry number by pixel value when the colour table refers to a palette (ctFlags bit 14), else null: {@link #paletteEntries}. */
		int[] paletteEntries;
		/** Raw pixel value of an indexed bitmap (0 outside). */
		int getIndex(int h,int v) throws M68kCpuException {if(!contains(h,v))return 0;int bit=(h-bounds.left)*pixelSize;return memory.readByte(base+(v-bounds.top)*rowBytes+(bit>>>3))>>>(8-pixelSize-(bit&7))&((1<<pixelSize)-1);}
		/** The byte address of pixel (h,v) by the ROM's arithmetic alone - no bounds test, so a point outside the bounds lands in
		 *  the neighbouring rows' bytes the way CopyBits reads a source rectangle that leaves its bitmap (Grizzly's text routine
		 *  computes negative glyph columns for capitals and gets the glyph from the previous row's wrap-around); -1 when the
		 *  address leaves memory. Only for reading. */
		long unclippedAddress(int h,int v,int bit){if(base==0||rowBytes<=0)return -1;long address=(long)base+(long)(v-bounds.top)*rowBytes+Math.floorDiv(bit,8);return address<0||address>=memory.size()?-1:address;}
		int getIndexUnclipped(int h,int v) throws M68kCpuException {int bit=(h-bounds.left)*pixelSize;long address=unclippedAddress(h,v,bit);if(address<0)return 0;return memory.readByte((int)address)>>>(8-pixelSize-Math.floorMod(bit,8))&((1<<pixelSize)-1);}
		int getInkRgbUnclipped(int h,int v) throws M68kCpuException {
			if(contains(h,v))return getInkRgb(h,v);
			int x=h-bounds.left;
			if(!direct){int bit=x*pixelSize;long address=unclippedAddress(h,v,bit);if(address<0)return 0;int value=memory.readByte((int)address)>>>(8-pixelSize-Math.floorMod(bit,8))&((1<<pixelSize)-1);
				return ~(palette==null?(value==0?0xffffff:0):palette[value])&0xffffff;}
			long address=unclippedAddress(h,v,x*pixelSize);if(address<0||address+(pixelSize>>>3)>memory.size())return 0;
			if(pixelSize==16){int value=memory.readWord((int)address),red=value>>>10&31,green=value>>>5&31,blue=value&31;return ~(red*255/31<<16|green*255/31<<8|blue*255/31)&0xffffff;}
			return ~((memory.readByte((int)address+1)<<16)|(memory.readByte((int)address+2)<<8)|memory.readByte((int)address+3))&0xffffff;}
		void setIndex(int h,int v,int value) throws M68kCpuException {if(!contains(h,v))return;disown(h,v);int bit=(h-bounds.left)*pixelSize,address=base+(v-bounds.top)*rowBytes+(bit>>>3),shift=8-pixelSize-(bit&7),mask=((1<<pixelSize)-1)<<shift;
			memory.writeByte(address,memory.readByte(address)&~mask|(value&((1<<pixelSize)-1))<<shift);}
		Bitmap(int base,int rowBytes,Rect bounds,int pixelSize,boolean direct,int[] palette){this.base=memory.physical(base);this.rowBytes=rowBytes; // an aliased screen address (screenAddressAlias) is the same bytes
			this.bounds=bounds;this.pixelSize=pixelSize;this.direct=direct;this.palette=palette;
			plane=smooth.active()&&this.base!=0&&rowBytes>0?smooth.plane(this.base,rowBytes,bounds.right-bounds.left,bounds.bottom-bounds.top,pixelSize,false):null;}
		/** The smooth zoom plane of these pixels, if they have one: a 1x write releases the hi-res blocks it lands on (see {@link #disown}). */
		final SmoothZoom.Plane plane;
		/**
		 * Pixel (h,v) was written at 1x: a block drawn at hi-res there no longer stands for it, even when the colour came out the
		 * same - a page's background drawn again around the previous page's text left the old glyphs' hi-res edges behind.
		 */
		final void disown(int h,int v){SmoothZoom.Plane p=plane;if(p==null)return;int x=h-bounds.left,y=v-bounds.top;
			if(x>=0&&y>=0&&x<p.width&&y<p.height&&p.own[y*p.width+x]!=0)p.disown(y,x,x+1);}
		/** Pixels left..right-1 of row v were written at 1x (see {@link #disown}). */
		final void disownSpan(int v,int left,int right){SmoothZoom.Plane p=plane;if(p==null)return;int y=v-bounds.top;if(y<0||y>=p.height)return;
			int x0=Math.max(0,left-bounds.left),x1=Math.min(p.width,right-bounds.left),o=y*p.width;
			for(int x=x0;x<x1;x++)if(p.own[o+x]!=0){p.disown(y,x0,x1);return;}}
		/** Whether pixel (h,v) has a block drawn at hi-res. */
		final boolean owned(int h,int v){SmoothZoom.Plane p=plane;if(p==null)return false;int x=h-bounds.left,y=v-bounds.top;return x>=0&&y>=0&&x<p.width&&y<p.height&&p.own[y*p.width+x]!=0;}
		boolean contains(int h,int v){return base!=0&&rowBytes>0&&QuickDraw68k.this.contains(bounds,h,v);}
		boolean get(int h,int v) throws M68kCpuException {int ink=getInkRgb(h,v);return 299*(ink>>>16&255)+587*(ink>>>8&255)+114*(ink&255)>=127_500;}
		void set(int h,int v,boolean value) throws M68kCpuException {setInkRgb(h,v,value?0xffffff:0);}
		int getInkRgb(int h,int v) throws M68kCpuException {if(!contains(h,v))return 0;int x=h-bounds.left,address=base+(v-bounds.top)*rowBytes;
			if(!direct){int bit=x*pixelSize,value=memory.readByte(address+(bit>>>3))>>>(8-pixelSize-(bit&7))&((1<<pixelSize)-1);
				return ~(palette==null?(value==0?0xffffff:0):palette[value])&0xffffff;}
			if(pixelSize==16){int value=memory.readWord(address+x*2),red=value>>>10&31,green=value>>>5&31,blue=value&31;
				return ~(red*255/31<<16|green*255/31<<8|blue*255/31)&0xffffff;}
			return ~((memory.readByte(address+x*4+1)<<16)|(memory.readByte(address+x*4+2)<<8)|memory.readByte(address+x*4+3))&0xffffff;}
		/** A whole 32-bit pixel (alpha byte included) on a 32-bit direct bitmap; other depths take the colour. */
		/** Reads {@code count} pixels of row {@code v} from {@code h} of a direct bitmap as plain 0xRRGGBB (by address, unclipped). */
		void readRgbRow(int v,int h,int[] out,int count) throws M68kCpuException {
			int bytes=pixelSize>>>3;long address=unclippedAddress(h,v,(h-bounds.left)*pixelSize);
			if(address<0||address+(long)count*bytes>memory.size()){for(int i=0;i<count;i++)out[i]=~getInkRgbUnclipped(h+i,v)&0xffffff;return;}
			byte[] row=readRowScratch=bytes(readRowScratch,count*bytes);memory.read((int)address,row,0,count*bytes);
			if(pixelSize==32)for(int i=0,o=0;i<count;i++,o+=4)out[i]=(row[o+1]&0xff)<<16|(row[o+2]&0xff)<<8|row[o+3]&0xff;
			else for(int i=0,o=0;i<count;i++,o+=2){int value=(row[o]&0xff)<<8|row[o+1]&0xff,red=value>>>10&31,green=value>>>5&31,blue=value&31;out[i]=red*255/31<<16|green*255/31<<8|blue*255/31;}
		}
		/** Writes plain 0xRRGGBB pixels to row {@code v} from {@code h} (clipped to the bounds): direct depths a row at a time. */
		void setRgbRow(int v,int h,int[] rgb,int offset,int count) throws M68kCpuException {
			if(base==0||rowBytes<=0||v<bounds.top||v>=bounds.bottom)return;
			int l=Math.max(h,bounds.left),r=Math.min(h+count,bounds.right);if(l>=r)return;
			offset+=l-h;count=r-l;
			if(!direct||pixelSize!=32&&pixelSize!=16){for(int i=0;i<count;i++)setInkRgb(l+i,v,~rgb[offset+i]&0xffffff);return;}
			disownSpan(v,l,r);
			int bytes=pixelSize>>>3,address=base+(v-bounds.top)*rowBytes+(l-bounds.left)*bytes;
			byte[] row=writeRowScratch=bytes(writeRowScratch,count*bytes);
			if(pixelSize==32)for(int i=0,o=0;i<count;i++,o+=4){int c=rgb[offset+i];row[o]=(byte)0xff;row[o+1]=(byte)(c>>>16);row[o+2]=(byte)(c>>>8);row[o+3]=(byte)c;}
			else for(int i=0,o=0;i<count;i++,o+=2){int c=rgb[offset+i],value=(c>>>19&31)<<10|(c>>>11&31)<<5|(c>>>3&31);row[o]=(byte)(value>>>8);row[o+1]=(byte)value;}
			memory.load(address,row,0,count*bytes);
		}
		void setArgb(int h,int v,int argb) throws M68kCpuException {
			if(!direct||pixelSize!=32){setInkRgb(h,v,~argb&0xffffff);return;}
			if(!contains(h,v))return;disown(h,v);int address=base+(v-bounds.top)*rowBytes+(h-bounds.left)*4;memory.writeLong(address,argb);}
		void setInkRgb(int h,int v,int ink) throws M68kCpuException {if(!contains(h,v))return;disown(h,v);if((ink&INDEX_INK)!=0&&direct)ink=indexInkToRgbInk(ink);int rgb=~ink&0xffffff,x=h-bounds.left,address=base+(v-bounds.top)*rowBytes;
			if(!direct){int value=(ink&INDEX_INK)!=0?ink&((1<<pixelSize)-1):nearestColor(rgb),bit=x*pixelSize,byteAddress=address+(bit>>>3),shift=8-pixelSize-(bit&7),mask=((1<<pixelSize)-1)<<shift;
				memory.writeByte(byteAddress,memory.readByte(byteAddress)&~mask|value<<shift);return;}
			if(pixelSize==16){int value=(rgb>>>19&31)<<10|(rgb>>>11&31)<<5|(rgb>>>3&31);memory.writeWord(address+x*2,value);return;}
			memory.writeLong(address+x*4,rgb);} // xRGB with a zero pad byte, as 32-bit QuickDraw writes it (see pixelValue)
		/** Raw pixel value for an ink: colour table index, 16-bit packed or xRGB. */
		/** An index ink drawn into a direct-colour bitmap takes the colour the table gives that index (white without a table). */
		int indexInkToRgbInk(int ink){int[] table=palette!=null?palette:matchPalette;int index=ink&0xff;return ~(table!=null&&index<table.length?table[index]:0xffffff)&0xffffff;}
		/** 32-bit pixels are xRGB with the pad byte 0, as Color2Index gives them on a real Mac: Bryce 2 marks the pixels its
		 *  progressive render has computed with a non-zero pad byte and paints each pass's blocks with the pen, so a pad of $FF
		 *  left every block "done" after the first, coarse pass. Host (Java2D) drawing treats the byte as padding, not alpha. */
		int pixelValue(int ink){if((ink&INDEX_INK)!=0){if(!direct)return ink&((1<<pixelSize)-1);ink=indexInkToRgbInk(ink);}int rgb=~ink&0xffffff;if(!direct)return nearestColor(rgb);if(pixelSize==16)return (rgb>>>19&31)<<10|(rgb>>>11&31)<<5|(rgb>>>3&31);return rgb;}
		/**
		 * Writes row {@code v} from {@code left} to {@code right} (exclusive, clipped to the bounds) with {@code onInk} where
		 * the 8-bit pattern row {@code patternRow} (bit 7 = h&7 == 0) is set and {@code offInk} elsewhere, as one memory load.
		 */
		void fillSpan(int v,int left,int right,int patternRow,int onInk,int offInk) throws M68kCpuException {
			if(base==0||rowBytes<=0||v<bounds.top||v>=bounds.bottom)return;left=Math.max(left,bounds.left);right=Math.min(right,bounds.right);if(left>=right)return;
			disownSpan(v,left,right);
			int on=pixelValue(onInk),off=patternRow==0xff?on:pixelValue(offInk);int x0=left-bounds.left,x1=right-bounds.left,rowStart=base+(v-bounds.top)*rowBytes;
			if(pixelSize==8&&(patternRow==0xff||patternRow==0||on==off)){memory.fill(rowStart+x0,x1-x0,patternRow==0?off:on);return;}
			if(direct){int bytesPerPixel=pixelSize>>>3;byte[] row=spanScratch=bytes(spanScratch,(x1-x0)*bytesPerPixel);
				for(int x=x0,o=0;x<x1;x++,o+=bytesPerPixel){int value=(patternRow&(0x80>>>((x+bounds.left)&7)))!=0?on:off;
					if(bytesPerPixel==2){row[o]=(byte)(value>>>8);row[o+1]=(byte)value;}else{row[o]=0;row[o+1]=(byte)(value>>>16);row[o+2]=(byte)(value>>>8);row[o+3]=(byte)value;}}
				memory.load(rowStart+x0*bytesPerPixel,row,0,(x1-x0)*bytesPerPixel);return;}
			int b0=x0*pixelSize>>>3,b1=(x1*pixelSize+7)>>>3,length=b1-b0;byte[] row=spanScratch=bytes(spanScratch,length);
			if(pixelSize<8){row[0]=(byte)memory.readByte(rowStart+b0);row[length-1]=(byte)memory.readByte(rowStart+b1-1);} // partial edge bytes keep their other pixels
			int mask=(1<<pixelSize)-1;
			for(int x=x0;x<x1;x++){int value=(patternRow&(0x80>>>((x+bounds.left)&7)))!=0?on:off;
				if(pixelSize==8){row[x-x0]=(byte)value;continue;}
				int bit=x*pixelSize,o=(bit>>>3)-b0,shift=8-pixelSize-(bit&7);row[o]=(byte)(row[o]&~(mask<<shift)|(value&mask)<<shift);}
			memory.load(rowStart+b0,row,0,length);
		}
		/** Colour matching goes through the table as it was when its seed last changed (QuickDraw's inverse table): entries animated
		 *  through the video driver do not move the matches, as on a real Macintosh. */
		int[] matchPalette;
		/** {@link #matchPalette} is theGDevice's table rather than this PixMap's own (see {@link #matchThroughCurrentDevice}). */
		boolean throughDevice;
		/** For a GWorld made with noNewDevice: the device it draws through (see GWorldState.sharedDevice), else 0. */
		int sharedDevice;
		/** Recent matches for the palette in use (see {@link #colorCacheFor}). */
		private long[] colorCache;private int[] colorCachePalette;
		int nearestColor(int rgb){int[] palette=matchPalette!=null?matchPalette:this.palette;
			// A 1-bit destination without a colour table maps like the ROM's inverse table of {white, black}: the nearest by the
			// unweighted component distance (Executor's MakeITable sums |dR|+|dG|+|dB|), i.e. black when R+G+B < 1.5*255 - not by
			// luminance. Grizzly thresholds its magenta rock texture into 1-bit letter masks: magenta is nearer white and stayed
			// white on a Mac, but is dark by luminance, and the masks came out solid.
			if(palette==null)return (rgb>>>16&255)+(rgb>>>8&255)+(rgb&255)<383?1:0;
			if(colorCache==null||colorCachePalette!=palette){colorCache=colorCacheFor(palette);colorCachePalette=palette;}
			long[] cache=colorCache;int slot=(rgb*0x9E3779B1)>>>(32-COLOR_CACHE_BITS);long entry=cache[slot];
			if(entry!=0&&(int)(entry>>>8&0xffffff)==rgb)return (int)entry&0xff;
			int best=searchColor(palette,rgb);
			// colours matched by RGB stay off the entries the program animates (antialiased text edges took them and flickered); its
			// animated pixels come from pixel values (sprites, CopyBits by index), not from RGB matching
			if(palette.length==256){boolean[] animated=null;try{animated=animatedEntries();}catch(M68kCpuException e){}
				if(animated!=null&&animated[best])best=searchSteadyColor(palette,rgb,animated);}
			cache[slot]=1L<<40|(long)rgb<<8|best;return best;}
	}
	/** The nearest entry by squared RGB distance (the first one on an inexact tie). A colour the table holds more than once goes
	 *  to its LAST entry - white to entry 0 when that is white, where the Color Manager keeps it. Factory 1.1 animates entries
	 *  128-144 with SetEntries (belts: one of 128-135 black in turn; the lift: copies of #EEEEEE/#8D8D8D) and on a Mac its
	 *  black frame, black arrows and #EEEEEE wall still get 255 and 245; the first copy made them flash with the belts. */
	static int searchColor(int[] palette,int rgb){rgb&=0xffffff;if(rgb==0xffffff&&palette.length>1&&(palette[0]&0xffffff)==0xffffff)return 0;
		int red=rgb>>>16&255,green=rgb>>>8&255,blue=rgb&255;int best=0;long distance=Long.MAX_VALUE;
		for(int i=0;i<palette.length;i++){int color=palette[i],dr=red-(color>>>16&255),dg=green-(color>>>8&255),db=blue-(color&255);
			long candidate=(long)dr*dr+(long)dg*dg+(long)db*db;if(candidate<distance||candidate==0){distance=candidate;best=i;}}return best;}
	/** {@link #searchColor} among the entries not marked in {@code skip}. */
	static int searchSteadyColor(int[] palette,int rgb,boolean[] skip){int red=rgb>>>16&255,green=rgb>>>8&255,blue=rgb&255,best=-1;long distance=Long.MAX_VALUE;
		for(int i=0;i<palette.length;i++){if(i<skip.length&&skip[i])continue;int color=palette[i],dr=red-(color>>>16&255),dg=green-(color>>>8&255),db=blue-(color&255);
			long candidate=(long)dr*dr+(long)dg*dg+(long)db*db;if(candidate<distance||candidate==0){distance=candidate;best=i;}}return best<0?searchColor(palette,rgb):best;}
	private static final int COLOR_CACHE_BITS=12;
	/** Direct-mapped rgb -> index caches per palette array (the seeded snapshots live as long as the table keeps its seed). */
	private final java.util.WeakHashMap<int[],long[]> colorCaches=new java.util.WeakHashMap<int[],long[]>();
	private long[] colorCacheFor(int[] palette){long[] cache=colorCaches.get(palette);if(cache==null){cache=new long[1<<COLOR_CACHE_BITS];colorCaches.put(palette,cache);}return cache;}
	private static final class Rect {
		final int top,left,bottom,right;Rect(int top,int left,int bottom,int right){this.top=top;this.left=left;this.bottom=bottom;this.right=right;}
		boolean empty(){return bottom<=top||right<=left;}
		@Override public boolean equals(Object other){if(!(other instanceof Rect))return false;Rect r=(Rect)other;
			return top==r.top&&left==r.left&&bottom==r.bottom&&right==r.right;}
		@Override public int hashCode(){return ((top*31+left)*31+bottom)*31+right;}
		@Override public String toString(){return "("+top+","+left+","+bottom+","+right+")";}
	}
}

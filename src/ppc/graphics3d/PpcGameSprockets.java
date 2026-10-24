import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple Game Sprockets for PowerPC programs: DrawSprocket 1.7 (DrawSprocketLib), InputSprocket 1.7 (InputSprocketLib) and
 * SoundSprocket (SoundSprocketLib), after the Universal Interfaces 3.4 headers (DrawSprocket.h, InputSprocket.h,
 * SoundSprocket.h; structures in PowerPC alignment).
 * <p>
 * <b>DrawSprocket</b>. The main display offers one context per display mode: the usual sizes of the time up to the screen
 * the program sees (and that screen's own size), each at 8, 16 and 32 bits. Reserving a context switches the main device to
 * the context's size (the program's screen becomes that size, MacApp68k.changeScreenSize) and depth when the screen has it
 * (SetDepth, as DSp switched the monitor; both restored at release; else CopyBits converts) and makes its buffers: the back buffer
 * is a GWorld of the requested back-buffer depth, the front buffer a borderless window (plainDBox) of the context's size in
 * the middle of the screen - over a second window covering the whole screen in the blanking colour when the context is
 * smaller, as the rest of the monitor was black after the switch. Both windows have that colour as their content colour, so
 * the Window Manager erases them to it. They show while the context is active (the menu bar hidden: MBarHeight 0) and hide
 * when it is paused or inactive. SwapBuffers waits for the next "VBL" (a 60 Hz frame, or the program's maximum frame rate;
 * kDSpContextOption_DontSyncVBL without a maximum does not wait), copies the back buffer to the window with CopyBits and then
 * refreshes the rectangles invalidated since the last swap from the underlay alternate buffer, if the context has one (DSp's
 * "underlay": the areas the program drew sprites over are restored for the next frame). An 8-bit back buffer shows through
 * an 8-bit display by index (it takes the display's CLUT before each swap, as DSp's back buffers shared it), so palette
 * games that call SetEntries or the Palette Manager themselves see their colours; SetCLUTEntries changes the display's CLUT
 * (or, on a direct display, the back buffer's table). The gamma fades are instant no-ops (a fade to black and back again
 * would only delay the program). DSpBlit_Faster/Fastest copy synchronously with CopyBits; a source colour key is copied pixel
 * by pixel between offscreen buffers of one depth, else with the transparent mode.
 * <p>
 * <b>InputSprocket</b>. There are two devices, the keyboard and the mouse, inactive until the program activates them (as ISp
 * 1.1 and later). Virtual elements made from the program's needs are bound to them automatically, since the configuration
 * dialog (ISpConfigure) is not provided: needs with a known label get the usual key (Quit Escape, Fire Space, the movement
 * buttons, pads and X/Y axes the arrow keys, mouse button needs the mouse button, delta needs the mouse movement); every
 * other button takes the next free key of Z S X D C F V (a beatmania-style keyboard), A, Q W E R T Y U I O P, G B H N J M K L,
 * 1 ... 0. The property {@code -Dhzisp.keys=Z,S,X,D,...} gives the keys of the needs in order instead ("-" skips one;
 * key names: letters, digits, space, return, tab, escape, shift, option, control, command, left, right, up, down, $hex
 * key codes). The binding is logged ("[isp] ..."). A poller thread
 * samples the host keyboard (the GetKeys map) and mouse every millisecond while an ISp device is active, so element events
 * carry the time of the key transition and short taps are never lost between two frames. AbsoluteTime is in nanoseconds
 * here (as UpTime).
 * <p>
 * <b>SoundSprocket</b> keeps listener and source positions, orientations, velocities and settings, and
 * SSpSource_CalcLocalization computes the SSpLocalizationData from them (distance, azimuth, elevation, cone, velocities);
 * the Sound Manager ignores the localization (siSSpLocalization), so sounds play unfiltered. Its functions are registered
 * without a library name: a program whose SoundSprocketLib is not found binds them through the missing library, while a
 * SoundSprocketLib next to the program (Fifteen Love) is still loaded and used.
 */
final class PpcGameSprockets {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcGameSprockets(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private int tb(String name,int... args) throws M68kException {return rt.toolbox(name,args);}

	void define(PpcLibraries l){defineDrawSprocket(l);defineInputSprocket(l);defineSoundSprocket(l);}

	static final int NO_ERR=0,PARAM_ERR=-50,MEM_FULL_ERR=-108;
	private static final boolean TRACE=PpcRuntime.TRACE;
	/** Mouse delta units per pixel moved. Killing Time divides a delta by 163 to get its cursor's pixels (its start-up code: a
	 *  multiply-high by the reciprocal), so one pixel of the hand is 163 units; -Dhzisp.mousescale=n changes it. The Y delta is positive upwards (the game's cursor ran
	 *  upside down with screen-down positive). */
	private static final int MOUSE_SCALE=Integer.getInteger("hzisp.mousescale",163);

	private void writeRect(int p,int top,int left,int bottom,int right) throws M68kCpuException {memory.writeWord(p,top);memory.writeWord(p+2,left);memory.writeWord(p+4,bottom);memory.writeWord(p+6,right);}
	private int[] readRect(int p) throws M68kCpuException {return new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	private void out(int p,int v) throws M68kCpuException {if(p!=0)memory.writeLong(p,v);}
	private static String fourCC(int v){StringBuilder sb=new StringBuilder();for(int s=24;s>=0;s-=8){int c=(v>>>s)&255;sb.append(c>=32&&c<127?(char)c:'?');}return sb.toString();}

	// ================================================================== DrawSprocket

	static final int DSP_INVALID_CONTEXT=-30442,DSP_ALREADY_RESERVED=-30444,DSP_NOT_RESERVED=-30445,DSP_NOT_FOUND=-30446;
	static final int STATE_ACTIVE=0,STATE_PAUSED=1,STATE_INACTIVE=2;
	static final int OPTION_DONT_SYNC_VBL=1<<2,BLIT_SRC_KEY=1;
	/** DrawSprocket 1.7.5 final (Game Sprockets 1.7.5) (NumVersion). */
	static final int DSP_VERSION=0x01758000;
	/** The display sizes offered besides the screen's own (the ones that fit on it). */
	static final int[][] MODE_SIZES={{512,384},{640,400},{640,480},{800,600},{832,624},{1024,768},{1152,870},{1280,960},{1280,1024},{1600,1200}};
	private static final int[] MODE_DEPTHS={8,16,32};
	/** The size of DSpContextAttributes. */
	private static final int ATTRIBUTES_SIZE=72;

	/** A display mode of the main display; reserved, it has its buffers. Its reference is a small guest block. */
	private final class Context {
		final int ref,width,height,depth;
		boolean reserved;int state=STATE_INACTIVE;
		int backDepth,options,pageCount=1,colorNeeds,colorTable;
		int window,backdrop,back;int left,top;
		AltBuffer underlay;
		final List<int[]> invalid=new ArrayList<int[]>();
		long nextFrame;int maxFps,vblProc,vblRefCon,gridWidth=16,gridHeight=16;
		int savedDepth,savedMenuBar=-1;
		boolean screenSwitched;String savedScreenSize; // the screen was made the context's size (restored at release)
		Context queued;int queuedAttributes;
		Context(int ref,int width,int height,int depth){this.ref=ref;this.width=width;this.height=height;this.depth=depth;}
	}
	/** An alternate buffer (DSpAltBuffer_New): a GWorld. */
	private static final class AltBuffer {final int ref,gworld,width,height;AltBuffer(int ref,int gworld,int width,int height){this.ref=ref;this.gworld=gworld;this.width=width;this.height=height;}}

	private final List<Context> modes=new ArrayList<Context>();
	private final Map<Integer,Context> contexts=new HashMap<Integer,Context>();
	private final Map<Integer,AltBuffer> altBuffers=new HashMap<Integer,AltBuffer>();
	private boolean debugMode;private int blanking=0; // blanking colour 0xRRGGBB (black)

	private int mainDevice() throws M68kException {return tb("GetMainDevice");}
	/** The main device's gdRect: top, left, bottom, right. */
	private int[] screen() throws M68kException {int gd=mainDevice();int p=gd==0?0:memory.readLong(gd);return p==0?new int[]{0,0,480,640}:readRect(p+34);}
	private int screenDepth() throws M68kException {int gd=mainDevice();int p=gd==0?0:memory.readLong(gd);int pm=p==0?0:memory.readLong(p+22);int pmp=pm==0?0:memory.readLong(pm);return pmp==0?32:memory.readWord(pmp+32);}

	/** The display modes, made once: sizes ascending, each at 8, 16 and 32 bits. */
	private List<Context> modes() throws M68kException {
		if(!modes.isEmpty())return modes;
		int[] s=screen();int sw=s[3]-s[1],sh=s[2]-s[0];
		List<int[]> sizes=new ArrayList<int[]>();
		for(int[] m:MODE_SIZES)if(m[0]<=sw&&m[1]<=sh&&!(m[0]==sw&&m[1]==sh))sizes.add(m);
		sizes.add(new int[]{sw,sh});
		sizes.sort((a,b)->Long.compare((long)a[0]*a[1],(long)b[0]*b[1]));
		for(int[] m:sizes)for(int d:MODE_DEPTHS)modes.add(newContext(m[0],m[1],d));
		return modes;
	}
	private Context newContext(int width,int height,int depth) throws M68kException {
		int ref=rt.allocate(16,4);memory.writeLong(ref,0x687a4453);memory.writeLong(ref+4,width);memory.writeLong(ref+8,height);memory.writeLong(ref+12,depth); // 'hzDS'
		Context c=new Context(ref,width,height,depth);contexts.put(ref,c);return c;
	}
	private Context context(int ref){return contexts.get(ref);}
	private Context reservedContext(){for(Context c:contexts.values())if(c.reserved)return c;return null;}
	private static boolean depthAllowed(int mask,int depth){return mask==0||mask==-1||(mask&(1<<Integer.numberOfTrailingZeros(depth)))!=0;}

	/**
	 * DSpFindBestContext: the smallest mode at least as large as asked (the screen's own size for 0 x 0) whose depth is the
	 * requested best depth when the display depth mask allows it, else the deepest allowed one.
	 */
	private Context findBest(int attributes) throws M68kException {
		int w=memory.readLong(attributes+4),h=memory.readLong(attributes+8),mask=memory.readLong(attributes+36),best=memory.readLong(attributes+44);
		if(TRACE)System.err.println("[dsp] find best: "+w+"x"+h+" display mask "+Integer.toHexString(mask)+" best "+best+" back mask "+Integer.toHexString(memory.readLong(attributes+32))+" best "+memory.readLong(attributes+40)+" options "+Integer.toHexString(memory.readLong(attributes+28))+" pages "+memory.readLong(attributes+48));
		int depth=0;
		if(best!=0&&depthAllowed(mask,best)&&(best==8||best==16||best==32))depth=best;
		else for(int d=MODE_DEPTHS.length-1;d>=0;d--)if(depthAllowed(mask,MODE_DEPTHS[d])){depth=MODE_DEPTHS[d];break;}
		if(depth==0)return null;
		int[] s=screen();if(w<=0||h<=0){w=s[3]-s[1];h=s[2]-s[0];}
		for(Context c:modes())if(c.depth==depth&&c.width>=w&&c.height>=h)return c;
		return null;
	}

	private void writeAttributes(Context c,int p) throws M68kCpuException {
		memory.fill(p,ATTRIBUTES_SIZE,0);
		memory.writeLong(p,60<<16);memory.writeLong(p+4,c.width);memory.writeLong(p+8,c.height);
		memory.writeLong(p+20,c.colorNeeds);memory.writeLong(p+24,c.colorTable);memory.writeLong(p+28,c.options);
		int bit=1<<Integer.numberOfTrailingZeros(c.depth);
		memory.writeLong(p+32,c.reserved?1<<Integer.numberOfTrailingZeros(c.backDepth):0x3f);memory.writeLong(p+36,bit); // back buffers of any depth until reserved
		memory.writeLong(p+40,c.reserved?c.backDepth:c.depth);memory.writeLong(p+44,c.depth);memory.writeLong(p+48,c.pageCount);
	}

	private int reserve(Context c,int attributes) throws M68kException {
		if(c.reserved)return DSP_ALREADY_RESERVED;
		Context other=reservedContext();if(other!=null)return DSP_ALREADY_RESERVED;
		int backMask=attributes==0?0:memory.readLong(attributes+32),backBest=attributes==0?0:memory.readLong(attributes+40);
		c.options=attributes==0?0:memory.readLong(attributes+28);c.pageCount=attributes==0?1:Math.max(1,memory.readLong(attributes+48));
		c.colorNeeds=attributes==0?0:memory.readLong(attributes+20);c.colorTable=attributes==0?0:memory.readLong(attributes+24);
		int backDepth=0;
		if(backBest==1||backBest==2||backBest==4||backBest==8||backBest==16||backBest==32)backDepth=backBest;
		else if(depthAllowed(backMask,c.depth))backDepth=c.depth;
		else for(int d=32;d>=1;d>>=1)if(depthAllowed(backMask,d)){backDepth=d;break;}
		if(backDepth==0)backDepth=c.depth;
		c.backDepth=backDepth;
		modes(); // (the modes offered are those of the screen as it was before this switch)
		// the display switches to the context's size, as DSp changed the monitor's mode: the screen the program sees takes it
		int[] before=screen();
		if(!debugMode&&(c.width!=before[3]-before[1]||c.height!=before[2]-before[0])){
			String former=rt.app.screenSizeOverride;
			if(rt.app.changeScreenSize(c.width+"x"+c.height)){c.screenSwitched=true;c.savedScreenSize=former;}
			if(TRACE)System.err.println("[dsp] screen "+(before[3]-before[1])+"x"+(before[2]-before[0])+" -> "+c.width+"x"+c.height+": "+c.screenSwitched);}
		// ... and to the context's depth
		int current=screenDepth();
		if(current!=c.depth&&(short)tb("HasDepth",mainDevice(),c.depth,1,1)!=0){ // (a depth the screen cannot take stays as it is: CopyBits converts)
			int e=(short)tb("SetDepth",mainDevice(),c.depth,1,1);if(e==0)c.savedDepth=current;if(TRACE)System.err.println("[dsp] SetDepth "+c.depth+" -> "+e);}
		int[] s=screen();int sw=s[3]-s[1],sh=s[2]-s[0];
		c.left=s[1]+Math.max(0,(sw-c.width)/2);c.top=s[0]+Math.max(0,(sh-c.height)/2);
		if(!debugMode&&(c.width<sw||c.height<sh))c.backdrop=newWindow(s[0],s[1],s[2],s[3]);
		c.window=newWindow(c.top,c.left,c.top+c.height,c.left+c.width);
		c.back=newGWorld(c.backDepth,c.width,c.height);
		if(c.window==0||c.back==0){release(c);return MEM_FULL_ERR;}
		clear(c.back,c.width,c.height);
		c.reserved=true;c.state=STATE_INACTIVE;c.nextFrame=0;c.invalid.clear();
		if(TRACE)System.err.println("[dsp] reserved "+c.width+"x"+c.height+"x"+c.depth+" back depth "+c.backDepth+" window "+Integer.toHexString(c.window)+" back "+Integer.toHexString(c.back));
		return NO_ERR;
	}
	private void release(Context c) throws M68kException {
		setState(c,STATE_INACTIVE);
		if(c.window!=0){tb("DisposeWindow",c.window);c.window=0;}
		if(c.backdrop!=0){tb("DisposeWindow",c.backdrop);c.backdrop=0;}
		if(c.back!=0){disposeGWorld(c.back);c.back=0;}
		if(c.savedDepth!=0){tb("SetDepth",mainDevice(),c.savedDepth,1,1);c.savedDepth=0;}
		if(c.screenSwitched){c.screenSwitched=false;rt.app.changeScreenSize(c.savedScreenSize);c.savedScreenSize=null;}
		c.underlay=null;c.invalid.clear();c.reserved=false;
	}
	private void setState(Context c,int state) throws M68kException {
		if(c.state==state)return;
		c.state=state;
		if(state==STATE_ACTIVE){
			if(c.savedMenuBar<0){c.savedMenuBar=memory.readWord(0xbaa);memory.writeWord(0xbaa,0);} // the menu bar is hidden
			if(c.backdrop!=0){tb("ShowWindow",c.backdrop);tb("BringToFront",c.backdrop);paint(c.backdrop);}
			tb("ShowWindow",c.window);tb("BringToFront",c.window);
			present(c);
		}else{
			if(c.window!=0)tb("HideWindow",c.window);if(c.backdrop!=0)tb("HideWindow",c.backdrop);
			if(c.savedMenuBar>=0){memory.writeWord(0xbaa,c.savedMenuBar);c.savedMenuBar=-1;}
		}
	}

	/** A borderless window (plainDBox), invisible, whose content colour is the blanking colour. */
	private int newWindow(int top,int left,int bottom,int right) throws M68kException {
		int r=rt.allocate(10,2),title=rt.allocate(2,2);
		try{
			writeRect(r,top,left,bottom,right);
			int w=tb("NewCWindow",0,r,title,0,2,-1,0,0);
			if(w==0)return 0;
			int wctb=tb("NewHandleClear",16); // WCTabHandle: ctSeed, ctFlags, ctSize 0, wContentColor
			if(wctb!=0){int p=memory.readLong(wctb);memory.writeWord(p+8,0);writeRgb(p+10,blanking);tb("SetWinColor",w,wctb);}
			return w;
		}finally{memory.free(r);memory.free(title);}
	}
	private void writeRgb(int p,int rgb) throws M68kCpuException {memory.writeWord(p,(rgb>>>16&255)*0x101);memory.writeWord(p+2,(rgb>>>8&255)*0x101);memory.writeWord(p+4,(rgb&255)*0x101);}
	/** Paints a window in the blanking colour and leaves nothing for an update. */
	private void paint(int window) throws M68kException {
		int[] saved=saveGWorld();int rgb=rt.allocate(8,2),colour=rt.allocate(8,2),r=rt.allocate(8,2);
		try{
			tb("SetGWorld",window,0);tb("GetForeColor",colour);writeRgb(rgb,blanking);tb("RGBForeColor",rgb);
			memory.load(r,memory.readBytes(window+16,8));tb("PaintRect",r);tb("ValidRect",r);tb("RGBForeColor",colour);
		}finally{restoreGWorld(saved);memory.free(rgb);memory.free(colour);memory.free(r);}
	}
	private int[] saveGWorld() throws M68kException {int p=rt.allocate(8,4);try{tb("GetGWorld",p,p+4);return new int[]{memory.readLong(p),memory.readLong(p+4)};}finally{memory.free(p);}}
	private void restoreGWorld(int[] s) throws M68kException {tb("SetGWorld",s[0],s[1]);}

	private int newGWorld(int depth,int width,int height) throws M68kException {
		int p=rt.allocate(12,4);
		try{
			writeRect(p+4,0,0,height,width);
			int e=(short)tb("NewGWorld",p,depth,p+4,0,0,0);
			int gw=memory.readLong(p);if(e!=0||gw==0){System.err.println("[dsp] NewGWorld "+width+"x"+height+"x"+depth+" failed: "+e);return 0;}
			tb("LockPixels",tb("GetGWorldPixMap",gw));
			return gw;
		}finally{memory.free(p);}
	}
	private void disposeGWorld(int gw) throws M68kException {
		int[] saved=saveGWorld();if(saved[0]==gw)tb("SetGWorld",0,0);
		tb("DisposeGWorld",gw);
		if(saved[0]!=gw)restoreGWorld(saved);else{int w=tb("FrontWindow");if(w!=0)tb("SetPort",w);}
	}
	/** Erases a GWorld to black (DSp's buffers start black). */
	private void clear(int gw,int width,int height) throws M68kException {
		int[] saved=saveGWorld();int r=rt.allocate(8,2);
		try{tb("SetGWorld",gw,0);writeRect(r,0,0,height,width);tb("PaintRect",r);}finally{restoreGWorld(saved);memory.free(r);}
	}

	/** CopyBits between two ports (CGrafPtrs) with black on white, so nothing is colourised. */
	private void copy(int source,int[] sourceRect,int destination,int[] destinationRect,int mode) throws M68kException {copy(source,sourceRect,destination,destinationRect,mode,-1);}
	/** The same with the background colour {@code background} (0xRRGGBB), for the transparent mode's colour key. */
	private void copy(int source,int[] sourceRect,int destination,int[] destinationRect,int mode,int background) throws M68kException {
		int[] saved=saveGWorld();int r=rt.allocate(16,2),colours=rt.allocate(18,2);
		try{
			tb("SetGWorld",destination,0);tb("GetForeColor",colours);tb("GetBackColor",colours+6);tb("ForeColor",33);
			if(background<0)tb("BackColor",30);else{writeRgb(colours+12,background);tb("RGBBackColor",colours+12);}
			writeRect(r,sourceRect[0],sourceRect[1],sourceRect[2],sourceRect[3]);writeRect(r+8,destinationRect[0],destinationRect[1],destinationRect[2],destinationRect[3]);
			tb("CopyBits",source+2,destination+2,r,r+8,mode,0);
			tb("RGBForeColor",colours);tb("RGBBackColor",colours+6);
		}finally{restoreGWorld(saved);memory.free(r);memory.free(colours);}
	}
	/** The PixMap of a colour port (0 for a basic one). */
	private int pixMap(int port) throws M68kCpuException {if(port==0||(memory.readWord(port+6)&0xc000)!=0xc000)return 0;int h=memory.readLong(port+2);return h==0?0:memory.readLong(h);}
	private int colorTable(int port) throws M68kCpuException {int pm=pixMap(port);if(pm==0)return 0;int h=memory.readLong(pm+42);return h==0?0:memory.readLong(h);}
	/** An indexed back buffer takes the display's CLUT (entries and seed) so it shows through by index. */
	private void shareDisplayClut(int gw) throws M68kException {
		int pm=pixMap(gw);if(pm==0)return;int depth=memory.readWord(pm+32);if(depth>8||screenDepth()!=depth)return;
		int gd=mainDevice();int gdp=memory.readLong(gd);int dpmh=memory.readLong(gdp+22);int dpm=dpmh==0?0:memory.readLong(dpmh);
		int dh=dpm==0?0:memory.readLong(dpm+42),th=memory.readLong(pm+42);if(dh==0||th==0||dh==th)return;
		int d=memory.readLong(dh),t=memory.readLong(th);if(d==0||t==0)return;
		int n=Math.min(memory.readWord(d+6),memory.readWord(t+6));
		if(memory.readLong(t)==memory.readLong(d))return; // the same seed: the same colours
		memory.load(t+8,memory.readBytes(d+8,8*(n+1)));memory.writeLong(t,memory.readLong(d));
	}
	/** The window shows the back buffer (the program's frame). */
	private void present(Context c) throws M68kException {
		if(c.window==0||c.back==0)return;
		shareDisplayClut(c.back);
		int[] all={0,0,c.height,c.width};copy(c.back,all,c.window,all,0);
		validate(c.window);if(c.backdrop!=0)validate(c.backdrop);
	}
	/** Nothing is left for an update event: the program does not know these windows. */
	private void validate(int window) throws M68kException {
		int[] saved=saveGWorld();int r=rt.allocate(8,2);
		try{tb("SetGWorld",window,0);memory.load(r,memory.readBytes(window+16,8));tb("ValidRect",r);}finally{restoreGWorld(saved);memory.free(r);}
	}

	/** Waits for the next frame (a VBL at 60 Hz or the program's maximum frame rate), with the VM's deferred work running. */
	private void waitFrame(Context c) throws M68kException {
		if((c.options&OPTION_DONT_SYNC_VBL)!=0&&c.maxFps==0)return;
		long period=1_000_000_000L/(c.maxFps>0?Math.min(60,c.maxFps):60),now=System.nanoTime();
		long target=c.nextFrame;
		if(target==0||target<now-period)target=now; // late (or the first frame): no wait, the rhythm starts again here
		c.nextFrame=target+period;
		if(target<=now)return;
		rt.cpu68.yielded();
		Toolbox68k tb=rt.toolbox;
		while(true){
			long remaining=target-System.nanoTime();if(remaining<=0)break;
			if(rt.cpu68.abortRequested())throw new M68kAbortException(rt.cpu.pc);
			tb.runDeferred();tb.runTimers();
			if(remaining>1_500_000L)try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			else Thread.onSpinWait();
		}
	}
	private int swap(Context c,int busyProc,int refCon) throws M68kException {
		if(!c.reserved)return DSP_NOT_RESERVED;
		waitFrame(c);
		if(busyProc!=0)rt.cpu.call(busyProc,c.ref,refCon);
		if(c.vblProc!=0)rt.cpu.call(c.vblProc,c.ref,c.vblRefCon);
		if(c.state==STATE_ACTIVE)present(c);
		if(c.underlay!=null)for(int[] r:c.invalid)copy(c.underlay.gworld,r,c.back,r,0);
		c.invalid.clear();
		return NO_ERR;
	}
	private int[] clip(int[] r,int width,int height){int[] c={Math.max(0,r[0]),Math.max(0,r[1]),Math.min(height,r[2]),Math.min(width,r[3])};return c[0]>=c[2]||c[1]>=c[3]?null:c;}

	/** DSpBlit_Faster / DSpBlit_Fastest: synchronous; a source colour key is honoured between buffers of the same depth. */
	private int blit(int info) throws M68kException {
		if(info==0)return PARAM_ERR;
		int src=memory.readLong(info+12),dst=memory.readLong(info+32);
		if(src==0){Context c=context(memory.readLong(info+8));if(c!=null)src=c.back;}
		if(dst==0){Context c=context(memory.readLong(info+28));if(c!=null)dst=c.back;}
		if(src==0||dst==0)return PARAM_ERR;
		int[] sr=readRect(info+16),dr=readRect(info+36);int mode=memory.readLong(info+48),key=memory.readLong(info+24);
		if((mode&BLIT_SRC_KEY)==0)copy(src,sr,dst,dr,0);
		else if(!keyedCopy(src,sr,dst,dr,key))copy(src,sr,dst,dr,36,keyColour(src,key)); // transparent: the key colour is the background
		memory.writeByte(info,1);
		int done=memory.readLong(info+4);if(done!=0)rt.cpu.call(done,info);
		return NO_ERR;
	}
	/** The colour of a source pixel value (0xRRGGBB): a CLUT entry, or 5-5-5 / 8-8-8 direct colour. */
	private int keyColour(int port,int key) throws M68kCpuException {
		int pm=pixMap(port);int depth=pm==0?1:memory.readWord(pm+32);
		if(depth==32)return key&0xffffff;
		if(depth==16)return ((key>>>10&31)*255/31)<<16|((key>>>5&31)*255/31)<<8|(key&31)*255/31;
		int t=colorTable(port);if(t==0||(key&0xff)>memory.readWord(t+6))return 0xffffff;
		int e=t+8+8*(key&0xff);return (memory.readWord(e+2)>>>8)<<16|(memory.readWord(e+4)>>>8)<<8|memory.readWord(e+6)>>>8;
	}
	/** A keyed copy pixel by pixel between offscreen buffers of the same depth and size (not onto the screen: windows clip there). */
	private boolean keyedCopy(int src,int[] sr,int dst,int[] dr,int key) throws M68kException {
		int sp=pixMap(src),dp=pixMap(dst);if(sp==0||dp==0)return false;
		int gd=memory.readLong(mainDevice());if(memory.readLong(dst+2)==memory.readLong(gd+22))return false;
		int depth=memory.readWord(sp+32);if(depth!=memory.readWord(dp+32)||(depth!=8&&depth!=16&&depth!=32))return false;
		if(sr[2]-sr[0]!=dr[2]-dr[0]||sr[3]-sr[1]!=dr[3]-dr[1])return false;
		int sBase=tb("GetPixBaseAddr",memory.readLong(src+2)),dBase=tb("GetPixBaseAddr",memory.readLong(dst+2));
		int sRow=memory.readWord(sp+4)&0x3fff,dRow=memory.readWord(dp+4)&0x3fff;int[] sb=readRect(sp+6),db=readRect(dp+6);
		int bytes=depth/8,h=sr[2]-sr[0],w=sr[3]-sr[1];
		for(int y=0;y<h;y++){
			int sy=sr[0]+y,dy=dr[0]+y;if(sy<sb[0]||sy>=sb[2]||dy<db[0]||dy>=db[2])continue;
			for(int x=0;x<w;x++){
				int sx=sr[1]+x,dx=dr[1]+x;if(sx<sb[1]||sx>=sb[3]||dx<db[1]||dx>=db[3])continue;
				int s=sBase+(sy-sb[0])*sRow+(sx-sb[1])*bytes,d=dBase+(dy-db[0])*dRow+(dx-db[1])*bytes;
				int v=bytes==1?memory.readByte(s):bytes==2?memory.readWord(s):memory.readLong(s);
				if(bytes==4?(v&0xffffff)==(key&0xffffff):v==(key&(bytes==1?0xff:0xffff)))continue;
				if(bytes==1)memory.writeByte(d,v);else if(bytes==2)memory.writeWord(d,v);else memory.writeLong(d,v);
			}
		}
		return true;
	}

	private void defineDrawSprocket(PpcLibraries l){
		final String lib="DrawSprocketLib";
		l.register(lib,"DSpStartup",(r,c)->ret(NO_ERR));
		l.register(lib,"DSpShutdown",(r,c)->{for(Context x:new ArrayList<Context>(contexts.values()))if(x.reserved)release(x);ret(NO_ERR);}); // the display comes back as it was
		l.register(lib,"DSpGetVersion",(r,c)->{int p=arg(0);memory.writeLong(p,DSP_VERSION);ret(p);}); // NumVersion through the hidden result pointer
		l.register(lib,"DSpGetFirstContext",(r,c)->{List<Context> m=modes();out(arg(1),m.isEmpty()?0:m.get(0).ref);ret(m.isEmpty()?DSP_NOT_FOUND:NO_ERR);});
		l.register(lib,"DSpGetNextContext",(r,c)->{List<Context> m=modes();Context x=context(arg(0));int i=x==null?-2:m.indexOf(x);
			if(x==null){out(arg(1),0);ret(DSP_INVALID_CONTEXT);return;}
			if(i<0||i+1>=m.size()){out(arg(1),0);ret(DSP_NOT_FOUND);return;}
			out(arg(1),m.get(i+1).ref);ret(NO_ERR);});
		l.register(lib,"DSpGetCurrentContext",(r,c)->{Context x=reservedContext();
			if(x==null){int[] s=screen();int d=screenDepth();for(Context m:modes())if(m.width==s[3]-s[1]&&m.height==s[2]-s[0]&&m.depth==d)x=m;}
			if(x==null&&!modes().isEmpty())x=modes().get(modes().size()-1);
			out(arg(1),x==null?0:x.ref);ret(x==null?DSP_NOT_FOUND:NO_ERR);});
		l.register(lib,"DSpFindBestContext",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}Context x=findBest(arg(0));out(arg(1),x==null?0:x.ref);ret(x==null?DSP_NOT_FOUND:NO_ERR);});
		l.register(lib,"DSpFindBestContextOnDisplayID",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}Context x=findBest(arg(0));out(arg(1),x==null?0:x.ref);ret(x==null?DSP_NOT_FOUND:NO_ERR);});
		l.register(lib,"DSpCanUserSelectContext",(r,c)->{if(arg(1)!=0)memory.writeByte(arg(1),0);ret(NO_ERR);}); // no selection dialog: the program picks the best context itself
		l.register(lib,"DSpUserSelectContext",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}Context x=findBest(arg(0));out(arg(3),x==null?0:x.ref);ret(x==null?DSP_NOT_FOUND:NO_ERR);});
		l.register(lib,"DSpProcessEvent",(r,c)->{ // (event, *processed): updates and activation of DSp's own windows, clicks on the blanking window
			int e=arg(0);boolean done=false;
			if(e!=0){int what=memory.readWord(e),msg=memory.readLong(e+2);
				for(Context x:contexts.values()){if(!x.reserved)continue;
					if(what==6&&(msg==x.window||msg==x.backdrop)){tb("BeginUpdate",msg);tb("EndUpdate",msg);if(msg==x.backdrop)paint(msg);else if(x.state==STATE_ACTIVE)present(x);done=true;}
					else if(what==8&&(msg==x.window||msg==x.backdrop))done=true;
					else if(what==1&&x.backdrop!=0){int p=rt.allocate(4,4);try{tb("FindWindow",memory.readLong(e+10),p);if(memory.readLong(p)==x.backdrop)done=true;}finally{memory.free(p);}}
				}}
			if(arg(1)!=0)memory.writeByte(arg(1),done?1:0);ret(NO_ERR);});
		l.register(lib,"DSpSetBlankingColor",(r,c)->{int p=arg(0);if(p!=0)blanking=(memory.readWord(p)>>>8)<<16|(memory.readWord(p+2)>>>8)<<8|memory.readWord(p+4)>>>8;ret(NO_ERR);});
		l.register(lib,"DSpSetDebugMode",(r,c)->{debugMode=(arg(0)&0xff)!=0;ret(NO_ERR);});
		l.register(lib,"DSpFindContextFromPoint",(r,c)->{ // (Point, *context)
			int v=(short)(arg(0)>>>16),h=(short)arg(0);Context x=reservedContext();
			if(x!=null&&!(v>=x.top&&v<x.top+x.height&&h>=x.left&&h<x.left+x.width))x=null;
			if(x==null){int[] s=screen();int d=screenDepth();for(Context m:modes())if(m.width==s[3]-s[1]&&m.height==s[2]-s[0]&&m.depth==d)x=m;}
			out(arg(1),x==null?0:x.ref);ret(x==null?DSP_NOT_FOUND:NO_ERR);});
		l.register(lib,"DSpGetMouse",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}
			java.awt.Point m=rt.toolbox.host().mouseLocation();memory.writeWord(p,m.y);memory.writeWord(p+2,m.x);ret(NO_ERR);});
		// alternate buffers
		l.register(lib,"DSpAltBuffer_New",(r,c)->{ // (context, inVRAMBuffer, *attributes, *outAltBuffer)
			Context x=context(arg(0));if(x==null){out(arg(3),0);ret(DSP_INVALID_CONTEXT);return;}
			int a=arg(2);int w=a==0?0:memory.readLong(a),h=a==0?0:memory.readLong(a+4);if(w<=0)w=x.width;if(h<=0)h=x.height;
			int gw=newGWorld(x.reserved?x.backDepth:x.depth,w,h);if(gw==0){out(arg(3),0);ret(MEM_FULL_ERR);return;}
			clear(gw,w,h);
			int ref=rt.allocate(8,4);memory.writeLong(ref,0x687a4142);memory.writeLong(ref+4,gw); // 'hzAB'
			altBuffers.put(ref,new AltBuffer(ref,gw,w,h));out(arg(3),ref);ret(NO_ERR);});
		l.register(lib,"DSpAltBuffer_Dispose",(r,c)->{AltBuffer a=altBuffers.remove(arg(0));if(a==null){ret(PARAM_ERR);return;}
			for(Context x:contexts.values())if(x.underlay==a)x.underlay=null;
			disposeGWorld(a.gworld);memory.free(a.ref);ret(NO_ERR);});
		l.register(lib,"DSpAltBuffer_InvalRect",(r,c)->{ // the program changed the underlay: the back buffers that show it get the area at once
			AltBuffer a=altBuffers.get(arg(0));if(a==null||arg(1)==0){ret(PARAM_ERR);return;}
			for(Context x:contexts.values())if(x.reserved&&x.underlay==a){int[] cr=clip(readRect(arg(1)),Math.min(a.width,x.width),Math.min(a.height,x.height));if(cr!=null)copy(a.gworld,cr,x.back,cr,0);}
			ret(NO_ERR);});
		l.register(lib,"DSpAltBuffer_GetCGrafPtr",(r,c)->{ // (altBuffer, bufferKind, *port, *gdevice)
			AltBuffer a=altBuffers.get(arg(0));if(a==null){out(arg(2),0);out(arg(3),0);ret(PARAM_ERR);return;}
			out(arg(2),a.gworld);out(arg(3),tb("GetGWorldDevice",a.gworld));ret(NO_ERR);});
		// contexts
		l.register(lib,"DSpContext_GetAttributes",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}if(arg(1)==0){ret(PARAM_ERR);return;}writeAttributes(x,arg(1));ret(NO_ERR);});
		l.register(lib,"DSpContext_Reserve",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}ret(reserve(x,arg(1)));});
		l.register(lib,"DSpContext_Queue",(r,c)->{Context p=context(arg(0)),x=context(arg(1));if(p==null||x==null){ret(DSP_INVALID_CONTEXT);return;}
			p.queued=x;if(p.queuedAttributes==0)p.queuedAttributes=rt.allocate(ATTRIBUTES_SIZE,4);if(arg(2)!=0)memory.load(p.queuedAttributes,memory.readBytes(arg(2),ATTRIBUTES_SIZE));else writeAttributes(p,p.queuedAttributes);ret(NO_ERR);});
		l.register(lib,"DSpContext_Switch",(r,c)->{Context o=context(arg(0)),n=context(arg(1));if(o==null||n==null){ret(DSP_INVALID_CONTEXT);return;}
			if(o==n){ret(NO_ERR);return;}
			int state=o.state;int a=o.queuedAttributes!=0&&o.queued==n?o.queuedAttributes:rt.allocate(ATTRIBUTES_SIZE,4);if(a!=o.queuedAttributes)writeAttributes(o,a);
			if(o.reserved)release(o);int e=reserve(n,a);if(e==0)setState(n,state);ret(e);});
		l.register(lib,"DSpContext_Release",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}if(!x.reserved){ret(DSP_NOT_RESERVED);return;}release(x);ret(NO_ERR);});
		l.register(lib,"DSpContext_Dispose",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}if(x.reserved)release(x);ret(NO_ERR);});
		l.register(lib,"DSpContext_GetDisplayID",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}
			int p=rt.allocate(4,4);try{memory.writeLong(p,1);tb("DMGetDisplayIDByGDevice",mainDevice(),p,1);out(arg(1),memory.readLong(p)==0?1:memory.readLong(p));}finally{memory.free(p);}ret(NO_ERR);});
		l.register(lib,"DSpContext_GlobalToLocal",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}int p=arg(1);
			if(p!=0){memory.writeWord(p,(short)memory.readWord(p)-x.top);memory.writeWord(p+2,(short)memory.readWord(p+2)-x.left);}ret(NO_ERR);});
		l.register(lib,"DSpContext_LocalToGlobal",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}int p=arg(1);
			if(p!=0){memory.writeWord(p,(short)memory.readWord(p)+x.top);memory.writeWord(p+2,(short)memory.readWord(p+2)+x.left);}ret(NO_ERR);});
		l.register(lib,"DSpContext_SetVBLProc",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}x.vblProc=arg(1);x.vblRefCon=arg(2);ret(NO_ERR);});
		l.register(lib,"DSpContext_GetFlattenedSize",(r,c)->{if(context(arg(0))==null){ret(DSP_INVALID_CONTEXT);return;}out(arg(1),16);ret(NO_ERR);});
		l.register(lib,"DSpContext_Flatten",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}if(arg(1)!=0)memory.load(arg(1),memory.readBytes(x.ref,16));ret(NO_ERR);});
		l.register(lib,"DSpContext_Restore",(r,c)->{int p=arg(0);if(p==0||memory.readLong(p)!=0x687a4453){out(arg(1),0);ret(DSP_INVALID_CONTEXT);return;}
			int w=memory.readLong(p+4),h=memory.readLong(p+8),d=memory.readLong(p+12);Context x=null;for(Context m:modes())if(m.width==w&&m.height==h&&m.depth==d)x=m;
			out(arg(1),x==null?0:x.ref);ret(x==null?DSP_NOT_FOUND:NO_ERR);});
		l.register(lib,"DSpContext_GetMonitorFrequency",(r,c)->{if(context(arg(0))==null){ret(DSP_INVALID_CONTEXT);return;}out(arg(1),60<<16);ret(NO_ERR);});
		l.register(lib,"DSpContext_SetMaxFrameRate",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}x.maxFps=Math.max(0,arg(1));ret(NO_ERR);});
		l.register(lib,"DSpContext_GetMaxFrameRate",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}out(arg(1),x.maxFps);ret(NO_ERR);});
		l.register(lib,"DSpContext_SetState",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}if(!x.reserved){ret(DSP_NOT_RESERVED);return;}
			int s=arg(1);if(s<0||s>2){ret(PARAM_ERR);return;}setState(x,s);ret(NO_ERR);});
		l.register(lib,"DSpContext_GetState",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}out(arg(1),x.reserved?x.state:STATE_INACTIVE);ret(NO_ERR);});
		l.register(lib,"DSpContext_IsBusy",(r,c)->{if(context(arg(0))==null){ret(DSP_INVALID_CONTEXT);return;}if(arg(1)!=0)memory.writeByte(arg(1),0);ret(NO_ERR);}); // swaps are synchronous
		l.register(lib,"DSpContext_SetDirtyRectGridSize",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}x.gridWidth=Math.max(1,arg(1));x.gridHeight=Math.max(1,arg(2));ret(NO_ERR);});
		l.register(lib,"DSpContext_GetDirtyRectGridSize",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}out(arg(1),x.gridWidth);out(arg(2),x.gridHeight);ret(NO_ERR);});
		l.register(lib,"DSpContext_GetDirtyRectGridUnits",(r,c)->{if(context(arg(0))==null){ret(DSP_INVALID_CONTEXT);return;}out(arg(1),1);out(arg(2),1);ret(NO_ERR);});
		l.register(lib,"DSpContext_InvalBackBufferRect",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}if(arg(1)==0){ret(PARAM_ERR);return;}
			int[] cr=clip(readRect(arg(1)),x.width,x.height);if(cr!=null&&x.invalid.size()<4096)x.invalid.add(cr);ret(NO_ERR);});
		l.register(lib,"DSpContext_SetUnderlayAltBuffer",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}
			AltBuffer a=arg(1)==0?null:altBuffers.get(arg(1));if(arg(1)!=0&&a==null){ret(PARAM_ERR);return;}x.underlay=a;ret(NO_ERR);});
		l.register(lib,"DSpContext_GetUnderlayAltBuffer",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}out(arg(1),x.underlay==null?0:x.underlay.ref);ret(NO_ERR);});
		// gamma: the fades complete at once (nothing dims)
		l.register(lib,"DSpContext_FadeGammaOut",(r,c)->ret(context(arg(0))==null&&arg(0)!=0?DSP_INVALID_CONTEXT:NO_ERR));
		l.register(lib,"DSpContext_FadeGammaIn",(r,c)->ret(context(arg(0))==null&&arg(0)!=0?DSP_INVALID_CONTEXT:NO_ERR));
		l.register(lib,"DSpContext_FadeGamma",(r,c)->ret(context(arg(0))==null&&arg(0)!=0?DSP_INVALID_CONTEXT:NO_ERR));
		l.register(lib,"DSpContext_SwapBuffers",(r,c)->{Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}ret(swap(x,arg(1),arg(2)));});
		l.register(lib,"DSpContext_GetBackBuffer",(r,c)->{Context x=context(arg(0));if(x==null){out(arg(2),0);ret(DSP_INVALID_CONTEXT);return;}
			if(!x.reserved){out(arg(2),0);ret(DSP_NOT_RESERVED);return;}out(arg(2),x.back);ret(NO_ERR);});
		l.register(lib,"DSpContext_GetFrontBuffer",(r,c)->{Context x=context(arg(0));if(x==null){out(arg(1),0);ret(DSP_INVALID_CONTEXT);return;}
			if(!x.reserved){out(arg(1),0);ret(DSP_NOT_RESERVED);return;}out(arg(1),x.window);ret(NO_ERR);});
		l.register(lib,"DSpContext_SetCLUTEntries",(r,c)->{ // (context, ColorSpec entries[], first, last)
			Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}if(!x.reserved){ret(DSP_NOT_RESERVED);return;}
			int entries=arg(1),first=arg(2)&0xffff,last=arg(3)&0xffff;if(entries==0||last<first||last>255){ret(PARAM_ERR);return;}
			if(x.depth<=8){ // the display's CLUT, on the main device
				int saved=tb("GetGDevice");tb("SetGDevice",mainDevice());try{tb("SetEntries",first,last-first,entries);}finally{tb("SetGDevice",saved);}
				shareDisplayClut(x.back);
			}else{int t=colorTable(x.back);if(t!=0){int n=memory.readWord(t+6);for(int i=first;i<=last&&i<=n;i++)memory.load(t+8+8*i+2,memory.readBytes(entries+8*(i-first)+2,6));tb("CTabChanged",memory.readLong(pixMap(x.back)+42));}}
			ret(NO_ERR);});
		l.register(lib,"DSpContext_GetCLUTEntries",(r,c)->{
			Context x=context(arg(0));if(x==null){ret(DSP_INVALID_CONTEXT);return;}
			int entries=arg(1),first=arg(2)&0xffff,last=arg(3)&0xffff;if(entries==0||last<first||last>255){ret(PARAM_ERR);return;}
			int t;if(x.depth<=8||!x.reserved){int gd=memory.readLong(mainDevice());int pm=memory.readLong(memory.readLong(gd+22));int h=memory.readLong(pm+42);t=h==0?0:memory.readLong(h);}else t=colorTable(x.back);
			for(int i=first;i<=last;i++){int e=entries+8*(i-first);memory.writeWord(e,i);
				if(t!=0&&i<=memory.readWord(t+6))memory.load(e+2,memory.readBytes(t+8+8*i+2,6));else memory.fill(e+2,6,0);}
			ret(NO_ERR);});
		l.register(lib,"DSpBlit_Faster",(r,c)->ret(blit(arg(0))));
		l.register(lib,"DSpBlit_Fastest",(r,c)->ret(blit(arg(0))));
	}

	// ================================================================== InputSprocket

	static final int ISP_BUFFER_TOO_SMALL=-30422,ISP_ELEMENT_NOT_IN_LIST=-30424;
	/** InputSprocket 1.7.5 final (Game Sprockets 1.7.5) (NumVersion). */
	static final int ISP_VERSION=0x01758000;
	static final int KIND_BUTTON=0x6275746e,KIND_DPAD=0x64706164,KIND_AXIS=0x61786973,KIND_DELTA=0x646c7461,KIND_MOVEMENT=0x6d6f7665,KIND_VIRTUAL=0x76697274;
	static final int CLASS_KEYBOARD=0x6b657964,CLASS_MOUSE=0x6d6f7573;
	static final int AXIS_MIN=0,AXIS_MIDDLE=0x7fffffff,AXIS_MAX=0xffffffff;
	/** ISpDPadData directions. */
	static final int PAD_IDLE=0,PAD_LEFT=1,PAD_UP_LEFT=2,PAD_UP=3,PAD_UP_RIGHT=4,PAD_RIGHT=5,PAD_DOWN_RIGHT=6,PAD_DOWN=7,PAD_DOWN_LEFT=8;
	static final int KEY_LEFT=0x7b,KEY_RIGHT=0x7c,KEY_DOWN=0x7d,KEY_UP=0x7e,KEY_ESCAPE=0x35,KEY_SPACE=0x31,KEY_TAB=0x30,
		KEY_SHIFT=0x38,KEY_OPTION=0x3a,KEY_CONTROL=0x3b,KEY_COMMAND=0x37;
	/**
	 * The keys generic button needs take in turn. The first seven are the keyboard layout of the beatmania-style players
	 * (Z S X D C F V: white and black keys alternating, the needs of MacBeat and BMSPlayer are exactly seven keys, then the
	 * turntable), then A and the top letter row, the rest of the letters, the digits and punctuation.
	 */
	private static final int[] KEY_POOL={0x06,0x01,0x07,0x02,0x08,0x03,0x09, 0x00,0x0c,0x0d,0x0e,0x0f,0x11,0x10,0x20,0x22,0x1f,0x23,
		0x05,0x0b,0x04,0x2d,0x26,0x2e,0x28,0x25, 0x12,0x13,0x14,0x15,0x17,0x16,0x1a,0x1c,0x19,0x1d, 0x2b,0x2f,0x2c,0x29,0x27,0x21,0x1e,0x1b,0x18};
	private static final String[] KEY_NAMES={"a","s","d","f","h","g","z","x","c","v","","b","q","w","e","r","y","t","1","2","3","4","6","5","=","9","7","-","8","0","]","o","u","[","i","p",
		"return","l","j","'","k",";","\\",",","/","n","m",".","tab","space","`","delete","","escape","","command","shift","capslock","option","control"};

	/** A virtual element (or one the program made with ISpElement_NewVirtual) and the keys or mouse input bound to it. */
	private final class Element {
		final int ref,dataSize;int kind=KIND_VIRTUAL,label=0x6e6f6e65,group,flags;byte[] name=new byte[0];
		int state;int[] movement={AXIS_MIDDLE,AXIS_MIDDLE,PAD_IDLE};
		final ArrayDeque<int[]> events=new ArrayDeque<int[]>();
		/** Binding: one key (buttons, asymmetric axes), two (low, high: symmetric axes), four (up, down, left, right: pads,
		 *  movement), mouse button (mouse), mouse delta axis 1 = x, 2 = y (delta). */
		int[] keys;boolean mouseButton;int mouseAxis;
		Element(int ref,int dataSize){this.ref=ref;this.dataSize=dataSize;}
		int device(){return mouseButton||mouseAxis!=0?mouseDevice:keys!=null?keyboardDevice:0;}
	}
	/** An element list: the elements with the refCon each was added with, and the events of all of them in time order. */
	private static final class ElementList {
		final int ref;final LinkedHashMap<Element,Integer> elements=new LinkedHashMap<Element,Integer>();final ArrayDeque<int[]> events=new ArrayDeque<int[]>();
		ElementList(int ref){this.ref=ref;}
	}
	private final Object isp=new Object();
	private final Map<Integer,Element> elements=new LinkedHashMap<Integer,Element>();
	private final Map<Integer,ElementList> lists=new HashMap<Integer,ElementList>();
	private ElementList globalList;
	private int keyboardDevice,mouseDevice,keyboardList,mouseList;
	private boolean keyboardActive,mouseActive,suspended,stopped=true;
	private Thread poller;
	/** The program's host window system (keyboard map, pointer); set on the emulation thread. */
	private volatile MacAppHost host;
	/** Some element is bound to the mouse: only then is the pointer polled. */
	private boolean mouseBound,mouseElements;
	private final byte[] keyMap=new byte[16];
	private int lastMouseX=Integer.MIN_VALUE,lastMouseY,lastWarpSerial;
	private static final int MAX_EVENTS=512;

	private void ensureDevices() throws M68kException {
		if(keyboardDevice!=0)return;
		keyboardDevice=rt.allocate(8,4);memory.writeLong(keyboardDevice,0x687a4944);memory.writeLong(keyboardDevice+4,CLASS_KEYBOARD); // 'hzID'
		mouseDevice=rt.allocate(8,4);memory.writeLong(mouseDevice,0x687a4944);memory.writeLong(mouseDevice+4,CLASS_MOUSE);
		ElementList g=new ElementList(rt.allocate(4,4));synchronized(isp){globalList=g;lists.put(g.ref,g);}
	}
	private Element newElement(int dataSize) throws M68kException {
		ensureDevices();
		int ref=rt.allocate(8,4);memory.writeLong(ref,0x687a4945); // 'hzIE'
		Element e=new Element(ref,dataSize);
		synchronized(isp){elements.put(ref,e);globalList.elements.put(e,0);}
		return e;
	}
	private Element element(int ref){synchronized(isp){return elements.get(ref);}}

	/** The key -Dhzisp.keys names: a letter, digit or punctuation, a key's name (space, return, escape, left ...) or $hex / 0xhex. */
	static int keyCode(String name){
		String n=name.trim().toLowerCase(java.util.Locale.ROOT);if(n.isEmpty()||n.equals("-"))return -1;
		switch(n){case "left":return KEY_LEFT;case "right":return KEY_RIGHT;case "up":return KEY_UP;case "down":return KEY_DOWN;case "esc":return KEY_ESCAPE;case "enter":return 0x4c;case "comma":return 0x2b;case "period":return 0x2f;}
		if(n.startsWith("0x")||n.startsWith("$"))try{return Integer.parseInt(n.substring(n.startsWith("$")?1:2),16)&127;}catch(NumberFormatException e){return -1;}
		for(int i=0;i<KEY_NAMES.length;i++)if(KEY_NAMES[i].equals(n))return i;
		return -1;
	}
	static String keyName(int code){
		switch(code){case KEY_LEFT:return "left";case KEY_RIGHT:return "right";case KEY_UP:return "up";case KEY_DOWN:return "down";}
		return code>=0&&code<KEY_NAMES.length&&!KEY_NAMES[code].isEmpty()?KEY_NAMES[code]:"$"+Integer.toHexString(code);
	}

	/**
	 * Binds the elements of the program's needs to the keyboard and the mouse, as InputSprocket's automatic configuration does
	 * (see the class comment for the keys); returns a description for the log.
	 */
	private String bind(Element[] es){
		java.util.Set<Integer> used=new java.util.HashSet<Integer>();
		String[] given=System.getProperty("hzisp.keys","").isEmpty()?new String[0]:System.getProperty("hzisp.keys").split(",");
		// the labelled needs first, so a generic button never takes the key a known label asks for
		for(int pass=0;pass<2;pass++)for(int i=0;i<es.length;i++){
			Element e=es[i];if(e==null)continue;
			if(pass==0){e.keys=null;e.mouseButton=false;e.mouseAxis=0;}
			if(i<given.length){if(pass==0){int k=keyCode(given[i]);if(k>=0){e.keys=e.kind==KIND_DPAD||e.kind==KIND_MOVEMENT?new int[]{KEY_UP,KEY_DOWN,KEY_LEFT,KEY_RIGHT}:new int[]{k};used.add(k);}}continue;}
			if(pass==0){
				int k=labelKey(e.label);
				switch(e.kind){
					case KIND_DPAD:case KIND_MOVEMENT:e.keys=new int[]{KEY_UP,KEY_DOWN,KEY_LEFT,KEY_RIGHT};break;
					case KIND_AXIS:
						if(e.label==0x78617869||e.label==0x72796178||e.label==0x72756464)e.keys=new int[]{KEY_LEFT,KEY_RIGHT};      // xaxi, yaw, rudder
						else if(e.label==0x79617869||e.label==0x72786178)e.keys=new int[]{KEY_DOWN,KEY_UP};                           // yaxi, pitch: up = maximum
						break;
					case KIND_DELTA:
						if(e.label==0x78646c74||e.label==0x63757278||e.label==0x7279646c)e.mouseAxis=1;       // xdlt, curx, rydl (yaw)
						else if(e.label==0x79646c74||e.label==0x63757279||e.label==0x7278646c)e.mouseAxis=2; // ydlt, cury, rxdl (pitch)
						break;
					default:
						if(e.label==0x6d6f7531)e.mouseButton=true; // mou1
						else if(k>=0&&!used.contains(k)){e.keys=new int[]{k};used.add(k);}
				}
			}else if(e.keys==null&&!e.mouseButton&&e.mouseAxis==0){
				if(e.kind==KIND_BUTTON||e.kind==KIND_VIRTUAL){int k=nextFree(used);if(k>=0){e.keys=new int[]{k};used.add(k);}}
				else if(e.kind==KIND_AXIS){ // a pedal (asymmetric) takes one key, other axes two
					boolean asymmetric=(e.flags&0x20000000)!=0||e.label==0x67617370||e.label==0x6272616b||e.label==0x636c7463||e.label==0x74687274;
					int a=nextFree(used);if(a>=0)used.add(a);int b=asymmetric?-1:nextFree(used);if(b>=0)used.add(b);
					if(a>=0)e.keys=asymmetric||b<0?new int[]{a}:new int[]{a,b};
				}
			}
		}
		mouseBound=false;for(Element e:elements.values())if(e.mouseButton||e.mouseAxis!=0)mouseBound=true;
		StringBuilder sb=new StringBuilder();
		for(Element e:es){if(e==null)continue;if(sb.length()>0)sb.append(", ");sb.append('"').append(MacJapaneseDecoder.decode(e.name)).append("\" ").append(fourCC(e.kind)).append('/').append(fourCC(e.label)).append(" = ");
			if(e.mouseButton)sb.append("mouse button");else if(e.mouseAxis!=0)sb.append(e.mouseAxis==1?"mouse x":"mouse y");
			else if(e.keys==null)sb.append("-");else for(int j=0;j<e.keys.length;j++)sb.append(j>0?"/":"").append(keyName(e.keys[j]));}
		return sb.toString();
	}
	private static int nextFree(java.util.Set<Integer> used){for(int k:KEY_POOL)if(!used.contains(k))return k;return -1;}
	/** The usual key for a labelled button need, -1 for none. */
	private static int labelKey(int label){
		switch(label){
			case 0x73747274:return KEY_ESCAPE;   // strt: Quit
			case 0x70617573:return 0x23;         // paus: P
			case 0x6f70746e:return KEY_TAB;      // optn: Select
			case 0x66697265:return KEY_SPACE;    // fire
			case 0x73666972:return KEY_OPTION;   // sfir
			case 0x6a756d70:return KEY_COMMAND;  // jump
			case 0x7175696b:return KEY_SHIFT;    // quik: Run
			case 0x73696465:return KEY_CONTROL;  // side: Sidestep
			case 0x62746d66:return KEY_UP;       // btmf: Move Forward
			case 0x62746d62:return KEY_DOWN;     // btmb
			case 0x6274746c:return KEY_LEFT;     // bttl: Turn Left
			case 0x62747472:return KEY_RIGHT;    // bttr
			case 0x626c6674:return 0x2b;         // blft: Slide Left ","
			case 0x62726774:return 0x2f;         // brgt: Slide Right "."
			case 0x62746c75:return 0x74;         // btlu: Look Up = page up
			case 0x62746c64:return 0x79;         // btld: Look Down = page down
			case 0x62746e78:return 0x1e;         // btnx: Next "]"
			case 0x62747076:return 0x21;         // btpv: Previous "["
			default:return -1;
		}
	}

	/** Samples the keyboard and mouse and turns changes into element events (poller thread or emulation thread). */
	private void poll(){
		MacAppHost host=this.host;
		if(host==null)return;
		long now=System.nanoTime();
		synchronized(isp){
			if(stopped||suspended||!(keyboardActive||mouseActive))return;
			java.util.Arrays.fill(keyMap,(byte)0);
			if(keyboardActive){host.keyMap(keyMap);int m=host.modifiers();
				if((m&0x100)!=0)keyMap[KEY_COMMAND>>>3]|=1<<(KEY_COMMAND&7);if((m&0x200)!=0)keyMap[KEY_SHIFT>>>3]|=1<<(KEY_SHIFT&7);
				if((m&0x800)!=0)keyMap[KEY_OPTION>>>3]|=1<<(KEY_OPTION&7);if((m&0x1000)!=0)keyMap[KEY_CONTROL>>>3]|=1<<(KEY_CONTROL&7);}
			int dx=0,dy=0;boolean button=false;
			if(mouseActive&&mouseBound){
				int w1=host.warpSerial();java.awt.Point p=host.rawPointerLocation();int w2=host.warpSerial();
				// a pointer the program warped (re-centred) moved without the hand: no delta for that step
				if(lastMouseX!=Integer.MIN_VALUE&&w1==w2&&w1==lastWarpSerial){dx=p.x-lastMouseX;dy=p.y-lastMouseY;}
				lastWarpSerial=w1==w2?w1:-1;lastMouseX=p.x;lastMouseY=p.y;button=host.isMouseDown();}
			for(Element e:elements.values()){
				if(e.mouseButton){if(mouseActive)update(e,button?1:0,now);continue;}
				if(e.mouseAxis!=0){if(mouseActive){int d=e.mouseAxis==1?dx:-dy;if(d!=0){d*=MOUSE_SCALE;e.state+=d;event(e,d,now);}}continue;}
				if(e.keys==null||!keyboardActive)continue;
				int[] k=e.keys;
				switch(e.kind){
					case KIND_DPAD:update(e,pad(down(k[0]),down(k[1]),down(k[2]),down(k[3])),now);break;
					case KIND_MOVEMENT:{boolean u=down(k[0]),d=down(k[1]),l=down(k[2]),r=down(k[3]);
						int x=l==r?AXIS_MIDDLE:l?AXIS_MIN:AXIS_MAX,y=u==d?AXIS_MIDDLE:u?AXIS_MAX:AXIS_MIN,dir=pad(u,d,l,r);
						if(e.movement[0]!=x||e.movement[1]!=y||e.movement[2]!=dir){e.movement=new int[]{x,y,dir};e.state=dir;event(e,dir,now);}
						break;}
					case KIND_AXIS:
						if(k.length==1)update(e,down(k[0])?AXIS_MAX:AXIS_MIN,now);
						else{boolean lo=down(k[0]),hi=down(k[1]);update(e,lo==hi?AXIS_MIDDLE:lo?AXIS_MIN:AXIS_MAX,now);}
						break;
					default:update(e,down(k[0])?1:0,now);
				}
			}
		}
	}
	private boolean down(int key){return key>=0&&(keyMap[key>>>3]&(1<<(key&7)))!=0;}
	private static int pad(boolean u,boolean d,boolean l,boolean r){
		if(u&&d){u=false;d=false;}if(l&&r){l=false;r=false;}
		if(u)return l?PAD_UP_LEFT:r?PAD_UP_RIGHT:PAD_UP;if(d)return l?PAD_DOWN_LEFT:r?PAD_DOWN_RIGHT:PAD_DOWN;
		return l?PAD_LEFT:r?PAD_RIGHT:PAD_IDLE;
	}
	private void update(Element e,int value,long now){if(e.state!=value){e.state=value;event(e,value,now);}}
	/** Queues an event on the element and on every list holding it (with that list's refCon). */
	private void event(Element e,int data,long when){
		if((e.flags&4)!=0)return; // kISpNeedFlag_PolledOnly
		e.events.addLast(new int[]{(int)(when>>>32),(int)when,e.ref,0,data});if(e.events.size()>MAX_EVENTS)e.events.removeFirst();
		for(ElementList l:lists.values()){Integer refCon=l.elements.get(e);if(refCon==null)continue;
			l.events.addLast(new int[]{(int)(when>>>32),(int)when,e.ref,refCon,data});if(l.events.size()>MAX_EVENTS)l.events.removeFirst();}
	}
	/** The poller runs while a device is active: key transitions get their own time and short taps are not lost. */
	private void startPoller(){
		synchronized(isp){
			if(poller!=null||stopped||!(keyboardActive||mouseActive))return;
			Thread t=new Thread(()->{
				while(true){synchronized(isp){if(poller!=Thread.currentThread())return;}poll();try{Thread.sleep(1);}catch(InterruptedException e){return;}}
			},"InputSprocket poller");
			t.setDaemon(true);poller=t;t.start();
		}
	}
	private void stopPoller(){Thread t;synchronized(isp){t=poller;poller=null;}if(t!=null)t.interrupt();}
	/** (emulation thread: the host is made on first use, and the poller only reads it) */
	private void activeChanged(){
		if(host==null)try{host=rt.toolbox.host();}catch(RuntimeException e){host=null;}
		synchronized(isp){lastMouseX=Integer.MIN_VALUE;mouseBound=false;for(Element e:elements.values())if(e.mouseButton||e.mouseAxis!=0)mouseBound=true;}
		if(keyboardActive||mouseActive)startPoller();else stopPoller();
	}

	private int writeEvent(int[] ev,int bufSize,int p) throws M68kCpuException {
		byte[] b=new byte[20];int[] v={ev[0],ev[1],ev[2],ev[3],ev[4]};for(int i=0;i<5;i++){b[4*i]=(byte)(v[i]>>>24);b[4*i+1]=(byte)(v[i]>>>16);b[4*i+2]=(byte)(v[i]>>>8);b[4*i+3]=(byte)v[i];}
		int n=Math.max(0,Math.min(20,bufSize));if(p!=0&&n>0)memory.load(p,b,0,n);
		return bufSize<20?ISP_BUFFER_TOO_SMALL:NO_ERR;
	}
	private int extract(Iterable<Element> source,int kind,int label,int count,int outCount,int buffer) throws M68kCpuException {
		int n=0;for(Element e:source){if(kind!=0&&e.kind!=kind||label!=0&&e.label!=label)continue;if(n<count&&buffer!=0)memory.writeLong(buffer+4*n,e.ref);n++;}
		out(outCount,n);return NO_ERR;
	}
	private List<Element> elementsOf(int count,int array) throws M68kCpuException {List<Element> l=new ArrayList<Element>();for(int i=0;i<count&&array!=0;i++){Element e=element(memory.readLong(array+4*i));l.add(e);}return l;}
	private int deviceList(int device) throws M68kException {
		ensureDevices();
		int ref=device==keyboardDevice?keyboardList:mouseList;
		if(ref==0){ref=rt.allocate(4,4);synchronized(isp){lists.put(ref,new ElementList(ref));}if(device==keyboardDevice)keyboardList=ref;else mouseList=ref;}
		ElementList l=lists.get(ref);
		if(device==mouseDevice&&!mouseElements){ // the mouse driver's own elements (X and Y movement, button), which programs like Heavy Metal F.A.K.K.2 read from the device's list directly
			mouseElements=true;
			for(int i=0;i<3;i++){
				int er=rt.allocate(8,4);memory.writeLong(er,0x687a4945);
				Element e=new Element(er,4);e.kind=i<2?KIND_DELTA:KIND_BUTTON;e.label=i==0?0x78646c74:i==1?0x79646c74:0x6d6f7531; // xdlt, ydlt, mou1
				e.name=(i==0?"X-axis":i==1?"Y-axis":"Button").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
				if(i<2)e.mouseAxis=i+1;else e.mouseButton=true;
				synchronized(isp){elements.put(er,e);mouseBound=true;}
			}
		}
		synchronized(isp){for(Element e:elements.values())if(e.device()==device&&!l.elements.containsKey(e))l.elements.put(e,0);}
		return ref;
	}

	private void defineInputSprocket(PpcLibraries l){
		final String lib="InputSprocketLib";
		l.register(lib,"ISpGetVersion",(r,c)->{int p=arg(0);memory.writeLong(p,ISP_VERSION);ret(p);}); // NumVersion through the hidden result pointer
		l.register(lib,"ISpStartup",(r,c)->{ensureDevices();synchronized(isp){stopped=false;}activeChanged();ret(NO_ERR);});
		l.register(lib,"ISpShutdown",(r,c)->{synchronized(isp){stopped=true;keyboardActive=false;mouseActive=false;}stopPoller();ret(NO_ERR);});
		l.register(lib,"ISpTickle",(r,c)->{poll();ret(NO_ERR);});
		l.register(lib,"ISpElement_NewVirtual",(r,c)->{ // (dataSize, *outElement, flags)
			if(arg(1)==0){ret(PARAM_ERR);return;}Element e=newElement(Math.max(4,arg(0)));out(arg(1),e.ref);ret(NO_ERR);});
		l.register(lib,"ISpElement_NewVirtualFromNeeds",(r,c)->{ // (count, needs[], outElements[], flags)
			int n=arg(0),needs=arg(1),outs=arg(2);if(n<0||n>0&&(needs==0||outs==0)){ret(PARAM_ERR);return;}
			Element[] es=new Element[n];
			for(int i=0;i<n;i++){int need=needs+92*i;int kind=memory.readLong(need+68);Element e=newElement(kind==KIND_MOVEMENT?12:4);
				e.kind=kind;e.label=memory.readLong(need+72);e.flags=memory.readLong(need+76);e.group=memory.readByte(need+67);e.name=rt.pBytes(need);
				memory.writeLong(outs+4*i,e.ref);es[i]=e;}
			String s;synchronized(isp){s=bind(es);}if(TRACE)System.err.println("[isp] "+s);
			ret(NO_ERR);});
		l.register(lib,"ISpElement_DisposeVirtual",(r,c)->{
			int n=arg(0),a=arg(1);
			synchronized(isp){for(int i=0;i<n&&a!=0;i++){Element e=elements.remove(memory.readLong(a+4*i));if(e==null)continue;for(ElementList x:lists.values()){x.elements.remove(e);x.events.removeIf(ev->ev[2]==e.ref);}memory.free(e.ref);}}
			ret(NO_ERR);});
		l.register(lib,"ISpInit",(r,c)->{ // (count, needs[], references[], appCreator, subCreator, flags, setListResID, reserved): the automatic configuration
			int n=arg(0),needs=arg(1),refs=arg(2);ensureDevices();
			if(n>0&&refs!=0){Element[] es=new Element[n];
				for(int i=0;i<n;i++){Element e=element(memory.readLong(refs+4*i));es[i]=e;
					if(e!=null&&needs!=0){int need=needs+92*i;e.kind=memory.readLong(need+68);e.label=memory.readLong(need+72);e.flags=memory.readLong(need+76);e.group=memory.readByte(need+67);
						e.name=rt.pBytes(need);}}
				String s;synchronized(isp){s=bind(es);}System.err.println("[isp] "+s);}
			synchronized(isp){stopped=false;}activeChanged();ret(NO_ERR);});
		l.register(lib,"ISpConfigure",(r,c)->ret(NO_ERR)); // no configuration dialog: the automatic binding stays
		l.register(lib,"ISpStop",(r,c)->{synchronized(isp){stopped=true;}stopPoller();ret(NO_ERR);});
		l.register(lib,"ISpSuspend",(r,c)->{synchronized(isp){suspended=true;}ret(NO_ERR);});
		l.register(lib,"ISpResume",(r,c)->{synchronized(isp){suspended=false;lastMouseX=Integer.MIN_VALUE;}ret(NO_ERR);});
		// devices
		l.register(lib,"ISpDevices_Extract",(r,c)->{ensureDevices();int n=arg(0);if(arg(2)!=0){if(n>0)memory.writeLong(arg(2),keyboardDevice);if(n>1)memory.writeLong(arg(2)+4,mouseDevice);}out(arg(1),2);ret(NO_ERR);});
		l.register(lib,"ISpDevices_ExtractByClass",(r,c)->{ensureDevices();int cls=arg(0),d=cls==CLASS_KEYBOARD?keyboardDevice:cls==CLASS_MOUSE?mouseDevice:0;
			if(d!=0&&arg(1)>0&&arg(3)!=0)memory.writeLong(arg(3),d);out(arg(2),d==0?0:1);ret(NO_ERR);});
		l.register(lib,"ISpDevices_ExtractByIdentifier",(r,c)->{ensureDevices();int id=arg(0),d=id==CLASS_KEYBOARD?keyboardDevice:id==CLASS_MOUSE?mouseDevice:0;
			if(d!=0&&arg(1)>0&&arg(3)!=0)memory.writeLong(arg(3),d);out(arg(2),d==0?0:1);ret(NO_ERR);});
		l.register(lib,"ISpDevices_ActivateClass",(r,c)->{ensureDevices();synchronized(isp){if(arg(0)==CLASS_KEYBOARD)keyboardActive=true;else if(arg(0)==CLASS_MOUSE)mouseActive=true;}activeChanged();ret(NO_ERR);});
		l.register(lib,"ISpDevices_DeactivateClass",(r,c)->{synchronized(isp){if(arg(0)==CLASS_KEYBOARD)keyboardActive=false;else if(arg(0)==CLASS_MOUSE)mouseActive=false;}activeChanged();ret(NO_ERR);});
		l.register(lib,"ISpDevices_Activate",(r,c)->{ensureDevices();synchronized(isp){for(int i=0;i<arg(0)&&arg(1)!=0;i++){int d=memory.readLong(arg(1)+4*i);if(d==keyboardDevice)keyboardActive=true;else if(d==mouseDevice)mouseActive=true;}}activeChanged();ret(NO_ERR);});
		l.register(lib,"ISpDevices_Deactivate",(r,c)->{ensureDevices();synchronized(isp){for(int i=0;i<arg(0)&&arg(1)!=0;i++){int d=memory.readLong(arg(1)+4*i);if(d==keyboardDevice)keyboardActive=false;else if(d==mouseDevice)mouseActive=false;}}activeChanged();ret(NO_ERR);});
		l.register(lib,"ISpDevice_IsActive",(r,c)->{int d=arg(0);if(d==0||d!=keyboardDevice&&d!=mouseDevice){ret(PARAM_ERR);return;}if(arg(1)!=0)memory.writeByte(arg(1),(d==keyboardDevice?keyboardActive:mouseActive)?1:0);ret(NO_ERR);});
		l.register(lib,"ISpDevice_GetDefinition",(r,c)->{ // (device, buflen, ISpDeviceDefinition*)
			int d=arg(0),n=arg(1),p=arg(2);if(d==0||p==0||d!=keyboardDevice&&d!=mouseDevice){ret(PARAM_ERR);return;}
			byte[] b=new byte[92];String name=d==keyboardDevice?"Keyboard":"Mouse";b[0]=(byte)name.length();for(int i=0;i<name.length();i++)b[1+i]=(byte)name.charAt(i);
			int cls=d==keyboardDevice?CLASS_KEYBOARD:CLASS_MOUSE;for(int i=0;i<4;i++){b[64+i]=(byte)(cls>>>(24-8*i));b[68+i]=(byte)(cls>>>(24-8*i));}b[75]=1;
			memory.load(p,b,0,Math.max(0,Math.min(92,n)));ret(n<92?ISP_BUFFER_TOO_SMALL:NO_ERR);});
		l.register(lib,"ISpDevice_GetElementList",(r,c)->{int d=arg(0);if(d==0||d!=keyboardDevice&&d!=mouseDevice||arg(1)==0){ret(PARAM_ERR);return;}out(arg(1),deviceList(d));ret(NO_ERR);});
		// elements
		l.register(lib,"ISpElement_GetGroup",(r,c)->{Element e=element(arg(0));if(e==null){ret(PARAM_ERR);return;}out(arg(1),e.group);ret(NO_ERR);});
		l.register(lib,"ISpElement_GetDevice",(r,c)->{Element e=element(arg(0));if(e==null){ret(PARAM_ERR);return;}out(arg(1),e.device());ret(NO_ERR);});
		l.register(lib,"ISpElement_GetInfo",(r,c)->{Element e=element(arg(0));int p=arg(1);if(e==null||p==0){ret(PARAM_ERR);return;}
			memory.fill(p,80,0);memory.writeLong(p,e.label);memory.writeLong(p+4,e.kind);
			byte[] b=e.name;int n=Math.min(63,b.length);memory.writeByte(p+8,n);memory.load(p+9,b,0,n);ret(NO_ERR);});
		l.register(lib,"ISpElement_GetConfigurationInfo",(r,c)->{Element e=element(arg(0));int n=arg(1),p=arg(2);if(e==null||p==0){ret(PARAM_ERR);return;}
			if(n>0)memory.fill(p,n,0);if(e.kind==KIND_AXIS&&n>0)memory.writeByte(p,e.keys!=null&&e.keys.length==2?1:0); // symetricAxis
			ret(NO_ERR);});
		l.register(lib,"ISpElement_GetSimpleState",(r,c)->{Element e=element(arg(0));if(e==null||arg(1)==0){ret(PARAM_ERR);return;}
			if(poller==null)poll();int v;synchronized(isp){v=e.state;if(e.mouseAxis!=0)e.state=0;}memory.writeLong(arg(1),v);ret(NO_ERR);}); // a delta reads the movement since the last read
		l.register(lib,"ISpElement_GetComplexState",(r,c)->{Element e=element(arg(0));int n=arg(1),p=arg(2);if(e==null||p==0){ret(PARAM_ERR);return;}
			if(poller==null)poll();int[] v;synchronized(isp){v=e.kind==KIND_MOVEMENT?e.movement.clone():new int[]{e.state};if(e.mouseAxis!=0)e.state=0;}
			for(int i=0;i<v.length&&4*i+4<=n;i++)memory.writeLong(p+4*i,v[i]);ret(n<4*v.length?ISP_BUFFER_TOO_SMALL:NO_ERR);});
		l.register(lib,"ISpElement_GetNextEvent",(r,c)->{Element e=element(arg(0));if(e==null){ret(PARAM_ERR);return;}
			if(poller==null)poll();int[] ev;synchronized(isp){ev=e.events.pollFirst();}
			if(arg(3)!=0)memory.writeByte(arg(3),ev==null?0:1);ret(ev==null?NO_ERR:writeEvent(ev,arg(1),arg(2)));});
		l.register(lib,"ISpElement_Flush",(r,c)->{Element e=element(arg(0));if(e==null){ret(PARAM_ERR);return;}synchronized(isp){e.events.clear();}ret(NO_ERR);});
		// element lists
		l.register(lib,"ISpElementList_New",(r,c)->{ // (count, elements[], *outList, flags)
			if(arg(2)==0){ret(PARAM_ERR);return;}ensureDevices();int ref=rt.allocate(4,4);ElementList x=new ElementList(ref);
			synchronized(isp){for(Element e:elementsOf(arg(0),arg(1)))if(e!=null)x.elements.put(e,0);lists.put(ref,x);}
			out(arg(2),ref);ret(NO_ERR);});
		l.register(lib,"ISpElementList_Dispose",(r,c)->{ElementList x;synchronized(isp){x=arg(0)==(globalList==null?0:globalList.ref)?null:lists.remove(arg(0));}if(x==null){ret(PARAM_ERR);return;}memory.free(x.ref);ret(NO_ERR);});
		l.register(lib,"ISpGetGlobalElementList",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}ensureDevices();out(arg(0),globalList.ref);ret(NO_ERR);});
		l.register(lib,"ISpElementList_AddElements",(r,c)->{ // (list, refCon, count, elements[])
			ElementList x;synchronized(isp){x=lists.get(arg(0));}if(x==null){ret(PARAM_ERR);return;}
			int refCon=arg(1);synchronized(isp){for(Element e:elementsOf(arg(2),arg(3)))if(e!=null)x.elements.put(e,refCon);}ret(NO_ERR);});
		l.register(lib,"ISpElementList_RemoveElements",(r,c)->{
			ElementList x;synchronized(isp){x=lists.get(arg(0));}if(x==null){ret(PARAM_ERR);return;}
			int e2=NO_ERR;synchronized(isp){for(Element e:elementsOf(arg(1),arg(2))){if(e==null||x.elements.remove(e)==null)e2=ISP_ELEMENT_NOT_IN_LIST;else x.events.removeIf(ev->ev[2]==e.ref);}}ret(e2);});
		l.register(lib,"ISpElementList_Extract",(r,c)->{ElementList x;synchronized(isp){x=lists.get(arg(0));}if(x==null){ret(PARAM_ERR);return;}
			List<Element> es;synchronized(isp){es=new ArrayList<Element>(x.elements.keySet());}ret(extract(es,0,0,arg(1),arg(2),arg(3)));});
		l.register(lib,"ISpElementList_ExtractByKind",(r,c)->{ElementList x;synchronized(isp){x=lists.get(arg(0));}if(x==null){ret(PARAM_ERR);return;}
			List<Element> es;synchronized(isp){es=new ArrayList<Element>(x.elements.keySet());}ret(extract(es,arg(1),0,arg(2),arg(3),arg(4)));});
		l.register(lib,"ISpElementList_ExtractByLabel",(r,c)->{ElementList x;synchronized(isp){x=lists.get(arg(0));}if(x==null){ret(PARAM_ERR);return;}
			List<Element> es;synchronized(isp){es=new ArrayList<Element>(x.elements.keySet());}ret(extract(es,0,arg(1),arg(2),arg(3),arg(4)));});
		l.register(lib,"ISpElementList_GetNextEvent",(r,c)->{ // (list, bufSize, event*, *wasEvent)
			ElementList x;synchronized(isp){x=lists.get(arg(0));}if(x==null){ret(PARAM_ERR);return;}
			if(poller==null)poll();int[] ev;synchronized(isp){ev=x.events.pollFirst();}
			if(TRACE&&ev!=null)System.err.printf("[isp] event: element %08X refCon %d data %08X%n",ev[2],ev[3],ev[4]);
			if(arg(3)!=0)memory.writeByte(arg(3),ev==null?0:1);ret(ev==null?NO_ERR:writeEvent(ev,arg(1),arg(2)));});
		l.register(lib,"ISpElementList_Flush",(r,c)->{ElementList x;synchronized(isp){x=lists.get(arg(0));if(x!=null)x.events.clear();}ret(x==null?PARAM_ERR:NO_ERR);});
		// time
		l.register(lib,"ISpUptime",(r,c)->{long t=System.nanoTime();int p=arg(0);memory.writeLong(p,(int)(t>>>32));memory.writeLong(p+4,(int)t);ret(p);}); // AbsoluteTime (nanoseconds, as UpTime) through the hidden result pointer
		l.register(lib,"ISpTimeToMicroseconds",(r,c)->{int in=arg(0),o=arg(1);if(in==0||o==0){ret(PARAM_ERR);return;}
			long t=((long)memory.readLong(in)<<32|memory.readLong(in+4)&0xffffffffL)/1000L;memory.writeLong(o,(int)(t>>>32));memory.writeLong(o+4,(int)t);ret(NO_ERR);});
		l.register(lib,"ISpPlotAppIconSuite",(r,c)->ret((short)tb("PlotIconID",arg(0),(short)arg(1),(short)arg(2),(short)arg(3))));
	}

	// ================================================================== SoundSprocket

	/** A listener or a source: positions and vectors (x, y, z), the camera transform, and the scalar settings. */
	private static final class Spatial {
		float[] position={0,0,0},orientation={0,0,-1},up={0,1,0},velocity={0,0,0},lastPosition=null;
		float[] transform={1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
		int medium,cpuLoad,mode=1;float humidity=0,roomSize=0,reflectivity=0,reverbAttenuation=0,metersPerUnit=1,referenceDistance=1,length,width,height,coneAngle=(float)(2*Math.PI),coneAttenuation=0;
		long lastNanos;float[] actualVelocity={0,0,0};
		void moved(){long now=System.nanoTime();if(lastPosition!=null&&now>lastNanos){float dt=(now-lastNanos)/1e9f;for(int i=0;i<3;i++)actualVelocity[i]=(position[i]-lastPosition[i])/dt;}lastPosition=position.clone();lastNanos=now;}
	}
	private final Map<Integer,Spatial> spatials=new HashMap<Integer,Spatial>();

	private float[] floats(int p,int n) throws M68kCpuException {float[] f=new float[n];for(int i=0;i<n;i++)f[i]=Float.intBitsToFloat(memory.readLong(p+4*i));return f;}
	private void writeFloats(int p,float... f) throws M68kCpuException {if(p==0)return;for(int i=0;i<f.length;i++)memory.writeLong(p+4*i,Float.floatToRawIntBits(f[i]));}
	private void writeFloat(int p,float f) throws M68kCpuException {if(p!=0)memory.writeLong(p,Float.floatToRawIntBits(f));}
	private float farg(int i){return (float)rt.farg(i);}
	private static float[] minus(float[] a,float[] b){return new float[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
	private static float dot(float[] a,float[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
	private static float[] cross(float[] a,float[] b){return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
	private static float length(float[] a){return (float)Math.sqrt(dot(a,a));}
	/** A camera placement (location, point of interest, up vector) sets position, orientation and up. */
	private static void place(Spatial s,float[] location,float[] interest,float[] up){s.position=location;s.orientation=minus(interest,location);s.up=up;s.moved();}
	/** A transform (QuickDraw 3D row vectors: the translation in the last row, the object looks down -z) sets the same. */
	private static void transform(Spatial s,float[] m){s.transform=m;s.position=new float[]{m[12],m[13],m[14]};s.orientation=new float[]{-m[8],-m[9],-m[10]};s.up=new float[]{m[4],m[5],m[6]};s.moved();}

	/** SSpSource_CalcLocalization: where the source is as the listener hears it (SSpLocalizationData, 196 bytes). */
	private void localize(Spatial src,Spatial lis,int p) throws M68kCpuException {
		memory.fill(p,196,0);
		float[] d=minus(src.position,lis.position);float dist=length(d);
		float[] f=lis.orientation,u=lis.up;float fl=length(f),ul=length(u);
		float azimuth=0,elevation=0;
		if(dist>0&&fl>0&&ul>0){f=new float[]{f[0]/fl,f[1]/fl,f[2]/fl};u=new float[]{u[0]/ul,u[1]/ul,u[2]/ul};float[] right=cross(f,u);
			float x=dot(d,right),y=dot(d,u),z=dot(d,f);azimuth=(float)Math.atan2(-x,z);elevation=(float)Math.atan2(y,Math.sqrt(x*x+z*z));} // azimuth positive to the left, elevation up
		float projection=1;float sl=length(src.orientation);if(dist>0&&sl>0)projection=-dot(src.orientation,d)/(sl*dist);
		float sourceVelocity=dist>0?-dot(src.velocity,d)/dist:0,listenerVelocity=dist>0?dot(lis.velocity,d)/dist:0;
		memory.writeLong(p,src.cpuLoad);memory.writeLong(p+4,lis.medium);writeFloats(p+8,lis.humidity,lis.roomSize,lis.reflectivity,lis.reverbAttenuation);
		memory.writeLong(p+24,src.mode);writeFloats(p+28,src.referenceDistance,(float)Math.cos(src.coneAngle/2),src.coneAttenuation);
		writeFloats(p+40,elevation,azimuth,dist*lis.metersPerUnit,projection,sourceVelocity*lis.metersPerUnit,listenerVelocity*lis.metersPerUnit);
	}

	private void defineSoundSprocket(PpcLibraries l){
		// registered by name: see the class comment
		l.register(null,"SSpConfigureSpeakerSetup",(r,c)->ret(NO_ERR)); // no setup dialog
		l.register(null,"SSpGetCPULoadLimit",(r,c)->{out(arg(0),3);ret(NO_ERR);});
		for(final String kind:new String[]{"Listener","Source"}){
			final String p="SSp"+kind+"_";
			l.register(null,p+"New",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}int ref=rt.allocate(8,4);memory.writeLong(ref,0x687a5353);spatials.put(ref,new Spatial());out(arg(0),ref);ret(NO_ERR);}); // 'hzSS'
			l.register(null,p+"Dispose",(r,c)->{if(spatials.remove(arg(0))==null){ret(PARAM_ERR);return;}memory.free(arg(0));ret(NO_ERR);});
			l.register(null,p+"SetTransform",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null||arg(1)==0){ret(PARAM_ERR);return;}transform(s,floats(arg(1),16));ret(NO_ERR);});
			l.register(null,p+"GetTransform",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloats(arg(1),s.transform);ret(NO_ERR);});
			l.register(null,p+"SetTransformfv",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null||arg(1)==0){ret(PARAM_ERR);return;}transform(s,floats(arg(1),16));ret(NO_ERR);});
			l.register(null,p+"GetTransformfv",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloats(arg(1),s.transform);ret(NO_ERR);});
			for(final String v:new String[]{"Position","Orientation","UpVector","Velocity"}){
				java.util.function.BiConsumer<Spatial,float[]> set=(s,f)->{switch(v){case "Position":s.position=f;s.moved();break;case "Orientation":s.orientation=f;break;case "UpVector":s.up=f;break;default:s.velocity=f;}};
				java.util.function.Function<Spatial,float[]> get=s->{switch(v){case "Position":return s.position;case "Orientation":return s.orientation;case "UpVector":return s.up;default:return s.velocity;}};
				for(String suffix:new String[]{"","fv"}){
					l.register(null,p+"Set"+v+suffix,(r,c)->{Spatial s=spatials.get(arg(0));if(s==null||arg(1)==0){ret(PARAM_ERR);return;}set.accept(s,floats(arg(1),3));ret(NO_ERR);});
					l.register(null,p+"Get"+v+suffix,(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloats(arg(1),get.apply(s));ret(NO_ERR);});
				}
				l.register(null,p+"Set"+v+"3f",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}set.accept(s,new float[]{farg(0),farg(1),farg(2)});ret(NO_ERR);});
			}
			l.register(null,p+"GetActualVelocity",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloats(arg(1),s.actualVelocity);ret(NO_ERR);});
			l.register(null,p+"GetActualVelocityfv",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloats(arg(1),s.actualVelocity);ret(NO_ERR);});
			l.register(null,p+"SetCameraPlacement",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null||arg(1)==0){ret(PARAM_ERR);return;}float[] f=floats(arg(1),9);
				place(s,new float[]{f[0],f[1],f[2]},new float[]{f[3],f[4],f[5]},new float[]{f[6],f[7],f[8]});ret(NO_ERR);});
			l.register(null,p+"GetCameraPlacement",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}int o=arg(1);if(o==0){ret(PARAM_ERR);return;}
				writeFloats(o,s.position);float[] poi={s.position[0]+s.orientation[0],s.position[1]+s.orientation[1],s.position[2]+s.orientation[2]};writeFloats(o+12,poi);writeFloats(o+24,s.up);ret(NO_ERR);});
			l.register(null,p+"SetCameraPlacementfv",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null||arg(1)==0||arg(2)==0||arg(3)==0){ret(PARAM_ERR);return;}place(s,floats(arg(1),3),floats(arg(2),3),floats(arg(3),3));ret(NO_ERR);});
			l.register(null,p+"GetCameraPlacementfv",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}
				writeFloats(arg(1),s.position);writeFloats(arg(2),s.position[0]+s.orientation[0],s.position[1]+s.orientation[1],s.position[2]+s.orientation[2]);writeFloats(arg(3),s.up);ret(NO_ERR);});
		}
		l.register(null,"SSpListener_SetMedium",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}s.medium=arg(1);s.humidity=farg(0);ret(NO_ERR);});
		l.register(null,"SSpListener_GetMedium",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}out(arg(1),s.medium);writeFloat(arg(2),s.humidity);ret(NO_ERR);});
		l.register(null,"SSpListener_SetReverb",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}s.roomSize=farg(0);s.reflectivity=farg(1);s.reverbAttenuation=farg(2);ret(NO_ERR);});
		l.register(null,"SSpListener_GetReverb",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloat(arg(1),s.roomSize);writeFloat(arg(2),s.reflectivity);writeFloat(arg(3),s.reverbAttenuation);ret(NO_ERR);});
		l.register(null,"SSpListener_SetMetersPerUnit",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}float m=farg(0);if(m<=0){ret(PARAM_ERR);return;}s.metersPerUnit=m;ret(NO_ERR);});
		l.register(null,"SSpListener_GetMetersPerUnit",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloat(arg(1),s.metersPerUnit);ret(NO_ERR);});
		l.register(null,"SSpSource_CalcLocalization",(r,c)->{Spatial s=spatials.get(arg(0)),li=spatials.get(arg(1));if(s==null||li==null||arg(2)==0){ret(PARAM_ERR);return;}localize(s,li,arg(2));ret(NO_ERR);});
		l.register(null,"SSpSource_SetCPULoad",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}s.cpuLoad=arg(1);ret(NO_ERR);});
		l.register(null,"SSpSource_GetCPULoad",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}out(arg(1),s.cpuLoad);ret(NO_ERR);});
		l.register(null,"SSpSource_SetMode",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}s.mode=arg(1);ret(NO_ERR);});
		l.register(null,"SSpSource_GetMode",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}out(arg(1),s.mode);ret(NO_ERR);});
		l.register(null,"SSpSource_SetReferenceDistance",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}s.referenceDistance=farg(0);ret(NO_ERR);});
		l.register(null,"SSpSource_GetReferenceDistance",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloat(arg(1),s.referenceDistance);ret(NO_ERR);});
		l.register(null,"SSpSource_SetSize",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}s.length=farg(0);s.width=farg(1);s.height=farg(2);ret(NO_ERR);});
		l.register(null,"SSpSource_GetSize",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloat(arg(1),s.length);writeFloat(arg(2),s.width);writeFloat(arg(3),s.height);ret(NO_ERR);});
		l.register(null,"SSpSource_SetAngularAttenuation",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}s.coneAngle=farg(0);s.coneAttenuation=farg(1);ret(NO_ERR);});
		l.register(null,"SSpSource_GetAngularAttenuation",(r,c)->{Spatial s=spatials.get(arg(0));if(s==null){ret(PARAM_ERR);return;}writeFloat(arg(1),s.coneAngle);writeFloat(arg(2),s.coneAttenuation);ret(NO_ERR);});
	}
}

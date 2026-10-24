import java.util.HashMap;
import java.util.Map;

/**
 * QuickDraw calls CarbonLib 1.1-1.6 added (most of them public only in the later Mac OS X QuickDraw.h), built on QuickDraw68k:
 * <ul>
 * <li>port-relative coordinate conversion (QDGlobalToLocalPoint/Rect/Region and the reverse, each returning its argument),
 *     QDSwapPort, the pattern origin (QDSet/GetPatternOrigin, honoured by QuickDraw68k's pattern fills);</li>
 * <li>port accessors CarbonLib 1.0 left out: text font/face/mode/size setters, the PixPat "Direct" accessors that hand the
 *     port's own Handles over without copying, empty tests of the clip and visible regions and intersections with them, the
 *     printing reference, and SwapPortPic/Region/PolySaveHandle, which suspend and resume an open picture, region or polygon
 *     definition (QuickDraw68k.swapDefinition);</li>
 * <li>NewGWorldFromPtr: a GWorld whose pixels ARE the caller's buffer (QuickDraw68k.newGWorldFromPtr), so drawing into the
 *     world is visible in the caller's memory and the caller's writes are what CopyBits reads;</li>
 * <li>QDSave/Restore/DisposeRegionBits: the current port's pixels under a region kept in a 32-bit GWorld and put back through
 *     the region as a mask;</li>
 * <li>GetPictureFromFile: a PICT file's picture (with or without the 512-byte header) as a PicHandle.</li>
 * </ul>
 * The screen-bits calls (SaveScreenBits and its relatives, CarbonLib-private) answer that bit saving is off: windows here are
 * composited by the host from their own buffers, so there are no screen pixels under a menu or balloon to save, and callers
 * of the Menu/Help Managers' kind fall back to update events, as they do on a Mac whose saving failed.
 */
final class PpcQuickDrawExtras {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcQuickDrawExtras(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	static final int NO_ERR=0,UNIMP_ERR=-4,PARAM_ERR=-50,FNF_ERR=-43,MEM_FULL_ERR=-108,C_DEPTH_ERR=-157;
	/** CGrafPort fields: visRgn, clipRgn, bkPixPat, pnPixPat, fillPixPat, picSave, rgnSave, polySave. */
	private static final int VIS_RGN=24,CLIP_RGN=28,BK_PIX_PAT=32,PN_PIX_PAT=58,FILL_PIX_PAT=62,PIC_SAVE=92,RGN_SAVE=96,POLY_SAVE=100;
	/** A QDRegionBitsRef's tag ('qdrb'): {tag, GWorld, bounds}. */
	private static final int REGION_BITS_TAG=0x71647262;

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	/** The first {@code n} arguments, read before any call that may run PowerPC code and reuse r3-r10. */
	private int[] args(int n) throws M68kCpuException {int[] a=new int[n];for(int i=0;i<n;i++)a[i]=rt.arg(i);return a;}
	private void ret(int v){rt.ret(v);}
	private QuickDraw68k qd(){return rt.toolbox.api.quickDraw();}
	private int carbon(String name,int... a) throws M68kException {return rt.cpu.call(rt.nativeSymbol("CarbonLib",name,Pef.CLASS_TVECTOR,false),a);}
	private interface Body{void run() throws M68kException;}
	private int currentPort() throws M68kException {int slot=rt.allocate(4,4);try{rt.toolbox("GetPort",slot);return memory.readLong(slot);}finally{memory.free(slot);}}
	/** Runs {@code body} with {@code port} current (the current port when 0). */
	private void inPort(int port,Body body) throws M68kException {
		int old=currentPort();if(port==0||port==old){body.run();return;}
		rt.toolbox("SetPort",port);try{body.run();}finally{rt.toolbox("SetPort",old);}
	}
	private void writeRgb(int p,int rgb) throws M68kCpuException {memory.writeWord(p,(rgb>>>16&255)*0x101);memory.writeWord(p+2,(rgb>>>8&255)*0x101);memory.writeWord(p+4,(rgb&255)*0x101);}
	/** Runs {@code body} with black on white in the current port (CopyBits colourizes otherwise), the colours restored afterwards. */
	private void blackOnWhite(Body body) throws M68kException {
		int saved=rt.allocate(12,2),c=rt.allocate(6,2);
		try{rt.toolbox("GetForeColor",saved);rt.toolbox("GetBackColor",saved+6);writeRgb(c,0);rt.toolbox("RGBForeColor",c);writeRgb(c,0xffffff);rt.toolbox("RGBBackColor",c);body.run();}
		finally{rt.toolbox("RGBForeColor",saved);rt.toolbox("RGBBackColor",saved+6);memory.free(c);memory.free(saved);}
	}
	/** A region's bounding box {top, left, bottom, right}, or null when it is empty. */
	private int[] regionBox(int rgn) throws M68kCpuException {
		int p=rgn==0?0:memory.readLong(rgn);if(p==0)return null;
		int[] r={(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6),(short)memory.readWord(p+8)};
		return r[2]<=r[0]||r[3]<=r[1]?null:r;
	}
	/** True when {@code port} has the CGrafPort layout (a colour port whose grafVars is a Handle; NewWindow windows keep a classic GrafPort's portBits.bounds there). */
	private boolean colorLayout(int port) throws M68kCpuException {return port!=0&&(memory.readWord(port+6)&0xc000)==0xc000&&rt.toolbox.api.isHandle(memory.readLong(port+8));}

	/** Get/SetPortPrintingReference: what a printing system attached to a port. */
	private final Map<Integer,Integer> printingReferences=new HashMap<Integer,Integer>();

	void define(PpcLibraries l){
		// ---- coordinates (QuickDraw.h, CarbonLib 1.3): port-relative, each returns its argument
		l.register(null,"QDGlobalToLocalPoint",(r,c)->{int[] a=args(2);if(a[1]!=0)inPort(a[0],()->rt.toolbox("GlobalToLocal",a[1]));ret(a[1]);});
		l.register(null,"QDLocalToGlobalPoint",(r,c)->{int[] a=args(2);if(a[1]!=0)inPort(a[0],()->rt.toolbox("LocalToGlobal",a[1]));ret(a[1]);});
		l.register(null,"QDGlobalToLocalRect",(r,c)->{int[] a=args(2);if(a[1]!=0)inPort(a[0],()->{rt.toolbox("GlobalToLocal",a[1]);rt.toolbox("GlobalToLocal",a[1]+4);});ret(a[1]);});
		l.register(null,"QDLocalToGlobalRect",(r,c)->{int[] a=args(2);if(a[1]!=0)inPort(a[0],()->{rt.toolbox("LocalToGlobal",a[1]);rt.toolbox("LocalToGlobal",a[1]+4);});ret(a[1]);});
		l.register(null,"QDGlobalToLocalRegion",(r,c)->{int[] a=args(2);offsetRegion(a[0],a[1],"GlobalToLocal");ret(a[1]);});
		l.register(null,"QDLocalToGlobalRegion",(r,c)->{int[] a=args(2);offsetRegion(a[0],a[1],"LocalToGlobal");ret(a[1]);});
		l.register(null,"QDSwapPort",(r,c)->{ // (newPort, *oldPort): Boolean, whether the current port changed
			int[] a=args(2);int old=currentPort();if(a[1]!=0)memory.writeLong(a[1],old);
			if(a[0]!=0&&a[0]!=old)rt.toolbox("SetPort",a[0]);ret(a[0]!=0&&a[0]!=old?1:0);});
		l.register(null,"QDSetPatternOrigin",(r,c)->{int pt=arg(0);qd().setPatternOrigin((short)pt,pt>>16);}); // Point by value: v high, h low
		l.register(null,"QDGetPatternOrigin",(r,c)->{int p=arg(0);if(p==0)return;int[] o=qd().patternOrigin();memory.writeWord(p,o[1]);memory.writeWord(p+2,o[0]);});

		// ---- port accessors
		l.register(null,"SetPortTextFont",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("TextFont",(short)a[1]));});
		l.register(null,"SetPortTextFace",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("TextFace",a[1]&0xff));});
		l.register(null,"SetPortTextMode",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("TextMode",(short)a[1]));});
		l.register(null,"SetPortTextSize",(r,c)->{int[] a=args(2);inPort(a[0],()->rt.toolbox("TextSize",(short)a[1]));});
		// the "Direct" PixPat accessors hand over the port's own Handles (no copy): NULL for a classic port, which has Patterns there
		l.register(null,"GetPortPenPixPatDirect",(r,c)->{int port=arg(0);ret(colorLayout(port)?memory.readLong(port+PN_PIX_PAT):0);});
		l.register(null,"GetPortBackPixPatDirect",(r,c)->{int port=arg(0);ret(colorLayout(port)?memory.readLong(port+BK_PIX_PAT):0);});
		l.register(null,"SetPortPenPixPatDirect",(r,c)->{int port=arg(0),pp=arg(1);if(colorLayout(port)&&pp!=0)memory.writeLong(port+PN_PIX_PAT,pp);});
		l.register(null,"SetPortBackPixPatDirect",(r,c)->{int port=arg(0),pp=arg(1);if(colorLayout(port)&&pp!=0)memory.writeLong(port+BK_PIX_PAT,pp);});
		l.register(null,"SetPortFillPixPatDirect",(r,c)->{int port=arg(0),pp=arg(1);if(colorLayout(port)&&pp!=0)memory.writeLong(port+FILL_PIX_PAT,pp);});
		l.register(null,"IsPortVisibleRegionEmpty",(r,c)->{int port=arg(0);int rgn=port==0?0:memory.readLong(port+VIS_RGN);ret(rgn==0||(rt.toolbox("EmptyRgn",rgn)&0xff)!=0?1:0);});
		l.register(null,"IsPortClipRegionEmpty",(r,c)->{int port=arg(0);int rgn=port==0?0:memory.readLong(port+CLIP_RGN);ret(rgn==0||(rt.toolbox("EmptyRgn",rgn)&0xff)!=0?1:0);});
		l.register(null,"SectRegionWithPortClipRegion",(r,c)->{int[] a=args(2);int rgn=a[0]==0?0:memory.readLong(a[0]+CLIP_RGN);if(rgn!=0&&a[1]!=0)rt.toolbox("SectRgn",rgn,a[1],a[1]);});
		l.register(null,"SectRegionWithPortVisibleRegion",(r,c)->{int[] a=args(2);int rgn=a[0]==0?0:memory.readLong(a[0]+VIS_RGN);if(rgn!=0&&a[1]!=0)rt.toolbox("SectRgn",rgn,a[1],a[1]);});
		l.register(null,"GetPortPrintingReference",(r,c)->{Integer v=printingReferences.get(arg(0));ret(v==null?0:v);});
		l.register(null,"SetPortPrintingReference",(r,c)->{int port=arg(0),ref=arg(1);if(port==0)return;if(ref==0)printingReferences.remove(port);else printingReferences.put(port,ref);});
		l.register(null,"SwapPortPicSaveHandle",(r,c)->{int[] a=args(2);ret(swapSave(a[0],0,PIC_SAVE,a[1]));});
		l.register(null,"SwapPortRegionSaveHandle",(r,c)->{int[] a=args(2);ret(swapSave(a[0],1,RGN_SAVE,a[1]));});
		l.register(null,"SwapPortPolySaveHandle",(r,c)->{int[] a=args(2);ret(swapSave(a[0],2,POLY_SAVE,a[1]));});

		// ---- offscreen worlds and saved bits
		l.register(null,"NewGWorldFromPtr",(r,c)->{int[] a=args(8);ret(newGWorldFromPtr(a));});
		l.register(null,"QDSaveRegionBits",(r,c)->{int[] a=args(2);ret(saveRegionBits(a[0],a[1]));});
		l.register(null,"QDRestoreRegionBits",(r,c)->{int[] a=args(2);ret(restoreRegionBits(a[0],a[1]));});
		l.register(null,"QDDisposeRegionBits",(r,c)->{int bits=arg(0);if(!isRegionBits(bits)){ret(PARAM_ERR);return;}disposeRegionBits(bits);ret(NO_ERR);});
		// Screen bit saving (CarbonLib-private, inferred: SaveScreenBits(const Rect*, SavedScreenBitsRef*) and the ref-taking rest): off -
		// the host composites the windows from their own buffers, so nothing on a screen needs saving; callers redraw through updates
		l.register(null,"IsScreenBitSavingEnabled",(r,c)->ret(0));
		l.register(null,"SetScreenBitSavingEnabled",(r,c)->{}); // (Boolean): turning it on changes nothing - there is still no screen to save
		l.register(null,"SaveScreenBits",(r,c)->{int out=arg(1);if(out!=0&&(out&1)==0)memory.writeLong(out,0);ret(UNIMP_ERR);});
		l.register(null,"RestoreScreenBits",(r,c)->ret(PARAM_ERR)); // no SaveScreenBits ever succeeds, so no ref is valid
		l.register(null,"DisposeSavedScreenBits",(r,c)->ret(arg(0)==0?NO_ERR:PARAM_ERR));
		l.register(null,"MoveSavedScreenBits",(r,c)->ret(PARAM_ERR));
		l.register(null,"GetSavedScreenBitsRepresentativePixMap",(r,c)->ret(0));

		// ---- pictures
		l.register(null,"GetPictureFromFile",(r,c)->{int[] a=args(2);ret(pictureFromFile(a[0],a[1]));}); // (const FSSpec*, PicHandle*) - inferred
	}

	/** Offsets a region by the difference between {@code port}'s local and global coordinates (the conversion of (0,0)). */
	private void offsetRegion(int port,int rgn,String conversion) throws M68kException {
		if(rgn==0)return;int pt=rt.allocate(4,2);
		try{memory.writeLong(pt,0);inPort(port,()->rt.toolbox(conversion,pt));int dv=(short)memory.readWord(pt),dh=(short)memory.readWord(pt+2);if(dv!=0||dh!=0)rt.toolbox("OffsetRgn",rgn,dh,dv);}
		finally{memory.free(pt);}
	}

	/** SwapPort...SaveHandle: the definition open in the port is suspended and its token returned; a token from before resumes,
	 *  anything else is kept in the port's field as the program's own (and returned by the next swap). */
	private int swapSave(int port,int kind,int field,int handle) throws M68kException {
		if(port==0)return 0;QuickDraw68k q=qd();boolean resume=q.isSuspendedDefinition(handle);int before=memory.readLong(port+field);
		int old=q.swapDefinition(port,kind,handle);if(old==0)old=before;
		if(!(resume&&kind==0))memory.writeLong(port+field,resume?0:handle); // a resumed picture is in picSave again: drawing records through it
		return old;
	}

	/** NewGWorldFromPtr(*world, pixelFormat, bounds, cTable, aGDevice, flags, buffer, rowBytes): the classic depths 1-32 (and the
	 *  indexed grey formats, which take the grey table); the FourCC formats of Mac OS X are not QuickDraw depths here (cDepthErr). */
	private int newGWorldFromPtr(int[] a) throws M68kException {
		int out=a[0],format=a[1],table=a[3];if(out==0)return PARAM_ERR;memory.writeLong(out,0);
		int depth;
		if(format==1||format==2||format==4||format==8||format==16||format==32)depth=format;
		else if(format==0x21||format==0x22||format==0x24||format==0x28){depth=format-0x20;if(table==0)table=rt.toolbox("GetCTable",32+depth);} // k1/2/4/8IndexedGrayPixelFormat
		else return C_DEPTH_ERR;
		return (short)qd().newGWorldFromPtr(out,depth,a[2],table,a[4],a[5],a[6],a[7]);
	}

	// ------------------------------------------------------------------ region bits

	private boolean isRegionBits(int p) throws M68kCpuException {return p!=0&&(p&1)==0&&memory.isAllocated(p)&&memory.readLong(p)==REGION_BITS_TAG;}
	/** QDSaveRegionBits(region, *bits): the current port's pixels inside the region's bounding box, copied into a 32-bit GWorld. */
	private int saveRegionBits(int rgn,int out) throws M68kException {
		if(rgn==0||out==0)return PARAM_ERR;memory.writeLong(out,0);
		int port=currentPort();if(port==0)return PARAM_ERR;
		int rec=rt.allocate(16,4);memory.writeLong(rec,REGION_BITS_TAG);memory.writeLong(rec+4,0);int[] box=regionBox(rgn);
		if(box!=null){
			for(int i=0;i<4;i++)memory.writeWord(rec+8+2*i,box[i]);
			int slot=rt.allocate(4,4);int err;
			try{err=(short)rt.toolbox("NewGWorld",slot,32,rec+8,0,0,0);if(err==0)memory.writeLong(rec+4,memory.readLong(slot));}finally{memory.free(slot);}
			if(err!=0){memory.writeLong(rec,0);memory.free(rec);return err;}
			int world=memory.readLong(rec+4);
			withGWorld(world,()->blackOnWhite(()->rt.toolbox("CopyBits",port+2,world+2,rec+8,rec+8,0,0)));
		}
		memory.writeLong(out,rec);return NO_ERR;
	}
	/** QDRestoreRegionBits(region, bits): the saved pixels back into the current port, masked by the region. */
	private int restoreRegionBits(int rgn,int bits) throws M68kException {
		if(!isRegionBits(bits))return PARAM_ERR;int world=memory.readLong(bits+4);if(world==0)return NO_ERR; // an empty region saved nothing
		int port=currentPort();if(port==0)return PARAM_ERR;
		blackOnWhite(()->rt.toolbox("CopyBits",world+2,port+2,bits+8,bits+8,0,rgn));return NO_ERR;
	}
	private void disposeRegionBits(int bits) throws M68kException {int world=memory.readLong(bits+4);if(world!=0)rt.toolbox("DisposeGWorld",world);memory.writeLong(bits,0);memory.free(bits);}
	/** Runs {@code body} with {@code world} as the current GWorld, the previous port and device restored afterwards. */
	private void withGWorld(int world,Body body) throws M68kException {
		int slot=rt.allocate(8,4);
		try{rt.toolbox("GetGWorld",slot,slot+4);rt.toolbox("SetGWorld",world,0);try{body.run();}finally{rt.toolbox("SetGWorld",memory.readLong(slot),memory.readLong(slot+4));}}
		finally{memory.free(slot);}
	}

	// ------------------------------------------------------------------ pictures

	/** The data fork of the file an FSSpec names (null when it cannot be opened; {@code err[0]} says why). */
	byte[] dataFork(int spec,int[] err) throws M68kException {
		int ref=rt.allocate(4,4);
		try{
			int e=(short)rt.toolbox("FSpOpenDF",spec,1,ref);if(e!=0){err[0]=e;return null;} // fsRdPerm
			int refNum=(short)memory.readWord(ref);
			try{int eof=rt.allocate(4,4);
				try{e=(short)carbon("GetEOF",refNum,eof);int n=e==0?memory.readLong(eof):0;if(e!=0||n<0||n>64<<20){err[0]=e!=0?e:MEM_FULL_ERR;return null;}
					int buf=rt.allocate(Math.max(1,n),4);
					try{memory.writeLong(eof,n);e=(short)carbon("FSRead",refNum,eof,buf);if(e!=0&&e!=-39){err[0]=e;return null;}return memory.readBytes(buf,memory.readLong(eof));}
					finally{memory.free(buf);}}
				finally{memory.free(eof);}}
			finally{carbon("FSClose",refNum);}
		}finally{memory.free(ref);}
	}
	/** Offset of a picture in a PICT file's data: 512 after the application header, 0 for a bare picture, -1 for neither. */
	static int pictureOffset(byte[] d){
		for(int at:new int[]{512,0}){
			if(d.length<at+12)continue;
			int top=(short)((d[at+2]&255)<<8|d[at+3]&255),left=(short)((d[at+4]&255)<<8|d[at+5]&255),bottom=(short)((d[at+6]&255)<<8|d[at+7]&255),right=(short)((d[at+8]&255)<<8|d[at+9]&255);
			boolean v1=(d[at+10]&255)==0x11&&(d[at+11]&255)==0x01,v2=d.length>=at+14&&(d[at+10]&255)==0x00&&(d[at+11]&255)==0x11&&(d[at+12]&255)==0x02&&(d[at+13]&255)==0xff;
			if((v1||v2)&&bottom>top&&right>left)return at;
		}
		return -1;
	}
	private int pictureFromFile(int spec,int out) throws M68kException {
		if(out!=0)memory.writeLong(out,0);if(spec==0||out==0)return PARAM_ERR;
		int[] err={0};byte[] d=dataFork(spec,err);if(d==null)return err[0];
		int at=pictureOffset(d);if(at<0)return PARAM_ERR; // not a PICT file
		byte[] pic=java.util.Arrays.copyOfRange(d,at,d.length);
		memory.writeLong(out,rt.toolbox.api.createHandle(pic));return NO_ERR;
	}
}

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * QuickDraw additions for hosted applications: proportional host-font text
 * (with Japanese support), the Font Manager, icons, palettes, colour cursors
 * bound to the host cursor, window-aware Local/Global conversion and a Java2D
 * fast path for 32-bit ports that the control, dialog and TextEdit code use.
 */
final class HostQuickDraw68k {
	private int maskTable; // GetMaskTable's tables, built on first use
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final QuickDraw68k qd;
	private final QuickTimeMath68k quickTimeMath;
	/** Video driver Control (status=false) / Status calls for the main screen; see QuickDraw68k.videoDriver. */
	int videoDriver(int pb,boolean status) throws M68kCpuException {return qd.videoDriver(pb,status);}
	private final Map<Integer,byte[]> cursors=new HashMap<Integer,byte[]>();
	private int currentCursorHandle;
	private final Map<Integer,Palette> palettes=new HashMap<Integer,Palette>();
	/** Window -> Palette Handle; the key -1 is the application's default palette (SetPalette((WindowPtr)-1, ...), IM Palette Manager),
	 *  which every window without a palette of its own activates. */
	private final Map<Integer,Integer> windowPalettes=new HashMap<Integer,Integer>();
	/** Palette Handle -> its update flags (SetPalette's cUpdates, NSetPalette/SetPaletteUpdates): UPDATES_FG, UPDATES_BK. */
	private final Map<Integer,Integer> paletteUpdates=new HashMap<Integer,Integer>();
	private static final int UPDATES_FG=2,UPDATES_BK=1;
	/** Update flags from a SetPalette cUpdates word: a Pascal Boolean (TRUE = all updates), or the pmNoUpdates..pmAllUpdates
	 *  constants ($8000 plus pmFgUpdates $4000 / pmBkUpdates $2000) that C programs pass through the same slot. */
	private static int updateFlags(int word){
		if((word&0x8000)!=0)return ((word&0x4000)!=0?UPDATES_FG:0)|((word&0x2000)!=0?UPDATES_BK:0);
		return (word>>>8)!=0||word==1?UPDATES_FG|UPDATES_BK:0; // the Boolean's byte is the high one; a C int TRUE is 1
	}
	private int fontLock;
	private int fmOutput;

	static final class Palette {int[] rgb;int[] usage;int[] tolerance;Palette(int n){rgb=new int[n];usage=new int[n];tolerance=new int[n];}}

	HostQuickDraw68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;qd=tb.api.quickDraw();quickTimeMath=new QuickTimeMath68k(memory);}

	boolean implemented(int trap){
		switch(trap){
			case 0x883:case 0x884:case 0x885:case 0x88c:case 0x88d:case 0x886:case 0x88b:case 0x837:case 0x8ed:case 0x882:case 0xa23:
			case 0x8fe:case 0x8ff:case 0x900:case 0x902:case 0x903:case 0x901:case 0x835:case 0x834:case 0x814:case 0x854:
			case 0x870:case 0x871:case 0x8bd:case 0x865:case 0x866:case 0x8f8:case 0x8f9:case 0x8fa:case 0x8fb:case 0x8fc:case 0x8c3:
			case 0x8ea:case 0xa4e:case 0x836:case 0x8f2:case 0xa20:case 0x8d7:case 0xbca:case 0xbc2:case 0x872:case 0xa49:case 0xa4a:
			case 0x94b:case 0xa1f:case 0xa1e:case 0xa25:case 0xbc9:case 0xa1b:case 0xa1c:case 0xa26:case 0xa1d:
			case 0xa90:case 0xa91:case 0xa92:case 0xa93:case 0xa94:case 0xa95:case 0xa96:case 0xa97:case 0xa98:case 0xa99:case 0xa9a:case 0xa9b:case 0xa9c:case 0xa9d:case 0xa9e:case 0xa9f:case 0xaa0:case 0xaa1:
			case 0x82e:case 0x831:case 0xa47:return true;
			case 0x890:case 0x8a0:case 0x8af:case 0x8b6:case 0x8c5:case 0x8d1:case 0x8eb:case 0x8ee:case 0x8f0:case 0x8f1:case 0xbf8:return true; // standard bottleneck procedures
			default:return false;
		}
	}

	/**
	 * QDColors ($8B0): the eight QuickDraw colours as ColorSpecs {value, red, green, blue} in the order black, yellow, magenta, red,
	 * cyan, green, blue, white (the RGB of the 'clut' 4 entries). Externals look a colour name up there: Rinaldi's -oid windows
	 * read (QDColors)+8*index+2, and with -1 there "blue,yellow" came out magenta on cyan.
	 */
	static int standardColors(M68kMemory memory) throws M68kCpuException {
		int[][] rgb={{0,0,0},{0xfc00,0xf37d,0x052f},{0xf2d7,0x0856,0x84ec},{0xdd6b,0x08c2,0x06a2},{0x0241,0xab54,0xeaff},{0x0000,0x8000,0x11b0},{0x0000,0x0000,0xd400},{0xffff,0xffff,0xffff}};
		int table=memory.allocate(8*8,2);
		for(int i=0;i<8;i++){memory.writeWord(table+i*8,i);memory.writeWord(table+i*8+2,rgb[i][0]);memory.writeWord(table+i*8+4,rgb[i][1]);memory.writeWord(table+i*8+6,rgb[i][2]);}
		return table;
	}

	void initializeLowMemory() throws M68kCpuException {
		int[] screen=tb.api.quickDraw().screenBounds(); // the screen programs see, not necessarily the whole host display
		memory.writeLong(0x9ee,tb.api.createHandle(regionBytes(screen[0]+MacAppHost.MENU_BAR_HEIGHT,screen[1],screen[2],screen[3])));// GrayRgn
		int colors=memory.readLong(0x8b0);if(colors==0||colors==0xffffffff)memory.writeLong(0x8b0,standardColors(memory));
		// MainDevice, DeviceList and TheGDevice: programs read the screen device through these too.
		int device=mainDevice();memory.writeLong(0x8a4,device);memory.writeLong(0x8a8,device);memory.writeLong(0xcc8,device);
		// ScrnBase: the main screen's pixels (baseAddr of gdPMap), where programs of the Mac Plus era write the screen directly
		int data=device==0?0:memory.readLong(device),map=data==0?0:memory.readLong(data+22),mapData=map==0?0:memory.readLong(map);
		if(mapData!=0)memory.writeLong(0x824,qd.guestScreenBase(memory.readLong(mapData)));
	}
	static byte[] regionBytes(int top,int left,int bottom,int right){byte[] r=new byte[10];r[0]=0;r[1]=10;r[2]=(byte)(top>>8);r[3]=(byte)top;r[4]=(byte)(left>>8);r[5]=(byte)left;r[6]=(byte)(bottom>>8);r[7]=(byte)bottom;r[8]=(byte)(right>>8);r[9]=(byte)right;return r;}

	/** The ROM's StdTxMeas leaves the width as a Fixed in the QuickDraw globals (thePort-172, fontAdj); Cyberdog 2.0's HTML layout
	 *  reads it there instead of the INTEGER result (every style run of a line was drawn at the line's start). */
	private void fontAdj(int width) throws M68kCpuException {int g=qd.globalsPointer();if(g>172)memory.writeLong(g-172,width<<16);}
	boolean handle(M68kCpu cpu,int trap) throws M68kException {
		if(bottleneck(cpu,trap))return true;
		// Measuring and drawing text swaps the port's font in on a real Mac (FMSwapFont inside StdTxMeas/StdText), which leaves
		// WidthTabHandle/WidthPtr describing it. Word 5.1 calls CharWidth(' ') and then reads every glyph width from that table:
		// with no table it read low memory (letters 0 wide, digits far too wide: every word with a digit wrapped).
		if(trap==0x883||trap==0x884||trap==0x885||trap==0x886||trap==0x88b||trap==0x88c||trap==0x88d||trap==0x8ed)currentWidthTable();
		switch(trap){
			case 0x883:{int c=memory.readWord(cpu.a[7])&0xff;cpu.a[7]+=2;qd.plainGlyphs=qd.isGWorld(tb.api.currentQuickDrawPort());try{drawBytes(new byte[]{(byte)c});}finally{qd.plainGlyphs=false;}return true;}
			case 0x884:{int s=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(s!=0)drawBytes(memory.readBytes(s+1,memory.readByte(s)));return true;}
			case 0x885:{int count=(short)memory.readWord(cpu.a[7]),first=(short)memory.readWord(cpu.a[7]+2),text=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;if(count>0)drawBytes(memory.readBytes(text+first,count));return true;}
			case 0x882:{ // StdText(byteCount, textAddr, numer, denom): scaled text (DrawPicture into a larger frame) at the size numer.v/denom.v gives
				int denom=memory.readLong(cpu.a[7]),numer=memory.readLong(cpu.a[7]+4),count=(short)memory.readWord(cpu.a[7]+12),text=memory.readLong(cpu.a[7]+8);cpu.a[7]+=14;
				if(count<=0||text==0)return true;
				int nv=(short)(numer>>16),dv=(short)(denom>>16),port=qd.currentPortAddress();
				if(port==0||nv<=0||dv<=0||nv==dv){drawBytes(memory.readBytes(text,count));return true;}
				int savedSize=memory.readWord(port+74),size=(short)savedSize<=0?12:(short)savedSize;
				memory.writeWord(port+74,Math.max(1,Math.round(size*(float)nv/dv)));
				try{drawBytes(memory.readBytes(text,count));}finally{memory.writeWord(port+74,savedSize);}
				return true;}
			case 0x88c:{int s=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int w=s==0?0:extraWidth(memory.readBytes(s+1,memory.readByte(s)));memory.writeWord(cpu.a[7],w);if(Mac68kApi.TRACE)System.err.println("[68K] StringWidth \""+(s==0?"":new String(memory.readBytes(s+1,memory.readByte(s)),java.nio.charset.StandardCharsets.ISO_8859_1))+"\" font "+qd.textState()[0]+" -> "+w);return true;}
			case 0x88d:{int c=memory.readWord(cpu.a[7])&0xff;cpu.a[7]+=2;int w=extraWidth(new byte[]{(byte)c});memory.writeWord(cpu.a[7],w);if(Mac68kApi.TRACE)System.err.println("[68K] CharWidth $"+Integer.toHexString(c)+" font "+qd.textState()[0]+" -> "+w);return true;}
			case 0x886:{int count=(short)memory.readWord(cpu.a[7]),first=(short)memory.readWord(cpu.a[7]+2),text=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;int w=count<=0?0:extraWidth(memory.readBytes(text+first,count));fontAdj(w);memory.writeWord(cpu.a[7],w);return true;}
			case 0x8ed:{ // StdTxMeas(byteCount, textAddr, VAR numer, VAR denom, VAR info): INTEGER - unscaled text, the FontInfo of the port's font
				int count=(short)memory.readWord(cpu.a[7]+16),text=memory.readLong(cpu.a[7]+12),info=memory.readLong(cpu.a[7]);cpu.a[7]+=18;
				if(info!=0){int[] fi=Fonts68k.fontInfo(currentFont());memory.writeWord(info,fi[0]);memory.writeWord(info+2,fi[1]);memory.writeWord(info+4,fi[2]);memory.writeWord(info+6,fi[3]);}
				int w=count<=0||text==0?0:width(memory.readBytes(text,count));fontAdj(w);memory.writeWord(cpu.a[7],w);return true;}
			case 0x88b:{int info=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(info!=0){int[] fi=Fonts68k.fontInfo(currentFont());memory.writeWord(info,fi[0]);memory.writeWord(info+2,fi[1]);memory.writeWord(info+4,fi[2]);memory.writeWord(info+6,fi[3]);}return true;}
			case 0x837:{int locs=memory.readLong(cpu.a[7]),text=memory.readLong(cpu.a[7]+4),count=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;measureRun(text,count,locs);return true;}
			case 0xa23:{int extra=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int port=qd.currentPortAddress();if(port!=0&&qd.colorPort(port))memory.writeWord(port+CH_EXTRA,Math.max(-32768,Math.min(32767,extra>>4)));return true;} // CharExtra(extra): chExtra (4.12) of a colour port
			case 0x8fe:return true;
			case 0x8ff:{int name=memory.readLong(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+4);cpu.a[7]+=6; // GetFontName
				// a Japanese System's system and application fonts are its Japanese family, as FontScript says: REALbasic converts
				// text to the encoding of UpgradeScriptInfoToTextEncoding(font name) before drawing, and "Chicago" turned kanji into "?"
				if((id==0||id==1)&&tb.encoding==StackTextEncoding.JAPANESE)id=Fonts68k.JAPANESE_SYSTEM_FONT;
				tb.writePascal(name,Fonts68k.nameOf(id),255);return true;}
			case 0x900:{int idPtr=memory.readLong(cpu.a[7]),name=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeWord(idPtr,Fonts68k.numberOf(tb.readPascal(name)));return true;}
			case 0x902:{int size=(short)memory.readWord(cpu.a[7]),id=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;tb.writeBoolean(cpu.a[7],Fonts68k.realFont(id,size));return true;} // RealFont(fontNum, size)
			case 0x903:cpu.a[7]+=2;return true;
			case 0x901:return fmSwapFont(cpu);
			case 0x835:{int rec=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int[] t=qd.textState();Font font=Fonts68k.font(t[0],t[3],t[1]);int[] fi=Fonts68k.fontInfo(font);memory.writeLong(rec,fi[0]<<16);memory.writeLong(rec+4,fi[1]<<16);memory.writeLong(rec+8,fi[3]<<16);memory.writeLong(rec+12,fi[2]<<16);memory.writeLong(rec+16,widthTable(font,t[0],t[3],t[1]));return true;} // FontMetrics(VAR theMetrics): ascent, descent, leading, widMax (Fixed) and the width table
			case 0x834:Fonts68k.scaleDisabled=memory.readByte(cpu.a[7])!=0;cpu.a[7]+=2;return true; // SetFScaleDisable: nearest strike at the requested spacing instead of scaled pixels
			case 0x814:memory.writeByte(0xbf4,memory.readByte(cpu.a[7])!=0?0xff:0);cpu.a[7]+=2;return true; // SetFractEnable: FractEnable ($BF4), which programs read back
			case 0x854:return fontDispatch(cpu);
			case 0x870:case 0x871:{int pt=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(pt!=0){int v=(short)memory.readWord(pt),h=(short)memory.readWord(pt+2);int[] r=trap==0x870?tb.windows.localToGlobal(h,v):tb.windows.globalToLocal(h,v);memory.writeWord(pt,r[1]);memory.writeWord(pt+2,r[0]);
				if(Mac68kApi.TRACE)System.err.println("[68K] "+(trap==0x870?"LocalToGlobal ":"GlobalToLocal ")+h+","+v+" -> "+r[0]+","+r[1]);}return true;}
			case 0x890:{int point=memory.readLong(cpu.a[7]);cpu.a[7]+=4;int base=cpu.a[7];cpu.pushWord(point);cpu.pushWord(point>>16);qd.handle(cpu,0x891);cpu.a[7]=base;return true;} // StdLine(newPt)
			case 0x8a0:return stdShape(cpu,0x8a1,0x8a5,0);   // StdRect(verb, r)
			case 0x8af:return stdShape(cpu,0x8b0,0x8b4,2);   // StdRRect(verb, r, ovalWidth, ovalHeight)
			case 0x8b6:return stdShape(cpu,0x8b7,0x8bb,0);   // StdOval(verb, r)
			case 0x8bd:return stdShape(cpu,0x8be,0x8c2,2);   // StdArc(verb, r, startAngle, arcAngle)
			case 0x8c5:return stdShape(cpu,0x8c6,0x8ca,0);   // StdPoly(verb, poly)
			case 0x8d1:return stdShape(cpu,0x8d2,0x8d6,0);   // StdRgn(verb, rgn)
			case 0x8eb:{ // StdBits(srcBits, srcRect, dstRect, mode, maskRgn): CopyBits into the current port
				int sp=cpu.a[7],mask=memory.readLong(sp),mode=memory.readWord(sp+4),dstRect=memory.readLong(sp+6),srcRect=memory.readLong(sp+10),srcBits=memory.readLong(sp+14);cpu.a[7]+=18;
				int port=qd.currentPortAddress();if(port==0)return true;int base=cpu.a[7];
				cpu.pushLong(srcBits);cpu.pushLong(port+2);cpu.pushLong(srcRect);cpu.pushLong(dstRect);cpu.pushWord(mode);cpu.pushLong(mask);qd.handle(cpu,0x8ec);cpu.a[7]=base;return true;}
			case 0x8f1:{int handle=memory.readLong(cpu.a[7]),size=(short)memory.readWord(cpu.a[7]+4),kind=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;qd.recordPictureComment(kind,commentData(handle,size));return true;} // StdComment
			case 0x8f0:{int count=(short)memory.readWord(cpu.a[7]),data=memory.readLong(cpu.a[7]+2);cpu.a[7]+=6;if(count>0&&data!=0)qd.appendPictureBytes(memory.readBytes(data,count));return true;} // StdPutPic(dataPtr, byteCount)
			case 0x8ee:cpu.a[7]+=6;return true;              // StdGetPic(dataPtr, byteCount): pictures are played from their Handle, nothing to fetch
			case 0xbf8:cpu.a[7]+=12;return true;             // StdOpcode(fromRect, toRect, opcode, version): unknown opcodes draw nothing
			case 0x865:{int v=(short)memory.readWord(cpu.a[7]),h=(short)memory.readWord(cpu.a[7]+2);cpu.a[7]+=4;tb.writeBoolean(cpu.a[7],pixelIsBlack(h,v));return true;}
			case 0x866:{int s=memory.readLong(cpu.a[7]),dst=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;String hex=tb.readPascal(s);for(int i=0;i+1<hex.length();i+=2){try{memory.writeByte(dst+i/2,Integer.parseInt(hex.substring(i,i+2),16));}catch(NumberFormatException e){}}return true;}
			case 0x8f8:case 0x8f9:{int dst=memory.readLong(cpu.a[7]),src=memory.readLong(cpu.a[7]+4),pt=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;mapPoint(pt,src,dst,trap==0x8f8);return true;}
			case 0x8fa:{int dst=memory.readLong(cpu.a[7]),src=memory.readLong(cpu.a[7]+4),r=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;mapPoint(r,src,dst,false);mapPoint(r+4,src,dst,false);return true;}
			case 0x8fb:return mapRegion(cpu);
			case 0x8fc:{int dst=memory.readLong(cpu.a[7]),src=memory.readLong(cpu.a[7]+4),poly=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;int data=poly==0?0:memory.readLong(poly);if(data!=0){int size=memory.readWord(data);mapPoint(data+2,src,dst,false);mapPoint(data+6,src,dst,false);for(int o=10;o+4<=size;o+=4)mapPoint(data+o,src,dst,false);}return true;}
			case 0x8c3:{ // PtToAngle(r: Rect; pt: Point; VAR angle: INTEGER): the Point is passed by value (it was read as a pointer:
				// AQUAZONE's fish got no heading towards their targets and stayed in a corner)
				int anglePtr=memory.readLong(cpu.a[7]),pt=memory.readLong(cpu.a[7]+4),r=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
				memory.writeWord(anglePtr,ptToAngle(r,(short)(pt>>>16),(short)pt));return true;}
			case 0x8ea:{int procs=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(procs!=0)for(int i=0;i<13;i++)memory.writeLong(procs+i*4,tb.cpu.trapRomAddress(stdProcTrap(i)));return true;}
			case 0xa4e:{int procs=memory.readLong(cpu.a[7]);cpu.a[7]+=4;if(procs!=0){for(int i=0;i<14;i++)memory.writeLong(procs+i*4,tb.cpu.trapRomAddress(stdProcTrap(i)));for(int i=14;i<20;i++)memory.writeLong(procs+i*4,0);}return true;} // SetStdCProcs: CQDProcs adds opcodeProc and six reserved entries
			case 0x836:{ // GetMaskTable: register call, A0 = the ROM's LeftMask[17], RightMask[17], BitMask[16] word tables (fast blitters);
				// it used to write a NIL onto the caller's stack
				if(maskTable==0){maskTable=memory.allocate(50*2,2);
					for(int i=0;i<=16;i++){memory.writeWord(maskTable+2*i,(0xffff0000>>>i)&0xffff);memory.writeWord(maskTable+34+2*i,0xffff>>>i);}
					for(int i=0;i<16;i++)memory.writeWord(maskTable+68+2*i,0x8000>>>i);}
				cpu.a[0]=maskTable;return true;}
			case 0x8f2:{int handle=memory.readLong(cpu.a[7]),size=(short)memory.readWord(cpu.a[7]+4),kind=(short)memory.readWord(cpu.a[7]+6);cpu.a[7]+=8;qd.recordPictureComment(kind,commentData(handle,size));return true;} // PicComment(kind, dataSize, dataHandle)
			case 0xa20:return qd.openColorPicture(cpu); // OpenCPicture(&OpenCPicParams): srcRect starts the record, so the parameter block pointer is OpenPicture's rect pointer (it passed the rect's first long - NIL for a (0,0,..) rect: Strata StudioPro 1.1 found the picFrame empty and gave up saving "for lack of memory")
			case 0x8d7:{int bits=memory.readLong(cpu.a[7]),rgn=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;memory.writeWord(cpu.a[7],qd.bitMapToRegion(rgn,bits));return true;} // BitMapToRegion(region, bitMap): OSErr
			case 0xbca:return deviceLoop(cpu);
			case 0xbc2:return quickTimeMath.handle(cpu); // _QuickTimeMath (D0 = selector)
			case 0x872:cpu.a[7]+=2;return true;
			case 0xa49:case 0xa4a:return qd.handle(cpu,trap); // SaveEntries/RestoreEntries (procedures: nothing is returned on the stack)
			case 0x94b:{int icon=memory.readLong(cpu.a[7]),rect=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;qd.chargeIcon(cpu,rect);plotIcon(icon,rect,false);return true;}
			case 0xa1f:{int icon=memory.readLong(cpu.a[7]),rect=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;qd.chargeIcon(cpu,rect);plotIcon(icon,rect,true);return true;}
			case 0xa1e:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],getCIcon(id));return true;}
			case 0xa25:{int h=memory.readLong(cpu.a[7]);cpu.a[7]+=4;disposeCIcon(h);return true;}
			case 0xbc9:return iconDispatch(cpu);
			case 0xa1b:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;int h=tb.resources.detachedResource(0x63727372,id);memory.writeLong(cpu.a[7],h);return true;} // GetCCursor('crsr')
			case 0xa1c:{int h=memory.readLong(cpu.a[7]);cpu.a[7]+=4;setColorCursor(h);return true;}
			case 0xa26:{int h=memory.readLong(cpu.a[7]);cpu.a[7]+=4;tb.api.disposeHandleAndFree(h);return true;}
			case 0xa1d:return true;
			case 0xa90:return true;
			case 0xa91:return newPalette(cpu);
			case 0xa92:{int id=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;int res=tb.resources.detachedResource(0x706c7474,id);memory.writeLong(cpu.a[7],res==0?0:paletteFromResource(res));return true;}
			case 0xa93:{int p=memory.readLong(cpu.a[7]);cpu.a[7]+=4;palettes.remove(p);tb.api.disposeHandleAndFree(p);return true;}
			case 0xa94:{int w=memory.readLong(cpu.a[7]);cpu.a[7]+=4;activatePalette(w);return true;} // ActivatePalette
			case 0xa95:{int updates=memory.readWord(cpu.a[7]),p=memory.readLong(cpu.a[7]+2),w=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;paletteUpdates.put(p,updateFlags(updates));setWindowPalette(w,p);return true;} // SetPalette(window, palette, cUpdates)
			case 0xa96:{int w=memory.readLong(cpu.a[7]);cpu.a[7]+=4;Integer p=windowPalette(w);memory.writeLong(cpu.a[7],p==null?0:p);return true;} // GetPalette
			case 0xa97:case 0xa98:{int entry=(short)memory.readWord(cpu.a[7]);cpu.a[7]+=2;pmColor(cpu,entry,trap==0xa97);return true;}
			case 0xa99:{int rgb=memory.readLong(cpu.a[7]),entry=(short)memory.readWord(cpu.a[7]+4),w=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Integer p=windowPalettes.get(w);Palette pal=p==null?null:palettes.get(p); // AnimateEntry
				if(pal!=null&&entry>=0&&entry<pal.rgb.length){pal.rgb[entry]=readRgb(rgb);writePaletteRecord(p,pal);animatePalette(w,pal);}return true;}
			case 0xa9a:{ // AnimatePalette(dstWindow, srcCTab, srcIndex, dstEntry, dstLength): the palette's colours change and, while it is the
				// active one, so do the screen's - some games fade their title this way (a flat block without it)
				int length=(short)memory.readWord(cpu.a[7]),dstEntry=(short)memory.readWord(cpu.a[7]+2),srcIndex=(short)memory.readWord(cpu.a[7]+4),table=memory.readLong(cpu.a[7]+6),w=memory.readLong(cpu.a[7]+10);cpu.a[7]+=14;
				Integer p=windowPalettes.get(w);Palette pal=p==null?null:palettes.get(p);int data=table==0?0:memory.readLong(table);if(pal==null||data==0)return true;
				int count=(short)memory.readWord(data+6)+1;
				for(int i=0;i<length;i++){int a=srcIndex+i,b=dstEntry+i;if(a<0||b<0||a>=count||b>=pal.rgb.length)break;pal.rgb[b]=readRgb(data+8+a*8+2);}
				writePaletteRecord(p,pal);animatePalette(w,pal);return true;}
			case 0xa9b:{int rgb=memory.readLong(cpu.a[7]),entry=(short)memory.readWord(cpu.a[7]+4),p=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Palette pal=palettes.get(p);writeRgb(rgb,pal!=null&&entry>=0&&entry<pal.rgb.length?pal.rgb[entry]:0);return true;}
			case 0xa9c:{int rgb=memory.readLong(cpu.a[7]),entry=(short)memory.readWord(cpu.a[7]+4),p=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Palette pal=palettes.get(p);if(pal!=null&&entry>=0&&entry<pal.rgb.length){pal.rgb[entry]=readRgb(rgb);writePaletteRecord(p,pal);}return true;}
			case 0xa9d:{int tol=memory.readLong(cpu.a[7]),usage=memory.readLong(cpu.a[7]+4),entry=(short)memory.readWord(cpu.a[7]+8),p=memory.readLong(cpu.a[7]+10);cpu.a[7]+=14;Palette pal=palettes.get(p);if(usage!=0)memory.writeWord(usage,pal!=null&&entry<pal.usage.length?pal.usage[entry]:0);if(tol!=0)memory.writeWord(tol,0);return true;}
			// Palette Manager argument sizes (IM: Palette Manager): SetEntryUsage 10, CTab2Palette 12, Palette2CTab 8, CopyPalette 14
			// CTab2Palette used to pop 14 and skewed the caller's registers by a word when a palette editor restored them.
			case 0xa9e:{int tol=(short)memory.readWord(cpu.a[7]),usage=(short)memory.readWord(cpu.a[7]+2),entry=(short)memory.readWord(cpu.a[7]+4),p=memory.readLong(cpu.a[7]+6);cpu.a[7]+=10;Palette pal=palettes.get(p);if(pal!=null&&entry>=0&&entry<pal.usage.length){pal.usage[entry]=usage;pal.tolerance[entry]=tol;writePaletteRecord(p,pal);}return true;}
			case 0xa9f:{int tol=(short)memory.readWord(cpu.a[7]),usage=(short)memory.readWord(cpu.a[7]+2),p=memory.readLong(cpu.a[7]+4),table=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;Palette pal=palettes.get(p);
				// the palette takes the colour table's size (IM: "if the palette is not the same size, it is resized"); some editors build a 2-entry palette this way
				int data=table==0?0:memory.readLong(table);if(pal!=null&&data!=0){int n=(short)memory.readWord(data+6)+1;if(n>=0&&n!=pal.rgb.length){pal.rgb=java.util.Arrays.copyOf(pal.rgb,n);pal.usage=java.util.Arrays.copyOf(pal.usage,n);pal.tolerance=java.util.Arrays.copyOf(pal.tolerance,n);}
					colorTableToPaletteObject(table,pal,0);for(int i=0;i<pal.usage.length;i++){pal.usage[i]=usage;pal.tolerance[i]=tol;}writePaletteRecord(p,pal);}return true;}
			case 0xaa0:{int table=memory.readLong(cpu.a[7]),p=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;paletteToColorTable(p,table,0);return true;}
			case 0xaa1:{int length=(short)memory.readWord(cpu.a[7]),dstEntry=(short)memory.readWord(cpu.a[7]+2),srcEntry=(short)memory.readWord(cpu.a[7]+4),dst=memory.readLong(cpu.a[7]+6),src=memory.readLong(cpu.a[7]+10);cpu.a[7]+=14;
				Palette from=palettes.get(src),to=palettes.get(dst);if(from!=null&&to!=null){for(int i=0;i<length;i++){int a=srcEntry+i,b=dstEntry+i;if(a<0||b<0||a>=from.rgb.length||b>=to.rgb.length)break;to.rgb[b]=from.rgb[a];to.usage[b]=from.usage[a];to.tolerance[b]=from.tolerance[a];}writePaletteRecord(dst,to);}return true;}
			// SetDeskCPat(PixPatHandle): DeskCPat ($CD8) and the top bit of pCDeskPat ($20B) say which pattern the desktop uses
			// (nil = the classic DeskPattern); the desk itself is the host's, so nothing is repainted
			case 0xa47:{int pattern=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeLong(0xcd8,pattern);
				int flags=memory.readByte(0x20b);memory.writeByte(0x20b,pattern!=0?flags|0x80:flags&0x7f);return true;}
			case 0xaa2:return paletteDispatch(cpu);
			default:return false;
		}
	}

	private static int stdProcTrap(int i){return QuickDraw68k.stdProcTrap(i);}

	// ------------------------------------------------------------------ bottleneck procedures (grafProcs)

	/** The bytes of a PicComment's data Handle (empty for NIL or a zero size). */
	private byte[] commentData(int handle,int size) throws M68kCpuException {
		int data=handle==0?0:memory.readLong(handle);if(data==0||size<=0)return new byte[0];return memory.readBytes(data,size);
	}
	/** QDProcs index a drawing trap is routed through, -1 for traps that do not go through the bottlenecks. */
	private static int bottleneckIndex(int trap){
		switch(trap){
			case 0x883:case 0x884:case 0x885:return 0;                          // DrawChar/DrawString/DrawText -> textProc
			case 0x891:case 0x892:return 1;                                     // LineTo/Line -> lineProc
			case 0x8a1:case 0x8a2:case 0x8a3:case 0x8a4:case 0x8a5:return 2;    // rects -> rectProc
			case 0x8b0:case 0x8b1:case 0x8b2:case 0x8b3:case 0x8b4:return 3;    // round rects -> rRectProc
			case 0x8b7:case 0x8b8:case 0x8b9:case 0x8ba:case 0x8bb:return 4;    // ovals -> ovalProc
			case 0x8be:case 0x8bf:case 0x8c0:case 0x8c1:case 0x8c2:return 5;    // arcs -> arcProc
			case 0x8c6:case 0x8c7:case 0x8c8:case 0x8c9:case 0x8ca:return 6;    // polygons -> polyProc
			case 0x8d2:case 0x8d3:case 0x8d4:case 0x8d5:case 0x8d6:return 7;    // regions -> rgnProc
			case 0x8ec:return 8;                                                // CopyBits into the port -> bitsProc
			case 0x8f2:return 9;                                                // PicComment -> commentProc
			case 0x886:case 0x88c:case 0x88d:return 10;                         // TextWidth/StringWidth/CharWidth -> txMeasProc
			default:return -1;
		}
	}
	private static int verbOf(int trap){return trap>=0x8d2?trap-0x8d2:trap>=0x8c6?trap-0x8c6:trap>=0x8be?trap-0x8be:trap>=0x8b7?trap-0x8b7:trap>=0x8b0?trap-0x8b0:trap-0x8a1;}
	/** The pattern of the Fill* call in progress (StdRect... with verb 4 paint with it), 0 = the port's fillPat. */
	private int stdFillPattern;
	/** FillRect and friends: the pattern becomes the port's fillPat before the bottleneck runs (a colour port keeps its
	 *  fillPixPat; the pattern pointer is remembered for the standard procedures instead). */
	private void setFillPattern(int port,int pattern) throws M68kCpuException {
		stdFillPattern=pattern;if(pattern!=0&&(memory.readWord(port+6)&0xc000)!=0xc000)memory.copy(pattern,port+40,8);
	}
	/**
	 * A port whose grafProcs point at the program's own procedures (not the ones SetStdProcs installs) gets its drawing
	 * calls delivered to them with the standard procedures' arguments, as QuickDraw does; a procedure that chains to the
	 * standard one calls the Std* trap, which draws (and records into an open picture) directly.
	 */
	private boolean bottleneck(M68kCpu cpu,int trap) throws M68kException {
		int index=bottleneckIndex(trap);if(index<0)return false;
		int port=qd.currentPortAddress();if(port==0)return false;
		int procs=memory.readLong(port+104);if(procs==0)return false;
		int proc=memory.readLong(procs+index*4);if(proc==0||proc==tb.cpu.trapRomAddress(stdProcTrap(index)))return false;
		int sp=cpu.a[7];
		if(Mac68kApi.TRACE)System.err.println("[68K] bottleneck "+index+" for trap $"+Integer.toHexString(0xa000|trap)+" -> $"+Integer.toHexString(proc));
		switch(index){
			case 0:{ // textProc(byteCount, textAddr, numer, denom)
				int count,text,base;
				if(trap==0x883){int c=memory.readWord(sp)&0xff;base=sp+2;memory.writeByte(base-2,c);text=base-2;count=1;cpu.a[7]=base-2;}
				else if(trap==0x884){int s=memory.readLong(sp);base=sp+4;cpu.a[7]=base;if(s==0)return true;count=memory.readByte(s);text=s+1;}
				else{count=(short)memory.readWord(sp);int first=(short)memory.readWord(sp+2);text=memory.readLong(sp+4)+first;base=sp+8;cpu.a[7]=base;}
				cpu.pushWord(count);cpu.pushLong(text);cpu.pushLong(0x00010001);cpu.pushLong(0x00010001);
				tb.cpu.callPascal(proc,14);cpu.a[7]=base;return true;}
			case 10:{ // txMeasProc(byteCount, textAddr, VAR numer, VAR denom, VAR info): INTEGER
				int count,text,base;
				if(trap==0x88d){int c=memory.readWord(sp)&0xff;base=sp+2;memory.writeByte(base-2,c);text=base-2;count=1;cpu.a[7]=base-2;}
				else if(trap==0x88c){int s=memory.readLong(sp);base=sp+4;cpu.a[7]=base;if(s==0){memory.writeWord(base,0);return true;}count=memory.readByte(s);text=s+1;}
				else{count=(short)memory.readWord(sp);int first=(short)memory.readWord(sp+2);text=memory.readLong(sp+4)+first;base=sp+8;cpu.a[7]=base;}
				cpu.a[7]-=16;int temps=cpu.a[7];memory.writeLong(temps,0x00010001);memory.writeLong(temps+4,0x00010001);memory.fill(temps+8,8,0);
				cpu.pushWord(0);cpu.pushWord(count);cpu.pushLong(text);cpu.pushLong(temps);cpu.pushLong(temps+4);cpu.pushLong(temps+8);
				tb.cpu.callPascal(proc,18);int width=(short)memory.readWord(cpu.a[7]);
				cpu.a[7]=base;memory.writeWord(base,width);return true;}
			case 1:{ // lineProc(newPt); the pen moves there afterwards whatever the procedure drew
				int v=(short)memory.readWord(sp),h=(short)memory.readWord(sp+2);cpu.a[7]+=4;
				if(trap==0x892){v+=(short)memory.readWord(port+48);h+=(short)memory.readWord(port+50);}
				int base=cpu.a[7];cpu.pushLong(v<<16|h&0xffff);tb.cpu.callPascal(proc,4);cpu.a[7]=base;
				memory.writeWord(port+48,v);memory.writeWord(port+50,h);return true;}
			case 2:case 4:case 6:case 7:{ // rectProc/ovalProc/polyProc/rgnProc(verb, shape)
				int verb=verbOf(trap),shape;
				if(verb==4){setFillPattern(port,memory.readLong(sp));shape=memory.readLong(sp+4);cpu.a[7]+=8;}else{shape=memory.readLong(sp);cpu.a[7]+=4;}
				int base=cpu.a[7];cpu.pushWord(verb<<8);cpu.pushLong(shape); // GrafVerb in the high byte, as MOVE.B pushes it
				try{tb.cpu.callPascal(proc,6);}finally{cpu.a[7]=base;stdFillPattern=0;}return true;}
			case 3:case 5:{ // rRectProc(verb, r, ovalWidth, ovalHeight) / arcProc(verb, r, startAngle, arcAngle)
				int verb=verbOf(trap),o=verb==4?4:0,second=(short)memory.readWord(sp+o),first=(short)memory.readWord(sp+o+2),rect=memory.readLong(sp+o+4);
				if(verb==4)setFillPattern(port,memory.readLong(sp));cpu.a[7]+=o+8;
				int base=cpu.a[7];cpu.pushWord(verb<<8);cpu.pushLong(rect);cpu.pushWord(first);cpu.pushWord(second);
				try{tb.cpu.callPascal(proc,10);}finally{cpu.a[7]=base;stdFillPattern=0;}return true;}
			case 8:{ // bitsProc(srcBits, srcRect, dstRect, mode, maskRgn) - only for copies into the port itself
				int mask=memory.readLong(sp),mode=memory.readWord(sp+4),dstRect=memory.readLong(sp+6),srcRect=memory.readLong(sp+10),dstBits=memory.readLong(sp+14),srcBits=memory.readLong(sp+18);
				if(dstBits!=port+2)return false;
				cpu.a[7]+=22;int base=cpu.a[7];cpu.pushLong(srcBits);cpu.pushLong(srcRect);cpu.pushLong(dstRect);cpu.pushWord(mode);cpu.pushLong(mask);
				tb.cpu.callPascal(proc,18);cpu.a[7]=base;return true;}
			case 9:{ // commentProc(kind, dataSize, dataHandle)
				int handle=memory.readLong(sp),size=memory.readWord(sp+4),kind=memory.readWord(sp+6);cpu.a[7]+=8;
				int base=cpu.a[7];cpu.pushWord(kind);cpu.pushWord(size);cpu.pushLong(handle);tb.cpu.callPascal(proc,8);cpu.a[7]=base;return true;}
			default:return false;
		}
	}
	/** Std{Rect,RRect,Oval,Arc,Poly,Rgn}(verb, shape[, two words]): drawn as the Frame/Paint/Erase/Invert/Fill call of the verb. */
	/**
	 * The GrafVerb argument word of a Std* procedure. GrafVerb is a byte-sized Pascal enum, so QuickDraw and Pascal callers
	 * push it with MOVE.B: the value is the HIGH byte and the low byte is whatever was on the stack (Enchanted Scepters'
	 * StdPoly calls pass $0118 = paint and $0018 = frame; reading the whole word dropped the painted polygons). A word of
	 * 1..4 with a zero high byte (a C caller passing an int) is taken as that verb.
	 */
	static int grafVerb(int word){
		int hi=word>>8&0xff,lo=word&0xff;
		if(hi==0&&lo>=1&&lo<=4)return lo;
		return hi<=4?hi:-1;
	}
	private boolean stdShape(M68kCpu cpu,int frameTrap,int fillTrap,int extraWords) throws M68kException {
		int sp=cpu.a[7],w2=(short)memory.readWord(sp),w1=(short)memory.readWord(sp+2),o=extraWords*2,shape=memory.readLong(sp+o),verb=grafVerb(memory.readWord(sp+o+4));cpu.a[7]+=6+o;
		int base=cpu.a[7];if(verb<0||verb>4)return true;
		cpu.pushLong(shape);if(extraWords==2){cpu.pushWord(w1);cpu.pushWord(w2);}
		if(verb==4){int port=qd.currentPortAddress();cpu.pushLong(stdFillPattern!=0?stdFillPattern:port+40);qd.handle(cpu,fillTrap);}
		else qd.handle(cpu,frameTrap+verb);
		cpu.a[7]=base;return true;
	}

	// ------------------------------------------------------------------ text

	Font currentFont() throws M68kCpuException {int[] t=qd.textState();return Fonts68k.font(t[0],t[3],t[1]);}
	Font fontFor(int id,int size,int face){return Fonts68k.font(id,size,face);}

	int width(byte[] text) throws M68kCpuException {return width(currentFont(),text);}
	int width(Font font,byte[] text){return font instanceof BitmapFont68k?((BitmapFont68k)font).width(text):Fonts68k.width(font,hostText(font,text));}
	/**
	 * The text a host font draws for Mac bytes: NUL, backspace and $1D map to the zero-width .null glyph in Macintosh TrueType
	 * fonts (and to empty glyphs in the bitmap ones), so they draw nothing; the host font would show its missing-glyph box.
	 * Exile pads "Hit mouse button to start." to a 32-byte Pascal string with NULs, which came out as six boxes.
	 */
	/** The text as a host font draws it: null glyphs dropped, and $11-$14 as the Mac fonts' command key, check mark, diamond
	 *  and Apple glyphs (REALbasic's menu editor draws a shortcut as $11 "Q"; a host font has nothing at U+0011). */
	private String hostText(byte[] text){return hostText(null,text);}
	/** The same as {@code font} draws it: a Roman-script font's one-byte characters under a Japanese system read as Mac OS Roman (Toolbox68k.decodeFor). */
	private String hostText(Font font,byte[] text){
		int keep=0;for(byte b:text)if(!nullGlyph(b))keep++;
		if(keep==text.length)return tb.decodeTitleFor(font,text);
		byte[] kept=new byte[keep];int n=0;for(byte b:text)if(!nullGlyph(b))kept[n++]=b;
		return tb.decodeTitleFor(font,kept);
	}
	private static boolean nullGlyph(byte b){return b==0x00||b==0x08||b==0x1d;}

	/** SpaceExtra (spExtra, Fixed at port+76) and CharExtra (chExtra, 4.12 at port+12 of a colour port): {pixels added to each
	 *  space, pixels added to each character}. Word processors justify lines with SpaceExtra (MacWrite). */
	private static final int SP_EXTRA=76,CH_EXTRA=12;
	private double[] textExtra() throws M68kCpuException {
		int port=qd.currentPortAddress();if(port==0)return new double[2];
		// a classic-layout window (GetNewWindow's GrafPort) keeps its portBits.bounds where a colour port has chExtra: it has none
		boolean chExtra=qd.colorPort(port)&&qd.classicWindowPosition(port)==null;
		return new double[]{memory.readLong(port+SP_EXTRA)/65536.0,chExtra?(short)memory.readWord(port+CH_EXTRA)/4096.0:0};
	}
	/** Characters of the text as byte ranges (a Japanese double-byte character is one). */
	private java.util.List<int[]> characters(byte[] text){
		java.util.List<int[]> list=new java.util.ArrayList<int[]>();boolean jp=tb.encoding==StackTextEncoding.JAPANESE;
		for(int i=0;i<text.length;){int n=jp&&Toolbox68k.isJapaneseLead(text[i]&255)&&i+1<text.length?2:1;list.add(new int[]{i,n});i+=n;}
		return list;
	}
	/** TextWidth/StringWidth/CharWidth: the width with SpaceExtra and CharExtra, as QuickDraw measures it. */
	private int extraWidth(byte[] text) throws M68kCpuException {
		double[] extra=textExtra();int plain=width(text);if(extra[0]==0&&extra[1]==0)return plain;
		int spaces=0;for(byte b:text)if(b==' ')spaces++;
		return (int)Math.round(plain+spaces*extra[0]+characters(text).size()*extra[1]);
	}
	/** DrawText with extra space: runs between spaces drawn one after the other (character by character with CharExtra). */
	private void drawSpaced(byte[] text,int h,int v,Font font,int mode,int face,double[] extra) throws M68kCpuException {
		double x=h;boolean hidden=qd.penHidden();java.util.List<int[]> chars=characters(text);
		int i=0;
		while(i<chars.size()){
			int start=chars.get(i)[0];int end=i;
			if(extra[1]==0)while(end<chars.size()&&text[chars.get(end)[0]]!=' ')end++; // a run up to the next space
			if(end==i)end=i+1;
			byte[] run=java.util.Arrays.copyOfRange(text,start,chars.get(end-1)[0]+chars.get(end-1)[1]);
			int px=(int)Math.round(x);int w=hidden?width(font,run):drawText(run,px,v,font,mode,face);
			x+=w;for(int k=i;k<end;k++){if(text[chars.get(k)[0]]==' ')x+=extra[0];x+=extra[1];}
			i=end;
		}
		qd.setPenLocation(v,(int)Math.round(x));
	}
	void drawBytes(byte[] text) throws M68kCpuException {
		int[] pen=qd.penLocation();int v=pen[0],h=pen[1];
		int[] t=qd.textState();Font font=stretched(Fonts68k.font(t[0],t[3],t[1]));
		qd.recordText(h,v,text); // an open picture gets the text too; some media windows render their title through a scaled picture
		if(!qd.penHidden())Fonts68k.noteDrawn(t[0],text,font); // the fonts "Fix Garbled Text…" tries (Fonts68k.Substitution)
		double[] extra=textExtra();
		if(extra[0]!=0||extra[1]!=0){drawSpaced(text,h,v,font,t[2],t[1],extra);return;} // justified lines: a document's, never translated
		awaiting=null;
		String translation=qd.penHidden()?null:translationOf(text,t[2]);
		if(translation!=null){ // the program's text in the user's language, in the room the original takes (its pen moves as far)
			int advance=width(font,text);
			drawTranslation(translation,h,v,Fonts68k.hostFont(t[0],t[3],t[1]),t[2],advance);
			qd.setPenLocation(v,h+advance);return;}
		if(awaiting!=null){ // drawn as it is for now; replaced in place when the translation comes (see PendingRun)
			PendingRun run=new PendingRun(awaiting,tb.api.currentQuickDrawPort(),h,v,t,width(font,text),Fonts68k.metrics(font));
			run.before=qd.readRgb(run.top,run.left,run.bottom,run.right);
			int advance=drawText(text,h,v,font,t[2],t[1]);
			run.after=qd.readRgb(run.top,run.left,run.bottom,run.right);
			run.fg=qd.foregroundRgb();run.bg=qd.backgroundRgb();
			synchronized(pendingRuns){pendingRuns.add(run);while(pendingRuns.size()>400)pendingRuns.remove(0);}
			qd.setPenLocation(v,h+advance);return;}
		int fit=qd.penHidden()?0:pageLineFit(font,text);
		if(fit>0){ // a translated line of a page of text, longer than the program's lines: squeezed into their width
			drawTranslation(hostText(text),h,v,Fonts68k.hostFont(t[0],t[3],t[1]),t[2],fit,true);
			qd.setPenLocation(v,h+fit);return;}
		int advance=qd.penHidden()?width(font,text):drawText(text,h,v,font,t[2],t[1]); // a hidden pen (HidePen, OpenPicture) only moves
		qd.setPenLocation(v,h+advance);
	}
	/**
	 * The width of the program's lines when {@code text} is a line of a page of text its translated resources made longer
	 * than them (ResourceTranslation.Overlay.lineBox: the longest original line in the font drawn), else 0.
	 */
	private int pageLineFit(Font font,byte[] text) throws M68kCpuException {
		ResourceTranslation.Overlay overlay=tb.app==null?null:tb.app.translatedResources;
		if(overlay==null||overlay.lineBoxes.isEmpty())return 0;
		String box=overlay.lineBox(hostText(text));
		if(box==null)return 0;
		int room;try{room=width(font,tb.encoding.encode(box));}catch(java.nio.charset.CharacterCodingException e){return 0;}
		room-=Math.max(1,font.getSize()/4); // a full-width line's last glyph ends short of its advance: the translation's ink as far
		return room>0&&width(font,text)>room?room:0;
	}

	/** Picture playback is drawing its text (a picture's text is part of the image: never translated). */
	boolean inPicture;
	/** Width over height of the glyphs while a picture drawn at another aspect plays its text (QuickDraw68k.pictureTextStretch), else 1. */
	double textStretch=1;
	/** {@code font} widened or narrowed by {@link #textStretch}; a strike (the program's own bitmaps) is drawn as it is. */
	private Font stretched(Font font){
		if(textStretch==1||font instanceof BitmapFont68k)return font;
		java.awt.geom.AffineTransform x=java.awt.geom.AffineTransform.getScaleInstance(textStretch,1);
		if(font instanceof StyledFont68k){StyledFont68k s=(StyledFont68k)font;return new StyledFont68k(s.base.deriveFont(x),s.face,(int)Math.round(s.fixedAdvance*textStretch));}
		return font.deriveFont(x);
	}

	/** The text of the run being drawn when its translation is still coming (set by translationOf), else null. */
	private String awaiting;
	/**
	 * A run drawn as it is while its translation was coming, with the pixels under it before and after: a program that
	 * draws its text once into an offscreen world and only copies that to the window on updates (ウルティメイト・スペル's
	 * prologue) never draws it again, so the translation replaces it in place - when the pixels are still exactly what the
	 * original left (the program has not drawn over it or scrolled it since).
	 */
	static final class PendingRun {
		final String text;final int port,h,v,fontId,size,face,mode,advance,ascent,descent;final int top,left,bottom,right;final long at=System.currentTimeMillis();
		int[] before,after;int fg,bg;
		PendingRun(String text,int port,int h,int v,int[] textState,int advance,FontMetrics m){
			this.text=text;this.port=port;this.h=h;this.v=v;fontId=textState[0];face=textState[1];mode=textState[2];size=textState[3];this.advance=advance;
			int pad=Math.max(2,(m.getAscent()+m.getDescent())/4);ascent=m.getAscent();descent=m.getDescent();
			top=v-ascent-2;bottom=v+descent+2;left=h-pad;right=h+advance+pad;
		}
	}
	private final java.util.List<PendingRun> pendingRuns=new java.util.ArrayList<PendingRun>();

	/**
	 * Plain copies (srcCopy, same size) from offscreen ports to windows: {source port, source rect (4), window, destination
	 * rect (4)}. Copies with the same ports and offset that touch are one area: a program that wipes its offscreen world
	 * onto the window a row at a time (ウルティメイト・スペル) makes one record, not hundreds.
	 */
	private final java.util.List<int[]> offscreenCopies=new java.util.ArrayList<int[]>();
	/** CopyBits from an offscreen world to a window, remembered while translation is on: a translation put into the world later goes to the window the same way. */
	void noteCopy(int sourcePort,int[] source,int destinationPort,int[] destination,int mode) throws M68kCpuException {
		if((mode&~0x40)!=0||source[2]-source[0]!=destination[2]-destination[0]||source[3]-source[1]!=destination[3]-destination[1])return;
		if(!tb.windows.isWindow(destinationPort)||tb.windows.isWindow(sourcePort))return;
		int dv=destination[0]-source[0],dh=destination[1]-source[1];
		synchronized(offscreenCopies){
			int[] merged={sourcePort,source[0],source[1],source[2],source[3],destinationPort,destination[0],destination[1],destination[2],destination[3]};
			for(java.util.Iterator<int[]> it=offscreenCopies.iterator();it.hasNext();){
				int[] c=it.next();
				if(c[0]!=sourcePort||c[5]!=destinationPort||c[6]-c[1]!=dv||c[7]-c[2]!=dh)continue;
				if(c[1]>merged[3]||c[3]<merged[1]||c[2]>merged[4]||c[4]<merged[2])continue; // apart: another area
				merged[1]=Math.min(merged[1],c[1]);merged[2]=Math.min(merged[2],c[2]);merged[3]=Math.max(merged[3],c[3]);merged[4]=Math.max(merged[4],c[4]);
				it.remove();
			}
			merged[6]=merged[1]+dv;merged[7]=merged[2]+dh;merged[8]=merged[3]+dv;merged[9]=merged[4]+dh;
			offscreenCopies.add(merged);
			while(offscreenCopies.size()>32)offscreenCopies.remove(0);
		}
	}
	/** Copies a rectangle of an offscreen port that changed on to the windows it was last copied to (see noteCopy). */
	private void copyOnToWindows(int port,int top,int left,int bottom,int right) throws M68kCpuException {
		java.util.List<int[]> copies;
		synchronized(offscreenCopies){copies=new java.util.ArrayList<int[]>(offscreenCopies);}
		if(Mac68kApi.TRACE)System.err.println("[68K] translation: "+copies.size()+" offscreen copies known");
		for(int[] c:copies){
			if(c[0]!=port||!qd.knownPort(c[5]))continue;
			int t=Math.max(top,c[1]),l=Math.max(left,c[2]),b=Math.min(bottom,c[3]),r=Math.min(right,c[4]);
			if(Mac68kApi.TRACE)System.err.println("[68K] translation copied on from $"+Integer.toHexString(port)+" to window $"+Integer.toHexString(c[5])+" "+t+","+l+"-"+b+","+r);
			if(b<=t||r<=l)continue;
			if(qd.currentPortAddress()!=port)qd.setPortTo(tb.cpu,port);
			int[] pixels=qd.readRgb(t,l,b,r);
			qd.setPortTo(tb.cpu,c[5]);
			qd.writeRgb(t+c[6]-c[1],l+c[7]-c[2],b+c[6]-c[1],r+c[7]-c[2],pixels);
			VMTranslation.copiedOn.incrementAndGet();
		}
	}

	/**
	 * Puts the translations that arrived in place of the runs drawn while they were coming. Returns the ports where a
	 * run could not be replaced because something was drawn over it since: those windows are erased and drawn again.
	 */
	java.util.Set<Integer> replacePendingRuns(VMTranslation.Translator translator) throws M68kCpuException {
		java.util.Set<Integer> failed=new java.util.HashSet<Integer>();
		java.util.List<PendingRun> runs;
		if(translator==null){ // the language is still being found: the runs wait; translation turned off: they are forgotten
			if(tb.app==null||tb.app.translator==null)synchronized(pendingRuns){pendingRuns.clear();}
			return failed;
		}
		synchronized(pendingRuns){runs=new java.util.ArrayList<PendingRun>(pendingRuns);}
		if(runs.isEmpty())return failed;
		long now=System.currentTimeMillis();
		int saved=qd.currentPortAddress();
		try{
			for(PendingRun run:runs){
				boolean drop=now-run.at>180_000||!qd.knownPort(run.port)||translator.editable(run.port);
				// asked with its port: drawn before the language was known it is asked only now, and its arrival must come back here
				String translation=drop?null:translator.lookup(VMTranslation.Kind.TEXT,run.text,run.port);
				if(!drop&&translation==null)continue; // still coming
				synchronized(pendingRuns){pendingRuns.remove(run);}
				if(drop)continue;
				if(qd.currentPortAddress()!=run.port)qd.setPortTo(tb.cpu,run.port);
				// only the pixels the original changed count (its glyphs; the next line may since have been drawn into the margin)
				int[] pixels=qd.readRgb(run.top,run.left,run.bottom,run.right);boolean intact=pixels.length==run.after.length;
				for(int i=0;intact&&i<pixels.length;i++)if(run.before[i]!=run.after[i]&&pixels[i]!=run.after[i])intact=false;
				if(Mac68kApi.TRACE)System.err.println("[68K] translation in place port $"+Integer.toHexString(run.port)+" at "+run.h+","+run.v+(intact?"":" (drawn over since)")+" \""+run.text+"\"");
				if(!intact){failed.add(run.port);continue;}
				for(int i=0;i<pixels.length;i++)if(run.before[i]!=run.after[i])pixels[i]=run.before[i];
				qd.writeRgb(run.top,run.left,run.bottom,run.right,pixels);
				Object[] fore=qd.saveForeground();qd.setForegroundRgb16((run.fg>>>16&255)*257,(run.fg>>>8&255)*257,(run.fg&255)*257);
				Object clip=qd.setHostClip(run.v-run.ascent,run.left,run.v+run.descent,run.right);
				try{
					int mode=run.mode;
					if((mode&0x3f)==0){qd.fillRgb(run.v-run.ascent,run.h,run.v+run.descent,run.h+run.advance,run.bg);mode=1;} // srcCopy: its box in the colour it had
					drawTranslation(translation,run.h,run.v,Fonts68k.hostFont(run.fontId,run.size,run.face),mode,run.advance);
				}finally{qd.restoreHostClip(clip);qd.restoreForeground(fore);}
				VMTranslation.replacedInPlace.incrementAndGet();
				if(!tb.windows.isWindow(run.port))copyOnToWindows(run.port,run.top,run.left,run.bottom,run.right);
			}
		}finally{if(qd.currentPortAddress()!=saved&&saved!=0)qd.setPortTo(tb.cpu,saved);}
		return failed;
	}

	/**
	 * The translation (VMTranslation) of a run the program draws, or null to draw it as it is: translation off or still
	 * coming, a picture's text, XOR text (drawn and erased again: rubber bands, hilites), or a port the user types into.
	 * A port is taken for an editing one when a run drawn there carries a character the user has just typed - an editor
	 * echoing the keystroke; the program's windows are then drawn again with that port's text untranslated.
	 */
	private String translationOf(byte[] text,int mode) throws M68kCpuException {
		VMTranslation.Translator translator=tb.translator();
		if(translator==null){
			// translation is on but the program's language is still being found: kept to be replaced like a run whose
			// translation is coming (a program that draws its title screen once, at once, would otherwise keep it)
			VMTranslation.Translator starting=tb.app==null||tb.app.service?null:tb.app.translator;
			if(starting!=null&&starting.from()==null&&!inPicture&&(mode&3)!=2&&text.length>=2&&!starting.editable(tb.api.currentQuickDrawPort())){
				String s=hostText(text);if(VMTranslation.Piece.of(s)!=null){starting.textDrawnIn(tb.api.currentQuickDrawPort());awaiting=s;}}
			return null;
		}
		if(inPicture||(mode&3)==2||text.length<2)return null;
		int port=tb.api.currentQuickDrawPort();
		if(translator.editable(port))return null;
		String s=hostText(text);
		if(translator.typedInto(s)){
			if(translator.markEditable(port)&&tb.app!=null){translator.eraseOnRedraw(port);tb.app.redrawForTranslation();}
			return null;
		}
		if(VMTranslation.Piece.of(s)!=null)translator.textDrawnIn(port);
		String translation=translator.lookup(VMTranslation.Kind.TEXT,s,port);
		if(translation==null&&translator.awaiting(VMTranslation.Kind.TEXT,s))awaiting=s;
		return translation;
	}

	/**
	 * Draws a translation with a host font at baseline (h,v) in the port's text mode, squeezed horizontally into {@code fit}
	 * pixels when it is wider (to half its width at most; {@code fit} <= 0: its own width). Returns the width it takes.
	 * It goes through a coverage mask like any host text, so every depth, clip and transfer mode behaves as for the original.
	 */
	int drawTranslation(String s,int h,int v,Font font,int mode,int fit) throws M68kCpuException {return drawTranslation(s,h,v,font,mode,fit,false);}
	/** The same; {@code smaller}: a much longer text also takes a smaller size (to 3/4) so it is squeezed less - a page's line. */
	int drawTranslation(String s,int h,int v,Font font,int mode,int fit,boolean smaller) throws M68kCpuException {
		if(s.isEmpty())return Math.max(0,fit);
		if(mode==49){ // grayishTextOr: dimmed, as drawText does it
			Object[] saved=qd.saveForeground();if(saved==null)return drawTranslation(s,h,v,font,1,fit,smaller);
			int f=qd.foregroundRgb(),b=qd.backgroundRgb();
			qd.setForegroundRgb16(((f>>>16&255)+(b>>>16&255))/2*257,((f>>>8&255)+(b>>>8&255))/2*257,((f&255)+(b&255))/2*257);
			try{return drawTranslation(s,h,v,font,1,fit,smaller);}finally{qd.restoreForeground(saved);}
		}
		if(smaller&&fit>0){
			int natural=Fonts68k.width(font,s);
			if(natural>fit*1.1){float size=Math.max(font.getSize2D()*0.75f,Math.round(font.getSize2D()*(float)Math.sqrt(fit/(double)natural)));if(size<font.getSize2D())return drawTranslation(s,h,v,font.deriveFont(size),mode,fit,false);}
		}
		VMTranslation.drawn.incrementAndGet();
		FontMetrics m=Fonts68k.metrics(font);int natural=Fonts68k.width(font,s);
		double sx=fit>0&&natural>fit?Math.max(0.5,fit/(double)natural):1;
		int drawn=(int)Math.ceil(natural*sx),ascent=m.getAscent(),height=Math.max(1,ascent+m.getDescent()),pad=Math.max(2,height/4);
		int room=fit>0?Math.max(fit,drawn):drawn;
		int[] box={h,v-ascent,room,height};
		if((mode&7)==0||(mode&7)==4)qd.fillRgb(v-ascent,h,v+m.getDescent(),h+room,(mode&7)==4?qd.foregroundRgb():qd.backgroundRgb());
		boolean smooth=qd.currentPortDepth()>1;int maskWidth=drawn+2*pad;
		BufferedImage mask=translationMask(font,s,sx,maskWidth,height,pad,ascent,1,smooth);
		SmoothZoom.Text hi=k->translationMask(font,s,sx,maskWidth,height,pad,ascent,k,true);
		if(smooth)qd.drawCoverageMask(mask,h-pad,v-ascent,mode,box,hi);else qd.drawMask(mask,h-pad,v-ascent,mode,box,hi);
		return room;
	}
	/** A translation's glyphs as a grey coverage mask {@code scale} times the size, squeezed by {@code sx}, its baseline at {@code ascent}. */
	private static BufferedImage translationMask(Font font,String s,double sx,int width,int height,int pad,int ascent,int scale,boolean smooth){
		BufferedImage mask=new BufferedImage(Math.max(1,width*scale),Math.max(1,height*scale),BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g=mask.createGraphics();
		try{
			g.setColor(Color.WHITE);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,smooth?RenderingHints.VALUE_TEXT_ANTIALIAS_ON:RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.scale(scale*sx,scale);
			Fonts68k.drawString(g,font,s,(int)Math.round(pad/sx),ascent);
		}finally{g.dispose();}
		return mask;
	}

	/**
	 * Outline and shadow text is hollow: QuickDraw draws the rim with the text mode and the glyphs' own ink with the
	 * opposite one (srcOr/srcBic, notSrcOr/notSrcBic), so the inside takes the background colour - outlined or
	 * shadowed text in a transparent HyperCard field over a black card picture shows white letters. srcCopy paints
	 * the character boxes with the background anyway; srcXor leaves the inside alone.
	 */
	private static boolean clearsHollow(int mode,int face){return TextStyle68k.hollow(face)&&(mode&~7)==0&&(mode&1)==1;}

	private static BufferedImage cropLeft(BufferedImage image,int columns){
		if(image==null||columns<=0||columns>=image.getWidth())return image;return image.getSubimage(columns,0,image.getWidth()-columns,image.getHeight());}
	/** Draws text at baseline (h,v) in the current port; returns the advance. Charged to the speed throttle as a real Mac's StdText. */
	int drawText(byte[] text,int h,int v,Font font,int mode,int face) throws M68kCpuException {
		int advance=drawText0(text,h,v,font,mode,face);
		qd.chargeText(tb.cpu,text.length,advance,Math.max(1,font.getSize())*5/4);
		return advance;
	}
	private int drawText0(byte[] text,int h,int v,Font font,int mode,int face) throws M68kCpuException {
		if(mode==49){ // grayishTextOr: srcOr in the colour halfway between the foreground and the background (dimmed items)
			Object[] saved=qd.saveForeground();if(saved==null)return drawText0(text,h,v,font,1,face);
			int f=qd.foregroundRgb(),b=qd.backgroundRgb();int r=((f>>>16&255)+(b>>>16&255))/2,g=((f>>>8&255)+(b>>>8&255))/2,bl=((f&255)+(b&255))/2;
			qd.setForegroundRgb16(r*257,g*257,bl*257);
			try{return drawText0(text,h,v,font,1,face);}finally{qd.restoreForeground(saved);}
		}
		if(tb.app!=null)tb.app.textShown(text); // a program asking for a 256-colour screen is restarted at 8 bits (MacApp68k.textShown)
		if(Mac68kApi.TRACE)System.err.println("[68K] drawText at "+h+","+v+" font "+font.getFontName()+"/"+font.getSize()+" face "+face+" mode "+mode+" port $"+Integer.toHexString(tb.api.currentQuickDrawPort())+" \""+tb.decode(text)+"\"");
		if(font instanceof BitmapFont68k){ // a strike (the program's own or an installed one): its glyphs, styled, through QuickDraw with the text mode
			BitmapFont68k strike=(BitmapFont68k)font;if(text.length==0)return 0;
			int advance=strike.width(text);int left=h-strike.maskLeft(),top=v-strike.maskAscent();
			// With the "always anti-alias" preference a family that carries outlines beside its bitmaps (Osaka, TrueType
			// Chicago) draws them instead: the strike's own pixels are all a two-byte code ever had, so kanji stay jagged otherwise.
			// the smooth zoom's planes take the run again at their scale: the outlines, or the bitmaps magnified (see SmoothZoom)
			int algorithm=qd.smooth.algorithm();SmoothZoom.Text hiInside=k->strike.coverage(text,true,k,algorithm),hiText=k->strike.coverage(text,false,k,algorithm);
			if(Fonts68k.smoothText&&qd.currentPortDepth()>1&&strike.smoothable()&&!qd.plainGlyphs){
				if((mode&7)==0||(mode&7)==4)qd.fillRgb(v-strike.ascent,h,v-strike.ascent+strike.fRectHeight,h+advance,(mode&7)==4?qd.foregroundRgb():qd.backgroundRgb());
				int coverageLeft=h-strike.coverageLeft();
				if(clearsHollow(mode,strike.face))qd.drawCoverageMask(strike.coverage(text,true),coverageLeft,top,mode^2,null,hiInside);
				qd.drawCoverageMask(strike.coverage(text),coverageLeft,top,mode,new int[]{h,v-strike.ascent,advance,strike.fRectHeight},hiText);return advance;}
			// the zoom planes' outline runs have the coverage's room on the left: cut to the pixel mask's columns
			int room=strike.coverageLeft()-strike.maskLeft();
			SmoothZoom.Text maskInside=room==0?hiInside:k->cropLeft(hiInside.render(k),room*k),maskText=room==0?hiText:k->cropLeft(hiText.render(k),room*k);
			if(clearsHollow(mode,strike.face))qd.drawMask(strike.mask(text,true),left,top,mode^2,null,maskInside);
			qd.drawGlyphMask(strike.mask(text),left,top,mode,new int[]{h,v-strike.ascent,advance,strike.fRectHeight},maskText);return advance;}
		String s=hostText(font,text);
		if(s.isEmpty())return 0;
		if((face&TextStyle68k.SYNTHETIC)!=0)font=Fonts68k.styled(font,face);
		if(font instanceof StyledFont68k){ // underline/outline/shadow/condense/extend on a host font: its coverage image, styled, through QuickDraw
			StyledFont68k styled=(StyledFont68k)font;int advance=styled.width(s);
			boolean direct=qd.directPortPixels(tb.api.currentQuickDrawPort())!=null,smooth=(direct||Fonts68k.smoothText)&&qd.currentPortDepth()>1&&!qd.plainGlyphs;
			FontMetrics m=Fonts68k.metrics(styled);int left=h-styled.maskLeft(),top=v-styled.maskAscent();
			if((mode&7)==0||(mode&7)==4)qd.fillRgb(v-m.getAscent(),h,v+m.getDescent(),h+advance,(mode&7)==4?qd.foregroundRgb():qd.backgroundRgb()); // srcCopy / notSrcCopy: the character boxes take the background
			SmoothZoom.Text hiInside=k->styled.coverage(s,true,k),hiText=k->styled.coverage(s,false,k);
			if(clearsHollow(mode,face)){BufferedImage inside=styled.coverage(s,smooth,true);
				if(smooth)qd.drawCoverageMask(inside,left,top,mode^2,null,hiInside);else qd.drawMask(inside,left,top,mode^2,null,hiInside);}
			BufferedImage coverage=styled.coverage(s,smooth);int[] box={h,v-m.getAscent(),advance,m.getAscent()+m.getDescent()};
			if(smooth)qd.drawCoverageMask(coverage,left,top,mode,box,hiText);else qd.drawMask(coverage,left,top,mode,box,hiText);
			return advance;
		}
		int advance=Fonts68k.width(font,s);FontMetrics m=Fonts68k.metrics(font);
		// change tracking covers the glyph box with room for overhang (italics, accents), not the whole clip
		// notSrcOr/notSrcXor/notSrcBic act on the clear pixels of the character boxes: the mask path below does those
		Graphics2D g=qd.plainGlyphs||(mode&~3)==4&&(mode&3)!=0?null:surface(tb.api.currentQuickDrawPort(),null,new int[]{v-m.getAscent()-2,h-m.getAscent(),v+m.getDescent()+2,h+advance+m.getAscent()},false);
		if(g!=null){
			try{
				g.setFont(font);
				int fg=qd.foregroundRgb(),bg=qd.backgroundRgb();
				if((mode&7)==0||(mode&7)==4){/* srcCopy / notSrcCopy: paint background box */g.setColor(new Color((mode&7)==4?fg:bg));g.fillRect(h,v-m.getAscent(),advance,m.getAscent()+m.getDescent());}
				if((mode&7)==2||(mode&7)==6){/* srcXor */g.setXORMode(Color.WHITE);g.setColor(Color.BLACK);}
				else g.setColor(new Color((mode&7)==4?bg:fg));
				Fonts68k.drawString(g,font,s,h,v); // the font may pair a Japanese fallback with a Roman family
			}finally{g.dispose();}
			return advance;
		}
		// Non-direct port (8-bit screen port, GWorld, SetPortPix buffer) or an inverted-source mode: the glyphs go through a mask. With the
		// "always anti-alias" preference the mask carries coverage and partial pixels blend the text colour into the
		// destination; a 1-bit destination cannot show grey, so it keeps the binary mask.
		boolean smooth=Fonts68k.smoothText&&qd.currentPortDepth()>1&&!qd.plainGlyphs;
		// The mask reaches past the advance on both sides: a glyph's ink may overhang it (the last "o" of "Taro" in Osaka 10
		// lost its right side and read "Tarc"); the painters skip empty mask pixels, so the margin draws nothing itself.
		final Font f=font;final int ascent=m.getAscent(),pad=Math.max(2,(m.getAscent()+m.getDescent())/4);BufferedImage mask;
		if(smooth){mask=new BufferedImage(Math.max(1,advance+2*pad),Math.max(1,m.getAscent()+m.getDescent()),BufferedImage.TYPE_BYTE_GRAY);
			Graphics2D mg=mask.createGraphics();mg.setColor(Color.WHITE);mg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);Fonts68k.drawString(mg,f,s,pad,ascent);mg.dispose();}
		else mask=Fonts68k.binaryMask(advance+2*pad,m.getAscent()+m.getDescent(),g2->Fonts68k.drawString(g2,f,s,pad,ascent));
		int[] box={h,v-m.getAscent(),advance,m.getAscent()+m.getDescent()};
		final int maskWidth=advance+2*pad,maskHeight=m.getAscent()+m.getDescent();SmoothZoom.Text hiText=k->Fonts68k.hiResMask(f,s,maskWidth,maskHeight,pad,ascent,k);
		if(smooth)qd.drawCoverageMask(mask,h-pad,v-m.getAscent(),mode,box,hiText);else qd.drawMask(mask,h-pad,v-m.getAscent(),mode,box,hiText);
		return advance;
	}

	/** Java2D over the current port when it is a 32-bit direct port; the caller must dispose it. */
	Graphics2D surface() throws M68kCpuException {return surface(tb.api.currentQuickDrawPort());}
	Graphics2D surface(int port) throws M68kCpuException {return surface(port,null);}
	/** Java2D over the current port only when it is a 32-bit direct port (text and pixel tests keep their QuickDraw paths otherwise). */
	Graphics2D directSurface() throws M68kCpuException {return surface(tb.api.currentQuickDrawPort(),null,false);}
	/**
	 * @param area local {top,left,bottom,right} the caller will draw in (clip and change tracking), or null for the whole port.
	 * Java2D writes straight into the buffer bytes, bypassing the VM's write tracking, so the rows about to be drawn are
	 * marked written here; the host converts only those rows on its next refresh.
	 */
	Graphics2D surface(int port,int[] area) throws M68kCpuException {return surface(port,area,true);}
	/** Set by {@link #alphaSurface}: the 32-bit surface keeps alpha in the pad byte (see MacAppHost.imageOver). */
	private boolean alphaSurface;
	/** A surface whose drawing stores alpha in a 32-bit buffer's pad byte (srcCopy of an image with alpha into a GWorld). */
	Graphics2D alphaSurface(int port,int[] area) throws M68kCpuException {alphaSurface=true;try{return surface(port,area,true);}finally{alphaSurface=false;}}
	Graphics2D surface(int port,int[] area,boolean staged) throws M68kCpuException {return surface(port,area,null,staged);}
	/**
	 * @param touchArea local {top,left,bottom,right} to mark written instead of the whole clip, without clipping the drawing to it
	 * (text: the glyph box with room for overhang). Only the bytes inside the clip are marked, row by row: marking whole rows of
	 * the screen port (a WDEF's title, the INIT icons at startup) set the device's per-byte "written" record across the entire
	 * screen, and a later window adopted those never-drawn device bytes (black) as its own pixels.
	 */
	Graphics2D surface(int port,int[] area,int[] touchArea,boolean staged) throws M68kCpuException {
		flushSurfaces();
		int[] px=qd.directPortPixels(port);
		if(px==null)return staged?stagedSurface(port,area,touchArea):null;
		BufferedImage image=tb.host().imageOver(px[0],px[2],px[3],px[1],alphaSurface);
		if(image==null)return null;
		Graphics2D g=image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
		// pixel (x,y) of the buffer is local (bounds.left+x, bounds.top+y)
		g.translate(-px[5],-px[4]);
		int top=px[4],bottom=px[4]+px[3],left=px[5],right=px[5]+px[2];
		if(port==tb.api.currentQuickDrawPort()&&!frameSurface){int[] c=qd.currentDrawableRect();g.clipRect(c[1],c[0],Math.max(0,c[3]-c[1]),Math.max(0,c[2]-c[0]));java.awt.Shape shape=qd.currentRegionClip();if(shape!=null)g.clip(shape);top=Math.max(top,c[0]);bottom=Math.min(bottom,c[2]);left=Math.max(left,c[1]);right=Math.min(right,c[3]);}
		if(area!=null){g.clipRect(area[1],area[0],Math.max(0,area[3]-area[1]),Math.max(0,area[2]-area[0]));top=Math.max(top,area[0]);bottom=Math.min(bottom,area[2]);left=Math.max(left,area[1]);right=Math.min(right,area[3]);}
		if(touchArea!=null){top=Math.max(top,touchArea[0]);bottom=Math.min(bottom,touchArea[2]);left=Math.max(left,touchArea[1]);right=Math.min(right,touchArea[3]);}
		if(bottom>top&&right>left){int[] span={px[0]+(top-px[4])*px[1]+(left-px[5])*4,px[1],bottom-top,(right-left)*4};touchRows(span);drawn.add(span);}
		return smoothSurface(g,port,px[0],px[1],px[2],px[3],32,px[5],px[4],0,0,left,top,right,bottom);
	}
	/**
	 * With the smooth zoom on, the graphics that draw into the buffer's plane as well ({@link SmoothGraphics}), else {@code g}: the
	 * plane is pinned and brought up to date over the local {@code left..right x top..bottom} the drawing may change, and
	 * {@link #flushSurfaces} commits it. {@code originX/Y}: the buffer pixel at {@code g}'s device origin (a staged image's corner).
	 */
	private Graphics2D smoothSurface(Graphics2D g,int port,int base,int rowBytes,int width,int height,int depth,int boundsLeft,int boundsTop,int originX,int originY,int left,int top,int right,int bottom) throws M68kCpuException {
		SmoothZoom smooth=qd.smooth;if(!smooth.active()||bottom<=top||right<=left)return g;
		SmoothZoom.Plane p=smooth.plane(memory.physical(base),rowBytes,width,height,depth,true);if(p==null)return g;
		int x0=Math.max(0,left-boundsLeft),y0=Math.max(0,top-boundsTop),x1=Math.min(width,right-boundsLeft),y1=Math.min(height,bottom-boundsTop);if(x1<=x0||y1<=y0)return g;
		int[] colors=depth<=8?qd.smoothColors(p,portPalette(port,depth)):null;
		long[] pin=p.pin(y0,y1);
		try{p.sync(x0,y0,x1,y1,colors,SmoothZoom.DRAW);}catch(RuntimeException e){p.unpin(pin);throw e;}
		java.awt.geom.AffineTransform toHi=java.awt.geom.AffineTransform.getScaleInstance(p.s,p.s);toHi.translate(originX,originY);
		SmoothGraphics sg=new SmoothGraphics(g,p.graphics(),toHi,p.s,smooth.algorithm());
		smoothSurfaces.add(new SmoothSurface(p,pin,new int[]{x0,y0,x1,y1},sg));
		return sg;
	}
	/** A plane drawn into through a {@link SmoothGraphics} until the next {@link #flushSurfaces}. */
	private static final class SmoothSurface {final SmoothZoom.Plane plane;final long[] pin;final int[] rect;final SmoothGraphics graphics;
		SmoothSurface(SmoothZoom.Plane plane,long[] pin,int[] rect,SmoothGraphics graphics){this.plane=plane;this.pin=pin;this.rect=rect;this.graphics=graphics;}}
	private final java.util.List<SmoothSurface> smoothSurfaces=new java.util.ArrayList<SmoothSurface>();
	/** Marks a row span {first row address, rowBytes, rows, bytes per row} written. */
	private void touchRows(int[] span){for(int y=0,address=span[0];y<span[2];y++,address+=span[1])memory.touch(address,span[3]);}
	/**
	 * Rows handed out by {@link #surface} for direct drawing. They are marked written BEFORE the drawing (so the host
	 * refresh picks them up) and again in {@link #flushSurfaces} AFTER it: the refresh runs on another thread and can
	 * convert the rows while Java2D is still painting them; without the second mark the finished rows would never be
	 * converted again (a script editor's lower half stayed white after a Return).
	 */
	private final java.util.List<int[]> drawn=new java.util.ArrayList<int[]>();

	/**
	 * Java2D over a port whose buffer is not 32-bit (the windows of a 16-bit or indexed hosted screen): the rows
	 * about to be drawn are staged as an RGB image, and {@link #flushSurfaces} converts them back into the buffer
	 * (RGB 5:5:5 words, or the nearest colour-table index) at the end of the trap. Java2D cannot alias such buffers.
	 */
	private static final class StagedSurface {final int port,base,rowBytes,width,depth,top,bottom,bufferRow,bufferColumn;final BufferedImage image;int[] clut;
		/** {@code bufferRow}/{@code bufferColumn}: the buffer row and pixel column of the staged image's top-left, fixed at staging time (a SetOrigin by guest code that runs meanwhile moves local coordinates, not the buffer). */
		StagedSurface(int port,int base,int rowBytes,int width,int depth,int top,int bottom,int bufferRow,int bufferColumn,BufferedImage image){this.port=port;this.base=base;this.rowBytes=rowBytes;this.width=width;this.depth=depth;this.top=top;this.bottom=bottom;this.bufferRow=bufferRow;this.bufferColumn=bufferColumn;this.image=image;}}
	private final java.util.List<StagedSurface> staged=new java.util.ArrayList<StagedSurface>();
	/** Stages the clip's rows and columns only (see {@link #surface(int,int[],int[],boolean)}); {@code touchArea} narrows the staged extent without clipping. */
	private Graphics2D stagedSurface(int port,int[] area,int[] touchArea) throws M68kCpuException {
		int[] px=qd.colorPortPixels(port);if(px==null||px[6]==32)return null;
		int base=px[0],rowBytes=px[1],w=px[2],h=px[3],boundsTop=px[4],boundsLeft=px[5],depth=px[6];
		int top=boundsTop,bottom=boundsTop+h,left=boundsLeft,right=boundsLeft+w;int[] clip=null;
		if(port==tb.api.currentQuickDrawPort()&&!frameSurface){clip=qd.currentDrawableRect();top=Math.max(top,clip[0]);bottom=Math.min(bottom,clip[2]);left=Math.max(left,clip[1]);right=Math.min(right,clip[3]);}
		if(area!=null){top=Math.max(top,area[0]);bottom=Math.min(bottom,area[2]);left=Math.max(left,area[1]);right=Math.min(right,area[3]);}
		if(touchArea!=null){top=Math.max(top,touchArea[0]);bottom=Math.min(bottom,touchArea[2]);left=Math.max(left,touchArea[1]);right=Math.min(right,touchArea[3]);}
		if(bottom<=top||right<=left)return null;
		// indexed pixels share bytes: stage whole bytes so the write-back never splits one
		int pixelsPerByte=depth<=8?8/depth:1;left=boundsLeft+(left-boundsLeft)/pixelsPerByte*pixelsPerByte;right=Math.min(boundsLeft+w,boundsLeft+(right-boundsLeft+pixelsPerByte-1)/pixelsPerByte*pixelsPerByte);
		int rows=bottom-top,columns=right-left,column=left-boundsLeft;byte[] raw=memory.raw();int rowStart=base+(top-boundsTop)*rowBytes;
		if(rowStart<0||rowStart+(long)rows*rowBytes>raw.length)return null;
		BufferedImage image=new BufferedImage(columns,rows,BufferedImage.TYPE_INT_RGB);int[] pixels=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
		int[] clut=depth<=8?portPalette(port,depth):null;
		for(int y=0;y<rows;y++)MacAppHost.readPixels(raw,rowStart+y*rowBytes+(column*depth>>>3),depth,clut,columns,pixels,y*columns);
		Graphics2D g=image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
		g.translate(-left,-top); // pixel (x,y) of the staged image is local (left+x, top+y)
		if(clip!=null){g.clipRect(clip[1],clip[0],Math.max(0,clip[3]-clip[1]),Math.max(0,clip[2]-clip[0]));java.awt.Shape shape=qd.currentRegionClip();if(shape!=null)g.clip(shape);} // a clip or visRgn that is not a rectangle, as QuickDraw's own drawing
		if(area!=null)g.clipRect(area[1],area[0],Math.max(0,area[3]-area[1]),Math.max(0,area[2]-area[0]));
		StagedSurface surface=new StagedSurface(port,base,rowBytes,columns,depth,top,bottom,top-boundsTop,column,image);surface.clut=clut;staged.add(surface);
		if(tb.hosted()&&memory.deviceWritten())tb.host().adoptDeviceWrites(port); // the program's direct screen writes go under the drawing, as for QuickDraw
		return smoothSurface(g,port,base,rowBytes,w,h,depth,boundsLeft,boundsTop,column,top-boundsTop,left,top,right,bottom);
	}
	/** The colours an indexed port's pixel values stand for: its PixMap's own table (an offscreen GWorld may carry one unlike the
	 *  screen's - red text in an 8-bit GWorld came out purple and olive through the screen's table), else the main device's. */
	private int[] portPalette(int port,int depth) throws M68kCpuException {int[] own=qd.colorPortPalette(port);return own!=null&&own.length==1<<depth?own:tb.host().devicePalette(depth);}
	/** Writes staged surfaces back into their buffers; called before a new surface is handed out and after every trap. */
	void flushSurfaces() throws M68kCpuException {
		if(!drawn.isEmpty()){for(int[] d:drawn)touchRows(d);drawn.clear();}
		if(!staged.isEmpty())flushStaged();
		// the planes drawn into take the buffers' new pixels as what their hi-res blocks stand for
		if(!smoothSurfaces.isEmpty()){java.util.List<SmoothSurface> done=new java.util.ArrayList<SmoothSurface>(smoothSurfaces);smoothSurfaces.clear();
			for(SmoothSurface s:done){try{s.plane.commit(s.rect[0],s.rect[1],s.rect[2],s.rect[3],null,s.graphics.drew());}finally{s.plane.unpin(s.pin);}}}
	}
	private void flushStaged() throws M68kCpuException {
		for(StagedSurface s:staged){
			int[] pixels=((java.awt.image.DataBufferInt)s.image.getRaster().getDataBuffer()).getData();byte[] raw=memory.raw();
			int[] now=qd.colorPortPixels(s.port);int rows=s.bottom-s.top;
			if(now==null||now[0]!=s.base||now[1]!=s.rowBytes||s.bufferRow+rows>now[3]){ // the buffer was replaced while the drawing was staged (a resize by guest code that ran meanwhile)
				if(Mac68kApi.TRACE)System.err.println("[68K] staged surface dropped: port $"+Integer.toHexString(s.port)+" base $"+Integer.toHexString(s.base)+" rows "+s.top+".."+s.bottom);
				continue;}
			int rowStart=s.base+s.bufferRow*s.rowBytes+(s.bufferColumn*s.depth>>>3),rowLength=(s.width*s.depth+7)>>>3;
			if(s.bufferColumn+s.width>now[2]){if(Mac68kApi.TRACE)System.err.println("[68K] staged surface dropped: port $"+Integer.toHexString(s.port)+" columns "+s.bufferColumn+"+"+s.width+" > "+now[2]);continue;}
			int[] clut=s.clut;java.util.HashMap<Integer,Integer> nearest=new java.util.HashMap<Integer,Integer>();
			boolean[] animated=s.depth==8&&clut!=null&&clut.length==256?qd.animatedEntries():null;
			for(int y=0;y<rows;y++){int o=rowStart+y*s.rowBytes,p=y*s.width;
				if(s.depth==16){for(int x=0;x<s.width;x++,o+=2){int rgb=pixels[p+x];int v=(rgb>>>19&31)<<10|(rgb>>>11&31)<<5|(rgb>>>3&31);raw[o]=(byte)(v>>>8);raw[o+1]=(byte)v;}}
				else{for(int x=0;x<s.width;x++){int rgb=pixels[p+x]&0xffffff;int bit=x*s.depth,b=o+(bit>>>3),shift=8-s.depth-(bit&7),mask=((1<<s.depth)-1)<<shift;
					// a pixel still showing its entry's colour keeps that entry (an animated one goes on animating)
					if(clut!=null){int old=(raw[b]&mask)>>>shift;if(old<clut.length&&(clut[old]&0xffffff)==rgb)continue;}
					Integer index=nearest.get(rgb);if(index==null){index=nearestIndex(clut,rgb);if(animated!=null&&animated[index])index=QuickDraw68k.searchSteadyColor(clut,rgb,animated);nearest.put(rgb,index);}
					raw[b]=(byte)((raw[b]&~mask)|(index<<shift&mask));}}
			}
			for(int y=0;y<rows;y++)memory.touch(rowStart+y*s.rowBytes,rowLength);
		}
		staged.clear();
	}
	private static int nearestIndex(int[] clut,int rgb){
		return clut==null||clut.length==0?0:QuickDraw68k.searchColor(clut,rgb);
	}

	/**
	 * While DrawControlInCurrentPort runs: the port controls draw into instead of their windows (0 otherwise). PowerPlant
	 * programs draw a window's controls into an offscreen GWorld and CopyBits it to the window (DragThing 4.5's dialogs came
	 * up blank: the controls went to the window and the copy covered them).
	 */
	int controlPort;
	/** The port a control of {@code window} draws in: the window's, or DrawControlInCurrentPort's current port. */
	int controlPort(int window){return controlPort!=0?controlPort:window;}
	/** While {@link #frameSurface(int,int[])} hands out a surface: the port's own clip does not apply. */
	private boolean frameSurface;
	/** Java2D over a window's whole buffer (content and the frame a custom-framed window's buffer covers) within {@code area}
	 *  (local), whether or not the window is the current port: a Window Manager definition drawing the frame outside the content. */
	Graphics2D frameSurface(int window,int[] area) throws M68kCpuException {frameSurface=true;try{return surface(window,area);}finally{frameSurface=false;}}
	/** Anti-aliasing for Java2D drawing into {@code port}: off when its pixels are 1 bit deep, where the grey edge pixels of a
	 *  1-pixel line round to white (MacPaint's OK button lost its frame on the compact-Mac screen). */
	Object antialiasHint(int port) throws M68kCpuException {
		int[] px=qd.colorPortPixels(port);int depth=px!=null?px[6]:qd.screenDepth();
		return depth==1?RenderingHints.VALUE_ANTIALIAS_OFF:RenderingHints.VALUE_ANTIALIAS_ON;
	}
	/** Java2D over a window's port regardless of the current port (clipped to the window). */
	Graphics2D windowSurface(int window) throws M68kCpuException {return surface(window,null);}
	/** Java2D over the port a window's controls draw in (see {@link #controlPort}), clipped to the local rectangle {top,left,bottom,right} the caller draws in. */
	Graphics2D windowSurface(int window,int top,int left,int bottom,int right) throws M68kCpuException {return surface(controlPort(window),new int[]{top,left,bottom,right});}

	private boolean pixelIsBlack(int h,int v) throws M68kCpuException {
		Graphics2D g=directSurface();
		if(g!=null){g.dispose();int port=tb.api.currentQuickDrawPort();int[] px=qd.directPortPixels(port);int x=h-px[5],y=v-px[4];if(x<0||y<0||x>=px[2]||y>=px[3])return false;int address=px[0]+y*px[1]+x*4;int rgb=memory.readByte(address+1)<<16|memory.readByte(address+2)<<8|memory.readByte(address+3);return (299*(rgb>>>16&255)+587*(rgb>>>8&255)+114*(rgb&255))<127500;}
		// any other port (the card window is the screen at the card's place, often 8-bit): its pixel as GetCPixel reads it
		int rgb=qd.currentPortRgb(h,v);
		return rgb>=0&&(299*(rgb>>>16&255)+587*(rgb>>>8&255)+114*(rgb&255))<127500;
	}

	void drawTextRun(int text,int length) throws M68kCpuException {if(length>0)drawBytes(memory.readBytes(text,length));}
	void measureRun(int text,int length,int widths) throws M68kCpuException {
		if(widths==0)return;byte[] bytes=length>0?memory.readBytes(text,length):new byte[0];
		int[] w=prefixWidths(currentFont(),bytes);
		for(int i=0;i<=bytes.length;i++)memory.writeWord(widths+i*2,w[i]);
	}
	/** Pixel width of every byte prefix of a run (index i = the first i bytes; a prefix that splits a two-byte character measures
	 *  like the one before it), summed character by character: measuring each prefix as a whole string made MeasureText and
	 *  PixelToChar quadratic, and iCab lays a page out with thousands of PixelToChar calls over its text runs. */
	private int[] prefixWidths(Font font,byte[] bytes){
		int[] w=new int[bytes.length+1];int i=0,total=0;
		while(i<bytes.length){int n=tb.encoding==StackTextEncoding.JAPANESE&&Toolbox68k.isJapaneseLead(bytes[i]&0xff)&&i+1<bytes.length?2:1;
			total+=width(font,java.util.Arrays.copyOfRange(bytes,i,i+n));
			if(n==2)w[i+1]=w[i];w[i+n]=total;i+=n;}
		return w;
	}
	/** Byte prefix length that does not split a two-byte character. */
	private int prefixLength(byte[] bytes,int n){
		if(tb.encoding!=StackTextEncoding.JAPANESE)return n;
		int i=0;while(i<n){int c=bytes[i]&0xff;if(Toolbox68k.isJapaneseLead(c)){if(i+1>=n)return i;i+=2;}else i++;}return n;
	}
	/**
	 * {offset, leadingEdge, remainingWidth} for a pixel position in a text run, as PixelToChar answers: inside the text the
	 * remaining width is -1 and leadingEdge says which half of a character the position is on (FALSE: the trailing half of
	 * the character before offset); past the end it is the length, leadingEdge TRUE and the width left over. Line layout code
	 * relies on both: WAV takes offset-1 when leadingEdge is FALSE (the last character of every line was left out) and
	 * breaks the line only when the remaining width is -1 (with 0 it never wrapped and crashed at the right margin).
	 */
	int[] pixelToChar(int text,int length,int pixel) throws M68kCpuException {
		byte[] bytes=length>0?memory.readBytes(text,length):new byte[0];
		if(bytes.length==0)return new int[]{0,1,Math.max(0,pixel)};
		if(pixel<=0)return new int[]{0,1,-1};
		int[] widths=prefixWidths(currentFont(),bytes);int previous=0;
		for(int i=1;i<=bytes.length;i++){
			int p=prefixLength(bytes,i);if(p!=i)continue;
			int w=widths[i];
			if(pixel<w){int mid=(previous+w)/2;return pixel<mid?new int[]{prefixStart(bytes,i),1,-1}:new int[]{i,0,-1};}
			previous=w;
		}
		return new int[]{bytes.length,1,pixel-previous};
	}
	private int prefixStart(byte[] bytes,int end){int i=0,start=0;while(i<end){start=i;int c=bytes[i]&0xff;if(tb.encoding==StackTextEncoding.JAPANESE&&Toolbox68k.isJapaneseLead(c))i+=2;else i++;}return start;}
	int charToPixel(int text,int length,int offset) throws M68kCpuException {
		if(offset<=0||length<=0)return 0;byte[] bytes=memory.readBytes(text,Math.min(length,offset));return width(currentFont(),bytes);
	}
	int truncString(int s,int width) throws M68kCpuException {return truncString(s,width,0);}
	/** TruncString(width, VAR string, truncWhere): 0 = fits, 1 = truncated with an ellipsis at the end (truncEnd 0) or in the middle (truncMiddle $4000). */
	int truncString(int s,int width,int truncWhere) throws M68kCpuException {
		byte[] cut=truncate(memory.readBytes(s+1,memory.readByte(s)),width,truncWhere);
		if(cut==null)return 0;
		memory.writeByte(s,cut.length);memory.load(s+1,cut);return 1;
	}
	/** TruncText(width, textPtr, VAR length, truncWhere): the same on a run of bytes in the program's encoding (UxTruncText XFCN). */
	int truncText(int text,int lengthPtr,int width,int truncWhere) throws M68kCpuException {
		int length=(short)memory.readWord(lengthPtr);if(length<0)return -1;
		byte[] cut=truncate(memory.readBytes(text,length),width,truncWhere);
		if(cut==null)return 0;
		memory.load(text,cut);memory.writeWord(lengthPtr,cut.length);return 1;
	}
	/**
	 * The text shortened with an ellipsis to fit {@code width} in the current font, null when it already fits; whole characters
	 * (a Japanese character's two bytes) go. The ellipsis is the script's one-byte token: $C9 in Mac OS Roman, $FF under
	 * KanjiTalk (the UxTruncText stack keeps a real Mac's "あいうえ" $FF "すせそ": the two-byte "…" it got here cost a kanji).
	 */
	private byte[] truncate(byte[] text,int width,int truncWhere) throws M68kCpuException {
		Font font=currentFont();
		if(width(font,text)<=width)return null;
		byte[] dots={(byte)(tb.encoding==StackTextEncoding.JAPANESE?0xff:0xc9)};
		java.util.List<int[]> chars=characters(text);int n=chars.size();
		if((truncWhere&0xffff)==0x4000||truncWhere==1){ // truncMiddle ($4000 in the Script Manager's TruncCode; Rinaldi's Truncate passed it and got the end cut): keep both ends
			int head=n/2,tail=n-head;
			while(head+tail>0&&width(font,joined(text,chars,head,tail,dots))>width){if(head>=tail)head--;else tail--;}
			return joined(text,chars,head,tail,dots);}
		int head=n;
		while(head>0&&width(font,joined(text,chars,head,0,dots))>width)head--;
		return joined(text,chars,head,0,dots);
	}
	/** The first {@code head} and last {@code tail} characters of {@code text} with {@code dots} between them. */
	private static byte[] joined(byte[] text,java.util.List<int[]> chars,int head,int tail,byte[] dots){
		int headEnd=head==0?0:chars.get(head-1)[0]+chars.get(head-1)[1],tailStart=tail==0?text.length:chars.get(chars.size()-tail)[0];
		byte[] out=new byte[headEnd+dots.length+text.length-tailStart];
		System.arraycopy(text,0,out,0,headEnd);System.arraycopy(dots,0,out,headEnd,dots.length);System.arraycopy(text,tailStart,out,headEnd+dots.length,text.length-tailStart);
		return out;
	}

	/**
	 * FMSwapFont(FMInput): the FMOutput record (IM Text 4-67) of the font: no font record Handle (the glyphs live on the
	 * host side), the style factors QuickDraw would apply (boldPixels 6, italicPixels 7, ulOffset 8, ulShadow 9, ulThick 10,
	 * shadowPixels 11, extra 12), the metrics (ascent 13, descent 14, widMax 15, leading 16), curStyle 17, numer/denom 18/22.
	 */
	/** The JSwapFont vector ($8E0): FMSwapFont entered by a jump, with the same stack as the trap. */
	void swapFont(M68kCpu cpu) throws M68kCpuException {fmSwapFont(cpu);}
	/** Handles FMSwapFont gave out for strikes, one per strike. */
	private final java.util.IdentityHashMap<byte[],Integer> strikeHandles=new java.util.IdentityHashMap<byte[],Integer>();
	private boolean fmSwapFont(M68kCpu cpu) throws M68kCpuException {
		int input=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		int family=(short)memory.readWord(input),size=(short)memory.readWord(input+2),face=memory.readByte(input+4);
		if(Mac68kApi.TRACE)System.err.println("[68K] FMSwapFont family "+family+" size "+size+" face "+face+" numer "+memory.readWord(input+10)+"/"+memory.readWord(input+8)+" denom "+memory.readWord(input+14)+"/"+memory.readWord(input+12)+" fract "+memory.readByte(0xbf4)+" scaleDisable "+memory.readByte(0xa63));
		if(fmOutput==0)fmOutput=memory.allocate(28,4);
		Font font=Fonts68k.font(family,size,face);int[] fi=Fonts68k.fontInfo(font);
		int synthesized=font instanceof BitmapFont68k?((BitmapFont68k)font).face:face;
		memory.writeWord(fmOutput,0);memory.writeLong(fmOutput+2,0);
		memory.writeByte(fmOutput+6,(synthesized&TextStyle68k.BOLD)!=0?1:0);memory.writeByte(fmOutput+7,(synthesized&TextStyle68k.ITALIC)!=0?1:0);
		memory.writeByte(fmOutput+8,1);memory.writeByte(fmOutput+9,1);memory.writeByte(fmOutput+10,1);memory.writeByte(fmOutput+11,(synthesized&TextStyle68k.SHADOW)!=0?1:0);
		memory.writeByte(fmOutput+12,TextStyle68k.extra(face));memory.writeByte(fmOutput+13,fi[0]);memory.writeByte(fmOutput+14,fi[1]);memory.writeByte(fmOutput+15,fi[2]);memory.writeByte(fmOutput+16,fi[3]);
		memory.writeByte(fmOutput+17,face);memory.writeWord(fmOutput+18,1);memory.writeWord(fmOutput+20,1);memory.writeWord(fmOutput+22,1);memory.writeWord(fmOutput+24,1);
		// fontHandle: the strike itself when the glyphs come from a 'FONT'/'NFNT' (Microsoft BASIC draws text from it), with the
		// scale the Font Manager applies to it in numer/denom; NIL for fonts drawn from outlines
		if(font instanceof BitmapFont68k){BitmapFont68k strike=(BitmapFont68k)font;byte[] data=strike.strikeData();
			if(data!=null){Integer h=strikeHandles.get(data);if(h==null){h=tb.api.createHandle(data);strikeHandles.put(data,h);}memory.writeLong(fmOutput+2,h);
				int from=strike.strikeSize();if(from>0&&from!=strike.size){memory.writeWord(fmOutput+18,strike.size);memory.writeWord(fmOutput+20,strike.size);memory.writeWord(fmOutput+22,from);memory.writeWord(fmOutput+24,from);}}}
		widthTable(font,family,size,face); // the ROM rebuilds WidthTabHandle for the swapped-in font
		memory.writeLong(cpu.a[7],fmOutput);return true;
	}

	/**
	 * The Font Manager's width table (IM Text 4-71 WidthTable, 1072 bytes) for a font: tabData 256 Fixed advances (-1 for a
	 * missing character), then tabFont/sExtra/style/fID/fSize/face/device/inNumer/inDenom/aFID/fHand/usedFam/aFace/vOutput/
	 * hOutput/vFactor/hFactor/aSize/tabSize. Text-layout code reads it through FontMetrics' wTabHandle or
	 * the WidthTabHandle global ($B2A) instead of calling CharWidth per character; one Handle, rebuilt for the current font
	 * like the ROM's.
	 */
	private int widthTableHandle;private String widthTableKey;
	private void currentWidthTable() throws M68kCpuException {int[] t=qd.textState();widthTable(currentFont(),t[0],t[3],t[1]);}
	private int widthTable(Font font,int family,int size,int face) throws M68kCpuException {
		if(widthTableHandle==0){widthTableHandle=tb.api.createHandle(new byte[1072]);}
		String key=family+"/"+Fonts68k.substitute(family)+"/"+size+"/"+face; // a font substitution changed meanwhile: other widths
		int table=memory.readLong(widthTableHandle);
		if(!key.equals(widthTableKey)||table==0){
			if(table==0){tb.api.loadHandle(widthTableHandle,new byte[1072]);table=memory.readLong(widthTableHandle);}
			for(int c=0;c<256;c++)memory.writeLong(table+c*4,width(font,new byte[]{(byte)c})<<16);
			// style = the style extra as Fixed; vOutput/hOutput are FOutNumer (FOutDenom is always 256) and vFactor/hFactor the
			// FScaleV/HFact scale in 8.8, so 256 each means "1.0" (ROM FontMgr.a SetUpScale/GoScaleLess). Word 5.1 scales the
			// advances by them: with the old size/1 its selection highlight covered a tenth of the line.
			memory.writeLong(table+1024,0);memory.writeLong(table+1028,0);memory.writeLong(table+1032,TextStyle68k.extra(face)<<16);
			memory.writeWord(table+1036,family);memory.writeWord(table+1038,size);memory.writeWord(table+1040,face&0xff);memory.writeWord(table+1042,0);
			memory.writeLong(table+1044,0x00010001);memory.writeLong(table+1048,0x00010001);memory.writeWord(table+1052,family);memory.writeLong(table+1054,0);
			memory.writeByte(table+1058,1);memory.writeByte(table+1059,face&0xff);memory.writeWord(table+1060,256);memory.writeWord(table+1062,256);
			memory.writeWord(table+1064,256);memory.writeWord(table+1066,256);memory.writeWord(table+1068,size);memory.writeWord(table+1070,1072);
			widthTableKey=key;
		}
		memory.writeLong(0xb2a,widthTableHandle);memory.writeLong(0xb10,table); // WidthTabHandle, WidthPtr
		return widthTableHandle;
	}

	/** FontDispatch ($A854): the selector is D0's low byte (Mac OS 8.5's calls $11-$14 were taken for $1-$4 by a 4-bit mask and
	 *  left their arguments on the stack; DragThing's FetchFontInfo came back all zeros and its dock's layer tabs 8 pixels high). */
	private boolean fontDispatch(M68kCpu cpu) throws M68kCpuException {
		int selector=cpu.d[0]&0xff,sp=cpu.a[7];
		switch(selector){
			case 0:{cpu.a[7]+=8;Font f=currentFont(); // IsOutline(numer, denom: Point): two Points (Word 5.1's stack was 4 bytes off after it)
				// false for an installed strike of the size (the Font Manager draws the bitmap): A Smaller GIF J measured Osaka 12
				// with OutlineMetrics, counted its Japanese text in bytes and summed the unwritten tail entries into its line breaks
				tb.writeBoolean(cpu.a[7],!(f instanceof BitmapFont68k)||((BitmapFont68k)f).drawnFromOutlines());return true;}
			case 1:case 0xa:cpu.a[7]+=2;return true;                        // SetOutlinePreferred, SetPreserveGlyph(Boolean)
			case 9:case 0xb:tb.writeBoolean(cpu.a[7],true);return true;     // GetOutlinePreferred, GetPreserveGlyph
			case 8:return outlineMetrics(cpu);
			case 0xc:memory.writeWord(cpu.a[7],0);return true;              // FlushFonts
			case 0x11:cpu.a[7]+=4;memory.writeLong(cpu.a[7],0);return true; // SetAntiAliasedTextEnabled(Boolean, minFontSize): OSStatus
			case 0x12:{int min=memory.readLong(sp);cpu.a[7]+=4;if(min!=0)memory.writeWord(min,9); // IsAntiAliasedTextEnabled(*minFontSize): Boolean
				tb.writeBoolean(cpu.a[7],Fonts68k.textAntialiasHint()==RenderingHints.VALUE_TEXT_ANTIALIAS_ON);return true;}
			case 0x13:{int bounds=memory.readLong(sp),text=memory.readLong(sp+4),count=(short)memory.readWord(sp+8);cpu.a[7]+=10; // QDTextBounds(byteCount, textAddr, *bounds): the text's box around the pen at 0,0
				if(bounds!=0){Font f=currentFont();int[] fi=Fonts68k.fontInfo(f);int w=count>0&&text!=0?width(f,memory.readBytes(text,count)):0;
					memory.writeWord(bounds,-fi[0]);memory.writeWord(bounds+2,0);memory.writeWord(bounds+4,fi[1]);memory.writeWord(bounds+6,w);}
				return true;}
			case 0x14:{int info=memory.readLong(sp),face=memory.readWord(sp+4)&0x7f,size=(short)memory.readWord(sp+6),id=(short)memory.readWord(sp+8);cpu.a[7]+=10; // FetchFontInfo(fontID, fontSize, fontStyle, *info): OSErr
				if(info!=0){int[] fi=Fonts68k.fontInfo(fontFor(id,size<=0?12:size,face));for(int i=0;i<4;i++)memory.writeWord(info+i*2,fi[i]);}
				memory.writeWord(cpu.a[7],0);return true;}
			default:return true;
		}
	}

	/**
	 * OutlineMetrics(byteCount, textPtr, numer, denom, VAR yMax, VAR yMin, awArray, lsbArray, boundsArray): per character of
	 * the text in the port's font (scaled by numer/denom), the advance width and left side bearing as Fixed and the glyph
	 * bounds as a Rect relative to the pen (QuickDraw's y grows downward: top is negative above the baseline); yMax/yMin are
	 * the extremes above and below the baseline (positive up). Measured with the host font's glyph metrics.
	 */
	private boolean outlineMetrics(M68kCpu cpu) throws M68kCpuException {
		int sp=cpu.a[7],boundsArray=memory.readLong(sp),lsbArray=memory.readLong(sp+4),awArray=memory.readLong(sp+8),yMinPtr=memory.readLong(sp+12),yMaxPtr=memory.readLong(sp+16);
		int denom=memory.readLong(sp+20),numer=memory.readLong(sp+24),text=memory.readLong(sp+28),count=(short)memory.readWord(sp+32);cpu.a[7]+=34;
		double scaleV=denom>>16==0?1:(double)(short)(numer>>16)/(short)(denom>>16),scaleH=(short)denom==0?1:(double)(short)numer/(short)denom;
		if(count<0||text==0){memory.writeWord(cpu.a[7],-50);return true;}
		// An element per character (Gif•gIf•giF advances its style runs by the first charCount elements). An installed strike
		// advances by its own widths, which is how StdText draws it (Java's stand-in font measured Osaka text differently).
		byte[] bytes=memory.readBytes(text,count);Font font=currentFont();boolean jp=tb.encoding==StackTextEncoding.JAPANESE;
		java.awt.font.FontRenderContext frc=new java.awt.font.FontRenderContext(null,true,true);
		double yMax=0,yMin=0;int element=0;
		for(int i=0;i<count;){
			int n=jp&&Toolbox68k.isJapaneseLead(bytes[i]&255)&&i+1<count?2:1;byte[] ch=java.util.Arrays.copyOfRange(bytes,i,i+n);String s=tb.decodeFor(font,ch);
			java.awt.font.GlyphVector gv=s.isEmpty()?null:font.createGlyphVector(frc,s.substring(0,1));double advance,lsb;java.awt.geom.Rectangle2D b;
			if(gv!=null&&gv.getNumGlyphs()>0){java.awt.font.GlyphMetrics gm=gv.getGlyphMetrics(0);advance=gm.getAdvance();lsb=gm.getLSB();b=gm.getBounds2D();}
			else{advance=s.isEmpty()?0:Fonts68k.metrics(font).charWidth(s.charAt(0));lsb=0;b=new java.awt.geom.Rectangle2D.Double(0,0,0,0);}
			if(font instanceof BitmapFont68k)advance=((BitmapFont68k)font).width(ch);
			advance*=scaleH;lsb*=scaleH;
			int top=(int)Math.floor(b.getY()*scaleV),bottom=(int)Math.ceil((b.getY()+b.getHeight())*scaleV),left=(int)Math.floor(b.getX()*scaleH),right=(int)Math.ceil((b.getX()+b.getWidth())*scaleH);
			if(b.getWidth()>0){yMax=Math.max(yMax,-top);yMin=Math.min(yMin,-bottom);}
			int e=element++;
			if(awArray!=0)memory.writeLong(awArray+e*4,(int)Math.round(advance*65536));
			if(lsbArray!=0)memory.writeLong(lsbArray+e*4,(int)Math.round(lsb*65536));
			if(boundsArray!=0){memory.writeWord(boundsArray+e*8,top);memory.writeWord(boundsArray+e*8+2,left);memory.writeWord(boundsArray+e*8+4,bottom);memory.writeWord(boundsArray+e*8+6,right);}
			i+=n;
		}
		if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder();for(int i=0;i<element&&awArray!=0;i++)sb.append(' ').append(memory.readLong(awArray+i*4)/65536.0);
			System.err.println("[68K] OutlineMetrics \""+tb.decode(bytes)+"\" font "+font.getFontName()+"/"+font.getSize()+" numer $"+Integer.toHexString(numer)+" denom $"+Integer.toHexString(denom)+" ->"+sb);}
		if(yMaxPtr!=0)memory.writeWord(yMaxPtr,(int)Math.round(yMax));if(yMinPtr!=0)memory.writeWord(yMinPtr,(int)Math.round(yMin));
		memory.writeWord(cpu.a[7],0);return true;
	}

	// ------------------------------------------------------------------ geometry helpers

	/** MapRgn(rgn, srcRect, dstRect): every rectangle of the shape mapped (After Dark's When dialog maps the GrayRgn to a miniature screen). */
	private boolean mapRegion(M68kCpu cpu) throws M68kCpuException {
		int dst=memory.readLong(cpu.a[7]),src=memory.readLong(cpu.a[7]+4),rgn=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;
		if(rgn==0||memory.readLong(rgn)==0)return true;
		QdRegion shape=qd.regionShapeOf(rgn),mapped=QdRegion.empty();
		int scratch=memory.allocate(8,2);
		try{
			for(int[] r:shape.rects()){
				memory.writeWord(scratch,r[0]);memory.writeWord(scratch+2,r[1]);memory.writeWord(scratch+4,r[2]);memory.writeWord(scratch+6,r[3]);
				mapPoint(scratch,src,dst,false);mapPoint(scratch+4,src,dst,false);
				int t=(short)memory.readWord(scratch),l=(short)memory.readWord(scratch+2),b=(short)memory.readWord(scratch+4),rr=(short)memory.readWord(scratch+6);
				if(b>t&&rr>l)mapped=QdRegion.combine(mapped,QdRegion.rect(t,l,b,rr),QdRegion.UNION);
			}
		}finally{memory.free(scratch);}
		qd.setRegion(rgn,mapped);
		return true;
	}

	/** PtToAngle: degrees 0-359 clockwise from 12 o'clock round the rect's centre, scaled to the rect's proportions (a corner
	 *  of any rect is at a multiple of 45). */
	int ptToAngle(int rect,int v,int h) throws M68kCpuException {
		int top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
		double dh=h-(left+right)/2.0,dv=v-(top+bottom)/2.0;
		if(right>left&&bottom>top){dh/=right-left;dv/=bottom-top;}
		if(dh==0&&dv==0)return 0;
		double a=Math.toDegrees(Math.atan2(dh,-dv));if(a<0)a+=360;
		return (int)Math.round(a)%360;
	}

	private void mapPoint(int pt,int src,int dst,boolean scaleOnly) throws M68kCpuException {
		int v=(short)memory.readWord(pt),h=(short)memory.readWord(pt+2);
		int st=(short)memory.readWord(src),sl=(short)memory.readWord(src+2),sb=(short)memory.readWord(src+4),sr=(short)memory.readWord(src+6);
		int dt=(short)memory.readWord(dst),dl=(short)memory.readWord(dst+2),db=(short)memory.readWord(dst+4),dr=(short)memory.readWord(dst+6);
		int sw=Math.max(1,sr-sl),sh=Math.max(1,sb-st),dw=dr-dl,dh=db-dt;
		if(scaleOnly){memory.writeWord(pt,v*dh/sh);memory.writeWord(pt+2,h*dw/sw);}
		else{memory.writeWord(pt,dt+(v-st)*dh/sh);memory.writeWord(pt+2,dl+(h-sl)*dw/sw);}
	}

	private boolean deviceLoop(M68kCpu cpu) throws M68kException {
		int flags=memory.readLong(cpu.a[7]),userData=memory.readLong(cpu.a[7]+4),proc=memory.readLong(cpu.a[7]+8),rgn=memory.readLong(cpu.a[7]+12);cpu.a[7]+=16;
		if(proc==0)return true;
		// pascal void proc(short depth, short deviceFlags, GDHandle targetDevice, long userData)
		cpu.pushWord(32);cpu.pushWord(0);cpu.pushLong(mainDevice());cpu.pushLong(userData);
		cpu.callPascal(proc,12);
		return true;
	}
	int mainDevice() throws M68kCpuException {
		int d=memory.readLong(0x8a4);if(d!=0)return d;
		if(tb.cpu==null)return 0;
		int savedSp=tb.cpu.a[7];tb.cpu.a[7]-=4;try{qd.handle(tb.cpu,0xa2a);d=memory.readLong(tb.cpu.a[7]);}finally{tb.cpu.a[7]=savedSp;}
		memory.writeLong(0x8a4,d);memory.writeLong(0x8a8,d);return d;
	}

	// ------------------------------------------------------------------ icons

	/** Draws an ICON (128 bytes) or cicn Handle into a rect of the current port. */
	void plotIcon(int handle,int rect,boolean color) throws M68kCpuException {
		if(handle==0||rect==0)return;
		int data=memory.readLong(handle);if(data==0)return;
		// a plain ICON is CopyBits srcCopy of its bitmap: in the port's fore and back colours (PgColorX's "Icon" sets RGBForeColor)
		BufferedImage image=color?decodeCicn(handle):decodeIcon(data,32,32,tb.api.handleSize(handle)>=256?data+128:0,0xff000000|qd.foregroundRgb(),0xff000000|qd.backgroundRgb());
		if(image==null)return;
		drawImage(image,(short)memory.readWord(rect),(short)memory.readWord(rect+2),(short)memory.readWord(rect+4),(short)memory.readWord(rect+6));
	}

	void drawImage(BufferedImage image,int top,int left,int bottom,int right) throws M68kCpuException {
		qd.imageDraws++;qd.recordPictureImage(image,top,left,bottom,right); // an open picture records the icon as well
		Graphics2D g=surface(tb.api.currentQuickDrawPort(),new int[]{top,left,bottom,right});
		if(g!=null){try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(image,left,top,right-left,bottom-top,null);}finally{g.dispose();}return;}
		int w=Math.max(1,right-left),h=Math.max(1,bottom-top);
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int argb=image.getRGB(x*image.getWidth()/w,y*image.getHeight()/h),alpha=argb>>>24;if(alpha==0||!qd.visibleAt(left+x,top+y))continue;
			if(alpha>=255)qd.fillRgb(top+y,left+x,top+y+1,left+x+1,argb&0xffffff);else qd.blendRgb(left+x,top+y,argb&0xffffff,alpha);}
	}

	BufferedImage decodeIcon(int bits,int width,int height,int mask) throws M68kCpuException {return decodeIcon(bits,width,height,mask,0xff000000,0xffffffff);}
	BufferedImage decodeIcon(int bits,int width,int height,int mask,int on,int off) throws M68kCpuException {
		BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);int rowBytes=(width+7)/8;
		for(int y=0;y<height;y++)for(int x=0;x<width;x++){boolean set=(memory.readByte(bits+y*rowBytes+x/8)&(0x80>>>(x&7)))!=0;boolean m=mask==0?true:(memory.readByte(mask+y*rowBytes+x/8)&(0x80>>>(x&7)))!=0;
			image.setRGB(x,y,!m?0:set?on:off);}
		return image;
	}

	/**
	 * GetCIcon: the 'cicn' resource carries the CIcon record with placeholders where the ROM fills in memory it allocates:
	 * the colour table goes into a Handle of its own (iconPMap.pmTable), the pixel image into iconData, and the BitMap/PixMap
	 * baseAddrs point at the mask, the 1-bit image and the pixel image. Programs that CopyBits the icon's PixMap themselves
	 * instead of calling PlotCIcon (by locking iconData and copying from iconPMap) rely on that.
	 */
	int getCIcon(int id) throws M68kCpuException {
		int h=tb.resources.detachedResource(0x6369636e,id);if(h==0)return 0;
		int p=memory.readLong(h),size=tb.api.handleSize(h);if(p==0||size<90)return h;
		int rowBytes=memory.readWord(p+4)&0x3fff,height=(short)memory.readWord(p+10)-(short)memory.readWord(p+6);
		int maskSize=(memory.readWord(p+54)&0x3fff)*height,bmapSize=(memory.readWord(p+68)&0x3fff)*height,ctab=82+maskSize+bmapSize;
		if(height<=0||ctab+8>size)return h;
		int count=(short)memory.readWord(p+ctab+6)+1,ctabSize=8+Math.max(0,count)*8,pixels=ctab+ctabSize;
		if(pixels>size)return h;
		byte[] table=memory.readBytes(p+ctab,Math.min(ctabSize,size-ctab)),image=memory.readBytes(p+pixels,Math.max(0,Math.min(rowBytes*height,size-pixels)));
		int tableHandle=tb.api.createHandle(table),dataHandle=tb.api.createHandle(image);
		qd.changeColorTableSeed(tableHandle);
		memory.writeLong(p+42,tableHandle);memory.writeLong(p+78,dataHandle);
		memory.writeLong(p,memory.readLong(dataHandle));memory.writeLong(p+50,p+82);memory.writeLong(p+64,p+82+maskSize);
		return h;
	}
	/** DisposeCIcon frees the Handles GetCIcon allocated along with the record. */
	void disposeCIcon(int h){
		if(h==0)return;
		try{int p=memory.readLong(h);if(p!=0&&tb.api.handleSize(h)>=82){int table=memory.readLong(p+42),data=memory.readLong(p+78);if(table!=0&&tb.api.handleSize(table)>0)tb.api.disposeHandleAndFree(table);if(data!=0&&tb.api.handleSize(data)>0)tb.api.disposeHandleAndFree(data);}}catch(M68kCpuException ignored){}
		tb.api.disposeHandleAndFree(h);
	}

	/** Decodes a 'cicn' Handle (PixMap + mask BitMap + 1-bit BitMap + color table + data; or, after {@link #getCIcon}, table and data in their own Handles). */
	BufferedImage decodeCicn(int handle) throws M68kCpuException {
		int p=memory.readLong(handle);if(p==0)return null;
		int rowBytes=memory.readWord(p+4)&0x3fff;int top=(short)memory.readWord(p+6),left=(short)memory.readWord(p+8),bottom=(short)memory.readWord(p+10),right=(short)memory.readWord(p+12);
		int pixelSize=memory.readWord(p+32);int w=right-left,h=bottom-top;if(w<=0||h<=0||w>256||h>256)return null;
		int maskRow=memory.readWord(p+50+4)&0x3fff;int bmapRow=memory.readWord(p+64+4)&0x3fff;
		int maskData=p+82,bmapData=maskData+maskRow*h;int ctab=bmapData+bmapRow*h,pixels=0;
		int tableHandle=memory.readLong(p+42),dataHandle=memory.readLong(p+78);
		if(tableHandle!=0&&dataHandle!=0&&tb.api.handleSize(tableHandle)>=8&&tb.api.handleSize(dataHandle)>0){ctab=memory.readLong(tableHandle);pixels=memory.readLong(dataHandle);}
		// The table's value fields are pixel values and may be sparse (some 8-bit icons list 42 entries with
		// values 44..255), so the lookup array spans the pixel depth, not the entry count; a table whose values are not a usable
		// set (duplicates, out of range) is positional, as in QuickDraw68k.readColorTable.
		int count=(short)memory.readWord(ctab+6)+1;int[] colors=new int[Math.max(1<<Math.min(8,Math.max(1,pixelSize)),Math.max(1,count))];
		boolean byValue=true;boolean[] seen=new boolean[colors.length];
		for(int i=0;i<count&&byValue;i++){int v=memory.readWord(ctab+8+i*8)&0xffff;if(v>=colors.length||seen[v])byValue=false;else seen[v]=true;}
		for(int i=0;i<count;i++){int e=ctab+8+i*8;int idx=byValue?memory.readWord(e)&0xffff:i;int rgb=(memory.readWord(e+2)>>>8)<<16|(memory.readWord(e+4)>>>8)<<8|(memory.readWord(e+6)>>>8);if(idx<colors.length)colors[idx]=rgb;}
		if(pixels==0)pixels=ctab+8+count*8;
		BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){
			boolean m=maskRow==0||(memory.readByte(maskData+y*maskRow+x/8)&(0x80>>>(x&7)))!=0;
			int value;int bit=x*pixelSize;int b=memory.readByte(pixels+y*rowBytes+(bit>>>3));value=(b>>>(8-pixelSize-(bit&7)))&((1<<pixelSize)-1);
			int rgb=value<colors.length?colors[value]:0;
			image.setRGB(x,y,m?0xff000000|rgb:0);
		}
		return image;
	}

	private final java.util.Map<Integer,int[]> iconCaches=new java.util.HashMap<Integer,int[]>(); // MakeIconCache handle -> {makeIcon proc, yourDataPtr}

	/** PlotIconMethod / LoadIconCache: draws the icon theMethod(type, yourDataPtr) : Handle hands out for the rectangle. */
	private void plotIconMethod(M68kCpu cpu,int rect,int align,int transform,int method,int data) throws M68kException {
		if(rect!=0&&method!=0){
			int top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
			boolean small=bottom-top<32||right-left<32;int size=small?16:32;
			int mask=iconFromMethod(cpu,method,data,small?0x69637323:0x49434e23),color=iconFromMethod(cpu,method,data,small?0x69637338:0x69636c38); // ics#/ICN#, ics8/icl8
			int maskData=mask==0?0:memory.readLong(mask),colorData=color==0?0:memory.readLong(color);
			BufferedImage image=null;
			if(colorData!=0&&tb.api.handleSize(color)>=size*size)image=decode8Bit(colorData,size,size,maskData==0?0:maskData+size*size/8);
			else if(maskData!=0)image=decodeIcon(maskData,size,size,tb.api.handleSize(mask)>=size*size/4?maskData+size*size/8:0);
			if(Mac68kApi.TRACE)System.err.printf("[68K] PlotIconMethod (%d,%d,%d,%d) transform $%x mask $%x color $%x%n",top,left,bottom,right,transform,mask,color);
			if(image!=null)drawAligned(transformIcon(image,transform),top,left,bottom,right,align);
		}
	}

	private boolean iconDispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;int argBytes=(selector>>>8)*2;int sel=selector&0xff;
		int sp=cpu.a[7];
		switch(sel){
			case 0x00:{ // PlotIconID(rect, align, transform, id)
				int id=(short)memory.readWord(sp),transform=memory.readWord(sp+2),align=memory.readWord(sp+4),rect=memory.readLong(sp+6);cpu.a[7]+=argBytes;
				qd.chargeIcon(cpu,rect);plotIconId(id,rect,align,transform);memory.writeWord(cpu.a[7],0);return true;}
			case 0x01:{ // GetIconSuite(VAR suite, id, selector)
				int selectorBits=memory.readLong(sp),id=(short)memory.readWord(sp+4),suitePtr=memory.readLong(sp+6);cpu.a[7]+=argBytes;
				int suite=tb.api.createHandle(new byte[]{0,0,(byte)(id>>8),(byte)id});
				// the ROM gets the members' Handles now, from the chain as it is now: Cyberdog links its editors file in only while
				// it builds its suites, so looking them up again at PlotIconSuite time found nothing (blank service icons)
				java.util.Map<Integer,Integer> members=new java.util.HashMap<Integer,Integer>();
				for(int type:SUITE_TYPES){int h=tb.resources.iconSuiteMemberQuietly(type,id);if(h!=0)members.put(type,h);}
				if(!members.isEmpty())suiteMembers.put(suite,members);
				memory.writeLong(suitePtr,suite);memory.writeWord(cpu.a[7],0);return true;}
			case 0x02:{ // DisposeIconSuite(theIconSuite, disposeData): the Boolean is on top, the suite below it (the suite used to be read from the Boolean's word)
				boolean disposeData=memory.readByte(sp)!=0;int suite=memory.readLong(sp+2);cpu.a[7]+=argBytes;
				java.util.Map<Integer,Integer> members=suiteMembers.remove(suite);if(disposeData&&members!=null)for(int h:members.values()){tb.resources.unpinHandle(h);if(!tb.resources.release(h))tb.api.disposeHandleAndFree(h);} // resource members are released (ReleaseResource), others disposed
				if(suite!=0)tb.api.disposeHandleAndFree(suite);memory.writeWord(cpu.a[7],0);return true;}
			case 0x03:{ // PlotIconSuite(rect, align, transform, suite)
				int suite=memory.readLong(sp),transform=memory.readWord(sp+4),align=memory.readWord(sp+6),rect=memory.readLong(sp+8);cpu.a[7]+=argBytes;
				if(suite!=0&&rect!=0){qd.chargeIcon(cpu,rect);plotIcons(suiteSource(suite),rect,align,transform);}memory.writeWord(cpu.a[7],0);return true;}
			case 0x05:{ // PlotIconMethod(rect, align, transform, theMethod, yourDataPtr): the icons come from theMethod(type, yourDataPtr) : Handle
				int data=memory.readLong(sp),method=memory.readLong(sp+4),transform=memory.readWord(sp+8),align=memory.readWord(sp+10),rect=memory.readLong(sp+12);cpu.a[7]+=argBytes;
				qd.chargeIcon(cpu,rect);plotIconMethod(cpu,rect,align,transform,method,data);
				memory.writeWord(cpu.a[7],0);return true;}
			case 0x04:{int data=memory.readLong(sp),method=memory.readLong(sp+4),cachePtr=memory.readLong(sp+8);cpu.a[7]+=argBytes; // MakeIconCache(VAR theHandle, makeIcon, yourDataPtr): an empty handle standing for the (method, data) pair, the icons come from the method when LoadIconCache plots
				int cache=tb.api.createHandle(new byte[]{0,0,0,0});iconCaches.put(cache,new int[]{method,data});if(cachePtr!=0)memory.writeLong(cachePtr,cache);memory.writeWord(cpu.a[7],0);return true;}
			case 0x06:{int cache=memory.readLong(sp),transform=memory.readWord(sp+4),align=memory.readWord(sp+6),rect=memory.readLong(sp+8);cpu.a[7]+=argBytes; // LoadIconCache(rect, align, transform, theIconCache)
				int[] c=iconCaches.get(cache);if(c!=null)qd.chargeIcon(cpu,rect);if(c!=null)plotIconMethod(cpu,rect,align,transform,c[0],c[1]);memory.writeWord(cpu.a[7],c==null?-50:0);return true;}
			case 0x19:case 0x1b:{int out=memory.readLong(sp),cache=memory.readLong(sp+4);cpu.a[7]+=argBytes; // GetIconCacheData(theCache, VAR theData) / GetIconCacheProc(theCache, VAR theProc)
				int[] c=iconCaches.get(cache);if(c!=null&&out!=0)memory.writeLong(out,c[sel==0x19?1:0]);memory.writeWord(cpu.a[7],c==null?-50:0);return true;}
			case 0x1a:case 0x1c:{int v=memory.readLong(sp),cache=memory.readLong(sp+4);cpu.a[7]+=argBytes; // SetIconCacheData(theCache, theData) / SetIconCacheProc(theCache, theProc)
				int[] c=iconCaches.get(cache);if(c!=null)c[sel==0x1a?1:0]=v;memory.writeWord(cpu.a[7],c==null?-50:0);return true;}
			case 0x07:{int suitePtr=memory.readLong(sp);cpu.a[7]+=argBytes;int suite=tb.api.createHandle(new byte[]{1,0,0,0});suiteMembers.put(suite,new java.util.HashMap<Integer,Integer>());memory.writeLong(suitePtr,suite);memory.writeWord(cpu.a[7],0);return true;} // NewIconSuite: empty until AddIconToSuite
			case 0x08:{int type=memory.readLong(sp),suite=memory.readLong(sp+4),icon=memory.readLong(sp+8);cpu.a[7]+=argBytes;if(suite!=0)suiteMembers.computeIfAbsent(suite,k->new java.util.HashMap<Integer,Integer>()).put(type,icon);memory.writeWord(cpu.a[7],suite==0?-50:0);return true;} // AddIconToSuite(theIconData, suite, theType)
			case 0x09:{int type=memory.readLong(sp),suite=memory.readLong(sp+4),iconPtr=memory.readLong(sp+8);cpu.a[7]+=argBytes;int h=suite==0?0:suiteSource(suite).handle(type);if(iconPtr!=0)memory.writeLong(iconPtr,h);memory.writeWord(cpu.a[7],h==0?-192:0);return true;} // GetIconFromSuite
			case 0x1d:case 0x1e:case 0x1f:{ // PlotIconHandle/PlotSICNHandle/PlotCIconHandle(rect, align, transform, handle)
				int handle=memory.readLong(sp),transform=memory.readWord(sp+4),rect=memory.readLong(sp+8);cpu.a[7]+=argBytes;qd.chargeIcon(cpu,rect);
				if(sel==0x1f)plotIcon(handle,rect,true);else if(sel==0x1d)plotIcon(handle,rect,false);else if(handle!=0){int data=memory.readLong(handle);if(data!=0)drawImage(decodeIcon(data,16,16,tb.api.handleSize(handle)>=64?data+32:0),(short)memory.readWord(rect),(short)memory.readWord(rect+2),(short)memory.readWord(rect+4),(short)memory.readWord(rect+6));}
				memory.writeWord(cpu.a[7],0);return true;}
			case 0x0d:case 0x0e:case 0x0f:case 0x10:case 0x11:case 0x12:{ // PtInIconID/Suite/Method(testPt, iconRect, align, ...) and RectInIconID/Suite/Method(testRect, iconRect, align, ...): the icon's rectangle stands for its mask
				int test=sp+argBytes-4,rect=memory.readLong(sp+argBytes-8);cpu.a[7]+=argBytes;boolean hit=false;
				if(rect!=0){int top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
					if(sel<=0x0f){int v=(short)memory.readWord(test),h=(short)memory.readWord(test+2);hit=v>=top&&v<bottom&&h>=left&&h<right;}
					else{int r=memory.readLong(test);if(r!=0){int t=(short)memory.readWord(r),l=(short)memory.readWord(r+2),b=(short)memory.readWord(r+4),rr=(short)memory.readWord(r+6);hit=Math.max(t,top)<Math.min(b,bottom)&&Math.max(l,left)<Math.min(rr,right);}}}
				if(Mac68kApi.TRACE&&rect!=0)System.err.printf("[68K] %s test %08x in (%d,%d,%d,%d) -> %b%n",sel<=0x0f?"PtInIcon":"RectInIcon",memory.readLong(test),(short)memory.readWord(rect),(short)memory.readWord(rect+2),(short)memory.readWord(rect+4),(short)memory.readWord(rect+6),hit);
				tb.writeBoolean(cpu.a[7],hit);return true;}
			case 0x0b:{int name=memory.readLong(sp),color=memory.readLong(sp+4),label=(short)memory.readWord(sp+8);cpu.a[7]+=argBytes; // GetLabel(labelNumber, VAR labelColor, VAR labelString)
				if(label<0||label>=LABEL_COLORS.length){memory.writeWord(cpu.a[7],-50);return true;}
				if(color!=0)writeRgb(color,LABEL_COLORS[label]);
				if(name!=0){int h=tb.resources.detachedResource(0x6c737472,SystemResources68k.LABEL_STRING_ID+label);byte[] text=h==0?null:tb.api.handleContents(h); // 'lstr': the name the Labels control panel keeps in the System file
					if(text!=null&&text.length>0)memory.load(name,java.util.Arrays.copyOf(text,Math.min(text.length,(text[0]&0xff)+1)));else memory.writeByte(name,0);}
				memory.writeWord(cpu.a[7],0);return true;}
			case 0x13:case 0x14:case 0x15:{ // IconIDToRgn(rgn, rect, align, id) / IconSuiteToRgn(rgn, rect, align, suite) / IconMethodToRgn(rgn, rect, align, method, data)
				int rect=memory.readLong(sp+argBytes-8),rgn=memory.readLong(sp+argBytes-4),align=memory.readWord(sp+argBytes-10);
				if(rgn!=0&&rect!=0){
					int top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
					IconSource source;
					if(sel==0x13){int id=(short)memory.readWord(sp);source=type->tb.resources.detachedResource(type,id);}
					else if(sel==0x14)source=suiteSource(memory.readLong(sp));
					else{int method=memory.readLong(sp+4),data=memory.readLong(sp); // the method answers the two masks before the region is made
						final int large=method==0?0:iconFromMethod(cpu,method,data,0x49434e23),small=method==0?0:iconFromMethod(cpu,method,data,0x69637323);
						source=type->type==0x49434e23?large:type==0x69637323?small:0;}
					QdRegion shape=iconMaskRegion(source,top,left,bottom,right,align);
					qd.setRegion(rgn,shape!=null?shape:QdRegion.rect(top,left,bottom,right)); // no mask: its rectangle
				}
				cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],0);return true;}
			case 0x0a:{ // ForEachIconDo(suite, selector, action, yourDataPtr): pascal OSErr action(theType, VAR theIcon, yourDataPtr) for every
				// type the selector names, in the suite's order, the Handle put back as the action leaves it; an error ends the walk.
				// Iconographer reads classic icons through it (an ICN#-only control panel opened blank - this answered unimpErr)
				int data=memory.readLong(sp),action=memory.readLong(sp+4),selectorBits=memory.readLong(sp+8),suite=memory.readLong(sp+12);cpu.a[7]+=argBytes;
				int err=suite==0||action==0?-50:0;
				if(err==0){IconSource source=suiteSource(suite);java.util.Map<Integer,Integer> members=suiteMembers.computeIfAbsent(suite,k->new java.util.HashMap<Integer,Integer>());
					int slot=memory.allocate(4,2);
					try{for(int i=0;i<SUITE_TYPES.length&&err==0;i++){if((selectorBits&SUITE_SELECTOR_BITS[i])==0)continue;
						int type=SUITE_TYPES[i],before=source.handle(type);memory.writeLong(slot,before);err=(short)callIconAction(cpu,action,type,slot,data);
						int after=memory.readLong(slot);if(after!=before){if(after!=0)members.put(type,after);else members.remove(type);}}}
					finally{memory.free(slot);}}
				memory.writeWord(cpu.a[7],err);return true;}
			default:cpu.a[7]+=argBytes;memory.writeWord(cpu.a[7],-4);return true;
		}
	}

	void plotIconId(int id,int rect,int transform) throws M68kCpuException {plotIconId(id,rect,0,transform);}
	void plotIconId(int id,int rect,int align,int transform) throws M68kCpuException {plotIcons(type->tb.resources.detachedResource(type,id),rect,align,transform);}
	/** Where an icon family's members come from (a resource ID, a suite built with AddIconToSuite): the Handle of a type, or 0. */
	private interface IconSource{int handle(int type) throws M68kCpuException;}
	/** Plots the best member of an icon family for the rectangle: 16-pixel ics8/ics# for small rectangles, icl8/ICN# (ICON) otherwise. */
	private void plotIcons(IconSource source,int rect,int align,int transform) throws M68kCpuException {
		int top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
		int w=right-left,h=bottom-top;
		BufferedImage image=null;
		if(w<=16||h<=16){int h8=source.handle(0x69637338),hm=source.handle(0x69637323);if(h8!=0)image=decode8Bit(memory.readLong(h8),16,16,hm==0?0:memory.readLong(hm)+32);else if(hm!=0)image=decodeIcon(memory.readLong(hm),16,16,memory.readLong(hm)+32);}
		if(image==null){int h8=source.handle(0x69636c38),hm=source.handle(0x49434e23);if(h8!=0)image=decode8Bit(memory.readLong(h8),32,32,hm==0?0:memory.readLong(hm)+128);else if(hm!=0)image=decodeIcon(memory.readLong(hm),32,32,memory.readLong(hm)+128);else{int hi=source.handle(0x49434f4e);if(hi!=0)image=decodeIcon(memory.readLong(hi),32,32,0);}}
		if(image==null)return;
		drawAligned(transformIcon(image,transform),top,left,bottom,right,align);
	}
	/**
	 * An icon drawn into (top, left, bottom, right) with an IconAlignmentType, as the ROM's PerformAlignment does it (IconUtils.c):
	 * the bounds of the icon's mask (MakeBoundary), mapped into the rectangle, are what moves to the rectangle's edge or centre -
	 * the icon itself keeps the rectangle's size. CodeWarrior 5's toolbar plots 32x32 icons whose buttons sit in the lower rows
	 * into (4,4,36,36) with atTopLeft: unaligned they came 14 pixels too low, under the build status line.
	 */
	private void drawAligned(BufferedImage image,int top,int left,int bottom,int right,int align) throws M68kCpuException {
		int[] r=aligned(image,top,left,bottom,right,align);
		drawImage(image,r[0],r[1],r[2],r[3]);
	}
	/** The rectangle (top, left, bottom, right) moved as PerformAlignment moves an icon in it (see {@link #drawAligned}). */
	private static int[] aligned(BufferedImage image,int top,int left,int bottom,int right,int align){
		int[] b=align==0?null:maskBounds(image);
		if(b!=null){
			int w=image.getWidth(),h=image.getHeight(),rw=right-left,rh=bottom-top;
			int bt=top+b[0]*rh/h,bl=left+b[1]*rw/w,bb=top+b[2]*rh/h,br=left+b[3]*rw/w; // MapRect(boundary, icon bounds, rect)
			int dx=0,dy=0;
			switch(align&0xc){case 4:dx=half(right+left-(br+bl));break;case 8:dx=left-bl;break;case 0xc:dx=right-br;break;default:}
			switch(align&3){case 1:dy=half(bottom+top-(bb+bt));break;case 2:dy=top-bt;break;case 3:dy=bottom-bb;break;default:}
			top+=dy;bottom+=dy;left+=dx;right+=dx;
		}
		return new int[]{top,left,bottom,right};
	}
	/**
	 * IconIDToRgn/IconSuiteToRgn/IconMethodToRgn: the icon's mask (ics# for a rectangle of 16 pixels or less, ICN# otherwise,
	 * as plotting chooses) mapped into the rectangle and aligned like the plotted icon - the ROM's IconToRgn. AppleWorks 5
	 * copies its ruler buttons through this region (CopyBits maskRgn): the whole rectangle wiped the ruler's tick marks.
	 * Null when the family has no mask.
	 */
	private QdRegion iconMaskRegion(IconSource source,int top,int left,int bottom,int right,int align) throws M68kCpuException {
		int w=right-left,h=bottom-top;if(w<=0||h<=0)return null;
		boolean small=w<=16||h<=16;int size=small?16:32,handle=source.handle(small?0x69637323:0x49434e23);
		if(handle==0&&small){small=false;size=32;handle=source.handle(0x49434e23);}
		int data=handle==0?0:memory.readLong(handle);
		if(data==0||tb.api.handleSize(handle)<size*size/4)return null;
		BufferedImage mask=decodeIcon(data+size*size/8,size,size,data+size*size/8);
		int[] r=aligned(mask,top,left,bottom,right,align);
		java.util.List<int[]> rows=new java.util.ArrayList<int[]>();
		for(int v=0;v<h;v++){int sy=v*size/h;java.util.List<Integer> xs=new java.util.ArrayList<Integer>();boolean in=false;
			for(int x=0;x<=w;x++){boolean on=x<w&&(mask.getRGB(x*size/w,sy)>>>24)!=0;if(on!=in){xs.add(r[1]+x);in=on;}}
			int[] a=new int[xs.size()];for(int i=0;i<a.length;i++)a[i]=xs.get(i);rows.add(a);}
		return QdRegion.fromRows(r[0],rows);
	}
	/** DivByTwoShort: halves towards zero. */
	private static int half(int v){return v<0?(v+1)>>1:v>>1;}
	/** Top, left, bottom, right of the opaque (mask) pixels, or null when there are none. */
	private static int[] maskBounds(BufferedImage image){
		int t=-1,l=Integer.MAX_VALUE,bt=-1,r=-1;
		for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)!=0){if(t<0)t=y;bt=y;if(x<l)l=x;if(x>r)r=x;}
		return t<0?null:new int[]{t,l,bt+1,r+1};
	}
	/** The members GetIconSuite collects: ICN#, icl4, icl8, ics#, ics4, ics8, icm#, icm4, icm8. */
	private static final int[] SUITE_TYPES={0x49434e23,0x69636c34,0x69636c38,0x69637323,0x69637334,0x69637338,0x69636d23,0x69636d34,0x69636d38};
	/** The IconSelectorValue bit of each of {@link #SUITE_TYPES} (svLarge1Bit ... svMini8Bit). */
	private static final int[] SUITE_SELECTOR_BITS={0x1,0x2,0x4,0x100,0x200,0x400,0x10000,0x20000,0x40000};
	/** Calls a ForEachIconDo action, pascal OSErr (ResType theType, Handle *theIcon, void *yourDataPtr), with the registers kept. */
	private int callIconAction(M68kCpu cpu,int action,int type,int iconSlot,int data) throws M68kException {
		int[] d=cpu.d.clone(),a=cpu.a.clone();int savedSp=cpu.a[7];
		try{cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.pushLong(type);cpu.pushLong(iconSlot);cpu.pushLong(data);cpu.callPascal(action,12);return memory.readWord(cpu.a[7]);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=savedSp;}
	}
	/** Icon suites: the resource ID they were read from (0 for NewIconSuite) and the members added to them. */
	private final java.util.Map<Integer,java.util.Map<Integer,Integer>> suiteMembers=new java.util.HashMap<Integer,java.util.Map<Integer,Integer>>();
	private IconSource suiteSource(int suite) throws M68kCpuException {
		final java.util.Map<Integer,Integer> members=suiteMembers.get(suite);int data=suite==0?0:memory.readLong(suite);final int id=data==0?0:(short)memory.readWord(data+2);final boolean fromResource=data!=0&&memory.readWord(data)==0&&(members==null||id!=0);
		return type->{Integer h=members==null?null:members.get(type);if(h!=null)return h;return fromResource&&id!=0?tb.resources.detachedResource(type,id):0;};
	}

	/** An icon getter of PlotIconMethod: pascal Handle theMethod(ResType, void *yourDataPtr). */
	private int iconFromMethod(M68kCpu cpu,int method,int data,int type) throws M68kException {
		int[] regs=cpu.a.clone(),dr=cpu.d.clone();int saved=cpu.a[7];
		try{cpu.pushLong(0);int slot=cpu.a[7];cpu.pushLong(type);cpu.pushLong(data);cpu.callPascal(method,8);return memory.readLong(slot);}
		finally{cpu.a[7]=saved;System.arraycopy(dr,0,cpu.d,0,8);System.arraycopy(regs,0,cpu.a,0,8);}
	}
	/** IconTransformType: ttSelected ($4000) darkens the icon, ttDisabled/ttOffline (1, 2) fade it, ttOpen (3) fills it with a grey dither. */
	private static BufferedImage transformIcon(BufferedImage image,int transform){
		boolean selected=(transform&0x4000)!=0;int state=transform&0xff;
		if(!selected&&state==0)return image;
		BufferedImage out=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int argb=image.getRGB(x,y);if((argb>>>24)==0){out.setRGB(x,y,0);continue;}
			int r=argb>>>16&255,g=argb>>>8&255,b=argb&255;
			if(state==3){int lum=(r*299+g*587+b*114)/1000;int v=lum<0x60?0x33:((x+y)&1)==0?0x66:0xbb;r=g=b=v;} // ttOpen: the icon filled with a grey dither, its dark lines kept
			else if(state>=1&&state<=2){r=(r+2*255)/3;g=(g+2*255)/3;b=(b+2*255)/3;} // ttDisabled / ttOffline: faded
			if(selected){r/=2;g/=2;b/=2;}
			out.setRGB(x,y,0xff000000|r<<16|g<<8|b);}
		return out;
	}

	private BufferedImage decode8Bit(int data,int w,int h,int mask) throws M68kCpuException {
		BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);int rowBytes=(w+7)/8;
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean m=mask==0||(memory.readByte(mask+y*rowBytes+x/8)&(0x80>>>(x&7)))!=0;int idx=memory.readByte(data+y*w+x);image.setRGB(x,y,m?0xff000000|MacSystemPalette.rgb(idx):0);}
		return image;
	}

	// ------------------------------------------------------------------ palettes

	/** PMgrDispatch ($AAA2): selector in D0.W, its high byte is the argument byte count (0 for the MOVEQ-style selectors). */
	private boolean paletteDispatch(M68kCpu cpu) throws M68kCpuException {
		int selector=QuickDraw68k.paletteSelector(cpu),sp=cpu.a[7];
		// The ROM dispatches on the low byte and each routine pops its own arguments, so a wrong size byte is harmless there:
		// Netscape Navigator 2.02's menu MDEF calls GetGray as $1219 (12 bytes of arguments, not 18) - popping $12 bytes
		// shifted its stack by 6 and its saved registers came back scrambled.
		if((selector&0xff)==0x19)selector=0x0c19;
		switch(selector){
			case 0x0000:{int entry=(short)memory.readWord(sp);cpu.a[7]+=2;int value=entry; // Entry2Index: palettes load index for entry here
				// A direct screen has no indices: the entry's colour as a pixel of the device (Color2Index). Mazeworld Catacombs writes
				// these values into its 32-bit offscreen view itself; the entry numbers made it a near-black blue.
				Integer ph=windowPalette(qd.currentPortAddress());Palette pal=ph==null?null:palettes.get(ph);
				if(pal!=null&&entry>=0&&entry<pal.rgb.length){int direct=qd.directPixelValue(pal.rgb[entry]);if(direct>=0)value=direct;}
				memory.writeLong(cpu.a[7],value);return true;}
			case 0x0002:{int gd=memory.readLong(sp);cpu.a[7]+=4;qd.restoreDeviceClut(gd);return true;} // RestoreDeviceClut(gd)
			case 0x0003:{int size=(short)memory.readWord(sp),p=memory.readLong(sp+2);cpu.a[7]+=6;Palette pal=palettes.get(p); // ResizePalette
				if(pal!=null&&size>=0){pal.rgb=java.util.Arrays.copyOf(pal.rgb,size);pal.usage=java.util.Arrays.copyOf(pal.usage,size);pal.tolerance=java.util.Arrays.copyOf(pal.tolerance,size);memory.writeWord(memory.readLong(p),size);}return true;} // (tolerance was left at the old size: 三國志英傑伝 grew a palette from 245 entries and writePaletteRecord threw)
			case 0x040d:case 0x040e:case 0x040f:case 0x0410:{ // SaveFore/SaveBack/RestoreFore/RestoreBack(VAR c: ColorSpec): a colour port's
				// rgbFgColor/rgbBkColor (value 0), a basic port's fgColor/bkColor in value; restored through RGBForeColor/ForeColor...
				int c=memory.readLong(sp);cpu.a[7]+=4;int port=qd.currentPortAddress();if(c==0||port==0)return true;
				boolean fore=selector==0x040d||selector==0x040f,color=qd.colorPort(port);
				if(selector==0x040d||selector==0x040e){
					if(color){memory.writeWord(c,0);memory.copy(port+(fore?36:42),c+2,6);}
					else{memory.writeWord(c,memory.readLong(port+(fore?80:84)));memory.fill(c+2,6,0);}
				}else{int base=cpu.a[7];
					if(color){cpu.pushLong(c+2);qd.handle(cpu,fore?0xa14:0xa15);}
					else{cpu.pushLong((short)memory.readWord(c));qd.handle(cpu,fore?0x862:0x863);}
					cpu.a[7]=base;}
				return true;}
			case 0x0a13:case 0x0a14:return false; // SetDepth/HasDepth: QuickDraw68k switches the device PixMap
			case 0x0015:memory.writeWord(cpu.a[7],0x0202);return true; // PMgrVersion
			case 0x0616:{int updates=memory.readWord(sp),p=memory.readLong(sp+2);cpu.a[7]+=6;paletteUpdates.put(p,updateFlags(updates|0x8000));return true;} // SetPaletteUpdates(p, updates)
			case 0x0417:cpu.a[7]+=4;memory.writeWord(cpu.a[7],0);return true; // GetPaletteUpdates
			case 0x0c19:{int fore=memory.readLong(sp),back=memory.readLong(sp+4);cpu.a[7]+=12; // GetGray: average of the two colours
				int a=readRgb(back),b=readRgb(fore);int gray=((a>>>16&255)+(b>>>16&255))/2<<16|((a>>>8&255)+(b>>>8&255))/2<<8|((a&255)+(b&255))/2;
				if(fore!=0)writeRgb(fore,gray);memory.writeWord(cpu.a[7],0x0100);return true;}
			case 0x0a1a:{int updates=memory.readWord(sp),p=memory.readLong(sp+2),w=memory.readLong(sp+6);cpu.a[7]+=10;paletteUpdates.put(p,updateFlags(updates|0x8000));setWindowPalette(w,p);return true;} // NSetPalette
			default:if(selector>=0x100){cpu.a[7]+=selector>>>8;return true;}return false;
		}
	}

	/**
	 * ActivatePalette / a palette window coming to the front: the Palette Manager loads the palette into the screen's
	 * colour table - pmExplicit entries at their own index, and a palette as large as the table replaces it outright
	 * (tolerant/animated entries all end up allocated; white and black stay at the ends); on a screen of 16 colours or fewer
	 * tolerant entries go where the Palette Manager's own allocation puts them (QuickDraw68k.correlateTolerant). Games set such a palette on
	 * a full-screen window and copy pixels by index afterwards, so the table has to hold their colours.
	 */
	void activatePalette(int window) throws M68kCpuException {
		if(window!=0&&qd.isGWorld(window)){Integer gp=windowPalettes.get(window);Palette g=gp==null?null:palettes.get(gp);
			if(g!=null&&g.rgb.length!=0)qd.layoutPaletteInto(window,g.rgb,g.usage,g.tolerance);return;}
		if(window==0||tb.windows.frontWindow()!=window)return;Integer ph=windowPalette(window);Palette p=ph==null?null:palettes.get(ph);
		if(p==null||p.rgb.length==0)return;
		if(qd.loadDeviceColors(p.rgb,p.usage,p.tolerance))colorsChanged(window);
	}
	/**
	 * The screen's colours changed: windows whose palettes ask for updates get their whole content invalidated - the front window
	 * with pmFgUpdates, the others with pmBkUpdates (IM Advanced Color Imaging, "Palette update flags"). Mario's Game Gallery
	 * blacks its window on an activate event, reloads its palette (SetPalette ... pmAllUpdates) and repaints on the update
	 * event that follows; without it the window stayed black but for the bits that later changed.
	 */
	private void colorsChanged(int front) throws M68kCpuException {
		for(Map.Entry<Integer,Integer> e:new java.util.ArrayList<Map.Entry<Integer,Integer>>(windowPalettes.entrySet())){
			int w=e.getKey();if(w==-1||!tb.windows.isWindow(w))continue;
			Integer flags=paletteUpdates.get(e.getValue());if(flags==null||(flags&(w==front?UPDATES_FG:UPDATES_BK))==0)continue;
			int top=(short)memory.readWord(w+16),left=(short)memory.readWord(w+18),bottom=(short)memory.readWord(w+20),right=(short)memory.readWord(w+22);
			tb.windows.invalidate(w,top,left,bottom,right);
		}
	}
	/** AnimateEntry/AnimatePalette changed a palette's colours: a palette that is active (its window is frontmost) drives the screen's table.
	 *  The active palette is the front window's own or the default one, so another window with the same palette in front still shows the
	 *  animation: Yoot Tower gives its floating Tool/Control/Message windows its tower window's palette and animates the sky and the
	 *  daylight through the tower window - with a floater in front the tower stayed in the palette's first colours (salmon sky). */
	private void animatePalette(int window,Palette p) throws M68kCpuException {
		if(window==0||p.rgb.length==0)return;int front=tb.windows.frontWindow();
		Integer own=windowPalettes.get(window),active=front==0?null:windowPalettes.containsKey(front)?windowPalettes.get(front):windowPalettes.get(-1);
		if(front==window||own!=null&&own.equals(active))qd.animateDeviceColors(p.rgb,p.usage);
	}
	/** SetPalette/NSetPalette. The default palette (window -1) takes effect at once for a front window that has none of its own:
	 *  HyperCard's Terazza XFCN sets it from a picture's colour table and copies the picture by index right after, without ActivatePalette. */
	private void setWindowPalette(int window,int palette) throws M68kCpuException {
		windowPalettes.put(window,palette);
		if(window==-1){int front=tb.windows.frontWindow();if(front!=0&&!windowPalettes.containsKey(front))activatePalette(front);}
		else activatePalette(window); // a palette given to the frontmost window takes effect at once; some programs set it before building offscreen pictures
	}
	/** The palette of a window: its own, else the application's default palette - the one set with SetPalette(-1) or, as the
	 *  Palette Manager assigns to every colour window, the program's 'pltt' 0 resource (some programs draw with PmForeColor
	 *  entries of its 'pltt' 0 without ever calling SetPalette). */
	private Integer windowPalette(int window) throws M68kCpuException {
		Integer ph=windowPalettes.get(window);if(ph!=null)return ph;
		if(!defaultPaletteLoaded){defaultPaletteLoaded=true;
			if(!windowPalettes.containsKey(-1)){int res=tb.resources.detachedResourceQuietly(0x706c7474,0);if(res!=0)windowPalettes.put(-1,paletteFromResource(res));
				if(Mac68kApi.TRACE)System.err.println("[68K] default palette: 'pltt' 0 "+(res==0?"not found":"loaded, "+palettes.get(res).rgb.length+" entries"));}}
		return windowPalettes.get(-1);
	}
	private boolean defaultPaletteLoaded;
	/** GetNewCWindow: the program's 'pltt' with the 'WIND's ID becomes the window's palette, as if SetPalette(w, GetNewPalette(id),
	 *  TRUE) (IM V "Palette Manager"). AnGST 1.2.2 paints black label bars and writes on them with PmForeColor(0) of its 'pltt'
	 *  128 (white) after GetNewCWindow(128): without the palette entry 0 was black and the labels vanished. */
	void attachTemplatePalette(int window,int id) throws M68kCpuException {
		ResourceFile68k.Resource r=tb.resources.find(0x706c7474,id);if(r==null||tb.resources.isSystem(r))return;
		int res=tb.resources.detachedResourceQuietly(0x706c7474,id);if(res==0)return;
		int p=paletteFromResource(res);paletteUpdates.put(p,UPDATES_FG|UPDATES_BK);setWindowPalette(window,p);
	}
	/**
	 * InitPalettes (called by InitWindows): the program's 'pltt' 0, its default palette, takes effect on an indexed screen before
	 * any window exists. Achtung Spitfire opens the CGrafPort it later points at all its buffers right after InitWindows; its
	 * PixMap copies the screen's colour table then, and the game copies its pictures by index through it - with the palette
	 * only loaded when the first window showed, that copy kept the standard colours and every picture was translated from them
	 * into rainbow noise.
	 */
	void initPalettes(){
		try{Integer ph=windowPalette(0);Palette p=ph==null?null:palettes.get(ph);
			if(p!=null&&p.rgb.length!=0)qd.loadDeviceColors(p.rgb,p.usage,p.tolerance);}catch(M68kCpuException ignored){}
	}
	/** The window became the frontmost visible one: its palette (if any) takes effect, like the Palette Manager's activate patch. */
	void windowActivated(int window){try{activatePalette(window);}catch(M68kCpuException ignored){}}

	/** The Finder labels' default colours (System 7): none (black), then Essential, Hot, In Progress, Cool, Personal, Project 1 and 2 -
	 *  orange, red, magenta, cyan, blue, green and brown of the standard 16-colour table. */
	static final int[] LABEL_COLORS={0x000000,0xff6600,0xdd0907,0xf31584,0x02abeb,0x0000d4,0x006512,0x562c05};
	private int readRgb(int p) throws M68kCpuException {return (memory.readWord(p)>>>8)<<16|(memory.readWord(p+2)>>>8)<<8|(memory.readWord(p+4)>>>8);}
	private void writeRgb(int p,int rgb) throws M68kCpuException {int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;memory.writeWord(p,r*257);memory.writeWord(p+2,g*257);memory.writeWord(p+4,b*257);}
	private boolean newPalette(M68kCpu cpu) throws M68kCpuException {
		int tolerance=(short)memory.readWord(cpu.a[7]),usage=(short)memory.readWord(cpu.a[7]+2),table=memory.readLong(cpu.a[7]+4),entries=(short)memory.readWord(cpu.a[7]+8);cpu.a[7]+=10;
		int handle=tb.api.createHandle(new byte[16+Math.max(0,entries)*16]);Palette p=new Palette(Math.max(0,entries));
		for(int i=0;i<entries;i++)p.usage[i]=usage;
		for(int i=0;i<entries;i++)p.tolerance[i]=tolerance;
		if(table!=0)colorTableToPaletteObject(table,p,0);
		palettes.put(handle,p);memory.writeWord(memory.readLong(handle),entries);writePaletteRecord(handle,p);memory.writeLong(cpu.a[7],handle);return true;
	}
	/** Mirrors the entries into the guest Palette record (pmEntries, then ColorInfo: ciRGB, ciUsage, ciTolerance, 3 private words)
	 *  for programs that read pmInfo themselves (palette editors may walk it). */
	private void writePaletteRecord(int handle,Palette p) throws M68kCpuException {
		int data=handle==0?0:memory.readLong(handle);if(data==0)return;
		if(tb.api.handleSize(handle)<16+p.rgb.length*16)tb.api.resizeHandle(handle,16+p.rgb.length*16);data=memory.readLong(handle);
		memory.writeWord(data,p.rgb.length);
		for(int i=0;i<p.rgb.length;i++){int e=data+16+i*16;writeRgb(e,p.rgb[i]);memory.writeWord(e+6,p.usage[i]);memory.writeWord(e+8,p.tolerance[i]);}
	}
	private int paletteFromResource(int res) throws M68kCpuException {
		int data=memory.readLong(res);if(data==0)return res;int entries=memory.readWord(data);Palette p=new Palette(entries);
		for(int i=0;i<entries;i++){int e=data+16+i*16;p.rgb[i]=readRgb(e);p.usage[i]=memory.readWord(e+6);}
		palettes.put(res,p);return res;
	}
	private void colorTableToPalette(int table,int paletteHandle,int start) throws M68kCpuException {Palette p=palettes.get(paletteHandle);if(p!=null)colorTableToPaletteObject(table,p,start);}
	private void colorTableToPaletteObject(int table,Palette p,int start) throws M68kCpuException {
		int data=table==0?0:memory.readLong(table);if(data==0)return;int count=(short)memory.readWord(data+6)+1;
		for(int i=0;i<count&&start+i<p.rgb.length;i++)p.rgb[start+i]=readRgb(data+8+i*8+2);
	}
	private void paletteToColorTable(int paletteHandle,int table,int start) throws M68kCpuException {
		Palette p=palettes.get(paletteHandle);if(p==null||table==0||memory.readLong(table)==0)return;
		// the table takes the palette's size (IM: "if the color table is not the same size as the palette, it is resized"):
		// Flashback passes a 16-byte NewHandle whose ctSize is garbage, and the 224 entries ran over the GWorld PixMap
		// allocated behind it (UpdateGWorld then read "0011 DDDD" colour specs as the PixMap)
		int count=Math.max(0,p.rgb.length-start);if(tb.api.setHandleSize(table,8+count*8)!=0)return;int data=memory.readLong(table);
		memory.writeWord(data+4,0);memory.writeWord(data+6,count-1);
		for(int i=0;i<count;i++){int e=data+8+i*8;memory.writeWord(e,i);writeRgb(e+2,p.rgb[start+i]);}
		qd.changeColorTableSeed(table);
	}
	private void pmColor(M68kCpu cpu,int entry,boolean fore) throws M68kCpuException {
		int window=tb.api.currentQuickDrawPort();Integer ph=windowPalette(window);Palette p=ph==null?null:palettes.get(ph);
		int rgb=p!=null&&entry>=0&&entry<p.rgb.length?p.rgb[entry]:(fore?0:0xffffff);
		int scratch=memory.allocate(6,2);writeRgb(scratch,rgb);cpu.a[7]-=4;memory.writeLong(cpu.a[7],scratch);qd.handle(cpu,fore?0xa14:0xa15);memory.free(scratch);
		// animated (4) or explicit (8) entries own a screen colour-table index: the port draws that index, so AnimatePalette recolours the pixels later
		if(p!=null&&entry>=0&&entry<p.rgb.length&&(p.usage[entry]&12)!=0)qd.setPortColorIndex(fore,entry);
	}

	private void setColorCursor(int handle) throws M68kCpuException {
		if(handle==0)return;int p=memory.readLong(handle);if(p==0)return;
		// crsr: type(2) map(4) data(4) xData(4) xValid(2) id(4) 1-bit data 32 mask 32 hotspot 4
		byte[] curs=new byte[68];for(int i=0;i<64;i++)curs[i]=(byte)memory.readByte(p+20+i);for(int i=0;i<4;i++)curs[64+i]=(byte)memory.readByte(p+84+i);
		int[] colors=colorCursorPixels(handle,p);
		tb.host().setCursor(colors!=null?MacAppHost.colorCursor(colors,curs):MacAppHost.cursorFromData(curs));
	}
	/** The 16x16 colours of a 'crsr' (its PixMap and pixel data: Handles, or offsets into the record as the resource has them),
	 *  or null for a 1-bit one - the colour cursors of Civilization and Super Studio Session used to show their black-and-white part. */
	private int[] colorCursorPixels(int handle,int p) throws M68kCpuException {
		if((memory.readWord(p)&0x8000)==0)return null; // crsrType $8001: a colour cursor
		int size=tb.api.handleSize(handle),mapRef=memory.readLong(p+2),dataRef=memory.readLong(p+6);
		boolean offsets=mapRef>0&&mapRef<size&&dataRef>0&&dataRef<size;
		int map=offsets?p+mapRef:derefHandle(mapRef),data=offsets?p+dataRef:derefHandle(dataRef);if(map==0||data==0)return null;
		int rowBytes=memory.readWord(map+4)&0x3fff,depth=memory.readWord(map+32),tableRef=memory.readLong(map+42);
		if(depth!=1&&depth!=2&&depth!=4&&depth!=8||rowBytes==0)return null;
		int table=offsets?(tableRef>0&&tableRef<size?p+tableRef:0):derefHandle(tableRef);if(table==0)return null;
		int[] rgb=new int[256];int entries=Math.min(256,memory.readWord(table+6)+1);
		for(int i=0;i<entries;i++){int e=table+8+i*8;rgb[memory.readWord(e)&0xff]=memory.readByte(e+2)<<16|memory.readByte(e+4)<<8|memory.readByte(e+6);}
		int[] pixels=new int[256];
		for(int y=0;y<16;y++)for(int x=0;x<16;x++){int bit=x*depth,index=(memory.readByte(data+y*rowBytes+bit/8)>>(8-depth-bit%8))&((1<<depth)-1);pixels[y*16+x]=rgb[index];}
		return pixels;
	}
	private int derefHandle(int h) throws M68kCpuException {return h==0||(h&1)!=0?0:memory.readLong(h);
	}

	// ------------------------------------------------------------------ Pack 12 / Pack 15

	/** The Color Picker's colour-model conversions on fractions 0..1 (hue as a fraction of the circle, red at 0). */
	static double[] convertColor(int selector,double[] c){
		switch(selector){
			case 3:case 4:return new double[]{1-c[0],1-c[1],1-c[2]}; // CMY <-> RGB
			case 6:case 8:{ // RGB -> HSL / HSV
				double max=Math.max(c[0],Math.max(c[1],c[2])),min=Math.min(c[0],Math.min(c[1],c[2])),delta=max-min,hue=0;
				if(delta>0){if(max==c[0])hue=((c[1]-c[2])/delta)/6;else if(max==c[1])hue=((c[2]-c[0])/delta+2)/6;else hue=((c[0]-c[1])/delta+4)/6;if(hue<0)hue+=1;}
				if(selector==8)return new double[]{hue,max==0?0:delta/max,max};
				double light=(max+min)/2;return new double[]{hue,delta==0?0:delta/(1-Math.abs(2*light-1)),light};}
			case 5:{ // HSL -> RGB
				double h=c[0],sat=c[1],l=c[2],chroma=(1-Math.abs(2*l-1))*sat;return fromHue(h,chroma,l-chroma/2);}
			case 7:{ // HSV -> RGB
				double chroma=c[2]*c[1];return fromHue(c[0],chroma,c[2]-chroma);}
			default:return c;
		}
	}
	private static double[] fromHue(double hue,double chroma,double m){
		double h=(hue-Math.floor(hue))*6,x=chroma*(1-Math.abs(h%2-1));double r,g,b;
		if(h<1){r=chroma;g=x;b=0;}else if(h<2){r=x;g=chroma;b=0;}else if(h<3){r=0;g=chroma;b=x;}else if(h<4){r=0;g=x;b=chroma;}else if(h<5){r=x;g=0;b=chroma;}else{r=chroma;g=0;b=x;}
		return new double[]{r+m,g+m,b+m};
	}

	boolean pack12(M68kCpu cpu) throws M68kCpuException {
		int selector=memory.readWord(cpu.a[7]);cpu.a[7]+=2;
		switch(selector){
			case 9:{ // GetColor(where, prompt, inColor, VAR outColor) : Boolean
				int out=memory.readLong(cpu.a[7]),in=memory.readLong(cpu.a[7]+4),prompt=memory.readLong(cpu.a[7]+8);cpu.a[7]+=16;
				final String title=tb.readPascal(prompt);final Color initial=new Color(readRgb(in));
				Color chosen=tb.host().ask(new java.util.concurrent.Callable<Color>(){@Override public Color call(){return OklabColorChooserPanel.showDialog(tb.host().anyWindow(),title,initial);}});
				// a colour kept as it was comes back with its 16-bit components (XTernal Collection's PickColor turned 12555,30665,55512
				// into 12593,30583,55512 when OK was clicked): the host's chooser has 8 bits a component
				if(chosen!=null){if((chosen.getRGB()&0xffffff)==(initial.getRGB()&0xffffff)){if(out!=in)memory.copy(in,out,6);}else writeRgb(out,chosen.getRGB()&0xffffff);}
				tb.writeBoolean(cpu.a[7],chosen!=null);return true;}
			// Fix2SmallFract/SmallFract2Fix: a SmallFract is the fraction of a Fixed in [0, 1] (0..$FFFF) - they used to shift
			// the integer half in and out
			case 1:{int f=memory.readLong(cpu.a[7]);cpu.a[7]+=4;memory.writeWord(cpu.a[7],f<0?0:f>=0x10000?0xffff:f&0xffff);return true;}
			case 2:{int f=memory.readWord(cpu.a[7]);cpu.a[7]+=2;memory.writeLong(cpu.a[7],f);return true;}
			case 3:case 4:case 5:case 6:case 7:case 8:{ // CMY2RGB, RGB2CMY, HSL2RGB, RGB2HSL, HSV2RGB, RGB2HSV (source, VAR destination), 3 SmallFracts each
				int dst=memory.readLong(cpu.a[7]),src=memory.readLong(cpu.a[7]+4);cpu.a[7]+=8;
				double[] in=new double[3];for(int i=0;i<3;i++)in[i]=memory.readWord(src+2*i)/65535.0;
				double[] out=convertColor(selector,in);
				for(int i=0;i<3;i++)memory.writeWord(dst+2*i,(int)Math.round(Math.max(0,Math.min(1,out[i]))*65535));
				return true;}
			// Color Picker 2.x calls (NewPicker: $0217, $0319, $041A...) are not here: the high byte is the argument size in words, so
			// pop the arguments and answer unimpErr in the OSErr result instead of leaving them on the stack
			default:cpu.a[7]+=(selector>>>8)*2;if(selector>>>8!=0)memory.writeWord(cpu.a[7],-4);return true;
		}
	}
	// ------------------------------------------------------------------ Picture Utilities (Pack 15)
	/** A NewPictInfo collection: pictures recorded so far and the verb/colour request they were opened with. */
	private static final class PictInfoCollection {final int verb,colorsRequested,pickMethod;final java.util.List<QuickDraw68k.PictureSurvey> surveys=new java.util.ArrayList<QuickDraw68k.PictureSurvey>();
		PictInfoCollection(int verb,int colorsRequested,int pickMethod){this.verb=verb;this.colorsRequested=colorsRequested;this.pickMethod=pickMethod;}}
	private final java.util.Map<Integer,PictInfoCollection> pictInfos=new java.util.HashMap<Integer,PictInfoCollection>();
	private int nextPictInfoId=1;
	private static final int PICT_INFO_VERSION=0,RETURN_COLOR_TABLE=1,RETURN_PALETTE=2,SUPPRESS_BLACK_AND_WHITE=16,PICT_INFO_VERB_ERR=-11000,PICT_INFO_ID_ERR=-11001,PICT_INFO_VERSION_ERR=-11002;
	/**
	 * GetPictInfo and its relatives (PixelCat sizes its offscreen from the depth and frame GetPictInfo reports; the
	 * old stub answered unimpErr and left the record as it was). The picture is played into an offscreen and its
	 * opcodes tallied ({@link QuickDraw68k#surveyPicture}); the colour table and palette come from the picture's own
	 * indexed PixMap when it has one, else from the most used colours of the rendering.
	 */
	boolean pack15(M68kCpu cpu) throws M68kCpuException {
		int selector=cpu.d[0]&0xffff,sp=cpu.a[7];int args=(selector>>>8)*2;cpu.a[7]+=args;
		try{
			switch(selector){
				case 0x0800:case 0x0801:{ // GetPictInfo(picture, info, verb, colorsRequested, pickMethod, version) / GetPixMapInfo(pixMap, ...)
					int version=(short)memory.readWord(sp),pickMethod=(short)memory.readWord(sp+2),colorsRequested=(short)memory.readWord(sp+4),verb=(short)memory.readWord(sp+6),info=memory.readLong(sp+8),handle=memory.readLong(sp+12);
					if(version!=PICT_INFO_VERSION){memory.writeWord(cpu.a[7],PICT_INFO_VERSION_ERR);return true;}
					QuickDraw68k.PictureSurvey survey=selector==0x0800?surveyPictureHandle(handle):surveyPixMapHandle(handle);
					if(survey==null||info==0){memory.writeWord(cpu.a[7],-50);return true;}
					writePictInfo(info,java.util.Collections.singletonList(survey),verb,colorsRequested,pickMethod);memory.writeWord(cpu.a[7],0);return true;}
				case 0x0602:{ // NewPictInfo(&id, verb, colorsRequested, pickMethod, version)
					int version=(short)memory.readWord(sp),pickMethod=(short)memory.readWord(sp+2),colorsRequested=(short)memory.readWord(sp+4),verb=(short)memory.readWord(sp+6),idPtr=memory.readLong(sp+8);
					if(version!=PICT_INFO_VERSION){memory.writeWord(cpu.a[7],PICT_INFO_VERSION_ERR);return true;}
					int id=nextPictInfoId++;pictInfos.put(id,new PictInfoCollection(verb,colorsRequested,pickMethod));if(idPtr!=0)memory.writeLong(idPtr,id);memory.writeWord(cpu.a[7],0);return true;}
				case 0x0403:case 0x0404:{ // RecordPictInfo(id, picture) / RecordPixMapInfo(id, pixMap)
					int handle=memory.readLong(sp),id=memory.readLong(sp+4);PictInfoCollection c=pictInfos.get(id);
					if(c==null){memory.writeWord(cpu.a[7],PICT_INFO_ID_ERR);return true;}
					QuickDraw68k.PictureSurvey survey=selector==0x0403?surveyPictureHandle(handle):surveyPixMapHandle(handle);
					if(survey!=null)c.surveys.add(survey);memory.writeWord(cpu.a[7],survey==null?-50:0);return true;}
				case 0x0505:{ // RetrievePictInfo(id, info, colorsRequested)
					int colorsRequested=(short)memory.readWord(sp),info=memory.readLong(sp+2),id=memory.readLong(sp+6);PictInfoCollection c=pictInfos.get(id);
					if(c==null){memory.writeWord(cpu.a[7],PICT_INFO_ID_ERR);return true;}
					if(info!=0)writePictInfo(info,c.surveys,c.verb,colorsRequested,c.pickMethod);memory.writeWord(cpu.a[7],0);return true;}
				case 0x0206:{int id=memory.readLong(sp);memory.writeWord(cpu.a[7],pictInfos.remove(id)==null?PICT_INFO_ID_ERR:0);return true;} // DisposePictInfo(id)
				default:memory.writeWord(cpu.a[7],PICT_INFO_VERB_ERR);return true;
			}
		}catch(M68kCpuException e){memory.writeWord(cpu.a[7],-50);return true;}
	}
	private QuickDraw68k.PictureSurvey surveyPictureHandle(int handle) throws M68kCpuException {
		int size=tb.api.handleSize(handle),pointer=handle==0?0:memory.readLong(handle);if(pointer==0||size<11)return null;
		return qd.surveyPicture(memory.readBytes(pointer,size));
	}
	/** A PixMap on its own is surveyed as a one-opcode picture would be: its depth, resolution and colour table. */
	private QuickDraw68k.PictureSurvey surveyPixMapHandle(int handle) throws M68kCpuException {
		int map=handle==0?0:memory.readLong(handle);if(map==0)return null;
		QuickDraw68k.PictureSurvey s=new QuickDraw68k.PictureSurvey();s.pixMapCount=1;s.depth=Math.max(1,memory.readWord(map+32));s.hRes=memory.readLong(map+22);s.vRes=memory.readLong(map+26);/* after baseAddr, rowBytes, bounds, pmVersion, packType, packSize */
		s.frame=new int[]{(short)memory.readWord(map+6),(short)memory.readWord(map+8),(short)memory.readWord(map+10),(short)memory.readWord(map+12)};
		int table=memory.readLong(map+42);if(s.depth<=8&&table!=0){int t=memory.readLong(table);if(t!=0){int count=(short)memory.readWord(t+6)+1;if(count>0&&count<=256){s.colorTable=new int[count];for(int i=0;i<count;i++)s.colorTable[i]=(memory.readWord(t+8+i*8+2)>>>8)<<16|(memory.readWord(t+8+i*8+4)>>>8)<<8|(memory.readWord(t+8+i*8+6)>>>8);}}}
		return s;
	}
	/** Fills a PictInfo record (version 0 layout, 104 bytes) from the surveys: totals, the deepest depth, the union of the frames, and the colours asked for. */
	private void writePictInfo(int info,java.util.List<QuickDraw68k.PictureSurvey> surveys,int verb,int colorsRequested,int pickMethod) throws M68kCpuException {
		for(int i=0;i<104;i++)memory.writeByte(info+i,0);
		int depth=1,hRes=72<<16,vRes=72<<16;int[] frame=null,table=null;
		java.util.LinkedHashMap<Integer,Integer> histogram=new java.util.LinkedHashMap<Integer,Integer>();
		long text=0,line=0,rect=0,rRect=0,oval=0,arc=0,poly=0,region=0,bitMap=0,pixMap=0,comment=0;
		for(QuickDraw68k.PictureSurvey s:surveys){
			if(s.depth>depth){depth=s.depth;hRes=s.hRes;vRes=s.vRes;}
			if(table==null&&s.colorTable!=null)table=s.colorTable;
			text+=s.textCount;line+=s.lineCount;rect+=s.rectCount;rRect+=s.rRectCount;oval+=s.ovalCount;arc+=s.arcCount;poly+=s.polyCount;region+=s.regionCount;bitMap+=s.bitMapCount;pixMap+=s.pixMapCount;comment+=s.commentCount;
			if(s.frame!=null)frame=frame==null?s.frame.clone():new int[]{Math.min(frame[0],s.frame[0]),Math.min(frame[1],s.frame[1]),Math.max(frame[2],s.frame[2]),Math.max(frame[3],s.frame[3])};
			if(s.image!=null){int w=s.image.getWidth(),h=s.image.getHeight();int step=Math.max(1,(int)Math.sqrt((double)w*h/65536)); // a sample of at most ~64K pixels
				for(int y=0;y<h;y+=step)for(int x=0;x<w;x+=step){int rgb=s.image.getRGB(x,y)&0xffffff;Integer n=histogram.get(rgb);histogram.put(rgb,n==null?1:n+1);}}
		}
		// the colours: the picture's own table when it has one, else the most used colours of the rendering (systemMethod, popularMethod and
		// medianMethod alike); black and white are always included unless suppressed
		java.util.List<Integer> colors=new java.util.ArrayList<Integer>();
		if(table!=null)for(int c:table)colors.add(c);
		else{java.util.List<java.util.Map.Entry<Integer,Integer>> entries=new java.util.ArrayList<java.util.Map.Entry<Integer,Integer>>(histogram.entrySet());
			entries.sort((a,b)->b.getValue()-a.getValue());for(java.util.Map.Entry<Integer,Integer> e:entries)colors.add(e.getKey());}
		if((verb&SUPPRESS_BLACK_AND_WHITE)==0){if(!colors.contains(0xffffff))colors.add(0,0xffffff);if(!colors.contains(0))colors.add(0);}
		int limit=colorsRequested<=0?256:Math.min(256,colorsRequested);
		if(colors.size()>limit){java.util.List<Integer> kept=new java.util.ArrayList<Integer>(colors.subList(0,limit));if((verb&SUPPRESS_BLACK_AND_WHITE)==0){if(!kept.contains(0xffffff))kept.set(0,0xffffff);if(!kept.contains(0))kept.set(kept.size()-1,0);}colors=kept;}
		memory.writeWord(info,PICT_INFO_VERSION);memory.writeLong(info+2,Math.max(histogram.size(),table!=null?table.length:0));
		memory.writeLong(info+14,hRes);memory.writeLong(info+18,vRes);memory.writeWord(info+22,depth);
		if(frame!=null)for(int i=0;i<4;i++)memory.writeWord(info+24+i*2,frame[i]);
		memory.writeLong(info+32,(int)text);memory.writeLong(info+36,(int)line);memory.writeLong(info+40,(int)rect);memory.writeLong(info+44,(int)rRect);memory.writeLong(info+48,(int)oval);memory.writeLong(info+52,(int)arc);
		memory.writeLong(info+56,(int)poly);memory.writeLong(info+60,(int)region);memory.writeLong(info+64,(int)bitMap);memory.writeLong(info+68,(int)pixMap);memory.writeLong(info+72,(int)comment);
		if((verb&(RETURN_COLOR_TABLE|RETURN_PALETTE))!=0){
			byte[] ctab=new byte[8+colors.size()*8];putWord(ctab,6,colors.size()-1);
			for(int i=0;i<colors.size();i++){int c=colors.get(i),at=8+i*8;putWord(ctab,at,i);putWord(ctab,at+2,(c>>>16&255)*257);putWord(ctab,at+4,(c>>>8&255)*257);putWord(ctab,at+6,(c&255)*257);}
			int tableHandle=tb.api.createHandle(ctab);
			if((verb&RETURN_COLOR_TABLE)!=0)memory.writeLong(info+10,tableHandle);
			if((verb&RETURN_PALETTE)!=0){int handle=tb.api.createHandle(new byte[16+colors.size()*16]);Palette p=new Palette(colors.size());
				for(int i=0;i<colors.size();i++){p.usage[i]=2;p.tolerance[i]=0;} // pmTolerant
				colorTableToPaletteObject(tableHandle,p,0);palettes.put(handle,p);memory.writeWord(memory.readLong(handle),colors.size());writePaletteRecord(handle,p);memory.writeLong(info+6,handle);}
			if((verb&RETURN_COLOR_TABLE)==0)tb.api.disposeHandleAndFree(tableHandle);
		}
	}
	private static void putWord(byte[] b,int at,int v){b[at]=(byte)(v>>>8);b[at+1]=(byte)v;}
}

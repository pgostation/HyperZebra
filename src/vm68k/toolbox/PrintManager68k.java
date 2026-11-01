import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.PageRanges;

/**
 * The Printing Manager (_PrGlue $A8FD; the selector long's high byte is the routine, bits 8-15 the argument bytes) on
 * the host's printers: PrStlDialog/PrJobDialog are the host's page setup and print dialogs, whose results fill the
 * TPrint record (72 dpi unless the program picked a higher resolution through PrGeneral's setRsl - the printer offers
 * 72 dpi up to the print resolution preference, PCARD.printDpi - the paper and imageable area of the chosen page format,
 * copies and page range). PrOpenDoc
 * gives the program a printing port whose GrafPort is a 32-bit offscreen page (an 8-bit one when memory is short);
 * every PrOpenPage/PrClosePage pair captures the page the program drew as an image, and PrCloseDoc prints the pages
 * through a PrinterJob. The draft loop is reported (bJDocLoop = 0) so programs draw pages directly; PrPicFile has
 * nothing left to do.
 */
final class PrintManager68k {
	private static final int PRINT_ERR=0x944,NO_ERR=0,I_PR_ABORT=-128,I_IO_ABORT=-27,I_MEM_FULL=-108;
	/** prStl.wDev: the device is a QuickDraw printer (bDevCItoh, the ImageWriter's family), not a LaserWriter (bDevLaser $03xx). The pages are
	 *  QuickDraw drawing captured into a bitmap, so a program told it prints to a PostScript device sends its page as PostScript PicComments
	 *  and the page came out blank (FreeHand 8). In the low byte, bit 1 (W_DEV_PORTRAIT) says the record is in portrait (the ImageWriter
	 *  driver's flag: PageMaker 6 reads a record without it as landscape and turns it a quarter turn itself, so a portrait page came out
	 *  sideways and cut); bit 0 is HyperZebra's own landscape mark. */
	static final int W_DEV=0x0100,W_DEV_PORTRAIT=0x0002,W_DEV_LANDSCAPE=0x0001;
	private static final int TPRINT_SIZE=120,TPRPORT_SIZE=178,TPRDLG_SIZE=200;
	private final Toolbox68k tb;private final M68kMemory memory;

	/** What one TPrint Handle carries on the host side: its page format and dialog results, and the document open on it. */
	private static final class Session {
		PageFormat format;PrintRequestAttributeSet attributes=new HashPrintRequestAttributeSet();int copies=1,firstPage=1,lastPage=9999;
		int xDpi=72,yDpi=72; // the resolution PrGeneral setRsl chose (rPage and the page image are in these dots)
		int dialog;int lastDialogInit; // the fake TPrDlg record and which Init built it last (0x3c style, 0x44 job)
	}
	/** A document opened with PrOpenDoc: the printing port and its offscreen page. */
	private static final class Document {
		int hPrint,port,gWorld;boolean ownPort,pageOpen;int width,height,depth;final List<BufferedImage> pages=new ArrayList<BufferedImage>();
	}
	private final Map<Integer,Session> sessions=new HashMap<Integer,Session>();
	private final Map<Integer,Document> documents=new HashMap<Integer,Document>();
	private PrinterJob job;

	PrintManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	private PrinterJob job(){if(job==null)job=PrinterJob.getPrinterJob();return job;}
	private Session session(int hPrint){Session s=sessions.get(hPrint);if(s==null){s=new Session();sessions.put(hPrint,s);}if(s.format==null)s.format=defaultFormat();return s;}
	private PageFormat defaultFormat(){try{return job().defaultPage();}catch(RuntimeException e){PageFormat f=new PageFormat();return f;}}
	private static boolean headless(){return java.awt.GraphicsEnvironment.isHeadless();}
	private void setError(int error) throws M68kCpuException {memory.writeWord(PRINT_ERR,error);}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		int routine=selector>>>24,argBytes=(selector>>>8)&0xff,sp=cpu.a[7],result=sp+argBytes;
		if(Mac68kApi.TRACE)System.err.println("[68K] PrGlue routine $"+Integer.toHexString(routine)+" args "+argBytes);
		switch(routine){
			case 0xc8:case 0xd0:case 0x80:case 0x88:case 0xa8:case 0xb0:setError(NO_ERR);break; // PrOpen/PrClose/PrDrvrOpen/PrDrvrClose/PrPurge/PrNoPurge
			case 0x20:{int h=memory.readLong(sp);Session s=session(h);s.format=defaultFormat();s.copies=1;s.firstPage=1;s.lastPage=9999;s.xDpi=s.yDpi=72;fillRecord(h,s);setError(NO_ERR);break;} // PrintDefault
			case 0x52:{int h=memory.readLong(sp);boolean changed=!recordValid(h);Session s=session(h);if(changed)fillRecord(h,s);else readJob(h,s);setError(NO_ERR);cpu.a[7]=result;memory.writeWord(result,changed?0x0100:0);return true;} // PrValidate
			case 0x2a:{int h=memory.readLong(sp);cpu.a[7]=result;tb.writeBoolean(result,styleDialog(h));return true;} // PrStlDialog
			case 0x32:{int h=memory.readLong(sp);cpu.a[7]=result;tb.writeBoolean(result,jobDialog(h));return true;}   // PrJobDialog
			case 0x3c:case 0x44:{int h=memory.readLong(sp);Session s=session(h);if(s.dialog==0){s.dialog=memory.allocate(TPRDLG_SIZE,2);memory.fill(s.dialog,TPRDLG_SIZE,0);}s.lastDialogInit=routine;
				cpu.a[7]=result;memory.writeLong(result,s.dialog);return true;} // PrStlInit / PrJobInit: a record the program may customize (its items are not shown)
			case 0x4a:{int init=memory.readLong(sp),h=memory.readLong(sp+4);Session s=session(h); // PrDlgMain(hPrint, pDlgInit)
				if(init!=0){int saved=cpu.a[7];cpu.a[7]-=4;cpu.pushLong(h);try{cpu.callPascal(init,4);}finally{cpu.a[7]=saved;}}
				boolean ok=s.lastDialogInit==0x3c?styleDialog(h):jobDialog(h);
				cpu.a[7]=result;tb.writeBoolean(result,ok);return true;}
			case 0x58:{int src=memory.readLong(sp+4),dst=memory.readLong(sp);int s1=memory.readLong(src),d1=memory.readLong(dst);if(s1!=0&&d1!=0){for(int i=62;i<82;i++)memory.writeByte(d1+i,memory.readByte(s1+i));Session a=session(src),b=session(dst);b.copies=a.copies;b.firstPage=a.firstPage;b.lastPage=a.lastPage;b.attributes=a.attributes;}break;} // PrJobMerge
			case 0x04:{int pIOBuf=memory.readLong(sp),pPrPort=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]=result;memory.writeLong(result,openDocument(cpu,h,pPrPort));return true;} // PrOpenDoc
			case 0x08:closeDocument(cpu,memory.readLong(sp));break;                                    // PrCloseDoc(pPrPort)
			case 0x10:openPage(cpu,memory.readLong(sp+4));break;                                      // PrOpenPage(pPrPort, pPageFrame)
			case 0x18:closePage(memory.readLong(sp));break;                                            // PrClosePage(pPrPort)
			case 0x60:{int status=memory.readLong(sp),pPrPort=memory.readLong(sp+12),h=memory.readLong(sp+16); // PrPicFile(hPrint, pPrPort, pIOBuf, pDevBuf, prStatus): pages already went out at PrCloseDoc
				if(status!=0){memory.fill(status,26,0);memory.writeLong(status+14,h);memory.writeLong(status+18,pPrPort);}break;}
			case 0xba:cpu.a[7]=result;memory.writeWord(result,memory.readWord(PRINT_ERR));return true; // PrError
			case 0xc0:setError((short)memory.readWord(sp));break;                                       // PrSetError
			case 0x70:prGeneral(memory.readLong(sp));break;                                           // PrGeneral(pData)
			case 0x94:cpu.a[7]=result;memory.writeLong(result,0);return true;                          // PrDrvrDCE
			case 0x9a:cpu.a[7]=result;memory.writeWord(result,0x0700);return true;                     // PrDrvrVers: 7.0
			case 0xa0:prCtlCall((short)memory.readWord(sp+12),memory.readLong(sp+8),memory.readLong(sp+4));break; // PrCtlCall(iWhichCtl, lParam1, lParam2, lParam3)
			default:break;                                                                             // the rest
		}
		cpu.a[7]=result;return true;
	}

	// ------------------------------------------------------------------ draft text (PrCtlCall)

	/**
	 * The low-level printer calls of the Printing Manager's driver (IM II-162): text sent with iPrIOCtl, line feeds and page ends
	 * with iPrDevCtl, as the ImageWriter prints in draft mode with its own font - Dartmouth's TextStream XCMD prints its fields
	 * this way, and nothing came out. Lines go into pages of a fixed-pitch font (12 characters per inch, 6 lines per inch, as
	 * the ImageWriter's draft text) that are printed at lPrDocClose like the pages of a printing port.
	 */
	private final StringBuilder draftLine=new StringBuilder();
	private final List<String> draftLines=new ArrayList<String>();
	private final List<BufferedImage> draftPages=new ArrayList<BufferedImage>();
	private Session draftSession;
	private static final int I_PR_IO_CTL=5,I_PR_DEV_CTL=7;
	private void prCtlCall(int which,int lParam1,int lParam2) throws M68kCpuException {
		if(which==I_PR_IO_CTL){ // lParam1: the bytes, lParam2: how many
			// a printer takes control bytes as commands, not glyphs: TextStream sends each line with its CR, the last one with the
			// string's NUL, which printed as a box
			if(lParam1!=0&&lParam2>0){String text=tb.decode(memory.readBytes(lParam1,Math.min(lParam2,32767)));
				for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='\t'||c>=' '&&c!=0x7f)draftLine.append(c);else if(c=='\r')draftLine.append(' ');}}
		}
		else if(which==I_PR_DEV_CTL)switch(lParam1>>>16){
			case 1:draftLine.setLength(0);draftLines.clear();draftPages.clear();break; // lPrReset (lPrDocOpen)
			case 3:if((lParam1&0xffff)!=0){draftLines.add(draftLine.toString());draftLine.setLength(0);}break; // lPrLineFeed: 0 = carriage return only, else a line feed (lPrLFSixth $FFFF, lPrLFEighth $FFFE, n dots)
			case 2:endDraftPage();break; // lPrPageEnd (lPrPageClose)
			case 4:break; // lPrPageOpen
			case 5:if(draftLine.length()>0||!draftLines.isEmpty())endDraftPage(); // lPrDocClose
				if(!draftPages.isEmpty()){if(draftSession==null){draftSession=new Session();draftSession.format=defaultFormat();}print(new ArrayList<BufferedImage>(draftPages),draftSession);}
				draftPages.clear();break;
			default:break;
		}
		setError(NO_ERR);
	}
	/** The lines so far become a page (8.5 x 11 inches at 144 dpi, the font 12 characters per inch, 6 lines per inch). */
	private void endDraftPage(){
		if(draftLine.length()>0){draftLines.add(draftLine.toString());draftLine.setLength(0);}
		BufferedImage page=new BufferedImage(1224,1584,BufferedImage.TYPE_INT_RGB);Graphics2D g=page.createGraphics();
		g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,page.getWidth(),page.getHeight());g.setColor(java.awt.Color.BLACK);
		g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,java.awt.Font.PLAIN,20)); // 12 pitch at 144 dpi: 12-pixel cells
		for(int i=0;i<draftLines.size();i++){String line=draftLines.get(i);float y=36+(i+1)*24-6;
			for(int c=0;c<line.length();c++)g.drawString(String.valueOf(line.charAt(c)),36+c*12,y);}
		g.dispose();draftPages.add(page);draftLines.clear();
	}

	// ------------------------------------------------------------------ TPrint record

	private boolean recordValid(int h) throws M68kCpuException {
		if(h==0)return true;int p=memory.readLong(h);if(p==0||tb.api.handleSize(h)<TPRINT_SIZE)return false;
		// a record made for another printer (a LaserWriter's, kept in a document or a program's defaults) is not this driver's: the real
		// drivers reformat it, and FreeHand 8 decides between PostScript and QuickDraw output from the wDev it finds there
		return memory.readWord(p)==3&&(memory.readWord(p+24)&0xff00)==(W_DEV&0xff00)&&(short)memory.readWord(p+4)>0&&(short)memory.readWord(p+6)>0&&(short)memory.readWord(p+12)>0&&(short)memory.readWord(p+14)>0;
	}
	/** Fills the TPrint record from the session's page format at its resolution (72 dpi unless setRsl chose more), keeping the job fields the session holds. */
	private void fillRecord(int h,Session s) throws M68kCpuException {
		if(h==0)return;
		if(tb.api.handleSize(h)<TPRINT_SIZE)tb.api.resizeHandle(h,TPRINT_SIZE);
		int p=memory.readLong(h);if(p==0)return;
		PageFormat f=s.format;int paperW=(int)Math.round(f.getWidth()),paperH=(int)Math.round(f.getHeight());
		int ix=(int)Math.round(f.getImageableX()),iy=(int)Math.round(f.getImageableY()),iw=(int)Math.round(f.getImageableWidth()),ih=(int)Math.round(f.getImageableHeight());
		if(iw<=0||ih<=0){ix=18;iy=18;iw=Math.max(1,paperW-36);ih=Math.max(1,paperH-36);}
		// rPage and rPaper are in printer dots: points scaled by the resolution
		int kx=s.xDpi,ky=s.yDpi;
		int pw=Math.max(1,iw*kx/72),ph=Math.max(1,ih*ky/72),px=ix*kx/72,py=iy*ky/72,pW=paperW*kx/72,pH=paperH*ky/72;
		memory.fill(p,TPRINT_SIZE,0);
		memory.writeWord(p,3);                                                                                      // iPrVersion
		for(int info:new int[]{p+2,p+32}){memory.writeWord(info,0);memory.writeWord(info+2,ky);memory.writeWord(info+4,kx);memory.writeWord(info+6,0);memory.writeWord(info+8,0);memory.writeWord(info+10,ph);memory.writeWord(info+12,pw);} // prInfo/prInfoPT: iDev, iVRes, iHRes, rPage
		memory.writeWord(p+16,-py);memory.writeWord(p+18,-px);memory.writeWord(p+20,pH-py);memory.writeWord(p+22,pW-px); // rPaper, relative to the page
		boolean landscape=f.getOrientation()!=PageFormat.PORTRAIT;
		memory.writeWord(p+24,W_DEV|(landscape?W_DEV_LANDSCAPE:W_DEV_PORTRAIT));memory.writeWord(p+26,paperH*120/72);memory.writeWord(p+28,paperW*120/72);memory.writeByte(p+30,0);memory.writeByte(p+31,1); // prStl: wDev, iPageV, iPageH (1/120 inch), bPort, feed
		int rowBytes=((pw+31)/32)*4;
		memory.writeWord(p+46,rowBytes);memory.writeWord(p+48,ph);memory.writeWord(p+50,pw);memory.writeWord(p+52,(short)Math.min(32767,rowBytes*ph));memory.writeWord(p+54,1);memory.writeByte(p+56,0);memory.writeByte(p+57,1);memory.writeByte(p+58,1);memory.writeByte(p+59,0); // prXInfo
		memory.writeWord(p+62,s.firstPage);memory.writeWord(p+64,s.lastPage);memory.writeWord(p+66,s.copies);memory.writeByte(p+68,0);memory.writeByte(p+69,1); // prJob: iFstPage, iLstPage, iCopies, bJDocLoop (draft), fFromUsr
	}
	/**
	 * The printer driver's 'PREC' 0, the print record PrintDefault starts from: US Letter at 72 dpi with half-inch margins less
	 * a quarter (the page Java's printing defaults to when it knows no printer). It sits in the System file here, where the
	 * printer resource file PrOpen opens would be in the resource chain: Swivel 3D reads it right after PrOpen and, without
	 * one, stopped at every start with "Memory is Full.".
	 */
	static byte[] defaultPrintRecord(){
		byte[] r=new byte[TPRINT_SIZE];int paperW=612,paperH=792,ix=18,iy=18,iw=paperW-36,ih=paperH-36;
		put(r,0,3);                                                                                          // iPrVersion
		for(int info:new int[]{2,32}){put(r,info+2,72);put(r,info+4,72);put(r,info+10,ih);put(r,info+12,iw);} // prInfo/prInfoPT: iVRes, iHRes, rPage
		put(r,16,-iy);put(r,18,-ix);put(r,20,paperH-iy);put(r,22,paperW-ix);                                 // rPaper, relative to the page
		put(r,24,W_DEV|W_DEV_PORTRAIT);put(r,26,paperH*120/72);put(r,28,paperW*120/72);r[31]=1;               // prStl: wDev, iPageV, iPageH, feed
		int rowBytes=((iw+31)/32)*4;
		put(r,46,rowBytes);put(r,48,ih);put(r,50,iw);put(r,52,Math.min(32767,rowBytes*ih));put(r,54,1);r[57]=1;r[58]=1; // prXInfo
		put(r,62,1);put(r,64,9999);put(r,66,1);r[69]=1;                                                       // prJob: pages 1-9999, one copy
		return r;
	}
	private static void put(byte[] b,int at,int word){b[at]=(byte)(word>>8);b[at+1]=(byte)word;}
	/** The job fields a program may have edited itself (copies, page range). */
	private void readJob(int h,Session s) throws M68kCpuException {
		int p=h==0?0:memory.readLong(h);if(p==0)return;
		s.firstPage=(short)memory.readWord(p+62);s.lastPage=(short)memory.readWord(p+64);s.copies=Math.max(1,(short)memory.readWord(p+66));
	}

	// ------------------------------------------------------------------ dialogs

	private boolean styleDialog(int h) throws M68kCpuException {
		Session s=session(h);readJob(h,s);
		if(headless()||!dialogs){fillRecord(h,s);return true;}
		PageFormat chosen;try{chosen=job().pageDialog(s.format);}catch(RuntimeException e){chosen=s.format;}
		if(chosen==s.format)return false; // cancelled: the same object comes back
		s.format=chosen;fillRecord(h,s);return true;
	}
	private boolean jobDialog(int h) throws M68kCpuException {
		Session s=session(h);readJob(h,s);
		// no dialog shown: as a confirmed one with no range - all pages (PrintReport calls PrJobDialog on a fresh record and
		// prints while iLstPage >= the page: the record's uninitialised 0 ended the report after its first row)
		if(headless()||!dialogs){s.firstPage=1;s.lastPage=9999;s.copies=Math.max(1,s.copies);fillRecord(h,s);return true;}
		s.attributes=new HashPrintRequestAttributeSet();s.attributes.add(new Copies(s.copies));
		boolean ok;try{ok=job().printDialog(s.attributes);}catch(RuntimeException e){ok=false;}
		if(!ok)return false;
		Copies copies=(Copies)s.attributes.get(Copies.class);s.copies=copies==null?1:Math.max(1,copies.getValue());
		PageRanges ranges=(PageRanges)s.attributes.get(PageRanges.class);
		if(ranges!=null&&ranges.getMembers().length>0){int[][] m=ranges.getMembers();s.firstPage=m[0][0];s.lastPage=m[m.length-1][1];}else{s.firstPage=1;s.lastPage=9999;}
		fillRecord(h,s);return true;
	}

	// ------------------------------------------------------------------ documents and pages

	/** PrOpenDoc: a TPrPort whose GrafPort is an offscreen GWorld the size of rPage; the port becomes current. */
	private int openDocument(M68kCpu cpu,int h,int pPrPort) throws M68kException {
		setError(NO_ERR);int p=h==0?0:memory.readLong(h);if(p==0){setError(I_PR_ABORT);return 0;}
		Document d=new Document();d.hPrint=h;
		int top=(short)memory.readWord(p+8),left=(short)memory.readWord(p+10),bottom=(short)memory.readWord(p+12),right=(short)memory.readWord(p+14);
		d.width=Math.max(1,right-left);d.height=Math.max(1,bottom-top);
		if(Mac68kApi.TRACE)System.err.printf("[68K] PrOpenDoc rPage (%d,%d,%d,%d) wDev $%04X iPageV %d iPageH %d iVRes %d iHRes %d rPaper (%d,%d,%d,%d)%n",top,left,bottom,right,memory.readWord(p+24),(short)memory.readWord(p+26),(short)memory.readWord(p+28),(short)memory.readWord(p+4),(short)memory.readWord(p+6),(short)memory.readWord(p+16),(short)memory.readWord(p+18),(short)memory.readWord(p+20),(short)memory.readWord(p+22));
		if(pPrPort!=0){d.port=pPrPort;}else{try{d.port=memory.allocate(TPRPORT_SIZE,2);d.ownPort=true;}catch(M68kCpuException e){setError(I_MEM_FULL);return 0;}}
		memory.fill(d.port,TPRPORT_SIZE,0);
		int scratch=memory.allocate(16,2);
		try{
			memory.writeWord(scratch+4,0);memory.writeWord(scratch+6,0);memory.writeWord(scratch+8,d.height);memory.writeWord(scratch+10,d.width);
			for(int depth:new int[]{32,8,1}){
				if((long)d.width*d.height*depth/8+65536>memory.availableBytes())continue;
				int saved=cpu.a[7];cpu.a[7]-=2;int result=cpu.a[7];
				cpu.pushLong(scratch);cpu.pushWord(depth);cpu.pushLong(scratch+4);cpu.pushLong(0);cpu.pushLong(0);cpu.pushLong(0);
				cpu.d[0]=0x00160000;tb.api.quickDraw().handle(cpu,0xb1d);int error=(short)memory.readWord(result);cpu.a[7]=saved;
				int gWorld=memory.readLong(scratch);
				if(error==0&&gWorld!=0){d.gWorld=gWorld;d.depth=depth;break;}
			}
		}finally{memory.free(scratch);}
		if(d.gWorld==0){if(d.ownPort)memory.free(d.port);setError(I_MEM_FULL);return 0;}
		memory.copy(d.gWorld,d.port,108);                 // gPort: the GWorld's CGrafPort, sharing its PixMap and regions
		int saved=cpu.a[7];cpu.pushLong(d.port+108);tb.quickDraw.handle(cpu,0x8ea);cpu.a[7]=saved; // gProcs = SetStdProcs
		memory.writeLong(d.port+104,d.port+108);          // grafProcs -> gProcs, as a printing port has them
		memory.writeByte(d.port+176,d.ownPort?1:0);memory.writeByte(d.port+177,1); // fOurPtr, fOurBits
		documents.put(d.port,d);
		setPort(cpu,d.port);
		if(Mac68kApi.TRACE)System.err.println("[68K] PrOpenDoc: "+d.width+"x"+d.height+" page at "+d.depth+" bits, port $"+Integer.toHexString(d.port));
		return d.port;
	}
	private void setPort(M68kCpu cpu,int port) throws M68kCpuException {int saved=cpu.a[7];cpu.pushLong(port);tb.api.quickDraw().handle(cpu,0x873);cpu.a[7]=saved;}

	/** PrOpenPage: a white page, the whole page as the clip, the printing port current. */
	private void openPage(M68kCpu cpu,int port) throws M68kCpuException {
		Document d=documents.get(port);if(d==null){setError(I_PR_ABORT);return;}
		if(d.pageOpen)closePage(port);
		int pixMap=memory.readLong(memory.readLong(d.gWorld+2));if(pixMap!=0){int base=memory.readLong(pixMap),rowBytes=memory.readWord(pixMap+4)&0x3fff;
			memory.fill(base,rowBytes*d.height,d.depth==32?0xff:0);} // white: all ones in a direct PixMap, index 0 (white) in the classic tables
		setPort(cpu,port);
		int saved=cpu.a[7];cpu.a[7]-=8;int rect=cpu.a[7];memory.writeWord(rect,0);memory.writeWord(rect+2,0);memory.writeWord(rect+4,d.height);memory.writeWord(rect+6,d.width);
		cpu.pushLong(rect);tb.api.quickDraw().handle(cpu,0x87b);cpu.a[7]=saved; // ClipRect(rPage)
		tb.api.quickDraw().handle(cpu,0x89e);                                  // PenNormal
		d.pageOpen=true;
	}
	/** PrClosePage: the page the program drew, as an image. */
	private void closePage(int port) throws M68kCpuException {
		Document d=documents.get(port);if(d==null||!d.pageOpen)return;d.pageOpen=false;
		tb.quickDraw.flushSurfaces();
		int pixMap=memory.readLong(memory.readLong(d.gWorld+2));if(pixMap==0)return;
		int base=memory.readLong(pixMap),rowBytes=memory.readWord(pixMap+4)&0x3fff;
		BufferedImage image=new BufferedImage(d.width,d.height,BufferedImage.TYPE_INT_RGB);
		int[] clut=null;if(d.depth<=8){int table=memory.readLong(pixMap+40),data=table==0?0:memory.readLong(table);if(data!=0){int count=(short)memory.readWord(data+6)+1;clut=new int[1<<d.depth];
			for(int i=0;i<count&&i<clut.length;i++){int e=data+8+i*8;clut[i]=(memory.readWord(e+2)>>>8)<<16|(memory.readWord(e+4)>>>8)<<8|memory.readWord(e+6)>>>8;}}}
		for(int y=0;y<d.height;y++){byte[] row=memory.readBytes(base+y*rowBytes,rowBytes);
			for(int x=0;x<d.width;x++){int rgb;
				if(d.depth==32){int o=x*4;rgb=(row[o+1]&0xff)<<16|(row[o+2]&0xff)<<8|(row[o+3]&0xff);}
				else if(d.depth==8){int v=row[x]&0xff;rgb=clut!=null&&v<clut.length?clut[v]:MacSystemPalette.rgb(v);}
				else{rgb=((row[x>>>3]>>>(7-(x&7)))&1)!=0?0:0xffffff;}
				image.setRGB(x,y,rgb);}}
		d.pages.add(image);
		if(Mac68kApi.TRACE)System.err.println("[68K] PrClosePage: page "+d.pages.size()+" captured");
	}
	/** PrCloseDoc: the captured pages go to the printer; the port and its page are released. */
	private void closeDocument(M68kCpu cpu,int port) throws M68kCpuException {
		Document d=documents.remove(port);if(d==null)return;
		if(d.pageOpen)closePage(port);
		Session s=session(d.hPrint);
		if(!d.pages.isEmpty()){
			if(memory.readWord(PRINT_ERR)==0)print(d.pages,s);
		}
		int saved=cpu.a[7];cpu.pushLong(d.gWorld);cpu.d[0]=0x00040004;tb.api.quickDraw().handle(cpu,0xb1d);cpu.a[7]=saved; // DisposeGWorld
		if(d.ownPort)memory.free(d.port);
	}
	/** Sends the pages to the printer chosen in the job dialog (or the default one), each scaled to the imageable area. */
	private void print(final List<BufferedImage> pages,Session s) throws M68kCpuException {
		String snapshot=System.getProperty("hz68k.snapshot");
		if(snapshot!=null){try{java.io.File dir=new java.io.File(snapshot);dir.mkdirs();for(int i=0;i<pages.size();i++)javax.imageio.ImageIO.write(pages.get(i),"png",new java.io.File(dir,"printed-"+(++snapshotPages)+".png"));}catch(java.io.IOException ignored){}} // debug: the pages as PNGs
		if(headless()||!enabled){lastPrinted=new ArrayList<BufferedImage>(pages);return;}
		PrinterJob j=job();
		j.setPrintable(new Printable(){@Override public int print(java.awt.Graphics g,PageFormat pf,int index){
			if(index>=pages.size())return NO_SUCH_PAGE;BufferedImage page=pages.get(index);Graphics2D g2=(Graphics2D)g;
			double sx=pf.getImageableWidth()/page.getWidth(),sy=pf.getImageableHeight()/page.getHeight(),scale=Math.min(sx,sy);
			g2.translate(pf.getImageableX(),pf.getImageableY());g2.scale(scale,scale);
			g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.drawImage(page,0,0,null);return PAGE_EXISTS;}},s.format);
		try{j.setCopies(s.copies);j.print(s.attributes);lastPrinted=new ArrayList<BufferedImage>(pages);}
		catch(PrinterException e){System.err.println("[68K] printing failed: "+e.getMessage());setError(I_IO_ABORT);}
		catch(RuntimeException e){System.err.println("[68K] printing failed: "+e);setError(I_IO_ABORT);}
	}
	/** The pages of the last document printed (tests, and headless runs where nothing can be printed). */
	List<BufferedImage> lastPrinted;private int snapshotPages;
	/** -Dhz68k.noprint=true: pages are captured but never sent to a printer (tests, scripted runs). */
	static boolean enabled=!Boolean.getBoolean("hz68k.noprint");
	/** -Dhz68k.noprintdialogs=true: the page setup and print dialogs are skipped as if confirmed (scripted runs). */
	static boolean dialogs=!Boolean.getBoolean("hz68k.noprintdialogs");

	/** The highest resolution offered to programs: the print resolution preference (72 dpi keeps the classic single resolution). */
	static int maxDpi(){return Math.max(72,PCARD.printDpi);}

	/**
	 * PrGeneral: getRslData offers every multiple of 72 dpi up to {@link #maxDpi()} (discrete records, like the
	 * ImageWriter's 72/144 dpi, and the same values as a range); setRsl picks one for the TPrint record, whose rPage and
	 * page image are then in those dots. Other opcodes succeed without effect.
	 */
	private void prGeneral(int data) throws M68kCpuException {
		if(data==0)return;int opcode=memory.readWord(data);
		if(Mac68kApi.TRACE)System.err.println("[68K] PrGeneral opcode "+opcode);
		memory.writeWord(data+2,0);
		if(opcode==4){int max=maxDpi(),count=Math.min(27,max/72); // TGetRslBlk: iRgType, xRslRg, yRslRg, iRslRecCnt, rgRslRec[27]
			memory.writeWord(data+8,1);memory.writeWord(data+10,72);memory.writeWord(data+12,max);memory.writeWord(data+14,72);memory.writeWord(data+16,max);memory.writeWord(data+18,count);
			for(int i=0;i<count;i++){memory.writeWord(data+20+i*4,72*(i+1));memory.writeWord(data+22+i*4,72*(i+1));}}
		else if(opcode==5){int h=memory.readLong(data+8),x=(short)memory.readWord(data+12),y=(short)memory.readWord(data+14); // TSetRslBlk: hPrint, iXRsl, iYRsl
			int max=maxDpi();
			if(x<72||y<72||x>max||y>max||h==0){memory.writeWord(data+2,1);} // noSuchRsl: the record keeps its resolution
			else{Session s=session(h);readJob(h,s);s.xDpi=x;s.yDpi=y;fillRecord(h,s);}}
		else if(opcode==8){memory.writeWord(data+8,0);} // getRotnOp: portrait
		else if(opcode<4||opcode>8)memory.writeWord(data+2,-192);              // opNotImpl
	}
}

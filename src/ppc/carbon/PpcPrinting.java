import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Carbon Printing Manager (the PM calls of CarbonLib, PMCore.h/PMApplication.h of Universal Interfaces 3.3 and 3.4)
 * the way CarbonLib did it on Mac OS 9: on top of the classic Printing Manager ({@link PrintManager68k}, reached through
 * the Toolbox bridge). A PMPageFormat and a PMPrintSettings each wrap a THPrint (a 120-byte TPrint Handle, with its host
 * session in PrintManager68k); the page format's record carries the paper, page and resolution, the print settings' the
 * job (copies, page range). A PMPrintSession (and the non-session API's PMPrintContext) keeps the document open on the
 * page format's record: PrOpenDoc's printing port is the QuickDraw graphics context, PrOpenPage/PrClosePage capture the
 * pages and PrCloseDoc prints them. The page setup and print dialogs are PrStlDialog/PrJobDialog, so their answers (and
 * -Dhz68k.noprintdialogs) are the classic ones.
 * <p>Every PM object is a small guest block (its reference), reference counted by PMRetain/PMRelease. Documents are
 * QuickDraw only (kPMDocumentFormatDefault / PICT); the printer is no PostScript device, so the PostScript calls become
 * PostScript PicComments in the printing port, which the page capture ignores.
 * <p>CarbonLib 1.6's additions (PMCore.h of Mac OS X 10.2): the printer is the only destination (file, fax and preview output
 * answer kPMNotImplemented), one printer and one paper are listed, and a page format or print settings object stands for its
 * job ticket - a CFMutableDictionary in the Mac OS X ticket format whose Printing Manager items are refreshed from the object -
 * for PMTicket... and the ticket routines CarbonLib exports (AddItem, SearchNodeForItem ...), whose private signatures are inferred.
 */
final class PpcPrinting {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcPrinting(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	// PMDefinitions.h
	static final int kPMCancel=0x80,kPMGeneralError=-30870,kPMOutOfScope=-30871,kPMInvalidParameter=-50,kPMNotImplemented=-30873,kPMNoSuchEntry=-30874,
		kPMInvalidPrintSettings=-30875,kPMInvalidPageFormat=-30876,kPMValueOutOfRange=-30877,kPMInvalidPrintSession=-30879,kPMInvalidPrinter=-30880,kPMObjectInUse=-30881;
	static final int kPMPortrait=1,kPMLandscape=2,kPMReversePortrait=3,kPMReverseLandscape=4,kPMColor=3;
	static final String FORMAT_DEFAULT="com.apple.documentformat.default",FORMAT_PICT="application/vnd.apple.printing-pict",FORMAT_PICT_PS="application/vnd.apple.printing-pict-ps",
		CONTEXT_DEFAULT="com.apple.graphicscontext.default",CONTEXT_QUICKDRAW="com.apple.graphicscontext.quickdraw",CONTEXT_COREGRAPHICS="com.apple.graphicscontext.coregraphics";
	private static final int TPRINT=120,I_PR_ABORT=-128;
	private static final int PS_BEGIN=190,PS_END=191,PS_HANDLE=192,PS_FILE=193; // PostScript PicComment kinds
	private static final int FLAT_FORMAT=0x504d7066,FLAT_SETTINGS=0x504d7073;  // 'PMpf', 'PMps': HyperZebra's flattened forms

	// ------------------------------------------------------------------ objects

	/** A PM object: its reference is a guest block, so it is a unique non-nil pointer to the program. */
	private abstract static class PMObject {int ref,count=1;int ticket;} // ticket: the job-ticket view (a CFMutableDictionary), made on demand
	/** PMPageFormat: a TPrint Handle for the paper/page/resolution, plus what TPrint has no room for. */
	private static final class PageFormat extends PMObject {
		int h;double scale=100;
		int orientation; // 0: as the record says (the page setup dialog's choice); kPMPortrait/kPMLandscape: forced by PMSetOrientation
		Map<Integer,byte[]> extended=new LinkedHashMap<Integer,byte[]>();
	}
	/** PMPrintSettings: a TPrint Handle whose job fields are the settings. */
	private static final class PrintSettings extends PMObject {
		int h;long rangeMin=1,rangeMax=0xffffffffL;int colorMode=kPMColor;String jobName;boolean collate=true;
		Map<Integer,byte[]> extended=new LinkedHashMap<Integer,byte[]>();
	}
	/** A document open on the classic Printing Manager: the printing port and the record it was opened with. */
	private static final class Document {int port,h;boolean pageOpen;int pages;}
	/** PMPrintSession, and (context = true) the non-session API's PMPrintContext. */
	private static final class Session extends PMObject {
		boolean context;int error,idleProc;Document document;String documentFormat=FORMAT_DEFAULT;
		Map<String,Integer> data=new HashMap<String,Integer>();
	}
	/** PMDialog: stands for the page setup or print dialog while a program customizes it (its items are not shown). */
	private static final class Dialog extends PMObject {boolean job;int target,dialogRef,itemProc,filterProc;boolean accepted,done;}
	/** PMPrinter: the host's printer as the classic Printing Manager presents it. */
	private static final class Printer extends PMObject {}

	private final Map<Integer,PMObject> objects=new HashMap<Integer,PMObject>();
	private Printer printer;
	/** The non-session API's state: PMError's value, the idle procedure, the context last begun (for PMPostScript...). */
	private int oldError,oldIdleProc;private Session oldContext;
	private int defaultFilter,defaultItemProc;

	private <T extends PMObject> T register(T o,int magic) throws M68kCpuException {o.ref=rt.allocate(16,4);memory.writeLong(o.ref,magic);objects.put(o.ref,o);return o;}
	private <T extends PMObject> T object(int ref,Class<T> type){PMObject o=ref==0?null:objects.get(ref);return type.isInstance(o)?type.cast(o):null;}
	private PageFormat format(int ref){return object(ref,PageFormat.class);}
	private PrintSettings settings(int ref){return object(ref,PrintSettings.class);}
	private Session session(int ref){return object(ref,Session.class);}
	private Dialog dialog(int ref){return object(ref,Dialog.class);}

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private void writeBoolean(int p,boolean b) throws M68kCpuException {if(p!=0)memory.writeByte(p,b?1:0);}
	private void writeLong(int p,int v) throws M68kCpuException {if(p!=0)memory.writeLong(p,v);}
	private void writeDouble(int p,double v) throws M68kCpuException {long b=Double.doubleToLongBits(v);memory.writeLong(p,(int)(b>>>32));memory.writeLong(p+4,(int)b);}
	private double readDouble(int p) throws M68kCpuException {return Double.longBitsToDouble((long)memory.readLong(p)<<32|memory.readLong(p+4)&0xffffffffL);}
	/** Writes a PMRect (four doubles: top, left, bottom, right). */
	private void writeRect(int p,double top,double left,double bottom,double right) throws M68kCpuException {writeDouble(p,top);writeDouble(p+8,left);writeDouble(p+16,bottom);writeDouble(p+24,right);}
	private int word(int p,int offset) throws M68kCpuException {return (short)memory.readWord(p+offset);}

	// ------------------------------------------------------------------ TPrint records

	private int newRecord() throws M68kException {int h=rt.toolbox.api.createHandle(new byte[TPRINT]);rt.toolbox("PrintDefault",h);return h;}
	private int copyRecord(int h) throws M68kException {
		int p=h==0?0:memory.readLong(h);byte[] b=new byte[TPRINT];
		if(p!=0){int n=Math.min(TPRINT,rt.toolbox.api.handleSize(h));System.arraycopy(memory.readBytes(p,n),0,b,0,n);}
		return rt.toolbox.api.createHandle(b);
	}
	private byte[] recordBytes(int h) throws M68kCpuException {return memory.readBytes(memory.readLong(h),TPRINT);}
	private void loadRecord(int h,byte[] b) throws M68kCpuException {memory.load(memory.readLong(h),b,0,TPRINT);}
	private void dispose(int h) throws M68kException {if(h!=0)rt.trap(0xa023,0,h,0);}
	private int prError() throws M68kException {return (short)rt.toolbox("PrError");}
	private int resolution(int p,boolean horizontal) throws M68kCpuException {int r=word(p,horizontal?6:4);return r>0?r:72;} // prInfo.iHRes / iVRes

	private PageFormat newFormat(int record) throws M68kException {PageFormat f=register(new PageFormat(),0x706d5046);f.h=record==0?newRecord():record;return f;} // 'pmPF'
	private PrintSettings newSettings(int record) throws M68kException {PrintSettings s=register(new PrintSettings(),0x706d5053);s.h=record==0?newRecord():record;return s;} // 'pmPS'

	private static boolean landscape(M68kMemory memory,int p) throws M68kCpuException {return (memory.readWord(p+24)&PrintManager68k.W_DEV_LANDSCAPE)!=0;} // prStl.wDev bit 0, as PrintManager68k marks landscape
	/** The orientation PMSetOrientation forced, back on the record after the classic calls refilled it from the host's page format. */
	private void applyOrientation(PageFormat f) throws M68kCpuException {
		if(f.orientation==0)return;int p=memory.readLong(f.h);if(p==0)return;
		if(landscape(memory,p)!=(f.orientation==kPMLandscape))rotate(p);
	}
	/** Turns the record a quarter turn: page, paper, resolutions, prStl page size and band sizes change axes. */
	private void rotate(int p) throws M68kCpuException {
		for(int info:new int[]{p+2,p+32}){swap(info+2,info+4);swap(info+6,info+8);swap(info+10,info+12);} // iVRes/iHRes, rPage
		swap(p+16,p+18);swap(p+20,p+22);                                                                  // rPaper
		swap(p+26,p+28);memory.writeWord(p+24,memory.readWord(p+24)^(PrintManager68k.W_DEV_LANDSCAPE|PrintManager68k.W_DEV_PORTRAIT)); // iPageV/iPageH, wDev orientation bits
		swap(p+48,p+50);int width=word(p,14)-word(p,10);memory.writeWord(p+46,((Math.max(1,width)+31)/32)*4); // prXInfo iBandV/iBandH, iRowBytes
	}
	private void swap(int a,int b) throws M68kCpuException {int t=memory.readWord(a);memory.writeWord(a,memory.readWord(b));memory.writeWord(b,t);}

	// ------------------------------------------------------------------ the calls

	void define(PpcLibraries l){
		// ---- objects
		l.register(null,"PMRetain",(r,c)->{PMObject o=object(arg(0),PMObject.class);if(o==null){ret(kPMInvalidParameter);return;}o.count++;ret(0);});
		l.register(null,"PMRelease",(r,c)->ret(release(arg(0))));
		l.register(null,"PMCreateSession",(r,c)->{int out=arg(0);if(out==0){ret(kPMInvalidParameter);return;}
			rt.toolbox("PrOpen");int e=prError();Session s=register(new Session(),0x706d534e);writeLong(out,s.ref);ret(e);}); // 'pmSN'
		l.register(null,"PMCreatePageFormat",(r,c)->{int out=arg(0);if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,newFormat(0).ref);ret(0);});
		l.register(null,"PMCreatePrintSettings",(r,c)->{int out=arg(0);if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,newSettings(0).ref);ret(0);});
		l.register(null,"PMNewPageFormat",(r,c)->{int out=arg(0);if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,newFormat(0).ref);ret(0);});
		l.register(null,"PMNewPrintSettings",(r,c)->{int out=arg(0);if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,newSettings(0).ref);ret(0);});
		l.register(null,"PMDisposePageFormat",(r,c)->ret(format(arg(0))==null?kPMInvalidPageFormat:free(objects.get(arg(0)))));
		l.register(null,"PMDisposePrintSettings",(r,c)->ret(settings(arg(0))==null?kPMInvalidPrintSettings:free(objects.get(arg(0)))));
		l.register(null,"PMCopyPageFormat",(r,c)->{PageFormat a=format(arg(0)),b=format(arg(1));if(a==null||b==null){ret(kPMInvalidPageFormat);return;}
			loadRecord(b.h,recordBytes(a.h));b.scale=a.scale;b.orientation=a.orientation;b.extended=new LinkedHashMap<Integer,byte[]>(a.extended);ret(0);});
		l.register(null,"PMCopyPrintSettings",(r,c)->{PrintSettings a=settings(arg(0)),b=settings(arg(1));if(a==null||b==null){ret(kPMInvalidPrintSettings);return;}
			loadRecord(b.h,recordBytes(a.h));b.rangeMin=a.rangeMin;b.rangeMax=a.rangeMax;b.colorMode=a.colorMode;b.jobName=a.jobName;b.collate=a.collate;b.extended=new LinkedHashMap<Integer,byte[]>(a.extended);ret(0);});

		// ---- defaults and validation
		l.register(null,"PMSessionDefaultPageFormat",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:defaultFormat(format(arg(1)))));
		l.register(null,"PMSessionDefaultPrintSettings",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:defaultSettings(settings(arg(1)))));
		l.register(null,"PMSessionValidatePageFormat",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:validateFormat(format(arg(1)),arg(2))));
		l.register(null,"PMSessionValidatePrintSettings",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:validateSettings(settings(arg(1)),arg(2))));
		l.register(null,"PMDefaultPageFormat",(r,c)->ret(defaultFormat(format(arg(0)))));
		l.register(null,"PMDefaultPrintSettings",(r,c)->ret(defaultSettings(settings(arg(0)))));
		l.register(null,"PMValidatePageFormat",(r,c)->ret(validateFormat(format(arg(0)),arg(1))));
		l.register(null,"PMValidatePrintSettings",(r,c)->ret(validateSettings(settings(arg(0)),arg(1))));

		// ---- dialogs
		l.register(null,"PMSessionPageSetupDialog",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:pageSetupDialog(s,format(arg(1)),arg(2)));});
		l.register(null,"PMSessionPrintDialog",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:printDialog(s,settings(arg(1)),arg(2),arg(3)));});
		l.register(null,"PMPageSetupDialog",(r,c)->ret(pageSetupDialog(null,format(arg(0)),arg(1))));
		l.register(null,"PMPrintDialog",(r,c)->ret(printDialog(null,settings(arg(0)),arg(1),arg(2))));
		l.register(null,"PMSessionPageSetupDialogInit",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:dialogInit(false,arg(1),arg(2))));
		l.register(null,"PMSessionPrintDialogInit",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:dialogInit(true,arg(1),arg(3))));
		l.register(null,"PMPageSetupDialogInit",(r,c)->ret(dialogInit(false,arg(0),arg(1))));
		l.register(null,"PMPrintDialogInit",(r,c)->ret(dialogInit(true,arg(0),arg(1))));
		l.register(null,"PMPrintDialogInitWithPageFormat",(r,c)->ret(dialogInit(true,arg(0),arg(2))));
		l.register(null,"PMSessionPageSetupDialogMain",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:dialogMain(s,false,0,arg(1),arg(2),arg(3)));});
		l.register(null,"PMSessionPrintDialogMain",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:dialogMain(s,true,arg(1),arg(2),arg(3),arg(4)));});
		l.register(null,"PMPageSetupDialogMain",(r,c)->ret(dialogMain(null,false,0,arg(0),arg(1),arg(2))));
		l.register(null,"PMPrintDialogMain",(r,c)->ret(dialogMain(null,true,arg(0),arg(1),arg(2),arg(3))));
		l.register(null,"PMSessionUseSheets",(r,c)->ret(kPMNotImplemented)); // no sheets on Mac OS 9: the program falls back to the modal dialogs
		// the sheet-done UPP of UI 3.4 (the 3.3 procInfos of the others give PpcLibraries their New/Dispose/Invoke): (session, window, Boolean accepted)
		l.register(null,"NewPMSheetDoneUPP",(r,c)->{int tv=arg(0);ret(tv==0?0:rt.newRoutineDescriptor(tv,0x7c0,PpcRuntime.ISA_PPC));});
		l.register(null,"DisposePMSheetDoneUPP",(r,c)->{});
		l.register(null,"InvokePMSheetDoneUPP",(r,c)->{int s=arg(0),w=arg(1),ok=arg(2)&0xff,upp=arg(3);if(upp!=0)rt.callUniversal(upp,0x7c0,new int[]{s,w,ok});});
		l.register(null,"PMGetDialogPtr",(r,c)->{Dialog d=dialog(arg(0));int out=arg(1);if(d==null||out==0){ret(kPMInvalidParameter);return;}writeLong(out,dialogRef(d));ret(0);});
		l.register(null,"PMGetModalFilterProc",(r,c)->{Dialog d=dialog(arg(0));int out=arg(1);if(d==null||out==0){ret(kPMInvalidParameter);return;}writeLong(out,d.filterProc!=0?d.filterProc:defaultFilter());ret(0);});
		l.register(null,"PMSetModalFilterProc",(r,c)->{Dialog d=dialog(arg(0));if(d==null){ret(kPMInvalidParameter);return;}d.filterProc=arg(1);ret(0);});
		l.register(null,"PMGetItemProc",(r,c)->{Dialog d=dialog(arg(0));int out=arg(1);if(d==null||out==0){ret(kPMInvalidParameter);return;}writeLong(out,d.itemProc!=0?d.itemProc:defaultItemProc());ret(0);});
		l.register(null,"PMSetItemProc",(r,c)->{Dialog d=dialog(arg(0));if(d==null){ret(kPMInvalidParameter);return;}d.itemProc=arg(1);ret(0);});
		l.register(null,"PMGetDialogAccepted",(r,c)->{Dialog d=dialog(arg(0));if(d==null){ret(kPMInvalidParameter);return;}writeBoolean(arg(1),d.accepted);ret(0);});
		l.register(null,"PMSetDialogAccepted",(r,c)->{Dialog d=dialog(arg(0));if(d==null){ret(kPMInvalidParameter);return;}d.accepted=(arg(1)&0xff)!=0;ret(0);});
		l.register(null,"PMGetDialogDone",(r,c)->{Dialog d=dialog(arg(0));if(d==null){ret(kPMInvalidParameter);return;}writeBoolean(arg(1),d.done);ret(0);});
		l.register(null,"PMSetDialogDone",(r,c)->{Dialog d=dialog(arg(0));if(d==null){ret(kPMInvalidParameter);return;}d.done=(arg(1)&0xff)!=0;ret(0);});

		// ---- documents and pages (session API)
		l.register(null,"PMSessionBeginDocument",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:beginDocument(s,settings(arg(1)),format(arg(2))));});
		l.register(null,"PMSessionEndDocument",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:endDocument(s));});
		l.register(null,"PMSessionBeginPage",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:beginPage(s));}); // (session, pageFormat, pageFrame): the page is the document's
		l.register(null,"PMSessionEndPage",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:endPage(s));});
		l.register(null,"PMSessionGetGraphicsContext",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:graphicsContext(s,arg(1),arg(2)));});
		l.register(null,"PMSessionSetDocumentFormatGeneration",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:setDocumentFormat(s,arg(1),arg(2)));});
		l.register(null,"PMSessionGetDocumentFormatGeneration",(r,c)->{Session s=session(arg(0));int out=arg(1);if(s==null){ret(kPMInvalidPrintSession);return;}if(out==0){ret(kPMInvalidParameter);return;}
			int a=stringArray(s.documentFormat);writeLong(out,a);ret(a==0?kPMNotImplemented:0);});
		l.register(null,"PMSessionGetDocumentFormatSupported",(r,c)->{Session s=session(arg(0));int out=arg(1),limit=arg(2);if(s==null){ret(kPMInvalidPrintSession);return;}if(out==0){ret(kPMInvalidParameter);return;}
			List<String> formats=new ArrayList<String>(List.of(FORMAT_DEFAULT,FORMAT_PICT));if(limit>0)while(formats.size()>limit)formats.remove(formats.size()-1);
			int a=stringArray(formats.toArray(new String[0]));writeLong(out,a);ret(a==0?kPMNotImplemented:0);});
		l.register(null,"PMSessionIsDocumentFormatSupported",(r,c)->{Session s=session(arg(0));if(s==null){ret(kPMInvalidPrintSession);return;}String f=cfText(arg(1));
			writeBoolean(arg(2),f!=null&&quickDrawFormat(f));ret(0);});

		// ---- documents and pages (non-session API: a PMPrintContext per document)
		l.register(null,"PMBegin",(r,c)->{rt.toolbox("PrOpen");ret(oldError=prError());});
		l.register(null,"PMEnd",(r,c)->{rt.toolbox("PrClose");ret(0);});
		l.register(null,"PMBeginDocument",(r,c)->{PrintSettings ps=settings(arg(0));PageFormat f=format(arg(1));int out=arg(2);if(out==0){ret(kPMInvalidParameter);return;}
			Session x=register(new Session(),0x706d5043);x.context=true;x.idleProc=oldIdleProc;int e=beginDocument(x,ps,f); // 'pmPC'
			if(e!=0){free(x);writeLong(out,0);ret(oldError=e);return;}oldContext=x;writeLong(out,x.ref);ret(0);});
		l.register(null,"PMEndDocument",(r,c)->{Session x=context(arg(0));if(x==null){ret(kPMInvalidParameter);return;}int e=endDocument(x);if(oldContext==x)oldContext=null;free(x);if(e!=0)oldError=e;ret(e);});
		l.register(null,"PMBeginPage",(r,c)->{Session x=context(arg(0));if(x==null){ret(kPMInvalidParameter);return;}int e=beginPage(x);if(e!=0)oldError=e;ret(e);});
		l.register(null,"PMEndPage",(r,c)->{Session x=context(arg(0));if(x==null){ret(kPMInvalidParameter);return;}int e=endPage(x);if(e!=0)oldError=e;ret(e);});
		l.register(null,"PMGetGrafPtr",(r,c)->{Session x=context(arg(0));int out=arg(1);if(x==null||out==0){ret(kPMInvalidParameter);return;}
			if(x.document==null){writeLong(out,0);ret(kPMOutOfScope);return;}writeLong(out,x.document.port);ret(0);});

		// ---- errors, idle procedures, session data
		l.register(null,"PMSessionError",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:s.error);});
		l.register(null,"PMSessionSetError",(r,c)->{Session s=session(arg(0));if(s==null){ret(kPMInvalidPrintSession);return;}setError(s,arg(1));ret(0);});
		l.register(null,"PMError",(r,c)->ret(oldError!=0?oldError:prError()));
		l.register(null,"PMSetError",(r,c)->{int e=arg(0);oldError=e;for(PMObject o:objects.values())if(o instanceof Session&&((Session)o).context)setError((Session)o,e);if(oldContext==null)rt.toolbox("PrSetError",(short)e);ret(0);});
		l.register(null,"PMSessionSetIdleProc",(r,c)->{Session s=session(arg(0));if(s==null){ret(kPMInvalidPrintSession);return;}s.idleProc=arg(1);ret(0);}); // kept: pages go out at the end of the document, nothing to wait for
		l.register(null,"PMSetIdleProc",(r,c)->{oldIdleProc=arg(0);ret(0);});
		l.register(null,"PMSessionSetDataInSession",(r,c)->{Session s=session(arg(0));String key=cfText(arg(1));int value=arg(2);if(s==null){ret(kPMInvalidPrintSession);return;}if(key==null){ret(kPMInvalidParameter);return;}
			if(value!=0)rt.libraries.coreFoundation.retain(value);Integer old=value!=0?s.data.put(key,value):s.data.remove(key);if(old!=null)rt.libraries.coreFoundation.release(old);ret(0);});
		l.register(null,"PMSessionGetDataFromSession",(r,c)->{Session s=session(arg(0));String key=cfText(arg(1));int out=arg(2);if(s==null){ret(kPMInvalidPrintSession);return;}if(key==null||out==0){ret(kPMInvalidParameter);return;}
			Integer v=s.data.get(key);writeLong(out,v==null?0:v);ret(v==null?kPMNoSuchEntry:0);}); // the Get rule: not retained for the caller
		l.register(null,"PMSessionGeneral",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:general(arg(1))));
		l.register(null,"PMGeneral",(r,c)->ret(general(arg(0))));

		// ---- old print records and flattened objects
		l.register(null,"PMSessionConvertOldPrintRecord",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:convertOld(arg(1),arg(2),arg(3))));
		l.register(null,"PMConvertOldPrintRecord",(r,c)->ret(convertOld(arg(0),arg(1),arg(2))));
		l.register(null,"PMSessionMakeOldPrintRecord",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:makeOld(settings(arg(1)),format(arg(2)),arg(3))));
		l.register(null,"PMMakeOldPrintRecord",(r,c)->ret(makeOld(settings(arg(0)),format(arg(1)),arg(2))));
		l.register(null,"PMFlattenPageFormat",(r,c)->{PageFormat f=format(arg(0));int out=arg(1);if(f==null){ret(kPMInvalidPageFormat);return;}if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,rt.toolbox.api.createHandle(flatten(f)));ret(0);});
		l.register(null,"PMUnflattenPageFormat",(r,c)->ret(unflattenFormat(arg(0),arg(1))));
		l.register(null,"PMFlattenPrintSettings",(r,c)->{PrintSettings s=settings(arg(0));int out=arg(1);if(s==null){ret(kPMInvalidPrintSettings);return;}if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,rt.toolbox.api.createHandle(flatten(s)));ret(0);});
		l.register(null,"PMUnflattenPrintSettings",(r,c)->ret(unflattenSettings(arg(0),arg(1))));
		l.register(null,"PMGetPageFormatExtendedData",(r,c)->{PageFormat f=format(arg(0));ret(f==null?kPMInvalidPageFormat:getExtended(f.extended,arg(1),arg(2),arg(3)));});
		l.register(null,"PMSetPageFormatExtendedData",(r,c)->{PageFormat f=format(arg(0));ret(f==null?kPMInvalidPageFormat:setExtended(f.extended,arg(1),arg(2),arg(3)));});
		l.register(null,"PMGetPrintSettingsExtendedData",(r,c)->{PrintSettings s=settings(arg(0));ret(s==null?kPMInvalidPrintSettings:getExtended(s.extended,arg(1),arg(2),arg(3)));});
		l.register(null,"PMSetPrintSettingsExtendedData",(r,c)->{PrintSettings s=settings(arg(0));ret(s==null?kPMInvalidPrintSettings:setExtended(s.extended,arg(1),arg(2),arg(3)));});

		// ---- page format: scale, resolution, orientation, page and paper
		l.register(null,"PMGetScale",(r,c)->{PageFormat f=format(arg(0));int out=arg(1);if(f==null){ret(kPMInvalidPageFormat);return;}if(out==0){ret(kPMInvalidParameter);return;}writeDouble(out,f.scale);ret(0);});
		l.register(null,"PMSetScale",(r,c)->{PageFormat f=format(arg(0));double v=rt.farg(0);if(f==null){ret(kPMInvalidPageFormat);return;}if(!(v>0)){ret(kPMValueOutOfRange);return;}f.scale=v;ret(0);}); // (the captured page is not scaled: the port keeps rPage)
		l.register(null,"PMGetResolution",(r,c)->{PageFormat f=format(arg(0));int out=arg(1);if(f==null){ret(kPMInvalidPageFormat);return;}if(out==0){ret(kPMInvalidParameter);return;}
			int p=memory.readLong(f.h);writeDouble(out,resolution(p,true));writeDouble(out+8,resolution(p,false));ret(0);});
		l.register(null,"PMSetResolution",(r,c)->{PageFormat f=format(arg(0));int res=arg(1);if(f==null){ret(kPMInvalidPageFormat);return;}if(res==0){ret(kPMInvalidParameter);return;}
			ret(setResolution(f,(int)Math.round(readDouble(res)),(int)Math.round(readDouble(res+8))));});
		l.register(null,"PMGetOrientation",(r,c)->{PageFormat f=format(arg(0));int out=arg(1);if(f==null){ret(kPMInvalidPageFormat);return;}if(out==0){ret(kPMInvalidParameter);return;}
			memory.writeWord(out,landscape(memory,memory.readLong(f.h))?kPMLandscape:kPMPortrait);ret(0);});
		l.register(null,"PMSetOrientation",(r,c)->{PageFormat f=format(arg(0));int o=arg(1)&0xffff;if(f==null){ret(kPMInvalidPageFormat);return;}
			if(o<kPMPortrait||o>kPMReverseLandscape){ret(kPMValueOutOfRange);return;}
			f.orientation=o==kPMLandscape||o==kPMReverseLandscape?kPMLandscape:kPMPortrait;applyOrientation(f);ret(0);}); // the reverse orientations revert to the plain ones, as with the Mac OS 9 drivers
		l.register(null,"PMGetAdjustedPageRect",(r,c)->ret(pageRect(format(arg(0)),arg(1),true,false)));
		l.register(null,"PMGetAdjustedPaperRect",(r,c)->ret(paperRect(format(arg(0)),arg(1),true)));
		l.register(null,"PMGetUnadjustedPageRect",(r,c)->ret(pageRect(format(arg(0)),arg(1),false,false)));
		l.register(null,"PMGetUnadjustedPaperRect",(r,c)->ret(paperRect(format(arg(0)),arg(1),false)));
		l.register(null,"PMGetPhysicalPageSize",(r,c)->ret(pageRect(format(arg(0)),arg(1),false,true)));
		l.register(null,"PMGetPhysicalPaperSize",(r,c)->ret(paperRect(format(arg(0)),arg(1),false)));
		for(String name:new String[]{"PMSetPhysicalPaperSize","PMSetUnadjustedPaperRect","PMSetAdjustedPageRect"}) // the paper comes from the page setup dialog
			l.register(null,name,(r,c)->ret(format(arg(0))==null?kPMInvalidPageFormat:kPMNotImplemented));

		// ---- print settings: job
		l.register(null,"PMGetCopies",(r,c)->ret(getJob(settings(arg(0)),66,arg(1))));
		l.register(null,"PMGetFirstPage",(r,c)->ret(getJob(settings(arg(0)),62,arg(1))));
		l.register(null,"PMGetLastPage",(r,c)->ret(getJob(settings(arg(0)),64,arg(1))));
		l.register(null,"PMSetCopies",(r,c)->{int n=arg(1);ret(n==0?kPMValueOutOfRange:setJob(settings(arg(0)),66,n));});
		l.register(null,"PMSetFirstPage",(r,c)->ret(setJob(settings(arg(0)),62,arg(1))));
		l.register(null,"PMSetLastPage",(r,c)->ret(setJob(settings(arg(0)),64,arg(1))));
		l.register(null,"PMGetPageRange",(r,c)->{PrintSettings s=settings(arg(0));if(s==null){ret(kPMInvalidPrintSettings);return;}writeLong(arg(1),(int)s.rangeMin);writeLong(arg(2),(int)s.rangeMax);ret(0);});
		l.register(null,"PMSetPageRange",(r,c)->{PrintSettings s=settings(arg(0));long min=arg(1)&0xffffffffL,max=arg(2)&0xffffffffL;if(s==null){ret(kPMInvalidPrintSettings);return;}
			if(min>max){ret(kPMValueOutOfRange);return;}s.rangeMin=min;s.rangeMax=max;
			int p=memory.readLong(s.h);long first=word(p,62)&0xffff,last=word(p,64)&0xffff; // the job's pages kept within the range
			if(first<min)setJob(s,62,(int)min);if(last>max)setJob(s,64,(int)max);ret(0);});
		l.register(null,"PMGetJobName",(r,c)->{PrintSettings s=settings(arg(0));int out=arg(1);if(s==null){ret(kPMInvalidPrintSettings);return;}if(out==0){ret(kPMInvalidParameter);return;}rt.toolbox.writePascal(out,jobName(s),255);ret(0);});
		l.register(null,"PMSetJobName",(r,c)->{PrintSettings s=settings(arg(0));int name=arg(1);if(s==null){ret(kPMInvalidPrintSettings);return;}if(name==0){ret(kPMInvalidParameter);return;}s.jobName=rt.toolbox.readPascal(name);ret(0);});
		l.register(null,"PMGetJobNameCFString",(r,c)->{PrintSettings s=settings(arg(0));int out=arg(1);if(s==null){ret(kPMInvalidPrintSettings);return;}if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,rt.libraries.textServices.newCFString(jobName(s)));ret(0);});
		l.register(null,"PMSetJobNameCFString",(r,c)->{PrintSettings s=settings(arg(0));String name=cfText(arg(1));if(s==null){ret(kPMInvalidPrintSettings);return;}if(name==null){ret(kPMInvalidParameter);return;}s.jobName=name;ret(0);});
		l.register(null,"PMGetColorMode",(r,c)->{PrintSettings s=settings(arg(0));int out=arg(1);if(s==null){ret(kPMInvalidPrintSettings);return;}if(out==0){ret(kPMInvalidParameter);return;}memory.writeWord(out,s.colorMode);ret(0);});
		l.register(null,"PMSetColorMode",(r,c)->{PrintSettings s=settings(arg(0));int m=arg(1)&0xffff;if(s==null){ret(kPMInvalidPrintSettings);return;}if(m<1||m>5){ret(kPMValueOutOfRange);return;}s.colorMode=m;ret(0);});
		l.register(null,"PMSetProfile",(r,c)->ret(settings(arg(0))==null?kPMInvalidPrintSettings:0)); // no ColorSync matching on the page capture
		l.register(null,"PMGetDestination",(r,c)->{PrintSettings s=settings(arg(0));if(s==null){ret(kPMInvalidPrintSettings);return;}if(arg(1)!=0)memory.writeWord(arg(1),1);writeLong(arg(2),0);ret(0);}); // kPMDestinationPrinter

		// ---- the printer
		l.register(null,"PMSessionGetCurrentPrinter",(r,c)->{Session s=session(arg(0));int out=arg(1);if(s==null){ret(kPMInvalidPrintSession);return;}if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,printer().ref);ret(0);});
		l.register(null,"PMPrinterGetLanguageInfo",(r,c)->ret(!isPrinter(arg(0))?kPMInvalidPrinter:languageInfo(arg(1))));
		l.register(null,"PMGetLanguageInfo",(r,c)->ret(languageInfo(arg(0))));
		l.register(null,"PMPrinterGetDriverCreator",(r,c)->ret(!isPrinter(arg(0))?kPMInvalidPrinter:driverCreator(arg(1))));
		l.register(null,"PMGetDriverCreator",(r,c)->ret(driverCreator(arg(0))));
		l.register(null,"PMPrinterGetDriverReleaseInfo",(r,c)->ret(!isPrinter(arg(0))?kPMInvalidPrinter:releaseInfo(arg(1))));
		l.register(null,"PMGetDriverReleaseInfo",(r,c)->ret(releaseInfo(arg(0))));
		l.register(null,"PMPrinterGetPrinterResolutionCount",(r,c)->ret(!isPrinter(arg(0))?kPMInvalidPrinter:resolutionCount(arg(1))));
		l.register(null,"PMGetPrinterResolutionCount",(r,c)->ret(resolutionCount(arg(0))));
		l.register(null,"PMPrinterGetPrinterResolution",(r,c)->ret(!isPrinter(arg(0))?kPMInvalidPrinter:printerResolution(arg(1),arg(2))));
		l.register(null,"PMGetPrinterResolution",(r,c)->ret(printerResolution(arg(0),arg(1))));
		l.register(null,"PMPrinterGetIndexedPrinterResolution",(r,c)->ret(!isPrinter(arg(0))?kPMInvalidPrinter:indexedResolution(arg(1),arg(2))));
		l.register(null,"PMGetIndexedPrinterResolution",(r,c)->ret(indexedResolution(arg(0),arg(1))));
		l.register(null,"PMPrinterGetDescriptionURL",(r,c)->{writeLong(arg(2),0);ret(!isPrinter(arg(0))?kPMInvalidPrinter:kPMNotImplemented);}); // no PPD: not a PostScript printer
		l.register(null,"PMIsPostScriptDriver",(r,c)->{int out=arg(0);if(out==0){ret(kPMInvalidParameter);return;}writeBoolean(out,false);ret(0);});

		// ---- ColorSync and PostScript
		for(String name:new String[]{"PMEnableColorSync","PMDisableColorSync"})l.register(null,name,(r,c)->ret(0));
		for(String name:new String[]{"PMSessionEnableColorSync","PMSessionDisableColorSync"})l.register(null,name,(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:0));
		l.register(null,"PMSessionSetPSInjectionData",(r,c)->ret(session(arg(0))==null?kPMInvalidPrintSession:0)); // ignored: no PostScript output
		l.register(null,"PMSessionPostScriptBegin",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:postScript(s,PS_BEGIN,null));});
		l.register(null,"PMSessionPostScriptEnd",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:postScript(s,PS_END,null));});
		l.register(null,"PMSessionPostScriptData",(r,c)->{Session s=session(arg(0));int p=arg(1),n=arg(2);ret(s==null?kPMInvalidPrintSession:p==0||n<0?kPMInvalidParameter:postScript(s,PS_HANDLE,memory.readBytes(p,n)));});
		l.register(null,"PMSessionPostScriptHandle",(r,c)->{Session s=session(arg(0));int h=arg(1);ret(s==null?kPMInvalidPrintSession:h==0?kPMInvalidParameter:postScript(s,PS_HANDLE,handleBytes(h)));});
		l.register(null,"PMSessionPostScriptFile",(r,c)->{Session s=session(arg(0));int spec=arg(1);ret(s==null?kPMInvalidPrintSession:spec==0?kPMInvalidParameter:postScript(s,PS_FILE,rt.toolbox.readPascalBytes(spec+6)));});
		l.register(null,"PMPostScriptBegin",(r,c)->ret(postScript(oldContext,PS_BEGIN,null)));
		l.register(null,"PMPostScriptEnd",(r,c)->ret(postScript(oldContext,PS_END,null)));
		l.register(null,"PMPostScriptData",(r,c)->{int p=arg(0),n=arg(1);ret(p==0||n<0?kPMInvalidParameter:postScript(oldContext,PS_HANDLE,memory.readBytes(p,n)));});
		l.register(null,"PMPostScriptHandle",(r,c)->{int h=arg(0);ret(h==0?kPMInvalidParameter:postScript(oldContext,PS_HANDLE,handleBytes(h)));});
		l.register(null,"PMPostScriptFile",(r,c)->{int spec=arg(0);ret(spec==0?kPMInvalidParameter:postScript(oldContext,PS_FILE,rt.toolbox.readPascalBytes(spec+6)));}); // FSSpec.name
		defineCarbon16(l);
	}

	// ------------------------------------------------------------------ CarbonLib 1.6 additions (PMCore.h of Mac OS X 10.2)

	// PMErrors.h (Mac OS X): the job-ticket errors
	static final int kPMTicketTypeNotFound=-9580,kPMSubTicketNotFound=-9583,kPMItemIsLocked=-9586,kPMTicketIsLocked=-9587,kPMKeyNotFound=-9589,kPMUnknownDataType=-9591;
	// PMDestinationType
	static final int kPMDestinationInvalid=0,kPMDestinationPrinter=1,kPMDestinationFile=2,kPMDestinationFax=3,kPMDestinationPreview=4,kPMDestinationProcessPDF=5;
	/** The printer's name and make-and-model: the Chooser's printer ('STR ' -8192 of SystemResources68k). */
	static final String PRINTER_NAME="HyperZebra Printer";
	// job-ticket keys (the Mac OS X print ticket format: an item is {creator, itemArray: [{key: value, client, modDate, stateFlag}]})
	private static final String T="com.apple.print.ticket.",T_TYPE=T+"type",T_API=T+"APIVersion",T_LOCK=T+"privateLock",T_CREATOR=T+"creator",T_ITEMS=T+"itemArray",
		T_CLIENT=T+"client",T_DATE=T+"modDate",T_STATE=T+"stateFlag",PM_CLIENT="com.apple.printingmanager",
		PAGE_FORMAT_TICKET="com.apple.print.PageFormatTicket",PRINT_SETTINGS_TICKET="com.apple.print.PrintSettingsTicket",PF="com.apple.print.PageFormat.",PS="com.apple.print.PrintSettings.";
	/** The ticket types in PMTicketType order (kPMJobTicketType = 1 ...); FindEnumTicketType answers the index. */
	private static final String[] TICKET_TYPES={null,"com.apple.print.JobTicket","com.apple.print.DocumentTicket","com.apple.print.PageTicket",PAGE_FORMAT_TICKET,PRINT_SETTINGS_TICKET,
		"com.apple.print.PrinterInfoTicket","com.apple.print.DestinationTicket","com.apple.print.ConverterSetupTicket","com.apple.print.PaperInfoTicket","com.apple.print.TicketListTicket"};
	/** PMMutex: a lock owned by one Thread Manager thread at a time (recursive), reference counted. */
	private static final class Mutex extends PMObject {int owner,depth;}
	private int printerName,arrayCallBacks,logControls;

	private PpcCoreFoundation cf(){return rt.libraries.coreFoundation;}
	/** dict[key] = a new CF object (the reference is handed to the dictionary). */
	private void own(int dict,String key,int value) throws M68kException {cf().put(dict,key,value);cf().release(value);}
	private int cf(Object v) throws M68kException {return cf().toCF(v,false);}
	private static int bool(int v){return (v&0xff)!=0?1:0;}

	private void defineCarbon16(PpcLibraries l){
		// ---- documents and pages without the status dialog (there is none here: the same calls)
		l.register(null,"PMSessionBeginDocumentNoDialog",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:beginDocument(s,settings(arg(1)),format(arg(2))));});
		l.register(null,"PMSessionEndDocumentNoDialog",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:endDocument(s));});
		l.register(null,"PMSessionBeginPageNoDialog",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:beginPage(s));}); // (session, pageFormat, pageFrame)
		l.register(null,"PMSessionEndPageNoDialog",(r,c)->{Session s=session(arg(0));ret(s==null?kPMInvalidPrintSession:endPage(s));});

		// ---- destinations: only the printer (the pages are captured QuickDraw drawing; nothing writes PDF/PostScript files, faxes or previews)
		l.register(null,"PMSessionGetDestinationType",(r,c)->{Session s=session(arg(0));int out=arg(2);if(s==null){ret(kPMInvalidPrintSession);return;}if(settings(arg(1))==null){ret(kPMInvalidPrintSettings);return;}
			if(out==0){ret(kPMInvalidParameter);return;}memory.writeWord(out,kPMDestinationPrinter);ret(0);});
		l.register(null,"PMSessionSetDestination",(r,c)->{Session s=session(arg(0));int type=arg(2)&0xffff;if(s==null){ret(kPMInvalidPrintSession);return;}if(settings(arg(1))==null){ret(kPMInvalidPrintSettings);return;}
			ret(type==kPMDestinationPrinter?0:type>kPMDestinationProcessPDF||type==kPMDestinationInvalid?kPMInvalidParameter:kPMNotImplemented);});
		l.register(null,"PMSessionCopyDestinationFormat",(r,c)->{Session s=session(arg(0));int out=arg(2);writeLong(out,0);if(s==null){ret(kPMInvalidPrintSession);return;}
			ret(settings(arg(1))==null?kPMInvalidPrintSettings:out==0?kPMInvalidParameter:0);}); // the printer destination has no file format: NULL
		l.register(null,"PMSessionCopyDestinationLocation",(r,c)->{Session s=session(arg(0));int out=arg(2);writeLong(out,0);if(s==null){ret(kPMInvalidPrintSession);return;}
			ret(settings(arg(1))==null?kPMInvalidPrintSettings:out==0?kPMInvalidParameter:0);}); // the printer destination has no location: NULL
		l.register(null,"PMSessionCopyOutputFormatList",(r,c)->{Session s=session(arg(0));int type=arg(1)&0xffff,out=arg(2);writeLong(out,0);if(s==null){ret(kPMInvalidPrintSession);return;}if(out==0){ret(kPMInvalidParameter);return;}
			if(type!=kPMDestinationPrinter){ret(type==kPMDestinationInvalid||type>kPMDestinationProcessPDF?kPMInvalidParameter:kPMNotImplemented);return;}
			int a=stringArray(FORMAT_PICT);writeLong(out,a);ret(a==0?kPMNotImplemented:0);});

		// ---- printers and paper
		l.register(null,"PMSessionCreatePrinterList",(r,c)->{Session s=session(arg(0));int list=arg(1),index=arg(2),current=arg(3);writeLong(list,0);
			if(s==null){ret(kPMInvalidPrintSession);return;}if(list==0){ret(kPMInvalidParameter);return;}
			int a=stringArray(PRINTER_NAME);writeLong(list,a);writeLong(index,0);writeLong(current,printer().ref);ret(a==0?kPMNotImplemented:0);});
		l.register(null,"PMSessionSetCurrentPrinter",(r,c)->{Session s=session(arg(0));String name=cfText(arg(1));if(s==null){ret(kPMInvalidPrintSession);return;}if(name==null){ret(kPMInvalidParameter);return;}
			ret(name.equals(PRINTER_NAME)?0:kPMInvalidPrinter);});
		l.register(null,"PMSessionCreatePageFormatList",(r,c)->{Session s=session(arg(0));int out=arg(2);writeLong(out,0);if(s==null){ret(kPMInvalidPrintSession);return;}
			if(arg(1)!=0&&!isPrinter(arg(1))){ret(kPMInvalidPrinter);return;}if(out==0){ret(kPMInvalidParameter);return;}
			PageFormat f=newFormat(0);int e=prError(); // the printer's paper as the page setup has it (the host offers one page format at a time)
			int a=formatArray(f.ref);f.count--; // the array holds the only reference
			if(a==0){free(f);ret(kPMNotImplemented);return;}writeLong(out,a);ret(e);});
		l.register(null,"PMPrinterGetMakeAndModelName",(r,c)->{int out=arg(1);if(!isPrinter(arg(0))){writeLong(out,0);ret(kPMInvalidPrinter);return;}if(out==0){ret(kPMInvalidParameter);return;}
			if(printerName==0){printerName=cf().newString(PRINTER_NAME);cf().object(printerName).constant=true;}writeLong(out,printerName);ret(0);}); // the Get rule: owned by the printer
		l.register(null,"PMPrinterIsPostScriptCapable",(r,c)->ret(0)); // a QuickDraw device (see PMIsPostScriptDriver)
		l.register(null,"PMSetAdjustedPaperRect",(r,c)->ret(format(arg(0))==null?kPMInvalidPageFormat:kPMNotImplemented)); // the paper comes from the page setup dialog, as PMSetAdjustedPageRect
		l.register(null,"PMSetCollate",(r,c)->{PrintSettings s=settings(arg(0));if(s==null){ret(kPMInvalidPrintSettings);return;}s.collate=bool(arg(1))!=0;ret(0);});
		l.register(null,"PMGetCollate",(r,c)->{PrintSettings s=settings(arg(0));int out=arg(1);if(s==null){ret(kPMInvalidPrintSettings);return;}if(out==0){ret(kPMInvalidParameter);return;}writeBoolean(out,s.collate);ret(0);});
		// Cocoa's NSPageLayout/NSPrintPanel preparation: there is no AppKit in a Carbon program's world
		for(String name:new String[]{"PMPrepareAppKitPageSetupDialog","PMPrepareAppKitPrintDialog"})l.register(null,name,(r,c)->ret(kPMNotImplemented));

		// ---- job tickets (PrintingCore's ticket code). A PMPageFormat or PMPrintSettings stands for its ticket (a CFMutableDictionary in the
		// Mac OS X ticket format, its Printing Manager items refreshed from the object, locked to com.apple.printingmanager); a CFDictionary is a ticket as it is.
		l.register(null,"PMTicketGetTypedItem",(r,c)->{ // (ticket, nodeIndex1, nodeIndex2, key, CFTypeID, CFTypeRef *item) as PMTicketGetCFString... take theirs; the key may come first
			int t=ticketOf(arg(0));boolean keyFirst=cfText(arg(1))!=null;String key=cfText(keyFirst?arg(1):arg(3));int n1=keyFirst?arg(2):arg(1),n2=keyFirst?arg(3):arg(2),type=arg(4),out=arg(5);
			writeLong(out,0);if(t==0||key==null||out==0){ret(kPMInvalidParameter);return;}
			int v=searchNode(t,key,n1,n2);if(v==0){ret(kPMKeyNotFound);return;}
			PpcCoreFoundation.CFObject o=cf().object(v);if(type!=0&&(o==null||o.type!=type)){ret(kPMUnknownDataType);return;}
			writeLong(out,v);ret(0);}); // the Get rule
		l.register(null,"PMTicketLock",(r,c)->{int t=ticketOf(arg(0));if(t==0){ret(kPMInvalidParameter);return;}cf().put(t,T_LOCK,cf().bool(true));ret(0);});
		l.register(null,"PMTicketUnlock",(r,c)->{int t=ticketOf(arg(0));if(t==0){ret(kPMInvalidParameter);return;}cf().put(t,T_LOCK,cf().bool(false));ret(0);});
		l.register(null,"PMTicketSetRetainCount",(r,c)->{int ref=arg(0),n=arg(1);if(n<1){ret(kPMInvalidParameter);return;}
			PMObject o=object(ref,PMObject.class);if(o!=null&&!(o instanceof Printer)){o.count=n;ret(0);return;}
			PpcCoreFoundation.CFObject d=cf().dictionary(ref);if(d==null){ret(kPMInvalidParameter);return;}if(!d.constant)d.references=n;ret(0);});
		// the ticket code's own routines that CarbonLib exports (private: signatures inferred from what they do, on the ticket model above)
		l.register(null,"AddItem",(r,c)->{int t=ticketOf(arg(0));String client=cfText(arg(1)),key=cfText(arg(2));if(t==0||client==null||key==null||arg(3)==0){ret(kPMInvalidParameter);return;}
			ret(setItem(t,client,key,arg(3),bool(arg(4))!=0,false,false));}); // (ticket, clientID, key, item, locked)
		l.register(null,"UpdateItem",(r,c)->{int t=ticketOf(arg(0));String client=cfText(arg(1)),key=cfText(arg(2));if(t==0||client==null||key==null||arg(3)==0){ret(kPMInvalidParameter);return;}
			ret(setItem(t,client,key,arg(3),bool(arg(4))!=0,false,true));}); // (ticket, clientID, key, item, locked): an existing item only
		l.register(null,"ValidItem",(r,c)->ret(validItem(arg(0))?1:0)); // (item container) -> Boolean
		l.register(null,"LockOrUnlockItem",(r,c)->{int t=ticketOf(arg(0));String client=cfText(arg(1)),key=cfText(arg(2));if(t==0||client==null||key==null){ret(kPMInvalidParameter);return;}
			ret(lockItem(t,client,key,bool(arg(3))!=0));}); // (ticket, clientID, key, lock)
		l.register(null,"BuildItemDictionary",(r,c)->{String client=cfText(arg(0)),key=cfText(arg(1));ret(client==null||key==null||arg(2)==0?0:itemDictionary(client,key,arg(2),bool(arg(3))!=0));}); // (clientID, key, item, locked) -> a new item dictionary
		l.register(null,"GetItemArrayAndDict",(r,c)->{int t=ticketOf(arg(0));String key=cfText(arg(1));int index=arg(2),arrayOut=arg(3),dictOut=arg(4);writeLong(arrayOut,0);writeLong(dictOut,0);
			if(t==0||key==null){ret(kPMInvalidParameter);return;}int a=cf().lookup(cf().lookup(t,key),T_ITEMS);PpcCoreFoundation.CFArray array=cf().array(a);
			if(array==null||array.values.isEmpty()){ret(kPMKeyNotFound);return;}int i=Math.max(0,index-1);if(i>=array.values.size()){ret(kPMKeyNotFound);return;}
			writeLong(arrayOut,a);writeLong(dictOut,array.values.get(i));ret(0);}); // (ticket, key, nodeIndex, *itemArray, *itemDict): the Get rule
		l.register(null,"KeyIsInternal",(r,c)->{String k=cfText(arg(0));ret(k!=null&&k.startsWith(T)?1:0);});
		l.register(null,"RemoveMetaData",(r,c)->{if(cf().dictionary(arg(0))==null){ret(kPMInvalidParameter);return;}removeMetaData(arg(0),0);ret(0);}); // (dictionary): client, modDate, stateFlag stripped from its items
		l.register(null,"FindSubDict",(r,c)->{int t=ticketOf(arg(0));String type=cfText(arg(1));int out=arg(3);writeLong(out,0);if(t==0||type==null){ret(kPMInvalidParameter);return;}
			int sub=cf().lookup(t,subTicketKey(type,arg(2)));if(sub==0||cf().lookup(sub,T_TYPE)==0){ret(kPMSubTicketNotFound);return;}writeLong(out,sub);ret(0);}); // (ticket, ticketType, nodeIndex, *subDict)
		l.register(null,"FindAllSubTickets",(r,c)->{int t=ticketOf(arg(0));String type=cfText(arg(1));int out=arg(2);writeLong(out,0);if(t==0||out==0){ret(kPMInvalidParameter);return;}
			List<Integer> found=new ArrayList<Integer>();for(int[] e:cf().dictionary(t).entries){String tt=cfText(cf().lookup(e[1],T_TYPE));if(tt!=null&&(type==null||tt.equals(type)))found.add(e[1]);}
			writeLong(out,cf().newObjectArray(found));ret(found.isEmpty()?kPMSubTicketNotFound:0);}); // (ticket, ticketType or NULL, *CFArray): a new array
		l.register(null,"FindEnumTicketType",(r,c)->{String type=cfText(arg(0));if(type==null){int t=ticketOf(arg(0));type=t==0?null:cfText(cf().lookup(t,T_TYPE));}
			int found=-1;for(int i=1;i<TICKET_TYPES.length;i++)if(TICKET_TYPES[i].equals(type))found=i;ret(found);}); // (ticketType or ticket) -> PMTicketType, kPMTicketTypeUnknown -1
		l.register(null,"CreateSubTicketKey",(r,c)->{String type=cfText(arg(0));ret(type==null?0:cf().newString(subTicketKey(type,arg(1))));}); // (ticketType, nodeIndex) -> a new CFString
		l.register(null,"UpdateOldTicket",(r,c)->{int t=ticketOf(arg(0));if(t==0){ret(kPMInvalidParameter);return;}updateOldTicket(t);ret(0);}); // (ticket): today's API version and items
		l.register(null,"SearchLeafForItem",(r,c)->{int t=ticketOf(arg(0));String key=cfText(arg(1));int out=arg(3);writeLong(out,0);if(t==0||key==null){ret(kPMInvalidParameter);return;}
			int v=leafItem(t,key,arg(2));writeLong(out,v);ret(v==0?kPMKeyNotFound:0);}); // (ticket, key, itemIndex, *item): this ticket only
		l.register(null,"SearchNodeForItem",(r,c)->{int t=ticketOf(arg(0));String key=cfText(arg(1));int out=arg(4);writeLong(out,0);if(t==0||key==null){ret(kPMInvalidParameter);return;}
			int v=searchNode(t,key,arg(2),arg(3));writeLong(out,v);ret(v==0?kPMKeyNotFound:0);}); // (ticket, key, nodeIndex, itemIndex, *item): the ticket and its sub-tickets

		// ---- mutexes, threads, logging
		l.register(null,"PMMutexCreate",(r,c)->{int out=arg(0);if(out==0){ret(kPMInvalidParameter);return;}writeLong(out,register(new Mutex(),0x706d4d58).ref);ret(0);}); // (PMMutex *) 'pmMX'
		l.register(null,"PMMutexRetain",(r,c)->{Mutex m=object(arg(0),Mutex.class);if(m==null){ret(kPMInvalidParameter);return;}m.count++;ret(0);});
		l.register(null,"PMMutexRelease",(r,c)->{Mutex m=object(arg(0),Mutex.class);ret(m==null?kPMInvalidParameter:release(m.ref));});
		l.register(null,"PMMutexLock",(r,c)->{Mutex m=object(arg(0),Mutex.class);if(m==null){ret(kPMInvalidParameter);return;}int me=threadID();
			for(int tries=0;m.depth>0&&m.owner!=me;tries++){if(tries>=10000){ret(kPMObjectInUse);return;}rt.toolbox("YieldToAnyThread");} // cooperative threads: the owner runs until it unlocks
			m.owner=me;m.depth++;ret(0);});
		l.register(null,"PMMutexTryLock",(r,c)->{Mutex m=object(arg(0),Mutex.class);if(m==null){ret(kPMInvalidParameter);return;}int me=threadID();
			if(m.depth>0&&m.owner!=me){ret(kPMObjectInUse);return;}m.owner=me;m.depth++;ret(0);});
		l.register(null,"PMMutexUnlock",(r,c)->{Mutex m=object(arg(0),Mutex.class);if(m==null){ret(kPMInvalidParameter);return;}if(m.depth==0||m.owner!=threadID()){ret(kPMOutOfScope);return;}m.depth--;ret(0);});
		l.register(null,"PMThreadGetID",(r,c)->ret(threadID()));
		l.registerData("pmLogControls",()->logControls());
		l.register(null,"PMLogInit",(r,c)->{logControls();ret(0);}); // the controls: -Dhzppc.pmlog turns the log on
		l.register(null,"PMLogAdd",(r,c)->{int ctl=logControls();if(memory.readLong(ctl)!=0){int level=arg(0),p=arg(1);if(Integer.compareUnsigned(level,0x10000)>=0){p=level;level=0;} // (level, message ...), or the message first
			String m=null;try{m=p==0?null:rt.cString(p);}catch(M68kCpuException e){/* not a string */}if(m!=null&&memory.readLong(ctl+4)>=level)System.err.println("PMLog: "+m);}ret(0);});

		// ---- PrintingCore's CF helpers (private: signatures inferred from their names)
		l.register(null,"PMCreateCFArray",(r,c)->ret(cf().newMutableObjectArray(new ArrayList<Integer>()))); // a new empty CFMutableArray of CF objects
		l.register(null,"PMCreateCFArrayFromPMRect",(r,c)->{int p=arg(0);ret(p==0?0:cf(List.of(readDouble(p),readDouble(p+8),readDouble(p+16),readDouble(p+24))));}); // (const PMRect *): top, left, bottom, right
		l.register(null,"PMCreateCFArrayFromPMResolution",(r,c)->{int p=arg(0);ret(p==0?0:cf(List.of(readDouble(p),readDouble(p+8))));}); // (const PMResolution *): hRes, vRes
		l.register(null,"PMCreatePMRectFromCFArray",(r,c)->{double[] v=doubles(arg(0),4);int out=arg(1);if(v==null||out==0){ret(kPMInvalidParameter);return;}writeRect(out,v[0],v[1],v[2],v[3]);ret(0);}); // (CFArray, PMRect *)
		l.register(null,"PMCreatePMResolutionFromCFArray",(r,c)->{double[] v=doubles(arg(0),2);int out=arg(1);if(v==null||out==0){ret(kPMInvalidParameter);return;}writeDouble(out,v[0]);writeDouble(out+8,v[1]);ret(0);});
		l.register(null,"PMCreateCFNumberFromDouble",(r,c)->ret(cf(rt.farg(0))));
		l.register(null,"PMCreateCFNumberFromSInt32",(r,c)->ret(cf(Integer.valueOf(arg(0)))));
		l.register(null,"PMCreateCFStringFromCFIndex",(r,c)->ret(cf().newString(Integer.toString(arg(0)))));
		l.register(null,"PMCreateCFStringFromCString",(r,c)->{int p=arg(0);ret(p==0?0:cf().newString(cf().decode(rt.libraries.textServices.systemEncoding(),rt.cBytes(p))));});
		l.register(null,"PMCreateCFStringFromPString",(r,c)->{int p=arg(0);ret(p==0?0:cf().newString(cf().decode(rt.libraries.textServices.systemEncoding(),java.util.Arrays.copyOfRange(rt.toolbox.readPascalBytes(p),1,1+memory.readByte(p)))));});
		l.register(null,"PMCreateCStringFromCFString",(r,c)->{String s=cfText(arg(0));if(s==null){ret(0);return;} // a new NUL-terminated Ptr in the system encoding (DisposePtr)
			byte[] b=cf().encode(rt.libraries.textServices.systemEncoding(),s);int p=rt.toolbox("NewPtrClear",b.length+1);if(p!=0)memory.load(p,b,0,b.length);ret(p);});
		l.register(null,"PMCFTypeIsStringOrArrayOfStrings",(r,c)->{int v=arg(0);if(cfText(v)!=null){ret(1);return;}PpcCoreFoundation.CFArray a=cf().array(v);
			boolean ok=a!=null;if(a!=null)for(int x:a.values)if(cfText(x)==null)ok=false;ret(ok?1:0);});
		l.register(null,"PMCFPrefsCopyMutableArray",(r,c)->{ // (key, applicationID): a mutable copy of an array preference, NULL when there is none
			String key=cfText(arg(0));if(key==null){ret(0);return;}int v=cf().copyAppPreference(key,arg(1));PpcCoreFoundation.CFArray a=cf().array(v);
			int copy=a==null?0:cf().newMutableObjectArray(a.values);if(v!=0)cf().release(v);ret(copy);});
		// files: (CFURL, out) / (CFURL, CF object) in either order - the URL is recognized by its type
		l.register(null,"PMReadCFDataFromFile",(r,c)->ret(readFile(false)));
		l.register(null,"PMReadCFPropertyListFromFile",(r,c)->ret(readFile(true)));
		l.register(null,"PMWriteCFDataToFile",(r,c)->ret(writeFile(false)));
		l.register(null,"PMWriteCFPropertyListToFile",(r,c)->ret(writeFile(true)));
	}

	/** A CFArray of page formats that retains them with PMRetain and releases them with PMRelease (0 without CFArray). */
	private int formatArray(int... refs) throws M68kException {
		if(arrayCallBacks==0){ // CFArrayCallBacks: version, retain, release, copyDescription, equal
			arrayCallBacks=rt.allocate(20,4);
			memory.writeLong(arrayCallBacks+4,rt.nativeVector("PMArrayRetain",(r,c)->{PMObject o=object(r.arg(1),PMObject.class);if(o!=null)o.count++;r.ret(r.arg(1));}));
			memory.writeLong(arrayCallBacks+8,rt.nativeVector("PMArrayRelease",(r,c)->release(r.arg(1))));
		}
		int create=rt.nativeSymbol("CarbonLib","CFArrayCreate",Pef.CLASS_TVECTOR,true);if(create==0)return 0;
		int list=rt.allocate(4*Math.max(1,refs.length),4);for(int i=0;i<refs.length;i++)memory.writeLong(list+4*i,refs[i]);
		try{return rt.cpu.call(create,0,list,refs.length,arrayCallBacks);}finally{memory.free(list);}
	}
	/** The current Thread Manager thread (kApplicationThreadID 1 when there is no Thread Manager answer). */
	private int threadID() throws M68kException {
		int p=rt.allocate(4,4);try{return rt.toolbox("GetCurrentThread",p)==0&&memory.readLong(p)!=0?memory.readLong(p):1;}catch(M68kCpuException e){return 1;}finally{memory.free(p);}
	}
	/** pmLogControls: {enabled, level}; -Dhzppc.pmlog[=level] turns logging on. */
	private int logControls() throws M68kCpuException {
		if(logControls==0){logControls=rt.allocate(16,4);String v=System.getProperty("hzppc.pmlog");if(v!=null){memory.writeLong(logControls,1);int level=9;try{level=Integer.parseInt(v.trim());}catch(NumberFormatException e){/* all levels */}memory.writeLong(logControls+4,level);}}
		return logControls;
	}
	/** The first {@code n} numbers of a CFArray of CFNumbers (null when it is not one). */
	private double[] doubles(int array,int n) throws M68kException {
		PpcCoreFoundation.CFArray a=cf().array(array);if(a==null||a.values.size()<n)return null;double[] v=new double[n];
		for(int i=0;i<n;i++){Object x=cf().toJava(a.values.get(i));if(!(x instanceof Number))return null;v[i]=((Number)x).doubleValue();}
		return v;
	}
	/** PMRead...FromFile: arg 0 or 1 is the CFURL, the other the result pointer. */
	private int readFile(boolean propertyList) throws M68kException {
		int a=arg(0),b=arg(1);boolean urlFirst=cf().url(a)!=null;int url=urlFirst?a:b,out=urlFirst?b:a;
		if(cf().url(url)==null||out==0)return kPMInvalidParameter;writeLong(out,0);
		java.io.File f=cf().fileOf(cf().url(url));if(f==null||!f.isFile())return -43; // fnfErr
		if(!Sandbox.allowFile(f,Sandbox.Access.READ,rt.toolbox.name))return -54; // permErr
		byte[] bytes;try{bytes=java.nio.file.Files.readAllBytes(f.toPath());}catch(IOException e){return -36;} // ioErr
		if(!propertyList){writeLong(out,cf().newData(bytes,false));return 0;}
		Object v=PpcCoreFoundation.parsePropertyList(bytes);if(v==null)return kPMGeneralError;
		writeLong(out,cf().toCF(v,true));return 0;
	}
	/** PMWrite...ToFile: one argument is the CFURL, the other the CFData or property list. */
	private int writeFile(boolean propertyList) throws M68kException {
		int a=arg(0),b=arg(1);boolean urlFirst=cf().url(a)!=null;int url=urlFirst?a:b,value=urlFirst?b:a;
		if(cf().url(url)==null||value==0)return kPMInvalidParameter;
		byte[] bytes;
		if(propertyList){Object v=cf().toJava(value);if(v==null)return kPMInvalidParameter;bytes=PpcCoreFoundation.writePropertyList(v);}
		else{PpcCoreFoundation.CFData d=cf().object(value,PpcCoreFoundation.CFData.class);if(d==null)return kPMInvalidParameter;bytes=d.bytes(cf());}
		java.io.File f=cf().fileOf(cf().url(url));if(f==null||f.getParentFile()==null||!f.getParentFile().isDirectory())return -120; // dirNFErr
		if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,rt.toolbox.name))return -54;
		try{java.nio.file.Files.write(f.toPath(),bytes);}catch(IOException e){return -36;}
		return 0;
	}

	// ------------------------------------------------------------------ job tickets

	/** The ticket {@code ref} stands for: a page format's or print settings' (refreshed), a CFDictionary as it is; 0 otherwise. */
	private int ticketOf(int ref) throws M68kException {
		PMObject o=ref==0?null:objects.get(ref);
		if(o instanceof PageFormat||o instanceof PrintSettings)return refreshTicket(o);
		return cf().dictionary(ref)!=null?ref:0;
	}
	/** The object's ticket with its Printing Manager items as the object has them now (locked items of com.apple.printingmanager). */
	private int refreshTicket(PMObject o) throws M68kException {
		boolean format=o instanceof PageFormat;
		if(o.ticket==0){o.ticket=cf().newTypeDictionary();own(o.ticket,T_TYPE,cf().newString(format?PAGE_FORMAT_TICKET:PRINT_SETTINGS_TICKET));own(o.ticket,T_API,cf().newString("00.20"));cf().put(o.ticket,T_LOCK,cf().bool(false));}
		Map<String,Object> items=new LinkedHashMap<String,Object>();
		if(format){PageFormat f=(PageFormat)o;int p=memory.readLong(f.h);
			items.put(PF+"PMOrientation",landscape(memory,p)?kPMLandscape:kPMPortrait);items.put(PF+"PMScaling",f.scale/100);
			items.put(PF+"PMHorizontalRes",(double)resolution(p,true));items.put(PF+"PMVerticalRes",(double)resolution(p,false));
			int rect=rt.allocate(32,4);
			try{pageRect(f,rect,true,false);items.put(PF+"PMAdjustedPageRect",List.of(readDouble(rect),readDouble(rect+8),readDouble(rect+16),readDouble(rect+24)));
				paperRect(f,rect,true);items.put(PF+"PMAdjustedPaperRect",List.of(readDouble(rect),readDouble(rect+8),readDouble(rect+16),readDouble(rect+24)));}
			finally{memory.free(rect);}
			items.put(PF+"FormattingPrinter",PRINTER_NAME);
		}else{PrintSettings s=(PrintSettings)o;int p=memory.readLong(s.h);
			items.put(PS+"PMCopies",Math.max(1,word(p,66)));items.put(PS+"PMFirstPage",word(p,62)&0xffff);items.put(PS+"PMLastPage",word(p,64)&0xffff);
			items.put(PS+"PMCollate",s.collate);items.put(PS+"PMColorMode",s.colorMode);items.put(PS+"PMPageRange",List.of(s.rangeMin,s.rangeMax));
			items.put(PS+"PMDestinationType",kPMDestinationPrinter);items.put("com.apple.print.JobInfo.PMJobName",jobName(s));
		}
		for(Map.Entry<String,Object> e:items.entrySet()){int v=cf(e.getValue());try{setItem(o.ticket,PM_CLIENT,e.getKey(),v,true,true,false);}finally{cf().release(v);}}
		return o.ticket;
	}
	private boolean ticketLocked(int t) throws M68kException {return Boolean.TRUE.equals(cf().toJava(cf().lookup(t,T_LOCK)));}
	/** A new item dictionary {key: value, client, modDate, stateFlag}. */
	private int itemDictionary(String client,String key,int value,boolean locked) throws M68kException {
		int d=cf().newTypeDictionary();cf().put(d,key,value);own(d,T_CLIENT,cf().newString(client));own(d,T_DATE,cf().newDate(PpcCoreFoundation.now()));own(d,T_STATE,cf(locked?1:0));
		return d;
	}
	/** The first item dictionary under {@code key} (0 when there is none). */
	private int firstItem(int t,String key) throws M68kException {PpcCoreFoundation.CFArray a=cf().array(cf().lookup(cf().lookup(t,key),T_ITEMS));return a==null||a.values.isEmpty()?0:a.values.get(0);}
	private boolean itemLocked(int item,String client) throws M68kException {
		Object state=cf().toJava(cf().lookup(item,T_STATE));String owner=cfText(cf().lookup(item,T_CLIENT));
		return state instanceof Number&&((Number)state).intValue()!=0&&owner!=null&&!owner.equals(client);
	}
	/**
	 * Sets ticket[key] to an item of {@code client}: refused when the ticket is locked (PMTicketLock) or the item is locked by another
	 * client; {@code force}: the Printing Manager's own refresh; {@code existing}: UpdateItem (the key must be there).
	 */
	private int setItem(int t,String client,String key,int value,boolean locked,boolean force,boolean existing) throws M68kException {
		if(key.startsWith(T))return kPMInvalidParameter; // the ticket's own keys are not items
		int old=firstItem(t,key);
		if(existing&&old==0)return kPMKeyNotFound;
		if(!force){if(ticketLocked(t))return kPMTicketIsLocked;if(old!=0&&itemLocked(old,client))return kPMItemIsLocked;}
		int item=itemDictionary(client,key,value,locked);
		List<Integer> one=new ArrayList<Integer>();one.add(item);int array=cf().newMutableObjectArray(one);cf().release(item);
		int container=cf().newTypeDictionary();own(container,T_CREATOR,cf().newString(client));own(container,T_ITEMS,array);
		own(t,key,container);return 0;
	}
	private int lockItem(int t,String client,String key,boolean lock) throws M68kException {
		int item=firstItem(t,key);if(item==0)return kPMKeyNotFound;
		if(ticketLocked(t))return kPMTicketIsLocked;if(itemLocked(item,client))return kPMItemIsLocked;
		own(item,T_STATE,cf(lock?1:0));own(item,T_CLIENT,cf().newString(client));return 0;
	}
	private boolean validItem(int container) throws M68kException {
		if(cf().dictionary(container)==null||cfText(cf().lookup(container,T_CREATOR))==null)return false;
		PpcCoreFoundation.CFArray a=cf().array(cf().lookup(container,T_ITEMS));if(a==null||a.values.isEmpty())return false;
		for(int item:a.values)if(cf().dictionary(item)==null||cfText(cf().lookup(item,T_CLIENT))==null)return false;
		return true;
	}
	/** Strips the per-item metadata (client, modDate, stateFlag) from a dictionary and the dictionaries and arrays below it. */
	private void removeMetaData(int ref,int depth) throws M68kException {
		if(depth>32)return;
		PpcCoreFoundation.CFDictionary d=cf().dictionary(ref);
		if(d!=null){for(String k:new String[]{T_CLIENT,T_DATE,T_STATE}){int key=cf().newString(k);try{d.remove(key);}finally{cf().release(key);}}for(int[] e:new ArrayList<int[]>(d.entries))removeMetaData(e[1],depth+1);return;}
		PpcCoreFoundation.CFArray a=cf().array(ref);if(a!=null)for(int v:new ArrayList<Integer>(a.values))removeMetaData(v,depth+1);
	}
	/** A sub-ticket's key in its parent: the ticket type, then ".2", ".3" ... for the further ones. */
	private static String subTicketKey(String type,int nodeIndex){return nodeIndex<=1?type:type+"."+nodeIndex;}
	/** An older ticket brought up to date: API version, lock, and bare values wrapped as items of com.apple.printingmanager. */
	private void updateOldTicket(int t) throws M68kException {
		own(t,T_API,cf().newString("00.20"));if(cf().lookup(t,T_LOCK)==0)cf().put(t,T_LOCK,cf().bool(false));
		for(int[] e:new ArrayList<int[]>(cf().dictionary(t).entries)){String k=cfText(e[0]);if(k==null||k.startsWith(T))continue;
			if(cf().lookup(e[1],T_TYPE)!=0){updateOldTicket(e[1]);continue;} // a sub-ticket
			if(!validItem(e[1])){int v=cf().retain(e[1]);try{setItem(t,PM_CLIENT,k,v,false,true,false);}finally{cf().release(v);}}}
	}
	/** ticket[key]'s value in its {@code index}-th item (0 and 1: the first), this ticket only. */
	private int leafItem(int t,String key,int index) throws M68kException {
		PpcCoreFoundation.CFArray a=cf().array(cf().lookup(cf().lookup(t,key),T_ITEMS));int i=Math.max(0,index-1);
		return a==null||i>=a.values.size()?0:cf().lookup(a.values.get(i),key);
	}
	/** The value of {@code key}: in the ticket ({@code node} 0, kPMTopLevel) or its {@code node}-th sub-ticket that has it, depth first. */
	private int searchNode(int t,String key,int node,int index) throws M68kException {
		List<Integer> tickets=new ArrayList<Integer>();collectTickets(t,tickets,0);
		int seen=0;for(int x:tickets){int v=leafItem(x,key,index);if(v==0)continue;if(node<=0||++seen==node)return v;}
		return 0;
	}
	private void collectTickets(int t,List<Integer> out,int depth) throws M68kException {
		out.add(t);if(depth>16)return;
		for(int[] e:cf().dictionary(t).entries)if(cf().dictionary(e[1])!=null&&cf().lookup(e[1],T_TYPE)!=0)collectTickets(e[1],out,depth+1);
	}

	// ------------------------------------------------------------------ objects' life

	private int release(int ref) throws M68kException {
		PMObject o=object(ref,PMObject.class);if(o==null)return kPMInvalidParameter;
		if(o instanceof Printer)return 0; // owned by the Printing Manager
		if(--o.count>0)return 0;
		return free(o);
	}
	/** Disposes of an object: its record, an open document (abandoned), the session's data; PrClose for a session. */
	private int free(PMObject o) throws M68kException {
		if(o==null)return kPMInvalidParameter;
		objects.remove(o.ref);
		if(o.ticket!=0){rt.libraries.coreFoundation.release(o.ticket);o.ticket=0;}
		if(o instanceof PageFormat)dispose(((PageFormat)o).h);
		else if(o instanceof PrintSettings)dispose(((PrintSettings)o).h);
		else if(o instanceof Session){Session s=(Session)o;
			if(s.document!=null){s.error=I_PR_ABORT;endDocument(s);}
			for(int v:s.data.values())rt.libraries.coreFoundation.release(v);s.data.clear();
			if(!s.context)rt.toolbox("PrClose");}
		else if(o instanceof Dialog){Dialog d=(Dialog)o;if(d.dialogRef!=0)rt.toolbox("DisposeDialog",d.dialogRef);}
		memory.free(o.ref);
		return 0;
	}
	private Session context(int ref){Session s=session(ref);return s!=null&&s.context?s:null;}
	private Printer printer() throws M68kCpuException {if(printer==null)printer=register(new Printer(),0x706d5052);return printer;} // 'pmPR'
	private boolean isPrinter(int ref){return printer!=null&&ref==printer.ref;}

	// ------------------------------------------------------------------ defaults and validation

	private int defaultFormat(PageFormat f) throws M68kException {
		if(f==null)return kPMInvalidPageFormat;
		rt.toolbox("PrintDefault",f.h);f.orientation=0;f.scale=100;return prError();
	}
	private int defaultSettings(PrintSettings s) throws M68kException {
		if(s==null)return kPMInvalidPrintSettings;
		rt.toolbox("PrintDefault",s.h);s.rangeMin=1;s.rangeMax=0xffffffffL;s.colorMode=kPMColor;s.collate=true;return prError();
	}
	/** PrValidate: {@code changed} gets whether the record had to be changed. */
	private int validateFormat(PageFormat f,int changed) throws M68kException {
		if(f==null)return kPMInvalidPageFormat;
		boolean c=rt.toolbox("PrValidate",f.h)!=0;applyOrientation(f);writeBoolean(changed,c);return prError();
	}
	private int validateSettings(PrintSettings s,int changed) throws M68kException {
		if(s==null)return kPMInvalidPrintSettings;
		boolean c=rt.toolbox("PrValidate",s.h)!=0;writeBoolean(changed,c);return prError();
	}

	// ------------------------------------------------------------------ dialogs

	/** PrStlDialog on the page format's record; a cancel leaves kPMCancel as the session's (or PMError's) error. */
	private int pageSetupDialog(Session s,PageFormat f,int accepted) throws M68kException {
		if(f==null)return kPMInvalidPageFormat;
		boolean ok=rt.toolbox("PrStlDialog",f.h)!=0;
		if(ok)f.orientation=0;else applyOrientation(f); // the user's choice replaces PMSetOrientation's
		writeBoolean(accepted,ok);dialogResult(s,ok);return 0;
	}
	/**
	 * PrJobDialog. It runs on the page format's record when there is one (the host's print dialog then keeps the chosen
	 * printer with the record the document is opened on), after the settings' job fields were merged into it; the answers
	 * are merged back into the settings' record.
	 */
	private int printDialog(Session s,PrintSettings ps,int formatRef,int accepted) throws M68kException {
		if(ps==null)return kPMInvalidPrintSettings;
		PageFormat f=format(formatRef);if(formatRef!=0&&f==null)return kPMInvalidPageFormat;
		boolean ok;
		if(f==null)ok=rt.toolbox("PrJobDialog",ps.h)!=0;
		else{
			rt.toolbox("PrJobMerge",ps.h,f.h);
			ok=rt.toolbox("PrJobDialog",f.h)!=0;
			applyOrientation(f);
			if(ok)rt.toolbox("PrJobMerge",f.h,ps.h);
		}
		writeBoolean(accepted,ok);dialogResult(s,ok);return 0;
	}
	private void dialogResult(Session s,boolean ok){int e=ok?0:kPMCancel;if(s!=null)s.error=e;else oldError=e;}

	/** PM...DialogInit: a PMDialog the program may customize before the dialog runs. */
	private int dialogInit(boolean job,int target,int out) throws M68kException {
		if(job?settings(target)==null:format(target)==null)return job?kPMInvalidPrintSettings:kPMInvalidPageFormat;
		if(out==0)return kPMInvalidParameter;
		Dialog d=register(new Dialog(),0x706d444c);d.job=job;d.target=target;writeLong(out,d.ref); // 'pmDL'
		return 0;
	}
	/**
	 * PM...DialogMain: the program's init procedure (settings or format, PMDialog*) runs first - it normally calls
	 * PM...DialogInit and customizes the dialog - then the classic dialog; the PMDialog is disposed of afterwards.
	 */
	private int dialogMain(Session s,boolean job,int settingsRef,int formatRef,int accepted,int initProc) throws M68kException {
		if(job?settings(settingsRef)==null:format(formatRef)==null)return job?kPMInvalidPrintSettings:kPMInvalidPageFormat;
		Dialog d=null;
		if(initProc!=0){
			int slot=rt.allocate(4,4);
			try{rt.callUniversal(initProc,0x3c0,new int[]{job?settingsRef:formatRef,slot});d=dialog(memory.readLong(slot));} // uppPMPrintDialogInitProcInfo / uppPMPageSetupDialogInitProcInfo
			finally{memory.free(slot);}
		}
		int flag=rt.allocate(4,4);
		try{
			int e=job?printDialog(s,settings(settingsRef),formatRef,flag):pageSetupDialog(s,format(formatRef),flag);
			boolean ok=memory.readByte(flag)!=0;
			if(d!=null){d.accepted=ok;d.done=true;free(d);}
			writeBoolean(accepted,ok);return e;
		}finally{memory.free(flag);}
	}
	/** An invisible dialog with no items standing for the Printing Manager's (a program may append its items to it). */
	private int dialogRef(Dialog d) throws M68kException {
		if(d.dialogRef!=0)return d.dialogRef;
		int items=rt.toolbox.api.createHandle(new byte[]{(byte)0xff,(byte)0xff}); // item count - 1 = -1
		int bounds=rt.allocate(8,4),title=rt.allocate(4,4);
		memory.writeWord(bounds,40);memory.writeWord(bounds+2,40);memory.writeWord(bounds+4,340);memory.writeWord(bounds+6,440);
		try{d.dialogRef=rt.toolbox("NewDialog",0,bounds,title,0,1,-1,0,0,items);} // invisible dBoxProc, frontmost, no go-away box
		finally{memory.free(bounds);memory.free(title);}
		return d.dialogRef;
	}
	private int defaultFilter() throws M68kCpuException {
		if(defaultFilter==0)defaultFilter=rt.newRoutineDescriptor(rt.nativeVector("PMStandardModalFilter",(r,c)->r.ret(0)),0xfd0,PpcRuntime.ISA_PPC); // uppModalFilterProcInfo: nothing handled
		return defaultFilter;
	}
	private int defaultItemProc() throws M68kCpuException {
		if(defaultItemProc==0)defaultItemProc=rt.newRoutineDescriptor(rt.nativeVector("PMStandardItemProc",(r,c)->{}),0x2c0,PpcRuntime.ISA_PPC); // uppPMItemProcInfo
		return defaultItemProc;
	}

	// ------------------------------------------------------------------ documents and pages

	/** The settings' job merged into the page format's record, then PrOpenDoc on it: the printing port becomes current. */
	private int beginDocument(Session s,PrintSettings ps,PageFormat f) throws M68kException {
		if(f==null)return fail(s,kPMInvalidPageFormat);
		if(s.document!=null)return fail(s,kPMOutOfScope);
		s.error=0;
		if(ps!=null){rt.toolbox("PrJobMerge",ps.h,f.h);rt.toolbox("PrValidate",f.h);applyOrientation(f);} // PrValidate: the host session reads the merged job
		int port=rt.toolbox("PrOpenDoc",f.h,0,0);int e=prError();
		if(e!=0||port==0){if(port!=0)rt.toolbox("PrCloseDoc",port);return fail(s,e!=0?e:kPMGeneralError);}
		Document d=new Document();d.port=port;d.h=f.h;s.document=d;
		return 0;
	}
	private int beginPage(Session s) throws M68kException {
		Document d=s.document;if(d==null)return fail(s,kPMOutOfScope);
		if(d.pageOpen)rt.toolbox("PrClosePage",d.port);
		rt.toolbox("PrOpenPage",d.port,0);int e=prError();
		if(e!=0)return fail(s,e);
		d.pageOpen=true;d.pages++;return 0;
	}
	private int endPage(Session s) throws M68kException {
		Document d=s.document;if(d==null||!d.pageOpen)return fail(s,kPMOutOfScope);
		rt.toolbox("PrClosePage",d.port);d.pageOpen=false;
		if(s.error!=0)return s.error; // the program abandoned the document (PMSessionSetError): its loop stops
		int e=prError();return e!=0?fail(s,e):0;
	}
	/** PrCloseDoc prints the pages - unless the program set an error (PMSessionSetError/PMSetError), which abandons them. */
	private int endDocument(Session s) throws M68kException {
		Document d=s.document;if(d==null)return fail(s,kPMOutOfScope);
		if(d.pageOpen){rt.toolbox("PrClosePage",d.port);d.pageOpen=false;}
		boolean abandoned=s.error!=0;
		if(abandoned)rt.toolbox("PrSetError",I_PR_ABORT);
		rt.toolbox("PrCloseDoc",d.port);s.document=null;
		int e=prError();
		if(abandoned){rt.toolbox("PrSetError",0);return 0;}
		return e!=0?fail(s,e):0;
	}
	private int fail(Session s,int e){if(s!=null&&e!=0)s.error=e;return e;}
	private void setError(Session s,int e) throws M68kException {s.error=e;if(s.document!=null)rt.toolbox("PrSetError",e==0?0:I_PR_ABORT);}

	/** kPMGraphicsContextQuickdraw (or the default): the printing port. */
	private int graphicsContext(Session s,int type,int out) throws M68kException {
		if(out==0)return kPMInvalidParameter;
		String t=type==0?null:cfText(type);
		if(t!=null&&!t.equals(CONTEXT_QUICKDRAW)&&!t.equals(CONTEXT_DEFAULT)){writeLong(out,0);return t.equals(CONTEXT_COREGRAPHICS)?kPMNotImplemented:kPMInvalidParameter;}
		if(s.document==null){writeLong(out,0);return kPMOutOfScope;}
		writeLong(out,s.document.port);return 0;
	}
	private static boolean quickDrawFormat(String f){return f.equals(FORMAT_DEFAULT)||f.equals(FORMAT_PICT)||f.equals(FORMAT_PICT_PS);}
	/** Only QuickDraw documents drawn through a QuickDraw port: a Core Graphics context (without QuickDraw) is refused. */
	private int setDocumentFormat(Session s,int format,int contexts) throws M68kException {
		String f=format==0?FORMAT_DEFAULT:cfText(format);
		if(f==null)return kPMInvalidParameter;
		if(!quickDrawFormat(f))return kPMNotImplemented;
		if(contexts!=0){
			int n=carbon("CFArrayGetCount",contexts);boolean quickDraw=n<=0,coreGraphics=false;
			for(int i=0;i<n;i++){String t=cfText(carbon("CFArrayGetValueAtIndex",contexts,i));if(t==null)continue;
				if(t.equals(CONTEXT_QUICKDRAW)||t.equals(CONTEXT_DEFAULT))quickDraw=true;else if(t.equals(CONTEXT_COREGRAPHICS))coreGraphics=true;}
			if(coreGraphics&&!quickDraw)return kPMNotImplemented;
		}
		s.documentFormat=f;return 0;
	}
	/** A new CFArray of new CFStrings (0 when CFArray is not available). */
	private int stringArray(String... values) throws M68kException {
		int create=rt.nativeSymbol("CarbonLib","CFArrayCreate",Pef.CLASS_TVECTOR,true),callBacks=rt.nativeSymbol("CarbonLib","kCFTypeArrayCallBacks",Pef.CLASS_DATA,true);
		if(create==0)return 0;
		int list=rt.allocate(4*values.length,4);int[] strings=new int[values.length];
		for(int i=0;i<values.length;i++){strings[i]=rt.libraries.textServices.newCFString(values[i]);memory.writeLong(list+4*i,strings[i]);}
		int array=rt.cpu.call(create,0,list,values.length,callBacks);
		memory.free(list);
		if(callBacks!=0||array==0)for(int ref:strings)rt.libraries.coreFoundation.release(ref); // the array retained them
		return array;
	}
	private int carbon(String name,int... args) throws M68kException {int tv=rt.nativeSymbol("CarbonLib",name,Pef.CLASS_TVECTOR,true);return tv==0?0:rt.cpu.call(tv,args);}
	private String cfText(int ref){return ref==0?null:rt.libraries.textServices.cfText(ref);}

	/** A PostScript PicComment into the printing port while a page is open (ignored by the page capture); noErr otherwise. */
	private int postScript(Session s,int kind,byte[] data) throws M68kException {
		if(s==null||s.document==null||!s.document.pageOpen)return 0;
		int h=data==null?0:rt.toolbox.api.createHandle(data);
		try{rt.toolbox("PicComment",kind,data==null?0:data.length,h);}finally{dispose(h);}
		return 0;
	}
	private byte[] handleBytes(int h) throws M68kCpuException {int p=memory.readLong(h);return p==0?new byte[0]:memory.readBytes(p,rt.toolbox.api.handleSize(h));}

	/** PrGeneral; the block carries the opcode's own result (iError). */
	private int general(int data) throws M68kException {
		if(data==0)return kPMInvalidParameter;
		rt.toolbox("PrGeneral",data);return prError();
	}

	// ------------------------------------------------------------------ old print records

	private int convertOld(int h,int settingsOut,int formatOut) throws M68kException {
		if(h==0||memory.readLong(h)==0)return kPMInvalidParameter;
		if(settingsOut!=0)writeLong(settingsOut,newSettings(copyRecord(h)).ref);
		if(formatOut!=0)writeLong(formatOut,newFormat(copyRecord(h)).ref);
		return 0;
	}
	/** A new TPrint Handle: the page format's record with the settings' job (prJob, 20 bytes at 62). */
	private int makeOld(PrintSettings s,PageFormat f,int out) throws M68kException {
		if(s==null)return kPMInvalidPrintSettings;if(f==null)return kPMInvalidPageFormat;if(out==0)return kPMInvalidParameter;
		int h=copyRecord(f.h);int p=memory.readLong(h),q=memory.readLong(s.h);
		memory.copy(q+62,p+62,20);writeLong(out,h);return 0;
	}

	// ------------------------------------------------------------------ flattened forms

	private byte[] flatten(PageFormat f) throws M68kCpuException {
		ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);
		try{o.writeInt(FLAT_FORMAT);o.writeShort(1);o.write(recordBytes(f.h));o.writeDouble(f.scale);o.writeShort(f.orientation);writeExtended(o,f.extended);}catch(IOException e){throw new IllegalStateException(e);}
		return b.toByteArray();
	}
	private byte[] flatten(PrintSettings s) throws M68kCpuException {
		ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);
		try{o.writeInt(FLAT_SETTINGS);o.writeShort(1);o.write(recordBytes(s.h));o.writeInt((int)s.rangeMin);o.writeInt((int)s.rangeMax);o.writeShort(s.colorMode);
			byte[] name=s.jobName==null?new byte[0]:s.jobName.getBytes(StandardCharsets.UTF_8);o.writeShort(s.jobName==null?-1:name.length);o.write(name);writeExtended(o,s.extended);o.writeByte(s.collate?1:0);}
		catch(IOException e){throw new IllegalStateException(e);}
		return b.toByteArray();
	}
	private static void writeExtended(DataOutputStream o,Map<Integer,byte[]> m) throws IOException {o.writeShort(m.size());for(Map.Entry<Integer,byte[]> e:m.entrySet()){o.writeInt(e.getKey());o.writeInt(e.getValue().length);o.write(e.getValue());}}
	private static Map<Integer,byte[]> readExtended(DataInputStream in) throws IOException {Map<Integer,byte[]> m=new LinkedHashMap<Integer,byte[]>();int n=in.readUnsignedShort();for(int i=0;i<n;i++){int id=in.readInt();byte[] v=new byte[in.readInt()];in.readFully(v);m.put(id,v);}return m;}

	/** Our flattened form, or a bare 120-byte TPrint (a print record saved by a classic program). */
	private int unflattenFormat(int flat,int out) throws M68kException {
		if(flat==0||out==0||memory.readLong(flat)==0)return kPMInvalidParameter;
		byte[] b=handleBytes(flat);
		if(b.length==TPRINT){PageFormat f=newFormat(rt.toolbox.api.createHandle(b));writeLong(out,f.ref);return 0;}
		try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(b));
			if(in.readInt()!=FLAT_FORMAT||in.readUnsignedShort()!=1)return kPMInvalidParameter;
			byte[] record=new byte[TPRINT];in.readFully(record);double scale=in.readDouble();int orientation=in.readShort();Map<Integer,byte[]> ext=readExtended(in);
			PageFormat f=newFormat(rt.toolbox.api.createHandle(record));f.scale=scale;f.orientation=orientation;f.extended=ext;writeLong(out,f.ref);return 0;
		}catch(IOException e){return kPMInvalidParameter;}
	}
	private int unflattenSettings(int flat,int out) throws M68kException {
		if(flat==0||out==0||memory.readLong(flat)==0)return kPMInvalidParameter;
		byte[] b=handleBytes(flat);
		if(b.length==TPRINT){PrintSettings s=newSettings(rt.toolbox.api.createHandle(b));writeLong(out,s.ref);return 0;}
		try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(b));
			if(in.readInt()!=FLAT_SETTINGS||in.readUnsignedShort()!=1)return kPMInvalidParameter;
			byte[] record=new byte[TPRINT];in.readFully(record);long min=in.readInt()&0xffffffffL,max=in.readInt()&0xffffffffL;int mode=in.readUnsignedShort();
			int n=in.readShort();String name=null;if(n>=0){byte[] nb=new byte[n];in.readFully(nb);name=new String(nb,StandardCharsets.UTF_8);}
			Map<Integer,byte[]> ext=readExtended(in);boolean collate=in.available()<=0||in.readByte()!=0; // the collate flag was appended later: older flattened settings collate
			PrintSettings s=newSettings(rt.toolbox.api.createHandle(record));s.rangeMin=min;s.rangeMax=max;s.colorMode=mode;s.jobName=name;s.extended=ext;s.collate=collate;writeLong(out,s.ref);return 0;
		}catch(IOException e){return kPMInvalidParameter;}
	}

	/** Extended data: {@code data} 0 asks only for the size; otherwise at most the size the program gave is copied. */
	private int getExtended(Map<Integer,byte[]> m,int id,int size,int data) throws M68kCpuException {
		byte[] v=m.get(id);if(v==null)return kPMNoSuchEntry;
		if(size==0&&data==0)return kPMInvalidParameter;
		int n=v.length;if(data!=0){int room=size==0?n:memory.readLong(size);if(room>0&&room<n)n=room;memory.load(data,v,0,n);}
		writeLong(size,data!=0?n:v.length);return 0;
	}
	private int setExtended(Map<Integer,byte[]> m,int id,int size,int data) throws M68kCpuException {
		if(size<0||(size>0&&data==0))return kPMInvalidParameter;
		m.put(id,size==0?new byte[0]:memory.readBytes(data,size));return 0;
	}

	// ------------------------------------------------------------------ page format values

	/**
	 * rPage as a PMRect. Adjusted: in the record's dots (the drawing coordinates of the printing port, with the
	 * orientation); unadjusted: in points, as for portrait paper; {@code sizeOnly}: at the origin (PMGetPhysicalPageSize).
	 */
	private int pageRect(PageFormat f,int out,boolean adjusted,boolean sizeOnly) throws M68kCpuException {
		if(f==null)return kPMInvalidPageFormat;if(out==0)return kPMInvalidParameter;
		int p=memory.readLong(f.h);
		// PMRects are in points whatever the drawing resolution: on Mac OS 9 the printing port is in the resolution's dots
		// and programs take the resolution as port width / page width (Acrobat 5's GetResolutionFromPortBounds - with the
		// rectangle in dots it rasterized a 72-dpi page in the middle of a 288-dpi sheet). Adjusted: as the page is set up
		// (orientation applied); unadjusted: portrait.
		double top=word(p,8),left=word(p,10),bottom=word(p,12),right=word(p,14);
		double h=72.0/resolution(p,true),v=72.0/resolution(p,false);
		top*=v;bottom*=v;left*=h;right*=h;
		if(adjusted){writeRect(out,top,left,bottom,right);return 0;}
		if(landscape(memory,p)){double t=top,b=bottom;top=left;bottom=right;left=t;right=b;}
		if(sizeOnly){bottom-=top;right-=left;top=0;left=0;}
		writeRect(out,top,left,bottom,right);return 0;
	}
	/** rPaper as a PMRect, relative to the page's origin: adjusted in dots, unadjusted/physical in points as for portrait paper. */
	private int paperRect(PageFormat f,int out,boolean adjusted) throws M68kCpuException {
		if(f==null)return kPMInvalidPageFormat;if(out==0)return kPMInvalidParameter;
		int p=memory.readLong(f.h);
		double top=word(p,16),left=word(p,18),bottom=word(p,20),right=word(p,22);
		double h=72.0/resolution(p,true),v=72.0/resolution(p,false);top*=v;bottom*=v;left*=h;right*=h; // points, as pageRect
		if(!adjusted&&landscape(memory,p)){double t=top,b=bottom;top=left;bottom=right;left=t;right=b;}
		writeRect(out,top,left,bottom,right);return 0;
	}
	/** PrGeneral setRsl on the page format's record (rPage and the page image are then in those dots). */
	private int setResolution(PageFormat f,int x,int y) throws M68kException {
		int block=rt.allocate(16,4);
		try{memory.writeWord(block,5);memory.writeLong(block+8,f.h);memory.writeWord(block+12,x);memory.writeWord(block+14,y); // TSetRslBlk: iOpCode setRslOp, iError, lReserved, hPrint, iXRsl, iYRsl
			rt.toolbox("PrGeneral",block);applyOrientation(f);
			return memory.readWord(block+2)!=0?kPMValueOutOfRange:0;}
		finally{memory.free(block);}
	}

	// ------------------------------------------------------------------ print settings values

	private int getJob(PrintSettings s,int offset,int out) throws M68kCpuException {
		if(s==null)return kPMInvalidPrintSettings;if(out==0)return kPMInvalidParameter;
		int v=word(memory.readLong(s.h),offset);memory.writeLong(out,Math.max(offset==66?1:0,v));return 0;
	}
	/** iFstPage/iLstPage/iCopies of the record; kPMPrintAllPages (and anything past 9999) is the classic iPrPgMax, 9999. */
	private int setJob(PrintSettings s,int offset,int value) throws M68kCpuException {
		if(s==null)return kPMInvalidPrintSettings;
		long v=value&0xffffffffL;int w=(int)Math.max(1,Math.min(offset==66?999:9999,v));
		memory.writeWord(memory.readLong(s.h)+offset,w);return 0;
	}
	private String jobName(PrintSettings s){return s.jobName!=null?s.jobName:rt.app!=null&&rt.app.name!=null?rt.app.name:"";}

	// ------------------------------------------------------------------ the printer

	/** PrGeneral getRslData: {xMin, xMax, yMin, yMax, then x,y of each discrete resolution}. */
	private int[] resolutions() throws M68kException {
		int block=rt.allocate(128,4);
		try{memory.writeWord(block,4);rt.toolbox("PrGeneral",block); // TGetRslBlk: iOpCode getRslDataOp, iError, lReserved, iRgType, xRslRg, yRslRg, iRslRecCnt, rgRslRec[27]
			if(memory.readWord(block+2)!=0)return new int[]{72,72,72,72,72,72};
			int n=Math.max(0,Math.min(27,word(block,18)));int[] r=new int[4+2*n];
			r[0]=word(block,10);r[1]=word(block,12);r[2]=word(block,14);r[3]=word(block,16);
			for(int i=0;i<n;i++){r[4+2*i]=word(block,20+4*i);r[5+2*i]=word(block,22+4*i);}
			if(n==0)return new int[]{r[0],r[1],r[2],r[3],72,72};
			return r;}
		finally{memory.free(block);}
	}
	private int resolutionCount(int out) throws M68kException {if(out==0)return kPMInvalidParameter;memory.writeLong(out,(resolutions().length-4)/2);return 0;}
	private int indexedResolution(int index,int out) throws M68kException {
		if(out==0)return kPMInvalidParameter;int[] r=resolutions();int n=(r.length-4)/2;
		if(index<1||index>n)return kPMValueOutOfRange; // 1-based
		writeDouble(out,r[2+2*index]);writeDouble(out+8,r[3+2*index]);return 0;
	}
	private int printerResolution(int tag,int out) throws M68kException {
		if(out==0)return kPMInvalidParameter;int[] r=resolutions();int x,y;
		switch(tag){
			case 0x6d6e7267:x=r[0];y=r[2];break;                // 'mnrg' kPMMinRange
			case 0x6d787267:x=r[1];y=r[3];break;                // 'mxrg' kPMMaxRange
			case 0x6d696e73:case 0x6d617873:{boolean max=tag==0x6d617873;x=y=0; // 'mins'/'maxs': the smallest/largest resolution with x == y
				for(int i=4;i<r.length;i+=2)if(r[i]==r[i+1]&&(x==0||(max?r[i]>x:r[i]<x)))x=y=r[i];
				if(x==0)return kPMNoSuchEntry;break;}
			case 0x64667472:case 0x64666c74:case 0x63757272:x=y=72;break; // 'dftr' kPMDefaultResolution, 'dflt', 'curr': the classic 72 dpi
			default:return kPMInvalidParameter;
		}
		writeDouble(out,x);writeDouble(out+8,y);return 0;
	}
	/** Not a PostScript printer: empty level, version and release (three Str32s). */
	private int languageInfo(int out) throws M68kCpuException {if(out==0)return kPMInvalidParameter;memory.fill(out,99,0);return 0;}
	private int driverCreator(int out) throws M68kCpuException {if(out==0)return kPMInvalidParameter;memory.writeLong(out,0x485a7072);return 0;} // 'HZpr': HyperZebra's printer stand-in
	/** VersRec of the driver: 7.0 final, as PrDrvrVers reports. */
	private int releaseInfo(int out) throws M68kCpuException {
		if(out==0)return kPMInvalidParameter;
		memory.fill(out,4+2+256+256,0);memory.writeLong(out,0x07008000);rt.writePString(out+6,"7.0");rt.writePString(out+6+256,"7.0, HyperZebra");return 0;
	}
}

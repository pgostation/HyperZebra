import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Macintosh Communications Toolbox: the Communications Resource Manager, the Connection, Terminal and File Transfer
 * Managers and the CTB utilities, reached through _CommToolboxDispatch $A08B - A0 points at the selector word, the Pascal
 * arguments lie above it (the last one first), the result comes back in D0 (MPW's CommToolbox glue). PowerPC programs reach
 * the same code through InterfaceLib glue made from {@link #TABLE} (see {@link #glueLine}). The DITL utilities
 * (AppendDITL/CountDITL/ShortenDITL) stay with the Dialog Manager.
 *
 * Tools are files of type 'cbnd' (connection), 'tbnd' (terminal) or 'fbnd' (file transfer) in the System Folder's Extensions
 * (or its Communications Folder); their definition procedures - 68K code resources 'cdef'/'cval'/'cset'/'cscr'/'cloc' and the
 * 't' and 'f' ones - do the work, called as FUNCTION def(record, msg: INTEGER; p1, p2, p3: LONGINT): LONGINT with the tool's
 * file current. The serial ports are registered with the CRM ("Modem Port", "Printer Port"); their drivers have nothing
 * connected (FileSystem68k), so a connection opens but nothing ever arrives.
 */
final class CommToolbox68k {
	static final int CLASS_CM=0x63626e64,CLASS_TM=0x74626e64,CLASS_FT=0x66626e64; // 'cbnd' 'tbnd' 'fbnd'
	static final int NOT_SUPPORTED=7,CHOOSE_OK_MINOR=1,CHOOSE_OK_MAJOR=2,CHOOSE_CANCEL=3,CHOOSE_FAILED=-1;
	/** Selector, routine name, result ("v", "2", "4", "B") and argument sizes in declaration order ("-" for none), from MPW's
	 *  CommToolbox glue (selectors) and the Universal Interfaces declarations (arguments). */
	static final String TABLE=
		"0101 InitCM 2 -|0102 CMSetRefCon v 4,4|0103 CMGetRefCon 4 4|0104 CMSetUserData v 4,4|0105 CMGetUserData 4 4|0106 CMGetToolName v 2,4|0107 CMGetProcID "+
		"2 4|0108 CMNew 4 2,4,4,4,4|0109 CMDispose v 4|010a CMIdle v 4|010b CMOpen 2 4,B,4,4|010c CMListen 2 4,B,4,4|010d CMAccept 2 4,B|010e CMClose 2 4,B,4,4"+
		",B|010f CMAbort 2 4|0110 CMStatus 2 4,4,4|0111 CMRead 2 4,4,4,2,B,4,4,4|0112 CMWrite 2 4,4,4,2,B,4,4,H|0113 CMActivate v 4,B|0114 CMResume v 4,B|0115 "+
		"CMMenu B 4,2,2|0116 CMReset v 4|0117 CMValidate B 4|0118 CMDefault v 4,2,B|0119 CMSetupSetup v 2,4,2,4,4|011a CMSetupItem v 2,4,2,4,4,4|011b CMSetupCl"+
		"eanup v 2,4,2,4,4|011c CMGetConfig 4 4|011d CMSetConfig 2 4,4|011e CMIntlToEnglish 2 4,4,4,2|011f CMEnglishToIntl 2 4,4,4,2|0120 CMGetVersion 4 4|0121"+
		" CMGetCMVersion 2 -|0122 CMSetupFilter B 2,4,2,4,4,4,4|0123 CMSetupPreflight 4 2,4|0124 CMChoose 2 4,s4,4|0125 CMBreak v 4,4,B,4|0126 CMAddSearch 4 4,"+
		"4,H,4|0127 CMRemoveSearch v 4,4|0128 CMClearSearch v 4|0129 CMIOKill 2 4,2|012a CMEvent v 4,4|012b CMSetupPostflight v 2|012c CMGetConnEnvirons 2 4,4|"+
		"012e CMSetupXCleanup v 2,4,2,4,B,4|012f CMGetErrorString v 4,2,4|0130 CMNewIOPB 2 4,4|0131 CMDisposeIOPB 2 4,4|0132 CMPBRead 2 4,4,B|0133 CMPBWrite 2 "+
		"4,4,B|0134 CMPBIOKill 2 4,4|0201 InitFT 2 -|0202 FTSetRefCon v 4,4|0203 FTGetRefCon 4 4|0204 FTSetUserData v 4,4|0205 FTGetUserData 4 4|0206 FTGetTool"+
		"Name v 2,4|0207 FTGetProcID 2 4|0208 FTNew 4 2,4,4,4,4,4,4,4,4,4|0209 FTDispose v 4|020a FTExec v 4|020b FTStart 2 4,H,4|020d FTAbort 2 4|020e FTResum"+
		"e v 4,B|020f FTValidate B 4|0210 FTDefault v 4,2,B|0211 FTSetupPreflight 4 2,4|0212 FTSetupSetup v 2,4,2,4,4|0213 FTSetupFilter B 2,4,2,4,4,4,4|0214 F"+
		"TSetupItem v 2,4,2,4,4,4|0215 FTSetupCleanup v 2,4,2,4,4|0216 FTGetConfig 4 4|0217 FTSetConfig 2 4,4|0218 FTIntlToEnglish 2 4,4,4,2|0219 FTEnglishToIn"+
		"tl 2 4,4,4,2|021a FTGetVersion 4 4|021b FTGetFTVersion 2 -|021c FTChoose 2 4,s4,4|021d FTEvent v 4,4|021e FTSetupPostflight v 2|021f FTMenu B 4,2,2|02"+
		"20 FTActivate v 4,B|0222 FTSetupXCleanup v 2,4,2,4,B,4|0223 FTGetErrorString v 4,2,4|0224 FTSend 2 4,2,4,4|0225 FTReceive 2 4,4,4|0301 InitTM 2 -|0302"+
		" TMNew 4 4,4,4,2,4,4,4,4,4,4,4,4|0303 TMDispose v 4|0304 TMKey v 4,4|0305 TMUpdate v 4,4|0306 TMPaint v 4,4,4|0307 TMActivate v 4,B|0308 TMResume v 4,"+
		"B|0309 TMClick v 4,4|030a TMStream 4 4,4,4,H|030b TMMenu B 4,2,2|030c TMReset v 4|030d TMClear v 4|030e TMResize v 4,4|030f TMGetSelect 4 4,4,4|0310 T"+
		"MGetLine v 4,2,4|0311 TMSetSelection v 4,4,2|0312 TMScroll v 4,2,2|0313 TMIdle v 4|0314 TMValidate B 4|0315 TMDefault v 4,2,B|0316 TMSetupPreflight 4 "+
		"2,4|0317 TMSetupSetup v 2,4,2,4,4|0318 TMSetupFilter B 2,4,2,4,4,4,4|0319 TMSetupItem v 2,4,2,4,4,4|031a TMSetupCleanup v 2,4,2,4,4|031b TMGetConfig 4"+
		" 4|031c TMSetConfig 2 4,4|031d TMIntlToEnglish 2 4,4,4,2|031e TMEnglishToIntl 2 4,4,4,2|031f TMGetProcID 2 4|0320 TMGetToolName v 2,4|0321 TMSetRefCon"+
		" v 4,4|0322 TMGetRefCon 4 4|0323 TMSetUserData v 4,4|0324 TMGetUserData 4 4|0325 TMGetVersion 4 4|0326 TMGetTMVersion 2 -|0327 TMAddSearch 2 4,4,4,H,4"+
		"|0328 TMRemoveSearch v 4,2|0329 TMClearSearch v 4|032b TMGetTermEnvirons 2 4,4|032c TMChoose 2 4,s4,4|032d TMEvent v 4,4|032e TMDoTermKey B 4,4|032f T"+
		"MCountTermKeys 2 4|0330 TMGetIndTermKey v 4,2,4|0331 TMSetupPostflight v 2|0334 TMSetupXCleanup v 2,4,2,4,B,4|0335 TMGetErrorString v 4,2,4|0401 InitC"+
		"TBUtilities 2 -|0405 CTBGetCTBVersion 2 -|0406 StandardNBP 2 s4,4,2,4,4,4,4,4|0407 CustomNBP 2 s4,4,2,4,4,4,4,4,2,4,4|0501 InitCRM 2 -|0502 CRMGetHead"+
		"er 4 -|0503 CRMInstall v 4|0504 CRMRemove 2 4|0505 CRMSearch 4 4|0506 CRMGetCRMVersion 2 -|0507 CRMGetResource 4 4,2|0508 CRMGet1Resource 4 4,2|0509 C"+
		"RMGetIndResource 4 4,2|050a CRMGet1IndResource 4 4,2|050b CRMGetNamedResource 4 4,4|050c CRMGet1NamedResource 4 4,4|050d CRMReleaseResource v 4|050e C"+
		"RMGetIndex 4 4|050f CRMLocalToRealID 2 4,2,4,2|0510 CRMRealToLocalID 2 4,2,4,2|0511 CRMGetIndToolName 2 4,2,4|0513 CRMReserveRF 2 2|0514 CRMReleaseRF "+
		"2 2|051a CRMFindCommunications 2 4,4|0521 CRMIsDriverOpen B 4|0523 CRMGetToolResource 4 2,4,2|0524 CRMReleaseToolResource v 2,4|0525 CRMParseCAPSResou"+
		"rce 2 4,4,4|0526 CRMGetToolNamedResource 4 2,4,4|"+		"051f CRMToolPreflight v 4|0520 CRMToolPostflight v 4"; // private calls of the CTB 1.1 tools (no InterfaceLib entry)
	static final Map<Integer,String[]> BY_SELECTOR=new HashMap<Integer,String[]>();
	static final Map<String,Integer> SELECTORS=new HashMap<String,Integer>();
	static{for(String e:TABLE.split("\\|")){String[] p=e.trim().split(" ");if(p.length<4)continue;int s=Integer.parseInt(p[0],16);BY_SELECTOR.put(s,p);SELECTORS.put(p[1],s);}}
	/** The InterfaceLib glue of a CTB routine: its declaration with the selector pushed onto the arguments, A0 = SP, the trap. */
	static String glueLine(String declaration,int selector){
		String[] t=declaration.split(" ");if(t.length<6)return declaration;
		return t[0]+" "+t[1]+" "+t[2]+" "+t[3]+" "+(t[2].equals("v")?"-":"D0:")+" 3F3C,"+String.format("%04X",selector)+",204F,A08B";
	}

	/** One kind of tool and the Manager that runs it: the definition procedure types, the record size and messages. */
	private static final class Kind {
		final String prefix;final int bundle,main,val,set,scr,loc,recordSize,errorStringMsg;final String title;
		Kind(String prefix,int bundle,String types,int recordSize,int errorStringMsg,String title){this.prefix=prefix;this.bundle=bundle;
			main=ResourceFile68k.fourcc(types.substring(0,4));val=ResourceFile68k.fourcc(types.substring(4,8));set=ResourceFile68k.fourcc(types.substring(8,12));
			scr=ResourceFile68k.fourcc(types.substring(12,16));loc=ResourceFile68k.fourcc(types.substring(16,20));
			this.recordSize=recordSize;this.errorStringMsg=errorStringMsg;this.title=title;}
	}
	private static final Kind CM=new Kind("CM",CLASS_CM,"cdefcvalcsetcscrcloc",144,112,"Connection Settings");
	private static final Kind TM=new Kind("TM",CLASS_TM,"tdeftvaltsettscrtloc",106,8,"Terminal Settings");
	private static final Kind FT=new Kind("FT",CLASS_FT,"fdeffvalfsetfscrfloc",414,8,"File Transfer Settings");
	// the fields every record starts with (ConnRecord, TermRecord, FTRecord)
	private static final int PROC_ID=0,FLAGS=2,ERR_CODE=6,REF_CON=8,USER_DATA=12,DEF_PROC=16,CONFIG=20,OLD_CONFIG=24;
	private static final int CM_BUF_SIZES=76,TM_ENVIRONS=28,TM_PROCS=44,TM_OWNER=60,TM_TERM_RECT=64,TM_VIEW_RECT=72,TM_VIS_RECT=80,TM_LAST_IDLE=88,FT_ENVIRONS=28,FT_PROCS=44,FT_OWNER=60,FT_DIRECTION=64,FT_REPLY=66,FT_IS_FT_MODE=1,FT_SUCC=0x80;
	// messages every main definition procedure takes
	private static final int INIT=0,DISPOSE=1,SUSPEND=2,RESUME=3,MENU=4,EVENT=5,ACTIVATE=6,DEACTIVATE=7,IDLE=50,RESET=51,ABORT=52;

	private final Toolbox68k tb;private final M68kMemory memory;
	CommToolbox68k(Toolbox68k tb){this.tb=tb;this.memory=tb.memory;}

	// ------------------------------------------------------------------ tools

	private final class Tool {
		final Kind kind;final String name;final File file;int ref=-1;
		/** The tool's procID: the reference number of its resource file, as the CTB hands it out (tools use it as one -
		 *  Apple Modem Tool's setup does UseResFile(procID) for its LDEFs); -1 until the file is open. */
		int procID(){return ref;}
		/** The tool's bundle: (type, local ID) -> real ID. */
		final Map<Long,Integer> real=new HashMap<Long,Integer>(),local=new HashMap<Long,Integer>();
		Tool(Kind kind,String name,File file){this.kind=kind;this.name=name;this.file=file;}
	}
	private final List<Tool> tools=new ArrayList<Tool>();private boolean scanned;
	private static final Charset MAC_ROMAN=Charset.forName("x-MacRoman");
	private static Kind kindOf(int bundle){return bundle==CLASS_CM?CM:bundle==CLASS_TM?TM:bundle==CLASS_FT?FT:null;}
	/** The installed tools, found once: Extensions, then a Communications Folder, then the System Folder itself. */
	private void scan(){
		if(scanned)return;scanned=true;
		File system=tb.files==null?null:tb.files.systemFolder;if(system==null)return;
		List<File> found=new ArrayList<File>();
		for(File folder:new File[]{new File(system,"Extensions"),new File(system,"Communications Folder"),system}){
			File[] list=folder.listFiles();if(list==null)continue;
			for(File f:list)if(f.isFile()&&kindOf(FinderInfo68k.type(f))!=null)found.add(f);
		}
		found.sort((a,b)->a.getName().compareToIgnoreCase(b.getName()));
		// a tool is known by its Mac name: "Aladdin X/YMODEM Tool" is "Aladdin X:YMODEM Tool" on the host (SITcomm asks for it by name)
		for(File f:found){String name;try{name=tb.files.fileManager.macNameOf(f);}catch(IOException e){name=f.getName();}tools.add(new Tool(kindOf(FinderInfo68k.type(f)),name,f));}
		if(Mac68kApi.TRACE)for(Tool t:tools)System.err.println("[CTB] tool "+t.kind.prefix+" '"+t.name+"'");
	}
	private List<Tool> tools(Kind k){scan();List<Tool> l=new ArrayList<Tool>();for(Tool t:tools)if(t.kind==k)l.add(t);return l;}
	private Tool tool(int procID){scan();if(procID<=0)return null;for(Tool t:tools)if(t.ref==procID)return t;return null;}
	private Tool tool(Kind k,String name){for(Tool t:tools(k))if(t.name.equalsIgnoreCase(name))return t;return null;}
	private Tool toolOf(int record) throws M68kCpuException {int p=record==0?0:memory.readLong(record);return p==0?null:tool((short)memory.readWord(p+PROC_ID));}
	/** Opens the tool's file (below the application's, as the CTB keeps tool files) and reads its bundle. */
	private boolean open(Tool t) throws M68kCpuException {
		if(t.ref>=0)return true;
		t.ref=tb.resources.openFileNoChain(t.file);if(t.ref<0)return false;
		int h=tb.resources.indexedInFile(t.ref,t.kind.bundle,1);byte[] b=h==0?null:tb.api.handleContents(h);
		if(b!=null&&b.length>=2){int at=2,types=u2(b,0)+1;
			for(int i=0;i<types&&at+6<=b.length;i++){int type=u4(b,at),n=u2(b,at+4)+1;at+=6;
				for(int j=0;j<n&&at+4<=b.length;j++){int l=(short)u2(b,at),r=(short)u2(b,at+2);at+=4;t.real.put(key(type,l),r);t.local.put(key(type,r),l);}}}
		return true;
	}
	private static long key(int type,int id){return ((long)type<<16)|(id&0xffff);}
	private static int u2(byte[] b,int o){return (b[o]&0xff)<<8|(b[o+1]&0xff);}
	private static int u4(byte[] b,int o){return u2(b,o)<<16|u2(b,o+2);}
	/** The code of a definition procedure of the tool (its first resource of that type), locked. */
	private int defProc(Tool t,int type) throws M68kException {
		if(!open(t))return 0;
		int h=tb.resources.indexedInFile(t.ref,type,1);if(h==0)return 0;
		osTrap(0xa029,0,h);return memory.readLong(h); // HLock
	}
	/** Calls a definition procedure with the tool's file current; its LONGINT answer (cmNotSupported when the tool has none). */
	private int call(Tool t,int type,int first,int msg,int p1,int p2,int p3) throws M68kException {
		int code=t==null?0:defProc(t,type);if(code==0)return NOT_SUPPORTED;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int saved=tb.resources.currentRefNum();
		try{
			tb.resources.useResFile(t.ref);
			cpu.a[7]-=4;int slot=cpu.a[7];memory.writeLong(slot,0);
			cpu.pushLong(first);cpu.pushWord(msg);cpu.pushLong(p1);cpu.pushLong(p2);cpu.pushLong(p3);
			cpu.callPascal(code,18);
			int result=memory.readLong(slot);
			if(Mac68kApi.TRACE)System.err.printf("[CTB] %s '%s' msg %d (%08X %08X %08X) -> %08X%n",typeName(type),t.name,msg,p1,p2,p3,result);
			return result;
		}finally{tb.resources.useResFile(saved);System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	private static String typeName(int t){return new String(new char[]{(char)(t>>>24),(char)(t>>>16&0xff),(char)(t>>>8&0xff),(char)(t&0xff)});}

	// ------------------------------------------------------------------ guest calls

	/** An OS trap with D0/A0 in, {D0, A0} out; the caller's registers are left as they were. */
	private int[] osTrap(int trap,int d0,int a0) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{cpu.d[0]=d0;cpu.a[0]=a0;tb.api.handle(cpu,trap,cpu.pc);return new int[]{cpu.d[0],cpu.a[0]};}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	/** A stack-based Toolbox trap: Pascal arguments ({@code sizes}: "2", "4" or "B" each) under a result slot of
	 *  {@code resultBytes} (0, 1 = Boolean, 2 or 4); returns the result. */
	private int tbTrap(int trap,int resultBytes,String sizes,int... args) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{
			int slot=0;if(resultBytes>0){cpu.a[7]-=resultBytes==4?4:2;slot=cpu.a[7];memory.fill(slot,resultBytes==4?4:2,0);}
			String[] s=sizes.isEmpty()?new String[0]:sizes.split(",");
			for(int i=0;i<s.length;i++){if(s[i].equals("4")){cpu.a[7]-=4;memory.writeLong(cpu.a[7],args[i]);}else{cpu.a[7]-=2;memory.writeWord(cpu.a[7],s[i].equals("B")?(args[i]!=0?0x100:0):args[i]);}}
			tb.api.handle(cpu,trap,cpu.pc);
			return resultBytes==4?memory.readLong(slot):resultBytes==2?(short)memory.readWord(slot):resultBytes==1?memory.readByte(slot):0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	/** An Icon Utilities call (_IconDispatch, the selector in D0): Pascal arguments under an OSErr; returns it. */
	private int iconTrap(int selector,String sizes,int... args) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{cpu.a[7]-=2;int slot=cpu.a[7];memory.writeWord(slot,0);String[] s=sizes.split(",");
			for(int i=0;i<s.length;i++){cpu.a[7]-=4;memory.writeLong(cpu.a[7],args[i]);}
			cpu.d[0]=selector;tb.api.handle(cpu,0xabc9,cpu.pc);return (short)memory.readWord(slot);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	private int newPtrClear(int size) throws M68kException {return osTrap(0xa31e,size,0)[1];}
	private void disposePtr(int p) throws M68kException {if(p!=0)osTrap(0xa01f,0,p);}
	private int ptrSize(int p) throws M68kException {return p==0?0:Math.max(0,osTrap(0xa021,0,p)[0]);}
	private int newHandleClear(int size) throws M68kException {return osTrap(0xa322,size,0)[1];}
	private void disposeHandle(int h) throws M68kException {if(h!=0)osTrap(0xa023,0,h);}
	private int copyPtr(int p) throws M68kException {int n=ptrSize(p);if(p==0)return 0;int c=newPtrClear(Math.max(1,n));if(c!=0&&n>0)memory.copy(p,c,n);return c;}
	private int scratch(int size) throws M68kCpuException {int p=memory.allocate(size,2);memory.fill(p,size,0);return p;}
	private String readPString(int p) throws M68kCpuException {if(p==0)return "";int n=memory.readByte(p);return new String(memory.readBytes(p+1,n),MAC_ROMAN);}
	private void writePString(int p,String s) throws M68kCpuException {if(p==0)return;byte[] b=s.getBytes(MAC_ROMAN);int n=Math.min(255,b.length);memory.writeByte(p,n);memory.load(p+1,java.util.Arrays.copyOf(b,n));}
	private int record(int h) throws M68kCpuException {return h==0?0:memory.readLong(h);}

	// ------------------------------------------------------------------ dispatch

	/** _CommToolboxDispatch for the selectors in {@link #TABLE}; false for others. */
	boolean dispatch(M68kCpu cpu) throws M68kException {
		int a0=cpu.a[0];int selector=memory.readWord(a0);
		if(selector==FILTER_SELECTOR){cpu.d[0]=chooseFilter(memory.readLong(a0+14),memory.readLong(a0+10),memory.readLong(a0+6))?1:0;return true;}
		String[] e=BY_SELECTOR.get(selector);if(e==null)return false;
		String[] sizes=e[3].equals("-")?new String[0]:e[3].split(",");
		int[] p=new int[sizes.length];int at=a0+2;
		for(int i=sizes.length-1;i>=0;i--){String k=sizes[i];
			if(k.equals("4")||k.equals("s4")){p[i]=memory.readLong(at);at+=4;}
			else{p[i]=k.equals("B")?memory.readByte(at):k.equals("H")?memory.readWord(at):(short)memory.readWord(at);at+=2;}}
		if(Mac68kApi.TRACE&&!e[1].endsWith("Idle")&&!e[1].endsWith("Status")){StringBuilder sb=new StringBuilder();for(int v:p)sb.append(String.format(" %X",v));System.err.println("[CTB] > "+e[1]+sb);}
		int r=run(e[1],p);
		if(e[2].equals("B"))r=r!=0?1:0;else if(e[2].equals("2"))r=(short)r;
		if(Mac68kApi.TRACE){StringBuilder sb=new StringBuilder();for(int v:p)sb.append(String.format(" %X",v));System.err.println("[CTB] "+e[1]+sb+(e[2].equals("v")?"":" -> "+r));}
		cpu.d[0]=r;
		return true;
	}

	private int run(String name,int[] p) throws M68kException {
		switch(name){
			case "InitCTBUtilities":return 0;
			case "CTBGetCTBVersion":return 2;
			case "StandardNBP":case "CustomNBP":return 1; // nlCancel: no AppleTalk lookup dialog
			default:
		}
		if(name.startsWith("CRM")||name.equals("InitCRM"))return crm(name,p);
		Kind k=name.startsWith("CM")||name.equals("InitCM")?CM:name.startsWith("TM")||name.equals("InitTM")?TM:FT;
		String op=name.startsWith("Init")?"Init":name.substring(2);
		int h=p.length>0?p[0]:0;
		switch(op){
			case "Init":scan();return 0;
			case "GetCMVersion":case "GetTMVersion":case "GetFTVersion":return 2;
			case "SetRefCon":memory.writeLong(record(h)+REF_CON,p[1]);return 0;
			case "GetRefCon":return memory.readLong(record(h)+REF_CON);
			case "SetUserData":memory.writeLong(record(h)+USER_DATA,p[1]);return 0;
			case "GetUserData":return memory.readLong(record(h)+USER_DATA);
			case "GetToolName":{Tool t=tool(p[0]);writePString(p[1],t==null||t.kind!=k?"":t.name);return 0;}
			case "GetProcID":{Tool t=tool(k,readPString(p[0]));return t==null||!open(t)?-1:t.procID();}
			case "GetVersion":{Tool t=toolOf(h);if(t==null||!open(t))return 0;int v=tb.resources.indexedInFile(t.ref,0x76657273,1);byte[] b=v==0?null:tb.api.handleContents(v);return b==null?0:tb.api.createHandle(b);}
			case "Dispose":dispose(k,h);return 0;
			case "Idle":if(k==TM)memory.writeLong(record(h)+TM_LAST_IDLE,tb.tickCount());return main(k,h,IDLE,0,0,0);
			case "Activate":return main(k,h,p[1]!=0?ACTIVATE:DEACTIVATE,0,0,0);
			case "Resume":return main(k,h,p[1]!=0?RESUME:SUSPEND,0,0,0);
			case "Menu":return main(k,h,MENU,p[1],p[2],0);
			case "Event":return main(k,h,EVENT,p[1],0,0);
			case "Reset":return main(k,h,RESET,0,0,0);
			case "Abort":return main(k,h,ABORT,0,0,0);
			case "GetErrorString":{writePString(p[2],"");return main(k,h,k.errorStringMsg,p[1],p[2],0);}
			case "Validate":{Tool t=toolOf(h);return t==null?0:call(t,k.val,h,0,0,0,0);}
			case "Default":{Tool t=tool(p[1]);return t==null?0:call(t,k.val,0,1,p[0],p[2],p[1]);}
			case "GetConfig":{Tool t=toolOf(h);int r=t==null?0:call(t,k.scr,h,0,0,0,0);return r==NOT_SUPPORTED?0:r;}
			case "SetConfig":{Tool t=toolOf(h);if(t==null)return -1;int r=call(t,k.scr,h,1,p[1],0,0);call(t,k.val,h,0,0,0,0);return r;}
			case "IntlToEnglish":case "EnglishToIntl":{Tool t=toolOf(h);if(p[2]!=0)memory.writeLong(p[2],0);return t==null?-1:call(t,k.loc,h,op.equals("IntlToEnglish")?0:1,p[1],p[2],p[3]);}
			case "SetupPreflight":{Tool t=tool(p[0]);if(t==null||!open(t))return 0;int s=setupStruct(0,0,0,p[0]);try{return call(t,k.set,s,0,0,0,p[1]);}finally{memory.free(s);}}
			case "SetupSetup":return setup(k,p[0],p[1],p[2],p[3],1,0,0,p[4]);
			case "SetupItem":return setup(k,p[0],p[1],p[2],p[3],2,p[4],0,p[5]);
			case "SetupFilter":return setup(k,p[0],p[1],p[2],p[3],3,p[4],p[5],p[6]);
			case "SetupCleanup":return setup(k,p[0],p[1],p[2],p[3],4,0,0,p[4]);
			case "SetupXCleanup":return setup(k,p[0],p[1],p[2],p[3],4,p[4],0,p[5]);
			case "SetupPostflight":return 0;
			case "Choose":return choose(k,p[0],p[1]);
			case "AddSearch":return ++searches; // searches in the data stream are never matched: nothing arrives
			case "RemoveSearch":case "ClearSearch":return 0;
			default:
		}
		if(k==CM)return connection(op,h,p);
		if(k==TM)return terminal(op,h,p);
		return fileTransfer(op,h,p);
	}
	private int searches;

	private int main(Kind k,int h,int msg,int p1,int p2,int p3) throws M68kException {Tool t=toolOf(h);return t==null?NOT_SUPPORTED:call(t,k.main,h,msg,p1,p2,p3);}
	/** CMSetupStruct/TMSetupStruct/FTSetupStruct: theDialog, count (the first appended item), theConfig, procID. */
	private int setupStruct(int dialog,int count,int config,int procID) throws M68kCpuException {
		int s=scratch(12);memory.writeLong(s,dialog);memory.writeWord(s+4,count);memory.writeLong(s+6,config);memory.writeWord(s+10,procID);return s;
	}
	private int setup(Kind k,int procID,int config,int count,int dialog,int msg,int p1,int p2,int cookie) throws M68kException {
		Tool t=tool(procID);if(t==null)return 0;int s=setupStruct(dialog,count,config,procID);
		try{return call(t,k.set,s,msg,p1,p2,cookie);}finally{memory.free(s);}
	}

	/** CMNew/TMNew/FTNew: the record, the tool's default configuration (its validation procedure allocates it), cmInitMsg, then
	 *  validation; 0 when the tool is missing or refuses to start. {@code fill} sets the Manager's own fields. */
	private interface Fill{void fill(int p) throws M68kException;}
	private int create(Kind k,int procID,int flags,int refCon,int userData,Fill fill) throws M68kException {
		Tool t=tool(procID);if(t==null||t.kind!=k||!open(t))return 0;
		int code=defProc(t,k.main);if(code==0)return 0;
		int h=newHandleClear(k.recordSize);if(h==0)return 0;osTrap(0xa029,0,h);int p=memory.readLong(h);
		memory.writeWord(p+PROC_ID,procID);memory.writeLong(p+FLAGS,flags);memory.writeLong(p+REF_CON,refCon);memory.writeLong(p+USER_DATA,userData);memory.writeLong(p+DEF_PROC,code);
		if(fill!=null)fill.fill(p);
		int var=scratch(4);
		try{call(t,k.val,h,1,var,1,procID);int config=memory.readLong(var);memory.writeLong(p+CONFIG,config);memory.writeLong(p+OLD_CONFIG,copyPtr(config));}
		finally{memory.free(var);}
		int err=call(t,k.main,h,INIT,0,0,0);
		if(err!=0){disposePtr(memory.readLong(p+CONFIG));disposePtr(memory.readLong(p+OLD_CONFIG));disposeHandle(h);return 0;}
		call(t,k.val,h,0,0,0,0);
		// a terminal tool sets up its screen (font metrics, rows and columns) on tmResetMsg: VT102 Tool divides by a cell
		// height it computes only there, and its first tmResizeMsg divided by zero
		if(k==TM)call(t,k.main,h,RESET,0,0,0);
		return h;
	}
	private void dispose(Kind k,int h) throws M68kException {
		int p=record(h);if(p==0)return;
		main(k,h,DISPOSE,0,0,0);
		disposePtr(memory.readLong(p+CONFIG));disposePtr(memory.readLong(p+OLD_CONFIG));disposeHandle(h);
	}

	// ------------------------------------------------------------------ Connection Manager

	private int connection(String op,int h,int[] p) throws M68kException {
		switch(op){
			case "New":{final int sizes=p[2];return create(CM,p[0],p[1],p[3],p[4],r->{if(sizes!=0)memory.copy(sizes,r+CM_BUF_SIZES,32);});}
			case "Open":case "Listen":{int c=completor(p[1],p[2]);try{return main(CM,h,op.equals("Open")?106:103,c,p[3],0);}finally{memory.free(c);}}
			case "Accept":return main(CM,h,104,p[1],0,0);
			// Apple's Serial Tool (its message dispatcher and routines) takes cmCloseMsg as p1 = completor, p2 = now (a Boolean),
			// p3 = timeout, and cmReadMsg/cmWriteMsg as p1 = the data buffer, p2 = timeout, p3 = completor - the CTB reference's
			// text has the last two swapped; the completor Ptr as the timeout made a read with timeout 0 wait for hours (XMODEM)
			case "Close":{int c=completor(p[1],p[2]);try{return main(CM,h,105,c,p[4],p[3]);}finally{memory.free(c);}}
			case "Status":return main(CM,h,102,p[1],p[2],0);
			case "Read":case "Write":{ // CMRead(hConn, theBuffer, VAR toRead, channel, async, completor, timeout, VAR flags); CMWrite's flags by value
				boolean read=op.equals("Read");int db=scratch(12),c=completor(p[4],p[5]);
				try{memory.writeLong(db,p[1]);memory.writeLong(db+4,p[2]==0?0:memory.readLong(p[2]));memory.writeWord(db+8,p[3]);memory.writeWord(db+10,read?0:p[7]);
					int r=main(CM,h,read?100:101,db,p[6],c);
					if(p[2]!=0)memory.writeLong(p[2],memory.readLong(db+4));if(read&&p[7]!=0)memory.writeWord(p[7],memory.readWord(db+10));
					return r;}
				finally{memory.free(db);memory.free(c);}}
			case "IOKill":return main(CM,h,108,p[1],0,0);
			case "Break":{int c=completor(p[2],p[3]);try{return main(CM,h,107,p[1],c,0);}finally{memory.free(c);}}
			case "GetConnEnvirons":return main(CM,h,109,p[1],0,0);
			default:return NOT_SUPPORTED; // the CTB 1.1 parameter-block calls
		}
	}
	/** CMCompletorRecord: async (a byte, then a filler) and the completion routine. */
	private int completor(int async,int routine) throws M68kCpuException {int c=scratch(6);memory.writeByte(c,async!=0?1:0);memory.writeLong(c+2,routine);return c;}

	// ------------------------------------------------------------------ Terminal Manager

	private int terminal(String op,int h,int[] p) throws M68kException {
		switch(op){
			case "New":return create(TM,p[3],p[2],p[10],p[11],r->{ // TMNew(termRect, viewRect, flags, procID, owner, sendProc, cacheProc, breakProc, clikLoop, environsProc, refCon, userData)
				if(p[0]!=0)memory.copy(p[0],r+TM_TERM_RECT,8);if(p[1]!=0){memory.copy(p[1],r+TM_VIEW_RECT,8);memory.copy(p[1],r+TM_VIS_RECT,8);}
				memory.writeLong(r+TM_OWNER,p[4]);memory.writeLong(r+TM_PROCS,p[5]);memory.writeLong(r+TM_PROCS+4,p[7]);memory.writeLong(r+TM_PROCS+8,p[6]);memory.writeLong(r+TM_PROCS+12,p[8]);
				memory.writeLong(r+TM_ENVIRONS,p[9]);memory.writeLong(r+TM_LAST_IDLE,tb.tickCount());});
			case "Key":return main(TM,h,100,p[1],0,0);
			case "Stream":return main(TM,h,101,p[1],p[2],p[3]);
			case "Resize":{int r=record(h);if(r!=0&&p[1]!=0)memory.copy(p[1],r+TM_VIEW_RECT,8);return main(TM,h,102,p[1],0,0);}
			case "Update":return main(TM,h,103,p[1],0,0);
			case "Click":return main(TM,h,104,p[1],0,0);
			case "GetSelect":return main(TM,h,105,p[1],p[2],0);
			case "SetSelection":return main(TM,h,106,p[1],p[2],0);
			case "Scroll":return main(TM,h,107,p[1],p[2],0);
			case "Clear":return main(TM,h,108,0,0,0);
			case "GetLine":return main(TM,h,109,p[1],p[2],0);
			case "Paint":return main(TM,h,110,p[1],p[2],0);
			case "GetTermEnvirons":return main(TM,h,112,p[1],0,0);
			case "DoTermKey":return main(TM,h,113,p[1],0,0);
			case "CountTermKeys":return main(TM,h,114,0,0,0);
			case "GetIndTermKey":return main(TM,h,115,p[1],p[2],0);
			default:return NOT_SUPPORTED;
		}
	}

	// ------------------------------------------------------------------ File Transfer Manager

	private int fileTransfer(String op,int h,int[] p) throws M68kException {
		switch(op){
			case "New":return create(FT,p[0],p[1],p[8],p[9],r->{ // FTNew(procID, flags, sendProc, recvProc, readProc, writeProc, environsProc, owner, refCon, userData)
				memory.writeLong(r+FT_PROCS,p[2]);memory.writeLong(r+FT_PROCS+4,p[3]);memory.writeLong(r+FT_PROCS+8,p[5]);memory.writeLong(r+FT_PROCS+12,p[4]);
				memory.writeLong(r+FT_ENVIRONS,p[6]);memory.writeLong(r+FT_OWNER,p[7]);});
			case "Start":{ // FTStart(hFT, direction, fileInfo): the manager stores the direction and the SFReply in the record; ftStartMsg has no parameters.
				// When the tool accepts, the manager turns ftIsFTMode on (and ftSucc off): Apple's XMODEM Tool leaves the flag to it (and
				// rejects a start while the flag is on), the application calls FTExec only while it is on, and the tool turns it off at the end
				int r=record(h);if(r!=0){memory.writeWord(r+FT_DIRECTION,p[1]);if(p[2]!=0)memory.copy(p[2],r+FT_REPLY,74);}
				int err=main(FT,h,100,0,0,0);
				r=record(h);if(err==0&&r!=0)memory.writeLong(r+FLAGS,memory.readLong(r+FLAGS)&~FT_SUCC|FT_IS_FT_MODE);
				return err;}
			case "Exec":return main(FT,h,102,0,0,0);
			case "Send":return main(FT,h,103,p[1],p[2],p[3]);
			case "Receive":return main(FT,h,104,p[1],p[2],0);
			default:return NOT_SUPPORTED;
		}
	}

	// ------------------------------------------------------------------ the standard tool-settings dialog (CMChoose, TMChoose, FTChoose)

	/**
	 * The tool-settings dialog: the tools of the kind as radio buttons, the chosen tool's own items appended below them (its setup
	 * procedure fills and answers them), OK and Cancel. OK with the same tool takes the edited configuration (chooseOKMinor);
	 * another tool replaces the record by a new one of that tool with the same settings (chooseOKMajor).
	 */
	private int choose(Kind k,int hVar,int where) throws M68kException {
		int h=hVar==0?0:memory.readLong(hVar);int rec=record(h);if(rec==0)return CHOOSE_FAILED;
		List<Tool> list=tools(k);Tool current=toolOf(h);if(list.isEmpty()||current==null)return CHOOSE_FAILED;
		int width=380,top=(short)(where>>>16),left=(short)where;if(top<40)top=60;if(left<4)left=40;
		// items: 1 OK, 2 Cancel, 3 the label, 4.. one radio button per tool
		java.io.ByteArrayOutputStream ditl=new java.io.ByteArrayOutputStream();int count=3+list.size();
		ditl.write(0);ditl.write(count-1);
		item(ditl,10,290,30,360,4,"OK");item(ditl,40,290,60,360,4,"Cancel");
		item(ditl,12,6,28,95,8,k==CM?"Connection:":k==TM?"Terminal:":"File Transfer:");
		for(int i=0;i<list.size();i++)item(ditl,10+20*i,96,28+20*i,280,6,list.get(i).name);
		int base=count,baseHeight=Math.max(70,20+20*list.size()+10);
		int items=tb.api.createHandle(ditl.toByteArray()),bounds=scratch(8),title=scratch(256),itemHit=scratch(2),cookie=scratch(4);
		memory.writeWord(bounds,top);memory.writeWord(bounds+2,left);memory.writeWord(bounds+4,top+baseHeight);memory.writeWord(bounds+6,left+width);writePString(title,k.title);
		int dialog=tbTrap(0xa97d,4,"4,4,4,B,2,4,B,4,4",0,bounds,title,0,1,-1,0,0,items); // NewDialog, hidden until the tool's items are in
		int result=CHOOSE_CANCEL;int savedResFile=tb.resources.currentRefNum();
		Tool chosen=current;int config=copyPtr(memory.readLong(rec+CONFIG));
		try{
			if(dialog==0)return CHOOSE_FAILED;
			int first=attach(k,dialog,chosen,config,cookie,base);
			select(dialog,list,chosen);
			tbTrap(0xa915,0,"4",dialog); // ShowWindow
			for(;;){
				chooseKind=k;chooseTool=chosen;chooseConfig=config;chooseFirst=first;chooseCookie=cookie;
				tbTrap(0xa991,0,"4,4",filterStub(),itemHit); // ModalDialog(filter, itemHit): the tool's setup procedure sees every event first
				int hit=(short)memory.readWord(itemHit);
				if(hit==1){result=chosen==current?CHOOSE_OK_MINOR:CHOOSE_OK_MAJOR;setup(k,chosen.procID(),config,first,dialog,4,1,0,cookie);break;}
				if(hit==2){setup(k,chosen.procID(),config,first,dialog,4,0,0,cookie);break;}
				if(hit>3&&hit<=base){Tool t=list.get(hit-4);if(t==chosen)continue;
					setup(k,chosen.procID(),config,first,dialog,4,0,0,cookie);
					tb.dialogs.shortenDITL(dialog,tb.dialogs.countDITL(dialog)-base);tbTrap(0xa91d,0,"4,2,2,B",dialog,width,baseHeight,1); // SizeWindow back
					disposePtr(config);chosen=t;config=defaultConfig(k,t);
					first=attach(k,dialog,chosen,config,cookie,base);select(dialog,list,chosen);
					// the old tool's drawing goes, and the whole dialog is drawn again (the new tool draws its lists from the update)
					int r=scratch(8);try{memory.copy(dialog+16,r,8);tbTrap(0xa873,0,"4",dialog);tbTrap(0xa8a3,0,"4",r);tbTrap(0xa981,0,"4",dialog);tbTrap(0xa928,0,"4",r);}finally{memory.free(r);} // SetPort, EraseRect(portRect), DrawDialog, InvalRect
					continue;}
				if(hit>base){memory.writeWord(itemHit,hit);setup(k,chosen.procID(),config,first,dialog,2,itemHit,0,cookie);}
			}
		}finally{
			if(dialog!=0)tbTrap(0xa983,0,"4",dialog); // DisposeDialog
			tb.resources.useResFile(savedResFile);chooseTool=null;
			memory.free(bounds);memory.free(title);memory.free(itemHit);memory.free(cookie);
		}
		if(result==CHOOSE_OK_MINOR){copyInto(config,memory.readLong(rec+CONFIG));call(current,k.val,h,0,0,0,0);}
		else if(result==CHOOSE_OK_MAJOR){
			int fresh=recreate(k,h,rec,chosen);
			if(fresh==0)result=CHOOSE_FAILED;
			else{copyInto(config,memory.readLong(record(fresh)+CONFIG));call(chosen,k.val,fresh,0,0,0,0);dispose(k,h);memory.writeLong(hVar,fresh);}
		}
		disposePtr(config);
		return result;
	}
	/** The tool-settings dialog's filter, a 68K stub calling back here through a private selector: pascal Boolean
	 *  filter(DialogPtr, EventRecord*, short *itemHit) - move.w #FILTER_SELECTOR,-(sp); movea.l sp,a0; _CommToolboxDispatch;
	 *  addq.w #2,sp; movea.l (sp)+,a0; lea 12(sp),sp; move.b d0,(sp); jmp (a0). */
	private static final int FILTER_SELECTOR=0x05f0;private int filterStub;
	private Kind chooseKind;private Tool chooseTool;private int chooseConfig,chooseFirst,chooseCookie;
	private int filterStub() throws M68kCpuException {
		if(filterStub!=0)return filterStub;
		int[] w={0x3f3c,FILTER_SELECTOR,0x204f,0xa08b,0x544f,0x205f,0x4fef,0x000c,0x1e80,0x4ed0};
		filterStub=memory.allocate(w.length*2,2);for(int i=0;i<w.length;i++)memory.writeWord(filterStub+2*i,w[i]);return filterStub;
	}
	/** cmSfilterMsg (and the others' 3) for each event of the dialog; Return and Enter choose OK, Esc and Command-period Cancel
	 *  (as in every modal dialog of the System) when the tool leaves them: CTBChoose with Esc waited for a click. */
	private boolean chooseFilter(int dialog,int event,int itemHit) throws M68kException {
		if(chooseTool==null||event==0)return false;
		if(setup(chooseKind,chooseTool.procID(),chooseConfig,chooseFirst,dialog,3,event,itemHit,chooseCookie)!=0)return true;
		int what=memory.readWord(event),message=memory.readLong(event+2),ch=message&0xff,modifiers=memory.readWord(event+14);
		if(what!=3&&what!=5)return false;
		if(ch==0x0d||ch==0x03){memory.writeWord(itemHit,1);return true;}
		if(ch==0x1b||((message>>>8)&0x7f)==0x35||(modifiers&0x100)!=0&&ch=='.'){memory.writeWord(itemHit,2);return true;} // Esc key ($35), Command-.
		return false;
	}
	/** The tool's setup items appended below the radio buttons and set up from {@code config}; the number of the first one. */
	private int attach(Kind k,int dialog,Tool t,int config,int cookie,int base) throws M68kException {
		memory.writeLong(cookie,0);
		int s=setupStruct(0,0,0,t.procID());int ditl;try{ditl=open(t)?call(t,k.set,s,0,0,0,cookie):0;}finally{memory.free(s);}
		int first=base+1;
		// the tool's file is current while the dialog is up: its resource controls (CNTL), menus and pictures come from there
		if(t.ref>=0)tb.resources.useResFile(t.ref);
		if(ditl!=0&&ditl!=NOT_SUPPORTED){tb.dialogs.appendDITL(dialog,ditl,2);setup(k,t.procID(),config,first,dialog,1,0,0,cookie);}
		return first;
	}
	private void select(int dialog,List<Tool> list,Tool chosen) throws M68kException {
		int type=scratch(2),handle=scratch(4),box=scratch(8);
		try{for(int i=0;i<list.size();i++){tbTrap(0xa98d,0,"4,2,4,4,4",dialog,4+i,type,handle,box);int c=memory.readLong(handle);if(c!=0)tbTrap(0xa963,0,"4,2",c,list.get(i)==chosen?1:0);}}
		finally{memory.free(type);memory.free(handle);memory.free(box);}
	}
	private int defaultConfig(Kind k,Tool t) throws M68kException {int var=scratch(4);try{call(t,k.val,0,1,var,1,t.procID());return memory.readLong(var);}finally{memory.free(var);}}
	private void copyInto(int from,int to) throws M68kException {if(from==0||to==0)return;int n=Math.min(ptrSize(from),ptrSize(to));if(n>0)memory.copy(from,to,n);}
	/** A record of another tool with the settings of the old one (the Manager's own fields copied over). */
	private int recreate(Kind k,int h,int rec,Tool t) throws M68kException {
		int flags=memory.readLong(rec+FLAGS),refCon=memory.readLong(rec+REF_CON),userData=memory.readLong(rec+USER_DATA);
		if(k==CM)return create(CM,t.procID(),flags,refCon,userData,r->memory.copy(rec+CM_BUF_SIZES,r+CM_BUF_SIZES,32));
		if(k==TM)return create(TM,t.procID(),flags,refCon,userData,r->{memory.copy(rec+TM_ENVIRONS,r+TM_ENVIRONS,4);memory.copy(rec+TM_PROCS,r+TM_PROCS,TM_LAST_IDLE-TM_PROCS);});
		return create(FT,t.procID(),flags,refCon,userData,r->{memory.copy(rec+FT_ENVIRONS,r+FT_ENVIRONS,4);memory.copy(rec+FT_PROCS,r+FT_PROCS,FT_OWNER+4-FT_PROCS);});
	}
	private static void item(java.io.ByteArrayOutputStream out,int top,int left,int bottom,int right,int type,String text){
		byte[] t=text.getBytes(MAC_ROMAN);
		for(int b:new int[]{0,0,0,0,top>>8,top,left>>8,left,bottom>>8,bottom,right>>8,right,type,t.length})out.write(b);
		out.write(t,0,t.length);if((t.length&1)!=0)out.write(0);
	}

	// ------------------------------------------------------------------ Communications Resource Manager

	private int crmHeader,nextDeviceID=1;private final List<Integer> devices=new ArrayList<Integer>();
	private int crm(String name,int[] p) throws M68kException {
		switch(name){
			case "InitCRM":initCRM();return 0;
			case "CRMGetCRMVersion":return 2;
			case "CRMGetHeader":initCRM();return crmHeader;
			case "CRMInstall":initCRM();install(p[0]);return 0;
			case "CRMRemove":devices.remove((Integer)p[0]);relink();return 0;
			case "CRMSearch":{initCRM();if(p[0]==0)return 0;int type=memory.readLong(p[0]+14),id=memory.readLong(p[0]+18);
				for(int d:devices)if(memory.readLong(d+14)==type&&memory.readLong(d+18)>id)return d;return 0;}
			case "CRMGetResource":return tbTrap(0xa9a0,4,"4,2",p[0],p[1]);
			case "CRMGet1Resource":return tbTrap(0xa81f,4,"4,2",p[0],p[1]);
			case "CRMGetIndResource":return tbTrap(0xa99d,4,"4,2",p[0],p[1]);
			case "CRMGet1IndResource":return tbTrap(0xa80e,4,"4,2",p[0],p[1]);
			case "CRMGetNamedResource":return tbTrap(0xa9a1,4,"4,4",p[0],p[1]);
			case "CRMGet1NamedResource":return tbTrap(0xa820,4,"4,4",p[0],p[1]);
			case "CRMReleaseResource":tbTrap(0xa9a3,0,"4",p[0]);return 0;
			case "CRMGetIndex":return 0;
			case "CRMGetToolResource":case "CRMGetToolNamedResource":{Tool t=tool(p[0]);if(t==null||!open(t))return 0;int saved=tb.resources.currentRefNum();
				try{tb.resources.useResFile(t.ref);return name.equals("CRMGetToolResource")?tbTrap(0xa81f,4,"4,2",p[1],p[2]):tbTrap(0xa820,4,"4,4",p[1],p[2]);}
				finally{tb.resources.useResFile(saved);}}
			case "CRMReleaseToolResource":tbTrap(0xa9a3,0,"4",p[1]);return 0;
			case "CRMLocalToRealID":case "CRMRealToLocalID":{Tool t=tool(p[1]);if(t==null||!open(t))return -1;
				Integer v=(name.equals("CRMLocalToRealID")?t.real:t.local).get(key(p[2],p[3]));return v==null?-1:v;}
			case "CRMGetIndToolName":{List<Tool> l=tools(kindOf(p[0])==null?CM:kindOf(p[0]));int i=p[1];writePString(p[2],i>=1&&i<=l.size()?l.get(i-1).name:"");return 0;}
			case "CRMFindCommunications":return -1;
			case "CRMIsDriverOpen":return 0;
			case "CRMParseCAPSResource":{byte[] b=p[0]==0?null:tb.api.handleContents(p[0]);if(b==null||b.length<2)return -1;
				for(int i=0,at=2,n=u2(b,0);i<n&&at+8<=b.length;i++,at+=8)if(u4(b,at)==p[1]){if(p[2]!=0)memory.writeLong(p[2],u4(b,at+4));return 0;}
				return -1;}
			case "CRMReserveRF":case "CRMReleaseRF":return 0;
			// a tool saves the current resource file around its own work (it has just made its file current): the context's
			// first word keeps the file to go back to
			case "CRMToolPreflight":if(p[0]!=0)memory.writeWord(p[0],tb.resources.currentRefNum());return 0;
			case "CRMToolPostflight":if(p[0]!=0)tb.resources.useResFile((short)memory.readWord(p[0]));return 0;
			default:return -1;
		}
	}
	/** The CRM queue with the two serial ports in it (crmSerialDevice records: driver names, port name, a blank icon). */
	private void initCRM() throws M68kException {
		if(crmHeader!=0)return;
		crmHeader=newPtrClear(10);
		serialPort("Modem Port",".AIn",".AOut");serialPort("Printer Port",".BIn",".BOut");
	}
	private void serialPort(String name,String in,String out) throws M68kException {
		int serial=newPtrClear(30);memory.writeWord(serial,1); // curCRMSerRecVers
		memory.writeLong(serial+2,stringHandle(in));memory.writeLong(serial+6,stringHandle(out));memory.writeLong(serial+10,stringHandle(name));
		byte[] icon=portIcon(name.startsWith("Modem"));int iconRecord=tb.api.createHandle(icon); // CRMIconRecord: ICN#, mask, then an icon suite holding the same ICN#
		int suiteVar=scratch(4);
		try{if(iconTrap(0x0207,"4",suiteVar)==0){int suite=memory.readLong(suiteVar);iconTrap(0x0608,"4,4,4",tb.api.createHandle(java.util.Arrays.copyOf(icon,256)),suite,0x49434e23);memory.writeLong(memory.readLong(iconRecord)+256,suite);}}
		finally{memory.free(suiteVar);}
		memory.writeLong(serial+14,iconRecord); // the tools take the port's icon from the suite (Apple Modem Tool's port list)
		memory.writeLong(serial+18,57600);memory.writeLong(serial+22,57600);
		int rec=newPtrClear(34);memory.writeWord(rec+4,9);memory.writeWord(rec+6,1);memory.writeLong(rec+14,1);memory.writeLong(rec+22,serial); // qType crmType, crmRecVersion, crmSerialDevice
		install(rec);
	}
	/** A CRMIconRecord with a plain drawing of a telephone (modem port) or a printer: the 1-bit icon, its mask, no suite. */
	private static byte[] portIcon(boolean modem){
		boolean[][] ink=new boolean[32][32],fill=new boolean[32][32];
		if(modem){box(ink,fill,4,14,28,27);box(ink,fill,2,6,30,11);box(ink,fill,11,11,21,14);
			for(int y=17;y<=24;y+=3)for(int x=8;x<=22;x+=7)box(ink,fill,x,y,x+2,y+1);}
		else{box(ink,fill,9,2,23,13);for(int y=5;y<=10;y+=2)for(int x=11;x<=20;x++)ink[y][x]=true;
			box(ink,fill,3,12,29,25);for(int x=6;x<=26;x++)ink[20][x]=true;box(ink,fill,7,25,25,29);}
		byte[] r=new byte[264];
		for(int y=0;y<32;y++)for(int x=0;x<32;x++){if(ink[y][x])r[y*4+x/8]|=(byte)(0x80>>(x&7));if(fill[y][x]||ink[y][x])r[128+y*4+x/8]|=(byte)(0x80>>(x&7));}
		return r;
	}
	/** An outlined box from (left, top) to (right, bottom) inclusive, filled in the mask. */
	private static void box(boolean[][] ink,boolean[][] fill,int left,int top,int right,int bottom){
		for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++){fill[y][x]=true;if(y==top||y==bottom||x==left||x==right)ink[y][x]=true;}
	}
	private int stringHandle(String s) throws M68kCpuException {byte[] b=s.getBytes(MAC_ROMAN);byte[] p=new byte[b.length+1];p[0]=(byte)b.length;System.arraycopy(b,0,p,1,b.length);return tb.api.createHandle(p);}
	private void install(int rec) throws M68kCpuException {if(rec==0)return;memory.writeLong(rec+18,nextDeviceID++);devices.add(rec);relink();}
	private void relink() throws M68kCpuException {
		for(int i=0;i<devices.size();i++)memory.writeLong(devices.get(i),i+1<devices.size()?devices.get(i+1):0);
		if(crmHeader!=0){memory.writeLong(crmHeader+2,devices.isEmpty()?0:devices.get(0));memory.writeLong(crmHeader+6,devices.isEmpty()?0:devices.get(devices.size()-1));}
	}
}

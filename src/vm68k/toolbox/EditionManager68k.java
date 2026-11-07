import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Edition Manager (_Pack11, $A82D): System 7's Publish & Subscribe (Inside Macintosh: Interapplication Communication,
 * chapter 2). A publisher writes a part of its document into an edition container file; every subscriber registered to that
 * edition hears of a new one through a Section Read event ('sect'/'read', the section handle as the direct object) and reads
 * it back. Sections are registered system-wide here ({@link #REGISTRY}): a publisher in one program reaches subscribers in
 * every other program the VM runs, as on a Mac.
 *
 * <p>Edition containers are written in HyperZebra's own layout (Apple keeps theirs private, and nothing is copied from the
 * System file): the data fork holds a header - 'HZed', version 1, the edition's modification date, the format count, then
 * (type, offset, length) per format in the order they were written - and the formats' bytes. The Finder type follows the
 * first standard format written ('edtp' PICT, 'edtt' TEXT, 'edts' sound, else 'edtu') and the creator is the publisher's.
 * The 'alis' format is the alias of the publisher's document followed by its section ID (GoToPublisherSection, the
 * "is this the publisher" check); 'fmts' is the virtual list of (type, length) of every format.
 *
 * <p>Opening goes through the edition opener (SetEditionOpenerProc) and reading/writing through its FormatIOProc, as Apple
 * describes them: the standard ones are host gates, so a program's own opener can chain to them. The dialogs are the host's
 * file panels with a preview of the edition (scripted answers: StandardFile68k's {@code sfreply}/{@code sfsave}), and the
 * section options are a host dialog. The Exp variants show the same dialogs: a program's expansion items and hook are not
 * shown or called.
 */
final class EditionManager68k {
	static final int NO_ERR=0,IO_ERR=-36,EOF_ERR=-39,FNF_ERR=-43,FL_LCKED_ERR=-45,F_BSY_ERR=-47,PARAM_ERR=-50,RF_NUM_ERR=-51,PERM_ERR=-54,
		WR_PERM_ERR=-61,NO_TYPE_ERR=-102,DIR_NF_ERR=-120,USER_CANCELED_ERR=-128,EDITION_MGR_INIT_ERR=-450,BAD_SECTION_ERR=-451,
		NOT_REGISTERED_SECTION_ERR=-452,BAD_SUB_PART_ERR=-454,MULTIPLE_PUBLISHER_WRN=-460,CONTAINER_NOT_FOUND_WRN=-461,NOT_THE_PUBLISHER_WRN=-463;
	static final int ST_SUBSCRIBER=0x01,ST_PUBLISHER=0x0a;
	/** UpdateMode: sumAutomatic/pumOnSave 0, sumManual/pumManual 1. */
	static final int MODE_AUTOMATIC=0,MODE_MANUAL=1;
	/** SectionRecord: version, kind (SignedBytes), mode, mdDate, sectionID, refCon, alias, subPart, nextSection, controlBlock, refNum. */
	static final int S_VERSION=0,S_KIND=1,S_MODE=2,S_MD_DATE=4,S_ID=8,S_REFCON=12,S_ALIAS=16,S_SUB_PART=20,S_NEXT=24,S_CONTROL=28,S_REF_NUM=32,SECTION_SIZE=36;
	/** EditionContainerSpec: theFile (FSSpec), theFileScript, thePart, thePartName (Str31), thePartScript. */
	static final int C_FILE=0,C_FILE_SCRIPT=70,C_PART=72,C_PART_NAME=76,C_PART_SCRIPT=108,CONTAINER_SIZE=110;
	/** EditionInfoRecord: crDate, mdDate, fdCreator, fdType, container. */
	static final int I_CR_DATE=0,I_MD_DATE=4,I_CREATOR=8,I_TYPE=12,I_CONTAINER=16,INFO_SIZE=126;
	/** EditionOpenerParamBlock: info, sectionH, document, fdCreator, ioRefNum, ioProc, success, formatsMask. */
	static final int P_INFO=0,P_SECTION=126,P_DOCUMENT=130,P_CREATOR=134,P_IO_REF_NUM=138,P_IO_PROC=142,P_SUCCESS=146,P_FORMATS_MASK=147,OPENER_BLOCK_SIZE=148;
	/** FormatIOParamBlock: ioRefNum, format, formatIndex, offset, buffPtr, buffLen. */
	static final int F_IO_REF_NUM=0,F_FORMAT=4,F_FORMAT_INDEX=8,F_OFFSET=12,F_BUFF_PTR=16,F_BUFF_LEN=20,IO_BLOCK_SIZE=24;
	static final int EO_OPEN=0,EO_CLOSE=1,EO_OPEN_NEW=2,EO_CLOSE_NEW=3,EO_CAN_SUBSCRIBE=4;
	static final int IO_HAS_FORMAT=0,IO_READ_FORMAT=1,IO_NEW_FORMAT=2,IO_WRITE_FORMAT=3;
	static final int SECTION_EVENT_CLASS=0x73656374,SECTION_READ=0x72656164,SECTION_WRITE=0x77726974,SECTION_SCROLL=0x7363726c,SECTION_CANCEL=0x636e636c,TYPE_SECTION_H=0x73656374;
	/** SectionOptionsReply.action: Get Edition Now, Send Edition Now, Open Publisher, Cancel Publisher/Subscriber, OK. */
	static final int ACTION_READ=SECTION_READ,ACTION_WRITE=SECTION_WRITE,ACTION_GOTO=0x676f746f,ACTION_CANCEL=SECTION_CANCEL,ACTION_OK=0x20202020;
	static final int PICT=0x50494354,TEXT=0x54455854,SND=0x736e6420,PRVW=0x70727677,ALIS=0x616c6973,FMTS=0x666d7473;
	static final int EDTP=0x65647470,EDTT=0x65647474,EDTS=0x65647473,EDTU=0x65647475;
	static final int MASK_PICT=1,MASK_TEXT=2,MASK_SND=4;
	/** 'HZed': the first long of an edition container written here. */
	static final int MAGIC=0x485a6564;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	EditionManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	// ------------------------------------------------------------------ edition containers

	/** An edition's formats in the order they were written, and its modification date (Mac seconds). */
	static final class Edition {
		final LinkedHashMap<Integer,byte[]> formats=new LinkedHashMap<Integer,byte[]>();int mdDate;
		/** The virtual 'fmts' format: (type, length) of every format. */
		byte[] formatList(){byte[] b=new byte[formats.size()*8];int at=0;for(Map.Entry<Integer,byte[]> e:formats.entrySet()){putLong(b,at,e.getKey());putLong(b,at+4,e.getValue().length);at+=8;}return b;}
		byte[] format(int type){return type==FMTS?formatList():formats.get(type);}
		int index(int type){int i=0;for(int t:formats.keySet()){if(t==type)return i;i++;}return type==FMTS?formats.size():-1;}
		/** The Finder type: the first standard format written. */
		int fileType(){for(int t:formats.keySet()){if(t==PICT)return EDTP;if(t==TEXT)return EDTT;if(t==SND)return EDTS;}return EDTU;}
		/** kPICTformatMask etc. of the standard formats it holds. */
		int formatsMask(){return (formats.containsKey(PICT)?MASK_PICT:0)|(formats.containsKey(TEXT)?MASK_TEXT:0)|(formats.containsKey(SND)?MASK_SND:0);}
	}
	/** An edition container's data fork as an edition; an empty file is an edition with no formats; null when it is not one written here. */
	static Edition parse(byte[] b){
		Edition e=new Edition();
		if(b==null)return null;
		if(b.length==0)return e;
		if(b.length<16||getLong(b,0)!=MAGIC)return null;
		e.mdDate=getLong(b,8);int count=getLong(b,12);
		if(count<0||16L+count*12L>b.length)return null;
		for(int i=0;i<count;i++){int at=16+i*12,type=getLong(b,at),offset=getLong(b,at+4),length=getLong(b,at+8);
			if(offset<0||length<0||(long)offset+length>b.length)return null;
			e.formats.put(type,Arrays.copyOfRange(b,offset,offset+length));}
		return e;
	}
	static byte[] build(Edition e){
		int size=16+e.formats.size()*12;for(byte[] d:e.formats.values())size+=d.length;
		byte[] b=new byte[size];putLong(b,0,MAGIC);b[5]=1;putLong(b,8,e.mdDate);putLong(b,12,e.formats.size());
		int entry=16,data=16+e.formats.size()*12;
		for(Map.Entry<Integer,byte[]> f:e.formats.entrySet()){byte[] d=f.getValue();putLong(b,entry,f.getKey());putLong(b,entry+4,data);putLong(b,entry+8,d.length);System.arraycopy(d,0,b,data,d.length);entry+=12;data+=d.length;}
		return b;
	}
	private String requester(){return tb.files.fileManager.requester();}
	/** The edition in a container file, read for the program (the permission gate decides); null when it is not one. */
	private Edition load(File f) throws IOException {Sandbox.checkFile(f,Sandbox.Access.READ,requester());return parse(Files.readAllBytes(f.toPath()));}
	/** The edition in a container file for a host dialog's preview: no program reads it. */
	static Edition peek(File f){try{return f!=null&&f.isFile()&&f.length()<64L*1024*1024?parse(Files.readAllBytes(f.toPath())):null;}catch(IOException|RuntimeException e){return null;}}
	/** Writes an edition into its container and sets the container's type, creator and modification date. */
	private int store(File f,Edition e,int creator) throws IOException {
		Sandbox.checkFile(f,Sandbox.Access.WRITE,requester());
		Files.write(f.toPath(),build(e));
		byte[] info=new byte[16];byte[] old=FinderInfo68k.read(f);if(old!=null)System.arraycopy(old,0,info,0,Math.min(16,old.length));
		putLong(info,0,e.fileType());if(creator!=0)putLong(info,4,creator);
		return tb.files.fileManager.setCatalogEntry(f,null,info,null,0,e.mdDate,null);
	}
	/** CreateEditionContainerFile: an empty edition, type 'edtu'. */
	private int createContainer(File f,int creator) throws IOException {
		int r=tb.files.fileManager.createFile(f);if(r!=NO_ERR)return r;
		Edition e=new Edition();
		Files.write(f.toPath(),build(e));
		byte[] info=new byte[16];putLong(info,0,EDTU);putLong(info,4,creator);
		return tb.files.fileManager.setCatalogEntry(f,null,info,null,0,0,null);
	}
	/** The date of the latest edition in a container: its header's, else the file's modification date. */
	private int editionDate(File f){
		if(f==null||!f.isFile())return 0;
		try{if(Sandbox.allowFile(f,Sandbox.Access.READ,requester())){Edition e=parse(Files.readAllBytes(f.toPath()));if(e!=null&&e.mdDate!=0)return e.mdDate;if(e!=null&&e.formats.isEmpty())return 0;}}catch(IOException e){}
		return FileManager68k.macDate(f.lastModified());
	}
	private static boolean isEditionType(int type){return type==EDTP||type==EDTT||type==EDTS||type==EDTU;}
	private static String canonical(File f){try{return f.getCanonicalPath();}catch(IOException e){return f.getAbsolutePath();}}

	// ------------------------------------------------------------------ registered sections

	/** A registered section: whose (the program's Edition Manager), its handle there, what it is and the container it names. */
	private static final class Registration {
		final EditionManager68k owner;final int section;final boolean publisher;
		volatile String container;volatile File document;
		Registration(EditionManager68k owner,int section,boolean publisher){this.owner=owner;this.section=section;this.publisher=publisher;}
		boolean alive(){MacApp68k app=owner.tb.app;return app==null||!app.finished;}
	}
	/** Every registered section of every program. */
	private static final List<Registration> REGISTRY=new ArrayList<Registration>();
	/** Containers a publisher has open for writing (canonical path -> its Edition Manager). */
	private static final Map<String,EditionManager68k> WRITERS=new HashMap<String,EditionManager68k>();
	/** GoToPublisherSection asked for a publisher whose document is being opened: its Section Scroll waits for it to register. */
	private static final class PendingScroll {final String container,document;final int sectionId;final long until;PendingScroll(String c,String d,int id){container=c;document=d;sectionId=id;until=System.currentTimeMillis()+120_000;}}
	private static final List<PendingScroll> PENDING_SCROLLS=new ArrayList<PendingScroll>();
	/** The container of the last section NewSection made (GetLastEditionContainerUsed). */
	private static volatile File lastContainer;

	/** This program's sections in registration order (their nextSection links). */
	private final List<Registration> own=new ArrayList<Registration>();
	/** One control block per container this program has sections of: sections sharing one share an edition. */
	private final Map<String,Integer> controlBlocks=new HashMap<String,Integer>();
	private final Map<String,Integer> controlBlockUsers=new HashMap<String,Integer>();

	private static List<Registration> registrations(){synchronized(REGISTRY){for(Iterator<Registration> it=REGISTRY.iterator();it.hasNext();)if(!it.next().alive())it.remove();return new ArrayList<Registration>(REGISTRY);}}
	private Registration registration(int section){for(Registration r:own)if(r.section==section)return r;return null;}
	private int pointer(int section) throws M68kCpuException {return section==0?0:memory.readLong(section);}
	private boolean validSection(int section) throws M68kCpuException {
		int p=pointer(section);if(p==0||tb.api.handleSize(section)<SECTION_SIZE)return false;
		int kind=memory.readByte(p+S_KIND);return kind==ST_SUBSCRIBER||kind==ST_PUBLISHER;
	}
	private boolean isPublisher(int section) throws M68kCpuException {return memory.readByte(pointer(section)+S_KIND)==ST_PUBLISHER;}
	/** The container a section's alias names (relative to its document when moved together); null when not even its folder is found. */
	private File containerOf(int section,File document) throws M68kCpuException {
		int alias=memory.readLong(pointer(section)+S_ALIAS);byte[] record=alias==0?null:tb.api.handleContents(alias);
		return record==null?null:tb.files.aliasTarget(record,document);
	}
	private File documentOf(int section){Registration r=registration(section);return r==null?null:r.document;}
	private File fileForSpec(int spec){if(spec==0)return null;try{return tb.files.fileForSpec(spec);}catch(IOException e){return null;}}
	private void setHandle(int handle,byte[] data) throws M68kCpuException {if(tb.api.setHandleSize(handle,data.length)==NO_ERR)memory.load(memory.readLong(handle),data);}

	/** Adds a section to this program's and the system's lists; the warning RegisterSection answers. */
	private int register(int section,File document,boolean[] aliasUpdated) throws M68kException {
		int p=pointer(section);boolean publisher=isPublisher(section);
		File f=containerOf(section,document);
		int alias=memory.readLong(p+S_ALIAS);byte[] record=alias==0?null:tb.api.handleContents(alias);
		// the alias found the container somewhere else (moved, renamed): it is brought up to date and the program saves the document
		if(f!=null&&f.exists()&&record!=null){String recorded=tb.files.aliasHostPath(record);if(recorded!=null&&!recorded.equals(f.getPath())){setHandle(alias,tb.files.makeAlias(f));aliasUpdated[0]=true;}}
		Registration r=new Registration(this,section,publisher);r.document=document;
		int result=NO_ERR;
		if(publisher){
			if(f!=null&&!f.exists()&&f.getParentFile()!=null&&f.getParentFile().isDirectory()){
				// the edition is gone: an empty one is made where it was, and the publisher is asked to write it again
				try{if(createContainer(f,tb.files.fileCreator(document==null?f:document))==NO_ERR)tb.laterAtEvent(()->deliver(SECTION_WRITE,section));}catch(IOException|SecurityException e){}
			}
			if(f==null||!f.exists())result=CONTAINER_NOT_FOUND_WRN;
			else{String key=canonical(f);
				for(Registration o:registrations())if(o.publisher&&key.equals(o.container)){result=MULTIPLE_PUBLISHER_WRN;break;}
				if(result==NO_ERR&&!isThePublisher(f,document,memory.readLong(p+S_ID)))result=NOT_THE_PUBLISHER_WRN;}
		}else if(f==null||!f.exists())result=CONTAINER_NOT_FOUND_WRN;
		else if(memory.readWord(p+S_MODE)==MODE_AUTOMATIC&&editionDate(f)!=memory.readLong(p+S_MD_DATE))tb.laterAtEvent(()->deliver(SECTION_READ,section));
		r.container=f!=null&&f.exists()?canonical(f):null;
		synchronized(REGISTRY){REGISTRY.add(r);}
		own.add(r);
		memory.writeLong(p+S_CONTROL,r.container==null?0:retainControlBlock(r.container));
		relink();
		if(publisher&&r.container!=null)scrollIfAsked(r,memory.readLong(p+S_ID));
		if(Mac68kApi.TRACE)System.err.println("[68K] Edition Manager: registered "+(publisher?"publisher":"subscriber")+" $"+Integer.toHexString(section)+" -> "+f+" ("+result+")");
		return result;
	}
	private void unregister(Registration r) throws M68kCpuException {
		synchronized(REGISTRY){REGISTRY.remove(r);}
		own.remove(r);
		int p=pointer(r.section);
		if(p!=0){memory.writeLong(p+S_CONTROL,0);memory.writeLong(p+S_NEXT,0);}
		if(r.container!=null)releaseControlBlock(r.container);
		relink();
	}
	/** nextSection: the program's sections linked in the order they were registered. */
	private void relink() throws M68kCpuException {
		for(int i=0;i<own.size();i++){int p=pointer(own.get(i).section);if(p!=0)memory.writeLong(p+S_NEXT,i+1<own.size()?own.get(i+1).section:0);}
	}
	private int retainControlBlock(String container) throws M68kCpuException {
		Integer h=controlBlocks.get(container);
		if(h==null){h=tb.api.createHandle(new byte[8]);controlBlocks.put(container,h);}
		controlBlockUsers.merge(container,1,Integer::sum);
		return h;
	}
	private void releaseControlBlock(String container){
		Integer users=controlBlockUsers.get(container);if(users==null)return;
		if(users>1){controlBlockUsers.put(container,users-1);return;}
		controlBlockUsers.remove(container);Integer h=controlBlocks.remove(container);if(h!=null)tb.api.disposeHandle(h);
	}
	/** Whether the publisher that last wrote the edition is this section of this document ('alis': its document and section ID). */
	private boolean isThePublisher(File container,File document,int sectionId){
		Edition e;try{e=load(container);}catch(IOException|SecurityException x){return true;}
		byte[] alis=e==null?null:e.formats.get(ALIS);
		if(alis==null)return true; // nobody has published into it yet
		int aliasSize=alis.length>=6?(alis[4]&0xff)<<8|(alis[5]&0xff):alis.length;
		if(aliasSize+4<=alis.length&&getLong(alis,aliasSize)!=sectionId)return false;
		if(document==null)return true;
		File publisher=tb.files.aliasTarget(Arrays.copyOf(alis,Math.min(aliasSize,alis.length)),container);
		return publisher==null||canonical(publisher).equals(canonical(document));
	}

	/** A Section Read/Write/Scroll event to this program for one of its sections; dropped once the section is gone. */
	private void deliver(int eventId,int section){
		if(registration(section)==null)return;
		try{if(eventId==SECTION_READ&&memory.readWord(pointer(section)+S_MODE)!=MODE_AUTOMATIC)return;}catch(M68kCpuException e){return;}
		AppleEvents68k.Portable p=new AppleEvents68k.Portable();p.type=AppleEvents68k.TYPE_AEVT;p.isRecord=true;p.eventClass=SECTION_EVENT_CLASS;p.eventId=eventId;
		AppleEvents68k.Portable s=new AppleEvents68k.Portable();s.type=TYPE_SECTION_H;s.data=new byte[4];putLong(s.data,0,section);p.items.put(AppleEvents68k.KEY_DIRECT,s);
		AppleEvents68k.Portable from=new AppleEvents68k.Portable();from.type=AppleEvents68k.TYPE_PSN;from.data=new byte[]{0,0,0,0,0,0,0,1};p.attributes.put(AppleEvents68k.KEY_ADDRESS,from); // kSystemProcess
		tb.events.postAppleEvent(p,0);
		if(Mac68kApi.TRACE)System.err.println("[68K] Edition Manager: Section "+AppleEvents68k.fourcc(eventId)+" event queued for section $"+Integer.toHexString(section));
	}
	/** A new edition is in {@code container}: every automatic subscriber to it, in any program, hears of it at its next event call. */
	private static void published(File container){
		String key=canonical(container);
		for(Registration r:registrations())if(!r.publisher&&key.equals(r.container)){final Registration s=r;s.owner.tb.laterAtEvent(()->s.owner.deliver(SECTION_READ,s.section));}
	}
	private void scrollIfAsked(Registration r,int sectionId){
		PendingScroll found=null;long now=System.currentTimeMillis();
		synchronized(PENDING_SCROLLS){for(Iterator<PendingScroll> it=PENDING_SCROLLS.iterator();it.hasNext();){PendingScroll s=it.next();
			if(s.until<now){it.remove();continue;}
			if(s.container.equals(r.container)&&(s.sectionId==sectionId||r.document!=null&&s.document.equals(canonical(r.document)))){found=s;it.remove();break;}}}
		if(found!=null){final int section=r.section;tb.laterAtEvent(()->deliver(SECTION_SCROLL,section));}
	}

	// ------------------------------------------------------------------ open editions

	/** An open edition (EditionRefNum): the opener and I/O procedure in charge, the format marks. */
	private static final class Open {
		int section,opener,ioProc,ioRefNum,editionDate;boolean writing;File container;
		final Map<Integer,Integer> marks=new HashMap<Integer,Integer>();final Set<Integer> written=new HashSet<Integer>();
		int mark(int format){Integer m=marks.get(format);return m==null?0:m;}
	}
	private final Map<Integer,Open> open=new HashMap<Integer,Open>();
	/** The standard opener's open containers (its ioRefNum). */
	private static final class NativeIo {final File file;final Edition edition;final boolean writing;int creator;NativeIo(File f,Edition e,boolean w){file=f;edition=e;writing=w;}}
	private final Map<Integer,NativeIo> io=new HashMap<Integer,NativeIo>();private int nextIoRef=1;
	/** SetEditionOpenerProc's opener; 0 = the standard one. */
	private int opener;
	private int standardOpenerGate,standardIoGate;
	/** The standard EditionOpenerProc as a routine a program can call (GetEditionOpenerProc; a program's opener chains to it). */
	private int standardOpener(){
		if(standardOpenerGate==0)standardOpenerGate=tb.registerGate(c->{int pb=memory.readLong(c.a[7]),verb=byteArgument(c.a[7]+4);c.a[7]+=6;memory.writeWord(c.a[7],standardOpener(verb,pb));});
		return standardOpenerGate;
	}
	/** The standard FormatIOProc (the standard opener's ioProc). */
	private int standardIo(){
		if(standardIoGate==0)standardIoGate=tb.registerGate(c->{int pb=memory.readLong(c.a[7]),verb=byteArgument(c.a[7]+4);c.a[7]+=6;memory.writeWord(c.a[7],standardIo(verb,pb));});
		return standardIoGate;
	}
	/** A byte-sized Pascal argument: in the high byte of its word, or the low one when a C caller pushed it as a short. */
	private int byteArgument(int at) throws M68kCpuException {int w=memory.readWord(at);return (w>>>8)!=0?w>>>8&0xff:w&0xff;}

	private int callOpener(int routine,int verb,int pb) throws M68kException {
		if(routine==0||routine==standardOpenerGate)return standardOpener(verb,pb);
		return callPascal(routine,verb,pb);
	}
	private int callIo(Open o,int verb,int pb) throws M68kException {
		if(o.ioProc==0||o.ioProc==standardIoGate)return standardIo(verb,pb);
		return callPascal(o.ioProc,verb,pb);
	}
	/** pascal OSErr proc(verb: SignedByte-sized enum, VAR paramBlock) in the program's code. */
	private int callPascal(int routine,int verb,int pb) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		cpu.a[7]-=2;memory.writeWord(cpu.a[7],0);cpu.pushWord(verb<<8);cpu.pushLong(pb);
		try{cpu.callPascal(routine,6);return (short)memory.readWord(cpu.a[7]);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}

	/** The standard opener: edition containers in HyperZebra's layout, through the standard FormatIOProc. */
	private int standardOpener(int verb,int pb) throws M68kCpuException {
		File f=fileForSpec(pb+P_INFO+I_CONTAINER+C_FILE);
		switch(verb){
			case EO_OPEN:case EO_CAN_SUBSCRIBE:{
				if(f==null||!f.isFile())return FNF_ERR;
				Edition e;try{e=load(f);}catch(SecurityException x){return PERM_ERR;}catch(IOException x){return IO_ERR;}
				if(e==null)return verb==EO_CAN_SUBSCRIBE?NO_TYPE_ERR:BAD_SUB_PART_ERR;
				if(verb==EO_CAN_SUBSCRIBE){memory.writeByte(pb+P_FORMATS_MASK,e.formatsMask());return NO_ERR;}
				int ref=nextIoRef++;io.put(ref,new NativeIo(f,e,false));
				memory.writeLong(pb+P_IO_REF_NUM,ref);memory.writeLong(pb+P_IO_PROC,standardIo());
				return NO_ERR;}
			case EO_OPEN_NEW:{
				if(f==null)return DIR_NF_ERR;
				String key=canonical(f);
				synchronized(WRITERS){EditionManager68k w=WRITERS.get(key);if(w!=null&&(w.tb.app==null||!w.tb.app.finished))return FL_LCKED_ERR;WRITERS.put(key,this);}
				int creator=memory.readLong(pb+P_CREATOR);
				if(!f.exists())try{int r=createContainer(f,creator);if(r!=NO_ERR){releaseWriter(key);return r;}}catch(SecurityException x){releaseWriter(key);return PERM_ERR;}catch(IOException x){releaseWriter(key);return IO_ERR;}
				NativeIo n=new NativeIo(f,new Edition(),true);n.creator=creator;
				// the publisher's document and section: GoToPublisherSection finds it from the edition, RegisterSection tells who published last
				File document=fileForSpec(memory.readLong(pb+P_DOCUMENT));int section=memory.readLong(pb+P_SECTION);
				if(document!=null){byte[] alias=tb.files.makeAlias(document);byte[] alis=Arrays.copyOf(alias,alias.length+4);putLong(alis,alias.length,section==0||pointer(section)==0?0:memory.readLong(pointer(section)+S_ID));n.edition.formats.put(ALIS,alis);}
				int ref=nextIoRef++;io.put(ref,n);
				memory.writeLong(pb+P_IO_REF_NUM,ref);memory.writeLong(pb+P_IO_PROC,standardIo());
				return NO_ERR;}
			case EO_CLOSE:io.remove(memory.readLong(pb+P_IO_REF_NUM));return NO_ERR;
			case EO_CLOSE_NEW:{
				NativeIo n=io.remove(memory.readLong(pb+P_IO_REF_NUM));if(n==null)return RF_NUM_ERR;
				try{
					if(memory.readByte(pb+P_SUCCESS)==0)return NO_ERR; // the edition there stays as it was
					int section=memory.readLong(pb+P_SECTION),date=section==0||pointer(section)==0?0:memory.readLong(pointer(section)+S_MD_DATE);
					n.edition.mdDate=date!=0?date:(int)Toolbox68k.macNow();
					try{return store(n.file,n.edition,n.creator);}catch(SecurityException x){return PERM_ERR;}catch(IOException x){return IO_ERR;}
				}finally{releaseWriter(canonical(n.file));}}
			default:return PARAM_ERR;
		}
	}
	private void releaseWriter(String key){synchronized(WRITERS){if(WRITERS.get(key)==this)WRITERS.remove(key);}}

	/** The standard FormatIOProc over a container the standard opener opened. */
	private int standardIo(int verb,int pb) throws M68kCpuException {
		NativeIo n=io.get(memory.readLong(pb+F_IO_REF_NUM));if(n==null)return RF_NUM_ERR;
		int format=memory.readLong(pb+F_FORMAT);
		switch(verb){
			case IO_HAS_FORMAT:{byte[] d=n.edition.format(format);if(d==null)return NO_TYPE_ERR;memory.writeLong(pb+F_BUFF_LEN,d.length);memory.writeLong(pb+F_FORMAT_INDEX,n.edition.index(format));return NO_ERR;}
			case IO_READ_FORMAT:{
				byte[] d=n.edition.format(format);if(d==null)return NO_TYPE_ERR;
				int offset=memory.readLong(pb+F_OFFSET),want=memory.readLong(pb+F_BUFF_LEN),buffer=memory.readLong(pb+F_BUFF_PTR);
				int got=offset<0||want<0?0:(int)Math.max(0,Math.min((long)want,(long)d.length-offset));
				if(got>0)memory.load(buffer,d,offset,got);
				memory.writeLong(pb+F_BUFF_LEN,got);return NO_ERR;}
			case IO_NEW_FORMAT:{if(!n.writing)return WR_PERM_ERR;if(format==FMTS)return PARAM_ERR;n.edition.formats.putIfAbsent(format,new byte[0]);memory.writeLong(pb+F_FORMAT_INDEX,n.edition.index(format));return NO_ERR;}
			case IO_WRITE_FORMAT:{
				if(!n.writing)return WR_PERM_ERR;
				byte[] d=n.edition.formats.get(format);if(d==null)return NO_TYPE_ERR;
				int offset=memory.readLong(pb+F_OFFSET),length=memory.readLong(pb+F_BUFF_LEN),buffer=memory.readLong(pb+F_BUFF_PTR);
				if(offset<0||length<0||(long)offset+length>64L*1024*1024)return PARAM_ERR;
				byte[] grown=d.length>=offset+length?d:Arrays.copyOf(d,offset+length);
				if(length>0)System.arraycopy(memory.readBytes(buffer,length),0,grown,offset,length);
				n.edition.formats.put(format,grown);return NO_ERR;}
			default:return PARAM_ERR;
		}
	}

	/** EditionInfoRecord of a section's container (also when the container is missing but its folder is there: {@code allowMissing}). */
	private int fillInfo(int section,int info,boolean allowMissing) throws M68kCpuException {
		memory.fill(info,INFO_SIZE,0);
		File f=containerOf(section,documentOf(section));
		if(f==null||!f.exists()&&!allowMissing)return FNF_ERR;
		writeContainerSpec(info+I_CONTAINER,f);
		if(f.isFile()){
			try{FileManager68k.Metadata m=tb.files.fileManager.metadataOf(f);memory.writeLong(info+I_CR_DATE,m.creationDate);}catch(IOException e){}
			memory.writeLong(info+I_MD_DATE,editionDate(f));memory.writeLong(info+I_CREATOR,tb.files.fileCreator(f));memory.writeLong(info+I_TYPE,tb.files.fileType(f));
		}
		return NO_ERR;
	}
	private void writeContainerSpec(int spec,File f) throws M68kCpuException {
		memory.fill(spec,CONTAINER_SIZE,0);
		try{tb.files.writeFileSpec(spec+C_FILE,f);}catch(IOException e){}
		memory.writeWord(spec+C_FILE_SCRIPT,tb.encoding==StackTextEncoding.JAPANESE?1:0); // kPartsNotUsed, no part name
	}

	// ------------------------------------------------------------------ _Pack11

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xff,args=(cpu.d[0]>>>8&0xff)*2,sp=cpu.a[7];
		int result;
		switch(selector){
			case 0x00:result=NO_ERR;break;                                                                  // InitEditionPack(Version)
			case 0x02:result=newSection(memory.readLong(sp+16),memory.readLong(sp+12),byteArgument(sp+10),memory.readLong(sp+6),memory.readWord(sp+4),memory.readLong(sp));break;
			case 0x04:result=registerSection(memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x06:{int section=memory.readLong(sp);Registration r=registration(section);if(r==null)result=NOT_REGISTERED_SECTION_ERR;else{unregister(r);result=NO_ERR;}break;} // UnRegisterSection
			case 0x08:result=registration(memory.readLong(sp))!=null?NO_ERR:NOT_REGISTERED_SECTION_ERR;break; // IsRegisteredSection
			case 0x0c:result=associateSection(memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x0e:result=createEditionContainerFile(memory.readLong(sp+6),memory.readLong(sp+2));break;
			case 0x10:result=deleteEditionContainerFile(memory.readLong(sp));break;
			case 0x12:result=openEdition(memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x14:result=openNewEdition(memory.readLong(sp+12),memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x16:result=closeEdition(memory.readLong(sp+2),byteArgument(sp)!=0);break;
			case 0x18:result=editionHasFormat(memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x1a:result=readEdition(memory.readLong(sp+12),memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x1c:result=writeEdition(memory.readLong(sp+12),memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x1e:result=formatMark(memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp),false);break;
			case 0x20:result=formatMark(memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp),true);break;
			case 0x22:{int section=memory.readLong(sp+4),info=memory.readLong(sp);result=!validSection(section)?BAD_SECTION_ERR:info==0?PARAM_ERR:fillInfo(section,info,false);break;} // GetEditionInfo
			case 0x24:result=goToPublisherSection(memory.readLong(sp));break;
			case 0x26:result=lastEditionContainerUsed(memory.readLong(sp));break;
			case 0x28:result=standardFormats(memory.readLong(sp+16),memory.readLong(sp+12),memory.readLong(sp+8),memory.readLong(sp+4),memory.readLong(sp));break;
			case 0x2a:{int at=memory.readLong(sp);if(at!=0)memory.writeLong(at,opener!=0?opener:standardOpener());result=NO_ERR;break;} // GetEditionOpenerProc
			case 0x2c:{int proc=memory.readLong(sp);opener=proc==standardOpenerGate?0:proc;result=NO_ERR;break;} // SetEditionOpenerProc
			case 0x2e:{int routine=memory.readLong(sp),pb=memory.readLong(sp+4),verb=byteArgument(sp+8);result=pb==0?PARAM_ERR:callOpener(routine,verb,pb);break;} // CallEditionOpenerProc
			case 0x30:{int routine=memory.readLong(sp),pb=memory.readLong(sp+4),verb=byteArgument(sp+8);Open o=new Open();o.ioProc=routine;result=pb==0?PARAM_ERR:callIo(o,verb,pb);break;} // CallFormatIOProc
			case 0x32:result=newSubscriberDialog(memory.readLong(sp));break;
			case 0x34:result=newSubscriberDialog(memory.readLong(sp+18));break;                            // NewSubscriberExpDialog
			case 0x36:result=newPublisherDialog(memory.readLong(sp));break;
			case 0x38:result=newPublisherDialog(memory.readLong(sp+18));break;                             // NewPublisherExpDialog
			case 0x3a:result=sectionOptionsDialog(memory.readLong(sp));break;
			case 0x3c:result=sectionOptionsDialog(memory.readLong(sp+18));break;                           // SectionOptionsExpDialog
			default:result=EDITION_MGR_INIT_ERR;break;
		}
		if(Mac68kApi.TRACE)System.err.println("[68K] EditionDispatch $"+Integer.toHexString(cpu.d[0]&0xffff)+" -> "+result);
		cpu.a[7]=sp+args;memory.writeWord(cpu.a[7],result);
		return true;
	}

	/** NewSection(container, sectionDocument, kind, sectionID, initialMode, VAR sectionH): a section aliased to the container, registered. */
	private int newSection(int container,int document,int kind,int sectionId,int mode,int sectionPtr) throws M68kException {
		if(sectionPtr!=0)memory.writeLong(sectionPtr,0);
		if(kind!=ST_SUBSCRIBER&&kind!=ST_PUBLISHER)return BAD_SECTION_ERR;
		if(container==0||sectionPtr==0)return PARAM_ERR;
		File f=fileForSpec(container+C_FILE);
		if(f==null)return DIR_NF_ERR;
		if(!f.exists()&&kind==ST_SUBSCRIBER)return FNF_ERR;
		File doc=fileForSpec(document);
		int section=tb.api.createHandle(new byte[SECTION_SIZE]),p=pointer(section);
		memory.writeByte(p+S_VERSION,1);memory.writeByte(p+S_KIND,kind);memory.writeWord(p+S_MODE,mode);
		memory.writeLong(p+S_MD_DATE,kind==ST_PUBLISHER?(int)Toolbox68k.macNow():0);memory.writeLong(p+S_ID,sectionId);
		memory.writeLong(p+S_ALIAS,tb.api.createHandle(tb.files.makeAlias(f)));
		lastContainer=f;
		boolean[] updated={false};int result=register(section,doc,updated);
		memory.writeLong(sectionPtr,section);
		return result;
	}
	/** RegisterSection(sectionDocument, sectionH, VAR aliasWasUpdated) for a section read back with its document. */
	private int registerSection(int document,int section,int updatedPtr) throws M68kException {
		if(updatedPtr!=0)memory.writeByte(updatedPtr,0);
		if(!validSection(section))return BAD_SECTION_ERR;
		if(registration(section)!=null)return NO_ERR;
		boolean[] updated={false};int result=register(section,fileForSpec(document),updated);
		if(updatedPtr!=0)memory.writeByte(updatedPtr,updated[0]?1:0);
		return result;
	}
	/** AssociateSection(sectionH, newSectionDocument): the document was saved under another name or place. */
	private int associateSection(int section,int document) throws M68kException {
		if(!validSection(section))return BAD_SECTION_ERR;
		File doc=fileForSpec(document);Registration r=registration(section);
		File f=containerOf(section,r==null?null:r.document);
		if(f!=null)setHandle(memory.readLong(pointer(section)+S_ALIAS),tb.files.makeAlias(f));
		if(r!=null)r.document=doc;
		return NO_ERR;
	}
	private int createEditionContainerFile(int spec,int creator) throws M68kCpuException {
		File f=fileForSpec(spec);if(f==null)return spec==0?PARAM_ERR:DIR_NF_ERR;
		try{return createContainer(f,creator);}catch(SecurityException e){return FileManager68k.securityError(e);}catch(IOException e){return IO_ERR;}
	}
	/** DeleteEditionContainerFile: an edition someone still subscribes to stays (the publisher's cancel leaves it to them). */
	private int deleteEditionContainerFile(int spec) throws M68kCpuException {
		File f=fileForSpec(spec);if(f==null||!f.isFile())return FNF_ERR;
		String key=canonical(f);
		for(Registration r:registrations())if(!r.publisher&&key.equals(r.container))return NO_ERR;
		try{return tb.files.fileManager.deleteObject(f,false);}catch(SecurityException e){return FileManager68k.securityError(e);}catch(IOException e){return IO_ERR;}
	}

	private int openEdition(int section,int refPtr) throws M68kException {
		if(refPtr!=0)memory.writeLong(refPtr,0);
		if(!validSection(section))return BAD_SECTION_ERR;
		if(registration(section)==null)return NOT_REGISTERED_SECTION_ERR;
		if(isPublisher(section))return PERM_ERR;
		return openWith(section,false,0,0,refPtr);
	}
	private int openNewEdition(int section,int creator,int document,int refPtr) throws M68kException {
		if(refPtr!=0)memory.writeLong(refPtr,0);
		if(!validSection(section))return BAD_SECTION_ERR;
		if(registration(section)==null)return NOT_REGISTERED_SECTION_ERR;
		if(!isPublisher(section))return WR_PERM_ERR;
		return openWith(section,true,creator,document,refPtr);
	}
	private int openWith(int section,boolean writing,int creator,int document,int refPtr) throws M68kException {
		if(memory.readLong(pointer(section)+S_REF_NUM)!=0&&open.containsKey(memory.readLong(pointer(section)+S_REF_NUM)))return F_BSY_ERR;
		int pb=memory.allocate(OPENER_BLOCK_SIZE,2);
		try{
			memory.fill(pb,OPENER_BLOCK_SIZE,0);
			int r=fillInfo(section,pb+P_INFO,writing);if(r!=NO_ERR)return r;
			memory.writeLong(pb+P_SECTION,section);memory.writeLong(pb+P_DOCUMENT,document);memory.writeLong(pb+P_CREATOR,creator);
			int routine=opener;
			r=callOpener(routine,writing?EO_OPEN_NEW:EO_OPEN,pb);
			if(r!=NO_ERR)return r;
			Open o=new Open();o.section=section;o.writing=writing;o.opener=routine;o.ioProc=memory.readLong(pb+P_IO_PROC);o.ioRefNum=memory.readLong(pb+P_IO_REF_NUM);
			o.editionDate=memory.readLong(pb+P_INFO+I_MD_DATE);o.container=fileForSpec(pb+P_INFO+I_CONTAINER+C_FILE);
			int ref=tb.api.createHandle(new byte[4]);open.put(ref,o);
			memory.writeLong(pointer(section)+S_REF_NUM,ref);
			if(refPtr!=0)memory.writeLong(refPtr,ref);
			return NO_ERR;
		}finally{memory.free(pb);}
	}
	/** CloseEdition(whichEdition, successful): a subscriber is up to date with the edition it read; a publisher's new edition goes out. */
	private int closeEdition(int ref,boolean successful) throws M68kException {
		Open o=open.remove(ref);if(o==null)return RF_NUM_ERR;
		int pb=memory.allocate(OPENER_BLOCK_SIZE,2);int r;
		try{
			memory.fill(pb,OPENER_BLOCK_SIZE,0);
			if(o.container!=null)writeContainerSpec(pb+P_INFO+I_CONTAINER,o.container);
			memory.writeLong(pb+P_INFO+I_MD_DATE,o.editionDate);
			memory.writeLong(pb+P_SECTION,o.section);memory.writeLong(pb+P_IO_REF_NUM,o.ioRefNum);memory.writeLong(pb+P_IO_PROC,o.ioProc);memory.writeByte(pb+P_SUCCESS,successful?1:0);
			r=callOpener(o.opener,o.writing?EO_CLOSE_NEW:EO_CLOSE,pb);
		}finally{memory.free(pb);}
		int p=validSection(o.section)?pointer(o.section):0;
		if(p!=0){memory.writeLong(p+S_REF_NUM,0);if(!o.writing&&successful&&r==NO_ERR)memory.writeLong(p+S_MD_DATE,o.editionDate);}
		tb.api.disposeHandle(ref);
		if(o.writing&&successful&&r==NO_ERR&&o.container!=null)published(o.container);
		return r;
	}
	private Open openEditionFor(int ref){return open.get(ref);}
	/** A FormatIOParamBlock for one call of an open edition's I/O procedure; the caller frees it. */
	private int ioBlock(Open o,int format,int offset,int buffer,int length) throws M68kCpuException {
		int pb=memory.allocate(IO_BLOCK_SIZE,2);
		memory.writeLong(pb+F_IO_REF_NUM,o.ioRefNum);memory.writeLong(pb+F_FORMAT,format);memory.writeLong(pb+F_FORMAT_INDEX,0);
		memory.writeLong(pb+F_OFFSET,offset);memory.writeLong(pb+F_BUFF_PTR,buffer);memory.writeLong(pb+F_BUFF_LEN,length);
		return pb;
	}
	private int editionHasFormat(int ref,int format,int sizePtr) throws M68kException {
		Open o=openEditionFor(ref);if(o==null)return RF_NUM_ERR;
		int pb=ioBlock(o,format,0,0,0);
		try{int r=callIo(o,IO_HAS_FORMAT,pb);if(sizePtr!=0)memory.writeLong(sizePtr,r==NO_ERR?memory.readLong(pb+F_BUFF_LEN):0);return r;}
		finally{memory.free(pb);}
	}
	/** ReadEdition: from the format's mark on; fewer bytes than asked is the format's end (noErr), none at all is eofErr. */
	private int readEdition(int ref,int format,int buffer,int lengthPtr) throws M68kException {
		Open o=openEditionFor(ref);if(o==null)return RF_NUM_ERR;
		if(lengthPtr==0)return PARAM_ERR;
		int want=memory.readLong(lengthPtr);if(want<0||want>0&&buffer==0)return PARAM_ERR;
		int pb=ioBlock(o,format,o.mark(format),buffer,want);
		try{
			int r=callIo(o,IO_READ_FORMAT,pb),got=r==NO_ERR?memory.readLong(pb+F_BUFF_LEN):0;
			memory.writeLong(lengthPtr,got);
			if(r!=NO_ERR)return r;
			o.marks.put(format,o.mark(format)+got);
			return got==0&&want>0?EOF_ERR:NO_ERR;
		}finally{memory.free(pb);}
	}
	private int writeEdition(int ref,int format,int buffer,int length) throws M68kException {
		Open o=openEditionFor(ref);if(o==null)return RF_NUM_ERR;
		if(!o.writing)return WR_PERM_ERR;
		if(length<0||length>0&&buffer==0)return PARAM_ERR;
		if(!o.written.contains(format)){
			int pb=ioBlock(o,format,0,0,0);
			try{int r=callIo(o,IO_HAS_FORMAT,pb);if(r==NO_TYPE_ERR)r=callIo(o,IO_NEW_FORMAT,pb);if(r!=NO_ERR)return r;}finally{memory.free(pb);}
			o.written.add(format);
		}
		int pb=ioBlock(o,format,o.mark(format),buffer,length);
		try{int r=callIo(o,IO_WRITE_FORMAT,pb);if(r==NO_ERR)o.marks.put(format,o.mark(format)+length);return r;}
		finally{memory.free(pb);}
	}
	/** Get/SetEditionFormatMark: every format has a mark of its own, 0 at first. */
	private int formatMark(int ref,int format,int value,boolean set) throws M68kException {
		Open o=openEditionFor(ref);if(o==null)return RF_NUM_ERR;
		if(!o.marks.containsKey(format)&&!o.written.contains(format)){
			int pb=ioBlock(o,format,0,0,0);
			try{int r=callIo(o,IO_HAS_FORMAT,pb);if(r!=NO_ERR&&!(set&&o.writing&&r==NO_TYPE_ERR)){if(!set&&value!=0)memory.writeLong(value,0);return r;}}finally{memory.free(pb);}
		}
		if(set){if(value<0)return PARAM_ERR;o.marks.put(format,value);}
		else if(value!=0)memory.writeLong(value,o.mark(format));
		return NO_ERR;
	}

	/** GetLastEditionContainerUsed: the container of the last new section; else its folder (or the program's) with no name, fnfErr. */
	private int lastEditionContainerUsed(int spec) throws M68kCpuException {
		if(spec==0)return PARAM_ERR;
		File f=lastContainer;
		if(f!=null&&f.isFile()){writeContainerSpec(spec,f);return NO_ERR;}
		File folder=f!=null&&f.getParentFile()!=null&&f.getParentFile().isDirectory()?f.getParentFile():tb.programDirectory();
		memory.fill(spec,CONTAINER_SIZE,0);
		if(folder!=null)try{memory.writeWord(spec,tb.files.volumeRefNumFor(folder));memory.writeLong(spec+2,tb.files.directoryIdFor(folder));}catch(IOException e){}
		return FNF_ERR;
	}
	/** GetStandardFormats(container, VAR previewFormat, preview, publisherAlias, formats): the Handles given (non-NIL) are filled. */
	private int standardFormats(int container,int previewFormatPtr,int preview,int publisherAlias,int formats) throws M68kCpuException {
		File f=container==0?null:fileForSpec(container+C_FILE);if(f==null||!f.isFile())return FNF_ERR;
		Edition e;try{e=load(f);}catch(SecurityException x){return PERM_ERR;}catch(IOException x){return IO_ERR;}
		if(e==null)return BAD_SUB_PART_ERR;
		int result=NO_ERR;
		if(preview!=0||previewFormatPtr!=0){
			int found=0;for(int t:new int[]{PRVW,PICT,TEXT,SND})if(e.formats.containsKey(t)){found=t;break;}
			if(previewFormatPtr!=0)memory.writeLong(previewFormatPtr,found);
			if(found==0)result=NO_TYPE_ERR;else if(preview!=0)setHandle(preview,e.formats.get(found));
		}
		if(publisherAlias!=0){byte[] alis=e.formats.get(ALIS);if(alis==null)result=NO_TYPE_ERR;else setHandle(publisherAlias,alis);}
		if(formats!=0)setHandle(formats,e.formatList());
		return result;
	}
	/**
	 * GoToPublisherSection(container): the publisher's program shows the publisher - the running one that has it registered gets a
	 * Section Scroll event at once; otherwise its document is opened (its program launched when needed) and the event follows
	 * when the program registers the section.
	 */
	private int goToPublisherSection(int container) throws M68kCpuException {
		File f=container==0?null:fileForSpec(container+C_FILE);if(f==null||!f.isFile())return FNF_ERR;
		Edition e;try{e=load(f);}catch(SecurityException x){return PERM_ERR;}catch(IOException x){return IO_ERR;}
		byte[] alis=e==null?null:e.formats.get(ALIS);if(alis==null)return FNF_ERR;
		int aliasSize=alis.length>=6?(alis[4]&0xff)<<8|(alis[5]&0xff):alis.length,sectionId=aliasSize+4<=alis.length?getLong(alis,aliasSize):0;
		File document=tb.files.aliasTarget(Arrays.copyOf(alis,Math.min(aliasSize,alis.length)),f);
		String key=canonical(f);
		for(Registration r:registrations())if(r.publisher&&key.equals(r.container)&&(document==null||r.document==null||canonical(r.document).equals(canonical(document)))){
			final Registration s=r;MacApp68k app=s.owner.tb.app;if(app!=null)app.bringToFront();
			s.owner.tb.laterAtEvent(()->s.owner.deliver(SECTION_SCROLL,s.section));return NO_ERR;}
		if(document==null||!document.exists())return FNF_ERR;
		File program=tb.files.applicationForCreator(tb.files.fileCreator(document));
		if(program==null)return FNF_ERR;
		synchronized(PENDING_SCROLLS){PENDING_SCROLLS.add(new PendingScroll(key,canonical(document),sectionId));}
		MacApp68k running=MacApp68k.runningFrom(program);
		try{
			if(running!=null&&running.toolbox!=null){running.toolbox.events.postOpenDocuments(Collections.singletonList(document));running.bringToFront();}
			else MacApp68k.launch(program,Collections.singletonList(document));
		}catch(IOException x){return FNF_ERR;}
		return NO_ERR;
	}

	// ------------------------------------------------------------------ dialogs

	/** Tests and scripted runs: the next SectionOptionsDialog's answer instead of the dialog - "cancel", "ok", or an action
	 *  ("read", "writ", "goto", "cncl"), optionally followed by " manual" or " auto" for the new update mode. */
	static volatile String scriptedOptions;

	private boolean noDialog(){return tb.standardFile.panelUnavailable()||tb.app!=null&&tb.app.unattended;}

	/** NewPublisherDialog(VAR reply): where the edition goes - the program's default name and folder, its preview shown. */
	private int newPublisherDialog(int reply) throws M68kCpuException {
		if(reply==0)return PARAM_ERR;
		memory.writeByte(reply,1);memory.writeByte(reply+1,0);
		File def=fileForSpec(reply+12+C_FILE);
		File folder=def==null?null:def.isDirectory()?def:def.getParentFile();
		if(folder==null||!folder.isDirectory())folder=tb.standardFile.panelDirectory();
		String name=def!=null&&!def.isDirectory()?def.getName():"untitled edition";
		int previewHandle=memory.readLong(reply+4),previewFormat=memory.readLong(reply+8);
		final byte[] preview=previewHandle==0?null:tb.api.handleContents(previewHandle);
		File chosen=StandardFile68k.scriptedSave;
		if(chosen!=null)StandardFile68k.scriptedSave=null;
		else if(noDialog())return USER_CANCELED_ERR;
		else{final File start=folder;final String defaultName=name;
			chosen=tb.host().ask(()->EditionDialogs.publish(tb.host().anyWindow(),start,defaultName,previewFormat,preview,tb.encoding));}
		if(chosen==null)return USER_CANCELED_ERR;
		Sandbox.chosenByUser(tb.name,chosen,true);
		memory.writeByte(reply,0);memory.writeByte(reply+1,chosen.exists()?1:0);
		writeContainerSpec(reply+12,chosen);
		return NO_ERR;
	}
	/** NewSubscriberDialog(VAR reply): an edition holding one of the formats asked for (formatsMask), previews shown. */
	private int newSubscriberDialog(int reply) throws M68kCpuException {
		if(reply==0)return PARAM_ERR;
		memory.writeByte(reply,1);
		int mask=memory.readByte(reply+1);if(mask==0)mask=MASK_PICT|MASK_TEXT|MASK_SND;
		File def=fileForSpec(reply+2+C_FILE);
		File folder=def==null?null:def.isDirectory()?def:def.getParentFile();
		if(folder==null||!folder.isDirectory())folder=tb.standardFile.panelDirectory();
		File chosen=StandardFile68k.scriptedReply;
		if(chosen!=null)StandardFile68k.scriptedReply=null;
		else if(noDialog())return USER_CANCELED_ERR;
		else{final File start=folder,selected=def!=null&&def.isFile()?def:null;final int formats=mask;
			chosen=tb.host().ask(()->EditionDialogs.subscribe(tb.host().anyWindow(),start,selected,formats,tb.files.fileManager::shownType,tb.encoding));}
		if(chosen==null||!chosen.isFile())return USER_CANCELED_ERR;
		Sandbox.chosenByUser(tb.name,chosen,false);
		memory.writeByte(reply,0);
		writeContainerSpec(reply+2,chosen);
		return NO_ERR;
	}
	/** SectionOptionsDialog(VAR reply): the section's edition and update mode, and what to do now (reply.action). */
	private int sectionOptionsDialog(int reply) throws M68kException {
		if(reply==0)return PARAM_ERR;
		int section=memory.readLong(reply+2);
		memory.writeByte(reply,1);memory.writeByte(reply+1,0);memory.writeLong(reply+6,ACTION_OK);
		if(!validSection(section))return BAD_SECTION_ERR;
		int p=pointer(section);boolean publisher=isPublisher(section);int mode=memory.readWord(p+S_MODE);
		File f=containerOf(section,documentOf(section));
		String where=f==null?"":tb.files.classicPath(f);
		int date=f!=null&&f.isFile()?editionDate(f):0;
		EditionDialogs.Options answer;
		String scripted=scriptedOptions;
		if(scripted!=null){scriptedOptions=null;answer=EditionDialogs.Options.parse(scripted,mode);}
		else if(noDialog())return USER_CANCELED_ERR;
		else{final boolean pub=publisher;final int m=mode;final long when=date==0?0:Toolbox68k.hostMillis(date&0xffffffffL);
			answer=tb.host().ask(()->EditionDialogs.options(tb.host().anyWindow(),pub,where,m,when,f!=null&&f.isFile()));}
		if(answer==null||answer.canceled)return USER_CANCELED_ERR;
		memory.writeByte(reply,0);
		if(answer.mode!=mode){memory.writeWord(p+S_MODE,answer.mode);memory.writeByte(reply+1,1);}
		memory.writeLong(reply+6,answer.action);
		return NO_ERR;
	}

	static int getLong(byte[] b,int at){return (b[at]&0xff)<<24|(b[at+1]&0xff)<<16|(b[at+2]&0xff)<<8|(b[at+3]&0xff);}
	static void putLong(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}
}

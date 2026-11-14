import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Classic desk Scrap Manager: the scrap in memory (the browser player's: a 68K external's PutScrap/GetScrap, PgColorX's
 * PastePict). On the desktop {@link ScrapManager68kHost} mirrors it to the host clipboard and keeps the scrap file.
 */
class ScrapManager68k {
	static final int NO_ERR=0,PARAM_ERR=-50,NO_TYPE_ERR=-102;
	static final int TEXT=0x54455854,PICT=0x50494354;
	final M68kMemory memory;
	final Mac68kApi macApi;
	final List<Flavor> flavors=new ArrayList<Flavor>();
	int infoPointer,namePointer,scrapHandle,scrapCount;
	/** The desk scrap exists from the start, empty and on disk (ScrapState 0): under System 7 the Process Manager has set it up
	 *  before any program runs, so LoadScrap/PutScrap work without the program's own ZeroScrap (ResEdit's LoadScrap on opening
	 *  a PICT reported "I/O Error [-100]" when it found ScrapState -1). */
	boolean inMemory;

	ScrapManager68k(M68kMemory memory,Mac68kApi macApi){this.memory=memory;this.macApi=macApi;}

	boolean handle(M68kCpu cpu,int trap) throws M68kCpuException {
		switch(trap){
			case 0x9f9:return infoScrap(cpu);
			case 0x9fa:return unloadScrap(cpu);
			case 0x9fb:return loadScrap(cpu);
			case 0x9fc:return zeroScrap(cpu);
			case 0x9fd:return getScrap(cpu);
			case 0x9fe:return putScrap(cpu);
			default:return false;
		}
	}


	/** Bumped when HyperZebra itself puts a picture on the host clipboard (Copy Picture): the scraps take it at their next look,
	 *  whatever the throttles say (Dartmouth's ClipToPICT right after a second Copy Picture stored the first picture again). */
	static volatile int hostClipboardGeneration;

	/** The host clipboard into the scrap when another application changed it (ScrapManager68kHost); none here */
	void pullFromHost() throws M68kCpuException {}
	/** The next pullFromHost looks at once (the resume event) */
	void forgetLastPull() {}
	/** The scrap's TEXT and PICT onto the host clipboard (ScrapManager68kHost); none here */
	void pushToHost() {}
	/** UnloadScrap's scrap file and LoadScrap's read of it (ScrapManager68kHost, applications only); none here */
	void writeScrapFile() {}
	void readScrapFile() {}

	/** How often the scrap took the host clipboard over, and that count when the program was last suspended (-1: not suspended). */
	int adoptions,adoptionsAtSuspend=-1;
	/** The program got a suspend event: what it puts in the scrap from now on (ClarisWorks' own clipboard) is its own change. */
	void suspended(){adoptionsAtSuspend=adoptions;}
	/** At the resume event: whether another program or host application changed the clipboard since the suspend (convertClipboard). */
	boolean changedSinceSuspend() throws M68kCpuException {
		if(adoptionsAtSuspend<0)return false;
		forgetLastPull();pullFromHost();boolean changed=adoptions!=adoptionsAtSuspend;adoptionsAtSuspend=-1;return changed;
	}

	private boolean infoScrap(M68kCpu cpu) throws M68kCpuException {pullFromHost();ensureInfo();syncInfo();memory.writeLong(cpu.a[7],infoPointer);return true;}
	private boolean zeroScrap(M68kCpu cpu) throws M68kCpuException {flavors.clear();inMemory=true;scrapCount=(short)(scrapCount+1);syncScrap();memory.writeLong(cpu.a[7],NO_ERR);return true;}
	private boolean unloadScrap(M68kCpu cpu) throws M68kCpuException {if(inMemory){if(scrapHandle!=0)macApi.disposeHandle(scrapHandle);scrapHandle=0;inMemory=false;syncInfo();}
		writeScrapFile();memory.writeLong(cpu.a[7],NO_ERR);return true;}
	private boolean loadScrap(M68kCpu cpu) throws M68kCpuException {if(!inMemory){readScrapFile();inMemory=true;syncScrap();}memory.writeLong(cpu.a[7],NO_ERR);return true;}

	static int getLong(byte[] data,int offset){return (data[offset]&0xff)<<24|(data[offset+1]&0xff)<<16|(data[offset+2]&0xff)<<8|(data[offset+3]&0xff);}

	private boolean putScrap(M68kCpu cpu) throws M68kCpuException {int source=memory.readLong(cpu.a[7]),type=memory.readLong(cpu.a[7]+4),length=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;int result;
		if(length<0||(length>0&&source==0))result=PARAM_ERR;else {byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)memory.readByte(source+i);flavors.add(new Flavor(type,data));inMemory=true;syncScrap();if(type==TEXT||type==PICT)pushToHost();result=NO_ERR;}
		memory.writeLong(cpu.a[7],result);return true;}
	private boolean getScrap(M68kCpu cpu) throws M68kCpuException {pullFromHost();int offsetPointer=memory.readLong(cpu.a[7]),type=memory.readLong(cpu.a[7]+4),destination=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;int result=NO_TYPE_ERR,offset=0;
		int cursor=0;for(Flavor flavor:flavors){if(flavor.type==type){offset=cursor+8;result=flavor.data.length;if(destination!=0)macApi.loadHandle(destination,flavor.data);break;}cursor+=8+flavor.data.length+(flavor.data.length&1);}
		if(offsetPointer!=0)memory.writeLong(offsetPointer,result>=0?offset:0);memory.writeLong(cpu.a[7],result);return true;}

	/** The ScrapInfo record is the low-memory globals ScrapSize ($960), ScrapHandle ($964), ScrapCount ($968), ScrapState ($96A)
	 *  and ScrapName ($96C), as on a real Mac: InfoScrap returns their address, and programs (MacApp-style resume checks,
	 *  LMGetScrapCount) read ScrapCount there directly. */
	static final int SCRAP_INFO=0x960;
	void initializeLowMemory() throws M68kCpuException {infoPointer=0;ensureInfo();}
	private void ensureInfo() throws M68kCpuException {if(infoPointer!=0)return;infoPointer=SCRAP_INFO;byte[] name="Clipboard File".getBytes(java.nio.charset.StandardCharsets.US_ASCII);namePointer=memory.allocate(name.length+1,2);memory.writeByte(namePointer,name.length);memory.load(namePointer+1,name);syncInfo();}
	void syncScrap() throws M68kCpuException {byte[] packed=pack();if(scrapHandle==0)scrapHandle=macApi.createHandle(packed);else macApi.loadHandle(scrapHandle,packed);syncInfo();}
	private void syncInfo() throws M68kCpuException {ensureInfo();memory.writeLong(infoPointer,packedSize());memory.writeLong(infoPointer+4,inMemory?scrapHandle:0);memory.writeWord(infoPointer+8,scrapCount);memory.writeWord(infoPointer+10,inMemory?1:0);memory.writeLong(infoPointer+12,namePointer);}
	byte[] pack(){byte[] result=new byte[packedSize()];int offset=0;for(Flavor flavor:flavors){putLong(result,offset,flavor.type);putLong(result,offset+4,flavor.data.length);System.arraycopy(flavor.data,0,result,offset+8,flavor.data.length);offset+=8+flavor.data.length+(flavor.data.length&1);}return result;}
	private int packedSize(){int result=0;for(Flavor flavor:flavors)result+=8+flavor.data.length+(flavor.data.length&1);return result;}
	private static void putLong(byte[] data,int offset,int value){data[offset]=(byte)(value>>>24);data[offset+1]=(byte)(value>>>16);data[offset+2]=(byte)(value>>>8);data[offset+3]=(byte)value;}

	static String name(int trap){switch(trap){case 0x9f9:return "InfoScrap";case 0x9fa:return "UnloadScrap";case 0x9fb:return "LoadScrap";case 0x9fc:return "ZeroScrap";case 0x9fd:return "GetScrap";case 0x9fe:return "PutScrap";default:return null;}}
	static final class Flavor {final int type;final byte[] data;/** the host image of a PICT (ScrapManager68kHost) */Object image;boolean rendered;Flavor(int type,byte[] data){this.type=type;this.data=data;}}

}

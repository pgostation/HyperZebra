import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Internet Config for 68K programs and externals: the component (type 'PREF', subtype 'ICAp', manufacturer 'JPQE') that
 * Internet Config's extension registers on a real Mac (Mac OS 9 has it built in). Programs reach it through the IC glue
 * linked into them, which opens this component first (OpenDefaultComponent 'PREF' 'ICAp') and only without one reads the "Internet Preferences" file itself -
 * where it found nothing, so XTernal Collection's GetEMail/GetRealName/GetWWWHomepage said "Couldn't read Internet Config
 * Preferences" and LaunchURL "Couldn't launch URL" (its glue wanted a helper application for the scheme and found none).
 * <p>
 * The preferences are those of PowerPC programs (PpcInternetConfig): System Folder:Preferences:Internet Preferences, one
 * 'ICRP' resource per key (a 4-byte ICAttr and the value), and the same built-in defaults without it (user strings set but
 * empty, the web helpers standing for the host's browser). ICLaunchURL finds the URL as Internet Config did and hands it
 * to the host's browser through the Sandbox's "open in the web browser" question, as the PowerPC ICLaunchURL does.
 * The component selectors are Internet Config's own (kICCStart = 0 ... kICCGetVersion = 50).
 */
final class InternetConfig68k implements Components68k.Native {
	static final int ICAP=0x49434170,PREF=0x50524546,JPQE=0x4a505145;
	private static final int ICRP=0x49435250,ATTR_NO_CHANGE=0xffffffff;
	static final int NO_ERR=0,PARAM_ERR=-50,PREF_NOT_FOUND=-666,PERM_ERR=-667,TRUNCATED=-670,NO_URL=-673; // MacErrors.h (icPrefNotFoundErr, icPermErr, icTruncatedErr, icNoURLErr)
	private static final int READ_ONLY_PERM=1;

	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Per instance: the permission ICBegin was given (0 between ICBegin/ICEnd pairs). */
	private final java.util.Map<Integer,Integer> perms=new java.util.HashMap<Integer,Integer>();
	private static int seed=1;

	private InternetConfig68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}
	/** Registers the component (every VM: Internet Config is part of the Mac OS 9.2.2 HyperZebra reports). */
	static void register(Components68k components,Toolbox68k tb){
		if(!"false".equals(System.getProperty("hz68k.internetconfig")))components.registerNative(PREF,ICAP,JPQE,0,new InternetConfig68k(tb));
	}
	@Override public void closed(int ci){perms.remove(ci);}

	/** {@code p} points at the arguments: the last declared one first (Pascal order), the instance above them. */
	@Override public int call(int ci,int what,int p) throws M68kException {
		int size=memory.readByte(p-3);
		if(Mac68kApi.TRACE)System.err.println("[68K] Internet Config selector "+what+" params "+size);
		// the calls that read arguments check their size: a call this table numbers differently is refused, not misread
		int expected=what>=0&&what<PARAM_SIZES.length?PARAM_SIZES[what]:-1;
		if(expected>=0&&size!=expected)return Components68k.BAD_COMPONENT_SELECTOR;
		switch(what){
			case 0:return NO_ERR;                                       // ICCStart(inst, creator)
			case 1:return NO_ERR;                                       // ICCStop(inst)
			case 2:case 3:case 14:case 30:case 33:case 34:return NO_ERR; // FindConfigFile, SpecifyConfigFile, FindUserConfigFile, GeneralFindConfigFile, ChooseConfig, ChooseNewConfig: the one set of preferences
			case 47:return NO_ERR;                                      // ICCRefreshCaches
			case 31:{int h=memory.readLong(p);if(h==0)return PARAM_ERR;  // ICCGetConfigReference(inst, ref Handle): this component's manufacturer, then the file name (Cyberdog 2.0 asks at start-up and says "requires Internet Config 1.2" on an error)
				byte[] name="Internet Preferences".getBytes(StandardCharsets.ISO_8859_1);byte[] ref=new byte[5+name.length];
				ref[0]=(byte)(JPQE>>>24);ref[1]=(byte)(JPQE>>>16);ref[2]=(byte)(JPQE>>>8);ref[3]=(byte)JPQE;ref[4]=(byte)name.length;System.arraycopy(name,0,ref,5,name.length);
				tb.api.loadHandle(h,ref);return NO_ERR;}
			case 32:return memory.readLong(p+4)==0?PARAM_ERR:NO_ERR;     // ICCSetConfigReference(inst, ref Handle, flags): the one set of preferences
			case 4:out(memory.readLong(p),seed);return NO_ERR;          // ICCGetSeed(inst, *seed)
			case 13:out(memory.readLong(p),perms.getOrDefault(ci,0));return NO_ERR; // ICCGetPerm(inst, *perm)
			case 5:perms.put(ci,memory.readByte(p));return NO_ERR;      // ICCBegin(inst, perm)
			case 10:perms.remove(ci);return NO_ERR;                     // ICCEnd(inst)
			case 11:{int name=memory.readLong(p);if(name==0)return PARAM_ERR;writePascal(name,"Internet Preferences".getBytes(StandardCharsets.ISO_8859_1));return NO_ERR;} // ICCDefaultFileName(inst, Str63)
			case 35:{int name=memory.readLong(p);if(name==0)return PARAM_ERR;writePascal(name,"Internet Preferences".getBytes(StandardCharsets.ISO_8859_1));return NO_ERR;} // ICCGetConfigName(inst, longname, name)
			case 6:return getPref(key(memory.readLong(p+12)),memory.readLong(p+8),memory.readLong(p+4),memory.readLong(p)); // ICCGetPref(inst, key, *attr, buf, *size)
			case 7:{int buf=memory.readLong(p+4),n=memory.readLong(p); // ICCSetPref(inst, key, attr, buf, size)
				if(perms.getOrDefault(ci,0)==READ_ONLY_PERM)return PERM_ERR;
				return set(key(memory.readLong(p+12)),memory.readLong(p+8),buf==0||n<=0?new byte[0]:memory.readBytes(buf,n));}
			case 36:{byte[][] v=get(key(memory.readLong(p+8)));int attr=memory.readLong(p+4),h=memory.readLong(p); // ICCFindPrefHandle(inst, key, *attr, prefh)
				if(v==null)return PREF_NOT_FOUND;if(attr!=0)memory.load(attr,v[0]);if(h!=0)tb.api.loadHandle(h,v[1]);return NO_ERR;}
			case 26:{byte[][] v=get(key(memory.readLong(p+8)));int attr=memory.readLong(p+4),out=memory.readLong(p); // ICCGetPrefHandle(inst, key, *attr, *prefh): a new Handle
				if(v==null){if(out!=0)memory.writeLong(out,0);return PREF_NOT_FOUND;}if(attr!=0)memory.load(attr,v[0]);if(out!=0)memory.writeLong(out,tb.api.createHandle(v[1]));return NO_ERR;}
			case 27:{int h=memory.readLong(p);byte[] v=h==0?null:tb.api.handleContents(h);if(v==null)return PARAM_ERR; // ICCSetPrefHandle(inst, key, attr, prefh)
				if(perms.getOrDefault(ci,0)==READ_ONLY_PERM)return PERM_ERR;return set(key(memory.readLong(p+8)),memory.readLong(p+4),v);}
			case 49:{byte[] v=PpcInternetConfig.defaults(key(memory.readLong(p+4)));int h=memory.readLong(p); // ICCGetDefaultPref(inst, key, prefh)
				if(v==null)return PREF_NOT_FOUND;if(h!=0)tb.api.loadHandle(h,v);return NO_ERR;}
			case 8:out(memory.readLong(p),keys().size());return NO_ERR; // ICCCountPref(inst, *count)
			case 9:{List<String> keys=keys();int i=memory.readLong(p+4),key=memory.readLong(p); // ICCGetIndPref(inst, index, key)
				if(i<1||i>keys.size())return PREF_NOT_FOUND;if(key!=0)writePascal(key,StackTextEncoding.ROMAN.encodeReplacing(keys.get(i-1)));return NO_ERR;}
			case 12:if(perms.getOrDefault(ci,0)==READ_ONLY_PERM)return PERM_ERR;return delete(key(memory.readLong(p))); // ICCDeletePref(inst, key)
			case 50:out(memory.readLong(p),memory.readLong(p+4)==0?0x00020000:0x02008000);return NO_ERR; // ICCGetVersion(inst, which, *version): the component interface 2.0, Internet Config 2.0
			case 15:return NO_ERR;                                      // ICCEditPreferences(inst, key): there is no Internet Config application to show
			case 16:{Found f=find(p+4);if(f==null)return PARAM_ERR;     // ICCParseURL(inst, hint, data, len, *selStart, *selEnd, url)
				int h=memory.readLong(p);if(f.url==null)return NO_URL;if(h!=0)tb.api.loadHandle(h,f.url.getBytes(StandardCharsets.ISO_8859_1));return NO_ERR;}
			case 17:{Found f=find(p);if(f==null)return PARAM_ERR;      // ICCLaunchURL(inst, hint, data, len, *selStart, *selEnd)
				return f.url==null?NO_URL:launch(f.url);}
			case 24:return mapped(PpcInternetConfig.byFilename(mappings(0),key(memory.readLong(p+4))),memory.readLong(p)); // ICCMapFilename(inst, filename, *entry)
			case 25:{int name=memory.readLong(p+4); // ICCMapTypeCreator(inst, type, creator, filename, *entry)
				return mapped(PpcInternetConfig.byTypeCreator(mappings(0),memory.readLong(p+12),memory.readLong(p+8),name==0?"":key(name)),memory.readLong(p));}
			case 28:return mapped(PpcInternetConfig.byFilename(mappings(memory.readLong(p+8)),key(memory.readLong(p+4))),memory.readLong(p)); // ICCMapEntriesFilename(inst, entries, filename, *entry)
			case 29:{int name=memory.readLong(p+4); // ICCMapEntriesTypeCreator(inst, entries, type, creator, filename, *entry)
				return mapped(PpcInternetConfig.byTypeCreator(mappings(memory.readLong(p+16)),memory.readLong(p+12),memory.readLong(p+8),name==0?"":key(name)),memory.readLong(p));}
			case 18:out(memory.readLong(p),mappings(memory.readLong(p+4)).size());return NO_ERR; // ICCCountMapEntries(inst, entries, *count)
			case 19:{List<PpcInternetConfig.Entry> list=mappings(memory.readLong(p+12));int i=memory.readLong(p+8); // ICCGetIndMapEntry(inst, entries, index, *pos, *entry)
				if(i<1||i>list.size())return PREF_NOT_FOUND;PpcInternetConfig.Entry e=list.get(i-1);out(memory.readLong(p+4),e.pos);return mapped(e,memory.readLong(p));}
			case 20:{int pos=memory.readLong(p+4);for(PpcInternetConfig.Entry e:mappings(memory.readLong(p+8)))if(e.pos==pos)return mapped(e,memory.readLong(p));return PARAM_ERR;} // ICCGetMapEntry(inst, entries, pos, *entry)
			default:return Components68k.BAD_COMPONENT_SELECTOR;
		}
	}

	/** Argument bytes of each selector that has a fixed list (-1: not checked): ICCGetPref 16, ICCLaunchURL 20 ... */
	private static final int[] PARAM_SIZES={4,0,6,4,4,2,16,16,4,8,0,4,4,4,4,4,24,20,8,16,12,-1,-1,-1,8,16,12,12,12,20,-1,4,8,-1,-1,6,12,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,0,-1,8,8};
	private String key(int p) throws M68kCpuException {byte[] b=tb.readPascalBytes(p);return b.length<1?"":StackTextEncoding.ROMAN.decode(java.util.Arrays.copyOfRange(b,1,b.length));}
	private void out(int at,int value) throws M68kCpuException {if(at!=0)memory.writeLong(at,value);}
	private void writePascal(int at,byte[] s) throws M68kCpuException {int n=Math.min(255,s.length);memory.writeByte(at,n);memory.load(at+1,java.util.Arrays.copyOf(s,n));}

	// ------------------------------------------------------------------ the preferences (the file PowerPC programs use)

	private int getPref(String key,int attr,int buf,int sizePtr) throws M68kCpuException {
		byte[][] v=get(key);
		if(Mac68kApi.TRACE)System.err.println("[68K]   IC key \""+key+"\""+(v==null?" (none)":" "+v[1].length+" bytes"));
		if(v==null)return PREF_NOT_FOUND;
		if(attr!=0)memory.load(attr,v[0]);int size=sizePtr==0?0:memory.readLong(sizePtr);
		if(buf!=0)memory.load(buf,java.util.Arrays.copyOf(v[1],Math.max(0,Math.min(size,v[1].length))));
		if(sizePtr!=0)memory.writeLong(sizePtr,v[1].length);
		return buf!=0&&size<v[1].length?TRUNCATED:NO_ERR;
	}
	private File file(){File folder=tb.files==null?null:tb.files.specialFolder(0x70726566,false);return folder==null?null:new File(folder,"Internet Preferences");} // 'pref'
	private ResourceFile68k open(){File f=file();if(f==null||!f.isFile())return null;try{return ResourceFile68k.open(f);}catch(IOException|RuntimeException e){return null;}}
	private static ResourceFile68k.Resource find(ResourceFile68k rf,String key){
		if(rf==null)return null;
		for(ResourceFile68k.Resource r:rf.ofType(ICRP))if(key.equals(r.name))return r;
		for(ResourceFile68k.Resource r:rf.ofType(ICRP))if(key.equalsIgnoreCase(r.name))return r; // keys compare without case
		return null;
	}
	/** {attr, value} of a key: the file's, else the built-in default; null when neither has it. */
	byte[][] get(String key){
		ResourceFile68k.Resource r=find(open(),key);
		if(r!=null){byte[] d=r.data();if(d!=null&&d.length>=4)return new byte[][]{java.util.Arrays.copyOf(d,4),java.util.Arrays.copyOfRange(d,4,d.length)};}
		byte[] v=PpcInternetConfig.defaults(key);return v==null?null:new byte[][]{new byte[4],v};
	}
	private int set(String key,int attr,byte[] value){
		File f=file();if(f==null)return PARAM_ERR;
		if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,requester()))return -54; // permErr
		try{
			ResourceFile68k rf=open();boolean created=rf==null;
			if(created){if(!f.exists()&&!f.createNewFile())return -36;rf=new ResourceFile68k(f);}
			ResourceFile68k.Resource old=find(rf,key);
			byte[] a=new byte[4];
			if(attr==ATTR_NO_CHANGE){if(old!=null&&old.data().length>=4)a=java.util.Arrays.copyOf(old.data(),4);}
			else{a[0]=(byte)(attr>>>24);a[1]=(byte)(attr>>>16);a[2]=(byte)(attr>>>8);a[3]=(byte)attr;}
			byte[] d=new byte[4+value.length];System.arraycopy(a,0,d,0,4);System.arraycopy(value,0,d,4,value.length);
			int id=128;if(old!=null)id=old.id;else for(ResourceFile68k.Resource r:rf.ofType(ICRP))id=Math.max(id,r.id+1);
			if(old!=null)rf.remove(old);
			rf.add(new ResourceFile68k.Resource(ICRP,id,old!=null?old.name:key,0,d));
			rf.write();
			if(created)FinderInfo68k.write(f,new byte[]{'I','C','A','p','I','C','A','p',0,0,0,0,0,0,0,0});
			seed++;return NO_ERR;
		}catch(IOException|RuntimeException e){return -36;} // ioErr
	}
	private int delete(String key){
		ResourceFile68k rf=open();ResourceFile68k.Resource r=find(rf,key);if(r==null)return PREF_NOT_FOUND;
		if(!Sandbox.allowFile(file(),Sandbox.Access.WRITE,requester()))return -54;
		try{rf.remove(r);rf.write();seed++;return NO_ERR;}catch(IOException|RuntimeException e){return -36;}
	}
	private List<String> keys(){List<String> keys=new ArrayList<String>();ResourceFile68k rf=open();if(rf!=null)for(ResourceFile68k.Resource r:rf.ofType(ICRP))if(r.name!=null)keys.add(r.name);return keys;}

	// ------------------------------------------------------------------ file mappings (read only)

	/** The packed map entries of an entries Handle, or of the "Mapping" preference for 0. */
	private List<PpcInternetConfig.Entry> mappings(int handle) throws M68kCpuException {
		byte[] v;if(handle!=0)v=tb.api.handleContents(handle);else{byte[][] m=get("Mapping");v=m==null?null:m[1];}
		return v==null?new ArrayList<PpcInternetConfig.Entry>():PpcInternetConfig.entries(v);
	}
	/** Fills an ICMapEntry (22 fixed bytes and five Str255s), or answers prefNotFound. */
	private int mapped(PpcInternetConfig.Entry e,int at) throws M68kCpuException {
		if(e==null)return PREF_NOT_FOUND;if(at==0)return PARAM_ERR;
		memory.fill(at,PpcInternetConfig.ENTRY_SIZE,0);
		memory.writeWord(at,e.total);memory.writeWord(at+2,22);memory.writeWord(at+4,e.version);memory.writeLong(at+6,e.type);memory.writeLong(at+10,e.creator);memory.writeLong(at+14,e.postCreator);memory.writeLong(at+18,e.flags);
		for(int i=0;i<5;i++)writePascal(at+22+i*256,e.raw[i]);
		return NO_ERR;
	}

	// ------------------------------------------------------------------ URLs

	private static final class Found{int start,end;String url;}
	/** The URL of (hint, data, len, *selStart, *selEnd) at {@code p} (the last argument first), the selection updated to it as IC does. */
	private Found find(int p) throws M68kCpuException {
		int selEnd=memory.readLong(p),selStart=memory.readLong(p+4),len=memory.readLong(p+8),data=memory.readLong(p+12),hintPtr=memory.readLong(p+16);
		if(data==0||len<0)return null;
		int s=selStart==0?0:memory.readLong(selStart),e=selEnd==0?s:memory.readLong(selEnd);
		if(s<0||e<s||e>len)return null;
		String hint=hintPtr==0?"":new String(java.util.Arrays.copyOfRange(tb.readPascalBytes(hintPtr),1,1+memory.readByte(hintPtr)),StandardCharsets.ISO_8859_1);
		PpcUrlAccess.Found f=PpcUrlAccess.findUrl(memory.readBytes(data,len),s,e,hint);
		if(selStart!=0)memory.writeLong(selStart,f.start);if(selEnd!=0)memory.writeLong(selEnd,f.end);
		Found r=new Found();r.start=f.start;r.end=f.end;r.url=f.url;return r;
	}
	/** The URL to the host's browser (or mail program) - only when the Sandbox lets the program open it. */
	private int launch(String url){
		if(Mac68kApi.TRACE)System.err.println("[68K] ICLaunchURL "+url);
		try{java.net.URI uri=new java.net.URI(url);
			if(uri.getScheme()==null||!Sandbox.allowOther(requester(),Sandbox.OPEN_URL,url)||!java.awt.Desktop.isDesktopSupported())return PREF_NOT_FOUND;
			java.awt.Desktop.getDesktop().browse(uri);return NO_ERR;}
		catch(Exception e){return PREF_NOT_FOUND;}
	}
	/** Who asks: the program, or for an external the stack it belongs to. */
	private String requester(){return tb.app!=null?tb.app.name:Sandbox.scriptRequester();}
}

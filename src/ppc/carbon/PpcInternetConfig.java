import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Internet Config for PowerPC programs (InternetConfigLib: ICStart, ICGetPref, ICMapFilename ...), kept in the startup disk's
 * "System Folder:Preferences:Internet Preferences" as Internet Config 2 keeps it: one 'ICRP' resource per preference, named by
 * its key, the data a 4-byte ICAttr and the value. The 68K Internet Config Extension (and its application) read and write the
 * same file, so preferences set there are seen here and the other way round. Without that file the built-in defaults answer
 * (see {@link #defaults}). Interarchy 4.0 checks its file mappings at every start-up: with no "Mapping" preference it reported
 * all of them wrong, and its "Set Defaults" was thrown away.
 */
final class PpcInternetConfig {
	static final int NOT_FOUND=-666,TRUNCATED=-670,PARAM_ERR=-50; // icPrefNotFoundErr, icTruncatedErr (-667 is icPermErr)
	private static final int ICRP=0x49435250,ATTR_NO_CHANGE=0xffffffff;
	private static final String MAPPING="Mapping";
	/** ICMapEntry in memory: the fixed part (22 bytes) and five Str255s. */
	static final int ENTRY_SIZE=22+5*256;
	static final int MAP_NOT_INCOMING=0x10,MAP_NOT_OUTGOING=0x20;
	private static int seed=1;

	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcInternetConfig(PpcRuntime rt){this.rt=rt;memory=rt.memory;}

	// ------------------------------------------------------------------ the preferences file

	private File file(){
		File folder=rt.toolbox.files==null?null:rt.toolbox.files.specialFolder(0x70726566,false); // 'pref'
		return folder==null?null:new File(folder,"Internet Preferences");
	}
	private ResourceFile68k open() {
		File f=file();if(f==null||!f.isFile())return null;
		try{return ResourceFile68k.open(f);}catch(IOException e){return null;}
	}
	private static ResourceFile68k.Resource find(ResourceFile68k rf,String key){
		if(rf==null)return null;
		for(ResourceFile68k.Resource r:rf.ofType(ICRP))if(key.equals(r.name))return r;
		for(ResourceFile68k.Resource r:rf.ofType(ICRP))if(key.equalsIgnoreCase(r.name))return r; // keys compare without case
		return null;
	}
	/** {attr, value} of a key, or null. */
	byte[][] get(String key){
		ResourceFile68k.Resource r=find(open(),key);
		if(r!=null){byte[] d=r.data();if(d.length>=4)return new byte[][]{java.util.Arrays.copyOf(d,4),java.util.Arrays.copyOfRange(d,4,d.length)};}
		byte[] v=defaults(key);return v==null?null:new byte[][]{new byte[4],v};
	}
	int set(String key,int attr,byte[] value){
		File f=file();if(f==null)return PARAM_ERR;
		if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,rt.toolbox.name))return -54; // permErr
		try{
			boolean created=!f.exists();if(created&&!f.createNewFile())return -36;
			ResourceFile68k rf=created?new ResourceFile68k(f):ResourceFile68k.open(f);
			ResourceFile68k.Resource old=find(rf,key);
			byte[] a=new byte[4];
			if(attr==ATTR_NO_CHANGE){if(old!=null&&old.data().length>=4)a=java.util.Arrays.copyOf(old.data(),4);}
			else{a[0]=(byte)(attr>>>24);a[1]=(byte)(attr>>>16);a[2]=(byte)(attr>>>8);a[3]=(byte)attr;}
			byte[] d=new byte[4+value.length];System.arraycopy(a,0,d,0,4);System.arraycopy(value,0,d,4,value.length);
			int id=old!=null?old.id:freeId(rf);
			if(old!=null)rf.remove(old);
			rf.add(new ResourceFile68k.Resource(ICRP,id,old!=null?old.name:key,0,d));
			rf.write();
			if(created)FinderInfo68k.write(f,new byte[]{'I','C','A','p','I','C','A','p',0,0,0,0,0,0,0,0});
			seed++;return 0;
		}catch(IOException e){return -36;} // ioErr
	}
	int delete(String key){
		File f=file();if(f==null||!f.isFile())return NOT_FOUND;
		try{ResourceFile68k rf=ResourceFile68k.open(f);ResourceFile68k.Resource r=find(rf,key);if(r==null)return NOT_FOUND;rf.remove(r);rf.write();seed++;return 0;}
		catch(IOException e){return -36;}
	}
	List<String> keys(){
		List<String> keys=new ArrayList<String>();ResourceFile68k rf=open();
		if(rf!=null)for(ResourceFile68k.Resource r:rf.ofType(ICRP))if(r.name!=null)keys.add(r.name);
		return keys;
	}
	int seed(){return seed;}
	private static int freeId(ResourceFile68k rf){int id=128;for(ResourceFile68k.Resource r:rf.ofType(ICRP))id=Math.max(id,r.id+1);return id;}

	/** Without a preferences file: empty user strings, and "Helper•<scheme>" for the web schemes, an ICAppSpec (creator, Str63
	 *  name) standing for the host's browser. */
	static byte[] defaults(String k){
		if(k.equals("RealName")||k.equals("Organization")||k.equals("Email")||k.equals("WWWHomePage"))return new byte[1]; // set but empty, as in a fresh Internet Config (Acrobat Reader 5.0 reads RealName)
		if(k.equalsIgnoreCase(MAPPING))return defaultMapping();
		if(!k.equals("Helper•http")&&!k.equals("Helper•https")&&!k.equals("Helper•ftp"))return null;
		byte[] spec=new byte[68];spec[0]='h';spec[1]='z';spec[2]='W';spec[3]='B';byte[] name="Web Browser".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);spec[4]=(byte)name.length;System.arraycopy(name,0,spec,5,name.length);
		return spec;
	}

	// ------------------------------------------------------------------ file mappings

	/** A fresh Internet Config's file mappings (the common extensions of the time): Anarchie 1.6 refuses to start ("install
	 *  the component ... Internet Config 1.1 or later") when the "Mapping" preference is missing. Rows: extension, type,
	 *  creator, flags (1 binary, 2 resource fork, 4 data fork, 0x10 not incoming, 0x20 not outgoing), application, MIME type, name. */
	private static final String[][] DEFAULT_MAPPINGS={
		{".txt","TEXT","ttxt","4","SimpleText","text/plain","Text"},
		{".text","TEXT","ttxt","4","SimpleText","text/plain","Text"},
		{".html","TEXT","ttxt","4","SimpleText","text/html","HTML"},
		{".htm","TEXT","ttxt","4","SimpleText","text/html","HTML"},
		{".c","TEXT","ttxt","4","SimpleText","text/plain","C Source"},
		{".h","TEXT","ttxt","4","SimpleText","text/plain","C Header"},
		{".hqx","TEXT","SITx","4","StuffIt Expander","application/mac-binhex40","BinHex"},
		{".sit","SIT!","SITx","5","StuffIt Expander","application/x-stuffit","StuffIt Archive"},
		{".sea","APPL","????","7","","application/x-sea","Self-Extracting Archive"},
		{".cpt","PACT","CPCT","5","Compact Pro","application/mac-compactpro","Compact Pro Archive"},
		{".bin","BINA","SITx","5","StuffIt Expander","application/macbinary","MacBinary"},
		{".uu","TEXT","SITx","4","StuffIt Expander","text/plain","UUEncode"},
		{".zip","ZIP ","SITx","5","StuffIt Expander","application/zip","ZIP Archive"},
		{".gz","Gzip","SITx","5","StuffIt Expander","application/x-gzip","GNU Zip Archive"},
		{".Z","ZIVU","SITx","5","StuffIt Expander","application/x-compress","Unix Compress Archive"},
		{".tar","TARF","SITx","5","StuffIt Expander","application/x-tar","Unix Tape Archive"},
		{".lzh","LHA ","LARC","5","MacLHA","application/x-lha","LHA Archive"},
		{".gif","GIFf","ttxt","5","SimpleText","image/gif","GIF Picture"},
		{".jpg","JPEG","ttxt","5","SimpleText","image/jpeg","JPEG Picture"},
		{".jpeg","JPEG","ttxt","5","SimpleText","image/jpeg","JPEG Picture"},
		{".png","PNGf","ttxt","5","SimpleText","image/png","PNG Picture"},
		{".tif","TIFF","ttxt","5","SimpleText","image/tiff","TIFF Picture"},
		{".tiff","TIFF","ttxt","5","SimpleText","image/tiff","TIFF Picture"},
		{".pict","PICT","ttxt","5","SimpleText","image/x-pict","PICT Picture"},
		{".pct","PICT","ttxt","5","SimpleText","image/x-pict","PICT Picture"},
		{".bmp","BMPp","ttxt","5","SimpleText","image/bmp","Windows Bitmap"},
		{".mov","MooV","TVOD","5","MoviePlayer","video/quicktime","QuickTime Movie"},
		{".qt","MooV","TVOD","5","MoviePlayer","video/quicktime","QuickTime Movie"},
		{".mpg","MPEG","TVOD","5","MoviePlayer","video/mpeg","MPEG Movie"},
		{".avi","VfW ","TVOD","5","MoviePlayer","video/x-msvideo","AVI Movie"},
		{".aif","AIFF","TVOD","5","MoviePlayer","audio/x-aiff","AIFF Sound"},
		{".aiff","AIFF","TVOD","5","MoviePlayer","audio/x-aiff","AIFF Sound"},
		{".au","ULAW","TVOD","5","MoviePlayer","audio/basic","Sun Audio"},
		{".wav","WAVE","TVOD","5","MoviePlayer","audio/x-wav","Windows Sound"},
		{".mid","Midi","TVOD","5","MoviePlayer","audio/x-midi","MIDI"},
		{".mp3","MPG3","TVOD","5","MoviePlayer","audio/mpeg","MP3 Sound"},
		{".pdf","PDF ","CARO","5","Acrobat Reader","application/pdf","Portable Document Format"},
		{".ps","TEXT","ttxt","4","SimpleText","application/postscript","PostScript"},
		{".rtf","TEXT","MSWD","4","Microsoft Word","application/rtf","Rich Text Format"},
		{".doc","WDBN","MSWD","5","Microsoft Word","application/msword","Word Document"},
		{".xls","XLS ","XCEL","5","Microsoft Excel","application/vnd.ms-excel","Excel Spreadsheet"},
		{".exe","DEXE","????","5","","application/octet-stream","DOS Program"},
	};
	private static byte[] defaultMapping(){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		for(String[] m:DEFAULT_MAPPINGS){
			java.io.ByteArrayOutputStream e=new java.io.ByteArrayOutputStream();
			e.write(0);e.write(0);e.write(0);e.write(22);e.write(0);e.write(0); // totalLength (filled below), fixedLength, version
			for(String four:new String[]{m[1],m[2]})e.write(four.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),0,4); // type, creator
			e.write(0);e.write(0);e.write(0);e.write(0); // no post-processing application
			int flags=Integer.parseInt(m[3]);e.write(0);e.write(0);e.write(0);e.write(flags);
			for(String str:new String[]{m[0],m[4],"",m[5],m[6]}){byte[] b=str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);e.write(b.length);e.write(b,0,b.length);}
			byte[] b=e.toByteArray();b[0]=(byte)(b.length>>>8);b[1]=(byte)b.length;out.write(b,0,b.length);
		}
		return out.toByteArray();
	}

	/** One packed map entry: where it starts in the "Mapping" value and its fields. */
	static final class Entry {
		int pos,total,version,type,creator,postCreator,flags;String[] strings=new String[5];byte[][] raw=new byte[5][];
		String extension(){return strings[0];}
	}
	/** The packed entries of a "Mapping" value: totalLength, fixedLength, version, type, creator, postCreator, flags, then the
	 *  Pascal strings extension, creator application, post-processing application, MIME type and entry name. */
	static List<Entry> entries(byte[] v){
		List<Entry> list=new ArrayList<Entry>();
		for(int p=0;p+22<=v.length;){
			int total=u2(v,p),fixed=u2(v,p+2);if(total<22||fixed<22||p+total>v.length)break;
			Entry e=new Entry();e.pos=p;e.total=total;e.version=u2(v,p+4);e.type=u4(v,p+6);e.creator=u4(v,p+10);e.postCreator=u4(v,p+14);e.flags=u4(v,p+18);
			int s=p+fixed;
			for(int i=0;i<5;i++){if(s>=p+total){e.raw[i]=new byte[0];e.strings[i]="";continue;}int n=v[s]&255;n=Math.min(n,p+total-s-1);e.raw[i]=java.util.Arrays.copyOfRange(v,s+1,s+1+n);e.strings[i]=StackTextEncoding.ROMAN.decode(e.raw[i]);s+=1+n;}
			list.add(e);p+=total;
		}
		return list;
	}
	/** Fills an ICMapEntry. */
	void writeEntry(int at,Entry e) throws M68kCpuException {
		memory.fill(at,ENTRY_SIZE,0);
		memory.writeWord(at,e.total);memory.writeWord(at+2,22);memory.writeWord(at+4,e.version);memory.writeLong(at+6,e.type);memory.writeLong(at+10,e.creator);memory.writeLong(at+14,e.postCreator);memory.writeLong(at+18,e.flags);
		for(int i=0;i<5;i++){byte[] s=e.raw[i];int n=Math.min(255,s.length);memory.writeByte(at+22+i*256,n);memory.load(at+23+i*256,java.util.Arrays.copyOf(s,n));}
	}
	/** An ICMapEntry packed the way the "Mapping" value keeps it. */
	byte[] packEntry(int at) throws M68kCpuException {
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		byte[] fixed=memory.readBytes(at,22);out.write(fixed,0,22);
		for(int i=0;i<5;i++){int n=memory.readByte(at+22+i*256);out.write(n);out.write(memory.readBytes(at+23+i*256,n),0,n);}
		byte[] b=out.toByteArray();b[0]=(byte)(b.length>>>8);b[1]=(byte)b.length;b[2]=0;b[3]=22;
		return b;
	}
	/** ICMapFilename: the entry whose extension ends the name (the longest such), skipping the ones not for incoming files. */
	static Entry byFilename(List<Entry> list,String name){
		Entry best=null;String lower=name.toLowerCase(java.util.Locale.ROOT);
		for(Entry e:list){String ext=e.extension();if(ext.isEmpty()||(e.flags&MAP_NOT_INCOMING)!=0)continue;
			if(lower.endsWith(ext.toLowerCase(java.util.Locale.ROOT))&&(best==null||ext.length()>best.extension().length()))best=e;}
		return best;
	}
	/** ICMapTypeCreator: type and creator, then type alone, the file name's extension deciding among several. */
	static Entry byTypeCreator(List<Entry> list,int type,int creator,String name){
		Entry exact=null,typeOnly=null;String lower=name==null?"":name.toLowerCase(java.util.Locale.ROOT);
		for(Entry e:list){if((e.flags&MAP_NOT_OUTGOING)!=0||e.type!=type)continue;
			boolean extMatch=!e.extension().isEmpty()&&lower.endsWith(e.extension().toLowerCase(java.util.Locale.ROOT));
			if(e.creator==creator){if(exact==null||extMatch)exact=e;}
			else if(typeOnly==null||extMatch)typeOnly=e;}
		return exact!=null?exact:typeOnly;
	}

	private static int u2(byte[] b,int at){return ((b[at]&255)<<8)|(b[at+1]&255);}
	private static int u4(byte[] b,int at){return ((b[at]&255)<<24)|((b[at+1]&255)<<16)|((b[at+2]&255)<<8)|(b[at+3]&255);}
}

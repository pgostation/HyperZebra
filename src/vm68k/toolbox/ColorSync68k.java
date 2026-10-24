import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ColorSync 3.0 (CMApplication.h, CMICCProfile.h) at trap level: _CMTrap $ABEE with MOVE.L #argumentBytes<<16|selector,D0
 * and Pascal arguments on the stack below a CMError (long) result. The argument byte counts in the headers' inline words are
 * not always right (CMGetProfileDescriptions, NCWConcatColorWorld), so each routine pops what its prototype pushes.
 * <p>
 * Profiles are real ICC version 2 profiles held as a header and a list of tagged elements (elements sharing data, as
 * CMSetProfileElementReference makes them, share one byte array); they are read from and written back to their
 * CMProfileLocation (file, Handle, Ptr, access procedure, buffer). The system profile is a built-in display profile, "Generic
 * RGB Profile" (sRGB primaries adapted to D50, gamma 1.8 like the Mac's standard display), with generic Gray, Lab, XYZ and
 * CMYK profiles as the default profiles of those spaces; their bytes are built here, not copied from Apple's profiles. The
 * ColorSync Profiles folder is the System Folder's "ColorSync Profiles"; searches and iterations see its profile files and the
 * built-in profiles. There is one CMM ('appl') and its matching is the identity: colours, PixMaps and bitmaps come back
 * unchanged and all in gamut. The colour-space conversions are the real formulas on the ColorSync component formats (XYZ
 * components u1.15, L 0..100 and a/b/u/v -128..127.996 over 0..65535, hue a fraction of the circle).
 */
final class ColorSync68k extends TrapManager68k {
	ColorSync68k(Toolbox68k tb){super(tb);}

	static final int NO_ERR=0,PARAM_ERR=-50,MEM_FULL_ERR=-108,FNF_ERR=-43,DIR_NF_ERR=-120,IO_ERR=-36,PERM_ERR=-54;
	static final int CM_PROFILE_ERROR=-170,CM_METHOD_ERROR=-171,CM_PROFILE_NOT_FOUND=-176,CM_CANT_CONCATENATE_ERROR=-178,CM_CANT_DELETE_PROFILE=-180,
		CM_ELEMENT_TAG_NOT_FOUND=-4200,CM_INDEX_RANGE_ERR=-4201,CM_FATAL_PROFILE_ERR=-4203,CM_INVALID_PROFILE_LOCATION=-4205,CM_INVALID_SEARCH=-4206,
		CM_INVALID_COLOR_SPACE=-4209,CM_INVALID_SRC_MAP=-4210,CM_INVALID_DST_MAP=-4211,CM_RANGE_OVERFLOW=-4214,CM_NAMED_COLOR_NOT_FOUND=-4216;
	/** CMGetColorSyncVersion / Gestalt 'cmtc'. */
	static final int VERSION=0x0300;
	static final int MAGIC=0x61637370; // 'acsp'
	static final int LOC_NONE=0,LOC_FILE=1,LOC_HANDLE=2,LOC_PTR=3,LOC_PROCEDURE=4,LOC_PATH=5,LOC_BUFFER=6;
	/** CMProfileLocation: locType + the largest union member (a 256-byte path); the 1.0-era layout was 72 bytes (an FSSpec). */
	static final int LOCATION_SIZE=258;
	private static final int CM_OPEN_READ_SPOOL=1,CM_OPEN_WRITE_SPOOL=2,CM_READ_SPOOL=3,CM_WRITE_SPOOL=4,CM_CLOSE_SPOOL=5;
	private static final int CM_OPEN_READ_ACCESS=1,CM_OPEN_WRITE_ACCESS=2,CM_READ_ACCESS=3,CM_WRITE_ACCESS=4,CM_CLOSE_ACCESS=5;
	private static final int D50_X=31595,D50_Y=32768,D50_Z=27030; // u1.15 (0.9642, 1.0, 0.8249)

	// ------------------------------------------------------------------ profiles

	/** An element's data; tags that reference each other hold the same instance. */
	static final class Data{byte[] bytes;Data(byte[] b){bytes=b;}}
	static final class Element{final int tag;Data data;Element(int tag,Data data){this.tag=tag;this.data=data;}}
	/** Where a profile lives: the CMProfileLocation bytes the program gave (locType, then the union). */
	static final class Location{
		final byte[] raw;
		Location(byte[] raw){this.raw=raw.length>=LOCATION_SIZE?raw:java.util.Arrays.copyOf(raw,LOCATION_SIZE);}
		int type(){return (short)be16(raw,0);}
		int long1(){return be32(raw,2);}
		int long2(){return be32(raw,6);}
		static Location of(int type,int a,int b){byte[] r=new byte[LOCATION_SIZE];put16(r,0,type);put32(r,2,a);put32(r,6,b);return new Location(r);}
		static Location none(){return of(LOC_NONE,0,0);}
	}
	/** An open profile (a CMProfileRef): the header and the elements, or a ColorSync 1.0 profile's raw bytes. */
	static final class Profile{
		final int ref;int refCount=1;byte[] header=new byte[128];final List<Element> elements=new ArrayList<Element>();byte[] v1;Location location;boolean modified;
		Profile(int ref){this.ref=ref;}
		Element element(int tag){for(Element e:elements)if(e.tag==tag)return e;return null;}
		boolean shared(Element e){for(Element o:elements)if(o!=e&&o.data==e.data)return true;return false;}
		int cls(){return be32(header,12);}
		int space(){return be32(header,16);}
		int pcs(){return be32(header,20);}
	}
	private final Map<Integer,Profile> profiles=new HashMap<Integer,Profile>();
	Profile profile(int ref){return profiles.get(ref);}

	/** Builds a profile from ICC bytes (null when they are not a profile); a data block referenced twice becomes one shared Data. */
	static boolean parse(Profile p,byte[] b){
		if(b.length>=132&&be32(b,36)==MAGIC){
			int count=be32(b,128);if(count<0||132L+12L*count>b.length)return false;
			System.arraycopy(b,0,p.header,0,128);p.elements.clear();p.v1=null;
			Map<Long,Data> byRange=new HashMap<Long,Data>();
			for(int i=0;i<count;i++){
				int t=132+12*i,tag=be32(b,t),offset=be32(b,t+4),size=be32(b,t+8);
				if(offset<0||size<0||(long)offset+size>b.length)return false;
				long key=(long)offset<<32|size&0xffffffffL;Data d=byRange.get(key);
				if(d==null){d=new Data(java.util.Arrays.copyOfRange(b,offset,offset+size));byRange.put(key,d);}
				p.elements.add(new Element(tag,d));
			}
			return true;
		}
		// a ColorSync 1.0 profile (CMProfile: CMHeader, chromaticities, response, name): kept as it is
		if(b.length>=60&&be32(b,0)<=b.length&&be32(b,0)>=60&&be32(b,8)==0x00000100){p.v1=b.clone();p.elements.clear();java.util.Arrays.fill(p.header,(byte)0);System.arraycopy(b,0,p.header,0,60);return true;}
		return false;
	}
	/** The profile as ICC bytes: header (size field updated), tag table, element data aligned to longs, shared data once. */
	static byte[] bytes(Profile p){
		if(p.v1!=null)return p.v1.clone();
		List<Data> distinct=new ArrayList<Data>();for(Element e:p.elements)if(!distinct.contains(e.data))distinct.add(e.data);
		int at=132+12*p.elements.size();int[] offsets=new int[distinct.size()];
		for(int i=0;i<distinct.size();i++){at=at+3&~3;offsets[i]=at;at+=distinct.get(i).bytes.length;}
		byte[] out=new byte[at];
		System.arraycopy(p.header,0,out,0,128);put32(out,0,at);put32(out,128,p.elements.size());
		for(int i=0;i<p.elements.size();i++){Element e=p.elements.get(i);int d=distinct.indexOf(e.data);put32(out,132+12*i,e.tag);put32(out,136+12*i,offsets[d]);put32(out,140+12*i,e.data.bytes.length);}
		for(int i=0;i<distinct.size();i++)System.arraycopy(distinct.get(i).bytes,0,out,offsets[i],distinct.get(i).bytes.length);
		return out;
	}
	private Profile newProfile() throws M68kCpuException {int ref=memory.allocate(8,4);memory.writeLong(ref,fourcc("prof"));memory.writeLong(ref+4,0);Profile p=new Profile(ref);profiles.put(ref,p);return p;}
	private void closeProfile(Profile p){if(--p.refCount>0)return;profiles.remove(p.ref);memory.free(p.ref);}
	/** Opens a profile from ICC bytes with the given location; null when the bytes are not a profile. */
	private Profile open(byte[] b,Location loc) throws M68kCpuException {
		Profile p=newProfile();if(!parse(p,b)){closeProfile(p);return null;}p.location=loc;return p;
	}

	// ------------------------------------------------------------------ built-in profiles

	private static int s15(double v){return (int)Math.round(v*65536);}
	private static byte[] xyzType(double x,double y,double z){byte[] b=new byte[20];put32(b,0,fourcc("XYZ "));put32(b,8,s15(x));put32(b,12,s15(y));put32(b,16,s15(z));return b;}
	private static byte[] gammaCurve(double gamma){byte[] b=new byte[14];put32(b,0,fourcc("curv"));put32(b,8,1);put16(b,12,(int)Math.round(gamma*256));return b;}
	private static byte[] textType(String s){byte[] t=roman(s);byte[] b=new byte[8+t.length+1];put32(b,0,fourcc("text"));System.arraycopy(t,0,b,8,t.length);return b;}
	/** A 'desc' (textDescriptionType) element: the ASCII name, no Unicode name, the Mac script name (Roman). */
	static byte[] descType(byte[] ascii,byte[] unicode,int script,byte[] mac){
		int unicodeChars=unicode==null?0:unicode.length/2;
		byte[] b=new byte[12+ascii.length+1+8+unicodeChars*2+3+67];
		put32(b,0,fourcc("desc"));put32(b,8,ascii.length+1);System.arraycopy(ascii,0,b,12,ascii.length);
		int at=12+ascii.length+1;put32(b,at+4,unicodeChars);if(unicodeChars>0)System.arraycopy(unicode,0,b,at+8,unicodeChars*2);
		at+=8+unicodeChars*2;put16(b,at,script);int n=mac==null?0:Math.min(66,mac.length);b[at+2]=(byte)n;if(n>0)System.arraycopy(mac,0,b,at+3,n);
		return b;
	}
	/** Makes a version 2.1 profile of {@code cls}/{@code space}/{@code pcs} with a description, white point, copyright and the given elements. */
	static byte[] makeProfile(String cls,String space,String pcs,String description,Object... tagsAndData){
		Profile p=new Profile(0);byte[] h=p.header;
		put32(h,4,fourcc("appl"));put32(h,8,0x02100000);put32(h,12,fourcc(cls));put32(h,16,fourcc(space));put32(h,20,fourcc(pcs));
		put16(h,24,1999);put16(h,26,6);put16(h,28,1);put32(h,36,MAGIC);put32(h,40,fourcc("APPL"));
		put32(h,68,s15(0.9642));put32(h,72,s15(1.0));put32(h,76,s15(0.8249));
		byte[] name=roman(description);
		p.elements.add(new Element(fourcc("desc"),new Data(descType(name,null,0,name))));
		p.elements.add(new Element(fourcc("cprt"),new Data(textType("No copyright, use freely"))));
		p.elements.add(new Element(fourcc("wtpt"),new Data(xyzType(0.9642,1.0,0.8249))));
		for(int i=0;i+1<tagsAndData.length;i+=2)p.elements.add(new Element(fourcc((String)tagsAndData[i]),new Data((byte[])tagsAndData[i+1])));
		return bytes(p);
	}
	static final String GENERIC_RGB="Generic RGB Profile";
	/** The built-in profiles: the system (display) profile first. */
	static final byte[][] BUILT_IN={
		makeProfile("mntr","RGB ","XYZ ",GENERIC_RGB,"rXYZ",xyzType(0.4361,0.2225,0.0139),"gXYZ",xyzType(0.3851,0.7169,0.0971),"bXYZ",xyzType(0.1431,0.0606,0.7141),
			"rTRC",gammaCurve(1.8),"gTRC",gammaCurve(1.8),"bTRC",gammaCurve(1.8)),
		makeProfile("mntr","GRAY","XYZ ","Generic Gray Profile","kTRC",gammaCurve(1.8)),
		makeProfile("spac","Lab ","Lab ","Generic Lab Profile"),
		makeProfile("spac","XYZ ","XYZ ","Generic XYZ Profile"),
		makeProfile("prtr","CMYK","Lab ","Generic CMYK Profile"),
	};
	private final int[] builtInCopies=new int[BUILT_IN.length];
	/** A built-in profile's location: a Ptr to a permanent guest copy of its bytes. */
	private Location builtIn(int i) throws M68kCpuException {if(builtInCopies[i]==0)builtInCopies[i]=block(BUILT_IN[i]);return Location.of(LOC_PTR,builtInCopies[i],0);}

	/** The system profile's location (the built-in display profile until CMSetSystemProfile), defaults by space/use/AVID. */
	private Location systemLocation;
	private final Map<Integer,Location> defaultsBySpace=new HashMap<Integer,Location>(),defaultsByUse=new HashMap<Integer,Location>(),byAvid=new HashMap<Integer,Location>();
	private Location systemLocation() throws M68kCpuException {if(systemLocation==null)systemLocation=builtIn(0);return systemLocation;}
	private Location defaultBySpace(int space) throws M68kCpuException {
		Location l=defaultsBySpace.get(space);if(l!=null)return l;
		if(space==fourcc("RGB "))return systemLocation();
		if(space==fourcc("GRAY"))return builtIn(1);if(space==fourcc("Lab "))return builtIn(2);if(space==fourcc("XYZ "))return builtIn(3);if(space==fourcc("CMYK"))return builtIn(4);
		return null;
	}

	// ------------------------------------------------------------------ locations

	private int lastError;
	/** Reads a CMProfileLocation from the guest (only the bytes its type uses). */
	private Location readLocation(int p) throws M68kCpuException {
		int type=(short)memory.readWord(p);
		int n=type==LOC_FILE?72:type==LOC_PATH?LOCATION_SIZE:type==LOC_NONE?2:10;
		return new Location(memory.readBytes(p,n));
	}
	/** Writes a location for the program: {@code max} bytes at most (72 for the 1.0-era CMGetProfileLocation). */
	private void writeLocation(int p,Location loc,int max) throws M68kCpuException {
		if(p==0)return;int type=loc==null?LOC_NONE:loc.type();
		int n=type==LOC_FILE?72:type==LOC_PATH?LOCATION_SIZE:type==LOC_NONE?2:10;
		memory.load(p,loc==null?new byte[2]:loc.raw,0,Math.min(n,max));
	}
	private File locationFile(Location loc) throws IOException,M68kCpuException {
		if(loc.type()==LOC_FILE){int spec=block(java.util.Arrays.copyOfRange(loc.raw,2,72));try{return tb.files.fileForSpec(spec);}finally{memory.free(spec);}}
		if(loc.type()==LOC_PATH){int n=0;while(n<256&&loc.raw[2+n]!=0)n++;return tb.files.fileForClassicPath(tb.encoding.decode(java.util.Arrays.copyOfRange(loc.raw,2,2+n)));}
		return null;
	}
	/** The bytes at a location, or null with {@link #lastError} set. */
	private byte[] load(M68kCpu cpu,Location loc) throws M68kException {
		switch(loc.type()){
			case LOC_FILE:case LOC_PATH:{
				try{File f=locationFile(loc);if(f==null||!f.isFile()){lastError=FNF_ERR;return null;}
					Sandbox.checkFile(f,Sandbox.Access.READ,tb.name);return java.nio.file.Files.readAllBytes(f.toPath());}
				catch(SecurityException e){lastError=PERM_ERR;return null;}catch(IOException e){lastError=IO_ERR;return null;}}
			case LOC_HANDLE:{int h=loc.long1();if(h==0){lastError=PARAM_ERR;return null;}return handleBytes(h);}
			case LOC_PTR:{int p=loc.long1();if(p==0){lastError=PARAM_ERR;return null;}
				int size=memory.readLong(p);if(size<60||size>1<<26){lastError=CM_FATAL_PROFILE_ERR;return null;}return memory.readBytes(p,size);}
			case LOC_BUFFER:{int p=loc.long1(),size=loc.long2();if(p==0||size<0||size>1<<26){lastError=PARAM_ERR;return null;}return memory.readBytes(p,size);}
			case LOC_PROCEDURE:return loadThroughProcedure(cpu,loc.long1(),loc.long2());
			default:lastError=CM_INVALID_PROFILE_LOCATION;return null;
		}
	}
	/** CMProfileAccessUPP (command, offset, *size, data, refCon): open for reading, the header, the whole profile, close. */
	private byte[] loadThroughProcedure(M68kCpu cpu,int proc,int refCon) throws M68kException {
		if(proc==0){lastError=PARAM_ERR;return null;}
		int size=memory.allocate(4,4);
		try{
			memory.writeLong(size,0);int err=(short)call(cpu,proc,2,CM_OPEN_READ_ACCESS,4,0,4,size,4,0,4,refCon,4);if(err!=NO_ERR){lastError=err;return null;}
			byte[] head=access(cpu,proc,refCon,size,0,128);byte[] all=null;
			if(head!=null){int total=be32(head,0);if(total>=128&&total<=1<<26)all=access(cpu,proc,refCon,size,0,total);else lastError=CM_FATAL_PROFILE_ERR;}
			memory.writeLong(size,0);call(cpu,proc,2,CM_CLOSE_ACCESS,4,0,4,size,4,0,4,refCon,4);
			return all;
		}finally{memory.free(size);}
	}
	private byte[] access(M68kCpu cpu,int proc,int refCon,int sizePtr,int offset,int n) throws M68kException {
		int buffer=memory.allocate(Math.max(1,n),4);
		try{memory.writeLong(sizePtr,n);int err=(short)call(cpu,proc,2,CM_READ_ACCESS,4,offset,4,sizePtr,4,buffer,4,refCon,4);
			if(err!=NO_ERR){lastError=err;return null;}int got=Math.max(0,Math.min(n,memory.readLong(sizePtr)));return memory.readBytes(buffer,got==0?n:got);}
		finally{memory.free(buffer);}
	}
	/** Stores profile bytes at a location (CMUpdateProfile, CMCopyProfile, the unflatten calls); returns the result code. */
	private int store(M68kCpu cpu,Location loc,byte[] b) throws M68kException {
		switch(loc.type()){
			case LOC_NONE:return NO_ERR; // a temporary profile in memory
			case LOC_FILE:case LOC_PATH:{
				try{File f=locationFile(loc);if(f==null||f.getParentFile()==null||!f.getAbsoluteFile().getParentFile().isDirectory())return DIR_NF_ERR;
					Sandbox.checkFile(f,Sandbox.Access.WRITE,tb.name);boolean fresh=!f.exists();java.nio.file.Files.write(f.toPath(),b);
					if(fresh){byte[] info=new byte[16];put32(info,0,fourcc("prof"));put32(info,4,fourcc("sync"));FinderInfo68k.write(f,info);}
					return NO_ERR;}
				catch(SecurityException e){return PERM_ERR;}catch(IOException e){return IO_ERR;}}
			case LOC_HANDLE:if(loc.long1()==0)return PARAM_ERR;setHandle(loc.long1(),b);return NO_ERR;
			case LOC_PTR:if(loc.long1()==0)return PARAM_ERR;memory.load(loc.long1(),b);return NO_ERR;
			case LOC_BUFFER:if(loc.long1()==0||loc.long2()<b.length)return CM_INVALID_PROFILE_LOCATION;memory.load(loc.long1(),b);return NO_ERR;
			case LOC_PROCEDURE:{int proc=loc.long1(),refCon=loc.long2();if(proc==0)return PARAM_ERR;
				int size=memory.allocate(4,4),buffer=block(b);
				try{
					memory.writeLong(size,b.length);int err=(short)call(cpu,proc,2,CM_OPEN_WRITE_ACCESS,4,0,4,size,4,0,4,refCon,4);if(err!=NO_ERR)return err;
					memory.writeLong(size,b.length);err=(short)call(cpu,proc,2,CM_WRITE_ACCESS,4,0,4,size,4,buffer,4,refCon,4);
					memory.writeLong(size,0);int close=(short)call(cpu,proc,2,CM_CLOSE_ACCESS,4,0,4,size,4,0,4,refCon,4);
					return err!=NO_ERR?err:close;
				}finally{memory.free(size);memory.free(buffer);}}
			default:return CM_INVALID_PROFILE_LOCATION;
		}
	}
	/** Opens the profile at a location for the program (*prof); returns the result code. */
	private int openAt(M68kCpu cpu,Location loc,int out) throws M68kException {
		putLong(out,0);
		byte[] b=load(cpu,loc);if(b==null)return lastError;
		Profile p=open(b,loc);if(p==null)return CM_FATAL_PROFILE_ERR;
		putLong(out,p.ref);return NO_ERR;
	}

	// ------------------------------------------------------------------ the ColorSync Profiles folder

	/** A profile the folder calls see: a file of the ColorSync Profiles folder or a built-in profile. */
	private static final class Entry{final File file;final Location location;final byte[] header;Entry(File f,Location l,byte[] h){file=f;location=l;header=h;}}
	File colorSyncFolder(boolean create){File f=new File(tb.files.systemFolder,"ColorSync Profiles");if(create&&!f.exists())f.mkdirs();return f.isDirectory()?f:null;}
	/** The folder's profiles (files whose data fork is a version 2 profile, subfolders included) and the built-in ones. */
	private List<Entry> folderProfiles() throws M68kException {
		List<Entry> l=new ArrayList<Entry>();
		File folder=colorSyncFolder(false);
		if(folder!=null)collect(folder,l,0);
		for(int i=0;i<BUILT_IN.length;i++)l.add(new Entry(null,builtIn(i),java.util.Arrays.copyOf(BUILT_IN[i],128)));
		return l;
	}
	private void collect(File dir,List<Entry> l,int depth) throws M68kCpuException {
		File[] files=dir.listFiles();if(files==null)return;java.util.Arrays.sort(files);
		for(File f:files){
			if(f.getName().startsWith("."))continue;
			if(f.isDirectory()){if(depth<3)collect(f,l,depth+1);continue;}
			byte[] head=new byte[128];
			try(java.io.InputStream in=java.nio.file.Files.newInputStream(f.toPath())){if(in.readNBytes(head,0,128)<128||be32(head,36)!=MAGIC)continue;}catch(IOException e){continue;}
			int spec=memory.allocate(70,2);
			try{if(tb.files.fileManager.writeFSSpec(spec,f)!=NO_ERR)continue;byte[] raw=new byte[LOCATION_SIZE];put16(raw,0,LOC_FILE);System.arraycopy(memory.readBytes(spec,70),0,raw,2,70);l.add(new Entry(f,new Location(raw),head));}
			finally{memory.free(spec);}
		}
	}
	private int folderSeed(List<Entry> l){int h=17;for(Entry e:l)h=31*h+(e.file==null?0:(e.file.getPath().hashCode()^(int)e.file.lastModified()));return h|1;}

	/** A profile search's result list and its criteria (a CMSearchRecord or a profile identifier). */
	private static final class Search{byte[] record;int filter;byte[] identifier;List<Entry> found=new ArrayList<Entry>();}
	private final Map<Integer,Search> searches=new HashMap<Integer,Search>();
	/** The CMSearchRecord test: header fields selected by searchMask, then the filter procedure (true = leave the profile out). */
	private boolean matches(M68kCpu cpu,Search s,Entry e,int refCon) throws M68kException {
		if(s.identifier!=null)return matchesIdentifier(s.identifier,e.header,description(e));
		byte[] r=s.record,h=e.header;int mask=be32(r,36);
		if((mask&0x01)!=0&&be32(r,0)!=be32(h,4))return false;
		if((mask&0x02)!=0&&be32(r,4)!=be32(h,12))return false;
		if((mask&0x04)!=0&&be32(r,8)!=be32(h,16))return false;
		if((mask&0x08)!=0&&be32(r,12)!=be32(h,20))return false;
		if((mask&0x10)!=0&&be32(r,16)!=be32(h,48))return false;
		if((mask&0x20)!=0&&be32(r,20)!=be32(h,52))return false;
		if((mask&0x40)!=0&&(be32(r,24)!=be32(h,56)||be32(r,28)!=be32(h,60)))return false;
		if((mask&0x80)!=0&&be32(r,32)!=be32(h,44))return false;
		if(s.filter!=0){
			byte[] b=load(cpu,e.location);if(b==null)return false;Profile p=open(b,e.location);if(p==null)return false;
			try{if(call(cpu,s.filter,1,p.ref,4,refCon,4)!=0)return false;}finally{closeProfile(p);}
		}
		return true;
	}
	private String description(Entry e) throws M68kException {
		byte[] b=load(null,e.location);if(b==null)return "";Profile p=new Profile(0);if(!parse(p,b))return "";
		Element d=p.element(fourcc("desc"));return d==null?"":roman(asciiDescription(d.data.bytes));
	}
	private void runSearch(M68kCpu cpu,Search s,int refCon) throws M68kException {s.found.clear();for(Entry e:folderProfiles())if(matches(cpu,s,e,refCon))s.found.add(e);}
	/** CMProfileIdentifier: a profile matches when its header agrees (rendering intent aside) and, if the identifier has one, its description. */
	private static boolean matchesIdentifier(byte[] ident,byte[] header,String desc){
		for(int i=4;i<100;i++){if(i>=64&&i<68)continue;if(ident[i]!=header[i])return false;}
		int n=be32(ident,140);if(n>1){String want=roman(java.util.Arrays.copyOfRange(ident,144,144+Math.min(n-1,ident.length-144)));return want.equals(desc);}
		return true;
	}

	// ------------------------------------------------------------------ descriptions

	/** The ASCII part of a 'desc' element (without its NUL). */
	static byte[] asciiDescription(byte[] d){
		if(d.length<12)return new byte[0];
		if(be32(d,0)==fourcc("text")){int n=8;while(n<d.length&&d[n]!=0)n++;return java.util.Arrays.copyOfRange(d,8,n);}
		int count=be32(d,8);if(count<0||12L+count>d.length)return new byte[0];
		int n=count;while(n>0&&d[12+n-1]==0)n--;return java.util.Arrays.copyOfRange(d,12,12+n);
	}
	/** The Unicode part of a 'desc' element (big-endian UniChars, without a trailing NUL), or null. */
	static byte[] unicodeDescription(byte[] d){
		if(d.length<12||be32(d,0)!=fourcc("desc"))return null;
		int at=12+be32(d,8);if(at<12||at+8>d.length)return null;int count=be32(d,at+4);if(count<=0||at+8L+2L*count>d.length)return null;
		int n=count;while(n>0&&be16(d,at+8+2*(n-1))==0)n--;return java.util.Arrays.copyOfRange(d,at+8,at+8+2*n);
	}
	/** The script part of a 'desc' element: {script code, name bytes}, or null when there is none. */
	static Object[] scriptDescription(byte[] d){
		if(d.length<12||be32(d,0)!=fourcc("desc"))return null;
		int at=12+be32(d,8);if(at<12||at+8>d.length)return null;at+=8+2*be32(d,at+4);if(at<0||at+3>d.length)return null;
		int code=(short)be16(d,at),n=d[at+2]&255;if(n==0||at+3+n>d.length)return null;
		while(n>0&&d[at+3+n-1]==0)n--;
		return new Object[]{code,java.util.Arrays.copyOfRange(d,at+3,at+3+n)};
	}

	// ------------------------------------------------------------------ dispatch

	/** Argument sizes (left to right) and result bytes by selector. */
	private static final int[][] SIG=new int[0x80][];
	private static void sig(int selector,int result,int... sizes){int[] s=new int[sizes.length+1];s[0]=result;System.arraycopy(sizes,0,s,1,sizes.length);SIG[selector]=s;}
	static{
		for(int s:new int[]{0x00,0x06,0x0a,0x0c,0x13,0x14,0x16,0x1e,0x25,0x26,0x28,0x2a,0x2b,0x33,0x35,0x38,0x3e,0x3f,0x41,0x49,0x4a,0x4f,0x50,0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x59,0x63})sig(s,4,4,4,4);
		for(int s:new int[]{0x02})sig(s,4,4,4,4);
		for(int s:new int[]{0x03,0x04,0x05,0x10,0x20,0x22,0x23,0x27,0x32,0x3d,0x47,0x48,0x4b,0x4c,0x4d,0x4e,0x58,0x61,0x65})sig(s,4,4,4,4,4);
		for(int s:new int[]{0x07,0x12,0x21,0x2c,0x2d,0x2e,0x2f,0x31,0x36,0x37,0x40,0x62})sig(s,4,4,4,4,4,4);
		for(int s:new int[]{0x15,0x1a,0x1b,0x1c,0x1f,0x24,0x39,0x3a,0x3b,0x3c,0x43,0x44,0x45,0x5a,0x5b,0x5c,0x5d,0x5e,0x69,0x79})sig(s,4,4,4);
		for(int s:new int[]{0x08,0x18,0x19,0x1d,0x34,0x42,0x64,0x66})sig(s,4,4);
		sig(0x01,0,4);sig(0x0b,0,4);sig(0x29,0,4);sig(0x09,0,4,4,4);sig(0x17,0,4,4,4);sig(0x0d,0,2);sig(0x0e,0,4,4);sig(0x0f,4,4);
		sig(0x11,4,2,2,4,4);sig(0x30,4,4,4,4,4,4,4);sig(0x46,4,4,4,4,4,4,4,4);sig(0x67,4,4,4,4,4,4,4,4);sig(0x68,4,4,4,4,4,2,4,4);
	}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;
		if(selector>=SIG.length||SIG[selector]==null)return false;
		int[] s=SIG[selector];int[] a=pop(cpu,java.util.Arrays.copyOfRange(s,1,s.length));
		int r=run(cpu,selector,a);
		if(s[0]!=0)result(cpu,4,r);
		return true;
	}

	private final Map<Integer,Integer> worlds=new HashMap<Integer,Integer>(); // CMWorldRef / CMMatchRef tokens -> kind
	private int newToken(int kind) throws M68kCpuException {int t=memory.allocate(8,4);memory.writeLong(t,kind);worlds.put(t,kind);return t;}
	private void disposeToken(int t){if(worlds.remove(t)!=null)memory.free(t);}

	private int run(M68kCpu cpu,int selector,int[] a) throws M68kException {
		switch(selector){
			// --- color worlds and matching (the identity)
			case 0x00:case 0x14:{putLong(a[0],0);if(a[0]==0)return PARAM_ERR;putLong(a[0],newToken(fourcc("cwld")));return NO_ERR;} // CWNewColorWorld, NCWNewColorWorld(cw, src, dst): NIL = system profile
			case 0x15:case 0x61:{putLong(a[0],0);if(a[0]==0||a[1]==0)return PARAM_ERR; // CWConcatColorWorld(cw, set), NCWConcatColorWorld(cw, set, proc, refCon)
				int count=selector==0x15?memory.readWord(a[1]+2):memory.readLong(a[1]+12);if(count<1)return PARAM_ERR;
				putLong(a[0],newToken(fourcc("cwld")));return NO_ERR;}
			case 0x01:disposeToken(a[0]);return 0;                             // CWDisposeColorWorld
			case 0x02:return worlds.containsKey(a[0])?NO_ERR:PARAM_ERR;         // CWMatchColors: unchanged
			case 0x03:{if(!worlds.containsKey(a[0]))return PARAM_ERR;if(a[3]!=0&&a[2]>0)memory.fill(a[3],4*((a[2]+31)/32),0);return NO_ERR;} // CWCheckColors: all in gamut (bits clear)
			case 0x04:return worlds.containsKey(a[0])?(a[1]==0?CM_INVALID_SRC_MAP:NO_ERR):PARAM_ERR; // CWMatchPixMap: unchanged
			case 0x07:{if(!worlds.containsKey(a[0]))return PARAM_ERR;if(a[1]==0)return CM_INVALID_SRC_MAP;if(a[4]==0)return CM_INVALID_DST_MAP; // CWCheckPixMap: result BitMap all clear
				clearBitMap(a[4]);return NO_ERR;}
			case 0x2c:return matchBitmap(a);                                    // CWMatchBitmap
			case 0x2d:{if(!worlds.containsKey(a[0]))return PARAM_ERR;if(a[1]==0)return CM_INVALID_SRC_MAP;if(a[4]==0)return CM_INVALID_DST_MAP; // CWCheckBitmap: result bitmap all clear
				int image=memory.readLong(a[4]),h=memory.readLong(a[4]+8),rb=memory.readLong(a[4]+12);if(image!=0&&h>0&&rb>0&&(long)h*rb<1<<26)memory.fill(image,h*rb,0);return NO_ERR;}
			case 0x1a:{if(!worlds.containsKey(a[0]))return PARAM_ERR;if(a[1]!=0){memory.fill(a[1],20,0);memory.writeLong(a[1],1);memory.writeLong(a[1]+4,fourcc("appl"));memory.writeLong(a[1]+8,VERSION);}return NO_ERR;} // CMGetCWInfo
			case 0x0a:case 0x16:{if(a[2]==0)return PARAM_ERR;putLong(a[2],newToken(fourcc("mtch")));return NO_ERR;} // CMBeginMatching, NCMBeginMatching(src, dst, *ref)
			case 0x0b:disposeToken(a[0]);return 0;                             // CMEndMatching
			case 0x09:case 0x17:if(a[0]!=0&&a[2]!=0)trap(0xa8f6,0,a[0],4,a[2],4);return 0; // (N)CMDrawMatchedPicture: DrawPicture, matched to the identity
			case 0x0d:return 0;                                                 // CMEnableMatchingComment
			case 0x08:case 0x3b:return NO_ERR;                                 // CMUseProfileComment, NCMUseProfileComment: no profile comments recorded
			case 0x0c:{putLong(a[2],0);return CM_CANT_CONCATENATE_ERROR;}       // ConcatenateProfiles (1.0)
			case 0x33:case 0x62:return newLinkProfile(cpu,a,selector==0x62);    // CWNewLinkProfile, NCWNewLinkProfile
			// --- ColorSync 1.0 profiles and the Profile Responder
			case 0x0e:{if(a[1]==0)return 0;memory.fill(a[1],66,0);byte[] v1=v1Profile(a[0]);if(v1!=null){int at=be32(v1,36);if(at>0&&at+66<=v1.length)memory.load(a[1],v1,at,66);}return 0;} // CMGetProfileName(h, CMIString)
			case 0x0f:{byte[] v1=v1Profile(a[0]);return v1==null?0:be32(v1,40);} // CMGetProfileAdditionalDataOffset
			case 0x05:case 0x12:{putLong(a[3],0);return CM_PROFILE_NOT_FOUND;}   // GetProfile, GetIndexedProfile
			case 0x06:case 0x10:return CM_PROFILE_NOT_FOUND;                    // SetProfile, SetProfileDescription
			case 0x13:return CM_CANT_DELETE_PROFILE;                            // DeleteDeviceProfile
			case 0x45:{putLong(a[1],0);return CM_METHOD_ERROR;}                 // CMConvertProfile2to1
			// --- system and default profiles
			case 0x18:{if(a[0]==0)return PARAM_ERR;return openAt(cpu,systemLocation(),a[0]);} // CMGetSystemProfile
			case 0x19:{if(a[0]==0)return PARAM_ERR;byte[] raw=new byte[LOCATION_SIZE];put16(raw,0,LOC_FILE);System.arraycopy(memory.readBytes(a[0],70),0,raw,2,70); // CMSetSystemProfile(FSSpec)
				return setSystem(cpu,new Location(raw));}
			case 0x64:{if(a[0]==0)return PARAM_ERR;return setSystem(cpu,readLocation(a[0]));} // NCMSetSystemProfile(location)
			case 0x5a:{Location l=defaultBySpace(a[0]);if(l==null){putLong(a[1],0);return CM_PROFILE_NOT_FOUND;}return openAt(cpu,l,a[1]);} // CMGetDefaultProfileBySpace
			case 0x5b:{Profile p=profile(a[1]);if(p==null)return PARAM_ERR;defaultsBySpace.put(a[0],persistentLocation(p));return NO_ERR;} // CMSetDefaultProfileBySpace
			case 0x69:{Location l=defaultsByUse.get(a[0]); // CMGetDefaultProfileByUse: input/display RGB, output/proof CMYK
				if(l==null){int use=a[0];if(use==fourcc("inpt")||use==fourcc("dply"))l=systemLocation();else if(use==fourcc("outp")||use==fourcc("pruf"))l=defaultBySpace(fourcc("CMYK"));}
				if(l==null){putLong(a[1],0);return PARAM_ERR;}return openAt(cpu,l,a[1]);}
			case 0x79:{Profile p=profile(a[1]);if(p==null)return PARAM_ERR;defaultsByUse.put(a[0],persistentLocation(p));return NO_ERR;} // CMSetDefaultProfileByUse
			case 0x5c:{Location l=byAvid.get(a[0]);return openAt(cpu,l!=null?l:systemLocation(),a[1]);} // CMGetProfileByAVID: the display's profile
			case 0x5d:{Profile p=profile(a[1]);if(p==null)return PARAM_ERR;byAvid.put(a[0],persistentLocation(p));return NO_ERR;} // CMSetProfileByAVID
			case 0x5e:{putLong(a[0],fourcc("appl"));putBoolean(a[1],false);return NO_ERR;} // CMGetPreferredCMM
			case 0x63:return iterateCmms(cpu,a);                                // CMIterateCMMInfo
			case 0x66:{putLong(a[0],VERSION);return a[0]==0?PARAM_ERR:NO_ERR;} // CMGetColorSyncVersion
			case 0x11:{File f=colorSyncFolder(bool(a[1])); // CMGetColorSyncFolderSpec(vRefNum, create, *vRefNum, *dirID)
				if(f==null)return DIR_NF_ERR;
				try{putWord(a[2],tb.files.startupVolumeRefNum());putLong(a[3],tb.files.directoryIdFor(f));}catch(IOException e){return IO_ERR;}
				return NO_ERR;}
			// --- opening, closing, saving
			case 0x1b:return newProfileAt(cpu,a);                               // CMNewProfile(*prof, location)
			case 0x1c:{if(a[0]==0||a[1]==0)return PARAM_ERR;return openAt(cpu,readLocation(a[1]),a[0]);} // CMOpenProfile
			case 0x1d:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;closeProfile(p);return NO_ERR;} // CMCloseProfile
			case 0x42:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;p.refCount++;return NO_ERR;} // CMCloneProfileRef
			case 0x43:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;putLong(a[1],p.refCount);return NO_ERR;} // CMGetProfileRefCount
			case 0x44:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;putBoolean(a[1],p.modified);return NO_ERR;} // CMProfileModified
			case 0x34:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR; // CMUpdateProfile: back to its location
				int err=store(cpu,p.location==null?Location.none():p.location,bytes(p));if(err==NO_ERR)p.modified=false;return err;}
			case 0x25:{Profile src=profile(a[2]);putLong(a[0],0);if(src==null)return PARAM_ERR; // CMCopyProfile(*target, location, src)
				Location loc=a[1]==0?Location.none():readLocation(a[1]);byte[] b=bytes(src);int err=store(cpu,loc,b);if(err!=NO_ERR)return err;
				Profile p=open(b,loc);if(p==null)return CM_FATAL_PROFILE_ERR;putLong(a[0],p.ref);return NO_ERR;}
			case 0x3c:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;writeLocation(a[1],p.location,72);return NO_ERR;} // CMGetProfileLocation
			case 0x59:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR; // NCMGetProfileLocation(prof, location, *size)
				if(a[1]==0){putLong(a[2],LOCATION_SIZE);return NO_ERR;}
				int max=a[2]==0?LOCATION_SIZE:memory.readLong(a[2]);writeLocation(a[1],p.location,Math.max(0,max));putLong(a[2],Math.min(LOCATION_SIZE,Math.max(0,max)));return NO_ERR;}
			case 0x26:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;putBoolean(a[1],valid(p));putBoolean(a[2],false);return NO_ERR;} // CMValidateProfile
			case 0x31:return flattenProfile(cpu,a);                             // CMFlattenProfile(prof, flags, proc, refCon, *notFound)
			case 0x32:case 0x65:return unflattenProfile(cpu,a,selector==0x65);  // CMUnflattenProfile(FSSpec*, ...), NCMUnflattenProfile(location, ...)
			// --- header and elements
			case 0x39:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;if(a[1]==0)return PARAM_ERR; // CMGetProfileHeader
				byte[] h=p.header.clone();if(p.v1==null)put32(h,0,bytes(p).length);memory.load(a[1],h);return NO_ERR;}
			case 0x3a:{Profile p=profile(a[0]);if(p==null||a[1]==0)return PARAM_ERR;if(p.v1!=null)return CM_PROFILE_ERROR; // CMSetProfileHeader
				p.header=memory.readBytes(a[1],128);p.modified=true;return NO_ERR;}
			case 0x1e:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;putBoolean(a[2],p.element(a[1])!=null);return NO_ERR;} // CMProfileElementExists
			case 0x1f:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;putLong(a[1],p.elements.size());return NO_ERR;} // CMCountProfileElements
			case 0x20:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;Element e=p.element(a[1]);if(e==null)return CM_ELEMENT_TAG_NOT_FOUND; // CMGetProfileElement(prof, tag, *size, data)
				return giveElement(e.data.bytes,0,a[2],a[3]);}
			case 0x22:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;if(a[1]<1||a[1]>p.elements.size())return CM_INDEX_RANGE_ERR; // CMGetIndProfileElement
				return giveElement(p.elements.get(a[1]-1).data.bytes,0,a[2],a[3]);}
			case 0x21:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;if(a[1]<1||a[1]>p.elements.size())return CM_INDEX_RANGE_ERR; // CMGetIndProfileElementInfo(prof, index, *tag, *size, *refs)
				Element e=p.elements.get(a[1]-1);putLong(a[2],e.tag);putLong(a[3],e.data.bytes.length);putBoolean(a[4],p.shared(e));return NO_ERR;}
			case 0x36:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;Element e=p.element(a[1]);if(e==null)return CM_ELEMENT_TAG_NOT_FOUND; // CMGetPartialProfileElement(prof, tag, offset, *count, data)
				if(a[2]<0||a[2]>e.data.bytes.length)return PARAM_ERR;return giveElement(e.data.bytes,a[2],a[3],a[4]);}
			case 0x23:{Profile p=profile(a[0]);if(p==null||a[2]<0||a[2]>0&&a[3]==0)return PARAM_ERR;if(p.v1!=null)return CM_PROFILE_ERROR; // CMSetProfileElement(prof, tag, size, data)
				setElement(p,a[1],memory.readBytes(a[3],a[2]));return NO_ERR;}
			case 0x38:{Profile p=profile(a[0]);if(p==null||a[2]<0||a[2]>1<<26)return PARAM_ERR;if(p.v1!=null)return CM_PROFILE_ERROR; // CMSetProfileElementSize(prof, tag, size)
				Element e=p.element(a[1]);setElement(p,a[1],e==null?new byte[a[2]]:java.util.Arrays.copyOf(e.data.bytes,a[2]));return NO_ERR;}
			case 0x37:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;Element e=p.element(a[1]);if(e==null)return CM_ELEMENT_TAG_NOT_FOUND; // CMSetPartialProfileElement(prof, tag, offset, count, data)
				if(a[2]<0||a[3]<0||a[3]>0&&a[4]==0||(long)a[2]+a[3]>e.data.bytes.length)return PARAM_ERR;
				byte[] b=e.data.bytes.clone();System.arraycopy(memory.readBytes(a[4],a[3]),0,b,a[2],a[3]);setElement(p,a[1],b);return NO_ERR;}
			case 0x35:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;Element e=p.element(a[1]);if(e==null)return CM_ELEMENT_TAG_NOT_FOUND; // CMSetProfileElementReference(prof, elementTag, referenceTag)
				Element r=p.element(a[2]);if(r==null)p.elements.add(new Element(a[2],e.data));else r.data=e.data;p.modified=true;return NO_ERR;}
			case 0x24:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;Element e=p.element(a[1]);if(e==null)return CM_ELEMENT_TAG_NOT_FOUND; // CMRemoveProfileElement
				p.elements.remove(e);p.modified=true;return NO_ERR;}
			case 0x3e:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;Element e=p.element(fourcc("desc")); // CMGetScriptProfileDescription(prof, name, *code)
				if(e==null){putPascal(a[1],new byte[0],255);putWord(a[2],0);return CM_ELEMENT_TAG_NOT_FOUND;}
				Object[] script=scriptDescription(e.data.bytes);
				if(script!=null){putPascal(a[1],(byte[])script[1],255);putWord(a[2],(Integer)script[0]);}else{putPascal(a[1],asciiDescription(e.data.bytes),255);putWord(a[2],0);}
				return NO_ERR;}
			case 0x67:return getDescriptions(a);                                // CMGetProfileDescriptions
			case 0x68:{Profile p=profile(a[0]);if(p==null)return PARAM_ERR;if(p.v1!=null)return CM_PROFILE_ERROR; // CMSetProfileDescriptions(prof, aName, aCount, mName, mCode, uName, uCount)
				byte[] ascii=a[1]==0||a[2]<=0?new byte[0]:memory.readBytes(a[1],a[2]);int n=ascii.length;while(n>0&&ascii[n-1]==0)n--;ascii=java.util.Arrays.copyOf(ascii,n);
				byte[] uni=a[5]==0||a[6]<=0?null:memory.readBytes(a[5],2*a[6]);
				setElement(p,fourcc("desc"),descType(ascii,uni,(short)a[4],a[3]==0?null:pascalBytes(a[3])));return NO_ERR;}
			case 0x41:return createIdentifier(a);                               // CMCreateProfileIdentifier(prof, ident, *size)
			// --- named colours
			case 0x46:case 0x47:case 0x48:case 0x49:case 0x4a:return namedColor(selector,a);
			// --- searches and the folder
			case 0x27:{if(a[0]==0||a[3]==0)return PARAM_ERR;Search s=new Search();s.record=memory.readBytes(a[0],44);s.filter=be32(s.record,40); // CMNewProfileSearch(spec, refCon, *count, *search)
				runSearch(cpu,s,a[1]);int t=newToken(fourcc("srch"));searches.put(t,s);putLong(a[3],t);putLong(a[2],s.found.size());return NO_ERR;}
			case 0x28:{Search s=searches.get(a[0]);if(s==null)return CM_INVALID_SEARCH;runSearch(cpu,s,a[1]);putLong(a[2],s.found.size());return NO_ERR;} // CMUpdateProfileSearch
			case 0x29:{if(searches.remove(a[0])!=null)disposeToken(a[0]);return 0;} // CMDisposeProfileSearch
			case 0x2a:{Search s=searches.get(a[0]);putLong(a[2],0);if(s==null)return CM_INVALID_SEARCH;if(a[1]<1||a[1]>s.found.size())return CM_INDEX_RANGE_ERR; // CMSearchGetIndProfile
				return openAt(cpu,s.found.get(a[1]-1).location,a[2]);}
			case 0x2b:{Search s=searches.get(a[0]);if(s==null)return CM_INVALID_SEARCH;if(a[1]<1||a[1]>s.found.size())return CM_INDEX_RANGE_ERR; // CMSearchGetIndProfileFileSpec
				Entry e=s.found.get(a[1]-1);if(e.file==null)return CM_INVALID_PROFILE_LOCATION;if(a[2]!=0)memory.load(a[2],e.location.raw,2,70);return NO_ERR;}
			case 0x3f:{putLong(a[2],0);if(a[0]==0||a[2]==0)return PARAM_ERR;Search s=new Search();s.identifier=identifier(a[0]); // CMProfileIdentifierFolderSearch(ident, *count, *search)
				runSearch(cpu,s,0);int t=newToken(fourcc("srch"));searches.put(t,s);putLong(a[2],t);putLong(a[1],s.found.size());return NO_ERR;}
			case 0x40:{if(a[0]==0)return PARAM_ERR;byte[] ident=identifier(a[0]);int matched=0; // CMProfileIdentifierListSearch(ident, list, listSize, *count, matchedList)
				for(int i=0;i<a[2]&&a[1]!=0;i++){Profile p=profile(memory.readLong(a[1]+4*i));if(p==null)continue;Element d=p.element(fourcc("desc"));
					if(matchesIdentifier(ident,p.header,d==null?"":roman(asciiDescription(d.data.bytes)))){if(a[4]!=0){memory.writeLong(a[4]+4*matched,p.ref);p.refCount++;}matched++;}}
				putLong(a[3],matched);return NO_ERR;}
			case 0x58:return iterateFolder(cpu,a);                              // CMIterateColorSyncFolder(proc, *seed, *count, refCon)
			// --- PostScript
			case 0x2e:case 0x2f:case 0x30:return postScript(cpu,selector,a);
			case 0x3d:{Profile dst=profile(a[1]);if(profile(a[0])==null||dst==null)return PARAM_ERR; // CMGetPS2ColorRenderingVMSize(src, dst, *vmSize, *notFound)
				putBoolean(a[3],false);Element vm=dst.element(fourcc("psvm"));
				if(vm!=null&&vm.data.bytes.length>=16){putLong(a[2],be32(vm.data.bytes,12));return NO_ERR;}
				String crd=renderingDictionary(dst);if(crd==null)return CM_METHOD_ERROR;putLong(a[2],4*crd.length()+4096);return NO_ERR;}
			// --- colour-space conversions
			case 0x4b:case 0x4c:case 0x4d:case 0x4e:return convertWithWhite(selector,a[0],a[1],a[2],a[3]);
			case 0x4f:case 0x50:case 0x51:case 0x52:case 0x53:case 0x54:case 0x55:return convert(selector,a[0],a[1],a[2]);
			case 0x56:{for(int i=0;i<a[2];i++)for(int c=0;c<3;c++)memory.writeLong(a[1]+12*i+4*c,memory.readWord(a[0]+6*i+2*c)<<1);return NO_ERR;} // CMConvertXYZToFixedXYZ: u1.15 -> 16.16
			case 0x57:{int err=NO_ERR;for(int i=0;i<a[2];i++)for(int c=0;c<3;c++){int v=memory.readLong(a[0]+12*i+4*c);int w=v+1>>1; // CMConvertFixedXYZToXYZ
				if(w<0||w>65535){w=w<0?0:65535;err=CM_RANGE_OVERFLOW;}memory.writeWord(a[1]+6*i+2*c,w);}return err;}
			default:return CM_METHOD_ERROR;
		}
	}

	/** Copies element bytes from {@code offset}: *size says how much the buffer takes (all when NIL) and returns how much was copied. */
	private int giveElement(byte[] b,int offset,int sizePtr,int data) throws M68kCpuException {
		int available=b.length-offset;
		if(data==0){putLong(sizePtr,available);return NO_ERR;}
		int n=sizePtr==0?available:Math.max(0,Math.min(available,memory.readLong(sizePtr)));
		if(n>0)memory.load(data,b,offset,n);putLong(sizePtr,n);return NO_ERR;
	}
	/** Sets an element's data, ending any sharing with other tags. */
	private static void setElement(Profile p,int tag,byte[] b){Element e=p.element(tag);if(e==null)p.elements.add(new Element(tag,new Data(b)));else e.data=new Data(b);p.modified=true;}
	private byte[] v1Profile(int h) throws M68kCpuException {if(h==0||!tb.api.isHandle(h))return null;byte[] b=handleBytes(h);return b.length>=60?b:null;}
	/** Where a profile made default can be found again: its own location, else a memory copy of its bytes. */
	private Location persistentLocation(Profile p) throws M68kCpuException {
		if(p.location!=null&&p.location.type()!=LOC_NONE&&!p.modified)return p.location;
		byte[] b=bytes(p);return Location.of(LOC_PTR,block(b),0);
	}
	private int setSystem(M68kCpu cpu,Location loc) throws M68kException {
		byte[] b=load(cpu,loc);if(b==null)return lastError;
		Profile p=new Profile(0);if(!parse(p,b))return CM_FATAL_PROFILE_ERR;
		if(p.cls()!=fourcc("mntr"))return CM_PROFILE_ERROR; // the system profile is a display profile
		systemLocation=loc.type()==LOC_FILE||loc.type()==LOC_PATH?loc:Location.of(LOC_PTR,block(b),0);return NO_ERR;
	}
	/** CMValidateProfile: an ICC header and a description, and for input/display profiles a transform for their space. */
	private static boolean valid(Profile p){
		if(p.v1!=null)return true;
		if(be32(p.header,36)!=MAGIC||p.element(fourcc("desc"))==null)return false;
		int cls=p.cls(),space=p.space();
		if(cls==fourcc("mntr")||cls==fourcc("scnr")){
			if(p.element(fourcc("A2B0"))!=null)return true;
			if(space==fourcc("GRAY"))return p.element(fourcc("kTRC"))!=null;
			if(space==fourcc("RGB "))for(String t:new String[]{"rXYZ","gXYZ","bXYZ","rTRC","gTRC","bTRC"})if(p.element(fourcc(t))==null)return false;
		}
		return true;
	}
	private int newProfileAt(M68kCpu cpu,int[] a) throws M68kException {
		putLong(a[0],0);if(a[0]==0)return PARAM_ERR;
		Location loc=a[1]==0?Location.none():readLocation(a[1]);
		Profile p=newProfile();byte[] h=p.header;
		put32(h,4,fourcc("appl"));put32(h,8,0x02100000);put32(h,36,MAGIC);put32(h,40,fourcc("APPL"));put32(h,68,s15(0.9642));put32(h,72,s15(1.0));put32(h,76,s15(0.8249));
		java.time.LocalDateTime now=java.time.LocalDateTime.now();put16(h,24,now.getYear());put16(h,26,now.getMonthValue());put16(h,28,now.getDayOfMonth());put16(h,30,now.getHour());put16(h,32,now.getMinute());put16(h,34,now.getSecond());
		p.location=loc;p.modified=true;putLong(a[0],p.ref);return NO_ERR;
	}
	/** CWNewLinkProfile / NCWNewLinkProfile: a device link profile from the first profile's space to the last one's, saved at the location. */
	private int newLinkProfile(M68kCpu cpu,int[] a,boolean ncm) throws M68kException {
		putLong(a[0],0);if(a[0]==0||a[2]==0)return PARAM_ERR;
		List<Profile> set=new ArrayList<Profile>();
		if(ncm){int n=memory.readLong(a[2]+12);for(int i=0;i<n&&i<64;i++){Profile p=profile(memory.readLong(a[2]+16+12*i+8));if(p!=null)set.add(p);}}
		else{int n=memory.readWord(a[2]+2);for(int i=0;i<n&&i<64;i++){Profile p=profile(memory.readLong(a[2]+4+4*i));if(p!=null)set.add(p);}}
		if(set.isEmpty())return PARAM_ERR;
		Profile first=set.get(0),last=set.get(set.size()-1);
		String from=ResourceFile68k.typeName(first.space()),to=ResourceFile68k.typeName(set.size()==1?first.pcs():last.space());
		byte[] b=makeProfile("link",from,to,"Link Profile");
		Location loc=a[1]==0?Location.none():readLocation(a[1]);
		int err=store(cpu,loc,b);if(err!=NO_ERR)return err;
		Profile p=open(b,loc);if(p==null)return CM_FATAL_PROFILE_ERR;putLong(a[0],p.ref);return NO_ERR;
	}
	private void clearBitMap(int bitMap) throws M68kCpuException {
		int base=memory.readLong(bitMap),rowBytes=memory.readWord(bitMap+4)&0x3fff,top=(short)memory.readWord(bitMap+6),bottom=(short)memory.readWord(bitMap+10);
		if(base!=0&&bottom>top&&(long)rowBytes*(bottom-top)<1<<26)memory.fill(base,rowBytes*(bottom-top),0);
	}
	/** CWMatchBitmap(cw, bitmap, proc, refCon, matched): the identity - in place, or copied into a matched bitmap of the same kind. */
	private int matchBitmap(int[] a) throws M68kCpuException {
		if(!worlds.containsKey(a[0]))return PARAM_ERR;if(a[1]==0)return CM_INVALID_SRC_MAP;
		if(a[4]==0||a[4]==a[1])return NO_ERR;
		int src=memory.readLong(a[1]),w=memory.readLong(a[1]+4),h=memory.readLong(a[1]+8),srb=memory.readLong(a[1]+12),depth=memory.readLong(a[1]+16),space=memory.readLong(a[1]+20);
		int dst=memory.readLong(a[4]),drb=memory.readLong(a[4]+12);
		if(dst==0)return CM_INVALID_DST_MAP;
		if(memory.readLong(a[4]+16)!=depth||memory.readLong(a[4]+20)!=space)return CM_INVALID_COLOR_SPACE;
		int rowBytes=(int)Math.min(Math.min(srb,drb),((long)w*depth+7)/8);
		if(src!=0&&h>0&&rowBytes>0)for(int y=0;y<h;y++)memory.copy(src+y*srb,dst+y*drb,rowBytes);
		return NO_ERR;
	}
	private int iterateCmms(M68kCpu cpu,int[] a) throws M68kException {
		putLong(a[1],1);if(a[0]==0)return NO_ERR;
		int info=memory.allocate(888,4);
		try{
			memory.fill(info,888,0);memory.writeLong(info,888);memory.writeLong(info+4,fourcc("appl"));memory.writeLong(info+8,fourcc("appl"));memory.writeLong(info+12,VERSION);
			String name="Apple CMM",desc="Identity colour matching";
			putPascal(info+16,roman(name),31);putPascal(info+48,roman(desc),255);
			memory.writeLong(info+304,name.length());for(int i=0;i<name.length();i++)memory.writeWord(info+308+2*i,name.charAt(i));
			memory.writeLong(info+372,desc.length());for(int i=0;i<desc.length();i++)memory.writeWord(info+376+2*i,desc.charAt(i));
			return (short)call(cpu,a[0],2,info,4,a[2],4);
		}finally{memory.free(info);}
	}
	/** CMIterateColorSyncFolder: one CMProfileIterateData (version 2) per profile to the program's procedure. */
	private int iterateFolder(M68kCpu cpu,int[] a) throws M68kException {
		List<Entry> l=folderProfiles();
		putLong(a[1],folderSeed(l));putLong(a[2],l.size());
		if(a[0]==0)return NO_ERR;
		int data=memory.allocate(664,4),names=memory.allocate(1024,4),makeAndModel=memory.allocate(32,4);
		try{
			for(Entry e:l){
				byte[] b=load(cpu,e.location);Profile p=new Profile(0);if(b==null||!parse(p,b))continue;
				Element d=p.element(fourcc("desc"));byte[] ascii=d==null?new byte[0]:asciiDescription(d.data.bytes);
				byte[] uni=d==null?null:unicodeDescription(d.data.bytes);Object[] script=d==null?null:scriptDescription(d.data.bytes);
				memory.fill(data,664,0);memory.fill(names,1024,0);memory.fill(makeAndModel,32,0);
				memory.writeLong(data,0x00020000);memory.load(data+4,p.header);
				memory.writeWord(data+132,script==null?0:(Integer)script[0]);putPascal(data+134,script==null?ascii:(byte[])script[1],255);
				memory.load(data+390,e.location.raw,0,LOCATION_SIZE);
				int asciiLen=Math.min(ascii.length,511);memory.load(names,ascii,0,asciiLen);
				int uniChars=uni!=null?Math.min(uni.length/2,255):asciiLen;
				if(uni!=null)memory.load(names+512,uni,0,2*uniChars);else for(int i=0;i<uniChars;i++)memory.writeWord(names+512+2*i,ascii[i]&255);
				memory.writeLong(data+648,uniChars);memory.writeLong(data+652,names+512);memory.writeLong(data+656,names);
				Element mm=p.element(fourcc("mmod"));if(mm!=null&&mm.data.bytes.length>=40){memory.load(makeAndModel,mm.data.bytes,8,32);memory.writeLong(data+660,makeAndModel);}
				int err=(short)call(cpu,a[0],2,data,4,a[3],4);if(err!=NO_ERR)return err;
			}
			return NO_ERR;
		}finally{memory.free(data);memory.free(names);memory.free(makeAndModel);}
	}
	private int getDescriptions(int[] a) throws M68kCpuException {
		Profile p=profile(a[0]);if(p==null)return PARAM_ERR;
		Element e=p.element(fourcc("desc"));if(e==null)return CM_ELEMENT_TAG_NOT_FOUND;
		byte[] ascii=asciiDescription(e.data.bytes);
		if(a[2]!=0){int max=a[1]==0?Integer.MAX_VALUE:memory.readLong(a[2]);int n=Math.min(ascii.length+1,Math.max(0,max));
			if(a[1]!=0&&n>0){memory.load(a[1],ascii,0,Math.min(n,ascii.length));if(n>ascii.length)memory.writeByte(a[1]+ascii.length,0);else memory.writeByte(a[1]+n-1,0);}
			memory.writeLong(a[2],a[1]==0?ascii.length+1:n);}
		Object[] script=scriptDescription(e.data.bytes);
		putPascal(a[3],script==null?ascii:(byte[])script[1],255);putWord(a[4],script==null?0:(Integer)script[0]);
		byte[] uni=unicodeDescription(e.data.bytes);if(uni==null){uni=new byte[2*ascii.length];for(int i=0;i<ascii.length;i++)put16(uni,2*i,ascii[i]&255);}
		if(a[6]!=0){int chars=uni.length/2;int max=a[5]==0?chars:memory.readLong(a[6]);int n=Math.min(chars,Math.max(0,max));if(a[5]!=0&&n>0)memory.load(a[5],uni,0,2*n);memory.writeLong(a[6],a[5]==0?chars:n);}
		return NO_ERR;
	}
	/** CMProfileIdentifier: the header, the calibration date, the ASCII description length (with its NUL) and text. */
	private byte[] profileIdentifier(Profile p){
		Element d=p.element(fourcc("desc"));byte[] ascii=d==null?new byte[0]:asciiDescription(d.data.bytes);
		byte[] b=new byte[144+ascii.length+1];System.arraycopy(p.header,0,b,0,128);
		Element calt=p.element(fourcc("calt"));if(calt!=null&&calt.data.bytes.length>=20)System.arraycopy(calt.data.bytes,8,b,128,12);
		put32(b,140,ascii.length+1);System.arraycopy(ascii,0,b,144,ascii.length);return b;
	}
	private int createIdentifier(int[] a) throws M68kCpuException {
		Profile p=profile(a[0]);if(p==null)return PARAM_ERR;
		byte[] b=profileIdentifier(p);
		if(a[1]==0){putLong(a[2],b.length);return NO_ERR;}
		int max=a[2]==0?b.length:memory.readLong(a[2]);if(max<b.length)return PARAM_ERR;
		memory.load(a[1],b);putLong(a[2],b.length);return NO_ERR;
	}
	private byte[] identifier(int p) throws M68kCpuException {int n=memory.readLong(p+140);if(n<0||n>4096)n=0;return memory.readBytes(p,144+n);}

	// ------------------------------------------------------------------ flattening

	/** CMFlattenProfile: the profile's bytes through the program's CMFlattenUPP (command, *size, data, refCon). */
	private int flattenProfile(M68kCpu cpu,int[] a) throws M68kException {
		Profile p=profile(a[0]);putBoolean(a[4],false);if(p==null||a[2]==0)return PARAM_ERR;
		return spoolOut(cpu,a[2],a[3],bytes(p));
	}
	private int spoolOut(M68kCpu cpu,int proc,int refCon,byte[] b) throws M68kException {
		int size=memory.allocate(4,4),buffer=block(b);
		try{
			memory.writeLong(size,b.length);int err=(short)call(cpu,proc,2,CM_OPEN_WRITE_SPOOL,4,size,4,0,4,refCon,4);if(err!=NO_ERR)return err;
			for(int at=0;at<b.length&&err==NO_ERR;at+=16384){int n=Math.min(16384,b.length-at);memory.writeLong(size,n);err=(short)call(cpu,proc,2,CM_WRITE_SPOOL,4,size,4,buffer+at,4,refCon,4);}
			memory.writeLong(size,0);int close=(short)call(cpu,proc,2,CM_CLOSE_SPOOL,4,size,4,0,4,refCon,4);
			return err!=NO_ERR?err:close;
		}finally{memory.free(size);memory.free(buffer);}
	}
	/** CMUnflattenProfile (into a new file in Temporary Items, its FSSpec returned) / NCMUnflattenProfile (to the location given). */
	private int unflattenProfile(M68kCpu cpu,int[] a,boolean ncm) throws M68kException {
		putBoolean(a[3],false);if(a[0]==0||a[1]==0)return PARAM_ERR;
		byte[] b;int size=memory.allocate(4,4),buffer=memory.allocate(16384,4);
		try{
			memory.writeLong(size,0);int err=(short)call(cpu,a[1],2,CM_OPEN_READ_SPOOL,4,size,4,0,4,a[2],4);if(err!=NO_ERR)return err;
			java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
			while(out.size()<1<<26){memory.writeLong(size,16384);err=(short)call(cpu,a[1],2,CM_READ_SPOOL,4,size,4,buffer,4,a[2],4);
				int n=memory.readLong(size);if(err!=NO_ERR||n<=0)break;out.write(memory.readBytes(buffer,Math.min(n,16384)),0,Math.min(n,16384));}
			memory.writeLong(size,0);call(cpu,a[1],2,CM_CLOSE_SPOOL,4,size,4,0,4,a[2],4);
			if(err!=NO_ERR)return err;b=out.toByteArray();
		}finally{memory.free(size);memory.free(buffer);}
		if(!parse(new Profile(0),b))return CM_FATAL_PROFILE_ERR;
		Location loc=ncm?readLocation(a[0]):null;
		if(loc==null||loc.type()==LOC_NONE){ // a temporary file, as ColorSync made one
			File dir=tb.files.specialFolder(0x74656d70,true);if(dir==null)return DIR_NF_ERR;
			File f=new File(dir,"ColorSync profile "+Long.toHexString(System.nanoTime()));
			int spec=memory.allocate(70,2);
			try{tb.files.fileManager.writeFSSpec(spec,f);byte[] raw=new byte[LOCATION_SIZE];put16(raw,0,LOC_FILE);System.arraycopy(memory.readBytes(spec,70),0,raw,2,70);loc=new Location(raw);}
			finally{memory.free(spec);}
			int err=store(cpu,loc,b);if(err!=NO_ERR)return err;
			if(ncm)writeLocation(a[0],loc,72);else memory.load(a[0],loc.raw,2,70);
			return NO_ERR;
		}
		return store(cpu,loc,b);
	}

	// ------------------------------------------------------------------ named colours ('ncl2')

	private int namedColor(int selector,int[] a) throws M68kCpuException {
		Profile p=profile(a[0]);if(p==null)return PARAM_ERR;
		Element e=p.element(fourcc("ncl2"));byte[] b=e==null?null:e.data.bytes;
		if(b==null||b.length<84||be32(b,0)!=fourcc("ncl2")){
			if(selector==0x46){putLong(a[1],0);putLong(a[2],0);putLong(a[3],0);putLong(a[4],0);putPascal(a[5],new byte[0],255);putPascal(a[6],new byte[0],255);}
			return CM_NAMED_COLOR_NOT_FOUND;
		}
		int count=be32(b,12),channels=be32(b,16),entry=32+6+2*channels;
		if(count<0||channels<0||channels>15||84L+(long)entry*count>b.length)return CM_FATAL_PROFILE_ERR;
		switch(selector){
			case 0x46:putLong(a[1],channels);putLong(a[2],p.space());putLong(a[3],p.pcs());putLong(a[4],count); // CMGetNamedColorInfo
				putPascal(a[5],cString(b,20,32),255);putPascal(a[6],cString(b,52,32),255);return NO_ERR;
			case 0x47:case 0x49:{byte[] want=pascalBytes(a[1]);int index=-1; // CMGetNamedColorValue(prof, name, device, PCS), CMGetNamedColorIndex(prof, name, *index)
				for(int i=0;i<count;i++)if(java.util.Arrays.equals(cString(b,84+entry*i,32),want)){index=i;break;}
				if(index<0)return CM_NAMED_COLOR_NOT_FOUND;
				if(selector==0x49){putLong(a[2],index+1);return NO_ERR;}
				giveNamed(b,84+entry*index,channels,a[2],a[3]);return NO_ERR;}
			case 0x48:if(a[1]<1||a[1]>count)return CM_NAMED_COLOR_NOT_FOUND;giveNamed(b,84+entry*(a[1]-1),channels,a[2],a[3]);return NO_ERR; // CMGetIndNamedColorValue
			case 0x4a:if(a[1]<1||a[1]>count)return CM_NAMED_COLOR_NOT_FOUND;putPascal(a[2],cString(b,84+entry*(a[1]-1),32),255);return NO_ERR; // CMGetNamedColorName
			default:return CM_METHOD_ERROR;
		}
	}
	/** An entry's device colour (16-bit components; the 5-8 channel CMColor forms hold bytes) and PCS colour into CMColors. */
	private void giveNamed(byte[] b,int at,int channels,int device,int pcs) throws M68kCpuException {
		if(pcs!=0){memory.fill(pcs,8,0);for(int c=0;c<3;c++)memory.writeWord(pcs+2*c,be16(b,at+32+2*c));}
		if(device!=0){memory.fill(device,8,0);for(int c=0;c<channels&&c<8;c++){int v=be16(b,at+38+2*c);if(channels>4)memory.writeByte(device+c,v>>>8);else memory.writeWord(device+2*c,v);}}
	}
	private static byte[] cString(byte[] b,int at,int max){int n=0;while(n<max&&at+n<b.length&&b[at+n]!=0)n++;return java.util.Arrays.copyOfRange(b,at,at+n);}

	// ------------------------------------------------------------------ PostScript

	/** The gamma of a 'curv' element (a table is approximated by its mid-point). */
	private static double gamma(byte[] c){
		if(c==null||c.length<12||be32(c,0)!=fourcc("curv"))return 1.0;
		int n=be32(c,8);if(n==0)return 1.0;if(n==1&&c.length>=14)return be16(c,12)/256.0;
		if(n<2||c.length<12+2*n)return 1.0;double mid=be16(c,12+2*(n/2))/65535.0;double x=(n/2)/(double)(n-1);
		return mid<=0||mid>=1?1.0:Math.log(mid)/Math.log(x);
	}
	private static double[] xyz(Profile p,String tag){Element e=p.element(fourcc(tag));if(e==null||e.data.bytes.length<20)return null;byte[] b=e.data.bytes;return new double[]{be32(b,8)/65536.0,be32(b,12)/65536.0,be32(b,16)/65536.0};}
	private static String num(double v){return String.format(java.util.Locale.ROOT,"%.4f",v);}
	private static double[] white(Profile p){double[] w=xyz(p,"wtpt");return w!=null?w:new double[]{0.9642,1.0,0.8249};}
	private static String decode(double g){return g==1.0?"{}":"{"+num(g)+" exp} bind";}
	/** The colour space array of a matrix/TRC RGB or a gray profile (null for other kinds). */
	static String colorSpace(Profile p){
		double[] w=white(p);String wp="/WhitePoint ["+num(w[0])+" "+num(w[1])+" "+num(w[2])+"]";
		if(p.space()==fourcc("GRAY")){Element k=p.element(fourcc("kTRC"));return "[/CIEBasedA <<\n/DecodeA "+decode(gamma(k==null?null:k.data.bytes))+"\n/MatrixA ["+num(w[0])+" "+num(w[1])+" "+num(w[2])+"]\n"+wp+"\n>>]\n";}
		double[] r=xyz(p,"rXYZ"),g=xyz(p,"gXYZ"),b=xyz(p,"bXYZ");
		if(p.space()!=fourcc("RGB ")||r==null||g==null||b==null)return null;
		StringBuilder s=new StringBuilder("[/CIEBasedABC <<\n/DecodeABC [");
		for(String t:new String[]{"rTRC","gTRC","bTRC"}){Element c=p.element(fourcc(t));s.append(decode(gamma(c==null?null:c.data.bytes))).append(' ');}
		s.append("]\n/MatrixABC [");for(double[] c:new double[][]{r,g,b})s.append(num(c[0])).append(' ').append(num(c[1])).append(' ').append(num(c[2])).append(' ');
		return s.append("]\n").append(wp).append("\n>>]\n").toString();
	}
	/** A type 1 colour rendering dictionary for a matrix/TRC RGB destination (null for other kinds). */
	static String renderingDictionary(Profile p){
		double[] r=xyz(p,"rXYZ"),g=xyz(p,"gXYZ"),b=xyz(p,"bXYZ");if(p.space()!=fourcc("RGB ")||r==null||g==null||b==null)return null;
		double[][] m={{r[0],g[0],b[0]},{r[1],g[1],b[1]},{r[2],g[2],b[2]}};
		double det=m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1])-m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0])+m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0]);
		if(Math.abs(det)<1e-9)return null;
		double[][] inv=new double[3][3];
		for(int i=0;i<3;i++)for(int j=0;j<3;j++){int i1=(j+1)%3,i2=(j+2)%3,j1=(i+1)%3,j2=(i+2)%3;inv[i][j]=(m[i1][j1]*m[i2][j2]-m[i1][j2]*m[i2][j1])/det;}
		double[] w=white(p);
		StringBuilder s=new StringBuilder("<<\n/ColorRenderingType 1\n/WhitePoint ["+num(w[0])+" "+num(w[1])+" "+num(w[2])+"]\n/MatrixABC [");
		for(int col=0;col<3;col++)for(int row=0;row<3;row++)s.append(num(inv[row][col])).append(' ');
		s.append("]\n/EncodeABC [");
		for(String t:new String[]{"rTRC","gTRC","bTRC"}){Element c=p.element(fourcc(t));double gm=gamma(c==null?null:c.data.bytes);s.append("{dup 0 lt {pop 0} if dup 1 gt {pop 1} if ").append(gm==1.0?"":num(1/gm)+" exp").append("} bind ");}
		return s.append("]\n/RangeABC [0 1 0 1 0 1]\n>>\n").toString();
	}
	private static final String[] INTENTS={"/Perceptual","/RelativeColorimetric","/Saturation","/AbsoluteColorimetric"};
	private int postScript(M68kCpu cpu,int selector,int[] a) throws M68kException {
		Profile src=profile(a[0]);if(src==null)return PARAM_ERR;
		int proc,refCon,notFound;String text;
		if(selector==0x30){Profile dst=profile(a[1]);if(dst==null)return PARAM_ERR;proc=a[3];refCon=a[4];notFound=a[5];text=renderingDictionary(dst);} // CMGetPS2ColorRendering(src, dst, flags, proc, refCon, *notFound)
		else{proc=a[2];refCon=a[3];notFound=a[4];text=selector==0x2e?colorSpace(src):INTENTS[be32(src.header,64)&3];} // CMGetPS2ColorSpace / CMGetPS2ColorRenderingIntent
		putBoolean(notFound,false);
		if(proc==0)return PARAM_ERR;if(text==null)return CM_METHOD_ERROR;
		return spoolOut(cpu,proc,refCon,text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	// ------------------------------------------------------------------ colour-space conversions

	private static double xyzOf(int v){return (v&0xffff)/32768.0;}
	private boolean clipped;
	private int clip(double v,double scale,double offset){double x=Math.round((v+offset)*scale);if(x<0){clipped=true;return 0;}if(x>65535){clipped=true;return 65535;}return (int)x;}
	private static double labF(double t){double e=216.0/24389,k=24389.0/27;return t>e?Math.cbrt(t):(k*t+16)/116;}
	private static double labFInverse(double f){double e=216.0/24389,k=24389.0/27;double f3=f*f*f;return f3>e?f3:(116*f-16)/k;}
	/** XYZ <-> Lab/Luv against a white point (a CMXYZColor, D50 when NIL); src and dst are arrays of 8-byte CMColors. */
	private int convertWithWhite(int selector,int src,int whitePtr,int dst,int count) throws M68kCpuException {
		if(src==0||dst==0)return PARAM_ERR;
		double xw=whitePtr==0?D50_X/32768.0:xyzOf(memory.readWord(whitePtr)),yw=whitePtr==0?1.0:xyzOf(memory.readWord(whitePtr+2)),zw=whitePtr==0?D50_Z/32768.0:xyzOf(memory.readWord(whitePtr+4));
		if(xw<=0||yw<=0||zw<=0)return PARAM_ERR;
		double uw=4*xw/(xw+15*yw+3*zw),vw=9*yw/(xw+15*yw+3*zw);
		clipped=false;
		for(int i=0;i<count;i++){
			int s=src+8*i,d=dst+8*i;
			int c0=memory.readWord(s),c1=memory.readWord(s+2),c2=memory.readWord(s+4);
			double[] out=new double[3];boolean toXyz=selector==0x4c||selector==0x4e;
			switch(selector){
				case 0x4b:{double fx=labF(xyzOf(c0)/xw),fy=labF(xyzOf(c1)/yw),fz=labF(xyzOf(c2)/zw); // XYZ -> Lab
					out[0]=116*fy-16;out[1]=500*(fx-fy);out[2]=200*(fy-fz);break;}
				case 0x4c:{double L=c0*100.0/65535,A=c1/256.0-128,B=c2/256.0-128; // Lab -> XYZ
					double fy=(L+16)/116,fx=fy+A/500,fz=fy-B/200;out[0]=labFInverse(fx)*xw;out[1]=(L>8?fy*fy*fy:L*27/24389)*yw;out[2]=labFInverse(fz)*zw;break;}
				case 0x4d:{double X=xyzOf(c0),Y=xyzOf(c1),Z=xyzOf(c2),den=X+15*Y+3*Z; // XYZ -> Luv
					double L=116*labF(Y/yw)-16;double up=den==0?uw:4*X/den,vp=den==0?vw:9*Y/den;out[0]=L;out[1]=13*L*(up-uw);out[2]=13*L*(vp-vw);break;}
				default:{double L=c0*100.0/65535,u=c1/256.0-128,v=c2/256.0-128; // Luv -> XYZ
					double Y=(L>8?Math.pow((L+16)/116,3):L*27/24389)*yw;
					if(L==0){out[0]=out[1]=out[2]=0;break;}
					double up=u/(13*L)+uw,vp=v/(13*L)+vw;if(vp==0){out[0]=out[1]=out[2]=0;break;}
					out[1]=Y;out[0]=Y*9*up/(4*vp);out[2]=Y*(12-3*up-20*vp)/(4*vp);break;}
			}
			if(toXyz){for(int c=0;c<3;c++)memory.writeWord(d+2*c,clip(out[c],32768,0));}
			else{memory.writeWord(d,clip(out[0],65535/100.0,0));memory.writeWord(d+2,clip(out[1],256,128));memory.writeWord(d+4,clip(out[2],256,128));}
		}
		return clipped?CM_RANGE_OVERFLOW:NO_ERR;
	}
	/** Yxy, HLS, HSV and gray conversions; all components are 0..65535 fractions (XYZ u1.15, hue a fraction of the circle). */
	private int convert(int selector,int src,int dst,int count) throws M68kCpuException {
		if(src==0||dst==0)return PARAM_ERR;
		clipped=false;
		for(int i=0;i<count;i++){
			int s=src+8*i,d=dst+8*i;
			double a=memory.readWord(s)/65535.0,b=memory.readWord(s+2)/65535.0,c=memory.readWord(s+4)/65535.0;
			double[] o;
			switch(selector){
				case 0x4f:{double X=xyzOf(memory.readWord(s)),Y=xyzOf(memory.readWord(s+2)),Z=xyzOf(memory.readWord(s+4)),sum=X+Y+Z; // XYZ -> Yxy
					o=new double[]{Y,sum==0?D50_X/32768.0/(D50_X/32768.0+1+D50_Z/32768.0):X/sum,sum==0?1/(D50_X/32768.0+1+D50_Z/32768.0):Y/sum};break;}
				case 0x50:{double Y=a,x=b,y=c; // Yxy -> XYZ (u1.15)
					double X=y==0?0:x*Y/y,Z=y==0?0:(1-x-y)*Y/y;
					for(int k=0;k<3;k++)memory.writeWord(d+2*k,clip(k==0?X:k==1?Y:Z,32768,0));continue;}
				case 0x51:o=rgbToHls(a,b,c);break;
				case 0x52:o=hlsToRgb(a,b,c);break;
				case 0x53:o=rgbToHsv(a,b,c);break;
				case 0x54:o=hsvToRgb(a,b,c);break;
				default:{memory.writeWord(d,clip(0.299*a+0.587*b+0.114*c,65535,0));continue;} // RGB -> Gray
			}
			for(int k=0;k<3;k++)memory.writeWord(d+2*k,clip(o[k],65535,0));
		}
		return clipped?CM_RANGE_OVERFLOW:NO_ERR;
	}
	static double[] rgbToHsv(double r,double g,double b){
		double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),delta=max-min;
		double h=0;if(delta>0){if(max==r)h=(g-b)/delta;else if(max==g)h=2+(b-r)/delta;else h=4+(r-g)/delta;h/=6;if(h<0)h+=1;}
		return new double[]{h,max==0?0:delta/max,max};
	}
	static double[] hsvToRgb(double h,double s,double v){
		if(s==0)return new double[]{v,v,v};
		double hh=(h>=1?0:h)*6;int i=(int)Math.floor(hh);double f=hh-i,p=v*(1-s),q=v*(1-s*f),t=v*(1-s*(1-f));
		switch(i){case 0:return new double[]{v,t,p};case 1:return new double[]{q,v,p};case 2:return new double[]{p,v,t};case 3:return new double[]{p,q,v};case 4:return new double[]{t,p,v};default:return new double[]{v,p,q};}
	}
	static double[] rgbToHls(double r,double g,double b){
		double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),l=(max+min)/2,delta=max-min;
		if(delta==0)return new double[]{0,l,0};
		double s=l<=0.5?delta/(max+min):delta/(2-max-min);
		double h;if(max==r)h=(g-b)/delta;else if(max==g)h=2+(b-r)/delta;else h=4+(r-g)/delta;h/=6;if(h<0)h+=1;
		return new double[]{h,l,s};
	}
	static double[] hlsToRgb(double h,double l,double s){
		if(s==0)return new double[]{l,l,l};
		double m2=l<=0.5?l*(1+s):l+s-l*s,m1=2*l-m2;
		return new double[]{hue(m1,m2,h+1.0/3),hue(m1,m2,h),hue(m1,m2,h-1.0/3)};
	}
	private static double hue(double m1,double m2,double h){if(h<0)h+=1;if(h>1)h-=1;if(h<1.0/6)return m1+(m2-m1)*h*6;if(h<0.5)return m2;if(h<2.0/3)return m1+(m2-m1)*(2.0/3-h)*6;return m1;}
}

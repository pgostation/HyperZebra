import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only ISO 9660 CDs (ECMA-119) as a Mac saw them with its Foreign File Access: the primary directory tree, with long
 * names from Rock Ridge ("NM") or, failing that, from the Joliet tree (UCS-2); Apple's ISO 9660 extensions give files their
 * type, creator and Finder flags ("AA" in the system use area) and an associated file with the same name holds a file's
 * resource fork. Names without those are the ISO ones, version (";1") and a bare trailing dot taken off.
 */
final class IsoVolume extends DiskImage.Volume {
	private static final int SECTOR=2048;
	private final DiskImage.Source source;
	private int blockSize=SECTOR;

	private IsoVolume(DiskImage.Source source){this.source=source;}

	/** Whether {@code s} holds an ISO 9660 primary volume descriptor at sector 16. */
	static boolean recognizes(DiskImage.Source s) throws IOException {
		if(s.length()<17L*SECTOR)return false;
		byte[] d=s.read(16L*SECTOR,8);
		return d[1]=='C'&&d[2]=='D'&&d[3]=='0'&&d[4]=='0'&&d[5]=='1';
	}

	/** Where a fork lives: extents {byte offset, length}. */
	private static final class Place{final List<long[]> extents=new ArrayList<long[]>();long length(){long n=0;for(long[] e:extents)n+=e[1];return n;}}

	@Override void copyFork(DiskImage.Entry entry,boolean resource,OutputStream out) throws IOException {
		Place place=(Place)(resource?entry.resourcePlace:entry.dataPlace);
		if(place==null)return;
		byte[] buffer=new byte[1<<16];
		for(long[] e:place.extents){
			long at=e[0],left=e[1];
			while(left>0){int n=(int)Math.min(buffer.length,left);source.read(at,buffer,0,n);out.write(buffer,0,n);at+=n;left-=n;}
		}
	}

	/** One directory record. */
	private static final class Record{
		long extent,length;int flags;byte[] isoName;String rockName;byte[] appleInfo;int date;
		boolean directory(){return (flags&2)!=0;}
		boolean associated(){return (flags&4)!=0;}
		boolean hidden(){return (flags&1)!=0;}
	}

	static DiskImage.Volume read(DiskImage.Source source) throws IOException {
		IsoVolume v=new IsoVolume(source);
		byte[] primary=null,joliet=null;
		for(int sector=16;sector<16+64;sector++){
			byte[] d=source.read((long)sector*SECTOR,SECTOR);
			if(d[1]!='C'||d[2]!='D'||d[3]!='0'||d[4]!='0'||d[5]!='1')break;
			int type=d[0]&0xff;
			if(type==255)break;
			if(type==1&&primary==null)primary=d;
			if(type==2&&joliet==null&&d[88]==0x25&&d[89]==0x2F&&(d[90]==0x40||d[90]==0x43||d[90]==0x45))joliet=d;
		}
		if(primary==null)throw new IOException("ISO 9660 without a primary volume descriptor");
		int size=DiskImage.le4(primary,128)&0xffff;if(size>=512&&size<=SECTOR&&(size&(size-1))==0)v.blockSize=size;
		v.kind="ISO 9660";
		v.unicodeName=new String(primary,40,32,StandardCharsets.ISO_8859_1).trim();
		v.created=v.modified=longDate(primary,813);
		// the primary tree: Rock Ridge names, Apple type/creator, associated files as resource forks
		Map<Long,DiskImage.Entry> byExtent=new HashMap<Long,DiskImage.Entry>();
		Record root=record(primary,156,v);
		boolean[] rockRidge={false};
		v.readDirectory(root,v.root,byExtent,rockRidge,true,new HashSet<Long>(),0);
		if(rockRidge[0])v.kind="ISO 9660 (Rock Ridge)";
		else if(joliet!=null){
			// names from the Joliet tree; what the primary tree knew (Finder info, resource forks) found by the data's place
			DiskImage.Entry jolietRoot=new DiskImage.Entry();jolietRoot.folder=true;jolietRoot.id=2;
			v.readDirectory(record(joliet,156,v),jolietRoot,null,null,false,new HashSet<Long>(),0);
			v.adoptPrimary(jolietRoot,byExtent);
			v.root.children.clear();v.root.children.addAll(jolietRoot.children);
			String name=new String(joliet,40,32,StandardCharsets.UTF_16BE).trim();if(!name.isEmpty())v.unicodeName=name;
			v.kind="ISO 9660 (Joliet)";
		}
		if(v.unicodeName.isEmpty())v.unicodeName="CD";
		v.root.folder=true;v.root.id=2;v.root.unicodeName=v.unicodeName;v.root.created=v.created;v.root.modified=v.modified;
		return v;
	}

	/** Reads the directory of {@code dir} into {@code into}; the primary tree also records files by their data's place. */
	private void readDirectory(Record dir,DiskImage.Entry into,Map<Long,DiskImage.Entry> byExtent,boolean[] rockRidge,boolean primary,Set<Long> seen,int depth) throws IOException {
		if(depth>64||!seen.add(dir.extent)||dir.length>(64L<<20))return;
		byte[] data=source.read(dir.extent*blockSize,(int)dir.length);
		List<Record> records=new ArrayList<Record>();
		for(int at=0;at<data.length;){
			int length=data[at]&0xff;
			if(length==0){at=(at/SECTOR+1)*SECTOR;continue;} // the rest of this sector is unused
			if(at+length>data.length||length<34)break;
			Record r=record(data,at,this);at+=length;
			if(r.isoName.length==1&&(r.isoName[0]==0||r.isoName[0]==1))continue; // "." and ".."
			records.add(r);
		}
		// multi-extent files: records with the same name in a row, the "not final" flag on all but the last
		Map<String,byte[]> resourceInfo=new HashMap<String,byte[]>();
		Map<String,Place> resourceForks=new HashMap<String,Place>();
		Map<DiskImage.Entry,String> keys=new HashMap<DiskImage.Entry,String>();
		for(int i=0;i<records.size();i++){
			Record r=records.get(i);
			Place place=new Place();place.extents.add(new long[]{r.extent*blockSize,r.length});
			while((r.flags&0x80)!=0&&i+1<records.size()){r=records.get(++i);place.extents.add(new long[]{r.extent*blockSize,r.length});}
			if(r.rockName!=null&&rockRidge!=null)rockRidge[0]=true;
			String key=new String(r.isoName,StandardCharsets.ISO_8859_1);
			if(r.associated()){resourceForks.put(key,place);if(r.appleInfo!=null)resourceInfo.put(key,r.appleInfo);continue;}
			DiskImage.Entry e=new DiskImage.Entry();
			e.unicodeName=r.rockName!=null?r.rockName:primary?isoName(r.isoName,r.directory()):jolietName(r.isoName);
			e.created=e.modified=r.date;
			if(r.appleInfo!=null)System.arraycopy(r.appleInfo,0,e.finderInfo,0,Math.min(10,r.appleInfo.length));
			if(r.hidden())e.finderInfo[8]|=0x40;
			if(r.directory()){e.folder=true;e.id=(int)r.extent;readDirectory(r,e,byExtent,rockRidge,primary,seen,depth+1);}
			else{e.dataPlace=place;e.dataLength=place.length();keys.put(e,key);if(byExtent!=null&&e.dataLength>0)byExtent.put(r.extent,e);}
			into.children.add(e);
		}
		for(DiskImage.Entry e:into.children){ // resource forks and their Finder info (when only the associated file has it)
			String key=keys.get(e);
			if(key==null)continue;
			Place fork=resourceForks.get(key);
			if(fork!=null){e.resourcePlace=fork;e.resourceLength=fork.length();}
			byte[] info=resourceInfo.get(key);
			if(info!=null&&e.finderInfo[0]==0)System.arraycopy(info,0,e.finderInfo,0,Math.min(10,info.length));
		}
	}

	/** Joliet entries take the Finder info and resource fork the primary tree gave the same data. */
	private void adoptPrimary(DiskImage.Entry dir,Map<Long,DiskImage.Entry> byExtent){
		for(DiskImage.Entry e:dir.children){
			if(e.folder){adoptPrimary(e,byExtent);continue;}
			Place place=(Place)e.dataPlace;if(place==null||place.extents.isEmpty()||place.length()==0)continue;
			DiskImage.Entry p=byExtent.get(place.extents.get(0)[0]/blockSize);
			if(p==null)continue;
			System.arraycopy(p.finderInfo,0,e.finderInfo,0,16);
			e.resourcePlace=p.resourcePlace;e.resourceLength=p.resourceLength;
		}
	}

	/** A directory record at {@code at}: extent, length, flags, name, and from the system use area Rock Ridge's name and Apple's Finder info. */
	private static Record record(byte[] b,int at,IsoVolume v) throws IOException {
		Record r=new Record();
		int length=b[at]&0xff;
		r.extent=DiskImage.le4(b,at+2)&0xffffffffL;r.length=DiskImage.le4(b,at+10)&0xffffffffL;
		r.date=shortDate(b,at+18);r.flags=b[at+25]&0xff;
		int nameLength=b[at+32]&0xff;
		r.isoName=java.util.Arrays.copyOfRange(b,at+33,at+33+nameLength);
		int use=at+33+nameLength;if((nameLength&1)==0)use++;
		v.systemUse(b,use,at+length,r,0);
		return r;
	}
	/** SUSP entries: Rock Ridge "NM" (name, possibly in pieces), "CE" (continuation area), Apple "AA" (type, creator, Finder flags). */
	private void systemUse(byte[] b,int at,int end,Record r,int depth) throws IOException {
		StringBuilder name=null;
		while(at+4<=end){
			int length=b[at+2]&0xff;if(length<4||at+length>end)break;
			char s1=(char)(b[at]&0xff),s2=(char)(b[at+1]&0xff);
			if(s1=='N'&&s2=='M'&&length>=5){
				int flags=b[at+4]&0xff;
				if((flags&6)==0){if(name==null)name=new StringBuilder();name.append(new String(b,at+5,length-5,StandardCharsets.UTF_8));}
			}else if(s1=='A'&&s2=='A'&&length>=14){
				r.appleInfo=java.util.Arrays.copyOfRange(b,at+4,at+14); // type, creator, Finder flags
			}else if(s1=='C'&&s2=='E'&&length>=28&&depth<8){
				long block=DiskImage.le4(b,at+4)&0xffffffffL;int offset=DiskImage.le4(b,at+12),size=DiskImage.le4(b,at+20);
				if(size>0&&size<=SECTOR*4&&offset>=0){byte[] more=source.read(block*blockSize+offset,size);Record sub=new Record();systemUse(more,0,size,sub,depth+1);
					if(sub.rockName!=null)name=(name==null?new StringBuilder():name).append(sub.rockName);if(sub.appleInfo!=null)r.appleInfo=sub.appleInfo;}
			}else if(s1=='S'&&s2=='T')break;
			at+=length;
		}
		if(name!=null)r.rockName=name.toString();
	}

	/** An ISO name: "README.TXT;1" → "README.TXT", "FOLDER." → "FOLDER". */
	private static String isoName(byte[] raw,boolean directory){
		String n=new String(raw,StandardCharsets.ISO_8859_1);
		int semicolon=n.lastIndexOf(';');if(semicolon>0&&!directory)n=n.substring(0,semicolon);
		if(n.endsWith(".")&&n.length()>1)n=n.substring(0,n.length()-1);
		return n;
	}
	private static String jolietName(byte[] raw){
		String n=new String(raw,0,raw.length&~1,StandardCharsets.UTF_16BE);
		int semicolon=n.lastIndexOf(';');if(semicolon>0)n=n.substring(0,semicolon);
		if(n.endsWith(".")&&n.length()>1)n=n.substring(0,n.length()-1);
		return n;
	}

	/** The 7-byte recording date of a directory record as a local Mac date. */
	private static int shortDate(byte[] b,int at){
		int year=1900+(b[at]&0xff),month=b[at+1],day=b[at+2],hour=b[at+3],minute=b[at+4],second=b[at+5],offset=b[at+6];
		return macDate(year,month,day,hour,minute,second,offset);
	}
	/** The 17-byte ASCII date of a volume descriptor as a local Mac date (0 when unset). */
	private static int longDate(byte[] b,int at){
		try{
			String s=new String(b,at,16,StandardCharsets.US_ASCII);
			if(s.startsWith("0000"))return 0;
			return macDate(Integer.parseInt(s.substring(0,4)),Integer.parseInt(s.substring(4,6)),Integer.parseInt(s.substring(6,8)),
				Integer.parseInt(s.substring(8,10)),Integer.parseInt(s.substring(10,12)),Integer.parseInt(s.substring(12,14)),b[at+16]);
		}catch(RuntimeException e){return 0;}
	}
	private static int macDate(int year,int month,int day,int hour,int minute,int second,int quarterHours){
		try{
			java.time.ZoneOffset zone=java.time.ZoneOffset.ofTotalSeconds(Math.max(-48,Math.min(52,quarterHours))*900);
			long unix=java.time.LocalDateTime.of(year,month,day,hour,minute,second).toEpochSecond(zone);
			return (int)(unix+2_082_844_800L+Toolbox68k.localOffsetSeconds());
		}catch(RuntimeException e){return 0;}
	}
}

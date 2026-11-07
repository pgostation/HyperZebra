import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only HFS and MFS volumes (Inside Macintosh: Files, "Data Organization on Volumes"). HFS: the Master Directory Block in
 * logical block 2, the extents overflow and catalog B*-trees (walked leaf by leaf), forks as allocation-block extents. MFS
 * (the 400K disks of 1984-86): one flat file directory and a 12-bit block map; its folders existed only in the Finder's
 * Desktop file, so every file comes out in the root folder. HFS Plus (TN1150, Mac OS 8.1 and later): the volume header in
 * block 2, 32-bit allocation blocks, eight extents per fork, Unicode names and GMT dates in the catalog.
 */
final class HfsVolume {
	private HfsVolume(){}

	private static int u1(byte[] b,int o){return b[o]&0xff;}
	private static int u2(byte[] b,int o){return DiskImage.u2(b,o);}
	private static int u4(byte[] b,int o){return DiskImage.u4(b,o);}

	// ------------------------------------------------------------------ HFS

	/** Where a fork lives on HFS: its extents (allocation block, count) in order, and the logical length. */
	private static final class Extents {final List<int[]> runs=new ArrayList<int[]>();long length;int fileId,forkType;
		long blocks(){long n=0;for(int[] r:runs)n+=r[1];return n;}}

	private static final class Hfs extends DiskImage.Volume {
		final DiskImage.Source source;final long firstBlock;final int blockSize;
		/** Overflow extent records: key fileId<<8|forkType → {file block, start, count}. */
		final Map<Long,List<int[]>> overflow=new HashMap<Long,List<int[]>>();
		Hfs(DiskImage.Source source,long firstBlock,int blockSize){this.source=source;this.firstBlock=firstBlock;this.blockSize=blockSize;kind="HFS";}

		@Override void copyFork(DiskImage.Entry entry,boolean resource,OutputStream out) throws IOException {
			Extents e=(Extents)(resource?entry.resourcePlace:entry.dataPlace);
			if(e!=null)copy(e,out);
		}
		void copy(Extents e,OutputStream out) throws IOException {
			complete(e);
			long left=e.length;byte[] buffer=new byte[Math.max(blockSize,65536/blockSize*blockSize)];
			for(int[] run:e.runs){
				long at=firstBlock+(long)run[0]*blockSize,runBytes=(long)run[1]*blockSize;
				while(runBytes>0&&left>0){int n=(int)Math.min(buffer.length,Math.min(runBytes,left));source.read(at,buffer,0,n);out.write(buffer,0,n);at+=n;runBytes-=n;left-=n;}
				if(left<=0)break;
			}
			// a damaged volume can promise more than its extents hold: the rest reads as zeros, so lengths stay what the catalog says
			byte[] zeros=new byte[8192];while(left>0){int n=(int)Math.min(zeros.length,left);out.write(zeros,0,n);left-=n;}
		}
		byte[] bytes(Extents e) throws IOException {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream((int)Math.min(1<<26,e.length));copy(e,out);return out.toByteArray();}
		/** Appends the overflow extents of a fork whose three catalog extents do not cover it. */
		void complete(Extents e){
			if((long)e.blocks()*blockSize>=e.length)return;
			List<int[]> more=overflow.get(((long)e.fileId<<8)|e.forkType);
			if(more==null)return;
			long have=e.blocks();
			for(int[] r:more){if(r[0]<have)continue;if(r[0]>have)break;for(int i=0;i<3;i++){int start=r[1+2*i],count=r[2+2*i];if(count==0)break;e.runs.add(new int[]{start,count});have+=count;}}
		}
	}

	static DiskImage.Volume readHfs(DiskImage.Source source) throws IOException {
		byte[] mdb=source.read(1024,162);
		int blockSize=u4(mdb,20);
		long firstBlock=u2(mdb,28)*512L;
		Hfs v=new Hfs(source,firstBlock,blockSize);
		v.created=u4(mdb,2);v.modified=u4(mdb,6);
		v.name=java.util.Arrays.copyOfRange(mdb,37,37+Math.min(27,u1(mdb,36)));
		// the extents overflow file first: the catalog's own extents may continue there
		Extents extentsFile=extents(mdb,134,u4(mdb,130),3,0);
		if(!plausible(extentsFile,v,source))throw new java.io.IOException("damaged HFS volume (extents file)");
		byte[] extentsTree=v.bytes(extentsFile);
		for(byte[][] record:leafRecords(extentsTree)){
			byte[] key=record[0],data=record[1];
			// the key without its length byte (leafRecords): forkType, fileID, startBlock - read one byte further on, as if the
			// length were still there, no record ever matched and a fork in more than three pieces ended in zeros (Crystal Quest
			// 2.2c's resource map, the last 64 of its 262 blocks)
			if(key.length<7||data.length<12)continue;
			int forkType=u1(key,0),fileId=u4(key,1),fileBlock=u2(key,5);
			int[] r=new int[7];r[0]=fileBlock;for(int i=0;i<6;i++)r[1+i]=u2(data,2*i);
			v.overflow.computeIfAbsent(((long)fileId<<8)|forkType,k->new ArrayList<int[]>()).add(r);
		}
		for(List<int[]> list:v.overflow.values())list.sort(Comparator.comparingInt(r->r[0]));
		Extents catalogFile=extents(mdb,150,u4(mdb,146),4,0);
		if(!plausible(catalogFile,v,source))throw new java.io.IOException("damaged HFS volume (catalog file)");
		byte[] catalogTree=v.bytes(catalogFile);
		Map<Integer,DiskImage.Entry> folders=new HashMap<Integer,DiskImage.Entry>();
		Map<DiskImage.Entry,Integer> parents=new HashMap<DiskImage.Entry,Integer>();
		for(byte[][] record:leafRecords(catalogTree)){
			byte[] key=record[0],data=record[1];
			if(key.length<6||data.length<2)continue;
			int parent=u4(key,1),nameLength=Math.min(31,u1(key,5));
			if(6+nameLength>key.length)nameLength=key.length-6;
			byte[] name=java.util.Arrays.copyOfRange(key,6,6+nameLength);
			int type=u1(data,0);
			if(type==1&&data.length>=70){
				int id=u4(data,6);
				DiskImage.Entry e=id==2?v.root:new DiskImage.Entry();
				e.folder=true;e.id=id;e.name=name;e.created=u4(data,10);e.modified=u4(data,14);
				System.arraycopy(data,22,e.finderInfo,0,16);System.arraycopy(data,38,e.extendedInfo,0,16);
				folders.put(id,e);if(id!=2)parents.put(e,parent);
			}else if(type==2&&data.length>=102){
				DiskImage.Entry e=new DiskImage.Entry();
				e.name=name;e.locked=(u1(data,2)&1)!=0;e.attributes=u1(data,2)&0x40;
				System.arraycopy(data,4,e.finderInfo,0,16);
				e.id=u4(data,20);
				e.dataLength=u4(data,26)&0xffffffffL;e.resourceLength=u4(data,36)&0xffffffffL;
				e.created=u4(data,44);e.modified=u4(data,48);
				System.arraycopy(data,56,e.extendedInfo,0,16);
				Extents d=extents(data,74,e.dataLength,e.id,0),r=extents(data,86,e.resourceLength,e.id,0xff);
				e.dataPlace=d;e.resourcePlace=r;
				parents.put(e,parent);
			}
		}
		v.root.folder=true;if(v.root.id==0){v.root.id=2;v.root.name=v.name;}
		// a record whose parent is missing (a damaged catalog) is kept in the root rather than lost
		for(Map.Entry<DiskImage.Entry,Integer> p:parents.entrySet()){
			DiskImage.Entry parent=folders.get(p.getValue());
			if(parent==null||parent==p.getKey()||inside(parent,p.getKey(),folders,parents))parent=v.root;
			parent.children.add(p.getKey());
		}
		return v;
	}
	/** Whether a B-tree file's size fits the volume (a volume that is not HFS after all, or a damaged one, can claim gigabytes). */
	private static boolean plausible(Extents e,Hfs v,DiskImage.Source source){return e.length>=512&&e.length<=source.length()&&e.length<=(long)Integer.MAX_VALUE-8;}
	/** Whether {@code folder} lies inside {@code entry} (a cycle in a damaged catalog). */
	private static boolean inside(DiskImage.Entry folder,DiskImage.Entry entry,Map<Integer,DiskImage.Entry> folders,Map<DiskImage.Entry,Integer> parents){
		Set<DiskImage.Entry> seen=new HashSet<DiskImage.Entry>();
		for(DiskImage.Entry f=folder;f!=null&&seen.add(f);){if(f==entry)return true;Integer p=parents.get(f);f=p==null?null:folders.get(p);}
		return false;
	}
	/** Three extent descriptors at {@code offset} (start block, count), for a fork of {@code length} bytes. */
	private static Extents extents(byte[] b,int offset,long length,int fileId,int forkType){
		Extents e=new Extents();e.length=length;e.fileId=fileId;e.forkType=forkType;
		for(int i=0;i<3;i++){int start=u2(b,offset+4*i),count=u2(b,offset+4*i+2);if(count==0)break;e.runs.add(new int[]{start,count});}
		return e;
	}
	/** Every record in the leaf nodes of a B*-tree held in {@code tree}: {key (without its length byte), data}. */
	private static List<byte[][]> leafRecords(byte[] tree){
		List<byte[][]> out=new ArrayList<byte[][]>();
		if(tree.length<512)return out;
		int nodeSize=u2(tree,32);if(nodeSize<512||(nodeSize&(nodeSize-1))!=0)nodeSize=512;
		int nodes=tree.length/nodeSize;
		int node=u4(tree,24); // header record: bthFNode
		Set<Integer> seen=new HashSet<Integer>();
		while(node>0&&node<nodes&&seen.add(node)){
			int base=node*nodeSize;
			int kind=(byte)tree[base+8],count=u2(tree,base+10);
			if(kind!=-1)break;
			for(int i=0;i<count;i++){
				int at=u2(tree,base+nodeSize-2*(i+1));if(at<14||at>=nodeSize)continue;
				int rec=base+at,keyLength=u1(tree,rec);if(keyLength==0)continue;
				int end=u2(tree,base+nodeSize-2*(i+2));if(end<=at||end>nodeSize-2*(count+1))end=nodeSize-2*(count+1);
				int dataStart=at+1+keyLength;if((dataStart&1)!=0)dataStart++;
				if(dataStart>end)continue;
				out.add(new byte[][]{java.util.Arrays.copyOfRange(tree,rec+1,rec+1+keyLength),java.util.Arrays.copyOfRange(tree,base+dataStart,base+end)});
			}
			node=u4(tree,base);
		}
		return out;
	}

	// ------------------------------------------------------------------ MFS

	private static final class Mfs extends DiskImage.Volume {
		final DiskImage.Source source;final long firstBlock;final int blockSize;final int[] map;
		Mfs(DiskImage.Source source,long firstBlock,int blockSize,int[] map){this.source=source;this.firstBlock=firstBlock;this.blockSize=blockSize;this.map=map;kind="MFS";}
		@Override void copyFork(DiskImage.Entry entry,boolean resource,OutputStream out) throws IOException {
			long left=resource?entry.resourceLength:entry.dataLength;
			int block=(Integer)(resource?entry.resourcePlace:entry.dataPlace);
			byte[] buffer=new byte[blockSize];int guard=map.length+2;
			while(left>0&&block>=2&&block-2<map.length&&guard-->0){
				source.read(firstBlock+(long)(block-2)*blockSize,buffer,0,blockSize);
				int n=(int)Math.min(blockSize,left);out.write(buffer,0,n);left-=n;
				block=map[block-2];if(block==1)break;
			}
			byte[] zeros=new byte[4096];while(left>0){int n=(int)Math.min(zeros.length,left);out.write(zeros,0,n);left-=n;}
		}
	}

	static DiskImage.Volume readMfs(DiskImage.Source source) throws IOException {
		byte[] mdb=source.read(1024,64);
		int directoryStart=u2(mdb,14),directoryLength=u2(mdb,16),blocks=u2(mdb,18),blockSize=u4(mdb,20),firstBlock=u2(mdb,28);
		// the block map follows the MDB: 12 bits per allocation block, blocks numbered from 2
		byte[] mapBytes=source.read(1024+64,(blocks*3+1)/2+2);
		int[] map=new int[blocks];
		for(int i=0;i<blocks;i++){int bit=i*12,o=bit/8;int v=u2(mapBytes,o);map[i]=(bit%8==0)?v>>>4:v&0xfff;}
		Mfs v=new Mfs(source,firstBlock*512L,blockSize,map);
		v.created=u4(mdb,2);v.modified=u4(mdb,6);
		v.name=java.util.Arrays.copyOfRange(mdb,37,37+Math.min(27,u1(mdb,36)));
		v.root.folder=true;v.root.id=2;v.root.name=v.name;v.root.created=v.created;v.root.modified=v.modified;
		byte[] directory=source.read(directoryStart*512L,directoryLength*512);
		for(int sector=0;sector<directoryLength;sector++){
			int at=sector*512;
			while(at+51<=(sector+1)*512){
				int flags=u1(directory,at);
				if((flags&0x80)==0)break; // the rest of this sector is unused
				int nameLength=u1(directory,at+50);
				if(at+51+nameLength>(sector+1)*512)break;
				DiskImage.Entry e=new DiskImage.Entry();
				e.locked=(flags&1)!=0;e.attributes=flags&0x40;
				System.arraycopy(directory,at+2,e.finderInfo,0,16);
				e.finderInfo[14]=0;e.finderInfo[15]=0; // fdFldr: the Finder's folder number, meaningless outside its Desktop file
				e.id=u4(directory,at+18);
				e.dataPlace=u2(directory,at+22);e.dataLength=u4(directory,at+24)&0xffffffffL;
				e.resourcePlace=u2(directory,at+32);e.resourceLength=u4(directory,at+34)&0xffffffffL;
				e.created=u4(directory,at+42);e.modified=u4(directory,at+46);
				e.name=java.util.Arrays.copyOfRange(directory,at+51,at+51+nameLength);
				v.root.children.add(e);
				at+=51+nameLength;if((at&1)!=0)at++;
			}
		}
		return v;
	}

	// ------------------------------------------------------------------ HFS Plus

	/** A fork on HFS Plus: its extents {start block, block count} in order and the logical length. */
	private static final class PlusFork {final List<long[]> runs=new ArrayList<long[]>();long length;int fileId,forkType;
		long blocks(){long n=0;for(long[] r:runs)n+=r[1];return n;}}

	private static final class HfsPlus extends DiskImage.Volume {
		final DiskImage.Source source;final int blockSize;
		/** Overflow extent records: key fileId<<8|forkType → {file block, then 8 × (start, count)}. */
		final Map<Long,List<long[]>> overflow=new HashMap<Long,List<long[]>>();
		HfsPlus(DiskImage.Source source,int blockSize,String kind){this.source=source;this.blockSize=blockSize;this.kind=kind;}
		@Override void copyFork(DiskImage.Entry entry,boolean resource,OutputStream out) throws IOException {
			PlusFork f=(PlusFork)(resource?entry.resourcePlace:entry.dataPlace);
			if(f!=null)copy(f,out);
		}
		void copy(PlusFork f,OutputStream out) throws IOException {
			complete(f);
			long left=f.length;byte[] buffer=new byte[Math.max(blockSize,(1<<16)/blockSize*blockSize)];
			for(long[] run:f.runs){
				long at=run[0]*blockSize,runBytes=run[1]*blockSize;
				while(runBytes>0&&left>0){int n=(int)Math.min(buffer.length,Math.min(runBytes,left));source.read(at,buffer,0,n);out.write(buffer,0,n);at+=n;runBytes-=n;left-=n;}
				if(left<=0)break;
			}
			byte[] zeros=new byte[8192];while(left>0){int n=(int)Math.min(zeros.length,left);out.write(zeros,0,n);left-=n;}
		}
		byte[] bytes(PlusFork f) throws IOException {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream((int)Math.min(1<<26,f.length));copy(f,out);return out.toByteArray();}
		void complete(PlusFork f){
			if(f.blocks()*blockSize>=f.length)return;
			List<long[]> more=overflow.get(((long)f.fileId<<8)|f.forkType);
			if(more==null)return;
			long have=f.blocks();
			for(long[] r:more){if(r[0]<have)continue;if(r[0]>have)break;for(int i=0;i<8;i++){long start=r[1+2*i],count=r[2+2*i];if(count==0)break;f.runs.add(new long[]{start,count});have+=count;}}
		}
	}

	private static long u4l(byte[] b,int o){return u4(b,o)&0xffffffffL;}
	private static PlusFork plusFork(byte[] b,int offset,int fileId,int forkType){
		PlusFork f=new PlusFork();f.fileId=fileId;f.forkType=forkType;
		f.length=(u4l(b,offset)<<32)|u4l(b,offset+4);
		for(int i=0;i<8;i++){long start=u4l(b,offset+16+8*i),count=u4l(b,offset+20+8*i);if(count==0)break;f.runs.add(new long[]{start,count});}
		return f;
	}
	/** A catalog date (GMT seconds since 1904) as the local Mac date the rest of this reader uses; 0 stays 0. */
	private static int localDate(int gmt){return gmt==0?0:gmt+Toolbox68k.localOffsetSeconds();}

	/** The HFS Plus (or HFSX) volume whose header is at byte 1024 of {@code source}. */
	static DiskImage.Volume readHfsPlus(DiskImage.Source source) throws IOException {
		byte[] header=source.read(1024,512);
		int blockSize=u4(header,40);
		if(blockSize<512||(blockSize&(blockSize-1))!=0)throw new IOException("damaged HFS Plus volume (block size)");
		HfsPlus v=new HfsPlus(source,blockSize,u2(header,0)==DiskImage.HFSX_SIGNATURE?"HFSX":"HFS Plus");
		v.created=u4(header,16);v.modified=localDate(u4(header,20)); // the header's creation date alone is local time
		PlusFork extentsFile=plusFork(header,192,3,0),catalogFile=plusFork(header,272,4,0);
		if(extentsFile.length>source.length()||catalogFile.length<512||catalogFile.length>source.length()||catalogFile.length>Integer.MAX_VALUE-8)throw new IOException("damaged HFS Plus volume (B-tree sizes)");
		if(extentsFile.length>=512)for(byte[][] record:leafRecordsPlus(v.bytes(extentsFile))){
			byte[] key=record[0],data=record[1];
			if(key.length<10||data.length<64)continue;
			int forkType=u1(key,0),fileId=u4(key,2);long fileBlock=u4l(key,6);
			long[] r=new long[17];r[0]=fileBlock;for(int i=0;i<16;i++)r[1+i]=u4l(data,4*i);
			v.overflow.computeIfAbsent(((long)fileId<<8)|forkType,k->new ArrayList<long[]>()).add(r);
		}
		for(List<long[]> list:v.overflow.values())list.sort(Comparator.comparingLong(r->r[0]));
		Map<Integer,DiskImage.Entry> folders=new HashMap<Integer,DiskImage.Entry>();
		Map<DiskImage.Entry,Integer> parents=new HashMap<DiskImage.Entry,Integer>();
		Map<Integer,DiskImage.Entry> inodes=new HashMap<Integer,DiskImage.Entry>();
		List<DiskImage.Entry> links=new ArrayList<DiskImage.Entry>();
		int privateFolder=-1;
		for(byte[][] record:leafRecordsPlus(v.bytes(catalogFile))){
			byte[] key=record[0],data=record[1];
			if(key.length<6||data.length<2)continue;
			int parent=u4(key,0),length=Math.min(255,u2(key,4));
			if(6+2*length>key.length)length=(key.length-6)/2;
			String name=java.text.Normalizer.normalize(new String(key,6,2*length,java.nio.charset.StandardCharsets.UTF_16BE),java.text.Normalizer.Form.NFC);
			int type=u2(data,0);
			if(type==1&&data.length>=88){
				int id=u4(data,8);
				if(parent==2&&name.startsWith("\0\0\0\0HFS+ Private Data")){privateFolder=id;continue;} // hard-link targets: kept out of sight
				DiskImage.Entry e=id==2?v.root:new DiskImage.Entry();
				e.folder=true;e.id=id;e.unicodeName=name;e.created=localDate(u4(data,12));e.modified=localDate(u4(data,16));
				System.arraycopy(data,48,e.finderInfo,0,16);System.arraycopy(data,64,e.extendedInfo,0,16);
				folders.put(id,e);if(id!=2)parents.put(e,parent);
			}else if(type==2&&data.length>=248){
				DiskImage.Entry e=new DiskImage.Entry();
				e.unicodeName=name;e.locked=(u2(data,2)&1)!=0;e.id=u4(data,8);
				e.created=localDate(u4(data,12));e.modified=localDate(u4(data,16));
				System.arraycopy(data,48,e.finderInfo,0,16);System.arraycopy(data,64,e.extendedInfo,0,16);
				PlusFork d=plusFork(data,88,e.id,0),r=plusFork(data,168,e.id,0xff);
				e.dataPlace=d;e.resourcePlace=r;e.dataLength=d.length;e.resourceLength=r.length;
				if(name.startsWith("iNode")){inodes.put(parseInt(name.substring(5)),e);parents.put(e,parent);continue;}
				if(new String(e.finderInfo,0,8,java.nio.charset.StandardCharsets.ISO_8859_1).equals("hlnkhfs+")){e.id=u4(data,44);links.add(e);} // a hard link: its data lives in iNode<n>
				if(parent==2&&(name.equals(".journal")||name.equals(".journal_info_block")))continue;
				parents.put(e,parent);
			}
		}
		v.root.folder=true;if(v.root.id==0){v.root.id=2;}
		v.unicodeName=v.root.unicodeName;
		for(DiskImage.Entry link:links){
			DiskImage.Entry target=inodes.get(link.id);if(target==null)continue;
			link.dataPlace=target.dataPlace;link.resourcePlace=target.resourcePlace;link.dataLength=target.dataLength;link.resourceLength=target.resourceLength;
			System.arraycopy(target.finderInfo,0,link.finderInfo,0,16);link.created=target.created;link.modified=target.modified;
		}
		for(Map.Entry<DiskImage.Entry,Integer> p:parents.entrySet()){
			int parentId=p.getValue();
			if(parentId==privateFolder&&privateFolder>0)continue;
			if(p.getKey().unicodeName!=null&&p.getKey().unicodeName.startsWith("\0"))continue; // other private folders (directory hard links)
			DiskImage.Entry parent=folders.get(parentId);
			if(parent==null||parent==p.getKey()||inside(parent,p.getKey(),folders,parents))parent=v.root;
			parent.children.add(p.getKey());
		}
		return v;
	}
	private static int parseInt(String s){try{return Integer.parseInt(s);}catch(NumberFormatException e){return -1;}}
	/** Leaf records of an HFS Plus B-tree (16-bit key lengths): {key without its length, data}. */
	private static List<byte[][]> leafRecordsPlus(byte[] tree){
		List<byte[][]> out=new ArrayList<byte[][]>();
		if(tree.length<512)return out;
		int nodeSize=u2(tree,32);if(nodeSize<512||(nodeSize&(nodeSize-1))!=0||nodeSize>tree.length)return out;
		int nodes=tree.length/nodeSize;
		int node=u4(tree,24);
		Set<Integer> seen=new HashSet<Integer>();
		while(node>0&&node<nodes&&seen.add(node)){
			int base=node*nodeSize;
			int kind=(byte)tree[base+8],count=u2(tree,base+10);
			if(kind!=-1)break;
			for(int i=0;i<count;i++){
				int at=u2(tree,base+nodeSize-2*(i+1));if(at<14||at+2>nodeSize)continue;
				int end=u2(tree,base+nodeSize-2*(i+2));if(end<=at||end>nodeSize-2*(count+1))end=nodeSize-2*(count+1);
				int keyLength=u2(tree,base+at);
				int dataStart=at+2+keyLength;if((dataStart&1)!=0)dataStart++;
				if(keyLength==0||dataStart>end)continue;
				out.add(new byte[][]{java.util.Arrays.copyOfRange(tree,base+at+2,base+at+2+keyLength),java.util.Arrays.copyOfRange(tree,base+dataStart,base+end)});
			}
			node=u4(tree,base);
		}
		return out;
	}
}

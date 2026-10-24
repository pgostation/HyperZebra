import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * A classic Macintosh disk image, read without a Mac: the container is taken off (Disk Copy 4.2, Disk Copy 6 / NDIF, UDIF,
 * a MacBinary wrapper, an Apple partition map, a GUID or MBR partition table, or none - a raw volume as .dsk/.hfv/.toast and many .img files are) and the volume inside is read
 * as HFS, HFS Plus or MFS ({@link HfsVolume}) or ISO 9660 ({@link IsoVolume}), read-only. What comes out is a tree of {@link Entry} with both forks and the Finder
 * information, which {@link DiskImages} writes into a host folder the 68K VM mounts as a disk.
 *
 * Everything here follows the published formats (Inside Macintosh: Files and Devices; the Disk Copy 4.2 header as
 * documented by Apple's Disk Copy notes); no Apple code or data is used.
 */
final class DiskImage implements Closeable {
	/** Random-access bytes of a disk (a whole image, or a part of one). Reads past the end give zeros. */
	interface Source extends Closeable {
		long length();
		void read(long position,byte[] buffer,int offset,int length) throws IOException;
		default byte[] read(long position,int length) throws IOException {byte[] b=new byte[length];read(position,b,0,length);return b;}
		@Override default void close() throws IOException {}
	}

	/** A range of a host file. */
	static final class FileSource implements Source {
		private final RandomAccessFile file;private final long base,length;
		FileSource(RandomAccessFile file,long base,long length){this.file=file;this.base=base;this.length=length;}
		@Override public long length(){return length;}
		@Override public synchronized void read(long position,byte[] buffer,int offset,int count) throws IOException {
			java.util.Arrays.fill(buffer,offset,offset+count,(byte)0);
			if(position<0||position>=length)return;
			int n=(int)Math.min(count,length-position);
			file.seek(base+position);file.readFully(buffer,offset,n);
		}
		@Override public void close() throws IOException {file.close();}
	}

	/** Bytes in memory: a disk read block by block through a driver (a disk-image utility's RAM disk). */
	static final class BytesSource implements Source {
		private final byte[] bytes;
		BytesSource(byte[] bytes){this.bytes=bytes;}
		@Override public long length(){return bytes.length;}
		@Override public void read(long position,byte[] buffer,int offset,int count){
			java.util.Arrays.fill(buffer,offset,offset+count,(byte)0);
			if(position<0||position>=bytes.length)return;
			System.arraycopy(bytes,(int)position,buffer,offset,(int)Math.min(count,bytes.length-position));
		}
	}

	/**
	 * A disk whose volume starts in the middle and wraps round at the end: the Twiggy (FileWare) disks of the 1983 Macintosh
	 * prototypes keep the MFS volume from the middle track outwards and on from the first one.
	 */
	static final class Rotated implements Source {
		private final Source inner;private final long start;
		Rotated(Source inner,long start){this.inner=inner;this.start=start;}
		@Override public long length(){return inner.length();}
		@Override public void read(long position,byte[] buffer,int offset,int count) throws IOException {
			long length=inner.length();
			while(count>0){long p=Math.floorMod(start+position,length);int n=(int)Math.min(count,length-p);inner.read(p,buffer,offset,n);position+=n;offset+=n;count-=n;}
		}
		@Override public void close() throws IOException {inner.close();}
	}

	/**
	 * A raw CD image ripped with 2352-byte sectors (BIN/CUE, MODE1/2352 or MODE2/2352): each sector is a 12-byte sync
	 * pattern, the address and mode, then the 2048 bytes of user data (offset 16 in mode 1, 24 in mode 2 form 1) and the
	 * error codes. The source is the user data alone, so the volume reads as on a CD drive (The Manhole CD-ROM).
	 */
	static final class RawCdSource implements Source {
		static final int SECTOR=2352,USER=2048;
		private final Source inner;private final int dataOffset;private final long length;
		RawCdSource(Source inner,int dataOffset){this.inner=inner;this.dataOffset=dataOffset;this.length=inner.length()/SECTOR*USER;}
		@Override public long length(){return length;}
		@Override public void read(long position,byte[] buffer,int offset,int count) throws IOException {
			java.util.Arrays.fill(buffer,offset,offset+count,(byte)0);
			while(count>0&&position<length){
				long sector=position/USER;int within=(int)(position%USER),n=Math.min(count,USER-within);
				inner.read(sector*SECTOR+dataOffset+within,buffer,offset,n);
				position+=n;offset+=n;count-=n;
			}
		}
		@Override public void close() throws IOException {inner.close();}
		/** The user data of {@code s} when it starts with a CD sync pattern in 2352-byte sectors, else null. */
		static Source of(Source s) throws IOException {
			if(s.length()<SECTOR*17L||s.length()%SECTOR!=0)return null;
			byte[] h=s.read(0,16);
			if(h[0]!=0||h[11]!=0)return null;
			for(int i=1;i<11;i++)if(h[i]!=(byte)0xff)return null;
			byte[] next=s.read(SECTOR,12);for(int i=1;i<11;i++)if(next[i]!=(byte)0xff)return null; // the next sector too
			return h[15]==2?new RawCdSource(s,24):h[15]==1?new RawCdSource(s,16):null;
		}
	}

	/** The disk a .cue sheet describes (its first FILE line, relative to the sheet); the file itself for anything else. */
	static File cueTarget(File file){
		if(file==null||!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".cue")||file.length()>65536)return file;
		try{
			java.util.regex.Pattern p=java.util.regex.Pattern.compile("^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))",java.util.regex.Pattern.CASE_INSENSITIVE);
			for(String line:java.nio.file.Files.readAllLines(file.toPath(),java.nio.charset.StandardCharsets.ISO_8859_1)){
				java.util.regex.Matcher m=p.matcher(line);
				if(!m.find())continue;
				File f=new File(file.getParentFile(),m.group(1)!=null?m.group(1):m.group(2));
				return f.isFile()?f:file;
			}
		}catch(IOException|RuntimeException e){}
		return file;
	}

	/** A range of another source (a partition). */
	static final class Slice implements Source {
		private final Source inner;private final long base,length;
		Slice(Source inner,long base,long length){this.inner=inner;this.base=base;this.length=Math.max(0,Math.min(length,inner.length()-base));}
		@Override public long length(){return length;}
		@Override public void read(long position,byte[] buffer,int offset,int count) throws IOException {
			java.util.Arrays.fill(buffer,offset,offset+count,(byte)0);
			if(position<0||position>=length)return;
			inner.read(base+position,buffer,offset,(int)Math.min(count,length-position));
		}
		@Override public void close() throws IOException {inner.close();}
	}

	/**
	 * A disk kept as chunks of sectors - zeros, stored, or compressed (ADC, zlib, bzip2) - in a data fork: Disk Copy 6 (NDIF)
	 * and UDIF (.dmg) images. Chunks are decompressed on demand and the last few kept.
	 */
	static class ChunkSource implements Source {
		static final int ZERO=0,RAW=1,ADC=2,ZLIB=3,BZIP2=4;
		private final Source data;private final long length;
		/** Per chunk, sorted by sector: first sector, sector count, kind, data offset, stored length. */
		private final long[][] chunks;
		private final java.util.LinkedHashMap<Integer,byte[]> cache=new java.util.LinkedHashMap<Integer,byte[]>(16,0.75f,true){
			@Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer,byte[]> e){return size()>8;}};
		ChunkSource(Source data,List<long[]> chunks,long sectors){
			this.data=data;chunks.sort((x,y)->Long.compare(x[0],y[0]));this.chunks=chunks.toArray(new long[0][]);length=sectors*512;
		}
		@Override public long length(){return length;}
		@Override public synchronized void read(long position,byte[] buffer,int offset,int count) throws IOException {
			java.util.Arrays.fill(buffer,offset,offset+count,(byte)0);
			while(count>0&&position<length){
				int index=chunkAt(position/512);
				if(index<0){int n=(int)Math.min(count,512-position%512);position+=n;offset+=n;count-=n;continue;}
				long[] c=chunks[index];long chunkStart=c[0]*512,chunkEnd=chunkStart+c[1]*512;
				int n=(int)Math.min(count,chunkEnd-position);
				int kind=(int)c[2];
				if(kind==RAW)data.read(c[3]+(position-chunkStart),buffer,offset,(int)Math.min(n,Math.max(0,c[4]-(position-chunkStart))));
				else if(kind!=ZERO){byte[] plain=decompressed(index);int from=(int)(position-chunkStart);System.arraycopy(plain,from,buffer,offset,Math.max(0,Math.min(n,plain.length-from)));}
				position+=n;offset+=n;count-=n;
			}
		}
		private int chunkAt(long sector){
			int lo=0,hi=chunks.length-1;
			while(lo<=hi){int mid=(lo+hi)>>>1;long[] c=chunks[mid];if(sector<c[0])hi=mid-1;else if(sector>=c[0]+c[1])lo=mid+1;else return mid;}
			return -1;
		}
		private byte[] decompressed(int index) throws IOException {
			byte[] plain=cache.get(index);if(plain!=null)return plain;
			long[] c=chunks[index];
			if(c[4]>256L<<20||c[1]*512>256L<<20)throw new IOException("disk image chunk too large");
			byte[] stored=data.read(c[3],(int)c[4]);int size=(int)(c[1]*512);
			switch((int)c[2]){
				case ADC:plain=adc(stored,size);break;
				case ZLIB:plain=inflate(stored,size);break;
				case BZIP2:plain=Bzip2.decompress(stored,size);break;
				default:plain=new byte[size];
			}
			cache.put(index,plain);return plain;
		}
		private static byte[] inflate(byte[] stored,int size) throws IOException {
			java.util.zip.Inflater inflater=new java.util.zip.Inflater();
			try{inflater.setInput(stored);byte[] out=new byte[size];int n=0;
				while(n<size&&!inflater.finished()){int k=inflater.inflate(out,n,size-n);if(k==0&&(inflater.needsInput()||inflater.needsDictionary()))break;n+=k;}
				return out;}
			catch(java.util.zip.DataFormatException e){throw new IOException("damaged zlib chunk: "+e.getMessage());}
			finally{inflater.end();}
		}
		@Override public void close() throws IOException {data.close();}
	}

	/**
	 * A Disk Copy 6 (NDIF) image: the 'bcem' resource lists the chunks of the disk - sectors of zeros, stored sectors, or
	 * sectors compressed with ADC - held in the data fork. A self-mounting image (.smi) is the same data behind a small
	 * application.
	 */
	static ChunkSource ndif(Source data,byte[] bcem) throws IOException {
		if(bcem.length<0x80)throw new IOException("bad NDIF header");
		long sectors=u4(bcem,0x44)&0xffffffffL;int count=u4(bcem,0x7c);
		if(count<1||0x80+12L*count>bcem.length)throw new IOException("bad NDIF chunk table");
		List<long[]> list=new ArrayList<long[]>();
		for(int i=0;i<count;i++){
			int at=0x80+12*i;int type=bcem[at+3]&0xff;
			long start=(u4(bcem,at)>>>8)&0xffffffL;
			if(type==0xff)break;
			long next=i+1<count?(u4(bcem,at+12)>>>8)&0xffffffL:sectors;
			if(next<=start)continue;
			int kind=type==0||type==1?ChunkSource.ZERO:type==2?ChunkSource.RAW:type==0x83?ChunkSource.ADC:-1;
			if(kind<0)throw new UnsupportedImageException("Disk Copy 6 (NDIF) chunk type $"+Integer.toHexString(type));
			list.add(new long[]{start,next-start,kind,u4(bcem,at+4)&0xffffffffL,u4(bcem,at+8)&0xffffffffL});
		}
		return new ChunkSource(data,list,sectors);
	}

	/** The 512-byte UDIF trailer ('koly') at the end of {@code s}, or null. */
	static byte[] udifTrailer(Source s) throws IOException {
		if(s.length()<1024)return null;
		byte[] k=s.read(s.length()-512,512);
		return k[0]=='k'&&k[1]=='o'&&k[2]=='l'&&k[3]=='y'?k:null;
	}
	/**
	 * A UDIF image (.dmg, Disk Copy 6.3 and later): the 'koly' trailer points at the block tables ('mish', one per partition)
	 * - in an XML property list, or in 'blkx' resources of a resource fork kept inside the file (older images) or beside it.
	 * Chunk kinds: zeros, stored, ADC, zlib (UDZO), bzip2 (UDBZ); LZFSE (ULFO) and LZMA (ULMO) are reported as not read.
	 */
	static ChunkSource udif(Source file,byte[] koly,File host) throws IOException {
		long dataOffset=u8(koly,24),sectors=u8(koly,492);
		long xmlOffset=u8(koly,216),xmlLength=u8(koly,224),rsrcOffset=u8(koly,40),rsrcLength=u8(koly,48);
		List<byte[]> tables=new ArrayList<byte[]>();
		if(xmlLength>0&&xmlLength<(64L<<20)){
			String xml=new String(file.read(xmlOffset,(int)xmlLength),java.nio.charset.StandardCharsets.UTF_8);
			int key=xml.indexOf("<key>blkx</key>");
			if(key>=0){
				int end=xml.indexOf("</array>",key);if(end<0)end=xml.length();
				java.util.regex.Matcher m=java.util.regex.Pattern.compile("<data>(.*?)</data>",java.util.regex.Pattern.DOTALL).matcher(xml.substring(key,end));
				while(m.find())tables.add(java.util.Base64.getMimeDecoder().decode(m.group(1)));
			}
		}
		if(tables.isEmpty()){
			byte[] fork=null;
			if(rsrcLength>0&&rsrcLength<(64L<<20))fork=file.read(rsrcOffset,(int)rsrcLength);
			else if(host!=null)fork=ResourceFile68k.readForkBytes(host,null);
			if(fork!=null&&fork.length>=16){
				ResourceFile68k rf=new ResourceFile68k(host);rf.parse(fork);
				for(ResourceFile68k.Resource r:rf.ofType(ResourceFile68k.fourcc("blkx")))tables.add(r.data());
			}
		}
		if(tables.isEmpty())throw new IOException("UDIF image without block tables");
		List<long[]> list=new ArrayList<long[]>();
		for(byte[] mish:tables){
			if(mish.length<204||mish[0]!='m'||mish[1]!='i'||mish[2]!='s'||mish[3]!='h')continue;
			long first=u8(mish,8),base=u8(mish,24);int count=u4(mish,200);
			for(int i=0;i<count&&204+40*i+40<=mish.length;i++){
				int at=204+40*i;int type=u4(mish,at);
				long start=first+u8(mish,at+8),n=u8(mish,at+16),offset=dataOffset+base+u8(mish,at+24),stored=u8(mish,at+32);
				if(type==0xffffffff||type==0x7ffffffe||n==0)continue;
				int kind;
				switch(type){
					case 0:case 2:kind=ChunkSource.ZERO;break;
					case 1:kind=ChunkSource.RAW;break;
					case 0x80000004:kind=ChunkSource.ADC;break;
					case 0x80000005:kind=ChunkSource.ZLIB;break;
					case 0x80000006:kind=ChunkSource.BZIP2;break;
					case 0x80000007:throw new UnsupportedImageException("UDIF .dmg compressed with LZFSE (ULFO)");
					case 0x80000008:throw new UnsupportedImageException("UDIF .dmg compressed with LZMA (ULMO)");
					default:throw new UnsupportedImageException("UDIF chunk type $"+Integer.toHexString(type));
				}
				list.add(new long[]{start,n,kind,offset,stored});
			}
		}
		if(sectors==0)for(long[] c:list)sectors=Math.max(sectors,c[0]+c[1]);
		return new ChunkSource(file,list,sectors);
	}
	static long u8(byte[] b,int o){return ((long)u4(b,o)<<32)|(u4(b,o+4)&0xffffffffL);}

	/**
	 * Apple Data Compression (ADC), a byte-oriented LZ77: a byte with the top bit set starts a literal run of (b&$7F)+1 bytes;
	 * %01xxxxxx is a copy of (b&$3F)+4 bytes from a 16-bit distance-1 in the next two bytes; %00lllldd a copy of ((b>>2)&$F)+3
	 * bytes from distance ((b&3)<<8|next)+1.
	 */
	static byte[] adc(byte[] in,int size){
		byte[] out=new byte[size];int o=0,i=0;
		while(i<in.length&&o<size){
			int b=in[i++]&0xff;
			if((b&0x80)!=0){int n=(b&0x7f)+1;n=Math.min(n,Math.min(size-o,in.length-i));System.arraycopy(in,i,out,o,n);i+=n;o+=n;}
			else{
				int n,distance;
				if((b&0x40)!=0){if(i+2>in.length)break;n=(b&0x3f)+4;distance=((in[i]&0xff)<<8|(in[i+1]&0xff))+1;i+=2;}
				else{if(i+1>in.length)break;n=((b>>2)&0xf)+3;distance=((b&3)<<8|(in[i]&0xff))+1;i++;}
				if(distance>o)break; // corrupt
				for(int k=0;k<n&&o<size;k++,o++)out[o]=out[o-distance];
			}
		}
		return out;
	}

	/** One file or folder on the volume. Names are the Mac's bytes; dates are Mac dates (local seconds since 1904). */
	static final class Entry {
		byte[] name=new byte[0];
		/** The name in Unicode when the volume keeps it so (HFS Plus); null for names in a Mac encoding. */
		String unicodeName;
		boolean folder,locked;
		/** FInfo / DInfo and FXInfo / DXInfo, 16 bytes each. */
		final byte[] finderInfo=new byte[16],extendedInfo=new byte[16];
		int created,modified;
		long dataLength,resourceLength;
		/** File-system specific: where the forks are (HFS extents, MFS first blocks). */
		Object dataPlace,resourcePlace;
		int id;
		final List<Entry> children=new ArrayList<Entry>();
		int fileCount(){int n=folder?0:1;for(Entry c:children)n+=c.fileCount();return n;}
		long byteCount(){long n=dataLength+resourceLength;for(Entry c:children)n+=c.byteCount();return n;}
	}

	/** A mounted file system: the volume's name, dates and the tree under its root folder. */
	abstract static class Volume {
		byte[] name=new byte[0];
		/** The volume name in Unicode (HFS Plus), else null. */
		String unicodeName;
		int created,modified;
		/** HFS "BD", MFS, ... - for messages. */
		String kind="";
		final Entry root=new Entry();
		/** Copies a fork of {@code entry} (the resource fork when {@code resource}) to {@code out}. */
		abstract void copyFork(Entry entry,boolean resource,OutputStream out) throws IOException;
		byte[] readFork(Entry entry,boolean resource) throws IOException {
			ByteArrayOutputStream out=new ByteArrayOutputStream((int)Math.min(Integer.MAX_VALUE-8,resource?entry.resourceLength:entry.dataLength));
			copyFork(entry,resource,out);return out.toByteArray();
		}
	}

	/** Thrown for an image this reader recognises but cannot open (yet), with a message naming the kind. */
	static final class UnsupportedImageException extends IOException {
		final String kind;
		UnsupportedImageException(String kind){super("Unsupported disk image: "+kind);this.kind=kind;}
	}

	final File file;
	/** The wrapping taken off, for messages: "Disk Copy 4.2", "raw", "Apple partition map", with " in MacBinary" when so. */
	final String container;
	final Source source;
	final Volume volume;

	private DiskImage(File file,String container,Source source,Volume volume){this.file=file;this.container=container;this.source=source;this.volume=volume;}
	@Override public void close() throws IOException {source.close();}

	static final int HFS_SIGNATURE=0x4244,MFS_SIGNATURE=0xD2D7,HFS_PLUS_SIGNATURE=0x482B,HFSX_SIGNATURE=0x4858;

	/** Opens {@code file} as a disk image. IOException when it is not one; {@link UnsupportedImageException} for a kind not read here. */
	static DiskImage open(File file) throws IOException {
		File named=file;file=cueTarget(file);
		RandomAccessFile raf=new RandomAccessFile(file,"r");
		Source whole=new FileSource(raf,0,raf.length());
		try{
			String wrapper="";
			Source image=whole;
			long[] macBinary=macBinaryDataFork(whole);
			if(macBinary!=null){image=new Slice(whole,128,macBinary[0]);wrapper=" in MacBinary";}
			String container="raw";
			Source cd=macBinary==null?RawCdSource.of(image):null;
			if(cd!=null){image=cd;container="CD (2352-byte sectors)";}
			byte[] bcem=ndifTable(file);
			byte[] koly=bcem==null?udifTrailer(image):null;
			if(bcem!=null){image=ndif(image,bcem);container=ndifName(file);}
			else if(koly!=null){image=udif(image,koly,file);container="UDIF";}
			long[] dc42=bcem!=null||koly!=null?null:diskCopy42(image);
			if(dc42!=null){image=new Slice(image,84,dc42[0]);container="Disk Copy 4.2";}
			Object[] found=findVolume(image);
			if(found==null){
				String kind=unsupportedKind(file,image);
				throw kind!=null?new UnsupportedImageException(kind):new IOException("Not a disk image: "+file.getName());
			}
			return new DiskImage(named,container+(found[1]==null?"":", "+found[1])+wrapper,(Source)found[2],(Volume)found[0]);
		}catch(IOException|RuntimeException e){whole.close();throw e;}
	}

	/**
	 * The volume on a whole disk the VM reads as a device (no image container around it): MFS/HFS, or a partition map.
	 * {@code name} only labels it ({@link #file}). IOException when no volume is found.
	 */
	static DiskImage ofDisk(String name,Source disk) throws IOException {
		Object[] found=findVolume(disk);
		if(found==null)throw new IOException("No Macintosh volume on "+name);
		return new DiskImage(new File(name),"drive"+(found[1]==null?"":", "+found[1]),(Source)found[2],(Volume)found[0]);
	}

	/**
	 * Whether {@code file} looks like a disk image this class opens, by its bytes (not its name): cheap enough for every
	 * dropped file. Kinds that are recognised but not read yet answer false; see {@link #unsupportedKind(File)}.
	 */
	static boolean recognizes(File file){
		file=cueTarget(file);
		if(file==null||!file.isFile()||file.length()<1536)return false;
		try(RandomAccessFile raf=new RandomAccessFile(file,"r")){
			Source image=new FileSource(raf,0,raf.length());
			long[] macBinary=macBinaryDataFork(image);
			if(macBinary!=null)image=new Slice(image,128,macBinary[0]);
			else{Source cd=RawCdSource.of(image);if(cd!=null)return findVolume(cd,false)!=null;}
			byte[] bcem=ndifTable(file);
			byte[] koly=bcem==null?udifTrailer(image):null;
			if(bcem!=null)image=ndif(image,bcem);
			else if(koly!=null)image=udif(image,koly,file);
			long[] dc42=bcem!=null||koly!=null?null:diskCopy42(image);
			if(dc42!=null)image=new Slice(image,84,dc42[0]);
			return findVolume(image,false)!=null;
		}catch(IOException|RuntimeException e){return false;}
	}

	/** A disk-image kind recognised but not read here ("Disk Copy 6 (NDIF)", "UDIF (.dmg)", "ISO 9660"), or null. */
	static String unsupportedKind(File file){
		if(file==null||!file.isFile())return null;
		try(RandomAccessFile raf=new RandomAccessFile(file,"r")){
			Source image=new FileSource(raf,0,raf.length());
			long[] macBinary=macBinaryDataFork(image);
			if(macBinary!=null)image=new Slice(image,128,macBinary[0]);
			byte[] bcem=ndifTable(file);
			byte[] koly=bcem==null?udifTrailer(image):null;
			try{
				if(bcem!=null)image=ndif(image,bcem);
				else if(koly!=null)image=udif(image,koly,file);
			}catch(UnsupportedImageException e){return e.kind;}
			long[] dc42=bcem!=null||koly!=null?null:diskCopy42(image);
			if(dc42!=null)image=new Slice(image,84,dc42[0]);
			if(findVolume(image,false)!=null)return null;
			return unsupportedKind(file,image);
		}catch(IOException|RuntimeException e){return null;}
	}

	private static String unsupportedKind(File file,Source image) throws IOException {
		long length=image.length();
		byte[] info=FinderInfo68k.nativeFinderInfo(file);
		byte[] fork=null;
		if(info==null||info[0]==0){try{byte[] stored=FinderInfo68k.stored(file);if(stored!=null)info=stored;}catch(RuntimeException ignored){}}
		String type=info==null?"":new String(info,0,4,"ISO-8859-1"),creator=info==null?"":new String(info,4,4,"ISO-8859-1");
		if(type.equals("rohd")||type.equals("dImg")||creator.equals("dCpy")||creator.equals("ddsk"))return "Disk Copy 6 (NDIF)";
		if(creator.equals("oneb")&&type.equals("APPL"))return "self-mounting image (.smi)";
		String name=file.getName().toLowerCase(java.util.Locale.ROOT);
		if(name.endsWith(".smi")||name.endsWith(".smi.bin"))return "self-mounting image (.smi)";
		if(name.endsWith(".dmg"))return "UDIF (.dmg)";
		if(name.endsWith(".img")||name.endsWith(".image")){
			try{fork=ResourceFile68k.readForkBytes(file,null);}catch(IOException|RuntimeException ignored){}
			if(fork!=null&&fork.length>0&&new String(fork,"ISO-8859-1").contains("bcem"))return "Disk Copy 6 (NDIF)";
		}
		return null;
	}

	/** The 'bcem' resource (128, else the first) of a Disk Copy 6 image or self-mounting image, from any fork storage (MacBinary too), or null. */
	static byte[] ndifTable(File file){
		try{
			if(file.length()==0&&ResourceFile68k.nativeForkLength(file)==0)return null;
			ResourceFile68k fork=ResourceFile68k.open(file);
			int type=ResourceFile68k.fourcc("bcem");
			ResourceFile68k.Resource r=fork.get(type,128);
			if(r==null){List<ResourceFile68k.Resource> all=fork.ofType(type);if(!all.isEmpty())r=all.get(0);}
			return r==null?null:r.data();
		}catch(IOException|RuntimeException e){return null;}
	}
	private static String ndifName(File file){
		byte[] info=FinderInfo68k.read(file);
		boolean app=info!=null&&info[0]=='A'&&info[1]=='P'&&info[2]=='P'&&info[3]=='L';
		return app||file.getName().toLowerCase(java.util.Locale.ROOT).contains(".smi")?"self-mounting image (NDIF)":"Disk Copy 6 (NDIF)";
	}

	/** {data fork length, resource fork length} of a MacBinary file whose data fork is a disk image, or null. */
	private static long[] macBinaryDataFork(Source s) throws IOException {
		if(s.length()<128+1536)return null;
		byte[] h=s.read(0,128);
		if(h[0]!=0||h[74]!=0||h[82]!=0)return null;
		int nameLength=h[1]&0xff;if(nameLength<1||nameLength>63)return null;
		long data=u4(h,83)&0xffffffffL,resource=u4(h,87)&0xffffffffL;
		long expected=128L+((data+127L)&~127L)+resource;
		if(data<1536||expected>s.length()+127L||expected<s.length()-2048L)return null;
		return new long[]{data,resource};
	}

	/** {data size, tag size} of a Disk Copy 4.2 image (84-byte header, private word $0100), or null. */
	static long[] diskCopy42(Source s) throws IOException {
		if(s.length()<84+1024)return null;
		byte[] h=s.read(0,84);
		int nameLength=h[0]&0xff;if(nameLength>63)return null;
		if(u2(h,82)!=0x0100)return null;
		long data=u4(h,64)&0xffffffffL,tags=u4(h,68)&0xffffffffL;
		if(data<1024||(data&511)!=0||84+data>s.length()||84+data+tags>s.length()+4096)return null;
		return new long[]{data,tags};
	}

	private static Object[] findVolume(Source s) throws IOException {return findVolume(s,true);}
	/**
	 * The volume in {@code s}: {Volume (null unless {@code read}), partition description or null, the source it lives in}.
	 * A volume at the start of the image (MDB in block 2), or the first HFS partition of an Apple partition map.
	 */
	private static Object[] findVolume(Source s,boolean read) throws IOException {
		Object[] direct=volumeAt(s,read);
		if(direct!=null)return new Object[]{direct[0],null,s};
		if(s.length()<1024)return null;
		if(s.length()%1024==0){ // a Twiggy disk: the MFS volume starts halfway
			Source rotated=new Rotated(s,s.length()/2);
			if(u2(rotated.read(1024,2),0)==MFS_SIGNATURE){Object[] v=volumeAt(rotated,read);if(v!=null)return new Object[]{v[0],"Twiggy",rotated};}
		}
		byte[] block0=s.read(0,512);
		// "ER": Driver Descriptor Record; some CD masters (Info-Mac III) leave block 0 blank and start with the "PM" map in block 1
		Object[] apm=u2(block0,0)==0x4552||u2(s.read(512,2),0)==0x504D?applePartitionMap(s,block0,read):null;
		if(apm!=null)return apm;
		Object[] gpt=guidPartitionTable(s,read);
		if(gpt!=null)return gpt;
		Object[] mbr=masterBootRecord(s,block0,read);
		if(mbr!=null)return mbr;
		// an ISO 9660 CD last: a hybrid CD's HFS side (above) is what a Mac mounted
		if(IsoVolume.recognizes(s))return new Object[]{read?IsoVolume.read(s):Boolean.TRUE,null,s};
		return null;
	}
	/** The first HFS/HFS Plus partition of an Apple partition map. */
	private static Object[] applePartitionMap(Source s,byte[] block0,boolean read) throws IOException {
		int blockSize=u2(block0,2);
		for(int entrySize:new int[]{512,blockSize}){
			if(entrySize<512||entrySize>4096||entrySize%512!=0)continue;
			byte[] first=s.read(entrySize,512);
			if(u2(first,0)==0x5453){Object[] old=oldPartitionMap(s,first,read);if(old!=null)return old;continue;} // "TS": the map before 1987
			if(u2(first,0)!=0x504D)continue; // "PM"
			int count=Math.min(64,u4(first,4));
			for(int i=1;i<=count;i++){
				byte[] pm=s.read((long)i*entrySize,512);
				if(u2(pm,0)!=0x504D)break;
				String type=cString(pm,48,32);
				if(!type.equalsIgnoreCase("Apple_HFS"))continue;
				long start=(u4(pm,8)&0xffffffffL)*entrySize,count2=(u4(pm,12)&0xffffffffL)*entrySize;
				Source partition=new Slice(s,start,count2);
				Object[] v=volumeAt(partition,read);
				if(v!=null)return new Object[]{v[0],"Apple partition map",partition};
			}
			break;
		}
		return null;
	}
	/**
	 * The old Apple partition map (signature "TS" in block 1, Inside Macintosh: Devices): after the signature, 12-byte
	 * entries {start block, size in blocks, file system ID} up to a zero one. The Manhole CD-ROM keeps its HFS volume
	 * ('TFS1') from block 6 this way.
	 */
	private static Object[] oldPartitionMap(Source s,byte[] map,boolean read) throws IOException {
		for(int e=2;e+12<=512;e+=12){
			long start=u4(map,e)&0xffffffffL,size=u4(map,e+4)&0xffffffffL;
			if(start==0&&size==0)break;
			Source partition=new Slice(s,start*512,size*512);
			Object[] v=volumeAt(partition,read);
			if(v!=null)return new Object[]{v[0],"old Apple partition map",partition};
		}
		return null;
	}
	/** GUID partition type of HFS Plus (48465300-0000-11AA-AA11-00306543ECAC) and APFS (7C3457EF-…), as stored (mixed endian). */
	private static final byte[] GPT_HFS={0x00,0x53,0x46,0x48,0,0,(byte)0xAA,0x11,(byte)0xAA,0x11,0x00,0x30,0x65,0x43,(byte)0xEC,(byte)0xAC},
		GPT_APFS={(byte)0xEF,0x57,0x34,0x7C,0,0,(byte)0xAA,0x11,(byte)0xAA,0x11,0x00,0x30,0x65,0x43,(byte)0xEC,(byte)0xAC};
	/** The first readable partition of a GUID partition table (what Mac OS X 10.4 and later put in .dmg files); APFS is reported. */
	private static Object[] guidPartitionTable(Source s,boolean read) throws IOException {
		if(s.length()<34*512)return null;
		byte[] header=s.read(512,92);
		if(!new String(header,0,8,java.nio.charset.StandardCharsets.ISO_8859_1).equals("EFI PART"))return null;
		long entries=le8(header,72);int count=Math.min(256,le4(header,80)),size=le4(header,84);
		if(size<128||size>4096)return null;
		boolean apfs=false;
		for(int i=0;i<count;i++){
			byte[] e=s.read(entries*512+(long)i*size,128);
			byte[] type=java.util.Arrays.copyOf(e,16);
			boolean empty=true;for(byte b:type)if(b!=0){empty=false;break;}
			if(empty)continue;
			if(java.util.Arrays.equals(type,GPT_APFS)){apfs=true;continue;}
			long first=le8(e,32),last=le8(e,40);
			if(last<first)continue;
			Source partition=new Slice(s,first*512,(last-first+1)*512);
			Object[] v=volumeAt(partition,read);
			if(v!=null)return new Object[]{v[0],"GUID partition table",partition};
		}
		if(apfs)throw new UnsupportedImageException("APFS");
		return null;
	}
	/** The first readable partition of a PC-style master boot record (type $AF: HFS / HFS Plus, but any is tried). */
	private static Object[] masterBootRecord(Source s,byte[] block0,boolean read) throws IOException {
		if((block0[510]&0xff)!=0x55||(block0[511]&0xff)!=0xAA)return null;
		for(int i=0;i<4;i++){
			int at=446+16*i,type=block0[at+4]&0xff;
			long start=le4(block0,at+8)&0xffffffffL,count=le4(block0,at+12)&0xffffffffL;
			if(type==0||type==0xEE||count==0)continue;
			Source partition=new Slice(s,start*512,count*512);
			Object[] v=volumeAt(partition,read);
			if(v!=null)return new Object[]{v[0],"MBR partition",partition};
		}
		return null;
	}
	static int le4(byte[] b,int o){return (b[o]&0xff)|(b[o+1]&0xff)<<8|(b[o+2]&0xff)<<16|(b[o+3]&0xff)<<24;}
	static long le8(byte[] b,int o){return (le4(b,o)&0xffffffffL)|((long)le4(b,o+4)<<32);}
	private static Object[] volumeAt(Source s,boolean read) throws IOException {
		if(s.length()<1536)return null;
		byte[] mdb=s.read(1024,162);
		int sig=u2(mdb,0);
		if(sig==HFS_PLUS_SIGNATURE||sig==HFSX_SIGNATURE){
			int blockSize=u4(s.read(1024+40,4),0);if(blockSize<512||(blockSize&(blockSize-1))!=0)return null;
			return new Object[]{read?HfsVolume.readHfsPlus(s):Boolean.TRUE};
		}
		if(sig==HFS_SIGNATURE){
			if(u2(mdb,124)==HFS_PLUS_SIGNATURE){ // an HFS wrapper (what Mac OS 8.1 and 9 see as "HFS") around the HFS Plus volume
				int blockSize=u4(mdb,20);if(blockSize<512||(blockSize&511)!=0)return null;
				long start=u2(mdb,28)*512L+(long)u2(mdb,126)*blockSize,length=(long)u2(mdb,128)*blockSize;
				Source embedded=new Slice(s,start,length);
				if(embedded.length()<1536||u2(embedded.read(1024,2),0)!=HFS_PLUS_SIGNATURE)return null;
				Object[] v=volumeAt(embedded,read);
				return v==null?null:new Object[]{v[0],embedded};
			}
			int blockSize=u4(mdb,20);if(blockSize<512||(blockSize&511)!=0)return null;
			return new Object[]{read?HfsVolume.readHfs(s):Boolean.TRUE};
		}
		if(sig==MFS_SIGNATURE){
			int blockSize=u4(mdb,20);if(blockSize<512||(blockSize&511)!=0)return null;
			return new Object[]{read?HfsVolume.readMfs(s):Boolean.TRUE};
		}
		return null;
	}

	static int u2(byte[] b,int o){return (b[o]&0xff)<<8|(b[o+1]&0xff);}
	static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|(b[o+3]&0xff);}
	private static String cString(byte[] b,int o,int max){int n=0;while(n<max&&b[o+n]!=0)n++;return new String(b,o,n,java.nio.charset.StandardCharsets.ISO_8859_1);}
}

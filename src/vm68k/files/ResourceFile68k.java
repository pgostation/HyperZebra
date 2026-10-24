import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory classic Macintosh resource fork with read/write support.
 *
 * Applications open their own file, preference files and other documents with
 * the Resource Manager; the fork is parsed into {@link Resource} entries, edits
 * are made in memory, and {@link #write()} stores the fork back to the host
 * file (native resource fork on macOS, AppleDouble sidecar elsewhere, or the
 * resource part of a MacBinary container).
 */
final class ResourceFile68k {
	static final int RES_SYS_HEAP=64,RES_PURGEABLE=32,RES_LOCKED=16,RES_PROTECTED=8,RES_PRELOAD=4,RES_CHANGED=2,RES_COMPRESSED=1;

	static final class Resource {
		final int type;int id;String name;int attributes;private byte[] data;
		/** Lazily read source (HyperZebra stack resource files): the file and the number of leading bytes to skip. */
		File source;int sourcePrefix;
		/** VM handle currently holding the data, or 0. */
		int handle;boolean changed;
		/** Already fed through the program's $A0FC resource-reading patch (ResourceManager68k.data). */
		boolean expanded;
		/** Offset of this resource's reference-list entry inside the in-memory map image ({@link #buildMap}), or -1. */
		int mapEntry=-1;
		/** Offset of the data in the fork's data area as the file's map records it (-1: not read from a file), and the length stored there. */
		int fileOffset=-1,fileLength;
		/** The file stores the data compressed: the map keeps resCompressed although {@link #data} hands out the expanded bytes. */
		boolean storedCompressed;
		private static final int ICON_TYPE=0x49434f4e;
		/** An 'ICON' (32x32, one bit) from an icon kept as a picture: HyperCard's standard icons are pictures in a HyperZebra Home
		 *  (UxAnswer's icon 1000 and its 21573/21576 animation were blank). Dark opaque pixels are the black ones. */
		static byte[] iconBits(File png){
			byte[] bits=new byte[128];
			try{java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(png);if(image==null)return bits;
				for(int y=0;y<32&&y<image.getHeight();y++)for(int x=0;x<32&&x<image.getWidth();x++){int p=image.getRGB(x,y);
					if((p>>>24)>=128&&((p>>16&255)*30+(p>>8&255)*59+(p&255)*11)/100<128)bits[y*4+x/8]|=(byte)(0x80>>>(x&7));}}
			catch(IOException|RuntimeException e){}
			return bits;
		}
		private static final int SND_TYPE=0x736e6420;
		/** Whether {@code file} is a sound kept as audio (AIFF/WAV) rather than 'snd ' bytes: HyperZebra's Home keeps HyperCard's
		 *  Harpsichord, Boing and flute as AIFF. */
		static boolean isAudioFile(File file){
			String lower=file.getName().toLowerCase(java.util.Locale.ROOT);
			return lower.endsWith(".aiff")||lower.endsWith(".aif")||lower.endsWith(".aifc")||lower.endsWith(".wav");
		}
		/** A format 1 'snd ' of an audio file, with the base note and loop of its AIFF INST chunk (else HyperCard's own for its
		 *  sounds by name, else middle C): PgSoundX's Harpsichord and Boing were "Not found snd resource". */
		static byte[] sndBits(File audio,String name){
			try{TSound.NoteInfo note=TSound.noteInfo(audio.getPath(),name);
				return HCResourceExport.sound(SoundData.read(audio),note.baseNote,note.loopStart,note.loopEnd);}
			catch(Exception e){System.err.println("[68K] cannot convert sound "+audio.getName()+": "+e.getMessage());return new byte[0];}
		}
		/** The source file is a picture or audio file whose resource bytes are made from it ({@link #iconBits}, {@link #sndBits}). */
		private boolean convertedSource(){
			if(source==null)return false;
			return type==ICON_TYPE&&source.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")||type==SND_TYPE&&isAudioFile(source);
		}
		Resource(int type,int id,String name,int attributes,byte[] data){this.type=type;this.id=id;this.name=name;this.attributes=attributes;this.data=data;}
		Resource(int type,int id,String name,File source,int sourcePrefix){this.type=type;this.id=id;this.name=name;this.source=source;this.sourcePrefix=sourcePrefix;}
		/** The resource bytes, read from the source file on first use; System 7 compressed resources are expanded. */
		byte[] data(){
			if(data==null&&convertedSource())data=type==ICON_TYPE?iconBits(source):sndBits(source,name);
			if(data==null&&source!=null){
				try{byte[] bytes=Files.readAllBytes(source.toPath());data=sourcePrefix>0&&bytes.length>=sourcePrefix?java.util.Arrays.copyOfRange(bytes,sourcePrefix,bytes.length):bytes;}
				catch(IOException e){data=new byte[0];}
			}
			if((attributes&RES_COMPRESSED)!=0){
				// a program never sees the compressed form: the Resource Manager runs the 'dcmp' when it loads the resource
				// (the attribute alone is not proof; some files set it on plain data)
				if(data!=null&&MacResourceDecompressor.isCompressed(data)){
					// (a program's own 'dcmp' runs in ResourceManager68k.data when the resource is loaded: no warning here)
					if(MacResourceDecompressor.hasBuiltInDecompressor(data))
					try{data=MacResourceDecompressor.decompress(data);attributes&=~RES_COMPRESSED;}
					catch(IOException e){System.err.println("[68K] cannot expand compressed resource "+typeName(type)+" "+id+": "+e.getMessage());}
				}else attributes&=~RES_COMPRESSED;
			}
			return data==null?new byte[0]:data;
		}
		void setData(byte[] bytes){data=bytes;source=null;}
		/** The program's own bytes while a translation (ResourceTranslation) is laid over them, else null. */
		byte[] original;
		/** The translation laid over the resource, as it was laid. */
		private byte[] translation;
		/** Lays a translation over the resource in memory: the program sees it, its file keeps the original. */
		void translate(byte[] bytes){original=data();translation=bytes;setData(bytes);}
		/** The resource as its file has it (under a translation, the original). */
		byte[] originalData(){return original!=null?original:data();}
		/** Whether the program changed a translated resource itself (its own change goes to its file). */
		boolean translationChanged(){byte[] d=data();return original!=null&&d!=translation&&!java.util.Arrays.equals(d,translation);}
		/** The bytes written to the file: the original while the translation is as it was laid, never the translation. */
		byte[] fileData(){return original!=null&&!translationChanged()?original:data();}
		/** The attributes as the map shows them (GetResAttrs): resCompressed stays set while the file holds the compressed form,
		 *  resChanged while a change waits for UpdateResFile (AddResource/ChangedResource set it; THINK C 5 ORs its own bits into
		 *  GetResAttrs of a resource it just added, and its project writer allocates space for the entries marked changed). */
		int mapAttributes(){return attributes&~RES_CHANGED|(changed?RES_CHANGED:0)|(storedCompressed?RES_COMPRESSED:0);}
		int length(){if((attributes&RES_COMPRESSED)!=0)return data().length;return storedLength();}
		/** The size of the bytes as stored, without expanding a compressed resource (map images only need consistent offsets). */
		int storedLength(){if(data==null&&source!=null)return convertedSource()?data().length:(int)Math.max(0,source.length()-sourcePrefix);return data==null?0:data.length;}
		String key(){return key(type,id);}
		static String key(int type,int id){return type+":"+id;}
	}

	final File file;
	/** Where the fork bytes live: NATIVE (..namedfork on macOS, the AFP_Resource stream on Windows), APPLEDOUBLE (._ sidecar), MACBINARY, NONE (new),
	 *  or DATA (a resource map in the file's data fork: Carbon's FSpOpenDataForkAsResourceFile, Mac OS X style .rsrc files). */
	enum Storage {NATIVE,APPLEDOUBLE,MACBINARY,NONE,DATA}
	Storage storage=Storage.NONE;
	final Map<String,Resource> byKey=new LinkedHashMap<String,Resource>();
	final List<Resource> ordered=new ArrayList<Resource>();
	int attributes;
	boolean dirty;
	int refNum;
	boolean writable=true;
	/** XCMD mode: the HyperZebra stack this file shows its externals (Mac68kResourceManager.forStack), whose resources take what
	 *  they write here instead of {@link #file}; null for a real resource file. */
	OStack stack;
	/** For a stack's file: each resource as the stack had it when the file was made or last stored (type, ID, name), so that
	 *  {@link Mac68kResourceManager#storeChanges} finds what was added, changed, renumbered or removed since. */
	java.util.IdentityHashMap<Resource,Object[]> stackOrigin;
	/** Guest Handle of the in-memory resource map image (see {@link #buildMap}), 0 until the Resource Manager materialises it. */
	int mapHandle;

	ResourceFile68k(File file){this.file=file;}

	static int fourcc(String value){return (value.charAt(0)&255)<<24|(value.charAt(1)&255)<<16|(value.charAt(2)&255)<<8|(value.charAt(3)&255);}
	static String typeName(int type){return ""+(char)((type>>>24)&255)+(char)((type>>>16)&255)+(char)((type>>>8)&255)+(char)(type&255);}

	/** Reads the resource fork of a host file, MacBinary container, or AppleDouble sidecar. */
	static ResourceFile68k open(File file) throws IOException {
		ResourceFile68k result=new ResourceFile68k(file);
		byte[] fork=readForkBytes(file,result);
		if(fork!=null&&fork.length>=16)result.parse(fork);
		return result;
	}

	/** A resource file kept in the data fork of {@code file} (empty when the data fork is): written back there too. */
	static ResourceFile68k openDataFork(File file) throws IOException {
		ResourceFile68k result=new ResourceFile68k(file);result.storage=Storage.DATA;
		byte[] data=file.isFile()?Files.readAllBytes(file.toPath()):new byte[0];
		if(data.length>=16)result.parse(data);else if(data.length>0)throw new IOException("not a resource map");
		return result;
	}

	/** Returns the raw resource fork of a file (or null), also detecting MacBinary containers. */
	static byte[] readForkBytes(File file,ResourceFile68k target) throws IOException {
		if(file==null||!file.isFile())return null;
		File nativeFork=nativeForkFile(file);
		if(nativeFork!=null)try(FileInputStream input=new FileInputStream(nativeFork)){
			byte[] data=input.readAllBytes();
			if(data.length>0){if(target!=null)target.storage=Storage.NATIVE;return data;}
		}catch(IOException e){/* no fork (or a volume without streams) */}
		MacBinary mb=MacBinary.read(file);
		if(mb!=null){if(target!=null)target.storage=Storage.MACBINARY;return mb.resourceFork;}
		for(File candidate:AppleDouble.sidecarCandidates(file)){
			if(!candidate.isFile())continue;
			byte[] fork=HCResource.extractResourceForkFromAppleDouble(Files.readAllBytes(candidate.toPath()));
			if(fork!=null){if(target!=null)target.storage=Storage.APPLEDOUBLE;return fork;}
		}
		return PcExchange.resourceFork(file); // a DOS disk's RESOURCE.FRK (read only: a changed fork goes where new ones go)
	}

	void parse(byte[] b) throws IOException {
		try{
			int dataOffset=u4(b,0),mapOffset=u4(b,4);
			attributes=u2(b,mapOffset+22);
			int typeListOffset=u2(b,mapOffset+24)+mapOffset,nameListOffset=u2(b,mapOffset+26)+mapOffset;
			int typeCount=(short)u2(b,typeListOffset)+1;
			for(int i=0;i<typeCount;i++){
				int entry=typeListOffset+2+8*i;
				int type=u4(b,entry),count=u2(b,entry+4)+1,refList=u2(b,entry+6)+typeListOffset;
				for(int j=0;j<count;j++){
					int ref=refList+12*j;
					int id=(short)u2(b,ref),nameOffset=(short)u2(b,ref+2),attrs=b[ref+4]&0xff;
					int off=((b[ref+5]&0xff)<<16|(b[ref+6]&0xff)<<8|(b[ref+7]&0xff))+dataOffset;
					int length=u4(b,off);
					byte[] data=new byte[Math.max(0,Math.min(length,b.length-off-4))];
					System.arraycopy(b,off+4,data,0,data.length);
					String name=null;
					if(nameOffset>=0){int at=nameListOffset+nameOffset;int n=b[at]&0xff;name=StackTextEncoding.ROMAN.decode(java.util.Arrays.copyOfRange(b,at+1,at+1+n));}
					Resource r=new Resource(type,id,name,attrs&~RES_CHANGED,data);r.fileOffset=off-dataOffset;r.fileLength=data.length;
					r.storedCompressed=(attrs&RES_COMPRESSED)!=0&&MacResourceDecompressor.isCompressed(data);add(r);
				}
			}
			forkHeader=java.util.Arrays.copyOf(b,16);
			dirty=false;
		}catch(IndexOutOfBoundsException e){throw new IOException("corrupt resource fork: "+e.getMessage());}
	}

	/** The first 16 bytes of the fork this file was parsed from (data/map offsets and lengths), or null. */
	private byte[] forkHeader;

	void add(Resource resource){
		Resource old=byKey.put(resource.key(),resource);
		if(old!=null)ordered.remove(old);
		ordered.add(resource);
	}

	Resource get(int type,int id){return byKey.get(Resource.key(type,id));}
	/** The first resource of {@code type} named {@code name} (any case). An empty name finds none: an unnamed resource has no
	 *  name to match (a stack's unnamed resources come here as "": UxAnswer's GetNamedResource('ICON', "") took ICON 8862 for
	 *  the second icon of an animation that should not run, and the dialog's own icon was overwritten at once). */
	Resource getNamed(int type,String name){
		if(name==null||name.isEmpty())return null;
		for(Resource r:ordered)if(r.type==type&&r.name!=null&&r.name.equalsIgnoreCase(name))return r;
		return null;
	}
	List<Resource> ofType(int type){List<Resource> result=new ArrayList<Resource>();for(Resource r:ordered)if(r.type==type)result.add(r);return result;}
	/** Every resource in file order. */
	List<Resource> all(){return new ArrayList<Resource>(ordered);}
	List<Integer> types(){List<Integer> result=new ArrayList<Integer>();for(Resource r:ordered)if(!result.contains(r.type))result.add(r.type);return result;}
	void remove(Resource resource){byKey.remove(resource.key());ordered.remove(resource);dirty=true;}
	Resource byHandle(int handle){if(handle==0)return null;for(Resource r:ordered)if(r.handle==handle)return r;return null;}
	int uniqueId(int type){
		int id=128;
		for(;;){if(get(type,id)==null)return id;id++;}
	}

	/**
	 * The map as the Resource Manager keeps it in memory (IM I-128): the on-disk map with mNext, mRefNum and the
	 * handle field of every reference-list entry filled in. Programs that walk TopMapHndl themselves (some resource editors copy
	 * and inspects a file's map straight after OpenResFile) read this image. Data offsets follow the layout
	 * {@link #serialize} would write; the data is not touched. Records each resource's entry offset in {@link Resource#mapEntry}.
	 */
	/** The resource's bytes are the ones at its file offset: read from the file and not grown by a change since. */
	private static boolean inFileLayout(Resource r){return r.fileOffset>=0&&(!r.changed||r.storedLength()<=r.fileLength);}

	byte[] buildMap(int nextMap){
		Map<Integer,List<Resource>> byType=new LinkedHashMap<Integer,List<Resource>>();
		for(Resource r:ordered){List<Resource> list=byType.get(r.type);if(list==null){list=new ArrayList<Resource>();byType.put(r.type,list);}list.add(r);}
		ByteArrayOutputStream names=new ByteArrayOutputStream();
		ByteArrayOutputStream refLists=new ByteArrayOutputStream();
		ByteArrayOutputStream typeList=new ByteArrayOutputStream();
		int typeListSize=2+8*byType.size();
		writeShort(typeList,byType.size()-1);
		int refOffset=typeListSize,dataSize=0;
		// The file's own data offsets for every resource still where the file has it: the Finder's segment loader reads a CODE
		// resource's length straight from the fork at header data offset + entry offset (RsrcMapEntry), and THINK C 5 rebuilds
		// its project's free-space list from the map's offsets and the lengths it reads from the fork. Resources the file does
		// not hold yet (added, or changed and grown) go after the file's data, as AddResource/ChangedResource place them; the
		// header's data length and map offset follow. Without a parsed header the map describes serialize's layout.
		boolean fileLayout=forkHeader!=null;
		Map<Resource,Integer> appended=new java.util.HashMap<Resource,Integer>();int dataEnd=fileLayout?u4(forkHeader,8):0;
		if(fileLayout)for(Resource r:ordered)if(!inFileLayout(r)){appended.put(r,dataEnd);
			int length=r.storedLength();dataEnd+=4+length+(length&1);}
		for(Map.Entry<Integer,List<Resource>> entry:byType.entrySet()){
			writeInt(typeList,entry.getKey());writeShort(typeList,entry.getValue().size()-1);writeShort(typeList,refOffset);
			for(Resource r:entry.getValue()){
				r.mapEntry=28+refOffset;
				int offset=!fileLayout?dataSize:appended.containsKey(r)?appended.get(r):r.fileOffset;
				writeShort(refLists,r.id);
				if(r.name==null||r.name.isEmpty())writeShort(refLists,-1); // "" is no name: offset -1, as the Resource Manager writes it (MPW DeRez reads the map itself and printed a "" name, then garbage)
				else{writeShort(refLists,names.size());byte[] n=StackTextEncoding.ROMAN.encodeReplacing(r.name);int len=Math.min(255,n.length);names.write(len);names.write(n,0,len);}
				int length=r.storedLength();
				refLists.write(r.mapAttributes()&0xff);refLists.write(offset>>>16);refLists.write(offset>>>8);refLists.write(offset);
				writeInt(refLists,r.handle);
				dataSize+=4+length+(length&1);refOffset+=12;
			}
		}
		ByteArrayOutputStream map=new ByteArrayOutputStream();
		int mapLength=28+typeListSize+refLists.size()+names.size();
		byte[] header=new byte[16];putInt(header,0,256);putInt(header,4,256+dataSize);putInt(header,8,dataSize);putInt(header,12,mapLength);
		if(fileLayout){header=forkHeader.clone();if(!appended.isEmpty()){int dataOffset=u4(header,0);putInt(header,4,dataOffset+dataEnd);putInt(header,8,dataEnd);putInt(header,12,mapLength);}}
		map.write(header,0,16);writeInt(map,nextMap);writeShort(map,refNum);writeShort(map,attributes|(writable?0:0x80));
		writeShort(map,28);writeShort(map,28+typeListSize+refLists.size());
		map.write(typeList.toByteArray(),0,typeList.size());map.write(refLists.toByteArray(),0,refLists.size());map.write(names.toByteArray(),0,names.size());
		return map.toByteArray();
	}

	/** Where {@link #serialize} put each resource's data (offset in the data area, length), for {@link #write}. */
	private final Map<Resource,int[]> lastLayout=new java.util.IdentityHashMap<Resource,int[]>();
	byte[] serialize(){
		ByteArrayOutputStream data=new ByteArrayOutputStream();lastLayout.clear();
		Map<Integer,List<Resource>> byType=new LinkedHashMap<Integer,List<Resource>>();
		for(Resource r:ordered){List<Resource> list=byType.get(r.type);if(list==null){list=new ArrayList<Resource>();byType.put(r.type,list);}list.add(r);}
		int[] dataOffsets=new int[ordered.size()];
		int index=0;
		for(List<Resource> list:byType.values())for(Resource r:list){
			dataOffsets[index++]=data.size();
			byte[] bytes=r.fileData();lastLayout.put(r,new int[]{data.size(),bytes.length});
			writeInt(data,bytes.length);data.write(bytes,0,bytes.length);
			if((bytes.length&1)!=0)data.write(0);
		}
		ByteArrayOutputStream names=new ByteArrayOutputStream();
		ByteArrayOutputStream refLists=new ByteArrayOutputStream();
		ByteArrayOutputStream typeList=new ByteArrayOutputStream();
		int typeListSize=2+8*byType.size();
		writeShort(typeList,byType.size()-1);
		index=0;int refOffset=typeListSize;
		for(Map.Entry<Integer,List<Resource>> entry:byType.entrySet()){
			writeInt(typeList,entry.getKey());writeShort(typeList,entry.getValue().size()-1);writeShort(typeList,refOffset);
			for(Resource r:entry.getValue()){
				writeShort(refLists,r.id);
				if(r.name==null||r.name.isEmpty())writeShort(refLists,-1); // "" is no name: offset -1, as the Resource Manager writes it (MPW DeRez reads the map itself and printed a "" name, then garbage)
				else{writeShort(refLists,names.size());byte[] n=StackTextEncoding.ROMAN.encodeReplacing(r.name);int len=Math.min(255,n.length);names.write(len);names.write(n,0,len);}
				int off=dataOffsets[index++];
				refLists.write(r.attributes&~RES_CHANGED);refLists.write(off>>>16);refLists.write(off>>>8);refLists.write(off);
				writeInt(refLists,0);
				refOffset+=12;
			}
		}
		ByteArrayOutputStream map=new ByteArrayOutputStream();
		int mapLength=28+typeListSize+refLists.size()+names.size();
		int dataOffset=256,mapOffset=256+data.size();
		byte[] header=new byte[16];putInt(header,0,dataOffset);putInt(header,4,mapOffset);putInt(header,8,data.size());putInt(header,12,mapLength);
		map.write(header,0,16);writeInt(map,0);writeShort(map,0);writeShort(map,attributes);
		writeShort(map,28);writeShort(map,28+typeListSize+refLists.size());
		map.write(typeList.toByteArray(),0,typeList.size());map.write(refLists.toByteArray(),0,refLists.size());map.write(names.toByteArray(),0,names.size());
		ByteArrayOutputStream fork=new ByteArrayOutputStream();
		fork.write(header,0,16);fork.write(new byte[240],0,240);fork.write(data.toByteArray(),0,data.size());fork.write(map.toByteArray(),0,map.size());
		return fork.toByteArray();
	}

	/** Writes the fork back to the host file using the storage it came from. */
	void write() throws IOException {
		if(file==null)return;
		for(Resource r:ordered)if(r.translationChanged()){ResourceTranslation.backUp(this);break;} // the program changed a translated resource: its file gets that
		byte[] fork=serialize();
		writeForkBytes(file,fork,storage);
		// the map keeps describing the fork as written from now on (serialize's layout), so later additions, removals and
		// growth only move what changed - THINK C reads unloaded resources' lengths from the fork at the map's offsets
		forkHeader=java.util.Arrays.copyOf(fork,16);
		for(Resource r:ordered){int[] at=lastLayout.get(r);if(at!=null){r.fileOffset=at[0];r.fileLength=at[1];}else r.fileOffset=-1;r.storedCompressed=false;}
		if(storage==Storage.NONE)storage=detectStorage();
		for(Resource r:ordered)r.changed=false;
		dirty=false;
	}

	private Storage detectStorage(){
		if(nativeForkPreferred(file))return Storage.NATIVE;
		return Storage.APPLEDOUBLE;
	}

	static final boolean WINDOWS=System.getProperty("os.name","").toLowerCase().startsWith("windows");

	/**
	 * The host's own resource fork of a file, or null where the host keeps none: "name/..namedfork/rsrc" on macOS, and on
	 * Windows the NTFS stream "name:AFP_Resource" - where a Mac copying to a Windows SMB share (and Services for Macintosh
	 * before it) leaves the fork, beside the Finder info in "name:AFP_AfpInfo" ({@link FinderInfo68k}).
	 */
	static File nativeForkFile(File file){
		if(file==null)return null;
		if(GMenu.isMacOSX())return new File(file.getPath()+File.separator+"..namedfork"+File.separator+"rsrc");
		if(WINDOWS)return ntfsStream(file,"AFP_Resource");
		return null;
	}
	/** Length of the host's own resource fork of a file (0 without one). */
	static long nativeForkLength(File file){File fork=nativeForkFile(file);return fork==null?0:fork.length();}
	/** Windows: a named NTFS stream of a file. java.io reaches "path:stream" (NIO paths refuse the colon); on a volume without streams it just is not found. */
	static File ntfsStream(File file,String stream){return new File(file.getAbsolutePath()+":"+stream);}
	/** Windows: whether a file keeps Mac metadata in NTFS streams (a copy from a Mac over SMB). */
	static boolean hasMacStreams(File file){return WINDOWS&&file!=null&&file.isFile()&&(ntfsStream(file,"AFP_AfpInfo").exists()||ntfsStream(file,"AFP_Resource").exists());}
	/**
	 * Whether a fork with no home yet goes to the host's own fork rather than a sidecar: on macOS (see {@link #nativeForks}),
	 * and on Windows for a file that already keeps its Mac metadata in NTFS streams, so its fork stays beside its Finder info.
	 */
	static boolean nativeForkPreferred(File file){return nativeForks()||hasMacStreams(file);}

	/**
	 * Whether new resource forks go into the host's own fork (macOS only). Everywhere else - Windows, Linux -
	 * and on macOS with -Dhz.appledouble=true (for files headed to those hosts), the fork and Finder info
	 * live in an AppleDouble "._name" sidecar next to the file, which every reader here also understands.
	 */
	static boolean nativeForks(){return GMenu.isMacOSX()&&!Boolean.getBoolean("hz.appledouble");}

	static void writeForkBytes(File file,byte[] fork,Storage storage) throws IOException {
		if(storage==Storage.DATA){Files.write(file.toPath(),fork);return;} // the map lives in the data fork
		if(!file.exists())Files.write(file.toPath(),new byte[0]);
		if(storage==Storage.MACBINARY){MacBinary mb=MacBinary.read(file);if(mb!=null){mb.resourceFork=fork;mb.write(file);return;}}
		File nativeFork=storage==Storage.NATIVE||(storage==Storage.NONE&&nativeForkPreferred(file))?nativeForkFile(file):null;
		if(nativeFork!=null){
			try(FileOutputStream out=new FileOutputStream(nativeFork)){out.write(fork);return;}
			catch(IOException e){if(storage==Storage.NATIVE)throw e;}
		}
		File sidecar=AppleDouble.sidecarOf(file),existing=AppleDouble.findSidecar(file); // Finder info of a __MACOSX sidecar carries over
		Files.write(sidecar.toPath(),AppleDouble.build(fork,existing!=null?AppleDouble.finderInfo(Files.readAllBytes(existing.toPath())):null));
	}

	private static int u2(byte[] b,int o){return (b[o]&0xff)<<8|(b[o+1]&0xff);}
	private static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|(b[o+3]&0xff);}
	private static void putInt(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}
	private static void writeShort(ByteArrayOutputStream out,int v){out.write(v>>>8);out.write(v);}
	private static void writeInt(ByteArrayOutputStream out,int v){out.write(v>>>24);out.write(v>>>16);out.write(v>>>8);out.write(v);}

	/** Minimal MacBinary I/II container reader/writer. */
	static final class MacBinary {
		byte[] header=new byte[128];byte[] dataFork=new byte[0];byte[] resourceFork=new byte[0];
		/** File name as Mac Roman text, and its raw bytes (decode them with the program's own encoding for Japanese names). */
		String name;byte[] nameBytes=new byte[0];int type,creator;
		static MacBinary read(File file) throws IOException {
			// the 128-byte header decides first: catalog scans ask about every file in a folder, and reading a multi-gigabyte
			// one whole to find out it is not MacBinary ran the VM out of memory
			if(!headerLooksLikeMacBinary(file))return null;
			byte[] all=Files.readAllBytes(file.toPath());
			if(!looksLikeMacBinary(all))return null;
			MacBinary mb=new MacBinary();System.arraycopy(all,0,mb.header,0,128);
			int nameLength=Math.min(63,all[1]&0xff);mb.nameBytes=java.util.Arrays.copyOfRange(all,2,2+nameLength);mb.name=StackTextEncoding.ROMAN.decode(mb.nameBytes);
			mb.type=u4(all,65);mb.creator=u4(all,69);
			int dataLength=u4(all,83),resourceLength=u4(all,87);
			int dataStart=128,resourceStart=dataStart+((dataLength+127)&~127);
			if(dataLength<0||resourceLength<0||dataStart+dataLength>all.length)return null;
			mb.dataFork=java.util.Arrays.copyOfRange(all,dataStart,dataStart+dataLength);
			mb.resourceFork=resourceStart+resourceLength<=all.length?java.util.Arrays.copyOfRange(all,resourceStart,resourceStart+resourceLength):new byte[0];
			return mb;
		}
		/** Cheap pre-check reading only the 128-byte header (so directory scans do not load every file). */
		/** {dataForkLength, resourceForkLength} from the 128-byte header, or null when the file is not a MacBinary container (nothing else is read). */
		static long[] headerForkLengths(File file){
			if(!headerLooksLikeMacBinary(file))return null;
			byte[] header=new byte[128];
			try(FileInputStream input=new FileInputStream(file)){if(input.readNBytes(header,0,128)!=128)return null;}catch(IOException e){return null;}
			return new long[]{u4(header,83),u4(header,87)};
		}
		static boolean headerLooksLikeMacBinary(File file){
			if(file==null||!file.isFile()||file.length()<128)return false;
			byte[] header=new byte[128];
			try(FileInputStream input=new FileInputStream(file)){if(input.readNBytes(header,0,128)!=128)return false;}catch(IOException e){return false;}
			if(header[0]!=0||header[74]!=0||header[82]!=0)return false;
			int nameLength=header[1]&0xff;if(nameLength<1||nameLength>63||!plausibleName(header,nameLength))return false;
			int dataLength=u4(header,83),resourceLength=u4(header,87);
			if(dataLength<0||resourceLength<0||dataLength>0x7fffff||resourceLength>0x7fffff)return false;
			long expected=128L+((dataLength+127L)&~127L)+resourceLength,length=file.length();
			return expected<=length+127L&&expected>=length-2048L;
		}
		/** The raw file name bytes stored in the header (null unless the file passes the header check). */
		static byte[] headerNameBytes(File file){
			if(!headerLooksLikeMacBinary(file))return null;
			byte[] header=new byte[128];
			try(FileInputStream input=new FileInputStream(file)){if(input.readNBytes(header,0,128)!=128)return null;}catch(IOException e){return null;}
			int nameLength=Math.min(63,header[1]&0xff);return java.util.Arrays.copyOfRange(header,2,2+nameLength);
		}
		/** A file name holds no control characters: a document whose first bytes happen to read 0, n, ..., 0, 0 (MacWrite 2.2's 128-byte header, all zero lengths) is not a container. */
		private static boolean plausibleName(byte[] header,int nameLength){for(int i=0;i<nameLength;i++){int c=header[2+i]&0xff;if(c<0x20||c==0x7f)return false;}return true;}
		static boolean looksLikeMacBinary(byte[] all){
			if(all.length<128||all[0]!=0||all[74]!=0||all[82]!=0)return false;
			int nameLength=all[1]&0xff;if(nameLength<1||nameLength>63||!plausibleName(all,nameLength))return false;
			int dataLength=u4(all,83),resourceLength=u4(all,87);
			if(dataLength<0||resourceLength<0||dataLength>0x7fffff||resourceLength>0x7fffff)return false;
			long expected=128L+((dataLength+127L)&~127L)+resourceLength;
			return expected<=all.length+127L&&expected>=all.length-2048L;
		}
		void write(File file) throws IOException {
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			putInt(header,83,dataFork.length);putInt(header,87,resourceFork.length);
			out.write(header,0,128);out.write(dataFork,0,dataFork.length);pad(out);out.write(resourceFork,0,resourceFork.length);pad(out);
			Files.write(file.toPath(),out.toByteArray());
		}
		private static void pad(ByteArrayOutputStream out){while((out.size()&127)!=0)out.write(0);}
	}

	/** AppleDouble sidecar builder (Finder info entry 9 + resource fork entry 2). */
	static final class AppleDouble {
		/** The "._name" companion that carries a file's resource fork and Finder info where the host has no forks (where new sidecars are written). */
		static File sidecarOf(File file){File absolute=file.getAbsoluteFile();return new File(absolute.getParentFile(),"._"+absolute.getName());}
		/**
		 * Every place a file's sidecar may sit, nearest first: "._name" beside it, then the "__MACOSX" mirror that an
		 * archive zipped by the Finder leaves when expanded on Windows or Linux - "__MACOSX/._name" for a file at the
		 * archive root, "__MACOSX/Folder/Sub/._name" for one inside folders, so every ancestor is tried.
		 */
		static java.util.List<File> sidecarCandidates(File file){
			File absolute=file.getAbsoluteFile();java.util.List<File> candidates=new java.util.ArrayList<File>();
			candidates.add(sidecarOf(absolute));
			String relative="._"+absolute.getName();
			for(File ancestor=absolute.getParentFile();ancestor!=null;ancestor=ancestor.getParentFile()){
				if(!ancestor.getName().equals("__MACOSX"))candidates.add(new File(new File(ancestor,"__MACOSX"),relative));
				relative=ancestor.getName()+File.separator+relative;
				if(candidates.size()>8)break; // an archive is never expanded that deep; nothing to find in / or C:\
			}
			return candidates;
		}
		/** The sidecar that exists for a file (see {@link #sidecarCandidates}), or null. */
		static File findSidecar(File file){for(File candidate:sidecarCandidates(file))if(candidate.isFile())return candidate;return null;}
		/** Length of the resource fork entry of a sidecar, read from its header only, or 0 without one. */
		static long forkLength(File sidecar){
			if(sidecar==null||!sidecar.isFile())return 0;
			try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(sidecar,"r")){
				byte[] head=new byte[26];in.readFully(head);
				if(u4(head,0)!=0x00051607)return 0;
				int entryCount=u2(head,24);byte[] entries=new byte[12*entryCount];in.readFully(entries);
				for(int i=0;i<entryCount;i++)if(u4(entries,12*i)==2)return u4(entries,12*i+8)&0xffffffffL;
			}catch(IOException e){}
			return 0;
		}
		/** Renames or moves a file's sidecar along with the file (a missing sidecar is fine; a failed move is reported). */
		static boolean moveWith(File from,File to){
			File source=findSidecar(from);if(source==null)return true;
			File destination=sidecarOf(to); // a __MACOSX sidecar comes to rest beside its file
			try{Files.move(source.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);return true;}
			catch(IOException e){return false;}
		}
		/** Deletes a file's sidecars (nothing to do without one). */
		static void deleteWith(File file){for(File sidecar:sidecarCandidates(file))if(sidecar.isFile())sidecar.delete();}
		static byte[] finderInfo(byte[] data){
			try{
				if(data==null||data.length<26||u4(data,0)!=0x00051607)return null;
				int entryCount=u2(data,24);
				for(int i=0;i<entryCount;i++){int d=26+12*i;if(u4(data,d)==9){int off=u4(data,d+4),len=u4(data,d+8);if(off+len<=data.length&&len>=32)return java.util.Arrays.copyOfRange(data,off,off+32);}}
			}catch(IndexOutOfBoundsException e){}
			return null;
		}
		static byte[] build(byte[] fork,byte[] finderInfo){return build(fork,finderInfo,null);}
		/** A sidecar with the Finder info, the resource fork and, when there is one, the Finder comment (entry 4). */
		static byte[] build(byte[] fork,byte[] finderInfo,byte[] comment){
			if(finderInfo==null)finderInfo=new byte[32];
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			int entries=comment==null?2:3;
			writeInt(out,0x00051607);writeInt(out,0x00020000);out.write(new byte[16],0,16);writeShort(out,entries);
			int finderOffset=26+12*entries,commentOffset=finderOffset+32,forkOffset=commentOffset+(comment==null?0:comment.length);
			writeInt(out,9);writeInt(out,finderOffset);writeInt(out,32);
			if(comment!=null){writeInt(out,4);writeInt(out,commentOffset);writeInt(out,comment.length);}
			writeInt(out,2);writeInt(out,forkOffset);writeInt(out,fork.length);
			out.write(finderInfo,0,32);if(comment!=null)out.write(comment,0,comment.length);out.write(fork,0,fork.length);
			return out.toByteArray();
		}
		/** The Comment entry (ID 4) of a sidecar, or null. */
		static byte[] comment(byte[] data){
			try{
				if(data==null||data.length<26||u4(data,0)!=0x00051607)return null;
				int entryCount=u2(data,24);
				for(int i=0;i<entryCount;i++){int d=26+12*i;if(u4(data,d)==4){int off=u4(data,d+4),len=u4(data,d+8);if(off>=0&&len>=0&&off+len<=data.length)return java.util.Arrays.copyOfRange(data,off,off+len);}}
			}catch(IndexOutOfBoundsException e){}
			return null;
		}
	}
}

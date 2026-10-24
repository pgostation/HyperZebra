import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finder icons for the classic Standard File dialogs: a folder shows the 'icl8' of the custom "Icon\r" file
 * inside it, a file shows its own 'icl8' (custom icon -16455 first, then 128, then the first one), masked
 * with the matching 'ICN#'. Files without one fall back to the List Manager's drawn pictograms. Images are
 * cached by path and modification time: every visit to a folder would otherwise read every resource fork again.
 * <p>
 * The launcher also asks for the icon the Finder would give a document ({@link #ofDocument}): the program that opens it
 * has a 'BNDL' resource that maps each file type it claims (an 'FREF' per type, 'APPL' for the program itself) to a local
 * icon number, and that number to the IDs of its icon family ('ICN#', 'icl8', 'ics#', 'ics8').
 */
final class FinderIcons68k {
	private FinderIcons68k(){}

	static final int CUSTOM_ICON_ID=-16455; // kCustomIconResource
	private static final int ICL8=ResourceFile68k.fourcc("icl8"),ICN_MASK=ResourceFile68k.fourcc("ICN#"),
		ICS8=ResourceFile68k.fourcc("ics8"),ICS_MASK=ResourceFile68k.fourcc("ics#"),
		BNDL=ResourceFile68k.fourcc("BNDL"),FREF=ResourceFile68k.fourcc("FREF");
	static final int APPL=ResourceFile68k.fourcc("APPL"),CDEV=ResourceFile68k.fourcc("cdev"),ANY_TYPE=ResourceFile68k.fourcc("****");
	private static final long MAX_FORK=16L<<20; // larger forks are skipped: reading them for one icon is not worth the wait
	private static final int CACHE_SIZE=1024;
	private static final Object NONE=new Object();
	private static final Map<String,Object[]> cache=new LinkedHashMap<String,Object[]>(64,0.75f,true){
		@Override protected boolean removeEldestEntry(Map.Entry<String,Object[]> eldest){return size()>CACHE_SIZE;}};

	/** The 32x32 colour icon of a file or folder, or null when it has none. */
	static BufferedImage of(File entry){
		if(entry==null)return null;
		File source=entry.isDirectory()?customIconFile(entry):entry;
		if(source==null||!source.isFile())return null;
		String key=source.getPath();long modified=source.lastModified(),length=source.length();
		synchronized(cache){Object[] hit=cache.get(key);if(hit!=null&&(Long)hit[0]==modified&&(Long)hit[1]==length)return hit[2]==NONE?null:(BufferedImage)hit[2];}
		BufferedImage image=null;
		try{image=read(source);}catch(IOException|RuntimeException ignored){}
		synchronized(cache){cache.put(key,new Object[]{modified,length,image==null?NONE:image});}
		return image;
	}

	/**
	 * The custom icon of a HyperZebra stack bundle (.hzb): HCConvert keeps the stack's icon resources as raw
	 * "icl8_-16455.data" / "ics8_-16455.data" files with their "ICN#" / "ics#" masks beside _stack.xml. The 16x16
	 * 'ics8' is returned for a small display size, the 32x32 'icl8' otherwise; either stands in when the other is
	 * missing (a 1-bit 'ICN#' / 'ics#' alone still gives the black-and-white icon). Null when the bundle has none.
	 */
	static BufferedImage ofStackBundle(File bundle,int size){
		if(bundle==null||!bundle.isDirectory())return null;
		String key=bundle.getPath()+"#"+(size<=16?"ics":"icl");
		File[] entries=bundle.listFiles();
		if(entries==null)return null;
		long modified=0,length=0;
		for(File f:entries){String n=f.getName();if(n.startsWith("icl8_")||n.startsWith("ics8_")||n.startsWith("ICN#_")||n.startsWith("ics#_")){modified=Math.max(modified,f.lastModified());length+=f.length();}}
		if(length==0)return null;
		synchronized(cache){Object[] hit=cache.get(key);if(hit!=null&&(Long)hit[0]==modified&&(Long)hit[1]==length)return hit[2]==NONE?null:(BufferedImage)hit[2];}
		BufferedImage image=null;
		try{
			boolean small=size<=16;
			image=small?decodeBundleSmall(entries):decodeBundleLarge(entries);
			if(image==null)image=small?decodeBundleLarge(entries):decodeBundleSmall(entries);
		}catch(IOException|RuntimeException ignored){}
		synchronized(cache){cache.put(key,new Object[]{modified,length,image==null?NONE:image});}
		return image;
	}
	private static BufferedImage decodeBundleLarge(File[] entries) throws IOException {
		byte[] icl8=bundleResource(entries,"icl8"),mask=bundleResource(entries,"ICN#");
		if(icl8!=null&&icl8.length>=1024)return decodeIcl8(icl8,mask);
		if(mask!=null&&mask.length>=256)return decodeIcn(mask);
		return null;
	}
	private static BufferedImage decodeBundleSmall(File[] entries) throws IOException {
		byte[] ics8=bundleResource(entries,"ics8"),mask=bundleResource(entries,"ics#");
		if(ics8!=null&&ics8.length>=256)return decodeIcs8(ics8,mask);
		if(mask!=null&&mask.length>=64)return decodeIcs(mask);
		return null;
	}
	/** The preferred "<type>_<id>.data" of a bundle: the custom icon, then ID 128, then the lowest ID. */
	private static byte[] bundleResource(File[] entries,String type) throws IOException {
		File best=null;int bestRank=Integer.MAX_VALUE;
		for(File f:entries){
			String n=f.getName();
			if(!n.startsWith(type+"_")||!n.endsWith(".data"))continue;
			int id;try{id=Integer.parseInt(n.substring(type.length()+1,n.length()-5));}catch(NumberFormatException e){continue;}
			int rank=id==CUSTOM_ICON_ID?Integer.MIN_VALUE:id==128?Integer.MIN_VALUE+1:id;
			if(rank<bestRank){bestRank=rank;best=f;}
		}
		return best==null||best.length()>MAX_FORK?null:java.nio.file.Files.readAllBytes(best.toPath());
	}

	// ------------------------------------------------------------------ a program's bundle (BNDL/FREF)

	/** What a program's 'BNDL' says: file type → the resource ID of its icon family (the same ID for ICN#, icl8, ics#, ics8). */
	static final class Bundle {
		/** The program's signature (creator), 0 when the BNDL is missing. */
		final int signature;
		private final Map<Integer,Integer> iconIdByType=new LinkedHashMap<Integer,Integer>();
		/** Every file type an 'FREF' names, icon or not ('APPL' for the program itself). */
		private final java.util.Set<Integer> claimedTypes=new java.util.LinkedHashSet<Integer>();
		Bundle(int signature){this.signature=signature;}
		/** The icon family ID for a file type, or null when the program does not claim the type. */
		Integer iconIdFor(int fileType){return iconIdByType.get(fileType);}
		/** Whether the program's bundle names this file type (one it opens, the way the Finder decides what a program can open). */
		boolean claims(int fileType){return claimedTypes.contains(fileType);}
		/** Whether the program claims any file type besides its own ('APPL'): one that opens documents at all. */
		boolean claimsDocuments(){for(int t:claimedTypes)if(t!=0x4150504C)return true;return false;}
		boolean isEmpty(){return iconIdByType.isEmpty();}
	}

	/** The bundle of a program (its BNDL/FREF resources), an empty one when it has none. */
	static Bundle bundleOf(File app){
		if(app==null||!app.isFile())return new Bundle(0);
		String key=app.getPath()+"#bndl";long modified=app.lastModified(),length=app.length();
		synchronized(cache){Object[] hit=cache.get(key);if(hit!=null&&(Long)hit[0]==modified&&(Long)hit[1]==length)return (Bundle)hit[2];}
		Bundle bundle=new Bundle(0);
		try{byte[] fork=forkOf(app);if(fork!=null)bundle=parseBundle(fork);}catch(IOException|RuntimeException ignored){}
		synchronized(cache){cache.put(key,new Object[]{modified,length,bundle});}
		return bundle;
	}
	private static byte[] forkOf(File file) throws IOException {
		if(ResourceFile68k.nativeForkLength(file)>MAX_FORK||file.length()>MAX_FORK)return null;
		byte[] fork=ResourceFile68k.readForkBytes(file,null);
		return fork==null||fork.length<16?null:fork;
	}
	/**
	 * 'BNDL': signature, version, then per resource type ('ICN#', 'FREF') a list of (local ID, resource ID) pairs; each
	 * 'FREF' names a file type and the local ID of its icon. Only the first BNDL counts (the Finder's does too).
	 */
	static Bundle parseBundle(byte[] fork){
		byte[] bndl=first(fork,BNDL);
		if(bndl==null||bndl.length<8)return new Bundle(0);
		Bundle bundle=new Bundle(u4(bndl,0));
		Map<Integer,Integer> iconIdByLocal=new LinkedHashMap<Integer,Integer>(),frefIdByLocal=new LinkedHashMap<Integer,Integer>();
		try{
			int types=(short)u2(bndl,6)+1,at=8;
			for(int t=0;t<types;t++){
				int type=u4(bndl,at),count=(short)u2(bndl,at+4)+1;at+=6;
				for(int i=0;i<count;i++,at+=4){
					int local=(short)u2(bndl,at),id=(short)u2(bndl,at+2);
					if(type==ICN_MASK)iconIdByLocal.put(local,id);else if(type==FREF)frefIdByLocal.put(local,id);
				}
			}
		}catch(IndexOutOfBoundsException ignored){}
		for(Integer frefId:frefIdByLocal.values()){
			byte[] fref=byId(fork,FREF,frefId);
			if(fref==null||fref.length<6)continue;
			Integer iconId=iconIdByLocal.get((int)(short)u2(fref,4));
			int fileType=u4(fref,0);
			bundle.claimedTypes.add(fileType);
			if(iconId!=null&&!bundle.iconIdByType.containsKey(fileType))bundle.iconIdByType.put(fileType,iconId);
		}
		return bundle;
	}

	/**
	 * The icon a program's bundle gives a file type (the program's own icon for 'APPL'): the 16x16 family member for a
	 * small display size, the 32x32 one otherwise, either standing in for a missing other. Null when the bundle does not
	 * claim the type (ask for {@link #ANY_TYPE} to get the bundle's icon for any other file, when it has one).
	 */
	static BufferedImage ofDocument(File app,int fileType,int size){
		if(app==null||!app.isFile()||fileType==0)return null;
		Integer iconId=bundleOf(app).iconIdFor(fileType);
		if(iconId==null)return null;
		String key=app.getPath()+"#"+ResourceFile68k.typeName(fileType)+"#"+(size<=16?"ics":"icl");long modified=app.lastModified(),length=app.length();
		synchronized(cache){Object[] hit=cache.get(key);if(hit!=null&&(Long)hit[0]==modified&&(Long)hit[1]==length)return hit[2]==NONE?null:(BufferedImage)hit[2];}
		BufferedImage image=null;
		try{
			byte[] fork=forkOf(app);
			if(fork!=null){
				boolean small=size<=16;
				image=small?familySmall(fork,iconId):familyLarge(fork,iconId);
				if(image==null)image=small?familyLarge(fork,iconId):familySmall(fork,iconId);
			}
		}catch(IOException|RuntimeException ignored){}
		synchronized(cache){cache.put(key,new Object[]{modified,length,image==null?NONE:image});}
		return image;
	}
	/** The raw icon resource ('ICN#', 'icl8', 'ics#'...) a program's bundle gives a file type, or null. */
	static byte[] bundleIcon(File app,int fileType,int resourceType){
		if(app==null||!app.isFile())return null;
		Integer iconId=bundleOf(app).iconIdFor(fileType);if(iconId==null)return null;
		try{byte[] fork=forkOf(app);return fork==null?null:byId(fork,resourceType,iconId);}catch(IOException|RuntimeException e){return null;}
	}
	/** A file's own custom icon (the family with ID -16455, which the Finder shows in place of the program's), or null. */
	static BufferedImage ofCustom(File file,int size){
		if(file==null||!file.isFile())return null;
		String key=file.getPath()+"#custom#"+(size<=16?"ics":"icl");long modified=file.lastModified(),length=file.length();
		synchronized(cache){Object[] hit=cache.get(key);if(hit!=null&&(Long)hit[0]==modified&&(Long)hit[1]==length)return hit[2]==NONE?null:(BufferedImage)hit[2];}
		BufferedImage image=null;
		try{
			byte[] fork=forkOf(file);
			if(fork!=null){
				boolean small=size<=16;
				image=small?familySmall(fork,CUSTOM_ICON_ID):familyLarge(fork,CUSTOM_ICON_ID);
				if(image==null)image=small?familyLarge(fork,CUSTOM_ICON_ID):familySmall(fork,CUSTOM_ICON_ID);
			}
		}catch(IOException|RuntimeException ignored){}
		synchronized(cache){cache.put(key,new Object[]{modified,length,image==null?NONE:image});}
		return image;
	}
	private static BufferedImage familyLarge(byte[] fork,int id){
		byte[] icl8=byId(fork,ICL8,id),mask=byId(fork,ICN_MASK,id);
		if(icl8!=null&&icl8.length>=1024)return decodeIcl8(icl8,mask);
		if(mask!=null&&mask.length>=256)return decodeIcn(mask);
		return null;
	}
	private static BufferedImage familySmall(byte[] fork,int id){
		byte[] ics8=byId(fork,ICS8,id),mask=byId(fork,ICS_MASK,id);
		if(ics8!=null&&ics8.length>=256)return decodeIcs8(ics8,mask);
		if(mask!=null&&mask.length>=64)return decodeIcs(mask);
		return null;
	}

	/** The "Icon\r" file the Finder keeps a folder's custom icon in (System 7 writes CR; some archives keep LF or drop it). */
	static File customIconFile(File directory){
		for(String name:new String[]{"Icon\r","Icon\n","Icon"}){File f=new File(directory,name);if(f.isFile())return f;}
		return null;
	}

	private static BufferedImage read(File file) throws IOException {
		byte[] fork=forkOf(file);
		if(fork==null)return null;
		byte[] icl8=find(fork,ICL8),mask=find(fork,ICN_MASK);
		if(icl8!=null&&icl8.length>=1024)return decodeIcl8(icl8,mask);
		if(mask!=null&&mask.length>=256)return decodeIcn(mask);
		return null;
	}

	/** The preferred resource of a type from a raw fork: the custom icon, then ID 128, then the first listed. */
	static byte[] find(byte[] b,int type){return lookup(b,type,null,false);}
	/** The resource of a type with this ID from a raw fork, or null. */
	static byte[] byId(byte[] b,int type,int id){return lookup(b,type,id,false);}
	/** The first listed resource of a type from a raw fork, or null. */
	static byte[] first(byte[] b,int type){return lookup(b,type,null,true);}
	private static byte[] lookup(byte[] b,int type,Integer wantedId,boolean firstListed){
		try{
			int dataOffset=u4(b,0),mapOffset=u4(b,4);
			int typeListOffset=u2(b,mapOffset+24)+mapOffset;
			int typeCount=(short)u2(b,typeListOffset)+1;
			for(int i=0;i<typeCount;i++){
				int entry=typeListOffset+2+8*i;
				if(u4(b,entry)!=type)continue;
				int count=u2(b,entry+4)+1,refList=u2(b,entry+6)+typeListOffset;
				int best=-1,bestRank=Integer.MAX_VALUE;
				for(int j=0;j<count;j++){
					int id=(short)u2(b,refList+12*j);
					int rank=wantedId!=null?(id==wantedId?0:Integer.MAX_VALUE):firstListed?j:id==CUSTOM_ICON_ID?0:id==128?1:2+j;
					if(rank<bestRank){bestRank=rank;best=j;}
				}
				if(best<0||bestRank==Integer.MAX_VALUE)return null;
				int ref=refList+12*best;
				int off=((b[ref+5]&0xff)<<16|(b[ref+6]&0xff)<<8|(b[ref+7]&0xff))+dataOffset;
				int length=u4(b,off);if(length<0||off+4+length>b.length)return null;
				byte[] data=new byte[length];System.arraycopy(b,off+4,data,0,length);return data;
			}
		}catch(IndexOutOfBoundsException ignored){}
		return null;
	}

	/** 'icl8' (1024 indexes into the system palette) with the 'ICN#' mask; without a mask, palette index 0 (white) is clear. */
	static BufferedImage decodeIcl8(byte[] icl8,byte[] icn){
		BufferedImage image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
		boolean masked=icn!=null&&icn.length>=256;
		for(int y=0;y<32;y++)for(int x=0;x<32;x++){
			int index=icl8[y*32+x]&0xff;
			boolean opaque=masked?(icn[128+y*4+x/8]>>(7-x%8)&1)!=0:index!=0;
			if(opaque)image.setRGB(x,y,0xff000000|MacSystemPalette.rgb(index));
		}
		return image;
	}
	/** 'ICN#' alone: black-and-white image masked by its second half. */
	static BufferedImage decodeIcn(byte[] icn){
		BufferedImage image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<32;y++)for(int x=0;x<32;x++){
			if((icn[128+y*4+x/8]>>(7-x%8)&1)==0)continue;
			image.setRGB(x,y,(icn[y*4+x/8]>>(7-x%8)&1)!=0?0xff000000:0xffffffff);
		}
		return image;
	}

	/** 'ics8' (256 indexes into the system palette) with the 'ics#' mask; without a mask, palette index 0 (white) is clear. */
	static BufferedImage decodeIcs8(byte[] ics8,byte[] ics){
		BufferedImage image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
		boolean masked=ics!=null&&ics.length>=64;
		for(int y=0;y<16;y++)for(int x=0;x<16;x++){
			int index=ics8[y*16+x]&0xff;
			boolean opaque=masked?(ics[32+y*2+x/8]>>(7-x%8)&1)!=0:index!=0;
			if(opaque)image.setRGB(x,y,0xff000000|MacSystemPalette.rgb(index));
		}
		return image;
	}
	/** 'ics#' alone: 16x16 black-and-white image masked by its second half. */
	static BufferedImage decodeIcs(byte[] ics){
		BufferedImage image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<16;y++)for(int x=0;x<16;x++){
			if((ics[32+y*2+x/8]>>(7-x%8)&1)==0)continue;
			image.setRGB(x,y,(ics[y*2+x/8]>>(7-x%8)&1)!=0?0xff000000:0xffffffff);
		}
		return image;
	}

	private static int u2(byte[] b,int at){return (b[at]&0xff)<<8|(b[at+1]&0xff);}
	private static int u4(byte[] b,int at){return (b[at]&0xff)<<24|(b[at+1]&0xff)<<16|(b[at+2]&0xff)<<8|(b[at+3]&0xff);}
}

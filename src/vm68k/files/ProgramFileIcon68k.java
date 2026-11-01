import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host Finder's icon of a program a 68K program made - StuffIt Expander restoring one from an archive, an installer.
 * The macOS Finder reads no 'BNDL', so a classic program shows the plain document icon there, and among the files of an
 * expanded folder it is hard to find. The icon its bundle gives it (the family the 'FREF' of 'APPL' names) becomes its
 * custom icon the way the Finder's Paste makes one: the classic members again as -16455, an 'icns' -16455 with every
 * size macOS asks for, and kHasCustomIcon. A classic Finder shows the same icon from them.
 * <p>
 * The resources are added after everything the fork held, whose bytes and offsets stay as they were (a program that
 * reads its own fork finds what it shipped with); a program with a custom icon of its own keeps it, and the file keeps
 * its modification date. Nothing is written on hosts without resource forks of their own.
 */
final class ProgramFileIcon68k {
	private ProgramFileIcon68k(){}

	static final int CUSTOM_ICON_ID=-16455;
	private static final int APPL=ResourceFile68k.fourcc("APPL"),ICNS=ResourceFile68k.fourcc("icns");
	private static final int HAS_BUNDLE=0x20,HAS_CUSTOM_ICON=0x04; // fdFlags high byte: kHasBundle $2000, kHasCustomIcon $0400
	private static final long MAX_FORK=16L<<20;
	/** The classic members a bundle's family may have, the least size of each, and the mask a colour member needs. */
	private static final String[][] MEMBERS={{"ICN#","256",null},{"icl4","512","ICN#"},{"icl8","1024","ICN#"},
		{"ics#","64",null},{"ics4","128","ics#"},{"ics8","256","ics#"}};

	/** Gives {@code file} its bundle's icon as its custom icon when it is a program with a bundle and none yet; true when written. */
	static boolean give(File file){
		if(!ResourceFile68k.nativeForks()||file==null||!file.isFile())return false;
		byte[] info=FinderInfo68k.nativeFinderInfo(file);
		if(info==null||u4(info,0)!=APPL||(info[8]&HAS_BUNDLE)==0||(info[8]&HAS_CUSTOM_ICON)!=0)return false;
		long length=ResourceFile68k.nativeForkLength(file);
		if(length<16||length>MAX_FORK)return false;
		try{
			byte[] fork=ResourceFile68k.readForkBytes(file,null);
			byte[] added=withIcon(fork);
			if(added==null)return false;
			long modified=file.lastModified();
			ResourceFile68k.writeForkBytes(file,added,ResourceFile68k.Storage.NATIVE);
			info[8]|=HAS_CUSTOM_ICON;
			FinderInfo68k.writeNative(file,info);
			if(modified>0)file.setLastModified(modified);
			if(Mac68kApi.TRACE)System.err.println("[file] "+file+": its bundle icon is its host Finder icon now");
			return true;
		}catch(IOException|RuntimeException e){
			System.err.println("[file] could not give "+file.getName()+" its icon: "+e);
			return false;
		}
	}

	/** {@code fork} with the custom icon made from its bundle's 'APPL' icon added, or null when it has no such icon or a custom icon already. */
	static byte[] withIcon(byte[] fork){
		if(fork==null||fork.length<16)return null;
		Integer id=FinderIcons68k.parseBundle(fork).iconIdFor(APPL);
		if(id==null||FinderIcons68k.byId(fork,ICNS,CUSTOM_ICON_ID)!=null)return null;
		Map<Integer,byte[]> family=new LinkedHashMap<Integer,byte[]>();
		for(String[] m:MEMBERS){
			int type=ResourceFile68k.fourcc(m[0]);
			if(FinderIcons68k.byId(fork,type,CUSTOM_ICON_ID)!=null)return null; // a custom icon the flag does not show: left alone
			byte[] b=FinderIcons68k.byId(fork,type,id);
			if(b!=null&&b.length>=Integer.parseInt(m[1]))family.put(type,b);
		}
		// a colour member without its mask would be a filled square
		for(String[] m:MEMBERS)if(m[2]!=null&&!family.containsKey(ResourceFile68k.fourcc(m[2])))family.remove(ResourceFile68k.fourcc(m[0]));
		if(family.isEmpty())return null;
		byte[] icns=StackFileIcon.familyOfClassic(family);
		if(icns==null)return null;
		List<Object[]> add=new ArrayList<Object[]>();
		for(Map.Entry<Integer,byte[]> e:family.entrySet())add.add(new Object[]{e.getKey(),e.getValue()});
		add.add(new Object[]{ICNS,icns});
		return append(fork,add);
	}

	/**
	 * {@code fork} with the resources {type, data} (ID -16455, no name) added: everything up to the end of the old fork stays,
	 * the new data follows it inside a longer data area, then a new map with the old entries unchanged. Null when the
	 * fork's map cannot be read or the data would lie beyond the map's 24-bit offsets.
	 */
	static byte[] append(byte[] fork,List<Object[]> resources){
		try{
			int dataOffset=u4(fork,0),mapOffset=u4(fork,4),mapLength=u4(fork,12);
			if(dataOffset<16||mapOffset<16||mapOffset+mapLength>fork.length)return null;
			int typeListOffset=mapOffset+u2(fork,mapOffset+24),nameListOffset=mapOffset+u2(fork,mapOffset+26);
			int typeCount=(short)u2(fork,typeListOffset)+1;
			Map<Integer,List<byte[]>> refs=new LinkedHashMap<Integer,List<byte[]>>();
			for(int i=0;i<typeCount;i++){
				int entry=typeListOffset+2+8*i,count=u2(fork,entry+4)+1,list=typeListOffset+u2(fork,entry+6);
				List<byte[]> l=refs.computeIfAbsent(u4(fork,entry),k->new ArrayList<byte[]>());
				for(int j=0;j<count;j++)l.add(java.util.Arrays.copyOfRange(fork,list+12*j,list+12*j+12));
			}
			byte[] names=nameListOffset<=mapOffset+mapLength?java.util.Arrays.copyOfRange(fork,nameListOffset,mapOffset+mapLength):new byte[0];
			ByteArrayOutputStream out=new ByteArrayOutputStream(fork.length+65536);
			out.write(fork,0,fork.length);
			if((out.size()&1)!=0)out.write(0);
			for(Object[] r:resources){
				int at=out.size()-dataOffset;byte[] data=(byte[])r[1];
				if(at+4+data.length>=1<<24)return null;
				byte[] ref=new byte[12];
				ref[0]=(byte)(CUSTOM_ICON_ID>>8);ref[1]=(byte)CUSTOM_ICON_ID;ref[2]=(byte)0xff;ref[3]=(byte)0xff; // no name
				ref[5]=(byte)(at>>>16);ref[6]=(byte)(at>>>8);ref[7]=(byte)at;
				refs.computeIfAbsent((Integer)r[0],k->new ArrayList<byte[]>()).add(ref);
				put4(out,data.length);out.write(data,0,data.length);
				if((out.size()&1)!=0)out.write(0);
			}
			int newMapOffset=out.size(),typeListSize=2+8*refs.size(),refCount=0;
			for(List<byte[]> l:refs.values())refCount+=l.size();
			int newMapLength=28+typeListSize+12*refCount+names.length;
			byte[] header=new byte[16];
			putInt(header,0,dataOffset);putInt(header,4,newMapOffset);putInt(header,8,newMapOffset-dataOffset);putInt(header,12,newMapLength);
			out.write(header,0,16);
			put4(out,0);put2(out,0);out.write(fork,mapOffset+22,2); // next map handle, file reference, the fork's attributes
			put2(out,28);put2(out,28+typeListSize+12*refCount);
			put2(out,refs.size()-1);
			int refAt=typeListSize;
			for(Map.Entry<Integer,List<byte[]>> e:refs.entrySet()){put4(out,e.getKey());put2(out,e.getValue().size()-1);put2(out,refAt);refAt+=12*e.getValue().size();}
			for(List<byte[]> l:refs.values())for(byte[] ref:l){java.util.Arrays.fill(ref,8,12,(byte)0);out.write(ref,0,12);} // no handles in a file
			out.write(names,0,names.length);
			byte[] result=out.toByteArray();
			System.arraycopy(header,0,result,0,16);
			return result;
		}catch(IndexOutOfBoundsException e){return null;}
	}

	private static int u2(byte[] b,int at){return (b[at]&0xff)<<8|(b[at+1]&0xff);}
	private static int u4(byte[] b,int at){return (b[at]&0xff)<<24|(b[at+1]&0xff)<<16|(b[at+2]&0xff)<<8|(b[at+3]&0xff);}
	private static void putInt(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}
	private static void put2(ByteArrayOutputStream o,int v){o.write(v>>>8);o.write(v);}
	private static void put4(ByteArrayOutputStream o,int v){o.write(v>>>24);o.write(v>>>16);o.write(v>>>8);o.write(v);}
}

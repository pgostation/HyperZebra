import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Icon Services (Mac OS 8.5, Icons.h): _IconServicesDispatch $AA75 with the selector in D0 (MOVEQ), Pascal arguments on the
 * stack below an OSErr (Boolean, IconRef) result slot. PowerPC programs reach it through the Toolbox bridge, which runs
 * the same inline words. An IconRef is a small guest block standing for a reference-counted set of icon family members
 * ('ICN#', 'icl8', 'ics4', 'il32'...): the System's generic icons for the kSystemIconsCreator types, a file's custom icon
 * (-16455 in its resource fork), a program's bundle icon through the desktop database (PBDTGetIcon), or data a program
 * registered. Two files of the same type and creator share one IconRef, as on a Mac. Drawing goes through the port as
 * PlotIconSuite's does; hit tests and regions follow the icon's mask.
 */
final class IconServices68k {
	private final Toolbox68k tb;
	private final M68kMemory memory;
	IconServices68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	static final int NO_ERR=0,PARAM_ERR=-50,FNF_ERR=-43,IO_ERR=-36,RES_NOT_FOUND=-192,
		INVALID_ICON_REF_ERR=-2580,NO_SUCH_ICON_ERR=-2581,NO_ICON_DATA_AVAILABLE_ERR=-2582;
	static final int SYSTEM_ICONS_CREATOR=fourcc("macs"),ICON_FAMILY=fourcc("icns"),CUSTOM_ICON_ID=-16455;
	static final int ICN_MASK=fourcc("ICN#"),ICL4=fourcc("icl4"),ICL8=fourcc("icl8"),IL32=fourcc("il32"),L8MK=fourcc("l8mk"),
		ICS_MASK=fourcc("ics#"),ICS4=fourcc("ics4"),ICS8=fourcc("ics8"),IS32=fourcc("is32"),S8MK=fourcc("s8mk"),
		ICM_MASK=fourcc("icm#"),ICM4=fourcc("icm4"),ICM8=fourcc("icm8"),
		ICH_MASK=fourcc("ich#"),ICH4=fourcc("ich4"),ICH8=fourcc("ich8"),IH32=fourcc("ih32"),H8MK=fourcc("h8mk"),
		IT32=fourcc("it32"),T8MK=fourcc("t8mk");
	/** Icon family elements in 'icns' order: type, IconSelectorValue bit (0: only with kSelectorAllAvailableData), size (0: compressed, variable). */
	private static final int[][] ELEMENTS={
		{ICN_MASK,0x1,256},{ICL4,0x2,512},{ICL8,0x4,1024},{IL32,0x8,0},{L8MK,0x10,1024},
		{ICS_MASK,0x100,64},{ICS4,0x200,128},{ICS8,0x400,256},{IS32,0x800,0},{S8MK,0x1000,256},
		{ICM_MASK,0x10000,48},{ICM4,0x20000,96},{ICM8,0x40000,192},
		{ICH_MASK,0x1000000,576},{ICH4,0x2000000,1152},{ICH8,0x4000000,2304},{IH32,0x8000000,0},{H8MK,0x10000000,2304},
		{IT32,0,0},{T8MK,0,16384}};
	/** The classic members an icon suite (and a resource family) holds. */
	private static final int[] CLASSIC={ICN_MASK,ICL4,ICL8,ICS_MASK,ICS4,ICS8,ICM_MASK,ICM4,ICM8};
	static int fourcc(String s){return ResourceFile68k.fourcc(s);}

	// ------------------------------------------------------------------ IconRefs

	/** Where a file-backed IconRef's data comes from again (UpdateIconRef). */
	private interface Loader{Map<Integer,byte[]> load() throws M68kException;}
	private static final class Ref {
		final int address,creator,type;
		int owners=1;boolean registered;String cacheKey;
		Map<Integer,byte[]> members=new HashMap<Integer,byte[]>(),override;
		Ref background,foreground;Loader loader;
		Ref(int address,int creator,int type){this.address=address;this.creator=creator;this.type=type;}
		Map<Integer,byte[]> data(){return override!=null?override:members;}
	}
	private final Map<Integer,Ref> refs=new HashMap<Integer,Ref>();
	/** Live IconRefs by what they show ("creator/type", "file:path"): the same icon hands out the same IconRef. */
	private final Map<String,Ref> cache=new HashMap<String,Ref>();
	/** RegisterIconRefFrom... entries by creator and type. */
	private final Map<Long,Ref> registry=new HashMap<Long,Ref>();
	private final Map<Integer,Boolean> customIcons=new HashMap<Integer,Boolean>();

	private static long key(int creator,int type){return (long)creator<<32|type&0xffffffffL;}
	private Ref newRef(int creator,int type,Map<Integer,byte[]> members) throws M68kCpuException {
		int address=memory.allocate(8,4);memory.writeLong(address,fourcc("icnR"));memory.writeLong(address+4,type);
		Ref r=new Ref(address,creator,type);if(members!=null)r.members=members;refs.put(address,r);return r;
	}
	/** Owners of an IconRef, 0 when it is not valid. */
	int owners(int ref){Ref r=refs.get(ref);return r==null?0:r.owners;}
	boolean valid(int ref){return refs.containsKey(ref);}
	private void release(Ref r){
		if(r.owners>0)r.owners--;
		if(r.owners==0&&!r.registered)dispose(r);
	}
	private void dispose(Ref r){
		if(refs.remove(r.address)==null)return;
		if(r.cacheKey!=null&&cache.get(r.cacheKey)==r)cache.remove(r.cacheKey);
		memory.free(r.address);
		if(r.background!=null)release(r.background);if(r.foreground!=null)release(r.foreground);
	}
	/** A cached IconRef (acquired) or a new one from {@code loader}, cached under {@code cacheKey}. */
	private Ref cached(String cacheKey,int creator,int type,Loader loader) throws M68kException {
		Ref r=cache.get(cacheKey);
		if(r!=null&&refs.containsKey(r.address)){r.owners++;return r;}
		Map<Integer,byte[]> m=loader.load();
		if(m==null||m.isEmpty())return null;
		r=newRef(creator,type,m);r.cacheKey=cacheKey;r.loader=loader;cache.put(cacheKey,r);return r;
	}

	// ------------------------------------------------------------------ the trap

	/** Reads the Pascal arguments (sizes 1 = a byte in a word's high half, 2, 4; left to right) and pops them. */
	private int[] pop(M68kCpu cpu,int... sizes) throws M68kCpuException {
		int[] v=new int[sizes.length];int at=cpu.a[7];
		for(int i=sizes.length-1;i>=0;i--){
			if(sizes[i]==4){v[i]=memory.readLong(at);at+=4;}
			else if(sizes[i]==1){v[i]=memory.readWord(at)>>>8&0xff;at+=2;}
			else{v[i]=(short)memory.readWord(at);at+=2;}
		}
		cpu.a[7]=at;return v;
	}
	private void err(M68kCpu cpu,int e) throws M68kCpuException {memory.writeWord(cpu.a[7],e);}
	private void writeRef(int p,Ref r) throws M68kCpuException {if(p!=0)memory.writeLong(p,r==null?0:r.address);}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xff;
		switch(selector){
			case 0x02:{int[] a=pop(cpu,4,4,4);int[] label={0};Ref r=null;int e; // GetIconRefFromFile(spec, *ref, *label)
				try{r=fromFile(a[0],label);e=r==null?NO_SUCH_ICON_ERR:NO_ERR;}catch(FileError x){e=x.code;}
				writeRef(a[1],r);if(a[2]!=0)memory.writeWord(a[2],label[0]);err(cpu,e);return true;}
			case 0x08:{int[] a=pop(cpu,4,4);Ref r=registry.remove(key(a[0],a[1])); // UnregisterIconRef(creator, type)
				if(r!=null){r.registered=false;if(r.owners==0)dispose(r);}err(cpu,r==null?NO_SUCH_ICON_ERR:NO_ERR);return true;}
			case 0x09:{int[] a=pop(cpu,4);Ref r=refs.get(a[0]); // UpdateIconRef: file icons read their data again
				if(r==null){err(cpu,INVALID_ICON_REF_ERR);return true;}
				if(r.loader!=null){Map<Integer,byte[]> m=r.loader.load();if(m!=null&&!m.isEmpty())r.members=m;}
				err(cpu,NO_ERR);return true;}
			case 0x0b:{int[] a=pop(cpu,4,4);Ref r=refs.get(a[0]);if(a[1]!=0)memory.writeWord(a[1],r==null?0:r.owners);err(cpu,r==null?INVALID_ICON_REF_ERR:NO_ERR);return true;} // GetIconRefOwners
			case 0x0e:{int[] a=pop(cpu,4,2,2,4,4);err(cpu,plot(a[0],a[1],a[2],a[4]));return true;} // PlotIconRef(rect, align, transform, usage, ref)
			case 0x0f:case 0x10:{int[] a=pop(cpu,4,4,2,4,4);tb.writeBoolean(cpu.a[7],hit(a[0],selector==0x10,a[1],a[2],a[4]));return true;} // PtInIconRef(*pt, rect, align, usage, ref) / RectInIconRef(*testRect, ...)
			case 0x11:{int[] a=pop(cpu,4,4,2,4,4);err(cpu,toRegion(a[0],a[1],a[2],a[4]));return true;} // IconRefToRgn(rgn, rect, align, usage, ref)
			case 0x12:{int[] a=pop(cpu,4,4,4,4);Ref r=refs.get(a[3]); // GetIconSizesFromIconRef(in, *out, usage, ref)
				if(r==null){err(cpu,INVALID_ICON_REF_ERR);return true;}
				if(a[1]!=0)memory.writeLong(a[1],available(r)&a[0]);err(cpu,NO_ERR);return true;}
			case 0x14:{int[] a=pop(cpu,4,4,4);Ref bg=refs.get(a[0]),fg=refs.get(a[1]); // CompositeIconRef(background, foreground, *composite)
				if(bg==null||fg==null){writeRef(a[2],null);err(cpu,INVALID_ICON_REF_ERR);return true;}
				Ref c=newRef(bg.creator,bg.type,null);c.background=bg;c.foreground=fg;bg.owners++;fg.owners++;writeRef(a[2],c);err(cpu,NO_ERR);return true;}
			case 0x15:pop(cpu,4);err(cpu,NO_ERR);return true; // IconServicesInit(initBlock)
			case 0x16:return true; // IconServicesTerminate
			case 0x18:pop(cpu,2);err(cpu,NO_ERR);return true; // FlushIconRefsByVolume: the data is read again when needed anyway
			case 0x1a:{int[] a=pop(cpu,4,4,4);Ref r=refs.get(a[0]); // IsIconRefComposite(ref, *background, *foreground)
				writeRef(a[1],r==null?null:r.background);writeRef(a[2],r==null?null:r.foreground);err(cpu,r==null?INVALID_ICON_REF_ERR:NO_ERR);return true;}
			case 0x1c:{int[] a=pop(cpu,4,4,4,4); // RegisterIconRefFromIconFamily(creator, type, family, *ref): the data is copied
				Map<Integer,byte[]> m=a[2]==0?null:parseFamily(tb.api.handleContents(a[2]));
				if(m==null||m.isEmpty()){writeRef(a[3],null);err(cpu,m==null?PARAM_ERR:NO_ICON_DATA_AVAILABLE_ERR);return true;}
				writeRef(a[3],register(a[0],a[1],m,null));err(cpu,NO_ERR);return true;}
			case 0x1e:{int[] a=pop(cpu,4);Ref r=refs.get(a[0]);if(r!=null)r.override=null;err(cpu,r==null?INVALID_ICON_REF_ERR:NO_ERR);return true;} // RemoveIconRefOverride
			case 0x1f:{int[] a=pop(cpu,2,1);customIcons.put(a[0],a[1]!=0);err(cpu,NO_ERR);return true;} // SetCustomIconsEnabled(vRefNum, enable)
			case 0x20:{int[] a=pop(cpu,2,4);if(a[1]!=0)tb.writeBoolean(a[1],customIconsEnabled(a[0]));err(cpu,NO_ERR);return true;} // GetCustomIconsEnabled(vRefNum, *enabled)
			case 0x21:{int[] a=pop(cpu,2,4,4,4);Ref r=iconRef(a[0],a[1],a[2]);writeRef(a[3],r);err(cpu,r==null?NO_SUCH_ICON_ERR:NO_ERR);return true;} // GetIconRef(vRefNum, creator, type, *ref)
			case 0x22:{int[] a=pop(cpu,2,4,4,1,1,4); // GetIconRefFromFolder(vRefNum, parentID, folderID, attributes, access, *ref)
				Ref r=iconRef(a[0],SYSTEM_ICONS_CREATOR,specialFolder(a[0],a[2]));writeRef(a[5],r);err(cpu,r==null?NO_SUCH_ICON_ERR:NO_ERR);return true;}
			case 0x23:{int[] a=pop(cpu,4,4,4,2,4);int[] e={NO_ERR}; // RegisterIconRefFromResource(creator, type, spec, resID, *ref)
				Map<Integer,byte[]> m=resourceFileFamily(a[2],a[3],e);
				if(m==null||m.isEmpty()){writeRef(a[4],null);err(cpu,e[0]!=NO_ERR?e[0]:RES_NOT_FOUND);return true;}
				final int spec=copySpec(a[2]),id=a[3];
				writeRef(a[4],register(a[0],a[1],m,()->resourceFileFamily(spec,id,new int[1])));err(cpu,NO_ERR);return true;}
			case 0x24:{int[] a=pop(cpu,4,4,4);Ref r=refs.get(a[0]); // IconRefToIconFamily(ref, whichIcons, *family)
				if(r==null){if(a[2]!=0)memory.writeLong(a[2],0);err(cpu,INVALID_ICON_REF_ERR);return true;}
				if(a[2]!=0)memory.writeLong(a[2],tb.api.createHandle(family(r,a[1])));err(cpu,NO_ERR);return true;}
			case 0x25:{int[] a=pop(cpu,4,4,4);err(cpu,familyToSuite(a[0],a[1],a[2]));return true;} // IconFamilyToIconSuite(family, whichIcons, *suite)
			case 0x26:{int[] a=pop(cpu,4,4,4);err(cpu,suiteToFamily(a[0],a[1],a[2]));return true;} // IconSuiteToIconFamily(suite, whichIcons, *family)
			case 0x27:{int[] a=pop(cpu,4);Ref r=refs.get(a[0]);if(r!=null)r.owners++;err(cpu,r==null?INVALID_ICON_REF_ERR:NO_ERR);return true;} // AcquireIconRef
			case 0x28:{int[] a=pop(cpu,4);Ref r=refs.get(a[0]);if(r!=null)release(r);err(cpu,r==null?INVALID_ICON_REF_ERR:NO_ERR);return true;} // ReleaseIconRef
			case 0x29:pop(cpu,4,4);err(cpu,NO_ERR);return true; // FlushIconRefs(creator, type)
			case 0x2a:{int[] a=pop(cpu,4,4,2);Ref r=refs.get(a[0]);int[] e={NO_ERR}; // OverrideIconRefFromResource(ref, spec, resID)
				if(r==null){err(cpu,INVALID_ICON_REF_ERR);return true;}
				Map<Integer,byte[]> m=resourceFileFamily(a[1],a[2],e);
				if(m==null||m.isEmpty()){err(cpu,e[0]!=NO_ERR?e[0]:RES_NOT_FOUND);return true;}
				r.override=m;err(cpu,NO_ERR);return true;}
			case 0x2b:{int[] a=pop(cpu,4,4);Ref r=refs.get(a[0]),n=refs.get(a[1]); // OverrideIconRef(old, new): the old one shows the new one's pictures
				if(r==null||n==null){err(cpu,INVALID_ICON_REF_ERR);return true;}
				r.override=new HashMap<Integer,byte[]>(n.data());err(cpu,NO_ERR);return true;}
			case 0x30:{int[] a=pop(cpu,4,4,4);err(cpu,setFamilyData(a[0],a[1],a[2]));return true;} // SetIconFamilyData(family, type, h)
			case 0x31:{int[] a=pop(cpu,4,4,4);err(cpu,getFamilyData(a[0],a[1],a[2]));return true;} // GetIconFamilyData(family, type, h)
			case 0x32:{int[] a=pop(cpu,4);tb.writeBoolean(cpu.a[7],refs.containsKey(a[0]));return true;} // IsValidIconRef
			case 0x33:{int[] a=pop(cpu,4);Ref r=refs.get(a[0]);tb.writeBoolean(cpu.a[7],r==null||maskEmpty(r));return true;} // IsIconRefMaskEmpty
			case 0x34:{int[] a=pop(cpu,4,4,4);memory.writeLong(cpu.a[7],variant(a[0],a[1],a[2]));return true;} // GetIconRefVariant(ref, variant, *transform) : IconRef
			case 0x35:{int[] a=pop(cpu,4,4,4,4);int[] e={NO_ERR}; // RegisterIconRefFromIconFile(creator, type, spec, *ref)
				byte[] b=readIconFile(a[2],e);Map<Integer,byte[]> m=b==null?null:parseFamily(b);
				if(m==null||m.isEmpty()){writeRef(a[3],null);err(cpu,e[0]!=NO_ERR?e[0]:NO_ICON_DATA_AVAILABLE_ERR);return true;}
				Ref existing=a[0]==SYSTEM_ICONS_CREATOR&&a[1]==0?null:registry.get(key(a[0],a[1]));
				if(existing!=null){existing.owners++;writeRef(a[3],existing);err(cpu,NO_ERR);return true;} // a registered pair keeps its first IconRef
				writeRef(a[3],register(a[0],a[1],m,null));err(cpu,NO_ERR);return true;}
			case 0x36:{int[] a=pop(cpu,4,4);int[] e={NO_ERR};byte[] b=readIconFile(a[0],e); // ReadIconFile(spec, *family)
				if(a[1]!=0)memory.writeLong(a[1],b==null?0:tb.api.createHandle(b));err(cpu,b==null?e[0]:NO_ERR);return true;}
			case 0x37:{int[] a=pop(cpu,4,4);err(cpu,writeIconFile(a[0],a[1]));return true;} // WriteIconFile(family, spec)
			default:return false;
		}
	}

	// ------------------------------------------------------------------ finding icons

	/** kSystemIconsCreator types with a System icon family (Icons.h resource IDs). */
	private static final Map<Integer,Integer> SYSTEM_IDS=new HashMap<Integer,Integer>();
	private static void sys(String type,int id){SYSTEM_IDS.put(fourcc(type),id);}
	static{
		sys("docu",-4000);sys("sdoc",-3985);sys("edtf",-3989);sys("APPL",-3996);sys("APPD",-3991);sys("fldr",-3999);sys("prvf",-3994);
		sys("flpy",-3998);sys("trsh",-3993);sys("ramd",-3988);sys("cddr",-3987);sys("desk",-3992);sys("ofld",-3997);sys("hdsk",-3995);
		sys("srvr",-3972);sys("suit",-3970);sys("movr",-3969);sys("pref",-3971);sys("qery",-16506);sys("INIT",-16415);sys("macs",-3983);
		sys("help",-20271);sys("amnu",-3982);sys("strt",-3981);sys("ownd",-3980);sys("dbox",-3979);sys("shfl",-3978);sys("mntd",-3977);
		sys("ctrl",-3976);sys("prnt",-3975);sys("prfÄ",-3974);sys("extn",-3973);sys("font",-3968);sys("ftrh",-3984);
	}
	/** Folder-like system types: without art of their own they show the generic folder. */
	private static final String FOLDERS="fldr dbox mntd ofld ownd prvf shfl ufld wfld aexÄ amnu apps asup astÄ cmnu ctrD ctrl sdvÄ docs extD extn favs font Ählp intÄ Änet Äloc morÄ prfÄ ppdf Äprd prnt rapp rdoc rsrv Äscr Älib scrÄ shdD shdf spki strD strt macD macs Ätex appr utiÄ fvoc prof issf usrÄ desk";
	private static final String DISKS="flpy hdsk cddr ramd rmov worm pcmc srvr";
	private static final String APPLICATIONS="APPL APPC APPD sdev";
	private static int fallbackId(int type){
		String t=ResourceFile68k.typeName(type);
		if(FOLDERS.contains(t))return -3999;
		if(DISKS.contains(t))return -3995;
		if(APPLICATIONS.contains(t))return type==fourcc("APPD")?-3991:-3996;
		return -4000;
	}

	/** GetIconRef: a registered icon, else a System icon ('macs'), else the desktop database's icon of that creator and type, else a generic one. */
	private Ref iconRef(int vRefNum,final int creator,final int type) throws M68kException {
		Ref reg=registry.get(key(creator,type));
		if(reg!=null&&refs.containsKey(reg.address)){reg.owners++;return reg;}
		String cacheKey=ResourceFile68k.typeName(creator)+"/"+ResourceFile68k.typeName(type);
		if(creator==SYSTEM_ICONS_CREATOR)return cached(cacheKey,creator,type,()->systemMembers(type));
		return cached(cacheKey,creator,type,()->{
			Map<Integer,byte[]> m=desktopMembers(vRefNum,creator,type);
			if(m==null||m.isEmpty())m=systemMembers(type);
			return m;});
	}

	/** The System's icon for a kSystemIconsCreator type (the generic document for a type without art). */
	private Map<Integer,byte[]> systemMembers(int type) throws M68kException {
		Map<Integer,byte[]> m=new HashMap<Integer,byte[]>();
		String t=ResourceFile68k.typeName(type);
		if(t.equals("note")||t.equals("caut")||t.equals("stop")){ // the alert icons: the System's 'ICON' 1, 2, 0 with a mask traced around them
			byte[] icon=resource(fourcc("ICON"),t.equals("stop")?0:t.equals("note")?1:2);
			if(icon!=null&&icon.length>=128){m.put(ICN_MASK,withMask(icon));return m;}
		}
		int file=t.equals("FNDR")?fourcc("FNDR"):t.equals("zsys")||t.equals("ZSYS")?fourcc("ZSYS"):t.equals("FFIL")||t.equals("ffil")?fourcc("ffil"):t.equals("dfil")?fourcc("dfil"):0;
		if(file!=0){for(int c:new int[]{ICN_MASK,ICL4,ICL8,ICS_MASK,ICS4,ICS8}){byte[] b=SystemIcons68k.systemFileIcon(file,c);if(b!=null)m.put(c,b);}if(!m.isEmpty())return m;}
		Integer id=SYSTEM_IDS.get(type);
		if(id!=null)m=resourceMembers(id);
		if(m.isEmpty())m=resourceMembers(fallbackId(type));
		if(m.isEmpty()&&fallbackId(type)!=-4000)m=resourceMembers(-4000);
		return m;
	}
	private byte[] resource(int type,int id) throws M68kException {
		int h=tb.resources.detachedResourceQuietly(type,id);if(h==0)return null;
		try{return tb.api.handleContents(h);}finally{tb.api.disposeHandleAndFree(h);}
	}
	/** The classic members of the icon family with ID {@code id} in the resource chain. */
	private Map<Integer,byte[]> resourceMembers(int id) throws M68kException {
		Map<Integer,byte[]> m=new HashMap<Integer,byte[]>();
		for(int type:CLASSIC){byte[] b=resource(type,id);if(b!=null&&b.length>0)m.put(type,b);}
		return m;
	}
	/** An 'ICON' as an 'ICN#': the picture and a mask that fills its outline (what lies outside it stays transparent). */
	private static byte[] withMask(byte[] icon){
		boolean[] outside=new boolean[32*32];java.util.ArrayDeque<Integer> todo=new java.util.ArrayDeque<Integer>();
		for(int i=0;i<32;i++){todo.add(i);todo.add(31*32+i);todo.add(i*32);todo.add(i*32+31);}
		while(!todo.isEmpty()){int p=todo.poll();int x=p%32,y=p/32;if(outside[p]||(icon[y*4+x/8]&(0x80>>>(x&7)))!=0)continue;outside[p]=true;
			if(x>0)todo.add(p-1);if(x<31)todo.add(p+1);if(y>0)todo.add(p-32);if(y<31)todo.add(p+32);}
		byte[] b=java.util.Arrays.copyOf(icon,256);
		for(int p=0;p<1024;p++)if(!outside[p])b[128+p/8]|=(byte)(0x80>>>(p&7));
		return b;
	}
	/** The icons of a creator's bundle through the desktop database (PBDTGetPath + PBDTGetIcon), or null. */
	private Map<Integer,byte[]> desktopMembers(int vRefNum,int creator,int type) throws M68kException {
		if(creator==0||creator==SYSTEM_ICONS_CREATOR)return null;
		int pb=memory.allocate(112,4),buffer=memory.allocate(1024,4);
		try{
			memory.fill(pb,112,0);memory.writeWord(pb+22,vRefNum);
			if(call(new int[]{0x7020,0xa260},0,pb,0)!=NO_ERR)return null; // PBDTGetPath -> ioDTRefNum
			Map<Integer,byte[]> m=new HashMap<Integer,byte[]>();
			int[] kinds={0,ICN_MASK,ICL4,ICL8,ICS_MASK,ICS4,ICS8};
			for(int kind=1;kind<=6;kind++){
				memory.writeLong(pb+32,buffer);memory.writeLong(pb+36,1024);memory.writeByte(pb+45,kind);memory.writeLong(pb+52,creator);memory.writeLong(pb+56,type);memory.writeLong(pb+40,0);
				if(call(new int[]{0x7023,0xa260},0,pb,0)!=NO_ERR)continue; // PBDTGetIcon
				int n=memory.readLong(pb+40);if(n>0&&n<=1024)m.put(kinds[kind],memory.readBytes(buffer,n));
			}
			return m;
		}finally{memory.free(buffer);memory.free(pb);}
	}

	/** Thrown by {@link #fromFile} with the File Manager's error. */
	private static final class FileError extends Exception {final int code;FileError(int code){super(null,null,false,false);this.code=code;}}
	/**
	 * GetIconRefFromFile: PBGetCatInfo tells a folder from a file and gives the Finder information; a custom icon (kHasCustomIcon)
	 * comes from the file's resource fork - a folder's from its "Icon\r" file - then special folders and the volume's root,
	 * then the type and creator (an application shows its own bundle icon).
	 */
	private Ref fromFile(int spec,int[] label) throws M68kException,FileError {
		if(spec==0)throw new FileError(PARAM_ERR);
		int vRefNum=(short)memory.readWord(spec),parent=memory.readLong(spec+2);
		int pb=memory.allocate(112,4),name=memory.allocate(64,2);
		try{
			memory.fill(pb,112,0);memory.copy(spec+6,name,64);
			memory.writeLong(pb+18,name);memory.writeWord(pb+22,vRefNum);memory.writeLong(pb+48,parent);
			memory.writeWord(pb+28,memory.readByte(name)==0?-1:0); // no name: the directory itself
			int e=call(new int[]{0x7009,0xa260},0,pb,0); // PBGetCatInfo
			if(e!=NO_ERR)throw new FileError(e);
			boolean folder=(memory.readByte(pb+30)&0x10)!=0;int flags=memory.readWord(pb+40);
			label[0]=flags>>>1&7;
			boolean custom=(flags&0x0400)!=0&&customIconsEnabled(vRefNum);
			File host=null;try{host=tb.files.fileForSpec(spec);}catch(IOException ignored){}
			if(host!=null&&GLauncher.isHostProgram(host)){final File f=host;Ref r=cached("host:"+f.getPath(),0,0,()->hostIconMembers(f));if(r!=null)return r;} // a program of the host (dropped on DragThing): its own icon
			if(folder){
				int dirId=memory.readLong(pb+48);
				if(custom&&host!=null){File iconFile=FinderIcons68k.customIconFile(host);if(iconFile!=null){final File f=iconFile;Ref r=cached("file:"+f.getPath(),0,0,()->forkMembers(f,CUSTOM_ICON_ID));if(r!=null)return r;}}
				return iconRef(vRefNum,SYSTEM_ICONS_CREATOR,dirId==2?fourcc("hdsk"):specialFolder(vRefNum,dirId));
			}
			int type=memory.readLong(pb+32),creator=memory.readLong(pb+36);
			if(custom&&host!=null){final File f=host;Ref r=cached("file:"+f.getPath(),creator,type,()->forkMembers(f,CUSTOM_ICON_ID));if(r!=null)return r;}
			if((type==fourcc("APPL")||type==fourcc("APPC")||type==fourcc("appe"))&&host!=null){ // a program's own icon from its bundle, even when another copy has the creator
				final File f=host;final int t=type;
				Ref r=cached("app:"+f.getPath(),creator,type,()->{Map<Integer,byte[]> m=new HashMap<Integer,byte[]>();
					for(int c:new int[]{ICN_MASK,ICL4,ICL8,ICS_MASK,ICS4,ICS8}){byte[] b=FinderIcons68k.bundleIcon(f,t,c);if(b!=null)m.put(c,b);}return m;});
				if(r!=null)return r;
			}
			return iconRef(vRefNum,creator,type);
		}finally{memory.free(name);memory.free(pb);}
	}
	/**
	 * The icon the host shows for one of its programs (a Mac OS X application, a Windows program), at 16, 32 and 128 pixels:
	 * 32-bit data with 8-bit masks, and 8-bit and black-and-white members (with their masks) for classic drawing.
	 */
	private static Map<Integer,byte[]> hostIconMembers(File f){
		Map<Integer,byte[]> m=new HashMap<Integer,byte[]>();
		try{
			BufferedImage src=hostIconImage(f);if(src==null)return m;
			for(int size:new int[]{16,32,128}){
				BufferedImage img=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=img.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
				Image scaled=src.getWidth()>size*2?src.getScaledInstance(size,size,Image.SCALE_AREA_AVERAGING):src; // smooth down-scaling from 256 or 512 pixels
				g.drawImage(scaled,0,0,size,size,null);g.dispose();
				if(size==128){byte[] d=encode32(img),it=new byte[d.length+4];System.arraycopy(d,0,it,4,d.length);m.put(IT32,it);m.put(T8MK,alpha8(img));continue;}
				boolean big=size==32;
				m.put(big?IL32:IS32,encode32(img));m.put(big?L8MK:S8MK,alpha8(img));m.put(big?ICL8:ICS8,indexed8(img));
				byte[] bits=HCResourceExport.monoBits(img,size,size,false),mask=HCResourceExport.monoBits(img,size,size,true),both=new byte[bits.length*2];
				System.arraycopy(bits,0,both,0,bits.length);System.arraycopy(mask,0,both,bits.length,mask.length);m.put(big?ICN_MASK:ICS_MASK,both);
			}
		}catch(Throwable t){if(Mac68kApi.TRACE)System.err.println("[68K] no host icon for "+f+": "+t);}
		return m;
	}
	/** The host's picture of a program, as large as found: a Mac OS X application's .icns (its PNG members), else the icon the host's file view gives. */
	private static BufferedImage hostIconImage(File f){
		if(f.isDirectory()){BufferedImage b=bundleIcns(f);if(Mac68kApi.TRACE)System.err.println("[68K] host icon for "+f+": "+(b!=null?"its .icns, "+b.getWidth()+" pixels":"no .icns"));if(b!=null)return b;}
		javax.swing.Icon icon=javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon(f,256,256);
		if(icon==null||icon.getIconWidth()<=0||icon.getIconHeight()<=0)return null;
		BufferedImage at=new BufferedImage(icon.getIconWidth(),icon.getIconHeight(),BufferedImage.TYPE_INT_ARGB);Graphics2D g=at.createGraphics();
		if(icon instanceof javax.swing.ImageIcon){Image img=((javax.swing.ImageIcon)icon).getImage();g.drawImage(img,0,0,at.getWidth(),at.getHeight(),null);}else icon.paintIcon(null,g,0,0);
		g.dispose();return at;
	}
	/** The largest PNG member (up to 512 pixels) of an application bundle's icon file: CFBundleIconFile of Contents/Info.plist, else AppIcon.icns or the only .icns. */
	private static BufferedImage bundleIcns(File app){
		File resources=new File(new File(app,"Contents"),"Resources");if(!resources.isDirectory())return null;
		File icns=null;
		try{String plist=new String(java.nio.file.Files.readAllBytes(new File(new File(app,"Contents"),"Info.plist").toPath()),java.nio.charset.StandardCharsets.UTF_8);
			java.util.regex.Matcher mt=java.util.regex.Pattern.compile("<key>CFBundleIconFile</key>\\s*<string>([^<]+)</string>").matcher(plist);
			if(mt.find()){String name=mt.group(1).trim();File c=new File(resources,name.endsWith(".icns")?name:name+".icns");if(c.isFile())icns=c;}}
		catch(IOException|RuntimeException ignored){}
		if(icns==null){File c=new File(resources,"AppIcon.icns");if(c.isFile())icns=c;}
		if(icns==null){File[] all=resources.listFiles((d,n)->n.toLowerCase(java.util.Locale.ROOT).endsWith(".icns"));if(all!=null&&all.length==1)icns=all[0];}
		if(icns==null)return null;
		try{byte[] b=java.nio.file.Files.readAllBytes(icns.toPath());if(b.length<8||MacApp68k.u4(b,0)!=ICON_FAMILY)return null;
			BufferedImage best=null;
			for(int at=8;at+8<=b.length;){int len=MacApp68k.u4(b,at+4);if(len<8||at+len>b.length)break;
				if(len>16&&(b[at+8]&255)==0x89&&b[at+9]=='P'&&b[at+10]=='N'&&b[at+11]=='G'){
					BufferedImage img=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b,at+8,len-8));
					if(img!=null&&img.getWidth()<=512&&(best==null||img.getWidth()>best.getWidth()))best=img;}
				at+=len;}
			return best;}
		catch(IOException|RuntimeException e){return null;}
	}
	/** Each pixel as the nearest colour of the standard 256-colour palette (transparent pixels white). */
	private static byte[] indexed8(BufferedImage img){
		int w=img.getWidth(),h=img.getHeight();byte[] out=new byte[w*h];int[] pal=new int[256];for(int i=0;i<256;i++)pal[i]=MacSystemPalette.rgb(i);
		Map<Integer,Integer> seen=new HashMap<Integer,Integer>();
		for(int i=0;i<out.length;i++){int argb=img.getRGB(i%w,i/w);int rgb=(argb>>>24)<128?0xffffff:argb&0xffffff;
			Integer hit=seen.get(rgb);
			if(hit==null){int best=0;long bestD=Long.MAX_VALUE;for(int c=0;c<256;c++){int p=pal[c];long dr=(p>>16&255)-(rgb>>16&255),dg=(p>>8&255)-(rgb>>8&255),db=(p&255)-(rgb&255);long d=dr*dr*3+dg*dg*4+db*db*2;if(d<bestD){bestD=d;best=c;}}hit=best;seen.put(rgb,hit);}
			out[i]=(byte)(int)hit;}
		return out;
	}
	/** Special folders (FindFolder) show their own icons; any other folder the generic one. */
	private int specialFolder(int vRefNum,int dirId) throws M68kException {
		if(dirId==2)return fourcc("fldr");
		String[][] kinds={{"macs","macs"},{"extn","extn"},{"ctrl","ctrl"},{"pref","prfÄ"},{"font","font"},{"amnu","amnu"},{"strt","strt"},{"shdf","shdf"},{"trsh","trsh"},{"desk","desk"}};
		int out=memory.allocate(8,4);
		try{
			for(String[] k:kinds){
				memory.fill(out,8,0);
				int e=call(new int[]{0x7000,0xa823},0,0,2,vRefNum,2,fourcc(k[0]),4,0,1,out,4,out+4,4); // FindFolder(vRefNum, type, false, *vRef, *dirID)
				if(e==NO_ERR&&memory.readLong(out+4)==dirId)return fourcc(k[1]);
			}
		}finally{memory.free(out);}
		return fourcc("fldr");
	}
	private boolean customIconsEnabled(int vRefNum){Boolean b=customIcons.get(vRefNum);return b==null||b;}

	/** An icon family from a host file's resource fork: its 'icns' of that ID first, else the classic members. */
	private Map<Integer,byte[]> forkMembers(File file,int id){
		Map<Integer,byte[]> m=new HashMap<Integer,byte[]>();
		if(file==null||!Sandbox.allowFile(file,Sandbox.Access.READ,tb.name))return m;
		try{
			byte[] fork=ResourceFile68k.readForkBytes(file,null);if(fork==null||fork.length<16)return m;
			byte[] icns=FinderIcons68k.byId(fork,ICON_FAMILY,id);
			if(icns!=null){Map<Integer,byte[]> f=parseFamily(icns);if(f!=null&&!f.isEmpty())return f;}
			for(int type:CLASSIC){byte[] b=FinderIcons68k.byId(fork,type,id);if(b!=null)m.put(type,b);}
		}catch(IOException|RuntimeException ignored){}
		return m;
	}
	private File hostFile(int spec){try{return tb.files.fileForSpec(spec);}catch(IOException e){return null;}}
	private int copySpec(int spec) throws M68kCpuException {int c=memory.allocate(70,2);memory.copy(spec,c,70);return c;}
	/** RegisterIconRefFromResource / OverrideIconRefFromResource: the family {@code id} of a file's resource fork. */
	private Map<Integer,byte[]> resourceFileFamily(int spec,int id,int[] e){
		File f=spec==0?null:hostFile(spec);
		if(f==null||!f.isFile()){e[0]=FNF_ERR;return null;}
		return forkMembers(f,id);
	}
	private Ref register(int creator,int type,Map<Integer,byte[]> m,Loader loader) throws M68kCpuException {
		Ref r=registry.get(key(creator,type));
		if(r!=null&&refs.containsKey(r.address)){r.members=m;r.owners++;return r;} // registering again replaces the pictures
		r=newRef(creator,type,m);r.registered=true;r.loader=loader;registry.put(key(creator,type),r);return r;
	}
	/** GetIconRefVariant: a folder's 'open' variant is the open folder; any other variant is the icon itself, plotted with a transform. */
	private int variant(int ref,int variant,int transformOut) throws M68kException {
		Ref r=refs.get(ref);if(r==null)return 0;
		int transform=0;Ref out=r;
		if(variant==fourcc("open")||variant==fourcc("odrp")){
			if(r.creator==SYSTEM_ICONS_CREATOR&&FOLDERS.contains(ResourceFile68k.typeName(r.type))){Ref open=iconRef(0,SYSTEM_ICONS_CREATOR,fourcc("ofld"));if(open!=null){out=open;open.owners--;}} // not an extra owner: the caller did not acquire it
			else transform=3; // kTransformOpen
		}
		if(transformOut!=0)memory.writeWord(transformOut,transform);
		return out.address;
	}

	// ------------------------------------------------------------------ icon families ('icns')

	/** Parses an 'icns' family into its elements; null when it is not one. */
	static Map<Integer,byte[]> parseFamily(byte[] b){
		if(b==null||b.length<8||u4(b,0)!=ICON_FAMILY)return null;
		Map<Integer,byte[]> m=new LinkedHashMap<Integer,byte[]>();
		int end=Math.min(b.length,Math.max(8,u4(b,4))),at=8;
		while(at+8<=end){int type=u4(b,at),size=u4(b,at+4);if(size<8||at+size>end)break;m.put(type,java.util.Arrays.copyOfRange(b,at+8,at+size));at+=size;}
		return m;
	}
	static byte[] buildFamily(Map<Integer,byte[]> m){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		int total=8;for(byte[] v:m.values())total+=8+v.length;
		put4(out,ICON_FAMILY);put4(out,total);
		for(Map.Entry<Integer,byte[]> e:m.entrySet()){put4(out,e.getKey());put4(out,8+e.getValue().length);out.write(e.getValue(),0,e.getValue().length);}
		return out.toByteArray();
	}
	private static int u4(byte[] b,int o){return (b[o]&255)<<24|(b[o+1]&255)<<16|(b[o+2]&255)<<8|(b[o+3]&255);}
	private static void put4(java.io.ByteArrayOutputStream o,int v){o.write(v>>>24);o.write(v>>>16);o.write(v>>>8);o.write(v);}
	private static boolean wanted(int which,int bit){return which==-1||(which&bit)!=0;}

	/** IconRefToIconFamily: the members the selector names; 32-bit data and 8-bit masks are made from the classic pictures when the icon has none. */
	private byte[] family(Ref r,int which){
		Map<Integer,byte[]> src=r.background!=null&&r.data().isEmpty()?r.background.data():r.data();
		Map<Integer,byte[]> out=new LinkedHashMap<Integer,byte[]>();
		for(int[] el:ELEMENTS){
			if(!wanted(which,el[1])||el[1]==0&&which!=-1)continue;
			byte[] b=src.get(el[0]);
			if(b==null&&(el[0]==IL32||el[0]==L8MK||el[0]==IS32||el[0]==S8MK)){BufferedImage p=image(r,el[0]==IL32||el[0]==L8MK?32:16);if(p!=null)b=el[0]==IL32||el[0]==IS32?encode32(p):alpha8(p);}
			if(b!=null)out.put(el[0],b);
		}
		return buildFamily(out);
	}
	/** The selector bits of the members an IconRef has (8-bit masks and 32-bit data count as made from the others). */
	private int available(Ref r){
		Map<Integer,byte[]> m=r.background!=null&&r.data().isEmpty()?r.background.data():r.data();
		int bits=0;for(int[] el:ELEMENTS)if(m.containsKey(el[0]))bits|=el[1];
		if((bits&0x7)!=0)bits|=0x18;if((bits&0x700)!=0)bits|=0x1800;
		return bits;
	}
	private int getFamilyData(int family,int type,int h) throws M68kException {
		if(family==0||h==0)return PARAM_ERR;
		Map<Integer,byte[]> m=parseFamily(tb.api.handleContents(family));if(m==null)return PARAM_ERR;
		byte[] b=m.get(type);if(b==null)b=new byte[0]; // no such member: the Handle becomes empty
		if(setHandleSize(h,b.length)!=NO_ERR)return -108;
		if(b.length>0)memory.load(memory.readLong(h),b);
		return NO_ERR;
	}
	private int setFamilyData(int family,int type,int h) throws M68kException {
		if(family==0||h==0)return PARAM_ERR;
		Map<Integer,byte[]> m=parseFamily(tb.api.handleContents(family));if(m==null)return PARAM_ERR;
		if(type==fourcc("PICT"))return PARAM_ERR; // no picture compression here
		m.put(type,tb.api.handleContents(h));
		Map<Integer,byte[]> ordered=new LinkedHashMap<Integer,byte[]>();for(int[] el:ELEMENTS)if(m.containsKey(el[0]))ordered.put(el[0],m.remove(el[0]));ordered.putAll(m);
		byte[] b=buildFamily(ordered);
		if(setHandleSize(family,b.length)!=NO_ERR)return -108;
		memory.load(memory.readLong(family),b);return NO_ERR;
	}
	/** IconFamilyToIconSuite: a new suite (NewIconSuite) holding copies of the classic members. */
	private int familyToSuite(int family,int which,int suitePtr) throws M68kException {
		if(family==0||suitePtr==0)return PARAM_ERR;
		Map<Integer,byte[]> m=parseFamily(tb.api.handleContents(family));if(m==null)return PARAM_ERR;
		int e=call(new int[]{0x303c,0x0207,0xabc9},0,0,2,suitePtr,4); // NewIconSuite(&suite)
		if(e!=NO_ERR)return e;
		int suite=memory.readLong(suitePtr);
		for(int[] el:ELEMENTS){
			if(!isClassic(el[0])||!wanted(which,el[1]))continue;byte[] b=m.get(el[0]);if(b==null)continue;
			call(new int[]{0x303c,0x0608,0xabc9},0,0,2,tb.api.createHandle(b),4,suite,4,el[0],4); // AddIconToSuite(data, suite, type)
		}
		return NO_ERR;
	}
	/** IconSuiteToIconFamily: a new family of the suite's classic members (GetIconFromSuite). */
	private int suiteToFamily(int suite,int which,int familyPtr) throws M68kException {
		if(suite==0||familyPtr==0)return PARAM_ERR;
		Map<Integer,byte[]> m=new LinkedHashMap<Integer,byte[]>();int slot=memory.allocate(4,4);
		try{
			for(int[] el:ELEMENTS){
				if(!isClassic(el[0])||!wanted(which,el[1]))continue;memory.writeLong(slot,0);
				if(call(new int[]{0x303c,0x0609,0xabc9},0,0,2,slot,4,suite,4,el[0],4)!=NO_ERR)continue; // GetIconFromSuite(&data, suite, type)
				int h=memory.readLong(slot);byte[] b=h==0?null:tb.api.handleContents(h);if(b!=null&&b.length>0)m.put(el[0],b);
			}
		}finally{memory.free(slot);}
		memory.writeLong(familyPtr,tb.api.createHandle(buildFamily(m)));
		return NO_ERR;
	}
	private static boolean isClassic(int type){for(int c:CLASSIC)if(c==type)return true;return false;}
	private int setHandleSize(int h,int size) throws M68kException {return call(new int[]{0xa024},size,h,0);}

	/** ReadIconFile: the data fork of an .icns file. */
	private byte[] readIconFile(int spec,int[] e){
		File f=spec==0?null:hostFile(spec);
		if(f==null||!f.isFile()){e[0]=FNF_ERR;return null;}
		if(!Sandbox.allowFile(f,Sandbox.Access.READ,tb.name)){e[0]=-54;return null;} // permErr
		try{byte[] b=java.nio.file.Files.readAllBytes(f.toPath());if(parseFamily(b)==null){e[0]=PARAM_ERR;return null;}return b;}
		catch(IOException x){e[0]=IO_ERR;return null;}
	}
	private int writeIconFile(int family,int spec) throws M68kCpuException {
		byte[] b=family==0?null:tb.api.handleContents(family);if(b==null||parseFamily(b)==null)return PARAM_ERR;
		File f=spec==0?null:hostFile(spec);if(f==null||f.isDirectory())return FNF_ERR;
		if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,tb.name))return -54;
		try{java.nio.file.Files.write(f.toPath(),java.util.Arrays.copyOf(b,Math.min(b.length,Math.max(8,u4(b,4)))));return NO_ERR;}catch(IOException x){return IO_ERR;}
	}

	// ------------------------------------------------------------------ pictures

	private static final int[] COLORS4={0xffffff,0xfcf305,0xff6402,0xdd0806,0xf20884,0x4600a5,0x0000d4,0x02abea,0x1fb714,0x006411,0x562c05,0x90713a,0xc0c0c0,0x808080,0x404040,0x000000};
	/** The Finder's default label colours (kTransformLabel1..7). */
	private static final int[] LABELS={0,0xff6600,0xdd0806,0xf20884,0x02abea,0x0000d4,0x006411,0x562c05};

	/** The icon at one of its native sizes (128, 48, 32, 16, or 12 for the 16 x 12 mini icons), deepest data first; null when absent (also StackFileIcon). */
	static BufferedImage picture(Map<Integer,byte[]> m,int size){
		int w=size==12?16:size,h=size;
		int d32,mask8,d8,d4,d1;
		switch(size){
			case 128:d32=IT32;mask8=T8MK;d8=d4=d1=0;break;
			case 48:d32=IH32;mask8=H8MK;d8=ICH8;d4=ICH4;d1=ICH_MASK;break;
			case 32:d32=IL32;mask8=L8MK;d8=ICL8;d4=ICL4;d1=ICN_MASK;break;
			case 16:d32=IS32;mask8=S8MK;d8=ICS8;d4=ICS4;d1=ICS_MASK;break;
			default:d32=0;mask8=0;d8=ICM8;d4=ICM4;d1=ICM_MASK;break;
		}
		int n=w*h,rowBytes=w/8;
		byte[] one=d1==0?null:m.get(d1);if(one!=null&&one.length<n/8)one=null;
		byte[] alpha=mask8==0?null:m.get(mask8);if(alpha!=null&&alpha.length<n)alpha=null;
		BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		int[] rgb=null;
		byte[] b32=d32==0?null:m.get(d32);if(b32!=null)rgb=decode32(b32,n,d32==IT32);
		byte[] b8=d8==0?null:m.get(d8),b4=d4==0?null:m.get(d4);
		if(rgb==null&&b8!=null&&b8.length>=n){rgb=new int[n];for(int i=0;i<n;i++)rgb[i]=MacSystemPalette.rgb(b8[i]&255);}
		if(rgb==null&&b4!=null&&b4.length>=n/2){rgb=new int[n];for(int i=0;i<n;i++)rgb[i]=COLORS4[(b4[i/2]>>>((i&1)==0?4:0))&15];}
		if(rgb==null&&one!=null){rgb=new int[n];for(int i=0;i<n;i++)rgb[i]=(one[i/8]&(0x80>>>(i&7)))!=0?0:0xffffff;}
		if(rgb==null)return null;
		boolean hasMask=one!=null&&one.length>=n/4;
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;
			int a=alpha!=null?alpha[i]&255:hasMask?((one[n/8+y*rowBytes+x/8]&(0x80>>>(x&7)))!=0?255:0):255;
			img.setRGB(x,y,a<<24|rgb[i]&0xffffff);}
		return img;
	}
	/** 32-bit icon data: three channel planes, each run-length packed as in 'icns' (or raw ARGB). */
	private static int[] decode32(byte[] b,int n,boolean skip4){
		if(b.length==n*4){int[] out=new int[n];for(int i=0;i<n;i++)out[i]=(b[4*i+1]&255)<<16|(b[4*i+2]&255)<<8|(b[4*i+3]&255);return out;}
		int[] out=new int[n];int at=skip4?4:0;
		for(int c=0;c<3;c++){int i=0;
			while(i<n&&at<b.length){int k=b[at++]&255;
				if(k<0x80){for(int j=0;j<=k&&i<n&&at<b.length;j++)out[i++]|=(b[at++]&255)<<(16-8*c);}
				else{int v=at<b.length?b[at++]&255:0;for(int j=0;j<k-125&&i<n;j++)out[i++]|=v<<(16-8*c);}}
			if(i<n)return null;}
		return out;
	}
	private static byte[] encode32(BufferedImage p){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int n=p.getWidth()*p.getHeight();
		for(int c=0;c<3;c++)for(int i=0;i<n;i+=128){int k=Math.min(128,n-i);out.write(k-1);for(int j=0;j<k;j++){int v=p.getRGB((i+j)%p.getWidth(),(i+j)/p.getWidth());out.write(v>>>(16-8*c)&255);}}
		return out.toByteArray();
	}
	private static byte[] alpha8(BufferedImage p){byte[] b=new byte[p.getWidth()*p.getHeight()];for(int i=0;i<b.length;i++)b[i]=(byte)(p.getRGB(i%p.getWidth(),i/p.getWidth())>>>24);return b;}

	/** The IconRef's picture closest to {@code target} pixels (a composite: its foreground over its background). */
	private BufferedImage image(Ref r,int target){
		if(r.background!=null&&r.foreground!=null&&r.override==null){
			BufferedImage bg=image(r.background,target),fg=image(r.foreground,target);
			if(bg==null)return fg;if(fg==null)return bg;
			int s=Math.max(bg.getWidth(),fg.getWidth());BufferedImage out=new BufferedImage(s,s,BufferedImage.TYPE_INT_ARGB);
			Graphics2D g=out.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(bg,0,0,s,s,null);g.drawImage(fg,0,0,s,s,null);}finally{g.dispose();}
			return out;
		}
		Map<Integer,byte[]> m=r.data();
		int[] order=target>=128?new int[]{128,48,32,16}:target>=48?new int[]{48,32,128,16}:target>16?new int[]{32,48,16,128}:target>12?new int[]{16,32,12,48}:new int[]{12,16,32};
		for(int s:order){BufferedImage p=picture(m,s);if(p!=null)return p;}
		return null;
	}
	/** The square the icon occupies in {@code rect} for an IconAlignmentType: {top, left, size}. */
	private static int[] square(int top,int left,int bottom,int right,int align){
		int w=right-left,h=bottom-top,s=Math.max(0,Math.min(w,h));
		int v=align&3,hz=align&0xc;
		int y=v==2?top:v==3?bottom-s:top+(h-s)/2,x=hz==8?left:hz==0xc?right-s:left+(w-s)/2;
		return new int[]{y,x,s};
	}
	/** The picture scaled to the square, with an IconTransformType applied. */
	private BufferedImage rendered(Ref r,int size,int transform){
		BufferedImage p=image(r,size);if(p==null||size<=0)return null;
		BufferedImage out=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=out.createGraphics();
		try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,p.getWidth()==size?RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR:RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			int ph=p.getHeight()*size/p.getWidth();g.drawImage(p,0,(size-ph)/2,size,ph,null);}finally{g.dispose();} // mini icons (16 x 12) keep their proportions
		int state=transform&0xff,label=transform>>>8&7;boolean selected=(transform&0x4000)!=0;
		if(state==0&&label==0&&!selected)return out;
		for(int y=0;y<size;y++)for(int x=0;x<size;x++){int argb=out.getRGB(x,y),a=argb>>>24;if(a==0)continue;
			int cr=argb>>>16&255,cg=argb>>>8&255,cb=argb&255;
			if(label!=0){int l=LABELS[label];cr=cr*(l>>>16&255)/255;cg=cg*(l>>>8&255)/255;cb=cb*(l&255)/255;} // the label colour tints the icon as the Finder's does
			if(state==3){int lum=(cr*299+cg*587+cb*114)/1000;cr=cg=cb=lum<0x60?0x33:((x+y)&1)==0?0x66:0xbb;} // kTransformOpen: a grey dither
			else if(state==1||state==2){cr=(cr+2*255)/3;cg=(cg+2*255)/3;cb=(cb+2*255)/3;} // disabled / offline: faded
			if(selected){cr/=2;cg/=2;cb/=2;}
			out.setRGB(x,y,a<<24|cr<<16|cg<<8|cb);}
		return out;
	}
	private int[] readRect(int rect) throws M68kCpuException {return new int[]{(short)memory.readWord(rect),(short)memory.readWord(rect+2),(short)memory.readWord(rect+4),(short)memory.readWord(rect+6)};}

	/** PlotIconRef into the current port. */
	int plot(int rect,int align,int transform,int ref) throws M68kException {
		Ref r=refs.get(ref);if(r==null)return INVALID_ICON_REF_ERR;if(rect==0)return PARAM_ERR;
		int[] b=readRect(rect);int[] sq=square(b[0],b[1],b[2],b[3],align);
		BufferedImage img=rendered(r,sq[2],transform);if(img==null)return NO_ICON_DATA_AVAILABLE_ERR;
		tb.quickDraw.drawImage(img,sq[0],sq[1],sq[0]+sq[2],sq[1]+sq[2]);tb.quickDraw.flushSurfaces();
		return NO_ERR;
	}
	/** PtInIconRef / RectInIconRef: whether the point (rectangle) touches the icon's mask. */
	private boolean hit(int test,boolean isRect,int rect,int align,int ref) throws M68kCpuException {
		Ref r=refs.get(ref);if(r==null||test==0||rect==0)return false;
		int[] b=readRect(rect);int[] sq=square(b[0],b[1],b[2],b[3],align);
		BufferedImage img=rendered(r,sq[2],0);if(img==null)return false;
		int t,l,bo,ri;
		if(isRect){int[] q=readRect(test);t=q[0];l=q[1];bo=q[2];ri=q[3];}else{t=(short)memory.readWord(test);l=(short)memory.readWord(test+2);bo=t+1;ri=l+1;}
		for(int y=Math.max(t,sq[0]);y<Math.min(bo,sq[0]+sq[2]);y++)for(int x=Math.max(l,sq[1]);x<Math.min(ri,sq[1]+sq[2]);x++)
			if((img.getRGB(x-sq[1],y-sq[0])>>>24)!=0)return true;
		return false;
	}
	/** IconRefToRgn: the mask's outline as a region. */
	private int toRegion(int rgn,int rect,int align,int ref) throws M68kCpuException {
		Ref r=refs.get(ref);if(r==null)return INVALID_ICON_REF_ERR;if(rgn==0||rect==0)return PARAM_ERR;
		int[] b=readRect(rect);int[] sq=square(b[0],b[1],b[2],b[3],align);
		BufferedImage img=rendered(r,sq[2],0);
		tb.api.quickDraw().setRegion(rgn,img==null?QdRegion.empty():regionOf(img,sq[0],sq[1]));
		return NO_ERR;
	}
	/** The region of an image's non-transparent pixels placed at (top, left). */
	static QdRegion regionOf(BufferedImage img,int top,int left){
		List<int[]> rows=new ArrayList<int[]>();
		for(int y=0;y<img.getHeight();y++){List<Integer> xs=new ArrayList<Integer>();boolean in=false;
			for(int x=0;x<=img.getWidth();x++){boolean on=x<img.getWidth()&&(img.getRGB(x,y)>>>24)!=0;if(on!=in){xs.add(left+x);in=on;}}
			int[] a=new int[xs.size()];for(int i=0;i<a.length;i++)a[i]=xs.get(i);rows.add(a);}
		return QdRegion.fromRows(top,rows);
	}
	private boolean maskEmpty(Ref r){
		BufferedImage img=image(r,32);if(img==null)return true;
		for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++)if((img.getRGB(x,y)>>>24)!=0)return false;
		return true;
	}

	// ------------------------------------------------------------------ calling the Toolbox from here

	private final Map<String,Integer> stubs=new HashMap<String,Integer>();
	/**
	 * Runs 68K {@code words} (a trap and its selector set-up) with D0/A0 given and Pascal arguments (value, size pairs,
	 * left to right; size 1 is a Boolean in a word's high byte) below a {@code resultBytes} result slot. Returns the result
	 * slot, or D0 when there is none. The registers and the stack pointer are as before afterwards.
	 */
	private int call(int[] words,int d0,int a0,int resultBytes,int... valueSize) throws M68kException {
		M68kCpu cpu=tb.cpu;
		String k=java.util.Arrays.toString(words);Integer at=stubs.get(k);
		if(at==null){at=memory.allocate(words.length*2+6,2);for(int i=0;i<words.length;i++)memory.writeWord(at+2*i,words[i]);memory.writeWord(at+2*words.length,0x4ef9);memory.writeLong(at+2*words.length+2,M68kCpu.RETURN_SENTINEL);stubs.put(k,at);}
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sp=cpu.a[7];
		try{
			if(resultBytes>0){cpu.a[7]-=resultBytes==4?4:2;memory.fill(cpu.a[7],resultBytes==4?4:2,0);}
			int slot=cpu.a[7],bytes=0;
			for(int i=0;i+1<valueSize.length;i+=2){int v=valueSize[i],size=valueSize[i+1];
				if(size==4){cpu.a[7]-=4;memory.writeLong(cpu.a[7],v);bytes+=4;}else{cpu.a[7]-=2;memory.writeWord(cpu.a[7],size==1?(v&0xff)<<8:v);bytes+=2;}}
			cpu.d[0]=d0;cpu.a[0]=a0;
			cpu.runUntilSentinel(at,bytes);
			return resultBytes==2?(short)memory.readWord(slot):resultBytes==4?memory.readLong(slot):resultBytes==1?memory.readByte(slot):cpu.d[0];
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=sp;}
	}
}

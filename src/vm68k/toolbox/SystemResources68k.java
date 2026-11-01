/**
 * The synthetic "System" resource file seen by hosted applications: standard
 * cursors, patterns, default icons, international resources, definition
 * procedures and a beep, so programs that ask for them through the Resource
 * Manager find something.
 *
 * Everything here is HyperZebra's own: the record layouts follow Inside
 * Macintosh and the set of types/IDs follows the inventory of a real System
 * file (tests/SystemResourceInventory, SYSTEM_7.5.3_RESOURCES.md), but no
 * code, artwork, sound or text is copied from Apple's files.
 */
final class SystemResources68k {
	/** 'STR ' -8192: the printer the Chooser chose (the driver file's name). */
	static final int CHOSEN_PRINTER=-8192;
	private SystemResources68k(){}

	/** A 'vers' resource for System version {@code v} (BCD $0922 = 9.2.2): final release, region 0, "9.2.2" twice. */
	static byte[] versionResource(int v){
		byte[] text=(((v>>8)&0xff)+"."+((v>>4)&0xf)+"."+(v&0xf)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] r=new byte[6+2*(1+text.length)];r[0]=(byte)(v>>8);r[1]=(byte)v;r[2]=(byte)0x80;
		r[6]=(byte)text.length;System.arraycopy(text,0,r,7,text.length);r[7+text.length]=(byte)text.length;System.arraycopy(text,0,r,8+text.length,text.length);
		return r;
	}
	static ResourceFile68k build(){return build(StackTextEncoding.ROMAN);}
	static ResourceFile68k build(StackTextEncoding encoding){return build(encoding,Toolbox68k.SYSTEM_VERSION);}
	/** @param encoding the program's text encoding: a Japanese program sees a KanjiTalk-like script configuration (itlc/itlb).
	 *  @param version the System version the VM reports (Gestalt sysv): the 'vers' 1 resource says the same. */
	static ResourceFile68k build(StackTextEncoding encoding,int version){
		ResourceFile68k f=new ResourceFile68k(null);
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CURS"),1,"iBeam",0,cursor(new int[]{0x0c60,0x0290,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0080,0x0290,0x0c60},new int[]{0x0c60,0x0ef0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x01c0,0x0ef0,0x0c60},7,7)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CURS"),2,"cross",0,cursor(new int[]{0x0100,0x0100,0x0100,0x0100,0x0100,0x0100,0x0100,0xfffe,0x0100,0x0100,0x0100,0x0100,0x0100,0x0100,0x0100,0},new int[]{0x0380,0x0380,0x0380,0x0380,0x0380,0x0380,0x0380,0xffff,0xffff,0x0380,0x0380,0x0380,0x0380,0x0380,0x0380,0x0380},7,7)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CURS"),3,"plus",0,cursor(new int[]{0x0000,0x0100,0x0100,0x0100,0x0100,0x0100,0x0100,0x7ffc,0x0100,0x0100,0x0100,0x0100,0x0100,0x0100,0,0},new int[]{0x0380,0x0380,0x0380,0x0380,0x0380,0x0380,0x0380,0xfffe,0xfffe,0x0380,0x0380,0x0380,0x0380,0x0380,0x0380,0},7,7)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CURS"),4,"watch",0,watch(0)));
		// The System's animated watch: acur -6078 cycles CURS 4 and -6078..-6072 (the minute hand turning), the frames sysHeap+locked+preload like the real ones.
		for(int i=1;i<8;i++)f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CURS"),-6078+i-1,null,ResourceFile68k.RES_SYS_HEAP|ResourceFile68k.RES_LOCKED|ResourceFile68k.RES_PRELOAD,watch(i)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("acur"),-6078,null,ResourceFile68k.RES_SYS_HEAP|ResourceFile68k.RES_LOCKED|ResourceFile68k.RES_PRELOAD,acur(new int[]{4,-6078,-6077,-6076,-6075,-6074,-6073,-6072})));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CURS"),-5760,"ring",0,cursor(new String[]{
			"      ....      ",
			"    ..####..    ",
			"   .########.   ",
			"  .###....###.  ",
			" .###......###. ",
			" .##........##. ",
			" .##........##. ",
			" .##........##. ",
			" .##........##. ",
			" .##........##. ",
			" .###......###. ",
			"  .###....###.  ",
			"   .########.   ",
			"    ..####..    ",
			"      ....      ",
			"                "},7,8)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("PAT#"),0,null,0,patterns()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("PAT "),16,"gray",0,new byte[]{(byte)0xaa,0x55,(byte)0xaa,0x55,(byte)0xaa,0x55,(byte)0xaa,0x55}));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ppat"),16,null,0,deskPixPat()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ppt#"),0,null,0,pixPatList()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("PAT "),17,"ltGray",0,new byte[]{(byte)0x88,0x22,(byte)0x88,0x22,(byte)0x88,0x22,(byte)0x88,0x22}));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("PAT "),18,"dkGray",0,new byte[]{(byte)0x77,(byte)0xdd,(byte)0x77,(byte)0xdd,(byte)0x77,(byte)0xdd,(byte)0x77,(byte)0xdd}));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ICON"),0,"stop",0,icon(0)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ICON"),1,"note",0,icon(1)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ICON"),2,"caution",0,icon(2)));
		// colour alert icons (Mac OS 8/9 Systems carry them; ADM, Acrobat 5's dialog library, plots its alerts' icons with GetCIcon)
		for(int k=0;k<3;k++)f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("cicn"),k,null,0,alertCicn(k)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("STR "),-16396,null,0,pascal("HyperZebra")));
		// The computer's name Sharing Setup keeps ('STR ' -16413): the PPC Toolbox's own machine (Rinaldi's PPCList: "Node name not found")
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("STR "),-16413,null,0,pascal(Toolbox68k.COMPUTER_NAME)));
		// The owner name Sharing Setup (and the Chooser's "user name") keeps ('STR ' -16096): CFGetUserName answers it, never the host account's name.
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("STR "),-16096,null,0,pascal("HyperZebra")));
		// The machine names by Gestalt 'mach' type (kMachineNameStrID, 'STR#' -16395): gestalt("mach") names, MacType ("Unknown CPU") and
		// SysEnvirons-based externals look the Mac IIvx (48) or Power Macintosh 7200 (108) HyperZebra reports up there (Best of HC Externals)
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("STR#"),MACHINE_NAMES_ID,null,0,machineNames()));
		// The current printer's name, as the Chooser leaves it ('STR ' -8192): PageMaker 6 dims its whole Print dialog without one; MacWrite 2.2
		// then looks for a driver file of that name and, finding none, disables Print (it said "no printing resource" before). The printer is the
		// QuickDraw device of PrintManager68k (wDev $01xx), so no real driver's name is borrowed.
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("STR "),CHOSEN_PRINTER,null,0,pascal("HyperZebra Printer")));
		boolean japanese=encoding==StackTextEncoding.JAPANESE;
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl0"),0,"U.S.",0,itl0(false)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl1"),0,"U.S.",0,itl1(false)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl0"),JAPANESE_ITL,"Japanese",0,itl0(true)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl1"),JAPANESE_ITL,"Japanese",0,itl1(true)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl2"),0,"U.S.",0,itl2()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl2"),JAPANESE_ITL,"Japanese",0,itl2()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl4"),0,"U.S.",0,itl4(false)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itl4"),JAPANESE_ITL,"Japanese",0,itl4(true)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itlb"),0,"Roman",0,itlb(false)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itlb"),1,"Japanese",0,itlb(true)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("itlc"),0,null,0,itlc(japanese)));
		// 'scrn' 0, the Monitors control panel's screen configuration: one main screen. Timer 1.11 / MS Timer's doTimer read its
		// slot for SlotVInstall and refused to run without it ("No scrn resource. Use Monitors Control Panel & reboot.")
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("scrn"),0,null,0,scrn()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("KCHR"),0,"U.S.",0,kchr()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("KCHR"),JAPANESE_ITL+1,"Japanese",0,kchr())); // itlbKeys of the Japanese bundle (the US layout: no JIS keyboard on the host)
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("KCAP"),KeyboardLayout68k.KEYBOARD_TYPE,null,0,KeyboardLayout68k.kcap())); // the keyboard Key Caps draws
		// Definition procedures under the IDs of a real System file: programs look them up (a custom WDEF/CDEF that
		// falls back to the standard one, HyperCard's check of every menu's menuProc, resource-editor tools) and the
		// Window/Control/Menu Managers store their Handles in the records. Windows, controls and menus are drawn
		// natively, so the code only has to be callable: each stub follows the Pascal calling convention of its kind.
		// WDEF 0 and CDEF 0/1 run natively when a program calls them (its own WDEF/CDEF handing standard variants on to them).
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("WDEF"),0,null,DEF_ATTRIBUTES,nativeDefStub(ResourceManager68k.WDEF_SELECTOR)));  // (varCode, WindowPtr, message, param): LONGINT
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("WDEF"),1,null,DEF_ATTRIBUTES,defStub(12,true)));
		for(int id:new int[]{0,1})f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CDEF"),id,null,DEF_ATTRIBUTES,nativeDefStub(ResourceManager68k.CDEF_SELECTOR|id))); // (varCode, ControlHandle, message, param): LONGINT
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CDEF"),63,null,DEF_ATTRIBUTES,defStub(12,true)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("MDEF"),0,null,DEF_ATTRIBUTES,nativeMdefStub())); // (message, MenuHandle, Rect*, Point, whichItem*): sizes and hit tests natively
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("LDEF"),0,null,DEF_ATTRIBUTES,defStub(20,false))); // (message, select, Rect*, Cell, dataOffset, dataLen, ListHandle)
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("LDEF"),ListManager68k.CTB_LDEF,null,DEF_ATTRIBUTES,defStub(20,false))); // the Communications Toolbox's list definition (the List Manager draws text rows for it): Apple Modem Tool checks the System file for it
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("MBDF"),0,null,DEF_ATTRIBUTES,defStub(10,true)));  // (selector, message, param1, param2): LONGINT
		// Generic icon families (document, folder, disks, application, Trash...), drawn by HyperZebra
		SystemIcons68k.addTo(f);
		// Decompressors: the Finder loads its own compressed CODE segments and runs the System's 'dcmp' on them
		for(int id=0;id<=3;id++)f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("dcmp"),id,null,DEF_ATTRIBUTES,dcmpStub(id)));
		// Packages: programs GetResource 'PACK' n to make sure a package is present (some icon editors treat a nil PACK 0,
		// the List Manager, as out of memory). A stub called through its Handle re-enters the native package through its trap.
		int[] packTraps={0xa9e7,0xa9e8,0xa9e9,0xa9ea,0xa9eb,0xa9ec,0xa9ed,0xa9ee,0xa816,0xa82b,0xa82c,0xa82d,0xa82e,0xa82f,0xa830,0xa831};
		for(int id=0;id<packTraps.length;id++)f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("PACK"),id,null,DEF_ATTRIBUTES,packStub(packTraps[id])));
		// The default window colour table ('wctb' 0, 13 parts as in System 7): a program's WDEF reads it for its frame colours
		// Some palettes use them for the title background and title text; with them missing every part came out black on black.
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("wctb"),0,null,0x40,windowColorTable()));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("snd "),1,"Simple Beep",0,beep()));
		// The Finder labels' names ('lstr' -16392 + label number, a Str31 each; GetLabel answers them with their colours)
		for(int i=0;i<LABEL_NAMES.length;i++)f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("lstr"),LABEL_STRING_ID+i,null,0x20,str31(LABEL_NAMES[i])));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("MENU"),-16490,null,0,new byte[]{(byte)0xbf,(byte)0x96,0,0,0,0,0,0,0,0,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,4,'H','e','l','p',0}));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("vers"),1,null,0,versionResource(version)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("PREC"),0,null,ResourceFile68k.RES_PURGEABLE,PrintManager68k.defaultPrintRecord())); // the printer driver's default print record
		StandardFile68k.addSystemResources(f);
		// The Folder Manager's names of the System Folder's special folders, as FileSystem68k.specialFolder makes them. Apple's
		// Installer reads the target System's 'fld#' 0 to place "special-extn:" files; without it they landed in the System Folder.
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("fld#"),0,null,0,folderNames(new String[][]{{"prnt","PrintMonitor Documents"},{"strt","Startup Items"},
			{"shdf","Shutdown Items"},{"amnu","Apple Menu Items"},{"extn","Extensions"},{"pref","Preferences"},{"ctrl","Control Panels"},{"font","Fonts"}})));
		// Suitcases of the Fonts folder: their FOND/NFNT/FONT/sfnt resources are found through the System file, as under System 7.1
		for(ResourceFile68k.Resource r:InstalledFonts68k.systemResources())if(f.get(r.type,r.id)==null)f.add(r);
		// The families the host fonts stand in for get a 'FOND' too: EGWord 5.0 builds its font menus from
		// CountResources/GetIndResource('FOND') + GetResInfo and showed empty ones (font pop-ups blank) without them.
		for(java.util.Map.Entry<Integer,String> e:Fonts68k.NAMES.entrySet()){
			int id=e.getKey();if(id==1||f.get(InstalledFonts68k.FOND,id)!=null)continue; // 1 = the application font, not a family
			String name=fondName(e.getValue());if(f.getNamed(InstalledFonts68k.FOND,name)!=null)continue;
			f.add(new ResourceFile68k.Resource(InstalledFonts68k.FOND,id,name,0x20,fond(id,e.getValue())));
		}
		return f;
	}
	/** A resource name as the map stores it: the family name's bytes in the system script (Shift-JIS for Japanese families), read as Mac Roman. */
	private static String fondName(String name){
		byte[] b=name.getBytes(java.nio.charset.Charset.forName("Shift_JIS"));
		return StackTextEncoding.ROMAN.decode(b,0,b.length);
	}
	/**
	 * A minimal 'FOND' for a family the host renders: the 52-byte header (family number, characters $20-$FF, rough metrics in
	 * 4.12 fixed point, monospaced flag) and one association entry, size 0 = outlines (every size), so programs see a scalable
	 * family; its 'sfnt' ID is the family number. Drawing never reads these - System FONDs are left to the host fonts.
	 */
	private static byte[] fond(int id,String name){
		boolean mono=name.equals("Monaco")||name.equals("Courier")||name.contains("等幅");
		byte[] d=new byte[60];
		d[0]=(byte)(mono?0x80:0);d[2]=(byte)(id>>8);d[3]=(byte)id;
		d[5]=0x20;d[7]=(byte)0xff;                                   // ffFirstChar, ffLastChar
		d[8]=0x0c;d[9]=(byte)0xcc;d[10]=(byte)0xfc;d[11]=(byte)0xcd; // ascent 0.8, descent -0.2
		d[14]=0x10;                                                  // widMax 1.0
		d[50]=0;d[51]=2;                                             // ffVersion
		d[52]=0;d[53]=0;                                             // one association entry
		d[58]=(byte)(id>>8);d[59]=(byte)id;                          // size 0, style 0, sfnt id
		return d;
	}

	/** 'fld#': per folder its type, a zero version word, the name's length as a word and the name, padded to an even length. */
	private static byte[] folderNames(String[][] folders){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		for(String[] folder:folders){
			byte[] type=folder[0].getBytes(java.nio.charset.StandardCharsets.US_ASCII),name=folder[1].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
			out.write(type,0,4);out.write(0);out.write(0);out.write(name.length>>8);out.write(name.length);out.write(name,0,name.length);
			if((name.length&1)!=0)out.write(0);
		}
		return out.toByteArray();
	}

	/** A cursor drawn as 16 rows of 16 characters: '#' black, '.' white, ' ' transparent. */
	private static byte[] cursor(String[] rows,int hotV,int hotH){
		int[] data=new int[16],mask=new int[16];
		for(int y=0;y<16;y++)for(int x=0;x<16;x++){char c=rows[y].charAt(x);if(c=='#')data[y]|=1<<(15-x);if(c!=' ')mask[y]|=1<<(15-x);}
		return cursor(data,mask,hotV,hotH);
	}
	/** The watch cursor with its minute hand in one of 8 positions (0 = east, then clockwise); the hour hand always points up. */
	private static byte[] watch(int minute){
		int[] data={0x3f00,0x3f00,0x3f00,0x3f00,0x4080,0x8040,0x8040,0x8040,0x8040,0x8040,0x8040,0x4080,0x3f00,0x3f00,0x3f00,0x3f00};
		int[] mask={0x3f00,0x3f00,0x3f00,0x3f00,0x7f80,0xffc0,0xffc0,0xffc0,0xffc0,0xffc0,0xffc0,0x7f80,0x3f00,0x3f00,0x3f00,0x3f00};
		int[] dx={1,1,0,-1,-1,-1,0,1},dy={0,1,1,1,0,-1,-1,-1};final int cx=5,cy=8;
		data[cy]|=1<<(15-cx);
		for(int i=1;i<=2;i++)data[cy-i]|=1<<(15-cx); // hour hand
		for(int i=1;i<=3;i++){int x=cx+dx[minute]*i,y=cy+dy[minute]*i;if(y>=5&&y<=10&&x>=1&&x<=8)data[y]|=1<<(15-x);} // minute hand, kept inside the face
		return cursor(data,mask,cy,cx);
	}
	/** An 'acur' record: frame count, a reserved word, then each frame as a CURS ID in the high word (the Cursor Utilities load the Handles in place). */
	private static byte[] acur(int[] ids){
		byte[] b=new byte[4+ids.length*4];b[0]=(byte)(ids.length>>8);b[1]=(byte)ids.length;
		for(int i=0;i<ids.length;i++){b[4+i*4]=(byte)(ids[i]>>8);b[5+i*4]=(byte)ids[i];}
		return b;
	}
	private static byte[] cursor(int[] data,int[] mask,int hotV,int hotH){
		byte[] b=new byte[68];
		for(int i=0;i<16;i++){b[i*2]=(byte)(data[i]>>8);b[i*2+1]=(byte)data[i];b[32+i*2]=(byte)(mask[i]>>8);b[32+i*2+1]=(byte)mask[i];}
		b[64]=(byte)(hotV>>8);b[65]=(byte)hotV;b[66]=(byte)(hotH>>8);b[67]=(byte)hotH;return b;
	}

	/**
	 * The 38 standard patterns of the classic PAT# 0, in the order Inside Macintosh I shows them (Toolbox Utilities, "Standard
	 * Patterns"): programs take them by index (GetIndPattern(sysPatListID, n)) - Wingz 1.0 fills its cells with number 20,
	 * white; with the patterns in another order its sheet came out striped.
	 */
	private static byte[] patterns(){
		long[] p={0xffffffffffffffffL,0xddff77ffddff77ffL,0xdd77dd77dd77dd77L,0xaa55aa55aa55aa55L,0x55ff55ff55ff55ffL,0xaaaaaaaaaaaaaaaaL,0xeeddbb77eeddbb77L,0x8888888888888888L,
			0xb130031bd8c00c8dL,0x8010022001084004L,0xff888888ff888888L,0xff808080ff080808L,0x8000000000000000L,0x8040200002040800L,0x8244394482010101L,0xf87422478f172271L,
			0x55a04040550a0404L,0x2050888888880502L,0xbf00bfbfb0b0b0b0L,0x0000000000000000L,0x8000080080000800L,0x8800220088002200L,0x8822882288228822L,0xaa00aa00aa00aa00L,
			0xff00ff00ff00ff00L,0x1122448811224488L,0xff000000ff000000L,0x0102040810204080L,0xaa00800088008000L,0xff80808080808080L,0x081c22c180010204L,0x881422418800aa00L,
			0x40a00000040a0000L,0x038448300c020101L,0x8080413e080814e3L,0x102054aaff020408L,0x77898f8f7798f8f8L,0x0008142a552a1408L};
		byte[] b=new byte[2+p.length*8];b[0]=0;b[1]=(byte)p.length;
		for(int i=0;i<p.length;i++)for(int j=0;j<8;j++)b[2+i*8+j]=(byte)(p[i]>>>(56-j*8));
		return b;
	}

	/**
	 * 'ppat' 16, the colour desktop pattern (GetPixPat(16); DeskCPat while pCDeskPat's top bit is set): the grey texture the
	 * desktop is drawn with ({@link WindowManager68k#DESK_FORE}/{@link WindowManager68k#DESK_BACK} over 'PAT ' 16) as an
	 * 8x8 1-bit PixMap with a two-entry colour table. The Desktop Patterns control panel replaces it.
	 */
	static byte[] deskPixPat(){
		final int map=28,pixels=map+50,table=pixels+16;
		byte[] b=new byte[table+8+2*8];
		put2(b,0,1);put4(b,2,map);put4(b,6,pixels);put2(b,14,-1);          // patType 1 (full PixPat), patMap, patData, patXValid -1
		for(int i=0;i<8;i++)b[20+i]=(byte)((i&1)==0?0xaa:0x55);             // pat1Data = 'PAT ' 16
		put2(b,map+4,0x8002);put2(b,map+10,8);put2(b,map+12,8);             // rowBytes (PixMap flag), bounds 0,0,8,8
		put4(b,map+22,0x480000);put4(b,map+26,0x480000);                    // hRes, vRes 72 dpi
		put2(b,map+32,1);put2(b,map+34,1);put2(b,map+36,1);put4(b,map+42,table); // pixelSize 1, cmpCount 1, cmpSize 1, pmTable
		for(int y=0;y<8;y++)b[pixels+y*2]=(byte)((y&1)==0?0xaa:0x55);
		put2(b,table+6,1);                                                  // ctSize: 2 entries
		int[] rgb={WindowManager68k.DESK_BACK,WindowManager68k.DESK_FORE};
		for(int i=0;i<2;i++){int e=table+8+i*8;put2(b,e,i);for(int c=0;c<3;c++){int v=rgb[i]>>>(16-c*8)&0xff;put2(b,e+2+c*2,v<<8|v);}}
		return b;
	}
	/**
	 * 'ppt#' 0, the System's colour pattern list: a count, an offset per pattern from the start of the resource, then each
	 * pattern as a whole 'ppat' resource image (System 7's has 12: 8x8, 4 bits per pixel). QuickDraw Utilities' NrPixPats /
	 * GetIndPixPat read it ("Patterned Ovals" drew half of its ovals solid black without one). The designs are HyperZebra's own:
	 * each row is 8 palette letters, the palette the colours ('.' = white) of the 4-bit colour table.
	 */
	static byte[] pixPatList(){
		String[][] art={
			{"rrrr....",".rrrr...","..rrrr..","...rrrr.","....rrrr","r....rrr","rr....rr","rrr....r"},          // red diagonal stripes
			{"bbBBbbBB","bbBBbbBB","BBbbBBbb","BBbbBBbb","bbBBbbBB","bbBBbbBB","BBbbBBbb","BBbbBBbb"},          // blue checks
			{"oooooooo","ooo.oooo","ooo.oooo","........","o.oooooo","o.oooooo","o.oooooo","........"},          // orange bricks
			{"gggggggg","gGggggGg","gggggggg","ggGggggG","gggggggg","gGggggGg","gggggggg","ggGggggG"},          // green speckles
			{"...p....","..ppp...",".ppppp..","ppp.ppp.",".ppppp..","..ppp...","...p....","........"},          // purple diamonds
			{"cc......","..cc....","....cc..","......cc","......cc","....cc..","..cc....","cc......"},          // teal zig-zag
			{"yyyyyyyy","yyyyyyyy","oooooooo","rrrrrrrr","pppppppp","bbbbbbbb","cccccccc","gggggggg"},          // rainbow bands
			{"nNnnnnnN","nnNnnnnN","nnnNnnNn","nnnnNNnn","nnnnNNnn","nnnNnnNn","nnNnnnnN","nNnnnnnN"},          // brown grain
			{"b.b.y.y.",".b.b.y.y","b.b.y.y.",".b.b.y.y","y.y.b.b.",".y.y.b.b","y.y.b.b.",".y.y.b.b"},          // blue/yellow quilt
			{"r.......","....g...","......b.","..y.....",".....p..",".c......","...o....",".......r"},          // confetti
			{"BBBBBBBB","B..BB..B","B..BB..B","BBBBBBBB","BBBBBBBB","B..BB..B","B..BB..B","BBBBBBBB"},          // sky windows
			{"kkkk....","kkkk....","kkkk....","kkkk....","....kkkk","....kkkk","....kkkk","....kkkk"}};         // grey squares
		String letters=".rbBogGpcyNnk";
		int[] rgb={0xffffff,0xdd2020,0x2040c0,0x90b8f0,0xf08820,0x208830,0x90d890,0x9030b0,0x10a0a0,0xf0d020,0x603810,0xa07040,0x808080};
		byte[][] pats=new byte[art.length][];for(int i=0;i<art.length;i++)pats[i]=pixPat(art[i],letters,rgb);
		int offset=2+4*pats.length,size=offset;for(byte[] p:pats)size+=p.length;
		byte[] b=new byte[size];put2(b,0,pats.length);
		for(int i=0;i<pats.length;i++){put4(b,2+i*4,offset);System.arraycopy(pats[i],0,b,offset,pats[i].length);offset+=pats[i].length;}
		return b;
	}
	/** One 8x8 'ppat' image at 4 bits per pixel with the colours its rows use; pat1Data (the 1-bit fallback) marks the dark pixels. */
	private static byte[] pixPat(String[] rows,String letters,int[] rgb){
		java.util.List<Integer> used=new java.util.ArrayList<Integer>();
		for(String r:rows)for(char c:r.toCharArray()){int k=letters.indexOf(c);if(!used.contains(k))used.add(k);}
		final int map=28,pixels=map+50,table=pixels+32;
		byte[] b=new byte[table+8+used.size()*8];
		put2(b,0,1);put4(b,2,map);put4(b,6,pixels);put2(b,14,-1);          // patType 1 (full PixPat), patMap, patData, patXValid -1
		put2(b,map+4,0x8004);put2(b,map+10,8);put2(b,map+12,8);             // rowBytes 4 (PixMap flag), bounds 0,0,8,8
		put4(b,map+22,0x480000);put4(b,map+26,0x480000);                    // hRes, vRes 72 dpi
		put2(b,map+32,4);put2(b,map+34,1);put2(b,map+36,4);put4(b,map+42,table); // pixelSize 4, cmpCount 1, cmpSize 4, pmTable
		for(int y=0;y<8;y++)for(int x=0;x<8;x++){int k=letters.indexOf(rows[y].charAt(x)),c=rgb[k],index=used.indexOf(k);
			b[pixels+y*4+x/2]|=(byte)(x%2==0?index<<4:index);
			int luma=((c>>16&0xff)*30+(c>>8&0xff)*59+(c&0xff)*11)/100;if(luma<128)b[20+y]|=(byte)(0x80>>x);}
		put2(b,table+6,used.size()-1);                                      // ctSize
		for(int i=0;i<used.size();i++){int e=table+8+i*8,c=rgb[used.get(i)];put2(b,e,i);for(int k=0;k<3;k++){int v=c>>>(16-k*8)&0xff;put2(b,e+2+k*2,v<<8|v);}}
		return b;
	}
	private static void put2(byte[] b,int i,int v){b[i]=(byte)(v>>8);b[i+1]=(byte)v;}
	private static void put4(byte[] b,int i,int v){put2(b,i,v>>>16);put2(b,i+2,v);}

	static byte[] icon(int kind){
		byte[] b=new byte[128];
		for(int y=0;y<32;y++){int row;
			if(kind==0){int d=Math.abs(y-15);int half=(int)Math.sqrt(Math.max(0,15.5*15.5-d*d));row=half==0?0:((0xffffffff>>>(31-2*half))<<(16-half));if(y>=8&&y<=23&&y!=12&&y!=13&&y!=18&&y!=19)row&=~0x000c0000;}
			else if(kind==1){int d=Math.abs(y-15);int half=(int)Math.sqrt(Math.max(0,15.5*15.5-d*d));row=half==0?0:((0xffffffff>>>(31-2*half))<<(16-half));if((y>=8&&y<=9)||(y>=12&&y<=22))row&=~0x00030000;}
			else{int half=Math.min(16,y/2+1);row=(0xffffffff>>>(32-2*half))<<(16-half);if(y>=10&&y<=20&&y!=17&&y!=18)row&=~0x00030000;}
			b[y*4]=(byte)(row>>>24);b[y*4+1]=(byte)(row>>>16);b[y*4+2]=(byte)(row>>>8);b[y*4+3]=(byte)row;}
		return b;
	}

	/** A 32x32 8-bit 'cicn' alert icon of HyperZebra's own design: stop (0) a red disc with "!", note (1) a blue disc with "i",
	 *  caution (2) a yellow triangle with "!" - each with a black outline, a mask and a 1-bit image. */
	static byte[] alertCicn(int kind){
		java.awt.image.BufferedImage im=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g=im.createGraphics();
		try{
			java.awt.Shape body=kind==2?new java.awt.Polygon(new int[]{15,1,30},new int[]{1,29,29},3):new java.awt.geom.Ellipse2D.Float(1,1,29,29);
			g.setColor(kind==0?new java.awt.Color(0xdd2222):kind==1?new java.awt.Color(0x3366cc):new java.awt.Color(0xffcc00));g.fill(body);
			g.setColor(java.awt.Color.BLACK);g.draw(body);
			g.setColor(kind==2?java.awt.Color.BLACK:java.awt.Color.WHITE);
			if(kind==1){g.fillRect(14,7,4,4);g.fillRect(14,13,4,12);}
			else{int top=kind==2?10:7;g.fillRect(14,top,4,kind==2?11:12);g.fillRect(14,kind==2?23:22,4,3);}
		}finally{g.dispose();}
		java.util.List<Integer> colors=new java.util.ArrayList<Integer>();colors.add(0xffffff);
		int[] index=new int[32*32];byte[] mask=new byte[128],bits=new byte[128];
		for(int y=0;y<32;y++)for(int x=0;x<32;x++){int argb=im.getRGB(x,y);
			if((argb>>>24)<128){index[y*32+x]=0;continue;}
			int rgb=argb&0xffffff;int i=colors.indexOf(rgb);if(i<0){i=colors.size();colors.add(rgb);}index[y*32+x]=i;
			mask[y*4+x/8]|=0x80>>>(x&7);
			int luma=((rgb>>16)&255)*30+((rgb>>8)&255)*59+(rgb&255)*11;if(luma<12800)bits[y*4+x/8]|=0x80>>>(x&7);}
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();java.io.DataOutputStream d=new java.io.DataOutputStream(out);
		try{
			d.writeInt(0);d.writeShort(0x8000|32);d.writeShort(0);d.writeShort(0);d.writeShort(32);d.writeShort(32); // PixMap: baseAddr, rowBytes, bounds
			d.writeShort(0);d.writeShort(0);d.writeInt(0);d.writeInt(0x00480000);d.writeInt(0x00480000);       // pmVersion, packType, packSize, hRes, vRes
			d.writeShort(0);d.writeShort(8);d.writeShort(1);d.writeShort(8);d.writeInt(0);d.writeInt(0);d.writeInt(0); // pixelType, pixelSize, cmpCount, cmpSize, planeBytes, pmTable, pmReserved
			for(int b=0;b<2;b++){d.writeInt(0);d.writeShort(4);d.writeShort(0);d.writeShort(0);d.writeShort(32);d.writeShort(32);} // maskMap, iconBMap
			d.writeInt(0);d.write(mask);d.write(bits);                                                           // iconData, mask, 1-bit image
			d.writeInt(0);d.writeShort(0);d.writeShort(colors.size()-1);                                         // ColorTable header
			for(int i=0;i<colors.size();i++){int c=colors.get(i);d.writeShort(i);d.writeShort(((c>>16)&255)*257);d.writeShort(((c>>8)&255)*257);d.writeShort((c&255)*257);}
			for(int v:index)d.writeByte(v);
		}catch(java.io.IOException e){throw new IllegalStateException(e);}
		return out.toByteArray();
	}

	static final int MACHINE_NAMES_ID=-16395;
	/** Model names by Gestalt machine type (the gestaltMachineType values of Gestalt.h), 1-128; types without a model are empty. */
	private static final Object[] MACHINE_NAMES={1,"Macintosh",2,"Macintosh XL",3,"Macintosh 512Ke",4,"Macintosh Plus",5,"Macintosh SE",6,"Macintosh II",
		7,"Macintosh IIx",8,"Macintosh IIcx",9,"Macintosh SE/30",10,"Macintosh Portable",11,"Macintosh IIci",12,"Power Macintosh 8100/120",13,"Macintosh IIfx",
		17,"Macintosh Classic",18,"Macintosh IIsi",19,"Macintosh LC",20,"Macintosh Quadra 900",21,"PowerBook 170",22,"Macintosh Quadra 700",23,"Macintosh Classic II",
		24,"PowerBook 100",25,"PowerBook 140",26,"Macintosh Quadra 950",27,"Macintosh LC III",29,"PowerBook Duo 210",30,"Macintosh Centris 650",32,"PowerBook Duo 230",
		33,"PowerBook 180",34,"PowerBook 160",35,"Macintosh Quadra 800",36,"Macintosh Quadra 650",37,"Macintosh LC II",38,"PowerBook Duo 250",39,"Workgroup Server 9150/80",
		40,"Power Macintosh 8100/110",41,"Power Macintosh 5200",42,"Power Macintosh 6200",44,"Macintosh IIvi",45,"Macintosh IIvm",47,"Power Macintosh 7100/80",
		48,"Macintosh IIvx",49,"Macintosh Color Classic",50,"PowerBook 165c",52,"Macintosh Centris 610",53,"Macintosh Quadra 610",54,"PowerBook 145",
		55,"Power Macintosh 8100/100",56,"Macintosh LC 520",57,"Workgroup Server 9150/120",58,"Power Macintosh 6400",60,"Macintosh Centris 660AV",62,"Macintosh Performa 460",
		65,"Power Macintosh 8100/80",67,"Power Macintosh 9500",68,"Power Macintosh 7500",69,"Power Macintosh 8500",71,"PowerBook 180c",72,"PowerBook 520",
		74,"Power Macintosh 5400",75,"Power Macintosh 6100/60",77,"PowerBook Duo 270c",78,"Macintosh Quadra 840AV",80,"Macintosh Performa 550",84,"PowerBook 165",
		85,"PowerBook 190",88,"Macintosh TV",89,"Macintosh LC 475",92,"Macintosh LC 575",94,"Macintosh Quadra 605",98,"Macintosh Quadra 630",99,"Macintosh LC 580",
		100,"Power Macintosh 6100/66",102,"PowerBook Duo 280",103,"PowerBook Duo 280c",104,"Power Macintosh LC 475",105,"Power Macintosh LC 575",106,"Power Macintosh Quadra 630",
		108,"Power Macintosh 7200",109,"Power Macintosh 7300",112,"Power Macintosh 7100/66",115,"PowerBook 150",116,"Power Macintosh Quadra 700",117,"Power Macintosh Quadra 900",
		118,"Power Macintosh Quadra 950",119,"Power Macintosh Centris 610",120,"Power Macintosh Centris 650",121,"Power Macintosh Quadra 610",122,"Power Macintosh Quadra 650",
		123,"Power Macintosh Quadra 800",124,"PowerBook Duo 2300",126,"PowerBook 500 PowerPC Upgrade",128,"PowerBook 5300"};
	private static byte[] machineNames(){
		String[] names=new String[128];java.util.Arrays.fill(names,"");
		for(int i=0;i<MACHINE_NAMES.length;i+=2)names[(Integer)MACHINE_NAMES[i]-1]=(String)MACHINE_NAMES[i+1];
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();out.write(0);out.write(names.length);
		for(String n:names){byte[] b=pascal(n);out.write(b,0,b.length);}
		return out.toByteArray();
	}
	private static byte[] pascal(String s){byte[] t=StackTextEncoding.ROMAN.encodeReplacing(s);byte[] b=new byte[t.length+1];b[0]=(byte)t.length;System.arraycopy(t,0,b,1,t.length);return b;}

	/** Virtual key code -> character of a US keyboard (IM: Macintosh Toolbox Essentials, "Virtual key codes"); 0 = no character. */
	private static final char[] KEY_PLAIN=new char[128],KEY_SHIFT=new char[128];
	static{
		String plain="asdfhgzxcv\u00a7bqweryt123465=97-80]ou[ip\rlj'k;\\,/nm.\t `\b\u0003\u001b";
		String shift="ASDFHGZXCV\u00b1BQWERYT!@#$^%+(&_*)}OU{IP\rLJ\"K:|<?NM>\t ~\b\u0003\u001b";
		for(int i=0;i<plain.length();i++){KEY_PLAIN[i]=plain.charAt(i);KEY_SHIFT[i]=shift.charAt(i);}
		int[][] keypad={{65,'.'},{67,'*'},{69,'+'},{71,0x1b},{75,'/'},{76,3},{78,'-'},{81,'='},{82,'0'},{83,'1'},{84,'2'},{85,'3'},{86,'4'},{87,'5'},{88,'6'},{89,'7'},{91,'8'},{92,'9'},
			{114,5},{115,1},{116,0x0b},{117,0x7f},{119,4},{121,0x0c},{123,0x1c},{124,0x1d},{125,0x1f},{126,0x1e}};
		for(int[] k:keypad)KEY_PLAIN[k[0]]=KEY_SHIFT[k[0]]=(char)k[1];
		for(int k:new int[]{96,97,98,99,100,101,103,105,107,109,111,113,118,120,122})KEY_PLAIN[k]=KEY_SHIFT[k]=0x10; // function keys
	}
	/** The character a US keyboard sends for the key code under those modifiers (what KeyTranslate and the KCHR tables agree on). */
	static int keyChar(int keyCode,boolean shift,boolean capsLock,boolean control){
		keyCode&=0x7f;char c=shift?KEY_SHIFT[keyCode]:KEY_PLAIN[keyCode];
		if(capsLock&&!shift&&c>='a'&&c<='z')c=(char)(c-32);
		if(control&&Character.toUpperCase(c)>='@'&&Character.toUpperCase(c)<='_')c=(char)(Character.toUpperCase(c)&0x1f);
		return c&0xff;
	}
	/**
	 * A 'KCHR' keyboard layout (IM: Text, "The KCHR resource"): version word, 256-byte table of modifier byte
	 * (bit 0 command, 1 shift, 2 caps lock, 3 option, 4 control, 5-7 right-hand shift/option/control) -> table
	 * number, table count, 128-byte key code -> character tables (plain, shift, caps lock, control, option, shift-option), dead-key records (the Option accents). HyperCard, TextEdit
	 * clients and KeyTranslate callers read the script's layout through GetScriptVariable(smScriptKeys).
	 */
	/** The 'KCHR' in use, for the key cache the System keeps a pointer to (ExpandMem emKeyCache, smKCHRCache). */
	static byte[] keyboardLayout(){return kchr();}
	/** US Option layer: the plain key's character -> what Option types (dead keys excluded). */
	private static final String OPTION_FROM="abcdfghjklmopqrstvwxyz1234567890-=[]\\;',./ ",OPTION_TO="\u00e5\u222b\u00e7\u2202\u0192\u00a9\u02d9\u2206\u02da\u00ac\u00b5\u00f8\u03c0\u0153\u00ae\u00df\u2020\u221a\u2211\u2248\u00a5\u03a9\u00a1\u2122\u00a3\u00a2\u221e\u00a7\u00b6\u2022\u00aa\u00ba\u2013\u2260\u201c\u2018\u00ab\u2026\u00e6\u2264\u2265\u00f7\u00a0";
	/** US Option dead keys: the key, its accent, and the letters it combines with -> the accented letters. */
	private static final String[][] DEAD_KEYS={
		{"e","\u00b4","aeiouAEIOU ","\u00e1\u00e9\u00ed\u00f3\u00fa\u00c1\u00c9\u00cd\u00d3\u00da\u00b4"},
		{"`","`","aeiouAEIOU ","\u00e0\u00e8\u00ec\u00f2\u00f9\u00c0\u00c8\u00cc\u00d2\u00d9`"},
		{"i","\u02c6","aeiouAEIOU ","\u00e2\u00ea\u00ee\u00f4\u00fb\u00c2\u00ca\u00ce\u00d4\u00db\u02c6"},
		{"n","\u02dc","anoANO ","\u00e3\u00f1\u00f5\u00c3\u00d1\u00d5\u02dc"},
		{"u","\u00a8","aeiouyAEIOUY ","\u00e4\u00eb\u00ef\u00f6\u00fc\u00ff\u00c4\u00cb\u00cf\u00d6\u00dc\u0178\u00a8"}};
	private static int roman(String c){byte[] b=c.getBytes(java.nio.charset.Charset.forName("x-MacRoman"));return b.length==1?b[0]&0xff:'?';}
	private static int keyFor(char plain){for(int k=0;k<128;k++)if(KEY_PLAIN[k]==plain)return k;return -1;}
	private static byte[] kchr(){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		out.write(0);out.write(0);                                     // version
		for(int m=0;m<256;m++){                                        // tables: 0 plain, 1 shift, 2 caps lock, 3 control, 4 option, 5 shift-option
			boolean shift=(m&0x22)!=0,caps=(m&0x04)!=0,control=(m&0x90)!=0,option=(m&0x48)!=0;
			out.write(control?3:option?(shift||caps?5:4):shift?1:caps?2:0);}
		out.write(0);out.write(6);                                     // table count
		for(int t=0;t<4;t++)for(int k=0;k<128;k++)out.write(keyChar(k,t==1,t==2,t==3));
		int[] option=new int[128],shiftOption=new int[128];
		for(int k=0;k<128;k++){option[k]=keyChar(k,false,false,false);shiftOption[k]=keyChar(k,true,false,false);}
		for(int i=0;i<OPTION_FROM.length();i++){int k=keyFor(OPTION_FROM.charAt(i));if(k<0)continue;String c=OPTION_TO.substring(i,i+1);option[k]=roman(c);
			String upper=c.toUpperCase(java.util.Locale.ROOT);if(!upper.equals(c)&&upper.length()==1&&roman(upper)!='?')shiftOption[k]=roman(upper);}
		for(String[] d:DEAD_KEYS){int k=keyFor(d[0].charAt(0));if(k>=0)option[k]=roman(d[1]);}
		for(int k=0;k<128;k++)out.write(option[k]);
		for(int k=0;k<128;k++)out.write(shiftOption[k]);
		out.write(0);out.write(DEAD_KEYS.length);                      // dead keys: table, key code, completions (char, result), no-match (0, accent)
		for(String[] d:DEAD_KEYS){out.write(4);out.write(keyFor(d[0].charAt(0)));out.write(0);out.write(d[2].length());
			for(int i=0;i<d[2].length();i++){out.write(d[2].charAt(i));out.write(roman(d[3].substring(i,i+1)));}
			out.write(0);out.write(roman(d[1]));}
		return out.toByteArray();
	}

	/** Resource ID of the Japanese international resources (itl0/itl1/itl2/itl4 16384, script bundle itlb 1). */
	static final int JAPANESE_ITL=16384;
	/** sysHeap + locked, as a real System file marks its definition procedures. */
	static final int DEF_ATTRIBUTES=ResourceFile68k.RES_SYS_HEAP|ResourceFile68k.RES_LOCKED;

	/**
	 * A definition procedure that does nothing: pops its Pascal arguments, stores a zero function result if the
	 * kind returns one, and returns to the caller.
	 * <pre>
	 *   MOVE.L (SP)+,A0      return address
	 *   ADDA.W #args,SP      drop the arguments
	 *   CLR.L  (SP)          function result (functions only)
	 *   JMP    (A0)
	 * </pre>
	 */
	/** MOVEA.L (SP)+,A0 ; _PackN ; JMP (A0): the stack then holds the selector and arguments exactly as the trap expects. */
	/** wctb 0: wContentColor, wFrameColor, wTextColor, wHiliteColor, wTitleBarColor, then the System 7 parts 5-12 (hilite/title
	 *  bar/dialog light+dark, tinge light+dark); the standard values of Inside Macintosh with the lavender tinge of a stock System 7. */
	static byte[] windowColorTable(){
		int[][] rgb={{0xffff,0xffff,0xffff},{0,0,0},{0,0,0},{0,0,0},{0xffff,0xffff,0xffff},
			{0xcccc,0xcccc,0xffff},{0x3333,0x3333,0x6666},{0xcccc,0xcccc,0xffff},{0x3333,0x3333,0x6666},
			{0xffff,0xffff,0xffff},{0,0,0},{0xcccc,0xcccc,0xffff},{0x3333,0x3333,0x6666}};
		byte[] b=new byte[8+rgb.length*8];b[7]=(byte)(rgb.length-1); // ctSeed 0, ctFlags 0, ctSize = entries-1
		for(int i=0;i<rgb.length;i++){int e=8+i*8;b[e+1]=(byte)i;for(int c=0;c<3;c++){b[e+2+c*2]=(byte)(rgb[i][c]>>8);b[e+3+c*2]=(byte)rgb[i][c];}}
		return b;
	}
	static byte[] packStub(int trap){return new byte[]{0x20,0x5f,(byte)(trap>>8),(byte)trap,0x4e,(byte)0xd0};}
	/**
	 * A 'dcmp' called as dcmp(source, destination, working, workingSize) (source = the data after the 18-byte header; the
	 * callee pops the arguments and keeps every register): _ResourceDispatch with the private selector
	 * {@link ResourceManager68k#DCMP_SELECTOR}+id expands the data natively.
	 */
	static byte[] dcmpStub(int id){
		int selector=ResourceManager68k.DCMP_SELECTOR|id;
		return new byte[]{0x2f,0x00,                                        // MOVE.L D0,-(SP)
			0x20,0x3c,(byte)(selector>>>24),(byte)(selector>>>16),(byte)(selector>>>8),(byte)selector, // MOVE.L #selector,D0
			(byte)0xa8,0x22,                                                // _ResourceDispatch
			0x4e,0x75};                                                     // RTS
	}
	/** A WDEF/CDEF (varCode, target, message, param): LONGINT served natively: _ResourceDispatch with a private selector writes
	 *  the result, then the stub pops the 12 bytes of arguments as the Pascal convention wants. */
	static byte[] nativeDefStub(int selector){
		return new byte[]{0x20,0x3c,(byte)(selector>>>24),(byte)(selector>>>16),(byte)(selector>>>8),(byte)selector, // MOVE.L #selector,D0
			(byte)0xa8,0x22,                                                // _ResourceDispatch
			0x20,0x5f,(byte)0xde,(byte)0xfc,0x00,0x0c,0x4e,(byte)0xd0};      // MOVE.L (SP)+,A0; ADDA.W #12,SP; JMP (A0)
	}
	/** MDEF 0: MenuManager68k.standardMdef through _ResourceDispatch, then pops its 18 bytes of arguments (a procedure). */
	static byte[] nativeMdefStub(){
		int selector=ResourceManager68k.MDEF_SELECTOR;
		return new byte[]{0x20,0x3c,(byte)(selector>>>24),(byte)(selector>>>16),(byte)(selector>>>8),(byte)selector, // MOVE.L #selector,D0
			(byte)0xa8,0x22,                                                // _ResourceDispatch
			0x20,0x5f,(byte)0xde,(byte)0xfc,0x00,0x12,0x4e,(byte)0xd0};      // MOVE.L (SP)+,A0; ADDA.W #18,SP; JMP (A0)
	}
	static byte[] defStub(int argumentBytes,boolean function){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		out.write(0x20);out.write(0x5f);                                   // MOVE.L (SP)+,A0
		out.write(0xde);out.write(0xfc);out.write(argumentBytes>>8);out.write(argumentBytes); // ADDA.W #n,SP
		if(function){out.write(0x42);out.write(0x97);}                     // CLR.L (SP)
		out.write(0x4e);out.write(0xd0);                                   // JMP (A0)
		return out.toByteArray();
	}

	/** Intl0Rec (Inside Macintosh: Text): number, currency, short date and time formats. */
	private static byte[] itl0(boolean japanese){
		byte[] b=new byte[32];
		b[0]='.';b[1]=',';b[2]=';';                     // decimalPt, thousSep, listSep
		b[3]=(byte)(japanese?0x5c:'$');b[4]=0;b[5]=0;   // currSym1..3: the yen sign is $5C in Shift-JIS
		b[6]=(byte)0xd0;                                // currFmt: currLeadingZ | currTrailingZ | currSymLead
		b[7]=(byte)(japanese?2:0);                      // dateOrder: mdy = 0, ymd = 2
		b[8]=0;                                         // shrtDateFmt: no leading zeros, no century
		b[9]=(byte)(japanese?'.':'/');                  // dateSep
		b[10]=(byte)(japanese?1:0xff);                  // timeCycle: 12-hour with 12:00 = 255, with 0:00 = 1 (KanjiTalk), 24-hour = 0
		b[11]=0x60;                                     // timeFmt: minLeadingZ | secLeadingZ
		b[12]=' ';b[13]='A';b[14]='M';b[15]=0;          // mornStr
		b[16]=' ';b[17]='P';b[18]='M';b[19]=0;          // eveStr
		b[20]=':';                                      // timeSep
		for(int i=21;i<29;i++)b[i]=0;                   // time1Suff..time8Suff
		b[29]=(byte)(japanese?0xff:0);                  // metricSys
		b[30]=(byte)(japanese?14:0);b[31]=1;            // intl0Vers: region (verUS 0, verJapan 14), version 1
		return b;
	}
	/** Intl1Rec: day and month names, long date format. Str15 names, then four flag bytes, five 4-char separators, version, localRtn (RTS). */
	private static byte[] itl1(boolean japanese){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		String[] days=japanese?new String[]{"日曜日","月曜日","火曜日","水曜日","木曜日","金曜日","土曜日"}:new String[]{"Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"};
		String[] months=japanese?new String[]{"1","2","3","4","5","6","7","8","9","10","11","12"}:new String[]{"January","February","March","April","May","June","July","August","September","October","November","December"};
		StackTextEncoding e=japanese?StackTextEncoding.JAPANESE:StackTextEncoding.ROMAN;
		for(String d:days)fixedPascal(out,e,d,16);
		for(String m:months)fixedPascal(out,e,m,16);
		out.write(0);                                   // suppressDay: 0 = show the day name in long dates
		out.write(japanese?0x4b:0xff);                  // lngDateFmt: order of the long date's day/month/year/day-of-week fields
		out.write(0);                                   // dayLeading0
		out.write(japanese?4:3);                        // abbrLen
		// st0..st4 (PACKED ARRAY[1..4] OF CHAR, no length byte): the text before/between/after the four fields of the
		// long date. US: "" Sunday ", " January " " 1 ", " 1990 "". Japan: "" 1990 "年 " 1 "月 " 1 "日 " 日曜日 "".
		String[] st=japanese?new String[]{"","年 ","月 ","日 ",""}:new String[]{"",", "," ",", ",""};
		for(String x:st){byte[] t=e.encodeReplacing(x);int n=Math.min(t.length,4);out.write(t,0,n);for(int i=n;i<4;i++)out.write(0);}
		out.write(japanese?14:0);out.write(1);          // intl1Vers: region, version 1 (no version-7 extension)
		out.write(0x4e);out.write(0x75);                // localRtn: RTS
		return out.toByteArray();
	}
	/**
	 * The Script.h token type of one byte of text, as a U.S. (or, with {@code japanese}, KanjiTalk single-byte) itl4 maps it for
	 * IntlTokenize: white 1 (space, tab), alpha 4 (letters, accented letters, ligatures; Japanese one-byte kana), numeric 5,
	 * new line 6 (CR), the punctuation and symbol tokens by their enum values (double quote = token2Quote 51, single quote =
	 * token1Quote 52, the curly quotes 47-50, the Mac Roman math signs and guillemets), unknown 0 for the rest (control
	 * characters, LF, other symbols). Written from the token list of Script.h and the Mac Roman character set; the real
	 * itl4 table was not copied.
	 */
	static int tokenType(int c,boolean japanese){
		c&=0xff;
		if(c=='\t'||c==' ')return 1;if(c=='\r')return 6;if(c>='0'&&c<='9')return 5;if(c>='A'&&c<='Z'||c>='a'&&c<='z')return 4;
		switch(c){case '(':return 16;case ')':return 17;case '[':return 18;case ']':return 19;case '{':return 20;case '}':return 21;
			case '+':return 24;case '-':return 25;case '*':return 26;case '/':return 29;case '\\':return 30;case '<':return 31;case '>':return 32;case '=':return 33;
			case '!':return 43;case '~':return 44;case ',':return 45;case '.':return 46;case '"':return 51;case '\'':return 52;case ';':return 53;case '%':return 54;
			case '^':return 55;case '_':return 56;case '&':return 57;case '@':return 58;case '|':return 59;case '?':return 60;case ':':return 68;case '#':return 69;case '$':return 70;
			default:break;}
		if(c<0x80)return 0;
		if(japanese)return c>=0xa1&&c<=0xdf?4:c==0xa0?71:0; // one-byte kana; a lead byte is classified with its pair by the tokenizer
		switch(c){
			case 0xad:return 40;case 0xb0:return 67;case 0xb1:return 28;case 0xb2:return 35;case 0xb3:return 37;case 0xb5:return 65;case 0xb7:return 63;case 0xb8:return 66;
			case 0xb9:return 61;case 0xba:return 64;case 0xc3:return 62;case 0xc7:return 22;case 0xc8:return 23;case 0xc9:return 77;case 0xca:return 71;case 0xd2:return 47;
			case 0xd3:return 48;case 0xd4:return 49;case 0xd5:return 50;case 0xd6:return 27;case 0xda:return 72;case 0xdb:return 73;case 0xdc:return 74;case 0xdd:return 75;
			case 0xe1:return 78;case 0xe4:return 76;
			default:break;}
		if(c<=0x9f||c==0xa7||c==0xae||c==0xaf||c==0xbb||c==0xbc||c==0xbe||c==0xbf||c==0xc4||c>=0xcb&&c<=0xcf||c==0xd8||c==0xd9||c==0xde||c==0xdf||c>=0xe5&&c<=0xef||c>=0xf1&&c<=0xf5)return 4;
		return 0;
	}
	/**
	 * itl2 (string comparison, IM Text) in the old format: the init, fetch, vernier and project hook offsets all lead to an RTS and
	 * formatFlag 0 says there are no extended tables - comparing, case and sorting are done natively (IUMagString, UppercaseText).
	 * The script bundle names it (itlbSort): the PowerPC Apple Dylan (MCL) loads the system script's itl2 by that ID at start-up
	 * and stopped with "Can't find :|itl2| 16384 resource".
	 */
	private static byte[] itl2(){return new byte[]{0,10,0,10,0,10,0,10,0,0,0x4e,0x75};}
	/**
	 * NItl4Rec (tokens resource, IM Text): the byte-to-token mapping table, an empty untoken table, the default NumberParts
	 * table and the white-space list. The str/fetch routines are absent (offsets 0): IntlTokenize is answered natively
	 * (Toolbox68k.intlTokenize, with {@link #tokenType}), and AppleScript asks IUGetItlTable for the number parts table (decimal
	 * point, thousands separator) when it initialises and gives up without one (Script Editor: "スクリプティングシステム (OSA) に誤りが起きました").
	 */
	private static byte[] itl4(boolean japanese){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		byte[] map=new byte[256];
		for(int c=0;c<256;c++){int t=tokenType(c,japanese);map[c]=(byte)(japanese&&(c>=0x81&&c<=0x9f||c>=0xe0&&c<=0xfc)?0xff:t);} // Japanese: $FF marks the lead byte of a 2-byte character
		byte[] untoken=new byte[]{0,4,0,0};                       // length 4, no entries
		java.io.ByteArrayOutputStream parts=new java.io.ByteArrayOutputStream();
		// NumberParts: version, data[tokLeftQuote..tokMaxSymbols] as WideChars (leftQuote, rightQuote, leadPlacer, leader,
		// nonLeader, zeroLead, percent, plus, minus, thousands, reserved, separator, escape, decPoint, EPlus, EMinus)
		int[] symbols={0x27,0x27,'^',japanese?0xa0:0xca,'#','0','%','+','-',',',0,';','\\','.','(',')'};
		parts.write(0);parts.write(1);
		for(int i=0;i<31;i++){int v=i<symbols.length?symbols[i]:0;parts.write(v>>8);parts.write(v&0xff);}
		wideCharArr(parts,1,"E+");wideCharArr(parts,1,"E-");wideCharArr(parts,0,"E");   // pePlus, peMinus, peMinusPlus
		if(japanese){parts.write(0);parts.write(9);for(int d=0;d<10;d++){parts.write(0x82);parts.write(0x4f+d);}}else wideCharArr(parts,9,"0123456789"); // altNumTable: the script's digits
		for(int i=0;i<20;i++)parts.write(0);                       // reserved
		byte[] numberParts=parts.toByteArray();
		byte[] white=japanese?new byte[]{0,22,0,3,0,10,0,14,0,18,1,' ',0,0,1,'\t',0,0,2,(byte)0x81,0x40,0}:new byte[]{0,12,0,2,0,8,0,10,1,' ',1,'\t'}; // length, count, offsets, (length, bytes)
		int header=68,mapOff=header,untokenOff=mapOff+256,partsOff=untokenOff+untoken.length,whiteOff=partsOff+numberParts.length;
		word(out,0);for(char c:"itl4".toCharArray())out.write(c);word(out,japanese?JAPANESE_ITL:0);word(out,japanese?0x711:0x700);word(out,1);word(out,0);word(out,0);word(out,0);
		word(out,8);
		long_(out,mapOff);long_(out,0);long_(out,0);long_(out,untokenOff);long_(out,partsOff);long_(out,whiteOff);long_(out,0);long_(out,0);
		word(out,0);word(out,0);word(out,0);word(out,untoken.length);word(out,numberParts.length);word(out,white.length);word(out,0);word(out,0);
		out.write(map,0,256);out.write(untoken,0,untoken.length);out.write(numberParts,0,numberParts.length);out.write(white,0,white.length);
		return out.toByteArray();
	}
	private static void wideCharArr(java.io.ByteArrayOutputStream out,int size,String chars){word(out,size);for(int i=0;i<10;i++){int v=i<chars.length()?chars.charAt(i):0;word(out,v);}}
	/** 'scrn' (Inside Macintosh V, Graphics Devices): count, then per screen spDrvrHw, slot, dCtlDevBase, mode, flagMask, flags
	 *  (screenActive + mainScreen), colorTable, gammaTable (-1: the defaults), the global rect and no control calls. */
	private static byte[] scrn(){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		word(out,1);word(out,0);word(out,0);word(out,0);word(out,0);word(out,0x80);word(out,0x8800);word(out,0x8800);word(out,-1);word(out,-1);
		word(out,0);word(out,0);word(out,480);word(out,640);word(out,0);
		return out.toByteArray();
	}
	private static void word(java.io.ByteArrayOutputStream out,int v){out.write((v>>8)&0xff);out.write(v&0xff);}
	private static void long_(java.io.ByteArrayOutputStream out,int v){word(out,v>>>16);word(out,v&0xffff);}
	private static void fixedPascal(java.io.ByteArrayOutputStream out,StackTextEncoding e,String s,int size){
		byte[] t=e.encodeReplacing(s);int n=Math.min(t.length,size-1);out.write(n);out.write(t,0,n);for(int i=n+1;i<size;i++)out.write(0);
	}
	/** ItlbRecord + ItlbExtRecord (50 bytes): the international resources, language and fonts of a script system. */
	private static byte[] itlb(boolean japanese){
		int itl=japanese?JAPANESE_ITL:0;
		int[] words={
			itl,itl,itl,                                // itlbNumber (itl0), itlbDate (itl1), itlbSort (itl2)
			japanese?0:0x0107,                          // itlbFlags: Roman = smsfAutoInit | smsfUnivExt | smsfSingByte..., Japanese = none
			itl,itl,                                    // itlbToken (itl4), itlbEncoding (itl5)
			japanese?11:0,                              // itlbLang: langEnglish 0, langJapanese 11
			0,                                          // itlbNumRep, itlbDateRep
			japanese?JAPANESE_ITL+1:0,japanese?JAPANESE_ITL+1:0, // itlbKeys (KCHR), itlbIcon (kcs#)
			0,0,                                        // itlbLocalSize (long)
			japanese?JAPANESE_ITL+52:4,japanese?12:9,   // itlbMonoFond/Size: Monaco 9, Osaka－等幅 12
			japanese?JAPANESE_ITL:3,12,                 // itlbPrefFond/Size: Geneva 12, Osaka 12
			japanese?JAPANESE_ITL:3,9,                  // itlbSmallFond/Size
			japanese?JAPANESE_ITL:16383,12,             // itlbSysFond/Size: Chicago 12 (FOND 16383), Osaka 12
			japanese?JAPANESE_ITL:3,12,                 // itlbAppFond/Size
			japanese?JAPANESE_ITL:3,9,                  // itlbHelpFond/Size
			(japanese?0x7b:0x7f)<<8|2};                 // itlbValidStyles, itlbAliasStyle (italic)
		byte[] b=new byte[words.length*2];for(int i=0;i<words.length;i++){b[i*2]=(byte)(words[i]>>8);b[i*2+1]=(byte)words[i];}
		return b;
	}
	/** ItlcRecord (48 bytes): the system script and region. */
	private static byte[] itlc(boolean japanese){
		byte[] b=new byte[48];
		b[1]=(byte)(japanese?1:0);                      // itlcSystem: smRoman 0, smJapanese 1
		b[4]=0;                                         // itlcFontForce
		b[5]=(byte)(japanese?0xff:0);                   // itlcIntlForce: a KanjiTalk system forces its own international resources
		b[6]=0;b[7]=0;                                  // itlcOldKybd, itlcFlags
		b[8]=0;b[9]=40;                                 // itlcIconOffset: keyboard icon 40 pixels from the menu bar's right edge
		b[10]=0;b[11]=0;                                // itlcIconSide, itlcIconRsvd
		b[13]=(byte)(japanese?14:0);                    // itlcRegionCode: verUS 0, verJapan 14
		return b;
	}

	/** A short 'snd ' format 1 resource with a sampled sine beep. */
	/** 'lstr' ID of label 0 ("None"); labels 1-7 follow. */
	static final int LABEL_STRING_ID=-16392;
	private static final String[] LABEL_NAMES={"None","Essential","Hot","In Progress","Cool","Personal","Project 1","Project 2"};
	private static byte[] str31(String text){byte[] b=new byte[32];byte[] t=text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);b[0]=(byte)Math.min(31,t.length);System.arraycopy(t,0,b,1,b[0]);return b;}

	private static byte[] beep(){
		int rate=22050;int samples=2200;
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		out.write(0);out.write(1);out.write(0);out.write(1);out.write(0);out.write(5);out.write(0);out.write(0);out.write(0);out.write(0x80);out.write(0);out.write(1);
		out.write(0x80);out.write(0x51);out.write(0);out.write(0);out.write(0);out.write(0);out.write(0);out.write(20); // bufferCmd, offset 20
		out.write(0);out.write(0);out.write(0);out.write(0); // samplePtr
		out.write(samples>>>24);out.write(samples>>>16);out.write(samples>>>8);out.write(samples);
		int fixedRate=rate<<16;out.write(fixedRate>>>24);out.write(fixedRate>>>16);out.write(fixedRate>>>8);out.write(fixedRate);
		out.write(0);out.write(0);out.write(0);out.write(0);out.write(samples>>>24);out.write(samples>>>16);out.write(samples>>>8);out.write(samples);
		out.write(0);out.write(60);
		for(int i=0;i<samples;i++){double env=Math.min(1.0,Math.min(i/100.0,(samples-i)/300.0));out.write(128+(int)(100*env*Math.sin(i*2*Math.PI*880/rate)));}
		return out.toByteArray();
	}
}

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The generic icon families of the synthetic System file (Icons.h: kGenericDocumentIconResource -4000 and its
 * neighbours): the Finder asks the System for them for every volume, folder and file that has no icon of its own, and
 * other programs plot them with PlotIconID. The pictures are HyperZebra's own, drawn here on a 32-pixel grid (and the
 * same shapes at half size for the small icons); each family gets 'ICN#', 'icl8', 'icl4', 'ics#', 'ics8' and 'ics4'.
 * The special folders (Startup Items, Apple Menu Items, Control Panels, Preferences, Extensions, Fonts) are the folder with
 * a badge of HyperZebra's own design - not Apple's pictures; UxGetFileIcon asks for every one of these IDs.
 */
final class SystemIcons68k {
	private SystemIcons68k(){}

	static final int GENERIC_DOCUMENT=-4000,GENERIC_FOLDER=-3999,FLOPPY=-3998,OPEN_FOLDER=-3997,GENERIC_APPLICATION=-3996,
		GENERIC_HARD_DISK=-3995,TRASH=-3993,DESK_ACCESSORY=-3991,STATIONERY=-3985,FULL_TRASH=-3984,SYSTEM_FOLDER=-3983,
		SUITCASE=-3970,EDITION=-3989,STARTUP_FOLDER=-3981,APPLE_MENU_FOLDER=-3982,CONTROL_PANEL_FOLDER=-3976,
		PREFERENCES_FOLDER=-3974,EXTENSIONS_FOLDER=-3973,FONTS_FOLDER=-3968,GENERIC_PREFERENCES=-3971,EXTENSION=-16415,
		// the System's 'MACS' bundle (FREF/BNDL facts of System 7.5): zsys -16494, INIT -16415, CLIP -16509; the Finder family
		// UxGetFileIcon asks for with GetResource (its creator's icon is in the Finder file itself, but programs look here)
		SYSTEM_SUITCASE=-16494,CLIPBOARD=-16509,FINDER=-16482;

	private static final Color INK=Color.BLACK,PAPER=Color.WHITE,LINES=new Color(0xbbbbbb),FOLDER=new Color(0x9999ff),
		FOLDER_DARK=new Color(0x6666cc),METAL=new Color(0xcccccc),METAL_DARK=new Color(0x888888),DISK=new Color(0x333333),
		LED=new Color(0x00cc00),APP=new Color(0xccccff),TRASH_BODY=new Color(0xdddddd),CASE=new Color(0x996633),
		START=new Color(0x33aa33),BOARD=new Color(0xcc9966),SLIDER=new Color(0xff9933);

	private static final java.util.Map<Integer,Painter> FAMILIES=new java.util.LinkedHashMap<Integer,Painter>();
	static{
		FAMILIES.put(GENERIC_DOCUMENT,SystemIcons68k::document);
		FAMILIES.put(STATIONERY,SystemIcons68k::stationery);
		FAMILIES.put(GENERIC_FOLDER,g->folder(g,false));
		FAMILIES.put(OPEN_FOLDER,g->folder(g,true));
		FAMILIES.put(SYSTEM_FOLDER,SystemIcons68k::systemFolder);
		FAMILIES.put(FLOPPY,SystemIcons68k::floppy);
		FAMILIES.put(GENERIC_HARD_DISK,SystemIcons68k::hardDisk);
		FAMILIES.put(GENERIC_APPLICATION,SystemIcons68k::application);
		FAMILIES.put(TRASH,g->trash(g,false));
		FAMILIES.put(FULL_TRASH,g->trash(g,true));
		FAMILIES.put(DESK_ACCESSORY,SystemIcons68k::deskAccessory);
		FAMILIES.put(SUITCASE,SystemIcons68k::suitcase);
		FAMILIES.put(EDITION,SystemIcons68k::edition);
		FAMILIES.put(GENERIC_PREFERENCES,g->{document(g);toggle(g,11,15);});
		FAMILIES.put(STARTUP_FOLDER,badgedFolder(SystemIcons68k::startBadge));
		FAMILIES.put(APPLE_MENU_FOLDER,badgedFolder(SystemIcons68k::menuBadge));
		FAMILIES.put(CONTROL_PANEL_FOLDER,badgedFolder(SystemIcons68k::sliderBadge));
		FAMILIES.put(PREFERENCES_FOLDER,badgedFolder(g->toggle(g,10,15)));
		FAMILIES.put(EXTENSIONS_FOLDER,badgedFolder(SystemIcons68k::plugBadge));
		FAMILIES.put(FONTS_FOLDER,badgedFolder(SystemIcons68k::letterBadge));
		FAMILIES.put(EXTENSION,SystemIcons68k::extension);
		FAMILIES.put(SYSTEM_SUITCASE,SystemIcons68k::systemSuitcase);
		FAMILIES.put(CLIPBOARD,SystemIcons68k::clipboard);
		FAMILIES.put(FINDER,SystemIcons68k::finder);
	}

	/** Adds every family to the System file (resources marked purgeable, as the System's own). */
	static void addTo(ResourceFile68k f){
		for(java.util.Map.Entry<Integer,Painter> e:FAMILIES.entrySet())add(f,e.getKey(),e.getValue());
		addSmallIcons(f);
	}

	/**
	 * The System's small 1-bit icons 'SICN' -4000..-3985 (System 7: the generic family IDs; some lists hold the icon and its
	 * mask, some the icon alone - the counts are those of a real System file): MegaWindow's SICN objects (`icon,...,SICN,-3997`)
	 * drew nothing without them. The pictures are the families' own at 16 pixels, plus a few stand-ins for IDs no family has.
	 */
	private static void addSmallIcons(ResourceFile68k f){
		Painter document=SystemIcons68k::document;
		Object[][] list={{GENERIC_DOCUMENT,2},{GENERIC_FOLDER,2},{FLOPPY,2},{OPEN_FOLDER,1},{GENERIC_APPLICATION,2},{GENERIC_HARD_DISK,2},
			{PRIVATE_FOLDER,1},{TRASH,2},{DESKTOP,1},{DESK_ACCESSORY,2},{-3990,1},{EDITION,2},{RAM_DISK,2},{CD_ROM,2},{-3986,1},{STATIONERY,1}};
		for(Object[] e:list){int id=(Integer)e[0];
			Painter painter=FAMILIES.get(id);
			if(painter==null)painter=id==PRIVATE_FOLDER?SystemIcons68k::privateFolder:id==DESKTOP?SystemIcons68k::desktop:id==RAM_DISK?SystemIcons68k::ramDisk:id==CD_ROM?SystemIcons68k::cdRom:document;
			byte[] both=iconAndMask(render(painter,16));
			f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("SICN"),id,null,ResourceFile68k.RES_PURGEABLE,(Integer)e[1]==2?both:java.util.Arrays.copyOf(both,32)));
		}
	}
	/** Small-icon-only IDs (Icons.h names): a private folder, the desktop, a RAM disk and a CD-ROM. */
	static final int PRIVATE_FOLDER=-3994,DESKTOP=-3992,RAM_DISK=-3988,CD_ROM=-3987;
	/** A folder with a strap and buckle across it. */
	private static void privateFolder(Pen g){folder(g,false);g.fill(INK,1,16,30,18);g.box(METAL,13,14,18,20);}
	/** A screen on a stand: the desktop. */
	private static void desktop(Pen g){g.box(METAL,2,3,29,23);g.box(PAPER,5,6,26,20);g.box(METAL,10,25,21,29);}
	/** A memory chip with its pins. */
	private static void ramDisk(Pen g){g.box(METAL,4,9,27,23);for(int x=6;x<=24;x+=6){g.line(INK,x,5,x,9);g.line(INK,x,23,x,27);}g.fill(LED,7,13,10,16);}
	/** A disc with its centre hole. */
	private static void cdRom(Pen g){
		g.g.setColor(METAL);g.g.fillOval(g.s(2),g.s(2),g.s(28),g.s(28));g.g.setColor(INK);g.g.drawOval(g.s(2),g.s(2),g.s(28),g.s(28));
		g.g.setColor(PAPER);g.g.fillOval(g.s(12),g.s(12),g.s(8),g.s(8));g.g.setColor(INK);g.g.drawOval(g.s(12),g.s(12),g.s(8),g.s(8));
	}

	private static final java.util.Map<Long,byte[]> SERVED=new java.util.HashMap<Long,byte[]>();
	/**
	 * The icon of a file of the System's own creator 'MACS' for the Desktop Database (PBDTGetIcon): the Finder ('FNDR'),
	 * the System suitcase ('zsys', System 6's 'ZSYS'), font and desk accessory suitcases ('ffil', 'dfil'), extensions ('INIT')
	 * and the Clipboard file ('CLIP'); null for other types. {@code resourceType} is 'ICN#', 'icl8', 'icl4', 'ics#', 'ics8' or 'ics4'.
	 */
	static byte[] systemFileIcon(int fileType,int resourceType){
		int family;
		if(fileType==0x464e4452)family=FINDER;                                  // FNDR
		else if(fileType==0x7a737973||fileType==0x5a535953)family=SYSTEM_SUITCASE; // zsys, ZSYS
		else if(fileType==0x6666696c)family=SUITCASE;                           // ffil
		else if(fileType==0x6466696c)family=DESK_ACCESSORY;                     // dfil
		else if(fileType==0x494e4954)family=EXTENSION;                          // INIT
		else if(fileType==0x434c4950)family=CLIPBOARD;                          // CLIP
		else return null;
		return served(family,FAMILIES.get(family),resourceType);
	}
	/** Keys of {@link #SERVED} for the HyperCard icons (no System resources). */
	private static final int HYPERCARD_APPLICATION=1,HYPERCARD_STACK=2;
	/**
	 * The icons the Desktop Database gives HyperCard's creator 'WILD' (PBDTGetIcon): HyperZebra plays HyperCard here, so the
	 * application ('APPL') and its stacks ('STAK') get pictures of HyperZebra's own design - a pile of cards, and a document with
	 * one on it - without a HyperCard program on the disk (UxGetFileIcon showed stacks with the blank document icon and found
	 * no icon for "APPL"/"WILD"); null for other types.
	 */
	static byte[] hyperCardIcon(int fileType,int resourceType){
		if(fileType==0x4150504c)return served(HYPERCARD_APPLICATION,SystemIcons68k::cardPile,resourceType);  // APPL
		if(fileType==0x5354414b)return served(HYPERCARD_STACK,SystemIcons68k::stackDocument,resourceType);  // STAK
		return null;
	}
	private static byte[] served(int family,Painter painter,int resourceType){
		long key=(long)family<<32|resourceType&0xffffffffL;
		synchronized(SERVED){
			byte[] hit=SERVED.get(key);if(hit!=null)return hit;
			boolean small=(resourceType>>>8&0xff)=='s'; // ics#/ics8/ics4
			BufferedImage image=render(painter,small?16:32);int depth=resourceType&0xff;
			byte[] data=depth=='#'?iconAndMask(image):indexed(image,depth=='8'?8:4);
			SERVED.put(key,data);return data;
		}
	}

	private interface Painter { void paint(Pen g); }

	/** Draws on a 32-unit grid scaled to the icon's size, without anti-aliasing (icons are bitmaps). */
	private static final class Pen {
		final Graphics2D g;final int size;
		Pen(Graphics2D g,int size){this.g=g;this.size=size;}
		int s(int v){return size==32?v:Math.round(v/2f);}
		Polygon poly(int... xy){Polygon p=new Polygon();for(int i=0;i+1<xy.length;i+=2)p.addPoint(s(xy[i]),s(xy[i+1]));return p;}
		/** A filled outlined polygon, corners given in grid units. */
		void shape(Color fill,int... xy){Polygon p=poly(xy);g.setColor(fill);g.fillPolygon(p);g.setColor(INK);g.drawPolygon(p);}
		void box(Color fill,int left,int top,int right,int bottom){shape(fill,left,top,right,top,right,bottom,left,bottom);}
		void fill(Color c,int left,int top,int right,int bottom){g.setColor(c);g.fillRect(s(left),s(top),Math.max(1,s(right)-s(left)),Math.max(1,s(bottom)-s(top)));}
		void line(Color c,int x1,int y1,int x2,int y2){g.setColor(c);g.drawLine(s(x1),s(y1),s(x2),s(y2));}
	}

	private static void document(Pen g){
		g.shape(PAPER,6,1,21,1,27,7,27,31,6,31);
		g.shape(PAPER,21,1,21,7,27,7);
		if(g.size==32)for(int y=11;y<=27;y+=4)g.line(LINES,10,y,23,y);
	}
	private static void stationery(Pen g){
		g.shape(PAPER,9,4,24,4,30,10,30,31,9,31);
		g.shape(PAPER,3,1,18,1,24,7,24,28,3,28);
		g.shape(PAPER,18,1,18,7,24,7);
		if(g.size==32)for(int y=11;y<=23;y+=4)g.line(LINES,7,y,20,y);
	}
	private static void folder(Pen g,boolean open){
		g.shape(open?FOLDER_DARK:FOLDER,1,7,3,5,11,5,13,7,30,7,30,27,1,27);
		if(open){g.shape(FOLDER,1,27,5,13,31,13,27,27);}
		else g.line(INK,1,10,30,10);
	}
	private static void systemFolder(Pen g){
		folder(g,false);
		// a small screen on the folder
		g.box(PAPER,11,13,20,21);g.fill(DISK,13,15,18,19);g.line(INK,13,24,18,24);
	}
	private static void floppy(Pen g){
		g.shape(DISK,3,3,26,3,29,6,29,29,3,29);
		g.box(METAL,9,3,23,12);g.fill(DISK,18,5,21,10);
		g.box(PAPER,7,16,25,29);
		if(g.size==32){g.line(LINES,9,20,23,20);g.line(LINES,9,24,23,24);}
	}
	private static void hardDisk(Pen g){
		g.shape(METAL,1,14,5,10,30,10,30,21,26,25,1,25);
		g.line(INK,1,14,26,14);g.line(INK,26,14,26,25);g.line(INK,26,14,30,10);
		g.fill(LED,4,20,7,22);
		g.line(METAL_DARK,10,19,23,19);
	}
	private static void application(Pen g){
		g.shape(APP,16,1,31,16,16,31,1,16);
		g.shape(PAPER,16,8,24,16,16,24,8,16);
		g.line(INK,13,16,19,16);g.line(INK,16,13,16,19);
	}
	private static void trash(Pen g,boolean full){
		g.shape(TRASH_BODY,8,9,24,9,22,31,10,31);
		for(int x=12;x<=20;x+=4)g.line(METAL_DARK,x,12,x,28);
		if(full){
			g.shape(PAPER,9,8,14,2,20,4,23,8);
			g.shape(METAL,6,6,25,9,24,11,5,8);
		}else{
			g.box(METAL,13,2,19,5);
			g.box(METAL,6,5,26,9);
		}
	}
	private static void deskAccessory(Pen g){
		g.box(PAPER,3,5,28,27);
		g.box(PAPER,3,5,28,10);
		for(int y=6;y<=9;y+=2)g.line(INK,5,y,26,y);
		g.box(METAL,7,14,24,23);
	}
	/** A window with a folder and a document in it: HyperZebra's own picture for the Finder. */
	private static void finder(Pen g){
		g.box(PAPER,2,4,29,28);
		g.box(METAL,2,4,29,9);
		if(g.size==32)for(int y=5;y<=8;y+=2)g.line(METAL_DARK,4,y,27,y);
		g.shape(FOLDER,5,15,6,14,9,14,10,15,15,15,15,24,5,24);
		g.shape(PAPER,18,12,23,12,26,15,26,25,18,25);
	}
	private static void suitcase(Pen g){
		g.box(METAL,12,5,20,9);
		g.box(CASE,3,9,29,28);
		g.line(INK,3,13,29,13);
		g.fill(DISK,9,11,12,15);g.fill(DISK,20,11,23,15);
	}
	/** The suitcase with a small screen on it: the System file. */
	private static void systemSuitcase(Pen g){
		suitcase(g);
		g.box(PAPER,11,16,21,25);g.fill(DISK,13,18,19,23);
	}
	/** A document with two overlapping frames on it (a published and a subscribed part): an edition file. */
	private static void edition(Pen g){
		document(g);
		g.box(FOLDER,9,10,19,19);g.box(PAPER,14,16,24,26);
	}
	/** A plug (the shape of a module that plugs into the system): an extension. */
	private static void extension(Pen g){
		g.fill(INK,10,3,13,11);g.fill(INK,19,3,22,11);
		g.shape(METAL,6,11,26,11,26,22,21,27,11,27,6,22);
		g.fill(LED,9,15,12,18);
		g.line(INK,16,27,16,31);
	}
	/** A board with a clip at the top and a sheet on it: the Clipboard file. */
	private static void clipboard(Pen g){
		g.box(BOARD,5,4,27,31);
		g.box(PAPER,8,8,24,28);
		g.box(METAL,11,1,21,6);
		if(g.size==32)for(int y=12;y<=24;y+=4)g.line(LINES,11,y,21,y);
	}
	/** Three cards on top of each other, the front one striped: HyperZebra's stand-in for the HyperCard application. */
	private static void cardPile(Pen g){
		g.box(PAPER,9,3,30,20);g.box(PAPER,5,7,26,24);g.box(APP,1,11,22,28);
		for(int y=14;y<=24;y+=5)g.fill(INK,4,y,19,y+2);
	}
	/** A document with a small pile of cards on it: a HyperCard stack. */
	private static void stackDocument(Pen g){
		g.shape(PAPER,6,1,21,1,27,7,27,31,6,31);
		g.shape(PAPER,21,1,21,7,27,7);
		g.box(PAPER,12,11,24,20);g.box(PAPER,10,14,22,23);g.box(APP,8,17,20,27);
		g.fill(INK,10,20,18,22);if(g.size==32)g.fill(INK,10,24,18,25);
	}
	/** A folder with a badge on its front that says what it holds. */
	private static Painter badgedFolder(Painter badge){return g->{folder(g,false);badge.paint(g);};}
	/** A start (play) triangle: Startup Items. */
	private static void startBadge(Pen g){g.shape(START,12,13,22,18,12,24);}
	/** A small pulled-down menu: Apple Menu Items. */
	private static void menuBadge(Pen g){
		g.box(PAPER,10,13,22,25);g.fill(DISK,10,13,22,16);
		if(g.size==32){g.line(INK,12,19,20,19);g.line(INK,12,22,18,22);}
	}
	/** Two slider tracks with their knobs: Control Panels. */
	private static void sliderBadge(Pen g){
		g.line(INK,10,16,22,16);g.box(SLIDER,12,13,15,19);
		g.line(INK,10,22,22,22);g.box(SLIDER,17,19,20,25);
	}
	/** An on/off switch at (left, top), 12 by 6 units: preferences. */
	private static void toggle(Pen g,int left,int top){
		g.box(PAPER,left,top,left+12,top+6);g.box(LED,left+6,top,left+12,top+6);
	}
	/** A small plug: Extensions. */
	private static void plugBadge(Pen g){
		g.fill(INK,13,12,15,15);g.fill(INK,18,12,20,15);
		g.box(METAL,11,15,22,22);g.line(INK,16,22,16,25);
	}
	/** A letter A drawn in strokes: Fonts. */
	private static void letterBadge(Pen g){
		g.shape(PAPER,10,13,22,13,22,25,10,25);
		g.line(INK,12,24,16,14);g.line(INK,16,14,20,24);g.line(INK,14,20,18,20);
	}

	// ------------------------------------------------------------------ encoding

	private static void add(ResourceFile68k f,int id,Painter painter){
		BufferedImage large=render(painter,32),small=render(painter,16);
		int attrs=ResourceFile68k.RES_PURGEABLE;
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ICN#"),id,null,attrs,iconAndMask(large)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("icl8"),id,null,attrs,indexed(large,8)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("icl4"),id,null,attrs,indexed(large,4)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ics#"),id,null,attrs,iconAndMask(small)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ics8"),id,null,attrs,indexed(small,8)));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("ics4"),id,null,attrs,indexed(small,4)));
	}

	/** The picture on a transparent background. */
	static BufferedImage render(Painter painter,int size){
		BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
		try{painter.paint(new Pen(g,size));}finally{g.dispose();}
		return image;
	}

	/** 'ICN#' / 'ics#': the 1-bit icon (near-black pixels black) followed by its mask (every painted pixel). */
	private static byte[] iconAndMask(BufferedImage image){
		int size=image.getWidth(),rowBytes=size/8;byte[] out=new byte[2*rowBytes*size];
		for(int y=0;y<size;y++)for(int x=0;x<size;x++){
			int argb=image.getRGB(x,y);if((argb>>>24)==0)continue;
			int bit=0x80>>>(x&7),at=y*rowBytes+x/8;
			out[rowBytes*size+at]|=bit;
			if(luminance(argb)<0x50)out[at]|=bit; // outlines and near-black parts; coloured fills stay white
		}
		return out;
	}

	/** Standard 16-colour table of the classic Mac OS (index 0 white ... 15 black). */
	private static final int[] COLORS4={0xffffff,0xfcf305,0xff6402,0xdd0806,0xf20884,0x4600a5,0x0000d4,0x02abea,
		0x1fb714,0x006411,0x562c05,0x90713a,0xc0c0c0,0x808080,0x404040,0x000000};

	/** 'icl8'/'ics8' (one byte per pixel, system palette) or 'icl4'/'ics4' (two pixels per byte); unpainted pixels are white. */
	private static byte[] indexed(BufferedImage image,int depth){
		int size=image.getWidth();byte[] out=new byte[size*size*depth/8];
		for(int y=0;y<size;y++)for(int x=0;x<size;x++){
			int argb=image.getRGB(x,y);int index=(argb>>>24)==0?0:depth==8?nearest8(argb&0xffffff):nearest(COLORS4,argb&0xffffff);
			int p=y*size+x;
			if(depth==8)out[p]=(byte)index;else out[p/2]|=(byte)((p&1)==0?index<<4:index);
		}
		return out;
	}
	private static int nearest8(int rgb){
		int best=0,bestDistance=Integer.MAX_VALUE;
		for(int i=0;i<256;i++){int d=distance(MacSystemPalette.rgb(i),rgb);if(d<bestDistance){bestDistance=d;best=i;}}
		return best;
	}
	private static int nearest(int[] table,int rgb){
		int best=0,bestDistance=Integer.MAX_VALUE;
		for(int i=0;i<table.length;i++){int d=distance(table[i],rgb);if(d<bestDistance){bestDistance=d;best=i;}}
		return best;
	}
	private static int distance(int a,int b){int dr=(a>>>16&255)-(b>>>16&255),dg=(a>>>8&255)-(b>>>8&255),db=(a&255)-(b&255);return dr*dr+dg*dg+db*db;}
	private static int luminance(int argb){return ((argb>>>16&255)*299+(argb>>>8&255)*587+(argb&255)*114)/1000;}
}

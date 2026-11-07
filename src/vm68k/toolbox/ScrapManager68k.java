import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Classic desk Scrap Manager. For applications and externals of open stacks the scrap is mirrored to the host clipboard
 * in both directions: TEXT travels as a string and PICT as an image (a program's picture is played into an offscreen
 * buffer on the way out; a host image comes in as a 32-bit DirectBitsRect picture), so Copy in a 68K program can be
 * pasted into any host application and the other way round.
 */
final class ScrapManager68k {
	private static final int NO_ERR=0,PARAM_ERR=-50,NO_TYPE_ERR=-102;
	static final int TEXT=0x54455854,PICT=0x50494354;
	/** The host clipboard is looked at this often at most while a program polls InfoScrap/GetScrap; images, which
	 *  cost a decode of the whole picture, are re-read this often unless the text or the set of types changed. */
	private static final long PULL_MS=250,IMAGE_PULL_MS=1000;
	/** A host image wider or taller than this is scaled down before it becomes a picture (well beyond any classic screen). */
	private static final int MAX_IMAGE_SIDE=4095; // a 32-bit PixMap's rowBytes (width*4) must fit 14 bits
	private final M68kMemory memory;
	private final Mac68kApi macApi;
	private final List<Flavor> flavors=new ArrayList<Flavor>();
	private int infoPointer,namePointer,scrapHandle,scrapCount;
	/** The desk scrap exists from the start, empty and on disk (ScrapState 0): under System 7 the Process Manager has set it up
	 *  before any program runs, so LoadScrap/PutScrap work without the program's own ZeroScrap (ResEdit's LoadScrap on opening
	 *  a PICT reported "I/O Error [-100]" when it found ScrapState -1). */
	private boolean inMemory;
	private long lastPull,lastImagePull;
	private byte[] lastHostText;
	private long lastHostImageKey;
	private boolean lastHostHadImage;
	/** The transferable this scrap last put on the host clipboard; while it still owns the clipboard there is nothing to pull. */
	private HostScrap ownContents;
	private volatile boolean ownsHost;

	ScrapManager68k(M68kMemory memory,Mac68kApi macApi){this.memory=memory;this.macApi=macApi;}

	boolean handle(M68kCpu cpu,int trap) throws M68kCpuException {
		switch(trap){
			case 0x9f9:return infoScrap(cpu);
			case 0x9fa:return unloadScrap(cpu);
			case 0x9fb:return loadScrap(cpu);
			case 0x9fc:return zeroScrap(cpu);
			case 0x9fd:return getScrap(cpu);
			case 0x9fe:return putScrap(cpu);
			default:return false;
		}
	}

	private boolean hosted(){Toolbox68k tb=macApi.application();return tb!=null&&tb.hosted();}
	private static Clipboard clipboard(){return java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();}

	private byte[] hostText(Transferable contents){
		try{if(!contents.isDataFlavorSupported(DataFlavor.stringFlavor))return null;
			String text=(String)contents.getTransferData(DataFlavor.stringFlavor);if(text==null)return null;
			return macApi.application().encoding.encodeReplacing(text.replace("\r\n","\r").replace('\n','\r'));
		}catch(Exception e){return null;}
	}
	/** The host clipboard image as opaque RGB (transparent parts over white, as a picture has no alpha). */
	private static BufferedImage hostImage(Transferable contents){
		try{if(!contents.isDataFlavorSupported(DataFlavor.imageFlavor))return null;
			Object data=contents.getTransferData(DataFlavor.imageFlavor);if(!(data instanceof Image))return null;Image image=(Image)data;
			if(!(image instanceof BufferedImage)&&!(image instanceof java.awt.image.MultiResolutionImage))image=new javax.swing.ImageIcon(image).getImage();
			int width=image.getWidth(null),height=image.getHeight(null);if(width<=0||height<=0)return null;
			return opaqueCopy(image,width,height,width,height);
		}catch(Exception e){return null;}
	}
	private static BufferedImage opaqueCopy(Image image,int sourceWidth,int sourceHeight,int width,int height){
		BufferedImage result=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=result.createGraphics();
		g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,width,height);
		if(width!=sourceWidth||height!=sourceHeight){g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,0,0,width,height,null);}
		else g.drawImage(image,0,0,null);
		g.dispose();return result;
	}
	/** Identifies an image's pixels so the same host image is not adopted twice (a 64-bit hash over every pixel). */
	private static long imageKey(BufferedImage image){
		int[] pixels=((DataBufferInt)image.getRaster().getDataBuffer()).getData();long key=(long)image.getWidth()<<32|image.getHeight();
		for(int pixel:pixels)key=key*1_000_003L+(pixel&0xffffff);return key==0?1:key;
	}

	/**
	 * Adopts the host clipboard when another application changed it since the last look: the scrap becomes what the
	 * host holds (TEXT and/or PICT); a change to content the scrap cannot represent leaves the program's own scrap alone.
	 */
	/** Bumped when HyperZebra itself puts a picture on the host clipboard (Copy Picture): the scraps take it at their next look,
	 *  whatever the throttles say (Dartmouth's ClipToPICT right after a second Copy Picture stored the first picture again). */
	static volatile int hostClipboardGeneration;
	private int seenGeneration=-1;
	private void pullFromHost() throws M68kCpuException {
		if(!hosted())return;long now=System.currentTimeMillis();int generation=hostClipboardGeneration;boolean fresh=generation!=seenGeneration;
		if(!fresh&&now-lastPull<PULL_MS)return;lastPull=now;seenGeneration=generation;
		Transferable contents;try{contents=clipboard().getContents(null);}catch(Exception e){return;} // also delivers lostOwnership when the host clipboard changed
		if(contents==null||ownsHost)return;
		byte[] text=hostText(contents);boolean hasImage=contents.isDataFlavorSupported(DataFlavor.imageFlavor);
		boolean textChanged=!Arrays.equals(text,lastHostText),typesChanged=hasImage!=lastHostHadImage;
		BufferedImage image=null;long imageKey=0;
		if(hasImage&&(fresh||textChanged||typesChanged||now-lastImagePull>=IMAGE_PULL_MS)){lastImagePull=now;image=hostImage(contents);if(image==null)hasImage=false;else imageKey=imageKey(image);}
		else if(hasImage)imageKey=lastHostImageKey;
		if(!textChanged&&!typesChanged&&imageKey==lastHostImageKey)return;
		lastHostText=text;lastHostHadImage=hasImage;lastHostImageKey=imageKey;
		if(text==null&&image==null)return;
		flavors.clear();
		if(text!=null)flavors.add(new Flavor(TEXT,text));
		if(image!=null){byte[] picture=pictureOf(image);if(picture!=null){Flavor flavor=new Flavor(PICT,picture);flavor.image=image;flavors.add(flavor);}}
		if(Mac68kApi.TRACE)System.err.println("[68K] scrap <- host clipboard"+(text!=null?" TEXT "+text.length+" bytes":"")+(image!=null?" PICT "+image.getWidth()+"x"+image.getHeight():""));
		inMemory=true;scrapCount=(short)(scrapCount+1);syncScrap();adoptions++;
	}
	/** How often the scrap took the host clipboard over, and that count when the program was last suspended (-1: not suspended). */
	private int adoptions,adoptionsAtSuspend=-1;
	/** The program got a suspend event: what it puts in the scrap from now on (ClarisWorks' own clipboard) is its own change. */
	void suspended(){adoptionsAtSuspend=adoptions;}
	/** At the resume event: whether another program or host application changed the clipboard since the suspend (convertClipboard). */
	boolean changedSinceSuspend() throws M68kCpuException {
		if(adoptionsAtSuspend<0)return false;
		lastPull=0;pullFromHost();boolean changed=adoptions!=adoptionsAtSuspend;adoptionsAtSuspend=-1;return changed;
	}
	/** A host image as a version 2 picture (32-bit DirectBitsRect), scaled down when it would not fit the program's heap. */
	private byte[] pictureOf(BufferedImage image){
		int width=image.getWidth(),height=image.getHeight();
		double scale=Math.min(1.0,Math.min((double)MAX_IMAGE_SIDE/width,(double)MAX_IMAGE_SIDE/height));
		long budget=Math.max(1L<<20,memory.availableBytes()/4L); // the picture, and an unpacked copy the program is likely to make of it
		if((long)width*height*4*scale*scale>budget)scale=Math.min(scale,Math.sqrt((double)budget/((long)width*height*4)));
		if(scale<1.0)image=opaqueCopy(image,width,height,Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)));
		try{return HCResourceExport.pict(image);}catch(Exception e){return null;}
	}

	/** Puts the scrap's TEXT and PICT on the host clipboard (a PICT is played into an image first). */
	private void pushToHost(){
		if(!hosted())return;String text=null;BufferedImage image=null;byte[] textBytes=null;
		for(Flavor flavor:flavors){
			if(flavor.type==TEXT&&text==null){try{text=macApi.application().encoding.decode(flavor.data).replace('\r','\n');textBytes=flavor.data;}catch(Exception ignored){}}
			else if(flavor.type==PICT&&image==null){if(flavor.image==null&&!flavor.rendered){flavor.rendered=true;flavor.image=renderPicture(flavor.data);}image=flavor.image;}
		}
		if(text==null&&image==null)return;
		HostScrap scrap=new HostScrap(text,image);
		try{clipboard().setContents(scrap,scrap);}catch(Exception e){return;}
		if(image!=null)for(Flavor flavor:flavors)if(flavor.type==PICT&&flavor.image==image&&flavor.data.length>=10){ // where its picFrame puts it
			int top=(short)((flavor.data[2]&0xff)<<8|flavor.data[3]&0xff),left=(short)((flavor.data[4]&0xff)<<8|flavor.data[5]&0xff);
			GMenuPaint.externalPicture(scrap,new java.awt.Rectangle(left,top,image.getWidth(),image.getHeight()));break;}
		ownContents=scrap;ownsHost=true;lastHostText=textBytes;lastHostHadImage=image!=null;lastHostImageKey=image!=null?imageKey(image):0;
		if(Mac68kApi.TRACE)System.err.println("[68K] scrap -> host clipboard"+(text!=null?" TEXT "+textBytes.length+" bytes":"")+(image!=null?" PICT "+image.getWidth()+"x"+image.getHeight():""));
	}
	private BufferedImage renderPicture(byte[] picture){
		QuickDraw68k quickDraw=macApi.quickDraw();if(quickDraw==null)return null;
		try{return quickDraw.renderPicture(picture);}
		catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[68K] scrap PICT not rendered for the host clipboard: "+e.getMessage());return null;}
	}

	private boolean infoScrap(M68kCpu cpu) throws M68kCpuException {pullFromHost();ensureInfo();syncInfo();memory.writeLong(cpu.a[7],infoPointer);return true;}
	private boolean zeroScrap(M68kCpu cpu) throws M68kCpuException {flavors.clear();inMemory=true;scrapCount=(short)(scrapCount+1);syncScrap();memory.writeLong(cpu.a[7],NO_ERR);return true;}
	private boolean unloadScrap(M68kCpu cpu) throws M68kCpuException {if(inMemory){if(scrapHandle!=0)macApi.disposeHandle(scrapHandle);scrapHandle=0;inMemory=false;syncInfo();}
		writeScrapFile();memory.writeLong(cpu.a[7],NO_ERR);return true;}
	private boolean loadScrap(M68kCpu cpu) throws M68kCpuException {if(!inMemory){readScrapFile();inMemory=true;syncScrap();}memory.writeLong(cpu.a[7],NO_ERR);return true;}

	/** The scrap file ("Clipboard File", ScrapName) in the System Folder of the startup disk, for applications only. */
	private java.io.File scrapFile(){
		Toolbox68k tb=macApi.application();if(tb==null||tb.app==null||tb.files==null)return null;
		java.io.File folder=tb.files.systemFolder;return folder!=null&&folder.isDirectory()?new java.io.File(folder,"Clipboard File"):null;
	}
	private long writtenModified=-1,writtenLength=-1;
	/**
	 * UnloadScrap writes the scrap to the scrap file (type 'CLIP', creator 'MACS'), as the ROM does: SuperPaint 1.0 looks
	 * the file up right after UnloadScrap (GetFInfo, then SetFInfo CLIP/MACS) and put up "had a problem reading or writing
	 * the file Clipboard File" at every start-up when it was not there.
	 */
	private void writeScrapFile(){
		java.io.File file=scrapFile();if(file==null)return;
		try{java.nio.file.Files.write(file.toPath(),pack());
			FinderInfo68k.write(file,new byte[]{'C','L','I','P','M','A','C','S',0,0,0,0,0,0,0,0});
			writtenModified=file.lastModified();writtenLength=file.length();
		}catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[68K] scrap file not written: "+e);}
	}
	/** LoadScrap reads the scrap file back when the program changed it on disk after UnloadScrap (a program may write it itself). */
	private void readScrapFile(){
		java.io.File file=scrapFile();if(file==null||!file.isFile()||writtenModified<0)return;
		if(file.lastModified()==writtenModified&&file.length()==writtenLength)return;
		try{byte[] data=java.nio.file.Files.readAllBytes(file.toPath());List<Flavor> read=new ArrayList<Flavor>();
			for(int offset=0;offset+8<=data.length;){int type=getLong(data,offset),length=getLong(data,offset+4);
				if(length<0||offset+8+length>data.length)return; // not a scrap: the scrap in memory stays
				read.add(new Flavor(type,Arrays.copyOfRange(data,offset+8,offset+8+length)));offset+=8+length+(length&1);}
			flavors.clear();flavors.addAll(read);scrapCount=(short)(scrapCount+1);writtenModified=file.lastModified();writtenLength=file.length();
		}catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[68K] scrap file not read: "+e);}
	}
	private static int getLong(byte[] data,int offset){return (data[offset]&0xff)<<24|(data[offset+1]&0xff)<<16|(data[offset+2]&0xff)<<8|(data[offset+3]&0xff);}

	private boolean putScrap(M68kCpu cpu) throws M68kCpuException {int source=memory.readLong(cpu.a[7]),type=memory.readLong(cpu.a[7]+4),length=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;int result;
		if(length<0||(length>0&&source==0))result=PARAM_ERR;else {byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)memory.readByte(source+i);flavors.add(new Flavor(type,data));inMemory=true;syncScrap();if(type==TEXT||type==PICT)pushToHost();result=NO_ERR;}
		memory.writeLong(cpu.a[7],result);return true;}
	private boolean getScrap(M68kCpu cpu) throws M68kCpuException {pullFromHost();int offsetPointer=memory.readLong(cpu.a[7]),type=memory.readLong(cpu.a[7]+4),destination=memory.readLong(cpu.a[7]+8);cpu.a[7]+=12;int result=NO_TYPE_ERR,offset=0;
		int cursor=0;for(Flavor flavor:flavors){if(flavor.type==type){offset=cursor+8;result=flavor.data.length;if(destination!=0)macApi.loadHandle(destination,flavor.data);break;}cursor+=8+flavor.data.length+(flavor.data.length&1);}
		if(offsetPointer!=0)memory.writeLong(offsetPointer,result>=0?offset:0);memory.writeLong(cpu.a[7],result);return true;}

	/** The ScrapInfo record is the low-memory globals ScrapSize ($960), ScrapHandle ($964), ScrapCount ($968), ScrapState ($96A)
	 *  and ScrapName ($96C), as on a real Mac: InfoScrap returns their address, and programs (MacApp-style resume checks,
	 *  LMGetScrapCount) read ScrapCount there directly. */
	static final int SCRAP_INFO=0x960;
	void initializeLowMemory() throws M68kCpuException {infoPointer=0;ensureInfo();}
	private void ensureInfo() throws M68kCpuException {if(infoPointer!=0)return;infoPointer=SCRAP_INFO;byte[] name="Clipboard File".getBytes(java.nio.charset.StandardCharsets.US_ASCII);namePointer=memory.allocate(name.length+1,2);memory.writeByte(namePointer,name.length);memory.load(namePointer+1,name);syncInfo();}
	private void syncScrap() throws M68kCpuException {byte[] packed=pack();if(scrapHandle==0)scrapHandle=macApi.createHandle(packed);else macApi.loadHandle(scrapHandle,packed);syncInfo();}
	private void syncInfo() throws M68kCpuException {ensureInfo();memory.writeLong(infoPointer,packedSize());memory.writeLong(infoPointer+4,inMemory?scrapHandle:0);memory.writeWord(infoPointer+8,scrapCount);memory.writeWord(infoPointer+10,inMemory?1:0);memory.writeLong(infoPointer+12,namePointer);}
	private byte[] pack(){byte[] result=new byte[packedSize()];int offset=0;for(Flavor flavor:flavors){putLong(result,offset,flavor.type);putLong(result,offset+4,flavor.data.length);System.arraycopy(flavor.data,0,result,offset+8,flavor.data.length);offset+=8+flavor.data.length+(flavor.data.length&1);}return result;}
	private int packedSize(){int result=0;for(Flavor flavor:flavors)result+=8+flavor.data.length+(flavor.data.length&1);return result;}
	private static void putLong(byte[] data,int offset,int value){data[offset]=(byte)(value>>>24);data[offset+1]=(byte)(value>>>16);data[offset+2]=(byte)(value>>>8);data[offset+3]=(byte)value;}

	static String name(int trap){switch(trap){case 0x9f9:return "InfoScrap";case 0x9fa:return "UnloadScrap";case 0x9fb:return "LoadScrap";case 0x9fc:return "ZeroScrap";case 0x9fd:return "GetScrap";case 0x9fe:return "PutScrap";default:return null;}}
	private static final class Flavor {final int type;final byte[] data;BufferedImage image;boolean rendered;Flavor(int type,byte[] data){this.type=type;this.data=data;}}

	/** What the scrap put on the host clipboard: its TEXT as a string and its PICT as an image. */
	private final class HostScrap implements Transferable,ClipboardOwner {
		private final String text;private final BufferedImage image;
		HostScrap(String text,BufferedImage image){this.text=text;this.image=image;}
		@Override public DataFlavor[] getTransferDataFlavors(){List<DataFlavor> result=new ArrayList<DataFlavor>();if(text!=null)result.add(DataFlavor.stringFlavor);if(image!=null)result.add(DataFlavor.imageFlavor);return result.toArray(new DataFlavor[0]);}
		@Override public boolean isDataFlavorSupported(DataFlavor flavor){return (text!=null&&DataFlavor.stringFlavor.equals(flavor))||(image!=null&&DataFlavor.imageFlavor.equals(flavor));}
		@Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if(text!=null&&DataFlavor.stringFlavor.equals(flavor))return text;if(image!=null&&DataFlavor.imageFlavor.equals(flavor))return image;throw new UnsupportedFlavorException(flavor);}
		/** Another application (or a host window of this one) put something else on the clipboard: the next look pulls it. */
		@Override public void lostOwnership(Clipboard clipboard,Transferable contents){if(ownContents==this)ownsHost=false;GMenuPaint.externalPictureGone(this);}
	}
}

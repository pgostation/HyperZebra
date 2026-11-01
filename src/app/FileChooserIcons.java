import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileView;

/**
 * Classic Mac icons in Swing's file chooser: a file's own custom icon (-16455), a program's icon from its bundle
 * (BNDL/FREF for its own file type: 'APPL', 'cdev', 'INIT'...), a folder's "Icon\r" file and a HyperZebra stack's
 * icon. Everything else keeps the host's icon. The host never shows these itself: macOS draws a classic program
 * (and any file whose Finder info lives in an AppleDouble sidecar) as a blank document, Windows and Linux know none
 * of them. Each icon carries its 16x16 and 32x32 members, so HiDPI screens get the large one.
 */
final class FileChooserIcons extends FileView {
	private FileChooserIcons(){}

	private static final FileChooserIcons INSTANCE=new FileChooserIcons();
	private static final int SIZE=16,CACHE_SIZE=2048;
	private static final Object NONE=new Object();
	private static final Map<String,Object[]> cache=new LinkedHashMap<String,Object[]>(64,0.75f,true){
		@Override protected boolean removeEldestEntry(Map.Entry<String,Object[]> eldest){return size()>CACHE_SIZE;}};

	/** Gives the chooser these icons (no-op when it already has a file view of its own). */
	static void install(JFileChooser chooser){
		if(chooser!=null&&chooser.getFileView()==null)chooser.setFileView(INSTANCE);
	}

	@Override public Icon getIcon(File f){
		if(f==null)return null;
		String key=f.getPath();long modified=f.lastModified(),length=f.isDirectory()?0:f.length();
		synchronized(cache){Object[] hit=cache.get(key);if(hit!=null&&(Long)hit[0]==modified&&(Long)hit[1]==length)return hit[2]==NONE?null:(Icon)hit[2];}
		Icon icon=null;
		try{icon=iconOf(f);}catch(RuntimeException ignored){}
		synchronized(cache){cache.put(key,new Object[]{modified,length,icon==null?NONE:icon});}
		return icon;
	}

	private static Icon iconOf(File f){
		BufferedImage small=null,large=null;
		if(f.isDirectory()){
			if(isStackFolder(f)){small=FinderIcons68k.ofStackBundle(f,16);large=FinderIcons68k.ofStackBundle(f,32);}
			else large=FinderIcons68k.of(f); // the folder's "Icon\r" file
		}
		else if(f.isFile()){
			small=FinderIcons68k.ofCustom(f,16);large=FinderIcons68k.ofCustom(f,32);
			if(small==null&&large==null){
				// the file's own bundle names its icon for its own type; without Finder info, a program's for 'APPL'
				byte[] info=FinderInfo68k.stored(f);
				int type=info!=null&&info.length>=4?MacApp68k.u4(info,0):0;
				if(type==0)type=FinderIcons68k.APPL;
				small=FinderIcons68k.ofDocument(f,type,16);large=FinderIcons68k.ofDocument(f,type,32);
			}
			if(small==null&&large==null&&StackBundle.isPacked(f)){
				File folder=StackBundle.iconFolder(f);
				if(folder!=null&&folder.isDirectory()){
					File png=new File(folder,"icon.png");
					if(png.isFile())try{large=ImageIO.read(png);}catch(IOException ignored){}
					if(large==null){small=FinderIcons68k.ofStackBundle(folder,16);large=FinderIcons68k.ofStackBundle(folder,32);}
				}
			}
		}
		if(small==null&&large==null)return null;
		Image base=scaled(small!=null?small:large,SIZE),hi=scaled(large!=null?large:small,SIZE*2);
		return new ImageIcon(new BaseMultiResolutionImage(base,hi));
	}

	/** A HyperZebra stack kept as a folder: one holding _stack.xml or toc.xml. */
	private static boolean isStackFolder(File f){
		return new File(f,"_stack.xml").isFile()||new File(f,"toc.xml").isFile();
	}

	/** The image at size x size: as it is when it already fits, otherwise scaled smoothly (a 16x16 icon doubles pixel for pixel). */
	private static BufferedImage scaled(BufferedImage image,int size){
		if(image.getWidth()==size&&image.getHeight()==size)return image;
		boolean doubling=image.getWidth()*2==size&&image.getHeight()*2==size;
		BufferedImage out=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g=out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,doubling?RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR:RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		if(doubling)g.drawImage(image,0,0,size,size,null);
		else g.drawImage(image.getScaledInstance(size,size,Image.SCALE_AREA_AVERAGING),0,0,null);
		g.dispose();
		return out;
	}
}

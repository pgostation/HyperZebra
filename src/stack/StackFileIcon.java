import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * The Finder icon of a stack file (.hzb) on macOS. A stack converted from a HyperCard stack that had a custom icon keeps
 * the icon's resources beside _stack.xml ('icns', 'icl8', 'ICN#', 'ics8', 'ics#', 'icl4', 'ics4' -16455 as raw .data
 * files), and a stack may have HyperZebra's own icon.png; the stack file then gets that icon the way the Finder's Paste
 * gives a file one: an 'icns' resource -16455 in its resource fork and kHasCustomIcon in its Finder info. The classic
 * members go into the fork as well, for the Standard File dialogs of 68K programs. Every size macOS asks for is made
 * again from the icon's own pictures (a kept family's 32-bit ones before the 8-bit ones): a kept 'icns' mostly ends at
 * 32 pixels, or holds the 32x32 icon on an empty 128x128. A pixel icon is enlarged by whole multiples for the large and
 * Retina sizes, so it stays crisp instead of being blurred by the Finder.
 * <p>
 * A stack without a custom icon gets nothing (the document icon of the file type), and nothing is written on other hosts
 * or with -Dhz.appledouble=true: an AppleDouble sidecar beside every stack file would only be clutter.
 */
final class StackFileIcon {
	static final int CUSTOM_ICON_ID = -16455;
	private static final int HAS_CUSTOM_ICON = 0x0400; // kHasCustomIcon in fdFlags
	private static final String PNG_ICON = "icon.png";
	private static final String ICNS = "icns";
	/** The classic members of an icon family and the least size of each. */
	private static final String[][] CLASSIC = {
		{ "ICN#", "256" }, { "icl4", "512" }, { "icl8", "1024" }, { "ics#", "64" }, { "ics4", "128" }, { "ics8", "256" } };
	/** The resource fork made for a stack folder, by the folder: {what its icon files were, the fork (or NONE)}. */
	private static final Map<String, Object[]> cache = new LinkedHashMap<String, Object[]>(16, 0.75f, true){
		@Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest){ return size()>16; }
	};
	private static final byte[] NONE = new byte[0];

	private StackFileIcon(){}

	/**
	 * Gives {@code file} - the new archive of the stack in {@code folder}, before it is moved over the stack file - the
	 * stack's custom icon. Nothing happens when the stack has none or the host keeps no resource forks.
	 */
	static void apply(File folder, File file){
		if(!ResourceFile68k.nativeForks()) return;
		byte[] fork = resourceFork(folder);
		if(fork!=null) write(file, fork);
	}

	/**
	 * Gives a stack file that has no custom icon yet (one written before HyperZebra gave stack files their icons) the
	 * custom icon of its stack. A file with a custom icon of its own (pasted in the Finder) keeps it. True when it was
	 * written: the resource fork changes the file's modification time.
	 */
	static boolean applyIfMissing(File folder, File file){
		if(!ResourceFile68k.nativeForks() || file==null || !file.isFile()) return false;
		byte[] info = FinderInfo68k.nativeFinderInfo(file);
		if(info!=null && ((info[8]&0xff)<<8&HAS_CUSTOM_ICON)!=0) return false;
		byte[] fork = resourceFork(folder);
		return fork!=null && write(file, fork);
	}

	private static boolean write(File file, byte[] fork){
		try{
			Files.write(ResourceFile68k.nativeForkFile(file).toPath(), fork);
		}catch(IOException e){
			System.err.println("[stack] could not give "+file.getName()+" its icon: "+e.getMessage());
			return false;
		}
		byte[] info = FinderInfo68k.nativeFinderInfo(file);
		if(info==null) info = new byte[32];
		info[8] |= HAS_CUSTOM_ICON>>8;
		if(!FinderInfo68k.writeNative(file, info)) System.err.println("[stack] could not mark the icon of "+file.getName());
		return true;
	}

	// ------------------------------------------------------------------ the icon

	/** The resource fork holding the custom icon of the stack in {@code folder}, or null when it has none. */
	static byte[] resourceFork(File folder){
		File[] files = folder==null ? null : folder.listFiles();
		if(files==null) return null;
		Map<String, File> icons = new LinkedHashMap<String, File>();
		List<String> stamp = new ArrayList<String>();
		for(File f: files){
			String n = f.getName();
			if(!isIconFile(n) || !f.isFile()) continue;
			icons.put(n, f);
			stamp.add(n+":"+f.length()+":"+f.lastModified());
		}
		if(icons.isEmpty()) return null;
		Collections.sort(stamp);
		String key = folder.getAbsolutePath(), stampText = String.join("\n", stamp);
		synchronized(cache){
			Object[] hit = cache.get(key);
			if(hit!=null && hit[0].equals(stampText)) return hit[1]==NONE ? null : (byte[])hit[1];
		}
		byte[] fork = null;
		try{ fork = build(icons); }
		catch(IOException | RuntimeException e){ System.err.println("[stack] could not read the icon of "+folder.getName()+": "+e); }
		synchronized(cache){
			cache.put(key, new Object[]{ stampText, fork==null ? NONE : fork });
		}
		return fork;
	}

	private static boolean isIconFile(String n){
		if(n.equals(PNG_ICON) || n.equals(ICNS+"_"+CUSTOM_ICON_ID+".data")) return true;
		for(String[] c: CLASSIC) if(n.equals(c[0]+"_"+CUSTOM_ICON_ID+".data")) return true;
		return false;
	}

	private static byte[] build(Map<String, File> icons) throws IOException {
		Map<Integer, byte[]> family = new LinkedHashMap<Integer, byte[]>();
		TreeMap<Integer, BufferedImage> art = new TreeMap<Integer, BufferedImage>();
		BufferedImage png = icons.containsKey(PNG_ICON) ? ImageIO.read(icons.get(PNG_ICON)) : null;
		if(png!=null){
			//HyperZebra's own icon goes before a converted stack's (as in the launcher): its pictures alone, no classic members
			addArt(art, png, false);
		}else{
			//the family the stack file had (Mac OS 8.5 and later), whose 32-bit pictures go before the 8-bit ones
			Map<Integer, byte[]> kept = null;
			File original = icons.get(ICNS+"_"+CUSTOM_ICON_ID+".data");
			if(original!=null && original.length()<(16L<<20)) kept = IconServices68k.parseFamily(Files.readAllBytes(original.toPath()));
			for(String[] c: CLASSIC){
				int type = ResourceFile68k.fourcc(c[0]);
				File f = icons.get(c[0]+"_"+CUSTOM_ICON_ID+".data");
				byte[] b = f!=null && f.length()<=65536 ? Files.readAllBytes(f.toPath()) : kept!=null ? kept.get(type) : null;
				if(b!=null && b.length>=Integer.parseInt(c[1])) family.put(type, b);
			}
			Map<Integer, byte[]> all = new LinkedHashMap<Integer, byte[]>(family);
			if(kept!=null) for(Map.Entry<Integer, byte[]> e: kept.entrySet()) all.putIfAbsent(e.getKey(), e.getValue());
			addArt(art, IconServices68k.picture(all, 16), true);
			addArt(art, IconServices68k.picture(all, 32), true);
			if(all.containsKey(ResourceFile68k.fourcc("it32"))) addArt(art, IconServices68k.picture(all, 128), true);
			for(byte[] b: all.values()){
				if(b.length>8 && (b[0]&0xff)==0x89 && b[1]=='P' && b[2]=='N' && b[3]=='G') addArt(art, ImageIO.read(new java.io.ByteArrayInputStream(b)), true);
			}
		}
		if(art.isEmpty()) return null;
		ResourceFile68k fork = new ResourceFile68k(null);
		for(Map.Entry<Integer, byte[]> e: family.entrySet()) //the classic members on their own as well (68K programs' dialogs)
			fork.add(new ResourceFile68k.Resource(e.getKey(), CUSTOM_ICON_ID, null, 0, e.getValue()));
		family.putAll(modern(art));
		fork.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc(ICNS), CUSTOM_ICON_ID, null, 0, IconServices68k.buildFamily(family)));
		return fork.serialize();
	}

	/**
	 * A picture of the icon by its size. With {@code whole}, one larger than 32 pixels whose drawing fills less than 3/8
	 * of it is left out: the 32x32 icon on an empty 128x128 'it32', as some icon tools of Mac OS 8.5 made it, would be a
	 * small icon at large sizes in the Finder; the 32x32 one enlarged is the icon.
	 */
	private static void addArt(TreeMap<Integer, BufferedImage> art, BufferedImage image, boolean whole){
		if(image==null) return;
		int w = image.getWidth(), h = image.getHeight(), size = Math.max(w, h);
		if(art.containsKey(size)) return;
		if(whole && size>32){
			int left = w, top = h, right = -1, bottom = -1;
			for(int y = 0; y<h; y++) for(int x = 0; x<w; x++){
				if((image.getRGB(x, y)>>>24)==0) continue;
				left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
			}
			if(right<0 || Math.max(right-left+1, bottom-top+1)*8<=size*3) return;
		}
		art.put(size, image);
	}

	/**
	 * The family members macOS draws: 16 and 32 pixel RGB with 8-bit masks ('is32'/'s8mk', 'il32'/'l8mk') and PNGs from
	 * 32 to 1024 pixels ('ic11' is 16 points on a Retina screen, 'ic12' 32 points, 'ic07'... 'ic10' 128 to 512 points),
	 * each made from the icon's own picture of that size, else the next larger one made small, else the largest one
	 * enlarged.
	 */
	private static Map<Integer, byte[]> modern(TreeMap<Integer, BufferedImage> art){
		BufferedImage s16 = at(art, 16), s32 = at(art, 32);
		Map<Integer, byte[]> m = new LinkedHashMap<Integer, byte[]>();
		m.put(ResourceFile68k.fourcc("is32"), rgb(s16));
		m.put(ResourceFile68k.fourcc("s8mk"), alpha(s16));
		m.put(ResourceFile68k.fourcc("il32"), rgb(s32));
		m.put(ResourceFile68k.fourcc("l8mk"), alpha(s32));
		byte[] p256 = png(at(art, 256)), p512 = png(at(art, 512));
		m.put(ResourceFile68k.fourcc("ic11"), png(s32));
		m.put(ResourceFile68k.fourcc("ic12"), png(at(art, 64)));
		m.put(ResourceFile68k.fourcc("ic07"), png(at(art, 128)));
		m.put(ResourceFile68k.fourcc("ic13"), p256);
		m.put(ResourceFile68k.fourcc("ic08"), p256);
		m.put(ResourceFile68k.fourcc("ic14"), p512);
		m.put(ResourceFile68k.fourcc("ic09"), p512);
		m.put(ResourceFile68k.fourcc("ic10"), png(at(art, 1024)));
		return m;
	}

	private static BufferedImage at(TreeMap<Integer, BufferedImage> art, int size){
		Map.Entry<Integer, BufferedImage> source = art.ceilingEntry(size);
		if(source==null) source = art.lastEntry();
		return sized(source.getValue(), size);
	}

	/** The picture in a {@code size} square, kept in proportion: enlarged by a whole multiple pixel for pixel, else smoothly. */
	private static BufferedImage sized(BufferedImage image, int size){
		int w = image.getWidth(), h = image.getHeight();
		if(w==size && h==size) return image;
		double scale = Math.min(size/(double)w, size/(double)h);
		int sw = Math.max(1, (int)Math.round(w*scale)), sh = Math.max(1, (int)Math.round(h*scale));
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		try{
			if(scale<1){
				//area averaging: a large picture made small keeps its lines (one bicubic step would drop them)
				g.drawImage(image.getScaledInstance(sw, sh, Image.SCALE_AREA_AVERAGING), (size-sw)/2, (size-sh)/2, null);
			}else{
				boolean whole = scale==Math.floor(scale);
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, whole
						? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.drawImage(image, (size-sw)/2, (size-sh)/2, sw, sh, null);
			}
		}finally{
			g.dispose();
		}
		return out;
	}

	/** 'is32'/'il32': the red, green and blue planes one after another, each packed in runs as 'icns' packs them. */
	private static byte[] rgb(BufferedImage image){
		int w = image.getWidth(), n = w*image.getHeight();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] plane = new byte[n];
		for(int c = 0; c<3; c++){
			for(int i = 0; i<n; i++) plane[i] = (byte)(image.getRGB(i%w, i/w)>>>(16-8*c));
			packRuns(plane, out);
		}
		return out.toByteArray();
	}

	/** A run of 3 to 130 equal bytes is 0x80+(length-3) and the byte; up to 128 other bytes are length-1 and the bytes. */
	private static void packRuns(byte[] b, ByteArrayOutputStream out){
		int i = 0, n = b.length;
		while(i<n){
			int run = 1;
			while(i+run<n && run<130 && b[i+run]==b[i]) run++;
			if(run>=3){
				out.write(0x80+run-3);
				out.write(b[i]);
				i += run;
				continue;
			}
			int start = i;
			while(i<n && i-start<128 && !(i+2<n && b[i]==b[i+1] && b[i]==b[i+2])) i++;
			out.write(i-start-1);
			out.write(b, start, i-start);
		}
	}

	/** 's8mk'/'l8mk': one byte of opacity per pixel. */
	private static byte[] alpha(BufferedImage image){
		int w = image.getWidth();
		byte[] b = new byte[w*image.getHeight()];
		for(int i = 0; i<b.length; i++) b[i] = (byte)(image.getRGB(i%w, i/w)>>>24);
		return b;
	}

	/** A PNG, with a palette when the picture has 256 colours or fewer (a pixel icon: a quarter of the size, or less). */
	private static byte[] png(BufferedImage image){
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try{ ImageIO.write(indexed(image), "png", out); }
		catch(IOException e){ throw new IllegalStateException(e); }
		return out.toByteArray();
	}

	private static BufferedImage indexed(BufferedImage image){
		int w = image.getWidth(), h = image.getHeight();
		int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
		Map<Integer, Integer> index = new LinkedHashMap<Integer, Integer>();
		for(int p: pixels){
			if((p>>>24)==0) p = 0; //every clear pixel is one entry
			if(!index.containsKey(p)){
				if(index.size()==256) return image;
				index.put(p, index.size());
			}
		}
		int n = index.size();
		byte[] r = new byte[n], g = new byte[n], b = new byte[n], a = new byte[n];
		for(Map.Entry<Integer, Integer> e: index.entrySet()){
			int p = e.getKey(), i = e.getValue();
			a[i] = (byte)(p>>>24); r[i] = (byte)(p>>16); g[i] = (byte)(p>>8); b[i] = (byte)p;
		}
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, new java.awt.image.IndexColorModel(8, n, r, g, b, a));
		byte[] data = ((java.awt.image.DataBufferByte)out.getRaster().getDataBuffer()).getData();
		for(int i = 0; i<pixels.length; i++){
			int p = pixels[i];
			data[i] = (byte)(int)index.get((p>>>24)==0 ? 0 : p);
		}
		return out;
	}
}

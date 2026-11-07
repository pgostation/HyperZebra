import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Decoded card and background pictures. Every "go" used to decode the card's and the background's picture files
 * again (PNG decoding was most of the time of a card change in stacks with pictures, and the background's picture is
 * the same for all its cards).
 *
 * An entry is used only while the file has the size and modification time it was decoded from; HyperZebra's own
 * writes of these files also drop the entry (invalidate). {@link #read} hands out a copy to draw into. A card showing its
 * picture takes {@link #readShared}: a new image per card change was uploaded to the graphics card again each time, and a
 * shared one stays there (what changes such a picture takes {@link #writable} first). Pictures of the kinds shown are kept
 * in the form the graphics pipeline takes fastest (see {@link #displayForm}).
 */
final class PictureCache {
	interface Loader {
		BufferedImage load(File file) throws IOException;
	}

	private static final long MAX_BYTES = 64L * 1024 * 1024; //512x342 のカードの絵なら90枚ほど
	private static final Object NONE = new Object();
	private static final Set<String> SHOWN = Set.of("imageio", "pbm");

	private static final class Entry {
		final Object image; //BufferedImage, or NONE when the loader found no picture in the file
		final long modified, length, bytes;
		Entry(Object image, long modified, long length) {
			this.image = image;
			this.modified = modified;
			this.length = length;
			this.bytes = image instanceof BufferedImage b ? (long)b.getWidth()*b.getHeight()*4 : 64;
		}
	}

	private static final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>(32, 0.75f, true);
	private static long bytes;
	/** Images handed out by readShared (also after their entry has gone: a card may still show them). */
	private static final Set<BufferedImage> shared = Collections.newSetFromMap(new WeakHashMap<BufferedImage, Boolean>());

	private PictureCache() {}

	/** loader(file) through the cache; kind tells loaders of the same file apart (e.g. "pbm" and "imageio"). */
	static BufferedImage read(File file, String kind, Loader loader) throws IOException {
		BufferedImage image = load(file, kind, loader);
		return image==null ? null : copy(image);
	}

	/** The cached picture itself: to show, not to change. */
	static BufferedImage readShared(File file, String kind, Loader loader) throws IOException {
		BufferedImage image = load(file, kind, loader);
		if(image!=null) synchronized(shared){ shared.add(image); }
		return image;
	}

	/** The picture to draw into: itself, or a copy when it came from readShared. */
	static BufferedImage writable(BufferedImage image) {
		return isShared(image) ? copy(image) : image;
	}

	static boolean isShared(BufferedImage image) {
		synchronized(shared){ return shared.contains(image); }
	}

	private static BufferedImage load(File file, String kind, Loader loader) throws IOException {
		BasicFileAttributes attributes;
		try{
			attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
		}catch(IOException | RuntimeException e){
			return loader.load(file); //無いファイルなどはそのまま読み込み側に任せる(従来どおりの結果・例外)
		}
		if(!attributes.isRegularFile()) return loader.load(file);
		if(kind.equals("imageio")){
			BufferedImage waiting = PictureSaver.pending(file);
			if(waiting!=null) return copy(waiting); //not shared: the next save brings another one
		}
		long modified = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
		long length = attributes.size();
		String key = kind+'\0'+file.getAbsolutePath();
		synchronized(PictureCache.class){
			Entry entry = entries.get(key);
			if(entry!=null && entry.modified==modified && entry.length==length){
				return entry.image instanceof BufferedImage b ? b : null;
			}
		}
		BufferedImage image = loader.load(file);
		if(image!=null && SHOWN.contains(kind)) image = displayForm(image);
		store(key, new Entry(image!=null ? image : NONE, modified, length));
		return image;
	}

	/**
	 * The same pixels in the form that reaches the screen fastest: no alpha for a picture without transparent pixels, and
	 * premultiplied alpha when pixels are only opaque or clear, as in a HyperCard picture. The first drawing of a new image of
	 * 1024x768 on the Metal pipeline took 3.8 ms from the 4BYTE_ABGR of a decoded PNG, 0.3 ms from these. Translucent pixels
	 * would be rounded by premultiplying: those pictures stay unpremultiplied.
	 */
	static BufferedImage displayForm(BufferedImage image) {
		int w = image.getWidth(), h = image.getHeight();
		int[] row = new int[w];
		boolean opaque = true, translucent = false;
		for(int y=0; y<h && !translucent; y++){
			image.getRGB(0, y, w, 1, row, 0, w);
			for(int argb : row){
				int alpha = argb>>>24;
				if(alpha==255) continue;
				opaque = false;
				if(alpha!=0){
					translucent = true;
					break;
				}
			}
		}
		int type = opaque ? BufferedImage.TYPE_INT_RGB : translucent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_ARGB_PRE;
		if(image.getType()==type) return image;
		BufferedImage out = new BufferedImage(w, h, type);
		for(int y=0; y<h; y++){
			image.getRGB(0, y, w, 1, row, 0, w);
			out.setRGB(0, y, w, 1, row, 0, w);
		}
		return out;
	}

	/** The picture just written to the file (not changed afterwards): the next read need not decode it. */
	static void put(File file, String kind, BufferedImage image) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
		BufferedImage kept = SHOWN.contains(kind) ? displayForm(image) : image;
		store(kind+'\0'+file.getAbsolutePath(), new Entry(kept, attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.size()));
	}

	private static void store(String key, Entry entry) {
		if(entry.bytes>MAX_BYTES) return;
		synchronized(PictureCache.class){
			Entry previous = entries.put(key, entry);
			if(previous!=null) bytes -= previous.bytes;
			bytes += entry.bytes;
			Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
			while(bytes>MAX_BYTES && it.hasNext()){
				Map.Entry<String, Entry> oldest = it.next();
				if(oldest.getValue()==entry) continue;
				bytes -= oldest.getValue().bytes;
				it.remove();
			}
		}
	}

	/** After HyperZebra has written the file. */
	static void invalidate(File file) {
		if(file==null) return;
		PictureSaver.cancel(file);
		String suffix = '\0'+file.getAbsolutePath();
		synchronized(PictureCache.class){
			Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
			while(it.hasNext()){
				Map.Entry<String, Entry> e = it.next();
				if(e.getKey().endsWith(suffix)){ bytes -= e.getValue().bytes; it.remove(); }
			}
		}
	}

	static synchronized void clear() {
		entries.clear();
		bytes = 0;
	}

	/** A picture with its own pixels, of the same kind (colour model, layout) as the original. */
	static BufferedImage copy(BufferedImage source) {
		switch(source.getType()){
			case BufferedImage.TYPE_INT_RGB: case BufferedImage.TYPE_INT_ARGB: case BufferedImage.TYPE_INT_ARGB_PRE:
			case BufferedImage.TYPE_INT_BGR: case BufferedImage.TYPE_3BYTE_BGR: case BufferedImage.TYPE_4BYTE_ABGR:
			case BufferedImage.TYPE_4BYTE_ABGR_PRE: {
				//同じ種類の画像への Src での描画は画素のそのままの複写(Raster.copyData は画素ごとの汎用処理で、
				//PNG の読み込み結果の 4BYTE_ABGR ではデコードと同じくらい時間がかかっていた)
				BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());
				Graphics2D g = copy.createGraphics();
				g.setComposite(AlphaComposite.Src);
				g.drawImage(source, 0, 0, null);
				g.dispose();
				return copy;
			}
			default:
				break;
		}
		ColorModel model = source.getColorModel();
		WritableRaster raster = source.copyData(source.getRaster().createCompatibleWritableRaster());
		return new BufferedImage(model, raster, model.isAlphaPremultiplied(), null);
	}
}

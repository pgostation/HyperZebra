import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Decoded card and background pictures. Every "go" used to decode the card's and the background's picture files
 * again (PNG decoding was most of the time of a card change in stacks with pictures, and the background's picture is
 * the same for all its cards).
 *
 * An entry is used only while the file has the size and modification time it was decoded from; HyperZebra's own
 * writes of these files also drop the entry (invalidate). Callers get a copy, since card pictures are drawn into
 * (the paint tools, drag with the paint tools from a script).
 */
final class PictureCache {
	interface Loader {
		BufferedImage load(File file) throws IOException;
	}

	private static final long MAX_BYTES = 64L * 1024 * 1024; //512x342 のカードの絵なら90枚ほど
	private static final Object NONE = new Object();

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

	private PictureCache() {}

	/** loader(file) through the cache; kind tells loaders of the same file apart (e.g. "pbm" and "imageio"). */
	static BufferedImage read(File file, String kind, Loader loader) throws IOException {
		BasicFileAttributes attributes;
		try{
			attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
		}catch(IOException | RuntimeException e){
			return loader.load(file); //無いファイルなどはそのまま読み込み側に任せる(従来どおりの結果・例外)
		}
		if(!attributes.isRegularFile()) return loader.load(file);
		long modified = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
		long length = attributes.size();
		String key = kind+'\0'+file.getAbsolutePath();
		synchronized(PictureCache.class){
			Entry entry = entries.get(key);
			if(entry!=null && entry.modified==modified && entry.length==length){
				return entry.image instanceof BufferedImage b ? copy(b) : null;
			}
		}
		BufferedImage image = loader.load(file);
		Entry entry = new Entry(image!=null ? image : NONE, modified, length);
		if(entry.bytes<=MAX_BYTES) synchronized(PictureCache.class){
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
		return image==null ? null : copy(image);
	}

	static BufferedImage read(String path, String kind, Loader loader) throws IOException {
		return path==null ? null : read(new File(path), kind, loader);
	}

	/** After HyperZebra has written the file. */
	static synchronized void invalidate(File file) {
		if(file==null) return;
		String suffix = '\0'+file.getAbsolutePath();
		Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
		while(it.hasNext()){
			Map.Entry<String, Entry> e = it.next();
			if(e.getKey().endsWith(suffix)){ bytes -= e.getValue().bytes; it.remove(); }
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

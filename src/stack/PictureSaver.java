import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Card and background pictures on their way to the stack folder. A script that paints leaves the paint tools again and
 * again (choose brush tool, drag, choose browse tool, in a game once a frame); PNG encoding took longer than the painting and
 * ran on the event thread. Here the newest picture of a file waits a moment on a thread of its own, so that a run of saves
 * writes the file once.
 *
 * Until it is written the picture is what the file holds: PictureCache.read hands it out. What reads the stack folder itself
 * calls {@link #flush} first (StackBundle.sync, StackSaving.copyBundle, CardClipboard, StackProtection); what deletes or writes
 * such a file calls {@link #cancel} (PictureCache.invalidate does). A file that does not exist yet is written at once: the
 * loaders look for the file before they ask the cache.
 */
final class PictureSaver {
	private static final long QUIET_NANOS = 250_000_000L;  //no newer picture for this long: write
	private static final long LONGEST_NANOS = 2_000_000_000L; //a game that never stops: still written this often

	private static final class Job {
		final OStack stack;
		final File file;
		final long since;
		BufferedImage image;
		long last;
		Job(OStack stack, File file, BufferedImage image, long now) {
			this.stack = stack;
			this.file = file;
			this.image = image;
			since = last = now;
		}
	}

	private static final Object lock = new Object();
	private static final LinkedHashMap<String, Job> waiting = new LinkedHashMap<String, Job>();
	private static Job writing;
	private static boolean hurry;
	private static Thread worker;

	private PictureSaver() {}

	/** Saves a copy of the picture as it is now (the caller goes on changing its own). */
	static void save(OStack stack, BufferedImage image, File file) throws IOException {
		if(!file.exists()){
			cancel(file);
			StackProtection.writeImage(stack, image, file);
			PictureCache.invalidate(file);
			return;
		}
		BufferedImage copy = PictureCache.copy(image);
		long now = System.nanoTime();
		synchronized(lock){
			Job job = waiting.get(key(file));
			if(job!=null){
				job.image = copy;
				job.last = now;
			}
			else waiting.put(key(file), new Job(stack, file, copy, now));
			if(worker==null) start();
			lock.notifyAll();
		}
	}

	/** The picture waiting for this file (no copy: not to be changed), null when the file is up to date. */
	static BufferedImage pending(File file) {
		synchronized(lock){
			Job job = waiting.get(key(file));
			if(job!=null) return job.image;
			return writing!=null && writing.file.equals(file.getAbsoluteFile()) ? writing.image : null;
		}
	}

	/** Returns when everything saved so far is in its file. */
	static void flush() {
		synchronized(lock){
			if(waiting.isEmpty() && writing==null) return;
			hurry = true;
			lock.notifyAll();
			boolean interrupted = false;
			while(!waiting.isEmpty() || writing!=null){
				try{ lock.wait(); }
				catch(InterruptedException e){ interrupted = true; }
			}
			hurry = false;
			if(interrupted) Thread.currentThread().interrupt();
		}
	}

	/** The file is being replaced or deleted by someone else: what waits for it is dropped. */
	static void cancel(File file) {
		synchronized(lock){
			waiting.remove(key(file));
			boolean interrupted = false;
			while(writing!=null && writing.file.equals(file.getAbsoluteFile())){
				try{ lock.wait(); }
				catch(InterruptedException e){ interrupted = true; }
			}
			if(interrupted) Thread.currentThread().interrupt();
		}
	}

	private static String key(File file) {
		return file.getAbsolutePath();
	}

	private static void start() {
		worker = new Thread(PictureSaver::run, "picture saver");
		worker.setDaemon(true);
		worker.start();
		Runtime.getRuntime().addShutdownHook(new Thread(PictureSaver::flush, "pictures at quit"));
	}

	private static void run() {
		for(;;){
			Job job;
			synchronized(lock){
				job = next();
				writing = job;
			}
			try{
				write(job);
			}catch(IOException | RuntimeException e){
				System.err.println("picture "+job.file+" could not be saved: "+e);
			}
			synchronized(lock){
				writing = null;
				lock.notifyAll();
			}
		}
	}

	/** Waits for a job that is due (lock held). */
	private static Job next() {
		boolean interrupted = false;
		for(;;){
			long now = System.nanoTime(), wait = Long.MAX_VALUE;
			Job due = null;
			for(Map.Entry<String, Job> e : waiting.entrySet()){
				Job job = e.getValue();
				long left = Math.min(job.last+QUIET_NANOS-now, job.since+LONGEST_NANOS-now);
				if(hurry || left<=0){
					due = job;
					break;
				}
				wait = Math.min(wait, left);
			}
			if(due!=null){
				waiting.remove(key(due.file));
				if(interrupted) Thread.currentThread().interrupt();
				return due;
			}
			try{
				if(wait==Long.MAX_VALUE) lock.wait();
				else lock.wait(wait/1_000_000+1);
			}catch(InterruptedException e){ interrupted = true; }
		}
	}

	private static void write(Job job) throws IOException {
		XMLwrite.writeAtomically(job.file, StackProtection.encodeImage(job.stack, job.image, job.file));
		PictureCache.put(job.file, "imageio", job.image); //the file reads back as this picture: no decoding it again
	}
}

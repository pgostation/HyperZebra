import java.awt.Font;
import java.awt.Image;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;

/**
 * What start-up needs but can do beside the main thread, begun before anything else: the first use of a font reads every
 * font the system has (0.2 s on a Mac), and the first image read starts ImageIO (0.1 s). The main thread only waits for
 * what is not finished by the time it gets there.
 */
final class StartupWork {
	private static FutureTask<Image> icon;
	private static CompletableFuture<Void> fonts;

	private StartupWork(){}

	/** On the main thread, after the properties AWT reads when it starts. */
	static void start(){
		icon = new FutureTask<Image>(StartupWork::readIcon);
		daemon(icon, "start-up: icon");
		fonts = new CompletableFuture<Void>();
		daemon(() -> {
			new Font(Font.SANS_SERIF, Font.PLAIN, 13).getFamily();
			StartupTrace.mark("fonts read");
			fonts.complete(null);
		}, "start-up: fonts");
	}

	/** Runs on the EDT once the system's fonts are read, so that a window can be set up without waiting for them. */
	static void whenFontsRead(Runnable work){
		if(fonts==null) work.run();
		else fonts.thenRun(() -> javax.swing.SwingUtilities.invokeLater(work));
	}

	/** The application's icon (resource/icon.png), or null. */
	static Image icon(){
		if(icon==null) return readIcon();
		try{
			return icon.get();
		}catch(InterruptedException | java.util.concurrent.ExecutionException e){
			return null;
		}
	}

	private static Image readIcon(){
		File file = new File("resource"+File.separatorChar+"icon.png");
		if(!file.isFile()) return null;
		try{
			return javax.imageio.ImageIO.read(file);
		}catch(java.io.IOException e){
			System.err.println("Could not load application icon: "+e.getMessage());
			return null;
		}
	}

	private static void daemon(Runnable work, String name){
		Thread t = new Thread(work, name);
		t.setDaemon(true);
		t.start();
	}
}

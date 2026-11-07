import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HyperZebra{

	public static void main(final String[] args) throws Exception {
		StartupTrace.mark("main");
		if(relaunchWithCorrectWorkingDirectoryIfNeeded(args)){
			return;//子プロセス側で本体が動くので、ここでは何もしない
		}

		//GMenuやPCARDのクラスの初期化はAWTを起動して時間がかかるので、StartupWorkを始めるまでは触らない
		boolean mac = System.getProperty("os.name","").startsWith("Mac");
		if(mac){
			//Property
			System.setProperty("com.apple.mrj.application.apple.menu.about.name",
					"HyperZebra");//about box

			System.setProperty("apple.laf.useScreenMenuBar", "true");
			System.setProperty("com.apple.macos.useScreenMenuBar", "true");
			//System.setProperty("com.apple.macos.smallTabs", "true");

			System.setProperty("apple.awt.rendering","speed");
			System.setProperty("apple.awt.graphics.UseQuartz","false");

		}
		StartupWork.start();
		//jpackageのアプリはバンドルのアイコンで出る。要るのはjava -jarと、再起動した子プロセス
		if(mac && (System.getProperty("jpackage.app-path")==null || System.getenv("HYPERZEBRA_RELAUNCHED")!=null)) setDockIcon();

		PCARD.main(args);
    }

	//再起動した子プロセスはLaunchServicesを通らずに実行ファイルとして起動されるため、macOSはアプリバンドルの
	//アイコンを使わず、Dockに汎用の「exec」アイコンを出してしまう。バンドルの.icnsの元画像(resource/icon.png)で
	//Dockアイコンを差し替える(java -jarでの起動でもJavaのコーヒーカップの代わりにこのアイコンになる)。
	private static void setDockIcon(){
		Thread t = new Thread(new Runnable(){ public void run(){
			try{
				if(!java.awt.Taskbar.isTaskbarSupported()) return;
				java.awt.Taskbar taskbar = java.awt.Taskbar.getTaskbar();
				if(!taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) return;
				java.awt.Image icon = StartupWork.icon();
				if(icon!=null) taskbar.setIconImage(icon);
			} catch(Throwable t){
				System.err.println("[dock] cannot set the Dock icon: "+t);
			}
		}}, "start-up: Dock icon");
		t.setDaemon(true);
		t.start();
	}

	//このアプリはresource/やhomeなど、多くのファイルをカレントディレクトリ
	//(相対パス)からの"./resource/…"のような形で読み込んでいる。jpackageで
	//書き出したアプリをFinder(や、macOS以外のランチャー)から直接起動すると、
	//OSがプロセスに渡すカレントディレクトリはアプリ本体の場所とは無関係な値
	//(例えば"/"など)になることが多く、これらの読み込みが軒並み失敗してしまう。
	//
	//JavaはJVM起動後にOSレベルのカレントディレクトリを変更する手段を持たない
	//(java.io.Fileの相対パスはプロセス起動時のOSのカレントディレクトリに
	//固定されており、System.setProperty("user.dir",...)では変わらない)。
	//jpackageのアプリは起動オプション-Duser.dir=$APPDIRで絶対パスの基準を"app"フォルダにしてあるので、
	//OS側はNativeChdir(Java 22以降のForeign Function API)で移す(changeDirectoryInPlace)。
	//できないときは、正しいディレクトリ(jpackageアプリの"app"フォルダ)を突き止めた上で、
	//そこをカレントディレクトリに指定してjpackage生成のランチャー自身を
	//子プロセスとして再起動することで解決する(JVMの起動が2回になるぶん遅い)。
	//
	//通常のjava -jarでの起動(既にresource/が見えている場合)や、jpackage化
	//されていない環境では即falseを返し、何もしない。
	private static boolean relaunchWithCorrectWorkingDirectoryIfNeeded(String[] args) {
		if(new File("resource").isDirectory()){
			return false;//既に正しい場所から起動されている
		}
		if(System.getenv("HYPERZEBRA_RELAUNCHED")!=null){
			return false;//念のための無限再起動防止
		}

		String appPath = System.getProperty("jpackage.app-path");
		if(appPath==null){
			return false;//jpackageでパッケージされたアプリではない
		}

		File appDir = findAppDirectory(new File(appPath));
		if(appDir==null){
			return false;//見つけられなかった。通常通り起動を試みる(失敗するかもしれない)
		}
		if(changeDirectoryInPlace(appDir)){
			return false;
		}

		try{
			List<String> command = new ArrayList<String>();
			command.add(appPath);
			for(String arg: args) command.add(arg);

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(appDir);
			pb.inheritIO();
			pb.redirectInput(ProcessBuilder.Redirect.PIPE);//FinderからのOpen Documentsを子プロセスへ渡す道(forwardOpenedFiles)
			pb.environment().put("HYPERZEBRA_RELAUNCHED", "1");
			Process p = pb.start();
			if(GMenu.isMacOSX()) forwardOpenedFiles(p);
			System.exit(p.waitFor());
		} catch(IOException | InterruptedException e){
			e.printStackTrace();
			return false;//再起動に失敗した場合は、諦めて通常通り起動を試みる
		}
		return true;//ここには来ない(System.exitで終了済み)
	}

	//このプロセスのままカレントディレクトリをdirへ移す。-Duser.dirが同じ場所を指しているときだけ
	//(java.io.FileやPathの絶対パスはuser.dirを基準にするので、OS側だけ移すと食い違う)
	private static boolean changeDirectoryInPlace(File dir){
		try{
			if(!dir.getCanonicalPath().equals(new File(System.getProperty("user.dir","")).getCanonicalPath())) return false;
			Object changed = Class.forName("NativeChdir").getMethod("chdir", String.class).invoke(null, dir.getPath());
			return Boolean.TRUE.equals(changed) && new File("resource").isDirectory();
		} catch(ReflectiveOperationException | IOException | LinkageError e){
			return false;//NativeChdirが無い(Java 21以前)、または呼べない: 再起動する
		}
	}

	//Finderで.hzbなどをダブルクリックして起動したとき、macOSは開く書類を起動引数ではなくApple Event(Open Documents)で、
	//LaunchServicesが起動したこのプロセス(親)に送ってくる。本体は再起動した子プロセスなので、親で受け取って
	//子の標準入力へ1行1パスで渡す(子はPCARD.readOpenedFilesFromParentで読む)。親はDockに出ないUIElementにする。
	private static void forwardOpenedFiles(final Process child){
		try{
			System.setProperty("apple.awt.UIElement", "true");
			if(!java.awt.Desktop.isDesktopSupported()) return;
			java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
			if(!desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE)) return;
			final java.io.Writer out = new java.io.OutputStreamWriter(child.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
			desktop.setOpenFileHandler(new java.awt.desktop.OpenFilesHandler(){
				public void openFiles(java.awt.desktop.OpenFilesEvent e){
					synchronized(out){
						try{
							for(File f: e.getFiles()) out.write(f.getAbsolutePath()+"\n");
							out.flush();
						} catch(IOException ex){
							System.err.println("[open] cannot pass "+e.getFiles()+" to HyperZebra: "+ex);
						}
					}
				}
			});
		} catch(Throwable t){
			System.err.println("[open] no Open Documents handler: "+t);
		}
	}

	//jpackageのランチャー実行ファイルの場所から、jar・リソース類が入っている
	//"app"フォルダを探す(macOS: Contents/MacOS/名前 → Contents/app、
	//Windows: 名前\名前.exe → 名前\app、Linux: 名前/bin/名前 → 名前/lib/app)。
	private static File findAppDirectory(File launcher){
		File dir = launcher.getParentFile();
		if(dir==null) return null;
		File parentDir = dir.getParentFile();

		File[] candidates = {
			parentDir==null ? null : new File(parentDir, "app"),//macOS
			new File(dir, "app"),//Windows
			parentDir==null ? null : new File(parentDir, "lib"+File.separatorChar+"app"),//Linux
		};
		for(File candidate: candidates){
			if(candidate!=null && new File(candidate, "resource").isDirectory()){
				return candidate;
			}
		}
		return null;
	}

    //@SuppressWarnings({ "deprecation", "restriction" })
	static
    void installMacHandler(){
		/*if(GMenu.isMacOSX()){
			try{
			//Mac Application Menu
			com.apple.eawt.Application fApplication = com.apple.eawt.Application.getApplication();
			fApplication.setEnabledPreferencesMenu(true);
			fApplication.addApplicationListener(
				new com.apple.eawt.ApplicationAdapter() {
			
					@Override
					public void handleAbout(com.apple.eawt.ApplicationEvent e) {
					//showAbout();
						//new GDialog(null, AppName+" "+PCARD.longVersion,
						//		null,"OK",null,null);
						try {
							TTalk.doScriptforMenu("about this");
						} catch (xTalkException e2) {
							e2.printStackTrace();
						}
					e.setHandled(true);
					}
			
					@Override
					public void handleOpenApplication(
					com.apple.eawt.ApplicationEvent e) {
						//new GDialog(null, "handleOpenApplication"+e.getFilename(),
								//null,"OK",null,null);
					}
			
					@Override
					public void handleOpenFile(com.apple.eawt.ApplicationEvent e) {
						try {
							TTalk.doScriptforMenu("open stack "+TTalk.quoteLiteral(e.getFilename()));
						} catch (xTalkException e1) {
							try {
								TTalk.doScriptforMenu("edit picture "+TTalk.quoteLiteral(e.getFilename()));
							} catch (xTalkException e2) {
								e2.printStackTrace();
							}
						}
					}
			
					@Override
					public void handlePreferences(
					com.apple.eawt.ApplicationEvent e) {
					//doPreference();
						//new GDialog(null, "handlePreferences",
								//null,"OK",null,null);
					}
			
					@Override
					public void handlePrintFile(
					com.apple.eawt.ApplicationEvent e) {
						new GDialog(null, "handlePrintFile",
								null,"OK",null,null);
					}
			
					@Override
					public void handleQuit(com.apple.eawt.ApplicationEvent e) {
						try {
							GMenuBrowse.doMenu("Quit HyperCard");
						} catch (xTalkException e1) {
							e1.printStackTrace();
						}
						//processExit();
					}
				}
			);
			}catch(Exception e){
				
			}
		}*/
    }
}
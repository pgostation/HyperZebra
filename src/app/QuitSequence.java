import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * HyperZebra's own Quit, done the way the Finder's Shut Down does it: the running programs are asked to quit one at a
 * time, each in turn, then the open stack is closed and its changes written (a failed write asks, StackSaving). A program
 * may take as long as it needs - its "Save changes?" waits for the user however long that takes - and nothing is ever
 * killed on a timer:
 * <ul>
 * <li>a program that cancels (its dialog answered Cancel, or its 'quit' handler returns userCanceledErr) stops the whole
 *     quit, and the programs not asked yet keep running;</li>
 * <li>after a moment a small window names the program being waited for and what it is doing (waiting for an answer in
 *     its dialog, quitting, not responding), with "Force Quit…" (after a confirmation, for that program) and "Don't Quit"
 *     (stops HyperZebra's quit; the program that was asked may still finish quitting on its own).</li>
 * </ul>
 * HyperZebra's own helper programs (services: the QuickTime VM for XCMDs) carry no user data and are stopped after a short wait.
 */
final class QuitSequence {
	private static final AtomicBoolean running = new AtomicBoolean();
	/** The quit request of macOS (logout, the Dock's Quit) to answer when this quit stops; null otherwise. */
	private static volatile java.awt.desktop.QuitResponse response;
	/** The window shown while a program is waited for (its "Don't Quit" sets {@link #userCancelled}). */
	private static volatile WaitWindow window;
	private static volatile boolean userCancelled;
	/** How long a program is waited for before the window comes up. */
	static final long WINDOW_AFTER_MS = 1500;
	/** What ends the process at the end (tests replace it). */
	static volatile Runnable exitAction = new Runnable(){ public void run(){ System.exit(0); }};

	private QuitSequence(){}

	/** Whether a quit is under way. */
	static boolean inProgress(){ return running.get(); }

	/** Starts quitting (once: a second request while one runs brings its window to the front). Returns at once. */
	static void start(java.awt.desktop.QuitResponse quitResponse){
		if(!running.compareAndSet(false, true)){
			if(quitResponse!=null) quitResponse.cancelQuit(); //進行中の終了がある: そちらに任せる
			WaitWindow w = window;
			if(w!=null) SwingUtilities.invokeLater(new Runnable(){ public void run(){ w.toFront(); }});
			return;
		}
		response = quitResponse;
		userCancelled = false;
		PCARD.exiting = true;
		Thread t = new Thread(new Runnable(){ public void run(){ runQuit(); }}, "HyperZebra quit");
		t.start();
	}

	private static void runQuit(){
		try{
			//プログラムを1つずつ(Finderの「システム終了」と同じ)。待っている間に新しく起動したものにも尋ねる
			for(MacApp68k app = nextProgram(); app!=null; app = nextProgram()){
				String outcome = quit(app);
				if(outcome!=null){ stop(outcome); return; }
			}
			//スタックは最後に: プログラムが終了を取り消したときに、閉じかけのスタックを残さない
			if(!closeStack()){
				stop(text("The stack could not be saved, so HyperZebra did not quit."));
				return;
			}
			for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && app.service) quitService(app);
			closeWindow();
			exitAction.run();
		}catch(RuntimeException e){
			e.printStackTrace();
			stop(text("HyperZebra did not quit because of an error."));
		}
	}

	/**
	 * The open stack: its close messages run on the script thread, then its changes are written; false when a write
	 * failed and the user kept the stack open. A script thread busy with a long-running script for 10 seconds does not
	 * hold the quit up: the changes are then written from here, without the close messages.
	 */
	private static boolean closeStack(){
		final PCARD pc = PCARD.pc;
		if(pc==null || pc.stack==null || pc.stack.curCard==null) return true;
		final CompletableFuture<Boolean> done = new CompletableFuture<Boolean>();
		final AtomicBoolean started = new AtomicBoolean();
		TTalk.runOnScriptThread(new Runnable(){ public void run(){
			if(!started.compareAndSet(false, true)) return; //間に合わなかった: こちら側で保存済み
			try{ done.complete(PCARD.prepareStackClose(StackSaving.Purpose.QUIT)); }
			catch(Throwable e){ e.printStackTrace(); done.complete(StackSaving.ensureSaved(pc.stack, StackSaving.Purpose.QUIT)); }
		}});
		try{
			return done.get(10, TimeUnit.SECONDS);
		}catch(java.util.concurrent.TimeoutException e){
			if(started.compareAndSet(false, true)) return StackSaving.ensureSaved(pc.stack, StackSaving.Purpose.QUIT);
			try{ return done.get(); }catch(Exception e2){ return false; } //閉じる処理が動いている(保存の確認中など): 待つ
		}catch(Exception e){
			return StackSaving.ensureSaved(pc.stack, StackSaving.Purpose.QUIT);
		}
	}

	/** The next running program to ask (not a helper service), or null when none is left. */
	private static MacApp68k nextProgram(){
		for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished && !app.service) return app;
		return null;
	}

	/** Asks one program to quit and waits for it; null when it quit, else why the quit stops. */
	private static String quit(MacApp68k app){
		app.requestQuit();
		long start = System.currentTimeMillis();
		while(!app.finished){
			if(userCancelled) return text("You stopped quitting HyperZebra.");
			EventManager68k.QuitProgress progress = app.quitProgress();
			if(progress==EventManager68k.QuitProgress.CANCELLED)
				return text("\"%s\" cancelled quitting, so HyperZebra did not quit.").replace("%s", app.name);
			if(System.currentTimeMillis()-start>=WINDOW_AFTER_MS) showWindow(app, progress);
			try{ Thread.sleep(100); }catch(InterruptedException e){ return text("You stopped quitting HyperZebra."); }
		}
		return null;
	}

	/** One of HyperZebra's own helper programs: asked, then stopped if it has not gone after three seconds. */
	private static void quitService(MacApp68k app){
		app.requestQuit();
		long deadline = System.currentTimeMillis()+3000;
		while(!app.finished && System.currentTimeMillis()<deadline){
			try{ Thread.sleep(50); }catch(InterruptedException e){ break; }
		}
		if(!app.finished){ System.err.println("[68K "+app.name+"] helper did not quit; stopping it"); app.kill(); }
	}

	/** The quit stops: HyperZebra goes on, macOS hears that the quit was cancelled, the launcher says why. */
	private static void stop(String why){
		closeWindow();
		for(MacApp68k app: MacApp68k.runningApplications()) if(!app.finished) app.withdrawQuit(); //待っていたダイアログが閉じた後に終了しないように
		PCARD.exiting = false;
		java.awt.desktop.QuitResponse r = response;
		response = null;
		if(r!=null) try{ r.cancelQuit(); }catch(RuntimeException ignored){}
		running.set(false);
		System.err.println("[quit] "+why);
		GLauncher.quitStopped(why);
	}

	// ------------------------------------------------------------------ the window

	private static void showWindow(final MacApp68k app, final EventManager68k.QuitProgress progress){
		SwingUtilities.invokeLater(new Runnable(){ public void run(){
			if(!running.get()) return;
			if(window==null) window = new WaitWindow();
			window.show(app, progress);
		}});
	}
	private static void closeWindow(){
		SwingUtilities.invokeLater(new Runnable(){ public void run(){
			WaitWindow w = window;
			window = null;
			if(w!=null){ w.setVisible(false); w.dispose(); }
		}});
	}

	/** "Waiting for … to quit": what the program is doing, Force Quit… and Don't Quit. Never takes the focus from the program's dialog. */
	private static final class WaitWindow extends JDialog {
		private static final long serialVersionUID = 1L;
		private final JLabel title = new JLabel(), detail = new JLabel();
		private final JButton force = new JButton(text("Force Quit…")), dontQuit = new JButton(text("Don't Quit"));
		private MacApp68k app;
		/** Programs already forced (their force quit takes up to three seconds). */
		private final List<MacApp68k> forced = new ArrayList<MacApp68k>();

		WaitWindow(){
			super((java.awt.Frame)null, text("Quitting HyperZebra"), false);
			setAutoRequestFocus(false);
			setAlwaysOnTop(false);
			setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
			JPanel content = new JPanel(new BorderLayout(0, 8));
			content.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
			JPanel texts = new JPanel(new BorderLayout(0, 4));
			title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
			texts.add(title, BorderLayout.NORTH);
			texts.add(detail, BorderLayout.CENTER);
			content.add(texts, BorderLayout.CENTER);
			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
			buttons.add(force);
			buttons.add(dontQuit);
			content.add(buttons, BorderLayout.SOUTH);
			setContentPane(content);
			force.addActionListener(e -> {
				MacApp68k target = app;
				if(target==null || target.finished) return;
				//確認してから止める(強制終了はこの明示的な操作だけ。時間切れで止めることはない)
				if(GAppInfoWindow.confirmForceQuit(this, target)){ forced.add(target); force.setEnabled(false); }
			});
			dontQuit.addActionListener(e -> { userCancelled = true; dontQuit.setEnabled(false); });
		}

		void show(MacApp68k app, EventManager68k.QuitProgress progress){
			this.app = app;
			title.setText(text("Waiting for \"%s\" to quit").replace("%s", app.name));
			String d;
			switch(progress){
				case WAITING_FOR_USER: d = text("It is waiting for your answer in its window (for example, whether to save changes)."); break;
				case NOT_RESPONDING: d = text("It is not responding. It may still be saving; you can wait, or force it to quit."); break;
				default: d = text("It is quitting…"); break;
			}
			detail.setText("<html><div style='width:300px'>"+d+"</div></html>");
			force.setEnabled(!forced.contains(app));
			if(!isVisible()){
				pack();
				Rectangle screen = getGraphicsConfiguration()!=null ? getGraphicsConfiguration().getBounds() : new Rectangle(0, 0, 1024, 768);
				setLocation(screen.x+screen.width-getWidth()-24, screen.y+48); //プログラムのダイアログを隠さないよう右上に
				setVisible(true);
			}else pack();
		}
	}

	private static String text(String english){
		try{ if(PCARD.pc!=null && PCARD.pc.intl!=null) return PCARD.pc.intl.getDialogText(english); }catch(RuntimeException ignored){}
		return english;
	}
}

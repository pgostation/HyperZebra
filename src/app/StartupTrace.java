/**
 * Start-up timing for -Dhz.startup.trace=true: each mark prints the milliseconds since the process started (stderr).
 * -Dhz.startup.exit=N ends the run N ms after the launcher is painted (jpackage.sh records its AOT cache in such a run).
 */
final class StartupTrace {
	private static final boolean ON = Boolean.getBoolean("hz.startup.trace") || System.getProperty("hz.startup.exit")!=null;
	private static final long START = ON ? ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis()) : 0;
	private static boolean framePainted, contentsPainted;

	private StartupTrace(){}

	static void mark(String what){
		if(ON) System.err.println("[startup] "+(System.currentTimeMillis()-START)+" ms "+what+" @"+System.currentTimeMillis());
	}

	/** A launcher window was painted, with its contents or not yet: with them is the time a user waits. */
	static void painted(boolean contents){
		if(!ON || (contents ? contentsPainted : framePainted)) return;
		if(contents) contentsPainted = true; else framePainted = true;
		mark(contents ? "launcher painted" : "window painted");
		long exitAfter = Long.getLong("hz.startup.exit", -1);
		if(!contents || exitAfter<0) return;
		Thread exit = new Thread(() -> {
			try{ Thread.sleep(exitAfter); }catch(InterruptedException e){ return; }
			Runtime.getRuntime().halt(0);
		});
		exit.setDaemon(true);
		exit.start();
	}
}

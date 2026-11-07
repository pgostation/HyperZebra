import java.util.concurrent.atomic.AtomicLong;

/**
 * The open stack's speed limit (Individual Stack Settings: Speed): HyperTalk, the card display and the stack's XCMDs/XFCNs
 * run at about the speed HyperCard had on a Macintosh of the time, for stacks whose animations and waits were timed by
 * how fast the machine they were made on ran (they flash by on HyperZebra, which runs HyperTalk several hundred times faster).
 * <p>HyperZebra's own speed is no measure of how long something took in HyperCard - a statement is cheap here, a card
 * change comparatively dear - so the limit is a model of HyperCard's costs, not a slowed-down HyperZebra: every statement,
 * loop pass and card-window redraw is charged what it cost real HyperCard in 68K instructions, and the script thread waits
 * until the machine's rate ({@link #mips}, million instructions a second, the 68K VM's preset scale) has paid for it. The
 * constants are fitted to real machines with the four tests of the "HC速度計" stack (script, card moves, icons, field text;
 * see tests/StackSpeedTest): HyperCard J-2.2 on a 68LC040 at 33 MHz (about 30 MIPS, {@link #REFERENCE_MIPS}) printed
 * 44/27/37/28. A real Mac ran HyperCard far slower than its MIPS suggest (SANE arithmetic, the ROM's Toolbox, memory
 * traffic); the 68K VM charges the same (Toolbox68k.trapCost, Toolbox68k.WEIGHT), and HyperCard 2.2 there gives these scores
 * at the same rate too, so a stack's XCMDs keep their pace against its scripts.
 * <p>Drawing does not keep up with the processor on faster machines (the video bus): the display costs are paid at
 * {@link #displayRate}, which grows as the rate to the power {@link #DISPLAY_EXPONENT} from the reference machine. A Power
 * Macintosh G3 at 333 MHz printed 900/228/480/220 with HyperCard J-2.3 PowerPC, which this gives at about 600 MIPS; a
 * Performa 6210 (603 at 80 MHz) 80/35/39/37, about 55 MIPS here (its display slower still: a fast processor on a slow bus).
 * XCMDs/XFCNs run in the 68K VM, which Toolbox68k paces to {@link #externalMips} itself.
 * <p>Games that move their buttons in "on idle" go as fast as idle comes: HyperCard sends it at most 40 times a second
 * ({@link #IDLE_INTERVAL_NANOS}, Pidle keeps to it under a limit) and its event loop costs {@link #IDLE} each time.
 * <p>The pace is a token bucket like the 68K VM's: the costs add up to a deadline, the script thread parks when it is ahead
 * of the wall clock, and time spent waiting (wait, answer, a visual effect) counts as elapsed - no burst afterwards.
 */
final class StackSpeed {
	private StackSpeed(){}

	/** The open stack's rate in million 68K instructions a second, 0 = unlimited (the standard). */
	static volatile double mips;
	/** -Dhz.stack.mips=N: every stack runs at N (0 = unlimited) whatever its settings say, for measurements. */
	private static final double FORCED = forced();
	private static double forced(){
		String v = System.getProperty("hz.stack.mips");
		try{ return v==null ? -1 : Math.max(0, Double.parseDouble(v)); }catch(NumberFormatException e){ return -1; }
	}

	/** The rate of the machine the costs are fitted to: a 68LC040 at 33 MHz. */
	static final double REFERENCE_MIPS = 30;
	/** How the display's speed grows with the rate: drawing at 20 times the reference rate is about 8 times as fast. */
	static final double DISPLAY_EXPONENT = 0.7;
	// The costs in instructions at the reference machine's rate. Their proportions came from HyperCard 2.2 in the 68K VM.
	/** A statement (or a control line: if, repeat, exit, ...): the dispatch, plus each of its words evaluated. */
	static final int STATEMENT = 9100, WORD = 2200;
	/** A pass through a block: one repeat loop iteration, an if branch, a handler body. */
	static final int BLOCK = 4400;
	/** A redraw of part of the card window (a part changed): HyperCard's compositing of the layers there... */
	static final int REDRAW = 110000;
	/** ...and the copy to the screen, per card pixel. Not paid under lock screen (HyperCard draws its offscreen card only). */
	static final double PIXEL = 11.7;
	/** Field text set by a script (put into a field): TextEdit's layout, plus each character laid out. */
	static final int FIELD_TEXT = 287000, CHARACTER = 80;
	/** A message that reaches a handler: HyperCard looks it up and sets up the handler's parameters and locals. */
	static final int HANDLER = 7300;
	/** send: the message is compiled from its text first. */
	static final int SEND = 13900;
	/** A pass of HyperCard's event loop that sends idle: WaitNextEvent, the cursor, the message along the message path. */
	static final int IDLE = 117000;
	/** HyperCard sends idle at most once in 1.5 ticks (its WaitNextEvent sleeps a tick): 40 a second in VM HyperCard 2.2 at any rate. */
	static final long IDLE_INTERVAL_NANOS = 25_000_000L;

	/** -Dhz.stack.speedtrace=true prints each redraw charged. */
	static final boolean TRACE = Boolean.getBoolean("hz.stack.speedtrace");
	/** The display costs charged by other threads (the EDT redraws) since the script thread last paid. */
	private static final AtomicLong pending = new AtomicLong();
	/** Where the paced time stands (System.nanoTime terms). */
	private static long deadline;
	/** When the script thread last paid: display requests within {@link #SCRIPT_RECENT_NANOS} of it are the script's doing. */
	private static volatile long lastStatement;
	private static final long SCRIPT_RECENT_NANOS = 50_000_000L;
	/** The wall time a script may run ahead after a wait (as the 68K VM's throttle). */
	private static final long SLACK_NANOS = 20_000_000L;

	/** The rate in force: the stack's own, or -Dhz.stack.mips. */
	static double rate(){ return FORCED>=0 ? FORCED : mips; }
	/** The display's rate at the stack's rate (see {@link #DISPLAY_EXPONENT}). */
	static double displayRate(){ double r = rate(); return r<=0 ? 0 : REFERENCE_MIPS*Math.pow(r/REFERENCE_MIPS, DISPLAY_EXPONENT); }
	/** What a display cost weighs at the stack's rate: the processor's instructions that take as long as it. */
	static double displayScale(){ double r = rate(); return r<=0 ? 1 : Math.pow(r/REFERENCE_MIPS, 1-DISPLAY_EXPONENT); }
	/** Whether the open stack runs at a limited speed. */
	static boolean limited(){ return rate()>0; }

	/** The stored speed of a stack: realSpeed=true is the 68040 preset (as programs keep it), mips any other rate, else unlimited. */
	static double rateOf(LauncherStore.AppSettings s){
		if(s==null) return 0;
		if(s.mips!=null) return s.mips;
		return Boolean.TRUE.equals(s.realSpeed) ? Toolbox68k.DEFAULT_MIPS : 0;
	}
	/** The open stack's settings changed (or it closed: null). */
	static void set(LauncherStore.AppSettings s){
		double r = rateOf(s);
		if(r!=mips){ mips = r; synchronized(StackSpeed.class){ deadline = 0; } pending.set(0); }
	}

	/** The costs charged by other threads and not paid yet (instructions). */
	static long unpaid(){ return pending.get(); }

	/** The rate an XCMD/XFCN of the open stack runs at in the 68K VM: the stack's limit, else {@code shared} (the VM setting). */
	static double externalMips(double shared){ double r = rate(); return r>0 ? r : shared; }

	/** A statement of {@code words} words is about to run (the script thread). */
	static void statement(int words){ pay(STATEMENT+(long)WORD*words); }
	/** A block is entered: a loop iteration, an if branch, a handler. */
	static void block(){ pay(BLOCK); }
	/** A control line's test of {@code words} words is evaluated again: a repeat while/until before each pass, an else if. */
	static void test(int words){ pay((long)WORD*words); }
	/** A message reaches a handler. */
	static void handler(){ pay(HANDLER); }
	/** A send statement compiles its message. */
	static void send(){ pay(SEND); }
	/** HyperCard's event loop sends an idle message. */
	static void idle(){ pay(IDLE); }
	/**
	 * The statement running changes nothing on the screen (set icon to the icon it has, set loc to where it is): HyperCard
	 * draws nothing for it, so the redraws HyperZebra asks for meanwhile are free.
	 */
	static void unchanged(){
		if(!limited() || javax.swing.SwingUtilities.isEventDispatchThread()) return;
		synchronized(LOCK){ drawnFree = true; }
	}
	/** A script set the text of a field to {@code length} characters. */
	static void fieldText(int length){ if(limited()) pending.addAndGet((long)(displayScale()*(FIELD_TEXT+(long)CHARACTER*length))); }

	/**
	 * The card window redraws {@code r} (card window pixels, {@code scale} = the zoom; CardRepaintManager, any thread). Charged
	 * while a script runs (a request soon after a statement is its doing); under lock screen only the compositing, not the copy
	 * to the screen. Swing asks for one change more than once (a button's icon: the button, then its rectangle and a pixel
	 * round it; a field and its text pane, again after the layout), and HyperCard drew it once: what overlaps a region already
	 * charged for the same statement only pays for its new pixels. A request that comes while the script thread waits before
	 * its next statement is still the last statement's (the next one has not run yet).
	 */
	static void redraw(java.awt.Rectangle r, double scale, boolean locked){
		if(!limited() || r.isEmpty() || System.nanoTime()-lastStatement>SCRIPT_RECENT_NANOS) return;
		long cost;
		synchronized(LOCK){
			java.util.ArrayList<java.awt.Rectangle> list = waiting ? previous : drawn;
			if(waiting ? previousFree : drawnFree) return;
			java.awt.Rectangle merged = null;
			long overlap = 0;
			for(java.awt.Rectangle d : list){
				java.awt.Rectangle i = d.intersection(r);
				if(!i.isEmpty() && (long)i.width*i.height>overlap){ overlap = (long)i.width*i.height; merged = d; }
			}
			double fresh = ((double)r.width*r.height-overlap)/(scale*scale);
			cost = (long)(displayScale()*((merged==null ? REDRAW : 0)+(locked ? 0 : PIXEL*fresh)));
			if(merged!=null) merged.add(r);
			else if(list.size()<64) list.add(new java.awt.Rectangle(r));
		}
		if(TRACE) System.err.println("[speed] redraw "+r.width+"x"+r.height+(locked?" locked":"")+(waiting?" late":"")+" cost "+cost);
		pending.addAndGet(cost);
	}
	/** The regions charged for the statement running (card window pixels), and for the one before it. */
	private static java.util.ArrayList<java.awt.Rectangle> drawn = new java.util.ArrayList<java.awt.Rectangle>(), previous = new java.util.ArrayList<java.awt.Rectangle>();
	/** The statement running, and the one before it, changed nothing on the screen ({@link #unchanged}). */
	private static boolean drawnFree, previousFree;
	private static final Object LOCK = new Object();
	/** The script thread is waiting for the machine before its next statement. */
	private static volatile boolean waiting;

	/** Adds {@code cost} instructions and what other threads charged, and parks until the machine would have run them. */
	private static void pay(long cost){
		double r = rate();
		if(r<=0) return;
		long now = System.nanoTime();
		lastStatement = now;
		if(javax.swing.SwingUtilities.isEventDispatchThread()){ pending.addAndGet(cost); return; } // paid by the script thread
		synchronized(LOCK){ // the statement before is done: what it draws from now on is late, the next statement's is its own
			java.util.ArrayList<java.awt.Rectangle> t = previous; previous = drawn; drawn = t; drawn.clear();
			previousFree = drawnFree; drawnFree = false;
			waiting = true;
		}
		try{
			cost += pending.getAndSet(0);
			long wait;
			synchronized(StackSpeed.class){
				deadline = Math.max(deadline, now-SLACK_NANOS)+(long)(cost*1000/r);
				wait = deadline-now;
			}
			while(wait>=500_000L && !TTalk.stop){ // Command-period still stops a slow script at once
				java.util.concurrent.locks.LockSupport.parkNanos(Math.min(wait, 20_000_000L));
				wait = deadlineNow()-System.nanoTime();
			}
		}finally{ waiting = false; }
		lastStatement = System.nanoTime();
	}
	private static synchronized long deadlineNow(){ return deadline; }
}

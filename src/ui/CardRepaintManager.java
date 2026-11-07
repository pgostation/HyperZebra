import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Repaint requests for the card (PCARD.pc.mainPane and its parts).
 *
 * Scripts change parts from the script thread through PCARD.runOnEDT, one EDT task per change. Each change used to
 * paint the card before the next change could start (the paint event is queued ahead of the next task), so a loop of
 * "set the loc" / "set icon" / "put … into fld" ran at the speed of full paints, far more often than a display shows.
 * Here the card is painted at most once per frame: a request after a quiet period goes straight to Swing, and so does
 * what follows until that paint runs (a move asks for the old and the new place one after the other: one paint, not one for
 * each, the part gone from the screen in between); requests within FRAME_NANOS after that are merged and handed over
 * together when the frame ends.
 *
 * While the screen is locked the card shows the snapshot taken when the lock began (MyPanel.paint draws VEffect.oldoff),
 * or, for lock screen without a picture (VEffect.lazyLock), the parts themselves, which keep the locked state, or, while a card is rebuilt by go without a visual effect (VEffect.freezeScreen), simply what was painted last:
 * requests made during the lock are dropped. Every way out of the lock repaints the whole card (VEffect.visualEffect,
 * TTalk.returnToIdle, OStack), so nothing is lost. A 68K external's drawing on the card window shows during the lock as well
 * ({@link #repaintThroughLock}).
 */
class CardRepaintManager extends RepaintManager {
	static final long FRAME_NANOS = 8_000_000L;

	/** Merged requests waiting for the end of the frame, by component. */
	private final Map<JComponent, Rectangle> pending = new LinkedHashMap<JComponent, Rectangle>();
	private long lastPass = System.nanoTime() - FRAME_NANOS;
	private boolean flushScheduled;
	/** Requests went to Swing at lastPass and its paint has not run yet. */
	private boolean paintQueued;
	private final Timer flushTimer;

	CardRepaintManager() {
		flushTimer = new Timer(1, e -> flush());
		flushTimer.setRepeats(false);
	}

	/** Installs the manager (on the EDT); keeps one that is already a CardRepaintManager (tests may subclass it). */
	static void install() {
		Runnable task = () -> {
			if(!(RepaintManager.currentManager(null) instanceof CardRepaintManager)){
				RepaintManager.setCurrentManager(new CardRepaintManager());
			}
		};
		if(SwingUtilities.isEventDispatchThread()) task.run();
		else SwingUtilities.invokeLater(task);
	}

	@Override
	public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
		JComponent card = PCARD.pc!=null ? PCARD.pc.mainPane : null;
		if(card==null || c==null || w<=0 || h<=0 || !(c==card || SwingUtilities.isDescendingFrom(c, card))){
			super.addDirtyRegion(c, x, y, w, h);
			return;
		}
		//スタック個別設定の速度制限: スクリプトによる再描画は当時のHyperCardの描画時間を払う(ロック中は画面への転送なし)
		if(StackSpeed.limited()) StackSpeed.redraw(cardRegion(card, c, x, y, w, h), PCARD.scale(), PCARD.lockedScreen && !PCARD.capturingEffect);
		//lock screen 中はロック開始時の画面(VEffect.oldoff)が描かれるだけで、ロック後の変更は解除まで見せない。
		//効果なしのカード移動(VEffect.freezeScreen、絵なし)では描かないことで直前の画面を残す
		if(PCARD.lockedScreen && !PCARD.capturingEffect) return;
		boolean pass;
		synchronized(this){
			long now = System.nanoTime();
			if(paintQueued && now-lastPass<FRAME_NANOS){
				pass = true; //Swing paints it together with what it has
			}
			else if(!flushScheduled && now-lastPass>=FRAME_NANOS){
				lastPass = now;
				paintQueued = true;
				pass = true;
			}
			else{
				Rectangle r = pending.get(c);
				if(r==null) pending.put(c, new Rectangle(x, y, w, h));
				else r.add(new Rectangle(x, y, w, h));
				if(!flushScheduled){
					flushScheduled = true;
					long wait = FRAME_NANOS-(now-lastPass);
					int delay = (int)Math.max(1, (wait+999_999)/1_000_000);
					SwingUtilities.invokeLater(() -> { flushTimer.setInitialDelay(delay); flushTimer.restart(); });
				}
				pass = false;
			}
		}
		if(pass) super.addDirtyRegion(c, x, y, w, h);
	}

	/**
	 * Repaints the whole card for what a 68K external drew straight into the card window (CardWindow68k.overlay) or a gamma
	 * table it set: also while the screen is locked, when the card shows the picture taken at the lock (VEffect.oldoff) or the
	 * parts as they were then (VEffect.lazyLock) and MyPanel.paint puts the drawing over that. lock screen stops HyperCard's own
	 * redraw of the card, not the external's drawing on the screen (ANIMATE's intro runs from openCard under the closeCard's lock).
	 * While go rebuilds the card without a picture (VEffect.freezeScreen) nothing could be shown: the request waits for the unlock.
	 */
	static void repaintThroughLock(JComponent card) {
		if(card==null) return;
		if(!PCARD.lockedScreen || PCARD.capturingEffect){ card.repaint(); return; }
		if(VEffect.oldoff==null && !VEffect.lazyLock) return;
		RepaintManager m = RepaintManager.currentManager(card);
		if(m instanceof CardRepaintManager) ((CardRepaintManager)m).passLocked(card);
		else card.repaint();
	}
	private void passLocked(JComponent card) {
		super.addDirtyRegion(card, 0, 0, card.getWidth(), card.getHeight());
	}

	/** A request on {@code c} in card window pixels (clipped to {@code c}). */
	private static Rectangle cardRegion(JComponent card, JComponent c, int x, int y, int w, int h) {
		Rectangle r = new Rectangle(x, y, w, h).intersection(new Rectangle(0, 0, c.getWidth(), c.getHeight()));
		return r.isEmpty() || c==card ? r : SwingUtilities.convertRectangle(c, r, card);
	}

	private void flush() {
		Map<JComponent, Rectangle> regions;
		synchronized(this){
			regions = new LinkedHashMap<JComponent, Rectangle>(pending);
			pending.clear();
			flushScheduled = false;
			lastPass = System.nanoTime();
		}
		//ロック前に出た要求: lock screen なら画面の絵(oldoff)はその変更の後に取ってあり、絵なしのロック(lazyLock)なら
		//部品はロック開始時のままなので描いてよい(これがロック前の最後のコマになる)。
		//カード移動の凍結(絵なし)では組み替え途中の部品を描いてしまうので捨てる(解除で全体を描き直す)
		if(PCARD.lockedScreen && !PCARD.capturingEffect && VEffect.oldoff==null && !VEffect.lazyLock) return;
		for(Map.Entry<JComponent, Rectangle> e : regions.entrySet()){
			Rectangle r = e.getValue();
			super.addDirtyRegion(e.getKey(), r.x, r.y, r.width, r.height);
		}
	}

	@Override
	public void paintDirtyRegions() {
		synchronized(this){ paintQueued = false; }
		super.paintDirtyRegions();
	}

	/** Swing's dirty region plus what waits for the end of the frame: everything that will be painted. */
	@Override
	public Rectangle getDirtyRegion(JComponent c) {
		Rectangle dirty = super.getDirtyRegion(c);
		synchronized(this){
			Rectangle waiting = pending.get(c);
			if(waiting==null) return dirty;
			if(dirty==null || dirty.isEmpty()) return new Rectangle(waiting);
			return dirty.union(waiting);
		}
	}

	@Override
	public void markCompletelyClean(JComponent c) {
		synchronized(this){ pending.remove(c); }
		super.markCompletelyClean(c);
	}

	/** True while merged requests are waiting for the end of the frame (tests wait for the last frame with this). */
	synchronized boolean hasPendingFrame() {
		return flushScheduled;
	}
}

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.Locale;

//ペンタブレットの筆圧・傾き。
//
//Javaのマウスイベントには筆圧が無いので、OSのイベントを横から読むネイティブ部が
//ペンの点(画面座標・筆圧・消しゴム側か・ペン先が触れているか・傾き)をrecord()でここへ溜め、
//ペイントツールがマウスイベントを受けたときに、その位置に一番近い最近の点を探して
//そのイベントの筆圧とする(update)。ネイティブ部はOSのイベントを先に見るので、
//EDTが遅れていても、合体されたドラッグでも、そのイベントの位置の筆圧が取れる。
//  macOS:   NativeTabletMac (NSEventのローカルモニタ。座標はポイント=Javaの画面座標)
//  Windows: NativeTabletWindows (AWTのスレッドへのWH_GETMESSAGEフックでWM_POINTERを読む。
//           Windows Ink。座標は物理ピクセルなのでphysicalPixels)
//どちらもsrc/ffm(Java 22のForeign Function API)にあり、start()がリフレクションで読む。
//ペンの点が見つからないイベント(マウス)の筆圧はNaN。-Dhz.tablet=falseで読まない。
public final class TabletInput {
	private TabletInput(){}

	//照合に使う点の数と古さ
	private static final int RING = 256;
	private static final long MAX_AGE_NS = 500_000_000L;
	//この距離(ポイント)より遠い点はそのイベントの点とみなさない
	private static final double MATCH_PT = 4;

	private static final double[] xs = new double[RING], ys = new double[RING];
	private static final float[] ps = new float[RING];
	private static final boolean[] erasers = new boolean[RING], contacts = new boolean[RING];
	//傾き(度。X正=右へ、Y正=手前へ倒す。分からなければNaN)
	private static final float[] tiltXs = new float[RING], tiltYs = new float[RING];
	private static final long[] times = new long[RING];
	private static int head, count;
	//macOS: 近接イベントで分かったペンの向き(消しゴム側か)
	private static volatile boolean proximityEraser;

	private static boolean started;
	//ネイティブ部が動いているか
	static volatile boolean available;
	//Windows: 点の座標は物理ピクセル(Javaの画面座標はモニタごとの倍率で割ったもの)
	static volatile boolean physicalPixels;

	//今のマウスイベントの筆圧(0〜1、ペンでなければNaN)と、消しゴム側か
	private static float pressure = Float.NaN;
	private static boolean eraser;

	//ネイティブ部を動かす(何度呼んでもよい)。ペイントツールを選んだとき・the penPressureで呼ばれる。
	static synchronized void start(){
		if(started) return;
		started = true;
		if("false".equals(System.getProperty("hz.tablet"))) return;
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String cls = os.startsWith("mac") ? "NativeTabletMac" : os.startsWith("windows") ? "NativeTabletWindows" : null;
		if(cls==null) return;
		try{
			Object ok = Class.forName(cls).getMethod("start").invoke(null);
			available = Boolean.TRUE.equals(ok);
		}catch(Throwable t){
			//Java 21以前(src/ffmが無い)、Windows 7(ポインタAPIが無い)など: 筆圧なしで動く
			if(Boolean.getBoolean("hz.tablet.debug")) t.printStackTrace();
		}
	}

	//ネイティブ部から(OSのイベントのスレッドで)。x,yは画面座標、pressureは0〜1。
	public static void record(double x, double y, float pressure, boolean eraser, boolean contact){
		record(x, y, pressure, eraser, contact, Float.NaN, Float.NaN);
	}
	//tiltX/tiltYは傾き(度、上の向き)。無ければNaN
	public static void record(double x, double y, float pressure, boolean eraser, boolean contact, float tiltX, float tiltY){
		synchronized(xs){
			tiltXs[head] = tiltX; tiltYs[head] = tiltY;
			xs[head] = x; ys[head] = y;
			ps[head] = Math.max(0f, Math.min(1f, pressure));
			erasers[head] = eraser || proximityEraser;
			contacts[head] = contact;
			times[head] = System.nanoTime();
			head = (head+1)%RING;
			if(count<RING) count++;
		}
	}

	//macOS: ペンが近づいた/離れた。ペンの向き(消しゴム側か)は近接イベントにしか無い。
	public static void proximity(boolean entering, boolean eraserEnd){
		proximityEraser = entering && eraserEnd;
	}

	//(x,y)に一番近い最近の点の番号(無ければ-1)。同じ距離なら新しい方。
	static int match(double x, double y, double tolerance, long now){
		synchronized(xs){
			int best = -1;
			double bestD = tolerance*tolerance;
			for(int k=count; k>=1; k--){ //古い順
				int i = ((head-k)%RING+RING)%RING;
				if(now-times[i]>MAX_AGE_NS) continue;
				double dx = xs[i]-x, dy = ys[i]-y, d = dx*dx+dy*dy;
				if(d<=bestD){ bestD = d; best = i; }
			}
			return best;
		}
	}

	//ペイント用のマウスイベントを受けたら最初に呼ぶ。放したときの点は筆圧0なので、
	//ストロークの最後の筆圧を残す(ツールは放した点まで直前の筆圧で描く)。
	static void update(MouseEvent e){
		if(e.getID()==MouseEvent.MOUSE_RELEASED) return;
		if(!started) start(); //ブラシ・消しゴム以外のツールから使い始めたとき(最初の押下には間に合わない)
		if(!available || count==0){ pressure = Float.NaN; eraser = false; return; }
		double[] q = nativePoint(e);
		if(q==null){ pressure = Float.NaN; return; }
		int i = match(q[0], q[1], q[2], System.nanoTime());
		synchronized(xs){
			if(i<0 || !contacts[i]){ pressure = Float.NaN; eraser = false; return; }
			//触れた直後は0のことがあるので、ごく小さい値で止める
			pressure = Math.max(0.01f, ps[i]);
			eraser = erasers[i];
		}
	}

	//ストロークの終わり(PaintTool.mouseUp)。スクリプトのdragなど、update()を通らない
	//マウス操作に前のペンの筆圧が残らないようにする
	static void endStroke(){ pressure = Float.NaN; eraser = false; }

	//VMのタブレットドライバ(WacomTablet68k)用: 最後のマウスイベントがペンのものか(浮かせていても)。
	//MacAppHostの全体のマウスリスナーがobserve()で更新する。筆圧はlatest()で読む
	static volatile boolean penNear, penNearEraser;
	static void observe(MouseEvent e){
		if(!available || count==0){ penNear = false; penNearEraser = false; return; }
		double[] q = nativePoint(e);
		int i = q==null ? -1 : match(q[0], q[1], q[2], System.nanoTime());
		synchronized(xs){
			penNear = i>=0;
			penNearEraser = i>=0 && erasers[i];
		}
	}

	//イベントの画面座標をネイティブ部の座標(x, y, 照合の距離)にする。取れなければnull
	private static double[] nativePoint(MouseEvent e){
		Point p;
		try{ p = e.getLocationOnScreen(); }catch(RuntimeException err){ return null; }
		double x = p.x, y = p.y, tol = MATCH_PT;
		if(physicalPixels){
			//Windows: Javaの座標はモニタの左上(物理ピクセル)からモニタの倍率で縮めたもの
			Component c = e.getComponent();
			GraphicsConfiguration gc = c==null ? null : c.getGraphicsConfiguration();
			if(gc!=null){
				Rectangle b = gc.getBounds();
				AffineTransform t = gc.getDefaultTransform();
				x = b.x+(p.x-b.x)*t.getScaleX();
				y = b.y+(p.y-b.y)*t.getScaleY();
				tol *= Math.max(1, t.getScaleX());
			}
		}
		return new double[]{x, y, tol};
	}

	//今のイベントの筆圧(0〜1)。ペンでなければNaN
	static float pressure(){ return pressure; }
	//今のイベントがペンの消しゴム側か
	static boolean eraser(){ return eraser; }

	//the penPressure: 最後のペンの点の筆圧(0〜1)。最近ペンが使われていなければNaN
	static float latest(){
		synchronized(xs){
			if(count==0) return Float.NaN;
			int i = (head-1+RING)%RING;
			if(System.nanoTime()-times[i]>1_000_000_000L) return Float.NaN;
			return contacts[i] ? ps[i] : 0f;
		}
	}

	//最後のペンの点の傾き{x,y}(度)。最近ペンが使われていないか、傾きが分からなければnull
	static float[] latestTilt(){
		synchronized(xs){
			if(count==0) return null;
			int i = (head-1+RING)%RING;
			if(System.nanoTime()-times[i]>1_000_000_000L || Float.isNaN(tiltXs[i]) || Float.isNaN(tiltYs[i])) return null;
			return new float[]{tiltXs[i], tiltYs[i]};
		}
	}

	//テスト用: 溜めた点と今の状態を捨てる
	static void reset(){
		synchronized(xs){ head = 0; count = 0; }
		pressure = Float.NaN; eraser = false; proximityEraser = false; penNear = false; penNearEraser = false;
	}
}

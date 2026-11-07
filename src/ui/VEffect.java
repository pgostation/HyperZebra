import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.TexturePaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.AlphaComposite;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.util.Date;

import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;


public class VEffect {
	static BufferedImage oldoff=null;

	public static void setOldOff() {
		if(PCARDFrame.pc==null || PCARDFrame.pc.mainPane==null) return;
		if(!SwingUtilities.isEventDispatchThread()){
			PCARD.runOnEDT(VEffect::setOldOff);
			return;
		}
		int width=PCARDFrame.pc.mainPane.getWidth();
		int height=PCARDFrame.pc.mainPane.getHeight();
		if(width<=0 || height<=0) return;
		oldoff = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
		Graphics2D offg = oldoff.createGraphics();
		try{
			paintCurrentPanel(offg);
		}finally{
			offg.dispose();
		}
	}

	/** lock screen が絵を取らずに始まり、まだ部品ツリー(Swing)が変わっていない間 true。
	 *  ロック中のボタンの変更(アイコン・表示・位置)は PCARD.runOnEDTWhenShown が解除まで保留するので、ツリーは
	 *  ロック開始時の画面のまま。ほかの変更が EDT でツリーに入る直前(PCARD.runOnEDT)、視覚効果・flash が古い画面を
	 *  使うときにだけ絵を取る(captureBeforeChange)。アニメーションの1コマごとのカード全体の描画を省く */
	static volatile boolean lazyLock = false;

	/** lock screen を絵なしで始める。部品はロック開始時のままなので、ロック前のコマはロック中に描かれても同じ絵になる。
	 *  描画を待たない(毎コマ lock screen するゲームでは、そのコマの描画の分だけスクリプトが止まっていた) */
	static void lockLazily() {
		oldoff = null;
		lazyLock = PCARDFrame.pc!=null && PCARDFrame.pc.mainPane!=null;
		PCARD.lockedScreen = true;
	}

	/** (EDT) 絵なしのロック中にツリーが変わる直前: ロック開始時の画面(今のツリー)を絵にしておく */
	static void captureBeforeChange() {
		if(!lazyLock) return;
		lazyLock = false;
		if(!PCARD.lockedScreen || oldoff!=null || PCARDFrame.pc==null || PCARDFrame.pc.mainPane==null) return;
		int width=PCARDFrame.pc.mainPane.getWidth();
		int height=PCARDFrame.pc.mainPane.getHeight();
		if(width<=0 || height<=0) return;
		BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
		Graphics2D g = picture.createGraphics();
		try{ paintCurrentPanel(g); }finally{ g.dispose(); }
		oldoff = picture;
	}

	/** 画面の絵を取らずに lock screen の状態にする(効果なしのカード移動)。既に EDT に積まれた描画を先に済ませてから、
	 *  EDT 上でロックする。ロック中のカードの再描画要求は CardRepaintManager が捨て、画面には直前の絵が残る。
	 *  解除(visualEffect)でカード全体を描き直す */
	public static void freezeScreen() {
		if(PCARDFrame.pc==null || PCARDFrame.pc.mainPane==null){ PCARD.lockedScreen = true; return; }
		oldoff = null;
		PCARD.runOnEDT(() -> { lazyLock = false; PCARD.lockedScreen = true; });
	}

	//Swingの共有ダブルバッファを経由してmainPaneを別画像へpaintすると、その直後の
	//部分再描画が同じバッファを参照し、カード全体の内容を部品矩形へ転写することが
	//ある。必ずEDT上で、ダブルバッファを一時停止してオフスクリーンへ出力する。
	static void paintCurrentPanel(Graphics2D target){
		RepaintManager manager = RepaintManager.currentManager(PCARDFrame.pc.mainPane);
		boolean doubleBuffering = manager.isDoubleBufferingEnabled();
		boolean saveCapturing = PCARD.capturingEffect;
		PCARD.capturingEffect = true;
		manager.setDoubleBufferingEnabled(false);
		try{
			PCARDFrame.pc.mainPane.printAll(target);
		}finally{
			manager.setDoubleBufferingEnabled(doubleBuffering);
			PCARD.capturingEffect = saveCapturing;
		}
	}

	//HyperTalkのflashも画面全体のキャプチャと直接描画を行うため、visual effectと
	//同じくEDTとダブルバッファを管理する。最後は必ず元画像へ戻す。
	public static void flash(int count){
		if(PCARDFrame.pc==null || PCARDFrame.pc.mainPane==null || count<=0) return;
		if(!SwingUtilities.isEventDispatchThread()){
			PCARD.runOnEDT(() -> flash(count));
			return;
		}
		captureBeforeChange();
		int width=PCARDFrame.pc.mainPane.getWidth();
		int height=PCARDFrame.pc.mainPane.getHeight();
		if(width<=0 || height<=0) return;

		BufferedImage normal = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
		Graphics2D normalGraphics = normal.createGraphics();
		try{
			if(PCARD.lockedScreen && oldoff!=null){
				normalGraphics.drawImage(oldoff, 0, 0, width, height,
					0, 0, oldoff.getWidth(), oldoff.getHeight(), null);
			}else{
				paintCurrentPanel(normalGraphics);
			}
		}finally{
			normalGraphics.dispose();
		}

		BufferedImage inverse = inverted(normal);

		Graphics screen = PCARDFrame.pc.mainPane.getGraphics();
		if(screen==null) return;
		try{
			for(int i=0; i<count; i++){
				screen.drawImage(inverse, 0, 0, width, height, null);
				Toolkit.getDefaultToolkit().sync(); //Windows(Direct3D)では同期しないと 100ms ごとにしか画面に出ず、反転が見えないことがある
				try{ Thread.sleep(50); }catch(InterruptedException e){ Thread.currentThread().interrupt(); break; }
				screen.drawImage(normal, 0, 0, width, height, null);
				Toolkit.getDefaultToolkit().sync();
				try{ Thread.sleep(50); }catch(InterruptedException e){ Thread.currentThread().interrupt(); break; }
			}
			screen.drawImage(normal, 0, 0, width, height, null);
			Toolkit.getDefaultToolkit().sync();
		}finally{
			screen.dispose();
		}
		if(!PCARD.lockedScreen)
			PCARDFrame.pc.mainPane.paintImmediately(0, 0, width, height);
	}
	
	public static void visualEffect(int effect, int toEffect, int speed) {
		visualEffect(effect, toEffect, speed, true);
	}

	/** The picture with every pixel inverted (flash, "to inverse"). XOR mode combines the paint colour with the XOR
	 *  colour: the default white paint XOR white is 0, which left the picture unchanged (flash showed nothing). */
	static BufferedImage inverted(BufferedImage picture){
		BufferedImage inverse = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_BGR);
		Graphics2D g = inverse.createGraphics();
		try{
			g.drawImage(picture, 0, 0, null);
			g.setColor(Color.black);
			g.setXORMode(Color.white);
			g.fillRect(0, 0, picture.getWidth(), picture.getHeight());
		}finally{
			g.dispose();
		}
		return inverse;
	}

	//効果のコマはカードウインドウへ直接描く。Windowsではカードほどの大きさのウインドウはDirect3Dで描かれ、
	//直接描いた絵はJava2Dの画面更新スレッドが100msごとにしか画面へ出さない(効果が毎秒10コマ程度でカクつく)。
	//1コマごとに同期して画面へ出し、コマは毎秒120程度に間引く(同じ絵を何千回も描いてパイプラインを詰まらせない)。
	private static final long FRAME_NANOS = 1_000_000_000L/120;
	private static long lastFrame;

	/** 1コマを画面へ出して次のコマの時刻まで待ち、今の時刻(ms)を返す */
	private static long nextFrame(){
		Toolkit.getDefaultToolkit().sync();
		long due = lastFrame+FRAME_NANOS;
		for(long t=System.nanoTime(); t<due; t=System.nanoTime()){
			if(due-t>2_000_000L){
				try{ Thread.sleep(1); }catch(InterruptedException e){ Thread.currentThread().interrupt(); break; }
			}
			else Thread.onSpinWait();
		}
		lastFrame = System.nanoTime();
		return System.currentTimeMillis();
	}

	/** 予約された複数の視覚効果を順に再生する(visual effect A / visual effect B / go …)。
	 *  途中の効果はその画像(黒/白/灰/反転/カード)で止め、次の効果はそこから始める。空なら効果なしで表示を確定する */
	public static void playEffects(java.util.List<int[]> effects) {
		if(effects==null || effects.isEmpty()){ visualEffect(0, 0, 3, true); return; }
		for(int i=0; i<effects.size(); i++){
			int[] e = effects.get(i);
			if(e[0]==0 && e.length>3 && e[3]!=0) holdPlain(e[2]); //written plain/cut: its speed's time passes
			visualEffect(e[0], e[1], e[2], i==effects.size()-1);
		}
	}

	//finish=false なら効果の終わりの画像を次の効果の開始画像(oldoff)として残し、画面は確定しない
	public static void visualEffect(int effect, int toEffect, int speed, boolean finish) {
		visualEffect(effect, toEffect, effectDuration(effect, playSpeed(speed)), finish, null);
	}

	/** AddColor's transitions (TXcmd.AddColor): only the rectangle area changes, from the picture taken by setOldOff to
	 *  the card as it is now, in ms milliseconds. AddColor plays its effects inside the picture's rectangle (HC J-2.3:
	 *  "fromLeft" on a picture at 340,30 wipes from x 340 to its right edge, not across the card) */
	public static void visualEffectIn(int effect, Rectangle area, long ms) {
		visualEffect(effect, 0, ms, true, area);
	}

	private static void visualEffect(int effect, int toEffect, long ms, boolean finish, Rectangle area) {
		if(PCARDFrame.pc==null || PCARDFrame.pc.mainPane==null) return;
		if(!SwingUtilities.isEventDispatchThread()){
			//効果なしの解除では古い画面の絵は要らない: runOnEDT の「変更前に絵を取る」を通さない
			PCARD.runOnEDTUnlocking(() -> visualEffect(effect, toEffect, ms, finish, area));
			return;
		}
		if(effect!=0 || !finish) captureBeforeChange(); //効果の始まりの絵(保留中のボタンの変更より前)
		//the card goes back into the window (unlock screen, go): what a 68K external drew over it is not in the new picture -
		//the effect starts from the screen with it (UxMaruPeke: "unlock screen with dissolve" erases its ○×)
		if(finish) CardWindow68k.cardCopied();
		lazyLock = false;
		PCARD.runDeferred();
		int width=PCARDFrame.pc.mainPane.getWidth();
		int height=PCARDFrame.pc.mainPane.getHeight();
		if(width<=0 || height<=0){
			PCARD.lockedScreen = false;
			oldoff = null;
			return;
		}
		
		if(oldoff!=null && (oldoff.getWidth()!=width || oldoff.getHeight()!=height)){
			//lock screen の後にカードウインドウの大きさが変わった(別の大きさのスタックへ go した: GuessWords → Home):
			//古い画面を新しい大きさに写してから効果をかける(dissolve は両方のバッファを同じ大きさとして走査する)
			BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
			Graphics2D rg = resized.createGraphics();
			rg.setColor(Color.white); rg.fillRect(0, 0, width, height);
			rg.drawImage(oldoff, 0, 0, null); rg.dispose();
			oldoff = resized;
		}
		if(effect==0){
			if(!finish) return; //cut: 次の効果へそのまま
		}
		else{
			BufferedImage off = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
			//VolatileImage off = PCARD.pc.stack.pcard.createVolatileImage(width, height);
			Graphics2D offg = off.createGraphics();
			Graphics paneg = PCARDFrame.pc.mainPane.getGraphics();
			
			/* to black1 white2 grey3 inverse4 */
			if(toEffect==0){
				//オフスクリーンに描画。この時点でPCARD.lockedScreenがtrueのことがある
				//(goコマンドによるカード遷移中など)ため、capturingEffectを立てて
				//ボタン/フィールドがロック中でも普通に描画されるようにする。
				//そうしないと、一部の部品がキャプチャから抜け落ちて真っ白のまま
				//アニメーションされてしまう。
				paintCurrentPanel(offg);
				PCARD.lockedScreen = true;
			}
			else if(toEffect==4){
				//オフスクリーンに描画(同上)
				BufferedImage card = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
				Graphics2D cardGraphics = card.createGraphics();
				try{ paintCurrentPanel(cardGraphics); }finally{ cardGraphics.dispose(); }
				PCARD.lockedScreen = true;
				offg.drawImage(inverted(card), 0, 0, null);
			}
			else{
				fillPlain(offg, toEffect, width, height);
			}

			if(paneg==null){
				offg.dispose();
				PCARD.lockedScreen = false;
				oldoff = null;
				PCARDFrame.pc.mainPane.repaint();
				return;
			}
			if(oldoff!=null) paneg.drawImage(oldoff,0,0,width,height,PCARDFrame.pc.stack.pcard);
			
			long start = new Date().getTime();
			Rectangle r = area==null ? null : area.intersection(new Rectangle(0, 0, width, height));
			if(r==null || (r.width==width && r.height==height)) playFrames(effect, (Graphics2D)paneg, oldoff, off, start, start+ms);
			else if(!r.isEmpty()){
				//the effect's geometry is the rectangle's: play it on copies of that part (dissolve indexes whole rows)
				Graphics2D g = (Graphics2D)paneg.create(r.x, r.y, r.width, r.height);
				try{ playFrames(effect, g, oldoff==null ? null : part(oldoff, r), part(off, r), start, start+ms); }
				finally{ g.dispose(); }
			}

			//上のアニメーションは経過時間(System.currentTimeMillis())に応じて描くので、
			//OSのタイマー精度やマシン負荷によっては、ループが「now>=end」で抜けた時点で
			//まだv(進捗)がwidth/height全体に到達しておらず、古い画面(oldoff、あるいは
			//何も描かれていない部分)がそのまま画面の一部に残ってしまうことがある。
			//特に速いスピード(very fast等)では顕著で、「unlock screen」後に画面の一部が
			//白いまま残る不具合の原因になっていた。効果の種類やスピードに関わらず、
			//最後に新しい内容(off)を画面全体へ確実に描き切って締めくくる。
			paneg.drawImage(off,0,0,width,height,0,0,width,height,PCARDFrame.pc.stack.pcard);
			Toolkit.getDefaultToolkit().sync();
			offg.dispose();
			paneg.dispose();
			if(!finish){
				//次の効果はこの画像から始める
				oldoff = off;
				PCARD.lockedScreen = true;
				return;
			}
		}

		//移動後のカードの表示
		PCARD.lockedScreen = false;
		oldoff = null;
		PCARDFrame.pc.mainPane.revalidate();
		if(effect==0){
			//効果なし(カード移動・unlock screen): 部品はこの時点で完成しているので、次の描画(1フレーム以内)で
			//まとめて出す。go を繰り返すループでカードごとに全体を同期描画しない
			PCARDFrame.pc.mainPane.repaint();
			return;
		}
		//効果の最後の絵は画面に直接描いてあるので、部品を含む本来の画面をすぐに描いて締めくくる
		PCARDFrame.pc.mainPane.paintImmediately(0, 0,
			PCARDFrame.pc.mainPane.getWidth(), PCARDFrame.pc.mainPane.getHeight());
	}

	private static BufferedImage part(BufferedImage image, Rectangle r){
		BufferedImage copy = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_BGR);
		Graphics2D g = copy.createGraphics();
		try{ g.drawImage(image, -r.x, -r.y, null); }finally{ g.dispose(); }
		return copy;
	}

	/** Paints the plain picture an effect can end on: to black (1), white (2) or gray (3, a 50% checker as HyperCard's) */
	static void fillPlain(Graphics2D g, int toEffect, int width, int height){
		if(toEffect==1){
			g.setColor(Color.black);
			g.fillRect(0,0,width,height);
		}
		else if(toEffect==2){
			g.setColor(Color.white);
			g.fillRect(0,0,width,height);
		}
		else if(toEffect==3){
			BufferedImage txtr = new BufferedImage(2, 2, BufferedImage.TYPE_INT_BGR);
			txtr.getRaster().getDataBuffer().setElem(0,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(1,0x000000);
			txtr.getRaster().getDataBuffer().setElem(2,0x000000);
			txtr.getRaster().getDataBuffer().setElem(3,0xFFFFFF);
			Rectangle2D r = new Rectangle2D.Double(0,0,2,2);
			g.setPaint(new TexturePaint(txtr, r));
			g.fillRect(0, 0, width, height);
		}
	}

	/** HyperCard J-2.3's own effects (numbers 0-27, the order of GVisualEffectWindow.EFFECTS): how many ticks (1/60 s) each takes
	 *  at the speeds very fast, fast, normal, slow, very slow. Measured in HyperCard J-2.3 in the 68K VM (go with `visual effect X
	 *  <speed>`, the same with the VM unthrottled: it is the effect's own time, not the CPU's; probe groups group_vec27_vfx*). The
	 *  speeds are not one scale: very slow is 6.5 times normal, and checkerboard/zoom open are the long ones, venetian blinds the short. */
	private static final int[][] HC_TICKS = {
		{2, 15, 30, 66, 197},     //0 plain / cut: HyperCard paces them too
		{1, 12, 25, 53, 158},     //1 barn door open
		{1, 12, 24, 53, 157},     //2 barn door close
		{3, 17, 32, 69, 203},     //3 dissolve
		{1, 6, 12, 26, 75},       //4 venetian blinds
		{1, 24, 46, 101, 299},    //5 checkerboard
		{1, 13, 26, 57, 169},     //6 iris open
		{1, 13, 26, 57, 168},     //7 iris close
		{2, 9, 18, 40, 118},      //8 scroll left
		{2, 9, 18, 40, 119},      //9 scroll right
		{2, 9, 18, 40, 118},      //10 scroll up
		{2, 9, 18, 40, 119},      //11 scroll down
		{1, 12, 25, 53, 158},     //12 wipe left
		{1, 13, 24, 54, 157},     //13 wipe right
		{1, 10, 20, 42, 126},     //14 wipe up
		{1, 10, 20, 42, 126},     //15 wipe down
		{1, 21, 42, 92, 273},     //16 zoom open (zoom out)
		{1, 7, 13, 27, 77},       //17 zoom close (zoom in)
		{4, 14, 27, 59, 173},     //18 shrink to top
		{4, 14, 27, 59, 173},     //19 shrink to bottom
		{5, 14, 27, 59, 173},     //20 shrink to center
		{3, 15, 28, 61, 180},     //21 stretch from top
		{3, 15, 29, 61, 179},     //22 stretch from bottom
		{3, 15, 28, 61, 180},     //23 stretch from center
		{4, 9, 19, 40, 118},      //24 push left
		{4, 9, 18, 40, 119},      //25 push right
		{3, 10, 18, 40, 119},     //26 push up
		{4, 10, 18, 40, 118}};    //27 push down

	/** The length of an effect at a speed: 1 very fast, 2 fast, 3 normal, 4 slow(ly), 5 very slow(ly) (ms); another
	 *  number plays no frames. HyperCard's effects 0-27 take HyperCard's time (HC_TICKS), HyperZebra's own ones a common table. */
	static long effectDuration(int effect, int speed){
		if(effect>=0 && effect<HC_TICKS.length && speed>=1 && speed<=5) return Math.round(HC_TICKS[effect][speed-1]*1000/60.0);
		return effectDuration(speed);
	}

	/** -Dhz.effects.fastest=true (the test runs): every effect plays at very fast, whatever speed the script asked for */
	private static final boolean FASTEST = Boolean.getBoolean("hz.effects.fastest");
	private static int playSpeed(int speed){ return FASTEST && speed>=1 && speed<=5 ? 1 : speed; }

	static long effectDuration(int speed){
		switch(speed){
			case 1: return 100;
			case 2: return 250;
			case 3: return 500;
			case 4: return 1000;
			case 5: return 2000;
			default: return 0;
		}
	}

	/** `visual effect plain|cut <speed>` before a go / unlock screen: HyperCard lets the speed's time pass with nothing drawn
	 *  (HC J-2.3 measured: 30 ticks at normal). A script thread's pause; an abort ends it. */
	public static void holdPlain(int speed){
		if(SwingUtilities.isEventDispatchThread()) return;
		long end = System.nanoTime()+effectDuration(0, playSpeed(speed))*1_000_000L;
		while(System.nanoTime()<end && !TTalk.stop){
			try{ Thread.sleep(5); }catch(InterruptedException e){ Thread.currentThread().interrupt(); break; }
		}
	}

	/** Draws the frames of an effect from the picture oldoff (null: what is already on paneg) to off on paneg between
	 *  the times start and end (ms). The card window and the effect preview (GVisualEffectWindow) both use it.
	 *  dissolve draws into oldoff. The last frame is not guaranteed to be all of off: the caller draws off afterwards. */
	static void playFrames(int effect, Graphics2D paneg, BufferedImage oldoff, BufferedImage off, long start, long end){
		if(oldoff==null || effect==3 || (effect>=FADE && effect<=PAGE_TURN_RIGHT)){
			//dissolve と追加の効果は自分でコマを1枚の絵にして出す。古い画面の絵がなければ画面に直接描く
			drawFrames(effect, paneg, oldoff, off, start, end, null);
			return;
		}
		//コマはオフスクリーン(canvas)で作り、1コマに1回で画面へ出す。小さな絵(venetian blinds の帯、checkerboard の
		//升目)を画面へ直接何十回も描くと、描きかけのコマが画面に出て四角くちらつく(macOS の Metal など)
		int width = off.getWidth(), height = off.getHeight();
		BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
		Graphics2D g = canvas.createGraphics();
		try{
			g.drawImage(oldoff, 0, 0, width, height, null);
			drawFrames(effect, g, oldoff, off, start, end, () -> paneg.drawImage(canvas, 0, 0, null));
		}finally{
			g.dispose();
		}
	}

	/** Shows the frame just drawn (show: copies the canvas to the screen; null: it was drawn on the screen) and waits
	 *  for the next frame */
	private static long present(Runnable show){
		if(show!=null) show.run();
		return nextFrame();
	}

	private static void drawFrames(int effect, Graphics2D paneg, BufferedImage oldoff, BufferedImage off, long start, long end, Runnable show){
		int width = off.getWidth(), height = off.getHeight();
		long now = start;
		if(effect==1) { //barn door open
			while(now<end){
				int v=(int)(width/2*((double)(now-start)/(end-start)));
				paneg.drawImage(off,width/2-v,0,width/2+v,height,width/2-v,0,width/2+v,height,null);
				now = present(show);
			}
		}
		else if(effect==2) { //barn door close
			while(now<end){
				int v=(int)(width/2*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,0,v,height,0,0,v,height,null);
				paneg.drawImage(off,width-v,0,width,height,width-v,0,width,height,null);
				now = present(show);
			}
		}
		else if(effect==3 && oldoff!=null) { //dissolve
			for(int i=0; i<16; ){
				now = present(show);
				int v=(int)(16*((double)(now-start)/(end-start)));
				if(v<i) continue; //nextFrame が次のコマまで待つ
				DataBuffer oldoffdb = oldoff.getRaster().getDataBuffer();
				DataBuffer offdb = off.getRaster().getDataBuffer();
				while(i<v && i<16){
					for(int y=getDissolveOffY(i);y<height;y+=2){
						for(int x=getDissolveOffX(i);x<width;x+=8){
							oldoffdb.setElem(0,y*width+x,offdb.getElem(0,y*width+x));
						}
					}
					i++;
				}
				paneg.drawImage(oldoff,0,0,width,height,null);
			}
		}
		else if(effect==4) { //venetian blinds
			while(now<end){
				int v=(int)(32*((double)(now-start)/(end-start)));
				for(int i=0; i<(height+31)/32; i++){
					paneg.drawImage(off,0,32*i,width,32*i+v,0,32*i,width,32*i+v,null);
				}
				now = present(show);
			}
		}
		else if(effect==5) { //checker board
			while(now<end){
				int v=(int)(64*((double)(now-start)/(end-start)));
				for(int i=0; i<(height+63)/64; i++){
					for(int j=0; j<(width+63)/64; j++){
						paneg.drawImage(off,j*64,64*i,j*64+32,64*i+v,j*64,64*i,j*64+32,64*i+v,null);
					}
					for(int j=0; j<(width+63)/64; j++){
						paneg.drawImage(off,j*64+32,64*i-32,j*64+64,64*i-32+v,j*64+32,64*i-32,j*64+64,64*i-32+v,null);
					}
				}
				now = present(show);
			}
		}
		else if(effect==6) { //iris open
			while(now<end){
				int h=(int)(width/2*((double)(now-start)/(end-start)));
				int v=(int)(height/2*((double)(now-start)/(end-start)));
				paneg.drawImage(off,width/2-h,height/2-v,width/2+h,height/2+v,width/2-h,height/2-v,width/2+h,height/2+v,null);
				now = present(show);
			}
		}
		else if(effect==7) { //iris close
			while(now<end){
				int h=(int)(width/2*((double)(now-start)/(end-start)));
				int v=(int)(height/2*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,0,width,v,0,0,width,v,null);
				paneg.drawImage(off,0,height-v,width,height,0,height-v,width,height,null);
				paneg.drawImage(off,0,v,h,height-v,0,v,h,height-v,null);
				paneg.drawImage(off,width-h,v,width,height-v,width-h,v,width,height-v,null);
				now = present(show);
			}
		}
		else if(effect==8 && oldoff!=null) { //scroll left
			while(now<end){
				int v=(int)(width*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,0,0,width-v,height,v,0,width,height,null);
				paneg.drawImage(off,width-v,0,width,height,width-v,0,width,height,null);
				now = present(show);
			}
		}
		else if(effect==9 && oldoff!=null) { //scroll right
			while(now<end){
				int v=(int)(width*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,v,0,width,height,0,0,width-v,height,null);
				paneg.drawImage(off,0,0,v,height,0,0,v,height,null);
				now = present(show);
			}
		}
		else if(effect==10 && oldoff!=null) { //scroll up
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,0,0,width,height-v,0,v,width,height,null);
				paneg.drawImage(off,0,height-v,width,height,0,height-v,width,height,null);
				now = present(show);
			}
		}
		else if(effect==11 && oldoff!=null) { //scroll down
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,0,v,width,height,0,0,width,height-v,null);
				paneg.drawImage(off,0,0,width,v,0,0,width,v,null);
				now = present(show);
			}
		}
		else if(effect==12) { //wipe left
			while(now<end){
				int v=(int)(width*((double)(now-start)/(end-start)));
				paneg.drawImage(off,width-v,0,width,height,width-v,0,width,height,null);
				now = present(show);
			}
		}
		else if(effect==13) { //wipe right
			while(now<end){
				int v=(int)(width*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,0,v,height,0,0,v,height,null);
				now = present(show);
			}
		}
		else if(effect==14) { //wipe up
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,height-v,width,height,0,height-v,width,height,null);
				now = present(show);
			}
		}
		else if(effect==15) { //wipe down
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,0,width,v,0,0,width,v,null);
				now = present(show);
			}
		}
		else if(effect==16) { //zoom open
			while(now<end){
				int h=(int)(width*((double)(now-start)/(end-start)));
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.setColor(Color.black); //XORで白黒反転する枠(描画色が白だと見えない)
				paneg.setXORMode(Color.white);
				paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
				now = present(show); //枠を見せてから消す
				paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
			}
			paneg.setPaintMode();
		}
		else if(effect==17) { //zoom close
			while(now<end){
				int h=(int)(width*((double)(end-now)/(end-start)));
				int v=(int)(height*((double)(end-now)/(end-start)));
				paneg.setColor(Color.black); //XORで白黒反転する枠(描画色が白だと見えない)
				paneg.setXORMode(Color.white);
				paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
				now = present(show);
				paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
			}
			paneg.setPaintMode();
		}
		else if(effect==18 && oldoff!=null) { //shrink to top
			while(now<end){
				int v=(int)(height*((double)(end-now)/(end-start)));
				paneg.drawImage(oldoff,0,0,width,v,0,0,width,height,null);
				paneg.drawImage(off,0,v,width,height,0,v,width,height,null);
				now = present(show);
			}
		}
		else if(effect==19 && oldoff!=null) { //shrink to bottom
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,0,v,width,height,0,0,width,height,null);
				paneg.drawImage(off,0,0,width,v,0,0,width,v,null);
				now = present(show);
			}
		}
		else if(effect==20 && oldoff!=null) { //shrink to center
			while(now<end){
				int v=(int)(height/2*((double)(end-now)/(end-start)));
				paneg.drawImage(oldoff,0,height/2-v,width,height/2+v,0,0,width,height,null);
				paneg.drawImage(off,0,0,width,height/2-v,0,0,width,height/2-v,null);
				paneg.drawImage(off,0,height/2+v,width,height,0,height/2+v,width,height,null);
				now = present(show);
			}
		}
		else if(effect==21) { //stretch from top
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,0,width,v,0,0,width,height,null);
				now = present(show);
			}
		}
		else if(effect==22) { //stretch from bottom
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,height-v,width,height,0,0,width,height,null);
				now = present(show);
			}
		}
		else if(effect==23) { //stretch from center
			while(now<end){
				int v=(int)(height/2*((double)(now-start)/(end-start)));
				paneg.drawImage(off,0,height/2-v,width,height/2+v,0,0,width,height,null);
				now = present(show);
			}
		}
		else if(effect==24 && oldoff!=null) { //push left
			while(now<end){
				int v=(int)(width*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,0,0,width-v,height,v,0,width,height,null);
				paneg.drawImage(off,width-v,0,width,height,0,0,v,height,null);
				now = present(show);
			}
		}
		else if(effect==25 && oldoff!=null) { //push right
			while(now<end){
				int v=(int)(width*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,v,0,width,height,0,0,width-v,height,null);
				paneg.drawImage(off,0,0,v,height,width-v,0,width,height,null);
				now = present(show);
			}
		}
		else if(effect==26 && oldoff!=null) { //push up
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,0,0,width,height-v,0,v,width,height,null);
				paneg.drawImage(off,0,height-v,width,height,0,0,width,v,null);
				now = present(show);
			}
		}
		else if(effect==27 && oldoff!=null) { //push down
			while(now<end){
				int v=(int)(height*((double)(now-start)/(end-start)));
				paneg.drawImage(oldoff,0,v,width,height,0,0,width,height-v,null);
				paneg.drawImage(off,0,0,width,v,0,height-v,width,height,null);
				now = present(show);
			}
		}
		else if(effect==ADDCOLOR_DISSOLVE) {
			AddColorDissolve dissolve = new AddColorDissolve(width, height);
			while(now<end){
				dissolve.drawTo(paneg, off, (double)(now-start)/(end-start));
				now = present(show);
			}
		}
		else if(isAddColorEffect(effect)) {
			while(now<end){
				addColorFrame(effect, paneg, off, (double)(now-start)/(end-start));
				now = present(show);
			}
		}

		else if(effect>=FADE && effect<=PAGE_TURN_RIGHT && oldoff!=null) {
			playModernEffect(effect, paneg, oldoff, off, start, end);
		}
	}

	/** AddColor's own effects (not HyperTalk visual effects: TXcmd.AddColor only), measured in HC J-2.3 + AddColor */
	static final int ADDCOLOR_CHECKER_OPEN=100, ADDCOLOR_CHECKER_CLOSE=101, ADDCOLOR_CIRCLE_CHECKER_OPEN=102,
		ADDCOLOR_IRIS_OPEN=103, ADDCOLOR_IRIS_CLOSE=104, ADDCOLOR_COMB_HORIZONTAL=105, ADDCOLOR_COMB_VERTICAL=106,
		ADDCOLOR_FROM_TOP_LEFT=107, ADDCOLOR_FROM_TOP_RIGHT=108, ADDCOLOR_FROM_BOTTOM_LEFT=109, ADDCOLOR_FROM_BOTTOM_RIGHT=110,
		ADDCOLOR_DISSOLVE=111;

	static boolean isAddColorEffect(int effect){
		return effect>=ADDCOLOR_CHECKER_OPEN && effect<=ADDCOLOR_DISSOLVE;
	}

	/** AddColor's dissolve, measured in HC J-2.3 + AddColor (はいぱあれもん Vol.6's title, 512 x 342): not HyperCard's
	 *  8 x 2 pattern but square cells at random places, in four passes of a quarter of the time each with cells of 4, 5,
	 *  6 and then 7 pixels (grids from the picture's top left). A pass shows its cells at an even rate: 18%, 32% and 52%
	 *  of its grid in the first three, every cell in the last. The frames only add to the last one. */
	static final class AddColorDissolve {
		static final int[] CELL = {4, 5, 6, 7};
		static final double[] SHARE = {0.18, 0.32, 0.52, 1.0};
		private final int width;
		private final int[][] order = new int[CELL.length][];
		private int pass = 0, done = 0;

		AddColorDissolve(int width, int height){
			this.width = width;
			java.util.Random random = new java.util.Random(0x41646443L);
			for(int i=0; i<CELL.length; i++){
				int cols = (width+CELL[i]-1)/CELL[i], rows = (height+CELL[i]-1)/CELL[i];
				int[] cells = new int[cols*rows];
				for(int c=0; c<cells.length; c++) cells[c] = c;
				for(int c=cells.length-1; c>0; c--){
					int r = random.nextInt(c+1), swap = cells[c];
					cells[c] = cells[r]; cells[r] = swap;
				}
				order[i] = cells;
			}
		}

		/** Draws the cells of off that are shown at progress t (0..1) and were not drawn yet */
		void drawTo(Graphics2D g, BufferedImage off, double t){
			t = Math.max(0, Math.min(1, t));
			int target = Math.min(CELL.length-1, (int)(t*CELL.length));
			while(pass<target){
				drawCells(g, off, count(pass, 1));
				pass++;
				done = 0;
			}
			drawCells(g, off, count(pass, t*CELL.length-pass));
		}

		private int count(int i, double u){
			return (int)Math.round(order[i].length*SHARE[i]*Math.min(1, u));
		}

		private void drawCells(Graphics2D g, BufferedImage off, int upto){
			int k = CELL[pass], cols = (width+k-1)/k;
			for(; done<upto; done++){
				int x = order[pass][done]%cols*k, y = order[pass][done]/cols*k;
				g.drawImage(off, x, y, x+k, y+k, x, y, x+k, y+k, null);
			}
		}
	}

	/** Draws the part of the new picture off that one of AddColor's own effects shows at progress t (0..1). Every
	 *  effect only adds to what it showed before, so each frame draws its whole part over the last one.
	 *  Measured (512 x 342 unless said otherwise):
	 *  - irisOpen/irisClose: a circle round the centre, radius max(width,height)*45/64 (360; 211 for a 300 x 100
	 *    picture) times t (open) or 1-t (close); irisClose at 60 ticks shrinks 12 pixels across a tick, 22 x 22 a tick
	 *    before the end
	 *  - combHorizontal: strips height/8 high (42, the 6 rows left over a 9th), even ones wiping in from the left,
	 *    odd ones from the right; combVertical: width/8 columns, even from the top, odd from the bottom
	 *  - checkerBoardClose: checkerBoardOpen backwards, the old picture shrinking to each cell's centre
	 *  - circleCheckerOpen: in the same 4 x 4 cells an ellipse of the cell's shape grows to half-axes cw*45/64,
	 *    ch*45/64 (90 x 60), centred so that it ends at the cell's right and bottom edges (297,198 in cell 2,2)
	 *  - fromTopLeft …: a rectangle growing from that corner, the picture revealed in place (not stretched) */
	static void addColorFrame(int effect, Graphics2D g, BufferedImage off, double t){
		int width = off.getWidth(), height = off.getHeight();
		java.awt.Shape part;
		switch(effect){
		case ADDCOLOR_CHECKER_OPEN:
			checkerOpenFrame(g, off, t);
			return;
		case ADDCOLOR_CHECKER_CLOSE: {
			int cw = width/4+1, ch = height/4+1;
			int ix = (int)(cw*t/2), iy = (int)(ch*t/2);
			Area area = new Area(new Rectangle(0, 0, width, height));
			for(int i=0; i<4; i++){
				for(int j=0; j<4; j++){
					int x0 = j*cw+ix, y0 = i*ch+iy, x1 = (j+1)*cw-ix, y1 = (i+1)*ch-iy;
					if(x0<x1 && y0<y1) area.subtract(new Area(new Rectangle(x0, y0, x1-x0, y1-y0)));
				}
			}
			part = area;
			break;
		}
		case ADDCOLOR_CIRCLE_CHECKER_OPEN: {
			int cw = width/4+1, ch = height/4+1, a = cw*45/64, b = ch*45/64;
			Area area = new Area();
			for(int i=0; i<4; i++){
				for(int j=0; j<4; j++){
					double cx = (j+1)*cw-a, cy = (i+1)*ch-b;
					area.add(new Area(new Ellipse2D.Double(cx-a*t, cy-b*t, 2*a*t, 2*b*t)));
				}
			}
			part = area;
			break;
		}
		case ADDCOLOR_IRIS_OPEN:
		case ADDCOLOR_IRIS_CLOSE: {
			double r = Math.max(width, height)*45/64*(effect==ADDCOLOR_IRIS_OPEN ? t : 1-t);
			//QuickDraw's oval in the rectangle centre ± r covers 2r-2 pixels across
			Ellipse2D circle = new Ellipse2D.Double(width/2.0-r+1, height/2.0-r+1, Math.max(0, 2*r-2), Math.max(0, 2*r-2));
			if(effect==ADDCOLOR_IRIS_OPEN) part = circle;
			else{
				Area area = new Area(new Rectangle(0, 0, width, height));
				area.subtract(new Area(circle));
				part = area;
			}
			break;
		}
		case ADDCOLOR_COMB_HORIZONTAL:
		case ADDCOLOR_COMB_VERTICAL: {
			boolean horizontal = effect==ADDCOLOR_COMB_HORIZONTAL;
			int length = horizontal ? width : height, across = horizontal ? height : width;
			int strip = Math.max(1, across/8), v = (int)(length*t);
			for(int k=0; k*strip<across; k++){
				int s0 = k*strip, s1 = Math.min(across, s0+strip), l0 = k%2==0 ? 0 : length-v, l1 = l0+v;
				if(horizontal) g.drawImage(off, l0, s0, l1, s1, l0, s0, l1, s1, null);
				else g.drawImage(off, s0, l0, s1, l1, s0, l0, s1, l1, null);
			}
			return;
		}
		default: { //fromTopLeft, fromTopRight, fromBottomLeft, fromBottomRight
			int w = (int)(width*t), h = (int)(height*t);
			boolean left = effect==ADDCOLOR_FROM_TOP_LEFT || effect==ADDCOLOR_FROM_BOTTOM_LEFT;
			boolean top = effect==ADDCOLOR_FROM_TOP_LEFT || effect==ADDCOLOR_FROM_TOP_RIGHT;
			int x0 = left ? 0 : width-w, y0 = top ? 0 : height-h;
			g.drawImage(off, x0, y0, x0+w, y0+h, x0, y0, x0+w, y0+h, null);
			return;
		}
		}
		Graphics2D clipped = (Graphics2D)g.create();
		try{
			clipped.clip(part);
			clipped.drawImage(off, 0, 0, null);
		}finally{
			clipped.dispose();
		}
	}

	/** A frame of AddColor's checkerBoardOpen at progress t (0..1): 4 x 4 cells of a quarter of the size plus one pixel
	 *  (129 x 86 on a 512 x 342 card, the last ones cut off), the new picture growing in each cell from its centre, the
	 *  same size in all of them (HC J-2.3 + AddColor, measured: 7 x 6 at 61,40 first, 123 x 82 at 3,2 near the end) */
	static void checkerOpenFrame(Graphics2D g, BufferedImage off, double t){
		int width = off.getWidth(), height = off.getHeight();
		int cw = width/4+1, ch = height/4+1;
		int ix = (int)(cw*(1-t)/2), iy = (int)(ch*(1-t)/2);
		for(int i=0; i<4; i++){
			for(int j=0; j<4; j++){
				int x0 = j*cw+ix, y0 = i*ch+iy, x1 = Math.min(width, (j+1)*cw-ix), y1 = Math.min(height, (i+1)*ch-iy);
				if(x0<x1 && y0<y1) g.drawImage(off,x0,y0,x1,y1,x0,y0,x1,y1,null);
			}
		}
	}

	//---- HyperZebra の追加効果(HyperCard にはない。After Effects / Premiere でよく使われるトランジション) ----
	//番号は TUtil.getVisualMode が返す効果の番号(HyperCard の 0-27 の後ろ)
	static final int FADE=28, MOSAIC=29, BLUR=30, BLOCK_DISSOLVE=31, RADIAL_WIPE=32, CIRCLE_OPEN=33, CIRCLE_CLOSE=34,
		CROSS_ZOOM=35, PAGE_TURN_LEFT=36, PAGE_TURN_RIGHT=37;

	private interface EffectFrame { void draw(double t); }

	/** start から end まで、進み t(0..1)ごとに1コマ描く */
	private static void animate(long start, long end, EffectFrame frame){
		long now = start;
		while(now<end){
			frame.draw(Math.min(1.0, (double)(now-start)/(end-start)));
			now = nextFrame();
		}
	}

	/** 古い画面 from から新しい画面 to へ、追加の効果で移る。コマはオフスクリーン(frame)で作ってから一度に画面へ出す */
	static void playModernEffect(int effect, Graphics2D screen, BufferedImage from, BufferedImage to, long start, long end){
		int width = to.getWidth(), height = to.getHeight();
		int[] a = intPixels(from), b = intPixels(to);
		BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
		int[] f = ((DataBufferInt)frame.getRaster().getDataBuffer()).getData();
		int big = Math.max(width, height);
		switch(effect){
		case FADE: //cross dissolve(to black/white なら dip to black/white)
			animate(start, end, t -> {
				mix(a, b, f, t);
				screen.drawImage(frame, 0, 0, null);
			});
			break;
		case MOSAIC: //古い画面がモザイクに崩れ、新しい画面がモザイクから戻る
			int maxBlock = Math.max(4, big/16);
			int[] mixed = new int[f.length];
			animate(start, end, t -> {
				int block = 1 + (int)Math.round((maxBlock-1)*(1-Math.abs(2*t-1)));
				mix(a, b, mixed, smoothstep(0.42, 0.58, t));
				pixelate(mixed, f, width, height, block);
				screen.drawImage(frame, 0, 0, null);
			});
			break;
		case BLUR: //ぼかしながら溶け合う
			double maxRadius = Math.max(3, big/28.0);
			BufferedImage mixedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
			int[] mp = ((DataBufferInt)mixedImage.getRaster().getDataBuffer()).getData();
			animate(start, end, t -> {
				mix(a, b, mp, smoothstep(0.3, 0.7, t));
				blur(mixedImage, frame, maxRadius*Math.sin(Math.PI*t));
				screen.drawImage(frame, 0, 0, null);
			});
			break;
		case BLOCK_DISSOLVE: { //ブロックが乱順に入れ替わる
			int size = Math.max(8, big/24);
			int columns = (width+size-1)/size, rows = (height+size-1)/size;
			int[] order = new int[columns*rows];
			for(int i=0; i<order.length; i++) order[i] = i;
			java.util.Random random = new java.util.Random(0x5EED);
			for(int i=order.length-1; i>0; i--){ int j = random.nextInt(i+1); int k = order[i]; order[i] = order[j]; order[j] = k; }
			int[] shown = {0};
			animate(start, end, t -> {
				int count = (int)(order.length*t);
				for(; shown[0]<count; shown[0]++){
					int x = order[shown[0]]%columns*size, y = order[shown[0]]/columns*size;
					screen.drawImage(to, x, y, x+size, y+size, x, y, x+size, y+size, null);
				}
			});
			break;
		}
		case RADIAL_WIPE: //時計の針のように12時から右回りに
		case CIRCLE_OPEN:
		case CIRCLE_CLOSE: {
			double radius = Math.hypot(width, height)/2+1, cx = width/2.0, cy = height/2.0;
			//コマはオフスクリーンで作り、1回で画面へ出す。アンチエイリアスの形を画面へ直接塗ると 32x32 のタイルごとに
			//描かれ、途中のタイルが画面に出て四角くちらつく(macOS の Metal など)
			Graphics2D fg = frame.createGraphics();
			fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			fg.setPaint(new TexturePaint(to, new Rectangle2D.Double(0, 0, width, height)));
			animate(start, end, t -> {
				System.arraycopy(a, 0, f, 0, f.length); //毎コマ古い画面から: 縁の半透明が重なって濃くならない
				java.awt.Shape clip;
				if(effect==RADIAL_WIPE) clip = new Arc2D.Double(cx-radius, cy-radius, 2*radius, 2*radius, 90, -360*t, Arc2D.PIE);
				else if(effect==CIRCLE_OPEN) clip = new Ellipse2D.Double(cx-radius*t, cy-radius*t, 2*radius*t, 2*radius*t);
				else{
					double r = radius*(1-t);
					Area area = new Area(new java.awt.Rectangle(0, 0, width, height));
					area.subtract(new Area(new Ellipse2D.Double(cx-r, cy-r, 2*r, 2*r)));
					clip = area;
				}
				//縁を滑らかにするため、形をクリップではなくテクスチャで塗る
				fg.fill(clip);
				screen.drawImage(frame, 0, 0, null);
			});
			fg.dispose();
			break;
		}
		case CROSS_ZOOM: { //古い画面へ寄りながらぶれ、寄った新しい画面から引いて戻る
			BufferedImage zoomed = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
			double maxRadius2 = Math.max(3, big/32.0);
			animate(start, end, t -> {
				Graphics2D g = zoomed.createGraphics();
				try{
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					double fade = smoothstep(0.4, 0.6, t);
					if(fade<1) drawZoomed(g, from, 1+6*t*t, width, height); //加速しながら寄る
					if(fade>0){
						g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float)fade));
						drawZoomed(g, to, 1+6*(1-t)*(1-t), width, height); //減速しながら引く
					}
				}finally{ g.dispose(); }
				blur(zoomed, frame, maxRadius2*Math.pow(Math.sin(Math.PI*t), 2));
				screen.drawImage(frame, 0, 0, null);
			});
			break;
		}
		case PAGE_TURN_LEFT:
		case PAGE_TURN_RIGHT: { //紙をめくる: 右下(page turn right なら左下)の角から紙が円筒状に巻き上がり、裏を見せて反対へ去る
			boolean toLeft = effect==PAGE_TURN_LEFT;
			animate(start, end, t -> {
				pageTurn(a, b, f, width, height, smoothstep(0, 1, t), toLeft);
				screen.drawImage(frame, 0, 0, null);
			});
			break;
		}
		}
	}

	private static double smoothstep(double edge0, double edge1, double x){
		double t = Math.max(0, Math.min(1, (x-edge0)/(edge1-edge0)));
		return t*t*(3-2*t);
	}

	/** image の画素(TYPE_INT_* でなければ写して変換する) */
	private static int[] intPixels(BufferedImage image){
		if(image.getRaster().getDataBuffer() instanceof DataBufferInt && image.getType()==BufferedImage.TYPE_INT_BGR)
			return ((DataBufferInt)image.getRaster().getDataBuffer()).getData();
		BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_BGR);
		Graphics2D g = copy.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return ((DataBufferInt)copy.getRaster().getDataBuffer()).getData();
	}

	/** out = a*(1-t) + b*t(各色8ビット) */
	static void mix(int[] a, int[] b, int[] out, double t){
		int k = (int)Math.round(t*256), j = 256-k;
		if(k<=0){ System.arraycopy(a, 0, out, 0, out.length); return; }
		if(k>=256){ System.arraycopy(b, 0, out, 0, out.length); return; }
		for(int i=0; i<out.length; i++){
			int p = a[i], q = b[i];
			out[i] = (((p&0xFF00FF)*j + (q&0xFF00FF)*k)>>>8 & 0xFF00FF) | (((p&0xFF00)*j + (q&0xFF00)*k)>>>8 & 0xFF00);
		}
	}

	/** 明るさを factor(0..1)倍にする */
	private static int shade(int p, double factor){
		int k = (int)(factor*256);
		if(k>=256) return p;
		return ((p&0xFF00FF)*k>>>8 & 0xFF00FF) | ((p&0xFF00)*k>>>8 & 0xFF00);
	}

	/** block 四方のモザイク。升目は画面の中央に揃える */
	static void pixelate(int[] src, int[] out, int width, int height, int block){
		if(block<=1){ System.arraycopy(src, 0, out, 0, out.length); return; }
		int ox = -((block - width%block)%block)/2, oy = -((block - height%block)%block)/2;
		for(int by=oy; by<height; by+=block){
			int y0 = Math.max(0, by), y1 = Math.min(height, by+block);
			for(int bx=ox; bx<width; bx+=block){
				int x0 = Math.max(0, bx), x1 = Math.min(width, bx+block);
				long r=0, g=0, bl=0;
				for(int y=y0; y<y1; y++) for(int x=x0, i=y*width+x0; x<x1; x++, i++){
					int p = src[i]; r += p>>16&0xFF; g += p>>8&0xFF; bl += p&0xFF;
				}
				int n = (y1-y0)*(x1-x0);
				int avg = (int)(r/n)<<16 | (int)(g/n)<<8 | (int)(bl/n);
				for(int y=y0; y<y1; y++) java.util.Arrays.fill(out, y*width+x0, y*width+x1, avg);
			}
		}
	}

	/** src を半径 radius でぼかして out へ。縮めた絵に箱型ぼかしを2回かけて拡大する(ガウスぼかしに近く、半径によらず速い) */
	static void blur(BufferedImage src, BufferedImage out, double radius){
		Graphics2D g = out.createGraphics();
		try{
			if(radius<0.75){ g.drawImage(src, 0, 0, null); return; }
			int width = src.getWidth(), height = src.getHeight();
			int factor = Math.max(1, (int)(radius/3));
			int sw = Math.max(1, (width+factor-1)/factor), sh = Math.max(1, (height+factor-1)/factor);
			BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_BGR);
			Graphics2D sg = small.createGraphics();
			sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			sg.drawImage(src, 0, 0, sw, sh, null);
			sg.dispose();
			int[] p = ((DataBufferInt)small.getRaster().getDataBuffer()).getData();
			int[] tmp = new int[p.length];
			int r = Math.max(1, (int)Math.round(radius/factor/1.6));
			boxBlur(p, tmp, sw, sh, r);
			boxBlur(p, tmp, sw, sh, r);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(small, 0, 0, width, height, null);
		}finally{
			g.dispose();
		}
	}

	/** 半径 r の箱型ぼかし(横、縦の順)。結果は p に戻る。端は端の画素を延ばす */
	private static void boxBlur(int[] p, int[] tmp, int w, int h, int r){
		blurLine(p, tmp, w, h, r, 1, w);
		blurLine(tmp, p, h, w, r, w, 1);
	}

	/** lines 本の線(長さ length、画素の間隔 step、線の間隔 lineStep)をそれぞれぼかす */
	private static void blurLine(int[] src, int[] out, int length, int lines, int r, int step, int lineStep){
		int div = 2*r+1;
		for(int line=0; line<lines; line++){
			int base = line*lineStep;
			int sr=0, sg=0, sb=0;
			for(int i=-r; i<=r; i++){
				int q = src[base + Math.max(0, Math.min(length-1, i))*step];
				sr += q>>16&0xFF; sg += q>>8&0xFF; sb += q&0xFF;
			}
			for(int i=0; i<length; i++){
				out[base+i*step] = (sr/div)<<16 | (sg/div)<<8 | sb/div;
				int qo = src[base + Math.max(0, i-r)*step], qi = src[base + Math.min(length-1, i+r+1)*step];
				sr += (qi>>16&0xFF) - (qo>>16&0xFF); sg += (qi>>8&0xFF) - (qo>>8&0xFF); sb += (qi&0xFF) - (qo&0xFF);
			}
		}
	}

	/** image を中央を中心に scale 倍して描く */
	private static void drawZoomed(Graphics2D g, BufferedImage image, double scale, int width, int height){
		double w = width*scale, h = height*scale;
		g.drawImage(image, (int)Math.round((width-w)/2), (int)Math.round((height-h)/2), (int)Math.round(w), (int)Math.round(h), null);
	}

	/** 紙をめくる1コマ。折り目は少し傾いた直線で、その先の紙は半径 r の円筒に巻き付き、巻き切った分は裏返って
	 *  折り目の手前に重なる。progress 0 で古い画面、1 で新しい画面だけになる */
	static void pageTurn(int[] old, int[] neu, int[] out, int width, int height, double progress, boolean toLeft){
		//n: 動かない側から、めくれる縁への向き(右下の角から始まるよう少し下へ傾ける)
		double nx = toLeft ? 1 : -1, ny = 0.3, len = Math.hypot(nx, ny);
		nx /= len; ny /= len;
		double sMin = Math.min(0, width*nx) + Math.min(0, height*ny), sMax = Math.max(0, width*nx) + Math.max(0, height*ny);
		double r = Math.max(8, Math.min(width, height)/7.0);
		double shadowWidth = r*0.9;
		double c = sMax + (sMin - r - shadowWidth - sMax)*progress; //折り目の位置(最後は影も画面の外へ)
		for(int y=0; y<height; y++){
			int row = y*width;
			for(int x=0; x<width; x++){
				double s = x*nx + y*ny;
				int i = row+x;
				if(s > c+r){ //巻き上がった紙の外: 新しい画面と紙の影
					double d = s-(c+r);
					out[i] = d>=shadowWidth ? neu[i] : shade(neu[i], 0.6+0.4*d/shadowWidth);
					continue;
				}
				//手前(高い)層から: 平らに重なった裏面 → 円筒の裏側 → 円筒の表側
				int pixel = -1;
				if(s < c){
					double s0 = 2*c + Math.PI*r - s;
					int q = sample(old, width, height, x + (s0-s)*nx, y + (s0-s)*ny);
					if(q!=-1) pixel = paperBack(q, 0.97);
				}
				else{
					double angle = Math.asin(Math.min(1, (s-c)/r));
					double s0 = c + (Math.PI-angle)*r;
					int q = sample(old, width, height, x + (s0-s)*nx, y + (s0-s)*ny);
					if(q!=-1) pixel = paperBack(q, 0.72+0.25*Math.cos(angle));
					else{
						s0 = c + angle*r;
						q = sample(old, width, height, x + (s0-s)*nx, y + (s0-s)*ny);
						if(q!=-1) pixel = shade(q, 0.55+0.45*Math.cos(angle));
					}
				}
				if(pixel!=-1) out[i] = pixel;
				else if(s < c) out[i] = old[i]; //まだめくれていない紙
				else out[i] = shade(neu[i], 0.6); //巻きの下
			}
		}
	}

	/** 画像の (x, y) に最も近い画素。外なら -1 */
	private static int sample(int[] image, int width, int height, double x, double y){
		int xi = (int)Math.floor(x+0.5), yi = (int)Math.floor(y+0.5);
		if(xi<0 || yi<0 || xi>=width || yi>=height) return -1;
		return image[yi*width+xi] & 0xFFFFFF;
	}

	/** 紙の裏: 表の絵がうっすら透けた白い紙を light の明るさで */
	private static int paperBack(int p, double light){
		int q = ((p&0xFF00FF)*51>>>8 & 0xFF00FF) | ((p&0xFF00)*51>>>8 & 0xFF00); //表の20%
		q += 0xCCCCCC; //紙の白80%
		return shade(q, light);
	}

	private final static int getDissolveOffY(int i){
		switch(i){
		case 0: return 0;
		case 1: return 0;
		case 2: return 1;
		case 3: return 1;
		case 4: return 0;
		case 5: return 0;
		case 6: return 1;
		case 7: return 1;
		case 8: return 1;
		case 9: return 1;
		case 10: return 0;
		case 11: return 0;
		case 12: return 1;
		case 13: return 1;
		case 14: return 0;
		case 15: return 0;
		}
		return 0;
	}
	private final static int getDissolveOffX(int i){
		switch(i){
		case 0: return 0;
		case 1: return 4;
		case 2: return 2;
		case 3: return 6;
		case 4: return 3;
		case 5: return 7;
		case 6: return 1;
		case 7: return 5;
		case 8: return 0;
		case 9: return 4;
		case 10: return 2;
		case 11: return 6;
		case 12: return 3;
		case 13: return 7;
		case 14: return 1;
		case 15: return 5;
		}
		return 0;
	}
}

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.util.ArrayList;

import javax.swing.JPanel;


//カードピクチャとバックグラウンドピクチャを表示するパネル
class MyPanel extends JPanel{
	private static final long serialVersionUID = 1L;
	PCARDFrame owner;
	
	MyPanel(PCARDFrame owner){
		super();
		this.owner = owner;
	}

	//HyperCard leaves the pointer alone while a handler runs: a script's choose tool / drag with a paint tool shows the
	//tool's cursor only when the handler is over (年表: set the cursor to none, choose brush tool, drag from … to …;
	//the brush cursor came up in the middle of the script). The last cursor asked for meanwhile waits for returnToIdle.
	private java.awt.Cursor heldCursor;
	private boolean cursorHeld;
	@Override
	public void setCursor(java.awt.Cursor cursor){
		if(owner!=null && owner==PCARD.pc && !TTalk.idle){
			heldCursor = cursor;
			cursorHeld = true;
			return;
		}
		cursorHeld = false;
		super.setCursor(cursor);
	}
	/** HyperZebra's own cursor (the wait cursor of StackProgress.busy): at once, also while a handler runs. */
	void setCursorNow(java.awt.Cursor cursor){
		super.setCursor(cursor);
	}
	/** The cursor a handler asked for, now that it is over (TTalk.returnToIdle). */
	void releaseHeldCursor(){
		if(!cursorHeld) return;
		cursorHeld = false;
		super.setCursor(heldCursor);
	}

	//AddColor の合成パネルが一番上に重なっているときは「子同士は重ならない」前提の最適化描画をやめる。
	//最適化のままだと、スクリプトが put した部品だけが再描画されて合成パネルの上に素のまま現れ、
	//実機では AddColor の絵に隠れている部品が見えてしまう
	@Override
	public boolean isOptimizedDrawingEnabled(){
		return !ShapeTool.active() && (PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.addColor==null);
	}

	//部品が自分だけを描き直す(フィールドのキャレットの移動・点滅、テキストの変更)ときも、カードはこのパネルから描かせる。
	//部品は重なる(バックグラウンドのフィールドの上にカードの絵やカードのボタン)のに、上の最適化描画は重ならない前提なので、
	//不透明なフィールドが自分だけを描くと上に載った部品を塗りつぶしていた(つばめ飛ぶ: カード全体のマップのフィールドを
	//クリックするとキャレットが動いてフィールド全体を描き直し、会話中のキャラクター(アイコンのボタン)が消えてマップだけ残った)。
	//カードの上に重ねる絵(68K XCMDがカードウインドウに描いた絵、Terazzaのスプライト、AddColor)も、部品だけが描かれると
	//その上に素の部品が現れる(Myst PreviewのHyperTintの絵の上のフィールド)
	@Override
	protected boolean isPaintingOrigin(){
		return PCARD.pc!=null && PCARD.pc.mainPane==this;
	}

	//カード本体(および子コンポーネント)を通常通り描画した後、スプライトの
	//重ね合わせバッファを一番上に重ねて描く。paint()をオーバーライドして
	//いるのは、Swing自身が(このアニメーションループとは無関係のタイミングで)
	//子コンポーネントを再描画したときも、必ず最新の重ね合わせ用バッファの内容が
	//一緒に描かれるようにするため。これをしないと、スプライトとして画面に描いた
	//絵が、Swingが独自に行う再描画によって消されてしまい(チラつきの原因)、
	//しかもスプライトで隠していたはずのカード本体の絵(白黒のビットマップ等)が
	//一瞬見えてしまう。
	@Override
	public void paint(Graphics g){
		//68K XCMDがビデオドライバのガンマテーブルを変えている間(Best of HC ExternalsのFade)は、Macの画面と同じく
		//カードウインドウ全体をその表に通して見せる。外部コマンドが読むカードの絵(capturingEffect)は表を通さない
		int[] gamma = CardWindow68k.gamma;
		if(gamma!=null && !PCARD.capturingEffect && !gammaPass && PCARD.pc!=null && PCARD.pc.mainPane==this){
			paintThroughGamma(g, gamma);
			return;
		}
		//lock screen中は、部品ごとにpaintComponentを止めるだけでは不十分。
		//setVisible/setBoundsなどがSwing内部の再描画を発生させると、親や背景だけが
		//描かれて部品が白抜けするため、ロック開始時の完成画面をパネル全体として
		//描き戻す。visual effect用のオフスクリーン取得中だけ通常描画を許可する。
		BufferedImage frozen = VEffect.oldoff;
		if(PCARD.lockedScreen && !PCARD.capturingEffect && frozen!=null){
			g.drawImage(frozen, 0, 0, getWidth(), getHeight(),
				0, 0, frozen.getWidth(), frozen.getHeight(), null);
			//68K XCMDがカードウインドウに直接描いた絵はロック中も見える(lock screenが止めるのはHyperCard自身のカードの描画だけ。
			//ANIMATEのイントロは前のカードのcloseCardのlock screenの下、openCardで動く)
			BufferedImage external = CardWindow68k.overlay;
			if(external!=null && PCARD.pc!=null && PCARD.pc.mainPane==this) PCARD.drawScaled(g, external, 0, 0, null);
			return;
		}
		//部品の表示/非表示を68K XCMD(ColorizeHC)が描き直す間は、変更前の画面のまま(CardWindow68k.holdScreen)
		BufferedImage held = CardWindow68k.held;
		if(held!=null && !PCARD.capturingEffect && PCARD.pc!=null && PCARD.pc.mainPane==this){
			g.drawImage(held, 0, 0, null);
			return;
		}
		if(PCARD.lockedScreen && !PCARD.capturingEffect && VEffect.lazyLock){
			//絵なしの lock screen: 部品はまだロック開始時のままなので、そのまま描けばロック中の画面になる
			PCARD.capturingEffect = true;
			try{ paint(g); }finally{ PCARD.capturingEffect = false; }
			return;
		}
		BufferedImage addColor = PCARD.pc!=null && PCARD.pc.stack!=null ? PCARD.pc.stack.addColor : null;
		if(addColor!=null && PCARD.pc.mainPane==this){
			//AddColor: 部品まで描いたカードを一旦オフスクリーンに描き、その上に色の絵を合成してから画面へ
			Rectangle clip = g.getClipBounds();
			if(clip==null) clip = new Rectangle(0, 0, getWidth(), getHeight());
			clip = clip.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
			if(clip.width>0 && clip.height>0){
				BufferedImage off = new BufferedImage(clip.width, clip.height, BufferedImage.TYPE_INT_ARGB);
				Graphics2D og = off.createGraphics();
				og.translate(-clip.x, -clip.y);
				og.setClip(clip);
				super.paint(og);
				og.dispose();
				TXcmd.AddColor.composite(off, clip.x, clip.y, clip.width, clip.height);
				g.drawImage(off, clip.x, clip.y, null);
			}
		}
		else super.paint(g);
		BufferedImage overlay = TXcmd.terazzaOverlay;
		if(TXcmd.terazzaAry != null && overlay != null){
			g.drawImage(overlay, 0, 0, null);
		}
		//68K XCMDが「カードウインドウ」に直接描いた絵(論理座標なのでscale()倍する)
		BufferedImage external = CardWindow68k.overlay;
		if(external != null){
			PCARD.drawScaled(g, external, 0, 0, null);
		}
		//ボタン/フィールドツールの選択枠(マーチングアンツ)。ここで毎回描き直す
		//ことで、部品を移動しても古い位置の枠がSwingの再描画によって
		//きちんと消え、かつ実機のHyperCardのようにアニメーションする。
		Component selTarget = ButtonGUI.currentAuthToolTarget();
		if(selTarget != null && g instanceof Graphics2D){
			ButtonGUI.paintAnts((Graphics2D) g, selTarget);
		}
		//Command-Option のピークの輪郭も同じく毎回描く
		if(GUI.stroke_frag != 0 && PCARD.pc != null && PCARD.pc.mainPane == this) GUI.paintPeekOutlines(g);
	}


	/** True while {@link #paintThroughGamma} paints the card into its offscreen picture. */
	private boolean gammaPass;
	/** Paints the card into an offscreen picture (at the device scale) and shows it through the gamma ramps (CardWindow68k.gamma). */
	private void paintThroughGamma(Graphics g, int[] gamma){
		Rectangle clip = g.getClipBounds();
		if(clip==null) clip = new Rectangle(0, 0, getWidth(), getHeight());
		clip = clip.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
		if(clip.width<=0 || clip.height<=0) return;
		AffineTransform tx = ((Graphics2D)g).getTransform();
		double sx = Math.max(1, Math.abs(tx.getScaleX())), sy = Math.max(1, Math.abs(tx.getScaleY()));
		int iw = (int)Math.ceil(clip.width*sx), ih = (int)Math.ceil(clip.height*sy);
		BufferedImage off = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
		Graphics2D og = off.createGraphics();
		gammaPass = true;
		try{
			og.scale(sx, sy);
			og.translate(-clip.x, -clip.y);
			og.setClip(clip);
			paint(og);
		}finally{ gammaPass = false; og.dispose(); }
		int[] px = ((java.awt.image.DataBufferInt)off.getRaster().getDataBuffer()).getData();
		MacAppHost.applyGamma(gamma, px, 0, px.length);
		g.drawImage(off, clip.x, clip.y, clip.width, clip.height, null);
	}

	static interface blendMode{
		static final int Null=-1;
		static final int Copy=0;
		static final int Blend=1;
		static final int Add=2;
		static final int Subtract=3;
		static final int Multiply=4;
		static final int Screen=5;
		static final int Darken=6;
		static final int Lighten=7;
		static final int Difference=8;
		static final int Hue=9;
		static final int Color=10;
		static final int Saturation=11;
		static final int Luminosity=12;
		static final int AlphaChannel=13;
	}
	
	int getBlendMode(String str) {
		if(str.equals("Copy")) return blendMode.Copy;
		if(str.equals("Blend")) return blendMode.Blend;
		if(str.equals("Add")) return blendMode.Add;
		if(str.equals("Subtract")) return blendMode.Subtract;
		if(str.equals("Multiply")) return blendMode.Multiply;
		if(str.equals("Screen")) return blendMode.Screen;
		if(str.equals("Darken")) return blendMode.Darken;
		if(str.equals("Lighten")) return blendMode.Lighten;
		if(str.equals("Difference")) return blendMode.Difference;
		if(str.equals("Hue")) return blendMode.Hue;
		if(str.equals("Color")) return blendMode.Color;
		if(str.equals("Saturation")) return blendMode.Saturation;
		if(str.equals("Luminosity")) return blendMode.Luminosity;
		if(str.equals("Alpha Channel")) return blendMode.AlphaChannel;
		return blendMode.Null;
	}
	
	
	
	//浮動選択のブレンド結果のキャッシュ。以前は再描画のたびにフルサイズの画像を
	//新規作成して全画素を処理していた(選択枠のアニメーションで100msごとに走る)。
	//浮動選択の画像・位置・モード・レベルが変わらなければ前回の結果を使い回す。
	//サーフェース側が変わる操作は必ずPaintUndo.snapshot()を通るので、そこで
	//invalidateBlendCache()を呼んで捨てる。
	private static BufferedImage blendCache;
	private static BufferedImage blendSurfaceCache; //AlphaChannelモード用(サーフェースのコピー)
	private static BufferedImage blendCacheSrc;
	private static BufferedImage blendCacheSurface;
	private static int blendCacheOx, blendCacheOy, blendCacheMode, blendCacheLevel;

	static void invalidateBlendCache(){
		blendCache = null;
		blendSurfaceCache = null;
		blendCacheSrc = null;
		blendCacheSurface = null;
	}

	//浮動選択の現在位置(サーフェース座標)
	static Point floatOffset(PCARDFrame frame){
		FloatingSelection f = frame==null ? null : frame.floating;
		return f==null ? new Point(0,0) : f.position();
	}

	private boolean blendCacheValid(BufferedImage flowImage, BufferedImage surface, Point offset){
		return blendCache!=null && blendCacheSrc==flowImage && blendCacheSurface==surface &&
			blendCacheOx==offset.x && blendCacheOy==offset.y &&
			blendCacheMode==owner.blendMode && blendCacheLevel==owner.blendLevel;
	}

	@Override
	protected void paintComponent(Graphics g){
		//ペイントツール用メイン描画部
		if(owner.tool==null) return;
		BufferedImage surface = owner.getSurface();
		if(surface==null) return;
		Graphics2D g2d = (Graphics2D) g;

		//0. 縮小表示では絵の外側が見えるので、灰色で塗って絵の範囲を分かるようにする
		if(owner.bit<1){
			g.setColor(new Color(0xCCCCCC));
			g.fillRect(0, 0, getWidth(), getHeight());
			float eb = owner.bit*appScale();
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, Math.round(surface.getWidth()*eb), Math.round(surface.getHeight()*eb));
		}

		//1. バックグラウンドと、編集中のレイヤーより下のレイヤー(追加レイヤーがあれば
		//平らにしたもの。無ければバックグラウンドの絵そのもの)
		LayerDisplay ld = LayerDisplay.of(owner);
		if(ld.under!=null){
			mainPaneDraw(g, ld.under);
		}

		//2. 浮動選択のブレンド結果を用意(AlphaChannelモードはサーフェース自体を差し替える)
		BufferedImage flowImage = null;
		BufferedImage surfaceToDraw = surface;
		Point offset = null;
		boolean floating = owner.floating!=null;
		if(floating){
			flowImage = owner.floating.image;
			offset = floatOffset(owner);
			if(owner.blendMode!=blendMode.Copy || owner.blendLevel!=100){
				if(!blendCacheValid(flowImage, surface, offset)){
					BufferedImage newImage = new BufferedImage(flowImage.getWidth(), flowImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
					BufferedImage sfcImage = null;
					if(owner.blendMode==blendMode.AlphaChannel){
						sfcImage = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_ARGB);
						Graphics2D sfcg = sfcImage.createGraphics();
						sfcg.setComposite(AlphaComposite.Src);
						sfcg.drawImage(surface,0,0,null);
						sfcg.dispose();
						makeBlendImage(newImage, flowImage, sfcImage, offset, owner.blendMode, owner.blendLevel);
					}
					else{
						makeBlendImage(newImage, flowImage, surface, offset, owner.blendMode, owner.blendLevel);
					}
					blendCache = newImage;
					blendSurfaceCache = sfcImage;
					blendCacheSrc = flowImage;
					blendCacheSurface = surface;
					blendCacheOx = offset.x;
					blendCacheOy = offset.y;
					blendCacheMode = owner.blendMode;
					blendCacheLevel = owner.blendLevel;
				}
				flowImage = blendCache;
				if(blendSurfaceCache!=null) surfaceToDraw = blendSurfaceCache;
			}
		}

		//3. サーフェース(編集中のレイヤー)。合成モード・不透明度・クリップのある
		//追加レイヤーは、下の絵に合成した結果を描く。上のレイヤーに合成モードのものが
		//あれば、上まで含めて平らにした絵を描く(プレビューと浮動選択はその上に重ねる)
		if(ld.full!=null){
			mainPaneDraw(g, ld.full);
		}
		else if(ld.activeBlended!=null){
			mainPaneDraw(g, ld.activeBlended);
		}
		else if(!ld.hideActive){
			mainPaneDraw(g, surfaceToDraw);
		}

		//4. ストローク中の一時レイヤー(半透明ブラシ・消しゴム・図形のプレビュー)。
		//アルファロックのレイヤーでは、確定時と同じく不透明な所だけに見せる
		if(owner.previewVisible && owner.previewBuf!=null){
			BufferedImage preview = owner.previewBuf;
			PaintLayer act = owner.activePaintLayer();
			if(act!=null && act.alphaLock && !(owner.tool instanceof toolSelectInterface)){
				preview = PaintLayers.maskedBy(preview, surface);
			}
			Graphics2D pg = (Graphics2D) g.create();
			pg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, owner.previewAlpha))));
			mainPaneDraw(pg, preview);
			pg.dispose();
		}

		//5. 浮動選択
		if(floating && flowImage!=null){
			AffineTransform saveAT = g2d.getTransform();
			if(owner.selectaf!=null){
				g2d.setTransform(owner.selectaf);
			}
			mainPaneDraw(g, flowImage, offset.x, offset.y, flowImage.getWidth(), flowImage.getHeight());
			g2d.setTransform(saveAT);
		}

		//5.5 編集中のレイヤーより上の(通常合成の)レイヤー
		if(ld.above!=null){
			mainPaneDraw(g, ld.above);
		}

		//6. 拡大表示時の枠線表示(4倍以上。3倍以下ではマス目が細かすぎて灰色に見える)
		if(owner.bit >= 4){
			bordersDraw(g, surface.getWidth(), surface.getHeight());
		}

		//6.5 対称描画の軸/中心のガイド
		PaintTool.paintSymmetryGuide(g2d, owner);

		//7. ツール固有のオーバーレイ(ドラッグ中の選択範囲など)と浮動選択の選択枠
		Graphics2D og = (Graphics2D) g.create();
		try{
			//the tool goes away when a script leaves the paint tools while this paints (AOL Dragger: doMenu "Clear Picture")
			toolInterface tool = owner.tool;
			if(tool!=null) tool.paintOverlay(og);
			if(owner.floating!=null){
				owner.floating.paintAnts(og);
				//拡大縮小・回転のハンドル(座標変換はPaintTool.ownerのもの)
				if(PaintTool.owner==owner) PaintTool.paintTransformHandles(og);
			}
		}
		finally{
			og.dispose();
		}
	}


	//追加レイヤーがあるときの表示用の絵。編集中のレイヤーより下(under: バックグラウンド
	//と下のレイヤーを平らにしたもの)と上(above: 通常合成なら透明の上に平らにしたもの)は、
	//レイヤーの構成(OCardBase.layerVersion)・編集中の番号・元画像が同じ間は使い回す。
	//編集中のレイヤー自身に合成モード等があればactiveBlended(下に合成した結果)、上の
	//レイヤーに合成モードのものがあればfull(上まで全部)を毎回作る(ストローク中の
	//再描画でも数msなので許容)。
	static class LayerDisplay {
		BufferedImage under, activeBlended, above, full;
		//編集中のレイヤーが非表示(描いても見えない。パレットの目を開けるまで)
		boolean hideActive;

		private static BufferedImage cUnder, cUnderBase, cUnderBg; private static OCardBase cUnderGroup;
		private static int cUnderVersion=-1, cUnderActive=-1; private static boolean cUnderEditBg;
		private static BufferedImage cAbove; private static OCardBase cAboveGroup;
		private static int cAboveVersion=-1, cAboveActive=-1;
		private static BufferedImage cBgFlat, cBgFlatBase; private static OCardBase cBgFlatGroup;
		private static int cBgFlatVersion=-1;

		static void invalidate(){
			cUnder = null; cAbove = null; cBgFlat = null;
		}

		static LayerDisplay of(PCARDFrame owner){
			LayerDisplay d = new LayerDisplay();
			boolean editBg = owner.isEditingBackground();
			OCardBase group = owner.paintGroup();
			BufferedImage base = editBg ? owner.bgImg : owner.mainImg;
			if(group==null || !group.hasLayers()){
				//追加レイヤー無し: 従来どおり(カード編集中はバックグラウンドの絵が下)
				if(!editBg && owner.bgImg!=null){
					OCardBase bg = owner.paintGroup(true);
					d.under = (bg!=null && bg.hasLayers()) ? bgFlat(owner, bg) : owner.bgImg;
				}
				return d;
			}
			int active = owner.activeLayer();
			int w = base.getWidth(), h = base.getHeight();
			//下
			OCardBase bgGroup = editBg ? null : owner.paintGroup(true);
			BufferedImage bgFlat = (!editBg && owner.bgImg!=null) ? ((bgGroup!=null && bgGroup.hasLayers()) ? bgFlat(owner, bgGroup) : owner.bgImg) : null;
			if(active==0 && editBg){
				d.under = null;
			}
			else if(active==0){
				d.under = bgFlat;
			}
			else{
				boolean valid = cUnder!=null && cUnderGroup==group && cUnderVersion==group.layerVersion && cUnderActive==active &&
					cUnderBase==base && cUnderBg==bgFlat && cUnderEditBg==editBg;
				if(!valid){
					ArrayList<PaintLayer> views = new ArrayList<PaintLayer>();
					views.add(new PaintLayer("base", base));
					for(int i=0; i<active-1; i++) views.add(group.layers.get(i));
					cUnder = PaintLayers.flatten(bgFlat, views, w, h);
					cUnderGroup = group; cUnderVersion = group.layerVersion; cUnderActive = active;
					cUnderBase = base; cUnderBg = bgFlat; cUnderEditBg = editBg;
				}
				d.under = cUnder;
			}
			//上のレイヤーに合成モードのものがあるか
			boolean abovePlain = true;
			for(int i=active; i<group.layers.size(); i++){
				PaintLayer l = group.layers.get(i);
				if(l.visible && !l.plain()) abovePlain = false;
			}
			PaintLayer act = active==0 ? null : group.layers.get(active-1);
			BufferedImage surface = owner.getSurface();
			d.hideActive = act!=null && !act.visible;
			BufferedImage clip = null;
			if(act!=null && act.clipToBelow) clip = active==1 ? base : group.layers.get(active-2).image;
			if(!abovePlain){
				//上まで全部を毎回平らにする
				ArrayList<PaintLayer> views = new ArrayList<PaintLayer>();
				PaintLayer a = act==null ? new PaintLayer("active", surface) : new PaintLayer(act.name, surface);
				if(act!=null){ a.blend = act.blend; a.opacity = act.opacity; a.clipToBelow = act.clipToBelow; }
				views.add(a);
				for(int i=active; i<group.layers.size(); i++) views.add(group.layers.get(i));
				//クリップの「すぐ下」はflattenがbaseで扱うので、下のレイヤーの画像を渡す
				BufferedImage full = PaintLayers.flatten(d.under, new ArrayList<PaintLayer>(), w, h);
				BufferedImage below = clip;
				for(PaintLayer l : views){
					if(l.visible && l.image!=null && !(l.clipToBelow && below==null)){
						PaintLayers.blendOnto(full, l.image, l.blend, l.opacity, l.clipToBelow ? below : null);
					}
					below = l.image;
				}
				d.full = full;
				d.under = null;
				return d;
			}
			if(act!=null && !act.plain() && act.visible){
				BufferedImage blended = PaintLayers.flatten(d.under, new ArrayList<PaintLayer>(), w, h);
				if(!(act.clipToBelow && clip==null)) PaintLayers.blendOnto(blended, surface, act.blend, act.opacity, clip);
				d.activeBlended = blended;
				d.under = null;
			}
			//上(通常合成だけ): 透明の上に平らにしたものを使い回す
			if(active<group.layers.size()){
				boolean valid = cAbove!=null && cAboveGroup==group && cAboveVersion==group.layerVersion && cAboveActive==active;
				if(!valid){
					ArrayList<PaintLayer> views = new ArrayList<PaintLayer>();
					for(int i=active; i<group.layers.size(); i++) views.add(group.layers.get(i));
					cAbove = PaintLayers.flatten(null, views, w, h);
					cAboveGroup = group; cAboveVersion = group.layerVersion; cAboveActive = active;
				}
				d.above = cAbove;
			}
			return d;
		}

		//カード編集中のバックグラウンド(pict+追加レイヤー)。bgImgがpictの複製なので
		//bgImgを元に平らにする
		private static BufferedImage bgFlat(PCARDFrame owner, OCardBase bg){
			boolean valid = cBgFlat!=null && cBgFlatGroup==bg && cBgFlatVersion==bg.layerVersion && cBgFlatBase==owner.bgImg;
			if(!valid){
				cBgFlat = PaintLayers.flatten(owner.bgImg, bg.layers, owner.bgImg.getWidth(), owner.bgImg.getHeight());
				cBgFlatGroup = bg; cBgFlatVersion = bg.layerVersion; cBgFlatBase = owner.bgImg;
			}
			return cBgFlat;
		}
	}

	//拡大表示のマス目(IconEditorのUse Grid)。論理座標のgridSizeごとに線を引く。
	//倍率が整数でなくても、線は論理画素の境界(toPhysicalと同じ丸め)に乗る。
	void bordersDraw(Graphics g, int width, int height){
		if(!PCARDFrame.useGrid) return;
		float eb = owner.bit*appScale();
		int left = PaintTool.viewLeftOf(owner), top = PaintTool.viewTopOf(owner);
		int step = Math.max(1, PCARDFrame.gridSize);
		int pw = Math.round((width-left)*eb), ph = Math.round((height-top)*eb);
		g.setColor(Color.GRAY);
		for(int lx=left-(left%step); lx<=width; lx+=step){
			int px = Math.round((lx-left)*eb);
			if(px<0) continue;
			g.drawLine(px, 0, px, ph);
		}
		for(int ly=top-(top%step); ly<=height; ly+=step){
			int py = Math.round((ly-top)*eb);
			if(py<0) continue;
			g.drawLine(0, py, pw, py);
		}
	}

	//IconEditor等(PCARD.pc以外のPCARDFrame)は2倍拡大表示モードの対象外なので、
	//アプリの拡大率はメインカードウインドウ(PCARD.pc)の場合のみ適用する。
	float appScale(){
		return (owner == PCARD.pc) ? (float)PCARD.scale() : 1;
	}

	void mainPaneDraw(Graphics g, BufferedImage img){
		mainPaneDraw(g, img, 0, 0, img.getWidth(), img.getHeight());
	}

	//画像の論理矩形(x,y,width,height)を、表示倍率(FatBits/Zoom×アプリの2倍表示)と
	//表示位置を掛けてmainPaneへ描く。拡大はドット絵をにじませずピクセルパーフェクトに
	//(最近傍)、縮小は補間して描く。
	public void mainPaneDraw(Graphics g, BufferedImage img, int x, int y, int width, int height){
		float eb = owner.bit*appScale();
		if(eb == 1){
			g.drawImage(img, x, y, x+width, y+height, 0, 0, width, height, null);
			return;
		}
		if(g instanceof Graphics2D){
			//FatBits/Zoomなしでカードの拡大率が整数でない(最大125%など)ときは、ブラウズ中と同じく滑らかに描く
			((Graphics2D)g).setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				owner.bit==1 && eb>1 ? DisplayZoom.interpolation(eb) :
				eb>1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		}
		int left = PaintTool.viewLeftOf(owner), top = PaintTool.viewTopOf(owner);
		int x0 = Math.round((x-left)*eb), y0 = Math.round((y-top)*eb);
		int x1 = Math.round((x+width-left)*eb), y1 = Math.round((y+height-top)*eb);
		g.drawImage(img, x0, y0, x1, y1, 0, 0, width, height, null);
	}


	//浮動選択を確定するときに、現在のブレンドモードを適用した画像を作る
	public static BufferedImage makeBlendImage(BufferedImage flowImage)
	{
		if(PaintTool.owner.blendMode==blendMode.Copy && PaintTool.owner.blendLevel==100){
			return flowImage;
		}
		
		BufferedImage newImage = new BufferedImage(flowImage.getWidth(), flowImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Point offset = floatOffset(PaintTool.owner);
		
		makeBlendImage(newImage, flowImage, PaintTool.owner.getSurface(), offset,
				PaintTool.owner.blendMode, PaintTool.owner.blendLevel);
		
		return newImage;
	}
	
	
	//flowImage(浮動選択)をsurfaceImage上のoffset位置へmodeで合成した結果をnewImageへ書く。
	//newImageはflowImageと同じ大きさで透明に初期化されていること。
	//AlphaChannelモードだけはsurfaceImage自身のアルファを書き換える。
	static void makeBlendImage(BufferedImage newImage,
			BufferedImage flowImage, BufferedImage surfaceImage, Point offset, int mode, int level)
	{
		if(flowImage.hasTileWriters()){
			BufferedImage flowImage2 = new BufferedImage(flowImage.getWidth(), flowImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D newg = flowImage2.createGraphics();
			newg.setComposite(AlphaComposite.Src);
			newg.drawImage(flowImage, 0,0,null);
			newg.dispose();
			flowImage = flowImage2;
		}
		if(level<0) level = 0;
		if(level>100) level = 100;

		switch(mode){
		case blendMode.Copy:
			copyImage(newImage, flowImage, level);
			return;
		case blendMode.Blend:
			blendImage(newImage, flowImage, level);
			return;
		case blendMode.AlphaChannel:
			alphaImage(flowImage, surfaceImage, offset, level);
			return;
		default:
			break;
		}

		//サーフェースの画素を参照するモード
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = Math.min(srcwidth, newImage.getWidth());
		int height = Math.min(srcheight, newImage.getHeight());
		int newwidth = newImage.getWidth();
		float[] hsb = new float[3];
		float[] sfc_hsb = new float[3];
		for(int v=0; v<height; v++){
			int sy = v+offset.y;
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(0, h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha==0) continue;

				//サーフェースの範囲外へはみ出した部分は、そのままの色で表示する
				//(以前は移動後の座標を選択画像自身の幅と比較していたため、
				//少し移動しただけで画素が消えていた)
				int sx = h+offset.x;
				if(sx<0 || sy<0 || sx>=sfcwidth || sy>=sfcheight){
					newbuf.setElem(0, h+v*newwidth, c);
					continue;
				}
				int sc = surfacebuf.getElem(0, sx+sy*sfcwidth);
				if((sc&0xFF000000)==0){
					//透明部分は白(lightenだけは黒)扱い
					sc = (mode==blendMode.Lighten) ? 0x00000000 : 0x00FFFFFF;
				}
				int rgb = blendPixel(mode, c&0x00FFFFFF, sc&0x00FFFFFF, level, hsb, sfc_hsb);
				newbuf.setElem(0, h+v*newwidth, alpha | (rgb&0x00FFFFFF));
			}
		}
	}

	//1画素分のブレンド。src=浮動選択の色、sfc=サーフェースの色(どちらもRGBのみ)。
	static int blendPixel(int mode, int src, int sfc, int level, float[] hsb, float[] sfc_hsb){
		int red = (src>>16)&0xFF, green = (src>>8)&0xFF, blue = src&0xFF;
		int sfc_red = (sfc>>16)&0xFF, sfc_green = (sfc>>8)&0xFF, sfc_blue = sfc&0xFF;
		int r, gr, b;
		switch(mode){
		case blendMode.Add:
			//addモードは黒を起点にした足し算
			r = clamp(sfc_red + red*level/100);
			gr = clamp(sfc_green + green*level/100);
			b = clamp(sfc_blue + blue*level/100);
			return (r<<16)|(gr<<8)|b;
		case blendMode.Subtract:
			//subtractモードは白を起点にした引き算
			r = clamp(sfc_red - (255-red)*level/100);
			gr = clamp(sfc_green - (255-green)*level/100);
			b = clamp(sfc_blue - (255-blue)*level/100);
			return (r<<16)|(gr<<8)|b;
		case blendMode.Multiply:
			//multiplyモードは黒を起点にした掛け算
			r = clamp(sfc_red*(red*level/100)/255);
			gr = clamp(sfc_green*(green*level/100)/255);
			b = clamp(sfc_blue*(blue*level/100)/255);
			return (r<<16)|(gr<<8)|b;
		case blendMode.Screen:
			//screenモードはレベルを起点にした足し算
			r = clamp(sfc_red + red - 255*level/100);
			gr = clamp(sfc_green + green - 255*level/100);
			b = clamp(sfc_blue + blue - 255*level/100);
			return (r<<16)|(gr<<8)|b;
		case blendMode.Darken:
			//darkenモードは暗いほうを残す
			return mix((sfc_red+sfc_green+sfc_blue < red+green+blue) ? sfc : src, sfc, level);
		case blendMode.Lighten:
			//lightenモードは明るいほうを残す
			return mix((sfc_red+sfc_green+sfc_blue > red+green+blue) ? sfc : src, sfc, level);
		case blendMode.Difference:
			//differenceモードは差の絶対値
			r = Math.abs(sfc_red-red);
			gr = Math.abs(sfc_green-green);
			b = Math.abs(sfc_blue-blue);
			return mix((r<<16)|(gr<<8)|b, sfc, level);
		case blendMode.Hue:
		case blendMode.Color:
		case blendMode.Saturation:
		case blendMode.Luminosity:
		{
			Color.RGBtoHSB(red, green, blue, hsb);
			Color.RGBtoHSB(sfc_red, sfc_green, sfc_blue, sfc_hsb);
			if(mode==blendMode.Hue){
				sfc_hsb[0] = hsb[0]; //色相のみ代入
			}
			else if(mode==blendMode.Color){
				sfc_hsb[0] = hsb[0]; //色相代入
				sfc_hsb[1] = hsb[1]; //彩度代入
			}
			else if(mode==blendMode.Saturation){
				sfc_hsb[1] = hsb[1]; //彩度代入
			}
			else{
				sfc_hsb[2] = hsb[2]; //明度代入
			}
			int rgb = Color.HSBtoRGB(sfc_hsb[0], sfc_hsb[1], sfc_hsb[2]);
			return mix(rgb&0x00FFFFFF, sfc, level);
		}
		default:
			return src;
		}
	}

	private static int clamp(int v){
		return v<0 ? 0 : (v>255 ? 255 : v);
	}

	//ブレンド結果resultとサーフェースの色sfcをBlend Levelで混ぜる
	//(level=100で結果そのまま、0でサーフェースのまま=変化なし)
	private static int mix(int result, int sfc, int level){
		if(level>=100) return result&0x00FFFFFF;
		int r = (((result>>16)&0xFF)*level + ((sfc>>16)&0xFF)*(100-level))/100;
		int g = (((result>>8)&0xFF)*level + ((sfc>>8)&0xFF)*(100-level))/100;
		int b = ((result&0xFF)*level + (sfc&0xFF)*(100-level))/100;
		return (r<<16)|(g<<8)|b;
	}


	private static void copyImage(BufferedImage newImage, BufferedImage flowImage, int level)
	{
		//copyモードは色を変えるだけ
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int srcwidth = flowImage.getWidth();
		int newwidth = newImage.getWidth();
		int width = Math.min(srcwidth, newwidth);
		int height = Math.min(flowImage.getHeight(), newImage.getHeight());
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(0, h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF)*level/100+255*(100-level)/100;
					int green = ((c>>8)&0xFF)*level/100+255*(100-level)/100;
					int blue = ((c)&0xFF)*level/100+255*(100-level)/100;
					c = alpha + (red<<16) + (green<<8) + blue;
					newbuf.setElem(0, h+v*newwidth, c);
				}
			}
		}
	}


	private static void blendImage(BufferedImage newImage, BufferedImage flowImage, int level)
	{
		//blendモードはアルファ値を変えるだけ
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int srcwidth = flowImage.getWidth();
		int newwidth = newImage.getWidth();
		int width = Math.min(srcwidth, newwidth);
		int height = Math.min(flowImage.getHeight(), newImage.getHeight());
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(0, h+v*srcwidth);
				int alpha = 0xFF&(c>>24);
				if(alpha!=0){
					alpha = alpha*level/100;
					c = (alpha<<24) + (0x00FFFFFF&c);
					newbuf.setElem(0, h+v*newwidth, c);
				}
			}
		}
	}


	private static void alphaImage(BufferedImage flowImage, BufferedImage surfaceImage, Point offset, int level)
	{
		//alpha channelモードは濃度をサーフェースのアルファチャンネルに適用
		//サーフェースを変更するので特別扱いが必要
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = flowImage.getWidth();
		int height = flowImage.getHeight();
		for(int v=0; v<height; v++){
			int sy = v+offset.y;
			if(sy<0 || sy>=sfcheight) continue;
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(0, h+v*width);
				int alpha = c&0xFF000000;
				if(alpha==0) continue;
				int sx = h+offset.x;
				if(sx<0 || sx>=sfcwidth) continue;
				int red = ((c>>16)&0xFF);
				int green = ((c>>8)&0xFF);
				int blue = ((c)&0xFF);

				int sc = surfacebuf.getElem(0, sx+sy*sfcwidth);
				if((sc&0xFF000000)==0){
					continue;
				}
				int sfc_alpha = 255-(red+green+blue)*level/100/3;

				c = (sfc_alpha<<24) + (0x00FFFFFF&sc);
				surfacebuf.setElem(0, sx+sy*sfcwidth, c);
			}
		}
	}
}

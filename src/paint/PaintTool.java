import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Paint;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/*
import cello.jtablet.TabletManager;
import cello.jtablet.event.TabletEvent;
import cello.jtablet.event.TabletListener;
import cello.jtablet.installer.JTabletExtension;
*/

//ペイントツール共通部。
//
//画像バッファの役割分担(PCARDFrameのフィールド):
//  getSurface()   編集対象(カードピクチャmainImg、またはバックグラウンド編集中はbgImg)
//  paintHistory   複数段のUndo/Redo(PaintUndo。差分矩形+浮動選択の複製)
//  previewBuf     ストローク中の一時レイヤー(半透明ブラシ、消しゴム、図形のプレビュー)。
//                 previewVisibleがtrueの間だけpaintComponentがpreviewAlphaで重ねて描く
//  floating       浮動選択(FloatingSelection)。矩形選択・投げなわ・スマート選択・
//                 ペーストが持ち上げた絵。移動・複製・確定はここ(PaintTool)で共通に扱う
//
//描画はすべてEDT上で行い、画面への反映はmainPane.repaint()経由(paintComponent)に
//統一している。mainPane.getGraphics()で直接描くことはしない。
public class PaintTool {
	static boolean mouse;
	static float lastx[] = new float[2];
	static float lasty[] = new float[2];
	static long lastTime;
	static boolean canTablet = false;
	static PCARDFrame owner;

	//2倍拡大表示モードは、IconEditor等(PCARD.pc以外のPCARDFrame)は対象外なので、
	//ownerがメインカードウインドウ(PCARD.pc)の場合のみアプリの拡大率を返す。
	//拡大率は整数とは限らない(設定の「最大125%拡大」など)。
	static float appScale(){
		return (owner == PCARD.pc) ? (float)PCARD.scale() : 1;
	}

	//FatBits(ペイントツール自体のさらなる拡大表示、owner.bit)とアプリの2倍拡大表示モードは
	//独立した機能だが、どちらも画面表示の倍率という点で同じなので、実際に画面へ
	//描画する際の倍率はこの2つを掛け合わせたものになる。
	//bitは整数とは限らない(Zoom In/Outの1.5倍・3倍、Zoom Outの0.5倍)。
	static float effectiveBit(){
		return owner.bit * appScale();
	}

	//拡大縮小表示中か(bit==1が実寸)
	static boolean zoomed(){
		return owner!=null && owner.bit!=1;
	}

	//FatBits表示の左上に来る論理座標。FatBitsでない(bit==1)ときは常に0
	//(bitLeft/bitTopはFatBitsを抜けても前回の値が残っている)。
	static int viewLeft(){
		return viewLeftOf(owner);
	}
	static int viewTop(){
		return viewTopOf(owner);
	}
	//ownerでないウインドウ(自分を描くMyPanel)用
	static int viewLeftOf(PCARDFrame frame){
		return frame.bit!=1 ? (int)Math.floor(frame.bitLeft) : 0;
	}
	static int viewTopOf(PCARDFrame frame){
		return frame.bit!=1 ? (int)Math.floor(frame.bitTop) : 0;
	}

	//FatBitsを抜ける。表示位置も戻しておかないと、bit==1でbitLeft/bitTopを
	//見る箇所(mainPaneDraw以外)がずれた位置を使ってしまう。
	static void leaveFatBits(PCARDFrame frame){
		setZoom(frame, 1, -1, -1);
	}

	//表示倍率の刻み(Zoom In/Zoom Out)。HyperCardのFatBitsは8倍。
	static final float[] ZOOM_STEPS = {0.25f, 0.5f, 0.75f, 1, 1.5f, 2, 3, 4, 6, 8, 12, 16, 24, 32};
	static final float ZOOM_MIN = 0.25f, ZOOM_MAX = 32;
	static final float FATBITS_ZOOM = 8;

	//今の倍率の次(direction>0)/前(direction<0)の刻み
	static float nextZoom(float zoom, int direction){
		if(direction>0){
			for(float z : ZOOM_STEPS) if(z>zoom+0.001f) return z;
			return ZOOM_MAX;
		}
		for(int i=ZOOM_STEPS.length-1; i>=0; i--) if(ZOOM_STEPS[i]<zoom-0.001f) return ZOOM_STEPS[i];
		return ZOOM_MIN;
	}

	//表示倍率を変える(FatBitsメニュー、鉛筆のCmd+クリック、Zoom In/Out、the zoom)。
	//カードウインドウでは、論理座標(centerX,centerY)が見える範囲の中心に来るよう
	//表示位置を決める(負なら今の中心を保つ)。IconEditorはスクロールペインの中で
	//自分のsetZoom(整数倍)を使う。倍率が1以外の間は実寸プレビューを出す。
	static void setZoom(PCARDFrame frame, float zoom, int centerX, int centerY){
		if(frame==null) return;
		zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
		if(frame instanceof IconEditor){
			((IconEditor)frame).setZoom(Math.max(1, Math.round(zoom)));
			return;
		}
		BufferedImage s = frame.getSurface();
		if(s==null){ frame.bit = zoom; return; }
		float oldZoom = frame.bit;
		if(centerX<0 || centerY<0){
			//今見えている範囲の中心を保つ
			if(oldZoom!=1){
				centerX = (int)(frame.bitLeft + s.getWidth()/oldZoom/2);
				centerY = (int)(frame.bitTop + s.getHeight()/oldZoom/2);
			}
			else{
				centerX = s.getWidth()/2;
				centerY = s.getHeight()/2;
			}
		}
		frame.bit = zoom;
		if(zoom==1){
			frame.bitLeft = 0;
			frame.bitTop = 0;
		}
		else{
			frame.bitLeft = (int)(centerX - s.getWidth()/zoom/2);
			frame.bitTop = (int)(centerY - s.getHeight()/zoom/2);
			clampView(frame);
		}
		if(frame.mainPane!=null) frame.mainPane.repaint();
		if(zoom!=1) FatBitsPreview.show(frame);
		else FatBitsPreview.hide(frame);
		if(frame==PCARD.pc && PCARD.pc.getJMenuBar()!=null){
			GMenu.changeSelected("Paint", "FatBits", zoom>1);
		}
	}

	//表示位置(bitLeft/bitTop)を、見える範囲が画像からはみ出さない範囲に収める
	//(縮小表示では画像全体が見えるので0)。
	static void clampView(PCARDFrame frame){
		BufferedImage s = frame.getSurface();
		if(s==null) return;
		float maxLeft = Math.max(0, s.getWidth() - s.getWidth()/frame.bit);
		float maxTop = Math.max(0, s.getHeight() - s.getHeight()/frame.bit);
		frame.bitLeft = Math.max(0, Math.min(frame.bitLeft, maxLeft));
		frame.bitTop = Math.max(0, Math.min(frame.bitTop, maxTop));
	}

	//表示倍率(%)。the zoom用
	static int zoomPercent(PCARDFrame frame){
		return Math.round(frame.bit*100);
	}

	//mainPane上の物理座標(アプリの2倍表示は既に割ってある)を論理座標へ。
	//snapなら最寄りのマス目の境界へ丸める(矩形選択・消しゴム)。
	static int toLogicalX(int px, boolean snap){
		if(owner.bit==1) return px;
		float b = owner.bit;
		return (int)Math.floor((snap ? px+b/2 : px)/b) + viewLeft();
	}
	static int toLogicalY(int py, boolean snap){
		if(owner.bit==1) return py;
		float b = owner.bit;
		return (int)Math.floor((snap ? py+b/2 : py)/b) + viewTop();
	}

	//論理座標(bgImg/getSurface()等の画像バッファ内の座標)で描画するGraphics2Dに、
	//実際のmainPane上の物理座標に変換するための拡大・スクロール変換を設定する。
	//centerOffsetは、1マス分の中心に点を打つ場合など、半マス分ずらしたい時に使う。
	static void applyBitTransform(Graphics2D g){
		applyBitTransform(g, 0);
	}
	static void applyBitTransform(Graphics2D g, float centerOffset){
		float eb = effectiveBit();
		if(eb!=1){
			AffineTransform af = new AffineTransform();
			af.translate(-viewLeft()*eb+centerOffset, -viewTop()*eb+centerOffset);
			af.scale(eb, eb);
			g.transform(af);
		}
	}

	//論理座標をmainPane上の物理座標に変換する。
	static int physX(int x){
		return Math.round((x-viewLeft())*effectiveBit());
	}
	static int physY(int y){
		return Math.round((y-viewTop())*effectiveBit());
	}

	//論理座標の矩形をmainPane上の物理座標に変換する。
	static Rectangle toPhysical(Rectangle r){
		int x0 = physX(r.x), y0 = physY(r.y);
		return new Rectangle(x0, y0, physX(r.x+r.width)-x0, physY(r.y+r.height)-y0);
	}

	//論理座標(left,top,width,height)の範囲をmainPaneの実際の物理座標に変換して
	//repaintする。effectiveBit()が1の場合はviewLeft/viewTopが0になるため、
	//単純にrepaint(left,top,width,height)を呼ぶのと同じ結果になる。
	static void repaintOwnerRect(int left, int top, int width, int height){
		if(owner==null || owner.mainPane==null) return;
		float eb = effectiveBit();
		int x0 = (int)Math.floor((left-viewLeft())*eb), y0 = (int)Math.floor((top-viewTop())*eb);
		int x1 = (int)Math.ceil((left+width-viewLeft())*eb), y1 = (int)Math.ceil((top+height-viewTop())*eb);
		owner.mainPane.repaint(x0, y0, x1-x0, y1-y0);
		FatBitsPreview.repaintFor(owner);
	}
	static void repaintOwnerRect(Rectangle rect){
		if(rect==null) return;
		repaintOwnerRect(rect.x, rect.y, rect.width, rect.height);
	}
	//選択枠(破線)の分だけ周囲を含めてrepaintする。
	static void repaintOwnerRectGrown(Rectangle rect, int grow){
		if(rect==null) return;
		repaintOwnerRect(rect.x-grow, rect.y-grow, rect.width+grow*2, rect.height+grow*2);
	}
	static void repaintOwner(){
		if(owner==null || owner.mainPane==null) return;
		owner.mainPane.repaint();
		FatBitsPreview.repaintFor(owner);
	}

	//ペイントプロパティ
	static int brushSize = 3; //TODO staticでないようにする
	//ブラシの品質(BrushTool参照)。硬さ・不透明度・手ぶれ補正は0〜100%、
	//間隔は直径に対する%(HyperTalk: the brushHardness/brushOpacity/brushSpacing/brushStabilization)
	static int brushHardness = 100;
	static int brushOpacity = 100;
	static int brushSpacing = 10;
	static int brushStabilization = 0;
	static float lineSize = 1;
	static int alpha = 100;
	static boolean antialias = false;
	static boolean editBackground = false;
	//HyperCardのOptionsメニュにある描画モード。IconEditorの
	//FatBits表示グリッド(PCARDFrame.useGrid/gridSize)とは別物。
	static boolean drawingGrid = false;
	static int drawingGridSize = 8;
	//Optionsメニューの「描画グリッドサイズ」に並ぶ大きさ。他は「その他…」で入力
	static final int[] GRID_SIZES = {2, 4, 8, 16, 32, 64};
	static final int GRID_SIZE_MAX = 256;
	static boolean drawCentered = false;
	static boolean drawMultiple = false;
	//Draw Multipleで図形を残す間隔(HyperTalkのmultiSpace、1〜9)。ドラッグが
	//この画素数以上動くたびに1つ残す(1なら以前どおりマウスイベントごと)。
	static int multiSpace = 1;
	static final int MULTI_SPACE_MAX = 9;
	//HyperCardのPower Keys(Optionsメニュー / the powerKeys)
	static boolean powerKeys = false;
	static BufferedImage pat;
	boolean fill;
	static int smartSelectPercent = 5; //5%
	static int fillTolerancePercent = 0; //バケツの色の許容範囲(%)。0はHyperCardどおり同じ色だけ
	static int sprayRadius = 12; //スプレー缶の散布半径
	static int eraserSize = 16; //消しゴムの一辺(HyperCardは16固定。ツールオプションで変えられる)
	static int regPolySides = 4; //正多角形の辺の数(HyperCard の既定値は 4: the polySides)
	static int smudgeStrength = 50; //指先ツールの強さ(%)。100で拾った絵をそのまま置く

	//対称描画(Symmetry)。ペンシル・ブラシ・消しゴム・スプレー・バケツ・図形ツールの
	//1回の操作を、軸/中心について対称な位置にも同時に描く(Procreateの
	//Drawing Assist)。Draw Multipleと組み合わせると模様が簡単に作れる。
	//  SYM_VERTICAL  縦の軸で左右対称
	//  SYM_HORIZONTAL 横の軸で上下対称
	//  SYM_QUAD      両方(4つ)
	//  SYM_RADIAL    中心のまわりにsymmetryAxes回対称(回転)
	//中心はsymmetryCenterX/Y(負ならキャンバスの中心)。座標系はサーフェースの
	//連続座標(画素(x,y)の中心は(x+0.5,y+0.5))で、点は symPoint、図形は
	//AffineTransform.createTransformedShapeで写す。
	static final int SYM_NONE = 0, SYM_VERTICAL = 1, SYM_HORIZONTAL = 2, SYM_QUAD = 3, SYM_RADIAL = 4;
	static final String[] SYMMETRY_NAMES = {"none", "vertical", "horizontal", "quadrant", "radial"};
	static final int SYMMETRY_AXES_MIN = 2, SYMMETRY_AXES_MAX = 16;
	static int symmetry = SYM_NONE;
	static int symmetryAxes = 6;
	static float symmetryCenterX = -1, symmetryCenterY = -1;

	static String symmetryName(){
		return SYMMETRY_NAMES[Math.max(0, Math.min(SYMMETRY_NAMES.length-1, symmetry))];
	}

	//"none"/"vertical"/"horizontal"/"quadrant"(または"quad"/"both")/"radial"(または"radial N")。
	//該当しなければ-1
	static int parseSymmetry(String v){
		v = v==null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
		if(v.equals("none") || v.equals("off") || v.equals("false") || v.equals("0")) return SYM_NONE;
		if(v.equals("vertical") || v.equals("v")) return SYM_VERTICAL;
		if(v.equals("horizontal") || v.equals("h")) return SYM_HORIZONTAL;
		if(v.equals("quadrant") || v.equals("quad") || v.equals("both") || v.equals("4-way")) return SYM_QUAD;
		if(v.startsWith("radial")){
			String n = v.substring(6).trim();
			if(n.length()>0){
				try{ setSymmetryAxes(Integer.parseInt(n)); }catch(NumberFormatException e){ return -1; }
			}
			return SYM_RADIAL;
		}
		return -1;
	}

	//対称の種類を変え、Optionsメニューのチェックを合わせる(メニュー・HyperTalk共通)
	static void setSymmetry(int kind){
		symmetry = Math.max(SYM_NONE, Math.min(SYM_RADIAL, kind));
		if(PCARD.pc==null) return;
		PCARD.runOnEDT(() -> {
			GMenu.changeSelected("Options", "Symmetry Off", symmetry==SYM_NONE);
			GMenu.changeSelected("Options", "Vertical Symmetry", symmetry==SYM_VERTICAL);
			GMenu.changeSelected("Options", "Horizontal Symmetry", symmetry==SYM_HORIZONTAL);
			GMenu.changeSelected("Options", "Quadrant Symmetry", symmetry==SYM_QUAD);
			GMenu.changeSelected("Options", "Radial Symmetry…", symmetry==SYM_RADIAL);
			if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
		});
	}

	static void setSymmetryAxes(int n){
		symmetryAxes = Math.max(SYMMETRY_AXES_MIN, Math.min(SYMMETRY_AXES_MAX, n));
	}

	//"x,y"で中心を設定する。空文字/"canvas"/"center"ならキャンバスの中心に戻す。
	//書式が違えばfalse
	static boolean setSymmetryCenter(String v){
		v = v==null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
		if(v.length()==0 || v.equals("canvas") || v.equals("center") || v.equals("centre") || v.equals("empty")){
			symmetryCenterX = symmetryCenterY = -1;
			return true;
		}
		String[] parts = v.split(",");
		if(parts.length!=2) return false;
		try{
			float x = Float.parseFloat(parts[0].trim()), y = Float.parseFloat(parts[1].trim());
			if(x<0 || y<0) return false;
			symmetryCenterX = x; symmetryCenterY = y;
			return true;
		}catch(NumberFormatException e){
			return false;
		}
	}

	//対称の中心(サーフェースの連続座標)。設定が無ければキャンバスの中心
	static float symmetryCenterX(BufferedImage surface){
		return symmetryCenterX>=0 ? symmetryCenterX : surface.getWidth()/2f;
	}
	static float symmetryCenterY(BufferedImage surface){
		return symmetryCenterY>=0 ? symmetryCenterY : surface.getHeight()/2f;
	}

	//今の対称設定で1回の操作を写す変換の一覧。先頭は常に恒等変換(元の位置)。
	//対称でないときは恒等変換1つだけ。
	static AffineTransform[] symmetryTransforms(){
		BufferedImage s = owner==null ? null : owner.getSurface();
		if(symmetry==SYM_NONE || s==null) return new AffineTransform[]{ new AffineTransform() };
		float cx = symmetryCenterX(s), cy = symmetryCenterY(s);
		AffineTransform mirrorX = new AffineTransform(-1, 0, 0, 1, 2*cx, 0);
		AffineTransform mirrorY = new AffineTransform(1, 0, 0, -1, 0, 2*cy);
		switch(symmetry){
		case SYM_VERTICAL:
			return new AffineTransform[]{ new AffineTransform(), mirrorX };
		case SYM_HORIZONTAL:
			return new AffineTransform[]{ new AffineTransform(), mirrorY };
		case SYM_QUAD:{
			AffineTransform both = new AffineTransform(mirrorX);
			both.concatenate(mirrorY);
			return new AffineTransform[]{ new AffineTransform(), mirrorX, mirrorY, both };
		}
		default:{
			int n = Math.max(SYMMETRY_AXES_MIN, Math.min(SYMMETRY_AXES_MAX, symmetryAxes));
			AffineTransform[] ts = new AffineTransform[n];
			for(int i=0; i<n; i++){
				ts[i] = AffineTransform.getRotateInstance(2*Math.PI*i/n, cx, cy);
			}
			return ts;
		}
		}
	}

	//画素(x,y)(の中心)を変換tで写した画素位置。回転で端数が出るときは四捨五入。
	static float[] symPoint(AffineTransform t, float x, float y){
		if(t.isIdentity()) return new float[]{x, y};
		double[] p = {x+0.5, y+0.5};
		t.transform(p, 0, p, 0, 1);
		return new float[]{(float)(p[0]-0.5), (float)(p[1]-0.5)};
	}
	//図形の座標(整数座標の輪郭線は画素(x,y)そのものに描かれる)を写すための変換:
	//画素の中心で写してから戻す。これで矩形(4,4)-(20,12)の鏡像の輪郭線が、元の輪郭線の
	//画素をちょうど写した画素に乗る(連続座標のまま写すと1画素ずれる)。
	static AffineTransform pixelTransform(AffineTransform t){
		AffineTransform p = AffineTransform.getTranslateInstance(-0.5, -0.5);
		p.concatenate(t);
		p.translate(0.5, 0.5);
		return p;
	}
	static Point symPoint(AffineTransform t, int x, int y){
		float[] p = symPoint(t, (float)x, (float)y);
		return new Point(Math.round(p[0]), Math.round(p[1]));
	}

	//矩形rと、それを対称の各変換で写した矩形をすべて含む矩形(再描画・消去範囲用)
	static Rectangle symmetricBounds(Rectangle r){
		if(symmetry==SYM_NONE || r==null) return r;
		Rectangle u = new Rectangle(r);
		for(AffineTransform t : symmetryTransforms()){
			if(t.isIdentity()) continue;
			u = u.union(t.createTransformedShape(r).getBounds());
		}
		return u;
	}

	//対称の軸/中心のガイド線をmainPaneへ描く(物理座標)。paintComponentから、
	//サーフェースの上・ツールのオーバーレイの前に呼ばれる。
	static void paintSymmetryGuide(Graphics2D g, PCARDFrame frame){
		if(symmetry==SYM_NONE || frame==null || frame.tool==null) return;
		BufferedImage s = frame.getSurface();
		if(s==null) return;
		float eb = frame.bit*((frame==PCARD.pc) ? (float)PCARD.scale() : 1);
		int left = viewLeftOf(frame), top = viewTopOf(frame);
		float cx = (symmetryCenterX(s)-left)*eb, cy = (symmetryCenterY(s)-top)*eb;
		int w = Math.round(s.getWidth()*eb), h = Math.round(s.getHeight()*eb);
		Graphics2D g2 = (Graphics2D) g.create();
		try{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(new Color(0x60, 0xA0, 0xFF, 0xB0));
			g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
			if(symmetry==SYM_VERTICAL || symmetry==SYM_QUAD){
				g2.draw(new Line2D.Float(cx, 0, cx, h));
			}
			if(symmetry==SYM_HORIZONTAL || symmetry==SYM_QUAD){
				g2.draw(new Line2D.Float(0, cy, w, cy));
			}
			if(symmetry==SYM_RADIAL){
				int n = Math.max(SYMMETRY_AXES_MIN, Math.min(SYMMETRY_AXES_MAX, symmetryAxes));
				float len = Math.max(w, h);
				for(int i=0; i<n; i++){
					double a = 2*Math.PI*i/n-Math.PI/2;
					g2.draw(new Line2D.Float(cx, cy, cx+(float)(Math.cos(a)*len), cy+(float)(Math.sin(a)*len)));
				}
				g2.draw(new Ellipse2D.Float(cx-3, cy-3, 6, 6));
			}
		}
		finally{
			g2.dispose();
		}
	}

	//multiSpaceを変え、Optionsメニューのチェックを合わせる(メニュー・HyperTalk共通)
	static void setMultiSpace(int space){
		multiSpace = Math.max(1, space); //the Options menu marks 1-9 only; a script may set more (HyperCard keeps it)
		if(PCARD.pc==null) return;
		PCARD.runOnEDT(() -> {
			for(int i=1; i<=MULTI_SPACE_MAX; i++){
				GMenu.changeSelected("Options", "Multiple Space "+i, i==multiSpace);
			}
		});
	}

	//描画グリッドの大きさを変え、Optionsメニューのチェックを合わせる(メニュー・HyperTalk共通)。
	//1〜GRID_SIZE_MAXに収める。
	static void setDrawingGridSize(int size){
		drawingGridSize = Math.max(1, Math.min(GRID_SIZE_MAX, size));
		if(PCARD.pc==null) return;
		PCARD.runOnEDT(() -> {
			for(int n : GRID_SIZES){
				GMenu.changeSelected("Options", "Drawing Grid Size "+n, n==drawingGridSize);
			}
		});
	}

	//Power Keysを切り替え、Optionsメニューのチェックを合わせる(メニュー・HyperTalk共通)
	static void setPowerKeys(boolean on){
		powerKeys = on;
		if(PCARD.pc==null) return;
		PCARD.runOnEDT(() -> GMenu.changeSelected("Options", "Power Keys", powerKeys));
	}

	static boolean gridTool(){
		if(owner==null || owner.tool==null) return false;
		return owner.tool instanceof LineTool || owner.tool instanceof RectTool ||
			owner.tool instanceof RoundRectTool || owner.tool instanceof OvalTool ||
			owner.tool instanceof RegPolyTool || owner.tool instanceof PolyTool;
	}

	static int snapDrawingCoordinate(int value){
		int size = Math.max(1, drawingGridSize);
		return Math.round(value/(float)size)*size;
	}

	static Point snapDrawingPoint(int x, int y){
		if(!drawingGrid || !gridTool()) return new Point(x,y);
		return new Point(snapDrawingCoordinate(x), snapDrawingCoordinate(y));
	}

	//Tint(AppleWorks): 画素の色を colour へ TINT_PERCENT% 寄せる。色のアルファは
	//強さとして掛ける(半透明の前景色なら薄く)。画素自身のアルファは変えない。
	static final int TINT_PERCENT = 25;
	static int tintPixel(int c, Color color){
		int a = c&0xFF000000;
		if(a==0) return c;
		int t = TINT_PERCENT*color.getAlpha()/255;
		int r = ((c>>16)&0xFF), g = ((c>>8)&0xFF), b = (c&0xFF);
		r += (color.getRed()-r)*t/100;
		g += (color.getGreen()-g)*t/100;
		b += (color.getBlue()-b)*t/100;
		return a | (r<<16) | (g<<8) | b;
	}

	//Shift制約用。startからpointへの角度をstepDegrees刻みに丸める。
	static Point constrainAngle(int startX, int startY, int x, int y, int stepDegrees){
		double dx=x-startX, dy=y-startY;
		double radius=Math.hypot(dx,dy);
		if(radius==0) return new Point(x,y);
		double step=Math.toRadians(stepDegrees);
		double angle=Math.round(Math.atan2(dy,dx)/step)*step;
		return new Point(startX+(int)Math.round(Math.cos(angle)*radius),
			startY+(int)Math.round(Math.sin(angle)*radius));
	}

	//-------------------
	// 画像バッファ補助
	//-------------------

	//画像の範囲内か
	static boolean inSurface(int x, int y){
		BufferedImage s = owner.getSurface();
		return s!=null && x>=0 && y>=0 && x<s.getWidth() && y<s.getHeight();
	}

	//範囲外なら0(完全透明)を返す安全なピクセル取得
	static int getPixel(BufferedImage img, int x, int y){
		if(img==null || x<0 || y<0 || x>=img.getWidth() || y>=img.getHeight()) return 0;
		return img.getRGB(x, y);
	}

	//TYPE_INT_ARGBで通常に生成した画像なら、ピクセル配列へ直接アクセスする
	//(DataBuffer.getElem()より大幅に速い)。それ以外はnull。
	static int[] directPixels(BufferedImage img){
		if(img==null) return null;
		if(img.getType()!=BufferedImage.TYPE_INT_ARGB && img.getType()!=BufferedImage.TYPE_INT_RGB) return null;
		DataBuffer db = img.getRaster().getDataBuffer();
		if(!(db instanceof DataBufferInt)) return null;
		if(db.getNumBanks()!=1 || db.getOffset()!=0) return null;
		if(img.getRaster().getSampleModelTranslateX()!=0 || img.getRaster().getSampleModelTranslateY()!=0) return null;
		int[] data = ((DataBufferInt)db).getData();
		if(data.length < img.getWidth()*img.getHeight()) return null;
		return data;
	}

	//画像全体を透明にする
	static void clearImage(BufferedImage img){
		if(img==null) return;
		clearImage(img, new Rectangle(0, 0, img.getWidth(), img.getHeight()));
	}
	static void clearImage(BufferedImage img, Rectangle rect){
		if(img==null || rect==null) return;
		Graphics2D g = img.createGraphics();
		g.setComposite(AlphaComposite.Clear);
		g.fill(rect);
		g.dispose();
	}

	static BufferedImage newArgb(int width, int height){
		return new BufferedImage(Math.max(1,width), Math.max(1,height), BufferedImage.TYPE_INT_ARGB);
	}

	//ストロークプレビュー用の一時レイヤー。サーフェースと同じ大きさを保証する。
	static BufferedImage previewBuf(){
		BufferedImage s = owner.getSurface();
		if(owner.previewBuf==null ||
			owner.previewBuf.getWidth()!=s.getWidth() || owner.previewBuf.getHeight()!=s.getHeight()){
			owner.previewBuf = newArgb(s.getWidth(), s.getHeight());
		}
		return owner.previewBuf;
	}
	//プレビューレイヤーを空にして表示を開始する。alphaは画面へ重ねるときの不透明度。
	static BufferedImage beginPreview(float alpha){
		BufferedImage buf = previewBuf();
		clearImage(buf);
		owner.previewAlpha = alpha;
		owner.previewVisible = true;
		return buf;
	}
	static void endPreview(){
		owner.previewVisible = false;
	}
	//プレビューレイヤーをalphaでサーフェースへ合成し、プレビューを終了する。
	static void commitPreview(float alpha){
		if(owner.previewBuf!=null){
			Graphics2D g = owner.getSurface().createGraphics();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			g.drawImage(owner.previewBuf, 0, 0, null);
			g.dispose();
		}
		endPreview();
	}

	//-------------------
	// 浮動選択(FloatingSelection)の共通処理
	//-------------------

	//ドラッグ中の浮動選択の移動状態
	static boolean floatDragging;
	private static boolean floatShift;
	private static int floatShiftX, floatShiftY;
	//直前に確定した浮動選択とそのウインドウ(スマート選択の範囲追加がマスクを引き継ぐ)
	static FloatingSelection lastCommitted;
	static PCARDFrame lastCommittedOwner;

	static FloatingSelection floating(){
		return owner==null ? null : owner.floating;
	}

	//持ち上げた絵を浮動選択にする(Undoスナップショットは呼び出し側で取っておく)
	static void liftFloating(FloatingSelection f){
		if(f==null) return;
		owner.floating = f;
		MyPanel.invalidateBlendCache();
		repaintOwner();
	}

	//サーフェースの矩形を持ち上げて浮動選択にする
	static FloatingSelection liftRect(Rectangle rect){
		if(rect==null || rect.width<=0 || rect.height<=0) return null;
		commitFloating();
		PaintUndo.snapshotForLift(owner);
		FloatingSelection f = FloatingSelection.lift(owner.getSurface(), rect, owner.opaqueSurface());
		liftFloating(f);
		return f;
	}

	//サーフェースと同じ大きさのマスクで持ち上げて浮動選択にする
	static FloatingSelection liftMask(BufferedImage surfaceMask){
		if(surfaceMask==null) return null;
		commitFloating();
		PaintUndo.snapshotForLift(owner);
		FloatingSelection f = FloatingSelection.lift(owner.getSurface(), surfaceMask, owner.opaqueSurface());
		liftFloating(f);
		return f;
	}

	//-------------------
	// 選択範囲の追加/削除(Shift/Option)と、形の操作(Feather/Grow/Shrink)
	//-------------------
	//浮動選択があるときに、その外でShift+ドラッグすると新しい範囲を今の選択に足し、
	//Option+ドラッグすると今の選択から除く(矩形選択・投げなわ・スマート選択で共通)。
	//mouseDownで決まり(selectCombine)、持ち上げ(selectRect/selectMask)で使う。
	//持ち上げてある絵はいったんサーフェースへ戻してから、合成した形で持ち上げ直す。
	//動かしていなければ履歴には何も残らない(持ち上げ直しが次の操作と1段になる)。
	//動かしてあれば今の位置で確定してから、その位置の形と合成する。
	static final int SELECT_REPLACE = 0, SELECT_ADD = 1, SELECT_SUBTRACT = 2;
	static int selectCombine = SELECT_REPLACE;

	//持ち上げてある絵をサーフェースへ戻す(動かしていなければ履歴なしで元どおり、
	//動かしてあれば今の位置で確定)。
	static void putBackFloating(){
		FloatingSelection f = floating();
		if(f==null) return;
		if(f.pristine && owner.blendMode==MyPanel.blendMode.Copy && owner.blendLevel==100){
			f.putBack(owner.getSurface());
			owner.floating = null;
			floatDragging = false;
			transformMode = HANDLE_NONE;
			transformSource = null;
			MyPanel.invalidateBlendCache();
		}
		else{
			commitFloating();
		}
	}

	//今の浮動選択の形(サーフェースと同じ大きさのマスク、今の位置)。無ければnull
	static BufferedImage currentSelectionMask(){
		FloatingSelection f = floating();
		if(f==null) return null;
		BufferedImage s = owner.getSurface();
		return f.surfaceMask(s.getWidth(), s.getHeight());
	}

	//矩形を選択する(selectCombineに応じて置き換え/追加/削除)
	static FloatingSelection selectRect(Rectangle rect){
		if(selectCombine==SELECT_REPLACE || floating()==null){
			selectCombine = SELECT_REPLACE;
			return liftRect(rect);
		}
		BufferedImage s = owner.getSurface();
		BufferedImage m = newArgb(s.getWidth(), s.getHeight());
		Graphics2D g = m.createGraphics();
		g.setColor(Color.BLACK);
		g.fill(rect);
		g.dispose();
		return selectMask(m);
	}

	//マスク(サーフェースと同じ大きさ、アルファ!=0が範囲)で選択する
	//(selectCombineに応じて置き換え/追加/削除)
	static FloatingSelection selectMask(BufferedImage mask){
		int mode = selectCombine;
		selectCombine = SELECT_REPLACE;
		if(mask==null) return null;
		if(mode==SELECT_REPLACE || floating()==null) return liftMask(mask);
		BufferedImage cur = currentSelectionMask();
		putBackFloating();
		BufferedImage combined = SelectionMask.combine(cur, mask, mode==SELECT_ADD);
		return liftMask(combined);
	}

	//今の選択範囲の形を作り替えて持ち上げ直す(Feather/Grow/Shrink)。
	//opは今の形(サーフェースと同じ大きさのマスク)から新しい形を作る。
	static FloatingSelection reshapeSelection(java.util.function.UnaryOperator<BufferedImage> op){
		BufferedImage cur = currentSelectionMask();
		if(cur==null) return null;
		BufferedImage next = op.apply(cur);
		putBackFloating();
		FloatingSelection f = liftMask(next);
		repaintOwner();
		return f;
	}

	/**
	 * The Paint menu's Select with a selection up (doMenu "Select" after a rectangle selection, らくがきカレンダー's stamps):
	 * the selection shrinks to the picture inside it, white that reaches its edge left out, as the lasso does.
	 */
	static FloatingSelection tightenSelection(){
		BufferedImage cur = currentSelectionMask();
		if(cur==null) return null;
		putBackFloating();
		BufferedImage s = owner.getSurface();
		int w = s.getWidth(), h = s.getHeight();
		BufferedImage picture = newArgb(w, h);
		BufferedImage mask = newArgb(w, h);
		for(int y=0; y<h; y++) for(int x=0; x<w; x++){
			boolean in = (cur.getRGB(x, y)>>>24)!=0;
			int p = s.getRGB(x, y);
			picture.setRGB(x, y, in && (p>>>24)!=0 ? (p|0xFF000000) : 0xFFFFFFFF);
			mask.setRGB(x, y, in ? 0xFF000000 : 0);
		}
		LassoTool.clearWhiteFromEdges(picture, mask);
		FloatingSelection f = liftMask(mask);
		repaintOwner();
		return f;
	}

	//選択範囲の縁をradius画素ぼかす(feather selection by N)
	static FloatingSelection featherSelection(float radius){
		return reshapeSelection(m -> SelectionMask.feather(m, radius));
	}
	//選択範囲をradius画素広げる(grow selection by N)
	static FloatingSelection growSelection(float radius){
		return reshapeSelection(m -> SelectionMask.grow(m, radius));
	}
	//選択範囲をradius画素狭める(shrink selection by N)
	static FloatingSelection shrinkSelection(float radius){
		return reshapeSelection(m -> SelectionMask.shrink(m, radius));
	}

	//浮動選択をサーフェースへ描き込んで終える。無ければ何もしない。
	static void commitFloating(){
		FloatingSelection f = floating();
		if(f==null) return;
		PaintUndo.snapshot(owner);
		f.stamp(owner);
		lastCommitted = f;
		lastCommittedOwner = owner;
		owner.floating = null;
		floatDragging = false;
		transformMode = HANDLE_NONE;
		transformSource = null;
		resetBlend();
		MyPanel.invalidateBlendCache();
		toCdPict();
		repaintOwner();
	}

	//浮動選択を捨てる(Deleteキー、Clear Selection、Cut)。持ち上げた跡は空いたまま。
	static void discardFloating(){
		FloatingSelection f = floating();
		if(f==null) return;
		PaintUndo.snapshot(owner);
		owner.floating = null;
		floatDragging = false;
		transformMode = HANDLE_NONE;
		transformSource = null;
		resetBlend();
		MyPanel.invalidateBlendCache();
		toCdPict();
		repaintOwner();
	}

	private static void resetBlend(){
		owner.blendMode = 0;
		owner.blendLevel = 100;
		if(PaintBlendDialog.dialog!=null){
			PaintBlendDialog.dialog.dispose();
		}
	}

	//Undo/Redoで浮動選択を戻す。表示と操作には選択ツールが要るので、他のツールなら
	//矩形選択ツールへ切り替える(ペーストと同じ)。
	static void restoreFloating(PCARDFrame frame, FloatingSelection f){
		frame.floating = f;
		floatDragging = false;
		transformMode = HANDLE_NONE;
		transformSource = null;
		if(f!=null && !(frame.tool instanceof toolSelectInterface)){
			if(frame.tool!=null) frame.tool.clear();
			frame.tool = new SelectTool();
			selectSelectToolButton(frame);
		}
		MyPanel.invalidateBlendCache();
	}

	//ツールバーの選択状態を矩形選択ツールに合わせる
	static void selectSelectToolButton(PCARDFrame frame){
		if(frame == PCARD.pc && PCARD.pc.toolbar!=null &&
				PCARD.pc.toolbar.selectToolButton!=null){
			PCARD.pc.toolbar.selectToolButton.setSelected(true);
		}
	}

	//浮動選択の上でマウスが押された: 移動(Optionなら複製を置いて移動)を始める
	private static void beginFloatDrag(int x, int y){
		FloatingSelection f = floating();
		floatDragging = true;
		floatShift = (GUI.key[11]>0);
		floatShiftX = x; floatShiftY = y;
		PaintUndo.snapshot(owner);
		if(GUI.key[12]>0){ //option: 複製を置いていく
			f.stamp(owner);
			f.pristine = false;
			MyPanel.invalidateBlendCache();
			repaintOwnerRect(floatingPaintBounds(f));
		}
	}

	private static void floatDragTo(int x, int y){
		FloatingSelection f = floating();
		if(f==null) return;
		if(floatShift && ((x-floatShiftX!=0) || (y-floatShiftY!=0))){
			if((floatShiftX==-1) || (floatShiftY!=-1) && (Math.abs(x-floatShiftX) > Math.abs(y-floatShiftY))){
				floatShiftX = -1;
				y = floatShiftY;
				lasty[0] = (int)floatShiftY;
			}else if ((floatShiftY==-1) || (floatShiftX!=-1)){
				floatShiftY = -1;
				x = floatShiftX;
				lastx[0] = (int)floatShiftX;
			}
		}
		Rectangle old = floatingPaintBounds(f);
		f.moveBy(x-(int)lastx[0], y-(int)lasty[0]);
		if(owner.selectaf!=null){
			//拡大縮小プレビュー中は表示位置が変換されるので全体を描き直す
			repaintOwner();
		}
		else{
			//変形ハンドルと回転のつまみは選択範囲の外にはみ出して描かれるので、
			//それも含めて移動前と移動後を描き直す(選択枠の分だけでは跡が残る)
			repaintOwnerRect(old.union(floatingPaintBounds(f)));
		}
	}

	//浮動選択を描いたときに画面が変わる範囲(論理座標): 選択範囲+選択枠+
	//変形ハンドル(画面上でHANDLE_PX)+回転のつまみ
	static Rectangle floatingPaintBounds(FloatingSelection f){
		Rectangle b = f.bounds();
		float eb = effectiveBit();
		int grow = 1+(int)Math.ceil((HANDLE_PX/2+2)/(double)eb);
		Rectangle r = new Rectangle(b.x-grow, b.y-grow, b.width+grow*2, b.height+grow*2);
		Point knob = rotateKnob(f);
		r.add(new Rectangle(knob.x-grow, knob.y-grow, grow*2, grow*2));
		return r;
	}

	//浮動選択の選択枠を描く(MyPanel.paintComponentから)
	static void paintFloatingOverlay(Graphics2D g){
		FloatingSelection f = floating();
		if(f!=null){
			f.paintAnts(g);
			paintTransformHandles(g);
		}
	}

	//-------------------
	// 浮動選択のハンドル(ドラッグで拡大縮小・回転)
	//-------------------
	//浮動選択の外接矩形の四隅と各辺の中央にハンドルを出す。ハンドルをドラッグすると
	//拡大縮小(shift: 縦横比を保つ、Option: 中心を動かさない)、上辺の中央から
	//伸びた柄の先のつまみをドラッグすると中心まわりに回転(shift: 15°刻み)。
	//つまみは選択範囲がキャンバスの上端に近ければ下辺側に出す。選択範囲の外の
	//それ以外の場所をクリックしたときは、これまでどおり確定になる(四隅の外側を
	//回転にすると、隅の近くをクリックして確定するHyperCardの操作と衝突する)。
	//以前は Rotate がメニュー(角度の入力)、
	//Scale がダイアログ(大きさの入力)だったが、どちらも選択範囲のその場の操作に
	//なり、HyperCard の「選択して動かす」延長で変形できる。マスク付きの選択
	//(投げなわ・スマート選択)も外接矩形のハンドルで同じように変形できる。
	//変形は毎回ドラッグ開始時の複製(transformSource)から掛け直すので、途中で
	//画質が劣化しない。1回のドラッグが Undo の1段になる。
	static final int HANDLE_PX = 7;        //ハンドルの一辺(画面ピクセル)
	static final int ROTATE_STALK_PX = 18; //回転のつまみまでの柄の長さ(画面ピクセル)
	//ハンドル番号: 1=左上 2=上 3=右上 4=左 5=右 6=左下 7=下 8=右下、9=回転、0=なし
	static final int HANDLE_NONE = 0, HANDLE_ROTATE = 9;
	static int transformMode;
	private static FloatingSelection transformSource;
	private static int transformStartX, transformStartY;
	//表示用: 回転中の角度(度)と、拡大縮小中の目標矩形
	private static int transformDegrees;
	private static Rectangle transformRect;
	private static Cursor rotateCursor;
	private static boolean handleCursorShown; //ハンドル用のカーソルを出している

	//論理座標(x,y)がどのハンドルの上か。ハンドルの当たり判定は画面ピクセルで
	//HANDLE_PX ぶん(FatBits では論理1マス以上)。
	static int hitTransformHandle(FloatingSelection f, int x, int y){
		if(f==null) return HANDLE_NONE;
		float eb = Math.max(1, effectiveBit());
		int tol = Math.max(1, (int)Math.ceil(HANDLE_PX/2.0/eb));
		Rectangle b = f.bounds();
		//小さな選択範囲では内側のほとんどがハンドルになって動かせなくなるので、
		//内側に食い込む分は範囲の1/4まで(外側はそのまま)
		int tolIn = Math.min(tol, Math.min(b.width, b.height)/4);
		int t = b.contains(x, y) ? tolIn : tol;
		int[][] handles = handlePoints(b);
		for(int i=0; i<handles.length; i++){
			if(Math.abs(x-handles[i][0])<=t && Math.abs(y-handles[i][1])<=t) return i+1;
		}
		//回転のつまみ
		Point knob = rotateKnob(f);
		if(Math.abs(x-knob.x)<=tol && Math.abs(y-knob.y)<=tol) return HANDLE_ROTATE;
		return HANDLE_NONE;
	}

	//回転のつまみの論理座標。上辺の中央の上(キャンバスの上端に近ければ下辺の下)。
	static Point rotateKnob(FloatingSelection f){
		Rectangle b = f.bounds();
		float eb = Math.max(1, effectiveBit());
		int stalk = Math.max(2, (int)Math.ceil(ROTATE_STALK_PX/(double)eb));
		int cx = b.x+b.width/2;
		int top = viewTop();
		if(b.y-stalk>=top) return new Point(cx, b.y-stalk);
		return new Point(cx, b.y+b.height+stalk);
	}

	//ハンドル1..8の論理座標(左上、上、右上、左、右、左下、下、右下)
	private static int[][] handlePoints(Rectangle b){
		int cx = b.x+b.width/2, cy = b.y+b.height/2;
		int r = b.x+b.width, bt = b.y+b.height;
		return new int[][]{{b.x,b.y},{cx,b.y},{r,b.y},{b.x,cy},{r,cy},{b.x,bt},{cx,bt},{r,bt}};
	}

	//ハンドルに応じたカーソル(mouseWithin から)
	static Cursor transformCursor(int handle){
		switch(handle){
			case 1: return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
			case 2: return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
			case 3: return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
			case 4: return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
			case 5: return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
			case 6: return Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
			case 7: return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
			case 8: return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
			case HANDLE_ROTATE: return rotateCursor();
			default: return Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
		}
	}

	//回転用のカーソル(円弧の矢印)。一度作って使い回す。
	private static Cursor rotateCursor(){
		if(rotateCursor!=null) return rotateCursor;
		try{
			BufferedImage bi = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = bi.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			java.awt.geom.Arc2D.Double arc = new java.awt.geom.Arc2D.Double(4, 4, 16, 16, 30, 270, java.awt.geom.Arc2D.OPEN);
			Polygon head = new Polygon(new int[]{19,19,25}, new int[]{4,13,8}, 3);
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(arc);
			g.draw(head);
			g.setColor(Color.BLACK);
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(arc);
			g.fill(head);
			g.dispose();
			rotateCursor = java.awt.Toolkit.getDefaultToolkit().createCustomCursor(bi, new Point(12, 12), "rotate-cursor");
		}catch(Exception e){
			rotateCursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
		}
		return rotateCursor;
	}

	//ハンドルの上でマウスが押された: 変形を始める
	private static void beginTransform(int handle, int x, int y){
		FloatingSelection f = floating();
		if(f==null) return;
		transformMode = handle;
		transformSource = f.copy();
		transformStartX = x;
		transformStartY = y;
		transformDegrees = 0;
		transformRect = f.bounds();
		PaintUndo.snapshot(owner);
	}

	//ドラッグ中/ドラッグ終了: 開始時の複製から変形をやり直す
	private static void transformTo(int x, int y){
		FloatingSelection f = floating();
		FloatingSelection src = transformSource;
		if(f==null || src==null) return;
		Rectangle old = f.bounds();
		f.restoreFrom(src);
		Rectangle b = src.bounds();
		boolean smooth = antialias;
		if(transformMode==HANDLE_ROTATE){
			double cx = b.x+b.width/2.0, cy = b.y+b.height/2.0;
			double a = Math.atan2(y-cy, x-cx)-Math.atan2(transformStartY-cy, transformStartX-cx);
			int deg = (int)Math.round(Math.toDegrees(a));
			if(GUI.key[11]>0) deg = (int)Math.round(deg/15.0)*15;
			deg = ((deg%360)+360)%360;
			transformDegrees = deg;
			if(deg!=0){
				f.transform(AffineTransform.getRotateInstance(Math.toRadians(deg), b.width/2.0, b.height/2.0), smooth);
			}
			transformRect = f.bounds();
		}
		else{
			Rectangle r = scaledRect(b, transformMode, x, y, GUI.key[11]>0, GUI.key[12]>0);
			transformRect = r;
			if(r.width!=b.width || r.height!=b.height){
				f.transform(AffineTransform.getScaleInstance(r.width/(double)b.width, r.height/(double)b.height), smooth);
			}
			f.moveBy(r.x-f.x, r.y-f.y);
		}
		repaintOwnerRectGrown(old.union(f.bounds()), HANDLE_PX+1);
		repaintOwner();
	}

	//ハンドルhandleを(x,y)まで動かしたときの矩形。反対側の辺(角)は動かない。
	//keepAspect(shift)は縦横比を保ち、fromCenter(Option)は中心を動かさない。
	//大きさは1以上(裏返しはしない)。
	static Rectangle scaledRect(Rectangle b, int handle, int x, int y, boolean keepAspect, boolean fromCenter){
		int left = b.x, top = b.y, right = b.x+b.width, bottom = b.y+b.height;
		boolean movesLeft = handle==1 || handle==4 || handle==6;
		boolean movesRight = handle==3 || handle==5 || handle==8;
		boolean movesTop = handle==1 || handle==2 || handle==3;
		boolean movesBottom = handle==6 || handle==7 || handle==8;
		if(fromCenter){
			int cx = b.x+b.width/2, cy = b.y+b.height/2;
			int hw = b.width-b.width/2, hh = b.height-b.height/2;
			if(movesLeft) hw = Math.max(1, cx-x);
			if(movesRight) hw = Math.max(1, x-cx);
			if(movesTop) hh = Math.max(1, cy-y);
			if(movesBottom) hh = Math.max(1, y-cy);
			if(keepAspect){
				double sx = hw/(double)Math.max(1, b.width/2.0), sy = hh/(double)Math.max(1, b.height/2.0);
				double s = (movesLeft||movesRight) && !(movesTop||movesBottom) ? sx :
					(movesTop||movesBottom) && !(movesLeft||movesRight) ? sy : Math.max(sx, sy);
				hw = Math.max(1, (int)Math.round(b.width/2.0*s));
				hh = Math.max(1, (int)Math.round(b.height/2.0*s));
			}
			return new Rectangle(cx-hw, cy-hh, hw*2, hh*2);
		}
		if(movesLeft) left = Math.min(x, right-1);
		if(movesRight) right = Math.max(x, left+1);
		if(movesTop) top = Math.min(y, bottom-1);
		if(movesBottom) bottom = Math.max(y, top+1);
		int w = right-left, h = bottom-top;
		if(keepAspect && b.width>0 && b.height>0){
			double sx = w/(double)b.width, sy = h/(double)b.height;
			double s = (movesLeft||movesRight) && !(movesTop||movesBottom) ? sx :
				(movesTop||movesBottom) && !(movesLeft||movesRight) ? sy : Math.max(sx, sy);
			w = Math.max(1, (int)Math.round(b.width*s));
			h = Math.max(1, (int)Math.round(b.height*s));
			//動かしていない辺(角)を固定したまま大きさを合わせる
			if(movesLeft) left = right-w; else right = left+w;
			if(movesTop) top = bottom-h; else bottom = top+h;
			//辺のハンドルで縦横比を保つときは、もう一方の向きは中心を保つ
			if(!(movesLeft||movesRight)){ int cx = b.x+b.width/2; left = cx-w/2; right = left+w; }
			if(!(movesTop||movesBottom)){ int cy = b.y+b.height/2; top = cy-h/2; bottom = top+h; }
		}
		return new Rectangle(left, top, right-left, bottom-top);
	}

	private static void endTransform(){
		transformMode = HANDLE_NONE;
		transformSource = null;
		transformRect = null;
		handleCursorShown = false;
		if(owner!=null && owner.mainPane!=null) TBCursor.changeCursor(owner);
		repaintOwner();
	}

	//ハンドルと、変形中の角度/大きさの表示(gはmainPaneの物理座標系)
	static void paintTransformHandles(Graphics2D g){
		FloatingSelection f = floating();
		if(f==null) return;
		Rectangle b = f.bounds();
		Graphics2D g2 = (Graphics2D) g.create();
		for(int[] p : handlePoints(b)){
			int px = physX(p[0]), py = physY(p[1]);
			g2.setColor(Color.WHITE);
			g2.fillRect(px-HANDLE_PX/2, py-HANDLE_PX/2, HANDLE_PX, HANDLE_PX);
			g2.setColor(Color.BLACK);
			g2.drawRect(px-HANDLE_PX/2, py-HANDLE_PX/2, HANDLE_PX-1, HANDLE_PX-1);
		}
		//回転のつまみ: 辺の中央から柄を伸ばし、先に丸いつまみ
		Point knob = rotateKnob(f);
		int kx = physX(knob.x), ky = physY(knob.y);
		int ex = physX(b.x+b.width/2);
		int ey = physY(knob.y<b.y ? b.y : b.y+b.height);
		g2.setColor(Color.WHITE);
		g2.setStroke(new BasicStroke(3f));
		g2.drawLine(ex, ey, kx, ky);
		g2.setColor(Color.BLACK);
		g2.setStroke(new BasicStroke(1f));
		g2.drawLine(ex, ey, kx, ky);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(Color.WHITE);
		g2.fillOval(kx-HANDLE_PX/2, ky-HANDLE_PX/2, HANDLE_PX, HANDLE_PX);
		g2.setColor(Color.BLACK);
		g2.drawOval(kx-HANDLE_PX/2, ky-HANDLE_PX/2, HANDLE_PX-1, HANDLE_PX-1);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		if(transformMode!=HANDLE_NONE && transformRect!=null){
			String label = transformMode==HANDLE_ROTATE ? transformDegrees+"\u00b0" : transformRect.width+" \u00d7 "+transformRect.height;
			g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			FontMetrics fm = g2.getFontMetrics();
			int lw = fm.stringWidth(label)+8, lh = fm.getHeight()+2;
			int lx = physX(b.x+b.width)+HANDLE_PX, ly = physY(b.y+b.height)+HANDLE_PX;
			g2.setColor(new Color(0,0,0,170));
			g2.fillRoundRect(lx, ly, lw, lh, 6, 6);
			g2.setColor(Color.WHITE);
			g2.drawString(label, lx+4, ly+fm.getAscent()+1);
		}
		g2.dispose();
	}

	//-------------------
	// Power Keys
	//-------------------
	//HyperCardのPower Keys(Optionsメニュー / the powerKeys)。ペイントツールを
	//使っているとき、修飾キーなしの1文字でPaintメニュー等のコマンドを実行する。
	//  A すべてを選択  B 黒パターン  C 中心から描く  D 暗く  F 塗りつぶし  G グリッド
	//  H 左右反転  I 反転  L 明るく  M 連続描画  O 不透明  P ピックアップ  T 透明
	//  V 上下反転  W 白パターン  1〜8 線の太さ  [ 左回転  ] 右回転  E 輪郭(Trace Edges)  R 復帰(Revert)
	//(S Select はこのアプリに対応するコマンドが無い)
	//Paint Textの入力中や、他のテキスト部品にフォーカスがあるときは効かない。
	static final int PATTERN_WHITE = 0, PATTERN_BLACK = 11; //HyperCardのパターン1と12

	//Power Keyに対応するメニューコマンド。特別扱いは "pattern:n"(0始まり)と
	//"lineSize:n"。対応しない文字はnull。
	static String powerKeyCommand(char c){
		switch(Character.toUpperCase(c)){
			case 'A': return "Select All";
			case 'B': return "pattern:"+PATTERN_BLACK;
			case 'C': return "Draw Centered";
			case 'D': return "Darken";
			case 'E': return "Trace Edges";
			case 'F': return "Fill";
			case 'G': return "Grid Snap";
			case 'H': return "Flip Horizontal";
			case 'I': return "Invert";
			case 'L': return "Lighten";
			case 'M': return "Draw Multiple";
			case 'O': return "Opaque";
			case 'P': return "Pickup";
			case 'R': return "Revert";
			case 'T': return "Transparent";
			case 'V': return "Flip Vertical";
			case 'W': return "pattern:"+PATTERN_WHITE;
			case '[': return "Rotate Left";
			case ']': return "Rotate Right";
			default:
				if(c>='1' && c<='8') return "lineSize:"+(c-'0');
				return null;
		}
	}

	//キー入力がPower Keyなら実行してtrue(GUI.keyPressedから。呼び出し側は
	//keyDownメッセージ等を送らない)。
	static boolean handlePowerKey(java.awt.event.KeyEvent e){
		if(!powerKeys || owner==null || owner.tool==null || AuthTool.tool!=null) return false;
		if(e.isMetaDown() || e.isControlDown() || e.isAltDown()) return false;
		char c = e.getKeyChar();
		if(c==java.awt.event.KeyEvent.CHAR_UNDEFINED) return false;
		if(owner.tool instanceof TypeTool && ((TypeTool)owner.tool).area!=null) return false;
		java.awt.Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if(focus instanceof javax.swing.text.JTextComponent) return false;
		return runPowerKey(c);
	}

	//文字cのPower Keyを実行する(テスト用に分離)
	static boolean runPowerKey(char c){
		String cmd = powerKeyCommand(c);
		if(cmd==null) return false;
		if(cmd.startsWith("pattern:")){
			if(owner.pat!=null) owner.pat.select(Integer.parseInt(cmd.substring(8)));
		}
		else if(cmd.startsWith("lineSize:")){
			lineSize = Integer.parseInt(cmd.substring(9));
			GToolOptions.changeCursor();
		}
		else{
			GMenuPaint.doMenu(cmd);
		}
		return true;
	}

	//-------------------
	// idle処理
	//-------------------
	public static void tabletinit(){
		 /*if (JTabletExtension.checkCompatibility(PaintTool.owner, "1.2.0")) {
			 canTablet = true;
			 if(tabletListener==null){
				 tabletListener = new MyTabletListener();
			 }
	     }*/

		 if(PCARD.pc!=null && (PCARD.pc.paidle==null || !PCARD.pc.paidle.isAlive())){
			 PCARD.pc.paidle = new PaintIdle();
			 PCARD.pc.paidle.start();
		 }
	}

	//FatBits中、マウス位置を最寄りのマス目の境界へ丸めるツールか。矩形選択と、
	//マス目2つ以上の幅がある消しゴム(境界に揃えると消える範囲がマス目単位になる)。
	//16倍以上では消しゴムはマス目1つぶんなので、丸めるとポインタの下ではなく
	//隣のマス目が消えてしまう。その場合は他のツールと同じくポインタのあるマス目を使う。
	static boolean snapsToGrid(){
		if(owner.tool==null) return false;
		if(owner.tool.getClass()==SelectTool.class) return true;
		return owner.tool.getClass()==EraserTool.class && EraserTool.brushSize(owner.bit)>=2;
	}

	//-------------------
	// マウスイベント(すべてEDTから呼ばれる)
	//-------------------
	public static void mouseUp(int x, int y){
		if(owner==null) return;
		if(owner.bit != 1){
			//端数切り捨て
			PaintTool.owner.bitLeft = (float)(int)PaintTool.owner.bitLeft;
			PaintTool.owner.bitTop = (float)(int)PaintTool.owner.bitTop;

			if(owner instanceof IconEditor){
				//スクロールバーを使う
				((IconEditor)owner).scrollpane.getHorizontalScrollBar().setValue(((IconEditor)owner).scrollpane.getHorizontalScrollBar().getValue()+(int)(PaintTool.owner.bitLeft*PaintTool.owner.bit));
				((IconEditor)owner).scrollpane.getVerticalScrollBar().setValue(((IconEditor)owner).scrollpane.getVerticalScrollBar().getValue()+(int)(PaintTool.owner.bitTop*PaintTool.owner.bit));
				PaintTool.owner.bitLeft = 0;
				PaintTool.owner.bitTop = 0;
			}

			boolean snap = snapsToGrid();
			x = toLogicalX(x, snap);
			y = toLogicalY(y, snap);
		}
		if(GUI.key[' ']>0) { //space
			return;
		}
		Point snapped = snapDrawingPoint(x,y);
		x=snapped.x; y=snapped.y;
		if(transformMode!=HANDLE_NONE){
			transformTo(x, y);
			endTransform();
			lastx[1] = lastx[0];
			lasty[1] = lasty[0];
			lastx[0] = x;
			lasty[0] = y;
			mouse = false;
			return;
		}
		if(floatDragging){
			floatDragTo(x, y);
			floatDragging = false;
			lastx[1] = lastx[0];
			lasty[1] = lasty[0];
			lastx[0] = x;
			lasty[0] = y;
			mouse = false;
			return;
		}
		if(owner.tool!=null){
			owner.tool.mouseUp(x,y);
			lastx[1] = lastx[0];
			lasty[1] = lasty[0];
			lastx[0] = x;
			lasty[0] = y;
			mouse = false;
		}
	}
	public static void mouseDown(int x, int y){
		if(owner==null) return;
		if(GUI.key[' ']>0) { //space
			lastx[0] = x;
			lasty[0] = y;
			return;
		}
		if(owner.bit != 1){
			boolean snap = snapsToGrid();
			x = toLogicalX(x, snap);
			y = toLogicalY(y, snap);
		}
		Point snapped = snapDrawingPoint(x,y);
		x=snapped.x; y=snapped.y;
		lastx[1] = lastx[0];
		lasty[1] = lasty[0];
		lastx[0] = x;
		lasty[0] = y;
		mouse = true;
		FloatingSelection f = floating();
		if(f!=null){
			//スマート選択のCmd+クリックは範囲の追加なので、浮動選択の上でも移動しない
			boolean extend = GUI.key[14]>0 && owner.tool instanceof SmartSelectTool;
			int handle = extend ? HANDLE_NONE : hitTransformHandle(f, x, y);
			if(handle!=HANDLE_NONE){
				//ハンドルの上: 拡大縮小/回転
				beginTransform(handle, x, y);
				return;
			}
			if(f.contains(x, y) && !extend){
				//選択範囲の上: 移動(Option+ドラッグなら複製)
				beginFloatDrag(x, y);
				return;
			}
			if(owner.tool instanceof toolSelectInterface && !extend && (GUI.key[11]>0 || GUI.key[12]>0)){
				//選択範囲の外でShift/Option: 今の選択に足す/から除く(持ち上げで合成する)
				selectCombine = GUI.key[11]>0 ? SELECT_ADD : SELECT_SUBTRACT;
			}
			else{
				//選択範囲の外をクリック: 確定してから新しい選択を始める
				selectCombine = SELECT_REPLACE;
				commitFloating();
			}
		}
		else{
			selectCombine = SELECT_REPLACE;
		}
		if(owner.tool!=null){
			owner.tool.mouseDown(x,y);
		}
	}
	public static void mouseWithin(int x, int y){
		if(owner==null) return;
		if(GUI.key[' ']>0) { //space
			if(owner.mainPane!=null){
				owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
			return;
		}
		if(owner.bit != 1){
			x = toLogicalX(x, false);
			y = toLogicalY(y, false);
		}
		Point snapped = snapDrawingPoint(x,y);
		x=snapped.x; y=snapped.y;
		FloatingSelection f = floating();
		if(f!=null){
			//BACKSPACE or DEL(押されている間: 2=押しっぱなし、3=押した直後): 浮動選択を捨てる。
			//1は「離したがkeyEventCheckがまだ0に戻していない」状態。keyEventCheckはスタックの
			//カードがあるときしか回らないので、ランチャーから開いた画像エディタでは一度でも
			//DeleteやBackspaceを押すと1のまま残り、以前(>0で判定)は次に選択した範囲に
			//ポインタが乗っただけで浮動選択が捨てられ、選択した部分が透明になっていた
			if(GUI.key[20]>=2 || GUI.key[21]>=2){
				discardFloating();
				return;
			}
			//選択枠のアニメーション
			repaintOwnerRectGrown(f.bounds(), 1);
			int handle = hitTransformHandle(f, x, y);
			if(handle!=HANDLE_NONE){
				owner.mainPane.setCursor(transformCursor(handle));
				handleCursorShown = true;
				return;
			}
			if(handleCursorShown){
				//ハンドルから外れた: ツール本来のカーソルへ戻す(投げなわ等はmouseWithinで
				//カーソルを設定しないので、ここで戻さないと矢印のまま残る)
				handleCursorShown = false;
				TBCursor.changeCursor(owner);
			}
			if(f.contains(x, y)){
				owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				return;
			}
		}
		if(owner.tool!=null&&owner.tool.mouseWithin(x,y)){
			lastx[1] = lastx[0];
			lasty[1] = lasty[0];
			lastx[0] = x;
			lasty[0] = y;
		}
	}
	public static void mouseStillDown(int x, int y){
		if(owner==null) return;
		if(GUI.key[' ']>0 && owner.bit!=1 && owner.tool!=null) {
			//スペースキー+ドラッグで拡大表示位置をスクロール
			BufferedImage s = owner.getSurface();
			PaintTool.owner.bitLeft += (PaintTool.lastx[0] - x)/PaintTool.owner.bit;
			PaintTool.owner.bitTop += (PaintTool.lasty[0] - y)/PaintTool.owner.bit;
			lastx[0] = x;
			lasty[0] = y;

			if(owner instanceof IconEditor){
				//スクロールバーを使う
				JScrollPane sp = ((IconEditor)owner).scrollpane;
				float b = owner.bit;
				float scrollX = sp.getHorizontalScrollBar().getValue()/b;
				float scrollY = sp.getVerticalScrollBar().getValue()/b;
				if(PaintTool.owner.bitLeft+scrollX<0) PaintTool.owner.bitLeft = -scrollX;
				if(PaintTool.owner.bitTop+scrollY<0) PaintTool.owner.bitTop = -scrollY;
				if(PaintTool.owner.bitLeft+s.getWidth()/b > s.getWidth()-scrollX){
					PaintTool.owner.bitLeft = s.getWidth()*(b-1)/b-scrollX;
				}
				if(PaintTool.owner.bitTop+s.getHeight()/b > s.getHeight()-scrollY){
					PaintTool.owner.bitTop = s.getHeight()*(b-1)/b-scrollY;
				}
			}
			else{
				clampView(owner);
			}

			repaintOwner();
			return;
		}
		if(owner.bit != 1){
			boolean snap = snapsToGrid();
			x = toLogicalX(x, snap);
			y = toLogicalY(y, snap);
		}
		Point snapped = snapDrawingPoint(x,y);
		x=snapped.x; y=snapped.y;
		if(transformMode!=HANDLE_NONE){
			transformTo(x, y);
			lastx[1] = lastx[0];
			lasty[1] = lasty[0];
			lastx[0] = x;
			lasty[0] = y;
			return;
		}
		if(floatDragging){
			floatDragTo(x, y);
			lastx[1] = lastx[0];
			lasty[1] = lasty[0];
			lastx[0] = x;
			lasty[0] = y;
			return;
		}
		if(owner.tool!=null&&owner.tool.mouseStillDown(x,y)){
			lastx[1] = lastx[0];
			lasty[1] = lasty[0];
			lastx[0] = x;
			lasty[0] = y;
		}
	}

	//パターン無し(ベタ塗り)か
	static boolean noPattern(){
		return PaintTool.owner.pat==null || PaintTool.owner.pat.pattern==11;
	}

	static public void setPattern(boolean invert){
		BufferedImage patorg = (PaintTool.owner.pat==null) ? null : PaintTool.owner.pat.patterns[PaintTool.owner.pat.pattern];
		Color foreColor = PaintTool.owner.fore==null ? Color.BLACK : PaintTool.owner.fore.color;
		Color backColor = PaintTool.owner.back==null ? Color.WHITE : PaintTool.owner.back.color;
		if(invert){
			Color t = foreColor; foreColor = backColor; backColor = t;
		}
		if(patorg==null){
			//ない場合
			PaintTool.pat = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g1 = PaintTool.pat.createGraphics();
			g1.setColor(foreColor);
			g1.fillRect(0,0,8,8);
			g1.dispose();
			return;
		}
		PaintTool.pat = colorizePattern(patorg, foreColor, backColor);
	}

	//カラーパターン: パターン画像の各ドットは次の3種類のどれか。
	//  ・灰色(R=G=B)のドット … 「インク」。黒が前景色、白が背景色、中間は両者の
	//    混色になる(「黒=前景色、白=背景色」という拡張形式)。
	//    前景色/背景色のアルファもそのまま効くので「青×透明の網点」が作れる。
	//  ・色の付いたドット … 固定色。前景色/背景色に関係なくその色のまま。
	//  ・透明なドット … 常に透明(アルファは掛け算で残す)。
	//パターン編集器(PatternEditor)とパレットの表示も同じ規則で色を付ける。
	static BufferedImage colorizePattern(BufferedImage patorg, Color foreColor, Color backColor){
		if(foreColor==null) foreColor = Color.BLACK;
		if(backColor==null) backColor = Color.WHITE;
		int width = patorg.getWidth(), height = patorg.getHeight();
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int fr = foreColor.getRed(), fg = foreColor.getGreen(), fb = foreColor.getBlue(), fa = foreColor.getAlpha();
		int br = backColor.getRed(), bg = backColor.getGreen(), bb = backColor.getBlue(), ba = backColor.getAlpha();
		for(int h=0; h<height; h++){
			for(int w=0; w<width; w++){
				int c = patorg.getRGB(w, h);
				int sa = (c>>>24)&0xFF;
				int r = (c>>16)&0xFF;
				int g = (c>>8)&0xFF;
				int b = c&0xFF;
				int result;
				if(sa==0){
					result = 0;
				}else if(r==g && g==b){
					int a = (r*ba+(0xFF-r)*fa)/0xFF;
					a = a*sa/0xFF;
					result = (a<<24) | (((r*br+(0xFF-r)*fr)/0xFF)<<16) | (((r*bg+(0xFF-r)*fg)/0xFF)<<8) | ((r*bb+(0xFF-r)*fb)/0xFF);
				}else{
					result = c;
				}
				out.setRGB(w, h, result);
			}
		}
		return out;
	}

	//画像を浮動選択として貼り付ける。ピクチャへ直接描き込まずに浮かせておくことで、
	//確定するまでユーザーが位置を自由に動かせる。「ペースト」メニューと、
	//ペイントツール中に画像ファイルをドロップしたときの両方から使う。
	//
	//FloatingSelection.ofが画像をARGBへ作り直す。ドロップされた画像は型がまちまち
	//(JPEGならTYPE_3BYTE_BGRなど)で、そのまま使うと後段の合成処理が期待通りに動かない。
	static public void pasteAsSelection(java.awt.Image img){
		pasteAsSelection(img, Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	/** As above, at (x, y) in picture coordinates; Integer.MIN_VALUE = the top left of the view. */
	static public void pasteAsSelection(java.awt.Image img, int x, int y){
		if(img==null || owner==null) return;
		int width = img.getWidth(owner);
		int height = img.getHeight(owner);
		if(width<=0 || height<=0) return;

		if(owner.tool!=null) owner.tool.end();
		commitFloating();
		PaintUndo.snapshot(owner);

		int px, py;
		if(owner instanceof IconEditor){
			px = (int)(((IconEditor)owner).scrollpane.getHorizontalScrollBar().getValue()/owner.bit);
			py = (int)(((IconEditor)owner).scrollpane.getVerticalScrollBar().getValue()/owner.bit);
		}
		else {
			px = viewLeft();
			py = viewTop();
		}
		if(x!=Integer.MIN_VALUE){ px = x; py = y; }
		FloatingSelection f = FloatingSelection.of(img, px, py);
		if(f==null) return;

		owner.tool = new SelectTool();
		liftFloating(f);

		//ツールバーの選択状態も選択ツールに合わせる。ドロップの場合は
		//ユーザーがツールバーを操作していないため、これが無いと表示中の
		//ツール(鉛筆など)と実際のツールが食い違ってしまう。
		selectSelectToolButton(owner);
	}

	//画像が完全に透明かどうか(全画素のアルファが0)
	static boolean isAllTransparent(BufferedImage img){
		int[] px = directPixels(img);
		int n = img.getWidth()*img.getHeight();
		if(px!=null){
			for(int i=0; i<n; i++){
				if((px[i]&0xFF000000)!=0) return false;
			}
			return true;
		}
		DataBuffer db = img.getRaster().getDataBuffer();
		for(int i=0; i<n; i++){
			if((db.getElem(0, i)&0xFF000000)!=0) return false;
		}
		return true;
	}

	//画像のRGBが全部白かどうか(アルファは見ない)
	static boolean isAllWhite(BufferedImage img){
		int[] px = directPixels(img);
		int n = img.getWidth()*img.getHeight();
		if(px!=null){
			for(int i=0; i<n; i++){
				if((px[i]&0x00FFFFFF)!=0x00FFFFFF) return false;
			}
			return true;
		}
		DataBuffer db = img.getRaster().getDataBuffer();
		for(int i=0; i<n; i++){
			if((db.getElem(0, i)&0x00FFFFFF)!=0x00FFFFFF) return false;
		}
		return true;
	}

	//srcの内容をカード/バックグラウンドのpictへコピーする(必要ならpictを作り直す)
	private static BufferedImage copyToPict(BufferedImage pict, BufferedImage src, int width, int height){
		if(pict==null || pict.getWidth()!=width || pict.getHeight()!=height){
			pict = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		}
		Graphics2D g = pict.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return pict;
	}

	//ペイントバッファの内容をカード/バックグラウンドのpictへ反映する(メモリ上のみ)。
	//追加レイヤーはその場で編集しているので反映は要らないが、平らにした絵の
	//キャッシュを捨て、アルファロックなら操作前の不透明度に戻す。
	static public void toCdPict(){
		if(PaintTool.owner!=PCARDFrame.pc) return;
		if(PCARD.pc.stack==null || PCARD.pc.stack.curCard==null || PaintTool.owner.mainImg==null) return;
		OStack stack = PaintTool.owner.stack;
		OCard card = stack.curCard;

		//アルファロック(編集中の追加レイヤー): 保留中の操作の前の絵の不透明度へ戻す。
		//描くツールだけが対象。選択ツール(持ち上げ・移動・確定)はロックに関わらず
		//そのまま(持ち上げた跡を不透明に戻してしまわないように)
		PaintLayer active = PaintTool.owner.activePaintLayer();
		if(active!=null && active.alphaLock && PaintTool.owner.floating==null &&
				!(PaintTool.owner.tool instanceof toolSelectInterface)){
			PaintLayers.enforceAlphaLock(active.image, PaintTool.owner.paintHistory.pendingBaseline(active.image));
		}
		card.invalidateComposite();
		if(card.bg!=null) card.bg.invalidateComposite();

		//Cardピクチャ
		if(isAllTransparent(PaintTool.owner.mainImg)){
			card.pict = null;
			card.bitmapName = null;
		}
		else{
			card.pict = copyToPict(card.pict, PaintTool.owner.mainImg, stack.width, stack.height);
		}

		//Bgピクチャ
		if(PaintTool.owner.bgImg==null || card.bg==null) return;
		if(isAllWhite(PaintTool.owner.bgImg)){
			card.bg.pict = null;
			card.bg.bitmapName = null;
		}
		else{
			card.bg.pict = copyToPict(card.bg.pict, PaintTool.owner.bgImg, stack.width, stack.height);
		}
	}

	//カード移動・スタックを閉じる前に、カードのペイントツールを終了する(浮動選択や
	//入力中の文字をカードピクチャへ確定する)。直前にアイコンエディタを使っていると
	//ownerがそちらを指したままなので、確定先がアイコンにならないようカードへ戻す。
	static public void endCardTool(){
		if(PCARD.pc==null || PCARD.pc.tool==null) return;
		PaintTool.owner = PCARD.pc;
		PCARD.pc.tool.end();
	}

	//カード/バックグラウンドのpictをファイルへ保存する。PNGのエンコードは重いので、
	//ペイントツール同士の切り替えでは呼ばず、ペイントモードを抜けるときやカード移動、
	//書き出しのときだけ呼ぶ。
	static public void saveCdPictures(){
		//カードピクチャにコピー(toCdPictはownerがカードのときだけ反映する)
		if(PCARD.pc!=null && PCARD.pc.tool!=null) PaintTool.owner = PCARD.pc;
		PaintTool.toCdPict();

		if(PCARDFrame.pc==null || PCARDFrame.pc.stack==null || PCARDFrame.pc.stack.curCard==null) return;
		if(PCARDFrame.pc.stack.file==null) return;

		//ファイル保存 (card)
		if(PCARDFrame.pc.stack.curCard.pict!=null){
			if(PCARDFrame.pc.stack.curCard.bitmapName==null ||
					PCARDFrame.pc.stack.curCard.bitmapName.length()==0 ||
					!PCARDFrame.pc.stack.curCard.bitmapName.matches(".*\\.png"))
			{
				PCARDFrame.pc.stack.curCard.bitmapName = "BMAP_"+PCARDFrame.pc.stack.curCard.id+".png";
				PCARDFrame.pc.stack.curCard.changed = true;
			}
			File file = new File(PCARDFrame.pc.stack.file.getParent()+File.separatorChar+PCARDFrame.pc.stack.curCard.bitmapName);
			try {
				StackProtection.writeImage(PCARDFrame.pc.stack, PCARDFrame.pc.stack.curCard.pict, file);
			} catch (IOException e) {
				e.printStackTrace();
			}
			PictureCache.invalidate(file);
		}

		//追加レイヤー
		PaintLayers.save(PCARDFrame.pc.stack.curCard, PCARDFrame.pc.stack.file);
		PaintLayers.save(PCARDFrame.pc.stack.curCard.bg, PCARDFrame.pc.stack.file);

		//ファイル保存 (bg)
		if(PCARDFrame.pc.stack.curCard.bg!=null &&
			PCARDFrame.pc.stack.curCard.bg.pict!=null)
		{
			if(PCARDFrame.pc.stack.curCard.bg.bitmapName==null ||
					PCARDFrame.pc.stack.curCard.bg.bitmapName.length()==0 ||
					!PCARDFrame.pc.stack.curCard.bg.bitmapName.matches(".*\\.png"))
			{
				PCARDFrame.pc.stack.curCard.bg.bitmapName = "BMAP_"+PCARDFrame.pc.stack.curCard.bg.id+".png";
				PCARDFrame.pc.stack.curCard.bg.changed = true;
			}
			File file2 = new File(PCARDFrame.pc.stack.file.getParent()+File.separatorChar+PCARDFrame.pc.stack.curCard.bg.bitmapName);
			try {
				StackProtection.writeImage(PCARDFrame.pc.stack, PCARDFrame.pc.stack.curCard.bg.pict, file2);
			} catch (IOException e) {
				e.printStackTrace();
			}
			PictureCache.invalidate(file2);
		}
	}

	//スポイト。透明度も含めて色を取得する。
	public static void spoit(int x, int y){
		if(!inSurface(x, y)) return;
		int c = PaintTool.owner.getSurface().getRGB(x, y);
		Color col = new Color((c>>16)&0xFF, (c>>8)&0xFF, (c)&0xFF, (c>>24)&0xFF);
		if(paintGUI.right){
			PaintTool.owner.back.color = col;
			PaintTool.owner.back.makeIcon(col);
		}else{
			PaintTool.owner.fore.color = col;
			PaintTool.owner.fore.makeIcon(col);
		}
	}
}


//各ウインドウのペイント履歴(複数段のUndo/Redo)。
//
//操作の直前にsnapshot()を呼ぶと、そのときのサーフェース(全体のコピー=baseline)と
//浮動選択の複製を「保留」として持つ。次のsnapshot()かUndo/Redoのときに、保留の
//baselineと現在の状態を比べて、変わった画素を囲む矩形の前後だけ(差分矩形)を
//履歴に積む。全画面のコピーを段数ぶん持たないので、MAX_STEPS段まで軽く戻れる。
//
//1つのエントリ = 1回の操作 = 「サーフェースの差分」+「浮動選択の前後の複製」。
//浮動選択の移動・反転・回転などサーフェースが変わらない操作も、浮動選択の複製の
//違いとして1段になる。編集したレイヤー(カード/バックグラウンド)も記録するので、
//背景の編集をカードへ戻ってからUndoしても正しいレイヤーへ戻る。
//
//持ち上げただけの浮動選択(FloatingSelection.pristine)は絵が変わっていないので、
//次の操作と1つのエントリにまとめる(HyperCardと同じく「選んで動かす」が1段)。
//
//浮動選択を確定(または破棄)すると、持ち上げてから確定するまでの一連のエントリを
//1つに畳む(HyperCardの「選んで・動かして・回して・置く」を1回のUndoで戻す)。
//浮いている間は各操作を1段ずつ戻せ、置いたあとは全体が1段になる。
class PaintUndo {
	static final int MAX_STEPS = 100;
	static final long MAX_BYTES = 128L * 1024 * 1024;

	static class Entry {
		final boolean background;
		//どのレイヤーか(0=本来のピクチャ、n=追加レイヤーのn番目)
		final int layer;
		//サーフェースの差分。rect==nullなら変化なし。大きさが変わった操作は
		//before/afterに全体(beforeSize/afterSize)を持つ。
		Rectangle rect;
		int[] before, after;
		java.awt.Dimension beforeSize, afterSize;
		//浮動選択の前後(nullなら無し)
		FloatingSelection floatBefore, floatAfter;

		Entry(boolean background, int layer){ this.background = background; this.layer = layer; }

		boolean changesSurface(){ return rect!=null; }
		long bytes(){
			long b = 0;
			if(before!=null) b += 4L*before.length;
			if(after!=null) b += 4L*after.length;
			if(floatBefore!=null) b += floatBefore.bytes();
			if(floatAfter!=null) b += floatAfter.bytes();
			return b;
		}
	}

	final java.util.ArrayDeque<Entry> past = new java.util.ArrayDeque<Entry>();
	final java.util.ArrayDeque<Entry> future = new java.util.ArrayDeque<Entry>();

	//保留中の操作前の状態
	private BufferedImage baseline;        //操作前のサーフェース全体のコピー
	private boolean baselineBackground;    //どのレイヤーか
	private int baselineLayer;
	private FloatingSelection baselineFloat; //操作前の浮動選択の複製(無ければnull)
	private boolean baselineLift;          //持ち上げ直後のbaseline(次の操作と1段にまとめる)

	static BufferedImage copyInto(BufferedImage dst, BufferedImage src){
		if(src==null) return dst;
		if(dst==null || dst.getWidth()!=src.getWidth() || dst.getHeight()!=src.getHeight()){
			dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		}
		//RGBやインデックス画像も色を保ってARGBへ変換する。
		dst.setRGB(0, 0, src.getWidth(), src.getHeight(),
			src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth()), 0, src.getWidth());
		return dst;
	}

	static void reset(PCARDFrame owner){
		PaintUndo h = owner.paintHistory;
		h.past.clear();
		h.future.clear();
		h.baseline = null;
		h.baselineFloat = null;
		refresh(owner);
	}

	static void refresh(PCARDFrame owner){
		if(owner==null) return;
		PaintUndo h = owner.paintHistory;
		GMenuPaint.setUndoMenu(owner, !h.past.isEmpty() || h.baseline!=null, !h.future.isEmpty());
	}

	//履歴に積んであるエントリの数(保留中の操作は含まない)
	int size(){ return past.size(); }

	//保留中の操作があるか
	boolean pending(){ return baseline!=null; }

	//保留中の操作の前のサーフェースのコピー(surfaceがその操作のレイヤーでなければnull)
	BufferedImage pendingBaseline(BufferedImage surface){
		if(baseline==null || PaintTool.owner==null) return null;
		if(layerImage(PaintTool.owner, baselineBackground, baselineLayer)!=surface) return null;
		return baseline;
	}

	private void trim(){
		long bytes = 0;
		for(Entry e : past) bytes += e.bytes();
		//巨大画像でも少なくとも直前の1操作は保持する。
		while(past.size()>1 && (past.size()>MAX_STEPS || bytes>MAX_BYTES))
			bytes -= past.removeFirst().bytes();
	}

	private static BufferedImage layerImage(PCARDFrame owner, boolean background, int layer){
		if(layer>0){
			OCardBase g = owner.paintGroup(background);
			if(g==null || layer>g.layers.size()) return null;
			return g.layers.get(layer-1).image;
		}
		return background ? owner.bgImg : owner.mainImg;
	}

	//履歴を積む・戻す対象のレイヤー番号(今編集中のもの)
	private static int activeLayerOf(PCARDFrame owner, boolean background){
		if(owner.paintGroup(background)==null) return 0;
		return background ? owner.bgLayer : owner.cardLayer;
	}

	//操作の直前に呼ぶ。保留中の操作があれば履歴へ積み、現在の状態を新しい保留にする。
	static void snapshot(PCARDFrame owner){
		if(owner==null || owner.getSurface()==null) return;
		PaintUndo h = owner.paintHistory;
		FloatingSelection f = owner.floating;
		//持ち上げ直後で何もしていない浮動選択なら、持ち上げ前のbaselineをそのまま使う
		if(h.baseline!=null && h.baselineLift && f!=null && f.pristine){
			h.baselineLift = false;
			f.pristine = false;
			MyPanel.invalidateBlendCache();
			return;
		}
		h.closePending(owner);
		h.baseline = copyInto(null, owner.getSurface());
		h.baselineBackground = owner.isEditingBackground();
		h.baselineLayer = activeLayerOf(owner, h.baselineBackground);
		h.baselineFloat = f==null ? null : f.copy();
		h.baselineLift = false;
		refresh(owner);
		MyPanel.invalidateBlendCache();
	}

	//保留中の操作を履歴へ積む(操作の区切りを明示したいとき)
	static void flush(PCARDFrame owner){
		if(owner==null) return;
		owner.paintHistory.closePending(owner);
		refresh(owner);
	}

	//持ち上げ(選択→浮動選択)の直前に呼ぶ。snapshot()と同じだが、持ち上げただけでは
	//絵が変わらないので、次の操作と1段にまとめる印を付ける。
	static void snapshotForLift(PCARDFrame owner){
		snapshot(owner);
		if(owner!=null) owner.paintHistory.baselineLift = true;
	}

	//保留中の操作を履歴へ積む(何も変わっていなければ捨てる)
	private void closePending(PCARDFrame owner){
		if(baseline==null) return;
		BufferedImage current = layerImage(owner, baselineBackground, baselineLayer);
		Entry e = new Entry(baselineBackground, baselineLayer);
		if(current!=null) diffInto(e, baseline, current);
		FloatingSelection f = owner.floating;
		boolean floatChanged = (f==null) != (baselineFloat==null) || (f!=null && !f.sameAs(baselineFloat));
		if(floatChanged){
			e.floatBefore = baselineFloat;
			e.floatAfter = f==null ? null : f.copy();
		}
		baseline = null;
		baselineFloat = null;
		baselineLift = false;
		if(!e.changesSurface() && !floatChanged) return;
		if(e.floatBefore!=null && e.floatAfter==null){
			//確定・破棄: 持ち上げからここまでの一連のエントリを1つに畳む
			java.util.ArrayList<Entry> session = new java.util.ArrayList<Entry>();
			while(!past.isEmpty()){
				Entry last = past.peekLast();
				if(last.floatAfter==null || last.background!=e.background || last.layer!=e.layer) break;
				session.add(0, past.removeLast());
				if(last.floatBefore==null) break; //持ち上げ(ペースト)のエントリまで
			}
			session.add(e);
			e = collapse(session, current);
			if(e==null) return;
		}
		past.addLast(e);
		future.clear();
		trim();
	}

	//一連のエントリを、最初の前から最後の後までの1つのサーフェース差分にまとめる。
	//currentは最後のエントリ適用後のサーフェース。絵が変わっていなければnull。
	private static Entry collapse(java.util.List<Entry> entries, BufferedImage current){
		Entry m = new Entry(entries.get(0).background, entries.get(0).layer);
		boolean resized = false;
		Rectangle union = null;
		for(Entry e : entries){
			if(!e.changesSurface()) continue;
			if(e.beforeSize!=null) resized = true;
			else union = union==null ? new Rectangle(e.rect) : union.union(e.rect);
		}
		if(!resized && union==null) return null;
		if(current==null) return null;
		//現在のサーフェースから逆順に戻して、最初のエントリの前の状態を作る
		BufferedImage scratch = copyInto(null, current);
		for(int i=entries.size()-1; i>=0; i--){
			scratch = applyPixels(scratch, entries.get(i), false);
		}
		if(resized || scratch.getWidth()!=current.getWidth() || scratch.getHeight()!=current.getHeight()){
			m.rect = new Rectangle(0, 0, 0, 0);
			m.beforeSize = new java.awt.Dimension(scratch.getWidth(), scratch.getHeight());
			m.afterSize = new java.awt.Dimension(current.getWidth(), current.getHeight());
			m.before = scratch.getRGB(0, 0, scratch.getWidth(), scratch.getHeight(), null, 0, scratch.getWidth());
			m.after = current.getRGB(0, 0, current.getWidth(), current.getHeight(), null, 0, current.getWidth());
			return m;
		}
		union = union.intersection(new Rectangle(0, 0, current.getWidth(), current.getHeight()));
		if(union.isEmpty()) return null;
		m.rect = union;
		m.before = scratch.getRGB(union.x, union.y, union.width, union.height, null, 0, union.width);
		m.after = current.getRGB(union.x, union.y, union.width, union.height, null, 0, union.width);
		return m;
	}

	//beforeとafterの違う画素を囲む矩形を求め、その範囲の前後の画素をeへ入れる
	private static void diffInto(Entry e, BufferedImage before, BufferedImage after){
		int w = before.getWidth(), h = before.getHeight();
		if(after.getWidth()!=w || after.getHeight()!=h){
			e.rect = new Rectangle(0, 0, 0, 0);
			e.beforeSize = new java.awt.Dimension(w, h);
			e.afterSize = new java.awt.Dimension(after.getWidth(), after.getHeight());
			e.before = before.getRGB(0, 0, w, h, null, 0, w);
			e.after = after.getRGB(0, 0, after.getWidth(), after.getHeight(), null, 0, after.getWidth());
			return;
		}
		int[] a = PaintTool.directPixels(before);
		int[] b = PaintTool.directPixels(after);
		if(a==null) a = before.getRGB(0, 0, w, h, null, 0, w);
		if(b==null) b = after.getRGB(0, 0, w, h, null, 0, w);
		int left = w, top = h, right = -1, bottom = -1;
		for(int y=0; y<h; y++){
			int row = y*w;
			for(int x=0; x<w; x++){
				if(a[row+x]==b[row+x]) continue;
				if(x<left) left = x;
				if(x>right) right = x;
				if(y<top) top = y;
				if(y>bottom) bottom = y;
			}
		}
		if(right<left) return;
		Rectangle r = new Rectangle(left, top, right-left+1, bottom-top+1);
		e.rect = r;
		e.before = new int[r.width*r.height];
		e.after = new int[r.width*r.height];
		for(int y=0; y<r.height; y++){
			System.arraycopy(a, (r.y+y)*w+r.x, e.before, y*r.width, r.width);
			System.arraycopy(b, (r.y+y)*w+r.x, e.after, y*r.width, r.width);
		}
	}

	//eの片側(undoならbefore、redoならafter)の画素をimgへ書き戻す。大きさが変わる
	//エントリなら新しい画像を返す(それ以外はimg自身)。
	private static BufferedImage applyPixels(BufferedImage img, Entry e, boolean redo){
		if(!e.changesSurface()) return img;
		int[] px = redo ? e.after : e.before;
		if(e.beforeSize!=null){
			java.awt.Dimension d = redo ? e.afterSize : e.beforeSize;
			BufferedImage out = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB);
			out.setRGB(0, 0, d.width, d.height, px, 0, d.width);
			return out;
		}
		if(img==null) return null;
		Rectangle r = e.rect;
		if(r.x+r.width>img.getWidth() || r.y+r.height>img.getHeight()) return img;
		int[] direct = PaintTool.directPixels(img);
		if(direct!=null){
			int w = img.getWidth();
			for(int y=0; y<r.height; y++){
				System.arraycopy(px, y*r.width, direct, (r.y+y)*w+r.x, r.width);
			}
		}
		else{
			img.setRGB(r.x, r.y, r.width, r.height, px, 0, r.width);
		}
		return img;
	}

	//eの片側(undoならbefore、redoならafter)をレイヤーと浮動選択へ書き戻す
	private static void apply(PCARDFrame owner, Entry e, boolean redo){
		//別のレイヤーの操作を戻すときは、そのレイヤーを編集中にする(戻した絵が見える)
		OCardBase group = owner.paintGroup(e.background);
		if(group!=null && e.layer<=group.layers.size()){
			if(e.background) owner.bgLayer = e.layer; else owner.cardLayer = e.layer;
			group.invalidateComposite();
		}
		BufferedImage img = layerImage(owner, e.background, e.layer);
		BufferedImage out = applyPixels(img, e, redo);
		if(out!=img){
			if(e.layer>0 && group!=null) group.layers.get(e.layer-1).image = out;
			else if(e.background) owner.bgImg = out;
			else owner.mainImg = out;
		}
		FloatingSelection f = redo ? e.floatAfter : e.floatBefore;
		if(e.floatBefore!=null || e.floatAfter!=null){
			PaintTool.restoreFloating(owner, f==null ? null : f.copy());
		}
	}

	static boolean undo(PCARDFrame owner){ return step(owner, false); }
	static boolean redo(PCARDFrame owner){ return step(owner, true); }

	private static boolean step(PCARDFrame owner, boolean redo){
		if(owner==null) return false;
		PaintUndo h = owner.paintHistory;
		h.closePending(owner);
		java.util.ArrayDeque<Entry> from = redo ? h.future : h.past;
		java.util.ArrayDeque<Entry> to = redo ? h.past : h.future;
		if(from.isEmpty()){
			refresh(owner);
			return false;
		}
		Entry e = from.peekLast();
		if(layerImage(owner, e.background, e.layer)==null && e.changesSurface() && e.beforeSize==null){
			refresh(owner);
			return false;
		}
		from.removeLast();
		apply(owner, e, redo);
		to.addLast(e);
		refresh(owner);
		MyPanel.invalidateBlendCache();
		return true;
	}
}


//投げなわ・スマート選択のマスク(srcbits)の輪郭。マスクが変わらない限り
//再計算しないので、選択枠のアニメーションのたびに全画素を走査しなくて済む。
class MaskOutline {
	final BufferedImage mask;
	final int[] xs;
	final int[] ys;
	final int count;
	final Rectangle bounds;

	private MaskOutline(BufferedImage mask, int[] xs, int[] ys, int count, Rectangle bounds){
		this.mask = mask;
		this.xs = xs;
		this.ys = ys;
		this.count = count;
		this.bounds = bounds;
	}

	//cachedが同じマスクから作ったものならそれを返す
	static MaskOutline of(BufferedImage mask, MaskOutline cached){
		if(mask==null) return null;
		if(cached!=null && cached.mask==mask) return cached;
		int width = mask.getWidth();
		int height = mask.getHeight();
		int[] px = PaintTool.directPixels(mask);
		if(px==null){
			px = mask.getRGB(0, 0, width, height, null, 0, width);
		}
		int[] xs = new int[Math.max(16, (width+height)*2)];
		int[] ys = new int[xs.length];
		int n = 0;
		int left = width, top = height, right = -1, bottom = -1;
		for(int y=0; y<height; y++){
			int row = y*width;
			for(int x=0; x<width; x++){
				int c = px[row+x];
				if((c & 0xFF000000) == 0) continue;
				if(x<left) left = x;
				if(x>right) right = x;
				if(y<top) top = y;
				if(y>bottom) bottom = y;
				//範囲に含まれていて、周囲4つのどれかが範囲外(画像の端も範囲外扱い)なら輪郭
				boolean edge =
					x==0 || (px[row+x-1]&0xFF000000)==0 ||
					x==width-1 || (px[row+x+1]&0xFF000000)==0 ||
					y==0 || (px[row-width+x]&0xFF000000)==0 ||
					y==height-1 || (px[row+width+x]&0xFF000000)==0;
				if(!edge) continue;
				if(n==xs.length){
					xs = java.util.Arrays.copyOf(xs, n*2);
					ys = java.util.Arrays.copyOf(ys, n*2);
				}
				xs[n] = x;
				ys[n] = y;
				n++;
			}
		}
		Rectangle bounds = (right<left) ? new Rectangle(0,0,0,0) : new Rectangle(left, top, right-left+1, bottom-top+1);
		return new MaskOutline(mask, xs, ys, n, bounds);
	}

	//輪郭を(ox,oy)だけずらして「蟻の行進」で描く。gは論理座標系(applyBitTransform済み)。
	//period個おきに点を打ち、phaseでアニメーションさせる。
	void paint(Graphics2D g, int ox, int oy, int phase, int period, int whiteMod){
		for(int i=0; i<count; i++){
			int x = xs[i];
			int y = ys[i];
			int k = x+y+phase;
			if(k%period!=0) continue;
			g.setColor(((k%(period*2))<whiteMod) ? Color.WHITE : Color.BLACK);
			g.drawLine(x+ox, y+oy, x+ox, y+oy);
		}
	}
}


interface toolInterface{
	public void mouseUp(int x, int y);
	public void mouseDown(int x, int y);
	public boolean mouseWithin(int x, int y);
	public boolean mouseStillDown(int x, int y);
	public void clear();
	public void end();
	public String getName();
	//選択枠などツール固有のオーバーレイをmainPaneへ描く。gはmainPaneの物理座標系。
	//MyPanel.paintComponentから、サーフェース・プレビュー・浮動選択の後に呼ばれる。
	default void paintOverlay(Graphics2D g){}
}

interface toolTabletInterface extends toolInterface{
	public void penUp(float x, float y, float pressure, boolean eraser);
	public void penDown(float x, float y, float pressure, boolean eraser);
	public boolean penStillDown(float x, float y, float pressure, boolean eraser);
}

//選択ツール(矩形・投げなわ・スマート選択)。持ち上げた絵は共通の浮動選択
//(PCARDFrame.floating)になるので、問い合わせはどのツールでも同じ。
interface toolSelectInterface extends toolInterface{
	//浮動選択の絵(無ければnull)
	default BufferedImage getSelectedSurface(PCARDFrame owner){
		return owner.floating==null ? null : owner.floating.image;
	}
	//持ち上げた位置の矩形
	default Rectangle getSelectedRect(){
		FloatingSelection f = PaintTool.floating();
		return f==null ? null : f.originBounds();
	}
	//現在位置の矩形
	default Rectangle getMoveRect(){
		FloatingSelection f = PaintTool.floating();
		return f==null ? null : f.bounds();
	}
	//浮動選択があるか
	default boolean isMove(){
		return PaintTool.floating()!=null;
	}
}


//-------------------
// 鉛筆
//-------------------
class PencilTool implements toolInterface{
	boolean invert;
	boolean shift;
	int shiftx, shifty;

	@Override
	public String getName() {
		return "Pencil";
	}

	@Override
	public void mouseUp(int x, int y) {
		PaintTool.toCdPict();
		PaintTool.repaintOwner();

		invert = false;
	}

	@Override
	public void mouseDown(int x, int y) {
		if(GUI.key[12]>0) {
			PaintTool.spoit((int)x, (int)y);
			return;
		}

		shift = (GUI.key[11]>0);
		shiftx = x; shifty = y;

		if(GUI.key[14]>0) { //cmd: FatBitsの出入り(クリックした点を中心に)
			if(PaintTool.owner instanceof IconEditor){
				((IconEditor)PaintTool.owner).toggleZoom();
				return;
			}
			if(PaintTool.owner.bit>1) PaintTool.leaveFatBits(PaintTool.owner);
			else PaintTool.setZoom(PaintTool.owner, PaintTool.FATBITS_ZOOM, x, y);
			return;
		}

		GMenuPaint.setUndo();

		PaintTool.lastx[0]=x;
		PaintTool.lasty[0]=y;

		//既に前景色の点をクリックしたら背景色で描く(消す)
		invert = false;
		if(PaintTool.inSurface(x, y)){
			int c = PaintTool.owner.getSurface().getRGB(x, y);
			Color col = PaintTool.owner.fore.color;
			int c2 = (col.getAlpha()<<24)+(col.getRed()<<16)+(col.getGreen()<<8)+(col.getBlue());
			if(c == c2 || c-1 == c2){
				invert = true;
			}
		}

		mouseStillDown(x,y);
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		if(GUI.key[12]>0){
			PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else{
			PaintTool.owner.mainPane.setCursor(TBCursor.pencilCursor());
		}
		return true;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(GUI.key[12]>0) { //opt spoit
			return true;
		}
		if(GUI.key[14]>0) { //cmd zoom
			return true;
		}

		if(shift && ((x-shiftx!=0) || (y-shifty!=0))){
			if((shiftx==-1) || (shifty!=-1) && (Math.abs(x-shiftx) > Math.abs(y-shifty))){
				shiftx = -1;
				y = shifty;
				PaintTool.lasty[0] = (int)shifty;
			}else if ((shifty==-1) || (shiftx!=-1)){
				shifty = -1;
				x = shiftx;
				PaintTool.lastx[0] = (int)shiftx;
			}
		}

		//線をサーフェースに書く
		Graphics2D g2 = PaintTool.owner.getSurface().createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		float brushSize = 1;
		g2.setStroke(new BasicStroke(brushSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		if(this.invert){
			g2.setColor(PaintTool.owner.back.color);
		}
		else{
			g2.setColor(PaintTool.owner.fore.color);
		}
		//対称描画: 同じ線分を対称な位置にも引く
		for(AffineTransform t : PaintTool.symmetryTransforms()){
			float[] a = PaintTool.symPoint(t, PaintTool.lastx[0], PaintTool.lasty[0]);
			float[] b = PaintTool.symPoint(t, (float)x, (float)y);
			g2.draw(new Line2D.Double(Math.round(a[0]), Math.round(a[1]), Math.round(b[0]), Math.round(b[1])));
		}
		g2.dispose();

		//変更した範囲だけ再描画
		PaintTool.repaintOwnerRect(PaintTool.symmetricBounds(strokeBounds(PaintTool.lastx[0], PaintTool.lasty[0], x, y, brushSize, 1)));

		return true;
	}

	//2点を結ぶ線分(太さbrushSize)を含む矩形。marginは余白。
	static Rectangle strokeBounds(float x0, float y0, float x1, float y1, float brushSize, int margin){
		int left = (int)Math.floor(Math.min(x0, x1)-brushSize/2)-margin;
		int top = (int)Math.floor(Math.min(y0, y1)-brushSize/2)-margin;
		int right = (int)Math.ceil(Math.max(x0, x1)+brushSize/2)+margin;
		int bottom = (int)Math.ceil(Math.max(y0, y1)+brushSize/2)+margin;
		return new Rectangle(left, top, right-left+1, bottom-top+1);
	}

	@Override
	public void clear(){

	}

	@Override
	public void end(){
		//TabletManager.getDefaultManager().removeTabletListener(PaintTool.owner.mainPane, PaintTool.tabletListener);
	}
}


//-------------------
//   ブラシ
//-------------------
//ブラシ。以前はBasicStroke(CAP_ROUND)で線分を引くだけだったが、
//  Size(直径) / Hardness(縁の柔らかさ) / Opacity(1ストロークの不透明度) /
//  Spacing(丸を置く間隔) / Stabilization(手ぶれ補正)
//を持つ「丸を並べる」ブラシにした。ストロークは常にプレビューレイヤーへ描き、
//丸が重なっても濃くならないよう(Opacityがストローク全体で一様になるよう)
//アルファは上書きでなく大きい方を残す。放したときにOpacity×透明度で
//サーフェースへ合成する。筆圧(jtablet)は無いので、pressureは0.5=等倍のまま。
//TODO 筆圧: Java側で使える筆圧API(JavaFX/JNI)が決まったらpressure→Size/Opacityを繋ぐ。
class BrushTool implements toolTabletInterface{
	Cursor cursor;
	boolean shift;
	float shiftx, shifty;
	//ストローク中(プレビューレイヤーに描いている)か、タブレットの消しゴム側か
	private boolean stroking;
	private boolean previewEraser;
	//手ぶれ補正後の位置と、生のポインタ位置
	private float sx, sy, rawX, rawY;
	//前の丸から進んだ距離(間隔に満たない分の持ち越し)
	private float carry;
	//丸のマスク(直径・硬さ・アンチエイリアスが同じ間は使い回す)
	private static float[] dab;
	private static int dabSide;
	private static float dabDiameter, dabHardness;
	private static boolean dabAA;

	@Override
	public String getName() {
		return "Brush";
	}

	public BrushTool(){
		PaintTool.tabletinit();
	}

	//直径(brushSizeが直径。pressure 0.5で等倍)
	static float diameter(float pressure){
		return Math.max(1f, PaintTool.brushSize*pressure*2.0f);
	}

	//丸のマスク。中心からの距離r(半径Rで正規化したt=r/R)に対して、t<=hardnessは1、
	//t>=1は0、その間は直線で落とす。硬さ100%でアンチエイリアスがオフなら境界で
	//1/0に切る(HyperCardのブラシと同じ)。
	static float[] dabMask(float diameter, float hardness, boolean aa){
		if(dab!=null && dabDiameter==diameter && dabHardness==hardness && dabAA==aa) return dab;
		int side = Math.max(1, (int)Math.ceil(diameter));
		float[] m = new float[side*side];
		float R = diameter/2f, c = side/2f;
		for(int y=0; y<side; y++){
			for(int x=0; x<side; x++){
				float r = (float)Math.hypot(x+0.5f-c, y+0.5f-c);
				float cov;
				if(hardness>=1f){
					cov = aa ? Math.max(0f, Math.min(1f, R-r+0.5f)) : (r<=R ? 1f : 0f);
				}
				else{
					float t = r/R;
					if(t<=hardness) cov = 1f;
					else if(t>=1f) cov = 0f;
					else cov = 1f-(t-hardness)/(1f-hardness);
				}
				m[x+y*side] = cov;
			}
		}
		dab = m; dabSide = side; dabDiameter = diameter; dabHardness = hardness; dabAA = aa;
		return m;
	}

	//丸を1つ置く(アルファは大きい方を残す)
	static void stamp(BufferedImage buf, float cx, float cy, float diameter, int argb, BufferedImage pattern, boolean eraser){
		float[] m = dabMask(diameter, Math.max(0f, Math.min(1f, PaintTool.brushHardness/100f)), PaintTool.antialias);
		int side = dabSide;
		int ox = Math.round(cx-side/2f), oy = Math.round(cy-side/2f);
		int w = buf.getWidth(), h = buf.getHeight();
		int[] px = PaintTool.directPixels(buf);
		int[] pp = pattern==null ? null : PaintTool.directPixels(pattern);
		int pw = pattern==null ? 0 : pattern.getWidth(), ph = pattern==null ? 0 : pattern.getHeight();
		for(int y=0; y<side; y++){
			int by = oy+y;
			if(by<0 || by>=h) continue;
			for(int x=0; x<side; x++){
				int bx = ox+x;
				if(bx<0 || bx>=w) continue;
				float cov = m[x+y*side];
				if(cov<=0f) continue;
				int color = argb;
				if(pattern!=null){
					int mx = ((bx%pw)+pw)%pw, my = ((by%ph)+ph)%ph;
					color = pp!=null ? pp[mx+my*pw] : pattern.getRGB(mx, my);
				}
				if(eraser) color = 0xFFFFFFFF;
				int a = Math.round(((color>>>24)&0xFF)*cov);
				if(a<=0) continue;
				int i = bx+by*w;
				int old = px!=null ? px[i] : buf.getRGB(bx, by);
				if(((old>>>24)&0xFF)>=a) continue;
				int v = (a<<24)|(color&0x00FFFFFF);
				if(px!=null) px[i] = v; else buf.setRGB(bx, by, v);
			}
		}
	}

	//(x0,y0)から(x1,y1)まで、間隔ごとに丸を置く。対称描画では対称な位置にも同じ
	//丸を置く(鏡映・回転は距離を変えないので持ち越し距離carryは共通)。
	private void dabLine(float x0, float y0, float x1, float y1, float diameter, int argb, BufferedImage pattern, boolean eraser){
		AffineTransform[] ts = PaintTool.symmetryTransforms();
		float carry0 = carry;
		for(AffineTransform t : ts){
			carry = carry0;
			float[] a = PaintTool.symPoint(t, x0, y0);
			float[] b = PaintTool.symPoint(t, x1, y1);
			dabLine1(a[0], a[1], b[0], b[1], diameter, argb, pattern, eraser);
		}
	}

	private void dabLine1(float x0, float y0, float x1, float y1, float diameter, int argb, BufferedImage pattern, boolean eraser){
		BufferedImage buf = PaintTool.previewBuf();
		float spacing = Math.max(0.5f, diameter*Math.max(1, PaintTool.brushSpacing)/100f);
		float dx = x1-x0, dy = y1-y0;
		float len = (float)Math.hypot(dx, dy);
		if(len<=0f){
			if(carry<0f){ stamp(buf, x0, y0, diameter, argb, pattern, eraser); carry = 0f; }
			return;
		}
		float t = carry<0f ? 0f : spacing-carry; //次の丸までの距離
		if(carry<0f){ stamp(buf, x0, y0, diameter, argb, pattern, eraser); t = spacing; }
		while(t<=len){
			stamp(buf, x0+dx*t/len, y0+dy*t/len, diameter, argb, pattern, eraser);
			t += spacing;
		}
		carry = len-(t-spacing);
	}

	private int strokeColor(){
		Color c = (GUI.key[14]>0) ? PaintTool.owner.back.color : PaintTool.owner.fore.color;
		return c.getRGB();
	}

	private BufferedImage strokePattern(boolean eraser){
		return (!PaintTool.noPattern() && !eraser) ? PaintTool.pat : null;
	}

	@Override
	public void mouseUp(int x, int y) {
		penUp(x, y, 0.5f, false);
	}

	@Override
	public void penUp(float x, float y, float pressure, boolean eraser){
		if(stroking){
			//放した点まで(手ぶれ補正の残りも)描き切る
			if(shift){ if(shiftx==-1) y = shifty; else if(shifty==-1) x = shiftx; }
			if(sx!=x || sy!=y){
				float d = diameter(pressure);
				dabLine(sx, sy, x, y, d, strokeColor(), strokePattern(eraser), eraser);
				PaintTool.repaintOwnerRect(PaintTool.symmetricBounds(PencilTool.strokeBounds(sx, sy, x, y, d, 1)));
				sx = rawX = x; sy = rawY = y;
			}
			//プレビューレイヤーをサーフェースに書き込む
			if(previewEraser){
				BufferedImage preview = PaintTool.owner.previewBuf;
				BufferedImage surface = PaintTool.owner.getSurface();
				int width = surface.getWidth();
				int height = surface.getHeight();
				for(int h=0; h<height; h++){
					for(int w=0; w<width; w++){
						int src = preview.getRGB(w, h);
						if((src&0xFF000000)==0) continue;
						int tgt = surface.getRGB(w, h);
						int a = (tgt>>>24);
						a = (a * (0xFF-(src>>>24)))/0xFF;
						surface.setRGB(w, h, (a<<24) | (tgt&0x00FFFFFF));
					}
				}
				PaintTool.endPreview();
			}
			else {
				PaintTool.commitPreview(strokeAlpha());
			}
			stroking = false;
		}

		PaintTool.toCdPict();
		PaintTool.repaintOwner();
	}

	//ストローク全体の不透明度(透明度ボタン×ブラシのOpacity)
	static float strokeAlpha(){
		return Math.max(0f, Math.min(1f, PaintTool.alpha/100.0F * PaintTool.brushOpacity/100.0F));
	}

	@Override
	public void mouseDown(int x, int y) {
		penDown(x, y, 0.501f, false);
	}

	@Override
	public void penDown(float x, float y, float pressure, boolean eraser){
		if(GUI.key[12]>0) {
			PaintTool.spoit((int)x, (int)y);
			return;
		}
		shift = (GUI.key[11]>0);
		shiftx = x; shifty = y;

		GMenuPaint.setUndo();

		PaintTool.setPattern(GUI.key[14]>0);

		stroking = true;
		previewEraser = eraser;
		PaintTool.beginPreview(eraser ? 1.0f : strokeAlpha());

		sx = rawX = x; sy = rawY = y;
		carry = -1f; //最初の丸を置く

		PaintTool.lastx[0]=x;
		PaintTool.lasty[0]=y;
		penStillDown(x,y,pressure, eraser);
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		return penWithin(x, y, 0.5f);
	}

	public boolean penWithin(float x, float y, float pressure){
		return true;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(GUI.key[12]>0){
			PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else{
			PaintTool.owner.mainPane.setCursor(cursor);
		}
		return penStillDown(x, y, 0.5f, false);
	}

	@Override
	public boolean penStillDown(float x, float y, float pressure, boolean eraser){
		if(!stroking) return true;
		if(shift && ((x-shiftx!=0) || (y-shifty!=0))){
			if((shiftx==-1) || (shifty!=-1) && (Math.abs(x-shiftx) > Math.abs(y-shifty))){
				shiftx = -1;
				y = shifty;
				PaintTool.lasty[0] = (int)shifty;
				PaintTool.lasty[1] = (int)shifty;
			}else if ((shifty==-1) || (shiftx!=-1)){
				shifty = -1;
				x = shiftx;
				PaintTool.lastx[0] = (int)shiftx;
				PaintTool.lastx[1] = (int)shiftx;
			}
		}
		rawX = x; rawY = y;

		//手ぶれ補正: 補正位置をポインタへ少しずつ寄せる(100%に近いほどゆっくり)
		float nx = x, ny = y;
		if(PaintTool.brushStabilization>0){
			float pull = Math.max(0.02f, 1f-Math.min(98, PaintTool.brushStabilization)/100f);
			nx = sx+(x-sx)*pull;
			ny = sy+(y-sy)*pull;
		}

		float d = diameter(pressure);
		dabLine(sx, sy, nx, ny, d, strokeColor(), strokePattern(eraser), eraser);

		//変更した範囲だけ再描画(paintComponentが半透明合成してくれる)
		PaintTool.repaintOwnerRect(PaintTool.symmetricBounds(PencilTool.strokeBounds(sx, sy, nx, ny, d, 1)));
		sx = nx; sy = ny;

		PaintTool.lastTime = System.currentTimeMillis();

		return true;
	}

	@Override
	public void clear(){

	}

	@Override
	public void end(){
		if(stroking){
			PaintTool.endPreview();
			stroking = false;
		}
	}
}


//-------------------
// 消しゴム
//-------------------
class EraserTool implements toolTabletInterface{
	Cursor cursor;
	boolean shift;
	float shiftx, shifty;
	private boolean stroking;

	public EraserTool(){
		PaintTool.tabletinit();
	}

	//消しゴムの大きさ(論理ピクセル)。等倍で16、FatBitsでは画面上16ピクセルに見える大きさ
	static float brushSize(float bit){
		return PaintTool.eraserSize/(float)bit;
	}

	@Override
	public String getName() {
		return "Eraser";
	}

	@Override
	public void mouseUp(int x, int y) {
		penUp(x, y, 0.5f, false);
	}

	@Override
	public void penUp(float x, float y, float pressure, boolean eraser){
		if(stroking){
			//プレビューレイヤーに描いた部分をサーフェースから消す
			BufferedImage preview = PaintTool.owner.previewBuf;
			BufferedImage surface = PaintTool.owner.getSurface();
			int width = surface.getWidth();
			int height = surface.getHeight();
			int erased = PaintTool.owner.opaqueSurface() ? 0xFFFFFFFF : 0x00FFFFFF;
			int[] src = PaintTool.directPixels(preview);
			int[] tgt = PaintTool.directPixels(surface);
			if(src!=null && tgt!=null){
				int n = width*height;
				for(int i=0; i<n; i++){
					if((src[i]&0xFF000000)!=0) tgt[i] = erased;
				}
			}
			else{
				for(int h=0; h<height; h++){
					for(int w=0; w<width; w++){
						if((preview.getRGB(w, h)&0xFF000000)!=0){
							surface.setRGB(w, h, erased);
						}
					}
				}
			}
			PaintTool.endPreview();
			stroking = false;
		}

		PaintTool.toCdPict();
		PaintTool.repaintOwner();
	}

	@Override
	public void mouseDown(int x, int y) {
		penDown(x, y+0.5f, 0.5f, false);
	}

	@Override
	public void penDown(float x, float y, float pressure, boolean eraser){
		GMenuPaint.setUndo();
		shift = (GUI.key[11]>0);
		shiftx = x; shifty = y;

		//消した部分は背景(バックグラウンド編集中は白)が見えるように、
		//プレビューレイヤーへ背景の絵を描いていく
		PaintTool.beginPreview(1.0f);
		stroking = true;

		PaintTool.lastx[0]=x;
		PaintTool.lasty[0]=y;
		penStillDown(x,y,pressure, eraser);
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		return penWithin(x, y, 0.5f);
	}

	public boolean penWithin(float x, float y, float pressure){
		return true;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		return penStillDown(x, y+0.5f, 0.5f, false);
	}

	@Override
	public boolean penStillDown(float x, float y, float pressure, boolean eraser){
		if(!stroking) return false;
		if(shift && ((x-shiftx!=0) || (y-shifty!=0))){
			if((shiftx==-1) || (shifty!=-1) && (Math.abs(x-shiftx) > Math.abs(y-shifty))){
				shiftx = -1;
				y = shifty;
				PaintTool.lasty[0] = (int)shifty;
			}else if ((shifty==-1) || (shiftx!=-1)){
				shifty = -1;
				x = shiftx;
				PaintTool.lastx[0] = (int)shiftx;
			}
		}

		Graphics2D g2 = PaintTool.previewBuf().createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		float brushSize = brushSize(PaintTool.owner.bit);
		g2.setStroke(new BasicStroke(brushSize, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

		if(PaintTool.owner.opaqueSurface() || PaintTool.owner.bgImg==null){
			g2.setColor(Color.WHITE);
		}else if(PaintTool.owner.isEditingBackground()){
			//バックグラウンドの追加レイヤー: 下はバックグラウンドピクチャ
			Rectangle2D.Double r = new Rectangle2D.Double(0, 0, PaintTool.owner.bgImg.getWidth(), PaintTool.owner.bgImg.getHeight());
			g2.setPaint(new TexturePaint(PaintTool.owner.bgImg, r));
		}else{
			Rectangle2D.Double r = new Rectangle2D.Double(0, 0, PaintTool.owner.bgImg.getWidth(), PaintTool.owner.bgImg.getHeight());
			g2.setPaint(new TexturePaint(PaintTool.owner.bgImg, r));
		}
		Line2D.Double line;
		//対称描画: 対称な位置にも同じ消しゴムを走らせる(消しゴムの形は正方形なので
		//位置だけ写せばよい)
		for(AffineTransform t : PaintTool.symmetryTransforms()){
			float[] a = PaintTool.symPoint(t, PaintTool.lastx[0], PaintTool.lasty[0]);
			float[] b = PaintTool.symPoint(t, x, y);
			for(int i=1; i<=16; i++){
				double px = (a[0]*i+b[0]*(16-i))/16;
				double py = (a[1]*i+b[1]*(16-i))/16;
				if(brushSize<2){
					//16倍以上: ポインタのあるマス目1つを消す(幅1未満の線は
					//マス目の境界をまたいで、どちらが消えるか定まらない)
					g2.fill(new Rectangle2D.Double(Math.floor(px), Math.floor(py), 1, 1));
					continue;
				}
				line = new Line2D.Double(px, py-brushSize/2, px, py+brushSize/2);
				g2.draw(line);
			}
		}
		g2.dispose();

		PaintTool.repaintOwnerRect(PaintTool.symmetricBounds(PencilTool.strokeBounds(PaintTool.lastx[0], PaintTool.lasty[0], x, y, brushSize, 4)));

		PaintTool.lastTime = System.currentTimeMillis();

		return true;
	}

	@Override
	public void clear(){

	}

	@Override
	public void end(){
		if(stroking){
			PaintTool.endPreview();
			stroking = false;
		}
	}
}


//-------------------
// バケツ
//-------------------
class PaintBucketTool implements toolInterface{

	@Override
	public String getName() {
		return "PaintBucket";
	}

	public static void gradfill(BufferedImage img, Color color1, Color color2, double angle){
		gradfill(img, color1, color2, angle, false);
	}

	//GradButtonの設定(色は今の前景色→背景色、角度、線形/放射状)で塗る。
	//テクスチャ塗りのときはPaintTexture.fill(imgの原点=キャンバスの原点)。
	public static void gradfill(BufferedImage img, GradButton grad){
		gradfill(img, grad, 0, 0);
	}

	//(ox,oy)はimgの左上のキャンバス座標。グラデーションには関係なく、テクスチャの
	//敷き詰めをキャンバスに揃えるために使う。
	public static void gradfill(BufferedImage img, GradButton grad, int ox, int oy){
		if(grad.texture){
			PaintTexture.fill(img, PaintTexture.current, ox, oy);
			return;
		}
		gradfill(img, grad.color1, grad.color2, grad.angle, grad.radial);
	}

	//imgの不透明な部分をグラデーションで塗る。線形は角度(0°で上→下、90°で
	//左→右)に沿って背景色→前景色、放射状は不透明な部分の中心が前景色で
	//外へ向かって背景色になる。元の画素のアルファは残す(図形のアンチエイリアス
	//の縁が消えない)ので、色のアルファと掛け合わせた値が結果のアルファになる。
	public static void gradfill(BufferedImage img, Color color1, Color color2, double angle, boolean radial){
		//imgの不透明な部分のangle補正した領域を取得
		DataBuffer buffer = img.getRaster().getDataBuffer();
		int width = img.getWidth();
		int height = img.getHeight();
		double sin = Math.sin(angle);
		double cos = Math.cos(angle);
		double min = 0, max = 0;
		int left = 0, top = 0, right = -1, bottom = -1;
		boolean found = false;

		for(int y=0; y<height; y++){
			for(int x=0; x<width; x++){
				if((0xFF000000&buffer.getElem(0, x+y*width))!=0){
					double p = x*sin+y*cos;
					if(!found){
						min = p;
						max = p;
						left = right = x;
						top = bottom = y;
						found = true;
					}
					if(p<min) min = p;
					if(p>max) max = p;
					if(x<left) left = x;
					if(x>right) right = x;
					if(y<top) top = y;
					if(y>bottom) bottom = y;
				}
			}
		}
		if(!found) return;
		double range = max-min;
		double cx = (left+right)/2.0, cy = (top+bottom)/2.0;
		double radius = Math.hypot((right-left)/2.0, (bottom-top)/2.0);
		int a1 = color1.getAlpha(), a2 = color2.getAlpha();

		//不透明な部分をグラデーション塗り
		for(int y=0; y<height; y++){
			for(int x=0; x<width; x++){
				int src = buffer.getElem(0, x+y*width);
				int srcAlpha = (src>>>24)&0xFF;
				if(srcAlpha!=0){
					double percent;
					if(radial){
						percent = (radius<=0.0) ? 1.0 : 1.0-Math.hypot(x-cx, y-cy)/radius;
					}else{
						//投影幅が0(1ドットや1ライン)なら0除算を避けて中間色にする
						percent = (range<=0.0) ? 0.5 : (x*sin+y*cos - min)/range;
					}
					if(percent<0) percent = 0; else if(percent>1) percent = 1;
					int c = ((int)(color1.getRed()*percent+color2.getRed()*(1.0-percent)))<<16;
					c += ((int)(color1.getGreen()*percent+color2.getGreen()*(1.0-percent)))<<8;
					c += ((int)(color1.getBlue()*percent+color2.getBlue()*(1.0-percent)));
					int a = (int)(a1*percent+a2*(1.0-percent));
					a = a*srcAlpha/0xFF;
					buffer.setElem(0, x+y*width, (a<<24)+c);
				}
			}
		}
	}

	//(px,py)と同じ色(RGBのみ比較、アルファは無視)で連結している領域をmaskへ
	//0xFF000000で書き込む(スキャンライン塗りつぶし)。maskはsurfaceと同じ大きさで
	//透明に初期化されていること。
	static void floodFill(BufferedImage surface, BufferedImage mask, int px, int py){
		floodFill(surface, mask, px, py, 0);
	}

	//tolerancePercent(0〜100)ぶんまでRGB各成分が違う画素も同じ色とみなす
	//(バケツのダブルクリックで出る許容範囲。0なら完全一致)。
	static void floodFill(BufferedImage surface, BufferedImage mask, int px, int py, int tolerancePercent){
		int width = surface.getWidth();
		int height = surface.getHeight();
		if(px<0 || py<0 || px>=width || py>=height) return;
		if(mask.getWidth()!=width || mask.getHeight()!=height) return;

		int[] src = PaintTool.directPixels(surface);
		if(src==null){
			src = surface.getRGB(0, 0, width, height, null, 0, width);
		}
		int[] dst = PaintTool.directPixels(mask);
		boolean writeBack = false;
		if(dst==null){
			dst = mask.getRGB(0, 0, width, height, null, 0, width);
			writeBack = true;
		}
		final int filled = 0xFF000000;
		final int target = src[px+py*width]&0x00FFFFFF;
		final int near = Math.max(0, Math.min(100, tolerancePercent))*255/100;
		final int[] pixels = src;
		java.util.function.IntPredicate same = near==0
			? (i -> (pixels[i]&0x00FFFFFF)==target)
			: (i -> {
				int c = pixels[i];
				return Math.abs(((c>>16)&0xFF)-((target>>16)&0xFF))<=near &&
					Math.abs(((c>>8)&0xFF)-((target>>8)&0xFF))<=near &&
					Math.abs((c&0xFF)-(target&0xFF))<=near;
			});

		ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
		queue.add(new int[]{px, py});
		while(!queue.isEmpty()){
			int[] p = queue.poll();
			int x = p[0];
			int y = p[1];
			int row = y*width;
			if(dst[row+x]==filled) continue;
			if(!same.test(row+x)) continue;

			//左右へ伸ばして同じ色のスパンを求める
			int lx = x;
			while(lx>0 && dst[row+lx-1]!=filled && same.test(row+lx-1)) lx--;
			int rx = x;
			while(rx<width-1 && dst[row+rx+1]!=filled && same.test(row+rx+1)) rx++;
			for(int i=lx; i<=rx; i++) dst[row+i] = filled;

			//上下の行で、スパンに接している未塗りの区間ごとに1点ずつ登録する
			for(int dy=-1; dy<=1; dy+=2){
				int ny = y+dy;
				if(ny<0 || ny>=height) continue;
				int nrow = ny*width;
				boolean inRun = false;
				for(int i=lx; i<=rx; i++){
					boolean match = dst[nrow+i]!=filled && same.test(nrow+i);
					if(match && !inRun){
						queue.add(new int[]{i, ny});
					}
					inRun = match;
				}
			}
		}
		if(writeBack){
			mask.setRGB(0, 0, width, height, dst, 0, width);
		}
	}

	private void seedfill(BufferedImage surface, BufferedImage newSurface, int px, int py){
		int width = surface.getWidth();
		int height = surface.getHeight();

		//裏画面を透明にする
		PaintTool.clearImage(newSurface);

		//サーフェースの透明部分を#FFFFFFにする(色比較を「透明=白」で統一するため)
		int[] px2 = PaintTool.directPixels(surface);
		if(px2!=null){
			int n = width*height;
			for(int i=0; i<n; i++){
				if((px2[i] & 0xFF000000)==0) px2[i] = 0x00FFFFFF;
			}
		}
		else{
			for(int y=0; y<height; y++){
				for(int x=0; x<width; x++){
					if((surface.getRGB(x, y) & 0xFF000000)==0){
						surface.setRGB(x, y, 0x00FFFFFF);
					}
				}
			}
		}

		floodFill(surface, newSurface, px, py, PaintTool.fillTolerancePercent);

		//パターンを適用
		PaintTool.setPattern(false);
		DataBuffer fillbuf = newSurface.getRaster().getDataBuffer();
		DataBuffer patbuf = PaintTool.pat.getRaster().getDataBuffer();
		int patW = PaintTool.pat.getWidth();
		int patH = PaintTool.pat.getHeight();
		for(int y=0; y<height; y++){
			for(int x=0; x<width; x++){
				int c1 = fillbuf.getElem(0, x+y*width);
				if((c1 & 0xFF000000) != 0){
					int c2 = patbuf.getElem(x%patW + (y%patH)*patW);
					fillbuf.setElem(0, x+y*width, c2);
				}
			}
		}

		//グラデーションを適用(パターンとの併用は不可)
		if(PaintTool.owner.grad!=null && PaintTool.owner.grad.use){
			gradfill(newSurface, PaintTool.owner.grad);
		}

		//サーフェースに反映する
		Graphics2D g2 = surface.createGraphics();
		g2.setComposite( AlphaComposite.getInstance(AlphaComposite.SRC_OVER,PaintTool.alpha/100.0F) );
		g2.drawImage(newSurface, 0, 0, null);
		g2.dispose();
	}

	@Override
	public void mouseUp(int x, int y) {
		if(GUI.key[12]>0) {
			PaintTool.spoit(x, y);
			return;
		}
		if(!PaintTool.inSurface(x, y)) return;

		GMenuPaint.setUndo();

		//プレビューレイヤーを作業用バッファとして使う(画面には出さない)。
		//対称描画では対称な位置からも塗る
		for(AffineTransform t : PaintTool.symmetryTransforms()){
			Point p = PaintTool.symPoint(t, x, y);
			if(!PaintTool.inSurface(p.x, p.y)) continue;
			seedfill(PaintTool.owner.getSurface(), PaintTool.previewBuf(), p.x, p.y);
		}
		PaintTool.endPreview();

		PaintTool.toCdPict();
		PaintTool.repaintOwner();
	}

	@Override
	public void mouseDown(int x, int y) {
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		if(GUI.key[12]>0){
			PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		}
		else{
			PaintTool.owner.mainPane.setCursor(TBCursor.bucketCursor());
		}
		return true;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		return true;
	}

	@Override
	public void clear(){

	}

	@Override
	public void end(){

	}
}


//-------------------
//スポイト(AppleWorksのEyedropper)。押した点の色(透明度も)を前景色に、右ボタン
//なら背景色にする。ドラッグ中も追いかけて拾う。他のツールのOptionクリックと
//同じPaintTool.spoit。
//-------------------
class EyedropperTool implements toolInterface{

	@Override
	public String getName() {
		return "Eyedropper";
	}

	@Override
	public void mouseUp(int x, int y) {
	}

	@Override
	public void mouseDown(int x, int y) {
		PaintTool.spoit(x, y);
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		PaintTool.owner.mainPane.setCursor(ClassicToolArt.cursor("Eyedropper"));
		return true;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		PaintTool.spoit(x, y);
		return true;
	}

	@Override
	public void clear(){
	}

	@Override
	public void end(){
	}
}


//-------------------
//指先(Smudge)。押した所の絵を拾い、ドラッグに沿って引き伸ばす。丸のマスク
//(ブラシと同じSize/Hardness/Spacing)の範囲で、1点進むごとに「そこにある絵」と
//「指に付いている絵」を混ぜて指に付け直し、それを置く。強さ(smudgeStrength)は
//指に付いた絵がブラシ1つ分進む間にどれだけ残るか(100%なら減らずに絵がそのまま
//動き、0%なら何も付かない)。透明も拾って運ぶ(premultipliedアルファで補間)ので、
//カードの絵の縁を透明側へなでるとぼやけて消えていく。
//サーフェースへ直接描く(1点ごとに下の絵を読み直すのでプレビューレイヤーは使わない)。
//-------------------
class SmudgeTool implements toolInterface{
	private boolean stroking;
	private float sx, sy, carry;
	private boolean shift;
	private float shiftx, shifty;
	//対称描画の写し先ごとに拾った絵(直径×直径のARGB。中心が(dab側/2))
	private int[][] pickup;
	private int pickupSide;

	@Override
	public String getName(){
		return "Smudge";
	}

	static float diameter(){
		return Math.max(1f, PaintTool.brushSize);
	}

	//(cx,cy)を中心とした丸の範囲のサーフェースの絵をbufへ拾う(範囲外は透明)
	private static void pick(BufferedImage surface, int[] buf, int side, float cx, float cy){
		int ox = Math.round(cx-side/2f), oy = Math.round(cy-side/2f);
		int w = surface.getWidth(), h = surface.getHeight();
		int[] px = PaintTool.directPixels(surface);
		for(int y=0; y<side; y++){
			int by = oy+y;
			for(int x=0; x<side; x++){
				int bx = ox+x;
				int v = 0;
				if(bx>=0 && by>=0 && bx<w && by<h) v = px!=null ? px[bx+by*w] : surface.getRGB(bx, by);
				buf[x+y*side] = v;
			}
		}
	}

	//(cx,cy)にある絵と指に付いた絵(buf)を、keep(指の絵が残る割合)で混ぜて指に付け直し、
	//マスクの範囲へ置く
	private static void deposit(BufferedImage surface, int[] buf, int side, float cx, float cy, float keep){
		float[] m = BrushTool.dabMask(side, Math.max(0f, Math.min(1f, PaintTool.brushHardness/100f)), PaintTool.antialias);
		int ox = Math.round(cx-side/2f), oy = Math.round(cy-side/2f);
		int w = surface.getWidth(), h = surface.getHeight();
		int[] px = PaintTool.directPixels(surface);
		for(int y=0; y<side; y++){
			int by = oy+y;
			for(int x=0; x<side; x++){
				int bx = ox+x;
				int bi = x+y*side;
				if(bx<0 || by<0 || bx>=w || by>=h){
					//範囲外は透明として指に混ぜる
					buf[bi] = lerpPremultiplied(0, buf[bi], keep);
					continue;
				}
				int i = bx+by*w;
				int d = px!=null ? px[i] : surface.getRGB(bx, by);
				int mixed = lerpPremultiplied(d, buf[bi], keep);
				buf[bi] = mixed;
				float cov = m[bi];
				if(cov<=0f) continue;
				int v = cov>=1f ? mixed : lerpPremultiplied(d, mixed, cov);
				if(v!=d){ if(px!=null) px[i] = v; else surface.setRGB(bx, by, v); }
			}
		}
	}

	//dからpへ割合tだけ寄せる(premultipliedアルファで補間)
	static int lerpPremultiplied(int d, int p, float t){
		float ad = ((d>>>24)&0xFF)/255f, ap = ((p>>>24)&0xFF)/255f;
		float rd = ((d>>16)&0xFF)*ad, gd = ((d>>8)&0xFF)*ad, bd = (d&0xFF)*ad;
		float rp = ((p>>16)&0xFF)*ap, gp = ((p>>8)&0xFF)*ap, bp = (p&0xFF)*ap;
		float a = ad+(ap-ad)*t;
		if(a<=0.0005f) return 0;
		float r = (rd+(rp-rd)*t)/a, g = (gd+(gp-gd)*t)/a, b = (bd+(bp-bd)*t)/a;
		int ia = Math.round(a*255), ir = clamp255(Math.round(r)), ig = clamp255(Math.round(g)), ib = clamp255(Math.round(b));
		return (ia<<24)|(ir<<16)|(ig<<8)|ib;
	}
	private static int clamp255(int v){ return v<0 ? 0 : v>255 ? 255 : v; }

	private void ensurePickup(int n, int side){
		if(pickup==null || pickup.length!=n || pickupSide!=side){
			pickup = new int[n][side*side];
			pickupSide = side;
		}
	}

	@Override
	public void mouseDown(int x, int y){
		if(GUI.key[12]>0){
			PaintTool.spoit(x, y);
			return;
		}
		if(!PaintTool.inSurface(x, y)) return;
		GMenuPaint.setUndo();
		shift = (GUI.key[11]>0);
		shiftx = x; shifty = y;
		BufferedImage surface = PaintTool.owner.getSurface();
		AffineTransform[] ts = PaintTool.symmetryTransforms();
		int side = Math.max(1, (int)Math.ceil(diameter()));
		ensurePickup(ts.length, side);
		for(int k=0; k<ts.length; k++){
			float[] p = PaintTool.symPoint(ts[k], (float)x, (float)y);
			pick(surface, pickup[k], side, p[0], p[1]);
		}
		sx = x; sy = y; carry = 0f;
		stroking = true;
	}

	@Override
	public boolean mouseStillDown(int x, int y){
		if(!stroking) return false;
		float fx = x, fy = y;
		if(shift && ((fx-shiftx!=0) || (fy-shifty!=0))){
			if((shiftx==-1) || (shifty!=-1) && (Math.abs(fx-shiftx) > Math.abs(fy-shifty))){
				shiftx = -1; fy = shifty;
			}else if((shifty==-1) || (shiftx!=-1)){
				shifty = -1; fx = shiftx;
			}
		}
		BufferedImage surface = PaintTool.owner.getSurface();
		float d = diameter();
		int side = pickupSide;
		float strength = Math.max(0f, Math.min(1f, PaintTool.smudgeStrength/100f));
		float spacing = Math.max(1f, d*Math.max(1, PaintTool.brushSpacing)/100f);
		//1点(spacing)進む間に指の絵が残る割合: ブラシ1つ分(d)でstrengthになるように
		float keep = strength>=1f ? 1f : (strength<=0f ? 0f : (float)Math.pow(strength, spacing/d));
		float dx = fx-sx, dy = fy-sy;
		float len = (float)Math.hypot(dx, dy);
		if(len<=0f) return true;
		AffineTransform[] ts = PaintTool.symmetryTransforms();
		if(ts.length!=pickup.length){
			//途中で対称設定が変わった: 今の位置で拾い直す
			ensurePickup(ts.length, side);
			for(int k=0; k<ts.length; k++){
				float[] p = PaintTool.symPoint(ts[k], sx, sy);
				pick(surface, pickup[k], side, p[0], p[1]);
			}
		}
		float t = spacing-carry;
		while(t<=len){
			float px = sx+dx*t/len, py = sy+dy*t/len;
			for(int k=0; k<ts.length; k++){
				float[] p = PaintTool.symPoint(ts[k], px, py);
				deposit(surface, pickup[k], side, p[0], p[1], keep);
			}
			t += spacing;
		}
		carry = len-(t-spacing);
		PaintTool.repaintOwnerRect(PaintTool.symmetricBounds(PencilTool.strokeBounds(sx, sy, fx, fy, d, 1)));
		sx = fx; sy = fy;
		PaintTool.lastTime = System.currentTimeMillis();
		return true;
	}

	@Override
	public void mouseUp(int x, int y){
		if(stroking){
			mouseStillDown(x, y);
			stroking = false;
		}
		PaintTool.toCdPict();
		PaintTool.repaintOwner();
	}

	@Override
	public boolean mouseWithin(int x, int y){
		PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(GUI.key[12]>0 ? Cursor.CROSSHAIR_CURSOR : Cursor.HAND_CURSOR));
		return true;
	}

	@Override
	public void clear(){
	}

	@Override
	public void end(){
		stroking = false;
	}
}


//-------------------
//スタンプ(Clone Stamp)。Optionクリックで写し元を決め、次にドラッグした所へ写し元の
//絵を描く。写し元と描く位置の差(ずれ)は最初のストロークで決まり、写し元を選び直す
//まで保たれる。写し元が無いときの最初のクリックは写し元の
//指定になる。ブラシと同じ丸のマスク・Size/Hardness/Spacing/Opacityで、ストロークは
//プレビューレイヤーへ描いて放したときに合成する(1ストロークの中で写し元が
//塗り重なった絵を拾わないため)。
//-------------------
class StampTool implements toolInterface{
	//写し元(サーフェース座標)。nullなら未指定
	static Point source;
	//写し元と描く位置のずれ(source - 最初にドラッグを始めた点)。設定済みか
	static int offsetX, offsetY;
	static boolean offsetSet;

	private boolean stroking;
	private float sx, sy, carry;
	private boolean shift;
	private float shiftx, shifty;
	//このストロークで写し元として読むサーフェース(ストローク中は変化しない)
	private BufferedImage from;

	@Override
	public String getName(){
		return "Stamp";
	}

	static void setSource(int x, int y){
		source = new Point(x, y);
		offsetSet = false;
		PaintTool.repaintOwner();
	}

	static float diameter(){
		return Math.max(1f, PaintTool.brushSize);
	}

	//(cx,cy)を中心とした丸に、(cx+offsetX, cy+offsetY)を中心とした写し元の絵を
	//プレビューレイヤーへ置く(ブラシのstampと同じくアルファは大きい方を残す)
	private void dab(BufferedImage buf, float cx, float cy, float diameter){
		float[] m = BrushTool.dabMask(diameter, Math.max(0f, Math.min(1f, PaintTool.brushHardness/100f)), PaintTool.antialias);
		int side = (int)Math.ceil(diameter);
		int ox = Math.round(cx-side/2f), oy = Math.round(cy-side/2f);
		int w = buf.getWidth(), h = buf.getHeight();
		int fw = from.getWidth(), fh = from.getHeight();
		int[] px = PaintTool.directPixels(buf);
		int[] fp = PaintTool.directPixels(from);
		for(int y=0; y<side; y++){
			int by = oy+y, fy = by+offsetY;
			if(by<0 || by>=h || fy<0 || fy>=fh) continue;
			for(int x=0; x<side; x++){
				int bx = ox+x, fx = bx+offsetX;
				if(bx<0 || bx>=w || fx<0 || fx>=fw) continue;
				float cov = m[x+y*side];
				if(cov<=0f) continue;
				int color = fp!=null ? fp[fx+fy*fw] : from.getRGB(fx, fy);
				int a = Math.round(((color>>>24)&0xFF)*cov);
				if(a<=0) continue;
				int i = bx+by*w;
				int old = px!=null ? px[i] : buf.getRGB(bx, by);
				if(((old>>>24)&0xFF)>=a) continue;
				int v = (a<<24)|(color&0x00FFFFFF);
				if(px!=null) px[i] = v; else buf.setRGB(bx, by, v);
			}
		}
	}

	private void dabLine(float x0, float y0, float x1, float y1){
		BufferedImage buf = PaintTool.previewBuf();
		float d = diameter();
		float spacing = Math.max(0.5f, d*Math.max(1, PaintTool.brushSpacing)/100f);
		AffineTransform[] ts = PaintTool.symmetryTransforms();
		float dx = x1-x0, dy = y1-y0;
		float len = (float)Math.hypot(dx, dy);
		if(len<=0f){
			if(carry<0f){
				for(AffineTransform t : ts){ float[] p = PaintTool.symPoint(t, x0, y0); dab(buf, p[0], p[1], d); }
				carry = 0f;
			}
			return;
		}
		float t = carry<0f ? 0f : spacing-carry;
		if(carry<0f){
			for(AffineTransform tr : ts){ float[] p = PaintTool.symPoint(tr, x0, y0); dab(buf, p[0], p[1], d); }
			t = spacing;
		}
		while(t<=len){
			float px = x0+dx*t/len, py = y0+dy*t/len;
			for(AffineTransform tr : ts){ float[] p = PaintTool.symPoint(tr, px, py); dab(buf, p[0], p[1], d); }
			t += spacing;
		}
		carry = len-(t-spacing);
	}

	@Override
	public void mouseDown(int x, int y){
		if(GUI.key[12]>0 || source==null){
			//Optionクリック(または写し元未指定の最初のクリック): 写し元を決める
			if(PaintTool.inSurface(x, y)) setSource(x, y);
			return;
		}
		if(!offsetSet){
			offsetX = source.x-x; offsetY = source.y-y;
			offsetSet = true;
		}
		GMenuPaint.setUndo();
		shift = (GUI.key[11]>0);
		shiftx = x; shifty = y;
		from = PaintTool.owner.getSurface();
		PaintTool.beginPreview(BrushTool.strokeAlpha());
		stroking = true;
		sx = x; sy = y; carry = -1f;
		dabLine(x, y, x, y);
		PaintTool.repaintOwnerRect(PaintTool.symmetricBounds(PencilTool.strokeBounds(x, y, x, y, diameter(), 1)));
	}

	@Override
	public boolean mouseStillDown(int x, int y){
		if(!stroking) return false;
		float fx = x, fy = y;
		if(shift && ((fx-shiftx!=0) || (fy-shifty!=0))){
			if((shiftx==-1) || (shifty!=-1) && (Math.abs(fx-shiftx) > Math.abs(fy-shifty))){
				shiftx = -1; fy = shifty;
			}else if((shifty==-1) || (shiftx!=-1)){
				shifty = -1; fx = shiftx;
			}
		}
		dabLine(sx, sy, fx, fy);
		PaintTool.repaintOwnerRect(PaintTool.symmetricBounds(PencilTool.strokeBounds(sx, sy, fx, fy, diameter(), 1)));
		sx = fx; sy = fy;
		PaintTool.lastTime = System.currentTimeMillis();
		return true;
	}

	@Override
	public void mouseUp(int x, int y){
		if(stroking){
			mouseStillDown(x, y);
			PaintTool.commitPreview(BrushTool.strokeAlpha());
			stroking = false;
			from = null;
		}
		PaintTool.toCdPict();
		PaintTool.repaintOwner();
	}

	@Override
	public boolean mouseWithin(int x, int y){
		PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		return true;
	}

	//写し元の印(十字)。ストローク中は今描いている位置に対応する写し元を示す
	@Override
	public void paintOverlay(Graphics2D g){
		if(source==null) return;
		float eb = PaintTool.effectiveBit();
		int left = PaintTool.viewLeft(), top = PaintTool.viewTop();
		float px, py;
		if(stroking && offsetSet){ px = sx+offsetX; py = sy+offsetY; }
		else{ px = source.x; py = source.y; }
		float cx = (px+0.5f-left)*eb, cy = (py+0.5f-top)*eb;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(new BasicStroke(1f));
		g.setColor(Color.WHITE);
		g.draw(new Ellipse2D.Float(cx-6, cy-6, 12, 12));
		g.setColor(new Color(0x30, 0x80, 0xFF));
		g.draw(new Ellipse2D.Float(cx-5, cy-5, 10, 10));
		g.draw(new Line2D.Float(cx-8, cy, cx+8, cy));
		g.draw(new Line2D.Float(cx, cy-8, cx, cy+8));
	}

	@Override
	public void clear(){
	}

	@Override
	public void end(){
		if(stroking){
			PaintTool.endPreview();
			stroking = false;
			from = null;
		}
	}
}


//-------------------
//スプレー缶
//-------------------
class SprayTool implements toolInterface{
	private Random rng = new Random();

	@Override
	public String getName(){
		return "Spray Can";
	}

	//(x,y)を中心に、散布半径内へランダムな点を吹き付ける。透明度はドットごとの
	//アルファ合成で反映するので、重なるほど自然に濃くなる(実物のスプレーの質感に近い)。
	private void sprayAt(int x, int y){
		int radius = Math.max(2, PaintTool.sprayRadius);
		int dots = Math.max(2, radius/2);

		Graphics2D g2 = PaintTool.owner.getSurface().createGraphics();
		if(PaintTool.antialias){
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		}else{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		}
		Color color = (GUI.key[14]>0) ? PaintTool.owner.back.color : PaintTool.owner.fore.color;
		g2.setColor(color);
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, PaintTool.alpha/100.0F));

		int left=x-radius, top=y-radius, right=x+radius, bottom=y+radius;
		for(int i=0; i<dots; i++){
			double angle = rng.nextDouble()*Math.PI*2;
			//中心に寄るほど点が密集するよう平方根で分布をとる(実際のスプレーらしい濃淡になる)
			double dist = Math.sqrt(rng.nextDouble())*radius;
			int px = x + (int)Math.round(Math.cos(angle)*dist);
			int py = y + (int)Math.round(Math.sin(angle)*dist);
			g2.fillRect(px, py, 1, 1);
		}
		g2.dispose();

		PaintTool.repaintOwnerRect(left-1, top-1, right-left+2, bottom-top+2);
	}

	//対称な位置にも吹き付ける
	private void spraySymmetric(int x, int y){
		for(AffineTransform t : PaintTool.symmetryTransforms()){
			Point p = PaintTool.symPoint(t, x, y);
			sprayAt(p.x, p.y);
		}
	}

	@Override
	public void mouseDown(int x, int y) {
		if(GUI.key[12]>0) {
			PaintTool.spoit(x, y);
			return;
		}
		GMenuPaint.setUndo();
		PaintTool.setPattern(GUI.key[14]>0);
		spraySymmetric(x, y);
	}

	@Override
	public void mouseUp(int x, int y) {
		if(GUI.key[12]>0) return;
		PaintTool.toCdPict();
		PaintTool.repaintOwner();
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		return false;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(GUI.key[12]>0) return false;
		spraySymmetric(x, y);
		return true;
	}

	@Override
	public void clear() {
	}

	@Override
	public void end() {
	}
}


//-------------------
//図形ツール共通部(矩形・角丸矩形・楕円・曲線・正多角形・多角形・線分)
//-------------------
//ドラッグ中の図形はプレビューレイヤーに描き、paintComponentが透明度を反映して
//画面へ重ねる。確定時にプレビューレイヤーをサーフェースへ合成する。
abstract class ShapeToolBase implements toolInterface{
	protected Rectangle lastClipRect = null;
	protected boolean previewing;
	protected boolean patternedStroke;
	//Draw Multipleで最後に図形を残したマウス位置(multiSpaceの間隔判定用)
	private Point lastMultiplePoint;

	//図形の描画開始(Undo退避、パターン準備、プレビュー開始)
	protected void beginShape(){
		GMenuPaint.setUndo();
		PaintTool.setPattern(GUI.key[14]>0);
		//HyperCardの図形ツールはOptionを押して描き始めると、
		//前景色ではなく現在のパターンで輪郭を描く。
		patternedStroke = GUI.key[12]>0;
		PaintTool.beginPreview(PaintTool.alpha/100.0F);
		previewing = true;
		lastClipRect = null;
		lastMultiplePoint = null;
	}

	//Draw Multiple中、前回残した位置からmultiSpace画素未満しか動いていなければ
	//今回は図形を残さない(trueを返す)。それ以外の場合(Draw Multipleでない、
	//対象外のツール、十分動いた)は残す位置を更新してfalse。
	protected boolean skipForMultiSpace(int x, int y){
		if(!(PaintTool.drawMultiple && supportsMultiple())) return false;
		int space = Math.max(1, PaintTool.multiSpace);
		if(lastMultiplePoint!=null &&
				Math.max(Math.abs(x-lastMultiplePoint.x), Math.abs(y-lastMultiplePoint.y))<space){
			return true;
		}
		lastMultiplePoint = new Point(x, y);
		return false;
	}

	//前回の図形を消し、clipRectの範囲へ描くためのGraphics2Dを返す
	protected Graphics2D beginDraw(Rectangle clipRect){
		if(!previewing){
			PaintTool.beginPreview(PaintTool.alpha/100.0F);
			previewing = true;
		}
		BufferedImage buf = PaintTool.previewBuf();
		if(!(PaintTool.drawMultiple && supportsMultiple())){
			PaintTool.clearImage(buf, dirtyRect(clipRect));
		}
		Graphics2D g2 = buf.createGraphics();
		if(PaintTool.antialias){
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		}else{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		}
		return g2;
	}

	//描画を終え、変更した範囲(前回と今回の図形の範囲)だけ再描画する
	protected void endDraw(Graphics2D g2, Rectangle clipRect){
		g2.dispose();
		PaintTool.repaintOwnerRect(dirtyRect(clipRect));
		lastClipRect = clipRect;
	}

	//前回と今回の図形の範囲(対称描画では写した先も含む)
	private Rectangle dirtyRect(Rectangle clipRect){
		Rectangle r = (lastClipRect==null || lastClipRect.isEmpty()) ? clipRect : clipRect.union(lastClipRect);
		return PaintTool.symmetricBounds(r);
	}

	//図形を(塗りがあれば塗ってから)輪郭線で描く。対称描画では対称な位置にも
	//同じ図形を写して描く(線幅・パターン・グラデーションの向きは変えない)。
	//図形ツールはg2の設定(線種)を済ませてからこれを呼ぶ。
	protected void drawShape(Graphics2D g2, Shape shape, boolean fill){
		for(AffineTransform t : PaintTool.symmetryTransforms()){
			Shape s = t.isIdentity() ? shape : PaintTool.pixelTransform(t).createTransformedShape(shape);
			if(fill) fillShape(g2, s);
			g2.setPaint(strokePaint());
			g2.draw(s);
		}
	}

	//プレビューをサーフェースへ確定する
	protected void commitShape(){
		if(previewing){
			PaintTool.commitPreview(PaintTool.alpha/100.0F);
			previewing = false;
		}
		lastClipRect = null;
		PaintTool.repaintOwner();
	}

	//プレビューを破棄する
	protected void cancelShape(){
		if(previewing){
			PaintTool.endPreview();
			previewing = false;
			PaintTool.repaintOwner();
		}
		lastClipRect = null;
	}

	protected static TexturePaint patternPaint(){
		Rectangle2D.Double r = new Rectangle2D.Double(0, 0, PaintTool.pat.getWidth(), PaintTool.pat.getHeight());
		return new TexturePaint(PaintTool.pat, r);
	}

	protected Paint strokePaint(){
		return patternedStroke ? patternPaint() : PaintTool.owner.fore.color;
	}

	//図形の中を「塗り」で埋める。塗りは色/パターン(PaintTool.pat。パターン12=
	//黒なら前景色のベタ塗り)か、グラデーションボタンが入っていればグラデーション
	//(バケツ・選択範囲のFillと同じPaintBucketTool.gradfill)。グラデーションは
	//図形の形を一度別のバッファへ描いてから、その不透明な部分だけを塗って重ねる
	//ので、図形ごとにその図形の大きさいっぱいのグラデーションになる。
	protected static void fillShape(Graphics2D g2, Shape shape){
		GradButton grad = PaintTool.owner.grad;
		if(grad==null || !grad.use){
			g2.setPaint(patternPaint());
			g2.fill(shape);
			return;
		}
		//バッファの外へはみ出した分は描かれないので、その範囲に限る(巨大な確保を避ける)
		BufferedImage target = PaintTool.previewBuf();
		Rectangle b = shape.getBounds().intersection(new Rectangle(-1, -1, target.getWidth()+2, target.getHeight()+2));
		if(b.isEmpty()) return;
		BufferedImage buf = new BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D bg = buf.createGraphics();
		bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
		bg.translate(-b.x, -b.y);
		bg.setColor(Color.BLACK);
		bg.fill(shape);
		bg.dispose();
		PaintBucketTool.gradfill(buf, grad, b.x, b.y);
		g2.drawImage(buf, b.x, b.y, null);
	}

	//Draw Multipleは、ドラッグ中の各形状を消さずに重ねて残す。
	//HyperCardで対象だった囲み図形だけがoverrideする。
	protected boolean supportsMultiple(){
		return false;
	}

	//矩形系ツールで共通のドラッグ領域。Draw Centeredでは開始点を中心にする。
	protected static Rectangle dragRectangle(Point anchor, int x, int y, boolean square){
		int dx=x-anchor.x, dy=y-anchor.y;
		if(square){
			int size=Math.max(Math.abs(dx),Math.abs(dy));
			dx=(dx<0) ? -size : size;
			dy=(dy<0) ? -size : size;
		}
		if(PaintTool.drawCentered){
			return new Rectangle(anchor.x-Math.abs(dx), anchor.y-Math.abs(dy),
				Math.abs(dx)*2, Math.abs(dy)*2);
		}
		return new Rectangle(Math.min(anchor.x,anchor.x+dx), Math.min(anchor.y,anchor.y+dy),
			Math.abs(dx), Math.abs(dy));
	}

	//幅・高さが負の矩形を正規化し、線幅分の余白を付ける
	protected static Rectangle clipOf(Rectangle rect, int margin){
		Rectangle clipRect = (Rectangle) rect.clone();
		if(clipRect.width<0){
			clipRect.width *= -1;
			clipRect.x -= clipRect.width;
		}
		if(clipRect.height<0){
			clipRect.height *= -1;
			clipRect.y -= clipRect.height;
		}
		int lineSize = (int)PaintTool.lineSize;
		clipRect.x -= lineSize+margin;
		clipRect.y -= lineSize+margin;
		clipRect.width += 2*(lineSize+margin)+1;
		clipRect.height += 2*(lineSize+margin)+1;
		return clipRect;
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		return false;
	}

	@Override
	public void clear() {
		cancelShape();
	}

	@Override
	public void end() {
		cancelShape();
	}
}


//-------------------
//矩形
//-------------------
class RectTool extends ShapeToolBase{
	private Rectangle srcRect;

	@Override
	public String getName() {
		return "Rect";
	}

	private void strokeDraw(Rectangle rect){
		float lineSize = PaintTool.lineSize;
		Rectangle clipRect = clipOf(rect, 2);

		Graphics2D g2 = beginDraw(clipRect);
		g2.setStroke(new BasicStroke(lineSize, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
		//塗り(パターン/グラデーション)と矩形
		drawShape(g2, rect, PaintTool.owner.fill);
		endDraw(g2, clipRect);
	}

	private Rectangle setSelection(int x, int y){
		Rectangle r = dragRectangle(new Point(srcRect.x,srcRect.y), x, y, GUI.key[11]>1);

		strokeDraw(r);

		return r;
	}

	@Override
	protected boolean supportsMultiple(){ return true; }

	@Override
	public void mouseUp(int x, int y) {
		if(srcRect==null) return;
		srcRect = setSelection(x,y);
		commitShape();
	}

	@Override
	public void mouseDown(int x, int y) {
		beginShape();
		//新しい選択領域を作る
		srcRect = new Rectangle(x,y,0,0);
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(srcRect==null) return false;
		if(x==srcRect.x+srcRect.width&&y==srcRect.y+srcRect.height){
			return false;
		}
		if(skipForMultiSpace(x,y)) return false;
		setSelection(x,y);
		return true;
	}
}


//-------------------
//角丸矩形
//-------------------
//HyperCardの角丸矩形は、角の大きさを変更するオプションを持たない。
//ここでも固定の16px相当にし、それ以外の操作はRectToolと同じにする。
class RoundRectTool extends ShapeToolBase{
	static final float ARC = 16.0f;
	private Rectangle srcRect;

	@Override
	public String getName() {
		return "Round Rect";
	}

	private void strokeDraw(Rectangle rect){
		Rectangle clipRect = clipOf(rect, 2);
		RoundRectangle2D.Float shape = new RoundRectangle2D.Float(
			rect.x, rect.y, rect.width, rect.height, ARC, ARC);

		Graphics2D g2 = beginDraw(clipRect);
		g2.setStroke(new BasicStroke(PaintTool.lineSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawShape(g2, shape, PaintTool.owner.fill);
		endDraw(g2, clipRect);
	}

	private Rectangle setSelection(int x, int y){
		Rectangle r = dragRectangle(new Point(srcRect.x,srcRect.y), x, y, GUI.key[11]>1);

		strokeDraw(r);
		return r;
	}

	@Override
	protected boolean supportsMultiple(){ return true; }

	@Override
	public void mouseDown(int x, int y){
		beginShape();
		srcRect = new Rectangle(x,y,0,0);
	}

	@Override
	public boolean mouseStillDown(int x, int y){
		if(srcRect==null) return false;
		if(x==srcRect.x+srcRect.width && y==srcRect.y+srcRect.height) return false;
		if(skipForMultiSpace(x,y)) return false;
		setSelection(x,y);
		return true;
	}

	@Override
	public void mouseUp(int x, int y){
		if(srcRect==null) return;
		srcRect = setSelection(x,y);
		commitShape();
	}
}


//-------------------
//楕円
//-------------------
class OvalTool extends ShapeToolBase{
	private Rectangle srcRect;

	@Override
	protected void drawShape(Graphics2D g2, Shape shape, boolean fill){
		if(!fill || PaintTool.antialias){
			super.drawShape(g2, shape, fill);
			return;
		}
		//塗りとdraw()の輪郭は別々に画素へ丸められ、輪郭の内側に透明な
		//画素が残ることがある。輪郭を含む面を塗り、同じ座標の輪郭面を
		//重ねることで、細長い楕円でも内部と輪郭の間に隙間を作らない。
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		for(AffineTransform t : PaintTool.symmetryTransforms()){
			Shape s = t.isIdentity() ? shape : PaintTool.pixelTransform(t).createTransformedShape(shape);
			Shape outline = g2.getStroke().createStrokedShape(s);
			Area filled = new Area(s);
			filled.add(new Area(outline));
			fillShape(g2, filled);
			g2.setPaint(strokePaint());
			g2.fill(outline);
		}
	}

	@Override
	public String getName() {
		return "Oval";
	}

	private void strokeDraw(Rectangle rect){
		float lineSize = PaintTool.lineSize;
		Rectangle clipRect = clipOf(rect, 1);

		Graphics2D g2 = beginDraw(clipRect);
		//塗り(パターン/グラデーション)と楕円
		g2.setStroke(new BasicStroke(lineSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawShape(g2, new Ellipse2D.Float(rect.x, rect.y, rect.width, rect.height), PaintTool.owner.fill);
		endDraw(g2, clipRect);
	}

	private Rectangle setSelection(int x, int y){
		Rectangle r = dragRectangle(new Point(srcRect.x,srcRect.y), x, y, GUI.key[11]>1);

		strokeDraw(r);

		return r;
	}

	@Override
	protected boolean supportsMultiple(){ return true; }

	@Override
	public void mouseUp(int x, int y) {
		if(srcRect==null) return;
		srcRect = setSelection(x,y);
		commitShape();
	}

	@Override
	public void mouseDown(int x, int y) {
		beginShape();
		srcRect = new Rectangle(x,y,0,0);
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(srcRect==null) return false;
		if(x==srcRect.x+srcRect.width&&y==srcRect.y+srcRect.height){
			return false;
		}
		if(skipForMultiSpace(x,y)) return false;
		setSelection(x,y);
		return true;
	}
}


//-------------------
//曲線(フリーハンド)
//-------------------
//HyperCardのCurveは「太さを持つ鉛筆」に近い。ドラッグ中の軌跡を
//曲線として描き、Draw Filledのときは終点と始点を閉じて塗りつぶす。
class CurveTool extends ShapeToolBase{
	private final ArrayList<Point> points = new ArrayList<Point>();

	@Override
	public String getName(){
		return "Curve";
	}

	@Override
	protected boolean supportsMultiple(){ return true; }

	private Path2D.Float path(boolean close){
		Path2D.Float p = new Path2D.Float();
		if(points.isEmpty()) return p;
		p.moveTo(points.get(0).x, points.get(0).y);
		for(int i=1; i<points.size(); i++){
			Point pt = points.get(i);
			p.lineTo(pt.x, pt.y);
		}
		if(close) p.closePath();
		return p;
	}

	private Rectangle bounds(){
		if(points.isEmpty()) return new Rectangle();
		Rectangle r = new Rectangle(points.get(0).x, points.get(0).y, 1, 1);
		for(int i=1; i<points.size(); i++) r.add(points.get(i));
		return r;
	}

	private void draw(){
		if(points.isEmpty()) return;
		Rectangle clipRect = clipOf(bounds(), 2);
		Graphics2D g2 = beginDraw(clipRect);
		g2.setStroke(new BasicStroke(PaintTool.lineSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		Path2D.Float p = path(PaintTool.owner.fill);
		drawShape(g2, p, PaintTool.owner.fill);
		endDraw(g2, clipRect);
	}

	@Override
	public void mouseDown(int x, int y){
		beginShape();
		points.clear();
		points.add(new Point(x,y));
		draw();
	}

	@Override
	public boolean mouseStillDown(int x, int y){
		if(points.isEmpty()) return false;
		Point last = points.get(points.size()-1);
		if(last.x==x && last.y==y) return false;
		points.add(new Point(x,y));
		draw();
		return true;
	}

	@Override
	public void mouseUp(int x, int y){
		if(points.isEmpty()) return;
		Point last = points.get(points.size()-1);
		if(last.x!=x || last.y!=y) points.add(new Point(x,y));
		draw();
		commitShape();
		points.clear();
	}

	@Override
	public void clear(){
		points.clear();
		super.clear();
	}

	@Override
	public void end(){
		points.clear();
		super.end();
	}
}


//-------------------
//正多角形
//-------------------
class RegPolyTool extends ShapeToolBase{
	private Point center;

	@Override
	public String getName(){
		return "Reg Poly";
	}

	@Override
	protected boolean supportsMultiple(){ return true; }

	//中心と、ドラッグ中の座標から正N角形を作る。角度はドラッグした方向で決まり、
	//Shiftを押していれば15度刻みに向きを制限する。
	private Polygon buildPolygon(int cx, int cy, int dragX, int dragY){
		int sides = Math.max(3, PaintTool.regPolySides);
		double dx = dragX-cx, dy = dragY-cy;
		double radius = Math.sqrt(dx*dx+dy*dy);
		double angle = Math.atan2(dy, dx);
		if(GUI.key[11]>1){
			double step=Math.toRadians(15);
			angle=Math.round(angle/step)*step;
		}
		int[] xs = new int[sides];
		int[] ys = new int[sides];
		for(int i=0; i<sides; i++){
			double a = angle + i*2*Math.PI/sides;
			xs[i] = cx + (int)Math.round(Math.cos(a)*radius);
			ys[i] = cy + (int)Math.round(Math.sin(a)*radius);
		}
		return new Polygon(xs, ys, sides);
	}

	private void strokeDraw(Polygon poly){
		float lineSize = PaintTool.lineSize;
		Rectangle clipRect = clipOf(poly.getBounds(), 2);

		Graphics2D g2 = beginDraw(clipRect);
		g2.setStroke(new BasicStroke(lineSize, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
		drawShape(g2, poly, PaintTool.owner.fill);
		endDraw(g2, clipRect);
	}

	@Override
	public void mouseDown(int x, int y) {
		beginShape();
		center = new Point(x, y);
		strokeDraw(buildPolygon(x, y, x, y));
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(center==null) return false;
		if(skipForMultiSpace(x,y)) return false;
		strokeDraw(buildPolygon(center.x, center.y, x, y));
		return true;
	}

	@Override
	public void mouseUp(int x, int y) {
		if(center==null) return;
		strokeDraw(buildPolygon(center.x, center.y, x, y));
		center = null;
		commitShape();
	}
}


//-------------------
//多角形(フリーハンド、頂点をクリックで指定)
//-------------------
class PolyTool extends ShapeToolBase{
	private ArrayList<Point> points = new ArrayList<Point>();
	private int previewX, previewY;
	private long lastDownTime = 0;
	private int lastDownX = Integer.MIN_VALUE, lastDownY = Integer.MIN_VALUE;

	@Override
	public String getName(){
		return "Poly";
	}

	private double dist(int x1, int y1, int x2, int y2){
		double dx=x1-x2, dy=y1-y2;
		return Math.sqrt(dx*dx+dy*dy);
	}

	private Point constrainedPoint(int x, int y){
		if(GUI.key[11]>1 && !points.isEmpty()){
			Point last=points.get(points.size()-1);
			return PaintTool.constrainAngle(last.x,last.y,x,y,15);
		}
		return new Point(x,y);
	}

	private Polygon toPolygon(boolean includePreview){
		int n = points.size() + (includePreview?1:0);
		int[] xs = new int[n];
		int[] ys = new int[n];
		for(int i=0; i<points.size(); i++){
			xs[i] = points.get(i).x;
			ys[i] = points.get(i).y;
		}
		if(includePreview){
			xs[n-1] = previewX;
			ys[n-1] = previewY;
		}
		return new Polygon(xs, ys, n);
	}

	//closed=falseなら「まだ確定していない、頂点を線でつないだだけ」のプレビュー、
	//closed=trueなら実際に確定する最終形状(閉じた多角形として塗り/線を描く)。
	private void draw(Polygon poly, boolean closed){
		Rectangle clipRect = clipOf(poly.getBounds(), 2);

		Graphics2D g2 = beginDraw(clipRect);
		g2.setStroke(new BasicStroke(PaintTool.lineSize, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
		if(closed){
			drawShape(g2, poly, PaintTool.owner.fill && poly.npoints>=3);
		}else{
			Path2D.Float open = new Path2D.Float();
			for(int i=0; i<poly.npoints; i++){
				if(i==0) open.moveTo(poly.xpoints[i], poly.ypoints[i]);
				else open.lineTo(poly.xpoints[i], poly.ypoints[i]);
			}
			drawShape(g2, open, false);
		}
		endDraw(g2, clipRect);
	}

	//確定: サーフェースに書き込み、状態をリセットする
	private void finish(){
		if(points.size()>=2){
			draw(toPolygon(false), true);
			commitShape();
		}
		else{
			cancelShape();
		}
		points.clear();
	}

	@Override
	public void mouseDown(int x, int y) {
		if(GUI.key[12]>0) {
			PaintTool.spoit(x, y);
			return;
		}
		long now = System.currentTimeMillis();
		boolean isDoubleClick = !points.isEmpty() && (now-lastDownTime)<400 && dist(x,y,lastDownX,lastDownY)<6;
		boolean nearFirst = points.size()>=2 && dist(x,y,points.get(0).x,points.get(0).y)<6;
		lastDownTime = now; lastDownX = x; lastDownY = y;

		if(points.isEmpty()){
			beginShape();
			points.add(new Point(x,y));
			previewX = x; previewY = y;
			return;
		}
		if(isDoubleClick || nearFirst){
			finish();
			return;
		}
		Point p=constrainedPoint(x,y);
		points.add(p);
		previewX = p.x; previewY = p.y;
		draw(toPolygon(false), false);
	}

	@Override
	public void mouseUp(int x, int y) {
		//頂点はクリック(mouseDown)の時点で確定するので、mouseUpでは何もしない
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		if(points.isEmpty()) return false;
		Point p=constrainedPoint(x,y);
		if(p.x==previewX && p.y==previewY) return false;
		previewX = p.x; previewY = p.y;
		draw(toPolygon(true), false);
		return true;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		return false;
	}

	@Override
	public void clear() {
		points.clear();
		cancelShape();
	}

	@Override
	public void end() {
		points.clear();
		cancelShape();
	}
}


//-------------------
//線分
//-------------------
class LineTool extends ShapeToolBase{
	private Rectangle srcRect;

	@Override
	public String getName() {
		return "Line";
	}

	private void strokeDraw(Rectangle rect){
		float lineSize = PaintTool.lineSize;
		Rectangle clipRect = clipOf(rect, 1);

		Graphics2D g2 = beginDraw(clipRect);
		g2.setStroke(new BasicStroke(lineSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawShape(g2, new Line2D.Float(rect.x, rect.y, rect.x+rect.width, rect.y+rect.height), false);
		endDraw(g2, clipRect);

		PaintTool.lastTime = System.currentTimeMillis();
	}

	private Rectangle setSelection(int x, int y){
		if(srcRect==null) return new Rectangle(x,y,0,0);

		Rectangle r = (Rectangle) srcRect.clone();

		if(GUI.key[11]>1){ //Shiftで15度単位
			Point p=PaintTool.constrainAngle(r.x,r.y,x,y,15);
			x=p.x; y=p.y;
		}

		r.width = x - r.x;
		r.height = y - r.y;

		strokeDraw(r);

		return r;
	}

	@Override
	public void mouseUp(int x, int y) {
		if(srcRect==null) return;
		srcRect = setSelection(x,y);
		commitShape();
	}

	@Override
	public void mouseDown(int x, int y) {
		beginShape();
		srcRect = new Rectangle(x,y,0,0);
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(srcRect==null) return false;
		if(x==srcRect.x+srcRect.width&&y==srcRect.y+srcRect.height){
			return false;
		}
		setSelection(x,y);
		return true;
	}
}


//-------------------
//選択
//-------------------
//矩形選択。ドラッグでラバーバンドを描き、放したときにその範囲を持ち上げて
//浮動選択(PCARDFrame.floating)にする。浮動選択の移動・複製・確定はPaintTool側。
class SelectTool implements toolSelectInterface{
	//srcRect:ドラッグ開始点(x,y,0,0)。dragRect:ドラッグ中のラバーバンド
	Rectangle srcRect;
	Rectangle dragRect;

	@Override
	public String getName() {
		return "Select";
	}

	//ドラッグ中のラバーバンドを破線で描く。浮動選択の枠はPaintTool.paintFloatingOverlay。
	@Override
	public void paintOverlay(Graphics2D g){
		Rectangle rect = dragRect;
		if(rect==null) return;
		rect = PaintTool.toPhysical(rect);
		rect.width--;
		rect.height--;
		if(rect.width<0 || rect.height<0) return;

		Graphics2D g3 = (Graphics2D) g.create();
		g3.setColor(Color.WHITE);
		g3.draw(rect);
		float dash[] = {4.0f, 2.0f};
		int i = (((int)System.currentTimeMillis()/100) & 0xFFFF)%6;
		g3.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, i));
		g3.setColor(Color.BLACK);
		g3.draw(rect);
		g3.dispose();
	}

	private Rectangle setSelection(int x, int y){
		if(srcRect==null) return null;

		Rectangle r = (Rectangle) srcRect.clone();
		if(x<r.x){
			r.width = r.x - x;
			r.x = x;
		}
		else{
			r.width = x - r.x;
		}
		if(y<r.y){
			r.height = r.y - y;
			r.y = y;
		}
		else{
			r.height = y - r.y;
		}

		Rectangle old = dragRect;
		dragRect = r;
		PaintTool.repaintOwnerRectGrown(old==null ? r : r.union(old), 1);

		return r;
	}

	void viewSelection(){
		PaintTool.repaintOwner();
	}

	@Override
	public void mouseUp(int x, int y) {
		if(srcRect==null) return;
		Rectangle r = setSelection(x,y);
		srcRect = null;
		dragRect = null;
		if(r==null || r.width<=0 || r.height<=0){
			//クリックしただけなら選択解除(追加/削除モードなら今の選択はそのまま)
			PaintTool.selectCombine = PaintTool.SELECT_REPLACE;
			PaintTool.repaintOwner();
			return;
		}
		PaintTool.selectRect(r);
	}

	@Override
	public void mouseDown(int x, int y) {
		//新しい選択領域を作る(浮動選択があればPaintTool側で確定済み)
		srcRect = new Rectangle(x,y,0,0);
		dragRect = null;
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		if(dragRect!=null) PaintTool.repaintOwnerRectGrown(dragRect, 1);
		PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		return false;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(srcRect==null) return false;
		if(x==srcRect.x+srcRect.width&&y==srcRect.y+srcRect.height){
			return false;
		}
		setSelection(x,y);
		return true;
	}

	@Override
	public void clear(){
		srcRect = null;
		dragRect = null;
		PaintTool.discardFloating();
		PaintTool.repaintOwner();
	}

	@Override
	public void end(){
		srcRect = null;
		dragRect = null;
		PaintTool.commitFloating();
		PaintTool.repaintOwner();
	}
}


//-------------------
//投げなわ
//-------------------
//ドラッグで多角形を描き、放したときにその形をマスクにして持ち上げる。
//囲んだ余白(画像の縁に接する白)は選択に入れない。
class LassoTool implements toolSelectInterface{
	ArrayList<Point> srcPoints = new ArrayList<Point>();

	@Override
	public String getName() {
		return "Lasso";
	}

	//ドラッグ中の輪郭の範囲
	private Rectangle pointsBounds(){
		if(srcPoints.isEmpty()) return null;
		Rectangle r = null;
		for(Point p: srcPoints){
			if(r==null) r = new Rectangle(p.x, p.y, 1, 1);
			else r.add(new Rectangle(p.x, p.y, 1, 1));
		}
		return r;
	}

	@Override
	public void paintOverlay(Graphics2D g){
		if(srcPoints.size()<2) return;
		Graphics2D g3 = (Graphics2D) g.create();
		PaintTool.applyBitTransform(g3, PaintTool.effectiveBit()/2);
		BasicStroke bs = new BasicStroke(0.1f);
		float dash[] = {4.0f, 2.0f};
		int i = (((int)System.currentTimeMillis()/100) & 0xFFFF)%6;
		BasicStroke bs2 = new BasicStroke(0.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, i);
		for(int j=0; j<srcPoints.size()-1; j++){
			Point p1 = srcPoints.get(j);
			Point p2 = srcPoints.get(j+1);

			g3.setStroke(bs);
			g3.setColor(Color.WHITE);
			g3.drawLine(p1.x, p1.y, p2.x, p2.y);
			g3.setStroke(bs2);
			g3.setColor(Color.BLACK);
			g3.drawLine(p1.x, p1.y, p2.x, p2.y);
		}
		g3.dispose();
	}

	//画像の縁に接している白(0xFFFFFFFF)の画素を、dstで透明にする。
	//投げなわで囲んだ余白部分を選択から外すために使う。
	static void clearWhiteFromEdges(BufferedImage src, BufferedImage dst){
		int width = src.getWidth();
		int height = src.getHeight();
		int[] s = PaintTool.directPixels(src);
		if(s==null) s = src.getRGB(0, 0, width, height, null, 0, width);
		int[] d = PaintTool.directPixels(dst);
		boolean writeBack = false;
		if(d==null){
			d = dst.getRGB(0, 0, width, height, null, 0, width);
			writeBack = true;
		}
		final int white = 0xFFFFFFFF;
		boolean[] visited = new boolean[width*height];
		int[] queue = new int[width*height];
		int head = 0, tail = 0;
		for(int x=0; x<width; x++){
			int i1 = x, i2 = (height-1)*width+x;
			if(s[i1]==white && !visited[i1]){ visited[i1]=true; queue[tail++]=i1; }
			if(s[i2]==white && !visited[i2]){ visited[i2]=true; queue[tail++]=i2; }
		}
		for(int y=0; y<height; y++){
			int i1 = y*width, i2 = y*width+width-1;
			if(s[i1]==white && !visited[i1]){ visited[i1]=true; queue[tail++]=i1; }
			if(s[i2]==white && !visited[i2]){ visited[i2]=true; queue[tail++]=i2; }
		}
		while(head<tail){
			int i = queue[head++];
			d[i] = 0x00FFFFFF;
			int x = i%width;
			int y = i/width;
			if(x>0 && s[i-1]==white && !visited[i-1]){ visited[i-1]=true; queue[tail++]=i-1; }
			if(x<width-1 && s[i+1]==white && !visited[i+1]){ visited[i+1]=true; queue[tail++]=i+1; }
			if(y>0 && s[i-width]==white && !visited[i-width]){ visited[i-width]=true; queue[tail++]=i-width; }
			if(y<height-1 && s[i+width]==white && !visited[i+width]){ visited[i+width]=true; queue[tail++]=i+width; }
		}
		if(writeBack){
			dst.setRGB(0, 0, width, height, d, 0, width);
		}
	}

	//多角形の中でサーフェースに絵のある部分をマスク(サーフェースと同じ大きさ)にする
	private BufferedImage makeMask(){
		int len = srcPoints.size();
		int[] xPoints = new int[len];
		int[] yPoints = new int[len];
		for(int j=0; j<len; j++){
			Point p = srcPoints.get(j);
			xPoints[j] = p.x;
			yPoints[j] = p.y;
		}

		BufferedImage surface = PaintTool.owner.getSurface();
		int width = surface.getWidth();
		int height = surface.getHeight();

		//マスク用バッファを用意する(全体を白にして多角形を黒で描く)
		BufferedImage srcsrcbits = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = srcsrcbits.createGraphics();
		g.setColor(new Color(255,255,255));
		g.fillRect(0,0, width, height);
		g.setColor(Color.BLACK);
		g.fillPolygon(xPoints, yPoints, len);
		g.dispose();

		//その形状に含まれるsurface()をsrcsrcbitsに描画(透明は白扱い)
		int[] msk = PaintTool.directPixels(srcsrcbits);
		int[] img = PaintTool.directPixels(surface);
		if(msk!=null && img!=null){
			int n = width*height;
			for(int i=0; i<n; i++){
				if(0xFFFFFFFF!=msk[i]){
					int v = img[i];
					if((v&0xFF000000)==0) v = 0xFFFFFFFF;
					msk[i] = v;
				}
			}
		}
		else{
			for(int y=0; y<height; y++){
				for(int x=0; x<width; x++){
					if(0xFFFFFFFF!=srcsrcbits.getRGB(x, y)){
						int v = surface.getRGB(x, y);
						if((v&0xFF000000)==0) v = 0xFFFFFFFF;
						srcsrcbits.setRGB(x, y, v);
					}
				}
			}
		}

		BufferedImage srcbits = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D srcg = srcbits.createGraphics();
		srcg.setComposite(AlphaComposite.Src);
		srcg.drawImage(srcsrcbits,0,0,null);
		srcg.dispose();

		//周辺の白色を透明化
		clearWhiteFromEdges(srcsrcbits, srcbits);
		return srcbits;
	}

	private void setSelection(int x, int y){
		if(srcPoints.size()==0 ||
			srcPoints.get(srcPoints.size()-1).x !=x ||
			srcPoints.get(srcPoints.size()-1).y !=y)
		{
			Point last = srcPoints.isEmpty() ? null : srcPoints.get(srcPoints.size()-1);
			srcPoints.add(new Point(x,y));
			//新しく増えた線分の範囲だけ再描画
			Rectangle r = new Rectangle(x, y, 1, 1);
			if(last!=null) r.add(new Rectangle(last.x, last.y, 1, 1));
			PaintTool.repaintOwnerRectGrown(r, 2);
		}
	}

	void viewSelection(){
		PaintTool.repaintOwner();
	}

	@Override
	public void mouseUp(int x, int y) {
		if(srcPoints.size()<3){
			srcPoints = new ArrayList<Point>();
			PaintTool.selectCombine = PaintTool.SELECT_REPLACE;
			PaintTool.repaintOwner();
			return;
		}
		//削除(Option)は囲んだ多角形そのもの、それ以外は囲んだ中の絵のある部分
		BufferedImage mask = PaintTool.selectCombine==PaintTool.SELECT_SUBTRACT ? polygonMask() : makeMask();
		srcPoints = new ArrayList<Point>();
		PaintTool.selectMask(mask);
		PaintTool.repaintOwner();
	}

	//囲んだ多角形の内側をそのままマスクにする(選択範囲からの削除用)
	private BufferedImage polygonMask(){
		int len = srcPoints.size();
		int[] xPoints = new int[len];
		int[] yPoints = new int[len];
		for(int j=0; j<len; j++){
			Point p = srcPoints.get(j);
			xPoints[j] = p.x;
			yPoints[j] = p.y;
		}
		BufferedImage surface = PaintTool.owner.getSurface();
		BufferedImage m = PaintTool.newArgb(surface.getWidth(), surface.getHeight());
		Graphics2D g = m.createGraphics();
		g.setColor(Color.BLACK);
		g.fillPolygon(xPoints, yPoints, len);
		g.dispose();
		return m;
	}

	@Override
	public void mouseDown(int x, int y) {
		//新しい選択領域を作る(浮動選択があればPaintTool側で確定済み)
		srcPoints = new ArrayList<Point>();
		srcPoints.add(new Point(x,y));
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		Rectangle r = pointsBounds();
		if(r!=null) PaintTool.repaintOwnerRectGrown(r, 2);
		PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		return false;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		//選択範囲を広げて行く
		setSelection(x,y);
		return true;
	}

	@Override
	public void clear(){
		srcPoints = new ArrayList<Point>();
		PaintTool.discardFloating();
		PaintTool.repaintOwner();
	}

	@Override
	public void end(){
		srcPoints = new ArrayList<Point>();
		PaintTool.commitFloating();
		PaintTool.repaintOwner();
	}

	//マスク(不透明部分)を囲む矩形
	public static Rectangle makeSelectedRect(BufferedImage in_srcbits){
		MaskOutline o = MaskOutline.of(in_srcbits, null);
		if(o==null) return new Rectangle(0,0,0,0);
		return (Rectangle) o.bounds.clone();
	}
}


//-------------------
//スマート選択
//-------------------
//クリック(ドラッグ)した点と近い色の続く範囲(Optionなら画面全体の類似色)をマスクに
//して持ち上げる。Cmdを押しながら浮動選択の外をクリックすると、直前の選択範囲へ
//追加する。選択範囲があるときのShift/Optionは他の選択ツールと同じく追加/削除
//(PaintTool.selectCombine)。
class SmartSelectTool implements toolSelectInterface{
	ArrayDeque<Point> pointList; //seedFill用
	ArrayList<Point> srcPoints = new ArrayList<Point>();
	BufferedImage srcbits; //作りかけのマスク(サーフェースと同じ大きさ)

	@Override
	public String getName() {
		return "MagicWand";
	}

	@Override
	public void paintOverlay(Graphics2D g){
		if(srcPoints.isEmpty()) return;
		Graphics2D g3 = (Graphics2D) g.create();
		PaintTool.applyBitTransform(g3, PaintTool.effectiveBit()/2);
		//選択中にクリックした点
		g3.setColor(Color.BLACK);
		for(Point p: srcPoints){
			g3.drawLine(p.x, p.y, p.x, p.y);
		}
		g3.dispose();
	}

	private final boolean isNear(int argb, Color color, int near){
		if(Math.abs(color.getRed()-((argb>>16)&0xFF))>near){
			return false;
		}
		if(Math.abs(color.getGreen()-((argb>>8)&0xFF))>near){
			return false;
		}
		if(Math.abs(color.getBlue()-((argb>>0)&0xFF))>near){
			return false;
		}
		if(Math.abs(color.getAlpha()-((argb>>24)&0xFF))>near){
			return false;
		}
		return true;
	}

	private boolean matches(int argb, ArrayList<Color> srcColors, int near){
		for(int i=0; i<srcColors.size(); i++){
			if(isNear(argb, srcColors.get(i), near)) return true;
		}
		return false;
	}

	private void seedfillH(BufferedImage surface, BufferedImage newSurface, int px, int py, ArrayList<Color> srcColors){
		DataBuffer buffer = surface.getRaster().getDataBuffer();
		DataBuffer newBuffer = newSurface.getRaster().getDataBuffer();
		int width = surface.getWidth();
		int height = surface.getHeight();
		int near = PaintTool.smartSelectPercent*256/100;

		//左を調べる
		int lx;
		for(lx=0; px+lx>=0; lx--){
			if(!matches(buffer.getElem(0, px+lx+py*width), srcColors, near)) break;
		}
		lx++;

		//右を調べる
		int rx;
		for(rx=0; px+rx<width; rx++){
			if(!matches(buffer.getElem(0, px+rx+py*width), srcColors, near)) break;
		}
		rx--;

		//そのラインを塗る
		for(int x=px+lx; x<=px+rx; x++){
			int c = buffer.getElem(0, x+py*width);
			if(c==0) c=1;
			newBuffer.setElem(0, x+py*width, c);
		}

		//上下のラインを探す
		for(int dy=-1; dy<=1; dy+=2){
			int ny = py+dy;
			if(ny<0 || ny>=height) continue;
			for(int x=px+lx; x<=px+rx; x++){
				if(!matches(buffer.getElem(0, x+ny*width), srcColors, near)) continue;
				//右端を探す
				boolean isMatch2 = false;
				if(x<px+rx){
					isMatch2 = matches(buffer.getElem(0, (x+1)+ny*width), srcColors, near);
				}
				if(x==px+rx || !isMatch2)
				{
					if(newBuffer.getElem(0, x+ny*width)==0x00000000){
						//未登録なので登録する
						pointList.add(new Point(x,ny));
						int c = buffer.getElem(0, x+ny*width);
						if(c==0) c=1;
						newBuffer.setElem(0, x+ny*width, c);
					}
				}
			}
		}
	}

	//マスクの値: 選んだ画素の色(透明色は1)。アルファが0でない画素が選択範囲。
	private void makesrcbits(){
		//Option: 画面全体の類似色。ただし選択範囲があってその外をOption+クリックした
		//ときは「選択範囲から除く」(PaintTool.selectCombine)なので、隣接した範囲だけ
		boolean allScreen = GUI.key[12]>0 && PaintTool.selectCombine!=PaintTool.SELECT_SUBTRACT;

		//時間がかかるのでカーソルを時計に
		PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

		BufferedImage surface = PaintTool.owner.getSurface();

		//含まれる色を列挙
		ArrayList<Color> srcColors = new ArrayList<Color>();
		int near = PaintTool.smartSelectPercent*256/100;
		if(srcPoints.size()>512){
			near+=3;
		}
		for(int j=0; j<srcPoints.size(); j++){
			Point p = srcPoints.get(j);
			if(p.x<0 || p.y<0 || p.x>=surface.getWidth() || p.y>=surface.getHeight()) continue;
			int d = surface.getRGB(p.x, p.y);
			Color c = new Color((d>>16)&0xFF, (d>>8)&0xFF, (d>>0)&0xFF, (d>>24)&0xFF);
			boolean isFound = false;
			for(int i=srcColors.size()-1; i>=0; i--)
			{
				if(isNear(d, srcColors.get(i), near/2)){
					isFound = true;
					break;
				}
			}
			if(!isFound) srcColors.add(c);
		}

		//塗りつぶし候補リストをリセット
		pointList = new ArrayDeque<Point>();

		if(allScreen && !srcColors.isEmpty()){
			//すべての類似色を検索
			DataBuffer buffer = surface.getRaster().getDataBuffer();
			DataBuffer newBuffer = srcbits.getRaster().getDataBuffer();
			int width1 = surface.getWidth();
			int height1 = surface.getHeight();
			int near1 = PaintTool.smartSelectPercent*256/100;
			int lasti = 0;
			for(int y=0; y<height1; y++){
				for(int x=0; x<width1; x++){
					int c = buffer.getElem(0, x+y*width1);
					boolean isMatch = false;
					if(isNear(c, srcColors.get(lasti), near1)){
						isMatch=true;
					}
					else{
						for(int i=0; i<srcColors.size(); i++){
							if(isNear(c, srcColors.get(i), near1)){
								isMatch=true;
								lasti = i;
								break;
							}
						}
					}
					if(isMatch){
						if(c==0) c=1;
						newBuffer.setElem(0, x+y*width1, c);
					}
				}
			}
		}
		else
		{
			//隣り合った類似色を検索
			for(int j=0; j<srcPoints.size(); j++){
				Point p = srcPoints.get(j);
				if(p.x<0 || p.y<0 || p.x>=surface.getWidth() || p.y>=surface.getHeight()) continue;
				seedfillH(surface, srcbits, p.x, p.y, srcColors);

				while(!pointList.isEmpty()){
					Point q = pointList.poll();
					seedfillH(surface, srcbits, q.x, q.y, srcColors);
				}
			}
		}

		//色リストはもういらないのでクリア
		srcPoints = new ArrayList<Point>();

		//カーソルを戻す
		TBCursor.changeCursor(PaintTool.owner);
	}

	private void addSelection(int x, int y){
		boolean isFound = false;
		//x,yは論理座標なので、物理座標のmainPane.getWidth/getHeight()ではなく
		//論理サイズのサーフェースと比較する。
		if(!PaintTool.inSurface(x, y)){
			return;
		}
		for(int i=0; i<srcPoints.size(); i++)
		{
			if(x == srcPoints.get(i).x && y == srcPoints.get(i).y){
				isFound = true;
			}

		}
		if(!isFound){
			srcPoints.add(new Point(x,y));
			PaintTool.repaintOwnerRectGrown(new Rectangle(x, y, 1, 1), 1);
		}
	}

	void viewSelection(){
		PaintTool.repaintOwner();
	}

	@Override
	public void mouseUp(int x, int y) {
		if(srcbits==null) return;
		makesrcbits();
		BufferedImage mask = srcbits;
		srcbits = null;
		PaintTool.selectMask(mask);
		PaintTool.repaintOwner();
	}

	@Override
	public void mouseDown(int x, int y) {
		BufferedImage surface = PaintTool.owner.getSurface();
		srcPoints = new ArrayList<Point>();
		srcbits = null;
		if(GUI.key[14]>0 && PaintTool.lastCommitted!=null && PaintTool.lastCommittedOwner==PaintTool.owner){
			//cmd: 直前の選択範囲(確定した浮動選択の形)に追加する
			FloatingSelection last = PaintTool.lastCommitted;
			srcbits = last.surfaceMask(surface.getWidth(), surface.getHeight());
		}
		if(srcbits==null){
			//マスク用バッファを用意する
			srcbits = PaintTool.newArgb(surface.getWidth(), surface.getHeight());
		}

		addSelection(x,y);
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		PaintTool.owner.mainPane.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
		return false;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		if(srcbits != null){
			//選択色を広げて行く
			for(int i=0; i<8; i++){
				addSelection((int)((PaintTool.lastx[0]*i+x*(8-i))/8),(int)((PaintTool.lasty[0]*i+y*(8-i))/8));
			}
		}
		return true;
	}

	@Override
	public void clear(){
		srcbits = null;
		srcPoints = new ArrayList<Point>();
		PaintTool.discardFloating();
		PaintTool.repaintOwner();
	}

	@Override
	public void end(){
		srcbits = null;
		srcPoints = new ArrayList<Point>();
		PaintTool.commitFloating();
		PaintTool.repaintOwner();
	}
}


//-------------------
// 文字
//-------------------
class TypeTool implements toolInterface{
	MyTextArea area;
	Rectangle typeRect;
	boolean type = false;
	private TypeToolListener listener;

	@Override
	public String getName() {
		return "Type";
	}

	//入力中の文字に今のフォント・大きさ・スタイル(PCARD.pc.textFont等)、前景色、
	//PaintメニューのAntialiasを反映する。ツールのダブルクリック(フォントダイアログ)、
	//HyperTalkのset the textFont等、前景色ボタン、Antialiasメニューから
	//refreshLive()経由で呼ばれ、入力中に変えたものがその場で見た目に出る
	//(以前は入力を始めた時点の設定で固定だった)。
	void applyStyle(){
		if(area==null || area.fldData==null) return;
		//倍率が整数でないときは最寄りの整数倍で描く(確定時は論理サイズで描き直す)
		int eb = Math.max(1, Math.round(PaintTool.effectiveBit()));
		area.fixedScale = eb;
		area.fldData.textFont = PCARD.pc.textFont;
		area.fldData.textSize = PCARD.pc.textSize; //論理サイズ(独自描画がfixedScale倍する)
		area.fldData.textHeight = PCARD.pc.textSize+PCARD.pc.textSize/3; //HyperCard's line spacing for the size (12 -> 16)
		area.fldData.textStyle = PCARD.pc.textStyle;
		area.setFont(new Font(PCARD.pc.textFont, PCARD.pc.textStyle&0x03, Math.min(512, PCARD.pc.textSize*eb)));
		Color fore = PaintTool.owner.fore!=null ? PaintTool.owner.fore.color : Color.BLACK;
		area.setForeground(fore);
		area.fldData.color = fore;
		//アンチエイリアス: 独自描画(textAntialias)とSwing描画(clientProperty)の両方
		area.textAntialias = PaintTool.antialias;
		area.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
			PaintTool.antialias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		//枠の大きさを文字に合わせ直す
		if(listener!=null) listener.changedUpdate(null);
		area.revalidate();
		area.repaint();
		PaintTool.repaintOwner();
	}

	//入力中のテキストツールがあれば、その見た目を今の設定に合わせる
	static void refreshLive(){
		PCARDFrame owner = PaintTool.owner;
		if(owner==null || !(owner.tool instanceof TypeTool)) return;
		TypeTool t = (TypeTool)owner.tool;
		if(t.area!=null) t.applyStyle();
	}

	@Override
	public void mouseUp(int x, int y) {
		if(type==false && typeRect!=null){
			GMenuPaint.setUndo();

			type = true;

			area = new MyTextArea("");
			area.fldData = new OField(null, 0);
			//フォーカスが無いとき(他のアプリが前面など)のMyTextAreaの独自描画は、
			//カードの拡大率ではなくこのキャンバスの倍率(FatBits×2倍表示)で
			//論理サイズの文字を描く。余白(左3、上1)もその倍率で付くので、
			//フォーカスのあるSwing描画(下のsetBorder)と同じ位置に文字が出る
			int eb = Math.max(1, Math.round(PaintTool.effectiveBit()));
			area.fixedScale = eb;
			//ペイントの文字は折り返さない(確定時の独自描画で幅が足りないと
			//行末の文字が次の行へ送られ、見えないまま失われていた)
			area.fldData.dontWrap = true;
			// MyTextArea.setBounds assumes a card field and applies the global
			// card scale even in the icon editor. Use this canvas's transform.
			Rectangle physical = PaintTool.toPhysical(typeRect);
			area.setBoundsRaw(physical.x, physical.y, physical.width, physical.height);
			area.setOpaque(false);
			//確定時の独自描画はフィールドと同じ余白(左3、上1)から文字を描く。
			//入力中の表示も同じ余白にして、確定で文字が動かないようにする
			area.setBorder(javax.swing.BorderFactory.createEmptyBorder(
				OField.textTopMargin(1, false)*eb, OField.textMargin(1, false)*eb, 0, 0));
			//独自描画は標準スタイルだと背景色で塗りつぶす。入力枠は透明にして
			//(確定時のend()と同じ)、下の絵を白く隠さないようにする
			area.fldData.style = 1;
			listener = new TypeToolListener();
			area.getDocument().addDocumentListener(listener);
			//フォント・大きさ・スタイル・色・アンチエイリアス(入力中に変えても効く)
			applyStyle();

			PaintTool.owner.mainPane.add(area);
			area.requestFocus();

			PaintTool.repaintOwner();
		}
		else{
			mouseStillDown(x,y);
		}
	}

	@Override
	public void mouseDown(int x, int y) {
		if(type == true && typeRect!=null && typeRect.contains(new Point(x,y))){

		}
		else{
			//入力中の文字を実際の領域に描画
			end();

			//新しい入力領域を作る。HyperCard J-2.3 puts the first line's baseline and the pen at the clicked point
			//(Logo 2.1's "text Hello" at the turtle: the letters stand on the point); the area draws its text 3 pixels in
			//and one line's ascent (plus 1) down, so it starts that far up and left
			//(the icon editor keeps its own: the box at the click)
			if(PaintTool.owner instanceof IconEditor) typeRect = new Rectangle(x,y,1,PCARD.pc.textSize+5);
			else{
				java.awt.FontMetrics fm = PaintTool.owner.getFontMetrics(OObject.stackFont(PCARD.pc.textFont, PCARD.pc.textStyle&0x03, PCARD.pc.textSize));
				typeRect = new Rectangle(x-OField.textMargin(1, false), y-OField.textTopMargin(1, false)-fm.getAscent(), 1, PCARD.pc.textSize+5);
			}
			type = false;
		}
	}

	@Override
	public boolean mouseWithin(int x, int y) {
		return false;
	}

	@Override
	public boolean mouseStillDown(int x, int y) {
		return true;
	}

	@Override
	public void clear(){
		type = false;
		typeRect = null;
		if(area!=null) {
			PaintTool.owner.mainPane.remove(area);
			area = null;
			PaintTool.repaintOwner();
		}
	}
	@Override
	public void end(){
		if(type && area!=null && typeRect!=null && !area.getText().isEmpty()){
			//textareaを一時レイヤーに描画してからサーフェースへ合成する
			BufferedImage buf = PaintTool.previewBuf();
			PaintTool.clearImage(buf);
			Graphics2D redog = buf.createGraphics();
			//PaintメニューのAntialiasに従う(以前はSwing側のアンチエイリアスが常に効いていた)
			redog.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				PaintTool.antialias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			redog.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				PaintTool.antialias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
			area.textAntialias = PaintTool.antialias;
			area.setCaretColor(new Color(0,0,0,0));
			//一時レイヤーは論理サイズなので、独自描画も等倍で行う
			area.fixedScale = 1;

			if(PaintTool.effectiveBit()!=1){
				area.fldData.textSize = PCARD.pc.textSize;
				area.setFont(new Font(PCARD.pc.textFont, PCARD.pc.textStyle&0x03, area.fldData.textSize));
				//area.paint(redog)はこの直後に論理サイズ(bit/scaleなし)の一時レイヤーへ
				//直接描き込むため、areaの実Swing座標も論理座標そのものに戻す必要がある。
				//通常のsetBoundsはscale()倍してしまうため、setBoundsRawで直接設定する。
				float eb = PaintTool.effectiveBit();
				Rectangle rect = area.getBounds();
				rect.x = (int)Math.floor(rect.x/eb)+PaintTool.viewLeft();
				rect.y = (int)Math.floor(rect.y/eb)+PaintTool.viewTop();
				area.setBoundsRaw(rect.x, rect.y, rect.width, rect.height);
			}
			area.fldData.width+=2;
			area.fldData.style=1;
			area.fldData.useMyDraw = true;
			area.fldData.color = PaintTool.owner.fore.color;
			//HyperCard J-2.3's paint text is opaque: every line stands on a white box as wide as the line, from the line's
			//top (baseline - ascent) to the next line, 1 pixel either side (typed over a black rectangle; Logo 2.1's "text"
			//covered the end of the line it had just drawn). Not in the icon editor, whose text keeps the icon's transparency
			if(!(PaintTool.owner instanceof IconEditor)){
				Font f = OObject.stackFont(PCARD.pc.textFont, PCARD.pc.textStyle&0x03, PCARD.pc.textSize);
				java.awt.FontMetrics fm = PaintTool.owner.getFontMetrics(f);
				boolean smear = MyTextArea.needsSmear(f, PCARD.pc.textStyle);
				int lineH = Math.max(PCARD.pc.textSize, area.fldData.textHeight);
				int pen = OField.textMargin(1, false), baseline = OField.textTopMargin(1, false)+fm.getAscent();
				redog.setColor(Color.WHITE);
				for(String line : area.getText().split("\\r?\\n|\\r", -1)){
					int w = MyTextArea.runWidth(fm, line, smear);
					if(w>0) redog.fillRect(pen-1, baseline-fm.getAscent(), w+2, lineH);
					baseline += lineH;
				}
			}
			area.paint(redog);
			redog.dispose();

			Graphics2D g = PaintTool.owner.getSurface().createGraphics();
			//透明度
			g.setComposite( AlphaComposite.getInstance(AlphaComposite.SRC_OVER,PaintTool.alpha/100.0F) );
			//サーフェースに反映
			g.drawImage(buf, typeRect.x, typeRect.y, null);
			g.dispose();
			PaintTool.endPreview();
		}
		type = false;
		if(area!=null) {
			PaintTool.owner.mainPane.remove(area);
			area = null;
		}
		PaintTool.repaintOwner();
	}


	class TypeToolListener implements DocumentListener {
		@Override
		public void changedUpdate(DocumentEvent e) {
			if(TypeTool.this.area==null) return;
			FontMetrics fo = TypeTool.this.area.getFontMetrics(TypeTool.this.area.getFont());
			//as wide as the text is drawn: a bold Geneva/Osaka is QuickDraw's smear, 1 pixel more a glyph (and outline/shadow
			//theirs); measured without it, the box cut the end of artGallery's bold 24-point "Hello !" off when committed
			int style = PCARD.pc.textStyle;
			boolean smear = MyTextArea.needsSmear(OObject.stackFont(PCARD.pc.textFont, style&0x03, PCARD.pc.textSize), style);
			int extra = MyTextArea.styleExtra(style);
			int scale = Math.max(1, Math.round(PaintTool.effectiveBit()));
			int width = 0;
			for(String line : TypeTool.this.area.getText().split("\\r?\\n|\\r", -1)){
				int plain = fo.stringWidth(line);
				width = Math.max(width, plain+(MyTextArea.runWidth(fo, line, smear, extra)-plain)*scale);
			}
			//area.getBounds()もfoによる計測もすでに物理(実)座標なので、通常のsetBoundsで
			//さらにscale()倍すると二重スケールになる。setBoundsRawで直接反映する。
			java.awt.Insets in = TypeTool.this.area.getInsets();
			Rectangle r = TypeTool.this.area.getBounds();
			int eb = Math.max(1, Math.round(PaintTool.effectiveBit()));
			r.width = in.left+width+in.right+eb;
			r.height = in.top+TypeTool.this.area.getLineCount()*TypeTool.this.area.getLineHeight()+in.bottom+5;
			area.setBoundsRaw(r.x, r.y, r.width, r.height);
			//fldDataは論理値なので、物理座標のr.width/heightをeffectiveBit()で割る。
			area.fldData.width=r.width/eb+1;
			area.fldData.height=r.height/eb;
		}
		@Override
		public void insertUpdate(DocumentEvent e) {
			changedUpdate(e);
		}
		@Override
		public void removeUpdate(DocumentEvent e) {
			changedUpdate(e);
		}
	}
}


//ペイントツールのidle処理。以前は常駐スレッドから100msごとにツールを呼んでいたが、
//マウスイベント(EDT)と同じバッファ・コンポーネントを別スレッドが同時に触ることになり、
//線の欠落やちらつき、例外の原因になっていた。Swing Timerに置き換え、すべての
//状態変更をEDT上で行う。役割:
//  ・マウスを止めたままでもmouseStillDown/mouseWithinを定期的に呼ぶ(スプレー缶の
//    連続吹き付け、選択枠のアニメーション、カーソル形状の更新)
//  ・ペイントモード中のキーボードショートカット処理(GUI.keyEventCheck)
class PaintIdle {
	private final javax.swing.Timer timer;

	PaintIdle(){
		timer = new javax.swing.Timer(100, e -> tick());
		timer.setRepeats(true);
		timer.setCoalesce(true);
	}

	void start(){
		timer.start();
	}

	void interrupt(){
		timer.stop();
	}

	boolean isAlive(){
		return timer.isRunning();
	}

	private static void tick(){
		PCARDFrame owner = PaintTool.owner;
		if(owner==null || owner.tool==null || owner.mainPane==null || !owner.isVisible()){
			return;
		}
		if(owner.getSurface()==null) return;

		try{
			if(PaintTool.lastTime+100<=System.currentTimeMillis() && owner.bit==1){
				//0.1秒以上待ったら同じ位置でもう一度
				if(PaintTool.mouse) {
					PaintTool.mouseStillDown((int)PaintTool.lastx[0], (int)PaintTool.lasty[0]);
				}
				else {
					PaintTool.mouseWithin((int)PaintTool.lastx[0], (int)PaintTool.lasty[0]);
				}
			}
			else if(!PaintTool.mouse) {
				Point p = pointerInPane(owner);
				if(p!=null){
					PaintTool.mouseWithin(p.x, p.y);
				}
			}
			if(PCARDFrame.pc!=null && PCARDFrame.pc.stack!=null && PCARDFrame.pc.stack.curCard!=null){
				GUI.keyEventCheck();
			}
		}catch(RuntimeException e){
			e.printStackTrace();
		}
	}

	//現在のマウス位置をmainPane上の論理座標(2倍表示モードを戻したもの)で返す。
	//mainPaneが画面に出ていなければnull。
	static Point pointerInPane(PCARDFrame owner){
		PointerInfo pointerInfo = MouseInfo.getPointerInfo();
		if(pointerInfo==null || !owner.mainPane.isShowing()) return null;
		Point p = pointerInfo.getLocation();
		SwingUtilities.convertPointFromScreen(p, owner.mainPane);
		if(owner != PCARD.pc) return p;
		return new Point(PCARD.toLogical(p.x), PCARD.toLogical(p.y));
	}
}


/*class MyTabletListener implements TabletListener
{
	toolTabletInterface tool;
	boolean in_stroke = false;

	@Override
	public void cursorDragged(TabletEvent event) {
		if(event.getPressure() == 0.0) {
			in_stroke = false;
			return;
		}
		boolean eraser = (event.getDevice().getType() == cello.jtablet.TabletDevice.Type.ERASER);

		if(!in_stroke){
			tool.penDown(event.getFloatX(), event.getFloatY(), event.getPressure(), eraser);
			in_stroke = true;
		}
    	tool.penStillDown(event.getFloatX(), event.getFloatY(), event.getPressure(), eraser);
		PaintTool.lastx[1] = PaintTool.lastx[0];
		PaintTool.lasty[1] = PaintTool.lasty[0];
		PaintTool.lastx[0] = event.getFloatX();
		PaintTool.lasty[0] = event.getFloatY();
	}

	@Override
	public void cursorReleased(TabletEvent event) {
		if(in_stroke){
			boolean eraser = (event.getDevice().getType() == cello.jtablet.TabletDevice.Type.ERASER);
			tool.penUp(event.getFloatX(), event.getFloatY(), event.getPressure(), eraser);
			in_stroke = false;
		}
	}
}*/

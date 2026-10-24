import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;

/**
 * フロートウインドウ(メッセージボックス・ツールパレット)共通のタイトルバー。
 * HyperCardのウィンドイド(縞模様の細いバー＋左端のクローズボックス)を、グラデーションのバーと
 * 赤い閉じるボタン(ホバーで×)で現代風にしたもの。ドラッグでウインドウを動かす。
 * ウインドウ側は{@link #applyShape}で角丸に切り抜き、本体を{@link #paintWindowBody}で描く。
 */
class GWindoidBar extends JComponent {
	private static final long serialVersionUID = 1L;

	static final int HEIGHT = 20;
	static final int CORNER = 8;
	static final int MARGIN = 8;
	static final int CLOSE_SIZE = 12;
	static final int CLOSE_X = MARGIN;
	static final int CLOSE_Y = (HEIGHT-CLOSE_SIZE)/2;

	static final Color BORDER = new Color(0x9A,0x9A,0x9A);
	private static final Color BAR_TOP = new Color(0xF6,0xF6,0xF6);
	private static final Color BAR_BOTTOM = new Color(0xD2,0xD2,0xD2);
	private static final Color BAR_LINE = new Color(0xB4,0xB4,0xB4);
	private static final Color STRIPE = new Color(0,0,0,18); //HyperCardの縞模様のなごり
	private static final Color CLOSE_FILL = new Color(0xEE,0x5C,0x52);
	private static final Color CLOSE_RING = new Color(0xC9,0x43,0x3A);
	private static final Color CLOSE_MARK = new Color(0x5A,0x12,0x0E);

	private final Window window;
	private final Runnable onClose;
	/** バーに出す名前(PLTEのWDEF 2054などタイトル付きのパレット)。nullなら縞だけ */
	private String title;
	/** 閉じるボタンを出すか(WDEF 2064のムーバブルモーダル風パレットには無い) */
	private boolean closeBox = true;
	/** 左端に縦に置くバー(WDEF 1993)。座標は横バーと同じ向きで扱い、描画と当たり判定で回す */
	private boolean vertical;
	private boolean closeHover, closePressed;
	private Point dragStart; //バーをつかんだ位置(バー内座標)

	GWindoidBar(Window window, Runnable onClose){
		this.window = window;
		this.onClose = onClose;
		setOpaque(false);
		Mouse mouse = new Mouse();
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	GWindoidBar setTitle(String title){ this.title = title; repaint(); return this; }
	GWindoidBar setCloseBox(boolean closeBox){ this.closeBox = closeBox; repaint(); return this; }
	GWindoidBar setVertical(boolean vertical){ this.vertical = vertical; repaint(); return this; }
	boolean isVertical(){ return vertical; }

	/** 縦バーのときはコンポーネント座標を横バー座標(長さ方向x、厚み方向y)に直す */
	private Point barPoint(Point p){
		return vertical ? new Point(p.y, HEIGHT-p.x) : p;
	}

	/** ウインドウを角丸に切り抜く(サイズが変わるたびに呼ぶ) */
	static void applyShape(Window w){
		w.setShape(new RoundRectangle2D.Float(0,0,w.getWidth(),w.getHeight(),CORNER,CORNER));
	}

	/** ウインドウ本体: 角丸の塗りと枠。バーの下に見える部分を描く(バーは自分の範囲を上書きする) */
	static void paintWindowBody(Graphics2D g, int w, int h, Color body){
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f,0.5f,w-1,h-1,CORNER,CORNER);
		g.setColor(body);
		g.fill(shape);
		g.setColor(BORDER);
		g.setStroke(new BasicStroke(1f));
		g.draw(shape);
	}

	@Override
	protected void paintComponent(Graphics g0){
		Graphics2D g = (Graphics2D)g0.create();
		try{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			if(vertical){
				//横バーとして描いたものを左辺に立てる(閉じるボタンが上、縞が下へ続く)
				g.rotate(Math.PI/2);
				g.translate(0,-HEIGHT);
				w = getHeight(); h = HEIGHT;
			}
			//上の角だけ丸い形(下辺はバーの外に出す)
			RoundRectangle2D top = new RoundRectangle2D.Float(0.5f,0.5f,w-1,h+CORNER,CORNER,CORNER);
			Shape clip = g.getClip();
			g.clip(top);
			g.setPaint(new GradientPaint(0,0,BAR_TOP,0,h,BAR_BOTTOM));
			g.fillRect(0,0,w,h);
			g.setColor(STRIPE);
			int stripeLeft = (closeBox ? CLOSE_X+CLOSE_SIZE : 0)+MARGIN, stripeRight = w-MARGIN;
			int titleLeft = 0, titleRight = 0;
			if(title!=null && !title.isEmpty()){
				g.setFont(getFont().deriveFont(java.awt.Font.BOLD, 11f));
				java.awt.FontMetrics fm = g.getFontMetrics();
				int tw = Math.min(fm.stringWidth(title), stripeRight-stripeLeft);
				titleLeft = (stripeLeft+stripeRight)/2-tw/2; titleRight = titleLeft+tw;
			}
			for(int y=4; y<h-3; y+=2){
				if(titleRight>titleLeft){
					//名前の左右に縞を残す
					g.drawLine(stripeLeft, y, titleLeft-6, y);
					g.drawLine(titleRight+6, y, stripeRight, y);
				}
				else g.drawLine(stripeLeft, y, stripeRight, y);
			}
			if(titleRight>titleLeft){
				g.setColor(new Color(0x40,0x40,0x40));
				java.awt.FontMetrics fm = g.getFontMetrics();
				Shape textClip = g.getClip();
				g.clipRect(titleLeft, 0, titleRight-titleLeft, h);
				g.drawString(title, titleLeft, (h+fm.getAscent()-fm.getDescent())/2);
				g.setClip(textClip);
			}
			g.setColor(BAR_LINE);
			g.drawLine(0,h-1,w,h-1);
			g.setClip(clip);
			//閉じるボタン
			if(closeBox){
			Ellipse2D dot = new Ellipse2D.Float(CLOSE_X,CLOSE_Y,CLOSE_SIZE,CLOSE_SIZE);
			Color fill = closePressed ? CLOSE_RING : CLOSE_FILL;
			g.setPaint(new GradientPaint(CLOSE_X,CLOSE_Y,fill.brighter(),CLOSE_X,CLOSE_Y+CLOSE_SIZE,fill));
			g.fill(dot);
			g.setColor(CLOSE_RING);
			g.setStroke(new BasicStroke(1f));
			g.draw(dot);
			if(closeHover || closePressed){
				g.setColor(CLOSE_MARK);
				g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				int cx = CLOSE_X+CLOSE_SIZE/2, cy = CLOSE_Y+CLOSE_SIZE/2, r = 3;
				g.drawLine(cx-r,cy-r,cx+r,cy+r);
				g.drawLine(cx+r,cy-r,cx-r,cy+r);
			}
			}
			//枠(バーにかかる部分)
			g.setColor(BORDER);
			g.setStroke(new BasicStroke(1f));
			g.draw(top);
		}finally{
			g.dispose();
		}
	}

	private boolean inCloseBox(Point p0){
		if(!closeBox) return false;
		Point p = barPoint(p0);
		return p.x>=CLOSE_X-2 && p.x<CLOSE_X+CLOSE_SIZE+2 && p.y>=CLOSE_Y-2 && p.y<CLOSE_Y+CLOSE_SIZE+2;
	}

	private class Mouse extends MouseAdapter {
		@Override public void mousePressed(MouseEvent e) {
			if(inCloseBox(e.getPoint())){ closePressed = true; repaint(); }
			else dragStart = e.getPoint();
		}
		@Override public void mouseReleased(MouseEvent e) {
			if(closePressed){
				closePressed = false; repaint();
				if(inCloseBox(e.getPoint()) && onClose!=null) onClose.run();
			}
			dragStart = null;
		}
		@Override public void mouseDragged(MouseEvent e) {
			if(closePressed){ boolean in = inCloseBox(e.getPoint()); if(in!=closeHover){ closeHover = in; repaint(); } }
			else if(dragStart!=null){
				Point s = e.getLocationOnScreen();
				window.setLocation(s.x-dragStart.x, s.y-dragStart.y);
			}
		}
		@Override public void mouseMoved(MouseEvent e) {
			boolean in = inCloseBox(e.getPoint());
			if(in!=closeHover){ closeHover = in; repaint(); }
		}
		@Override public void mouseExited(MouseEvent e) {
			if(closeHover){ closeHover = false; repaint(); }
		}
	}
}

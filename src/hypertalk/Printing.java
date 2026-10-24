import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.util.ArrayList;
import javax.swing.SwingUtilities;

/**
 * HyperTalk の print 系コマンドの印刷ジョブ。
 * open printing … close printing の間の print はページとして溜め、close printing で1つのジョブとして印刷する。
 * open printing 無しの print は単独のジョブになる。
 * ページはカードの画像(print card / print N cards)か、テキスト(print field / print <式>)。
 * カードは印刷解像度の設定(PCARD.printDpi: 72〜288dpi)に合わせて論理座標の pixelsPerPoint() 倍で描き、紙には 72dpi の大きさで置く。
 */
class Printing {
	/** 1ページ分 */
	static abstract class Page {
		abstract void paint(Graphics2D g, PageFormat pf);
	}

	/** カードの絵。image は論理(72dpi)座標の pixelsPerPoint 倍の大きさで描かれていて、紙には 1/pixelsPerPoint に縮めて置く */
	static class ImagePage extends Page {
		final BufferedImage image; final int pixelsPerPoint;
		ImagePage(BufferedImage image){ this(image, 1); }
		ImagePage(BufferedImage image, int pixelsPerPoint){ this.image = image; this.pixelsPerPoint = Math.max(1, pixelsPerPoint); }
		/** 紙の上の大きさ(ポイント) */
		double pointWidth(){ return (double)image.getWidth()/pixelsPerPoint; }
		double pointHeight(){ return (double)image.getHeight()/pixelsPerPoint; }
		void paint(Graphics2D g, PageFormat pf){
			double w = pf.getImageableWidth(), h = pf.getImageableHeight();
			double pw = pointWidth(), ph = pointHeight();
			double scale = Math.min(1.0, Math.min(w/pw, h/ph));
			g.translate(pf.getImageableX(), pf.getImageableY());
			g.scale(scale/pixelsPerPoint, scale/pixelsPerPoint);
			//高解像度の絵を縮めて置くときは補間して滑らかに(等倍はそのまま)
			if(pixelsPerPoint>1 || scale<1.0) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.drawImage(image, 0, 0, null);
		}
	}

	static class TextPage extends Page {
		final String text; final Font font; final int lineHeight; final int align; final int[] margins;
		TextPage(String text, Font font, int lineHeight, int align, int[] margins){
			this.text = text; this.font = font; this.lineHeight = lineHeight; this.align = align; this.margins = margins;
		}
		void paint(Graphics2D g, PageFormat pf){
			g.setFont(font);
			g.setColor(Color.black);
			double left = pf.getImageableX()+margins[0], top = pf.getImageableY()+margins[1];
			double width = pf.getImageableWidth()-margins[0]-margins[2];
			FontMetrics fm = g.getFontMetrics();
			double y = top + fm.getAscent();
			for(String line : text.split("\n", -1)){
				for(String piece : wrap(line, fm, width)){
					double x = left;
					int pw = fm.stringWidth(piece);
					if(align==1) x = left + (width-pw)/2; else if(align==2) x = left + width - pw;
					g.drawString(piece, (float)x, (float)y);
					y += lineHeight;
				}
			}
		}
		static ArrayList<String> wrap(String line, FontMetrics fm, double width){
			ArrayList<String> out = new ArrayList<String>();
			StringBuilder cur = new StringBuilder();
			for(String word : line.split(" ", -1)){
				String trial = cur.length()==0 ? word : cur+" "+word;
				if(fm.stringWidth(trial)>width && cur.length()>0){ out.add(cur.toString()); cur = new StringBuilder(word); }
				else { cur.setLength(0); cur.append(trial); }
			}
			out.add(cur.toString());
			return out;
		}
	}

	static boolean open = false;          //open printing 中
	static boolean withDialog = false;    //open printing with dialog
	static final ArrayList<Page> pages = new ArrayList<Page>();
	/** テストなどで実際のプリンタへ送らないときは、ここに最後のジョブのページを残すだけにする */
	static boolean dryRun = false;
	static ArrayList<Page> lastJob = new ArrayList<Page>();

	static void add(Page page){
		pages.add(page); //open printing 中でなければ呼び出し側が flush する
	}

	/** 溜めたページを1つのジョブとして印刷する。失敗/キャンセルなら理由を返す(成功は空) */
	static String flush(){
		ArrayList<Page> job = new ArrayList<Page>(pages);
		pages.clear();
		lastJob = job;
		if(job.isEmpty()) return "";
		if(dryRun || GraphicsEnvironment.isHeadless()) return "";
		try{
			PrinterJob pj = PrinterJob.getPrinterJob();
			pj.setPrintable(new Printable(){
				public int print(Graphics g, PageFormat pf, int index){
					if(index>=job.size()) return NO_SUCH_PAGE;
					job.get(index).paint((Graphics2D)g, pf);
					return PAGE_EXISTS;
				}
			});
			if(withDialog && !pj.printDialog()) return "Cancel";
			pj.print();
			return "";
		}catch(PrinterException e){
			return "Couldn't print: "+e.getMessage();
		}
	}

	/** 印刷解像度(dpi)を、カード1ポイントあたりの画素数にする(72dpi = 1)。2倍表示中は絵の画素が割り切れるよう偶数にそろえる */
	static int pixelsPerPoint(){
		int k = Math.max(1, Math.round(PCARD.printDpi/72f));
		//(拡大率が整数でない(最大125%など)ときは絵が補間されるので、そろえない)
		double scale = PCARD.scale();
		int screen = (int)scale;
		if(screen>1 && screen==scale && k%screen!=0) k += screen - k%screen;
		return k;
	}

	/** 表示中のカードの画像(rect が非nullならその部分)。印刷解像度(PCARD.printDpi)に合わせ、論理座標の pixelsPerPoint() 倍で描く */
	static BufferedImage captureCurrentCard(Rectangle rect){
		return captureCurrentCard(rect, pixelsPerPoint());
	}
	static BufferedImage captureCurrentCard(Rectangle rect, int pixelsPerPoint){
		if(PCARD.pc==null || PCARD.pc.mainPane==null) return null;
		final BufferedImage[] holder = new BufferedImage[1];
		final double screen = PCARD.scale();
		final int k = Math.max(1, pixelsPerPoint);
		Runnable r = () -> {
			int w = PCARD.pc.mainPane.getWidth(), h = PCARD.pc.mainPane.getHeight(); //画面の画素(論理座標の screen 倍)
			if(w<=0 || h<=0) return;
			//mainPane の描画に k/screen 倍の変換をかけて描く。文字や枠は高解像度で描かれ、ビットマップは整数倍に拡大される
			int ow = (int)Math.round(w*k/screen), oh = (int)Math.round(h*k/screen);
			BufferedImage img = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setColor(Color.white); g.fillRect(0, 0, ow, oh);
			g.scale(k/screen, k/screen);
			boolean saveCapturing = PCARD.capturingEffect;
			PCARD.capturingEffect = true;
			try{ PCARD.pc.mainPane.printAll(g); }
			finally{ PCARD.capturingEffect = saveCapturing; g.dispose(); }
			holder[0] = img;
		};
		if(SwingUtilities.isEventDispatchThread()) r.run();
		else{ try{ SwingUtilities.invokeAndWait(r); }catch(Exception e){ return null; } }
		BufferedImage img = holder[0];
		if(img!=null && rect!=null){
			Rectangle rr = new Rectangle(rect.x*k, rect.y*k, rect.width*k, rect.height*k).intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
			if(rr.isEmpty()) return null;
			img = img.getSubimage(rr.x, rr.y, rr.width, rr.height);
		}
		return img;
	}
}

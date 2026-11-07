import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

//浮動選択(Floating Selection)。
//
//矩形選択・投げなわ・スマート選択・ペーストで持ち上げた絵は、サーフェースから
//切り離されてこのオブジェクトになり、確定(commit)するまでサーフェースの上に
//浮いている。浮いている間は移動・複製(Option+ドラッグ)・反転・回転・変形・
//Invert/Darken/Lighten・塗りつぶし・Opaque/Transparent などがこの絵に対して効き、
//選択の外をクリックした瞬間にサーフェースへ描き込まれる。
//
//PCARDFrame.floating がウインドウごとの浮動選択で、どの選択ツールで作ったかに
//関係なく、移動・複製・確定は PaintTool 側で共通に扱う。ツール側は「どの範囲を
//持ち上げるか」(矩形・多角形・近似色)を決めるだけ。
//
//画像は選択範囲ぴったりの大きさ(width×height)のARGBで、maskがnullなら矩形、
//そうでなければ同じ大きさのマスク(アルファが0でない画素が選択範囲)。マスクの外の
//画素は常に透明にしておく(各操作の最後にapplyMask()で保証する)。
//
//Undo履歴(PaintUndo)はサーフェースの差分と一緒にこのオブジェクトの複製を持つので、
//浮いている間の各操作もそれぞれ1回のUndo単位になる。
class FloatingSelection {
	BufferedImage image;  //選択範囲の絵(ARGB、範囲ぴったりの大きさ)
	BufferedImage mask;   //null=矩形。そうでなければimageと同じ大きさで、アルファ!=0が選択範囲
	int x, y;             //サーフェース上の現在位置
	int originX, originY; //持ち上げた位置(getSelectedRect用。移動量 = (x-originX, y-originY))
	//持ち上げただけで何もしていない。履歴上は次の操作と1つにまとめる
	//(「切り取って浮かせる」だけでは絵が変わらないので、Undoが空振りに見えないように)
	boolean pristine;
	//マスクの縁が0〜255の被覆率(Featherの結果)。絵のアルファには被覆率が掛かって
	//いて、確定(stamp)は普通のSrcOverでなく被覆率の足し算(下に残した分と足して
	//元の絵に戻る)で合成する。
	boolean soft;
	private MaskOutline outline;

	FloatingSelection(BufferedImage image, BufferedImage mask, int x, int y){
		this.image = toArgb(image);
		this.mask = mask;
		this.x = x;
		this.y = y;
		this.originX = x;
		this.originY = y;
	}

	int width(){ return image.getWidth(); }
	int height(){ return image.getHeight(); }
	Rectangle bounds(){ return new Rectangle(x, y, width(), height()); }
	Rectangle originBounds(){ return new Rectangle(originX, originY, width(), height()); }
	boolean isRectangular(){ return mask==null; }

	/** A picture copied from a selection in the shape of what it holds is that shape again when pasted: Fill and Invert stay inside
	 *  it, and a drag from anywhere in its box moves it (HC J-2.3: エンボス加工 drags it from the white between two bars) */
	void keepShape(){
		if(mask!=null) return;
		int[] ip = pixels(image);
		for(int i=0; i<width()*height(); i++){
			if((ip[i]>>>24)==0){ mask = alphaMask(image); outline = null; pastedShape = true; return; }
		}
	}
	private boolean pastedShape;

	static BufferedImage toArgb(BufferedImage img){
		if(img.getType()==BufferedImage.TYPE_INT_ARGB && PaintTool.directPixels(img)!=null) return img;
		BufferedImage out = PaintTool.newArgb(img.getWidth(), img.getHeight());
		Graphics2D g = out.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return out;
	}

	private static int[] pixels(BufferedImage img){
		int[] px = PaintTool.directPixels(img);
		if(px!=null) return px;
		throw new IllegalStateException("floating selection buffers must be direct ARGB");
	}

	//-------------------
	// 持ち上げ
	//-------------------

	//矩形をサーフェースから持ち上げる。持ち上げた跡は透明(バックグラウンドは白)になる。
	static FloatingSelection lift(BufferedImage surface, Rectangle rect, boolean background){
		if(surface==null || rect==null || rect.width<=0 || rect.height<=0) return null;
		BufferedImage img = PaintTool.newArgb(rect.width, rect.height);
		Graphics2D g = img.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(surface, 0, 0, rect.width, rect.height,
			rect.x, rect.y, rect.x+rect.width, rect.y+rect.height, null);
		g.dispose();
		eraseRect(surface, rect, background);
		FloatingSelection f = new FloatingSelection(img, null, rect.x, rect.y);
		f.pristine = true;
		return f;
	}

	//サーフェースと同じ大きさのマスク(アルファ!=0が選択範囲)で持ち上げる。
	//範囲が空ならnull。マスクのアルファが255未満の画素(Featherの縁)は被覆率
	//として扱い、絵にはアルファ×被覆率を、サーフェースには残り(カードは
	//アルファ×(1-被覆率)、バックグラウンドは白へ被覆率ぶん寄せた色)を残す。
	static FloatingSelection lift(BufferedImage surface, BufferedImage surfaceMask, boolean background){
		if(surface==null || surfaceMask==null) return null;
		Rectangle b = LassoTool.makeSelectedRect(surfaceMask);
		if(b.width<=0 || b.height<=0) return null;
		BufferedImage img = PaintTool.newArgb(b.width, b.height);
		BufferedImage msk = PaintTool.newArgb(b.width, b.height);
		int[] sur = PaintTool.directPixels(surface);
		int[] smask = PaintTool.directPixels(surfaceMask);
		int[] ip = pixels(img);
		int[] mp = pixels(msk);
		int sw = surface.getWidth(), sh = surface.getHeight();
		int erased = background ? 0xFFFFFFFF : 0x00FFFFFF;
		boolean soft = false;
		for(int ly=0; ly<b.height; ly++){
			int sy = b.y+ly;
			for(int lx=0; lx<b.width; lx++){
				int sx = b.x+lx;
				if(sx<0 || sy<0 || sx>=sw || sy>=sh) continue;
				int m = smask!=null ? smask[sx+sy*surfaceMask.getWidth()] : surfaceMask.getRGB(sx, sy);
				int cov = (m>>>24)&0xFF;
				if(cov==0) continue;
				int src = sur!=null ? sur[sx+sy*sw] : surface.getRGB(sx, sy);
				int lifted, left;
				if(cov==255){
					mp[lx+ly*b.width] = 0xFF000000;
					lifted = src;
					left = erased;
				}
				else{
					soft = true;
					mp[lx+ly*b.width] = cov<<24;
					int a = (src>>>24)&0xFF;
					lifted = (((a*cov+127)/255)<<24) | (src&0x00FFFFFF);
					if(background){
						//不透明なレイヤー: 白へ被覆率ぶん寄せる
						int r = (src>>16)&0xFF, g = (src>>8)&0xFF, bl = src&0xFF;
						r += ((255-r)*cov+127)/255; g += ((255-g)*cov+127)/255; bl += ((255-bl)*cov+127)/255;
						left = (src&0xFF000000) | (r<<16) | (g<<8) | bl;
					}
					else{
						left = (((a*(255-cov)+127)/255)<<24) | (src&0x00FFFFFF);
					}
				}
				ip[lx+ly*b.width] = lifted;
				if(sur!=null) sur[sx+sy*sw] = left; else surface.setRGB(sx, sy, left);
			}
		}
		FloatingSelection f = new FloatingSelection(img, msk, b.x, b.y);
		f.pristine = true;
		f.soft = soft;
		return f;
	}

	//ペーストやドロップなど、サーフェース外から来た絵を位置(x,y)に浮かせる。
	static FloatingSelection of(Image src, int x, int y){
		int w = src.getWidth(null), h = src.getHeight(null);
		if(w<=0 || h<=0) return null;
		BufferedImage img = PaintTool.newArgb(w, h);
		Graphics2D g = img.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return new FloatingSelection(img, null, x, y);
	}

	static void eraseRect(BufferedImage surface, Rectangle rect, boolean background){
		Graphics2D g = surface.createGraphics();
		if(background){
			g.setColor(java.awt.Color.WHITE);
		}
		else{
			g.setComposite(AlphaComposite.Clear);
		}
		g.fillRect(rect.x, rect.y, rect.width, rect.height);
		g.dispose();
	}

	//-------------------
	// 問い合わせ
	//-------------------

	//サーフェース座標(sx,sy)が選択範囲の上か(マスクがあればその内側)
	boolean contains(int sx, int sy){
		int lx = sx-x, ly = sy-y;
		if(lx<0 || ly<0 || lx>=width() || ly>=height()) return false;
		if(mask==null || pastedShape) return true;
		return (mask.getRGB(lx, ly)&0xFF000000)!=0;
	}

	//ローカル座標(lx,ly)が選択範囲の内側か
	boolean inside(int lx, int ly){
		if(lx<0 || ly<0 || lx>=width() || ly>=height()) return false;
		return mask==null || (mask.getRGB(lx, ly)&0xFF000000)!=0;
	}

	MaskOutline outline(){
		if(mask==null) return null;
		outline = MaskOutline.of(mask, outline);
		return outline;
	}

	//マスクを、サーフェースと同じ大きさの画像上の現在位置へ描いたもの
	//(スマート選択の範囲追加や、選択範囲の逆転で使う)
	BufferedImage surfaceMask(int surfaceWidth, int surfaceHeight){
		BufferedImage out = PaintTool.newArgb(surfaceWidth, surfaceHeight);
		Graphics2D g = out.createGraphics();
		if(mask==null){
			g.setColor(java.awt.Color.BLACK);
			g.fillRect(x, y, width(), height());
		}
		else{
			g.drawImage(mask, x, y, null);
		}
		g.dispose();
		return out;
	}

	FloatingSelection copy(){
		FloatingSelection f = new FloatingSelection(PaintUndo.copyInto(null, image),
			mask==null ? null : PaintUndo.copyInto(null, mask), x, y);
		f.originX = originX;
		f.originY = originY;
		f.pristine = pristine;
		f.soft = soft;
		return f;
	}

	//同じ位置・同じ絵・同じマスクか(履歴の差分検出用)
	boolean sameAs(FloatingSelection o){
		if(o==null) return false;
		if(x!=o.x || y!=o.y || width()!=o.width() || height()!=o.height()) return false;
		if((mask==null)!=(o.mask==null)) return false;
		if(!samePixels(image, o.image)) return false;
		return mask==null || samePixels(mask, o.mask);
	}

	static boolean samePixels(BufferedImage a, BufferedImage b){
		if(a==b) return true;
		if(a==null || b==null || a.getWidth()!=b.getWidth() || a.getHeight()!=b.getHeight()) return false;
		int[] pa = PaintTool.directPixels(a);
		int[] pb = PaintTool.directPixels(b);
		int n = a.getWidth()*a.getHeight();
		if(pa!=null && pb!=null){
			for(int i=0; i<n; i++) if(pa[i]!=pb[i]) return false;
			return true;
		}
		for(int yy=0; yy<a.getHeight(); yy++)
			for(int xx=0; xx<a.getWidth(); xx++) if(a.getRGB(xx,yy)!=b.getRGB(xx,yy)) return false;
		return true;
	}

	long bytes(){
		return 4L*width()*height()*(mask==null ? 1 : 2);
	}

	//-------------------
	// 移動・確定
	//-------------------

	void moveBy(int dx, int dy){
		if(dx==0 && dy==0) return;
		x += dx;
		y += dy;
		pristine = false;
	}

	//現在位置へ絵を描き込む(ブレンドモード適用)。複製(Option+ドラッグ)と確定の両方で使う。
	void stamp(PCARDFrame owner){
		BufferedImage surface = owner.getSurface();
		if(surface==null) return;
		BufferedImage blended = MyPanel.makeBlendImage(image);
		if(soft){
			coverageAdd(surface, blended, x, y);
			return;
		}
		Graphics2D g = surface.createGraphics();
		g.drawImage(blended, x, y, null);
		g.dispose();
	}

	//被覆率の足し算で合成する。src(アルファ=被覆率×元のアルファ)とdst(残した分)の
	//アルファの和が255以下ならプリマルチプライドの色を足す(元の絵がそのまま戻る)。
	//超えるなら普通のSrcOver。
	static void coverageAdd(BufferedImage dst, BufferedImage src, int ox, int oy){
		int[] d = PaintTool.directPixels(dst);
		int[] s = PaintTool.directPixels(src);
		int dw = dst.getWidth(), dh = dst.getHeight();
		int sw = src.getWidth(), sh = src.getHeight();
		for(int ly=0; ly<sh; ly++){
			int y = oy+ly;
			if(y<0 || y>=dh) continue;
			for(int lx=0; lx<sw; lx++){
				int x = ox+lx;
				if(x<0 || x>=dw) continue;
				int sv = s!=null ? s[lx+ly*sw] : src.getRGB(lx, ly);
				int sa = (sv>>>24)&0xFF;
				if(sa==0) continue;
				int i = x+y*dw;
				int dv = d!=null ? d[i] : dst.getRGB(x, y);
				int da = (dv>>>24)&0xFF;
				int out;
				if(da==0 || sa==255){
					out = sv;
				}
				else if(sa+da<=255){
					int a = sa+da;
					int r = (((sv>>16)&0xFF)*sa+((dv>>16)&0xFF)*da)/a;
					int g = (((sv>>8)&0xFF)*sa+((dv>>8)&0xFF)*da)/a;
					int b = ((sv&0xFF)*sa+(dv&0xFF)*da)/a;
					out = (a<<24)|(r<<16)|(g<<8)|b;
				}
				else{
					//SrcOver
					int a = sa+da*(255-sa)/255;
					int r = (((sv>>16)&0xFF)*sa+((dv>>16)&0xFF)*da*(255-sa)/255)/Math.max(1,a);
					int g = (((sv>>8)&0xFF)*sa+((dv>>8)&0xFF)*da*(255-sa)/255)/Math.max(1,a);
					int b = ((sv&0xFF)*sa+(dv&0xFF)*da*(255-sa)/255)/Math.max(1,a);
					out = (a<<24)|(r<<16)|(g<<8)|b;
				}
				if(d!=null) d[i] = out; else dst.setRGB(x, y, out);
			}
		}
	}

	//持ち上げた絵をそのままサーフェースへ戻す(履歴なし。選択範囲の形を変えて
	//持ち上げ直すときに使う)。動かしていない(pristine)前提で、マスクの内側の
	//画素を元の値で上書きする(ぼかした縁は被覆率の足し算)。
	void putBack(BufferedImage surface){
		if(surface==null) return;
		if(soft){
			coverageAdd(surface, image, x, y);
			return;
		}
		int[] sur = PaintTool.directPixels(surface);
		int[] ip = pixels(image);
		int[] mp = mask==null ? null : pixels(mask);
		int sw = surface.getWidth(), sh = surface.getHeight();
		int w = width(), h = height();
		for(int ly=0; ly<h; ly++){
			int sy = y+ly;
			if(sy<0 || sy>=sh) continue;
			for(int lx=0; lx<w; lx++){
				int sx = x+lx;
				if(sx<0 || sx>=sw) continue;
				int i = lx+ly*w;
				if(mp!=null && (mp[i]&0xFF000000)==0) continue;
				if(sur!=null) sur[sx+sy*sw] = ip[i]; else surface.setRGB(sx, sy, ip[i]);
			}
		}
	}

	//-------------------
	// 選択範囲への操作。どれも画像(とマスク)をその場で書き換える。
	//-------------------

	//マスクの外を透明にする(不変条件の維持)
	void applyMask(){
		if(mask==null) return;
		int[] ip = pixels(image);
		int[] mp = pixels(mask);
		int n = width()*height();
		for(int i=0; i<n; i++) if((mp[i]&0xFF000000)==0) ip[i] = 0;
	}

	private void touched(){
		pristine = false;
		outline = null;
		//ブレンド結果のキャッシュは画像の同一性で判定しているので、その場で書き換えたら捨てる
		MyPanel.invalidateBlendCache();
	}

	private static BufferedImage transformed(BufferedImage src, AffineTransform af, int w, int h){
		return transformed(src, af, w, h, false);
	}

	//smoothなら補間(Antialiasメニューがオンのとき)、そうでなければ最近傍で変換する
	private static BufferedImage transformed(BufferedImage src, AffineTransform af, int w, int h, boolean smooth){
		BufferedImage out = PaintTool.newArgb(w, h);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			smooth ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
		g.transform(af);
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}

	//任意のアフィン変換(ハンドルのドラッグによる拡大縮小・回転、Rotate/Slant/
	//Distortメニュー)。afはローカル座標(左上=0,0)での変換。結果は変換後の外接
	//矩形ぴったりの大きさになり、その左上が来る位置へ動く(持ち上げ位置も同じだけ
	//動かす)。矩形選択を回転・傾斜させると外接矩形の隅に空きができるので、そこは
	//選択範囲に入らないようマスク付きになる(投げなわで囲んだのと同じ扱い)。
	void transform(AffineTransform af, boolean smooth){
		int w = width(), h = height();
		java.awt.geom.Rectangle2D b = af.createTransformedShape(new Rectangle(0, 0, w, h)).getBounds2D();
		int nx = (int)Math.floor(b.getX()), ny = (int)Math.floor(b.getY());
		int nw = Math.max(1, (int)Math.ceil(b.getMaxX())-nx);
		int nh = Math.max(1, (int)Math.ceil(b.getMaxY())-ny);
		AffineTransform local = AffineTransform.getTranslateInstance(-nx, -ny);
		local.concatenate(af);
		boolean axisAligned = af.getShearX()==0 && af.getShearY()==0;
		if(mask==null && !axisAligned){
			BufferedImage solid = PaintTool.newArgb(w, h);
			java.util.Arrays.fill(pixels(solid), 0xFF000000);
			mask = alphaMask(transformed(solid, local, nw, nh, false));
		}
		else if(mask!=null){
			mask = alphaMask(transformed(mask, local, nw, nh, false));
		}
		image = transformed(image, local, nw, nh, smooth);
		x += nx; y += ny;
		originX += nx; originY += ny;
		applyMask();
		touched();
	}

	//他の浮動選択の状態(絵・マスク・位置)をこの浮動選択へ戻す。ハンドルのドラッグ中、
	//毎回変形前の複製から変形し直すために使う(画像は共有するが、transform()は
	//新しい画像を作るので複製側は変わらない)。
	void restoreFrom(FloatingSelection src){
		image = src.image;
		mask = src.mask;
		x = src.x; y = src.y;
		originX = src.originX; originY = src.originY;
		pristine = src.pristine;
		touched();
	}

	void flipHorizontal(){
		AffineTransform af = new AffineTransform(-1, 0, 0, 1, width(), 0);
		image = transformed(image, af, width(), height());
		if(mask!=null) mask = transformed(mask, af, width(), height());
		touched();
	}

	void flipVertical(){
		AffineTransform af = new AffineTransform(1, 0, 0, -1, 0, height());
		image = transformed(image, af, width(), height());
		if(mask!=null) mask = transformed(mask, af, width(), height());
		touched();
	}

	//90度回転。中心が動かないよう位置を補正する(持ち上げ位置も同じだけ動かす)。
	void rotate(boolean left){
		int w = width(), h = height();
		AffineTransform af = left
			? new AffineTransform(0, -1, 1, 0, 0, w)   // (x,y) -> (y, w-1-x)
			: new AffineTransform(0, 1, -1, 0, h, 0);  // (x,y) -> (h-1-y, x)
		image = transformed(image, af, h, w);
		if(mask!=null) mask = transformed(mask, af, h, w);
		int dx = (w-h)/2;
		x += dx; y -= dx;
		originX += dx; originY -= dx;
		touched();
	}

	//画素ごとの色操作(選択範囲の内側だけ)
	private interface PixelOp { int apply(int argb); }

	private void eachInside(PixelOp op){
		int[] ip = pixels(image);
		int[] mp = mask==null ? null : pixels(mask);
		int n = width()*height();
		for(int i=0; i<n; i++){
			if(mp!=null && (mp[i]&0xFF000000)==0) continue;
			ip[i] = op.apply(ip[i]);
		}
		touched();
	}

	void invert(){
		eachInside(PaintTool::invertedPixel);
	}

	private static int clamp(int v){ return v<0 ? 0 : (v>255 ? 255 : v); }

	void darken(){ shade(-20); }
	void lighten(){ shade(20); }

	//AppleWorksのTint: 選択範囲の色を前景色へ少し寄せる(PaintTool.tintPixel、
	//Darken/Lightenと同じく繰り返すほど効く)。透明な画素はそのまま。
	void tint(Color color){
		eachInside(c -> PaintTool.tintPixel(c, color));
	}

	//AppleWorksのBlend: 隣り合う画素の色を混ぜ合わせてなだらかにする
	//(Blurより一様な3×3の平均。繰り返すほど溶ける)。
	void blend(){ convolve(BLEND); }
	private void shade(int delta){
		eachInside(c -> (c&0xFF000000) |
			(clamp(((c>>16)&0xFF)+delta)<<16) | (clamp(((c>>8)&0xFF)+delta)<<8) | clamp((c&0xFF)+delta));
	}

	//選択範囲を現在のパターン(と、使うならグラデーション)で塗りつぶす
	void fill(BufferedImage pattern, GradButton grad){
		int pw = pattern.getWidth(), ph = pattern.getHeight();
		int[] pp = PaintTool.directPixels(pattern);
		int[] ip = pixels(image);
		int[] mp = mask==null ? null : pixels(mask);
		int w = width(), h = height();
		for(int ly=0; ly<h; ly++){
			for(int lx=0; lx<w; lx++){
				int i = lx+ly*w;
				if(mp!=null && (mp[i]&0xFF000000)==0) continue;
				ip[i] = pp!=null ? pp[lx%pw + (ly%ph)*pw] : pattern.getRGB(lx%pw, ly%ph);
			}
		}
		if(grad!=null && grad.use){
			PaintBucketTool.gradfill(image, grad, x, y);
		}
		touched();
	}

	//白を透明にする
	void transparent(){
		eachInside(c -> ((c&0xFF000000)!=0 && (c&0x00FFFFFF)==0x00FFFFFF) ? 0x00FFFFFF : c);
	}

	//透明を白にする
	void opaque(){
		eachInside(c -> (c&0xFF000000)==0 ? 0xFFFFFFFF : c);
	}

	//現在位置の下にあるサーフェースの絵を選択範囲へ取り込む
	void pickup(BufferedImage surface){
		if(surface==null) return;
		int sw = surface.getWidth(), sh = surface.getHeight();
		int[] sur = PaintTool.directPixels(surface);
		int[] ip = pixels(image);
		int[] mp = mask==null ? null : pixels(mask);
		int w = width(), h = height();
		for(int ly=0; ly<h; ly++){
			int sy = y+ly;
			for(int lx=0; lx<w; lx++){
				int i = lx+ly*w;
				if(mp!=null && (mp[i]&0xFF000000)==0) continue;
				int sx = x+lx;
				if(sx<0 || sy<0 || sx>=sw || sy>=sh) continue;
				ip[i] = sur!=null ? sur[sx+sy*sw] : surface.getRGB(sx, sy);
			}
		}
		touched();
	}

	//選択範囲を1画素ぶん広げる(矩形は四方へ、マスクは4近傍の膨張)
	void expand(){
		int w = width(), h = height();
		BufferedImage img = PaintTool.newArgb(w+2, h+2);
		Graphics2D g = img.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(image, 1, 1, null);
		g.dispose();
		if(mask!=null){
			BufferedImage msk = PaintTool.newArgb(w+2, h+2);
			int[] src = pixels(mask);
			int[] dst = pixels(msk);
			int nw = w+2;
			for(int ly=0; ly<h; ly++){
				for(int lx=0; lx<w; lx++){
					if((src[lx+ly*w]&0xFF000000)==0) continue;
					int c = lx+1+(ly+1)*nw;
					dst[c] = 0xFF000000;
					dst[c-1] = 0xFF000000;
					dst[c+1] = 0xFF000000;
					dst[c-nw] = 0xFF000000;
					dst[c+nw] = 0xFF000000;
				}
			}
			mask = msk;
		}
		image = img;
		x--; y--;
		originX--; originY--;
		touched();
	}

	//端の処理をさせるため、画像の周囲を2画素ぶん引き延ばした画像を作る
	private BufferedImage padded(){
		int w = width(), h = height();
		BufferedImage big = PaintTool.newArgb(w+4, h+4);
		Graphics2D g = big.createGraphics();
		g.drawImage(image, 0,2,2,h+2, 0,0,2,h, null);
		g.drawImage(image, w+2,2,w+4,h+2, w-2,0,w,h, null);
		g.drawImage(image, 2,0,w+2,2, 0,0,w,2, null);
		g.drawImage(image, 2,h+2,w+2,h+4, 0,h-2,w,h, null);
		g.drawImage(image, 2,2, null);
		g.dispose();
		return big;
	}

	private void convolve(float[] operator){
		BufferedImage big = padded();
		ConvolveOp op = new ConvolveOp(new Kernel(5,5,operator), ConvolveOp.EDGE_NO_OP, null);
		BufferedImage bigOut = op.filter(big, null);
		BufferedImage out = PaintTool.newArgb(width(), height());
		Graphics2D g = out.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(bigOut, -2, -2, null);
		g.dispose();
		image = out;
		applyMask();
		touched();
	}

	static final float[] BLUR = {
		0.00f, 0.01f, 0.02f, 0.01f, 0.00f,
		0.01f, 0.03f, 0.08f, 0.03f, 0.01f,
		0.02f, 0.08f, 0.40f, 0.08f, 0.02f,
		0.01f, 0.03f, 0.08f, 0.03f, 0.01f,
		0.00f, 0.01f, 0.02f, 0.01f, 0.00f,
	};
	static final float[] SHARPEN = {
		-0.00f, -0.01f, -0.02f, -0.01f, -0.00f,
		-0.01f, -0.03f, -0.08f, -0.03f, -0.01f,
		-0.02f, -0.08f,  1.60f, -0.08f, -0.02f,
		-0.01f, -0.03f, -0.08f, -0.03f, -0.01f,
		-0.00f, -0.01f, -0.02f, -0.01f, -0.00f,
	};

	//Blend用。3×3の単純平均(端の2画素分は0)
	static final float[] BLEND = {
		0f, 0f,    0f,    0f,    0f,
		0f, 1/9f,  1/9f,  1/9f,  0f,
		0f, 1/9f,  1/9f,  1/9f,  0f,
		0f, 1/9f,  1/9f,  1/9f,  0f,
		0f, 0f,    0f,    0f,    0f,
	};

	void blur(){ convolve(BLUR); }
	void sharpen(){ convolve(SHARPEN); }

	//絵を丸ごと差し替える(フィルタ・変形・拡大縮小の結果)。大きさが同じなら今の
	//マスクをそのまま使い(外にはみ出た画素は消す)、大きさが変わったら中心が動かない
	//ように位置を補正して、マスクは差し替えた絵のアルファから作り直す
	//(矩形選択のままなら矩形のまま)。
	void replaceImage(BufferedImage newImage){
		if(newImage==null) return;
		int ow = width(), oh = height();
		BufferedImage img = toArgb(newImage);
		if(img==newImage) img = PaintUndo.copyInto(null, newImage);
		int nw = img.getWidth(), nh = img.getHeight();
		image = img;
		if(nw!=ow || nh!=oh){
			int dx = (ow-nw)/2, dy = (oh-nh)/2;
			x += dx; y += dy;
			originX += dx; originY += dy;
			if(mask!=null) mask = alphaMask(img);
		}
		else{
			applyMask();
		}
		touched();
	}

	//絵の不透明な画素をマスクにする
	static BufferedImage alphaMask(BufferedImage img){
		BufferedImage m = PaintTool.newArgb(img.getWidth(), img.getHeight());
		int[] src = PaintTool.directPixels(img);
		int[] dst = pixels(m);
		int n = img.getWidth()*img.getHeight();
		for(int i=0; i<n; i++){
			int c = src!=null ? src[i] : img.getRGB(i%img.getWidth(), i/img.getWidth());
			if((c&0xFF000000)!=0) dst[i] = 0xFF000000;
		}
		return m;
	}

	//指定の大きさへ拡大縮小する(位置はそのまま)
	void scaleTo(int newWidth, int newHeight){
		if(newWidth<=0 || newHeight<=0) return;
		image = scaled(image, newWidth, newHeight);
		if(mask!=null){
			BufferedImage m = scaled(mask, newWidth, newHeight);
			mask = alphaMask(m);
			applyMask();
		}
		touched();
	}

	private static BufferedImage scaled(BufferedImage src, int w, int h){
		Image img = src.getScaledInstance(w, h, Image.SCALE_SMOOTH);
		BufferedImage out = PaintTool.newArgb(w, h);
		Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	//浮動選択の中身を、範囲外を透明にした新しい画像として返す(クリップボード用)
	BufferedImage cropped(){
		return PaintUndo.copyInto(null, image);
	}

	//-------------------
	// 選択枠の描画
	//-------------------

	//選択枠を「蟻の行進」で描く。gはmainPaneの物理座標系。
	void paintAnts(Graphics2D g){
		if(mask==null){
			Rectangle rect = PaintTool.toPhysical(bounds());
			rect.width--;
			rect.height--;
			if(rect.width<0 || rect.height<0) return;
			Graphics2D g3 = (Graphics2D) g.create();
			g3.setColor(java.awt.Color.WHITE);
			g3.draw(rect);
			float dash[] = {4.0f, 2.0f};
			int i = (((int)System.currentTimeMillis()/100) & 0xFFFF)%6;
			g3.setStroke(new java.awt.BasicStroke(1.0f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10.0f, dash, i));
			g3.setColor(java.awt.Color.BLACK);
			g3.draw(rect);
			g3.dispose();
			return;
		}
		MaskOutline o = outline();
		if(o==null) return;
		Graphics2D g3 = (Graphics2D) g.create();
		PaintTool.applyBitTransform(g3, PaintTool.effectiveBit()/2);
		int i = ((int)(System.currentTimeMillis()/100))%8;
		o.paint(g3, x, y, i, 4, 4);
		g3.dispose();
	}

	//移動後の選択枠の再描画範囲(枠線ぶん外側を含む)
	static Rectangle antsRepaintRect(Rectangle r){
		if(r==null) return null;
		return new Rectangle(r.x-1, r.y-1, r.width+2, r.height+2);
	}

	Point position(){ return new Point(x, y); }
}

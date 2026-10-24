import java.awt.image.BufferedImage;

//選択範囲のマスク(サーフェースと同じ大きさのARGB、アルファ!=0が選択範囲)に
//対する形の操作: 広げる(Grow)・狭める(Shrink)・ぼかす(Feather)と、
//追加(Shift)/削除(Option)の合成。どれも距離変換(各画素から最寄りの選択/非選択
//画素までのユークリッド距離、Felzenszwalb-Huttenlocherの厳密な1次元変換の
//2回掛け、O(画素数))で求めるので、半径が大きくても遅くならない。
//
//結果のマスクは、Grow/Shrinkと合成では0xFF000000/0の2値、Featherでは縁が
//0〜255のアルファ(被覆率)になる(FloatingSelection.liftはこの被覆率で
//画素を分ける)。
class SelectionMask {
	static final float INF = 1e20f;

	static boolean[] selected(BufferedImage mask){
		int w = mask.getWidth(), h = mask.getHeight();
		int[] px = PaintTool.directPixels(mask);
		boolean[] in = new boolean[w*h];
		for(int i=0; i<w*h; i++){
			int v = px!=null ? px[i] : mask.getRGB(i%w, i/w);
			in[i] = (v&0xFF000000)!=0;
		}
		return in;
	}

	static BufferedImage toMask(boolean[] in, int w, int h){
		BufferedImage out = PaintTool.newArgb(w, h);
		int[] px = PaintTool.directPixels(out);
		for(int i=0; i<w*h; i++) if(in[i]) px[i] = 0xFF000000;
		return out;
	}

	//1次元の距離変換(2乗距離)。fは各位置のコスト(0=その位置が対象、INF=対象でない)。
	//結果はout[offset+q*stride]へ。
	private static void dt1d(float[] f, int n, int[] v, float[] z, int stride, int offset, float[] out){
		int k = 0;
		v[0] = 0;
		z[0] = -INF;
		z[1] = INF;
		for(int q=1; q<n; q++){
			float s = ((f[q]+(float)q*q)-(f[v[k]]+(float)v[k]*v[k]))/(2f*q-2f*v[k]);
			while(s<=z[k]){
				k--;
				s = ((f[q]+(float)q*q)-(f[v[k]]+(float)v[k]*v[k]))/(2f*q-2f*v[k]);
			}
			k++;
			v[k] = q;
			z[k] = s;
			z[k+1] = INF;
		}
		k = 0;
		for(int q=0; q<n; q++){
			while(z[k+1]<q) k++;
			float dq = q-v[k];
			out[offset+q*stride] = dq*dq+f[v[k]];
		}
	}

	//各画素から、targetがtrueの最寄りの画素までの距離(targetの画素は0、無ければINF)
	static float[] distanceTo(boolean[] target, int w, int h){
		float[] g = new float[w*h];
		for(int i=0; i<w*h; i++) g[i] = target[i] ? 0f : INF;
		int n = Math.max(w, h);
		float[] f = new float[n], z = new float[n+1];
		int[] v = new int[n];
		float[] tmp = new float[w*h];
		//縦方向
		for(int x=0; x<w; x++){
			for(int y=0; y<h; y++) f[y] = g[x+y*w];
			dt1d(f, h, v, z, w, x, tmp);
		}
		//横方向
		float[] out = new float[w*h];
		for(int y=0; y<h; y++){
			for(int x=0; x<w; x++) f[x] = tmp[x+y*w];
			dt1d(f, w, v, z, 1, y*w, out);
		}
		for(int i=0; i<w*h; i++) out[i] = out[i]>=INF ? INF : (float)Math.sqrt(out[i]);
		return out;
	}

	//選択範囲をradius画素ぶん広げる(円形)
	static BufferedImage grow(BufferedImage mask, float radius){
		int w = mask.getWidth(), h = mask.getHeight();
		boolean[] in = selected(mask);
		float[] dist = distanceTo(in, w, h);
		boolean[] out = new boolean[w*h];
		for(int i=0; i<w*h; i++) out[i] = dist[i]<=radius;
		return toMask(out, w, h);
	}

	//選択範囲をradius画素ぶん狭める(円形)。画像の縁は非選択とみなさない
	//(縁に接した選択範囲が縁から削られない)。
	static BufferedImage shrink(BufferedImage mask, float radius){
		int w = mask.getWidth(), h = mask.getHeight();
		boolean[] in = selected(mask);
		boolean[] notIn = new boolean[w*h];
		for(int i=0; i<w*h; i++) notIn[i] = !in[i];
		float[] dist = distanceTo(notIn, w, h);
		boolean[] out = new boolean[w*h];
		for(int i=0; i<w*h; i++) out[i] = in[i] && dist[i]>radius;
		return toMask(out, w, h);
	}

	//選択範囲の縁をradius画素の幅でぼかす。縁(選択と非選択の境界)からの符号付き
	//距離dを、-radius〜+radiusで0〜1の被覆率(滑らかに)に写す。選択範囲は外側へ
	//radiusぶん広がり、内側radiusぶんが半透明になる。
	static BufferedImage feather(BufferedImage mask, float radius){
		int w = mask.getWidth(), h = mask.getHeight();
		if(radius<=0) return mask;
		boolean[] in = selected(mask);
		boolean[] notIn = new boolean[w*h];
		for(int i=0; i<w*h; i++) notIn[i] = !in[i];
		float[] toIn = distanceTo(in, w, h);
		float[] toOut = distanceTo(notIn, w, h);
		BufferedImage out = PaintTool.newArgb(w, h);
		int[] px = PaintTool.directPixels(out);
		for(int i=0; i<w*h; i++){
			//境界は画素の間にあるので、内側は+0.5、外側は-0.5から数える
			float d = in[i] ? toOut[i]-0.5f : -(toIn[i]-0.5f);
			float c = (d+radius)/(2f*radius);
			if(c<=0f) continue;
			if(c>1f) c = 1f;
			c = c*c*(3f-2f*c);
			int a = Math.round(c*255f);
			if(a<=0) continue;
			px[i] = a<<24;
		}
		return out;
	}

	//base に add を足す(add=true)、または add の範囲を base から除く(add=false)
	static BufferedImage combine(BufferedImage base, BufferedImage other, boolean add){
		int w = base.getWidth(), h = base.getHeight();
		int[] b = PaintTool.directPixels(base);
		int[] o = PaintTool.directPixels(other);
		BufferedImage out = PaintTool.newArgb(w, h);
		int[] px = PaintTool.directPixels(out);
		for(int i=0; i<w*h; i++){
			int bv = b!=null ? b[i] : base.getRGB(i%w, i/w);
			int ov = (other.getWidth()==w && other.getHeight()==h) ? (o!=null ? o[i] : other.getRGB(i%w, i/w)) : 0;
			boolean inB = (bv&0xFF000000)!=0, inO = (ov&0xFF000000)!=0;
			boolean sel = add ? (inB||inO) : (inB&&!inO);
			if(sel) px[i] = 0xFF000000;
		}
		return out;
	}

	//マスクの選択画素数
	static int count(BufferedImage mask){
		int n = 0;
		for(boolean b : selected(mask)) if(b) n++;
		return n;
	}
}

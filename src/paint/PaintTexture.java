import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

//AppleWorksの「テクスチャ」塗り。パターン(8〜64のppat、前景色/背景色で色付け)と
//違って、任意の大きさのフルカラーの絵をそのまま敷き詰めて塗る。塗りの種類
//(GradButton)の一つで、バケツ・図形の塗り・選択範囲のFillがgradfill経由で
//ここへ来る。内蔵テクスチャは起動時に持たず、初めて使うときに乱数で作る
//(端がつながるように周期ノイズで作る)。読み込んだ絵や選択範囲から作った絵は
//"custom"として1つ持つ。スタックには保存しない(セッション限り)。
class PaintTexture {
	static final int TILE = 128;
	static final String CUSTOM = "custom";
	//内蔵テクスチャの名前(メニュー/ポップアップの並び順)
	static final String[] BUILT_IN = {"Wood", "Marble", "Clouds", "Sand", "Granite", "Brick", "Canvas", "Water"};

	//今の塗りに使うテクスチャ。nullなら未選択(the fillTexture = "none")
	static BufferedImage current;
	static String currentName = "none";
	//読み込んだ/選択範囲から作ったテクスチャ
	static BufferedImage custom;

	private static final Map<String, BufferedImage> cache = new LinkedHashMap<>();

	//名前のテクスチャを返す(内蔵名は大文字小文字を区別しない、"custom"は読み込んだ絵)。無ければnull
	static BufferedImage named(String name){
		if(name==null) return null;
		if(name.equalsIgnoreCase(CUSTOM)) return custom;
		for(String n : BUILT_IN){
			if(n.equalsIgnoreCase(name)){
				BufferedImage img = cache.get(n);
				if(img==null){ img = generate(n); cache.put(n, img); }
				return img;
			}
		}
		return null;
	}

	//名前で選ぶ。内蔵名・"custom"・"none"のほか、画像ファイルのパスも受け付ける
	//(読み込んでcustomにする)。選べなければfalse。
	static boolean select(String name){
		if(name==null) return false;
		String v = name.trim();
		if(v.isEmpty() || v.equalsIgnoreCase("none")){
			current = null; currentName = "none";
			return true;
		}
		BufferedImage img = named(v);
		if(img!=null){
			current = img;
			currentName = v.equalsIgnoreCase(CUSTOM) ? CUSTOM : canonical(v);
			return true;
		}
		File f = new File(v);
		if(f.isFile()){
			try{ return setCustom(PaintFile.read(f)); }catch(IOException e){ return false; }
		}
		return false;
	}

	private static String canonical(String name){
		for(String n : BUILT_IN) if(n.equalsIgnoreCase(name)) return n;
		return name;
	}

	//読み込んだ絵をcustomテクスチャにして選ぶ
	static boolean setCustom(BufferedImage img){
		if(img==null) return false;
		BufferedImage argb = PaintTool.newArgb(img.getWidth(), img.getHeight());
		Graphics2D g = argb.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		custom = argb;
		current = argb;
		currentName = CUSTOM;
		return true;
	}

	//何も選ばれていなければ最初の内蔵テクスチャにする(set the gradient to texture)
	static void ensureSelected(){
		if(current==null) select(BUILT_IN[0]);
	}

	//imgの不透明な部分をテクスチャで塗る。テクスチャは(ox,oy)がキャンバスの原点に
	//なるように敷き詰める(図形やバケツはキャンバスに揃い、隣り合う塗りがつながる)。
	//元の画素のアルファは残す(gradfillと同じ)ので、テクスチャのアルファと掛けた値が
	//結果のアルファになる。
	static void fill(BufferedImage img, BufferedImage tex, int ox, int oy){
		if(tex==null) return;
		int[] px = PaintTool.directPixels(img);
		int[] tp = PaintTool.directPixels(tex);
		int w = img.getWidth(), h = img.getHeight();
		int tw = tex.getWidth(), th = tex.getHeight();
		for(int y=0; y<h; y++){
			int ty = Math.floorMod(y+oy, th);
			for(int x=0; x<w; x++){
				int i = x+y*w;
				int src = px!=null ? px[i] : img.getRGB(x, y);
				int sa = (src>>>24)&0xFF;
				if(sa==0) continue;
				int tx = Math.floorMod(x+ox, tw);
				int t = tp!=null ? tp[tx+ty*tw] : tex.getRGB(tx, ty);
				int ta = (t>>>24)&0xFF;
				int out = ((sa*ta/255)<<24) | (t&0x00FFFFFF);
				if(px!=null) px[i] = out; else img.setRGB(x, y, out);
			}
		}
	}

	//-------------------
	// 内蔵テクスチャ
	//-------------------
	//端がつながる周期的な値ノイズ(格子はTILE/cellごと、格子点は周期で折り返す)。
	//octaves段重ねたfBmを0..1で返す。
	private static float[] noise(long seed, int cell, int octaves, float persistence){
		float[] out = new float[TILE*TILE];
		float amp = 1, total = 0;
		int period = Math.max(1, TILE/cell);
		Random rnd = new Random(seed);
		for(int o=0; o<octaves; o++){
			float[] lattice = new float[period*period];
			for(int i=0; i<lattice.length; i++) lattice[i] = rnd.nextFloat();
			float step = TILE/(float)period;
			for(int y=0; y<TILE; y++){
				float fy = y/step; int y0 = (int)fy; float ty = smooth(fy-y0);
				int y1 = (y0+1)%period; y0 %= period;
				for(int x=0; x<TILE; x++){
					float fx = x/step; int x0 = (int)fx; float tx = smooth(fx-x0);
					int x1 = (x0+1)%period; x0 %= period;
					float a = lattice[x0+y0*period], b = lattice[x1+y0*period];
					float c = lattice[x0+y1*period], d = lattice[x1+y1*period];
					float v = (a+(b-a)*tx)*(1-ty) + (c+(d-c)*tx)*ty;
					out[x+y*TILE] += v*amp;
				}
			}
			total += amp;
			amp *= persistence;
			period *= 2;
		}
		for(int i=0; i<out.length; i++) out[i] /= total;
		return out;
	}

	private static float smooth(float t){ return t*t*(3-2*t); }

	private static int rgb(float r, float g, float b){
		return 0xFF000000 | (clamp(r)<<16) | (clamp(g)<<8) | clamp(b);
	}
	private static int clamp(float v){ int i = Math.round(v); return i<0 ? 0 : (i>255 ? 255 : i); }
	private static int lerp(Color a, Color b, float t){
		if(t<0) t = 0; else if(t>1) t = 1;
		return rgb(a.getRed()+(b.getRed()-a.getRed())*t, a.getGreen()+(b.getGreen()-a.getGreen())*t, a.getBlue()+(b.getBlue()-a.getBlue())*t);
	}

	static BufferedImage generate(String name){
		BufferedImage img = PaintTool.newArgb(TILE, TILE);
		int[] px = PaintTool.directPixels(img);
		switch(name){
		case "Wood": {
			//年輪: 縦方向に流れる縞をノイズで揺らす
			float[] n = noise(1, 16, 3, 0.5f);
			Color light = new Color(0xD2A15A), dark = new Color(0x8B5A2B);
			for(int y=0; y<TILE; y++) for(int x=0; x<TILE; x++){
				float v = (float)Math.sin((x/(float)TILE*6 + n[x+y*TILE]*1.5)*Math.PI*2)*0.5f+0.5f;
				px[x+y*TILE] = lerp(light, dark, v*0.8f + n[x+y*TILE]*0.2f);
			}
			break;
		}
		case "Marble": {
			//大理石: 乱流ノイズをsinに通した筋
			float[] n = noise(2, 32, 4, 0.5f);
			Color white = new Color(0xF2F0EC), vein = new Color(0x6E6A72);
			for(int y=0; y<TILE; y++) for(int x=0; x<TILE; x++){
				float v = (float)Math.abs(Math.sin((y/(float)TILE*2 + n[x+y*TILE]*2.5)*Math.PI));
				px[x+y*TILE] = lerp(vein, white, (float)Math.pow(v, 0.5));
			}
			break;
		}
		case "Clouds": {
			float[] n = noise(3, 8, 5, 0.5f);
			Color sky = new Color(0x5C9BE0), cloud = Color.WHITE;
			for(int i=0; i<px.length; i++) px[i] = lerp(sky, cloud, (n[i]-0.3f)*2.2f);
			break;
		}
		case "Sand": {
			float[] n = noise(4, 64, 3, 0.7f);
			float[] m = noise(5, 8, 2, 0.5f);
			Color a = new Color(0xE6D3A3), b = new Color(0xC2A66B);
			for(int i=0; i<px.length; i++) px[i] = lerp(a, b, n[i]*0.7f + m[i]*0.3f);
			break;
		}
		case "Granite": {
			//細かい粒(画素ごとの乱数)に、少し大きめの斑を混ぜる
			float[] n = noise(6, 2, 1, 0.5f);
			float[] m = noise(7, 4, 2, 0.5f);
			Color a = new Color(0xC4C1BB), b = new Color(0x5A5754), c = new Color(0xE0CFC0);
			for(int i=0; i<px.length; i++){
				int base = lerp(a, b, n[i]*0.7f + m[i]*0.3f);
				px[i] = (m[i]>0.6f && n[i]>0.5f) ? lerp(new Color(base), c, 0.7f) : base;
			}
			break;
		}
		case "Brick": {
			//32×16のレンガを段ごとに半分ずらす。目地は2ピクセル
			float[] n = noise(8, 32, 2, 0.5f);
			Color brick = new Color(0xB5432E), mortar = new Color(0xD8D2C8);
			int bw = 32, bh = 16;
			for(int y=0; y<TILE; y++){
				int row = y/bh;
				for(int x=0; x<TILE; x++){
					int xx = (x + (row%2==1 ? bw/2 : 0))%bw;
					boolean joint = (y%bh)<2 || xx<2;
					int c = joint ? mortar.getRGB() : lerp(brick, new Color(0x8E2F1F), n[x+y*TILE]);
					px[x+y*TILE] = c;
				}
			}
			break;
		}
		case "Canvas": {
			//布目: 縦横の織りを細かい格子で
			float[] n = noise(9, 64, 2, 0.5f);
			Color a = new Color(0xF0E9DA), b = new Color(0xC8BFA8);
			for(int y=0; y<TILE; y++) for(int x=0; x<TILE; x++){
				float weave = (((x/2)+(y/2))&1)==0 ? 0.25f : 0.55f;
				px[x+y*TILE] = lerp(a, b, weave + (n[x+y*TILE]-0.5f)*0.5f);
			}
			break;
		}
		case "Water": {
			float[] n = noise(10, 16, 4, 0.5f);
			Color deep = new Color(0x1F5FA8), light = new Color(0x8FD3F4);
			for(int y=0; y<TILE; y++) for(int x=0; x<TILE; x++){
				float v = (float)Math.sin((x/(float)TILE*4 + y/(float)TILE*2 + n[x+y*TILE]*3)*Math.PI*2)*0.5f+0.5f;
				px[x+y*TILE] = lerp(deep, light, v*0.6f + n[x+y*TILE]*0.4f);
			}
			break;
		}
		default:
			for(int i=0; i<px.length; i++) px[i] = 0xFF808080;
		}
		return img;
	}

	//ポップアップ等の見本用: 大きなテクスチャを縮めた絵
	static BufferedImage thumbnail(BufferedImage tex, int size){
		BufferedImage out = PaintTool.newArgb(size, size);
		if(tex==null) return out;
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		int side = Math.min(tex.getWidth(), tex.getHeight());
		g.drawImage(tex, 0, 0, size, size, 0, 0, side, side, null);
		g.dispose();
		return out;
	}
}

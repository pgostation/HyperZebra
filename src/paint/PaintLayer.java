import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

//ペイントの追加レイヤー。HyperCard本来のカードピクチャ(OCardBase.pict)と
//バックグラウンドピクチャはそのまま「Card Paint」「Background Paint」として残し、
//必要な人だけがその上に足す(OCardBase.layers。下から順、先頭が一番下)。
//画像はカードと同じ大きさのARGBで、スタックのバンドルにBMAP_<id>_L<n>.pngとして
//保存され、カード/バックグラウンドのXMLに<paintLayer …/>で並ぶ。
//HyperCardへの書き出しやサムネイルは、OCardBase.composite()で平らにした絵を使う。
class PaintLayer {
	String name;
	BufferedImage image;
	boolean visible = true;
	int opacity = 100;                   //0〜100
	int blend = MyPanel.blendMode.Copy;  //MyPanel.blendModeの値(Copy=通常)
	boolean alphaLock;                   //描いても不透明度(アルファ)を変えない
	boolean clipToBelow;                 //すぐ下のレイヤーの不透明な所だけ見せる
	String fileName;                     //バンドル内のPNG名(未保存ならnull)

	PaintLayer(String name, BufferedImage image){
		this.name = name;
		this.image = image;
	}

	//通常合成・不透明度100%・クリップ無し(単純にdrawImageで重ねられる)
	boolean plain(){
		return blend==MyPanel.blendMode.Copy && opacity>=100 && !clipToBelow;
	}

	PaintLayer copy(String newName){
		PaintLayer l = new PaintLayer(newName, PaintUndo.copyInto(null, image));
		l.visible = visible; l.opacity = opacity; l.blend = blend;
		l.alphaLock = alphaLock; l.clipToBelow = clipToBelow;
		return l;
	}

	long bytes(){
		return image==null ? 0 : 4L*image.getWidth()*image.getHeight();
	}

	//----- 合成モードの名前(XML・HyperTalk・レイヤーパレット共通) -----
	static final String[] BLEND_NAMES = {"Normal", "Blend", "Add", "Subtract", "Multiply", "Screen",
		"Darken", "Lighten", "Difference", "Hue", "Color", "Saturation", "Luminosity"};

	static String blendName(int mode){
		if(mode<0 || mode>=BLEND_NAMES.length) return BLEND_NAMES[0];
		return BLEND_NAMES[mode];
	}

	//名前→モード。知らない名前は-1
	static int parseBlend(String name){
		if(name==null) return -1;
		String n = name.trim();
		if(n.equalsIgnoreCase("Copy")) return MyPanel.blendMode.Copy;
		for(int i=0; i<BLEND_NAMES.length; i++){
			if(BLEND_NAMES[i].equalsIgnoreCase(n)) return i;
		}
		return -1;
	}

	//----- XML -----
	void writeXML(XMLStreamWriter writer) throws javax.xml.stream.XMLStreamException {
		writer.writeEmptyElement("paintLayer");
		writer.writeAttribute("name", name==null ? "" : name);
		if(fileName!=null) writer.writeAttribute("file", fileName);
		writer.writeAttribute("visible", Boolean.toString(visible));
		writer.writeAttribute("opacity", Integer.toString(opacity));
		writer.writeAttribute("blend", blendName(blend));
		writer.writeAttribute("alphaLock", Boolean.toString(alphaLock));
		writer.writeAttribute("clip", Boolean.toString(clipToBelow));
		writer.writeCharacters("\n\t\t");
	}

	//<paintLayer …/>のSTART_ELEMENTで呼ぶ。画像はまだ読まない(fileNameだけ)。
	static PaintLayer readXML(XMLStreamReader reader){
		PaintLayer l = new PaintLayer(attr(reader, "name", ""), null);
		l.fileName = attr(reader, "file", null);
		l.visible = Boolean.parseBoolean(attr(reader, "visible", "true"));
		try{ l.opacity = Math.max(0, Math.min(100, Integer.parseInt(attr(reader, "opacity", "100")))); }catch(NumberFormatException e){}
		int b = parseBlend(attr(reader, "blend", "Normal"));
		l.blend = b<0 ? MyPanel.blendMode.Copy : b;
		l.alphaLock = Boolean.parseBoolean(attr(reader, "alphaLock", "false"));
		l.clipToBelow = Boolean.parseBoolean(attr(reader, "clip", "false"));
		return l;
	}

	private static String attr(XMLStreamReader reader, String name, String def){
		String v = reader.getAttributeValue(null, name);
		return v==null ? def : v;
	}
}


//レイヤーの合成。表示(MyPanel/MyLabel)、書き出し(HCExport)、レイヤーの結合で共通。
class PaintLayers {

	//srcをdstへ重ねる。opacity 0〜100、modeはMyPanel.blendMode、clipがnullでなければ
	//その画像の不透明な所だけ(clipのアルファを掛けて)重ねる。dst/srcは同じ大きさ。
	//通常合成でも不透明度やクリップがあれば画素ごとに計算する。
	static void blendOnto(BufferedImage dst, BufferedImage src, int mode, int opacity, BufferedImage clip){
		if(dst==null || src==null) return;
		if(mode==MyPanel.blendMode.Copy && opacity>=100 && clip==null){
			Graphics2D g = dst.createGraphics();
			g.setComposite(AlphaComposite.SrcOver);
			g.drawImage(src, 0, 0, null);
			g.dispose();
			return;
		}
		int w = Math.min(dst.getWidth(), src.getWidth()), h = Math.min(dst.getHeight(), src.getHeight());
		int[] d = PaintTool.directPixels(dst), s = PaintTool.directPixels(src);
		int[] c = clip==null ? null : PaintTool.directPixels(clip);
		int dw = dst.getWidth(), sw = src.getWidth(), cw = clip==null ? 0 : clip.getWidth();
		float op = Math.max(0, Math.min(100, opacity))/100f;
		float[] hsb = new float[3], hsb2 = new float[3];
		for(int y=0; y<h; y++){
			for(int x=0; x<w; x++){
				int sp = s!=null ? s[x+y*sw] : src.getRGB(x, y);
				int sa = (sp>>>24)&0xFF;
				if(sa==0) continue;
				float a = sa/255f*op;
				if(clip!=null){
					if(x>=clip.getWidth() || y>=clip.getHeight()) continue;
					int cp = c!=null ? c[x+y*cw] : clip.getRGB(x, y);
					a *= ((cp>>>24)&0xFF)/255f;
				}
				if(a<=0f) continue;
				int di = x+y*dw;
				int dp = d!=null ? d[di] : dst.getRGB(x, y);
				int v = blendPixelOver(dp, sp, a, mode, hsb, hsb2);
				if(d!=null) d[di] = v; else dst.setRGB(x, y, v);
			}
		}
	}

	//1画素: srcの色をmodeでdstと混ぜ(dstの不透明な分だけ)、不透明度aでdstの上に重ねる。
	//B = (1-αd)·Cs + αd·blend(Cs,Cd)、その後は通常のsource-over。
	static int blendPixelOver(int dp, int sp, float a, int mode, float[] hsb, float[] hsb2){
		float ad = ((dp>>>24)&0xFF)/255f;
		int srgb = sp&0x00FFFFFF, drgb = dp&0x00FFFFFF;
		int brgb = srgb;
		if(mode!=MyPanel.blendMode.Copy && ad>0f){
			int mixed = mode==MyPanel.blendMode.Blend ? srgb : MyPanel.blendPixel(mode, srgb, drgb, 100, hsb, hsb2);
			if(ad>=1f) brgb = mixed;
			else brgb = lerpRgb(srgb, mixed, ad);
		}
		float ao = a+ad*(1f-a);
		if(ao<=0f) return 0;
		float wS = a/ao, wD = ad*(1f-a)/ao;
		int r = Math.round(((brgb>>16)&0xFF)*wS+((drgb>>16)&0xFF)*wD);
		int g = Math.round(((brgb>>8)&0xFF)*wS+((drgb>>8)&0xFF)*wD);
		int b = Math.round((brgb&0xFF)*wS+(drgb&0xFF)*wD);
		return (Math.round(ao*255)<<24)|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);
	}

	private static int lerpRgb(int a, int b, float t){
		int r = Math.round(((a>>16)&0xFF)+(((b>>16)&0xFF)-((a>>16)&0xFF))*t);
		int g = Math.round(((a>>8)&0xFF)+(((b>>8)&0xFF)-((a>>8)&0xFF))*t);
		int bl = Math.round((a&0xFF)+((b&0xFF)-(a&0xFF))*t);
		return (clamp(r)<<16)|(clamp(g)<<8)|clamp(bl);
	}

	private static int clamp(int v){ return v<0 ? 0 : v>255 ? 255 : v; }

	//base(nullなら透明)の上にlayersを順に重ねた新しい画像。見えないレイヤーは飛ばす。
	//クリップの「すぐ下」は、見えているかに関わらずすぐ下のレイヤー(先頭ならbase)。
	static BufferedImage flatten(BufferedImage base, List<PaintLayer> layers, int width, int height){
		BufferedImage out = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
		if(base!=null){
			Graphics2D g = out.createGraphics();
			g.setComposite(AlphaComposite.Src);
			g.drawImage(base, 0, 0, null);
			g.dispose();
		}
		if(layers==null) return out;
		BufferedImage below = base;
		for(PaintLayer l : layers){
			if(l.visible && l.image!=null){
				//下が無い(透明)所へのクリップは何も見せない
				if(!(l.clipToBelow && below==null)){
					blendOnto(out, l.image, l.blend, l.opacity, l.clipToBelow ? below : null);
				}
			}
			below = l.image;
		}
		return out;
	}

	//visibleでないレイヤーも含めて、レイヤー一覧のうち画像があるものだけ
	static List<PaintLayer> withImages(List<PaintLayer> layers){
		ArrayList<PaintLayer> out = new ArrayList<PaintLayer>();
		if(layers!=null) for(PaintLayer l : layers) if(l.image!=null) out.add(l);
		return out;
	}

	//-------------------
	//レイヤーの操作(メニュー・レイヤーパレット・HyperTalk共通)。すべてカードウインドウ
	//(PCARD.pc)のペイントモード中だけ。構成が変わる操作はUndo履歴を捨てる(履歴は
	//レイヤーごとの画素差分なので、消えたレイヤーの差分は戻せない)。
	//-------------------

	//操作の前に、浮動選択や入力中の文字を今のレイヤーへ確定する
	private static boolean prepare(PCARDFrame owner){
		if(owner==null || owner!=PCARD.pc || owner.tool==null || owner.getSurface()==null) return false;
		if(owner.paintGroup()==null) return false;
		PaintTool.owner = owner;
		owner.tool.end();
		PaintUndo.flush(owner);
		return true;
	}

	private static void changed(PCARDFrame owner, boolean structure){
		OCardBase g = owner.paintGroup();
		if(g!=null){ g.invalidateComposite(); g.changed = true; }
		if(structure) PaintUndo.reset(owner);
		PaintTool.toCdPict();
		GLayerPalette.refresh();
		if(owner.mainPane!=null) owner.mainPane.repaint();
	}

	//本来のピクチャを含めた枚数
	static int count(PCARDFrame owner){
		OCardBase g = owner==null ? null : owner.paintGroup();
		return g==null ? 1 : 1+g.layers.size();
	}

	//番号nのレイヤーの名前(0は"Card Paint"/"Background Paint")
	static String nameOf(PCARDFrame owner, int n){
		if(n<=0) return owner.isEditingBackground() ? "Background Paint" : "Card Paint";
		OCardBase g = owner.paintGroup();
		if(g==null || n>g.layers.size()) return "";
		String name = g.layers.get(n-1).name;
		return name==null ? "" : name;
	}

	//名前(大文字小文字無視)か番号でレイヤーを探す。見つからなければ-1
	static int find(PCARDFrame owner, String key){
		if(key==null) return -1;
		String k = key.trim();
		try{
			int n = Integer.parseInt(k);
			return (n>=1 && n<=count(owner)) ? n-1 : -1;
		}catch(NumberFormatException e){}
		for(int i=0; i<count(owner); i++){
			if(nameOf(owner, i).equalsIgnoreCase(k)) return i;
		}
		return -1;
	}

	//編集するレイヤーを切り替える
	static boolean setActive(PCARDFrame owner, int n){
		if(!prepare(owner)) return false;
		if(n<0 || n>=count(owner)) return false;
		owner.setActiveLayer(n);
		changed(owner, false);
		return true;
	}

	//まだ使われていないレイヤー名 "Layer N"
	private static String newName(OCardBase g){
		for(int n=1; ; n++){
			String name = "Layer "+n;
			boolean used = false;
			for(PaintLayer l : g.layers) if(name.equalsIgnoreCase(l.name)) used = true;
			if(!used) return name;
		}
	}

	//今のレイヤーの上に空のレイヤーを足し、それを編集中にする
	static PaintLayer add(PCARDFrame owner){
		if(!prepare(owner)) return null;
		OCardBase g = owner.paintGroup();
		int w = owner.stack.width, h = owner.stack.height;
		PaintLayer l = new PaintLayer(newName(g), new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB));
		int at = owner.activeLayer(); //0なら先頭(pictのすぐ上)
		g.layers.add(at, l);
		owner.setActiveLayer(at+1);
		changed(owner, true);
		return l;
	}

	//今のレイヤーの複製をすぐ上に作り、それを編集中にする(本来のピクチャも複製できる)
	static PaintLayer duplicate(PCARDFrame owner){
		if(!prepare(owner)) return null;
		OCardBase g = owner.paintGroup();
		int at = owner.activeLayer();
		PaintLayer l;
		if(at==0){
			l = new PaintLayer(nameOf(owner, 0)+" copy", PaintUndo.copyInto(null, owner.getSurface()));
		}else{
			PaintLayer src = g.layers.get(at-1);
			l = src.copy(src.name+" copy");
		}
		g.layers.add(at, l);
		owner.setActiveLayer(at+1);
		changed(owner, true);
		return l;
	}

	//今の追加レイヤーを捨てる(本来のピクチャは捨てられない)
	static boolean delete(PCARDFrame owner){
		if(!prepare(owner)) return false;
		int at = owner.activeLayer();
		if(at==0) return false;
		OCardBase g = owner.paintGroup();
		PaintLayer l = g.layers.remove(at-1);
		deleteFile(owner, l);
		owner.setActiveLayer(at-1);
		changed(owner, true);
		return true;
	}

	private static void deleteFile(PCARDFrame owner, PaintLayer l){
		if(l.fileName==null || owner.stack==null || owner.stack.file==null) return;
		java.io.File f = new java.io.File(owner.stack.file.getParent(), l.fileName);
		if(f.isFile()) f.delete();
		l.fileName = null;
	}

	//今の追加レイヤーをすぐ下のレイヤーへ結合する(下が本来のピクチャならそこへ)
	static boolean mergeDown(PCARDFrame owner){
		if(!prepare(owner)) return false;
		int at = owner.activeLayer();
		if(at==0) return false;
		OCardBase g = owner.paintGroup();
		PaintLayer l = g.layers.get(at-1);
		BufferedImage below = at==1 ? (owner.isEditingBackground() ? owner.bgImg : owner.mainImg) : g.layers.get(at-2).image;
		BufferedImage clip = null;
		if(l.clipToBelow) clip = below;
		if(l.visible) blendOnto(below, l.image, l.blend, l.opacity, clip);
		g.layers.remove(at-1);
		deleteFile(owner, l);
		owner.setActiveLayer(at-1);
		changed(owner, true);
		return true;
	}

	//すべての追加レイヤーを本来のピクチャへ結合する
	static boolean flattenAll(PCARDFrame owner){
		if(!prepare(owner)) return false;
		OCardBase g = owner.paintGroup();
		if(g.layers.isEmpty()) return false;
		BufferedImage base = owner.isEditingBackground() ? owner.bgImg : owner.mainImg;
		BufferedImage flat = flatten(base, g.layers, base.getWidth(), base.getHeight());
		Graphics2D gg = base.createGraphics();
		gg.setComposite(AlphaComposite.Src);
		gg.drawImage(flat, 0, 0, null);
		gg.dispose();
		for(PaintLayer l : new ArrayList<PaintLayer>(g.layers)) deleteFile(owner, l);
		g.layers.clear();
		owner.setActiveLayer(0);
		changed(owner, true);
		return true;
	}

	//今の追加レイヤーを1つ上(delta>0)/下(delta<0)へ動かす(本来のピクチャは一番下のまま)
	static boolean move(PCARDFrame owner, int delta){
		if(!prepare(owner)) return false;
		int at = owner.activeLayer();
		if(at==0) return false;
		OCardBase g = owner.paintGroup();
		int to = at+delta;
		if(to<1 || to>g.layers.size()) return false;
		PaintLayer l = g.layers.remove(at-1);
		g.layers.add(to-1, l);
		owner.setActiveLayer(to);
		changed(owner, true);
		return true;
	}

	//今のレイヤーの属性を変える(見える/不透明度/合成/アルファロック/クリップ/名前)。
	//本来のピクチャには名前と表示以外は無い
	static void setVisible(PCARDFrame owner, int n, boolean v){
		OCardBase g = owner.paintGroup();
		if(g==null || n<1 || n>g.layers.size()) return;
		g.layers.get(n-1).visible = v;
		changed(owner, false);
	}
	static boolean setOpacity(PCARDFrame owner, int opacity){
		PaintLayer l = owner.activePaintLayer();
		if(l==null) return false;
		l.opacity = Math.max(0, Math.min(100, opacity));
		changed(owner, false);
		return true;
	}
	static boolean setBlend(PCARDFrame owner, int mode){
		PaintLayer l = owner.activePaintLayer();
		if(l==null || mode<0 || mode>=PaintLayer.BLEND_NAMES.length) return false;
		l.blend = mode;
		changed(owner, false);
		return true;
	}
	static boolean setAlphaLock(PCARDFrame owner, boolean lock){
		PaintLayer l = owner.activePaintLayer();
		if(l==null) return false;
		l.alphaLock = lock;
		changed(owner, false);
		return true;
	}
	static boolean setClipToBelow(PCARDFrame owner, boolean clip){
		PaintLayer l = owner.activePaintLayer();
		if(l==null) return false;
		l.clipToBelow = clip;
		changed(owner, false);
		return true;
	}
	static boolean rename(PCARDFrame owner, String name){
		PaintLayer l = owner.activePaintLayer();
		if(l==null || name==null || name.trim().isEmpty()) return false;
		l.name = name.trim();
		changed(owner, false);
		return true;
	}

	//保存: レイヤーの画像をバンドルへ書く(名前が無ければ BMAP_<id>_L<n>.png を割り当てる)
	static void save(OCardBase g, java.io.File stackFile){
		if(g==null || g.layers==null || g.layers.isEmpty() || stackFile==null) return;
		for(PaintLayer l : g.layers){
			if(l.image==null) continue;
			if(l.fileName==null){
				for(int n=1; ; n++){
					String name = "BMAP_"+g.id+"_L"+n+".png";
					boolean used = false;
					for(PaintLayer o : g.layers) if(name.equals(o.fileName)) used = true;
					if(!used && !new java.io.File(stackFile.getParent(), name).exists()){ l.fileName = name; break; }
				}
				g.changed = true;
			}
			try{
				StackProtection.writeImage(g.stack, l.image, new java.io.File(stackFile.getParent(), l.fileName));
			}catch(java.io.IOException e){
				e.printStackTrace();
			}
		}
	}

	//srcのアルファにmaskのアルファを掛けた新しい画像(アルファロック中のプレビュー表示用)
	static BufferedImage maskedBy(BufferedImage src, BufferedImage mask){
		if(src==null || mask==null) return src;
		int w = Math.min(src.getWidth(), mask.getWidth()), h = Math.min(src.getHeight(), mask.getHeight());
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		int[] s = PaintTool.directPixels(src), m = PaintTool.directPixels(mask), o = PaintTool.directPixels(out);
		int sw = src.getWidth(), mw = mask.getWidth();
		for(int y=0; y<h; y++){
			for(int x=0; x<w; x++){
				int sp = s!=null ? s[x+y*sw] : src.getRGB(x, y);
				int sa = (sp>>>24)&0xFF;
				if(sa==0) continue;
				int mp = m!=null ? m[x+y*mw] : mask.getRGB(x, y);
				int ma = (mp>>>24)&0xFF;
				if(ma==0) continue;
				int v = ((sa*ma/255)<<24)|(sp&0x00FFFFFF);
				if(o!=null) o[x+y*sw] = v; else out.setRGB(x, y, v);
			}
		}
		return out;
	}

	//カード/バックグラウンドを削除したとき: その追加レイヤーのPNGと、他のカード/bgが
	//使っていなければ本来のピクチャ(BMAP_<id>.png)も捨てる
	static void deleteFiles(OStack stack, OCardBase g){
		if(g==null || stack==null || stack.file==null || stack.file.getParentFile()==null) return;
		java.io.File dir = stack.file.getParentFile();
		if(g.layers!=null){
			for(PaintLayer l : g.layers){
				if(l.fileName==null) continue;
				java.io.File f = new java.io.File(dir, l.fileName);
				if(f.isFile()) f.delete();
				l.fileName = null;
			}
		}
		if(g.bitmapName!=null && !bitmapShared(stack, g)){
			java.io.File f = new java.io.File(dir, g.bitmapName);
			if(f.isFile()) f.delete();
		}
	}

	//gのピクチャのファイル名を、g以外のカード/バックグラウンドも使っているか
	private static boolean bitmapShared(OStack stack, OCardBase g){
		for(OCard c : stack.cdCacheList) if(c!=g && g.bitmapName.equals(c.bitmapName)) return true;
		for(OBackground b : stack.bgCacheList) if(b!=g && g.bitmapName.equals(b.bitmapName)) return true;
		return false;
	}

	//アルファロック: 操作前(before)の不透明度を操作後(after)に戻す。before==nullなら何もしない。
	//透明だった画素は透明のまま(色も捨てる)、不透明だった画素は色だけ新しく。
	static void enforceAlphaLock(BufferedImage after, BufferedImage before){
		if(after==null || before==null) return;
		if(after.getWidth()!=before.getWidth() || after.getHeight()!=before.getHeight()) return;
		int[] a = PaintTool.directPixels(after), b = PaintTool.directPixels(before);
		int w = after.getWidth(), h = after.getHeight();
		for(int y=0; y<h; y++){
			for(int x=0; x<w; x++){
				int i = x+y*w;
				int bp = b!=null ? b[i] : before.getRGB(x, y);
				int ap = a!=null ? a[i] : after.getRGB(x, y);
				int ba = bp&0xFF000000;
				int v = ba==0 ? 0 : (ba|(ap&0x00FFFFFF));
				if(v!=ap){ if(a!=null) a[i] = v; else after.setRGB(x, y, v); }
			}
		}
	}
}

import java.awt.image.BufferedImage;

/**
 * A paint layer of a card or background (Sept 2026): its picture over the card's picture and how it is blended there. The
 * model is the core's (the browser player composites a card's layers, HzLayers); the desktop's operations and compositing
 * are PaintLayers (src/paint/PaintLayers.java).
 */
class PaintLayer {
	String name;
	BufferedImage image;
	boolean visible = true;
	int opacity = 100;                   //0〜100
	int blend = NORMAL;  //MyPanel.blendModeの値(Copy=通常)
	boolean alphaLock;                   //描いても不透明度(アルファ)を変えない
	boolean clipToBelow;                 //すぐ下のレイヤーの不透明な所だけ見せる
	String fileName;                     //バンドル内のPNG名(未保存ならnull)

	PaintLayer(String name, BufferedImage image){
		this.name = name;
		this.image = image;
	}

	/** MyPanel.blendMode.Copy: plain source-over (the model is the core's too: the browser player draws a card's layers) */
	static final int NORMAL = 0;

	//通常合成・不透明度100%・クリップ無し(単純にdrawImageで重ねられる)
	boolean plain(){
		return blend==NORMAL && opacity>=100 && !clipToBelow;
	}

	/** The same settings over another picture (a copy of this one's: PaintLayers.duplicate). */
	PaintLayer with(String newName, BufferedImage picture){
		PaintLayer l = new PaintLayer(newName, picture);
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
		if(n.equalsIgnoreCase("Copy")) return NORMAL;
		for(int i=0; i<BLEND_NAMES.length; i++){
			if(BLEND_NAMES[i].equalsIgnoreCase(n)) return i;
		}
		return -1;
	}

	//----- XML -----
	void writeXML(XmlOut writer) throws XmlException {
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
	static PaintLayer readXML(XmlIn reader){
		PaintLayer l = new PaintLayer(attr(reader, "name", ""), null);
		l.fileName = attr(reader, "file", null);
		l.visible = Boolean.parseBoolean(attr(reader, "visible", "true"));
		try{ l.opacity = Math.max(0, Math.min(100, Integer.parseInt(attr(reader, "opacity", "100")))); }catch(NumberFormatException e){}
		int b = parseBlend(attr(reader, "blend", "Normal"));
		l.blend = b<0 ? NORMAL : b;
		l.alphaLock = Boolean.parseBoolean(attr(reader, "alphaLock", "false"));
		l.clipToBelow = Boolean.parseBoolean(attr(reader, "clip", "false"));
		return l;
	}

	private static String attr(XmlIn reader, String name, String def){
		String v = reader.getAttributeValue(null, name);
		return v==null ? def : v;
	}
}

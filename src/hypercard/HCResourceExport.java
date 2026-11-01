import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/** Builds a classic Macintosh resource fork from HyperZebra resource objects. */
final class HCResourceExport {
	static final class Result {
		final byte[] fork;
		final int count;
		final ArrayList<String> warnings;
		Result(byte[] fork, int count, ArrayList<String> warnings){
			this.fork=fork; this.count=count; this.warnings=warnings;
		}
	}

	private static final class Resource {
		String type, name; int id; byte[] data; int dataOffset, nameOffset=-1;
		/** The attributes the import kept (Rsrc.KEPT_ATTRIBUTES), written back into the map entry. */
		int attributes;
		Resource(String type,int id,String name,byte[] data){
			this.type=type;this.id=id;this.name=name==null?"":name;this.data=data;
		}
	}

	static Result build(OStack stack, StackTextEncoding encoding) throws IOException {
		StackTextEncoder encoder=new StackTextEncoder(encoding);
		Result result=build(stack,encoder);
		if(encoder.replacementOccurred())result.warnings.add(encoder.replacementWarning());
		return result;
	}

	static Result build(OStack stack, StackTextEncoder encoder) throws IOException {
		ArrayList<Resource> resources=new ArrayList<Resource>();
		ArrayList<String> warnings=new ArrayList<String>();
		if(stack.rsrc!=null){
			for(Rsrc.rsrcClass source: stack.rsrc.rsrcIdMap.values()) {
				Resource converted=convert(stack, source, warnings);
				if(converted!=null){ converted.attributes=source.attributes&Rsrc.KEPT_ATTRIBUTES; resources.add(converted); }
			}
			for(Rsrc.xcmdClass xcmd: stack.rsrc.getXcmdResources()) {
				File file=resourceFile(stack,xcmd.filename);
				if(!file.isFile()){warnings.add("External command resource not found: "+xcmd.name);continue;}
				String type=xcmd.type.equals("function")?"XFCN":"XCMD";
				if(xcmd.platform!=null && xcmd.platform.toLowerCase().contains("ppc")) type=type.toLowerCase();
				Resource external=new Resource(type,xcmd.id,xcmd.name,Files.readAllBytes(file.toPath()));
				external.attributes=xcmd.attributes&Rsrc.KEPT_ATTRIBUTES;
				resources.add(external);
			}
			for(Rsrc.addcolorClass color: stack.rsrc.addcolorList){
				//AddColorはカードIDを名前にした HCcd/HCbg を GetNamedResource で探す(名前が無いと色が付かない)
				resources.add(new Resource(color.isBg?"HCbg":"HCcd",color.id,Integer.toString(color.id),addColor(color,encoder)));
			}
			for(Rsrc.PlteClass palette: stack.rsrc.plteList){
				resources.add(new Resource("PLTE",palette.id,palette.name,palette(palette,encoder)));
			}
		}
		Map<String,Resource> unique=new LinkedHashMap<String,Resource>();
		for(Resource resource:resources){String key=resource.type+":"+resource.id;if(unique.containsKey(key))warnings.add("Duplicate resource was omitted: "+key);else unique.put(key,resource);}
		resources=new ArrayList<Resource>(unique.values());
		//grouped by type; within a type in the stack's resource map order (the imported file's: Get1IndResource counts in it)
		Collections.sort(resources, Comparator.comparing((Resource r)->r.type));
		return new Result(resourceFork(resources,encoder),resources.size(),warnings);
	}

	private static Resource convert(OStack stack, Rsrc.rsrcClass source,
			ArrayList<String> warnings) throws IOException {
		File file=resourceFile(stack,source.filename);
		String type=source.type;
		//ICON/cicn/CURS/crsr/PAT#/ppatは、インポート時のMac生データ
		//(拡張子.data)のまま保存されており、リソースエディタで編集した
		//あとも同じ生データのまま保存し直される(HCResource.readResourceData、
		//Rsrc.loadImage、IconEditor.save参照)。その場合は画像へのデコード・
		//再エンコードを経由せず、生データをそのまま書き出す(pictureが.pictの
		//ときに元のPICTバイト列をそのまま使うのと同じ考え方。画質の劣化が無く、
		//リソースが多いスタックのエクスポートも速い)。画像ファイル(.png等)に
		//なっている場合(このアプリの旧バージョンで編集済みのものを開いた場合等)
		//だけ、画像から変換する。
		if(type.equals("PAT#")) {
			if(rawBytesAvailable(file,source.filename)) return new Resource("PAT#",source.id,source.name,readRaw(file));
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Pattern resource not found: "+source.name);return null;}
			return new Resource("PAT#",source.id,source.name,patSharp(image));
		}
		if(type.equals("ppat")) {
			if(rawBytesAvailable(file,source.filename)) return new Resource("ppat",source.id,source.name,readRaw(file));
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Pixel pattern resource not found: "+source.name);return null;}
			return new Resource("ppat",source.id,source.name,ppat(image));
		}
		if(type.equals("crsr")) {
			if(rawBytesAvailable(file,source.filename)) return new Resource("crsr",source.id,source.name,readRaw(file));
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Color cursor resource not found: "+source.name);return null;}
			return new Resource("crsr",source.id,source.name,crsr(image,source.hotsporleft,source.hotsportop));
		}
		if(type.length()==4 && !type.equals("ICON") && !type.equals("PICT") && !type.equals("CURS") && !type.equals("font") && !type.equals("cicn")) {
			if(!file.isFile()){warnings.add("Resource not found: "+source.name);return null;}
			return new Resource(type,source.id,source.name,Files.readAllBytes(file.toPath()));
		}
		if(type.equals("ICON")) {
			if(rawBytesAvailable(file,source.filename)) return new Resource("ICON",source.id,source.name,readRaw(file));
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Icon resource not found: "+source.name);return null;}
			return new Resource("ICON",source.id,source.name,monoBits(image,32,32,false));
		}
		if(type.equals("CURS")) {
			if(rawBytesAvailable(file,source.filename)) return new Resource("CURS",source.id,source.name,readRaw(file));
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Cursor resource not found: "+source.name);return null;}
			ByteArrayOutputStream bytes=new ByteArrayOutputStream();
			bytes.write(monoBits(image,16,16,false)); bytes.write(monoBits(image,16,16,true));
			DataOutputStream d=new DataOutputStream(bytes); d.writeShort(source.hotsportop); d.writeShort(source.hotsporleft);
			return new Resource("CURS",source.id,source.name,bytes.toByteArray());
		}
		if(type.equals("PICT")) {
			//インポート時(HCResource.convertPICT2PICTfile)とグラフィックエディタ(IconEditor)は
			//PICTを512バイトヘッダ付きの.pictファイルとして保存する。他の生リソースの.dataと
			//同じく、そのバイト列をヘッダを除いてそのまま書き出す(変換し直さない)
			if(rawBytesAvailable(file,source.filename)||(file.isFile()&&source.filename!=null&&source.filename.toLowerCase().endsWith(".pict"))) {
				byte[] data=readRaw(file);
				if(data.length>512){byte[] pict=new byte[data.length-512];System.arraycopy(data,512,pict,0,pict.length);return new Resource("PICT",source.id,source.name,pict);}
			}
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Picture resource not found: "+source.name);return null;}
			return new Resource("PICT",source.id,source.name,pict(image));
		}
		if(type.equals("SND")) {
			if(!file.isFile()){warnings.add("Sound resource not found: "+source.name);return null;}
			if(rawBytesAvailable(file,source.filename)) return new Resource("snd ",source.id,source.name,readRaw(file));
			try{return new Resource("snd ",source.id,source.name,sound(file));}
			catch(Exception e){warnings.add("Sound resource could not be converted: "+source.name);return null;}
		}
		if(type.equals("font")) {
			if(!(source.optionInfo instanceof Rsrc.FontInfo)){return new Resource("NFNT",source.id,source.name,new byte[0]);}
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Bitmap font resource not found: "+source.name);return null;}
			return new Resource("NFNT",source.id,source.name,font((Rsrc.FontInfo)source.optionInfo,image));
		}
		if(type.equals("cicn")){
			if(rawBytesAvailable(file,source.filename)) return new Resource("cicn",source.id,source.name,readRaw(file));
			BufferedImage image=readImage(file);
			if(image==null){warnings.add("Color icon resource not found: "+source.name);return null;}
			return new Resource("cicn",source.id,source.name,colorIcon(image));
		}
		if(file.isFile() && type.length()==4) return new Resource(type,source.id,source.name,Files.readAllBytes(file.toPath()));
		warnings.add("Unsupported resource type: "+type+" ("+source.name+")");
		return null;
	}

	//ファイルが存在し、かつ拡張子が.data(=インポート時に保存した生のMac
	//リソースバイト列のまま、まだ画像として編集されていない)であればtrue。
	private static boolean rawBytesAvailable(File file,String filename){
		return file.isFile() && filename!=null && filename.toLowerCase().endsWith(".data");
	}

	private static byte[] readRaw(File file) throws IOException {
		return Files.readAllBytes(file.toPath());
	}

	private static File resourceFile(OStack stack,String filename){
		if(filename==null)return new File("");
		File file=new File(filename); if(file.isAbsolute())return file;
		return stack.file==null||stack.file.getParentFile()==null?file:new File(stack.file.getParentFile(),filename);
	}

	private static BufferedImage readImage(File file){try{return file.isFile()?ImageIO.read(file):null;}catch(IOException e){return null;}}

	static byte[] monoBits(BufferedImage image,int width,int height,boolean mask){
		byte[] result=new byte[width*height/8];
		for(int y=0;y<height;y++)for(int x=0;x<width;x++){
			int argb=(x<image.getWidth()&&y<image.getHeight())?image.getRGB(x,y):0;
			int alpha=(argb>>>24)&255, red=(argb>>>16)&255,green=(argb>>>8)&255,blue=argb&255;
			boolean set=mask?alpha>=128:(alpha>=128&&(299*red+587*green+114*blue)/1000<128);
			if(set)result[y*(width/8)+x/8]|=1<<(7-x%8);
		}
		return result;
	}

	//IconEditor(内蔵グラフィックエディタ)がPICTリソースを編集して保存する際にも
	//使うため、パッケージ内に公開しておく。
	//PICTはcicn/ppat/crsrと違って256色パレットに縛られる必要がなく、Color
	//QuickDrawのDirectBitsRect(32bit直接カラー)にそのまま対応できるため、
	//パレットへの量子化はせず各ピクセルのRGBをそのまま書き出す
	//(読み込み側は既にPictureFile.loadPICTが32bitのDirectBitsRectに対応
	//済み)。行のバイト列はR面・G面・B面の3プレーンを順に並べたもの
	//(実機のPICTの32bit直接ピクセルと同じレイアウト)で、これをPackBitsで
	//圧縮する。PixMapのrowBytesは実機と同じく32bitピクセル1行分(幅*4)。
	//展開後の行は3プレーン分(幅*3)しか無いが、QuickDrawはrowBytesを
	//行バッファの大きさと圧縮長のバイト/ワード判定にしか使わない
	//(幅*3にしていた頃は、68KのDrawPictureが行バッファを幅*3で切り詰めて
	//右側1/4のピクセルが落ちていた)。
	static byte[] pict(BufferedImage image)throws IOException{
		int width=Math.min(4095,image.getWidth()),height=Math.min(32767,image.getHeight()); //rowBytes(幅*4)はフラグを除く14bitに収める
		if(width<=0||height<=0)throw new IOException("Picture has no pixels.");
		int rowBytes=width*4; //32bit PixMapのrowBytes
		int planeBytes=width*3; //圧縮前の行データ: R/G/Bの3プレーン
		Rectangle bounds=new Rectangle(0,0,width,height);
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeShort(0);writeRect(d,bounds);
		// Version 2 PICT header, required for Color QuickDraw PixMap opcodes.
		d.writeShort(0x0011);d.writeShort(0x02FF);d.writeShort(0x0C00);
		d.writeShort(0xFFFE);d.writeShort(0);d.writeInt(72<<16);d.writeInt(72<<16);writeRect(d,bounds);d.writeInt(0);
		d.writeShort(0x009A); // DirectBitsRect(32bit直接カラー。カラーテーブル不要)
		d.writeInt(0); //baseAddr(ダミー。実機でも解凍時には使われない)
		d.writeShort(0x8000|rowBytes);writeRect(d,bounds);
		d.writeShort(0);d.writeShort(0);d.writeInt(0); //pmVersion,packType,packSize
		d.writeInt(72<<16);d.writeInt(72<<16); //hRes,vRes
		d.writeShort(16);d.writeShort(32);d.writeShort(3);d.writeShort(8); //pixelType(RGBDirect),pixelSize,cmpCount,cmpSize
		d.writeInt(0);d.writeInt(0);d.writeInt(0); //planeBytes,pmTable,pmReserved(カラーテーブルなし)
		writeRect(d,bounds);writeRect(d,bounds);d.writeShort(0); // srcCopy
		for(int y=0;y<height;y++){
			byte[] row=new byte[planeBytes];
			for(int x=0;x<width;x++){
				int argb=image.getRGB(x,y);
				row[x]=(byte)((argb>>16)&255); //Rプレーン
				row[x+width]=(byte)((argb>>8)&255); //Gプレーン
				row[x+width*2]=(byte)(argb&255); //Bプレーン
			}
			byte[] packed=packBits(row);
			if(rowBytes>250)d.writeShort(packed.length);else d.writeByte(packed.length);
			d.write(packed);
		}
		if((out.size()&1)!=0)d.writeByte(0);
		d.writeShort(0x00FF);
		byte[] result=out.toByteArray();
		ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putShort(0,(short)Math.min(65535,result.length));
		return result;
	}

	static byte[] colorIcon(BufferedImage image)throws IOException{
		int width=Math.min(32766,image.getWidth()),height=Math.min(32767,image.getHeight());
		if(width<=0||height<=0)throw new IOException("Color icon has no pixels.");
		int rowBytes=(width+1)&~1, bitRowBytes=((width+15)/16)*2;
		Rectangle bounds=new Rectangle(0,0,width,height);
		int[] palette=buildPalette(image);
		byte[] indices=ditherToPalette(image,palette);
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeInt(0);writePixMap(d,rowBytes,bounds);
		writeBitMap(d,bitRowBytes,bounds);writeBitMap(d,bitRowBytes,bounds);
		for(int y=0;y<height;y++)d.write(oneBitRow(image,y,width,bitRowBytes,true));
		for(int y=0;y<height;y++)d.write(oneBitRow(image,y,width,bitRowBytes,false));
		d.writeInt(0); // iconData handle; zero in a compiled resource
		writeColorTable(d,palette);
		for(int y=0;y<height;y++){
			for(int x=0;x<width;x++)d.writeByte(indices[x+y*width]);
			if((width&1)!=0)d.writeByte(0);
		}
		return out.toByteArray();
	}

	//PAT#: 8x8の1bitパターンを横一列に並べた画像(幅8*N、高さ8。
	//convertPAT2PNG参照)を、その並びのままN個の8x8パターンへ分割して書き出す。
	static byte[] patSharp(BufferedImage image)throws IOException{
		int height=image.getHeight();
		int count=Math.max(1,image.getWidth()/8);
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeShort(count);
		for(int p=0;p<count;p++){
			for(int y=0;y<8;y++){
				int row=0;
				for(int x=0;x<8;x++){
					int px=p*8+x;
					boolean set=false;
					if(px<image.getWidth() && y<height){
						int argb=image.getRGB(px,y);
						int alpha=(argb>>>24)&255,red=(argb>>>16)&255,green=(argb>>>8)&255,blue=argb&255;
						set=alpha>=128&&(299*red+587*green+114*blue)/1000<128;
					}
					if(set)row|=1<<(7-x);
				}
				d.writeByte(row);
			}
		}
		return out.toByteArray();
	}

	//ppat: カラーのピクセルパターン。PixMap+ColorTableを持つカラー版として書き出す
	//(白黒版フォールバックのpat1Dataは、画像全体を8x8にダウンサンプルした
	//ものから合成する)。
	static byte[] ppat(BufferedImage image)throws IOException{
		int width=Math.min(32766,image.getWidth()),height=Math.min(32767,image.getHeight());
		if(width<=0||height<=0)throw new IOException("Pixel pattern has no pixels.");
		int rowBytes=(width+1)&~1;
		Rectangle bounds=new Rectangle(0,0,width,height);
		//レイアウト: ヘッダ(28)+PixMap(baseAddr込みで50)+ピクセルデータ(rowBytes*height)
		//+ColorTable、の順に配置する(convertppat2PNGの読み込み側とオフセットを揃える)。
		int patMap=28, patData=patMap+50, pmTable=patData+rowBytes*height;
		int[] palette=buildPalette(image);
		byte[] indices=ditherToPalette(image,palette);
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeShort(1); //patType=1(カラーパターン)
		d.writeInt(patMap);
		d.writeInt(patData);
		d.writeInt(0); //patXData
		d.writeShort(-1); //patXValid(無効)
		d.writeInt(0); //patXMap
		d.write(patFallbackBits(image)); //pat1Data(offset20、8バイト)
		//PixMap(50バイト): baseAddr(4、未使用)+writePixMapの中身(46)。
		//pmTableだけは実際のColorTableの位置を指す必要があるので、writePixMapを
		//使わずここで直接書く。
		d.writeInt(0); //baseAddr
		d.writeShort(0x8000|rowBytes);writeRect(d,bounds);
		d.writeShort(0);d.writeShort(0);d.writeInt(0); //version,packType,packSize
		d.writeInt(72<<16);d.writeInt(72<<16); //hRes,vRes
		d.writeShort(0);d.writeShort(8);d.writeShort(1);d.writeShort(8); //pixelType,pixelSize,cmpCount,cmpSize
		d.writeInt(0);d.writeInt(pmTable);d.writeInt(0); //planeBytes,pmTable,pmReserved
		for(int y=0;y<height;y++){
			for(int x=0;x<width;x++)d.writeByte(indices[x+y*width]);
			if((width&1)!=0)d.writeByte(0);
		}
		writeColorTable(d,palette);
		return out.toByteArray();
	}

	//crsr: カラーカーソル。16x16固定(実機のcrsrリソースの標準サイズ)。
	//ppatと同様PixMap+ColorTableを持ち、白黒版フォールバック(crsr1Data)と
	//形の輪郭を表すマスク(crsrMask)、ホットスポットも書き出す。
	static byte[] crsr(BufferedImage image,int hotLeft,int hotTop)throws IOException{
		int width=16,height=16;
		int rowBytes=(width+1)&~1;
		Rectangle bounds=new Rectangle(0,0,width,height);
		//レイアウト: ヘッダ(96)+PixMap(baseAddr込みで50)+ピクセルデータ+ColorTable
		int crsrMap=96, crsrData=crsrMap+50, pmTable=crsrData+rowBytes*height;
		int[] palette=buildPalette(image);
		byte[] indices=ditherToPalette(image,palette);
		int srcWidth=image.getWidth();
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeShort(0x8001); //crsrType(カラーカーソル: $8001。$8000は白黒のカーソル)
		d.writeInt(crsrMap);
		d.writeInt(crsrData);
		d.writeInt(0); //crsrXData
		d.writeShort(-1); //crsrXValid(無効)
		d.writeInt(0); //crsrXHandle
		d.write(monoBits(image,16,16,false)); //crsr1Data(白黒フォールバック、32バイト)
		d.write(monoBits(image,16,16,true)); //crsrMask(形の輪郭、32バイト)
		d.writeShort(hotTop);d.writeShort(hotLeft); //crsrHotSpot(v,hの順)
		d.writeInt(0); //crsrXTable
		d.writeInt(0); //crsrID
		//PixMap(50バイト): baseAddr(4、未使用)+本体(46)。pmTableは実際の
		//ColorTableの位置を指す必要があるのでwritePixMapを使わず直接書く。
		d.writeInt(0); //baseAddr
		d.writeShort(0x8000|rowBytes);writeRect(d,bounds);
		d.writeShort(0);d.writeShort(0);d.writeInt(0); //version,packType,packSize
		d.writeInt(72<<16);d.writeInt(72<<16); //hRes,vRes
		d.writeShort(0);d.writeShort(8);d.writeShort(1);d.writeShort(8); //pixelType,pixelSize,cmpCount,cmpSize
		d.writeInt(0);d.writeInt(pmTable);d.writeInt(0); //planeBytes,pmTable,pmReserved
		for(int y=0;y<height;y++){
			for(int x=0;x<width;x++){
				byte idx=(x<srcWidth&&y<image.getHeight())?indices[x+y*srcWidth]:(byte)255;
				d.writeByte(idx);
			}
			if((width&1)!=0)d.writeByte(0);
		}
		writeColorTable(d,palette);
		return out.toByteArray();
	}

	//ppatの白黒フォールバック(pat1Data)用に、画像全体を8x8へ単純に間引いて
	//1bitパターン化する。
	private static byte[] patFallbackBits(BufferedImage image){
		byte[] result=new byte[8];
		int width=image.getWidth(),height=image.getHeight();
		for(int y=0;y<8;y++){
			int row=0;
			for(int x=0;x<8;x++){
				int sx=Math.min(width-1,x*width/8), sy=Math.min(height-1,y*height/8);
				int argb=image.getRGB(sx,sy);
				int alpha=(argb>>>24)&255,red=(argb>>>16)&255,green=(argb>>>8)&255,blue=argb&255;
				boolean set=alpha>=128&&(299*red+587*green+114*blue)/1000<128;
				if(set)row|=1<<(7-x);
			}
			result[y]=(byte)row;
		}
		return result;
	}

	private static void writePixMap(DataOutputStream d,int rowBytes,Rectangle bounds)throws IOException{
		d.writeShort(0x8000|rowBytes);writeRect(d,bounds);
		d.writeShort(0);d.writeShort(0);d.writeInt(0); // version, packType, packSize
		d.writeInt(72<<16);d.writeInt(72<<16);
		d.writeShort(0);d.writeShort(8);d.writeShort(1);d.writeShort(8);
		d.writeInt(0);d.writeInt(0);d.writeInt(0);
	}

	private static void writeBitMap(DataOutputStream d,int rowBytes,Rectangle bounds)throws IOException{
		d.writeInt(0);d.writeShort(rowBytes);writeRect(d,bounds);
	}

	private static void writeColorTable(DataOutputStream d,int[] palette)throws IOException{
		d.writeInt(0);d.writeShort(0);d.writeShort(255);
		for(int i=0;i<256;i++){
			int rgb=palette[i];
			int red=(rgb>>16)&255,green=(rgb>>8)&255,blue=rgb&255;
			d.writeShort(i);d.writeShort(red*65535/255);d.writeShort(green*65535/255);d.writeShort(blue*65535/255);
		}
	}

	//cicn/ppat/crsr/カラーPICTは、実機のシステムパレットではなく画像ごとに
	//専用のカラーテーブルを埋め込む方式にした(読み込み側のdecodeCicn等は
	//リソースに実際に入っているカラーテーブルをそのまま読むので、どちらの
	//方式でも正しく解釈できる)。以前は各チャンネルを固定式(R3bit/G3bit/B2bit)
	//に切り捨てるだけで、グラデーション等で色帯(バンディング)が目立って
	//いたため、画像の内容に合わせた256色パレットを作り(重み付きメディアン
	//カット)、誤差拡散(Floyd-Steinberg)でその中の一番近い色に割り当てる
	//ようにした。index 255は常に白を割り当て、透明ピクセル・背景用に予約する
	//(通常の不透明ピクセルの割り当て先には使わない)。
	private static int[] buildPalette(BufferedImage image){
		int width=image.getWidth(),height=image.getHeight();
		Map<Integer,Integer> histogram=new LinkedHashMap<Integer,Integer>();
		for(int y=0;y<height;y++)for(int x=0;x<width;x++){
			int argb=image.getRGB(x,y);
			if(((argb>>>24)&255)<128)continue; //透明はパレットの対象に含めない
			int rgb=argb&0xFFFFFF;
			Integer count=histogram.get(rgb);
			histogram.put(rgb,count==null?1:count+1);
		}
		int[] palette=new int[256];
		palette[255]=0xFFFFFF;
		if(histogram.isEmpty())return palette; //全て透明な画像。残りは黒のまま

		List<int[]> initial=new ArrayList<int[]>(histogram.size()); //各要素は{r,g,b,count}
		for(Map.Entry<Integer,Integer> entry:histogram.entrySet()){
			int rgb=entry.getKey();
			initial.add(new int[]{(rgb>>16)&255,(rgb>>8)&255,rgb&255,entry.getValue()});
		}

		List<List<int[]>> boxes=new ArrayList<List<int[]>>();
		boxes.add(initial);
		while(boxes.size()<255){
			int splitIndex=-1,bestChannel=0,bestRange=-1;
			for(int i=0;i<boxes.size();i++){
				List<int[]> box=boxes.get(i);
				if(box.size()<=1)continue;
				int minR=255,maxR=0,minG=255,maxG=0,minB=255,maxB=0;
				for(int[] c:box){
					if(c[0]<minR)minR=c[0]; if(c[0]>maxR)maxR=c[0];
					if(c[1]<minG)minG=c[1]; if(c[1]>maxG)maxG=c[1];
					if(c[2]<minB)minB=c[2]; if(c[2]>maxB)maxB=c[2];
				}
				int rangeR=maxR-minR,rangeG=maxG-minG,rangeB=maxB-minB;
				int channel=rangeR>=rangeG&&rangeR>=rangeB?0:(rangeG>=rangeB?1:2);
				int range=channel==0?rangeR:(channel==1?rangeG:rangeB);
				if(range>bestRange){bestRange=range;splitIndex=i;bestChannel=channel;}
			}
			if(splitIndex<0)break; //これ以上分割できる箱がない(元の色数がそもそも少ない)

			final int channel=bestChannel;
			List<int[]> box=boxes.remove(splitIndex);
			Collections.sort(box,new Comparator<int[]>(){
				@Override public int compare(int[] a,int[] b){return a[channel]-b[channel];}
			});
			long total=0;for(int[] c:box)total+=c[3];
			long half=0;int cut=1;
			for(int i=0;i<box.size();i++){
				half+=box.get(i)[3];
				if(half*2>=total){cut=i+1;break;}
			}
			cut=Math.max(1,Math.min(box.size()-1,cut));
			boxes.add(new ArrayList<int[]>(box.subList(0,cut)));
			boxes.add(new ArrayList<int[]>(box.subList(cut,box.size())));
		}

		for(int i=0;i<boxes.size()&&i<255;i++){
			long r=0,g=0,b=0,count=0;
			for(int[] c:boxes.get(i)){r+=(long)c[0]*c[3];g+=(long)c[1]*c[3];b+=(long)c[2]*c[3];count+=c[3];}
			palette[i]=count==0?0:((int)(r/count)<<16)|((int)(g/count)<<8)|(int)(b/count);
		}
		//箱の数が255未満(元の色数が少ない)ときは、残りを最後の箱の色で埋める
		for(int i=boxes.size();i<255;i++)palette[i]=palette[Math.max(0,Math.min(254,boxes.size()-1))];
		return palette;
	}

	//buildPaletteが作ったパレットに対して誤差拡散しながら各ピクセルを一番
	//近い色に割り当て、そのpalette index(row-major)を返す。
	private static byte[] ditherToPalette(BufferedImage image,int[] palette){
		int width=image.getWidth(),height=image.getHeight();
		byte[] indices=new byte[width*height];
		float[] errR=new float[width*height],errG=new float[width*height],errB=new float[width*height];
		for(int y=0;y<height;y++){
			for(int x=0;x<width;x++){
				int pos=x+y*width;
				int argb=image.getRGB(x,y);
				if(((argb>>>24)&255)<128){
					indices[pos]=(byte)255;
					continue;
				}
				float r=clamp255(((argb>>16)&255)+errR[pos]);
				float g=clamp255(((argb>>8)&255)+errG[pos]);
				float b=clamp255((argb&255)+errB[pos]);
				int nearest=nearestPaletteIndex(palette,r,g,b);
				indices[pos]=(byte)nearest;
				int pr=(palette[nearest]>>16)&255,pg=(palette[nearest]>>8)&255,pb=palette[nearest]&255;
				float er=r-pr,eg=g-pg,eb=b-pb;
				//Floyd-Steinberg分配(右、左下、下、右下)
				if(x+1<width)diffuseError(errR,errG,errB,pos+1,er,eg,eb,7f/16f);
				if(x>0&&y+1<height)diffuseError(errR,errG,errB,pos+width-1,er,eg,eb,3f/16f);
				if(y+1<height)diffuseError(errR,errG,errB,pos+width,er,eg,eb,5f/16f);
				if(x+1<width&&y+1<height)diffuseError(errR,errG,errB,pos+width+1,er,eg,eb,1f/16f);
			}
		}
		return indices;
	}

	private static float clamp255(float v){return v<0?0:(v>255?255:v);}

	private static void diffuseError(float[] errR,float[] errG,float[] errB,int pos,float er,float eg,float eb,float weight){
		errR[pos]+=er*weight;errG[pos]+=eg*weight;errB[pos]+=eb*weight;
	}

	private static int nearestPaletteIndex(int[] palette,float r,float g,float b){
		int best=0;double bestDist=Double.MAX_VALUE;
		for(int i=0;i<255;i++){ //255番は透明/背景専用なので通常ピクセルの候補には含めない
			int pr=(palette[i]>>16)&255,pg=(palette[i]>>8)&255,pb=palette[i]&255;
			double dr=r-pr,dg=g-pg,db=b-pb;
			double dist=dr*dr+dg*dg+db*db;
			if(dist<bestDist){bestDist=dist;best=i;}
		}
		return best;
	}

	private static byte[] oneBitRow(BufferedImage image,int y,int width,int rowBytes,boolean mask){
		byte[] row=new byte[rowBytes];
		for(int x=0;x<width;x++){
			int argb=image.getRGB(x,y),alpha=(argb>>>24)&255;
			int red=(argb>>>16)&255,green=(argb>>>8)&255,blue=argb&255;
			boolean set=mask?alpha>=128:alpha>=128&&(299*red+587*green+114*blue)/1000<128;
			if(set)row[x/8]|=1<<(7-x%8);
		}
		return row;
	}

	private static byte[] packBits(byte[] row){
		ByteArrayOutputStream packed=new ByteArrayOutputStream();
		for(int offset=0;offset<row.length;){
			int run=1;while(offset+run<row.length&&run<128&&row[offset+run]==row[offset])run++;
			if(run>=3){packed.write(257-run);packed.write(row[offset]);offset+=run;continue;}
			int literalStart=offset;offset+=run;
			while(offset<row.length&&offset-literalStart<128){
				run=1;while(offset+run<row.length&&run<128&&row[offset+run]==row[offset])run++;
				if(run>=3)break;
				// at most 128 literal bytes: a pair taken at 127 made 129, whose count byte $80 is PackBits' no-op, and
				// the rest of the row decoded as garbage (one-row streaks in JPEGs a program opened as PICT)
				offset+=Math.min(run,128-(offset-literalStart));
			}
			int count=offset-literalStart;packed.write(count-1);packed.write(row,literalStart,count);
		}
		return packed.toByteArray();
	}

	private static byte[] sound(File file) throws Exception {
		return sound(SoundData.read(file));
	}

	//SoundEditor(内蔵サウンドエディタ)が、編集したSoundDataをMac本来のsnd
	//リソース形式のまま保存し直す際にも使うため、パッケージ内に公開しておく
	//(format 1・8bitモノラル無圧縮のsnd リソースとして書き出す)。
	static byte[] sound(SoundData sound) throws IOException {
		return sound(sound,60,0,0);
	}

	/** The same with the sound header's base note (MIDI) and loop in sample frames (0, 0: none): HyperCard's Harpsichord is at 67,
	 *  its flute loops 744-1300, and externals that play notes on a sampled-sound channel go from that base. */
	static byte[] sound(SoundData sound,int baseNote,int loopStart,int loopEnd) throws IOException {
		int frames=sound.frameCount();
		byte[] samples=new byte[frames];
		for(int frame=0;frame<frames;frame++){
			long sum=0;for(int channel=0;channel<sound.channels;channel++)sum+=sound.samples[frame*sound.channels+channel];
			int signed=(int)(sum/sound.channels);samples[frame]=(byte)Math.max(0,Math.min(255,(signed>>8)+128));
		}
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeShort(1);d.writeShort(1);d.writeShort(5);d.writeInt(0x80);d.writeShort(1);
		d.writeShort(0x8051);d.writeShort(0);d.writeInt(20);
		//sampleRateはMacの"Fixed"(16.16固定小数点)形式で書き込む。sampleRate*65536は
		//よくある44100Hzなどでも2^31を超えるため、double→intの直接キャストだと
		//(飽和により)Integer.MAX_VALUEに丸められてしまい、誤ったサンプルレートに
		//なってノイズのような再生になってしまう。long経由にして単純に下位32bitへ
		//切り詰めることで、符号なし32bit値として正しいビットパターンにする。
		d.writeInt(0);d.writeInt(samples.length);d.writeInt((int)Math.round(sound.sampleRate*65536.0));
		boolean loops=loopStart>=0&&loopEnd>loopStart&&loopEnd<=samples.length;
		d.writeInt(loops?loopStart:0);d.writeInt(loops?loopEnd:0);d.writeByte(0);d.writeByte(baseNote>0&&baseNote<128?baseNote:60);d.write(samples);
		return out.toByteArray();
	}

	static byte[] font(Rsrc.FontInfo info,BufferedImage image) throws IOException {
		int rowWords=(image.getWidth()+15)/16, height=info.fRectHeight>0?info.fRectHeight:image.getHeight();
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeShort(info.fontType);d.writeShort(info.firstChar);d.writeShort(info.lastChar);d.writeShort(info.widMax);
		d.writeShort(info.kernMax);d.writeShort(info.nDescent);d.writeShort(info.fRectWidth);d.writeShort(height);
		int count=info.lastChar-info.firstChar+3;
		d.writeShort((26+rowWords*2*height+count*2-16)/2);d.writeShort(info.ascent);d.writeShort(info.descent);d.writeShort(info.leading);d.writeShort(rowWords);
		for(int y=0;y<height;y++)for(int xb=0;xb<rowWords*2;xb++){
			int value=0;for(int bit=0;bit<8;bit++){int x=xb*8+bit;if(x<image.getWidth()&&y<image.getHeight()){
				int p=image.getRGB(x,y);if(((p>>>24)&255)>=128&&(p&0xFFFFFF)<0x808080)value|=1<<(7-bit);}}
			d.writeByte(value);
		}
		//an entry per code, the missing glyph's and the closing one (FontInfo from before Oct 23 2026: see StackStrikeFont.Strike.owWords)
		for(int loc:StackStrikeFont.Strike.locs(info,image.getWidth()))d.writeShort(loc);
		for(int ow:StackStrikeFont.Strike.owWords(info,image.getWidth()))d.writeShort(ow);
		return out.toByteArray();
	}

	private static byte[] addColor(Rsrc.addcolorClass color,StackTextEncoder encoder)throws IOException{
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		for(Rsrc.addcolorObjClass object:color.objList){
			//AddColorの"hide"で隠したオブジェクトは種類バイトの最上位ビットを立てて残す(HCResource.convertAddColorResource)
			int hidden=object.visible?0:0x80;
			if(object instanceof Rsrc.BtnObject){Rsrc.BtnObject o=(Rsrc.BtnObject)object;d.writeByte(1|hidden);d.writeShort(o.id);writeColor(d,o.bevel,o.color);}
			else if(object instanceof Rsrc.FldObject){Rsrc.FldObject o=(Rsrc.FldObject)object;d.writeByte(2|hidden);d.writeShort(o.id);writeColor(d,o.bevel,o.color);}
			else if(object instanceof Rsrc.RectObject){Rsrc.RectObject o=(Rsrc.RectObject)object;d.writeByte(3|hidden);writeRect(d,o.rect);writeColor(d,o.bevel,o.color);}
			else if(object instanceof Rsrc.PictObject){Rsrc.PictObject o=(Rsrc.PictObject)object;d.writeByte((o.file?5:4)|hidden);writeRect(d,o.rect);d.writeByte(o.transparent?1:0);byte[] name=encoded(o.name,encoder);d.writeByte(Math.min(255,name.length));d.write(name,0,Math.min(255,name.length));}
		}
		return out.toByteArray();
	}

	private static void writeColor(DataOutputStream d,int bevel,Color color)throws IOException{d.writeShort(bevel);Color c=color==null?Color.BLACK:color;d.writeShort(c.getRed()*257);d.writeShort(c.getGreen()*257);d.writeShort(c.getBlue()*257);}
	private static void writeRect(DataOutputStream d,Rectangle r)throws IOException{d.writeShort(r.y);d.writeShort(r.x);d.writeShort(r.y+r.height);d.writeShort(r.x+r.width);}

	private static byte[] palette(Rsrc.PlteClass p,StackTextEncoder encoder)throws IOException{
		ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
		d.writeInt(p.windowDef);d.writeShort(p.hiliteType);d.writeShort(p.btnType);d.writeShort(p.pictId);
		d.writeShort(p.pictHV.y);d.writeShort(p.pictHV.x);d.writeLong(0);d.writeShort(p.objList.size());
		for(Rsrc.plteBtnObject button:p.objList){writeRect(d,button.rect);d.writeShort(0);byte[] message=encoded(button.message,encoder);int length=Math.min(255,message.length);d.writeByte(length);d.write(message,0,length);if((out.size()&1)!=0)d.writeByte(0);}
		return out.toByteArray();
	}

	private static byte[] resourceFork(List<Resource> resources,StackTextEncoder encoder)throws IOException{
		ByteArrayOutputStream data=new ByteArrayOutputStream();DataOutputStream dd=new DataOutputStream(data);
		for(Resource r:resources){r.dataOffset=data.size();dd.writeInt(r.data.length);dd.write(r.data);}
		Map<String,List<Resource>> types=new LinkedHashMap<String,List<Resource>>();
		for(Resource r:resources)types.computeIfAbsent(r.type,k->new ArrayList<Resource>()).add(r);
		ByteArrayOutputStream names=new ByteArrayOutputStream();
		for(Resource r:resources)if(!r.name.isEmpty()){byte[] encoded=encoded(r.name,encoder);int length=Math.min(255,encoded.length);r.nameOffset=names.size();names.write(length);names.write(encoded,0,length);}
		int mapOffset=256+data.size(), typeCount=types.size();
		int typeListStart=28, typeRecordsStart=30, referencesStart=typeRecordsStart+typeCount*8;
		int referenceBytes=resources.size()*12, nameListStart=referencesStart+referenceBytes, mapLength=nameListStart+names.size();
		ByteBuffer map=ByteBuffer.allocate(mapLength).order(ByteOrder.BIG_ENDIAN);
		map.position(22);map.putShort((short)0);map.putShort((short)typeListStart);map.putShort((short)nameListStart);
		map.position(typeListStart);map.putShort((short)(typeCount-1));
		int referencePosition=referencesStart,typeIndex=0;
		for(Map.Entry<String,List<Resource>> entry:types.entrySet()){
			int pos=typeRecordsStart+typeIndex++*8;map.position(pos);map.put(four(entry.getKey()));map.putShort((short)(entry.getValue().size()-1));map.putShort((short)(referencePosition-typeRecordsStart+2));
			for(Resource r:entry.getValue()){map.position(referencePosition);map.putShort((short)r.id);map.putShort((short)r.nameOffset);map.put((byte)r.attributes);put3(map,r.dataOffset);map.putInt(0);referencePosition+=12;}
		}
		map.position(nameListStart);map.put(names.toByteArray());
		int dataOffset=256,dataLength=data.size();ByteBuffer header=ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
		header.putInt(dataOffset).putInt(mapOffset).putInt(dataLength).putInt(mapLength);System.arraycopy(header.array(),0,map.array(),0,16);
		ByteArrayOutputStream fork=new ByteArrayOutputStream();fork.write(header.array());fork.write(new byte[240]);fork.write(data.toByteArray());fork.write(map.array());return fork.toByteArray();
	}

	static byte[] appleDouble(byte[] resourceFork){
		byte[] finder=HCStackFileWriter.finderInfo();int offset=26+24;ByteBuffer b=ByteBuffer.allocate(offset+finder.length+resourceFork.length).order(ByteOrder.BIG_ENDIAN);
		b.putInt(0x00051607).putInt(0x00020000).put(new byte[16]).putShort((short)2);
		b.putInt(9).putInt(offset).putInt(finder.length);b.putInt(2).putInt(offset+finder.length).putInt(resourceFork.length);b.put(finder).put(resourceFork);return b.array();
	}

	private static byte[] encoded(String value,StackTextEncoder encoder){return encoder.encode(value==null?"":value);}
	private static byte[] four(String value){if(value.length()!=4)throw new IllegalArgumentException(value);return new byte[]{(byte)value.charAt(0),(byte)value.charAt(1),(byte)value.charAt(2),(byte)value.charAt(3)};}
	private static void put3(ByteBuffer b,int value){b.put((byte)(value>>16)).put((byte)(value>>8)).put((byte)value);}
}

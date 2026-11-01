import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import javax.swing.JFileChooser;


public class PictureFile {

	//-------------------------------
	//PPMファイル保存
	//-------------------------------
	//保存→読み込みで画像が崩れるので使ってはいけない
	public static void saveAsPpm(BufferedImage img, String path){
		if(img.getType()==BufferedImage.TYPE_INT_ARGB){
			//main
		}
		else if(img.getType()==BufferedImage.TYPE_INT_RGB){
			//bg
		}
		else return;
		
		if(path==null){
			JFileChooser chooser = new JFileChooser(new File("./"));
			chooser.setDialogTitle(PCARDFrame.pc.intl.getDialogText("Save File"));
			chooser.setSelectedFile(new File("./.ppm"));
			int ret = PCARD.showFileChooser(chooser, PCARDFrame.pc, true);
			if(ret != JFileChooser.APPROVE_OPTION){
				//保存しない
				return;
			}
			path = chooser.getSelectedFile().getName();
		}
		
		File file = new File(path);
		if(file.exists()&&!file.canWrite()){
			//書き込めない
			return;
		}

		try {
			BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(file));
			stream.write('P');
			stream.write('6');
			stream.write('\n');
			String str;
			str = Integer.toString(img.getWidth());
			for(int i=0; i<str.length(); i++){
				stream.write(str.charAt(i));
			}
			stream.write(' ');
			str = Integer.toString(img.getHeight());
			for(int i=0; i<str.length(); i++){
				stream.write(str.charAt(i));
			}
			stream.write('\n');
			stream.write('2');
			stream.write('5');
			stream.write('5');
			stream.write('\n');
			
			for(int y=0; y<img.getHeight(); y++){
				for(int x=0; x<img.getWidth(); x++){
					int c = img.getRaster().getDataBuffer().getElem(0,x+img.getWidth()*y);
					int r = (c>>16)&0xFF;
					int g = (c>>8)&0xFF;
					int b = (c>>0)&0xFF;
					stream.write(r);
					stream.write(g);
					stream.write(b);
				}
			}
			
			if(img.getType()==BufferedImage.TYPE_INT_ARGB){
				//アルファデータ
				stream.write('\n');
				stream.write('P');
				stream.write('5');
				stream.write('\n');
				str = Integer.toString(img.getWidth());
				for(int i=0; i<str.length(); i++){
					stream.write(str.charAt(i));
				}
				stream.write(' ');
				str = Integer.toString(img.getHeight());
				for(int i=0; i<str.length(); i++){
					stream.write(str.charAt(i));
				}
				stream.write('\n');
				stream.write('2');
				stream.write('5');
				stream.write('5');
				stream.write('\n');
				
				for(int y=0; y<img.getHeight(); y++){
					for(int x=0; x<img.getWidth(); x++){
						int c = img.getRaster().getDataBuffer().getElem(0,x+img.getWidth()*y);
						int a = (c>>24)&0xFF;
						stream.write(a);
					}
				}
			}
			
			stream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	

	//-------------------------------
	//PBM/PPMファイル読み込み
	//-------------------------------
	/** loadPbm through PictureCache (card and background pictures read at every card change) */
	static BufferedImage loadPbmCached(String path){
		try{
			return PictureCache.read(path, "pbm", f -> loadPbm(f.getPath()));
		}catch(IOException e){
			return null;
		}
	}

	public static BufferedImage loadPbm(String path){
		File file = new File(path);
		if(!file.exists() || file.isDirectory()) return null;
		
		BufferedInputStream stream;
		try {
			stream = new BufferedInputStream(new FileInputStream(path));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		}
		//読み終えたら閉じる(開いたままだとWindowsではそのファイルを移動・削除できない)
		try { return loadPbm(stream); }
		finally { try { stream.close(); } catch (IOException e) {} }
	}

	private static BufferedImage loadPbm(BufferedInputStream stream){
		//サイズ取得
		int ver = readPbmVer(stream);
		if(ver==0) return null;
		Dimension size = readPbmSize(stream);
		if(size==null) return null;
		int depth = 0;
		if(ver>=5){
			depth = readPpmDepth(stream);
		}
		
		byte[] b = null;
		if(ver==4){
			//PBM画像データ
			b = new byte[size.width/8*size.height];
			try {
				for(int i=0; i<b.length; i++){
					b[i]=(byte)stream.read();
				}
				stream.read();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}else if(ver==6){
			//PPM画像データ
			b = new byte[size.width*3*size.height];
			try {
				for(int i=0; i<b.length; i++){
					b[i]=(byte)stream.read();
				}
				stream.read();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		//マスクサイズ取得
		int maskver = readPbmVer(stream);
		Dimension masksize = null;
		int maskdepth = 0;
		if(maskver==4||maskver==5){
			masksize = readPbmSize(stream);
			if(maskver>=5){
				maskdepth = readPpmDepth(stream);
			}
		}

		//PBMマスク画像データ
		byte[] msk = null;
		if(maskver==4){
			//PBM画像データ
			msk = new byte[masksize.width/8*masksize.height];
			try {
				for(int i=0; i<msk.length; i++){
					msk[i]=(byte)stream.read();
				}
				stream.read();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}else if(maskver==5){
			//PPM画像データ
			msk = new byte[masksize.width*masksize.height];
			try {
				for(int i=0; i<msk.length; i++){
					msk[i]=(byte)stream.read();
				}
				stream.read();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		BufferedImage img = null;
		if(ver==4 /*&& maskver==4*/){
			//BufferedImageに変換
			img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
			img.getGraphics().setColor(Color.WHITE);
			img.getGraphics().fillRect(0, 0, size.width, size.height);
			DataBuffer db = img.getRaster().getDataBuffer();
			for(int v=0; v<size.height; v++){
				for(int h=0; h<size.width; h++){
					int pix=0x0, mpix=0xFF000000;
					switch(h%8){
					case 0: pix=(b[(v*size.width+h)/8]>>7)&0x01; break;
					case 1: pix=(b[(v*size.width+h)/8]>>6)&0x01; break;
					case 2: pix=(b[(v*size.width+h)/8]>>5)&0x01; break;
					case 3: pix=(b[(v*size.width+h)/8]>>4)&0x01; break;
					case 4: pix=(b[(v*size.width+h)/8]>>3)&0x01; break;
					case 5: pix=(b[(v*size.width+h)/8]>>2)&0x01; break;
					case 6: pix=(b[(v*size.width+h)/8]>>1)&0x01; break;
					case 7: pix=(b[(v*size.width+h)/8]   )&0x01; break;
					}
					if(pix==0) pix = 0x00FFFFFF;
	
					if(msk!=null){
						switch(h%8){
						case 0: mpix=(msk[(v*masksize.width+h)/8]>>7)&0x01; break;
						case 1: mpix=(msk[(v*masksize.width+h)/8]>>6)&0x01; break;
						case 2: mpix=(msk[(v*masksize.width+h)/8]>>5)&0x01; break;
						case 3: mpix=(msk[(v*masksize.width+h)/8]>>4)&0x01; break;
						case 4: mpix=(msk[(v*masksize.width+h)/8]>>3)&0x01; break;
						case 5: mpix=(msk[(v*masksize.width+h)/8]>>2)&0x01; break;
						case 6: mpix=(msk[(v*masksize.width+h)/8]>>1)&0x01; break;
						case 7: mpix=(msk[(v*masksize.width+h)/8]   )&0x01; break;
						}
						if(mpix!=0) mpix = 0xFF000000;
					}
					db.setElem(v*size.width+h, mpix+pix);
				}
			}
		}
		else if(ver==6 && /*maskver==5 &&*/ depth==255 /*&& maskdepth==255*/){
			//BufferedImageに変換
			img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
			img.getGraphics().setColor(Color.WHITE);
			img.getGraphics().fillRect(0, 0, size.width, size.height);
			DataBuffer db = img.getRaster().getDataBuffer();
			for(int v=0; v<size.height; v++){
				for(int h=0; h<size.width; h++){
					int pix=0x0, mpix=0xFF000000;
					pix=(b[v*size.width*3+h]<<16)+(b[v*size.width*3+h+1]<<8)+(b[v*size.width*3+h+2]);
	
					if(msk!=null && maskdepth==255){
						mpix=msk[v*masksize.width+h]<<24;
					}
					db.setElem(v*size.width+h, mpix+pix);
				}
			}
		}
		
		return img;
	}
	
	//PBMヘッダ読み込み
	private static int readPbmVer(BufferedInputStream stream){
		String tmpstr="";
		int ver = 0;
		
		try {
			//バージョン
			for(int i=0; i<2; i++){
				tmpstr += (char)stream.read();
			}
			if(tmpstr.equals("P4")) ver = 4;
			if(tmpstr.equals("P5")) ver = 5;
			if(tmpstr.equals("P6")) ver = 6;
			stream.read();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return ver;
	}
	
	//PBMヘッダ読み込み
	private static Dimension readPbmSize(BufferedInputStream stream){
		String tmpstr="";
		int width=0, height=0;
		
		try {
			//サイズ
			tmpstr="";
			while(true){
				char c = (char)stream.read();
				if(c==' ') break;
				if(c<'0'||c>'9') return null;
				tmpstr += c;
			}
			width = Integer.valueOf(tmpstr);

			tmpstr="";
			while(true){
				char c = (char)stream.read();
				if(c=='\n') break;
				tmpstr += c;
			}
			height = Integer.valueOf(tmpstr);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return new Dimension(width,height);
	}
	
	//PBMヘッダ読み込み
	private static int readPpmDepth(BufferedInputStream stream){
		String tmpstr="";
		int depth = 0;
		
		try {
			//バージョン
			for(int i=0; i<3; i++){
				tmpstr += (char)stream.read();
			}
			if(tmpstr.equals("255")) depth = 255;
			stream.read();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return depth;
	}
	
	
	
	//-------------------------------
	//PICTファイル読み込み
	//-------------------------------
	public static BufferedImage loadPICT(String path){
		if(path==null) return null; //リソースが無い(AddColor colorPict にスタックに無い PICT 名)
		File file = new File(path);
		if(!file.exists()) return null;
		
		BufferedInputStream stream;
		try {
			stream = new EndLimitedStream(new FileInputStream(path));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		}
		//読み終えたら閉じる(開いたままだとWindowsではそのファイルを移動・削除できない)
		try { return loadPICT(stream); }
		catch(ReadPastEnd e) { return null; }
		finally { try { stream.close(); } catch (IOException e) {} }
	}

	/** A picture's data as a PICT resource holds it (no 512-byte file header), or null when it cannot be read. */
	static BufferedImage loadPICT(byte[] picture){
		if(picture==null) return null;
		BufferedInputStream stream = new EndLimitedStream(new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(new byte[512]), new java.io.ByteArrayInputStream(picture)));
		try { return loadPICT(stream); }
		catch(ReadPastEnd e) { return null; }
		catch(RuntimeException e) { return null; }
	}

	/** A file that is no PICT read as one: its "frame" asks for thousands of rows long after the end of the file */
	private static final class ReadPastEnd extends RuntimeException {
		ReadPastEnd(){ super("not a PICT", null, false, false); }
	}
	/** Gives up (ReadPastEnd) after a million reads past the end of the file. Opening a stack's _stack.xml tried it as a PICT
	 *  first and decoded the XML's bogus rows for minutes; a truncated real PICT still ends with what it had */
	private static final class EndLimitedStream extends BufferedInputStream {
		private int pastEnd;
		EndLimitedStream(java.io.InputStream in){ super(in); }
		private int counted(int n){ if(n<0 && ++pastEnd>(1<<20)) throw new ReadPastEnd(); return n; }
		@Override public synchronized int read() throws IOException { return counted(super.read()); }
		@Override public synchronized int read(byte[] b, int off, int len) throws IOException { return counted(super.read(b, off, len)); }
	}

	private static BufferedImage loadPICT(BufferedInputStream stream){
		//サイズ取得
		int[] frameOrigin = new int[2]; //top, left of the picture's frame (its bits' rectangles are in that space)
		Dimension size = readPICTv1Size(stream,frameOrigin);
		Dimension picFrame = size;
		int[] resolution = {72, 72};
		int version = readPICTVer(stream);
		if(version==2){
			Dimension v2 = readPICTv2Size(stream,version,frameOrigin,resolution);
			size = v2!=null && v2.width==-1 && v2.height==-1 ? picFrame : v2;
		}
		if(size==null) return null;
		if(size.width<=0 || size.height<=0){
			System.out.println("PICT size error");
			return null;
		}
		//a frame of more than 64M pixels is no picture QuickDraw drew (a text file read as a PICT asked for 15904x29300, 1.8 GB)
		if((long)size.width*size.height > 64L*1024*1024) return null;

		//各データ
		BufferedImage img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
		boolean[] inverted = null; //the pixels a bits opcode inverts where the picture has not drawn: the card under them (see withInversion)
		BufferedImage jpegimg = null;
		int jpgHeight = 0;
		//QuickTime画像を描いた後の描画(「QuickTimeが必要です」の代替テキスト)は、実機のQuickTimeと同様に
		//クリップ領域を空にして描かない(次の ClipRgn オプコードで元に戻る)
		boolean quickTimeClipEmpty = false;

		Graphics2D g = img.createGraphics();
		//a version 1 frame that does not start at 0,0: the image is the frame, as DrawPicture maps it (PUGO_GT's CopyRight
		//PICT button, frame 78,175 to 306,189, showed its empty top-left part centred in the button)
		if(version==1) g.translate(-frameOrigin[1], -frameOrigin[0]);
		g.setColor(Color.BLACK);
		g.setBackground(Color.WHITE);
		//QuickDraw state the drawing opcodes keep: the pen (lines) and the text location, font, face and size
		int penX = 0, penY = 0, penWidth = 1, penHeight = 1;
		int textX = 0, textY = 0, fontId = 0, textFace = 0;
		int fontsize = 12;
		//opcode coordinates are in the picture frame's space: a version 2 frame's origin comes off them (version 1 translates g)
		final int originX = version==1 ? 0 : frameOrigin[1], originY = version==1 ? 0 : frameOrigin[0];
		int opColor = 0x000000; //OpColor: blend weight, addPin/subPin limits (a colour port starts with black)
		int backColor = 0xFFFFFF; //RGBBkCol: the colour transparent mode leaves out
		g.setFont(new Font("", Font.PLAIN, fontsize));

		//System.out.println("-------");
		while(true){
			int opcode;
			try {
				opcode = readOpcode2(stream,version);
			} catch (IOException e1) {
				break;
			}
			if(opcode==0x0000){ //アライメントずれの暫定措置
				opcode = readOpcode(stream,1);
			}
			boolean bitmap_flag=false;
			boolean packbits_flag=false;
			boolean fullcolor_flag=false;
			boolean rgnmask_flag=false;
			//System.out.println("opcode:0x"+ Integer.toHexString( opcode ));
			if(opcode==0x8200||opcode==0x8201){
				//CompressedQuickTime/UncompressedQuickTime: version, 3x3 Fixed matrix, matte/mask, mode, srcRect, accuracy,
				//ImageDescription(86バイト以上)、画像データ。以前は固定154バイトを飛ばしてJPEGと決めつけていたので、
				//マットや他のコーデック(raw/rle/png)のPICTで壊れていた(Earth 〜紅玉の森〜)
				int filelen = (readOpcode(stream,2)<<16)+readOpcode(stream,2);
				byte[] body = new byte[Math.max(0,filelen)];
				int got = 0;
				try {
					while(got<body.length){ int n = stream.read(body, got, body.length-got); if(n<0) break; got += n; }
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				BufferedImage qt = decodeQuickTimeOpcode(body, got);
				if(qt!=null){
					quickTimeClipEmpty = true;
					int[] at = quickTimeOrigin(body);
					if(at!=null && at[0]>=0 && at[1]>=0 && at[0]<size.width && at[1]<size.height){
						g.drawImage(qt, at[0], at[1], null);
					}else{
						jpegimg = qt;
						jpgHeight += qt.getHeight();
					}
				}
				continue;
			}
			else if(opcode==0x0090||opcode==0x0091){
				bitmap_flag = true;
			}
			else if(opcode==0x0098||opcode==0x0099){
				packbits_flag = true;
			}
			else if(opcode==0x009A||opcode==0x009B){
				packbits_flag = true;
				fullcolor_flag = true;
			}
			else if(opcode==0x00a1){
				//ロングコメント
				opcode = readOpcode(stream,2);
				int length = readOpcode(stream,2);
				byte[] b = new byte[length];
				for(int i=0;i<length;i++){b[i] = (byte)readOpcode(stream,1);}
				String s="";
				try {
					s = new String(b, "US-ASCII");
				} catch (UnsupportedEncodingException e) {
				}
				//System.out.println("long comment:"+s);
				continue;
			}
			else if(opcode==0x00ff||opcode==0xffff){
				break; //終了コード
			}
			else if(opcode==0x001e){
				continue; //デフォルトのハイライトカラー使用、らしい
			}
			else if(opcode==0x0001){
				//領域
				int length = readOpcode(stream,2);
				for(int i=0;i<length-2;i++){readOpcode(stream,1);}
				quickTimeClipEmpty = false;
				continue;
			}
			else if(opcode==0x001f){
				//OpColor
				int cr = readOpcode(stream,2);//r
				int cg = readOpcode(stream,2);//g
				int cb = readOpcode(stream,2);//b
				opColor = ((cr>>8)<<16)|((cg>>8)<<8)|(cb>>8);
				continue;
			}
			else if(opcode==0x001e){
				//defHilite
				continue;
			}
			else if(opcode==0x00a0){
				//ショートコメント
				readOpcode(stream,2);
				continue;
			}
			else if(opcode==0x0009){
				//ペンパターン
				int[]penptn = new int[8];
				for(int i=0;i<8;i++){penptn[i] = readOpcode(stream,1);}
				continue;
			}
			else if(opcode==0x0020 || opcode==0x0021 || opcode==0x0022 || opcode==0x0023){
				//Line (pnLoc, newPt) / LineFrom (newPt) / ShortLine (pnLoc, dh, dv) / ShortLineFrom (dh, dv): points are v,h in
				//the picture's coordinates (a version 2 frame's origin is subtracted, version 1 draws translated)
				if(opcode==0x0020 || opcode==0x0022){ penY = (short)readOpcode(stream,2); penX = (short)readOpcode(stream,2); }
				int toX, toY;
				if(opcode==0x0020 || opcode==0x0021){ toY = (short)readOpcode(stream,2); toX = (short)readOpcode(stream,2); }
				else{ int dh = (byte)readOpcode(stream,1), dv = (byte)readOpcode(stream,1); toX = penX+dh; toY = penY+dv; }
				//a pen of no width or height draws nothing (the credits PICT of Gradebook starts with PnSize 0,0 and a ShortLine)
				if(penWidth>0 && penHeight>0 && !quickTimeClipEmpty) g.drawLine(penX-originX, penY-originY, toX-originX, toY-originY);
				penX = toX; penY = toY;
				continue;
			}
			else if(opcode==0x0007){
				//PnSize (v, h)
				penHeight = readOpcode(stream,2);
				penWidth = readOpcode(stream,2);
				g.setStroke(new BasicStroke(Math.max(1,(penWidth+penHeight)/2), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
				continue;
			}
			else if(opcode==0x001a){
				//RGBcolor｜前景色（RGB）
				int cr = readOpcode(stream,2);
				int cg = readOpcode(stream,2);
				int cb = readOpcode(stream,2);
				g.setColor(new Color(cr/256,cg/256,cb/256));
				continue;
			}
			else if(opcode==0x001b){
				//RGBcolor｜背景色（RGB）
				int cr = readOpcode(stream,2);
				int cg = readOpcode(stream,2);
				int cb = readOpcode(stream,2);
				g.setBackground(new Color(cr/256,cg/256,cb/256));
				backColor = ((cr>>8)<<16)|((cg>>8)<<8)|(cb>>8);
				continue;
			}
			else if(opcode==0x002c){
				//FontName: data length, the old font number and the name (TxFont that follows picks the font)
				int length = readOpcode(stream,2);
				for(int i=0;i<length;i++){readOpcode(stream,1);}
				continue;
			}
			else if(opcode==0x0003){
				//TxFont
				fontId = (short)readOpcode(stream,2);
				continue;
			}
			else if(opcode==0x0004){
				//TxFace: one byte (a version 2 picture pads it to a word: the face is the high byte)
				textFace = version==2 ? readOpcode(stream,2)>>8 : readOpcode(stream,1);
				continue;
			}
			else if(opcode==0x0005 || opcode==0x0008 || opcode==0x0015 || opcode==0x0016){
				//TxMode, PnMode, PnLocHFrac, ChExtra
				readOpcode(stream,2);
				continue;
			}
			else if(opcode==0x000d){
				//TxSize (0 = the system size)
				fontsize = readOpcode(stream,2);
				if(fontsize<=0) fontsize = 12;
				continue;
			}
			else if(opcode==0x002d || opcode==0x002e){
				//LineJustify, glyphState
				int length = readOpcode(stream,2);
				for(int i=0;i<length;i++){readOpcode(stream,1);}
				continue;
			}
			else if(opcode>=0x0028 && opcode<=0x002b){
				//LongText (location v,h) / DHText / DVText / DHDVText: dh and dv are unsigned bytes from the previous text's
				//location (where it started, not where it ended); the location is the baseline's left end. Before, the point
				//was read h,v, the frame origin stayed in it and dh/dv went from the pen: the Gradebook credits' title and
				//"Disk copies and site licenses" sat off the balloon and on the picture's bits.
				if(opcode==0x0028){ textY = (short)readOpcode(stream,2); textX = (short)readOpcode(stream,2); }
				else if(opcode==0x0029) textX += readOpcode(stream,1);
				else if(opcode==0x002a) textY += readOpcode(stream,1);
				else{ textX += readOpcode(stream,1); textY += readOpcode(stream,1); }
				int count = readOpcode(stream,1);
				byte[] b = new byte[count];
				for(int i=0;i<count;i++){b[i] = (byte)readOpcode(stream,1);}
				String s = HCData.currentEncoding.decode(b).replaceAll("[\\x00-\\x1f]", "");
				if(!quickTimeClipEmpty && !s.isEmpty()){
					int style = ((textFace&1)!=0 ? Font.BOLD : 0) | ((textFace&2)!=0 ? Font.ITALIC : 0);
					Font font = new Font(Fonts68k.familyFor(fontId), style, fontsize);
					g.setFont(font);
					java.awt.RenderingHints.Key aa = java.awt.RenderingHints.KEY_TEXT_ANTIALIASING;
					g.setRenderingHint(aa, Fonts68k.textAntialiasHint());
					int x = textX-originX, y = textY-originY;
					g.drawString(s, x, y);
					if((textFace&4)!=0){ int w = g.getFontMetrics().stringWidth(s); g.drawLine(x, y+1, x+w-1, y+1); } //underline
				}
				continue;
			}
			else{
				//不明
				continue;
			}

			if(opcode==0x0091||opcode==0x0099||opcode==0x009B){
				rgnmask_flag = true;
			}
			
			if(jpegimg!=null){
				//Graphics2D g = img.createGraphics();
				g.drawImage(jpegimg, 0,jpgHeight-jpegimg.getHeight(),null);
			}
			int[] pixels = ((java.awt.image.DataBufferInt)img.getRaster().getDataBuffer()).getData();

			if(fullcolor_flag){
				readOpcode(stream,2);//ベースアドレス
				readOpcode(stream,2);
			}
			
			//A PixMap has the top bit of rowBytes set (DirectBitsRect always holds one); otherwise it is a BitMap, 1 bit black on
			//white. The opcode does not tell: a version 2 picture's PackBitsRect can hold a BitMap, whose bits were read as a
			//PixMap header (it came out all white), and a version 2 BitsRect a PixMap, whose header was read as bits.
			int rowBytesWord = readOpcode(stream,2);
			boolean pixMap = fullcolor_flag || (rowBytesWord&0x8000)!=0;
			int rowBytes = 0x3fff & rowBytesWord;
			
			int btop = (short)readOpcode(stream,2);//左上Ｙ座標
			int bleft = (short)readOpcode(stream,2);
			int bbottom = (short)readOpcode(stream,2);
			int bright = (short)readOpcode(stream,2);
			
			int bpp = 1;
			int packType = 0;
			int cmpCount = 1;
			int[] palette = new int[]{0xFFFFFFFF, 0xFF000000};
			//色テーブルのvalueがインデックスと違う(疎な)表: ピクセル値でエントリを引く。
			//K-0 FightersのMPバー(2色表のvalue 0と255、ピクセルは255=黒)は表の長さを超えるため位置では引けず白一色になっていた
			int[] palValue = null;
			
			if(pixMap){
				readOpcode(stream,2);//バージョン
				packType = readOpcode(stream,2);//圧縮タイプ
				readOpcode(stream,2);//圧縮サイズ
				readOpcode(stream,2);//圧縮サイズ
				readOpcode(stream,2);//水平解像度
				readOpcode(stream,2);//水平解像度
				readOpcode(stream,2);//垂直解像度
				readOpcode(stream,2);//垂直解像度
				readOpcode(stream,2);//ピクセルタイプ
				bpp = readOpcode(stream,2);//１ピクセルあたりのビット数
				cmpCount = readOpcode(stream,2);//コンポーネント数
				/*int cmpSize =*/ readOpcode(stream,2);//コンポーネントサイズ
				readOpcode(stream,2);//次のカラープレーンまでのオフセット
				readOpcode(stream,2);//次のカラープレーンまでのオフセット
				readOpcode(stream,2);//反転
				readOpcode(stream,2);//反転
				readOpcode(stream,2);//カラーテーブル識別番号
				readOpcode(stream,2);//カラーテーブル識別番号
				palette = null;
				if(!fullcolor_flag){
					readOpcode(stream,2);//カラーテーブルID
					readOpcode(stream,2);//カラーテーブルID
					readOpcode(stream,2);//カラーテーブルフラグ
					int palette_cnt = 1+readOpcode(stream,2);//登録されているパレット数
					palette = new int[palette_cnt];
					int[] pvals = new int[palette_cnt];
					boolean sparse = false;
					for(int i=0; i<palette_cnt; i++){
						int pidx = readOpcode(stream,2);//パレット番号
						pvals[i] = pidx;
						if(pidx!=i) sparse = true;
						int cr = readOpcode(stream,2)>>8;//パレット色データR
						int cg = readOpcode(stream,2)>>8;//パレット色データG
						int cb = readOpcode(stream,2)>>8;//パレット色データB
						palette[i] = 0xFF000000+(cr<<16)+(cg<<8)+cb;
					}
					if(sparse && bpp<=8){
						palValue = new int[1<<bpp];
						java.util.Arrays.fill(palValue,-1);
						for(int i=palette_cnt-1; i>=0; i--){
							if(pvals[i]>=0 && pvals[i]<palValue.length) palValue[pvals[i]] = i;
						}
					}
				}
			}

			if(rowBytes==0) rowBytes = (bright-bleft)*bpp/8;
			if(rowBytes<8) packbits_flag = false;
			
			int dtop = readOpcode(stream,2);//元解像度での左上Ｙ座標
			int dleft = readOpcode(stream,2);
			int dbottom = readOpcode(stream,2);
			int dright = readOpcode(stream,2);
			int ttop = (short)readOpcode(stream,2);//destination rectangle (the picture frame's coordinates)
			int tleft = (short)readOpcode(stream,2);
			int tbottom = (short)readOpcode(stream,2);
			int tright = (short)readOpcode(stream,2);
			//only the source rectangle's part of the bits is copied (when it lies in the bounds, as it should)
			int srcTop = dtop, srcLeft = dleft, srcBottom = dbottom, srcRight = dright;
			boolean clipSource = srcTop>=btop && srcLeft>=bleft && srcBottom<=bbottom && srcRight<=bright;
			boolean framed = ttop>=frameOrigin[0] && tleft>=frameOrigin[1];
			if(framed){
				//the bits go where the destination rectangle says in the frame: a frame that does not start at 0,0 (Brigantine's
				//AddColor title: 5,17, five bands down to 375), and tiles whose bounds all start at 0,0 (HyperLunacy vol2's
				//cover photo: six 144-pixel tiles, each drawn to its own place). Before, the source rectangle was taken as the
				//place: the last band (past the image's height) was moved to the top below, covering the first, and the tiles
				//all went to 0,0.
				int offTop = ttop-frameOrigin[0]+btop-dtop, offLeft = tleft-frameOrigin[1]+bleft-dleft;
				dbottom = tbottom-frameOrigin[0]; dright = tright-frameOrigin[1];
				dtop = offTop; dleft = offLeft;
			}
			
			if(dright>size.width || dbottom>size.height){ //無理矢理対応・・・
				dright -= dleft;
				dleft = 0;
				dbottom -= dtop;
				dtop = 0;
				if(dright>size.width || dbottom>size.height){
					break; //無理
				}
			}
			
			int trans_mode = readOpcode(stream,2);//転送モード
			if(trans_mode!=0){
				//System.out.println("trans_mode:"+trans_mode);
			}
			
			//マスク領域(BitsRgn/PackBitsRgn/DirectBitsRgn): the bits are drawn only inside it
			boolean[] mask = null;
			int maskTop = 0, maskLeft = 0, maskWidth = 0, maskHeight = 0;
			if(rgnmask_flag){
				int len = readOpcode(stream,2);
				maskTop = (short)readOpcode(stream,2);
				maskLeft = (short)readOpcode(stream,2);
				maskHeight = Math.max(0, (short)readOpcode(stream,2)-maskTop);
				maskWidth = Math.max(0, (short)readOpcode(stream,2)-maskLeft);
				if((long)maskWidth*maskHeight > 64L*1024*1024){ maskWidth = 0; maskHeight = 0; }
				mask = new boolean[maskWidth*maskHeight];
				if(len<=10){
					java.util.Arrays.fill(mask, true); //a rectangle
				}else{
					//inversion points: each (h,v) of a row's list inverts everything right of h from row v down
					int[] words = new int[(len-10)/2];
					for(int i=0; i<words.length; i++) words[i] = (short)readOpcode(stream,2);
					boolean[] row = new boolean[maskWidth];
					int i = 0, rowV = -1;
					while(i<words.length && words[i]!=32767){
						int v = words[i++];
						for(int y=Math.max(0,rowV-maskTop); y<Math.min(maskHeight,v-maskTop); y++) System.arraycopy(row, 0, mask, y*maskWidth, maskWidth);
						while(i<words.length && words[i]!=32767){
							for(int x=Math.max(0,words[i++]-maskLeft); x<maskWidth; x++) row[x] = !row[x];
						}
						i++;
						rowV = v;
					}
					//a region that does not end with every inversion undone: its last rows go on to the bottom
					for(int y=Math.max(0,rowV-maskTop); rowV>=0 && y<maskHeight; y++) System.arraycopy(row, 0, mask, y*maskWidth, maskWidth);
				}
			}
			
			//the bits: a row is PackBits-compressed in a PackBitsRect/Rgn and DirectBitsRect/Rgn unless rowBytes is under 8
			boolean packed = opcode!=0x0090 && opcode!=0x0091 && rowBytes>=8 && packType!=1 && packType!=2;
			int width = bright-bleft, height = bbottom-btop;
			int rowLength = (bpp==32 && packType==2) ? width*3 : rowBytes;
			int mode = trans_mode;
			boolean colorSource = pixMap;
			int[] maskOrigin = framed ? frameOrigin : new int[2];
			long dataBytes = 0;
			for(int v=0; v<height; v++){
				byte[] data = new byte[Math.max(rowLength, rowBytes)];
				if(packed){
					int count = rowBytes>250 ? readOpcode(stream,2) : readOpcode(stream,1);
					dataBytes += (rowBytes>250 ? 2 : 1)+count;
					byte[] packedRow = new byte[count];
					readBytes(stream, packedRow);
					unpackBits(packedRow, data, bpp==16 ? 2 : 1);
				}else{
					byte[] row = new byte[rowLength];
					readBytes(stream, row);
					System.arraycopy(row, 0, data, 0, rowLength);
					dataBytes += rowLength;
				}
				if(clipSource && (btop+v<srcTop || btop+v>=srcBottom)) continue;
				int y = v+dtop;
				//after a QuickTime image the clip is empty: its stand-in for Macs without QuickTime (Daisuki♡Ver2's "QuickTime PICT"
				//logo bits) is not drawn, as with the stand-in text
				if(y<0 || y>=size.height || jpegimg!=null || quickTimeClipEmpty) continue;
				for(int h=0; h<width; h++){
					if(clipSource && (bleft+h<srcLeft || bleft+h>=srcRight)) continue;
					int x = h+dleft;
					if(x<0 || x>=size.width) continue; //bits outside the frame
					if(mask!=null){
						int mx = x+maskOrigin[1]-maskLeft, my = y+maskOrigin[0]-maskTop;
						if(mx<0 || my<0 || mx>=maskWidth || my>=maskHeight || !mask[my*maskWidth+mx]) continue;
					}
					int rgb;
					if(bpp==16){
						int pix16 = ((0xFF&data[h*2])<<8)+(0xFF&data[h*2+1]);
						int cr = ((pix16>>10)&0x1F)*0xFF/0x1F;
						int cg = ((pix16>> 5)&0x1F)*0xFF/0x1F;
						int cb = ((pix16    )&0x1F)*0xFF/0x1F;
						rgb = (cr<<16)|(cg<<8)|cb;
					}
					else if(bpp==32){
						if(packType==2){ //unpacked RGB without the pad byte
							rgb = ((0xFF&data[h*3])<<16)|((0xFF&data[h*3+1])<<8)|(0xFF&data[h*3+2]);
						}else if(packed){ //component planes: (alpha,) red, green, blue
							int plane = cmpCount>=4 ? width : 0;
							rgb = ((0xFF&data[plane+h])<<16)|((0xFF&data[plane+h+width])<<8)|(0xFF&data[plane+h+width*2]);
						}else{ //unpacked xRGB
							rgb = ((0xFF&data[h*4+1])<<16)|((0xFF&data[h*4+2])<<8)|(0xFF&data[h*4+3]);
						}
					}
					else{
						int idx;
						if(bpp==1) idx = (data[h/8]>>(7-h%8))&0x01;
						else if(bpp==2) idx = (data[h/4]>>(6-h%4*2))&0x03;
						else if(bpp==4) idx = (data[h/2]>>(4-h%2*4))&0x0F; //4bit(16色)は1ニブル=4bit
						else idx = data[h]&0xFF;
						if(palette==null) continue;
						if(palValue!=null && idx<palValue.length && palValue[idx]>=0) idx = palValue[idx];
						if(idx>=palette.length) idx = 0;
						rgb = palette[idx]&0xFFFFFF;
					}
					int at = y*size.width+x, before = pixels[at];
					//a pixel the picture inverts stays the inverted card under a bit that leaves the destination as it is: BlackPict's
					//NEKO is its picture inverted in srcXor and then the picture in srcOr, solid black on white but the cat over black
					if(inverted!=null && inverted[at] && leavesDestination(rgb, mode, colorSource, opColor, backColor)) continue;
					boolean inverts = ((before>>>24)==0 || inverted!=null && inverted[at]) && invertsDestination(rgb, mode, colorSource, opColor, backColor);
					if(inverts && inverted!=null && inverted[at]){inverted[at] = false; pixels[at] = 0; continue;} //inverted twice: the card again
					if(inverts){if(inverted==null) inverted = new boolean[size.width*size.height]; inverted[at] = true;}
					int out = transferPixel(rgb, before, mode, colorSource, opColor, backColor);
					if(out!=0){pixels[at] = out; if(!inverts && inverted!=null && (out>>>24)==0xFF) inverted[at] = false;}
				}
			}
			if(version==2 && (dataBytes&1)!=0) readOpcode(stream,1); //version 2 opcodes start on even bytes
		}
		
		g.dispose();
		if(inverted==null) return toPictureFrame(img, picFrame, resolution);
		BufferedImage mask = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
		int[] maskPixels = ((java.awt.image.DataBufferInt)mask.getRaster().getDataBuffer()).getData();
		for(int i=0; i<inverted.length; i++) if(inverted[i]) maskPixels[i] = 0xFFFFFFFF;
		return withInversion(toPictureFrame(img, picFrame, resolution), toPictureFrame(mask, picFrame, resolution));
	}

	/** The image property {@link #withInversion} puts the inverted pixels under (opaque where the card is to be inverted). */
	public static final String INVERT_MASK = "hz.invertMask";
	/** The image property {@link #withInversion} puts the picture under without the inverted pixels (transparent there). */
	public static final String INVERT_BASE = "hz.invertBase";
	/**
	 * A picture whose bits opcodes invert what is under them (srcXor, notSrcXor, a 1-bit hilite) where it has drawn nothing
	 * itself: QuickDraw inverts the card there when the picture is drawn over it. The image keeps those pixels as they come out
	 * on white (picture windows, paint and the like show it as before) and carries the mask and the picture without them as
	 * properties, which {@link #invertingIcon} draws over the card: the UDI "What's TransfarMode" PICT buttons showed srcXor over
	 * black as plain black and notSrcXor over the dithered hill as a solid black box.
	 */
	private static BufferedImage withInversion(BufferedImage img, BufferedImage mask){
		if(mask.getWidth()!=img.getWidth() || mask.getHeight()!=img.getHeight()) return img;
		BufferedImage base = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for(int y=0; y<img.getHeight(); y++) for(int x=0; x<img.getWidth(); x++) base.setRGB(x, y, (mask.getRGB(x, y)>>>24)>=128 ? 0 : img.getRGB(x, y));
		java.util.Hashtable<String,Object> properties = new java.util.Hashtable<String,Object>();
		properties.put(INVERT_MASK, mask);
		properties.put(INVERT_BASE, base);
		return new BufferedImage(img.getColorModel(), img.getRaster(), img.isAlphaPremultiplied(), properties);
	}
	/** The inverted-pixel mask {@link #withInversion} gave {@code image}, or null. */
	public static BufferedImage invertMask(java.awt.Image image){
		if(!(image instanceof BufferedImage)) return null;
		Object mask = ((BufferedImage)image).getProperty(INVERT_MASK);
		return mask instanceof BufferedImage ? (BufferedImage)mask : null;
	}
	/**
	 * {@code icon} (a button's picture, possibly scaled) drawn the way QuickDraw draws a picture with inverting bits opcodes over
	 * the card: the picture without those pixels, then the card inverted under the mask (XOR, as OButton inverts a hilited icon).
	 * Icons of other images are returned as they are.
	 */
	public static javax.swing.Icon invertingIcon(final javax.swing.Icon icon, java.awt.Image image){
		final BufferedImage mask = invertMask(image);
		if(icon==null || mask==null) return icon;
		final BufferedImage base = (BufferedImage)((BufferedImage)image).getProperty(INVERT_BASE);
		return new javax.swing.Icon(){
			public int getIconWidth(){ return icon.getIconWidth(); }
			public int getIconHeight(){ return icon.getIconHeight(); }
			private BufferedImage inversion; //the mask at the size last drawn
			public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y){
				int w = getIconWidth(), h = getIconHeight();
				if(w<=0 || h<=0) return;
				BufferedImage inv = inversion;
				if(inv==null || inv.getWidth()!=w || inv.getHeight()!=h) inversion = inv = inversionImage(mask, w, h);
				Graphics2D g2 = (Graphics2D)g.create();
				try{
					g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					g2.drawImage(base, x, y, w, h, null);
					invert(g2, inv, x, y);
				}finally{ g2.dispose(); }
			}
		};
	}
	/**
	 * Black where {@code mask} is opaque (alpha 128 or more), transparent elsewhere, sampled to {@code w} x {@code h} (nearest
	 * pixel): what {@link #invert} XORs onto the card.
	 */
	public static BufferedImage inversionImage(BufferedImage mask, int w, int h){
		int mw = mask.getWidth(), mh = mask.getHeight();
		BufferedImage inv = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		int[] out = ((java.awt.image.DataBufferInt)inv.getRaster().getDataBuffer()).getData();
		int[] row = new int[mw];
		for(int v=0; v<h; v++){
			mask.getRGB(0, (int)((long)v*mh/h), mw, 1, row, 0, mw);
			for(int u=0; u<w; u++) if((row[(int)((long)u*mw/w)]>>>24)>=128) out[v*w+u] = 0xFF000000;
		}
		return inv;
	}
	/**
	 * Inverts the destination under the black pixels of {@code inversion} (from {@link #inversionImage}) in one XOR drawImage.
	 * The Metal pipeline flushes its queue and copies the surface for each XOR operation, so a fillRect per run or pixel took
	 * seconds a paint (BlackPictDemo's moving NEKO button, a hilited icon).
	 */
	public static void invert(Graphics2D g, BufferedImage inversion, int x, int y){
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setPaintMode();
			g2.setColor(Color.BLACK);
			g2.setXORMode(Color.WHITE);
			g2.drawImage(inversion, x, y, null);
		}finally{ g2.dispose(); }
	}
	/** Whether a bits opcode's pixel inverts the destination whatever it is (white becomes black and black white). */
	static boolean invertsDestination(int rgb, int mode, boolean colorSource, int opColor, int backColor){
		rgb &= 0xFFFFFF;
		mode = booleanTransferMode(mode, colorSource);
		if(mode==32 || mode==36) return false;
		return transferRgb(rgb, 0xFFFFFF, mode, opColor, backColor)==0 && transferRgb(rgb, 0x000000, mode, opColor, backColor)==0xFFFFFF;
	}
	/** Whether a bits opcode's pixel leaves the destination as it is, white and black alike (a white bit in srcOr, a black one in srcBic...). */
	static boolean leavesDestination(int rgb, int mode, boolean colorSource, int opColor, int backColor){
		rgb &= 0xFFFFFF;
		mode = booleanTransferMode(mode, colorSource);
		if(mode==32) return false;
		return transferRgb(rgb, 0xFFFFFF, mode, opColor, backColor)==0xFFFFFF && transferRgb(rgb, 0x000000, mode, opColor, backColor)==0;
	}

	/**
	 * An extended version 2 picture whose header gives a resolution other than 72 dpi records its drawing in the header's
	 * source rectangle at that resolution, while picFrame is the same rectangle at 72 dpi; DrawPicture maps the one onto the
	 * other (QuickDraw68k.playPicture). The picture is decoded in the header's space - where the bits and QuickTime opcodes
	 * place themselves - and here stretched to the frame with the same pixel sampling. シムレストラン's 50 dpi QuickTime
	 * pictures came out 80x80 instead of their 115x115 frame.
	 */
	private static BufferedImage toPictureFrame(BufferedImage img, Dimension picFrame, int[] resolution){
		if(resolution[0]==72 && resolution[1]==72) return img;
		if(picFrame==null || picFrame.width<=0 || picFrame.height<=0) return img;
		int sw = img.getWidth(), sh = img.getHeight(), dw = picFrame.width, dh = picFrame.height;
		if(sw==dw && sh==dh) return img;
		if((long)dw*dh > 64L*1024*1024) return img;
		int[] src = ((java.awt.image.DataBufferInt)img.getRaster().getDataBuffer()).getData();
		BufferedImage framed = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
		int[] dst = ((java.awt.image.DataBufferInt)framed.getRaster().getDataBuffer()).getData();
		for(int v=0; v<dh; v++){
			int sy = (int)((long)v*sh/dh);
			for(int h=0; h<dw; h++) dst[v*dw+h] = src[sy*sw+(int)((long)h*sw/dw)];
		}
		return framed;
	}
	
	/**
	 * One pixel of a bits opcode through its transfer mode, as QuickDraw68k (the VM's DrawPicture) combines it: the boolean
	 * modes on ink (black = all ones), the arithmetic ones on RGB, and a 1-bit BitMap's arithmetic modes mapped to boolean ones
	 * as in a 1-bit port (Inside Macintosh: Imaging, arithmetic modes in basic ports). Where the picture has drawn the pixel
	 * already the result is exact. Where it has not, the picture is drawn later over the card, so a mode whose result is the
	 * destination on both white and black leaves the pixel (transparent), and one that depends on it gives what it does on white
	 * (one that inverts the destination is also put in the picture's inversion mask: see withInversion).
	 * Only srcCopy, srcOr and transparent were told apart before; the others drew the source like srcCopy.
	 * @return the ARGB pixel, or 0 to leave the destination
	 */
	static int transferPixel(int rgb, int destination, int mode, boolean colorSource, int opColor, int backColor){
		rgb &= 0xFFFFFF;
		mode = booleanTransferMode(mode, colorSource);
		if((destination>>>24)==0xFF) return 0xFF000000|transferRgb(rgb, destination&0xFFFFFF, mode, opColor, backColor);
		if(mode==32){ //blend: the source over whatever is there, by the OpColor's weight
			int weight = (((opColor>>16)&0xFF)+((opColor>>8)&0xFF)+(opColor&0xFF))/3;
			return weight==0 ? 0 : (weight<<24)|rgb;
		}
		int onWhite = transferRgb(rgb, 0xFFFFFF, mode, opColor, backColor), onBlack = transferRgb(rgb, 0x000000, mode, opColor, backColor);
		if(onWhite==0xFFFFFF && onBlack==0x000000) return 0;
		return 0xFF000000|onWhite;
	}
	/** A bits opcode's mode as {@link #transferRgb} takes it: 0-7, or an arithmetic mode 32-39 for a colour source. */
	private static int booleanTransferMode(int mode, boolean colorSource){
		if(mode>=96 && mode<=103) mode -= 64; //maskMode combined with an arithmetic mode
		if((mode&64)!=0) mode &= ~64; //ditherCopy
		if(mode>=8 && mode<=15) mode -= 8; //pattern modes
		if(!colorSource){
			switch(mode){case 32: mode=0; break; case 33: case 37: mode=3; break; case 34: case 38: case 50: mode=2; break;
				case 35: case 36: case 39: mode=1; break; default: break;}
		}
		else if(mode==50) mode = 2; //hilite
		if(mode>7 && (mode<32 || mode>39)) mode = 0;
		return mode;
	}
	private static int transferRgb(int source, int destination, int mode, int opColor, int backColor){
		if(mode>=32 && mode<=39){
			if(mode==36) return source==backColor ? destination : source; //transparent
			int result = 0;
			for(int shift=16; shift>=0; shift-=8){
				int s = (source>>>shift)&0xFF, d = (destination>>>shift)&0xFF, op = (opColor>>>shift)&0xFF, r;
				switch(mode){
				case 32: r = (s*op+d*(255-op)+127)/255; break; //blend
				case 33: r = Math.min(op, s+d); break; //addPin
				case 34: r = (s+d)&0xFF; break; //addOver
				case 35: r = Math.max(op, d-s); break; //subPin: the source taken from the destination
				case 37: r = Math.max(s, d); break; //addMax
				case 38: r = (d-s)&0xFF; break; //subOver
				default: r = Math.min(s, d); break; //adMin
				}
				result |= r<<shift;
			}
			return result;
		}
		int s = ~source&0xFFFFFF, d = ~destination&0xFFFFFF, r;
		if((mode&4)!=0) s = ~s&0xFFFFFF; //notSrc...
		switch(mode&3){
		case 1: r = s|d; break; //srcOr
		case 2: r = s^d; break; //srcXor
		case 3: r = ~s&d; break; //srcBic
		default: r = s; break; //srcCopy
		}
		return ~r&0xFFFFFF;
	}

	/** Unpacks one PackBits row; unit 2 is the 16-bit pixel form, whose runs repeat words */
	private static void unpackBits(byte[] packed, byte[] row, int unit){
		int out = 0;
		for(int i=0; i<packed.length && out<row.length; ){
			int flag = packed[i++]&0xFF;
			if(flag<128){
				for(int j=0; j<(flag+1)*unit && i<packed.length; j++, i++) if(out<row.length) row[out++] = packed[i];
			}else if(flag>128){
				int count = 257-flag;
				for(int k=0; k<count; k++) for(int j=0; j<unit; j++) if(out<row.length && i+j<packed.length) row[out++] = packed[i+j];
				i += unit;
			}
		}
	}

	private static void readBytes(BufferedInputStream stream, byte[] b){
		try {
			int got = 0;
			while(got<b.length){ int n = stream.read(b, got, b.length-got); if(n<0) break; got += n; }
		} catch (IOException e) {}
	}

	//PICTヘッダ読み込み
	private static Dimension readPICTv1Size(BufferedInputStream stream, int[] origin){
		int width=0, height=0;
		try {
			//filler
			for(int i=0; i<512; i++){
				stream.read();
			}
			//v1 filesize
			for(int i=0; i<2; i++){
				stream.read();
			}
			//print size
			int top = (short)readOpcode(stream,2), left = (short)readOpcode(stream,2);
			height = (short)readOpcode(stream,2)-top;
			width = (short)readOpcode(stream,2)-left;
			origin[0] = top; origin[1] = left;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		return new Dimension(width,height);
	}
	
	/** QuickTime画像オプコード(0x8200/0x8201)の本体をデコードする。コーデックが扱えなければnull */
	private static BufferedImage decodeQuickTimeOpcode(byte[] body, int length){
		try{
			if(length<70) return null;
			int matrix = 2;
			int matteSize = MacApp68k.u4(body, matrix+36), maskSize = MacApp68k.u4(body, matrix+62);
			int cursor = matrix+66;
			if(matteSize>0) cursor += matteSize;
			if(maskSize>0) cursor += maskSize;
			if(cursor+86>length) return null;
			int idSize = MacApp68k.u4(body, cursor);
			if(idSize<86 || cursor+idSize>length) return null;
			byte[] description = java.util.Arrays.copyOfRange(body, cursor, cursor+idSize);
			int dataSize = MacApp68k.u4(description, 44);
			int imageData = cursor+idSize;
			if(dataSize<=0 || imageData+dataSize>length) dataSize = length-imageData;
			if(dataSize<=0) return null;
			int[] argb = QuickTimeImage68k.decode(description, java.util.Arrays.copyOfRange(body, imageData, imageData+dataSize));
			if(argb==null){
				System.out.println("PICT QuickTime data: codec "+QuickTimeImage68k.codecName(description)+" not decoded");
				return null;
			}
			int width = MacApp68k.u2(description,32), height = MacApp68k.u2(description,34);
			BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			bi.setRGB(0, 0, width, height, argb, 0, width);
			return bi;
		}catch(RuntimeException e){
			return null;
		}
	}

	/** QuickTime画像オプコードの行列が置く左上(tx,ty)。読めなければnull */
	private static int[] quickTimeOrigin(byte[] body){
		if(body.length<2+36) return null;
		double tx = MacApp68k.u4(body, 2+24)/65536.0, ty = MacApp68k.u4(body, 2+28)/65536.0;
		return new int[]{(int)Math.round(tx), (int)Math.round(ty)};
	}

	//PICTヘッダ読み込み
	private static int readPICTVer(BufferedInputStream stream){
		int version = 0;

		//バージョン(前にNOPオプコードの0が並ぶことがある: Infini-D 1.0.2の書き出しは30バイト)
		int opcode = readOpcode(stream,2);
		for(int nops=0; opcode==0x0000 && nops<128; nops++) opcode = readOpcode(stream,2);
		if(opcode==0x0011) {//バージョンオプコード2
			opcode = readOpcode(stream,2);
			if(opcode==0x02FF) version = 2;//バージョン2
		}
		else if(opcode==0x1101) version = 1;//バージョン1
		
		return version;
	}

	/** The version 2 header: its rectangle (the space the opcodes draw in) and, for the extended $FFFE form, the resolution */
	private static Dimension readPICTv2Size(BufferedInputStream stream, int version, int[] origin, int[] resolution){
		int top=0,left=0,bottom=0,right=0;
		
		//バージョン2ヘッダ
		int opcode = readOpcode(stream,version);
		if(opcode==0x0C00);//バージョン2ヘッダーオプコード
		else return null;
		int zahyou = readOpcode(stream,version);//座標位置指定形式
		if(zahyou == 0xfffe){
			readOpcode(stream,version);//予約
			resolution[0] = readOpcode(stream,version);//水平解像度
			readOpcode(stream,version);//水平解像度(小数点以下)
			resolution[1] = readOpcode(stream,version);//垂直解像度
			readOpcode(stream,version);//垂直解像度(小数点以下)
			top = readOpcode(stream,version);//左上Ｙ座標
			left = readOpcode(stream,version);//左上Ｘ座標
			bottom = readOpcode(stream,version);//右下Ｙ座標
			right = readOpcode(stream,version);//右下Ｘ座標
			readOpcode(stream,version);//予約（0）
			readOpcode(stream,version);//予約（0）
		}
		else if(zahyou == 0xffff){ //固定小数点座標
			readOpcode(stream,version);//予約(ffff)
			left = readOpcode(stream,version);//左上Ｘ標
			readOpcode(stream,version);//左上Ｘ座標(小数点以下)
			top = readOpcode(stream,version);//左上Ｙ座標
			readOpcode(stream,version);//左上Ｙ座標(小数点以下)
			right = readOpcode(stream,version);//右下Ｘ座標
			readOpcode(stream,version);//右下Ｘ座標(小数点以下)
			bottom = readOpcode(stream,version);//右下Ｙ座標
			readOpcode(stream,version);//右下Ｙ座標(小数点以下)
			readOpcode(stream,version);//予約（0）
			readOpcode(stream,version);//予約（0）
		}
		else{
			//another header form (AOL PicturePal's "PPheader": 0001 1744 then the bounds): QuickDraw goes by the picture's frame,
			//so the rest of the 24-byte header is skipped and the caller keeps the frame (-1,-1)
			for(int i=0; i<11; i++) readOpcode(stream,version);
			return new Dimension(-1,-1);
		}
		
		//座標は符号付き(枠が-1から始まるPICTもある)
		top=(short)top;left=(short)left;bottom=(short)bottom;right=(short)right;
		origin[0]=top; origin[1]=left;
		return new Dimension(right-left,bottom-top);
	}

	private static final int readOpcode(BufferedInputStream stream, int version){
		byte[] opcode = new byte[2];
		for(int i=0; i<version; i++){
			try {
				opcode[i] = (byte)stream.read();
			} catch (IOException e) {
			}
		}
		int iop = 0;
		if(version==1) iop = (opcode[0])&0xff;
		else if(version==2) iop = ((opcode[0]&0xff)<<8)+(opcode[1]&0xff);
		//System.out.println(". "+iop);
		return iop;
	}
	
	private static final int readOpcode2(BufferedInputStream stream, int version) throws IOException{
		byte[] opcode = new byte[2];
		for(int i=0; i<version; i++){
			opcode[i] = (byte)stream.read();
		}
		int iop = 0;
		if(version==1) iop = (opcode[0])&0xff;
		else if(version==2) iop = ((opcode[0]&0xff)<<8)+(opcode[1]&0xff);
		return iop;
	}
}

import java.awt.AlphaComposite;
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
			int ret = chooser.showSaveDialog(PCARDFrame.pc);
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
		File file = new File(path);
		if(!file.exists()) return null;
		
		BufferedInputStream stream;
		try {
			stream = new BufferedInputStream(new FileInputStream(path));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		}
		
		//サイズ取得
		Dimension size = readPICTv1Size(stream);
		int version = readPICTVer(stream);
		if(version==2) size = readPICTv2Size(stream,version);
		if(size==null) return null;
		if(size.width<=0 || size.height<=0){
			System.out.println("PICT size error");
			return null;
		}

		//各データ
		BufferedImage img = null;
		BufferedImage jpegimg = null;
		int jpgHeight = 0;

		Graphics2D g = null;
		int px = 0;
		int py = 10;
		int fontsize = 12;

		System.out.println("-------");
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
			System.out.println("opcode:0x"+ Integer.toHexString( opcode ));
			if(opcode==0x8200||opcode==0x8201){
				//JPEG
				int filelen = (readOpcode(stream,2)<<16)+readOpcode(stream,2);//00 00 53 60
				for(int i=0; i<100; i++){ //macファイルヘッダ
					readOpcode(stream,1);
				}
				/*int width2 =*/ readOpcode(stream,2);
				/*int height2 =*/ readOpcode(stream,2);
				for(int i=0; i<50; i++){ //macファイルヘッダ2
					readOpcode(stream,1);
				}
				byte[] b = new byte[filelen-154];
				try {
					stream.read(b, 0, filelen-154);
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				try {
					jpegimg = javax.imageio.ImageIO.read(new ByteArrayInputStream(b));
					jpgHeight+=jpegimg.getHeight();
				} catch (Exception e) {
					e.printStackTrace();
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
				System.out.println("long comment:"+s);
				continue;
			}
			else if(opcode==0x00ff||opcode==0xffff){
				break; //終了コード
			}
			else if(opcode==0x001e){
				continue; //?
			}
			else if(opcode==0x0001){
				//領域
				int length = readOpcode(stream,2);
				for(int i=0;i<length-2;i++){readOpcode(stream,1);}
				continue;
			}
			else if(opcode==0x001f){
				//OpColor
				readOpcode(stream,2);//r
				readOpcode(stream,2);//g
				readOpcode(stream,2);//b
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
			else if(opcode==0x0022){
				//ペン位置
				int penX = (short)readOpcode(stream,2);
				int penY = (short)readOpcode(stream,2);
				byte penX2 = (byte)readOpcode(stream,1);
				byte penY2 = (byte)readOpcode(stream,1);
				System.out.println("penX:"+penX+" penY:"+penY+" penX2:"+penX2+" penY2:"+penY2);
				if(img==null) {
					img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
					g = img.createGraphics();
				}
				g.drawLine(penX, penY, penX+penX2, penY+penY2);
				px = penX+penX2;
				py = penY+penY2;
				continue;
			}
			else if(opcode==0x0007){
				//ペンサイズ
				int penSizeX = readOpcode(stream,2);
				int penSizeY = readOpcode(stream,2);
				g.setStroke(new BasicStroke((penSizeX+penSizeY)/2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
				continue;
			}
			else if(opcode==0x002c){
				//フォント (2+データ長)
				int length = readOpcode(stream,2);
				byte[] b = new byte[length];
				for(int i=0;i<length;i++){b[i] = (byte)readOpcode(stream,1);}
				String s="";
				try {
					s = new String(b, "SJIS");
				} catch (UnsupportedEncodingException e) {
				}
				System.out.println("font:'"+s+"' at "+px+","+py);
				//g.drawString(s,px,py);
				continue;
			}
			else if(opcode==0x0003){
				//書体ID
				readOpcode(stream,2);
				continue;
			}
			else if(opcode==0x0004){
				//文字形状
				readOpcode(stream,2);
				continue;
			}
			else if(opcode==0x000d){
				//文字サイズ
				fontsize = readOpcode(stream,2);
				g.setFont(new Font("", 0, fontsize));
				continue;
			}
			else if(opcode==0x002e){
				//? 文字に関する何か
				int length = readOpcode(stream,2);
				for(int i=0;i<length;i++){readOpcode(stream,1);}
				px=0;py=0;//###
				continue;
			}
			else if(opcode==0x0028){
				//文字列描画
				int ddx = readOpcode(stream,2);
				int ddy = readOpcode(stream,2);
				int count = readOpcode(stream,1);
				byte[] b = new byte[count];
				for(int i=0;i<count;i++){b[i] = (byte)readOpcode(stream,1);}
				String s="";
				try {
					s = new String(b, "SJIS");
				} catch (UnsupportedEncodingException e) {
				}
				System.out.println("drawString28:'"+s+"' at "+(ddx)+","+(ddy));
				do{
					String s2 = s;
					if((s.indexOf('\r')>-1)) s2 = s.substring(0,s.indexOf('\r'));
					g.drawString(s2,ddx,ddy+fontsize);
					s = s.substring(s.indexOf('\r')+1);
					ddy = ddy+fontsize;
				}while((s.indexOf('\r')>-1));
				continue;
			}
			else if(opcode==0x0029){
				//文字列描画（水平相対座標）
				int dx = readOpcode(stream,1);
				int count = readOpcode(stream,1);
				byte[] b = new byte[count];
				for(int i=0;i<count;i++){b[i] = (byte)readOpcode(stream,1);}
				String s="";
				try {
					s = new String(b, "SJIS");
				} catch (UnsupportedEncodingException e) {
				}
				System.out.println("drawString29:'"+s+"' at "+(px+dx)+","+(py));
				do{
					String s2 = s;
					if((s.indexOf('\r')>-1)) s2 = s.substring(0,s.indexOf('\r'));
					g.drawString(s2,px+dx,py+fontsize);
					s = s.substring(s.indexOf('\r')+1);
					py = py+fontsize;
				}while((s.indexOf('\r')>-1));
				continue;
			}
			else if(opcode==0x002a){
				//文字列描画（垂直相対座標）
				int dy = readOpcode(stream,1);
				int count = readOpcode(stream,1);
				byte[] b = new byte[count];
				for(int i=0;i<count;i++){b[i] = (byte)readOpcode(stream,1);}
				String s="";
				try {
					s = new String(b, "SJIS");
				} catch (UnsupportedEncodingException e) {
				}
				System.out.println("drawString2a:'"+s+"' at "+(px)+","+(py+dy));
				do{
					String s2 = s;
					if((s.indexOf('\r')>-1)) s2 = s.substring(0,s.indexOf('\r'));
					g.drawString(s2,px,py+dy+fontsize);
					s = s.substring(s.indexOf('\r')+1);
					py = py+fontsize;
				}while((s.indexOf('\r')>-1));
				continue;
			}
			else if(opcode==0x002b){
				//文字列描画（水平垂直相対座標）
				int dx = readOpcode(stream,1);
				int dy = readOpcode(stream,1);
				int count = readOpcode(stream,1);
				byte[] b = new byte[count];
				for(int i=0;i<count;i++){b[i] = (byte)readOpcode(stream,1);}
				String s="";
				try {
					s = new String(b, "SJIS");
				} catch (UnsupportedEncodingException e) {
				}
				System.out.println("drawString2b:'"+s+"' at "+(px+dx)+","+(py+dy));
				do{
					String s2 = s;
					if((s.indexOf('\r')>-1)) s2 = s.substring(0,s.indexOf('\r'));
					g.drawString(s2,px+dx,py+dy+fontsize);
					s = s.substring(s.indexOf('\r')+1);
					py = py+fontsize;
				}while((s.indexOf('\r')>-1));
				continue;
			}
			else{
				//不明
				continue;
			}

			if(opcode==0x0091||opcode==0x0099||opcode==0x009B){
				rgnmask_flag = true;
			}
			
			if(img==null) {
				img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
				g = img.createGraphics();
				g.setColor(new Color(255,255,255));
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
				g.fillRect(0,0, size.width, size.height);
			}
			if(jpegimg!=null){
				//Graphics2D g = img.createGraphics();
				g.drawImage(jpegimg, 0,jpgHeight-jpegimg.getHeight(),null);
			}
			DataBuffer db = img.getRaster().getDataBuffer();

			if(fullcolor_flag){
				readOpcode(stream,2);//ベースアドレス
				readOpcode(stream,2);
			}
			
			int rowBytes = 0x3fff & readOpcode(stream,2);
			
			int btop = readOpcode(stream,2);//左上Ｙ座標
			int bleft = readOpcode(stream,2);
			int bbottom = readOpcode(stream,2);
			int bright = readOpcode(stream,2);
			
			int bpp = 1;
			int[] palette = null;
			if(version==1 || jpegimg!=null){
				palette = new int[]{0xFFFFFFFF, 0xFF000000};
			}
			
			if(!bitmap_flag&&version==2&&jpegimg==null){
				readOpcode(stream,2);//バージョン
				readOpcode(stream,2);//圧縮タイプ
				readOpcode(stream,2);//圧縮サイズ
				readOpcode(stream,2);//圧縮サイズ
				readOpcode(stream,2);//水平解像度
				readOpcode(stream,2);//水平解像度
				readOpcode(stream,2);//垂直解像度
				readOpcode(stream,2);//垂直解像度
				readOpcode(stream,2);//ピクセルタイプ
				bpp = readOpcode(stream,2);//１ピクセルあたりのビット数
				/*int byteoff =*/ readOpcode(stream,2);//次のピクセルまでのバイトオフセット
				/*int pixelbytes =*/ readOpcode(stream,2);//コンポーネントサイズ
				readOpcode(stream,2);//次のカラープレーンまでのオフセット
				readOpcode(stream,2);//次のカラープレーンまでのオフセット
				readOpcode(stream,2);//反転
				readOpcode(stream,2);//反転
				readOpcode(stream,2);//カラーテーブル識別番号
				readOpcode(stream,2);//カラーテーブル識別番号
				if(!fullcolor_flag){
					readOpcode(stream,2);//カラーテーブルID
					readOpcode(stream,2);//カラーテーブルID
					readOpcode(stream,2);//カラーテーブルフラグ
					int palette_cnt = 1+readOpcode(stream,2);//登録されているパレット数
					//if(palette_cnt > 256) return null;
					palette = new int[palette_cnt];
					for(int i=0; i<palette_cnt; i++){
						/*int pidx =*/ readOpcode(stream,2);//パレット番号
						int cr = readOpcode(stream,2)>>8;//パレット色データR
						int cg = readOpcode(stream,2)>>8;//パレット色データG
						int cb = readOpcode(stream,2)>>8;//パレット色データB
						palette[i] = 0xFF000000+(cr<<16)+(cg<<8)+cb;
					}
				}
			}

			if(rowBytes==0) rowBytes = (bright-bleft)*bpp/8;
			if(rowBytes<8) packbits_flag = false;
			
			int dtop = readOpcode(stream,2);//元解像度での左上Ｙ座標
			int dleft = readOpcode(stream,2);
			int dbottom = readOpcode(stream,2);
			int dright = readOpcode(stream,2);
			
			if(dright>size.width || dbottom>size.height){ //無理矢理対応・・・
				dright -= dleft;
				dleft = 0;
				dbottom -= dtop;
				dtop = 0;
				if(dright>size.width || dbottom>size.height){
					break; //無理
				}
			}
			
			readOpcode(stream,2);//72dpiでの左上Ｙ座標
			readOpcode(stream,2);
			readOpcode(stream,2);
			readOpcode(stream,2);
			
			int trans_mode = readOpcode(stream,2);//転送モード
			if(trans_mode!=0){
				//System.out.println("trans_mode:"+trans_mode);
			}
			
			if(rgnmask_flag){
				//System.out.println("rgnmask_flag:"+rgnmask_flag);
				int len = readOpcode(stream,2);
				readOpcode(stream,2);//top
				readOpcode(stream,2);//left
				readOpcode(stream,2);//bottom
				readOpcode(stream,2);//right
				if(len==10){
					for(int y=0; y<size.height; y++){
						for(int x=0; x<size.width; x++){
							db.setElem(y*size.width+x, 0xFFFFFFFF);
						}
					}
				}
				//for(int i=0; i<len-10;i++){readOpcode(stream,1);}
				
				//リージョンフォーマット
				//
				//領域の輪郭の線のデータが入っている
				//上から見て行って、輪郭に含まれたら1、もう一度含まれたら0
				//ということをやればビットマップデータに出来る。
				//
				//最初のwordで上からの行数を示す (飛ばした行は上の行と同じ)
				//(繰り返し){
				//  次のwordで輪郭部分の開始位置
				//  次のwordで輪郭部分の終了位置
				//}
				//32767でライン終了
				int scanline = 0;
				for(int i=0; i<len-10;){
					int lastscanline = scanline;
					scanline = readOpcode(stream,2);
					i+=2;
					for(int yy=lastscanline+1; yy<scanline; yy++){
						if(yy<size.height){
							for(int xx=0; xx<size.width; xx++){
								db.setElem(yy*size.width+xx, db.getElem((yy-1)*size.width+xx));
							}
						}
					}
					int x=0;
					while(i<len-10){
						int xstart = readOpcode(stream,2);
						i+=2;
						if(xstart==32767) {
							if(scanline<size.height && scanline>0){
								for(; x<size.width; x++){
									db.setElem(scanline*size.width+x, db.getElem((scanline-1)*size.width+x));
								}
							}
							break;
						}
						int xend = readOpcode(stream,2);
						i+=2;
						if(scanline<size.height){
							for(; x<xstart; x++){
								if(scanline>0){
									db.setElem(scanline*size.width+x, db.getElem((scanline-1)*size.width+x));
								}
							}
						}
						if(scanline<size.height){
							for(; x<xend; x++){
								if(scanline==0){
									db.setElem(scanline*size.width+x, 0xFFFFFFFF);
								}else{
									if(db.getElem((scanline-1)*size.width+x)==0x00000000){
										db.setElem(scanline*size.width+x, 0xFFFFFFFF);
									}
								}
							}
						}
					}
				}
			}
			
			if(bitmap_flag&&!packbits_flag){
				//無圧縮BitMap
				for(int v=0; v<bbottom-btop; v++){
					byte[] data = new byte[rowBytes];
					for(int i=0; i<rowBytes; i++){
						try { data[i] = (byte)stream.read(); }
						catch (IOException e) {}
					}
					for(int h=0; h<bright-bleft; h++){
						int pix = (data[h/8]>>(7-(h%8)))&0x01;
						if(pix!=0) {
							if(trans_mode==1) continue; //#srcOr
							else pix=0xFFFFFFFF;
						}
						else if( pix==0 && trans_mode==3 ){ //#srcBic
							pix=0xFFFFFFFF;
						}
						if(rgnmask_flag){
							if(db.getElem(v*size.width+h)==0x00000000){
								continue;
							}
						}
						db.setElem((v/*+btop*/)*size.width+(h/*+bleft*/), pix);
					}
				}
			}
			else if(bitmap_flag&&packbits_flag){
				//圧縮BitMap
				int dlen = 1;
				if(rowBytes>=251) dlen=2;
				for(int v=0; v<bbottom-btop; v++){
					if(v+dtop>=size.height) break;
					
					byte[] data = new byte[rowBytes];
					int offset = 0;
					//packBitsを展開
					int packsize = readOpcode(stream,dlen);
					for(int i=0; i<packsize; i++){
						int dsize = readOpcode(stream,1);
						if(dsize>=128) {
							//同じデータが連続する場合
							dsize = 256-dsize+1;
							int src = readOpcode(stream,1);
							for(int j=0; j<dsize; j++){
								data[j+offset] = (byte)src;
							}
							offset += dsize;
						}
						else {
							//データそのまま
							dsize++;
							for(int j=0; j<dsize; j++){
								try { data[j+offset] = (byte)stream.read(); }
								catch (IOException e) {}
							}
							offset += dsize;
						}
					}
					for(int h=0; h<bright-bleft; h++){
						int pix = (data[h/8]>>(7-(h%8)))&0x01;
						if(pix!=0){
							if(trans_mode==1) continue; //#srcOr
							else pix=0xFFFFFFFF;
						}
						else if( pix==0 && trans_mode==3 ){ //#srcBic
							pix=0xFFFFFFFF;
						}
						if(rgnmask_flag){
							if(db.getElem(v*size.width+h)==0x00000000){
								continue;
							}
						}
						db.setElem((v+dtop)*size.width+(h+dleft), pix);
					}
				}
			}
			else if(!bitmap_flag&&packbits_flag){
				System.out.println("packbits-pixmap");
				System.out.println("bpp:"+bpp);
				System.out.println("ispalette:"+(palette!=null));
				System.out.println("rowBytes:"+rowBytes);
				//圧縮PixMap
				int dlen = 1;
				if(rowBytes>=251) dlen=2;
				for(int v=0; v<bbottom-btop; v++){
					//System.out.println(v+"<"+(bbottom-btop));
					byte[] data = new byte[rowBytes];
					int offset = 0;
					//packBitsを展開
					int packsize = readOpcode(stream,dlen);
					//System.out.println("packsize:"+packsize);
					if(bpp==16){ //16bitのpackbitsは違うらしい
						for(int i=0; i<packsize; i++){
							int dsize = readOpcode(stream,1);
							if(dsize>=128) {	
								//同じデータが連続する場合
								//System.out.println("renzoku dsize:"+dsize);
								dsize = 256-dsize+1;
								int src1 = readOpcode(stream,1);
								int src2 = readOpcode(stream,1);
								i+=2;
								for(int j=0; j<dsize*2 && j+offset<data.length; j++){
									data[j+offset] = (byte)src1;
									j++;
									data[j+offset] = (byte)src2;
								}
								offset += dsize*2;
							}
							else {
								//データそのまま
								//System.out.println("sonomama dsize:"+dsize);
								dsize++;
								for(int j=0; j<dsize*2; j++){
									if(rowBytes<=j+offset){
										//System.out.println("over rowBytes2!");
										continue;
									}
									try { data[j+offset] = (byte)stream.read();i++; }
									catch (IOException e) {}
									//System.out.println("data["+(j+offset)+"]:"+data[j+offset]);
								}
								offset += dsize*2;
							}
						}
					}
					else{ //16bit以外のpackbits
						for(int i=0; i<packsize; i++){
							int dsize = readOpcode(stream,1);
							if(dsize>=128) {
								//System.out.println("renzoku dsize:"+dsize);
								//同じデータが連続する場合
								dsize = 256-dsize+1;
								int src = readOpcode(stream,1);
								//System.out.println("src:"+src);
								i++;
								if(rowBytes<dsize+offset){
									//System.out.println("over rowBytes1!");
									continue;
								}
								for(int j=0; j<dsize && j+offset<data.length; j++){ data[j+offset] = (byte)src; }
								offset += dsize;
							}
							else {
								//データそのまま
								//System.out.println("sonomama dsize:"+dsize);
								dsize++;
								for(int j=0; j<dsize; j++){
									if(rowBytes<=j+offset){
										//System.out.println("over rowBytes2!");
										continue;
									}
									try { data[j+offset] = (byte)stream.read();i++; }
									catch (IOException e) {}
									//System.out.println("data["+(j+offset)+"]:"+data[j+offset]);
								}
								offset += dsize;
							}
						}
					} //packbits終了
					
					if(v+dtop>=size.height) {
						//System.out.println("v+dtop("+(v+dtop)+") > "+size.height);
						break;
					}
					for(int h=0; h<bright-bleft; h++){
						if(h+dleft>=size.width) break;
						int pix = 0;
						int idx = 0;
						if(bpp==1){
							idx = (data[h/8]>>(8-(h%8+1)))&0x01;
						}
						else if(bpp==2){
							idx = (data[h/4]>>(8-(h%4*2+2)))&0x03;
						}
						else if(bpp==4){
							idx = (data[h/2]>>(8-(h%2*4+4)))&0x07;
						}
						else if(bpp==8){
							idx = (data[h])&0xFF;
						}
						else if(bpp==16){
							int pix16 = ((0xFF&data[h*2])<<8)+(0xFF&data[h*2+1]);
							int cr = (pix16>>10)&0x1F;
							int cg = (pix16>> 5)&0x1F;
							int cb = (pix16>> 0)&0x1F;
							cr = cr*0xFF/0x1F;
							cg = cg*0xFF/0x1F;
							cb = cb*0xFF/0x1F;
							pix = 0xFF000000|(cr<<16)|(cg<<8)|cb;
						}
						else if(bpp==32){
							pix = 0xFF000000|((0xFF&data[h])<<16)|((0xFF&data[h+(bright-bleft)])<<8)|(0xFF&(data[h+(bright-bleft)*2]));
						}
						if(fullcolor_flag){
							if(trans_mode==36 && pix==0xFFFFFFFF){
								continue;
							}
							else if( pix==0x00000000 && trans_mode==3 ){ //#srcBic
								pix=0xFFFFFFFF;
							}
						}else if(palette!=null){
							if(idx>=palette.length) idx = 0;
							pix = palette[idx];
							if((trans_mode==36 || trans_mode==1) && pix==0xFFFFFFFF){ //#srcOR ,transparent
								continue;
							}
							else if( pix==0x00000000 && trans_mode==3 ){ //#srcBic
								pix=0xFFFFFFFF;
							}
						}else{
							if(pix!=0) {
								if(trans_mode==1) continue; //#srcOr
								else pix=0xFFFFFFFF;
							}
							else if( pix==0 && trans_mode==3 ){ //#srcBic
								pix=0xFFFFFFFF;
							}
						}
						if(rgnmask_flag){
							if(db.getElem((v+dtop)*size.width+(h+dleft))==0x00000000){
								continue;
							}
						}
						if(jpegimg==null){
							//if((v+dtop)%100==5){
								//System.out.println((h+dleft)+","+(v+dtop)+" "+Integer.toHexString(pix));
							//}
							db.setElem((v+dtop)*size.width+(h+dleft), pix);
						}
					}
				}
			}
		}
		
		return img;
	}
	
	//PICTヘッダ読み込み
	private static Dimension readPICTv1Size(BufferedInputStream stream){
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
			readOpcode(stream,2);
			readOpcode(stream,2);
			height = readOpcode(stream,2);
			width = readOpcode(stream,2);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		return new Dimension(width,height);
	}
	
	//PICTヘッダ読み込み
	private static int readPICTVer(BufferedInputStream stream){
		int version = 0;

		//バージョン
		int opcode = readOpcode(stream,2);
		if(opcode==0x0011) {//バージョンオプコード2
			opcode = readOpcode(stream,2);
			if(opcode==0x02FF) version = 2;//バージョン2
		}
		else if(opcode==0x1101) version = 1;//バージョン1
		
		return version;
	}

	private static Dimension readPICTv2Size(BufferedInputStream stream, int version){
		int top=0,left=0,bottom=0,right=0;
		
		//バージョン2ヘッダ
		int opcode = readOpcode(stream,version);
		if(opcode==0x0C00);//バージョン2ヘッダーオプコード
		else return null;
		int zahyou = readOpcode(stream,version);//座標位置指定形式
		if(zahyou == 0xfffe){
			readOpcode(stream,version);//予約
			readOpcode(stream,version);//水平解像度
			readOpcode(stream,version);//水平解像度
			readOpcode(stream,version);//垂直解像度
			readOpcode(stream,version);//垂直解像度
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

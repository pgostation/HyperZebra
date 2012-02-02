import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.DataInputStream;
import java.io.File;
//import java.io.FileOutputStream;
//import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import javax.imageio.ImageIO;


public class HCData {
	public static boolean readDataFork(DataInputStream dis, OStack stack){
		//System.out.println("==readDataFork==");
		
		boolean result = false;
		boolean isReadStack = false;
		int aligncnt = 0; //for debug
		int errCount = 0;
		
		try {
			while(dis.available()>0){
				int blockSize = readCode(dis, 4);
				int typeCode = readCode(dis, 4);

				if(stack.totalSize>0){
					try {
						stack.bar.setValue(stack.barOffset+(73*(stack.totalSize-dis.available()))/stack.totalSize);
						stack.bar.paintImmediately(stack.bar.getBounds());
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				while(true){
					String typeStr = "";
					typeStr += (char)(0xFF&(typeCode>>24));
					typeStr += (char)(0xFF&(typeCode>>16));
					typeStr += (char)(0xFF&(typeCode>>8));
					typeStr += (char)(0xFF&(typeCode>>0));
					
					if(typeStr.equals("STAK")){
						if(isReadStack){
							break;
						}
						HCStackDebug.blockstart(typeStr);
						isReadStack = true;
						stack.bar.setString("Converting Stack Data...");
						stack.readStackBlock(dis, blockSize);
					}
					else if(typeStr.equals("STBL")){
						stack.bar.setString("Converting Style Data...");
						HCStackDebug.blockstart(typeStr);
						stack.readStyleBlock(dis, blockSize);
					}
					else if(typeStr.equals("FTBL")){
						stack.bar.setString("Converting Font Data...");
						HCStackDebug.blockstart(typeStr);
						stack.readFontBlock(dis, blockSize);
					}
					else if(typeStr.equals("LIST")){
						stack.bar.setString("Converting List Data...");
						HCStackDebug.blockstart(typeStr);
						stack.readListBlock(dis, blockSize);
					}
					else if(typeStr.equals("PAGE")){
						stack.bar.setString("Converting Page Data...");
						HCStackDebug.blockstart(typeStr);
						stack.readPageBlock(dis, blockSize);
					}
					else if(typeStr.equals("CARD")){
						HCStackDebug.blockstart(typeStr);
						stack.bar.setString("Converting Card Data...");
			    		OCard cd = new OCard(PCARDFrame.pc.stack);
						if(cd.readCardBlock(dis, blockSize)){
							//stack.cdCacheList.add(cd); //new OCard()で登録されてる
						}
						else{
							errCount++;
						}
						java.lang.System.gc();//GCをなるべく呼ぶ
					}
					else if(typeStr.equals("BKGD")){
						HCStackDebug.blockstart(typeStr);
						stack.bar.setString("Converting Background Data...");
						OBackground bg = new OBackground(PCARDFrame.pc.stack);
						if(bg.readBackgroundBlock(dis, blockSize)){
							stack.AddNewBg(bg);
						}else{
							errCount++;
						}
					}
					else if(typeStr.equals("MAST")){
						HCStackDebug.blockstart(typeStr);
						stack.readNullBlock(dis, blockSize);
					}
					else if(typeStr.equals("BMAP")){
						HCStackDebug.blockstart(typeStr);
						stack.bar.setString("Converting Picture Data...");
						stack.bar.paintImmediately(stack.bar.getBounds());
						readPictureBlock(dis, blockSize, stack);
					}
					else if(typeStr.equals("FREE")){
						HCStackDebug.blockstart(typeStr);
						stack.readNullBlock(dis, blockSize);
					}
					else if(typeStr.equals("PRNT")){
						HCStackDebug.blockstart(typeStr);
						stack.readNullBlock(dis, blockSize);
					}
					else if(typeStr.equals("PRST")){
						HCStackDebug.blockstart(typeStr);
						stack.readNullBlock(dis, blockSize);
					}
					else if(typeStr.equals("PRFT")){
						HCStackDebug.blockstart(typeStr);
						stack.readNullBlock(dis, blockSize);
					}
					else if(typeStr.equals("TAIL")){
						HCStackDebug.blockstart(typeStr);
						if(isReadStack){
							result = true;
						}
						break;
					}
					else{
						while(true){
							//アライメントをどうにか合わせてみる
							blockSize = (0x00FFFFFF&blockSize)<<8;
							blockSize += (0x00FF&(typeCode>>24));
							//System.out.println("<blockSize:"+blockSize);
							
							typeCode = typeCode<<8;
							int read = readCode(dis, 1);
							if(read == -1) throw new IOException();
							typeCode += read;
							//System.out.println("<typeCode:"+typeCode);
							
							aligncnt++;
							if(aligncnt==32){
								//System.out.println("!");
							}
							
							if((typeCode&0xFF000000)!=0x00000000){
								break;
							}
						}
						continue;
					}
					//HCStackDebug.debuginfo("<<end of "+typeStr+">>");
					//HCStackDebug.debuginfo("size:"+Integer.toString(blockSize));
					//System.out.println("blockSize:"+blockSize);
					//System.out.println("typeStr:"+typeStr);
					aligncnt=0;
					break;
				}
				if(result == true){
					break;
				}

			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		//デバッグ情報出力
		/*File f = new File("./debug_"+stack.name+".txt");
		try {
			FileOutputStream stream = new FileOutputStream(f);
			stream.write(HCStackDebug.allStr.toString().getBytes());
			stream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}*/
		
		if(result==true){
			//カードの枚数チェック
			//System.out.println(stack.cdCacheList.size()+","+stack.cardIdList.size());
			if(stack.cdCacheList.size()!=stack.cardIdList.size()){
				System.out.println("number of cards check error.");
				result = false;
			}
			if(errCount>0){
				System.out.println("errCount="+errCount);
				result = false;
			}
		}
		
		//ピクチャの変換を開始
		new HCData().new convertPictureThread().start();
		
		return result;
	}
	
	static final int readCode(DataInputStream dis, int size){
		byte[] opcode = new byte[size];
		for(int i=0; i<size; i++){
			try {
				int c = dis.read();
				HCStackDebug.read(c);
				opcode[i] = (byte)c;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		int iop = 0;
		if(size==1){
			iop = (opcode[0])&0xff;
			//System.out.println("code1:"+opcode[0]);
		}
		else if(size==2) {
			iop = (short)((opcode[0]&0xff)<<8)+(opcode[1]&0xff);
			//System.out.println("code2:"+opcode[0]+" "+opcode[1]);
		}
		else if(size==4) {
			iop = ((opcode[0]&0xff)<<24)+((opcode[1]&0xff)<<16)
				+((opcode[2]&0xff)<<8)+(opcode[3]&0xff);
			//System.out.println("code4:"+opcode[0]+" "+opcode[1]+" "+opcode[2]+" "+opcode[3]);
		}
		else{
			//System.out.println("!!!");
		}
		return iop;
	}
	
	static final String readStr(DataInputStream dis, int size){
		StringBuilder str = new StringBuilder(size);
		//String debugStr = "";
		for(int i=0; i<size; i++){
			try {
				int v = dis.read();
				HCStackDebug.read(v);
				str.append((char)v);
				//debugStr += " "+v;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		//if(debugStr.length()<40){
			//System.out.println("str:"+debugStr);
		//}else{
			//System.out.println("str/:"+debugStr.substring(0,40));
			//System.out.println("/str"+debugStr.substring(debugStr.length()-40));
		//}
		return str.toString();
	}
	
	static final resultStr readText(DataInputStream dis, int maxLen){
		resultStr result = new resultStr();
		if(maxLen<0) {
			result.str="";
			return result;
		}
		byte[] b = new byte[maxLen];
		result.length_in_src = 0;
		
		int i=0;
		int i2 = 0;
		try {
			for(; i+i2<maxLen; i++){
				int c = dis.read();
				HCStackDebug.read(c);
				result.length_in_src++;
				if(c<0) break;
				if(c>=0x00 && c<=0x1f && c!=0x0a && c!=0x0d && c!=0x09 || c==0x7F) {
					i2++;
					i--;
					continue;
				}
				b[i] = (byte)c;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			if(PCARD.pc.lang.equals("Japanese")){
				result.str = new String(b, 0, i, "SJIS");
			}else{
				result.str = new String(b, 0, i);
			}
			//改行コード変更
			for(int j=0;j<result.str.length(); j++){
				if(result.str.charAt(j)=='\r'){
					result.str = result.str.substring(0,j)+"\n"+result.str.substring(j+1);
				}
			}
			return result;
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		
		result.str = "";
		return result;
	}
	
	static final resultStr readTextToZero(DataInputStream dis, int maxLen){
		resultStr result = new resultStr();
		if(maxLen<0) {
			result.str="";
			result.length_in_src = 0;
			return result;
		}
		byte[] b = new byte[maxLen];
		result.length_in_src = 0;
		
		int i=0;
		try {
			for(; i<maxLen; i++){
				int c = dis.read();
				HCStackDebug.read(c);
				result.length_in_src++;
				if(c<=0) break;
				b[i] = (byte)c;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		try {
			result.str = new String(b, 0, i, "SJIS");
			//改行コード変更
			for(int j=0;j<result.str.length(); j++){
				if(result.str.charAt(j)=='\r'){
					result.str = result.str.substring(0,j)+"\n"+result.str.substring(j+1);
				}
			}
			return result;
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		
		result.str = "";
		return result;
	}
	
	static final String[] readPatterns(DataInputStream dis, String parentPath){
		String[] patAry = new String[40];
		
		for(int i=0; i<40; i++){
			BufferedImage bi = new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
			DataBuffer db = bi.getRaster().getDataBuffer();
			try {
				for(int y=0; y<8; y++){
					int c = dis.read();
					HCStackDebug.read(c);
					for(int x=0; x<8; x++){
						if(((c>>(7-x))&0x01)==0){
							db.setElem(y*8+x, 0xFFFFFFFF);
						}else{
							db.setElem(y*8+x, 0xFF000000);
						}
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			//PNG形式に変換してファイルに保存
			String filename = "PAT_"+(i+1)+".png";
			File ofile=new File(parentPath+File.separatorChar+filename);
			try {
				ImageIO.write(bi, "png", ofile);
			} catch (IOException e) {
				e.printStackTrace();
			}
			patAry[i] = filename;
		}
		return patAry;
	}
	

	static ArrayList<Thread> threadList = new ArrayList<Thread>();
	
	public static boolean readPictureBlock(DataInputStream dis, int blockSize, OStack stack){
		//System.out.println("readPictureBlock");

		if(blockSize>200000 || blockSize < 0){
			return false;
		}
		
		//ブロックのデータを順次読み込み
		int bitmapId = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("bitmapId:"+Integer.toString(bitmapId));
		//System.out.println("bitmapId:"+bitmapId);
		/*String filler =*/ HCData.readStr(dis, 12);
		//System.out.println("filler:"+filler);
		
		int top = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("top:"+Integer.toString(top));
		int left = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("left:"+Integer.toString(left));
		int bottom = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("bottom:"+Integer.toString(bottom));
		int right = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("right:"+Integer.toString(right));
		
		int maskTop = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("maskTop:"+Integer.toString(maskTop));
		int maskLeft = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("maskLeft:"+Integer.toString(maskLeft));
		int maskBottom = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("maskBottom:"+Integer.toString(maskBottom));
		int maskRight = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("maskRight:"+Integer.toString(maskRight));
		
		int imgTop = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("imgTop:"+Integer.toString(imgTop));
		int imgLeft = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("imgLeft:"+Integer.toString(imgLeft));
		int imgBottom = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("imgBottom:"+Integer.toString(imgBottom));
		int imgRight = HCData.readCode(dis, 2);
		//HCStackDebug.debuginfo("imgRight:"+Integer.toString(imgRight));
		
		/*String filler2 =*/ HCData.readStr(dis, 8);
		//System.out.println("filler2:"+filler2);

		int maskSize = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("maskSize:"+Integer.toString(maskSize));
		int imgSize = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("imgSize:"+Integer.toString(imgSize));

		if(blockSize < 64+maskSize+imgSize){
			//System.out.println("!:");
			return false;
		}
		if(maskSize<0 || imgSize<0){
			//System.out.println("!:");
			return false;
		}

		//マスクをbyte配列にロード
		byte[] mask = null;
		if(maskSize>0){
			mask = new byte[maskSize];
			try {
				//dis.read(mask);
				for(int i=0; i<maskSize; i++){
					int v = dis.read();
					HCStackDebug.read(v);
					mask[i] = (byte)v;
				}
			} catch (IOException e) {
				e.printStackTrace();
			};
		}

		//イメージをbyte配列にロード
		byte[] img = new byte[imgSize];
		try {
			//dis.read(img);
			for(int i=0; i<imgSize; i++){
				int v = dis.read();
				HCStackDebug.read(v);
				img[i] = (byte)v;
			}
		} catch (IOException e) {
			e.printStackTrace();
		};
		
		//イメージの読み込みは最後に別スレッドで行う
		Thread p = new HCData().new saveThread(mask, img, bitmapId,
				top, left, right, bottom, 
				maskLeft, maskTop, maskRight, maskBottom,
				imgLeft, imgTop, imgRight, imgBottom, stack );
		if(stack.GetCardbyId(stack.firstCard)!=null &&
				stack.GetCardbyId(stack.firstCard).bitmapName!=null&&
				stack.GetCardbyId(stack.firstCard).bitmapName.equals("BMAP_"+bitmapId+".png")){
			p.start();
		}
		else if(stack.GetBackgroundbyId(stack.firstBg)!=null &&
				stack.GetBackgroundbyId(stack.firstBg).bitmapName!=null &&
				stack.GetBackgroundbyId(stack.firstBg).bitmapName.equals("BMAP_"+bitmapId+".png")){
			p.start();
		}
		else{
			threadList.add(p);
		}
		
		
		//アライメント調整
		int remainLength = blockSize - (64+maskSize+imgSize);
		HCStackDebug.debuginfo("remainLength:"+Integer.toString(remainLength));
		//System.out.println("remainLength:"+remainLength);
		/*String padding =*/ HCData.readStr(dis, remainLength);
		//System.out.println("padding:"+padding);
		
		return true;
	}
	
	
	private class convertPictureThread extends Thread{
		public void run(){
			if(threadList!=null){
				Thread lastp = null;
				Thread lastp2 = null;
				for(int i=0; i<threadList.size(); i++){
					Thread p = threadList.get(i);
					if(p!=null&&!p.isAlive()){
						p.start();
					}
					//並行3スレッドまで
					while(lastp2!=null && lastp2.isAlive()){
						try {
							Thread.sleep(20);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
					lastp2 = lastp;
					lastp = p;
				}
			}
		}
	}
	
	private class saveThread extends Thread
	{
		byte[] mask;
		byte[] img;
		int bitmapId;
		int top;
		int left;
		int right;
		int bottom;
		int maskLeft;
		int maskTop;
		int maskRight;
		int maskBottom;
		int imgLeft;
		int imgTop;
		int imgRight;
		int imgBottom;
		OStack stack;
		
		saveThread(byte[] mask, byte[] img, int bitmapId,
				int top, int left, int right, int bottom, 
				int maskLeft, int maskTop, int maskRight, int maskBottom,
				int imgLeft, int imgTop, int imgRight, int imgBottom, OStack stack )
		{
			this.mask = mask;
			this.img = img;
			this.bitmapId = bitmapId;
			this.top = top;
			this.left = left;
			this.right = right;
			this.bottom = bottom;
			this.maskLeft = maskLeft;
			this.maskTop = maskTop;
			this.maskRight = maskRight;
			this.maskBottom = maskBottom;
			this.imgLeft = imgLeft;
			this.imgTop = imgTop;
			this.imgRight = imgRight;
			this.imgBottom = imgBottom;
			this.stack = stack;
			
			setName("BMAP_"+bitmapId+".png");
		}
		
		@Override
		public void run(){
			//bgに登録されているbitmapIDか？(スタックのデータを読み切ってしまわないと見逃してしまう場合あり)
			boolean isBgPicture = false;
			for(int i=0; i<stack.bgCacheList.size(); i++){
				if(stack.bgCacheList.get(i).bitmapName!=null && stack.bgCacheList.get(i).bitmapName.equals("BMAP_"+bitmapId+".png")){
					isBgPicture = true;
					break;
				}
			}
			
			BufferedImage maskBi = null;
			if(mask!=null){
				maskBi = readWOBA(mask, bitmapId, right, bottom, maskLeft, maskTop, maskRight, maskBottom, false);
			}
			BufferedImage mainBi = readWOBA(img, bitmapId, right, bottom, imgLeft, imgTop, imgRight, imgBottom, isBgPicture);
			
			Graphics2D g = mainBi.createGraphics();
			g.setColor(Color.WHITE);
			g.fillRect(0,0,imgLeft,bottom);
			g.fillRect(imgRight,0,right,bottom);
			g.fillRect(0,0,right,imgTop);
			g.fillRect(0,imgBottom,right,bottom);
			
			//アルファチャンネル付きのイメージに合成
			if(mask!=null){
				DataBuffer maindb = mainBi.getRaster().getDataBuffer();
				DataBuffer maskdb = maskBi.getRaster().getDataBuffer();
				for(int y=top; y<bottom; y++){
					for(int x=left; x<right; x++){
						int v = 0x00FFFFFF&maindb.getElem(x+y*right);
						if((0xFF000000&maindb.getElem(x+y*right))==0){
							continue;
						}
						if(v!=0){
							v = v | (0xFF000000&(~maskdb.getElem(x+y*right)<<24));
						}
						else {
							v = v | 0xFF000000;
						}
						maindb.setElem(x+y*right, v);
					}
				}
			}
			else if(!isBgPicture){
				DataBuffer maindb = mainBi.getRaster().getDataBuffer();
				for(int y=top; y<bottom; y++){
					for(int x=left; x<right; x++){
						int v = 0x00FFFFFF & maindb.getElem(x+y*right);
						if(v!=0) v = 0xFFFFFFFF;
						else v = 0xFF000000;
						maindb.setElem(x+y*right, v);
					}
				}
			}
			
			//ファイルに保存(これに時間がかかる)
			String filename = "BMAP_"+bitmapId+".png";
			File file = new File(PCARDFrame.pc.stack.file.getParent()+File.separatorChar+filename);
			try {
				ImageIO.write(mainBi, "png", file);
			} catch (IOException e) {
				e.printStackTrace();
			}

			for(int i=0; i<threadList.size(); i++){
				Thread p = threadList.get(i);
				if(p==this){
					threadList.set(i, null);
					break;
				}
			}
		}
	

		//HyperCardのピクチャフォーマット(Wrath Of Bill Atkinson)の読み込み
		private BufferedImage readWOBA(byte[] img, int id, int cdWidth, int cdHeight,
				int left, int top, int right, int bottom, boolean isBgPicture)
		{
			BufferedImage bi = new BufferedImage(cdWidth, cdHeight, BufferedImage.TYPE_INT_ARGB);
			if(isBgPicture){
				Graphics2D g = bi.createGraphics();
				g.setColor(Color.WHITE);
				g.fillRect(0,0,cdWidth,cdHeight);
			}
			else{
				Graphics2D g = bi.createGraphics();
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
				g.fillRect(0,0,cdWidth,cdHeight);
			}
			
			
			DataBuffer db = bi.getRaster().getDataBuffer();
			left = left/32*32;
			right = (right+31)/32*32;
			//String debugStr = "";
			
			byte[] keepArray = new byte[]{(byte) 0xAA, 0x55, (byte) 0xAA, 0x55, (byte) 0xAA, 0x55, (byte) 0xAA, 0x55};
			int dh = 0;
			int dv = 0;
			int repeatInstructionCount = 0;
			int repeatInstructionIndex = 0;
			
			int i=0;
			for(int y=top; y<bottom; y++){
				//debugStr += "\n"+"line "+y;//####
				
				int opcode = 0;
				int x = left;
				for(; i<img.length && x < right; /*i++*/){
					if(repeatInstructionCount>0){
						i = repeatInstructionIndex;
						repeatInstructionCount--;
						//debugStr += "rep ";//####
					}
					opcode = (0x00FF&img[i]);
					i++;
					//System.out.println("opcode("+i+")="+Integer.toHexString(opcode));
					//debugStr += "["+Integer.toHexString(opcode)+"]";//####
					
					if(opcode <= 0x7F){
						int dataBytes = opcode>>4;
						int zeroBytes = opcode & 0x0F;
						
						for(int j=0; j<zeroBytes; j++){
							for(int k=0; k<8 && x<right; k++){
								db.setElem(x+y*cdWidth, 0xFFFFFFFF);
								x++;
							}
						}
						for(int j=0; j<dataBytes && i<img.length; j++){
							for(int k=0; k<8; k++){
								db.setElem(x+y*cdWidth, (0x01&(img[i]>>(7-k)))!=0?0xFF000000:0xFFFFFFFF);
								x++;
							}
							//debugStr += Integer.toHexString((int)(0x00ff&img[i]))+" ";//####
							i++;
						}
					}
					else if(opcode >= 0x80 && opcode <= 0x8F){
						switch(opcode){
						case 0x80: //1行分無圧縮
							while(x<right && i<img.length){
								for(int k=0; k<8; k++){
									db.setElem(x+y*cdWidth, (0x01&(img[i]>>(7-k)))!=0?0xFF000000:0xFFFFFFFF);
									x++;
								}
								//debugStr += Integer.toHexString((int)(0x00FF&img[i]))+" ";//####
								i++;
							}
							break;
						case 0x81: //1行白
							while(x<right){
								db.setElem(x+y*cdWidth, 0xFFFFFFFF);
								x++;
							}
							break;
						case 0x82: //1行黒
							while(x<right){
								db.setElem(x+y*cdWidth, 0xFF000000);
								x++;
							}
							break;
						case 0x83: //1行同一バイト
							while(x<right){
								for(int k=0; k<8; k++){
									db.setElem(x+y*cdWidth, (0x01&(img[i]>>(7-k)))!=0?0xFF000000:0xFFFFFFFF);
									x++;
								}
							}
							//debugStr += Integer.toHexString(img[i])+" ";//####
							keepArray[y%8] = img[i];
							i++;
							break;
						case 0x84: //1行同一バイト,保持配列のデータを使う
							while(x<right){
								for(int k=0; k<8; k++){
									db.setElem(x+y*cdWidth, (0x01&(keepArray[y%8]>>(7-k)))!=0?0xFF000000:0xFFFFFFFF);
									x++;
								}
							}
							//debugStr += "keep "+Integer.toHexString((int)(0x00FF&keepArray[y%8]))+" ";//####
							break;
						case 0x85: //1行上をコピー
							while(x<right){
								for(int k=0; k<8; k++){
									db.setElem(x+y*cdWidth, db.getElem(0, x+(y-1)*cdWidth));
									x++;
								}
							}
							break;
						case 0x86: //2行上をコピー
							while(x<right){
								for(int k=0; k<8; k++){
									db.setElem(x+y*cdWidth, db.getElem(0, x+(y-2)*cdWidth));
									x++;
								}
							}
							break;
						case 0x87: //3行上をコピー
							while(x<right){
								for(int k=0; k<8; k++){
									db.setElem(x+y*cdWidth, db.getElem(0, x+(y-3)*cdWidth));
									x++;
								}
							}
							break;
						case 0x88:
							dh = 16; dv = 0; //16bit右シフトしてXOR
							break;
						case 0x89:
							dh = 0; dv = 0; //
							break;
						case 0x8A:
							dh = 0; dv = 1; //1行上とXOR
							break;
						case 0x8B:
							dh = 0; dv = 2; //2行上とXOR
							break;
						case 0x8C:
							dh = 1; dv = 0; //1bit右シフトしてXOR
							break;
						case 0x8D:
							dh = 1; dv = 1; //1bit右シフト、1行上とXOR
							break;
						case 0x8E:
							dh = 2; dv = 2; //2bit右シフト、2行上とXOR
							break;
						case 0x8F:
							dh = 8; dv = 0; //8bit右シフトしてXOR
							break;
							
						default:
							//System.out.println("!");
							break;
						}
					}
					else if(opcode >= 0xA0 && opcode <= 0xBF){
						//下5bit分、次のバイトのopcodeを繰り返す
						repeatInstructionCount = (0x1F & opcode);
						repeatInstructionIndex = i;
					}
					else if(opcode >= 0xC0 && opcode <= 0xDF){
						//下5bit*8分のデータ
						int count = (0x1F & opcode)*8;
						while(count>0 && x<cdWidth && i<img.length){
							for(int k=0; k<8; k++){
								db.setElem(x+y*cdWidth, (0x01&(img[i]>>(7-k)))!=0?0xFF000000:0xFFFFFFFF);
								x++;
							}
							count--;
							//debugStr += Integer.toHexString((int)(0x00FF&img[i]))+" ";//####
							i++;
						}
					}
					else if(opcode >= 0xE0 && opcode <= 0xFF){
						//下5bit*16分のゼロ
						int count = (0x1F & opcode)*16;
						while(count>0 && x<cdWidth){
							for(int k=0; k<8; k++){
								db.setElem(x+y*cdWidth, 0xFFFFFFFF);
								x++;
							}
							count--;
						}
					}
				}
	
				if(opcode>=0x80 && opcode<=0x87){
					//1行書き換えのときはdh,dvを実施しない
					continue;
				}
				
				//
				//debugStr += " dh="+dh+" dv="+dv;//####
				if( y<bottom ){
					if (dh>0)
					{
						x=left+dh;
						while(x<right){
							//int v = 0xFF000000|0x00FFFFFF&(db.getElem(0, x+y*cdWidth)^db.getElem(0, (x-dh)+y*cdWidth));
							int a1 = db.getElem(0, x+y*cdWidth);
							int a2 = db.getElem(0, (x-dh)+y*cdWidth);
							int a3 = a1^a2;
							int a4 = 0x00FFFFFF&a3;
							int a5 = 0xFF000000|a4;
							int v = a5;
							v = v^(0x00FFFFFF);
							db.setElem(x+y*cdWidth, v);
							x++;
						}
					}
	
					x=left;
					if (dv>0)
					{
						while(x<right && y>=dv){
							db.setElem(x+y*cdWidth, 0x00FFFFFF^(0xFF000000|0x00FFFFFF&(db.getElem(0, x+y*cdWidth)^db.getElem(0, x+(y-dv)*cdWidth))));
							x++;
						}
					}
				}
				
				//go next row
			}
			
			/*File f = new File("./woba_debug_"+id+".txt");
			try {
				FileOutputStream stream = new FileOutputStream(f);
				stream.write(debugStr.getBytes());
				stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}*/
			
			return bi;
		}
	}
}


class resultStr{
	String str;
	int length_in_src;
}



class HCStackDebug{
	static StringBuilder allStr = new StringBuilder(10000);
	static String hexstr;
	static StringBuilder debugStr = new StringBuilder(100);
	static int all_counter;
	static int block_counter;

	public static void read(int code){
		/*if(block_counter%16==0){
			allStr.append("\n");
			allStr.append(Integer.toHexString(all_counter));
			allStr.append(" ");
			allStr.append(Integer.toHexString(block_counter));
			allStr.append(" ");
			allStr.append(hexstr);
			hexstr = "";
			allStr.append(" ");
			allStr.append(debugStr);
			debugStr = new StringBuilder(100);
		}
		hexstr += " "+Integer.toHexString(code);
		all_counter+=1;
		block_counter+=1;*/
	}
	
	public static void debuginfo(String str){
		/*debugStr.append(" ");
		debugStr.append(str);*/
	}
	
	public static void blockstart(String str){
		/*block_counter = 0;
		debugStr.append("\n\nBLOCK<<");
		debugStr.append(" "+str);
		debugStr.append(">>");*/
	}
}
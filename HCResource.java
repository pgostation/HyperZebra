import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;


public class HCResource {	
	//AppleDoubleHeaderFileの中にリソースフォークのデータがある
	public static boolean readAppleDoubleHeader(DataInputStream dis, OStack stack){
		//System.out.println("readAppleDoubleHeader");
		
		int magic = readCode(dis, 4);
		if (magic!= 0x51607){
			System.out.println("magic!= 0x51607");
		}
		@SuppressWarnings("unused")
		int version = readCode(dis, 4);
		@SuppressWarnings("unused")
		String homefilesystem = readStr(dis, 16);
		int numberOfEntryies = readCode(dis, 2);
		int theOffset = 26;
		for(int i=0; i<numberOfEntryies; i++){
			int entryId = readCode(dis, 4);
			int entryOffset = readCode(dis, 4);
			int entryLength = readCode(dis, 4);
			theOffset += 12;
			
			if(entryId == 2){
				//リソースフォークのID
				
				//リソースフォークまで読み飛ばす
				int length = (entryOffset-theOffset);
				for(int j=0; j<length; j++){
					try {
						dis.read();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				//リソースフォーク部分を読み込む
				byte[] b = new byte[entryLength];
				for(int j=0; j<entryLength; j++){
					try {
						b[j] = (byte)dis.read();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				//リソースフォークのデータを解析
				readResourceFork(b, stack);

				java.lang.System.gc();//GCをなるべく呼ぶ
				
				break;
			}else{
			}
		}
		
		return true;
	}
	
	private static final int readCode(DataInputStream dis, int size){
		byte[] opcode = new byte[size];
		for(int i=0; i<size; i++){
			try {
				opcode[i] = (byte)dis.read();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		int iop = 0;
		if(size==1) iop = (opcode[0])&0xff;
		else if(size==2) iop = ((opcode[0]&0xff)<<8)+(opcode[1]&0xff);
		else if(size==4) iop = ((opcode[0]&0xff)<<24)+((opcode[1]&0xff)<<16)
			+((opcode[2]&0xff)<<8)+(opcode[3]&0xff);
		return iop;
	}
	
	private static final String readStr(DataInputStream dis, int size){
		StringBuilder str = new StringBuilder(size);
		for(int i=0; i<size; i++){
			try {
				str.append((char)dis.read());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return str.toString();
	}

	
	//リソースフォーク内に各リソースのデータが含まれる
	static boolean readResourceFork(byte[] b, OStack stack){
		//System.out.println("readResourceFork");
		
		if(b.length==0) return true;
		
        int dataOffset = u4(b,0);
        int mapOffset = u4(b,4);
        @SuppressWarnings("unused")
		int dataLength = u4(b,8);
        @SuppressWarnings("unused")
		int mapLength = u4(b,12);
		//System.out.println("dataOffset:"+dataOffset);
		//System.out.println("mapOffset:"+mapOffset);
		//System.out.println("dataLength:"+dataLength);
		//System.out.println("mapLength:"+mapLength);
		
		int offset = mapOffset+16+4+2;
		@SuppressWarnings("unused")
		int attrs = u2(b,offset);
		offset+=2;

        int typeListOffset = u2(b,offset) + mapOffset + 2;
		offset+=2;
        int nameListOffset = u2(b,offset) + mapOffset;
		offset+=2;
        int typesCount = u2(b,offset) + 1;
		offset+=2;
		
        for (int i = 0; i < typesCount && offset+8<=b.length; i++) {
    		//System.out.println("========");
    		//各タイプと個数、位置
    		offset = typeListOffset + 8*i;
        	String type = str4(b,offset);
    		//System.out.println("type:"+type);
        	offset+=4;
        	int count = u2(b,offset) + 1;
    		//System.out.println("count:"+count);
        	offset+=2;
        	int rsrcoffset = u2(b,offset)+ typeListOffset - 2;
        	offset+=2;

        	//各リソースのヘッダー
    		offset = rsrcoffset;
            for (int j=0; j<count; j++) {
    			stack.bar.setValue(stack.barOffset+(25*i)/typesCount+(25*1)*j/count/typesCount);
    			stack.bar.setString("Converting "+type+" resource "+j +"/"+ count);
    			stack.bar.paintImmediately(stack.bar.getBounds());
        		//System.out.println("====");
            	//ヘッダ部分
            	int id = s2(b,offset);
            	offset+=2;
            	int nameoffset = s2(b,offset);
            	offset+=2;
        		//System.out.println("nameoffset:"+nameoffset);
                if(nameoffset>=0) nameoffset += nameListOffset;
        		//System.out.println("nameoffset:"+nameoffset);
                @SuppressWarnings("unused")
				int rsrcAttr = u1(b,offset);
            	offset+=1;
                int dataoffset = u3(b,offset) + dataOffset;
        		//System.out.println("dataOffset:"+dataOffset);
            	offset+=3;
                offset+=4; //reserved

                //名前
                String name = "";
                if (nameoffset >= 0) {
                    int namelen = u1(b,nameoffset);
            		//System.out.println("namelen:"+namelen);
					name = new String(b,nameoffset+1,namelen);
                }
        		//System.out.println("name:"+name);
        		
        		//データ
                int datalen = u4(b,dataoffset);
                readResourceData(stack, b, dataoffset+4, datalen, type, id, name);
            }
        }
		
		return true;
	}

	private static final String strn(byte[] b, int offset, int length){
		/*StringBuilder str = new StringBuilder(length);
		for(int i=0; i<length; i++){
			str.append((char)b[offset+i]);
		}
		return str.toString();*/
		if(PCARD.pc.lang.equals("Japanese")){
			try {
				return new String(b, offset, length, "SJIS");
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
			return "";
		}else{
			return new String(b, offset, length);
		}
	}
	
	private static final String str4(byte[] b, int offset){
		StringBuilder str = new StringBuilder(4);
		str.append((char)b[offset]);
		str.append((char)b[offset+1]);
		str.append((char)b[offset+2]);
		str.append((char)b[offset+3]);
		return str.toString();
	}
	
	private static final int u4(byte[] b, int offset){
		return (int) ((((long)(0x7F&b[offset]))<<24)+((0xff&b[offset+1])<<16)+((0xff&b[offset+2])<<8)+(0xff&b[offset+3]));
	}
	
	private static final int u3(byte[] b, int offset){
		return ((0x00ff&b[offset])<<16)+((0xff&b[offset+1])<<8)+(0xff&b[offset+2]);
	}
	
	private static final int u2(byte[] b, int offset){
		return ((0x000000ff&b[offset])<<8)+(0xff&b[offset+1]);
	}
	
	private static final short s2(byte[] b, int offset){
		return (short)(((0x00ff&b[offset])<<8)+(0xff&b[offset+1]));
	}
	
	private static final int u1(byte[] b, int offset){
		return 0x00ff&b[offset];
	}
	
	
	//各リソースのデータをファイルに変換する
	private static void readResourceData(OStack stack,
			byte[] in_b, int start, int datalen,
			String type, int id, String name)
	{
		String parentPath = new File(stack.path).getParent();
		
		byte[] b = new byte[datalen];
		System.arraycopy(in_b, start, b, 0, datalen);
		
		//ファイルに変換
		String filename = null;
		String mytype = type;
		if(type.equals("ICON")){
			filename = convertICON2PNG(b, parentPath, id);
			mytype = "icon";
		}
		else if(type.equals("cicn") ){
			filename = convertcicn2PNG(b, parentPath, id);
			mytype = "cicn";
		}
		else if(type.equals("PICT") || type.equals("pict") || type.equals("Pdat") ){
			filename = convertPICT2PICTfile(b, parentPath, id);
			mytype = "picture";
		}
		else if(type.equals("snd ")){
			filename = convertSND2AIFF(b, parentPath, id);
			mytype = "sound";
		}
		else if(type.equals("CURS")){
			//CURSは関数内で登録する
			filename = null;
			convertCURS2Cursor(b, parentPath, id, name, stack.rsrc);
		}
		else if(type.equals("FONT") || type.equals("NFNT")){
			convertFONT2PNG(b, parentPath, id, name, stack.rsrc);
		}
		else if(type.equals("HCcd") || type.equals("HCbg")){
			Rsrc.addcolorClass addColorOwner;
			addColorOwner = stack.rsrc.new addcolorClass(Integer.toString(id), type.equals("HCbg"));
			convertAddColorResource(addColorOwner, b);
		    stack.rsrc.addcolorList.add(addColorOwner);
		}
		else if(type.equals("PLTE")){
			Rsrc.PlteClass plteOwner;
			plteOwner = stack.rsrc.new PlteClass(id, name,0,true,0,0, new Point(0,0));
			convertPLTEResource(plteOwner, b, stack.rsrc);
		    stack.rsrc.plteList.add(plteOwner);
		}
		else if(type.equals("XCMD") || type.equals("xcmd") || type.equals("XFCN") || type.equals("xfcn")){
			//XCMDは関数内で登録する
			convertXCMD2file(b, type, id, name, stack);
		}
		else{
			filename = convertRsrc2file(b, type, parentPath, id, name);
			mytype = type;
    		//System.out.println("Error: Unknown resource type \""+type+"\"");
		}
		
		//リソースとして登録
		if(filename!=null){
			stack.rsrc.addResource(id, mytype, name, filename);
		}
	}

	//-----------
	// ICON
	//-----------
	private static String convertICON2PNG(byte[] b, String parentPath, int id)
	{
		//アイコンデータの取り込み
		//単なる32*32bitの固定データ
		BufferedImage bi = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
		DataBuffer db = bi.getRaster().getDataBuffer();
		for(int y=0; y<32; y++){
			for(int x=0; x<32; x++){
				int c = b[y*4+x/8];
				c = 0x01&(c>>(7-x%8));
				db.setElem(x+y*32, (c==0) ? 0xFFFFFFFF:0xFF000000);
			}
		}

		//周辺の白色を透明化
		for(int y=0; y<32; y++){
			int x=0;
			if(db.getElem(0, y*32+x)==0xFFFFFFFF){
				clearPixel(x, y, db);
			}
			x=31;
			if(db.getElem(0, y*32+x)==0xFFFFFFFF){
				clearPixel(x, y, db);
			}
		}
		for(int x=0; x<32; x++){
			int y=0;
			if(db.getElem(0, y*32+x)==0xFFFFFFFF){
				clearPixel(x, y, db);
			}
			y=31;
			if(db.getElem(0, y*32+x)==0xFFFFFFFF){
				clearPixel(x, y, db);
			}
		}
		
		//PNG形式に変換してファイルに保存
		File ofile=new File(parentPath+File.separatorChar+"ICON_"+id+".png");
		try {
			ImageIO.write(bi, "png", ofile);
		} catch (IOException e) {
			e.printStackTrace();
		}

		if(ofile.exists()){
			//変換成功
			return ofile.getName();
		}
		
		return null;
	}
	
	private final static void clearPixel(int x, int y, DataBuffer db){
		db.setElem(x+y*32,0x00FFFFFF);
		if(x>0){
			if(db.getElem(0, y*32+x-1)==0xFFFFFFFF){
				clearPixel(x-1, y, db);
			}
		}
		if(x+1<32){
			if(db.getElem(0, y*32+x+1)==0xFFFFFFFF){
				clearPixel(x+1, y, db);
			}
		}
		if(y>0){
			if(db.getElem(0, (y-1)*32+x)==0xFFFFFFFF){
				clearPixel(x, y-1, db);
			}
		}
		if(y+1<32){
			if(db.getElem(0, (y+1)*32+x)==0xFFFFFFFF){
				clearPixel(x, y+1, db);
			}
		}
	}
	

	//-----------
	// cicn
	//-----------
	private static String convertcicn2PNG(byte[] b, String parentPath, int id)
	{
		BufferedImage bi;
		int offset = 0;

		//カラーアイコンデータの取り込み
		{
			//iconPMap
			/*int baseAddr =*/ u4(b,offset);
			offset+=4;
			int rowBytes = 0x7FFF & u2(b,offset);
			offset+=2;

			/*int top =*/ u2(b,offset);
			offset+=2;
			/*int left =*/ u2(b,offset);
			offset+=2;
			int bottom = u2(b,offset);
			offset+=2;
			int right = u2(b,offset);
			offset+=2;
			/*int pmVersion =*/ u2(b,offset);
			offset+=2;
			int packType = u2(b,offset);
			offset+=2;
			int packSize = u4(b,offset);
			offset+=4;
			/*int hRes =*/ u4(b,offset);
			offset+=4;
			/*int vRes =*/ u4(b,offset);
			offset+=4;
			/*int pixelType =*/ u2(b,offset);
			offset+=2;
			int pixelSize = u2(b,offset);
			offset+=2;
			/*int cmpCount =*/ u2(b,offset);
			offset+=2;
			/*int cmpSize =*/ u2(b,offset);
			offset+=2;
			/*int planeBytes =*/ u4(b,offset);
			offset+=4;

			/*int ctabhandle =*/ u4(b,offset);
			offset+=4;
			/*int pmreserved =*/ u4(b,offset);
			offset+=4;
			
			//maskBMap
			/*int mbaseAddr =*/ u4(b,offset);
			offset+=4;
			int mrowBytes = 0x7FFF & u2(b,offset);
			offset+=2;

			/*int mtop =*/ u2(b,offset);
			offset+=2;
			/*int mleft =*/ u2(b,offset);
			offset+=2;
			/*int mbottom =*/ u2(b,offset);
			offset+=2;
			/*int mright =*/ u2(b,offset);
			offset+=2;

			//int mrowBytes = (mright+7)/8;
			
			//iconBMap
			/*int ibaseAddr =*/ u4(b,offset);
			offset+=4;
			int irowBytes = 0x7FFF & u2(b,offset);
			offset+=2;

			/*int itop =*/ u2(b,offset);
			offset+=2;
			/*int ileft =*/ u2(b,offset);
			offset+=2;
			/*int ibottom =*/ u2(b,offset);
			offset+=2;
			/*int iright =*/ u2(b,offset);
			offset+=2;

			//int irowBytes = (iright+7)/8;

			//int iconData = u4(b,offset);
			//offset+=4;
			
			//画像データ
			BufferedImage maskbi = new BufferedImage(right,bottom,BufferedImage.TYPE_INT_ARGB);
			DataBuffer maskdb = maskbi.getRaster().getDataBuffer();
			for(int y=0; y<bottom; y++){
				byte[] data = new byte[mrowBytes];
				for(int i=0; i<mrowBytes; i++){
					if(offset>=b.length){
						continue;
					}
					data[i] = b[offset];offset++;
				}
			
				for(int x=0; x<right && x*1/8<mrowBytes; x++){
					int idx = data[x*1/8]&0x00FF;
					idx = (idx>>(7-x%8))&0x01;
					maskdb.setElem((y*right)+x, idx==0?0xFF000000:0xFFFFFFFF);
				}
			}	

			//画像データ
			bi = new BufferedImage(right,bottom,BufferedImage.TYPE_INT_ARGB);
			DataBuffer monodb = bi.getRaster().getDataBuffer();
			for(int y=0; y<bottom; y++){
				byte[] data = new byte[irowBytes];
				for(int i=0; i<irowBytes; i++){
					data[i] = b[offset];offset++;
				}
			
				for(int x=0; x<right && x*1/8<irowBytes; x++){
					int idx = data[x*1/8]&0x00FF;
					idx = (idx>>(x%8))&0x01;
					monodb.setElem((y*right)+x, idx==0?0xFF000000:0xFFFFFFFF);
				}
			}

			if(offset+1<b.length){
				/*int iconData =*/ u4(b,offset);
				offset+=4;
				//cTableヘッダ
				/*int ctSeed =*/ u4(b,offset);
				offset+=4;
				/*int ctFlag =*/ u2(b,offset);
				offset+=2;
				int ctSize = u2(b,offset);
				offset+=2;
				
				//palette
				Integer[] palette;
				if(ctSize==0){
					palette = new Integer[]{0xFF000000, 0xFFFFFFFF};
				}
				else{
					palette = new Integer[256];
					for(int i=0; i<ctSize+1; i++){
						int value = 0x00FF&u2(b,offset);
						offset+=2;
						int red = u2(b,offset);
						offset+=2;
						int green = u2(b,offset);
						offset+=2;
						int blue = u2(b,offset);
						offset+=2;
						palette[value] = 0xFF000000 | (((red/256)<<16) + ((green/256)<<8) + ((blue/256)));
					}
				}
		
				//画像データ
				bi = new BufferedImage(right,bottom,BufferedImage.TYPE_INT_ARGB);
				DataBuffer db = bi.getRaster().getDataBuffer();
				for(int y=0; y<bottom; y++){
					byte[] data = new byte[rowBytes];
					if(packType==0){
						for(int i=0; i<rowBytes && offset<b.length; i++){
							data[i] = b[offset];offset++;
						}
					}
					else{
						//packBitsを展開
						for(int i=0; i<packSize; i++){
							int dsize = 0x00FF&b[offset];offset++;
							int doffset = 0;
							if(dsize>=128) {
								//同じデータが連続する場合
								dsize = 256-dsize+1;
								int src = b[offset];offset++;
								i++;
								for(int j=0; j<dsize && j+doffset<data.length; j++){ data[j+doffset] = (byte)src; }
								doffset += dsize;
							}
							else {
								//データそのまま
								dsize++;
								for(int j=0; j<dsize; j++){
									if(rowBytes<=j+doffset){
										//System.out.println("!");
										continue;
									}
									data[j+doffset] = b[offset];offset++;i++;
								}
								doffset += dsize;
							}
						}
					}
				
					for(int x=0; x<right && x*pixelSize/8<rowBytes; x++){
						int idx = data[x*pixelSize/8]&0x00FF;
						if(pixelSize==1) idx = (idx>>(7-x%8))&0x01;
						if(pixelSize==2) idx = (idx>>(6-2*(x%4)))&0x03;
						if(pixelSize==4) idx = (idx>>(4-4*(x%2)))&0x0F;
						if(idx>=palette.length || palette[idx]==null) idx = 0;
						int pixel = palette[idx];
						db.setElem((y*right)+x, pixel);
					}
				}
				
				bi = OCard.makeAlphaImage(bi, maskbi);
			}
		}
	
		
		//PNG形式に変換してファイルに保存
		File ofile=new File(parentPath+File.separatorChar+"cicn_"+id+".png");
		try {
			ImageIO.write(bi, "png", ofile);
		} catch (IOException e) {
			e.printStackTrace();
		}

		if(ofile.exists()){
			//変換成功
			return ofile.getName();
		}
		
		return null;
	}
	
	
	//-----------
	// PICT
	//-----------
	private static String convertPICT2PICTfile(byte[] b, String parentPath, int id)
	{
		byte[] header = new byte[512];
		
		//FileOutputStream作成
		File ofile=new File(parentPath+File.separatorChar+"PICT_"+id+".pict");
		FileOutputStream stream = null;
		try {
			stream = new FileOutputStream(ofile);
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
		if(stream == null){
			return null;
		}

		//512byteのヘッダーを追加してファイルに保存
		try {
			stream.write(header);
			stream.write(b);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		try {
			stream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		if(ofile.exists()){
			//変換成功
			return ofile.getName();
		}
		
		return null;
	}
	
	
	//-----------
	// SND
	//-----------
	//private static final long nullCmd = 0;
	//private static final long quietCmd = 3;
	//private static final long flushCmd = 4;
	//private static final long reInitCmd = 5;
	//private static final long waitCmd = 10;
	//private static final long pauseCmd = 11;
	//private static final long resumeCmd = 12;
	//private static final long callBackCmd = 13;
	//private static final long syncCmd = 14;
	//private static final long availableCmd = 24;
	//private static final long versionCmd = 25;
	//private static final long freqDurationCmd = 40;
	//private static final long ampCmd = 43;
	//private static final long volumeCmd  = 46;
	//private static final long getVolumeCmd = 47;
	private static final long soundCmd = 80;
	private static final long bufferCmd = 81;
	//private static final long rateMultiplierCmd = 86;
	//private static final long getRateMultiplierCmd = 87;

	
	private static final long sampledSynth = 5;
	//private static final long squareWaveSynth = 1;
	//private static final long waveTableSynth = 3;
	//private static final long MACE3snthID = 11;
	//private static final long MACE6snthID = 13;

	private static final long initMono = 0x0080;
	private static final long initStereo = 0x00C0;
	private static final long initMACE3 = 0x0300;
	private static final long initMACE6 = 0x0400;
	
	
	private static String convertSND2AIFF(byte[] b, String parentPath, int id)
	{
		//System.out.println("SND_"+id+".aiff");
		
		//FileInputStream作成
		FileOutputStream stream = null;
		File ofile=new File(parentPath+File.separatorChar+"SND_"+id+".aiff");
		try {
			stream = new FileOutputStream(ofile);
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
		if(stream == null){
			return null;
		}
		
		
		//デバッグ表示
		/*for(int i=0; i<b.length; ){
			String str1="";
			String str2="";
			for(int i2=0; i2<4; i++,i2++){
				str1 += " "+ b[i];
				str2 += (char) b[i];
			}
			System.out.println(str1+"    "+str2);
		}*/
		
		int offset = 0;
		
		if(b.length<2){
			return null;
		}
		
		int format = u2(b,offset);
		//System.out.println("format:"+format);
		offset +=2;
		if(format==1){
			int initMACE = 0;
			/*int numModifiers =*/ u2(b,offset);
			//System.out.println("numModifiers:"+numModifiers);
			offset +=2;
			int modNumber = u2(b,offset);
			//System.out.println("modNumber:"+modNumber);
			offset +=2;
			if(modNumber==sampledSynth){
				//System.out.println("sampledSynth!");
			}
			int modInit = u4(b,offset);
			//System.out.println("modInit:"+modInit);
			offset +=4;
			int channel = 1;
			if((modInit&initMono)==initMono){
				//System.out.println("initMono!");
				channel = 1;
			}
			if((modInit&initStereo)==initStereo){
				//System.out.println("initStereo!");
				channel = 2;
			}
			if((modInit&initMACE3)==initMACE3){
				//System.out.println("initMAC3!");
				initMACE = 3;
			}
			if((modInit&initMACE6)==initMACE6){
				//System.out.println("initMAC6!");
				initMACE = 6;
			}
			int numCommands = u2(b,offset);
			System.out.println("numCommands:"+numCommands);
			offset +=2;
			
			//SndCommand & data
			for(int n=0; n<numCommands; n++){
				System.out.println("b[0]:"+b[offset]);
				System.out.println("b[1]:"+b[offset+1]);
				int sndcmd_cmd = s2(b,offset);
				System.out.println("sndcmd_cmd:"+sndcmd_cmd);
				offset +=2;
				if((0x00FF&sndcmd_cmd)==bufferCmd){
					//System.out.println("bufferCmd!");
				}
				int sndcmd_param1 = u2(b,offset);
				System.out.println("sndcmd_param1:"+sndcmd_param1);
				offset +=2;
				int sndcmd_param2 = u4(b,offset);
				System.out.println("sndcmd_param2:"+sndcmd_param2);
				offset +=4;
				
				if((0x00FF&sndcmd_cmd)==bufferCmd){
					int in_offset = sndcmd_param2;
					/*int samplePtr =*/ u4(b,in_offset);
					in_offset +=4;
					//System.out.println("samplePtr:"+samplePtr);
					int length = u4(b,in_offset);
					in_offset +=4;
					//System.out.println("length:"+length);
					int sampleRate = u4(b,in_offset);
					in_offset +=4;
					//System.out.println("sampleRate:"+sampleRate);
					/*int loopStart =*/ u4(b,in_offset);
					in_offset +=4;
					//System.out.println("loopStart:"+loopStart);
					/*int loopEnd =*/ u4(b,in_offset);
					in_offset +=4;
					//System.out.println("loopEnd:"+loopEnd);
					/*int encode =*/ u1(b,in_offset);
					in_offset +=1;
					//System.out.println("encode:"+encode);
					/*int baseFrequency =*/ u1(b,in_offset);
					in_offset +=1;
					//System.out.println("baseFrequency:"+baseFrequency);
					int dataOffset = in_offset;

					//AIFF形式に変換
					AudioFormat af = new AudioFormat(sampleRate/65536.0f, 8, channel,
							false/*signed*/, true/*big-endian*/);
					InputStream in = new ByteArrayInputStream(b, dataOffset, length);
					AudioInputStream ais = new AudioInputStream(in, af, length);
					AudioFileFormat.Type type = AudioFileFormat.Type.AIFF;
					try {
						AudioSystem.write(ais, type, ofile);
						ais.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				if(initMACE>0 || sndcmd_cmd==-32687){
					int sampleRate = 11025*65536;
					int dataOffset = 54;//offset+22;
					int length = b.length - dataOffset;
					//AIFF形式に変換
					AudioFormat af = new AudioFormat(sampleRate/65536.0f, 8, channel,
							false/*signed*/, true/*big-endian*/);
					InputStream in = new ByteArrayInputStream(b, dataOffset, length);
					AudioInputStream ais = new AudioInputStream(in, af, length);
					AudioFileFormat.Type type = AudioFileFormat.Type.AIFF;
					try {
						AudioSystem.write(ais, type, ofile);
						ais.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
					
					//圧縮された音声の場合はデータを書き込み直す
					try {
						RandomAccessFile raf = new RandomAccessFile(ofile,"rw");
						
						//'FORM'のckSizeを圧縮ヘッダ分増やす
						raf.seek(4);
						byte[] ckSizeByte = new byte[4];
						raf.read(ckSizeByte, 0, 4);
						System.out.println("ckSizeByte:"+ckSizeByte[0]+","+ckSizeByte[1]+","+ckSizeByte[2]+","+ckSizeByte[3]);
						int ckSize = (((int)(0x00FF&ckSizeByte[0]))<<24) +
							(((int)(0x00FF&ckSizeByte[1]))<<16) +
							(((int)(0x00FF&ckSizeByte[2]))<<8) +
							(((int)(0x00FF&ckSizeByte[3]))<<0);
						System.out.println("ckSize:"+ckSize);
						ckSize += 16;
						raf.seek(4);
						byte[] ckSizeByte2 = new byte[]{
								(byte)(0xFF&(ckSize>>24)), (byte)(0xFF&(ckSize>>16)), (byte)(0xFF&(ckSize>>8)), (byte)(0xFF&ckSize)};
						System.out.println("ckSizeByte2:"+ckSizeByte2[0]+","+ckSizeByte2[1]+","+ckSizeByte2[2]+","+ckSizeByte2[3]);
						raf.write(ckSizeByte2, 0, 4);
						
						//'AIFF'->'AIFC'
						raf.seek(11);
						raf.write((byte)'C');

						//'COMM'のckSizeを圧縮ヘッダ分増やす
						raf.seek(12+4);
						byte[] cmSizeByte = new byte[4];
						raf.read(cmSizeByte, 0, 4);
						System.out.println("cmSizeByte:"+cmSizeByte[0]+","+cmSizeByte[1]+","+cmSizeByte[2]+","+cmSizeByte[3]);
						int cmSize = (((int)(0x00FF&cmSizeByte[0]))<<24) +
							(((int)(0x00FF&cmSizeByte[1]))<<16) +
							(((int)(0x00FF&cmSizeByte[2]))<<8) +
							(((int)(0x00FF&cmSizeByte[3]))<<0);
						System.out.println("cmSize:"+cmSize);
						cmSize += 16;
						raf.seek(12+4);
						byte[] cmSizeByte2 = new byte[]{
								(byte)(0xFF&(cmSize>>24)), (byte)(0xFF&(cmSize>>16)), (byte)(0xFF&(cmSize>>8)), (byte)(0xFF&cmSize)};
						System.out.println("cmSizeByte2:"+cmSizeByte2[0]+","+cmSizeByte2[1]+","+cmSizeByte2[2]+","+cmSizeByte2[3]);
						raf.write(cmSizeByte2, 0, 4);
						
						//'SSND'の前に圧縮情報を入れる
						raf.seek(38);
						byte[] saveHeader = new byte[16];
						raf.read(saveHeader, 0, 16);
						raf.seek(38);
						byte[] typebyte;
						if(initMACE == 3){
							typebyte = "MAC3MACE 3-to-1\0".getBytes();
						}else{
							typebyte = "MAC6MACE 6-to-1\0".getBytes();
						}
						raf.write(typebyte);
						raf.write(saveHeader);
						
						//音声データを入れる
						//length = Math.min(length, b.length-dataOffset);
						raf.write(b, dataOffset, b.length-dataOffset);
						raf.close();
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
		else{
			//System.out.println("snd resource version 2.");

			/*int referenceCnt =*/ u2(b,offset);
			//System.out.println("reference cnt:"+referenceCnt);
			offset +=2;
			int numCommands = u2(b,offset);
			//System.out.println("numCommands:"+numCommands);
			offset +=2;
			
			//SndCommand & data
			for(int n=0; n<numCommands; n++){
				//System.out.println("b[0]:"+b[offset]);
				//System.out.println("b[1]:"+b[offset+1]);
				int sndcmd_cmd = s2(b,offset);
				//System.out.println("sndcmd_cmd:"+sndcmd_cmd);
				offset +=2;
				if((0x00FF&sndcmd_cmd)==soundCmd){
					//System.out.println("soundCmd!");
				}
				/*int nil =*/ u2(b,offset);
				//System.out.println("nil:"+nil);
				offset +=2;
				
				if((0x00FF&sndcmd_cmd)==soundCmd){
					/*int samplePtr =*/ u4(b,offset);
					offset +=4;
					//System.out.println("samplePtr:"+samplePtr);
					/*int ptrtodata =*/ u4(b,offset);
					offset +=4;
					//System.out.println("ptrtodata:"+ptrtodata);
					int numofSamples = u4(b,offset);
					offset +=4;
					//System.out.println("numofSamples:"+numofSamples);
					int sampleRate = u4(b,offset);
					offset +=4;
					//System.out.println("sampleRate:"+sampleRate);
					/*int startByte =*/ u4(b,offset);
					offset +=4;
					//System.out.println("startByte:"+startByte);
					int endByte = u4(b,offset);
					offset +=4;
					//System.out.println("endByte:"+endByte);
					int baseNote = u2(b,offset);
					offset +=2;
					//System.out.println("baseNote:"+baseNote);
					if(baseNote>32767){
						numofSamples = endByte;
					}
					
					int dataOffset = offset;

					int channel = 1;
					int length = numofSamples;
					
					//AIFF形式に変換
					AudioFormat af = new AudioFormat(sampleRate/65536.0f, 8, channel,
							false/*signed*/, true/*big-endian*/);
					InputStream in = new ByteArrayInputStream(b, dataOffset, length);
					AudioInputStream ais = new AudioInputStream(in, af, length);
					AudioFileFormat.Type type = AudioFileFormat.Type.AIFF;
					if(baseNote>32767){
						//type = AudioFileFormat.Type.AIFC;
					}
					try {
						AudioSystem.write(ais, type, ofile);
						ais.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
					
					if(baseNote>32767){
						//圧縮された音声の場合はデータを書き込み直す
						try {
							RandomAccessFile raf = new RandomAccessFile(ofile,"rw");
							
							//'FORM'のckSizeを圧縮ヘッダ分増やす
							raf.seek(4);
							byte[] ckSizeByte = new byte[4];
							raf.read(ckSizeByte, 0, 4);
							//System.out.println("ckSizeByte:"+ckSizeByte[0]+","+ckSizeByte[1]+","+ckSizeByte[2]+","+ckSizeByte[3]);
							int ckSize = (((int)(0x00FF&ckSizeByte[0]))<<24) +
								(((int)(0x00FF&ckSizeByte[1]))<<16) +
								(((int)(0x00FF&ckSizeByte[2]))<<8) +
								(((int)(0x00FF&ckSizeByte[3]))<<0);
							//System.out.println("ckSize:"+ckSize);
							ckSize += 16;
							raf.seek(4);
							byte[] ckSizeByte2 = new byte[]{
									(byte)(0xFF&(ckSize>>24)), (byte)(0xFF&(ckSize>>16)), (byte)(0xFF&(ckSize>>8)), (byte)(0xFF&ckSize)};
							//System.out.println("ckSizeByte2:"+ckSizeByte2[0]+","+ckSizeByte2[1]+","+ckSizeByte2[2]+","+ckSizeByte2[3]);
							raf.write(ckSizeByte2, 0, 4);
							
							//'AIFF'->'AIFC'
							raf.seek(11);
							raf.write((byte)'C');

							//'COMM'のckSizeを圧縮ヘッダ分増やす
							raf.seek(12+4);
							byte[] cmSizeByte = new byte[4];
							raf.read(cmSizeByte, 0, 4);
							//System.out.println("cmSizeByte:"+cmSizeByte[0]+","+cmSizeByte[1]+","+cmSizeByte[2]+","+cmSizeByte[3]);
							int cmSize = (((int)(0x00FF&cmSizeByte[0]))<<24) +
								(((int)(0x00FF&cmSizeByte[1]))<<16) +
								(((int)(0x00FF&cmSizeByte[2]))<<8) +
								(((int)(0x00FF&cmSizeByte[3]))<<0);
							//System.out.println("cmSize:"+cmSize);
							cmSize += 16;
							raf.seek(12+4);
							byte[] cmSizeByte2 = new byte[]{
									(byte)(0xFF&(cmSize>>24)), (byte)(0xFF&(cmSize>>16)), (byte)(0xFF&(cmSize>>8)), (byte)(0xFF&cmSize)};
							//System.out.println("cmSizeByte2:"+cmSizeByte2[0]+","+cmSizeByte2[1]+","+cmSizeByte2[2]+","+cmSizeByte2[3]);
							raf.write(cmSizeByte2, 0, 4);
							
							//'SSND'の前に圧縮情報を入れる
							raf.seek(38);
							byte[] saveHeader = new byte[16];
							raf.read(saveHeader, 0, 16);
							raf.seek(38);
							byte[] typebyte;
							if(baseNote == 65084){
								typebyte = "MAC6MACE 6-to-1\0".getBytes();
							}else{
								typebyte = "MAC3MACE 3-to-1\0".getBytes();
							}
							raf.write(typebyte);
							raf.write(saveHeader);
							
							//音声データを入れる
							length = Math.min(length, b.length-dataOffset);
							raf.write(b, dataOffset, length);
							raf.close();
						} catch (FileNotFoundException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
		
		try {
			stream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		if(ofile.exists()){
			//変換成功
			return ofile.getName();
		}
		
		return null;
	}


	//-----------
	// CURS
	//-----------
	private static void convertCURS2Cursor(byte[] b, String parentPath, int id,
			String name, Rsrc rsrc)
	{
		//カーソルデータの取り込み
		//16*16bitの画像、マスク、Point型
		
		BufferedImage bi = new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
		DataBuffer db = bi.getRaster().getDataBuffer();
		for(int y=0; y<16; y++){
			for(int x=0; x<16; x++){
				int c = b[y*2+x/8];
				c = 0x01&(c>>(7-x%8));
				int m = b[(y+16)*2+x/8];
				m = 0x01&(m>>(7-x%8));
				int v = 0;
				if(c==0&&m!=0) v = 0xFFFFFFFF;
				if(c!=0&&m!=0) v = 0xFF000000;
				if(c==0&&m==0) v = 0x00000000;
				
				db.setElem(x+y*16, v);
			}
		}
		
		//PNG形式に変換してファイルに保存
		File ofile=new File(parentPath+File.separatorChar+"CURS_"+id+".png");
		try {
			ImageIO.write(bi, "png", ofile);
		} catch (IOException e) {
			e.printStackTrace();
		}

		if(ofile.exists()){
			//変換成功
			int y = u2(b,(16*16*2)/8);
			int x = u2(b,(16*16*2)/8);
			rsrc.addResource(id, "cursor", name, ofile.getName(),
					Integer.toString(x), Integer.toString(y));
		}
		
	}
	
	
	//-----------
	// FONT,NFNT
	//-----------
	@SuppressWarnings("unused")
	private static void convertFONT2PNG(byte[] b, String parentPath, int id, String name, Rsrc rsrc)
	{
		//フォントデータの取り込み
		int offset = 0;
		
		if(b.length==0) {
			//フォント名のためだけに作られるダミー
			rsrc.addResource(id, "font", name, "", "0", "0");
			return;
		}
		
		Rsrc.FontInfo fontinfo = rsrc.new FontInfo();
		
		fontinfo.fontType = u2(b,offset);
		offset+=2;
		//System.out.println("fontType:"+fontType);
		int firstChar = u2(b,offset);
		fontinfo.firstChar = (char)firstChar;
		offset+=2;
		//System.out.println("firstChar:"+firstChar);
		int lastChar = u2(b,offset);
		fontinfo.lastChar = (char)lastChar;
		offset+=2;
		//System.out.println("lastChar:"+lastChar);
		fontinfo.widMax = u2(b,offset);
		offset+=2;
		//System.out.println("widMax:"+widMax);
		fontinfo.kernMax = u2(b,offset);
		offset+=2;
		//System.out.println("kernMax:"+kernMax);
		fontinfo.nDescent = u2(b,offset);
		offset+=2;
		//System.out.println("nDescent:"+nDescent);
		fontinfo.fRectWidth = u2(b,offset);
		offset+=2;
		//System.out.println("fRectWidth:"+fRectWidth);
		int fRectHeight = u2(b,offset);
		fontinfo.fRectHeight = fRectHeight;
		offset+=2;
		//System.out.println("fRectHeight:"+fRectHeight);
		fontinfo.owTLoc = u2(b,offset);
		offset+=2;
		//System.out.println("owTLoc:"+owTLoc);
		fontinfo.ascent = u2(b,offset);
		offset+=2;
		//System.out.println("ascent:"+ascent);
		fontinfo.descent = u2(b,offset);
		offset+=2;
		//System.out.println("descent:"+descent);
		fontinfo.leading = u2(b,offset);
		offset+=2;
		//System.out.println("leading:"+leading);
		int rowWords = u2(b,offset);
		offset+=2;
		//System.out.println("rowWords:"+rowWords);
		int bitimageOffset = offset;
		offset+=rowWords*2*fRectHeight;
		int loctableOffset = offset;
		offset+=((lastChar-firstChar)+2)*2;
		int owtableOffset = offset;
		offset+=((lastChar-firstChar)+2)*2;

		fontinfo.locs = new int[(lastChar-firstChar)+2];
		fontinfo.offsets = new int[(lastChar-firstChar)+2];
		fontinfo.widthes = new int[(lastChar-firstChar)+2];
		for(int i=0; i<((lastChar-firstChar)+2); i++){
			fontinfo.locs[i] = ((0x00FF&b[loctableOffset+i*2])<<8)+((0x00FF&b[loctableOffset+i*2+1]));
		}
		for(int i=0; i<((lastChar-firstChar)+2); i++){
			fontinfo.offsets[i] = b[loctableOffset+i*2];
			fontinfo.widthes[i] = b[loctableOffset+i*2+1];
		}
		
		//System.out.println("compare:"+(b.length - offset));

		if(rowWords==0) return;
		
		BufferedImage bi = new BufferedImage(rowWords*2*8,fRectHeight,BufferedImage.TYPE_INT_ARGB);
		DataBuffer db = bi.getRaster().getDataBuffer();
		for(int y=0; y<fRectHeight; y++){
			for(int x=0; x<rowWords*2*8; x++){
				int c = b[bitimageOffset+(y*rowWords*2*8+x)/8];
				c = 0x01&(c>>(7-x%8));
				db.setElem(x+y*rowWords*2*8, (c==0) ? 0x00FFFFFF:0xFF000000);
			}
		}
		
		//PNG形式に変換してファイルに保存
		File ofile=new File(parentPath+File.separatorChar+"FONT_"+id+".png");
		try {
			ImageIO.write(bi, "png", ofile);
		} catch (IOException e) {
			e.printStackTrace();
		}

		if(ofile.exists()){
			//変換成功
			int x = firstChar;
			int y = lastChar;
			rsrc.addFontResource(id, "font", name, ofile.getName(),
					fontinfo);
		}
		
	}
	
	
	//-----------
	// XCMD,XFCN
	//-----------
	private static void convertXCMD2file(byte[] b,
			String type, int id, String name, OStack stack)
	{
		String funcStr = "";
		if(type.equals("XCMD")||type.equals("xcmd")){
			funcStr = "command";
		}
		else if(type.equals("XFCN")||type.equals("xfcn")){
			funcStr = "function";
		}

		String platform = "";
		if(type.equals("XCMD")||type.equals("XFCN")){
			platform = "68k";
		}
		else if(type.equals("xcmd")||type.equals("xfcn")){
			platform = "ppc";
		}
		
		String path = type+"_"+platform+"_"+id+"_"+name+".data";
		try {
			File file = new File(stack.file.getParent()+File.separatorChar+path);
			FileOutputStream stream = new FileOutputStream(file);
			stream.write(b);
			stream.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		//XCMDマップに追加
		Rsrc.xcmdClass xcmd = new Rsrc.xcmdClass(
				Integer.toString(id), funcStr, name, path, "mac"+platform, Integer.toString(b.length));
		stack.rsrc.addXcmd(xcmd);
	}

	
	//-----------
	// AddColor
	//-----------
	private static void convertAddColorResource(Rsrc.addcolorClass parent,
			byte[] b)
	{
		int offset = 0;
        
		while(offset+2<b.length)
		{
			int type = b[offset];
			offset++;
			
	        if(type==1){
	        	int btnid = u2(b, offset);
	        	offset += 2;
	        	int bevel = u2(b, offset);
	        	offset += 2;
	        	int red = u2(b, offset);
	        	offset += 2;
	        	int green = u2(b, offset);
	        	offset += 2;
	        	int blue = u2(b, offset);
	        	offset += 2;
	        	parent.addBtnObject(btnid, bevel, new Color(red/256, green/256, blue/256), true);
	        }
	        if(type==2){
	        	int fldid = u2(b, offset);
	        	offset += 2;
	        	int bevel = u2(b, offset);
	        	offset += 2;
	        	int red = u2(b, offset);
	        	offset += 2;
	        	int green = u2(b, offset);
	        	offset += 2;
	        	int blue = u2(b, offset);
	        	offset += 2;
	        	parent.addFldObject(fldid, bevel, new Color(red/256, green/256, blue/256), true);
	        }
	        if(type==3){
	        	int top = u2(b, offset);
	        	offset += 2;
	        	int left = u2(b, offset);
	        	offset += 2;
	        	int bottom = u2(b, offset);
	        	offset += 2;
	        	int right = u2(b, offset);
	        	offset += 2;
	        	Rectangle rect = new Rectangle(left,top,right-left,bottom-top);
	        	int bevel = u2(b, offset);
	        	offset += 2;
	        	int red = u2(b, offset);
	        	offset += 2;
	        	int green = u2(b, offset);
	        	offset += 2;
	        	int blue = u2(b, offset);
	        	offset += 2;
	        	parent.addRectObject(rect, bevel, new Color(red/256, green/256, blue/256), true);
	        }
	        if(type==4 || type==5){
	        	int top = u2(b, offset);
	        	offset += 2;
	        	int left = u2(b, offset);
	        	offset += 2;
	        	int bottom = u2(b, offset);
	        	offset += 2;
	        	int right = u2(b, offset);
	        	offset += 2;
	        	Rectangle rect = new Rectangle(left,top,right-left,bottom-top);
	        	boolean transparent = u1(b, offset)==1;
	        	offset += 1;
	        	int nameLen = u1(b, offset);
	        	offset += 1;
	    		String nameStr = strn(b, offset, nameLen);
	        	offset += nameLen;
	        	parent.addPictObject(nameStr, rect, transparent, true);
	        }
		}
	}


	//-----------
	// PLTE
	//-----------
	private static void convertPLTEResource(Rsrc.PlteClass plteOwner, byte[] b, Rsrc rsrc) {
		int offset = 0;
		
    	int windowDef = u4(b, offset);
    	offset += 4;
    	int clearhilite = u2(b, offset);
    	offset += 2;
    	int btnType = u2(b, offset);
    	offset += 2;
    	int pictId = u2(b, offset);
    	offset += 2;
    	int offsetv = u2(b, offset);
    	offset += 2;
    	int offseth = u2(b, offset);
    	offset += 2;
    	@SuppressWarnings("unused")
		int reserved1 = u4(b, offset);
    	offset += 4;
    	@SuppressWarnings("unused")
    	int reserved2 = u4(b, offset);
    	offset += 4;
    	
    	plteOwner.windowDef = windowDef;
    	plteOwner.clearHilite = (clearhilite!=0);
    	plteOwner.btnType = btnType;
    	plteOwner.pictId = pictId;
    	plteOwner.pictHV.x = offseth;
    	plteOwner.pictHV.y = offsetv;
    	
    	int btncount = u2(b, offset);
    	offset += 2;
    	for(int i=0; i<btncount; i++){
    		Rectangle rect = new Rectangle(0,0,0,0);
    		
    		rect.y = u2(b, offset);
        	offset += 2;
    		rect.x = u2(b, offset);
        	offset += 2;
    		rect.height = u2(b, offset)-rect.y;
        	offset += 2;
    		rect.width = u2(b, offset)-rect.x;
        	offset += 2;
        	
        	@SuppressWarnings("unused")
        	int reserved = u2(b, offset);
        	offset += 2;
        	
    		int msgLen = u1(b, offset);
        	offset += 1;
    		String msgStr = strn(b, offset, msgLen);
        	offset += msgLen;
        	if(offset%2==1) offset++;
        	
        	plteOwner.objList.add(rsrc.new plteBtnObject(rect, msgStr));
    	}
	}
	

	//-----------
	// Other Resources
	//-----------
	private static String convertRsrc2file(byte[] b,
			String type, String parentPath, int id, String name)
	{
		String path = parentPath+File.separatorChar+type+"_"+id+".data";
		File file = null;
		try {
			file = new File(path);
			FileOutputStream stream = new FileOutputStream(file);
			stream.write(b);
			stream.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		if(file.exists()){
			//変換成功
			return file.getName();
		}
		
		return null;
	}
}


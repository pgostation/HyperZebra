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

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;


public class HCResource {
	//AppleDoubleHeaderFileの中にリソースフォークのデータがある
	public static boolean readAppleDoubleHeader(DataInputStream dis, OStack stack){
		//System.out.println("readAppleDoubleHeader");
		if(dis==null) return false;
		try {
			byte[] data = dis.readAllBytes();
			byte[] resourceFork = extractResourceForkFromAppleDouble(data);
			if(resourceFork==null) return false;
			return readResourceFork(resourceFork, stack);
		} catch (IOException e) {
			System.err.println("Invalid AppleDouble data: "+e.getMessage());
		}
		return false;
	}

	//AppleDoubleファイル本体のバイト列からリソースフォーク部分だけを取り出す。
	//見つからない、または不正な場合はnull。
	public static byte[] extractResourceForkFromAppleDouble(byte[] data){
		try {
			if(data==null || data.length<26 || u4(data, 0)!=0x00051607) return null;
			int entryCount = u2(data, 24);
			if(entryCount<0 || 26L+12L*entryCount>data.length) return null;
			for(int i=0; i<entryCount; i++){
				int descriptor = 26+12*i;
				if(u4(data, descriptor)!=2) continue;
				long entryOffset = u4long(data, descriptor+4);
				long entryLength = u4long(data, descriptor+8);
				if(entryOffset<0 || entryLength<0 || entryOffset+entryLength>data.length ||
					entryLength>Integer.MAX_VALUE) return null;
				byte[] resourceFork = new byte[(int)entryLength];
				System.arraycopy(data, (int)entryOffset, resourceFork, 0, (int)entryLength);
				return resourceFork;
			}
		} catch (IndexOutOfBoundsException e) {
			System.err.println("Invalid AppleDouble data: "+e.getMessage());
		}
		return null;
	}

	//スタック変換とは無関係に、リソースフォーク内から指定タイプの最初の1件だけを
	//生バイト列のまま取り出す（例：QuickTime動画の'moov'リソース）。見つからなければnull。
	public static byte[] extractRawResourceData(byte[] b, String wantType){
		if(b==null || b.length<16) return null;
		try {

		int dataOffset = u4(b,0);
		int mapOffset = u4(b,4);

		int offset = mapOffset+16+4+2;
		offset+=2; //リソースマップ属性（未使用）

		int typeListOffset = u2(b,offset) + mapOffset + 2;
		offset+=2;
		offset+=2; //nameListOffset（名前は使わないので未使用）
		int typesCount = u2(b,offset) + 1;
		offset+=2;

		for (int i = 0; i < typesCount && offset+8<=b.length; i++) {
			offset = typeListOffset + 8*i;
			String type = str4(b,offset);
			offset+=4;
			int count = u2(b,offset) + 1;
			offset+=2;
			int rsrcoffset = u2(b,offset)+ typeListOffset - 2;
			offset+=2;

			if(!type.equals(wantType)) continue;

			offset = rsrcoffset;
			for (int j=0; j<count && offset+12<=b.length; j++) {
				offset+=2; //id（未使用）
				offset+=2; //nameoffset（未使用）
				offset+=1; //rsrcAttr（未使用）
				int dataoffset = u3(b,offset) + dataOffset;
				offset+=3;
				offset+=4; //reserved

				int datalen = u4(b,dataoffset);
				byte[] data = new byte[datalen];
				System.arraycopy(b, dataoffset+4, data, 0, datalen);
				return data; //最初に見つかった1件を返す
			}
		}

		} catch (IndexOutOfBoundsException | NegativeArraySizeException e) {
			System.err.println("Invalid resource fork: "+e.getMessage());
		}
		return null;
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
	static boolean readResourceFork(byte[] b, OStack stack){ return readResourceFork(b, stack, null); }

	/**
	 * {@code onlyTypes}: when not null, only resources of these types are converted, and one the stack already has (same
	 * type and id) is left alone - the Home stack made at start-up borrows the HyperCard application's icons, sounds and
	 * patterns this way, behind the Home stack's own resources (HomeStack).
	 */
	static boolean readResourceFork(byte[] b, OStack stack, java.util.Set<String> onlyTypes){
		//System.out.println("readResourceFork");
		
		if(b==null || b.length==0) return true;
		if(b.length<16) return false;
		try {
		
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
    			if(stack.bar!=null){ //進捗表示なし(Homeスタックの自動変換など)でも読めるように
    				stack.bar.setValue(stack.barOffset+(25*i)/typesCount+(25*1)*j/count/typesCount);
    				stack.bar.setString("Converting "+type+" resource "+j +"/"+ count);
    				stack.bar.paintImmediately(stack.bar.getBounds());
    			}
        		//System.out.println("====");
            	//ヘッダ部分
            	int id = s2(b,offset);
            	offset+=2;
            	int nameoffset = s2(b,offset);
            	offset+=2;
            	if(onlyTypes!=null && (!onlyTypes.contains(type) || stack.rsrc.getResource1(id, type)!=null)){
            		offset += 8; //attributes, data offset, reserved: skip this resource
            		continue;
            	}
        		//System.out.println("nameoffset:"+nameoffset);
                if(nameoffset>=0) nameoffset += nameListOffset;
        		//System.out.println("nameoffset:"+nameoffset);
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
					name = strn(b,nameoffset+1,namelen);
                }
        		//System.out.println("name:"+name);
        		
        		//データ
                int datalen = u4(b,dataoffset);
                if((rsrcAttr&1)!=0 && MacResourceDecompressor.isCompressed(java.util.Arrays.copyOfRange(b,dataoffset+4,dataoffset+4+Math.max(0,Math.min(datalen,18))))){
                	//System 7の圧縮リソース(resCompressed属性+'dcmp'ヘッダ)は展開した形で取り込む
                	try{
                		byte[] expanded = MacResourceDecompressor.decompress(java.util.Arrays.copyOfRange(b,dataoffset+4,dataoffset+4+datalen));
                		readResourceData(stack, new File(stack.path).getParent(), expanded, 0, expanded.length, type, id, name, rsrcAttr);
                		continue;
                	}catch(IOException e){
                		System.err.println("Cannot expand compressed resource "+type+" "+id+": "+e.getMessage());
                	}
                }
                readResourceData(stack, new File(stack.path).getParent(), b, dataoffset+4, datalen, type, id, name, rsrcAttr);
            }
        }
		
		return true;
		} catch (IndexOutOfBoundsException | NegativeArraySizeException e) {
			System.err.println("Invalid resource fork: "+e.getMessage());
			return false;
		}
	}

	private static final String strn(byte[] b, int offset, int length){
		/*StringBuilder str = new StringBuilder(length);
		for(int i=0; i<length; i++){
			str.append((char)b[offset+i]);
		}
		return str.toString();*/
		return HCData.sanitizeForXml(HCData.currentEncoding.decode(b, offset, length));
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
		return (int)u4long(b, offset);
	}

	private static final long u4long(byte[] b, int offset){
		return ((long)(0xFF&b[offset])<<24)+((long)(0xff&b[offset+1])<<16)+
			((long)(0xff&b[offset+2])<<8)+(long)(0xff&b[offset+3]);
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
	
	
	/**
	 * A resource an external put into an open stack's resource file (AddResource/ChangedResource on CurResFile: G-XCollection's
	 * MtMakeIcon, MtMakePictRsrc): stored in the stack's folder the way an import stores it, replacing one of that type and ID.
	 */
	static void storeResource(OStack stack, byte[] b, String type, int id, String name){
		storeResource(stack, b, type, id, name, 0);
	}

	/** {@link #storeResource} with the resource's attributes (GetResAttrs), kept as an import keeps them. */
	static void storeResource(OStack stack, byte[] b, String type, int id, String name, int attributes){
		readResourceData(stack, stack.file.getParent(), b, 0, b.length, type, id, name==null ? "" : name, attributes);
	}

	//各リソースのデータをファイルに変換する
	private static void readResourceData(OStack stack,
			byte[] in_b, int start, int datalen,
			String type, int id, String name)
	{
		readResourceData(stack, new File(stack.path).getParent(), in_b, start, datalen, type, id, name, 0);
	}

	private static void readResourceData(OStack stack, String parentPath,
			byte[] in_b, int start, int datalen,
			String type, int id, String name, int attributes)
	{
		byte[] b = new byte[datalen];
		System.arraycopy(in_b, start, b, 0, datalen);
		//one resource that cannot be converted is left out, not the whole stack: PowerPalettes' FONT 384 (a size-0 family-name
		//record) holds 68K code, read as a font it asked for a 328944x11935 image and the stack did not open at all
		try{
			convertResourceData(stack, parentPath, b, type, id, name);
			keepAttributes(stack, type, id, attributes);
		}catch(RuntimeException | OutOfMemoryError e){
			System.err.println("Skipped resource "+type+" "+id+" \""+name+"\": "+e);
		}
	}

	/**
	 * The attributes ({@link Rsrc#KEPT_ATTRIBUTES}) of a resource just converted go with it, so GetResAttrs gives an external
	 * what the HyperCard file said (SendAppleEvent's 'PROC' 7910 is resSysHeap+resLocked: only then does it install its
	 * coercion handler).
	 */
	private static void keepAttributes(OStack stack, String type, int id, int attributes){
		attributes &= Rsrc.KEPT_ATTRIBUTES;
		if(attributes==0 || stack.rsrc==null) return;
		if(Rsrc.isExternalMacType(type)){
			Rsrc.xcmdClass x = stack.rsrc.getExternal(type, id);
			if(x!=null) x.attributes = attributes;
			return;
		}
		Rsrc.rsrcClass r = stack.rsrc.getResource1(id, storedType(type));
		if(r!=null) r.attributes = attributes;
	}

	/** The type a resource of this Mac type is kept as in the stack ('snd ' as "SND", 'pict' as "PICT", ...). ColorizeHC's 'Pdat'
	 *  pictures stay 'Pdat': the XCMD loads them by that type (GetNamedResource 'Pdat'), and kept as PICT they were not found. */
	static String storedType(String type){
		if(type.equals("PICT") || type.equals("pict")) return "PICT";
		if(type.equals("snd ")) return "SND";
		if(type.equals("FONT") || type.equals("NFNT")) return "font";
		return type;
	}

	private static void convertResourceData(OStack stack, String parentPath, byte[] b, String type, int id, String name)
	{
		//ファイルに変換
		String filename = null;
		String mytype = type;
		//ICON/cicn/CURS/PAT#/ppatは、以前はインポート時にPNGへ変換して保存していたが、
		//スタックに大量に入っていることがあるアイコン等をインポートのたびに全部
		//デコード・PNGエンコードし直すのは無駄が大きい(実際に開いて見たり編集したり
		//する機会が無いリソースの方がずっと多い)。PICT/pictが元々そうしているように、
		//生データのまま保存しておき、実際に画像として使うとき(サムネイル表示・
		//リソースエディタで開く・ボタンのアイコンとして描画する等、Rsrc.loadImage
		//経由の箇所すべて)に初めて画像へ変換する。
		if(type.equals("ICON")){
			filename = convertRsrc2file(b, "ICON", parentPath, id, name);
			mytype = "ICON";
		}
		else if(type.equals("cicn") ){
			filename = convertRsrc2file(b, "cicn", parentPath, id, name);
			mytype = "cicn";
		}
		else if(type.equals("PICT") || type.equals("pict") ){
			filename = convertPICT2PICTfile(b, parentPath, id);
			mytype = "PICT";
		}
		else if(type.equals("snd ")){
			//ICON/cicn等と同じく、インポート時にAIFFへ変換せず生データのまま
			//保存する。実際に再生・編集するときになって初めてAIFF相当のデータへ
			//デコードする(TSound.openAudioInputStream、decodeSndToAiffBytes参照)。
			filename = convertRsrc2file(b, "SND", parentPath, id, name);
			mytype = "SND";
		}
		else if(type.equals("CURS")){
			//CURSはホットスポット(クリック基準点)をリソースの登録情報として
			//持つため、画像に変換せずとも生データから読み取って登録しておく
			filename = null;
			registerCURSResource(b, parentPath, id, name, stack.rsrc);
		}
		else if(type.equals("PAT#")){
			filename = convertRsrc2file(b, "PAT#", parentPath, id, name);
			mytype = "PAT#";
		}
		else if(type.equals("ppat")){
			filename = convertRsrc2file(b, "ppat", parentPath, id, name);
			mytype = "ppat";
		}
		else if(type.equals("crsr")){
			//crsr(カラーカーソル)もCURSと同様、ホットスポットをリソースの
			//登録情報として持つため、生データから読み取って登録しておく
			filename = null;
			registerCrsrResource(b, parentPath, id, name, stack.rsrc);
		}
		else if(type.equals("FONT") || type.equals("NFNT")){
			//a 'FONT' of size 0 (ID family*128) only names the family (IM Text 4-66): whatever its data is, it is no strike
			convertFONT2PNG(type.equals("FONT") && (id&127)==0 ? new byte[0] : b, parentPath, id, name, stack.rsrc);
		}
		else if(type.equals("HCcd") || type.equals("HCbg")){
			Rsrc.addcolorClass addColorOwner;
			//AddColor finds an HCcd/HCbg by its name, the card's or background's ID: an ID past 32767 has no resource ID of
			//its own (Chronos: card 1041743 is HCcd -6833 named "1041743", its colours were lost)
			String owner = name!=null && name.trim().matches("[0-9]{1,9}") ? name.trim() : Integer.toString(id);
			addColorOwner = stack.rsrc.new addcolorClass(owner, type.equals("HCbg"));
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
	//ICONの生データ(128バイト)を画像へ変換する。ファイルへの書き出しは行わない
	//(インポート時にPNGへ変換して保存する代わりに、生データのまま保存しておき、
	//実際に表示・編集するときにここで初めて画像化する。Rsrc.loadImage参照)。
	static BufferedImage decodeICON(byte[] b)
	{
		//アイコンデータの取り込み
		//単なる32*32bitの固定データ
		BufferedImage bi = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
		DataBuffer db = bi.getRaster().getDataBuffer();
		for(int y=0; y<32; y++){
			for(int x=0; x<32; x++){
				int i = y*4+x/8;
				int c = i<b.length ? b[i] : 0; //128バイトに満たないICON(MacEnquirer 2.0)は残りを白とする
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
		return bi;
	}

	//テスト等、実際にPNGファイルとして書き出したい場合のための薄いラッパー
	private static String convertICON2PNG(byte[] b, String parentPath, int id)
	{
		BufferedImage bi = decodeICON(b);

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
	//cicnの生データを画像へ変換する(ファイルへは書き出さない。decodeICON参照)。
	static BufferedImage decodeCicn(byte[] b)
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
		return bi;
	}

	//テスト等、実際にPNGファイルとして書き出したい場合のための薄いラッパー
	private static String convertcicn2PNG(byte[] b, String parentPath, int id)
	{
		BufferedImage bi = decodeCicn(b);

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
	// PAT# (パターンリスト。1つのリソースに8x8の1bitパターンがN個連続して入っている)
	//-----------
	//PAT#リソースはICON等と違って1つのリソースの中に複数のパターンが入っているため、
	//8x8のパターンを横一列に並べた1枚の画像(幅8*N、高さ8)として編集できるようにする。
	//書き出し(HCResourceExport.patSharp)側もこの横並び表現を8x8ずつに再分割して戻す。
	//PAT#の生データを画像へ変換する(ファイルへは書き出さない。decodeICON参照)。
	//壊れているなどで読み取れなければnullを返す。
	static BufferedImage decodePAT(byte[] b)
	{
		if(b.length<2) return null;
		int count = ((b[0]&0xFF)<<8) | (b[1]&0xFF);
		if(count<0) return null;
		//壊れたデータで巨大な画像を作ってしまわないように、実際に入っているバイト数から
		//読み取れる範囲に制限する
		int maxByCount = (b.length-2)/8;
		if(count>maxByCount) count = maxByCount;
		if(count<=0) return null;

		BufferedImage bi = new BufferedImage(8*count, 8, BufferedImage.TYPE_INT_ARGB);
		DataBuffer db = bi.getRaster().getDataBuffer();
		for(int p=0; p<count; p++){
			int patOffset = 2+p*8;
			for(int y=0; y<8; y++){
				int rowByte = b[patOffset+y]&0xFF;
				for(int x=0; x<8; x++){
					int bit = (rowByte>>(7-x))&0x01;
					db.setElem((p*8+x)+y*(8*count), bit!=0?0xFF000000:0xFFFFFFFF);
				}
			}
		}
		return bi;
	}

	//テスト等、実際にPNGファイルとして書き出したい場合のための薄いラッパー
	private static String convertPAT2PNG(byte[] b, String parentPath, int id)
	{
		BufferedImage bi = decodePAT(b);
		if(bi==null) return null;

		File ofile=new File(parentPath+File.separatorChar+"PAT#_"+id+".png");
		try {
			ImageIO.write(bi, "png", ofile);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return ofile.exists() ? ofile.getName() : null;
	}

	//-----------
	// ppat (ピクセルパターン。PixMap+カラーテーブルを持つカラー版パターン、
	// または8x8の1bit旧形式パターンのみを持つもの)
	//-----------
	//ppatの生データを画像へ変換する(ファイルへは書き出さない。decodeICON参照)。
	static BufferedImage decodePpat(byte[] b)
	{
		BufferedImage bi = null;
		try{
			int patType = u2(b,0);
			int patMapOffset = u4(b,2);
			int patDataOffset = u4(b,6);
			//pat1Data(8バイト、offset20〜27)は白黒版のフォールバックパターン。
			//カラー版(PixMap)が無い場合はこれを8x8画像として使う。
			if(patType==0 || patMapOffset<=0 || patMapOffset+50>b.length){
				bi = new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
				DataBuffer db = bi.getRaster().getDataBuffer();
				for(int y=0; y<8; y++){
					int rowByte = b[20+y]&0xFF;
					for(int x=0; x<8; x++){
						int bit = (rowByte>>(7-x))&0x01;
						db.setElem(x+y*8, bit!=0?0xFF000000:0xFFFFFFFF);
					}
				}
			}
			else{
				//PixMap(cicnのiconPMapと同じレイアウト)
				int offset = patMapOffset;
				/*int baseAddr =*/ u4(b,offset); offset+=4;
				int rowBytes = 0x7FFF & u2(b,offset); offset+=2;
				/*int top =*/ u2(b,offset); offset+=2;
				/*int left =*/ u2(b,offset); offset+=2;
				int bottom = u2(b,offset); offset+=2;
				int right = u2(b,offset); offset+=2;
				/*int pmVersion =*/ u2(b,offset); offset+=2;
				int packType = u2(b,offset); offset+=2;
				/*int packSize =*/ u4(b,offset); offset+=4;
				/*int hRes =*/ u4(b,offset); offset+=4;
				/*int vRes =*/ u4(b,offset); offset+=4;
				/*int pixelType =*/ u2(b,offset); offset+=2;
				int pixelSize = u2(b,offset); offset+=2;
				/*int cmpCount =*/ u2(b,offset); offset+=2;
				/*int cmpSize =*/ u2(b,offset); offset+=2;
				/*int planeBytes =*/ u4(b,offset); offset+=4;
				int pmTable = u4(b,offset); offset+=4;
				/*int pmReserved =*/ u4(b,offset); offset+=4;

				//パレット(pmTableが指す位置にあるColorTable)
				Integer[] palette;
				if(pmTable<=0 || pmTable+8>b.length){
					palette = new Integer[]{0xFF000000, 0xFFFFFFFF};
				}
				else{
					int ctOffset = pmTable;
					/*int ctSeed =*/ u4(b,ctOffset); ctOffset+=4;
					/*int ctFlags =*/ u2(b,ctOffset); ctOffset+=2;
					int ctSize = u2(b,ctOffset); ctOffset+=2;
					palette = new Integer[256];
					for(int i=0; i<=ctSize && ctOffset+8<=b.length; i++){
						int value = 0x00FF & u2(b,ctOffset); ctOffset+=2;
						int red = u2(b,ctOffset); ctOffset+=2;
						int green = u2(b,ctOffset); ctOffset+=2;
						int blue = u2(b,ctOffset); ctOffset+=2;
						if(value<palette.length){
							palette[value] = 0xFF000000 | (((red/256)<<16) + ((green/256)<<8) + ((blue/256)));
						}
					}
				}

				//ピクセルデータ(patDataOffsetから、rowBytes*heightバイト。
				//packType==0なら無圧縮、それ以外はcicnと同じPackBits展開)
				bi = new BufferedImage(right,bottom,BufferedImage.TYPE_INT_ARGB);
				DataBuffer db = bi.getRaster().getDataBuffer();
				int dataPos = patDataOffset;
				for(int y=0; y<bottom; y++){
					byte[] data = new byte[rowBytes];
					if(packType==0){
						for(int i=0; i<rowBytes && dataPos<b.length; i++){
							data[i] = b[dataPos]; dataPos++;
						}
					}
					else{
						//PackBitsを展開(cicnと同じアルゴリズム)
						int rowStart = dataPos;
						int doffset = 0;
						while(dataPos-rowStart<rowBytes && dataPos<b.length){
							int dsize = 0x00FF&b[dataPos]; dataPos++;
							if(dsize>=128){
								dsize = 256-dsize+1;
								byte src = b[dataPos]; dataPos++;
								for(int j=0; j<dsize && j+doffset<data.length; j++){ data[j+doffset] = src; }
								doffset += dsize;
							}
							else{
								dsize++;
								for(int j=0; j<dsize; j++){
									if(rowBytes<=j+doffset){ continue; }
									data[j+doffset] = b[dataPos]; dataPos++;
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
						if(pixelSize==8) idx = idx;
						if(idx>=palette.length || palette[idx]==null) idx = 0;
						int pixel = palette[idx]==null?0xFF000000:palette[idx];
						db.setElem((y*right)+x, pixel);
					}
				}
			}
		} catch(ArrayIndexOutOfBoundsException e){
			//壊れたデータ等。ここまでに作れていた画像があればそれを使う
		}
		return bi;
	}

	//テスト等、実際にPNGファイルとして書き出したい場合のための薄いラッパー
	private static String convertppat2PNG(byte[] b, String parentPath, int id)
	{
		BufferedImage bi = decodePpat(b);
		if(bi==null) return null;

		File ofile=new File(parentPath+File.separatorChar+"ppat_"+id+".png");
		try {
			ImageIO.write(bi, "png", ofile);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return ofile.exists() ? ofile.getName() : null;
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

	static float sampleRateFromSoundHeader(byte[] data, int headerOffset, float fallback){
		if(data==null || headerOffset<0 || headerOffset+12>data.length) return fallback;
		long fixedRate = u4long(data, headerOffset+8);
		float rate = fixedRate/65536.0F;
		return rate>=1000.0F && rate<=192000.0F ? rate : fallback;
	}
	
	
	//テスト等、実際にAIFFファイルとして書き出したい場合のための薄いラッパー。
	//実際のインポートでは呼ばれない(snd リソースは生データのまま保存され、
	//decodeSndToAiffBytes/TSound.openAudioInputStreamが再生・編集時にその場で
	//デコードする。Rsrc.loadImageの音声版に相当)。
	private static String convertSND2AIFF(byte[] b, String parentPath, int id)
	{
		File ofile=new File(parentPath+File.separatorChar+"SND_"+id+".aiff");
		return convertSndToFile(b, ofile) ? ofile.getName() : null;
	}

	//snd リソースの生データを、実際に音として使うとき(再生・編集)にその場で
	//AIFFへ変換する。ファイルには書き出さず、AIFFファイルの中身をそのまま
	//バイト列として返す(呼び出し側でAudioSystem.getAudioInputStreamに渡せる)。
	//内部のデコード処理(convertSndToFile)は実際のファイルへ書き込む形でしか
	//実装されていないため、一時ファイルを介して変換する。
	static byte[] decodeSndToAiffBytes(byte[] b) {
		File tempFile = null;
		try {
			tempFile = TempFiles.createFile("hyperzebra-snd-", ".aiff");
			if(!convertSndToFile(b, tempFile)) return null;
			return java.nio.file.Files.readAllBytes(tempFile.toPath());
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		} finally {
			if(tempFile!=null) tempFile.delete();
		}
	}

	//snd リソースの生データをAIFF形式に変換し、ofileへ書き込む。成功したらtrue。
	private static boolean convertSndToFile(byte[] b, File ofile)
	{
		//FileInputStream作成
		FileOutputStream stream = null;
		try {
			stream = new FileOutputStream(ofile);
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
		if(stream == null){
			return false;
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
			return false;
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
			offset +=2;
			
			//SndCommand & data
			for(int n=0; n<numCommands; n++){
				int sndcmd_cmd = s2(b,offset);
				offset +=2;
				if((0x00FF&sndcmd_cmd)==bufferCmd){
					//System.out.println("bufferCmd!");
				}
				int sndcmd_param1 = u2(b,offset);
				offset +=2;
				int sndcmd_param2 = u4(b,offset);
				offset +=4;
				
				if((0x00FF&sndcmd_cmd)==bufferCmd){
					int in_offset = sndcmd_param2;
					/*int samplePtr =*/ u4(b,in_offset);
					in_offset +=4;
					//System.out.println("samplePtr:"+samplePtr);
					int length = u4(b,in_offset);
					in_offset +=4;
					//System.out.println("length:"+length);
					float sampleRate = u4long(b,in_offset)/65536.0F;
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
					AudioFormat af = new AudioFormat(sampleRate, 8, channel,
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
				
				//sndcmd_cmd==-32687(=0x8051=dataOffsetFlag|bufferCmd)は、圧縮の
				//有無に関わらずインライン音声データを持つ"format 1" snd リソースの
				//ほぼ全てが使う、ごく普通のコマンド値(既に上のbufferCmd分岐で正しく
				//デコード済み)。これをMACE圧縮の判定に使ってしまうと、標準の
				//非圧縮8bit音声(HyperZebraが書き出す音声を含む)まで誤って
				//MACE圧縮として扱われ、6倍前後の長さに水増しされたノイズのような
				//音声になってしまう。圧縮されているかどうかはmodInit(initMACE3/6)
				//でのみ判定する。
				if(initMACE>0){
					float sampleRate = sampleRateFromSoundHeader(b, sndcmd_param2, 11025.0F);
					int dataOffset = 54;//offset+22;
					int length = b.length - dataOffset;
					//AIFF形式に変換
					AudioFormat af = new AudioFormat(sampleRate, 8, channel,
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
						int ckSize = (((int)(0x00FF&ckSizeByte[0]))<<24) +
							(((int)(0x00FF&ckSizeByte[1]))<<16) +
							(((int)(0x00FF&ckSizeByte[2]))<<8) +
							(((int)(0x00FF&ckSizeByte[3]))<<0);
						ckSize += 16;
						raf.seek(4);
						byte[] ckSizeByte2 = new byte[]{
								(byte)(0xFF&(ckSize>>24)), (byte)(0xFF&(ckSize>>16)), (byte)(0xFF&(ckSize>>8)), (byte)(0xFF&ckSize)};
						raf.write(ckSizeByte2, 0, 4);
						
						//'AIFF'->'AIFC'
						raf.seek(11);
						raf.write((byte)'C');

						//'COMM'のckSizeを圧縮ヘッダ分増やす
						raf.seek(12+4);
						byte[] cmSizeByte = new byte[4];
						raf.read(cmSizeByte, 0, 4);
						int cmSize = (((int)(0x00FF&cmSizeByte[0]))<<24) +
							(((int)(0x00FF&cmSizeByte[1]))<<16) +
							(((int)(0x00FF&cmSizeByte[2]))<<8) +
							(((int)(0x00FF&cmSizeByte[3]))<<0);
						cmSize += 16;
						raf.seek(12+4);
						byte[] cmSizeByte2 = new byte[]{
								(byte)(0xFF&(cmSize>>24)), (byte)(0xFF&(cmSize>>16)), (byte)(0xFF&(cmSize>>8)), (byte)(0xFF&cmSize)};
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
					float sampleRate = u4long(b,offset)/65536.0F;
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
					AudioFormat af = new AudioFormat(sampleRate, 8, channel,
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

		return ofile.exists();
	}


	//-----------
	// CURS
	//-----------
	//CURSの生データを画像へ変換する(ファイルへは書き出さない。decodeICON参照)。
	static BufferedImage decodeCURS(byte[] b)
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
		return bi;
	}

	//テスト等、実際にPNGファイルとして書き出したい場合のための薄いラッパー
	private static void convertCURS2Cursor(byte[] b, String parentPath, int id,
			String name, Rsrc rsrc)
	{
		BufferedImage bi = decodeCURS(b);

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
			int x = u2(b,(16*16*2)/8+2);
			rsrc.addResource(id, "CURS", name, ofile.getName(),
					Integer.toString(x), Integer.toString(y));
		}

	}

	//インポート時にはCURSを画像化せず、生データのまま保存する
	//(decodeCURSは実際に表示・編集するときにRsrc.loadImage経由で呼ばれる)。
	//ホットスポットだけはリソースの登録情報として必要なので、生データから
	//直接読み取る(画像デコードは不要な、軽い処理)。
	private static void registerCURSResource(byte[] b, String parentPath, int id,
			String name, Rsrc rsrc)
	{
		String filename = convertRsrc2file(b, "CURS", parentPath, id, name);
		if(filename!=null){
			int y = u2(b,(16*16*2)/8);
			int x = u2(b,(16*16*2)/8+2);
			rsrc.addResource(id, "CURS", name, filename,
					Integer.toString(x), Integer.toString(y));
		}
	}

	//-----------
	// crsr (カラーカーソル。cicn/ppatと同じPixMap+ColorTableに加えて、
	// 白黒版フォールバック(crsr1Data/crsrMask)とホットスポットを持つ)
	//-----------
	//crsrのヘッダ(96バイト): crsrType(2),crsrMap(4,オフセット),
	//crsrData(4,オフセット),crsrXData(4),crsrXValid(2),crsrXHandle(4),
	//crsr1Data(32,16x16の1bit白黒画像),crsrMask(32,16x16の1bitマスク),
	//crsrHotSpot(4=v,h),crsrXTable(4),crsrID(4)
	static BufferedImage decodeCrsr(byte[] b)
	{
		BufferedImage bi = null;
		try{
			int crsrType = u2(b,0);
			int crsrMapOffset = u4(b,2);
			int crsrDataOffset = u4(b,6);
			//crsrType $8001 is a colour cursor, as ResEdit makes them (the test was == $8000: OxSetCCursor's six crsr came out white
			//only); HyperZebra used to write $8000 for its own, so bit 15 is enough, as for the VM's SetCCursor
			if((crsrType&0x8000)!=0 && crsrMapOffset>0 && crsrMapOffset+50<=b.length){
				//PixMap(cicn/ppatと同じレイアウト)
				int offset = crsrMapOffset;
				/*int baseAddr =*/ u4(b,offset); offset+=4;
				int rowBytes = 0x7FFF & u2(b,offset); offset+=2;
				/*int top =*/ u2(b,offset); offset+=2;
				/*int left =*/ u2(b,offset); offset+=2;
				int bottom = u2(b,offset); offset+=2;
				int right = u2(b,offset); offset+=2;
				/*int pmVersion =*/ u2(b,offset); offset+=2;
				int packType = u2(b,offset); offset+=2;
				/*int packSize =*/ u4(b,offset); offset+=4;
				/*int hRes =*/ u4(b,offset); offset+=4;
				/*int vRes =*/ u4(b,offset); offset+=4;
				/*int pixelType =*/ u2(b,offset); offset+=2;
				int pixelSize = u2(b,offset); offset+=2;
				/*int cmpCount =*/ u2(b,offset); offset+=2;
				/*int cmpSize =*/ u2(b,offset); offset+=2;
				/*int planeBytes =*/ u4(b,offset); offset+=4;
				int pmTable = u4(b,offset); offset+=4;
				/*int pmReserved =*/ u4(b,offset); offset+=4;

				Integer[] palette;
				if(pmTable<=0 || pmTable+8>b.length){
					palette = new Integer[]{0xFF000000, 0xFFFFFFFF};
				}
				else{
					int ctOffset = pmTable;
					/*int ctSeed =*/ u4(b,ctOffset); ctOffset+=4;
					/*int ctFlags =*/ u2(b,ctOffset); ctOffset+=2;
					int ctSize = u2(b,ctOffset); ctOffset+=2;
					palette = new Integer[256];
					for(int i=0; i<=ctSize && ctOffset+8<=b.length; i++){
						int value = 0x00FF & u2(b,ctOffset); ctOffset+=2;
						int red = u2(b,ctOffset); ctOffset+=2;
						int green = u2(b,ctOffset); ctOffset+=2;
						int blue = u2(b,ctOffset); ctOffset+=2;
						if(value<palette.length){
							palette[value] = 0xFF000000 | (((red/256)<<16) + ((green/256)<<8) + ((blue/256)));
						}
					}
				}

				bi = new BufferedImage(right,bottom,BufferedImage.TYPE_INT_ARGB);
				DataBuffer db = bi.getRaster().getDataBuffer();
				int dataPos = crsrDataOffset;
				for(int y=0; y<bottom; y++){
					byte[] data = new byte[rowBytes];
					if(packType==0){
						for(int i=0; i<rowBytes && dataPos<b.length; i++){
							data[i] = b[dataPos]; dataPos++;
						}
					}
					else{
						//PackBitsを展開(cicnと同じアルゴリズム)
						int rowStart = dataPos;
						int doffset = 0;
						while(dataPos-rowStart<rowBytes && dataPos<b.length){
							int dsize = 0x00FF&b[dataPos]; dataPos++;
							if(dsize>=128){
								dsize = 256-dsize+1;
								byte src = b[dataPos]; dataPos++;
								for(int j=0; j<dsize && j+doffset<data.length; j++){ data[j+doffset] = src; }
								doffset += dsize;
							}
							else{
								dsize++;
								for(int j=0; j<dsize; j++){
									if(rowBytes<=j+doffset){ continue; }
									data[j+doffset] = b[dataPos]; dataPos++;
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
						int pixel = palette[idx]==null?0xFF000000:palette[idx];
						db.setElem((y*right)+x, pixel);
					}
				}

				//crsrMask(offset52、16x16の1bit)を形の輪郭(アルファ)として適用する。
				//マスクの外の画素は、白なら透明、それ以外の色は画面とXORされる(Macのカラー
				//カーソル)。XORはできないので、白いカードの上での見え方どおりその色で描く
				//(白黒のCURSでマスク外のデータを黒で描くのと同じ近似。OxSetCCursorの
				//crsr 132/133はマスクが空で、雲と稲妻が消えていた)。
				for(int y=0; y<bottom; y++){
					for(int x=0; x<right; x++){
						int m = y<16&&x<16 ? (b[52+y*2+x/8]>>(7-x%8))&0x01 : 0;
						int pixel = db.getElem((y*right)+x);
						if(m==0 && (pixel&0xFFFFFF)==0xFFFFFF) db.setElem((y*right)+x, 0);
					}
				}
			}
		} catch(ArrayIndexOutOfBoundsException e){
			//壊れたデータ等。以下の白黒フォールバックへ進む
		}

		if(bi!=null) return bi;

		//カラー版(PixMap)が無い、または壊れている場合は白黒版
		//(crsr1Data/crsrMask、それぞれoffset20/52の16x16の1bitデータ)を使う
		//(decodeCURSと同じ組み立て方。CURSはimage/maskが連続配置なのに対し、
		//crsrはヘッダの途中にあるのでオフセットだけが異なる)
		bi = new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
		DataBuffer db = bi.getRaster().getDataBuffer();
		for(int y=0; y<16; y++){
			for(int x=0; x<16; x++){
				int c = b[20+y*2+x/8]; c = 0x01&(c>>(7-x%8));
				int m = b[52+y*2+x/8]; m = 0x01&(m>>(7-x%8));
				int v = 0;
				if(c==0&&m!=0) v = 0xFFFFFFFF;
				if(c!=0&&m!=0) v = 0xFF000000;
				if(c==0&&m==0) v = 0x00000000;
				db.setElem(x+y*16, v);
			}
		}
		return bi;
	}

	//インポート時にはcrsrを画像化せず、生データのまま保存する
	//(decodeCrsrは実際に表示・編集するときにRsrc.loadImage経由で呼ばれる)。
	//ホットスポットだけはリソースの登録情報として必要なので、生データから
	//直接読み取る(画像デコードは不要な、軽い処理)。crsrHotSpotはoffset84
	//(v,hの順で2バイトずつ)。
	private static void registerCrsrResource(byte[] b, String parentPath, int id,
			String name, Rsrc rsrc)
	{
		String filename = convertRsrc2file(b, "crsr", parentPath, id, name);
		if(filename!=null){
			int y = u2(b,84);
			int x = u2(b,86);
			rsrc.addResource(id, "crsr", name, filename,
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
		if(b.length<26) return; //shorter than a font record's header
		
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
		//data that is no font strike (the header's strike image must lie inside the resource, the characters be one-byte
		//codes): it is left out instead of making an image of whatever the numbers say
		if(lastChar>255 || firstChar>lastChar || fRectHeight>1024 || (long)bitimageOffset+(long)rowWords*2*fRectHeight>b.length){
			System.err.println("Not a font strike: "+name+" "+id+" ("+b.length+" bytes, "+rowWords+" row words x "+fRectHeight+")");
			return;
		}
		offset+=rowWords*2*fRectHeight;
		//the location and offset/width tables have an entry per code, the missing glyph's, and one more (IM Text 4-71);
		//the offset/width table is where owTLoc (words from its own field, nDescent the high word) says
		int count = (lastChar-firstChar)+3;
		int loctableOffset = offset;
		int owtableOffset = 16+(((short)fontinfo.nDescent>0 ? fontinfo.nDescent<<16 : 0)+fontinfo.owTLoc)*2;
		if(owtableOffset+count*2>b.length) owtableOffset = loctableOffset+count*2;

		fontinfo.locs = new int[count];
		fontinfo.offsets = new int[count];
		fontinfo.widthes = new int[count];
		for(int i=0; i<count; i++){
			int at = loctableOffset+i*2;
			fontinfo.locs[i] = at+1<b.length ? ((0x00FF&b[at])<<8)+(0x00FF&b[at+1]) : 0;
		}
		for(int i=0; i<count; i++){
			int at = owtableOffset+i*2;
			//offset and width are unsigned bytes; $FFFF (255/255) = no glyph
			fontinfo.offsets[i] = at+1<b.length ? 0x00FF&b[at] : 0xFF;
			fontinfo.widthes[i] = at+1<b.length ? 0x00FF&b[at+1] : 0xFF;
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
			//種類バイトの最上位ビットはAddColorの"hide"で隠したオブジェクト
			//以前は符号付きで読んでどの種類にも
			//当てはまらず、次のバイトから読み直してでたらめなオブジェクトを作っていた
			int type = u1(b, offset) & 0x7f;
			boolean visible = (b[offset] & 0x80)==0;
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
	        	parent.addBtnObject(btnid, bevel, new Color(red/256, green/256, blue/256), visible);
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
	        	parent.addFldObject(fldid, bevel, new Color(red/256, green/256, blue/256), visible);
	        }
	        if(type==3){
	        	int top = (short)u2(b, offset); //a QuickDraw Rect: signed (Brigantine's title picture starts at -11,-14)
	        	offset += 2;
	        	int left = (short)u2(b, offset);
	        	offset += 2;
	        	int bottom = (short)u2(b, offset);
	        	offset += 2;
	        	int right = (short)u2(b, offset);
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
	        	parent.addRectObject(rect, bevel, new Color(red/256, green/256, blue/256), visible);
	        }
	        if(type==4 || type==5){
	        	int top = (short)u2(b, offset); //a QuickDraw Rect: signed (Brigantine's title picture starts at -11,-14)
	        	offset += 2;
	        	int left = (short)u2(b, offset);
	        	offset += 2;
	        	int bottom = (short)u2(b, offset);
	        	offset += 2;
	        	int right = (short)u2(b, offset);
	        	offset += 2;
	        	Rectangle rect = new Rectangle(left,top,right-left,bottom-top);
	        	boolean transparent = u1(b, offset)==1;
	        	offset += 1;
	        	int nameLen = u1(b, offset);
	        	offset += 1;
	    		String nameStr = strn(b, offset, nameLen);
	        	offset += nameLen;
	        	parent.addPictObject(nameStr, rect, transparent, visible, type==5);
	        }
	        if(type<1 || type>5){
	        	System.err.println("Unknown AddColor object type "+type+" in HC"+(parent.isBg?"bg":"cd")+" "+parent.id);
	        	break;
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
    	plteOwner.setHiliteType((short)clearhilite); //-1(押している間だけ)/0/最初に点灯するボタン番号
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
	/**
	 * The resource type as part of a file name: a type with characters beyond printable ASCII ('Ñÿmh', NoteMaker's owner
	 * resource, read as "ﾄ\uFFD8mh") becomes its UTF-16 code units in hex - the host refused such a name ("Illegal byte
	 * sequence") and the resource was lost. The type itself is kept in the stack's XML.
	 */
	static String fileSafeType(String type) {
		boolean plain = true;
		for(int i=0; i<type.length(); i++){
			char c = type.charAt(i);
			if(c<0x20 || c>0x7E || c=='/' || c==':' || c=='\\') plain = false;
		}
		if(plain) return type;
		StringBuilder sb = new StringBuilder("x");
		for(int i=0; i<type.length(); i++) sb.append(String.format("%04X", (int)type.charAt(i)));
		return sb.toString();
	}

	private static String convertRsrc2file(byte[] b,
			String type, String parentPath, int id, String name)
	{
		String path = parentPath+File.separatorChar+fileSafeType(type)+"_"+id+".data";
		File file = null;
		try {
			file = new File(path);
			if(file.exists()){
					//大文字小文字だけが違うタイプ(68K版'BITs'とPowerPC版'bits'、
					//'DMCX'/'dmcx'など)は、大文字小文字を区別しないファイルシステムでは同じ
				//ファイル名になり、後から書いた方が先のものを潰していた(68Kコード
				//リソースがPEFになり、書き出したスタックのAddColorが$AAFEで落ちる)。
				//新しいフォルダに同名のファイルがあるのはその場合だけなので、
				//タイプの大文字小文字パターンを付けて別名にする
				file = new File(parentPath+File.separatorChar+fileSafeType(type)+"_"+id+"_"+caseTag(type)+".data");
			}
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

	//リソースタイプの大文字小文字パターン('BITs' → "UUUl")
	private static String caseTag(String type){
		StringBuilder tag = new StringBuilder();
		for(int i=0; i<type.length(); i++){
			char c = type.charAt(i);
			tag.append(Character.isUpperCase(c) ? 'U' : Character.isLowerCase(c) ? 'l' : '-');
		}
		return tag.toString();
	}
}

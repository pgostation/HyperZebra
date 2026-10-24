import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;


//import org.apache.tools.tar.TarEntry;
//import org.apache.tools.tar.TarInputStream;




public class HCConvert {
	/** True after the last openHCStack/openMacBinaryStack returned false because the user cancelled the password of a
	 *  Private Access stack. */
	static boolean cancelled;

	public static boolean openHCStack(String path, OStack stack) {
		//System.out.println("openHCStack...");
		cancelled = false;

		byte[] dataFork;
		try {
			dataFork = java.nio.file.Files.readAllBytes(new File(path).toPath());
		} catch (IOException e) {
			System.err.println("Could not read HyperCard data fork: "+e.getMessage());
			return false;
		}

		// Resource Forkがなくてもデータフォークだけのスタックとして開ける。
		byte[] resourceFork = getResourceForkBytes(path);

		//スタックファイルには文字コードを記録するフィールドがない(HYPERCARD_STACK_FORMAT.md)ので、
		//スタック内のテキストから推定する(StackEncodingDetector)。テキストがASCIIだけならリソースフォークも見る
		useEncoding(stack, StackEncodingDetector.detect(dataFork, resourceFork, null, new File(path).getName(), defaultEncoding()));

		//進捗表示を開始(小さなスタックは出さない)
		StackProgress.open(stack, new File(path).getName(), "Creating stack folder",
			(long)dataFork.length+(resourceFork==null ? 0 : resourceFork.length));
    	
		//ディレクトリを作成
		if(!CreateStackFolder(stack, path)){
			closeProgress(stack);
			return false;
		}

		stack.bar.setString("Reading HyperCard data fork");
		boolean dataRead = HCData.readDataFork(new DataInputStream(new ByteArrayInputStream(dataFork)), stack);
		dataFork = null;
		if(!dataRead){
			closeProgress(stack);
			//Private Accessのパスワードを入れずにやめた: 作りかけのフォルダを残さない
			if(cancelled) deleteTree(stack.file.getParentFile());
			return false;
		}

		stack.barOffset = 75; //データフォークが0-73%、リソースが残りを使う
		if(resourceFork!=null) HCResource.readResourceFork(resourceFork, stack);
		closeProgress(stack);
		stack.setName(new File(path).getName());
		return true;
	}



	public static boolean openMacBinaryStack(String path, OStack stack) {
		//System.out.println("openMacBinaryHCStack...");
		cancelled = false;

		//MacBinaryは128バイトのヘッダ
		//+ データフォーク  (データフォークの長さを128byteで切り上げる)
		//+ リソースフォーク
		//
		//lzh形式のファイルを解凍するとMacBinary形式だったりする。
		//一部のWindows用解凍ソフトには、解凍後にMacBinaryへ変換するオプションがある
		
		byte[] header = new byte[128];
		try (FileInputStream fis = new FileInputStream(new File(path))) {
			if(fis.readNBytes(header, 0, header.length)!=header.length ||
				header[65]!='S' || header[66]!='T' || header[67]!='A' || header[68]!='K'){
				closeProgress(stack);
				return false;
			}
			long dataForkLength = unsignedInt(header, 83);
			long resourceForkLength = unsignedInt(header, 87);
			long resourceOffset = 128L + ((dataForkLength+127L)/128L)*128L;
			long requiredLength = resourceOffset + resourceForkLength;
			if(dataForkLength<=0 || dataForkLength>Integer.MAX_VALUE ||
				resourceForkLength>Integer.MAX_VALUE || requiredLength>new File(path).length()){
				closeProgress(stack);
				return false;
			}

			//進捗表示を開始(小さなスタックは出さない)
			StackProgress.open(stack, new File(path).getName(), "", dataForkLength+resourceForkLength);

			int dataForkLen = (int)dataForkLength;
			//データフォーク部分をbyte配列に読み込む
			byte[] dataBytes = fis.readNBytes(dataForkLen);
			if(dataBytes.length!=dataForkLen) throw new IOException("Short MacBinary data fork");

			//文字コードはスタック内のテキストから推定する(StackEncodingDetector)。
			//ヘッダのファイル名(1バイト目が長さ)もスタックと同じ文字コードで書かれている
			int nameLength = Math.min(header[1]&0xFF, 63);
			java.util.List<byte[]> macName = java.util.Collections.singletonList(java.util.Arrays.copyOfRange(header, 2, 2+nameLength));
			useEncoding(stack, StackEncodingDetector.detect(dataBytes, macBinaryResourceFork(path, resourceOffset, resourceForkLength),
				macName, new File(path).getName(), defaultEncoding()));

			if(!CreateStackFolder(stack, path)){
				closeProgress(stack);
				return false;
			}
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(dataBytes));

			//データフォーク読み込み
			if(HCData.readDataFork(dis, stack)){
				dataBytes = null;
				
				int rsrcForkLen = (int)resourceForkLength;

				byte[] rsrcBytes = null;
				if(rsrcForkLen>0){ //リソースフォークがあるか？
					//データフォークの次のブロックまで読み飛ばす
					int padding = (128-dataForkLen%128)%128;
					if(fis.readNBytes(padding).length!=padding) throw new IOException("Short MacBinary padding");
	
					//リソースフォーク部分をbyte配列に読み込む
					rsrcBytes = fis.readNBytes(rsrcForkLen);
					if(rsrcBytes.length!=rsrcForkLen) throw new IOException("Short MacBinary resource fork");
				}
				
				stack.barOffset = 75;
				if(rsrcForkLen==0 || HCResource.readResourceFork(rsrcBytes, stack)){
					closeProgress(stack);
					
					stack.setName(new File(path).getName());
					return true;
				}
				else{
					System.out.println("Error occured at reading resource data from MacBinary file.");
		    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Error occured at reading MacBinary HyperCard stack data."),
		    				null,"OK",null,null);
				}
			}
			else if(cancelled){
				//Private Accessのパスワードを入れずにやめた
				deleteTree(stack.file.getParentFile());
			}
			else{
				System.out.println("Error occured at reading data from MacBinary file.");
	    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Error occured at reading MacBinary HyperCard stack data."),
	    				null,"OK",null,null);
			}
		} catch (IOException e) {
			System.err.println("Could not read MacBinary stack: "+e.getMessage());
		}

		closeProgress(stack);
		return false;
	}

	/** The resource fork of a MacBinary file (for the encoding guess; the import reads it again in order), or null. */
	private static byte[] macBinaryResourceFork(String path, long offset, long length) {
		if(length<=0 || length>(64<<20)) return null;
		try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path, "r")) {
			byte[] fork = new byte[(int)length];
			file.seek(offset);
			file.readFully(fork);
			return fork;
		} catch (IOException e) {
			return null;
		}
	}

	/** The encoding of a stack whose text does not tell it: the one of the user's language. */
	private static StackTextEncoding defaultEncoding() {
		PCARD pc = PCARD.pc;
		return pc!=null && pc.lang!=null ? StackTextEncoding.forLegacyLanguageSetting(pc.lang) : HCData.currentEncoding;
	}

	private static void useEncoding(OStack stack, StackTextEncoding encoding) {
		System.out.println("Stack text encoding: "+encoding.name());
		HCData.setCurrentEncoding(encoding);
		stack.legacyTextEncoding = encoding;
	}

	private static long unsignedInt(byte[] bytes, int offset) {
		return ((long)(bytes[offset]&0xFF)<<24) | ((long)(bytes[offset+1]&0xFF)<<16) |
			((long)(bytes[offset+2]&0xFF)<<8) | (long)(bytes[offset+3]&0xFF);
	}

	private static void closeProgress(OStack stack) {
		StackProgress.close(stack);
	}

	//指定パスのファイルのリソースフォークの生データを取得する。
	//ホストのリソースフォーク（macOSの..namedfork/rsrc、WindowsのNTFSストリームAFP_Resource）が無ければ、
	//AppleDoubleのサイドカーファイル（._ファイル名）を探す。
	//HyperCardスタック以外の単独ファイルを開く際のリソース変換フォールバックにも使う。
	public static byte[] getResourceForkBytes(String path) {
		File source = new File(path);
		File nativeFork = ResourceFile68k.nativeForkFile(source);
		if(nativeFork!=null) try (FileInputStream input = new FileInputStream(nativeFork)) {
			byte[] resourceData = input.readAllBytes();
			if(resourceData.length>0) return resourceData;
		} catch (IOException e) {
			// Resource ForkのないファイルではAppleDoubleを探す。
		}

		//._name のほか、Finderで圧縮したzipをWindows等で展開してできる __MACOSX ミラーも探す
		for(File candidate: ResourceFile68k.AppleDouble.sidecarCandidates(source)){
			if(!candidate.isFile()) continue;
			try (FileInputStream input = new FileInputStream(candidate)) {
				return HCResource.extractResourceForkFromAppleDouble(input.readAllBytes());
			} catch (IOException e) {
				System.err.println("Could not read AppleDouble resource fork: "+e.getMessage());
				return null;
			}
		}
		//DOSディスクにPC Exchangeで置かれたファイルは、フォルダのRESOURCE.FRKにDOS名で入っている
		return PcExchange.resourceFork(source);
	}

	//HyperCardスタック以外の単独ファイルが持つリソースフォークを、スタック変換のときと
	//同じ要領でフォルダに変換する（PICT/ICON/cicn/snd/CURS/FONTなど全種類のリソースを
	//変換して1つずつファイルに保存し、stack.rsrcに登録する）。
	//変換できたリソースが1つもなければfalseを返す。
	public static boolean convertResourceForkToFolder(String path, OStack stack) {
		byte[] resourceData = getResourceForkBytes(path);
		if(resourceData==null || resourceData.length==0) return false;

		//進捗表示を開始
		StackProgress.open(stack, new File(path).getName(), "Converting resources", resourceData.length);

		if(!CreateResourceFolder(stack, path)){
			closeProgress(stack);
			return false;
		}

		boolean success;
		try{ success = HCResource.readResourceFork(resourceData, stack); }
		catch(RuntimeException e){ success = false; }
		closeProgress(stack);
		if(success){
			stack.setName(new File(path).getName());
		}else{
			//変換できなかった（壊れたリソースフォーク等）ときは、作ったフォルダを残さない
			deleteTree(stack.file.getParentFile());
		}
		return success;
	}

	private static void deleteTree(File f){
		if(StackBundle.discard(f))return; //the working folder of a stack file not written yet
		if(f==null||!f.exists())return;
		File[] children=f.listFiles();
		if(children!=null)for(File c:children)deleteTree(c);
		f.delete();
	}

	//HyperCardスタックの".hzb"フォルダと同じ要領で、リソース変換専用のフォルダを作成する。
	private static boolean CreateResourceFolder(OStack stack, String path) {
		stack.bar.setValue(2);
		stack.bar.setString("Create a new folder");
		stack.bar.paintImmediately(stack.bar.getBounds());

		//ディレクトリを作成
		File newdir = new File(path+" Resources");
		int i=2;
		while(newdir.exists()){
			newdir = new File(path+" Resources "+i);
			i++;
		}
		if(!newdir.mkdir()){
    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Can't create a new folder."),
    				null,"OK",null,null);
			return false;
		}
		stack.path = newdir.getPath()+File.separatorChar+"_resources.xml";
		stack.file = new File(stack.path);

		return true;
	}


	private static boolean CreateStackFolder(OStack stack, String path) {
		stack.bar.setValue(2);
		stack.bar.setString("Create a new folder");
		stack.bar.paintImmediately(stack.bar.getBounds());
		
		//変換先は元のスタックの隣の1ファイル形式のスタック "X.hzb"(ZIP)。中身は作業フォルダに書き、保存のたびに
		//ファイルへ書き戻す(StackBundle)。同じ名前の .hzb(以前のフォルダ形式も)があれば "X 2.hzb", "X 3.hzb", …
		File packed = new File(path+".hzb");
		int i=2;
		while(packed.exists()){
			packed = new File(path+" "+i+".hzb");
			i++;
		}
		File newdir;
		try{
			if(!java.nio.file.Files.isWritable(packed.getAbsoluteFile().getParentFile().toPath())) throw new IOException("not writable");
			newdir = StackBundle.create(packed);
		}catch(IOException e){
    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Can't create a new folder."),
    				null,"OK",null,null);
			return false;
		}
		stack.path = newdir.getPath()+File.separatorChar+"_stack.xml";
		stack.file = new File(stack.path);
		
		return true;
	}
	
	
	public static File MakeAppleDoubleTar(String path){
		//MacOSXであること
		String os=System.getProperty("os.name");
        if(os!=null && os.startsWith("Mac OS X")){
        }
        else{
    		/*new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Converting from HyperCard Stack is need to run on MacOSX."),
    				null,"OK",null,null);*/
        	return null;
        }

    	String srcpath = path;
        String tarpath = path;
        for(int i=0; i<srcpath.length(); i++){
        	if(srcpath.charAt(i)>=128 || srcpath.length()>=100-2){ //2byte文字やパスがtarに格納できるより長かったら
        		String renameStr;
        		int j=1;
        		while(true){
        			renameStr = System.getProperty("java.io.tmpdir")+File.separatorChar+"hyperzebra_renamed"+j;
        			if(!new File(renameStr).exists()){
        				break;
        			}
        			j++;
        		}
        		new File(srcpath).renameTo(new File(renameStr));
        		srcpath = renameStr;
        		
        		{
        			//コピーを作るとリソースフォークが失われてしまう
        			
	        		//日本語パス対策でテンポラリにスタックのコピーを作る
	        		/*srcpath = System.getProperty("java.io.tmpdir")+File.separatorChar+"hyperzebra_stack";
	
					try {
		    		    FileChannel srcChannel = new FileInputStream(path).getChannel();
		    		    FileChannel destChannel = new FileOutputStream(srcpath).getChannel();
		    		    try {
		    		        srcChannel.transferTo(0, srcChannel.size(), destChannel);
		    		    } finally {
		    		        srcChannel.close();
							destChannel.close();
		    		    }
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}*/
        		}

        		//日本語パス対策でテンポラリにtarアーカイブを作る
        		tarpath = System.getProperty("java.io.tmpdir")+File.separatorChar+"hyperzebra_work";
        		break;
        	}
        }
        
        String path2 = tarpath;
    	File file = new File(path2+".tar");
    	int i=1;
    	while(file.exists()){
    		path2 = tarpath+" "+i;
        	file = new File(path2+".tar");
    		i++;
    	}
    	
        //Appleのtarで保存
		try{
			ProcessBuilder pb = new ProcessBuilder("tar", "-cvf", path2+".tar", srcpath);
			//ProcessBuilder pb = new ProcessBuilder("tar", "-cvf", path+".tar", path);
			Process p = pb.start();
			p.waitFor();
			InputStream is = p.getInputStream();
			BufferedReader br = new BufferedReader(new InputStreamReader(is));
	        boolean isSuccess = false;
			for (;;) {
				String line = br.readLine();
				if (line == null) break;
				//System.out.println("tar>"+line);
				if(line.startsWith("a "+srcpath)){
					isSuccess = true;
					break;
				}
			}
			if(!isSuccess){
				//successしない
				//System.out.println("Error occured at creating tar archive.");
			}
		} catch (IOException e1) {
			e1.printStackTrace();
			file = null;
		} catch (InterruptedException e1) {
			e1.printStackTrace();
			file = null;
		}
		
		if(!srcpath.equals(path)){
			//ファイルを移動していたら元に戻す
			new File(srcpath).renameTo(new File(path));
		}
		
		if(file!=null){
	    	File ofile = new File(path2+".tar");
	    	if(ofile.exists()){
	    		return file;
	    	}
		}
    	return null;
	}
	
	
	static public String getConvertPath(String inPath){
		byte[] sjisByte = null;
		try {
			sjisByte = inPath.getBytes("SJIS");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		
		String sjisPath = "";
		try {
			sjisPath = new String(sjisByte, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		
		return sjisPath;
	}

	
	public static boolean readFromTar(File tarFile, OStack stack){
		//System.out.println("readFromTar...");
		cancelled = false;
		FileInputStream fis;
		MyTarInputStream tin = null;
		boolean success = true;
		boolean foundDataFork = false;
		try {
			fis = new FileInputStream(tarFile);
			tin = new HCConvert().new MyTarInputStream(fis); 
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		//Apache antのライブラリでtarを扱う
		if(tin==null) return false;
		try {
			MyTarEntry tarEnt = tin.getNextEntry();
			while (tarEnt != null) {
				String name = tarEnt.getName();
				int size = tarEnt.getSize();
				ByteArrayOutputStream bos = new ByteArrayOutputStream(size);
				tin.copyEntryContents(bos);
				byte[] data = bos.toByteArray();
				DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

				if(name.contains("._")){
					//Apple Double Header File(リソースフォークを含む)
					if(!HCResource.readAppleDoubleHeader(dis, stack)){
						System.out.println("Error occured at reading resouce fork from tar file.");
						success = false;
					}
					stack.barOffset += 25;
				}
				else{
					//データフォーク
					foundDataFork = true;
					if(!HCData.readDataFork(dis, stack)){
						System.out.println("Error occured at reading data fork from tar file.");
						if(!cancelled) new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Error occured at reading HyperCard stack data."),
								null,"OK",null,null);
						success = false;
					}
					stack.barOffset += 73;
				}
				
				dis.close();
				tarEnt = tin.getNextEntry();
			}
			tin.close();
		} catch (IOException e) {
			e.printStackTrace();
			success = false;
		}
		
		return success && foundDataFork;
	}
	
	class MyTarInputStream{
		private FileInputStream fis;
		private int size;
		
		MyTarInputStream(FileInputStream fis){
			this.fis = fis;
		}
		
		public void copyEntryContents(ByteArrayOutputStream bos) throws IOException {
			for(int i=0; i<size; i++){
				bos.write(fis.read());
			}
			//パディング
			if(512-((size-1)%512+1)>0){
				for(int i=0; i<512-((size-1)%512+1); i++){
					fis.read();
				}
			}
		}
		
		public MyTarEntry getNextEntry() throws IOException{
			//ヘッダ512byte読み込み
			StringBuilder name = new StringBuilder(100);
			for(int i=0; i<100; i++){
				name.append((char)fis.read());
			}
			for(int i=0; i<24; i++){
				fis.read();
			}
			if(name.toString().equals("")){
				return null; //終端っぽい
			}
			
			StringBuilder sizeStrbldr = new StringBuilder(12);
			int j;
			for(j=0; j<11; j++){
				char c = (char)fis.read();
				if(c!='0'){
					sizeStrbldr.append(c);
					j++;
					break;
				}
			}
			for(; j<11; j++){
				char c = (char)fis.read();
				sizeStrbldr.append(c);
				if(c<=0){
					return null;
				}
			}
			String sizeStr=sizeStrbldr.toString();
			if(sizeStr.equals("")){
				return null; //終端っぽい
			}
			size = Integer.valueOf(sizeStr,8);
			for(int i=0; i<512-100-24-11; i++){
				fis.read();
			}
			
			//MyTarEntryクラスを作成して名前とサイズを返す
			MyTarEntry entry = new MyTarEntry(name.toString(), size);
			return entry;
		}
		
		public void close() throws IOException{
			fis.close();
		}
	}
	
	class MyTarEntry{
		String name;
		int size;
		
		MyTarEntry(String name, int size){
			this.name = name;
			this.size = size;
		}
		
		public String getName() {
			return name;
		}

		public int getSize() {
			return size;
		}
	}
}

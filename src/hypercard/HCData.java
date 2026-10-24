import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
//import java.io.FileOutputStream;
//import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import javax.imageio.ImageIO;


public class HCData {
	private static class HCDataReadException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		HCDataReadException(String message, Throwable cause) { super(message, cause); }
		HCDataReadException(String message) { super(message); }
	}

	/**
	 * Encoding currently used to decode the text of the stack being read.
	 * Defaults to Japanese for backward compatibility (HyperZebra used to
	 * only support that one encoding); set it via {@link #setCurrentEncoding}
	 * before converting a stack authored with a different Mac OS script.
	 */
	static StackTextEncoding currentEncoding = StackTextEncoding.JAPANESE;

	static void setCurrentEncoding(StackTextEncoding encoding) {
		currentEncoding = encoding == null ? StackTextEncoding.JAPANESE : encoding;
	}

	public static boolean readDataFork(DataInputStream dis, OStack stack){
		//FTBL/STBLはファイル中のどこにでも置かれ得るが、実際のスタックでは
		//CARD/BKGDより後ろ(ファイル末尾寄り)に置かれていることが多い。
		//CARD/BKGD内のパートはこの時点のstack.fontList/styleListを検索して
		//フォントID・スタイルIDを名前へ解決するため、単純にファイル先頭から
		//1パスで読むと、FTBL/STBLがまだ読まれていない段階でCARD/BKGDを解決
		//してしまい、フォント指定が(常に既定フォントへ)失われてしまう。
		//これを避けるため、まずファイル全体をバッファに読み込み、FTBL/STBLだけ
		//先に一度読み込んでおいてから、通常通りの1パス読み込みを行う
		//(通常パスではFTBL/STBLに再度出会っても読み直さない)。
		byte[] allBytes;
		try {
			allBytes = dis.readAllBytes();
		} catch (IOException e) {
			System.err.println("HyperCard data fork could not be buffered: "+e.getMessage());
			return false;
		}
		int skip = stackOffset(allBytes);
		if(skip>0) allBytes = java.util.Arrays.copyOfRange(allBytes, skip, allBytes.length);
		if(!unlockPrivateAccess(allBytes, stack)) return false;
		int preReadErrCount = preReadTableBlocks(allBytes, stack);
		return readDataForkBlocks(allBytes, stack, preReadErrCount);
	}

	/**
	 * A Private Access stack has part of its STAK header encrypted with its password (StackProtection): asks for the
	 * password, as HyperCard does when opening one, and decrypts the header in place. False when it was not given
	 * (HCConvert.cancelled is then set).
	 */
	static boolean unlockPrivateAccess(byte[] allBytes, OStack stack){
		if(allBytes.length<0x50 || allBytes[4]!='S' || allBytes[5]!='T' || allBytes[6]!='A' || allBytes[7]!='K') return true;
		if(!StackProtection.isPrivateAccess(allBytes, 0)) return true;
		StackTextEncoding encoding = stack.legacyTextEncoding!=null ? stack.legacyTextEncoding : currentEncoding;
		if(stack.unlockedPassword!=null && StackProtection.decryptHeader(allBytes, 0, stack.unlockedPassword, encoding)) return true;
		String name = stack.file!=null && stack.file.getParentFile()!=null
			? stack.file.getParentFile().getName().replaceFirst("(?i)\\.hzb$", "") : stack.name;
		String pw = StackProtection.askUntilRight(name, typed -> StackProtection.decryptHeader(allBytes, 0, typed, encoding));
		if(pw==null){
			HCConvert.cancelled = true;
			return false;
		}
		stack.unlockedPassword = pw;
		return true;
	}

	/** A block of the data fork: where it starts, its size (header included) and its type. */
	static final class BlockSpan {
		final int offset, size;
		final String type;
		BlockSpan(int offset, int size, String type){ this.offset = offset; this.size = size; this.type = type; }
	}

	private static int int32(byte[] data, int at){
		return ((data[at]&0xFF)<<24)|((data[at+1]&0xFF)<<16)|((data[at+2]&0xFF)<<8)|(data[at+3]&0xFF);
	}

	private static String blockType(byte[] data, int at){
		return new String(new char[]{(char)(data[at+4]&0xFF), (char)(data[at+5]&0xFF), (char)(data[at+6]&0xFF), (char)(data[at+7]&0xFF)});
	}

	/** A header whose size stays inside the data and whose type is four printable characters. */
	private static boolean plausibleBlock(byte[] data, int at){
		if(at<0 || at+8>data.length) return false;
		long size = Integer.toUnsignedLong(int32(data, at));
		if(size<8 || at+size>data.length) return false;
		for(int i=4; i<8; i++) if((data[at+i]&0xFF)<0x20 || (data[at+i]&0xFF)>0x7E) return false;
		return true;
	}

	/**
	 * Offsets of the blocks the master index (MAST) lists. HyperCard finds its blocks through this index, so a stack that
	 * has not been compacted can keep unused bytes between blocks - neither a block nor marked FREE - that a walk from block
	 * to block cannot step over ("CLUT Conversion" of the Color XCMD 1.3 package: 320 stale bytes before its only card).
	 */
	private static final java.util.Set<String> KNOWN_BLOCKS = new java.util.HashSet<String>(java.util.Arrays.asList(
		"STAK","MAST","LIST","PAGE","BKGD","CARD","BMAP","FREE","STBL","FTBL","PRNT","PRST","PRFT","TAIL"));

	static java.util.TreeSet<Integer> masterBlockOffsets(byte[] data){
		java.util.TreeSet<Integer> offsets = new java.util.TreeSet<Integer>();
		for(int at=0; plausibleBlock(data, at); at += int32(data, at)){
			if(!blockType(data, at).equals("MAST")) continue;
			int end = at+int32(data, at);
			//ヘッダ(12バイト)の後の20バイトは予約。以降の各4バイト: 上位3バイト=ファイル内位置/32、下位1バイト=ブロックIDの下位8ビット
			for(int k=at+32; k+4<=end; k+=4){
				int entry = int32(data, k);
				if(entry==0) continue;
				int offset = (entry>>>8)*32;
				if(plausibleBlock(data, offset)) offsets.add(offset);
				else{
					//bytes lost or added in a copy (HyperFrog SW on Info-Mac II: from one card on, every block sits 2 bytes early):
					//the block whose ID ends in the entry's low byte within a few bytes of where the index says
					for(int d=1; d<32; d++){
						int found = -1;
						for(int cand : new int[]{offset-d, offset+d}){
							if(plausibleBlock(data, cand) && KNOWN_BLOCKS.contains(blockType(data, cand)) && cand+12<=data.length &&
									(int32(data, cand+8)&0xFF)==(entry&0xFF)){ found = cand; break; }
						}
						if(found>=0){ offsets.add(found); break; }
					}
				}
			}
			break;
		}
		return offsets;
	}

	/**
	 * The blocks of a data fork in file order, up to TAIL. Unused bytes between blocks are skipped: the walk goes on at the
	 * next block the master index lists (or, past its last entry, at the next 32-byte boundary with a block header).
	 * error[0] tells why the walk stopped early (null when it reached TAIL or the end of the data).
	 */
	static java.util.List<BlockSpan> blockSpans(byte[] data, String[] error){
		java.util.List<BlockSpan> spans = new ArrayList<BlockSpan>();
		java.util.TreeSet<Integer> master = null;
		int stackEnd = stackEnd(data);
		int at = 0;
		while(at+8<=data.length){
			boolean valid = plausibleBlock(data, at);
			if(valid && at>0){
				//索引に載っているブロックを内側に含むヘッダは、使われていない領域に残った古いデータ
				if(master==null) master = masterBlockOffsets(data);
				Integer inner = master.higher(at);
				if(inner!=null && inner < at+(long)Integer.toUnsignedLong(int32(data, at)) && !master.contains(at)) valid = false;
			}
			if(!valid){
				if(master==null) master = masterBlockOffsets(data);
				int next = -1;
				//a listed block a few bytes before here: the previous block's size runs into it (bytes lost in a copy: HyperFrog SW)
				int lastStart = spans.isEmpty() ? -1 : spans.get(spans.size()-1).offset;
				Integer listed = master.higher(Math.max(lastStart, at-32));
				if(listed!=null && listed>=at-31 && listed<at && spans.get(spans.size()-1).offset+(long)spans.get(spans.size()-1).size==at){
					//shorten the previous block to end where the listed one begins
					BlockSpan p = spans.remove(spans.size()-1);
					spans.add(new BlockSpan(p.offset, listed-p.offset, p.type));
				}
				else listed = master.higher(at);
				if(listed!=null) next = listed;
				//only up to the stack's own size: past it the fork can hold anything (パーフェクション・リベンジ: 11 MB of other
				//files' sectors after a 165 KB stack), and a block found there would be read as part of this stack
				else for(int k=(at/32+1)*32; k+8<=stackEnd; k+=32){
					if(plausibleBlock(data, k) && KNOWN_BLOCKS.contains(blockType(data, k))){ next = k; break; }
				}
				if(next<0){
					//最後の空き(FREE)ブロックやTAILの途中で切れたファイル(Christmas Carols: FREE が122バイト足りない)は
					//内容が欠けていないので正常な終わりとする(以前は失敗扱いで _stack.xml を書かず書き込み禁止で開いていた)
					String t = blockType(data, at);
					if(t.equals("FREE") || t.equals("TAIL")) break;
					if(error!=null && error.length>0) error[0] = "invalid block size "+Integer.toUnsignedLong(int32(data, at))+" at "+at;
					break;
				}
				at = next;
				continue;
			}
			int size = int32(data, at);
			String type = blockType(data, at);
			spans.add(new BlockSpan(at, size, type));
			if(type.equals("TAIL")) break;
			at += size;
		}
		return spans;
	}

	/**
	 * Where the stack ends: the file size its STAK header records (offset 0x14) when that lies inside the data, else the
	 * end of the data. The data fork can be longer than the stack (bytes of other files after it).
	 */
	static int stackEnd(byte[] data){
		if(data.length<0x18 || !blockType(data, 0).equals("STAK")) return data.length;
		long size = Integer.toUnsignedLong(int32(data, 0x14));
		return size>=int32(data, 0) && size<=data.length ? (int)size : data.length;
	}

	/**
	 * Where the STAK block starts inside a data fork. Stacks saved by the PowerPC HyperCard 2.3+ can carry a PowerPC code
	 * fragment in front of the stack: a 32-byte "HCSA" header ("HCSA", "pwpc", "peff", 0, offset of the stack, length of the
	 * fragment, …) followed by the PEF header and then the ordinary stack. 0 for a plain stack.
	 */
	static int stackOffset(byte[] data){
		if(data.length<0x20 || data[0]!='H' || data[1]!='C' || data[2]!='S' || data[3]!='A') return 0;
		int offset = ((data[16]&0xFF)<<24)|((data[17]&0xFF)<<16)|((data[18]&0xFF)<<8)|(data[19]&0xFF);
		if(offset>0 && offset+8<=data.length && data[offset+4]=='S' && data[offset+5]=='T' && data[offset+6]=='A' && data[offset+7]=='K') return offset;
		//ヘッダの値が合わなければ "STAK" を探す
		for(int i=0x20; i+8<=data.length; i+=1) if(data[i+4]=='S' && data[i+5]=='T' && data[i+6]=='A' && data[i+7]=='K') return i;
		return 0;
	}

	//ファイル全体からFTBL/STBL/LISTブロックだけを探して先に読み込む
	//(CARD/BKGDのフォント・スタイル解決より前に済ませておくため)。
	//LISTも同様で、PAGEブロックはLISTのページ表(pageIdList)を参照して
	//カードIDを登録するが、実際のスタックではLISTがPAGEより後ろに
	//置かれていることがあり、1パスではPAGEを読めずカードIDが空になる。
	//ブロック構造を壊すような不正なデータに出会ったら、本読み込み側の
	//エラー処理に任せるためここでは静かに諦める。戻り値は読み込みに
	//失敗したFTBL/STBL/LISTブロックの数(本読み込み側のerrCountに合算する)。
	/** Format version from the STAK header (8 = HyperCard 1.x, 10 = HyperCard 2.x); set before any block is read. */
	static int formatVersion = 10;
	static boolean isV1(){ return formatVersion==8; }

	private static int preReadTableBlocks(byte[] allBytes, OStack stack){
		int errCount = 0;
		//STAKヘッダのフォーマット版(0x10)。HyperCard 1.x のスタック(8)はブロック内の配置が少し違う
		formatVersion = allBytes.length>=20 && allBytes[4]=='S' && allBytes[5]=='T' && allBytes[6]=='A' && allBytes[7]=='K'
			? ((allBytes[16]&0xFF)<<24)|((allBytes[17]&0xFF)<<16)|((allBytes[18]&0xFF)<<8)|(allBytes[19]&0xFF) : 10;
		if(isV1()) stack.addClassicFontTable(); //1.x には FTBL が無く、パートは Mac のフォントIDそのものを持つ
		try {
			for(BlockSpan span : blockSpans(allBytes, null)){
				int blockSize = span.size;
				byte[] payload = java.util.Arrays.copyOfRange(allBytes, span.offset+8, span.offset+span.size);
				String typeStr = span.type;
				if(typeStr.equals("FTBL")){
					if(!stack.readFontBlock(new DataInputStream(new java.io.ByteArrayInputStream(payload)), blockSize)) errCount++;
				}
				else if(typeStr.equals("STBL")){
					if(!stack.readStyleBlock(new DataInputStream(new java.io.ByteArrayInputStream(payload)), blockSize)) errCount++;
				}
				else if(typeStr.equals("LIST")){
					if(!stack.readListBlock(new DataInputStream(new java.io.ByteArrayInputStream(payload)), blockSize)) errCount++;
				}
				else if(typeStr.equals("STAK") && !isV1() && span.size>=0x38){
					//the LIST the header names (0x34): stale LIST blocks after it are skipped (AOL Naturist States)
					stack.setHeaderListId(int32(allBytes, span.offset+0x34));
				}
				else if(typeStr.equals("TAIL")){
					return errCount;
				}
			}
		} catch (HCDataReadException e) {
			//本読み込み側で同じ内容を読むため、ここでの失敗は無視して構わない
		}
		return errCount;
	}

	private static boolean readDataForkBlocks(byte[] allBytes, OStack stack, int initialErrCount){
		boolean result = false;
		boolean isReadStack = false;
		int errCount = initialErrCount;
		String currentBlockType = "header";
		String[] walkError = new String[1];

		try {
			for(BlockSpan span : blockSpans(allBytes, walkError)){
				int blockOffset = span.offset;
				int blockSize = span.size;
				for(int i=0; i<8; i++) HCStackDebug.read(allBytes[blockOffset+i]&0xFF);
				byte[] payload = java.util.Arrays.copyOfRange(allBytes, blockOffset+8, blockOffset+blockSize);
				DataInputStream block = new DataInputStream(
					new java.io.ByteArrayInputStream(payload));

				if(stack.totalSize>0){
					if(stack.bar!=null){
						//値が変わったときだけ描く(数千枚のカードで毎ブロック同期描画していた)
						int left = allBytes.length-(blockOffset+blockSize);
						int value = stack.barOffset+(73*(stack.totalSize-left))/stack.totalSize;
						if(value!=stack.bar.getValue()){
							stack.bar.setValue(value);
							stack.bar.paintImmediately(stack.bar.getBounds());
						}
					}
				}

				String typeStr = span.type;
				currentBlockType = typeStr+" at "+blockOffset;
				if(typeStr.equals("STAK")){
					if(!isReadStack){
						HCStackDebug.blockstart(typeStr);
						isReadStack = true;
						if(stack.bar!=null) stack.bar.setString("Converting Stack Data...");
						if(!stack.readStackBlock(block, blockSize)) errCount++;
						StackProtection.contentKey(stack); //パスワード付きなら、この後に書く絵を封印する鍵を用意
					}
				}
				else if(typeStr.equals("STBL")){
					//preReadTableBlocksで既に読み込み済み。ここで読み直すと
					//stack.styleListに同じ内容が重複して追加されてしまうので、
					//進捗表示だけ行いスキップする。
					if(stack.bar!=null) stack.bar.setString("Converting Style Data...");
					HCStackDebug.blockstart(typeStr);
				}
				else if(typeStr.equals("FTBL")){
					//preReadTableBlocksで既に読み込み済み(理由はSTBLと同様)。
					if(stack.bar!=null) stack.bar.setString("Converting Font Data...");
					HCStackDebug.blockstart(typeStr);
				}
				else if(typeStr.equals("LIST")){
					//preReadTableBlocksで既に読み込み済み(理由はSTBLと同様。
					//PAGEより後ろにLISTが置かれたスタックへの対応)。
					if(stack.bar!=null) stack.bar.setString("Converting List Data...");
					HCStackDebug.blockstart(typeStr);
				}
				else if(typeStr.equals("PAGE")){
					if(stack.bar!=null) stack.bar.setString("Converting Page Data...");
					HCStackDebug.blockstart(typeStr);
					if(!stack.readPageBlock(block, blockSize)) errCount++;
				}
				else if(typeStr.equals("CARD")){
					HCStackDebug.blockstart(typeStr);
					if(stack.bar!=null) stack.bar.setString("Converting Card Data...");
					OCard cd = new OCard(stack);
					if(!cd.readCardBlock(block, blockSize)) errCount++;
				}
				else if(typeStr.equals("BKGD")){
					HCStackDebug.blockstart(typeStr);
					if(stack.bar!=null) stack.bar.setString("Converting Background Data...");
					OBackground bg = new OBackground(stack);
					if(bg.readBackgroundBlock(block, blockSize)) stack.AddNewBg(bg);
					else errCount++;
				}
				else if(typeStr.equals("BMAP")){
					HCStackDebug.blockstart(typeStr);
					if(stack.bar!=null){
						stack.bar.setString("Converting Picture Data...");
						stack.bar.paintImmediately(stack.bar.getBounds());
					}
					if(!readPictureBlock(block, blockSize, stack)) errCount++;
				}
				else if(typeStr.equals("MAST") || typeStr.equals("FREE") ||
					typeStr.equals("PRNT") || typeStr.equals("PRST") || typeStr.equals("PRFT")){
					HCStackDebug.blockstart(typeStr);
					if(!stack.readNullBlock(block, blockSize)) errCount++;
				}
				else if(typeStr.equals("TAIL")){
					HCStackDebug.blockstart(typeStr);
					result = isReadStack;
				}
				if(result) break;
			}
			stack.orderCardsByPageList();
			java.util.Map<Integer,Integer> nextBg = new java.util.HashMap<Integer,Integer>();
			for(OBackground bg : stack.bgCacheList) nextBg.put(bg.id, bg.importNextBg);
			stack.orderBackgroundsByList(nextBg); //bkgnd N is the Nth of HyperCard's background list, not of the blocks
			if(!result && walkError[0]!=null){
				//最後のほうのブロックが別のデータで上書きされている(パーフェクション・リベンジ: STBL/FTBL/TAIL のあった場所に
				//他のスタックのカード)。カードと背景が全部読めていれば、失われたのは文字スタイル表などなので読めた分で開く
				//(カード枚数は下で確かめる)
				int bgCount = allBytes.length>=0x28 ? int32(allBytes, 0x24) : -1;
				if(!isReadStack || stack.bgCacheList.size()!=bgCount) throw new HCDataReadException(walkError[0]);
				System.out.println("HyperCard stack: the blocks after "+currentBlockType+" are unreadable ("+walkError[0]+"); every card and background was read");
				errCount++;
			}
			//TAIL の前(最後の FREE の途中)でファイルが切れていた: 中身は全部読めている(Christmas Carols)
			if(!result && isReadStack) result = true;
		} catch (HCDataReadException e) {
			System.err.println("HyperCard data fork is truncated or invalid in "+
				currentBlockType+": "+e.getMessage());
			result = false;
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
			if(stack.cdCacheList.size()>stack.cardIdList.size()){
				//a CARD block that no PAGE lists (AOL Greatful Stacked: 132 card blocks, 131 cards in the list and the header):
				//HyperCard never shows it, so it is left out when every listed card was read
				java.util.Set<Integer> listed = new java.util.HashSet<Integer>(stack.cardIdList);
				java.util.Set<Integer> read = new java.util.HashSet<Integer>();
				for(OCard cd : stack.cdCacheList) read.add(cd.id);
				if(read.containsAll(listed)){
					int before = stack.cdCacheList.size();
					stack.cdCacheList.removeIf(cd -> !listed.contains(cd.id));
					System.out.println("HyperCard stack: "+(before-stack.cdCacheList.size())+" card block(s) no page lists were left out");
				}
			}
			if(stack.cdCacheList.size()<stack.cardIdList.size() && !stack.cdCacheList.isEmpty()){
				//listed cards whose blocks are gone (AOL Naturist States: the file ends early, 50 of the 52 cards are there): the
				//stack opens with the cards it has, like a stack whose last blocks were overwritten
				java.util.Set<Integer> read = new java.util.HashSet<Integer>();
				for(OCard cd : stack.cdCacheList) read.add(cd.id);
				int before = stack.cardIdList.size();
				stack.cardIdList.removeIf(id -> !read.contains(id));
				System.out.println("HyperCard stack: "+(before-stack.cardIdList.size())+" listed card(s) have no card block");
				errCount++;
			}
			if(stack.cdCacheList.size()!=stack.cardIdList.size()){
				System.out.println("number of cards check error.");
				result = false;
			}
			if(errCount>0){
				//壊れたブロックがあっても(コピーで1バイト欠けたカード: HyperFrog SW)、カードが全部そろっていれば読めた分で開く。
				//以前は失敗扱いで _stack.xml を書かず、「XMLデータの読み込み中にエラー…書き込み禁止」と出ていた(元のファイルは変えない)
				System.out.println("HyperCard stack: "+errCount+" damaged block(s) were read in part");
			}
			//PAGE 表の印をカードへ(go first marked card で再開位置を覚えるスタックがある: 勇者の石)
			for(OCard cd : stack.cdCacheList) cd.marked = stack.importMarkedIds.contains(cd.id);
		}
		
		//完全に読み取れたときだけピクチャの変換を開始
		if(result) new HCData().new convertPictureThread().start();
		
		return result;
	}
	
	static final int readCode(DataInputStream dis, int size){
		byte[] opcode = new byte[size];
		try {
			dis.readFully(opcode);
			for(byte value: opcode) HCStackDebug.read(value&0xFF);
		} catch (IOException e) {
			throw new HCDataReadException("unexpected end of data", e);
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

	static int remaining(DataInputStream dis) {
		try {
			return dis.available();
		} catch (IOException e) {
			throw new HCDataReadException("could not determine block remainder", e);
		}
	}

	static class PartContent {
		int partId;
		boolean cardPart;
		String text = "";
		ArrayList<styleClass> styles = new ArrayList<styleClass>();
	}

	/**
	 * The {@code count} content records of a card or background. HyperCard 1.x writes them back to back: part id + NUL-terminated
	 * text, with no padding (If Monks Had Macs "Passing Notes": the header's content size is the unpadded total, and a pad byte
	 * taken from the name dropped its first letter - "ntro" - or, for a card without a name, made the script its name). The
	 * {@code size} bytes the header gives are read as a whole and split without padding, or with even padding when only that
	 * fits them. 2.x records carry their own lengths.
	 */
	static java.util.List<PartContent> readContents(DataInputStream dis, int count, int size) {
		java.util.List<PartContent> list = new ArrayList<PartContent>();
		if(!isV1() || size<0 || size>remaining(dis)){
			for(int i=0; i<count; i++) list.add(readPartContent(dis));
			return list;
		}
		byte[] raw = new byte[size];
		try {
			dis.readFully(raw);
			for(byte value : raw) HCStackDebug.read(value&0xFF);
		} catch (IOException e) {
			throw new HCDataReadException("unexpected end of part content", e);
		}
		java.util.List<PartContent> r = splitV1Contents(raw, count, false);
		if(r==null) r = splitV1Contents(raw, count, true);
		if(r==null) throw new HCDataReadException("part content does not fit its size");
		return r;
	}

	private static java.util.List<PartContent> splitV1Contents(byte[] raw, int count, boolean pad) {
		java.util.List<PartContent> list = new ArrayList<PartContent>();
		int pos = 0;
		for(int i=0; i<count; i++){
			if(pos+2>raw.length) return null;
			int signedPartId = (short)(((raw[pos]&0xFF)<<8)|(raw[pos+1]&0xFF));
			int start = pos+2, end = start;
			while(end<raw.length && raw[end]!=0) end++;
			if(end>=raw.length) return null;
			PartContent c = new PartContent();
			c.cardPart = signedPartId < 0;
			c.partId = signedPartId < 0 ? -signedPartId : signedPartId;
			int length = end-start;
			c.text = length==0 ? "" : readText(new DataInputStream(new java.io.ByteArrayInputStream(raw, start, length)), length).str;
			list.add(c);
			pos = end+1;
			if(pad && ((2+length+1)&1)!=0) pos++;
		}
		return pos==raw.length || (pad && pos==raw.length+1) ? list : null;
	}

	static PartContent readPartContent(DataInputStream dis) {
		PartContent result = new PartContent();
		int signedPartId = (short)readCode(dis, 2);
		result.cardPart = signedPartId < 0;
		result.partId = signedPartId < 0 ? -signedPartId : signedPartId;
		if(isV1()){
			//HyperCard 1.x: 長さもスタイルも無く、NUL終端のテキストが続く。レコードは偶数長に揃える
			java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
			try {
				int b;
				while((b = dis.read()) > 0){ HCStackDebug.read(b); text.write(b); }
				if(b<0) throw new HCDataReadException("unexpected end of part content");
				if(((2+text.size()+1)&1)!=0 && remaining(dis)>0) dis.readUnsignedByte();
			} catch (IOException e) {
				throw new HCDataReadException("unexpected end of part content", e);
			}
			byte[] bytes = text.toByteArray();
			result.text = bytes.length==0 ? "" : readText(new DataInputStream(new java.io.ByteArrayInputStream(bytes)), bytes.length).str;
			return result;
		}
		int contentLength = readCode(dis, 2) & 0xFFFF;
		if(contentLength > remaining(dis)) {
			throw new HCDataReadException("part content exceeds block boundary");
		}

		byte[] contentBytes = new byte[contentLength];
		try {
			dis.readFully(contentBytes);
			// Part-content records are aligned to an even byte boundary.  The
			// alignment byte is not included in the recorded content length.
			if((contentLength & 1) != 0 && remaining(dis) > 0) dis.readUnsignedByte();
		} catch (IOException e) {
			throw new HCDataReadException("unexpected end of part content", e);
		}

		if(contentLength == 0) return result;
		DataInputStream content = new DataInputStream(
			new java.io.ByteArrayInputStream(contentBytes));
		int marker = readCode(content, 1);
		if((marker & 0x80) == 0) {
			result.text = readText(content, remaining(content)).str;
			return result;
		}

		if(remaining(content) < 1) {
			throw new HCDataReadException("truncated styled part content");
		}
		// The 15-bit value here is not the style table's byte length by itself;
		// it is the offset from the start of the content (i.e. including the
		// 2-byte marker/length header already read) to where the text begins.
		int styleLength = ((marker & 0x7F) << 8) | readCode(content, 1);
		int styleBytes = styleLength - 2;
		if(styleBytes < 0 || styleBytes > remaining(content) || (styleBytes & 3) != 0) {
			throw new HCDataReadException("invalid style data length "+styleLength);
		}
		for(int i=0; i<styleBytes/4; i++) {
			styleClass style = new styleClass();
			style.textPosition = readCode(content, 2);
			style.styleId = readCode(content, 2);
			result.styles.add(style);
		}
		// スタイルランのtextPositionは、ファイル上ではテキスト先頭からの
		// "バイト数"(エンコード後のバイト列上の位置)で書かれている。一方
		// このアプリの他の場所(OField.styleList、HCExport.content()の
		// エクスポート処理等)はtextPositionを常にJavaの文字列インデックス
		// (デコード後の"文字数")として扱う。日本語のような1文字が2バイトに
		// エンコードされる文字が含まれる文字列では、バイト数と文字数がずれる
		// ため、変換せずそのまま使うと(特に全角文字の後ろでは)スタイルの
		// 適用開始位置が本来より後ろにずれてしまう。ここでバイト位置を
		// 文字インデックスへ変換しておく。
		int textStartInContent = contentBytes.length - remaining(content);
		result.text = readText(content, remaining(content)).str;
		byte[] textBytes = java.util.Arrays.copyOfRange(contentBytes,
			Math.min(textStartInContent, contentBytes.length), contentBytes.length);
		for(styleClass style: result.styles) {
			int byteOffset = Math.max(0, Math.min(textBytes.length, style.textPosition));
			style.textPosition = currentEncoding.decode(textBytes, 0, byteOffset).length();
		}
		return result;
	}
	
	static final String readStr(DataInputStream dis, int size){
		StringBuilder str = new StringBuilder(size);
		//String debugStr = "";
		for(int i=0; i<size; i++){
			try {
				int v = dis.readUnsignedByte();
				HCStackDebug.read(v);
				str.append((char)v);
				//debugStr += " "+v;
			} catch (IOException e) {
				throw new HCDataReadException("unexpected end of string data", e);
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
		return readText(dis, maxLen, false);
	}

	/** A part's script: as {@link #readText} (the NUL padding after it is skipped), its control characters kept like a
	 *  field's - they are part of string literals that externals use ("ICONS/<ESC>!µ" makes a hierarchical item for The
	 *  Yennie eXternals' metaMenu XFCN, which showed "ICONSµ" when the ESC was dropped) - and decoded as a script. */
	/**
	 * An object script in an OSA language (HyperCard 2.2+, see {@link OsaScript}): {@code padBefore} bytes (the part
	 * record's padding after the name), the source text, a NUL, then a 'WOSA' record. Consumes them and answers the text
	 * with {@link resultStr#osa} set; for any other script it answers null and leaves the stream where it was.
	 */
	static resultStr readOsaScript(DataInputStream dis, int maxLen, int padBefore){
		if(maxLen<padBefore+1+OsaScript.MAGIC.length || !dis.markSupported()) return null;
		byte[] b = new byte[maxLen];
		int n;
		try {
			dis.mark(maxLen);
			n = dis.readNBytes(b, 0, maxLen);
			dis.reset();
		} catch (IOException e) {
			return null;
		}
		int nul = padBefore;
		while(nul<n && b[nul]!=0) nul++;
		OsaScript osa = OsaScript.parse(b, nul+1, n);
		if(osa==null) return null;
		for(int i=0; i<padBefore; i++) readCode(dis, 1);
		resultStr result = readScriptToZero(dis, nul+1-padBefore);
		try {
			for(int i=0; i<osa.length(); i++) HCStackDebug.read(dis.read());
		} catch (IOException e) {
			throw new HCDataReadException("unexpected end of OSA script data", e);
		}
		result.length_in_src = nul+1+osa.length();
		result.osa = osa;
		return result;
	}

	static final resultStr readPartScript(DataInputStream dis, int maxLen){
		return readText(dis, maxLen, true);
	}

	private static resultStr readText(DataInputStream dis, int maxLen, boolean script){
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
				//control characters stay: field text shows them (Chicago/Osaka draw $11-$14 as ⌘ ✓ ◆ Apple, ClassicGlyphs; 出しにくい文字's
				//table of them was an empty line) and a styled text's run offsets count them (StyleRun byte positions)
				if(c==0x00) {
					i2++;
					i--;
					continue;
				}
				b[i] = (byte)c;
			}
		} catch (IOException e) {
			throw new HCDataReadException("unexpected end of text data", e);
		}
		//a part script keeps HyperTalk's Roman ¬ ≠ ≤ ≥ in Chinese/Korean stacks like card/bkgnd scripts (中文版 地址簿's "answer … && ¬")
		result.str = script ? decodeScript(b, i, currentEncoding) : currentEncoding.decode(b, 0, i);
		//改行コード変更
		for(int j=0;j<result.str.length(); j++){
			if(result.str.charAt(j)=='\r'){
				result.str = result.str.substring(0,j)+"\n"+result.str.substring(j+1);
			}
		}
		result.str = sanitizeForXml(result.str);
		return result;
	}
	
	static final resultStr readTextToZero(DataInputStream dis, int maxLen){
		return readTextToZero(dis, maxLen, false);
	}

	/** Mac OS Roman's ¬ (line continuation), ≠, ≤ and ≥: one byte each in every script's HyperTalk */
	private static final int[] MACROMAN_OPERATOR_BYTES = {0xC2, 0xAD, 0xB2, 0xB3};
	private static final char[] MACROMAN_OPERATOR_CHARS = {'¬', '≠', '≤', '≥'};

	/**
	 * Decodes script text. In the two-byte encodings other than Japanese (Chinese, Korean) the bytes of HyperTalk's Mac OS Roman
	 * operators are lead bytes, so "global a,b,¬" ⏎ read the ¬ and the line end as one broken character (中文版 HyperCard's
	 * 音频帮助: "変数名の頭がアルファベットではありません"). Such a byte that cannot start a two-byte character there (its next
	 * byte is not a trail byte: a space, a return, the end) is the Roman operator. Japanese has its own repair
	 * ({@link #restoreMacRomanScriptOperators}), since those bytes are one-byte kana in Shift-JIS.
	 */
	static String decodeScript(byte[] b, int n, StackTextEncoding encoding){
		//HyperCard's script lines end at CR only; a line feed is a character in the line (AOL TextMuncher: put "… “<LF>” format …").
		//Here every line feed would end a line, so it becomes a space
		for(int i=0; i<n; i++){
			if(b[i]==0x0A){ b = java.util.Arrays.copyOf(b, n); for(int k=i; k<n; k++) if(b[k]==0x0A) b[k] = 0x20; break; }
		}
		if(encoding!=StackTextEncoding.SIMPLIFIED_CHINESE && encoding!=StackTextEncoding.TRADITIONAL_CHINESE &&
				encoding!=StackTextEncoding.KOREAN) return encoding.decode(b, 0, n);
		StringBuilder out = new StringBuilder(n);
		int start = 0;
		for(int i=0; i<n; i++){
			int c = b[i]&0xFF;
			if(c<0x80) continue;
			int next = i+1<n ? b[i+1]&0xFF : -1;
			boolean trail = encoding==StackTextEncoding.KOREAN ? next>=0xA1 && next<=0xFE :
				encoding==StackTextEncoding.TRADITIONAL_CHINESE ? next>=0x40 && next<=0x7E || next>=0xA1 && next<=0xFE :
				next>=0x40 && next<=0xFE && next!=0x7F;
			if(trail){ i++; continue; } //a two-byte character
			for(int k=0; k<MACROMAN_OPERATOR_BYTES.length; k++){
				if(c!=MACROMAN_OPERATOR_BYTES[k]) continue;
				out.append(encoding.decode(b, start, i-start)).append(MACROMAN_OPERATOR_CHARS[k]);
				start = i+1;
				break;
			}
		}
		out.append(encoding.decode(b, start, n-start));
		return out.toString();
	}

	private static resultStr readTextToZero(DataInputStream dis, int maxLen, boolean script){
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
			throw new HCDataReadException("unexpected end of zero-terminated text", e);
		}

		result.str = script ? decodeScript(b, i, currentEncoding) : currentEncoding.decode(b, 0, i);
		//改行コード変更
		for(int j=0;j<result.str.length(); j++){
			if(result.str.charAt(j)=='\r'){
				result.str = result.str.substring(0,j)+"\n"+result.str.substring(j+1);
			}
		}
		result.str = sanitizeForXml(result.str);
		return result;
	}

	//classic Macで「オプション+,」「オプション+.」等で入力した数学記号(≠,≤,≥)や
	//行継続記号(¬)はMac Roman上の1バイト文字だが、日本語環境向けSJISデコードでは
	//半角カナに化けてしまう。スクリプト中で単独の単語として現れた場合に限り、
	//元のMac Roman記号へ復元する。
	private static final char[] MACROMAN_OPERATOR_MOJIBAKE = {'ｭ','ｲ','ｳ','ﾂ'};//ｭ,ｲ,ｳ,ﾂ
	private static final String[] MACROMAN_OPERATOR_SYMBOL = {"≠","≤","≥","¬"};//≠,≤,≥,¬

	static resultStr readScriptToZero(DataInputStream dis, int maxLen) {
		resultStr result = readTextToZero(dis, maxLen, true);
		//このMac Roman運算子の復元はSJIS/MacJapaneseデコード特有の文字化けの補正なので、
		//他の文字コードで開いている場合は行わない。
		if(currentEncoding == StackTextEncoding.JAPANESE){
			result.str = restoreMacRomanScriptOperators(result.str);
		}
		result.str = joinLineContinuations(result.str);
		return result;
	}

	//行末の¬(オプション+L)は次の行と論理的に1行として続くことを示す。
	//スクリプトの実行時に行単位で処理する箇所が多いため、読み込み時に結合しておく。
	static String joinLineContinuations(String script) {
		String[] lines = script.split("\n", -1);
		ArrayList<String> outLines = new ArrayList<String>();
		StringBuilder current = new StringBuilder();
		boolean pending = false;
		for(String line: lines){
			current.append(line);
			int end = current.length();
			while(end>0 && (current.charAt(end-1)==' ' || current.charAt(end-1)=='\t')) end--;
			if(end>0 && current.charAt(end-1)=='¬'){
				//¬ と改行は空白1つ(HyperCard)。詰めてしまうと "put x after¬" ⏎ "menu 3" が "aftermenu" になっていた
				current.setLength(end-1);
				current.append(' ');
				pending = true;
			}else{
				outLines.add(current.toString());
				current = new StringBuilder();
				pending = false;
			}
		}
		if(pending || current.length()>0) outLines.add(current.toString());
		StringBuilder joined = new StringBuilder();
		for(int i=0; i<outLines.size(); i++){
			if(i>0) joined.append('\n');
			joined.append(outLines.get(i));
		}
		return joined.toString();
	}

	private static boolean isScriptWordBoundary(char c) {
		return c==' ' || c=='\t' || c=='\n' || c=='\r';
	}

	static String restoreMacRomanScriptOperators(String script) {
		StringBuilder sanitized = new StringBuilder(script.length());
		for(int i=0; i<script.length(); i++){
			char c = script.charAt(i);
			int idx = -1;
			for(int k=0; k<MACROMAN_OPERATOR_MOJIBAKE.length; k++){
				if(c==MACROMAN_OPERATOR_MOJIBAKE[k]){ idx = k; break; }
			}
			boolean isolated = idx>=0 &&
				(i==0 || isScriptWordBoundary(script.charAt(i-1))) &&
				(i==script.length()-1 || isScriptWordBoundary(script.charAt(i+1)));
			if(isolated){
				sanitized.append(MACROMAN_OPERATOR_SYMBOL[idx]);
			}else{
				sanitized.append(c);
			}
		}
		return sanitized.toString();
	}

	/** Drops what no XML file can hold (lone surrogates, U+FFFE/FFFF). Control characters stay: XMLwrite keeps them as
	 *  U+E000+c (XmlText), and scripts need them - The Yennie eXternals builds a hierarchical menu item with "ICONS/<ESC>!µ"
	 *  for its metaMenu XFCN, which showed "ICONSµ" without the ESC. */
	static String sanitizeForXml(String text) {
		StringBuilder sanitized = new StringBuilder(text.length());
		text.codePoints().forEach(codePoint -> {
			if((codePoint>=0x01 && codePoint<=0xD7FF) ||
				(codePoint>=0xE000 && codePoint<=0xFFFD) ||
				(codePoint>=0x10000 && codePoint<=0x10FFFF)){
				sanitized.appendCodePoint(codePoint);
			}
		});
		return sanitized.toString();
	}

	static BufferedImage applyBmapTransparency(BufferedImage image,
			BufferedImage mask, boolean backgroundPicture) {
		if(image==null || backgroundPicture) return image;
		if(mask!=null &&
			(image.getWidth()!=mask.getWidth() || image.getHeight()!=mask.getHeight())){
			throw new IllegalArgumentException("BMAP image and mask sizes differ");
		}
		for(int y=0; y<image.getHeight(); y++){
			for(int x=0; x<image.getWidth(); x++){
				// 画像ビットは常に黒として優先する。マスクは透明度のマスクではなく
				// 「白いピクセルの位置」を表すビットマップで、マスク矩形内では
				// 1ビットが白、0ビットが透明になる(WOBA形式の仕様。real
				// HyperCardが書き出したスタックや、HyperCardPreview
				// (MaskedImage.swiftのsubscript実装)の解釈と照合して確認済み)。
				// 矩形外はreadWOBAが透明で表すため区別できる。
				// 以前はここが逆(0ビットが白、1ビットが透明)になっており、
				// 白のはずの部分が透明に、透明のはずの部分が白に描画される
				// ことがあった。
				boolean imageBit = isSetBmapBit(image.getRGB(x, y));
				int maskPixel = mask==null ? 0 : mask.getRGB(x, y);
				boolean insideMask = (maskPixel & 0xFF000000)!=0;
				boolean white = insideMask && isSetBmapBit(maskPixel);
				image.setRGB(x, y, imageBit ? 0xFF000000 :
					(white ? 0xFFFFFFFF : 0x00000000));
			}
		}
		return image;
	}

	private static boolean isSetBmapBit(int pixel) {
		return (pixel & 0xFF000000)!=0 && (pixel & 0x00FFFFFF)==0;
	}

	//マスクのビットマップデータを持たず、矩形だけが指定されている場合に使う、
	//「その矩形全体でマスクビットが立っている」ことを表す疑似マスク画像を作る。
	//applyBmapTransparencyはisSetBmapBit(黒=0xFF000000)をマスクビット1として
	//読むので、矩形内を黒(0xFF000000)、矩形外を透明(0x00000000、alpha=0の
	//ままなのでinsideMask判定でfalseになる)で塗る。
	static BufferedImage buildRectangularMaskImage(int cdWidth, int cdHeight,
			int maskLeft, int maskTop, int maskRight, int maskBottom){
		BufferedImage bi = new BufferedImage(cdWidth, cdHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = bi.createGraphics();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		g.fillRect(0, 0, cdWidth, cdHeight);
		g.dispose();
		DataBuffer db = bi.getRaster().getDataBuffer();
		int left = Math.max(0, maskLeft), top = Math.max(0, maskTop);
		int right = Math.min(cdWidth, maskRight), bottom = Math.min(cdHeight, maskBottom);
		for(int y=top; y<bottom; y++){
			for(int x=left; x<right; x++){
				db.setElem(x+y*cdWidth, 0xFF000000);
			}
		}
		return bi;
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
	

	//変換スレッド(読み込み)・カード表示(OCard/OBackgroundのbuild)・
	//convertPictureThreadの3者から触られるので同期リストにしておく
	static java.util.List<Thread> threadList = java.util.Collections.synchronizedList(new ArrayList<Thread>());

	//まだ開始していなければ開始する。isAlive()での判定だと、終了済みのスレッドや
	//二者が同時に判定した場合にstart()を二重に呼んでIllegalThreadStateExceptionになる
	static void startIfNew(Thread p){
		if(p==null) return;
		synchronized(p){
			if(p.getState()==Thread.State.NEW) p.start();
		}
	}

	/** Waits until every picture of the stack just read has been decoded and written (the threads of {@link #threadList}
	 *  started by the convertPictureThread), at most {@code timeoutMs}; true when they all finished. */
	static boolean waitForPictures(long timeoutMs){
		long deadline = System.currentTimeMillis()+timeoutMs;
		while(true){
			boolean busy = false;
			Thread[] snapshot;
			synchronized(threadList){ snapshot = threadList.toArray(new Thread[0]); }
			for(Thread p: snapshot){
				if(p!=null && p.getState()!=Thread.State.TERMINATED){ busy = true; break; }
			}
			if(!busy) return true;
			if(System.currentTimeMillis()>deadline) return false;
			try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
		}
	}
	
	public static boolean readPictureBlock(DataInputStream dis, int blockSize, OStack stack){
		//System.out.println("readPictureBlock");

		if(blockSize>200000 || blockSize < 0){
			return false;
		}
		
		//ブロックのデータを順次読み込み
		int bitmapId = HCData.readCode(dis, 4);
		//HCStackDebug.debuginfo("bitmapId:"+Integer.toString(bitmapId));
		//System.out.println("bitmapId:"+bitmapId);
		/*String filler =*/ HCData.readStr(dis, isV1() ? 8 : 12); //1.x: 矩形はブロック内 0x14 から(2.x は 0x18)、以降は 4 バイトずつ前
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

		//long: garbage sizes in a damaged BMAP (AOL Easy Listings) wrapped the sum below blockSize and allocated gigabytes
		if(maskSize<0 || imgSize<0 || blockSize < (isV1() ? 60L : 64L)+maskSize+imgSize){
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
		//最初のカード・バックグラウンドのピクチャはすぐに書き始める。それでも threadList には入れる: 入れないと
		//最初のカードを表示するとき(OCard/OBackground の build)に書き終わるのを待たず、書きかけの PNG を読んで
		//"Error skipping PNG metadata" になる(スタック手帳 MONTHLY/SMALL の取り込み)。waitForPictures も待てなかった
		threadList.add(p);
		if(stack.GetCardbyId(stack.firstCard)!=null &&
				stack.GetCardbyId(stack.firstCard).bitmapName!=null&&
				stack.GetCardbyId(stack.firstCard).bitmapName.equals("BMAP_"+bitmapId+".png")){
			startIfNew(p);
		}
		else if(stack.GetBackgroundbyId(stack.firstBg)!=null &&
				stack.GetBackgroundbyId(stack.firstBg).bitmapName!=null &&
				stack.GetBackgroundbyId(stack.firstBg).bitmapName.equals("BMAP_"+bitmapId+".png")){
			startIfNew(p);
		}
		
		
		//アライメント調整
		int remainLength = blockSize - ((isV1() ? 60 : 64)+maskSize+imgSize);
		HCStackDebug.debuginfo("remainLength:"+Integer.toString(remainLength));
		//System.out.println("remainLength:"+remainLength);
		/*String padding =*/ HCData.readStr(dis, Math.max(0, remainLength));
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
					startIfNew(p);
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
			for(int i=0; stack.bgCacheList!=null&&i<stack.bgCacheList.size(); i++){
				if(stack.bgCacheList.get(i).bitmapName!=null && stack.bgCacheList.get(i).bitmapName.equals("BMAP_"+bitmapId+".png")){
					isBgPicture = true;
					break;
				}
			}
			
			BufferedImage maskBi = null;
			if(mask!=null){
				maskBi = readWOBA(mask, bitmapId, right, bottom, maskLeft, maskTop, maskRight, maskBottom, false);
			}
			else if(maskRight>maskLeft && maskBottom>maskTop){
				//マスクのビットマップデータ(maskSize)自体は無くても、マスクの矩形
				//(maskLeft〜maskBottom)だけがヘッダに記録されていることがある。
				//HyperCardPreview(MaskedImage.swiftのbuildImageLayer)はこの場合、
				//その矩形全体を「マスクが立っている」(=不透明白)領域として扱う
				//(実データを持たない.rectangularレイヤー)。以前はmaskSize==0を
				//「マスク無し(=矩形外と同じ全面透明)」と同一視していたため、
				//本来矩形全体が不透明白であるべきカードピクチャの白い部分が
				//透明になってしまっていた。
				maskBi = HCData.buildRectangularMaskImage(right, bottom, maskLeft, maskTop, maskRight, maskBottom);
			}
			BufferedImage mainBi = readWOBA(img, bitmapId, right, bottom, imgLeft, imgTop, imgRight, imgBottom, isBgPicture);
			mainBi = HCData.applyBmapTransparency(mainBi, maskBi, isBgPicture);
			
			//ファイルに保存(これに時間がかかる)
			String filename = "BMAP_"+bitmapId+".png";
			File file = new File(stack.file.getParent()+File.separatorChar+filename);
			try {
				StackProtection.writeImage(stack, mainBi, file); //パスワード付きなら封印
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
		//WOBA(HyperCardのカードピクチャ圧縮形式)を1bitのビット列としてデコードする。
		//
		//以前はここでピクセルを直接ARGB色(黒=0xFF000000/白=0xFFFFFFFF)として
		//DataBufferに書き込んでいたが、0x88〜0x8F(dh/dv、行間の差分XOR)命令は
		//本来「生のビット」に対してXORを取る差分符号化(いわゆるスキャンライン
		//フィルタ)であり、ARGB色の状態からビットを都度読み戻してXORし直す実装は
		//色⇔ビットの変換誤差が蓄積し、その行以降のビットパターンが本物の
		//HyperCard/HyperCardPreview(HyperCardCommon/BitmapLoading.swiftの
		//decodeLayer)が復元する内容と食い違う原因になっていた(白いはずの広い
		//領域が透明になる不具合として現れていた)。
		//
		//ここではHyperCardPreviewのdecodeLayerと同じく、まず64bit単位のビット列
		//(long[])としてデコードし、最後にまとめてARGB色へ変換する。
		//アルゴリズム自体はdecodeLayerの移植で、ビット演算の意味も完全に一致させている。
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

			left = left/32*32;
			right = (right+31)/32*32;
			int rectWidth = right-left;
			int rectHeight = bottom-top;
			if(img.length==0 || rectWidth<=0 || rectHeight<=0){
				return bi;
			}

			int wordsPerRow = (rectWidth+63)/64;
			long[] pixels = new long[wordsPerRow*rectHeight];
			decodeWOBABits(img, pixels, wordsPerRow, rectWidth, top, bottom);

			DataBuffer db = bi.getRaster().getDataBuffer();
			for(int row=0; row<rectHeight; row++){
				int y = top+row;
				int base = row*wordsPerRow;
				for(int lx=0; lx<rectWidth; lx++){
					long word = pixels[base + lx/64];
					boolean set = ((word >>> (63-(lx%64))) & 1L)!=0;
					db.setElem((left+lx)+y*cdWidth, set?0xFF000000:0xFFFFFFFF);
				}
			}

			return bi;
		}

		//HyperCardCommon/BitmapLoading.swiftのdecodeLayerと同じアルゴリズムで、
		//生のビット列(1ワード=64bit、1行の先頭は必ずワード境界に揃う)へデコードする。
		//pixels[row*wordsPerRow + wordIndex]がその行のビットを保持し、
		//ビット位置はワード内でMSBが先頭(x=0)になる。
		private static void decodeWOBABits(byte[] img, long[] pixels, int wordsPerRow, int rectWidth, int top, int bottom){
			int integerLengthImage = wordsPerRow;
			int integerLength32 = rectWidth/32;
			int rowWidth32 = integerLength32*32;

			byte[] repeatedBytes = new byte[]{(byte)0xAA,0x55,(byte)0xAA,0x55,(byte)0xAA,0x55,(byte)0xAA,0x55};
			int dh = 0;
			int dv = 0;

			int i = 0;
			int pixelIndex = 0;
			int y = top;

			rowLoop:
			while(y < bottom){
				int x = 0;
				int repeatCount = 1;

				while(x < rowWidth32){
					if(i>=img.length) break rowLoop;
					int opcode = 0x00FF & img[i];
					i++;

					if(opcode <= 0x7F){
						int zeroLength = opcode & 0x0F;
						int dataLength = opcode >> 4;
						int totalLength = zeroLength+dataLength;
						for(int di=0; di<dataLength; di++){
							if(i>=img.length) break rowLoop;
							int value = 0x00FF & img[i];
							i++;
							for(int r=0; r<repeatCount; r++){
								writeByteInRow(pixels, pixelIndex, x+(zeroLength+di+r*totalLength)*8, value);
							}
						}
						x += totalLength*repeatCount*8;
						repeatCount = 1;
					}
					else if(opcode==0x80){ //1行分無圧縮
						for(int wi=0; wi<integerLength32 && i+3<img.length; wi++){
							long value = readU32(img, i+wi*4);
							for(int r=0; r<repeatCount; r++){
								writeInt32InRow(pixels, pixelIndex+r*integerLengthImage, wi*32, value);
							}
						}
						i += integerLength32*4;
						pixelIndex += repeatCount*integerLengthImage;
						y += repeatCount;
						continue rowLoop;
					}
					else if(opcode==0x81){ //1行白(=0ビットのまま)
						pixelIndex += repeatCount*integerLengthImage;
						y += repeatCount;
						continue rowLoop;
					}
					else if(opcode==0x82){ //1行黒(全ビット1)
						for(int r=0; r<repeatCount; r++){
							for(int wi=0; wi<integerLengthImage; wi++) pixels[wi+pixelIndex] = -1L;
							pixelIndex += integerLengthImage;
							y++;
						}
						continue rowLoop;
					}
					else if(opcode==0x83){ //1行同一バイト
						if(i>=img.length) break rowLoop;
						int v = 0x00FF & img[i];
						i++;
						long word = 0;
						for(int s=0; s<8; s++) word = (word<<8) | v;
						//0x83/0x84の8行周期は画像矩形の上端からの行番号で数える(カード座標の
						//yではない)。上端が1のBMAP(WindowScript 1.5 Demoの背景)では、
						//y%8だと網点の位相が1行ずれ、以後のdv差分XORでIndexボタンの白い枠内が
						//黒く反転していた(実機HyperCard J-2.3の表示と照合)。
						repeatedBytes[(y-top)%8] = (byte)v;
						for(int r=0; r<repeatCount; r++){
							for(int wi=0; wi<integerLengthImage; wi++) pixels[wi+pixelIndex] = word;
							pixelIndex += integerLengthImage;
							y++;
						}
						continue rowLoop;
					}
					else if(opcode==0x84){ //1行同一バイト,保持配列のデータを使う
						for(int r=0; r<repeatCount; r++){
							int v = repeatedBytes[(y-top)%8] & 0xFF;
							long word = 0;
							for(int s=0; s<8; s++) word = (word<<8) | v;
							for(int wi=0; wi<integerLengthImage; wi++) pixels[wi+pixelIndex] = word;
							pixelIndex += integerLengthImage;
							y++;
						}
						continue rowLoop;
					}
					else if(opcode==0x85 || opcode==0x86 || opcode==0x87){ //1/2/3行上をコピー
						int back = opcode-0x84;
						for(int r=0; r<repeatCount; r++){
							int srcIndex = pixelIndex - back*integerLengthImage;
							for(int wi=0; wi<integerLengthImage; wi++){
								pixels[wi+pixelIndex] = srcIndex>=0 ? pixels[wi+srcIndex] : 0;
							}
							pixelIndex += integerLengthImage;
							y++;
						}
						continue rowLoop;
					}
					else if(opcode>=0x88 && opcode<=0x8F){
						switch(opcode){
						case 0x88: dh=16; dv=0; break; //16bit右シフトしてXOR
						case 0x89: dh=0; dv=0; break;
						case 0x8A: dh=0; dv=1; break; //1行上とXOR
						case 0x8B: dh=0; dv=2; break; //2行上とXOR
						case 0x8C: dh=1; dv=0; break; //1bit右シフトしてXOR
						case 0x8D: dh=1; dv=1; break; //1bit右シフト、1行上とXOR
						case 0x8E: dh=2; dv=2; break; //2bit右シフト、2行上とXOR
						case 0x8F: dh=8; dv=0; break; //8bit右シフトしてXOR
						}
					}
					else if(opcode >= 0xA0 && opcode <= 0xBF){
						//下5bit分、次の命令を繰り返す
						repeatCount = 0x1F & opcode;
					}
					else if(opcode >= 0xC0 && opcode <= 0xDF){
						//下5bit*8分のデータ
						int dataLength = (0x1F & opcode)*8;
						for(int di=0; di<dataLength; di++){
							if(i>=img.length) break rowLoop;
							int value = 0x00FF & img[i];
							i++;
							for(int r=0; r<repeatCount; r++){
								writeByteInRow(pixels, pixelIndex, x+(di+r*dataLength)*8, value);
							}
						}
						x += dataLength*repeatCount*8;
						repeatCount = 1;
					}
					else if(opcode >= 0xE0){ //0xE0〜0xFF: 下5bit*128分のゼロ(何も書かず読み飛ばす)
						int zeroCount = (0x1F & opcode)*128;
						x += zeroCount*repeatCount;
						repeatCount = 1;
					}
					else{
						break rowLoop;
					}
				}

				if(dh!=0){
					applyDh(pixels, pixelIndex, integerLengthImage, dh);
				}
				if(dv!=0 && dv<=y-top){
					for(int wi=0; wi<integerLengthImage; wi++){
						pixels[wi+pixelIndex] ^= pixels[wi+pixelIndex-dv*integerLengthImage];
					}
				}
				pixelIndex += integerLengthImage;
				y++;
			}
		}

		private static void writeByteInRow(long[] row, int rowPixelIndex, int x, int byteValue){
			int idx = rowPixelIndex + x/64;
			int shift = 64-8-(x%64);
			row[idx] |= ((long)(byteValue & 0xFF)) << shift;
		}

		private static void writeInt32InRow(long[] row, int rowPixelIndex, int x, long int32Value){
			int idx = rowPixelIndex + x/64;
			int shift = 64-32-(x%64);
			row[idx] |= (int32Value & 0xFFFFFFFFL) << shift;
		}

		private static long readU32(byte[] data, int offset){
			return ((long)(data[offset]&0xFF)<<24) | ((data[offset+1]&0xFF)<<16)
				| ((data[offset+2]&0xFF)<<8) | (data[offset+3]&0xFF);
		}

		//HyperCardCommon/BitmapLoading.swiftのapplyDxの移植。dh(dx)bit間隔で
		//行全体にわたる差分XORを復元する(「1bit右シフトしてXOR」等)。
		private static void applyDh(long[] row, int rowPixelIndex, int integerLength, int dh){
			long previousResult = 0;
			long previousXorLeft = 0;
			int steps = 64/dh;
			for(int idx=0; idx<integerLength; idx++){
				long value = row[idx+rowPixelIndex];
				long xorLeft = value;
				long xorRight = 0;
				for(int k=0; k<steps; k++){
					xorLeft ^= (value << (dh*k));
					xorRight ^= (value >>> (dh*k));
				}
				long result = previousResult ^ previousXorLeft ^ xorRight;
				row[idx+rowPixelIndex] = result;
				previousResult = result;
				previousXorLeft = xorLeft;
			}
		}
	}
}


class resultStr{
	String str;
	int length_in_src;
	/** set by HCData.readOsaScript: the compiled OSA script that followed the text */
	OsaScript osa;
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

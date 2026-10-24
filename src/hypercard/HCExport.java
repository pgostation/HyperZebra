import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

/** Writes HyperZebra's in-memory model as a classic HyperCard 2.x data fork. */
final class HCExport {
	static final class Result {
		final int cardCount;
		final int backgroundCount;
		final int pictureCount;
		int resourceCount;
		final ArrayList<String> warnings;
		File outputFile;
		boolean zipPackage;
		/** The AppleDouble "._" sidecar holding the resource fork on hosts without native forks, or null. */
		File sidecar;

		Result(int cards, int backgrounds, int pictures, ArrayList<String> warnings) {
			cardCount = cards;
			backgroundCount = backgrounds;
			pictureCount = pictures;
			this.warnings = warnings;
			resourceCount = 0;
		}
	}

	private static final int PAGE_CARD_LIMIT = 250;

	static Result exportStack(OStack stack, File destination,
			StackTextEncoding encoding) throws IOException {
		StackTextEncoder encoder=new StackTextEncoder(encoding);
		ExportData exported = build(stack, encoder);
		HCResourceExport.Result resources=HCResourceExport.build(stack,encoder);
		exported.result.warnings.addAll(resources.warnings);
		if(encoder.replacementOccurred())exported.result.warnings.add(encoder.replacementWarning());
		exported.result.resourceCount=resources.count;
		HCStackFileWriter.Result written=HCStackFileWriter.write(destination,exported.bytes,resources.fork);
		exported.result.outputFile=written.file;
		exported.result.zipPackage=written.zip;
		exported.result.sidecar=written.sidecar;
		return exported.result;
	}

	static byte[] exportDataFork(OStack stack, StackTextEncoding encoding) throws IOException {
		return build(stack, new StackTextEncoder(encoding)).bytes;
	}

	private static final class ExportData {
		byte[] bytes;
		Result result;
	}

	private static ExportData build(OStack stack, StackTextEncoder encoder) throws IOException {
		if(stack == null) throw new IOException("No stack is open.");
		ArrayList<OCard> cards = orderedCards(stack);
		if(cards.isEmpty()) throw new IOException("A HyperCard stack must contain at least one card.");
		ArrayList<OBackground> backgrounds = orderedBackgrounds(stack, cards);
		ArrayList<String> warnings = new ArrayList<String>();
		IdAllocator ids = new IdAllocator();
		Map<OCard,Integer> cardIds = mapObjectIds(cards, ids, warnings, "card");
		Map<OBackground,Integer> backgroundIds = mapObjectIds(backgrounds, ids, warnings, "background");

		int listId = ids.allocate();
		int pageCount = (cards.size()+PAGE_CARD_LIMIT-1)/PAGE_CARD_LIMIT;
		int[] pageIds = new int[pageCount];
		for(int i=0; i<pageCount; i++) pageIds[i] = ids.allocate();
		int fontTableId = ids.allocate();
		int styleTableId = ids.allocate();

		Map<Integer,Integer> styleIds = normalizedStyleIds(stack, warnings);
		Map<OCardBase,Integer> pictureIds = new LinkedHashMap<OCardBase,Integer>();
		for(OBackground bg: backgrounds) if(loadPicture(stack, bg) != null) pictureIds.put(bg, ids.allocate());
		for(OCard card: cards) if(loadPicture(stack, card) != null) pictureIds.put(card, ids.allocate());

		ArrayList<Block> blocks = new ArrayList<Block>();
		blocks.add(new Block("LIST", listId, listBlock(listId, pageIds, cards.size())));
		for(int i=0; i<pageCount; i++) {
			int from = i*PAGE_CARD_LIMIT;
			int to = Math.min(cards.size(), from+PAGE_CARD_LIMIT);
			blocks.add(new Block("PAGE", pageIds[i], pageBlock(pageIds[i], listId,
				cards.subList(from, to), cardIds)));
		}
		blocks.add(new Block("FTBL", fontTableId, fontBlock(stack, fontTableId, encoder)));
		blocks.add(new Block("STBL", styleTableId, styleBlock(stack, styleTableId, styleIds)));

		for(int i=0; i<backgrounds.size(); i++) {
			OBackground bg = backgrounds.get(i);
			int next = backgroundIds.get(backgrounds.get((i+1)%backgrounds.size()));
			int previous = backgroundIds.get(backgrounds.get((i+backgrounds.size()-1)%backgrounds.size()));
			blocks.add(new Block("BKGD", backgroundIds.get(bg), backgroundBlock(stack, bg,
				backgroundIds.get(bg), pictureIds.get(bg), next, previous, cards, encoder, styleIds, warnings)));
		}
		for(int i=0; i<cards.size(); i++) {
			OCard card = cards.get(i);
			Integer mappedBg = mappedBackground(card.bgid, backgrounds, backgroundIds);
			if(mappedBg == null) mappedBg = backgroundIds.get(backgrounds.get(0));
			blocks.add(new Block("CARD", cardIds.get(card), cardBlock(stack, card,
				cardIds.get(card), pictureIds.get(card), pageIds[i/PAGE_CARD_LIMIT], mappedBg,
				encoder, styleIds, warnings)));
		}
		int colorPictures = 0;
		for(Map.Entry<OCardBase,Integer> entry: pictureIds.entrySet()) {
			PictureData picture = pictureBlock(stack, entry.getKey(), entry.getValue());
			if(picture.colorLost) colorPictures++;
			blocks.add(new Block("BMAP", entry.getValue(), picture.bytes));
		}
		if(colorPictures>0) warnings.add(colorPictures+" picture(s) were converted to 1-bit black and white.");
		if(hasColoredParts(cards, backgrounds)) {
			warnings.add("Custom button and field colors are not exported yet.");
		}

		byte[] placeholderStack = stackBlock(stack, 0, 0, backgrounds.size(),
			backgroundIds.get(backgrounds.get(0)), cards.size(), cardIds.get(cards.get(0)),
			listId, fontTableId, styleTableId, encoder);
		int mastTables = Math.max(1, ids.highestTable()+1);
		int mastSize = mastTables*512;
		int offset = placeholderStack.length + mastSize;
		for(Block block: blocks) { block.offset = offset; offset += block.bytes.length; }
		byte[] tail = tailBlock();
		int totalSize = offset + tail.length;
		byte[] stackBytes = stackBlock(stack, totalSize, placeholderStack.length, backgrounds.size(),
			backgroundIds.get(backgrounds.get(0)), cards.size(), cardIds.get(cards.get(0)),
			listId, fontTableId, styleTableId, encoder);
		byte[] mast = mastBlock(mastTables, blocks);

		ByteArrayOutputStream all = new ByteArrayOutputStream(totalSize);
		all.write(stackBytes);
		all.write(mast);
		for(Block block: blocks) all.write(block.bytes);
		all.write(tail);

		ExportData answer = new ExportData();
		answer.bytes = all.toByteArray();
		answer.result = new Result(cards.size(), backgrounds.size(), pictureIds.size(), warnings);
		return answer;
	}

	private static ArrayList<OCard> orderedCards(OStack stack) {
		ArrayList<OCard> result = new ArrayList<OCard>();
		Set<OCard> added = new HashSet<OCard>();
		for(Integer id: stack.cardIdList) {
			for(OCard card: stack.cdCacheList) if(card.id == id && added.add(card)) { result.add(card); break; }
		}
		for(OCard card: stack.cdCacheList) if(added.add(card)) result.add(card);
		return result;
	}

	//the stack's background list as it is (bkgnd 1 first: the STAK's first background, then each BKGD's next one), so that
	//real HyperCard numbers the backgrounds as HyperZebra does
	private static ArrayList<OBackground> orderedBackgrounds(OStack stack, List<OCard> cards) throws IOException {
		ArrayList<OBackground> result = new ArrayList<OBackground>(stack.backgroundList());
		if(result.isEmpty()) throw new IOException("The stack has no background.");
		return result;
	}

	private static <T extends OObject> Map<T,Integer> mapObjectIds(List<T> objects,
			IdAllocator ids, ArrayList<String> warnings, String type) {
		Map<T,Integer> result = new HashMap<T,Integer>();
		for(T object: objects) {
			int mapped = ids.preserve(object.id);
			result.put(object, mapped);
			if(mapped != object.id) warnings.add("A conflicting "+type+" ID "+object.id+" was changed to "+mapped+".");
		}
		return result;
	}

	private static Integer mappedBackground(int oldId, List<OBackground> backgrounds,
			Map<OBackground,Integer> ids) {
		for(OBackground background: backgrounds) if(background.id==oldId) return ids.get(background);
		return null;
	}

	private static Map<Integer,Integer> normalizedStyleIds(OStack stack, ArrayList<String> warnings) {
		Map<Integer,Integer> result = new HashMap<Integer,Integer>();
		Set<Integer> used = new HashSet<Integer>();
		int next = 1;
		for(OStack.styleClass style: stack.styleList) {
			if(result.containsKey(style.id)) {
				warnings.add("A duplicate text style definition was omitted.");
				continue;
			}
			int id = style.id;
			if(id<0 || id>65535 || used.contains(id)) {
				while(used.contains(next)) next++;
				id = next++;
				warnings.add("A conflicting text style ID was renumbered.");
			}
			used.add(id);
			result.put(style.id, id);
		}
		return result;
	}

	private static byte[] stackBlock(OStack stack, int totalSize, int mastOffset,
			int bgCount, int firstBg, int cardCount, int firstCard, int listId,
			int fontTableId, int styleTableId, StackTextEncoder encoder) throws IOException {
		byte[] script = encodedScript(joinScript(stack.scriptList), encoder);
		byte[] osa = osaBytes(stack);
		int length = align32(0x601 + script.length + osa.length);
		ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
		b.putInt(length).put(bytes4("STAK")).putInt(-1).putInt(0);
		b.putInt(10).putInt(totalSize).putInt(mastOffset).putInt(0).putInt(0);
		b.putInt(bgCount).putInt(firstBg).putInt(cardCount).putInt(firstCard).putInt(listId);
		b.putInt(0).putInt(0).putInt(0).putInt(stack.getPasswordHash());
		b.putShort((short)stack.userLevel).putShort((short)0);
		//Private Accessはヘッダをパスワードで暗号化する。パスワードが分からなければ(開いたときに聞いていれば分かる)付けない
		boolean privateAccess = stack.getPrivateAccess() && stack.getPasswordHash()!=0 && stack.unlockedPassword!=null;
		int flags = (stack.cantPeek?1<<10:0) | (stack.cantAbort?1<<11:0) | (privateAccess?StackProtection.FLAG_PRIVATE_ACCESS:0) |
			(stack.cantDelete?1<<14:0) | (stack.cantModify?1<<15:0);
		b.putShort((short)flags);
		b.position(0x60);
		for(int i=0; i<4; i++) b.putInt(0x02000000);
		b.position(0x78);
		putRect(b, stack.windowRect);
		putRect(b, stack.screenRect);
		if(stack.scroll != null) b.putShort((short)stack.scroll.y).putShort((short)stack.scroll.x); //v, h
		b.position(0x1B0);
		b.putInt(fontTableId).putInt(styleTableId);
		b.putShort((short)Math.max(1, stack.height)).putShort((short)Math.max(1, stack.width));
		b.position(0x400);
		b.position(0x600);
		b.put(script).put((byte)0).put(osa);
		long sum = 0;
		for(int i=0; i<0x600; i+=4) sum = (sum + Integer.toUnsignedLong(b.getInt(i))) & 0xFFFFFFFFL;
		b.putInt(0x70, (int)(-sum));
		if(privateAccess){
			//チェックサムは暗号化前の内容で取る(HyperCardは復号してから確かめる)
			StackProtection.xorHeader(b.array(), 0,
				StackProtection.hash(StackProtection.passwordBytes(stack.unlockedPassword, stack.legacyTextEncoding)));
		}
		return b.array();
	}

	private static void putRect(ByteBuffer b, java.awt.Rectangle rect) {
		if(rect == null) { b.putLong(0); return; }
		b.putShort((short)rect.x).putShort((short)rect.y)
			.putShort((short)(rect.x+rect.width)).putShort((short)(rect.y+rect.height));
	}

	private static byte[] listBlock(int id, int[] pageIds, int totalCards) throws IOException {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload);
		d.writeInt(id); d.writeInt(0); d.writeInt(pageIds.length); d.writeInt(0x800);
		d.writeInt(totalCards); d.writeShort(8); d.writeShort(2); d.writeShort(1); d.writeShort(0);
		d.writeInt(0); d.writeInt(totalCards); d.writeInt(0);
		for(int i=0; i<pageIds.length; i++) {
			d.writeInt(pageIds[i]); d.writeShort(Math.min(PAGE_CARD_LIMIT, totalCards-i*PAGE_CARD_LIMIT));
		}
		return block("LIST", payload.toByteArray());
	}

	private static byte[] pageBlock(int id, int listId, List<OCard> cards,
			Map<OCard,Integer> cardIds) throws IOException {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload);
		d.writeInt(id); d.writeInt(0); d.writeInt(listId); d.writeInt(0);
		int previousBg = Integer.MIN_VALUE;
		for(int i=0; i<cards.size(); i++) {
			OCard card = cards.get(i);
			d.writeInt(cardIds.get(card));
			boolean hasName = card.name!=null && !card.name.isEmpty();
			//0x20ビットは、実際に本物のHyperCardが書き出したスタックを複数
			//調べたところ、bg fld/btnの上書きの有無(hasCardContent)に関係なく
			//常に立っていた(=「保存済みの実在カード」という意味で、常に1と
			//見て良さそう)。以前はhasCardContent()の結果次第にしていたため、
			//背景側の上書きを持たないカードでこのビットが本物のHyperCardと
			//食い違っていた。
			int flags = (card.marked?0x10:0) | 0x20 |
				(card.bgid!=previousBg?0x40:0) | (hasName?0x80:0);
			d.writeByte(flags);
			//PAGEエントリの各カード情報には、cardId(4バイト)+flags(1バイト)に
			//続けて3バイトの「検索ハッシュ」(findコマンドの単語検索・および
			//"go to card <名前>"によるカード検索を高速化するためのビットマスク
			//らしい)が入る。
			//
			//経緯:このハッシュが非ゼロになっている実例(SH0WGUN・DATAX・THINK等)
			//は、ユーザーが実際に本物のHyperCardでカード名を編集・保存し直した
			//カードだった。つまりこのハッシュは、本物のHyperCard側でそのカードが
			//編集・保存されたときに遅延計算されるキャッシュ値らしい。
			//当初は「未編集のカードはハッシュ0のままで問題ない」と考え0を書いて
			//いたが、ユーザーが実際に検証したところ、エクスポート直後の(一度も
			//本物のHyperCardで編集していない)まっさらな状態でも、ハッシュが0の
			//カードは"go to card <名前>"で見つからず、1枚を編集して直しても
			//他の未編集カードは直らない(1枚ごとに個別に必要)ことを確認した。
			//つまりハッシュ0は「無害」ではなく、実際にカードが見つからなくなる。
			//正確な計算アルゴリズムは非公開かつ複雑で、誤って実装すると別の不具合
			//を生みかねない。ハッシュは「絶対に一致しないカードを足切りする」
			//ためだけに使われる一方向のフィルタなので、全カードに無条件で
			//実際に本物のHyperCardが出力した「使用可能な全ビットが1になっている」
			//値(0x7FFFFF)を書いておけば、正確なアルゴリズムを実装できなくても、
			//どのカードも誤って足切りされることは無くなる。
			d.write(new byte[]{0x7f,(byte)0xff,(byte)0xff});
			previousBg = card.bgid;
		}
		return block("PAGE", payload.toByteArray());
	}

	private static boolean hasCardContent(OCard card) {
		if(card.bgfldList!=null && !card.bgfldList.isEmpty()) return true;
		if(card.bgbtnList!=null && !card.bgbtnList.isEmpty()) return true;
		for(OObject part: card.partsList) if(part.getText()!=null && !part.getText().isEmpty()) return true;
		return false;
	}

	private static boolean hasColoredParts(List<OCard> cards, List<OBackground> backgrounds) {
		ArrayList<OCardBase> owners = new ArrayList<OCardBase>();
		owners.addAll(backgrounds); owners.addAll(cards);
		for(OCardBase owner: owners) for(OObject part: owner.partsList) {
			if(part instanceof OButton) {
				OButton button=(OButton)part;
				if(!java.awt.Color.BLACK.equals(button.color) || !java.awt.Color.WHITE.equals(button.bgColor)) return true;
			}else if(part instanceof OField) {
				OField field=(OField)part;
				if(!java.awt.Color.BLACK.equals(field.color) || !java.awt.Color.WHITE.equals(field.bgColor)) return true;
			}
		}
		return false;
	}

	private static byte[] fontBlock(OStack stack, int id, StackTextEncoder encoder) throws IOException {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload);
		d.writeInt(id); d.write(new byte[6]); d.writeShort(stack.fontList.size()); d.writeInt(0);
		for(OStack.fontClass font: stack.fontList) {
			d.writeShort(font.id); writeFontName(d, font.name, encoder);
		}
		return block("FTBL", payload.toByteArray());
	}

	private static byte[] styleBlock(OStack stack, int id, Map<Integer,Integer> styleIds) throws IOException {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload);
		d.writeInt(id); d.writeInt(0); d.writeInt(styleIds.size());
		int next = 1;
		for(Integer value: styleIds.values()) next = Math.max(next, value+1);
		d.writeInt(next);
		Set<Integer> written = new HashSet<Integer>();
		for(OStack.styleClass style: stack.styleList) {
			if(!written.add(style.id)) continue;
			d.writeInt(styleIds.get(style.id)); d.writeInt(0); d.writeShort(0); d.writeShort(0);
			d.writeShort(style.font); d.writeByte(style.style<0?0xFF:style.style);
			d.writeByte(style.style<0?0xFF:0); d.writeShort(style.size); d.write(new byte[6]);
		}
		return block("STBL", payload.toByteArray());
	}

	private static byte[] backgroundBlock(OStack stack, OBackground bg, int id, Integer bitmapId,
			int next, int previous, List<OCard> cards, StackTextEncoder encoder,
			Map<Integer,Integer> styleIds, ArrayList<String> warnings) throws IOException {
		PartSet partSet = parts(bg, encoder, warnings);
		ArrayList<byte[]> contents = new ArrayList<byte[]>();
		for(OObject part: bg.partsList) if(!(part instanceof OField) || ((OField)part).sharedText) {
			if(part.getText()==null || part.getText().isEmpty()) continue;
			contents.add(content(partSet.ids.get(part), part.getText(),
				part instanceof OField ? ((OField)part).styleList : null, encoder, styleIds));
		}
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload);
		d.writeInt(id); d.writeInt(0); d.writeInt(bitmapId==null?0:bitmapId);
		int flags = (bg.dontSearch?1<<11:0) | (!bg.showPict?1<<13:0) |
			(bg.cantDelete?1<<14:0) | (bg.cantModify?1<<15:0);
		d.writeShort(flags); d.write(new byte[2]);
		int cardCount = 0; for(OCard card: cards) if(card.bgid==bg.id) cardCount++;
		d.writeInt(cardCount); d.writeInt(next); d.writeInt(previous);
		d.writeShort(partSet.bytes.size()); d.writeShort(nextPartId(partSet)); d.writeInt(total(partSet.bytes));
		d.writeShort(contents.size()); d.writeInt(total(contents));
		for(byte[] part: partSet.bytes) d.write(part);
		for(byte[] content: contents) d.write(content);
		writeCString(d, bg.name, encoder); writeCString(d, joinScript(bg.scriptList), encoder, true); d.write(osaBytes(bg));
		return block("BKGD", payload.toByteArray());
	}

	private static byte[] cardBlock(OStack stack, OCard card, int id, Integer bitmapId,
			int pageId, int bgId, StackTextEncoder encoder, Map<Integer,Integer> styleIds,
			ArrayList<String> warnings) throws IOException {
		PartSet partSet = parts(card, encoder, warnings);
		ArrayList<byte[]> contents = new ArrayList<byte[]>();
		for(OObject part: card.partsList) if(part.getText()!=null && !part.getText().isEmpty()) {
			contents.add(content(-partSet.ids.get(part), part.getText(),
				part instanceof OField ? ((OField)part).styleList : null, encoder, styleIds));
		}
		if(card.bgfldList!=null) for(OBgFieldData field: card.bgfldList) {
			if(field.text!=null && !field.text.isEmpty()) contents.add(content(field.id, field.text,
				field.styleList, encoder, styleIds));
		}
		if(card.bgbtnList!=null) for(OBgButtonData button: card.bgbtnList) {
			if(button.check_hilite) contents.add(content(button.id, "1", null, encoder, styleIds));
		}
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload);
		d.writeInt(id); d.writeInt(0); d.writeInt(bitmapId==null?0:bitmapId);
		int flags = (card.dontSearch?1<<11:0) | (!card.showPict?1<<13:0) |
			(card.cantDelete?1<<14:0) | (card.cantModify?1<<15:0);
		d.writeShort(flags); d.write(new byte[10]); d.writeInt(pageId); d.writeInt(bgId);
		d.writeShort(partSet.bytes.size()); d.writeShort(nextPartId(partSet)); d.writeInt(total(partSet.bytes));
		d.writeShort(contents.size()); d.writeInt(total(contents));
		for(byte[] part: partSet.bytes) d.write(part);
		for(byte[] content: contents) d.write(content);
		writeCString(d, card.name, encoder); writeCString(d, joinScript(card.scriptList), encoder, true); d.write(osaBytes(card));
		return block("CARD", payload.toByteArray());
	}

	private static final class PartSet {
		ArrayList<byte[]> bytes = new ArrayList<byte[]>();
		Map<OObject,Integer> ids = new HashMap<OObject,Integer>();
	}

	private static PartSet parts(OCardBase owner, StackTextEncoder encoder,
			ArrayList<String> warnings) throws IOException {
		PartSet result = new PartSet();
		Set<Integer> used = new HashSet<Integer>();
		int next = 1;
		for(OObject part: owner.partsList) {
			int id = part.id;
			if(id<=0 || id>32767 || used.contains(id)) {
				while(used.contains(next)) next++;
				id = next++;
				warnings.add("A conflicting part ID was renumbered on "+owner.getShortName()+".");
			}
			used.add(id); result.ids.put(part, id);
			result.bytes.add(partBlock(part, id, owner.stack, encoder));
		}
		return result;
	}

	private static byte[] partBlock(OObject object, int id, OStack stack,
			StackTextEncoder encoder) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(out);
		d.writeShort(0); d.writeShort(id);
		if(object instanceof OButton) {
			OButton button = (OButton)object;
			d.writeByte(1); d.writeByte((button.getVisible()?0:0x80) | (button.enabled?0:1));
			writeRect(d, button); d.writeByte((button.showName?0x80:0) | (button.getHilite()?0x40:0) |
				(button.getAutoHilite()?0x20:0) | (!button.sharedHilite && button.parent instanceof OBackground?0x10:0) | (button.group&15));
			//a card button keeps its hilite in these flags only when the "not shared" bit is clear: with the bit set real
			//HyperCard reads the hilite from the card's contents instead, and an exported card button lost its hilite
			d.writeByte(exportButtonStyle(button.style)); d.writeShort(button.titleWidth);
			d.writeShort(button.style==8 ? button.getSelectedLine() : button.icon);
			writeTextProperties(d, button.textAlign, fontId(stack, button.textFont), button.textSize,
				button.textStyle, button.textHeight);
		} else {
			OField field = (OField)object;
			d.writeByte(2); d.writeByte((field.getVisible()?0:0x80) | (field.dontWrap?0x20:0) |
				(field.dontSearch?0x10:0) | (field.sharedText?8:0) | (!field.fixedLineHeight?4:0) |
				(field.autoTab?2:0) | (field.enabled?1:0));
			writeRect(d, field); d.writeByte((field.autoSelect?0x80:0) | (field.showLines?0x40:0) |
				(field.wideMargins?0x20:0) | (field.multipleLines?0x10:0));
			d.writeByte(exportFieldStyle(field.style)); d.writeShort(field.selectedEnd); d.writeShort(field.selectedStart);
			writeTextProperties(d, field.textAlign, fontId(stack, field.textFont), field.textSize,
				field.textStyle, field.textHeight);
		}
		writePaddedCString(d, object.name, encoder);
		if(object.osaScript!=null){
			//an AppleScript (OSA) script: its source text and NUL, then the compiled 'WOSA' record as it was imported (no
			//padding byte between them; the record is padded to an even length below)
			writeCString(d, joinScript(object.scriptList), encoder, true); d.write(object.osaScript.bytes());
		}
		else writePaddedCString(d, joinScript(object.scriptList), encoder, true);
		if((out.size()&1)!=0) d.writeByte(0);
		byte[] bytes = out.toByteArray();
		ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putShort(0, (short)bytes.length);
		return bytes;
	}

	private static void writeRect(DataOutputStream d, OObject object) throws IOException {
		d.writeShort(object.top); d.writeShort(object.left);
		d.writeShort(object.top+object.height); d.writeShort(object.left+object.width);
	}

	private static void writeTextProperties(DataOutputStream d, int align, int font,
			int size, int style, int height) throws IOException {
		d.writeShort(align==2?-1:align); d.writeShort(font); d.writeShort(size);
		d.writeByte(style); d.writeByte(0); d.writeShort(height);
	}

	private static int exportButtonStyle(int style) {
		int[] map = {8,0,1,2,4,3,9,10,11,5,6};
		return style>=0 && style<map.length ? map[style] : 8;
	}

	private static int exportFieldStyle(int style) {
		switch(style) { case 1:return 0; case 2:return 1; case 3:return 2; case 4:return 4; case 5:return 7; default:return 0; }
	}

	private static int fontId(OStack stack, String name) {
		if(name!=null) for(OStack.fontClass font: stack.fontList) if(name.equals(font.name)) return font.id;
		return 0;
	}

	private static byte[] content(int signedPartId, String text, ArrayList<styleClass> styles,
			StackTextEncoder encoder, Map<Integer,Integer> styleIds) throws IOException {
		byte[] textBytes = encoded(text, encoder);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		DataOutputStream b = new DataOutputStream(body);
		if(styles == null || styles.isEmpty()) {
			b.writeByte(0); b.write(textBytes);
		} else {
			ArrayList<styleClass> valid = new ArrayList<styleClass>();
			for(styleClass run: styles) if(styleIds.containsKey(run.styleId)) valid.add(run);
			b.writeShort(0x8000 | (2+valid.size()*4));
			for(styleClass run: valid) {
				int charOffset = Math.max(0, Math.min(text.length(), run.textPosition));
				b.writeShort(encoded(text.substring(0, charOffset), encoder).length);
				b.writeShort(styleIds.get(run.styleId));
			}
			b.write(textBytes);
		}
		ByteArrayOutputStream record = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(record);
		d.writeShort(signedPartId); d.writeShort(body.size()); d.write(body.toByteArray());
		if((body.size()&1)!=0) d.writeByte(0);
		return record.toByteArray();
	}

	private static PictureData pictureBlock(OStack stack, OCardBase owner, int id) throws IOException {
		BufferedImage image = loadPicture(stack, owner);
		if(image == null) throw new IOException("Picture disappeared during export.");
		int width = Math.min(stack.width, image.getWidth());
		int height = Math.min(stack.height, image.getHeight());
		int rowBytes = ((width+31)/32)*4;
		boolean needMask = owner instanceof OCard;
		int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
		boolean colorLost = false;
		for(int i=0; i<argb.length; i++) {
			int alpha=(argb[i]>>>24)&255, red=(argb[i]>>>16)&255, green=(argb[i]>>>8)&255, blue=argb[i]&255;
			if(alpha!=0 && (red!=green || green!=blue || alpha!=255)) { colorLost=true; break; }
		}
		boolean[] black = atkinsonDither(argb, width, height);
		//まず無圧縮のビット列(1行rowBytesバイト、MSBが左端)を作り、WOBAで圧縮する
		byte[] imageRows = new byte[height*rowBytes];
		byte[] maskRows = needMask ? new byte[height*rowBytes] : null;
		for(int y=0; y<height; y++) {
			int rowOffset=y*rowBytes, pixelOffset=y*width;
			for(int x=0; x<width; x++) {
				int bit = 0x80>>>(x&7), index = rowOffset+(x>>3);
				if(black[pixelOffset+x]) imageRows[index] |= bit;
				//マスクは「白い画素の位置」を表すビットマップで、1ビットが不透明
				//(白)、0ビットが透明(HCData.applyBmapTransparency参照)。以前は
				//逆に透明画素でビットを立てていたため、書き出したカードを読み戻すと
				//白と透明が入れ替わっていた。
				if(needMask && ((argb[pixelOffset+x]>>>24)&255)>=128) maskRows[index] |= bit;
			}
		}
		byte[] imageData = encodeWOBA(imageRows, rowBytes, height);
		byte[] maskData = needMask ? encodeWOBA(maskRows, rowBytes, height) : new byte[0];
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload);
		d.writeInt(id); d.write(new byte[12]);
		int canvasWidth = ((Math.max(1, stack.width)+31)/32)*32;
		int canvasHeight = Math.max(1, stack.height);
		d.writeShort(0); d.writeShort(0); d.writeShort(canvasHeight); d.writeShort(canvasWidth);
		if(needMask) {
			d.writeShort(0); d.writeShort(0); d.writeShort(height); d.writeShort(width);
		}else{
			d.writeLong(0);
		}
		d.writeShort(0); d.writeShort(0); d.writeShort(height); d.writeShort(width);
		d.write(new byte[8]); d.writeInt(maskData.length); d.writeInt(imageData.length);
		d.write(maskData); d.write(imageData);
		PictureData result = new PictureData(); result.bytes=block("BMAP",payload.toByteArray()); result.colorLost=colorLost;
		return result;
	}

	//WOBA(HyperCardのカードピクチャ圧縮形式)のエンコーダ。rowsは1行rowBytes
	//バイト(32bit境界に揃えてある)の無圧縮ビット列で、height行分を並べたもの。
	//デコード側はHCData.decodeWOBABitsを参照。
	//
	//圧縮率よりも速度を優先し、1パスで次の命令だけを使う:
	//  0x81/0x82        白1行/黒1行
	//  0x83 v           1行同一バイト
	//  0x85/0x86/0x87   1/2/3行上と同じ行
	//  0xA0+n           次の1バイト命令(0x81/0x82/0x85)をn行分繰り返す
	//  0xE0+n           16nバイトのゼロ
	//  0xC0+n           8nバイトのデータ
	//  (d<<4)|z         zバイトのゼロに続いてdバイトのデータ(z≦15、d≦7)
	//  0x80             無圧縮1行(圧縮しても縮まなかった行の逃げ道)
	//  0x8A/0x89        1行上との差分XORモードのオン/オフ
	//行間差分は、データ命令で符号化する行ごとに「そのまま」と「1行上とのXOR」の
	//両方を符号化して短いほうを選ぶだけで、水平方向の差分(dh)や2行上との
	//差分は探索しない。行単位の命令(0x80〜0x87)はデコーダがXORを適用せずに
	//次の行へ進むので、モードに関係なく使える。
	static byte[] encodeWOBA(byte[] rows, int rowBytes, int height) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(rows.length/4+16);
		byte[] rowBuffer = new byte[rowBytes+rowBytes/8+2];
		byte[] deltaBuffer = new byte[rowBuffer.length];
		byte[] delta = new byte[rowBytes];
		boolean deltaMode = false;
		int y = 0;
		while(y<height) {
			int offset = y*rowBytes;
			int opcode = rowOpcode(rows, offset, rowBytes, y);
			//同じ内容の行が続く数(先頭の行を含めて最大31行=繰り返し命令の上限)
			int same = 1;
			while(same<31 && y+same<height && rowsEqual(rows, offset, offset+same*rowBytes, rowBytes)) same++;
			if(opcode==0x81 || opcode==0x82 || opcode==0x85) {
				//「1行上と同じ」の繰り返しは、その行と同じ内容が続くことと同義
				if(same>1) out.write(0xA0+same);
				out.write(opcode);
				y += same;
				continue;
			}
			if(opcode==0x83) { out.write(0x83); out.write(rows[offset]); }
			else if(opcode==0x86 || opcode==0x87) out.write(opcode);
			else {
				int length = encodeWOBARow(rows, offset, rowBytes, rowBuffer);
				int deltaLength = Integer.MAX_VALUE;
				if(y>0) {
					for(int i=0; i<rowBytes; i++) delta[i] = (byte)(rows[offset+i]^rows[offset-rowBytes+i]);
					deltaLength = encodeWOBARow(delta, 0, rowBytes, deltaBuffer);
				}
				int plainCost = length+(deltaMode?1:0), deltaCost = deltaLength+(deltaMode?0:1);
				if(deltaCost<plainCost && deltaLength<=rowBytes) {
					if(!deltaMode) { out.write(0x8A); deltaMode=true; }
					out.write(deltaBuffer, 0, deltaLength);
				} else if(length<=rowBytes) {
					if(deltaMode) { out.write(0x89); deltaMode=false; }
					out.write(rowBuffer, 0, length);
				} else {
					out.write(0x80); out.write(rows, offset, rowBytes);
				}
			}
			if(same>2) { out.write(0xA0+(same-1)); out.write(0x85); }
			else if(same==2) out.write(0x85);
			y += same;
		}
		return out.toByteArray();
	}

	//行全体を1〜2バイトで表せる命令を返す。なければ0(データ命令で符号化する)。
	private static int rowOpcode(byte[] rows, int offset, int rowBytes, int y) {
		byte first = rows[offset];
		boolean uniform = true;
		for(int i=1; i<rowBytes; i++) if(rows[offset+i]!=first) { uniform=false; break; }
		if(uniform) {
			if(first==0) return 0x81;
			if(first==(byte)0xFF) return 0x82;
		}
		for(int back=1; back<=3 && back<=y; back++) {
			if(rowsEqual(rows, offset, offset-back*rowBytes, rowBytes)) return 0x84+back;
		}
		return uniform ? 0x83 : 0;
	}

	private static boolean rowsEqual(byte[] rows, int a, int b, int rowBytes) {
		for(int i=0; i<rowBytes; i++) if(rows[a+i]!=rows[b+i]) return false;
		return true;
	}

	//1行をゼロ連長とデータ連長の命令で符号化してbufferに書き、長さを返す。
	//ゼロが2バイト以上続くところでデータ連長を区切る(1バイトのゼロはデータの
	//まま含めたほうが命令バイトを節約できる)。行末ちょうどでx座標が行幅に
	//達するよう、末尾のゼロも必ず命令として出す。
	private static int encodeWOBARow(byte[] rows, int offset, int rowBytes, byte[] buffer) {
		int length=0, pos=0;
		while(pos<rowBytes) {
			int zeros = 0;
			while(pos+zeros<rowBytes && rows[offset+pos+zeros]==0) zeros++;
			if(zeros>=16) {
				int n = Math.min(31, zeros/16);
				buffer[length++] = (byte)(0xE0+n);
				pos += n*16;
				continue;
			}
			int dataStart=pos+zeros, data=0;
			while(dataStart+data<rowBytes) {
				int i = offset+dataStart+data;
				if(rows[i]==0 && (dataStart+data+1>=rowBytes || rows[i+1]==0)) break;
				data++;
			}
			if(data<=7) {
				if(length+1+data>buffer.length) return buffer.length+1;
				buffer[length++] = (byte)((data<<4)|zeros);
				System.arraycopy(rows, offset+dataStart, buffer, length, data);
				length += data;
				pos = dataStart+data;
				continue;
			}
			//ゼロ命令1つ + 8バイト単位のデータ命令(1つで最大248バイト) + 端数の命令
			int needed = (zeros>0?1:0) + (data+247)/248 + 1 + data;
			if(length+needed>buffer.length) return buffer.length+1;
			if(zeros>0) buffer[length++] = (byte)zeros;
			pos = dataStart;
			while(data>=8) {
				int n = Math.min(31, data/8);
				buffer[length++] = (byte)(0xC0+n);
				System.arraycopy(rows, offset+pos, buffer, length, n*8);
				length += n*8; pos += n*8; data -= n*8;
			}
			if(data>0) {
				buffer[length++] = (byte)(data<<4);
				System.arraycopy(rows, offset+pos, buffer, length, data);
				length += data; pos += data;
			}
		}
		return length;
	}

	private static final class PictureData { byte[] bytes; boolean colorLost; }

	//カードピクチャは1bit白黒なので、中間調を持つ画像はどこかで2値化する必要が
	//ある。単純な閾値処理では写真やグラデーションが黒い塊に潰れてしまうため、
	//Atkinsonディザリングで2値化する。
	//
	//Atkinsonディザリングは、QuickDraw・MacPaint・HyperCard本体の作者である
	//Bill Atkinsonが初代Macのために作った誤差拡散方式で、classic Macの1bit画像
	//そのものの見た目になる(当時のスキャナソフトにも搭載されていた)。
	//Floyd-Steinbergと違い、誤差の3/4だけを近傍6画素へ1/8ずつ配り、残り1/4は
	//捨てる。そのぶん明部・暗部のディテールは落ちるが、白飛び・黒潰れが締まって
	//コントラストが強く出る。
	//
	//    誤差の配分(*が処理中の画素、数値は誤差の何倍を足すか):
	//          *   1/8 1/8
	//      1/8 1/8 1/8
	//          1/8
	//
	//元画像が既に純粋な白黒(中間調なし)の場合、どの画素も誤差0なので伝播が
	//一切起きず、結果は閾値処理と完全に同じになる。つまりHyperCard由来の
	//1bit画像をそのまま書き戻すときには影響しない。
	private static boolean[] atkinsonDither(BufferedImage image, int width, int height) {
		return atkinsonDither(image.getRGB(0, 0, width, height, null, 0, width), width, height);
	}

	private static boolean[] atkinsonDither(int[] pixels, int width, int height) {
		int[] level = new int[width*height];
		boolean[] opaque = new boolean[width*height];
		for(int y=0; y<height; y++) for(int x=0; x<width; x++) {
			int index=y*width+x, argb=pixels[index], alpha=(argb>>>24)&255;
			opaque[index] = alpha>=128;
			//透明部はマスクで抜かれて描画されないので、誤差を出さない白として扱う
			if(!opaque[index]) { level[index]=255; continue; }
			int red=(argb>>>16)&255, green=(argb>>>8)&255, blue=argb&255;
			level[index] = (299*red+587*green+114*blue)/1000;
		}
		boolean[] black = new boolean[width*height];
		for(int y=0; y<height; y++) for(int x=0; x<width; x++) {
			int index = y*width+x;
			//透明部は描画されないので、2値化もせず誤差も伝播させない
			if(!opaque[index]) continue;
			int value = level[index];
			black[index] = value<128;
			int error = (value-(black[index]?0:255))/8;
			if(error==0) continue;
			diffuse(level, opaque, width, height, x+1, y,   error);
			diffuse(level, opaque, width, height, x+2, y,   error);
			diffuse(level, opaque, width, height, x-1, y+1, error);
			diffuse(level, opaque, width, height, x,   y+1, error);
			diffuse(level, opaque, width, height, x+1, y+1, error);
			diffuse(level, opaque, width, height, x,   y+2, error);
		}
		return black;
	}

	private static void diffuse(int[] level, boolean[] opaque, int width, int height,
			int x, int y, int error) {
		if(x<0 || x>=width || y<0 || y>=height) return;
		int index = y*width+x;
		if(!opaque[index]) return;
		level[index] += error;
	}

	private static BufferedImage loadPicture(OStack stack, OCardBase owner) {
		BufferedImage base = owner.pict;
		if(base == null && owner.bitmapName != null && stack.file != null && stack.file.getParentFile() != null) {
			try { base = StackProtection.readImage(new File(stack.file.getParentFile(), owner.bitmapName)); }
			catch(IOException e) { base = null; }
		}
		//追加のペイントレイヤーは平らにして書き出す(HyperCardにはレイヤーが無い)
		if(owner.hasLayers()) {
			owner.loadLayerImages();
			int w = stack.width, h = stack.height;
			if(base != null) { w = Math.max(w, base.getWidth()); h = Math.max(h, base.getHeight()); }
			return PaintLayers.flatten(base, owner.layers, w, h);
		}
		return base;
	}

	private static byte[] mastBlock(int tableCount, List<Block> blocks) {
		ByteBuffer b = ByteBuffer.allocate(tableCount*512).order(ByteOrder.BIG_ENDIAN);
		b.putInt(tableCount*512).put(bytes4("MAST")).putInt(-1).putInt(0);
		for(Block block: blocks) {
			int table=block.id>>>15, slot=(block.id>>>8)&0x7F;
			int position=table*512+slot*4;
			b.putInt(position, ((block.offset/32)<<8) | (block.id&255));
		}
		return b.array();
	}

	private static byte[] tailBlock() throws IOException {
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(payload); d.writeInt(-1); d.writeInt(0);
		return block("TAIL", payload.toByteArray());
	}

	private static byte[] block(String type, byte[] payload) throws IOException {
		int length=align32(8+payload.length);
		ByteArrayOutputStream out=new ByteArrayOutputStream(length); DataOutputStream d=new DataOutputStream(out);
		d.writeInt(length); d.write(bytes4(type)); d.write(payload); d.write(new byte[length-8-payload.length]);
		return out.toByteArray();
	}

	private static int total(List<byte[]> records) { int total=0; for(byte[] record:records) total+=record.length; return total; }
	/** The compiled AppleScript (OSA) record that follows an object's script text and NUL, or none (OsaScript). */
	private static byte[] osaBytes(OObject object) { return object.osaScript==null ? new byte[0] : object.osaScript.bytes(); }
	private static int nextPartId(PartSet set) { int max=0; for(Integer id:set.ids.values()) max=Math.max(max,id); return Math.min(32767,max+1); }
	private static int align32(int value) { return (value+31)&~31; }
	private static byte[] bytes4(String value) { return new byte[]{(byte)value.charAt(0),(byte)value.charAt(1),(byte)value.charAt(2),(byte)value.charAt(3)}; }
	private static String joinScript(ArrayList<String> lines) {
		if(lines==null || lines.isEmpty()) return "";
		StringBuilder out=new StringBuilder(); for(int i=0;i<lines.size();i++){if(i>0)out.append('\n');out.append(lines.get(i));} return out.toString();
	}
	private static byte[] encoded(String text, StackTextEncoder encoder) {
		return encoder.encode((text==null?"":text).replace("\r\n","\n").replace('\n','\r'));
	}
	//NUL終端文字列を書くだけで、パディングは付けない。BKGD/CARD(name・
	//script)で使う。実在の(HyperZebraで書き出したのではない)本物の
	//HyperCardスタック複数本を読み込んで確認したところ、これらのレベルの
	//name・scriptの直後にパディングは一切無かった。
	//(以前はここにも「常に1バイトパディングする」という誤った処理を
	//入れていたが、それだと本物のHyperCardが書き出したスタックを開いたとき
	//カード/背景スクリプトの先頭1文字が失われるという逆方向の不具合を
	//生んでしまっていた。)
	private static void writeCString(DataOutputStream d, String text, StackTextEncoder encoder) throws IOException {
		writeCString(d, text, encoder, false);
	}
	private static void writeCString(DataOutputStream d, String text, StackTextEncoder encoder, boolean script) throws IOException {
		byte[] bytes = script ? encodedScript(text, encoder) : encoded(text, encoder);
		d.write(bytes); d.writeByte(0);
	}
	/** A script: HyperTalk's ¬ ≠ ≤ ≥ are Mac OS Roman bytes whatever the stack's encoding (see HCData.decodeScript). */
	private static byte[] encodedScript(String text, StackTextEncoder encoder) {
		return encoder.encodeScript((text==null?"":text).replace("\r\n","\n").replace('\n','\r'));
	}

	//FTBLのフォント名だけは、name自体のバイト数が偶数のときだけ、直後に
	//1バイトのパディングが入る(name+NULが奇数長になるときにパディングして
	//偶数長にする、という昔ながらの単純な偶数バイト境界合わせ)。
	//OStack.readFontBlockの対応するコメント参照。
	private static void writeFontName(DataOutputStream d, String text, StackTextEncoder encoder) throws IOException {
		byte[] bytes = encoded(text, encoder);
		d.write(bytes); d.writeByte(0);
		if((bytes.length & 1)==0) d.writeByte(0);
	}

	//PART(ボタン/フィールド)のname・scriptだけは、classic Mac形式で
	//NUL終端文字列の直後に、文字列自体のバイト数が偶数か奇数かに関係なく、
	//常に1バイトの固定パディングが入る。これはPARTレコード固有の仕様で、
	//FTBL/BKGD/CARDレベルのname・script(パディング無し。上のwriteCString
	//参照)とは異なる。実際に本物のHyperCardで書き出したスタックとバイト
	//単位で比較して確認した。
	private static void writePaddedCString(DataOutputStream d, String text, StackTextEncoder encoder) throws IOException {
		writePaddedCString(d, text, encoder, false);
	}
	private static void writePaddedCString(DataOutputStream d, String text, StackTextEncoder encoder, boolean script) throws IOException {
		byte[] bytes = script ? encodedScript(text, encoder) : encoded(text, encoder);
		d.write(bytes); d.writeByte(0); d.writeByte(0);
	}

	private static final class Block {
		String type; int id; int offset; byte[] bytes;
		Block(String type,int id,byte[] bytes){this.type=type;this.id=id;this.bytes=bytes;}
	}

	private static final class IdAllocator {
		private final Set<Integer> ids=new HashSet<Integer>();
		private final Set<Integer> slots=new HashSet<Integer>();
		private int nextSlot=8;
		int preserve(int requested) {
			int slot=requested>>>8, table=requested>>>15, slotInTable=(requested>>>8)&0x7F;
			if(requested>0 && requested<2265535 && !(table==0&&slotInTable<8) && !ids.contains(requested) && !slots.contains(slot)) {
				ids.add(requested);slots.add(slot);return requested;
			}
			return allocate();
		}
		int allocate() {
			while(slots.contains(nextSlot) || ((nextSlot>>>7)==0 && (nextSlot&0x7F)<8)) nextSlot++;
			int slot=nextSlot++, id=(slot<<8)|((slot%255)+1); ids.add(id);slots.add(slot);return id;
		}
		int highestTable(){int max=0;for(Integer slot:slots)max=Math.max(max,slot>>>7);return max;}
	}
}

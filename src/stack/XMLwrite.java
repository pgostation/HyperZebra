import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;


public class XMLwrite {
	OStack stack;
	saveThread saveThread;
	
	public XMLwrite(OStack stack){
		this.stack = stack;
		
		saveThread = new saveThread();
		saveThread.start();
	}

	/** Writes a stack's _stack.xml once, without the change-watching thread (the Home stack converted at start-up). */
	static void writeOnce(OStack stack){
		XMLwrite w = new XMLwrite(stack, false);
		w.saveStackNow(true);
	}
	private XMLwrite(OStack stack, boolean watch){
		this.stack = stack;
	}
	/** A writer without the autosave thread (tests drive the writes themselves). */
	static XMLwrite withoutAutosave(OStack stack){ return new XMLwrite(stack, false); }
	
	/** Why the last write failed (shown to the user), or null when it succeeded. */
	volatile String lastError;
	/** When the last write failed (System.currentTimeMillis), for the autosave's back-off. */
	private volatile long lastFailureAt;
	/** The autosave told the user about the current failure (StackSaving): no second dialog until a write succeeds. */
	volatile boolean failureReported;
	/** How long the autosave waits after a failed write before it tries again (a full disk is not written every second). */
	static final long RETRY_AFTER_FAILURE_MS=30_000;
	/** While an "on idle" handler keeps running, unsaved changes are written at most this often (not every second). */
	static final long BUSY_SAVE_NANOS=10_000_000_000L;
	/** When the changes now waiting for the autosave were first seen (System.nanoTime, 0 = none). */
	private long unsavedSince;

	//メニュー(New Stack等)と監視スレッドの両方から呼ばれるため、同時に同じ
	//ファイルへ書き込まないようsynchronizedにしておく
	/** Writes the stack; false (with {@link #lastError}) when it could not be written - the changes then stay marked as unsaved. */
	public synchronized boolean saveStackNow(){ return saveStackNow(false); }

	/** Writes the stack when it has unsaved changes. Waits for a write in progress (the autosave's) and reports its outcome. */
	synchronized boolean saveIfNeeded(){
		if(!needsSave()) return syncBundle();
		return saveStackNow(false);
	}

	/** A stack file (.hzb ZIP) gets what was written to its working folder outside the stack itself (pictures, resources). */
	private boolean syncBundle(){
		String failure = StackBundle.sync(targetFile());
		if(failure==null){
			if(lastError!=null || failureReported){ lastError = null; failureReported = false; StackSaving.recovered(stack); }
			return true;
		}
		lastError = failure;
		lastFailureAt = System.currentTimeMillis();
		System.err.println("Could not save the stack "+StackBundle.host(stack.file)+": "+failure);
		return false;
	}

	/** {@code initial}: the first write of a stack just converted from HyperCard — done even for a "can't modify" stack,
	 *  otherwise the .hzb has no _stack.xml at all and cannot be opened again (including locked 1.x stacks).
	 *  The change flags are cleared as each part is serialized (an edit made meanwhile marks its part again) and are all
	 *  set again unless the file reached the disk, so a failed write (a full disk, no permission) is never taken for a
	 *  saved stack: the autosave tries again and closing the stack asks the user (StackSaving). */
	synchronized boolean saveStackNow(boolean initial){
		//"can't modify"のスタックは書き出さない(仕様)。ただしユーザーが編集画面で変えたもの(スクリプト・部品など)は捨てない
		if(stack.cantModify && !initial && !stack.userEdited) return true;
		boolean userEdits = stack.userEdited;
		stack.userEdited = false;
		ArrayList<OCardBase> cleared = new ArrayList<OCardBase>();
		String failure;
		try{
			failure = write(cleared);
		}catch(RuntimeException e){
			//書き出し途中の例外(部品の整合性が崩れている等)も「保存できなかった」として扱う
			e.printStackTrace();
			failure = "Internal error: "+e;
		}
		if(failure==null){
			boolean wasFailing = lastError!=null || failureReported;
			lastError = null;
			failureReported = false;
			if(wasFailing) StackSaving.recovered(stack); //「未保存」の表示を消す
			return true;
		}
		//ディスクに書けなかった: 消した変更フラグを全部戻す(次の自動保存・閉じるときの確認の対象に残す)
		stack.changed = true;
		if(userEdits) stack.userEdited = true;
		for(OCardBase part: cleared) part.changed = true;
		lastError = failure;
		lastFailureAt = System.currentTimeMillis();
		System.err.println("Could not save the stack "+StackBundle.host(stack.file)+": "+failure);
		return false;
	}

	/** The file the stack is written to: the stack's own .xml, or _stack.xml in its folder. */
	File targetFile(){
		if(stack.file.getName().endsWith(".xml")) return stack.file;
		return new File(stack.file.getParent()+File.separatorChar+"_stack.xml");
	}

	/** Serializes and writes the stack; null when it reached the disk, otherwise why not. */
	private String write(ArrayList<OCardBase> cleared){
		File file = targetFile();
		if(file.exists() && !file.canWrite()) return "Writing is not allowed: "+file.getPath();
		File dir = file.getAbsoluteFile().getParentFile();
		if(dir==null || !dir.isDirectory()) return "The folder is missing: "+(dir==null ? file.getPath() : dir.getPath());

		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		 
        StringWriter stringWriter = new StringWriter();
        try {
        	XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);
        	
			writer.writeStartDocument("utf-8", "1.0");
            writer.writeCharacters("\n");
			//writer.writeDTD("<!DOCTYPE stackfile PUBLIC \"-//Apple, Inc.//DTD stackfile V 2.0//EN\" \"\" >");
            writer.writeCharacters("\n");
	        writer.writeStartElement("stackfile");
            writer.writeCharacters("\n\t");

	        stack.changed = false; //書き出す前に消す: 書き出し中の変更は再びtrueにするので次回に残る
	        stack.writeXML(writer);
	        stack.writeFontXML(writer);
	        stack.writeStyleXML(writer);
			for(int i=0 ;i<stack.bgCacheList.size(); i++){
				OBackground bg = stack.bgCacheList.get(i);
				if(bg.changed){ bg.changed = false; cleared.add(bg); }
				bg.writeXML(writer);
			}
			if(stack.cardIdList.size()>=stack.cdCacheList.size()){
				for(int i=0 ;i<stack.cardIdList.size(); i++){
					int id = stack.cardIdList.get(i);
					for(int j=0 ;j<stack.cdCacheList.size(); j++){
						OCard cd = stack.cdCacheList.get(j);
						if(cd.id == id){
							if(cd.changed){ cd.changed = false; cleared.add(cd); }
							cd.writeXML(writer);
							break;
						}
					}
				}
			}else{
				for(int i=0 ;i<stack.cdCacheList.size(); i++){
					OCard cd = stack.cdCacheList.get(i);
					if(cd.changed){ cd.changed = false; cleared.add(cd); }
					cd.writeXML(writer);
				} //これでは順番が狂ってしまう
			}
	        stack.rsrc.writeXML(writer);
	        stack.rsrc.writePLTEXML(writer);
	        stack.rsrc.writeAddColorXML(writer);
	        stack.rsrc.writeXcmdXML(writer);
	 
	        writer.writeEndDocument();
	 
	        writer.close();
		} catch (XMLStreamException e) {
			e.printStackTrace();
			return "Could not build the stack file: "+e.getMessage();
		}

		byte[] xml;
		try {
			xml = XmlText.encodeControlChars(stringWriter.toString()).getBytes("utf-8");
		} catch (java.io.UnsupportedEncodingException e) {
			return e.toString();
		}
		//パスワード付きのスタックはフォルダを開いても中身(スクリプト・テキスト)が読めないよう封印する
		if(StackProtection.sealsXml(stack)){
			try {
				xml = StackProtection.seal(xml, StackProtection.sealPassword(stack));
			} catch (java.security.GeneralSecurityException e) {
				e.printStackTrace();
				return "Could not seal the protected stack: "+e.getMessage(); //平文で書き出してしまわない
			}
		}
		try {
			writeAtomically(file, xml);
		} catch (IOException e) {
			e.printStackTrace();
			return describe(e, file);
		}
		//1ファイル形式のスタック(.hzb の ZIP)は作業フォルダに書いたものをファイルへ書き戻す(書けなければ未保存のまま)
		return StackBundle.sync(file);
	}

	/** An I/O failure in words the user can act on (a full disk, no permission), else the system's own message. */
	static String describe(IOException e, File file){
		String m = e.getMessage()==null ? "" : e.getMessage();
		String lower = m.toLowerCase(java.util.Locale.ROOT);
		if(lower.contains("no space") || lower.contains("disk full") || lower.contains("not enough space") || lower.contains("quota"))
			return "The disk is full: "+file.getPath();
		if(e instanceof java.nio.file.AccessDeniedException || lower.contains("permission denied") || lower.contains("access is denied") || lower.contains("read-only"))
			return "Writing is not allowed: "+file.getPath();
		return (m.isEmpty() ? e.getClass().getSimpleName() : m)+" ("+file.getPath()+")";
	}

	//いったん同じフォルダの一時ファイルへ書き切ってから本来の名前へ置き換える。
	//直接上書きしていると、書き込み途中でクラッシュ・強制終了したときに
	//_stack.xmlが途中までしか無い(壊れた)状態で残ってしまう。
	static void writeAtomically(File file, byte[] data) throws IOException {
		File dir = file.getAbsoluteFile().getParentFile();
		File temp = File.createTempFile(file.getName()+".", ".tmp", dir);
		try {
			try (FileOutputStream stream = new FileOutputStream(temp)) {
				stream.write(data);
				stream.getFD().sync();
			}
			try {
				Files.move(temp.toPath(), file.toPath(),
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			if(temp.exists()) temp.delete();
		}
	}
	
	boolean needsSave(){
		if(stack.changed) return true;
		ArrayList<OCard> cards = stack.cdCacheList;
		ArrayList<OBackground> bgs = stack.bgCacheList;
		if(cards==null || bgs==null) return false;
		for(int i=0 ;i<cards.size(); i++){
			if(cards.get(i).changed) return true;
		}
		for(int i=0 ;i<bgs.size(); i++){
			if(bgs.get(i).changed) return true;
		}
		return false;
	}

	class saveThread extends Thread{
		//暇なときは変更を監視
		public void run(){
			setName("saveXMLThread");
			setPriority(MIN_PRIORITY);
			
			if(!stack.file.exists()){
				//xmlファイルがないときは即作成(can't modify のスタックでも、変換直後の1回は書く)
				saveStackNow(true);
			}
			
			while(true){
				try{
					sleep(1000);//1秒1回
				} catch (InterruptedException e) {
			        //this.interrupt();
				}
				//if(isInterrupted()) break;
				if(PCARD.pc !=stack.pcard) break;
				if(stack.cdCacheList==null) break;
				
				if(TTalk.idle){
					try{
						//書けなかった後は少し間をあけて再挑戦する(容量不足のディスクへ毎秒書き直さない)
						if(needsSave() && (lastError==null || System.currentTimeMillis()-lastFailureAt>=RETRY_AFTER_FAILURE_MS)){
							//on idle のハンドラが動き続けている(ゲーム・アニメーション)間は、毎秒スタック全体を書き直さず
							//BUSY_SAVE_NANOS ごとにまとめる。ユーザーの編集(userEdited)と、止まったあとはすぐ書く
							long now = System.nanoTime();
							if(unsavedSince==0) unsavedSince = now;
							boolean busy = TTalk.idleHandlerAt!=0 && now-TTalk.idleHandlerAt < 2_000_000_000L;
							if(!busy || stack.userEdited || now-unsavedSince >= BUSY_SAVE_NANOS){
								unsavedSince = 0;
								if(!saveStackNow() && !failureReported) StackSaving.autosaveFailed(stack);
							}
						}
						else unsavedSince = 0;
					}catch(RuntimeException e){
						//書き出し中の例外(部品の整合性が崩れている等)でこの監視スレッド
						//自体が終了してしまうと、以後の変更が一切保存されなくなる。
						//ログだけ残して次回に再挑戦する。
						e.printStackTrace();
					}
				}
			}
		}
	}
}

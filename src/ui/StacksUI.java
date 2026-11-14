import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.nio.charset.Charset;
import java.io.*;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

/** Desktop code moved out of the core's OStack unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class StacksUI {
	private StacksUI(){}

	// from OStack (instance)
    static boolean openStackFileInThread(OStack self, String inpath, boolean isUsingOnly) {
    	
    	self.path = inpath;
		self.file = new File(self.path);
		if(!self.file.exists()){
			//start using a stack that is not there (Colorize and Pictoid Template: start using stack "Pictoid XCMD"): the
			//current stack stays as it is. failureOpenFile took the open card's parts away (a blank card) and said so.
			if(!isUsingOnly) PCARD.pc.failureOpenFile(inpath);
			return false;
		}
		//スクリプトからの go/open/start using: ユーザが開いた場所とHyperZebra HDの外なら許可を求める(Sandbox)
		if(!Sandbox.allowFile(self.file, Sandbox.Access.READ, Sandbox.scriptRequester())){
			self.name="";
			return false;
		}
		//1ファイル形式のスタック(.hzb の ZIP): 作業フォルダに展開して、従来の .hzb フォルダと同じように開く(StackBundle)
		if(StackBundle.isPacked(self.file)){
			try{
				self.file = StackBundle.open(self.file);
			}catch(IOException e){
				System.err.println("Could not open the stack file "+inpath+": "+e.getMessage());
				self.name="";
				if(!isUsingOnly) PCARD.pc.failureOpenFile(inpath);
				return false;
			}
			inpath = self.file.getPath();
			self.path = inpath;
		}
		if(!isUsingOnly && ZipExpander.isZip(self.file)){
			//zip書庫(Finderの「圧縮」やHyperZebraのポータブルパッケージ)はまず展開する。
			//__MACOSX以下のAppleDoubleは各ファイルの「._名前」サイドカー(macOSでは実フォーク)になる。
			File expanded = self.expandZipArchive(self.file);
			if(expanded==null){
				self.name="";
				return false; //展開だけ済ませたか、失敗をダイアログで伝えた
			}
			inpath = expanded.getPath();
			self.path = inpath;
			self.file = expanded;
		}
		if(!isUsingOnly && DiskImages.openFromUser(self.file)){
			self.name="";
			return false; //ディスクイメージはマウントした
		}
		if(!isUsingOnly && self.launch68kApplication(inpath)){
			self.name="";
			return false; //呼び出し側で元のスタックに戻す
		}

		self.scriptList = new ArrayList<String>();
		
		{
			String STAKstr = null;
			//先頭8バイトだけ見て閉じる(以前はストリームを閉じずに放置していた)
			try (FileInputStream fstream = new FileInputStream(self.path)) {
				//HCスタックとして読んでみる
				STAKstr = "";
				for(int i=0; i<4; i++){
					fstream.read();
				}
				for(int i=0; i<4; i++){
					STAKstr += (char)fstream.read();
				}
			} catch (FileNotFoundException e) {
			} catch (IOException e) {
				e.printStackTrace();
			}
			//a data fork that starts like a stack but did not convert: openHCStack has already pointed path at the conversion
			//(an .hzb folder that is not there), so the check below must not go by path (UbiQUE™ XFCN from a damaged archive got
			//"error reading the XML data… opened write-protected")
			boolean damagedStack = false;
			if(STAKstr != null){
				if(STAKstr.equals("STAK") || STAKstr.equals("pwpc")){ //"HCSApwpcpeff": PowerPC 版 HyperCard がスタックの前に付けるコードフラグメント(HCData.stackOffset)
					//これはきっとHyperCardスタックファイル
					final File rawStack = new File(self.path); //openHCStack points path at the conversion
					if(true == HCConvert.openHCStack(self.path, self)){
						if(self.cdCacheList.size()>0){
							self.standaloneApplication = MacApp68k.isStandaloneStack(rawStack);
							self.finishOpen(isUsingOnly);
							return true;
						}
						damagedStack = true;
					}
					else if(HCConvert.cancelled){
						//文字コードの選択をキャンセル: 黙って戻る(以前はXMLとして読みに行って「読み込みに失敗」を出していた)
						self.name="";
						return false;
					}
					else damagedStack = true;
				}
				else if(true/*path.substring(path.length()-4).equals(".bin")*/){
					//MacBinary形式では？
					if(true == HCConvert.openMacBinaryStack(self.path, self)){
						if(self.cdCacheList.size()>0){
							self.finishOpen(isUsingOnly);
							return true;
						}
					}
					else if(HCConvert.cancelled){
						self.name="";
						return false;
					}
				}
			}
			//Finder の種類が STAK なのにスタックとして読めなかったファイルは壊れたスタック: XML として読みに行くと
			//「XMLデータの読み込み中にエラー…書き込み禁止で開きます」になるので、開けなかったことだけを伝える
			if(damagedStack || (new File(self.path).isFile() && self.stackFileType(self.path))){
				System.err.println("damaged HyperCard stack (the data fork is not a stack): "+inpath);
			}
			//XML形式として読む
			else if(System.getProperty("java.version").startsWith("1.5")){
				new GDialog(PCARD.pc, HZ.intl().getDialogText("This version of Java Runtime is not supported streaming API for XML."),
						null,"OK",null,null);
			}
			else{
				if(new File(self.path).isDirectory()){
					self.path += File.separatorChar+"_stack.xml";
					if(!new File(self.path).exists()){
						self.path = inpath + File.separatorChar+"toc.xml";
					}
				}
				
				if(XMLRead.readToc(self.path, self)){
					//Private Access: パスワードを聞く(封印されたXMLなら読み込み時に聞き済み)
					if(!StackProtection.unlockOnOpen(self)){
						self.name="";
						return false;
					}
					if(self.cdCacheList.size()>0){
						self.file = new File(self.path);
						self.finishOpen(isUsingOnly);
						return true;
					}
				}
				else if(XMLRead.lockedOut){
					self.name="";
					return false;
				}
			}
		}

		self.name="";
		if(!isUsingOnly) PCARD.pc.failureOpenFile(inpath);
		return false;
    }

	// from OStack (instance)
	/** Lines of OStack.buildStackFile (the core calls this in their place). */
	static void openStackWindow(OStack self, boolean isUsingOnly) {
		if(HZ.home!=null){
			self.usingStacks.add(HZ.home);
		}
		
		
		if(PCARD.pc.toolbar==null){
			PCARD.pc.toolbar = new GToolBar(PCARD.pc);
		}
		
		String sepStr = ""+File.separatorChar;
		if(File.separatorChar=='\\'){
			sepStr = "\\\\";
		}
    	String[] strArray = self.path.split(sepStr);
		//name=strArray[strArray.length-1];
    	if(strArray.length-2>=0){
    		//HyperCard のスタック名はファイル名。展開形式のフォルダ "X.hzb" のスタックは "X"(the short name of this stack、
    		//ウインドウ名。以前は "HyperPiece 2.hzb" になり、HyperPiece 2 のパス表示やスタック名の比較が合わなかった)
    		self.name=strArray[strArray.length-2].replaceFirst("(?i)\\.hzb$", "");
    	}else{
    		self.name=self.path;
    	}
		//the host may keep the name decomposed (an unpacked "はいぱあれもん" has ぱ as は + U+309A): a script compares it with
		//what it typed, which is composed (the short name of this stack is "はいぱあれもん vol.4" was false)
		self.name = Platform.get().normalize(self.macNameOfHostName(self.name, self.legacyTextEncoding), UnicodeNorm.Form.NFC);
		self.pcard.setTitle(self.getTitleName());

		//2倍表示モードでこのスタックのウインドウが画面に収まるかどうかを判定しておく
		//(以降のPCARD.scale()の結果に反映される)
		PCARD.updateZoomFitsScreen(self.width, self.height);

		int scaledW = Host.get().toPhysical(self.width);
		int scaledH = Host.get().toPhysical(self.height);
		self.mainPane.setBounds(self.left, self.top, scaledW, scaledH+self.pcard.getInsets().top);
		self.mainPane.setVisible(true);
		Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
		if(d!=null){
			self.pcard.setBounds(d.width/2-scaledW/2, d.height/2-scaledH/2-20, scaledW, scaledH+20);
		}else{
			self.pcard.setBounds(self.left, self.top-scaledH-20, scaledW, scaledH+20);
		}
		self.pcard.setVisible(true);
		//pcard.setLocationRelativeTo(null);
		self.pcard.setResizable(self.resizable); 
		//HyperCard J-2.3 and 2.4 open a stack with the card window as big as the card (on the screen) and not scrolled, whatever
		//window rect and scroll the header saved (measured with script-less stacks; RDS experiments sets its 192x160 window and
		//scroll in its own openCard). Shattered Stone saved a scroll of 64,0 and opened cut off at the left.
		self.windowNominal = null;
		if(self.scroll!=null) self.scroll = new Point(0, 0);
		self.setNewBounds();
		
		PCARD.pc.msg.setBounds(PCARD.pc.getX()+PCARD.pc.getWidth()/2-240,PCARD.pc.getY()+PCARD.pc.getHeight(),480,28+PCARD.pc.getInsets().top);

		
		//1番目のカードを開く
		if(self.cardIdList.size()>=1){
			self.mainPane.removeAll();
			try {
				self.curCard = OCard.getOCard(self, self.cardIdList.get(0), false);
				self.curCard.bg = OBackground.getOBackground(self, self.curCard, self.curCard.bgid, false);
				self.curCard.parent = self.curCard.bg;
			} catch (xTalkException e) {
				e.printStackTrace();
				self.curCard = self.cdCacheList.get(0);
				try {
					self.curCard.bg = OBackground.getOBackground(self, self.curCard, self.curCard.bgid, false);
					self.curCard.parent = self.curCard.bg;
				} catch (xTalkException e2) {
					e2.printStackTrace();
				}
			}

	    	HZ.lockedScreen = false;
			self.mainPane.repaint();
			//「リーセント」メニュー用に、開いた直後のカードも履歴に記録しておく
			self.recordRecentCard(self.curCard);
			//TTalk.talk = new TTalk();
			//TTalk.talk.start();
			TTalk.CallMessage("startUp",self.curCard);
			TTalk.CallMessage("openStack",self.curCard);
			TTalk.CallMessage("openBackground",self.curCard);
			TTalk.CallMessage("openCard",self.curCard);
		}
		
		//変更監視&XML保存のスレッドを立ち上げておく
		if(!System.getProperty("java.version").startsWith("1.5")){ //JRE1.5.xは非対応
			self.saveXML = new XMLwrite(self);
		}
	}
}

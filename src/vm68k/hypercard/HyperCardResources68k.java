/**
 * The resources of the HyperCard application that externals borrow, as a resource file searched after the stacks and
 * before the System file (HyperCard's own place in the chain: current stack, stacks in use, Home, HyperCard, System).
 *
 * UxAnswer (M.J.DASH's 筋肉開始) loads DLOG 135, HyperCard's answer dialog, with GetNewDialog and fills in its buttons
 * and ^0; without it the external returned at once and the button seemed dead. The item numbers and rectangles follow
 * HyperCard 2.2/J-2.3/2.4 (the same in all three) so externals that change items by number keep working; the bytes are
 * HyperZebra's own.
 *
 * Rinaldi's externals (Oct 2026) borrow more of them: the Menu XCMD reads HyperCard's File menu (MENU 2) for its international
 * title and Quit item before it does anything, SelectFile shows HyperCard's "Get File" dialog (DLOG/DITL 138, the standard
 * SFGetFile layout with the ^0 prompt as item 11), and XRef reads WTLK "Strings", HyperTalk's vocabulary as Pascal strings, to
 * tell built-in words from variables and handlers. The menus are MENU 1-14 and 21-35 (Japanese titles and items for a
 * Japanese stack, as in HC J-2.3) and MENU 101-114, HyperCard's English set with the menu IDs 1-14; separators are named "-1",
 * "-2" … as in HyperCard. The item names, menu IDs and dialog rectangles follow HyperCard 2.4 and J-2.3; the bytes are built here.
 */
final class HyperCardResources68k {
	private HyperCardResources68k(){}

	static final int ASK_DIALOG=134,ANSWER_DIALOG=135,GET_FILE_DIALOG=138;
	private static final int USER_ITEM=0,BTN_CTRL=4,STAT_TEXT=8,EDIT_TEXT=16,ITEM_DISABLE=128;

	static ResourceFile68k build(StackTextEncoding encoding){
		ResourceFile68k f=new ResourceFile68k(null);
		f.writable=false;
		byte[] ok={'O','K'},cancel=encoding==StackTextEncoding.JAPANESE?encoding.encodeReplacing("キャンセル"):new byte[]{'C','a','n','c','e','l'};
		byte[] param0={'^','0'};
		// answer: three buttons along the bottom (1 = the default at the right), the prompt above
		java.io.ByteArrayOutputStream answer=new java.io.ByteArrayOutputStream();
		answer.write(0);answer.write(3);
		item(answer,new int[]{43,223,63,314},BTN_CTRL,ok);
		item(answer,new int[]{43,112,63,203},BTN_CTRL,new byte[0]);
		item(answer,new int[]{43,6,63,97},BTN_CTRL,new byte[0]);
		item(answer,new int[]{11,9,29,314},STAT_TEXT|ITEM_DISABLE,param0);
		add(f,ANSWER_DIALOG,"Answer",new int[]{100,86,174,414},answer.toByteArray());
		// ask: OK and Cancel, the prompt and the reply field
		java.io.ByteArrayOutputStream ask=new java.io.ByteArrayOutputStream();
		ask.write(0);ask.write(3);
		item(ask,new int[]{60,130,80,210},BTN_CTRL,ok);
		item(ask,new int[]{60,232,80,312},BTN_CTRL,cancel);
		item(ask,new int[]{6,8,24,312},STAT_TEXT|ITEM_DISABLE,param0);
		item(ask,new int[]{31,10,47,309},EDIT_TEXT,new byte[0]);
		add(f,ASK_DIALOG,"Ask",new int[]{108,96,198,418},ask.toByteArray());
		// Get File: the standard file dialog (Open, a hidden button, Cancel, the folder pop-up, Eject, Drive, the list and its
		// scroll bar, a line, a hidden picture item) with the prompt ^0 above the list
		boolean japanese=encoding==StackTextEncoding.JAPANESE;
		java.io.ByteArrayOutputStream get=new java.io.ByteArrayOutputStream();
		get.write(0);get.write(10);
		item(get,new int[]{145,257,163,337},BTN_CTRL,text(encoding,japanese?"開く":"Open"));
		item(get,new int[]{2,622,67,642},BTN_CTRL,text(encoding,"Hidden"));
		item(get,new int[]{170,256,188,336},BTN_CTRL,cancel);
		item(get,new int[]{48,232,68,347},USER_ITEM|ITEM_DISABLE,new byte[0]);
		item(get,new int[]{74,256,92,336},BTN_CTRL,text(encoding,japanese?"取り出し":"Eject"));
		item(get,new int[]{100,256,118,336},BTN_CTRL,text(encoding,japanese?"ドライブ":"Drive"));
		item(get,new int[]{48,13,194,231},USER_ITEM,new byte[0]);
		item(get,new int[]{48,230,194,247},USER_ITEM,new byte[0]);
		item(get,new int[]{129,252,130,340},USER_ITEM|ITEM_DISABLE,new byte[0]);
		item(get,new int[]{1,536,85,601},STAT_TEXT,new byte[0]);
		item(get,new int[]{4,13,22,336},STAT_TEXT|ITEM_DISABLE,param0);
		add(f,GET_FILE_DIALOG,"Get File",new int[]{0,0,200,348},get.toByteArray());
		for(String[] m:japanese?JAPANESE_MENUS:MENUS)menu(f,encoding,m,0);
		for(String[] m:MENUS){int id=Integer.parseInt(m[0].substring(0,m[0].indexOf('	')));if(id<=14)menu(f,encoding,m,100);}
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("WTLK"),1,"Strings",0x20,vocabulary()));
		byte[] patterns=paintPatterns();
		if(patterns!=null)f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("PAT#"),128,"HyperPats",0x20,patterns));
		for(String[] s:SOUNDS){java.io.File audio=new java.io.File("resource",s[2]);
			if(!audio.isFile())continue;
			ResourceFile68k.Resource sound=new ResourceFile68k.Resource(ResourceFile68k.fourcc("snd "),Integer.parseInt(s[0]),s[1],audio,0);
			sound.attributes=0x20;f.add(sound);}
		// the application's owner resource: Drazga's NewMenu/ShowMenu (DevStack) take 'WILD' 0 as the place to keep their menu
		// handles (they mark it $FFFF and grow it) and did nothing without one
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("WILD"),0,"",0x20,ownerString()));
		// the Finder bundle: Rinaldi's 2.8+ externals (Align, FullSort …) ask GetResource('BNDL',128) whether they run in HyperCard
		// (owner 'WILD') or the Player and only then use `the itemDelimiter`; without it a ";" list stayed one column
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("BNDL"),128,"",0x20,bundle()));
		String[] documents={"APPL","STAK","STRN","BTSK"}; // the file types HyperCard's FREF 128-131 name, local IDs 0-3
		for(int i=0;i<documents.length;i++){byte[] fref=new byte[7];System.arraycopy(documents[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,fref,0,4);fref[5]=(byte)i;
			f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("FREF"),128+i,"",0x20,fref));}
		for(Object[] c:CURSORS)f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CURS"),(Integer)c[0],(String)c[1],0x20,cursor((String[])c[4],(Integer)c[2],(Integer)c[3])));
		return f;
	}

	/** 'BNDL' 128 in the layout the Finder reads: owner 'WILD' id 0, then the types it maps (FREF and ICN#, local IDs 0-3 to
	 *  resource IDs 128-131, as HyperCard's bundle has them). Built here, not copied. */
	static byte[] bundle(){
		java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(8+2*(6+4*4));
		b.putInt(ResourceFile68k.fourcc("WILD")).putShort((short)0).putShort((short)1); // owner, its ID, number of types - 1
		for(String type:new String[]{"FREF","ICN#"}){b.putInt(ResourceFile68k.fourcc(type)).putShort((short)3);for(int i=0;i<4;i++)b.putShort((short)i).putShort((short)(128+i));}
		return b.array();
	}

	/** 'WILD' 0: a Pascal string naming the program, as HyperCard's owner resource holds its version line (the text is HyperZebra's). */
	static byte[] ownerString(){
		byte[] text=("HyperZebra (HyperCard 2.4 compatible)").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();pascal(out,text);return out.toByteArray();
	}

	/**
	 * HyperCard's own cursors, by the IDs and names HyperCard 2.2/2.4/J-2.3 give them (CURS 128 "Hand", 129 "Checkpoint", 130
	 * "HBarMover", 131 "VBarMover"): LockCursor's `lockCursor "hand"` looks the hand up by name in the HyperCard file ("Couldn't
	 * find cursor by that name!" without it). The pictures are HyperZebra's own drawings: '#' black, 'o' white, '.' clear.
	 * {id, name, hot spot v, hot spot h, rows}
	 */
	private static final Object[][] CURSORS={
		{128,"Hand",0,5,new String[]{
			".....##.........",
			"....#oo#........",
			"....#oo#........",
			"....#oo#........",
			"....#oo######...",
			"....#oo#o#o#o#..",
			".##.#ooooooooo#.",
			"#oo##ooooooooo#.",
			"#ooo#ooooooooo#.",
			".#oooooooooooo#.",
			"..#ooooooooooo#.",
			"..#oooooooooo#..",
			"...#ooooooooo#..",
			"....#oooooooo#..",
			".....#ooooooo#..",
			".....#########.."}},
		{129,"Checkpoint",0,0,new String[]{
			"##..............",
			"#o##............",
			"#ooo###.........",
			"#oooooo##.......",
			"#oooooooo##.....",
			"#oooooo##.......",
			"#ooo###.........",
			"#o##............",
			"##..............",
			"#...............",
			"#...............",
			"#...............",
			"#...............",
			"#...............",
			"###.............",
			"................"}},
		{130,"HBarMover",7,7,barMover()},
		{131,"VBarMover",7,7,transposed(barMover())},
	};
	/** A bar with an arrow either side of it, pointing away (HBarMover: arrows up and down). */
	private static String[] barMover(){
		return new String[]{
			".......#........",
			"......###.......",
			".....#####......",
			"....#######.....",
			".......#........",
			".......#........",
			"###############.",
			"................",
			"###############.",
			".......#........",
			".......#........",
			"....#######.....",
			".....#####......",
			"......###.......",
			".......#........",
			"................"};
	}
	private static String[] transposed(String[] rows){
		String[] out=new String[16];
		for(int x=0;x<16;x++){StringBuilder b=new StringBuilder();for(int y=0;y<16;y++)b.append(rows[y].charAt(x));out[x]=b.toString();}
		return out;
	}
	/** A 'CURS' (data, mask, hot spot v,h) from 16 rows of '#' (black), 'o' (white) and '.' (clear). */
	static byte[] cursor(String[] rows,int hotV,int hotH){
		byte[] c=new byte[68];
		for(int y=0;y<16;y++)for(int x=0;x<16;x++){char p=rows[y].charAt(x);int bit=0x80>>>(x&7),at=y*2+(x>>3);
			if(p=='#')c[at]|=(byte)bit;
			if(p=='#'||p=='o')c[32+at]|=(byte)bit;}
		c[64]=(byte)(hotV>>8);c[65]=(byte)hotV;c[66]=(byte)(hotH>>8);c[67]=(byte)hotH;
		return c;
	}

	/**
	 * HyperCard's own sampled sounds (ID, name, HyperZebra's copy of the samples in resource/): PgSoundX loads "Harpsichord" and
	 * "Boing" by name onto its channels and plays notes from their base notes (67, 74). Made into 'snd ' resources when read
	 * (ResourceFile68k.sndBits). HyperZebra's Home keeps the same three as SND 128/129/146 and comes first in the chain.
	 */
	private static final String[][] SOUNDS={{"128","Harpsichord","harpsichord.wav"},{"129","Boing","Boing.wav"},{"146","flute","flute.wav"}};

	/**
	 * PAT# 128 "HyperPats", HyperCard's 40 paint patterns, from HyperZebra's own pattern pictures (resource/PAT_1..40.png, the
	 * Patterns palette's): xDraw's "pen pattern,128,n" takes them with GetIndPattern and painted with an uninitialised pattern
	 * into the card picture without it. Null when the pictures are not there.
	 */
	private static byte[] paintPatterns(){
		byte[] cached=patternsCache;if(cached!=null)return cached.length==0?null:cached;
		byte[] built=readPaintPatterns();patternsCache=built==null?new byte[0]:built; // read once: an external's VM is made per call
		return built;
	}
	private static volatile byte[] patternsCache;
	private static byte[] readPaintPatterns(){
		byte[] out=new byte[2+40*8];out[1]=40;
		for(int i=0;i<40;i++){
			java.io.File file=new java.io.File("resource",("PAT_"+(i+1)+".png"));
			java.awt.image.BufferedImage image;
			try{image=file.isFile()?javax.imageio.ImageIO.read(file):null;}catch(java.io.IOException e){image=null;}
			if(image==null||image.getWidth()<8||image.getHeight()<8)return null;
			for(int y=0;y<8;y++){int bits=0;
				for(int x=0;x<8;x++){int argb=image.getRGB(x,y);int lum=((argb>>16&0xff)*30+(argb>>8&0xff)*59+(argb&0xff)*11)/100;if((argb>>>24)>=0x80&&lum<0x80)bits|=0x80>>x;}
				out[2+i*8+y]=(byte)bits;}
		}
		return out;
	}

	/** {"id	resource name", title, items…}; an item is "text[	command key[	style]]". English (HyperCard 2.4). */
	private static final String[][] MENUS={
		{"1\tApple","\u0014","About HyperCard...","-"},
		{"2\tFile","File","New Stack...","Open Stack...\tO","Close Stack\tW","Save a Copy...","-1","Compact Stack","Protect Stack...","Delete Stack...","-2","Page Setup...","Print Field...","Print Card\tP","Print Stack...","Print Report...","-3","Quit HyperCard\tQ"},
		{"3\tEdit","Edit","Undo\tZ","-1","Cut\tX","Copy\tC","Paste\tV","Clear","-2","New Card\tN","Delete Card","Cut Card","Copy Card","-3","Text Style...\tT","Background\tB","Icon...\tI"},
		{"4\tGo","Go","Back\t~","Home\tH","Help\t?","Recent\tR","-1","First\t1","Prev\t2","Next\t3","Last\t4","-2","Find...\tF","Message\tM","Scroll\tE","Next Window\tL"},
		{"5\tTools","Tools",},
		{"6\tObjects","Objects","Button Info...","Field Info...","Card Info...","Bkgnd Info...","Stack Info...","-1","Bring Closer\t+","Send Farther\t-","-2","New Button","New Field","New Background"},
		{"7\tFont","Font",},
		{"8\tStyle","Style","Plain","Bold\t\t1","Italic\t\t2","Underline\t\t4","Outline\t\t8","Shadow\t\t16","Condense\t\t32","Extend\t\t64","Group","-1","  9","10","12","14","18","24","-2","Other..."},
		{"9\tPaint","Paint","Select\tS","Select All\tA","-1","Fill","Invert","Pickup","Darken","Lighten","Trace Edges","Rotate Left","Rotate Right","Flip Vertical","Flip Horizontal","-2","Opaque","Transparent","-3","Keep\tK","Revert"},
		{"10\tOptions","Options","Grid","FatBits","Power Keys","-1","Line Size...","Brush Shape...","Edit Pattern...","Polygon Sides...","-2","Draw Filled","Draw Centered","Draw Multiple","-3","Rotate","Slant","Distort","Perspective"},
		{"11\tPatterns","Patterns",},
		{"12\tShortFile","File","New Stack...","Open Stack...\tO","Close Stack\tW","Save a Copy...","-1","Compact Stack","-2","Page Setup...","Print Field...","Print Card\tP","Print Stack...","Print Report...","-3","Quit HyperCard\tQ"},
		{"13\tShortEdit","Edit","Undo\tZ","-1","Cut\tX","Copy\tC","Paste\tV","Clear","-2","New Card\tN","Delete Card"},
		{"14\tPaintFile","File","New Stack...","Open Stack...\tO","Close Stack\tW","Save a Copy...","-1","Import Paint...","Export Paint...","-4","Compact Stack","Protect Stack...","Delete Stack...","-2","Page Setup...","Print Field...","Print Card\tP","Print Stack...","Print Report...","-3","Quit HyperCard\tQ"},
		{"21\tIconFile","File","New Icon\tN","Close Icon Editor\tW","Duplicate Icon\tD","-1","Quit HyperCard\tQ"},
		{"22\tIconEdit","Edit","Undo\tZ","-1","Cut Icon\tX","Copy Icon\tC","Paste\tV","Clear Icon","-2","New Button"},
		{"23\tIcon","Icon","Erase","Pickup\tP","Keep\tK","Revert","-1","First\t1","Prev\t2","Next\t3","Last\t4","-2","Find...\tF"},
		{"24\tIconSpecial","Special","Flip Horizontal","Flip Vertical","Frame","Gray","Invert","Mirror Horizontal","Mirror Vertical","Rotate 90°","Shadow"},
		{"25\tReportEdit","Edit","Undo\tZ","-","Cut\tX","Copy\tC","Paste\tV","Clear","-","Delete Report","Cut Report","Copy Report","-","Report Items...\tE","Report Name..."},
		{"26\tReports","Reports","New Report\tN","-"},
		{"27\tItems","Items","New Item\tN","Item Info...\tI"},
		{"28\tScriptFile","File","Close Script\tW","Save Script\tS","Revert to Saved","Print Script\tP","-","Quit HyperCard\tQ"},
		{"29\tScriptEdit","Edit","Undo\tZ","-","Cut\tX","Copy\tC","Paste\tV","Clear","-","Select All\tA"},
		{"30\tScriptGo","Go","-","Next Window\tL"},
		{"31\tScript","Script","Find...\tF","Find Again\tG","Enter 'Find' String\tE","Find Selection\tH","Scroll to Selection","-","Replace...\tR","Replace Again\tT","-","Comment\t-","Uncomment\t=","-","Check Syntax\tK","-","Set Checkpoint\tD"},
		{"32\tDebugger","Debugger","Step\tS","Step Into\tI","Trace","Trace Into\tT","Go\tG","-","Trace Delay...","Set Checkpoint\tD","Abort\tA","-","Variable Watcher","Message Watcher"},
		{"33\tScripting Language","Scripting Language","HyperTalk"},
		{"34\tHandlers","Handlers","HyperTalk Handlers"},
		{"35\tFunctions","Functions","HyperTalk Functions"},
	};
	/** The same for a Japanese stack (HyperCard J-2.3: half-width katakana titles). */
	private static final String[][] JAPANESE_MENUS={
		{"1\tApple","\u0014","HyperCardについて...","-"},
		{"2\tFile","ﾌｧｲﾙ","新規スタック...","スタックを開く...\tO","スタックを閉じる\tW","バックアップコピー保存...","-1","スタック整理","スタック保護...","スタック削除...","-2","用紙設定...","フィールドプリント...","カードプリント\tP","スタックプリント...","リポートプリント...","-3","HyperCard終了\tQ"},
		{"3\tEdit","編集","取り消し\tZ","-1","カット\tX","コピー\tC","ペースト\tV","消去","-2","新規カード\tN","カード削除","カットカード","コピーカード","-3","テキストスタイル...\tT","バックグラウンド\tB","アイコン編集...\tI"},
		{"4\tGo","ｺﾞｰ","バック\t~","ホーム\tH","ヘルプ\t?","リーセント\tR","-1","最初のカード\t1","前のカード\t2","次のカード\t3","最後のカード\t4","-2","検索...\tF","メッセージ\tM","スクロール\tE","次のウインドウ\tL"},
		{"5\tTools","ﾂｰﾙ",},
		{"6\tObjects","ｵﾌﾞｼﾞｪｸﾄ","ボタン情報...","フィールド情報...","カード情報...","バックグラウンド情報...","スタック情報...","-1","前面へ出す\t+","背面へ送る\t-","-2","新規ボタン","新規フィールド","新規バックグラウンド"},
		{"7\tFont","ﾌｫﾝﾄ",},
		{"8\tStyle","ｽﾀｲﾙ","標準","ボールド\t\t1","イタリック\t\t2","アンダーライン\t\t4","アウトライン\t\t8","シャドウ\t\t16","字間せまく\t\t32","字間広く\t\t64","グループ","-1","  9","10","12","14","18","24","-2","その他..."},
		{"9\tPaint","ﾍﾟｲﾝﾄ","選択\tS","すべてを選択\tA","-1","塗りつぶし","パターン反転","切り取り","濃くする","薄くする","縁どり","左回転","右回転","縦反転","横反転","-2","不透明","透明","-3","作業の保存\tK","復帰"},
		{"10\tOptions","ｵﾌﾟｼｮﾝ","グリッド","ビット表示","パワーキー","-1","ラインの太さ...","ブラシの種類...","パターンの編集...","多角形の種類...","-2","塗りつぶして描く","中心から描く","重ねて描く","-3","回す","傾ける","ゆがめる","奥ゆきをつける"},
		{"11\tPatterns","ﾊﾟﾀｰﾝ",},
		{"12\tShortFile","ﾌｧｲﾙ","新規スタック...","スタックを開く...\tO","スタックを閉じる\tW","バックアップコピー保存...","-1","スタック整理","-2","用紙設定...","フィールドプリント...","カードプリント\tP","スタックプリント...","リポートプリント...","-3","HyperCard終了\tQ"},
		{"13\tShortEdit","編集","取り消し\tZ","-1","カット\tX","コピー\tC","ペースト\tV","消去","-2","新規カード\tN","カード削除"},
		{"14\tPaintFile","ﾌｧｲﾙ","新規スタック...","スタックを開く...\tO","スタックを閉じる\tW","バックアップコピー保存...","-1","ペイントデータ読み込み...","ペイントデータ書き出し...","-4","スタック整理","スタック保護...","スタック削除...","-2","用紙設定...","フィールドプリント...","カードプリント\tP","スタックプリント...","リポートプリント...","-3","HyperCard終了\tQ"},
		{"21\tIconFile","ﾌｧｲﾙ","新規アイコン\tN","アイコン編集を閉じる\tW","アイコン複製\tD","-1","HyperCard終了\tQ"},
		{"22\tIconEdit","編集","取り消し\tZ","-1","カットアイコン\tX","コピーアイコン\tC","ペースト\tV","消去アイコン","-2","新規ボタン"},
		{"23\tIcon","アイコン","消す","切り取り\tP","作業の保存\tK","復帰","-1","最初のアイコン\t1","前のアイコン\t2","次のアイコン\t3","最後のアイコン\t4","-2","検索...\tF"},
		{"24\tIconSpecial","特殊機能","横反転","縦反転","枠づけ","グレイ","パターン反転","左右対称","上下対称","直角に回す","影をつける"},
		{"25\tReportEdit","編集","取り消し\tZ","-","カット\tX","コピー\tC","ペースト\tV","消去","-","削除リポート設計","カットリポート設計","コピーリポート設計","-","リポート項目...\tE","リポート設計の名前..."},
		{"26\tReports","リポート","新規リポート設計\tN","-"},
		{"27\tItems","リポート項目","新規項目\tN","項目情報...\tI"},
		{"28\tScriptFile","ﾌｧｲﾙ","スクリプトを閉じる\tW","スクリプトを保存\tS","スクリプトを復帰","スクリプトプリント\tP","-","HyperCard終了\tQ"},
		{"29\tScriptEdit","編集","取り消し\tZ","-","カット\tX","コピー\tC","ペースト\tV","消去","-","すべてを選択\tA"},
		{"30\tScriptGo","ｺﾞｰ","-","次のウインドウ\tL"},
		{"31\tScript","ｽｸﾘﾌﾟﾄ","検索...\tF","次を検索\tG","選択文字列を検索\tH","選択文字列を表示","-","置き換え...\tR","次を置き換え\tT","-","注釈に変える\t-","注釈をはずす\t=","-","構文を確認\tK","-","チェックをつける\tD"},
		{"32\tDebugger","Debugger","ステップ実行\tS","詳しくステップ実行\tI","トレース","詳しくトレース\tT","実行\tG","-","トレースの速さ...","チェックをつける\tD","デバッガ終了\tA","-","変数表示","メッセージ表示"},
		{"33\tScripting Language","スクリプティング言語","HyperTalk"},
	};

	/** A MENU (menuID, width and height 0, the standard MDEF, enableFlags, title, items with icon/key/mark/style) under ID {@code id+offset}. */
	private static void menu(ResourceFile68k f,StackTextEncoding encoding,String[] m,int offset){
		int tab=m[0].indexOf('	'),id=Integer.parseInt(m[0].substring(0,tab));
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		int enable=1;
		for(int i=2;i<m.length&&i-1<=31;i++)if(!m[i].startsWith("-"))enable|=1<<(i-1);
		if(m.length-2>31)enable|=0x80000000; // items past 31 are always enabled
		out.write(id>>8);out.write(id);for(int i=0;i<8;i++)out.write(0);
		out.write(enable>>>24);out.write(enable>>>16);out.write(enable>>>8);out.write(enable);
		pascal(out,text(encoding,m[1]));
		for(int i=2;i<m.length;i++){
			String[] p=m[i].split("	",-1);
			pascal(out,text(encoding,p[0]));
			out.write(0);out.write(p.length>1&&!p[1].isEmpty()?p[1].charAt(0):0);out.write(0);out.write(p.length>2?Integer.parseInt(p[2]):0);
		}
		out.write(0);
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("MENU"),id+offset,m[0].substring(tab+1),0x20,out.toByteArray()));
	}

	/** HyperTalk's words (commands, functions, properties, messages, constants and the keywords between them). */
	private static final String VOCABULARY=
		"do else end exit function global if next on pass repeat return send then before after into char character word item "+
		"displayLine line activate add answer appleEvent arrowKey ask beep choose click close commandKeyDown controlkey "+
		"convert copy create debug delete dial disable divide doMenu drag edit enable enterInField enterkey errorDialog export "+
		"find functionkey get go help hide import keyDown lock mark multiply open pause play pop print push put read reply "+
		"request reset returnInField returnkey rewind run save select set show sort speak start stop subtract tabkey type "+
		"unlock unmark visual wait write abs annuity atan average charToNum clickChunk clickH clickLine clickLoc clickText "+
		"clickV commandKey cmdKey compound cos date destination diskSpace exp exp1 exp2 foundChunk foundField foundLine "+
		"foundText heapSpace length ln ln1 log2 max menus min mouse mouseClick mouseH mouseLoc mouseV number numToChar "+
		"numToHex offset optionKey param paramCount params programs random result round screenRect seconds secs selectedButton "+
		"selectedChunk selectedField selectedLine selectedLines selectedLoc selectedText serialPorts shiftKey sin sound speech "+
		"sqrt stacks stackSpace sum systemVersion tan target ticks time tool trunc value version voices windows and contains "+
		"div is mod or long short abbrev abbr abbreviated english last mid middle any first second third fourth fifth sixth "+
		"seventh eighth ninth tenth button btn field fld part track tracks QuickTime video audio text VR sprite MPEG music "+
		"MIDI chapter this prev previous down empty false formFeed lineFeed pi quote space tab comma colon true up zero one "+
		"two three four five six seven eight nine ten bg bkgnd background card cd in not of within the selection message msg "+
		"me menu menuItem a an no there window clipboard movie scene node hotspot spright object picture pict poly polygon reg "+
		"regular chars characters bgs bkgnds backgrounds cards cds fields flds btns buttons gray grey center centre "+
		"closeBackground closeCard closeField closeStack deleteBackground deleteButton deleteCard deleteField deleteStack "+
		"exitField idle mouseDown mouseEnter mouseLeave mouseStillDown mouseUp mouseWithin mouseDoubleClick newBackground "+
		"newButton newCard newField newStack openBackground openCard openField openStack quit resume resumeStack startup "+
		"suspend suspendStack moveWindow sizeWindow userLevel powerKeys grid lineSize filled centered multiple multiSpace "+
		"pattern brush polySides editBkgnd language numberFormat lockScreen lockRecent cursor dragSpeed name id style rect "+
		"rectangle loc location top left bottom right width height topLeft botRight bottomRight textAlign textFont textSize "+
		"textStyle textHeight size icon showName lockText showLines wideMargins scroll visible freeSize script hilite highlite "+
		"hilight highlight autoHilite blindTyping longWindowTitles lockMessages textArrows showPict cantModify cantDelete "+
		"autoTab userModify scriptEditor variableWatcher messageWatcher debugger traceDelay stacksInUse marked enabled "+
		"checkMark markChar menuMessage menuMsg cmdChar commandChar sharedText fixedLineHeight sharedHilite cantAbort cantPeek "+
		"reportTemplates dontSearch dontWrap suspended scriptTextFont scriptTextSize printTextFont printTextStyle "+
		"printTextSize printTextAlign printTextHeight printMargins lockErrorDialogs address itemDelimiter owner environment "+
		"partNumber family scriptingLanguage titleWidth multipleLines zoomed autoSelect soundChannel dialingTime dialingVolume "+
		"dialingPort plain bold italic underline outline shadow condense extend group dateItems box parts words lines items "+
		"displayLines cut zoom out dissolve venetian blinds iris checkerboard wipe barn door stretch from shrink to slow "+
		"slowly very fast black white inverse recent back forth whole string normal stack home international all where by "+
		"finding hypercard fatbits variable watcher for times tick sec until while transparent opaque roundrect checkBox "+
		"radioButton scrolling standard default oval popup program controller sprites sprights hotspots objects nodes scenes "+
		"movies pictures menuBar file folder disk application document ascending descending numeric dateTime as forever with "+
		"without password clear browse lasso pencil eraser spray can bucket curve at printing template new modem dialog paint "+
		"titleBar groups messages error dialogs screen URL report tempo menuMessages menuMsgs eof keyword ae class sender data "+
		"voice neuter robotic male female using effect menuItems machine AppleScript Manager Mac OS ";
	/** WTLK "Strings": a count, then the words as Pascal strings. */
	private static byte[] vocabulary(){
		String[] words=VOCABULARY.trim().split(" ");
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		out.write(words.length>>8);out.write(words.length);
		for(String w:words)pascal(out,w.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		return out.toByteArray();
	}
	private static byte[] text(StackTextEncoding encoding,String s){return encoding.encodeReplacing(s);}
	private static void pascal(java.io.ByteArrayOutputStream out,byte[] b){int n=Math.min(255,b.length);out.write(n);out.write(b,0,n);}

	/**
	 * A DLOG (dBoxProc, invisible, no go-away box, refCon 0, empty title, no auto-position) and its DITL under the same ID and
	 * HyperCard's name for them: externals of the HyperCard 1.x days find the dialog by name (Dartmouth's Password XFCN does
	 * GetNamedResource('DLOG', 'Ask') and returned "Error -192" without one).
	 */
	private static void add(ResourceFile68k f,int id,String name,int[] bounds,byte[] items){
		byte[] dlog=new byte[24];
		for(int i=0;i<4;i++){dlog[2*i]=(byte)(bounds[i]>>8);dlog[2*i+1]=(byte)bounds[i];}
		dlog[9]=1;dlog[18]=(byte)(id>>8);dlog[19]=(byte)id;
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("DLOG"),id,name,0x20,dlog));
		f.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("DITL"),id,name,0x20,items));
	}

	private static void item(java.io.ByteArrayOutputStream out,int[] rect,int type,byte[] data){
		for(int i=0;i<4;i++)out.write(0);
		for(int v:rect){out.write(v>>>8&0xff);out.write(v&0xff);}
		int n=Math.min(255,data.length);out.write(type);out.write(n);out.write(data,0,n);if((n&1)!=0)out.write(0);
	}
}

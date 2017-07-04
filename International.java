import java.util.HashMap;



class International {
	HashMap<String,String> menuHash = new HashMap<String,String>(); //高速化のためのツリー
	HashMap<String,String> menuRHash = new HashMap<String,String>(); //逆引き
	HashMap<String,String> dialogHash = new HashMap<String,String>(); //高速化のためのツリー
	HashMap<String,String> dialogRHash = new HashMap<String,String>(); //逆引き

	public International(String lang) throws Exception{
		if(lang.equals("Japanese") ){
			putHash(menuHash, menuRHash,"File","ファイル");
			putHash(menuHash, menuRHash,"New Stack…","新規スタック…");
			putHash(menuHash, menuRHash,"Open Stack…","スタックを開く…");
			putHash(menuHash, menuRHash,"Open Recent Stack","最近使ったスタックを開く");
			putHash(menuHash, menuRHash,"Clear This Menu","メニューを消去");
			putHash(menuHash, menuRHash,"Close Stack","スタックを閉じる");
			putHash(menuHash, menuRHash,"Save a Copy…","別名で保存…");
			putHash(menuHash, menuRHash,"Compact Stack","スタック整理");
			putHash(menuHash, menuRHash,"Protect Stack…","スタック保護…");
			putHash(menuHash, menuRHash,"Delete Stack…","スタック削除…");
			putHash(menuHash, menuRHash,"Print…","プリント…");
			putHash(menuHash, menuRHash,"Quit HyperCard","HyperCard終了");
			putHash(menuHash, menuRHash,"Quit","終了");
			
			putHash(menuHash, menuRHash,"Import Paint…","ペイントを読み込む…");
			putHash(menuHash, menuRHash,"Export Paint…","ペイントを書き出す…");
			putHash(menuHash, menuRHash,"Save as ppm…","ppmファイルとして保存…");
			
			putHash(menuHash, menuRHash,"Edit","編集");
			putHash(menuHash, menuRHash,"Undo","取り消し");
			putHash(menuHash, menuRHash,"Redo","やり直し");
			putHash(menuHash, menuRHash,"Cut","カット");
			putHash(menuHash, menuRHash,"Copy","コピー");
			putHash(menuHash, menuRHash,"Paste","ペースト");
			putHash(menuHash, menuRHash,"Delete","消去");
			putHash(menuHash, menuRHash,"Clear Selection","選択範囲の消去");
			putHash(menuHash, menuRHash,"New Card","新規カード");
			putHash(menuHash, menuRHash,"Delete Card","カード削除");
			putHash(menuHash, menuRHash,"Cut Card","カット カード");
			putHash(menuHash, menuRHash,"Copy Card","コピー カード");
			putHash(menuHash, menuRHash,"Background","バックグラウンド");
			putHash(menuHash, menuRHash,"Icon…","アイコン編集…");
			putHash(menuHash, menuRHash,"Sound…","サウンド編集…");
			putHash(menuHash, menuRHash,"Resource…","リソース編集…");

			putHash(menuHash, menuRHash,"Cut Button","カット ボタン");
			putHash(menuHash, menuRHash,"Copy Button","コピー ボタン");
			putHash(menuHash, menuRHash,"Paste Button","ペースト ボタン");
			putHash(menuHash, menuRHash,"Delete Button","消去 ボタン");

			putHash(menuHash, menuRHash,"Cut Field","カット フィールド");
			putHash(menuHash, menuRHash,"Copy Field","コピー フィールド");
			putHash(menuHash, menuRHash,"Paste Field","ペースト フィールド");
			putHash(menuHash, menuRHash,"Delete Field","消去 フィールド");
			
			putHash(menuHash, menuRHash,"Undo Paint","ペイントの取り消し");
			putHash(menuHash, menuRHash,"Redo Paint","ペイントのやり直し");
			putHash(menuHash, menuRHash,"Cut Picture","カット ピクチュア");
			putHash(menuHash, menuRHash,"Copy Picture","コピー ピクチュア");
			putHash(menuHash, menuRHash,"Paste Picture","ペースト ピクチュア");

			putHash(menuHash, menuRHash,"Go","ゴー");
			putHash(menuHash, menuRHash,"Back","戻る");
			putHash(menuHash, menuRHash,"Home","ホーム");
			putHash(menuHash, menuRHash,"Help","ヘルプ");
			putHash(menuHash, menuRHash,"Recent","リーセント");
			putHash(menuHash, menuRHash,"First","最初のカード");
			putHash(menuHash, menuRHash,"Prev","前のカード");
			putHash(menuHash, menuRHash,"Next","次のカード");
			putHash(menuHash, menuRHash,"Last","最後のカード");
			putHash(menuHash, menuRHash,"Find…","検索…");
			putHash(menuHash, menuRHash,"Message","メッセージ");
			putHash(menuHash, menuRHash,"Next Window","次のウィンドウ");

			putHash(menuHash, menuRHash,"Tool","ツール");
			putHash(menuHash, menuRHash,"Hide ToolBar","ツールバーを隠す");
			putHash(menuHash, menuRHash,"Show ToolBar","ツールバーを表示");
			putHash(menuHash, menuRHash,"Browse","ブラウズ");
			putHash(menuHash, menuRHash,"Button","ボタン");
			putHash(menuHash, menuRHash,"Field","フィールド");
			//putHash(menuHash, menuRHash,"Hide ColorPalette","カラーパレットを隠す");
			//putHash(menuHash, menuRHash,"Show ColorPalette","カラーパレットを表示");
			//putHash(menuHash, menuRHash,"Hide PatternPalette","パターンパレットを隠す");
			//putHash(menuHash, menuRHash,"Show PatternPalette","パターンパレットを表示");

			putHash(menuHash, menuRHash,"Objects","オブジェクト");
			
			putHash(menuHash, menuRHash,"Font","フォント");
			
			putHash(menuHash, menuRHash,"Paint","ペイント");
			putHash(menuHash, menuRHash,"Select","選択");
			putHash(menuHash, menuRHash,"Select All","すべてを選択");
			putHash(menuHash, menuRHash,"FatBits","拡大表示");
			putHash(menuHash, menuRHash,"Grid","グリッド");
			putHash(menuHash, menuRHash,"Use Grid","グリッドを表示");
			putHash(menuHash, menuRHash,"Grid Size 1","グリッドサイズ1");
			putHash(menuHash, menuRHash,"Grid Size 16","グリッドサイズ16");
			putHash(menuHash, menuRHash,"Antialias","アンチエイリアス");
			putHash(menuHash, menuRHash,"Fill","塗りつぶし");
			putHash(menuHash, menuRHash,"Invert","反転");
			putHash(menuHash, menuRHash,"Pickup","ピックアップ");
			putHash(menuHash, menuRHash,"Darken","暗くする");
			putHash(menuHash, menuRHash,"Lighten","明るくする");
			putHash(menuHash, menuRHash,"Rotate Left","左回転");
			putHash(menuHash, menuRHash,"Rotate Right","右回転");
			putHash(menuHash, menuRHash,"Flip Horizontal","左右反転");
			putHash(menuHash, menuRHash,"Flip Vertical","上下反転");
			putHash(menuHash, menuRHash,"Opaque","不透明");
			putHash(menuHash, menuRHash,"Transparent","透明");
			putHash(menuHash, menuRHash,"Keep","作業の保存");
			putHash(menuHash, menuRHash,"Revert","復帰");
			putHash(menuHash, menuRHash,"Rotate","回す");
			putHash(menuHash, menuRHash,"Distort","自由に変形");
			putHash(menuHash, menuRHash,"Stretch","傾ける");
			putHash(menuHash, menuRHash,"Perspective","遠近効果");
			putHash(menuHash, menuRHash,"Color Convert…","色の変更…");
			putHash(menuHash, menuRHash,"Emboss…","浮き出し効果…");
			putHash(menuHash, menuRHash,"Scale Selection…","選択範囲の拡大縮小…");
			putHash(menuHash, menuRHash,"Reverse Selection","選択範囲の逆転");
			putHash(menuHash, menuRHash,"Expand Selection","選択範囲を広げる");
			putHash(menuHash, menuRHash,"Filter…","フィルター…");
			putHash(menuHash, menuRHash,"Blending Mode…","合成モード…");

			putHash(menuHash, menuRHash,"Tool","ツール");
			putHash(menuHash, menuRHash,"Browse","ブラウズ");
			putHash(menuHash, menuRHash,"Button","ボタン");
			putHash(menuHash, menuRHash,"Field","フィールド");

			putHash(menuHash, menuRHash,"Select","選択");
			putHash(menuHash, menuRHash,"Lasso","投げなわ");
			putHash(menuHash, menuRHash,"MagicWand","自動選択");
			putHash(menuHash, menuRHash,"Pencil","鉛筆");
			putHash(menuHash, menuRHash,"Brush","ブラシ");
			putHash(menuHash, menuRHash,"Eraser","消しゴム");
			putHash(menuHash, menuRHash,"Line","線");
			putHash(menuHash, menuRHash,"SprayCan","スプレー");
			putHash(menuHash, menuRHash,"Rect","長方形");
			putHash(menuHash, menuRHash,"RoundRect","丸みのある長方形");
			putHash(menuHash, menuRHash,"PaintBucket","バケツ");
			putHash(menuHash, menuRHash,"Oval","楕円");
			putHash(menuHash, menuRHash,"Curve","円弧");
			putHash(menuHash, menuRHash,"Type","文字");
			putHash(menuHash, menuRHash,"Polygon","多角形");
			putHash(menuHash, menuRHash,"FreePolygon","フリー多角形");
			putHash(menuHash, menuRHash,"Spoit","スポイト");

			putHash(menuHash, menuRHash,"Transparency","透明度");
			putHash(menuHash, menuRHash,"Gradation","グラデーション");
			putHash(menuHash, menuRHash,"Angle","角度");
			putHash(menuHash, menuRHash,"Fill","塗りつぶす");
			putHash(menuHash, menuRHash,"Don't Fill","塗りつぶさない");

			putHash(menuHash, menuRHash,"Button Info…","ボタン情報…");
			putHash(menuHash, menuRHash,"Field Info…","フィールド情報…");
			putHash(menuHash, menuRHash,"Card Info…","カード情報…");
			putHash(menuHash, menuRHash,"Background Info…","バックグラウンド情報…");
			putHash(menuHash, menuRHash,"Stack Info…","スタック情報…");
			putHash(menuHash, menuRHash,"Bring Closer","前面に出す");
			putHash(menuHash, menuRHash,"Send Farther","背面に送る");
			putHash(menuHash, menuRHash,"New Button","新規ボタン");
			putHash(menuHash, menuRHash,"New Field","新規フィールド");
			putHash(menuHash, menuRHash,"New Background","新規バックグラウンド");
			
			putHash(menuHash, menuRHash,"Close","閉じる");
			putHash(menuHash, menuRHash,"Save","保存");
			putHash(menuHash, menuRHash,"Find","検索");
			putHash(menuHash, menuRHash,"Find Next","次を検索");
			putHash(menuHash, menuRHash,"Find Prev","前を検索");
			putHash(menuHash, menuRHash,"Replace","置き換え");
			putHash(menuHash, menuRHash,"Replace Next","次を置き換え");
			putHash(menuHash, menuRHash,"Replace Prev","前を置き換え");
			putHash(menuHash, menuRHash,"Script","スクリプト");
			putHash(menuHash, menuRHash,"Edit Card Script","カードスクリプトを開く");
			putHash(menuHash, menuRHash,"Edit Background Script","バックグラウンドスクリプトを開く");
			putHash(menuHash, menuRHash,"Edit Stack Script","スタックスクリプトを開く");
			putHash(menuHash, menuRHash,"Comment","コメントにする");
			putHash(menuHash, menuRHash,"Uncomment","コメントを外す");
			
			putHash(menuHash, menuRHash,"Debug","デバッグ");
			putHash(menuHash, menuRHash,"Step","ステップ");
			putHash(menuHash, menuRHash,"Trace","トレース");
			putHash(menuHash, menuRHash,"Run","実行");
			putHash(menuHash, menuRHash,"Variable Watcher","変数表示ウィンドウ");

			putHash(menuHash, menuRHash,"New Item","新規作成");
			putHash(menuHash, menuRHash,"Open","開く");
			putHash(menuHash, menuRHash,"Image Size…","サイズ変更…");
			putHash(menuHash, menuRHash,"View File","ファイルを表示");
			putHash(menuHash, menuRHash,"Hot Spot…","ホットスポット…");
			

			//ダイアログ用
			putHash(dialogHash, dialogRHash,"Save File","ファイルに保存");
			putHash(dialogHash, dialogRHash,"Script Editor","スクリプトエディタ");
			putHash(dialogHash, dialogRHash,"Script is not saved.","スクリプトが保存されていません");
			putHash(dialogHash, dialogRHash,"Save","保存する");
			putHash(dialogHash, dialogRHash,"Discard","保存しない");
			putHash(dialogHash, dialogRHash,"Find String","検索");
			putHash(dialogHash, dialogRHash,"Find Prev","前を検索");
			putHash(dialogHash, dialogRHash,"Find Next","次を検索");
			putHash(dialogHash, dialogRHash,"Replace String","置換");
			putHash(dialogHash, dialogRHash,"Replace All","すべて置換");
			putHash(dialogHash, dialogRHash,"Replace Prev","前を置換");
			putHash(dialogHash, dialogRHash,"Replace Next","次を置換");
			
			putHash(dialogHash, dialogRHash,"Icon Editor","アイコンエディタ");
			putHash(dialogHash, dialogRHash,"Name:","名前:");
			putHash(dialogHash, dialogRHash,"ID:","ID:");
			putHash(dialogHash, dialogRHash,"Width:","幅:");
			putHash(dialogHash, dialogRHash,"Height:","高さ:");
			putHash(dialogHash, dialogRHash,"Cancel","キャンセル");

			putHash(dialogHash, dialogRHash,"New Button","新規ボタン");
			
			putHash(dialogHash, dialogRHash,"Button Name:","ボタン名:");
			putHash(dialogHash, dialogRHash,"Card button ","カードボタン ");
			putHash(dialogHash, dialogRHash,"Background button ","バックグラウンドボタン ");
			putHash(dialogHash, dialogRHash,"ID: ","ID: ");
			putHash(dialogHash, dialogRHash,"Number: ","番号: ");
			putHash(dialogHash, dialogRHash,"Part Number: ","部品番号: ");
			putHash(dialogHash, dialogRHash,"Style:","形式:");
			putHash(dialogHash, dialogRHash,"Standard","標準");
			putHash(dialogHash, dialogRHash,"Transparent","透明");
			putHash(dialogHash, dialogRHash,"Opaque","不透明");
			putHash(dialogHash, dialogRHash,"Rectangle","長方形");
			putHash(dialogHash, dialogRHash,"Shadow","シャドウ");
			putHash(dialogHash, dialogRHash,"RoundRect","丸みのある長方形");
			putHash(dialogHash, dialogRHash,"Default","省略時設定");
			putHash(dialogHash, dialogRHash,"Oval","楕円");
			putHash(dialogHash, dialogRHash,"Popup","ポップアップ");
			putHash(dialogHash, dialogRHash,"CheckBox","チェックボックス");
			putHash(dialogHash, dialogRHash,"Radio","ラジオボタン");
			putHash(dialogHash, dialogRHash,"Family:","ファミリー:");
			putHash(dialogHash, dialogRHash,"Show Name","名前を表示");
			putHash(dialogHash, dialogRHash,"Enabled","使えるように");
			putHash(dialogHash, dialogRHash,"Visible","見えるように");
			putHash(dialogHash, dialogRHash,"Auto Hilite","オートハイライト");
			putHash(dialogHash, dialogRHash,"Shared Hilite","ハイライトを共有");
			putHash(dialogHash, dialogRHash,"Scale Icon","アイコンの拡大縮小");
			putHash(dialogHash, dialogRHash,"Font…","フォント…");
			putHash(dialogHash, dialogRHash,"Icon…","アイコン…");
			putHash(dialogHash, dialogRHash,"Effect…","視覚効果…");
			putHash(dialogHash, dialogRHash,"LinkTo…","移動…");
			putHash(dialogHash, dialogRHash,"Script…","スクリプト…");
			putHash(dialogHash, dialogRHash,"Content…","内容…");
			putHash(dialogHash, dialogRHash,"None","なし");

			putHash(dialogHash, dialogRHash,"Field Name:","フィールド名:");
			putHash(dialogHash, dialogRHash,"Card field ","カードフィールド ");
			putHash(dialogHash, dialogRHash,"Background field ","バックグラウンドフィールド ");
			putHash(dialogHash, dialogRHash,"Locked text","ロックテキスト");
			putHash(dialogHash, dialogRHash,"Don't wrap","行を回り込ませない");
			putHash(dialogHash, dialogRHash,"Auto select","自動的に選択");
			putHash(dialogHash, dialogRHash,"Multiple lines","複数行");
			putHash(dialogHash, dialogRHash,"Wide margins","余白を広く");
			putHash(dialogHash, dialogRHash,"Fixed line height","行の高さを固定");
			putHash(dialogHash, dialogRHash,"Show lines","行表示");
			putHash(dialogHash, dialogRHash,"Auto tab","オートタブ");
			putHash(dialogHash, dialogRHash,"Don't search","検索しない");
			putHash(dialogHash, dialogRHash,"Shared text","テキストを共有");
			putHash(dialogHash, dialogRHash,"Scroll","スクロール");

			putHash(dialogHash, dialogRHash,"Card Name:","カード名:");
			putHash(dialogHash, dialogRHash,"Card ","カード ");
			putHash(dialogHash, dialogRHash,"Background Name:","バックグラウンド名:");
			putHash(dialogHash, dialogRHash,"Background ","バックグラウンド ");
			putHash(dialogHash, dialogRHash,"Show picture","ピクチャを表示");
			putHash(dialogHash, dialogRHash,"Marked","マーク");
			putHash(dialogHash, dialogRHash,"Can't delete","削除不可");
			
			putHash(dialogHash, dialogRHash,"Stack Name:","スタック名:");
			putHash(dialogHash, dialogRHash,"Stack Path: ","スタックのパス: ");
			putHash(dialogHash, dialogRHash,"Size…","大きさ…");

			putHash(dialogHash, dialogRHash,"New Stack","新規スタック");

			putHash(dialogHash, dialogRHash,"Color Convert","色の変更");
			putHash(dialogHash, dialogRHash,"Divide","分ける");
			putHash(dialogHash, dialogRHash,"Unite","合わせる");
			
			putHash(dialogHash, dialogRHash,"Emboss","浮き出し効果");
			putHash(dialogHash, dialogRHash,"Thickness","厚み");
			putHash(dialogHash, dialogRHash,"Use","使用する");
			putHash(dialogHash, dialogRHash,"Brightness ","明るさ ");
			putHash(dialogHash, dialogRHash,"Width ","幅 ");
			putHash(dialogHash, dialogRHash,"Gradation","グラデーション");
			putHash(dialogHash, dialogRHash,"Highlight","ハイライト");
			putHash(dialogHash, dialogRHash,"Area ","範囲 ");
			putHash(dialogHash, dialogRHash,"Reflection","反射");
			putHash(dialogHash, dialogRHash,"Line","直線");
			putHash(dialogHash, dialogRHash,"Curve","弧");
			putHash(dialogHash, dialogRHash,"Fit","形状に合わせる");
			putHash(dialogHash, dialogRHash,"Angle ","角度 ");

			putHash(dialogHash, dialogRHash,"Scale Selection","選択範囲の拡大縮小");
			putHash(dialogHash, dialogRHash,"Keep aspect ratio","縦横比を固定");
			putHash(dialogHash, dialogRHash,"Choose from Screen","画面の色を取得");

			putHash(dialogHash, dialogRHash,"Filter","フィルター");
			putHash(dialogHash, dialogRHash,"Auto"," 自動");
			putHash(dialogHash, dialogRHash,"Trace Edges","縁取り");
			putHash(dialogHash, dialogRHash,"Trace Edges 2","縁取り2");
			putHash(dialogHash, dialogRHash,"Spread Edges Dark","輪郭を太く(暗い色)");
			putHash(dialogHash, dialogRHash,"Spread Edges","輪郭を太く");
			putHash(dialogHash, dialogRHash,"Spread Edges Light","輪郭を太く(明るい色)");
			putHash(dialogHash, dialogRHash,"Small Median","メディアン小");
			putHash(dialogHash, dialogRHash,"Median","メディアン");
			putHash(dialogHash, dialogRHash,"Large Median","メディアン大");
			putHash(dialogHash, dialogRHash,"Motion Blur","モーションブラー");
			putHash(dialogHash, dialogRHash,"Sharpen","シャープ");
			putHash(dialogHash, dialogRHash,"Blur","ぼかし");
			putHash(dialogHash, dialogRHash,"Glass Tile","ガラスタイル");
			putHash(dialogHash, dialogRHash,"Frosted Glass","すりガラス");
			putHash(dialogHash, dialogRHash,"Horizontal Wave","水平ウェーブ");
			putHash(dialogHash, dialogRHash,"Vertical Wave","垂直ウェーブ");
			putHash(dialogHash, dialogRHash,"Noise","ノイズを加える");
			putHash(dialogHash, dialogRHash,"Higher Contrast","コントラストを強く");
			putHash(dialogHash, dialogRHash,"Lower Contrast","コントラストを弱く");
			putHash(dialogHash, dialogRHash,"Higher Saturation","彩度を上げる");
			putHash(dialogHash, dialogRHash,"Lower Saturation","彩度を下げる");
			putHash(dialogHash, dialogRHash,"Grayscale","グレースケール");
			putHash(dialogHash, dialogRHash,"Binarization","白黒(ディザリングあり)");
			putHash(dialogHash, dialogRHash,"Binarization","白黒");
			putHash(dialogHash, dialogRHash,"Index Color with Dithering","減色(ディザリングあり)");
			putHash(dialogHash, dialogRHash,"Index Color","減色");

			putHash(dialogHash, dialogRHash,"Blending Mode","合成モード");
			putHash(dialogHash, dialogRHash,"Copy","コピー");
			putHash(dialogHash, dialogRHash,"Blend","ブレンド");
			putHash(dialogHash, dialogRHash,"Add","発光");
			putHash(dialogHash, dialogRHash,"Subtract","陰影");
			putHash(dialogHash, dialogRHash,"Multiply","乗算");
			putHash(dialogHash, dialogRHash,"Screen","スクリーン");
			putHash(dialogHash, dialogRHash,"Darken","暗い色を残す");
			putHash(dialogHash, dialogRHash,"Lighten","明るい色を残す");
			putHash(dialogHash, dialogRHash,"Difference","差の絶対値");
			putHash(dialogHash, dialogRHash,"Hue","色相");
			putHash(dialogHash, dialogRHash,"Color","色合い");
			putHash(dialogHash, dialogRHash,"Saturation","彩度");
			putHash(dialogHash, dialogRHash,"Luminosity","輝度");
			putHash(dialogHash, dialogRHash,"Alpha Channel","透明度");

			//フォントダイアログ
			putHash(dialogHash, dialogRHash,"Font","フォント");
			putHash(dialogHash, dialogRHash,"Size","サイズ");
			putHash(dialogHash, dialogRHash,"Style","スタイル");
			putHash(dialogHash, dialogRHash,"Bold","太字");
			putHash(dialogHash, dialogRHash,"Italic","斜体");
			putHash(dialogHash, dialogRHash,"Underline","下線");
			putHash(dialogHash, dialogRHash,"Outline","アウトライン");
			putHash(dialogHash, dialogRHash,"Shadow","シャドウ");
			putHash(dialogHash, dialogRHash,"Condensed","字間を狭く");
			putHash(dialogHash, dialogRHash,"Extend","字間を広く");
			putHash(dialogHash, dialogRHash,"Align Left","左寄せ");
			putHash(dialogHash, dialogRHash,"Align Center","センタリング");
			putHash(dialogHash, dialogRHash,"Align Right","右寄せ");
			
			//一般エラー
			putHash(dialogHash, dialogRHash,"Could't open the file.","ファイルを開けませんでした");
			putHash(dialogHash, dialogRHash,"Drop file here","スタックをここにドロップしてください");
			putHash(dialogHash, dialogRHash,"Illegal size.","サイズが不正です");
			putHash(dialogHash, dialogRHash,"No selected button.","ボタンが選択されていません");
			putHash(dialogHash, dialogRHash,"No selected field.","フィールドが選択されていません");
			putHash(dialogHash, dialogRHash,"Converting from HyperCard Stack is need to run on MacOSX.","HyperCardスタックのコンバートはMacOSX上でのみ利用できます");
			putHash(dialogHash, dialogRHash,"Can't create a new folder.","フォルダを作成できません");
			putHash(dialogHash, dialogRHash,"Error occured at reading HyperCard stack data.","HyperCardスタックの読み込みでエラーが発生しました");
			putHash(dialogHash, dialogRHash,"Error occured at reading MacBinary HyperCard stack data.","MacBinaryエンコードされたHyperCardスタックの読み込みでエラーが発生しました");
			putHash(dialogHash, dialogRHash,"Resource data is not found.","リソースファイルが見つかりませんでした");
			putHash(dialogHash, dialogRHash,"This version of Java Runtime is not supported streaming API for XML.", "Javaランタイムのバージョンが古いためXML読み込み機能が使えません");
			putHash(dialogHash, dialogRHash,"Error occured at reading XML file.", "XMLデータの読み込み中にエラーが発生しました");
			putHash(dialogHash, dialogRHash,"XML end tag is not found.", "XMLファイルを最後まで読めませんでした");
			putHash(dialogHash, dialogRHash,"This file name already exists. If you continue, the existing file will be replaced.","そのファイル名は既に使われています。上書きしますか？");
			putHash(dialogHash, dialogRHash,"At least one card is required.","スタックには必ずカードが一枚以上必要です");
			putHash(dialogHash, dialogRHash,"Can't open the file '%1'.","ファイル'%1'を開けませんでした");
			putHash(dialogHash, dialogRHash,"This resource is not in this stack. Make a copy?","このリソースはこのスタックにはありません。コピーを作成します。");
			
			//スクリプトエラー
			
		}
		else if(lang.equals("English") ){
			//何もしない
		}
		else {
			throw new Exception("Unsupported Language \""+lang+"\"");
		}
	}
	
	private void putHash(HashMap<String,String> nh, HashMap<String,String> rh, String eng, String other){
		nh.put(eng, other);
		if(rh!=null){
			rh.put(other, eng);
		}
	}
	
	public String getText(String name){
		String ret = menuHash.get(name);
		if(ret==null) return name;
		else return ret;
	}
	
	public String getEngText(String name){
		String ret = menuRHash.get(name);
		if(ret==null) return name;
		else return ret;
	}
	
	public String getToolText(String name){
		return getText(name);
	}
	
	public String getToolEngText(String name){
		return getEngText(name);
	}
	
	public String getDialogText(String name){
		String ret = dialogHash.get(name);
		if(ret==null) return name;
		else return ret;
	}
	
	public String getDialogEngText(String name){
		String ret = dialogRHash.get(name);
		if(ret==null) return name;
		else return ret;
	}
}

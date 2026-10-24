import java.awt.Font;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemColor;
import java.awt.dnd.DropTarget;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.border.EmptyBorder;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class OField extends OObject implements Cloneable {
	OCardBase card = null;
	//表示中でないカードの非共有bgフィールドを参照するとき(bg fld X of cd Y)の、
	//そのカード側のテキストを読み書きするビュー(forCardで作る複製)の対象カード。
	//通常のフィールドではnull。
	OCard cardView = null;
	OBackground bkgd = null;
	MyTextArea fld = null;
	MyScrollPane scrollPane = null;
	
	//プロパティ
	Boolean autoTab=false;
	Boolean autoSelect=false;
	Boolean dontSearch=false;
	Boolean dontWrap=false;
	Color color=Color.BLACK;
	Color bgColor=Color.WHITE;
	//Color selectColor=Color.ORANGE;
	//Color textColor=Color.BLACK;
	Boolean fixedLineHeight=false;
	// lockTextはenabledで代用
	//number (カードの情報から求める)
	int scroll=0;
	Boolean sharedText=false;
	Boolean showLines=false;
	int style=0;//(0標準) 1透明 2不透明 3長方形 4シャドウ 5スクロール
	int textAlign=0;//0左 1中 2右
	String textFont="";
	int textHeight=16;
	int textSize=12;
	int textStyle=0;//0 plain
	boolean wideMargins=false;
	int selectedLine=0;
	int selectedStart=0;
	int selectedEnd=0;
	boolean multipleLines;
	boolean hilite=false;//
	//表(tableStyle): テキストの各行が行、タブ区切りの各itemがセル(FieldTable)
	boolean tableStyle=false;
	String columnWidths="";//"120,80,200"(論理ピクセル。空の項目は残りの幅を等分)
	String columnNames="";//見出し(タブまたはカンマ区切り)
	boolean sortable=true;//見出しのクリックで並べ替える
	boolean gridLines=true;
	//表で選択中の行(1起点、昇順)と列(1起点、0は無し)。selectedLineは先頭の行
	int[] selectedRows=new int[0];
	int selectedColumn=0;
	//最後にクリックしたセル(the clickText / the clickChunk)
	int clickRow=0, clickColumn=0;
	//アウトライン(outlineStyle): JSONまたはタブで字下げしたテキストを開閉できる木で表示する(FieldOutline)
	boolean outlineStyle=false;
	int outlineLevel=1;//最初に開いておく階層の数(0なら全部閉じる)
	String selectedPath="";//JSONのアウトラインで選択中の項目("weather.days.2")
	FieldOutline.Data.Node clickNode;//最後にクリックした項目(the clickText)
	
	ArrayList<styleClass> styleList;
	
	//追加プロパティ
	@SuppressWarnings("unused")
	private String reservedFamily;
	@SuppressWarnings("unused")
	private int reservedTitleWidth;
	@SuppressWarnings("unused")
	private int reservedIcon;
	@SuppressWarnings("unused")
	private String reserved35;
	DropTarget drop;
	GUI_fldDocument docListener;
	
	boolean useMyDraw = false;
	
	//get
	public String getName() {return name;}
	public ArrayList<String> getScript() {return scriptList;}
	public int getSelectedLine() {return selectedLine;}
	public String getSelectedText() {
		String text = getText();
		if(outlineStyle){
			//アウトライン: 選択中の項目の見出し(字下げのタブを除く)、JSONなら値
			FieldOutline.Data.Tree tree = FieldOutline.Data.parse(text);
			StringBuilder sb = new StringBuilder();
			try{
				if(tree.json){
					FieldOutline.Data.Node n = selectedPath.isEmpty() ? null : tree.find(selectedPath);
					if(n!=null) sb.append(n.text());
				}
				else for(int row : selectedRows){
					FieldOutline.Data.Node n = tree.atLine(row);
					if(n==null) continue;
					if(sb.length()>0) sb.append('\n');
					sb.append(n.label);
				}
			}catch(xTalkException e){ return ""; }
			return sb.toString();
		}
		if(tableStyle && selectedRows.length>0){
			//表: 選択中の行(複数なら改行区切り)
			String[] lines = FieldTable.Data.lines(text);
			StringBuilder sb = new StringBuilder();
			for(int row : selectedRows){
				if(row<1 || row>lines.length) continue;
				if(sb.length()>0) sb.append('\n');
				sb.append(lines[row-1]);
			}
			return sb.toString();
		}
		if(selectedLine>0){
			String[] strary = text.split("\n");
			if(selectedLine-1 < strary.length){
				String str = strary[selectedLine-1];
				if(str.endsWith("\r"))
					str = str.substring(0, str.length()-1);
				return str;
			}
		}
		//表示中のフィールドなら実際の選択範囲(select コマンドやマウス選択)を優先する
		if(fld!=null && fld.getSelectionEnd()>fld.getSelectionStart()){
			String t = fld.getText();
			int s0 = Math.max(0, Math.min(fld.getSelectionStart(), t.length()));
			int e0 = Math.max(s0, Math.min(fld.getSelectionEnd(), t.length()));
			return t.substring(s0, e0);
		}
		//selectedStart/Endはテキスト変更後に範囲外になっていることがある
		int start = Math.max(0, Math.min(selectedStart, text.length()));
		int end = Math.max(start, Math.min(selectedEnd, text.length()));
		return text.substring(start, end);
	}

	JComponent getComponent(){
		if(scrollPane!=null) return scrollPane;
		if(fld!=null) return fld;
		return null;
	}

	MyTextArea getMyTextArea(){
		if(fld!=null) return fld;
		return null;
	}
	
	//set
	public void setName(String in) {
		name=in;
		((OCardBase)parent).changed = true;
	}
	public void setTopLeft(int h, int v) {
		final int oldLeft=left;
		final int oldTop=top;
		left=h;
		top=v;
		if(fld!=null){
			final int newLeft=left;
			final int newTop=top;
			final int fieldWidth=width;
			final int fieldHeight=height;
			PCARD.runOnEDTWhenShown(this, PCARD.EDT_BOUNDS, () -> {
				JComponent component=getComponent();
				if(component==null) return;
				component.setBounds(newLeft, newTop, fieldWidth, fieldHeight);
				if(!PCARD.lockedScreen) {
					if(Math.abs(newLeft-oldLeft)<fieldWidth && Math.abs(newTop-oldTop)<fieldHeight)
						PCARD.repaintLogicalRect(Math.min(newLeft,oldLeft), Math.min(newTop,oldTop), fieldWidth+Math.abs(newLeft-oldLeft), fieldHeight+Math.abs(newTop-oldTop));
					else {
						PCARD.repaintLogicalRect(newLeft, newTop, fieldWidth, fieldHeight);
						PCARD.repaintLogicalRect(oldLeft, oldTop, fieldWidth, fieldHeight);
					}
				}
			}, this::applyBounds);
		}
		((OCardBase)parent).changed = true;
		((OCardBase)parent).movePanelPeers(this, h-oldLeft, v-oldTop);
		CardWindow68k.partMoved(this, oldTop, oldLeft, oldTop+height, oldLeft+width); //an external's drawing there goes
	}
	public void setRect(int v1, int v2, int v3, int v4) {
		final int oldLeft=left;
		final int oldTop=top;
		final int oldWidth=width;
		final int oldHeight=height;
		left=v1;
		top=v2;
		width=v3-v1;
		height=v4-v2;
		if(fld!=null){
			final int newLeft=left;
			final int newTop=top;
			final int newWidth=width;
			final int newHeight=height;
			PCARD.runOnEDTWhenShown(this, PCARD.EDT_BOUNDS, () -> {
				JComponent component=getComponent();
				if(component==null) return;
				component.setBounds(newLeft, newTop, newWidth, newHeight);
				if(!PCARD.lockedScreen) {
					PCARD.repaintLogicalRect(newLeft, newTop, newWidth, newHeight);
					PCARD.repaintLogicalRect(oldLeft, oldTop, oldWidth, oldHeight);
				}
			}, this::applyBounds);
		}
		((OCardBase)parent).changed = true;
		CardWindow68k.partMoved(this, oldTop, oldLeft, oldTop+oldHeight, oldLeft+oldWidth); //an external's drawing there goes
	}
	public void setTextByInputarea(String in) {
		setText2(in);
		shownText = in; //入力されたテキストはもう部品(Swing)にある
		((OCardBase)parent).changed = true;
	}
	/** The text this field's Swing component was last given (by setText or typing); null when unknown. */
	private volatile String shownText;
	public void setText(String in) {
		//部品がもう同じテキスト(スタイルなし)を表示していれば何も変わらない: 毎コマ同じ文字を入れるゲームの表示で、
		//EDT への往復(lock screen 中は開始時の画面の絵も)を省く
		if(in!=null && fld!=null && cardView==null && in.equals(shownText) && in.equals(super.getText()) &&
				(styleList==null || styleList.isEmpty())) return;
		setText2(in);
		if(fld!=null) {
			Runnable updateAndRepaint = () -> {
				int scroll = 0;
				if(scrollPane!=null) scroll = scrollPane.getVerticalScrollBar().getValue();
				String text = getText();
				//put keeps the field's scroll (HyperCard J-2.3: DicSGML puts its 332-line index into a scrolling field at
				//scroll 0 and shows its top); the caret's catching up later (invokeLater) scrolled to the end
				fld.keepScrollWhile(() -> fld.setText(text));
				shownText = text;
				if(scrollPane!=null) scrollPane.getVerticalScrollBar().setValue(scroll);
				if(!PCARD.lockedScreen) {
					PCARD.repaintLogicalRect(left, top, width, height);
				}
			};
			//スクリプト実行スレッドから直接Swingコンポーネントのテキストを更新して
			//すぐに再描画すると、EDT側の描画処理と競合して文字が描画されない
			//（フィールドが真っ白のまま）ことがあるため、EDT上でまとめて同期実行する。
			//lock screen 中も保留しない: select・選択範囲・行の位置などはスクリプトスレッドから部品を直接読む
			PCARD.runOnEDT(updateAndRepaint);
		}
		((OCardBase)parent).changed = true;
	}
	public void setText2(String in) {
		//表示・getText()用のテキストは常にフィールド自身に持つ
		super.setText(in);
		//共有でないbg fldのテキストは、表示中カードのbgfldListにも保存する
		//(保存時にカードのcontentとして書き出されるのはこちら)
		OBgFieldData data = cardFieldData(true);
		if(data!=null){
			data.text = in;
			data.styleList = styleList;
			data.card.changed = true;
		}
	}

	/** The number of lines as HyperTalk counts them: none in an empty text, a final return starts no line */
	static int textLineCount(String text) {
		if(text==null || text.isEmpty()) return 0;
		int n = 1;
		for(int i=0; i<text.length()-1; i++) if(text.charAt(i)=='\n' || text.charAt(i)=='\r') n++;
		return n;
	}

	@Override
	public String getText() {
		//他カードのビューなら、そのカードのbgfldListのテキストを返す
		if(cardView!=null){
			OBgFieldData data = cardFieldData(false);
			return data==null || data.text==null ? "" : data.text;
		}
		return super.getText();
	}

	//「bg fld X of cd Y」のように表示中でないカードの非共有bgフィールドを参照した
	//ときに使う複製。フィールド自身のtextは表示中カードの内容しか持たないため、
	//複製のgetText/setTextはカードYのbgfldListを読み書きする。Swing部品は持たない
	//(書き込みが表示中カードの画面に出ないように)。表示中カードや共有フィールド、
	//カードフィールドではthisをそのまま返す。
	/** 部品のコピー(GUI部品や表示中カードへの参照は持たない)。create stack ... with background 用 */
	OField copyForBackground(OBackground bg) {
		OField dst;
		try{ dst = (OField)super.clone(); }
		catch(CloneNotSupportedException e){ return null; }
		dst.fld = null; dst.scrollPane = null; dst.drop = null; dst.docListener = null; dst.cardView = null;
		dst.card = bg; dst.parent = bg;
		if(scriptList!=null) dst.scriptList = new ArrayList<String>(scriptList);
		return dst;
	}

	OField forCard(OCard cd) {
		if(cd==null || card==null || !card.objectType.equals("background") || sharedText) return this;
		if(cardView==cd || ((OBackground)card).viewCard==cd) return this;
		OField view;
		try{ view = (OField)super.clone(); }
		catch(CloneNotSupportedException e){ return this; }
		view.cardView = cd;
		view.fld = null;
		view.scrollPane = null;
		view.drop = null;
		view.docListener = null;
		OBgFieldData data = view.cardFieldData(false);
		view.styleList = data==null ? null : data.styleList;
		return view;
	}

	//共有でないbgフィールドの、表示中カード側のデータ(OBgFieldData)。
	//カードフィールドや共有フィールド、表示中カードが無いときはnull。
	//createがtrueなら無ければ作る。
	OBgFieldData cardFieldData(boolean create) {
		if(card==null || !card.objectType.equals("background") || sharedText) return null;
		OCard viewCard = cardView!=null ? cardView : ((OBackground)card).viewCard;
		if(viewCard==null || viewCard.bgfldList==null) return null;
		for(int j=0; j<viewCard.bgfldList.size(); j++){
			if(viewCard.bgfldList.get(j).id == id) return viewCard.bgfldList.get(j);
		}
		if(!create) return null;
		OBgFieldData bgflddata = new OBgFieldData(viewCard, id);
		bgflddata.fld = this;
		bgflddata.text = getText();
		bgflddata.styleList = styleList;
		viewCard.bgfldList.add(bgflddata);
		return bgflddata;
	}
	/** (EDT) The components show the visibility the field has now. */
	private void applyVisible(){
		if(fld!=null) fld.setVisible(isShown());
		if(scrollPane!=null) scrollPane.setVisible(isShown());
	}
	/** Its panel was shown or hidden, or it joined or left one: the components follow isShown. */
	@Override void applyShown(){
		if(fld==null) return;
		PCARD.runOnEDTWhenShown(this, PCARD.EDT_VISIBLE, () -> {
			applyVisible();
			if(!PCARD.lockedScreen) PCARD.repaintLogicalRect(left, top, width, height);
		}, this::applyVisible);
	}
	/** (EDT) The component has the rectangle the field has now. */
	private void applyBounds(){
		JComponent component=getComponent();
		if(component!=null) component.setBounds(left, top, width, height);
	}
	public void setVisible(boolean in) {
		if(getVisible()==in) return;
		super.setVisible(in);
		if(fld!=null)
		{
			PCARD.runOnEDTWhenShown(this, PCARD.EDT_VISIBLE, () -> {
				applyVisible();
				if(!PCARD.lockedScreen)
					PCARD.repaintLogicalRect(left, top, width, height);
			}, this::applyVisible);
		}
	}
	public void setEnabled(boolean in) {
		enabled=in;
		if(fld!=null)
		{
			fld.setEnabled(in);
			if(!PCARD.lockedScreen) {
				PCARD.repaintLogicalRect(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	//find で見つけた文字列の枠。HyperCardでは選択(selection)とは別物なので、
	//JTextAreaの選択ではなくハイライトで表示する
	private static OField foundBoxField;
	private static Object foundBoxTag;
	void showFoundBox(int start, int end) {
		clearFoundBox();
		if(fld==null) return;
		try{
			foundBoxTag = fld.getHighlighter().addHighlight(start, end,
				new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0, 0, 0, 48)));
			foundBoxField = this;
			fld.repaint();
		}catch(javax.swing.text.BadLocationException e){
			foundBoxTag = null;
		}
	}
	static void clearFoundBox() {
		if(foundBoxField!=null && foundBoxField.fld!=null && foundBoxTag!=null){
			foundBoxField.fld.getHighlighter().removeHighlight(foundBoxTag);
			foundBoxField.fld.repaint();
		}
		foundBoxField = null;
		foundBoxTag = null;
	}

	public void setSelectedLine(int in) {
		selectedLine=in;
		selectedStart=0;
		selectedEnd=0;
		if(tableStyle || outlineStyle) setSelectedRows(in>0 ? new int[]{in} : new int[0]);
		if(fld!=null)
		{
			PCARD.repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setScroll(int in) {
		scroll=in;
		if(scrollPane!=null) {
			scrollPane.getVerticalScrollBar().setValue(MyScrollPane.toViewScroll(in));
			//a pane not laid out with the text yet keeps the scroll for its layout (MyScrollPane.followScroll); else the bar's limit applies
			if(MyScrollPane.textLaidOut(scrollPane)) scroll=MyScrollPane.toLogicalScroll(scrollPane.getVerticalScrollBar().getValue());
			if(fld!=null)
			{
				PCARD.repaintLogicalRect(left, top, width, height);
			}
		}
	}
	/** 表の選択行(1起点)。表示中なら表にも反映する */
	void setSelectedRows(int[] rows) {
		int[] sorted = rows.clone();
		java.util.Arrays.sort(sorted);
		selectedRows = sorted;
		selectedLine = sorted.length>0 ? sorted[0] : 0;
		if(sorted.length==0) selectedColumn = 0;
		FieldTable table = table();
		if(table!=null) PCARD.runOnEDT(table::showSelection);
		FieldOutline outline = outline();
		if(outline!=null) PCARD.runOnEDT(outline::showSelection);
	}
	/** JSONのアウトラインの選択(selectedPath)。表示中ならアウトラインにも反映する */
	void setSelectedPath(String path) {
		selectedPath = path==null ? "" : path;
		selectedRows = new int[0];
		selectedLine = 0;
		FieldOutline outline = outline();
		if(outline!=null) PCARD.runOnEDT(outline::showSelection);
	}
	FieldOutline outline() {
		return scrollPane instanceof FieldOutline ? (FieldOutline)scrollPane : null;
	}
	/** 表の選択を"line 2 of card field 1" / "line 2 to 4 of …"(連続していなければ先頭の行)で */
	String tableSelectedLine(String fieldRef) {
		int[] rows = selectedRows;
		if(rows.length==0) return "";
		boolean contiguous = rows[rows.length-1]-rows[0]==rows.length-1;
		if(rows.length==1 || !contiguous) return "line "+rows[0]+" of "+fieldRef;
		return "line "+rows[0]+" to "+rows[rows.length-1]+" of "+fieldRef;
	}
	FieldTable table() {
		return scrollPane instanceof FieldTable ? (FieldTable)scrollPane : null;
	}
	/** 表の見た目(フォント・色・列幅・罫線・選択の方式)をプロパティから描き直す */
	void refreshTable() {
		FieldTable table = table();
		if(table!=null) PCARD.runOnEDT(() -> { table.applyAppearance(); table.reload(); });
		FieldOutline outline = outline();
		if(outline!=null) PCARD.runOnEDT(() -> { outline.applyAppearance(); outline.reload(); });
	}
	/**
	 * The style as Field Info's Style menu and the style property choose it: 0 transparent, 1 opaque, 2 rectangle, 3 shadow,
	 * 4 scrolling, 5 table, 6 outline. A field that becomes a table or an outline gets the scrolling field's frame (one that
	 * already is keeps its own); any other style turns the table or outline off. The caller rebuilds the card's parts.
	 */
	void setStyleIndex(int index){
		boolean table = index==5, outline = index==6;
		if(table || outline){ if(table!=tableStyle || outline!=outlineStyle) style = 5; }
		else style = Math.max(1, Math.min(5, index+1));
		if(table!=tableStyle || outline!=outlineStyle){
			tableStyle = table;
			outlineStyle = outline;
			selectedRows = new int[0];
			selectedColumn = 0;
			selectedPath = "";
		}
		if(parent instanceof OCardBase) ((OCardBase)parent).changed = true;
	}
	/** tableStyleの切り替え: 表示中なら部品を作り直す(重なり順はそのまま) */
	void rebuildComponent() {
		if(fld==null) return;
		PCARD.runOnEDT(() -> {
			JComponent old = getComponent();
			if(old==null || PCARD.pc==null || PCARD.pc.mainPane==null) return;
			java.awt.Container pane = old.getParent();
			int z = pane==null ? -1 : pane.getComponentZOrder(old);
			if(pane!=null) pane.remove(old);
			FieldTable table = table();
			if(table!=null) table.dispose();
			FieldOutline outline = outline();
			if(outline!=null) outline.dispose();
			if(scrollPane!=null && card!=null && card.paneList!=null) card.paneList.remove(scrollPane);
			removeListener();
			fld.fldData = null;
			fld = null;
			scrollPane = null;
			buildOField(this);
			JComponent now = getComponent();
			if(now!=null && now.getParent()!=null && z>=0)
				now.getParent().setComponentZOrder(now, Math.min(z, now.getParent().getComponentCount()-1));
			if(!PCARD.lockedScreen) PCARD.repaintLogicalRect(left, top, width, height);
		});
	}
	/** The Swing component takes a new font on the event thread: set from the script thread, its views were rebuilt while the
	 *  caret's queued repaint measured them (JHUG C.A.P.S. vol.2 "flap": NPE in PlainView.updateMetrics, host null) */
	public void showFont(Font font) {
		MyTextArea c = fld;
		if(c!=null) PCARD.runOnEDT(() -> c.setFont(font));
	}
	/** setLineWrap on the event thread, as showFont */
	public void showLineWrap(boolean wrap) {
		MyTextArea c = fld;
		if(c!=null) PCARD.runOnEDT(() -> c.setLineWrap(wrap));
	}
	public void setTextSize(int in) {
		if(in<1) return;
		textSize = in;
		//HyperCard: textSizeを変えるとtextHeightは新しいサイズの既定値(size + size/3)になる
		textHeight = in + in/3;
		if(fld!=null)
		{
			showFont(OObject.stackFont(textFont, textStyle & 0x03, textSize*PCARD.scale()));
			PCARD.repaintLogicalRect(left, top, width, height);
		}
		refreshTable();
		((OCardBase)parent).changed = true;
	}
	public void setTextHeight(int in) {
		textHeight = in;
		if(fld!=null) fld.repaint();
		refreshTable();
		((OCardBase)parent).changed = true;
	}
	public void setHilite(boolean in) {
		hilite=in;
		if(fld!=null)
		{
			if(hilite){
				fld.setSelectionStart(0);
				fld.setSelectionEnd(fld.getText().length()-1);
			}
			else{
				fld.setSelectionStart(0);
				fld.setSelectionEnd(0);
			}
			if(!PCARD.lockedScreen) {
				PCARD.repaintLogicalRect(left, top, width, height);
			}
		}
		((OCardBase)parent).changed = true;
	}
	public void setTextFont(String in) {
		textFont = in;
		if(fld != null){
			showFont(OObject.stackFont(textFont, textStyle & 0x03, textSize*PCARD.scale()));
		}
		refreshTable();
		if(!PCARD.lockedScreen) {
			PCARD.repaintLogicalRect(left, top, width, height);
		}
		((OCardBase)parent).changed = true;
	}
	public void setWideMargins(boolean in) {
		wideMargins = in;
		applyMargin();
		if(fld!=null) fld.repaint();
		if(parent instanceof OCardBase) ((OCardBase)parent).changed = true;
	}
	void applyMargin() {
		if(fld==null) return;
		int margin = textMargin(PCARD.scale(), wideMargins);
		int topMargin = textTopMargin(PCARD.scale(), wideMargins);
		fld.setMargin(new Insets(topMargin, margin, topMargin, margin));
	}
	/**
	 * HyperCardのフィールドは、rectの左から3px・上から1pxの位置にテキストを置く(右端はrect.right-4)。
	 * 以前は余白0で描いていたため、実機より3px左・1px上にずれ、隣接するフィールド同士の
	 * テキストが重なることがあった。
	 */
	static int textMargin(double scale, boolean wide) {
		return (int)Math.round((wide ? 8 : 3)*Math.max(1, scale));
	}
	/** The first line's top below rect.top: 1, 5 with wide margins (HyperCard J-2.3's TextEdit destRect for a field). */
	static int textTopMargin(double scale, boolean wide) {
		return (int)Math.round((wide ? 5 : 1)*Math.max(1, scale));
	}
	/** A fixedLineHeight field's line height: textHeight, at least textSize (HyperCard J-2.3 raises a smaller textHeight to it). */
	int fixedRowHeight() {return Math.max(1, Math.max(textHeight, textSize));}
	/** The baseline below a fixed line's top: HyperCard J-2.3 gives TextEdit textHeight - textHeight div 4 (20 -> 15, 13 -> 10, 25 -> 19). */
	static int fixedLineAscent(int lineHeight) {return lineHeight-lineHeight/4;}
	/**
	 * {ascent, descent+leading} of the classic bitmap strikes HyperCard measured lines with (FontInfo of the real suitcases:
	 * the System's Geneva/Chicago/Monaco, the Geneva, Helvetica and Osaka suitcases), by family and point size; the host
	 * fonts standing in for them differ by a pixel or two (Geneva 12: 16 classic, 15 in Java; Osaka 12: 16, Hiragino 11+6).
	 */
	private static final java.util.Map<String,int[][]> CLASSIC_METRICS = new java.util.HashMap<String,int[][]>();
	static {
		CLASSIC_METRICS.put("geneva", new int[][]{{9,10,2},{10,10,3},{12,12,4},{14,14,5},{18,18,5},{20,20,4},{24,22,7}});
		CLASSIC_METRICS.put("chicago", new int[][]{{12,12,4}});
		CLASSIC_METRICS.put("monaco", new int[][]{{9,9,2}});
		CLASSIC_METRICS.put("helvetica", new int[][]{{9,8,3},{10,9,3},{12,10,4},{14,11,4},{18,14,4},{24,20,5}});
		CLASSIC_METRICS.put("osaka", new int[][]{{9,10,2},{10,11,3},{12,12,4},{14,14,5}});
	}
	private static int[] classicMetrics(String font, int size) {
		//no font name: a field made here (create field) - HyperCard gives a new field Geneva
		int[][] sizes = CLASSIC_METRICS.get(font==null || font.isEmpty() ? "geneva" : font.toLowerCase(java.util.Locale.ROOT));
		if(sizes!=null) for(int[] e : sizes) if(e[0]==size) return new int[]{e[1], e[2]};
		return null;
	}
	/**
	 * A line's height without a fixed line height: its font's ascent+descent+leading (HyperCard's GetFontInfo) - the classic
	 * strike's where known, else Osaka's classic proportions (ascent = size; Hiragino carries a leading the bitmap Osaka never
	 * had), else the host font's. {@code size} is in drawn pixels ({@code scale} times the point size).
	 */
	static int naturalLineHeight(String font, int size, int scale, java.awt.FontMetrics fm) {
		scale = Math.max(1, scale);
		int[] m = classicMetrics(font, size/scale);
		if(m!=null) return (m[0]+m[1])*scale;
		if(isOsaka(font)) return naturalAscent(font, size, scale, fm)+FullButtonName.drop(font, Math.max(1, size/scale), fm)*scale;
		return fm.getAscent()+fm.getDescent()+Math.max(0, fm.getLeading());
	}
	/** A font's ascent as HyperCard lays lines out: the classic strike's (Osaka 12 -> 12; Hiragino's own is 11), Osaka's size, or the host font's. */
	static int naturalAscent(String font, int size, int scale, java.awt.FontMetrics fm) {
		scale = Math.max(1, scale);
		int[] m = classicMetrics(font, size/scale);
		if(m!=null) return m[0]*scale;
		return isOsaka(font) ? size : fm.getAscent();
	}
	private static boolean isOsaka(String font) {return font!=null && font.equalsIgnoreCase("Osaka");}
	private static final java.awt.Graphics2D METRICS = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB).createGraphics();
	/** The distance between the field's lines in card pixels, as drawn: textHeight when fixed, else the font's own line height. */
	int lineHeight() {
		if(fixedLineHeight) return fixedRowHeight();
		java.awt.FontMetrics fm = METRICS.getFontMetrics(OObject.stackFont(textFont, textStyle&0x03, textSize));
		return Math.max(1, naturalLineHeight(textFont, textSize, 1, fm));
	}

	static class TextStyleSpec {
		String font;
		int size;
		int style;
		TextStyleSpec(String font, int size, int style){
			this.font=font; this.size=size; this.style=style;
		}
	}

	TextStyleSpec textStyleAt(int characterPosition) {
		TextStyleSpec result = new TextStyleSpec(textFont, textSize, textStyle);
		if(styleList==null || card==null || card.stack==null) return result;
		int offset = styleOffset(getText(), characterPosition);
		//位置を含む最後の区間だけが効く。区間の-1(指定なし)はフィールドの設定(前の区間の値ではない)
		styleClass current = null;
		for(styleClass run : styleList){
			if(run.textPosition>offset) continue;
			if(current==null || run.textPosition>=current.textPosition) current = run;
		}
		if(current==null) return result;
		for(OStack.styleClass entry : card.stack.styleList){
			if(entry.id!=current.styleId) continue;
			if(entry.font!=-1){
				for(OStack.fontClass fontEntry : card.stack.fontList){
					if(fontEntry.id==entry.font){ result.font=fontEntry.name; break; }
				}
			}
			if(entry.size!=-1) result.size=entry.displaySize();
			if(entry.style!=-1) result.style=entry.style;
			break;
		}
		return result;
	}

	void applyTextStyle(int start, int end, String font, int size, int style) {
		String text = getText();
		start = Math.max(0, Math.min(start, text.length()));
		end = Math.max(start, Math.min(end, text.length()));
		if(start==end || card==null || card.stack==null) return;
		TextStyleSpec after = textStyleAt(end);
		int startOffset = styleOffset(text, start);
		int endOffset = styleOffset(text, end);
		if(styleList==null) styleList = new ArrayList<styleClass>();
		styleList.removeIf(run -> run.textPosition>=startOffset && run.textPosition<=endOffset);
		styleList.add(new styleClass(startOffset,
			card.stack.addCompleteTextStyle(font, size, style)));
		if(end<text.length()) styleList.add(new styleClass(endOffset,
			card.stack.addCompleteTextStyle(after.font, after.size, after.style)));
		styleList.sort((a,b) -> Integer.compare(a.textPosition, b.textPosition));
		//非共有bgフィールドなら、カード側のスタイルも差し替える
		//(上でstyleListを新しく作った場合、以前の参照共有では追従しない)
		OBgFieldData data = cardFieldData(true);
		if(data!=null){ data.styleList = styleList; data.card.changed = true; }
		useMyDraw = true;
		if(fld!=null) fld.repaint();
		if(parent instanceof OCardBase) ((OCardBase)parent).changed = true;
	}

	/** Field-level set of textFont/textSize/textStyle: the chunk runs stop overriding that attribute
	 *  (HyperCard: "set the textStyle of fld to plain" clears every run's style, fonts and sizes stay). */
	void clearRunAttribute(String which) {
		if(styleList==null || card==null || card.stack==null) return;
		ArrayList<styleClass> updated = new ArrayList<styleClass>();
		for(styleClass run : styleList){
			OStack.styleClass entry = null;
			for(OStack.styleClass e : card.stack.styleList) if(e.id==run.styleId){ entry = e; break; }
			if(entry==null){ updated.add(run); continue; }
			int font = entry.font, size = entry.size, style = entry.style;
			if(which.equals("font")) font = -1;
			else if(which.equals("size")) size = -1;
			else style = -1;
			if(font==-1 && size==-1 && style==-1) continue;
			updated.add(new styleClass(run.textPosition, card.stack.addRawTextStyle(style, font, size)));
		}
		styleList = updated.isEmpty() ? null : updated;
		OBgFieldData data = cardFieldData(false);
		if(data!=null){ data.styleList = styleList; data.card.changed = true; }
		if(fld!=null) fld.repaint();
	}

	/**
	 * styleList.textPositionの単位。以前はここで1文字を「255以下なら1、それ以外は2」と数えた
	 * 疑似バイト位置にしていたが、HCData.readContent(インポート)とHCExport(エクスポート)は
	 * Javaの文字列インデックスで読み書きしており、単位が食い違っていた(インポートした
	 * 「所持金：」末尾の空のスタイルランが「金：」に掛かって描画される等)。HyperZebraの
	 * チャンクは文字単位なので、スタイルランの位置も文字インデックスに統一する。
	 */
	static int styleOffset(String text, int characterPosition) {
		return Math.max(0, Math.min(text==null ? 0 : text.length(), characterPosition));
	}
	static boolean drawsUnderline(int style, boolean showGroups) {
		return (style&4)>0 || ((style&128)>0 && showGroups);
	}

	void preserveStylesForTextChange(String newText) {
		if(styleList==null || styleList.isEmpty()) return;
		String oldText = getText();
		int prefix=0;
		while(prefix<oldText.length() && prefix<newText.length()
				&& oldText.charAt(prefix)==newText.charAt(prefix)) prefix++;
		int oldSuffix=oldText.length();
		int newSuffix=newText.length();
		while(oldSuffix>prefix && newSuffix>prefix
				&& oldText.charAt(oldSuffix-1)==newText.charAt(newSuffix-1)){
			oldSuffix--; newSuffix--;
		}
		int startOffset=styleOffset(oldText, prefix);
		int oldEndOffset=styleOffset(oldText, oldSuffix);
		int newEndOffset=styleOffset(newText, newSuffix);
		int delta=newEndOffset-oldEndOffset;
		styleList.removeIf(run -> run.textPosition>=startOffset && run.textPosition<oldEndOffset);
		for(styleClass run : styleList){
			if(run.textPosition>=oldEndOffset) run.textPosition+=delta;
		}
		styleList.sort((a,b) -> Integer.compare(a.textPosition, b.textPosition));
	}

	public void setColor(Color col) {
		color=col;
		if(fld!=null)
		{
			fld.setForeground(col);
			fld.repaint();
		}
		refreshTable();
	}
	public void setBgColor(Color col) {
		bgColor=col;
		if(fld!=null)
		{
			fld.setBackground(col);
			fld.repaint();
		}
		refreshTable();
	}
	

	public OField(OCardBase cd, int fldId) {
    	objectType="field";
		card = cd;
		parent = cd;
		scriptList = new ArrayList<String>();

		width=64; height=64;
		id=fldId;
	}
	

	@SuppressWarnings("unchecked")
	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
		boolean firstSelectedInt = true;
		this.textStyle=0;
		
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamReader.START_ELEMENT) {
	            	String elm = reader.getLocalName().intern();
	            	if(elm.equals("id")){ this.id = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("visible")){ this.setVisible(XMLRead.bool(reader));reader.next(); }
	            	else if(elm.equals("dontWrap")){ this.dontWrap = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("dontSearch")){ this.dontSearch = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("sharedText")){ this.sharedText = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("fixedLineHeight")){ this.fixedLineHeight = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("autoTab")){ this.autoTab = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("lockText")){ this.enabled = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("rect")){
	            		int right=0, bottom=0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XMLStreamReader.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("left")){
	    		            		this.left = Integer.valueOf(reader.getElementText());
	    		            		if(this.left>=32768) this.left -= 65536;
	    		            	}
	    		            	else if(elm2.equals("top")){
	    		            		this.top = Integer.valueOf(reader.getElementText());
	    		            		if(this.top>=32768) this.top -= 65536;
	    		            	}
	    		            	else if(elm2.equals("right")){ right = Integer.valueOf(reader.getElementText()); }
	    		            	else if(elm2.equals("bottom")){ bottom = Integer.valueOf(reader.getElementText()); }
	    		            	else System.out.println("Part_Type: " + reader.getLocalName());
	    	            	}
	    	            	else if (eventType2 == XMLStreamReader.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("rect")){
	    		            		this.width = right - this.left;
	    		            		this.height = bottom - this.top;
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            	}
	            	else if(elm.equals("color") || elm.equals("bgColor")){
    	            	int red=0, green=0, blue=0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XMLStreamReader.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("red")){ red = Integer.valueOf(reader.getElementText());}
	    		            	else if(elm2.equals("green")){ green = Integer.valueOf(reader.getElementText());}
	    		            	else if(elm2.equals("blue")){ blue = Integer.valueOf(reader.getElementText()); }
	    		            	else System.out.println("Part_Type: " + reader.getLocalName());
	    	            	}
	    	            	else if (eventType2 == XMLStreamReader.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("color")){
	    		            		this.color = new Color(red/256, green/256, blue/256);
	    		            		break;
	    		            	}
	    		            	if(elm2.equals("bgColor")){
	    		            		this.bgColor = new Color(red/256, green/256, blue/256);
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            	}
	            	else if(elm.equals("style")){
	            		String tmpstr = reader.getElementText();
						if(0==tmpstr.compareTo("standard")) this.style=0;
						if(0==tmpstr.compareTo("transparent")) this.style=1;
						if(0==tmpstr.compareTo("opaque")) this.style=2;
						if(0==tmpstr.compareTo("rectangle")) this.style=3;
						if(0==tmpstr.compareTo("shadow")) this.style=4;
						if(0==tmpstr.compareTo("scrolling")) this.style=5;
	            	}
	            	else if(elm.equals("autoSelect")){ this.autoSelect = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("showLines")){ this.showLines = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("wideMargins")){ this.wideMargins = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("multipleLines")){ this.multipleLines = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("tableStyle")){ this.tableStyle = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("outlineStyle")){ this.outlineStyle = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("outlineLevel")){ try{ this.outlineLevel = Integer.parseInt(reader.getElementText().trim()); }catch(NumberFormatException e){} }
	            	else if(elm.equals("sortable")){ this.sortable = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("gridLines")){ this.gridLines = XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("columnWidths")){ this.columnWidths = reader.getElementText(); }
	            	else if(elm.equals("columnNames")){ this.columnNames = reader.getElementText(); }
	            	else if(elm.equals("reservedFamily")){ this.reservedFamily = reader.getElementText(); }
	            	else if(elm.equals("titleWidth")){ this.reservedTitleWidth = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("icon")){ this.reservedIcon = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textAlign")){
	            		String alignStr = reader.getElementText();
	            		if(alignStr.equals("left")) this.textAlign = 0;
	            		else if(alignStr.equals("center")) this.textAlign = 1;
	            		else if(alignStr.equals("right")) this.textAlign = 2;
	            	}
	            	else if(elm.equals("textFontID")){
	            		int textFontID = Integer.valueOf(reader.getElementText());
            			OStack stack = ((OCardBase)this.parent).stack;
            			for(int i=0; i<stack.fontList.size();i++){
            				if(stack.fontList.get(i).id ==textFontID){
            					textFont = stack.fontList.get(i).name;
            					break;
            				}
            			}
	            	}
	            	else if(elm.equals("textSize")){ this.textSize = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textHeight")){ this.textHeight = Integer.valueOf(reader.getElementText()); }
	            	else if(elm.equals("textStyle")){ 
	            		String tmpstr=reader.getElementText();
						if(tmpstr.contains("plain")) this.textStyle=0;
						if(tmpstr.contains("bold")) this.textStyle+=1;
						if(tmpstr.contains("italic")) this.textStyle+=2;
						if(tmpstr.contains("underline")) this.textStyle+=4;
						if(tmpstr.contains("outline")) this.textStyle+=8;
						if(tmpstr.contains("shadow")) this.textStyle+=16;
						if(tmpstr.contains("condensed")) this.textStyle+=32;
						if(tmpstr.contains("extend")) this.textStyle+=64;
						if(tmpstr.contains("group")) this.textStyle+=128;
	            	}
	            	else if(elm.equals("selectedLines")){ 
	            		//this.selectedLine = Integer.valueOf(reader.getElementText());
	            	}
	            	else if(elm.equals("integer")){ 
	            		if(firstSelectedInt){
	            			this.selectedStart = Integer.valueOf(reader.getElementText());
		            		this.selectedEnd = this.selectedStart;
	            			firstSelectedInt = false;
	            		}
	            		else{
	            			this.selectedEnd = Integer.valueOf(reader.getElementText());
	            		}
	            	}
	            	else if(elm.equals("styleRuns")){
	            		ArrayList<styleClass> runs = new ArrayList<styleClass>();
	            		int runTextPosition = 0, runStyleId = 0;
	    	            while(reader.hasNext()){
	    	            	int eventType2 = reader.next();
	    	            	if (eventType2 == XMLStreamReader.START_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("textPosition")){ runTextPosition = Integer.valueOf(reader.getElementText()); }
	    		            	else if(elm2.equals("styleId")){ runStyleId = Integer.valueOf(reader.getElementText()); }
	    	            	}
	    	            	else if (eventType2 == XMLStreamReader.END_ELEMENT) {
	    		            	String elm2 = reader.getLocalName();
	    		            	if(elm2.equals("run")){
	    		            		runs.add(new styleClass(runTextPosition, runStyleId));
	    		            	}
	    		            	else if(elm2.equals("styleRuns")){
	    		            		break;
	    		            	}
	    		            }
	    	            }
	            		this.styleList = runs;
	            	}
	            	else if(elm.equals("reserved35")){ reserved35 = reader.getElementText(); }
	            	else if(elm.equals("name")){ 
	            		name = reader.getElementText();
	            	}
				else if(elm.equals("panel")){ panelId = XMLRead.panelId(reader.getElementText()); }
	            	else if(elm.equals("osaScript")) osaScript = OsaScript.readXML(reader);
	            	else if(elm.equals("script"))
	            	{
	            		String scrStr = reader.getElementText();
	            		//末尾の空行を保つため、limit引数に-1を指定する
	            		String[] scriptAry = scrStr.split("\n", -1);
	            		for(int i=0; i<scriptAry.length; i++)
	            		{
	            			scriptList.add(scriptAry[i]);
	            		}
	            		stringList = new ArrayList[scriptList.size()];
	            		typeList = new ArrayList[scriptList.size()];
	            	}
	            	else if(elm.equals("true")); //dummy 
	            	else if(elm.equals("false")); //dummy
	            	else if(elm.equals("part")); //dummy for clipboard
	            	else if(elm.equals("type")); //dummy for clipboard
	            	else if(elm.equals("autoHighlight")){ XMLRead.bool(reader);reader.next(); }
	            	else if(elm.equals("lastSelectedLine")){  }
	            	else if(elm.equals("selectedLine")){ 
	            		this.selectedLine = Integer.valueOf(reader.getElementText()); }
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamReader.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("part")){
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
	        }
	    }
	    return reader;
	}

	
	public void writeXML(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("part");
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("id");
        writer.writeCharacters(Integer.toString(id));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("type");
        writer.writeCharacters("field");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("visible");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(getVisible())+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("dontWrap");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(dontWrap)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("dontSearch");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(dontSearch)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("sharedText");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(sharedText)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("fixedLineHeight");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(fixedLineHeight)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("autoTab");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(autoTab)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("lockText");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(enabled)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("rect");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("left");
            writer.writeCharacters(Integer.toString(left));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("top");
            writer.writeCharacters(Integer.toString(top));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("right");
            writer.writeCharacters(Integer.toString(left+width));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("bottom");
            writer.writeCharacters(Integer.toString(top+height));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("style");
        switch(style){
        case 0: writer.writeCharacters("standard");break;
        case 1: writer.writeCharacters("transparent");break;
        case 2: writer.writeCharacters("opaque");break;
        case 3: writer.writeCharacters("rectangle");break;
        case 4: writer.writeCharacters("shadow");break;
        case 5: writer.writeCharacters("scrolling");break;
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("autoSelect");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(autoSelect)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("showLines");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(showLines)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("wideMargins");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(wideMargins)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("multipleLines");
        writer.writeCharacters(" ");
        writer.writeEmptyElement(Boolean.toString(multipleLines)+" ");
        writer.writeCharacters(" ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        //表(tableStyle)のプロパティ。既定値のままなら書かない(表でないフィールドの.hzbは今まで通り)
        if(tableStyle || !sortable || !gridLines || !columnWidths.isEmpty() || !columnNames.isEmpty()){
        	String[][] flags = {{"tableStyle", Boolean.toString(tableStyle)}, {"sortable", Boolean.toString(sortable)}, {"gridLines", Boolean.toString(gridLines)}};
        	for(String[] flag : flags){
		        writer.writeStartElement(flag[0]);
		        writer.writeCharacters(" ");
		        writer.writeEmptyElement(flag[1]+" ");
		        writer.writeCharacters(" ");
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t");
        	}
	        writer.writeStartElement("columnWidths");
	        writer.writeCharacters(columnWidths);
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
	        writer.writeStartElement("columnNames");
	        writer.writeCharacters(columnNames);
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }

        //アウトライン(outlineStyle)。既定値のままなら書かない
        if(outlineStyle || outlineLevel!=1){
	        writer.writeStartElement("outlineStyle");
	        writer.writeCharacters(" ");
	        writer.writeEmptyElement(Boolean.toString(outlineStyle)+" ");
	        writer.writeCharacters(" ");
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
	        writer.writeStartElement("outlineLevel");
	        writer.writeCharacters(Integer.toString(outlineLevel));
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }

       /* writer.writeStartElement("reservedFamily");
        writer.writeCharacters(" 0 ");
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");*/

    	if(selectedLine>0){
	        writer.writeStartElement("selectedLines");
	        writer.writeCharacters("\n\t\t\t");
	        {
		        writer.writeCharacters("\t");
		        writer.writeStartElement("integer");
		        writer.writeCharacters(Integer.toString(selectedLine));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t");
	        }
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
    	}

        writer.writeStartElement("textAlign");
        switch(textAlign){
        case 0: writer.writeCharacters("left");break;
        case 1: writer.writeCharacters("center");break;
        case 2: writer.writeCharacters("right");break;
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        int textFontID = 0;
        for(int i=0; i<((OCardBase)parent).stack.fontList.size(); i++){
        	OStack.fontClass fontinfo = ((OCardBase)parent).stack.fontList.get(i);
        	if(fontinfo.name.equals(textFont)){
        		textFontID = fontinfo.id;
        		break;
        	}
        }
        writer.writeStartElement("textFontID");
        writer.writeCharacters(Integer.toString(textFontID));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("textSize");
        writer.writeCharacters(Integer.toString(textSize));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        if(textStyle==0){
            writer.writeStartElement("textStyle");
            writer.writeCharacters("plain");
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t");
        }
        else{
        	if((textStyle&1)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("bold");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&2)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("italic");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&4)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("underline");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&8)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("outline");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&16)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("shadow");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&32)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("condensed");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&64)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("extend");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        	if((textStyle&128)>0){
                writer.writeStartElement("textStyle");
                writer.writeCharacters("group");
                writer.writeEndElement();
                writer.writeCharacters("\n\t\t\t");
        	}
        }

        writer.writeStartElement("textHeight");
        writer.writeCharacters(Integer.toString(textHeight));
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");

        writer.writeStartElement("color");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("red");
            writer.writeCharacters(Integer.toString(color.getRed()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("green");
            writer.writeCharacters(Integer.toString(color.getGreen()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("blue");
            writer.writeCharacters(Integer.toString(color.getBlue()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        writer.writeStartElement("bgColor");
        writer.writeCharacters("\n\t\t\t\t");
        {
            writer.writeStartElement("red");
            writer.writeCharacters(Integer.toString(bgColor.getRed()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("green");
            writer.writeCharacters(Integer.toString(bgColor.getGreen()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
            
            writer.writeStartElement("blue");
            writer.writeCharacters(Integer.toString(bgColor.getBlue()*256));
            writer.writeEndElement();
            writer.writeCharacters("\n\t\t\t\t");
        }
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
        
        //文字単位のスタイル(部分的な太字・フォント変更など)。styleList内の
        //各要素は「このテキストオフセットからstack.styleListのこのIDのスタイルが
        //適用される」を表す(HyperCardのスタイル付きコンテンツレコードと同じ形)。
        //これを書き出さないと、.hzbとして保存し直すたびにフィールド内の部分的な
        //文字装飾が失われてしまう。
        if(styleList!=null && !styleList.isEmpty()){
	        writer.writeStartElement("styleRuns");
	        writer.writeCharacters("\n\t\t\t");
	        for(styleClass run: styleList){
		        writer.writeStartElement("run");
		        writer.writeCharacters("\n\t\t\t\t");
		        writer.writeStartElement("textPosition");
		        writer.writeCharacters(Integer.toString(run.textPosition));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t\t");
		        writer.writeStartElement("styleId");
		        writer.writeCharacters(Integer.toString(run.styleId));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t");
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t\t");
	        }
	        writer.writeEndElement();
	        writer.writeCharacters("\n\t\t\t");
        }

        writer.writeStartElement("name");
        writer.writeCharacters(name);
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t\t");
		if(panelId!=0){
			writer.writeStartElement("panel");
			writer.writeCharacters(Integer.toString(panelId));
			writer.writeEndElement();
			writer.writeCharacters("\n\t\t\t");
		}

        writer.writeStartElement("script");
        for(int i=0; i<scriptList.size(); i++){
        	writer.writeCharacters(scriptList.get(i));
        	if(i<scriptList.size()-1){
        		writer.writeCharacters("\n");
        	}
		}
        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");

        if(osaScript!=null) osaScript.writeXML(writer, "\n\t\t"); //HyperCard 2.2+ compiled AppleScript, kept as is (OsaScript)

        writer.writeEndElement();
        writer.writeCharacters("\n\t\t");
	}

	
	//HCのスタックを変換
	@SuppressWarnings("unchecked")
	public boolean readFieldBlock(DataInputStream dis, int partSize){
		//System.out.println("====readFieldBlock====");
		//ブロックのデータを順次読み込み
		int flags = HCData.readCode(dis, 1);
		//System.out.println("flags:"+flags);
		setVisible(((flags>>7)&0x01)==0);
		dontWrap = ((flags>>5)&0x01)!=0;
		dontSearch = ((flags>>4)&0x01)!=0;
		sharedText = ((flags>>3)&0x01)!=0;
		fixedLineHeight = ! (((flags>>2)&0x01)!=0);
		autoTab = ((flags>>1)&0x01)!=0;
		enabled = (((flags>>0)&0x01)!=0);
		top = HCData.readCode(dis, 2);
		//System.out.println("top:"+top);
		left = HCData.readCode(dis, 2);
		//System.out.println("left:"+left);
		int bottom = HCData.readCode(dis, 2);
		//System.out.println("bottom:"+bottom);
		height = bottom - top;
		int right = HCData.readCode(dis, 2);
		//System.out.println("right:"+right);
		width = right - left;
		int flags2 = HCData.readCode(dis, 1);
		autoSelect = ((flags2>>7)&0x01)!=0;
		showLines = ((flags2>>6)&0x01)!=0;
		wideMargins = ((flags2>>5)&0x01)!=0;
		multipleLines = ((flags2>>4)&0x01)!=0;
		//group = (flags2)&0x0F;
		int style = HCData.readCode(dis, 1);
		//0標準 1透明 2不透明 3長方形 4シャドウ 5丸みのある長方形 6省略時設定 7楕円 8ポップアップ 9チェックボックス 10ラジオ
		switch(style){
		case 0: this.style = 1; break;//transparent
		case 1: this.style = 2; break;//opaque
		case 2: this.style = 3; break;//rectangle
		//case 3: this.style = 5; break;//roundRect
		case 4: this.style = 4; break;//shadow
		//case 5: this.style = 9; break;//checkBox
		//case 6: this.style = 10; break;//radioButton
		case 7: this.style = 5; break;//scrolling
		//case 8: this.style = 0; break;//standard
		//case 9: this.style = 6; break;//default
		//case 10: this.style = 7; break;//oval
		//case 11: this.style = 8; break;//popup
		}
		selectedEnd = HCData.readCode(dis, 2);
		//System.out.println("selectedEnd:"+selectedEnd);
		selectedStart = HCData.readCode(dis, 2);
		//System.out.println("selectedStart:"+selectedStart);
		selectedLine = selectedStart;
		int inTextAlign = HCData.readCode(dis, 2);
		switch(inTextAlign){
		case 0: textAlign = 0; break;
		case 1: textAlign = 1; break;
		case -1: textAlign = 2; break;
		}
		//System.out.println("inTextAlign:"+inTextAlign);
		int textFontID = HCData.readCode(dis, 2);
		//System.out.println("textFontID:"+textFontID);
		textSize = HCData.readCode(dis, 2);
		//System.out.println("textSize:"+textSize);
		textStyle = HCData.readCode(dis, 1);
		//System.out.println("textStyle:"+textStyle);
		/*int filler =*/ HCData.readCode(dis, 1);
		//System.out.println("filler:"+filler);
		textHeight = HCData.readCode(dis, 2);
		//System.out.println("textHeight:"+textHeight);
		resultStr nameResult = HCData.readTextToZero(dis, partSize - 30);
		name = nameResult.str;
		//System.out.println("name:"+name);
		//HCStackDebug.debuginfo("name:"+name);
		/*int filler2 =*/ //HCData.readCode(dis, 1);
		//System.out.println("filler2:"+filler2);
		//HyperCard 2.2+: an AppleScript (OSA) script is source text + NUL + a compiled 'WOSA' record after the name's padding byte
		resultStr scriptResult = HCData.readOsaScript(dis, partSize - 30 - nameResult.length_in_src, 1);
		if(scriptResult!=null) osaScript = scriptResult.osa;
		else scriptResult = HCData.readPartScript(dis, partSize - 30 - nameResult.length_in_src);
		String scriptStr = scriptResult.str;
		//System.out.println("scriptStr:"+scriptStr);

		//フォント名をテーブルから検索
		OStack stack = ((OCardBase)this.parent).stack;
		for(int i=0; i<stack.fontList.size();i++){
			if(stack.fontList.get(i).id ==textFontID){
				textFont = stack.fontList.get(i).name;
				break;
			}
		}

		int remainLength = partSize - (30+(nameResult.length_in_src)+(scriptResult.length_in_src));
		//System.out.println("remainLength:"+remainLength);
		//HCStackDebug.debuginfo("remainLength:"+remainLength);
		if(remainLength<0 || remainLength > 10){
			//System.out.println("!");
		}
		if(remainLength>0){
			/*String padding =*/ HCData.readStr(dis, remainLength);
			//System.out.println("padding:"+padding);
			//HCStackDebug.debuginfo("padding:"+padding);
		}
		/*if((nameResult.length_in_src+1+scriptResult.length_in_src+1)%2 != 0){
			String padding = HCData.readStr(dis, 1);
			System.out.println("padding:"+padding);
		}*/

		//スクリプト
		//末尾の空行を保つため、limit引数に-1を指定する(OCard.javaの
		//readCardBlock参照)
		String[] scriptAry = scriptStr.split("\n", -1);
		for(int i=0; i<scriptAry.length; i++)
		{
			scriptList.add(scriptAry[i]);
		}
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		
		return true;
	}
	
	
	static void buildOField(OField ofld)
	{
		//swingのテキストコンポーネントを追加
		ofld.fld = new MyTextArea(ofld.getText());

		ofld.fld.fldData = ofld;
		ofld.addListener();

		//上のnew MyTextArea(ofld.getText())で既にテキストは設定済みであり、ここでの
		//setTextは本来不要な二重設定だった。しかしこの時点では直前のaddListener()で
		//ドキュメントリスナー(GUI_fldDocument)が既に取り付けられているため、この
		//setTextが「全文削除→全文挿入」のドキュメントイベントを発生させてしまい、
		//GUI_fldDocument.changedUpdate→preserveStylesForTextChangeが呼ばれて
		//フィールドのstyleList(部分的な文字装飾)がカードを開く/表示するたびに
		//消えてしまっていた(シャドウなどのスタイルが表示上だけ消える不具合の原因)。
		ofld.fld.setForeground(ofld.color);
		ofld.fld.setBackground(ofld.bgColor);
		ofld.fld.setBounds(ofld.left, ofld.top, ofld.width, ofld.height); 
		//ofld.fld.setSelectionColor(ofld.selectColor);
		
		//the host's family for the font, or its stand-in (a font only the stack's own FONT strike has, as Uli Kusterer's Sempach,
		//is drawn with the host's default face: card text is never drawn from bitmap strikes)
		ofld.fld.setFont(OObject.stackFont(ofld.textFont, ofld.textStyle, ofld.textSize*PCARD.scale()));
		if(ofld.textHeight==0){
			ofld.textHeight = ofld.fld.getLineHeight();
		}
		ofld.fld.setVisible(ofld.isShown());
		//lockTextだけでなく、現在のツールとピーク状態も考慮して決める
		//(フィールドツール選択中にカードが作り直されても、テキストが
		//選択できる状態に戻ってしまわないようにする)
		ofld.updateTextEditable();
		ofld.fld.setLineWrap(!ofld.dontWrap);
		//while it is edited Swing lays the text out: at word breaks, as the own drawing and HyperCard wrap (Parts of Speech's
		//typed "between, down" broke as "do" / "wn")
		ofld.fld.setWrapStyleWord(true);
		if(ofld.selectedStart>0 && ofld.selectedEnd>0) {
			ofld.fld.setSelectionStart(ofld.selectedStart);
			ofld.fld.setSelectionEnd(ofld.selectedEnd);
		}
		Style s = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
		switch(ofld.textAlign){
			case 0:break;
			case 1:StyleConstants.setAlignment(s, StyleConstants.ALIGN_CENTER);break;
			case 2:StyleConstants.setAlignment(s, StyleConstants.ALIGN_RIGHT);break;
		}
		StyleConstants.setComponent(s, ofld.fld);
		//(0標準) 1透明 2不透明 3長方形 4シャドウ 5スクロール
		switch(ofld.style) {
		case 1:
			ofld.fld.setOpaque(false);
			break;
		case 2:
			ofld.fld.setBorder(new EmptyBorder(1,1,1,1));
			break;
		case 3:
			ofld.fld.setBorder(new RectangleFieldBorder(ofld));
			break;
		case 4:
			//枠と影はHyperCardと同じ形(ShadowFieldBorder)。影の欠けた角からはカードが見えるので不透明にしない
			ofld.fld.setBorder(new ShadowFieldBorder(ofld));
			ofld.fld.setOpaque(false);
			break;
		} 
		if(ofld.autoSelect) ofld.fld.setOpaque(false);
		ofld.applyMargin();

		if(ofld.outlineStyle) {
			//アウトライン: 表と同じく、テキストはMyTextAreaに持たせたまま木を部品として置く
			FieldOutline outline = new FieldOutline(ofld);
			outline.setBounds(ofld.left, ofld.top, ofld.width, ofld.height);
			outline.setVisible(ofld.isShown());
			PCARD.pc.mainPane.add(outline);
			ofld.card.paneList.add(outline);
			ofld.scrollPane = outline;
			MyScrollPane.followScroll(outline, ofld);
		}
		else if(ofld.tableStyle) {
			//表: テキストはMyTextAreaに持たせたまま(画面には出さない)、表を部品として置く
			FieldTable table = new FieldTable(ofld);
			table.setBounds(ofld.left, ofld.top, ofld.width, ofld.height);
			table.setVisible(ofld.isShown());
			PCARD.pc.mainPane.add(table);
			ofld.card.paneList.add(table);
			ofld.scrollPane = table;
			MyScrollPane.followScroll(table, ofld);
		}
		else if(ofld.style<=4) {
			PCARD.pc.mainPane.add(ofld.fld);
		}
		else {
			MyScrollPane scrollpane = new MyScrollPane();
			scrollpane.fldData=ofld;
			scrollpane.setViewportView(ofld.fld);
			scrollpane.setBounds(ofld.left, ofld.top, ofld.width, ofld.height);
			scrollpane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
			scrollpane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			scrollpane.getVerticalScrollBar().setUnitIncrement(Math.max(1, (int)Math.round(Math.max(1, ofld.textHeight)*Math.max(1, PCARD.scale()))));
			scrollpane.setVisible(ofld.isShown());
			PCARD.pc.mainPane.add(scrollpane);
			ofld.card.paneList.add(scrollpane);
			ofld.scrollPane = scrollpane;
			MyScrollPane.followScroll(scrollpane, ofld);
			
    		ofld.fld.revalidate();
		}
	}
    
	
    //テキストを編集・選択できるのは、ブラウズツールで、ユーザーレベル2以上で、かつピーク中でなく、
    //かつフィールド自身がロックされていない(lockTextがオフ=enabledがfalse)
    //ときだけ。
    //・ボタン/フィールドツール選択中はフィールドを「オブジェクトとして」操作する
    //  ので、中のテキストは選択させない。
    //・shift+opt+cmdのピーク中も同様。編集可能なままだとSwingのキャレットが先に
    //  クリックに反応してしまい、スクリプトエディタを開く操作にならない。
    //enabled(=lockTextの代用)自体は変更せず、表示上の編集可否だけを切り替える。
    void updateTextEditable(){
		if(fld==null) return;
		//ユーザーレベル1(ブラウズ)ではロックされていないフィールドにも入力できない(HyperCard)
		boolean editable = !enabled && PCARD.editMode==0 && AuthTool.tool==null && StackProtection.effectiveUserLevel()>=2;
		fld.setEditable(editable);
		fld.setFocusable(editable);
		//表・アウトラインはテキスト欄を画面に出さないので、部品の方にも入力の可否を知らせる
		//(フィールドツールで作り直された部品は、ブラウズツールに戻っても入力できないままだった)
		FieldTable table = table();
		if(table!=null) PCARD.runOnEDT(table::editableChanged);
		FieldOutline outline = outline();
		if(outline!=null) PCARD.runOnEDT(outline::editableChanged);
	}
    /** Whether a table's cells or an outline's items can be typed into now: unlocked, the browse tool (no paint tool either),
     *  user level 2 or more (HyperCard: level 1 types nothing). */
    boolean textEditable(){
		return !enabled && PCARD.editMode==0 && AuthTool.tool==null && (PCARD.pc==null || PCARD.pc.tool==null)
			&& StackProtection.effectiveUserLevel()>=2;
	}

    //現在のカードと背景の全フィールドに、上のルールを適用し直す。
    //ツールの切り替え時とピークの開始・終了時に呼ぶ。
    static void updateAllTextEditable(){
		if(PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return;
		for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
			PCARD.pc.stack.curCard.fldList.get(i).updateTextEditable();
		}
		if(PCARD.pc.stack.curCard.bg!=null){
			for(int i=0; i<PCARD.pc.stack.curCard.bg.fldList.size(); i++){
				PCARD.pc.stack.curCard.bg.fldList.get(i).updateTextEditable();
			}
		}
	}

    void addListener(){
		//ボタンと同様、生成時とブラウズツールへの復帰時の両方から呼ばれるため、
		//外さずに追加すると同じリスナーが二重に付いてしまう
		removeListener();
		if(GUI.editingTool()) return; //ツール中はブラウズツールへ戻るときに付け直す
		fld.addMouseListener(card.stack.GUI_fld);
		fld.addMouseMotionListener(card.stack.GUI_fld);
		docListener = new GUI_fldDocument(this);
		fld.getDocument().addDocumentListener(docListener);
	}

    void removeListener(){
		if(fld==null || card==null) return; //a field of a card still being built
		fld.removeMouseListener(card.stack.GUI_fld);
		//ここはremoveでなければならない。addになっていたため、ツールを切り替える
		//たびにモーションリスナーが増え続けていた
		fld.removeMouseMotionListener(card.stack.GUI_fld);
		fld.getDocument().removeDocumentListener(docListener);
		docListener = null;
	}
    
    static fldOutlineListen fldOutlineListen = new fldOutlineListen();
    void addListener(MouseListener listener){
		fld.addMouseListener(listener);
    }
    void removeListener(MouseListener listener){
    	if(fld==null) return; //a field of a card still being built (choose a tool while doMenu "New Card" is under way)
    	fld.removeMouseListener(listener);
    }
    void addMotionListener(MouseMotionListener listener){
		fld.addMouseMotionListener(listener);
    }
    void removeMotionListener(MouseMotionListener listener){
    	if(fld==null) return;
    	fld.removeMouseMotionListener(listener);
    }

    //フィールド自身のローカル座標(コンポーネント左上起点、物理ピクセル。
    //MouseEventのe.getX()/e.getY()と同じ座標系)から、その位置にある文字の
    //インデックス(0起点、text.charAt(pos)がクリックされた文字)を求める。
    //paintComponentが実際に描画した位置(hitBoxes)を使うため、折り返しや
    //スタイル混在、独自フォント(ピクチャフォント)、拡大表示(2倍表示モード)
    //などが絡んでも、実際に見えている文字と一致する。該当する文字が無ければ-1。
    //結果は {文字のインデックス, その文字より右なら1, その文字の行より下に何行分(行の高さで数えて)}
    static int[] hitTestClick(OField field, int localX, int localY){
    	int[] r = {-1, 0, 0};
    	if(field==null || field.fld==null) return r;
    	String now = field.fld.getText();
    	if(field.fld.hitBoxesText==null || !field.fld.hitBoxesText.equals(now)) field.fld.refreshHitBoxes();
    	java.util.ArrayList<int[]> boxes = field.fld.hitBoxes;
    	if(boxes==null || boxes.isEmpty()) return r;
    	long bestScore = Long.MAX_VALUE;
    	for(int[] b : boxes){
    		int x=b[1], y=b[2], w=b[3], h=b[4];
    		int dx = 0, dy = 0;
    		if(localX<x) dx = x-localX;
    		else if(localX>=x+w) dx = localX-(x+w)+1;
    		if(localY<y) dy = y-localY;
    		else if(localY>=y+h) dy = localY-(y+h)+1;
    		//まず行(縦方向)が合っているものを優先し、その中でより近い(横方向の)
    		//ものを選ぶ。どの行にも入らない(テキストより下)ときは一番近い行の中で選ぶ
    		long score = ((long)(dy==0?0:1)<<40) + ((long)dy<<24) + (long)dx*dx;
    		if(score<bestScore){
    			bestScore = score;
    			r[0] = b[0];
    			r[1] = localX>=x+w ? 1 : 0;
    			r[2] = localY>=y+h && h>0 ? (localY-(y+h))/h+1 : 0;
    		}
    	}
    	return r;
    }
}

class fldOutlineListen implements MouseListener {

    public void mouseClicked(MouseEvent e) {
    	//フィールドのピークは Command-Option-Shift。キーの押しっぱなしが残っていただけの普通のクリックでは開かない
    	if(GUI.isPeekClick(e)) ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, ((MyTextArea)e.getSource()).fldData);
		GUI.endPeek();
    }
	
    public void mousePressed(MouseEvent e) {
    }
    public void mouseReleased(MouseEvent e) {
    }
	public void mouseEntered(MouseEvent arg0) {
	}
	public void mouseExited(MouseEvent arg0) {
	}
}

class MyScrollPane extends JScrollPane {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OField fldData = null;
	
	MyScrollPane(){
		super();
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
		//スクロールは画面の絵を写してずらす(BLIT)のではなく描き直す。カードの部品は重なるのに、カードのパネルは重ならない前提の
		//最適化描画なので、Swingはフィールドの上に載ったボタンやカードの絵まで一緒に写してずらし、描き直さなかった
		//(スクロールバーやホイールでスクロールすると上のボタンがテキストと一緒に流れた)。描き直しはカードのパネルから重なり順どおりに描く
		getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
	}

	@Override
	public void paint(Graphics g){
		if(PCARD.lockedScreen && !PCARD.capturingEffect) return;
		super.paint(g);
	}

	static int toViewScroll(int logicalScroll){
		return PCARD.toPhysical(logicalScroll);
	}

	/**
	 * The field's scroll and the pane's scroll bar follow each other, except where the bar is only held at its end by a view not
	 * yet as tall as the field's text: while the card is built the bar has Swing's default range (0..100, extent 10) and the
	 * text comes later, and a scroll of 120 came back as 90 after `go this cd` (PgColorX's colour field on card 45; HyperCard
	 * keeps it, up to text height - field height). When the view grows, the scroll it could not show yet goes on.
	 */
	static void followScroll(javax.swing.JScrollPane pane, OField ofld){
		javax.swing.JScrollBar bar = pane.getVerticalScrollBar();
		//the user's own scrolling (the bar, the wheel, keys or a drag in the field) always counts
		final long[] userAt = {0};
		java.awt.event.MouseAdapter user = new java.awt.event.MouseAdapter(){
			@Override public void mousePressed(java.awt.event.MouseEvent e){userAt[0]=System.currentTimeMillis();}
			@Override public void mouseDragged(java.awt.event.MouseEvent e){userAt[0]=System.currentTimeMillis();}
			@Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e){userAt[0]=System.currentTimeMillis();}
		};
		bar.addMouseListener(user);bar.addMouseMotionListener(user);pane.addMouseWheelListener(user);
		java.awt.Component view0 = pane.getViewport().getView();
		if(view0!=null){view0.addMouseListener(user);view0.addMouseMotionListener(user);
			view0.addKeyListener(new java.awt.event.KeyAdapter(){@Override public void keyPressed(java.awt.event.KeyEvent e){userAt[0]=System.currentTimeMillis();}});}
		bar.addAdjustmentListener(e -> {
			if(placingView>0) return; //a view placed at its field's scroll below (part of it while the text is not all laid out yet)
			int logical = toLogicalScroll(e.getValue());
			//below the field's scroll, not by the user: the view was held there while it was not as tall as the text yet
			if(logical<ofld.scroll && !e.getValueIsAdjusting() && System.currentTimeMillis()-userAt[0]>=1000){
				java.awt.Component view = pane.getViewport().getView();
				int room = view==null ? 0 : toLogicalScroll(Math.max(0, view.getPreferredSize().height-pane.getViewport().getExtentSize().height));
				if(room>logical) return;
			}
			ofld.scroll = logical;
		});
		final int[] viewHeight = {-1};
		pane.getViewport().addChangeListener(e -> {
			javax.swing.JViewport port = pane.getViewport();java.awt.Component view = port.getView();
			if(view==null || view.getHeight()==viewHeight[0]) return; //only a view that grew or shrank: not a scroll by the user
			viewHeight[0] = view.getHeight();
			int want = toViewScroll(ofld.scroll), y = port.getViewPosition().y;
			int pos = Math.max(0, Math.min(want, view.getHeight()-port.getExtentSize().height));
			if(pos>y){placingView++;try{port.setViewPosition(new java.awt.Point(port.getViewPosition().x, pos));}finally{placingView--;}}
		});
		bar.setValue(toViewScroll(ofld.scroll));
	}
	/** A view is being placed at its field's scroll (Swing's own layout, on the event thread or the script thread). */
	private static int placingView;
	/** Whether the pane shows the field's whole text height (so its bar's limit is the field's). */
	static boolean textLaidOut(javax.swing.JScrollPane pane){
		javax.swing.JViewport port = pane.getViewport();java.awt.Component view = port.getView();
		return view!=null && port.getExtentSize().height>0 && view.getHeight()>0 && view.getHeight()>=view.getPreferredSize().height;
	}

	static int toLogicalScroll(int viewScroll){
		return PCARD.toLogicalLength(viewScroll);
	}

	//2倍拡大表示モードでは、呼び出し元は今まで通り論理座標(x,y,w,h)を渡してよい。
	//実際のSwing上のサイズ・位置だけをここで拡大率倍する。
	@Override
	public void setBounds(int x, int y, int width, int height){
		Rectangle physical = PCARD.toPhysical(x, y, width, height);
		super.setBounds(physical.x, physical.y, physical.width, physical.height);
	}

	@Override
    protected void paintComponent(Graphics g) {
		if(PCARD.pc.bit != 1) return;
		if(PaintTool.editBackground && fldData.parent.objectType.equals("card")) return;
		if(!isVisible()||
			fldData.card!=PCARD.pc.stack.curCard||
			(PCARD.lockedScreen && !PCARD.capturingEffect))
		{
			/*for(int i=0; i<getComponentCount(); i++){
				getComponent(i).setVisible(false);
			}*/
			return;
		}
		/*for(int i=0; i<getComponentCount(); i++){
			if(getComponent(i).getClass() == JScrollPane.ScrollBar.class) continue;
			//setVisible(true)をやったときに書き換え命令が発生するのでこっそりやりたい
			Class<JScrollPane.ScrollBar> c = JScrollPane.ScrollBar.class;
			Field m;
			try {
				m = c.getDeclaredField("visible");
				m.setAccessible(true);
				m.set(getComponent(i), true); // .visible = true;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}*/
        super.paintComponent(g);
		if(AuthTool.tool!=null && FieldGUI.gui.target == this){
			FieldGUI.drawSelectBorder(this);
		}
    }
}


class MyTextArea extends JTextArea {
	/** The style runs as they are now, without the holes a concurrent change leaves (null when there are none). */
	private static java.util.ArrayList<styleClass> styleSnapshot(java.util.ArrayList<styleClass> live) {
		if(live==null) return null;
		for(int attempt=0; attempt<3; attempt++){
			try{
				java.util.ArrayList<styleClass> copy = new java.util.ArrayList<styleClass>(live);
				copy.removeIf(java.util.Objects::isNull);
				return copy;
			}catch(RuntimeException e){ /* changed while copying: try again */ }
		}
		return new java.util.ArrayList<styleClass>();
	}

	/** The stack of the field this area shows (a card read from another stack's clone may not carry one). */
	private OStack fieldStack(){
		return fldData.card!=null && fldData.card.stack!=null ? fldData.card.stack : PCARD.pc.stack;
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OField fldData = null;
	JScrollPane pr_scrl;
	int pr_selLine;//showLine xfcn用
	boolean smallfont;
	/** Points taken off the text size of a translated field's text that would not fit otherwise ({@link #fitTranslation}) */
	int fitShrink;
	/** The translation and field geometry fitShrink was worked out for */
	private StackTranslation.Shown fitShown;
	private int fitSig;
	/** A paint that only lays the text out to see whether it fits (draws nothing; {@link #fitOverflow} tells) */
	private boolean measuringFit;
	/** A measuring paint of the field's own text (the translation left out) */
	private boolean measuringOriginal;
	private boolean fitOverflow;
	/** What the field's own text shows (measured with measuringOriginal): the lines (between returns) shown whole, -1 for
	 *  all of them; then whether the first row of the next line shows too (a line cut after its first rows) */
	private int fitShownLines = -1;
	private boolean fitShowsFirstRow;
	/** The field's own lines wider than a field that does not wrap */
	private final java.util.BitSet fitWideLines = new java.util.BitSet();
	//独自描画(paintComponent)の倍率。0ならカードの拡大率(PCARD.scale())に従う。
	//ペイントのテキストツールはfldData.textSizeやsetBoundsRawに物理サイズを直接
	//入れ、確定時は論理サイズの一時レイヤーへ描くので、カードの2倍表示に
	//引きずられないよう1に固定する(引きずられると確定した文字が2倍の大きさになる)。
	int fixedScale = 0;
	//独自描画(paintComponent)の文字のアンチエイリアス。nullなら既定のまま。
	//ペイントのテキストツールがPaintメニューのAntialiasに合わせて設定する
	//(フォーカスのあるSwing描画側はclientProperty KEY_TEXT_ANTIALIASINGで合わせる)。
	Boolean textAntialias = null;
	//独自描画の文字。textAntialiasがfalseならグリフの輪郭をアンチエイリアス無しで
	//塗って描く(macOSのJavaはKEY_TEXT_ANTIALIASING=OFFを無視して常に滑らかに
	//描くので、ペイントのAntialiasオフをdrawStringでは再現できない)。
	/** 独自描画の1行の中の、同じスタイルで続く区間。行全体の幅が決まってから描く。 */
	static class LineSegment {
		String str;
		int x; //行の先頭からの位置(寄せの分は含まない)
		int width;
		Font font;
		FontMetrics metrics;
		int textStyle;
		Color color;
		int baseline;
		int textHeight;
		/** the baseline below the line's top (OField.naturalAscent, or the fixed line height's ascent) */
		int ascent;
		int[] idx; //各文字が元テキストの何文字目か
		int emptyIdx; //空の区間の場合に残すクリック位置
		/** bold in a host family without a bold face: QuickDraw's bold, every glyph drawn again 1px right and 1px wider */
		boolean smear;
		/** pixels QuickDraw adds to every glyph's advance for outline, shadow, condense and extend ({@link #styleExtra}) */
		int extra;
	}

	/** Whether {@code font} needs QuickDraw's smeared bold for {@code textStyle} (Geneva and Osaka have no bold face for Java) */
	static boolean needsSmear(Font font, int textStyle){
		return (textStyle&1)!=0 && font!=null && !Fonts68k.hasBoldFace(font);
	}
	/** Whether text from {@code from} on has anything but line breaks: the overflow shrink (smallfont) looks at the next line,
	 *  and after the last one there is none (FROMINE VR LIKA's one-line 14-point message, HC J-2.3 draws it at 14) */
	static boolean inkAfter(String text, int from){
		for(int k=Math.max(0, from); k<text.length(); k++){ char ch = text.charAt(k); if(ch!='\r' && ch!='\n') return true; }
		return false;
	}
	/** The Java style of a field run's font: an outline or shadow takes the plain face, its bold being QuickDraw's smear
	 *  ({@link #smears}) - a bold face's strokes leave no room for the 1-pixel rim at 12-14 points (paintRimmedRun) */
	static int fontStyle(int textStyle){
		return (textStyle&(8|16))!=0 ? textStyle&0x02 : textStyle&0x03;
	}
	/** Whether a field run of {@code font} (made with {@link #fontStyle}) is bold by drawing every glyph again 1px right */
	static boolean smears(Font font, int textStyle){
		return (textStyle&1)!=0 && ((textStyle&(8|16))!=0 || needsSmear(font, textStyle));
	}
	/** A character of a word a line break must not split: anything but spaces and Japanese (which wraps at any character) */
	static boolean isWrapWordChar(char c){
		if(c<=' ') return false;
		if(c<0x2E80) return true;
		return c>=0xFF61 && c<=0xFF9F; //half-width katakana are one-byte characters too, but Japanese: wrap anywhere
	}
	/** The width of {@code s}, with QuickDraw's 1px per glyph of a smeared bold */
	static int runWidth(FontMetrics fo, String s, boolean smear){
		return runWidth(fo, s, smear, 0);
	}
	/** The width of {@code s}, with QuickDraw's 1px per glyph of a smeared bold and {@code extra} per glyph for the other styles */
	static int runWidth(FontMetrics fo, String s, boolean smear, int extra){
		int w = fo.stringWidth(s);
		int add = (smear?1:0)+extra;
		if(add!=0) for(int i=0; i<s.length(); i++) if(fo.charWidth(s.charAt(i))>0) w += add;
		return w;
	}
	/** The Font Manager's style extras besides bold (TextStyle68k.extra): outline +1, shadow +2, condense -1, extend +1 per glyph
	 *  (あつきちゃん's bold shadow Osaka 14 title is 17 pixels a character in HyperCard J-2.3) */
	static int styleExtra(int textStyle){
		int e = 0;
		if((textStyle&8)!=0) e++;
		if((textStyle&16)!=0) e += 2;
		if((textStyle&32)!=0) e--;
		if((textStyle&64)!=0) e++;
		return e;
	}

	/** 溜めておいた1行分の区間を、行頭のx位置leftから順に描き、ヒットテスト用の位置も記録する。 */
	private void paintLine(Graphics g, java.util.List<LineSegment> line, int left){
		for(LineSegment seg : line){
			int lft = left+seg.x;
			int top = seg.baseline;
			FontMetrics fo = seg.metrics;
			g.setFont(seg.font);

			//実際の描画位置をヒットテスト用に記録する(the clickText / the clickChunk用)
			{
				int rowTop = top-seg.ascent;
				int cum = 0;
				for(int k=0; k<seg.idx.length; k++){
					int cw = fo.charWidth(seg.str.charAt(k));
					if(cw>0) cw += (seg.smear?1:0)+seg.extra;
					hitBoxes.add(new int[]{seg.idx[k], lft+cum, rowTop, Math.max(1,cw), seg.textHeight});
					cum += cw;
				}
				if(seg.idx.length==0){
					//空行にもクリック可能な位置を1つ残しておく
					hitBoxes.add(new int[]{seg.emptyIdx, lft, rowTop, 1, seg.textHeight});
				}
			}

			g.setColor(seg.color);
			int textStyle = seg.textStyle;
			if((textStyle&8)>0 || (textStyle&16)>0){
				//outline or shadow
				paintRimmedRun(g,seg,lft,top);
				g.setColor(fldData.bgColor);
			}
			else{
				//文字描画
				drawRun(g,seg,lft,top);
			}

			//underline: none under an empty run (the rest of an underlined run after its return starts the next line, and a
			//zero-length drawLine still paints its first pixel: XTernal Collection's Infos headings left a dot before each line)
			if(seg.width>0 && OField.drawsUnderline(textStyle, PCARD.showGroups)){
				g.drawLine(lft,top,lft+seg.width,top);
				if((textStyle&8)>0 || (textStyle&16)>0){
					g.setColor(seg.color);
					g.drawLine(lft,top+1,lft+seg.width,top+1);
				}
			}
		}
	}

	/**
	 * An outline or shadow run as QuickDraw draws it: the letters in the field's colour with a 1-pixel rim in the text colour
	 * all round; a shadow adds the letters again 1 and 2 pixels right and down (QuickDraw's shadow is the outline with a
	 * shadow, the 68K TextStyle68k rim: shadowed text has its top and left rim too, Script Lister's title). The rim is crisp,
	 * grown from the host glyphs' coverage cut at a half; the letters stay anti-aliased over it. Anti-aliased rims blurred
	 * into grey (FROMINE VR LIKA's bold outline message), and letters cut to black and white fell apart at 10 points (Dark
	 * Dungeon's Osaka 10 outline story on black). Bold is the plain face smeared ({@link #fontStyle}): a bold face's strokes
	 * leave no room for the rim.
	 */
	private void paintRimmedRun(Graphics g, LineSegment seg, int x, int y){
		FontMetrics fo = seg.metrics;
		int pad = 3+seg.font.getSize()/4; //glyphs reach past their advance and ascent; the rim and shadow 2 more
		int w = seg.width+2*pad, h = fo.getAscent()+fo.getDescent()+2*pad;
		if(seg.str.isEmpty() || w<=0 || h<=0) return;
		java.awt.image.BufferedImage cover = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
		Graphics2D cg = cover.createGraphics();
		try{
			cg.setColor(Color.WHITE);
			cg.setFont(seg.font);
			cg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			drawRun(cg, seg, pad, pad+fo.getAscent());
		}finally{ cg.dispose(); }
		int[] c = new int[w*h]; //coverage 0..255
		for(int py=0; py<h; py++) for(int px=0; px<w; px++) c[py*w+px] = cover.getRGB(px, py)&0xff;
		int[][] rim = (seg.textStyle&16)!=0 ? new int[][]{{0,0},{-1,0},{0,-1},{1,0},{0,1},{2,1},{1,2}} : new int[][]{{0,0},{-1,0},{0,-1},{1,0},{0,1}};
		Color letters = fldData.bgColor, edge = seg.color;
		java.awt.image.BufferedImage run = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for(int py=0; py<h; py++) for(int px=0; px<w; px++){
			int a = c[py*w+px];
			boolean onRim = false;
			for(int[] d : rim){
				int sx = px-d[0], sy = py-d[1];
				if(sx>=0 && sy>=0 && sx<w && sy<h && c[sy*w+sx]>=128){ onRim = true; break; }
			}
			if(onRim){
				//the letters' colour over the rim's by coverage
				int r = (edge.getRed()*(255-a)+letters.getRed()*a)/255, gr = (edge.getGreen()*(255-a)+letters.getGreen()*a)/255,
					b = (edge.getBlue()*(255-a)+letters.getBlue()*a)/255;
				run.setRGB(px, py, 0xff000000|r<<16|gr<<8|b);
			}
			else if(a>0) run.setRGB(px, py, a<<24|(letters.getRGB()&0xffffff));
		}
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g2.drawImage(run, x-pad, y-fo.getAscent()-pad, null);
		}finally{ g2.dispose(); }
	}

	/** One segment's text: glyph by glyph for QuickDraw's smeared bold or style extras, else the run at once */
	private void drawRun(Graphics g, LineSegment seg, int x, int y){
		if(!seg.smear && seg.extra==0){ drawText(g, seg.str, x, y); return; }
		FontMetrics fo = seg.metrics;
		for(int k=0; k<seg.str.length(); k++){
			String c = seg.str.substring(k, k+1);
			int cw = fo.charWidth(seg.str.charAt(k));
			drawText(g, c, x, y);
			if(cw>0){ if(seg.smear) drawText(g, c, x+1, y); x += cw+(seg.smear?1:0)+seg.extra; }
		}
	}

	private void drawText(Graphics g, String str, int x, int y){
		if(ClassicGlyphs.needsAppleShape(g, str)){ ClassicGlyphs.drawString(g, str, x, y, this::drawText); return; }
		if(Boolean.FALSE.equals(textAntialias) && g instanceof Graphics2D && !str.isEmpty()){
			Graphics2D g2 = (Graphics2D)g;
			Object aa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g2.fill(g2.getFont().createGlyphVector(g2.getFontRenderContext(), str).getOutline(x, y));
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa==null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
			return;
		}
		g.drawString(str, x, y);
	}
	/** The whole magnification the own drawing works at: the card's when whole; a fractional one (125% etc.) draws at 1x
	 *  under a scaling transform ({@link #paintTransformed}), so the lines wrap as at 1x. */
	int drawScale(){
		if(fixedScale>0) return fixedScale;
		double scale = PCARD.scale();
		return DisplayZoom.whole(scale) ? (int)scale : 1;
	}
	//スクロールフィールドで、独自描画(paintComponent)が実際に必要とする
	//コンテンツの高さ。Swing標準のgetPreferredSize()(フォント本来の行間隔
	//基準)とは食い違うことがあるため、実測してスクロール範囲に反映する。
	private int measuredContentHeight = -1;
	/** Whether measuredContentHeight was set for the own drawing (the exact height) or for Swing's editing */
	private boolean measuredOwnHeight;

	//独自描画(paintComponent)が実際に文字を描いた位置の記録。要素は
	//{元テキストでの文字インデックス, 左端x, 上端y, 幅, 高さ}(いずれも
	//コンポーネント自身のローカル座標=物理ピクセル)。フィールドは折り返しや
	//スタイル混在、独自フォント(ピクチャフォント)など複雑なレイアウトを行うため、
	//標準のJTextArea#viewToModel2Dでは実際の描画位置と一致しない。そこで実際の
	//描画結果をそのままヒットテストに使う(the clickText / the clickChunk用)。
	final java.util.ArrayList<int[]> hitBoxes = new java.util.ArrayList<int[]>();
	/** The text hitBoxes were made for (null before the first paint) */
	volatile String hitBoxesText;
	private boolean measuringHitBoxes;

	/** Lays the field out again into an off-screen image, for hitBoxes of the text it has now: a handler that puts text into a
	 *  field and clicks at it before the screen was redrawn (or under lock screen) got the old text's places (HC J-2.3 probe
	 *  D Click Field: the clickChunk of the second click was the first one's) */
	void refreshHitBoxes(){
		int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setClip(0, 0, w, h);
		boolean was = measuringHitBoxes;
		measuringHitBoxes = true;
		try{ paintComponent(g); }
		catch(RuntimeException e){ /* the boxes stay as they were */ }
		finally{ measuringHitBoxes = was; g.dispose(); }
	}

	/** The smallest text size a translation may be shrunk to: half the field's size, not under 6 points */
	static int fitMinSize(int textSize){
		return Math.min(textSize, Math.max(6, (textSize+1)/2));
	}

	/**
	 * A translated field's text is often longer than the original: when it would run out of the field (past the bottom of a
	 * field that does not scroll, past the right of a field that does not wrap) it is drawn smaller, as little as makes it
	 * fit, down to half the size ({@link #fitMinSize}). Only the lines the field's own text shows need to fit: a field that
	 * hides lines below its bottom (or past the right of a field that does not wrap) keeps them hidden in the translation
	 * too, line for line (a translated field keeps its returns), instead of shrinking everything to bring them into view.
	 * Measured by laying the text out without drawing (the same paint, empty clip); the result is kept until the
	 * translation or the field's size or font changes.
	 */
	private void fitTranslation(Graphics g, StackTranslation.Shown translated){
		if(translated==null){ fitShown = null; fitShrink = 0; return; }
		int sig = java.util.Objects.hash(drawScale(), paintTransform, fldData.width, fldData.height, fldData.textFont, fldData.textSize,
			fldData.textStyle, fldData.textHeight, fldData.fixedLineHeight, fldData.wideMargins, fldData.dontWrap, fldData.style);
		if(translated==fitShown && sig==fitSig) return;
		fitShown = translated; fitSig = sig; fitShrink = 0;
		int most = fldData.textSize-fitMinSize(fldData.textSize);
		if(most<=0) return;
		Graphics gm = g.create();
		gm.setClip(0, 0, 0, 0);
		boolean was = measuringFit;
		measuringFit = true;
		fitShownLines = -1; fitWideLines.clear();
		try{
			if(!overflowsWhenDrawn(gm)) return;
			//what the original shows at the size it is drawn (a HyperCard 2.x stack's one point less, smallfont), in a line 15%
			//wider: the host fonts are wider than the classic ones, so lines that fitted in HyperCard often wrap or run out here
			//(its rows stay as tall: a smaller size would make more lines look shown than HyperCard showed)
			OStack st = fieldStack();
			fitShrink = st!=null && st.createdByVersion!=null && st.createdByVersion.startsWith("2") && fldData.textSize>6 ? 1 : 0;
			measuringOriginal = true;
			try{ overflowsWhenDrawn(gm); }finally{ measuringOriginal = false; }
			fitShrink = 0;
			if(!overflowsWhenDrawn(gm)) return; //fits as far as the original showed
			//the least shrink that fits (the whole range when even the smallest does not)
			int lo = 1, hi = most;
			while(lo<hi){
				int mid = (lo+hi)>>>1;
				fitShrink = mid;
				if(overflowsWhenDrawn(gm)) lo = mid+1; else hi = mid;
			}
			fitShrink = lo;
		}catch(RuntimeException e){
			fitShrink = 0;
		}finally{
			measuringFit = was;
			gm.dispose();
			hitBoxes.clear(); //the next (real) paint lays them out again
		}
	}
	/** A row of line {@code line} (between returns) runs past the field's bottom; {@code firstRow}: the line's first row */
	private void fitPastBottom(int line, boolean firstRow){
		if(measuringOriginal){
			if(fitShownLines<0){ fitShownLines = line; fitShowsFirstRow = !firstRow; }
		}else if(fitShownLines<0 || line<fitShownLines || (line==fitShownLines && firstRow && fitShowsFirstRow)) fitOverflow = true;
	}
	/** Line {@code line} is wider than a field that does not wrap */
	private void fitPastRight(int line){
		if(measuringOriginal) fitWideLines.set(line);
		else if(!fitWideLines.get(line)) fitOverflow = true;
	}
	private boolean overflowsWhenDrawn(Graphics gm){
		fitOverflow = false;
		paintComponent(gm);
		return fitOverflow;
	}

	//リストフィールド(autoSelect)の行をクリックしたとき、キャレット移動に伴う
	//JTextAreaの自動スクロール(scrollRectToVisible)を抑える。HyperCardでは
	//見えている行をクリックしてもリストはスクロールしない。キャレットの
	//表示調整はinvokeLaterで遅れて走るので、解除も同じくinvokeLaterで行う
	boolean suppressAutoScroll = false;

	void keepScrollWhile(Runnable r){
		suppressAutoScroll = true;
		try{ r.run(); }
		finally{ javax.swing.SwingUtilities.invokeLater(() -> suppressAutoScroll = false); }
	}

	@Override
	public void scrollRectToVisible(Rectangle aRect){
		if(suppressAutoScroll) return;
		super.scrollRectToVisible(aRect);
	}

	MyTextArea(String str){
		super(str);
		this.setDoubleBuffered(PCARD.useDoubleBuffer);
		//拡大した下描き(magnifiesUnscaledPaint)のキャレットはSwingのキャレットとは位置が違うので、動いたら全体を描き直す
		addCaretListener(e -> { if(magnifiesUnscaledPaint()) repaint(); });
	}

	@Override
	public void paint(Graphics g){
		if(PCARD.lockedScreen && !PCARD.capturingEffect) return;
		super.paint(g);
	}

	/** Swing's caret places a press through the text view, whose font metrics are set only when that view is painted or
	 *  measured. This field draws its own text, so a view made anew (a script set the textFont) had none: the press threw
	 *  inside the caret and the card never got the click (Stop the Beat's loop waiting for the mouseClick ran on). Measuring
	 *  the view first sets them. */
	@Override
	protected void processMouseEvent(MouseEvent e){
		if(e.getID()==MouseEvent.MOUSE_PRESSED){
			try{ getUI().getPreferredSize(this); }catch(RuntimeException ex){ /* the press goes on as before */ }
		}
		super.processMouseEvent(e);
	}

	//カード上へ直接配置されているフィールドには論理座標が渡される。一方、
	//スクロールフィールドではJViewportのレイアウト処理が、既に拡大済みの
	//Swing座標を渡す。後者まで再度拡大するとレイアウトのたびにviewが大きくなり、
	//スクロールモデルとテキストの折り返し幅が壊れるため、そのまま適用する。
	@Override
	public void setBounds(int x, int y, int width, int height){
		if(getParent() instanceof JViewport){
			super.setBounds(x, y, width, height);
		}else{
			Rectangle physical = PCARD.toPhysical(x, y, width, height);
			super.setBounds(physical.x, physical.y, physical.width, physical.height);
		}
	}

	//ペイントツールのテキストツールが、論理サイズ(bit/scaleなし)のサーフェースへ
	//area.paint(...)で直接描き込む直前に、実際のSwing座標を論理座標そのものに
	//戻すために使う。通常のsetBoundsはscale()倍してしまうため使えない。
	void setBoundsRaw(int x, int y, int width, int height){
		super.setBounds(x, y, width, height);
	}

	public int getLineHeight(){
		  return getRowHeight();
	}

	static int nextTextLeft(int left, int drawWidth, boolean lineBreak,
			boolean styleChange) {
		if(lineBreak) return 0;
		if(styleChange) return left+drawWidth;
		return left;
	}

	/**
	 * A styled line without a fixed line height (HyperCard J-2.3): the baseline is the line's top plus the largest ascent of
	 * its runs, the next line starts the largest descent+leading below it. A 24-point title among 14-point text is no longer
	 * cut off at the field's top, and the lines below move down as in HyperCard. {baseline, bottom}
	 */
	static int[] styledLineBox(int lineTop, java.util.List<LineSegment> line) {
		int maxAscent = 0, maxBelow = 0;
		for(LineSegment ls : line){ maxAscent = Math.max(maxAscent, ls.ascent); maxBelow = Math.max(maxBelow, ls.textHeight-ls.ascent); }
		return new int[]{lineTop+maxAscent, lineTop+maxAscent+maxBelow};
	}

	static int nextTextTop(int top, int textHeight, boolean lineBreak) {
		return lineBreak ? top+textHeight : top;
	}

	static int baselineAfterInitialStyle(int baseline, int initialFontAscent,
			int styledFontAscent, int stylePosition, int textIndex) {
		if(stylePosition==0 && textIndex==0){
			return baseline+Math.max(0, styledFontAscent-initialFontAscent);
		}
		return baseline;
	}

	//スクロールフィールドの独自描画ループが最後まで進んだ後のtop(=描画され
	//なかった仮想的な次の行のベースライン位置)から、実際に必要なコンテンツの
	//高さを求める。この値がSwing標準のgetPreferredSize()より大きい場合、
	//スクロールバーの可動範囲を広げて最後の行までスクロールできるようにする。
	static int measuredScrollContentHeight(int finalTop, int initialFontAscent, int horizontalMargin) {
		return finalTop-initialFontAscent+horizontalMargin;
	}

	static Color lineSeparatorColor(Color textColor, Color backgroundColor) {
		return new Color(
			(backgroundColor.getRed()*3+textColor.getRed())/4,
			(backgroundColor.getGreen()*3+textColor.getGreen())/4,
			(backgroundColor.getBlue()*3+textColor.getBlue())/4);
	}

	/**
	 * showLines: a solid light line (the text colour a quarter of the way from the background, lineSeparatorColor) where
	 * HyperCard J-2.3 puts its dotted one - one pixel below each fixed line's baseline (line top + textHeight - textHeight
	 * div 4 + 1), textHeight apart down to the field's bottom, whatever the text size; a scrolling field shows none.
	 * Measured with textHeight 12/16/20/30: lines at 11/14/17/25 below the field's top. HyperZebra keeps the places but
	 * draws the line light rather than dotted (user's choice, as spreadsheets moved from dotted to light grid lines).
	 */
	private void paintLineSeparators(Graphics g) {
		if(fldData==null) return;
		//Swingが描く編集中のフィールドは拡大率が整数でなくても拡大したフォントで描くので、線もその間隔で引く
		double scale = fixedScale>0 || paintTransform!=1 ? drawScale() : PCARD.scale();
		paintLineSeparators(g, scale, fldData.width, fldData.height, fldData.fixedRowHeight(),
			fldData.wideMargins, fldData.style, fldData.fixedLineHeight, fldData.showLines, fldData.color, fldData.bgColor);
	}

	/** The showLines lines of a field (logical pixels, drawn at the scale) - see above */
	static void paintLineSeparators(Graphics g, double scale, int width, int height, int row,
			boolean wideMargins, int style, boolean fixedLineHeight, boolean showLines, Color color, Color background) {
		if(!fixedLineHeight || !showLines || style==5 || width<=2 || height<=2 || row<=0) return;
		Graphics2D g2 = (Graphics2D)g.create();
		try{
			g2.scale(scale, scale);
			g2.setColor(lineSeparatorColor(color!=null ? color : Color.BLACK, background!=null ? background : Color.WHITE));
			for(int y=OField.textTopMargin(1, wideMargins)+OField.fixedLineAscent(row)+1; y<height-1; y+=row){
				g2.fillRect(1, y, width-2, 1);
			}
		}finally{
			g2.dispose();
		}
	}

	/** 論理サイズで描いている最中(拡大表示の下描き)。 */
	private boolean paintingUnscaled;
	/**
	 * ペイントのテキストツールのプレビュー(fixedScale倍)を、確定と同じ論理サイズで描いてから拡大する(macOS以外)。
	 * Windowsの小さい文字はヒンティングや埋め込みビットマップで、同じフォントを8倍の大きさで描いた形とは違う
	 * (日本語Windowsの12ポイントのDialogでは高さが7ドット、96ポイントの1/8は8.5ドット): 確定すると文字が上下にずれ、
	 * 大きさも変わっていた。macOSは小さい文字もアウトラインのまま描くので、これまで通り拡大したフォントで描く。
	 */
	private boolean magnifiesUnscaledPaint(){
		return fixedScale>1 && !paintingUnscaled && !GMenu.isMacOSX() && fldData!=null;
	}
	/** 入力中も同じ下描きを拡大して見せ、キャレットと選択範囲は実際に描いた文字の位置(hitBoxes)に描く
	 *  (Swingの描画は拡大したフォントの字形と字幅なので、確定する文字とずれる)。 */
	private void paintUnscaledThenMagnify(Graphics g){
		int s = fixedScale;
		int w = Math.max(1, (getWidth()+s-1)/s), h = Math.max(1, (getHeight()+s-1)/s);
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D ig = img.createGraphics();
		ig.setClip(0, 0, w, h);
		boolean ownDraw = fldData.useMyDraw;
		fixedScale = 1; paintingUnscaled = true; fldData.useMyDraw = true;
		try { paintComponent(ig); }
		finally { fixedScale = s; paintingUnscaled = false; fldData.useMyDraw = ownDraw; ig.dispose(); }
		for(int[] b : hitBoxes){ b[1]*=s; b[2]*=s; b[3]*=s; b[4]*=s; } //ヒットテストは拡大後の座標
		Graphics2D g2 = (Graphics2D)g.create();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.drawImage(img, 0, 0, w*s, h*s, null);
		if(isFocusOwner() && !ownDraw){
			int selStart = getSelectionStart(), selEnd = getSelectionEnd();
			if(selEnd>selStart){
				g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.4f));
				g2.setColor(getSelectionColor());
				for(int[] b : hitBoxes) if(b[0]>=selStart && b[0]<selEnd) g2.fillRect(b[1], b[2], b[3], b[4]);
				g2.setComposite(java.awt.AlphaComposite.SrcOver);
			}
			else if(getCaret()!=null){
				//点滅はSwingのキャレットの矩形だけを描き直すので、この位置のキャレットは点滅させない
				if(getCaret().getBlinkRate()!=0){ getCaret().setBlinkRate(0); getCaret().setVisible(true); }
			}
			if(selEnd<=selStart && getCaret()!=null && getCaret().isVisible()){
				int[] at = caretBox(getCaretPosition());
				if(at!=null){ g2.setColor(getCaretColor()); g2.fillRect(at[0], at[1], Math.max(1, s/4), at[2]); }
			}
		}
		g2.dispose();
	}
	/** 文字位置dotのキャレット{x, 上端, 高さ}: その文字の左端、無ければ直前の文字の右端 */
	private int[] caretBox(int dot){
		int[] before = null;
		for(int[] b : hitBoxes){
			if(b[0]==dot) return new int[]{b[1], b[2], b[4]};
			if(b[0]<dot && (before==null || b[0]>before[0])) before = b;
		}
		return before==null ? null : new int[]{before[1]+before[3], before[2], before[4]};
	}

	/** Painting a shadow field's contents clipped to the inside of its frame (see paintComponent). */
	private boolean paintingInsideFrame;
	/** The inside of a shadow field's frame in the coordinates this paint draws in (logical when drawn at 1x and magnified). */
	private Rectangle shadowInterior(){
		if(paintTransform!=1 || paintingUnscaled) return ShadowFieldBorder.interior(fldData.width, fldData.height, 1);
		return ShadowFieldBorder.interior(getWidth(), getHeight(), Math.max(1, PCARD.scale()));
	}

	/** The card's fractional magnification the own drawing is done under (1 when whole or a fixed scale is set). */
	private double paintTransform = 1;
	/** Whether this paint is the own drawing at a fractional card magnification (see {@link #drawScale}). */
	private boolean paintsTransformed(){
		if(fixedScale>0 || paintTransform!=1 || fldData==null) return false;
		if(DisplayZoom.whole(PCARD.scale())) return false;
		return !isFocusOwner() || fldData.useMyDraw;
	}
	/** The own drawing at 1x, magnified by the card's fractional scale; hit boxes are then given in window pixels. */
	private void paintTransformed(Graphics2D g){
		double s = PCARD.scale();
		Graphics2D g2 = (Graphics2D)g.create();
		g2.scale(s, s);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		paintTransform = s;
		try { paintComponent(g2); }
		finally { paintTransform = 1; g2.dispose(); }
		for(int[] b : hitBoxes){
			int x0 = (int)Math.round(b[1]*s), y0 = (int)Math.round(b[2]*s);
			b[3] = Math.max(1, (int)Math.round((b[1]+b[3])*s)-x0); b[4] = Math.max(1, (int)Math.round((b[2]+b[4])*s)-y0);
			b[1] = x0; b[2] = y0;
		}
	}

	@Override
    protected void paintComponent(Graphics g) {
		if(!isVisible()) return;
		if(magnifiesUnscaledPaint() && g instanceof Graphics2D){ paintUnscaledThenMagnify(g); return; }
		if(paintsTransformed() && g instanceof Graphics2D){ paintTransformed((Graphics2D)g); return; }
		if(PCARD.pc.bit != 1 && fldData.card!=null) return;
		if(PaintTool.editBackground && fldData.parent!=null && fldData.parent.objectType.equals("card")) return;
		if(fldData!=null && fldData.card!=null && fldData.card.objectType.equals("cd") && fldData.card!=PCARD.pc.stack.curCard) return;
		if(PCARD.lockedScreen && !PCARD.capturingEffect && !measuringHitBoxes) return;
		if(fldData!=null && fldData.style==4 && !paintingInsideFrame){
			//シャドウ: 枠の内側だけをフィールドの色で塗り、文字も枠の内側に切り取る(HyperCardと同じ)
			Rectangle inside = shadowInterior();
			Graphics gi = g.create();
			try{
				gi.setColor(fldData.bgColor);
				gi.fillRect(inside.x, inside.y, inside.width, inside.height);
				gi.clipRect(inside.x, inside.y, inside.width, inside.height);
				paintingInsideFrame = true;
				paintComponent(gi);
			}finally{ paintingInsideFrame = false; gi.dispose(); }
			return;
		}
        // JTextComponent#getText()は文書全体をコピーする。文字ごとに呼ぶと
        // 長いフィールドの描画が二次的に遅くなるため、この描画で一度だけ取得する。
        //スタックの翻訳(StackTranslation)がオンなら、入力中でないフィールドは行ごとの訳を描く(フィールドのテキストは元のまま)
        final String ownText = getText();
        hitBoxesText = ownText;
        final StackTranslation.Shown translated = fldData!=null && fldData.card!=null && !isFocusOwner() && !measuringOriginal
        		? StackTranslation.field(fldData, ownText) : null;
        final String displayText = translated!=null ? translated.text : ownText;
        //a copy: a script thread may replace the runs while this paints (AOL JARS: put into a styled field during a popup click)
        final java.util.ArrayList<styleClass> styles = styleSnapshot(translated!=null ? translated.styles : fldData!=null ? fldData.styleList : null);
        //super.paintComponent(g);
    	if(fldData!=null){
    		//通常フィールド
    		if(isFocusOwner()==false || fldData.useMyDraw){
    			if(!measuringFit) fitTranslation(g, translated);
    			if(textAntialias!=null && g instanceof Graphics2D){
    				((Graphics2D)g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
    					textAntialias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    			}
    			if(fldData.style!=1){
	    			g.setColor(fldData.bgColor);
	    			Rectangle r = g.getClipBounds();
	    			g.fillRect(r.x,r.y,r.width,r.height);
				}
				paintLineSeparators(g);

				hitBoxes.clear();

    			int drawScale = drawScale();
    			//the list's hilited line: only a line the text has - HyperCard keeps the selectedLine when the text is replaced
    			//(put empty into the list) but draws no hilite past the last line (Game Genie Code Archiver's emptied list, HC J-2.3)
    			int hiliteLine = fldData.enabled && fldData.autoSelect && fldData.dontWrap && fldData.selectedLine>0 &&
    				fldData.selectedLine<=OField.textLineCount(fldData.getText()) ? fldData.selectedLine : 0;
    			//styled lines are as tall as their runs: the hilite is drawn with its line, over the line's own box (HSP Icon
    			//List: Monaco 9 runs in a 12-point field with textHeight 16 showed line 3's hilite on line 4)
    			boolean hiliteWithLine = hiliteLine>0 && !fldData.fixedLineHeight && styles!=null && !styles.isEmpty();
    			if(hiliteLine>0 && !hiliteWithLine){
        			//ライン選択
    	    		{
    	    			g.setColor(SystemColor.textHighlight);
    	    			int rowHeight = fldData.lineHeight();
    	    			g.fillRect(1,(fldData.selectedLine-1)*rowHeight*drawScale,fldData.width*drawScale-1,rowHeight*drawScale);
    	    		}
    			}

    			String textFont = fldData.textFont;
    			int textStyle = fldData.textStyle;
    			int textSize = fldData.textSize;
    			//a translation that would run out of the field is drawn smaller (fitTranslation), its runs and fixed lines alike
    			double fitRatio = 1;
    			if(fitShrink>0 && (translated!=null || measuringOriginal)){
    				fitRatio = (textSize-fitShrink)/(double)textSize;
    				textSize -= fitShrink;
    			}else if(smallfont && textSize>6) textSize--;
    			textSize *= drawScale;
    			Color textColor = fldData.color;
    			int nextStyle = 0;
    			int nextTextPosition = -1;
    			if(styles!=null && styles.size()>nextStyle){
    				nextTextPosition = styles.get(nextStyle).textPosition;
    			}
				StringBuilder readingStr = new StringBuilder();
				//readingStrの各文字が元テキストの何文字目かを並行して記録する
				//(ヒットテスト用。\r\nはreadingStrに積まれないため単純な
				//オフセット計算では求められない)
				java.util.ArrayList<Integer> readingIdx = new java.util.ArrayList<Integer>();
			//int v=0;
				g.setFont(OObject.stackFont(textFont, fontStyle(textStyle), textSize));
				FontMetrics fo = g.getFontMetrics();
				boolean smear = smears(g.getFont(), textStyle);
				int extra = styleExtra(textStyle)*drawScale;
				int ascent = OField.naturalAscent(textFont, textSize, drawScale, fo); //the current run's
				int initialFontAscent = ascent;
				//HyperCard J-2.3 (its TextEdit record, read in the 68K VM): a fixedLineHeight field's lines are textHeight apart
				//(at least textSize) with the baseline textHeight-textHeight/4 below each line's top, whatever the font; without
				//a fixed line height textHeight plays no part - a line is as tall as its font (masaruquiz: Osaka 12 fields with
				//textHeight 20 show 20-pixel rows only when fixed)
				int fixedRow = fitRatio<1 ? Math.max(1, (int)Math.round(fldData.fixedRowHeight()*fitRatio)) : fldData.fixedRowHeight();
				int textHeight = fldData.fixedLineHeight ? fixedRow*drawScale : OField.naturalLineHeight(textFont, textSize, drawScale, fo);
				int fixedAscent = fldData.fixedLineHeight ? OField.fixedLineAscent(fixedRow)*drawScale : -1;
				if(fixedAscent>=0){ ascent = fixedAscent; initialFontAscent = ascent; }
				int nihongo_i = 0;
		int horizontalMargin = OField.textMargin(drawScale, fldData.wideMargins);
		int topMargin = OField.textTopMargin(drawScale, fldData.wideMargins);
		int top = topMargin+ascent;
		//the top of the line being laid out: without a fixed line height a line is as tall as its tallest run (HyperCard J-2.3:
		//baseline = line top + the largest ascent in the line, next line top = baseline + the largest descent+leading)
		int lineTop = topMargin;
		int textLine = 1; //the line of the text being laid out (a list field does not wrap)
		int hardLine = 0; //the line between returns being laid out, and whether the row is its first (fitTranslation)
		boolean firstRow = true;
		//the width a line may fill, measured in HyperCard J-2.3 (a line wraps when it is wider
		//than the field less 7, 15 with wide margins, 9 for a shadow field, 23 for a scrolling field)
		int fldWidth = fldData.width*drawScale-(fldData.wideMargins ? 15 : 7)*drawScale;
		if(fldData.style==5) fldWidth-=16*drawScale;
		else if(fldData.style==4) fldWidth-=2*drawScale;
		if(measuringOriginal) fldWidth = fldWidth*115/100; //the room the original's lines had in HyperCard (fitTranslation)
		//スタイル区間(STBL)の-1は「フィールドの設定を継承」。直前の区間の値ではない。
		final String baseFont = textFont;
		final int baseStyle = textStyle;
		final int baseSize = textSize;
		final int baseHeight = textHeight;
		//1行分のスタイル区間を溜めておき、行が確定してから行全体の幅で
		//センタリング・右寄せを決める(区間ごとに寄せると2区間目以降が右にずれる)
		java.util.ArrayList<LineSegment> line = new java.util.ArrayList<LineSegment>();
		int lineWidth = 0; //現在の行に積んだ区間の幅の合計

			for(int i=0; i<=displayText.length(); i++){
				char c = ' ';
				char nextc = ' ';
				if(i<displayText.length()){
					c = displayText.charAt(i);
					if(i+1<displayText.length()){
						nextc = displayText.charAt(i+1);
					}
				}else{
					c = '\n';
				}

				boolean isStyleChange = false;
				if(nextTextPosition!=-1 && nextTextPosition<=nihongo_i){
					isStyleChange = true;
				}
				boolean reprocessCharacter = isStyleChange && i!=0;

				if(!reprocessCharacter){
					if(c!='\r' && c!='\n'){
						readingStr.append(ClassicGlyphs.map(textFont, c)); //the classic font's glyph at a control code (⌘ ✓ ◆ Apple), measured and drawn as such
						readingIdx.add(i);
					}
					//styleList.textPositionはJavaの文字列インデックス(styleOffset参照)なので1文字=1で数える。
					nihongo_i++;
				}

				String drawStr = readingStr.toString();
			int w = 0;
			if(fldData.dontWrap==false) {
				w = runWidth(fo, drawStr, smear, extra);
			}

					//HyperCard J-2.3 wraps a word when the line up to it, with the space after it, is wider than the field
					//allows ("aaaa bbbb cc" in Geneva 18 needs 102 pixels for "aaaa bbbb " on one line; the last word of the
					//text needs no space). A line used to keep a character's width (the text size) free, so "Hardin Helper"
					//(18pt bold, 127 pixels) wrapped in a 146-pixel field that HyperCard shows it in on one line.
					boolean overflow = lineWidth+w>fldWidth;
					boolean isLineBreak = (!reprocessCharacter && (c=='\r' || c=='\n')) || overflow;
					if(isLineBreak||(isStyleChange && i!=0)){ //この行を描画する

					if(!isStyleChange && overflow){
						//行末処理
						{
							//drawStr ends with the character that did not fit
							int len = drawStr.length();
							char last = len>0 ? drawStr.charAt(len-1) : ' ';
							if(last==' '){
								//the space after a word did not fit: the word goes to the next line too
								while(len>0 && drawStr.charAt(len-1)==' ') len--;
								while(len>0 && isWrapWordChar(drawStr.charAt(len-1))) len--;
								if(len==0 && lineWidth==0) len = drawStr.length(); //a single word: the spaces hang
							}
							else if(isWrapWordChar(last)){
								while(len>0 && isWrapWordChar(drawStr.charAt(len-1))) len--;
								//a single word wider than the line is broken where it overflows
								if(len==0 && lineWidth==0) len = Math.max(1, drawStr.length()-1);
							}
							else{
								//Japanese text wraps at any character; closing punctuation stays at the end of the line
								if("、。，．,.」』）)！？!?ー".indexOf(last)<0) len--;
								if(len==0 && lineWidth==0) len = 1;
							}
							if(len>0){
								drawStr = drawStr.substring(0,len);
							}else if(lineWidth>0){
								//この区間は1語も入らない: 前の区間で行を終え、まるごと次の行へ送る
								drawStr = "";
							}
						}
					}
					//この行(セグメント)で実際に描画する文字が、元テキストの何文字目に
					//あたるかを先に控えてから、描画済み分をバッファから取り除く
					int drawnLen = drawStr.length();
					int[] segIdx = new int[drawnLen];
					for(int k=0; k<drawnLen; k++) segIdx[k] = readingIdx.get(k);
					readingStr.delete(0,drawnLen);
					readingIdx.subList(0,drawnLen).clear();

			int drawWidth = runWidth(fo, drawStr, smear, extra);

			LineSegment seg = new LineSegment();
			seg.str = drawStr; seg.x = lineWidth; seg.width = drawWidth;
			seg.font = g.getFont(); seg.metrics = fo; seg.textStyle = textStyle; seg.color = textColor;
			seg.baseline = top; seg.textHeight = textHeight; seg.ascent = ascent; seg.idx = segIdx; seg.emptyIdx = i; seg.smear = smear; seg.extra = extra;
			line.add(seg);

					if(isLineBreak){
						//行が確定: 行全体の幅で寄せを決めてから各区間を描く
						int lft = horizontalMargin;
						//centred and right-aligned lines keep their measured places (centre of the field less 3+3, end at right-4):
						//the wrap width is one pixel narrower than that box
						if(fldData.textAlign==1) lft = horizontalMargin+(fldWidth+drawScale-lineWidth-drawWidth)/2;
						if(fldData.textAlign==2) lft = horizontalMargin+fldWidth-lineWidth-drawWidth;
						int lineBottom = -1;
						if(!fldData.fixedLineHeight && styles!=null && !styles.isEmpty()){
							int[] box = styledLineBox(lineTop, line);
							for(LineSegment ls : line) ls.baseline = box[0];
							lineBottom = box[1];
						}
						boolean inked = false;
						for(LineSegment ls : line) if(ls.str.length()>0) inked = true;
						if(hiliteWithLine && textLine==hiliteLine && lineBottom>lineTop){
							g.setColor(SystemColor.textHighlight);
							g.fillRect(1, lineTop, fldData.width*drawScale-1, lineBottom-lineTop);
						}
						textLine++;
						if(measuringFit){
							if(fldData.dontWrap && lineWidth+drawWidth>fldWidth+drawScale) fitPastRight(hardLine);
						}else paintLine(g, line, lft);
						if(!reprocessCharacter && (c=='\r' || c=='\n')){ hardLine++; firstRow = true; }
						else firstRow = false;
						line.clear();
						// スタイル境界と改行が同じ位置でも次の行へ進める。
						lineWidth = nextTextLeft(lineWidth, drawWidth, true, isStyleChange);
						if(lineBottom>=0){ lineTop = lineBottom; top = lineTop+ascent; }
						else{ top = nextTextTop(top, textHeight, true); lineTop = top-ascent; }
						//HyperCard 2.x系から変換されたスタックは、元のMac OSとのフォント
						//メトリクスの違いで文字が収まらなくなることがあるため、スクロールしない
						//フィールドで文字がフィールドの高さからはみ出す場合、少しフォントサイズを
						//小さくして収まりやすくする（HyperZebraで新規作成したスタックは対象外）。
						//(empty lines at the end of the text draw nothing and do not count: masaruquiz's "近藤昌彦|あ|あ|あ||" fits)
						//The stack's own bitmap font has HyperCard's metrics: no shrink (東海道森林大爆走's 32-point map tiles).
						if(measuringFit){
							//the next row is past the bottom when its box (from lineTop, leading allowed 2 pixels) is; a wrapped row's
							//rest is still in readingStr (the last word of the text wrapping past the bottom counts)
							if(inked && fldData.style!=5 && lineTop+textHeight>(fldData.height+2)*drawScale && (readingStr.length()>0 || inkAfter(displayText, i+1)))
								fitPastBottom(hardLine, firstRow);
						}else if(inked && translated==null && !StackStrikeFont.isStackFont(g.getFont()) && PCARD.pc.stack.createdByVersion!=null && PCARD.pc.stack.createdByVersion.length()>0 && PCARD.pc.stack.createdByVersion.charAt(0)=='2' &&
								fldData.style!=5 && !smallfont && top+textHeight>(fldData.height+4)*drawScale && inkAfter(displayText, i+1)){
							smallfont = true;
							repaint();
						}
					}else if(isStyleChange){
						// 同じ行の次のスタイル区間は、直前の文字列の右から描く。
						lineWidth = nextTextLeft(lineWidth, drawWidth, false, true);
					}

					if(!reprocessCharacter && c=='\r'&&i+1<displayText.length()&&displayText.charAt(i+1)=='\n'){
    						i++;
    					}
    				}

					if(isStyleChange){
						//次のスタイルを適用(指定のない属性はフィールドの設定に戻る)
						int appliedStylePosition = nextTextPosition;
						int styleId = styles.get(nextStyle).styleId;
						textFont = baseFont;
						textStyle = baseStyle;
						textSize = baseSize;
						textHeight = baseHeight;
						for(int j=0; j<fieldStack().styleList.size(); j++){
							OStack.styleClass styleClass = fieldStack().styleList.get(j);
							if(styleClass.id==styleId){
								if(styleClass.font!=-1){
									for(int k=0; k<fieldStack().fontList.size(); k++){
										OStack.fontClass fontClass = fieldStack().fontList.get(k);
										if(fontClass.id==styleClass.font){
							    			textFont = fontClass.name;
							    			break;
										}
									}
								}
								if(styleClass.style!=-1){
									textStyle = styleClass.style;
								}
								if(styleClass.size!=-1){
									int size = styleClass.displaySize();
									if(fitRatio<1) size = Math.max(4, (int)Math.round(size*fitRatio));
									textSize = size*drawScale;
								}
								//Color textColor = styleClass;
								break;
							}
						}

						//新しいスタイルを適用
						g.setFont(OObject.stackFont(textFont, fontStyle(textStyle), textSize));
			        	fo = g.getFontMetrics();
						smear = smears(g.getFont(), textStyle);
						extra = styleExtra(textStyle)*drawScale;
						//a run of another size is as tall as its font (ascent+descent+leading), as HyperCard lays it out
						if(!fldData.fixedLineHeight && textSize!=baseSize){
							textHeight = Math.max(textSize+1, OField.naturalLineHeight(textFont, textSize, drawScale, fo));
							if((textStyle&8)>0 || (textStyle&16)>0) textHeight+=1;
						}
						if(fixedAscent<0){
							ascent = OField.naturalAscent(textFont, textSize, drawScale, fo);
							top = baselineAfterInitialStyle(top, initialFontAscent, ascent, appliedStylePosition, i);
						}

						nextStyle++;
						if(styles.size()>nextStyle){
							nextTextPosition = styles.get(nextStyle).textPosition;
						}else{
							nextTextPosition = -1;
						}
						if(reprocessCharacter){
							i--;
							continue;
						}
	    			}
    			}

    			//スクロールフィールドでは、この独自描画が実際に必要とする高さ(top)と、
    			//Swing標準のgetPreferredSize()(フォント本来の行間隔=getRowHeight()基準)
    			//が食い違うことがある。fldData.textHeightがフォント本来の行高と異なる
    			//値を持つ場合(HyperCardスタックの変換元でのフォントメトリクスの違い等)、
    			//スクロールバーの可動範囲がSwing側の(実際より小さい)高さで決まってしまい、
    			//最後の1〜2行までスクロールできなくなる。ここで実測した高さを
    			//スクロール範囲へ反映する。
    			if(fldData.style==5 && !measuringFit){
    				int contentHeight = (int)Math.ceil(measuredScrollContentHeight(top, initialFontAscent, topMargin)*paintTransform);
    				//the own drawing (not being edited by Swing) scrolls exactly over the lines it laid out, as HyperCard does
    				//(Break Name's Monaco 9 runs in a Palatino 12 field: Swing's height made the scroll range 1139, HC's is 611)
    				boolean own = !isFocusOwner() || fldData.useMyDraw;
    				if(contentHeight!=measuredContentHeight || own!=measuredOwnHeight){
    					measuredContentHeight = contentHeight;
    					measuredOwnHeight = own;
    					if(own){
    						setPreferredSize(new java.awt.Dimension(Math.max(1, getWidth()), contentHeight));
						javax.swing.SwingUtilities.invokeLater(this::revalidate);
    					}else{
    						setPreferredSize(null);
    						java.awt.Dimension pref = getPreferredSize();
    						if(pref.height<contentHeight) setPreferredSize(new java.awt.Dimension(pref.width, contentHeight));
						javax.swing.SwingUtilities.invokeLater(this::revalidate);
    					}
    				}
    			}/*else if(fldData.textAlign!=0){ //右寄せかセンタリング
    				if(fldData.style!=1){
    					g.setColor(Color.WHITE);
    					g.fillRect(1,1,fldData.width-2,fldData.height-2);
    				}
    				g.setFont(OObject.stackFont(fldData.textFont, fldData.textStyle&0x03, fldData.textSize));
		        	FontMetrics fo = g.getFontMetrics();
	    			int v=0;
					int sz = fldData.textHeight;
					StringBuilder str = new StringBuilder();
	    			for(int i=0; i<getText().length(); i++){
	    				char c = getText().charAt(i);
	    				if(c=='\r'||c=='\n'||i==getText().length()){
	    					//System.out.println("fontpict:"+h+","+v+"  "+c%8+","+c/8+""+(char)(c+32));
	    		    		String str2 = str.toString();
	    		        	int w = fo.stringWidth(str2);
	    		        	int lft = 0;
	    		        	if(fldData.textAlign==0) lft = 2;
	    		        	if(fldData.textAlign==1) lft = (fldData.width-w)/2;
	    		        	if(fldData.textAlign==2) lft = fldData.width-w;
	    		        	g.setColor(Color.BLACK);
	    		        	g.drawString(str2,lft,fldData.textSize+v*sz);
	    					
	    		        	str.delete(0,str.length());
	    					v++;
	    					if(c=='\r'&&i+1<getText().length()&&getText().charAt(i+1)=='\n') i++;
	    				}
	    				else{
	    					str.append(c);
	    				}
		    		}
    			}*/
    			
    			//訳を描いたときのクリック位置は、元のテキストの同じ行の中の位置に直す(the clickLine / the clickChunk)
    			if(translated!=null) for(int[] b : hitBoxes) b[0] = translated.original(b[0]);
    			if(!measuringFit && AuthTool.tool!=null && FieldGUI.gui.target == this){
    				FieldGUI.drawSelectBorder(this);
    			}
    			return;
    		}
    	}else if(fldData==null){
    		//showList用
			//ライン選択
			g.setColor(Color.WHITE);
			Rectangle r = g.getClipBounds();
			g.fillRect(r.x,r.y,r.width,r.height);
    		if(pr_selLine>0){
    			//g.setColor(Color.WHITE);
    			//g.setXORMode(SystemColor.textHighlight);
    			g.setColor(SystemColor.textHighlight);
    			g.fillRect(1,(pr_selLine-1)*getRowHeight(),getWidth()-1,getRowHeight());
    			//g.setXORMode(Color.WHITE);
    		}
    	}
		g.setPaintMode();
		try{
			super.paintComponent(g);
		}catch(NullPointerException e){
			//フォーカス中のフィールドがカード遷移で外れる際、Swing内部のPlainViewが
			//コンテナ未設定のまま再描画されることがあるため無視する
		}
		if(fldData!=null){
			paintLineSeparators(g);
		}
		if(AuthTool.tool!=null && FieldGUI.gui.target == this){
			FieldGUI.drawSelectBorder(this);
		}
    }
	
	@Override
	protected void paintBorder(Graphics g){
		if(!isVisible()) return;
		if(PCARD.pc.bit != 1) return;
		if(PaintTool.editBackground && fldData.parent!=null && fldData.parent.objectType.equals("card")) return;
		if(fldData!=null && fldData.card!=null && fldData.card.objectType.equals("cd") && fldData.card!=PCARD.pc.stack.curCard) return;
		if(PCARD.lockedScreen && !PCARD.capturingEffect) return;
		super.paintBorder(g);
	}
}


class styleClass{
	int textPosition;
	int styleId;
	styleClass(){}
	styleClass(int textPosition, int styleId){
		this.textPosition=textPosition;
		this.styleId=styleId;
	}
}

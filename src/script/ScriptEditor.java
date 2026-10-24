import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

public class ScriptEditor extends JFrame {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	//private OStack parent = null;
	static JTabbedPane tabPane = null;
	/** This editor's tabs (tabPane is the ones of the stack on show) */
	final JTabbedPane tabs;
	String FindStr = "";
	String ReplaceStr = "";
	/** 選択中の行・列と、直近の実行時エラーを常時表示するステータスバー */
	private JLabel errorStatus;
	private JLabel caretStatus;
	//static private ScriptArea area;
	
	public ScriptEditor(Frame owner){
		super();
		
		//frame
		setBounds(owner.getX()+owner.getWidth()/2-320,owner.getY()+owner.getHeight()/2-320,640,640);
		setTitle(PCARD.pc.intl.getDialogText("Script Editor"));
		getContentPane().setLayout(new BorderLayout());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
		    	maybeExit();
		    	if(!PCARD.pc.stack.scriptEditor.isVisible()){
		    		//maybeExitでキャンセルされなかったときだけ全タブを捨てる
		    		tabPane.removeAll();
		    	}
		    	TTalk.tracemode=0;
		    	TTalk.stepflag = true;
			}
			
			@Override
			public void windowOpened(WindowEvent e){
				//area.checkIndentCall();
			}
		});

		//tabpane
		tabPane = tabs = new JTabbedPane();
		getContentPane().add(tabPane);

		//ハンドラポップアップ(選ぶとそのハンドラの先頭行にジャンプする)
		handlerPopup = new JComboBox<ScriptArea.HandlerEntry>();
		handlerPopup.setFocusable(false);
		handlerPopup.setPrototypeDisplayValue(new ScriptArea.HandlerEntry("function returnMessageText", 0));
		handlerPopup.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(updatingHandlerPopup) return;
				Object sel = handlerPopup.getSelectedItem();
				ScriptArea area = selectedArea();
				if(area==null || !(sel instanceof ScriptArea.HandlerEntry)) return;
				ScriptArea.HandlerEntry entry = (ScriptArea.HandlerEntry)sel;
				if(entry.line < 0) return;
				area.jumpToHandler(entry.line);
				area.requestFocus();
			}
		});
		JPanel handlerBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		handlerBar.add(handlerPopup);
		//HyperTalk簡易リファレンス(カーソル位置の単語を引く)
		JButton referenceButton = new JButton(PCARD.pc.intl.getDialogText("Reference"));
		referenceButton.setFocusable(false);
		referenceButton.setToolTipText(PCARD.pc.intl.getText("HyperTalk Reference")+" ("+
				(System.getProperty("os.name").toLowerCase().startsWith("mac os x") ? "⌘K" : "Ctrl+K")+")");
		referenceButton.addActionListener(e -> showReference());
		JPanel referenceBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
		referenceBar.add(referenceButton);
		JPanel northBar = new JPanel(new BorderLayout());
		northBar.add(handlerBar, BorderLayout.CENTER);
		northBar.add(referenceBar, BorderLayout.EAST);
		getContentPane().add(northBar, BorderLayout.NORTH);
		tabPane.addChangeListener(e -> {
			refreshHandlerPopup();
			refreshStatus(selectedArea());
		});
		refreshHandlerPopup();

		JPanel statusBar = new JPanel(new BorderLayout(8, 0));
		statusBar.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(205, 205, 205)),
				BorderFactory.createEmptyBorder(3, 7, 3, 7)));
		errorStatus = new JLabel(" ");
		errorStatus.setForeground(new Color(170, 25, 25));
		errorStatus.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		errorStatus.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				ScriptArea area = selectedArea();
				if(area!=null){
					int line = area.errorLine>=0 ? area.errorLine :
							(area.diagnosticAtLine(area.caretLine())!=null ? area.caretLine() : area.nextDiagnosticLine());
					if(line<0) return;
					area.selectLine(line);
					area.requestFocusInWindow();
				}
			}
		});
		caretStatus = new JLabel(" ", SwingConstants.RIGHT);
		statusBar.add(errorStatus, BorderLayout.CENTER);
		statusBar.add(caretStatus, BorderLayout.EAST);
		getContentPane().add(statusBar, BorderLayout.SOUTH);
		refreshStatus(null);

		//menu
		new SEMenu(this);
	}

	/** ハンドラポップアップ。項目はスクリプト中の on/function 行 */
	JComboBox<ScriptArea.HandlerEntry> handlerPopup = null;
	private boolean updatingHandlerPopup = false;
	/** 編集のたびに作り直さないよう少し待ってからポップアップを更新する */
	private Timer handlerPopupTimer = null;

	/** 選択中のタブのScriptArea。無ければnull */
	static ScriptArea selectedArea(){
		if(tabPane == null) return null;
		Component sel = tabPane.getSelectedComponent();
		if(!(sel instanceof JScrollPane)) return null;
		JViewport vp = ((JScrollPane)sel).getViewport();
		if(vp.getComponentCount()<1 || !(vp.getComponent(0) instanceof ScriptArea)) return null;
		return (ScriptArea)vp.getComponent(0);
	}

	/** スクリプトが編集されたときに呼ぶ。選択中のタブならポップアップを(少し遅らせて)作り直す */
	void scheduleHandlerPopupRefresh(ScriptArea area){
		if(area != selectedArea()) return;
		if(handlerPopupTimer == null){
			handlerPopupTimer = new Timer(200, e -> refreshHandlerPopup());
			handlerPopupTimer.setRepeats(false);
		}
		handlerPopupTimer.restart();
	}

	/** 選択中のタブのスクリプトからポップアップの項目を作り直す */
	void refreshHandlerPopup(){
		if(handlerPopup == null) return;
		ScriptArea area = selectedArea();
		updatingHandlerPopup = true;
		try{
			handlerPopup.removeAllItems();
			ArrayList<ScriptArea.HandlerEntry> handlers = null;
			if(area != null) handlers = ScriptArea.listHandlers(area.getText());
			if(handlers == null || handlers.isEmpty()){
				handlerPopup.addItem(new ScriptArea.HandlerEntry(PCARD.pc.intl.getDialogText("No Handlers"), -1));
				handlerPopup.setEnabled(false);
			} else {
				for(ScriptArea.HandlerEntry h : handlers) handlerPopup.addItem(h);
				handlerPopup.setEnabled(true);
			}
		} finally {
			updatingHandlerPopup = false;
		}
		syncHandlerPopupToCaret(area);
	}

	/** キャレットのある行を含むハンドラをポップアップで選択状態にする(ジャンプはしない) */
	void syncHandlerPopupToCaret(ScriptArea area){
		if(handlerPopup == null || area == null || area != selectedArea() || !handlerPopup.isEnabled()) return;
		int line = area.caretLine();
		int index = -1;
		for(int i=0; i<handlerPopup.getItemCount(); i++){
			if(handlerPopup.getItemAt(i).line <= line) index = i;
			else break;
		}
		if(index < 0 || index == handlerPopup.getSelectedIndex()) return;
		updatingHandlerPopup = true;
		try{
			handlerPopup.setSelectedIndex(index);
		} finally {
			updatingHandlerPopup = false;
		}
	}

	/** 選択中タブのキャレット位置と実行時エラーをステータスバーへ反映する。 */
	void refreshStatus(ScriptArea area){
		if(caretStatus==null || errorStatus==null) return;
		if(area==null || area!=selectedArea()){
			caretStatus.setText(" ");
			errorStatus.setText(" ");
			errorStatus.setToolTipText(null);
			return;
		}
		int line = area.caretLine()+1;
		int column = area.caretColumn()+1;
		caretStatus.setText(International.t("Line {0}, Column {1}", line, column));
		errorStatus.setForeground(new Color(170, 25, 25));
		if(area.errorLine>=0){
			String message = area.errorMessage==null ? "" : area.errorMessage;
			errorStatus.setText(International.t("Error (line {0}): {1}", area.errorLine+1, message));
			errorStatus.setToolTipText(message);
		}else if(!area.diagnostics.isEmpty()){
			ScriptArea.ScriptDiagnostic atCaret = area.diagnosticAtLine(area.caretLine());
			ScriptArea.ScriptDiagnostic first = atCaret!=null ? atCaret : area.diagnostics.get(0);
			if(atCaret==null){
				//キャレットの行に指摘が無ければ、警告よりエラーを先に見せる
				for(ScriptArea.ScriptDiagnostic d : area.diagnostics) if(!d.warning){ first = d; break; }
			}
			int errors = area.errorCount();
			int warnings = area.diagnostics.size()-errors;
			String count = errors>0 && warnings>0 ? International.t("{0} syntax error(s), {1} warning(s)", errors, warnings)
					: errors>0 ? International.t("{0} syntax error(s)", errors) : warnings>0 ? International.t("{0} warning(s)", warnings) : "";
			errorStatus.setForeground(errors>0 ? new Color(170, 25, 25) : new Color(150, 95, 0));
			//件数 — 行: 指摘
			errorStatus.setText(International.tc("script", "{0} — {1}: {2}", count, first.line+1, first.message()));
			errorStatus.setToolTipText(first.message());
		}else{
			errorStatus.setText(" ");
			errorStatus.setToolTipText(null);
		}
	}
	
	/** HyperTalk簡易リファレンスを開く。選択中の語(1行)かキャレット位置の単語がリファレンスにあれば、それで検索する */
	void showReference(){
		GScriptReference.show(this, lookupWord(selectedArea()));
	}

	/** リファレンスで引く語: 選択中の短い文字列、またはキャレットのある(直前の)単語 */
	static String lookupWord(ScriptArea area){
		if(area==null) return null;
		String sel = area.getSelectedText();
		if(sel!=null && !sel.isBlank() && sel.indexOf('\n')<0 && sel.length()<=40) return sel.strip();
		String text = area.getText();
		int caret = Math.min(area.getCaretPosition(), text.length());
		int start = caret, end = caret;
		while(start>0 && Character.isLetterOrDigit(text.charAt(start-1))) start--;
		while(end<text.length() && Character.isLetterOrDigit(text.charAt(end))) end++;
		return start<end ? text.substring(start, end) : null;
	}

	/** スタック内検索ウインドウ(エディタごとに1つ。閉じても結果を保ったまま隠すだけ) */
	FindInStackDialog findInStackDialog = null;

	void showFindInStack(){
		if(findInStackDialog==null) findInStackDialog = new FindInStackDialog(this);
		findInStackDialog.show(FindStr);
	}

	/** スタック内検索の1件: オブジェクトのスクリプトの line 行目(0始まり)、column 文字目 */
	static class StackMatch {
		final OObject object;
		final int line;
		final int column;
		final int length;
		final String lineText;
		StackMatch(OObject object, int line, int column, int length, String lineText){
			this.object = object;
			this.line = line;
			this.column = column;
			this.length = length;
			this.lineText = lineText;
		}
	}

	/**
	 * スタック内のすべてのスクリプト(スタック、bg とその部品、カードとその部品)から文字列を探す。
	 * エディタで開いているスクリプトは保存前の内容を見る。
	 * 大文字小文字は caseSensitive=false なら区別しない(エディタの検索と同じ)。
	 */
	static ArrayList<StackMatch> findInStack(OStack st, String needle, boolean caseSensitive){
		ArrayList<StackMatch> matches = new ArrayList<StackMatch>();
		if(st==null || needle==null || needle.isEmpty()) return matches;
		for(OObject obj : scriptedObjects(st)){
			String[] lines = scriptLinesOf(obj);
			for(int i=0; i<lines.length; i++){
				String hay = caseSensitive ? lines[i] : lines[i].toLowerCase();
				String key = caseSensitive ? needle : needle.toLowerCase();
				int from = 0;
				while(true){
					int at = hay.indexOf(key, from);
					if(at<0) break;
					matches.add(new StackMatch(obj, i, at, needle.length(), lines[i]));
					from = at + Math.max(1, key.length());
				}
			}
		}
		return matches;
	}

	/** 検索対象のオブジェクトを、スタック → 各bgとその部品 → 各カードとその部品 の順に並べる */
	static ArrayList<OObject> scriptedObjects(OStack st){
		ArrayList<OObject> list = new ArrayList<OObject>();
		list.add(st);
		for(OBackground bg : st.backgroundsInOrder()){
			list.add(bg);
			addParts(list, bg);
		}
		ArrayList<OCard> cards = new ArrayList<OCard>();
		for(int i=0; i<st.cardIdList.size(); i++){
			OCard cd = st.GetCardbyId(st.cardIdList.get(i));
			if(cd!=null && !cards.contains(cd)) cards.add(cd);
		}
		//cardIdListに無いカード(テスト等)も落とさない
		for(OCard cd : st.cdCacheList) if(!cards.contains(cd)) cards.add(cd);
		for(OCard cd : cards){
			list.add(cd);
			addParts(list, cd);
		}
		return list;
	}

	private static void addParts(ArrayList<OObject> list, OCardBase owner){
		//partsListの順(重なり順)ではなく、ボタン → フィールド → シェイプの番号順で並べる
		if(owner.btnList!=null) list.addAll(owner.btnList);
		if(owner.fldList!=null) list.addAll(owner.fldList);
		if(owner.shapeList!=null) list.addAll(owner.shapeList);
		//パネルのスクリプト
		if(owner.panelList!=null) for(OPanel p : owner.panelList) if(!p.getScriptText().trim().isEmpty()) list.add(p);
	}

	/** 検索結果に出す名前: 部品は「部品 of カード/bg」まで(the long name のようにスタックのパスは付けない) */
	static String matchObjectName(OObject obj){
		if(obj instanceof OCardBase || obj.parent==null) return obj.getShortName();
		return obj.getShortName()+" of "+obj.parent.getShortName();
	}

	/** オブジェクトのスクリプトの各行。エディタで開いていれば未保存の内容 */
	static String[] scriptLinesOf(OObject obj){
		ScriptArea area = areaFor(obj);
		if(area!=null && area.isEditable()) return area.getText().split("\n", -1);
		if(obj.scriptList==null) return new String[0];
		return obj.scriptList.toArray(new String[0]);
	}

	/** 見つかった箇所をエディタで開き、その文字列を選択する */
	static void showMatch(StackMatch m){
		if(m==null || PCARD.pc==null) return;
		openScriptEditor(PCARD.pc, m.object, m.line);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				ScriptArea area = areaFor(m.object);
				if(area==null) return;
				String[] lines = area.getText().split("\n", -1);
				if(m.line>=lines.length) return;
				int start = 0;
				for(int i=0; i<m.line; i++) start += lines[i].length()+1;
				int from = Math.min(start + m.column, area.getText().length());
				int to = Math.min(from + m.length, area.getText().length());
				area.setSelectionStart(from);
				area.setSelectionEnd(to);
				//openScriptEditorがエディタをtoFrontするので検索ウインドウが隠れる。結果を続けて辿れるよう
				//検索ウインドウを前に戻し、フォーカスを失ってもエディタの選択範囲は見えたままにする
				FindInStackDialog dialog = PCARD.pc.stack==null || PCARD.pc.stack.scriptEditor==null ? null : PCARD.pc.stack.scriptEditor.findInStackDialog;
				if(dialog!=null && dialog.isVisible()){
					dialog.toFront();
					dialog.table.requestFocusInWindow();
					SwingUtilities.invokeLater(new Runnable() {
						public void run() { area.getCaret().setSelectionVisible(true); }
					});
				} else {
					area.requestFocusInWindow();
				}
			}
		});
	}

	public static void openScriptEditor(Frame owner, OObject obj) {
		openScriptEditor(owner, obj, -1);
	}
	
	private static Frame tmp_owner;
	private static OObject tmp_obj;
	private static int tmp_line;
	
	public static void openScriptEditor(Frame owner, OObject obj, int line) {
		tmp_owner = owner;
		tmp_obj = obj;
		tmp_line = line;
		
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				openScriptEditor1(tmp_owner, tmp_obj, tmp_line);
			}
		});
	}
	
	private static void openScriptEditor1(Frame owner, OObject obj, int line) {
		//System.out.println("openScriptEditor");
		
		ArrayList<String> scriptList = obj.scriptList;
		if(PCARD.pc.stack.scriptEditor == null) {
			PCARD.pc.stack.scriptEditor = new ScriptEditor(owner);
		}

		//2重オープンチェック
		if(tabPane!=null){
			for(int i=0; i<tabPane.getTabCount(); i++){
				if(!(tabPane.getComponentAt(i) instanceof JScrollPane)){
					continue;
				}
				JScrollPane scrPane = (JScrollPane)tabPane.getComponentAt(i);
				if(scrPane.getViewport().getComponentCount()<1){
					continue;
				}
				if(scrPane.isValid()==false){
					PCARD.pc.stack.scriptEditor.dispose();
					PCARD.pc.stack.scriptEditor = new ScriptEditor(owner);
					return;//壊れている
				}
				ScriptArea area = (ScriptArea)scrPane.getViewport().getComponent(0);
				if(area.compareObject(obj) && area.isEditable()==(obj.osaScript!=null)){
					//the scriptingLanguage changed since the tab was opened (AppleScript description ↔ HyperTalk): open it anew
					tabPane.remove(i);
					break;
				}
				if(area.compareObject(obj)==true){
					if(line >= 0){
						//指定行にジャンプ
						String[] newAry = area.getText().split("\n");
						int selStart = 0;
						int selEnd = 0;
						for(int j=0; j<newAry.length; j++){
							if(line == j) {
								selEnd = selStart+newAry[j].length();
								break;
							}
							selStart += newAry[j].length()+1;
						}
						area.setSelectionStart(selStart);
						if(selEnd>0) area.setSelectionEnd(selEnd);
					}
					
					//先に正しいタブを選んでから見せることで、開き直したときに前回の
					//(今回開こうとしているのとは違う)スクリプトが一瞬表示されるのを防ぐ
					tabPane.setSelectedIndex(i);
					tabPane.setVisible(true);
					scrPane.setVisible(true);
					area.setVisible(true);

					PCARD.pc.stack.scriptEditor.setVisible(true);
					PCARD.pc.stack.scriptEditor.toFront();
					area.requestFocus();
					return;
				}
			}
		}
		
		JScrollPane scrollpane = new JScrollPane();
		scrollpane.setName("JScrollPane");
		scrollpane.setBounds(0, 0, 640, 640);
		scrollpane.setPreferredSize(new Dimension(640, 640));
		scrollpane.getVerticalScrollBar().setValue(0);
		scrollpane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scrollpane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		tabPane.addTab(obj.getShortName(), scrollpane);
		tabPane.setTabComponentAt(tabPane.indexOfComponent(scrollpane), new TabHeader(scrollpane));

		String text = "";
		for(int i=0; i<scriptList.size(); i++){
			text += scriptList.get(i);
			if(i<scriptList.size()-1) text += "\n";
		}
		//a compiled AppleScript (OSA) object script cannot be shown or edited: a read-only description instead (OsaScript)
		boolean osa = obj.osaScript!=null;
		if(osa) text = OsaScript.placeholder(obj);
		else if((obj.objectType.equals("button") || obj.objectType.equals("shape")) && text.length()==0){
			text = "on mouseUp\n  \nend mouseUp";
			line = 1;
		}
		
		//area
		ScriptArea area = new ScriptArea(PCARD.pc.stack.scriptEditor, text);
		area.checkIndentCall();
		area.setObject(obj);
		if(osa) area.setEditable(false);
		area.setMargin(new Insets(2,ScriptArea.MARKER_MARGIN,2,2));
		area.getPreferredSize();
		area.setOpaque(false);
		area.setCaretPosition(0);
		//scrollpane.add(area);
		scrollpane.setViewportView(area);
		
		
		if(tabPane.getTabCount()>2){
			for(int i=0; i<tabPane.getTabCount(); i++){
				if(!(tabPane.getComponentAt(i) instanceof JScrollPane)) continue;
				JScrollPane scrPane = (JScrollPane)tabPane.getComponentAt(i);
				if(scrPane.getViewport().getComponentCount()>=1){
					ScriptArea a = (ScriptArea)scrPane.getViewport().getComponent(0);
					if(a!=null) tabPane.setTitleAt(i, a.object.getShortShortName());
				}
			}
		}
		refreshTabHeaders();

		if(line >= 0){
			//指定行にジャンプ
			String[] newAry = area.getText().split("\n");
			int selStart = 0;
			int selEnd = 0;
			for(int j=0; j<newAry.length; j++){
				if(line == j) {
					int spacing = 0;
					while(spacing<newAry[j].length() && (newAry[j].charAt(spacing)==' ' || newAry[j].charAt(spacing)=='　')) spacing++;
					selStart += spacing;
					selEnd = selStart+newAry[j].length()-spacing;
					break;
				}
				selStart += newAry[j].length()+1;
			}
			area.setSelectionStart(selStart);
			if(selEnd>0) area.setSelectionEnd(selEnd);
		}

		//先に新しいタブを選んでから見せることで、開き直したときに前回の
		//(今回開こうとしているのとは違う)スクリプトが一瞬表示されるのを防ぐ
		try{
			tabPane.setSelectedComponent(scrollpane);
		} catch(Exception e){
			System.out.println("Error: tabPane.setSelectedComponent(scrollpane);");
		}
		//addTab時点ではまだScriptAreaが無かったのでここで作り直す
		PCARD.pc.stack.scriptEditor.refreshHandlerPopup();

		PCARD.pc.stack.scriptEditor.toFront();

		try{
			PCARD.pc.stack.scriptEditor.setVisible(true);
		} catch(Exception e){
			System.out.println("Error: PCARD.pc.stack.scriptEditor.setVisible(true);");
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					PCARD.pc.stack.scriptEditor.setVisible(true);
				}
			});
		}

		area.requestFocus();
	}

	/** Answers the question asked when a stack with unsaved scripts is left (tests): 0 Save, 1 Don't Save, 2 Cancel. */
	static java.util.function.IntSupplier leaveAlertForTest = null;

	/**
	 * The stack on show is about to be left or closed, and then written: each unsaved script in its editor is saved or
	 * dropped as the user says, so what is saved goes to disk with the stack. Only the frontmost stack is live (HyperCard
	 * 2.x model, GStackWindow), so the editor cannot stay open with the stack left: it closes in stackClosed. Called on the
	 * script or quit thread (GDialog blocks it). false: Cancel, the stack stays.
	 */
	static boolean stackLeaving(OStack stack){
		ScriptEditor editor = stack==null ? null : stack.scriptEditor;
		if(editor==null) return true;
		ArrayList<ScriptArea> unsaved = new ArrayList<ScriptArea>();
		onEDT(() -> { for(ScriptArea area : openAreas(editor.tabs)) if(!area.saved && area.isEditable()) unsaved.add(area); });
		for(ScriptArea area : unsaved){
			int answer;
			if(leaveAlertForTest!=null) answer = leaveAlertForTest.getAsInt();
			else{
				onEDT(() -> {
					Component tab = SwingUtilities.getAncestorOfClass(JScrollPane.class, area);
					if(tab!=null && editor.tabs.indexOfComponent(tab)>=0) editor.tabs.setSelectedComponent(tab);
					editor.setVisible(true);
					editor.toFront();
				});
				String cancel = PCARD.pc.intl.getDialogText("Cancel");
				String dontSave = PCARD.pc.intl.getDialogText("Don't Save");
				String save = PCARD.pc.intl.getDialogText("Save");
				String name = area.object==null ? "" : area.object.getShortName();
				new GDialog(null, PCARD.pc.intl.getDialogText("Save changes to the script of “%1” before closing?").replace("%1", name),
						null, cancel, dontSave, save);
				answer = GDialog.clicked.equals(save) ? 0 : GDialog.clicked.equals(dontSave) ? 1 : 2;
			}
			if(answer==2) return false;
			if(answer==0) onEDT(() -> saveScript(area));
		}
		return true;
	}

	/** The stack is closed or left: its editor goes, its tabs show objects of that stack (OStack.clean). */
	static void stackClosed(OStack stack){
		ScriptEditor editor = stack==null ? null : stack.scriptEditor;
		if(editor==null) return;
		Runnable close = () -> {
			if(tabPane==editor.tabs) tabPane = null;
			editor.dispose();
		};
		if(SwingUtilities.isEventDispatchThread()) close.run();
		else SwingUtilities.invokeLater(close);
	}

	private static void onEDT(Runnable r){
		if(SwingUtilities.isEventDispatchThread()){ r.run(); return; }
		try{ SwingUtilities.invokeAndWait(r); }
		catch(InterruptedException e){ Thread.currentThread().interrupt(); }
		catch(java.lang.reflect.InvocationTargetException e){ e.getCause().printStackTrace(); }
	}

	/** 開いている全タブのScriptArea */
	static ArrayList<ScriptArea> openAreas(){
		return openAreas(tabPane);
	}

	static ArrayList<ScriptArea> openAreas(JTabbedPane tabPane){
		ArrayList<ScriptArea> areas = new ArrayList<ScriptArea>();
		if(tabPane == null) return areas;
		for(int i=0; i<tabPane.getTabCount(); i++){
			if(!(tabPane.getComponentAt(i) instanceof JScrollPane)) continue;
			JViewport vp = ((JScrollPane)tabPane.getComponentAt(i)).getViewport();
			if(vp.getComponentCount()<1) continue;
			if(vp.getComponent(0) instanceof ScriptArea) areas.add((ScriptArea)vp.getComponent(0));
		}
		return areas;
	}

	static ScriptArea areaFor(OObject obj){
		for(ScriptArea area : openAreas()){
			if(area.compareObject(obj)) return area;
		}
		return null;
	}

	/**
	 * デバッガが文で止まった(またはトレース中に通過した)ときに、その行をエディタで示す。
	 * スクリプトスレッドから呼ばれるのでEDTに回す。
	 */
	static void showDebugLine(OObject obj, int line, boolean error){
		if(PCARD.pc==null || obj==null) return;
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				for(ScriptArea area : openAreas()){
					if(area.debugLine>=0 && !area.compareObject(obj)) area.setDebugLine(-1, false);
				}
				ScriptArea area = areaFor(obj);
				ScriptEditor editor = PCARD.pc.stack==null ? null : PCARD.pc.stack.scriptEditor;
				if(area==null || editor==null || !editor.isVisible()){
					openScriptEditor1(PCARD.pc, obj, line);
					area = areaFor(obj);
				}
				else{
					//すでに開いているならタブと行を合わせるだけ(トレース中に毎文呼ばれるので軽くする)
					Component tab = SwingUtilities.getAncestorOfClass(JScrollPane.class, area);
					if(tab!=null && tabPane.getSelectedComponent()!=tab) tabPane.setSelectedComponent(tab);
					area.selectLine(line);
				}
				if(area!=null) area.setDebugLine(line, error);
			}
		});
	}

	/**
	 * 実行時エラーを、そのスクリプトを次に開いたときにも残る赤い行として表示する。
	 * エディタがすでに開いている場合だけ即時更新し、エラーダイアログの前面を奪わない。
	 */
	static void showRuntimeError(OObject obj){
		if(obj==null) return;
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				ScriptArea area = areaFor(obj);
				if(area!=null) area.setRuntimeError(obj.lastErrorLine, obj.lastErrorMessage);
			}
		});
	}

	/** デバッガの現在行表示を消す */
	static void clearDebugLine(){
		if(PCARD.pc==null) return;
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				for(ScriptArea area : openAreas()){
					if(area.debugLine>=0) area.setDebugLine(-1, false);
				}
			}
		});
	}

	static void maybeExit() {
		for(int i=0; i<tabPane.getTabCount(); i++){
			if(!(tabPane.getComponentAt(i) instanceof JScrollPane)){
				continue;
			}
			JScrollPane scrPane = (JScrollPane)tabPane.getComponentAt(i);
			if(scrPane.getViewport().getComponentCount()==0) return;
			ScriptArea area = (ScriptArea)scrPane.getViewport().getComponent(0);
			if(false == saveAlert(area)){
				return;//クローズキャンセル
			}
		}
		PCARD.pc.stack.scriptEditor.setVisible(false);
		//PCARD.pc.stack.scriptEditor.dispose();
		//PCARD.pc.stack.scriptEditor = null;
	}

	/** タブを1つ閉じる。未保存なら確認し、キャンセルされたら何もしない。最後のタブならウィンドウごと閉じる */
	static void closeTab(Component tabComponent){
		if(tabPane == null || tabComponent == null) return;
		int index = tabPane.indexOfComponent(tabComponent);
		if(index < 0) return;
		ScriptArea area = null;
		if(tabComponent instanceof JScrollPane){
			JViewport vp = ((JScrollPane)tabComponent).getViewport();
			if(vp.getComponentCount() > 0) area = (ScriptArea)vp.getComponent(0);
		}
		if(false == saveAlert(area)){
			return;//クローズキャンセル
		}
		tabPane.removeTabAt(index);
		if(0 == tabPane.getTabCount()){
			maybeExit();
		} else {
			refreshTabHeaders();
			Component sel = tabPane.getSelectedComponent();
			if(sel instanceof JScrollPane){
				JViewport vp = ((JScrollPane)sel).getViewport();
				if(vp.getComponentCount() > 0) vp.getComponent(0).requestFocus();
			}
		}
	}

	/** 複数タブが開いているときだけ各タブに閉じるボタンを出す */
	static void refreshTabHeaders(){
		if(tabPane == null) return;
		boolean showClose = tabPane.getTabCount() > 1;
		for(int i=0; i<tabPane.getTabCount(); i++){
			Component h = tabPane.getTabComponentAt(i);
			if(h instanceof TabHeader){
				((TabHeader)h).setCloseVisible(showClose);
			}
		}
	}

	/** タブ見出し: タイトル + 閉じるボタン */
	static class TabHeader extends JPanel {
		private static final long serialVersionUID = 1L;
		private final JButton closeButton;

		TabHeader(final Component tabComponent){
			super(new FlowLayout(FlowLayout.LEFT, 0, 0));
			setOpaque(false);

			//タイトルは常にJTabbedPane側のものを表示する(setTitleAtに追従)
			JLabel label = new JLabel() {
				private static final long serialVersionUID = 1L;
				@Override
				public String getText() {
					if(tabPane == null) return "";
					int i = tabPane.indexOfComponent(tabComponent);
					return i >= 0 ? tabPane.getTitleAt(i) : "";
				}
			};
			label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
			add(label);

			//L&Fのボタン余白で"×"が"…"に省略されるので、文字ではなく自前で描く
			closeButton = new JButton() {
				private static final long serialVersionUID = 1L;
				private boolean hover = false;
				{
					addMouseListener(new MouseAdapter() {
						@Override
						public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
						@Override
						public void mouseExited(MouseEvent e) { hover = false; repaint(); }
					});
				}
				@Override
				protected void paintComponent(Graphics g) {
					Graphics2D g2 = (Graphics2D)g.create();
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					int w = getWidth(), h = getHeight();
					if(hover){
						g2.setColor(new Color(0, 0, 0, 40));
						g2.fillRoundRect(1, 1, w-2, h-2, 6, 6);
					}
					g2.setColor(hover ? Color.BLACK : Color.DARK_GRAY);
					g2.setStroke(new BasicStroke(1.5f));
					int m = 5;
					g2.drawLine(m, m, w-m-1, h-m-1);
					g2.drawLine(w-m-1, m, m, h-m-1);
					g2.dispose();
				}
			};
			closeButton.setToolTipText(PCARD.pc.intl.getText("Close"));
			closeButton.setPreferredSize(new Dimension(16, 16));
			closeButton.setFocusable(false);
			closeButton.setBorderPainted(false);
			closeButton.setContentAreaFilled(false);
			closeButton.setOpaque(false);
			closeButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					closeTab(tabComponent);
				}
			});
			add(closeButton);

			//見出しをクリックしたらそのタブを選択する(ボタン以外の部分)
			MouseAdapter selectOnClick = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					int i = tabPane.indexOfComponent(tabComponent);
					if(i >= 0) tabPane.setSelectedIndex(i);
				}
			};
			addMouseListener(selectOnClick);
			label.addMouseListener(selectOnClick);
		}

		void setCloseVisible(boolean visible){
			closeButton.setVisible(visible);
			revalidate();
			repaint();
		}
	}

	static boolean saveAlert(ScriptArea area){
		if(area == null || area.saved) {
			return true;
		}
		java.awt.Toolkit.getDefaultToolkit().beep();
		Object[] options = { PCARD.pc.intl.getDialogText("Save"),
				PCARD.pc.intl.getDialogText("Discard"),
				PCARD.pc.intl.getDialogText("Cancel") };
		int retValue = JOptionPane.showOptionDialog(PCARD.pc.stack.scriptEditor,
				PCARD.pc.intl.getDialogText("Script is not saved."),
				"Exit Options",
				JOptionPane.YES_NO_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE, null, options, options[0]);
		if(retValue==JOptionPane.YES_OPTION) {
			//保存する
			saveScript(area);
		}else if(retValue==JOptionPane.NO_OPTION) {
			//保存しない
		}else {
			//キャンセル、またはダイアログの閉じるボタン(CLOSED_OPTION): 編集に戻る
			return false;
		}
		return true;
	}
	
	static void saveScript(ScriptArea area){
		//the read-only description of a compiled AppleScript object script is never a script to save
		if(!area.isEditable()) return;
		String allStr = "";
		try {
			allStr = area.getDocument().getText(0, area.getDocument().getLength());
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
		
		/*OObject obj = null;
		//カード(bg)を開いているなら、メモリ上にあるオブジェクトの情報を書き換える
		if(area.ParentId==0 || area.compareParent(PCARD.pc.stack.curCard)){
			obj = area.getObject(PCARD.pc.stack.curCard);
		} else {
			obj = area.getObjectOtherCard();
		}*/
		OObject obj = area.object;
		
		if(obj != null){
			obj.setScript(allStr);
			OStack.userEdit();
			area.setRuntimeError(-1, null);
			area.savedScript();
		} else {
			System.out.println("スクリプトの保存に失敗しました");
		}
	}
	
	
	/** デバッグメニューは常にメニューバーにあるので、ここではエディタを用意するだけ */
	static void setTracemode(){
		if(PCARD.pc==null || PCARD.pc.stack==null) return;
		if(PCARD.pc.stack.scriptEditor == null){
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					if(PCARD.pc.stack!=null && PCARD.pc.stack.scriptEditor == null){
						PCARD.pc.stack.scriptEditor = new ScriptEditor(PCARD.pc);
					}
				}
			});
		}
	}
}

class seListener implements DocumentListener
{
	ScriptArea area;
	
	seListener(ScriptArea inarea){
		super();
		area = inarea;
	}
	
	public void changedUpdate(DocumentEvent e) {
		area.documentChanged(e);
	}
	public void insertUpdate(DocumentEvent e) {
		String changeStr = area.getText().substring(e.getOffset(), e.getOffset()+e.getLength());
		if(changeStr.equals("\n")){
			area.checkIndentCall();
		}
		if(changeStr.contains("\t")){
			area.documentChanged(e);
			area.checkIndentCall();
			return;
		}
		area.documentChanged(e);
		area.scheduleCheckWords();
		area.handlersMayHaveChanged();
	}
	public void removeUpdate(DocumentEvent e) {
		area.documentChanged(e);
		area.scheduleCheckWords();
		area.handlersMayHaveChanged();
	}
}

class ScriptArea extends JTextPane {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	OObject object;
	//
	boolean saved = true;
	//UndoManager undo = null;
	ScriptEditor parent = null;
	//String ParentType;
	//int ParentId;
	//String ObjType;
	//int ObjId;
	seListener seListener = null;
	private static final int COLORING_DELAY_MILLIS = 120;
	private static final Color COLOR_STRING = new Color(0, 0, 16);
	private static final Color COLOR_COMMENT = new Color(0, 96, 0);
	private static final Color COLOR_XCMD = new Color(64, 0, 128);
	private static final Color COLOR_COMMAND = new Color(64, 0, 32);
	private static final Color COLOR_FUNCTION = new Color(16, 0, 8);
	private static final Color COLOR_VARIABLE = new Color(0, 64, 128);
	private static final Color COLOR_OBJECT = new Color(16, 0, 0);
	private static final Color COLOR_DEFAULT = new Color(0, 0, 0);
	private static final Color COLOR_OTHER = new Color(16, 16, 16);
	private static final Color COLOR_IF = new Color(128, 32, 0);
	private static final Color COLOR_HANDLER = new Color(128, 0, 0);
	private static final Color COLOR_REPEAT = new Color(128, 64, 0);
	private static final Color COLOR_PROPERTY = new Color(0, 32, 0);
	private final Timer checkWordsTimer;
	private volatile int checkWordsGeneration;
	private boolean checkWordsRunning;
	private boolean checkWordsPending;
	/** 左余白(チェックポイント、エラー印、行番号用) */
	static final int MARKER_MARGIN = 58;
	private static final Color COLOR_DEBUG_LINE = new Color(255, 236, 128);
	private static final Color COLOR_ERROR_LINE = new Color(255, 190, 190);
	private static final Color COLOR_CHECKPOINT = new Color(200, 40, 40);
	private static final Color COLOR_GUTTER = new Color(244, 244, 244);
	private static final Color COLOR_GUTTER_TEXT = new Color(115, 115, 115);
	private static final Color COLOR_CURRENT_LINE = new Color(238, 244, 252);
	private static final Color COLOR_STATIC_ERROR_LINE = new Color(255, 235, 235);
	private static final Color COLOR_WARNING_LINE = new Color(255, 248, 214);
	private static final Color COLOR_WARNING_MARK = new Color(214, 150, 0);
	/** デバッガが止まっている行(-1なら無し) */
	int debugLine = -1;
	boolean debugError = false;
	/** 最後に実行時エラーになった行。デバッガの再開後も編集/保存まで残す */
	int errorLine = -1;
	String errorMessage;
	/** 入力中のスクリプトを実行せずに見つけた構文エラー。EDT上でのみ差し替える。 */
	final ArrayList<ScriptDiagnostic> diagnostics = new ArrayList<ScriptDiagnostic>();

	static class ScriptDiagnostic {
		final int line;
		/** 英語の文面({0}… に args を入れる前)。表示するときに訳す */
		final String template;
		final Object[] args;
		/** 英語の文面(並べ替えと重複の判定に使う) */
		final String english;
		/** 実行すれば必ずエラーになるものではない指摘(見つからないコマンド名、重複したハンドラ) */
		final boolean warning;

		ScriptDiagnostic(int line, String template, boolean warning, Object... args){
			this.line = line;
			this.template = template;
			this.args = args;
			this.english = International.format(template, args);
			this.warning = warning;
		}

		/** 使用中の言語の文面 */
		String message(){
			return International.t(template, args);
		}
	}
	
	public ScriptArea(ScriptEditor se, String text) {
		super();
		parent = se;
		//undo = new UndoManager();
		undobuf.add(text);
		
		setFont(new Font(PCARD.scriptFont, 0, PCARD.scriptFontSize));

		StyleContext scontext = new StyleContext();
		DefaultStyledDocument doc = new DefaultStyledDocument(scontext);
		setDocument(doc);

	    try{
	    	doc.insertString(0, text, scontext.getStyle(StyleContext.DEFAULT_STYLE));
	    }catch (BadLocationException e){
	    	e.printStackTrace();
	    }

		//getDocument().addUndoableEditListener(undo);
	    //getDocument().addUndoableEditListener(new UndoableEditListener() {
			//public void undoableEditHappened(UndoableEditEvent e) {
				//行われた編集(文字の追加や削除)をUndoManagerに登録
				/*if (checkThreadCnt==0) {
					sub.addEdit(e.getEdit());
					sub.end();
					undo.addEdit(sub);
					sub = new UndoManager();
					sub.setLimit(10000);
				}
				else{
					sub.addEdit(e.getEdit());
				}*/
				
				/*
				 * javax.swing.undoではまったのでメモ。
					例えば、文字列の置換のような削除、挿入という複数の処理を１回で元に戻したい場合は
					UndoManagerを入れ子にする。
					
					UndoManagerを２つ用意して、基本はサブに追加する。一塊の処理が終わったらend()を呼んでメインに追加する。
					
					void undoableEditHappened(UndoableEditEvent e) {
					　　sub.addEdit(e.getEdit());
					　　if (!compound) {
					　　　　sub.end();
					　　　　main.addEdit(sub);
					　　　　sub = new UndoManager();
					　　}
					}
				 */
			//}
		//});
		
		addFocusListener(new SAFocusListener());
		seListener = new seListener(this);
		getDocument().addDocumentListener(seListener);
		//キャレットの移動に合わせてハンドラポップアップの選択を追従させる
		addCaretListener(e -> {
			if(parent != null){
				parent.syncHandlerPopupToCaret(this);
				parent.refreshStatus(this);
			}
			repaint();
		});
		checkWordsTimer = new Timer(COLORING_DELAY_MILLIS, e -> startCheckWordsThread());
		checkWordsTimer.setRepeats(false);

		//左余白のクリックでチェックポイントを設定/解除
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if(e.getX() >= getMargin().left || !SwingUtilities.isLeftMouseButton(e)) return;
				int line = lineAtY(e.getY());
				if(line>=0){
					toggleCheckpoint(line);
					e.consume();
				}
			}
		});
		//空文字でも登録しておくと、行番号上にマウスを置いたとき動的tooltipが呼ばれる
		setToolTipText("");
	}

	//---------------------------------------------------------------- ハンドラポップアップ

	/** ハンドラポップアップの1項目: 表示名と先頭行(0始まり) */
	static class HandlerEntry {
		final String label;
		final int line;
		HandlerEntry(String label, int line){
			this.label = label;
			this.line = line;
		}
		@Override
		public String toString(){ return label; }
	}

	/**
	 * スクリプト中のハンドラ定義行(on xxx / function xxx)を出現順に列挙する。
	 * 行頭の空白(全角含む)は無視し、ハンドラ名の後ろ(引数やコメント)は落とす。
	 */
	static ArrayList<HandlerEntry> listHandlers(String script){
		ArrayList<HandlerEntry> handlers = new ArrayList<HandlerEntry>();
		if(script == null) return handlers;
		String[] lines = script.split("\n", -1);
		for(int i=0; i<lines.length; i++){
			String[] words = lines[i].strip().split("[ \t　]+");
			if(words.length < 2 || words[1].isEmpty()) continue;
			String keyword;
			if(words[0].equalsIgnoreCase("on")) keyword = "on";
			else if(words[0].equalsIgnoreCase("function")) keyword = "function";
			else continue;
			String name = words[1];
			int comment = name.indexOf("--");
			if(comment == 0) continue;
			if(comment > 0) name = name.substring(0, comment);
			handlers.add(new HandlerEntry(keyword+" "+name, i));
		}
		return handlers;
	}

	/** ハンドラの先頭行を選択し、その行ができるだけ表示範囲の上端に来るようにスクロールする */
	void jumpToHandler(int line){
		selectLine(line);
		try{
			Rectangle r = modelToView2D(getSelectionStart()).getBounds();
			Rectangle visible = getVisibleRect();
			if(r != null && visible.height > 0){
				//現在位置より下のハンドラは行を上端へ、上のハンドラは行から1画面分を見せる
				scrollRectToVisible(new Rectangle(0, r.y, 1, visible.height));
				scrollRectToVisible(new Rectangle(0, r.y, 1, r.height));
			}
		}catch(BadLocationException e){
		}
	}

	/** 編集でハンドラの一覧が変わったかもしれないのでポップアップを更新してもらう */
	void handlersMayHaveChanged(){
		if(parent != null) parent.scheduleHandlerPopupRefresh(this);
	}

	//---------------------------------------------------------------- デバッガ表示

	void setDebugLine(int line, boolean error){
		debugLine = line;
		debugError = error;
		repaint();
	}

	void setRuntimeError(int line, String message){
		errorLine = line;
		errorMessage = message;
		if(parent!=null) parent.refreshStatus(this);
		repaint();
	}

	void setDiagnostics(ArrayList<ScriptDiagnostic> found){
		diagnostics.clear();
		diagnostics.addAll(found);
		if(parent!=null) parent.refreshStatus(this);
		repaint();
	}

	/** その行の指摘(エラーを警告より先に並べてあるので、あればエラー) */
	ScriptDiagnostic diagnosticAtLine(int line){
		for(ScriptDiagnostic diagnostic : diagnostics) if(diagnostic.line==line) return diagnostic;
		return null;
	}

	int errorCount(){
		int n = 0;
		for(ScriptDiagnostic diagnostic : diagnostics) if(!diagnostic.warning) n++;
		return n;
	}

	int nextDiagnosticLine(){
		if(diagnostics.isEmpty()) return -1;
		int line = caretLine();
		for(ScriptDiagnostic diagnostic : diagnostics) if(diagnostic.line>line) return diagnostic.line;
		return diagnostics.get(0).line;
	}

	@Override
	public String getToolTipText(MouseEvent e){
		if(errorLine>=0 && lineAtY(e.getY())==errorLine){
			return errorMessage==null || errorMessage.isEmpty() ? "Script Error" : errorMessage;
		}
		int hoverLine = lineAtY(e.getY());
		if(diagnosticAtLine(hoverLine)!=null){
			StringBuilder text = new StringBuilder();
			for(ScriptDiagnostic diagnostic : diagnostics){
				if(diagnostic.line!=hoverLine) continue;
				if(text.length()>0) text.append('\n');
				text.append(International.t(diagnostic.warning ? "Warning: {0}" : "Error: {0}", diagnostic.message()));
			}
			return text.toString();
		}
		if(e.getX()<getMargin().left){
			return International.t("Click to toggle checkpoint");
		}
		return null;
	}

	/** y座標にある行番号(0始まり)。行が無ければ-1 */
	int lineAtY(int y){
		int pos = viewToModel2D(new Point(getMargin().left+1, y));
		if(pos<0) return -1;
		return getDocument().getDefaultRootElement().getElementIndex(pos);
	}

	/** 行(0始まり)の先頭の空白を除いた部分を選択して表示範囲に入れる */
	void selectLine(int line){
		javax.swing.text.Element root = getDocument().getDefaultRootElement();
		if(line<0 || line>=root.getElementCount()) return;
		javax.swing.text.Element element = root.getElement(line);
		int start = element.getStartOffset();
		int end = Math.max(start, element.getEndOffset()-1);
		try{
			String text = getDocument().getText(start, end-start);
			int spacing = 0;
			while(spacing<text.length() && (text.charAt(spacing)==' ' || text.charAt(spacing)=='　')) spacing++;
			start += spacing;
		}catch(BadLocationException e){
		}
		setSelectionStart(start);
		setSelectionEnd(end);
	}

	/** キャレットのある行番号(0始まり) */
	int caretLine(){
		return getDocument().getDefaultRootElement().getElementIndex(getCaretPosition());
	}

	/** キャレットの行頭からの文字数(0始まり)。 */
	int caretColumn(){
		javax.swing.text.Element root = getDocument().getDefaultRootElement();
		javax.swing.text.Element line = root.getElement(caretLine());
		return line==null ? 0 : Math.max(0, getCaretPosition()-line.getStartOffset());
	}

	/** エディタ上の行番号のチェックポイントを設定/解除する */
	void toggleCheckpoint(int line){
		if(object==null || line<0) return;
		object.toggleCheckpoint(line);
		repaint();
	}

	void clearCheckpoints(){
		if(object==null) return;
		object.clearCheckpoints();
		repaint();
	}

	private Rectangle lineBounds(int line){
		javax.swing.text.Element root = getDocument().getDefaultRootElement();
		if(line<0 || line>=root.getElementCount()) return null;
		javax.swing.text.Element element = root.getElement(line);
		try{
			java.awt.geom.Rectangle2D start = modelToView2D(element.getStartOffset());
			java.awt.geom.Rectangle2D end = modelToView2D(Math.max(element.getStartOffset(), element.getEndOffset()-1));
			if(start==null) return null;
			int top = (int)start.getY();
			int bottom = end==null ? (int)(start.getY()+start.getHeight()) : (int)(end.getY()+end.getHeight());
			return new Rectangle(0, top, getWidth(), Math.max(1, bottom-top));
		}catch(BadLocationException e){
			return null;
		}
	}

	@Override
	protected void paintComponent(Graphics g) {
		//静的解析のエラー、現在行、実行時エラー/停止行の背景
		HashSet<Integer> diagnosticLines = new HashSet<Integer>();
		HashSet<Integer> errorLines = new HashSet<Integer>();
		for(ScriptDiagnostic diagnostic : diagnostics){
			diagnosticLines.add(diagnostic.line);
			if(!diagnostic.warning) errorLines.add(diagnostic.line);
		}
		for(int line : diagnosticLines){
			Rectangle r = lineBounds(line);
			g.setColor(errorLines.contains(line) ? COLOR_STATIC_ERROR_LINE : COLOR_WARNING_LINE);
			if(r!=null) g.fillRect(getMargin().left, r.y, Math.max(0, r.width-getMargin().left), r.height);
		}
		Rectangle current = lineBounds(caretLine());
		if(current!=null && debugLine<0 && errorLine!=caretLine() && !diagnosticLines.contains(caretLine())){
			g.setColor(COLOR_CURRENT_LINE);
			g.fillRect(getMargin().left, current.y, Math.max(0, current.width-getMargin().left), current.height);
		}
		int highlightedLine = debugLine>=0 ? debugLine : errorLine;
		if(highlightedLine>=0){
			Rectangle r = lineBounds(highlightedLine);
			if(r!=null){
				g.setColor((debugLine>=0 && !debugError) ? COLOR_DEBUG_LINE : COLOR_ERROR_LINE);
				g.fillRect(r.x, r.y, r.width, r.height);
			}
		}
		super.paintComponent(g);

		//固定幅のガター: 行番号、チェックポイント、実行時エラー
		Graphics2D gutter = (Graphics2D)g.create();
		Rectangle clip = g.getClipBounds();
		gutter.setColor(COLOR_GUTTER);
		gutter.fillRect(0, clip.y, getMargin().left-3, clip.height);
		gutter.setColor(new Color(215, 215, 215));
		gutter.drawLine(getMargin().left-4, clip.y, getMargin().left-4, clip.y+clip.height);
		gutter.setFont(getFont().deriveFont(Math.max(10.0f, getFont().getSize2D()-1.0f)));
		FontMetrics fm = gutter.getFontMetrics();
		javax.swing.text.Element root = getDocument().getDefaultRootElement();
		int firstVisible = root.getElementIndex(Math.max(0,
				viewToModel2D(new Point(getMargin().left+1, Math.max(0, clip.y)))));
		int lastVisible = root.getElementIndex(Math.max(0,
				viewToModel2D(new Point(getMargin().left+1, Math.max(0, clip.y+clip.height)))));
		for(int line=firstVisible; line<=lastVisible && line<root.getElementCount(); line++){
			Rectangle r = lineBounds(line);
			if(r==null || r.y+r.height<clip.y || r.y>clip.y+clip.height) continue;
			String number = Integer.toString(line+1);
			gutter.setColor(line==caretLine() ? Color.DARK_GRAY : COLOR_GUTTER_TEXT);
			int baseline = r.y+(r.height-fm.getHeight())/2+fm.getAscent();
			gutter.drawString(number, getMargin().left-9-fm.stringWidth(number), baseline);
			if(line==errorLine || diagnosticLines.contains(line)){
				gutter.setColor(line==errorLine || errorLines.contains(line) ? COLOR_CHECKPOINT : COLOR_WARNING_MARK);
				int[] xs = {3, 11, 3};
				int mid = r.y+r.height/2;
				int[] ys = {mid-5, mid, mid+5};
				gutter.fillPolygon(xs, ys, 3);
			}
		}
		gutter.dispose();

		//チェックポイントのマーク
		TreeSet<Integer> checkpoints = object==null ? null : object.checkpoints;
		if(checkpoints!=null && !checkpoints.isEmpty()){
			Graphics2D g2 = (Graphics2D)g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(COLOR_CHECKPOINT);
			for(int line : new ArrayList<Integer>(checkpoints)){
				Rectangle r = lineBounds(line);
				if(r==null) continue;
				int d = Math.min(9, r.height-2);
				g2.fillOval(16, r.y+(r.height-d)/2, d, d);
			}
			g2.dispose();
		}
	}

	static boolean flag;
	
	void checkIndentCall(){
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				if(flag==false){
					flag = true;
					checkIndent();
					scheduleCheckWords();
					flag = false;
				}
			}
		});
	}

	void scheduleCheckWords(){
		if(!SwingUtilities.isEventDispatchThread()){
			SwingUtilities.invokeLater(this::scheduleCheckWords);
			return;
		}
		checkWordsGeneration++;
		checkWordsTimer.restart();
	}

	private synchronized void startCheckWordsThread(){
		if(checkWordsRunning){
			checkWordsPending = true;
			return;
		}
		checkWordsRunning = true;
		checkWordsPending = false;
		checkWordsThread thread = new checkWordsThread(checkWordsGeneration);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.setDaemon(true);
		thread.start();
	}

	private void finishCheckWordsThread(){
		boolean restart;
		synchronized(this){
			checkWordsRunning = false;
			restart = checkWordsPending;
			checkWordsPending = false;
		}
		if(restart){
			SwingUtilities.invokeLater(this::startCheckWordsThread);
		}
	}

	class checkWordsThread extends Thread{
		private final int generation;

		checkWordsThread(int generation){
			this.generation = generation;
		}

		public void run(){
			try{
				checkWords(generation);
			}finally{
				finishCheckWordsThread();
			}
		}
	}

	void checkIndent(){
		String script = getText();
		
		int selectedStartLine = (script.substring(0,getSelectionStart())+" ").split("\n").length-1;
		int selectedEndLine = (script.substring(0,getSelectionEnd())+" ").split("\n").length-1;
		int selectedStartChar = getSelectionStart() - script.substring(0,getSelectionStart()).lastIndexOf("\n")-1;
		int selectedEndChar = getSelectionEnd() - script.substring(0,getSelectionEnd()).lastIndexOf("\n")-1;
		
		//タブ文字を消す
		if(script.indexOf("\t")>= 0){
			script = script.substring(0,script.indexOf("\t"))+ script.substring(script.indexOf("\t")+1,script.length());
			selectedStartChar--;
			selectedEndChar--;
		}
		
		//インデントを解析
		String newScript = "";
		String[] scrAry = (script+" ").split("\n");
		ArrayList<String> nestAry = new ArrayList<String>();
		int indent = 0;
		int nextIndent = 0;
		int i;
		String[] lastwords = null;
		for(i=0; i<scrAry.length; i++){
			int spacing = 0;
			while(spacing<scrAry[i].length() && (scrAry[i].charAt(spacing)==' ' || scrAry[i].charAt(spacing)=='　')) spacing++;

			ArrayList<String> stringList = new ArrayList<String>();
			ArrayList<TTalk.wordType> typeList  = new ArrayList<TTalk.wordType>();
			checkWordsLine(scrAry[i], stringList, typeList, true, false);
			for(int j=0; j<typeList.size(); j++){
				if(typeList.get(j)==TTalk.wordType.COMMENT){
					typeList.remove(j);
					stringList.remove(j);
					break;
				}
			}
			if(stringList.size()==0) stringList.add("");
			String[] words = new String[stringList.size()];
			stringList.toArray(words);
			
			//String[] words = scrAry[i].substring(spacing).split(" ");
			/*for(int j=0; j<words.length; j++){
				if(words[j].indexOf("--")==0){
					words[j] = "";
					if(j-1 >= 0) words[words.length-1] = words[j-1];
					else words[words.length-1] = "";
					break;
				}
				else if(words[j].indexOf("--")>=0){
					words[j] = words[j].substring(0,words[j].indexOf("--"));
					if(j-1 >= 0) words[words.length-1] = words[j-1];
					else words[words.length-1] = "";
					break;
				}
			}*/
			
			if(words[0].equalsIgnoreCase("on") && words.length >= 2){
				if(indent == 0){
					nextIndent = 1;
					if(words[1].length()>0) nestAry.add("on "+words[1]);
					else if(words.length >= 3 && words[2].length()>0) nestAry.add("on "+words[2]);
				}
				else break;
			}
			if(words[0].equalsIgnoreCase("function") && words.length >= 2){
				if(indent == 0){
					nextIndent = 1;
					if(words[1].length()>0) nestAry.add("on "+words[1]);
					else if(words.length >= 3 && words[2].length()>0) nestAry.add("on "+words[2]);
				}
				else break;
			}
			if(words[0].equalsIgnoreCase("repeat")){
				if(indent >= 1){
					nextIndent = indent+1;
					nestAry.add("repeat");
				}
				else break;
			}
			if(words[0].equalsIgnoreCase("else")){
				if(lastwords[0].equalsIgnoreCase("if")&&!lastwords[lastwords.length-1].equalsIgnoreCase("then")||lastwords[0].equalsIgnoreCase("else")&&lastwords.length>=2&&lastwords[1].equalsIgnoreCase("if")){
					//
				}
				else if(nestAry.get(nestAry.size()-1).equalsIgnoreCase("then")){
					indent -= 1;
					nextIndent -= 1;
					nestAry.remove(nestAry.size()-1);
				}
				//else break;
			}
			if(words[words.length-1].equalsIgnoreCase("then")||words[words.length-1].equalsIgnoreCase("else")){
				if(indent >= 1){
					nextIndent = indent+1;
					nestAry.add("then");
				}
				else break;
			}
			if(words[0].equalsIgnoreCase("end")){
				if(words[1].equalsIgnoreCase("repeat")){
					if(nestAry.get(nestAry.size()-1).equalsIgnoreCase("repeat")){
						indent -= 1;
						nextIndent -= 1;
						nestAry.remove(nestAry.size()-1);
					}
					else break;
				}
				else if(words[1].equalsIgnoreCase("if")){
					if(nestAry.get(nestAry.size()-1).equalsIgnoreCase("then")){
						indent -= 1;
						nextIndent -= 1;
						nestAry.remove(nestAry.size()-1);
					}
					else break;
				}
				else if(words.length>=2 && nestAry.size()>=1 && nestAry.get(nestAry.size()-1).equalsIgnoreCase("on "+words[1])){
					indent -= 1;
					nextIndent -= 1;
					nestAry.remove(nestAry.size()-1);
				}
				else if(words[1].length()==0 && words.length>=3 && nestAry.get(nestAry.size()-1).equalsIgnoreCase("on "+words[2])){
					indent -= 1;
					nextIndent -= 1;
					nestAry.remove(nestAry.size()-1);
				}
				else break;
			}
			
			lastwords = words;
			
			for(int j=0; j<indent; j++) { newScript += "  "; }
			indent = nextIndent;
			
			newScript += scrAry[i].substring(spacing);
			if(i<scrAry.length-1) newScript += "\n";
		}
		
		for(; i<scrAry.length; i++){
			int spacing = 0;
			while(spacing<scrAry[i].length() && (scrAry[i].charAt(spacing)==' ' || scrAry[i].charAt(spacing)=='　')) spacing++;
			
			for(int j=0; j<indent; j++) { newScript += "  "; }
			
			newScript += scrAry[i].substring(spacing);
			if(i<scrAry.length-1) newScript += "\n";
		}
		if(newScript.substring(newScript.length()).equals(" ")){
			newScript = newScript.substring(0,newScript.length()-1);
		}

		{
			String[] newAry = (newScript+" ").split("\n");
			int selStart = 0;
			int j=0;
			for(; j<newAry.length; j++){
				if(selectedStartLine == j) break;
				selStart += newAry[j].length()+1;
			}
			if(j<newAry.length && newAry[j].matches("^[ ]*$")){
				selStart += newAry[j].length();
			}else{
				selStart += selectedStartChar;
			}

			int selEnd = 0;
			for(j=0; j<newAry.length; j++){
				if(selectedEndLine == j) break;
				selEnd += newAry[j].length()+1;
			}
			selEnd += selectedEndChar;
			
			
			//テキストを設定
			getDocument().removeDocumentListener(seListener);
			setText(newScript);
			getDocument().addDocumentListener(seListener);

			setSelectionStart(selStart);
			setSelectionEnd(selEnd);
		}
	}
		
	@SuppressWarnings("unchecked")
	void checkWords(int generation){
		String sourceText = getText();
		ArrayList<ScriptDiagnostic> foundDiagnostics = analyzeScript(sourceText, ScriptLint.namesFor(object));
		String text = sourceText;
		text = text.replace('\r','\n');
		text = text.toLowerCase();
		String[] scriptAry = text.split("\n");
		
		ArrayList<String>[] stringList = new ArrayList[scriptAry.length];
		ArrayList<TTalk.wordType>[] typeList = new ArrayList[scriptAry.length];
		StringBuilder handler = new StringBuilder("");
		TreeSet<String> varSet = new TreeSet<String>();
		
		//行の連結
		for(int i=scriptAry.length-2; i>=0; i--){
			if(scriptAry[i].length()==0) continue;
			char c = scriptAry[i].charAt(scriptAry[i].length()-1);
			if(c=='~' || c=='ﾂ'){
				scriptAry[i] = scriptAry[i].substring(0,scriptAry[i].length()-1) + scriptAry[i+1];
				scriptAry[i+1] = "";
			}
		}
		
		//全ての行を解析
		for(int i=0; i<scriptAry.length; i++){
			stringList[i] = new ArrayList<String>();
			typeList[i]  = new ArrayList<TTalk.wordType>();
			checkWordsLine(scriptAry[i], stringList[i], typeList[i], true, true);
			checkWordsLine2(scriptAry[i], stringList[i], typeList[i], true, handler, varSet);
		}
		

		if(generation != checkWordsGeneration) return;

		ArrayList<ColorSpan> spans = new ArrayList<ColorSpan>();
		//文字に色を付ける位置と色をまとめる
		int start = 0;
		for(int i=0; i<scriptAry.length; i++){
			for(int j=0; j<stringList[i].size(); j++){
				Color color;
				if(typeList[i].get(j)==TTalk.wordType.STRING || typeList[i].get(j)==TTalk.wordType.CONST){
					color = COLOR_STRING;
				}
				else if(typeList[i].get(j)==TTalk.wordType.COMMENT){
					color = COLOR_COMMENT;
				}
				else if(typeList[i].get(j)==TTalk.wordType.XCMD || typeList[i].get(j)==TTalk.wordType.XFCN){
					color = COLOR_XCMD;
				}
				else if(typeList[i].get(j)==TTalk.wordType.CMD || typeList[i].get(j)==TTalk.wordType.CMD_SUB || typeList[i].get(j)==TTalk.wordType.GLOBAL){
					color = COLOR_COMMAND;
				}
				else if(typeList[i].get(j)==TTalk.wordType.FUNC || typeList[i].get(j)==TTalk.wordType.OF_FUNC || typeList[i].get(j)==TTalk.wordType.THE_FUNC){
					color = COLOR_FUNCTION;
				}
				else if(typeList[i].get(j)==TTalk.wordType.VARIABLE){
					color = COLOR_VARIABLE;
				}
				else if(typeList[i].get(j)==TTalk.wordType.OBJECT || typeList[i].get(j)==TTalk.wordType.OF_OBJ){
					color = COLOR_OBJECT;
				}
				else if(typeList[i].get(j)==TTalk.wordType.CHUNK || typeList[i].get(j)==TTalk.wordType.OF_CHUNK){
					color = COLOR_DEFAULT;
				}
				else if(typeList[i].get(j)==TTalk.wordType.X){
					color = COLOR_OTHER;
				}
				else if(typeList[i].get(j)==TTalk.wordType.IF || typeList[i].get(j)==TTalk.wordType.THEN || typeList[i].get(j)==TTalk.wordType.ELSE || typeList[i].get(j)==TTalk.wordType.ENDIF ){
					color = COLOR_IF;
				}
				else if(typeList[i].get(j)==TTalk.wordType.ON_HAND || typeList[i].get(j)==TTalk.wordType.END_HAND || typeList[i].get(j)==TTalk.wordType.ON_FUNC || typeList[i].get(j)==TTalk.wordType.EXIT || typeList[i].get(j)==TTalk.wordType.PASS || typeList[i].get(j)==TTalk.wordType.RETURN ){
					color = COLOR_HANDLER;
				}
				else if(typeList[i].get(j)==TTalk.wordType.REPEAT || typeList[i].get(j)==TTalk.wordType.END_REPEAT || typeList[i].get(j)==TTalk.wordType.EXIT_REP || typeList[i].get(j)==TTalk.wordType.NEXT_REP ){
					color = COLOR_REPEAT;
				}
				else if(typeList[i].get(j)==TTalk.wordType.PROPERTY || typeList[i].get(j)==TTalk.wordType.OF_PROP ){
					color = COLOR_PROPERTY;
				}
				else{
					color = COLOR_DEFAULT;
				}
				if(start<0) start = 0;
				if(start>=text.length()) start = Math.max(0, text.length()-10);
				start = findColorTokenStart(text, stringList[i].get(j), start);
				int len = stringList[i].get(j).length();
				if(start+len>sourceText.length()) len = 0;
				if(len>0) spans.add(new ColorSpan(start, len, color));
				start += len;
			}
		}

		SwingUtilities.invokeLater(() -> applyAnalysis(sourceText, generation, spans, foundDiagnostics));
	}

	/**
	 * スクリプトを実行せずに行う構文解析。動的なメッセージ名やオブジェクトの存在は
	 * HyperTalkでは実行時まで確定しないため、ここでは文字列・括弧・ハンドラ・repeat・
	 * if/then/elseの構造だけを診断し、正しいカスタムコマンドを誤ってエラーにしない。
	 */
	static ArrayList<ScriptDiagnostic> analyzeScript(String source){
		return analyzeScript(source, null);
	}

	/**
	 * @param names スクリプトの外(同じスタック・使用中のスタック・Home)にあるハンドラと XCMD の名前。
	 *        null なら見つからないコマンド・関数の警告は出さない
	 */
	static ArrayList<ScriptDiagnostic> analyzeScript(String source, ScriptLint.Names names){
		ArrayList<ScriptDiagnostic> result = new ArrayList<ScriptDiagnostic>();
		String[] rawLines = (source==null ? "" : source).replace('\r', '\n').split("\n", -1);
		ArrayList<String> scriptLines = new ArrayList<String>(Arrays.asList(rawLines));
		TTalk.joinContinuationLines(scriptLines);
		String[][] words = new String[rawLines.length][];
		Deque<Integer> openParentheses = new ArrayDeque<Integer>();

		for(int line=0; line<rawLines.length; line++){
			String analysisLine = scriptLines.get(line);
			StringBuilder code = new StringBuilder(analysisLine.length());
			boolean inString = false;
			for(int i=0; i<analysisLine.length(); i++){
				char c = analysisLine.charAt(i);
				if(c=='"'){
					if(!inString) code.append(" __string__ ");
					else code.append(' ');
					inString = !inString;
					continue;
				}
				if(!inString && c=='-' && i+1<analysisLine.length() && analysisLine.charAt(i+1)=='-') break;
				if(inString){
					code.append(' ');
					continue;
				}
				if(c=='=') code.append(" = ");
				else code.append(c);
				if(c=='(') openParentheses.push(line);
				else if(c==')'){
					if(openParentheses.isEmpty()) addDiagnostic(result, line,
							"Closing parenthesis has no matching opening parenthesis.");
					else openParentheses.pop();
				}
			}
			if(inString) addDiagnostic(result, line,
					"String literal is not closed.");
			String trimmed = code.toString().strip();
			words[line] = trimmed.isEmpty() ? new String[0] : trimmed.toLowerCase().split("[ \\t　]+");
		}
		while(!openParentheses.isEmpty()) addDiagnostic(result, openParentheses.removeLast(),
				"Opening parenthesis is not closed.");

		int[] ifScopeEnd = new int[rawLines.length];
		Arrays.fill(ifScopeEnd, rawLines.length-1);
		String handlerName = null;
		int handlerStart = -1;
		Deque<Integer> repeats = new ArrayDeque<Integer>();
		for(int line=0; line<words.length; line++){
			String[] w = words[line];
			if(w.length==0) continue;
			boolean handlerOpening = w[0].equals("on") || w[0].equals("function");
			boolean endRepeat = w[0].equals("end") && w.length>1 && w[1].equals("repeat");
			boolean endIf = w[0].equals("end") && w.length>1 && w[1].equals("if");
			boolean handlerClosing = w[0].equals("end") && !endRepeat && !endIf;

			if(handlerOpening){
				if(w.length<2){
					addDiagnostic(result, line, "Handler name is missing.");
					continue;
				}
				if(handlerName!=null){
					addDiagnostic(result, handlerStart, false, "Handler \"{0}\" is not closed with end {0}.", handlerName);
					while(!repeats.isEmpty()) addMissingRepeat(result, repeats.removeLast());
					for(int j=handlerStart; j<line; j++) ifScopeEnd[j]=line-1;
				}
				handlerName = w[1];
				handlerStart = line;
				continue;
			}

			if(w[0].equals("repeat")){
				repeats.push(line);
				if(w.length>1 && (w[1].equals("while") || w[1].equals("until")) && w.length<3){
					addDiagnostic(result, line, false, "repeat {0} requires a condition.", w[1]);
				}
				if(w.length>1 && w[1].equals("with")){
					boolean hasEquals = w.length>3 && w[3].equals("=");
					boolean hasTo = false;
					for(int i=4; i<w.length; i++) if(w[i].equals("to")){ hasTo=true; break; }
					if(!hasEquals || !hasTo) addDiagnostic(result, line,
							"repeat with requires 'variable = start to end'.");
				}
			}
			else if(endRepeat){
				if(repeats.isEmpty()) addDiagnostic(result, line,
						"end repeat has no matching repeat.");
				else repeats.pop();
			}
			else if((w[0].equals("exit") || w[0].equals("next")) && w.length>1 && w[1].equals("repeat") && repeats.isEmpty()){
				addDiagnostic(result, line, false, "{0} repeat is outside a repeat block.", w[0]);
			}

			if(handlerClosing){
				String found = w.length>1 ? w[1] : "";
				if(handlerName==null){
					//HyperCard J-2.3 ignores it like any line outside a handler (Fishing Flash Cards has stray "end mouseUp"
					//lines after its handlers): a warning, not an error
					addDiagnostic(result, line, true, "end has no matching handler.");
				}else{
					if(found.isEmpty() || !found.equals(handlerName)) addDiagnostic(result, line,
							false, "Expected end {0}.", handlerName);
					while(!repeats.isEmpty()) addMissingRepeat(result, repeats.removeLast());
					for(int j=handlerStart; j<line; j++) ifScopeEnd[j]=line-1;
					handlerName = null;
					handlerStart = -1;
				}
			}
		}
		if(handlerName!=null){
			addDiagnostic(result, handlerStart, false, "Handler \"{0}\" is not closed with end {0}.", handlerName);
			for(int j=handlerStart; j<rawLines.length; j++) ifScopeEnd[j]=rawLines.length-1;
		}
		while(!repeats.isEmpty()) addMissingRepeat(result, repeats.removeLast());

		//実行系と同じifパーサーを使うので、1行ifや「thenを次行に置く」構文も同じ判定になる。
		//ifの構造に含まれた行の印(どのifにも属さないelse / end ifを見つける)。壊れたifの範囲も含めて二重に報告しない
		boolean[] inIf = new boolean[words.length];
		for(int line=0; line<words.length; line++){
			if(words[line].length==0 || !words[line][0].equals("if")) continue;
			if(words[line].length>1 && words[line][1].equals("then")){
				addDiagnostic(result, line, "if statement requires a condition.");
			}
			int scopeEnd = Math.max(line, ifScopeEnd[line]);
			try{
				int endLine = TTalk.validateIfStructure(scriptLines, line, scopeEnd);
				for(int j=line; j<=endLine && j<inIf.length; j++) inIf[j] = true;
			}catch(xTalkException e){
				for(int j=line; j<=scopeEnd && j<inIf.length; j++) inIf[j] = true;
				//実行系の if の解析のエラー(「if文にはthenが必要です」「end ifが見つかりません」)を検査の文面にする
				String message = e.getMessage()==null ? "" : e.getMessage();
				addDiagnostic(result, line, message.contains("then") ? "if statement requires then." :
						message.contains("end if") ? "Multi-line if statement is not closed with end if." : "Invalid if structure.");
			}catch(RuntimeException e){
				for(int j=line; j<=scopeEnd && j<inIf.length; j++) inIf[j] = true;
				addDiagnostic(result, line, "Invalid if structure.");
			}
		}
		//どのifにも属さないelse / end if / then(実行すると「elseが分かりません」「endが不正な位置にあります」)
		for(int line=0; line<words.length; line++){
			String[] w = words[line];
			if(w.length==0 || inIf[line]) continue;
			if(w[0].equals("else")) addDiagnostic(result, line, "else has no matching if.");
			else if(w[0].equals("then")) addDiagnostic(result, line, "then has no matching if.");
			else if(w[0].equals("end") && w.length>1 && w[1].equals("if")) addDiagnostic(result, line,
					"end if has no matching if.");
		}

		String last = rawLines.length==0 ? "" : rawLines[rawLines.length-1].stripTrailing();
		if(!last.isEmpty() && TTalk.isContinuationChar(last.charAt(last.length()-1))) addDiagnostic(result, Math.max(0, rawLines.length-1),
				"Continuation marker has no following line.");

		//文ごとの検査(引数・キーワードの欠落、ぶら下がった演算子、見つからないコマンド名…)。構造のエラーがある行は重ねない
		HashSet<Integer> structural = new HashSet<Integer>();
		for(ScriptDiagnostic d : result) structural.add(d.line);
		ScriptLint.check(scriptLines, names, (line, warning, message, args) -> {
			if(!warning && structural.contains(line)) return;
			addDiagnostic(result, line, warning, message, args);
		});
		//同じ行ではエラーを警告より先に
		result.sort((a,b) -> a.line!=b.line ? Integer.compare(a.line,b.line) :
				a.warning!=b.warning ? (a.warning ? 1 : -1) : a.english.compareTo(b.english));
		return result;
	}

	private static void addMissingRepeat(ArrayList<ScriptDiagnostic> result, int line){
		addDiagnostic(result, line, "repeat is not closed with end repeat.");
	}

	private static void addDiagnostic(ArrayList<ScriptDiagnostic> result, int line, String message){
		addDiagnostic(result, line, false, message);
	}

	/** message: the English text with {0}, {1}... for the args (translated when it is shown) */
	private static void addDiagnostic(ArrayList<ScriptDiagnostic> result, int line, boolean warning, String message, Object... args){
		ScriptDiagnostic diagnostic = new ScriptDiagnostic(Math.max(0, line), message, warning, args);
		for(ScriptDiagnostic existing : result){
			if(existing.line==line && existing.english.equals(diagnostic.english)) return;
		}
		result.add(diagnostic);
	}

	static int findColorTokenStart(String text, String token, int start){
		int searchStart = Math.max(0, Math.min(start, text.length()));
		int found = text.indexOf(token, searchStart);
		if(found >= searchStart && found-searchStart <= 64) return found;
		return searchStart;
	}

	private void applyAnalysis(String sourceText, int generation, ArrayList<ColorSpan> spans,
			ArrayList<ScriptDiagnostic> foundDiagnostics){
		if(generation != checkWordsGeneration || !sourceText.equals(getText())) return;
		DefaultStyledDocument document = (DefaultStyledDocument)getDocument();
		Map<Color, MutableAttributeSet> attributes = new HashMap<Color, MutableAttributeSet>();
		document.removeDocumentListener(seListener);
		try{
			for(ColorSpan span : spans){
				MutableAttributeSet attr = attributes.get(span.color);
				if(attr == null){
					attr = new SimpleAttributeSet();
					StyleConstants.setForeground(attr, span.color);
					attributes.put(span.color, attr);
				}
				document.setCharacterAttributes(span.start, span.length, attr, false);
			}
		}finally{
			document.addDocumentListener(seListener);
		}
		setDiagnostics(foundDiagnostics);
	}

	private static class ColorSpan{
		final int start;
		final int length;
		final Color color;

		ColorSpan(int start, int length, Color color){
			this.start = start;
			this.length = length;
			this.color = color;
		}
	}

	
	//"1e" / "2.5E" のように、数値リテラルの指数部の直前まで読み込んだ状態か
	private static boolean isExponentPrefix(CharSequence str){
		int n = str.length();
		if(n<2) return false;
		char e = str.charAt(n-1);
		if(e!='e' && e!='E') return false;
		boolean digit = false;
		boolean dot = false;
		for(int i=0; i<n-1; i++){
			char c = str.charAt(i);
			if(Character.isDigit(c)) digit = true;
			else if(c=='.' && !dot) dot = true;
			else return false;
		}
		return digit;
	}

	//"id (" の直前がこの語なら id はオブジェクト指定（card id (x)）、それ以外の ID(x) は関数呼び出し
	private static final java.util.Set<String> ID_QUALIFIED_WORDS = java.util.Set.of(
		"the", "cd", "card", "bg", "bkgnd", "background", "btn", "button", "fld", "field", "stack",
		"window", "part", "menu", "menuitem", "program", "shape", "panel", "movie", "picture", "pict");

	static void checkWordsLine(String script, ArrayList<String> stringList, ArrayList<TTalk.wordType> typeList, boolean isCmd, boolean isEditor)
	{
		StringBuilder str = new StringBuilder(16);
		boolean inFunc = false;
		int dictionaryDepth = 0;
		ArrayList<TTalk.wordType> Brackets = new ArrayList<TTalk.wordType>();
		
		//単語分割する。演算子、括弧、コメント、文字列の分別。
		for(int i=0; i<script.length(); i++) {
			char code = script.charAt(i);
			//≠ ≤ ≥ は Mac Roman の $AD $B2 $B3。MacJapanese として読んだスタックでは半角カナ ｭ ｲ ｳ に化ける
			//(継続記号 ¬→ﾂ と同じ扱い)ので、引用符の外ではその演算子とみなす
			if(code=='ｭ') code='≠'; else if(code=='ｲ') code='≤'; else if(code=='ｳ') code='≥';
			if(code=='+' || code=='-' || code=='*' || code=='/' || code=='^' || code=='&'
				|| code=='=' || code=='<' || code=='>' || code=='≠' || code=='≤' || code=='≥') {
				if(code=='-' && i>0 && script.codePointAt(i-1)=='-')
				{
					//コメント
					typeList.remove(typeList.size()-1);
					stringList.remove(stringList.size()-1);
					//行の終わりまでコメント
					typeList.add(TTalk.wordType.COMMENT);
					stringList.add(script.substring(i-1));
					break;
				}
				if((code=='-' || code=='+') && isExponentPrefix(str)
						&& i+1<script.length() && Character.isDigit(script.charAt(i+1))){
					//1e-7 / 2.5E+3: 指数の符号は演算子ではなく数値リテラルの一部
					str.append(code);
					continue;
				}
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				typeList.add(TTalk.wordType.OPERATOR);
				stringList.add(String.valueOf((char)code).intern());
			} else if(code=='[' || code==']' || code=='{' || code=='}' || (code==':' && dictionaryDepth>0)) {
				if(str.length()>0){
					typeList.add(Character.isDigit(str.charAt(0)) ? TTalk.wordType.STRING : TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				TTalk.wordType kind = code=='[' ? TTalk.wordType.LCOLLECTION :
					code==']' ? TTalk.wordType.RCOLLECTION :
					code=='{' ? TTalk.wordType.LDICTIONARY :
					code=='}' ? TTalk.wordType.RDICTIONARY : TTalk.wordType.COLON;
				typeList.add(kind);
				stringList.add(String.valueOf(code));
				if(code=='{') dictionaryDepth++;
				else if(code=='}') dictionaryDepth--;
			} else if(code=='(') {
				if(str.length()>0 || typeList.size()>0 && typeList.get(typeList.size()-1) == TTalk.wordType.X) {
					if(str.length()>0){
						if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
						else typeList.add(TTalk.wordType.X);
						stringList.add(str.toString().toLowerCase().intern());
						str.setLength(0);
					}
					String funcstr = stringList.get(stringList.size()-1);
					if(funcstr=="cd" || funcstr=="card" || 
							funcstr=="bg" || funcstr=="bkgnd" ||funcstr=="background" ||
							funcstr=="btn" || funcstr=="button" ||
							funcstr=="fld" || funcstr=="field" ||
							funcstr=="stack"||
							funcstr=="char" || funcstr=="character"||
							funcstr=="item" || funcstr=="word" ||
							funcstr=="line" ||
							funcstr=="window" ||
							funcstr=="menu" || funcstr=="part" || funcstr=="menuitem" ||
							//card id (x) は括弧の式、TwinkleSord の put 100*ID(name) はユーザー関数 ID の呼び出し
							funcstr=="id" && stringList.size()>=2 && ID_QUALIFIED_WORDS.contains(stringList.get(stringList.size()-2)) ||
							funcstr=="or" || funcstr=="and" || funcstr=="not" ||
							funcstr=="until" || funcstr=="while" ||
							funcstr=="to" || funcstr=="from" || funcstr=="by" ||
							funcstr=="at" || funcstr=="with" || funcstr=="for" ||
							funcstr=="into" || funcstr=="after" || funcstr=="before" ||
							funcstr=="div" || funcstr=="mod" || funcstr=="is" || funcstr=="in" || funcstr=="within" || funcstr=="a" || funcstr=="an" ||
							//answer file ("Install " & x) / open file (p) / read from file (p): 関数 file() の呼び出しではなく括弧の式
							funcstr=="file" && stringList.size()>=2 && (stringList.get(stringList.size()-2)=="answer" ||
								stringList.get(stringList.size()-2)=="ask" || stringList.get(stringList.size()-2)=="open" ||
								stringList.get(stringList.size()-2)=="close" || stringList.get(stringList.size()-2)=="from" ||
								stringList.get(stringList.size()-2)=="to") ||
							//there is a file (SysDir() & "x") / there is no folder (p) (Cosmic Osmo)
							(funcstr=="file" || funcstr=="folder" || funcstr=="disk" || funcstr=="document" || funcstr=="application") &&
								stringList.size()>=2 && (stringList.get(stringList.size()-2)=="a" || stringList.get(stringList.size()-2)=="an" ||
								stringList.get(stringList.size()-2)=="no"))
					{
						typeList.add(TTalk.wordType.LBRACKET);
						Brackets.add(TTalk.wordType.LBRACKET);
						inFunc = false;
					}
					else if(funcstr=="of" && stringList.size() >= 2 && (
							/*stringList.get(stringList.size()-2).matches("^[0-9]*$") ||
							stringList.get(stringList.size()-2)=="char" ||
							stringList.get(stringList.size()-2)=="character" ||
							stringList.get(stringList.size()-2)=="item" ||
							stringList.get(stringList.size()-2)=="word" ||
							stringList.get(stringList.size()-2)=="line" ||*/
							!TTalk.funcSet.contains(stringList.get(stringList.size()-2)) ||
							//number of (x & "s"): the objects the value names (1行ショーブだっ！)
							stringList.get(stringList.size()-2)=="number") )
					{
						typeList.add(TTalk.wordType.LBRACKET);
						Brackets.add(TTalk.wordType.LBRACKET);
						inFunc = false;
					}
					else if(isCmd && typeList.size()==1)
					{
						//左側がコマンドとして認識される場合
						typeList.add(TTalk.wordType.LBRACKET);
						Brackets.add(TTalk.wordType.LBRACKET);
						inFunc = false;
					}
					else {
						typeList.add(TTalk.wordType.LFUNC);
						Brackets.add(TTalk.wordType.LFUNC);
						inFunc = true;
					}
					stringList.add("(");
				}
				else {
					typeList.add(TTalk.wordType.LBRACKET);
					Brackets.add(TTalk.wordType.LBRACKET);
					inFunc = false;
					stringList.add("(");
				}
			} else if(code==')') {
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				if(Brackets.size()==0 || Brackets.get(Brackets.size()-1)==TTalk.wordType.LBRACKET){
					typeList.add(TTalk.wordType.RBRACKET);
					if(Brackets.size()>=1) Brackets.remove(Brackets.size()-1);
				}
				else if(Brackets.get(Brackets.size()-1)==TTalk.wordType.LFUNC){
					typeList.add(TTalk.wordType.RFUNC);
					if(Brackets.size()>=1) Brackets.remove(Brackets.size()-1);
				}
				if(Brackets.size()==0 || Brackets.get(Brackets.size()-1)==TTalk.wordType.LBRACKET){
					inFunc = false;
				}
				else{
					inFunc = true;
				}
				stringList.add(")");
			} else if(code==',') {
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				if(inFunc){
					typeList.add(TTalk.wordType.COMMA_FUNC);//関数の引数指定
				}
				else{
					typeList.add(TTalk.wordType.COMMA);//loc/rectあるいはglobal、はたまたハンドラの引数
				}
				stringList.add(",");
			} else if(code=='"') {
				if(str.length()>0) {
					typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				i++;
				while(i<script.length()) {
					code = script.charAt(i);
					if(code=='"') break;
					str.append(code);
					i++;
				}
				if(isEditor){
					typeList.add(TTalk.wordType.QUOTE);
					stringList.add("\"");
				}
				
				typeList.add(TTalk.wordType.STRING);
				stringList.add(TTalk.quotedLiteralToken(str.toString()));
				str.setLength(0);

				if(isEditor){
					typeList.add(TTalk.wordType.QUOTE);
					stringList.add("\"");
				}
			} else if (code==' ' || code=='\t' || code=='\u3000') {
				//全角スペースも単語の区切りとして扱う(日本語入力で誤って入りやすい)
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
			}
			else if(i==script.length()-1) {
				if(str.length()>0 && isPlainNumber(str) && Character.isLetter(code)){
					//数字の直後に空白なしで単語が続いても区切る（例:"1to"→"1"と"to"）
					typeList.add(TTalk.wordType.STRING);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				str.append(code);
				if(str.length()>0) {
					if(Character.isDigit(str.charAt(0))) typeList.add(TTalk.wordType.STRING);
					else typeList.add(TTalk.wordType.X);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
			} else {
				if(str.length()>0 && isPlainNumber(str) && Character.isLetter(code) &&
						!isExponentMarker(script, i)){
					//数字の直後に空白なしで単語が続いても区切る（例:"1to"→"1"と"to"）
					typeList.add(TTalk.wordType.STRING);
					stringList.add(str.toString().toLowerCase().intern());
					str.setLength(0);
				}
				str.append(code);
			}
		}
		functionFormOfKeywords(stringList, typeList, isCmd);
		bareLayerField(stringList, typeList);
	}

	//put x into background field / card field with nothing after "field": HyperCard J-2.3 reads the variable "field" (probes
	//Jhug Bare Field Var / Bare Bg Field: no error, no field changes; JHUG ラデアック・7's openCard on a background without fields)
	private static final java.util.Set<String> LAYER_WORDS = java.util.Set.of("card", "cd", "background", "bkgnd", "bg");
	private static final java.util.Set<String> VALUE_END_WORDS = java.util.Set.of("then", "else", "into", "after", "before", "and",
		"or", "is", "contains", "mod", "div", "&", "&&", "+", "-", "*", "/", "=", "<", ">", "<>", "<=", ">=", "≠", "≤", "≥", "^");
	private static void bareLayerField(ArrayList<String> words, ArrayList<TTalk.wordType> types){
		for(int k=0; k+1<words.size(); k++){
			if(types.get(k)!=TTalk.wordType.X || types.get(k+1)!=TTalk.wordType.X) continue;
			if(!LAYER_WORDS.contains(words.get(k)) || words.get(k+1)!="field") continue;
			if(k>0 && types.get(k-1)==TTalk.wordType.X){
				String p = words.get(k-1);
				if(p=="a" || p=="an" || p=="this" || p=="number" || p=="next" || p=="prev" || p=="last" ||
						p=="first" || p=="any" || p=="mid" || p=="middle" || TTalk.isRelativeChunkSpecifier(p)) continue;
			}
			int n = k+2;
			if(n<words.size()){
				TTalk.wordType t = types.get(n);
				boolean end = t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC || t==TTalk.wordType.COMMA ||
					t==TTalk.wordType.COMMA_FUNC || (t!=TTalk.wordType.STRING && VALUE_END_WORDS.contains(words.get(n)));
				if(!end) continue;
			}
			words.remove(k);
			types.remove(k);
		}
	}

	//HyperCard J-2.3 で確かめた語: 値を置く位置の card(7) / cd (7) / of(7) / to(7) / char(7) などは同名のユーザー関数の呼び出し
	//(function card x があれば呼ばれ、無ければ「card がわかりません」)。オブジェクトやキーワードを置く位置
	//(the short name of cd (2)、there is a cd (2)、go cd (2)、add 1 to (v)、x is in (y))と、括弧の後ろに of/to/in が続く
	//chunk(char (2) of x、line (n) to (m) of x)は今までどおり。btn/fld は値の位置でもオブジェクト(HC もボタンの内容を返す)
	private static final java.util.Set<String> VALUE_FUNCTION_WORDS = java.util.Set.of(
		"a", "an", "at", "by", "for", "from", "in", "of", "to", "until", "while", "with", "within",
		"card", "cd", "bg", "bkgnd", "background", "stack", "window", "menu", "menuitem",
		"char", "character", "item", "line", "word");
	//part(a, b, c) / btn(…, …): with a comma in the brackets the words cannot name an object, so they call the user function of
	//that name (JHUG W.H.A.T. Kakkuro "function part total, N, outer", written before HyperCard 2.2 made "part" a keyword;
	//HC J-2.3 itself no longer compiles it - probe Jhug Part Function). part(7) stays the object, as in HC J-2.3
	private static final java.util.Set<String> OBJECT_FUNCTION_WORDS = java.util.Set.of("part", "btn", "button", "fld", "field");
	private static boolean hasTopLevelComma(ArrayList<TTalk.wordType> types, int from, int close){
		for(int d=0, m=from; m<close; m++){
			TTalk.wordType t = types.get(m);
			if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) d++;
			else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) d--;
			else if(d==0 && (t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC)) return true;
		}
		return false;
	}
	private static final java.util.Set<String> CHUNK_WORDS = java.util.Set.of("char", "character", "item", "word", "line");
	private static final java.util.Set<String> PREPOSITION_WORDS = java.util.Set.of(
		"a", "an", "at", "by", "for", "from", "in", "of", "to", "until", "while", "with", "within");
	//この語の直後は式(値)の位置
	private static final java.util.Set<String> VALUE_POSITION_WORDS = java.util.Set.of(
		"put", "get", "return", "if", "is", "and", "or", "not", "div", "mod", "contains", "until", "while",
		"char", "character", "item", "word", "line"); //chunk の番号: char cd(0) of x

	private static void functionFormOfKeywords(ArrayList<String> words, ArrayList<TTalk.wordType> types, boolean isCmd){
		for(int k=1; k<types.size(); k++){
			if(types.get(k)!=TTalk.wordType.LBRACKET || types.get(k-1)!=TTalk.wordType.X) continue;
			String w = words.get(k-1);
			boolean objectWord = OBJECT_FUNCTION_WORDS.contains(w);
			if(!(VALUE_FUNCTION_WORDS.contains(w) || objectWord) || !valuePosition(words, types, k-2, w, isCmd)) continue;
			int close = -1;
			for(int d=0, m=k; m<types.size(); m++){
				TTalk.wordType t = types.get(m);
				if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) d++;
				else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC){ if(--d==0){ close = m; break; } }
			}
			if(close<0) continue;
			if(objectWord && !hasTopLevelComma(types, k+1, close)) continue;
			//char (2) of x / item (n-1)*2+1 of x / line (1) to (2) of x / card (2) of stack "x" は chunk・オブジェクト:
			//括弧の後ろ、式の区切りまでに of/to/in がある。chunk の番号の中(char cd(0) of x)の of は外側の chunk のもの
			boolean indexOfChunk = k>=2 && types.get(k-2)==TTalk.wordType.X && CHUNK_WORDS.contains(words.get(k-2));
			if(!indexOfChunk && !PREPOSITION_WORDS.contains(w) && chunkOfFollows(words, types, close+1)) continue;
			types.set(k, TTalk.wordType.LFUNC);
			types.set(close, TTalk.wordType.RFUNC);
			for(int d=0, m=k+1; m<close; m++){
				TTalk.wordType t = types.get(m);
				if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC) d++;
				else if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC) d--;
				else if(d==0 && t==TTalk.wordType.COMMA) types.set(m, TTalk.wordType.COMMA_FUNC);
			}
		}
	}

	/** char/character/item/word/line at i with nothing a chunk could take after it (the end, an operator, then/into…): a variable or
	 *  parameter of that name, as in HyperCard J-2.3 (itch.io ElfBalls: on selectCharacter character / if character = "Rogue") */
	private static boolean chunkWordEndsValue(ArrayList<String> words, int i){
		if(i+1>=words.size()) return true;
		String x = words.get(i+1);
		switch(x){
			case "=": case "<": case ">": case "<>": case "≠": case "<=": case ">=": case "≤": case "≥": case "&": case "&&":
			case "+": case "*": case "/": case "^": case ",": case ")":
			case "then": case "else": case "into": case "after": case "before": case "is": case "contains": case "and": case "or":
			case "mod": case "div": return true;
			default: return false;
		}
	}

	//from から式の区切り(深さ0のコンマ、閉じ括弧、then/else/into/after/before)までに深さ0の of/to/in があるか
	private static boolean chunkOfFollows(ArrayList<String> words, ArrayList<TTalk.wordType> types, int from){
		for(int d=0, m=from; m<types.size(); m++){
			TTalk.wordType t = types.get(m);
			if(t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC){ d++; continue; }
			if(t==TTalk.wordType.RBRACKET || t==TTalk.wordType.RFUNC){ if(--d<0) return false; continue; }
			if(d>0 || t==TTalk.wordType.STRING) continue;
			if(t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC) return false;
			String x = words.get(m);
			if(x=="then" || x=="else" || x=="into" || x=="after" || x=="before") return false;
			if(x=="of" || x=="to" || x=="in") return true;
		}
		return false;
	}

	private static boolean valuePosition(ArrayList<String> words, ArrayList<TTalk.wordType> types, int p, String w, boolean isCmd){
		if(p<0) return !isCmd; //式の先頭(文の先頭はコマンド)
		TTalk.wordType t = types.get(p);
		if(t==TTalk.wordType.OPERATOR || t==TTalk.wordType.LBRACKET || t==TTalk.wordType.LFUNC ||
				t==TTalk.wordType.COMMA || t==TTalk.wordType.COMMA_FUNC) return true;
		if(t!=TTalk.wordType.X) return false;
		String prev = words.get(p);
		//x is in (y) / x is not within (r) / x is a (y): 演算子の一部。last item of (x) / char 1 to (n) の of/to は chunk のもの
		//(not alone is the operator: if not within("0,0",fRect) calls the user function - JHUG GraphTrainer)
		if((prev=="is" || (prev=="not" && p>0 && types.get(p-1)==TTalk.wordType.X && words.get(p-1)=="is")) &&
				(w=="in" || w=="within" || w=="a" || w=="an")) return false;
		if(CHUNK_WORDS.contains(prev) && PREPOSITION_WORDS.contains(w)){
			//last item of (x): 序数の後ろの chunk には番号が無い。char of(0) of x の of(0) は番号(ユーザー関数)
			if(p==0 || types.get(p-1)==TTalk.wordType.STRING) return false;
			return !TTalk.isRelativeChunkSpecifier(words.get(p-1)) && words.get(p-1)!="mid";
		}
		return VALUE_POSITION_WORDS.contains(prev);
	}

	//数字に続く e/E が指数表記("1e3"/"1e-7"など)の一部か。直後に数字、または
	//符号と数字が続く場合だけ指数とみなす
	private static boolean isExponentMarker(String script, int i) {
		char c = script.charAt(i);
		if(c!='e' && c!='E') return false;
		if(i+1<script.length() && Character.isDigit(script.charAt(i+1))) return true;
		return i+2<script.length() && (script.charAt(i+1)=='-' || script.charAt(i+1)=='+')
			&& Character.isDigit(script.charAt(i+2));
	}

	//バッファが数(数字と小数点1つまで)だけで構成されているか（数字の直後に単語が続く場合の区切り判定に使う。
	//Dumbit Sound.1 の "put trunc(…)-.2into offset" も ".2" と "into" に分かれる）
	private static boolean isPlainNumber(StringBuilder str) {
		boolean digit = false, dot = false;
		for(int i=0; i<str.length(); i++){
			char c = str.charAt(i);
			if(Character.isDigit(c)) digit = true;
			else if(c=='.' && !dot) dot = true;
			else return false;
		}
		return digit;
	}
	
	
	/** a word that names the kind of object before its name: card field <name>, bg btn <name>, stack <name>… */
	private static boolean isObjectKindWord(String w){
		switch(w.toLowerCase(java.util.Locale.ROOT)){
			case "field": case "fld": case "button": case "btn": case "part": case "card": case "cd": case "bg": case "bkgnd":
			case "background": case "stack": case "window": return true;
			default: return false;
		}
	}

	static void checkWordsLine2(String script, ArrayList<String> stringList, ArrayList<TTalk.wordType> typeList, boolean isCmd, StringBuilder handler, TreeSet<String> varSet)
	{
		String command = "";

		

		//演算子を特定
		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType theType = typeList.get(i);
			
			if((theType==TTalk.wordType.X || theType==TTalk.wordType.OPERATOR) &&
				TTalk.operatorSet.contains(stringList.get(i)/*.toLowerCase()*/)) {
				if(stringList.get(i)=="div") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="mod") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="not") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="and") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="or") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="contains") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i)=="within") typeList.set(i,TTalk.wordType.OPERATOR);
				else if(stringList.get(i).equals("there")) {
					if(i + 1 < typeList.size() && stringList.get(i+1).equals("is")) {
						if(i + 2 < typeList.size() && (stringList.get(i+2).equals("a") ||
								stringList.get(i+2).equals("an") || stringList.get(i+2).equals("no"))) {
							typeList.set(i,TTalk.wordType.OPERATOR);
							typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
							typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
						}
						else if(i + 3 < typeList.size() && stringList.get(i+2).equalsIgnoreCase("not")) {
							if(0==stringList.get(i+3).compareToIgnoreCase("a") || 0==stringList.get(i+3).compareToIgnoreCase("an")) {
								typeList.set(i,TTalk.wordType.OPERATOR);
								typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
								typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
								typeList.set(i+3,TTalk.wordType.OPERATOR_SUB);
							}
							//else throw new xTalkException("there is not requires a/an");
						}
						//else throw new xTalkException("there is requires a/an");
					}
				}
				else if(stringList.get(i).equals("is")) {
					//"a"/"in"/"not"がクォート付き文字列(x is "a")なら演算子の一部ではなく比較対象
					boolean next1IsWord = i + 1 < typeList.size() && typeList.get(i+1)!=TTalk.wordType.STRING;
					boolean next2IsWord = i + 2 < typeList.size() && typeList.get(i+2)!=TTalk.wordType.STRING;
					if(next1IsWord && (stringList.get(i+1).equals("in") || stringList.get(i+1).equals("a") || stringList.get(i+1).equals("an"))) {
						typeList.set(i,TTalk.wordType.OPERATOR);
						typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
						//is a number/date などの型名も評価対象ではない(dateが関数として評価されるのを防ぐ)
						if(!stringList.get(i+1).equals("in") && next2IsWord && TTalk.isTypeNameForIsA(stringList.get(i+2))){
							typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
						}
					}
					else if(next1IsWord && stringList.get(i+1).equals("not") ) {
						if(next2IsWord && (stringList.get(i+2).equalsIgnoreCase("in") || stringList.get(i+2).equalsIgnoreCase("a") || stringList.get(i+2).equalsIgnoreCase("an"))) {
							typeList.set(i,TTalk.wordType.OPERATOR);
							typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
							typeList.set(i+2,TTalk.wordType.OPERATOR_SUB);
							if(!stringList.get(i+2).equalsIgnoreCase("in") && i + 3 < typeList.size() &&
								typeList.get(i+3)!=TTalk.wordType.STRING && TTalk.isTypeNameForIsA(stringList.get(i+3))){
								typeList.set(i+3,TTalk.wordType.OPERATOR_SUB);
							}
						}
						else { //is not
							typeList.set(i,TTalk.wordType.OPERATOR);
							typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
						}
					}
					else typeList.set(i,TTalk.wordType.OPERATOR);
				}
				else if(stringList.get(i)=="<") {
					if(i<typeList.size()-1 && (stringList.get(i+1)=="=" || stringList.get(i+1)==">") ) {
						typeList.set(i,TTalk.wordType.OPERATOR);
						typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
					}
				}
				else if(stringList.get(i)==">") {
					if(i<typeList.size()-1 && stringList.get(i+1)=="=" ) {
						typeList.set(i,TTalk.wordType.OPERATOR);
						typeList.set(i+1,TTalk.wordType.OPERATOR_SUB);
					}
				}
			}
			/*else if(theType==wordType.LFUNC) {
				if(i>=start+1 && (typeAry[i-1]==wordType.OPERATOR ||
						stringList.get(i-1)=="to"))
					typeAry[i]=wordType.LBRACKET;
			}*/
		}
		//An operator word is no function name: x contains (y & comma) groups its right side (Myst Preview's go handler)
		for(int i=1; i<typeList.size(); i++){
			if(typeList.get(i)==TTalk.wordType.LFUNC && stringList.get(i-1)!="within" && //値の位置の within(x) はユーザー関数
					(typeList.get(i-1)==TTalk.wordType.OPERATOR || typeList.get(i-1)==TTalk.wordType.OPERATOR_SUB))
				typeList.set(i, TTalk.wordType.LBRACKET);
		}
		
		//各単語の分別
		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType type = typeList.get(i);
			if(type==TTalk.wordType.X){
				String str = stringList.get(i);
				if(i==0 && isCmd){
					//行の最初の単語
					if(str=="repeat"){
						typeList.set(i, TTalk.wordType.REPEAT);
						command = "repeat";
						continue;
					}
					else if(str=="if"){
						typeList.set(i, TTalk.wordType.IF);
						continue;
					}
					else if(str=="else"){
						typeList.set(i, TTalk.wordType.ELSE);
						continue;
					}
					else if(str=="end"){
						if(typeList.size()>i+1){
							if(stringList.get(i+1)=="if"){
								typeList.set(i, TTalk.wordType.ENDIF);
								i++;
								typeList.set(i, TTalk.wordType.ENDIF);
								continue;
							}
							else if(stringList.get(i+1)=="repeat"){
								typeList.set(i, TTalk.wordType.END_REPEAT);
								i++;
								typeList.set(i, TTalk.wordType.END_REPEAT);
								continue;
							}
							else if(stringList.get(i+1).equalsIgnoreCase(handler.toString())){
								typeList.set(i, TTalk.wordType.END_HAND);
								i++;
								typeList.set(i, TTalk.wordType.END_HAND);
								varSet.clear();
								handler.delete(0, handler.length());
								continue;
							}
						}
					}
					else if(str=="on"){
						if(typeList.size()>i+1){
							typeList.set(i, TTalk.wordType.ON_HAND);
							i++;
							typeList.set(i, TTalk.wordType.ON_HAND);
							handler.append(stringList.get(i));
							continue;
						}
					}
					else if(str=="function"){
						if(typeList.size()>i+1){
							typeList.set(i, TTalk.wordType.ON_FUNC);
							i++;
							typeList.set(i, TTalk.wordType.ON_FUNC);
							handler.append(stringList.get(i));
							continue;
						}
					}
				}
				if((i==0 && isCmd) || i>=1 && (typeList.get(i-1)==TTalk.wordType.THEN || typeList.get(i-1)==TTalk.wordType.ELSE)){
					//コマンドをかけるところの最初の単語
					if(PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.rsrc!=null &&
							PCARD.pc.stack.rsrc.hasXcmd(str, "command")){
						typeList.set(i, TTalk.wordType.XCMD);
					}
					else if(str=="global"){
						typeList.set(i, TTalk.wordType.GLOBAL);
						continue;
					}
					else if(TTalk.commandSet.contains(str)){
						typeList.set(i, TTalk.wordType.CMD);
						command = str;
						continue;
					}
					else if(str=="return"){
						typeList.set(i, TTalk.wordType.RETURN);
						continue;
					}
					else if(str=="pass"){
						if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase(handler.toString())){
							typeList.set(i, TTalk.wordType.PASS);
							i++;
							typeList.set(i, TTalk.wordType.PASS);
							continue;
						}
					}
					else if(str=="exit"){
						if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase(handler.toString())){
							typeList.set(i, TTalk.wordType.EXIT);
							i++;
							typeList.set(i, TTalk.wordType.EXIT);
							continue;
						}
						else if(typeList.size()>i+2 && stringList.get(i+1).equalsIgnoreCase("to") && stringList.get(i+2).equalsIgnoreCase("hypercard")){
							typeList.set(i, TTalk.wordType.EXIT);
							i++;
							typeList.set(i, TTalk.wordType.EXIT);
							i++;
							typeList.set(i, TTalk.wordType.EXIT);
							continue;
						}
						else if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase("repeat")){
							typeList.set(i, TTalk.wordType.EXIT_REP);
							i++;
							typeList.set(i, TTalk.wordType.EXIT_REP);
							continue;
						}
					}
					else if(str=="next"){
						if(typeList.size()>i+1 && stringList.get(i+1).equalsIgnoreCase("repeat")){
							typeList.set(i, TTalk.wordType.NEXT_REP);
							i++;
							typeList.set(i, TTalk.wordType.NEXT_REP);
							continue;
						}
					}
					else if(str=="if"){
						typeList.set(i, TTalk.wordType.IF);
						continue;
					}
					else if(str=="else"){
						typeList.set(i, TTalk.wordType.ELSE);
						continue;
					}
					else if(str=="then"){
						typeList.set(i, TTalk.wordType.THEN);
						continue;
					}
					else{
						typeList.set(i, TTalk.wordType.USER_CMD);
					}
				}
				else{
					//コマンドが書けないところ
					if(str=="else"){
						typeList.set(i, TTalk.wordType.ELSE);
						continue;
					}
					else if(str=="then"){
						typeList.set(i, TTalk.wordType.THEN);
						continue;
					}
					else if(PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.rsrc!=null &&
							//an XFCN is called only as name(...): "the userLevel" stays the property even when the stack
							//has a UserLevel XFCN (Rinaldi's X-Archive), and a variable of that name stays a variable
							i+1<stringList.size() && stringList.get(i+1).equals("(") && (i==0 || !"the".equals(stringList.get(i-1))) &&
							PCARD.pc.stack.rsrc.hasXcmd(str, "function")){
						typeList.set(i, TTalk.wordType.XFCN);
						continue;
					}
					else if(typeList.get(0)==TTalk.wordType.ON_HAND ||
							typeList.get(0)==TTalk.wordType.ON_FUNC)
					{
						//引数
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(str=="it" || (i>0 && stringList.get(i-1)!="the")&&(varSet!=null&&varSet.contains(str))&&
							!propertyOfObject(stringList, i) && !layerWordBeforePart(stringList, i) ||
							//an expression that starts with a variable named like a chunk: "if item is "Quit HyperCard" then" in
							//"on doMenu item" (HyperExtend's プラグインマネージャ)
							i==0 && !isCmd && varSet!=null && varSet.contains(str) &&
							(stringList.size()==1 || TTalk.operatorSet.contains(stringList.get(1)) || stringList.get(1)=="then"))
					{
						typeList.set(i, TTalk.wordType.VARIABLE);
						continue;
					}
					else if(TTalk.constantSet.contains(str)){
						typeList.set(i, TTalk.wordType.CONST);
						continue;
					}
					else if(str=="in" && i>=1 && typeList.get(i-1)==TTalk.wordType.FUNC && stringList.get(i-1)!="number" &&
							//not a function that takes no argument after "the": find the value of the clickline in bkgnd field "x" (AOL MOVIES)
							!(i>=2 && stringList.get(i-2)=="the" && TTalk.NO_ARGUMENT_FUNCTIONS.contains(stringList.get(i-1).toLowerCase(java.util.Locale.ROOT))))
					{
						//value in bkgnd field id 4 / length in x (The Ultimate Warrior): HyperCard J-2.3 takes "in" for "of" here
						stringList.set(i, "of");
						typeList.set(i, TTalk.wordType.OF_FUNC);
						continue;
					}
					else if(str=="of" && i>=1)
					{
						if(typeList.get(i-1)==TTalk.wordType.OBJECT){
							typeList.set(i, TTalk.wordType.OF_OBJ);
							continue;
						}
						else if(typeList.get(i-1)==TTalk.wordType.CHUNK){
							typeList.set(i, TTalk.wordType.OF_CHUNK);
							continue;
						}
						else if(typeList.get(i-1)==TTalk.wordType.FUNC){
							typeList.set(i, TTalk.wordType.OF_FUNC);
							continue;
						}
						else if(typeList.get(i-1)==TTalk.wordType.PROPERTY){
							typeList.set(i, TTalk.wordType.OF_PROP);
							continue;
						}
						if(i>=2){
							if(typeList.get(i-2)==TTalk.wordType.OBJECT){
								typeList.set(i, TTalk.wordType.OF_OBJ);
								continue;
							}
							else if(typeList.get(i-2)==TTalk.wordType.CHUNK){
								typeList.set(i, TTalk.wordType.OF_CHUNK);
								continue;
							}
						}
					}
					else if(typeList.get(0)==TTalk.wordType.GLOBAL)
					{
						//グローバル変数
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(i>=1 && typeList.get(i-1)==TTalk.wordType.REPEAT && (str=="with" || str=="until" || str=="while")){
						typeList.set(i, TTalk.wordType.REPEAT);
						continue;
					}
					else if(i>=1 && typeList.get(i-1)==TTalk.wordType.REPEAT && stringList.get(i-1)=="with"){
						//repeat変数
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(command=="put" && (str=="into" || str=="after" || str=="before")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="add" && str=="to"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="subtract" && str=="from"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="divide" && str=="by"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="multiply" && str=="by"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="show" && str=="at"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="answer" && (str=="with" || str=="or")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="ask" && str=="with"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="set" && str=="to"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="send" && str=="to"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="visual" && str=="effect"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="play" && str=="stop"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="pop" && str=="card"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="push" && str=="card"){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="drag" && (str=="from" || str=="to" || str=="with")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="click" && (str=="at" || str=="with")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if((command=="lock" || command=="unlock") &&
							(str=="screen" || str=="with" || str=="messages" || str=="recent" || str=="errordialogs")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="wait" && (str=="until" || str=="while" || str=="for")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="convert" && (str=="to")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="open" && (str=="file")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="read" && (str=="from" || str=="file" || str=="at" || str=="for" || str=="until")){
						typeList.set(i, TTalk.wordType.CMD_SUB);
						continue;
					}
					else if(command=="repeat" && (str=="to" || str=="down" )){
						typeList.set(i, TTalk.wordType.REPEAT);
						continue;
					}
					else if(str=="cd" || str=="card"
						|| str=="bg" || str=="bkgnd" || str=="background"
						|| str=="btn" || str=="button"
						|| str=="fld" || str=="field"
						|| str=="stack"
						|| str=="window"
						|| str=="menu" || str=="part" || str=="menuitem"
						|| (str=="panel" && !(i+1<stringList.size() && stringList.get(i+1)=="of"))
						|| str=="shape"
						|| str=="menubar" || str=="titlebar" || str=="msg" || str=="message"
						|| str=="hypercard"
						|| str=="me"
						|| (str=="id" && i+1<stringList.size() && stringList.get(i+1)!="of"))
					{
						typeList.set(i, TTalk.wordType.OBJECT);
						continue;
					}
					else if((str=="char" || str=="character"
						|| str=="item" || str=="word"
						|| str=="line") && !chunkWordEndsValue(stringList, i))
					{
						typeList.set(i, TTalk.wordType.CHUNK);
						continue;
					}
					else if(i>=1 && typeList.get(i-1)==TTalk.wordType.CMD_SUB && str!="the" &&
							(command=="put" || command=="add" || command=="subtract" || command=="divide" || command=="multiply") )
					{
						//変数("put x into the selection" の the は変数ではない)
						typeList.set(i, TTalk.wordType.VARIABLE);
						varSet.add(str);
						continue;
					}
					else if(TTalk.propertySet.contains(str) || TTalk.isAdjective(str))
					{
						//HyperZebra 独自のプロパティは the/set の後ろだけ。HyperCard のスクリプトでは変数名・部品名
						//(bg fld DATA of cd X、char DATA of C_DR、words in path of path)
						if((i>=1 && stringList.get(i-1)=="the") ||
								(i>=1 && stringList.get(i-1)=="set") ||
								(i+1<stringList.size() && stringList.get(i+1)=="of" && !TTalk.EXTENSION_PROPERTIES.contains(str)))
						{
							typeList.set(i, TTalk.wordType.PROPERTY);
							continue;
						}
					}
					else if(!TTalk.funcSet.contains(str) && i>=1 && (stringList.get(i-1)=="the" || !TTalk.commandSet.contains(str)) &&
							i+2<stringList.size() && stringList.get(i+1)=="of" && stringList.get(i+2)=="window")
					{
						//外部ウィンドウ(XCMD の windoid)の独自プロパティ: the buttonClick of window "Progress"(UxProgressBar)。
						//HyperCard と同じく the は無くてもよい: get displayMonth of window "Calendario"(wCalendar の説明どおりの書き方)
						typeList.set(i, TTalk.wordType.PROPERTY);
						continue;
					}
					if(TTalk.funcSet.contains(str) && i>0 && (i+1>=stringList.size() || typeList.get(i+1)!=TTalk.wordType.LFUNC) &&
							isObjectKindWord(stringList.get(i-1)) && typeList.get(i-1)!=TTalk.wordType.STRING){
						//card field offset / bg btn length: オブジェクトの種類の後ろの関数名は(括弧が無ければ)名前(Canary)
						continue;
					}
					if(TTalk.funcSet.contains(str)){
						// roundは引数なしでは成立しない。&round や round = 1 のような
						// 位置では、同名の変数として後段で解決できるようにしておく。
						if("round".equals(str) && (i+1>=stringList.size() ||
								typeList.get(i+1)==TTalk.wordType.OPERATOR ||
								typeList.get(i+1)==TTalk.wordType.THEN ||
								typeList.get(i+1)==TTalk.wordType.ELSE)){
							continue;
						}
						if(str=="number" && i+2<stringList.size() && stringList.get(i+1)=="of" &&
								!stringList.get(i+2).matches("s$")){
							//number of xxsは関数だが、そうでない場合はプロパティ
							typeList.set(i, TTalk.wordType.PROPERTY);
							continue;
						}
						else if(str=="number" && i-1>0 && stringList.get(i-1)=="a"){
							typeList.set(i, TTalk.wordType.X);
							continue;
						}
						else {
							typeList.set(i, TTalk.wordType.FUNC);
							continue;
						}
					}
					else if(i+1<stringList.size() && stringList.get(i)!="of" && typeList.get(i+1)==TTalk.wordType.LFUNC){
						typeList.set(i, TTalk.wordType.USER_FUNC);
						continue;
					}
				}
			}
		}

		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType type = typeList.get(i);
			if(type==TTalk.wordType.X){
				String str = stringList.get(i);
				if((str=="first"||str=="last"||str=="prev"||str=="previous"||str=="next") && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.OBJECT){
					typeList.set(i, TTalk.wordType.OBJECT);
				}
				if(TTalk.isRelativeChunkSpecifier(str) && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.CHUNK){
					typeList.set(i, TTalk.wordType.CHUNK);
				}
			}
		}
		
		for(int i=0; i<typeList.size(); i++){
			TTalk.wordType type = typeList.get(i);
			if(type==TTalk.wordType.X){
				String str = stringList.get(i);
				//"the time of the date": a function that takes no argument is called even when "of" follows (Mosaïcon)
				if(str=="the" && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.FUNC && (i+2>=stringList.size() || stringList.get(i+2)!="of" ||
						TTalk.NO_ARGUMENT_FUNCTIONS.contains(stringList.get(i+1).toLowerCase(java.util.Locale.ROOT)))){
					typeList.set(i, TTalk.wordType.THE_FUNC);
				}
				if(str=="the" && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.PROPERTY){
					typeList.set(i, TTalk.wordType.PROPERTY);
				}
				if(str=="this" && i+1<stringList.size() && typeList.get(i+1)==TTalk.wordType.OBJECT){
					typeList.set(i, TTalk.wordType.OBJECT);
				}
			}
		}
	}
	
	
	/**
	 * A property name used as a local variable still reads the property after short/long/abbr: "if short name of this bg
	 * = "Edit"" in a handler with a parameter called name (HTML TagWriter's setPalette). HyperCard 2.4 does that, but takes
	 * a bare "name of this card" there as the variable (Can't understand arguments).
	 */
	/** cd/bg before fld/btn/part is the object word even when a variable has that name (戦闘シーン: put "128" into CD,
	 *  then word 6 of cd fld MD) */
	static boolean layerWordBeforePart(ArrayList<String> words, int i){
		if(i+1>=words.size()) return false;
		switch(words.get(i)){
			case "cd": case "card": case "bg": case "bkgnd": case "background": break;
			default: return false;
		}
		switch(words.get(i+1)){
			case "fld": case "field": case "btn": case "button": case "part":
			case "flds": case "fields": case "btns": case "buttons": case "parts": return true;
			default: return false;
		}
	}

	static boolean propertyOfObject(ArrayList<String> words, int i){
		String str = words.get(i);
		if(!TTalk.propertySet.contains(str) || i<1 || i+2>=words.size() || words.get(i+1)!="of") return false;
		return TTalk.isAdjective(words.get(i-1));
	}

	boolean compareObject(OObject obj){
		return (obj==this.object);
	}
	/*
		if(ObjType.equals(obj.objectType) && ObjId == obj.id){
			if(obj.parent != null){
				if(ParentType.equals(obj.parent.objectType) && ParentId == obj.parent.id){
					return true;
				}
			}else if(obj.objectType.equals("stack")){
				return true;//スタックごとにエディタウィンドウを持つので必ず一致
			}
		}
		return false;
	}*/
	
	//オブジェクトの親が一致するか
	/*boolean compareParent(OObject parent){
		if(ParentType!=null && ParentType.equals("background") && parent != null && parent.objectType.equals("card")){
			OCard cd = (OCard)parent;
			if(ParentId == cd.bg.id){
				return true;
			}
		} else {
			if(ParentType!=null && ParentType.equals(parent.objectType) && ParentId == parent.id){
				return true;
			}
		}
		return false;
	}*/
	
	void setObject(OObject obj){
		this.object = obj;
		if(obj!=null) setRuntimeError(obj.lastErrorLine, obj.lastErrorMessage);
	}
	/*	ObjType = obj.objectType;
		ObjId = obj.id;
		if(obj.parent != null){
			ParentType = obj.parent.objectType;
			ParentId = obj.parent.id;
		}
	}*/
	
	/*OObject getObject(OCard parent){
		if(ObjType.equals("stack")) return parent.stack;
		if(ObjType.equals("background") && ObjId == parent.bgid) return parent.bg;
		if(ObjType.equals("card") && ObjId == parent.id) return parent;
		if(ParentType.equals("bg")){
			OBackground bg = parent.bg;
			if(bg == null){
				bg = PCARD.pc.stack.GetBackgroundbyId(ParentId);
			}
			if(ObjType.equals("field") && ParentId == parent.bgid) return bg.GetFldbyId(ObjId);
			if(ObjType.equals("button") && ParentId == parent.bgid) return bg.GetBtnbyId(ObjId);
		}else{
			if(ObjType.equals("field") && ParentId == parent.id) return parent.GetFldbyId(ObjId);
			if(ObjType.equals("button") && ParentId == parent.id) return parent.GetBtnbyId(ObjId);
		}
		return null;
	}*/

	/*OObject getObjectOtherCard(){
		if(ParentType.equals("card")){
			OCard card = PCARD.pc.stack.GetCardbyId(ParentId);
			return getObject(card);
		} else {
			OCard card = PCARD.pc.stack.GetCardofBackgroundbyId(ParentId);
			return getObject(card);
		}
	}*/
	
	void changedScript(){
		saved = false;
		parent.getRootPane().putClientProperty("windowModified", Boolean.TRUE);
		if(!getText().equals(undobuf.get(undobuf.size()-1))){
			undobuf.add(getText());
			if(undobuf.size()>=2){
				//単語単位でundobufに登録する
				String str1 = undobuf.get(undobuf.size()-2);
				String str2 = undobuf.get(undobuf.size()-1);
				boolean wordflag = editCrossesWordBoundary(str1, str2);
				// Keep the initial state. Subsequent single-character edits in the
				// same word may replace the preceding snapshot, but a selection cut
				// (or paste) is always an independent undo operation.
				if(!wordflag && undobuf.size()>2){
					undobuf.remove(undobuf.size()-2);
				}
			}
			if(undobuf.size()>32){
				undobuf.remove(0);
			}
			redobuf.clear();
			setCanUndo();
		}
	}

	/** 通常編集の共通処理。エラー行以前が変わったら行ずれを避けるため古い診断を消す。 */
	void documentChanged(DocumentEvent e){
		if(errorLine>=0 && e!=null){
			javax.swing.text.Element root = getDocument().getDefaultRootElement();
			int first = root.getElementIndex(Math.min(e.getOffset(), getDocument().getLength()));
			if(first<=errorLine){
				setRuntimeError(-1, null);
				if(object!=null){
					object.lastErrorLine = -1;
					object.lastErrorMessage = null;
				}
			}
		}
		changedScript();
		if(parent!=null) parent.refreshStatus(this);
	}

	static boolean editCrossesWordBoundary(String before, String after){
		if(before==null || after==null) return true;
		int prefix=0;
		int sharedLength=Math.min(before.length(), after.length());
		while(prefix<sharedLength && before.charAt(prefix)==after.charAt(prefix)) prefix++;

		int beforeEnd=before.length();
		int afterEnd=after.length();
		while(beforeEnd>prefix && afterEnd>prefix &&
				before.charAt(beforeEnd-1)==after.charAt(afterEnd-1)){
			beforeEnd--;
			afterEnd--;
		}

		int removedLength=beforeEnd-prefix;
		int insertedLength=afterEnd-prefix;
		if(removedLength>1 || insertedLength>1) return true;
		for(int i=prefix; i<beforeEnd; i++){
			if(Character.isWhitespace(before.charAt(i))) return true;
		}
		for(int i=prefix; i<afterEnd; i++){
			if(Character.isWhitespace(after.charAt(i))) return true;
		}
		return false;
	}
	
	void savedScript(){
		saved = true;
		parent.getRootPane().putClientProperty("windowModified", Boolean.FALSE);
	}

	void setCanUndo(){
		SEMenu.undoMenu.setEnabled(true);
	}
	
	ArrayList<String> undobuf = new ArrayList<String>();
	ArrayList<String> redobuf = new ArrayList<String>();
	
	void undoStatus(){
		//SEMenu.undoMenu.setEnabled(undo.canUndo());
		//SEMenu.redoMenu.setEnabled(undo.canRedo());
		SEMenu.undoMenu.setEnabled(undobuf.size()>1);
		SEMenu.redoMenu.setEnabled(redobuf.size()>0);
	}
	
	void undo(){
		if(undobuf.size()>1){
			String str1 = getText();
			String str2 = undobuf.get(undobuf.size()-2);
			int pos = getCaretPosition()+str2.length()-str1.length();
			redobuf.add(str1);
			getDocument().removeDocumentListener(seListener);
			setText(str2);
			undobuf.remove(undobuf.size()-1);
			getDocument().addDocumentListener(seListener);
			undoStatus();
			scheduleCheckWords();
			if(pos>=0 && pos<getText().length()){
				setCaretPosition(pos);
			}
		}
	}
	
	void redo(){
		if(redobuf.size()>0){
			int pos = getCaretPosition();
			undobuf.add(getText());
			getDocument().removeDocumentListener(seListener);
			setText(redobuf.get(redobuf.size()-1));
			redobuf.remove(redobuf.size()-1);
			getDocument().addDocumentListener(seListener);
			undoStatus();
			scheduleCheckWords();
			if(pos<getText().length()){
				setCaretPosition(pos);
			}
		}
	}
	
	
	class SAFocusListener implements FocusListener {
		public void focusGained(FocusEvent e) {
			if(saved) savedScript(); else changedScript();
			undoStatus();
		}
		public void focusLost(FocusEvent e) {
		}
	}
}


class SEMenu {

	/**
	 * 
	 */
	static JMenuItem undoMenu = null;
	static JMenuItem redoMenu = null;
	
	public SEMenu(ScriptEditor scriptEditor){
		ActionListener listener=null;
		
		listener = new SEMenuListener();
		
    	// メニューバーの設定
		JMenuBar mb=new JMenuBar();
		scriptEditor.setJMenuBar(mb);
		
		JMenu m;
		JMenuItem mi;
		int s=InputEvent.CTRL_DOWN_MASK;
		if(System.getProperty("os.name").toLowerCase().startsWith("mac os x")){
			s = InputEvent.META_DOWN_MASK;
		}
		int s_opt = s | InputEvent.ALT_DOWN_MASK;
		int s_shift = s | InputEvent.SHIFT_DOWN_MASK;

	    // Fileメニュー
	    m=new JMenu(PCARD.pc.intl.getText("File"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Close")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Save")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Print…")));mi.setEnabled(false);

	    // Editメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Edit"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Undo")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);
	    undoMenu = mi;
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Redo")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s_shift));mi.addActionListener(listener);
	    redoMenu = mi;
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Paste")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select All")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Find")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Find Next")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Find Prev")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, s_shift));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Find in Stack…")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, s_shift));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Replace")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Replace Next")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Replace Prev")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, s_shift));mi.addActionListener(listener);
	   

	    // Scriptメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Script"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Edit Card Script")));
	    mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, s_opt));
	    mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Edit Background Script")));
	    mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_2, s_opt));
	    mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Edit Stack Script")));
	    mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_3, s_opt));
	    mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Next Window")));
	    mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, s));
	    mi.addActionListener(listener); m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Comment")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Uncomment")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Set Checkpoint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Clear All Checkpoints")));mi.addActionListener(listener);

	    // Debugメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Debug"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Step")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_7, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Step Into")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_8, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Step Out")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_9, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Trace")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_5, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Run")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_6, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Abort")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Variable Watcher")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Message Watcher")));mi.addActionListener(listener);

	    // Helpメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Help"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("HyperTalk Reference")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, s));mi.addActionListener(listener);
	}
}

//メニュー動作
class SEMenuListener implements ActionListener {
	public void actionPerformed (ActionEvent e) {
		String in_cmd = e.getActionCommand();
		String cmd = PCARD.pc.intl.getEngText(in_cmd);
		JTabbedPane tabpane = ScriptEditor.tabPane;
		JViewport vp = (JViewport)((JScrollPane)tabpane.getSelectedComponent()).getComponent(0);
		if(vp.getComponentCount()==0) return;
		ScriptArea area = (ScriptArea)vp.getComponent(0);
		if(cmd.equals("Cut")){
			area.cut();
		}
		if(cmd.equals("Copy")){
			area.copy();
		}
		if(cmd.equals("Paste")){
			area.paste();
		}
		if(cmd.equals("Select All")){
			area.selectAll();
		}
		if(cmd.equals("Undo")){
			area.undo();
			area.undoStatus();
		}
		if(cmd.equals("Redo")){
			area.redo();
			area.undoStatus();
		}
		if(cmd.equals("Find")){
			new FindDialog(area.parent);
			cmd = FindDialog.clicked;
		}
		if(cmd.equals("Find in Stack…")){
			String sel = area.getSelectedText();
			if(sel!=null && !sel.isEmpty() && sel.indexOf('\n')<0) area.parent.FindStr = sel;
			area.parent.showFindInStack();
			return;
		}
		if(cmd.equals("Find Next")){
			String searchedStr = area.getText().substring(area.getSelectionEnd(), area.getText().length()).toLowerCase();
			if(searchedStr.indexOf(area.parent.FindStr.toLowerCase()) >= 0){
				area.setSelectionStart(area.getSelectionEnd() + searchedStr.indexOf(area.parent.FindStr.toLowerCase()));
				area.setSelectionEnd(area.getSelectionStart() + area.parent.FindStr.length());
			} else {
				java.awt.Toolkit.getDefaultToolkit().beep();
			}
		}
		if(cmd.equals("Find Prev")){
			String searchedStr = area.getText().substring(0, area.getSelectionStart()).toLowerCase();
			if(searchedStr.lastIndexOf(area.parent.FindStr.toLowerCase()) >= 0){
				area.setSelectionStart(searchedStr.lastIndexOf(area.parent.FindStr.toLowerCase()));
				area.setSelectionEnd(area.getSelectionStart() + area.parent.FindStr.length());
			} else {
				java.awt.Toolkit.getDefaultToolkit().beep();
			}
		}
		if(cmd.equals("Replace")){
			new ReplaceDialog(area.parent);
			cmd = ReplaceDialog.clicked;
		}
		if(cmd.equals("Replace Next")){
			int savStart = area.getSelectionStart();
			String searchedStr = area.getText().substring(area.getSelectionStart(), area.getText().length()).toLowerCase();
			if(searchedStr.indexOf(area.parent.FindStr.toLowerCase()) >= 0){
				String text = area.getText();
				text = text.substring(0,area.getSelectionStart() + searchedStr.indexOf(area.parent.FindStr.toLowerCase())) +
				area.parent.ReplaceStr +
				text.substring(area.getSelectionStart() + searchedStr.indexOf(area.parent.FindStr.toLowerCase()) + area.parent.FindStr.length(), text.length());
				area.setText(text);
				
				area.setSelectionStart(savStart + searchedStr.indexOf(area.parent.FindStr.toLowerCase()));
				area.setSelectionEnd(area.getSelectionStart() + area.parent.ReplaceStr.length());
			} else {
				java.awt.Toolkit.getDefaultToolkit().beep();
			}
		}
		if(cmd.equals("Replace Prev")){
			String searchedStr = area.getText().substring(0, area.getSelectionEnd());
			if(searchedStr.lastIndexOf(area.parent.FindStr.toLowerCase()) >= 0){
				String text = area.getText();
				text = text.substring(0,searchedStr.lastIndexOf(area.parent.FindStr.toLowerCase())) +
				area.parent.ReplaceStr +
				text.substring(searchedStr.lastIndexOf(area.parent.FindStr.toLowerCase()) + area.parent.FindStr.length(), text.length());
				area.setText(text);
				
				area.setSelectionStart(searchedStr.lastIndexOf(area.parent.FindStr.toLowerCase()));
				area.setSelectionEnd(area.getSelectionStart() + area.parent.ReplaceStr.length());
			} else {
				java.awt.Toolkit.getDefaultToolkit().beep();
			}
		}
		if(cmd.equals("Replace All")){
			area.setSelectionStart(0);
			while(true){
				int savStart = area.getSelectionStart();
				String searchedStr = area.getText().substring(area.getSelectionStart(), area.getText().length()).toLowerCase();
				if(searchedStr.indexOf(area.parent.FindStr.toLowerCase()) >= 0){
					String text = area.getText();
					text = text.substring(0,area.getSelectionStart() + searchedStr.indexOf(area.parent.FindStr.toLowerCase())) +
					area.parent.ReplaceStr +
					text.substring(area.getSelectionStart() + searchedStr.indexOf(area.parent.FindStr.toLowerCase()) + area.parent.FindStr.length(), text.length());
					area.setText(text);
					
					area.setSelectionStart(savStart + searchedStr.indexOf(area.parent.FindStr.toLowerCase()));
					area.setSelectionEnd(area.getSelectionStart() + area.parent.ReplaceStr.length());
				}
				else break;
			}
		}
		if(cmd.equals("Close")){
			ScriptEditor.closeTab(tabpane.getSelectedComponent());
		}
		if(cmd.equals("Save")){
			ScriptEditor.saveScript(area);
		}

		{
			OObject obj = area.object;
			
			if(cmd.equals("Edit Card Script")){
				OCard cd = PCARD.pc.stack.curCard;
				if(obj.objectType.equals("card")) cd = (OCard)obj;
				else if(obj.parent!=null && obj.parent.objectType.equals("card")) cd = (OCard)obj.parent;
				ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, cd);
			}
			if(cmd.equals("Edit Background Script")){
				OBackground bg = PCARD.pc.stack.curCard.bg;
				if(obj.objectType.equals("background")) bg = (OBackground)obj;
				else if(obj.objectType.equals("card")) bg = ((OCard)obj).bg;
				else if(obj.parent!=null && obj.parent.objectType.equals("background")) bg = (OBackground)obj.parent;
				ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, bg);
			}
			if(cmd.equals("Edit Stack Script")){
				ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, PCARD.pc.stack);
			}
		}
		
		if(cmd.equals("Next Window")){
			PCARD.pc.stack.pcard.toFront();
		}
		if(cmd.equals("Comment")){
			String allScript = area.getText();
			int start = area.getSelectionStart();
			int end = area.getSelectionEnd();
			if(start==0 && end==0) return;
			if(end==0) end = start;

			String newScript = allScript.substring(0, start);
			String[] scrAry = (allScript.substring(start, end)).split("\n");
			for(int i=0; i<scrAry.length; i++){
				int spacing = 0;
				while(spacing<scrAry[i].length() && (scrAry[i].charAt(spacing)==' ' || scrAry[i].charAt(spacing)=='　')) spacing++;
				scrAry[i] = scrAry[i].substring(0,spacing)+"-- "+scrAry[i].substring(spacing);

				newScript += scrAry[i];
				if(i<scrAry.length-1) newScript+="\n";
			}
			newScript += allScript.substring(end);
			area.setText(newScript);
		}
		if(cmd.equals("Uncomment")){
			String allScript = area.getText();
			int start = area.getSelectionStart();
			int end = area.getSelectionEnd();
			if(start==0 && end==0) return;
			if(end==0) end = start;

			String newScript = allScript.substring(0, start);
			String[] scrAry = (allScript.substring(start, end)).split("\n");
			for(int i=0; i<scrAry.length; i++){
				int spacing = 0;
				while(spacing<scrAry[i].length() && (scrAry[i].charAt(spacing)==' ' || scrAry[i].charAt(spacing)=='　')) spacing++;
				int comment = 0;
				while(spacing<scrAry[i].length() && (scrAry[i].charAt(spacing+comment)==' ' || scrAry[i].charAt(spacing+comment)=='-')) comment++;
				scrAry[i] = scrAry[i].substring(0,spacing)+scrAry[i].substring(spacing+comment);

				newScript += scrAry[i];
				if(i<scrAry.length-1) newScript+="\n";
			}
			newScript += allScript.substring(end);
			area.setText(newScript);
		}
		if(cmd.equals("Set Checkpoint")){
			area.toggleCheckpoint(area.caretLine());
		}
		if(cmd.equals("Clear All Checkpoints")){
			area.clearCheckpoints();
		}
		if(cmd.equals("Step")){
			ScriptDebugger.stepOver();
		}
		if(cmd.equals("Step Into")){
			ScriptDebugger.stepInto();
		}
		if(cmd.equals("Step Out")){
			ScriptDebugger.stepOut();
		}
		if(cmd.equals("Trace")){
			ScriptDebugger.trace();
		}
		if(cmd.equals("Go") || cmd.equals("Run")){
			ScriptDebugger.go();
		}
		if(cmd.equals("Abort")){
			ScriptDebugger.abort();
		}
		if(cmd.equals("Variable Watcher")){
			if(VariableWatcher.watcherWindow!=null){
				VariableWatcher.watcherWindow.setVisible(true);
				VariableWatcher.refresh();
			}
		}
		if(cmd.equals("HyperTalk Reference")){
			area.parent.showReference();
			return;
		}
		if(cmd.equals("Message Watcher")){
			if(MessageWatcher.watcherWindow!=null){
				MessageWatcher.watcherWindow.setVisible(true);
				MessageWatcher.watcherWindow.setTable(TTalk.talk.messageRingArray, TTalk.talk.objectRingArray, TTalk.talk.ringCnt);
			}
		}
	}
}

class FindDialog extends JDialog implements ActionListener {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String clicked="";
	JTextField area;
	
	FindDialog(ScriptEditor owner) {
		super(owner,true);
		setTitle(PCARD.pc.intl.getDialogText("Find String"));
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel topPanel = new JPanel();
		//topPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		getContentPane().add("North",topPanel);
		JPanel btmPanel = new JPanel();
		btmPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));
		getContentPane().add("South",btmPanel);

		String text = owner.FindStr;
		area = new JTextField(text);
		area.setPreferredSize(new Dimension(380, 28));
		//area.setSize(380, 20);
		//area.setMargin(new Insets(16,16,16,16));
		//area.setLineWrap(true);
		//area.setBorder(new LineBorder(Color.BLACK));
		topPanel.add(area);
		
		JButton btn1 = new JButton(PCARD.pc.intl.getDialogText("Find Prev"));
		btn1.addActionListener(this);
		btmPanel.add(btn1);
		
		JButton btn2 = new JButton(PCARD.pc.intl.getDialogText("Find Next"));
		btn2.addActionListener(this);
		btmPanel.add(btn2);
		getRootPane().setDefaultButton(btn2);

		setBounds(owner.getX()+owner.getWidth()/2-200,owner.getY()+owner.getHeight()/2-area.getPreferredSize().height/2,400,area.getPreferredSize().height+80);
		setResizable(false);
		setVisible(true);
	}
	
	public void actionPerformed(ActionEvent e) {
		clicked=e.getActionCommand();
		((ScriptEditor)getOwner()).FindStr = area.getText();
		this.dispose();
	}
}

class ReplaceDialog extends JDialog implements ActionListener {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String clicked="";
	JTextField area1;
	JTextField area2;
	
	ReplaceDialog(ScriptEditor owner) {
		super(owner,true);
		setTitle(PCARD.pc.intl.getDialogText("Replace String"));
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new FlowLayout(FlowLayout.CENTER));
		topPanel.setPreferredSize(new Dimension(380, 60));
		getContentPane().add("North",topPanel);
		JPanel btmPanel = new JPanel();
		btmPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));
		getContentPane().add("South",btmPanel);

		String text1 = owner.FindStr;
		area1 = new JTextField(text1);
		area1.setPreferredSize(new Dimension(380, 28));
		//area1.setMargin(new Insets(8,16,8,16));
		//area1.setLineWrap(true);
		//area1.setBorder(new LineBorder(Color.BLACK));
		topPanel.add(area1);
		
		
		String text2 = owner.ReplaceStr;
		area2 = new JTextField(text2);
		area2.setPreferredSize(new Dimension(380, 28));
		//area2.setMargin(new Insets(8,16,8,16));
		//area2.setLineWrap(true);
		//area2.setBorder(new LineBorder(Color.BLACK));
		area2.transferFocus();
		topPanel.add(area2);
		topPanel.add(area1);

		//タブキーによる移動
        Set<KeyStroke> 
        strokes = new HashSet<KeyStroke>(Arrays.asList(KeyStroke.getKeyStroke("pressed TAB")));
        area1.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, strokes);
        strokes = new HashSet<KeyStroke>(Arrays.asList(KeyStroke.getKeyStroke("pressed TAB")));
        area2.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, strokes);
		
		JButton btn3 = new JButton(PCARD.pc.intl.getDialogText("Replace All"));
		btn3.addActionListener(this);
		btmPanel.add(btn3);
		
		JButton btn1 = new JButton(PCARD.pc.intl.getDialogText("Replace Prev"));
		btn1.addActionListener(this);
		btmPanel.add(btn1);
		
		JButton btn2 = new JButton(PCARD.pc.intl.getDialogText("Replace Next"));
		btn2.addActionListener(this);
		btmPanel.add(btn2);
		getRootPane().setDefaultButton(btn2);

		setBounds(owner.getX()+owner.getWidth()/2-200,owner.getY()+owner.getHeight()/2-area1.getPreferredSize().height,400,area1.getPreferredSize().height*2+110);
		setResizable(false);
		setVisible(true);
	}
	
	public void actionPerformed(ActionEvent e) {
		clicked=e.getActionCommand();
		((ScriptEditor)getOwner()).FindStr = area1.getText();
		((ScriptEditor)getOwner()).ReplaceStr = area2.getText();
		this.dispose();
	}
}

/** スタック内のすべてのスクリプトから検索する。モードレスで、結果をダブルクリックするとその箇所をエディタで開く */
class FindInStackDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	final ScriptEditor editor;
	JTextField field;
	JCheckBox caseBox;
	JTable table;
	JLabel status;
	ArrayList<ScriptEditor.StackMatch> matches = new ArrayList<ScriptEditor.StackMatch>();

	FindInStackDialog(ScriptEditor owner){
		super(owner, false);
		editor = owner;
		setTitle(PCARD.pc.intl.getDialogText("Find in Stack"));
		getContentPane().setLayout(new BorderLayout());

		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		field = new JTextField();
		field.addActionListener(this);
		field.setActionCommand("Find");
		top.add(field, BorderLayout.CENTER);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		caseBox = new JCheckBox(PCARD.pc.intl.getDialogText("Case Sensitive"));
		right.add(caseBox);
		JButton find = new JButton(PCARD.pc.intl.getDialogText("Find"));
		find.setActionCommand("Find");
		find.addActionListener(this);
		right.add(find);
		top.add(right, BorderLayout.EAST);
		getContentPane().add(top, BorderLayout.NORTH);
		getRootPane().setDefaultButton(find);

		table = new JTable(new javax.swing.table.AbstractTableModel() {
			private static final long serialVersionUID = 1L;
			final String[] names = {
				PCARD.pc.intl.getDialogText("Object"),
				PCARD.pc.intl.getDialogText("Line No."),
				PCARD.pc.intl.getDialogText("Script")
			};
			public int getRowCount(){ return matches.size(); }
			public int getColumnCount(){ return 3; }
			public String getColumnName(int c){ return names[c]; }
			public Object getValueAt(int r, int c){
				ScriptEditor.StackMatch m = matches.get(r);
				if(c==0) return ScriptEditor.matchObjectName(m.object);
				if(c==1) return Integer.valueOf(m.line+1);
				return m.lineText.trim();
			}
		});
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setAutoCreateRowSorter(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(260);
		table.getColumnModel().getColumn(1).setPreferredWidth(40);
		table.getColumnModel().getColumn(1).setMaxWidth(60);
		table.getColumnModel().getColumn(2).setPreferredWidth(320);
		table.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e){
				if(e.getClickCount()==2) openSelected();
			}
		});
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openMatch");
		table.getActionMap().put("openMatch", new AbstractAction() {
			private static final long serialVersionUID = 1L;
			public void actionPerformed(ActionEvent e){ openSelected(); }
		});
		getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);

		JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
		status = new JLabel(" ");
		bottom.add(status);
		getContentPane().add(bottom, BorderLayout.SOUTH);

		//Cmd-W / Escape で閉じる(隠すだけ)
		int s = System.getProperty("os.name").toLowerCase().startsWith("mac os x") ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_W, s), "closeFind");
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
		getRootPane().getActionMap().put("closeFind", new AbstractAction() {
			private static final long serialVersionUID = 1L;
			public void actionPerformed(ActionEvent e){ setVisible(false); }
		});
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

		setSize(640, 300);
		//エディタの下に並べる(画面に収まらなければエディタの下端に重ねる)
		Rectangle screen = owner.getGraphicsConfiguration().getBounds();
		Insets ins = Toolkit.getDefaultToolkit().getScreenInsets(owner.getGraphicsConfiguration());
		int y = Math.min(owner.getY()+owner.getHeight(), screen.y+screen.height-ins.bottom-getHeight());
		setLocation(owner.getX()+owner.getWidth()/2-getWidth()/2, Math.max(screen.y+ins.top, y));
	}

	void show(String text){
		if(text!=null && !text.isEmpty()) field.setText(text);
		setVisible(true);
		toFront();
		field.requestFocusInWindow();
		field.selectAll();
	}

	public void actionPerformed(ActionEvent e){
		String needle = field.getText();
		editor.FindStr = needle;
		matches = ScriptEditor.findInStack(PCARD.pc.stack, needle, caseBox.isSelected());
		((javax.swing.table.AbstractTableModel)table.getModel()).fireTableDataChanged();
		if(needle.isEmpty()) status.setText(" ");
		else if(matches.isEmpty()) status.setText(PCARD.pc.intl.getDialogText("Not found."));
		else status.setText(PCARD.pc.intl.getDialogText("%1 found.").replace("%1", Integer.toString(matches.size())));
		if(!matches.isEmpty()){
			table.setRowSelectionInterval(0, 0);
		}
	}

	void openSelected(){
		int row = table.getSelectedRow();
		if(row<0 || row>=matches.size()) return;
		ScriptEditor.showMatch(matches.get(row));
	}
}

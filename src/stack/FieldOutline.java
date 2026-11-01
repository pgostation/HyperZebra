import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemColor;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.CaretEvent;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellEditor;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * The outline view of a field whose outlineStyle is true. The field's text stays the data, in one of two forms:
 * - JSON (the text starts with { or [ and parses): objects and arrays open into their members; a member shows "key: value".
 *   The view is read-only; the selectedPath ("weather.days.2.name", array indexes from 1) is the path JSONValue takes.
 * - Indented text: each non-empty line is a node, its leading tabs its level (a line indented more than one step under the
 *   line above is still its child). Selecting a node selects its line (the selectedLine), and while the text is not locked
 *   the outline is typed into like an outliner: typing, Return or F2 (or a click on the selected node) edits a node's label in
 *   place; Return while editing goes on to a new node below (Return on an empty new node ends), Tab and Shift-Tab indent and
 *   outdent the node with its children, Delete removes a node (its children stay); each change rewrites the lines.
 * The view takes the place of MyScrollPane on the card (the field's MyTextArea keeps the text but is not shown); the text
 * changing from a script redraws the outline, keeping the nodes that were open.
 */
final class FieldOutline extends MyScrollPane {
	private static final long serialVersionUID = 1L;

	final JTree tree;
	private final DefaultTreeModel model;
	/** The outline as last read from the text. */
	Data.Tree data = new Data.Tree(new Data.Node("", "", 0, "", null, -1), false);
	/** Set while the view itself changes the selection (not the user). */
	private boolean syncing;
	private boolean reloadPending;
	private boolean loaded;
	private boolean mouseDownSent;
	/** The press that sent nothing is the second click of a double-click (GUI.doublePress): mouseDoubleClick at its release. */
	private boolean secondClick;
	private boolean pointerInside;
	private final JTextField editor = new FieldTable.TypingField();
	/**
	 * The text with a new node's line in it (labelled NEW_NODE) while that node is being typed, else null: the node is only
	 * written into the field's text when it is given a label.
	 */
	private String pendingText;
	private int pendingLine;
	/** Set while the outline writes the field's text itself (it shows the change already), and while it stops an edit itself. */
	private boolean writingText, ownStop;
	private final DocumentListener textListener = new DocumentListener(){
		public void insertUpdate(DocumentEvent e){ reloadLater(); }
		public void removeUpdate(DocumentEvent e){ reloadLater(); }
		public void changedUpdate(DocumentEvent e){ reloadLater(); }
	};

	/** Builds the outline of a field (on the EDT); OField.buildOField puts it on the card as the field's scrollPane. */
	FieldOutline(OField field){
		fldData = field;
		model = new DefaultTreeModel(new DefaultMutableTreeNode()){
			private static final long serialVersionUID = 1L;
			@Override public void valueForPathChanged(TreePath path, Object newValue){ labelEdited(path, newValue); }
		};
		tree = new JTree(model){
			private static final long serialVersionUID = 1L;
			@Override public boolean isPathEditable(TreePath path){
				return labelsEditable() && node(path)!=null && node(path).line>0;
			}
			//typing on a selected node edits its label (instead of jumping to the node that starts with the letter)
			@Override protected void processKeyEvent(KeyEvent e){
				if(e.getID()==KeyEvent.KEY_TYPED && typeIntoNode(e)){ e.consume(); return; }
				super.processKeyEvent(e);
			}
			@Override public void paint(Graphics g){
				if(PCARD.lockedScreen && !PCARD.capturingEffect) return;
				super.paint(g);
			}
		};
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setToggleClickCount(2);
		tree.setInvokesStopCellEditing(true);
		tree.setLargeModel(false);
		Renderer renderer = new Renderer();
		tree.setCellRenderer(renderer);
		editor.setBorder(new LineBorder(Color.BLACK));
		tree.setCellEditor(new DefaultTreeCellEditor(tree, renderer, new javax.swing.DefaultCellEditor(editor)));
		tree.setEditable(true);
		tree.getCellEditor().addCellEditorListener(new CellEditorListener(){
			public void editingStopped(ChangeEvent e){}
			//Escape on a new node: it goes away again
			public void editingCanceled(ChangeEvent e){ if(!ownStop && pendingText!=null) SwingUtilities.invokeLater(() -> dropPending()); }
		});
		outlinerKeys();
		setViewportView(tree);
		setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_AS_NEEDED);
		setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_AS_NEEDED);

		tree.addTreeSelectionListener(this::nodesSelected);
		MouseAdapter messages = new MessageMouse();
		tree.addMouseListener(messages);
		tree.addMouseMotionListener(messages);
		getViewport().addMouseListener(messages);

		field.fld.getDocument().addDocumentListener(textListener);
		//select line N of field … sets the text's selection: show it as the selected node
		field.fld.addCaretListener(this::caretMoved);
		applyAppearance();
		reload();
	}

	private static boolean browsing(){
		return PCARD.editMode==0 && AuthTool.tool==null && (PCARD.pc==null || PCARD.pc.tool==null);
	}

	/** Labels of an indented outline are edited like the field's text: only with the browse tool and while lockText is false. */
	private boolean labelsEditable(){
		return fldData!=null && fldData.textEditable() && !data.json;
	}

	/** The tool, lockText or the user level changed: the outline takes the keyboard (and edits) only while it may. */
	void editableChanged(){
		tree.setFocusable(browsing());
		if(!labelsEditable() && tree.isEditing()) tree.stopEditing();
	}

	/** Line widths and insets: the card magnification rounded to whole pixels (fonts and sizes use the exact one). */
	private int scale(){ return Math.max(1, (int)Math.round(PCARD.scale())); }

	private static Data.Node node(TreePath path){
		if(path==null) return null;
		Object last = path.getLastPathComponent();
		if(!(last instanceof DefaultMutableTreeNode)) return null;
		Object user = ((DefaultMutableTreeNode)last).getUserObject();
		return user instanceof Data.Node ? (Data.Node)user : null;
	}

	/** The font, colours, border and selection mode from the field's properties. */
	void applyAppearance(){
		OField f = fldData;
		int scale = scale();
		Font font = OObject.stackFont(f.textFont, f.textStyle&0x03, f.textSize*PCARD.scale());
		tree.setFont(font);
		tree.setRowHeight(PCARD.toPhysical(Math.max(f.textHeight, f.textSize+2)));
		tree.setForeground(f.color);
		tree.setBackground(f.bgColor);
		getViewport().setBackground(f.bgColor);
		tree.getSelectionModel().setSelectionMode(f.multipleLines
			? TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION : TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.setFocusable(browsing());
		//(0標準) 1透明 2不透明 3長方形 4シャドウ 5スクロール: an outline always has a frame, except when transparent
		if(f.style==1){
			setBorder(new EmptyBorder(0, 0, 0, 0));
			setOpaque(false);
			getViewport().setOpaque(false);
			tree.setOpaque(false);
		}
		else{
			//HyperCardの影: 欠けた角からカードが見えるよう、外側は描かず中身(viewport)だけ不透明にする
			setOpaque(f.style!=4);
			getViewport().setOpaque(true);
			tree.setOpaque(true);
			if(f.style==4) setBorder(new ShadowFieldBorder(f));
			else setBorder(new LineBorder(f.color, scale));
		}
		tree.repaint();
	}

	private void reloadLater(){
		if(reloadPending || writingText) return;
		reloadPending = true;
		SwingUtilities.invokeLater(() -> { reloadPending = false; reload(); });
	}

	/** Reads the outline from the field's text again, keeping the open nodes and the selection (the field's). */
	void reload(){
		if(fldData==null || fldData.fld==null) return;
		pendingText = null;
		if(tree.isEditing()) stopOwnEdit();
		Set<String> open = loaded ? openNodes() : null;
		Point view = getViewport().getViewPosition();
		Data.Tree old = data;
		data = Data.parse(fldData.getText());
		//other text altogether (no node in common, JSON instead of indented text): open it to the outlineLevel like a new one
		if(open!=null && (old.json!=data.json || !Data.sharesNode(old.root, Data.ids(data.root)))) open = null;
		DefaultMutableTreeNode root = treeNode(data.root);
		syncing = true;
		try{
			model.setRoot(root);
			expand(root, open);
		}finally{ syncing = false; }
		loaded = true;
		showSelection();
		if(open!=null) getViewport().setViewPosition(view);
	}

	/** Opens the nodes to the field's outlineLevel again (set the outlineLevel of field …). */
	void expandToLevel(){
		syncing = true;
		try{
			DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
			for(int row=tree.getRowCount()-1; row>=0; row--) tree.collapseRow(row);
			expand(root, null);
		}finally{ syncing = false; }
	}

	private static DefaultMutableTreeNode treeNode(Data.Node node){
		DefaultMutableTreeNode t = new DefaultMutableTreeNode(node);
		for(Data.Node child : node.children) t.add(treeNode(child));
		return t;
	}

	/** Opens the nodes that were open (or, the first time, the levels below the field's outlineLevel), parents first. */
	private void expand(DefaultMutableTreeNode root, Set<String> open){
		int level = Math.max(0, fldData.outlineLevel);
		Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
		while(nodes.hasMoreElements()){
			DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
			if(t==root || t.isLeaf()) continue;
			Data.Node n = (Data.Node)t.getUserObject();
			boolean expand = open!=null ? open.contains(n.id) : n.level<level;
			if(expand) tree.expandPath(new TreePath(t.getPath()));
		}
		tree.expandPath(new TreePath(root));
	}

	private Set<String> openNodes(){
		Set<String> open = new HashSet<String>();
		DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
		Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
		while(nodes.hasMoreElements()){
			DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
			if(t==root || t.isLeaf() || !(t.getUserObject() instanceof Data.Node)) continue;
			if(tree.isExpanded(new TreePath(t.getPath()))) open.add(((Data.Node)t.getUserObject()).id);
		}
		return open;
	}

	/** Shows the field's selection: the selected lines (indented text) or the selectedPath (JSON). */
	void showSelection(){
		OField f = fldData;
		if(f==null) return;
		syncing = true;
		try{
			tree.clearSelection();
			DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
			Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
			TreePath first = null;
			while(nodes.hasMoreElements()){
				DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
				if(t==root || !(t.getUserObject() instanceof Data.Node)) continue;
				Data.Node n = (Data.Node)t.getUserObject();
				boolean selected = data.json ? !f.selectedPath.isEmpty() && n.path.equals(f.selectedPath)
					: n.line>0 && Arrays.binarySearch(f.selectedRows, n.line)>=0;
				if(!selected) continue;
				TreePath path = new TreePath(t.getPath());
				tree.addSelectionPath(path);//also opens its parents
				if(first==null) first = path;
			}
			if(first!=null) tree.scrollPathToVisible(first);
		}finally{ syncing = false; }
		tree.repaint();
	}

	/** The user selected nodes (click, arrow keys): kept as the field's selection. */
	private void nodesSelected(TreeSelectionEvent e){
		if(syncing) return;
		TreePath[] paths = tree.getSelectionPaths();
		ArrayList<Integer> lines = new ArrayList<Integer>();
		String path = "";
		if(paths!=null) for(TreePath p : paths){
			Data.Node n = node(p);
			if(n==null) continue;
			if(n.line>0 && !lines.contains(n.line)) lines.add(n.line);
			if(p==tree.getLeadSelectionPath() || path.isEmpty()) path = n.path;
		}
		int[] rows = new int[lines.size()];
		for(int i=0; i<rows.length; i++) rows[i] = lines.get(i);
		Arrays.sort(rows);
		fldData.selectedRows = rows;
		fldData.selectedLine = rows.length>0 ? rows[0] : 0;
		fldData.selectedPath = path;
	}

	/** "select line 3 of field …" selects the text of that line: select its node too (indented text). */
	private void caretMoved(CaretEvent e){
		if(syncing || fldData==null || fldData.fld==null || data.json) return;
		int start = Math.min(e.getDot(), e.getMark()), end = Math.max(e.getDot(), e.getMark());
		if(end<=start) return;
		String text = fldData.getText();
		int first = 1, last = 1;
		for(int i=0; i<text.length() && i<end; i++){
			if(text.charAt(i)=='\n'){ if(i<start) first++; if(i<end-1) last++; }
		}
		int[] lines = new int[last-first+1];
		for(int i=0; i<lines.length; i++) lines[i] = first+i;
		fldData.selectedRows = lines;
		fldData.selectedLine = first;
		showSelection();
	}

	/** A label edited in place (the edit ended by a click elsewhere): that line of the text gets the new label after its tabs. */
	private void labelEdited(TreePath path, Object value){
		Data.Node n = node(path);
		if(n==null || n.line<1 || data.json) return;
		String label = value==null ? "" : value.toString();
		//the outline is rebuilt after the editor has gone (not while the tree is still finishing the edit)
		if(isPending(n)){
			if(label.trim().isEmpty()){ SwingUtilities.invokeLater(this::dropPending); return; }
			String text = Data.setLabel(pendingText, n.line, label);
			SwingUtilities.invokeLater(() -> change(text, false, l -> l, n.line));
			return;
		}
		if(label.equals(n.label)) return;
		String text = Data.setLabel(fldData.getText(), n.line, label);
		writeText(text);
		SwingUtilities.invokeLater(() -> change(text, false, l -> l, n.line));
	}

	private boolean isPending(Data.Node n){
		return n!=null && pendingText!=null && n.line==pendingLine;
	}

	/** The lines the outline shows: the field's text, with the new node being typed. */
	private String shownText(){
		return pendingText!=null ? pendingText : fldData.getText();
	}

	/** Writes the field's text from the outline (which shows it already) and sends closeField like a typed field. */
	private void writeText(String text){
		if(text.equals(fldData.getText())) return;
		writingText = true;
		try{ fldData.setText(text); }finally{ writingText = false; }
		TTalk.CallMessage("closeField", fldData);
	}

	/** Ends an edit without writing what the editor holds (the caller writes it itself). */
	private void stopOwnEdit(){
		ownStop = true;
		try{ tree.cancelEditing(); }finally{ ownStop = false; }
	}

	/**
	 * Shows new lines: written into the field's text, or (pending) kept as the text with a new node being typed. The nodes that
	 * were open stay open where map moves their lines (0: gone); the node of line select is selected.
	 */
	private void change(String text, boolean pending, IntUnaryOperator map, int select){
		Set<Integer> open = new HashSet<Integer>();
		for(int line : openLines()){ int to = map.applyAsInt(line); if(to>0) open.add(to); }
		if(pending) pendingText = text;
		else{ pendingText = null; writeText(text); }
		fldData.selectedRows = select>0 ? new int[]{select} : new int[0];
		fldData.selectedLine = Math.max(0, select);
		fldData.selectedPath = "";
		Point view = getViewport().getViewPosition();
		data = Data.parse(text);
		DefaultMutableTreeNode root = treeNode(data.root);
		syncing = true;
		try{
			model.setRoot(root);
			Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
			while(nodes.hasMoreElements()){
				DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
				if(t!=root && !t.isLeaf() && open.contains(((Data.Node)t.getUserObject()).line)) tree.expandPath(new TreePath(t.getPath()));
			}
			tree.expandPath(new TreePath(root));
		}finally{ syncing = false; }
		getViewport().setViewPosition(view);
		showSelection();
	}

	/** The lines of the nodes that are open now. */
	private Set<Integer> openLines(){
		Set<Integer> open = new HashSet<Integer>();
		DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
		Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
		while(nodes.hasMoreElements()){
			DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
			if(t==root || t.isLeaf() || !(t.getUserObject() instanceof Data.Node)) continue;
			if(tree.isExpanded(new TreePath(t.getPath()))) open.add(((Data.Node)t.getUserObject()).line);
		}
		return open;
	}

	private TreePath pathOfLine(int line){
		DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
		Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
		while(nodes.hasMoreElements()){
			DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
			if(t!=root && t.getUserObject() instanceof Data.Node && ((Data.Node)t.getUserObject()).line==line) return new TreePath(t.getPath());
		}
		return null;
	}

	/** Starts editing the label of a line's node; typed (if any) takes the place of what it says (Escape gives it back). */
	private void editLine(int line, String typed){
		TreePath path = pathOfLine(line);
		if(path==null) return;
		tree.setSelectionPath(path);
		tree.scrollPathToVisible(path);
		tree.startEditingAtPath(path);
		if(!tree.isEditing()) return;
		editor.requestFocusInWindow();
		if(typed!=null) editor.setText(typed);
		editor.setCaretPosition(editor.getText().length());
	}

	/** The selected node, or null. */
	private Data.Node selectedNode(){
		return node(tree.getLeadSelectionPath()!=null ? tree.getLeadSelectionPath() : tree.getSelectionPath());
	}

	/** A new empty node to type: the first child of an open node, else the next one after the node and its children (none: last). */
	void newNodeAfter(Data.Node n, String typed){
		String text = fldData.getText();
		int at, depth;
		if(n==null){
			at = text.isEmpty() ? 1 : FieldTable.Data.lines(text).length+1;
			depth = 0;
		}
		else if(!n.children.isEmpty() && tree.isExpanded(pathOfLine(n.line))){
			at = n.line+1;
			depth = Data.depth(text, n.children.get(0).line);
		}
		else{
			at = Data.lastLine(n)+1;
			depth = Data.depth(text, n.line);
		}
		final int line = at;
		change(Data.insertLine(text, line, depth, Data.NEW_NODE), true, l -> l>=line ? l+1 : l, line);
		pendingLine = line;
		editLine(line, typed);
	}

	/** Removes the empty new node again (Escape, Return on it, or a click elsewhere). */
	private void dropPending(){
		if(pendingText==null) return;
		int line = pendingLine;
		Data.Node above = null;
		for(int l=line-1; l>=1 && above==null; l--) above = data.atLine(l);
		change(fldData.getText(), false, l -> l==line ? 0 : l>line ? l-1 : l, above!=null ? above.line : 0);
	}

	/** Return while editing: keeps the label, then a new node below (Return on an empty new node only ends the edit). */
	private void editorReturn(){
		Data.Node n = node(tree.getEditingPath());
		String label = editor.getText();
		stopOwnEdit();
		if(n==null) return;
		if(isPending(n) && label.trim().isEmpty()){ dropPending(); return; }
		change(Data.setLabel(shownText(), n.line, label), false, l -> l, n.line);
		Data.Node now = data.atLine(n.line);
		if(now!=null) newNodeAfter(now, null);
	}

	/** Tab or Shift-Tab while editing: keeps the label (an empty new node stays new) and goes on editing the moved node. */
	private void editorIndent(boolean in){
		Data.Node n = node(tree.getEditingPath());
		String label = editor.getText();
		int caret = editor.getCaretPosition();
		stopOwnEdit();
		if(n==null) return;
		boolean pending = isPending(n) && label.trim().isEmpty();
		if(!pending) change(Data.setLabel(shownText(), n.line, label), false, l -> l, n.line);
		Data.Node now = data.atLine(n.line);
		if(now!=null) indent(now, in, pending);
		editLine(n.line, null);
		if(tree.isEditing()) editor.setCaretPosition(Math.min(caret, editor.getText().length()));
	}

	/**
	 * Tab: the node (with its children) goes under the node above it at the same level. Shift-Tab: it goes up a level, after its
	 * parent (the nodes after it under that parent become its children, as the lines say).
	 */
	private void indent(Data.Node n, boolean in, boolean pending){
		String text = shownText();
		Data.Node parent = parentOf(data.root, n);
		if(parent==null) return;
		int depth = Data.depth(text, n.line), to;
		if(in){
			int index = parent.children.indexOf(n);
			if(index<1){ Toolkit.getDefaultToolkit().beep(); return; }
			Data.Node above = parent.children.get(index-1);
			to = Data.depth(text, above.line)+1;
		}
		else{
			if(parent==data.root){ Toolkit.getDefaultToolkit().beep(); return; }
			to = Data.depth(text, parent.line);
		}
		int delta = to-depth;
		String moved = Data.shift(text, n.line, Data.lastLine(n), delta);
		Set<Integer> opened = new HashSet<Integer>();
		if(in) opened.add(parent.children.get(parent.children.indexOf(n)-1).line); //shows the node under its new parent
		change(moved, pending, l -> l, n.line);
		for(int line : opened){ TreePath p = pathOfLine(line); if(p!=null) tree.expandPath(p); }
		showSelection();
	}

	private static Data.Node parentOf(Data.Node node, Data.Node child){
		for(Data.Node c : node.children){
			if(c==child) return node;
			Data.Node p = parentOf(c, child);
			if(p!=null) return p;
		}
		return null;
	}

	/** Delete: removes the selected node's line (its children go under the node above, as the lines say). */
	private void deleteNode(){
		Data.Node n = selectedNode();
		if(n==null || n.line<1){ Toolkit.getDefaultToolkit().beep(); return; }
		int line = n.line;
		int row = tree.getRowForPath(pathOfLine(line));
		Data.Node next = row>=0 && row+1<tree.getRowCount() ? node(tree.getPathForRow(row+1)) : null;
		Data.Node prev = row>0 ? node(tree.getPathForRow(row-1)) : null;
		int select = next!=null ? next.line-1 : prev!=null ? prev.line : 0;
		change(Data.deleteLine(fldData.getText(), line), false, l -> l==line ? 0 : l>line ? l-1 : l, select);
	}

	/** A character typed on the outline (not in the editor): replaces the selected node's label, or starts a new last node. */
	boolean typeIntoNode(KeyEvent e){
		char c = e.getKeyChar();
		if(!labelsEditable() || c<0x20 || c==0x7f || c==KeyEvent.CHAR_UNDEFINED || e.isMetaDown() || e.isControlDown()) return false;
		//typed before the editor has the keyboard: goes into it too
		if(tree.isEditing()){ editor.replaceSelection(String.valueOf(c)); return true; }
		Data.Node n = selectedNode();
		if(n!=null && n.line>0) editLine(n.line, String.valueOf(c));
		else newNodeAfter(null, String.valueOf(c));
		return true;
	}

	/** Return, Tab, Shift-Tab and Delete on the outline and in its label editor. */
	private void outlinerKeys(){
		tree.setFocusTraversalKeysEnabled(false);
		editor.setFocusTraversalKeysEnabled(false);
		bind(tree, "ENTER", "hzEditNode", () -> {
			Data.Node n = selectedNode();
			if(n!=null && n.line>0) editLine(n.line, null);
			else newNodeAfter(null, null);
		});
		bind(tree, "TAB", "hzIndent", () -> { Data.Node n = selectedNode(); if(n!=null && n.line>0) indent(n, true, false); });
		bind(tree, "shift TAB", "hzOutdent", () -> { Data.Node n = selectedNode(); if(n!=null && n.line>0) indent(n, false, false); });
		bind(tree, "BACK_SPACE", "hzDeleteNode", this::deleteNode);
		bind(tree, "DELETE", "hzDeleteNode", this::deleteNode);
		bind(editor, "ENTER", "hzNextNode", this::editorReturn);
		bind(editor, "TAB", "hzIndent", () -> editorIndent(true));
		bind(editor, "shift TAB", "hzOutdent", () -> editorIndent(false));
	}

	/** A key of the outline that works only while it can be typed into (else the key does what it did). */
	private void bind(JComponent c, String key, String name, Runnable action){
		c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
		c.getActionMap().put(name, new AbstractAction(){
			private static final long serialVersionUID = 1L;
			@Override public void actionPerformed(ActionEvent e){ if(labelsEditable()) action.run(); }
			@Override public boolean isEnabled(){ return labelsEditable(); }
		});
	}

	/** Stops editing when the card goes away, keeping what was typed. */
	@Override
	public void removeNotify(){
		if(tree.isEditing()) tree.stopEditing();
		super.removeNotify();
	}

	/** Detaches from the field's text (the field is being rebuilt or its card closed). */
	void dispose(){
		if(fldData!=null && fldData.fld!=null) fldData.fld.getDocument().removeDocumentListener(textListener);
	}

	/** Plain labels in the field's font and colours, no folder or document icons. */
	private final class Renderer extends DefaultTreeCellRenderer {
		private static final long serialVersionUID = 1L;
		Renderer(){
			setLeafIcon(null);
			setOpenIcon(null);
			setClosedIcon(null);
		}
		@Override
		public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus){
			super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, false);
			setText(StackTranslation.cell(fldData, getText())); //the stack's own translation (StackLanguages); a label edited is its own
			setFont(t.getFont());
			setTextNonSelectionColor(fldData.color);
			setBackgroundNonSelectionColor(new Color(0, 0, 0, 0));
			setTextSelectionColor(SystemColor.textHighlightText);
			setBackgroundSelectionColor(SystemColor.textHighlight);
			setBorderSelectionColor(null);
			setForeground(selected ? SystemColor.textHighlightText : fldData.color);
			setBorder(new EmptyBorder(0, scale(), 0, 3*scale()));
			return this;
		}
	}

	/**
	 * The browse tool's messages for the field, as for a list field: mouseDown/mouseStillDown/mouseUp/mouseDoubleClick,
	 * mouseEnter/mouseWithin/mouseLeave; the clickLine is the clicked node's line and the clickText its label (JSON: its value).
	 */
	private final class MessageMouse extends MouseAdapter {
		private Point toPane(MouseEvent e){
			return SwingUtilities.convertPoint((Component)e.getSource(), e.getPoint(), FieldOutline.this);
		}
		@Override public void mousePressed(MouseEvent e){
			mouseDownSent = false;
			GUI.mouseDowned();
			OField f = fldData;
			if(f==null) return;
			if(PCARD.editMode!=0){
				//Command-Option-Shift shows the field outlines: a click opens the script
				//(key[]に Command-Option が残っていただけの普通のクリックならピークを終える)
				if(!GUI.isPeekClick(e)) GUI.endPeek();
				else if(GUI.stroke_frag==2 && PCARD.pc!=null && PCARD.pc.stack!=null){
					ScriptEditor.openScriptEditor(PCARD.pc.stack.pcard, f);
					GUI.endPeek();
				}
				return;
			}
			if(!browsing()) return;
			Point p = toPane(e);
			GUI.clickH = f.left + PCARD.toLogical(p.x);
			GUI.clickV = f.top + PCARD.toLogical(p.y);
			GUI.noteFieldClick(f, p.x, p.y);
			GUI.clickField = f;
			f.clickNode = null;
			if(e.getSource()==tree){
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				Data.Node n = node(path);
				f.clickNode = n;
				f.clickRow = n!=null ? n.line : 0;
				if(n!=null && n.line>0) GUI.clickLine = "line "+n.line+" of "+f.getShortName();
			}
			//locked only, as other fields: an unlocked one's click is editing and sends no mouse messages (HyperCard)
			if(!TTalk.idle || !f.enabled) return;
			mouseDownSent = true;
			secondClick = GUI.doublePress(e, f); //as other fields: no mouseDown, mouseDoubleClick at the release
			if(secondClick) return;
			TTalk.CallMessage("mouseDown", f);
			GUI.startStillDown(f); //mouseStillDown while held
		}
		@Override public void mouseReleased(MouseEvent e){
			GUI.mouseUped();
			boolean sent = mouseDownSent;
			mouseDownSent = false;
			if(!browsing() || fldData==null) return;
			boolean inside = new Rectangle(0, 0, getWidth(), getHeight()).contains(toPane(e));
			if(sent && inside && TTalk.idle){
				TTalk.CallMessage(secondClick ? "mouseDoubleClick" : "mouseUp", fldData);
				GUI.mouseDown = false; //as for buttons: the click that sends mouseUp is not the mouseClick of its handler
			}
			//a quick click whose mouseDown handler still runs: its mouseUp follows that handler (as for buttons, HyperCard)
			else if(sent && inside && !secondClick){
				TTalk.callPendingMouseUp(fldData);
				GUI.mouseDown = false;
			}
			else GUI.mouseClicked();
		}
		@Override public void mouseDragged(MouseEvent e){
			//mouseStillDown comes from GUI.startStillDown (locked fields only)
		}
		@Override public void mouseMoved(MouseEvent e){
			if(TTalk.idle && browsing() && fldData!=null) TTalk.CallMessage("mouseWithin", fldData);
		}
		@Override public void mouseEntered(MouseEvent e){
			if(pointerInside || fldData==null) return;
			pointerInside = true;
			if(TTalk.idle && browsing()) TTalk.CallMessage("mouseEnter", fldData);
		}
		@Override public void mouseExited(MouseEvent e){
			if(new Rectangle(0, 0, getWidth(), getHeight()).contains(toPane(e)) || fldData==null) return;
			pointerInside = false;
			if(TTalk.idle && browsing()) TTalk.CallMessage("mouseLeave", fldData);
		}
	}

	/** The field's text as an outline (no Swing: also used by the HyperTalk properties and the tests). */
	static final class Data {
		private Data(){}

		static final class Node {
			/** What the outline shows. */
			final String label;
			/** Stays the same while the text changes around the node (the open nodes are found again by it). */
			final String id;
			/** The node's line in the text (1-based); 0 for JSON. */
			final int line;
			/** The JSON path ("weather.days.2"); empty for indented text. */
			final String path;
			/** The JSON value (Map, List, a HyperTalk text or null); null for indented text. */
			final Object value;
			/** 0 for the top level. */
			final int level;
			final List<Node> children = new ArrayList<Node>();
			Node(String label, String id, int line, String path, Object value, int level){
				this.label = label; this.id = id; this.line = line; this.path = path; this.value = value; this.level = level;
			}
			@Override public String toString(){ return label; }
			/** What the selectedText and the clickText give: the label, or for JSON the value (objects and arrays as collections). */
			String text() throws xTalkException {
				if(line>0) return label;
				if(value==null) return "";
				return TalkJson.result(value);
			}
		}

		static final class Tree {
			final Node root;
			final boolean json;
			Tree(Node root, boolean json){ this.root = root; this.json = json; }
			/** The node at a JSON path or null. */
			Node find(String path){ return find(root, path); }
			private static Node find(Node node, String path){
				for(Node child : node.children){
					if(child.path.equals(path)) return child;
					if(path.startsWith(child.path+".")){ Node n = find(child, path); if(n!=null) return n; }
				}
				return null;
			}
			/** The node of a line (indented text) or null. */
			Node atLine(int line){ return atLine(root, line); }
			private static Node atLine(Node node, int line){
				for(Node child : node.children){
					if(child.line==line) return child;
					Node n = atLine(child, line);
					if(n!=null) return n;
				}
				return null;
			}
		}

		/** JSON when the text is a JSON object or array (or a HyperTalk collection), else indented text. */
		static Tree parse(String text){
			if(text==null) text = "";
			if(TalkCollection.isCollection(text)){
				try{ return json(fromCollection(text)); }catch(xTalkException e){ /* show it as text */ }
			}
			String t = text.trim();
			if(t.startsWith("{") || t.startsWith("[")){
				try{
					Object root = TalkJson.parse(t);
					if(root instanceof Map<?,?> || root instanceof List<?>) return json(root);
				}catch(xTalkException e){ /* not JSON: an indented outline that starts with a bracket */ }
			}
			return indented(text);
		}

		private static Object fromCollection(String value) throws xTalkException {
			if(!TalkCollection.isCollection(value)) return value;
			TalkCollection c = TalkCollection.decode(value);
			if(c.isArray()){
				ArrayList<Object> list = new ArrayList<Object>();
				for(String item : c.items) list.add(fromCollection(item));
				return list;
			}
			LinkedHashMap<String,Object> map = new LinkedHashMap<String,Object>();
			for(Map.Entry<String,String> e : c.entries.entrySet()) map.put(e.getKey(), fromCollection(e.getValue()));
			return map;
		}

		private static Tree json(Object root){
			Node top = new Node("", "", 0, "", root, -1);
			members(top, root);
			return new Tree(top, true);
		}

		private static void members(Node parent, Object value){
			if(value instanceof Map<?,?>){
				for(Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) member(parent, String.valueOf(e.getKey()), e.getValue());
			}
			else if(value instanceof List<?>){
				List<?> list = (List<?>)value;
				for(int i=0; i<list.size(); i++) member(parent, Integer.toString(i+1), list.get(i));
			}
		}

		private static void member(Node parent, String key, Object value){
			String path = parent.path.isEmpty() ? key : parent.path+"."+key;
			Node n = new Node(key+describe(value), path, 0, path, value, parent.level+1);
			parent.children.add(n);
			members(n, value);
		}

		/** ": value" for a scalar, " {3}" for an object of three members, " [2]" for an array of two. */
		private static String describe(Object value){
			if(value instanceof Map<?,?>) return " {"+((Map<?,?>)value).size()+"}";
			if(value instanceof List<?>) return " ["+((List<?>)value).size()+"]";
			if(value==null) return ": null";
			String text;
			try{ text = TalkJson.result(value); }catch(xTalkException e){ text = String.valueOf(value); }
			return ": "+text.replace("\r\n", "\n").replace('\r', '\n').replace('\n', '⏎');
		}

		/** Each non-empty line a node; its leading tabs give its level under the nearest line above with fewer tabs. */
		static Tree indented(String text){
			Node root = new Node("", "", 0, "", null, -1);
			if(text.isEmpty()) return new Tree(root, false);
			String[] lines = FieldTable.Data.lines(text);
			ArrayList<Node> parents = new ArrayList<Node>();
			ArrayList<Integer> tabs = new ArrayList<Integer>();
			parents.add(root);
			tabs.add(-1);
			for(int i=0; i<lines.length; i++){
				String line = lines[i];
				if(line.trim().isEmpty()) continue;
				int depth = 0;
				while(depth<line.length() && line.charAt(depth)=='\t') depth++;
				while(tabs.size()>1 && tabs.get(tabs.size()-1)>=depth){ parents.remove(parents.size()-1); tabs.remove(tabs.size()-1); }
				Node parent = parents.get(parents.size()-1);
				String label = line.substring(depth);
				if(label.equals(NEW_NODE)) label = "";
				//the id: the labels from the top (a repeated label under the same parent gets its count)
				String base = parent.id+"\u0001"+label;
				String id = base;
				for(int k=2; hasChildId(parent, id); k++) id = base+"\u0002"+k;
				Node n = new Node(label, id, i+1, "", null, parent.level+1);
				parent.children.add(n);
				parents.add(n);
				tabs.add(depth);
			}
			return new Tree(root, false);
		}

		static Set<String> ids(Node node){
			Set<String> ids = new HashSet<String>();
			addIds(node, ids);
			return ids;
		}
		private static void addIds(Node node, Set<String> ids){
			for(Node c : node.children){ ids.add(c.id); addIds(c, ids); }
		}
		static boolean sharesNode(Node node, Set<String> ids){
			for(Node c : node.children) if(ids.contains(c.id) || sharesNode(c, ids)) return true;
			return false;
		}

		private static boolean hasChildId(Node parent, String id){
			for(Node c : parent.children) if(c.id.equals(id)) return true;
			return false;
		}

		/** The label of a new node's line while it is being typed (not empty, so the line is a node; shown as nothing). */
		static final String NEW_NODE = "\u200B";

		/** The leading tabs of a line (1-based). */
		static int depth(String text, int line){
			String[] lines = FieldTable.Data.lines(text);
			if(line<1 || line>lines.length) return 0;
			int depth = 0;
			while(depth<lines[line-1].length() && lines[line-1].charAt(depth)=='\t') depth++;
			return depth;
		}

		/** The last line of a node and its children. */
		static int lastLine(Node n){
			int last = n.line;
			for(Node c : n.children) last = Math.max(last, lastLine(c));
			return last;
		}

		/** The text with a new line (1-based; one past the last adds it at the end) of depth tabs and a label. */
		static String insertLine(String text, int line, int depth, String label){
			String v = label.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			List<String> lines = new ArrayList<String>(text.isEmpty() ? new ArrayList<String>() : Arrays.asList(FieldTable.Data.lines(text)));
			StringBuilder sb = new StringBuilder();
			for(int i=0; i<depth; i++) sb.append('\t');
			lines.add(Math.max(0, Math.min(line-1, lines.size())), sb.append(v).toString());
			return String.join("\n", lines)+(finalReturn ? "\n" : "");
		}

		/** The text without one line (1-based). */
		static String deleteLine(String text, int line){
			String[] lines = FieldTable.Data.lines(text);
			if(text.isEmpty() || line<1 || line>lines.length) return text;
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			List<String> rest = new ArrayList<String>(Arrays.asList(lines));
			rest.remove(line-1);
			if(rest.isEmpty()) return "";
			return String.join("\n", rest)+(finalReturn ? "\n" : "");
		}

		/** Lines first to last (1-based) with delta tabs more (fewer, when negative, as far as they have tabs). */
		static String shift(String text, int first, int last, int delta){
			String[] lines = FieldTable.Data.lines(text);
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			for(int i=Math.max(1, first); i<=Math.min(last, lines.length); i++){
				String line = lines[i-1];
				if(delta>0){
					StringBuilder sb = new StringBuilder();
					for(int k=0; k<delta; k++) sb.append('\t');
					lines[i-1] = sb.append(line).toString();
				}
				else{
					int cut = 0;
					while(cut<-delta && cut<line.length() && line.charAt(cut)=='\t') cut++;
					lines[i-1] = line.substring(cut);
				}
			}
			return String.join("\n", lines)+(finalReturn ? "\n" : "");
		}

		/** The text with the label of one line (1-based) replaced, keeping its tabs; returns and tabs in the label become spaces. */
		static String setLabel(String text, int line, String label){
			String v = label.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
			String[] lines = FieldTable.Data.lines(text);
			if(line<1 || line>lines.length) return text;
			boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
			String old = lines[line-1];
			int depth = 0;
			while(depth<old.length() && old.charAt(depth)=='\t') depth++;
			lines[line-1] = old.substring(0, depth)+v;
			return String.join("\n", lines)+(finalReturn ? "\n" : "");
		}
	}
}

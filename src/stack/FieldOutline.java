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
	FieldOutlineData.Tree data = new FieldOutlineData.Tree(new FieldOutlineData.Node("", "", 0, "", null, -1), false);
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
				if(HZ.lockedScreen && !HZ.capturingEffect) return;
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
		return HZ.editMode==0 && !Host.get().authToolActive() && (!Host.get().hasCardWindow() || !Host.get().paintToolActive());
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
	private int scale(){ return Math.max(1, (int)Math.round(Host.get().scale())); }

	private static FieldOutlineData.Node node(TreePath path){
		if(path==null) return null;
		Object last = path.getLastPathComponent();
		if(!(last instanceof DefaultMutableTreeNode)) return null;
		Object user = ((DefaultMutableTreeNode)last).getUserObject();
		return user instanceof FieldOutlineData.Node ? (FieldOutlineData.Node)user : null;
	}

	/** The font, colours, border and selection mode from the field's properties. */
	void applyAppearance(){
		OField f = fldData;
		int scale = scale();
		Font font = OObject.stackFont(f.textFont, f.textStyle&0x03, f.textSize*Host.get().scale());
		tree.setFont(font);
		tree.setRowHeight(Host.get().toPhysical(Math.max(f.textHeight, f.textSize+2)));
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
		FieldOutlineData.Tree old = data;
		data = FieldOutlineData.parse(fldData.getText());
		//other text altogether (no node in common, JSON instead of indented text): open it to the outlineLevel like a new one
		if(open!=null && (old.json!=data.json || !FieldOutlineData.sharesNode(old.root, FieldOutlineData.ids(data.root)))) open = null;
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

	private static DefaultMutableTreeNode treeNode(FieldOutlineData.Node node){
		DefaultMutableTreeNode t = new DefaultMutableTreeNode(node);
		for(FieldOutlineData.Node child : node.children) t.add(treeNode(child));
		return t;
	}

	/** Opens the nodes that were open (or, the first time, the levels below the field's outlineLevel), parents first. */
	private void expand(DefaultMutableTreeNode root, Set<String> open){
		int level = Math.max(0, fldData.outlineLevel);
		Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
		while(nodes.hasMoreElements()){
			DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
			if(t==root || t.isLeaf()) continue;
			FieldOutlineData.Node n = (FieldOutlineData.Node)t.getUserObject();
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
			if(t==root || t.isLeaf() || !(t.getUserObject() instanceof FieldOutlineData.Node)) continue;
			if(tree.isExpanded(new TreePath(t.getPath()))) open.add(((FieldOutlineData.Node)t.getUserObject()).id);
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
				if(t==root || !(t.getUserObject() instanceof FieldOutlineData.Node)) continue;
				FieldOutlineData.Node n = (FieldOutlineData.Node)t.getUserObject();
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
			FieldOutlineData.Node n = node(p);
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
		FieldOutlineData.Node n = node(path);
		if(n==null || n.line<1 || data.json) return;
		String label = value==null ? "" : value.toString();
		//the outline is rebuilt after the editor has gone (not while the tree is still finishing the edit)
		if(isPending(n)){
			if(label.trim().isEmpty()){ SwingUtilities.invokeLater(this::dropPending); return; }
			String text = FieldOutlineData.setLabel(pendingText, n.line, label);
			SwingUtilities.invokeLater(() -> change(text, false, l -> l, n.line));
			return;
		}
		if(label.equals(n.label)) return;
		String text = FieldOutlineData.setLabel(fldData.getText(), n.line, label);
		writeText(text);
		SwingUtilities.invokeLater(() -> change(text, false, l -> l, n.line));
	}

	private boolean isPending(FieldOutlineData.Node n){
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
		data = FieldOutlineData.parse(text);
		DefaultMutableTreeNode root = treeNode(data.root);
		syncing = true;
		try{
			model.setRoot(root);
			Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
			while(nodes.hasMoreElements()){
				DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
				if(t!=root && !t.isLeaf() && open.contains(((FieldOutlineData.Node)t.getUserObject()).line)) tree.expandPath(new TreePath(t.getPath()));
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
			if(t==root || t.isLeaf() || !(t.getUserObject() instanceof FieldOutlineData.Node)) continue;
			if(tree.isExpanded(new TreePath(t.getPath()))) open.add(((FieldOutlineData.Node)t.getUserObject()).line);
		}
		return open;
	}

	private TreePath pathOfLine(int line){
		DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
		Enumeration<javax.swing.tree.TreeNode> nodes = root.preorderEnumeration();
		while(nodes.hasMoreElements()){
			DefaultMutableTreeNode t = (DefaultMutableTreeNode)nodes.nextElement();
			if(t!=root && t.getUserObject() instanceof FieldOutlineData.Node && ((FieldOutlineData.Node)t.getUserObject()).line==line) return new TreePath(t.getPath());
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
	private FieldOutlineData.Node selectedNode(){
		return node(tree.getLeadSelectionPath()!=null ? tree.getLeadSelectionPath() : tree.getSelectionPath());
	}

	/** A new empty node to type: the first child of an open node, else the next one after the node and its children (none: last). */
	void newNodeAfter(FieldOutlineData.Node n, String typed){
		String text = fldData.getText();
		int at, depth;
		if(n==null){
			at = text.isEmpty() ? 1 : FieldTableData.lines(text).length+1;
			depth = 0;
		}
		else if(!n.children.isEmpty() && tree.isExpanded(pathOfLine(n.line))){
			at = n.line+1;
			depth = FieldOutlineData.depth(text, n.children.get(0).line);
		}
		else{
			at = FieldOutlineData.lastLine(n)+1;
			depth = FieldOutlineData.depth(text, n.line);
		}
		final int line = at;
		change(FieldOutlineData.insertLine(text, line, depth, FieldOutlineData.NEW_NODE), true, l -> l>=line ? l+1 : l, line);
		pendingLine = line;
		editLine(line, typed);
	}

	/** Removes the empty new node again (Escape, Return on it, or a click elsewhere). */
	private void dropPending(){
		if(pendingText==null) return;
		int line = pendingLine;
		FieldOutlineData.Node above = null;
		for(int l=line-1; l>=1 && above==null; l--) above = data.atLine(l);
		change(fldData.getText(), false, l -> l==line ? 0 : l>line ? l-1 : l, above!=null ? above.line : 0);
	}

	/** Return while editing: keeps the label, then a new node below (Return on an empty new node only ends the edit). */
	private void editorReturn(){
		FieldOutlineData.Node n = node(tree.getEditingPath());
		String label = editor.getText();
		stopOwnEdit();
		if(n==null) return;
		if(isPending(n) && label.trim().isEmpty()){ dropPending(); return; }
		change(FieldOutlineData.setLabel(shownText(), n.line, label), false, l -> l, n.line);
		FieldOutlineData.Node now = data.atLine(n.line);
		if(now!=null) newNodeAfter(now, null);
	}

	/** Tab or Shift-Tab while editing: keeps the label (an empty new node stays new) and goes on editing the moved node. */
	private void editorIndent(boolean in){
		FieldOutlineData.Node n = node(tree.getEditingPath());
		String label = editor.getText();
		int caret = editor.getCaretPosition();
		stopOwnEdit();
		if(n==null) return;
		boolean pending = isPending(n) && label.trim().isEmpty();
		if(!pending) change(FieldOutlineData.setLabel(shownText(), n.line, label), false, l -> l, n.line);
		FieldOutlineData.Node now = data.atLine(n.line);
		if(now!=null) indent(now, in, pending);
		editLine(n.line, null);
		if(tree.isEditing()) editor.setCaretPosition(Math.min(caret, editor.getText().length()));
	}

	/**
	 * Tab: the node (with its children) goes under the node above it at the same level. Shift-Tab: it goes up a level, after its
	 * parent (the nodes after it under that parent become its children, as the lines say).
	 */
	private void indent(FieldOutlineData.Node n, boolean in, boolean pending){
		String text = shownText();
		FieldOutlineData.Node parent = parentOf(data.root, n);
		if(parent==null) return;
		int depth = FieldOutlineData.depth(text, n.line), to;
		if(in){
			int index = parent.children.indexOf(n);
			if(index<1){ Toolkit.getDefaultToolkit().beep(); return; }
			FieldOutlineData.Node above = parent.children.get(index-1);
			to = FieldOutlineData.depth(text, above.line)+1;
		}
		else{
			if(parent==data.root){ Toolkit.getDefaultToolkit().beep(); return; }
			to = FieldOutlineData.depth(text, parent.line);
		}
		int delta = to-depth;
		String moved = FieldOutlineData.shift(text, n.line, FieldOutlineData.lastLine(n), delta);
		Set<Integer> opened = new HashSet<Integer>();
		if(in) opened.add(parent.children.get(parent.children.indexOf(n)-1).line); //shows the node under its new parent
		change(moved, pending, l -> l, n.line);
		for(int line : opened){ TreePath p = pathOfLine(line); if(p!=null) tree.expandPath(p); }
		showSelection();
	}

	private static FieldOutlineData.Node parentOf(FieldOutlineData.Node node, FieldOutlineData.Node child){
		for(FieldOutlineData.Node c : node.children){
			if(c==child) return node;
			FieldOutlineData.Node p = parentOf(c, child);
			if(p!=null) return p;
		}
		return null;
	}

	/** Delete: removes the selected node's line (its children go under the node above, as the lines say). */
	private void deleteNode(){
		FieldOutlineData.Node n = selectedNode();
		if(n==null || n.line<1){ Toolkit.getDefaultToolkit().beep(); return; }
		int line = n.line;
		int row = tree.getRowForPath(pathOfLine(line));
		FieldOutlineData.Node next = row>=0 && row+1<tree.getRowCount() ? node(tree.getPathForRow(row+1)) : null;
		FieldOutlineData.Node prev = row>0 ? node(tree.getPathForRow(row-1)) : null;
		int select = next!=null ? next.line-1 : prev!=null ? prev.line : 0;
		change(FieldOutlineData.deleteLine(fldData.getText(), line), false, l -> l==line ? 0 : l>line ? l-1 : l, select);
	}

	/** A character typed on the outline (not in the editor): replaces the selected node's label, or starts a new last node. */
	boolean typeIntoNode(KeyEvent e){
		char c = e.getKeyChar();
		if(!labelsEditable() || c<0x20 || c==0x7f || c==KeyEvent.CHAR_UNDEFINED || e.isMetaDown() || e.isControlDown()) return false;
		//typed before the editor has the keyboard: goes into it too
		if(tree.isEditing()){ editor.replaceSelection(String.valueOf(c)); return true; }
		FieldOutlineData.Node n = selectedNode();
		if(n!=null && n.line>0) editLine(n.line, String.valueOf(c));
		else newNodeAfter(null, String.valueOf(c));
		return true;
	}

	/** Return, Tab, Shift-Tab and Delete on the outline and in its label editor. */
	private void outlinerKeys(){
		tree.setFocusTraversalKeysEnabled(false);
		editor.setFocusTraversalKeysEnabled(false);
		bind(tree, "ENTER", "hzEditNode", () -> {
			FieldOutlineData.Node n = selectedNode();
			if(n!=null && n.line>0) editLine(n.line, null);
			else newNodeAfter(null, null);
		});
		bind(tree, "TAB", "hzIndent", () -> { FieldOutlineData.Node n = selectedNode(); if(n!=null && n.line>0) indent(n, true, false); });
		bind(tree, "shift TAB", "hzOutdent", () -> { FieldOutlineData.Node n = selectedNode(); if(n!=null && n.line>0) indent(n, false, false); });
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
			if(HZ.editMode!=0){
				//Command-Option-Shift shows the field outlines: a click opens the script
				//(key[]に Command-Option が残っていただけの普通のクリックならピークを終える)
				if(!GUI.isPeekClick(e)) GUI.endPeek();
				else if(GUI.stroke_frag==2 && Host.get().hasCardWindow() && HZ.stack()!=null){
					ScriptEditor.openScriptEditor(HZ.stack().pcard, f);
					GUI.endPeek();
				}
				return;
			}
			if(!browsing()) return;
			Point p = toPane(e);
			GUI.clickH = f.left + Host.get().toLogical(p.x);
			GUI.clickV = f.top + Host.get().toLogical(p.y);
			GUI.noteFieldClick(f, p.x, p.y);
			HZ.clickField = f;
			f.clickNode = null;
			if(e.getSource()==tree){
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				FieldOutlineData.Node n = node(path);
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

}

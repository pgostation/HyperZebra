import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.ChangeEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The HyperTalk quick reference beside the script editor (Help > HyperTalk Reference, Cmd-K, or
 * the Reference button): a search field, the categories on the left and one line per entry.
 * Typing filters at once; Up/Down pick a line, Return (or a double-click) inserts it into the
 * script with its first placeholder selected. The selected line grows to show its whole syntax
 * (one form per line) and description. The entries are {@link HyperTalkReference}'s; "HZ" marks
 * what HyperZebra adds to HyperTalk.
 */
final class GScriptReference extends JDialog {
	private static final long serialVersionUID=1L;
	private static GScriptReference instance;

	private final ScriptEditor editor;
	private final JTextField search;
	private final JCheckBox hzOnly;
	private final JList<Object> categoryList;
	private final DefaultListModel<Object> categoryModel=new DefaultListModel<Object>();
	private final Map<String,Integer> counts=new HashMap<String,Integer>();
	private final EntryModel model=new EntryModel();
	private final JTable table;
	private final JLabel status;
	private boolean updating;
	/** The view row shown on several lines (the selected one), or -1. */
	private int expandedRow=-1;
	/** Draws and measures the expanded row's syntax and description. */
	private final WrapRenderer wrap=new WrapRenderer();
	/** Shown in the category list above the categories: every entry. */
	private static final String ALL="*";

	/**
	 * Shows the reference for the script editor. A word the reference knows becomes the search
	 * (the selection or the word at the caret); otherwise the last search stays.
	 */
	static GScriptReference show(ScriptEditor editor,String lookup){
		if(instance==null||instance.editor!=editor||!instance.isDisplayable()){
			if(instance!=null)instance.dispose();
			instance=new GScriptReference(editor);
			instance.place();
		}
		if(lookup!=null&&HyperTalkReference.knows(lookup))instance.setQuery(lookup.strip());
		instance.setVisible(true);
		instance.toFront();
		instance.search.requestFocusInWindow();
		instance.search.selectAll();
		return instance;
	}

	/** The open reference window, or null (tests). */
	static GScriptReference current(){return instance;}

	private GScriptReference(ScriptEditor editor){
		super(editor,International.t("HyperTalk Quick Reference"),ModalityType.MODELESS);
		this.editor=editor;
		setDefaultCloseOperation(HIDE_ON_CLOSE);

		search=new HintField(International.t("Search (e.g. put, repeat, textFont, line, colour)"));
		search.getDocument().addDocumentListener(new DocumentListener(){
			public void insertUpdate(DocumentEvent e){queryChanged();}
			public void removeUpdate(DocumentEvent e){queryChanged();}
			public void changedUpdate(DocumentEvent e){queryChanged();}
		});
		hzOnly=new JCheckBox(International.t("HyperZebra additions only"));
		hzOnly.setToolTipText(International.t("Only the HZ entries (added by HyperZebra, not in HyperCard)"));
		hzOnly.setFocusable(false);
		hzOnly.addActionListener(e->refresh());
		JPanel top=new JPanel(new BorderLayout(8,0));
		top.setBorder(BorderFactory.createEmptyBorder(8,8,6,8));
		top.add(search,BorderLayout.CENTER);
		top.add(hzOnly,BorderLayout.EAST);

		categoryModel.addElement(ALL);
		for(HyperTalkReference.Category c:HyperTalkReference.CATEGORIES)categoryModel.addElement(c);
		categoryList=new JList<Object>(categoryModel);
		categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		categoryList.setFocusable(false);
		categoryList.setCellRenderer(new CategoryRenderer());
		categoryList.setSelectedIndex(0);
		categoryList.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!updating)refresh();});
		JScrollPane categoryScroll=new JScrollPane(categoryList,JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		categoryScroll.setMinimumSize(new Dimension(150,100));

		table=new JTable(model);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setShowGrid(false);
		table.setIntercellSpacing(new Dimension(0,0));
		table.setRowHeight(Math.max(20,table.getFontMetrics(table.getFont()).getHeight()+6));
		table.setFillsViewportHeight(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setMaxWidth(34);
		table.getColumnModel().getColumn(0).setMinWidth(34);
		table.getColumnModel().getColumn(1).setPreferredWidth(420);
		table.getColumnModel().getColumn(2).setPreferredWidth(340);
		table.getColumnModel().getColumn(3).setPreferredWidth(110);
		table.getColumnModel().getColumn(0).setCellRenderer(new MarkRenderer());
		table.getColumnModel().getColumn(1).setCellRenderer(new SyntaxRenderer());
		table.getColumnModel().getColumn(2).setCellRenderer(new TipRenderer(true));
		table.getColumnModel().getColumn(3).setCellRenderer(new TipRenderer(false));
		table.addMouseListener(new MouseAdapter(){
			@Override public void mouseClicked(MouseEvent e){
				if(e.getClickCount()==2&&table.rowAtPoint(e.getPoint())>=0)insertSelected();
			}
		});
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"insertEntry");
		table.getActionMap().put("insertEntry",action(this::insertSelected));
		table.getActionMap().put("copy",action(this::copySelected));
		table.getSelectionModel().addListSelectionListener((ListSelectionEvent e)->{if(!e.getValueIsAdjusting())fitSelectedRow();});
		table.getColumnModel().addColumnModelListener(new TableColumnModelListener(){
			public void columnMarginChanged(ChangeEvent e){SwingUtilities.invokeLater(GScriptReference.this::fitSelectedRow);}
			public void columnAdded(TableColumnModelEvent e){}
			public void columnRemoved(TableColumnModelEvent e){}
			public void columnMoved(TableColumnModelEvent e){}
			public void columnSelectionChanged(ListSelectionEvent e){}
		});
		table.addComponentListener(new ComponentAdapter(){
			@Override public void componentResized(ComponentEvent e){SwingUtilities.invokeLater(GScriptReference.this::fitSelectedRow);}
		});
		JScrollPane tableScroll=new JScrollPane(table);

		JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,categoryScroll,tableScroll);
		split.setDividerLocation(International.uses("ja")?205:185);
		split.setBorder(BorderFactory.createEmptyBorder(0,8,0,8));

		status=new JLabel(" ");
		status.setForeground(UIManager.getColor("Label.disabledForeground"));
		JButton copy=new JButton(International.t("Copy"));
		copy.setFocusable(false);
		copy.addActionListener(e->copySelected());
		JButton insert=new JButton(International.t("Insert in Script"));
		insert.setFocusable(false);
		insert.addActionListener(e->insertSelected());
		JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
		buttons.add(copy);
		buttons.add(insert);
		JPanel bottom=new JPanel(new BorderLayout(8,0));
		bottom.setBorder(BorderFactory.createEmptyBorder(6,10,8,8));
		bottom.add(status,BorderLayout.CENTER);
		bottom.add(buttons,BorderLayout.EAST);

		getContentPane().add(top,BorderLayout.NORTH);
		getContentPane().add(split,BorderLayout.CENTER);
		getContentPane().add(bottom,BorderLayout.SOUTH);

		// the search field steers the list: Up/Down move, Return inserts, Escape clears and then closes
		bind(search,KeyEvent.VK_DOWN,0,()->moveSelection(1));
		bind(search,KeyEvent.VK_UP,0,()->moveSelection(-1));
		bind(search,KeyEvent.VK_PAGE_DOWN,0,()->moveSelection(10));
		bind(search,KeyEvent.VK_PAGE_UP,0,()->moveSelection(-10));
		bind(search,KeyEvent.VK_ENTER,0,this::insertSelected);
		int menu=Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		JComponent root=getRootPane();
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"escape");
		root.getActionMap().put("escape",action(()->{
			if(!search.getText().isEmpty()){search.setText("");search.requestFocusInWindow();}
			else setVisible(false);
		}));
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_W,menu),"close");
		root.getActionMap().put("close",action(()->setVisible(false)));
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F,menu),"find");
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_K,menu),"find");
		root.getActionMap().put("find",action(()->{search.requestFocusInWindow();search.selectAll();}));

		refresh();
		setSize(1000,560);
	}

	/** Beside the script editor when the screen has room, otherwise over its right half. */
	private void place(){
		Rectangle owner=editor.getBounds();
		GraphicsConfiguration gc=editor.getGraphicsConfiguration();
		Rectangle screen=gc!=null?gc.getBounds():new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
		int w=getWidth(),h=getHeight();
		int x=owner.x+owner.width+4;
		if(x+w>screen.x+screen.width)x=owner.x-w-4;
		if(x<screen.x)x=Math.max(screen.x,Math.min(owner.x+owner.width-w/2,screen.x+screen.width-w));
		int y=Math.max(screen.y,Math.min(owner.y,screen.y+screen.height-h));
		setLocation(x,y);
	}

	void setQuery(String query){
		search.setText(query);
	}

	String query(){return search.getText();}

	/** The entries shown now, in order (tests). */
	List<HyperTalkReference.Entry> shownEntries(){return new ArrayList<HyperTalkReference.Entry>(model.rows);}

	private boolean hadQuery;

	private void queryChanged(){
		boolean hasQuery=!search.getText().isBlank();
		// a new search looks everywhere; the category can be narrowed afterwards
		if(hasQuery&&!hadQuery&&categoryList.getSelectedIndex()!=0){
			updating=true;
			try{categoryList.setSelectedIndex(0);}finally{updating=false;}
		}
		hadQuery=hasQuery;
		refresh();
	}

	private String selectedCategory(){
		Object sel=categoryList.getSelectedValue();
		return sel instanceof HyperTalkReference.Category?((HyperTalkReference.Category)sel).id:null;
	}

	private void refresh(){
		String query=search.getText();
		boolean hz=hzOnly.isSelected();
		List<HyperTalkReference.Entry> all=HyperTalkReference.search(query,null,hz);
		counts.clear();
		counts.put(ALL,all.size());
		for(HyperTalkReference.Category c:HyperTalkReference.CATEGORIES){
			int n=0;
			for(HyperTalkReference.Entry e:all)if(e.in(c.id))n++;
			counts.put(c.id,n);
		}
		categoryList.repaint();
		String cat=selectedCategory();
		List<HyperTalkReference.Entry> rows=new ArrayList<HyperTalkReference.Entry>();
		for(HyperTalkReference.Entry e:all)if(e.in(cat))rows.add(e);
		expandedRow=-1;
		model.setRows(rows);
		if(!rows.isEmpty()){
			table.setRowSelectionInterval(0,0);
			table.scrollRectToVisible(table.getCellRect(0,0,true));
		}
		String n=International.t(rows.size()==1?"{0} entry":"{0} entries",rows.size());
		String text=International.t("{0}   ↑↓ choose   Return insert   ⌘C copy   esc clear/close",n);
		if(!System.getProperty("os.name","").toLowerCase().startsWith("mac"))text=text.replace("⌘","Ctrl-");
		status.setText(text);
	}

	private void moveSelection(int delta){
		int n=table.getRowCount();
		if(n==0)return;
		int row=table.getSelectedRow();
		row=row<0?(delta>0?0:n-1):Math.max(0,Math.min(n-1,row+delta));
		table.setRowSelectionInterval(row,row);
		table.scrollRectToVisible(table.getCellRect(row,0,true));
	}

	/** The selected row as tall as its wrapped syntax and description; the row before back to one line. */
	private void fitSelectedRow(){
		int row=table.getSelectedRow();
		int base=table.getRowHeight();
		if(expandedRow>=0&&expandedRow!=row&&expandedRow<table.getRowCount()&&table.getRowHeight(expandedRow)!=base)table.setRowHeight(expandedRow,base);
		expandedRow=row;
		if(row<0)return;
		int h=base;
		for(int c=1;c<=2;c++){
			int w=table.getColumnModel().getColumn(c).getWidth();
			if(w<=0)continue;
			Component comp=table.prepareRenderer(table.getCellRenderer(row,c),row,c);
			comp.setSize(w,Short.MAX_VALUE);
			h=Math.max(h,comp.getPreferredSize().height);
		}
		if(table.getRowHeight(row)!=h){
			table.setRowHeight(row,h);
			table.scrollRectToVisible(table.getCellRect(row,0,true));
		}
	}

	/** Space above a cell's first line so it sits where it would in a one-line row. */
	private int topInset(Font f){
		return Math.max(0,(table.getRowHeight()-table.getFontMetrics(f).getHeight())/2);
	}

	/** A cell of the expanded row that is not wrapped: its text level with the first line. */
	private void alignTop(DefaultTableCellRenderer r,int row,int left,int right){
		if(row==expandedRow){
			r.setVerticalAlignment(SwingConstants.TOP);
			r.setBorder(BorderFactory.createEmptyBorder(topInset(r.getFont()),left,0,right));
		}else{
			r.setVerticalAlignment(SwingConstants.CENTER);
			r.setBorder(BorderFactory.createEmptyBorder(0,left,0,right));
		}
	}

	private HyperTalkReference.Entry selectedEntry(){
		int row=table.getSelectedRow();
		if(row<0&&table.getRowCount()>0)row=0;
		return row<0?null:model.rows.get(table.convertRowIndexToModel(row));
	}

	private void copySelected(){
		HyperTalkReference.Entry e=selectedEntry();
		if(e==null)return;
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(e.template()),null);
	}

	/** Puts the selected entry's template at the script editor's caret and selects its first placeholder. */
	void insertSelected(){
		HyperTalkReference.Entry e=selectedEntry();
		ScriptArea area=ScriptEditor.selectedArea();
		if(e==null)return;
		if(area==null||!area.isEditable()){Toolkit.getDefaultToolkit().beep();return;}
		String text=e.template();
		int start=area.getSelectionStart();
		area.replaceSelection(text);
		int[] ph=HyperTalkReference.firstPlaceholder(text);
		if(ph!=null)area.select(start+ph[0],start+ph[1]);
		else area.setCaretPosition(start+text.length());
		if(text.indexOf('\n')>=0)area.checkIndentCall();
		editor.toFront();
		area.requestFocusInWindow();
	}

	private static void bind(JComponent c,int key,int mods,Runnable r){
		String name="ref-"+key+"-"+mods;
		c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key,mods),name);
		c.getActionMap().put(name,action(r));
	}

	private static AbstractAction action(Runnable r){
		return new AbstractAction(){
			private static final long serialVersionUID=1L;
			public void actionPerformed(ActionEvent e){r.run();}
		};
	}

	/** The entry's first category; a property shows its object ("Field"). */
	private String categoryTitle(HyperTalkReference.Entry e){
		HyperTalkReference.Category c=HyperTalkReference.category(e.cats.get(0));
		return c==null?"":c.title();
	}

	private final class EntryModel extends AbstractTableModel {
		private static final long serialVersionUID=1L;
		List<HyperTalkReference.Entry> rows=new ArrayList<HyperTalkReference.Entry>();
		void setRows(List<HyperTalkReference.Entry> rows){this.rows=rows;fireTableDataChanged();}
		public int getRowCount(){return rows.size();}
		public int getColumnCount(){return 4;}
		@Override public String getColumnName(int c){
			switch(c){
			case 0:return "";
			case 1:return International.t("Syntax");
			case 2:return International.t("Description");
			default:return International.t("Category");
			}
		}
		public Object getValueAt(int r,int c){
			HyperTalkReference.Entry e=rows.get(r);
			switch(c){
			case 0:return e.hz?"HZ":"";
			case 1:return e;
			case 2:return e.description();
			default:return categoryTitle(e);
			}
		}
	}

	/** The HyperZebra mark: a small coloured "HZ". */
	private final class MarkRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID=1L;
		private final Color mark=new Color(0xD0,0x5A,0x00);
		@Override public Component getTableCellRendererComponent(JTable t,Object v,boolean sel,boolean focus,int r,int c){
			super.getTableCellRendererComponent(t,v,sel,false,r,c);
			setHorizontalAlignment(SwingConstants.CENTER);
			setFont(t.getFont().deriveFont(Font.BOLD,Math.max(9f,t.getFont().getSize2D()-3f)));
			if(!sel)setForeground(mark);
			alignTop(this,r,0,0);
			setToolTipText("HZ".equals(v)?International.t("Added by HyperZebra (not in HyperCard)"):null);
			return this;
		}
	}

	/** The syntax in the script font; placeholders, optional brackets and alternatives are quieter. */
	private final class SyntaxRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID=1L;
		private final Map<HyperTalkReference.Entry,String> html=new HashMap<HyperTalkReference.Entry,String>();
		private final Font font;
		SyntaxRenderer(){
			Font base=table.getFont();
			String family=PCARD.scriptFont==null||PCARD.scriptFont.isEmpty()?Font.MONOSPACED:PCARD.scriptFont;
			font=new Font(family,Font.PLAIN,Math.round(base.getSize2D()));
		}
		@Override public Component getTableCellRendererComponent(JTable t,Object v,boolean sel,boolean focus,int r,int c){
			HyperTalkReference.Entry e=(HyperTalkReference.Entry)v;
			if(r==expandedRow)return wrap.show(t,String.join("\n",e.display().split("  ·  ")),font,sel);
			super.getTableCellRendererComponent(t,"",sel,false,r,c);
			setFont(font);
			setText(sel?"<html><nobr>"+escape(e.display())+"</nobr></html>":html.computeIfAbsent(e,GScriptReference::highlighted));
			setToolTipText(e.display());
			setBorder(BorderFactory.createEmptyBorder(0,4,0,4));
			return this;
		}
	}

	private static String escape(String s){
		return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
	}

	/** Placeholders in grey italics, [ ] | … · in grey. */
	private static String highlighted(HyperTalkReference.Entry e){
		String s=e.display();
		String quiet=colour(UIManager.getColor("Label.disabledForeground"),new Color(130,130,130));
		String place=colour(null,new Color(40,110,170));
		StringBuilder b=new StringBuilder("<html><nobr>");
		for(int i=0;i<s.length();i++){
			char ch=s.charAt(i);
			if(ch=='<'){
				int end=s.indexOf('>',i);
				if(end>i+1&&s.substring(i+1,end).indexOf(' ')<0&&s.substring(i+1,end).indexOf('<')<0&&!HyperTalkReference.isTag(s,i,end+1)){
					b.append("<i><font color='").append(place).append("'>&lt;").append(escape(s.substring(i+1,end))).append("&gt;</font></i>");
					i=end;
					continue;
				}
			}
			if(ch=='['||ch==']'||ch=='|'||ch=='…'||ch=='·'){
				b.append("<font color='").append(quiet).append("'>").append(ch).append("</font>");
				continue;
			}
			b.append(escape(String.valueOf(ch)));
		}
		return b.append("</nobr></html>").toString();
	}

	private static String colour(Color c,Color fallback){
		if(c==null)c=fallback;
		return String.format("#%02x%02x%02x",c.getRed(),c.getGreen(),c.getBlue());
	}

	/** Description and category; the expanded row's description wraps. */
	private final class TipRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID=1L;
		private final boolean wraps;
		TipRenderer(boolean wraps){this.wraps=wraps;}
		@Override public Component getTableCellRendererComponent(JTable t,Object v,boolean sel,boolean focus,int r,int c){
			if(wraps&&r==expandedRow)return wrap.show(t,v==null?"":v.toString(),t.getFont(),sel);
			super.getTableCellRendererComponent(t,v,sel,false,r,c);
			setToolTipText(v==null||v.toString().isEmpty()?null:v.toString());
			alignTop(this,r,4,4);
			return this;
		}
	}

	/** Several lines, wrapped at words, for the expanded row. */
	private final class WrapRenderer extends JTextArea {
		private static final long serialVersionUID=1L;
		WrapRenderer(){
			setLineWrap(true);
			setWrapStyleWord(true);
			setEditable(false);
			setOpaque(true);
		}
		Component show(JTable t,String text,Font f,boolean sel){
			setFont(f);
			setText(text);
			setForeground(sel?t.getSelectionForeground():t.getForeground());
			setBackground(sel?t.getSelectionBackground():t.getBackground());
			int pad=topInset(f);
			setBorder(BorderFactory.createEmptyBorder(pad,4,pad,4));
			setToolTipText(null);
			return this;
		}
	}

	private final class CategoryRenderer extends DefaultListCellRenderer {
		private static final long serialVersionUID=1L;
		@Override public Component getListCellRendererComponent(JList<?> list,Object v,int index,boolean sel,boolean focus){
			String title;
			String id;
			int indent=6;
			if(v instanceof HyperTalkReference.Category){
				HyperTalkReference.Category c=(HyperTalkReference.Category)v;
				title=c.title();
				id=c.id;
				if(c.isSub())indent=22;
			}else{
				title=International.t("All");
				id=ALL;
			}
			Integer n=counts.get(id);
			super.getListCellRendererComponent(list,title+(n==null?"":"  ("+n+")"),index,sel,false);
			setBorder(BorderFactory.createEmptyBorder(2,indent,2,6));
			if(n!=null&&n==0&&!sel)setForeground(UIManager.getColor("Label.disabledForeground"));
			return this;
		}
	}

	/** A text field that shows a grey hint while it is empty. */
	private static final class HintField extends JTextField {
		private static final long serialVersionUID=1L;
		private final String hint;
		HintField(String hint){this.hint=hint;}
		@Override protected void paintComponent(Graphics g){
			super.paintComponent(g);
			if(!getText().isEmpty())return;
			Graphics g2=g.create();
			try{
				g2.setColor(UIManager.getColor("Label.disabledForeground")!=null?UIManager.getColor("Label.disabledForeground"):Color.GRAY);
				g2.setFont(getFont());
				java.awt.Insets in=getInsets();
				int y=in.top+(getHeight()-in.top-in.bottom-g2.getFontMetrics().getHeight())/2+g2.getFontMetrics().getAscent();
				g2.drawString(hint,in.left+2,y);
			}finally{
				g2.dispose();
			}
		}
	}
}

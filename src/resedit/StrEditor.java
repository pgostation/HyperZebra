import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

class StrUI {
	static String text(String english) {
		if(PCARDFrame.pc != null && PCARDFrame.pc.intl != null)
			return PCARDFrame.pc.intl.getText(english);
		return english;
	}
	static String dialogText(String english) {
		if(PCARDFrame.pc != null && PCARDFrame.pc.intl != null)
			return PCARDFrame.pc.intl.getDialogText(english);
		return english;
	}

	//種別ごとの表示名(ウインドウタイトル等に使う)
	static String typeLabel(String type) {
		if(type.equals("STR ")) return dialogText("String");
		if(type.equals("STR#")) return dialogText("String List");
		if(type.equals("TEXT")) return dialogText("Text");
		return type;
	}
}

/** DocumentListenerの3メソッドをまとめて同じRunnableへ流すための薄いアダプタ。 */
class SimpleDocListener implements DocumentListener {
	private final Runnable action;
	SimpleDocListener(Runnable action) { this.action = action; }
	@Override public void insertUpdate(DocumentEvent e) { action.run(); }
	@Override public void removeUpdate(DocumentEvent e) { action.run(); }
	@Override public void changedUpdate(DocumentEvent e) { action.run(); }
}

/** STR('STR ')/STR#/TEXTリソースの一覧。IconTypeEditor/SoundTypeEditorと同じ位置づけ。 */
class StrTypeEditor extends TableTypeEditor {
	private static final long serialVersionUID = 1L;

	StrTypeEditor(PCARDFrame pc, String type, OObject object) {
		super(pc, type, object, new String[]{"ID", StrUI.text("Name"), StrUI.dialogText("Preview")});
		setSize(560, 420);
		setLocationRelativeTo(pc);
		open(pc, 0);
		setVisible(true);
	}

	@Override String typeTitle() { return StrUI.typeLabel(type); }

	@Override Object[] rowFor(Rsrc.rsrcClass resource) {
		StackTextEncoding encoding = pcard.stack.legacyTextEncoding;
		String preview;
		try {
			byte[] data = Files.readAllBytes(new File(rsrc.getFilePathAll(resource.id, type)).toPath());
			preview = previewFor(type, data, encoding);
		} catch(Exception error) {
			preview = "("+StrUI.dialogText("unreadable")+")";
		}
		return new Object[]{resource.id, resource.name, preview};
	}

	private static String previewFor(String type, byte[] data, StackTextEncoding encoding) {
		String text;
		if(type.equals("STR#")) {
			String[] items = StrResourceCodec.decodeStringList(data, encoding);
			text = items.length==0 ? "" : items[0]+(items.length>1 ? "  (+"+(items.length-1)+")" : "");
		}
		else if(type.equals("STR ")) {
			text = StrResourceCodec.decodeString(data, encoding);
		}
		else {
			text = StrResourceCodec.decodeText(data, encoding);
		}
		text = text.replace("\r"," ").replace("\n"," ");
		return text.length()>60 ? text.substring(0,60)+"…" : text;
	}

	@Override void createNewResource() {
		new StrResourceEditor(this, null);
	}

	@Override void openResource(int id) {
		Rsrc.rsrcClass resource = rsrc.getResource1(id, type);
		if(resource != null) new StrResourceEditor(this, resource);
	}
}

/** STR/STR#/TEXT1件を編集するダイアログ。文字コードはその場で選び直せる。 */
class StrResourceEditor extends JDialog {
	private static final long serialVersionUID = 1L;
	private final StrTypeEditor owner;
	private final String type;
	private Rsrc.rsrcClass resource;
	private byte[] originalBytes = new byte[0];

	private final JTextField idField;
	private final JTextField nameField;
	private final JComboBox<StackTextEncoding> encodingCombo;
	private final JLabel byteCountLabel = new JLabel(" ");

	//STR /TEXT用
	private JTextArea textArea;
	//STR#用
	private DefaultListModel<String> listModel;
	private JList<String> stringList;
	private JTextArea entryArea;
	private boolean suppressListSync = false;

	//「リソースに反映」していない変更があるか(閉じるときに尋ねる)。本文は編集フラグ、ID・名前は反映済みの値と比べる
	private boolean loading = false;
	private boolean contentEdited = false;
	private String appliedId;
	private String appliedName;

	StrResourceEditor(StrTypeEditor owner, Rsrc.rsrcClass resource) {
		super(owner, true);
		this.owner = owner;
		this.type = owner.type;
		this.resource = resource;
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent e) { requestClose(); }
		});
		setLayout(new BorderLayout(8,8));

		JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT));
		header.add(new JLabel(StrUI.dialogText("ID:")));
		idField = new JTextField(resource==null
			? Integer.toString(owner.rsrc.getNewResourceId(type)) : Integer.toString(resource.id), 6);
		header.add(idField);
		header.add(new JLabel(StrUI.dialogText("Name:")));
		nameField = new JTextField(resource==null ? "" : resource.name, 20);
		header.add(nameField);
		header.add(new JLabel(StrUI.dialogText("Encoding:")));
		encodingCombo = new JComboBox<StackTextEncoding>(StackTextEncoding.values());
		encodingCombo.setSelectedItem(owner.pcard.stack.legacyTextEncoding);
		encodingCombo.addActionListener(e -> reloadContentForEncoding());
		header.add(encodingCombo);
		add(header, BorderLayout.NORTH);

		add(buildContentComponent(), BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout());
		footer.add(byteCountLabel, BorderLayout.WEST);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		//作業用コピーへ反映する(スタックへはリソースエディタの保存(Cmd-S)で)
		buttons.add(button(StrUI.text("Apply to Resource"), e -> save()));
		buttons.add(button(StrUI.text("Close"), e -> requestClose()));
		footer.add(buttons, BorderLayout.EAST);
		add(footer, BorderLayout.SOUTH);

		setTitle(resource==null ? StrUI.dialogText("New")+" "+StrUI.typeLabel(type) : resource.id+" "+resource.name);
		loadContent();
		appliedId = idField.getText().trim();
		appliedName = nameField.getText();

		setSize(520, type.equals("STR#") ? 480 : 220);
		setLocationRelativeTo(owner);
		setVisible(true);
	}

	private JButton button(String text, ActionListener listener) {
		JButton btn = new JButton(text);
		btn.addActionListener(listener);
		return btn;
	}

	private JComponent buildContentComponent() {
		if(type.equals("STR#")) {
			listModel = new DefaultListModel<String>();
			stringList = new JList<String>(listModel);
			stringList.addListSelectionListener(e -> { if(!e.getValueIsAdjusting()) syncEntryAreaFromSelection(); });
			JScrollPane listScroll = new JScrollPane(stringList);
			listScroll.setPreferredSize(new Dimension(200, 300));

			entryArea = new JTextArea();
			entryArea.setLineWrap(true);
			entryArea.setWrapStyleWord(true);
			entryArea.getDocument().addDocumentListener(new SimpleDocListener(this::syncSelectionFromEntryArea));
			JScrollPane entryScroll = new JScrollPane(entryArea);

			JPanel listButtons = new JPanel();
			listButtons.setLayout(new BoxLayout(listButtons, BoxLayout.Y_AXIS));
			listButtons.add(button(StrUI.dialogText("Add String"), e -> addEntry()));
			listButtons.add(button(StrUI.dialogText("Remove"), e -> removeEntry()));
			listButtons.add(button(StrUI.dialogText("Move Up"), e -> moveEntry(-1)));
			listButtons.add(button(StrUI.dialogText("Move Down"), e -> moveEntry(1)));

			JPanel listPanel = new JPanel(new BorderLayout());
			listPanel.add(listScroll, BorderLayout.CENTER);
			listPanel.add(listButtons, BorderLayout.EAST);

			JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listPanel, entryScroll);
			split.setResizeWeight(0.5);
			return split;
		}
		else {
			textArea = new JTextArea();
			textArea.setLineWrap(true);
			textArea.setWrapStyleWord(true);
			textArea.getDocument().addDocumentListener(new SimpleDocListener(() -> { markEdited(); updateByteCount(); }));
			return new JScrollPane(textArea);
		}
	}

	private void loadContent() {
		if(resource != null) {
			try {
				originalBytes = Files.readAllBytes(
					new File(owner.rsrc.getFilePathAll(resource.id, type)).toPath());
			} catch(IOException error) {
				originalBytes = new byte[0];
			}
		}
		reloadContentForEncoding();
	}

	//文字コードを切り替えたときは、元の生バイト列をその文字コードで読み直す
	//(どの文字コードで作られたか分からない既存リソースを、見た目が読める
	//ものになるまで切り替えて試せるように)。本文を編集したあとは読み直さず
	//入力を残し、反映するときに選んだ文字コードで書き出す。
	private void reloadContentForEncoding() {
		if(contentEdited) {
			updateByteCount();
			return;
		}
		StackTextEncoding encoding = currentEncoding();
		loading = true;
		try {
			decodeOriginal(encoding);
		} finally {
			loading = false;
		}
		updateByteCount();
	}

	private void decodeOriginal(StackTextEncoding encoding) {
		if(type.equals("STR#")) {
			String[] items = StrResourceCodec.decodeStringList(originalBytes, encoding);
			suppressListSync = true;
			listModel.clear();
			for(String s : items) listModel.addElement(s);
			suppressListSync = false;
			if(listModel.size() > 0) stringList.setSelectedIndex(0);
			else syncEntryAreaFromSelection();
		}
		else {
			String text = type.equals("STR ")
				? StrResourceCodec.decodeString(originalBytes, encoding)
				: StrResourceCodec.decodeText(originalBytes, encoding);
			textArea.setText(text);
		}
	}

	private void markEdited() {
		if(!loading) contentEdited = true;
	}

	private boolean hasUnappliedChanges() {
		return contentEdited || !idField.getText().trim().equals(appliedId) || !nameField.getText().equals(appliedName);
	}

	//閉じる: 反映していない変更があれば 反映しない/キャンセル/反映 を尋ねる
	private void requestClose() {
		if(hasUnappliedChanges()) {
			String dontApply = StrUI.dialogText("Don't Apply");
			String cancel = StrUI.dialogText("Cancel");
			String apply = StrUI.text("Apply to Resource");
			new GDialog(owner, StrUI.dialogText("Apply the changes to “%1” before closing?").replace("%1", getTitle()),
				null, dontApply, cancel, apply);
			if(GDialog.clicked.equals(cancel)) return;
			if(GDialog.clicked.equals(apply) && !save()) return;
		}
		dispose();
	}

	private StackTextEncoding currentEncoding() {
		return (StackTextEncoding)encodingCombo.getSelectedItem();
	}

	private void syncEntryAreaFromSelection() {
		if(suppressListSync) return;
		int idx = stringList.getSelectedIndex();
		suppressListSync = true;
		entryArea.setText(idx < 0 ? "" : listModel.get(idx));
		entryArea.setEnabled(idx >= 0);
		suppressListSync = false;
	}

	private void syncSelectionFromEntryArea() {
		if(suppressListSync) return;
		int idx = stringList.getSelectedIndex();
		if(idx < 0) return;
		listModel.set(idx, entryArea.getText());
		markEdited();
		updateByteCount();
	}

	private void addEntry() {
		listModel.addElement("");
		stringList.setSelectedIndex(listModel.size()-1);
		markEdited();
		updateByteCount();
	}

	private void removeEntry() {
		int idx = stringList.getSelectedIndex();
		if(idx < 0) return;
		listModel.remove(idx);
		if(listModel.size() > 0) stringList.setSelectedIndex(Math.min(idx, listModel.size()-1));
		else syncEntryAreaFromSelection();
		markEdited();
		updateByteCount();
	}

	private void moveEntry(int delta) {
		int idx = stringList.getSelectedIndex();
		int newIdx = idx+delta;
		if(idx < 0 || newIdx < 0 || newIdx >= listModel.size()) return;
		String value = listModel.remove(idx);
		listModel.add(newIdx, value);
		stringList.setSelectedIndex(newIdx);
		markEdited();
	}

	private void updateByteCount() {
		StackTextEncoding encoding = currentEncoding();
		if(type.equals("STR#")) {
			int total = 2;
			for(int i=0; i<listModel.size(); i++)
				total += 1+Math.min(StrResourceCodec.MAX_PASCAL_STRING_BYTES, encoding.encodeReplacing(listModel.get(i)).length);
			byteCountLabel.setText(listModel.size()+" "+StrUI.dialogText("strings")+", "+total+" "+StrUI.dialogText("bytes"));
		}
		else if(type.equals("STR ")) {
			int len = encoding.encodeReplacing(textArea.getText()).length;
			byteCountLabel.setText(Math.min(StrResourceCodec.MAX_PASCAL_STRING_BYTES,len)+" / "
				+StrResourceCodec.MAX_PASCAL_STRING_BYTES+" "+StrUI.dialogText("bytes")
				+(len>StrResourceCodec.MAX_PASCAL_STRING_BYTES ? "  ("+StrUI.dialogText("will be truncated")+")" : ""));
		}
		else {
			int len = encoding.encodeReplacing(textArea.getText()).length;
			byteCountLabel.setText(len+" "+StrUI.dialogText("bytes"));
		}
	}

	//255バイトを超えるPascal文字列がある場合は、黙って切り詰めず確認する。
	private boolean confirmTruncationIfNeeded(StackTextEncoding encoding) {
		if(type.equals("STR#")) {
			for(int i=0; i<listModel.size(); i++) {
				if(encoding.encodeReplacing(listModel.get(i)).length > StrResourceCodec.MAX_PASCAL_STRING_BYTES) {
					return confirmTruncationDialog();
				}
			}
			return true;
		}
		if(type.equals("STR ")) {
			if(encoding.encodeReplacing(textArea.getText()).length > StrResourceCodec.MAX_PASCAL_STRING_BYTES) {
				return confirmTruncationDialog();
			}
		}
		return true;
	}

	private boolean confirmTruncationDialog() {
		int answer = JOptionPane.showConfirmDialog(this,
			StrUI.dialogText("This text is longer than 255 bytes and will be truncated when saved. Continue?"),
			StrUI.typeLabel(type), JOptionPane.OK_CANCEL_OPTION);
		return answer == JOptionPane.OK_OPTION;
	}

	//書き込み先はリソースエディタの作業用コピー(スタックには保存(Cmd-S)で書き戻される)。反映できたらtrue
	private boolean save() {
		int id;
		try {
			id = Integer.parseInt(idField.getText().trim());
		} catch(NumberFormatException error) {
			JOptionPane.showMessageDialog(this, StrUI.dialogText("Please enter a valid numeric ID."));
			return false;
		}
		//ほかのリソースのIDへは変えない(黙って置き換えない。リソース情報ダイアログと同じ)
		if((resource==null || id!=resource.id) && owner.rsrc.getResource1(id, type)!=null) {
			JOptionPane.showMessageDialog(this, StrUI.dialogText("A resource with this ID already exists."));
			return false;
		}

		StackTextEncoding encoding = currentEncoding();
		if(!confirmTruncationIfNeeded(encoding)) return false;

		byte[] data;
		if(type.equals("STR#")) {
			String[] items = new String[listModel.size()];
			for(int i=0; i<items.length; i++) items[i] = listModel.get(i);
			data = StrResourceCodec.encodeStringList(items, encoding);
		}
		else if(type.equals("STR ")) {
			data = StrResourceCodec.encodeString(textArea.getText(), encoding);
		}
		else {
			data = StrResourceCodec.encodeText(textArea.getText(), encoding);
		}

		String name = nameField.getText();
		String filename = owner.rsrc.freeFileName(type+"_"+id+".data", resource);
		try {
			try(FileOutputStream fos = new FileOutputStream(owner.rsrc.fileToWrite(filename))) {
				fos.write(data);
			}
		} catch(IOException error) {
			JOptionPane.showMessageDialog(this, StrUI.dialogText("The resource could not be saved.")+"\n"+error.getMessage());
			return false;
		}

		if(resource == null) {
			owner.rsrc.addResource(id, type, name, filename);
			resource = owner.rsrc.getResource1(id, type);
		}
		else {
			resource.filename = filename;
			owner.rsrc.setIdAndName(resource, id, name);
		}
		originalBytes = data;
		contentEdited = false;
		appliedId = idField.getText().trim();
		appliedName = name;
		setTitle(resource.id+" "+resource.name);
		owner.reload();
		owner.selectIds(new int[]{resource.id});
		return true;
	}
}

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/**
 * HyperZebra's own viewer of a classic Mac text document (a ReadMe in Shift-JIS or Mac OS Roman, a SimpleText 'ttro'
 * file), for a drop that no known Mac program opens: the host's own text editor reads such a file as UTF-8 and shows
 * garbage. {@link TextDocument} guesses the encoding; the menu at the top changes it. A SimpleText document keeps its
 * style runs and pictures. The text can be selected and copied, and saved as UTF-8 for the host. A picture clipping
 * shows its picture alone, to copy or save as PNG. One window per file.
 */
final class GTextDocumentWindow extends JFrame {
	private static final long serialVersionUID = 1L;
	private static final Map<File,GTextDocumentWindow> open = new HashMap<File,GTextDocumentWindow>();
	/** The point size of plain text at 100%. */
	private static final int PLAIN_SIZE = 13;

	/** Shows the file in its window (made on the first call, brought to the front later). */
	static void show(File f) throws IOException {
		File key = f.getAbsoluteFile();
		GTextDocumentWindow w = open.get(key);
		if(w==null){
			w = new GTextDocumentWindow(TextDocument.read(f));
			open.put(key, w);
			java.awt.Window near = PCARD.pc!=null && PCARD.pc.isShowing() ? PCARD.pc : null;
			w.setLocationRelativeTo(near);
		}
		w.setVisible(true);
		w.setState(JFrame.NORMAL);
		w.toFront();
	}

	private static String text(String key){ return PCARD.pc!=null && PCARD.pc.intl!=null ? PCARD.pc.intl.getDialogText(key) : key; }

	private final TextDocument doc;
	private boolean wrap = true;
	private final JTextPane pane = new JTextPane(){
		private static final long serialVersionUID = 1L;
		@Override public boolean getScrollableTracksViewportWidth(){
			return wrap || getParent()==null || getUI().getPreferredSize(this).width<=getParent().getWidth();
		}
	};
	private final JComboBox<TextDocument.Encoding> encodings = new JComboBox<TextDocument.Encoding>(TextDocument.ENCODINGS.toArray(new TextDocument.Encoding[0]));
	private final JCheckBox wrapBox = new JCheckBox(text("Wrap lines"), true), fixedBox = new JCheckBox(text("Fixed-width font"));
	private double zoom = 1;

	private GTextDocumentWindow(TextDocument doc){
		super(LauncherStore.displayName(doc.file));
		this.doc = doc;
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		if(PCARD.pc!=null && PCARD.pc.getIconImage()!=null) setIconImage(PCARD.pc.getIconImage());
		addWindowListener(new WindowAdapter(){ @Override public void windowClosed(WindowEvent e){ open.remove(doc.file.getAbsoluteFile()); } });
		int menu = GMenu.isMacOSX() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().registerKeyboardAction(e -> zoomBy(1.15), KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menu), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().registerKeyboardAction(e -> zoomBy(1.15), KeyStroke.getKeyStroke(KeyEvent.VK_SEMICOLON, menu), JComponent.WHEN_IN_FOCUSED_WINDOW); // + on a JIS keyboard
		getRootPane().registerKeyboardAction(e -> zoomBy(1.15), KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menu), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().registerKeyboardAction(e -> zoomBy(1/1.15), KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menu), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().registerKeyboardAction(e -> { zoom = 1; render(); }, KeyStroke.getKeyStroke(KeyEvent.VK_0, menu), JComponent.WHEN_IN_FOCUSED_WINDOW);

		pane.setEditable(false);
		pane.setBackground(Color.WHITE);
		pane.setForeground(Color.BLACK);
		pane.setSelectedTextColor(Color.BLACK);
		pane.setMargin(new java.awt.Insets(8, 10, 8, 10));
		encodings.setSelectedItem(doc.detected);
		encodings.setMaximumRowCount(TextDocument.ENCODINGS.size());
		encodings.setRenderer(new DefaultListCellRenderer(){
			private static final long serialVersionUID = 1L;
			@Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus){
				return super.getListCellRendererComponent(list, value==null ? "" : text(((TextDocument.Encoding)value).name), index, selected, focus);
			}
		});
		encodings.addActionListener(e -> render());
		wrapBox.addActionListener(e -> { wrap = wrapBox.isSelected(); pane.revalidate(); pane.repaint(); });
		fixedBox.addActionListener(e -> render());
		JButton smaller = new JButton("A−"), larger = new JButton("A+"), save = new JButton(text("Save as UTF-8…"));
		smaller.setToolTipText(text("Smaller text")); larger.setToolTipText(text("Larger text"));
		save.setToolTipText(text("Save a copy the host's editors read"));
		smaller.addActionListener(e -> zoomBy(1/1.15));
		larger.addActionListener(e -> zoomBy(1.15));
		save.addActionListener(e -> saveAsUtf8());

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		BufferedImage picture = doc.pictureOnly() ? PictureFile.loadPICT(doc.pictures.get(0)) : null;
		if(doc.pictureOnly()){ // a picture clipping: no text to decode or wrap
			smaller.setToolTipText(text("Smaller")); larger.setToolTipText(text("Larger"));
			JButton copy = new JButton(text("Copy")), png = new JButton(text("Save as PNG…"));
			copy.setEnabled(picture!=null); png.setEnabled(picture!=null);
			copy.addActionListener(e -> copyPicture(picture));
			png.addActionListener(e -> saveAsPng(picture));
			bar.add(smaller);
			bar.add(larger);
			bar.add(copy);
			bar.add(png);
		}
		else{
			bar.add(new JLabel(text("Text Encoding:")));
			bar.add(encodings);
			bar.add(wrapBox);
			bar.add(fixedBox);
			bar.add(smaller);
			bar.add(larger);
			bar.add(save);
		}
		JPanel content = new JPanel(new BorderLayout());
		content.add(bar, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(pane);
		scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xC8C8C8)));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		content.add(scroll, BorderLayout.CENTER);
		setContentPane(content);
		render();
		if(picture!=null) setSize(new Dimension(Math.min(1200, Math.max(bar.getPreferredSize().width+16, picture.getWidth()+60)), Math.min(900, Math.max(240, picture.getHeight()+bar.getPreferredSize().height+90))));
		else setSize(new Dimension(Math.max(680, bar.getPreferredSize().width+16), 760));
	}

	private void zoomBy(double factor){
		zoom = Math.max(0.5, Math.min(4, zoom*factor));
		render();
	}

	/** Fills the pane anew in the chosen encoding, size and font. */
	private void render(){
		TextDocument.Encoding e = (TextDocument.Encoding)encodings.getSelectedItem();
		if(e==null) return;
		boolean japanese = e==TextDocument.SHIFT_JIS || e==TextDocument.EUC_JP || e==TextDocument.ISO_2022_JP;
		DefaultStyledDocument d = new DefaultStyledDocument();
		try{
			if(doc.pictureOnly()) placePicture(d);
			else if(doc.runs.isEmpty() || !e.byteOriented()){
				SimpleAttributeSet plain = new SimpleAttributeSet();
				StyleConstants.setFontFamily(plain, family(japanese ? "Osaka" : "Geneva", japanese));
				StyleConstants.setFontSize(plain, (int)Math.round(PLAIN_SIZE*zoom));
				d.insertString(0, doc.text(e), plain);
			}
			else{
				List<TextDocument.Run> runs = doc.runs;
				for(int i=0; i<runs.size(); i++){
					TextDocument.Run run = runs.get(i);
					int from = i==0 ? 0 : run.start, to = i+1<runs.size() ? runs.get(i+1).start : doc.data.length;
					d.insertString(d.getLength(), TextDocument.lines(e.decode(doc.data, from, to)), attributes(run, japanese));
				}
				if(d.getLength()>0 && d.getText(0, 1).equals("﻿")) d.remove(0, 1);
			}
			if(e.macScript() && !doc.pictures.isEmpty() && !doc.pictureOnly()) placePictures(d, e.decode(new byte[]{(byte)0xCA}, 0, 1).charAt(0));
		}catch(BadLocationException impossible){ throw new IllegalStateException(impossible); }
		pane.setDocument(d);
		pane.setCaretPosition(0);
	}

	private SimpleAttributeSet attributes(TextDocument.Run run, boolean japanese){
		SimpleAttributeSet a = new SimpleAttributeSet();
		// the system and application fonts are Osaka on a Japanese system (KanjiTalk draws them so)
		String name = japanese && (run.font==0 || run.font==1 || run.font==3) ? "Osaka" : Fonts68k.nameOf(run.font);
		if(name.isEmpty()) name = japanese ? "Osaka" : "Geneva";
		StyleConstants.setFontFamily(a, family(name, japanese));
		StyleConstants.setFontSize(a, (int)Math.round((run.size<=0 ? 12 : run.size)*zoom*PLAIN_SIZE/12.0));
		StyleConstants.setBold(a, (run.face&1)!=0);
		StyleConstants.setItalic(a, (run.face&2)!=0);
		StyleConstants.setUnderline(a, (run.face&4)!=0);
		StyleConstants.setForeground(a, run.color);
		return a;
	}

	/** The host family for a classic font name, or the fixed-width one when that is chosen. */
	private String family(String name, boolean japanese){
		if(fixedBox.isSelected()) name = japanese ? "Osaka−等幅" : "Monaco";
		String family = OObject.hostFamily(name);
		return new Font(family, Font.PLAIN, 12).getFamily().equals(Font.DIALOG) ? Font.SANS_SERIF : family;
	}

	/**
	 * SimpleText's pictures: PICT 1000, 1001... take the places of the option-space characters, in order, each centred on
	 * its line. SimpleText draws a picture over the lines below it, so the empty lines its author left for it go.
	 */
	private void placePictures(DefaultStyledDocument d, char optionSpace) throws BadLocationException {
		int next = 0;
		for(int i=0; i<d.getLength() && next<doc.pictures.size(); i++){
			if(d.getText(i, 1).charAt(0)!=optionSpace) continue;
			BufferedImage img = PictureFile.loadPICT(doc.pictures.get(next++));
			if(img==null) continue;
			int w = Math.max(1, (int)Math.round(img.getWidth()*zoom)), h = Math.max(1, (int)Math.round(img.getHeight()*zoom));
			java.awt.Image shown = zoom==1 ? img : img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH);
			SimpleAttributeSet a = new SimpleAttributeSet(d.getCharacterElement(i).getAttributes());
			StyleConstants.setIcon(a, new ImageIcon(shown));
			d.remove(i, 1);
			d.insertString(i, " ", a);
			SimpleAttributeSet centre = new SimpleAttributeSet();
			StyleConstants.setAlignment(centre, StyleConstants.ALIGN_CENTER);
			d.setParagraphAttributes(i, 1, centre, false);
			int covered = 0;
			for(;;){
				int at = d.getParagraphElement(i).getEndOffset();
				if(at>=d.getLength()) break;
				javax.swing.text.Element line = d.getParagraphElement(at);
				int lineHeight = pane.getFontMetrics(d.getFont(d.getCharacterElement(at).getAttributes())).getHeight();
				if(!blank(d.getText(line.getStartOffset(), line.getEndOffset()-line.getStartOffset())) || covered+lineHeight>h+lineHeight/2) break;
				covered += lineHeight;
				d.remove(line.getStartOffset(), Math.min(line.getEndOffset(), d.getLength())-line.getStartOffset());
			}
		}
	}
	/** A picture clipping's picture, centred, at the zoom chosen. */
	private void placePicture(DefaultStyledDocument d) throws BadLocationException {
		BufferedImage img = PictureFile.loadPICT(doc.pictures.get(0));
		if(img==null){ d.insertString(0, text("The picture can't be read."), new SimpleAttributeSet()); return; }
		int w = Math.max(1, (int)Math.round(img.getWidth()*zoom)), h = Math.max(1, (int)Math.round(img.getHeight()*zoom));
		SimpleAttributeSet a = new SimpleAttributeSet();
		StyleConstants.setIcon(a, new ImageIcon(zoom==1 ? img : img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH)));
		d.insertString(0, " ", a);
		SimpleAttributeSet centre = new SimpleAttributeSet();
		StyleConstants.setAlignment(centre, StyleConstants.ALIGN_CENTER);
		d.setParagraphAttributes(0, 1, centre, false);
	}
	/** The picture on the host's clipboard. */
	private static void copyPicture(final BufferedImage img){
		if(img==null) return;
		java.awt.datatransfer.Transferable t = new java.awt.datatransfer.Transferable(){
			public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors(){ return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor}; }
			public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f){ return java.awt.datatransfer.DataFlavor.imageFlavor.equals(f); }
			public Object getTransferData(java.awt.datatransfer.DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException {
				if(!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f);
				return img;
			}
		};
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
	}
	/** Writes the picture as PNG to a file the user names, beside the clipping by default. */
	private void saveAsPng(BufferedImage img){
		if(img==null) return;
		FileDialog dialog = new FileDialog(this, text("Save as PNG…"), FileDialog.SAVE);
		File dir = doc.file.getAbsoluteFile().getParentFile();
		if(dir!=null) dialog.setDirectory(dir.getPath());
		dialog.setFile(LauncherStore.displayName(doc.file)+".png");
		dialog.setVisible(true);
		if(dialog.getFile()==null) return;
		File target = new File(dialog.getDirectory(), dialog.getFile());
		if(target.getAbsoluteFile().equals(doc.file.getAbsoluteFile())){
			new GDialog(this, text("Choose another name: the original stays as it is."), null, "OK", null, null);
			return;
		}
		try{ if(!javax.imageio.ImageIO.write(img, "png", target)) throw new IOException("PNG"); }
		catch(IOException ex){ new GDialog(this, text("Can't save the file.")+"\n"+ex.getMessage(), null, "OK", null, null); }
	}

	/** A line of nothing but spaces (an option-space is a picture's place, not a space). */
	private static boolean blank(String line){
		for(int i=0; i<line.length(); i++){ char c = line.charAt(i); if(c!=' ' && c!='\t' && c!='\n' && c!='　') return false; }
		return true;
	}

	/** Writes the text as UTF-8 (LF line ends) to a file the user names, beside the original by default. */
	private void saveAsUtf8(){
		TextDocument.Encoding e = (TextDocument.Encoding)encodings.getSelectedItem();
		if(e==null) return;
		FileDialog dialog = new FileDialog(this, text("Save as UTF-8…"), FileDialog.SAVE);
		File dir = doc.file.getAbsoluteFile().getParentFile();
		String name = LauncherStore.displayName(doc.file);
		name = name.toLowerCase(java.util.Locale.ROOT).endsWith(".txt") ? name.substring(0, name.length()-4)+" (UTF-8).txt" : name+".txt";
		if(dir!=null) dialog.setDirectory(dir.getPath());
		dialog.setFile(name);
		dialog.setVisible(true);
		if(dialog.getFile()==null) return;
		File target = new File(dialog.getDirectory(), dialog.getFile());
		if(target.getAbsoluteFile().equals(doc.file.getAbsoluteFile())){
			new GDialog(this, text("Choose another name: the original stays as it is."), null, "OK", null, null);
			return;
		}
		try{ Files.write(target.toPath(), doc.text(e).getBytes(StandardCharsets.UTF_8)); }
		catch(IOException ex){ new GDialog(this, text("Can't save the file.")+"\n"+ex.getMessage(), null, "OK", null, null); }
	}
}

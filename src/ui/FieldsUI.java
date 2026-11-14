import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.text.*;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.SystemColor;
import java.awt.dnd.DropTarget;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.DataInputStream;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import java.awt.Frame;
import javax.swing.JDialog;
import javax.swing.JFrame;

/** Desktop code moved out of the core's TUtil unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class FieldsUI {
	private FieldsUI(){}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction3 (the chain asks this in their place): true when one of them matched. */
	static boolean selectionFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("selectedline")){
			//現在選択中のテキストを含む行(選択が無ければ空)
			result.theResult = "";
			OField field = TUtil.selectionField();
			if(field!=null && field.fld!=null){
				int start = field.fld.getSelectionStart();
				int end = field.fld.getSelectionEnd();
				if(end>start){
					String text = field.getText();
					int firstLine = 1;
					int lastLine = 1;
					for(int i=0; i<text.length() && i<end; i++){
						if(text.charAt(i)=='\n'){
							if(i<start) firstLine++;
							if(i<end-1) lastLine++;
						}
					}
					//the line it starts on, however far it goes (HC J-2.3: select line 2 to 3 of cd fld 1 gives "line 2 of card field 1")
					result.theResult = "line "+firstLine+" of "+TUtil.fieldNumberRef(field);
				}
				else if(field==HZ.clickField){
					//挿入ポイントだけ(select after line 2 of …)でもその行(HyperCard J-2.3: "line 2 of card field 4")
					String text = field.getText();
					int line = 1;
					for(int i=0; i<text.length() && i<start; i++) if(text.charAt(i)=='\n') line++;
					result.theResult = "line "+line+" of "+TUtil.fieldNumberRef(field);
				}
			}
		}
		else if(0==message.compareTo("selectedfield")){
			//現在選択中のテキストのあるフィールド: "card field N" / "bkgnd field N"(番号形式、HyperCard)
			result.theResult = "";
			OField field = TUtil.selectionField();
			//挿入ポイントだけでもそのフィールド(HyperCard J-2.3 / 2.4: select after char 2 of … → "card field 3")
			if(field!=null && field.fld!=null && (field.fld.getSelectionEnd()>field.fld.getSelectionStart() || field==HZ.clickField)){
				result.theResult = TUtil.fieldNumberRef(field);
			}
		}
		else if(0==message.compareTo("selectedloc")){
			//現在選択中のテキストの中央位置(カード座標)
			result.theResult = "";
			//HyperCard: 選択範囲(または挿入ポイント)の左端と下端(カード座標)。挿入ポイントだけでも返す
			OField field = TUtil.selectionField();
			if(field!=null && field.fld!=null && (field.fld.isFocusOwner() || field==HZ.clickField)){
				int start = field.fld.getSelectionStart();
				try{
					Rectangle2D r1 = field.fld.modelToView2D(start);
					double scale = Host.get().scale();
					double left = r1.getX(), top = r1.getY();
					if(field.scrollPane!=null) top -= MyScrollPane.toViewScroll(field.scroll); //where it shows
					if(field.textAlign!=0){
						//the view lays every line out from the left
						double lineLeft = field.fld.modelToView2D(javax.swing.text.Utilities.getRowStart(field.fld, start)).getX();
						double lineRight = field.fld.modelToView2D(javax.swing.text.Utilities.getRowEnd(field.fld, start)).getX();
						double room = field.fld.getWidth()-2*lineLeft-(lineRight-lineLeft);
						left += field.textAlign==1 ? room/2 : room;
					}
					int x = (int)(left/scale) + field.left;
					int y = (int)((top+r1.getHeight())/scale) + field.top;
					if(field.scroll==0 && (field.styleList==null || field.styleList.isEmpty())){
						//the rows as the field draws them, the view's may be taller (selectedLoc is how a script measures a line)
						int margin = OField.textTopMargin(1, field.wideMargins);
						long row = Math.round((r1.getY()/scale-margin)/(r1.getHeight()/scale));
						y = field.top+margin+(int)(row+1)*field.lineHeight();
					}
					result.theResult = x+","+y;
				}catch(Exception e){}
			}
		}
		else return false;
		return true;
	}

	// from TTalk
	static boolean editSelectedText(String cmd) {
		String c = cmd.toLowerCase(Locale.ROOT).replace(" text", "").trim();
		if(!c.equals("cut") && !c.equals("copy") && !c.equals("paste") && !c.equals("clear") && !c.equals("delete")) return false;
		if(PCARD.pc==null || Host.get().paintToolActive() || Host.get().authToolActive()) return false;
		OField field = TUtil.findFocusedOrClickedField();
		final javax.swing.text.JTextComponent tc = field!=null && field.fld!=null ? field.fld :
			UiHost.get().msgExists() && UiHost.get().msgVisible() ? GMsg.area : null;
		if(tc==null) return false;
		boolean locked = field!=null && field.enabled;
		boolean selected = tc.getSelectionEnd()>tc.getSelectionStart();
		if(!selected && !c.equals("paste") || locked && !c.equals("copy")) return false;
		TTalk.runOnEventThread(() -> {
			if(c.equals("cut")) tc.cut();
			else if(c.equals("copy")) tc.copy();
			else if(c.equals("paste")) tc.paste();
			else tc.replaceSelection("");
		});
		if(field!=null && !c.equals("copy") && field.parent instanceof OCardBase) ((OCardBase)field.parent).changed = true;
		return true;
	}

	// from OField (instance)
	static void showFoundBox(OField self, int start, int end) {
		clearFoundBox();
		if(self.fld==null) return;
		try{
			self.foundBoxTag = self.fld.getHighlighter().addHighlight(start, end,
				new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0, 0, 0, 48)));
			self.foundBoxField = self;
			self.view().repaint();
		}catch(javax.swing.text.BadLocationException e){
			self.foundBoxTag = null;
		}
	}

	// from OField
	static void clearFoundBox() {
		if(OField.foundBoxField!=null && OField.foundBoxField.fld!=null && OField.foundBoxTag!=null){
			OField.foundBoxField.fld.getHighlighter().removeHighlight(OField.foundBoxTag);
			OField.foundBoxField.view().repaint();
		}
		OField.foundBoxField = null;
		OField.foundBoxTag = null;
	}

	/** For the fonts' metrics (was OField.METRICS). */
	static final java.awt.Graphics2D METRICS = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB).createGraphics();

	// from TTalk (instance)
	/** Lines of TTalk.keyboardCommand (the core calls this in their place). */
	static void moveCaretInView(TTalk self, String message, String param, MemoryData memData, OField caretField) throws xTalkException {
				javax.swing.text.JTextComponent tc = caretField.fld;
				final String dir = param;
				self.runOnEventThread(() -> { //the caret of a text component moves on the EDT (see insertAtCaret)
					int pos = tc.getCaretPosition();
					int len = tc.getText().length();
					if(dir.equals("left")) pos = Math.max(0, pos-1);
					else if(dir.equals("right")) pos = Math.min(len, pos+1);
					else if(dir.equals("up") || dir.equals("down")){
						try{
							java.awt.Rectangle r = tc.modelToView2D(pos).getBounds();
							int y = dir.equals("up") ? r.y-1 : r.y+r.height+1;
							int p = tc.viewToModel2D(new java.awt.Point(r.x, Math.max(0, y)));
							if(p>=0) pos = p;
						}catch(javax.swing.text.BadLocationException e){}
					}
					tc.setCaretPosition(pos);
				});
	}

	// from OObject
	static String availableFamily(String... candidates){
		String[] families=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		for(String c:candidates)for(String f:families)if(f.equalsIgnoreCase(c))return f;
		return null;
	}

	// from OObject
	static String containedFamily(String name){
		String lower=name.toLowerCase();
		String best=null;
		for(String f:java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()){
			String fl=f.toLowerCase();
			if(fl.length()<4 || (best!=null && fl.length()<=best.length())) continue;
			int i=lower.indexOf(fl);
			if(i<0 || (i>0 && Character.isLetterOrDigit(name.charAt(i-1)))) continue;
			int e=i+fl.length();
			//the word ends there: a space, the end, or a capital starting the style ("FuturaBold")
			if(e<name.length() && Character.isLetterOrDigit(name.charAt(e)) && !Character.isUpperCase(name.charAt(e))) continue;
			best=f;
		}
		return best;
	}
}

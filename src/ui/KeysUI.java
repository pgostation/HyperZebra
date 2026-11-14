import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.*;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;

/** Desktop code moved out of the core's TTalk (tools/wasm-core/moves.txt: type, by hand); the core reaches it through
 * {@link UiHost}. */
final class KeysUI {
	private KeysUI(){}

	// from TTalk
	/** type without a command key: the paint text tool's insertion point, a field's caret or the message box takes the
	 *  letters (TTalk's type command; false: nothing takes them). */
	static boolean typeIntoViews(String text, int[] modKeys, OField fieldWithCaret) throws xTalkException {
		final TypeTool paintText = PCARD.pc!=null && PCARD.pc.tool instanceof TypeTool && ((TypeTool)PCARD.pc.tool).area!=null ?
				(TypeTool)PCARD.pc.tool : null;
			if(paintText!=null){
				//the text tool's insertion point that "click at" put on the card: the letters become paint text, committed when
				//another tool is chosen (Logo 2.1's "text": choose text tool / click at x,y / type y)
				boolean shift = false;
				for(int k : modKeys) if(k==11) shift = true;
				final String typed = shift ? text.toUpperCase() : text;
				TTalk.runOnEventThread(() -> { if(paintText.area!=null) paintText.area.replaceSelection(typed); });
			}
			else if(fieldWithCaret!=null && fieldWithCaret.fld!=null && !fieldWithCaret.enabled){
				javax.swing.text.JTextComponent tc = fieldWithCaret.fld;
				boolean shift = false;
				for(int k : modKeys) if(k==11) shift = true;
				String typed = shift ? text.toUpperCase() : text;
				//a backspace (numToChar(8)) deletes as the Delete key does (HC J-2.3: type "z" then type numToChar(8) leaves the text)
				TTalk.runOnEventThread(() -> { //the field's text component changes on the EDT (see insertAtCaret)
					int from = 0;
					for(int i=0; i<=typed.length(); i++){
						if(i<typed.length() && typed.charAt(i)!='\b') continue;
						if(i>from){
							int at = tc.getSelectionStart();
							tc.replaceSelection(typed.substring(from, i));
							//テキスト更新でキャレットが戻ることがあるので、入力した直後へ置き直す
							int after = Math.min(at+i-from, tc.getText().length());
							tc.setCaretPosition(after);
						}
						if(i<typed.length()) TTalk.deleteBeforeCaret(fieldWithCaret);
						from = i+1;
					}
				});
				fieldWithCaret.setSelectedLine(0);
				((OCardBase)fieldWithCaret.parent).changed = true;
			}else if(GMsg.msg!=null && GMsg.msg.isVisible()){
				//no field open: the message box takes the letters, over the text a put left selected (HC J-2.3: put empty / type "play …",
				//HyperPiece++ Sound ON / OFF)
				boolean shift = false;
				for(int k : modKeys) if(k==11) shift = true;
				final String typed = shift ? text.toUpperCase() : text;
				TTalk.runOnEventThread(() -> GMsg.msg.typeText(typed));
			}
		else return false;
		return true;
	}

	// from TTalk
	/** type with modifier keys other than command alone: the OS keyboard types them (Robot). */
	static void typeWithKeyboard(String text, int[] modKeys) {
				Robot rb = null;
				try {
					rb = new Robot();
				} catch (AWTException e) {
					e.printStackTrace();
				}
				if(rb!=null){
					boolean ctrl=false, shift=false, alt=false, cmd=false;
					for(int k : modKeys){ if(k==13) ctrl=true; if(k==11) shift=true; if(k==12) alt=true; if(k==14) cmd=true; }
					if(ctrl) rb.keyPress(KeyEvent.VK_CONTROL);
					if(shift) rb.keyPress(KeyEvent.VK_SHIFT);
					if(alt) rb.keyPress(KeyEvent.VK_ALT);
					if(cmd) rb.keyPress(KeyEvent.VK_META);
					if(modKeys.length>0) rb.delay(200);
					for(int i=0; i<text.length(); i++){
						char c = text.charAt(i);
						int code = KeyEvent.getExtendedKeyCodeForChar(c);
						if(code==KeyEvent.VK_UNDEFINED) continue;
						boolean upper = Character.isUpperCase(c);
						if(upper && !shift) rb.keyPress(KeyEvent.VK_SHIFT);
						rb.keyPress(code);
						rb.delay(40);
						rb.keyRelease(code);
						if(upper && !shift) rb.keyRelease(KeyEvent.VK_SHIFT);
					}
					if(ctrl) rb.keyRelease(KeyEvent.VK_CONTROL);
					if(shift) rb.keyRelease(KeyEvent.VK_SHIFT);
					if(alt) rb.keyRelease(KeyEvent.VK_ALT);
					if(cmd) rb.keyRelease(KeyEvent.VK_META);
				}
	}
}

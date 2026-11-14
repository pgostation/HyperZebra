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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;

/** Desktop code moved out of the core's TTalk unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class PointerUI {
	private PointerUI(){}

	// from TTalk (instance)
	/** Branches of TTalk.CommandExec8 (the chain asks this in their place): true when one of them matched. */
	static boolean clickAndDrag(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
		if(str=="click"){
			int next=1;
			if(next<stringList.size()&&stringList.get(next).equalsIgnoreCase("at"))
			{
				self.resetFindResume(); //a click makes the next find start at the top (HC J-2.3 probe F Unyo Find2)
				next++;
				if(next>=stringList.size()) throw new xTalkException("Missing coordinates for click at");
				int[] stop = new int[1];
				String param = self.evalPointList(stringList, typeAry, next, "with", stop, memData, object, target);
				next = stop[0];
				int[] at = self.commandPoint(param, "Cannot interpret the coordinates for click at", memData, object, target);
				int x = at[0], y = at[1];
				
				//with shiftKey,optionKey,commandKey : クリック中だけそのキーが押されている状態にする
				int[] modKeys = self.clickModifierKeys(stringList, typeAry, next, memData, object, target);
				if(!Host.get().paintToolActive() && !Host.get().authToolActive()){
					//browse tool
					OObject obj = null;
					for(int k : modKeys) GUI.key[k] = 1;
					try{
					//under lock screen show/hide/move wait for the unlock (runOnEDTWhenShown): bring the parts up to date first, so
					//a part the script has just shown takes the click (BIKKY流's pseudo menu bar: lock screen, show the popup, click at it)
					if(HZ.lockedScreen) Host.get().runOnEDT(() -> {});
					//the parts are laid out in window pixels (display zoom)
					int px = Host.get().toPhysical(x), py = Host.get().toPhysical(y);
					boolean absorbed = false;
					for(int i=0; i<PCARD.pc.mainPane.getComponentCount(); i++)
					{
						Component c = PCARD.pc.mainPane.getComponent(i);
						//a disabled button takes the click and sends nothing: the card behind it gets no mouseUp either
						//(HC 2.4 in the VM: IsRunning/PadList's dimmed Help button over a card whose mouseUp goes prev)
						if(c.isVisible() && !c.isEnabled() && px>=c.getX()&&py>=c.getY()&&px<c.getX()+c.getWidth()&&py<c.getY()+c.getHeight()
								&& self.disabledButtonOf(c)){ absorbed = true; break; }
						if(c.isVisible() &&
								px>=c.getX()&&py>=c.getY()&&px<c.getX()+c.getWidth()&&py<c.getY()+c.getHeight() && //containsを使うと透明ボタンで反応しない
								c.isEnabled()){
							if(c.getClass()==MyPopup.class) obj = ((MyPopup)c).btnData;
							if(c.getClass()==MyPopupNameLabel.class) obj = ((MyPopupNameLabel)c).btnData;
							if(c.getClass()==MyCheck.class) obj = ((MyCheck)c).btnData;
							if(c.getClass()==MyRadio.class) obj = ((MyRadio)c).btnData;
							if(c.getClass()==RoundButton.class) obj = ((RoundButton)c).btnData;
							if(c.getClass()==RectButton.class) obj = ((RectButton)c).btnData;
							if(c.getClass()==RoundedCornerButton.class) obj = ((RoundedCornerButton)c).btnData;
							if(c.getClass()==MyButton.class) obj = ((MyButton)c).btnData;
							if(c.getClass()==MyTextArea.class) obj = ((MyTextArea)c).fldData;
							if(c instanceof MyScrollPane) obj = ((MyScrollPane)c).fldData;
							//シェイプは描いている部分だけがクリックを受ける(外れたら下の部品へ)
							if(c instanceof OShape.ShapeView && ((OShape.ShapeView)c).shape.hitsCard(x, y)) obj = ((OShape.ShapeView)c).shape;
							//if(c.getClass()==MyLabel.class) obj = ((MyLabel)c).cd;
							
							if(obj!=null){
								GUI.clickH = x;
								GUI.clickV = y;
								if(obj instanceof OField && self.scrollBarClick((OField)obj, x, y)) break;
								GUI.mouseClicked = true; GUI.userClickPending = false;
								//click at a button: the clickChunk/clickText no longer name the last field click (HC J-2.3
								//probe D Click BgField; a user's click on the card during a handler keeps them)
								if(!(obj instanceof OField)) GUI.clickTextField = null;
								if(obj instanceof OField && ((OField)obj).fld!=null){
									//the clickChunk / clickText / clickLine もこのクリックを指す(フィールドの描画位置で)
									OField cf = (OField)obj;
									java.awt.Point view = cf.scrollPane!=null ? cf.scrollPane.getViewport().getViewPosition() : new java.awt.Point(0,0);
									GUI.noteFieldClick(cf, Host.get().toPhysical(x-cf.left)+view.x, Host.get().toPhysical(y-cf.top)+view.y);
									GUI.clickByScript = true;
									//a locked list field (autoSelect) hilites the clicked line, below the text its last line (HC J-2.3
									//probes D Click List / A List Click: click at selects, select empty keeps it)
									if(cf.enabled && cf.autoSelect && cf.dontWrap){
										String text = cf.getText();
										int lines = OField.textLineCount(text);
										int[] hit = OField.hitTestClick(cf, Host.get().toPhysical(x-cf.left)+view.x, Host.get().toPhysical(y-cf.top)+view.y);
										if(lines>0 && hit[0]>=0){
											int line = 1;
											for(int k=0; k<hit[0] && k<text.length(); k++) if(text.charAt(k)=='\n') line++;
											cf.setSelectedLine(Math.min(lines, Math.max(1, line)));
										}
									}
								}
								if(obj instanceof OField && !((OField)obj).enabled && ((OField)obj).fld!=null){
									//ロックされていないフィールド: メッセージではなく、その位置に挿入ポイントを置く
									OField f = (OField)obj;
									javax.swing.text.JTextComponent tc = f.fld;
									java.awt.Point local = new java.awt.Point(Host.get().toPhysical(x-f.left), Host.get().toPhysical(y-f.top));
									self.runOnEventThread(() -> { //on the EDT (see insertAtCaret)
										int pos = tc.viewToModel2D(local);
										if(pos<0) pos = tc.getText().length();
										tc.requestFocusInWindow();
										tc.setCaretPosition(Math.min(pos, tc.getText().length()));
									});
									HZ.clickField = f;
									self.openFieldNow(f, true);
									Host.get().runOnEDT(() -> GField.fillToClickedLine(f, local.y));
								}else if(obj instanceof OButton && ((OButton)obj).popup!=null && !SwingUtilities.isEventDispatchThread()){
									//a popup button pulls its menu down (HyperCard tracks it): mouseUp only when an item is chosen
									self.talk.ReceiveMessage("mouseDown", "", obj, null, false);
									if(self.choosePopupItem((OButton)obj)) self.talk.ReceiveMessage("mouseUp", "", obj, null, false);
								}else{
									//a button or shape closes the open field; a locked field or a button without autoHilite keeps it open
									if(!(obj instanceof OField) && !(obj instanceof OButton && !((OButton)obj).getAutoHilite())) self.closeOpenField(true, true);
									//HyperCardは mouseDown, mouseStillDown, mouseUp の順に送る
									self.talk.ReceiveMessage("mouseDown", "", obj, null, false);
									self.talk.ReceiveMessage("mouseStillDown", "", obj, null, false);
									if(obj instanceof OButton) self.clickHilite((OButton)obj);
									self.talk.ReceiveMessage("mouseUp", "", obj, null, false);
								}
								break;
							}
						}
					}
					if(obj==null&&!absorbed&&x>=0&&y>=0&&x<HZ.stack().width&&y<HZ.stack().height){
						GUI.clickH = x;
						GUI.clickV = y;
						GUI.mouseClicked = true; GUI.userClickPending = false;
						GUI.clickTextField = null; //click at the card: the clickChunk is empty (HC J-2.3 probe D Click BgField)
						self.closeOpenField(true, true);
						self.talk.ReceiveMessage("mouseDown", "", HZ.stack().curCard, null, false);
						self.talk.ReceiveMessage("mouseStillDown", "", HZ.stack().curCard, null, false);
						self.talk.ReceiveMessage("mouseUp", "", HZ.stack().curCard, null, false);
					}
					}finally{
						for(int k : modKeys) GUI.key[k] = 0;
					}
				}
				else{
					//auth or paint tool
					if(x<0||x>PCARD.pc.getWidth()||y<0||y>PCARD.pc.getHeight()){
						result.theResult = "Error: out of card window";
						return true;
					}
					if(self.paintToolDrag(x, y, x, y, modKeys)) return true;
					//button / field tool: the click selects the part under the point (HyperCard; 夏休み's install button does
					//choose button tool / click at the loc of button 3 / doMenu コピーボタン). Was a real mouse click via Robot
					if(AuthTool.tool instanceof ButtonTool || AuthTool.tool instanceof FieldTool){
						boolean buttons = AuthTool.tool instanceof ButtonTool;
						OObject hit = self.authToolPartAt(x, y, buttons);
						if(buttons){
							ButtonGUI.gui.tgtOBtn = (OButton)hit;
							ButtonGUI.gui.target = hit==null ? null : ((OButton)hit).getComponent();
						}else{
							FieldGUI.gui.tgtOFld = (OField)hit;
							FieldGUI.gui.target = hit==null ? null : ((OField)hit).getComponent();
						}
						if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
						return true;
					}
					int j;
					for(j=2; j<stringList.size(); j++){
						if(stringList.get(j).equalsIgnoreCase("with")){
							break;
						}
					}
					String withKey = self.modifierKeyWords(self.clickModifierKeys(stringList, typeAry, j, memData, object, target));
					Robot rb = null;
					try {
						rb = new Robot();
					} catch (AWTException e) {
						e.printStackTrace();
					}
					java.awt.Point sp = self.cardToScreen(x, y);
					x = sp.x; y = sp.y;
					if(rb!=null){
						/*BufferedImage bi = new BufferedImage(16,16, BufferedImage.TYPE_INT_ARGB);
						Toolkit kit = Toolkit.getDefaultToolkit();
						Cursor cr = kit.createCustomCursor(bi, new Point(0, 0), "none-cursor");
						PCARD.pc.setCursor(cr);*/
						
						if(withKey.contains("control")) rb.keyPress(KeyEvent.VK_CONTROL);
						if(withKey.contains("shift")) rb.keyPress(KeyEvent.VK_SHIFT);
						if(withKey.contains("option") || withKey.contains("alt")) rb.keyPress(KeyEvent.VK_ALT);
						if(withKey.contains("command")||withKey.contains("cmd")) rb.keyPress(KeyEvent.VK_META);
						if(withKey.length()>0) rb.delay(100);
						{
					        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
							int savex = pointerInfo.getLocation().x;
							int savey = pointerInfo.getLocation().y;
							rb.mouseMove(x, y);
							rb.delay(10);
							rb.mousePress(InputEvent.BUTTON1_DOWN_MASK);
							rb.delay(20);
							rb.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
							rb.delay(20);
							rb.mouseMove(savex, savey);
						}
						if(withKey.contains("control")) rb.keyRelease(KeyEvent.VK_CONTROL);
						if(withKey.contains("shift")) rb.keyRelease(KeyEvent.VK_SHIFT);
						if(withKey.contains("option") || withKey.contains("alt")) rb.keyRelease(KeyEvent.VK_ALT);
						if(withKey.contains("command")||withKey.contains("cmd")) rb.keyRelease(KeyEvent.VK_META);
						
						/*PCARD.pc.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));*/
					}
				}
			}
			else throw new xTalkException("Cannot understand click");
		}
		else if(str=="drag"){
			int next=1;
			int sx=0, sy=0;
			int ex=0, ey=0;
			if(next<stringList.size()&&stringList.get(next).equalsIgnoreCase("from"))
			{
				next++;
				if(next>=stringList.size()) throw new xTalkException("Missing coordinates for drag from");
				int[] stop = new int[1];
				String param = self.evalPointList(stringList, typeAry, next, "to", stop, memData, object, target);
				next = stop[0];
				int[] at = self.commandPoint(param, "Cannot interpret the coordinates for drag from", memData, object, target);
				sx = at[0];
				sy = at[1];
			}
			else throw new xTalkException("Missing from");

			if(next<stringList.size()&&stringList.get(next).equalsIgnoreCase("to"))
			{
				next++;
				if(next>=stringList.size()) throw new xTalkException("Missing coordinates for drag to");
				int[] stop = new int[1];
				String param = self.evalPointList(stringList, typeAry, next, "with", stop, memData, object, target);
				next = stop[0];
				int[] at = self.commandPoint(param, "Cannot interpret the coordinates for drag to", memData, object, target);
				ex = at[0];
				ey = at[1];
			}
			else throw new xTalkException("Missing to");
			//a paint tool takes the drag itself, before the next line runs (らくがきカレンダー: choose tool 4, drag over the
			//picture, doMenu "Copy Picture"). The OS pointer is not moved: a Robot drag lands wherever the pointer's window is
			int[] dragKeys = self.clickModifierKeys(stringList, typeAry, next, memData, object, target);
			if(self.paintToolDrag(sx, sy, ex, ey, dragKeys)){
				//with the option key the select tool closes in on the picture as the lasso does (エンボス加工: only the shapes are copied)
				for(int key : dragKeys) if(key==12 && Host.get().paintToolActive() && PCARD.pc.tool.getClass()==SelectTool.class){
					self.selectToolWentLasso = PCARD.pc.tool;
					self.runOnEventThread(() -> { if(PaintTool.floating()!=null) PaintTool.tightenSelection(); });
				}
				return true;
			}
			if(self.authToolDrag(sx, sy, ex, ey, dragKeys, memData)) return true;
			{
				int j;
				for(j=next; j<stringList.size(); j++){
					if(stringList.get(j).equalsIgnoreCase("with")){
						break;
					}
				}
				String withKey = self.modifierKeyWords(self.clickModifierKeys(stringList, typeAry, j, memData, object, target));
				Robot rb = null;
				try {
					rb = new Robot();
				} catch (AWTException e) {
					e.printStackTrace();
				}
				//カード座標→スクリーン座標(mainPaneの位置と拡大率を考慮)
				java.awt.Point p0 = self.cardToScreen(sx, sy), p1 = self.cardToScreen(ex, ey);
				sx = p0.x; sy = p0.y; ex = p1.x; ey = p1.y;
				if(rb!=null){
					/*BufferedImage bi = new BufferedImage(16,16, BufferedImage.TYPE_INT_ARGB);
					Toolkit kit = Toolkit.getDefaultToolkit();
					Cursor cr = kit.createCustomCursor(bi, new Point(0, 0), "none-cursor");
					PCARD.pc.setCursor(cr);*/
					
					if(withKey.contains("control")) rb.keyPress(KeyEvent.VK_CONTROL);
					if(withKey.contains("shift")) rb.keyPress(KeyEvent.VK_SHIFT);
					if(withKey.contains("option") || withKey.contains("alt")) rb.keyPress(KeyEvent.VK_ALT);
					if(withKey.contains("command")||withKey.contains("cmd")) rb.keyPress(KeyEvent.VK_META);
					if(withKey.length()>0) rb.delay(100);
					{
				        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
						int savex = pointerInfo.getLocation().x;
						int savey = pointerInfo.getLocation().y;
						rb.mouseMove(sx, sy);
						rb.delay(10);
						rb.mousePress(InputEvent.BUTTON1_DOWN_MASK);
						rb.delay(20);
						if(self.dragspeed>0){
							double start = new Date().getTime();
							double d = Math.sqrt((ex-sx)*(ex-sx)+(ey-sy)*(ey-sy));
							while(true){
								double sec = (new Date().getTime()-start)/10.0*3/5/60;
								double per = sec*self.dragspeed/(d+0.4);
								if(per>=1) break;
								rb.mouseMove((int)(sx*(1-per)+ex*per), (int)(sy*(1-per)+ey*per));

								try {
									Platform.get().sleep(10);
								} catch (InterruptedException e) {
								}
							}
						}
						rb.mouseMove(ex, ey);
						rb.delay(20);
						rb.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
						rb.delay(20);
						rb.mouseMove(savex, savey);
					}
					if(withKey.contains("control")) rb.keyRelease(KeyEvent.VK_CONTROL);
					if(withKey.contains("shift")) rb.keyRelease(KeyEvent.VK_SHIFT);
					if(withKey.contains("option") || withKey.contains("alt")) rb.keyRelease(KeyEvent.VK_ALT);
					if(withKey.contains("command")||withKey.contains("cmd")) rb.keyRelease(KeyEvent.VK_META);
					
					/*PCARD.pc.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));*/
				}
			}
		}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction2 (the chain asks this in their place): true when one of them matched. */
	static boolean inputFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("mouseclick")){
			//外部ウィンドウ(ムービー等)へのクリックもハンドラ実行中はキューに残り、the mouseClick が取る(Myst Preview の PlayLogo)
			String v = GUI.mouseClicked || GUI.takeHeldPress() || Xcmd68k.hasSessions() && Xcmd68k.takeWindowClick() ||
				TTalk.takeQueuedClick() ?"true":"false";
			GUI.mouseClicked = false;//一度読んだらfalseに戻る
			result.theResult = v;
		}
		else if(0==message.compareTo("mouse")){
			result.theResult = GUI.takeMouseState()?"down":"up";
		}
		else if(0==message.compareTo("mouseh")){
	        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
	        result.theResult = Integer.toString(Host.get().toLogical(pointerInfo.getLocation().x-TUtil.getCardOriginOnScreen().x));
		}
		else if(0==message.compareTo("mousev")){
	        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
	        result.theResult = Integer.toString(Host.get().toLogical(pointerInfo.getLocation().y-TUtil.getCardOriginOnScreen().y));
		}
		else if(0==message.compareTo("mouseloc")){
			//カードの左上起点のマウス座標
	        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
	        result.theResult = Integer.toString(Host.get().toLogical(pointerInfo.getLocation().x-TUtil.getCardOriginOnScreen().x))+","+
			Integer.toString(Host.get().toLogical(pointerInfo.getLocation().y-TUtil.getCardOriginOnScreen().y));
		}
		else if(0==message.compareTo("clickh")){
			result.theResult = Integer.toString(GUI.clickH);
		}
		else if(0==message.compareTo("clickv")){
			result.theResult = Integer.toString(GUI.clickV);
		}
		else if(0==message.compareTo("clickloc")){
			//カードの左上起点のクリック座標
			result.theResult = Integer.toString(GUI.clickH)+","+Integer.toString(GUI.clickV);
		}
		else if(0==message.compareTo("clickline")){
			//最後にクリックしたフィールドの行: "line 2 of card field 1"(名前があっても番号の形、HyperCard J-2.3 / 2.4)
			OField field = GUI.clickTextField;
			if(field!=null && field.fld!=null && !field.outlineStyle && !field.tableStyle) result.theResult = TUtil.clickedLineRef(field);
			else result.theResult = GUI.clickLine;
		}
		else if(0==message.compareTo("clickchunk")){
			//最後にクリックした語(Groupスタイルなら連続するグループ全体)を指すチャンク式。
			//"char 5 to 7 of card field 1"(名前があっても番号の形、HyperCard J-2.3 / 2.4)
			result.theResult = "";
			OField field = GUI.clickTextField;
			if(field!=null && field.outlineStyle){
				//アウトライン: クリックした項目の行(字下げしたテキストのとき)
				if(field.clickNode!=null && field.clickNode.line>0) result.theResult = "line "+field.clickNode.line+" of "+field.getShortName();
			}
			else if(field!=null && field.tableStyle){
				//表: クリックしたセル
				if(field.clickRow>0 && field.clickColumn>0) result.theResult = "item "+field.clickColumn+" of line "+field.clickRow+" of "+field.getShortName();
			}
			else if(field!=null && field.fld!=null && !GUI.clickOnOtherCard()){
				try{
					int[] r = TUtil.clickedTextRange(field);
					if(r!=null) result.theResult = "char "+(r[0]+1)+" to "+r[1]+" of "+TUtil.fieldNumberRef(field);
				}catch(Exception e){}
			}
		}
		else if(0==message.compareTo("clicktext")){
			//最後にクリックした語(Groupスタイルなら連続するグループ全体)
			result.theResult = "";
			OField field = GUI.clickTextField;
			if(field!=null && field.outlineStyle){
				//アウトライン: クリックした項目の見出し(JSONなら値)
				if(field.clickNode!=null) result.theResult = field.clickNode.text();
			}
			else if(field!=null && field.tableStyle){
				if(field.clickRow>0 && field.clickColumn>0){
					java.util.List<String[]> rows = FieldTableData.parseRows(field.getText());
					if(field.clickRow<=rows.size()){
						String[] cells = rows.get(field.clickRow-1);
						result.theResult = field.clickColumn<=cells.length ? cells[field.clickColumn-1] : "";
					}
				}
			}
			else if(field!=null && GUI.clickOnOtherCard()){
				if(GUI.clickTextAtClick!=null) result.theResult = GUI.clickTextAtClick;
			}
			else if(field!=null && field.fld!=null){
				try{
					int[] r = TUtil.clickedTextRange(field);
					if(r!=null && r.length==2) result.theResult = field.fld.getText().substring(r[0], r[1]);
				}catch(Exception e){}
			}
		}
		else if(0==message.compareTo("commandkey")||0==message.compareTo("cmdkey")){
			GUI.noteKeyPoll();
			if(GUI.key[14]>=1) result.theResult = "down"; else result.theResult = "up";
		}
		else if(0==message.compareTo("optionkey")){
			GUI.noteKeyPoll();
			if(GUI.key[12]>=1) result.theResult = "down"; else result.theResult = "up";
		}
		else if(0==message.compareTo("shiftkey")){
			GUI.noteKeyPoll();
			if(GUI.key[11]>=1) result.theResult = "down"; else result.theResult = "up";
		}
		else if(0==message.compareTo("controlkey")){
			GUI.noteKeyPoll();
			if(GUI.key[13]>=1) result.theResult = "down"; else result.theResult = "up";
		}
		else return false;
		return true;
	}

	// from TTalk
	/** Lines of TTalk.returnToIdle (the core calls this in their place). */
	static void pointerAtIdle() {
		if(!GUI.mouseDown) TTalk.resetIdleCursor();
		else TTalk.cursorHeldByPress = true; //GUI.mouseUped puts the browse cursor back
		if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.releaseHeldCursor(); //a script's choose tool shows its cursor now
		HZ.stack().mouseWithinCheck();

		ScriptDebugger.reset();
	}
}

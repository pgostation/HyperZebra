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

public class TUtil {
	static TUtil tutil = new TUtil();

	//classic Mac OSがQuickDraw内蔵で提供していた標準カーソル（1=Ibeam, 2=cross, 3=plus, 4=watch）。
	//スタック自身のリソースフォークには含まれない（Toolboxが直接持っていたため）ので、
	//現行OSのカーソルに読み替える。
	private static final HashMap<Integer,Integer> CLASSIC_MAC_CURSOR_IDS = new HashMap<Integer,Integer>();
	static {
		CLASSIC_MAC_CURSOR_IDS.put(0, Cursor.DEFAULT_CURSOR);   //arrow
		CLASSIC_MAC_CURSOR_IDS.put(1, Cursor.TEXT_CURSOR);      //iBeam
		CLASSIC_MAC_CURSOR_IDS.put(2, Cursor.CROSSHAIR_CURSOR); //cross
		CLASSIC_MAC_CURSOR_IDS.put(3, Cursor.CROSSHAIR_CURSOR); //plus(表計算用の十字)
		CLASSIC_MAC_CURSOR_IDS.put(4, Cursor.WAIT_CURSOR);      //watch
	}

	//set the cursor のカスタムカーソルは、同じ画像(Rsrc の共有キャッシュの BufferedImage)とホットスポットなら同じ Cursor を返す。
	//毎回 createCustomCursor すると別物の Cursor になり、macOS では設定のたびに NSCursor の作成と設定がメインスレッドへ
	//積まれる。勇者の石は歩くループで毎周 set the cursor to (mouseLoc is within …) (Usha Items の CURS "true"/"false") を
	//実行し、毎秒1万回積まれてレインボーカーソルになっていた
	//(Cursor が画像を持つので弱参照の表にはできない。BufferedImage は同一性で比べるので、古いものから捨てる小さな表にする)
	private static final java.util.LinkedHashMap<BufferedImage,Object[]> customCursors = new java.util.LinkedHashMap<>(16, 0.75f, true){
		@Override protected boolean removeEldestEntry(java.util.Map.Entry<BufferedImage,Object[]> e){ return size()>64; }
	};
	private static Cursor noneCursor;

	static synchronized Cursor customCursor(BufferedImage bi, Point hotSpot){
		Object[] c = customCursors.get(bi);
		if(c!=null && hotSpot.equals(c[0])) return (Cursor)c[1];
		//a CURS hot spot may lie outside its 16x16 image (JHUG Image of HCPower: set cursor in mouseEnter); the host refuses
		//one outside the image (IndexOutOfBoundsException "invalid hotSpot"), so it is moved to the nearest pixel
		Point at = new Point(Math.max(0, Math.min(bi.getWidth()-1, hotSpot.x)), Math.max(0, Math.min(bi.getHeight()-1, hotSpot.y)));
		Cursor cr;
		try{ cr = Toolkit.getDefaultToolkit().createCustomCursor(bi, at, "none-cursor"); }
		catch(IndexOutOfBoundsException e){ cr = Toolkit.getDefaultToolkit().createCustomCursor(bi, new Point(0, 0), "none-cursor"); }
		customCursors.put(bi, new Object[]{new Point(hotSpot), cr});
		return cr;
	}

	/**
	 * A HyperCard built-in cursor by its CURS ID (hand 128, ibeam 1, cross 2, plus 3, watch 4): the stack's own CURS of that ID
	 * when it has one (PUGO_GT's arrow-shaped "Hand" 128 and "wait" 4 show in the real HyperCard for the browse tool and
	 * set cursor to watch), otherwise the host cursor {@code predefined}.
	 */
	static Cursor hyperCardCursor(int id, int predefined){
		OStack stack = PCARD.pc==null ? null : PCARD.pc.stack;
		Object[] c = stack==null || stack.rsrc==null ? null : stack.rsrc.stackCursor(id);
		if(c==null) return Cursor.getPredefinedCursor(predefined);
		return customCursor((BufferedImage)c[0], (Point)c[1]);
	}

	/** The browse tool's cursor (CURS 128 of the stack, else the host hand). */
	static Cursor browseCursor(){ return hyperCardCursor(128, Cursor.HAND_CURSOR); }

	private static final HashMap<String,String> compatibilityProperties = new HashMap<String,String>();
	/** reset printing: the print properties back to HyperCard's defaults (大福帳 resets them before it prints). */
	static void resetPrinting(){
		compatibilityProperties.put("printmargins", "0,0,0,0"); //HyperCardの既定値
		compatibilityProperties.put("printtextalign", "left");
		compatibilityProperties.put("printtextfont", "Geneva");
		compatibilityProperties.put("printtextheight", "16");
		compatibilityProperties.put("printtextsize", "10"); //HyperCardの既定値
		compatibilityProperties.put("printtextstyle", "plain");
	}
	static {
		//the address: zone:computer:program, HyperCard's network address for program-to-program messages (HC 2.2 measured in the VM:
		//"*:HyperZebra:HyperCard" with the computer name of the VM's Sharing Setup; Rinaldi's PPCList demo reads its defaults from it)
		compatibilityProperties.put("address", "*:"+Toolbox68k.COMPUTER_NAME+":HyperCard");
		compatibilityProperties.put("blindtyping", "false");
		compatibilityProperties.put("brush", "8"); //HyperCard の既定のブラシ(丸)
		compatibilityProperties.put("centered", "false");
		compatibilityProperties.put("checkmark", "✓");
		compatibilityProperties.put("cmdchar", "⌘");
		compatibilityProperties.put("commandchar", "⌘");
		compatibilityProperties.put("cursor", "hand");
		compatibilityProperties.put("debugger", "false");
		compatibilityProperties.put("dialingtime", "30");
		compatibilityProperties.put("dialingvolume", "3");
		compatibilityProperties.put("environment", "development");
		compatibilityProperties.put("longwindowtitles", "false");
		compatibilityProperties.put("lockrecent", "false");
		compatibilityProperties.put("markchar", "✓");
		compatibilityProperties.put("menumessage", "");
		compatibilityProperties.put("menumsg", "");
		compatibilityProperties.put("multiple", "false");
		resetPrinting();
		compatibilityProperties.put("reporttemplates", "");
		compatibilityProperties.put("scripteditor", "false");
		compatibilityProperties.put("scriptinglanguage", "HyperTalk");
		compatibilityProperties.put("soundchannel", "1");
		compatibilityProperties.put("textalign", "left");
		compatibilityProperties.put("textheight", "16"); //HyperCard の既定(ペイントの文字の行の高さ)
		compatibilityProperties.put("textarrows", "false"); //HyperCardの既定値
		compatibilityProperties.put("tracedelay", "0");
		compatibilityProperties.put("usermodify", "false"); //HyperCardの既定値
	}

	/** the traceDelay等、値を保持するだけの互換プロパティの現在値 */
	static String compatibilityProperty(String name) {
		String value = compatibilityProperties.get(name.toLowerCase(java.util.Locale.ROOT));
		return value==null ? "" : value;
	}

	/** The ticks: the same count as an external's TickCount (Toolbox68k.tickCount, 16_666_667 ns a tick from the same origin). */
	static long currentTicks() {
		return System.nanoTime() / 16_666_667L;
	}

	private static Point getCardOriginOnScreen() {
		if(PCARD.pc.mainPane==null){ //テスト(カードウインドウなし)
			return new Point(PCARD.pc.getX()+PCARD.pc.getInsets().left, PCARD.pc.getY()+PCARD.pc.getInsets().top);
		}
		if(PCARD.pc.mainPane.isShowing()){
			//from the frame's own bounds: the native window's place (getLocationOnScreen) lags a setBounds just made
			//(set the rect of this card moves the window and the script reads the card window's rect right after)
			Point p = javax.swing.SwingUtilities.convertPoint(PCARD.pc.mainPane, 0, 0, PCARD.pc);
			return new Point(PCARD.pc.getX()+p.x, PCARD.pc.getY()+p.y);
		}
		Point origin = windowOrigin();
		return new Point(origin.x+PCARD.pc.mainPane.getX(), origin.y+PCARD.pc.mainPane.getY());
	}

	/**
	 * The card window's rect in global (screen) coordinates, as HyperCard gives it: the part of the card the window shows,
	 * without the title bar and (Windows/Linux) the in-window menu bar. It used to be the OWindow's own left/top/width/height,
	 * which nothing sets, so "the rect of the card window" was 0,0,0,0 and a stack that sets the window from it (Slot Machine:
	 * set the rect of the card window to item 1 of r, item 2 of r, item 1 of r + 336, item 4 of r) shrank it to its title bar.
	 */
	static java.awt.Rectangle cardWindowRect() {
		Point origin = getCardOriginOnScreen();
		if(PCARD.pc.mainPane!=null){
			//a window smaller than the card shows it from its scroll: the window starts that far into the card
			Point s = PCARD.pc.cardScroll();
			origin = new Point(origin.x+PCARD.toPhysical(s.x), origin.y+PCARD.toPhysical(s.y));
		}
		int w, h;
		if(PCARD.pc.mainPane!=null){
			java.awt.Rectangle visible = PCARD.pc.mainPane.getVisibleRect();
			w = visible.width; h = visible.height;
			if(w<=0 || h<=0 || !PCARD.pc.mainPane.isShowing()){
				w = PCARD.pc.stack!=null ? PCARD.toPhysical(PCARD.pc.stack.width) : PCARD.pc.mainPane.getWidth();
				h = PCARD.pc.stack!=null ? PCARD.toPhysical(PCARD.pc.stack.height) : PCARD.pc.mainPane.getHeight();
			}
		}else{
			w = PCARD.pc.getWidth(); h = PCARD.pc.getHeight();
		}
		return new java.awt.Rectangle(origin.x, origin.y, w, h);
	}

	/** Moves/sizes the card window so that the card part it shows is {@code r} (global coordinates); the frame keeps its
	 *  title bar, menu bar and borders around it. */
	static void setCardWindowRect(java.awt.Rectangle r) {
		java.awt.Rectangle now = cardWindowRect();
		java.awt.Rectangle frame = PCARD.pc.getBounds();
		int chromeW = frame.width-now.width, chromeH = frame.height-now.height;
		int w = Math.max(1, r.width), h = Math.max(1, r.height);
		OStack st = PCARD.pc.stack;
		if(st!=null && (w!=now.width || h!=now.height)){
			//HyperCard 2.4: the card window shows at most the card, and its width is a multiple of 16 (200 -> 208, 193 -> 192).
			//That size stays the window's own: a later bigger card is shown in it (OStack.windowNominal)
			int lw = PCARD.toLogical(w), lh = PCARD.toLogical(h);
			lw = lw>=st.width ? st.width : Math.max(16, Math.min(st.width, (lw+8)/16*16));
			lh = Math.max(1, Math.min(lh, st.height));
			st.windowNominal = new java.awt.Dimension(lw, lh);
			w = PCARD.toPhysical(lw);
			h = PCARD.toPhysical(lh);
		}
		//laid out at once: the script reads the rect (and sets the scroll within it) right after
		final java.awt.Rectangle b = new java.awt.Rectangle(r.x-(now.x-frame.x), r.y-(now.y-frame.y), w+chromeW, h+chromeH);
		PCARD.runOnEDT(() -> { PCARD.pc.setBounds(b); PCARD.pc.validate(); });
	}

	/** Geometry properties of the card window (left, top, right, bottom, width, height, rect, loc/topLeft, bottomRight), or null.
	 *  The loc of a window is its top-left corner (HyperCard). */
	static String cardWindowProperty(String prpty) {
		java.awt.Rectangle r = externalGlobal(cardWindowRect());
		switch(prpty){
			case "rect": case "rectangle": return r.x+","+r.y+","+(r.x+r.width)+","+(r.y+r.height);
			case "left": return Integer.toString(r.x);
			case "top": return Integer.toString(r.y);
			case "right": return Integer.toString(r.x+r.width);
			case "bottom": return Integer.toString(r.y+r.height);
			case "width": return Integer.toString(r.width);
			case "height": return Integer.toString(r.height);
			case "loc": case "location": case "topleft": return r.x+","+r.y;
			case "bottomright": case "botright": return (r.x+r.width)+","+(r.y+r.height);
			default: return null;
		}
	}

	/** Sets a geometry property of the card window; false when the property is not one of them. */
	static boolean setCardWindowProperty(String prpty, String value) throws xTalkException {
		if(cardWindowProperty(prpty)==null) return false; //zoomed や visible などは従来どおり(値を数値として読まない)
		String[] items = value.split(",");
		int[] v = new int[items.length];
		try{
			for(int i=0; i<items.length; i++) v[i] = (int)Double.parseDouble(items[i].trim());
		}catch(NumberFormatException e){
			throw new xTalkException("A number is required here");
		}
		ExternalFrame frame = currentFrame(); //the externals' classic screen while one is up (scriptsFrame)
		java.awt.Rectangle r = frame!=null ? frame.toGuest().apply(cardWindowRect()) : toLogicalGlobal(cardWindowRect());
		switch(prpty){
			case "rect": case "rectangle":
				if(v.length!=4) throw new xTalkException("A rectangle is required here");
				r = new java.awt.Rectangle(v[0], v[1], v[2]-v[0], v[3]-v[1]); break;
			case "loc": case "location": case "topleft":
				if(v.length!=2) throw new xTalkException("Coordinates are required here");
				r.setLocation(v[0], v[1]); break;
			case "bottomright": case "botright":
				if(v.length!=2) throw new xTalkException("Coordinates are required here");
				r.setSize(v[0]-r.x, v[1]-r.y); break;
			case "left": case "top": case "right": case "bottom": case "width": case "height":
				if(v.length!=1) throw new xTalkException("A number is required here");
				if(prpty.equals("left")) r.x = v[0];
				else if(prpty.equals("top")) r.y = v[0];
				else if(prpty.equals("right")) r.x = v[0]-r.width;
				else if(prpty.equals("bottom")) r.y = v[0]-r.height;
				else if(prpty.equals("width")) r.width = v[0];
				else r.height = v[0];
				break;
			default: return false;
		}
		setCardWindowRect(frame!=null ? frame.hostRect(r) : toPhysicalGlobal(r));
		return true;
	}

	/**
	 * The screen an external sees while it evaluates an expression (EvalExpr), when that is not the display: a 68K external's VM
	 * on a small card window gets a classic screen round it (ExternalScreenArea), so `the rect of the card window` and
	 * `the screenRect` must be in that screen's coordinates - Apple's PlotPICT clipped to them (nothing drawn), DragRect took them
	 * as its limit and MonitorConfig("CardWindow") found the card on no monitor. {@code toGuest} maps a display rectangle to the
	 * external's global coordinates; null = the scripts' own coordinates.
	 */
	record ExternalFrame(java.awt.Rectangle screen, java.util.function.UnaryOperator<java.awt.Rectangle> toGuest,
			java.util.function.UnaryOperator<java.awt.Rectangle> toHost) {
		ExternalFrame(java.awt.Rectangle screen, java.util.function.UnaryOperator<java.awt.Rectangle> toGuest){ this(screen, toGuest, null); }
		/** The display rectangle showing a rectangle of the external's global coordinates: toHost, or toGuest undone (it is a
		 *  shift and a scale away from the display's coordinates) */
		java.awt.Rectangle hostRect(java.awt.Rectangle guest) {
			if(toHost!=null) return toHost.apply(guest);
			final int far = 100000; //far off any window, where toGuest is the plain shift and scale
			java.awt.Rectangle g = toGuest.apply(new java.awt.Rectangle(-far, -far, far, far));
			double scale = g.width>0 ? g.width/(double)far : 1;
			int x = (int)Math.round(-far+(guest.x-g.x)/scale), y = (int)Math.round(-far+(guest.y-g.y)/scale);
			return new java.awt.Rectangle(x, y, (int)Math.round(guest.width/scale), (int)Math.round(guest.height/scale));
		}
	}
	static final ThreadLocal<ExternalFrame> externalFrame = new ThreadLocal<ExternalFrame>();
	/**
	 * The classic screen the 68K externals see, for the scripts too while one is up (null otherwise; set by the VM side, see
	 * ExternalScreenArea): HyperCard has one coordinate system, so a card-window rect an external hands back (Process Monitor's
	 * resizeHCWindow, resizeWindow XFCN: "64,106,576,504" for set the rect of card window to it) and one a script hands an
	 * external (Semi-Modal Window Demo: Window "…",top+50,… from the rect of card window: "off the screen") must agree. Stacks
	 * that run no external on a small card window never get one, so they keep the display's coordinates.
	 */
	static volatile java.util.function.Supplier<ExternalFrame> scriptsFrame;
	private static volatile boolean scriptsFrameLoaded;
	/** The frame the asker's global coordinates are in: the external's own during its EvalExpr, else the scripts' (null = the display) */
	static ExternalFrame currentFrame() {
		ExternalFrame frame = externalFrame.get();
		if(frame!=null) return frame;
		java.util.function.Supplier<ExternalFrame> shared = scriptsFrame;
		if(shared==null && !scriptsFrameLoaded){
			//the VM side sets it when its class loads, which was at the first external's VM: Semi-Modal Window Demo's first click
			//(before any external ran) got display coordinates
			scriptsFrameLoaded = true;
			try{ Class.forName("ExternalScreenArea"); }catch(Throwable e){}
			shared = scriptsFrame;
		}
		if(shared==null) return null;
		try{ return shared.get(); }catch(RuntimeException e){ return null; }
	}
	/** A display rectangle in the global coordinates of whoever asks: the external's screen during its EvalExpr, else the scripts'. */
	static java.awt.Rectangle externalGlobal(java.awt.Rectangle r) {
		ExternalFrame frame = currentFrame();
		return frame!=null ? frame.toGuest().apply(r) : toLogicalGlobal(r);
	}

	/** A screen rectangle in the global coordinates scripts see: at a zoom the screen is the smaller Mac screen it magnifies
	 *  (the screenRect, the card window's rect), so a script that sets the card window to its card size and centres it in the
	 *  screenRect (Myst Preview: width 544, loc (item 3 of the screenRect - 544) div 2) gets the whole card, centred. */
	static java.awt.Rectangle toLogicalGlobal(java.awt.Rectangle r) {
		int left = PCARD.toLogical(r.x), top = PCARD.toLogical(r.y);
		return new java.awt.Rectangle(left, top, PCARD.toLogical(r.x+r.width)-left, PCARD.toLogical(r.y+r.height)-top);
	}
	/** The screen pixels of a rectangle in the scripts' global coordinates ({@link #toLogicalGlobal}). */
	static java.awt.Rectangle toPhysicalGlobal(java.awt.Rectangle r) {
		int left = PCARD.toPhysical(r.x), top = PCARD.toPhysical(r.y);
		return new java.awt.Rectangle(left, top, PCARD.toPhysical(r.x+r.width)-left, PCARD.toPhysical(r.y+r.height)-top);
	}

	/** set the loc/topLeft of window "Message" (ぺいんと+ puts its palettes back) or of another of HyperZebra's windows: its top
	 *  left at (h, v) of the card window, the message box as "set the loc of msg" places it */
	static void placeDialogWindow(java.awt.Window w, int h, int v) {
		Point o = w==GMsg.msg ? getCardOriginOnScreen() : windowOrigin();
		w.setLocation(o.x+PCARD.toPhysical(h), o.y+PCARD.toPhysical(v));
	}

	/** set loc of the tool window が位置を決めた直後の show では既定の位置へ戻さない */
	static boolean toolWindowPlaced;

	/** Screen point of the card window's content (below the title bar and, on Windows/Linux, the in-window
	 *  menu bar): the origin of the card-window coordinates picture/movie/external windows are placed in. */
	/** The usable area (without menu bar and Dock) of the display the card window is on, null when it is not shown */
	static java.awt.Rectangle cardWindowScreenArea() {
		if(PCARD.pc==null || !PCARD.pc.isShowing()) return null;
		java.awt.GraphicsConfiguration gc = PCARD.pc.getGraphicsConfiguration();
		if(gc==null) return null;
		java.awt.Rectangle b = gc.getBounds();
		java.awt.Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		return new java.awt.Rectangle(b.x+in.left, b.y+in.top, b.width-in.left-in.right, b.height-in.top-in.bottom);
	}

	/** A picture window's globalRect as the host can show it: macOS keeps windows below its menu bar (a window set at the top
	 *  of the screen is pushed down whole), so one reaching under it starts below it and keeps its bottom. GG REMIX hides the
	 *  menu bar and puts a black window from the screen top to the card window's top; pushed down, it covered the card. */
	static java.awt.Rectangle belowHostMenuBar(java.awt.Rectangle r) {
		if(!GMenu.isMacOSX()) return r;
		for(java.awt.GraphicsDevice gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
			java.awt.GraphicsConfiguration gc = gd.getDefaultConfiguration();
			java.awt.Rectangle b = gc.getBounds();
			if(r.x+r.width<=b.x || r.x>=b.x+b.width || r.y+r.height<=b.y || r.y>=b.y+b.height) continue;
			int top = b.y+Toolkit.getDefaultToolkit().getScreenInsets(gc).top;
			if(r.y<top && r.y+r.height>top) return new java.awt.Rectangle(r.x, top, r.width, r.y+r.height-top);
		}
		return r;
	}

	static Point windowOrigin() {
		return new Point(PCARD.pc.getX()+PCARD.pc.getInsets().left, PCARD.pc.getY()+PCARD.pc.chromeTop());
	}

	/** スタックを移動したときの初期化: userModify は別のスタックへ行くと false に戻る(HyperCard) */
	static void resetStackProperties() {
		compatibilityProperties.put("usermodify", "false");
	}

	static boolean propertyBoolean(String name, String value) throws xTalkException {
		if(value.equalsIgnoreCase("true")) return true;
		if(value.equalsIgnoreCase("false")) return false;
		throw new xTalkException("{0} requires true or false", name);
	}

	private static int propertyInteger(String name, String value) throws xTalkException {
		try{
			return (int)Double.parseDouble(value);
		}catch(NumberFormatException err){
			throw new xTalkException("Specify a number for {0}", name);
		}
	}

	private static String textAlignName(int align) {
		if(align==1) return "center";
		if(align==2) return "right";
		return "left";
	}

	private static int textAlignValue(String value) throws xTalkException {
		value = value.trim(); //HyperCard J-2.3 takes " center" and "right " (Practical XFCNs' Draw Tree: item 4 of "Geneva, 9, 9, center")
		if(value.equalsIgnoreCase("left")) return 0;
		if(value.equalsIgnoreCase("center")) return 1;
		if(value.equalsIgnoreCase("right")) return 2;
		throw new xTalkException("textAlign requires left, center or right");
	}

	/** The textFont of the paint text: HyperCard's own font until a script sets one (HC J-2.3 answers Osaka, also after
	 *  set textFont to empty), Geneva on a Roman system. */
	static String paintTextFont() {
		String font = PCARD.pc.textFont;
		if(font!=null && !font.isEmpty()) return font;
		OStack st = PCARD.pc.stack;
		return st!=null && st.legacyTextEncoding==StackTextEncoding.JAPANESE ? "Osaka" : "Geneva";
	}

	static String textStyleName(int style) {
		if(style==0) return "plain";
		StringBuilder result = new StringBuilder();
		String[] names = {"bold","italic","underline","outline","shadow","condense","extend","group"};
		for(int i=0; i<names.length; i++){
			if((style & (1<<i))!=0){
				if(result.length()>0) result.append(',');
				result.append(names[i]);
			}
		}
		return result.toString();
	}

	static int textStyleValue(String value) {
		if(value.equalsIgnoreCase("plain")) return 0;
		int style = 0;
		String lower = value.toLowerCase();
		if(lower.contains("bold")) style |= 1;
		if(lower.contains("italic")) style |= 2;
		if(lower.contains("underline")) style |= 4;
		if(lower.contains("outline")) style |= 8;
		if(lower.contains("shadow")) style |= 16;
		if(lower.contains("condense")) style |= 32;
		if(lower.contains("extend")) style |= 64;
		if(lower.contains("group")) style |= 128;
		return style;
	}

	//フォーカスのあるフィールドを探す。無ければ最後にクリックしたフィールドを返す
	//クリック位置の語の範囲[start,end)。クリックした文字がGroupスタイルなら、
	//空白をまたいでもGroupスタイルが続く範囲全体(HyperCardのclickText/clickChunk)
	static final int GROUP_STYLE = 128;
	/** The clicked word of the last field click as {start, end} (end exclusive); a click on a space outside a Group run gives that space as {pos, pos+1, 1}
	 *  ("char 8 to 8", empty clickText), null only when nothing was hit. A click to the
	 *  right of a line's last character (also below the text) gives that line's return, or the place after the last
	 *  character, as {pos, pos+1, 1}: HyperCard J-2.3 / 2.4 answer "char 12 to 12 of card field 1" and an empty clickText. */
	static int[] clickedTextRange(OField field) throws Exception {
		String text = field.fld.getText();
		int[] hit = OField.hitTestClick(field, GUI.clickFieldLocalX, GUI.clickFieldLocalY);
		int pos = hit[0];
		if(pos<0 || pos>=text.length()) return null;
		if(text.charAt(pos)=='\n') return new int[]{pos, pos+1, 1}; //空行
		if(hit[1]!=0 && (pos+1==text.length() || text.charAt(pos+1)=='\n')) return new int[]{pos+1, pos+2, 1};
		//Groupスタイルの空白はグループの一部、そうでない空白はその1文字で clickText は空(HyperCard J-2.3、probe F Click Space)。
		//以前は空白で clickChunk が空になり、京大カードの mouseDown の get the textStyle of chunkClicked がエラーになっていた
		boolean group = (field.textStyleAt(pos).style & GROUP_STYLE)!=0;
		if(!group && Character.isWhitespace(text.charAt(pos))) return new int[]{pos, pos+1, 1};
		int start=pos, end=pos+1;
		if(group){
			while(start>0 && text.charAt(start-1)!='\n' && (field.textStyleAt(start-1).style & GROUP_STYLE)!=0) start--;
			while(end<text.length() && text.charAt(end)!='\n' && (field.textStyleAt(end).style & GROUP_STYLE)!=0) end++;
		}else{
			//日本語のスタックでは1バイト文字と2バイト文字の境目でも語が切れる(HyperCard J の word と同じ)
			boolean jp = PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.legacyTextEncoding==StackTextEncoding.JAPANESE;
			boolean wide = TTalk.isDoubleByteChar(text.charAt(pos));
			while(start>0 && !Character.isWhitespace(text.charAt(start-1)) && !(jp && TTalk.isDoubleByteChar(text.charAt(start-1))!=wide)) start--;
			while(end<text.length() && !Character.isWhitespace(text.charAt(end)) && !(jp && TTalk.isDoubleByteChar(text.charAt(end))!=wide)) end++;
		}
		return new int[]{start, end};
	}

	/** the clickLine of a plain field: the line of the clicked character; below the text HyperCard J-2.3 / 2.4 go on counting
	 *  lines of the field's line height ("line 4" under two lines), a list field (autoSelect) stays on its last line. */
	static String clickedLineRef(OField field){
		String text = field.fld.getText();
		int[] hit = OField.hitTestClick(field, GUI.clickFieldLocalX, GUI.clickFieldLocalY);
		int line = 1;
		if(hit[0]>=0){
			for(int i=0; i<hit[0] && i<text.length(); i++) if(text.charAt(i)=='\n') line++;
			if(!field.autoSelect || GUI.clickByScript) line += hit[2]; //click at below a list: "line 4" (HC J-2.3 probe D Click List)
		}else{
			//まだ描かれていない(当たり判定の箱が無い)フィールド: 行の高さで数える
			int lineHeight = field.lineHeight();
			line = Math.max(1, (PCARD.toLogical(GUI.clickFieldLocalY) - OField.textTopMargin(1, field.wideMargins))/lineHeight + 1);
			int lineCount = 1;
			for(int i=0; i<text.length(); i++) if(text.charAt(i)=='\n') lineCount++;
			if(field.autoSelect && line>lineCount) line = lineCount;
		}
		return "line "+line+" of "+fieldNumberRef(field);
	}

	/** the selectedLine of <field>: "line N [to M] of card field N", empty without a selection. A list field (autoSelect)
	 *  always gives the range form, "line 3 to 3 of card field 2" for one line (HyperCard J-2.3 / 2.4). */
	static String fieldSelectedLineRef(OField f){
		if(f.tableStyle || f.outlineStyle) return f.tableSelectedLine(fieldNumberRef(f));
		if(f.getSelectedLine()>0) return "line "+f.getSelectedLine()+(f.autoSelect ? " to "+f.getSelectedLine() : "")+" of "+fieldNumberRef(f);
		if(f.fld!=null && f.fld.getSelectionEnd()>f.fld.getSelectionStart()){
			String text = f.getText();
			int s0 = f.fld.getSelectionStart(), e0 = f.fld.getSelectionEnd();
			int first=1, last=1;
			for(int i=0; i<text.length() && i<e0; i++){ if(text.charAt(i)=='\n'){ if(i<s0) first++; if(i<e0-1) last++; } }
			return first==last && !f.autoSelect ? "line "+first+" of "+fieldNumberRef(f) : "line "+first+" to "+last+" of "+fieldNumberRef(f);
		}
		return "";
	}

	/** "card field N" / "bkgnd field N"(HyperCardのselectedField等の形式) */
	/** 表の選択行を"2,5,7"で */
	static String tableRowList(OField field){
		StringBuilder sb = new StringBuilder();
		for(int row : field.selectedRows){ if(sb.length()>0) sb.append(','); sb.append(row); }
		return sb.toString();
	}

	/** "3" / "2,5,7" / "2 to 4" / "line 2 to 4 of card field 1" / empty → 行番号(1..rowCount) */
	static int[] parseRowList(String value, int rowCount) throws xTalkException {
		java.util.TreeSet<Integer> rows = new java.util.TreeSet<Integer>();
		if(value==null) return new int[0];
		String v = value.trim().toLowerCase();
		int of = v.indexOf(" of ");
		if(v.startsWith("line ")) v = v.substring(5, of>0 ? of : v.length()).trim();
		if(!v.isEmpty()){
			for(String part : v.split(",")){
				String p = part.trim();
				if(p.isEmpty()) continue;
				String[] range = p.split("\\s+to\\s+");
				try{
					int a = Integer.parseInt(range[0].trim());
					int b = range.length>1 ? Integer.parseInt(range[1].trim()) : a;
					for(int r=Math.min(a,b); r<=Math.max(a,b); r++) if(r>=1 && r<=rowCount) rows.add(r);
				}catch(NumberFormatException e){
					throw new xTalkException("Specify line numbers like \"3\", \"2,5\" or \"2 to 4\"");
				}
			}
		}
		int[] result = new int[rows.size()];
		int i = 0;
		for(int r : rows) result[i++] = r;
		return result;
	}

	/** HyperCard copies the part's rectangle of the card into the window when it is shown or hidden on the card on show and
	 *  the screen is not locked - also when it was visible already: a 68K external that hooked that copy (ColorizeHC) draws
	 *  its pictures there. G'S LAIR adds the monsters' pictures and then shows their buttons, which its saved battle card
	 *  has visible: the first battle stayed black. */
	private static boolean partRedrawnByExternal(OObject part){
		PCARD pc = PCARD.pc;
		if(PCARD.lockedScreen || pc==null || pc.stack==null || pc.stack.curCard==null || pc.mainPane==null) return false;
		OCard cd = pc.stack.curCard;
		if(part.parent!=cd && part.parent!=cd.bg) return false;
		return Xcmd68k.cardDrawingHooked(pc.stack);
	}

	/** Shows or hides a part ({@code change}) as HyperCard does: when an external draws the card window on HyperCard's copy
	 *  (see {@link #partRedrawnByExternal}) the window goes from the old picture straight to what the external draws on the
	 *  copy. Swing painted the part in between (its repaint) with the external's old drawing over it: G'S LAIR's opaque white
	 *  monster button flashed white at every show of the blinking after a hit. */
	private static void showOrHidePart(OObject part, Runnable change){
		if(!partRedrawnByExternal(part)){
			change.run();
			//the copy puts the card back over what an external drew in that rectangle (UxMaruPeke's ○×)
			if(!PCARD.lockedScreen && PCARD.pc!=null && PCARD.pc.stack!=null && (part.parent==PCARD.pc.stack.curCard || PCARD.pc.stack.curCard!=null && part.parent==PCARD.pc.stack.curCard.bg))
				CardWindow68k.partCopied(part.top, part.left, part.top+part.height, part.left+part.width);
			return;
		}
		CardWindow68k.holdScreen();
		try{
			change.run();
			Xcmd68k.redrawHookedCards(PCARD.pc.stack, part.top, part.left, part.top+part.height, part.left+part.width);
		}finally{ CardWindow68k.releaseScreen(); }
	}

	/** Properties whose change HyperCard draws by copying the part's rectangle of its card into the window (see
	 *  {@link #partChangedOnScreen}); "visible" has its own copy (showOrHidePart). */
	private static final java.util.Set<String> REDRAWN_PART_PROPERTIES = java.util.Set.of(
		"text", "hilite", "highlight", "highlite", "hilight", "name", "icon", "textstyle", "textfont", "textsize", "textalign",
		"textheight", "style", "rect", "rectangle", "loc", "location", "left", "top", "right", "bottom", "width", "height",
		"topleft", "bottomright", "showname", "enabled", "color", "backcolor", "hilitecolor", "scroll", "showlines", "family",
		"widemargins", "locktext", "autohilite");
	/** When a hooked card was last redrawn for a part change (System.nanoTime), the parts' rectangle still to redraw, its stack. */
	private static long partRedrawnAt;
	private static java.awt.Rectangle pendingPartRedraw;
	private static OStack pendingPartStack;
	private static java.util.concurrent.ScheduledExecutorService partRedrawTimer;
	/** Changes this close together (a repeat loop of puts) are drawn together, once, at the end of the interval. */
	private static final long PART_REDRAW_INTERVAL_MS = 40;

	/**
	 * A part of the card on show changed while the screen is not locked (put into a field, set the hilite of a button…): HyperCard
	 * redraws it in its copy of the card and copies that rectangle into the window with CopyBits, which a 68K external that hooked
	 * the copy colours (ColorizeHC's coloured card kept the old field text and a washed-out button). The first change is drawn at
	 * once; further ones within {@link #PART_REDRAW_INTERVAL_MS} are drawn together after it.
	 */
	static void partChangedOnScreen(OObject part){
		if(part==null || !(part instanceof OButton || part instanceof OField) || !Xcmd68k.hasSessions() || !partRedrawnByExternal(part)) return;
		OStack stack = PCARD.pc.stack;
		java.awt.Rectangle r = new java.awt.Rectangle(part.left, part.top, Math.max(1, part.width), Math.max(1, part.height));
		boolean now;
		synchronized(TUtil.class){
			long t = System.nanoTime();
			now = pendingPartRedraw==null && t-partRedrawnAt >= PART_REDRAW_INTERVAL_MS*1_000_000L;
			if(now) partRedrawnAt = t;
			else{
				boolean schedule = pendingPartRedraw==null;
				pendingPartRedraw = pendingPartRedraw==null || pendingPartStack!=stack ? r : pendingPartRedraw.union(r);
				pendingPartStack = stack;
				if(schedule){
					if(partRedrawTimer==null) partRedrawTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
						Thread th = new Thread(task, "HyperZebra part redraw"); th.setDaemon(true); return th; });
					partRedrawTimer.schedule(TUtil::flushPartRedraw, PART_REDRAW_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
				}
			}
		}
		if(!now) return;
		CardWindow68k.holdScreen();
		try{ Xcmd68k.redrawHookedCards(stack, r.y, r.x, r.y+r.height, r.x+r.width); }
		finally{ CardWindow68k.releaseScreen(); }
	}
	private static void flushPartRedraw(){
		java.awt.Rectangle r; OStack stack;
		synchronized(TUtil.class){ r = pendingPartRedraw; stack = pendingPartStack; pendingPartRedraw = null; pendingPartStack = null; partRedrawnAt = System.nanoTime(); }
		//locked since (unlock screen redraws the whole card), or another stack now
		if(r==null || PCARD.lockedScreen || PCARD.pc==null || PCARD.pc.stack!=stack) return;
		try{ Xcmd68k.redrawHookedCards(stack, r.y, r.x, r.y+r.height, r.x+r.width); }
		catch(RuntimeException e){ System.err.println("[xcmd] the card's redraw failed: "+e); }
	}

	private static StackTextEncoding scriptEncoding(){
		OStack st = PCARD.pc!=null ? PCARD.pc.stack : null;
		return st!=null && st.legacyTextEncoding!=null ? st.legacyTextEncoding : HCData.currentEncoding;
	}

	/** The character the byte 128-255 stands for in the stack's text encoding, or null (a lead byte of a two-byte code). */
	static String legacyChar(int b){
		StackTextEncoding enc = scriptEncoding();
		if(enc==null) return null;
		try{
			String s = enc.decode(new byte[]{(byte)b});
			if(s.length()!=1 || s.charAt(0)=='\uFFFD' || s.charAt(0)=='?') return null;
			return s;
		}catch(RuntimeException e){ return null; }
	}

	/** The character in the stack's text encoding (one or two bytes), or null when it has none. */
	static byte[] legacyBytes(char c){
		StackTextEncoding enc = scriptEncoding();
		if(enc==null) return null;
		try{ return enc.encode(String.valueOf(c)); }
		catch(Exception e){ return null; }
	}

	/** Whether the parentheses outside quoted strings pair up. */
	static boolean parenthesesBalanced(String s){
		int depth = 0;
		boolean quoted = false;
		for(int i=0; i<s.length(); i++){
			char c = s.charAt(i);
			if(c=='"') quoted = !quoted;
			else if(!quoted && c=='(') depth++;
			else if(!quoted && c==')' && --depth<0) return false;
		}
		return depth==0;
	}

	/** A value of max/min/average/sum: empty or blanks count as 0 (HyperCard J-2.3: max(-3, " ") = 0, average(empty, 4) = 2) */
	private static double blankZero(String v){
		return v.trim().isEmpty() ? 0 : Double.valueOf(v);
	}

	static String fieldNumberRef(OField field){
		OCardBase owner = field.parent instanceof OCardBase ? (OCardBase)field.parent : null;
		if(owner==null || owner.fldList==null) return field.getShortName(); //a card not loaded (HTML TagWriter's palette)
		int n = -1;
		for(int i=0; i<owner.fldList.size(); i++){ if(owner.fldList.get(i).id==field.id){ n = i+1; break; } }
		if(n<0) return field.getShortName();
		return (owner.objectType.equals("background") ? "bkgnd field " : "card field ")+n;
	}

	static OField findFocusedOrClickedField(){
		OField found = GUI.clickField;
		//select コマンドやクリックで決まったフィールドを優先する(フォーカスの移動は非同期なので)
		if(found!=null && found.fld!=null) return found;
		for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
			OField field = PCARD.pc.stack.curCard.fldList.get(i);
			JComponent compo = field.getComponent();
			if(compo!=null && compo.isFocusOwner()) found = field;
		}
		for(int i=0; i<PCARD.pc.stack.curCard.bg.fldList.size(); i++){
			OField field = PCARD.pc.stack.curCard.bg.fldList.get(i);
			JComponent compo = field.getComponent();
			if(compo!=null && compo.isFocusOwner()) found = field;
		}
		return found;
	}

	/** A number followed at once by a character that is neither a letter, a digit, a space nor HyperTalk punctuation */
	private static final java.util.regex.Pattern NUMBER_THEN_STRAY_SIGN =
		java.util.regex.Pattern.compile("[0-9][^\\s\\p{L}\\p{N}_.,+\\-*/^&=<>≠≤≥()\"]");

	/** math functions that read their arguments as numbers (TTalk.rawArgsFor) */
	private static final java.util.Set<String> RAW_NUMBER_ARGS = new java.util.HashSet<String>(java.util.Arrays.asList(
		"abs","atan","cos","exp","exp1","exp2","ln","ln1","log2","round","sin","sqrt","tan","trunc","annuity","compound",
		"average","max","min","sum"));

	/** one-number math functions whose empty argument is 0 (HyperCard J-2.3: ln(empty) = -INF, exp(empty) = 1, abs(empty) = 0) */
	private static final java.util.Set<String> EMPTY_IS_ZERO = new java.util.HashSet<String>(java.util.Arrays.asList(
		"abs","atan","cos","exp","exp1","exp2","ln","ln1","log2","random","round","sin","sqrt","tan","trunc"));

	/** trunc/round of SANE's NAN/INF: HyperCard converts them to a 32-bit integer (NAN = 0 in J-2.3), null for other values */
	/** trunc/round give a 32-bit integer: beyond it the value saturates (HC J-2.3 probes D Nan Int / E MathInf, Oct 13 2026:
	 *  trunc(3000000000) = 2147483647, trunc(3000000000.5) too; NAN = -2147483648, as the PowerPC/SANE conversions do) */
	static String int32Saturated(double whole){
		if(whole>2147483647d) return "2147483647";
		if(whole<-2147483648d) return "-2147483648";
		return wholeNumberString(whole);
	}

	static String saneIntegerOf(String param){
		String t = param.trim();
		if(t.matches("(?i)NAN(\\(\\d*\\))?|NaN")) return "-2147483648";
		if(t.matches("(?i)\\+?INF|\\+?Infinity")) return "2147483647";
		if(t.matches("(?i)-INF|-Infinity")) return "-2147483648";
		return null;
	}

	//HyperTalk内蔵関数
	public static Result CallSystemFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc)
		throws xTalkException 
	{
		//a built-in function or property was read: how a caught command writes an object in its params (HyperCard J-2.3)
		if(message!=null) BuiltinCommandMessage.noteProperty(message.toLowerCase(java.util.Locale.ROOT));
		String[] raw = TTalk.rawArgsFor(params);
		if(raw!=null && message!=null && RAW_NUMBER_ARGS.contains(message.toLowerCase(java.util.Locale.ROOT))){
			//math functions take a computed argument at full precision (HyperCard: round(cos(pi/2)*1000000000) = 0)
			params = params.clone();
			for(int k=0; k<params.length && k<raw.length; k++) if(raw[k]!=null) params[k] = raw[k];
		}
		if(params!=null && params.length==1 && params[0]!=null && params[0].trim().isEmpty() && EMPTY_IS_ZERO.contains(message.toLowerCase(java.util.Locale.ROOT))){
			params = new String[]{"0"}; //abs(empty) = 0 (HyperCard J-2.3)
		}
		try{
			return callSystemFunction1(message, params, target, memData, isFunc);
		}catch(NumberFormatException e){
			//空の引数は 0(HyperCard J-2.3: ln(empty) = -INF, exp(empty) = 1, abs(empty) = 0。Periodic Table の Convert)
			boolean blank = false;
			String[] zeroed = params==null ? null : params.clone();
			if(zeroed!=null) for(int i=0; i<zeroed.length; i++) if(zeroed[i]!=null && zeroed[i].trim().isEmpty()){ zeroed[i] = "0"; blank = true; }
			if(blank){
				try{ return callSystemFunction1(message, zeroed, target, memData, isFunc); }
				catch(NumberFormatException e2){ /* below */ }
			}
			//exp("abc"): 数値でない引数はスクリプトのエラー(以前は Java の例外がハンドラを抜けていた)
			throw new xTalkException("{0} requires a number", message);
		}
	}

	private static Result callSystemFunction1(String message, String[] params, OObject target, MemoryData memData, boolean isFunc)
		throws xTalkException 
	{
		Result result = new Result();
		result.ret = 1;//見つからなければ1
		
		message = message.toLowerCase();
		if(message.startsWith("abbr ")) message = "abbrev "+message.substring(5);
		else if(message.startsWith("abbreviated ")) message = "abbrev "+message.substring(12);
		//the [long|short|abbrev] English date/time: 常に英語(米国)形式。日付関数はもともと英語形式なので同じもの
		if(message.startsWith("english ")) message = message.substring(8);
		else if(message.startsWith("long english ")) message = "long "+message.substring(13);
		else if(message.startsWith("short english ")) message = "short "+message.substring(14);
		else if(message.startsWith("abbrev english ")) message = "abbrev "+message.substring(15);
		if(message.equals("jsonvalue")) {
			if(params==null || params.length!=2) throw new xTalkException("JSONValue requires two arguments: a JSON string and a key");
			result.theResult=TalkJson.value(params[0], params[1]);
			result.ret=0;
			return result;
		}
		//localizedText(text, value1, …): the stack's own translation into HyperZebra's language (StackLanguages), {1}… replaced
		if(message.equals("localizedtext")) {
			if(params==null || params.length<1) throw new xTalkException("localizedText requires a text and optional values for {1}, {2}…");
			OStack st = null;
			for(OObject o = target; o!=null && st==null; o = o.ownerObject()){
				if(o instanceof OStack) st = (OStack)o;
				else if(o instanceof OCardBase) st = ((OCardBase)o).stack;
			}
			if(st==null && PCARD.pc!=null) st = PCARD.pc.stack;
			result.theResult=StackLanguages.localizedText(st, params[0], java.util.Arrays.copyOfRange(params, 1, params.length));
			result.ret=0;
			return result;
		}
		//正規表現(LiveCodeと同じ名前と引数)。結果を受け取る変数付きの呼び出しはTTalkが扱う(TTalk.regexCall)
		if(message.equals("matchtext") || message.equals("matchchunk")) {
			if(params==null || params.length!=2) throw new xTalkException(message.equals("matchtext")
				? "matchText requires a string, a regular expression and optional result variables" : "matchChunk requires a string, a regular expression and optional position variables");
			String[] found=message.equals("matchtext") ? TalkRegex.matchText(params[0], params[1]) : TalkRegex.matchChunk(params[0], params[1]);
			result.theResult=Boolean.toString(found!=null);
			result.ret=0;
			return result;
		}
		if(message.equals("replacetext")) {
			if(params==null || params.length!=3) throw new xTalkException("replaceText requires three arguments: a string, a regular expression and replacement text");
			result.theResult=TalkRegex.replaceText(params[0], params[1], params[2]);
			result.ret=0;
			return result;
		}
		
		if(0==message.compareTo("trunc")){
			if(params.length<1) throw new xTalkException("An argument is required");
			//SANE's infinities and NaN become a 32-bit integer (HyperCard J-2.3 probes, Oct 13 2026: trunc(0/0) = -2147483648,
			//trunc(1/0) = 2147483647, trunc(-1/0) = -2147483648)
			String special = saneIntegerOf(params[0]);
			if(special!=null){ result.theResult = special; result.ret = 0; return result; }
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			//0方向への切り捨て。long(2^63)を超える値も壊さないよう、doubleのまま整形する
			result.theResult = int32Saturated(v<0 ? Math.ceil(v) : Math.floor(v));
			result.ret = 0;
		}
		else if(0==message.compareTo("round")){
			if(params.length<1) throw new xTalkException("An argument is required");
			//SANE's infinities and NaN become a 32-bit integer (HyperCard J-2.3 probes, Oct 13 2026: trunc(0/0) = -2147483648,
			//trunc(1/0) = 2147483647, trunc(-1/0) = -2147483648)
			String special = saneIntegerOf(params[0]);
			if(special!=null){ result.theResult = special; result.ret = 0; return result; }
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			result.theResult = int32Saturated(Math.rint(v));
			result.ret = 0;
		}
		else if(0==message.compareTo("abs")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			if(v-(int)v == 0){
				result.theResult = Integer.toString((int)(v>0?v:-v));
			}else{
				result.theResult = Double.toString(v>0?v:-v);
			}
			result.ret = 0;
		}
		else if(0==message.compareTo("min")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			String[] vAry = params;
			if(params[0].contains(",")) vAry = params[0].split(",");
			try{v=blankZero(vAry[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			for(int i=1; i<vAry.length; i++){
				double v2;
				try{v2=blankZero(vAry[i]);}
				catch(Exception e) { 
					if(params[0].equals("∞")) v2 = Double.MAX_VALUE;
					else throw new xTalkException("A number is required here");
				}
				if(v2 < v) v = v2;
			}
			if(v-(int)v == 0){
				result.theResult = Integer.toString((int)v);
			}else{
				result.theResult = Double.toString(v);
			}
			result.ret = 0;
		}
		else if(0==message.compareTo("max")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			String[] vAry = params;
			if(params[0].contains(",")) vAry = params[0].split(",");
			try{v=blankZero(vAry[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			for(int i=1; i<vAry.length; i++){
				double v2;
				try{v2=blankZero(vAry[i]);}
				catch(Exception e) { 
					if(params[0].equals("∞")) v2 = Double.MAX_VALUE;
					else throw new xTalkException("A number is required here");
				}
				if(v2 > v) v = v2;
			}
			if(v-(int)v == 0){
				result.theResult = Integer.toString((int)v);
			}else{
				result.theResult = Double.toString(v);
			}
			result.ret = 0;
		}
		else if(0==message.compareTo("average")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			String[] vAry = params;
			if(params[0].contains(",")) vAry = params[0].split(",");
			try{v=blankZero(vAry[0]);}
			catch(Exception e) { throw new xTalkException("A number is required here");}
			for(int i=1; i<vAry.length; i++){
				double v2;
				try{v2=blankZero(vAry[i]);}
				catch(Exception e) { throw new xTalkException("A number is required here");}
				v += v2;
			}
			v /= vAry.length;
			if(v-(int)v == 0){
				result.theResult = Integer.toString((int)v);
			}else{
				result.theResult = Double.toString(v);
			}
			result.ret = 0;
		}
		else if(0==message.compareTo("sum")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			String[] vAry = params;
			if(params[0].contains(",")) vAry = params[0].split(",");
			try{v=blankZero(vAry[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			for(int i=1; i<vAry.length; i++){
				double v2;
				try{v2=blankZero(vAry[i]);}
				catch(Exception e) { 
					if(params[0].equals("∞")) v2 = Double.MAX_VALUE;
					else throw new xTalkException("A number is required here");
				}
				v += v2;
			}
			if(v-(int)v == 0){
				result.theResult = Integer.toString((int)v);
			}else{
				result.theResult = Double.toString(v);
			}
			result.ret = 0;
		}
		else if(0==message.compareTo("random")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			//random(n): 1〜n(nは切り捨てて整数)の一様乱数。
			//(以前は (int)(v*random+0.999999) で 0 や n+1 が出ることがあった)
			long n = (long)Math.floor(v);
			if(n<1) result.theResult = "1"; //HyperCard J-2.3/2.4: random(0), random(-3), random(empty) = 1
			else result.theResult = Long.toString(1+(long)(Math.random()*n));
			result.ret = 0;
		}
		else if(0==message.compareTo("sqrt")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			result.theResult = Double.toString((Math.sqrt(v)));
			result.ret = 0;
		}
		else if(0==message.compareTo("sin")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			result.theResult = Double.toString((Math.sin(v)));
			result.ret = 0;
		}
		else if(0==message.compareTo("cos")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			result.theResult = Double.toString((Math.cos(v)));
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("tan")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			result.theResult = Double.toString((Math.tan(v)));
			result.ret = 0;
		}
		else if(0==message.compareTo("atan")){
			if(params.length<1) throw new xTalkException("An argument is required");
			double v;
			try{v=Double.valueOf(params[0]);}
			catch(Exception e) { 
				if(params[0].equals("∞")) v = Double.MAX_VALUE;
				else throw new xTalkException("A number is required here");
			}
			result.theResult = Double.toString((Math.atan(v)));
			result.ret = 0;
		}
		else if(0==message.compareTo("chartonum")){
			if(params.length<1) throw new xTalkException("An argument is required");
			//実機のHyperCardは複数文字なら最初の文字、空なら0を返す。
			//リファレンスの「1文字」は構文上の説明で、エラーにはしない
			char c = 0;
			if(params[0].length()>0) c = params[0].charAt(0);
			//HyperZebraは改行を内部では\nで持つが、HyperCardのreturnはCR(13)。
			//lineFeed定数は内部で\rにしてあるので、こちらはLF(10)として報告する
			if(c=='\n') c = '\r';
			else if(c=='\r') c = '\n';
			int code = c;
			//HyperCard は文字をスタックの文字コードのバイトで数える(HyperCard J-2.3: charToNum("ｱ") = 177、
			//charToNum("あ") = 130 = Shift-JIS の1バイト目)。その文字コードで表せない文字は Unicode の番号のまま
			if(c>=128){
				byte[] b = legacyBytes(c);
				if(b!=null && b.length>0) code = b[0]&0xFF;
			}
			result.theResult = Integer.toString(code);
			result.ret = 0;
		}
		else if(0==message.compareTo("number") && params!=null && params.length==1){
			//number(cards) / number(bgs)の関数形: the number of Xと同じ
			result.theResult = TTalk.evaluateExternalExpression("the number of "+params[0], target);
			result.ret = 0;
		}
		else if(0==message.compareTo("length")){
			if(params.length<1) throw new xTalkException("An argument is required");
			result.theResult = Integer.toString(TTalk.textLength(params[0]));
			result.ret = 0;
		}
		else if(0==message.compareTo("value")){
			if(params.length<1) throw new xTalkException("An argument is required");
			//再評価はハンドラの文脈(me / the target)で行う
			try{
				result.theResult = TTalk.evaluateValueText(params[0], memData, ScriptDebugger.currentObject(), target);
			}catch(xTalkException e){
				//HyperCard J-2.3: text that does not parse as an expression is its own value: value("9+-") = "9+-",
				//value("2+3 4") = "2+3 4", value("put 1") = "put 1" (HyperPiece 2 の電卓が "=" で「9+-=9+-」と表示する)。
				//エラーになるのは括弧の対応が取れないもの(value("(1+2") → ")" が必要)と、数でない値の計算(value("abc+1"))

				//A number run into a sign HyperTalk has no use for is no expression either: value("-2.5±1.23") = "-2.5±1.23"
				//(HC J-2.3 probe "AR Value d"; Lines&Kinetics re-reads its "slope±error" result lines with value of)
				if((e.isNumericError() && !NUMBER_THEN_STRAY_SIGN.matcher(params[0]).find()) || !parenthesesBalanced(params[0])) throw e;
				result.theResult = params[0];
			}
			result.ret = 0;
		}
		else if(0==message.compareTo("param")){
			//param of i
			if(params.length<1) throw new xTalkException("An argument is required");
			int i = Integer.valueOf(params[0].trim())-1;
			if(i==-1){
				//param(0)はメッセージ名
				result.theResult = memData.message==null ? "" : memData.message;
			}
			else if(memData.params!=null && i>=0 && i<memData.params.length){
				result.theResult = memData.params[i];
			}
			result.ret = 0;
		}
		else if(0==message.compareTo("annuity")){
			//年率計算(年金現価係数)
			if(params.length<2) throw new xTalkException("Two arguments are required");
			double rate = Double.valueOf(params[0]);
			double periods = Double.valueOf(params[1]);
			//double base = 0.0;
			//for(int i=0; i<(int)periods; i++){
				//base += 1.0;
				//base /= (1.0+rate);
			//}
			//base += (periods-(int)periods);
			//base /= 1.0+(rate*(periods-(int)periods));
			
			double base = (1-1/Math.pow((1+rate),periods))/rate;
			result.theResult = Double.toString(base);
			result.ret = 0;
		}
		else if(0==message.compareTo("compound")){
			//
			if(params.length<2) throw new xTalkException("Two arguments are required");
			double rate = Double.valueOf(params[0]);
			double periods = Double.valueOf(params[1]);
			double base = Math.pow((1+rate),periods);
			result.theResult = Double.toString(base);
			result.ret = 0;
		}
		else if(0==message.compareTo("exp")){
			//指数
			if(params.length<1) throw new xTalkException("An argument is required");
			double v = Double.valueOf(params[0]);
			double base = Math.exp(v);
			result.theResult = Double.toString(base);
			result.ret = 0;
		}
		else if(0==message.compareTo("exp1")){
			//指数-1
			if(params.length<1) throw new xTalkException("An argument is required");
			double v = Double.valueOf(params[0]);
			double base = Math.exp(v);
			result.theResult = Double.toString(base-1.0);
			result.ret = 0;
		}
		else if(0==message.compareTo("ln")){
			//自然対数
			if(params.length<1) throw new xTalkException("An argument is required");
			double v = Double.valueOf(params[0]);
			double base = Math.log(v);
			result.theResult = Double.toString(base);
			result.ret = 0;
		}
		else if(0==message.compareTo("ln1")){
			//1足した数の自然対数
			if(params.length<1) throw new xTalkException("An argument is required");
			double v = Double.valueOf(params[0]);
			double base = Math.log(1.0+v);
			result.theResult = Double.toString(base);
			result.ret = 0;
		}
		else if(0==message.compareTo("exp2")){
			//2のx乗
			if(params.length<1) throw new xTalkException("An argument is required");
			double v = Double.valueOf(params[0]);
			double base = Math.pow(2,v);
			result.theResult = Double.toString(base);
			result.ret = 0;
		}
		else if(0==message.compareTo("log2")){
			//2のx乗
			if(params.length<1) throw new xTalkException("An argument is required");
			double v = Double.valueOf(params[0]);
			double base = Math.log(v)/Math.log(2);
			result.theResult = Double.toString(base);
			result.ret = 0;
		}
		else return CallSystemFunction2(message, params, target, memData, isFunc, result);
		
		//ここまで来たら既知の関数/プロパティ。空文字を返す関数(param, clickText等)も
		//「未定義」と区別できるようにretを0にしておく
		result.ret = 0;
		if(result.theResult==null) result.theResult = "";
		return result;
	}

	private static Result CallSystemFunction2(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		if(0==message.compareTo("offset")){
			if(params.length>=2){
				//HyperCardのoffsetは大文字小文字を区別しない
				int off = params[0].isEmpty() ? -1 : params[1].toLowerCase(java.util.Locale.ROOT).indexOf(params[0].toLowerCase(java.util.Locale.ROOT));
				if(off>=0) result.theResult = Integer.toString(off+1);
				else result.theResult = "0";
				result.ret = 0;
			}
		}
		else if(0==message.compareTo("ticks")){
			result.theResult = Long.toString(currentTicks());
		}
		else if(0==message.compareTo("milliseconds")){
			//the milliseconds: a finer clock than the ticks (same origin); 3 decimals = microseconds
			long nanos = System.nanoTime();
			result.theResult = (nanos/1_000_000L) + "." + String.format("%03d", (nanos/1000L)%1000L);
		}
		else if(0==message.compareTo("voices")){
			//the voices (HyperCard 2.3 speech): the voices of the speech engine of this computer (empty when there is none)
			String sp = Speech.programName();
			result.theResult = sp!=null && Sandbox.allowOther(Sandbox.scriptRequester(), Sandbox.RUN_PROGRAM, sp) ? Speech.voices() : "";
		}
		else if(0==message.compareTo("speech")){
			result.theResult = Speech.speechState();
		}
		else if(0==message.compareTo("seconds") || 0==message.compareTo("secs")){
			result.theResult = Long.toString(HCDateTime.toMacSeconds(HCDateTime.now()));
		}
		else if(0==message.compareTo("short version")){
			result.theResult = PCARD.version;
		}
		else if(0==message.compareTo("version")){
			result.theResult = PCARD.version;
		}
		else if(0==message.compareTo("long version")){
			//HyperCard: 8桁の版コード("02308000" for 2.3)。HyperZebra 3.0.0 → "03008000"
			result.theResult = PCARD.hcVersionCode(PCARD.longVersion);
		}
		else if(0==message.compareTo("systemname")){
			result.theResult = System.getProperty("os.name");
		}
		else if(0==message.compareTo("systemversion")){
			// HyperCard asks for the emulated Macintosh System version, not the host JVM/OS version, written as one number - the
			// minor and bug-fix digits after the point (HC J-2.3 on Mac OS 9.2.2: "9.22"), so `the systemVersion < 10` compares
			// numerically (Rinaldi's demos took their Mac OS X branch with "7.5.5"). The System externals see: 7.5.5 → "7.55".
			int v = Toolbox68k.SYSTEM_VERSION;
			result.theResult = Integer.toHexString((v>>8)&0xff)+"."+String.format("%02x", v&0xff);
		}
		else if(0==message.compareTo("javaversion")){
			result.theResult = System.getProperty("java.version");
		}
		else if(0==message.compareTo("javavmversion")){
			result.theResult = System.getProperty("java.vm.version");
		}
		else if(0==message.compareTo("mouseclick")){
			//外部ウィンドウ(ムービー等)へのクリックもハンドラ実行中はキューに残り、the mouseClick が取る(Myst Preview の PlayLogo)
			String v = GUI.mouseClicked || GUI.takeHeldPress() || Xcmd68k.hasSessions() && Xcmd68k.takeWindowClick() ?"true":"false";
			GUI.mouseClicked = false;//一度読んだらfalseに戻る
			result.theResult = v;
		}
		else if(0==message.compareTo("mouse")){
			result.theResult = GUI.takeMouseState()?"down":"up";
		}
		else if(0==message.compareTo("mouseh")){
	        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
	        result.theResult = Integer.toString(PCARD.toLogical(pointerInfo.getLocation().x-getCardOriginOnScreen().x));
		}
		else if(0==message.compareTo("mousev")){
	        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
	        result.theResult = Integer.toString(PCARD.toLogical(pointerInfo.getLocation().y-getCardOriginOnScreen().y));
		}
		else if(0==message.compareTo("mouseloc")){
			//カードの左上起点のマウス座標
	        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
	        result.theResult = Integer.toString(PCARD.toLogical(pointerInfo.getLocation().x-getCardOriginOnScreen().x))+","+
			Integer.toString(PCARD.toLogical(pointerInfo.getLocation().y-getCardOriginOnScreen().y));
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
			if(field!=null && field.fld!=null && !field.outlineStyle && !field.tableStyle) result.theResult = clickedLineRef(field);
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
			else if(field!=null && field.fld!=null){
				try{
					int[] r = clickedTextRange(field);
					if(r!=null) result.theResult = "char "+(r[0]+1)+" to "+r[1]+" of "+fieldNumberRef(field);
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
					java.util.List<String[]> rows = FieldTable.Data.parseRows(field.getText());
					if(field.clickRow<=rows.size()){
						String[] cells = rows.get(field.clickRow-1);
						result.theResult = field.clickColumn<=cells.length ? cells[field.clickColumn-1] : "";
					}
				}
			}
			else if(field!=null && field.fld!=null){
				try{
					int[] r = clickedTextRange(field);
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
		else if(0==message.compareTo("heapspace")){
			long freesize = Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
			//a long integer as HyperCard's (its heap is part of a partition): an external reading it with StrToLong wrapped past 2^32.
			//Steady while memory is plentiful: Java's own allocations moved it by megabytes between two reads, so a stack that
			//measures what a command took (Cannons and Castles: AddColor install) saw noise
			if(freesize>=256L*1024*1024) freesize = Integer.MAX_VALUE;
			result.theResult = Long.toString(Math.max(0, Math.min(freesize, Integer.MAX_VALUE)-(PCARD.pc!=null && PCARD.pc.stack!=null ? PCARD.pc.stack.addColorHeapBytes : 0)));
		}
		else if(0==message.compareTo("stackspace")){
			//HyperCardのスタック領域の残り。ハンドラの呼び出しの深さに応じて減る値にする
			//(1呼び出しあたり約1KB、上限は128000)
			long free = 128000L - 1024L*ScriptDebugger.depth();
			result.theResult = Long.toString(Math.max(0, free));
		}
		else if(0==message.compareTo("diskspace")){
			//the diskSpace [of disk <名前>]
			File where = null;
			if(params!=null && params.length>0 && params[0].length()>0){
				String name = params[0];
				where = diskFolder(name);
				if(where==null) throw new xTalkException("No such disk: {0}", name);
			}else if(PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.file!=null){
				where = StackBundle.folderAround(PCARD.pc.stack.file); //the disk the stack is on (a stack file's, not its working folder's)
				if(where==null) where = PCARD.pc.stack.file;
			}else{
				where = new File(".");
			}
			result.theResult = Long.toString(where.getFreeSpace());
		}
		else if(0==message.compareTo("clipboard")){
			//the clipboard (HyperCard 2.4; Hyped's Clipboard accessory: put the clipboard into cd fld 1): the text on the
			//clipboard, empty when it holds no text
			String text = "";
			try{
				java.awt.datatransfer.Transferable t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
				if(t!=null && t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)){
					text = ((String)t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor)).replace("\r\n", "\n").replace('\r', '\n');
				}
			}catch(Exception e){
				text = "";
			}
			result.theResult = text;
		}
		else if(0==message.compareTo("fontexists") && isFunc){
			//FontExists(<font name>[,<point size>]): an XFCN of HyperCard 2.4's Home stack that stacks call without carrying it
			//(Living Labyrinth: if not fontExists("Times"); MailStack: if fontExists("Charcoal")). HyperZebra's Home has none, so
			//it is answered here from the fonts of the Font menu (a stack's or Home's own FontExists runs instead when there is one)
			String name = params.length>0 ? params[0].trim() : "";
			boolean found = false;
			if(!name.isEmpty()){
				for(java.awt.Component c : TTalk.fontMenu().getMenuComponents()){
					if(c instanceof javax.swing.JMenuItem && name.equalsIgnoreCase(((javax.swing.JMenuItem)c).getText())){ found = true; break; }
				}
			}
			result.theResult = Boolean.toString(found);
		}
		else if(0==message.compareTo("screenrect")){
			//カードウィンドウを表示しているモニタの矩形(複数モニタ)
			java.awt.Rectangle r = null;
			if(PCARD.pc!=null && PCARD.pc.isShowing()){
				java.awt.Rectangle win = PCARD.pc.getBounds();
				long best = -1;
				for(java.awt.GraphicsDevice gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
					java.awt.Rectangle b = gd.getDefaultConfiguration().getBounds();
					java.awt.Rectangle in = b.intersection(win);
					long area = in.isEmpty() ? 0 : (long)in.width*in.height;
					if(area>best){ best = area; r = b; }
				}
			}
			if(r==null){
				Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
				r = new java.awt.Rectangle(0, 0, d.width, d.height);
			}
			r = toLogicalGlobal(r); //the screen the zoomed card window is on, at the card's scale
			ExternalFrame frame = currentFrame();
			if(frame!=null) r = frame.screen();
			result.theResult = r.x+","+r.y+","+(r.x+r.width)+","+(r.y+r.height);
		}
		else if(0==message.compareTo("windows")){
			String windowsStr="";
			/*Frame[] frame = PCARD.getFrames();
			for(int i=0; i<frame.length; i++){
				if(GMsg.class == frame[i].getClass()){
					if(!windowsStr.equals("")) windowsStr+="\n";
					windowsStr += "Message";
				}
				if(!windowsStr.equals("")) windowsStr+="\n";
				windowsStr += frame[i].getName();
			}*/
			/*Window[] win = PCARD.getWindows();
			for(int i=0; i<win.length; i++){
				if(!windowsStr.equals("")) windowsStr+="\n";
				windowsStr += win[i].getName();
			}*/
			//HyperCard 2.4: every window name is followed by a return ("Message<CR>…<CR>Home<CR>")
			StringBuilder names = new StringBuilder();
			for(Object[] w : windowEntries()) names.append((String)w[0]).append('\n');
			result.theResult = names.toString();
		}
		else if(0==message.compareTo("result")){
			if(TTalk.lastResult != null && TTalk.lastResult.theResult != null){
				result.theResult = TTalk.lastResult.theResult;
			}
			else result.theResult = "";
		}
		else if(0==message.compareTo("itemdelimiter")){
			result.theResult = TTalk.itemDelimiter;
		}
		else if(0==message.compareTo("numberformat")){
			result.theResult = TTalk.numberFormatPattern;
		}
		else if(0==message.compareTo("sound")){
			String name = TTalk.sound().name;
			if(name.equals("")) name = "done";
			result.theResult = name;
		}
		else if(0==message.compareTo("long time")){
			result.theResult = HCDateTime.format(HCDateTime.now(), "long time");
		}
		else if(0==message.compareTo("time") || 0==message.compareTo("short time") ||
				0==message.compareTo("abbrev time")){
			result.theResult = HCDateTime.format(HCDateTime.now(), "short time");
		}
		else if(0==message.compareTo("long date")){
			result.theResult = HCDateTime.format(HCDateTime.now(), "long date");
		}
		else if(0==message.compareTo("abbrev date")){
			result.theResult = HCDateTime.format(HCDateTime.now(), "abbrev date");
		}
		else if(0==message.compareTo("date") || 0==message.compareTo("short date")){
			result.theResult = HCDateTime.format(HCDateTime.now(), "short date");
		}
		else if(0==message.compareTo("menus")){
			//HyperCard: 1行目は "Apple"、各行の後ろに return が付く("Apple<CR>ﾌｧｲﾙ<CR>…<CR>")。名前は scriptMenuName
			StringBuilder tmpStr = new StringBuilder();
			for(String n : TTalk.scriptMenuNames()) tmpStr.append(n).append('\n');
			result.theResult = tmpStr.toString();
		}
		else if(0==message.compareTo("destination")){
			//the destination (HyperCard 2.2): the full path of the stack HyperCard is going to (HyperCard Help's closeStack
			//keeps its help functions in use only when going to another help stack); otherwise this stack
			result.theResult = TTalk.destinationStack!=null ? TTalk.destinationStack :
				(PCARD.pc.stack==null || PCARD.pc.stack.path==null ? "" : PCARD.pc.stack.stackPathName());
		}
		else if(0==message.compareTo("stacks")){
			//開いているスタックのパス(カードウインドウのスタック、続いて自分のウインドウにある他のスタックを前から順に)。
			//HyperCardと同じくlong nameと同じコロン区切り(ちんちろり〜〜んは":"で区切って名前を取り出す)
			//one line per stack, each with its return (HC J-2.3 probe Objects: "the stacks" ends with return, like the windows)
			StringBuilder stacks = new StringBuilder();
			if(PCARD.pc.stack.path!=null) stacks.append(PCARD.pc.stack.stackPathName()).append('\n');
			for(String other : TTalk.windowStackPaths()) stacks.append(java.text.Normalizer.normalize(TTalk.macStackPath(other), java.text.Normalizer.Form.NFC)).append('\n');
			result.theResult = stacks.toString();
		}
		else if(0==message.compareTo("suspended")){
			result.theResult = PCARD.pc.isFocused()?"false":"true";
		}
		else if(0==message.compareTo("foundline")){
			//見つかった文字列を含む行(findが失敗していれば空)
			if(PCARD.pc.foundObject==null){
				result.theResult = "";
			}
			else {
				String text = TTalk.Evalution(PCARD.pc.foundObject, memData, null, null);
				int line = 1;
				for(int i=0; i<PCARD.pc.foundIndex && i<text.length(); i++){
					if(text.charAt(i)=='\n') line++;
				}
				result.theResult = "line "+line+" of "+PCARD.pc.foundObject;
			}
		}
		else if(0==message.compareTo("foundtext")){
			result.theResult = PCARD.pc.foundObject==null || PCARD.pc.foundText==null ? "" : PCARD.pc.foundText;
		}
		else if(0==message.compareTo("foundchunk")){
			if(PCARD.pc.foundObject==null){
				result.theResult = "";
			}
			else{
				//char は1始まり
				result.theResult = "char "+(PCARD.pc.foundIndex+1)+" to "+
				(PCARD.pc.foundIndex+PCARD.pc.foundText.length())+" of "+PCARD.pc.foundObject;
			}
		}
		else if(0==message.compareTo("foundfield")){
			result.theResult = PCARD.pc.foundObject==null ? "" : PCARD.pc.foundObject;
		}
		else if(0==message.compareTo("paramcount")){
			if(memData.params==null) result.theResult = "0";
			else result.theResult = Integer.toString(memData.params.length);
		}
		else if(0==message.compareTo("params")){
			//HyperCard: メッセージなら name "p1","p2"、関数なら name("p1","p2")。組み込みコマンドのメッセージは
			//HyperCard の書き方(answer normal "Q" with "OK"、visual)
			String builtin = BuiltinCommandMessage.paramsText(memData.params);
			if(builtin!=null){ result.theResult = builtin; result.ret = 0; return result; } //found: params() in on choose (Cannons and Castles)
			String str = "";
			if(memData.message!=null) str += memData.message;
			StringBuilder args = new StringBuilder();
			if(memData.params!=null){
				for(int i=0; i<memData.params.length; i++){
					if(i>0) args.append(',');
					args.append('"').append(memData.params[i]).append('"');
				}
			}
			if(memData.isFunction) str += "("+args+")";
			else if(args.length()>0) str += " "+args;
			result.theResult = str;
		}
		else if(0==message.compareTo("target")){
			if(target!=null) result.theResult = target.getShortName();
			else result.theResult = "";
		}
		else if(0==message.compareTo("short target") || 0==message.compareTo("long target") ||
				0==message.compareTo("abbrev target") || 0==message.compareTo("abbr target") || 0==message.compareTo("abbreviated target")){
			//the [short|long|abbrev] target: the [short|long|abbrev] name of the target と同じ
			if(target==null) result.theResult = "";
			else if(message.startsWith("short")) result.theResult = target.name.isEmpty() ? target.getShortName() : target.name;
			else if(message.startsWith("long")) result.theResult = target.getLongName();
			else result.theResult = target.getShortName();
		}
		else return CallSystemFunction3(message, params, target, memData, isFunc, result);
		result.ret = 0;
		if(result.theResult==null) result.theResult = "";
		return result;
	}

	private static Result CallSystemFunction3(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		if(0==message.compareTo("tool")){
			if(PCARD.pc.tool!=null){
				if(PCARD.pc.tool.getClass()==SelectTool.class) result.theResult = "select tool";
				else if(PCARD.pc.tool.getClass()==LassoTool.class) result.theResult = "lasso tool";
				else if(PCARD.pc.tool.getClass()==SmartSelectTool.class) result.theResult = "magicwand tool";
				else if(PCARD.pc.tool.getClass()==PencilTool.class) result.theResult = "pencil tool";
				else if(PCARD.pc.tool.getClass()==BrushTool.class) result.theResult = "brush tool";
				else if(PCARD.pc.tool.getClass()==PaintBucketTool.class) result.theResult = "bucket tool";
				else if(PCARD.pc.tool.getClass()==LineTool.class) result.theResult = "line tool";
				else if(PCARD.pc.tool.getClass()==RectTool.class) result.theResult = "rectangle tool";
				else if(PCARD.pc.tool.getClass()==RoundRectTool.class) result.theResult = "round rect tool";
				else if(PCARD.pc.tool.getClass()==OvalTool.class) result.theResult = "oval tool";
				else if(PCARD.pc.tool.getClass()==CurveTool.class) result.theResult = "curve tool";
				else if(PCARD.pc.tool.getClass()==TypeTool.class) result.theResult = "text tool";
				else if(PCARD.pc.tool.getClass()==EraserTool.class) result.theResult = "eraser tool";
				else if(PCARD.pc.tool.getClass()==SprayTool.class) result.theResult = "spray tool"; //HyperCard J-2.3 names (probe H Expr ChooseNames)
				else if(PCARD.pc.tool.getClass()==RegPolyTool.class) result.theResult = "regular polygon tool";
				else if(PCARD.pc.tool.getClass()==PolyTool.class) result.theResult = "polygon tool";
				else if(PCARD.pc.tool.getClass()==EyedropperTool.class) result.theResult = "eyedropper tool";
				else if(PCARD.pc.tool.getClass()==SmudgeTool.class) result.theResult = "smudge tool";
				else if(PCARD.pc.tool.getClass()==StampTool.class) result.theResult = "stamp tool";
			}
			else if(AuthTool.tool!=null){
				if(AuthTool.tool.getClass()==ButtonTool.class) result.theResult = "button tool";
				else if(AuthTool.tool.getClass()==FieldTool.class) result.theResult = "field tool";
				else if(AuthTool.tool instanceof ShapeTool) result.theResult = "shape tool";
			}
			else result.theResult = "browse tool";
		}
		else if((0==message.compareTo("selectedtext") || 0==message.compareTo("selectedline")) && params!=null && params.length>0 && params[0].length()>0){
			//the selectedText/selectedLine of <listField|popupButton>
			OObject obj = TTalk.getObject(params[0], memData, target);
			if(obj instanceof OField){
				OField f = (OField)obj;
				if(0==message.compareTo("selectedtext")) result.theResult = f.getSelectedText();
				else result.theResult = fieldSelectedLineRef(f);
			}
			else if(obj instanceof OButton){
				OButton b = (OButton)obj;
				int line = b.getSelectedLine();
				if(0==message.compareTo("selectedtext")){
					String[] lines = b.getText().split("\n", -1);
					result.theResult = (line>=1 && line<=lines.length) ? lines[line-1] : "";
				}else{
					OCardBase owner = (OCardBase)b.parent;
					int n = owner.btnList.indexOf(b)+1;
					result.theResult = line>=1 ? "line "+line+" of "+(owner.objectType.equals("background") ? "background button " : "card button ")+n : "";
				}
			}
			else throw new xTalkException("selectedText/selectedLine requires a field or pop-up button");
		}
		else if(0==message.compareTo("selectedtext") ||
				0==message.compareTo("selection"))
		{
			if(GUI.clickField!=null && GUI.clickField.fld!=null){
				//select コマンドやクリックで決まったフィールドを優先する(フォーカスの移動は非同期なので、直前に
				//フォーカスのあった別のフィールドの選択を返していた: select line 2 of cd fld "F2" の後に "bc")
				result.theResult = GUI.clickField.getSelectedText();
				if(result.theResult==null) result.theResult = "";
				return result;
			}
			//入力フォーカスのあるフィールドを検索して選択テキストを返す
			//cd
			for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
				OField field = PCARD.pc.stack.curCard.fldList.get(i);
				JComponent compo = field.getComponent();
				if(compo!=null && compo.isFocusOwner()){
					result.theResult = field.getSelectedText();
				}
			}
			//bg
			for(int i=0; i<PCARD.pc.stack.curCard.bg.fldList.size(); i++){
				OField field = PCARD.pc.stack.curCard.bg.fldList.get(i);
				JComponent compo = field.getComponent();
				if(compo!=null && compo.isFocusOwner()){
					result.theResult = field.getSelectedText();
				}
			}
			if(result.theResult==null){
				result.theResult = "";
			}
		}
		else if(0==message.compareTo("selectedchunk")){
			//現在選択中のテキストを指すチャンク式
			result.theResult = "";
			OField field = findFocusedOrClickedField();
			if(field!=null && field.fld!=null){
				int start = field.fld.getSelectionStart();
				int end = field.fld.getSelectionEnd();
				if(end>start){
					//HyperCard J-2.3: "char 19 to 19 of card field 1"(名前があっても番号の形。selectedField/selectedLine と同じ)
					result.theResult = "char "+(start+1)+" to "+end+" of "+fieldNumberRef(field);
				}
				else if(field==GUI.clickField){
					//挿入ポイント(select before/after …)は "char N+1 to N" の形
					result.theResult = "char "+(start+1)+" to "+start+" of "+fieldNumberRef(field);
				}
			}
		}
		else if(0==message.compareTo("selectedline")){
			//現在選択中のテキストを含む行(選択が無ければ空)
			result.theResult = "";
			OField field = findFocusedOrClickedField();
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
					if(firstLine==lastLine) result.theResult = "line "+firstLine+" of "+fieldNumberRef(field);
					else result.theResult = "line "+firstLine+" to "+lastLine+" of "+fieldNumberRef(field);
				}
				else if(field==GUI.clickField){
					//挿入ポイントだけ(select after line 2 of …)でもその行(HyperCard J-2.3: "line 2 of card field 4")
					String text = field.getText();
					int line = 1;
					for(int i=0; i<text.length() && i<start; i++) if(text.charAt(i)=='\n') line++;
					result.theResult = "line "+line+" of "+fieldNumberRef(field);
				}
			}
		}
		else if(0==message.compareTo("selectedfield")){
			//現在選択中のテキストのあるフィールド: "card field N" / "bkgnd field N"(番号形式、HyperCard)
			result.theResult = "";
			OField field = findFocusedOrClickedField();
			//挿入ポイントだけでもそのフィールド(HyperCard J-2.3 / 2.4: select after char 2 of … → "card field 3")
			if(field!=null && field.fld!=null && (field.fld.getSelectionEnd()>field.fld.getSelectionStart() || field==GUI.clickField)){
				result.theResult = fieldNumberRef(field);
			}
		}
		else if(0==message.compareTo("selectedloc")){
			//現在選択中のテキストの中央位置(カード座標)
			result.theResult = "";
			//HyperCard: 選択範囲(または挿入ポイント)の左端と下端(カード座標)。挿入ポイントだけでも返す
			OField field = findFocusedOrClickedField();
			if(field!=null && field.fld!=null && (field.fld.isFocusOwner() || field==GUI.clickField)){
				int start = field.fld.getSelectionStart();
				try{
					Rectangle2D r1 = field.fld.modelToView2D(start);
					double scale = PCARD.scale();
					int x = (int)(r1.getX()/scale) + field.left;
					int y = (int)((r1.getY()+r1.getHeight())/scale) + field.top;
					result.theResult = x+","+y;
				}catch(Exception e){}
			}
		}
		else if(0==message.compareTo("selectedbutton")){
			//the selectedButton of [bg] family N: そのファミリーでハイライトされているボタン
			if(params==null || params.length<1) throw new xTalkException("selectedButton requires a family number");
			String spec = params[0].trim();
			boolean bg = false;
			String[] w = spec.split("\\s+");
			int k = 0;
			if(w.length>0 && (w[0].equalsIgnoreCase("bg") || w[0].equalsIgnoreCase("bkgnd") || w[0].equalsIgnoreCase("background") || w[0].equalsIgnoreCase("cd") || w[0].equalsIgnoreCase("card"))){
				bg = !(w[0].equalsIgnoreCase("cd") || w[0].equalsIgnoreCase("card")); k++;
			}
			if(k<w.length && w[k].equalsIgnoreCase("family")) k++;
			if(k>=w.length) throw new xTalkException("selectedButton requires a family number");
			int family;
			String num = w[k].replace("\"", "").trim();
			try{ family = Integer.parseInt(num); }catch(NumberFormatException e){ throw new xTalkException("The family number is not a number: {0}", w[k]); }
			if(family<1 || family>15) throw new xTalkException("family must be between 1 and 15: {0}", family);
			OCard ownerCard = PCARD.pc.stack.curCard;
			//of <card>: that card's buttons (the card's id comes as the second parameter)
			if(params.length>=2){
				try{
					OCard c = PCARD.pc.stack.GetCardbyId(Integer.parseInt(params[1].trim()));
					if(c!=null) ownerCard = c;
				}catch(NumberFormatException e){}
			}
			OCardBase owner = bg ? ownerCard.bg : ownerCard;
			result.theResult = "";
			if(owner!=null){
				for(int i=0; i<owner.btnList.size(); i++){
					OButton b = owner.btnList.get(i);
					//a background button's hilite on the card asked about (sharedHilite false: each card has its own)
					if(bg) b = b.forCard(ownerCard);
					if(b.group==family && b.getHilite()){
						result.theResult = (bg ? "background button " : "card button ")+(i+1);
						//of <card>: the card is named too (HyperCard J-2.3: card button 10 of card id 2817)
						if(params.length>=2 && ownerCard!=PCARD.pc.stack.curCard) result.theResult += " of card id "+ownerCard.id;
						break;
					}
				}
			}
		}
		else if(0==message.compareTo("programs")){
			//実行中のアプリケーション(macOSではSystem Eventsに問い合わせる)。先頭は自分自身
			java.util.List<String> progs = new java.util.ArrayList<String>();
			progs.add(PCARD.AppName);
			if(System.getProperty("os.name","").toLowerCase().contains("mac")){
				try{
					Process p = new ProcessBuilder("osascript", "-e", "tell application \"System Events\" to get name of every process whose background only is false").start();
					String out = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
					p.waitFor();
					for(String n : out.split(",")){ n = n.trim(); if(!n.isEmpty() && !progs.contains(n)) progs.add(n); }
				}catch(Exception e){}
			}
			result.theResult = String.join("\n", progs);
		}
		else if(0==message.compareTo("numtochar"))
		{
			int i = Integer.valueOf(params[0]);
			if(i<=0||i>65535) result.theResult = "";
			else{
				char c = (char)i;
				//128〜255 はスタックの文字コードの1バイト文字(HyperCard J-2.3: numToChar(194) = "ﾂ"(継続記号)、
				//numToChar(177) = "ｱ"。Mac OS Roman のスタックなら numToChar(165) = "•")
				if(i>=128 && i<=255){
					String one = legacyChar(i);
					if(one!=null) c = one.charAt(0);
				}
				//charToNumと対称に、13(CR)は内部の改行\n、10(LF)は\rにする
				if(c=='\r') c = '\n';
				else if(c=='\n') c = '\r';
				result.theResult = Character.toString(c);
			}
		}
		//
		//プロパティも関数と同じ扱い。こいつらはsetできる。
		//
		else if(0==message.compareTo("userlevel")){
			result.theResult = Integer.toString(StackProtection.effectiveUserLevel());
			result.ret = 0;
		}
		else if(0==message.compareTo("scripttextfont")){
			result.theResult = PCARD.scriptFont;
			result.ret = 0;
		}
		else if(0==message.compareTo("scripttextsize")){
			result.theResult = Integer.toString(PCARD.scriptFontSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("systemlang")){
			result.theResult = International.englishName(PCARD.pc.lang); //"Japanese"/"English"(以前の版と同じ英語の名前)
			result.ret = 0;
		}
		else if(0==message.compareTo("dragspeed")){
			result.theResult = Integer.toString(TTalk.dragspeed);
			result.ret = 0;
		}
		else if(0==message.compareTo("editbkgnd")){
			result.theResult = Boolean.toString(PaintTool.editBackground);
			result.ret = 0;
		}
		else if(0==message.compareTo("filled")){
			result.theResult = Boolean.toString(PCARD.pc.fill);
			result.ret = 0;
		}
		else if(0==message.compareTo("centered")){
			result.theResult = Boolean.toString(PaintTool.drawCentered);
			result.ret = 0;
		}
		else if(0==message.compareTo("multiple")){
			result.theResult = Boolean.toString(PaintTool.drawMultiple);
			result.ret = 0;
		}
		else if(0==message.compareTo("multispace")){
			result.theResult = Integer.toString(PaintTool.multiSpace);
			result.ret = 0;
		}
		else if(0==message.compareTo("polysides")){
			result.theResult = Integer.toString(PaintTool.regPolySides);
			result.ret = 0;
		}
		else if(0==message.compareTo("gradient")){
			//塗りの種類(GradButton): off / linear / radial
			GradButton g = PCARD.pc.grad;
			result.theResult = g==null ? "off" : g.kind()==0 ? "off" : g.kind()==1 ? "linear" : g.kind()==2 ? "radial" : "texture";
			result.ret = 0;
		}
		else if(0==message.compareTo("filltexture")){
			//テクスチャ塗りに使うテクスチャの名前(内蔵名 / custom / none)
			result.theResult = PaintTexture.currentName;
			result.ret = 0;
		}
		else if(0==message.compareTo("gradientangle")){
			GradButton g = PCARD.pc.grad;
			result.theResult = Integer.toString(g==null ? 0 : GToolOptions.AngleDial.normalize((int)Math.round(Math.toDegrees(g.angle))));
			result.ret = 0;
		}
		else if(0==message.compareTo("filltolerance")){
			result.theResult = Integer.toString(PaintTool.fillTolerancePercent);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushsize")){
			result.theResult = Integer.toString(PaintTool.brushSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushhardness")){
			result.theResult = Integer.toString(PaintTool.brushHardness);
			result.ret = 0;
		}
		else if(0==message.compareTo("smudgestrength")){
			result.theResult = Integer.toString(PaintTool.smudgeStrength);
			result.ret = 0;
		}
		else if(0==message.compareTo("stampsource")){
			//スタンプの写し元(未指定なら空)
			result.theResult = StampTool.source==null ? "" : StampTool.source.x+","+StampTool.source.y;
			result.ret = 0;
		}
		else if(0==message.compareTo("brushopacity")){
			result.theResult = Integer.toString(PaintTool.brushOpacity);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushspacing")){
			result.theResult = Integer.toString(PaintTool.brushSpacing);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushstabilization") || 0==message.compareTo("brushstabilizer")){
			result.theResult = Integer.toString(PaintTool.brushStabilization);
			result.ret = 0;
		}
		else if(0==message.compareTo("zoom")){
			//ペイントの表示倍率(%)。100が実寸、800がFatBits
			result.theResult = Integer.toString(PaintTool.zoomPercent(PCARD.pc));
			result.ret = 0;
		}
		else if(0==message.compareTo("zoomorigin")){
			//拡大表示で左上に見えているカード座標
			result.theResult = PaintTool.viewLeftOf(PCARD.pc)+","+PaintTool.viewTopOf(PCARD.pc);
			result.ret = 0;
		}
		else if(0==message.compareTo("powerkeys")){
			result.theResult = Boolean.toString(PaintTool.powerKeys);
			result.ret = 0;
		}
		else if(0==message.compareTo("gridsize")){
			//描画グリッドの大きさ(Options > Drawing Grid Size)
			result.theResult = Integer.toString(PaintTool.drawingGridSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("paintlayer")){
			//編集中のレイヤー番号(1=Card Paint/Background Paint、2〜=追加レイヤー)
			result.theResult = Integer.toString(PCARD.pc.mainImg==null ? 1 : PCARD.pc.activeLayer()+1);
			result.ret = 0;
		}
		else if(0==message.compareTo("paintlayercount")){
			result.theResult = Integer.toString(PaintLayers.count(PCARD.pc));
			result.ret = 0;
		}
		else return CallSystemFunction4(message, params, target, memData, isFunc, result);
		result.ret = 0;
		if(result.theResult==null) result.theResult = "";
		return result;
	}

	private static Result CallSystemFunction4(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		if(0==message.compareTo("layername")){
			result.theResult = PCARD.pc.mainImg==null ? "" : PaintLayers.nameOf(PCARD.pc, PCARD.pc.activeLayer());
			result.ret = 0;
		}
		else if(0==message.compareTo("layeropacity") || 0==message.compareTo("layerblend") ||
				0==message.compareTo("layervisible") || 0==message.compareTo("layeralphalock") || 0==message.compareTo("layerclip")){
			PaintLayer l = PCARD.pc.mainImg==null ? null : PCARD.pc.activePaintLayer();
			if(0==message.compareTo("layeropacity")) result.theResult = l==null ? "100" : Integer.toString(l.opacity);
			else if(0==message.compareTo("layerblend")) result.theResult = l==null ? "Normal" : PaintLayer.blendName(l.blend);
			else if(0==message.compareTo("layervisible")) result.theResult = l==null ? "true" : Boolean.toString(l.visible);
			else if(0==message.compareTo("layeralphalock")) result.theResult = l==null ? "false" : Boolean.toString(l.alphaLock);
			else result.theResult = l==null ? "false" : Boolean.toString(l.clipToBelow);
			result.ret = 0;
		}
		else if(0==message.compareTo("symmetry")){
			//対称描画(Options > Symmetry): none / vertical / horizontal / quadrant / radial
			result.theResult = PaintTool.symmetryName();
			result.ret = 0;
		}
		else if(0==message.compareTo("symmetryaxes")){
			result.theResult = Integer.toString(PaintTool.symmetryAxes);
			result.ret = 0;
		}
		else if(0==message.compareTo("symmetrycenter")){
			//中心の座標。未設定ならキャンバスの中心
			java.awt.image.BufferedImage sfc = PCARD.pc.getSurface();
			if(sfc==null) sfc = PCARD.pc.stack==null ? null : PCARD.pc.stack.curCard==null ? null : PCARD.pc.stack.curCard.pict;
			float cx = PaintTool.symmetryCenterX, cy = PaintTool.symmetryCenterY;
			if(cx<0 || cy<0){
				if(sfc!=null){ cx = sfc.getWidth()/2f; cy = sfc.getHeight()/2f; }
				else if(PCARD.pc.stack!=null){ cx = PCARD.pc.stack.width/2f; cy = PCARD.pc.stack.height/2f; }
				else { cx = 0; cy = 0; }
			}
			result.theResult = Math.round(cx)+","+Math.round(cy);
			result.ret = 0;
		}
		else if(0==message.compareTo("grid")){
			result.theResult = Boolean.toString(PaintTool.drawingGrid);
			result.ret = 0;
		}
		else if(0==message.compareTo("linesize")){
			result.theResult = TTalk.numFormat.format(PaintTool.lineSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("pattern") || 0==message.compareTo("brushpattern") ||
				0==message.compareTo("penpattern") || 0==message.compareTo("fillpattern")){
			result.theResult = PCARD.pc.pat==null ? "1" : Integer.toString(PCARD.pc.pat.pattern+1);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushcolor") || 0==message.compareTo("pencolor")){
			result.theResult = paintColorText(PCARD.pc.fore==null ? Color.BLACK : PCARD.pc.fore.color);
			result.ret = 0;
		}
		else if(0==message.compareTo("brushbackcolor") || 0==message.compareTo("penbackcolor")){
			result.theResult = paintColorText(PCARD.pc.back==null ? Color.WHITE : PCARD.pc.back.color);
			result.ret = 0;
		}
		else if(0==message.compareTo("language")){
			//スクリプトの言語(HyperCard J-2.3 でも "English")。HyperZebra の表示言語(PCARD.pc.lang)ではない
			result.theResult = "English";
			result.ret = 0;
		}
		else if(0==message.compareTo("lockscreen")){
			result.theResult = Boolean.toString(PCARD.lockedScreen);
			result.ret = 0;
		}
		else if(0==message.compareTo("lockmessages")){
			result.theResult = Boolean.toString(TTalk.lockMessages);
			result.ret = 0;
		}
		else if(0==message.compareTo("lockerrordialogs")){
			result.theResult = Boolean.toString(TTalk.lockErrorDialogs);
			result.ret = 0;
		}
		else if(0==message.compareTo("messagewatcher")){
			result.theResult = Boolean.toString(MessageWatcher.watcherWindow!=null && MessageWatcher.watcherWindow.isVisible());
			result.ret = 0;
		}
		else if(0==message.compareTo("variablewatcher")){
			result.theResult = Boolean.toString(VariableWatcher.watcherWindow!=null && VariableWatcher.watcherWindow.isVisible());
			result.ret = 0;
		}
		else if(0==message.compareTo("debugger")){
			result.theResult = Boolean.toString(ScriptDebugger.paused);
			result.ret = 0;
		}
		else if(0==message.compareTo("stacksinuse")){
			//start using しているスタックのパス(メッセージパス順、return区切り)。カレントスタック自身は含まない
			StringBuilder sb = new StringBuilder();
			if(PCARD.pc.stack!=null && PCARD.pc.stack.usingStacks!=null){
				for(OStack u : PCARD.pc.stack.usingStacks){
					if(u==null || u.path==null) continue;
					String path = TTalk.stackIdentityPath(u.path);
					//Homeスタック(./home)はメッセージパスの一部で、stacksInUseには含まれない
					if(u==PCARD.home || path.equals(TTalk.stackIdentityPath("./home"))) continue;
					if(sb.length()>0) sb.append('\n');
					//Mac paths, as the stacks gives them (UxDeleteFile compares each line with the stack's path: "File is busy" instead
					//of "Stack is in using" when the line was the host path of the .hzb)
					sb.append(java.text.Normalizer.normalize(u.file!=null ? u.stackPathName() : TTalk.macStackPath(u.path), java.text.Normalizer.Form.NFC));
				}
			}
			result.theResult = sb.toString();
			result.ret = 0;
		}
		else if(0==message.compareTo("textfont")){
			result.theResult = paintTextFont();
			result.ret = 0;
		}
		else if(0==message.compareTo("textsize")){
			result.theResult = Integer.toString(PCARD.pc.textSize);
			result.ret = 0;
		}
		else if(0==message.compareTo("textstyle")){
			result.theResult = textStyleName(PCARD.pc.textStyle);
			result.ret = 0;
		}
		else if(compatibilityProperties.containsKey(message)){
			result.theResult = compatibilityProperties.get(message);
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("checkHCFont")){
			//Homeスタックの関数
			result.theResult = ""; //とにかくemptyで
			result.ret = 0;
		}
		else{
			System.out.println("未定義の関数/プロパティ:"+message);
			if(Boolean.getBoolean("dbg.undef")) new Throwable().printStackTrace(System.out);
			return result; //ret=1のまま返す
		}
		result.ret = 0;
		if(result.theResult==null) result.theResult = "";
		return result;
	}

	
	
	//システムメッセージおよびXCMD,XFCN
	public static Result CallSystemMessage(String message, String[] params, OObject target, MemoryData memData, boolean isFunc)
		throws xTalkException 
	{
		Result result = new Result();
		result.ret = 1;//見つからなければ1

		//下のメッセージ名のどれでもなければ(関数呼び出しなど)、名前を順に比べずに XCMD/組み込み関数の処理へ進む。
		//compareToIgnoreCase は ı(U+0131) のような文字も ASCII の文字と同じとみなすので、ASCII の名前のときだけ省く
		if(isAscii(message) && !SYSTEM_MESSAGE_NAMES.contains(message.toLowerCase(java.util.Locale.ROOT))){
			return callExternalOrFunction(message, params, target, memData, isFunc, result);
		}
		//a function call is never one of HyperCard's messages: Palette("Install",…) is the stack's own XFCN (PaletteXFCN's
		//tear-off palette never appeared: the call ended in the built-in palette command)
		if(isFunc && TXcmd.externalOwner(message, "function")!=null){
			return callExternalOrFunction(message, params, target, memData, isFunc, result);
		}
		//a 68K external named like one of HyperCard's own messages takes it as the message passes the stack, before
		//HyperCard's handling (Best of HC Externals' ErrorDialog XCMD; also moveWindow/resume/openField…). One that sets
		//passFlag lets HyperCard's own handling below run. Picture stays the built-in stand-in (stacks carry copies of
		//HyperCard's own Picture XCMD), Palette too when it names a PLTE resource as HyperCard's palette does; another
		//external named Palette is the stack's own (Palette Stack's pltt XCMD: Palette "G","Logo","LogoPal")
		if(!isFunc && !message.equalsIgnoreCase("picture") && !(message.equalsIgnoreCase("palette") && namesPlteResource(params)) &&
				TXcmd.runnable68kExternal(message, "command")){
			Result taken = TXcmd.CallExternalCommand(message, params, target, memData, false, result);
			if(taken.ret==0) return taken;
			result = new Result();
			result.ret = 1;
		}

		if(message.equalsIgnoreCase("mouseDown")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("mouseUp")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("mouseLeave")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("mouseEnter")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("mouseWithin")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("mouseStillDown")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("mouseDoubleClick")){
			result.ret = 0;
		}
		//表(tableStyle)のフィールドが送るメッセージ: ハンドラが無ければ何もしない
		else if(message.equalsIgnoreCase("sortTable") || message.equalsIgnoreCase("closeField") ||
				//HyperCard's own messages with nothing more to do when a script passes them (Graph Maker: on exitField … pass exitField)
				message.equalsIgnoreCase("exitField") || message.equalsIgnoreCase("openField") ||
				message.equalsIgnoreCase("resumeStack") || message.equalsIgnoreCase("suspendStack") ||
				message.equalsIgnoreCase("resume") || message.equalsIgnoreCase("suspend") ||
				message.equalsIgnoreCase("moveWindow") || message.equalsIgnoreCase("errorDialog")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("mouseUpInPicture") ||
				message.equalsIgnoreCase("mouseDownInPicture") ||
				message.equalsIgnoreCase("openPicture") ||
				message.equalsIgnoreCase("closePicture") ||
				message.equalsIgnoreCase("openPalette") ||
				message.equalsIgnoreCase("closePalette") ||
				message.equalsIgnoreCase("openMovie") ||
				message.equalsIgnoreCase("closeMovie") ||
				message.equalsIgnoreCase("mouseDownInMovie") ||
				message.equalsIgnoreCase("mouseUpInMovie") ||
				message.equalsIgnoreCase("mouseStillDownInMovie") ||
				//Movie XCMD 1.2 reports what the user did with the controller (Myst Preview: movieRateChanged at every play)
				message.equalsIgnoreCase("movieRateChanged") ||
				message.equalsIgnoreCase("movieTimeChanged") ||
				message.equalsIgnoreCase("movieVolumeChanged")){
			//the Picture / Palette / Movie XCMDs' messages to the card: nothing to do when no script takes them
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("startUp")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("openStack")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("closeStack")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("openCard")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("closeCard")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("openBackground")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("closeBackground")){
			result.ret = 0;
		}
		//カード/bgの作成・削除時のシステムメッセージ(GMenuBrowse.doMenuが送る)
		else if(message.equalsIgnoreCase("newCard") || message.equalsIgnoreCase("deleteCard") ||
				message.equalsIgnoreCase("newBackground") || message.equalsIgnoreCase("deleteBackground") ||
				message.equalsIgnoreCase("newButton") || message.equalsIgnoreCase("newField") ||
				message.equalsIgnoreCase("deleteButton") || message.equalsIgnoreCase("deleteField") ||
				message.equalsIgnoreCase("newPanel") || message.equalsIgnoreCase("deletePanel") ||
				message.equalsIgnoreCase("newStack") || message.equalsIgnoreCase("mouseStillDown")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("idle")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("keydown")){
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("commandkeydown")){
			result.ret = 0;
		}
		else if(0==message.compareTo("doMenu")){
			//メニュー選択がスクリプトに捕まえられなかった: 組み込みのメニュー動作/menuMessage
			GMenuBrowse.doMenu(params.length>0 ? params[0] : "", params.length>1 ? params[1] : null);
			result.ret = 0;
		}
		else if(0==message.compareTo("arrowKey")){
			//ユーザーの矢印キー: スクリプトで捕まえられなければカード移動(左右)/リーセント履歴(上下)。
			//textArrows が true でフィールド編集中なら Swing 側が挿入ポイントを動かすので何もしない
			OField caret = findFocusedOrClickedField();
			boolean textArrows = compatibilityProperty("textArrows").equalsIgnoreCase("true");
			if(!(textArrows && caret!=null && caret.fld!=null && caret.fld.isFocusOwner())){
				TTalk.arrowKeyNavigation(params[0]);
			}
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("controlkey") || message.equalsIgnoreCase("functionkey") ||
				message.equalsIgnoreCase("returnkey") || message.equalsIgnoreCase("enterkey") ||
				message.equalsIgnoreCase("returninfield") || message.equalsIgnoreCase("enterinfield") ||
				message.equalsIgnoreCase("tabkey")){
			//キーボードコマンドが最後まで届いた: 組み込み動作はコマンド側(TTalk.keyboardCommand)で行う
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("picture")){
			//HomeスタックのXCMD
			//no source (picture "部品1",,rect,false: まさの冒険's ending): HyperCard's default is a file; a resource of that
			//name is looked for after it, as HyperZebra did before
			boolean defaultSource = !(params.length>=2 && params[1].trim().length()>0);
			String typeStr = defaultSource ? "file" : params[1];
			String windowType = "rect";
			if(params.length>=3 && params[2].length()>0){
				windowType = params[2];
			}
			boolean visible = true;
			if(params.length>=4 && params[3].length()>0){
				visible = params[3].equalsIgnoreCase("true");
			}
			BufferedImage bi = null;
			String windowName = params[0];
			if(defaultSource){
				File around = StackBundle.folderAround(PCARD.pc.stack.file);
				File beside = new File(around, params[0]);
				if(!beside.isFile()){ File other = TTalk.expandShortHfsNames(beside); if(other!=null) beside = other; }
				if(!beside.isFile() && !new File(TTalk.convertFromMacPath(params[0])).isFile()) typeStr = "resource";
			}
			if(typeStr.equalsIgnoreCase("resource")){
				//リソースを探す(名前、無ければID)。ウィンドウ名はリソース名(無名ならID)。
				//picture 128,resourceの後でそのウィンドウを操作する場合がある
				//名前で見つけたPICTはそのまま使う(IDで引き直すと、Homeの名前とこのスタックのIDが重なったとき別の絵になる)
				Rsrc rsrc = PCARD.pc.stack.rsrc;
				Rsrc.rsrcClass named = rsrc.findByNameAll(params[0],"PICT");
				if(named!=null){
					bi = rsrc.getImage(params[0],"PICT");
					if(named.name!=null && named.name.length()>0) windowName = named.name;
				}
				else if(params[0].trim().matches("-?[0-9]+")){
					int rsrcid = Integer.parseInt(params[0].trim());
					bi = rsrc.getImage(rsrcid,"PICT");
					String rsrcName = rsrc.getNameAll(rsrcid,"PICT");
					if(rsrcName!=null && rsrcName.length()>0) windowName = rsrcName;
					//a PICT without a name: the window is "PICT ID n" (Power Tools Resource Mover "View")
					else if(bi!=null) windowName = "PICT ID "+rsrcid;
				}
			}
			else if(typeStr.equalsIgnoreCase("clipboard")){
				//picture "Clipboard","clipboard": the picture on the clipboard (Readymade Buttons)
				try{
					java.awt.datatransfer.Transferable t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
					if(t!=null && t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)){
						java.awt.Image img = (java.awt.Image)t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
						if(img instanceof BufferedImage) bi = (BufferedImage)img;
						else if(img!=null && img.getWidth(null)>0){
							bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
							java.awt.Graphics2D g = bi.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose();
						}
					}
				}catch(Exception e){ bi = null; }
			}
			else if(typeStr.equalsIgnoreCase("file")){
				//ファイルを探す(スタックのフォルダ、次に指定されたパスそのもの)。PICT でも PNG/JPEG/GIF でもよい
				File around = StackBundle.folderAround(PCARD.pc.stack.file); //beside the stack (a stack file's folder, not its working folder)
				String path = (around+File.separator+params[0]);
				if(!new File(path).exists()){
					//the name in the other encoding (BRAVE: "picture "レオン",file" for the host file "ÉåÉIÉì", its Shift-JIS bytes read as Mac OS Roman)
					File other = TTalk.expandShortHfsNames(new File(path));
					if(other!=null && other.isFile()) path = other.getPath();
				}
				bi = loadPictureFile(path);
				if(bi==null) bi = loadPictureFile(TTalk.convertFromMacPath(params[0]));
				//":fix:Flower.pic": a partial path from the folder open file uses (UDI UxGetFileIcon demo buttons)
				if(bi==null) bi = loadPictureFile(TTalk.resolveFilePath(params[0]));
				//ウィンドウ名はファイル名(パスは含めない)
				String fileName = new File(TTalk.convertFromMacPath(params[0])).getName();
				if(!fileName.isEmpty()) windowName = fileName;
			}
			if(bi!=null){
				GPictWindow gpw = new GPictWindow(PCARD.pc, windowName, bi, windowType, visible);
				gpw.enablePictureMessages();
				new OWindow(gpw);
				//openPicture <window name> before the next statement (HyperCard J-2.3 measured; Express Yourself keeps the name
				//its on openPicture is given and moves that window right after the picture command)
				GPictWindow.sendPictureMessage("openPicture", windowName);
			}
			//Picture XCMD: a picture it cannot show sets the result (Power Tools Resource Mover "View",
			//Readymade Buttons: if "Couldn't display picture" is in the result)
			else result.theResult = "Couldn't display picture.";
			result.ret = 0;
		}
		else if(message.equalsIgnoreCase("palette")){
			//HomeスタックのXCMD
			if(params.length<1 || params[0].length()==0){
				throw new xTalkException("palette requires a palette name");
			}
			OWindow existingWindow = OWindow.findPaletteWindow(params[0]);
			if(existingWindow!=null){
				if(params.length>=2){
					Point location = parsePaletteLocation(params[1]);
					//locationはカード基準の論理座標なので、実画面座標に足す前にscale()倍する。
					if(location!=null){
						existingWindow.gpw.setLocation(paletteLocationOnScreen(
							getCardOriginOnScreen(), new Point(PCARD.toPhysical(location.x), PCARD.toPhysical(location.y))));
					}
				}
				existingWindow.gpw.setVisible(true);
				existingWindow.gpw.toFront();
				result.ret = 0;
				return result;
			}
			//PLTEリソースを探す
			boolean found = false;
			for(int i=0; i<PCARD.pc.stack.rsrc.plteList.size(); i++){
				Rsrc.PlteClass plte = PCARD.pc.stack.rsrc.plteList.get(i);
				if(plte.name.equalsIgnoreCase(params[0])){
					BufferedImage bi = PCARD.pc.stack.rsrc.getImage(plte.pictId,"PICT");
					if(bi==null){
						bi = PCARD.pc.stack.rsrc.getImage(plte.id,"PICT");
					}
					if(bi!=null){
						// Some PICT resources retain their QuickDraw origin as transparent
						// top/left canvas space, while others are already normalized.
						openPaletteWindow(plte, normalizePaletteImage(bi, plte.pictHV), params);
					}
					else{
						result.theResult = "Error: PICT not found.";
					}
					found = true;
					break;
				}
			}
			//palette "Navigator" (MailReader and ~120 other stacks): HyperCard's own PLTE 128, for stacks without one
			if(!found && params[0].trim().equalsIgnoreCase(NavigatorPalette.NAME))
				openPaletteWindow(NavigatorPalette.plte(PCARD.pc.stack.rsrc), NavigatorPalette.image(), params);
			result.ret = 0;
		}
		else{
			return callExternalOrFunction(message, params, target, memData, isFunc, result);
		}
		
		return result;
	}

	private static final java.util.Set<String> SYSTEM_MESSAGE_NAMES = java.util.Set.of(
		"arrowkey", "closebackground", "closecard", "closefield", "closemovie", "closepalette", "closepicture", "openpicture",
		"closestack", "commandkeydown", "controlkey", "deletebackground", "deletebutton", "deletecard",
		"deletefield", "domenu", "enterinfield", "enterkey", "functionkey", "idle",
		"keydown", "mousedoubleclick", "mousedown", "mousedowninmovie", "mousedowninpicture", "mouseenter",
		"mouseleave", "mousestilldown", "mouseup", "mouseupinmovie", "mouseupinpicture", "mousewithin",
		"mousestilldowninmovie", "movieratechanged", "movietimechanged", "movievolumechanged",
		"newbackground", "newbutton", "newcard", "newfield", "newpanel", "deletepanel", "newstack", "openbackground",
		"opencard", "openmovie", "openpalette", "openstack", "palette", "picture",
		"returninfield", "returnkey", "sorttable", "startup", "tabkey",
		"exitfield", "openfield", "resumestack", "suspendstack", "resume", "suspend", "movewindow", "errordialog");

	/** palette <name>: the name of a PLTE resource of the stack or an open palette (HyperCard's own palette command) */
	private static boolean namesPlteResource(String[] params) {
		if(params==null || params.length<1 || params[0]==null || params[0].isEmpty()) return false; //HyperCard's needs a name
		if(OWindow.findPaletteWindow(params[0])!=null) return true;
		OStack st = PCARD.pc!=null ? PCARD.pc.stack : null;
		if(st==null || st.rsrc==null) return true;
		for(Rsrc.PlteClass plte : st.rsrc.plteList) if(plte.name!=null && plte.name.equalsIgnoreCase(params[0])) return true;
		return false;
	}

	private static boolean isAscii(String s) {
		for(int i=0; i<s.length(); i++) if(s.charAt(i)>=0x80) return false;
		return true;
	}

	//システムメッセージ以外: スタックの XCMD/XFCN、HyperCard 自前の XCMD、組み込み関数
	private static Result callExternalOrFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		if(TXcmd.externalOwner(message, isFunc ? "function" : "command")!=null) //the open stack's, a using stack's or Home's
		{
			return TXcmd.CallExternalCommand(message,params, target, memData, isFunc, result);
		}
		else if(!isFunc && (message.equalsIgnoreCase("addcolor") || message.equalsIgnoreCase("movie"))){
			//AddColor は HyperCard 2.2 以降、Movie は HyperCard 2.4 が自前で持つ XCMD(スタック側にリソースは無い): 組み込みの実装へ
			return TXcmd.CallExternalCommand(message,params, target, memData, isFunc, result);
		}
		else if(!isFunc && homeUserLevelHandler(message)){
			result.ret = 0;
		}
		else{
			result = CallSystemFunction(message.toLowerCase(), params, target, memData, isFunc);
			if(result.ret!=0){
				System.out.println("未定義の関数:"+message);
				throw new xTalkException(OsaScript.cantUnderstand(message, target));
			}
		}
		return result;
	}

	private static Integer homeSavedUserLevel;

	/**
	 * setUserLevelFive / restoreUserLevel: handlers of the HyperCard 2.x Home stack script (J-2.3, 2.2, Player 2.4.1)
	 * that stacks call ("GG REMIX" suspendStack). HyperZebra's own Home has no stack script, so a message no handler
	 * took ends here: setUserLevelFive keeps the current level and sets 5, restoreUserLevel brings the kept level back
	 * (nothing when none was kept). getHomeInfo (Home 1.x/2.x) brings back the preference level.
	 */
	private static boolean homeUserLevelHandler(String message){
		if(message.equalsIgnoreCase("setUserLevelFive")){
			homeSavedUserLevel = StackProtection.effectiveUserLevel();
			PCARD.userLevel = 5;
		}
		else if(message.equalsIgnoreCase("restoreUserLevel")){
			if(homeSavedUserLevel==null) return true;
			PCARD.userLevel = homeSavedUserLevel;
		}
		else if(message.equalsIgnoreCase("getHomeInfo")){
			//HyperCard 1.x/2.x Home: re-applies the User Preferences card (user level etc.); stacks call it in
			//closeStack/CheckGlobals (If Monks Had Macs). Without the card the level is HyperZebra's preference level, 5.
			applyHomePreferences();
		}
		else return false;
		GMenu.enforceUserLevel();
		return true;
	}

	/**
	 * What Home's getHomeInfo does with its "User Preferences" card (HomeStack.addPreferenceCards): the user level of
	 * the hidden field "User Level", the powerKeys/textArrows/blindTyping of its check boxes, the global UserName, and
	 * the globals Stacks, Applications and Documents from the background field "Paths" of the search path cards.
	 */
	private static void applyHomePreferences(){
		int level = 5;
		OStack home = TTalk.homeStackObject();
		OCard prefs = home==null ? null : HomeStack.cardNamed(home, HomeStack.PREFERENCES_CARD);
		if(prefs!=null){
			OField levelField = prefs.GetFld("User Level");
			try { if(levelField!=null) level = Math.max(1, Math.min(5, Integer.parseInt(levelField.getText().trim()))); }
			catch(NumberFormatException e){ /* HyperZebra's level */ }
			OButton b = prefs.GetBtn("Power Keys");
			if(b!=null) PaintTool.setPowerKeys(b.getHilite());
			b = prefs.GetBtn("Text Arrows");
			if(b==null) b = prefs.GetBtn("Arrow Keys in Text"); //HyperCard 2.x Home's name
			if(b!=null) compatibilityProperties.put("textarrows", Boolean.toString(b.getHilite()));
			b = prefs.GetBtn("Blind Typing");
			if(b!=null) compatibilityProperties.put("blindtyping", Boolean.toString(b.getHilite()));
			OField name = prefs.GetFld("User Name");
			TTalk.setGlobal("UserName", name==null ? "" : name.getText());
			for(String card: HomeStack.PATH_CARDS) TTalk.setGlobal(card, homePaths(home, card));
		}
		PCARD.userLevel = level;
	}
	/** The background field "Paths" of Home's search path card {@code card}; empty when there is none. */
	private static String homePaths(OStack home, String card){
		OCard cd = HomeStack.cardNamed(home, card);
		OBackground bg = cd==null ? null : home.GetBackgroundbyId(cd.bgid);
		OField field = bg==null ? null : bg.GetFld("Paths");
		if(field==null) return "";
		for(OBgFieldData data: cd.bgfldList) if(data.id==field.id) return data.text==null ? "" : data.text;
		return "";
	}

	static Point parsePaletteLocation(String value) {
		if(value==null) return null;
		String[] items = value.trim().split("\\s*,\\s*");
		if(items.length!=2) return null;
		try {
			return new Point(Integer.parseInt(items[0]), Integer.parseInt(items[1]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static Point paletteLocationOnScreen(Point cardOrigin, Point paletteLocation) {
		return new Point(cardOrigin.x+paletteLocation.x, cardOrigin.y+paletteLocation.y);
	}

	/** Opens a palette window for {@code plte} at params[1] (card coordinates; HyperCard's 10,20 without) and sends openPalette. */
	private static void openPaletteWindow(Rsrc.PlteClass plte, BufferedImage image, String[] params) {
		//ウィンドウ作成(見た目とボタンはPLTEの内容からGPictWindowが組み立てる)
		GPictWindow gpw = new GPictWindow(PCARD.pc, plte, image);
		OWindow window = new OWindow(gpw);
		//位置はカードの左上基準。省略時はHyperCardと同じく10,20
		Point location = params.length>=2 ? parsePaletteLocation(params[1]) : null;
		if(location==null) location = new Point(10,20);
		gpw.setLocation(paletteLocationOnScreen(
			getCardOriginOnScreen(), new Point(PCARD.toPhysical(location.x), PCARD.toPhysical(location.y))));
		gpw.setVisible(true);
		if(PCARD.pc.stack.curCard!=null){
			TTalk.CallMessage("openPalette", new String[]{params[0], Integer.toString(window.id)}, PCARD.pc.stack.curCard);
		}
	}

	static java.awt.Rectangle paletteButtonBounds(java.awt.Rectangle source, Point pictOrigin) {
		return new java.awt.Rectangle(source.x-pictOrigin.x, source.y-pictOrigin.y,
			source.width, source.height);
	}

	static BufferedImage normalizePaletteImage(BufferedImage source, Point pictOrigin) {
		if(source==null || pictOrigin==null || pictOrigin.x<=0 && pictOrigin.y<=0 ||
			pictOrigin.x<0 || pictOrigin.y<0 || pictOrigin.x>=source.getWidth() ||
			pictOrigin.y>=source.getHeight()) return source;

		int minX = source.getWidth();
		int minY = source.getHeight();
		for(int y=0; y<source.getHeight(); y++){
			for(int x=0; x<source.getWidth(); x++){
				if((source.getRGB(x, y)>>>24)!=0){
					if(x<minX) minX=x;
					if(y<minY) minY=y;
				}
			}
		}
		// Do not crop an already-normalized PICT merely because its PLTE
		// resource uses absolute coordinates for buttons.
		if(minX<pictOrigin.x || minY<pictOrigin.y) return source;

		int width = source.getWidth()-pictOrigin.x;
		int height = source.getHeight()-pictOrigin.y;
		BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D graphics = normalized.createGraphics();
		graphics.drawImage(source, -pictOrigin.x, -pictOrigin.y, null);
		graphics.dispose();
		return normalized;
	}
	
	
	static SendMsgListener sendMsgListener = tutil.new SendMsgListener();

	/** パレットのボタンが押された: そのメッセージ(複数行も可)をスクリプトスレッドで実行する */
	static void sendPaletteMessage(String message){
		try { TTalk.doScriptforMenu(message); } catch (xTalkException e) {}
	}
	
	class SendMsgListener implements ActionListener
	{
		@Override
		public void actionPerformed(ActionEvent e) {
			String msg = ((JComponent)e.getSource()).getName();
			try {
				TTalk.doScriptforMenu(msg);
			} catch (xTalkException e1) {
			}
		}
	}
	
	//偶数丸め
	/** SANE(HyperCard)と同じ「.5は偶数側へ」の丸め。負数や2^31を超える値でも正しく丸める */
	static long IEEE_Round(double v){
		return (long)Math.rint(v);
	}

	//整数値のdoubleを指数表記や".0"なしの文字列にする(trunc/round用)
	static String wholeNumberString(double v){
		if(Double.isNaN(v) || Double.isInfinite(v)) return TTalk.formatRawNumber(v);
		if(Math.abs(v)<9.007199254740992E15) return Long.toString((long)v);
		return new java.math.BigDecimal(v).toPlainString();
	}
	
	/** disk <name> (the diskSpace of disk X, there is a disk X): the disks as the 68K programs see them (FileSystem68k) —
	 *  "HyperZebra HD" (the folder of the virtual System Folder), "Macintosh HD" (the root), "Home", the volumes under /Volumes —
	 *  or an absolute path. null when there is no such disk */
	static File diskFolder(String name){
		if(name==null || name.isEmpty()) return null;
		if(name.equalsIgnoreCase(FileSystem68k.BOOT_VOLUME_NAME)) return QuickTimeService.systemFolder().getParentFile();
		if(name.equalsIgnoreCase("Home")) return new File(System.getProperty("user.home"));
		if(name.equalsIgnoreCase("Macintosh HD")) return new File(File.listRoots()!=null && File.listRoots().length>0 ? File.listRoots()[0].getPath() : "/");
		for(DiskImages.Mounted m : DiskImages.mounted()) if(m.name.equalsIgnoreCase(name) && m.folder.isDirectory()) return m.folder;
		File[] roots = File.listRoots();
		if(roots!=null) for(File root : roots){ if(name.equalsIgnoreCase(root.getPath().replace("\\","").replace(":",""))) return root; }
		File volume = new File(name.startsWith("/") ? name : "/Volumes/"+name);
		return volume.isDirectory() ? volume : null;
	}

	/** picture <file>,file / open picture: a PICT file or one Java reads (PNG, JPEG, GIF…). null when the file is neither */
	static BufferedImage loadPictureFile(String path){
		File f = new File(path);
		if(!f.isFile()) return null;
		try{
			BufferedImage bi = ImageIO.read(f);
			if(bi!=null) return bi;
		}catch(IOException | RuntimeException e){ /* not an image Java knows */ }
		try{ return PictureFile.loadPICT(path); }
		catch(RuntimeException e){ return null; } //a file that is not a PICT parses to nonsense sizes
	}

	/** カードウインドウ基準の論理座標で見た、picture/paletteウインドウの位置 */
	static java.awt.Rectangle pictureWindowRect(GPictWindow gpw){
		Point origin = gpw.isPaletteWindow() ? getCardOriginOnScreen() : windowOrigin();
		return new java.awt.Rectangle(PCARD.toLogical(gpw.getX()-origin.x), PCARD.toLogical(gpw.getY()-origin.y), PCARD.toLogicalLength(gpw.getWidth()), PCARD.toLogicalLength(gpw.getHeight()));
	}

	/** The message box in the card-window coordinates "set the loc of msg" uses. */
	static java.awt.Rectangle messageBoxRect(){
		Point origin = getCardOriginOnScreen();
		return new java.awt.Rectangle(PCARD.toLogical(GMsg.msg.getX()-origin.x), PCARD.toLogical(GMsg.msg.getY()-origin.y), PCARD.toLogicalLength(GMsg.msg.getWidth()), PCARD.toLogicalLength(GMsg.msg.getHeight()));
	}

	/** loc/rect/left/…/visible of HyperCard's Scroll/FatBits windoids: kept, nothing is shown. False for another property. */
	private static boolean setHcWindowProperty(OHcWindow w, String prpty, String value) throws xTalkException {
		switch(prpty){
			case "visible": w.setVisible(propertyBoolean("visible", value)); return true;
			case "loc": case "location": case "topleft": {
				String[] xy = value.split(",", -1);
				if(xy.length!=2) throw new xTalkException("Coordinates are required here");
				w.left = propertyInteger(prpty, xy[0].trim()); w.top = propertyInteger(prpty, xy[1].trim()); return true;
			}
			case "rect": case "rectangle": {
				String[] p = value.split(",", -1);
				if(p.length!=4) throw new xTalkException("rect requires four coordinates");
				int x = propertyInteger(prpty, p[0].trim()), y = propertyInteger(prpty, p[1].trim());
				w.width = propertyInteger(prpty, p[2].trim())-x; w.height = propertyInteger(prpty, p[3].trim())-y; w.left = x; w.top = y; return true;
			}
			case "left": w.left = propertyInteger(prpty, value.trim()); return true;
			case "top": w.top = propertyInteger(prpty, value.trim()); return true;
			case "right": w.left = propertyInteger(prpty, value.trim())-w.width; return true;
			case "bottom": w.top = propertyInteger(prpty, value.trim())-w.height; return true;
		}
		return false;
	}

	/** left/top/…/rect of a window whose loc is its top-left corner, or null for another property. */
	static String rectProperty(java.awt.Rectangle r, String prpty){
		switch(prpty){
			case "left": return Integer.toString(r.x);
			case "top": return Integer.toString(r.y);
			case "right": return Integer.toString(r.x+r.width);
			case "bottom": return Integer.toString(r.y+r.height);
			case "topleft": case "loc": case "location": return r.x+","+r.y;
			case "botright": case "bottomright": return (r.x+r.width)+","+(r.y+r.height);
			case "rect": case "rectangle": return r.x+","+r.y+","+(r.x+r.width)+","+(r.y+r.height);
			case "width": return Integer.toString(r.width);
			case "height": return Integer.toString(r.height);
		}
		return null;
	}

	/** picture/paletteウインドウのプロパティ。該当しなければnull */
	static String getPictureWindowProperty(GPictWindow gpw, String prpty){
		java.awt.Rectangle r = pictureWindowRect(gpw);
		switch(prpty){
			case "left": return Integer.toString(r.x);
			case "top": return Integer.toString(r.y);
			case "right": return Integer.toString(r.x+r.width);
			case "bottom": return Integer.toString(r.y+r.height);
			case "topleft": return r.x+","+r.y;
			case "botright": case "bottomright": return (r.x+r.width)+","+(r.y+r.height);
			case "loc": case "location": return r.x+","+r.y; //the loc of a window is its top-left corner (HyperTalk Reference), as set loc puts it
			case "rect": case "rectangle": return r.x+","+r.y+","+(r.x+r.width)+","+(r.y+r.height);
			case "width": return Integer.toString(r.width);
			case "height": return Integer.toString(r.height);
			case "visible": return Boolean.toString(gpw.isVisible());
			case "dithering": return Boolean.toString(gpw.dithering);
			//the size of the picture itself, whatever the window's rect (HyperCard J-2.3; G-zone ! sizes its cards by them)
			case "picturewidth": return gpw.label!=null && gpw.label.bi!=null ? Integer.toString(gpw.label.bi.getWidth()) : Integer.toString(r.width);
			case "pictureheight": return gpw.label!=null && gpw.label.bi!=null ? Integer.toString(gpw.label.bi.getHeight()) : Integer.toString(r.height);
			case "globalloc": return PCARD.toLogical(gpw.getX())+","+PCARD.toLogical(gpw.getY()); //screen coordinates (HyperCard 2.2 picture windows), as the screenRect's
			case "globalrect": return PCARD.toLogical(gpw.getX())+","+PCARD.toLogical(gpw.getY())+","+(PCARD.toLogical(gpw.getX())+r.width)+","+(PCARD.toLogical(gpw.getY())+r.height);
		}
		if(gpw.isPaletteWindow()){
			switch(prpty){
				case "hilitedbutton": return Integer.toString(gpw.getHilitedButton());
				case "commands": return gpw.paletteCommands();
				case "buttoncount": return Integer.toString(gpw.paletteButtonCount());
			}
		}
		return null;
	}

	//---- pattern Nのプロパティ(画像オブジェクト風の語彙) ----
	// width/height … ドット数(ppatの規則で8/16/32/64)
	// imageData    … 1ドット4バイト(0,R,G,B)のバイナリ
	// text         … 1行1段、1ドット1文字("1"=前景インク、"0"=背景インク。色付きの
	//                ドットは明るさで丸める)。HyperCardのPATと同じ感覚で書ける
	// number/name  … 番号と"pattern N"
	static String getPatternProperty(OPattern op, String prpty) throws xTalkException {
		java.awt.image.BufferedImage img = op.image();
		if(img==null) throw new xTalkException("No such pattern");
		int w=img.getWidth(), h=img.getHeight();
		if(prpty.equals("width")) return Integer.toString(w);
		if(prpty.equals("height")) return Integer.toString(h);
		if(prpty.equals("number")) return Integer.toString(op.index+1);
		if(prpty.equals("name") || prpty.equals("short name") || prpty.equals("long name")) return op.getShortName();
		if(prpty.equals("imagedata")){
			StringBuilder sb = new StringBuilder(w*h*4);
			for(int y=0;y<h;y++) for(int x=0;x<w;x++){
				int c = img.getRGB(x,y);
				sb.append((char)0).append((char)((c>>16)&0xFF)).append((char)((c>>8)&0xFF)).append((char)(c&0xFF));
			}
			return sb.toString();
		}
		if(prpty.equals("text")){
			StringBuilder sb = new StringBuilder();
			for(int y=0;y<h;y++){
				if(y>0) sb.append('\n');
				for(int x=0;x<w;x++){
					int c = img.getRGB(x,y);
					int a=(c>>>24)&0xFF, lum=(((c>>16)&255)*299+((c>>8)&255)*587+(c&255)*114)/1000;
					sb.append(a>=128 && lum<128 ? '1' : '0');
				}
			}
			return sb.toString();
		}
		return null;
	}

	static void setPatternProperty(OPattern op, String prpty, String value) throws xTalkException {
		java.awt.image.BufferedImage img = op.image();
		if(img==null) throw new xTalkException("No such pattern");
		int w=img.getWidth(), h=img.getHeight();
		if(prpty.equals("width") || prpty.equals("height")){
			int n = propertyInteger(prpty, value);
			if(n<PatButton.MIN_SIDE || n>PatButton.MAX_SIDE || (n&(n-1))!=0)
				throw new xTalkException("{0} requires 8, 16, 32 or 64 (ppat format)", prpty);
			int nw = prpty.equals("width") ? n : w, nh = prpty.equals("height") ? n : h;
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(nw,nh,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for(int y=0;y<nh;y++) for(int x=0;x<nw;x++) out.setRGB(x,y,img.getRGB(x%w,y%h));
			op.setImage(out);
			return;
		}
		if(prpty.equals("imagedata")){
			if(value.length()!=w*h*4) throw new xTalkException("Incorrect imageData length ({0} bytes required)", (w*h*4));
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			int p=0;
			for(int y=0;y<h;y++) for(int x=0;x<w;x++){
				int r=value.charAt(p+1)&0xFF, g=value.charAt(p+2)&0xFF, b=value.charAt(p+3)&0xFF;
				p+=4;
				out.setRGB(x,y,PatternEditor.normalize(0xFF000000|(r<<16)|(g<<8)|b));
			}
			op.setImage(out);
			return;
		}
		if(prpty.equals("text")){
			String[] rows = value.split("\r?\n|\r");
			int nh = PatButton.ppatSide(rows.length), nw = 0;
			for(String r : rows) nw = Math.max(nw, r.trim().length());
			nw = PatButton.ppatSide(nw);
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(nw,nh,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for(int y=0;y<nh;y++){
				String row = y<rows.length ? rows[y].trim() : "";
				for(int x=0;x<nw;x++){
					char ch = x<row.length() ? row.charAt(x) : '0';
					boolean on = ch=='1' || ch=='#' || ch=='*' || ch=='X' || ch=='x';
					out.setRGB(x,y,on ? PatternEditor.INK_FORE : PatternEditor.INK_BACK);
				}
			}
			op.setImage(out);
			return;
		}
		throw new xTalkException("pattern has no property {0}", prpty);
	}

	//ペイントの色(brushColor/penColor=前景色、brushBackColor/penBackColor=背景色)。
	//値はHyperZebraの他の色プロパティと同じ16bitのr,g,b。背景色は"transparent"で透明になる。
	static String paintColorText(Color col){
		if(col==null) return "";
		if(col.getAlpha()==0) return "transparent";
		return col.getRed()*256+","+col.getGreen()*256+","+col.getBlue()*256;
	}
	static Color paintColorValue(String name, String value, boolean allowTransparent) throws xTalkException {
		String v = value.trim();
		if(allowTransparent && (v.equalsIgnoreCase("transparent") || v.equalsIgnoreCase("none") || v.isEmpty())) return new Color(0,0,0,0);
		String[] strs = v.split(",");
		if(strs.length!=3) throw new xTalkException("{0} must be specified as r,g,b", name);
		int r,g,b;
		try{
			r = Integer.parseInt(strs[0].trim()); g = Integer.parseInt(strs[1].trim()); b = Integer.parseInt(strs[2].trim());
		}catch(NumberFormatException e){
			throw new xTalkException("{0} must be specified as r,g,b", name);
		}
		if(r<0||r>65535 || g<0||g>65535 || b<0||b>65535) throw new xTalkException("Color components must be between 0 and 65535");
		return new Color(r/256, g/256, b/256);
	}

	public static String getProperty(
			String inProperty, OObject obj, OObject target, MemoryData memData) throws xTalkException
	{
		String prpty=inProperty.toLowerCase();
		//the short/long number of an object is its number (HyperCard J-2.3; 地雷ゲーム: short number of the target + …)
		if(prpty.endsWith(" number") && prpty.matches("(short|long|abbr|abbrev|abbreviated) number")) prpty = "number";
		BuiltinCommandMessage.noteProperty(prpty);
		if(obj instanceof OWindow && ((OWindow)obj).dlog instanceof VariableWatcher && (prpty.equals("hbarloc") || prpty.equals("vbarloc"))){
			VariableWatcher w = (VariableWatcher)((OWindow)obj).dlog;
			return Integer.toString(PCARD.toLogical(prpty.equals("hbarloc") ? w.hBarLoc() : w.vBarLoc()));
		}
		//the debugger's windows where they are, in the coordinates `set` places them in (the card window's)
		if(obj instanceof OWindow && ((OWindow)obj).dlog!=null && obj!=OWindow.msgwindow){
			java.awt.Rectangle b = ((OWindow)obj).dlog.getBounds();Point o = windowOrigin();
			int l = PCARD.toLogical(b.x-o.x), t = PCARD.toLogical(b.y-o.y), w = PCARD.toLogical(b.width), h = PCARD.toLogical(b.height);
			switch(prpty){
			case "rect": case "rectangle": return l+","+t+","+(l+w)+","+(t+h);
			case "loc": case "location": case "topleft": return l+","+t;
			case "width": return Integer.toString(w);
			case "height": return Integer.toString(h);
			default: break;
			}
		}
		if(prpty.equals("panel") && (obj instanceof OButton || obj instanceof OField || obj instanceof OShape)) return panelOfPart(obj);
		//HyperCard 2.2+: the language of an object's script; a compiled AppleScript (OSA) one is kept but never run (OsaScript)
		if(prpty.equals("scriptinglanguage") && hasScriptingLanguage(obj)) return obj.osaScript==null ? "HyperTalk" : obj.osaScript.languageName();
		if(obj instanceof OShape){
			OShape shape=(OShape)obj;
			if(prpty.equals("number"))return Integer.toString(shape.getNumber());
			if(prpty.equals("text"))return shape.text;
			if(prpty.equals("path"))return shape.pathData;
			if(prpty.equals("fill"))return shape.fillColor==null?"none":String.format("#%08X",shape.fillColor.getRGB());
			if(prpty.equals("stroke"))return shape.strokeColor==null?"none":String.format("#%08X",shape.strokeColor.getRGB());
			if(prpty.equals("strokewidth"))return Float.toString(shape.strokeWidth);
			if(prpty.equals("opacity"))return Integer.toString(Math.round(shape.opacity*100));
			if(prpty.equals("rotation"))return ShapeFilterPanel.num(shape.rotation);
			String value=shape.scriptProperty(prpty);if(value!=null)return value;
		}
		if(obj instanceof OPanel){
			OPanel panel=(OPanel)obj;
			int x=panel.getLeft(), y=panel.getTop(), w=panel.getWidth(), h=panel.getHeight();
			if(prpty.equals("visible")) return Boolean.toString(panel.getVisible());
			if(prpty.equals("left")) return Integer.toString(x);
			if(prpty.equals("top")) return Integer.toString(y);
			if(prpty.equals("right")) return Integer.toString(x+w);
			if(prpty.equals("bottom")) return Integer.toString(y+h);
			if(prpty.equals("width")) return Integer.toString(w);
			if(prpty.equals("height")) return Integer.toString(h);
			if(prpty.equals("loc") || prpty.equals("location")) return (x+w/2)+","+(y+h/2);
			if(prpty.equals("topleft")) return x+","+y;
			if(prpty.equals("rect") || prpty.equals("rectangle")) return x+","+y+","+(x+w)+","+(y+h);
			if(prpty.equals("number")) return Integer.toString(panel.getNumber());
			//Panel Data: the text (like a button's contents), the named values as a dictionary, their keys
			if(prpty.equals("text")) return panel.getText();
			if(prpty.equals("data")) return panel.allData();
			if(prpty.equals("datakeys")) return panel.dataKeys();
			if(prpty.equals("script")) return panel.getScriptText();
		}
		if(obj==OToolWindow.toolwindow && PCARD.pc!=null && PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
			//the Tools palette where it is, in the card window's coordinates set loc/topLeft place it in (ぺいんと+ saves it)
			java.awt.Rectangle b = PCARD.pc.toolbar.tb.getBounds();Point o = windowOrigin();
			int l = PCARD.toLogical(b.x-o.x), t = PCARD.toLogical(b.y-o.y);
			String v = rectProperty(new java.awt.Rectangle(l, t, PCARD.toLogical(b.width), PCARD.toLogical(b.height)), prpty);
			if(v!=null) return v;
		}
		if(obj instanceof OHcWindow){ //HyperCard's Scroll/FatBits windoids: the rect a script gave them (loc = top left, as for windows)
			OHcWindow w = (OHcWindow)obj;
			String v = rectProperty(new java.awt.Rectangle(w.left, w.top, w.width, w.height), prpty);
			if(v!=null) return v;
		}
		//every name form of a window is its title (HyperCard J-2.3, measured: the name, short, long and abbrev name of window "Message"
		//are "Message", of the card window the stack's name). Dartmouth's Index compares the name of window id N with "Index"
		if((obj instanceof OWindow || obj==OMsg.msg || obj instanceof OHcWindow || obj instanceof OXWindow) && isNameForm(prpty)){
			if(obj instanceof OXWindow || obj instanceof OHcWindow) return obj.name;
			for(Object[] w : windowEntries()) if(w[1]==obj) return (String)w[0];
			return obj==OMsg.msg ? "Message" : obj.name;
		}
		if(obj instanceof OXWindow) return ((OXWindow)obj).getProperty(inProperty); //68K外部ウィンドウ: 外部コマンドに xGetPropEvt
		if(obj instanceof OWindow && prpty.equals("zoomed")) return "false";
		if(obj instanceof OWindow && ((OWindow)obj).frame!=null && ((OWindow)obj).frame==PCARD.pc){
			String geometry = cardWindowProperty(prpty);
			if(geometry!=null) return geometry;
		}
		OButton btn=null;
		OField fld=null;
		//OCard cd=null;
		//OBackground bg=null;
		//OStack stk=null;
		
		/*if(obj==null) {
			return CallSystemFunction(prpty, null, target, memData, true).theResult;
		}*/

		if(obj instanceof OMenuItem){
			String v = getMenuItemProperty((OMenuItem)obj, prpty);
			if(v!=null) return v;
		}
		if(obj instanceof OMenu){
			OMenu om = (OMenu)obj;
			if(prpty.equals("english name")) return PCARD.pc.intl.getEngText(om.menu.getText());
			if(prpty.equals("enabled")) return Boolean.toString(om.menu.isEnabled());
		}
		if(obj instanceof OStack && prpty.equals("usermodify")) return compatibilityProperty("userModify");
		if(obj instanceof OPattern){
			String v = getPatternProperty((OPattern)obj, prpty);
			if(v!=null) return v;
		}
		if(0==obj.objectType.compareTo("button"))
			btn=(OButton)obj;
		if(0==obj.objectType.compareTo("field"))
			fld=(OField)obj;
		if(obj instanceof OWindow && ((OWindow)obj).movieHandle!=null){
			//the currTime / duration / loop / soundVolume / movie … of movie N (HyperCard 2.4 Movie XCMD)
			String v = TQTMovie.property(((OWindow)obj).movieHandle, prpty);
			if(v!=null) return v;
			if(prpty.equals("windowstyle")) return ((OWindow)obj).gpw==null ? "windoid" : ((OWindow)obj).gpw.isUndecorated() ? "rect" : "windoid";
		}
		if(obj instanceof OWindow && ((OWindow)obj).gpw!=null){
			String v = getPictureWindowProperty(((OWindow)obj).gpw, prpty);
			if(v!=null) return v;
		}
		if(obj instanceof OMsg && GMsg.msg!=null){
			String v = rectProperty(messageBoxRect(), prpty);
			if(v!=null) return v;
		}
		/*if(0==obj.objectType.compareTo("card"))
			cd=(OCard)obj;
		if(0==obj.objectType.compareTo("background"))
			bg=(OBackground)obj;
		if(0==obj.objectType.compareTo("stack"))
			stk=(OStack)obj;*/
		
		if(0==prpty.compareTo("autohilite")){
			return btn==null ? "" : Boolean.toString(btn.getAutoHilite());
		}
		else if(0==prpty.compareTo("autoselect")){
			return fld==null ? "" : Boolean.toString(fld.autoSelect);
		}
		else if(0==prpty.compareTo("autotab")){
			return fld==null ? "" : Boolean.toString(fld.autoTab);
		}
		else if(0==prpty.compareTo("dontwrap")){
			return fld==null ? "" : Boolean.toString(fld.dontWrap);
		}
		else if(0==prpty.compareTo("fixedlineheight")){
			return fld==null ? "" : Boolean.toString(fld.fixedLineHeight);
		}
		else if(0==prpty.compareTo("multiple") || 0==prpty.compareTo("multiplelines")){
			return fld==null ? "" : Boolean.toString(fld.multipleLines);
		}
		else if(0==prpty.compareTo("sharedtext")){
			return fld==null ? "" : Boolean.toString(fld.sharedText);
		}
		else if(0==prpty.compareTo("showlines")){
			return fld==null ? "" : Boolean.toString(fld.showLines);
		}
		//表(tableStyle)のフィールド
		else if(0==prpty.compareTo("tablestyle")){
			return fld==null ? "" : Boolean.toString(fld.tableStyle);
		}
		else if(0==prpty.compareTo("sortable")){
			return fld==null ? "" : Boolean.toString(fld.sortable);
		}
		else if(0==prpty.compareTo("gridlines")){
			return fld==null ? "" : Boolean.toString(fld.gridLines);
		}
		else if(0==prpty.compareTo("columnwidths")){
			return fld==null ? "" : fld.columnWidths;
		}
		else if(0==prpty.compareTo("columnnames")){
			return fld==null ? "" : fld.columnNames;
		}
		else if(0==prpty.compareTo("selectedcolumn")){
			return fld==null || fld.selectedColumn<1 ? "" : Integer.toString(fld.selectedColumn);
		}
		//アウトライン(outlineStyle)のフィールド
		else if(0==prpty.compareTo("outlinestyle")){
			return fld==null ? "" : Boolean.toString(fld.outlineStyle);
		}
		else if(0==prpty.compareTo("outlinelevel")){
			return fld==null ? "" : Integer.toString(fld.outlineLevel);
		}
		else if(0==prpty.compareTo("selectedpath")){
			return fld==null || !fld.outlineStyle ? "" : fld.selectedPath;
		}
		else if(0==prpty.compareTo("selectedlines")){
			return fld==null ? "" : tableRowList(fld);
		}
		else if(0==prpty.compareTo("widemargins")){
			return fld==null ? "" : Boolean.toString(fld.wideMargins);
		}
		else if(0==prpty.compareTo("sharedhilite")){
			return btn==null ? "" : Boolean.toString(btn.sharedHilite);
		}
		else if(0==prpty.compareTo("showname")){
			return btn==null ? "" : Boolean.toString(btn.showName);
		}
		else if(0==prpty.compareTo("family")){
			return btn==null ? "" : Integer.toString(btn.group);
		}
		else if(0==prpty.compareTo("titlewidth")){
			return btn==null ? "" : Integer.toString(btn.titleWidth);
		}
		else if(0==prpty.compareTo("textalign")){
			if(btn!=null) return textAlignName(btn.textAlign);
			if(fld!=null) return textAlignName(fld.textAlign);
			return "";
		}
		else if(0==prpty.compareTo("textfont")){
			if(btn!=null) return btn.textFont;
			if(fld!=null) return fld.textFont;
			return "";
		}
		else if(0==prpty.compareTo("textheight")){
			if(btn!=null) return Integer.toString(btn.textHeight);
			if(fld!=null) return Integer.toString(fld.textHeight);
			return "";
		}
		else if(0==prpty.compareTo("textsize")){
			if(btn!=null) return Integer.toString(btn.textSize);
			if(fld!=null) return Integer.toString(fld.textSize);
			return "";
		}
		else if(0==prpty.compareTo("partnumber")){
			if((btn!=null || fld!=null) && obj.parent instanceof OCardBase){
				return Integer.toString(((OCardBase)obj.parent).GetNumberofParts(obj));
			}
			return "";
		}
		else if(0==prpty.compareTo("owner") || 0==prpty.compareTo("long owner") || 0==prpty.compareTo("short owner") || 0==prpty.compareTo("abbrev owner")){
			//ownerはカード(所属バックグラウンド)とウィンドウにだけある(HyperCard)
			//Scroll/FatBits too (HC J-2.3 probe Aol Owner Window List; AOL TIFF Stack asks every one of the windows)
			if(obj==OMsg.msg || obj==OToolWindow.toolwindow || obj==OPatternWindow.patternwindow || obj instanceof OHcWindow) return "HyperCard";
			if(!(obj instanceof OCard) && !(obj instanceof OWindow)) throw new xTalkException("owner is available only for cards and windows");
			if(obj instanceof OWindow) return windowOwner((OWindow)obj);
			OObject owner = obj.ownerObject();
			if(owner==null) return "";
			if(prpty.startsWith("long")) return owner.getLongName();
			if(prpty.startsWith("short")) return owner.name==null || owner.name.isEmpty() ? owner.getShortName() : owner.name;
			return owner.getShortName();
		}
		else if(0==prpty.compareTo("dontsearch")){
			if(fld!=null) return Boolean.toString(fld.dontSearch);
			if(obj instanceof OCard) return Boolean.toString(((OCard)obj).dontSearch);
			if(obj instanceof OBackground) return Boolean.toString(((OBackground)obj).dontSearch);
			return "";
		}
		else if(0==prpty.compareTo("marked")){
			return obj instanceof OCard ? Boolean.toString(((OCard)obj).marked) : "";
		}
		else if(0==prpty.compareTo("icon")){
			if(btn!=null) {
				if(btn.iconURI!=null && !btn.iconURI.toString().isEmpty()) return btn.iconURI.toString();
				else return Integer.toString(btn.icon);
			}
			else return "";
		}
		else if(0==prpty.compareTo("hilite") || 0==prpty.compareTo("highlight") || 0==prpty.compareTo("highlite") || 0==prpty.compareTo("hilight")){
			if(btn!=null) {
				return btn.getHilite()?"true":"false";
			}
			//a (list) field: whether a line is hilited (HyperCard 2.4 answers false after "set the hilite of fld to false")
			else if(fld!=null) return fld.getSelectedLine()>0 ? "true" : "false";
			else return "";
		}
		else if(0==prpty.compareTo("enabled")){
			//enabled はボタンとメニュー/メニュー項目だけ(フィールドは lockText)
			if(btn!=null) {
				return (btn.enabled)?"true":"false";
			}
			else if(fld!=null) throw new xTalkException("Fields have no enabled property (use lockText)");
			else return "";
		}
		else if(0==prpty.compareTo("selectedtext")){
			if(fld!=null) return fld.getSelectedText();
			if(btn!=null) return btn.getSelectedText();
			else return "";
		}
		else if(0==prpty.compareTo("selectedline")){
			//the selectedLine of <listField|popupButton>: "line N [to M] of card field N"(番号形式)。選択が無ければ空
			if(fld!=null) return fieldSelectedLineRef(fld);
			if(btn!=null){
				int line = btn.getSelectedLine();
				if(line<1) return "";
				OCardBase owner = (OCardBase)btn.parent;
				int n = owner.btnList.indexOf(btn)+1;
				return "line "+line+" of "+(owner.objectType.equals("background") ? "background button " : "card button ")+n;
			}
			return "";
		}
		else if(0==prpty.compareTo("selectedfield")){
			//入力フォーカスのあるフィールドを検索
			//cd
			for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
				OField field = PCARD.pc.stack.curCard.fldList.get(i);
				JComponent compo = field.getComponent();
				if(compo!=null && compo.isFocusOwner()){
					return field.getShortName();
				}
			}
			//bg
			for(int i=0; i<PCARD.pc.stack.curCard.bg.fldList.size(); i++){
				OField field = PCARD.pc.stack.curCard.bg.fldList.get(i);
				JComponent compo = field.getComponent();
				if(compo!=null && compo.isFocusOwner()){
					return field.getShortName();
				}
			}
			return "";
		}
		/*else if(0==prpty.compareTo("selectedbutton")){
			//ファミリーの中で選択されたボタン
			return "";
		}*/
		else if(obj instanceof OCardBase && (prpty.equals("left") || prpty.equals("top") || prpty.equals("right") || prpty.equals("bottom")
				|| prpty.equals("botright") || prpty.equals("bottomright") || prpty.equals("topleft") || prpty.equals("loc") || prpty.equals("location"))){
			//カードの矩形は 0,0,幅,高さ(Readymade Buttons: put bottom of this cd - 30 into floor; UxGrowCard puts its grow button
			//at the bottomRight of this card - it was 0,0, which took the button off the card)
			OStack st = ((OCardBase)obj).stack;
			if(prpty.equals("right")) return Integer.toString(st.width);
			if(prpty.equals("bottom")) return Integer.toString(st.height);
			if(prpty.equals("botright") || prpty.equals("bottomright")) return st.width+","+st.height;
			if(prpty.equals("topleft")) return "0,0";
			if(prpty.equals("loc") || prpty.equals("location")) return (st.width/2)+","+(st.height/2);
			return "0";
		}
		else if(0==prpty.compareTo("left")){
			return Integer.toString(obj.left);
		}
		else if(0==prpty.compareTo("top")){
			return Integer.toString(obj.top);
		}
		else if(0==prpty.compareTo("right")){
			return Integer.toString(obj.left+obj.width);
		}
		else if(0==prpty.compareTo("bottom")){
			return Integer.toString(obj.top+obj.height);
		}
		else if(0==prpty.compareTo("botright") || 0==prpty.compareTo("bottomright")){
			return Integer.toString(obj.left+obj.width)+","+Integer.toString(obj.top+obj.height);
		}
		else if(0==prpty.compareTo("topleft")){
			return Integer.toString(obj.left)+","+Integer.toString(obj.top);
		}
		else if(0==prpty.compareTo("loc") || 0==prpty.compareTo("location")){
			return Integer.toString(obj.left+obj.width/2)+","+Integer.toString(obj.top+obj.height/2);
		}
		else if(0==prpty.compareTo("rect") || 0==prpty.compareTo("rectangle")){
			if(obj instanceof OCardBase) return "0,0,"+((OCardBase)obj).stack.width+","+((OCardBase)obj).stack.height;
			return Integer.toString(obj.left)+","+Integer.toString(obj.top)+","+Integer.toString(obj.left+obj.width)+","+Integer.toString(obj.top+obj.height);
		}
		else if(0==prpty.compareTo("width")){
			if(obj instanceof OCardBase) return Integer.toString(((OCardBase)obj).stack.width);
			return Integer.toString(obj.width);
		}
		else if(0==prpty.compareTo("height")){
			if(obj instanceof OCardBase) return Integer.toString(((OCardBase)obj).stack.height);
			return Integer.toString(obj.height);
		}
		else if(0==prpty.compareTo("visible")){
			return (obj.getVisible()==true)?"true":"false";
		}
		else if(0==prpty.compareTo("locktext")){
			if(obj.objectType.equals("field")){
				return (obj.enabled)?"true":"false";
			}
			else throw new xTalkException("lockText can only be read from fields");
		}
		else if(0==prpty.compareTo("showpict")){
			if(obj.objectType.equals("card")||obj.objectType.equals("background")){
				return (((OCardBase)obj).showPict==true)?"true":"false";
			}
			else throw new xTalkException("showPict can only be read from cards and backgrounds");
		}
		else if(0==prpty.compareTo("short name")){
			//名前のないオブジェクトのshort nameは、HyperCardと同様に"card button id 3"の形
			return obj.name.isEmpty() ? obj.getShortName() : obj.name;
		}
		else if(0==prpty.compareTo("name") && (obj instanceof OWindow || obj==OMsg.msg)){
			//the name of window K: ウィンドウの名前そのもの(Power Tools: if the name of window K is "Pelican")
			for(Object[] w : windowEntries()) if(w[1]==obj) return (String)w[0];
			return obj==OMsg.msg ? "Message" : obj.name;
		}
		else if(0==prpty.compareTo("name")){
			return obj.getShortName();
		}
		else if(0==prpty.compareTo("long name")){
			return obj.getLongName();
		}
		else if(0==prpty.compareTo("id")){
			//HyperCard J-2.3: the id of this card = "card id 3001"(HyperPiece 2 は word 3 of id of this cd で番号を取る)。
			//バックグラウンド・ボタン・フィールドは番号だけ。番号だけが欲しいときは the short id
			if(obj instanceof OCard) return "card id "+obj.id;
			//the card window / the message box: the WindowPtr externals see (HyperCard answers its window's address)
			if(obj==OWindow.cdwindow || obj instanceof OWindow && ((OWindow)obj).frame!=null && ((OWindow)obj).frame==PCARD.pc) return Integer.toString(Xcmd68k.CARD_WINDOW_RECORD);
			if(obj==OMsg.msg || obj==OWindow.msgwindow) return Integer.toString(Xcmd68k.MESSAGE_WINDOW_RECORD);
			return Integer.toString(obj.id);
		}
		else if(0==prpty.compareTo("long id")){
			//HyperCard J-2.3: ボタン/フィールドの long id / abbr id は番号だけ
			if(btn!=null || fld!=null) return Integer.toString(obj.id);
			return obj.getLongId();
		}
		else if(0==prpty.compareTo("abbrev name") || 0==prpty.compareTo("abbreviated name") || 0==prpty.compareTo("abbr name")){
			return obj.getShortName();
		}
		else if(0==prpty.compareTo("short id")){
			return Integer.toString(obj.id);
		}
		else if(0==prpty.compareTo("abbrev id") || 0==prpty.compareTo("abbreviated id") || 0==prpty.compareTo("abbr id")){
			if(btn!=null || fld!=null) return Integer.toString(obj.id);
			return obj.getIdName();
		}
		else if(0==prpty.compareTo("number")){
			if(fld!=null) return Integer.toString(((OCardBase) fld.parent).GetNumberof(fld));
			if(btn!=null) return Integer.toString(((OCardBase) btn.parent).GetNumberof(btn));
			if(obj.objectType.equals("card")){
				//スタック内でのカードの順番(1始まり)
				OStack st = ((OCard)obj).stack!=null ? ((OCard)obj).stack : PCARD.pc.stack;
				int index = st.cardIdList==null ? -1 : st.cardIdList.indexOf(obj.id);
				return index<0 ? "" : Integer.toString(index+1);
			}
			if(obj.objectType.equals("background")){
				//HyperCard のバックグラウンド一覧での番号(1始まり、OStack.backgroundsInOrder)。カードの並びには依存しない
				OStack st = ((OBackground)obj).stack!=null ? ((OBackground)obj).stack : PCARD.pc.stack;
				int n = st.GetNumberof((OBackground)obj);
				return n<=0 ? "" : Integer.toString(n);
			}
			if(obj instanceof OWindow){
				int i = OWindow.list.indexOf(obj);
				return i<0 ? "1" : Integer.toString(i+1);
			}
			else {
				System.out.println("未定義のオブジェクトプロパティ:"+prpty);
				return "";
			}
		}
		else if(0==prpty.compareTo("script")){
			//HyperCard は字下げを保存せず、読み出すときに整える(TTalk.formatScript)
			return TTalk.formatScript(obj.scriptList);
		}
		else if(0==prpty.compareTo("version") || 0==prpty.compareTo("long version") || 0==prpty.compareTo("short version")){
			//the version of HyperCard は the long version と同じ8桁("02418000")、
			//the version of stack は「作成/圧縮/最終変更時のHyperCardのバージョン,最終変更日時(秒)」の5項目。
			//HyperCard 2.4: the [long|short] version of HyperCard / stack (the short one is "2.4.1"-style)
			if(obj.objectType.equals("hypercard")) return prpty.equals("short version") ? PCARD.version : PCARD.hcVersionCode(PCARD.longVersion);
			if(obj.objectType.equals("stack")){
				//HyperCard J-2.3: "02218000,02218000,02218000,02308000,3874211277" = 作成した版, 最後に整理した版,
				//整理以降に変更した最も古い版, 最後に変更した版, 最終変更日時(秒)
				OStack st = (OStack)obj;
				String cur = PCARD.hcVersionCode(PCARD.longVersion);
				String created = st.createdByVersion==null ? cur : PCARD.hcVersionCode(st.createdByVersion);
				String compacted = st.lastCompactedVersion==null ? created : PCARD.hcVersionCode(st.lastCompactedVersion);
				String first = st.firstEditedVersion==null ? compacted : PCARD.hcVersionCode(st.firstEditedVersion);
				String last = st.changed || st.lastEditedVersion==null ? cur : PCARD.hcVersionCode(st.lastEditedVersion);
				Calendar modified = Calendar.getInstance();
				if(!st.changed && st.file!=null && st.file.exists()) modified.setTimeInMillis(st.file.lastModified());
				return created+","+compacted+","+first+","+last+","+HCDateTime.toMacSeconds(modified);
			}
			System.out.println("未定義のオブジェクトプロパティ:"+prpty);
			return "";
		}
		else if(0==prpty.compareTo("freesize")){
			if(obj.objectType.equals("stack")){
				return "0";
			}
			else System.out.println("未定義のオブジェクトプロパティ:"+prpty);
		}
		else if(0==prpty.compareTo("style")){
			if(fld!=null) {
				//表・アウトラインはスタイルの一つとして選ぶ(枠はstyleのまま)
				if(fld.tableStyle) return "table";
				if(fld.outlineStyle) return "outline";
				switch(fld.style){
				case 0: return "transparent"; //旧データのstyle 0はtransparentとして報告する
				case 1: return "transparent";
				case 2: return "opaque";
				case 3: return "rectangle";
				case 4: return "shadow";
				case 5: return "scrolling";
				default: return "???";
				}
			}
			else if(btn!=null) {
				switch(btn.style){
				case 0: return "standard";
				case 1: return "transparent";
				case 2: return "opaque";
				case 3: return "rectangle";
				case 4: return "shadow";
				case 5: return "roundRect";
				case 6: return "default";
				case 7: return "oval";
				case 8: return "popup";
				case 9: return "checkBox";
				case 10: return "radioButton";
				default: return "???";
				}
			}
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("textstyle")){
			if(fld!=null) return textStyleName(fld.textStyle);
			else if(btn!=null) return textStyleName(btn.textStyle);
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("cantmodify")){
			if(obj instanceof OStack) return Boolean.toString(((OStack)obj).cantModify);
			if(obj instanceof OCard) return Boolean.toString(((OCard)obj).cantModify);
			if(obj instanceof OBackground) return Boolean.toString(((OBackground)obj).cantModify);
			return "";
		}
		else if(0==prpty.compareTo("cantabort")){
			if(obj.objectType.equals("stack")){
				return (((OStack)obj).cantAbort==true)?"true":"false";
			}
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("cantdelete")){
			if(obj instanceof OStack) return Boolean.toString(((OStack)obj).cantDelete);
			if(obj instanceof OCard) return Boolean.toString(((OCard)obj).cantDelete);
			if(obj instanceof OBackground) return Boolean.toString(((OBackground)obj).cantDelete);
			return "";
		}
		else if(0==prpty.compareTo("cantpeek")){
			return obj instanceof OStack ? Boolean.toString(((OStack)obj).cantPeek) : "";
		}
		else if(0==prpty.compareTo("scroll")){
			if(fld!=null) return Integer.toString(fld.scroll);
			if(obj==OWindow.cdwindow || obj instanceof OCard){
				//the scroll of card window: the card point at the window's top left (0,0 unless the window is smaller than the card)
				Point s = PCARD.pc.cardScroll();
				return s.x+","+s.y;
			}
			if(obj instanceof OWindow && ((OWindow)obj).gpw!=null){
				GPictWindow gpw = ((OWindow)obj).gpw;
				return gpw.getScrollX()+","+gpw.getScrollY();
			}
			return "";
		}
		else if(0==prpty.compareTo("zoomed")){
			if(obj instanceof OWindow) return Boolean.toString(((OWindow)obj).isZoomed());
			return "false";
		}
		else if(0==prpty.compareTo("rate")){
			if(obj instanceof OWindow){
				OWindow window=(OWindow)obj;
				if(window.movieHandle!=null) return Integer.toString(TQTMovie.rate(window.movieHandle));
				if(window.mp3!=null) return window.mp3.isPlaying()?"1":"0";
			}
			return "";
		}
		else if(0==prpty.compareTo("blendingmode")){
			if(obj.objectType.equals("button")){
				if (((OButton)obj).blendMode==0) return "copy";
				else if (((OButton)obj).blendMode==1) return "blend";
				else return "";
			}
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("blendinglevel")){
			if(obj.objectType.equals("button")){
				return Integer.toString(((OButton)obj).blendLevel);
			}
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("color")){
			if(obj.objectType.equals("button")){
				Color col = ((OButton)obj).color;
				return col.getRed()*256+","+col.getGreen()*256+","+col.getBlue()*256;
			}
			if(obj.objectType.equals("field")){
				Color col = ((OField)obj).color;
				return col.getRed()*256+","+col.getGreen()*256+","+col.getBlue()*256;
			}
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("hilitecolor")){
			if(obj.objectType.equals("button")){
				Color col = ((OButton)obj).hiliteColor;
				if(col==null) return ""; //空 = HyperCardと同じ反転
				return col.getRed()*256+","+col.getGreen()*256+","+col.getBlue()*256;
			}
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("backcolor")){
			if(obj.objectType.equals("button")){
				Color col = ((OButton)obj).bgColor;
				return col.getRed()*256+","+col.getGreen()*256+","+col.getBlue()*256;
			}
			if(obj.objectType.equals("field")){
				Color col = ((OField)obj).bgColor;
				return col.getRed()*256+","+col.getGreen()*256+","+col.getBlue()*256;
			}
			else System.out.println("このオブジェクトにプロパティ"+prpty+"はありません");
		}
		else if(0==prpty.compareTo("size")){
			OStack sizeOf = obj instanceof OStack && ((OStack)obj).file!=null ? (OStack)obj : PCARD.pc.stack;
			File packed = StackBundle.packedOf(sizeOf.file);
			if(packed!=null && packed.isFile()) return Long.toString(packed.length()); //a stack file (.hzb ZIP): the file's size
			File parent = new File(sizeOf.file.getParent());
			long size=0;
			File files[] = parent.listFiles();
			if (files != null) {
			    for (int i=0; i < files.length; i++) {
			        size += files[i].length();
			    }
			}
			return Long.toString(size);
		}
		else {
			System.out.println("未定義のオブジェクトプロパティ:"+prpty);
		}
		return "";
	}
	
	private static boolean isNameForm(String prpty){
		switch(prpty){
			case "name": case "short name": case "long name": case "abbrev name": case "abbreviated name": case "abbr name": return true;
			default: return false;
		}
	}

	//---- menuItem のプロパティ ----
	private static String getMenuItemProperty(OMenuItem omi, String prpty) throws xTalkException {
		JMenuItem mi = omi.item();
		if(mi==null){
				//区切り線: 名前は "-"、有効/チェックはfalse
			switch(prpty){
			case "name": case "short name": case "abbrev name": case "long name": case "english name": return "-";
			case "enabled": case "checkmark": return "false";
			case "menumessage": case "menumsg": {
				//HyperCard keeps the message given for a "-" item (put "A,-,B" into menu … with menuMsg "a,-,b": "-")
				java.awt.Component c = omi.separator();
				Object m = c instanceof javax.swing.JComponent ? ((javax.swing.JComponent)c).getClientProperty("menuMessage") : null;
				return m==null ? "" : m.toString();
			}
			case "commandchar": case "cmdchar": return "";
			case "id": return Integer.toString(omi.index+1);
			}
			throw new xTalkException("No such menu item");
		}
		switch(prpty){
		case "name": case "short name": case "abbrev name": case "long name": return TTalk.scriptMenuItemText(omi.menu, mi);
		case "english name": return PCARD.pc.intl.getEngText(mi.getText());
		case "enabled": return Boolean.toString(mi.isEnabled());
		case "checkmark": return Boolean.toString(mi instanceof javax.swing.JCheckBoxMenuItem && ((javax.swing.JCheckBoxMenuItem)mi).isSelected());
		case "menumessage": case "menumsg": { Object m = mi.getClientProperty("menuMessage"); return m==null ? "" : m.toString(); }
		case "commandchar": case "cmdchar": {
			javax.swing.KeyStroke ks = mi.getAccelerator();
			return ks==null ? "" : String.valueOf((char)ks.getKeyCode());
		}
		case "id": return Integer.toString(omi.index+1);
		case "textstyle": { Object st = mi.getClientProperty(MENU_ITEM_STYLE); return textStyleName(st instanceof Integer ? (Integer)st : 0); }
		case "markchar": {
			//HyperCard 2.x: empty, the char set, or numToChar(18) (Chicago's check mark) for an item checked with checkMark
			if(!(mi instanceof javax.swing.JCheckBoxMenuItem) || !((javax.swing.JCheckBoxMenuItem)mi).isSelected()) return "";
			Object m = mi.getClientProperty(MENU_ITEM_MARK);
			return m==null ? "\u0012" : m.toString();
		}
		}
		return null;
	}

	/** Client properties of a menu item: its textStyle (QuickDraw style bits) and the markChar set by a script */
	private static final String MENU_ITEM_STYLE = "hzTextStyle", MENU_ITEM_MARK = "hzMarkChar";

	/** The item's font for a textStyle: bold/italic/underline show, outline/shadow/condense/extend are only kept */
	private static void applyMenuItemStyle(JMenuItem mi, int style){
		java.awt.Font base = javax.swing.UIManager.getFont("MenuItem.font");
		if(base==null) base = mi.getFont();
		if(base==null) return;
		int awt = ((style & 1)!=0 ? java.awt.Font.BOLD : 0) | ((style & 2)!=0 ? java.awt.Font.ITALIC : 0);
		java.awt.Font f = base.deriveFont(awt);
		if((style & 4)!=0){
			java.util.Map<java.awt.font.TextAttribute,Object> attrs = new java.util.HashMap<>();
			attrs.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
			f = f.deriveFont(attrs);
		}
		mi.setFont(f);
	}

	static void setMenuItemProperty(OMenuItem omi, String prpty, String value) throws xTalkException {
		JMenuItem mi = omi.item();
		//a separator ("-"): HyperCard lets a loop over every item set checkMark/enabled on it too (Parara2.0's
		//"repeat with M=6 to the number of menuItems … set the checkMark of menuItem M … to false")
		if(mi==null && (prpty.equals("checkmark") || prpty.equals("enabled")) && omi.index>=0) return;
		if(mi==null && omi.separator()!=null){
			//Power Tools Menu Maker sets menuMsg/commandChar of every item, the "-" ones too
			if(prpty.equals("menumessage") || prpty.equals("menumsg")){
				if(omi.separator() instanceof javax.swing.JComponent) ((javax.swing.JComponent)omi.separator()).putClientProperty("menuMessage", value.trim());
				return;
			}
			if(prpty.equals("commandchar") || prpty.equals("cmdchar") || prpty.equals("name")) return;
		}
		if(mi==null) throw new xTalkException("No such menu item");
		switch(prpty){
		case "name":
			mi.setText(value);
			break;
		case "enabled":
			mi.setEnabled(propertyBoolean("enabled", value));
			break;
		case "checkmark": {
			boolean on = propertyBoolean("checkMark", value);
			if(!(mi instanceof javax.swing.JCheckBoxMenuItem)){
				//普通の項目はチェック表示できないので、同じ内容のチェック項目に差し替える
				javax.swing.JCheckBoxMenuItem cb = new javax.swing.JCheckBoxMenuItem(mi.getText());
				cb.setEnabled(mi.isEnabled());
				cb.setAccelerator(mi.getAccelerator());
				for(java.awt.event.ActionListener l : mi.getActionListeners()) cb.addActionListener(l);
				cb.putClientProperty("menuMessage", mi.getClientProperty("menuMessage"));
				cb.putClientProperty(MENU_ITEM_STYLE, mi.getClientProperty(MENU_ITEM_STYLE));
				if(mi.getClientProperty(MENU_ITEM_STYLE)!=null) cb.setFont(mi.getFont());
				omi.menu.remove(omi.index);
				omi.menu.insert(cb, omi.index);
				mi = cb;
			}
			((javax.swing.JCheckBoxMenuItem)mi).setSelected(on);
			mi.putClientProperty(MENU_ITEM_MARK, null);
			break;
		}
		case "markchar": {
			//set the markChar … to "x": the item is checked (HyperCard: the checkMark is then true), empty unchecks it
			String mark = value.isEmpty() ? "" : value.substring(0, 1);
			setMenuItemProperty(omi, "checkmark", mark.isEmpty() ? "false" : "true");
			JMenuItem now = omi.item();
			if(now!=null && !mark.isEmpty() && !mark.equals("\u0012")) now.putClientProperty(MENU_ITEM_MARK, mark);
			return;
		}
		case "textstyle": {
			int style = textStyleValue(value.trim());
			mi.putClientProperty(MENU_ITEM_STYLE, style);
			applyMenuItemStyle(mi, style);
			break;
		}
		case "menumessage": case "menumsg": {
			//実行は doMenu メッセージ経由(リスナーはそのまま)。ここでは値だけ更新する
			String msg = value.trim();
			mi.putClientProperty("menuMessage", msg.equalsIgnoreCase("empty") ? "" : msg);
			break;
		}
		case "commandchar": case "cmdchar":
			if(value.isEmpty()) mi.setAccelerator(null);
			else mi.setAccelerator(javax.swing.KeyStroke.getKeyStroke(Character.toUpperCase(value.charAt(0)),
				java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
			break;
		default:
			throw new xTalkException("Cannot set property {0} on a menuItem", prpty);
		}
		if(PCARD.pc.menu!=null) GMenu.menuUpdate(PCARD.pc.menu.mb);
	}

	/**
	 * The debugger's windows (Variable Watcher, Message Watcher), which scripts place like HyperCard's (rect, loc and topLeft in the
	 * card window's coordinates, as for other windows), and the Variable Watcher's bars hBarLoc and vBarLoc (Rinaldi's GlobalMaster
	 * demo: "Cannot set rect on this object", "Cannot interpret property hbarloc"). False for other properties.
	 */
	private static boolean setWatcherWindowProperty(OWindow w, String prpty, String value) throws xTalkException {
		javax.swing.JDialog d = w.dlog;
		switch(prpty){
		case "rect": case "rectangle":{
			int[] r = coordinates(value, 4, "A rectangle is required here");
			d.setBounds(windowOrigin().x+PCARD.toPhysical(r[0]), windowOrigin().y+PCARD.toPhysical(r[1]), PCARD.toPhysical(r[2]-r[0]), PCARD.toPhysical(r[3]-r[1]));
			return true;}
		case "loc": case "location": case "topleft":{
			int[] p = coordinates(value, 2, "Coordinates are required here");
			d.setLocation(windowOrigin().x+PCARD.toPhysical(p[0]), windowOrigin().y+PCARD.toPhysical(p[1]));
			return true;}
		case "hbarloc": case "vbarloc":
			if(!(d instanceof VariableWatcher)) return false;
			int v = propertyInteger(prpty, value.trim());
			if(prpty.equals("hbarloc")) ((VariableWatcher)d).setHBarLoc(PCARD.toPhysical(v)); else ((VariableWatcher)d).setVBarLoc(PCARD.toPhysical(v));
			return true;
		default: return false;
		}
	}

	/** Objects with HyperCard 2.2's scriptingLanguage property: stacks, backgrounds, cards, buttons and fields. */
	private static boolean hasScriptingLanguage(OObject obj){
		return obj instanceof OStack || obj instanceof OCardBase || obj instanceof OButton || obj instanceof OField;
	}

	/**
	 * set the scriptingLanguage of an object (HyperCard 2.2+): "HyperTalk" turns a compiled AppleScript object script into a
	 * HyperTalk one (its stored source text, usually empty, stays the script); its own language changes nothing. HyperZebra
	 * runs no OSA object scripts, so no other language can be given.
	 */
	private static void setScriptingLanguage(OObject obj, String value) throws xTalkException {
		if(value.equalsIgnoreCase("HyperTalk")){
			if(obj.osaScript==null) return;
			obj.osaScript = null;
			obj.setScript(obj.scriptList==null ? "" : String.join("\n", obj.scriptList));
			return;
		}
		if(obj.osaScript!=null && (value.equalsIgnoreCase(obj.osaScript.languageName()) || value.equals(obj.osaScript.subtype()))) return;
		throw new xTalkException("Cannot set an object's script language to {0} (HyperZebra object scripts support only HyperTalk / AppleScript object scripts are not supported)", value);
	}

	/** Integer properties an empty value sets to 0 (HyperCard J-2.3); other values that are no integer stay errors */
	private static final java.util.Set<String> EMPTY_PROPERTY_IS_ZERO = java.util.Set.of(
		"scroll", "width", "height", "left", "top", "right", "bottom", "textsize", "dragspeed");

	/** the panel of a button, field or shape: its panel's name (panel id N when it has none), empty when it is in none. */
	static String panelOfPart(OObject part){
		OPanel p = part.panel();
		if(p==null) return "";
		return p.name.isEmpty() ? "panel id "+p.id : p.name;
	}
	private static final java.util.regex.Pattern PANEL_REFERENCE =
		java.util.regex.Pattern.compile("(?i)(?:(?:card|cd|bkgnd|bg|background)\\s+)?panel\\s+(id\\s+)?(\"?)(.*?)\\2");
	/** set the panel of a button, field or shape to a panel of its own card or background: its name, "panel 2",
	 *  "panel id 3" (or the name of a panel), or empty to take it out. A panel is made first (New Panel, create panel). */
	static void setPanelOfPart(OObject part, String value) throws xTalkException {
		String v = value==null ? "" : value.trim();
		if(v.isEmpty()){ part.setPanel(null); return; }
		if(!(part.parent instanceof OCardBase)) throw new xTalkException("No such panel: {0}", v);
		OCardBase owner = (OCardBase)part.parent;
		OPanel p = owner.panelNamed(v);
		if(p==null){
			java.util.regex.Matcher m = PANEL_REFERENCE.matcher(v);
			if(m.matches()) p = TTalk.findPanel(owner, m.group(3), m.group(1)!=null, !m.group(2).isEmpty());
		}
		if(p==null) throw new xTalkException("No such panel: {0}", v);
		part.setPanel(p);
	}

	public static void SetProperty(
			OObject obj, String inProperty, String value)
	throws xTalkException 
	{
		setPropertyNow(obj, inProperty, value);
		if(REDRAWN_PART_PROPERTIES.contains(inProperty.toLowerCase(java.util.Locale.ROOT))) partChangedOnScreen(obj);
		//a stack that is not open (read on the side for "stack X", or one in use) has no autosave: HyperCard writes the change
		//into that stack's file (HC J-2.3 measured: set the script of stack "Bare Words" from another stack, then the file holds
		//it). The Prototyping Toolkit's set the script of stack "Background" changed a copy, and "go stack" opened the file without it
		if(obj instanceof OStack && PCARD.pc!=null && obj!=PCARD.pc.stack){
			OStack other = (OStack)obj;
			if(other.changed && other.saveXML==null && other.file!=null && other.cdCacheList!=null){
				boolean lockChange = inProperty.equalsIgnoreCase("cantModify"); //a stack locked now is written once more with its lock
				if(lockChange) XMLwrite.writeOnce(other);
				else XMLwrite.withoutAutosave(other).saveStackNow();
			}
		}
	}

	private static void setPropertyNow(
			OObject obj, String inProperty, String value)
	throws xTalkException 
	{
		String prpty=inProperty.toLowerCase();
		//HyperCard J-2.3 takes an empty integer as 0: set the scroll of fld 1 to indexScroll while that global is still empty
		//(the openCard of Info-Mac XI's glossary stacks), set the height of btn 1 to empty (0), the textSize (then 1)
		if(value!=null && value.trim().isEmpty() && EMPTY_PROPERTY_IS_ZERO.contains(prpty)) value = "0";
		if(prpty.equals("panel") && (obj instanceof OButton || obj instanceof OField || obj instanceof OShape)){
			setPanelOfPart(obj, value);
			return;
		}
		if(prpty.equals("scriptinglanguage") && hasScriptingLanguage(obj)){
			setScriptingLanguage(obj, value.trim());
			return;
		}
		if(obj instanceof OHcWindow && setHcWindowProperty((OHcWindow)obj, prpty, value)) return;
		if(obj instanceof OStackWindow && prpty.equals("visible")){ obj.setVisible(propertyBoolean("visible", value)); return; }
		if(obj instanceof OWindow && ((OWindow)obj).dlog!=null && obj!=OWindow.msgwindow && setWatcherWindowProperty((OWindow)obj, prpty, value)) return;
		//カードウィンドウの位置と大きさ(グローバル座標、loc は左上): 表示しているカードの部分を指定どおりにする
		if(obj instanceof OWindow && ((OWindow)obj).frame!=null && ((OWindow)obj).frame==PCARD.pc && setCardWindowProperty(prpty, value)) return;
		//set the textSize|textFont|textStyle of msg (Mono Eye): the message box's own text
		if(obj instanceof OMsg && (prpty.equals("textsize") || prpty.equals("textfont") || prpty.equals("textstyle"))){
			if(GMsg.area!=null){
				java.awt.Font f = GMsg.area.getFont();
				if(prpty.equals("textsize")) f = f.deriveFont((float)Math.max(1, propertyInteger("textSize", value.trim())*PCARD.scale()));
				else if(prpty.equals("textfont")) f = new java.awt.Font(value.trim(), f.getStyle(), f.getSize());
				else f = f.deriveFont(textStyleValue(value) & 0x03);
				GMsg.area.setFont(f);
			}
			return;
		}
		//set the top/left/right/bottom of message box (TEXTURE TIME): moves it, card-window coordinates like its loc
		if(obj instanceof OMsg && GMsg.msg!=null && (prpty.equals("left") || prpty.equals("top") || prpty.equals("right") || prpty.equals("bottom"))){
			int v = propertyInteger(prpty, value.trim());
			java.awt.Rectangle r = messageBoxRect();
			int x = prpty.equals("left") ? v : prpty.equals("right") ? v-r.width : r.x;
			int y = prpty.equals("top") ? v : prpty.equals("bottom") ? v-r.height : r.y;
			Point origin = getCardOriginOnScreen();
			GMsg.msg.setLocation(origin.x+PCARD.toPhysical(x), origin.y+PCARD.toPhysical(y));
			return;
		}
		//globalLoc/globalRect of a picture window: screen coordinates (its loc/rect are card-window coordinates)
		if(obj instanceof OWindow && ((OWindow)obj).gpw!=null && (prpty.equals("globalloc") || prpty.equals("globalrect"))){
			GPictWindow gpw = ((OWindow)obj).gpw;
			String word = value.trim().toLowerCase(java.util.Locale.ROOT);
			if(prpty.equals("globalloc") && word.matches("[a-z]+")){
				//set the globalLoc of window "splash" to mainscreen (AOL KwikInvoicer) / to card (Simple Sample Font Printer's
				//splash): HyperCard J-2.3 centres the window on the main screen below the menu bar (probe Aol GlobalLoc Word: a
				//95x65 picture at 352,277 on 800x600) for card, main, deepest, largest and any other word alike, also with the card
				//window moved to 0,40; "screen" leaves it where it is. card here = the screen the card window is on
				if(word.equals("screen")) return;
				java.awt.Rectangle s = word.equals("card") ? cardWindowScreenArea() : null;
				if(s==null) s = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
				gpw.setLocation(s.x+(s.width-gpw.getWidth())/2, s.y+(s.height-gpw.getHeight())/2);
				return;
			}
			String[] items = value.split(",");
			int[] v = new int[items.length];
			try{ for(int i=0; i<items.length; i++) v[i] = (int)Double.parseDouble(items[i].trim()); }
			catch(NumberFormatException e){ throw new xTalkException("A number is required here"); }
			if(prpty.equals("globalloc")){
				if(v.length!=2) throw new xTalkException("Coordinates are required here");
				gpw.setLocation(PCARD.toPhysical(v[0]), PCARD.toPhysical(v[1]));
			}else{
				if(v.length!=4) throw new xTalkException("A rectangle is required here");
				gpw.setBounds(belowHostMenuBar(new java.awt.Rectangle(PCARD.toPhysical(v[0]), PCARD.toPhysical(v[1]),
					Math.max(1,PCARD.toPhysical(v[2]-v[0])), Math.max(1,PCARD.toPhysical(v[3]-v[1])))));
			}
			return;
		}
		if(obj instanceof OShape){
			OShape shape=(OShape)obj;
			if(prpty.equals("visible")){shape.setVisible(propertyBoolean("visible",value));return;}
			if(prpty.equals("text")){
				if(!shape.kind.equals("text"))throw new xTalkException("text can only be set on text shapes");
				shape.text=value;java.awt.geom.Rectangle2D natural=shape.naturalTextBounds();
				shape.setSize(Math.max(1,(int)Math.ceil(natural.getWidth())),Math.max(1,(int)Math.ceil(natural.getHeight())));return;
			}
			if(prpty.equals("path")){shape.pathData=value;shape.kind="bezier";shape.repaintView();((OCardBase)shape.parent).changed=true;return;}
			if(prpty.equals("name")){shape.name=value;((OCardBase)shape.parent).changed=true;return;}
			if(prpty.equals("script")){shape.setScript(value);return;}
			if(prpty.equals("rotation")){try{float v=Float.parseFloat(value.trim());if(!Float.isFinite(v))throw new NumberFormatException();shape.rotation=OShape.normalizeAngle(v);}catch(NumberFormatException e){throw new xTalkException("rotation requires a number");}shape.repaintView();((OCardBase)shape.parent).changed=true;return;}
			if(prpty.equals("opacity")){int v=propertyInteger(prpty,value);if(v<0||v>100)throw new xTalkException("opacity must be between 0 and 100");shape.setOpacityValue(v/100f);return;}
			if(prpty.equals("fill")){shape.fillColor=OShape.color(value);shape.gradientStart=null;shape.gradientEnd=null;shape.repaintView();((OCardBase)shape.parent).changed=true;return;}
			if(prpty.equals("stroke")){shape.strokeColor=OShape.color(value);shape.repaintView();((OCardBase)shape.parent).changed=true;return;}
			if(shape.setScriptProperty(prpty,value))return;
			if(prpty.equals("strokewidth")){try{shape.strokeWidth=Float.parseFloat(value.trim());}catch(NumberFormatException e){throw new xTalkException("strokeWidth requires a number");}shape.repaintView();((OCardBase)shape.parent).changed=true;return;}
			if(prpty.equals("left")||prpty.equals("top")||prpty.equals("right")||prpty.equals("bottom")){
				int v=propertyInteger(prpty,value),x=shape.left,y=shape.top;
				if(prpty.equals("left"))x=v;else if(prpty.equals("top"))y=v;else if(prpty.equals("right"))x=v-shape.width;else y=v-shape.height;
				shape.setTopLeft(x,y);return;
			}
			if(prpty.equals("loc")||prpty.equals("location")||prpty.equals("topleft")){
				String[] xy=value.split(",",-1);if(xy.length!=2)throw new xTalkException("Coordinates are required here");
				int x=propertyInteger(prpty,xy[0].trim()),y=propertyInteger(prpty,xy[1].trim());
				if(!prpty.equals("topleft")){x-=shape.width/2;y-=shape.height/2;}shape.setTopLeft(x,y);return;
			}
			if(prpty.equals("width")){shape.setSize(propertyInteger(prpty,value),shape.height);return;}
			if(prpty.equals("height")){shape.setSize(shape.width,propertyInteger(prpty,value));return;}
			if(prpty.equals("rect")||prpty.equals("rectangle")){
				String[] p=value.split(",",-1);if(p.length!=4)throw new xTalkException("rect requires four coordinates");
				int x=propertyInteger(prpty,p[0].trim()),y=propertyInteger(prpty,p[1].trim());
				int right=propertyInteger(prpty,p[2].trim()),bottom=propertyInteger(prpty,p[3].trim());shape.setSize(right-x,bottom-y);shape.setTopLeft(x,y);return;
			}
			throw new xTalkException("Cannot set {0} on this Shape", inProperty);
		}
		if(obj instanceof OPanel){
			OPanel panel=(OPanel)obj;
			if(prpty.equals("visible")){ panel.setVisible(propertyBoolean("visible",value)); return; }
			if(prpty.equals("text")){ panel.setText(TalkCollection.display(value)); return; }
			if(prpty.equals("data")){ panel.setAllData(value); return; }
			if(prpty.equals("name")){ panel.name=value==null ? "" : value; panel.owner.changed=true; return; }
			if(prpty.equals("script")){ panel.setScript(value); return; }
			if(prpty.equals("left") || prpty.equals("top") || prpty.equals("right") || prpty.equals("bottom")){
				int v=propertyInteger(prpty,value), x=panel.getLeft(), y=panel.getTop();
				if(prpty.equals("left")) x=v;
				else if(prpty.equals("top")) y=v;
				else if(prpty.equals("right")) x=v-panel.getWidth();
				else y=v-panel.getHeight();
				panel.setTopLeft(x,y); return;
			}
			if(prpty.equals("loc") || prpty.equals("location") || prpty.equals("topleft")){
				String[] xy=value.split(",",-1);
				if(xy.length!=2) throw new xTalkException("Coordinates are required here");
				int x=propertyInteger(prpty,xy[0].trim()), y=propertyInteger(prpty,xy[1].trim());
				if(!prpty.equals("topleft")){ x-=panel.getWidth()/2; y-=panel.getHeight()/2; }
				panel.setTopLeft(x,y); return;
			}
			throw new xTalkException("Cannot set {0} on this Panel", inProperty);
		}
		if(obj instanceof OMenuItem){
			setMenuItemProperty((OMenuItem)obj, prpty, value);
			return;
		}
		if(obj instanceof OXWindow){ //68K外部ウィンドウ: 外部コマンドに xSetPropEvt を送る
			((OXWindow)obj).setProperty(inProperty, value);
			return;
		}
		if(obj instanceof OStack && prpty.equals("usermodify")){
			//set the userModify of this stack to … (Referenceの例) はグローバルプロパティと同じ
			SetProperty(null, inProperty, value);
			return;
		}
		if(obj instanceof OWindow && ((OWindow)obj).gpw!=null && ((OWindow)obj).gpw.isPaletteWindow() && prpty.equals("hilitedbutton")){
			//set the hilitedButton of window "X" to n (0でどれも点灯しない)
			((OWindow)obj).gpw.setHilitedButton(propertyInteger("hilitedButton", value));
			return;
		}
		if(obj instanceof OWindow && ((OWindow)obj).movieHandle!=null){
			//set the loop / rate / soundVolume of movie N (HyperCard 2.4); currTime and audioLevel are below
			String h = ((OWindow)obj).movieHandle;
			if(prpty.equals("loop")){ TQTMovie.setLoop(h, propertyBoolean("loop", value)); return; }
			if(prpty.equals("rate")){
				double r;
				try{ r = Double.parseDouble(value.trim()); }catch(NumberFormatException e){ throw new xTalkException("A number is required here"); }
				TQTMovie.setRate(h, r); return;
			}
			if(prpty.equals("soundvolume") || prpty.equals("volume")){ TQTMovie.setVolume(h, propertyInteger("soundVolume", value)); return; }
			if(prpty.equals("loopispalindrome") || prpty.equals("timeformat") || prpty.equals("sendmousewithinmsg") ||
				prpty.equals("closeonfinish") || prpty.equals("dragenabled") || prpty.equals("badge") || prpty.equals("fastidle") ||
				prpty.equals("callbackmsg") || prpty.equals("passmovieclick") || prpty.equals("dontErase".toLowerCase()) ||
				//the controller bar and play selection (Earth 〜紅玉の森〜: set controllerVisible of window TheMovieName to false):
				//HyperZebra's movie windows show no controller bar
				prpty.equals("controllervisible") || prpty.equals("playselection") || prpty.equals("selectionstart") ||
				prpty.equals("selectionend") || prpty.equals("highlight")) return; //accepted, no effect here
		}
		if(obj instanceof OWindow && prpty.equals("zoomed")){
			//the zoomed of the card window: カードウィンドウは常にカードの大きさなので何もしない
			propertyBoolean("zoomed", value);
			return;
		}
		if(obj instanceof OPattern){
			setPatternProperty((OPattern)obj, prpty, value);
			return;
		}
		OButton btn=null;
		OField fld=null;
		OCard cd=null;
		/*OBackground bg=null;
		OStack stk=null;*/

		if(obj==null){
			setHyperCardProperty(inProperty, value, prpty);
			return;
		}
		
		if(0==obj.objectType.compareTo("button"))
			btn=(OButton)obj;
		if(0==obj.objectType.compareTo("field"))
			fld=(OField)obj;
		if(0==obj.objectType.compareTo("card"))
			cd=(OCard)obj;
		/*if(0==obj.objectType.compareTo("background"))
			bg=(OBackground)obj;
		if(0==obj.objectType.compareTo("stack"))
			stk=(OStack)obj;*/
		
		if(0==prpty.compareTo("autohilite")){
			if(btn==null) throw new xTalkException("autoHilite can only be set on buttons");
			btn.setAutoHilite(propertyBoolean("autoHilite", value));
		}
		else if(0==prpty.compareTo("autoselect")){
			if(fld==null) throw new xTalkException("autoSelect can only be set on fields");
			fld.autoSelect = propertyBoolean("autoSelect", value);
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("autotab")){
			if(fld==null) throw new xTalkException("autoTab can only be set on fields");
			fld.autoTab = propertyBoolean("autoTab", value);
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("dontwrap")){
			if(fld==null) throw new xTalkException("dontWrap can only be set on fields");
			fld.dontWrap = propertyBoolean("dontWrap", value);
			fld.showLineWrap(!fld.dontWrap);
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("fixedlineheight")){
			if(fld==null) throw new xTalkException("fixedLineHeight can only be set on fields");
			fld.fixedLineHeight = propertyBoolean("fixedLineHeight", value);
			if(fld.fld!=null) fld.fld.repaint();
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("multiple") || 0==prpty.compareTo("multiplelines")){
			if(fld==null) throw new xTalkException("multipleLines can only be set on fields");
			fld.multipleLines = propertyBoolean("multipleLines", value);
			fld.refreshTable();
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("sharedtext")){
			if(fld==null || fld.parent==null || !fld.parent.objectType.equals("background"))
				throw new xTalkException("sharedText can only be set on background fields");
			fld.sharedText = propertyBoolean("sharedText", value);
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("tablestyle") || 0==prpty.compareTo("outlinestyle")){
			//表とアウトラインはどちらか一方(片方をtrueにするともう一方はfalse)
			if(fld==null) throw new xTalkException("{0} can only be set on fields", inProperty);
			boolean on = propertyBoolean(inProperty, value);
			boolean table = 0==prpty.compareTo("tablestyle") ? on : on ? false : fld.tableStyle;
			boolean outline = 0==prpty.compareTo("outlinestyle") ? on : on ? false : fld.outlineStyle;
			if(table!=fld.tableStyle || outline!=fld.outlineStyle){
				fld.tableStyle = table;
				fld.outlineStyle = outline;
				fld.selectedRows = new int[0];
				fld.selectedColumn = 0;
				fld.selectedPath = "";
				fld.rebuildComponent();
			}
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("outlinelevel")){
			if(fld==null) throw new xTalkException("outlineLevel can only be set on fields");
			int level = propertyInteger("outlineLevel", value);
			if(level<0) throw new xTalkException("outlineLevel must be at least 0");
			fld.outlineLevel = level;
			FieldOutline outline = fld.outline();
			if(outline!=null) PCARD.runOnEDT(outline::expandToLevel);
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("selectedpath")){
			if(fld==null || !fld.outlineStyle) throw new xTalkException("selectedPath can only be set on outline (outlineStyle) fields");
			String path = value==null ? "" : value.trim();
			if(!path.isEmpty()){
				FieldOutline.Data.Tree tree = FieldOutline.Data.parse(fld.getText());
				if(!tree.json) throw new xTalkException("selectedPath is for JSON outlines (use selectedLine for indented outlines)");
				if(tree.find(path)==null) throw new xTalkException("No such item: {0}", path);
			}
			fld.setSelectedPath(path);
		}
		else if(0==prpty.compareTo("sortable") || 0==prpty.compareTo("gridlines")){
			if(fld==null) throw new xTalkException("{0} can only be set on fields", inProperty);
			boolean on = propertyBoolean(inProperty, value);
			if(0==prpty.compareTo("sortable")) fld.sortable = on; else fld.gridLines = on;
			fld.refreshTable();
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("columnwidths")){
			if(fld==null) throw new xTalkException("columnWidths can only be set on fields");
			fld.columnWidths = FieldTable.Data.normalizeWidths(value);
			fld.refreshTable();
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("columnnames")){
			if(fld==null) throw new xTalkException("columnNames can only be set on fields");
			fld.columnNames = value==null ? "" : value;
			fld.refreshTable();
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("selectedline") || 0==prpty.compareTo("selectedlines")){
			//表の行の選択: "3" / "2,5,7" / "2 to 4" / "line 2 to 4 of …" / empty
			if(fld==null || !(fld.tableStyle || fld.outlineStyle)) throw new xTalkException("{0} can only be set on table (tableStyle) or outline (outlineStyle) fields", inProperty);
			int[] rows = parseRowList(value, FieldTable.Data.parseRows(fld.getText()).size());
			if(fld.outlineStyle && !fld.multipleLines && rows.length>1) rows = new int[]{rows[0]};
			fld.setSelectedRows(rows);
		}
		else if(0==prpty.compareTo("selectedcolumn")){
			if(fld==null || !fld.tableStyle) throw new xTalkException("selectedColumn can only be set on table (tableStyle) fields");
			int column = value==null || value.trim().isEmpty() ? 0 : propertyInteger("selectedColumn", value);
			if(column<0) throw new xTalkException("selectedColumn requires a column number of at least 1");
			fld.selectedColumn = column;
			fld.setSelectedRows(fld.selectedRows);
			fld.selectedColumn = column;
		}
		else if(0==prpty.compareTo("showlines")){
			if(fld==null) throw new xTalkException("showLines can only be set on fields");
			fld.showLines = propertyBoolean("showLines", value);
			if(fld.fld!=null) fld.fld.repaint();
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("widemargins")){
			if(fld==null) throw new xTalkException("wideMargins can only be set on fields");
			fld.setWideMargins(propertyBoolean("wideMargins", value));
		}
		else if(0==prpty.compareTo("locktext")){
			if(fld==null) throw new xTalkException("lockText can only be set on fields");
			fld.enabled = propertyBoolean("lockText", value);
			fld.updateTextEditable();
			((OCardBase)fld.parent).changed = true;
		}
		else if(0==prpty.compareTo("partnumber")){
			if((btn==null && fld==null) || !(obj.parent instanceof OCardBase)) throw new xTalkException("partNumber can only be set on buttons and fields");
			int n = propertyInteger("partNumber", value);
			OCardBase owner = (OCardBase)obj.parent;
			if(owner.setPartNumber(obj, n) && PCARD.pc!=null && PCARD.pc.mainPane!=null && !owner.restackComponents())
				OCard.reloadCurrentCard();
		}
		else if(0==prpty.compareTo("number")){
			throw new xTalkException("Cannot change number (use partNumber)");
		}
		else if(0==prpty.compareTo("sharedhilite")){
			if(btn==null || btn.parent==null || !btn.parent.objectType.equals("background"))
				throw new xTalkException("sharedHilite can only be set on background buttons");
			btn.sharedHilite = propertyBoolean("sharedHilite", value);
			((OCardBase)btn.parent).changed = true;
		}
		else if(0==prpty.compareTo("showname")){
			if(btn==null) throw new xTalkException("showName can only be set on buttons");
			btn.setShowName(propertyBoolean("showName", value));
		}
		else if(0==prpty.compareTo("family")){
			if(btn==null) throw new xTalkException("family can only be set on buttons");
			int family = propertyInteger("family", value);
			if(family<0 || family>16) throw new xTalkException("family must be between 0 and 16");
			btn.group = family;
			((OCardBase)btn.parent).changed = true;
		}
		else if(0==prpty.compareTo("titlewidth")){
			if(btn==null) throw new xTalkException("titleWidth can only be set on buttons");
			btn.titleWidth = propertyInteger("titleWidth", value);
			//popup(style==8)でshowNameがオンのときは、titleWidthが名前表示欄の
			//幅として実際に画面へ反映されるので、即座に描き直す。
			if(btn.style==8 && btn.popup!=null) btn.popup.setBounds(btn.left, btn.top, btn.width, btn.height);
			((OCardBase)btn.parent).changed = true;
		}
		else if(0==prpty.compareTo("textalign")){
			int align = textAlignValue(value);
			if(btn!=null) btn.textAlign = align;
			else if(fld!=null){ fld.textAlign = align; fld.refreshTable(); }
			else throw new xTalkException("textAlign can only be set on buttons or fields");
			((OCardBase)obj.parent).changed = true;
		}
		else if(0==prpty.compareTo("textfont")){
			if(btn!=null) btn.setTextFont(value);
			else if(fld!=null){ fld.setTextFont(value); fld.clearRunAttribute("font"); }
			else throw new xTalkException("textFont can only be set on buttons or fields");
		}
		else if(0==prpty.compareTo("textheight")){
			int height = propertyInteger("textHeight", value);
			if(btn!=null){ btn.textHeight = height; ((OCardBase)btn.parent).changed = true; }
			else if(fld!=null) fld.setTextHeight(height);
			else throw new xTalkException("textHeight can only be set on buttons or fields");
		}
		else if(0==prpty.compareTo("textsize")){
			int size = propertyInteger("textSize", value);
			if(size<1) size = 1;//HyperCard J-2.3: 0 (or empty) gives the textSize 1, no error
			//Swing側のフォントは物理サイズ(2倍表示モードではscale倍)で指定する
			if(btn!=null){
				btn.textSize = size;
				if(btn.getComponent()!=null) btn.getComponent().setFont(OObject.stackFont(btn.textFont, btn.textStyle & 0x03, size*PCARD.scale()));
				((OCardBase)btn.parent).changed = true;
			}
			else if(fld!=null){
				fld.textSize = size;
				fld.textHeight = size + size/3; //HyperCard: textHeightは新しいサイズの既定値に戻る
				fld.showFont(OObject.stackFont(fld.textFont, fld.textStyle & 0x03, size*PCARD.scale()));
				fld.clearRunAttribute("size");
				fld.refreshTable();
				((OCardBase)fld.parent).changed = true;
			}
			else throw new xTalkException("textSize can only be set on buttons or fields");
		}
		else if(0==prpty.compareTo("textstyle")){
			int style = textStyleValue(value);
			if(btn!=null){
				btn.textStyle = style;
				if(btn.getComponent()!=null) btn.getComponent().setFont(OObject.stackFont(btn.textFont, style & 0x03, btn.textSize*PCARD.scale()));
			}
			else if(fld!=null){
				fld.textStyle = style;
				fld.showFont(OObject.stackFont(fld.textFont, style & 0x03, fld.textSize*PCARD.scale()));
				fld.clearRunAttribute("style");
				fld.refreshTable();
			}
			else throw new xTalkException("textStyle can only be set on buttons or fields");
			((OCardBase)obj.parent).changed = true;
		}
		else if(0==prpty.compareTo("dontsearch")){
			boolean flag = propertyBoolean("dontSearch", value);
			if(fld!=null){ fld.dontSearch = flag; ((OCardBase)fld.parent).changed = true; }
			else if(obj instanceof OCard){ ((OCard)obj).dontSearch = flag; ((OCard)obj).changed = true; }
			else if(obj instanceof OBackground){ ((OBackground)obj).dontSearch = flag; ((OBackground)obj).changed = true; }
			else throw new xTalkException("dontSearch can only be set on cards, backgrounds or fields");
		}
		else if(0==prpty.compareTo("marked")){
			if(!(obj instanceof OCard)) throw new xTalkException("marked can only be set on cards");
			((OCard)obj).marked = propertyBoolean("marked", value);
			((OCard)obj).changed = true;
		}
		else if(0==prpty.compareTo("botright") || 0==prpty.compareTo("bottomright")){
			String[] point = value.split(",");
			if(point.length!=2) throw new xTalkException("bottomRight requires coordinates");
			int right = propertyInteger("bottomRight", point[0]);
			int bottom = propertyInteger("bottomRight", point[1]);
			if(btn!=null) btn.setTopLeft(right-btn.getWidth(), bottom-btn.getHeight());
			else if(fld!=null) fld.setTopLeft(right-fld.getWidth(), bottom-fld.getHeight());
			else if("window".equals(obj.objectType) || "msg".equals(obj.objectType)){
				//set the bottomright of window nomeimmagine to 508,338 (Veglia's picture windows): the window keeps its size
				String[] rect = getProperty("rect", obj, null, null).split(",");
				if(rect.length!=4) throw new xTalkException("Cannot set bottomRight on this object");
				int width = propertyInteger("bottomRight", rect[2].trim())-propertyInteger("bottomRight", rect[0].trim());
				int height = propertyInteger("bottomRight", rect[3].trim())-propertyInteger("bottomRight", rect[1].trim());
				SetProperty(obj, "topleft", (right-width)+","+(bottom-height));
			}
			else throw new xTalkException("Cannot set bottomRight on this object");
		}
		else if(0==prpty.compareTo("icon")){
			if(btn==null) throw new xTalkException("Icons can only be set on buttons");
			int iconId;
			if(value.trim().isEmpty()){
				//HyperCard互換: "set icon of btn to empty" はアイコンなし(0)にする。実機(HyperCard J-2.3)で
				//ハンドラ内から実行すると the icon は 0 になりエラーも出ない(メッセージボックスからだと
				//"Can't understand arguments"になる)。表示中の部品を消す処理などで使われる。
				iconId = 0;
			}
			else try{
				iconId = Integer.parseInt(value.trim());
			}catch(NumberFormatException err){
				//IDでなければアイコン名。名前でも見つからなければHyperZebra拡張の
				//絶対URI(画像ファイル)として扱う。相対URIはtoURL()できず表示も
				//されないので、以下の「存在しないアイコン」扱いに落とす。
				Rsrc rsrc = btn.parent instanceof OCardBase && ((OCardBase)btn.parent).stack!=null ? ((OCardBase)btn.parent).stack.rsrc : PCARD.pc.stack.rsrc;
				iconId = rsrc.getRsrcIdAll(value,"ICON");
				if(iconId==0){
					try {
						URI uri = new URI(value);
						if(uri.isAbsolute()){
							btn.setIconURI(uri);
							return;
						}
					} catch (URISyntaxException e) {
					}
					iconId = Integer.MIN_VALUE;
				}
			}
			//HyperCard互換: 存在しないアイコンを指定した場合、メッセージボックス
			//から直接実行したときだけエラーになり、ハンドラ内からの実行では
			//エラーを出さずにボタンのアイコンも変更しない。
			if(!btn.iconExists(iconId)){
				if(ScriptDebugger.depth()==0) throw new xTalkException("No such icon");
				return;
			}
			btn.setIcon(iconId);
		}
		else if(0==prpty.compareTo("visible")){
			if(btn!=null || fld!=null){
				boolean visible = propertyBoolean("visible", value);
				final OButton b = btn; final OField f = fld;
				showOrHidePart(btn!=null ? btn : fld, () -> { if(b!=null) b.setVisible(visible); else f.setVisible(visible); });
			}
			else if(0==obj.objectType.compareTo("titlebar")){
				//PCARD.pc.setVisible(false);
				//PCARD.pc.setUndecorated(!value.equalsIgnoreCase("true"));
				//PCARD.pc.setVisible(true);
			}
			else if(0==obj.objectType.compareTo("menubar")){
				/*GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
				GraphicsDevice device = ge.getDefaultScreenDevice();  // GraphicsDeviceの取得

		        if (device.isFullScreenSupported()) {
			        // フルスクリーン化！
			        device.setFullScreenWindow(PCARD.pc);
		        }*/
				
				//これではメニューを消せないので、メニューがないウィンドウを作ってそちらに移動とか？Macでは無意味だが。
				/*if(value.equalsIgnoreCase("true")){
					PCARD.pc.setJMenuBar(PCARD.pc.menu.mb);
				}else{
					PCARD.pc.setJMenuBar(new JMenuBar());
				}*/
			}
			else if(0==obj.objectType.compareTo("msg")){
				boolean b = value.equalsIgnoreCase("true");
				GMsg.msg.setVisible(b);
				if(b && GMsg.shownByUser()){ //スクリプト中のshow msgではカードのキー入力を奪わない
					GMsg.msg.requestFocus();
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				if(PCARD.pc.stack!=null && PCARD.pc.toolbar!=null){
					//スクリプトが loc を決めた(show tool window at h,v)ときはその位置のまま
					if(!toolWindowPlaced) PCARD.pc.toolbar.tb.setBounds(Math.max(0,PCARD.pc.getX()-GToolBar.WINDOW_WIDTH-2),
						PCARD.pc.getY()+24,GToolBar.WINDOW_WIDTH,GToolBar.WINDOW_HEIGHT);
					toolWindowPlaced = false;
					PCARD.pc.toolbar.tb.setVisible(value.equalsIgnoreCase("true"));
				}
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//本物のHyperCardのような独立したPatternsパレットは無いため何もしない
				//(エラーにはしない)
			}
			else if(0==obj.objectType.compareTo("picture")){
				obj.setVisible(value.equalsIgnoreCase("true"));
			}
			else if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					boolean show = value.equalsIgnoreCase("true");
					//hide window (the short name of this stack) while the mouse is down (Dot Block): the hidden window never
					//gets the mouse-up, so "wait until the mouse is up" would never end
					if(!show && ((OWindow)obj).frame==PCARD.pc) GUI.mouseDown = false;
					((OWindow)obj).frame.setVisible(show);
				}
				else if(((OWindow)obj).dlog!=null){
					((OWindow)obj).dlog.setVisible(value.equalsIgnoreCase("true"));
					if(((OWindow)obj).dlog == MessageWatcher.watcherWindow){
						MessageWatcher.watcherWindow.setTable(TTalk.talk.messageRingArray, TTalk.talk.objectRingArray, TTalk.talk.ringCnt);
					}
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setVisible(value.equalsIgnoreCase("true"));
				}
				else if(((OWindow)obj).movieHandle!=null){
					//音だけの Movie(BGM の .mov)には表示するウィンドウが無い: 状態だけ覚える(Earth 〜紅玉の森〜: show window TheMovieName)
					obj.setVisible(propertyBoolean("visible", value));
				}
				else{
					throw new xTalkException("Cannot set visible on this object?");
				}
			}
			else throw new xTalkException("Cannot set visible on this object");
		}
		else if(0==prpty.compareTo("enabled")){
			if(btn!=null){
				btn.setEnabled(propertyBoolean("enabled", value));
			}
			else if(fld!=null){
				throw new xTalkException("Cannot set enabled on a field (use lockText)");
			}
			else if(obj instanceof OMenu){
				//(以前は value をメニュー名として渡していたため何も変わらなかった)
				((OMenu)obj).menu.setEnabled(propertyBoolean("enabled", value));
				GMenu.menuUpdate(PCARD.pc.menu.mb);
			}
			else throw new xTalkException("Cannot set enabled on this object");
		}
		else {
			SetProperty2(obj, inProperty, value, prpty, btn, fld, cd);
			return;
		}
	}

	//HyperCard自身のプロパティ(SetPropertyが大きすぎてJITコンパイルされないので分けてある)
	private static void setHyperCardProperty(String inProperty, String value, String prpty)
	throws xTalkException
	{
			//HyperCardのプロパティ
			if(0==prpty.compareTo("scripttextfont")){
				PCARD.scriptFont = value;
			}
			else if(0==prpty.compareTo("scripttextsize")){
				try{
					PCARD.scriptFontSize = Integer.valueOf(value);
				}catch(Exception err){
					throw new xTalkException("A number is required here");
				}
			}
			else if(0==prpty.compareTo("textfont")){
				PCARD.pc.textFont = value;
				PCARD.runOnEDT(TypeTool::refreshLive);
			}
			else if(0==prpty.compareTo("textstyle")){
				PCARD.pc.textStyle = textStyleValue(value);
				PCARD.runOnEDT(TypeTool::refreshLive);
			}
			else if(0==prpty.compareTo("textsize")){
				try{
					PCARD.pc.textSize = (int)Double.parseDouble(value.trim()); //" 9" and "10.0" as in HyperCard J-2.3
					PCARD.runOnEDT(TypeTool::refreshLive);
				}catch(Exception err){
					throw new xTalkException("A number is required here");
				}
			}
			else if(0==prpty.compareTo("textheight")){
				//the line height of painted text (Graph Maker: set textHeight to fontSize); kept for the text tool's lines
				compatibilityProperties.put("textheight", Integer.toString(propertyInteger("textHeight", value.trim())));
			}
			else if(0==prpty.compareTo("itemdelimiter")){
				if(value.length()==1) {
					TTalk.itemDelimiter = value;
				}
				else if(value.length()==0) {
					TTalk.itemDelimiter = ",";
				}
				else {
					throw new xTalkException("itemDelimiter requires a single character");
				}
			}
			else if(0==prpty.compareTo("numberformat")){
				if(value.length()==0){
					throw new xTalkException("numberFormat requires a format");
				}
				try{
					TTalk.numFormat = TTalk.newNumberFormat(value);
					TTalk.numberFormatPattern = value;
				}catch(IllegalArgumentException err){
					throw new xTalkException("Invalid numberFormat");
				}
			}
			else if(0==prpty.compareTo("userlevel")){
				//HyperCard J-2.3: an integer is brought into 1..5 (0 and empty give 1, 9 gives 5); "abc" or 2.7 are errors.
				//TK HTML Edit's closeStack sets it to a global that its openStack never filled
				int level;
				String v = value.trim();
				if(v.isEmpty()) level = 0;
				else{
					try{
						level = Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
					}catch(NumberFormatException err){
						throw new xTalkException("userLevel requires an integer from 1 to 5");
					}
				}
				level = Math.max(1, Math.min(5, level));
				PCARD.userLevel = level;
				//スタックの「ユーザーレベルの制限」より上にはならない(StackProtection.effectiveUserLevel)
				GMenu.enforceUserLevel();
			}
			else if(0==prpty.compareTo("usermodify")){
				//true/false のみ。userModify はスタックを移動すると false に戻る(resetStackProperties)
				compatibilityProperties.put(prpty, Boolean.toString(propertyBoolean(inProperty, value)));
			}
			else if(0==prpty.compareTo("powerkeys")){
				PaintTool.setPowerKeys(propertyBoolean(inProperty, value));
			}
			else if(0==prpty.compareTo("cursor")){
				Cursor cr = null;
						/*new Cursor(Cursor.CROSSHAIR_CURSOR) ,
						new Cursor(Cursor.DEFAULT_CURSOR) ,
						new Cursor(Cursor.HAND_CURSOR) ,
						new Cursor(Cursor.MOVE_CURSOR) ,
						new Cursor(Cursor.WAIT_CURSOR) ,
						new Cursor(Cursor.TEXT_CURSOR) ,
						new Cursor(Cursor.SW_RESIZE_CURSOR) ,
						new Cursor(Cursor.SE_RESIZE_CURSOR) ,
						new Cursor(Cursor.NW_RESIZE_CURSOR) ,
						new Cursor(Cursor.NE_RESIZE_CURSOR) ,
						new Cursor(Cursor.N_RESIZE_CURSOR) ,
						new Cursor(Cursor.S_RESIZE_CURSOR) ,
						new Cursor(Cursor.W_RESIZE_CURSOR) ,
						new Cursor(Cursor.E_RESIZE_CURSOR) ,*/
				//名前の付いたカーソルは HyperCard では CURS の ID(hand 128, ibeam 1, cross 2, plus 3, watch 4)。
				//スタックに同じ ID の CURS があればそれが出る(VM の HyperCard で確認)
				if(value.equalsIgnoreCase("hand") || value.equalsIgnoreCase("browse")){
					cr = browseCursor();
				}else if(value.equalsIgnoreCase("watch")){
					cr = hyperCardCursor(4, Cursor.WAIT_CURSOR);
				}else if(value.equalsIgnoreCase("busy")){
					cr = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);
				}else if(value.equalsIgnoreCase("arrow")){
					cr = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
				}else if(value.equalsIgnoreCase("cross")){
					cr = hyperCardCursor(2, Cursor.CROSSHAIR_CURSOR);
				}else if(value.equalsIgnoreCase("plus")){
					cr = hyperCardCursor(3, Cursor.CROSSHAIR_CURSOR);
				}else if(value.equalsIgnoreCase("ibeam")){
					cr = hyperCardCursor(1, Cursor.TEXT_CURSOR);
				}else if(value.equalsIgnoreCase("none")){
					synchronized(TUtil.class){
						if(noneCursor==null) noneCursor = Toolkit.getDefaultToolkit().createCustomCursor(
								new BufferedImage(16,16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "none-cursor");
						cr = noneCursor;
					}
				}else{
					//CURSは生データ(.data)のまま保存されていることがあるため、
					//ImageIO.readではなくgetImage()経由でデコードする(Rsrc.loadImage
					//参照)。Inside Macintoshの慣習どおり、同じIDのcrsr(カラー
					//カーソル)があればモノクロのCURSより優先して使う(ホットスポットも
					//同じ優先順位で取得する。Rsrc.getDisplayImage/getDisplayHotSpot参照)。
					//名前で見つけたカーソルはIDで引き直さない(Homeの名前とこのスタックのIDが重なると別のカーソルになる)
					Rsrc rsrc = PCARD.pc.stack.rsrc;
					boolean named = rsrc.findByNameAll(value, "CURS")!=null;
					int rsrcid = !named && value.matches("[0-9]+") ? Integer.valueOf(value) : 0;
					BufferedImage bi = named ? rsrc.getDisplayImage(value, "CURS") : rsrc.getDisplayImage(rsrcid, "CURS");
					if(bi!=null){
						Point hotSpot = named ? rsrc.getDisplayHotSpot(value) : rsrc.getDisplayHotSpot(rsrcid);
						//ホットスポットの記録が無いカーソル(Internet Tour の待ちカーソル)は中央。null は createCustomCursor が落ちる
						if(hotSpot==null) hotSpot = new Point(bi.getWidth()/2, bi.getHeight()/2);
						cr = customCursor(bi, hotSpot);
					}else if(value.matches("[0-9]+") && CLASSIC_MAC_CURSOR_IDS.containsKey(Integer.valueOf(value))){
						//スタックのリソースフォークには入っていない、classic Mac OS標準の
						//システムカーソル（Toolbox内蔵）。現行OSの近いカーソルで代替する。
						cr = Cursor.getPredefinedCursor(CLASSIC_MAC_CURSOR_IDS.get(Integer.valueOf(value)));
					}
					//else: 存在しないカーソルが指定された場合、HyperCardと同様にエラーには
					//せず何もしない（現在のカーソルのまま）。
				}
				//同じカーソルなら設定し直さない(Component.setCursor は同じでもカーソルの更新を走らせる)
				if(cr!=null && PCARD.pc.getCursor()!=cr)
					PCARD.pc.setCursor(cr);
				compatibilityProperties.put("cursor", value);
			}
			else if(0==prpty.compareTo("lockscreen")){
				if(value.equalsIgnoreCase("true")){
					TTalk.doScriptforMenu("lock screen");
				}else if(value.equalsIgnoreCase("false")){
					TTalk.doScriptforMenu("unlock screen");
				}
			}
			else if(0==prpty.compareTo("language")){
				//set language to oldLanguage (HyperBib の cleanExit): スクリプトの翻訳言語。HyperZebra は表示言語を変えないので受け付けるだけ
			}
			else if(0==prpty.compareTo("lockrecent")){
				if(value.equalsIgnoreCase("true")){
					
				}else if(value.equalsIgnoreCase("false")){
					
				}
				compatibilityProperties.put("lockrecent", Boolean.toString(propertyBoolean("lockRecent", value)));
			}
			else if(0==prpty.compareTo("lockmessages")){
				if(value.equalsIgnoreCase("true")){
					TTalk.doScriptforMenu("lock messages");
				}else if(value.equalsIgnoreCase("false")){
					TTalk.doScriptforMenu("unlock messages");
				}
			}
			else if(0==prpty.compareTo("lockerrordialogs")){
				if(value.equalsIgnoreCase("true")){
					TTalk.lockErrorDialogs = true;
				}else if(value.equalsIgnoreCase("false")){
					TTalk.lockErrorDialogs = false;
				}
				else throw new xTalkException("A boolean is required here");
			}
			else if(0==prpty.compareTo("dragspeed")){
				TTalk.dragspeed = propertyInteger("dragSpeed", value.trim());
			}
			else if(0==prpty.compareTo("editbkgnd")){
				boolean on = propertyBoolean("editBkgnd", value);
				//editBkgnd は userLevel 3(Painting)以上でだけ使える
				if(on && StackProtection.effectiveUserLevel()<3) throw new xTalkException("editBkgnd requires userLevel 3 or higher");
				PaintTool.editBackground = on;
			}
			else if(0==prpty.compareTo("filled")){
				PCARD.pc.fill = propertyBoolean("filled", value);
			}
			else if(0==prpty.compareTo("centered")){
				PaintTool.drawCentered = propertyBoolean("centered", value);
				compatibilityProperties.put("centered", Boolean.toString(PaintTool.drawCentered));
			}
			else if(0==prpty.compareTo("multiple")){
				PaintTool.drawMultiple = propertyBoolean("multiple", value);
				compatibilityProperties.put("multiple", Boolean.toString(PaintTool.drawMultiple));
				PCARD.runOnEDT(() -> GMenu.changeSelected("Options","Draw Multiple",PaintTool.drawMultiple));
			}
			else if(0==prpty.compareTo("multispace")){
				//1以上。メニューは1〜9だが、HC J-2.3 は set the multiSpace to 10 も受け付けて 10 を返す(プローブ A Err MultiSpace)
				int space = propertyInteger("multiSpace", value);
				if(space<1) throw new xTalkException("multiSpace must be between 1 and {0}", PaintTool.MULTI_SPACE_MAX);
				PaintTool.setMultiSpace(space);
			}
			else if(0==prpty.compareTo("polysides")){
				int sides = propertyInteger("polySides", value);
				//HyperCard J-2.3 keeps 0 and brings the others into 3..50 (-1 → 3, 2 → 3, 60 → 50; Mosaïcon: set the polysides to random (49))
				if(sides!=0) sides = Math.max(3, Math.min(50, sides));
				PaintTool.regPolySides = sides;
			}
			else if(0==prpty.compareTo("gradient")){
				//set the gradient to off / linear / radial (グラデーションボタンと同じ)
				String v = value.trim().toLowerCase(java.util.Locale.ROOT);
				int kind;
				if(v.equals("off") || v.equals("false") || v.equals("none") || v.equals("0")) kind = 0;
				else if(v.equals("linear") || v.equals("on") || v.equals("true") || v.equals("1")) kind = 1;
				else if(v.equals("radial") || v.equals("circular") || v.equals("2")) kind = 2;
				else if(v.equals("texture") || v.equals("3")) kind = 3;
				else throw new xTalkException("gradient requires off, linear, radial or texture");
				final int k = kind;
				PCARD.runOnEDT(() -> { if(PCARD.pc.grad!=null) PCARD.pc.grad.setKind(k); });
			}
			else if(0==prpty.compareTo("gradientangle")){
				int deg = propertyInteger("gradientAngle", value);
				PCARD.runOnEDT(() -> {
					if(PCARD.pc.grad!=null){ PCARD.pc.grad.angle = Math.toRadians(GToolOptions.AngleDial.normalize(deg)); PCARD.pc.grad.makeIcon(); }
				});
			}
			else if(0==prpty.compareTo("filltexture")){
				//set the fillTexture to Wood / Marble / … / custom / none / 画像ファイルのパス。
				//none以外なら塗りの種類もテクスチャにする
				if(!PaintTexture.select(value)) throw new xTalkException("fillTexture requires a built-in texture name or an image file: {0}", value);
				PCARD.runOnEDT(() -> { if(PCARD.pc.grad!=null) PCARD.pc.grad.setKind(PaintTexture.current==null ? 0 : 3); });
			}
			else if(0==prpty.compareTo("filltolerance")){
				int t = propertyInteger("fillTolerance", value);
				if(t<0 || t>100) throw new xTalkException("fillTolerance must be between 0 and 100");
				PaintTool.fillTolerancePercent = t;
			}
			else if(0==prpty.compareTo("brushsize")){
				int v = propertyInteger("brushSize", value);
				if(v<1 || v>256) throw new xTalkException("brushSize must be between 1 and 256");
				PaintTool.brushSize = v;
				PCARD.runOnEDT(GToolOptions::changeCursor);
			}
			else if(0==prpty.compareTo("brushhardness")){
				int v = propertyInteger("brushHardness", value);
				if(v<0 || v>100) throw new xTalkException("brushHardness must be between 0 and 100");
				PaintTool.brushHardness = v;
			}
			else if(0==prpty.compareTo("smudgestrength")){
				int v = propertyInteger("smudgeStrength", value);
				if(v<1 || v>100) throw new xTalkException("smudgeStrength must be between 1 and 100");
				PaintTool.smudgeStrength = v;
			}
			else if(0==prpty.compareTo("stampsource")){
				//set the stampSource to x,y (空で解除)
				String v = value.trim();
				if(v.length()==0){ StampTool.source = null; StampTool.offsetSet = false; }
				else{
					String[] parts = v.split(",");
					if(parts.length!=2) throw new xTalkException("stampSource requires \"x,y\"");
					try{
						int x = Integer.parseInt(parts[0].trim()), y = Integer.parseInt(parts[1].trim());
						StampTool.source = new java.awt.Point(x, y); StampTool.offsetSet = false;
					}catch(NumberFormatException err){ throw new xTalkException("stampSource requires \"x,y\""); }
				}
				PCARD.runOnEDT(() -> { if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint(); });
			}
			else if(0==prpty.compareTo("brushopacity")){
				int v = propertyInteger("brushOpacity", value);
				if(v<1 || v>100) throw new xTalkException("brushOpacity must be between 1 and 100");
				PaintTool.brushOpacity = v;
			}
			else if(0==prpty.compareTo("brushspacing")){
				int v = propertyInteger("brushSpacing", value);
				if(v<1 || v>200) throw new xTalkException("brushSpacing must be between 1 and 200");
				PaintTool.brushSpacing = v;
			}
			else if(0==prpty.compareTo("brushstabilization") || 0==prpty.compareTo("brushstabilizer")){
				int v = propertyInteger(inProperty, value);
				if(v<0 || v>98) throw new xTalkException("{0} must be between 0 and 98", inProperty);
				PaintTool.brushStabilization = v;
			}
			else if(0==prpty.compareTo("zoom")){
				//set the zoom to 300 (%)。25〜3200
				float pct;
				try{ pct = Float.parseFloat(value.trim().replace("%","")); }
				catch(NumberFormatException err){ throw new xTalkException("zoom requires a number (%)"); }
				if(pct<PaintTool.ZOOM_MIN*100 || pct>PaintTool.ZOOM_MAX*100)
					throw new xTalkException("zoom must be between {0} and {1}", (int)(PaintTool.ZOOM_MIN*100), (int)(PaintTool.ZOOM_MAX*100));
				PCARD.runOnEDT(() -> { PaintTool.owner = PCARD.pc; PaintTool.setZoom(PCARD.pc, pct/100f, -1, -1); });
			}
			else if(0==prpty.compareTo("zoomorigin")){
				//set the zoomOrigin to x,y: 拡大表示の左上に来るカード座標(見える範囲に収める)
				String[] parts = value.split(",");
				if(parts.length!=2) throw new xTalkException("zoomOrigin requires a point (x,y)");
				int ox, oy;
				try{ ox = Integer.parseInt(parts[0].trim()); oy = Integer.parseInt(parts[1].trim()); }
				catch(NumberFormatException err){ throw new xTalkException("zoomOrigin requires a point (x,y)"); }
				PCARD.runOnEDT(() -> {
					if(PCARD.pc.bit==1) return;
					PCARD.pc.bitLeft = ox; PCARD.pc.bitTop = oy;
					PaintTool.clampView(PCARD.pc);
					PCARD.pc.mainPane.repaint();
					FatBitsPreview.repaintFor(PCARD.pc);
				});
			}
			else if(0==prpty.compareTo("grid")){
				PaintTool.drawingGrid = propertyBoolean("grid", value);
			}
			else if(0==prpty.compareTo("gridsize")){
				int size = propertyInteger("gridSize", value);
				if(size<1 || size>PaintTool.GRID_SIZE_MAX) throw new xTalkException("gridSize must be between 1 and {0}", PaintTool.GRID_SIZE_MAX);
				PaintTool.setDrawingGridSize(size);
			}
			else if(0==prpty.compareTo("paintlayer")){
				//set the paintLayer to N / "name"
				final String v = value;
				final int[] found = new int[1];
				PCARD.runOnEDT(() -> {
					found[0] = PaintLayers.find(PCARD.pc, v);
					if(found[0]>=0) PaintLayers.setActive(PCARD.pc, found[0]);
				});
				if(found[0]<0) throw new xTalkException("No such layer: {0}", value);
			}
			else if(0==prpty.compareTo("layername")){
				final String v = value;
				PCARD.runOnEDT(() -> PaintLayers.rename(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("layeropacity")){
				int v = propertyInteger("layerOpacity", value);
				if(v<0 || v>100) throw new xTalkException("layerOpacity must be between 0 and 100");
				PCARD.runOnEDT(() -> PaintLayers.setOpacity(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("layerblend")){
				int mode = PaintLayer.parseBlend(value);
				if(mode<0) throw new xTalkException("layerBlend requires a blend mode name (Normal, Multiply, etc.)");
				PCARD.runOnEDT(() -> PaintLayers.setBlend(PCARD.pc, mode));
			}
			else if(0==prpty.compareTo("layervisible")){
				boolean v = propertyBoolean("layerVisible", value);
				PCARD.runOnEDT(() -> PaintLayers.setVisible(PCARD.pc, PCARD.pc.activeLayer(), v));
			}
			else if(0==prpty.compareTo("layeralphalock")){
				boolean v = propertyBoolean("layerAlphaLock", value);
				PCARD.runOnEDT(() -> PaintLayers.setAlphaLock(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("layerclip")){
				boolean v = propertyBoolean("layerClip", value);
				PCARD.runOnEDT(() -> PaintLayers.setClipToBelow(PCARD.pc, v));
			}
			else if(0==prpty.compareTo("symmetry")){
				//set the symmetry to none/vertical/horizontal/quadrant/radial [N]
				int kind = PaintTool.parseSymmetry(value);
				if(kind<0) throw new xTalkException("symmetry requires none, vertical, horizontal, quadrant or radial");
				PaintTool.setSymmetry(kind);
			}
			else if(0==prpty.compareTo("symmetryaxes")){
				int n = propertyInteger("symmetryAxes", value);
				if(n<PaintTool.SYMMETRY_AXES_MIN || n>PaintTool.SYMMETRY_AXES_MAX) throw new xTalkException("symmetryAxes must be between {0} and {1}", PaintTool.SYMMETRY_AXES_MIN, PaintTool.SYMMETRY_AXES_MAX);
				PaintTool.setSymmetryAxes(n);
				PCARD.runOnEDT(() -> { if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint(); });
			}
			else if(0==prpty.compareTo("symmetrycenter")){
				if(!PaintTool.setSymmetryCenter(value)) throw new xTalkException("symmetryCenter requires \"x,y\"");
				PCARD.runOnEDT(() -> { if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint(); });
			}
			else if(0==prpty.compareTo("linesize")){
				try{
					PaintTool.lineSize = Float.parseFloat(value);
				}catch(NumberFormatException err){
					throw new xTalkException("lineSize requires a number");
				}
			}
			else if(0==prpty.compareTo("pattern") || 0==prpty.compareTo("brushpattern") ||
					0==prpty.compareTo("penpattern") || 0==prpty.compareTo("fillpattern")){
				int pattern = propertyInteger(inProperty, value);
				if(pattern<1 || pattern>40) throw new xTalkException("{0} must be between 1 and 40", inProperty);
				if(PCARD.pc.pat!=null) PCARD.pc.pat.select(pattern-1);
			}
			else if(0==prpty.compareTo("brushcolor") || 0==prpty.compareTo("pencolor")){
				final Color col = paintColorValue(inProperty, value, false);
				if(PCARD.pc.fore!=null) PCARD.runOnEDT(() -> PCARD.pc.fore.makeIcon(col));
			}
			else if(0==prpty.compareTo("brushbackcolor") || 0==prpty.compareTo("penbackcolor")){
				final Color col = paintColorValue(inProperty, value, true);
				if(PCARD.pc.back!=null) PCARD.runOnEDT(() -> PCARD.pc.back.makeIcon(col));
			}
			else if(0==prpty.compareTo("messagewatcher")){
				if(MessageWatcher.watcherWindow!=null) MessageWatcher.watcherWindow.setVisible(propertyBoolean("messageWatcher", value));
			}
			else if(0==prpty.compareTo("variablewatcher")){
				if(VariableWatcher.watcherWindow!=null) VariableWatcher.watcherWindow.setVisible(propertyBoolean("variableWatcher", value));
			}
			else if(0==prpty.compareTo("textalign")){
				compatibilityProperties.put("textalign", textAlignName(textAlignValue(value)));
			}
			else if(prpty.equals("printtextsize") || prpty.equals("printtextheight")){
				int v = propertyInteger(prpty, value);
				if(v<1) throw new xTalkException("{0} requires a positive integer", inProperty);
				compatibilityProperties.put(prpty, Integer.toString(v));
			}
			else if(prpty.equals("printtextalign")){
				compatibilityProperties.put(prpty, textAlignName(textAlignValue(value)));
			}
			else if(prpty.equals("printmargins")){
				String[] parts = value.split(",");
				if(parts.length!=4) throw new xTalkException("printMargins requires four integers (left,top,right,bottom)");
				StringBuilder sb = new StringBuilder();
				for(int i=0; i<4; i++){
					int v = propertyInteger("printMargins", parts[i].trim());
					if(v<0) throw new xTalkException("printMargins requires nonnegative integers");
					if(i>0) sb.append(',');
					sb.append(v);
				}
				compatibilityProperties.put(prpty, sb.toString());
			}
			else if(prpty.equals("printtextstyle")){
				compatibilityProperties.put(prpty, textStyleName(textStyleValue(value)));
			}
			else if(prpty.equals("soundchannel")){
				int v = propertyInteger("soundChannel", value);
				//HC J-2.3 ignores a channel below 1 without an error (probe A Err SoundChannel: set to 0 leaves 1)
				if(v>=1) compatibilityProperties.put(prpty, Integer.toString(v));
			}
			else if(compatibilityProperties.containsKey(prpty)){
				compatibilityProperties.put(prpty, value);
			}
			else throw new xTalkException("Cannot interpret property {0}", prpty);
	}


	/** The numbers of a point or rectangle being set (loc, topLeft, rect): an empty item is 0 as in HyperCard J-2.3
	 *  (set the loc of btn 1 to "," -> 0,0; "60," -> 60,0; Logo 2.1 sets the turtle to empty globals before it places it).
	 *  HyperCard wants integers; fractions are kept lenient here (truncated). */
	static int[] coordinates(String value, int count, String wrongCount) throws xTalkException {
		String[] items = value.split(",", -1);
		if(items.length!=count) throw new xTalkException(wrongCount);
		int[] v = new int[count];
		for(int i=0; i<count; i++){
			String t = items[i].trim();
			if(t.isEmpty()) continue;
			try{ v[i] = (int)Double.parseDouble(t); }
			catch(NumberFormatException err){ throw new xTalkException("A number is required here"); }
		}
		return v;
	}

	private static void SetProperty2(OObject obj, String inProperty, String value, String prpty,
			OButton btn, OField fld, OCard cd)
	throws xTalkException
	{
		if(0==prpty.compareTo("hilite") || 0==prpty.compareTo("highlight") || 0==prpty.compareTo("highlite") || 0==prpty.compareTo("hilight")){
			if(btn!=null){
				btn.setHilite(propertyBoolean("hilite", value));
			}
			else if(fld!=null){
				//HyperCard Help Extras: set the hilite of bkgnd field "Index" to false - the list field's hilited line goes
				if(!propertyBoolean("hilite", value)) fld.setSelectedLine(0);
			}
			else throw new xTalkException("Cannot set hilite on this object");
		}
		else if(0==prpty.compareTo("text")){
			value = TalkCollection.display(value);
			if(btn!=null) btn.setText(value);
			else if(fld!=null){
				if(StackSpeed.limited()) StackSpeed.fieldText(value.length()); //速度制限: 当時のTextEditの組版時間
				fld.setText(value);
			}
			else if(obj.objectType.equals("menu")) {
				OMenu om = (OMenu) obj;
				om.menu.add(new JMenuItem(value));
			}
			else throw new xTalkException("Text can only be stored in buttons and fields");
		}
		else if(0==prpty.compareTo("name")){
			if(btn!=null) btn.setName(value);
			else if(fld!=null) fld.setName(value);
			else obj.name = value;
		}
		else if(0==prpty.compareTo("left")){
			int v;
			try{
				double d = Double.valueOf(value);
				v = (int)d;
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(btn!=null) btn.setTopLeft(v, btn.getTop());
			else if(fld!=null) fld.setTopLeft(v, fld.getTop());
			else if(cd!=null) PCARD.pc.stack.setTopLeft(v, PCARD.pc.stack.getTop());
			else if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(windowOrigin().x+PCARD.toPhysical(v), ((OWindow)obj).frame.getY(), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(windowOrigin().x+PCARD.toPhysical(v), ((OWindow)obj).gpw.getY(), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else{
					throw new xTalkException("Cannot set left on this object?");
				}
			}
			else throw new xTalkException("Cannot set left on this object");
		}
		else if(0==prpty.compareTo("top")){
			int v;
			try{
				double d = Double.valueOf(value);
				v = (int)d;
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(btn!=null) btn.setTopLeft(btn.getLeft(),v);
			else if(fld!=null) fld.setTopLeft(fld.getLeft(),v);
			else if(cd!=null) PCARD.pc.stack.setTopLeft(PCARD.pc.stack.getLeft(),v);
			else if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(((OWindow)obj).frame.getX(), windowOrigin().y+PCARD.toPhysical(v), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(((OWindow)obj).gpw.getX(), windowOrigin().y+PCARD.toPhysical(v), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else{
					throw new xTalkException("Cannot set top on this object?");
				}
			}
			else throw new xTalkException("Cannot set top on this object");
		}
		else if(0==prpty.compareTo("right")){
			int v;
			try{
				double d = Double.valueOf(value);
				v = (int)d;
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(btn!=null) btn.setTopLeft(v-btn.getWidth(), btn.getTop());
			else if(fld!=null) fld.setTopLeft(v-fld.getWidth(), fld.getTop());
			else if(cd!=null) PCARD.pc.stack.setTopLeft(v-PCARD.pc.stack.getWidth(), PCARD.pc.stack.getTop());
			else throw new xTalkException("Cannot set right on this object");
		}
		else if(0==prpty.compareTo("bottom")){
			int v;
			try{
				double d = Double.valueOf(value);
				v = (int)d;
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(btn!=null) btn.setTopLeft(btn.getLeft(),v-btn.getHeight());
			else if(fld!=null) fld.setTopLeft(fld.getLeft(),v-fld.getHeight());
			else if(cd!=null) PCARD.pc.stack.setTopLeft(PCARD.pc.stack.getLeft(),v-PCARD.pc.stack.getHeight());
			else throw new xTalkException("Cannot set bottom on this object");
		}
		else if(0==prpty.compareTo("topleft")){
			int[] pt = coordinates(value, 2, "Coordinates are required here");
			int v1 = pt[0], v2 = pt[1];
			if(btn!=null) btn.setTopLeft(v1,v2);
			else if(fld!=null) fld.setTopLeft(v1,v2);
			else if(cd!=null) PCARD.pc.stack.setTopLeft(v1,v2);
			else if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(windowOrigin().x+PCARD.toPhysical(v1), windowOrigin().y+PCARD.toPhysical(v2), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(windowOrigin().x+PCARD.toPhysical(v1), windowOrigin().y+PCARD.toPhysical(v2), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else if(((OWindow)obj).dlog!=null) placeDialogWindow(((OWindow)obj).dlog, v1, v2);
				else{
					throw new xTalkException("Cannot set topleft on this object?");
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				//set topLeft of window "Tools" (ぺいんと+ puts its palettes back where they were): as its loc
				if(PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
					PCARD.pc.toolbar.tb.setLocation(windowOrigin().x+PCARD.toPhysical(v1), windowOrigin().y+PCARD.toPhysical(v2));
					toolWindowPlaced = true;
				}
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//no Patterns palette of its own (as set loc)
			}
			else if(0==obj.objectType.compareTo("msg")){
				//set the topleft of msg to 20,355 (AOL ProTech; HC J-2.3 probe Aol Msg TopLeft)
				if(GMsg.msg!=null){
					Point cardOrigin = getCardOriginOnScreen();
					GMsg.msg.setBounds(cardOrigin.x+PCARD.toPhysical(v1), cardOrigin.y+PCARD.toPhysical(v2), GMsg.msg.getWidth(), GMsg.msg.getHeight());
				}
			}
			else throw new xTalkException("Cannot set topleft on this object");
		}
		else if(0==prpty.compareTo("loc") || 0==prpty.compareTo("location")){
			if(obj instanceof OWindow && ((OWindow)obj).gpw!=null && !value.matches("\\s*[-+0-9.]+\\s*,\\s*[-+0-9.]+\\s*")){
				//a picture window's loc that is no point (HyperCard J-2.3, Gradebook's showCredits: set the loc of window
				//"credits" to card): "card" centres the window on the screen, anything else ("screen", "foo") is ignored, no error
				if(value.trim().equalsIgnoreCase("card")){
					GPictWindow gpw = ((OWindow)obj).gpw;
					java.awt.Rectangle screen = PCARD.pc.getGraphicsConfiguration()!=null ? PCARD.pc.getGraphicsConfiguration().getBounds() : new java.awt.Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
					gpw.setLocation(screen.x+(screen.width-gpw.getWidth())/2, screen.y+(screen.height-gpw.getHeight())/2);
				}
				return;
			}
			int[] pt = coordinates(value, 2, "Coordinates are required here");
			int v1 = pt[0], v2 = pt[1];
			if(btn!=null) btn.setTopLeft(v1-btn.getWidth()/2, v2-btn.getHeight()/2);
			else if(fld!=null) fld.setTopLeft(v1-fld.getWidth()/2, v2-fld.getHeight()/2);
			else if(cd!=null) PCARD.pc.stack.setTopLeft(v1-PCARD.pc.stack.getWidth()/2, v2-PCARD.pc.stack.getHeight()/2);
			else if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame==PCARD.pc){
					((OWindow)obj).frame.setBounds(v1,v2, ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(windowOrigin().x+PCARD.toPhysical(v1), windowOrigin().y+PCARD.toPhysical(v2), ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(windowOrigin().x+PCARD.toPhysical(v1), windowOrigin().y+PCARD.toPhysical(v2), ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else if(((OWindow)obj).dlog!=null) placeDialogWindow(((OWindow)obj).dlog, v1, v2);
			}
			else if(0==obj.objectType.compareTo("msg")){
				if(GMsg.msg!=null){
					Point cardOrigin = getCardOriginOnScreen();
					GMsg.msg.setBounds(cardOrigin.x+PCARD.toPhysical(v1), cardOrigin.y+PCARD.toPhysical(v2),
						GMsg.msg.getWidth(), GMsg.msg.getHeight());
				}
			}
			else if(0==obj.objectType.compareTo("tool window")){
				//show tool window at 8,72 (ShowDialog XCMD スタック): パレットの左上を画面座標で置く
				if(PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
					PCARD.pc.toolbar.tb.setLocation(windowOrigin().x+PCARD.toPhysical(v1), windowOrigin().y+PCARD.toPhysical(v2));
					toolWindowPlaced = true;
				}
			}
			else if(0==obj.objectType.compareTo("pattern window")){
				//独立したPatternsパレットは無い(show で何もしないのと同じ)
			}
			/*else{
				throw new xTalkException("Cannot set loc on this object?");
			}*/
			/*else if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(windowOrigin().x+v1-((OWindow)obj).frame.getWidth()/2, windowOrigin().y+v2-((OWindow)obj).frame.getHeight()/2, ((OWindow)obj).frame.getWidth(), ((OWindow)obj).frame.getHeight());
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(windowOrigin().x+v1-((OWindow)obj).gpw.getWidth()/2, windowOrigin().y+v2-((OWindow)obj).gpw.getHeight()/2, ((OWindow)obj).gpw.getWidth(), ((OWindow)obj).gpw.getHeight());
				}
				else{
					throw new xTalkException("Cannot set loc on this object?");
				}
			}
			else if(0==obj.objectType.compareTo("msg")){
				if(GMsg.msg!=null){
					GMsg.msg.setBounds(windowOrigin().x+v1-GMsg.msg.getWidth()/2, windowOrigin().y+v2-GMsg.msg.getHeight()/2, GMsg.msg.getWidth(), GMsg.msg.getHeight());
				}
			}*/
			else throw new xTalkException("Cannot set loc on this object");
		}
		else if(0==prpty.compareTo("rect") || 0==prpty.compareTo("rectangle")){
			int[] r = coordinates(value, 4, "A rectangle is required here");
			int v1 = r[0], v2 = r[1], v3 = r[2], v4 = r[3];
			if(btn!=null) btn.setRect(v1,v2,v3,v4);
			else if(fld!=null) fld.setRect(v1,v2,v3,v4);
			else if(cd!=null) PCARD.pc.stack.setRect(v1,v2,v3,v4);
			else if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).frame==PCARD.pc){
					((OWindow)obj).frame.setBounds(v1,v2,v3-v1,v4-v2);
				}
				else if(((OWindow)obj).frame!=null){
					((OWindow)obj).frame.setBounds(windowOrigin().x+PCARD.toPhysical(v1),windowOrigin().y+PCARD.toPhysical(v2),PCARD.toPhysical(v3-v1),PCARD.toPhysical(v4-v2));
				}
				else if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.setBounds(windowOrigin().x+PCARD.toPhysical(v1),windowOrigin().y+PCARD.toPhysical(v2),PCARD.toPhysical(v3-v1),PCARD.toPhysical(v4-v2));
				}
				else{
					throw new xTalkException("Cannot set rect on this object?");
				}
			}
			else throw new xTalkException("Cannot set rect on this object");
		}
		else if(0==prpty.compareTo("width")){
			int v;
			try{
				double d = Double.valueOf(value);
				v = (int)d;
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(btn!=null) btn.setRect(btn.getLeft()+btn.getWidth()/2-v/2,btn.getTop(),btn.getLeft()+btn.getWidth()/2+(v+1)/2,btn.getTop()+btn.getHeight());
			else if(fld!=null) fld.setRect(fld.getLeft()+fld.getWidth()/2-v/2,fld.getTop(),fld.getLeft()+fld.getWidth()/2+(v+1)/2,fld.getTop()+fld.getHeight());
			else if(cd!=null) PCARD.pc.stack.setRect(PCARD.pc.stack.getLeft()+PCARD.pc.stack.getWidth()/2-v/2,PCARD.pc.stack.getTop(),PCARD.pc.stack.getLeft()+PCARD.pc.stack.getWidth()/2+(v+1)/2,PCARD.pc.stack.getTop()+PCARD.pc.stack.getHeight());
		}
		else if(0==prpty.compareTo("height")){
			int v;
			try{
				double d = Double.valueOf(value);
				v = (int)d;
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(btn!=null) btn.setRect(btn.getLeft(),btn.getTop()+btn.getHeight()/2-v/2,btn.getLeft()+btn.getWidth(),btn.getTop()+btn.getHeight()/2+(v+1)/2);
			else if(fld!=null) fld.setRect(fld.getLeft(),fld.getTop()+fld.getHeight()/2-v/2,fld.getLeft()+fld.getWidth(),fld.getTop()+fld.getHeight()/2+(v+1)/2);
			else if(cd!=null) PCARD.pc.stack.setRect(PCARD.pc.stack.getLeft(),PCARD.pc.stack.getTop()+PCARD.pc.stack.getHeight()/2-v/2,PCARD.pc.stack.getLeft()+PCARD.pc.stack.getWidth(),PCARD.pc.stack.getTop()+PCARD.pc.stack.getHeight()/2+(v+1)/2);
		}
		else if(0==prpty.compareTo("zoomed")){
			if(0==obj.objectType.compareTo("window")){
				((OWindow)obj).setZoomed(propertyBoolean("zoomed", value));
			}
			else throw new xTalkException("Cannot set zoomed on this object");
		}
		else if(0==prpty.compareTo("cantpeek")){
			if(obj!=null&&obj.objectType.equals("stack")){
				((OStack)obj).cantPeek = propertyBoolean("cantPeek", value);
				((OStack)obj).changed = true;
			}
			else throw new xTalkException("cantpeek can only be set on stacks");
		}
		else if(0==prpty.compareTo("cantabort")){
			if(obj!=null&&obj.objectType.equals("stack")){
				((OStack)obj).cantAbort = propertyBoolean("cantAbort", value);
				((OStack)obj).changed = true;
			}
			else throw new xTalkException("cantabort can only be set on stacks");
		}
		else if(0==prpty.compareTo("cantdelete")){
			boolean flag = propertyBoolean("cantDelete", value);
			if(obj instanceof OStack){ ((OStack)obj).cantDelete = flag; ((OStack)obj).changed = true; }
			else if(obj instanceof OCard){ ((OCard)obj).cantDelete = flag; ((OCard)obj).changed = true; }
			else if(obj instanceof OBackground){ ((OBackground)obj).cantDelete = flag; ((OBackground)obj).changed = true; }
			else throw new xTalkException("cantDelete can only be set on stacks, cards and backgrounds");
		}
		else if(0==prpty.compareTo("cantmodify")){
			if(obj instanceof OStack){
				((OStack)obj).changed = true;
				if(PCARD.pc.stack.saveXML!=null && PCARD.pc.stack.saveXML.saveThread!=null){
					//cantmodifyにした/外したことを保存する
					PCARD.pc.stack.saveXML.saveThread.interrupt();
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
					}
				}
				((OStack)obj).cantModify = propertyBoolean("cantModify", value);
			}
			else if(obj instanceof OCard){ ((OCard)obj).cantModify = propertyBoolean("cantModify", value); ((OCard)obj).changed = true; }
			else if(obj instanceof OBackground){ ((OBackground)obj).cantModify = propertyBoolean("cantModify", value); ((OBackground)obj).changed = true; }
			else throw new xTalkException("cantModify can only be set on stacks, cards and backgrounds");
		}
		else if(0==prpty.compareTo("script")){
			if(obj!=null){
				obj.setScript(value);
			}
		}
		else if(0==prpty.compareTo("showpict")){
			if(obj!=null){
				if(obj.objectType.equals("card")||obj.objectType.equals("background")){
					boolean visible = propertyBoolean("showPict", value);
					((OCardBase)obj).showPict = visible;
					if(((OCardBase)obj).picture!=null) ((OCardBase)obj).picture.setVisible(visible);
					//((OCardBase)obj).stack.pcard.repaint();
					((OCardBase)obj).changed = true;
				}
				else throw new xTalkException("showPict can only be set on cards and backgrounds");
			}
		}

		else if(0==prpty.compareTo("audiolevel")){
			//Movie XCMDのウィンドウ用プロパティ(0-256)
			int v;
			if(value.equals("")) value="0";
			try{
				v = Integer.valueOf(value);
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(obj.objectType.equals("window") && ((OWindow)obj).mp3!=null) {
				((OWindow)obj).mp3.setVolume(v);
			}
			else if(obj.objectType.equals("window") && ((OWindow)obj).movieHandle!=null) {
				TQTMovie.setVolume(((OWindow)obj).movieHandle,v);
			}
			else throw new xTalkException("Cannot set audiolevel on {0}", obj.getShortName());
		}
		else if(0==prpty.compareTo("timeformat")){
			//Movie XCMDのウィンドウ用プロパティ
		}
		else {
			SetProperty3(obj, inProperty, value, prpty, btn, fld, cd);
			return;
		}
	}

	private static void SetProperty3(OObject obj, String inProperty, String value, String prpty,
			OButton btn, OField fld, OCard cd)
	throws xTalkException
	{
		if(0==prpty.compareTo("currtime")){
			//Movie XCMDのウィンドウ用プロパティ
			int v;
			if(value.equals("")) value="0";
			try{
				v = Integer.valueOf(value);
			}catch(Exception err){
				throw new xTalkException("A number is required here");
			}
			if(obj.objectType.equals("window") && ((OWindow)obj).mp3!=null) {
				((OWindow)obj).mp3.setTime(v);
			}
			else if(obj.objectType.equals("window") && ((OWindow)obj).movieHandle!=null) {
				TQTMovie.setCurrentTime(((OWindow)obj).movieHandle,v);
			}
			else throw new xTalkException("Cannot set currTime on {0}", obj.getShortName());
		}
		else if(0==prpty.compareTo("scroll")){
			if(fld!=null && fld.style==5) {
				int v;
				try{
					double d = Double.valueOf(value);
					v = (int)d;
				}catch(Exception err){
					throw new xTalkException("A number is required here");
				}
				fld.setScroll(v);
			}
			else if(cd!=null) {
				int v1, v2;
				String[] strAry = value.split(",");
				if(strAry.length!=2){
					throw new xTalkException("Coordinates are required here");
				}
				try{
					double d = Double.valueOf(strAry[0]);
					v1 = (int)d;
					d = Double.valueOf(strAry[1]);
					v2 = (int)d;
				}catch(Exception err){
					throw new xTalkException("A number is required here");
				}
				PCARD.pc.stack.setScroll(v1,v2);
			}
			else if(obj==OWindow.cdwindow) {
				//set the scroll of cd window to x*208+3,0 (RDS experiments): the part of the card the window shows
				String[] strAry = value.split(",");
				if(strAry.length!=2) throw new xTalkException("Coordinates are required here");
				int v1, v2;
				try{
					v1 = (int)Double.parseDouble(strAry[0].trim());
					v2 = (int)Double.parseDouble(strAry[1].trim());
				}catch(NumberFormatException err){
					throw new xTalkException("A number is required here");
				}
				//HyperCard 2.4: kept within the card at the window's size when set, and horizontally a multiple of 16
				//(100,50 -> 96,50; a full-size window stays at 0,0)
				java.awt.Rectangle view = cardWindowRect();
				int maxX = Math.max(0, PCARD.pc.stack.width-PCARD.toLogical(view.width));
				int maxY = Math.max(0, PCARD.pc.stack.height-PCARD.toLogical(view.height));
				PCARD.pc.stack.setScroll(Math.max(0, Math.min(v1, maxX))/16*16, Math.max(0, Math.min(v2, maxY)));
			}
			else if(obj!=null && obj.objectType.equals("window") && ((OWindow)obj).gpw!=null) {
				int v1, v2;
				String[] strAry = value.split(",");
				if(strAry.length!=2){
					throw new xTalkException("Coordinates are required here");
				}
				try{
					double d = Double.valueOf(strAry[0]);
					v1 = (int)d;
					d = Double.valueOf(strAry[1]);
					v2 = (int)d;
				}catch(Exception err){
					throw new xTalkException("A number is required here");
				}
				((OWindow)obj).gpw.setScroll(v1,v2);
			}
		}
		else if(0==prpty.compareTo("style")){
			if(btn!=null) {
				if(value.equalsIgnoreCase("standard")) btn.style=0;
				else if(value.equalsIgnoreCase("transparent")) btn.style=1;
				else if(value.equalsIgnoreCase("opaque")) btn.style=2;
				else if(value.equalsIgnoreCase("rectangle") || value.equalsIgnoreCase("rect")) btn.style=3; //"rect" (ハノイの塔)
				else if(value.equalsIgnoreCase("shadow")) btn.style=4;
				else if(value.equalsIgnoreCase("roundrect")) btn.style=5;
				else if(value.equalsIgnoreCase("default")) btn.style=6;
				else if(value.equalsIgnoreCase("oval")) btn.style=7;
				else if(value.equalsIgnoreCase("popup")) btn.style=8;
				else if(value.equalsIgnoreCase("checkbox")) btn.style=9;
				else if(value.equalsIgnoreCase("radio") || value.equalsIgnoreCase("radiobutton")) btn.style=10;
				else throw new xTalkException("Cannot understand this style");
				OCard.reloadCurrentCard();
			}
			else if(fld!=null) {
				//フィールドのスタイルは transparent/opaque/rectangle/shadow/scrolling (HyperCard) と table/outline
				boolean view = fld.tableStyle || fld.outlineStyle;
				if(value.equalsIgnoreCase("transparent")) fld.setStyleIndex(0);
				else if(value.equalsIgnoreCase("opaque")) fld.setStyleIndex(1);
				else if(value.equalsIgnoreCase("rectangle") || value.equalsIgnoreCase("rect")) fld.setStyleIndex(2);
				else if(value.equalsIgnoreCase("shadow")) fld.setStyleIndex(3);
				else if(value.equalsIgnoreCase("scroll") || value.equalsIgnoreCase("scrolling")) fld.setStyleIndex(4);
				else if(value.equalsIgnoreCase("table")) fld.setStyleIndex(5);
				else if(value.equalsIgnoreCase("outline")) fld.setStyleIndex(6);
				else throw new xTalkException("Cannot understand this style");
				//表・アウトラインになる/やめるときは、その部品だけを作り直す(tableStyle と同じ)
				if(view || fld.tableStyle || fld.outlineStyle) fld.rebuildComponent();
				else OCard.reloadCurrentCard();
			}
			else throw new xTalkException("Cannot set style on this object");
		}
		else if(prpty.equals("blendingmode")){
			int v=0;
			if(value.equalsIgnoreCase("copy")) v = 0;
			else if(value.equalsIgnoreCase("blend")) v = 1;
			if(btn!=null) btn.setBlendMode(v);
		}
		else if(prpty.equals("blendinglevel")){
			int v=0;
			v = Integer.valueOf(value);
			if(v<0 || v>100){		
				throw new xTalkException("Invalid value for property {1} of {0}", obj.getShortName(), prpty);
			}
			if(btn!=null) btn.setBlendLevel(v);
		}
		else if(prpty.equals("color")){
			int r=0,g=0,b=0;
			String[] strs = value.split(",");
			if(strs.length!=3){		
				throw new xTalkException("Color must be specified as r,g,b");
			}
			r = Integer.valueOf(strs[0]);
			g = Integer.valueOf(strs[1]);
			b = Integer.valueOf(strs[2]);
			if(r<0||r>65535 || g<0||g>65535 || b<0||b>65535){		
				throw new xTalkException("Color components must be between 0 and 65535");
			}
			if(btn!=null) {
				btn.setColor(new Color(r/256, g/256, b/256));
			}
			if(fld!=null) {
				fld.setColor(new Color(r/256, g/256, b/256));
			}
			OCard.reloadCurrentCard();
		}
		else if(prpty.equals("hilitecolor")){
			if(btn==null){
				throw new xTalkException("Cannot understand property {1} of {0}", obj.getShortName(), prpty);
			}
			//empty (or "none") goes back to HyperCard's inversion
			if(value.trim().isEmpty() || value.trim().equalsIgnoreCase("none")){
				btn.setHiliteColor(null);
				return;
			}
			String[] strs = value.split(",");
			if(strs.length!=3){
				throw new xTalkException("Color must be specified as r,g,b");
			}
			int r, g, b;
			try{
				r = Integer.valueOf(strs[0].trim());
				g = Integer.valueOf(strs[1].trim());
				b = Integer.valueOf(strs[2].trim());
			}catch(NumberFormatException e){
				throw new xTalkException("Color must be specified as r,g,b");
			}
			if(r<0||r>65535 || g<0||g>65535 || b<0||b>65535){
				throw new xTalkException("Color components must be between 0 and 65535");
			}
			btn.setHiliteColor(new Color(r/256, g/256, b/256));
		}
		else if(prpty.equals("backcolor")){
			int r=0,g=0,b=0;
			String[] strs = value.split(",");
			if(strs.length!=3){		
				throw new xTalkException("Color must be specified as r,g,b");
			}
			r = Integer.valueOf(strs[0]);
			g = Integer.valueOf(strs[1]);
			b = Integer.valueOf(strs[2]);
			if(r<0||r>65535 || g<0||g>65535 || b<0||b>65535){		
				throw new xTalkException("Color components must be between 0 and 65535");
			}
			if(btn!=null) {
				btn.setBgColor(new Color(r/256, g/256, b/256));
			}
			if(fld!=null) {
				fld.setBgColor(new Color(r/256, g/256, b/256));
			}
			OCard.reloadCurrentCard();
		}
		else if(prpty.equals("dithering") && obj instanceof OWindow && ((OWindow)obj).gpw!=null){
			//picture windowのプロパティ(HyperCard 2.2: らんぱぶ, TK HTML Edit が set the dithering of window … to true)
			((OWindow)obj).gpw.dithering = propertyBoolean("dithering", value);
		}
		else if(prpty.equals("scale")){
			//picture windowのプロパティ
			int v=0;
			v = Integer.valueOf(value);
			float vv=1;
			switch(v){
			case -5: vv=1f/32;break;
			case -4: vv=1f/16;break;
			case -3: vv=1f/8;break;
			case -2: vv=1f/4;break;
			case -1: vv=1f/2;break;
			case 1: vv=2;break;
			case 2: vv=4;break;
			case 3: vv=8;break;
			case 4: vv=16;break;
			case 5: vv=32;break;
			}
			if(0==obj.objectType.compareTo("window")){
				if(((OWindow)obj).gpw!=null){
					((OWindow)obj).gpw.label.scale=vv;
					((OWindow)obj).gpw.repaint();
				}
				else{
					throw new xTalkException("Cannot set scale on this object");
				}
			}
			else throw new xTalkException("Cannot set scale on this object");
		}
		else{
			throw new xTalkException("Cannot understand property {1} of {0}", obj.getShortName(), prpty);
		}
	}

		
	public static int getVisualMode(ArrayList<String> strList, int start, int end) throws xTalkException{
		int mode=0; int to=0; int spd=3;
		int next=start;
		
		if(next>=strList.size()) return 0;
		if(strList.get(next).equalsIgnoreCase("cut")) {
			mode=0; next++;
		}
		else if(strList.get(next).equalsIgnoreCase("plain")) {
			mode=0; next++;
		}
		else if(strList.get(next).equalsIgnoreCase("barn")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("door")) {
				next++;
				if(next<strList.size()&&strList.get(next).equalsIgnoreCase("open")) {
					mode=1; next++;
				}
				else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("close")) {
					mode=2; next++;
				}
				else throw new xTalkException("Cannot understand the visual effect");
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("dissolve")) {
			mode=3; next++; 
		}
		else if(strList.get(next).equalsIgnoreCase("venetian")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("blinds")) {
				mode=4; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("checkerboard")) {
			mode=5; next++;
		}
		else if(strList.get(next).equalsIgnoreCase("iris")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("open")) {
				mode=6; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("close")) {
				mode=7; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("scroll")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("left")) {
				mode=8; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("right")) {
				mode=9; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("up")) {
				mode=10; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("down")) {
				mode=11; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("wipe")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("left")) {
				mode=12; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("right")) {
				mode=13; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("up")) {
				mode=14; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("down")) {
				mode=15; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("zoom")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("open")) {
				mode=16; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("in")) {
				mode=16; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("close")) {
				mode=17; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("out")) {
				mode=17; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("shrink")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("to")){
				next++;
				if(next<strList.size()&&strList.get(next).equalsIgnoreCase("top")) {
					mode=18; next++;
				}
				else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("bottom")) {
					mode=19; next++;
				}
				else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("center")) {
					mode=20; next++;
				}
				else throw new xTalkException("Cannot understand the visual effect");
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("stretch")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("from")){
				next++;
				if(next<strList.size()&&strList.get(next).equalsIgnoreCase("top")) {
					mode=21; next++;
				}
				else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("bottom")) {
					mode=22; next++;
				}
				else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("center")) {
					mode=23; next++;
				}
				else throw new xTalkException("Cannot understand the visual effect");
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("push")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("left")) {
				mode=24; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("right")) {
				mode=25; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("up")) {
				mode=26; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("down")) {
				mode=27; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		//HyperZebra の追加効果(After Effects 風のトランジション): fade / cross dissolve / cross fade / mosaic / pixelate /
		//blur / block dissolve / radial wipe / clock wipe / circle open|close / cross zoom / page turn|curl [left|right]
		else if(strList.get(next).equalsIgnoreCase("fade") || strList.get(next).equalsIgnoreCase("crossfade")) {
			mode=VEffect.FADE; next++;
		}
		else if(strList.get(next).equalsIgnoreCase("cross")) {
			next++;
			String w = next<strList.size() ? strList.get(next) : "";
			if(w.equalsIgnoreCase("dissolve") || w.equalsIgnoreCase("fade")) mode=VEffect.FADE;
			else if(w.equalsIgnoreCase("zoom")) mode=VEffect.CROSS_ZOOM;
			else throw new xTalkException("Cannot understand the visual effect");
			next++;
		}
		else if(strList.get(next).equalsIgnoreCase("mosaic") || strList.get(next).equalsIgnoreCase("pixelate")) {
			mode=VEffect.MOSAIC; next++;
		}
		else if(strList.get(next).equalsIgnoreCase("blur")) {
			mode=VEffect.BLUR; next++;
		}
		else if(strList.get(next).equalsIgnoreCase("block")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("dissolve")) {
				mode=VEffect.BLOCK_DISSOLVE; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("radial") || strList.get(next).equalsIgnoreCase("clock")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("wipe")) {
				mode=VEffect.RADIAL_WIPE; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("circle")) {
			next++;
			if(next<strList.size()&&strList.get(next).equalsIgnoreCase("open")) {
				mode=VEffect.CIRCLE_OPEN; next++;
			}
			else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("close")) {
				mode=VEffect.CIRCLE_CLOSE; next++;
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		else if(strList.get(next).equalsIgnoreCase("page")) {
			next++;
			if(next<strList.size()&&(strList.get(next).equalsIgnoreCase("turn") || strList.get(next).equalsIgnoreCase("curl"))) {
				mode=VEffect.PAGE_TURN_LEFT; next++;
				if(next<strList.size()&&strList.get(next).equalsIgnoreCase("left")) next++;
				else if(next<strList.size()&&strList.get(next).equalsIgnoreCase("right")) { mode=VEffect.PAGE_TURN_RIGHT; next++; }
			}
			else throw new xTalkException("Cannot understand the visual effect");
		}
		
		//正式な順序は effect [speed] [to image] だが、to image が先でも受け付ける。
		//speed: fast / slow / slowly / very fast / very slow / very slowly。image: black/white/gray/grey/inverse/card
		boolean sawSpeed=false, sawTo=false;
		while(next<=end && next<strList.size()){
			String w = strList.get(next);
			if(!sawTo && w.equalsIgnoreCase("to")){
				next++;
				if(next>=strList.size() || next>end) throw new xTalkException("Cannot understand the visual effect (missing image after to)");
				String img = strList.get(next);
				if(img.equalsIgnoreCase("black")) to=1;
				else if(img.equalsIgnoreCase("white")) to=2;
				else if(img.equalsIgnoreCase("grey") || img.equalsIgnoreCase("gray")) to=3;
				else if(img.equalsIgnoreCase("inverse")) to=4;
				else if(img.equalsIgnoreCase("card") || img.equalsIgnoreCase("cd")) to=0; //to cd (AOL Stocks 1.0, Menu Editor)
				else throw new xTalkException("Cannot understand the visual effect: to {0}", img);
				next++; sawTo=true;
			}
			else if(!sawSpeed && w.equalsIgnoreCase("fast")){ spd=2; next++; sawSpeed=true; }
			else if(!sawSpeed && (w.equalsIgnoreCase("slow") || w.equalsIgnoreCase("slowly"))){ spd=4; next++; sawSpeed=true; }
			else if(!sawSpeed && w.equalsIgnoreCase("very")){
				next++;
				if(next>=strList.size() || next>end) throw new xTalkException("Cannot understand the visual effect (missing speed after very)");
				String sp = strList.get(next);
				if(sp.equalsIgnoreCase("fast")) spd=1;
				else if(sp.equalsIgnoreCase("slow") || sp.equalsIgnoreCase("slowly")) spd=5;
				else throw new xTalkException("Cannot understand the visual effect: very {0}", sp);
				next++; sawSpeed=true;
			}
			else throw new xTalkException("Cannot understand the visual effect: {0}", w);
		}
		return mode + (to<<8) + (spd<<16);	
	}

	/** The windows in the order of "the windows" (front to back), as HyperCard 2.4 lists them: the message box, the
	 *  watchers and the shown XCMD/palette windows, the tool and pattern windows (HyperCard lists these windoids even when
	 *  they are hidden), and last the card window named after its stack. Each entry is {name, OObject}. */
	static java.util.List<Object[]> windowEntries() {
		boolean longTitles = compatibilityProperty("longWindowTitles").equalsIgnoreCase("true");
		boolean english = PCARD.pc!=null && PCARD.pc.stack!=null && PCARD.pc.stack.legacyTextEncoding!=StackTextEncoding.JAPANESE;
		java.util.List<Object[]> list = new java.util.ArrayList<Object[]>();
		//68K externals' windows (Movie XCMD 1.2 and other XWindows) come first, as HyperCard's XCMD windows do
		if(Xcmd68k.hasWindowSessions()) for(Xcmd68k.ExternalWindow w : Xcmd68k.windows()){
			OXWindow xw = new OXWindow(w);
			if(xw.name!=null && !xw.name.isEmpty()) list.add(new Object[]{xw.name, xw});
		}
		if(GMsg.msg!=null) list.add(new Object[]{"Message", OMsg.msg});
		//picture and movie windows are document windows: a hidden one is behind the windoids and palettes, in front of the card
		//window, and one on show is the first window (HC J-2.3 probe Jhug Window Number: a picture opened hidden is window 8 of
		//9, after show window 8 window 1; JHUG Libra Demo: picture … / set the loc of window 8 / show window 8)
		java.util.List<Object[]> documents = new java.util.ArrayList<Object[]>();
		java.util.List<Object[]> shownDocuments = new java.util.ArrayList<Object[]>();
		for(int i=0; i<OWindow.list.size();i++){
			OWindow w = OWindow.list.get(i);
			if(w==OWindow.msgwindow || w==OWindow.cdwindow || w.name==null) continue;
			if(w.isMovie() || (w.gpw!=null && !w.gpw.isPaletteWindow())){ (w.getVisible() ? shownDocuments : documents).add(new Object[]{w.name, w}); continue; }
			String name = w.name;
			if(english && w.dlog instanceof MessageWatcher) name = "Message Watcher";
			else if(english && w.dlog instanceof VariableWatcher) name = "Variable Watcher";
			//HyperCard J-2.3 names them メッセージ表示 / 変数表示 (the window titles here say ウィンドウ too)
			else if(w.dlog instanceof MessageWatcher) name = "メッセージ表示";
			else if(w.dlog instanceof VariableWatcher) name = "変数表示";
			list.add(new Object[]{name, w});
		}
		list.add(new Object[]{OHcWindow.scroll.name, OHcWindow.scroll}); //HyperCard lists its hidden Scroll and FatBits windoids too
		list.add(new Object[]{OHcWindow.fatbits.name, OHcWindow.fatbits});
		if(OPatternWindow.patternwindow!=null) list.add(new Object[]{"Patterns", OPatternWindow.patternwindow});
		if(OToolWindow.toolwindow!=null) list.add(new Object[]{"Tools", OToolWindow.toolwindow});
		list.addAll(documents);
		list.addAll(0, shownDocuments);
		if(PCARD.pc!=null && PCARD.pc.stack!=null){
			String title = longTitles && PCARD.pc.stack.path!=null && PCARD.pc.stack.cardWindowTitle==null ? TTalk.stackIdentityPath(PCARD.pc.stack.path) : PCARD.pc.stack.getWindowTitle();
			list.add(new Object[]{title, OWindow.cdwindow});
		}
		//stacks left open in windows of their own (go … in a new window): HyperCard lists them behind the card window
		for(String name : TTalk.windowStackNames()) list.add(new Object[]{name, null});
		return list;
	}

	/** window N: the Nth window of "the windows" (Power Tools: repeat with K = the number of windows down to 1 / close window K) */
	static OObject windowByNumber(int n) {
		java.util.List<Object[]> list = windowEntries();
		return n>=1 && n<=list.size() ? (OObject)list.get(n-1)[1] : null;
	}

	/** the owner of window: the external command that made it ("Picture", "Palette", "Movie", the watchers), else HyperCard */
	static String windowOwner(OWindow w) {
		if(w.isMovie()) return "Movie";
		if(w.gpw!=null) return w.gpw.isPaletteWindow() ? "Palette" : "Picture";
		if(w.dlog instanceof VariableWatcher) return "VariableWatcher";
		if(w.dlog instanceof MessageWatcher) return "MessageWatcher";
		return "HyperCard";
	}
}

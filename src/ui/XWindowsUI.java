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
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;

/** Desktop code moved out of the core's TUtil unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class XWindowsUI {
	private XWindowsUI(){}

	// from TUtil
	static void partChangedOnScreen(OObject part, java.awt.Rectangle before){
		if(part==null || !(part instanceof OButton || part instanceof OField) || !Xcmd68k.hasSessions() || !TUtil.partRedrawnByExternal(part)) return;
		if(javax.swing.SwingUtilities.isEventDispatchThread()){ TUtil.partRedrawTimer().execute(() -> partChangedOnScreen(part, before)); return; }
		OStack stack = HZ.stack();
		java.awt.Rectangle r = new java.awt.Rectangle(part.left, part.top, Math.max(1, part.width), Math.max(1, part.height));
		if(before!=null && !before.isEmpty() && !before.equals(r)) r = r.union(before);
		java.awt.Rectangle draw = null;
		synchronized(TUtil.class){
			long t = System.nanoTime();
			TUtil.partChangedAt = t;
			java.awt.Rectangle all = TUtil.pendingPartRedraw==null || TUtil.pendingPartStack!=stack ? r : TUtil.pendingPartRedraw.union(r);
			if(t-TUtil.partRedrawnAt >= TUtil.PART_REDRAW_INTERVAL_MS*1_000_000L){
				draw = all; TUtil.partRedrawnAt = t; TUtil.pendingPartRedraw = null; TUtil.pendingPartStack = null;
			}else{
				boolean schedule = TUtil.pendingPartRedraw==null;
				TUtil.pendingPartRedraw = all; TUtil.pendingPartStack = stack;
				if(schedule) TUtil.schedulePartRedraw(TUtil.PART_REDRAW_INTERVAL_MS);
			}
		}
		if(draw==null) return;
		CardWindow68k.holdScreen();
		try{ Xcmd68k.redrawHookedCards(stack, draw.y, draw.x, draw.y+draw.height, draw.x+draw.width); }
		finally{ CardWindow68k.releaseScreen(); }
	}

	// from TUtil
	static boolean partRedrawnByExternal(OObject part){
		PCARD pc = PCARD.pc;
		if(HZ.lockedScreen || pc==null || pc.stack==null || pc.stack.curCard==null || pc.mainPane==null) return false;
		OCard cd = pc.stack.curCard;
		if(part.parent!=cd && part.parent!=cd.bg) return false;
		return UiHost.get().xcmd68kCardDrawingHooked(pc.stack);
	}
}

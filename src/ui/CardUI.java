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
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.io.DataInputStream;
import java.nio.charset.Charset;
import java.io.*;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Desktop code moved out of the core's TTalk unchanged (tools/wasm-core/moves.txt); the core reaches it through
 * {@link UiHost}. */
final class CardUI {
	private CardUI(){}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void endPaintBeforeHome(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
					PaintTool.endCardTool();
					PCARD.pc.tool = null;
					PaintTool.saveCdPictures();
			    	//ペイント用バッファ
			    	PCARD.pc.releasePaintBuffers();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void emptyCardWindow(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
	    		PCARD.pc.emptyWindow();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void endPaintBeforeGo(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				PaintTool.endCardTool();
				PaintTool.saveCdPicturesLater();
		    	//ペイント用バッファ
		    	PCARD.pc.releasePaintBuffers();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void freezeForCardChange(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				if(self.pendingVisuals.isEmpty()) VEffect.freezeScreen();
				else VEffect.setOldOff();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void dropLeftCardPicture(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, OCard cd) throws xTalkException {
			if(HZ.stack().curCard!=cd) GMenuPaint.cardLeft(); //(Revert: the left card's pictures are on disk now)
			if(HZ.stack().curCard.label!=null && HZ.stack().curCard!=cd) {
				PCARD.pc.mainPane.remove(HZ.stack().curCard.label);
				HZ.stack().curCard.label = null;
				HZ.stack().curCard.pict = null;
			}
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void dropSelectedRange(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				GUI.dropSelectedRange();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void cardRedrawn(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
			CardWindow68k.cardRedrawn();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void playVisualEffects(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				VEffect.playEffects(self.pendingVisuals);
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec3 (the core calls this in their place). */
	static void restartToolAfterGo(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
			if(Host.get().paintToolActive()){
				//ペイントの開始
				String toolName = PCARD.pc.tool.getName();
				PCARD.pc.tool = null;
				TBButtonListener.ChangeTool(toolName, null);
			}
			else if(Host.get().authToolActive()){
				//オーサリングの開始
				if(AuthTool.tool.getClass()==ButtonTool.class){
					TBButtonListener.ChangeTool("button", null);
				}
				else if(AuthTool.tool.getClass()==FieldTool.class){
					TBButtonListener.ChangeTool("field", null);
				}
				else if(AuthTool.tool instanceof ShapeTool){TBButtonListener.ChangeTool("Shape",null);}
			}
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void flashScreen(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str, int cnt) throws xTalkException {
			VEffect.flash(cnt);
	}

	// from TTalk
	/** Lines of TTalk.returnToIdle (the core calls this in their place). */
	static void unlockScreenAtIdle() {
			TTalk.applyDeferredMenuBar();
			VEffect.visualEffect(0, 0, 3);
	}

	// from OStack
	static BufferedImage makeRecentThumbnail(OCard card){
		if(card==null || card.stack==null || card.stack.width<=0 || card.stack.height<=0) return null;
		BufferedImage thumb = new BufferedImage(OStack.RECENT_THUMBNAIL_WIDTH, OStack.RECENT_THUMBNAIL_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = thumb.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.scale(OStack.RECENT_THUMBNAIL_WIDTH/(double)card.stack.width, OStack.RECENT_THUMBNAIL_HEIGHT/(double)card.stack.height);
		CardImageExport.paint(g, card);
		g.dispose();
		return thumb;
	}

	// from TTalk (instance)
	/** Lines of TTalk.goToStack2 (the core calls this in their place). */
	static void endPaintBeforeStack(TTalk self, String stackPath, String cardSpec, MemoryData memData, OObject object, OObject target) throws xTalkException {
		if(Host.get().paintToolActive()){
			PaintTool.endCardTool();
			PCARD.pc.tool = null;
			PaintTool.saveCdPictures();
			PCARD.pc.releasePaintBuffers();
		}
	}

	// from Rsrc
	static BufferedImage loadImage(String path, String type){
		if(path==null) return null;
		BufferedImage bi = null;
		try{
			if(path.toLowerCase().endsWith(".data")){
				byte[] b = java.nio.file.Files.readAllBytes(new File(path).toPath());
				if("ICON".equals(type)) bi = HCResource.decodeICON(b);
				else if("cicn".equals(type)) bi = HCResource.decodeCicn(b);
				else if("CURS".equals(type)) bi = HCResource.decodeCURS(b);
				else if("crsr".equals(type)) bi = HCResource.decodeCrsr(b);
				else if("PAT#".equals(type)) bi = HCResource.decodePAT(b);
				else if("ppat".equals(type)) bi = HCResource.decodePpat(b);
				else if("Pdat".equals(type)) bi = PictureFile.loadPICT(b); //ColorizeHC's pictures: PICT data without the 512-byte file header
				if(bi!=null) return bi;
				//未知の型で.data拡張子の場合はここで諦めず、念のため下の
				//通常の画像読み込みも試す(下のtry内で失敗してもnullを返すだけ)
			}
			bi = PictureFile.loadPbm(path);
			if(bi==null){
				bi = javax.imageio.ImageIO.read(new File(path));
			}
			if(bi==null){
				bi = PictureFile.loadPICT(path);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return bi;
	}
}

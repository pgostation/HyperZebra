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
final class SoundsUI {
	private SoundsUI(){}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void stopSound(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				self.sound().PlayStop();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void stopSpeech(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				Speech.stopAll();
	}

	// from TTalk (instance)
	/** Lines of TTalk.CommandExec13 (the core calls this in their place). */
	static void beepOnce(TTalk self, ArrayList<String> stringList, TTalk.wordType[] typeAry, OObject object, OObject target, MemoryData memData, Result result, String str) throws xTalkException {
				SoundMixer68k.beep();
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction2 (the chain asks this in their place): true when one of them matched. */
	static boolean speechFunctions(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("voices")){
			//the voices (HyperCard 2.3 speech): the voices of the speech engine of this computer (empty when there is none)
			String sp = Speech.programName();
			result.theResult = sp!=null && Sandbox.allowOther(Sandbox.scriptRequester(), Sandbox.RUN_PROGRAM, sp) ? Speech.voices() : "";
		}
		else if(0==message.compareTo("speech")){
			result.theResult = Speech.speechState();
		}
		else return false;
		return true;
	}

	// from TUtil
	/** Branches of TUtil.CallSystemFunction2 (the chain asks this in their place): true when one of them matched. */
	static boolean soundFunction(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result) throws xTalkException {
		if(0==message.compareTo("sound")){
			String name = TTalk.sound().name;
			if(name.equals("")) name = "done";
			result.theResult = name;
		}
		else return false;
		return true;
	}

	// from TTalk
    static TSound sound(){
    	int channel = TTalk.soundChannel();
    	if(channel<=1) return TTalk.tsound;
    	synchronized(TTalk.soundChannels){
    		TSound s = TTalk.soundChannels.get(channel);
    		if(s==null){ s = new TSound(); TTalk.soundChannels.put(channel, s); }
    		return s;
    	}
    }

	// from TTalk
    static void stopAllSounds(){
    	TTalk.tsound.PlayStop(true);
    	java.util.ArrayList<TSound> others;
    	synchronized(TTalk.soundChannels){ others = new java.util.ArrayList<TSound>(TTalk.soundChannels.values()); }
    	for(TSound s: others) s.PlayStop(true);
    }
}

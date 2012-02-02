import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.filechooser.FileFilter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class GMenu {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	JMenuBar mb=new JMenuBar();

	public static boolean changeMenuName(String menu, String item, String name){
		JMenuItem mi = searchMenuItem(PCARD.pc.getJMenuBar(), menu, item);
		if(mi==null) return false;
		
		mi.setText(PCARD.pc.intl.getText(name));
		return true;
	}
	
	public static boolean changeEnabled(String menu, String item, boolean enabled){
		JMenuItem mi = searchMenuItem(PCARD.pc.getJMenuBar(), menu, item);
		if(mi==null) return false;
		
		mi.setEnabled(enabled);
		return true;
	}
	
	public static boolean changeMEnabled(String menu, boolean enabled){
		JMenu m = searchMenu(PCARD.pc.getJMenuBar(), menu);
		if(m==null) return false;
		
		m.setEnabled(enabled);
		return true;
	}
		
	public static boolean changeSelected(String menu, String item, boolean selected){
		JCheckBoxMenuItem mi = (JCheckBoxMenuItem)searchMenuItem(PCARD.pc.getJMenuBar(), menu, item);
		if(mi==null) return false;
		
		mi.setSelected(selected);
		return true;
	}

	public static JMenu searchMenu(JMenuBar mb, String menu){
		if(mb==null) return null;
		int count = mb.getMenuCount();
		//そのまま探す
		for(int i=0; i<count; i++){
			JMenu m = mb.getMenu(i);
			if(m.getText().equals(menu)){
				return m;
			}
		}
		//英語メニューで探す
		for(int i=0; i<count; i++){
			JMenu m = mb.getMenu(i);
			if(PCARD.pc.intl.getEngText(m.getText()).equals(menu)){
				return m;
			}
		}
		
		return null;
	}
	
	public static JMenuItem searchMenuItem(JMenuBar mb, String menu, String item){
		if(mb==null) return null;
		int count = mb.getMenuCount();
		//そのまま探す
		for(int i=0; i<count; i++){
			JMenu m = mb.getMenu(i);
			if(m.getText().equals(menu)){
				int itemcount = m.getItemCount();
				for(int j=0; j<itemcount; j++){
					JMenuItem mi = m.getItem(j);
					if(mi.getText().equals(item)){
						return mi;
					}
				}
			}
		}
		//英語メニューで探す
		for(int i=0; i<count; i++){
			JMenu m = mb.getMenu(i);
			if(PCARD.pc.intl.getEngText(m.getText()).equals(menu)){
				int itemcount = m.getItemCount();
				for(int j=0; j<itemcount; j++){
					JMenuItem mi = m.getItem(j);
					if(mi!=null && PCARD.pc.intl.getEngText(mi.getText()).equals(item)){
						return mi;
					}
				}
			}
		}
		
		return null;
	}
	

	public GMenu(PCARD pcard, int mode){
		ActionListener listener=null;
		
		if(mode==0) {
			listener = new GMenuBrowse();
		}
		else if(mode==1){
			listener = new GMenuPaint();
		}
		
		JMenu m;
		JMenuItem mi;
		JCheckBoxMenuItem cb;
		int s=InputEvent.CTRL_DOWN_MASK;
		int s_shi=InputEvent.CTRL_DOWN_MASK + InputEvent.SHIFT_DOWN_MASK;
		if(isMacOSX()){
			s = InputEvent.META_DOWN_MASK;
			s_shi = InputEvent.META_DOWN_MASK + InputEvent.SHIFT_DOWN_MASK;
		}

	    if(mode==0){
		    // Fileメニュー
		    m=new JMenu(PCARD.pc.intl.getText("File"));
		    mb.add(m);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Stack…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Open Stack…")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));mi.addActionListener(listener);
		    {
				JMenu subm=new JMenu(PCARD.pc.intl.getText("Open Recent Stack"));
				m.add(subm);
		    }
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Close Stack")));mi.setEnabled(false);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Save a Copy…")));mi.setEnabled(false);mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Compact Stack")));mi.setEnabled(false);mi.setEnabled(false);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Protect Stack…")));mi.setEnabled(false);mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Delete Stack…")));mi.setEnabled(false);mi.setEnabled(false);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Print…")));mi.setEnabled(false);

			if(!isMacOSX()){
				m.addSeparator();
			    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("About "+PCARD.AppName+"…")));
			    mi.addActionListener(listener);
			    
				m.addSeparator();
			    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Quit")));
			    mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, s));
			    mi.addActionListener(listener);
			}
			
		    // Editメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Edit"));
		    mb.add(m);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Undo")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);
		    m.addSeparator();
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Paste")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Delete")));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Card")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Delete Card")));mi.addActionListener(listener);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut Card")));mi.setEnabled(false);mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy Card")));mi.setEnabled(false);mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Background")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, s));cb.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Icon…")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Sound…")));mi.setEnabled(false);mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Resource…")));mi.addActionListener(listener);
	    }

	    if(mode==1){
	    	// Fileメニュー
		    m=new JMenu(PCARD.pc.intl.getText("File"));
		    mb.add(m);
		    m.setEnabled(false);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Import Paint…")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Export Paint…")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));*/mi.addActionListener(listener);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Save as ppm…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));*/mi.addActionListener(listener);
		    
		    // Editメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Edit"));
		    mb.add(m);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Undo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Redo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s_shi));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Paste Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Clear Selection")));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Card")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, s));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Background")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, s));cb.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Icon…")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Sound…")));mi.setEnabled(false);mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Resource…")));mi.setEnabled(false);mi.addActionListener(listener);
	    }
	    
	    // Goメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Go"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Back")));mi.setEnabled(false);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DEAD_TILDE, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Home")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Help")));mi.setEnabled(false);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Recent")));mi.setEnabled(false);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("First")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Prev")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_2, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Next")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_3, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Last")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_4, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Find…")));mi.setEnabled(false);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Message")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Next Window")));mi.setEnabled(false);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, s));mi.addActionListener(listener);
	    
	    // Toolメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Tool"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Show ToolBar")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Browse")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Button")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Field")));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Lasso")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("MagicWand")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Pencil")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Brush")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Eraser")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Line")));mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("SprayCan")));mi.setEnabled(false);mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rect")));mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("RoundRect")));mi.setEnabled(false);mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("PaintBucket")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Oval")));mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Curve")));mi.setEnabled(false);mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Type")));mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Polygon")));mi.setEnabled(false);mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("FreePolygon")));mi.setEnabled(false);mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Spoit")));mi.setEnabled(false);mi.addActionListener(listener);
	    
		//0.ブラウズモード
	    if(mode==0){
		    // Objectsメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Objects"));
		    mb.add(m);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Button Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Field Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Card Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Background Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Stack Info…")));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Bring Closer")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Send Farther")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, s));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Button")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Field")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Background")));mi.addActionListener(listener);
		    
	    }
	    
	    if(mode==1){
		    // Paintメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Paint"));
		    mb.add(m);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select All")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));mi.addActionListener(listener);
		    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("FatBits")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, s));cb.addActionListener(listener);
		    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Antialias")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));cb.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Reverse Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Expand Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Add to Protect Area")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Discard Protect Area")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Color Convert…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Emboss…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Filter…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Blending Mode…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Scale Selection…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Fill")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Invert")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Pickup")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Darken")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Lighten")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate Left")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate Right")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Flip Horizontal")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Flip Vertical")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, s));*/mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Opaque")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Transparent")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Keep")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Revert")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Distort")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Stretch")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Perspective")));mi.setEnabled(false);/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);

		}
	}
	
	static boolean isMacOSX(){
		return System.getProperty("os.name").toLowerCase().startsWith("mac os x");
	}
	
	public static void menuUpdate(JMenuBar srcMenuBar){
		JMenuBar menubar = PCARD.pc.getJMenuBar();
		menubar.removeAll();
		for(int i=0; i<srcMenuBar.getComponentCount(); i++){
			Component c = srcMenuBar.getComponent(i);
			if(c.getClass()==JMenu.class){
				JMenu menu = (JMenu)c;
				JMenu newMenu = new JMenu(menu.getText());
				for(int j=0; j<menu.getPopupMenu().getComponentCount(); j++){
					Component c2 = menu.getPopupMenu().getComponent(j);
					if(c2.getClass()==JMenuItem.class){
						JMenuItem mi = (JMenuItem)c2;
						JMenuItem newmi = new JMenuItem(mi.getText());
						newmi.setEnabled(c2.isEnabled());
						ActionListener[] listeners = mi.getActionListeners();
						if(listeners.length>0){
							newmi.addActionListener(listeners[0]);
						}
						newmi.setAccelerator(mi.getAccelerator());
						newMenu.add(newmi);
					}
					else if(c2.getClass()==JCheckBoxMenuItem.class){
						JCheckBoxMenuItem mi = (JCheckBoxMenuItem)c2;
						JCheckBoxMenuItem newmi = new JCheckBoxMenuItem(mi.getText());
						newmi.setEnabled(mi.isEnabled());
						newmi.setSelected(mi.isSelected());
						ActionListener[] listeners = mi.getActionListeners();
						if(listeners.length>0){
							newmi.addActionListener(listeners[0]);
						}
						newmi.setAccelerator(mi.getAccelerator());
						newMenu.add(newmi);
					}
					else if(c2.getClass()==JPopupMenu.Separator.class){
						newMenu.addSeparator();
					}
					else if(c2.getClass()==JMenu.class){
						JMenu menu2 = (JMenu)c2;
						JMenu newMenu2 = new JMenu(menu2.getText());
						for(int k=0; k<menu2.getPopupMenu().getComponentCount(); k++){
							Component c3 = menu2.getPopupMenu().getComponent(k);
							if(c3.getClass()==JMenuItem.class){
								JMenuItem mi = (JMenuItem)c3;
								JMenuItem newmi = new JMenuItem(mi.getText());
								newmi.setEnabled(c3.isEnabled());
								ActionListener[] listeners = mi.getActionListeners();
								if(listeners.length>0){
									newmi.addActionListener(listeners[0]);
								}
								newmi.setAccelerator(mi.getAccelerator());
								newMenu2.add(newmi);
							}
						}
						newMenu.add(newMenu2);
					}
				}
				menubar.add(newMenu);
			}
		}
    	BuildRecentMenu((JMenu)menubar.getComponent(0));
	}
	
	static String[] recentsMenuName = new String[10];
	static String[] recentsMenuPath = new String[10];
	
	public static void BuildRecentMenu(JMenu m){
		GMenuBrowse listener = new GMenuBrowse();
		
		JMenu subm = null;
		for(int i=0; i<m.getPopupMenu().getComponentCount(); i++){
			if(m.getPopupMenu().getComponent(i).getClass() == JMenu.class){
				subm = (JMenu)m.getPopupMenu().getComponent(i);
				subm.removeAll();
			}
		}
		if(subm==null) return;
		
		//最近使ったスタック
		File recentFile = new File("resource_trash"+File.separatorChar+"recent.txt");
		if(recentFile.exists()){
			FileInputStream fis = null;
			try {
				fis = new FileInputStream(recentFile);
				int length = (int)recentFile.length();
				byte[] b = new byte[length];
				fis.read(b);
				String[] recents = new String(b).split("\n");
	    		for(int i=0; i<10 && i<recents.length; i++){
	    			if(new File(recents[i]).exists()){
		    			JMenuItem mi = new JMenuItem((i+1)+" "+new File(recents[i]).getName());
		    			mi.addActionListener(listener);
		    			subm.add(mi);
		    			recentsMenuPath[i] = recents[i];
		    			recentsMenuName[i] = (i+1)+" "+new File(recents[i]).getName();
	    			}
	    		}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		subm.addSeparator();
		JMenuItem mi = new JMenuItem(PCARD.pc.intl.getText("Clear This Menu"));
		mi.addActionListener(listener);
		subm.add(mi);
	}
}


/*class MyMenu extends JMenu
{
	private static final long serialVersionUID = 5858305584938822448L;
	
	public MyMenu(String text) {
		super(text);
		TearoffThread thread = new TearoffThread(this.getPopupMenu());
		thread.start();
	}
	
}


class TearoffThread extends Thread
{
	JPopupMenu popup;
	TearoffThread(JPopupMenu popup){
		this.popup = popup;
	}
	
	public void run(){
		while(!popup.isVisible()){
			try { sleep(10); } catch (InterruptedException e) {}
		}
		while(popup.isVisible()){
			try { sleep(10); } catch (InterruptedException e) {}
			
	        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
	        Rectangle vrect = popup.getVisibleRect();
	        Point p = pointerInfo.getLocation();
			if(p.y>vrect.y && (p.y>vrect.y+vrect.height || p.x<vrect.x || p.x>vrect.x+vrect.width)){
				popup.setLocation(p.x, p.y);
			}
		}
	}
}*/

//ブラウズ時のメニュー動作(オーサリング、ペイントでも使う)
class GMenuBrowse implements ActionListener {
	public void actionPerformed (ActionEvent e) {
		String cmd = e.getActionCommand();
		//System.out.println(cmd);
		
		//スクリプト実行中はメニュー実施しない
		if(TTalk.idle!=true) return;
		
		try{
			doMenu(cmd);
		}catch(xTalkException e1){
			e1.printStackTrace();
		}
	}
	
	public static void doMenu(String in_cmd)
	throws xTalkException
	{
		String cmd = PCARD.pc.intl.getEngText(in_cmd);
		//System.out.println(cmd);

		if(cmd.equalsIgnoreCase("First") ||
				cmd.equalsIgnoreCase("Prev") ||
				cmd.equalsIgnoreCase("Next") ||
				cmd.equalsIgnoreCase("Last"))
		{
			if(PCARD.pc.tool != null){
				PaintTool.saveCdPictures();
			}
			
			if(cmd.equalsIgnoreCase("About "+PCARD.AppName+"…")){
				TTalk.doScriptforMenu("about this");
			}
			else if(cmd.equalsIgnoreCase("First")){
				TTalk.doScriptforMenu("go first");
			}
			else if(cmd.equalsIgnoreCase("Prev")){
				TTalk.doScriptforMenu("go prev");
			}
			else if(cmd.equalsIgnoreCase("Next")){
				TTalk.doScriptforMenu("go next");
			}
			else if(cmd.equalsIgnoreCase("Last")){
				TTalk.doScriptforMenu("go last");
			}
			
			if(PCARD.pc.tool != null){
				//ペイントツールの場合はこれで色々やる
				TBButtonListener.ChangeTool("DummyPaint", null);
			}
			else if(AuthTool.tool != null){
				if(AuthTool.tool.getClass() == ButtonTool.class){
					ButtonGUI.gui.tgtOBtn = null;
					ButtonGUI.gui.target = null;
					PCARD.pc.mainPane.addMouseListener(ButtonGUI.gui);
					PCARD.pc.mainPane.addMouseMotionListener(ButtonGUI.gui);
				}
				else if(AuthTool.tool.getClass() == FieldTool.class){
					FieldGUI.gui.tgtOFld = null;
					FieldGUI.gui.target = null;
					PCARD.pc.mainPane.addMouseListener(FieldGUI.gui);
					PCARD.pc.mainPane.addMouseMotionListener(FieldGUI.gui);
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Home")){
			TTalk.doScriptforMenu("go home");
		}
		else if(cmd.equalsIgnoreCase("Message")){
			if(GMsg.msg.isVisible()) TTalk.doScriptforMenu("hide msg");
			else  TTalk.doScriptforMenu("show msg");
		}
		else if(cmd.equalsIgnoreCase("Show ToolBar")){
			TTalk.doScriptforMenu("show tool window");
		}
		else if(cmd.equalsIgnoreCase("Hide ToolBar")){
			TTalk.doScriptforMenu("hide tool window");
		}
		else if(cmd.equalsIgnoreCase("Browse")){
			TTalk.doScriptforMenu("choose browse tool");
		}
		else if(cmd.equalsIgnoreCase("Button")){
			TTalk.doScriptforMenu("choose button tool");
		}
		else if(cmd.equalsIgnoreCase("Field")){
			TTalk.doScriptforMenu("choose field tool");
		}
		else if(cmd.equalsIgnoreCase("Select")){
			TTalk.doScriptforMenu("choose select tool");
		}
		else if(cmd.equalsIgnoreCase("Lasso")){
			TTalk.doScriptforMenu("choose lasso tool");
		}
		else if(cmd.equalsIgnoreCase("MagicWand")){
			TTalk.doScriptforMenu("choose magicwand tool");
		}
		else if(cmd.equalsIgnoreCase("Pencil")){
			TTalk.doScriptforMenu("choose pencil tool");
		}
		else if(cmd.equalsIgnoreCase("Brush")){
			TTalk.doScriptforMenu("choose brush tool");
		}
		else if(cmd.equalsIgnoreCase("Eraser")){
			TTalk.doScriptforMenu("choose eraser tool");
		}
		else if(cmd.equalsIgnoreCase("Line")){
			TTalk.doScriptforMenu("choose line tool");
		}
		else if(cmd.equalsIgnoreCase("SprayCan")){
			TTalk.doScriptforMenu("choose spraycan tool");
		}
		else if(cmd.equalsIgnoreCase("Rect")){
			TTalk.doScriptforMenu("choose rect tool");
		}
		else if(cmd.equalsIgnoreCase("RoundRect")){
			TTalk.doScriptforMenu("choose roundrect tool");
		}
		else if(cmd.equalsIgnoreCase("PaintBucket")){
			TTalk.doScriptforMenu("choose paintbucket tool");
		}
		else if(cmd.equalsIgnoreCase("Oval")){
			TTalk.doScriptforMenu("choose oval tool");
		}
		else if(cmd.equalsIgnoreCase("Curve")){
			TTalk.doScriptforMenu("choose curve tool");
		}
		else if(cmd.equalsIgnoreCase("Type")){
			TTalk.doScriptforMenu("choose type tool");
		}
		else if(cmd.equalsIgnoreCase("Polygon")){
			TTalk.doScriptforMenu("choose polygon tool");
		}
		else if(cmd.equalsIgnoreCase("FreePolygon")){
			TTalk.doScriptforMenu("choose freepolygon tool");
		}
		else if(cmd.equalsIgnoreCase("Spoit")){
			TTalk.doScriptforMenu("choose spoit tool");
		}
		else if(cmd.equalsIgnoreCase("Home")){
			TTalk.doScriptforMenu("go home");
		}
		else if(cmd.equalsIgnoreCase("Next Window")){
			if(PCARD.pc.stack.scriptEditor!=null){
				PCARD.pc.stack.scriptEditor.toFront();
			}
		}
		else if(cmd.equalsIgnoreCase("Button Info…")){
			if(ButtonGUI.gui.target!=null){
	    		OButton obtn = null;
	    		Component target = ButtonGUI.gui.target;
	        	if(target.getClass() == MyButton.class) obtn = ((MyButton)target).btnData;
	        	if(target.getClass() == RoundedCornerButton.class) obtn = ((RoundedCornerButton)target).btnData;
	        	if(target.getClass() == RectButton.class) obtn = ((RectButton)target).btnData;
	        	if(target.getClass() == RoundButton.class) obtn = ((RoundButton)target).btnData;
	        	if(target.getClass() == MyRadio.class) obtn = ((MyRadio)target).btnData;
	        	if(target.getClass() == MyCheck.class) obtn = ((MyCheck)target).btnData;
	        	if(target.getClass() == MyPopup.class) obtn = ((MyPopup)target).btnData;
	        	AuthDialog.openAuthDialog(PCARD.pc, "button", obtn);
			}else{
	    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("No selected button."),
	    				null,"OK",null,null);
			}
		}
		else if(cmd.equalsIgnoreCase("Field Info…")){
			if(FieldGUI.gui.target!=null){
	    		OField obtn = null;
	    		Component target = FieldGUI.gui.target;
	        	if(target.getClass() == MyTextArea.class) obtn = ((MyTextArea)target).fldData;
	        	if(target.getClass() == MyScrollPane.class) obtn = ((MyScrollPane)target).fldData;
	        	
	        	AuthDialog.openAuthDialog(PCARD.pc, "field", obtn);
			}else{
	    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("No selected field."),
	    				null,"OK",null,null);
			}
		}
		else if(cmd.equalsIgnoreCase("Card Info…")){
			AuthDialog.openAuthDialog(PCARD.pc, "card", PCARD.pc.stack.curCard);
		}
		else if(cmd.equalsIgnoreCase("Background Info…")){
			AuthDialog.openAuthDialog(PCARD.pc, "background", PCARD.pc.stack.curCard.bg);
		}
		else if(cmd.equalsIgnoreCase("Stack Info…")){
			AuthDialog.openAuthDialog(PCARD.pc, "stack", PCARD.pc.stack);
		}
		else if(cmd.equalsIgnoreCase("New Button")){
			TTalk.doScriptforMenu("choose button tool");
			TTalk.doScriptforMenu("create button");
		}
		else if(cmd.equalsIgnoreCase("New Field")){
			TTalk.doScriptforMenu("choose field tool");
			TTalk.doScriptforMenu("create field");
		}
		else if(cmd.equalsIgnoreCase("Bring Closer")){ //to front
			if(AuthTool.tool.getClass()==ButtonTool.class){
				if(ButtonGUI.gui.target!=null){
		    		OButton obtn = null;
		    		Component target = ButtonGUI.gui.target;
		        	if(target.getClass() == MyButton.class) obtn = ((MyButton)target).btnData;
		        	if(target.getClass() == RoundedCornerButton.class) obtn = ((RoundedCornerButton)target).btnData;
		        	if(target.getClass() == RectButton.class) obtn = ((RectButton)target).btnData;
		        	if(target.getClass() == RoundButton.class) obtn = ((RoundButton)target).btnData;
		        	if(target.getClass() == MyRadio.class) obtn = ((MyRadio)target).btnData;
		        	if(target.getClass() == MyCheck.class) obtn = ((MyCheck)target).btnData;
		        	if(target.getClass() == MyPopup.class) obtn = ((MyPopup)target).btnData;
	
		        	if(obtn!=null){
			        	int i = ((OCardBase)obtn.parent).GetNumberof(obtn)-1;
			        	int ip = ((OCardBase)obtn.parent).GetNumberofParts(obtn)-1;
			        	ip++;
			        	if(ip>=((OCardBase)obtn.parent).partsList.size()) ip--;
			        	else if(((OCardBase)obtn.parent).partsList.get(ip).objectType.equals("button")){
			        		i++;
			        	}
			        	((OCardBase)obtn.parent).partsList.remove(obtn);
			        	((OCardBase)obtn.parent).btnList.remove(obtn);
			        	((OCardBase)obtn.parent).partsList.add(ip, obtn);
						((OCardBase)obtn.parent).btnList.add(i, obtn);
	
						OCard.reloadCurrentCard();
						GUI.removeAllListener();
						ButtonGUI.gui.addListenerToParts();
						
						//obtnは生きているがtargetはいなくなるので取り直す
						ButtonGUI.gui.target = obtn.getComponent();
		        	}
				}
				else{
		    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("No selected button."),
		    				null,"OK",null,null);
				}
			}
			if(AuthTool.tool.getClass()==FieldTool.class){
				if(FieldGUI.gui.target!=null){
		    		OField obtn = null;
		    		Component target = FieldGUI.gui.target;
		        	if(target.getClass() == MyTextArea.class) obtn = ((MyTextArea)target).fldData;
		        	if(target.getClass() == MyScrollPane.class) obtn = ((MyScrollPane)target).fldData;
	
		        	if(obtn!=null){
			        	int i = ((OCardBase)obtn.parent).GetNumberof(obtn)-1;
			        	int ip = ((OCardBase)obtn.parent).GetNumberofParts(obtn)-1;
			        	ip++;
			        	if(ip>=((OCardBase)obtn.parent).partsList.size()) ip--;
			        	else if(((OCardBase)obtn.parent).partsList.get(ip).objectType.equals("field")){
			        		i++;
			        	}
			        	((OCardBase)obtn.parent).partsList.remove(obtn);
			        	((OCardBase)obtn.parent).fldList.remove(obtn);
			        	((OCardBase)obtn.parent).partsList.add(ip, obtn);
						((OCardBase)obtn.parent).fldList.add(i, obtn);
	
						OCard.reloadCurrentCard();
						GUI.removeAllListener();
						FieldGUI.gui.addListenerToParts();
						
						//obtnは生きているがtargetはいなくなるので取り直す
						FieldGUI.gui.target = obtn.getComponent();
		        	}
				}
				else{
		    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("No selected field."),
		    				null,"OK",null,null);
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Send Farther")){ //to back
			if(AuthTool.tool.getClass()==ButtonTool.class){
				if(ButtonGUI.gui.target!=null){
		    		OButton obtn = null;
		    		Component target = ButtonGUI.gui.target;
		        	if(target.getClass() == MyButton.class) obtn = ((MyButton)target).btnData;
		        	if(target.getClass() == RoundedCornerButton.class) obtn = ((RoundedCornerButton)target).btnData;
		        	if(target.getClass() == RectButton.class) obtn = ((RectButton)target).btnData;
		        	if(target.getClass() == RoundButton.class) obtn = ((RoundButton)target).btnData;
		        	if(target.getClass() == MyRadio.class) obtn = ((MyRadio)target).btnData;
		        	if(target.getClass() == MyCheck.class) obtn = ((MyCheck)target).btnData;
		        	if(target.getClass() == MyPopup.class) obtn = ((MyPopup)target).btnData;
	
		        	if(obtn!=null){
			        	int i = ((OCardBase)obtn.parent).GetNumberof(obtn)-1;
			        	int ip = ((OCardBase)obtn.parent).GetNumberofParts(obtn)-1;
			        	ip--;
			        	if(ip<0) ip=0;
			        	else if(((OCardBase)obtn.parent).partsList.get(ip).objectType.equals("button")){
			        		i--;
			        	}
			        	((OCardBase)obtn.parent).partsList.remove(obtn);
			        	((OCardBase)obtn.parent).btnList.remove(obtn);
			        	((OCardBase)obtn.parent).partsList.add(ip, obtn);
						((OCardBase)obtn.parent).btnList.add(i, obtn);
						
						OCard.reloadCurrentCard();
						GUI.removeAllListener();
						ButtonGUI.gui.addListenerToParts();
						
						//obtnは生きているがtargetはいなくなるので取り直す
						ButtonGUI.gui.target = obtn.getComponent();
		        	}
				}
				else{
		    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("No selected button."),
		    				null,"OK",null,null);
				}
			}
			if(AuthTool.tool.getClass()==FieldTool.class){
				if(FieldGUI.gui.target!=null){
		    		OField obtn = null;
		    		Component target = FieldGUI.gui.target;
		        	if(target.getClass() == MyTextArea.class) obtn = ((MyTextArea)target).fldData;
		        	if(target.getClass() == MyScrollPane.class) obtn = ((MyScrollPane)target).fldData;
	
		        	if(obtn!=null){
			        	int i = ((OCardBase)obtn.parent).GetNumberof(obtn)-1;
			        	int ip = ((OCardBase)obtn.parent).GetNumberofParts(obtn)-1;
			        	ip--;
			        	if(ip<0) ip=0;
			        	else if(((OCardBase)obtn.parent).partsList.get(ip).objectType.equals("button")){
			        		i--;
			        	}
			        	((OCardBase)obtn.parent).partsList.remove(obtn);
			        	((OCardBase)obtn.parent).fldList.remove(obtn);
			        	((OCardBase)obtn.parent).partsList.add(ip, obtn);
						((OCardBase)obtn.parent).fldList.add(i, obtn);
	
						OCard.reloadCurrentCard();
						GUI.removeAllListener();
						FieldGUI.gui.addListenerToParts();
						
						//obtnは生きているがtargetはいなくなるので取り直す
						FieldGUI.gui.target = obtn.getComponent();
		        	}
				}
				else{
		    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("No selected field."),
		    				null,"OK",null,null);
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Delete Button")){
			if(AuthTool.tool.getClass()==ButtonTool.class){
				if(ButtonGUI.gui.tgtOBtn!=null){
					((OCardBase)ButtonGUI.gui.tgtOBtn.parent).partsList.remove(ButtonGUI.gui.tgtOBtn);
					((OCardBase)ButtonGUI.gui.tgtOBtn.parent).btnList.remove(ButtonGUI.gui.tgtOBtn);
					if(ButtonGUI.gui.tgtOBtn.getComponent()!=null){
						PCARDFrame.pc.mainPane.remove(ButtonGUI.gui.tgtOBtn.getComponent());
					}
					ButtonGUI.gui.target = null;
					ButtonGUI.gui.tgtOBtn = null;
					
					OCard.reloadCurrentCard();
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Delete Field")){
			if(AuthTool.tool.getClass()==FieldTool.class){
				if(FieldGUI.gui.tgtOFld!=null){
					((OCardBase)FieldGUI.gui.tgtOFld.parent).partsList.remove(FieldGUI.gui.tgtOFld);
					((OCardBase)FieldGUI.gui.tgtOFld.parent).fldList.remove(FieldGUI.gui.tgtOFld);
					if(FieldGUI.gui.tgtOFld.getComponent()!=null){
						PCARDFrame.pc.mainPane.remove(FieldGUI.gui.tgtOFld.getComponent());
					}
					FieldGUI.gui.target = null;
					FieldGUI.gui.tgtOFld = null;
					
					OCard.reloadCurrentCard();
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Cut Button") || cmd.equalsIgnoreCase("Copy Button")){
			if(AuthTool.tool.getClass()==ButtonTool.class){
				if(ButtonGUI.gui.tgtOBtn!=null){
					//XMLテキストを取得
					XMLOutputFactory factory = XMLOutputFactory.newInstance();
			        StringWriter stringWriter = new StringWriter();
			        try {
			        	XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);

			            writer.writeStartElement("buttonclip");
			            writer.writeCharacters("\n\t");
			            
			        	{
				            writer.writeStartElement("clipoption");
				            writer.writeCharacters("\n\t\t");
				            
				            if(ButtonGUI.gui.tgtOBtn.icon!=0){
					            writer.writeStartElement("iconpath");
					            writer.writeCharacters(/*PCARD.pc.stack.file.getParent()+File.separatorChar
					            		+*/PCARD.pc.stack.rsrc.getFilePathAll(ButtonGUI.gui.tgtOBtn.icon, "icon"));
					            writer.writeEndElement();
					            writer.writeCharacters("\n\t\t");
					            
					            writer.writeStartElement("iconname");
					            writer.writeCharacters(PCARD.pc.stack.rsrc.getNameAll(ButtonGUI.gui.tgtOBtn.icon, "icon"));
					            writer.writeEndElement();
					            writer.writeCharacters("\n\t\t");
				            }
				            
				            writer.writeStartElement("fontname");
				            writer.writeCharacters(ButtonGUI.gui.tgtOBtn.textFont);
				            writer.writeEndElement();
				            writer.writeCharacters("\n\t\t");
				            
				            writer.writeStartElement("text");
				            writer.writeCharacters(ButtonGUI.gui.tgtOBtn.getText());
				            writer.writeEndElement();
				            writer.writeCharacters("\n\t");
				            writer.writeEndElement();
				            writer.writeCharacters("\n\t\t");
			        	}
			        	
			        	//ボタン情報をXMLにする
			            ButtonGUI.gui.tgtOBtn.writeXML(writer);
			            
			            //buttonclipタグを閉じる
			            writer.writeEndElement();
			            
				        writer.close();
				        
				        //クリップボードにコピー
				        {
							Toolkit kit = Toolkit.getDefaultToolkit();
							Clipboard clip = kit.getSystemClipboard();

							StringSelection ss = new StringSelection(stringWriter.toString());
							clip.setContents(ss, ss);
				        }
				        
				        if(cmd.equalsIgnoreCase("Cut Button")){
				        	//カットのときは削除する
							((OCardBase)ButtonGUI.gui.tgtOBtn.parent).partsList.remove(ButtonGUI.gui.tgtOBtn);
							((OCardBase)ButtonGUI.gui.tgtOBtn.parent).btnList.remove(ButtonGUI.gui.tgtOBtn);
							if(ButtonGUI.gui.tgtOBtn.getComponent()!=null){
								PCARDFrame.pc.mainPane.remove(ButtonGUI.gui.tgtOBtn.getComponent());
							}
							ButtonGUI.gui.target = null;
							ButtonGUI.gui.tgtOBtn = null;
							
							OCard.reloadCurrentCard();
				        }
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Paste Button")){
			if(AuthTool.tool.getClass()==ButtonTool.class){
				Toolkit kit = Toolkit.getDefaultToolkit();
				Clipboard clip = kit.getSystemClipboard();

				String str = null;
				try {
					str = (String)clip.getData(DataFlavor.stringFlavor);
				} catch (UnsupportedFlavorException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
				if(str!=null && str.startsWith("<buttonclip>")){
				    XMLInputFactory factory = XMLInputFactory.newInstance();
				    XMLStreamReader reader = null;
				    try {
						reader = factory.createXMLStreamReader(new ByteArrayInputStream(str.getBytes()));
					} catch (Exception e) {
						e.printStackTrace();
					}

					OCardBase cdbase = PCARD.pc.stack.curCard;
					if(PaintTool.editBackground){
						cdbase = PCARD.pc.stack.curCard.bg;
					}
		        	int newid = 1;
		        	for(; newid<32767; newid++){
		        		if(cdbase.GetPartbyId(newid)==null){
		        			break;
		        		}
		        	}
        			OButton btn = new OButton(cdbase, newid);
				    try {
				    	//clipoptionエレメントを読み出す
				    	String iconpath = "";
				    	String iconname = "";
				    	String fontname = "";
				    	String text = "";
				    	while (reader.hasNext()) {
				            int eventType = reader.next();
				            if (eventType == XMLStreamReader.START_ELEMENT) {
				            	String elm = reader.getLocalName().intern();
				            	if(elm.equals("iconpath")){ iconpath = reader.getElementText(); }
				            	else if(elm.equals("iconname")){ iconname = reader.getElementText(); }
				            	else if(elm.equals("fontname")){ fontname = reader.getElementText(); }
				            	else if(elm.equals("text")){ text = reader.getElementText(); }
				            }
				            else if (eventType == XMLStreamReader.END_ELEMENT) {
				            	String elm = reader.getLocalName();
				            	if(elm.equals("clipoption")){
				            		break;
				            	}
				            }
				    	}
				    	
            			reader = btn.readXML(reader);
            			btn.setText(text);
            			
            			btn.textFont = fontname;
            			boolean isFound = false;
            	        for(int i=0; i<PCARD.pc.stack.fontList.size(); i++){
            	        	OStack.fontClass fontinfo = PCARD.pc.stack.fontList.get(i);
            	        	if(fontinfo.name.equals(fontname)){
            	        		isFound = true;
            	        		break;
            	        	}
            	        }
            	        if(!isFound){
            	        	int newfontid=0;
                	        for(; newfontid<32767; newfontid++){
                	        	boolean isUsed = false;
                    	        for(int i=0; i<PCARD.pc.stack.fontList.size(); i++){
                    	        	OStack.fontClass fontinfo = PCARD.pc.stack.fontList.get(i);
                    	        	if(fontinfo.id==newfontid){
                    	        		isUsed = true;
                    	        		break;
                    	        	}
                    	        }
                    	        if(!isUsed) break;
                	        }
            	        	OStack.fontClass fontinfo = PCARD.pc.stack.new fontClass(newfontid, fontname);
            	        	PCARD.pc.stack.fontList.add(fontinfo);
            	        }
            			
            			if(btn.icon!=0){
            				String path = PCARD.pc.stack.rsrc.getFilePathAll(btn.icon, "icon");
            				//String path = PCARD.pc.stack.file.getParent();
            				/*if(fname!=null){
            					path += File.separatorChar + fname;
            				}*/
	            			if(!iconpath.equals(path)){
	            				//他のスタックからのコピーの場合アイコンリソースもコピーする
	            				if(PCARD.pc.stack.rsrc.getNameAll(btn.icon, "icon")!=null){
		            				//アイコンが既に存在しているので別のIDにする
		            				btn.icon = PCARD.pc.stack.rsrc.getNewResourceId("icon", btn.icon);
		            			}
		            			if(iconpath!=null && iconpath.length()>0 && new File(iconpath).exists()){
		            				//ファイルをコピー
		            				String srcfname = new File(iconpath).getName();
		            				String ext = "";
		            				if(srcfname.lastIndexOf(".")>=0){
										ext = srcfname.substring(srcfname.lastIndexOf("."));
									}
		    						String newFileName = "ICON_"+btn.icon+ext;
		    						String newFilePath = PCARDFrame.pc.stack.file.getParent()+File.separatorChar+newFileName;
		    						FileChannel srcChannel = new FileInputStream(iconpath).getChannel();
		    						FileChannel destChannel = new FileOutputStream(newFilePath).getChannel();
		    						try {
		    							srcChannel.transferTo(0, srcChannel.size(), destChannel);
		    						} finally {
		    							srcChannel.close();
		    							destChannel.close();
		    						}
		    						//リソースに追加
		    						PCARDFrame.pc.stack.rsrc.addResource(btn.icon, "icon", iconname, newFileName);
		            			}
	            			}
            			}
            			
            			cdbase.btnList.add(btn);
            			cdbase.partsList.add(btn);
						
						OCard.reloadCurrentCard();
						
						ButtonGUI.gui.tgtOBtn = btn;
						ButtonGUI.gui.target = btn.getComponent();
            		} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Cut Field") || cmd.equalsIgnoreCase("Copy Field")){
			if(AuthTool.tool.getClass()==FieldTool.class){
				if(FieldGUI.gui.tgtOFld!=null){
					//XMLテキストを取得
					XMLOutputFactory factory = XMLOutputFactory.newInstance();
			        StringWriter stringWriter = new StringWriter();
			        try {
			        	XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);

			            writer.writeStartElement("fieldclip");
			            writer.writeCharacters("\n\t");
			            
			        	{
				            writer.writeStartElement("clipoption");
				            writer.writeCharacters("\n\t\t");
				            
				            writer.writeStartElement("fontname");
				            writer.writeCharacters(FieldGUI.gui.tgtOFld.textFont);
				            writer.writeEndElement();
				            writer.writeCharacters("\n\t\t");
				            
				            writer.writeStartElement("text");
				            writer.writeCharacters(FieldGUI.gui.tgtOFld.getText());
				            writer.writeEndElement();
				            writer.writeCharacters("\n\t");
				            writer.writeEndElement();
				            writer.writeCharacters("\n\t\t");
			        	}
			        	
			        	//フィールド情報をXMLにする
			        	FieldGUI.gui.tgtOFld.writeXML(writer);
			            
			            //fieldclipタグを閉じる
			            writer.writeEndElement();
			            
				        writer.close();
				        
				        //クリップボードにコピー
				        {
							Toolkit kit = Toolkit.getDefaultToolkit();
							Clipboard clip = kit.getSystemClipboard();

							StringSelection ss = new StringSelection(stringWriter.toString());
							clip.setContents(ss, ss);
				        }
				        
				        if(cmd.equalsIgnoreCase("Cut Field")){
				        	//カットのときは削除する
							((OCardBase)FieldGUI.gui.tgtOFld.parent).partsList.remove(FieldGUI.gui.tgtOFld);
							((OCardBase)FieldGUI.gui.tgtOFld.parent).fldList.remove(FieldGUI.gui.tgtOFld);
							if(FieldGUI.gui.tgtOFld.getComponent()!=null){
								PCARDFrame.pc.mainPane.remove(FieldGUI.gui.tgtOFld.getComponent());
							}
							FieldGUI.gui.target = null;
							FieldGUI.gui.tgtOFld = null;
							
							OCard.reloadCurrentCard();
				        }
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Paste Field")){
			if(AuthTool.tool.getClass()==FieldTool.class){
				Toolkit kit = Toolkit.getDefaultToolkit();
				Clipboard clip = kit.getSystemClipboard();

				String str = null;
				try {
					str = (String)clip.getData(DataFlavor.stringFlavor);
				} catch (UnsupportedFlavorException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
				if(str!=null && str.startsWith("<fieldclip>")){
				    XMLInputFactory factory = XMLInputFactory.newInstance();
				    XMLStreamReader reader = null;
				    try {
						reader = factory.createXMLStreamReader(new ByteArrayInputStream(str.getBytes()));
					} catch (Exception e) {
						e.printStackTrace();
					}

					OCardBase cdbase = PCARD.pc.stack.curCard;
					if(PaintTool.editBackground){
						cdbase = PCARD.pc.stack.curCard.bg;
					}
		        	int newid = 1;
		        	for(; newid<32767; newid++){
		        		if(cdbase.GetPartbyId(newid)==null){
		        			break;
		        		}
		        	}
        			OField fld = new OField(cdbase, newid);
				    try {
				    	//clipoptionエレメントを読み出す
				    	String fontname = "";
				    	String text = "";
				    	while (reader.hasNext()) {
				            int eventType = reader.next();
				            if (eventType == XMLStreamReader.START_ELEMENT) {
				            	String elm = reader.getLocalName().intern();
				            	if(elm.equals("fontname")){ fontname = reader.getElementText(); }
				            	else if(elm.equals("text")){ text = reader.getElementText(); }
				            }
				            else if (eventType == XMLStreamReader.END_ELEMENT) {
				            	String elm = reader.getLocalName();
				            	if(elm.equals("clipoption")){
				            		break;
				            	}
				            }
				    	}
				    	
            			reader = fld.readXML(reader);
            			fld.setText(text);
            			
            			fld.textFont = fontname;
            			boolean isFound = false;
            	        for(int i=0; i<PCARD.pc.stack.fontList.size(); i++){
            	        	OStack.fontClass fontinfo = PCARD.pc.stack.fontList.get(i);
            	        	if(fontinfo.name.equals(fontname)){
            	        		isFound = true;
            	        		break;
            	        	}
            	        }
            	        if(!isFound){
            	        	int newfontid=0;
                	        for(; newfontid<32767; newfontid++){
                	        	boolean isUsed = false;
                    	        for(int i=0; i<PCARD.pc.stack.fontList.size(); i++){
                    	        	OStack.fontClass fontinfo = PCARD.pc.stack.fontList.get(i);
                    	        	if(fontinfo.id==newfontid){
                    	        		isUsed = true;
                    	        		break;
                    	        	}
                    	        }
                    	        if(!isUsed) break;
                	        }
            	        	OStack.fontClass fontinfo = PCARD.pc.stack.new fontClass(newfontid, fontname);
            	        	PCARD.pc.stack.fontList.add(fontinfo);
            	        }
            			
            			cdbase.fldList.add(fld);
            			cdbase.partsList.add(fld);
						
						OCard.reloadCurrentCard();
						
						FieldGUI.gui.tgtOFld = fld;
						FieldGUI.gui.target = fld.getComponent();
            		} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
		else if(cmd.equalsIgnoreCase("Background")){
			PaintTool.editBackground = !PaintTool.editBackground;
			GMenu.changeSelected("Edit","Background",PaintTool.editBackground);

			ButtonGUI.gui.tgtOBtn = null;
			ButtonGUI.gui.target = null;
			FieldGUI.gui.tgtOFld = null;
			FieldGUI.gui.target = null;
			
			OCard.reloadCurrentCard();
			
			String titleName = PCARD.pc.stack.name;
			if(titleName.length()>5 && titleName.substring(titleName.length()-5).equals(".xstk")){
				titleName = titleName.substring(0,titleName.length()-5);
			}
			if(PaintTool.editBackground){
				PCARD.pc.setTitle("/// "+titleName+" ///");
			}else{
				PCARD.pc.setTitle(titleName);
			}
		}
		else if(cmd.equalsIgnoreCase("Open Stack…")){
			/*FileDialog fd = new FileDialog(PCARD.pc , PCARD.pc.intl.getDialogText("Open Stack") , FileDialog.LOAD);
			fd.setVisible(true);
			if(fd.getFile()!=null){
				String path = fd.getDirectory()+fd.getFile();
				TTalk.CallMessage("open stack \""+path+"\"", "", null, false, true);
			}*/
			
			JFileChooser chooser = new JFileChooser();
			if(PCARD.pc.stack!=null&&PCARD.pc.stack.file!=null){
				chooser.setCurrentDirectory(new File(new File(PCARD.pc.stack.file.getParent()).getParent()));
			}
			chooser.setDialogTitle(PCARD.pc.intl.getDialogText("Open Stack"));
			chooser.setDialogType(JFileChooser.OPEN_DIALOG);
			chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
			int selected = chooser.showOpenDialog(PCARD.pc);
			if (selected == JFileChooser.APPROVE_OPTION) {
				PCARD.pc.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				String path = chooser.getSelectedFile().getPath();
				String fname = new File(path).getName();
				String parentpath = new File(new File(path).getParent()).getPath();
				if(fname.length()>5 && fname.substring(fname.length()-4).equals(".hzb")){
					//ok
				}
				else if(parentpath.length()>5 && parentpath.substring(parentpath.length()-4).equals(".hzb")){
					// .hzbの中にあるファイルを開くと親が開くようにする
					path = parentpath;
				}
				
				TTalk.CallMessage("open stack \""+path+"\"", "", null, false, true);
			}
		}
		else if(cmd.equalsIgnoreCase("New Stack…")){
			/*FileDialog fd = new FileDialog(PCARD.pc , PCARD.pc.intl.getDialogText("New Stack") , FileDialog.SAVE);
			fd.setVisible(true);
			if(fd.getFile()!=null){
				String path = fd.getDirectory()+fd.getFile();*/
			//MacOSXの標準ファイルダイアログが無用な上書き確認などするので自作する
			JFileChooser chooser = new JFileChooser();
			if(PCARD.pc.stack!=null&&PCARD.pc.stack.file!=null){
				chooser.setCurrentDirectory(new File(new File(PCARD.pc.stack.file.getParent()).getParent()));
			}
			chooser.setDialogTitle(PCARD.pc.intl.getDialogText("New Stack"));
			chooser.setDialogType(JFileChooser.SAVE_DIALOG);
			chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			chooser.setFileFilter(new FileFilter() {
				public boolean accept(File f) {
					//拡張子がhzbのものは非表示
					String name = f.getName();
					int n = name.lastIndexOf('.');
					if (n > 0) {
						String ext = name.substring(n);
						if(ext.equalsIgnoreCase(".hzb")){
							return false;
						}
					}
					if(f.list()!=null && f.list().length!=0){
						return false;
					}
					return true;
				}

				public String getDescription() {
					return PCARD.AppName+" stack(*.hzb)";
				}
			});
			
			int selected = chooser.showSaveDialog(PCARD.pc);
			if (selected == JFileChooser.APPROVE_OPTION) {
		    	//進捗表示を開始
				PCARD.pc.stack.barDialog = new JDialog(PCARD.pc);
				PCARD.pc.stack.barDialog.setUndecorated(true);
				PCARD.pc.stack.barDialog.getContentPane().setLayout(new BorderLayout());
				PCARD.pc.stack.bar = new JProgressBar();
				PCARD.pc.stack.bar.setStringPainted(true);
				PCARD.pc.stack.bar.setSize(new Dimension(PCARD.pc.getWidth(), 20));
				PCARD.pc.stack.bar.setString("");
				PCARD.pc.stack.bar.setValue(0);
				PCARD.pc.stack.barOffset = 0;
				PCARD.pc.stack.barDialog.add("Center", PCARD.pc.stack.bar);
				PCARD.pc.stack.barDialog.setBounds(PCARD.pc.getBounds());
				PCARD.pc.stack.barDialog.setVisible(true);
				PCARD.pc.stack.bar.paintImmediately(PCARD.pc.stack.bar.getBounds());
				PCARD.pc.stack.bar.setString("Make a new stack");
				
				String path = chooser.getSelectedFile().getPath();
				
				//拡張子 hzb を付ける
				int n = path.lastIndexOf('.');
				if (n > 0) {
					String ext = path.substring(n);
					if(!ext.equalsIgnoreCase(".hzb")){
						path += ".hzb";
					}
				} else {
					path += ".hzb";
				}
				
				File f = new File(path);
				if(f.exists()){
					//上書き確認
		    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("This file name already exists. If you continue, the existing file will be replaced."),
		    				null,PCARDFrame.pc.intl.getDialogText("Cancel"),"OK",null);
					if(GDialog.clicked.equals(PCARDFrame.pc.intl.getDialogText("Cancel"))){
						PCARD.pc.stack.barDialog.remove(PCARD.pc.stack.bar);
						PCARD.pc.stack.barDialog.dispose();
			    		return;
					}
		    		int j=1;
            		String renameStr;
            		while(true){
            			renameStr = System.getProperty("java.io.tmpdir")+File.separatorChar+PCARD.AppName+"_renamed"+j;
            			if(!new File(renameStr).exists()){
            				break;
            			}
            			j++;
            		}
            		f.renameTo(new File(renameStr));
				}
				
				PCARD.pc.setTitle("");
				
				if(f.mkdir()){ //ディレクトリ作成
					PCARD.pc.stack.bar.setValue(10);
					PCARD.pc.stack.bar.paintImmediately(PCARD.pc.stack.bar.getBounds());
					
					String xmlFilePath = f.getPath()+File.separatorChar+"_stack.xml";
					File xmlFile = new File(xmlFilePath);
					
					//スタックのインスタンスを作成
					OStack newStack = new OStack(PCARD.pc);
					newStack.file = xmlFile;
					newStack.rsrc = new Rsrc(newStack);
					newStack.scriptList = new ArrayList<String>();
					newStack.width = 640;
					newStack.height = 480;
					
					//カードとバックグラウンドのインスタンスを作成
					OCard cd = new OCard(newStack);
					OBackground bg = new OBackground(newStack);

					cd.id = (int)(Math.random()*9000+1000);
					bg.id = (int)(Math.random()*1000+cd.id+1);
					cd.bgid = bg.id;
					
					newStack.AddNewCard(cd.id);
					//newStack.cdCacheList.add(cd);
					newStack.AddNewBg(bg);
					newStack.firstCard = cd.id;
					newStack.firstBg = bg.id;

					newStack.createdByVersion = PCARD.longVersion;
					newStack.lastCompactedVersion = PCARD.longVersion;
					newStack.firstEditedVersion = PCARD.longVersion;
					newStack.userLevel = 5;

					for(int i=0; i<40; i++){
						newStack.Pattern[i] = "PAT_"+(i+1)+".png";

						File ifile = new File("."+File.separatorChar+"resource"+File.separatorChar+"PAT_"+(i+1)+".png");
						File ofile = new File(f.getPath()+File.separatorChar+"PAT_"+(i+1)+".png");
						try {
							BufferedImage bi = ImageIO.read(ifile);
							ImageIO.write(bi, "png", ofile);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					
					PCARD.pc.stack.bar.setValue(30);
					PCARD.pc.stack.bar.paintImmediately(PCARD.pc.stack.bar.getBounds());
					
					int fontid = 0;
					String fontname = "Chicago";
					if(PCARD.pc.lang.equals("Japanese")){
						fontid = 16384;
						fontname = "Osaka";
					}
					OStack.fontClass fontClass = newStack.new fontClass(fontid, fontname);
					newStack.fontList.add(fontClass);

					PCARD.pc.stack.bar.setValue(50);
					PCARD.pc.stack.bar.paintImmediately(PCARD.pc.stack.bar.getBounds());
					
					//XML書き込み
					XMLwrite xmlWrite = new XMLwrite(newStack);
					xmlWrite.saveStackNow();
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					newStack.clean();

					PCARD.pc.stack.bar.setValue(80);
					PCARD.pc.stack.bar.paintImmediately(PCARD.pc.stack.bar.getBounds());
					
					PCARD.pc.stack.barDialog.remove(PCARD.pc.stack.bar);
					PCARD.pc.stack.barDialog.dispose();
					
					//XMLを開く
					TTalk.CallMessage("open stack \""+f.getPath()+"\"", "", null, false, true);
					
				}
				else{
		    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Can't create a new folder."),
		    				null,"OK",null,null);
				}
			}
		}
		else if(cmd.equalsIgnoreCase("New Card")){
			if(PCARD.pc.stack.curCard==null) return;
			
			//カードのインスタンスを作成
			OCard cd = new OCard(PCARD.pc.stack);

			cd.id = (int)(Math.random()*100+1+PCARD.pc.stack.GetMaxCardId());
			cd.bgid = PCARD.pc.stack.curCard.bg.id;
			cd.changed = true;
			
			PCARD.pc.stack.AddNewCard(PCARD.pc.stack.GetNumberof(PCARD.pc.stack.curCard), cd.id);
			TTalk.CallMessage("go cd id "+cd.id, "", null, false, true);
		}
		else if(cmd.equalsIgnoreCase("New Background")){
			if(PCARD.pc.stack.curCard==null) return;
			
			//バックグラウンドのインスタンスを作成
			OBackground bg = new OBackground(PCARD.pc.stack);
			bg.id = (int)(Math.random()*100+1+PCARD.pc.stack.GetMaxCardId());
			bg.changed = true;
			PCARD.pc.stack.AddNewBg(bg);
			
			//カードのインスタンスを作成
			OCard cd = new OCard(PCARD.pc.stack);
			cd.id = (int)(Math.random()*100+1+PCARD.pc.stack.GetMaxCardId());
			cd.bgid = bg.id;
			cd.changed = true;
			PCARD.pc.stack.AddNewCard(PCARD.pc.stack.GetNumberof(PCARD.pc.stack.curCard), cd.id);
			
			TTalk.CallMessage("go cd id "+cd.id, "", null, false, true);
		}
		else if(cmd.equalsIgnoreCase("Delete Card")){
			if(1==PCARD.pc.stack.cardIdList.size()){
	    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("At least one card is required."),
	    				null,"OK",null,null);
	    		return;
			}
			int previd = 0;
			for(int i=0; i<PCARD.pc.stack.cardIdList.size(); i++){
				if(PCARD.pc.stack.cardIdList.get(i) == PCARD.pc.stack.curCard.id){
					if(i>0){
						previd = PCARD.pc.stack.cardIdList.get(i-1);
					}else{
						//最初のカードの場合
						previd = PCARD.pc.stack.cardIdList.get(i+1);
						PCARD.pc.stack.firstCard = previd;
						PCARD.pc.stack.firstBg = PCARD.pc.stack.GetCardbyId(previd).bgid;
					}
					//カード削除
					PCARD.pc.stack.cardIdList.remove(i);
					
				}
			}
			for(int i=0; i<PCARD.pc.stack.cdCacheList.size(); i++){
				if(PCARD.pc.stack.cdCacheList.get(i).id == PCARD.pc.stack.curCard.id){
					//カード削除
					PCARD.pc.stack.cdCacheList.remove(i);
				}
			}
			int bgid = PCARD.pc.stack.curCard.bgid;
			boolean useBg = false;
			for(int i=0; i<PCARD.pc.stack.cdCacheList.size(); i++){
				if(PCARD.pc.stack.cdCacheList.get(i).bgid == bgid){
					useBg = true;
					break;
				}
			}
			if(!useBg){
				//bg参照数が0なので削除する
				for(int j=0; j<PCARD.pc.stack.bgCacheList.size(); j++){
					if(PCARD.pc.stack.bgCacheList.get(j).id==bgid){
						PCARD.pc.stack.bgCacheList.remove(j);
						break;
					}
				}
			}
			
			if(previd!=0){
				TTalk.CallMessage("go cd id "+previd, "", null, false, true);
			}
		}
		else if(cmd.equalsIgnoreCase("Icon…")){
			new ResEdit(PCARD.pc, "icon", null);
		}
		else if(cmd.equalsIgnoreCase("Sound…")){
			new ResEdit(PCARD.pc, "sound", null);
		}
		else if(cmd.equalsIgnoreCase("Resource…")){
			new ResEditIndex(PCARD.pc);
		}
		else if(cmd.equalsIgnoreCase("Clear This Menu")){
	    	{
	        	File recentFile = new File("resource_trash"+File.separatorChar+"recent.txt");
	    		FileOutputStream fos = null;
	    		try {
	    			fos = new FileOutputStream(recentFile);
	    			fos.write("".getBytes());
	    			fos.close();
	    		} catch (FileNotFoundException e) {
	    			e.printStackTrace();
	    		} catch (IOException e) {
					e.printStackTrace();
				}
	    	}
	    	GMenu.BuildRecentMenu((JMenu)PCARD.pc.getJMenuBar().getComponent(0));
		}
		else if(cmd.equalsIgnoreCase("Quit HyperCard") || cmd.equalsIgnoreCase("Quit")){
			if(PCARD.pc.stack!=null){
				TTalk.doScriptforMenu("closeCard");
				if(PCARD.pc.stack.curCard!=null && PCARD.pc.stack.curCard.bg != null){
					TTalk.doScriptforMenu("closeBackground");
				}
				TTalk.doScriptforMenu("closeStack");
	
				//スタック保存待ち
				for(int j=0; j<100; j++){
					boolean flag = false;
					if(PCARD.pc.stack.changed){
						flag = true;
					}
					for(int i=0 ;i<PCARD.pc.stack.cdCacheList.size(); i++){
						if(PCARD.pc.stack.cdCacheList.get(i).changed){
							flag = true;
						}
					}
					for(int i=0 ;i<PCARD.pc.stack.bgCacheList.size(); i++){
						if(PCARD.pc.stack.bgCacheList.get(i).changed){
							flag = true;
						}
					}
					if(flag){
						try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
						}
					}
					else{
						break;
					}
				}
			}
			
			//終了
			System.exit(0);
		}
		else{
			for(int i=0; i<GMenu.recentsMenuName.length; i++){
				if(cmd.equalsIgnoreCase(GMenu.recentsMenuName[i])){
					TTalk.CallMessage("open stack \""+GMenu.recentsMenuPath[i]+"\"", "", null, false, true);
			    	return;
				}
			}
			//どのメニューにも一致しない
			{
				GMenuPaint.doMenu(cmd);
			}
		}
	}
}

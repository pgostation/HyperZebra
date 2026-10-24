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
import javax.swing.AbstractAction;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.filechooser.FileFilter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class GMenu {
	JMenuBar mb=new JMenuBar();

	//HyperCardの描画モードを、カードとリソース編集の両方で同じ内容にする。
	static void addPaintOptionsMenu(JMenuBar mb, ActionListener listener){
		JMenu options=new JMenu(PCARD.pc.intl.getText("Options"));
		JCheckBoxMenuItem cb;
		JMenuItem mi;
		cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText("Grid Snap"), PaintTool.drawingGrid);
		cb.addActionListener(listener); options.add(cb);
		//描画グリッドの大きさ。よく使う大きさにチェック、他は「その他…」で入力(the gridSize)
		JMenu sizes=new JMenu(PCARD.pc.intl.getText("Drawing Grid Size"));
		for(int size:PaintTool.GRID_SIZES){
			cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText("Drawing Grid Size "+size), PaintTool.drawingGridSize==size);
			cb.addActionListener(listener); sizes.add(cb);
		}
		sizes.addSeparator();
		mi=new JMenuItem(PCARD.pc.intl.getText("Drawing Grid Size…"));
		mi.addActionListener(listener); sizes.add(mi);
		options.add(sizes);
		options.addSeparator();
		cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText("Draw Centered"), PaintTool.drawCentered);
		cb.addActionListener(listener); options.add(cb);
		cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText("Draw Filled"), PaintTool.owner!=null && PaintTool.owner.fill);
		cb.addActionListener(listener); options.add(cb);
		cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText("Draw Multiple"), PaintTool.drawMultiple);
		cb.addActionListener(listener); options.add(cb);
		//Draw Multipleの間隔(HyperTalkのmultiSpace)。1〜9の1つにチェック
		JMenu spaces=new JMenu(PCARD.pc.intl.getText("Multiple Space"));
		for(int space=1; space<=PaintTool.MULTI_SPACE_MAX; space++){
			cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText("Multiple Space "+space), PaintTool.multiSpace==space);
			cb.addActionListener(listener); spaces.add(cb);
		}
		options.add(spaces);
		//対称描画(the symmetry)。1つにチェック。Radialは回転数を入力する
		JMenu symmetry=new JMenu(PCARD.pc.intl.getText("Symmetry"));
		String[] symItems={"Symmetry Off","Vertical Symmetry","Horizontal Symmetry","Quadrant Symmetry","Radial Symmetry…"};
		for(int i=0; i<symItems.length; i++){
			cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText(symItems[i]), PaintTool.symmetry==i);
			cb.addActionListener(listener); symmetry.add(cb);
		}
		symmetry.addSeparator();
		mi=new JMenuItem(PCARD.pc.intl.getText("Symmetry Center…"));
		mi.addActionListener(listener); symmetry.add(mi);
		options.add(symmetry);
		options.addSeparator();
		cb=new JCheckBoxMenuItem(PCARD.pc.intl.getText("Power Keys"), PaintTool.powerKeys);
		cb.addActionListener(listener); options.add(cb);
		mb.add(options);
	}

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
		if(PCARD.pc==null) return false; //テスト等、カードウインドウが無いとき
		JMenuItem found = searchMenuItem(PCARD.pc.getJMenuBar(), menu, item);
		//"Tool"メニューの各ツール項目は(トグル表示の必要が無いため)普通の
		//JMenuItemで作られており、JCheckBoxMenuItemではない。以前は無条件で
		//キャストしていたため、ツール切り替えのたびにClassCastExceptionに
		//なっていた。
		if(!(found instanceof JCheckBoxMenuItem)) return false;

		((JCheckBoxMenuItem)found).setSelected(selected);
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
	
	//サブメニューの中の項目(english=trueなら英語名で比較)
	private static JMenuItem searchSubMenuItem(JMenu sub, String item, boolean english){
		for(int k=0; k<sub.getItemCount(); k++){
			JMenuItem si = sub.getItem(k);
			if(si==null) continue;
			String text = english ? PCARD.pc.intl.getEngText(si.getText()) : si.getText();
			if(text.equals(item)) return si;
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
					//セパレータの位置ではgetItem()がnullを返す(下のループには
					//既にこのチェックがあったが、こちらには無かった)
					if(mi!=null && mi.getText().equals(item)){
						return mi;
					}
					//サブメニュー(Multiple Space等)の中も探す
					if(mi instanceof JMenu){
						JMenuItem sub = searchSubMenuItem((JMenu)mi, item, false);
						if(sub!=null) return sub;
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
					if(mi!=null && (PCARD.pc.intl.getEngText(mi.getText()).equals(item) || mi.getText().equals(PCARD.pc.intl.getText(item)))){
						return mi;
					}
					if(mi instanceof JMenu){
						JMenuItem sub = searchSubMenuItem((JMenu)mi, item, true);
						if(sub!=null) return sub;
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
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Close Stack")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Save a Copy…")));mi.addActionListener(listener); //updateAuthoringMenus: a folder stack is open
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Export HyperCard Stack…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Import SVG…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Export Card Image…")));mi.addActionListener(listener);
		    addTranslationItems(m);
		    m.addSeparator();
		    //このスタックだけの拡大表示・キー・コントローラ・フォルダ外へのアクセス(StackSettings)。セキュリティの設定なので
		    //doMenuを通さない(スタックのdoMenuハンドラに止められない)
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Individual Stack Settings…")));
		    mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ showStackSettings(); }});
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Compact Stack")));mi.setEnabled(false);mi.setEnabled(false);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Protect Stack…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Delete Stack…")));mi.setEnabled(false);mi.setEnabled(false);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Print…")));mi.setEnabled(false);
		    //「設定…」「68Kアプリ設定…」はランチャーのメニューだけにある(スタックのメニューには出さない)

			if(!isMacOSX()){
				//「終了」はmacOSのCmd-Q(アプリケーションメニュー)と同じく、スタックを閉じてランチャーに戻る(PCARD.quitFromStackWindow)。
				//HyperZebra全体の終了はランチャーの「終了」。以前はほかの項目と同じくdoMenuメッセージをカレントカードへ送っていたので、
				//スタックのdoMenuハンドラに捕まる・スクリプトスレッドで保存待ちになるなどで、Windowsでは何も起きないことがあった。
				m.addSeparator();
			    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Quit")));
			    mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, s));
			    mi.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){ PCARD.quitFromStackWindow(); }});
			}
			
		    // Editメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Edit"));
		    mb.add(m);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Undo")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);mi.setEnabled(false);
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Redo")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s_shi));mi.addActionListener(listener);mi.setEnabled(false);
			m.addMenuListener(new javax.swing.event.MenuListener() {
				public void menuSelected(javax.swing.event.MenuEvent e) { AuthoringUndo.updateMenus(); GMenu.updateAuthoringMenus(); }
				public void menuDeselected(javax.swing.event.MenuEvent e) {}
				public void menuCanceled(javax.swing.event.MenuEvent e) {}
			});
		    m.addSeparator();
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Paste")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
			m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Delete")));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Card")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Delete Card")));mi.addActionListener(listener);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut Card")));mi.setEnabled(false);mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy Card")));mi.addActionListener(listener); //CardClipboard (Paste Card: doMenu)
		    m.addSeparator();
		    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Background")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, s));cb.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Icon…")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Sound…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Resource…")));mi.addActionListener(listener);
	    }

	    if(mode==1){
	    	// Fileメニュー
		    m=new JMenu(PCARD.pc.intl.getText("File"));
		    mb.add(m);
		    m.setEnabled(false);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Import Paint…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Export Paint…")));mi.addActionListener(listener);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Save as ppm…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));*/mi.addActionListener(listener);
		    
		    // Editメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Edit"));
		    mb.add(m);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Undo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);mi.setEnabled(!PCARD.pc.paintHistory.past.isEmpty());
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Redo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s_shi));mi.addActionListener(listener);mi.setEnabled(!PCARD.pc.paintHistory.future.isEmpty());
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
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Sound…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Resource…")));mi.setEnabled(false);mi.addActionListener(listener);
	    }
	    
	    // Goメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Go"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Back")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DEAD_TILDE, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Home")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Help")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Recent")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Show Launcher")));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("First")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Prev")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_2, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Next")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_3, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Last")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_4, s));mi.addActionListener(listener);
	    if(mode==0){
	    	//矢印キーでの移動もサポートする（左=最初のカード、右=最後のカード）。
	    	//メニューのアクセラレータと同じ仕組み(ルートペインのWHEN_IN_FOCUSED_WINDOW)で
	    	//登録するので、Cmd+1/Cmd+4と同様にどこにフォーカスがあっても効く。
	    	JRootPane rootPane = PCARD.pc.getRootPane();
	    	rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
	    		KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, s), "hyperzebra.goFirstCard");
	    	rootPane.getActionMap().put("hyperzebra.goFirstCard", new AbstractAction(){
	    		private static final long serialVersionUID = 1L;
	    		public void actionPerformed(ActionEvent e){
	    			if(TTalk.idle!=true) return;
	    			try{ GMenuBrowse.doMenu("First"); }catch(xTalkException ex){ ex.printStackTrace(); }
	    		}
	    	});
	    	rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
	    		KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, s), "hyperzebra.goLastCard");
	    	rootPane.getActionMap().put("hyperzebra.goLastCard", new AbstractAction(){
	    		private static final long serialVersionUID = 1L;
	    		public void actionPerformed(ActionEvent e){
	    			if(TTalk.idle!=true) return;
	    			try{ GMenuBrowse.doMenu("Last"); }catch(xTalkException ex){ ex.printStackTrace(); }
	    		}
	    	});
	    }
	    m.addSeparator();
	    //Find… (HyperCard: cmd-F puts find "" into the message box; the paint menus keep cmd-F for FatBits)
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Find…")));if(mode!=1)mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Message")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Next Window")));mi.setEnabled(false);mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, s));mi.addActionListener(listener);
	    
	    // Toolメニュー
	    //翻訳表(International.java)には"Tools"(複数形)ではなく"Tool"で
	    //登録されているため、"Tools"のままだと一致せず日本語環境でも
	    //英語の"Tools"が表示されていた
	    m=new JMenu(PCARD.pc.intl.getText("Tool"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Show ToolBar")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Browse")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Button")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Field")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Shape")));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Lasso")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("MagicWand")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Pencil")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Brush")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Eraser")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Line")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("SprayCan")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rect")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("RoundRect")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("PaintBucket")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Oval")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Curve")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Type")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Polygon")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("FreePolygon")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Eyedropper")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Smudge")));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Stamp")));mi.addActionListener(listener);
	    
		//0.ブラウズモード
	    if(mode==0){
		    // Objectsメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Objects"));
		    mb.add(m);
		    m.addMenuListener(new javax.swing.event.MenuListener(){
		        public void menuSelected(javax.swing.event.MenuEvent e){GMenu.updateAuthoringMenus();}
		        public void menuDeselected(javax.swing.event.MenuEvent e){}
		        public void menuCanceled(javax.swing.event.MenuEvent e){}
		    });
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Button Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Field Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Shape Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Panel Info…")));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Card Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Background Info…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Stack Info…")));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Bring Closer")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Send Farther")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, s));mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Button")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Field")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Shape")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Panel")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("New Background")));mi.addActionListener(listener);
		    
	    }
	    
	    if(mode==1){
		    // Paintメニュー
		    m=new JMenu(PCARD.pc.intl.getText("Paint"));
		    mb.add(m);
		    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select All")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));mi.addActionListener(listener);
		    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("FatBits")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, s));cb.addActionListener(listener);
		    //任意倍率の拡大縮小(FatBitsは8倍固定)。Cmd+= / Cmd+- / Cmd+0
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Zoom In")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Zoom Out")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, s));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Actual Size")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, s));mi.addActionListener(listener);
		    {
		    	JMenu zoom = new JMenu(PCARD.pc.intl.getText("Zoom"));
		    	for(int pct : new int[]{25,50,100,200,300,400,800,1600,3200}){
		    		zoom.add(mi = new JMenuItem(PCARD.pc.intl.getText("Zoom "+pct+"%")));mi.addActionListener(listener);
		    	}
		    	zoom.add(mi = new JMenuItem(PCARD.pc.intl.getText("Zoom…")));mi.addActionListener(listener);
		    	m.add(zoom);
		    }
		    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Antialias")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s_shi));cb.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Reverse Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Add 1-Pixel Margin to Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Feather Selection…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Grow Selection…")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Shrink Selection…")));mi.addActionListener(listener);
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
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Trace Edges")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Tint")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Blend")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate Left")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate Right")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Flip Horizontal")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Flip Vertical")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, s));*/mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Opaque")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Transparent")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Keep")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Revert")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
		    m.addSeparator();
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rotate")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Slant")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Distort")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Stretch")));mi.addActionListener(listener);
		    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Perspective")));mi.addActionListener(listener);
		    m.addSeparator();
		    {
		    	//追加のペイントレイヤー(PaintLayers)。パレット(Cmd-L)と同じ操作
		    	JMenu layers = new JMenu(PCARD.pc.intl.getText("Layers"));
		    	layers.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Show Layers"), GLayerPalette.isShowing()));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, s));cb.addActionListener(listener);
		    	layers.addSeparator();
		    	for(String item : new String[]{"New Layer","Duplicate Layer","Delete Layer","Merge Down","Flatten Layers"}){
		    		layers.add(mi = new JMenuItem(PCARD.pc.intl.getText(item)));mi.addActionListener(listener);
		    	}
		    	layers.addSeparator();
		    	layers.add(mi = new JMenuItem(PCARD.pc.intl.getText("Move Layer Up")));mi.addActionListener(listener);
		    	layers.add(mi = new JMenuItem(PCARD.pc.intl.getText("Move Layer Down")));mi.addActionListener(listener);
		    	layers.add(mi = new JMenuItem(PCARD.pc.intl.getText("Rename Layer…")));mi.addActionListener(listener);
		    	m.add(layers);
		    }
		    addPaintOptionsMenu(mb, listener);

		}
	}
	
	/** File > "Individual Stack Settings…": the open stack's own zoom, keys, game controller and access (GAppSettingsDialog). */
	static void showStackSettings(){
		File f = PCARD.pc==null || PCARD.pc.stack==null ? null : StackTranslation.fileOf(PCARD.pc.stack);
		if(f==null || !GAppSettingsDialog.isStack(f)){ java.awt.Toolkit.getDefaultToolkit().beep(); return; }
		if(!javax.swing.SwingUtilities.isEventDispatchThread()){ javax.swing.SwingUtilities.invokeLater(GMenu::showStackSettings); return; }
		GAppSettingsDialog.show(PCARD.pc, f);
	}

	/**
	 * File > "Translation Settings…" (GTranslationSettingsDialog): the open stack's own translation settings. Show translation,
	 * "Resource Translation…" and "AI Chat…" are in that dialog, so the menu has this one item.
	 * It acts at once (no doMenu: the stack's own menu handlers are not asked).
	 */
	private static void addTranslationItems(JMenu m){
		m.addSeparator();
		final String settingsText = PCARD.pc.intl.getText("Translation Settings…");
		JMenuItem settings = new JMenuItem(settingsText);
		settings.addActionListener(new ActionListener(){ public void actionPerformed(ActionEvent e){
			File f = StackTranslation.fileOf(PCARD.pc.stack);
			if(f!=null) GTranslationSettingsDialog.show(PCARD.pc, f);
		}});
		m.add(settings);
		//menuUpdateは項目を作り直す(リスナーは引き継ぐ)ので、開いたメニューの中の項目を名前で探して更新する
		m.addMenuListener(new javax.swing.event.MenuListener() {
			public void menuSelected(javax.swing.event.MenuEvent e) {
				File f = PCARD.pc.stack==null ? null : StackTranslation.fileOf(PCARD.pc.stack);
				for(Component c : ((JMenu)e.getSource()).getMenuComponents()){
					if(c instanceof JMenuItem && settingsText.equals(((JMenuItem)c).getText())) ((JMenuItem)c).setEnabled(f!=null);
				}
			}
			public void menuDeselected(javax.swing.event.MenuEvent e) {}
			public void menuCanceled(javax.swing.event.MenuEvent e) {}
		});
	}

	static boolean isMacOSX(){
		return System.getProperty("os.name").toLowerCase().startsWith("mac os x");
	}
	
	public static void menuUpdate(JMenuBar srcMenuBar){
		//the bar on screen is rebuilt on the event thread: a script's create/delete menu changed it while it was being painted
		//(BookStyle's openStack: "No such child" from JComponent.paint)
		if(!javax.swing.SwingUtilities.isEventDispatchThread()){
			try{ javax.swing.SwingUtilities.invokeAndWait(() -> menuUpdate(srcMenuBar)); }
			catch(InterruptedException e){ Thread.currentThread().interrupt(); }
			catch(java.lang.reflect.InvocationTargetException e){
				if(e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
				if(e.getCause() instanceof Error) throw (Error)e.getCause();
			}
			return;
		}
		AuthoringUndo.updateMenus();
		JMenuBar menubar = PCARD.pc.getJMenuBar();
		menubar.removeAll();
		for(int i=0; i<srcMenuBar.getComponentCount(); i++){
			Component c = srcMenuBar.getComponent(i);
			if(c.getClass()==JMenu.class){
				JMenu menu = (JMenu)c;
				JMenu newMenu = new JMenu(menu.getText());
				for (javax.swing.event.MenuListener listener : menu.getMenuListeners()) newMenu.addMenuListener(listener);
				newMenu.setEnabled(menu.isEnabled());
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
		menubar.add(balloonHelpMenu());
		//スクリプトが全メニューを削除した直後は空のことがある
    	if(menubar.getComponentCount()>0 && menubar.getComponent(0) instanceof JMenu){
    		BuildRecentMenu((JMenu)menubar.getComponent(0));
    	}
		updateAuthoringMenus();
		//An in-window bar (Windows/Linux, or no screen menu bar) laid out while it was empty kept that 1 px height: the menus
		//were not shown and the card pane took their 21 px (カウントクロス opened 21 px too tall in some runs)
		menubar.revalidate();
		menubar.repaint();
	}
	
	/**
	 * HyperCard on System 7 has the system's Balloon Help menu at the right end of the bar, its Show Balloons turning balloons on
	 * for HyperCard's externals (HCBalloon, HyperBalloons, Inflate show nothing while they are off). HyperZebra's stand-in: a Help
	 * menu with that one item, for HelpManager68k's one setting shared by every external. It lives only in the bar on the screen,
	 * not in the menus scripts see (the menus, menu numbers, doMenu of HyperCard's menus) nor in HyperCard's menus an external gets.
	 */
	private static JMenu balloonHelpMenu(){
		International intl=PCARD.pc.intl;
		JMenu help=new JMenu(intl==null?"Help":intl.getText("Help"));
		final JCheckBoxMenuItem show=new JCheckBoxMenuItem(intl==null?"Show Balloons":intl.getText("Show Balloons"),HelpManager68k.hyperCardBalloons());
		show.addActionListener(e -> HelpManager68k.setHyperCardBalloons(show.isSelected()));
		help.add(show);
		help.addMenuListener(new javax.swing.event.MenuListener(){
			public void menuSelected(javax.swing.event.MenuEvent e){ show.setSelected(HelpManager68k.hyperCardBalloons()); } //an external's HMSetBalloons may have changed it
			public void menuDeselected(javax.swing.event.MenuEvent e){}
			public void menuCanceled(javax.swing.event.MenuEvent e){}
		});
		return help;
	}

	// Derive menu labels and availability from the current authoring tool and selection.
	static void updateAuthoringMenus(){
		if(PCARD.pc==null||PCARD.pc.getJMenuBar()==null)return;
		boolean button=AuthTool.tool instanceof ButtonTool,field=AuthTool.tool instanceof FieldTool,shape=ShapeTool.active();
		String suffix=button?" Button":field?" Field":shape?" Shape":"";
		boolean hasButton=button&&ButtonGUI.gui.target!=null,hasField=field&&FieldGUI.gui.target!=null;
		boolean hasShape=shape&&ShapeEditor.active(ShapeEditor.selected);
		for(JMenuBar bar:new JMenuBar[]{PCARD.pc.getJMenuBar(),PCARD.pc.menu==null?null:PCARD.pc.menu.mb}){
			if(bar==null)continue;
			for(String action:new String[]{"Cut","Copy","Paste","Delete"}){
				for(String old:new String[]{""," Button"," Field"," Shape"}){
					JMenuItem item=searchMenuItem(bar,"Edit",action+old);
					if(item!=null){item.setText(PCARD.pc.intl.getText(action+suffix));item.setEnabled(suffix.isEmpty()||action.equals("Paste")||hasButton||hasField||hasShape);break;}
				}
			}
			String[] names={"Button Info…","Field Info…","Shape Info…","Panel Info…","Bring Closer","Send Farther"};
			boolean[] enabled={hasButton,hasField,hasShape,GMenuBrowse.hasPanels(),hasButton||hasField||hasShape,hasButton||hasField||hasShape};
			for(int i=0;i<names.length;i++){JMenuItem item=searchMenuItem(bar,"Objects",names[i]);if(item!=null)item.setEnabled(enabled[i]);}
			JMenuItem background=searchMenuItem(bar,"Edit","Background");
			if(background!=null){background.setEnabled(AuthTool.tool!=null||PCARD.pc.tool!=null);if(background instanceof JCheckBoxMenuItem)((JCheckBoxMenuItem)background).setSelected(PaintTool.editBackground);}
		}
		updateSaveACopy();
		applyUserLevel();
	}

	/** File > Save a Copy… works on an open folder stack (.hzb); applyUserLevel then limits it like the other items. */
	private static void updateSaveACopy(){
		boolean can=StackSaving.canSaveElsewhere(PCARD.pc.stack);
		for(JMenuBar bar:new JMenuBar[]{PCARD.pc.getJMenuBar(),PCARD.pc.menu==null?null:PCARD.pc.menu.mb}){
			JMenuItem item=bar==null?null:searchMenuItem(bar,"File","Save a Copy…");
			if(item==null) continue;
			item.putClientProperty(LEVEL_MARK, null); //the stack decides first; a level that is too low disables it again below
			item.setEnabled(can);
		}
	}

	//---- ユーザーレベル(HyperCardのUser Preferencesとスタック保護の「ユーザーレベルの制限」) ----

	private static final String LEVEL_MARK = "hz.userLevelOff";
	/** Menu items and the user level they need; menus not listed here stay as they are. */
	private static final Object[][] LEVEL_ITEMS = {
		{"File","Save a Copy…",1}, //every level (HyperCard's Browsing level keeps it): the open stack does not change
		{"Edit","New Card",2},{"Edit","Delete Card",2},{"Edit","Copy Card",2},
		{"Edit","Background",3},{"Edit","Icon…",4},{"Edit","Sound…",4},{"Edit","Resource…",4},
		{"Tool","Button",4},{"Tool","Field",4},{"Tool","Shape",4},
	};

	private static void levelEnable(java.awt.Component c, boolean allowed){
		if(!(c instanceof JComponent)) return;
		JComponent j=(JComponent)c;
		if(!allowed){ if(j.isEnabled()){ j.setEnabled(false); j.putClientProperty(LEVEL_MARK, Boolean.TRUE); } }
		else if(j.getClientProperty(LEVEL_MARK)!=null){ j.putClientProperty(LEVEL_MARK, null); j.setEnabled(true); }
	}

	private static void levelShow(JMenu m, boolean allowed){
		if(m==null) return;
		if(!allowed){ if(m.isVisible()){ m.setVisible(false); m.putClientProperty(LEVEL_MARK+".v", Boolean.TRUE); } }
		else if(m.getClientProperty(LEVEL_MARK+".v")!=null){ m.putClientProperty(LEVEL_MARK+".v", null); m.setVisible(true); }
		levelEnable(m, allowed);
	}

	/**
	 * Shows the menus and tools of the user level in effect, as HyperCard does: no Tools menu below Painting (3), no
	 * Objects menu and no button/field tools below Authoring (4), no New/Delete Card at Browsing (1). Scripts
	 * (doMenu, choose tool) are not limited. Only the menu bar on screen changes, the one scripts edit stays whole.
	 */
	static void applyUserLevel(){
		if(PCARD.pc==null || PCARD.pc.intl==null) return;
		int level=StackProtection.effectiveUserLevel();
		JMenuBar bar=PCARD.pc.getJMenuBar();
		if(bar!=null && PCARD.pc.menu!=null && bar!=PCARD.pc.menu.mb){
			levelShow(searchMenu(bar,"Tool"), level>=3);
			levelShow(searchMenu(bar,"Objects"), level>=4);
			for(Object[] e:LEVEL_ITEMS){
				JMenuItem item=searchMenuItem(bar,(String)e[0],(String)e[1]);
				if(item!=null) levelEnable(item, level>=(Integer)e[2]);
			}
		}
		if(PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null){
			for(java.awt.Component c:PCARD.pc.toolbar.tb.getContentPane().getComponents()){
				if(c instanceof TBButton) levelEnable(c, level>=StackProtection.levelForTool(PCARD.pc.intl.getToolEngText(c.getName())));
			}
		}
	}

	/** After the user level changed (set userLevel, another stack, Protect Stack): menus, tools and fields follow. */
	static void enforceUserLevel(){
		PCARD.runOnEDT(() -> {
			if(PCARD.pc==null) return;
			applyUserLevel();
			int level=StackProtection.effectiveUserLevel();
			String tool = AuthTool.tool instanceof ButtonTool ? "Button" : AuthTool.tool instanceof FieldTool ? "Field"
				: ShapeTool.active() ? "Shape" : PCARD.pc.tool!=null ? "Pencil" : "Browse";
			if(level<StackProtection.levelForTool(tool)) TBButtonListener.ChangeTool(PCARD.pc.intl.getToolText("Browse"), null);
			if(level<3 && PCARD.pc.toolbar!=null && PCARD.pc.toolbar.tb!=null && PCARD.pc.toolbar.tb.isVisible()) PCARD.pc.toolbar.tb.setVisible(false);
			OField.updateAllTextEditable();
		});
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
		
		//最近使ったスタック(ランチャーの履歴のうちスタック。新しい順に10件)
		java.util.Arrays.fill(recentsMenuName, null);
		java.util.Arrays.fill(recentsMenuPath, null);
		java.util.List<LauncherStore.Item> recents = LauncherStore.get().recentStacks(recentsMenuName.length);
		for(int i=0; i<recents.size(); i++){
			LauncherStore.Item item = recents.get(i);
			if(!item.exists()) continue;
			JMenuItem mi = new JMenuItem((i+1)+" "+item.name());
			mi.addActionListener(listener);
			subm.add(mi);
			recentsMenuPath[i] = item.path;
			recentsMenuName[i] = (i+1)+" "+item.name();
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
	/** doMenu … without dialog の間 true */
	static boolean withoutDialog = false;
	/** スクリプトの doMenu コマンドから呼ばれている間 true(TTalk が立てる)。ユーザーのメニュー操作では false */
	static boolean fromScript = false;
	/** Open Stack…'s "Open stack in new window", as the user left it last (this session) */
	static boolean openStackInNewWindow = true;

	public void actionPerformed (ActionEvent e) {
		String cmd = e.getActionCommand();
		//System.out.println(cmd);
		
		//スクリプト実行中はメニュー実施しない
		if(TTalk.idle!=true) return;
		
		//HyperCardと同様、まず doMenu <項目>,<メニュー> をカレントカードへ送り、
		//捕まえられなければ組み込み動作(TUtil の doMenu → GMenuBrowse.doMenu)
		String menuTitle = "";
		if(e.getSource() instanceof JMenuItem){
			java.awt.Container parent = ((JMenuItem)e.getSource()).getParent();
			if(parent instanceof JPopupMenu && ((JPopupMenu)parent).getInvoker() instanceof JMenu)
				menuTitle = ((JMenu)((JPopupMenu)parent).getInvoker()).getText();
		}
		if(PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null){
			TTalk.CallMessage("doMenu", new String[]{cmd, menuTitle}, PCARD.pc.stack.curCard);
			return;
		}
		try{
			doMenu(cmd);
		}catch(xTalkException e1){
			e1.printStackTrace();
		}
	}

	/** A file inside a .hzb folder stands for the stack (the folder); anything else is itself. */
	static File stackFolderOf(File f) {
		if(f==null) return null;
		f = StackBundle.host(f); //a file in a stack file's working folder stands for the stack file
		String fname = f.getName();
		if(fname.length()>5 && fname.substring(fname.length()-4).equalsIgnoreCase(".hzb")) return f;
		File parent = f.getParentFile();
		if(parent!=null && parent.getName().length()>5 && parent.getName().substring(parent.getName().length()-4).equalsIgnoreCase(".hzb")) return parent;
		return f;
	}

	/** Directory beside the current stack for file dialogs; a relative stack path must be resolved first. */
	static File stackChooserDirectory(File stackFile) {
		if(stackFile==null) return null;
		File absoluteFile = StackBundle.host(stackFile).getAbsoluteFile(); //beside a stack file (.hzb ZIP), not in its working folder
		File directory = absoluteFile.getParentFile();
		if(directory==null) return null;
		String name = absoluteFile.getName();
		if(directory.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb")
				|| name.equalsIgnoreCase("_stack.xml") || name.equalsIgnoreCase("toc.xml")) {
			directory = directory.getParentFile();
		}
		return directory;
	}

	/** Next Window: the card window comes to the front when another of HyperZebra's windows is in front of it (a script
	 *  editor, an external's or a picture window); otherwise the next of those comes forward, except a backdrop that
	 *  surrounds the card window. */
	static void nextWindow() {
		Runnable r = () -> {
			PCARD pc = PCARD.pc;
			if(pc==null) return;
			//a backdrop an external put around the card window goes behind it whether the card window is active or not, even
			//before its native window is up (ColorCover's black cover: Daisuki♡Ver2 opened over another stack stayed black)
			if(Xcmd68k.sendBackdropsBehindHyperCard(pc.getBounds())){
				if(pc.isShowing()) pc.toFront();
				return;
			}
			if(!pc.isActive() && pc.isShowing()){
				//the external window in front (active, or a backdrop around the card) goes behind the card for good: when focus
				//came back to it later (an alert closing) it covered the card again
				java.awt.Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
				if(active!=null && active!=pc) Xcmd68k.sendBehindHyperCard(active);
				for(java.awt.Window w : java.awt.Window.getWindows())
					if(w!=pc && w!=active && w.isShowing() && w.getBounds().contains(pc.getBounds())) Xcmd68k.sendBehindHyperCard(w);
				pc.toFront(); return;
			}
			for(java.awt.Window w : java.awt.Window.getWindows()){
				if(w!=pc && w.isShowing() && w.getOwner()!=pc && !(w instanceof javax.swing.JDialog && ((javax.swing.JDialog)w).isModal())){
					//a backdrop around the card window (xWindowFrame's black frame) stays behind it
					if(w.getWidth()>0 && w.getHeight()>0 && !w.getBounds().contains(pc.getBounds())){ w.toFront(); return; }
				}
			}
		};
		if(javax.swing.SwingUtilities.isEventDispatchThread()) r.run();
		else try{ javax.swing.SwingUtilities.invokeAndWait(r); }catch(Exception e){ /* the window order only */ }
	}

	/** doMenu <item>[, <menu>]: カスタムメニューの項目なら menuMessage を実行し、それ以外は組み込みのメニュー動作 */
	public static void doMenu(String item, String menuName) throws xTalkException {
		//HyperCard's Edit menu says "Clear Button"/"Clear Field" where HyperZebra's says Delete
		if(item!=null && item.equalsIgnoreCase("Clear Button")) item = "Delete Button";
		else if(item!=null && item.equalsIgnoreCase("Clear Field")) item = "Delete Field";
		//and "Bkgnd Info..." where HyperZebra's Objects menu says Background Info… (HyperCard 2.x Home's b)
		else if(item!=null && (item.equalsIgnoreCase("Bkgnd Info...") || item.equalsIgnoreCase("Bkgnd Info…"))) item = "Background Info…";
		//"select bg button 1" then doMenu "Copy Button" (SimOfAnt): the Edit menu follows the selection a script just made
		GMenu.updateAuthoringMenus();
		//Go > Next Window (Myst Preview: xWindowFrame opens a black backdrop window, then doMenu "next window" brings the card
		//window back in front of it). HyperZebra keeps the item disabled in its menu, but the command always works
		if(item!=null && (menuName==null || menuName.isEmpty() || menuName.equalsIgnoreCase("Go")) &&
				(item.equalsIgnoreCase("Next Window") || item.equals(PCARD.pc.intl!=null ? PCARD.pc.intl.getText("Next Window") : "Next Window"))){
			nextWindow();
			return;
		}
		//Copy Card / Paste Card (HyperCard's Edit menu; Paste is "Paste Card" while a card is on the clipboard): scripts duplicate
		//template cards with them (If Monks Had Macs "Journal")
		if(item!=null && (menuName==null || menuName.isEmpty() || menuName.equalsIgnoreCase("Edit") ||
				(PCARD.pc.intl!=null && menuName.equalsIgnoreCase(PCARD.pc.intl.getText("Edit"))))){
			String eng = PCARD.pc.intl!=null ? PCARD.pc.intl.getEngText(item) : item;
			if((eng.equalsIgnoreCase("Copy Card") || item.equalsIgnoreCase("Copy Card")) && TTalk.findMenu("Edit")!=null &&
					TTalk.findMenuItemIndex(TTalk.findMenu("Edit"), item)>=0){
				if(PCARD.pc.tool!=null) PaintTool.saveCdPictures();
				CardClipboard.copy(PCARD.pc.stack.curCard);
				return;
			}
			if(eng.equalsIgnoreCase("Paste Card") || item.equalsIgnoreCase("Paste Card")){
				OCard pasted = CardClipboard.paste(PCARD.pc.stack.curCard);
				TTalk.sendNewCard = true; //移動先で(openCardの前に)newCardを送る(New Card と同じ)
				goToNewCard(pasted.id);
				return;
			}
		}
		JMenu menu = menuName!=null && !menuName.isEmpty() ? TTalk.findMenu(menuName) : null;
		//doMenu "Keep", "Paint" right after "choose tool 4" (J-Paint's filters): the Paint menu is in the paint menu bar
		if(menu==null && menuName!=null && !menuName.isEmpty()) menu = TTalk.findPaintMenu(menuName);
		//doMenu "ことえり", "Keyboards": the system's Keyboard menu, kept for scripts only (ScreenSaver Stack)
		if(menu==null && TTalk.isKeyboardsMenuName(menuName)){
			if(TTalk.chooseKeyboardsMenuItem(item)) return;
			throw new xTalkException("No such menu item: {0}", item);
		}
		//doMenu "Show All" of the system's Application menu, kept for scripts only (does nothing)
		if(menu==null && TTalk.isApplicationMenuName(menuName)){
			if(TTalk.chooseApplicationMenuItem(item)) return;
			throw new xTalkException("No such menu item: {0}", item);
		}
		//doMenu "Plain", "Style" (PALotto; HC J-2.3 probe Pan doMenu Style: no error with nothing selected): HyperCard's Style and
		//Font menus, which HyperZebra's menu bar lacks, style the selected text
		if(menu==null && menuName!=null && TTalk.isHyperCardOnlyMenu(menuName) && TTalk.virtualHyperCardMenu(menuName)!=null){
			String vn = java.text.Normalizer.normalize(menuName.trim(), java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
			boolean style = vn.equals("style") || vn.equals("スタイル");
			OField field = TUtil.findFocusedOrClickedField();
			boolean selected = field!=null && field.fld!=null && field.fld.getSelectionEnd()>field.fld.getSelectionStart();
			if(selected && item!=null && (style ? item.trim().matches("(?i)plain|bold|italic|underline|outline|shadow|condense|extend|group") : vn.equals("font") || vn.equals("フォント")))
				TTalk.doScriptforMenu("set the "+(style ? "textStyle" : "textFont")+" of the selectedChunk to \""+item.trim().replace("\"", "")+"\"");
			return;
		}
		if(menuName!=null && !menuName.isEmpty() && menu==null) throw new xTalkException("No such menu: {0}", menuName);
		java.util.List<JMenu> menus = menu!=null ? java.util.Collections.singletonList(menu) : new java.util.ArrayList<JMenu>(TTalk.menuList());
		if(menu==null) menus.add(TTalk.appleMenu); //doMenu "About X…" (アップルメニューの項目1)
		for(JMenu m : menus){
			int idx = TTalk.findMenuItemIndex(m, item);
			if(idx<0) continue;
			java.awt.Component c = m.getPopupMenu().getComponent(idx);
			if(!(c instanceof JMenuItem)) continue;
			JMenuItem mi = (JMenuItem)c;
			//HyperCard J-2.3 runs a disabled script item's menuMsg anyway; a disabled built-in item (Undo with nothing to undo,
			//Norbik Nudley) does nothing, no error
			//Background is the exception: HyperCard J-2.3 measured (Oct 24 2026), doMenu "Background" in the Browse tool turns
			//background editing on and the tool stays Browse, so Palette XFCN's Install (Background, New Button, Background) makes
			//its hidden button on the background - here it went on the card and the stack's own "Boing" was renamed instead
			if(!mi.isEnabled() && mi.getClientProperty("menuMessage")==null && !"Background".equalsIgnoreCase(PCARD.pc.intl!=null ? PCARD.pc.intl.getEngText(item) : item)){
				return; //doMenu "Compact Stack" も同じ(スタック整理はここでは不要)
			}
			Object msg = mi.getClientProperty("menuMessage");
			if(msg!=null){
				//スクリプトで作った項目: menuMessage を実行する。空なら同じ名前の組み込みコマンド(無ければ何もしない)。
				//GUN GRAVE REMIX: put "About...,-,Quit HyperCard" into menu "File" の「Quit HyperCard」で終了する
				String script = msg.toString();
				if(script.length()>0) TTalk.doScriptforMenu(script);
				else doMenu(mi.getText());
				return;
			}
			doMenu(mi.getText());
			return;
		}
		//「最近使ったスタックを開く」の項目は表示用メニューバーにしか無い(BuildRecentMenu)ので、モデル側の
		//サブメニューでは見つからない。組み込み動作へ渡す
		//HyperCard's Edit menu says Cut/Copy/Paste/Clear Picture while a paint tool is chosen, where HyperZebra's says Cut/Copy/
		//Paste/Clear: doMenu "Clear Picture", "Edit" (UxGetInputLevel's wave buttons) works as without the menu name
		//Paste Picture also in the browse tool, where HyperCard's Edit menu says Paste Picture while a picture is on the clipboard
		//(JHUG KURUMIのX'masKit: domenu "paste picture","edit" in a button's mouseUp)
		if(menu!=null && item!=null && (PCARD.pc.tool!=null || item.equalsIgnoreCase("paste picture")) && item.matches("(?i)(cut|copy|paste|clear) picture") &&
				(menuName.equalsIgnoreCase("Edit") || PCARD.pc.intl!=null && menuName.equalsIgnoreCase(PCARD.pc.intl.getText("Edit")))){
			doMenu(item);
			return;
		}
		if(menu!=null && !isRecentMenuItem(item)) throw new xTalkException("No such menu item: {0}", item);
		if(menu==null && TTalk.chooseKeyboardsMenuItem(item)) return; //doMenu "U.S." (ScreenSaver Stack: the input source to use)
		doMenu(item); //組み込みメニューの英語名など
	}
	
	/** 「最近使ったスタックを開く」サブメニューの項目(N ファイル名 / このメニューを消去)か */
	private static boolean isRecentMenuItem(String item) {
		if(item==null) return false;
		if(item.equalsIgnoreCase("Clear This Menu") ||
			(PCARD.pc.intl!=null && item.equalsIgnoreCase(PCARD.pc.intl.getText("Clear This Menu")))) return true;
		for(String name : GMenu.recentsMenuName){
			if(name!=null && name.equalsIgnoreCase(item)) return true;
		}
		return false;
	}

	/** File > New Stack… (the card window's menu and the launcher): asks where to save the new stack and makes it there
	 *  (a .hzb file with one card). Returns the stack file to open, or null when cancelled or it could not be made.
	 *  {@code forScripts}: as PCARD.showFileChooser - the launcher's own dialog passes false. */
	static File newStackDialog(Component parent, boolean forScripts) {
		//MacOSXの標準ファイルダイアログが無用な上書き確認などするので自作する
		JFileChooser chooser = new JFileChooser();
		File directory = null;
		if(PCARD.pc.stack!=null&&PCARD.pc.stack.file!=null) directory = stackChooserDirectory(PCARD.pc.stack.file);
		if(directory==null) directory = AppPaths.stateDirectory(); //no stack open (the launcher): HyperZebra HD
		if(directory!=null) chooser.setCurrentDirectory(directory);
		chooser.setDialogTitle(PCARD.pc.intl.getDialogText("New Stack"));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);
		chooser.setFileFilter(new FileFilter() {
			public boolean accept(File f) {
				//保存先のフォルダはどれでも選べる(中身があっても)。フォルダ形式の古いスタック(.hzb フォルダ)には入らない。
				//ファイルは既存のスタック(.hzb)だけを見せる(同じ名前を避けやすいように)
				boolean hzb = f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hzb");
				if(f.isDirectory()) return !hzb;
				return hzb;
			}

			public String getDescription() {
				return PCARD.AppName+" stack(*.hzb)";
			}
		});
		FileChooserPlaces.attach(chooser);
		//「別名保存:」(Aqua の保存ダイアログの名前欄)では既存スタックの複製と紛らわしい: HyperCard の新規スタックと同じ「新規スタック名:」
		setFileNameLabel(chooser, PCARD.pc.intl.getDialogText("New stack name:"));

		int selected = PCARD.showFileChooser(chooser, parent, true, forScripts);
		if (selected != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile()==null) return null;

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
				return null;
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

		boolean created;
		try(StackProgress.Busy busy = StackProgress.busy()){
			created = createNewStack(f, 512, 342, null);
		}
		if(!created){
			new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Can't create a new folder."),
					null,"OK",null,null);
			return null;
		}
		return f;
	}

	/** The label of a save dialog's name field (Aqua: "Save As:", Metal: "File Name:"), the JLabel labelling its text field. */
	static void setFileNameLabel(java.awt.Container c, String text) {
		for(Component child: c.getComponents()){
			if(child instanceof javax.swing.JLabel && ((javax.swing.JLabel)child).getLabelFor() instanceof javax.swing.JTextField){
				((javax.swing.JLabel)child).setText(text);
			}
			else if(child instanceof java.awt.Container) setFileNameLabel((java.awt.Container)child, text);
		}
	}

	//新しいスタック(1ファイル形式の .hzb、StackBundle)を作る。カード1枚とバックグラウンド1つを持つ。
	//template が非nullならそのバックグラウンドの名前・スクリプト・部品・ピクチャを写す(create stack ... with background)。
	//作成できなければfalse。
	static boolean createNewStack(File f, int width, int height, OBackground template) {
		if(f.exists()) return false;
		File folder; //the stack file's working folder: the first write makes the file
		try{ folder = StackBundle.create(f); }catch(IOException e){ return false; }
		String xmlFilePath = folder.getPath()+File.separatorChar+"_stack.xml";
		File xmlFile = new File(xmlFilePath);

		//スタックのインスタンスを作成
		OStack newStack = new OStack(PCARD.pc);
		newStack.file = xmlFile;
		newStack.rsrc = new Rsrc(newStack);
		newStack.scriptList = new ArrayList<String>();
		newStack.width = width;
		newStack.height = height;

		//カードとバックグラウンドのインスタンスを作成
		OCard cd = new OCard(newStack);
		OBackground bg = new OBackground(newStack);

		cd.id = (int)(Math.random()*9000+1000);
		bg.id = (int)(Math.random()*1000+cd.id+1);
		cd.bgid = bg.id;

		if(template!=null){
			bg.name = template.name;
			if(template.scriptList!=null) bg.scriptList = new ArrayList<String>(template.scriptList);
			bg.osaScript = template.osaScript;
			if(template.pict!=null){
				BufferedImage copy = new BufferedImage(template.pict.getWidth(), template.pict.getHeight(), template.pict.getType());
				copy.getGraphics().drawImage(template.pict, 0, 0, null);
				bg.pict = copy;
			}
			for(OPanel panel : template.panelList) bg.panelList.add(panel.copyFor(bg));
			for(OObject part : template.partsList){
				try{
					OObject copy;
					if(part instanceof OField){
						OField dst = ((OField)part).copyForBackground(bg);
						if(dst==null) continue;
						dst.panelId = part.panelId;
						bg.fldList.add(dst);
						copy = dst;
					}else if(part instanceof OButton){
						OButton src = (OButton)part;
						OButton dst = new OButton(bg, src.id);
						dst.name = src.name; dst.panelId = src.panelId; dst.style = src.style;
						dst.setRect(src.left, src.top, src.left+src.width, src.top+src.height);
						if(src.scriptList!=null) dst.scriptList = new ArrayList<String>(src.scriptList);
						dst.osaScript = src.osaScript;
						dst.setText(src.getText());
						bg.btnList.add(dst);
						copy = dst;
					}else continue;
					bg.partsList.add(copy);
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		}

		newStack.AddNewCard(cd.id);
		newStack.AddNewBg(bg);
		newStack.firstCard = cd.id;
		newStack.firstBg = bg.id;

		newStack.createdByVersion = PCARD.longVersion;
		newStack.lastCompactedVersion = PCARD.longVersion;
		newStack.firstEditedVersion = PCARD.longVersion;
		newStack.userLevel = 5;

		//パターンはMac本来のppat(カラーのピクセルパターン)としてスタックに置く
		for(int i=0; i<40; i++){
			newStack.Pattern[i] = "PAT_"+(i+1)+".ppat";
			File ifile = new File("."+File.separatorChar+"resource"+File.separatorChar+"PAT_"+(i+1)+".png");
			File ofile = new File(folder, "PAT_"+(i+1)+".ppat");
			try {
				BufferedImage bi = ImageIO.read(ifile);
				java.nio.file.Files.write(ofile.toPath(), HCResourceExport.ppat(bi));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		int fontid = 0;
		String fontname = "Chicago";
		if(International.uses("ja")){
			fontid = 16384;
			fontname = "Osaka";
		}
		OStack.fontClass fontClass = newStack.new fontClass(fontid, fontname);
		newStack.fontList.add(fontClass);

		//XML書き込み
		XMLwrite xmlWrite = new XMLwrite(newStack);
		if(!xmlWrite.saveStackNow()){
			//作れなかったことを伝える(以前は書けなくても作成できたことになっていた)
			new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("The stack could not be saved.")+"\n"+StackSaving.reason(xmlWrite.lastError), null, "OK", null, null);
			newStack.clean();
			StackBundle.discard(folder);
			return false;
		}
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		newStack.clean();
		return true;
	}

	//bg部品を削除したとき、各カードが持っているその部品のデータ
	//(非共有フィールドのテキスト・ボタンのハイライト)も一緒に消す。
	//残っていると保存時に存在しない部品のcontentが書き出され、
	//以前はOCard.writeXMLでNPEになって自動保存が止まっていた。
	static void removeCardDataForBgPart(OObject part){
		if(part.parent==null || !part.parent.objectType.equals("background")) return;
		int bgid = part.parent.id;
		for(OCard cd : PCARD.pc.stack.cdCacheList){
			if(cd.bgid!=bgid) continue;
			boolean removed = false;
			if(part instanceof OButton) removed = cd.bgbtnList.removeIf(d -> d.id==part.id);
			else if(part instanceof OField) removed = cd.bgfldList.removeIf(d -> d.id==part.id);
			if(removed) cd.changed = true;
		}
		((OCardBase)part.parent).changed = true;
	}

	/** Shows a card New Card / New Background / Paste Card just made: at once when a script chose the item (HyperCard J-2.3:
	 *  doMenu "New Card" then the number of this card is the new card's), else as a message after the menu action. */
	static void goToNewCard(int id) throws xTalkException {
		if(Thread.currentThread()==TTalk.talk) TTalk.runExternalStatement("go cd id "+id, PCARD.pc.stack.curCard);
		else TTalk.CallMessage("go cd id "+id, "", null, false, true);
	}

	public static void doMenu(String in_cmd) throws xTalkException {
		String cmd = PCARD.pc.intl.getEngText(in_cmd);
		// Menu messages normally arrive on the interpreter thread. Shape authoring changes Swing widgets.
		boolean shapeAction=java.util.Arrays.asList("shape","edit shapes","new shape","new shape…","shape info…",
			"import svg…","cut shape","copy shape","paste shape","delete shape").contains(cmd.toLowerCase(java.util.Locale.ROOT))
			|| (ShapeTool.active()&&(cmd.equalsIgnoreCase("Bring Closer")||cmd.equalsIgnoreCase("Send Farther")));
		if(shapeAction&&!javax.swing.SwingUtilities.isEventDispatchThread()){
			final xTalkException[] failure=new xTalkException[1];
			try{javax.swing.SwingUtilities.invokeAndWait(()->{try{doMenu(in_cmd);}catch(xTalkException e){failure[0]=e;}});}
			catch(InterruptedException e){Thread.currentThread().interrupt();return;}
			catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException(e.getCause());}
			if(failure[0]!=null)throw failure[0];return;
		}
		if (cmd.equalsIgnoreCase("Undo") || cmd.equalsIgnoreCase("Redo")) {
			AuthoringUndo.perform(cmd.equalsIgnoreCase("Redo"));
			return;
		}
		//メニューからの編集(ユーザー操作): cantModify のスタックでも書き出す(OStack.userEdited)
		if(javax.swing.SwingUtilities.isEventDispatchThread() && editingCommand(cmd)) OStack.userEdit();
		AuthoringUndo.Snapshot before = AuthoringUndo.editsParts(cmd) ? AuthoringUndo.capture() : null;
		try { doMenuImpl(in_cmd); }
		finally { AuthoringUndo.commit(before); }
	}

	/** Menu commands that change the stack (not navigation, viewing or the clipboard of text). */
	private static boolean editingCommand(String cmd){
		String c = cmd.toLowerCase(java.util.Locale.ROOT);
		return AuthoringUndo.editsParts(cmd) || c.endsWith("info…") || c.endsWith("info...") || c.startsWith("new ") ||
			c.startsWith("delete ") || c.startsWith("cut ") || c.startsWith("paste ") || c.contains("script") ||
			c.contains("panel") || c.contains("layer") || c.startsWith("protect") || c.equals("clear") ||
			c.contains("background") || c.contains("icon") || c.contains("resource");
	}

	/** The panels of the current card, then of its background. */
	static java.util.List<OPanel> currentPanels(){
		java.util.List<OPanel> panels = new ArrayList<OPanel>();
		if(PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return panels;
		OCard card = PCARD.pc.stack.curCard;
		panels.addAll(card.panelList);
		if(card.getBg()!=null) panels.addAll(card.getBg().panelList);
		return panels;
	}
	static boolean hasPanels(){
		return !currentPanels().isEmpty();
	}

	/** The button, field or shape selected with its tool, or null. */
	static OObject selectedPart(){
		if(ShapeTool.active() && ShapeEditor.active(ShapeEditor.selected)) return ShapeEditor.selected;
		if(AuthTool.tool instanceof ButtonTool && ButtonGUI.gui.target!=null) return ButtonGUI.gui.tgtOBtn;
		if(AuthTool.tool instanceof FieldTool && FieldGUI.gui.target!=null) return FieldGUI.gui.tgtOFld;
		return null;
	}

	/** Objects > Panel Info…: the panel of the selected part, the only panel here, or the one chosen from those of the card
	 *  and its background. */
	private static void openPanelInfo(){
		Runnable open = () -> {
			OObject part = selectedPart();
			OPanel panel = part==null ? null : part.panel();
			java.util.List<OPanel> panels = currentPanels();
			if(panel==null && panels.size()==1) panel = panels.get(0);
			if(panel==null && panels.size()>1){
				String[] names = new String[panels.size()];
				for(int i=0; i<names.length; i++) names[i] = panels.get(i).getShortName();
				Object chosen = javax.swing.JOptionPane.showInputDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Panel:"),
					PCARD.pc.intl.getDialogText("Panel Info"), javax.swing.JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
				for(int i=0; i<names.length; i++) if(names[i].equals(chosen)) panel = panels.get(i);
				if(panel==null) return;
			}
			if(panel==null){
				new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("There are no panels. Make one with Objects > New Panel."), null,"OK",null,null);
				return;
			}
			AuthDialog.openAuthDialog(PCARD.pc, "panel", panel);
		};
		if(javax.swing.SwingUtilities.isEventDispatchThread()) open.run();
		else try{ javax.swing.SwingUtilities.invokeAndWait(open); }catch(Exception e){ e.printStackTrace(); }
	}

	/**
	 * HyperCard's Card/Bkgnd/Stack Info dialogs are modal: a script's doMenu "Card Info..." goes on once the user closed
	 * the dialog. HyperZebra's is modeless, so the script thread waits here instead - otherwise the next line of Home's
	 * c/b/s (choose browse tool) closed it at once. Not from the menu bar or with "without dialog"; Command-period ends it.
	 */
	private static void waitForInfoDialog(){
		if(!fromScript || withoutDialog || javax.swing.SwingUtilities.isEventDispatchThread()) return;
		AuthDialog dialog = AuthDialog.authDialog;
		OObject shown = AuthDialog.object;
		try{
			while(dialog!=null && AuthDialog.authDialog==dialog && AuthDialog.object==shown && dialog.isVisible() && !TTalk.stop){
				Thread.sleep(50);
			}
		}catch(InterruptedException e){
			Thread.currentThread().interrupt();
		}
	}

	private static void doMenuImpl(String in_cmd) throws xTalkException
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
			
			//go はスクリプトスレッドがすぐに実行する。下のツールの後始末(ペイントの ChangeTool など)は EDT 上で
			//今のカードに対して行うものなので、go をキューに積む前に済ませる(同時に走るとペイントの絵が混ざる)
			String goScript = "go "+cmd.toLowerCase(java.util.Locale.ROOT);
			
			if(PCARD.pc.tool != null){
				//ペイントツールの場合はこれで色々やる
				TBButtonListener.ChangeTool("DummyPaint", null);
			}
			else if(AuthTool.tool != null){
				//カード移動後はTTalk側がChangeTool()でリスナーを付け直す。ここで
				//外さずに追加すると移動のたびに同じリスナーが増えていき、
				//mousePressedが複数回走ってdragThreadやボタン作成が重複する。
				if(AuthTool.tool.getClass() == ButtonTool.class){
					ButtonGUI.gui.tgtOBtn = null;
					ButtonGUI.gui.target = null;
					PCARD.pc.mainPane.removeMouseListener(ButtonGUI.gui);
					PCARD.pc.mainPane.removeMouseMotionListener(ButtonGUI.gui);
					PCARD.pc.mainPane.addMouseListener(ButtonGUI.gui);
					PCARD.pc.mainPane.addMouseMotionListener(ButtonGUI.gui);
				}
				else if(AuthTool.tool.getClass() == FieldTool.class){
					FieldGUI.gui.tgtOFld = null;
					FieldGUI.gui.target = null;
					PCARD.pc.mainPane.removeMouseListener(FieldGUI.gui);
					PCARD.pc.mainPane.removeMouseMotionListener(FieldGUI.gui);
					PCARD.pc.mainPane.addMouseListener(FieldGUI.gui);
					PCARD.pc.mainPane.addMouseMotionListener(FieldGUI.gui);
				}
			}
			TTalk.doScriptforMenu(goScript);
		}
		else if(cmd.equalsIgnoreCase("Home")){
			TTalk.doScriptforMenu("go home");
		}
		else if(cmd.equalsIgnoreCase("Back")){
			PCARD.pc.stack.goBack();
		}
		else if(cmd.equalsIgnoreCase("Recent")){
			new GRecentWindow(PCARD.pc);
		}
		else if(cmd.equalsIgnoreCase("Find…") || cmd.equalsIgnoreCase("Find...")){
			//HyperCard: the message box shows find "" with the insertion point between the quotes (Card Maker IRO: doMenu "検索...")
			javax.swing.SwingUtilities.invokeLater(GMsg::showFind);
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
			//with a paint selection up this is the Paint menu's Select: the selection tightens around the picture in it
			if(PCARD.pc.tool!=null && AuthTool.tool==null && PaintTool.owner!=null && PaintTool.floating()!=null) PaintTool.tightenSelection();
			else TTalk.doScriptforMenu("choose select tool");
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
			TTalk.doScriptforMenu("choose spray can tool");
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
			TTalk.doScriptforMenu("choose reg poly tool");
		}
		else if(cmd.equalsIgnoreCase("FreePolygon")){
			TTalk.doScriptforMenu("choose poly tool");
		}
		else if(cmd.equalsIgnoreCase("Eyedropper")){
			TTalk.doScriptforMenu("choose eyedropper tool");
		}
		else if(cmd.equalsIgnoreCase("Smudge")){
			TTalk.doScriptforMenu("choose smudge tool");
		}
		else if(cmd.equalsIgnoreCase("Stamp")){
			TTalk.doScriptforMenu("choose stamp tool");
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
		else if(cmd.equalsIgnoreCase("Panel Info…")){
			openPanelInfo();
			waitForInfoDialog();
		}
		else if(cmd.equalsIgnoreCase("New Panel")){
			//like New Button: a new panel on the card (the background while editing it), named "New Panel"; from the menu the
			//selected button, field or shape goes into it and Panel Info opens
			TTalk.newPanelFromMenu = !fromScript;
			try{ TTalk.doScriptforMenu("create panel"); }
			finally{ TTalk.newPanelFromMenu = false; }
		}
		else if(cmd.equalsIgnoreCase("New Shape")||cmd.equalsIgnoreCase("New Shape…")){ShapeEditor.create();}
		else if(cmd.equalsIgnoreCase("Shape Info…")){ShapeEditor.open(ShapeEditor.selected);}
		else if(cmd.equalsIgnoreCase("Shape")||cmd.equalsIgnoreCase("Edit Shapes")){TBButtonListener.ChangeTool("Shape",null);}
		else if(cmd.equalsIgnoreCase("Import SVG…")){ShapeEditor.importSvg();}
		//Go > Help: HyperZebra Help, on what HyperZebra adds to HyperCard (the launcher's Help menu has every topic)
		else if(cmd.equalsIgnoreCase("Help")){GHelpWindow.show(GHelpWindow.TOPIC_EXTENSIONS);}
		else if(cmd.equalsIgnoreCase("Export Card Image…")){CardImageExport.exportDialog(PCARD.pc);}
		else if(cmd.equalsIgnoreCase("Delete Shape")){if(ShapeTool.active())ShapeEditor.deleteSelected();}
		else if(cmd.equalsIgnoreCase("Copy Shape")){if(ShapeTool.active())ShapeEditor.copySelected();}
		else if(cmd.equalsIgnoreCase("Cut Shape")){if(ShapeTool.active()){ShapeEditor.copySelected();ShapeEditor.deleteSelected();}}
		else if(cmd.equalsIgnoreCase("Paste Shape")){if(ShapeTool.active())ShapeEditor.paste();}
		else if(cmd.equalsIgnoreCase("Card Info…")){
			AuthDialog.openAuthDialog(PCARD.pc, "card", PCARD.pc.stack.curCard);
			waitForInfoDialog();
		}
		else if(cmd.equalsIgnoreCase("Background Info…")){
			AuthDialog.openAuthDialog(PCARD.pc, "background", PCARD.pc.stack.curCard.bg);
			waitForInfoDialog();
		}
		else if(cmd.equalsIgnoreCase("Stack Info…")){
			AuthDialog.openAuthDialog(PCARD.pc, "stack", PCARD.pc.stack);
			waitForInfoDialog();
		}
		else if(cmd.equalsIgnoreCase("New Button")){
			TTalk.doScriptforMenu("choose button tool");
			//the user's menu: the new button goes where it hides no other part (a script's doMenu: the centre, as in HyperCard)
			TTalk.newPartApart = !fromScript;
			try{ TTalk.doScriptforMenu("create button"); }
			finally{ TTalk.newPartApart = false; }
		}
		else if(cmd.equalsIgnoreCase("New Field")){
			TTalk.doScriptforMenu("choose field tool");
			TTalk.newPartApart = !fromScript;
			try{ TTalk.doScriptforMenu("create field"); }
			finally{ TTalk.newPartApart = false; }
		}
		else if(cmd.equalsIgnoreCase("Bring Closer")){ //to front
			if(ShapeTool.active()){ShapeEditor.reorder(true);return;}
			if(AuthTool.tool==null)return;
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
			        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)obtn.parent).stepInStack(obtn, true);
						((OCardBase)obtn.parent).changed = true;
	
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
			        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)obtn.parent).stepInStack(obtn, true);
						((OCardBase)obtn.parent).changed = true;
	
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
			if(ShapeTool.active()){ShapeEditor.reorder(false);return;}
			if(AuthTool.tool==null)return;
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
			        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)obtn.parent).stepInStack(obtn, false);
						((OCardBase)obtn.parent).changed = true;
						
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
			        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)obtn.parent).stepInStack(obtn, false);
						((OCardBase)obtn.parent).changed = true;
	
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
					removeCardDataForBgPart(ButtonGUI.gui.tgtOBtn);
					((OCardBase)ButtonGUI.gui.tgtOBtn.parent).partsList.remove(ButtonGUI.gui.tgtOBtn);
					((OCardBase)ButtonGUI.gui.tgtOBtn.parent).btnList.remove(ButtonGUI.gui.tgtOBtn);
					((OCardBase)ButtonGUI.gui.tgtOBtn.parent).changed = true;
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
					removeCardDataForBgPart(FieldGUI.gui.tgtOFld);
					((OCardBase)FieldGUI.gui.tgtOFld.parent).partsList.remove(FieldGUI.gui.tgtOFld);
					((OCardBase)FieldGUI.gui.tgtOFld.parent).fldList.remove(FieldGUI.gui.tgtOFld);
					((OCardBase)FieldGUI.gui.tgtOFld.parent).changed = true;
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
					            		+*/PCARD.pc.stack.rsrc.getFilePathAll(ButtonGUI.gui.tgtOBtn.icon, "ICON"));
					            writer.writeEndElement();
					            writer.writeCharacters("\n\t\t");
					            
					            writer.writeStartElement("iconname");
					            writer.writeCharacters(PCARD.pc.stack.rsrc.getNameAll(ButtonGUI.gui.tgtOBtn.icon, "ICON"));
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
							removeCardDataForBgPart(ButtonGUI.gui.tgtOBtn);
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
		        	int newid = cdbase.newPartId();
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
            			btn.panelId = 0; //a pasted part is in no panel (the panel ids belong to the card it was copied from)
            			//readXMLはクリップボード側の<id>で上書きするので、空きIDへ戻す
            			//(同じカードへ複製すると同じIDの部品が2つできていた)
            			btn.id = newid;
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
            				String path = PCARD.pc.stack.rsrc.getFilePathAll(btn.icon, "ICON");
            				//String path = PCARD.pc.stack.file.getParent();
            				/*if(fname!=null){
            					path += File.separatorChar + fname;
            				}*/
	            			if(!iconpath.equals(path)){
	            				//他のスタックからのコピーの場合アイコンリソースもコピーする
	            				if(PCARD.pc.stack.rsrc.getNameAll(btn.icon, "ICON")!=null){
		            				//アイコンが既に存在しているので別のIDにする
		            				btn.icon = PCARD.pc.stack.rsrc.getNewResourceId("ICON", btn.icon);
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
		    						FileChannel srcChannel = null;
		    						FileChannel destChannel = null;
		    						try {
			    						srcChannel = new FileInputStream(iconpath).getChannel();
			    						destChannel = new FileOutputStream(newFilePath).getChannel();
		    							srcChannel.transferTo(0, srcChannel.size(), destChannel);
		    						} finally {
		    							srcChannel.close();
		    							destChannel.close();
		    						}
		    						//リソースに追加
		    						PCARDFrame.pc.stack.rsrc.addResource(btn.icon, "ICON", iconname, newFileName);
		            			}
	            			}
            			}
            			
            			cdbase.btnList.add(btn);
            			cdbase.partsList.add(btn);
            			cdbase.changed = true;
						
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
							removeCardDataForBgPart(FieldGUI.gui.tgtOFld);
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
		        	int newid = cdbase.newPartId();
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
            			fld.panelId = 0;
            			//readXMLはクリップボード側の<id>で上書きするので、空きIDへ戻す
            			fld.id = newid;
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
            			cdbase.changed = true;
						
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
			ShapeEditor.selected=null;
			GMenu.changeSelected("Edit","Background",PaintTool.editBackground);

			ButtonGUI.gui.tgtOBtn = null;
			ButtonGUI.gui.target = null;
			FieldGUI.gui.tgtOFld = null;
			FieldGUI.gui.target = null;
			
			OCard.reloadCurrentCard();
			
			String titleName = PCARD.pc.stack.getWindowTitle();
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
				TTalk.CallMessage("open stack "+TTalk.quoteLiteral(path), "", null, false, true);
			}*/
			
			JFileChooser chooser = new JFileChooser();
			if(PCARD.pc.stack!=null&&PCARD.pc.stack.file!=null){
				File directory = stackChooserDirectory(PCARD.pc.stack.file);
				if(directory!=null) chooser.setCurrentDirectory(directory);
			}
			chooser.setDialogTitle(PCARD.pc.intl.getDialogText("Open Stack"));
			chooser.setDialogType(JFileChooser.OPEN_DIALOG);
			chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
			//HyperCard 2.x の「Open stack in new window」: 表示中のスタックは閉じずに自分のウインドウに残る(GStackWindow)
			javax.swing.JCheckBox newWindow = new javax.swing.JCheckBox(PCARD.pc.intl.getDialogText("Open stack in new window"), openStackInNewWindow);
			boolean stackOnShow = PCARD.pc.stack!=null && PCARD.pc.stack.curCard!=null;
			newWindow.setEnabled(stackOnShow);
			javax.swing.JPanel accessory = new javax.swing.JPanel(new java.awt.BorderLayout());
			accessory.add(newWindow, java.awt.BorderLayout.SOUTH);
			chooser.setAccessory(accessory);
			int selected = PCARD.showFileChooser(chooser, PCARD.pc, false);
			if(stackOnShow) openStackInNewWindow = newWindow.isSelected();
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
				
				TTalk.CallMessage("open stack "+TTalk.quoteLiteral(path)+(stackOnShow && openStackInNewWindow ? " in a new window" : ""), "", null, false, true);
			}
		}
		else if(cmd.equalsIgnoreCase("New Stack…")){
			File f = newStackDialog(PCARD.pc, true);
			if(f!=null){
				//XMLを開く
				TTalk.CallMessage("open stack "+TTalk.quoteLiteral(f.getPath()), "", null, false, true);
			}
		}
		else if(cmd.equalsIgnoreCase("New Card")){
			if(PCARD.pc.stack.curCard==null) return;
			
			//カードのインスタンスを作成
			OCard cd = new OCard(PCARD.pc.stack);

			cd.id = PCARD.pc.stack.newCardOrBgId();
			cd.bgid = PCARD.pc.stack.curCard.bg.id;
			cd.changed = true;
			
			PCARD.pc.stack.AddNewCard(PCARD.pc.stack.GetNumberof(PCARD.pc.stack.curCard), cd.id);
			//移動先で(openCardの前に)newCardを送る
			TTalk.sendNewCard = true;
			goToNewCard(cd.id);
		}
		else if(cmd.equalsIgnoreCase("New Background")){
			if(PCARD.pc.stack.curCard==null) return;
			
			//バックグラウンドのインスタンスを作成
			OBackground bg = new OBackground(PCARD.pc.stack);
			bg.id = PCARD.pc.stack.newCardOrBgId();
			bg.changed = true;
			PCARD.pc.stack.AddNewBgAfter(bg, PCARD.pc.stack.curCard.bgid); //HyperCard J-2.3: the bg after this card's in the list
			
			//カードのインスタンスを作成
			OCard cd = new OCard(PCARD.pc.stack);
			cd.id = PCARD.pc.stack.newCardOrBgId();
			cd.bgid = bg.id;
			cd.changed = true;
			PCARD.pc.stack.AddNewCard(PCARD.pc.stack.GetNumberof(PCARD.pc.stack.curCard), cd.id);
			
			//移動先で(openBackground/openCardの前に)newBackground, newCardを送る
			TTalk.sendNewBackground = true;
			TTalk.sendNewCard = true;
			goToNewCard(cd.id);
			//HyperCard J-2.3 leaves the new background open for editing: the editBkgnd is true and New Field makes a
			//background field (itch.io Desert of the Real: doMenu new background / doMenu new field / set the name of bg field 1)
			if(Thread.currentThread()==TTalk.talk){ if(!PaintTool.editBackground) doMenuImpl("Background"); }
			else TTalk.runOnScriptThread(() -> { //after the go queued above, even when its handlers stop with an error
				try{ if(!PaintTool.editBackground) doMenuImpl("Background"); }catch(xTalkException e){}
			}, true);
		}
		else if(cmd.equalsIgnoreCase("Delete Card")){
			if(PCARD.pc.stack.curCard==null) return;
			if(1==PCARD.pc.stack.cardIdList.size()){
	    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("At least one card is required."),
	    				null,"OK",null,null);
	    		return;
			}
			//deleteCard/deleteBackgroundは削除の前に同期的に送る必要があるので、
			//スクリプトスレッド以外(メニュー操作)からはスクリプトとして投げ直す
			if(Thread.currentThread()!=TTalk.talk){
				TTalk.CallMessage("doMenu \"Delete Card\"", "", null, false, true);
				return;
			}
			//cantDelete(カード・最後のカードならbg・スタック)を尊重する
			OCard deleting = PCARD.pc.stack.curCard;
			boolean lastCardOfBg = true;
			for(int i=0; i<PCARD.pc.stack.cdCacheList.size(); i++){
				OCard other = PCARD.pc.stack.cdCacheList.get(i);
				if(other!=deleting && other.bgid==deleting.bgid){ lastCardOfBg = false; break; }
			}
			if(deleting.cantDelete || PCARD.pc.stack.cantDelete ||
					(lastCardOfBg && deleting.bg!=null && deleting.bg.cantDelete)){
	    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Can't delete this card."),
	    				null,"OK",null,null);
	    		return;
			}
			TTalk.doScriptforMenu("send \"deleteCard\" to this card");
			if(lastCardOfBg) TTalk.doScriptforMenu("send \"deleteBackground\" to this background");
			if(PCARD.pc.stack.curCard!=deleting) return; //ハンドラがカードを移動した
			int previd = 0;
			for(int i=0; i<PCARD.pc.stack.cardIdList.size(); i++){
				if(PCARD.pc.stack.cardIdList.get(i) == PCARD.pc.stack.curCard.id){
					if(i>0){
						previd = PCARD.pc.stack.cardIdList.get(i-1);
					}else{
						//最初のカードの場合
						previd = PCARD.pc.stack.cardIdList.get(i+1);
						PCARD.pc.stack.firstCard = previd;
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
			//カードのピクチャと追加レイヤーのファイルも捨てる
			PaintLayers.deleteFiles(PCARD.pc.stack, deleting);
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
						OBackground removedBg = PCARD.pc.stack.bgCacheList.remove(j);
						PaintLayers.deleteFiles(PCARD.pc.stack, removedBg);
						//firstBg is the head of the background list (bkgnd 1), not the first card's background
						if(!PCARD.pc.stack.bgCacheList.isEmpty()) PCARD.pc.stack.firstBg = PCARD.pc.stack.bgCacheList.get(0).id;
						break;
					}
				}
			}
			//カード一覧(とbg一覧)が変わったので保存対象にする。以前はここで
			//フラグを立てておらず、他の変更のついでに保存されるのを当てにしていた
			PCARD.pc.stack.changed = true;
			
			if(previd!=0){
				TTalk.CallMessage("go cd id "+previd, "", null, false, true);
			}
		}
		else if(cmd.equalsIgnoreCase("Icon…")){
			new ResEdit(PCARD.pc, "ICON", null);
		}
		else if(cmd.equalsIgnoreCase("Sound…")){
			new ResEdit(PCARD.pc, "SND", null);
		}
		else if(cmd.equalsIgnoreCase("Resource…")){
			new ResEditIndex(PCARD.pc);
		}
		else if(cmd.equalsIgnoreCase("Clear This Menu")){
			LauncherStore.get().clearRecentStacks();
	    	GMenu.BuildRecentMenu((JMenu)PCARD.pc.getJMenuBar().getComponent(0));
		}
		else if(cmd.equalsIgnoreCase("Export HyperCard Stack…")){
			if(PCARD.pc.stack==null) return;
			if(PCARD.pc.tool!=null) PaintTool.saveCdPictures();
			StackTextEncoding encoding = GEncodingDialog.choose(PCARD.pc, HCData.currentEncoding);
			if(encoding==null) return;
			JFileChooser chooser = new JFileChooser();
			OStack exportStack = PCARD.pc.stack;
			File suggestedDirectory = stackChooserDirectory(exportStack.file);
			if(suggestedDirectory!=null) chooser.setCurrentDirectory(suggestedDirectory);
			String suggestedName = exportStack.name==null || exportStack.name.isEmpty()
				? "Untitled" : exportStack.name.replace('/', '_').replace(':', '_');
			suggestedName += ".stack";
			chooser.setSelectedFile(new File(suggestedName));
			chooser.setDialogTitle(PCARD.pc.intl.getDialogText("Export HyperCard Stack"));
			chooser.setDialogType(JFileChooser.SAVE_DIALOG);
			chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			if(PCARD.showFileChooser(chooser, PCARD.pc, true)!=JFileChooser.APPROVE_OPTION) return;
			File output = chooser.getSelectedFile();
			if(output.exists()) {
				new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText(
					"This file name already exists. If you continue, the existing file will be replaced."),
					null, PCARD.pc.intl.getDialogText("Cancel"), "OK", null);
				if(GDialog.clicked.equals(PCARD.pc.intl.getDialogText("Cancel"))) return;
			}
			PCARD.pc.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
			try {
				HCExport.Result result = HCExport.exportStack(exportStack, output, encoding);
				String message = PCARD.pc.intl.getDialogText("HyperCard stack exported successfully.");
				if(result.zipPackage) message += "\r"+PCARD.pc.intl.getDialogText(
					"The destination cannot store Macintosh forks, so a ZIP package was created.");
				if(result.sidecar!=null) message += "\r"+PCARD.pc.intl.getDialogText(
					"The resource fork was saved as an AppleDouble file next to the stack. Keep both files together.")
					+"\r"+result.sidecar.getName();
				if(result.outputFile!=null) message += "\r"+result.outputFile.getPath();
				if(!result.warnings.isEmpty()) {
					StringBuilder details = new StringBuilder(message);
					for(String warning: result.warnings) details.append('\r').append("• ").append(warning);
					message = details.toString();
				}
				new GDialog(PCARD.pc, message, null, "OK", null, null);
			} catch(IOException e) {
				new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Could not export the HyperCard stack.")+
					"\r"+e.getMessage(), null, "OK", null, null);
			} finally {
				PCARD.pc.setCursor(Cursor.getDefaultCursor());
			}
		}
		else if(cmd.equalsIgnoreCase("Preferences…") || cmd.equalsIgnoreCase("Settings…") || cmd.equalsIgnoreCase("Stack Settings…")){
			GPreferencesDialog.showSettings(PCARD.pc);
		}
		else if(cmd.equalsIgnoreCase("Mac Application Settings…") || cmd.equalsIgnoreCase("VM Settings…")){
			GPreferencesDialog.showVM(PCARD.pc);
		}
		else if(cmd.equalsIgnoreCase("Individual Stack Settings…")){
			GMenu.showStackSettings();
		}
		else if(cmd.equalsIgnoreCase("Translation Settings…")){
			File f = PCARD.pc.stack==null ? null : StackTranslation.fileOf(PCARD.pc.stack);
			if(f!=null) GTranslationSettingsDialog.show(PCARD.pc, f);
		}
		else if(cmd.equalsIgnoreCase("Save a Copy…")||cmd.equalsIgnoreCase("Save a Copy...")||cmd.equalsIgnoreCase("Save a Copy")){
			//HyperCardと同じく保存先を尋ねてスタックの複製を書き出す。開いているスタックはそのまま
			if(PCARD.pc.stack==null) return;
			StackSaving.saveACopyFromMenu(PCARD.pc.stack);
		}
		else if(cmd.equalsIgnoreCase("Protect Stack…")||cmd.equalsIgnoreCase("Protect Stack")){
			GProtectStackDialog.show(PCARD.pc.stack);
		}
		else if(cmd.equalsIgnoreCase("Close Stack")){
			//スタックを閉じてランチャーに戻る(HyperZebraは終了しない)
			PCARD.closeCurrentStack();
		}
		else if(cmd.equalsIgnoreCase("Show Launcher")){
			GLauncher.showLauncher(true);
		}
		else if(cmd.equalsIgnoreCase("Quit HyperCard") || cmd.equalsIgnoreCase("Quit")){
			//スクリプトの doMenu "Quit HyperCard"(スタック自身の「終了」ボタンや menuMessage): 実機ではFinderに戻る操作なので、
			//ランチャーがあるときは「スタックを閉じる」と同じ経路でランチャーに戻る(HyperZebraは終了しない)。
			//ユーザーのメニューの「終了」/Cmd-Q(PCARD.quitFromMenu)だけがHyperZebraを終了する。
			if(fromScript && GLauncher.enabled()){
				PCARD.closeCurrentStack();
				return;
			}
			//スタックのcloseメッセージと保存(書けなければ尋ねる)、68Kアプリの終了はQuitSequenceが順に行う
			//終了
			PCARD.exit();
		}
		else{
			for(int i=0; i<GMenu.recentsMenuName.length; i++){
				if(cmd.equalsIgnoreCase(GMenu.recentsMenuName[i])){
					TTalk.CallMessage("open stack "+TTalk.quoteLiteral(GMenu.recentsMenuPath[i]), "", null, false, true);
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

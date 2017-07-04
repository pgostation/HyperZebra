import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;


public class ResEdit {
	static ResEdit nulleditor;
	ResTypeEditor child;
	
	public ResEdit(PCARDFrame pc, String type, OObject object){
		if(pc==null){
			pc = new PCARDFrame();
			pc.stack = new OStack(pc);
		}
		
		//各Typeごとに開いて選択する
		if(type.equals("icon") || type.equals("cicn") || type.equals("picture") || type.equals("cursor")){
			child = new IconTypeEditor(pc, type, object);
		}
		else{
			child = new OtherTypeEditor(pc, type, object);
		}
	}
}

class ResTypeEditor extends JFrame {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	ResTypeEditor editor;
	String type;
	OObject object;
	PCARDFrame pcard;
	JScrollPane scrollpane;
	JPanel contpane;
	Rsrc.rsrcClass[] rsrcAry;
	int selectedId[] = {0};
	int scroll;

	public ResTypeEditor(PCARDFrame pc, String type, OObject object){
		super();
		editor = this;
		this.type = type;
		this.object = object;
		this.pcard = pc;
		
		if(pcard.stack.rsrc == null){
			pcard.stack.rsrc = new Rsrc(pcard.stack);
		}
		
		int w=560;
		int h=480;
		
		//frame
		setTitle(type);
		getContentPane().setLayout(new BorderLayout());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				maybeExit();
			}
		});

		addComponentListener(new ResComponentListener());
		
		//scroll
		{
			scrollpane = new JScrollPane();
			scrollpane.setName("JScrollPane");
			int paneh = h;
			if(object!=null){
				paneh = h-60;
			}
			scrollpane.setBounds(0, 0, w, paneh);
			scrollpane.setPreferredSize(new Dimension(w, paneh));
			scrollpane.getVerticalScrollBar().setValue(0);
			scrollpane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
			scrollpane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
			scrollpane.getVerticalScrollBar().setUnitIncrement(12);
			this.add("North", scrollpane);
			
			contpane = new JPanel();
			//contpane.setBounds(0, 0, w-20, paneh);
			contpane.setPreferredSize(new Dimension(w-20, paneh));
			contpane.setOpaque(true);
			contpane.setBackground(new Color(219,223,230));
			scrollpane.setViewportView(contpane);
		}
		
		//アイコン選択ダイアログのときはok buttonを表示
		if(object!=null){
			JPanel okPanel = new JPanel();
			okPanel.setLayout(new FlowLayout());
			
			JButton jbtn = new JButton("OK");
			jbtn.addActionListener(new ResOkButtonListener());
			okPanel.add(jbtn);
			this.add("South", okPanel);
		}

		if(pc!=null){
			setBounds(pc.getX()+pc.getWidth()/2-w/2,pc.getY()+pc.getHeight()/2-h/2,w,h);
		}
		else{
			setBounds(0,0,w,h);
		}
		
		//menu
		new REMenu(this);
	}
	
	@SuppressWarnings("unchecked")
	void open(PCARDFrame pc, int in_scroll){
		while(contpane.getComponentCount()>0){
			contpane.remove(contpane.getComponent(0));
		}
		
		if(object==null){
			//リソースをソートしてリストに保存
			int number = pc.stack.rsrc.getRsrcCount(type);
			
			rsrcAry = new Rsrc.rsrcClass[number];
			for(int i=0; i<number; i++){
				Rsrc.rsrcClass rsrc = pc.stack.rsrc.getRsrcByIndex(type, i);
				rsrcAry[i] = rsrc;
			}
			Arrays.sort(rsrcAry, new DataComparator());
		}else{
			//リソースをソートしてリストに保存
			int number = pc.stack.rsrc.getRsrcCount(type);
			int numberall = pc.stack.rsrc.getRsrcCountAll(type);
			
			rsrcAry = new Rsrc.rsrcClass[numberall];
			int i=0;
			for(i=0; i<number; i++){
				Rsrc.rsrcClass rsrc = pc.stack.rsrc.getRsrcByIndex(type, i);
				rsrcAry[i] = rsrc;
			}
			for(int j=pc.stack.usingStacks.size()-1; j>=0; j--){
				OStack rsrcstack = pc.stack.usingStacks.get(j);
				Iterator<Rsrc.rsrcClass> it = rsrcstack.rsrc.rsrcIdMap.values().iterator();
				while(it.hasNext()){
					Rsrc.rsrcClass rsrc = it.next();
					if(rsrc.type.equals(type)){
						String path1 = (rsrcstack.file.getParent()+File.separatorChar+rsrc.filename);
						String path2 = pc.stack.rsrc.getFilePathAll(rsrc.id, type);
						if(path1.equals(path2))
						{
							rsrcAry[i] = rsrc;
							i++;
						}
					}
				}
			}
			Arrays.sort(rsrcAry, new DataComparator());
		}
	}
	
	@SuppressWarnings("rawtypes")
	class DataComparator implements Comparator
	{
		@Override
		public int compare(Object o1, Object o2){
			return ((Rsrc.rsrcClass)o1).id - ((Rsrc.rsrcClass)o2).id;
		}
	}
	
	class ResComponentListener implements ComponentListener {
		@Override
		public void componentHidden(ComponentEvent e) {
		}

		@Override
		public void componentMoved(ComponentEvent e) {
		}

		@Override
		public void componentResized(ComponentEvent e) {
			int paneh = ((Component)e.getSource()).getHeight();
			if(object!=null){
				paneh -= 60;
			}
			scrollpane.setBounds(0, 0, ((Component)e.getSource()).getWidth(),
					paneh);
			int n = ((Component)e.getSource()).getWidth()/133;
			if(n==0) n=1;
			int len = 0;
			if(rsrcAry!=null){
				len = rsrcAry.length;
			}
			contpane.setPreferredSize(new Dimension(((Component)e.getSource()).getWidth()-20,
					133*((len+n-1)/n)+20));
			contpane.setBounds(0,0,((Component)e.getSource()).getWidth()-20,
					133*((len+n-1)/n)+20);
		}

		@Override
		public void componentShown(ComponentEvent e) {
		}
	}
	
	class ResOkButtonListener implements ActionListener
	{
		@Override
		public void actionPerformed(ActionEvent e) {
			if(type.equals("icon") && object!=null){
				((OButton)object).setIcon(selectedId[0]);
			}
			editor.dispose();
		}
	}

	void maybeExit() {
		object = null;
		pcard = null;
		scrollpane = null;
		contpane = null;
		rsrcAry = null;
		selectedId = null;
		
		System.gc();
		editor.dispose();
		
		editor = null;
	}
}


class IconTypeEditor extends ResTypeEditor {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	IconButton[] selectedButton;
	
	public IconTypeEditor(PCARDFrame pc, String type, OObject object) {
		super(pc, type, object);

		if(object!=null){
			selectedId[0] = ((OButton)object).icon;
		}
		
		contpane.setLayout(new FlowLayout(FlowLayout.LEFT));
		contpane.addMouseListener(new IconBackListener());
		
		scrollpane.getVerticalScrollBar().setUnitIncrement(133);

		open(pcard, 0);
		
		new DropTarget(this, new IconDropListener());
		
		toFront();
		setVisible(true);
	}
	
	@Override
	void open(PCARDFrame pc, int in_scroll){
		super.open(pc, in_scroll);
		LineBorder border = new LineBorder(Color.GRAY);
		IconButtonListener listener = new IconButtonListener();

		selectedButton = new IconButton[selectedId.length];
		scroll = in_scroll;
		
		int number = rsrcAry.length;
		setTitle(type+"("+number+")");
		
		for(int i=0; i<number; i++){
			Rsrc.rsrcClass rsrc = rsrcAry[i];
			JPanel panel = new JPanel();
			panel.setLayout(new BorderLayout());
			panel.setPreferredSize(new Dimension(128,128));
			panel.setBackground(new Color(219,223,230));

			IconButton iconlabel = new IconButton(this);
			iconlabel.setName(Integer.toString(rsrc.id));
			iconlabel.setPreferredSize(new Dimension(126,112));
			if(object!=null&&!pc.stack.rsrc.rsrcIdMap.containsKey(type+rsrc.id)){
				iconlabel.setEnabled(false);
			}
			iconlabel.setBorder(border);
			for(int j=0; j<selectedId.length; j++){
				if(rsrc.id == selectedId[j]){
					selectedButton[j] = iconlabel;
					iconlabel.setBorder(new LineBorder(new Color(128,128,192), 3));
					if(j==0 && in_scroll == 0){
						scroll = 133*(i/4);
					}
					break;
				}
			}
			iconlabel.setHorizontalAlignment(SwingConstants.CENTER);
			iconlabel.addMouseListener(listener);
			//iconはすべて読み込めないので後で表示する
			panel.add("North", iconlabel);
			
			JLabel label = new JLabel(rsrc.id+" "+rsrc.name);
			label.setPreferredSize(new Dimension(128,16));
			panel.add("South", label);
			
			contpane.add(panel);
		}
		
		contpane.setPreferredSize(new Dimension(contpane.getPreferredSize().width, 133*((number+3)/4)));
		
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				scrollpane.getVerticalScrollBar().setValue(scroll);
				scrollpane.setPreferredSize(new Dimension(scrollpane.getPreferredSize().width, 1024));
				scrollpane.setVisible(false);
				scrollpane.setVisible(true);
			}
		});
	}
	
	
	void updateContent(int id){
		int number = rsrcAry.length;
		setTitle(type+"("+number+")");
		
		for(int i=0; i<contpane.getComponentCount(); i++){
			Rsrc.rsrcClass rsrc = rsrcAry[i];

			Component component = (Component)contpane.getComponent(i);
			if(component!=null){
				if(component.getClass()==JPanel.class){
					for(int j=0; j<((JPanel)component).getComponentCount(); j++){
						Component component2 = (Component)((JPanel)component).getComponent(j);
						if(component2.getClass()==IconButton.class){
							if(component2.getName().equals(Integer.toString(id))){
								((IconButton)component2).setIcon(null);
								((IconButton)component2).setName(Integer.toString(rsrc.id));
								((IconButton)component2).repaint();
							}
						}
						else if(component2.getClass()==JLabel.class){
							((JLabel)component2).setText(rsrc.id+" "+rsrc.name);
						}
					}
				}
			}
		}
	}
	
	
	static Border getSelectedBorder(int r, int g, int b){
		Color color1 = new Color(r,g,b);
		Color color2 = new Color((255+r*3)/4, (255+g*3)/4, (255+b*3)/4);
		Color color3 = new Color((255+r)/2, (255+g)/2, (255+b)/2);
		CompoundBorder border1 = new CompoundBorder(
				new LineBorder(color3), 
				new LineBorder(color2));
		CompoundBorder border2 = new CompoundBorder(
				border1, 
				new LineBorder(color1));
		
		return border2;
	}
	
	
	class IconButton extends JButton
	{
	    /**
		 * 
		 */
		private static final long serialVersionUID = 1L;
		BufferedImage bi;
		IconTypeEditor owner;
		
		IconButton(IconTypeEditor owner){
			this.owner = owner;
		}
		
		@Override
	    protected void paintComponent(Graphics g) {
			g.setColor(Color.WHITE);
			g.fillRect(0,0,128,128);
	    	if(getIcon()==null){
	    		BufferedImage bi = pcard.stack.rsrc.getImage(Integer.valueOf(this.getName()),type);
				int w = 124;
				int h = 110;
				if(bi!=null && (bi.getWidth()<=w && bi.getHeight()<=h))
				{
					//そのままの大きさ
					//String filename = pcard.stack.rsrc.getFileName(Integer.valueOf(this.getName()), "icon");
					//String path = pcard.stack.file.getParent()+File.separatorChar+filename;
					setIcon(new ImageIcon(bi));
	    			bi.flush();
	    			setAlignmentX(CENTER_ALIGNMENT);
	    	    	super.paintComponent(g);
	    	    	return;
		    	}
				if(bi!=null && (bi.getWidth()>w || bi.getHeight()>h)){
					//縮小表示
					float rate = (float)w/bi.getWidth();
					if((float)h/bi.getHeight() < rate){
						rate = (float)h/bi.getHeight();
					}
					int nw = (int)(rate * bi.getWidth());
					int nh = (int)(rate * bi.getHeight());
					
					//3倍の大きさの画像も用意
					if(bi.getWidth()>nw*3){
						this.bi = new BufferedImage(nw*3,nh*3,BufferedImage.TYPE_INT_ARGB);
						Graphics2D g1 = (Graphics2D)this.bi.getGraphics();
						g1.drawImage(bi, 0, 0, nw*3, nh*3, 0, 0, bi.getWidth(), bi.getHeight(), this);
					}else{
						this.bi = bi;
					}
					
					BufferedImage newbi = new BufferedImage(nw,nh,BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = (Graphics2D)newbi.getGraphics();
					g2.drawImage(bi, 0, 0, nw, nh, 0, 0, bi.getWidth(), bi.getHeight(), this);
					bi.flush();
					bi = newbi;

					//後から高画質にする
					if(updateThread==null || !updateThread.isAlive()){
						updateThread = new lateUpdateThread();
						updateThread.setButton(this);
						updateThread.start();
					}else{
						updateThread.setButton(this);
					}
				}
				if(bi==null){
	    			bi = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
	    			Graphics g2 = bi.getGraphics();
	    			g2.setColor(Color.RED);
	    			g2.drawLine(0,0,32,32);
	    			g2.drawLine(32,0,0,32);
	    		}
    			setIcon(new ImageIcon(bi));
    			bi.flush();
    			setAlignmentX(CENTER_ALIGNMENT);
	    	}
	    	super.paintComponent(g);
	    	
			if(updateThread!=null && !updateThread.isAlive() && updateList.size()>0){
				updateThread = new lateUpdateThread();
				updateThread.start();
			}
	    }
	}
	
	lateUpdateThread updateThread;
	ArrayList<IconButton> updateList = new ArrayList<IconButton>();
	
	class lateUpdateThread extends Thread
	{
		void setButton(IconButton button){
			updateList.add(button);
		}
		
		public void run(){
			//高画質にする
			setPriority(MIN_PRIORITY);

			try{
				sleep(100);
			} catch (InterruptedException e) {
		        this.interrupt();
			}
			
			for(int i=0; i<updateList.size(); i++){
				IconButton button = updateList.get(i);

				try{
					sleep(10);
				} catch (InterruptedException e) {
			        this.interrupt();
				}
				
				//画面上に見えているか？
				int top = button.owner.scrollpane.getVerticalScrollBar().getValue();
				int bottom = top + button.owner.scrollpane.getHeight();
				if(button.getParent().getBounds().y + button.getBounds().height > top &&
						button.getParent().getBounds().y < bottom )
				{
					BufferedImage bi = button.bi;
					int w = 124;
					int h = 110;
					float rate = (float)w/bi.getWidth();
					if((float)h/bi.getHeight() < rate){
						rate = (float)h/bi.getHeight();
					}
					int nw = (int)(rate * bi.getWidth());
					int nh = (int)(rate * bi.getHeight());
					//そこからきれいに縮小
					Image img = bi.getScaledInstance(nw, -1, Image.SCALE_AREA_AVERAGING );
					BufferedImage newbi = new BufferedImage(nw,nh,BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = (Graphics2D)newbi.getGraphics();
					g2.drawImage(img, 0, 0, nw, nh, button);
					button.setIcon(new ImageIcon(newbi));
	    			bi.flush();
	    			newbi.flush();
	    			System.gc();
	    			
	    			updateList.remove(i);
	    			i--;
				}
			}
		}
	}
	
	class IconButtonListener implements MouseListener
	{

		@Override
		public void mouseClicked(MouseEvent e) {
        	if (e.getClickCount() >= 2){
        		//ダブルクリック
        		if(pcard.stack.rsrc.rsrcIdMap.containsKey(type+editor.selectedId[0])){
        			//開く
        			new IconEditor(editor, pcard.stack.rsrc, type, editor.selectedId[0]);
        		}else{
        			//このスタックのリソースでない場合はコピーを作るか尋ねる
        			new GDialog(editor, PCARD.pc.intl.getDialogText("This resource is not in this stack. Make a copy?"), null, "Cancel", "OK", null);
        			if(GDialog.clicked.equals("OK")){
        				Rsrc.rsrcClass r = pcard.stack.rsrc.getResourceAll(editor.selectedId[0], type);
        				if(r!=null){
        					String srcFilePath = pcard.stack.rsrc.getFilePathAll(editor.selectedId[0], type);
        					String newFileName = new File(srcFilePath).getName();
    						String newFilePath = editor.pcard.stack.file.getParent()+File.separatorChar+newFileName;
    						FileChannel srcChannel=null;
    						FileChannel destChannel=null;
    						try {
								srcChannel = new FileInputStream(srcFilePath).getChannel();
	    						destChannel = new FileOutputStream(newFilePath).getChannel();
								srcChannel.transferTo(0, srcChannel.size(), destChannel);
    						} catch (Exception e1) {
								e1.printStackTrace();
							} finally {
    							try {
        							srcChannel.close();
									destChannel.close();
								} catch (IOException e1) {
									e1.printStackTrace();
								}
							} 
        					pcard.stack.rsrc.addResource(r);
	            			new IconEditor(editor, pcard.stack.rsrc, type, editor.selectedId[0]);
        				}
        				else{
        					System.out.println("resource not found.");
        				}
        			}
        		}
        	}
		}

		@Override
		public void mouseEntered(MouseEvent e) {
		}

		@Override
		public void mouseExited(MouseEvent e) {
		}

		@Override
		public void mousePressed(MouseEvent e) {
    		IconButton btn = (IconButton)e.getSource();
    		IconTypeEditor editor = (IconTypeEditor)(btn.getRootPane().getParent());
  		  	if((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0){
    			//範囲選択
    			int selectid = Integer.valueOf(btn.getName());
    			int lastselectid = editor.selectedId[0];
    			int allnumber = rsrcAry.length;
    			for(int i=0; i<allnumber; i++){
    				Rsrc.rsrcClass rsrc = rsrcAry[i];
    				if((rsrc.id<=selectid) != (rsrc.id<=lastselectid)){
    					//一つずつ追加
    	    			IconButton[] newSelectedButton = new IconButton[editor.selectedButton.length+1];
    		    		int[] newSelectedId = new int[editor.selectedButton.length+1];
    		    		System.arraycopy(editor.selectedButton, 0, newSelectedButton, 1, editor.selectedButton.length);
    		    		System.arraycopy(editor.selectedId, 0, newSelectedId, 1, editor.selectedButton.length);
    		    		//ボタンを探す
    		    		for(int j=0; j<contpane.getComponentCount(); j++){
    		    			JPanel panel = (JPanel)contpane.getComponent(i);
    		    			IconButton iconbutton = (IconButton)panel.getComponent(0);
    		    			if(Integer.valueOf(iconbutton.getName())==rsrc.id){
    		    				newSelectedButton[0] = iconbutton;
            		    		newSelectedId[0] = rsrc.id;
                				iconbutton.setBorder(getSelectedBorder(128,128,192));
            		    		break;
    		    			}
    		    		}
    		    		editor.selectedButton = newSelectedButton;
    		    		editor.selectedId = newSelectedId;
    				}
    			}
    		}
  		  	else if(((e.getModifiersEx() & InputEvent.META_DOWN_MASK) != 0) || 
  		  			((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0)){
    			//追加選択
    			IconButton[] newSelectedButton = new IconButton[editor.selectedButton.length+1];
	    		int[] newSelectedId = new int[editor.selectedButton.length+1];
	    		System.arraycopy(editor.selectedButton, 0, newSelectedButton, 1, editor.selectedButton.length);
	    		System.arraycopy(editor.selectedId, 0, newSelectedId, 1, editor.selectedButton.length);
	    		newSelectedButton[0] = btn;
	    		newSelectedId[0] = Integer.valueOf(btn.getName());
	    		editor.selectedButton = newSelectedButton;
	    		editor.selectedId = newSelectedId;
	    		((IconButton)(e.getSource())).setBorder(getSelectedBorder(96,96,128));
    		}
    		else{
    			//一つ選択
	    		if(editor.selectedButton!=null){
	    			for(int i=0; i<editor.selectedButton.length; i++){
	    				if(editor.selectedButton[i]!=null){
	    					editor.selectedButton[i].setBorder(new LineBorder(Color.GRAY));
	    				}
	    			}
	    		}
	    		if(editor.selectedButton==null||editor.selectedButton.length != 1){
	    			editor.selectedButton = new IconButton[1];
	    			editor.selectedId = new int[1];
	    		}
	    		editor.selectedButton[0] = btn;
	    		editor.selectedId[0] = Integer.valueOf(btn.getName());
	    		((IconButton)(e.getSource())).setBorder(getSelectedBorder(96,96,128));
    		}
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			IconTypeEditor ieditor = (IconTypeEditor)editor;
    		if(ieditor.selectedButton!=null){
    			ieditor.selectedButton[0].setBorder(getSelectedBorder(128,128,192));
    		}
		}
		
	}
	
	class IconBackListener implements MouseListener
	{

		@Override
		public void mouseClicked(MouseEvent e) {
		}

		@Override
		public void mouseEntered(MouseEvent e) {
		}

		@Override
		public void mouseExited(MouseEvent e) {
		}

		@Override
		public void mousePressed(MouseEvent e) {
			JPanel btn = (JPanel)e.getSource();
    		IconTypeEditor editor = (IconTypeEditor)(btn.getRootPane().getParent());
  		  	if(((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0)||
  	  		  	((e.getModifiersEx() & InputEvent.META_DOWN_MASK) != 0) ||
  	    		((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0)){
    			
    		}
    		else{
	    		if(editor.selectedButton!=null){
	    			for(int i=0; i<editor.selectedButton.length; i++){
	    				if(editor.selectedButton[i]!=null){
	    					editor.selectedButton[i].setBorder(new LineBorder(Color.GRAY));
	    				}
	    			}
	    		}
	    		if(editor.selectedButton==null||editor.selectedButton.length != 1){
	    			editor.selectedButton = new IconButton[1];
	    			editor.selectedId = new int[1];
	    		}
	    		editor.selectedButton[0] = null;
	    		editor.selectedId[0] = 0;
    		}
		}

		@Override
		public void mouseReleased(MouseEvent e) {
		}
	}
	
	class IconDropListener extends DropTargetAdapter {
		@Override
		public void drop(DropTargetDropEvent e) {
			try {
				Transferable transfer = e.getTransferable();
				if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
				{
					e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
					@SuppressWarnings("unchecked")
					List<File> fileList = 
						(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));

					((IconTypeEditor)editor).selectedButton = null;
					editor.selectedId = new int[fileList.size()];
					
					for(int j=0; j<fileList.size(); j++){
						String path = fileList.get(j).toString();
						String ext = "";
						BufferedImage bi = null;
						try{
							ext = ".ppm";
							bi = PictureFile.loadPbm(path);
							if(bi==null){
								if(path.lastIndexOf(".")>=0){
									ext = path.substring(path.lastIndexOf("."), path.length());
								}
								else{
									ext = "";
								}
								bi = javax.imageio.ImageIO.read(new File(path));
							}
							if(bi==null){
								ext = ".pict";
								bi = PictureFile.loadPICT(path);
							}
						} catch (IOException e2) {
							e2.printStackTrace();
						}
						if(bi!=null){
							String name = new File(path).getName();
							Pattern p = Pattern.compile("([0-9]{1,5})([^0-9])");
							Matcher m = p.matcher(name);
							int baseid = 1000;
							if(m.find()){
								baseid = Integer.valueOf(m.group(1));
							}
							if(baseid<-32768 || baseid > 32767){
								baseid = (baseid+1000000)%10000;
							}
							int iconid;
							String newFileName="";
							if(pcard.stack.file!=null){ 
								iconid = pcard.stack.rsrc.getNewResourceId(type, baseid);
								
								//ファイルをコピー
								if(type.equals("icon")){
									newFileName = "ICON_"+iconid+ext;
								}else if(type.equals("cicn")){
									newFileName = "cicn_"+iconid+ext;
								}else if(type.equals("picture")){
									newFileName = "PICT_"+iconid+ext;
								}else if(type.equals("cursor")){
									newFileName = "CURS_"+iconid+ext;
								}else {
									System.out.println("unknown resource type");
								}
								String newFilePath = pcard.stack.file.getParent()+File.separatorChar+newFileName;
								FileChannel srcChannel = null;
								FileChannel destChannel = null;
								try {
									srcChannel = new FileInputStream(path).getChannel();
									destChannel = new FileOutputStream(newFilePath).getChannel();
									srcChannel.transferTo(0, srcChannel.size(), destChannel);
								} finally {
									srcChannel.close();
									destChannel.close();
								}
							}
							else{
								iconid = pcard.stack.rsrc.getNewResourceId(type,1);
								newFileName = path;
							}
							//リソースに追加
							pcard.stack.rsrc.addResource(iconid, type, name, newFileName);
						
							editor.selectedId[j] = iconid;
						}
						else {
							String str = PCARD.pc.intl.getDialogText("Can't open the file '%1'.");
							str = str.replace("%1",path);
							new GDialog(editor, str,null,"OK",null,null);
				    	}
					}
					//開き直す
					editor.open(pcard, 0);
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}
}


class OtherTypeEditor extends ResTypeEditor {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	//IconButton[] selectedButton;
	
	public OtherTypeEditor(PCARDFrame pc, String type, OObject object) {
		super(pc, type, object);
		
		//contpane.setLayout(new FlowLayout(FlowLayout.LEFT));
		//contpane.addMouseListener(new IconBackListener());
		
		scrollpane.getVerticalScrollBar().setUnitIncrement(133);

		open(pcard, 0);
		
		//new DropTarget(this, new IconDropListener());
		
		toFront();
		setVisible(true);
	}
	
	@Override
	void open(PCARDFrame pc, int in_scroll){
		super.open(pc, in_scroll);

		//selectedButton = new IconButton[selectedId.length];
		scroll = in_scroll;
		
		int number = rsrcAry.length;
		setTitle(type+"("+number+")");

	    //テーブルを用意
		String[][] tabledata = new String[number][3];
			
		for(int i=0; i<number; i++){
			Rsrc.rsrcClass rsrc = rsrcAry[i];
			tabledata[i][0] = Integer.toString(rsrc.id);
			tabledata[i][1] = rsrc.name;
			tabledata[i][2] = Long.toString(new File(pc.stack.file.getParent()+File.separatorChar+rsrc.filename).length());
		}

		String[] columnNames = {"ID", "Name", "Size"};

		JTable table = new JTable(tabledata, columnNames);
	    table.setEnabled(false);
		table.setDefaultRenderer(Object.class, new MultiLineCellRenderer());
		
		
		
		//contpane.setPreferredSize(new Dimension(contpane.getPreferredSize().width, 133*((number+3)/4)));
		scrollpane.add(table);
		scrollpane.setViewportView(table);
		
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				scrollpane.getVerticalScrollBar().setValue(scroll);
				scrollpane.setPreferredSize(new Dimension(scrollpane.getPreferredSize().width, 1024));
				scrollpane.setVisible(false);
				scrollpane.setVisible(true);
			}
		});
	}
}


class REMenu {
	/**
	 * 
	 */
	
	public REMenu(JFrame owner){
		ActionListener listener=null;
		
		listener = new REMenuListener(owner);
		
    	// メニューバーの設定
		JMenuBar mb=new JMenuBar();
		owner.setJMenuBar(mb);
		
		JMenu m;
		JMenuItem mi;
		int s=InputEvent.CTRL_DOWN_MASK;
		if(System.getProperty("os.name").toLowerCase().startsWith("mac os x")){
			s = InputEvent.META_DOWN_MASK;
		}

	    // Fileメニュー
	    m=new JMenu(PCARDFrame.pc.intl.getText("File"));
	    mb.add(m);
	    
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("New Item")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Open")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("View File")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, s));mi.addActionListener(listener);
	    String os=System.getProperty("os.name");
	    String ver=System.getProperty("os.version");
        if(os!=null && os.startsWith("Mac OS X") && !ver.startsWith("10.5")){
        }
        else{
	    	mi.setEnabled(false);
	    }
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Close")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));mi.addActionListener(listener);

	    // Editメニュー
	    m=new JMenu(PCARDFrame.pc.intl.getText("Edit"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Cut")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Copy")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Paste")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Delete")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARDFrame.pc.intl.getText("Select All")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));mi.addActionListener(listener);
	}
}

//リソース編集のメニュー動作
class REMenuListener implements ActionListener {
	ResTypeEditor editor;
	
	REMenuListener(JFrame owner){
		super();
		this.editor = (ResTypeEditor)owner;
	}
	
	@Override
	public void actionPerformed (ActionEvent e) {
		String in_cmd = e.getActionCommand();
		String cmd = PCARDFrame.pc.intl.getEngText(in_cmd);

		if(cmd.equals("Close")){
			editor.dispose();
			return;
		}
		
		if(editor.pcard.stack==null){
			return;
		}
		
		if(cmd.equals("New Item")){
			String newFileName = "";
			String name = "";
			int rsrcid = 0;
			rsrcid = editor.pcard.stack.rsrc.getNewResourceId(editor.type);
			if(editor.type.equals("icon")){
				newFileName = "ICON_"+rsrcid+".png";
			}else if(editor.type.equals("cicn")){
				newFileName = "cicn_"+rsrcid+".png";
			}else if(editor.type.equals("picture")){
				newFileName = "PICT_"+rsrcid+".png";
			}else if(editor.type.equals("cursor")){
				newFileName = "CURS_"+rsrcid+".png";
			}else {
				System.out.println("unknown resource type.");
				newFileName = "dummy";
				name = "dummy";
			}
			//ファイルを作成
			if(editor.pcard.stack.file==null) return;
			String newFilePath = editor.pcard.stack.file.getParent()+File.separatorChar+newFileName;
			File newFile = new File(newFilePath);
			if(editor.type.equals("icon") || editor.type.equals("cicn")
					 || editor.type.equals("picture") || editor.type.equals("cursor"))
			{
				BufferedImage bi = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
				try {
					ImageIO.write(bi,"png",newFile);
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
			else{
				System.out.println("unknown resource type..");
			}
			
			//リソースに追加
			editor.pcard.stack.rsrc.addResource(rsrcid, editor.type, name, newFileName);
			editor.selectedId = new int[1];
			editor.selectedId[0] = rsrcid;
			//開き直す
			editor.open(editor.pcard, 0 );
		}
		else if(cmd.equals("Open")){
			if(editor.selectedId[0]!=0){
				//if(editor.type.equals("icon")){
					new IconEditor(editor, editor.pcard.stack.rsrc, editor.type, editor.selectedId[0]);
				//}
			}
		}
		else if(cmd.equals("Select All")){
			int number = editor.rsrcAry.length;
    		((IconTypeEditor)editor).selectedId = new int[number];
    		((IconTypeEditor)editor).selectedButton = new IconTypeEditor.IconButton[number];
			for(int i=0; i<number; i++){
				Rsrc.rsrcClass rsrc = editor.rsrcAry[i];
				if(editor.type.equals("icon")){
					//ボタンを探す
		    		for(int j=0; j<editor.contpane.getComponentCount(); j++){
		    			JPanel panel = (JPanel)editor.contpane.getComponent(i);
		    			IconTypeEditor.IconButton iconbutton = (IconTypeEditor.IconButton)panel.getComponent(0);
		    			if(Integer.valueOf(iconbutton.getName())==rsrc.id){
		    	    		((IconTypeEditor)editor).selectedButton[i] = iconbutton;
		    				editor.selectedId[i] = rsrc.id;
	        				iconbutton.setBorder(IconTypeEditor.getSelectedBorder(128,128,192));
	        				break;
		    			}
		    		}
				}
			}
		}
		else if(cmd.equals("View File")){
			if(editor.selectedId[0]!=0){
				String parentPath = editor.pcard.stack.file.getParent();
				String filename = "";
				for(int i=0; i<editor.selectedId.length; i++){
					if(filename.length()>0) filename += ":";
					filename += parentPath+File.separatorChar+
					editor.pcard.stack.rsrc.getFileName1(editor.selectedId[i], editor.type);
				}
				/*boolean isBundle = false;
				String attrStr = "";
				if(new File("/Developer/Tools/GetFileInfo").exists()){
					try {
						//バンドルかどうか
						ProcessBuilder pb = new ProcessBuilder("/Developer/Tools/GetFileInfo", getConvertPath(parentPath));
						Process p = pb.start();
						p.waitFor();
						InputStream is = p.getInputStream();
						BufferedReader br = new BufferedReader(new InputStreamReader(is));
						for (;;) {
							String line = br.readLine();
							if (line == null) break;
							if(line.startsWith("attributes: ")){
								if(line.substring("attributes: ".length()).contains("b")){
									isBundle = true;
									attrStr = line.substring("attributes: ".length());
								}
								break;
							}
						}
					} catch (IOException e1) {
						e1.printStackTrace();
					} catch (InterruptedException e1) {
						e1.printStackTrace();
					}
				}*/
				
				try{
					/*if(isBundle){
						String notBundleAttr;
						int index = attrStr.indexOf("b");
						notBundleAttr = attrStr.substring(0,index) + attrStr.substring(index);
						ProcessBuilder pb1 = new ProcessBuilder("/Developer/Tools/SetFile", "-a", notBundleAttr, "\""+parentPath+"\"");
						pb1.start();
					}*/
					//"-R"はOSX10.6以上 バンドルの中はあらかじめ開いておかないと表示できない
					ProcessBuilder pb = new ProcessBuilder("open", "-R", filename);
					pb.start();
					/*if(isBundle){
						ProcessBuilder pb1 = new ProcessBuilder("/Developer/Tools/SetFile", "-a", attrStr, "\""+parentPath+"\"");
						pb1.start();
					}*/
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
		}
		else if(cmd.equals("Cut") || cmd.equals("Copy")){
			if(editor.selectedId.length>0){
		        //ファイルをコピー
	            for(int i=0; i<editor.selectedId.length; i++)
	        	{
	            	Rsrc.rsrcClass rsrc;
	            	rsrc = editor.pcard.stack.rsrc.getResource1(editor.selectedId[i], editor.type);
	            
					String FilePath = editor.pcard.stack.file.getParent()+File.separatorChar+rsrc.filename;
					String destFilePath = "resource_trash"+File.separatorChar+rsrc.filename;
					try {
		    		    FileChannel srcChannel = null;
		    		    FileChannel destChannel = null;
		    		    try {
			    		    srcChannel = new FileInputStream(FilePath).getChannel();
			    		    destChannel = new FileOutputStream(destFilePath).getChannel();
		    		        srcChannel.transferTo(0, srcChannel.size(), destChannel);
		    		    } finally {
		    		        srcChannel.close();
							destChannel.close();
		    		    }
					} catch (FileNotFoundException e2) {
						e2.printStackTrace();
					} catch (IOException e2) {
						e2.printStackTrace();
					}
	        	}
		        
				//XMLテキストを取得
				XMLOutputFactory factory = XMLOutputFactory.newInstance();
		        StringWriter stringWriter = new StringWriter();
		        try {
		        	XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);
	
		            writer.writeStartElement("resourceclips");
		            writer.writeCharacters("\n\t");
		            
		            for(int i=0; i<editor.selectedId.length; i++)
		        	{
			            Rsrc.rsrcClass rsrc;
			            rsrc = editor.pcard.stack.rsrc.getResource1(editor.selectedId[i], editor.type);
			            rsrc.writeXMLOneRsrc(writer);
		        	}
		            
		            writer.writeEndElement();
		            
			        writer.close();
			        
			        //クリップボードにコピー(XML)
			        {
						Toolkit kit = Toolkit.getDefaultToolkit();
						Clipboard clip = kit.getSystemClipboard();
	
						StringSelection ss = new StringSelection(stringWriter.toString());
						clip.setContents(ss, ss);
			        }
		        } catch (Exception e2) {
					e2.printStackTrace();
				}
			}
		}
		else if(cmd.equals("Paste")){
			Toolkit kit = Toolkit.getDefaultToolkit();
			Clipboard clip = kit.getSystemClipboard();
	
			if(clip.isDataFlavorAvailable(DataFlavor.stringFlavor)){
				String str = null;
				try {
					str = (String)clip.getData(DataFlavor.stringFlavor);
					StringSelection ss = new StringSelection(str);
					clip.setContents(ss, ss);
				} catch (UnsupportedFlavorException e2) {
					e2.printStackTrace();
				} catch (IOException e2) {
					e2.printStackTrace();
				}
				if(str!=null && str.startsWith("<resourceclips>")){
				    XMLInputFactory factory = XMLInputFactory.newInstance();
				    XMLStreamReader reader = null;
				    try {
						reader = factory.createXMLStreamReader(new ByteArrayInputStream(str.getBytes()));
					} catch (Exception e2) {
						e2.printStackTrace();
					}
		
					editor.selectedId = new int[]{0};
					for(int i=0; i<9999; i++){
					    try {
					    	int origId = 0;
					    	String typeStr = "", nameStr = "", fnameStr = "", leftStr = "0", topStr = "0";
					        Rsrc.OptionInfo info = null;
					        boolean isComplete = false;
					        while (reader.hasNext()) {
					    	    try {
						            int eventType = reader.next();
						            if (eventType == XMLStreamConstants.START_ELEMENT) {
						            	String elm = reader.getLocalName();
						            	if(elm.equals("id")){ origId = Integer.valueOf(reader.getElementText()); }
						            	else if(elm.equals("type")){ typeStr = reader.getElementText(); }
						            	else if(elm.equals("name")){ nameStr = reader.getElementText(); }
						            	else if(elm.equals("file")){ fnameStr = reader.getElementText(); }
						            	else if(elm.equals("left")){ leftStr = reader.getElementText(); }
						            	else if(elm.equals("top")){ topStr = reader.getElementText(); }
						            	else if(elm.equals("hotspot")){  }
						            	else if(elm.equals("fontinfo")){ 
						            		info = editor.pcard.stack.rsrc.new FontInfo();
						            		reader = editor.pcard.stack.rsrc.readFontInfoXML(reader, (Rsrc.FontInfo)info); }
						            	else if(elm.equals("resourceclips")){ }
						            	else if(elm.equals("media")){ }
						            	else{
						            		System.out.println("Local Name: " + reader.getLocalName());
						            		System.out.println("Element Text: " + reader.getElementText());
						            	}
						            }
						            if (eventType == XMLStreamConstants.END_ELEMENT) {
						            	String elm = reader.getLocalName();
						            	if(elm.equals("media")){
						            		isComplete = true;
						            		break;
						            	}
						            	else if(elm.equals("resourceclips")){
						            		isComplete = false;
						            		break;
						            	}
						            }
							    } catch (Exception ex) {
								    System.err.println(ex.getMessage());
								    break;
						        }
						    }
					        if(isComplete){
					        	//リソースを追加
					        	int id = editor.pcard.stack.rsrc.getNewResourceId(typeStr, origId);
					        	
		        				//リソースのファイルもコピーする
		        				String srcFilePath = "resource_trash"+File.separatorChar+fnameStr;
		        				String newFileName = "dummy";
		        				if(new File(srcFilePath).exists()){
		            				//ファイルをコピー
		            				String ext = "";
		            				if(fnameStr.lastIndexOf(".")>=0){
										ext = fnameStr.substring(fnameStr.lastIndexOf("."));
									}
		            				String typePrefix;
		            				if(typeStr.equals("icon")){
		            					typePrefix = "ICON_";
		            				}else if(typeStr.equals("cicn")){
		            					typePrefix = "cicn_";
		            				}else if(typeStr.equals("picture")){
		            					typePrefix = "PICT_";
		            				}else if(typeStr.equals("cursor")){
		            					typePrefix = "CURS_";
		            				}else{
		            					typePrefix = typeStr+"_";
		            				}
		    						newFileName = typePrefix+id+ext;
		    						String newFilePath = editor.pcard.stack.file.getParent()+File.separatorChar+newFileName;
		    						FileChannel srcChannel = null;
		    						FileChannel destChannel = null;
		    						try {
			    						srcChannel = new FileInputStream(srcFilePath).getChannel();
			    						destChannel = new FileOutputStream(newFilePath).getChannel();
		    							srcChannel.transferTo(0, srcChannel.size(), destChannel);
		    						} finally {
		    							srcChannel.close();
		    							destChannel.close();
		    						}
		            			}
		            			else{
		            				//ファイルがない
		            			}
		        				
		        				//リソースに追加
					        	editor.pcard.stack.rsrc.addResource(id, typeStr, nameStr, newFileName, leftStr, topStr, info);
					        	
					        	int[] oldSelId = editor.selectedId;
								editor.selectedId = new int[oldSelId.length+1];
								System.arraycopy(oldSelId, 0, editor.selectedId, 0, oldSelId.length);
								editor.selectedId[editor.selectedId.length-1] = id;
			    			}
			    		} catch (Exception e2) {
							e2.printStackTrace();
						}
					} //end of for
					
				}
			} //stringFlavor
			else if(editor.type.equals("icon")){
				Image img = GMenuPaint.getClipboardImage();
				if(img!=null){
					//画像の場合
					
					String newFileName = "dummy";
					String name = "dummy";
					int rsrcid = 0;
					if(editor.type.equals("icon")){
						rsrcid = editor.pcard.stack.rsrc.getNewResourceId("icon");
						newFileName = "ICON_"+rsrcid+".png";
						name = "";
					}
					//ファイルを作成
					String newFilePath = editor.pcard.stack.file.getParent()+File.separatorChar+newFileName;
					File newFile = new File(newFilePath);
					if(editor.type.equals("icon")){
						try {
							ImageIO.write((RenderedImage)img,"png",newFile);
						} catch (IOException e1) {
							e1.printStackTrace();
						}
					}
					
					//リソースに追加
					editor.pcard.stack.rsrc.addResource(rsrcid, "icon", name, newFileName);
					editor.selectedId = new int[1];
					editor.selectedId[0] = rsrcid;
				}
			}
			
			editor.scroll = editor.scrollpane.getVerticalScrollBar().getValue();
			//開き直す
			editor.open(editor.pcard, 0 );
			OCard.reloadCurrentCard();
		}
		
		if(cmd.equals("Delete") || cmd.equals("Cut")){
			//削除
			int number = editor.selectedId.length;
			for(int i=0; i<number; i++){
				editor.pcard.stack.rsrc.deleteResource(editor.type, editor.selectedId[i]);
			}
			//選択解除
			if(editor.type.equals("icon")){
				((IconTypeEditor)editor).selectedButton = null;
			}
			editor.selectedId = new int[]{0};
			editor.scroll = editor.scrollpane.getVerticalScrollBar().getValue();
			//開き直す
			editor.open(editor.pcard, 0 );
			OCard.reloadCurrentCard();
		}
	}
	
}
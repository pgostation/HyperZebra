import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
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
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;
//import javax.swing.ImageIcon;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;


public class IconEditor extends PCARDFrame {
	private static final long serialVersionUID = 1L;
	IconEditor iconeditor;
	Rsrc rsrc;
	int rsrcid; //現在のid。可変。
	int orgrsrcid; //最初に開いたときに記録。不変。
	JTextField namefld;
	JTextField idfld;
	JFrame owner;
	JScrollPane scrollpane;
	JTextField[] textfields = new JTextField[2];
	ICDropListener droplistener = new ICDropListener(this);
	String type;
	
	public IconEditor(JFrame owner, Rsrc rsrc, String type, int id){
		this(owner, rsrc, type, id, 0, 0);
	}
	
	public IconEditor(JFrame owner, Rsrc rsrc, String type, int id, int width, int height){
		this.rsrc = rsrc;
		this.owner = owner;
		this.type = type;
		rsrcid = id;
		orgrsrcid = id;
		iconeditor = this;
		tool = new SelectTool();
		
		bitLeft = 0.0f;
		bitTop = 0.0f;

		setTitle(this.rsrc.getFileName1(this.rsrcid, type));
		
		int w = 640;
		int h = 480;
		//frame
		getContentPane().setLayout(new BorderLayout());

		PaintTool.owner = pc;
		
		//menu
		new IEMenu(this);

		System.gc();
		
		//画像を読み込む
		BufferedImage bi = null;
		BufferedImage srcimg = rsrc.getImage(id, type);
		if(srcimg==null){
			if(width==0 || height == 0){
				width = 32;
				height = 32;
			}
			bi = new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
		}
		else{
			if(width == 0 || height == 0){
				width = srcimg.getWidth();
				height = srcimg.getHeight();
			}
			bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			bi.getGraphics().drawImage(srcimg, 0, 0, null);
		}

		if(width<128 && height<128){
			bit = 8;
		}
		else if(width<320 && height<320){
			bit = 4;
		}
		else if(width<640 && height<640){
			bit = 1;
		}
		else {
			bit = 1;
		}

		w = Math.max(w,width*bit+180);
		h = Math.max(h,height*bit+45);
		setBounds(owner.getX()+owner.getWidth()/2-w/2,owner.getY()+owner.getHeight()/2-h/2,w,h);
		
    	{
    		//ペイント用バッファ
    		mainImg = bi;
    		bgImg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_BYTE_GRAY );
			Graphics2D g = (Graphics2D) bgImg.getGraphics();
			BufferedImage txtr = new BufferedImage(4, 4, BufferedImage.TYPE_INT_BGR);
			txtr.getRaster().getDataBuffer().setElem(0,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(1,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(2,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(3,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(4,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(5,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(6,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(7,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(8,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(9,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(10,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(11,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(12,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(13,0xFFDDDD);
			txtr.getRaster().getDataBuffer().setElem(14,0xFFFFFF);
			txtr.getRaster().getDataBuffer().setElem(15,0xFFFFFF);
			Rectangle2D r = new Rectangle2D.Double(0,0,4,4);
			g.setPaint(new TexturePaint(txtr, r));
			g.fillRect(0,0, bgImg.getWidth(), bgImg.getHeight());
			//System.out.println("max:"+Runtime.getRuntime().maxMemory());
			//System.out.println("total:"+Runtime.getRuntime().totalMemory());
			if(Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
					>2*4*bi.getWidth()*bi.getHeight()){
				undoBuf = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB );
				redoBuf = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB );
			}
			else{
	    		new GDialog(this, "Out of Memory Error.",null,"OK",null,null);
			}
    	}
    	
		//leftside
		{
			JPanel leftPanel = new JPanel();
			leftPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
			leftPanel.setBounds(0, 0, 160, h);
			leftPanel.setPreferredSize(new Dimension(160, h));
			this.add("West", leftPanel);

			JPanel toolPanel = new JPanel();
			toolPanel.setLayout(null/*new GridLayout(14,1)*/);
			toolPanel.setPreferredSize(new Dimension(78,190));
			leftPanel.add(toolPanel);
			
			TBButton jbtn;
	        ButtonGroup grp = new ButtonGroup();
			ActionListener listener = new IEActionListener(this);
	        TBButtonListener listener2 = new TBButtonListener();
	        
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Select"),0,0);
	        grp.add(jbtn);
	        jbtn.setSelected(true);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Lasso"),0,1);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("MagicWand"),0,2);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Pencil"),1,0);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Brush"),1,1);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Eraser"),1,2);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Line"),2,0);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Rect"),2,1);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Oval"),2,2);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("PaintBucket"),3,0);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);
			
			jbtn = new TBButton(PCARDFrame.pc.intl.getToolText("Type"),3,1);
	        grp.add(jbtn);
			jbtn.setFocusable(false);
			jbtn.addActionListener(listener);
			jbtn.addMouseListener(listener2);
			toolPanel.add(jbtn);

			this.fore = new CPButton(Color.BLACK,4,0,false);
			this.fore.setFocusable(false);
			toolPanel.add(this.fore);
	        this.back = new CPButton(Color.WHITE,4,1,true);
	        this.back.setFocusable(false);
	        toolPanel.add(this.back);
	        this.pat = new PatButton(11,5,0);
	        this.pat.setFocusable(false);
	        toolPanel.add(this.pat);
	        this.grad = new GradButton(Color.BLACK, Color.WHITE,5,1);
	        this.grad.setFocusable(false);
	        toolPanel.add(this.grad);
			
	        this.trans = new TransButton(PCARDFrame.pc.intl.getToolText("Transparency"),5,2);
	        this.trans.setFocusable(false);
	        //trans.addActionListener(listener);
	        this.trans.addMouseListener(listener2);
			toolPanel.add(this.trans);

			leftPanel.add(new MyLabelPanel("name", "Name:", rsrc.getName1(id, type), 160, 120));
			leftPanel.add(new MyLabelPanel("id", "ID:", ""+id, 160, 80));
			
			/*JPanel savePanel = new JPanel();
			leftPanel.add(savePanel);
			jbtn = new JButton(PCARD.pc.intl.getToolText("Save"));
			jbtn.addActionListener(listener);
			savePanel.add(jbtn);*/
		}

		toFront();
		setVisible(true);
		
		//scroll area
		scrollpane = new JScrollPane();
		{
			JPanel panel = new JPanel();
			panel.setLayout(null);
			this.add(panel);
			
			scrollpane.setName("JScrollPane");
			int sw = w-160;
			int sh = h-getInsets().top;
			if(sw > bi.getWidth()*bit+20) sw = bi.getWidth()*bit+20;
			if(sh > bi.getHeight()*bit+20) sh = bi.getHeight()*bit+20;

			scrollpane.setBounds(((w-160)-sw)/2, ((h-getInsets().top)-sh)/2, sw, sh);
			//scrollpane.setPreferredSize(new Dimension(sw, sh));
			scrollpane.getVerticalScrollBar().setValue(0);
			scrollpane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
			scrollpane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
			panel.add("Center", scrollpane);
		}
		
		//mainpane
		{
	    	mainPane = new MyPanel(this);
	    	mainPane.setLayout(null);
	    	mainPane.setPreferredSize(new Dimension(bi.getWidth()*bit, bi.getHeight()*bit));
	    	mainPane.setBounds(0, 0, bi.getWidth()*bit, bi.getHeight()*bit);
	    	mainPane.setOpaque(false); //隅の画像が消えないのを抑止。
			scrollpane.setViewportView(mainPane);
			
			IconGUI gui = new IconGUI(this);
			mainPane.addMouseListener(gui);
			mainPane.addMouseMotionListener(gui);
			/*DropTarget drop = */new DropTarget(mainPane, droplistener);
		}

		setVisible(true);
		
		addWindowListener(new IEWindowListener(this));
        addComponentListener(new IEComponentListener(scrollpane));
        addKeyListener(new IEKeyListener());
        
		System.gc();
	}

	boolean quicksave(){
		if(this.rsrc==null) return true;
		
		//this.tool.end();
		
		/*String filepath = this.rsrc.ownerstack.file.getParent()
			+File.separatorChar+"_"+this.rsrc.getFileName(this.rsrcid, "icon");
		try {
			ImageIO.write(this.mainImg, "png", new File(filepath));
		} catch (IOException e1) {
			e1.printStackTrace();
		}*/
		
		return true;
	}
	
	boolean save(){
		if(this.rsrc==null) return true;
		
		this.tool.end();

		int id = 0;
		try{
			id = Integer.valueOf(this.idfld.getText());
		}catch(Exception e2){ }
		
		//名前とidを反映
		{
			Rsrc.rsrcClass iconres;

			if(this.orgrsrcid == id){
				iconres = this.rsrc.getResource1(this.orgrsrcid, type);
			}
			else{
				String prefix;
				if(type.equals("icon")){
					prefix = "ICON_";
				}
				else if(type.equals("cicn")){
					prefix = "cicn_";
				}
				else if(type.equals("picture")){
					prefix = "PICT_";
				}
				else if(type.equals("cursor")){
					prefix = "CURS_";
				}
				else{
					prefix = "dummy_";
				}
				iconres = this.rsrc.new rsrcClass(0, type, "", prefix+id+".png", "0", "0", null);
			}
			
			{
				iconres.name = this.namefld.getText();
				if(id!=0 && iconres.id != id){
					if(iconres.id != 0){
						this.rsrc.deleteResource(type, iconres.id);
					}
					iconres.id = id;
					this.rsrc.addResource(iconres);
				}
			}
		}
		
		String fileName = this.rsrc.getFileName1(id, type);
		String ext = "";
		if(fileName!=null && fileName.lastIndexOf(".")>=0){
			ext = fileName.substring(fileName.lastIndexOf("."));
			if(!ext.equals(".png")){
				fileName = fileName.substring(0,fileName.lastIndexOf("."))+".png";
				Rsrc.rsrcClass res = this.rsrc.getResource1(id, type);
				res.filename = fileName;
			}
		}
		String filepath = fileName;
		if(this.rsrc.ownerstack.file!=null){
			filepath = this.rsrc.ownerstack.file.getParent()+File.separatorChar+fileName;
		}
		
		
		if(this.mainImg != null){
			try {
				ImageIO.write(this.mainImg, "png", new File(filepath));
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
		
		if(owner.getClass() == IconTypeEditor.class){
			IconTypeEditor typeeditor = (IconTypeEditor)owner;
			/*int id = 0;
			try{
				id = Integer.valueOf(this.idfld.getText());
			}catch(Exception e2){ }*/
			if(this.orgrsrcid!=id){
				(typeeditor).selectedButton = null;
				typeeditor.selectedId = new int[1];
				typeeditor.selectedId[0] = id;
				int scroll = typeeditor.scrollpane.getVerticalScrollBar().getValue();
				//開き直す
				typeeditor.open(typeeditor.pcard, scroll );
			}
			else{
				typeeditor.updateContent(this.rsrcid);
			}
		}
		
		OCard.reloadCurrentCard();
		
		return true;
	}


	class MyLabelPanel extends JPanel
	{
		private static final long serialVersionUID = 1L;

		MyLabelPanel(String name, String labelStr, String value, int width, int fldWidth)
		{
			super();
			setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
			
			JLabel label = new JLabel(PCARDFrame.pc.intl.getDialogText(labelStr));
			add(label);
			JTextField jfld = new JTextField(value);
			jfld.setMargin(new Insets(0,0,0,0));
			jfld.setPreferredSize(new Dimension(fldWidth, jfld.getPreferredSize().height));
			jfld.setName(name);
			
			//textfieldに不要なフォーカスを取られないようにする
			jfld.setFocusable(false);
			for(int i=0; i<textfields.length; i++){
				if(textfields[i]==null) {
					textfields[i] = jfld;
					break;
				}
			}
			jfld.addMouseListener(new MouseAdapter() {
			    @Override
				public void mouseEntered(MouseEvent e) {
					((JTextField)e.getSource()).setFocusable(true);
			    }
			});
			
			add(jfld);
			if(name.equals("name")){
				IconEditor.this.namefld = jfld;
			}
			if(name.equals("id")){
				IconEditor.this.idfld = jfld;
			}
			
			this.setPreferredSize(new Dimension(width, jfld.getPreferredSize().height));
		}
	}
}


class IEWindowListener implements WindowListener
{
	IconEditor owner;
	
	IEWindowListener(IconEditor owner){
		this.owner = owner;
	}
	
	@Override
	public void windowActivated(WindowEvent arg0) {
		if(PaintTool.owner != owner){
			PaintTool.owner = owner;
			PaintTool.alpha = 100;
		}
	}
	@Override
	public void windowClosed(WindowEvent arg0) {
	}
	@Override
	public void windowClosing(WindowEvent arg0) {
		PaintTool.owner = owner;
		owner.save();

		owner.iconeditor.end();
		owner.iconeditor = null;
		owner.rsrc = null;
		owner.namefld = null;
		owner.idfld = null;
		owner.owner = null;
		owner.textfields = null;
		owner.droplistener = null;
		owner.scrollpane = null;
		
		PaintTool.owner = PCARDFrame.pc;
		System.gc();
	}
	@Override
	public void windowDeactivated(WindowEvent arg0) {
		owner.quicksave();
		PaintTool.owner = PCARDFrame.pc;
		System.gc();
	}
	@Override
	public void windowDeiconified(WindowEvent arg0) {
	}
	@Override
	public void windowIconified(WindowEvent arg0) {
	}
	@Override
	public void windowOpened(WindowEvent arg0) {
	}
}


class IEComponentListener implements ComponentListener {
	JScrollPane scrollpane;
	
	IEComponentListener(JScrollPane scrollpane){
		this.scrollpane = scrollpane;
	}
	@Override
	public void componentMoved(ComponentEvent e) {
	}
	@Override
	public void componentResized(ComponentEvent e) {
		IconEditor owner = (IconEditor)e.getSource();
		Dimension size = owner.getSize();
		int sw = size.width-160;
		int sh = size.height-((JFrame)e.getSource()).getInsets().top;
		int rate = owner.bit;
		if(sw > owner.mainImg.getWidth()*rate+20) sw = owner.mainImg.getWidth()*rate+20;
		if(sh > owner.mainImg.getHeight()*rate+20) sh = owner.mainImg.getHeight()*rate+20;

		scrollpane.setBounds(((size.width-160)-sw)/2, ((size.height-owner.getInsets().top)-sh)/2, sw, sh);
		owner.setVisible(true);
	}
	@Override
	public void componentHidden(ComponentEvent e) {
	}
	@Override
	public void componentShown(ComponentEvent e) {
	}
}


class IconGUI implements MouseListener, MouseMotionListener
{
	IconEditor owner;
	int clickH;
	int clickV;
	boolean right = false;
	
	IconGUI(IconEditor owner){
		this.owner = owner;
	}
    @Override
	public void mouseClicked(MouseEvent e) {
    }
    @Override
	public void mousePressed(MouseEvent e) {
		right = (javax.swing.SwingUtilities.isRightMouseButton(e));
    	int x = e.getX();
    	int y = e.getY();
    	PaintTool.mouseDown(x, y);
    }
    @Override
	public void mouseReleased(MouseEvent e) {
    	int x = e.getX();
    	int y = e.getY();
    	PaintTool.mouseUp(x, y);
    	PaintTool.lastTime = 0;
    }
    @Override
	public void mouseEntered(MouseEvent e) {
    	if(owner.textfields == null) return;
    	
		for(int i=0; i<owner.textfields.length; i++){
			if(owner.textfields[i]!=null) {
				owner.textfields[i].setFocusable(false);
			}
		}
    }
    @Override
	public void mouseExited(MouseEvent e) {
    }
	@Override
	public void mouseDragged(MouseEvent e) {
    	int x = e.getX();
    	int y = e.getY();
    	PaintTool.mouseStillDown(x, y);
		PaintTool.lastTime = System.currentTimeMillis();
	}
	@Override
	public void mouseMoved(MouseEvent e) {
    	int x = e.getX();
    	int y = e.getY();
    	PaintTool.mouseWithin(x, y);
		PaintTool.lastTime = System.currentTimeMillis();
	}
}


class IEKeyListener implements KeyListener
{

	@Override
	public void keyPressed(KeyEvent e) {
		GUI.gui.keyPressed(e);
	}

	@Override
	public void keyReleased(KeyEvent e) {
		GUI.gui.keyReleased(e);
	}

	@Override
	public void keyTyped(KeyEvent e) {
		GUI.gui.keyTyped(e);
	}
}


class IEActionListener implements ActionListener
{
	IconEditor owner;
	
	IEActionListener(IconEditor frame){
		owner = frame;
	}
	
	@Override
	public void actionPerformed (ActionEvent e) {
		String in_cmd = ((JComponent)e.getSource()).getName();
		String cmd = PCARD.pc.intl.getToolText(in_cmd);
		ChangeTool(cmd);
	}
	
	public boolean ChangeTool(String cmd) {
		//前のツールの終了処理
		if(owner.tool!=null){
			owner.tool.end();
		}

		if(PCARD.pc.intl.getToolText("Select").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Select").equalsIgnoreCase(cmd)){
			owner.tool = new SelectTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Lasso").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Lasso").equalsIgnoreCase(cmd)){
			owner.tool = new LassoTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("MagicWand").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("MagicWand").equalsIgnoreCase(cmd)){
			owner.tool = new SmartSelectTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARDFrame.pc.intl.getToolText("Brush").equalsIgnoreCase(cmd) ||
			PCARDFrame.pc.intl.getToolEngText("Brush").equalsIgnoreCase(cmd)){
			owner.tool = new BrushTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("PaintBucket").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("PaintBucket").equalsIgnoreCase(cmd)){
			owner.tool = new PaintBucketTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Pencil").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Pencil").equalsIgnoreCase(cmd)){
			owner.tool = new PencilTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Eraser").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Eraser").equalsIgnoreCase(cmd)){
			owner.tool = new EraserTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Line").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Line").equalsIgnoreCase(cmd)){
			owner.tool = new LineTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Rect").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Rect").equalsIgnoreCase(cmd)){
			owner.tool = new RectTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Oval").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Oval").equalsIgnoreCase(cmd)){
			owner.tool = new OvalTool();
			TBCursor.changeCursor(owner);
		}
		else if(PCARD.pc.intl.getToolText("Type").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("Type").equalsIgnoreCase(cmd)){
			owner.tool = new TypeTool();
			TBCursor.changeCursor(owner);
		}
		/*else if(PCARD.pc.intl.getToolText("OK").equalsIgnoreCase(cmd) ||
				PCARD.pc.intl.getToolEngText("OK").equalsIgnoreCase(cmd)){
			Rsrc.rsrcClass iconres = owner.rsrc.getResource(owner.rsrcid, "icon");
			
			iconres.name = owner.namefld.getText();
			int id = 0;
			try{
				id = Integer.valueOf(owner.idfld.getText());
			}catch(Exception e2){ }
			if(id!=0 && iconres.id != id){
				owner.rsrc.deleteResource("icon", iconres.id);
				iconres.id = id;
			}
		}*/
		else{
			return false;//見つからない
		}
		
		return true;
	}
}
	

class IEMenu {
	private static final long serialVersionUID = 1L;
	static JMenuItem undoMenu = null;
	static JMenuItem redoMenu = null;
	static IconEditor owner;

	public static boolean changeEnabled(String menu, String item, boolean enabled){
		JMenuItem mi = GMenu.searchMenuItem(owner.getJMenuBar(), menu, item);
		if(mi==null) return false;
		
		mi.setEnabled(enabled);
		return true;
	}
	
	IEMenu(IconEditor in_owner){
		owner = in_owner;
		
		ActionListener listener=null;
		
		listener = new IEMenuListener(in_owner);
		
    	// メニューバーの設定
		JMenuBar mb=new JMenuBar();
		owner.setJMenuBar(mb);
		
		JMenu m;
		JMenuItem mi;
		JCheckBoxMenuItem cb;
		int s=InputEvent.CTRL_DOWN_MASK;
		if(System.getProperty("os.name").toLowerCase().startsWith("mac os x")){
			s = InputEvent.META_DOWN_MASK;
		}
		int s_shift = s+InputEvent.SHIFT_MASK;

	    // Fileメニュー
	    m=new JMenu(PCARD.pc.intl.getText("File"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Close")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, s));mi.addActionListener(listener);

	    // Editメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Edit"));
	    mb.add(m);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Undo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));mi.addActionListener(listener);
	    undoMenu = mi;
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Redo Paint")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s_shift));mi.addActionListener(listener);
	    redoMenu = mi;
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Cut Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Copy Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Paste Picture")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Clear Selection")));mi.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Image Size…")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));mi.addActionListener(listener);
	    if(owner.type.equals("cursor")){
	    	m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Hot Spot…")));mi.addActionListener(listener);
	    }

	    // Paintメニュー
	    m=new JMenu(PCARD.pc.intl.getText("Paint"));
	    mb.add(m);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, s));mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Select All")));mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, s));mi.addActionListener(listener);
	    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("FatBits")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, s));cb.addActionListener(listener);
	    {
			JMenu subm=new JMenu(PCARD.pc.intl.getText("Grid"));
			subm.add(mi = new JMenuItem(PCARD.pc.intl.getText("Grid Size 1")));mi.addActionListener(listener);
			subm.add(mi = new JMenuItem(PCARD.pc.intl.getText("Grid Size 16")));mi.addActionListener(listener);
			subm.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Use Grid")));cb.addActionListener(listener);cb.setSelected(IconEditor.useGrid);
			m.add(subm);
	    }
	    m.add(cb = new JCheckBoxMenuItem(PCARD.pc.intl.getText("Antialias")));cb.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, s));cb.addActionListener(listener);
	    m.addSeparator();
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Reverse Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Expand Selection")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Add to Protect Area")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    //m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Discard Protect Area")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
	    m.add(mi = new JMenuItem(PCARD.pc.intl.getText("Color Convert…")));/*mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, s));*/mi.addActionListener(listener);
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

//メニュー動作
class IEMenuListener implements ActionListener {
	IconEditor editor;
	
	IEMenuListener(IconEditor editor){
		this.editor = editor;
	}
	
	public void actionPerformed (ActionEvent e) {
		String in_cmd = e.getActionCommand();
		String cmd = PCARD.pc.intl.getEngText(in_cmd);
		
		if(cmd.equalsIgnoreCase("Image Size…")){
			editor.save();
			new IconSizeDialog(editor);
		}
		else if(cmd.equalsIgnoreCase("Hot Spot…")){
			new HotSpotDialog(editor);
		}
		else if(cmd.equalsIgnoreCase("Close")){
			editor.save();
			editor.dispose();
		}
		else{
			GMenuPaint.doMenu(cmd);
		}
	}
}


class IconSizeDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	JTextField widthField;
	JTextField heightField;
	IconEditor editor;
	JButton defaultButton;
	
	IconSizeDialog(IconEditor owner) {
		super(owner, true);
		editor = owner;
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new GridLayout(2,1));
		topPanel.setPreferredSize(new Dimension(200,80));
		getContentPane().add("Center",topPanel);
		
		{
			JPanel panel = new JPanel();
			panel.setLayout(new FlowLayout());
			//panel.setPreferredSize(new Dimension(320,32));
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Width:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			
			JTextField area1 = new JTextField(""+owner.mainImg.getWidth());
			area1.setPreferredSize(new Dimension(64, area1.getPreferredSize().height));
			panel.add(area1);
			widthField = area1;
			topPanel.add(panel);
		}
		{
			JPanel panel = new JPanel();
			//panel.setPreferredSize(new Dimension(320,32));
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Height:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			JTextField area2 = new JTextField(""+owner.mainImg.getHeight());
			area2.setPreferredSize(new Dimension(64, area2.getPreferredSize().height));
			panel.add(area2);
			heightField = area2;
			topPanel.add(panel);
		}
		
		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);

		{
			JButton btn1 = new JButton("Cancel");
			btn1.addActionListener(this);
			btmPanel.add(btn1);
		}
		
		{
			JButton btn2 = new JButton("OK");
			btn2.addActionListener(this);
			btmPanel.add(btn2);
			getRootPane().setDefaultButton(btn2);
			defaultButton = btn2;
		}

		setBounds(owner.getX()+owner.getWidth()/2-120,owner.getY()+owner.getHeight()/2-120,240,160);
		setUndecorated(true);//タイトルバー非表示
		
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				if(defaultButton!=null) defaultButton.requestFocus();
			}
		});
		
		setVisible(true);
		
	}
	
	public void actionPerformed(ActionEvent e) {
		if(e.getActionCommand().equals("OK")){
			int width = 0;
			int height = 0;
			try{
				width = Integer.valueOf(widthField.getText());
				height = Integer.valueOf(heightField.getText());
			}catch(Exception e2){
				
			}
			if(width<=0 || height <=0 || width*height >= 5000*5000){
	    		new GDialog(editor, PCARD.pc.intl.getDialogText("Illegal size.")
	    				,null,"OK",null,null);
			}
			else
			{
				new IconEditor(editor.owner, editor.rsrc, editor.type, editor.rsrcid, width, height);
				editor.dispose();
			}
		}
		this.dispose();
	}
}


class HotSpotDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	JTextField xField;
	JTextField yField;
	IconEditor editor;
	JButton defaultButton;
	
	HotSpotDialog(IconEditor owner) {
		super(owner, true);
		editor = owner;
		getContentPane().setLayout(new BorderLayout());

		//パネルを追加する
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new GridLayout(2,1));
		topPanel.setPreferredSize(new Dimension(200,80));
		getContentPane().add("Center",topPanel);
		
		{
			JPanel panel = new JPanel();
			panel.setLayout(new FlowLayout());
			//panel.setPreferredSize(new Dimension(320,32));
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("X:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			
			Point hotSpot = owner.rsrc.getHotSpotAll(owner.rsrcid);
			JTextField area1 = new JTextField(""+hotSpot.x);
			area1.setPreferredSize(new Dimension(64, area1.getPreferredSize().height));
			panel.add(area1);
			xField = area1;
			topPanel.add(panel);
		}
		{
			JPanel panel = new JPanel();
			//panel.setPreferredSize(new Dimension(320,32));
			
			JLabel label = new JLabel(PCARD.pc.intl.getDialogText("Y:"));
			label.setPreferredSize(new Dimension(64, label.getPreferredSize().height));
			panel.add(label);
			Point hotSpot = owner.rsrc.getHotSpotAll(owner.rsrcid);
			JTextField area2 = new JTextField(""+hotSpot.y);
			area2.setPreferredSize(new Dimension(64, area2.getPreferredSize().height));
			panel.add(area2);
			yField = area2;
			topPanel.add(panel);
		}
		
		//パネルを追加する
		JPanel btmPanel = new JPanel();
		getContentPane().add("South",btmPanel);

		{
			JButton btn1 = new JButton("Cancel");
			btn1.addActionListener(this);
			btmPanel.add(btn1);
		}
		
		{
			JButton btn2 = new JButton("OK");
			btn2.addActionListener(this);
			btmPanel.add(btn2);
			getRootPane().setDefaultButton(btn2);
			defaultButton = btn2;
		}

		setBounds(owner.getX()+owner.getWidth()/2-120,owner.getY()+owner.getHeight()/2-120,240,160);
		setUndecorated(true);//タイトルバー非表示
		
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				if(defaultButton!=null) defaultButton.requestFocus();
			}
		});
		
		setVisible(true);
		
	}
	
	public void actionPerformed(ActionEvent e) {
		if(e.getActionCommand().equals("OK")){
			int x = 0;
			int y = 0;
			try{
				x = Integer.valueOf(xField.getText());
				y = Integer.valueOf(yField.getText());
			}catch(Exception e2){
				
			}
			if(x<=0 || y <=0 || x>=32 || y>=32){
	    		new GDialog(editor, PCARD.pc.intl.getDialogText("Illegal point.")
	    				,null,"OK",null,null);
			}
			else
			{
				Rsrc.rsrcClass r = editor.rsrc.getResourceAll(editor.rsrcid, editor.type);
				r.hotsporleft = x;
				r.hotsportop = y;
			}
		}
		this.dispose();
	}
}


class ICDropListener extends DropTargetAdapter {
	IconEditor owner;
	
	ICDropListener(IconEditor owner){
		this.owner = owner;
	}
	
	public void drop(DropTargetDropEvent e) {
		try {
			Transferable transfer = e.getTransferable();
			if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
			{
				e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
				@SuppressWarnings("unchecked")
				List<File> fileList = 
					(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));
				String path = fileList.get(0).toString();
				BufferedImage bi = null;
				try{
					bi = PictureFile.loadPbm(path);
					if(bi==null){
						bi = javax.imageio.ImageIO.read(new File(path));
					}
					if(bi==null){
						bi = PictureFile.loadPICT(path);
					}
				} catch (IOException e2) {
					e2.printStackTrace();
				}
				if(bi!=null){
					if(owner.tool!=null){
						PaintTool.owner = owner;
						owner.tool.end();
					}
					owner.tool = new SelectTool();
					TBCursor.changeCursor(owner);

					owner.redoBuf = bi;
					((SelectTool)owner.tool).move = true;
					((SelectTool)owner.tool).srcRect = new Rectangle(0,0,bi.getWidth(), bi.getHeight());
					((SelectTool)owner.tool).moveRect = new Rectangle(0,0,bi.getWidth(), bi.getHeight());
					owner.mainPane.repaint();
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
}

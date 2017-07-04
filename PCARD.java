import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

class PCARDFrame extends JFrame {
	private static final long serialVersionUID = 1L;

	static PCARD pc=null;
	static boolean useGrid = true;
	static int gridSize = 1;
	MyPanel mainPane;
	OStack stack;
	static OStack home;
	int bit = 1;
	float bitLeft;
	float bitTop;
	toolInterface tool;
	CPButton fore;
	CPButton back;
	GradButton grad;
	PatButton pat;
	TransButton trans;
	boolean fill;
	AffineTransform selectaf;
	int blendMode;
	int blendLevel = 100;
	
	//ペイント
	BufferedImage mainImg;
	BufferedImage bgImg;
	BufferedImage undoBuf;
	BufferedImage redoBuf;
	
	public void end(){
		if(mainImg!=null) mainImg.flush();
		mainImg = null;
		if(bgImg!=null) bgImg.flush();
		bgImg = null;
		if(undoBuf!=null) undoBuf.flush();
		undoBuf = null;
		if(redoBuf!=null) redoBuf.flush();
		redoBuf = null;
		pat = null;
		fore = null;
		back = null;
		grad = null;
		stack = null;
		mainPane = null;
		tool = null;
	}
	
    public BufferedImage getSurface(){
    	if(PaintTool.editBackground) return bgImg;
    	return mainImg;
    }
	
    public void setSurface(BufferedImage bi){
    	if(PaintTool.editBackground) bgImg = bi;
    	mainImg = bi;
    }

    public void setNewBounds(){
    	Rectangle r = pc.getBounds();
    	if(pc.toolbar==null || pc.toolbar.tb==null) return;
    	pc.setBounds(r.x, r.y, pc.stack.width+pc.toolbar.getTWidth(), pc.stack.height+pc.toolbar.getTHeight()+pc.getInsets().top+pc.getJMenuBar().getHeight());
    	if(pc.stack!=null && pc.toolbar!=null && pc.stack.scroll!=null){
    		pc.mainPane.setBounds(pc.toolbar.getTWidth()-pc.stack.scroll.x, pc.toolbar.getTHeight()-pc.stack.scroll.y, pc.stack.width, pc.stack.height);
    	}
    }
}



public class PCARD extends PCARDFrame /*implements MRJOpenDocumentHandler*/  {
	private static final long serialVersionUID = 1L;

	static String AppName = "HyperZebra";
	
	static String version="3.0";
	static String longVersion="3.0a5";
	GMenu menu;
	GMenu paintMenu;
	GMsg msg;
	International intl;
	String lang;
	static boolean useDoubleBuffer = true;//バッファをオフにしてメモリ節約できる？
	Pidle pidle;
	PaintIdle paidle;
	DropTarget drop;
	MyDropTargetListener droplistener;
	GToolBar toolbar;
	
	
    //プロパティ
    static boolean lockedScreen=false;
    static int visual=0;
    static int toVisual=0; /* to black1 white2 grey3 inverse4 */
    static int visSpd=3; /* very fast1 fast2 normal3 slow4 veryslow5 */
    static int editMode = 0; //0:ブラウジング  1:アウトライン強調
    static String scriptFont = "";
    static int scriptFontSize = 12;
    static int userLevel = 5;
    String textFont = "";
    int textSize = 12;
    int textStyle = 0;
    //int textAlign = 0;
    int foundIndex = 0;
    String foundText;
    String foundObject;
    
    public static void main(final String[] args) {
    	pc = new PCARD();

		if(System.getProperty("user.language").equals("ja")){
			pc.lang = "Japanese";
		}
		else{
			pc.lang = "English";
		}

        // ウィンドウアイコンの設定
		try {
			Image icon = ImageIO.read(new File("."+File.separatorChar+"resource"+File.separatorChar+"icon.png"));
	        pc.setIconImage(icon);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
        
    	new OWindow(pc, true);
    	pc.rootPane.setDoubleBuffered(useDoubleBuffer);
    	pc.getRootPane().setOpaque(false);
    	pc.setBackground(Color.WHITE);
    	
    	pc.setLayout(new BorderLayout());
    	pc.mainPane = new MyPanel(pc);
    	pc.mainPane.setDoubleBuffered(useDoubleBuffer);
    	//pc.setContentPane(pc.mainPane/*, BorderLayout.CENTER*/);//こうするとツールバーがおかしくなる
    	pc.add(pc.mainPane, BorderLayout.CENTER);
    	
		Cursor cr = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
		pc.setCursor(cr);

		try {
			pc.intl = new International(pc.lang);
		} catch (Exception e) {
			
		}

    	pc.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});
    	
    	OStack ostack = new OStack(pc);
    	pc.stack = ostack;

		pc.msg = new GMsg(pc, "");
		new VariableWatcher();
		new MessageWatcher();
		
		pc.menu = new GMenu(pc, 0);
		pc.paintMenu = new GMenu(pc, 1);
		JMenuBar menubar = new JMenuBar();
		pc.setJMenuBar(menubar);
		GMenu.menuUpdate(PCARD.pc.menu.mb);

		TTalk.talk = new TTalk(); //TTalkが異常終了したときのためにGUI.javaにスレッド再起動処理あり
		TTalk.talk.start();
		
		String homepath = "./home";
    	if(new File(homepath).exists()){
    		//ostack.openStackFileInThread(homepath, true);
    		try {
				TTalk.doScriptforMenu("start using stack "+"\""+homepath+"\"");
			} catch (xTalkException e1) {
				e1.printStackTrace();
			}
    		home = ostack;
    	}
    	
    	String path = "./home";
    	if(args.length>0) {
    		path = args[0];
    	}
    	
    	if(path!=null && new File(path).exists()){
    		ostack.openStackFile(path, false);
    	}
    	else if(home!=null){
    		home.buildStackFile(false);
    	}
    	else{
    		pc.failureOpenFile(path);
    	}

    	HyperZebra.installMacHandler();
    	
    	//idle処理
    	pc.pidle = new Pidle();
    	pc.pidle.start();
    	
    	pc.paidle = new PaintIdle();
		pc.paidle.start();
    }
    
    void successOpenFile()
    {
		PCARD.lockedScreen = true;
		
		if(pc.drop!=null){
			pc.drop.removeDropTargetListener(pc.droplistener);
			pc.droplistener = null;
		}
    	PaintTool.owner = PCARDFrame.pc;
    	PCARDFrame.pc.stack.buildStackFile(false);

    	//最近使ったスタックに追加
    	if(PCARDFrame.pc.stack.path.startsWith("./home")){
    		return;
    	}
    	
    	//まず読み込み
    	File recentFile = new File("resource_trash"+File.separatorChar+"recent.txt");
    	String[] recents = new String[1];
    	if(recentFile.exists()){
    		FileInputStream fis = null;
    		try {
    			fis = new FileInputStream(recentFile);
    			int length = (int)recentFile.length();
    			byte[] b = new byte[length];
    			fis.read(b);
    			String recentStr = new String(b);
    			recentStr = " \n"+recentStr;
    			recents = recentStr.split("\n");
    			fis.close();
    		} catch (FileNotFoundException e) {
    			e.printStackTrace();
    		} catch (IOException e) {
				e.printStackTrace();
			}
    	}
    	
    	//最初の行に追加
    	String path = PCARDFrame.pc.stack.path;
    	if(new File(path).getName().equals("_stack.xml") ||
    			new File(path).getName().equals("toc.xml")){
    		path = new File(path).getParent();
    	}
    	recents[0] = path;

    	//ファイルに書き出し
    	{
    		FileOutputStream fos = null;
    		try {
    			fos = new FileOutputStream(recentFile);
    			for(int i=0; i<recents.length; i++){
    				if(i==0 || !recents[i].equals(path)){
	    				fos.write(recents[i].getBytes());
	    				if(i<recents.length-1) fos.write("\n".getBytes());
    				}
    			}
    			fos.close();
    		} catch (FileNotFoundException e) {
    			e.printStackTrace();
    		} catch (IOException e) {
				e.printStackTrace();
			}
    	}
    	GMenu.BuildRecentMenu((JMenu)PCARD.pc.getJMenuBar().getComponent(0));
	}
    
    void failureOpenFile(String path){
		if(pc.stack.curCard!=null) {
			pc.stack.curCard.removeData();
			if(pc.stack.curCard.bg!=null) pc.stack.curCard.bg.removeData();
		}
		pc.emptyWindow();
		if(!path.equals("./home")){
			new GDialog(pc, PCARDFrame.pc.intl.getDialogText("Could't open the file."),
					null,"OK",null,null);
		}
    }
	
    public void emptyWindow() {
		setTitle(PCARDFrame.pc.intl.getDialogText("Drop file here"));
		
		Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
		if(d!=null){
			setBounds(d.width/2-400/2, d.height/2-320/2-20, 400, 320+20);
		}else{
			setBounds(0, 0-320-20, 400, 320+20);
		}
		setVisible(true);
		//setLocationRelativeTo(null);
		droplistener = new MyDropTargetListener();
		drop = new DropTarget(pc.mainPane, droplistener);
	}

	private class MyDropTargetListener extends DropTargetAdapter {
		/*
		 * @see java.awt.dnd.DropTargetListener#drop(java.awt.dnd.DropTargetDropEvent)
		 */
		@Override
		public void drop(DropTargetDropEvent e) {
			try {
				Transferable transfer = e.getTransferable();
				if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
				{
					if(pc.stack!=null){
						pc.stack.clean();
						pc.stack = new OStack(pc);
					}
					e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
					@SuppressWarnings("unchecked")
					List<File> fileList = 
						(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));
					for(int i=0; i<fileList.size(); i++){
						pc.stack.openStackFile(fileList.get(i).toString(), false);
					}
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}
    
	PCARD() {
        GUI.gui.addMouseListener(this);
        this.addKeyListener(GUI.gui);
        //this.addWindowListener(paintGUI.gui);
        this.addWindowListener(new PCARDWindowListener(this));
    }
}


class PCARDWindowListener implements WindowListener
{
	PCARD owner;
	
	PCARDWindowListener(PCARD owner){
		this.owner = owner;
	}
	
	@Override
	public void windowActivated(WindowEvent arg0) {
		if(owner!=null && owner.toolbar!=null){
			owner.toolbar.activate();
		}
	}
	@Override
	public void windowClosed(WindowEvent arg0) {
	}
	@Override
	public void windowClosing(WindowEvent arg0) {
	}
	@Override
	public void windowDeactivated(WindowEvent arg0) {
		if(owner!=null && owner.toolbar!=null){
			owner.toolbar.deactivate();
		}
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


class Pidle extends Thread {
	@Override
	public void run() {
        this.setName("idle thread");
		while(true){
			if(PCARDFrame.pc.stack!=null && PCARDFrame.pc.stack.curCard!=null && PCARDFrame.pc.tool==null){
				if(TTalk.idle && PCARD.pc.isActive()){
					if(System.currentTimeMillis() - TTalk.lastErrorTime > 10*1000){
						TTalk.CallMessage("idle","",PCARDFrame.pc.stack.curCard, true, false);
					}
				}
				GUI.keyEventCheck();
				//PCARD.pc.stack.pcard.requestFocusInWindow(); //これするとフィールドに文字打てなくなる
			}
			try{
				sleep(100);//1000msecに10回
			} catch (InterruptedException e) {
		          Thread.currentThread().interrupt();
			}
			if (Thread.currentThread().isInterrupted()) {
				break;
			}
		}
	}
}

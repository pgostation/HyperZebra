import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;


class CPButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

    static CPButtonListener listener = new CPButtonListener();
	JPopupMenu popup = new JPopupMenu(); // ポップアップメニューを生成
    Color color;
	
	CPButton(Color in_color, int y, int x, boolean isback){
		super("");
        this.setFocusable(false);
		BufferedImage bi = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
		Graphics g = bi.createGraphics();
		g.setColor(in_color);
		g.fillRect(0,0,16,12);
		this.setIcon(new ImageIcon(bi));
		this.color = in_color;
		//this.setForeground(color);
		//this.setBackground(color);
		//this.setBounds((y*3+x)*24,0/*y*20*/,24,20);
		this.setBounds(x*26,y*28+16,26,26);
        setMargin(new Insets(0,0,0,0));
        this.addActionListener(listener);
        this.addMouseListener(listener);
        
        if(isback)
        {
        	addPopupMenuItem(PCARDFrame.pc.intl.getToolText("Transparency"), new ActionListener(){
				@Override
				public void actionPerformed(ActionEvent e){
					Color c = PaintTool.owner.back.color;
					int alpha = c.getAlpha()==0?0xFF:0x00;
					PaintTool.owner.back.color = new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
					PaintTool.owner.back.makeIcon(PaintTool.owner.back.color);
				}
			});
        }
	}

	// メニュー項目を追加
	private JMenuItem addPopupMenuItem(String name, ActionListener al){
		JMenuItem item = new JMenuItem(name);
		item.addActionListener(al);
		popup.add(item);
		return item;
	}
	
	public void makeIcon(Color col){
		this.color = col;
		BufferedImage bi = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
		Graphics g = bi.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0,0,16,12);
		g.setColor(Color.GRAY);
		g.drawRect(0,0,8,12);
		g.drawRect(0,0,16,6);
		g.setColor(Color.BLACK);
		g.drawRect(0,0,16,12);
		g.setColor(col);
		g.fillRect(0,0,16,12);
		this.setIcon(new ImageIcon(bi));
	}
	
	//paintComponentをオーバーライドすると表示が崩れる
    /*@Override
    protected void paintComponent(Graphics g) {
    }*/
}


class CPButtonListener implements ActionListener, MouseListener {
	@Override
	public void actionPerformed (ActionEvent e) {
		CPButton btn = (CPButton)e.getSource();
		/*if(e.getActionCommand().equals("color1"))*/{
			//Color col = JColor.showDialog(PaintTool.owner, "Color", btn.color );
			/*Color col = GColorDialog.getColor(PaintTool.owner, btn.color, new Point(btn.getX(), btn.getY()), true);

			if(col != null){
				btn.makeIcon(col);
			}*/
			new CPButtonThread(btn, e).start();
		}
	}

	class CPButtonThread extends Thread {
		CPButton btn;
		ActionEvent e;
		
		CPButtonThread(CPButton btn, ActionEvent e){
			super();
			this.btn = btn;
			this.e = e;
		}
		
		public void run(){
			Color col = null;
			if(btn.getClass()==CPButton.class){
				col = GColorDialog.getColor(PaintTool.owner, btn.color, true);
			}
			else if(btn.getClass()==AuthColorButton.class){
				col = GColorDialog.getColor(PCARD.pc, btn.color, true);
			}

			if(col != null){
				btn.makeIcon(col);
			}
		}
	}
	
	@Override
	public void mouseClicked(MouseEvent arg0) {
		if(javax.swing.SwingUtilities.isRightMouseButton(arg0)){
			((CPButton)arg0.getSource()).popup.show(arg0.getComponent(), arg0.getX(), arg0.getY());
		}
	}

	@Override
	public void mouseEntered(MouseEvent arg0) {
	}
	@Override
	public void mouseExited(MouseEvent arg0) {
	}
	@Override
	public void mousePressed(MouseEvent arg0) {
	}
	@Override
	public void mouseReleased(MouseEvent arg0) {
	}
}





class GradButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

    static GradButtonListener listener = new GradButtonListener();
	JPopupMenu popup = new JPopupMenu(); // ポップアップメニューを生成
    Color color1;
    Color color2;
    double angle;
    boolean use = false;
	
    GradButton(Color color1, Color color2, int y, int x){
		super("Gradation");
        this.setFocusable(false);
		//BufferedImage bi = new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
		//Graphics g = bi.createGraphics();
		//g.setColor(color1);
		//g.fillRect(0,0,16,12);
		//this.setIcon(new ImageIcon(bi));
		this.setText(/*PCARDFrame.pc.intl.getToolText("Gradation")+" "+*/(this.use?"":"off"));
		this.color1 = color1;
		this.color2 = color2;
		this.angle = 0;
		//this.setBounds((y*3+x)*24,0/*y*20*/,24,20);
		this.setBounds(x*26,y*28+16,26,26);
        setMargin(new Insets(0,0,0,0));
        this.addActionListener(listener);
        this.addMouseListener(listener);
        
        {
    		int[] angles = {0,10,20,30,40,50,60,70,80,90,100,110,120,130,140,150,160,170,180,190,200,210,220,230,240,250,260,270,280,290,300,310,320,330,340,350};
        	for(int i=0; i<angles.length; i++){
	        	addPopupMenuItem(PCARDFrame.pc.intl.getToolText("Angle")+" "+angles[i], new ActionListener(){
					@Override
					public void actionPerformed(ActionEvent e){
						PCARDFrame.pc.grad.angle = Math.PI/180.0*Integer.valueOf(((JMenuItem)(e.getSource())).getText().split(" ")[1]);
						PCARDFrame.pc.grad.makeIcon();
					}
				});
        	}
        }
	}
	
	// メニュー項目を追加
	private JMenuItem addPopupMenuItem(String name, ActionListener al){
		JMenuItem item = new JMenuItem(name);
		item.addActionListener(al);
		popup.add(item);
		return item;
	}
	
	public void makeIcon(){
		if(this.use){
			this.color1 = PaintTool.owner.fore.color;
			this.color2 = PaintTool.owner.back.color;
			BufferedImage bi = new BufferedImage(16,12,BufferedImage.TYPE_INT_ARGB);
			bi.getGraphics().fillRect(0,0,16,12);
			PaintBucketTool.gradfill(bi,this.color1,this.color2,this.angle);
			this.setIcon(new ImageIcon(bi));
		}else{
			this.setIcon(null);
		}
	}
	
	//paintComponentをオーバーライドすると表示が崩れる
    /*@Override
    protected void paintComponent(Graphics g) {
    }*/
}


class GradButtonListener implements ActionListener, MouseListener {
	@Override
	public void actionPerformed (ActionEvent e) {
		GradButton btn = (GradButton)e.getSource();
		/*if(e.getActionCommand().equals("color1"))*/{
			btn.use = !btn.use;
			btn.setText(/*PCARDFrame.pc.intl.getToolText("Gradation")+" "+*/(btn.use?"":"off"));
			btn.makeIcon();
		}
	}
	
	@Override
	public void mouseClicked(MouseEvent arg0) {
		//String cmd = ((GradButton)arg0.getSource()).getText();
		if(javax.swing.SwingUtilities.isRightMouseButton(arg0)){
			//if(PCARDFrame.pc.intl.getToolText("Gradation").equals(cmd.split(" ")[0])){
				((GradButton)arg0.getSource()).popup.show(arg0.getComponent(), arg0.getX(), arg0.getY());
			//}
		}
	}

	@Override
	public void mouseEntered(MouseEvent arg0) {
	}
	@Override
	public void mouseExited(MouseEvent arg0) {
	}
	@Override
	public void mousePressed(MouseEvent arg0) {
	}
	@Override
	public void mouseReleased(MouseEvent arg0) {
	}
}


class PatButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

    static PatButtonListener listener = new PatButtonListener();
	JPopupMenu popup = new JPopupMenu(); // ポップアップメニューを生成
    int pattern;
	BufferedImage[] patterns = new BufferedImage[40];
	
    PatButton(int in_pattern, int y, int x){
		super(/*"Pattern"*/);
        this.setFocusable(false);
		this.pattern = in_pattern;
		//this.setBounds((y*3+x)*24,0/*y*20*/,24,20);
		this.setBounds(x*26,y*28+16,26,26);
        setMargin(new Insets(0,0,0,0));
        //this.addActionListener(listener);
        this.addMouseListener(listener);

    	for(int i=0; i<patterns.length; i++){
    		if(PCARDFrame.pc.stack.Pattern[i]!=null){
    			try {
					patterns[i] = ImageIO.read(new File(PCARDFrame.pc.stack.file.getParent()+File.separatorChar+PCARDFrame.pc.stack.Pattern[i]));
				} catch (IOException e) {
					e.printStackTrace();
				}
				if(patterns[i] == null && PCARDFrame.pc.stack.Pattern[i].length()>0){
					patterns[i] = PictureFile.loadPbm(PCARDFrame.pc.stack.file.getParent()+File.separatorChar+PCARDFrame.pc.stack.Pattern[i]);
				}
				if(patterns[i]==null && PCARDFrame.pc.stack.Pattern[i].length()>0){
        			patterns[i] = PictureFile.loadPbm("resource"+File.separatorChar+"PAT_"+i+".pbm");
    			}
    			if(patterns[i]==null){
    				patterns[i] = new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
    				Graphics g = patterns[i].getGraphics();
    				g.setColor(Color.BLACK);
    				g.fillRect(0,0,16,16);
    			}
				if(patterns[i].getWidth()<16 || patterns[i].getHeight()<16){
					BufferedImage pat = patterns[i];
					int width = pat.getWidth();
					int height = pat.getHeight();
					if(width<16) width *= 2;
					if(height<16) height *= 2;
					patterns[i] = new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
					patterns[i].getGraphics().drawImage(pat, 0, 0, PCARDFrame.pc);
					patterns[i].getGraphics().drawImage(pat, 0, pat.getHeight(), PCARDFrame.pc);
					patterns[i].getGraphics().drawImage(pat, pat.getWidth(), 0, PCARDFrame.pc);
					patterns[i].getGraphics().drawImage(pat, pat.getWidth(), pat.getHeight(), PCARDFrame.pc);
				}
    		}
    	}
    	
    	if(patterns[pattern]!=null){
    		setIcon(new ImageIcon(patterns[pattern]));
    	}

        {
        	for(int i=0; i<patterns.length; i++){
        		ImageIcon icon = null;
        		if(patterns[i]!=null) {
        			icon = new ImageIcon(patterns[i]);
        		}
        		else{
    				//パターンを./resource/から取ってくる
					String fname = "PAT_"+(i+1)+".png";
		
					File ifile = new File("."+File.separatorChar+"resource"+File.separatorChar+fname);
					try {
						patterns[i] = ImageIO.read(ifile);
	        			icon = new ImageIcon(patterns[i]);
					} catch (IOException e) {
						e.printStackTrace();
					}
        		}
        		JCheckBoxMenuItem item = addPopupMenuItem(PCARDFrame.pc.intl.getToolText("Pattern")+" "+i, icon, new ActionListener(){
					@Override
					public void actionPerformed(ActionEvent e){
						PaintTool.owner.pat.pattern = Integer.valueOf(((JMenuItem)(e.getSource())).getText().split(" ")[1]);
						BufferedImage patbi = PaintTool.owner.pat.patterns[PaintTool.owner.pat.pattern];
						if(patbi!=null){
							PaintTool.owner.pat.setIcon(new ImageIcon(patbi));
						}

			        	for(int i=0; i<PaintTool.owner.pat.popup.getComponentCount(); i++){
			        		Component c = PaintTool.owner.pat.popup.getComponent(i);
				        	if(c.getClass()==JCheckBoxMenuItem.class){
				        		((JCheckBoxMenuItem)c).setSelected(false);
				        	}
			        	}
			        	((JCheckBoxMenuItem)(e.getSource())).setSelected(true);
					}
				});
        		if(i==pattern){
        			item.setSelected(true);
        		}
        	}
        }
	}
	
	// メニュー項目を追加
	private JCheckBoxMenuItem addPopupMenuItem(String name, ImageIcon icon, ActionListener al){
		JCheckBoxMenuItem item = new JCheckBoxMenuItem(name, icon);
		item.addActionListener(al);
		popup.add(item);
		return item;
	}
}



class PatButtonListener implements MouseListener {
	
	@Override
	public void mouseClicked(MouseEvent arg0) {
		//String cmd = ((PatButton)arg0.getSource()).getText();
		//if(PCARDFrame.pc.intl.getToolText("Pattern").equals(cmd.split(" ")[0])){
			((PatButton)arg0.getSource()).popup.show(arg0.getComponent(), arg0.getX(), arg0.getY());
		//}
	}

	@Override
	public void mouseEntered(MouseEvent arg0) {
	}
	@Override
	public void mouseExited(MouseEvent arg0) {
	}
	@Override
	public void mousePressed(MouseEvent arg0) {
	}
	@Override
	public void mouseReleased(MouseEvent arg0) {
	}
}



class TransButton extends JButton {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	JPopupMenu popup = new JPopupMenu(); // ポップアップメニューを生成
	
	TransButton(String in_text, int y, int x){
		super();
        this.setFocusable(false);
		//this.setBounds((y*3+x)*24,0/*y*20*/,24,20);
		this.setBounds(x*26,y*28+16,26,26);
		this.setName(in_text);
        setMargin(new Insets(0,0,0,0));

        if(PCARD.pc.intl.getToolText("Transparency").equals(in_text)){
    		int[] alpha = {0,5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90,95};
        	for(int i=0; i<alpha.length; i++){
	        	addPopupMenuItem(alpha[i]+" %", new ActionListener(){
					public void actionPerformed(ActionEvent e){
						PaintTool.alpha = 100-Integer.valueOf(((JMenuItem)(e.getSource())).getText().split(" ")[0]);
						TBCursor.changeCursor(PCARD.pc);
						
			        	for(int i=0; i<popup.getComponentCount(); i++){
			        		if(popup.getComponent(i) instanceof JCheckBoxMenuItem){
			        			JCheckBoxMenuItem item = (JCheckBoxMenuItem)popup.getComponent(i);
			        			item.setSelected(false);
			        		}
						}
						((JCheckBoxMenuItem)(e.getSource())).setSelected(true);
						PaintTool.owner.trans.setText(100-PaintTool.alpha+"%");
					}
				},
				alpha[i]==PaintTool.alpha);
        	}
        }
        
        setText(100-PaintTool.alpha+"%");
        setFont(new Font("",0,10));
	}
	
	// メニュー項目を追加
	private JMenuItem addPopupMenuItem(String name, ActionListener al, boolean check){
		JCheckBoxMenuItem item = new JCheckBoxMenuItem(name);
		item.addActionListener(al);
		item.setSelected(check);
		//item.setBounds(0,0,getPreferredSize().width, getPreferredSize().height);
		popup.add(item);
		return item;
	}
}

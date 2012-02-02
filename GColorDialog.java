import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Frame;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;


class GColorDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	static JColorChooser chooser = new JColorChooser();
	static Color selectedColor;
	static CaptureThread captureThread;
	
	public static Color getColor(Frame frame, Color color, boolean useCapture){
		captureThread = null;
		new GColorDialog(frame, color, useCapture);
		while(captureThread!=null && captureThread.isAlive()){
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
			}
		}
		return selectedColor;
	}
	
	private GColorDialog(Frame frame, Color color, boolean useCapture){
		super(frame, true);
		this.setLayout(new BorderLayout());
		setResizable(false);
		
		if(color!=null){
			chooser.setColor(color);
		}
		
		selectedColor = chooser.getColor();
		
		getContentPane().add("Center", chooser);

		JPanel panel = new JPanel();
		getContentPane().add("South", panel);
		
		if(useCapture){
			JButton btn2 = new JButton(PCARD.pc.intl.getDialogText("Choose from Screen"));
			btn2.setName("capture");
			btn2.addActionListener(this);
			panel.add(btn2);
		}

		JButton btn1 = new JButton("OK");
		btn1.setName("OK");
		btn1.addActionListener(this);
		panel.add(btn1);
		
		/*if(point!=null){
			setBounds(point.x, point.y, chooser.getPreferredSize().width, chooser.getPreferredSize().height+40);
		}else if(frame!=null){
			setBounds(frame.getX()+frame.getWidth()/2-300/2, frame.getY()+frame.getHeight()/2-200/2, chooser.getPreferredSize().width, chooser.getPreferredSize().height+40);
		}else*/{
	        PointerInfo info = MouseInfo.getPointerInfo();
			setBounds(info.getLocation().x-180, info.getLocation().y-150, chooser.getPreferredSize().width, chooser.getPreferredSize().height+40);
		}

		setVisible(true);
	}
	
	@Override
	public void actionPerformed(ActionEvent e) {
		JButton btn = (JButton)e.getSource();
		if(btn.getName().equals("OK")){
			selectedColor = chooser.getColor();
			this.dispose();
		}
		else if(btn.getName().equals("capture")){
			captureThread = new CaptureThread(this);
			captureThread.start();
			this.dispose();
		}
	}
}


class CaptureThread extends Thread {
	JDialog owner;
	
	CaptureThread(JDialog owner){
		super();
		this.owner = owner;
	}
	
	
	public void run(){
		Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException ex) {
            ex.printStackTrace();
            return;
        }
        
        
        JWindow window = new JWindow();
        JLabel lbl = new JLabel();
        lbl.addMouseListener(new mouseListener());
		window.getContentPane().add(lbl);

        Color color = Color.WHITE;
        clicked = false;
        PointerInfo lastinfo = null;
		while(!clicked /*&& owner.isVisible()*/){
	        PointerInfo info = MouseInfo.getPointerInfo();
	        if(lastinfo!=null &&
	        	info.getLocation().x == lastinfo.getLocation().x &&
	        	info.getLocation().y == lastinfo.getLocation().y)
	        {
	        	continue;
	        }
	        lastinfo = info;
	        
	        window.setVisible(false);
	        
			Rectangle bounds = new Rectangle(info.getLocation().x-10, info.getLocation().y-10, 20, 20);
			BufferedImage image = robot.createScreenCapture(bounds);
			BufferedImage bigimage = new BufferedImage(80,80,BufferedImage.TYPE_INT_ARGB);
			bigimage.createGraphics().drawImage(image, 0, 0, 80, 80, 0, 0, 20, 20, null);
			lbl.setIcon(new ImageIcon(bigimage));
			window.setBounds(info.getLocation().x-40, info.getLocation().y-40, 80, 80);
			window.setVisible(true);
			
			int d = image.getRaster().getDataBuffer().getElem(10+10*20);
			color = new Color((d>>16)&0xFF, (d>>8)&0xFF, (d>>0)&0xFF);
		}
		
		window.dispose();
		
		if(clicked){
			GColorDialog.chooser.setColor(color);
			GColorDialog.selectedColor = color;
		}
	}
	
	static boolean clicked = false;
	private class mouseListener implements MouseListener {
		@Override
		public void mouseClicked(MouseEvent arg0) {
		}
		@Override
		public void mouseEntered(MouseEvent arg0) {
		}
		@Override
		public void mouseExited(MouseEvent arg0) {
		}
		@Override
		public void mousePressed(MouseEvent arg0) {
			clicked = true;
		}
		@Override
		public void mouseReleased(MouseEvent arg0) {
		}
	}
}

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.swing.*;

public class GPictWindow extends JDialog {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	String name;
	MyLabel3 label;
	
	GPictWindow(PCARD owner, String name, BufferedImage bi, String windowType, boolean visible) {
		super(owner);
		this.name = name;

		//ƒpƒlƒ‹‚ð’Ç‰Á‚·‚é
		if(bi!=null){
			label = new MyLabel3(name, bi);
			getContentPane().add(label);
			
		}
		
		if(windowType.equalsIgnoreCase("rect") || windowType.equalsIgnoreCase("shadow")){
			setUndecorated(true);
			setBounds(owner.getX()+owner.getWidth()/2-bi.getWidth()/2,owner.getY()+owner.getHeight()/2-bi.getHeight()/2,bi.getWidth(),bi.getHeight()/*+owner.getInsets().top*/);
			setResizable(false);
		}
		else{
			setBounds(owner.getX()+owner.getWidth()/2-bi.getWidth()/2,owner.getY()+owner.getHeight()/2-bi.getHeight()/2,bi.getWidth(),bi.getHeight()+owner.getInsets().top);
			//setResizable(false);
		}
		setVisible(visible);
	}
}

class MyLabel3 extends JLabel {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	BufferedImage bi;
	float scale;
	
	MyLabel3(String fname, BufferedImage bi){
		super();
		this.bi = bi;
		this.scale = 1f;
		//this.setIcon(new ImageIcon(bi));
	}
	
    @Override
    protected void paintComponent(Graphics g) {
		if(bi==null){
			g.setColor(Color.WHITE);
			Rectangle r = g.getClipBounds();
			g.fillRect(r.x, r.y, r.width, r.height);
			return;
		}
		g.drawImage(bi,0,0,(int)(bi.getWidth()*scale),(int)(bi.getHeight()*scale), this);
    }
}
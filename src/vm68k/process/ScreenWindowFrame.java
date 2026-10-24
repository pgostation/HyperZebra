import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * The window frame round a full-screen program's game screen (see MacAppHost.updateScreenBar): a Mac OS 9 document window
 * frame named after the program - Apple Platinum, or the document window of the Kaleidoscope scheme in use - with the screen
 * as its content. It is four bare windows (title bar with the top edge, left, right and bottom edges) laid round the screen,
 * so it never covers the game and being raised by a click hides nothing. Dragging any of them moves the whole screen, every
 * window of the program with it; a double click on the title bar puts the screen back in the middle of the display. A click on
 * them hands the keyboard focus to the program's front window (the program keeps its keys). The frame has no close, zoom or
 * collapse box.
 */
final class ScreenWindowFrame {
	/** What the frame asks of its host: all called on the EDT, the offsets in host pixels from where the press was. {@code pressed}:
	 *  any press on the frame - the frame never takes the focus, so the program's front window must (a click on the title bar
	 *  of the screen in the background left the application active with no window focused: menus grey, keys lost). */
	interface Mover{void pressed();void dragStarted();void draggedBy(int dx,int dy);void dragEnded();void centre();}

	/** How the frame looks, in screen pixels (magnified with the screen). */
	interface Look {
		/** The frame's widths outside the content: {top, left, bottom, right}. */
		int[] outsets();
		/** The whole frame for content of that size, the content's place left clear (ARGB, structure-sized). */
		BufferedImage paint(int contentWidth,int contentHeight,String title,boolean active);
	}

	/** The title font of Mac OS 9 windows: the system font at 12 points (Charcoal; Osaka on a Japanese system). */
	static Font titleFont(){return Fonts68k.hostFont(0,12,0);}

	/** Apple Platinum, as the Appearance Manager draws a document window (AppearanceManager68k.Platinum). */
	static final Look PLATINUM=new Look(){
		@Override public int[] outsets(){return AppearanceManager68k.Platinum.outsets(0);}
		@Override public BufferedImage paint(int w,int h,String title,boolean active){
			int[] o=outsets();int[] c={o[0],o[1],o[0]+h,o[1]+w};
			BufferedImage image=new BufferedImage(w+o[1]+o[3],h+o[0]+o[2],BufferedImage.TYPE_INT_ARGB);
			Graphics2D g=image.createGraphics();
			try{
				Font font=titleFont();FontMetrics fm=Fonts68k.metrics(font);
				int[] text=title.isEmpty()?null:AppearanceManager68k.Platinum.titleText(0,c,0,Fonts68k.width(font,title)+2,fm.getAscent()+fm.getDescent());
				AppearanceManager68k.Platinum.windowFrame(g,0,c,active?1:0,0,text);
				if(text!=null){g.setColor(new Color(active?0:0x888888));g.clipRect(text[1],text[0],text[3]-text[1],text[2]-text[0]);
					g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());
					Fonts68k.drawString(g,font,title,text[1]+1,text[0]+(text[2]-text[0]-fm.getAscent()-fm.getDescent())/2+fm.getAscent());}
			}finally{g.dispose();}
			return image;
		}
	};

	private final javax.swing.JFrame[] strips=new javax.swing.JFrame[4]; // title bar (top), left, right, bottom
	private final Rectangle[] sources=new Rectangle[4]; // each strip's part of the magnified frame
	private String title;
	private Look look=PLATINUM;private boolean active=true;
	private int contentWidth,contentHeight,frameWidth,frameHeight;
	private BufferedImage image;private Object interpolation=RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;

	ScreenWindowFrame(String title,final Mover mover){
		title=title==null?"":title;
		this.title=title.toLowerCase(java.util.Locale.ROOT).endsWith(".app")?title.substring(0,title.length()-4):title; // the program's name, as a Mac shows it
		boolean translucent=false;
		try{translucent=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);}catch(Exception ignored){}
		for(int i=0;i<4;i++){
			final int index=i;
			// a bare frame that may take the focus: macOS spends the first click on a window of an application in the background on
			// activating it, and a window that cannot be key gets no event from it - the application came forward with no window
			// focused. Taking the focus, the frame hands it on to the program's front window at once.
			javax.swing.JFrame strip=new javax.swing.JFrame(this.title);strip.setUndecorated(true);strip.setDefaultCloseOperation(javax.swing.JFrame.DO_NOTHING_ON_CLOSE);
			strip.addWindowFocusListener(new java.awt.event.WindowAdapter(){@Override public void windowGainedFocus(java.awt.event.WindowEvent e){javax.swing.SwingUtilities.invokeLater(mover::pressed);}});
			if(translucent)strip.setBackground(new Color(0,0,0,0)); // a scheme's rounded corners and shadows
			JComponent part=new JComponent(){@Override protected void paintComponent(Graphics g){paintStrip((Graphics2D)g,index);}};
			MouseAdapter drag=new MouseAdapter(){
				private Point start;
				@Override public void mousePressed(MouseEvent e){mover.pressed();if(e.getButton()!=MouseEvent.BUTTON1)return;
					if(e.getClickCount()==2&&index==0){start=null;mover.centre();return;}
					start=e.getLocationOnScreen();mover.dragStarted();}
				@Override public void mouseDragged(MouseEvent e){if(start==null)return;Point p=e.getLocationOnScreen();mover.draggedBy(p.x-start.x,p.y-start.y);}
				@Override public void mouseReleased(MouseEvent e){if(start==null)return;start=null;mover.dragEnded();}
			};
			part.addMouseListener(drag);part.addMouseMotionListener(drag);
			strip.setContentPane(part);strips[i]=strip;
		}
	}

	/** The frame's widths outside the screen in screen pixels, for the look it has now. */
	int[] outsets(){return look.outsets().clone();}

	/** Lays the frame round {@code screen} (host pixels; the program's screen is {@code width} x {@code height}) and shows it. */
	void show(Rectangle screen,int width,int height,int percent,Look look,boolean active){
		boolean redraw=look!=this.look||active!=this.active||width!=contentWidth||height!=contentHeight||image==null;
		this.look=look;this.active=active;contentWidth=width;contentHeight=height;
		if(redraw)image=look.paint(width,height,title,active);
		int[] o=look.outsets();
		int top=DisplayZoom.scale(o[0],percent),left=DisplayZoom.scale(o[1],percent),bottom=DisplayZoom.scale(o[2],percent),right=DisplayZoom.scale(o[3],percent);
		frameWidth=screen.width+left+right;frameHeight=screen.height+top+bottom;
		interpolation=DisplayZoom.interpolation(percent/100.0);
		Rectangle[] at={new Rectangle(screen.x-left,screen.y-top,frameWidth,top),new Rectangle(screen.x-left,screen.y,left,screen.height),
			new Rectangle(screen.x+screen.width,screen.y,right,screen.height),new Rectangle(screen.x-left,screen.y+screen.height,frameWidth,bottom)};
		for(int i=0;i<4;i++){
			sources[i]=new Rectangle(at[i].x-(screen.x-left),at[i].y-(screen.y-top),at[i].width,at[i].height);
			javax.swing.JFrame strip=strips[i];
			if(at[i].width<=0||at[i].height<=0){if(strip.isVisible())strip.setVisible(false);continue;}
			if(!strip.getBounds().equals(at[i]))strip.setBounds(at[i]);
			if(redraw)strip.repaint();
			if(!strip.isVisible())strip.setVisible(true);
		}
	}
	/** The title bar's bounds on the display while the frame is shown, else null. */
	Rectangle titleBarBounds(){return strips[0].isVisible()?strips[0].getBounds():null;}
	boolean isShowing(){return strips[0].isVisible();}
	boolean owns(java.awt.Window w){for(javax.swing.JFrame s:strips)if(s==w)return true;return false;}
	void hide(){for(javax.swing.JFrame s:strips)if(s.isVisible())s.setVisible(false);}
	void dispose(){for(javax.swing.JFrame s:strips){s.setVisible(false);s.dispose();}}

	private void paintStrip(Graphics2D g,int index){
		BufferedImage img=image;Rectangle src=sources[index];if(img==null||src==null)return;
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,interpolation);
		g.drawImage(img,-src.x,-src.y,frameWidth,frameHeight,null);
	}
}

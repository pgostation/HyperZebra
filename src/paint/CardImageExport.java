import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

/** カードのペイントとベクターシェイプを画像として平らにする。 */
class CardImageExport {
	static BufferedImage render(OCard card){
		if(card==null || card.stack==null) return null;
		int w=Math.max(1,card.stack.width), h=Math.max(1,card.stack.height);
		BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=image.createGraphics();
		paint(g,card);
		g.dispose();
		return image;
	}

	/** The card's paint and shapes, in card coordinates (the caller may scale g). */
	static void paint(Graphics2D g,OCard card){
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		if(card.bg!=null) renderOwner(g,card.bg);
		renderOwner(g,card);
	}

	private static void renderOwner(Graphics2D g,OCardBase owner){
		if(Boolean.TRUE.equals(owner.showPict)){
			BufferedImage paint=owner.composite();
			if(paint!=null) g.drawImage(paint,0,0,null);
		}
		if(owner.shapeList!=null) for(OShape shape:owner.shapeList){
			if(!shape.isShown()) continue;
			Graphics2D local=(Graphics2D)g.create();
			local.translate(shape.left,shape.top);
			shape.render(local);
			local.dispose();
		}
	}

	static void exportDialog(PCARD frame){
		if(frame==null || frame.stack==null || frame.stack.curCard==null) return;
		JFileChooser chooser=new JFileChooser();
		chooser.setDialogTitle(frame.intl.getText("Export Card Image…"));
		chooser.setFileFilter(PaintFile.PNG_FILTER);
		chooser.setSelectedFile(new File("Card-"+frame.stack.curCard.id+".png"));
		if(PCARD.showFileChooser(chooser,frame,true)!=JFileChooser.APPROVE_OPTION)return;
		File file=chooser.getSelectedFile();
		if(!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))file=new File(file.getParentFile(),file.getName()+".png");
		try{PaintFile.write(render(frame.stack.curCard),file);}
		catch(IOException ex){JOptionPane.showMessageDialog(frame,ex.getMessage());}
	}
}

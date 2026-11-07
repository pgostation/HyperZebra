import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBuffer;
import java.awt.image.Kernel;
import java.io.IOException;

import javax.swing.JScrollPane;
import javax.swing.JOptionPane;
import javax.swing.JViewport;


//ペイント時のメニュー動作
class GMenuPaint implements ActionListener {
	@Override
	public void actionPerformed (ActionEvent e) {
		PaintTool.owner = PCARD.pc;
		String cmd = e.getActionCommand();

		boolean result = doMenu(cmd);
		if(result==false){
			try {
				//the Tools menu's Select is the tool, the Paint menu's the command
				if(fromToolsMenu(e.getSource()) && "Select".equalsIgnoreCase(PCARD.pc.intl.getEngText(cmd))) TTalk.doScriptforMenu("choose select tool");
				else GMenuBrowse.doMenu(cmd);
			} catch (xTalkException e1) {
				e1.printStackTrace();
			}
		}
	}

	private static boolean fromToolsMenu(Object source){
		if(!(source instanceof javax.swing.JMenuItem)) return false;
		java.awt.Container parent = ((javax.swing.JMenuItem)source).getParent();
		return parent instanceof javax.swing.JPopupMenu && ((javax.swing.JPopupMenu)parent).getInvoker() instanceof javax.swing.JMenu &&
			PCARD.pc.intl.getText("Tool").equals(((javax.swing.JMenu)((javax.swing.JPopupMenu)parent).getInvoker()).getText());
	}

	//サーフェース全体をafで変換した同じ大きさの新しい画像
	static BufferedImage transformedSurface(BufferedImage src, AffineTransform af, int width, int height){
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.transform(af);
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}

	//サーフェース全体にぼかし/シャープのフィルタを掛けた新しい画像
	static BufferedImage convolveSurface(BufferedImage bi, float[] operator){
		//端の処理をさせるため、画像サイズを大きくする
		int w = bi.getWidth(), h = bi.getHeight();
		BufferedImage bigbi = new BufferedImage(w+4, h+4, BufferedImage.TYPE_INT_ARGB);
		Graphics2D bg = bigbi.createGraphics();
		bg.drawImage(bi, 0,2,2,h+2, 0,0,2,h, null);
		bg.drawImage(bi, w+2,2,w+4,h+2, w-2,0,w,h, null);
		bg.drawImage(bi, 2,0,w+2,2, 0,0,w,2, null);
		bg.drawImage(bi, 2,h+2,w+2,h+4, 0,h-2,w,h, null);
		bg.drawImage(bi, 2,2, null);
		bg.dispose();
		ConvolveOp convop = new ConvolveOp(new Kernel(5,5,operator), ConvolveOp.EDGE_NO_OP, null);
		BufferedImage bignewimg = convop.filter(bigbi, null);
		BufferedImage newimg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = newimg.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(bignewimg, -2,-2, null);
		g.dispose();
		return newimg;
	}

	static int clamp(int v){ return v<0 ? 0 : (v>255 ? 255 : v); }

	static BufferedImage affineImage(BufferedImage source, AffineTransform transform){
		Point2D[] corners={new Point2D.Double(0,0),new Point2D.Double(source.getWidth(),0),
			new Point2D.Double(0,source.getHeight()),new Point2D.Double(source.getWidth(),source.getHeight())};
		double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
		for(Point2D p:corners){ Point2D q=transform.transform(p,null); minX=Math.min(minX,q.getX()); minY=Math.min(minY,q.getY()); maxX=Math.max(maxX,q.getX()); maxY=Math.max(maxY,q.getY()); }
		int width=Math.max(1,(int)Math.ceil(maxX-minX)), height=Math.max(1,(int)Math.ceil(maxY-minY));
		BufferedImage result=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
		Graphics2D g=result.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,PaintTool.antialias ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.translate(-minX,-minY); g.transform(transform); g.drawImage(source,0,0,null); g.dispose();
		return result;
	}

	static BufferedImage perspectiveImage(BufferedImage source, double topScale, int topOffset){
		topScale=Math.max(0.05,Math.min(4.0,topScale));
		int width=Math.max(1,(int)Math.ceil(Math.max(source.getWidth(),source.getWidth()*topScale)+Math.abs(topOffset)));
		int height=source.getHeight();
		BufferedImage result=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
		for(int y=0;y<height;y++){
			double t=height<=1?1.0:y/(double)(height-1);
			double scale=topScale+(1.0-topScale)*t;
			double rowWidth=source.getWidth()*scale;
			double left=(width-rowWidth)/2.0+topOffset*(1.0-t);
			for(int x=0;x<width;x++){
				int sx=(int)Math.floor((x-left)/scale);
				if(sx>=0 && sx<source.getWidth()) result.setRGB(x,y,source.getRGB(sx,y));
			}
		}
		return result;
	}

	//変形した絵で浮動選択を差し替える(中心は動かない)
	static boolean applyTransformedSelection(PCARDFrame owner, BufferedImage transformed){
		FloatingSelection f = owner.floating;
		if(transformed==null || f==null) return false;
		setUndo();
		f.replaceImage(transformed);
		owner.selectaf=null;
		PaintTool.repaintOwner();
		return true;
	}

	static void promptSelectionTransform(String command){
		FloatingSelection f = PaintTool.floating();
		if(f==null) return;
		BufferedImage source=f.image;
		String initial=command.equals("Rotate")?"0":command.equals("Perspective")?"60,0":"20,0";
		String hint=command.equals("Rotate")?"Angle (degrees)":command.equals("Perspective")?"Top width %, offset pixels":"Horizontal %, vertical %";
		String input=JOptionPane.showInputDialog(PaintTool.owner,hint,initial);
		if(input==null) return;
		try{
			//回転・傾斜はアフィン変換として浮動選択にそのまま掛ける(マスクも一緒に
			//変形され、矩形選択の空いた隅は選択範囲から外れる)。中心は動かさない。
			AffineTransform af=null;
			BufferedImage result=null;
			if(command.equals("Rotate")){
				af=AffineTransform.getRotateInstance(Math.toRadians(Double.parseDouble(input.trim())),source.getWidth()/2.0,source.getHeight()/2.0);
			}else{
				String[] v=input.split(",");
				double a=Double.parseDouble(v[0].trim());
				double b=v.length>1?Double.parseDouble(v[1].trim()):0;
				if(command.equals("Perspective")) result=perspectiveImage(source,a/100.0,(int)Math.round(b));
				else{
					double shx=a/100.0, shy=command.equals("Distort")?b/100.0:0;
					af=AffineTransform.getTranslateInstance(-shx*source.getHeight()/2.0,-shy*source.getWidth()/2.0);
					af.shear(shx,shy);
				}
			}
			if(af!=null){
				setUndo();
				f.transform(af,PaintTool.antialias);
				PaintTool.owner.selectaf=null;
				PaintTool.repaintOwner();
			}
			else applyTransformedSelection(PaintTool.owner,result);
		}catch(NumberFormatException ex){ Toolkit.getDefaultToolkit().beep(); }
	}
	
	//Keep / Revert (HyperCard's Paint menu, 作業の保存 / 復帰): Keep remembers the picture being painted, Revert brings it back.
	//J-Paint's filters Keep before they paint and its "戻す" Reverts. Without a Keep on this card and layer, Revert goes back
	//to the picture last saved to the stack.
	private static BufferedImage keptImage;
	private static OCard keptCard;
	private static boolean keptBg;
	private static PaintLayer keptLayer;

	static void keep(PCARDFrame owner){
		if(owner==null || owner.getSurface()==null) return;
		if(owner.tool!=null) owner.tool.end();
		keptImage = PaintUndo.copyInto(null, owner.getSurface());
		keptCard = owner.stack!=null ? owner.stack.curCard : null;
		keptBg = owner.isEditingBackground();
		keptLayer = owner.activePaintLayer();
	}

	static void revert(PCARDFrame owner){
		if(owner==null || owner.getSurface()==null || owner.stack==null) return;
		if(owner.tool!=null){ owner.tool.end(); owner.tool.clear(); }
		OCard card = owner.stack.curCard;
		boolean bg = owner.isEditingBackground();
		PaintLayer layer = owner.activePaintLayer();
		BufferedImage back;
		if(keptImage!=null && keptCard==card && keptBg==bg && keptLayer==layer) back = keptImage;
		else if(layer==null) back = savedPicture(owner.stack, card, bg);
		else return; //an extra layer kept nowhere
		BufferedImage surface = owner.getSurface();
		setUndo();
		Graphics2D g = surface.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.setColor(bg ? Color.WHITE : new Color(0, 0, 0, 0));
		g.fillRect(0, 0, surface.getWidth(), surface.getHeight());
		if(back!=null) g.drawImage(back, 0, 0, null);
		g.dispose();
		if(card!=null){ card.invalidateComposite(); if(card.bg!=null) card.bg.invalidateComposite(); }
		PaintTool.toCdPict();
		PaintTool.repaintOwner();
	}

	//the card's (or background's) picture as saved in the stack folder; null when it has none
	private static BufferedImage savedPicture(OStack stack, OCard card, boolean bg){
		OCardBase base = card==null ? null : bg ? card.bg : card;
		if(base==null || base.bitmapName==null || base.bitmapName.isEmpty() || stack.file==null) return null;
		java.io.File file = new java.io.File(stack.file.getParent(), base.bitmapName);
		PictureSaver.flush();
		if(!file.isFile()) return null;
		try{ return StackProtection.readImage(file); }
		catch(IOException e){ return null; }
	}

	//Trace Edges (HyperCard's Paint menu): each shape becomes its one-pixel outline just outside it, in the colour of the
	//shape beside it; the rest is cleared (white on the background picture). J-Paint's plug-ins use it.
	static BufferedImage traceEdges(BufferedImage src, boolean whiteGround){
		int w = src.getWidth(), h = src.getHeight();
		int[] in = src.getRGB(0, 0, w, h, null, 0, w);
		int[] out = new int[w*h];
		int ground = whiteGround ? 0xFFFFFFFF : 0;
		for(int y=0; y<h; y++){
			for(int x=0; x<w; x++){
				int i = y*w+x;
				out[i] = ground;
				if(ink(in[i])) continue;
				if(x>0 && ink(in[i-1])) out[i] = in[i-1];
				else if(x<w-1 && ink(in[i+1])) out[i] = in[i+1];
				else if(y>0 && ink(in[i-w])) out[i] = in[i-w];
				else if(y<h-1 && ink(in[i+w])) out[i] = in[i+w];
			}
		}
		BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		result.setRGB(0, 0, w, h, out, 0, w);
		return result;
	}
	private static boolean ink(int argb){ return (argb>>>24)>=0x80 && (argb&0xFFFFFF)!=0xFFFFFF; }

	public static boolean doMenu(String in_cmd)
	{
		String cmd = PCARDFrame.pc.intl.getEngText(in_cmd);
		
		if(cmd.equalsIgnoreCase("Undo Paint") || cmd.equalsIgnoreCase("Redo Paint")){
			applyHistory(PaintTool.owner, cmd.equalsIgnoreCase("Redo Paint"));
		}
		else if(cmd.equalsIgnoreCase("Antialias")){
			PaintTool.antialias = !PaintTool.antialias;
			GMenu.changeSelected("Paint","Antialias",PaintTool.antialias);
			TypeTool.refreshLive();
		}
		else if(cmd.equalsIgnoreCase("Import Paint…")){
			PaintFile.importDialog(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Export Paint…")){
			PaintFile.exportDialog(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Save as ppm…")){
			PaintTool.owner.tool.end();
			PaintTool.owner.tool.clear();
			PictureFile.saveAsPpm(PaintTool.owner.getSurface(), null);
		}
		else if(cmd.equalsIgnoreCase("Background")){
			if(PaintTool.owner.tool!=null){
				PaintTool.owner.tool.end();
				PaintTool.owner.tool.clear();
			}
			
			PaintTool.editBackground = !PaintTool.editBackground;
			GMenu.changeSelected("Edit","Background",PaintTool.editBackground);

			PCARDFrame.pc.mainPane.repaint();

			String titleName = PCARDFrame.pc.stack.getWindowTitle();
			if(PaintTool.editBackground){
				PCARDFrame.pc.setTitle("/// "+titleName+" ///");
			}else{
				PCARDFrame.pc.setTitle(titleName);
			}
		}
		else if(cmd.equalsIgnoreCase("Clear Selection") || cmd.equalsIgnoreCase("Clear Picture")){ //Clear Picture: HyperCard's name of the item (Apple's ClearCardArt: doMenu "clear picture")
			//浮動選択を捨てる(持ち上げた跡は空いたまま)
			PaintTool.discardFloating();
			PaintTool.owner.tool.clear();
			PaintTool.backToToolBeforeSelect();
			PaintTool.owner.mainPane.repaint();
		}
		else if(cmd.equalsIgnoreCase("Cut Picture")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setClipboardImage(scrapImage(f), f.width(), f.height());
				copiedFrom = f.bounds(); copiedBy = ImageSelection.current;
				PaintTool.discardFloating();
				PaintTool.backToToolBeforeSelect();
			}
			else System.out.println("Error");
			PaintTool.owner.mainPane.repaint();
		}
		else if(cmd.equalsIgnoreCase("Copy Picture")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setClipboardImage(scrapImage(f), f.width(), f.height());
				copiedFrom = f.bounds(); copiedBy = ImageSelection.current;
			}
			else System.out.println("Error");
		}
		else if(cmd.equalsIgnoreCase("Paste Picture")){
			//画像ファイルのドロップと同じ処理を使う。HyperZebra 自身がコピーした絵は、HyperCard と同じくコピーした位置に
			//貼る(らくがきカレンダーのスタンプは、貼った絵をボタンの位置からクリックした所へ drag する)
			java.awt.Image pasted = getClipboardImage();
			//in the browse tool HyperCard chooses the select tool and pastes there (Apple's DsktpICN/PlotFinderICN: lock screen,
			//doMenu "Paste Picture", choose browse tool); nothing was pasted without a paint session
			if(pasted!=null && (PaintTool.owner==null || PaintTool.owner==PCARDFrame.pc && PCARDFrame.pc.tool==null) && PCARDFrame.pc.stack!=null){
				if(!TBButtonListener.ChangeTool("Select", null)) return false;
			}
			Rectangle from = copiedFrom;
			if(from!=null && pasted!=null && pasted.getWidth(null)==from.width && pasted.getHeight(null)==from.height){
				PaintTool.pasteAsSelection(pasted, from.x, from.y);
				if(PaintTool.floating()!=null) PaintTool.floating().keepShape();
			}
			else PaintTool.pasteAsSelection(pasted);
			PaintTool.owner.mainPane.repaint();
		}
		else if(cmd.equalsIgnoreCase("Select All")){
			PaintTool.owner.tool.end();
			BufferedImage surface = PaintTool.owner.getSurface();
			//the select tool while the selection lasts, then the tool in use comes back (HC J-2.3)
			if(PaintTool.owner==PCARD.pc && PaintTool.toolBeforeSelect==null && !(PaintTool.owner.tool instanceof SelectTool)) PaintTool.toolBeforeSelect = TBButtonListener.lastcmd;
			//サーフェース全体を浮動選択へ移す
			PaintTool.owner.tool = new SelectTool();
			PaintTool.liftRect(new Rectangle(0, 0, surface.getWidth(), surface.getHeight()));
			PaintTool.selectSelectToolButton(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("FatBits")){
			if(PaintTool.owner instanceof IconEditor){
				((IconEditor)PaintTool.owner).toggleZoom();
				return true;
			}
			if(PaintTool.owner.bit>1){
				PaintTool.leaveFatBits(PaintTool.owner);
			}
			else{
				//最後にクリックした点のあたりが見えるように
				PaintTool.setZoom(PaintTool.owner, PaintTool.FATBITS_ZOOM,
					(int)PaintTool.lastx[0], (int)PaintTool.lasty[0]);
			}
		}
		else if(cmd.equalsIgnoreCase("Zoom In") || cmd.equalsIgnoreCase("Zoom Out")){
			//表示倍率を1段上げる/下げる(FatBitsは8倍固定だが、Zoomは0.25〜32倍の刻み)
			float next = PaintTool.nextZoom(PaintTool.owner.bit, cmd.equalsIgnoreCase("Zoom In") ? 1 : -1);
			PaintTool.setZoom(PaintTool.owner, next, -1, -1);
		}
		else if(cmd.equalsIgnoreCase("Actual Size")){
			PaintTool.setZoom(PaintTool.owner, 1, -1, -1);
		}
		else if(cmd.matches("(?i)Zoom \\d+%")){
			PaintTool.setZoom(PaintTool.owner, Integer.parseInt(cmd.substring(5, cmd.length()-1))/100f, -1, -1);
		}
		else if(cmd.equalsIgnoreCase("Zoom…")){
			String v = JOptionPane.showInputDialog(PaintTool.owner, PCARD.pc.intl.getDialogText("Zoom (%):"),
				String.valueOf(PaintTool.zoomPercent(PaintTool.owner)));
			if(v!=null){
				try{
					PaintTool.setZoom(PaintTool.owner, Float.parseFloat(v.trim().replace("%",""))/100f, -1, -1);
				}catch(NumberFormatException e){}
			}
		}
		else if(cmd.equalsIgnoreCase("Flip Horizontal")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setUndo();
				f.flipHorizontal();
				PaintTool.repaintOwner();
			}
			else{
				//サーフェース全体の左右反転
				setUndo();
				BufferedImage src = PaintTool.owner.getSurface();
				AffineTransform af = new AffineTransform(-1, 0, 0, 1, src.getWidth(), 0);
				PaintTool.owner.setSurface(transformedSurface(src, af, src.getWidth(), src.getHeight()));
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Flip Vertical")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setUndo();
				f.flipVertical();
				PaintTool.repaintOwner();
			}
			else{
				//サーフェース全体の上下反転
				setUndo();
				BufferedImage src = PaintTool.owner.getSurface();
				AffineTransform af = new AffineTransform(1, 0, 0, -1, 0, src.getHeight());
				PaintTool.owner.setSurface(transformedSurface(src, af, src.getWidth(), src.getHeight()));
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Color Convert…")){
			
			new ColorConvertDialog(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Rotate Left") || cmd.equalsIgnoreCase("Rotate Right")){
			boolean left = cmd.equalsIgnoreCase("Rotate Left");
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setUndo();
				f.rotate(left);
				PaintTool.repaintOwner();
			}
			else{
				//サーフェース全体の回転(大きさはそのまま、中心で回す)
				setUndo();
				BufferedImage src = PaintTool.owner.getSurface();
				AffineTransform af = new AffineTransform();
				af.setToRotation(left ? Math.PI*3.0/2.0 : Math.PI/2.0, src.getWidth()/2, src.getHeight()/2);
				PaintTool.owner.setSurface(transformedSurface(src, af, src.getWidth(), src.getHeight()));
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Emboss…")){
			
			new EmbossDialog(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Blur") || cmd.equalsIgnoreCase("Sharpen")){
			boolean blur = cmd.equalsIgnoreCase("Blur");
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setUndo();
				if(blur) f.blur(); else f.sharpen();
				PaintTool.repaintOwner();
			}
			else{
				//サーフェース全体
				setUndo();
				PaintTool.owner.setSurface(convolveSurface(PaintTool.owner.getSurface(),
					blur ? FloatingSelection.BLUR : FloatingSelection.SHARPEN));
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Keep")){
			keep(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Revert")){
			revert(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Trace Edges")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null) applyTransformedSelection(PaintTool.owner, traceEdges(f.image, false));
			else if(PaintTool.owner.getSurface()!=null){
				setUndo();
				boolean ground = PaintTool.owner.isEditingBackground() && PaintTool.owner.activePaintLayer()==null;
				PaintTool.owner.setSurface(traceEdges(PaintTool.owner.getSurface(), ground));
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Invert")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setUndo();
				f.invert();
				PaintTool.repaintOwner();
			}
			else{
				//サーフェース全体の色反転
				setUndo();
				int[] px = PaintTool.directPixels(PaintTool.owner.getSurface());
				if(px!=null) for(int i=0; i<px.length; i++) px[i] = (px[i]&0xFF000000) | (~px[i]&0x00FFFFFF);
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Darken") || cmd.equalsIgnoreCase("Lighten")){
			int delta = cmd.equalsIgnoreCase("Darken") ? -20 : 20;
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				setUndo();
				if(delta<0) f.darken(); else f.lighten();
				PaintTool.repaintOwner();
			}
			else{
				//サーフェース全体
				setUndo();
				int[] px = PaintTool.directPixels(PaintTool.owner.getSurface());
				if(px!=null){
					for(int i=0; i<px.length; i++){
						int c = px[i];
						px[i] = (c&0xFF000000) | (clamp(((c>>16)&0xFF)+delta)<<16) |
							(clamp(((c>>8)&0xFF)+delta)<<8) | clamp((c&0xFF)+delta);
					}
				}
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Tint")){
			//AppleWorksのTint: 前景色へ少し寄せる(選択範囲、無ければサーフェース全体)
			Color fore = PaintTool.owner.fore==null ? Color.BLACK : PaintTool.owner.fore.color;
			FloatingSelection f = PaintTool.floating();
			setUndo();
			if(f!=null){
				f.tint(fore);
				PaintTool.repaintOwner();
			}
			else{
				int[] px = PaintTool.directPixels(PaintTool.owner.getSurface());
				if(px!=null) for(int i=0; i<px.length; i++) px[i] = PaintTool.tintPixel(px[i], fore);
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Blend")){
			//AppleWorksのBlend: 隣の画素と色を混ぜてなだらかにする
			FloatingSelection f = PaintTool.floating();
			setUndo();
			if(f!=null){
				f.blend();
				PaintTool.repaintOwner();
			}
			else{
				PaintTool.owner.setSurface(convolveSurface(PaintTool.owner.getSurface(), FloatingSelection.BLEND));
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Pickup")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				//選択範囲を下にあるものにする
				setUndo();
				f.pickup(PaintTool.owner.getSurface());
				PaintTool.repaintOwner();
			}
			else{
				//選択範囲がありません!
			}
		}
		else if(cmd.equalsIgnoreCase("Fill")){
			PaintTool.setPattern(false);
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				//選択範囲を塗りつぶし
				setUndo();
				f.fill(PaintTool.pat, PaintTool.owner.grad);
				PaintTool.repaintOwner();
			}
			else{
				//サーフェース全体を塗りつぶし
				setUndo();
				BufferedImage bi = PaintTool.owner.getSurface();
				Graphics2D g = bi.createGraphics();
				g.setComposite(AlphaComposite.Src);
				g.setPaint(new java.awt.TexturePaint(PaintTool.pat,
					new Rectangle2D.Double(0, 0, PaintTool.pat.getWidth(), PaintTool.pat.getHeight())));
				g.fillRect(0, 0, bi.getWidth(), bi.getHeight());
				g.dispose();
				//グラデーションを適用(パターンとの併用は不可)
				if(PaintTool.owner.grad!=null && PaintTool.owner.grad.use){
					PaintBucketTool.gradfill(bi, PaintTool.owner.grad);
				}
				PaintTool.owner.mainPane.repaint();
			}
		}
		else if(cmd.equalsIgnoreCase("Transparent")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				//選択範囲の白色を透明にする
				setUndo();
				f.transparent();
				PaintTool.repaintOwner();
			}
		}
		else if(cmd.equalsIgnoreCase("Opaque")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				//選択範囲の透明を白色にする
				setUndo();
				f.opaque();
				PaintTool.repaintOwner();
			}
		}
		else if(cmd.equalsIgnoreCase("Reverse Selection")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null && !f.isRectangular()){
				//選択範囲を逆転する: 今の絵を確定し、その形の外側を持ち上げる
				BufferedImage surface = PaintTool.owner.getSurface();
				BufferedImage inside = f.surfaceMask(surface.getWidth(), surface.getHeight());
				PaintTool.commitFloating();
				int[] px = PaintTool.directPixels(inside);
				for(int i=0; i<px.length; i++) px[i] = (px[i]&0xFF000000)==0 ? 0xFF000000 : 0;
				PaintTool.liftMask(inside);
				PaintTool.repaintOwner();
			}
			else{
				//選択範囲がありません!
			}
		}
		else if(cmd.equalsIgnoreCase("Scale Selection…") || cmd.equalsIgnoreCase("Stretch")){
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				new PaintScaleDialog(PaintTool.owner);
			}
			else{
				//選択範囲がありません!
			}
		}
		else if(cmd.equalsIgnoreCase("Rotate") || cmd.equalsIgnoreCase("Slant") ||
			cmd.equalsIgnoreCase("Distort") || cmd.equalsIgnoreCase("Perspective")){
			promptSelectionTransform(cmd.substring(0,1).toUpperCase()+cmd.substring(1).toLowerCase());
		}
		else if(cmd.equalsIgnoreCase("Add 1-Pixel Margin to Selection") || cmd.equalsIgnoreCase("Expand Selection")){
			//旧名 Expand Selection も doMenu 用に受け付ける(Grow Selection…と紛らわしいので改名)
			FloatingSelection f = PaintTool.floating();
			if(f!=null){
				//選択範囲を1pixel拡大(持ち上げた絵はそのまま、周りに透明な縁を足す。周りの絵は拾わない)
				setUndo();
				f.expand();
				PaintTool.repaintOwner();
			}
			else{
				//選択範囲がありません!
			}
		}
		else if(cmd.equalsIgnoreCase("Feather Selection…") || cmd.equalsIgnoreCase("Grow Selection…") ||
				cmd.equalsIgnoreCase("Shrink Selection…")){
			//選択範囲の形の操作(PaintTool.featherSelection等。スクリプトからは
			//feather/grow/shrink selection by N)
			if(PaintTool.floating()==null){
				JOptionPane.showMessageDialog(PaintTool.owner, PCARD.pc.intl.getDialogText("No selection"));
				return true;
			}
			String prompt = cmd.startsWith("Feather") ? "Feather radius (pixels):" : cmd.startsWith("Grow") ? "Grow by (pixels):" : "Shrink by (pixels):";
			String v = JOptionPane.showInputDialog(PaintTool.owner, PCARD.pc.intl.getDialogText(prompt), cmd.startsWith("Feather") ? "4" : "2");
			if(v==null) return true;
			float r;
			try{ r = Float.parseFloat(v.trim()); }catch(NumberFormatException e){ return true; }
			if(r<=0) return true;
			if(cmd.startsWith("Feather")) PaintTool.featherSelection(r);
			else if(cmd.startsWith("Grow")) PaintTool.growSelection(r);
			else PaintTool.shrinkSelection(r);
		}
		else if(cmd.equalsIgnoreCase("Filter…")){
			
			new PaintFilter(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Blending Mode…")){
			
			PaintBlendDialog.showPaintBlendDialog(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Grid Snap")){
			PaintTool.drawingGrid = !PaintTool.drawingGrid;
			GMenu.changeSelected("Options","Grid Snap",PaintTool.drawingGrid);
		}
		else if(cmd.matches("(?i)Drawing Grid Size \\d+")){
			PaintTool.setDrawingGridSize(Integer.parseInt(cmd.substring(cmd.lastIndexOf(' ')+1)));
			PaintTool.drawingGrid = true;
			GMenu.changeSelected("Options","Grid Snap",true);
		}
		else if(cmd.equalsIgnoreCase("Drawing Grid Size…")){
			//任意の大きさ(1〜256)を入力。入力した大きさでスナップも入れる
			String v = JOptionPane.showInputDialog(PaintTool.owner, PCARD.pc.intl.getDialogText("Grid size (pixels):"),
				Integer.toString(PaintTool.drawingGridSize));
			if(v==null) return true;
			int size;
			try{ size = Integer.parseInt(v.trim()); }catch(NumberFormatException e){ return true; }
			if(size<1 || size>PaintTool.GRID_SIZE_MAX) return true;
			PaintTool.setDrawingGridSize(size);
			PaintTool.drawingGrid = true;
			GMenu.changeSelected("Options","Grid Snap",true);
		}
		else if(cmd.equalsIgnoreCase("Draw Centered")){
			PaintTool.drawCentered = !PaintTool.drawCentered;
			GMenu.changeSelected("Options","Draw Centered",PaintTool.drawCentered);
		}
		else if(cmd.equalsIgnoreCase("Draw Filled")){
			PaintTool.owner.fill = !PaintTool.owner.fill;
			GMenu.changeSelected("Options","Draw Filled",PaintTool.owner.fill);
		}
		else if(cmd.equalsIgnoreCase("Draw Multiple")){
			PaintTool.drawMultiple = !PaintTool.drawMultiple;
			GMenu.changeSelected("Options","Draw Multiple",PaintTool.drawMultiple);
		}
		else if(cmd.matches("(?i)Multiple Space [1-9]")){
			PaintTool.setMultiSpace(Integer.parseInt(cmd.substring(cmd.lastIndexOf(' ')+1)));
		}
		else if(cmd.equalsIgnoreCase("Power Keys")){
			PaintTool.setPowerKeys(!PaintTool.powerKeys);
		}
		else if(cmd.equalsIgnoreCase("Show Layers")){
			GLayerPalette.toggle();
		}
		else if(cmd.equalsIgnoreCase("New Layer")){
			PaintLayers.add(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Duplicate Layer")){
			PaintLayers.duplicate(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Delete Layer")){
			PaintLayers.delete(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Merge Down")){
			PaintLayers.mergeDown(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Flatten Layers")){
			PaintLayers.flattenAll(PaintTool.owner);
		}
		else if(cmd.equalsIgnoreCase("Move Layer Up")){
			PaintLayers.move(PaintTool.owner, 1);
		}
		else if(cmd.equalsIgnoreCase("Move Layer Down")){
			PaintLayers.move(PaintTool.owner, -1);
		}
		else if(cmd.equalsIgnoreCase("Rename Layer…")){
			if(PaintTool.owner.activePaintLayer()!=null){
				String v = JOptionPane.showInputDialog(PaintTool.owner, PCARD.pc.intl.getDialogText("Layer name:"),
					PaintLayers.nameOf(PaintTool.owner, PaintTool.owner.activeLayer()));
				if(v!=null) PaintLayers.rename(PaintTool.owner, v);
			}
		}
		else if(cmd.equalsIgnoreCase("Symmetry Off")){
			PaintTool.setSymmetry(PaintTool.SYM_NONE);
		}
		else if(cmd.equalsIgnoreCase("Vertical Symmetry")){
			PaintTool.setSymmetry(PaintTool.SYM_VERTICAL);
		}
		else if(cmd.equalsIgnoreCase("Horizontal Symmetry")){
			PaintTool.setSymmetry(PaintTool.SYM_HORIZONTAL);
		}
		else if(cmd.equalsIgnoreCase("Quadrant Symmetry")){
			PaintTool.setSymmetry(PaintTool.SYM_QUAD);
		}
		else if(cmd.equalsIgnoreCase("Radial Symmetry…")){
			//回転数(2〜16)を入力してから放射対称に
			String v = JOptionPane.showInputDialog(PaintTool.owner, PCARD.pc.intl.getDialogText("Radial symmetry count (2-16):"),
				Integer.toString(PaintTool.symmetryAxes));
			if(v==null){ PaintTool.setSymmetry(PaintTool.symmetry); return true; }
			int n;
			try{ n = Integer.parseInt(v.trim()); }catch(NumberFormatException e){ PaintTool.setSymmetry(PaintTool.symmetry); return true; }
			if(n<PaintTool.SYMMETRY_AXES_MIN || n>PaintTool.SYMMETRY_AXES_MAX){ PaintTool.setSymmetry(PaintTool.symmetry); return true; }
			PaintTool.setSymmetryAxes(n);
			PaintTool.setSymmetry(PaintTool.SYM_RADIAL);
		}
		else if(cmd.equalsIgnoreCase("Symmetry Center…")){
			//中心の座標(空ならキャンバスの中心に戻す)
			BufferedImage sfc = PaintTool.owner.getSurface();
			String cur = sfc==null ? "" : Math.round(PaintTool.symmetryCenterX(sfc))+","+Math.round(PaintTool.symmetryCenterY(sfc));
			String v = JOptionPane.showInputDialog(PaintTool.owner, PCARD.pc.intl.getDialogText("Symmetry center (x,y; empty = canvas center):"), cur);
			if(v==null) return true;
			if(!PaintTool.setSymmetryCenter(v)) return true;
			PaintTool.owner.repaint();
		}
		else if(cmd.equalsIgnoreCase("Use Grid")){
			IconEditor.useGrid = !IconEditor.useGrid;
			PaintTool.owner.repaint();
		}
		else if(cmd.equalsIgnoreCase("Grid Size 1")){
			IconEditor.gridSize = 1;
			PaintTool.owner.repaint();
		}
		else if(cmd.equalsIgnoreCase("Grid Size 16")){
			IconEditor.gridSize = 16;
			PaintTool.owner.repaint();
		}
		else{
			return false;
		}
		
		return true;
	}
	
	
	//クリップボードに画像をコピー
	/** Where the picture HyperZebra last put on the clipboard was (Cut/Copy Picture); null once another program owns it. */
	static volatile Rectangle copiedFrom;
	/** Who put {@link #copiedFrom} there: HyperZebra's own copy (its ImageSelection) or an external's scrap. */
	private static volatile Object copiedBy;
	/** An external put a picture on the scrap (it reaches the host clipboard as an image): Paste Picture puts it where its
	 *  picFrame says, as HyperCard does with a PICT (Apple's DsktpICN pastes a Finder icon framed at the place asked for). */
	static void externalPicture(Object owner, Rectangle frame){ copiedBy = owner; copiedFrom = frame; }
	/** That scrap is no longer on the clipboard. */
	static void externalPictureGone(Object owner){ if(copiedBy==owner) copiedFrom = null; }

	/** Option-Copy Card: the card as shown (its fields and buttons too) goes on the clipboard, and Paste Picture puts it back
	 *  at the card's top left. */
	static void copyCardPicture(){
		OStack st = PCARDFrame.pc.stack;
		if(st==null) return;
		BufferedImage img = Printing.captureCurrentCard(new Rectangle(0, 0, st.width, st.height), 1);
		if(img==null) return;
		setClipboardImage(img, img.getWidth(), img.getHeight());
		copiedFrom = new Rectangle(0, 0, img.getWidth(), img.getHeight()); copiedBy = ImageSelection.current;
	}

	/** The picture Copy/Cut Picture puts on the clipboard. A Select-tool rectangle is opaque as on HyperCard's scrap (a 1-bit
	 *  PICT): the card layer's transparent pixels become white, so Paste Picture covers what is below (Machina's part preview
	 *  box showed every part pasted over the last one). A lasso selection keeps its shape. */
	static BufferedImage scrapImage(FloatingSelection f){
		if(!f.isRectangular()) return f.image;
		BufferedImage out = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, f.width(), f.height());
		g.drawImage(f.image, 0, 0, null);
		g.dispose();
		return out;
	}

	public static void setClipboardImage(BufferedImage img, int width, int height) {
		Toolkit kit = Toolkit.getDefaultToolkit();
		Clipboard clip = kit.getSystemClipboard();

		ImageSelection is = new ImageSelection(img, width, height);
		ImageSelection.current = is;
		clip.setContents(is, is);
		ScrapManager68k.hostClipboardGeneration++; //externals' scraps take it at once
	}
	
	
	static class ImageSelection implements Transferable, ClipboardOwner {
		static volatile ImageSelection current;

		protected Image data;

		public ImageSelection(BufferedImage image, int width, int height) {
			this.data = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = (Graphics2D)this.data.getGraphics();
			g.setColor(Color.WHITE);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
			g.fillRect(0, 0, width, height);
			g = (Graphics2D)this.data.getGraphics();
			g.drawImage(image, 0, 0, PCARDFrame.pc);
		}

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { DataFlavor.imageFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
			if (DataFlavor.imageFlavor.equals(flavor)) {
				return data;
			}
			throw new UnsupportedFlavorException(flavor);
		}

		@Override
		public void lostOwnership(Clipboard clipboard, Transferable contents) {
			//(called later, also when HyperZebra itself copies again: only the latest copy's loss counts)
			if(copiedBy==this) copiedFrom = null;
			this.data = null;
		}
	}
	
	
	public static Image getClipboardImage() {
		Toolkit kit = Toolkit.getDefaultToolkit();
		Clipboard clip = kit.getSystemClipboard();

		try {
			return (Image) clip.getData(DataFlavor.imageFlavor);
		} catch (UnsupportedFlavorException e) {
			return null;
		} catch (IOException e) {
			return null;
		}
	}
	
	
	
	//Undo/Redo。浮動選択はそのまま履歴の一部として戻る(移動した選択をUndoすると
	//元の位置へ戻る)。入力中の文字だけは確定してから戻す。
	static void applyHistory(PCARDFrame owner, boolean redo){
		if(owner==null) return;
		if(owner.tool instanceof TypeTool){
			owner.tool.end();
		}
		PaintTool.floatDragging = false;
		if(redo) PaintUndo.redo(owner);
		else PaintUndo.undo(owner);
		if(owner==PaintTool.owner) PaintTool.toCdPict();
		if(owner.mainPane!=null) owner.mainPane.repaint();
	}

	//編集操作の直前に呼ぶ。現在の状態を履歴の保留にし、メニューの状態を更新する。
	static public void setUndo(){
		PaintUndo.snapshot(PaintTool.owner);
		PaintUndo.refresh(PaintTool.owner);
	}

	//Undo/Redoメニューの有効・無効を切り替える(メニューバーが無い場合は何もしない)
	static void setUndoMenu(PCARDFrame owner, boolean undoEnabled, boolean redoEnabled){
		if(owner==null) return;
		if(owner == PCARDFrame.pc){
			if(PCARDFrame.pc.getJMenuBar()==null) return;
			GMenu.changeEnabled("Edit","Redo Paint",redoEnabled);
			GMenu.changeEnabled("Edit","Undo Paint",undoEnabled);
		}else if(owner instanceof IconEditor){
			if(IEMenu.owner==null || IEMenu.owner.getJMenuBar()==null) return;
			IEMenu.changeEnabled("Edit","Redo Paint",redoEnabled);
			IEMenu.changeEnabled("Edit","Undo Paint",undoEnabled);
		}
	}
}

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Kernel;
import java.util.ArrayList;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;

class PaintFilter extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	private PCARDFrame owner;
	private BufferedImage save_bi;
	private JComboBox filtercombo;
	private JComboBox colorcombo;
	private JComboBox dithercombo;
	
	PaintFilter(PCARDFrame owner) {
		super(owner, true);
		this.owner = owner;
		getContentPane().setLayout(new BorderLayout());
		setTitle(PCARD.pc.intl.getDialogText("Filter"));
		
		if(!(owner.tool instanceof toolSelectInterface) ||
				((toolSelectInterface)owner.tool).getSelectedSurface(owner)==null)
		{
			GMenuPaint.setUndo();
			GMenuPaint.doMenu("Select All");
			
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
		}
		
		//ダイアログ内を作る
		makeDialog();
		
		setBounds(owner.getX()+owner.getWidth()/2-150,owner.getY()+20,300,200);
		
		setResizable(false);
		setVisible(true);
	}

	private void makeDialog()
	{
		this.getContentPane().removeAll();
		this.getContentPane().repaint();

		if(owner.tool instanceof toolSelectInterface &&
				((toolSelectInterface)owner.tool).getSelectedSurface(owner)!=null)
		{
			toolSelectInterface tl = (toolSelectInterface)owner.tool;
			save_bi = tl.getSelectedSurface(owner);
		}
		else return;
		

		{
			JPanel panel = new JPanel();
			Border aquaBorder = UIManager.getBorder("TitledBorder.aquaVariant");
			if(aquaBorder==null){
				aquaBorder = new EtchedBorder();
			}
			panel.setBorder( new TitledBorder( aquaBorder, PCARD.pc.intl.getDialogText("Filter") ) );
			getContentPane().add("Center", panel);
			
			String[] value = new String[]{
					PCARD.pc.intl.getDialogText("None"),
					PCARD.pc.intl.getDialogText("Trace Edges"),
					PCARD.pc.intl.getDialogText("Trace Edges 2"),
					PCARD.pc.intl.getDialogText("Spread Edges Dark"),
					PCARD.pc.intl.getDialogText("Spread Edges"),
					PCARD.pc.intl.getDialogText("Spread Edges Light"),
					PCARD.pc.intl.getDialogText("Small Median"),
					PCARD.pc.intl.getDialogText("Median"),
					PCARD.pc.intl.getDialogText("Large Median"),
					PCARD.pc.intl.getDialogText("Motion Blur"),
					PCARD.pc.intl.getDialogText("Blur"),
					PCARD.pc.intl.getDialogText("Sharpen"),
					PCARD.pc.intl.getDialogText("Glass Tile"),
					PCARD.pc.intl.getDialogText("Frosted Glass"),
					PCARD.pc.intl.getDialogText("Horizontal Wave"),
					PCARD.pc.intl.getDialogText("Vertical Wave"),
					PCARD.pc.intl.getDialogText("Noise"),
					PCARD.pc.intl.getDialogText("Higher Contrast"),
					PCARD.pc.intl.getDialogText("Lower Contrast"),
					PCARD.pc.intl.getDialogText("Higher Saturation"),
					PCARD.pc.intl.getDialogText("Lower Saturation"),
					PCARD.pc.intl.getDialogText("Grayscale"),
					PCARD.pc.intl.getDialogText("Binarization"),
					PCARD.pc.intl.getDialogText("Index Color")};
			filtercombo = new JComboBox(value);
			filtercombo.setName("Filter");
			filtercombo.setSelectedIndex(0);
			filtercombo.setMaximumRowCount(32);
			filtercombo.addActionListener(this);
			panel.add(filtercombo);
			
			value = new String[]{
					"Auto","512","256","128","64","32","16","12","8","6","4","3","2"
					};
			colorcombo = new JComboBox(value);
			colorcombo.setName("Colors");
			colorcombo.setSelectedIndex(0);
			colorcombo.setMaximumRowCount(32);
			colorcombo.addActionListener(this);
			colorcombo.setVisible(false);
			panel.add(colorcombo);
			
			value = new String[]{
					"None","Floyd-Steinburg","Bill Atkinson"/*,"Pattern"*/
					};
			dithercombo = new JComboBox(value);
			dithercombo.setName("Dither");
			dithercombo.setSelectedIndex(0);
			dithercombo.setMaximumRowCount(32);
			dithercombo.addActionListener(this);
			dithercombo.setVisible(false);
			panel.add(dithercombo);
		}
		
		//ok, cancel
		{
			JPanel panel = new JPanel();
			getContentPane().add("South", panel);
			
			JButton button = new JButton(PCARD.pc.intl.getDialogText("Cancel"));
			button.setName("Cancel");
			button.addActionListener(this);
			panel.add(button);
			
			button = new JButton(PCARD.pc.intl.getDialogText("OK"));
			button.setName("OK");
			button.addActionListener(this);
			panel.add(button);
		}		
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		String name = ((JComponent)e.getSource()).getName();
		String cmd = PCARD.pc.intl.getDialogEngText(name);
		if(cmd!=null && cmd.equals("Cancel")){
			owner.redoBuf = save_bi;
			this.dispose();
			owner.mainPane.repaint();
			return;
		}
		if(cmd!=null && cmd.equals("OK")){
			this.dispose();
			return;
		}
		
		String colorstr = (String)colorcombo.getSelectedItem();
		int color = 0;
		if(PCARD.pc.intl.getDialogEngText(colorstr).equals("Auto")){
			color = -1;
		}
		else{
			color = Integer.valueOf(colorstr);
		}
		int dithermode = dithercombo.getSelectedIndex()-1;
		
		BufferedImage obi = save_bi;
		int width = obi.getWidth();
		int height = obi.getHeight();
		if(owner.tool.getClass()==SelectTool.class){
			width = ((SelectTool)owner.tool).getSelectedRect().width;
			height = ((SelectTool)owner.tool).getSelectedRect().height;
		}
		BufferedImage srcbi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D srcg = srcbi.createGraphics();
		srcg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		srcg.fillRect(0,0,srcbi.getWidth(), srcbi.getHeight());
		srcg = srcbi.createGraphics();
		srcg.drawImage(obi, 0, 0, null);
		
		BufferedImage newbi = null;
		
		if(cmd!=null && cmd.equals("Filter")){
			colorcombo.setVisible(false);
			dithercombo.setVisible(false);
			
			String str = (String)((JComboBox)e.getSource()).getSelectedItem();
			String selectedFilter = PCARD.pc.intl.getDialogEngText(str);
			if(selectedFilter.equals("None")){
				newbi = srcbi;
			} 
			else if(selectedFilter.equals("Trace Edges")){
				newbi = TraceEdges(srcbi);
			}
			else if(selectedFilter.equals("Trace Edges 2")){
				newbi = TraceEdges2(srcbi);
			}
			else if(selectedFilter.equals("Spread Edges Dark")){
				newbi = SpreadPixel(srcbi,0);
			}
			else if(selectedFilter.equals("Spread Edges Light")){
				newbi = SpreadPixel(srcbi,1);
			}
			else if(selectedFilter.equals("Spread Edges")){
				newbi = SpreadPixel(srcbi,2);
			}
			else if(selectedFilter.equals("Small Median")){
				newbi = Median5(srcbi);
			}
			else if(selectedFilter.equals("Median")){
				newbi = Median(srcbi);
			}
			else if(selectedFilter.equals("Large Median")){
				newbi = Median25(srcbi);
			}
			else if(selectedFilter.equals("Motion Blur")){
				newbi = MotionBlur(srcbi);
			}
			else if(selectedFilter.equals("Blur")){
				newbi = Blur(srcbi);
			}
			else if(selectedFilter.equals("Sharpen")){
				newbi = Sharpen(srcbi);
			}
			else if(selectedFilter.equals("Glass Tile")){
				newbi = GlassTile(srcbi);
			}
			else if(selectedFilter.equals("Frosted Glass")){
				newbi = FrostedGlass(srcbi);
			}
			else if(selectedFilter.equals("Horizontal Wave")){
				newbi = WaveH(srcbi);
			}
			else if(selectedFilter.equals("Vertical Wave")){
				newbi = WaveV(srcbi);
			}
			else if(selectedFilter.equals("Noise")){
				newbi = Noise(srcbi);
			}
			else if(selectedFilter.equals("Higher Contrast")){
				newbi = HighContrast(srcbi);
			}
			else if(selectedFilter.equals("Lower Contrast")){
				newbi = LowContrast(srcbi);
			}
			else if(selectedFilter.equals("Higher Saturation")){
				newbi = HighSaturation(srcbi);
			}
			else if(selectedFilter.equals("Lower Saturation")){
				newbi = LowSaturation(srcbi);
			}
			else if(selectedFilter.equals("Grayscale")){
				newbi = Grayscale(srcbi);
			}
			else if(selectedFilter.equals("Binarization")){
				newbi = BinarizationDither(srcbi, dithermode);
				dithercombo.setVisible(true);
			}
			else if(selectedFilter.equals("Index Color")){
				newbi = IndexColor(srcbi, color, dithermode);
				colorcombo.setVisible(true);
				dithercombo.setVisible(true);
			}
		}
		if(cmd!=null && cmd.equals("Colors")){
			String str = (String)filtercombo.getSelectedItem();
			String selectedFilter = PCARD.pc.intl.getDialogEngText(str);
			if(selectedFilter.equals("Index Color")){
				newbi = IndexColor(srcbi, color, dithermode);
			}
		}
		if(cmd!=null && cmd.equals("Dither")){
			String str = (String)filtercombo.getSelectedItem();
			String selectedFilter = PCARD.pc.intl.getDialogEngText(str);
			if(selectedFilter.equals("Binarization")){
				newbi = BinarizationDither(srcbi, dithermode);
			}
			else if(selectedFilter.equals("Index Color")){
				newbi = IndexColor(srcbi, color, dithermode);
			}
		}
		
		if(newbi != null){
			if(owner.tool instanceof toolSelectInterface &&
					((toolSelectInterface)owner.tool).getSelectedSurface(owner)!=null)
			{
				owner.redoBuf = newbi;
			}
		}
		owner.mainPane.repaint();
	}


	private BufferedImage getBigImage(BufferedImage bi)
	{
		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = new BufferedImage(bi.getWidth()+4, bi.getHeight()+4, BufferedImage.TYPE_INT_ARGB);

		if(owner.tool.getClass()==SelectTool.class){
			Graphics2D newg = bigbi.createGraphics();
			newg.drawImage(bi, 0,2,2,bi.getHeight()+2, 0,0,2,bi.getHeight(), null);
			newg.drawImage(bi, bi.getWidth()+2,2,bi.getWidth()+4,bi.getHeight()+2, bi.getWidth()-2,0,bi.getWidth(),bi.getHeight(), null);
			newg.drawImage(bi, 2,0,bi.getWidth()+2,2, 0,0,bi.getWidth(),2, null);
			newg.drawImage(bi, 2,bi.getHeight()+2,bi.getWidth()+2,bi.getHeight()+4, 0,bi.getHeight()-2,bi.getWidth(),bi.getHeight(), null);
			newg.drawImage(bi, 2,2, null);
		}
		else{
			Graphics2D newg = bigbi.createGraphics();
			newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
			newg.fillRect(0,0,bigbi.getWidth(), bigbi.getHeight());
			newg = bigbi.createGraphics();
			newg.drawImage(bi, 0,0, null);
			newg.drawImage(bi, 0,4, null);
			newg.drawImage(bi, 4,0, null);
			newg.drawImage(bi, 4,4, null);
			newg.drawImage(bi, 0,2, null);
			newg.drawImage(bi, 2,0, null);
			newg.drawImage(bi, 4,2, null);
			newg.drawImage(bi, 2,4, null);
			newg.drawImage(bi, 2,2, null);
		}
		
		return bigbi;
	}
	
	
	private BufferedImage getBigImage(BufferedImage bi, int awidth, int aheight)
	{
		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = new BufferedImage(bi.getWidth()+awidth*2, bi.getHeight()+aheight*2, BufferedImage.TYPE_INT_ARGB);

		if(owner.tool.getClass()==SelectTool.class){
			Graphics2D newg = bigbi.createGraphics();
			newg.drawImage(bi, 0,aheight,awidth,bi.getHeight()+aheight,                                     0,0,awidth,bi.getHeight(), null);
			newg.drawImage(bi, bi.getWidth()+awidth,aheight,bi.getWidth()+awidth*2,bi.getHeight()+aheight,  bi.getWidth()-awidth,0,bi.getWidth(),bi.getHeight(), null);
			newg.drawImage(bi, awidth,0,bi.getWidth()+awidth,aheight,                                       0,0,bi.getWidth(),aheight, null);
			newg.drawImage(bi, awidth,bi.getHeight()+aheight,bi.getWidth()+awidth,bi.getHeight()+aheight*2, 0,bi.getHeight()-aheight,bi.getWidth(),bi.getHeight(), null);
			newg.drawImage(bi, awidth,aheight, null);
		}
		else{
			Graphics2D newg = bigbi.createGraphics();
			newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
			newg.fillRect(0,0,bigbi.getWidth(), bigbi.getHeight());
			newg = bigbi.createGraphics();
			newg.drawImage(bi, 0,aheight, null);
			newg.drawImage(bi, awidth,0, null);
			newg.drawImage(bi, awidth*2,aheight, null);
			newg.drawImage(bi, awidth,aheight*2, null);
			newg.drawImage(bi, awidth,aheight, null);
		}
		
		return bigbi;
	}
	
	private void cleanBigPixel(BufferedImage newimg){
		if(owner.tool.getClass()==LassoTool.class ||
			owner.tool.getClass()==SmartSelectTool.class){
			BufferedImage srcbits = null;
			if(owner.tool.getClass()==LassoTool.class){
				srcbits = ((LassoTool)owner.tool).srcbits;
			}
			else if(owner.tool.getClass()==SmartSelectTool.class){
				srcbits = ((SmartSelectTool)owner.tool).srcbits;
			}
	
			DataBuffer mskbuf = srcbits.getRaster().getDataBuffer();
			DataBuffer movbuf = newimg.getRaster().getDataBuffer();
			int width = srcbits.getWidth();
			int height = srcbits.getHeight();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					if((mskbuf.getElem(h+v*width)&0xFF000000)==0){
						movbuf.setElem(h+v*width, 0x00FFFFFF);
					}
				}
			}
		}
	}
	
	
	private BufferedImage SpreadPixel(BufferedImage srcbi, int mode) {
		BufferedImage newbi = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D newg = newbi.createGraphics();
		newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		newg.fillRect(0,0,newbi.getWidth(), newbi.getHeight());
		
		//処理 (8マス)
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newbi.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		int[] c = new int[9];
		int[] d = new int[9];
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				c[4] = srcdb.getElem(h+v*width);
				for(int iy=-1; iy<=1; iy++){
					for(int ix=-1; ix<=1; ix++){
						if(h+ix<0 || v+iy<0 || h+ix>=width || v+iy>=height){
							c[4+ix+iy*3] = c[4];
						}
						else{
							c[4+ix+iy*3] = srcdb.getElem(h+ix+(v+iy)*width);
						}
						if((c[4+ix+iy*3]&0xFF000000)==0) c[4+ix+iy*3] = 0xFFFFFFFF;
					}
				}
	
				int max=0;
				int maxc = 0xFF000000;
				int min=1000;
				int minc = 0xFFFFFFFF;
				for(int i=0; i<9; i++){
					d[i] = ((c[i]>>16)&0xFF)+((c[i]>>8)&0xFF)+((c[i])&0xFF);
					if(i!=4 && d[i]<min){
						min = d[i];
						minc = c[i];
					}
					if(i!=4 && d[i]>max){
						max = d[i];
						maxc = c[i];
					}
				}
				
				if(/*min==255*3 &&*/ (srcdb.getElem(h+v*width)&0xFF000000)==0){
					continue;
				}
				
				if(mode == 1)
				{
					//周りで最も薄い色にする
					newdb.setElem(h+v*width, maxc);
				}
				else if(mode == 0){
					//周りで最も濃い色にする
					newdb.setElem(h+v*width, minc);
				}
				else{
					//平均色にする
					int cc = 0xFF000000+(((((minc>>17)&0x7F)+((maxc>>17)&0x7F)))<<16)+(((((minc>>9)&0x7F)+((maxc>>9)&0x7F)))<<8)+((((minc>>1)&0x7F)+((maxc>>1)&0x7F)));
					newdb.setElem(h+v*width, cc);
				}
			}
		}
		
		return newbi;
	}
	
	
	private BufferedImage TraceEdges(BufferedImage bi)
	{
		//選択範囲の縁取り
		
		BufferedImage bigbi = getBigImage(bi);
		
		BufferedImage newbigbi = new BufferedImage(bigbi.getWidth(), bigbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D newg = newbigbi.createGraphics();
		newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		newg.fillRect(0,0,newbigbi.getWidth(), newbigbi.getHeight());
		
		//処理 (8マス)
		DataBuffer srcdb = bigbi.getRaster().getDataBuffer();
		DataBuffer newdb = newbigbi.getRaster().getDataBuffer();
		int width = bigbi.getWidth();
		int height = bigbi.getHeight();
		int[] c = new int[9];
		int[] d = new int[9];
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				c[4] = srcdb.getElem(h+v*width);
				for(int iy=-1; iy<=1; iy++){
					for(int ix=-1; ix<=1; ix++){
						if(h+ix<0 || v+iy<0 || h+ix>=width || v+iy>=height){
							c[4+ix+iy*3] = c[4];
						}
						else{
							c[4+ix+iy*3] = srcdb.getElem(h+ix+(v+iy)*width);
						}
						if((c[4+ix+iy*3]&0xFF000000)==0) c[4+ix+iy*3] = 0xFFFFFFFF;
					}
				}

				int max=0;
				int maxc = 0xFF000000;
				int min=1000;
				int minc = 0xFFFFFFFF;
				for(int i=0; i<9; i++){
					d[i] = ((c[i]>>16)&0xFF)+((c[i]>>8)&0xFF)+((c[i])&0xFF);
					if(i!=4 && d[i]<min){
						min = d[i];
						minc = c[i];
					}
					if(i!=4 && d[i]>max){
						max = d[i];
						maxc = c[i];
					}
				}
				
				if(min==255*3 && (srcdb.getElem(h+v*width)&0xFF000000)==0){
					continue;
				}
				
				if( d[4]*8 <= d[0]+d[1]+d[2]+d[3]+d[5]+d[6]+d[7]+d[8])
				{
					//周りで最も薄い色にする
					newdb.setElem(h+v*width, maxc);
				}
				else{
					//周りで最も濃い色にする
					newdb.setElem(h+v*width, minc);
				}
			}
		}

		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(newbigbi, -2,-2, null);
		
		cleanBigPixel(newimg);
	
		return newimg;
	}

	
	private BufferedImage TraceEdges2(BufferedImage bi) {
		//フィルタによる縁取り
		
		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi);

		//フィルター処理
		final float[] operator={
				0.00f, 0.00f, 0.50f, 0.00f, 0.00f,
				0.00f, 0.00f, 0.00f, 0.00f, 0.00f,
				0.50f, 0.00f, -1.00f, 0.00f, 0.50f,
				0.00f, 0.00f, 0.00f, 0.00f, 0.00f,
				0.00f, 0.00f, 0.50f, 0.00f, 0.00f,
			};
		Kernel blur=new Kernel(5,5,operator);
		ConvolveOp convop=new ConvolveOp(blur,ConvolveOp.EDGE_NO_OP,null);
		BufferedImage bignewimg = convop.filter(bigbi,null);

		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -2,-2, null);

		cleanBigPixel(newimg);
		
		return newimg;
	}
	

	private BufferedImage Median(BufferedImage srcbi)
	{
		//選択範囲のメディアン(中央値を取りますよ)
		
		BufferedImage newbi = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D newg = newbi.createGraphics();
		newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		newg.fillRect(0,0,newbi.getWidth(), newbi.getHeight());
		
		//処理 (8マス)
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newbi.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		int[] c = new int[9];
		int[] d = new int[9];
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				c[4] = srcdb.getElem(h+v*width);
				for(int iy=-1; iy<=1; iy++){
					for(int ix=-1; ix<=1; ix++){
						if(h+ix<0 || v+iy<0 || h+ix>=width || v+iy>=height){
							c[4+ix+iy*3] = c[4];
						}
						else{
							c[4+ix+iy*3] = srcdb.getElem(h+ix+(v+iy)*width);
						}
						if((c[4+ix+iy*3]&0xFF000000)==0) c[4+ix+iy*3] = 0xFFFFFFFF;
					}
				}

				for(int i=0; i<9; i++){
					d[i] = ((c[i]>>16)&0xFF)+((c[i]>>8)&0xFF)+((c[i])&0xFF);
				}
				for(int i=0; i<8; i++){
					for(int j=i+1; j<9; j++){
						if(d[i]<d[j]){
							int dd = d[i];
							d[i] = d[j];
							d[j] = dd;
							int cc = c[i];
							c[i] = c[j];
							c[j] = cc;
						}
					}
				}
				
				if((srcdb.getElem(h+v*width)&0xFF000000)==0){
					continue;
				}
				
				//中央値を使う
				newdb.setElem(h+v*width, c[4]);
			}
		}
	
		return newbi;
	}
	

	private BufferedImage Median5(BufferedImage srcbi)
	{
		//選択範囲のメディアン(中央値を取りますよ)(5近傍)
		
		BufferedImage newbi = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D newg = newbi.createGraphics();
		newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		newg.fillRect(0,0,newbi.getWidth(), newbi.getHeight());
		
		//処理 (4マス)
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newbi.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		int[] c = new int[5];
		int[] d = new int[5];
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				c[2] = srcdb.getElem(h+v*width);
				
				if(h-1<0) c[0] = c[2];
				else c[0] = srcdb.getElem(h-1+(v)*width);
				if((c[0]&0xFF000000)==0) c[0] = 0xFFFFFFFF;
				
				if(h+1>=width) c[1] = c[2];
				else c[1] = srcdb.getElem(h+1+(v)*width);
				if((c[1]&0xFF000000)==0) c[1] = 0xFFFFFFFF;
				
				if(v-1<0) c[3] = c[2];
				else c[3] = srcdb.getElem(h+(v-1)*width);
				if((c[3]&0xFF000000)==0) c[3] = 0xFFFFFFFF;
				
				if(v+1>=height) c[4] = c[2];
				else c[4] = srcdb.getElem(h+(v+1)*width);
				if((c[4]&0xFF000000)==0) c[4] = 0xFFFFFFFF;

				for(int i=0; i<5; i++){
					d[i] = ((c[i]>>16)&0xFF)+((c[i]>>8)&0xFF)+((c[i])&0xFF);
				}
				for(int i=0; i<4; i++){
					for(int j=i+1; j<5; j++){
						if(d[i]<d[j]){
							int dd = d[i];
							d[i] = d[j];
							d[j] = dd;
							int cc = c[i];
							c[i] = c[j];
							c[j] = cc;
						}
					}
				}
				
				if((srcdb.getElem(h+v*width)&0xFF000000)==0){
					continue;
				}
				
				//中央値を使う
				newdb.setElem(h+v*width, c[2]);
			}
		}
	
		return newbi;
	}


	private BufferedImage Median25(BufferedImage srcbi)
	{
		//選択範囲のメディアン(中央値を取りますよ)
		
		BufferedImage newbi = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D newg = newbi.createGraphics();
		newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		newg.fillRect(0,0,newbi.getWidth(), newbi.getHeight());
		
		//処理 (24マス)
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newbi.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		int[] c = new int[25];
		int[] d = new int[25];
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				c[12] = srcdb.getElem(h+v*width);
				for(int iy=-2; iy<=2; iy++){
					for(int ix=-2; ix<=2; ix++){
						if(h+ix<0 || v+iy<0 || h+ix>=width || v+iy>=height){
							c[12+ix+iy*3] = c[12];
						}
						else{
							c[12+ix+iy*3] = srcdb.getElem(h+ix+(v+iy)*width);
						}
						if((c[12+ix+iy*3]&0xFF000000)==0) c[12+ix+iy*3] = 0xFFFFFFFF;
					}
				}

				for(int i=0; i<25; i++){
					d[i] = ((c[i]>>16)&0xFF)+((c[i]>>8)&0xFF)+((c[i])&0xFF);
				}
				for(int i=0; i<24; i++){
					for(int j=i+1; j<25; j++){
						if(d[i]<d[j]){
							int dd = d[i];
							d[i] = d[j];
							d[j] = dd;
							int cc = c[i];
							c[i] = c[j];
							c[j] = cc;
						}
					}
				}
				
				if((srcdb.getElem(h+v*width)&0xFF000000)==0){
					continue;
				}
				
				//中央値を使う
				newdb.setElem(h+v*width, c[12]);
			}
		}
	
		return newbi;
	}
	
	
	private BufferedImage MotionBlur(BufferedImage bi) {

		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi);

		//フィルター処理
		final float[] operator={
				0.00f, 0.00f, 0.00f, 0.00f, 0.00f,
				0.00f,-0.06f,-0.08f,-0.06f, 0.00f,
				0.20f, 0.32f, 0.36f, 0.32f, 0.20f,
				0.00f,-0.06f,-0.08f,-0.06f, 0.00f,
				0.00f, 0.00f, 0.00f, 0.00f, 0.00f,
			};
		Kernel blur=new Kernel(5,5,operator);
		ConvolveOp convop=new ConvolveOp(blur,ConvolveOp.EDGE_NO_OP,null);
		BufferedImage bignewimg = convop.filter(bigbi,null);

		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -2,-2, null);

		cleanBigPixel(newimg);
		
		return newimg;
	}

	
	private BufferedImage GlassTile(BufferedImage bi) {
		//myフィルター処理
		int patWidth = 10;
		int patHeight = 10;
		
		final Point[] pattern = new Point[patWidth*patHeight];

		for(int v=0; v<patHeight; v++){
			for(int h=0; h<patWidth; h++){
				pattern[h+v*patWidth] = new Point(h-v,v-h);
			}
		}

		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi, patWidth, patHeight );

		//処理
		BufferedImage bignewimg = new BufferedImage(bi.getWidth()+patWidth*2, bi.getHeight()+patHeight*2, BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = bigbi.getRaster().getDataBuffer();
		DataBuffer newdb = bignewimg.getRaster().getDataBuffer();
		int width = bigbi.getWidth();
		int height = bigbi.getHeight();
		for(int v=patHeight; v<height-patHeight; v++){
			for(int h=patWidth; h<width-patWidth; h++){
				int c = srcdb.getElem(h+pattern[h%patWidth+(v%patHeight)*patWidth].x+(v+pattern[h%patWidth+(v%patHeight)*patWidth].y)*width);
				newdb.setElem(h+v*width, c);
			}
		}
		
		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -patWidth,-patHeight, null);
		
		return newimg;
	}

	
	private BufferedImage WaveH(BufferedImage bi) {
		//myフィルター処理
		int patWidth = 20;
		int patHeight = 20;
		
		final Point[] pattern = new Point[patWidth*patHeight];

		for(int v=0; v<patHeight; v++){
			for(int h=0; h<patWidth; h++){
				pattern[h+v*patWidth] = new Point(
						(int)(Math.sin(v/10.0*Math.PI)*3.7),
						0);
			}
		}

		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi, patWidth, patHeight );

		//処理
		BufferedImage bignewimg = new BufferedImage(bi.getWidth()+patWidth*2, bi.getHeight()+patHeight*2, BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = bigbi.getRaster().getDataBuffer();
		DataBuffer newdb = bignewimg.getRaster().getDataBuffer();
		int width = bigbi.getWidth();
		int height = bigbi.getHeight();
		for(int v=patHeight; v<height-patHeight; v++){
			for(int h=patWidth; h<width-patWidth; h++){
				int c = srcdb.getElem(h+pattern[h%patWidth+(v%patHeight)*patWidth].x+(v+pattern[h%patWidth+(v%patHeight)*patWidth].y)*width);
				newdb.setElem(h+v*width, c);
			}
		}
		
		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -patWidth,-patHeight, null);

		cleanBigPixel(newimg);
		
		return newimg;
	}

	
	private BufferedImage WaveV(BufferedImage bi) {
		//myフィルター処理
		int patWidth = 20;
		int patHeight = 20;
		
		final Point[] pattern = new Point[patWidth*patHeight];

		for(int v=0; v<patHeight; v++){
			for(int h=0; h<patWidth; h++){
				pattern[h+v*patWidth] = new Point(
						0,//(int)(Math.sin(h/10.0*Math.PI)*5-Math.sin(h/20.0*Math.PI)*3),
						(int)(Math.sin(h/10.0*Math.PI)*3.7));
			}
		}

		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi, patWidth, patHeight );

		//処理
		BufferedImage bignewimg = new BufferedImage(bi.getWidth()+patWidth*2, bi.getHeight()+patHeight*2, BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = bigbi.getRaster().getDataBuffer();
		DataBuffer newdb = bignewimg.getRaster().getDataBuffer();
		int width = bigbi.getWidth();
		int height = bigbi.getHeight();
		for(int v=patHeight; v<height-patHeight; v++){
			for(int h=patWidth; h<width-patWidth; h++){
				int c = srcdb.getElem(h+pattern[h%patWidth+(v%patHeight)*patWidth].x+(v+pattern[h%patWidth+(v%patHeight)*patWidth].y)*width);
				newdb.setElem(h+v*width, c);
			}
		}
		
		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -patWidth,-patHeight, null);

		cleanBigPixel(newimg);
		
		return newimg;
	}

	
	private BufferedImage FrostedGlass(BufferedImage bi) {
		int patWidth = 10;
		int patHeight = 10;
		
		final Point[] pattern = new Point[patWidth*patHeight];

		for(int v=0; v<patHeight; v++){
			for(int h=0; h<patWidth; h++){
				pattern[h+v*patWidth] = new Point((int)(Math.random()*3)-1,(int)(Math.random()*3)-1);
			}
		}

		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi, patWidth, patHeight );

		//処理
		BufferedImage bignewimg = new BufferedImage(bi.getWidth()+patWidth*2, bi.getHeight()+patHeight*2, BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = bigbi.getRaster().getDataBuffer();
		DataBuffer newdb = bignewimg.getRaster().getDataBuffer();
		int width = bigbi.getWidth();
		int height = bigbi.getHeight();
		for(int v=patHeight; v<height-patHeight; v++){
			for(int h=patWidth; h<width-patWidth; h++){
				int c = srcdb.getElem(h+pattern[h%patWidth+(v%patHeight)*patWidth].x+(v+pattern[h%patWidth+(v%patHeight)*patWidth].y)*width);
				newdb.setElem(h+v*width, c);
			}
		}
		
		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -patWidth,-patHeight, null);

		cleanBigPixel(newimg);
		
		return newimg;
	}

	
	private BufferedImage Noise(BufferedImage srcbi) {
		//処理
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newimg.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcdb.getElem(h+v*width);
				int a = c>>24;
				int r = (c>>16)&0xFF;
				int g = (c>>8)&0xFF;
				int b = (c)&0xFF;
				
				r += (int)(Math.random()*30-15);
				if(r<0) r=0; if(r>255) r = 255;
				g += (int)(Math.random()*30-15);
				if(g<0) g=0; if(g>255) g = 255;
				b += (int)(Math.random()*30-15);
				if(b<0) b=0; if(b>255) b = 255;
				
				int d = (a<<24) + (r<<16) + (g<<8) + b;
				newdb.setElem(h+v*width, d);
			}
		}
		
		return newimg;
	}

	
	private BufferedImage HighContrast(BufferedImage srcbi) {
		//処理
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newimg.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcdb.getElem(h+v*width);
				int a = c>>24;
				int r = (c>>16)&0xFF;
				int g = (c>>8)&0xFF;
				int b = (c)&0xFF;
				
				r = (int) (r*1.2-25);
				if(r<0) r=0; if(r>255) r = 255;
				g = (int) (g*1.2-25);
				if(g<0) g=0; if(g>255) g = 255;
				b = (int) (b*1.2-25);
				if(b<0) b=0; if(b>255) b = 255;
				
				int d = (a<<24) + (r<<16) + (g<<8) + b;
				newdb.setElem(h+v*width, d);
			}
		}
		
		return newimg;
	}

	
	private BufferedImage LowContrast(BufferedImage srcbi) {
		//処理
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newimg.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcdb.getElem(h+v*width);
				int a = c>>24;
				int r = (c>>16)&0xFF;
				int g = (c>>8)&0xFF;
				int b = (c)&0xFF;
				
				r = (int) (r*0.8)+25;
				g = (int) (g*0.8)+25;
				b = (int) (b*0.8)+25;
				
				int d = (a<<24) + (r<<16) + (g<<8) + b;
				newdb.setElem(h+v*width, d);
			}
		}
		
		return newimg;
	}

	
	private BufferedImage HighSaturation(BufferedImage srcbi) {
		//処理
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newimg.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		float[] hsb = new float[3];
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcdb.getElem(h+v*width);
				int a = c>>24;
				int r = (c>>16)&0xFF;
				int g = (c>>8)&0xFF;
				int b = (c)&0xFF;

				Color.RGBtoHSB(r, g, b, hsb);
				if(hsb[1] > 0.0f) hsb[1] += 0.1f;
				if(hsb[1] > 1.0f) hsb[1] = 1.0f;
				int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
				
				int d = (a<<24) + (0x00FFFFFF&rgb);
				newdb.setElem(h+v*width, d);
			}
		}
		
		return newimg;
	}

	
	private BufferedImage LowSaturation(BufferedImage srcbi) {
		//処理
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newimg.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		float[] hsb = new float[3];
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcdb.getElem(h+v*width);
				int a = c>>24;
				int r = (c>>16)&0xFF;
				int g = (c>>8)&0xFF;
				int b = (c)&0xFF;

				Color.RGBtoHSB(r, g, b, hsb);
				hsb[1] -= 0.1f;
				if(hsb[1] < 0.0f) hsb[1] = 0.0f;
				int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
				
				int d = (a<<24) + (0x00FFFFFF&rgb);
				newdb.setElem(h+v*width, d);
			}
		}
		
		return newimg;
	}

	
	private BufferedImage Grayscale(BufferedImage srcbi) {
		//処理
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
		DataBuffer newdb = newimg.getRaster().getDataBuffer();
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = srcdb.getElem(h+v*width);
				int a = c>>24;
				int d = (int)(0.29891f*((c>>16)&0xFF)+0.58661f*((c>>8)&0xFF)+0.1144f*((c)&0xFF));

				//int d = (((c>>16)&0xFF) + ((c>>8)&0xFF) + ((c)&0xFF))/3;
				
				int cc = (a<<24) + (d<<16) + (d<<8) + d;
				newdb.setElem(h+v*width, cc);
			}
		}
		
		return newimg;
	}

	
	private BufferedImage BinarizationDither(BufferedImage srcbi, int dithermode) {
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);

		if(dithermode==-1){
			//ディザ無し
			DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
			DataBuffer newdb = newimg.getRaster().getDataBuffer();
			int width = srcbi.getWidth();
			int height = srcbi.getHeight();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					int c = srcdb.getElem(h+v*width);
					int a = c>>24;
					//int brightness = (int)(0.33f*((c>>16)&0xFF)+0.33f*((c>>8)&0xFF)+0.33f*((c)&0xFF));
					int brightness = (int)(0.29891f*((c>>16)&0xFF)+0.58661f*((c>>8)&0xFF)+0.1144f*((c)&0xFF));
					int d;
					if(brightness<128) d=0;
					else d = 0xFF;
					
					int cc = (a<<24) + (d<<16) + (d<<8) + d;
					newdb.setElem(h+v*width, cc);
				}
			}

		}
		else if(dithermode == 0){
			//Floyd-Steinburg Algorithm

			DataBuffer newdb = newimg.getRaster().getDataBuffer();
			int width = srcbi.getWidth();
			int height = srcbi.getHeight();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					newdb.setElem(h+v*width, 0);
				}
			}

			DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					int color = srcdb.getElem(h+v*width);
					int alpha = color&0xFF000000;
					
					//明るさを求める
					int brightness = (int)(0.29891f*((color>>16)&0xFF)+0.58661f*((color>>8)&0xFF)+0.1144f*((color)&0xFF));
					//ガンマ調整 brightness = (int) (Math.pow(brightness/256f, 1.8)*256);
					brightness += newdb.getElem(h+v*width);
					
					//ピクセルを白か黒にする
					if(brightness>=128){
						brightness -= 256;
						newdb.setElem(h+v*width, alpha + 0x00FFFFFF);
					}
					else{
						newdb.setElem(h+v*width, alpha + 0x00000000);
					}

					//誤差を拡散する
					if(h+1<width) newdb.setElem(h+1+v*width, newdb.getElem(h+1+v*width)+brightness*7/16);
					if(v+1>=height) continue;
					if(h-1>=0) newdb.setElem(h-1+(v+1)*width, newdb.getElem(h-1+(v+1)*width)+brightness*3/16);
					newdb.setElem(h+(v+1)*width, newdb.getElem(h+(v+1)*width)+brightness*5/16);
					if(h+1<width) newdb.setElem(h+1+(v+1)*width, newdb.getElem(h+1+(v+1)*width)+brightness*1/16);
				}
			}
		}
		else if(dithermode == 1){
			//Bill Atkinson's HyperScan Algorithm
			// http://verlagmartinkoch.at/software/dither/index.html

			DataBuffer newdb = newimg.getRaster().getDataBuffer();
			int width = srcbi.getWidth();
			int height = srcbi.getHeight();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					newdb.setElem(h+v*width, 0);
				}
			}

			DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					int color = srcdb.getElem(h+v*width);
					int alpha = color&0xFF000000;
					
					//明るさを求める
					int brightness = (int)(0.29891f*((color>>16)&0xFF)+0.58661f*((color>>8)&0xFF)+0.1144f*((color)&0xFF));
					//ガンマ調整 brightness = (int) (Math.pow(brightness/256f, 1.8)*256);
					brightness += newdb.getElem(h+v*width);
					
					//ピクセルを白か黒にする
					if(brightness>=128){
						brightness -= 256;
						newdb.setElem(h+v*width, alpha + 0x00FFFFFF);
					}
					else{
						newdb.setElem(h+v*width, alpha + 0x00000000);
					}

					//誤差を拡散する
					int carry = brightness/8;
					if(h+1<width) newdb.setElem(h+1+v*width, newdb.getElem(h+1+v*width)+carry);
					if(h+2<width) newdb.setElem(h+2+v*width, newdb.getElem(h+2+v*width)+carry);
					if(v+1>=height) continue;
					if(h-1>=0) newdb.setElem(h-1+(v+1)*width, newdb.getElem(h-1+(v+1)*width)+carry);
					newdb.setElem(h+(v+1)*width, newdb.getElem(h+(v+1)*width)+carry);
					if(h+1<width) newdb.setElem(h+1+(v+1)*width, newdb.getElem(h+1+(v+1)*width)+carry);
					if(v+2<height) newdb.setElem(h+(v+2)*width, carry);
					
					//誤差を拡散する(Burkes)
					/*int carry = brightness;
					if(h+1<width) newdb.setElem(h+1+v*width, newdb.getElem(h+1+v*width)+carry*4/16);
					if(h+2<width) newdb.setElem(h+2+v*width, newdb.getElem(h+2+v*width)+carry*2/16);
					if(v+1>=height) continue;
					if(h-2>=0) newdb.setElem(h-2+(v+1)*width, newdb.getElem(h-2+(v+1)*width)+carry*1/16);
					if(h-1>=0) newdb.setElem(h-1+(v+1)*width, newdb.getElem(h-1+(v+1)*width)+carry*2/16);
					newdb.setElem(h+(v+1)*width, newdb.getElem(h+(v+1)*width)+carry*4/16);
					if(h+1<width) newdb.setElem(h+1+(v+1)*width, newdb.getElem(h+1+(v+1)*width)+carry*2/16);
					if(h+2<width) newdb.setElem(h+2+(v+1)*width, newdb.getElem(h+2+(v+1)*width)+carry*1/16);
					*/
					//誤差を拡散する(Stucki)
					/*int carry = brightness;
					if(h+1<width) newdb.setElem(h+1+v*width, newdb.getElem(h+1+v*width)+carry*8/42);
					if(h+2<width) newdb.setElem(h+2+v*width, newdb.getElem(h+2+v*width)+carry*4/42);
					if(v+1>=height) continue;
					if(h-2>=0) newdb.setElem(h-2+(v+1)*width, newdb.getElem(h-2+(v+1)*width)+carry*2/42);
					if(h-1>=0) newdb.setElem(h-1+(v+1)*width, newdb.getElem(h-1+(v+1)*width)+carry*4/42);
					newdb.setElem(h+(v+1)*width, newdb.getElem(h+(v+1)*width)+carry*8/42);
					if(h+1<width) newdb.setElem(h+1+(v+1)*width, newdb.getElem(h+1+(v+1)*width)+carry*4/42);
					if(h+2<width) newdb.setElem(h+2+(v+1)*width, newdb.getElem(h+2+(v+1)*width)+carry*2/42);
					if(v+2>=height) continue;
					if(h-2>=0) newdb.setElem(h-2+(v+2)*width, newdb.getElem(h-2+(v+2)*width)+carry*1/42);
					if(h-1>=0) newdb.setElem(h-1+(v+2)*width, newdb.getElem(h-1+(v+2)*width)+carry*2/42);
					newdb.setElem(h+(v+2)*width, newdb.getElem(h+(v+2)*width)+carry*4/42);
					if(h+1<width) newdb.setElem(h+1+(v+2)*width, newdb.getElem(h+1+(v+2)*width)+carry*2/42);
					if(h+2<width) newdb.setElem(h+2+(v+2)*width, newdb.getElem(h+2+(v+2)*width)+carry*1/42);
					*/
					//誤差を拡散する(JaJuNi)
					/*int carry = brightness;
					if(h+1<width) newdb.setElem(h+1+v*width, newdb.getElem(h+1+v*width)+carry*7/48);
					if(h+2<width) newdb.setElem(h+2+v*width, newdb.getElem(h+2+v*width)+carry*5/48);
					if(v+1>=height) continue;
					if(h-2>=0) newdb.setElem(h-2+(v+1)*width, newdb.getElem(h-2+(v+1)*width)+carry*3/48);
					if(h-1>=0) newdb.setElem(h-1+(v+1)*width, newdb.getElem(h-1+(v+1)*width)+carry*5/48);
					newdb.setElem(h+(v+1)*width, newdb.getElem(h+(v+1)*width)+carry*7/48);
					if(h+1<width) newdb.setElem(h+1+(v+1)*width, newdb.getElem(h+1+(v+1)*width)+carry*5/48);
					if(h+2<width) newdb.setElem(h+2+(v+1)*width, newdb.getElem(h+2+(v+1)*width)+carry*3/48);
					if(v+2>=height) continue;
					if(h-2>=0) newdb.setElem(h-2+(v+2)*width, newdb.getElem(h-2+(v+2)*width)+carry*1/48);
					if(h-1>=0) newdb.setElem(h-1+(v+2)*width, newdb.getElem(h-1+(v+2)*width)+carry*3/48);
					newdb.setElem(h+(v+2)*width, newdb.getElem(h+(v+2)*width)+carry*5/48);
					if(h+1<width) newdb.setElem(h+1+(v+2)*width, newdb.getElem(h+1+(v+2)*width)+carry*3/48);
					if(h+2<width) newdb.setElem(h+2+(v+2)*width, newdb.getElem(h+2+(v+2)*width)+carry*1/48);
					*/
					//誤差を拡散する(Sierra 2line)
					/*int carry = brightness;
					if(h+1<width) newdb.setElem(h+1+v*width, newdb.getElem(h+1+v*width)+carry*4/16);
					if(h+2<width) newdb.setElem(h+2+v*width, newdb.getElem(h+2+v*width)+carry*3/16);
					if(v+1>=height) continue;
					if(h-2>=0) newdb.setElem(h-2+(v+1)*width, newdb.getElem(h-2+(v+1)*width)+carry*1/16);
					if(h-1>=0) newdb.setElem(h-1+(v+1)*width, newdb.getElem(h-1+(v+1)*width)+carry*2/16);
					newdb.setElem(h+(v+1)*width, newdb.getElem(h+(v+1)*width)+carry*3/16);
					if(h+1<width) newdb.setElem(h+1+(v+1)*width, newdb.getElem(h+1+(v+1)*width)+carry*2/16);
					if(h+2<width) newdb.setElem(h+2+(v+1)*width, newdb.getElem(h+2+(v+1)*width)+carry*1/16);
					*/
					//誤差を拡散する(Sierra lite)
					/*int carry = brightness;
					if(h+1<width) newdb.setElem(h+1+v*width, newdb.getElem(h+1+v*width)+carry*2/4);
					if(v+1>=height) continue;
					if(h-1>=0) newdb.setElem(h-1+(v+1)*width, newdb.getElem(h-1+(v+1)*width)+carry*1/4);
					newdb.setElem(h+(v+1)*width, newdb.getElem(h+(v+1)*width)+carry*1/4);
					*/
				}
			}
			
		}
		else if(dithermode == 2){
			//パターン処理はJavaのdrawImageに任せる
			byte[] r_a = new byte[]{(byte)0,(byte)254};
			byte[] g_a = new byte[]{(byte)0,(byte)255};
			byte[] b_a = new byte[]{(byte)0,(byte)254};
			
			IndexColorModel colorModel = new IndexColorModel(8, 2, r_a, g_a, b_a);
			BufferedImage ditherimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_BYTE_INDEXED, colorModel);
			ditherimg.createGraphics().drawImage(srcbi, 0, 0, null);
			
			newimg.createGraphics().drawImage(ditherimg, 0, 0, null);
			DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
			DataBuffer newdb = newimg.getRaster().getDataBuffer();
			int width = srcbi.getWidth();
			int height = srcbi.getHeight();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					int a = (srcdb.getElem(h+v*width))&0xFF000000;
					
					int cc = a + newdb.getElem(h+v*width);
					newdb.setElem(h+v*width, cc);
				}
			}
		}
		
		return newimg;
	}

	
	private BufferedImage Blur(BufferedImage bi) {
		//処理
		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi);
		
		//フィルター処理
		final float[] operator={
				0.00f, 0.01f, 0.02f, 0.01f, 0.00f,
				0.01f, 0.03f, 0.08f, 0.03f, 0.01f,
				0.02f, 0.08f, 0.40f, 0.08f, 0.02f,
				0.01f, 0.03f, 0.08f, 0.03f, 0.01f,
				0.00f, 0.01f, 0.02f, 0.01f, 0.00f,
			};
		Kernel blur=new Kernel(5,5,operator);
		ConvolveOp convop=new ConvolveOp(blur,ConvolveOp.EDGE_NO_OP,null);
		BufferedImage bignewimg = convop.filter(bigbi,null);

		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -2,-2, null);

		cleanBigPixel(newimg);
		
		return newimg;
	}

	
	private BufferedImage Sharpen(BufferedImage bi) {
		//処理
		//端の処理をさせるため、画像サイズを大きくする
		BufferedImage bigbi = getBigImage(bi);
		
		//フィルター処理
		final float[] operator={
				-0.00f, -0.01f, -0.02f, -0.01f, -0.00f,
				-0.01f, -0.03f, -0.08f, -0.03f, -0.01f,
				-0.02f, -0.08f,  1.60f, -0.08f, -0.02f,
				-0.01f, -0.03f, -0.08f, -0.03f, -0.01f,
				-0.00f, -0.01f, -0.02f, -0.01f, -0.00f,
			};
		Kernel blur=new Kernel(5,5,operator);
		ConvolveOp convop=new ConvolveOp(blur,ConvolveOp.EDGE_NO_OP,null);
		BufferedImage bignewimg = convop.filter(bigbi,null);

		BufferedImage newimg = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		newimg.createGraphics().drawImage(bignewimg, -2,-2, null);

		cleanBigPixel(newimg);
		
		return newimg;
	}

	class colorCnt{
		public colorCnt(int redmin, int redmax, int greenmin, int greenmax, int bluemin, int bluemax, int cnt) {
			this.redmin = redmin;
			this.redmax = redmax;
			this.greenmin = greenmin;
			this.greenmax = greenmax;
			this.bluemin = bluemin;
			this.bluemax = bluemax;
			this.count = cnt;
		}
		int redmin; //色空間エリア
		int redmax;
		int greenmin;
		int greenmax;
		int bluemin;
		int bluemax;
		float redavr;
		float greenavr;
		float blueavr;
		int reddist;
		int greendist;
		int bluedist;
		int count; //エリア内にあるピクセル数
		
		int[] initArea = new int[6];
		void setInitArea(colorCnt parentCnt){
			initArea[0] = parentCnt.initArea[0];
			initArea[1] = parentCnt.initArea[1];
			initArea[2] = parentCnt.initArea[2];
			initArea[3] = parentCnt.initArea[3];
			initArea[4] = parentCnt.initArea[4];
			initArea[5] = parentCnt.initArea[5];
		}
		
		
		void setDist(int[] countHist, int[] counts, int dithermode, int colorsize, byte[] gradarea){
			int iredmin = -1;
			int iredmax = 0;
			//int average = 0;
			this.redavr = 0;
			float pixtotal = 0;
			for(int i=this.redmin; i<this.redmax; i++){
				//ピクセル数を数える
				countHist[i] = 0;
				for(int g=this.greenmin; g<this.greenmax; g++){
					for(int b=this.bluemin; b<this.bluemax; b++){
						countHist[i] += counts[i*256*64+g*64+b];
					}
				}
				pixtotal += countHist[i];
				this.redavr += i*countHist[i];
				if(countHist[i]>0){
					//ピクセルがあるのでminとmaxに反映
					if(iredmin==-1){
						iredmin = i;
						this.redmin = i;
					}
					iredmax = i+1;
				}
			}
			this.redmax = iredmax;
			this.redavr /= (pixtotal);
			//平均値との乖離の合計を求める
			this.reddist = 0;
			for(int i=this.redmin; i<this.redmax; i++){
				this.reddist += countHist[i]*Math.abs(i-this.redavr)/*/(this.redmax-this.redmin)*/;
			}
			if(this.redmax-this.redmin<=1){
				this.reddist = 0;
			}
			
			int igreenmin = -1;
			int igreenmax = 0;
			this.greenavr = 0;
			pixtotal = 0;
			for(int i=this.greenmin; i<this.greenmax; i++){
				//ピクセル数を数える
				countHist[i] = 0;
				for(int r=this.redmin; r<this.redmax; r++){
					for(int b=this.bluemin; b<this.bluemax; b++){
						countHist[i] += counts[r*256*64+i*64+b];
					}
				}
				pixtotal += countHist[i];
				this.greenavr += i*countHist[i];
				if(countHist[i]>0){
					//ピクセルがあるのでminとmaxに反映
					if(igreenmin==-1){
						igreenmin = i;
						this.greenmin = i;
					}
					igreenmax = i+1;
				}
			}
			this.greenmax = igreenmax;
			this.greenavr /= (pixtotal);
			//平均値との乖離の合計を求める
			this.greendist = 0;
			for(int i=this.greenmin; i<this.greenmax; i++){
				this.greendist += countHist[i]*Math.abs(i-this.greenavr)/*/(this.greenmax-this.greenmin)*/;
			}
			if(this.greenmax-this.greenmin<=1){
				this.greendist = 0;
			}
			this.greendist/=4;
			
			int ibluemin = -1;
			int ibluemax = 0;
			this.blueavr = 0;
			pixtotal = 0;
			for(int i=this.bluemin; i<this.bluemax; i++){
				//ピクセル数を数える
				countHist[i] = 0;
				for(int r=this.redmin; r<this.redmax; r++){
					for(int g=this.greenmin; g<this.greenmax; g++){
						countHist[i] += counts[r*256*64+g*64+i];
					}
				}
				pixtotal += countHist[i];
				this.blueavr += i*countHist[i];
				if(countHist[i]>0){
					//ピクセルがあるのでminとmaxに反映
					if(ibluemin==-1){
						ibluemin = i;
						this.bluemin = i;
					}
					ibluemax = i+1;
				}
			}
			this.bluemax = ibluemax;
			this.blueavr /= (pixtotal);
			//平均値との乖離の合計を求める
			this.bluedist = 0;
			for(int i=this.bluemin; i<this.bluemax; i++){
				this.bluedist += countHist[i]*Math.abs(i-this.blueavr)/*/(this.bluemax-this.bluemin)*/;
			}
			if(this.bluemax-this.bluemin<=1){
				this.bluedist = 0;
			}

			//明るさの変化度合いを調べる
			int countBright = 0;
			if(reddist>this.bluedist){
				//両方とも明るい領域の数
				for(int r=((int)this.redavr+this.redmax)/2; r<this.redmax; r++){
					for(int g=((int)this.greenavr+this.greenmax)/2; g<this.greenmax; g++){
						for(int b=this.bluemin; b<this.bluemax; b++){
							countBright += counts[r*256*64+g*64+b];
						}
					}
				}
				//両方とも暗い領域の数
				for(int r=this.redmin; r<((int)this.redavr+this.redmin)/2; r++){
					for(int g=this.greenmin; g<((int)this.greenavr+this.greenmax)/2; g++){
						for(int b=this.bluemin; b<this.bluemax; b++){
							countBright += counts[r*256*64+g*64+b];
						}
					}
				}
			}
			else{
				//両方とも明るい領域の数
				for(int r=this.redmin; r<this.redmax; r++){
					for(int g=((int)this.greenavr+this.greenmax)/2; g<this.greenmax; g++){
						for(int b=((int)this.blueavr+this.bluemax)/2; b<this.bluemax; b++){
							countBright += counts[r*256*64+g*64+b];
						}
					}
				}
				//両方とも暗い領域の数
				for(int r=this.redmin; r<this.redmax; r++){
					for(int g=this.greenmin; g<((int)this.greenavr+this.greenmax)/2; g++){
						for(int b=this.bluemin; b<((int)this.blueavr+this.bluemin)/2; b++){
							countBright += counts[r*256*64+g*64+b];
						}
					}
				}
			}

			//人間の視覚に合わせる処理

			//全てのdistが大きければ明るさの違い
			//if(this.redavr<58 && (float)this.reddist/this.greendist/2<1.2f && (float)this.bluedist/this.greendist/2<1.2f){

			
			if(countBright>pixtotal*2/16 && (dithermode==-1 && colorsize>16))
			{
				int c565 = (((int)this.redavr>>1)<<11)+(((int)this.greenavr>>2)<<5)+(((int)this.blueavr>>1));
				float grad=1.0f;
				//System.out.println("gradarea[c565]:"+gradarea[c565]);
				if(colorsize>32){
					//グラデーションエリアでは明るさを優先
					if(gradarea[c565]>=64) grad = 1.3f;
					else if(gradarea[c565]>=8) grad = 1.2f;
					else if (gradarea[c565]>=4) grad = 1.1f;
					else grad = 0.9f;
				}
				
				//二つの領域がともに高いところにピクセルが集中していれば明るさの違い
				if(this.greenavr/4>1 && this.greendist>this.reddist/2 && this.greendist>this.bluedist/2 ){
					//緑を強化(緑が明るさに影響大)
					this.reddist *= 1.0/grad;
					if(dithermode!=-1 || colorsize<=16) this.greendist *= 1.2;
					else this.greendist *= (1.4f+colorsize/256.0f)*grad;
					this.bluedist *= 1.0/grad;
				}
				else if(this.reddist>this.bluedist){
					//赤を強化
					if(dithermode!=-1 || colorsize<=16) this.reddist *= 1.2;
					else this.reddist *= (1.4f+colorsize/256.0f)*grad;
					this.greendist *= 1.0/grad;
					this.bluedist *= 1.0/grad;
				}
				else{
					//青を強化
					this.reddist *= 1.0/grad;
					this.greendist *= 1.0/grad;
					if(dithermode!=-1 || colorsize<=16) this.bluedist *= 1.2;
					else this.bluedist *= (1.4f+colorsize/256.0f)*grad;
				}
			}
			
			if(countBright<pixtotal*1/32 && colorsize<24){
				//色数の少ない場合はむしろ色相を優先
				this.reddist *= 1.8;
				this.greendist *= 1.8;
				this.bluedist *= 1.8;
			}
			
			if( this.blueavr+this.greenavr/4+this.redavr<60 && this.blueavr>this.greenavr/4*1.5 && this.blueavr>this.redavr*1.5 ){
				//暗くて青が大
				this.reddist *= 0.8;
				this.greendist *= 0.6;
			}
			
			if( this.redavr>this.blueavr*2 && this.redavr>this.greenavr/4*2*1.2 ){
				//赤が大で他が弱い場合は他の変化を無視
				this.reddist *= 1.0;
				this.greendist *= 0.8;
				this.bluedist *= 0.2;
			}
			if( this.greenavr/4*1.2>this.blueavr*2 && this.greenavr/4*1.2>this.redavr*2 ){
				//緑が大で他が弱い場合は他の変化を無視
				this.greendist *= 1.1;
				this.reddist *= 0.2;
				this.bluedist *= 0.2;
			}
			if( this.blueavr>this.redavr*2 && this.blueavr>this.greenavr/4*1.2 ){
				//青が大で他が弱い場合は他の変化を無視、やっぱり青の変化を無視
				this.reddist *= 1;
				this.greendist *= 1;
				this.bluedist *= 0.8;
			}

			if( (this.greenmax/4>48 && this.redmax>54) && this.blueavr<32 ){
				//赤and緑が大で青が弱い場合は青の変化を無視
				this.bluedist *= 0.1;
				this.greendist *= 0.8;
				this.reddist *= 0.7;
			}
			else if( (this.greenmax/4>48 || this.redmax>54) && this.blueavr<32 ){
				//赤or緑が大で青が弱い場合は青の変化を無視
				this.bluedist *= 0.3;
				this.greendist *= 1.0;
				this.reddist *= 0.9;
			}
			else if( (this.greenmax/4>36 && this.bluemax>54) && this.redavr<32 ){
				//緑and青が大で赤が弱い場合は赤の変化を無視
				this.reddist *= 0.3;
				this.greendist *= 1.0;
				this.bluedist *= 0.9;
			}
			else if( (this.redmax>48 && this.redmax>54) && this.greenavr/4<12 ){
				//赤and青が大で緑が弱い場合は緑の変化を無視
				this.greendist *= 0.50;
			}
			
			if(this.redavr+this.greenavr/4+this.blueavr >= 188){
				//白色エリア
				this.reddist = this.reddist*3/4;
				this.greendist = this.greendist*3/4;
				this.bluedist = this.bluedist*3/4;
			}
			else if( this.redavr>this.greenavr/4+5 && this.greenavr/4>this.blueavr+5 && 
					this.greenavr/4*1.5 >= this.redavr && this.blueavr*2 >= this.greenavr/4){
				//肌色エリア
				//this.reddist *= 1.3;
				this.greendist *= 1.3;
				//this.bluedist *= 1.3;
			}
			else if( (this.redmin>48 && this.redavr<=55 || this.bluemin>48 && this.blueavr<=55)  &&
					(this.greendist>this.reddist) && (this.greendist>this.bluedist) ){
				//緑の変化大
				this.greendist *= 1.5;
			}
			else if( this.greenavr/4>54 && this.greenmin/2>this.bluemax && this.greenmin/4>this.redmax && (this.greenmax/4-this.greenmin/4<=8) ){
				//緑が大で緑が狭い
				this.greendist = this.greendist*3/4;
			}
			else if(this.redmax+this.greenmax/4+this.bluemax <= 32){
				//暗色エリア
				if(this.reddist>this.greendist/2) {
					this.reddist *= 0.6;
					this.greendist *= 0.1;
					this.bluedist *= 0.05;
				}
				else if(this.bluedist>this.greendist/2) {
					this.reddist *= 0.1;
					this.greendist *= 0.05;
					this.bluedist *= 0.6;
				}
				else {
					this.reddist *= 0.1;
					this.greendist *= 0.6;
					this.bluedist *= 0.05;
				}
				if(this.redmax+this.greenmax/4+this.bluemax <= 16){
					//かなり暗色エリアの場合はさらに半分
					this.reddist *= 0.5;
					this.greendist *= 0.5;
					this.bluedist *= 0.5;
				}
			}
			else if(this.redmax+this.greenmax/4+this.bluemax <= 64){
				//やや暗色エリア
				//this.reddist *= 0.9;
				//this.greendist *= 0.9;
				//this.bluedist *= 0.8;
			}
		}
	}
	
	private BufferedImage IndexColor(BufferedImage srcbi, 
			int colors, int dithermode)
	{
		//6+8+6の20bitに変換
		int width = srcbi.getWidth();
		int height = srcbi.getHeight();
		BufferedImage minibi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		

		DataBuffer db = srcbi.getRaster().getDataBuffer();
		DataBuffer minidb = minibi.getRaster().getDataBuffer();
		for(int v=0; v<height; v++){
			for(int h=0; h<width; h++){
				int c = db.getElem(h+v*width);
				int r = (c>>16)&0xFF;
				int g = (c>>8)&0xFF;
				int b = (c>>0)&0xFF;
				minidb.setElem(h+v*width, ((r/4)<<14)+((g)<<6)+((b/4)));
			}
		}
		
		int[] counts = new int[1<<20];
		for(int i=0; i<counts.length; i++){
			counts[i] = 0;
		}

		byte[] gradarea = new byte[1<<16];//5,6,5でグラデーションのエリアを記憶
		
		//含まれる色をカウント
		//DataBuffer db = srcbi.getRaster().getDataBuffer();
		//DataBuffer minidb = minibi.getRaster().getDataBuffer();
		int[] grad_d = new int[8];
		int cnt_renzoku;
		for(int v=0; v<height; v++){
			cnt_renzoku = 0;
			for(int h=0; h<width; h++){
				int orgd = db.getElem(h+v*width);
				if(((orgd>>24)&0xFF)==0) continue;//透明色は含まない
				int d = minidb.getElem(h+v*width);
				grad_d[cnt_renzoku%8] = d;
				counts[d]+=1;
				int r = ((d>>14)&0x3F);
				int g = ((d>>6)&0xFF);
				int b = ((d)&0x3F);
				if(r>=54 && r<g/4+24 && r>g/4+4 &&g/4<b+16 && g/4>b+2){
					//肌色エリア
					counts[d]+=1;
				}
				if((colors==-1 || colors >= 32) && h-1>=0 && v-1>=0 && /*r+g+b<364 &&*/ r+g+b>24){
					//面
					int dd1 = minidb.getElem(h-1+v*width);
					int dd2 = minidb.getElem(h+(v-1)*width);
					int cnt = 0;
					if(Math.abs(r - ((dd1>>14)&0x3F))<=3 && Math.abs(r - ((dd2>>14)&0x3F))<=3){
						cnt++;
					}
					if(Math.abs(g - ((dd1>>6)&0xFF))<=15 && Math.abs(g - ((dd2>>6)&0xFF))<=15 )
					{
						cnt++;
					}
					if(Math.abs(b - ((dd1)&0x3F))<=3 && Math.abs(b - ((dd2)&0x3F))<=3){
						cnt++;
					}
					if(cnt==3){
						counts[d]+=1;
						cnt_renzoku++;
						if(cnt_renzoku>=2 && h-cnt_renzoku>=0){
							int dd3 = minidb.getElem(h-cnt_renzoku+v*width);
							//グラデーションの方向が変わっていないか
							int houkou = 0;
							if(((r-((dd1>>14)&0x3F))>0) == ((((dd1>>14)&0x3F)-((dd3>>14)&0x3F))<0)){
								houkou++;
							}
							if(((g-((dd1>>6)&0xFF))>0) == ((((dd1>>6)&0xFF)-((dd3>>6)&0xFF))<0)){
								houkou++;
							}
							if(((b-((dd1)&0x3F))>0) == ((((dd1)&0x3F)-((dd3)&0x3F))<0)){
								houkou++;
							}
							if(houkou>=2){
								cnt_renzoku = 0;
							}
						}
						if(cnt_renzoku>=8){
							//8個以上連続して類似した色が続くとより優先度を高くする
							int add = 1;
							
							int dd3 = minidb.getElem(h-cnt_renzoku+v*width);
							if((r-((dd3>>14)&0x3F))<=3 && (g-((dd3>>6)&0xFF))<=7 && (b-((dd3)&0x3F))<=3){
								//微妙な差の場合はむしろ量子化誤差かもしれない
								add = -1;
							}
							
							for(int k=0; k<8; k++){
								int ddr = ((grad_d[k]>>14)&0x3F)>>1;
								int ddg = ((grad_d[k]>>6)&0xFF)>>2;
								int ddb = ((grad_d[k])&0x3F)>>1;
								//counts[grad_d[k]]+=8;
								if(gradarea[((ddr)<<11)+((ddg)<<5)+(ddb)]<64){
									gradarea[((ddr)<<11)+((ddg)<<5)+(ddb)]+=add;
								}
							}
							cnt_renzoku = 0;
						}
					}
					else cnt_renzoku = 0;
				}
			}
		}

		//縦向きに走査してグラデーションを調べる
		if(colors==-1 || colors >= 128){
			for(int h=0; h<width; h+=2){
				cnt_renzoku = 0;
				for(int v=0; v<height; v++){
					int orgd = db.getElem(h+v*width);
					if(((orgd>>24)&0xFF)==0) continue;//透明色は含まない
					int d = minidb.getElem(h+v*width);
					grad_d[cnt_renzoku%8] = d;
					int r = ((d>>14)&0x3F);
					int g = ((d>>6)&0xFF);
					int b = ((d)&0x3F);
					if((colors==-1 || colors >= 32) && v-1>=0 && /*r+g+b<364 &&*/ r+g+b>24){
						//面
						int dd1 = minidb.getElem(h+(v-1)*width);
						int cnt = 0;
						if(Math.abs(r - ((dd1>>14)&0x3F))<=3){
							cnt++;
						}
						if(Math.abs(g - ((dd1>>6)&0xFF))<=15)
						{
							cnt++;
						}
						if(Math.abs(b - ((dd1)&0x3F))<=3){
							cnt++;
						}
						if(cnt==3){
							cnt_renzoku++;
							if(cnt_renzoku>=2 && v-cnt_renzoku>=0){
								int dd3 = minidb.getElem(h+(v-cnt_renzoku)*width);
								//グラデーションの方向が変わっていないか
								int houkou = 0;
								if(((r-((dd1>>14)&0x3F))>0) == ((((dd1>>14)&0x3F)-((dd3>>14)&0x3F))<0)){
									houkou++;
								}
								if(((g-((dd1>>6)&0xFF))>0) == ((((dd1>>6)&0xFF)-((dd3>>6)&0xFF))<0)){
									houkou++;
								}
								if(((b-((dd1)&0x3F))>0) == ((((dd1)&0x3F)-((dd3)&0x3F))<0)){
									houkou++;
								}
								if(houkou>=2){
									cnt_renzoku = 0;
								}
							}
							if(cnt_renzoku>=8){
								//8個以上連続して類似した色が続くとより優先度を高くする
								int add = 1;

								int dd3 = minidb.getElem(h+(v-cnt_renzoku)*width);
								if((r-((dd3>>14)&0x3F))<=3 && (g-((dd3>>6)&0xFF))<=7 && (b-((dd3)&0x3F))<=3){
									//微妙な差の場合はむしろ量子化誤差かもしれない
									add = -1;
								}
								
								for(int k=0; k<8; k++){
									//counts[grad_d[k]]+=8;
									int ddr = ((grad_d[k]>>14)&0x3F)>>1;
									int ddg = ((grad_d[k]>>6)&0xFF)>>2;
									int ddb = ((grad_d[k])&0x3F)>>1;
									if(gradarea[((ddr)<<11)+((ddg)<<5)+(ddb)]>0 && gradarea[((ddr)<<11)+((ddg)<<5)+(ddb)]<127){
										gradarea[((ddr)<<11)+((ddg)<<5)+(ddb)]+=add;
									}
									else if(gradarea[((ddr)<<11)+((ddg)<<5)+(ddb)]>-128){
										gradarea[((ddr)<<11)+((ddg)<<5)+(ddb)]--;
									}
								}
								cnt_renzoku = 0;
							}
						}
						else cnt_renzoku = 0;
					}
				}
			}
		}
		
		boolean autoColors = false;
		if(colors<0){
			colors = 256;
			autoColors = true;
		}
		
		//カラーの配置を分析し、どこかで分ける
		ArrayList<colorCnt> colorList = new ArrayList<colorCnt>();
		int total = 0;
		for(int i=0; i<counts.length; i++){
			total += counts[i];
		}
		if(total>=1000*1000){
			for(int i=0; i<counts.length; i++){
				counts[i] = (counts[i]+9)/10;
			}
		}
		int[] countHist = new int[256];
		
		colorList.add(new colorCnt(0,64,0,256,0,64,total));
		colorList.get(0).initArea[0] = 0;
		colorList.get(0).initArea[1] = 64;
		colorList.get(0).initArea[2] = 0;
		colorList.get(0).initArea[3] = 256;
		colorList.get(0).initArea[4] = 0;
		colorList.get(0).initArea[5] = 64;
		//最初のエリアの範囲を締める
		colorList.get(0).setDist(countHist, counts, dithermode, colorList.size(), gradarea);
		
		while(colorList.size()<colors){
			//色空間をメディアンカット法で切り分けて行く
			
			//分割対象のエリアを決める。範囲が広くピクセルの多いエリア。
			float maxdist = 0;
			colorCnt maxarea = null;
			for(int i=0; i<colorList.size(); i++){
				colorCnt colarea = colorList.get(i);
				float dist = ((float)colarea.reddist+1)+
							((float)colarea.greendist+1)+
								((float)colarea.bluedist+1);
				//float dist = Math.max(((float)colarea.reddist+1)/**(colarea.redmax-colarea.redmin)*//**(colarea.redmax-colarea.redmin)*/,
				//		Math.max(((float)colarea.greendist+1)/**(colarea.greenmax-colarea.greenmin)/2*//**(colarea.greenmax-colarea.greenmin)/2*/,
				//				((float)colarea.bluedist+1)/**(colarea.bluemax-colarea.bluemin)*//**(colarea.bluemax-colarea.bluemin)*/));
				dist = (dist)*(colarea.count+(float)total/colorList.size()*100);
				
				if(dist>=maxdist){
					maxdist = dist;
					maxarea = colarea;
				}
			}
			
			if(maxarea==null){
				maxarea = colorList.get(0);
			}

			/*System.out.println("select area!  count:"+maxarea.count);
			System.out.println("redmin:"+maxarea.redmin+" redmax:"+maxarea.redmax
					+" greenmin:"+maxarea.greenmin+" greenmax:"+maxarea.greenmax
					+" bluemin:"+maxarea.bluemin+" bluemax:"+maxarea.bluemax);
			System.out.println("reddist:"+maxarea.reddist
					+" greendist:"+maxarea.greendist
					+" bluedist:"+maxarea.bluedist);*/
			
			//分割方向(r,g,b)を決める ピクセルがその色方向に偏っていてピクセルを多く分割できる位置
			if(maxarea.reddist==0 && maxarea.greendist==0 && maxarea.bluedist==0){
				//分割不可能
				if(maxarea.count==-10000){
					break;
				}
				maxarea.count = -10000;
				continue;
			}
			
			if(autoColors && (colorList.size()==2 || colorList.size()==4 || colorList.size()==8 || colorList.size()==16 || colorList.size()==32 || colorList.size()==64 || colorList.size()==128)){
				if(maxarea.reddist+ maxarea.greendist+maxarea.bluedist<=total/500){
					break;
				}
			}
			
			colorCnt newarea;
			newarea = new colorCnt(maxarea.redmin, maxarea.redmax, maxarea.greenmin, maxarea.greenmax, maxarea.bluemin, maxarea.bluemax, 0);

			if(maxarea.reddist>=maxarea.greendist && maxarea.reddist>=maxarea.bluedist){
				//赤を分割
				
				//分割に都合が良い場所を求める。
				
				//ヒストグラムを求める
				float average=0;
				int counttotal=0;
				for(int r=maxarea.redmin; r<maxarea.redmax; r++){
					countHist[r] = 0;
					for(int g=newarea.greenmin; g<newarea.greenmax; g++){
						for(int b=newarea.bluemin; b<newarea.bluemax; b++){
							countHist[r] += counts[r*256*64+g*64+b];
						}
					}
					average += r*countHist[r];
					counttotal += countHist[r];
				}
				average/=counttotal;
				int areawidth = (maxarea.redmax-maxarea.redmin)*4;
				if(areawidth>32){
					//ヒストグラムをぼかす
					for(int j=0; j<areawidth/32; j++){
						for(int i=newarea.redmin+1; i<newarea.redmax-1; i++){
							countHist[i] = (countHist[i-1]+2*countHist[i]+countHist[i+1])/4;
						}
					}
				}
				//System.out.println("average"+average+"    counttotal:"+counttotal);
				
				//平均値に近くてピクセルの少ないところを探す
				float maxrank = -1000000;
				for(int i=maxarea.redmin+1; i<maxarea.redmax; i++){
					float rank = (((float)counttotal)/(countHist[i]+1*counttotal/areawidth)-(5+32.0f/areawidth)*Math.abs(average-i));
					//System.out.println("rank"+rank+"    "+countHist[i]);
					if(rank>=maxrank){
						int splitcount = 0;
						for(int j=maxarea.redmin; j<=i; j++){
							splitcount += countHist[j];
						}
						if(splitcount>maxarea.count*2/8 && splitcount<maxarea.count*6/8){
							//分割
							maxrank = rank;
							newarea.redmin = i;
						}
					}
				}
				if(maxrank == -1000000) newarea.redmin = (int)average+1;
				maxarea.redmax = newarea.redmin;
				
				newarea.setInitArea(maxarea); //初期の色空間を記憶
				newarea.initArea[0] = newarea.redmin;
				maxarea.initArea[1] = maxarea.redmax;
			}
			else if(maxarea.greendist>=maxarea.reddist && maxarea.greendist>=maxarea.bluedist){
				//緑を分割
				
				//分割に都合が良い場所を求める。
				
				//ヒストグラムを求める
				float average=0;
				int counttotal=0;
				for(int g=maxarea.greenmin; g<maxarea.greenmax; g++){
					countHist[g] = 0;
					for(int r=newarea.redmin; r<newarea.redmax; r++){
						for(int b=newarea.bluemin; b<newarea.bluemax; b++){
							countHist[g] += counts[r*256*64+g*64+b];
						}
					}
					average += g*countHist[g];
					counttotal += countHist[g];
				}
				average/=counttotal;
				int areawidth = (maxarea.greenmax-maxarea.greenmin)*1;
				if(areawidth>32){
					//ヒストグラムをぼかす
					for(int j=0; j<areawidth/32/4; j++){
						for(int i=newarea.greenmin+1; i<newarea.greenmax-1; i++){
							countHist[i] = (countHist[i-1]+2*countHist[i]+countHist[i+1])/4;
						}
					}
				}
				//System.out.println("average"+average+"    counttotal:"+counttotal);
				
				//平均値に近くてピクセルの少ないところを探す
				float maxrank = -1000000;
				for(int i=maxarea.greenmin+1; i<maxarea.greenmax; i++){
					float rank = (((float)counttotal)/(countHist[i]+1*counttotal/areawidth)-(5+32.0f/areawidth)*Math.abs(average-i));
					//System.out.println("rank"+rank+"    "+countHist[i]);
					if(rank>=maxrank){
						int splitcount = 0;
						for(int j=maxarea.greenmin; j<=i; j++){
							splitcount += countHist[j];
						}
						if(splitcount>maxarea.count*2/8 && splitcount<maxarea.count*6/8){
							//分割
							maxrank = rank;
							newarea.greenmin = i;
						}
					}
				}
				if(maxrank == -1000000) newarea.greenmin = (int)average+1;
				maxarea.greenmax = newarea.greenmin;
				
				newarea.setInitArea(maxarea); //初期の色空間を記憶
				newarea.initArea[2] = newarea.greenmin;
				maxarea.initArea[3] = maxarea.greenmax;
			}
			else{
				//青を分割
				
				//分割に都合が良い場所を求める。
				
				//ヒストグラムを求める
				float average=0;
				int counttotal=0;
				for(int b=maxarea.bluemin; b<maxarea.bluemax; b++){
					countHist[b] = 0;
					for(int r=newarea.redmin; r<newarea.redmax; r++){
						for(int g=newarea.greenmin; g<newarea.greenmax; g++){
							countHist[b] += counts[r*256*64+g*64+b];
						}
					}
					average += b*countHist[b];
					counttotal += countHist[b];
				}
				average/=counttotal;
				int areawidth = (maxarea.bluemax-maxarea.bluemin)*4;
				if(areawidth>32){
					//ヒストグラムをぼかす
					for(int j=0; j<areawidth/32; j++){
						if(j%2==0){
							for(int i=newarea.greenmin+1; i<newarea.greenmax-1; i++){
								countHist[i] = (countHist[i-1]+2*countHist[i]+countHist[i+1])/4;
							}
						}else{
							for(int i=newarea.greenmax-2; i>=newarea.greenmin+1; i--){
								countHist[i] = (countHist[i-1]+2*countHist[i]+countHist[i+1])/4;
							}
						}
					}
				}
				//System.out.println("average"+average+"    counttotal:"+counttotal);

				//平均値に近くてピクセルの少ないところを探す
				float maxrank = -1000000;
				for(int i=maxarea.bluemin+1; i<maxarea.bluemax; i++){
					float rank = (((float)counttotal)/(countHist[i]+1*counttotal/areawidth)-(5+32.0f/areawidth)*Math.abs(average-i));
					//System.out.println("rank"+rank+"    "+countHist[i]);
					if(rank>=maxrank){
						int splitcount = 0;
						for(int j=maxarea.bluemin; j<=i; j++){
							splitcount += countHist[j];
						}
						if(splitcount>maxarea.count*2/8 && splitcount<maxarea.count*6/8){
							//分割
							maxrank = rank;
							newarea.bluemin = i;
						}
					}
				}
				if(maxrank == -1000000) newarea.bluemin = (int)average+1;
				maxarea.bluemax = newarea.bluemin;
				
				newarea.setInitArea(maxarea); //初期の色空間を記憶
				newarea.initArea[4] = newarea.bluemin;
				maxarea.initArea[5] = maxarea.bluemax;
			}
			
			
			//新しい領域のピクセル数を数える
			int count = 0;
			for(int r=newarea.redmin; r<newarea.redmax; r++){
				for(int g=newarea.greenmin; g<newarea.greenmax; g++){
					for(int b=newarea.bluemin; b<newarea.bluemax; b++){
						count += counts[r*256*64+g*64+b];
					}
				}
			}
			newarea.count = count;
			
			maxarea.count = 0;
			for(int r=maxarea.redmin; r<maxarea.redmax; r++){
				for(int g=maxarea.greenmin; g<maxarea.greenmax; g++){
					for(int b=maxarea.bluemin; b<maxarea.bluemax; b++){
						maxarea.count += counts[r*256*64+g*64+b];
					}
				}
			}
			
			//範囲を締める
			newarea.setDist(countHist, counts, dithermode, colorList.size(), gradarea);
			maxarea.setDist(countHist, counts, dithermode, colorList.size(), gradarea);
			
			//リストに追加
			colorList.add(newarea);
			
			/*System.out.println("newarea.count:"+newarea.count);
			System.out.println(" redmin:"+newarea.redmin+" redmax:"+newarea.redmax
					+" greenmin:"+newarea.greenmin+" greenmax:"+newarea.greenmax
					+" bluemin:"+newarea.bluemin+" bluemax:"+newarea.bluemax);
			System.out.println(" reddist:"+newarea.reddist
					+" greendist:"+newarea.greendist
					+" bluedist:"+newarea.bluedist);

			System.out.println("maxarea.count:"+maxarea.count);
			System.out.println(" redmin:"+maxarea.redmin+" redmax:"+maxarea.redmax
					+" greenmin:"+maxarea.greenmin+" greenmax:"+maxarea.greenmax
					+" bluemin:"+maxarea.bluemin+" bluemax:"+maxarea.bluemax);
			System.out.println(" reddist:"+maxarea.reddist
					+" greendist:"+maxarea.greendist
					+" bluedist:"+maxarea.bluedist);*/
		}

		Color[] srcColorBest = new Color[colors];
		
		if(dithermode==-1){
			//色を決める。エリア内のピクセルの色の平均値 ピクセルの差が十分にあれば代表値
			for(int i=0; i<colorList.size(); i++){
				int red = 0;
				int green = 0;
				int blue = 0;
				int totalpix = 0;
				int maxpix = 0;
				int maxrgb = 0;

				colorCnt area = colorList.get(i);
				for(int r=area.redmin; r<area.redmax; r++){
					for(int g=area.greenmin; g<area.greenmax; g++){
						for(int b=area.bluemin; b<area.bluemax; b++){
							int c = counts[r*256*64+g*64+b];
							if(c==0) continue;
							/*if(r>=28*2 && r<g/2+12*2 && r>g/2+2*2 &&g/2<b+8*2 && g/2>b+1*2){
								//肌色エリア  はここではなく最初のピクセル数を数えるときに考慮する
								c *= 2;
							}*/
							red += r*c;
							green += g*c;
							blue += b*c;
							totalpix += c;
							if(c>maxpix){
								maxpix = c;
								maxrgb = r*256*64+g*64+b;
							}
						}
					}
				}
				if(totalpix==0) totalpix = 1;

				if(maxpix >= totalpix/2){
					//代表値
					srcColorBest[i] = new Color((maxrgb>>14)*255/63, ((maxrgb>>6)&0xFF)*255/255, (maxrgb&0x3F)*255/63);
				}
				else{
					//平均値
					srcColorBest[i] = new Color(red/totalpix*255/63, green/totalpix*255/255, blue/totalpix*255/63);
				}
				
				//srcColorBest[i] = new Color(i, (i*4)%256, (i*16)%256); //indexで色分け
			}
			
			//色空間にどの色に近いのかを書き込む
			short[] colorAry = new short[1<<20];
			for(int i=0; i<colorAry.length; i++){
				colorAry[i] = -1;
			}
			for(short i=0; i<colorList.size(); i++){
				colorCnt area = colorList.get(i);
				for(int r=area.redmin; r<area.redmax; r++){
					for(int g=area.greenmin; g<area.greenmax; g++){
						for(int b=area.bluemin; b<area.bluemax; b++){
							colorAry[r*256*64+g*64+b] = i;
						}
					}
				}
			}
			
			//元画像を指定色のみに変換する
			width = srcbi.getWidth();
			height = srcbi.getHeight();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					int cc = db.getElem(h+v*width);
					if((cc&0xFF000000)==0){
						continue;
					}
					int c = minidb.getElem(h+v*width)&0x000FFFFF;
					if(/*colorList.size()>32 &&*/ colorAry[c]!=-1){
						Color newcolor = srcColorBest[colorAry[c]];
						int d = (cc&0xFF000000) + (newcolor.getRed()<<16) + (newcolor.getGreen()<<8) +(newcolor.getBlue());
						db.setElem(h+v*width, d);
						continue;
					}
					boolean isFound = false;
					int nearoffset = 0;
					while(!isFound){
						for(int i=0; i<srcColorBest.length; i++){
							if(srcColorBest[i]==null) continue;
							if(isNear(cc, srcColorBest[i], nearoffset)){
								Color newcolor = srcColorBest[i];
								int d = (cc&0xFF000000) + (newcolor.getRed()<<16) + (newcolor.getGreen()<<8) +(newcolor.getBlue());
								db.setElem(h+v*width, d);
								isFound = true;
								break;
							}
						}
						nearoffset += nearoffset/2+1;
					}
				}
			}
			
			//明るさソート
			for(int i=0; i<srcColorBest.length-1; i++){
				if(srcColorBest[i]==null) continue;
				for(int j=i+1; j<srcColorBest.length; j++){
					if(srcColorBest[j]==null) continue;
					if(srcColorBest[i].getRed()+srcColorBest[i].getGreen()+srcColorBest[i].getBlue()
							<srcColorBest[j].getRed()+srcColorBest[j].getGreen()+srcColorBest[j].getBlue()){
						Color c = srcColorBest[i];
						srcColorBest[i] = srcColorBest[j];
						srcColorBest[j] = c;
					}
				}
			}
			
			//パレット表示
			/*for(int y=0; y<8; y++){
				for(int x=0; x<srcColorBest.length/8; x++){
					if(srcColorBest[x+y*srcColorBest.length/8]==null) continue;
					int d = 0xFF000000+(srcColorBest[x+y*srcColorBest.length/8].getRed()<<16)+(srcColorBest[x+y*srcColorBest.length/8].getGreen()<<8)+(srcColorBest[x+y*srcColorBest.length/8].getBlue()<<0);
					for(int i=0; i<4; i++){
						for(int j=0; j<4; j++){
							db.setElem(x*4+i+(y*4+j)*width, d);
						}
					}
				}
			}*/

			/*for(int i=0; i<srcColorBest.length-1; i++)
			{
				Color color1 = srcColorBest[i];
				Color color2 = srcColorBest[i+1];
				float[] hsb1 = Color.RGBtoHSB(color1.getRed(), color1.getGreen(), color1.getBlue(), null);
				float[] hsb2 = Color.RGBtoHSB(color2.getRed(), color2.getGreen(), color2.getBlue(), null);
	

				System.out.println("srcColorBest "+i+":"+color1.getRed()+","+color1.getGreen()+","+color1.getBlue());
				System.out.println("hsb "+i+":"+hsb1[0]+","+hsb1[1]+","+hsb1[2]);

				float d = 4*Math.abs(hsb1[0] - hsb2[0])*Math.max(0.0f, hsb1[1]+hsb2[1]-0.05f)*Math.max(0.0f, Math.max(hsb1[2]+hsb2[2]-0.3f, (hsb1[2]+hsb2[2])/4-0.1f))
				+ 2*Math.abs(hsb1[1] - hsb2[1])*Math.max(hsb1[2]+hsb2[2]-0.3f, (hsb1[2]+hsb2[2])/4)
				+ 4*Math.abs(hsb1[2] - hsb2[2]);
				
				System.out.println("d "+i+":"+d+"="+4*Math.abs(hsb1[0] - hsb2[0])*Math.max(0.0f, hsb1[1]+hsb2[1]-0.05f)*Math.max(0.0f, Math.max(hsb1[2]+hsb2[2]-0.3f, (hsb1[2]+hsb2[2])/4-0.1f))
						+" + "+2*Math.abs(hsb1[1] - hsb2[1])*Math.max(hsb1[2]+hsb2[2]-0.3f, (hsb1[2]+hsb2[2])/4)
						+" + "+4*Math.abs(hsb1[2] - hsb2[2]));
			}*/
			
			return srcbi;
		}

		/////////////////////
		//  ディザを使用する場合
		/////////////////////
		
		
		//色を決める。エリア内のピクセルの色の平均値 ピクセルの差が十分にあれば代表値
		for(int i=0; i<colorList.size(); i++){
			double red = 0;
			double green = 0;
			double blue = 0;
			double totalpix = 0;
			int maxpix = 0;
			int maxrgb = 0;

			colorCnt area = colorList.get(i);
			for(int r=area.redmin; r<area.redmax; r++){
				for(int g=area.greenmin; g<area.greenmax; g++){
					for(int b=area.bluemin; b<area.bluemax; b++){
						int c = counts[r*256*64+g*64+b];
						if(c==0) continue;
						red += r*c;
						green += g*c;
						blue += b*c;
						totalpix += c;
						if(c>maxpix){
							maxpix = c;
							maxrgb = r*256*64+g*64+b;
						}
					}
				}
			}
			if(totalpix==0) totalpix = 1;

			if(maxpix >= totalpix*3/4){
				//代表値
				srcColorBest[i] = new Color((int)((0x003F&(maxrgb>>14))*255/63), ((maxrgb>>6)&0x00FF)*255/255, (maxrgb&0x003F)*255/63);
			}
			else{
				//平均値を基本にする
				int cred = ((int)(red/totalpix)*255/63);
				int cgreen = ((int)(green/totalpix)*255/255);
				int cblue = ((int)(blue/totalpix)*255/63);
				
				//全空間でrgbの彩度が最も高い場合はそれを採用する
				int minbright = 1000;
				int maxbright = 0;
				int redsat = -255;
				int greensat = -255;
				int bluesat = -255;
				int rgsat = -255;
				int gbsat = -255;
				int rbsat = -255;
				for(int j=0; j<colorList.size(); j++){
					colorCnt aarea = colorList.get(j);
					if(i==j) continue;
					if(aarea.redavr+aarea.greenavr/4+aarea.blueavr<minbright) {
						minbright = (int)(aarea.redavr+aarea.greenavr/4+aarea.blueavr);
					}
					if(aarea.redavr+aarea.greenavr/4+aarea.blueavr>maxbright) {
						maxbright = (int)(aarea.redavr+aarea.greenavr/4+aarea.blueavr);
					}
					if(aarea.redmax-Math.min(aarea.greenmax/4,aarea.bluemax)>redsat) {
						redsat = aarea.redmax-Math.min(aarea.greenmax/4,aarea.bluemax);
					}
					if(aarea.greenmax/4-Math.min(aarea.redmax,aarea.bluemax)>greensat) {
						greensat = aarea.greenmax/4-Math.min(aarea.redmax,aarea.bluemax);
					}
					if(aarea.bluemax-Math.min(aarea.greenmax/4,aarea.redmax)>bluesat) {
						bluesat = aarea.bluemax-Math.min(aarea.greenmax/4,aarea.redmax);
					}
					if(Math.min(aarea.redmax,aarea.greenmax/4)-aarea.bluemax>rgsat) {
						rgsat = Math.min(aarea.redmax,aarea.greenmax/4)-aarea.bluemax;
					}
					if(Math.min(aarea.bluemax,aarea.greenmax/4)-aarea.redmax>gbsat) {
						gbsat = Math.min(aarea.bluemax,aarea.greenmax/4)-aarea.redmax;
					}
					if(Math.min(aarea.redmax,aarea.bluemax)-aarea.greenmax/4>rbsat) {
						rbsat = Math.min(aarea.redmax,aarea.bluemax)-aarea.greenmax/4;
					}
				}
				if(area.redavr+area.greenavr/4+area.blueavr<minbright) {
					//明るい
					cred = (cred*3+area.redmin*255/63)/4;
					cgreen = (cgreen*3+area.greenmin*255/255)/4;
					cblue = (cblue*3+area.bluemin*255/63)/4;
				}
				else if(area.redavr+area.greenavr/4+area.blueavr>maxbright) {
					//暗い
					cred = (cred*3+area.redmax*255/63)/4;
					cgreen = (cgreen*3+area.greenmax*255/255)/4;
					cblue = (cblue*3+area.bluemax*255/63)/4;
				}
				else if(Math.min(area.redmax,area.bluemax)-area.greenmax/4>rbsat){
					cred = (cred*3+area.redmax*255/63)/4;
					cblue = (cblue*3+area.bluemax*255/63)/4;
				}
				else if(Math.min(area.redmax,area.greenmax/4)-area.bluemax>rgsat){
					cred = (cred*3+area.redmax*255/63)/4;
					cgreen = (cgreen*3+area.greenmax*255/255)/4;
				}
				else if(Math.min(area.bluemax,area.greenmax/4)-area.redmax>gbsat){
					cgreen = (cgreen*3+area.greenmax*255/255)/4;
					cblue = (cblue*3+area.bluemax*255/63)/4;
				}
				else if(area.redmax-Math.min(area.greenmax/4,area.bluemax)>redsat){
					cred = (cred*3+area.redmax*255/63)/4;
				}
				else if(area.greenmax/4-Math.min(area.redmax,area.bluemax)>greensat){
					cgreen = (cgreen*3+area.greenmax*255/255)/4;
				}
				else if(area.bluemax-Math.min(area.greenmax/4,area.redmax)>bluesat){
					cblue = (cblue*3+area.bluemax*255/63)/4;
				}
				srcColorBest[i] = new Color(cred, cgreen, cblue);
			}
			
			//srcColorBest[i] = new Color(i, (i*4)%256, (i*16)%256); //indexで色分け
		}
		
		counts = null;
		System.gc();
		BufferedImage newimg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_INT_ARGB);

		if(dithermode==0 || dithermode==1){
			//色空間にどの色に近いのかを書き込む
			short[] colorAry = new short[1<<20];
			/*for(int i=0; i<colorAry.length; i++){
				colorAry[i] = -1;
			}*/
			for(short i=0; i<colorList.size(); i++){
				colorCnt area = colorList.get(i);
				for(int r=area.initArea[0]; r<area.initArea[1]; r++){
					for(int g=area.initArea[2]; g<area.initArea[3]; g++){
						for(int b=area.initArea[4]; b<area.initArea[5]; b++){
							colorAry[r*256*64+g*64+b] = i;
						}
					}
				}
			}
			

			DataBuffer newdb = newimg.getRaster().getDataBuffer();
			width = srcbi.getWidth();
			height = srcbi.getHeight();
			for(int v=0; v<height; v++){
				for(int h=0; h<width; h++){
					newdb.setElem(h+v*width, 0);
				}
			}

			DataBuffer srcdb = srcbi.getRaster().getDataBuffer();
			for(int v=0; v<height; v++){
				//横方向はジグザグにスキャンする
				int hstart = 0;
				int hend = width;
				int add=1;
				if(v%2==1){
					hstart = width-1;
					hend = -1;
					add=-1;
				}
				for(int h=hstart; h!=hend; h+=add){
					int color = srcdb.getElem(h+v*width);
					
					//r,g,bを求める
					int carrycolor = newdb.getElem(h+v*width);
					int r = ((color>>16)&0xFF)+(byte)((carrycolor>>16)&0xFF);
					int g = ((color>>8)&0xFF)+(byte)((carrycolor>>8)&0xFF);
					int b = ((color)&0xFF)+(byte)((carrycolor)&0xFF);
					int rr, gg, bb;
					rr = r;
					if(r>255) {
						r = 255;
					} else if(r<0) {
						r = 0;
					}
					gg = g;
					if(g>255) {
						g = 255;
					} else if(g<0) {
						g = 0;
					}
					bb = b;
					if(b>255) {
						b = 255;
					} else if(b<0) {
						b = 0;
					}
					
					//ピクセルをインデックスカラーの近いものにする
					int index = colorAry[(r>>2)*256*64+(g>>0)*64+(b>>2)];
					Color newcolor = srcColorBest[index];
					if(colorList.size()<=32){
						//力技検索
						int rrr = (r + newcolor.getRed())/2;
						int ggg = (g + newcolor.getGreen())/2;
						int bbb = (b + newcolor.getBlue())/2;
						boolean isFound = false;
						int nearoffset = 0;
						while(!isFound){
							for(int i=0; i<srcColorBest.length; i++){
								if(srcColorBest[i]==null) continue;
								if(isNear((rrr<<16)+(ggg<<8)+bbb, srcColorBest[i], nearoffset)){
									newcolor = srcColorBest[i];
									isFound = true;
									break;
								}
							}
							nearoffset += nearoffset/2+1;
						}
					}
					
					//あまりにもかけ離れている場合は採用しない
					if(colorList.size()>=4 && Math.abs(((color>>16)&0xFF)-newcolor.getRed()) +Math.abs(((color>>8)&0xFF)-newcolor.getGreen()) +Math.abs(((color)&0xFF)-newcolor.getBlue()) >48*544/(32+colorList.size())){
						if(colorList.size()<=256){
							//力技検索
							boolean isFound = false;
							int nearoffset = 1;
							while(!isFound){
								for(int i=0; i<srcColorBest.length; i++){
									if(srcColorBest[i]==null) continue;
									if(isNear(color, srcColorBest[i], nearoffset)){
										newcolor = srcColorBest[i];
										isFound = true;
										break;
									}
								}
								nearoffset += nearoffset/2+1;
							}
						}
						else{
							r = ((color>>16)&0xFF);
							g = ((color>>8)&0xFF);
							b = ((color)&0xFF);
							index = colorAry[(r>>2)*256*64+(g>>0)*64+(b>>2)];
							newcolor = srcColorBest[index];
						}
					}
					
					//ピクセルに反映
					int d = (color&0xFF000000) + (newcolor.getRed()<<16) + (newcolor.getGreen()<<8) +(newcolor.getBlue());
					newdb.setElem(h+v*width, d);

					//余りを求める
					int cr = rr - newcolor.getRed();
					if(cr>127) cr = 127;
					if(cr<-127) cr = -127;
					int cg = gg - newcolor.getGreen();
					if(cg>127) cg = 127;
					if(cg<-127) cg = -127;
					int cb = bb - newcolor.getBlue();
					if(cb>127) cb = 127;
					if(cb<-127) cb = -127;
					
					//誤差を拡散する
					if(dithermode == 0){
						//Floyd-Steinburg Algorithm
						if(h+add>=0 && h+add<width) {
							int dd = newdb.getElem(h+add+v*width);
							int er = ((byte)((dd>>16)&0xFF)+cr*7/16);
							int eg = ((byte)((dd>>8)&0xFF)+cg*7/16);
							int eb = ((byte)((dd)&0xFF)+cb*7/16);
							newdb.setElem(h+add+v*width, ((0x00FF&er)<<16)+((0x00FF&eg)<<8)+((0x00FF&eb)));
						}
						if(v+1>=height) continue;
						if(h-1>=0){
							int dd = newdb.getElem(h-1+(v+1)*width);
							int er = (byte)((dd>>16)&0xFF)+cr*3/16;
							int eg = (byte)((dd>>8)&0xFF)+cg*3/16;
							int eb = (byte)((dd)&0xFF)+cb*3/16;
							newdb.setElem(h-1+(v+1)*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
						{
							int dd = newdb.getElem(h+(v+1)*width);
							int er = (byte)((dd>>16)&0xFF)+cr*5/16;
							int eg = (byte)((dd>>8)&0xFF)+cg*5/16;
							int eb = (byte)((dd)&0xFF)+cb*5/16;
							newdb.setElem(h+(v+1)*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
						if(h+1<width){
							int dd = newdb.getElem(h+1+(v+1)*width);
							int er = (byte)((dd>>16)&0xFF)+cr*1/16;
							int eg = (byte)((dd>>8)&0xFF)+cg*1/16;
							int eb = (byte)((dd)&0xFF)+cb*1/16;
							newdb.setElem(h+1+(v+1)*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
					}else{
						//Bill Atkinson Algorithm
						if(h+add>=0 && h+add<width) {
							int dd = newdb.getElem(h+add+v*width);
							int er = (byte)((dd>>16)&0xFF)+cr/8;
							int eg = (byte)((dd>>8)&0xFF)+cg/8;
							int eb = (byte)((dd)&0xFF)+cb/8;
							newdb.setElem(h+add+v*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
						if(h+add*2>=0 && h+add*2<width) {
							int dd = newdb.getElem(h+add*2+v*width);
							int er = (byte)((dd>>16)&0xFF)+cr/8;
							int eg = (byte)((dd>>8)&0xFF)+cg/8;
							int eb = (byte)((dd)&0xFF)+cb/8;
							newdb.setElem(h+add*2+v*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
						if(v+1>=height) continue;
						if(h-1>=0){
							int dd = newdb.getElem(h-1+(v+1)*width);
							int er = (byte)((dd>>16)&0xFF)+cr/8;
							int eg = (byte)((dd>>8)&0xFF)+cg/8;
							int eb = (byte)((dd)&0xFF)+cb/8;
							newdb.setElem(h-1+(v+1)*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
						{
							int dd = newdb.getElem(h+(v+1)*width);
							int er = (byte)((dd>>16)&0xFF)+cr/8;
							int eg = (byte)((dd>>8)&0xFF)+cg/8;
							int eb = (byte)((dd)&0xFF)+cb/8;
							newdb.setElem(h+(v+1)*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
						if(h+1<width){
							int dd = newdb.getElem(h+1+(v+1)*width);
							int er = (byte)((dd>>16)&0xFF)+cr/8;
							int eg = (byte)((dd>>8)&0xFF)+cg/8;
							int eb = (byte)((dd)&0xFF)+cb/8;
							newdb.setElem(h+1+(v+1)*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
						if(v+2<height){
							int dd = newdb.getElem(h+(v+2)*width);
							int er = (byte)((dd>>16)&0xFF)+cr/8;
							int eg = (byte)((dd>>8)&0xFF)+cg/8;
							int eb = (byte)((dd)&0xFF)+cb/8;
							newdb.setElem(h+(v+2)*width, ((0xFF&er)<<16)+((0xFF&eg)<<8)+(0xFF&eb));
						}
					}
				}
			}
		}
		else if(dithermode==2){
			//パターン処理はdrawImageに任せる
			
			//IndexColorModelを作成
			byte[] r_a = new byte[256];
			byte[] g_a = new byte[256];
			byte[] b_a = new byte[256];
			for(int i=0; i<srcColorBest.length; i++){
				if(srcColorBest[i] == null) continue;
				r_a[i] = (byte) srcColorBest[i].getRed();
				g_a[i] = (byte) srcColorBest[i].getGreen();
				b_a[i] = (byte) srcColorBest[i].getBlue();
			}
			
			IndexColorModel colorModel = new IndexColorModel(8, colors, r_a, g_a, b_a);
			
			//TYPE_BYTE_INDEXEDのBufferedImageを作る
			BufferedImage indeximg = new BufferedImage(srcbi.getWidth(), srcbi.getHeight(), BufferedImage.TYPE_BYTE_INDEXED, colorModel);
			Graphics2D indexg = indeximg.createGraphics();
			indexg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
			indexg.fillRect(0,0,indeximg.getWidth(), indeximg.getHeight());
			indexg = indeximg.createGraphics();
			indexg.drawImage(srcbi, 0,0, null);
			
			Graphics2D newg = newimg.createGraphics();
			newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
			newg.fillRect(0,0,newimg.getWidth(), newimg.getHeight());
			newimg.createGraphics().drawImage(indeximg, 0,0, null);
		}
		
		return newimg;
	}


	/*private final boolean isNear(Color color1, Color color2, int near){
		if(Math.abs(color1.getRed()-color2.getRed())>near){
			return false;
		}
		if(Math.abs(color1.getGreen()-color2.getGreen())>near){
			return false;
		}
		if(Math.abs(color1.getBlue()-color2.getBlue())>near){
			return false;
		}
		return true;
	}*/
	

	private final boolean isNear(int argb, Color color, int near){
		if(Math.abs(color.getRed()-((argb>>16)&0xFF))>near){
			return false;
		}
		if(Math.abs(color.getGreen()-((argb>>8)&0xFF))>near){
			return false;
		}
		if(Math.abs(color.getBlue()-((argb>>0)&0xFF))>near){
			return false;
		}
		/*if((color.getBlue()-((argb>>0)&0xFF)) - (color.getGreen()-((argb>>8)&0xFF))>near){
			return false;
		}
		if((color.getGreen()-((argb>>8)&0xFF)) - (color.getRed()-((argb>>16)&0xFF))>near){
			return false;
		}
		if((color.getRed()-((argb>>16)&0xFF)) - (color.getBlue()-((argb>>0)&0xFF))>near){
			return false;
		}*/
		return true;
	}
}

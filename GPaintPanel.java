import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;

import javax.swing.JPanel;


//カードピクチャとバックグラウンドピクチャを表示するパネル
class MyPanel extends JPanel{
	private static final long serialVersionUID = 1L;
	PCARDFrame owner;
	
	MyPanel(PCARDFrame owner){
		super();
		this.owner = owner;
	}


	static interface blendMode{
		static final int Null=-1;
		static final int Copy=0;
		static final int Blend=1;
		static final int Add=2;
		static final int Subtract=3;
		static final int Multiply=4;
		static final int Screen=5;
		static final int Darken=6;
		static final int Lighten=7;
		static final int Difference=8;
		static final int Hue=9;
		static final int Color=10;
		static final int Saturation=11;
		static final int Luminosity=12;
		static final int AlphaChannel=13;
	}
	
	int getBlendMode(String str) {
		if(str.equals("Copy")) return blendMode.Copy;
		if(str.equals("Blend")) return blendMode.Blend;
		if(str.equals("Add")) return blendMode.Add;
		if(str.equals("Subtract")) return blendMode.Subtract;
		if(str.equals("Multiply")) return blendMode.Multiply;
		if(str.equals("Screen")) return blendMode.Screen;
		if(str.equals("Darken")) return blendMode.Darken;
		if(str.equals("Lighten")) return blendMode.Lighten;
		if(str.equals("Difference")) return blendMode.Difference;
		if(str.equals("Hue")) return blendMode.Hue;
		if(str.equals("Color")) return blendMode.Color;
		if(str.equals("Saturation")) return blendMode.Saturation;
		if(str.equals("Luminosity")) return blendMode.Luminosity;
		if(str.equals("Alpha Channel")) return blendMode.AlphaChannel;
		return blendMode.Null;
	}
	
	
	@Override
	protected void paintComponent(Graphics g){
		//ペイントツール用メイン描画部
		if(owner.tool!=null){
			if(!PaintTool.editBackground){
				mainPaneDraw(g, owner.bgImg);
			}
			if(owner.blendMode!=blendMode.AlphaChannel){
				mainPaneDraw(g, owner.getSurface());
			}
			
			AffineTransform saveAT = ((Graphics2D) g).getTransform();
			
			if(owner.tool!=null && owner.redoBuf!=null &&
				owner.tool instanceof toolSelectInterface && ((toolSelectInterface)owner.tool).isMove())
			{
				BufferedImage flowImage = owner.redoBuf;
				
				//ブレンドモード
				if(owner.blendMode!=blendMode.Copy || owner.blendLevel!=100){
					Point offset = new Point(0,0);
					if(owner.tool.getClass()==SelectTool.class){
						SelectTool tool = (SelectTool)owner.tool;
						offset.x = tool.moveRect.x;
						offset.y = tool.moveRect.y;
					}else if(owner.tool.getClass()==LassoTool.class){
						LassoTool tool = (LassoTool)owner.tool;
						offset.x = tool.movePoint.x;
						offset.y = tool.movePoint.y;
					}else if(owner.tool.getClass()==SmartSelectTool.class){
						SmartSelectTool tool = (SmartSelectTool)owner.tool;
						offset.x = tool.movePoint.x;
						offset.y = tool.movePoint.y;
					}
					
					BufferedImage newImage = new BufferedImage(flowImage.getWidth(), flowImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
					Graphics2D newg = newImage.createGraphics();
					newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
					newg.fillRect(0,0,newImage.getWidth(), newImage.getHeight());

					if(owner.blendMode==blendMode.AlphaChannel){
						BufferedImage sfcImage = new BufferedImage(owner.getSurface().getWidth(), owner.getSurface().getHeight(), BufferedImage.TYPE_INT_ARGB);
						Graphics2D sfcg = sfcImage.createGraphics();
						sfcg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
						sfcg.fillRect(0,0,sfcImage.getWidth(), sfcImage.getHeight());
						sfcImage.createGraphics().drawImage(owner.getSurface(),0,0,null);
						
						makeBlendImage(newImage, flowImage, sfcImage, offset, owner.blendMode, owner.blendLevel);

						mainPaneDraw(g, sfcImage);
					}
					else{
						makeBlendImage(newImage, flowImage, owner.getSurface(), offset, owner.blendMode, owner.blendLevel);
					}
					
					flowImage = newImage;
				}
				
				//選択範囲を描画
				if(owner.tool.getClass()==SelectTool.class){
					SelectTool tl = (SelectTool)owner.tool;
					if(tl.move){
						if(owner.selectaf!=null){
							((Graphics2D)g).setTransform(owner.selectaf);
						}
						mainPaneDraw(g, flowImage, tl.moveRect.x, tl.moveRect.y, tl.moveRect.width, tl.moveRect.height);
					}
				}
				else if(owner.tool.getClass()==LassoTool.class){
					LassoTool tl = (LassoTool)owner.tool;
					if(tl.move){
						if(owner.selectaf!=null){
							((Graphics2D)g).setTransform(owner.selectaf);
						}
						mainPaneDraw(g, flowImage, tl.movePoint.x, tl.movePoint.y, owner.redoBuf.getWidth(), owner.redoBuf.getHeight());
					}
				}
				else if(owner.tool.getClass()==SmartSelectTool.class){
					SmartSelectTool tl = (SmartSelectTool)owner.tool;
					if(tl.move){
						if(owner.selectaf!=null){
							((Graphics2D)g).setTransform(owner.selectaf);
						}
						mainPaneDraw(g, flowImage, tl.movePoint.x, tl.movePoint.y, owner.redoBuf.getWidth(), owner.redoBuf.getHeight());
					}
				}
			}

			((Graphics2D)g).setTransform(saveAT);
			
			if(owner.bit > 2){
				bordersDraw(g, owner.bit, owner.bgImg.getWidth(), owner.bgImg.getHeight());
			}
		}
		
		/*if(PCARD.pc.stack!=null&&PCARD.pc.stack.curCard!=null
				&&PCARD.pc.stack.curCard.bg!=null&&PCARD.pc.stack.curCard.bg.label!=null
				&&PCARD.pc.tool==null){
			return;
		}*/
	}


	static void bordersDraw(Graphics g, int bit, int width, int height){
		if(!PCARDFrame.useGrid) return;
		for(int h=0/*bit-1*/; h<width*bit; h+=bit*PCARDFrame.gridSize){
			g.setColor(Color.GRAY);
			g.drawLine(h,0,h,height*bit);
		}
		for(int v=0/*bit-1*/; v<height*bit; v+=bit*PCARDFrame.gridSize){
			g.setColor(Color.GRAY);
			g.drawLine(0,v,width*bit,v);
		}
	}
	
	void mainPaneDraw(Graphics g, BufferedImage img){
		if(owner.bit > 1){
			//拡大表示
			g.drawImage(img, ((int)owner.bitLeft)*(-owner.bit),
					((int)owner.bitTop)*(-owner.bit),
					((int)owner.bitLeft)*(-owner.bit)+owner.bgImg.getWidth()*owner.bit,
					((int)owner.bitTop)*(-owner.bit)+owner.bgImg.getHeight()*owner.bit,
					0, 0, owner.bgImg.getWidth(), owner.bgImg.getHeight(), null);
		}
		else {
			g.drawImage(img, 0, 0, null);
		}
	}
	
	public void mainPaneDraw(Graphics g, BufferedImage img, int x, int y, int width, int height){
		if(owner.bit > 1){
			//拡大表示
			g.drawImage(img, x*owner.bit-(int)owner.bitLeft*owner.bit,
					y*owner.bit-(int)owner.bitTop*owner.bit,
					x*owner.bit-(int)owner.bitLeft*owner.bit+width*owner.bit,
					y*owner.bit-(int)owner.bitTop*owner.bit+height*owner.bit,
					0, 0, width, height, null);
		}
		else {
			g.drawImage(img, x, y, x+width, y+height, 0, 0, width, height, null);
		}
	}
	

	public static BufferedImage makeBlendImage(BufferedImage flowImage)
	{
		if(PaintTool.owner.blendMode==blendMode.Copy && PaintTool.owner.blendLevel==100){
			return flowImage;
		}
		
		BufferedImage newImage = new BufferedImage(flowImage.getWidth(), flowImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D newg = newImage.createGraphics();
		newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
		newg.fillRect(0,0,newImage.getWidth(), newImage.getHeight());

		Point offset = new Point(0,0);
		if(PaintTool.owner.tool.getClass()==SelectTool.class){
			SelectTool tool = (SelectTool)PaintTool.owner.tool;
			offset.x = tool.moveRect.x;
			offset.y = tool.moveRect.y;
		}else if(PaintTool.owner.tool.getClass()==LassoTool.class){
			LassoTool tool = (LassoTool)PaintTool.owner.tool;
			offset.x = tool.movePoint.x;
			offset.y = tool.movePoint.y;
		}else if(PaintTool.owner.tool.getClass()==SmartSelectTool.class){
			SmartSelectTool tool = (SmartSelectTool)PaintTool.owner.tool;
			offset.x = tool.movePoint.x;
			offset.y = tool.movePoint.y;
		}
		
		makeBlendImage(newImage, flowImage, PaintTool.owner.getSurface(), offset,
				PaintTool.owner.blendMode, PaintTool.owner.blendLevel);
		
		return newImage;
	}
	
	
	private static void makeBlendImage(BufferedImage newImage,
			BufferedImage flowImage, BufferedImage surfaceImage, Point offset, int mode, int level)
	{
		if(flowImage.hasTileWriters()){
			
			BufferedImage flowImage2 = new BufferedImage(flowImage.getWidth(), flowImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D newg = flowImage2.createGraphics();
			newg.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
			newg.fillRect(0,0,flowImage2.getWidth(), flowImage2.getHeight());
			flowImage2.createGraphics().drawImage(flowImage, 0,0,null);
			flowImage = flowImage2;
		}
		
		switch(mode){
		case blendMode.Copy: 
			copyImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Blend:
			blendImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Add:
			addImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Subtract:
			subtractImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Multiply:
			multiplyImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Screen:
			screenImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Darken:
			darkenImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Lighten:
			lightenImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Difference:
			differenceImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Hue:
			hueImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Color:
			colorImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Saturation:
			saturationImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.Luminosity:
			luminosityImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		case blendMode.AlphaChannel:
			alphaImage(newImage, flowImage, surfaceImage, offset, level);
			break;
		}
	}


	private static void copyImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//copyモードは色を変えるだけ
		
		//DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<srcheight; v++){
			if(v<0 || v>=height){
				continue;
			}
			for(int h=0; h<srcwidth; h++){
				if(h<0 || h>=width){
					continue;
				}
				int c = srcbuf.getElem(h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF)*level/100+255*(100-level)/100;
					int green = ((c>>8)&0xFF)*level/100+255*(100-level)/100;
					int blue = ((c)&0xFF)*level/100+255*(100-level)/100;
					c = alpha + (red<<16) + (green<<8) + blue;
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void blendImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//blendモードはアルファ値を変えるだけ
		
		//DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<srcheight; v++){
			if(v<0 || v>=height){
				continue;
			}
			for(int h=0; h<srcwidth; h++){
				if(h<0 || h>=width){
					continue;
				}
				int c = srcbuf.getElem(h+v*srcwidth);
				int alpha = 0xFF&(c>>24);
				if(alpha!=0){
					alpha = alpha*level/100;
					c = (alpha<<24) + (0x00FFFFFF&c);
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void addImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//addモードは黒を起点にした足し算
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<srcheight; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<srcwidth; h++){
				int c = srcbuf.getElem(h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					int red = ((c>>16)&0xFF)*level/100;
					int green = ((c>>8)&0xFF)*level/100;
					int blue = ((c)&0xFF)*level/100;

					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);
					
					red += sfc_red;
					if(red>255) red = 255;
					green += sfc_green;
					if(green>255) green = 255;
					blue += sfc_blue;
					if(blue>255) blue = 255;
					
					c = alpha + (red<<16) + (green<<8) + blue;
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void subtractImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//subtractモードは白を起点にした引き算
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<srcheight; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<srcwidth; h++){
				int c = srcbuf.getElem(h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = (255-((c>>16)&0xFF))*level/100;
					int green = (255-((c>>8)&0xFF))*level/100;
					int blue = (255-((c)&0xFF))*level/100;

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);
					
					sfc_red -= red;
					if(sfc_red<0) sfc_red = 0;
					sfc_green -= green;
					if(sfc_green<0) sfc_green = 0;
					sfc_blue -= blue;
					if(sfc_blue<0) sfc_blue = 0;
					
					c = alpha + (sfc_red<<16) + (sfc_green<<8) + sfc_blue;
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void multiplyImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//multiplyモードは黒を起点にした掛け算
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<srcheight; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<srcwidth; h++){
				int c = srcbuf.getElem(h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF)*level/100;
					int green = ((c>>8)&0xFF)*level/100;
					int blue = ((c)&0xFF)*level/100;

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);
					
					sfc_red = sfc_red*red/255;
					sfc_green = sfc_green*green/255;
					sfc_blue = sfc_blue*blue/255;
					
					c = alpha + (sfc_red<<16) + (sfc_green<<8) + sfc_blue;
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void screenImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//screenモードはレベルを起点にした足し算
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<srcheight; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<srcwidth; h++){
				int c = srcbuf.getElem(h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF)-255*level/100;
					int green = ((c>>8)&0xFF)-255*level/100;
					int blue = ((c)&0xFF)-255*level/100;

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);
					
					sfc_red += red;
					if(sfc_red<0) sfc_red = 0;
					if(sfc_red>255) sfc_red = 255;
					sfc_green += green;
					if(sfc_green<0) sfc_green = 0;
					if(sfc_green>255) sfc_green = 255;
					sfc_blue += blue;
					if(sfc_blue<0) sfc_blue = 0;
					if(sfc_blue>255) sfc_blue = 255;
					
					c = alpha + (sfc_red<<16) + (sfc_green<<8) + sfc_blue;
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void darkenImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//darkenモードは暗いほうを残す
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int srcwidth = flowImage.getWidth();
		int srcheight = flowImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<srcheight; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<srcwidth; h++){
				int c = srcbuf.getElem(h+v*srcwidth);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);
					
					if(sfc_red+sfc_green+sfc_blue < red+green+blue){
						c = alpha + (sfc_red<<16) + (sfc_green<<8) + sfc_blue;
					}
					else{
						c = alpha + (red<<16) + (green<<8) + blue;
					}
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void lightenImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//lightenモードは明るいほうを残す
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<height; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(h+v*width);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00000000;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);
					
					if(sfc_red+sfc_green+sfc_blue > red+green+blue){
						c = alpha + (sfc_red<<16) + (sfc_green<<8) + sfc_blue;
					}
					else{
						c = alpha + (red<<16) + (green<<8) + blue;
					}
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void differenceImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//differenceモードは差の絶対値
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<height; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(h+v*width);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);

					sfc_red = Math.abs(sfc_red-red);
					sfc_green = Math.abs(sfc_green-green);
					sfc_blue = Math.abs(sfc_blue-blue);
					
					c = alpha + (sfc_red<<16) + (sfc_green<<8) + sfc_blue;
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void hueImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//hueモードは色相のみ
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		float[] hsb = new float[3];
		float[] sfc_hsb = new float[3];
		for(int v=0; v<height; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(h+v*width);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);

					Color.RGBtoHSB(red, green, blue, hsb);
					Color.RGBtoHSB(sfc_red, sfc_green, sfc_blue, sfc_hsb);
					sfc_hsb[0] = hsb[0]; //色相のみ代入
					int rgb = Color.HSBtoRGB(sfc_hsb[0], sfc_hsb[1], sfc_hsb[2]);
					
					c = alpha + (0x00FFFFFF&rgb);
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void colorImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//colorモードは色相と彩度
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		float[] hsb = new float[3];
		float[] sfc_hsb = new float[3];
		for(int v=0; v<height; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(h+v*width);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);

					Color.RGBtoHSB(red, green, blue, hsb);
					Color.RGBtoHSB(sfc_red, sfc_green, sfc_blue, sfc_hsb);
					sfc_hsb[0] = hsb[0]; //色相代入
					sfc_hsb[1] = hsb[1]; //彩度代入
					int rgb = Color.HSBtoRGB(sfc_hsb[0], sfc_hsb[1], sfc_hsb[2]);
					
					c = alpha + (0x00FFFFFF&rgb);
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}
	
	
	private static void saturationImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//saturationモードは彩度
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		float[] hsb = new float[3];
		float[] sfc_hsb = new float[3];
		for(int v=0; v<height; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(h+v*width);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);

					Color.RGBtoHSB(red, green, blue, hsb);
					Color.RGBtoHSB(sfc_red, sfc_green, sfc_blue, sfc_hsb);
					sfc_hsb[1] = hsb[1]; //彩度代入
					int rgb = Color.HSBtoRGB(sfc_hsb[0], sfc_hsb[1], sfc_hsb[2]);
					
					c = alpha + (0x00FFFFFF&rgb);
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}
	
	
	private static void luminosityImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//luminosityモードは明度
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		float[] hsb = new float[3];
		float[] sfc_hsb = new float[3];
		for(int v=0; v<height; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(h+v*width);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					int sfc_red, sfc_green, sfc_blue; 
					if((sc&0xFF000000)==0){
						sc = 0x00FFFFFF;
					}
					sfc_red = ((sc>>16)&0xFF);
					sfc_green = ((sc>>8)&0xFF);
					sfc_blue = ((sc)&0xFF);

					Color.RGBtoHSB(red, green, blue, hsb);
					Color.RGBtoHSB(sfc_red, sfc_green, sfc_blue, sfc_hsb);
					sfc_hsb[2] = hsb[2]; //明度代入
					int rgb = Color.HSBtoRGB(sfc_hsb[0], sfc_hsb[1], sfc_hsb[2]);
					
					c = alpha + (0x00FFFFFF&rgb);
					newbuf.setElem(h+v*width, c);
				}
			}
		}
	}


	private static void alphaImage(BufferedImage newImage, BufferedImage flowImage,
			BufferedImage surfaceImage, Point offset, int level)
	{
		//alpha channelモードは濃度をサーフェースのアルファチャンネルに適用
		//サーフェースを変更するので特別扱いが必要
		
		DataBuffer surfacebuf = surfaceImage.getRaster().getDataBuffer();
		DataBuffer srcbuf = flowImage.getRaster().getDataBuffer();
		//DataBuffer newbuf = newImage.getRaster().getDataBuffer();
		int sfcwidth = surfaceImage.getWidth();
		int sfcheight = surfaceImage.getHeight();
		int width = newImage.getWidth();
		int height = newImage.getHeight();
		for(int v=0; v<height; v++){
			if(v+offset.y<0 || v+offset.y>=height){
				continue;
			}
			if(v+offset.y>=sfcheight){
				continue;
			}
			for(int h=0; h<width; h++){
				int c = srcbuf.getElem(h+v*width);
				int alpha = c&0xFF000000;
				if(alpha!=0){
					int red = ((c>>16)&0xFF);
					int green = ((c>>8)&0xFF);
					int blue = ((c)&0xFF);

					if(h+offset.x<0 || h+offset.x>=width){
						continue;
					}
					if(h+offset.x>=sfcwidth){
						continue;
					}
					int sc = surfacebuf.getElem(h+offset.x+(v+offset.y)*sfcwidth);
					if((sc&0xFF000000)==0){
						continue;
					}
					int sfc_alpha = 255-(red+green+blue)*level/100/3;
					
					c = (sfc_alpha<<24) + (0x00FFFFFF&sc);
					surfacebuf.setElem(h+offset.x+(v+offset.y)*sfcwidth, c);
				}
			}
		}
	}
}

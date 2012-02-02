import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.util.Date;


public class VEffect {
	static BufferedImage oldoff=null;

	public static void setOldOff() {
		int width=PCARDFrame.pc.mainPane.getWidth();
		int height=PCARDFrame.pc.mainPane.getHeight();
		if(width<=0 || height<=0) return;
		oldoff = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
		Graphics2D offg = oldoff.createGraphics();
		PCARDFrame.pc.mainPane.paint(offg);
	}
	
	public static void visualEffect(int effect, int toEffect, int speed) {
		int width=PCARDFrame.pc.mainPane.getWidth();
		int height=PCARDFrame.pc.mainPane.getHeight();
		
		if(effect==0){
		}
		else{
			BufferedImage off = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
			//VolatileImage off = PCARD.pc.stack.pcard.createVolatileImage(width, height);
			Graphics2D offg = off.createGraphics();
			Graphics paneg = PCARDFrame.pc.mainPane.getGraphics();
			
			/* to black1 white2 grey3 inverse4 */
			if(toEffect==0){
				//オフスクリーンに描画
				//PCARD.lockedScreen = false;
				PCARDFrame.pc.mainPane.paint(offg);
				PCARD.lockedScreen = true;
			}
			else if(toEffect==4){
				//オフスクリーンに描画
				//PCARD.lockedScreen = false;
				PCARDFrame.pc.mainPane.paint(offg);
				PCARD.lockedScreen = true;
				offg.setXORMode(Color.white);
				offg.fillRect(0,0,width,height);
			}
			else if(toEffect==1){
				offg.setColor(Color.black);
				offg.fillRect(0,0,width,height);
			}
			else if(toEffect==2){
				offg.setColor(Color.white);
				offg.fillRect(0,0,width,height);
			}
			else if(toEffect==3){
				BufferedImage txtr = new BufferedImage(2, 2, BufferedImage.TYPE_INT_BGR);
				txtr.getRaster().getDataBuffer().setElem(0,0xFFFFFF);
				txtr.getRaster().getDataBuffer().setElem(1,0x000000);
				txtr.getRaster().getDataBuffer().setElem(2,0x000000);
				txtr.getRaster().getDataBuffer().setElem(3,0xFFFFFF);
				Rectangle2D r = new Rectangle2D.Double(0,0,2,2);
				offg.setPaint(new TexturePaint(txtr, r));
				offg.fillRect(0, 0, width, height);
			}

			if(oldoff!=null) paneg.drawImage(oldoff,0,0,width,height,PCARDFrame.pc.stack.pcard);
			
			long start = new Date().getTime();
			long now = start;
			long end = start;
			switch(speed){
				case 1:end = start+100; break;
				case 2:end = start+250; break;
				case 3:end = start+500; break;
				case 4:end = start+1000; break;
				case 5:end = start+2000; break;
			}
			if(effect==1) { //barn door open
				while(now<end){
					int v=(int)(width/2*((double)(now-start)/(end-start)));
					paneg.drawImage(off,width/2-v,0,width/2+v,height,width/2-v,0,width/2+v,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==2) { //barn door close
				while(now<end){
					int v=(int)(width/2*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,0,v,height,0,0,v,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,width-v,0,width,height,width-v,0,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==3 && oldoff!=null) { //dissolve
				for(int i=0; i<16; ){
					now = new Date().getTime();
					int v=(int)(16*((double)(now-start)/(end-start)));
					if(v<i) {try{Thread.sleep(5);}catch(Exception e){} continue;}
					DataBuffer oldoffdb = oldoff.getRaster().getDataBuffer();
					DataBuffer offdb = off.getRaster().getDataBuffer();
					while(i<v && i<16){
						for(int y=getDissolveOffY(i);y<height;y+=2){
							for(int x=getDissolveOffX(i);x<width;x+=8){
								oldoffdb.setElem(0,y*width+x,offdb.getElem(0,y*width+x));
							}
						}
						i++;
					}
					paneg.drawImage(oldoff,0,0,width,height,PCARDFrame.pc.stack.pcard);
				}
			}
			else if(effect==4) { //venetian blinds
				while(now<end){
					int v=(int)(32*((double)(now-start)/(end-start)));
					for(int i=0; i<(height+31)/32; i++){
						paneg.drawImage(off,0,32*i,width,32*i+v,0,32*i,width,32*i+v,PCARDFrame.pc.stack.pcard);
					}
					now = new Date().getTime();
				}
			}
			else if(effect==5) { //checker board
				while(now<end){
					int v=(int)(64*((double)(now-start)/(end-start)));
					for(int i=0; i<(height+63)/64; i++){
						for(int j=0; j<(width+63)/64; j++){
							paneg.drawImage(off,j*64,64*i,j*64+32,64*i+v,j*64,64*i,j*64+32,64*i+v,PCARDFrame.pc.stack.pcard);
						}
						for(int j=0; j<(width+63)/64; j++){
							paneg.drawImage(off,j*64+32,64*i-32,j*64+64,64*i-32+v,j*64+32,64*i-32,j*64+64,64*i-32+v,PCARDFrame.pc.stack.pcard);
						}
					}
					now = new Date().getTime();
				}
			}
			else if(effect==6) { //iris open
				while(now<end){
					int h=(int)(width/2*((double)(now-start)/(end-start)));
					int v=(int)(height/2*((double)(now-start)/(end-start)));
					paneg.drawImage(off,width/2-h,height/2-v,width/2+h,height/2+v,width/2-h,height/2-v,width/2+h,height/2+v,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==7) { //iris close
				while(now<end){
					int h=(int)(width/2*((double)(now-start)/(end-start)));
					int v=(int)(height/2*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,0,width,v,0,0,width,v,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,height-v,width,height,0,height-v,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,v,h,height-v,0,v,h,height-v,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,width-h,v,width,height-v,width-h,v,width,height-v,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==8 && oldoff!=null) { //scroll left
				while(now<end){
					int v=(int)(width*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,0,0,width-v,height,v,0,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,width-v,0,width,height,width-v,0,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==9 && oldoff!=null) { //scroll right
				while(now<end){
					int v=(int)(width*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,v,0,width,height,0,0,width-v,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,0,v,height,0,0,v,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==10 && oldoff!=null) { //scroll up
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,0,0,width,height-v,0,v,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,height-v,width,height,0,height-v,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==11 && oldoff!=null) { //scroll down
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,0,v,width,height,0,0,width,height-v,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,0,width,v,0,0,width,v,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==12) { //wipe left
				while(now<end){
					int v=(int)(width*((double)(now-start)/(end-start)));
					paneg.drawImage(off,width-v,0,width,height,width-v,0,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==13) { //wipe right
				while(now<end){
					int v=(int)(width*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,0,v,height,0,0,v,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==14) { //wipe up
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,height-v,width,height,0,height-v,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==15) { //wipe down
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,0,width,v,0,0,width,v,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==16) { //zoom open
				while(now<end){
					int h=(int)(width*((double)(now-start)/(end-start)));
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.setXORMode(Color.white);
					paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
					paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
					now = new Date().getTime();
				}
			}
			else if(effect==17) { //zoom close
				while(now<end){
					int h=(int)(width*((double)(end-now)/(end-start)));
					int v=(int)(height*((double)(end-now)/(end-start)));
					paneg.setXORMode(Color.white);
					paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
					paneg.drawRect(width/2-h/2,height/2-v/2,h,v);
					now = new Date().getTime();
				}
			}
			else if(effect==18 && oldoff!=null) { //shrink to top
				while(now<end){
					int v=(int)(height*((double)(end-now)/(end-start)));
					paneg.drawImage(oldoff,0,0,width,v,0,0,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,v,width,height,0,v,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==19 && oldoff!=null) { //shrink to bottom
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,0,v,width,height,0,0,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,0,width,v,0,0,width,v,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==20 && oldoff!=null) { //shrink to center
				while(now<end){
					int v=(int)(height/2*((double)(end-now)/(end-start)));
					paneg.drawImage(oldoff,0,height/2-v,width,height/2+v,0,0,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,0,width,height/2-v,0,0,width,height/2-v,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,height/2+v,width,height,0,height/2+v,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==21) { //stretch from top
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,0,width,v,0,0,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==22) { //stretch from bottom
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,height-v,width,height,0,0,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==23) { //stretch from center
				while(now<end){
					int v=(int)(height/2*((double)(now-start)/(end-start)));
					paneg.drawImage(off,0,height/2-v,width,height/2+v,0,0,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==24 && oldoff!=null) { //push left
				while(now<end){
					int v=(int)(width*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,0,0,width-v,height,v,0,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,width-v,0,width,height,0,0,v,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==25 && oldoff!=null) { //push right
				while(now<end){
					int v=(int)(width*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,v,0,width,height,0,0,width-v,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,0,v,height,width-v,0,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==26 && oldoff!=null) { //push up
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,0,0,width,height-v,0,v,width,height,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,height-v,width,height,0,0,width,v,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
			else if(effect==27 && oldoff!=null) { //push down
				while(now<end){
					int v=(int)(height*((double)(now-start)/(end-start)));
					paneg.drawImage(oldoff,0,v,width,height,0,0,width,height-v,PCARDFrame.pc.stack.pcard);
					paneg.drawImage(off,0,0,width,v,0,height-v,width,height,PCARDFrame.pc.stack.pcard);
					now = new Date().getTime();
				}
			}
		}
		
		//移動後のカードの表示
		PCARD.lockedScreen = false;
		//PCARD.pc.mainPane.paintImmediately(0, 0, PCARD.pc.stack.width, PCARD.pc.stack.height);
		PCARDFrame.pc.mainPane.repaint();

		oldoff = null;
	}

	private final static int getDissolveOffY(int i){
		switch(i){
		case 0: return 0;
		case 1: return 0;
		case 2: return 1;
		case 3: return 1;
		case 4: return 0;
		case 5: return 0;
		case 6: return 1;
		case 7: return 1;
		case 8: return 1;
		case 9: return 1;
		case 10: return 0;
		case 11: return 0;
		case 12: return 1;
		case 13: return 1;
		case 14: return 0;
		case 15: return 0;
		}
		return 0;
	}
	private final static int getDissolveOffX(int i){
		switch(i){
		case 0: return 0;
		case 1: return 4;
		case 2: return 2;
		case 3: return 6;
		case 4: return 3;
		case 5: return 7;
		case 6: return 1;
		case 7: return 5;
		case 8: return 0;
		case 9: return 4;
		case 10: return 2;
		case 11: return 6;
		case 12: return 3;
		case 13: return 7;
		case 14: return 1;
		case 15: return 5;
		}
		return 0;
	}
}

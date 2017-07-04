import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.JPanel;


public class TXcmd {
	static TXcmd txcmd = new TXcmd();
	
	//XCMD,XFCN
	public static Result CallExternalCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException 
	{
		if(0==message.compareToIgnoreCase("UxBack")){
			System.out.println("未定義のXCMD:"+message);
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("Movie")){
			//QuickTime for Javaは64bitで使えないし、サポートもしないみたいだしどうしようもない。
			//BGMならiTunesでmp3に変換してもらおう。

			//+.mp3ファイルがあれば再生
			//MP3SPIを使う
			//Movie (path1&TheMovieName),"borderLess","0,0","invisible","floating"
			if(params.length>=1){
				String path = params[0];
				for(int i=0;i<path.length();i++){
					if(path.charAt(i)==':') path = path.substring(0,i)+File.separatorChar+path.substring(i+1);
				}
				File f = new File(path);
				if(!f.exists()){
					path = path+".mp3";
					f = new File(path);
				}
				if(!f.exists()){
					result.theResult = "ファイル"+path+"がありません";
					result.ret = 0;
				}
				else{
					TMP3 mp3 = new TMP3(path,params[0]);
					if(mp3.mp3ok()==true){
						new OWindow(mp3);
					}else{
						result.theResult = "ファイル"+path+"を再生できません";
						result.ret = 0;
					}
				}
			}

			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("KStealKeyX")){
			String ret = "";
			for(int i=0; i<params[0].length(); i++){
				char c = params[0].charAt(i);
				if(c=='^' && i+2<params[0].length()){
					String key = params[0].substring(i,i+3);
					i+=2;
					if(key.equals("^au")) ret += (GUI.key[0]>1)?"1":"0";
					if(key.equals("^ad")) ret += (GUI.key[1]>1)?"1":"0";
					if(key.equals("^al")) ret += (GUI.key[2]>1)?"1":"0";
					if(key.equals("^ar")) ret += (GUI.key[3]>1)?"1":"0";
					if(key.equals("^re")) ret += (GUI.key[10]>1)?"1":"0";
					if(key.equals("^sh")) ret += (GUI.key[11]>1)?"1":"0";
					if(key.equals("^sf")) ret += (GUI.key[11]>1)?"1":"0";
					if(key.equals("^op")) ret += (GUI.key[12]>1)?"1":"0";
					if(key.equals("^ct")) ret += (GUI.key[13]>1)?"1":"0";
					if(key.equals("^cm")) ret += (GUI.key[14]>1)?"1":"0";
					if(key.equals("^ca")) ret += (GUI.key[15]>1)?"1":"0";
					if(key.equals("^de")) ret += (GUI.key[20]>1)?"1":"0";
					if(key.equals("^dl")) ret += (GUI.key[21]>1)?"1":"0";
					if(key.equals("^es")) ret += (GUI.key[22]>1)?"1":"0";
					if(key.equals("^t0")) ret += (GUI.key[100]>1)?"1":"0";
					if(key.equals("^t1")) ret += (GUI.key[101]>1)?"1":"0";
					if(key.equals("^t2")) ret += (GUI.key[102]>1)?"1":"0";
					if(key.equals("^t3")) ret += (GUI.key[103]>1)?"1":"0";
					if(key.equals("^t4")) ret += (GUI.key[104]>1)?"1":"0";
					if(key.equals("^t5")) ret += (GUI.key[105]>1)?"1":"0";
					if(key.equals("^t6")) ret += (GUI.key[106]>1)?"1":"0";
					if(key.equals("^t7")) ret += (GUI.key[107]>1)?"1":"0";
					if(key.equals("^t8")) ret += (GUI.key[108]>1)?"1":"0";
					if(key.equals("^t9")) ret += (GUI.key[109]>1)?"1":"0";
				}else{
					if(c>=32 && c < 128) ret += (GUI.key[c]>1)?"1":"0";
				}
			}
			 
			result.theResult = ret;
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("KStealKeyX2")){
			String ret = "";
			for(int i=0; i<params[0].length(); i++){
				char c = params[0].charAt(i);
				if(c=='<' && i+2<params[0].length()){
					String key = params[0].substring(i,i+3);
					while(params[0].charAt(i)!='>')i++;
					if(key.equals("<lt")) ret += (GUI.key['<']>1)?"1":"0";
					if(key.equals("<gt")) ret += (GUI.key['>']>1)?"1":"0";
					if(key.equals("<au")) ret += (GUI.key[0]>1)?"1":"0";
					if(key.equals("<ad")) ret += (GUI.key[1]>1)?"1":"0";
					if(key.equals("<al")) ret += (GUI.key[2]>1)?"1":"0";
					if(key.equals("<ar")) ret += (GUI.key[3]>1)?"1":"0";
					if(key.equals("<re")) ret += (GUI.key[10]>1)?"1":"0";
					if(key.equals("<sh")) ret += (GUI.key[11]>1)?"1":"0";
					if(key.equals("<sf")) ret += (GUI.key[11]>1)?"1":"0";
					if(key.equals("<op")) ret += (GUI.key[12]>1)?"1":"0";
					if(key.equals("<ct")) ret += (GUI.key[13]>1)?"1":"0";
					if(key.equals("<cm")) ret += (GUI.key[14]>1)?"1":"0";
					if(key.equals("<ca")) ret += (GUI.key[15]>1)?"1":"0";
					if(key.equals("<de")) ret += (GUI.key[20]>1)?"1":"0";
					if(key.equals("<dl")) ret += (GUI.key[21]>1)?"1":"0";
					if(key.equals("<es")) ret += (GUI.key[22]>1)?"1":"0";
					if(key.equals("<t0")) ret += (GUI.key[100]>1)?"1":"0";
					if(key.equals("<t1")) ret += (GUI.key[101]>1)?"1":"0";
					if(key.equals("<t2")) ret += (GUI.key[102]>1)?"1":"0";
					if(key.equals("<t3")) ret += (GUI.key[103]>1)?"1":"0";
					if(key.equals("<t4")) ret += (GUI.key[104]>1)?"1":"0";
					if(key.equals("<t5")) ret += (GUI.key[105]>1)?"1":"0";
					if(key.equals("<t6")) ret += (GUI.key[106]>1)?"1":"0";
					if(key.equals("<t7")) ret += (GUI.key[107]>1)?"1":"0";
					if(key.equals("<t8")) ret += (GUI.key[108]>1)?"1":"0";
					if(key.equals("<t9")) ret += (GUI.key[109]>1)?"1":"0";
				}else{
					if(c>=32 && c < 128) ret += (GUI.key[c]>1)?"1":"0";
				}
			 }
			 
			 result.theResult = ret;
			 result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("UxGetInKey")){
			String ret = "";
			if(params[0].equalsIgnoreCase("C")){
				for(char c=0; c<128; c++){
					String keyStr = null;
					if(GUI.key[c]>1){
						if(c==0) keyStr = Character.toString('↑');
						else if(c==1) keyStr = Character.toString('↓');
						else if(c==2) keyStr = Character.toString('←');
						else if(c==3) keyStr = Character.toString('→');
						else if(c==20) keyStr = Character.toString('⌫');
						else if(c==21) keyStr = Character.toString('⌦');
						else if(c==22) keyStr = Character.toString('⎋');
						if(c>=32){
							keyStr = Character.toString(c);
						}
						if(keyStr!=null){
							ret = keyStr;
						}
					}
				}
			}
			else{
				for(int c=0; c<128; c++){
					String keyStr = null;
					if(GUI.key[c]>1){
						if(c==0) keyStr = Integer.toString('↑');
						else if(c==1) keyStr = Integer.toString('↓');
						else if(c==2) keyStr = Integer.toString('←');
						else if(c==3) keyStr = Integer.toString('→');
						else if(c==20) keyStr = Integer.toString('⌫');
						else if(c==21) keyStr = Integer.toString('⌦');
						else if(c==22) keyStr = Integer.toString('⎋');
						if(c>=32){
							keyStr = Integer.toString(c);
						}
						if(keyStr!=null){
							ret = keyStr;
						}
					}
				}
			}
			
			 result.theResult = ret;
			 result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("showList")){
			//Power ToolsのshowList XFCN
			if(params.length>=5){
				new GshowList(PCARD.pc, params[0], params[1], false, false, params[4].split(","), 0, 0, 0);
				result.theResult = GshowList.clicked+"\n"+GshowList.selectList;
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("FontExists")){
			if(params.length>=1){
				result.theResult = (Font.decode(params[0]).getFontName().equals(params[0]))?"true":"false";
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("getmonitor")){
			Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
			result.theResult = "32,"+d.width+","+d.height;
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("setmonitor")){
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("getmem")){
			int freesize = (int) (Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
			result.theResult = Integer.toString(freesize);
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("terazza")){
			if(params.length>=1){
				if(params[0].equalsIgnoreCase("init")){
					terazzaAry = new ArrayList<Terazza>();
				}
				else if(params[0].equalsIgnoreCase("pal")){
					//palette?
				}
				else if(params[0].equalsIgnoreCase("make")){
					if(params.length==9){
						int id = Integer.valueOf(params[1]);
						int speed = Integer.valueOf(params[2]);
						int pictnum = 0;
						int pictnums = 0;
						int pictnume = 0;
						if(params[3].contains("/")){
							String[] picStr = params[3].split("/");
							if(picStr.length>0 && picStr[0].length()>0){
								pictnums = 1000+Integer.valueOf(picStr[0]);
							}
							if(picStr.length>1 && picStr[1].length()>0){
								pictnum = 1000+Integer.valueOf(picStr[1]);
							}
							if(picStr.length>2 && picStr[2].length()>0){
								pictnume = 1000+Integer.valueOf(picStr[2]);
							}
						}else {
							pictnum = 1000+Integer.valueOf(params[3]);
							pictnums = pictnum;
							pictnume = pictnum;
						}
						int pointh = Integer.valueOf(params[4].split(",")[0]);
						int pointv = Integer.valueOf(params[4].split(",")[1]);
						int pointeh = 0;
						int pointev = 0;
						if(params[6].length()>0) {
							pointeh = Integer.valueOf(params[6].split(",")[0]);
							pointev = Integer.valueOf(params[6].split(",")[1]);
						}
						boolean visible = params[8].equalsIgnoreCase("true");
						
						Terazza trz = new Terazza();
						trz.id = id;
						trz.speed = speed;
						trz.time = 0;
						trz.pictnum = pictnum;
						trz.pictnums = pictnums;
						trz.pictnume = pictnume;
						trz.h = pointh;
						trz.v = pointv;
						trz.eh = pointeh;
						trz.ev = pointev;
						trz.visible = visible;
						terazzaAry.add(trz);
					}
				}
				else if(params[0].equalsIgnoreCase("start")){
					for(int i=1; i<params.length; i++){
						int id = Integer.valueOf(params[i]);
						for(int j=0; j<terazzaAry.size(); j++){
							Terazza trz = terazzaAry.get(j);
							if(trz.id == id) {
								String path = PCARD.pc.stack.rsrc.getFilePathAll(trz.pictnum, "picture");
								BufferedImage bi = null;
								if(path!=null){
									//String path = PCARD.pc.stack.file.getParent()+File.separatorChar+fileName;
									bi = PictureFile.loadPICT(path);
								}
								if(bi != null){
									PCARD.pc.mainPane.getGraphics().drawImage(bi, trz.h, trz.v, PCARD.pc);
								}
								trz.visible = true;
								trz.time = new Date().getTime();
								break;
							}
						}
					}
				}
				else if(params[0].equalsIgnoreCase("move")){
					//
				}
				else if(params[0].equalsIgnoreCase("quit")){
					PCARD.pc.mainPane.repaint();
					terazzaAry = null;
				}
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("enviro")){
			result.theResult = "32";
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("addcolor")){
			/*String paramStr = "";
			for(int i=0; i<params.length; i++){
				paramStr += params[i]+",";
			}
			System.out.println("addcolor "+paramStr);*/
			
			if(params.length>=1 && params[0].equalsIgnoreCase("remove")){
				AddColor.remove();
			}
			else if(params.length>1 && params[0].equalsIgnoreCase("disableObject")){
				int number = 1;
				if(params.length>2){
					number = Integer.valueOf(params[2]);
				}
				Rsrc.setEnabledACObj(number-1, false);
			}
			else if(params.length>1 && params[0].equalsIgnoreCase("enableObject")){
				int number = 1;
				if(params.length>2){
					number = Integer.valueOf(params[2]);
				}
				Rsrc.setEnabledACObj(number-1, true);
			}
			else if(params.length>1 && params[0].equalsIgnoreCase("colorCard")){
				int time = 30;
				if(params.length>2){
					time = Integer.valueOf(params[2]);
				}
				if(params.length>1){
					AddColor.colorCard(params[1], time);
				}
				else{
					AddColor.colorCard("fromtop", 0);
				}
			}
			else if(params.length>=1 && params[0].equalsIgnoreCase("colorPict")){
				if(params.length>=2 && params[1]=="cd"){
					
				}
				String pict = "";
				if(params.length>=3){
					pict = params[2];
				}
				Point p = new Point(0,0);
				if(params.length>=4){
					String[] points = params[3].split(",");
					if(points.length==2){
						p.x = Integer.valueOf(points[0]);
						p.y = Integer.valueOf(points[1]);
					}
				}
				String mode = "o";
				if(params.length>=5){
					 mode = params[4];
				}
				String effect = "fromtop";
				if(params.length>=6){
					effect = params[5];
				}
				int time = 0;
				if(params.length>=7){
					time = Integer.valueOf(params[6]);
				}
				AddColor.colorPict(pict, p, null,mode, effect, time);
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("colorizehc")){
			String paramStr = "";
			for(int i=0; i<params.length; i++){
				paramStr += params[i]+",";
			}
			System.out.println("colorizehc "+paramStr);
			
			if(params.length>=1 && (params[0].equalsIgnoreCase("dispose") || params[0].equalsIgnoreCase("new"))){
				AddColor.remove();
			}
			if(params.length>=1 && 
					(params[0].equalsIgnoreCase("add") || params[0].equalsIgnoreCase("new")))
			{
				String pict = "";
				if(params.length>=2){
					pict = params[1];
				}
				Rectangle srcRect = new Rectangle(0,0,0,0);
				if(params.length>=3 && params[2].length()>0){
					getRect(params[2],srcRect);
				}
				
				AddColor.colorPict(pict, null, srcRect, "o", "Copy", 0);
			}
			else if(params.length>=1 && 
					params[0].equalsIgnoreCase("colorfill"))
			{
				Rectangle srcRect = new Rectangle(0,0,0,0);
				if(params.length>=2 && params[1].length()>0){
					getRect(params[1],srcRect);
				}
				Color color = null;
				if(params.length>=3 && params[2].length()>0){
					color = getColor(params[2]);
				}
				
				AddColor.colorFill(color, srcRect, "o", "Copy", 0);
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("mtpickuplines")){ //for unyo2
			String resStr = "";
			String[] srcText = params[0].split("\n");
			for(int i=0; i<srcText.length; i++){
				if(srcText[i].contains(params[1])){
					if(resStr.length()>0) resStr+="\n";
					resStr += srcText[i];
				}
			}
			result.theResult = resStr;
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("captureanimegif")){
			if(params.length>=1 && params[0].equalsIgnoreCase("start")){
				Rectangle rect = PCARD.pc.getBounds();
				if(params.length>=2 && params[1].split(",").length==4){
					String[] rectStr = params[1].split(",");
					rect.x = Integer.valueOf(rectStr[0]);
					rect.y = Integer.valueOf(rectStr[1]);
					rect.width = Integer.valueOf(rectStr[2])-rect.x;
					rect.height = Integer.valueOf(rectStr[3])-rect.y;
				}
				animegifThread = new CaptureAnimationGIF(rect);
				animegifThread.start();
			}
			else{
				if(animegifThread!=null){
					animegifThread.endFlag = true;
				}
			}
		}
		else if(0==message.compareToIgnoreCase("senttcp")){
			if(params.length<1){
			}
			else if(params[0].equalsIgnoreCase("listen")){
				//sentTCP "listen",localPort
				System.out.println("SentTCP:listen unimplement");
			}
			else if(params.length>=3 && params[0].equalsIgnoreCase("connect")){
				//sentTCP "connect",remoteIP,remotePort
				//String encode = "US-ASCII";
				//if(params.length>=4){
				//	encode = params[3];
				//}
				int conID = -1;
				try {
					Socket socket = new Socket(params[1], Integer.valueOf(params[2]));
					conID = sentTCP.addSentTCP(socket);
			        /*BufferedReader reader = new BufferedReader
			            (new InputStreamReader(socket.getInputStream(), encode));
			        OutputStream output = socket.getOutputStream();
			        output.write("HELO\r\n".getBytes("US-ASCII"));
			        
			        System.out.println(reader.readLine());*/
				} catch (NumberFormatException e) {
					e.printStackTrace();
				} catch (UnknownHostException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}

				result.theResult = Integer.toString(conID);
				result.ret = 0;
			}
			else if(params.length>=3 && params[0].equalsIgnoreCase("send")){
				//sentTCP "send",connectionID,dataToSend[,encode]
				String encode = "US-ASCII";
				if(params.length>=4){
					encode = params[3];
				}
				int conID = Integer.valueOf(params[1]);
				if(conID<0 || conID>=sentTCPAry.length){
					System.out.println("SentTCP:connectionID failure");
					result.theResult = Integer.toString(-1);
					result.ret = 0;
				}
				else{
					try {
						Socket socket = sentTCPAry[conID].socket;
				        /*BufferedReader reader = new BufferedReader
				            (new InputStreamReader(socket.getInputStream(), encode));*/
				        OutputStream output = socket.getOutputStream();
				        output.write(params[2].getBytes(encode));
				        
				        /*System.out.println(reader.readLine());*/
					} catch (NumberFormatException e) {
						e.printStackTrace();
					} catch (UnknownHostException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			else if(params.length>=3 && params[0].equalsIgnoreCase("receive")){
				//sentTCP "receive",connectionID[,termChar,timeout,encode]
				String encode = "US-ASCII";
				if(params.length>=4){
					encode = params[3];
				}
				String termChar = "";
				if(params.length>=2 && params[1].length()>0){
					termChar = params[1];
				}
				int timeout = Integer.MAX_VALUE;
				if(params.length>=3 && params[2].length()>0){
					timeout = Integer.valueOf(params[2]);
				}
				int conID = Integer.valueOf(params[1]);
				if(conID<0 || conID>=sentTCPAry.length){
					System.out.println("SentTCP:connectionID failure");
					result.theResult = Integer.toString(-1);
					result.ret = 0;
				}
				else{
					int start = (int)new Date().getTime()/10*3/5;
					while((int)new Date().getTime()/10*3/5>start+timeout){
						try {
							Socket socket = sentTCPAry[conID].socket;
					        BufferedReader reader = new BufferedReader
					            (new InputStreamReader(socket.getInputStream(), encode));
					        
					        String receive = reader.readLine();
					        result.theResult += receive;
					        if(receive.contains(termChar)){
					        	break;
					        }
						} catch (NumberFormatException e) {
							e.printStackTrace();
						} catch (UnknownHostException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
			else if(params.length>=3 && params[0].equalsIgnoreCase("status")){
				//sentTCP "status",connectionID
				int conID = Integer.valueOf(params[1]);
				if(conID<0 || conID>=sentTCPAry.length){
					System.out.println("SentTCP:connectionID failure");
					result.theResult = Integer.toString(-1);
					result.ret = 0;
				}
				else if(sentTCPAry[conID].listen){
					System.out.println("SentTCP:listen unimplement2");
				}
				else{
					Socket socket = sentTCPAry[conID].socket;
					if(socket.isClosed()){
						result.theResult = "closed";
					}
					else if(socket.isConnected()){
						result.theResult = "established";//てけとー
					}
					else{
						result.theResult = "opening";//てけとー
					}
				}
			}
			else if(params.length>=3 && params[0].equalsIgnoreCase("close")){
				//sentTCP "close",connectionID
				int conID = Integer.valueOf(params[1]);
				if(conID<0 || conID>=sentTCPAry.length){
					System.out.println("SentTCP:connectionID failure");
					result.theResult = Integer.toString(-1);
					result.ret = 0;
				}
				else{
					Socket socket = sentTCPAry[conID].socket;
					if(socket.isClosed()){
					}
					else {
						try {
							socket.close();
						} catch (IOException e) {
							e.printStackTrace();
							result.theResult = Integer.toString(-2);
						}
					}
				}
			}
			else if(params.length>=3 && params[0].equalsIgnoreCase("nameToAddr")){
				//sentTCP "nameToAddr",name
				try {
					InetAddress ia = InetAddress.getByName(params[1]);
					byte [] bs = ia.getAddress();
					for(int i=0; i<bs.length; i++){
						result.theResult += bs[i];
						if(i+1<bs.length)result.theResult += ".";
					}
				} catch (UnknownHostException e) {
					e.printStackTrace();
				}
			}
			else{
				System.out.println("SentTCP:未実装のコマンド:"+params[0]);
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("pgcolorx") || 0==message.compareToIgnoreCase("mirage")){
			if(params.length>=1){
				Point p = new Point();
				if(params[0].equalsIgnoreCase("new")){
					int port = Integer.valueOf(params[1]);
					if(getSize(params[2],p)){
						//rect/size
						PgColorX[port] = txcmd.new PgColorXBuf(p);
					}
					else {
						BufferedImage bi = null;
						if(params[2].matches("^[0-9]*$")){
							bi = PCARD.pc.stack.rsrc.getImage(Integer.valueOf(params[2]), "picture");
						}
						else{
							int id = PCARD.pc.stack.rsrc.getRsrcIdAll(params[2], "picture");
							bi = PCARD.pc.stack.rsrc.getImage(id, "picture");
						}
						if(bi!=null){
							p.x = bi.getWidth();
							p.y = bi.getHeight();
							PgColorX[port] = txcmd.new PgColorXBuf(p);
							PgColorX[port].bi = bi;
						}
						else{
							result.theResult = "Error: Can't read Picture resource "+params[2]+".";
						}
					}
				}
				else if(params[0].equalsIgnoreCase("CopyBits") || params[0].equalsIgnoreCase("BufToBuf")){
					//PgColorX "CopyBits",転送元port,転送先port,<*転送元rect>,<*転送先topleft/rect>,<*mode>,<*opColor>
					
					if(params.length>=5 && params[4].split("\n").length>=2){
						//改行で繋げて高速化とか厄介なことを・・・
						String[] srcparam = params[4].split("\n");
						String[] newparams = new String[params.length];
						for(int i=0; i<srcparam.length; i++){
							String[] tmpparam = params[1].split("\n");
							newparams[1] = tmpparam[i%tmpparam.length];
							tmpparam = params[2].split("\n");
							newparams[2] = tmpparam[i%tmpparam.length];
							if(params.length>=4 && params[3]!=null){
								tmpparam = params[3].split("\n");
								newparams[3] = tmpparam[i%tmpparam.length];
							}
							if(params.length>=5 && params[4]!=null){
								tmpparam = params[4].split("\n");
								newparams[4] = tmpparam[i%tmpparam.length];
							}
							if(params.length>=6 && params[5]!=null){
								tmpparam = params[5].split("\n");
								newparams[5] = tmpparam[i%tmpparam.length];
							}
							if(params.length>=7 && params[6]!=null){
								tmpparam = params[6].split("\n");
								newparams[6] = tmpparam[i%tmpparam.length];
							}
							PgColorX[0].CopyBits(newparams);
						}
					}
					else{
						PgColorX[0].CopyBits(params);
					}
				}
				else if(params[0].equalsIgnoreCase("OnDraw")){
					//PgColorX "OnDraw",転送元port,<*転送元rect>,<*転送先topleft/rect>,<*mode>,<*opColor>
					int srcport = getPort(params[1]);
					
					Rectangle srcRect;
					if(params.length>=3 && params[2].length()>0){
						srcRect = new Rectangle();
						getRect(params[2],srcRect);
					}
					else{
						srcRect = new Rectangle(0,0,PgColorX[srcport].bi.getWidth(),PgColorX[srcport].bi.getHeight());
					}

					Rectangle dstRect;
					dstRect = new Rectangle(0,0,srcRect.width,srcRect.height);
					if(params.length>=4 && params[3].length()>0){
						getRect(params[3],dstRect);
					}
					
					String mode = "copy";
					if(params.length>=5 && params[4].length()>0){
						mode = params[4];
					}
					
					BufferedImage srcbi=null;
					if(srcport>=0){
						srcbi = PgColorX[srcport].bi;
					}
					else if(srcport==-1){
						VEffect.setOldOff();
						srcbi = VEffect.oldoff;
					}
					
					Graphics2D dstGraphics=null;
					dstGraphics = (Graphics2D) PCARD.pc.mainPane.getGraphics();
					
					if(mode.equalsIgnoreCase("transparent") && PgColorX[srcport].colorKey==null){
						PgColorX[srcport].colorKey = Color.WHITE;
						PgColorX[srcport].setTransparent();
					}
					
					dstGraphics.drawImage(srcbi, 
							dstRect.x,dstRect.y,dstRect.x+dstRect.width,dstRect.y+dstRect.height,
							srcRect.x,srcRect.y,srcRect.x+srcRect.width,srcRect.y+srcRect.height, PCARD.pc);
				}
				else if(params[0].equalsIgnoreCase("Tiling")){
					//PgColorX "Tiling",転送元port,転送先port,<*転送元rect>,<*転送先topleft/rect>,<*mode>,<*opColor>
					int srcport = getPort(params[1]);
					int dstport = getPort(params[2]);
					
					Rectangle srcRect;
					if(params.length>=4 && params[3].length()>0){
						srcRect = new Rectangle();
						getRect(params[3],srcRect);
					}
					else{
						srcRect = new Rectangle(0,0,PgColorX[srcport].bi.getWidth(),PgColorX[srcport].bi.getHeight());
					}

					Rectangle dstRect;
					if(dstport>=0){
						dstRect = new Rectangle(0,0,PgColorX[dstport].bi.getWidth(),PgColorX[dstport].bi.getHeight());
					}
					else{
						dstRect = new Rectangle(0,0,PCARD.pc.mainPane.getWidth(),PCARD.pc.mainPane.getHeight());
					}
					if(params.length>=5 && params[4].length()>0){
						getRect(params[4],dstRect);
					}
					
					String mode = "copy";
					if(params.length>=6 && params[5].length()>0){
						mode = params[5];
					}
					
					BufferedImage srcbi=null;
					if(srcport>=0){
						srcbi = PgColorX[srcport].bi;
					}
					else if(srcport==-1){
						VEffect.setOldOff();
						srcbi = VEffect.oldoff;
					}
					
					Graphics2D dstGraphics=null;
					if(dstport>=0){
						dstGraphics = PgColorX[dstport].bi.createGraphics();
						PgColorX[dstport].colorKey = null;
					}
					else if(dstport==-1){
						dstGraphics = (Graphics2D) PCARD.pc.mainPane.getGraphics();
					}
					
					if(mode.equalsIgnoreCase("transparent") && PgColorX[srcport].colorKey==null){
						PgColorX[srcport].colorKey = Color.WHITE;
						PgColorX[srcport].setTransparent();
					}

					for(int y=dstRect.y; y<dstRect.y+dstRect.height; y+=srcRect.height){
						for(int x=dstRect.x; x<dstRect.x+dstRect.width; x+=srcRect.width){
							dstGraphics.drawImage(srcbi, 
									x,y,x+srcRect.width,y+srcRect.height,
									srcRect.x,srcRect.y,srcRect.x+srcRect.width,srcRect.y+srcRect.height, PCARD.pc);
						}
					}
				}
				else if(params[0].equalsIgnoreCase("PicFont")){
					//PgColorX "PicFont",転送元port,転送先port,string,<*転送先topleft>,<*mode>,<*opColor>
					int srcport = getPort(params[1]);
					int dstport = getPort(params[2]);
					
					String text = "";
					if(params.length>=4 && params[3].length()>0){
						text = params[3];
					}
					Rectangle srcRect = new Rectangle(0,0,PgColorX[srcport].bi.getWidth(),PgColorX[srcport].bi.getHeight());

					Rectangle dstRect;
					if(dstport>=0){
						dstRect = new Rectangle(0,0,PgColorX[dstport].bi.getWidth(),PgColorX[dstport].bi.getHeight());
					}
					else{
						dstRect = new Rectangle(0,0,PCARD.pc.mainPane.getWidth(),PCARD.pc.mainPane.getHeight());
					}
					if(params.length>=5 && params[4].length()>0){
						getRect(params[4],dstRect);
					}
					
					String mode = "copy";
					if(params.length>=6 && params[5].length()>0){
						mode = params[5];
					}
					
					BufferedImage srcbi=null;
					if(srcport>=0){
						srcbi = PgColorX[srcport].bi;
					}
					else if(srcport==-1){
						VEffect.setOldOff();
						srcbi = VEffect.oldoff;
					}
					
					Graphics2D dstGraphics=null;
					if(dstport>=0){
						dstGraphics = PgColorX[dstport].bi.createGraphics();
						PgColorX[dstport].colorKey = null;
					}
					else if(dstport==-1){
						dstGraphics = (Graphics2D) PCARD.pc.mainPane.getGraphics();
					}
					
					if(mode.equalsIgnoreCase("transparent") && PgColorX[srcport].colorKey==null){
						PgColorX[srcport].colorKey = Color.WHITE;
						PgColorX[srcport].setTransparent();
					}

					for(int i=0; i<text.length(); i++){
						char c = text.charAt(i);
						if(c<32||c>=128) continue;
						dstGraphics.drawImage(srcbi,
								dstRect.x, dstRect.y, dstRect.x+srcRect.width/8, dstRect.y+srcRect.height/12,
								c%8*srcRect.width/8,(c-32)/8*srcRect.height/12,(c%8+1)*srcRect.width/8,((c-32)/8+1)*srcRect.height/12, PCARD.pc);
						dstRect.x += srcRect.width/8;
					}
				}
				else if(params[0].equalsIgnoreCase("Pict") || params[0].equalsIgnoreCase("Add") || params[0].equalsIgnoreCase("LoadPict")){
					//PgColorX "Pict",port,<PICT Name/ID>,<*topleft/rect/"original">
					int port = getPort(params[1]);
					BufferedImage bi = null;
					
					if(params[2].matches("^[0-9]*$")){
						bi = PCARD.pc.stack.rsrc.getImage(Integer.valueOf(params[2]), "picture");
					}
					else{
						int id = PCARD.pc.stack.rsrc.getRsrcIdAll(params[2], "picture");
						bi = PCARD.pc.stack.rsrc.getImage(id, "picture");
					}

					if(bi!=null){
						Rectangle rect;
						rect = new Rectangle(0,0,bi.getWidth(),bi.getHeight());
						if(params.length>=4 && params[3].length()>0){
							getRect(params[3],rect);
						}
						
						Graphics2D dstGraphics=null;
						if(port>=0){
							dstGraphics = PgColorX[port].bi.createGraphics();
							PgColorX[port].colorKey = null;
						}
						else if(port==-1){
							dstGraphics = (Graphics2D) PCARD.pc.mainPane.getGraphics();
						}
					
						dstGraphics.drawImage(bi, 
								rect.x,rect.y,rect.x+rect.width,rect.y+rect.height,
								0,0,bi.getWidth(),bi.getHeight(), PCARD.pc);
					}
				}
				else if(params[0].equalsIgnoreCase("String") || params[0].equalsIgnoreCase("DrawString")){
					//PgColorX "String",port,<text>,<color>,<topleft>,<*mode>,<*opColor>
					//PgColorX "DrawString",<text>,<color>,<topleft>,<*mode>,<*opColor>
					
					int offset = 0;
					int port = -1;
					if(params[0].equalsIgnoreCase("String")) {
						port = getPort(params[1]);
						offset++;
					}
					String text = params[offset+1];
					Color color = getColor(params[offset+2]);
					
					Point topleft = new Point(0,0);
					if(params.length>=offset+4){
						getSize(params[offset+3], topleft);
					}
					String mode = "copy";
					if(params.length>=offset+5){
						mode = params[offset+4];
					}
					
					Graphics2D dstGraphics=null;
					if(port>=0){
						dstGraphics = PgColorX[port].bi.createGraphics();
						PgColorX[port].colorKey = null;
					}
					else if(port==-1){
						dstGraphics = (Graphics2D) PCARD.pc.mainPane.getGraphics();
					}
					
					Font font = new Font(PCARD.pc.textFont, PCARD.pc.textStyle, PCARD.pc.textSize);
					dstGraphics.setFont(font);

					if(mode.equalsIgnoreCase("copy")){
			        	FontMetrics fo = dstGraphics.getFontMetrics();
						int strWidth = fo.stringWidth(text);
						dstGraphics.setColor(Color.WHITE);
						dstGraphics.fillRect(topleft.x, topleft.y, topleft.x+strWidth, topleft.y+PCARD.pc.textSize);
					}
					
					dstGraphics.setColor(color);
					dstGraphics.drawString(text, topleft.x, topleft.y+PCARD.pc.textSize);
				}
				else if(params[0].equalsIgnoreCase("Kill")){
					if(params.length>=2){
						int port = getPort(params[1]);
						PgColorX[port] = null;
					}
					else{
						for(int port=0; port<PgColorX.length; port++){
							PgColorX[port] = null;
						}
					}
				}
				else{
					System.out.println("PgColorX:未実装のコマンド:"+params[0]);
				}
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("uxanswer")){
			//[Are you Muscle?, "1010, 1013, 8", 400, NO!, MUSCLE!]
			String str = params[0];
			int[] icon = null;
			if(params.length>=2 && params[1].length()>0){
				String[] iconsStr = params[1].split(",");
				icon = new int[iconsStr.length];
				for(int i=0; i<iconsStr.length; i++){
					while(iconsStr[i].charAt(0)==' '){
						iconsStr[i] = iconsStr[i].substring(1);
					}
					while(iconsStr[i].charAt(iconsStr[i].length()-1)==' '){
						iconsStr[i] = iconsStr[i].substring(0,iconsStr[i].length()-1);
					}
					icon[i] = Integer.valueOf(iconsStr[i]);
				}
			}
			int iconwait = 0;
			if(icon!=null && icon.length>=3){
				iconwait = icon[icon.length-1];
			}
			int time = 0;
			if(params.length>=3 && params[2].length()>0){
				time = Integer.valueOf(params[2]);
			}
			String[] Buttons = new String[params.length-3];
			for(int i=0; i<params.length-3; i++){
				if(params.length>=4+i && params[3+i].length()>0){
					Buttons[i] = params[3+i];
				}
			}
			
			new GUxAnswer(PCARD.pc, str, null, icon, iconwait, time, Buttons);
			TTalk.setVariable(memData, "it", GUxAnswer.clicked);
			
			result.theResult = "";
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("MtFilesInFldr")){
			//MtFilesInFldr(folderpath,"T:STAK")
			String parentpath = params[0];
			/*String which = null;
			if(params.length>=2){
				which = params[1];
			}*/
			
			File parent = new File(parentpath);
			if(parent.isDirectory()){
				result.theResult = "";
				File[] files = parent.listFiles();
				for(int i=0; i<files.length; i++){
					result.theResult += files[i].getPath();
					if(i+1<files.length){
						result.theResult += "\n";
					}
				}
			}
			else{
				result.theResult = "Error: It is not Directory.";
			}
			
			result.ret = 0;
		}
		/*else if(0==message.compareToIgnoreCase("shell")){
			ProcessBuilder pb = new ProcessBuilder(params[0], params[1], params[2], params[3],
					params[4], params[5], params[6], params[7], params[8], params[9],
					params[10], params[11], params[12], params[13], params[14], params[15]);
			try {
				Process p = pb.start();
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}*/
		else if(0==message.compareToIgnoreCase("UxSetDialogFont")){
			//何もしない
		}
		else if(0==message.compareToIgnoreCase("UxShowHideCS")){
			//コントロールストリップ? 何もしない
		}
		else
		{
			System.out.println("未実装のXCMD/XFCN:"+message);
			result.ret = 0;
			throw new xTalkException("未実装のXCMD/XFCN:"+message);
		}
		
		return result;
	}

	static sentTCP[] sentTCPAry = new sentTCP[128];
	static class sentTCP {
		Socket socket;
		boolean listen;
		
		static int addSentTCP(Socket socket){
			for(int i=0; i<sentTCPAry.length; i++){
				if(sentTCPAry[i]==null){
					sentTCPAry[i]=new sentTCP();
					sentTCPAry[i].socket = socket;
					return i;
				}
			}
			return -1;
		}
	}
	
	static boolean getSize(String param, Point p){
		if(param.indexOf(",")==-1){
			return false;
		}
		else if(param.matches("^-{0,1}[0-9]*,-{0,1}[0-9]*$")){
			String[] ps = param.split(",");
			p.x = Integer.valueOf(ps[0]);
			p.y = Integer.valueOf(ps[1]);
			return true;
		}
		else if(param.matches("^-{0,1}[0-9]*,-{0,1}[0-9]*,-{0,1}[0-9]*,-{0,1}[0-9]*$")){
			String[] ps = param.split(",");
			p.x = Integer.valueOf(ps[2])-Integer.valueOf(ps[0]);
			p.y = Integer.valueOf(ps[3])-Integer.valueOf(ps[1]);
			return true;
		}
		return false;
	}

	static boolean getRect(String param, Rectangle r){
		if(param.matches("^-{0,1}[0-9\\.]*,-{0,1}[0-9\\.]*$")){
			String[] ps = param.split(",");
			r.x = Double.valueOf(ps[0]).intValue();
			r.y = Double.valueOf(ps[1]).intValue();
			return true;
		}
		else if(param.matches("^-{0,1}[0-9\\.]*,-{0,1}[0-9\\.]*,-{0,1}[0-9\\.]*,-{0,1}[0-9\\.]*$")){
			String[] ps = param.split(",");
			r.x = Double.valueOf(ps[0]).intValue();
			r.y = Double.valueOf(ps[1]).intValue();
			r.width = Double.valueOf(ps[2]).intValue()-r.x;
			r.height = Double.valueOf(ps[3]).intValue()-r.y;
			return true;
		}
		return false;
	}

	static Color getColor(String param){
		if(param.matches("^{0,1}[0-9]*,{0,1}[0-9]*,{0,1}[0-9]*$")){
			String[] ps = param.split(",");
			int r = Integer.valueOf(ps[0]);
			int g = Integer.valueOf(ps[1]);
			int b = Integer.valueOf(ps[2]);
			Color color = new Color(r/256,g/256,b/256);
			return color;
		}
		else if(param.matches("^#[0-9A-Fa-f]{6}$")){
			int r = Integer.valueOf(param.substring(1,2),16);
			int g = Integer.valueOf(param.substring(3,4),16);
			int b = Integer.valueOf(param.substring(5,6),16);
			Color color = new Color(r,g,b);
			return color;
		}
		else if(param.equalsIgnoreCase("white")){
			return Color.WHITE;
		}
		else if(param.equalsIgnoreCase("black")){
			return Color.BLACK;
		}
		else if(param.equalsIgnoreCase("red")){
			return Color.RED;
		}
		else if(param.equalsIgnoreCase("green")){
			return Color.GREEN;
		}
		else if(param.equalsIgnoreCase("blue")){
			return Color.BLUE;
		}
		else if(param.equalsIgnoreCase("yellow")){
			return Color.YELLOW;
		}
		else if(param.equalsIgnoreCase("orange")){
			return Color.ORANGE;
		}
		else if(param.equalsIgnoreCase("gray")){
			return Color.GRAY;
		}
		else if(param.equalsIgnoreCase("pink")){
			return Color.PINK;
		}
		return Color.BLACK;
	}

	static int getPort(String param){
		if(param.matches("^{0,1}[0-9]*$")){
			return Integer.valueOf(param);
		}
		else if(param.equalsIgnoreCase("cd") || param.equalsIgnoreCase("card") || param.equalsIgnoreCase("direct") || param.equalsIgnoreCase("dir")){
			return -1;
		}
		else if(param.equalsIgnoreCase("cd buf") ){
			return -2;
		}
		else if(param.equalsIgnoreCase("pri")){
			return -3;
		}
		else if(param.equalsIgnoreCase("cd alpha")){
			return -4;
		}
		return 0;
	}
	
	//PgColorX
	static PgColorXBuf[] PgColorX = new PgColorXBuf[128];
	class PgColorXBuf {
		BufferedImage bi;
		Color colorKey;
		
		PgColorXBuf(Point p){
			bi = new BufferedImage(p.x, p.y, BufferedImage.TYPE_INT_ARGB);
		}
		
		public void CopyBits(String[] params) {
			int srcport = getPort(params[1]);
			int dstport = getPort(params[2]);
			
			Rectangle srcRect;
			if(params.length>=4 && params[3].length()>0){
				srcRect = new Rectangle();
				getRect(params[3],srcRect);
			}
			else{
				srcRect = new Rectangle(0,0,PgColorX[srcport].bi.getWidth(),PgColorX[srcport].bi.getHeight());
			}
	
			Rectangle dstRect;
			dstRect = new Rectangle(0,0,srcRect.width,srcRect.height);
			if(params.length>=5 && params[4].length()>0){
				getRect(params[4],dstRect);
			}
			
			String mode = "copy";
			if(params.length>=6 && params[5].length()>0){
				mode = params[5];
			}
			
			BufferedImage srcbi=null;
			if(srcport>=0){
				srcbi = PgColorX[srcport].bi;
			}
			else if(srcport==-1){
				VEffect.setOldOff();
				srcbi = VEffect.oldoff;
			}
			
			Graphics2D dstGraphics=null;
			if(dstport>=0){
				dstGraphics = PgColorX[dstport].bi.createGraphics();
				PgColorX[dstport].colorKey = null;
			}
			else if(dstport==-1){
				dstGraphics = (Graphics2D) PCARD.pc.mainPane.getGraphics();
			}
			
			if(mode.equalsIgnoreCase("transparent") && PgColorX[srcport].colorKey==null){
				PgColorX[srcport].colorKey = Color.WHITE;
				PgColorX[srcport].setTransparent();
			}
			
			dstGraphics.drawImage(srcbi, 
					dstRect.x,dstRect.y,dstRect.x+dstRect.width,dstRect.y+dstRect.height,
					srcRect.x,srcRect.y,srcRect.x+srcRect.width,srcRect.y+srcRect.height, PCARD.pc);
		}
	
		void setTransparent(){
			int c = 0xFF000000 + (colorKey.getRed()<<16) + (colorKey.getGreen()<<8) + colorKey.getBlue();
			
			DataBuffer db = bi.getRaster().getDataBuffer();
			int width = bi.getWidth();
			int height = bi.getHeight();
			for(int y=0; y<height; y++){
				for(int x=0; x<width; x++){
					if(db.getElem(x+y*width)==c){
						db.setElem(x+y*width,0);
					}
				}
			}
		}
	}
	
	//AddColor
	static class AddColor{
		static void remove(){
			if(addColorPanel!=null){
				PCARD.pc.mainPane.remove(addColorPanel);
				addColorPanel = null;
			}
			PCARD.pc.stack.addColor = null;
		}
		
		
		static void colorCard(String effect, int time){
			//addcolorClassを探す
			ArrayList<Rsrc.addcolorClass> cdList = PCARD.pc.stack.rsrc.addcolorList;
			Rsrc.addcolorClass accd = null;
			for(int i=0; i<cdList.size(); i++){
				if(PCARD.pc.stack.curCard.id == cdList.get(i).id){
					accd = cdList.get(i);
					break;
				}
			}
			if(accd==null) return;

			//addcolorClassにあるオブジェクトを描画
			BufferedImage bi = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB);

			ArrayList<Rsrc.addcolorObjClass> objList = accd.objList;
			Rsrc.addcolorObjClass obj = null;
			for(int i=0; i<objList.size(); i++){
				obj = objList.get(i);
				if(!obj.visible) continue;
				if(obj.getClass()==Rsrc.PictObject.class){
					Rsrc.PictObject picobj = (Rsrc.PictObject)obj;
					int pictid = PCARD.pc.stack.rsrc.getRsrcIdAll(picobj.name, "picture");
					String path = PCARD.pc.stack.rsrc.getFilePathAll(pictid, "picture");
					//String path = (PCARD.pc.stack.file.getParent()+File.separatorChar+fileName);
					BufferedImage pict = PictureFile.loadPICT(path);
					if(pict!=null){
						bi.getGraphics().drawImage(pict, picobj.rect.x, picobj.rect.y, null);
					}
				}
				if(obj.getClass()==Rsrc.FldObject.class){
					Rsrc.FldObject fldobj = (Rsrc.FldObject)obj;
					Graphics g = bi.getGraphics();
					g.setColor(fldobj.color);
					OField fld = PCARD.pc.stack.curCard.GetFldbyId(fldobj.id);
					g.fillRect(fld.left, fld.top, fld.width, fld.height);
				}
				if(obj.getClass()==Rsrc.BtnObject.class){
					Rsrc.BtnObject btnobj = (Rsrc.BtnObject)obj;
					Graphics g = bi.getGraphics();
					g.setColor(btnobj.color);
					OButton btn = PCARD.pc.stack.curCard.GetBtnbyId(btnobj.id);
					g.fillRect(btn.left, btn.top, btn.width, btn.height);
				}
				if(obj.getClass()==Rsrc.RectObject.class){
					Rsrc.RectObject rectobj = (Rsrc.RectObject)obj;
					Graphics g = bi.getGraphics();
					g.setColor(rectobj.color);
					((Graphics2D)g).fill(rectobj.rect);
				}
			}


			VEffect.setOldOff();
			
			//AddColorの表示用コンポーネントを追加
			addToMainPane();
			
			//裏画面を表画面に描画
			PCARD.pc.stack.addColor = bi;
			//PCARD.pc.mainPane.repaint();

			int speed;
			if(time<15) speed = 1;
			else if(time<30) speed = 2;
			else if(time<60) speed = 3;
			else if(time<120) speed = 4;
			else speed = 5;
			effect = convAddColorEffect(effect);
			ArrayList<String> effList = new ArrayList<String>();
			String[] effects = effect.split(" ");
			for(int i=0; i<effects.length; i++){
				effList.add(effects[i]);
			}
			try {
				VEffect.visualEffect(0xFF&TUtil.getVisualMode(effList,0,0), 0, speed);
			} catch (xTalkException e) {
			}
		}

		
		static void colorPict(String pict, Point p, Rectangle r, String mode, String effect, int time){
			//裏画面を準備
			BufferedImage bi = PCARD.pc.stack.addColor;
			if(bi==null){
				bi = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB);
			}

			VEffect.setOldOff();
			
			//PICTを描画
			int pictid = PCARD.pc.stack.rsrc.getRsrcIdAll(pict, "picture");
			String path = PCARD.pc.stack.rsrc.getFilePathAll(pictid, "picture");
			//String path = (PCARD.pc.stack.file.getParent()+File.separatorChar+fileName);
			BufferedImage pictbi = PictureFile.loadPICT(path);
			if(pictbi!=null){
				if(p!=null){
					bi.getGraphics().drawImage(pictbi, p.x, p.y, null);
				}
				else if(r!=null){
					bi.getGraphics().drawImage(pictbi, r.x, r.y, r.width, r.height, null);
				}
			}
			
			//AddColorの表示用コンポーネントを追加
			addToMainPane();
			addColorPanel.mode = mode;
			
			//裏画面を表画面に描画
			PCARD.pc.stack.addColor = bi;
			PCARD.pc.mainPane.repaint();
			
			int speed;
			if(time<15) speed = 1;
			else if(time<30) speed = 2;
			else if(time<60) speed = 3;
			else if(time<120) speed = 4;
			else speed = 5;
			effect = convAddColorEffect(effect);
			ArrayList<String> effList = new ArrayList<String>();
			String[] effects = effect.split(" ");
			for(int i=0; i<effects.length; i++){
				effList.add(effects[i]);
			}
			try {
				VEffect.visualEffect(0xFF&TUtil.getVisualMode(effList,0,0), 0, speed);
			} catch (xTalkException e) {
			}
		}
		
		
		static void colorFill(Color color, Rectangle r, String mode, String effect, int time){
			//裏画面を準備
			BufferedImage bi = PCARD.pc.stack.addColor;
			if(bi==null){
				bi = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB);
			}

			VEffect.setOldOff();
			
			Graphics g = bi.getGraphics();
			g.setColor(color);
			g.fillRect(r.x, r.y, r.width, r.height);
			
			//AddColorの表示用コンポーネントを追加
			addToMainPane();
			addColorPanel.mode = mode;
			
			//裏画面を表画面に描画
			PCARD.pc.stack.addColor = bi;
			PCARD.pc.mainPane.repaint();
			
			int speed;
			if(time<15) speed = 1;
			else if(time<30) speed = 2;
			else if(time<60) speed = 3;
			else if(time<120) speed = 4;
			else speed = 5;
			effect = convAddColorEffect(effect);
			ArrayList<String> effList = new ArrayList<String>();
			String[] effects = effect.split(" ");
			for(int i=0; i<effects.length; i++){
				effList.add(effects[i]);
			}
			try {
				VEffect.visualEffect(0xFF&TUtil.getVisualMode(effList,0,0), 0, speed);
			} catch (xTalkException e) {
			}
		}
		
		
		static String convAddColorEffect(String effect){
			if(effect.equalsIgnoreCase("fromLeft")){
				effect="stretch from right";
			}
			if(effect.equalsIgnoreCase("fromRight")){
				effect="stretch from left";
			}
			if(effect.equalsIgnoreCase("fromTop")){
				effect="stretch from top";
			}
			if(effect.equalsIgnoreCase("fromBottom")){
				effect="stretch from bottom";
			}
			if(effect.equalsIgnoreCase("fromTopLeft")){
				effect="stretch from topleft";
			}
			if(effect.equalsIgnoreCase("fromTopRight")){
				effect="stretch from topright";
			}
			if(effect.equalsIgnoreCase("fromBottomLeft")){
				effect="stretch from bottomleft";
			}
			if(effect.equalsIgnoreCase("fromBottomRight")){
				effect="stretch from bottomright";
			}
			if(effect.equalsIgnoreCase("irisOpen")){
				effect="iris open";
			}
			if(effect.equalsIgnoreCase("irisClose")){
				effect="iris close";
			}
			if(effect.equalsIgnoreCase("checkerBoardOpen")){
				effect="checkerboard";
			}
			if(effect.equalsIgnoreCase("checkerBoardClose")){
				effect="checkerboard";
			}
			if(effect.equalsIgnoreCase("circleCheckerOpen")){
				effect="checkerboard";
			}
			if(effect.equalsIgnoreCase("checkerBoardClose")){
				effect="checkerboard";
			}
			if(effect.equalsIgnoreCase("barnDoorOpen")){
				effect="barn door open";
			}
			if(effect.equalsIgnoreCase("barnDoorClose")){
				effect="barn door close";
			}
			if(effect.equalsIgnoreCase("combVertical")){
				effect="venetian blinds";
			}
			if(effect.equalsIgnoreCase("combHorizontal")){
				effect="venetian blinds";
			}
			if(effect.equalsIgnoreCase("rectOpen")){
				effect="iris open";
			}
			if(effect.equalsIgnoreCase("rectClose")){
				effect="iris close";
			}
			if(effect.equalsIgnoreCase("venetianBlindsHorizontal")){
				effect="venetian blinds";
			}
			if(effect.equalsIgnoreCase("venetianBlindsVertical")){
				effect="venetian blinds";
			}
			if(effect.equalsIgnoreCase("rakeHorizOpen")){
				effect="venetian blinds";
			}
			if(effect.equalsIgnoreCase("rakeHorizClose")){
				effect="venetian blinds";
			}
			if(effect.equalsIgnoreCase("rakeVertOpen")){
				effect="venetian blinds";
			}
			if(effect.equalsIgnoreCase("rakeVertClose")){
				effect="venetian blinds";
			}
			
			return effect;
		}
		static public void addToMainPane(){
			if(addColorPanel==null){
				addColorPanel = new AddColorPanel();
				addColorPanel.setBounds(0,0,PCARD.pc.stack.width, PCARD.pc.stack.height);
				addColorPanel.setOpaque(true);
			}
			PCARD.pc.mainPane.remove(addColorPanel);
			PCARD.pc.mainPane.add(addColorPanel, 0);
		}
		
		static AddColorPanel addColorPanel;
		
		//AddColorの表示用コンポーネント。
		//この裏にあるコンポーネントの描画を横取りして、加算合成して表示する
		static class AddColorPanel extends JPanel {
			private static final long serialVersionUID = 1L;
			static boolean lock;
			String mode;
			@Override
			protected void paintComponent(Graphics g){
				if(lock) return;
				BufferedImage offImage = new BufferedImage(PCARD.pc.stack.addColor.getWidth(), PCARD.pc.stack.addColor.getHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D offg = offImage.createGraphics();
				lock = true;
				PCARD.pc.mainPane.paint(offg);
				lock = false;

				DataBuffer offdb = offImage.getRaster().getDataBuffer();
				DataBuffer adddb = PCARD.pc.stack.addColor.getRaster().getDataBuffer();
				int width = PCARD.pc.stack.addColor.getWidth();
				Rectangle cliprect = g.getClipBounds();
				for(int y=cliprect.y; y<cliprect.y+cliprect.height; y++){
					for(int x=cliprect.x; x<cliprect.x+cliprect.width; x++){
						int a = offdb.getElem(0, y*width+x);
						int b = adddb.getElem(0, y*width+x);
						if(a==0xFFFFFFFF || mode!=null && mode.equalsIgnoreCase("o")) offdb.setElem(0, y*width+x, b);
						else{
							int pr = (a>>16)&0xFF + (b>>16)&0xFF;
							if(pr>0xFF) pr = 0xFF;
							int pg = (a>>8)&0xFF + (b>>8)&0xFF;
							if(pg>0xFF) pg = 0xFF;
							int pb = (a)&0xFF + (b)&0xFF;
							if(pb>0xFF) pb = 0xFF;
							int result = 0xFF000000 + (pr<<16) + (pg<<8) + pb;
							offdb.setElem(0, y*width+x, result);
						}
					}
				}
				
				g.drawImage(offImage, 0, 0, null);
			}
		}
	}
	
	//Terazza
	static ArrayList<Terazza> terazzaAry;

	static class Terazza {
		int id;
		int pictnum;
		int pictnums;
		int pictnume;
		int speed;
		long time;
		int h;
		int v;
		int eh;
		int ev;
		boolean visible;
	}
	
	static void TerazzaAnime(){
		if(terazzaAry==null) return;
		for(int j=0; j<terazzaAry.size(); j++){
			Terazza trz = terazzaAry.get(j);
			if(trz.visible && trz.speed>0) {
				Double now = (double) ((new Date().getTime() - trz.time)/1500.0*trz.speed);
				//System.out.println("now="+now+"     trz.time="+trz.time);
				if(now > 1000.0) now = 1000.0;
				int h = (int) ((trz.eh*(now)+trz.h*(1000.0-now))/1000.0);
				//System.out.println("h="+h+"   trz.eh="+trz.eh+"   trz.h="+trz.h);
				int v = (int) ((trz.ev*(now)+trz.v*(1000.0-now))/1000.0);
				int pictnum = trz.pictnum;
				if(now < 100.0) pictnum = trz.pictnums;
				if(now > 1000.0) pictnum = trz.pictnume;
				if(pictnum>0){
					String path = PCARD.pc.stack.rsrc.getFilePathAll(pictnum, "picture");
					BufferedImage bi = null;
					if(path!=null){
						//String path = PCARD.pc.stack.file.getParent()+File.separatorChar+fileName;
						bi = PictureFile.loadPICT(path);
					}
					if(bi != null){
						PCARD.pc.mainPane.getGraphics().drawImage(bi, h, v, PCARD.pc);
					}
				}else{
					if(trz.pictnum>0){
						String path = PCARD.pc.stack.rsrc.getFilePathAll(pictnum, "picture");
						if(path!=null){
							//String path = PCARD.pc.stack.file.getParent()+File.separatorChar+fileName;
							BufferedImage bi = PictureFile.loadPICT(path);
							PCARD.pc.mainPane.repaint(h, v, bi.getWidth(), bi.getHeight());
						}
					}
				}
			}
		}
	}
	
	static CaptureAnimationGIF animegifThread;
	
	static class CaptureAnimationGIF extends Thread
	{
		boolean endFlag;
		Rectangle rect;
		
		CaptureAnimationGIF(Rectangle rect){
			endFlag = false;
			this.rect = rect;
		}
		
		public void run(){
			Robot robot;
	        try {
	            robot = new Robot();
	        } catch (AWTException ex) {
	            ex.printStackTrace();
	            return;
	        }
				
			java.util.Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("gif");
			ImageWriter iw = it.hasNext()? (ImageWriter)it.next() : null;

			//ファイル用意
		    ImageOutputStream imageOutputStream = null;
			try {
				imageOutputStream = ImageIO.createImageOutputStream(new File("./anime.gif"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}
			if(imageOutputStream == null) return;
			
			iw.setOutput(imageOutputStream);

		    
			try {
				iw.prepareWriteSequence(null);
				BufferedImage lastImage = null;
				int waitTime = 0;
				IIOMetadata meta = null;
				ImageWriteParam iwp = null;
				BufferedImage image = null;
				while(!endFlag){
					//画像取り込み
			        image = robot.createScreenCapture(rect);

			        //違いを比較
		        	boolean isDifferent = false;
		        	Rectangle updateRect = new Rectangle(rect.width-1,rect.height-1,0,0);
		        	int updateRight = 0;
		        	int updateBottom = 0;
			        if(lastImage!=null){
			        	DataBuffer lastdb = lastImage.getRaster().getDataBuffer();
			        	DataBuffer db = image.getRaster().getDataBuffer();
			        	for(int y=0; y<rect.height; y++){
				        	for(int x=0; x<rect.width; x++){
				        		if(db.getElem(x+y*rect.width)!=lastdb.getElem(x+y*rect.width)){
				        			if(x<updateRect.x) updateRect.x = x;
				        			if(y<updateRect.y) updateRect.y = y;
				        			if(x>=updateRight) updateRight = x+1;
				        			if(y>=updateBottom) updateBottom = y+1;
				        			isDifferent = true;
				        		}
				        	}
			        	}
						
						lastImage = image;
						
			        	if(isDifferent){
				        	updateRect.width = updateRight - updateRect.x;
				        	updateRect.height = updateBottom - updateRect.y;
				        	if(!updateRect.equals(rect)){
					        	BufferedImage newimage = new BufferedImage(updateRect.width, updateRect.height, BufferedImage.TYPE_INT_RGB);
					        	newimage.createGraphics().drawImage(image, 0, 0, updateRect.width, updateRect.height,
					        			updateRect.x, updateRect.y, updateRect.x+updateRect.width, updateRect.y+updateRect.height, null);
					        	image = newimage;
				        	}
			        	}
			        }
			        else{
						lastImage = image;
			        	updateRect = rect;
			        	isDifferent = true;
			        }

		        	if(isDifferent){
				        //前の画像書き込み
						iw.writeToSequence(new IIOImage(image, null, meta), iwp);
						
				        //パラメータを設定
						iwp = iw.getDefaultWriteParam();
	
						meta = iw.getDefaultImageMetadata(
						    new ImageTypeSpecifier(image), iwp);
	
						String metaFormat = meta.getNativeMetadataFormatName();
						IIOMetadataNode root = (IIOMetadataNode)meta.getAsTree(metaFormat);
	
						IIOMetadataNode node = new IIOMetadataNode("GraphicControlExtension");
						node.setAttribute("disposalMethod", "none");
						node.setAttribute("userInputFlag", "FALSE");
						node.setAttribute("transparentColorFlag", "FALSE");
						node.setAttribute("transparentColorIndex", "0");
						node.setAttribute("delayTime", Integer.toString(waitTime));
						root.appendChild(node);
						
						node = new IIOMetadataNode ("ImageDescriptor");
						node.setAttribute("imageLeftPosition", Integer.toString(updateRect.x));
						node.setAttribute("imageTopPosition", Integer.toString(updateRect.y));
						node.setAttribute("imageWidth", Integer.toString(updateRect.width));
						node.setAttribute("imageHeight", Integer.toString(updateRect.height));
						node.setAttribute("interlaceFlag", "False");
						root.appendChild(node);
	
						meta.setFromTree(metaFormat, root);
						
						waitTime = 0;
		        	}
		        	
					try {
						Thread.sleep(50);
						waitTime += 5;
					} catch (InterruptedException e) {
					}
				}
				
				if(image!=null){
			        //画像書き込み
					iw.writeToSequence(new IIOImage(image, null, meta), iwp);
				}
				
				iw.endWriteSequence();
				
				//ファイル書き込み
			    imageOutputStream.close();
			    iw.dispose();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}

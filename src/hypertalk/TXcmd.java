import java.awt.AWTException;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
	/** The most memory GetMem reports: a figure a 32-bit classic Mac could have (scripts divide it and compare it). */
	static final long GETMEM_LIMIT = 256L*1024*1024;
	private static boolean uxBackReported = false;

	//XCMD,XFCN
	/**
	 * The stack whose resource fork holds the external: the open stack, else a stack in use, else Home - HyperCard looks
	 * through the resource files of the message path (Parara's 作例 calls UxGet1SndList from "Parara2.0").
	 */
	static OStack externalOwner(String name, String type){
		if(PCARD.pc==null || PCARD.pc.stack==null) return null;
		OStack via = pathStack.get();
		if(via!=null && resourcesOf(via)!=null && resourcesOf(via).hasXcmd(name, type)) return via;
		OStack st = PCARD.pc.stack;
		if(st.rsrc!=null && st.rsrc.hasXcmd(name, type)) return st;
		if(st.usingStacks!=null) for(OStack u : st.usingStacks) if(u!=null && u.rsrc!=null && u.rsrc.hasXcmd(name, type)) return u;
		if(PCARD.home!=null && PCARD.home.rsrc!=null && PCARD.home.rsrc.hasXcmd(name, type)) return PCARD.home;
		OStack app = applicationResources();
		if(app!=null && app!=st && app.rsrc!=null && app.rsrc.hasXcmd(name, type)) return app;
		return null;
	}

	/** A stack the message passed on its way that is not in the current card's hierarchy (the running handler's own stack): the
	 *  external being called is that stack's ({@link #CallExternalCommandOf}). */
	private static final ThreadLocal<OStack> pathStack = new ThreadLocal<OStack>();

	/** A stack's resources, also once it was closed (a handler of it still running after it went to another stack). */
	static Rsrc resourcesOf(OStack stack){
		return stack==null ? null : stack.rsrc!=null ? stack.rsrc : stack.closedResources;
	}

	/** Whether {@code stack}'s own resources hold the external {@code name} ("command" XCMD, "function" XFCN) that the VM runs. */
	static boolean has68kExternal(OStack stack, String name, String type){
		Rsrc r = resourcesOf(stack);
		return runsInVm(r==null ? null : r.getXcmd(name, type));
	}

	/** Whether the 68K VM runs that external: 68K code (XCMD/XFCN), and the PowerPC code of HyperCard 2.3's native externals
	 *  (xcmd/xfcn) through its Mixed Mode (Xcmd68k.powerPcCode) */
	static boolean runsInVm(Rsrc.xcmdClass x){
		String p = x==null || x.platform==null ? "" : x.platform.toLowerCase();
		return p.contains("68k") || p.contains("ppc");
	}

	/** Runs {@code stack}'s own external {@code message}: HyperCard found it in that stack's resource fork as the message passed
	 *  the stack, which need not be the current one (a handler of it went to another stack). */
	static Result CallExternalCommandOf(OStack stack, String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		OStack saved = pathStack.get();
		pathStack.set(stack);
		try{ return CallExternalCommand(message, params, target, memData, isFunc, result); }
		finally{ pathStack.set(saved); }
	}

	/** The stand-alone application stack opened last (its .hzb folder or _stack.xml) and a copy read for its resources */
	private static String applicationPath;
	private static OStack applicationCopy;
	/** A stand-alone application stack was opened: HyperCard runs it as the application, so its externals stay in the
	 *  resource chain below HyperCard's own when its scripts go to other stacks (Cosmic Osmo's CO.Resource calls the
	 *  xMemory XCMD of the "Cosmic Osmo" application from CO.Intro) */
	static synchronized void applicationOpened(OStack stack){
		String p = stack.file!=null ? stack.file.getPath() : stack.path;
		if(p!=null && new File(p).getName().equals("_stack.xml") && new File(p).getParentFile()!=null) p = new File(p).getParent();
		if(p==null || p.equals(applicationPath)) return;
		applicationPath = p; applicationCopy = null;
	}
	/** The application stack's resources once its window shows another stack (read once, as start using reads a stack) */
	private static synchronized OStack applicationResources(){
		if(applicationPath==null) return null;
		if(applicationCopy==null){
			OStack cur = PCARD.pc.stack;
			File here = cur==null ? null : cur.file!=null ? cur.file.getParentFile() : cur.path!=null ? new File(cur.path).getParentFile() : null;
			if(here!=null && here.getAbsoluteFile().equals(new File(applicationPath).getAbsoluteFile())) return null; //on show: its own resources answer
			File xml = new File(applicationPath, "_stack.xml");
			if(!xml.isFile()) return null; //not written yet (the stand-alone is still opening)
			OStack copy = new OStack(PCARD.pc);
			if(!copy.openStackFileInThread(applicationPath, true)){ applicationPath = null; return null; }
			applicationCopy = copy;
		}
		return applicationCopy;
	}

	/** Whether an external the VM runs (68K, or PowerPC-only) of that name is in the message path: it takes the message before a
	 *  built-in command of the same name (MacinTalk Stack's Speak XCMD over HyperZebra's speak) */
	static boolean runnable68kExternal(String name, String type){
		if(PCARD.pc==null || PCARD.pc.stack==null) return false;
		OStack st = PCARD.pc.stack;
		boolean any = st.rsrc!=null && st.rsrc.hasAnyXcmd() || PCARD.home!=null && PCARD.home.rsrc!=null && PCARD.home.rsrc.hasAnyXcmd();
		if(!any && st.usingStacks!=null) for(OStack u : st.usingStacks) if(u!=null && u.rsrc!=null && u.rsrc.hasAnyXcmd()){ any = true; break; }
		if(!any) return false; //built-in commands run on without a lookup in stacks that carry no externals
		OStack owner = externalOwner(name, type);
		if(owner==null || owner.rsrc==null) return false;
		return runsInVm(owner.rsrc.getXcmd(name, type));
	}

	/** The target of the message whose handler called the running external: its EvalExpr callback sees it as "the target"
	 *  (ScrollBar XFCN builds "send trackScroll to" && the target, which must name the button being tracked) */
	static final ThreadLocal<OObject> callTarget = new ThreadLocal<OObject>();
	/** The running external called AbortScript: the script stops when it returns (UxAnswer's cmd-.). */
	static volatile boolean abortScriptAfterExternal;
	/** The variables of the handler that called the running external: HyperCard evaluates an EvalExpr in that handler, so its
	 *  local variables are seen (Superman vs. Bill Gates: AddColor colorPict, "cd","fly1","(xx-52),(yy-52)" - HC J-2.3 draws
	 *  the picture along the path the handler's xx/yy take) */
	static final ThreadLocal<MemoryData> callVariables = new ThreadLocal<MemoryData>();

	public static Result CallExternalCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		OObject saved = callTarget.get();
		MemoryData savedVariables = callVariables.get();
		callTarget.set(target);
		callVariables.set(memData);
		try{ return callExternalCommand(message, params, target, memData, isFunc, result); }
		finally{ callTarget.set(saved); callVariables.set(savedVariables); }
	}

	/** A number parameter of a built-in stand-in for an external: a number as it is, else the HyperTalk expression it holds
	 *  evaluated as the external's EvalExpr would ("(xx-52)"); 0 when that is no number either */
	private static int externalNumber(String param) {
		String p = param==null ? "" : param.trim();
		try{ return (int)Math.round(Double.parseDouble(p)); }catch(NumberFormatException e){}
		if(p.isEmpty() || PCARD.pc==null || PCARD.pc.stack==null || PCARD.pc.stack.curCard==null) return 0;
		try{
			OObject t = callTarget.get();
			String v = TTalk.evaluateExternalExpression(p, PCARD.pc.stack.curCard, t!=null ? t : PCARD.pc.stack.curCard).trim();
			return (int)Math.round(Double.parseDouble(v));
		}catch(xTalkException | NumberFormatException e){ return 0; }
	}

	private static Result callExternalCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		//外部コマンドがあれば、それがメッセージを受け取る(何もしない組み込みの代用 UxShowHideCS / UxSetDialogFont も)。
		//以前は ret が「受け取り手なし」(1)のまま戻る分岐があり、文を式として評価し直して「showがわかりません」になっていた
		result.ret = 0;
		//The stack's own 68K external (or a using stack's, or Home's) runs in the 68K VM, as in HyperCard (a PowerPC-only one -
		//an xcmd/xfcn of HyperCard 2.3 - too, through the VM's Mixed Mode): the built-in stand-ins
		//below (Movie, QTMovie, AddColor, KStealKeyX, …) are only for stacks that call one without carrying it (HyperCard 2.4's
		//Movie is part of HyperCard itself). Myst Preview's Movie XCMD 1.2 has properties, callbacks and closeOnFinish that the
		//stand-in never had. -Dhz.builtinXcmds=true puts the stand-ins first again (to compare).
		//AddColor stays HyperZebra's own even when the stack carries Apple's: that XCMD colours HyperCard's card window through
		//HyperCard's internals and draws nothing here (The HyperCard Roundup's title card stayed white), while the built-in one
		//reads the same HCcd/HCbg/PICT resources and composites them over the card.
		if(!BUILTIN_XCMDS_FIRST && !message.equalsIgnoreCase("AddColor")){
			OStack owner = externalOwner(message, isFunc ? "function" : "command");
			Rsrc.xcmdClass external = resourcesOf(owner)==null ? null : resourcesOf(owner).getXcmd(message, isFunc ? "function" : "command");
			if(runsInVm(external))
				return run68kExternal(message, params, isFunc, result, owner, external);
		}
		if(0==message.compareToIgnoreCase("Movie")){
			//Movie XCMDもQTMovieと同じ変換・再生バックエンドを使う。
			//これにより古いQuickTime動画、音声、および'musi'トラックを扱える。
			//Movie (path1&TheMovieName),"borderLess","0,0","invisible","floating"
			System.out.println("Movie XCMD: call params="+java.util.Arrays.toString(params));
			if(params.length>=1){
				File f=resolveMovieFile(params[0],PCARD.pc==null?null:PCARD.pc.stack);
				if(f==null){
					System.err.println("Movie XCMD: file not found: "+params[0]);
					result.theResult = "ファイル"+params[0]+"がありません";
					result.ret = 0;
				}
				else{
					System.out.println("Movie XCMD: resolved file="+f.getAbsolutePath()+" size="+f.length());
					//Movie <file>,<window style>,<location>,<visible>,<layering>: the location is a point, a rect (its top left),
					//or card/largest/deepest/main (centred) / document (20,20 in from the card window)
					String style=params.length>=2&&!params[1].isEmpty()?params[1]:"windoid";
					int x=-1,y=-1;
					if(params.length>=3){
						String loc=params[2].trim();
						if(loc.equalsIgnoreCase("document")){x=20;y=20;}
						else if(!loc.isEmpty()&&Character.isDigit(loc.charAt(0))||loc.startsWith("-")){
							String[] point=loc.split(",");
							try{
								if(point.length>0)x=Integer.parseInt(point[0].trim());
								if(point.length>1)y=Integer.parseInt(point[1].trim());
							}catch(NumberFormatException e){x=-1;y=-1;}
						}
					}
					boolean visible=true,loop=false;
					for(int i=1;i<params.length;i++){
						if(params[i].equalsIgnoreCase("loop"))loop=true;
						if(params[i].equalsIgnoreCase("invisible"))visible=false;
					}
					String handle=TQTMovie.openMovieFile(f,f.getName(),x,y,loop,true,style,visible);
					System.out.println("Movie XCMD: open returned handle="+handle);
					result.theResult=""; //HyperCard: the result is empty unless the XCMD failed
				}
			}

			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("QTMovie")){
			//QTMovie XCMDの仕様書は無いため、実際に使っているスタックのスクリプトから
			//呼び出し規約を推測して実装している（詳細はTQTMovie参照）。
			//  QTMovie OpenMovie,<mode>,"<名前>","<左,上>"[,loop]  → "the result"にハンドルが入る
			//  QTMovie Direct,<ハンドル>,Idle                     → 再生を進める（idleハンドラから毎回呼ばれる）
			//  QTMovie Direct,<ハンドル>,Dispose                  → 閉じる
			if(params.length>=1 && params[0].equalsIgnoreCase("OpenMovie")){
				String name = params.length>=3 ? params[2] : "";
				int x=0, y=0;
				if(params.length>=4){
					String[] xy = params[3].split(",");
					try{
						if(xy.length>=1) x = Integer.parseInt(xy[0].trim());
						if(xy.length>=2) y = Integer.parseInt(xy[1].trim());
					}catch(NumberFormatException e){
						//位置指定が無い/不正なら0,0のまま
					}
				}
				boolean loop = params.length>=5 && params[4].equalsIgnoreCase("loop");
				result.theResult = TQTMovie.openMovie(name, x, y, loop, params.length>=2 ? params[1] : null);
			}
			else if(params.length>=1 && params[0].equalsIgnoreCase("Direct")){
				String handle = params.length>=2 ? params[1] : "";
				String action = params.length>=3 ? params[2] : "";
				if(action.equalsIgnoreCase("Dispose")){
					TQTMovie.dispose(handle);
				}
				else if(action.equalsIgnoreCase("Idle")){
					TQTMovie.idle(handle);
				}
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("KStealKeyX")){
			GUI.noteKeyPoll();
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
					if(c>=32 && c < 128) {
						int keyCode = Character.isLowerCase(c) ? Character.toUpperCase(c) : c;
						ret += (GUI.key[keyCode]>1)?"1":"0";
					}
				}
			}
			 
			result.theResult = ret;
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("KStealKeyX2")){
			GUI.noteKeyPoll();
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
					if(c>=32 && c < 128) {
						int keyCode = Character.isLowerCase(c) ? Character.toUpperCase(c) : c;
						ret += (GUI.key[keyCode]>1)?"1":"0";
					}
				}
			 }
			 
			 result.theResult = ret;
			 result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("UxGetInKey")){
			GUI.noteKeyPoll();
			String ret = "";
			//uxGetInKey("C") は押されているキーの文字、uxGetInKey() はキーコード(押されていなければ 0:
			//"repeat until uxGetInKey() is 0"でキーが離されるのを待つスクリプトがある)
			if(params.length>0 && params[0].equalsIgnoreCase("C")){
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
				ret = "0";
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
		else if(0==message.compareToIgnoreCase("maxdepth")){
			//MaxDepth XFCN。常にフルカラー表示なので32を返す
			result.theResult = "32";
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("setmonitor")){
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("getmem")){
			//GetMem XFCN: "HyperCard のパーティション,空き"(バイト)。Java のヒープの空きを 32 ビット Mac の範囲(256MB まで)で答える。
			//以前は4GBほどの値を int にしてあふれ、負の値になっていた(はいぱあれもん Vol.6 が「メモリが足りない」カードへ進んだ)
			long free = Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
			long size = Math.max(0, Math.min(free, GETMEM_LIMIT));
			result.theResult = size+","+size;
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("terazza")){
			if(params.length>=1){
				if(params[0].equalsIgnoreCase("init")){
					terazzaAry = new ArrayList<Terazza>();
					terazzaStack = PCARD.pc.stack;
					terazzaTransparentRGB = -1;
					terazzaImageCache.clear();
					ensureTerazzaOverlay();
				}
				else if(params[0].equalsIgnoreCase("pal")){
					//パレット指定PICTの左上ピクセルではなく、白を透明色として扱う。
					//(左上ピクセルは背景画像自体の色である場合があり、透明色の判定には使えない)
					if(params.length>=2){
						terazzaTransparentRGB = 0x00FFFFFF;
						//既にキャッシュした画像があれば透明色を反映し直す必要があるため破棄する
						terazzaImageCache.clear();
					}
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
								if(loadTerazzaImage(trz.pictnum) != null){
									eraseTerazzaTrail(trz);
								}
								trz.visible = true;
								trz.time = new Date().getTime();
								//重ねた順(=配列の登録順)を保ったまま表示中の
								//全スプライトをバッファに描き直し、残像消去で崩れてしまった
								//重なりを正しい順序で復元してから、画面に反映する。
								redrawAllVisibleTerazza();
								repaintTerazzaOverlay();
								break;
							}
						}
					}
				}
				else if(params[0].equalsIgnoreCase("move")){
					//実機(68K Mac)では1コマの描画自体に実時間がかかり、それが
					//スクリプト側のrepeatループの1周ごとの間隔(ペース)になっていた。
					//現在は描画が一瞬で終わるため、ここでフレームを1枚描画してから
					//約30ms待つことで、実機に近いアニメーション速度を再現する
					//(waitがTalkSleepで行っているのと同じ30ms刻みにして、
					//待っている間もcmd+.による中断を反映できるようにする)。
					terazzaMoveFramePause();
				}
				else if(params[0].equalsIgnoreCase("quit")){
					terazzaQuit();
				}
			}
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("enviro")){
			result.theResult = "32";
			result.ret = 0;
		}
		else if(0==message.compareToIgnoreCase("colorcover")){
			//ColorCover XCMD。カード以外の画面部分を単色で覆う。
			if(params.length>=1 && params[0].equalsIgnoreCase("off")){
				PCARD.pc.mainPane.setBackground(Color.WHITE);
			}
			else if(params.length>=4 && params[0].equalsIgnoreCase("on")){
				try{
					int r = (Integer.valueOf(params[1]) & 0xFFFF) >> 8;
					int g = (Integer.valueOf(params[2]) & 0xFFFF) >> 8;
					int b = (Integer.valueOf(params[3]) & 0xFFFF) >> 8;
					PCARD.pc.mainPane.setBackground(new Color(r,g,b));
				}catch(NumberFormatException e){}
			}
			PCARD.pc.mainPane.repaint();
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
			else if(params.length>=1 && params[0].equalsIgnoreCase("install")){
				//AddColor install[,depth]: AddColor sets up its offscreen buffer at that depth (8 when none given), which the heap
				//then lacks until AddColor remove (Cannons and Castles plays in black and white unless the heapSpace drops ≥50000)
				int depth = params.length>1 && !params[1].isBlank() ? externalNumber(params[1]) : 8;
				if(depth<1 || depth>32) depth = 8;
				PCARD.pc.stack.addColorHeapBytes = (long)PCARD.pc.stack.width*PCARD.pc.stack.height*depth/8;
			}
			else if(params.length>1 && params[0].equalsIgnoreCase("disableObject")){
				int number = 1;
				if(params.length>2){
					number = externalNumber(params[2]);
				}
				Rsrc.setEnabledACObj(number-1, false);
			}
			else if(params.length>3 && params[0].equalsIgnoreCase("changeObjectColor")){
				//AddColor changeObjectColor,bg|cd,<object number>,"r,g,b": the colour object's colour now, drawn by the next colorCard
				String[] c = params[3].split(",");
				if(c.length==3){
					Rsrc.setColorACObj(params[1].trim().toLowerCase().startsWith("b"), externalNumber(params[2])-1,
						new Color(Math.max(0, Math.min(255, externalNumber(c[0])/256)), Math.max(0, Math.min(255, externalNumber(c[1])/256)),
							Math.max(0, Math.min(255, externalNumber(c[2])/256))));
				}
			}
			else if(params.length>1 && params[0].equalsIgnoreCase("enableObject")){
				int number = 1;
				if(params.length>2){
					number = externalNumber(params[2]);
				}
				Rsrc.setEnabledACObj(number-1, true);
			}
			else if(params.length>=1 && params[0].equalsIgnoreCase("colorCard")){
				int time = 30;
				if(params.length>2){
					time = externalNumber(params[2]);
				}
				AddColor.colorCard(params.length>1 ? params[1] : "", time); //no effect given: the colours at once
			}
			else if(params.length>=4 && (params[0].equalsIgnoreCase("colorButton") || params[0].equalsIgnoreCase("colorField") ||
					params[0].equalsIgnoreCase("colorRect"))){
				//AddColor colorButton|colorField, "cd"|"bg", id, "r,g,b", bevel / colorRect, "cd"|"bg", "l,t,r,b", "r,g,b", bevel:
				//colours one object on the screen now, nothing stored (HTML Color Utility's swatch: AddColor "colorButton","bg",
				//22,RGB,2 after each pick; it stayed white)
				AddColor.colorObject(params[0], params[1], params[2], params[3], params.length>=5 ? externalNumber(params[4]) : 0);
			}
			else if(params.length>=1 && params[0].equalsIgnoreCase("colorPict")){
				if(params.length>=2 && "cd".equals(params[1])){
					
				}
				String pict = "";
				if(params.length>=3){
					pict = params[2];
				}
				//"left,top" places the picture at its own size; "left,top,right,bottom" draws it into that rectangle
				//(HyperLunacy vol2's button skins: "std btn mid" 4 pixels wide stretched across each button, HC J-2.3)
				Point p = new Point(0,0);
				Rectangle r = null;
				if(params.length>=4){
					String[] points = params[3].split(",");
					if(points.length==2){
						p.x = externalNumber(points[0]);
						p.y = externalNumber(points[1]);
					}
					else if(points.length==4){
						int left = externalNumber(points[0]), top = externalNumber(points[1]);
						r = new Rectangle(left, top, externalNumber(points[2])-left, externalNumber(points[3])-top);
						p = null;
					}
				}
				String mode = "o";
				if(params.length>=5){
					 mode = params[4];
				}
				String effect = ""; //none given: the picture appears at once (HC J-2.3, はいぱあれもん Vol.6's "AddColor colorPict,cd,OPG5,"0,0",o")
				if(params.length>=6){
					effect = params[5];
				}
				int time = 0;
				if(params.length>=7){
					time = externalNumber(params[6]);
				}
				AddColor.colorPict(pict, p, r, mode, effect, time, false);
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
				
				AddColor.colorPict(pict, null, srcRect, "o", "Copy", 0, true);
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
					if(!Sandbox.allowNetwork(Sandbox.scriptRequester(), Sandbox.NET_CONNECT, params[1], Integer.parseInt(params[2].trim()))) throw new IOException("access not allowed");
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
			else if(params.length>=2 && params[0].equalsIgnoreCase("receive")){
				//sentTCP "receive",connectionID[,termChar,timeout,encode]
				String encode = "US-ASCII";
				if(params.length>=5 && params[4].length()>0){
					encode = params[4];
				}
				String termChar = "";
				if(params.length>=3 && params[2].length()>0){
					termChar = params[2];
				}
				int timeout = Integer.MAX_VALUE;
				if(params.length>=4 && params[3].length()>0){
					timeout = Integer.valueOf(params[3]);
				}
				int conID = Integer.valueOf(params[1]);
				if(conID<0 || conID>=sentTCPAry.length || sentTCPAry[conID]==null){
					System.out.println("SentTCP:connectionID failure");
					result.theResult = Integer.toString(-1);
					result.ret = 0;
				}
				else{
					try {
						Socket socket = sentTCPAry[conID].socket;
						if(timeout != Integer.MAX_VALUE){
							long timeoutMillis = Math.max(1L, (timeout * 1000L + 59L) / 60L);
							socket.setSoTimeout((int)Math.min(timeoutMillis, Integer.MAX_VALUE));
						}
						else{
							socket.setSoTimeout(0);
						}
						BufferedReader reader = new BufferedReader
							(new InputStreamReader(socket.getInputStream(), encode));
						StringBuilder received = new StringBuilder();
						while(true){
							String line = reader.readLine();
							if(line == null) break;
							if(received.length() > 0) received.append('\n');
							received.append(line);
							if(termChar.isEmpty() || line.contains(termChar)) break;
						}
						result.theResult = received.toString();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			else if(params.length>=2 && params[0].equalsIgnoreCase("status")){
				//sentTCP "status",connectionID
				int conID = Integer.valueOf(params[1]);
				if(conID<0 || conID>=sentTCPAry.length || sentTCPAry[conID]==null){
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
			else if(params.length>=2 && params[0].equalsIgnoreCase("close")){
				//sentTCP "close",connectionID
				int conID = Integer.valueOf(params[1]);
				if(conID<0 || conID>=sentTCPAry.length || sentTCPAry[conID]==null){
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
			else if(params.length>=2 && params[0].equalsIgnoreCase("nameToAddr")){
				//sentTCP "nameToAddr",name
				try {
					if(!Sandbox.allowNetwork(Sandbox.scriptRequester(), Sandbox.NET_LOOKUP, params[1], 0)) throw new java.net.UnknownHostException("access not allowed");
					InetAddress ia = InetAddress.getByName(params[1]);
					byte [] bs = ia.getAddress();
					StringBuilder address = new StringBuilder();
					for(int i=0; i<bs.length; i++){
						if(address.length()>0) address.append('.');
						address.append(bs[i] & 0xff);
					}
					result.theResult = address.toString();
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
							bi = PCARD.pc.stack.rsrc.getImage(Integer.valueOf(params[2]), "PICT");
						}
						else{
							bi = PCARD.pc.stack.rsrc.getImage(params[2], "PICT"); //by name, not its ID looked up again
						}
						bi = PictureTranslation.shown(PCARD.pc.stack, bi); //its text translated when the stack is
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
						//VEffect.oldoffは画面の物理(実)サイズのスナップショット。srcRectは
						//論理座標なので、scale()倍してから物理サイズのoldoffを参照する。
						srcRect = PCARD.toPhysical(srcRect.x, srcRect.y, srcRect.width, srcRect.height);
					}

					Graphics2D dstGraphics=null;
					dstGraphics = getScaledMainPaneGraphics();
					
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
						//getScaledMainPaneGraphics()がscale()倍の変換を設定するため、
						//ここでのdstRectは論理座標(mainPaneの実サイズをscale()で割った値)にする。
						dstRect = new Rectangle(0,0,PCARD.toLogicalLength(PCARD.pc.mainPane.getWidth()),PCARD.toLogicalLength(PCARD.pc.mainPane.getHeight()));
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
						//VEffect.oldoffは画面の物理(実)サイズのスナップショット。srcRectは
						//論理座標なので、scale()倍してから物理サイズのoldoffを参照する。
						srcRect = PCARD.toPhysical(srcRect.x, srcRect.y, srcRect.width, srcRect.height);
					}
					
					Graphics2D dstGraphics=null;
					if(dstport>=0){
						dstGraphics = PgColorX[dstport].bi.createGraphics();
						PgColorX[dstport].colorKey = null;
					}
					else if(dstport==-1){
						dstGraphics = getScaledMainPaneGraphics();
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
						//getScaledMainPaneGraphics()がscale()倍の変換を設定するため、
						//ここでのdstRectは論理座標(mainPaneの実サイズをscale()で割った値)にする。
						dstRect = new Rectangle(0,0,PCARD.toLogicalLength(PCARD.pc.mainPane.getWidth()),PCARD.toLogicalLength(PCARD.pc.mainPane.getHeight()));
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
						//VEffect.oldoffは画面の物理(実)サイズのスナップショット。srcRectは
						//論理座標なので、scale()倍してから物理サイズのoldoffを参照する。
						srcRect = PCARD.toPhysical(srcRect.x, srcRect.y, srcRect.width, srcRect.height);
					}
					
					Graphics2D dstGraphics=null;
					if(dstport>=0){
						dstGraphics = PgColorX[dstport].bi.createGraphics();
						PgColorX[dstport].colorKey = null;
					}
					else if(dstport==-1){
						dstGraphics = getScaledMainPaneGraphics();
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
						bi = PCARD.pc.stack.rsrc.getImage(Integer.valueOf(params[2]), "PICT");
					}
					else{
						bi = PCARD.pc.stack.rsrc.getImage(params[2], "PICT"); //by name, not its ID looked up again
					}
					bi = PictureTranslation.shown(PCARD.pc.stack, bi); //its text translated when the stack is

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
							dstGraphics = getScaledMainPaneGraphics();
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
						dstGraphics = getScaledMainPaneGraphics();
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
			String str = params.length>=1 ? params[0] : "";
			int[] icon = null;
			//UxAnswer 2.0: UxAnswer msg, "btn1,btn2,…"(第2引数が数値でなければボタン名の一覧。アイコン・時間は無し)
			if(params.length>=2 && params[1].length()>0 && !params[1].trim().matches("[0-9 ,]+")){
				String[] btns = params[1].split(",");
				for(int i=0; i<btns.length; i++) btns[i] = btns[i].trim();
				new GUxAnswer(PCARD.pc, new StackTranslation.Dialog(null), str, null, null, 0, 0, btns);
				TTalk.setVariable(memData, "it", GUxAnswer.clicked);
				result.theResult = "";
				result.ret = 0;
				return result;
			}
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
			String[] Buttons = new String[Math.max(0, params.length-3)];
			for(int i=0; i<params.length-3; i++){
				if(params.length>=4+i && params[3+i].length()>0){
					Buttons[i] = params[3+i];
				}
			}
			
			if(Buttons.length==0 && time==0) Buttons = new String[]{"OK"}; //ボタンも時間も無いと閉じられない
			new GUxAnswer(PCARD.pc, new StackTranslation.Dialog(null), str, null, icon, iconwait, time, Buttons);
			TTalk.setVariable(memData, "it", GUxAnswer.clicked);
			
			result.theResult = "";
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
			OStack owner = externalOwner(message, isFunc ? "function" : "command");
			if(owner==null) owner = PCARD.pc.stack;
			Rsrc.xcmdClass external = owner.rsrc.getXcmd(message,
				isFunc ? "function" : "command");
			if(!runsInVm(external)) {
				System.out.println("未実装のXCMD/XFCN:"+message);
				result.ret = 0;
				throw new xTalkException("Unimplemented XCMD/XFCN: {0}", message);
			}
			return run68kExternal(message, params, isFunc, result, owner, external);
		}
		
		return result;
	}

	/** -Dhz.builtinXcmds=true: HyperZebra's stand-ins answer before a stack's own 68K external of the same name (the old order). */
	static final boolean BUILTIN_XCMDS_FIRST = Boolean.getBoolean("hz.builtinXcmds");

	/** Runs an XCMD/XFCN resource of {@code owner} in the 68K VM (an xcmd/xfcn's PowerPC code through its Mixed Mode); the result
	 *  is what it returned. */
	private static Result run68kExternal(String message, String[] params, boolean isFunc, Result result, OStack owner, Rsrc.xcmdClass external)
		throws xTalkException
	{
		//an external reached again through its own callbacks gets a fresh VM (~50 MB with its screen and card window) each time:
		//Phoneanizer's openCard calls AMLoad, whose "go home"/"go back" (sent when OpenResFile fails on a stack path of 128
		//characters or more: it takes the length byte as signed) reopens the card, and the VMs filled the Java heap within
		//seconds. HyperCard stops such a loop with "Too much recursion"; so does this, long before the heap runs out.
		int[] nesting = EXTERNAL_NESTING.get();
		if(nesting[0] >= MAX_EXTERNAL_NESTING){
			throw new xTalkException("Too much handler recursion: {0} [{1}]", message, ScriptDebugger.frameTrail(6));
		}
		nesting[0]++;
		try {
			return run68kExternalOnce(message, params, isFunc, result, owner, external);
		} finally {
			nesting[0]--;
		}
	}
	/** 68K externals running now on this thread, one inside another's callback (see {@link #run68kExternal}). */
	private static final ThreadLocal<int[]> EXTERNAL_NESTING = ThreadLocal.withInitial(() -> new int[1]);
	static final int MAX_EXTERNAL_NESTING = 16;

	private static Result run68kExternalOnce(String message, String[] params, boolean isFunc, Result result, OStack owner, Rsrc.xcmdClass external)
		throws xTalkException
	{
		try {
			M68kCpu.hostAbort = () -> TTalk.stop; //cmd+. で実行中の68K XCMDも止める
			File resource = Xcmd68k.resourceFile(owner, external);
			Xcmd68k.passedLastCall(); //a stale note from an earlier call
			abortScriptAfterExternal = false;
			result.theResult = Xcmd68k.execute(resource,params,
				owner.legacyTextEncoding,PCARD.pc.stack,external);
			//the external set passFlag: the message goes on as if nothing had handled it (a built-in command of that name runs)
			result.ret = Xcmd68k.passedLastCall() ? 1 : 0;
			if(abortScriptAfterExternal){ abortScriptAfterExternal = false; TTalk.stop = true; throw new xTalkException("user abort."); }
		} catch (M68kMacApiException e) {
			throw new xTalkException("{0}: Called an unsupported Mac API ({1})", message, e.getMessage());
		} catch (M68kAbortException e) {
			throw new xTalkException("{0}: Aborted", message);
		} catch (M68kException e) {
			throw new xTalkException(message+": "+e.getMessage());
		} catch (IOException e) {
			throw new xTalkException("{0}: Cannot read the XCMD/XFCN resource ({1})", message, e.getMessage());
		}
		return result;
	}

	/**
	 * Resolves a Movie XCMD path. Converted stacks keep their _stack.xml in the
	 * .hzb directory, while many original stacks keep media
	 * in a sibling BGMs directory. Classic scripts commonly construct the former
	 * path from the stack's long name, so try both locations.
	 */
	static File resolveMovieFile(String suppliedPath,OStack stack){
		if(suppliedPath==null||suppliedPath.isEmpty())return null;
		//Mac のパス(: 区切り、Windows では "C:hzs:music:a.mov" のように1文字のボリューム名がドライブ)も、ホストのパス
		//("C:\hzs\music\a.mov")もそのまま受け取る。以前は : を \ に置き換えるだけだったので、Windows の絶対パスが
		//"C\\hzs\..." の相対パスになり、スタックと同じフォルダにたまたま同名のファイルがあるときしか見つからなかった
		String path=TTalk.convertFromMacPath(suppliedPath);
		ArrayList<File> candidates=new ArrayList<File>();
		File requested=new File(path);candidates.add(requested);
		if(suppliedPath.indexOf(':')>=0 && !TTalk.isWindowsHostPath(suppliedPath) && !requested.isAbsolute()) candidates.add(new File(File.separator+path));
		String name=requested.getName();
		if(stack!=null&&stack.file!=null){
			File hzbDir=stack.file.getParentFile();
			File stackDir=StackBundle.folderAround(stack.file); // a stack file's folder, not its working folder's
			if(hzbDir!=null){candidates.add(new File(hzbDir,name));candidates.add(new File(new File(hzbDir,"BGMs"),name));}
			if(stackDir!=null){candidates.add(new File(stackDir,name));candidates.add(new File(new File(stackDir,"BGMs"),name));}
		}
		//HyperCard's search paths for documents and stacks (the globals Documents/Stacks; Myst Preview's movies are in "Preview Files:")
		for(String global:new String[]{"documents","stacks"})for(File folder:TTalk.searchPathFolders(global))candidates.add(new File(folder,name));
		for(File candidate:candidates){
			if(candidate.isFile())return candidate;
			File unpacked=FileManager68k.existingOrUnarchived(candidate.getParentFile(),candidate.getName());if(unpacked!=null&&unpacked.isFile())return unpacked;
			if(!candidate.getName().contains(".")){
				File mp3=new File(candidate.getPath()+".mp3");if(mp3.isFile())return mp3;
			}
		}
		return null;
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
				//VEffect.oldoffは画面の物理(実)サイズのスナップショット。srcRectは
				//論理座標なので、scale()倍してから物理サイズのoldoffを参照する。
				srcRect = PCARD.toPhysical(srcRect.x, srcRect.y, srcRect.width, srcRect.height);
			}

			Graphics2D dstGraphics=null;
			if(dstport>=0){
				dstGraphics = PgColorX[dstport].bi.createGraphics();
				PgColorX[dstport].colorKey = null;
			}
			else if(dstport==-1){
				dstGraphics = getScaledMainPaneGraphics();
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
			PCARD.pc.stack.addColorHeapBytes = 0;
			PCARD.pc.stack.addColor = null;
			setOpaque(null, null, null, false);
			if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
		}
		
		
		static void colorCard(String effect, int time){
			//AddColor colorCard: the background's colour objects (HCbg), then the card's (HCcd), in list order.
			//A card with neither shows in black and white (the previous card's colours do not stay)
			BufferedImage bi = colorCardImage(PCARD.pc.stack.curCard);
			int number = effectNumber(effect);
			if(number!=0) VEffect.setOldOff();
			PCARD.pc.stack.addColor = bi; //a new picture: the card's black stays on top of all of it (colorPict "o" does not carry over)
			playEffect(number, time, null);
		}

		/** AddColor's effects that are not HyperCard visual effects (measured in HC J-2.3 + AddColor, see VEffect.addColorFrame) */
		private static final java.util.Map<String,Integer> ADDCOLOR_OWN_EFFECTS = java.util.Map.ofEntries(
			java.util.Map.entry("checkerboardopen", VEffect.ADDCOLOR_CHECKER_OPEN),
			java.util.Map.entry("checkerboardclose", VEffect.ADDCOLOR_CHECKER_CLOSE),
			java.util.Map.entry("circlecheckeropen", VEffect.ADDCOLOR_CIRCLE_CHECKER_OPEN),
			java.util.Map.entry("irisopen", VEffect.ADDCOLOR_IRIS_OPEN),
			java.util.Map.entry("irisclose", VEffect.ADDCOLOR_IRIS_CLOSE),
			java.util.Map.entry("combhorizontal", VEffect.ADDCOLOR_COMB_HORIZONTAL),
			java.util.Map.entry("combvertical", VEffect.ADDCOLOR_COMB_VERTICAL),
			java.util.Map.entry("fromtopleft", VEffect.ADDCOLOR_FROM_TOP_LEFT),
			java.util.Map.entry("fromtopright", VEffect.ADDCOLOR_FROM_TOP_RIGHT),
			java.util.Map.entry("frombottomleft", VEffect.ADDCOLOR_FROM_BOTTOM_LEFT),
			java.util.Map.entry("frombottomright", VEffect.ADDCOLOR_FROM_BOTTOM_RIGHT),
			java.util.Map.entry("dissolve", VEffect.ADDCOLOR_DISSOLVE));

		/** The VEffect number of an AddColor effect name; 0 = none (no name, or one AddColor has and HyperZebra does not) */
		static int effectNumber(String effect){
			if(effect==null || effect.trim().isEmpty()) return 0;
			Integer own = ADDCOLOR_OWN_EFFECTS.get(effect.trim().toLowerCase());
			if(own!=null) return own;
			ArrayList<String> effList = new ArrayList<String>();
			for(String word : convAddColorEffect(effect.trim()).split(" ")) effList.add(word);
			try {
				return 0xFF&TUtil.getVisualMode(effList,0,0);
			} catch (xTalkException e) {
				return 0;
			}
		}

		/** Shows the new colours: with an effect, inside area (the picture's rectangle; null = the whole card) from the
		 *  picture setOldOff took, lasting AddColor's time in ticks (HC J-2.3: checkerBoardOpen,20 takes 1/3 s,
		 *  barnDoorOpen,75 1.25 s); without one at once */
		private static void playEffect(int number, int ticks, Rectangle area){
			if(number==0){
				//the colours go into the window over what 68K externals drew there, as with an effect (VEffect's path): Anthony's
				//KillCard demo ends with colorMe = "AddColor colorCard,stamp,0", and its lines stayed on the card
				if(area==null) CardWindow68k.cardCopied();
				else CardWindow68k.partCopied(area.y, area.x, area.y+area.height, area.x+area.width);
				addToMainPane();
				return;
			}
			long ms = Math.max(0, ticks)*1000L/60;
			if(number!=VEffect.ADDCOLOR_DISSOLVE){
				VEffect.visualEffectIn(number, area, ms);
				return;
			}
			//dissolve shows everything in half its time and then waits out the rest (はいぱあれもん Vol.6, HC J-2.3: dissolve,300
			//is all shown after 2.45 s and the next AddColor comes later; barnDoorOpen,75 takes 1.23 s)
			long end = System.currentTimeMillis()+ms;
			VEffect.visualEffectIn(number, area, ms/2);
			if(javax.swing.SwingUtilities.isEventDispatchThread()) return;
			for(long left; !TTalk.stop && (left = end-System.currentTimeMillis())>0; ){
				try{ Thread.sleep(Math.min(10, left)); }
				catch(InterruptedException e){ Thread.currentThread().interrupt(); return; }
			}
		}

		
		/** The colours of a card as colorCard draws them: its background's HCbg objects, then its HCcd objects */
		static BufferedImage colorCardImage(OCard card){
			OStack stack = card.stack;
			Rsrc.addcolorClass bgColors = null, cdColors = null;
			for(Rsrc.addcolorClass c : stack.rsrc.addcolorList){
				if(c.isBg){ if(card.bg!=null && c.id==card.bg.id && bgColors==null) bgColors = c; }
				else if(c.id==card.id && cdColors==null) cdColors = c;
			}
			BufferedImage bi = new BufferedImage(stack.width, stack.height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = bi.createGraphics();
			if(bgColors!=null) drawColorObjects(g, bgColors, card.bg);
			if(cdColors!=null) drawColorObjects(g, cdColors, card);
			g.dispose();
			return bi;
		}

		/** The objects of one HCbg/HCcd list: pictures, and buttons/fields/rectangles filled with their colour and bevel */
		private static void drawColorObjects(Graphics2D g, Rsrc.addcolorClass list, OCardBase layer){
			for(Rsrc.addcolorObjClass obj : list.objList){
				if(!obj.visible) continue;
				if(obj instanceof Rsrc.PictObject){
					Rsrc.PictObject picobj = (Rsrc.PictObject)obj;
					BufferedImage pict = colorPicture(picobj.name, picobj.file);
					if(pict==null) continue;
					if(picobj.transparent) pict = whiteToClear(pict);
					//DrawPicture into the object's rectangle: a 511-pixel picture in a 512-pixel rectangle is stretched (Book,
					//Chapter & Verse's dithered background, HC J-2.3), pixel for pixel as QuickDraw scales
					Rectangle pr = picobj.rect;
					if(pr.width>0 && pr.height>0 && (pr.width!=pict.getWidth() || pr.height!=pict.getHeight())){
						Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
						g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
						g.drawImage(pict, pr.x, pr.y, pr.width, pr.height, null);
						if(hint!=null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
					}
					else g.drawImage(pict, pr.x, pr.y, null);
					continue;
				}
				Rectangle area = null;
				boolean see = false; //a transparent button or field: its colour is blended half and half with what is below
				if(obj instanceof Rsrc.FldObject){
					OField fld = layer==null ? null : layer.GetFldbyId(((Rsrc.FldObject)obj).id);
					//a hidden field is not coloured (Clue Deluxe's hidden "editor"/"credits" fields over the castle, HC J-2.3)
					if(fld!=null && fld.getVisible()) area = new Rectangle(fld.left, fld.top, fld.width, fld.height); //HCCD entry for a field that no longer exists (Clue Deluxe)
					see = fld!=null && fld.style==1;
				}
				else if(obj instanceof Rsrc.BtnObject){
					OButton btn = layer==null ? null : layer.GetBtnbyId(((Rsrc.BtnObject)obj).id);
					if(btn!=null && btn.getVisible()) area = new Rectangle(btn.left, btn.top, btn.width, btn.height);
					see = btn!=null && btn.style==1;
				}
				else if(obj instanceof Rsrc.RectObject) area = ((Rsrc.RectObject)obj).rect;
				if(area==null) continue;
				//HyperCard J-2.3 with AddColor (measured): red on a transparent button gives #FF6666 over white, #880000 over
				//black and #990066 over blue in its 8-bit palette - an even blend (Book, Chapter & Verse: the background's
				//"Bible" shows through the coloured buttons); an opaque one is the colour itself
				java.awt.Composite composite = g.getComposite();
				if(see) g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f));
				fillBevelled(g, area, obj.runColor!=null ? obj.runColor : obj.color, obj.bevel);
				g.setComposite(composite);
			}
		}

		/**
		 * A colour area with AddColor's bevel, as HyperCard J-2.3 draws it (measured): the outer pixel ring keeps the colour
		 * (the button's frame sits there), then bevel rings with the top and left edges halfway to white and the bottom
		 * and right edges at three quarters of the colour (#AAAAAA, bevel 1: #D5D5D5 / #838383 one pixel in)
		 */
		static void fillBevelled(Graphics2D g, Rectangle r, Color color, int bevel){
			g.setColor(color);
			g.fill(r);
			int b = Math.min(bevel, (Math.min(r.width, r.height)-2)/2);
			if(b<=0) return;
			Color light = new Color((color.getRed()+255)/2, (color.getGreen()+255)/2, (color.getBlue()+255)/2);
			Color dark = new Color(color.getRed()*3/4, color.getGreen()*3/4, color.getBlue()*3/4);
			for(int i=1; i<=b; i++){
				int x0 = r.x+i, y0 = r.y+i, x1 = r.x+r.width-1-i, y1 = r.y+r.height-1-i;
				g.setColor(light); g.drawLine(x0, y0, x1, y0); g.drawLine(x0, y0, x0, y1);
				g.setColor(dark); g.drawLine(x0+1, y1, x1, y1); g.drawLine(x1, y0+1, x1, y1);
			}
		}

		/** A picture object's image: a PICT resource of the stack chain by name, or (type 5, or no such resource) a picture
		 *  file in the stack's folder or at the path given - PICT, or PNG/JPEG when a file was converted for the host */
		static BufferedImage colorPicture(String name, boolean file){
			if(name==null || name.isEmpty()) return null;
			if(!file){
				BufferedImage pict = PictureFile.loadPICT(PCARD.pc.stack.rsrc.getFilePathAll(name, "PICT")); //by name, not its ID looked up again
				if(pict!=null) return PictureTranslation.shown(PCARD.pc.stack, pict); //its text translated when the stack is
			}
			java.util.List<File> candidates = new ArrayList<File>();
			if(PCARD.pc.stack.file!=null){
				File folder = StackBundle.folderAround(PCARD.pc.stack.file); //<folder>/<stack>.hzb/_stack.xml: pictures sit beside the stack
				if(folder!=null) candidates.add(new File(folder, name.replace(':', File.separatorChar)));
			}
			String converted = TTalk.convertFromMacPath(name);
			if(converted!=null) candidates.add(new File(converted));
			for(File f : candidates){
				if(!f.isFile()) continue;
				try{
					BufferedImage pict = PictureCache.read(f, "addcolor", ff -> TUtil.loadPictureFile(ff.getPath()));
					if(pict!=null) return pict;
				}catch(IOException e){ /* try the next place */ }
			}
			return null;
		}

		/** A transparent picture object: its white pixels let what is below show */
		static BufferedImage whiteToClear(BufferedImage src){
			BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
			for(int y=0; y<src.getHeight(); y++) for(int x=0; x<src.getWidth(); x++){
				int p = src.getRGB(x, y);
				out.setRGB(x, y, (p&0xFFFFFF)==0xFFFFFF ? 0 : p);
			}
			return out;
		}

		/** colorButton/colorField/colorRect: one object's colour drawn over the card's colours now (the next colorCard redraws them) */
		static void colorObject(String kind, String layerName, String which, String rgb, int bevel){
			OCard card = PCARD.pc.stack.curCard;
			if(card==null) return;
			OCardBase layer = layerName.trim().toLowerCase().startsWith("b") ? card.bg : card;
			Rectangle area = null;
			boolean see = false;
			if(kind.equalsIgnoreCase("colorRect")){
				String[] v = which.split(",");
				if(v.length!=4) return;
				int l = externalNumber(v[0]), t = externalNumber(v[1]);
				area = new Rectangle(l, t, externalNumber(v[2])-l, externalNumber(v[3])-t);
			}
			else if(layer!=null && kind.equalsIgnoreCase("colorButton")){
				OButton btn = layer.GetBtnbyId(externalNumber(which));
				if(btn!=null && btn.getVisible()){ area = new Rectangle(btn.left, btn.top, btn.width, btn.height); see = btn.style==1; }
			}
			else if(layer!=null){
				OField fld = layer.GetFldbyId(externalNumber(which));
				if(fld!=null && fld.getVisible()){ area = new Rectangle(fld.left, fld.top, fld.width, fld.height); see = fld.style==1; }
			}
			String[] c = rgb.split(",");
			if(area==null || area.width<=0 || area.height<=0 || c.length!=3) return;
			Color color = new Color(Math.max(0, Math.min(255, externalNumber(c[0])/256)), Math.max(0, Math.min(255, externalNumber(c[1])/256)),
				Math.max(0, Math.min(255, externalNumber(c[2])/256)));
			BufferedImage bi = PCARD.pc.stack.addColor;
			if(bi==null) bi = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = bi.createGraphics();
			if(see) g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f));
			fillBevelled(g, area, color, bevel);
			g.dispose();
			setOpaque(bi, area, null, false); //the card's black shows on a colour (HyperLunacy vol2: a white colorRect over the page kept its text)
			addToMainPane();
			PCARD.pc.stack.addColor = bi;
			if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
		}

		/** Draws a PICT into the colour picture at p (its own size) or into r; coversCard (ColorizeHC): an opaque one hides the
		 *  card under it, black and all, where AddColor's leave the card's black on top */
		static void colorPict(String pict, Point p, Rectangle r, String mode, String effect, int time, boolean coversCard){
			//裏画面を準備
			BufferedImage bi = PCARD.pc.stack.addColor;
			if(bi==null){
				bi = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB);
			}

			int number = effectNumber(effect);
			if(number!=0) VEffect.setOldOff();
			Rectangle area = null;
			
			//PICTを描画
			String path = PCARD.pc.stack.rsrc.getFilePathAll(pict, "PICT"); //by name, not its ID looked up again
			//String path = (PCARD.pc.stack.file.getParent()+File.separatorChar+fileName);
			BufferedImage pictbi = PictureTranslation.shown(PCARD.pc.stack, PictureFile.loadPICT(path)); //its text translated when the stack is
			//mode "t" (transparent): the picture's white pixels leave what is under them (Superman vs. Bill Gates: the flying
			//figures over the city, HC J-2.3); "o" copies the whole rectangle
			boolean transparent = mode!=null && mode.trim().toLowerCase().startsWith("t");
			if(pictbi!=null && transparent) pictbi = whiteToClear(pictbi);
			if(pictbi!=null){
				if(p!=null) area = new Rectangle(p.x, p.y, pictbi.getWidth(), pictbi.getHeight());
				else if(r!=null) area = new Rectangle(r);
			}
			if(area!=null && area.width>0 && area.height>0){
				//DrawPicture into a rectangle of another size scales pixel for pixel
				BufferedImage placed = pictbi;
				if(area.width!=pictbi.getWidth() || area.height!=pictbi.getHeight()){
					placed = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB);
					Graphics2D pg = placed.createGraphics();
					pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					pg.drawImage(pictbi, 0, 0, area.width, area.height, null);
					pg.dispose();
				}
				Graphics2D g = bi.createGraphics();
				g.drawImage(placed, area.x, area.y, null);
				g.dispose();
				//AddColor (HC J-2.3, HyperLunacy vol2): the card's black stays on top of "o" pictures too - the field text and the
				//card picture's title showed through an opaque PICT drawn over them; "o" only keeps the picture's white
				setOpaque(bi, area, transparent ? placed : null, coversCard && !transparent);
			}
			
			PCARD.pc.stack.addColor = bi;
			playEffect(number, time, area);
		}
		
		
		static void colorFill(Color color, Rectangle r, String mode, String effect, int time){
			//裏画面を準備
			BufferedImage bi = PCARD.pc.stack.addColor;
			if(bi==null){
				bi = new BufferedImage(PCARD.pc.stack.width, PCARD.pc.stack.height, BufferedImage.TYPE_INT_ARGB);
			}

			int number = effectNumber(effect);
			if(number!=0) VEffect.setOldOff();
			
			Graphics g = bi.getGraphics();
			g.setColor(color);
			g.fillRect(r.x, r.y, r.width, r.height);
			setOpaque(bi, r, null, mode!=null && mode.equalsIgnoreCase("o"));
			
			PCARD.pc.stack.addColor = bi;
			playEffect(number, time, new Rectangle(r));
		}
		
		
		static String convAddColorEffect(String effect){
			//fromLeft reveals the picture from its left edge (HC J-2.3 + AddColor, measured): HyperCard's wipe right
			if(effect.equalsIgnoreCase("fromLeft")){
				effect="wipe right";
			}
			if(effect.equalsIgnoreCase("fromRight")){
				effect="wipe left";
			}
			if(effect.equalsIgnoreCase("fromTop")){
				effect="wipe down";
			}
			if(effect.equalsIgnoreCase("fromBottom")){
				effect="wipe up";
			}
			if(effect.equalsIgnoreCase("barnDoorOpen")){
				effect="barn door open";
			}
			if(effect.equalsIgnoreCase("barnDoorClose")){
				effect="barn door close";
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
		/** The AddColor picture is composited over the card by {@link MyPanel#paint}; here it only asks for a repaint. */
		static public void addToMainPane(){
			if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
		}

		/** The AddColor picture {@link #opaque} belongs to (another picture: none of its pixels are opaque) */
		private static BufferedImage opaqueOf;
		/** Pixels (y*width+x) an opaque ColorizeHC picture or fill drew last: they replace the card; elsewhere black card
		 *  pixels come through */
		private static java.util.BitSet opaque = new java.util.BitSet();

		/** Marks the pixels of r opaque or not; with drawn (its 0,0 at r's top left) only those it does not leave clear */
		static synchronized void setOpaque(BufferedImage bi, Rectangle r, BufferedImage drawn, boolean on){
			if(bi!=opaqueOf){ opaqueOf = bi; opaque = new java.util.BitSet(); }
			if(bi==null || r==null) return;
			Rectangle c = r.intersection(new Rectangle(0, 0, bi.getWidth(), bi.getHeight()));
			for(int y=c.y; y<c.y+c.height; y++){
				for(int x=c.x; x<c.x+c.width; x++){
					if(drawn!=null && (drawn.getRGB(x-r.x, y-r.y)>>>24)==0) continue;
					opaque.set(y*bi.getWidth()+x, on);
				}
			}
		}

		/**
		 * Composites the AddColor buffer (logical card size) over {@code off}, the card as Swing painted it for the clip
		 * {@code x,y,w,h} in physical pixels: the card's black stays on top of the colours (AddColor, HC J-2.3), except where an
		 * opaque ColorizeHC picture covers the card. This used to be a JPanel on top of the parts, and a part that repainted
		 * itself (a put into a field) came back on top of the picture.
		 */
		static void composite(BufferedImage off, int x, int y, int w, int h){
			BufferedImage add = PCARD.pc.stack==null ? null : PCARD.pc.stack.addColor;
			if(add==null) return;
			int scale = PCARD.scalePercent();
			DataBuffer offdb = off.getRaster().getDataBuffer();
			DataBuffer adddb = add.getRaster().getDataBuffer();
			int addWidth = add.getWidth(), addHeight = add.getHeight();
			java.util.BitSet opaque;
			synchronized(AddColor.class){ opaque = add==opaqueOf ? (java.util.BitSet)AddColor.opaque.clone() : new java.util.BitSet(); }
			for(int yy=0; yy<h; yy++){
				int ay = DisplayZoom.unscale(y+yy, scale);
				if(ay<0 || ay>=addHeight) continue;
				for(int xx=0; xx<w; xx++){
					int ax = DisplayZoom.unscale(x+xx, scale);
					if(ax<0 || ax>=addWidth) continue;
					int a = offdb.getElem(0, yy*w+xx);
					int b = adddb.getElem(0, ay*addWidth+ax);
					if((b>>>24)==0) continue; //絵の無いところは部品がそのまま見える
					if((a>>>24)==0) a = 0xFFFFFFFF; //まだ描かれていない(透明)画素は白と同じ
					if(a==0xFFFFFFFF || opaque.get(ay*addWidth+ax)) offdb.setElem(0, yy*w+xx, b);
					else{
						//透明モードの PICT を白黒のカードに srcOr で重ねた見え方: 白→絵の色、黒→黒、中間→暗くした色(乗算)
						//(以前の式は演算子の優先順位が崩れていて、黒だけたまたま残っていた)
						int pr = (((a>>16)&0xFF) * ((b>>16)&0xFF)) / 255;
						int pg = (((a>>8)&0xFF) * ((b>>8)&0xFF)) / 255;
						int pb = ((a&0xFF) * (b&0xFF)) / 255;
						offdb.setElem(0, yy*w+xx, 0xFF000000 | (pr<<16) | (pg<<8) | pb);
					}
				}
			}
		}
	}
	
	//Terazza
	static ArrayList<Terazza> terazzaAry;
	static int terazzaTransparentRGB = -1;
	//アニメーション中は毎フレーム(約30ms間隔)同じ絵を何度も描き直すため、
	//PICTファイルの読み込み・デコードをリソース番号ごとにキャッシュしておく。
	//キャッシュしないと、開始時や移動していないスプライトの重ね描き分も
	//含めて毎フレームディスクから読み直すことになり、その間の負荷でアニメーション
	//がチラつく原因になる。init/pal/quitでクリアする。
	//ただし、1回のinit~quit(またはinit~init)の間に非常に多くの種類の絵を
	//表示するスタックだと、このキャッシュだけが際限なく増え続けてOutOfMemoryの
	//原因になり得るため、件数の上限を設けて古い(最近使っていない)ものから
	//自動的に破棄する(LRU)。
	private static final int TERAZZA_IMAGE_CACHE_MAX = 64;
	private static final LinkedHashMap<Integer, BufferedImage> terazzaImageCache =
		new LinkedHashMap<Integer, BufferedImage>(16, 0.75f, true){
			private static final long serialVersionUID = 1L;
			protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> eldest){
				return size() > TERAZZA_IMAGE_CACHE_MAX;
			}
		};

	//スプライトを合成しておくオフスクリーンの重ね合わせ用バッファ
	//(いわゆるダブルバッファ)。画面に直接描く代わりにこのバッファを更新し、
	//MyPanel.paintでカード本体の上に重ねて描画する。こうすることで、Swing自身が
	//(このアニメーションループとは無関係のタイミングで)ボタンやフィールドの
	//再描画を行っても、必ず最新のスプライト画像が一緒に描かれるようになり、
	//消去してから描き直すまでの一瞬だけ画面に映ってしまう問題(チラつきや、
	//カード本来の絵が一瞬見えてしまう現象)を防ぐ。
	static BufferedImage terazzaOverlay;
	//Terazza を init したスタック。quit しないまま閉じられたら(G'S LAIR のように最初のカードで閉じる)スプライトも消す
	private static OStack terazzaStack;

	static void terazzaQuit(){
		terazzaAry = null;
		terazzaStack = null;
		terazzaImageCache.clear();
		terazzaOverlay = null;
		if(PCARD.pc!=null && PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
	}

	/** The stack closed: sprites it left on the card without "Terazza quit" go with it (they stayed over the next stack opened). */
	static void stackClosing(OStack stack){
		if(terazzaAry!=null && terazzaStack!=null && Xcmd68k.sameStack(terazzaStack, stack)) terazzaQuit();
	}

	//PgColorX/mirage XCMD等がport==-1(画面)へ直接描画する際に使う。
	//これらは全てHyperTalkスクリプトから見える論理座標(rect/topleft等)を使って
	//描画するため、返すGraphics2Dにscale()倍の拡大変換とニアレストネイバー補間を
	//あらかじめ設定しておくことで、呼び出し側の描画コードを変更せずに
	//2倍ピクセルパーフェクト拡大表示モードへ対応する。
	private static Graphics2D getScaledMainPaneGraphics(){
		Graphics2D g = (Graphics2D) PCARD.pc.mainPane.getGraphics();
		double scale = PCARD.scale();
		if(scale!=1){
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, PCARD.scaleInterpolation());
			g.scale(scale, scale);
		}
		return g;
	}

	//重ね合わせ用バッファをカードの大きさに合わせて用意する(サイズが変わっていれば作り直す)
	private static void ensureTerazzaOverlay(){
		if(PCARD.pc==null || PCARD.pc.mainPane==null) return;
		int w = PCARD.pc.mainPane.getWidth();
		int h = PCARD.pc.mainPane.getHeight();
		if(w<=0 || h<=0) return;
		if(terazzaOverlay==null || terazzaOverlay.getWidth()!=w || terazzaOverlay.getHeight()!=h){
			terazzaOverlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		}
	}

	//このフレームでバッファに加えた更新を、実際の画面に反映する
	private static void repaintTerazzaOverlay(){
		if(PCARD.pc==null || PCARD.pc.mainPane==null) return;
		PCARD.pc.mainPane.paintImmediately(0, 0, PCARD.pc.mainPane.getWidth(), PCARD.pc.mainPane.getHeight());
	}

	//呼び出し元(loadTerazzaImage)ではsrcはこの呼び出しの直後に破棄される
	//(戻り値で置き換えられる)ため、同じ大きさの新規BufferedImageを別途
	//確保せず、srcのピクセルをその場で書き換える。大きな画像をキャッシュに
	//多数のコマを溜め込む処理では、一時的とはいえ同サイズの画像を2枚同時に
	//保持するだけでOutOfMemoryにつながりかねないため、ここでは倍のメモリを
	//使わないようにしている。
	private static BufferedImage applyTerazzaTransparency(BufferedImage src, int transparentRGB) {
		if(transparentRGB < 0){
			return src;
		}

		int width = src.getWidth();
		int height = src.getHeight();
		for(int y=0; y<height; y++){
			for(int x=0; x<width; x++){
				int rgb = src.getRGB(x, y) & 0x00FFFFFF;
				if(rgb == transparentRGB){
					src.setRGB(x, y, 0x00000000);
				}
				else{
					src.setRGB(x, y, 0xFF000000 | rgb);
				}
			}
		}
		return src;
	}

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
		//直前に描画した位置とサイズ。透明部分は下地を消さないため、
		//次のコマ描画前にここを再描画して残像(黒い文字などの取り残し)を消す
		int lastX=-1, lastY=-1, lastW=0, lastH=0;
	}

	//透明部分を含む画像を描画する前に、直前の描画位置の重ね合わせ用バッファを
	//透明に戻しておく(画面には触れない)。この時点ではまだ画面に反映しない
	//ため、消去してから描き直すまでの間の状態が画面に見えてしまうことはない。
	//バッファ上の消去なので、そこに重なっていた他のスプライトも
	//一緒に消えてしまう(呼び出し側で重ね描きし直す必要がある)。
	private static void eraseTerazzaTrail(Terazza trz){
		if(trz.lastW>0 && trz.lastH>0){
			clearTerazzaOverlayRect(trz.lastX, trz.lastY, trz.lastW, trz.lastH);
		}
	}

	//重ね合わせ用バッファの指定範囲を透明に戻す(画面にはまだ反映しない)
	private static void clearTerazzaOverlayRect(int x, int y, int w, int h){
		if(terazzaOverlay==null) return;
		Graphics2D g = terazzaOverlay.createGraphics();
		g.setComposite(AlphaComposite.Clear);
		g.fillRect(x, y, w, h);
		g.dispose();
	}

	//指定のリソース番号の画像を読み込み、透明色を適用して返す。
	//毎フレーム同じ絵を読み直さずに済むよう、結果をキャッシュする。
	private static BufferedImage loadTerazzaImage(int pictnum){
		BufferedImage cached = terazzaImageCache.get(pictnum);
		if(cached != null) return cached;

		String path = PCARD.pc.stack.rsrc.getFilePathAll(pictnum, "PICT");
		if(path==null) return null;
		BufferedImage bi = PictureFile.loadPICT(path);
		if(bi==null) return null;
		bi = applyTerazzaTransparency(bi, terazzaTransparentRGB);
		terazzaImageCache.put(pictnum, bi);
		return bi;
	}

	//既に読み込み・透明化済みの画像を、そのまま重ね合わせ用バッファに描き込み、
	//次回の残像消去に使う位置とサイズを記録する。画面にはまだ反映しない。
	//h,vやbiのサイズはHyperTalkスクリプトから見える論理座標。重ね合わせ用バッファは
	//mainPaneの実(物理)サイズなので、ここでscale()倍してから描画・記録する。
	private static void drawTerazza(Terazza trz, BufferedImage bi, int h, int v){
		Rectangle r = PCARD.toPhysical(h, v, bi.getWidth(), bi.getHeight());
		if(terazzaOverlay != null){
			Graphics2D g = terazzaOverlay.createGraphics();
			g.setComposite(AlphaComposite.SrcOver);
			//整数倍ならドット絵をにじませず、ピクセルパーフェクトに拡大表示する
			PCARD.drawScaled(g, bi, h, v, null);
			g.dispose();
		}
		trz.lastX = r.x; trz.lastY = r.y;
		trz.lastW = r.width; trz.lastH = r.height;
	}

	//指定のリソース番号の画像を読み込んで描画する
	private static void drawTerazza(Terazza trz, int pictnum, int h, int v){
		BufferedImage bi = loadTerazzaImage(pictnum);
		if(bi != null){
			drawTerazza(trz, bi, h, v);
		}
	}

	//スプライト配列内に、残像消去の対象になる、動いていて
	//表示中のスプライトが1つでもあるかどうかを判定する。1つでもあれば、
	//その残像消去に巻き込まれて他の静止スプライトが消えている可能性がある。
	static boolean hasMovingVisibleTerazza(List<Terazza> sprites){
		if(sprites==null) return false;
		for(Terazza trz: sprites){
			if(trz.visible && trz.speed>0) return true;
		}
		return false;
	}

	//スプライト配列内の表示中スプライトを、重ねた順(=配列の登録順)を
	//保ったまま全員描き直す。動いているスプライトは現在時刻から新しい位置・絵を
	//計算し、動いていないスプライトは元の位置・絵のまま描く。
	//残像消去(eraseTerazzaTrail、下地の描き戻し)によって他のスプライトが
	//巻き込まれて消えてしまった分の復元に使う。ここで登録順を保つことが重要で、
	//後から重ねた(=配列の後ろの)スプライトが速度に関わらず必ず上に来るようにする
	//(そうしないと、下になるはずの静止画像が動いているスプライトの上に
	//描き直されてしまい、動いている絵がほとんど隠れてしまう)。
	private static void redrawAllVisibleTerazza(){
		if(terazzaAry==null) return;
		ensureTerazzaOverlay(); //ウィンドウサイズが変わっていた場合に備えて確認しておく
		long nowMillis = new Date().getTime();
		for(int j=0; j<terazzaAry.size(); j++){
			Terazza trz = terazzaAry.get(j);
			if(!trz.visible) continue;
			if(trz.speed>0) {
				Double now = (double) ((nowMillis - trz.time)/1500.0*trz.speed);
				//System.out.println("now="+now+"     trz.time="+trz.time);
				if(now > 1000.0) now = 1000.0;
				int h = (int) ((trz.eh*(now)+trz.h*(1000.0-now))/1000.0);
				//System.out.println("h="+h+"   trz.eh="+trz.eh+"   trz.h="+trz.h);
				int v = (int) ((trz.ev*(now)+trz.v*(1000.0-now))/1000.0);
				int pictnum = trz.pictnum;
				if(now < 100.0) pictnum = trz.pictnums;
				if(now > 1000.0) pictnum = trz.pictnume;
				if(pictnum>0){
					drawTerazza(trz, pictnum, h, v);
				}else{
					if(trz.pictnum>0){
						String path = PCARD.pc.stack.rsrc.getFilePathAll(pictnum, "PICT");
						if(path!=null){
							//String path = PCARD.pc.stack.file.getParent()+File.separatorChar+fileName;
							BufferedImage bi = PictureFile.loadPICT(path);
							if(bi != null){
								//描く絵がないので、重ね合わせ用バッファ側は透明のままにしておく
								//(h,v,bi幅高さは論理座標。バッファは物理サイズなのでscale()倍する)
								Rectangle r = PCARD.toPhysical(h, v, bi.getWidth(), bi.getHeight());
								clearTerazzaOverlayRect(r.x, r.y, r.width, r.height);
								trz.lastX = r.x; trz.lastY = r.y;
								trz.lastW = r.width; trz.lastH = r.height;
							}
						}
					}
				}
			}else{
				drawTerazza(trz, trz.pictnum, trz.h, trz.v);
			}
		}
	}

	static void TerazzaAnime(){
		if(terazzaAry==null) return;
		//透明部分がある画像は下地を消さないので、複数のスプライトが重なっていても
		//全員分の残像を先に消してから、全員分を描き直す(片方ずつ消して描くと
		//隣のスプライトを巻き込んで消してしまうことがある)
		if(!hasMovingVisibleTerazza(terazzaAry)) return; //動いているスプライトがなければ描き直す必要はない
		for(int j=0; j<terazzaAry.size(); j++){
			Terazza trz = terazzaAry.get(j);
			if(trz.visible && trz.speed>0){
				eraseTerazzaTrail(trz);
			}
		}
		redrawAllVisibleTerazza();
		//このフレームでのバッファの更新(消去+重ね描き)をまとめて画面に反映する。
		//消去してから描き直すまでの間の状態を画面に一切見せないため、ここで
		//初めて画面に触れる。
		repaintTerazzaOverlay();
	}

	//移動コマンドから呼ばれる。現在の状態でフレームを1枚描画したうえで、
	//約30ms(TalkSleepが使っているのと同じ刻み)待つことで、実機で自然に生じていた
	//repeatループのペースを再現する。cmd+.による中断(TTalk.stop)にもすぐ反応できるよう
	//一度に30ms寝るのではなく10ms刻みで確認する。
	private static void terazzaMoveFramePause(){
		TerazzaAnime();
		int total = 30;
		int elapsed = 0;
		while(elapsed < total){
			if(TTalk.stop) return;
			int t = Math.min(10, total-elapsed);
			try{
				Thread.sleep(t);
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
				return;
			}
			elapsed += t;
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
				if(!Sandbox.allowFile(new File("./anime.gif"), Sandbox.Access.WRITE, Sandbox.scriptRequester())) throw new IOException("access not allowed");
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

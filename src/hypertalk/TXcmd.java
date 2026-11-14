import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;


public class TXcmd {

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
		if(HZ.home!=null && HZ.home.rsrc!=null && HZ.home.rsrc.hasXcmd(name, type)) return HZ.home;
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
		boolean any = st.rsrc!=null && st.rsrc.hasAnyXcmd() || HZ.home!=null && HZ.home.rsrc!=null && HZ.home.rsrc.hasAnyXcmd();
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

	public static Result CallExternalCommand(String message, String[] params, OObject target, MemoryData memData, boolean isFunc, Result result)
		throws xTalkException
	{
		OObject saved = callTarget.get();
		MemoryData savedVariables = HZ.callVariables.get();
		callTarget.set(target);
		HZ.callVariables.set(memData);
		try{ return callExternalCommand(message, params, target, memData, isFunc, result); }
		finally{ callTarget.set(saved); HZ.callVariables.set(savedVariables); }
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
		//外部コマンドがあれば、それがメッセージを受け取る。
		//以前は ret が「受け取り手なし」(1)のまま戻る分岐があり、文を式として評価し直して「showがわかりません」になっていた
		result.ret = 0;
		//The stack's own 68K external (or a using stack's, or Home's) runs in the 68K VM, as in HyperCard (a PowerPC-only one -
		//an xcmd/xfcn of HyperCard 2.3 - too, through the VM's Mixed Mode). The only built-in ones left are HyperCard's own
		//AddColor and Movie (HyperCard 2.2/2.4 carry them, stacks call them without a resource), FontExists and KStealKeyX
		//(the Mosslight RPG Engine sample registers a "java" KStealKeyX); every other external is the stack's code or
		//"Unimplemented" (the Java stand-ins for PgColorX, ColorizeHC, KStealKeyX2, UxAnswer, Terazza, … were removed). Myst Preview's Movie XCMD 1.2 has properties, callbacks and closeOnFinish that the
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
		else if(0==message.compareToIgnoreCase("FontExists")){
			if(params.length>=1){
				result.theResult = (Font.decode(params[0]).getFontName().equals(params[0]))?"true":"false";
			}
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
				AddColor.colorPict(pict, p, r, mode, effect, time);
			}
			result.ret = 0;
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
	//AddColor
	static class AddColor{
		static void remove(){
			PCARD.pc.stack.addColorHeapBytes = 0;
			PCARD.pc.stack.addColor = null;
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
			addToMainPane();
			PCARD.pc.stack.addColor = bi;
			if(PCARD.pc.mainPane!=null) PCARD.pc.mainPane.repaint();
		}

		/** Draws a PICT into the colour picture at p (its own size) or into r; the card's black stays on top of it */
		static void colorPict(String pict, Point p, Rectangle r, String mode, String effect, int time){
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
				//card picture's title showed through an opaque PICT drawn over them; "o" only keeps the picture's white (composite)
			}
			
			PCARD.pc.stack.addColor = bi;
			playEffect(number, time, area);
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

		/**
		 * Composites the AddColor buffer (logical card size) over {@code off}, the card as Swing painted it for the clip
		 * {@code x,y,w,h} in physical pixels: the card's black stays on top of the colours (AddColor, HC J-2.3). This used to be
		 * a JPanel on top of the parts, and a part that repainted
		 * itself (a put into a field) came back on top of the picture.
		 */
		static void composite(BufferedImage off, int x, int y, int w, int h){
			BufferedImage add = PCARD.pc.stack==null ? null : PCARD.pc.stack.addColor;
			if(add==null) return;
			int scale = PCARD.scalePercent();
			int[] offPixels = ((java.awt.image.DataBufferInt)off.getRaster().getDataBuffer()).getData(); //off: TYPE_INT_ARGB of w x h
			int[] addPixels = add.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferInt d ? d.getData() : null;
			int addWidth = add.getWidth(), addHeight = add.getHeight();
			int[] column = new int[w];
			for(int xx=0; xx<w; xx++) column[xx] = DisplayZoom.unscale(x+xx, scale);
			for(int yy=0; yy<h; yy++){
				int ay = DisplayZoom.unscale(y+yy, scale);
				if(ay<0 || ay>=addHeight) continue;
				int addRow = ay*addWidth, offRow = yy*w;
				for(int xx=0; xx<w; xx++){
					int ax = column[xx];
					if(ax<0 || ax>=addWidth) continue;
					int a = offPixels[offRow+xx];
					int b = addPixels!=null ? addPixels[addRow+ax] : add.getRaster().getDataBuffer().getElem(0, addRow+ax);
					if((b>>>24)==0) continue; //絵の無いところは部品がそのまま見える
					if((a>>>24)==0) a = 0xFFFFFFFF; //まだ描かれていない(透明)画素は白と同じ
					if(a==0xFFFFFFFF) offPixels[offRow+xx] = b;
					else{
						//透明モードの PICT を白黒のカードに srcOr で重ねた見え方: 白→絵の色、黒→黒、中間→暗くした色(乗算)
						//(以前の式は演算子の優先順位が崩れていて、黒だけたまたま残っていた)
						int pr = (((a>>16)&0xFF) * ((b>>16)&0xFF)) / 255;
						int pg = (((a>>8)&0xFF) * ((b>>8)&0xFF)) / 255;
						int pb = ((a&0xFF) * (b&0xFF)) / 255;
						offPixels[offRow+xx] = 0xFF000000 | (pr<<16) | (pg<<8) | pb;
					}
				}
			}
		}
	}
}

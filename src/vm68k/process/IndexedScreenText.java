/**
 * Recognises a program telling the user it needs a 256-colour screen: "Reset your monitor to 256 colors and try again",
 * "Set your monitor to 256-colors/greys", "このアプリケーションの実行には、256色表示可能な環境が必要です". Such a program runs
 * at 8 bits from the start (MacApp68k.screenDepthFor looks through its resources) or is restarted at 8 bits when it
 * shows the text after all (MacApp68k.textShown) - either way the user never has to find the per-program depth setting.
 *
 * A match is "256" followed by colo(u)r/grey/gray/shades, or by 色 in Shift-JIS, with a word about the monitor or a
 * requirement within {@link #CONTEXT} bytes, or "8-bit" followed by mode or a colour word with an actual request nearby
 * ({@link #REQUEST_WORDS}): an image editor's "Use Windows 256 Colors" export option or a resource
 * editor's "Standard 256 Colors" palette menu names no monitor and needs no screen. Benchmarks and palette/file-format
 * wording nearby rule a match out (Speedometer's "Color Quickdraw test for 256-color mode").
 */
final class IndexedScreenText {
	private IndexedScreenText(){}

	static final int CONTEXT=90;
	/** Words (lower case, Mac OS Roman / ASCII) saying the text is about the screen or a requirement. */
	private static final String[] SCREEN_WORDS={"monitor","mode","screen","display","require","need","must","switch","set your","set the","works","run","play"};
	/** The same in Shift-JIS: モニタ, モード, 表示, 環境, 必要, 切り替え, 起動, 動作. */
	private static final byte[][] SCREEN_WORDS_SJIS={
		{(byte)0x83,(byte)0x82,(byte)0x83,(byte)0x6a,(byte)0x83,(byte)0x5e},{(byte)0x83,(byte)0x82,(byte)0x81,(byte)0x5b,(byte)0x83,(byte)0x68},
		{(byte)0x95,(byte)0x5c,(byte)0x8e,(byte)0xa6},{(byte)0x8a,(byte)0xc2,(byte)0x8b,(byte)0xab},{(byte)0x95,(byte)0x4b,(byte)0x97,(byte)0x76},
		{(byte)0x90,(byte)0xd8,(byte)0x82,(byte)0xe8},{(byte)0x8b,(byte)0x4e,(byte)0x93,(byte)0xae},{(byte)0x93,(byte)0xae,(byte)0x8d,(byte)0xec}};
	/** Wording that makes it something else: a benchmark, a palette or file-format choice, a document's colours, a list of depths the program handles (thousands/millions), a limit of a mode. */
	private static final String[] OTHER_WORDS={"test","palette","standard","export","save","gif","bmp","jpeg","windows","vga","document","thousand","million","applicable","up to"};
	/**
	 * What an "8-bit" match needs nearby instead of {@link #SCREEN_WORDS}: an actual request. "8-bit mode" names a mode by
	 * itself, so "mode"/"run"/"display" say nothing - Fractal Wizard's help on pixel values "(0 in 8-bit mode, $FFFF in
	 * 16-bit mode)" and Fractal! manual's "When running on 8-bit color (or any clut type display)" ask for no depth.
	 */
	private static final String[] REQUEST_WORDS={"require","need","must","only","couldn","can't","cannot","switch","set the monitor","set your monitor","set the screen","set your screen"};
	/** The requests among {@link #SCREEN_WORDS_SJIS}: 必要, 切り替え. */
	private static final byte[][] REQUEST_WORDS_SJIS={SCREEN_WORDS_SJIS[4],SCREEN_WORDS_SJIS[5]};
	/** Wording that rules a match out only within its own string: a conversion to 8 bits (NIH Image), a mode's colour maximum (CFG's help), a request denied (Ingemar's skiing game: "8-bit color not required"),
	 *  an option that draws in that many colours whatever the screen shows (Infini-D 2.0.1's balloon help: "render to 16 colors regardless of the number of colors"). */
	private static final String[] SAME_STRING_WORDS={"conver","maximum","not requir","not need","no need","not necessary","regardless"};

	/**
	 * Warnings that name no depth but mean "not this one" to a program that only animates fast at 1, 4 or 8 bits: Ingemar's
	 * skiing game (Sprite Animation Toolkit) times a blit at start-up and says "This screen depth is not recommened [sic] when
	 * running ISG" at 16/32 bits. The 1990s hardware such text was written for topped out at 8 bits for games.
	 */
	private static final String[] DEPTH_WARNINGS={"screen depth is not recommen"};

	/** The first matching text in the program's resources (all types: strings, dialog items, code constants), or null. */
	static String findIn(ResourceFile68k resources){return findIn(resources,false);}
	/** The same for a request for a 16-colour screen ({@link #findSixteen}). */
	static String findSixteenIn(ResourceFile68k resources){return findIn(resources,true);}
	private static String findIn(ResourceFile68k resources,boolean sixteen){
		int text=ResourceFile68k.fourcc("TEXT");
		for(ResourceFile68k.Resource r:resources.all()){
			if(r.type==text)continue; // help and read-me pages explain colour depths (CFG's help on colour tables), they ask for nothing
			byte[] data;try{data=r.data();}catch(Exception e){continue;}
			if(data==null||data.length<6)continue;
			String hit=sixteen?findSixteen(data):find(data);if(hit!=null)return hit;
		}
		return null;
	}

	/** Wording that makes "16 colours" a minimum or one choice among several, not the one depth the program runs at. */
	private static final String[] SIXTEEN_OTHER_WORDS={"256","or more","at least","or greater","or higher","minimum","more colo","thousand","million"};
	/**
	 * The matching text around a request for a 16-colour screen in {@code data}, or null: "16 colo(u)rs"/"16-colour"/16色 with a
	 * word about the screen or a requirement nearby, as {@link #find} looks for 256. Color VETTE! (1991) insists on exactly 4 bits
	 * ("Please change your Monitors setting in the Control Panel to 16 colors." - it quits on 8 bits too), so such a program
	 * starts on a 16-colour screen. A minimum ("16 colors or more") or a list of depths does not count.
	 */
	static String findSixteen(byte[] data){
		for(int i=0;i+4<data.length;i++){
			if(data[i]!='1'||data[i+1]!='6'||i>0&&data[i-1]>='0'&&data[i-1]<='9')continue;
			int j=i+2;if(j<data.length&&(data[j]==' '||data[j]=='-'))j++;
			int end=colourWordEnd(data,j);if(end<0)continue;
			int from=Math.max(0,i-CONTEXT),to=Math.min(data.length,end+CONTEXT);
			if(!mentions(data,from,to,SCREEN_WORDS,SCREEN_WORDS_SJIS))continue;
			if(mentions(data,from,to,OTHER_WORDS,null)||mentions(data,from,to,SIXTEEN_OTHER_WORDS,null))continue;
			int sFrom=i,sTo=end;while(sFrom>from&&!boundary(data[sFrom-1]))sFrom--;while(sTo<to&&!boundary(data[sTo]))sTo++;
			if(mentions(data,sFrom,sTo,SAME_STRING_WORDS,null))continue;
			return text(data,Math.max(0,i-40),Math.min(data.length,end+30));
		}
		return null;
	}

	/** The matching text around the first request for 256 colours in {@code data}, or null. */
	static String find(byte[] data){
		for(int i=0;i+5<data.length;i++){
			for(String w:DEPTH_WARNINGS)if(startsWith(data,i,w))return text(data,i,Math.min(data.length,i+w.length()+40));
			int end=-1;boolean eightBit=false;
			if(data[i]=='2'&&data[i+1]=='5'&&data[i+2]=='6'){
				if(i>0&&data[i-1]>='0'&&data[i-1]<='9')continue; // 1256, 2560...
				int j=i+3;if(j<data.length&&(data[j]==' '||data[j]=='-'))j++;
				end=colourWordEnd(data,j);
			}else if(data[i]=='8'&&(i==0||data[i-1]<'0'||data[i-1]>'9')){ // "8-bit mode", "8 bit color" (Bert: "set the monitor to 1-, 4- or 8-bit mode")
				int j=i+1;if(j<data.length&&(data[j]==' '||data[j]=='-'))j++;
				if(startsWith(data,j,"bit")){j+=3;if(j<data.length&&data[j]==' ')j++;if(startsWith(data,j,"mode"))end=j+4;else end=colourWordEnd(data,j);}
				eightBit=true;
			}
			if(end<0)continue;
			int from=Math.max(0,i-CONTEXT),to=Math.min(data.length,end+CONTEXT);
			if(!(eightBit?mentions(data,from,to,REQUEST_WORDS,REQUEST_WORDS_SJIS):mentions(data,from,to,SCREEN_WORDS,SCREEN_WORDS_SJIS)))continue;
			if(mentions(data,from,to,OTHER_WORDS,null))continue;
			int sFrom=i,sTo=end;while(sFrom>from&&!boundary(data[sFrom-1]))sFrom--;while(sTo<to&&!boundary(data[sTo]))sTo++;
			if(mentions(data,sFrom,sTo,SAME_STRING_WORDS,null))continue; // these only in the request's own string (3d Brick Bash's next string mentions a "Paddle maximum")
			return text(data,Math.max(0,i-40),Math.min(data.length,end+30));
		}
		return null;
	}

	/** Where one string ends and the next begins in resource data: NUL, CR, or the control codes of a length byte or item record. */
	private static boolean boundary(byte b){return (b&0xff)<0x09||b==0x0d;}
	/** End of "colo(u)r"/"grey"/"gray"/"shades"/色 starting at {@code j}, or -1. */
	private static int colourWordEnd(byte[] d,int j){
		if(j+1<d.length&&(d[j]&0xff)==0x90&&(d[j+1]&0xff)==0x46)return j+2; // 色
		for(String w:new String[]{"colo","grey","gray","shade"})if(startsWith(d,j,w))return j+w.length();
		return -1;
	}
	private static boolean startsWith(byte[] d,int at,String w){
		if(at+w.length()>d.length)return false;
		for(int k=0;k<w.length();k++){int c=d[at+k]&0xff;if(c>='A'&&c<='Z')c+=32;if(c!=w.charAt(k))return false;}
		return true;
	}
	private static boolean mentions(byte[] d,int from,int to,String[] words,byte[][] sjis){
		for(int i=from;i<to;i++){
			for(String w:words)if(i+w.length()<=to&&startsWith(d,i,w))return true;
			if(sjis!=null)for(byte[] w:sjis)if(i+w.length<=to&&matchAt(d,i,w))return true;
		}
		return false;
	}
	private static boolean matchAt(byte[] d,int at,byte[] w){for(int k=0;k<w.length;k++)if(d[at+k]!=w[k])return false;return true;}
	/** Printable ASCII of the snippet (for the log). */
	private static String text(byte[] d,int from,int to){
		StringBuilder sb=new StringBuilder();
		for(int i=from;i<to;i++){int c=d[i]&0xff;sb.append(c>=32&&c<127?(char)c:c==13?' ':'.');}
		return sb.toString().trim();
	}
}

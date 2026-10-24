import java.util.ArrayList;
import java.util.List;

/**
 * Balloon Help (Pack 14, _Pack14 $A830; selector word in D0 with the argument words in its high byte). Balloons are
 * off until HMSetBalloons (the program's Help menu item) turns them on; then HMShowBalloon/HMShowMenuBalloon show the
 * program's own messages as a host tooltip window next to the tip, and the menu items get their 'hmnu' messages as
 * tooltips. The message resources ('hmnu', 'hdlg', 'hrct', 'hwin' share one component format) are parsed for
 * HMGetIndHelpMsg/HMExtractHelpMsg. HMShowBalloon shows every form: strings as text, a PICT (by ID or Handle) as the picture,
 * and styled text ('TEXT' with its 'styl', or a styled TERec: Rinaldi's FullBalloons "ShowTEXT"/"ShowField") drawn in its fonts,
 * sizes, faces and colours; a message whose resource is missing is resNotFound (FullBalloons: "Error : Resource not found").
 */
final class HelpManager68k {
	private static final int NO_ERR=0,PARAM_ERR=-50,RES_NOT_FOUND=-192,HM_HELP_DISABLED=-850,HM_SKIPPED_BALLOON=-857,HM_WRONG_VERSION=-858,HM_UNKNOWN_HELP_TYPE=-859,HM_NO_BALLOON_UP=-862;
	private static final int STRING=1,PICT=2,STRING_RES=3,TE_HANDLE=4,PICT_HANDLE=5,TE_RES=6,STR_RES=7,SKIP_ITEM=256,COMPARE_ITEM=512,NAMED_RESOURCE=1024;
	private final Toolbox68k tb;private final M68kMemory memory;
	/** A program's own Show Balloons (its VM is its own Mac); HyperCard's externals share {@link #hyperCardBalloons}. */
	private boolean balloons;private int font=0,fontSize=0,dialogResId=-1;private final java.util.Map<Integer,Integer> menuResIds=new java.util.HashMap<Integer,Integer>();
	private volatile boolean balloonUp;

	HelpManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/**
	 * Show Balloons for HyperCard's externals. On a Mac it is one system-wide setting, and HyperCard's externals all run in one
	 * HyperCard; here each external call may get a VM of its own that goes when the call returns: Inflate's / BHelp's "ON"
	 * (HMSetBalloons true) was lost with its one-shot VM and every later balloon answered "off" (HyperBalloons "Error,-855").
	 * HyperZebra's own menu can turn it on for stacks too ({@link #setHyperCardBalloons}); -Dhz68k.balloons=true starts with it on.
	 */
	private static volatile boolean hyperCardBalloons=Boolean.getBoolean("hz68k.balloons");
	/** Whether Balloon Help is on for HyperCard's externals. */
	static boolean hyperCardBalloons(){return hyperCardBalloons;}
	/** Turns Balloon Help on or off for every HyperCard external (a Show Balloons menu item; HMSetBalloons in an XCMD). */
	static void setHyperCardBalloons(boolean on){hyperCardBalloons=on;}
	/** An external's VM: the setting is HyperCard's (see {@link #hyperCardBalloons}). */
	private boolean hyperCard(){return tb.app==null&&tb.stack!=null;}
	boolean balloons(){return hyperCard()?hyperCardBalloons:balloons;}
	private void setBalloons(boolean on){if(hyperCard())hyperCardBalloons=on;else balloons=on;}

	boolean dispatch(M68kCpu cpu) throws M68kCpuException {
		int selector=cpu.d[0]&0xffff;int args=(selector>>>8)*2,sp=cpu.a[7];int result=NO_ERR;
		switch(selector&0xff){
			case 0x00:{int handlePtr=memory.readLong(sp);if(handlePtr!=0)memory.writeLong(handlePtr,tb.menus.helpMenuHandle());break;} // HMGetHelpMenuHandle
			case 0x01:{ // HMShowBalloon(aHelpMsg, tip, alternateRect, tipProc, theProc, variant, method)
				int msg=memory.readLong(sp+18),tip=memory.readLong(sp+14),hot=memory.readLong(sp+10);
				// Balloon Help off is hmHelpDisabled (Inflate 1.2's "Help balloons are not enabled."; -855 hmHelpManagerNotInited
				// made it "unknown Inflate error")
				if(!balloons())result=HM_HELP_DISABLED;
				else{Balloon b=balloon(msg);
					if(b.error!=0)result=b.error;else if(b.image!=null)showImage(b.image,(short)(tip&0xffff),(short)(tip>>16));
					else if(b.text.isEmpty())result=HM_SKIPPED_BALLOON;else show(b.text,(short)(tip&0xffff),(short)(tip>>16));
					if(result==NO_ERR&&hot!=0)removeWhenLeft(new int[]{(short)memory.readWord(hot),(short)memory.readWord(hot+2),(short)memory.readWord(hot+4),(short)memory.readWord(hot+6)});}
				if(Mac68kApi.TRACE)System.err.println("[68K] HMShowBalloon type "+(msg==0?-1:memory.readWord(msg))+" -> "+result);break;}
			case 0x05:{ // HMShowMenuBalloon(itemNum, itemMenuID, itemFlags, itemReserved, tip, alternateRect, tipProc, theProc, variant)
				int tip=memory.readLong(sp+12),menuId=(short)memory.readWord(sp+24),item=(short)memory.readWord(sp+26);
				String text=balloons()?menuHelp(menuId,item,true):null;
				if(!balloons())result=HM_HELP_DISABLED;else if(text==null||text.isEmpty())result=HM_SKIPPED_BALLOON;else show(text,(short)(tip&0xffff),(short)(tip>>16));break;}
			case 0x02:if(!balloonUp)result=HM_NO_BALLOON_UP;hide();break;                          // HMRemoveBalloon
			case 0x03:cpu.a[7]+=args;tb.writeBoolean(cpu.a[7],balloons());return true;            // HMGetBalloons
			case 0x04:{boolean on=memory.readByte(sp)!=0;if(balloons()!=on){setBalloons(on);if(!on)hide();tb.menus.markDirty();}break;} // HMSetBalloons
			case 0x07:cpu.a[7]+=args;tb.writeBoolean(cpu.a[7],balloonUp);return true;             // HMIsBalloon
			case 0x08:font=(short)memory.readWord(sp);break;case 0x09:fontSize=(short)memory.readWord(sp);break;
			case 0x0a:{int p=memory.readLong(sp);if(p!=0)memory.writeWord(p,font);break;}case 0x0b:{int p=memory.readLong(sp);if(p!=0)memory.writeWord(p,fontSize);break;}
			case 0x0c:dialogResId=(short)memory.readWord(sp);break;                                 // HMSetDialogResID
			case 0x0d:menuResIds.put((int)(short)memory.readWord(sp+2),(int)(short)memory.readWord(sp));break; // HMSetMenuResID(menuID, resID)
			case 0x0e:{int rectPtr=memory.readLong(sp),msg=memory.readLong(sp+4);Balloon b=balloon(msg);if(rectPtr!=0){int[] size=b.image!=null?new int[]{b.image.getWidth()+16,b.image.getHeight()+12}:balloonSize(b.text);memory.writeWord(rectPtr,0);memory.writeWord(rectPtr+2,0);memory.writeWord(rectPtr+4,size[1]);memory.writeWord(rectPtr+6,size[0]);}break;} // HMBalloonRect
			case 0x0f:{int pictPtr=memory.readLong(sp);if(pictPtr!=0)memory.writeLong(pictPtr,0);result=PARAM_ERR;break;} // HMBalloonPict: no picture rendering
			case 0x10:break;                                                                      // HMScanTemplateItems
			case 0x06:{ // HMGetIndHelpMsg(whichType, whichResID, whichMsg, whichState, VAR options, VAR tip, VAR altRect, VAR theProc, VAR variant, VAR aHelpMsg, VAR count)
				int countPtr=memory.readLong(sp),msgPtr=memory.readLong(sp+4),variantPtr=memory.readLong(sp+8),procPtr=memory.readLong(sp+12),rectPtr=memory.readLong(sp+16),tipPtr=memory.readLong(sp+20),optionsPtr=memory.readLong(sp+24);
				int state=(short)memory.readWord(sp+28),which=(short)memory.readWord(sp+30),resId=(short)memory.readWord(sp+32),type=memory.readLong(sp+34);
				Template t=template(type,resId);
				if(t==null){result=-192;break;}
				if(countPtr!=0)memory.writeWord(countPtr,t.components.size());if(optionsPtr!=0)memory.writeLong(optionsPtr,t.options);
				if(procPtr!=0)memory.writeWord(procPtr,t.proc);if(variantPtr!=0)memory.writeWord(variantPtr,t.variant);
				if(tipPtr!=0)memory.writeLong(tipPtr,0);if(rectPtr!=0)memory.fill(rectPtr,8,0);
				if(which<0||which>=t.components.size()){result=PARAM_ERR;break;}
				Component c=t.components.get(which);if(tipPtr!=0)memory.writeLong(tipPtr,c.tipV<<16|c.tipH&0xffff);if(rectPtr!=0)for(int i=0;i<4;i++)memory.writeWord(rectPtr+i*2,c.rect[i]);
				result=writeMessage(msgPtr,c,state);break;}
			case 0x11:{ // HMExtractHelpMsg(whichType, whichResID, whichMsg, whichState, VAR aHelpMsg)
				int msgPtr=memory.readLong(sp),state=(short)memory.readWord(sp+4),which=(short)memory.readWord(sp+6),resId=(short)memory.readWord(sp+8),type=memory.readLong(sp+10);
				Template t=template(type,resId);if(t==null){result=-192;break;}
				if(which<0||which>=t.components.size()){result=PARAM_ERR;break;}
				result=writeMessage(msgPtr,t.components.get(which),state);break;}
			case 0x13:{int p=memory.readLong(sp);if(p!=0)memory.writeWord(p,dialogResId);break;}   // HMGetDialogResID
			case 0x14:{int p=memory.readLong(sp),menuId=(short)memory.readWord(sp+4);Integer id=menuResIds.get(menuId);if(p!=0)memory.writeWord(p,id==null?-1:id);if(id==null)result=PARAM_ERR;break;} // HMGetMenuResID
			case 0x15:{int p=memory.readLong(sp);if(p!=0)memory.writeLong(p,0);break;}             // HMGetBalloonWindow: the balloon is a host window
			default:break;
		}
		cpu.a[7]+=args;memory.writeWord(cpu.a[7],result);return true;
	}

	/** The help text of a menu item for the tooltips of the host menus (null: balloons off or no 'hmnu' for the menu). */
	String menuHelp(int menuId,int item,boolean enabled){
		if(!balloons())return null;Integer resId=menuResIds.get(menuId);
		Template t=template(0x686d6e75,resId==null?menuId:resId);if(t==null)return null;
		int index=item+1;if(index<0||index>=t.components.size())return null; // 0 = missing items, 1 = the title, 2.. = items
		Component c=t.components.get(index);if(c.type==SKIP_ITEM){c=t.components.get(0);if(c.type==SKIP_ITEM)return null;}
		return messageOf(c,enabled?0:1);
	}

	// ------------------------------------------------------------------ balloon window

	private void show(String text,int h,int v){tb.host().showBalloon(text,h,v);balloonUp=true;} // the tip is in global coordinates
	private void showImage(java.awt.image.BufferedImage image,int h,int v){tb.host().showBalloonImage(image,h,v);balloonUp=true;}
	private void hide(){if(balloonUp)tb.host().hideBalloon();balloonUp=false;stopTracking();}
	/**
	 * HMShowBalloon's alternateRect is the balloon's hot rectangle (global): the Help Manager takes the balloon down once the
	 * pointer leaves it. XTernal Collection's BalloonHelp (from mouseEnter handlers) left every balloon up for good.
	 */
	private void removeWhenLeft(int[] hotRect){
		stopTracking();if(java.awt.GraphicsEnvironment.isHeadless())return;
		final MacAppHost host=tb.host();
		javax.swing.Timer timer=new javax.swing.Timer(150,null);
		timer.addActionListener(e->{
			if(!balloonUp){timer.stop();return;}
			java.awt.PointerInfo info=java.awt.MouseInfo.getPointerInfo();if(info==null)return;
			java.awt.Point p=host.toGuest(info.getLocation());
			if(p.y<hotRect[0]||p.x<hotRect[1]||p.y>=hotRect[2]||p.x>=hotRect[3]){timer.stop();if(balloonUp){balloonUp=false;host.hideBalloon();}}
		});
		tracking=timer;timer.start();
	}
	private void stopTracking(){javax.swing.Timer t=tracking;tracking=null;if(t!=null)t.stop();}
	private volatile javax.swing.Timer tracking;
	private static int[] balloonSize(String text){int lines=1,longest=0,current=0;for(char c:text.toCharArray()){if(c=='\r'||c=='\n'){lines++;current=0;}else{current++;longest=Math.max(longest,current);}}return new int[]{Math.min(220,longest*7+16),lines*14+12};}

	// ------------------------------------------------------------------ messages

	/** What a balloon shows: its text, or a picture (a PICT, or styled text drawn), or the error that keeps it from showing. */
	private static final class Balloon {String text="";java.awt.image.BufferedImage image;int error;}
	/** An HMMessageRecord: hmmHelpType, then a Str255 / PICT id / {STR# id, index} / TEHandle / PicHandle / TEXT id / STR id. */
	private Balloon balloon(int msg) throws M68kCpuException {
		Balloon b=new Balloon();
		if(msg==0){b.error=HM_UNKNOWN_HELP_TYPE;return b;}
		int type=memory.readWord(msg),id=(short)memory.readWord(msg+2);
		switch(type){
			case STRING:b.text=tb.readPascal(msg+2);break;
			case STRING_RES:{if(tb.resources.find(0x53545223,id)==null)b.error=RES_NOT_FOUND;else b.text=indexedString(id,(short)memory.readWord(msg+4));break;}
			case STR_RES:{ResourceFile68k.Resource r=tb.resources.find(0x53545220,id);if(r==null){b.error=RES_NOT_FOUND;break;}byte[] d=tb.resources.data(r);b.text=d.length==0?"":tb.decode(java.util.Arrays.copyOfRange(d,1,1+Math.min(d[0]&0xff,d.length-1)));break;}
			case TE_RES:{ResourceFile68k.Resource r=tb.resources.find(0x54455854,id);if(r==null){b.error=RES_NOT_FOUND;break;}
				ResourceFile68k.Resource s=tb.resources.find(0x7374796c,id);
				byte[] text=tb.resources.data(r);if(s==null)b.text=tb.decode(text);else b.image=styledText(text,tb.resources.data(s));break;}
			case TE_HANDLE:{int te=memory.readLong(msg+2);if(te==0||!tb.textEdit.isRecord(te))break;
				byte[] text=tb.textEdit.text(te),scrap=tb.textEdit.styleScrapOf(te);if(scrap==null)b.text=tb.decode(text);else b.image=styledText(text,scrap);break;}
			case PICT:{ResourceFile68k.Resource r=tb.resources.find(0x50494354,id);if(r==null){b.error=RES_NOT_FOUND;break;}b.image=tb.api.quickDraw().renderPicture(tb.resources.data(r));if(b.image==null)b.error=HM_SKIPPED_BALLOON;break;}
			case PICT_HANDLE:{int h=memory.readLong(msg+2);byte[] d=h==0?null:tb.api.handleContents(h);b.image=d==null?null:tb.api.quickDraw().renderPicture(d);if(b.image==null)b.error=HM_SKIPPED_BALLOON;break;}
			default:b.error=HM_UNKNOWN_HELP_TYPE;
		}
		return b;
	}
	/** The width a balloon's text is wrapped to. */
	private static final int BALLOON_TEXT_WIDTH=200;
	/**
	 * Styled text as the balloon shows it: the bytes in the fonts, sizes, faces and colours of a StScrpRec (scrpNStyles, then
	 * 20-byte ScrpSTElements: startChar, height, ascent, font, face, filler, size, RGB colour), wrapped at the balloon's width.
	 */
	java.awt.image.BufferedImage styledText(byte[] bytes,byte[] scrap){
		int n=scrap.length<2?0:MacApp68k.u2(scrap,0);
		java.util.List<String> parts=new ArrayList<String>();java.util.List<int[]> styles=new ArrayList<int[]>();
		for(int i=0;i<Math.max(1,n);i++){
			int o=2+i*20;boolean has=i<n&&o+20<=scrap.length;
			int start=has?Math.min(bytes.length,MacApp68k.u4(scrap,o)):0,end=i+1<n&&o+40<=scrap.length?Math.min(bytes.length,MacApp68k.u4(scrap,o+20)):bytes.length;
			if(end<=start)continue;
			parts.add(tb.decode(java.util.Arrays.copyOfRange(bytes,start,end)));
			styles.add(has?new int[]{(short)MacApp68k.u2(scrap,o+8),scrap[o+10]&0x7f,(short)MacApp68k.u2(scrap,o+12),MacApp68k.u2(scrap,o+14),MacApp68k.u2(scrap,o+16),MacApp68k.u2(scrap,o+18)}:new int[]{0,0,12,0,0,0});
		}
		StringBuilder all=new StringBuilder();for(String p:parts)all.append(p);
		String text=all.toString().replace('\n','\r');
		if(text.isEmpty())text=" ";
		java.util.List<java.text.AttributedString> lines=new ArrayList<java.text.AttributedString>();
		java.util.List<int[]> lineRanges=new ArrayList<int[]>();
		for(int a=0;a<=text.length();){int b=text.indexOf('\r',a);if(b<0)b=text.length();lineRanges.add(new int[]{a,b});a=b+1;if(b==text.length())break;}
		int[] runStart=new int[parts.size()+1];for(int i=0;i<parts.size();i++)runStart[i+1]=runStart[i]+parts.get(i).length();
		java.awt.image.BufferedImage scratch=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D sg=scratch.createGraphics();java.awt.font.FontRenderContext frc=sg.getFontRenderContext();sg.dispose();
		java.util.List<java.awt.font.TextLayout> layouts=new ArrayList<java.awt.font.TextLayout>();java.util.List<Integer> blank=new ArrayList<Integer>();
		int height=0,width=0;
		for(int[] lr:lineRanges){
			if(lr[1]==lr[0]){int[] st=styles.isEmpty()?new int[]{0,0,12}:styles.get(Math.max(0,runAt(runStart,lr[0])));int h=Fonts68k.font(st[0],st[2],st[1]).getSize()+4;layouts.add(null);blank.add(h);height+=h;continue;}
			java.text.AttributedString as=new java.text.AttributedString(text.substring(lr[0],lr[1]));
			for(int i=0;i<parts.size();i++){int a=Math.max(runStart[i],lr[0]),b=Math.min(runStart[i+1],lr[1]);if(b<=a)continue;int[] st=styles.get(i);
				as.addAttribute(java.awt.font.TextAttribute.FONT,Fonts68k.font(st[0],st[2],st[1]),a-lr[0],b-lr[0]);
				as.addAttribute(java.awt.font.TextAttribute.FOREGROUND,new java.awt.Color(st[3]>>8,st[4]>>8,st[5]>>8),a-lr[0],b-lr[0]);
				if((st[1]&4)!=0)as.addAttribute(java.awt.font.TextAttribute.UNDERLINE,java.awt.font.TextAttribute.UNDERLINE_ON,a-lr[0],b-lr[0]);}
			java.awt.font.LineBreakMeasurer m=new java.awt.font.LineBreakMeasurer(as.getIterator(),frc);
			while(m.getPosition()<lr[1]-lr[0]){java.awt.font.TextLayout l=m.nextLayout(BALLOON_TEXT_WIDTH);layouts.add(l);blank.add(0);
				height+=(int)Math.ceil(l.getAscent()+l.getDescent()+l.getLeading());width=Math.max(width,(int)Math.ceil(l.getAdvance()));}
		}
		java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(Math.max(1,width),Math.max(1,height),java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g=image.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		float y=0;for(int i=0;i<layouts.size();i++){java.awt.font.TextLayout l=layouts.get(i);if(l==null){y+=blank.get(i);continue;}y+=l.getAscent();l.draw(g,0,y);y+=l.getDescent()+l.getLeading();}
		g.dispose();
		return image;
	}
	private static int runAt(int[] runStart,int at){for(int i=0;i+1<runStart.length;i++)if(at<runStart[i+1])return i;return runStart.length-2;}
	private String indexedString(int resId,int index) throws M68kCpuException {
		ResourceFile68k.Resource r=tb.resources.find(0x53545223,resId);if(r==null)return "";byte[] d=tb.resources.data(r);if(d.length<2)return "";
		int count=MacApp68k.u2(d,0),p=2;for(int i=1;i<=count&&p<d.length;i++){int n=d[p]&0xff;if(i==index)return tb.decode(java.util.Arrays.copyOfRange(d,p+1,Math.min(d.length,p+1+n)));p+=1+n;}
		return "";
	}
	private String messageOf(Component c,int state){
		try{
			Object m=c.states[Math.max(0,Math.min(3,state))];if(m==null)return null;
			switch(c.type){
				case STRING:return (String)m;
				case STRING_RES:{int[] v=(int[])m;return indexedString(v[0],v[1]);}
				case STR_RES:{ResourceFile68k.Resource r=tb.resources.find(0x53545220,(Integer)m);if(r==null)return "";byte[] d=tb.resources.data(r);return d.length==0?"":tb.decode(java.util.Arrays.copyOfRange(d,1,1+Math.min(d[0]&0xff,d.length-1)));}
				case TE_RES:{ResourceFile68k.Resource r=tb.resources.find(0x54455854,(Integer)m);return r==null?"":tb.decode(tb.resources.data(r));}
				default:return "";
			}
		}catch(M68kCpuException e){return null;}
	}
	/** Fills an HMMessageRecord from a template component for a state (khmmString forms are copied as strings, resource forms by reference). */
	private int writeMessage(int msgPtr,Component c,int state) throws M68kCpuException {
		if(msgPtr==0)return PARAM_ERR;Object m=c.states[Math.max(0,Math.min(3,state))];
		switch(c.type){
			case STRING:memory.writeWord(msgPtr,STRING);tb.writePascal(msgPtr+2,m==null?"":(String)m,255);return NO_ERR;
			case STRING_RES:{int[] v=m==null?new int[]{0,0}:(int[])m;memory.writeWord(msgPtr,STRING_RES);memory.writeWord(msgPtr+2,v[0]);memory.writeWord(msgPtr+4,v[1]);return NO_ERR;}
			case STR_RES:case TE_RES:case PICT:memory.writeWord(msgPtr,c.type);memory.writeWord(msgPtr+2,m==null?0:(Integer)m);return NO_ERR;
			case SKIP_ITEM:return HM_SKIPPED_BALLOON;
			default:return HM_UNKNOWN_HELP_TYPE;
		}
	}

	// ------------------------------------------------------------------ 'hmnu' / 'hdlg' templates

	static final class Component {int type,tipH,tipV;final int[] rect=new int[4];final Object[] states=new Object[4];}
	static final class Template {int version,options,proc,variant;final List<Component> components=new ArrayList<Component>();}
	private final java.util.Map<Long,Template> templates=new java.util.HashMap<Long,Template>();

	/**
	 * Header: version, options, balloonProcID, variantCode, count; then components each of {size, hmmHelpType, [tip, rect
	 * for 'hdlg'/'hrct'], then four messages (enabled, disabled, checked, other) in the type's form}. hmmSkipItem has no
	 * messages, hmmCompareItem starts with the string to compare, hmmNamedResourceItem with the resource name.
	 */
	private Template template(int type,int resId){
		long key=(long)type<<32|(resId&0xffffffffL);Template cached=templates.get(key);if(cached!=null)return cached;
		ResourceFile68k.Resource r=tb.resources.find(type,resId);if(r==null)return null;
		byte[] d;try{d=tb.resources.data(r);}catch(M68kCpuException e){return null;}
		if(d.length<12)return null;Template t=new Template();t.version=MacApp68k.u2(d,0);t.options=MacApp68k.u4(d,2);t.proc=(short)MacApp68k.u2(d,6);t.variant=(short)MacApp68k.u2(d,8);
		int count=MacApp68k.u2(d,10),p=12;boolean geometry=type==0x68646c67||type==0x68726374; // hdlg, hrct: tip and rect per component
		while(p+4<=d.length){ // components until the data ends (count is not reliable across the template kinds)
			int size=MacApp68k.u2(d,p),kind=MacApp68k.u2(d,p+2);if(size<4)break;int q=p+4,end=Math.min(d.length,p+size);
			Component c=new Component();c.type=kind;
			if(kind==COMPARE_ITEM||kind==NAMED_RESOURCE){int n=q<end?d[q]&0xff:0;q+=1+n;if((q&1)!=0)q++;if(kind==COMPARE_ITEM&&q+2<=end){c.type=MacApp68k.u2(d,q);q+=2;}}
			if(geometry&&q+12<=end){c.tipV=(short)MacApp68k.u2(d,q);c.tipH=(short)MacApp68k.u2(d,q+2);for(int k=0;k<4;k++)c.rect[k]=(short)MacApp68k.u2(d,q+4+k*2);q+=12;}
			for(int state=0;state<4&&q<end;state++){
				switch(c.type){
					case STRING:{int n=d[q]&0xff;c.states[state]=tb.decode(java.util.Arrays.copyOfRange(d,q+1,Math.min(end,q+1+n)));q+=1+n;break;}
					case STRING_RES:if(q+4<=end)c.states[state]=new int[]{(short)MacApp68k.u2(d,q),(short)MacApp68k.u2(d,q+2)};q+=4;break;
					case PICT:case TE_RES:case STR_RES:if(q+2<=end)c.states[state]=(int)(short)MacApp68k.u2(d,q);q+=2;break;
					default:q=end;break;
				}
			}
			t.components.add(c);p+=size+(size&1);
		}
		templates.put(key,t);return t;
	}
}

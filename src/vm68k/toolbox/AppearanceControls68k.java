import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Appearance Manager's standard control definitions (Appearance 1.x of Mac OS 8/9, as CarbonLib has them) in Java, drawn
 * in the Platinum look: static text, edit text (a TextEdit record inside, keyboard focus, key filter, password bullets),
 * group boxes (text, check box and pop-up titles), user panes (the program's procedures called through Mixed Mode),
 * separators, placards, window headers, bevel buttons (text, icon or picture content, a pop-up menu, push/toggle/sticky
 * behaviour), image wells, tabs, pop-up arrows, little arrows, chasing arrows (animated at idle time), disclosure triangles,
 * icons, pictures, clocks, scrolling text boxes, list boxes (a List Manager list inside), radio groups, sliders and the
 * Appearance push buttons, check boxes and radio buttons (procIDs 368-375).
 * <p>
 * Each control is an ordinary ControlHandle whose definition is a {@link ControlManager68k.NativeDefinition}: the Control
 * Manager hands it drawing, hit testing, tracking and keys, and the Appearance Manager's ControlDispatch hands it
 * Set/GetControlData, keyboard focus, idle time and activation. The content set through the data tags lives here, as the
 * Appearance CDEFs keep it. A program's own CDEF resource of the same ID still wins (ControlManager68k.attachDefProc).
 * <p>
 * Creation follows NewControl's overloaded fields, as the CDEFs' initCntl reads them: a bevel button's minimum is its
 * behaviour + content type, its maximum the content's resource ID and its value a menu ID; the tab control's value names a
 * 'tab#' resource, the list box's an 'ldes', the picture's and icon's value their resource IDs, the scrolling text box's a
 * 'TEXT', the clock's its flags and the user pane's its feature bits.
 * <p>
 * Without an embedding hierarchy, a tab control redraws the controls created after it that lie inside its pane (as the
 * Control Manager redraws an embedder's subcontrols); radio groups keep only their value.
 */
final class AppearanceControls68k {
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final ControlManager68k cm;
	AppearanceControls68k(Toolbox68k tb,ControlManager68k cm){this.tb=tb;this.memory=tb.memory;this.cm=cm;}

	/** CDEF resource IDs (procID >> 4) of the kinds defined here. */
	static final int BEVEL=2,SLIDER=3,TRIANGLE=4,LITTLE_ARROWS=6,CHASING_ARROWS=7,TABS=8,SEPARATOR=9,GROUP_BOX=10,IMAGE_WELL=11,POPUP_ARROW=12,PLACARD=14,CLOCK=15,
		USER_PANE=16,EDIT_TEXT=17,STATIC_TEXT=18,PICTURE=19,ICON=20,WINDOW_HEADER=21,LIST_BOX=22,BUTTONS=23,RADIO_GROUP=26,SCROLL_TEXT=27,EDIT_UNICODE=57;
	/** Whether a procID is one of the kinds defined here (kControlProgressBarProc stays the Control Manager's own). */
	static boolean handles(int procId){
		switch(procId>>4){
			case BEVEL:case SLIDER:case TRIANGLE:case LITTLE_ARROWS:case CHASING_ARROWS:case TABS:case SEPARATOR:case GROUP_BOX:case IMAGE_WELL:case POPUP_ARROW:case PLACARD:case CLOCK:
			case USER_PANE:case EDIT_TEXT:case STATIC_TEXT:case PICTURE:case ICON:case WINDOW_HEADER:case LIST_BOX:case BUTTONS:case RADIO_GROUP:case SCROLL_TEXT:case EDIT_UNICODE:return true;
			default:return false;
		}
	}

	// ControlPartCodes
	static final int PART_MENU=2,PART_TRIANGLE=4,PART_EDIT_TEXT=5,PART_PICTURE=6,PART_ICON=7,PART_CLOCK=8,PART_BUTTON=10,PART_CHECK_BOX=11,PART_UP=20,PART_DOWN=21,
		PART_PAGE_UP=22,PART_PAGE_DOWN=23,PART_LIST_BOX=24,PART_LIST_DOUBLE_CLICK=25,PART_IMAGE_WELL=26,PART_INDICATOR=129;
	static final int NO_ERR=0,PARAM_ERR=-50,ERR_DATA_NOT_SUPPORTED=-30581,ERR_INVALID_PART_CODE=-30588;
	// ControlFeatures
	private static final int F_GHOSTING=1,F_EMBEDDING=1<<1,F_FOCUS=1<<2,F_IDLE=1<<3,F_ACTIVATE=1<<4,F_TRACKING=1<<5,F_DATA=1<<6,F_FOCUS_ON_CLICK=1<<8,F_BEST_RECT=1<<9,F_LIVE=1<<10,F_RADIO=1<<11;
	private static int tag(String s){return s.charAt(0)<<24|s.charAt(1)<<16|s.charAt(2)<<8|s.charAt(3);}
	static final int T_FONT=tag("font"),T_FILTER=tag("fltr"),T_TEXT=tag("text"),T_CFSTRING=tag("cfst"),T_TRUNC=tag("trun"),T_TEXT_HEIGHT=tag("thei"),
		T_TE=tag("than"),T_SELECTION=tag("sele"),T_PASSWORD=tag("pass"),T_LOCKED=tag("lock"),
		T_CONTENT=tag("cont"),T_TRANSFORM=tag("tran"),T_TEXT_ALIGN=tag("tali"),T_TEXT_OFFSET=tag("toff"),T_GRAPHIC_ALIGN=tag("gali"),T_GRAPHIC_OFFSET=tag("goff"),T_TEXT_PLACE=tag("tplc"),
		T_MENU_VALUE=tag("mval"),T_MENU=tag("mhnd"),T_OWNED_MENU=tag("omrf"),T_LAST_MENU=tag("lmnu"),
		T_TAB_RECT=tag("rect"),T_TAB_ENABLED=tag("enab"),T_TAB_INFO=tag("tabi"),T_POPUP_MENU=tag("mhan"),T_TITLE_RECT=tag("trec"),T_LAST_VALUE=tag("last"),T_ANIMATING=tag("anim"),
		T_PICTURE=tag("pich"),T_ICON_TRANSFORM=tag("trfm"),T_ICON_ALIGN=tag("algn"),T_ICON_RES=tag("ires"),T_LIST=tag("lhan"),T_DOUBLE_CLICK=tag("dblc"),T_DEFAULT=tag("dflt"),
		T_SCROLL_DELAY=tag("stdl"),T_SCROLL_BETWEEN=tag("scdl"),T_SCROLL_AMOUNT=tag("samt"),T_SCROLL_RES=tag("tres"),T_CLOCK_DATE=tag("date"),
		T_PANE_DRAW=tag("draw"),T_PANE_HIT=tag("hitt"),T_PANE_TRACK=tag("trak"),T_PANE_IDLE=tag("idle"),T_PANE_KEY=tag("keyd"),T_PANE_ACTIVATE=tag("acti"),T_PANE_FOCUS=tag("foci");
	/** Inline words of the Toolbox routines called from here. */
	private static final int[] SET_PORT={0xa873},GET_MENU_HANDLE={0xa949},GET_MENU={0xa9bf},POPUP_MENU_SELECT={0xa80b},CHECK_ITEM={0xa945},TE_IDLE={0xa9da},RGB_BACK_COLOR={0xaa15},
		LNEW={0x3f3c,0x0044,0xa9e7},LUPDATE={0x3f3c,0x0064,0xa9e7},LCLICK={0x3f3c,0x0018,0xa9e7},LACTIVATE={0x4267,0xa9e7},LDISPOSE={0x3f3c,0x0028,0xa9e7},
		PLOT_ICON_ID={0x303c,0x0500,0xabc9},PLOT_ICON_SUITE={0x303c,0x0603,0xabc9},PLOT_ICON_REF={0x700e,0xaa75},GET_CICON={0xaa1e},PLOT_CICON={0xaa1f},DISPOSE_CICON={0xaa25},
		GET_ICON={0xa9bb},PLOT_ICON={0xa94b},GET_PICTURE={0xa9bc},DRAW_PICTURE={0xa8f6};
	// Platinum colours
	static final int BLACK=0,WHITE=0xffffff,FACE=0xdddddd,LIGHT=0xeeeeee,SHADOW=0x888888,GREY=0x999999,PALE=0xaaaaaa,MID=0xcccccc,PRESSED=0x777777,INK_OFF=0x777777,FOCUS=0x6666cc;
	private static final byte BULLET=(byte)0xa5; // '•' in Mac Roman

	// ------------------------------------------------------------------ one control

	/** One control of an Appearance kind: its data tags and the state of its kind. */
	final class Def implements ControlManager68k.NativeDefinition {
		final int handle,procId,kind,variant;
		private final Map<Long,byte[]> tags=new HashMap<Long,byte[]>();
		boolean focused,ringDrawn,atEnd;
		/** Edit text: the TERec Handle; a password field's clear text (the record shows bullets). */
		int te;byte[] secret;
		/** List box: its ListHandle. Bevel buttons: behaviour bits. User pane: features. */
		int list,behaviour,features;
		/** Chasing arrows: the frame; scrolling text box: pixels scrolled and the tick of the next step; clock: seconds added to the host's time. */
		int phase,scrollTop,nextTick;long clockOffset;
		byte[] boxText;String shown="";int[] ghost;
		/** A tag's value, else the default the Appearance CDEFs answer for a standard tag nobody set ('dflt' false ...). */
		byte[] orDefault(byte[] v,int tag){return v!=null?v:AppearanceManager68k.standardTagDefault(tag);}
		Def(int handle,int procId){this.handle=handle;this.procId=procId;kind=procId>>4;variant=procId&15;}
		byte[] tag(int part,int tag){return tags.get(slot(part,tag));}
		void put(int part,int tag,byte[] v){tags.put(slot(part,tag),v);}
		int word(int tag,int dflt){byte[] v=tag(0,tag);return v==null||v.length<2?dflt:s16(v,0);}
		int longv(int tag,int dflt){byte[] v=tag(0,tag);return v==null||v.length<4?dflt:be32(v,0);}
		boolean bool(int tag,boolean dflt){byte[] v=tag(0,tag);return v==null||v.length<1?dflt:v[0]!=0;}
		/** An edit text control (Dialog Manager Tab order). */
		boolean takesText(){return kind==EDIT_TEXT||kind==EDIT_UNICODE;}
		/** kControlUsesOwningWindowsFontVariant, for the kinds whose bit 3 means that. */
		boolean windowFont(){return (variant&8)!=0&&(kind==EDIT_TEXT||kind==STATIC_TEXT||kind==GROUP_BOX||kind==BEVEL||kind==BUTTONS||kind==EDIT_UNICODE);}

		@Override public void draw(int h) throws M68kException {paintControl(this);}
		@Override public int test(int h,int x,int y) throws M68kException {return hit(this,x,y);}
		@Override public int track(int h,int x,int y,int modifiers) throws M68kException {return trackControl(this,x,y,modifiers,0);}
		@Override public int track(int h,int x,int y,int modifiers,int action) throws M68kException {return trackControl(this,x,y,modifiers,action);}
		@Override public int key(int h,int keyCode,int charCode,int modifiers) throws M68kException {return keyDown(this,keyCode,charCode,modifiers);}
		@Override public void dispose(int h) throws M68kException {disposeControl(this);}
		@Override public int setData(int h,int part,int tag,int size,int data) throws M68kException {
			int n=Math.max(0,Math.min(size,16<<20));return set(this,part,tag,data==0?new byte[n]:memory.readBytes(data,n));}
		@Override public int getData(int h,int part,int tag,int bufferSize,int buffer,int actualSize) throws M68kException {return answer(orDefault(get(this,part,tag),tag),bufferSize,buffer,actualSize);}
		@Override public int focus(int h,int part) throws M68kException {return focusControl(this,part);}
		@Override public void idle(int h) throws M68kException {idleControl(this);}
		@Override public void activate(int h,boolean active) throws M68kException {activateControl(this,active);}
		@Override public int features(int h){return featuresOf(this);}
		@Override public int[] bestRect(int h) throws M68kException {return best(this);}
	}

	/** The definition of a new control of an Appearance kind, set up from NewControl's fields as its CDEF's initCntl does. */
	ControlManager68k.NativeDefinition attach(int handle,int procId) throws M68kException {Def c=new Def(handle,procId);init(c);return c;}

	private void init(Def c) throws M68kException {
		int window=cm.ownerWindow(c.handle);
		switch(c.kind){
			case BEVEL:{int min=min(c),max=max(c),menuId=value(c),type=min&0xff;c.behaviour=min&0xff00; // min: behaviour + content type, max: its resource, value: a menu
				if(type!=0)c.put(0,T_CONTENT,new byte[]{0,(byte)type,(byte)(max>>8),(byte)max,0,0});
				setFields(c,0,0,1);
				if(menuId!=0){int menu=call(4,GET_MENU_HANDLE,menuId,2);if(menu==0)menu=call(4,GET_MENU,menuId,2);if(menu!=0){c.put(0,T_MENU,be(menu));c.put(0,T_MENU_VALUE,word(0));}}
				break;}
			case TABS:{int id=value(c);ResourceFile68k.Resource res=id==0?null:tb.resources.find(0x74616223,id); // 'tab#': version 0, count, {iconSuiteID, pstring name, 6 reserved bytes}
				if(res!=null){byte[] b=tb.resources.data(res);int n=b.length>=4&&s16(b,0)==0?u16(b,2):0,p=4,count=0;
					for(int i=0;i<n&&p+3<=b.length;i++){int len=b[p+2]&255,l=Math.min(len,b.length-p-3);byte[] info=new byte[260];info[2]=b[p];info[3]=b[p+1];info[4]=(byte)l;System.arraycopy(b,p+3,info,5,l);
						c.put(i+1,T_TAB_INFO,info);p+=3+len+6;count++;}
					setFields(c,count>0?1:0,count>0?1:0,count);}
				else clampValue(c);
				break;}
			case LIST_BOX:{int id=value(c);ResourceFile68k.Resource res=id==0?null:tb.resources.find(0x6c646573,id); // 'ldes'
				if(res!=null){byte[] b=tb.resources.data(res);if(b.length>=18)createList(c,window,s16(b,2),s16(b,4),s16(b,6),s16(b,8),b[10]!=0,b[12]!=0,s16(b,14),b[16]!=0);}
				setFields(c,0,0,0);break;}
			case EDIT_TEXT:case EDIT_UNICODE:newTextEdit(c,window);break;
			case USER_PANE:c.features=value(c);break; // the initial value carries the pane's features
			case SCROLL_TEXT:loadBoxText(c,value(c));c.nextTick=ticks()+c.longv(T_SCROLL_DELAY,120);break;
			case PICTURE:case ICON:case CLOCK:break; // (the value names the resource / carries the flags)
			default:clampValue(c);break; // NewControl handed the value over unclamped (see ControlManager68k.create)
		}
	}

	// ------------------------------------------------------------------ record fields and small helpers

	private int rec(Def c) throws M68kCpuException {return memory.readLong(c.handle);}
	private int[] bounds(Def c) throws M68kCpuException {int d=rec(c);return new int[]{(short)memory.readWord(d+8),(short)memory.readWord(d+10),(short)memory.readWord(d+12),(short)memory.readWord(d+14)};}
	private int value(Def c) throws M68kCpuException {return (short)memory.readWord(rec(c)+18);}
	private int min(Def c) throws M68kCpuException {return (short)memory.readWord(rec(c)+20);}
	private int max(Def c) throws M68kCpuException {return (short)memory.readWord(rec(c)+22);}
	private int hilite(Def c) throws M68kCpuException {return memory.readByte(rec(c)+17);}
	/** Active: not kControlInactivePart (255) or kControlDisabledPart (254). */
	private boolean active(Def c) throws M68kCpuException {return hilite(c)<254;}
	private void clampValue(Def c) throws M68kCpuException {int v=value(c),lo=min(c),hi=max(c);if(v<lo||v>hi)memory.writeWord(rec(c)+18,Math.max(lo,Math.min(Math.max(lo,hi),v)));}
	private void setFields(Def c,int value,int min,int max) throws M68kCpuException {int d=rec(c);memory.writeWord(d+18,value);memory.writeWord(d+20,min);memory.writeWord(d+22,max);}
	private String title(Def c) throws M68kCpuException {String own=ownTitle(c),t=own.length()<2||tb.translator()==null?null:tb.translated(VMTranslation.Kind.UI,own);return t==null?own:t;}
	/** The title as the program set it (title() gives its translation when there is one: VMTranslation). */
	private String ownTitle(Def c) throws M68kCpuException {int d=rec(c);return tb.decodeTitle(memory.readBytes(d+41,memory.readByte(d+40)));}
	private int ticks() throws M68kCpuException {return memory.readLong(0x16a);}
	private boolean japanese(){return tb.encoding==StackTextEncoding.JAPANESE;}
	private int background(int window) throws M68kCpuException {return tb.api.quickDraw().colorsOf(window)[1];}
	private String cfText(int ref){return ref==0||tb.app==null||tb.app.ppc==null?null:tb.app.ppc.libraries.textServices.cfText(ref);}
	/** A new CFString (one reference, for the caller to release) of text in the program's encoding; null for a 68K program. */
	private byte[] cfString(byte[] text) throws M68kCpuException {
		if(tb.app==null||tb.app.ppc==null)return null;return be(tb.app.ppc.libraries.textServices.newCFString(tb.decode(text==null?new byte[0]:text)));}
	private static int s16(byte[] b,int o){return (short)((b[o]&255)<<8|b[o+1]&255);}
	private static int u16(byte[] b,int o){return (b[o]&255)<<8|b[o+1]&255;}
	private static int be32(byte[] b,int o){return b.length<o+4?0:(b[o]&255)<<24|(b[o+1]&255)<<16|(b[o+2]&255)<<8|b[o+3]&255;}
	private static byte[] be(int v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
	private static byte[] word(int v){return new byte[]{(byte)(v>>8),(byte)v};}
	private static byte[] rectBytes(int[] r){byte[] b=new byte[8];for(int i=0;i<4;i++){b[2*i]=(byte)(r[i]>>8);b[2*i+1]=(byte)r[i];}return b;}
	private static long slot(int part,int tag){return ((long)(part&0xffff)<<32)|(tag&0xffffffffL);}
	private static boolean inside(int[] r,int h,int v){return r!=null&&h>=r[1]&&h<r[3]&&v>=r[0]&&v<r[2];}
	private static int[] outset(int[] r,int n){return new int[]{r[0]-n,r[1]-n,r[2]+n,r[3]+n};}
	/** The 8-bit RGB of an RGBColor at {@code o}. */
	private static int rgb8(byte[] b,int o){return (b[o]&255)<<16|(b[o+2]&255)<<8|b[o+4]&255;}
	private void writeRect(int p,int[] r) throws M68kCpuException {memory.writeWord(p,r[0]);memory.writeWord(p+2,r[1]);memory.writeWord(p+4,r[2]);memory.writeWord(p+6,r[3]);}
	private static byte[] splice(byte[] b,int start,int end,byte[] with){byte[] r=new byte[b.length-(end-start)+with.length];System.arraycopy(b,0,r,0,start);System.arraycopy(with,0,r,start,with.length);System.arraycopy(b,end,r,start+with.length,b.length-end);return r;}

	/** GetControlData's copy-out: the bytes into the buffer (as many as fit), their size into *actualSize. */
	private int answer(byte[] v,int bufferSize,int buffer,int actualSize) throws M68kCpuException {
		if(v==null)return ERR_DATA_NOT_SUPPORTED;
		if(buffer!=0&&bufferSize>0)memory.load(buffer,Arrays.copyOf(v,Math.min(v.length,bufferSize)));
		if(actualSize!=0)memory.writeLong(actualSize,v.length);
		return NO_ERR;
	}

	// ------------------------------------------------------------------ calling the Toolbox and the program

	private interface Body{void run() throws M68kException;}
	/**
	 * Runs a Toolbox routine from Java: its inline words (a trap, or a selector move/push and the trap) in a small stub,
	 * Pascal arguments as value,size pairs pushed left to right (size 1: a Boolean, in the high byte of a word), a result of
	 * {@code resultBytes} (1: a Boolean) above them.
	 */
	private int call(int resultBytes,int[] inline,int... valueSize) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();int stub=memory.allocate(2*inline.length+8,4);
		try{
			int at=stub;for(int w:inline){memory.writeWord(at,w);at+=2;}memory.writeWord(at,0x4ef9);memory.writeLong(at+2,M68kCpu.RETURN_SENTINEL);
			int room=resultBytes==1?2:resultBytes;if(room>0){cpu.a[7]-=room;memory.fill(cpu.a[7],room,0);}
			int slot=cpu.a[7],bytes=push(cpu,valueSize);
			cpu.runUntilSentinel(stub,bytes);
			return resultBytes==1?memory.readByte(slot):resultBytes==2?(short)memory.readWord(slot):resultBytes==4?memory.readLong(slot):0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);memory.free(stub);}
	}
	/** Calls a program's Pascal routine - a 68K ProcPtr or a routine descriptor (a PowerPC UPP enters through Mixed Mode). */
	private int callProc(int proc,int resultBytes,int... valueSize) throws M68kException {
		if(proc==0)return 0;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		try{
			int room=resultBytes==1?2:resultBytes;if(room>0){cpu.a[7]-=room;memory.fill(cpu.a[7],room,0);}
			int slot=cpu.a[7],bytes=push(cpu,valueSize);
			cpu.callPascal(proc,bytes);
			return resultBytes==1?memory.readByte(slot):resultBytes==2?(short)memory.readWord(slot):resultBytes==4?memory.readLong(slot):0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
	}
	private int push(M68kCpu cpu,int[] valueSize) throws M68kCpuException {
		int bytes=0;
		for(int i=0;i+1<valueSize.length;i+=2){int v=valueSize[i],size=valueSize[i+1];
			if(size==4){cpu.pushLong(v);bytes+=4;}else{cpu.pushWord(size==1?(v!=0?0x100:0):v);bytes+=2;}}
		return bytes;
	}
	/** ControlActionProc(control, part). */
	private void callAction(int proc,Def c,int part) throws M68kException {callProc(proc,0,c.handle,4,part,2);}
	/** Runs {@code body} with {@code port} as QuickDraw's current port. */
	private void inPort(int port,Body body) throws M68kException {
		port=tb.quickDraw.controlPort(port); // DrawControlInCurrentPort: the current port instead of the window
		int saved=tb.api.quickDraw().currentPortAddress();
		if(port!=0&&saved!=port)call(0,SET_PORT,port,4);
		try{body.run();}finally{if(port!=0&&saved!=port&&saved!=0)call(0,SET_PORT,saved,4);}
	}
	/** Runs {@code body} in the window's port with a white background colour: an edit field's text area is white in any window. */
	private void onWhite(int owner,Body body) throws M68kException {
		int window=tb.quickDraw.controlPort(owner);
		if(window==0||(memory.readWord(window+6)&0xc000)!=0xc000){inPort(window,body);return;} // a basic GrafPort keeps its own background
		inPort(window,()->{
			int rgb=memory.allocate(6,2);byte[] saved=memory.readBytes(window+42,6); // rgbBkColor
			try{memory.fill(rgb,6,0xff);call(0,RGB_BACK_COLOR,rgb,4);body.run();}
			finally{memory.load(rgb,saved);call(0,RGB_BACK_COLOR,rgb,4);memory.free(rgb);}});
	}

	// ------------------------------------------------------------------ drawing helpers

	private interface Painter{void paint(Graphics2D g) throws M68kException;}
	/** Java2D over the control's window within {@code area} (window-local): straight into its surface, else an image drawn through QuickDraw. */
	private void paint(int window,int[] area,Painter p) throws M68kException {
		if(area[2]<=area[0]||area[3]<=area[1])return;
		Graphics2D g=tb.quickDraw.windowSurface(window,area[0],area[1],area[2],area[3]);BufferedImage staged=null;
		if(g==null){
			int w=area[3]-area[1],h=area[2]-area[0];if((long)w*h>16_777_216L)return;
			staged=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);g=staged.createGraphics();g.translate(-area[1],-area[0]);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());
		}
		try{p.paint(g);}finally{g.dispose();}
		if(staged!=null){BufferedImage image=staged;inPort(window,()->tb.quickDraw.drawImage(image,area[0],area[1],area[2],area[3]));}
		tb.quickDraw.flushSurfaces();
	}
	private static void fill(Graphics2D g,int[] r,int rgb){Look.fill(g,r[0],r[1],r[2],r[3],rgb);}
	private static void frame(Graphics2D g,int[] r,int rgb){Look.frame(g,r[0],r[1],r[2],r[3],rgb);}
	private static void text(Graphics2D g,Font f,String s,int x,int baseline,int rgb){if(s.isEmpty())return;g.setColor(new Color(rgb));Fonts68k.drawString(g,f,s,x,baseline);}
	/** A theme draw state: inactive 0, active 1, pressed 2. */
	private static int state(boolean active,boolean pressed){return !active?0:pressed?2:1;}

	/** {font number, size, face} of a theme font: 0 system, 1 small system, 2 small emphasized, 3 views, 5 application (Osaka on a Japanese system). */
	private int[] themeSpec(int id){
		boolean j=japanese();int small=Fonts68k.numberOf(j?"Osaka":"Geneva");
		switch(id){
			case 1:case 6:return new int[]{small,10,0};                 // small system, label
			case 2:return new int[]{small,10,1};                        // small emphasized
			case 3:return new int[]{small,j?12:10,0};                   // views
			case 4:case 107:return new int[]{0,12,1};                   // emphasized system, alert header
			case 5:return new int[]{small,12,0};                        // application
			case 106:return new int[]{small,9,0};                       // utility window title
			default:return new int[]{0,12,0};                           // the system font (Charcoal, Osaka on a Japanese system)
		}
	}
	/**
	 * {font number, size, face} of the control's text: its ControlFontStyleRec ('font' tag: flags, font, size, style...) over
	 * the window's font (useWFont variant) or the theme font {@code theme}. Meta-fonts (-1 big system, -2 small system, -3
	 * small bold, -4 views) and theme font IDs ignore the other fields unless kControlAddToMetaFontMask says otherwise.
	 */
	private int[] fontSpec(Def c,int window,int theme) throws M68kCpuException {
		int[] f=c.windowFont()&&window!=0?new int[]{(short)memory.readWord(window+68),Math.max(9,(short)memory.readWord(window+74)),memory.readByte(window+70)}:themeSpec(theme);
		byte[] r=c.tag(0,T_FONT);if(r==null||r.length<8)return f;
		int flags=s16(r,0),font=s16(r,2),size=s16(r,4),face=s16(r,6);boolean meta=false;
		if((flags&0x80)!=0){f=themeSpec(font);meta=true;}                 // kControlUseThemeFontIDMask
		else if((flags&1)!=0){if(font<0){f=themeSpec(font==-1?0:font==-2?1:font==-3?2:3);meta=true;}else f[0]=font;} // kControlUseFontMask
		if(meta&&(flags&0x200)==0)return f;                               // kControlAddToMetaFontMask
		if((flags&0x100)!=0)f[1]+=size;else if((flags&4)!=0&&size>0)f[1]=size; // kControlAddFontSizeMask, kControlUseSizeMask
		if((flags&2)!=0)f[2]=face&0x7f;                                    // kControlUseFaceMask
		return f;
	}
	private static Font font(int[] spec){return Fonts68k.font(spec[0],spec[1],spec[2]);}
	/** The control's font; a host font of the same family when its title is shown translated (a strike lacks the characters). */
	private Font font(Def c,int window,int theme) throws M68kCpuException {
		int[] spec=fontSpec(c,window,theme);
		return title(c).equals(ownTitle(c))?font(spec):Fonts68k.hostFont(spec[0],spec[1],spec[2]);
	}

	/** Lines of {@code text} broken at returns and wrapped at word boundaries or after two-byte characters (at characters when a word is wider) to {@code width}. */
	static List<String> wrap(String text,Font f,int width){
		List<String> out=new ArrayList<String>();
		for(String para:text.split("\r\n|\r|\n",-1)){
			if(width<=0||Fonts68k.width(f,para)<=width){out.add(para);continue;}
			int start=0;
			while(start<para.length()){
				int end=start,brk=-1;
				while(end<para.length()&&Fonts68k.width(f,para.substring(start,end+1))<=width){end++;if(end<para.length()&&(para.charAt(end)==' '||twoByte(para.charAt(end-1))))brk=end;}
				if(end>=para.length()){out.add(para.substring(start));break;}
				if(end==start)end=start+1; // one character wider than the box
				int cut=brk>start?brk:end;out.add(para.substring(start,cut));start=cut;
				while(start<para.length()&&para.charAt(start)==' ')start++;
			}
		}
		return out;
	}
	/** A character that is two bytes in Shift-JIS (kanji, kana, full-width forms): a line may break after it, as TextEdit breaks
	 *  Japanese text (DragThing's registration text broke only at its spaces, after "US$25" and "Kagi"). Half-width katakana is one byte. */
	private static boolean twoByte(char c){return c>=0x2000&&!(c>=0xff61&&c<=0xff9f);}
	/** TruncString: the text cut to {@code width} with an ellipsis at the end or (truncMiddle) in the middle. */
	static String truncate(String s,Font f,int width,boolean middle){
		if(Fonts68k.width(f,s)<=width)return s;String e="…";
		if(middle){for(int keep=s.length()-1;keep>0;keep--){String t=s.substring(0,(keep+1)/2)+e+s.substring(s.length()-keep/2);if(Fonts68k.width(f,t)<=width)return t;}return e;}
		for(int n=s.length()-1;n>0;n--){String t=s.substring(0,n)+e;if(Fonts68k.width(f,t)<=width)return t;}
		return e;
	}

	/** {width, height} a ControlButtonContentInfo's graphic takes: a picture its frame, a 'cicn' its own bounds, another icon 32
	 *  pixels (16 in less room); null for none. Iconographer's tool buttons hold 26x22 'cicn's: squeezed to 16x16, the one-pixel
	 *  frame of the rectangle tool's icon vanished and the oval's lost its top and bottom. */
	private int[] graphicSize(byte[] ci,int[] room) throws M68kException {
		if(ci==null||ci.length<2)return null;int type=s16(ci,0);
		if(type==3||type==131){int pic=type==3?call(4,GET_PICTURE,s16(ci,2),2):be32(ci,2);int p=pic==0?0:memory.readLong(pic);if(p==0)return null;
			return new int[]{(short)memory.readWord(p+8)-(short)memory.readWord(p+4),(short)memory.readWord(p+6)-(short)memory.readWord(p+2)};}
		if(type==2||type==130){int h=type==2?call(4,GET_CICON,s16(ci,2),2):be32(ci,2),p=h==0?0:memory.readLong(h); // CIcon: iconPMap.bounds at 6
			int w=p==0?0:(short)memory.readWord(p+12)-(short)memory.readWord(p+8),ht=p==0?0:(short)memory.readWord(p+10)-(short)memory.readWord(p+6);
			if(type==2&&h!=0)call(0,DISPOSE_CICON,h,4);
			if(w>0&&ht>0)return new int[]{w,ht};}
		if(!(type>=1&&type<=4||type>=129&&type<=133))return null;
		int s=Math.min(room[3]-room[1],room[2]-room[0])>=32?32:16;return new int[]{s,s};
	}
	/** Draws a ControlButtonContentInfo's graphic (resources 1-4; icon suite, cicn, PICT Handles, IconRef, ICON 129-133) in {@code r}. */
	private void plotContent(int window,byte[] ci,int[] r,int align,int transform) throws M68kException {
		if(ci==null||ci.length<4)return;int type=s16(ci,0),res=s16(ci,2),ref=be32(ci,2);
		if(type==0)return;
		int rect=memory.allocate(8,2);
		try{
			writeRect(rect,r);
			inPort(window,()->{
				switch(type){
					case 1:call(2,PLOT_ICON_ID,rect,4,align,2,transform,2,res,2);break;          // kControlContentIconSuiteRes
					case 2:{int h=call(4,GET_CICON,res,2);if(h!=0){call(0,PLOT_CICON,rect,4,h,4);call(0,DISPOSE_CICON,h,4);}else plotIcon(rect,res);break;} // cicn, else ICON
					case 3:{int p=call(4,GET_PICTURE,res,2);if(p!=0)call(0,DRAW_PICTURE,p,4,rect,4);break;}
					case 4:plotIcon(rect,res);break;
					case 129:call(2,PLOT_ICON_SUITE,rect,4,align,2,transform,2,ref,4);break;
					case 130:call(0,PLOT_CICON,rect,4,ref,4);break;
					case 131:call(0,DRAW_PICTURE,ref,4,rect,4);break;
					case 132:call(2,PLOT_ICON_REF,rect,4,align,2,transform,2,0,4,ref,4);break;
					case 133:call(0,PLOT_ICON,rect,4,ref,4);break;
					default:break;
				}});
		}finally{memory.free(rect);}
	}
	private void plotIcon(int rect,int res) throws M68kException {int h=call(4,GET_ICON,res,2);if(h!=0)call(0,PLOT_ICON,rect,4,h,4);}
	/** The icon transform for a state: the program's own ('tran'/'trfm'), selected while pressed, disabled while inactive. */
	private int transform(Def c,int tag,boolean pressed,boolean active){int t=c.word(tag,0);if(pressed)t|=0x4000;if(!active)t|=1;return t;}

	// ------------------------------------------------------------------ drawing

	private void paintControl(Def c) throws M68kException {
		int window=cm.ownerWindow(c.handle);int[] r=bounds(c);if(r[3]<=r[1]||r[2]<=r[0])return;
		boolean on=active(c);int hilite=hilite(c),value=value(c);
		switch(c.kind){
			case STATIC_TEXT:drawStaticText(c,window,r,on);break;
			case EDIT_TEXT:case EDIT_UNICODE:drawEditText(c,window,r,on);break;
			case GROUP_BOX:drawGroupBox(c,window,r,on);break;
			case USER_PANE:{int proc=c.longv(T_PANE_DRAW,0);if(proc!=0)inPort(window,()->callProc(proc,0,c.handle,4,0,2));break;}
			case SEPARATOR:paint(window,r,g->{boolean across=r[3]-r[1]>=r[2]-r[0];
				if(across){int y=r[0]+Math.max(0,(r[2]-r[0]-2)/2);Look.line(g,r[1],y,r[3]-1,y,on?SHADOW:PALE);if(y+1<r[2])Look.line(g,r[1],y+1,r[3]-1,y+1,WHITE);}
				else{int x=r[1]+Math.max(0,(r[3]-r[1]-2)/2);Look.line(g,x,r[0],x,r[2]-1,on?SHADOW:PALE);if(x+1<r[3])Look.line(g,x+1,r[0],x+1,r[2]-1,WHITE);}});break;
			case PLACARD:paint(window,r,g->{KaleidoscopeScheme68k k=KaleidoscopeScheme68k.active(tb);
				if(k!=null&&k.drawFrameElement(g,KaleidoscopeScheme68k.PLACARD+(!on?0:hilite!=0?2:1),r[0],r[1],r[2],r[3])!=null)return;
				fill(g,r,hilite!=0&&on?GREY:on?FACE:LIGHT);frame(g,r,on?SHADOW:PALE);if(on&&hilite==0)Look.bevel(g,r[0]+1,r[1]+1,r[2]-1,r[3]-1,WHITE,PALE);});break;
			case WINDOW_HEADER:paint(window,r,g->{KaleidoscopeScheme68k k=KaleidoscopeScheme68k.active(tb);
				if(k!=null&&k.drawFrameElement(g,KaleidoscopeScheme68k.HEADER+(on?1:0),r[0],r[1],r[2],r[3])!=null)return;
				fill(g,r,LIGHT);Look.line(g,r[1],r[0],r[3]-1,r[0],WHITE);
				if(c.variant==0){Look.line(g,r[1],r[2]-2,r[3]-1,r[2]-2,on?SHADOW:PALE);Look.line(g,r[1],r[2]-1,r[3]-1,r[2]-1,on?BLACK:SHADOW);} // the list-view variant has no bottom line
				else Look.line(g,r[1],r[2]-1,r[3]-1,r[2]-1,on?SHADOW:PALE);});break;
			case BEVEL:drawBevel(c,window,r,on,hilite,value);break;
			case BUTTONS:drawButton(c,window,r,on,hilite,value);break;
			case IMAGE_WELL:{byte[] ci=c.tag(0,T_CONTENT);
				paint(window,r,g->{fill(g,r,WHITE);frame(g,r,hilite!=0&&on?BLACK:on?SHADOW:PALE);Look.bevel(g,r[0]+1,r[1]+1,r[2]-1,r[3]-1,on?PALE:LIGHT,LIGHT);});
				int[] room={r[0]+2,r[1]+2,r[2]-2,r[3]-2};int[] s=graphicSize(ci,room);
				if(s!=null){int x=(room[1]+room[3]-s[0])/2,y=(room[0]+room[2]-s[1])/2;plotContent(window,ci,new int[]{y,x,y+s[1],x+s[0]},0,transform(c,T_TRANSFORM,false,on));}
				break;}
			case TABS:drawTabs(c,window,r,on,hilite,value);break;
			case POPUP_ARROW:paint(window,r,g->{fill(g,r,background(window));int[] o={2,0,3,1}; // east, west, north, south
				Look.arrow(g,(r[1]+r[3])/2,(r[0]+r[2])/2,(c.variant&4)!=0?3:5,o[c.variant&3],on?BLACK:SHADOW);});break;
			case LITTLE_ARROWS:paint(window,r,g->{fill(g,r,background(window));KaleidoscopeScheme68k k=KaleidoscopeScheme68k.active(tb);
				if(k!=null&&k.drawCentred(g,KaleidoscopeScheme68k.LITTLE_ARROWS+(!on?0:hilite==PART_UP?2:hilite==PART_DOWN?3:1),r[0],r[1],r[2],r[3]))return;
				Look.button(g,r,7,hilite==PART_UP&&on?2:hilite==PART_DOWN&&on?3:on?1:0,0,0);});break;
			case CHASING_ARROWS:paint(window,r,g->{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);fill(g,r,background(window));Look.chasingArrows(g,r,c.phase,on?1:0);});break;
			case TRIANGLE:drawTriangle(c,window,r,on,hilite,value);break;
			case ICON:{int type=c.tag(0,T_CONTENT)!=null?-1:(c.variant&6)==2?1:2; // icon suite (322/323), else cicn or ICON (320/321); IconRefs through 'cont'
				byte[] ci=type<0?c.tag(0,T_CONTENT):new byte[]{0,(byte)type,(byte)(c.word(T_ICON_RES,value)>>8),(byte)c.word(T_ICON_RES,value),0,0};
				plotContent(window,ci,r,c.word(T_ICON_ALIGN,0),transform(c,T_ICON_TRANSFORM,hilite==PART_ICON,on));break;}
			case PICTURE:{int pic=c.longv(T_PICTURE,0);if(pic==0&&value!=0)pic=call(4,GET_PICTURE,value,2);
				if(pic!=0){int rect=memory.allocate(8,2);int p=pic;try{writeRect(rect,r);inPort(window,()->call(0,DRAW_PICTURE,p,4,rect,4));}finally{memory.free(rect);}}
				break;}
			case CLOCK:{String s=clockText(c);c.shown=s;Font f=font(c,window,0);FontMetrics fm=Fonts68k.metrics(f);
				paint(window,r,g->{fill(g,r,WHITE);frame(g,r,on?SHADOW:PALE);Look.bevel(g,r[0]+1,r[1]+1,r[2]-1,r[3]-1,on?PALE:LIGHT,LIGHT);
					g.setFont(f);text(g,f,s,r[1]+4,(r[0]+r[2]-fm.getHeight())/2+fm.getAscent(),on?BLACK:INK_OFF);});break;}
			case SCROLL_TEXT:drawScrollText(c,window,r,on,hilite);break;
			case LIST_BOX:drawListBox(c,window,r,on);break;
			case SLIDER:{Slider k=sliderTrack(c,r);int n=k.max-k.min+1,ticks=(c.variant&2)!=0&&(c.variant&8)==0?(n>=2&&n<=12?n:11):0; // kControlSliderHasTickMarks
				KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb);boolean themed=scheme!=null&&scheme.sliderMetrics(k.horizontal,k.dir)!=null;
				paint(window,r,g->{fill(g,r,background(window));
					if(themed&&scheme.drawSlider(g,r[0],r[1],r[2],r[3],k.horizontal,k.dir,k.thumbStart(k.value),c.ghost==null?-1:k.horizontal?c.ghost[1]:c.ghost[0],on,hilite==PART_INDICATOR))return;
					Look.slider(g,k,on,hilite==PART_INDICATOR,c.ghost,ticks);});break;}
			default:break; // radio groups draw nothing of their own
		}
	}

	private void drawStaticText(Def c,int window,int[] r,boolean on) throws M68kException {
		Font own=font(c,window,0);byte[] style=c.tag(0,T_FONT);int flags=style!=null&&style.length>=24?s16(style,0):0;
		int back=(flags&0x10)!=0?rgb8(style,18):background(window),ink=!on?INK_OFF:(flags&8)!=0?rgb8(style,12):BLACK,just=(flags&0x40)!=0?s16(style,10):0;
		byte[] t=c.tag(0,T_TEXT);String s=tb.decode(t==null?new byte[0]:t);int trunc=c.word(T_TRUNC,-1),width=r[3]-r[1];
		String translation=s.length()<2||tb.translator()==null?null:tb.translated(VMTranslation.Kind.UI,s);
		if(translation!=null){ // a host font, smaller (to 60%) until the lines fit the control
			int[] spec=fontSpec(c,window,0);s=translation;own=Fonts68k.hostFont(spec[0],spec[1],spec[2]);
			for(int sz=spec[1]-1,least=Math.max(7,(int)Math.ceil(spec[1]*0.6));sz>=least&&wrap(s,own,width).size()*Fonts68k.metrics(own).getHeight()>r[2]-r[0];sz--)own=Fonts68k.hostFont(spec[0],sz,spec[2]);}
		Font f=own;FontMetrics fm=Fonts68k.metrics(f);
		List<String> lines;
		if(trunc>=0){lines=new ArrayList<String>();for(String p:s.split("\r\n|\r|\n",-1))lines.add(truncate(p,f,width,(trunc&0x4000)!=0));} // truncEnd / truncMiddle
		else lines=wrap(s,f,width);
		paint(window,r,g->{fill(g,r,back);g.setFont(f);int y=r[0]+fm.getAscent();
			for(String line:lines){if(y-fm.getAscent()>=r[2])break;int w=Fonts68k.width(f,line);
				int x=just==1?r[1]+(width-w)/2:just==-1?r[3]-w:r[1]; // teCenter, teFlushRight, else left
				text(g,f,line,x,y,ink);y+=fm.getHeight();}});
	}

	/** An edit field: its frame (and the focus ring) outside the rectangle, the TextEdit record's text on white inside it. */
	private void drawEditText(Def c,int window,int[] r,boolean on) throws M68kException {
		int t=memory.readLong(c.te);
		if((short)memory.readWord(t)!=r[0]||(short)memory.readWord(t+2)!=r[1]||(short)memory.readWord(t+4)!=r[2]||(short)memory.readWord(t+6)!=r[3])tb.textEdit.setRects(c.te,r); // moved or resized
		memory.writeLong(t+82,window);
		boolean live=c.focused&&on;if((memory.readWord(t+36)!=0)!=live)tb.textEdit.activate(c.te,live);
		paint(window,outset(r,4),g->{
			int[] ring=outset(r,4);fill(g,ring,background(window));
			if(live){g.setColor(new Color(FOCUS));g.fillRect(ring[1],ring[0],ring[3]-ring[1],2);g.fillRect(ring[1],ring[2]-2,ring[3]-ring[1],2);g.fillRect(ring[1],ring[0],2,ring[2]-ring[0]);g.fillRect(ring[3]-2,ring[0],2,ring[2]-ring[0]);}
			int[] o=outset(r,2);Look.bevel(g,o[0],o[1],o[2],o[3],on?SHADOW:PALE,WHITE);frame(g,outset(r,1),on?BLACK:SHADOW);fill(g,r,WHITE);});
		int port=tb.quickDraw.controlPort(window);memory.writeLong(t+82,port); // inPort: DrawControlInCurrentPort draws the text in the current port
		try{onWhite(window,()->tb.textEdit.update(c.te,0,Integer.MAX_VALUE,false));}finally{memory.writeLong(t+82,window);}
	}

	/** Group box title geometry: {top, left, bottom, right} of the title (and its check box or pop-up), empty without one. */
	private int[] groupTitle(Def c,int window,int[] r) throws M68kException {
		boolean primary=(c.variant&4)==0;int sub=c.variant&3;Font f=font(c,window,primary?0:1);FontMetrics fm=Fonts68k.metrics(f);String t=title(c);
		int x=r[1]+(primary?10:8),w;
		switch(sub){
			case 1:w=12+4+Fonts68k.width(f,t)+4;break;
			case 2:w=(t.isEmpty()?0:Fonts68k.width(f,t)+6)+popupWidth(c,f);break;
			default:w=t.isEmpty()?0:Fonts68k.width(f,t)+6;break;
		}
		int h=Math.max(fm.getHeight(),sub==1?14:sub==2?20:0);
		return new int[]{r[0],x,r[0]+h,Math.min(r[3]-4,x+w)};
	}
	private String popupItem(int menu,int item) throws M68kCpuException {MenuManager68k.Menu m=menu==0?null:tb.menus.read(menu);return m!=null&&item>=1&&item<=m.items.size()?m.items.get(item-1).text:"";}
	private int popupWidth(Def c,Font f) throws M68kCpuException {return Math.max(60,Fonts68k.width(f,popupItem(c.longv(T_POPUP_MENU,0),value(c)))+34);}
	/** The pop-up button of a pop-up group box's title. */
	private int[] popupBox(Def c,int window,int[] r) throws M68kException {
		int[] tr=groupTitle(c,window,r);String t=title(c);int x=tr[1]+(t.isEmpty()?0:Fonts68k.width(font(c,window,(c.variant&4)==0?0:1),t)+6);return new int[]{tr[0],x,tr[2],tr[3]};
	}
	private void drawGroupBox(Def c,int window,int[] r,boolean on) throws M68kException {
		boolean primary=(c.variant&4)==0;int sub=c.variant&3,value=value(c),hilite=hilite(c);Font f=font(c,window,primary?0:1);FontMetrics fm=Fonts68k.metrics(f);String t=title(c);
		int[] tr=groupTitle(c,window,r);boolean titled=tr[3]>tr[1];int top=titled?(tr[0]+tr[2])/2:r[0];int[] box=sub==2?popupBox(c,window,r):null;
		paint(window,r,g->{
			int dark=!on?PALE:primary?SHADOW:PALE; // an etched frame: a grey line and a white one inside it
			Look.frame(g,top,r[1],r[2]-1,r[3]-1,dark);Look.frame(g,top+1,r[1]+1,r[2],r[3],WHITE);
			if(!titled)return;
			fill(g,tr,background(window));g.setFont(f);int ink=on?BLACK:INK_OFF,base=(tr[0]+tr[2]-fm.getHeight())/2+fm.getAscent();
			switch(sub){
				case 1:{int y=(tr[0]+tr[2])/2-6;Look.button(g,new int[]{y,tr[1]+2,y+12,tr[1]+14},1,state(on,hilite==PART_CHECK_BOX),value,0);text(g,f,t,tr[1]+18,base,ink);break;}
				case 2:{text(g,f,t,tr[1]+2,base,ink);Look.button(g,box,5,state(on,hilite==PART_MENU),0,0);
					text(g,f,truncate(popupItem(c.longv(T_POPUP_MENU,0),value),f,box[3]-box[1]-30,false),box[1]+8,base,hilite==PART_MENU&&on?WHITE:ink);break;}
				default:text(g,f,t,tr[1]+3,base,ink);break;
			}});
	}

	/** A bevel button: the bevel, its graphic and title placed by the alignment tags, the pop-up glyph of an attached menu. */
	private void drawBevel(Def c,int window,int[] r,boolean on,int hilite,int value) throws M68kException {
		int size=c.variant&3,kind=size==0?8:size==1?3:9;boolean down=on&&(hilite==PART_BUTTON||hilite==PART_MENU);
		int[] content=Look.content(r,kind,value,0);
		if(down&&(c.behaviour&0x8000)!=0)content=new int[]{content[0]+1,content[1]+1,content[2]+1,content[3]+1}; // kControlBehaviorOffsetContents
		boolean menu=c.longv(T_MENU,0)!=0,menuRight=(c.variant&4)!=0;if(menu){if(menuRight)content[3]-=10;else content[2]=Math.max(content[0]+1,content[2]-2);}
		String t=title(c);Font f=font(c,window,0);FontMetrics fm=Fonts68k.metrics(f);
		byte[] ci=c.tag(0,T_CONTENT);int[] gs=graphicSize(ci,content);
		int tw=t.isEmpty()?0:Fonts68k.width(f,t),th=t.isEmpty()?0:fm.getHeight();
		int gali=c.word(T_GRAPHIC_ALIGN,0),tali=c.word(T_TEXT_ALIGN,1),place=c.word(T_TEXT_PLACE,0);
		int[] gr=null;int tx,ty; // the graphic's rectangle, the title's left and top
		int cw=content[3]-content[1],ch=content[2]-content[0];
		if(gs==null){
			tx=tali==1?content[1]+(cw-tw)/2:tali==-1?content[3]-tw-Math.max(0,c.word(T_TEXT_OFFSET,0)):content[1]+Math.max(0,c.word(T_TEXT_OFFSET,0));ty=content[0]+(ch-th)/2;
		}else{
			// kControlBevelButtonAlignSysDirection (-1) is the leading side, the left on our left-to-right systems (QuickHelp's toolbar:
			// icon at the left, title after it - centred, the title went under the icon and out of the 20-pixel button)
			if(gali==-1)gali=1;
			if(t.isEmpty())place=-2;else if(place==-1)place=1;else if(place<=0)place=gali==1||gali==5||gali==6?1:gali==2||gali==7||gali==8?2:gali==4?4:3; // PlaceNormally: beside a graphic at a side, else below it; SysDirection: after it
			int bw=place==1||place==2?gs[0]+4+tw:Math.max(gs[0],tw),bh=place==1||place==2?Math.max(gs[1],th):place<0?gs[1]:gs[1]+2+th;
			boolean left=gali==1||gali==5||gali==6,right=gali==2||gali==7||gali==8,top=gali==3||gali==5||gali==7,bottom=gali==4||gali==6||gali==8;
			byte[] go=c.tag(0,T_GRAPHIC_OFFSET);int dv=go==null||go.length<4?0:s16(go,0),dh=go==null||go.length<4?0:s16(go,2);
			int bx=left?content[1]+dh:right?content[3]-bw-dh:content[1]+(cw-bw)/2,by=top?content[0]+dv:bottom?content[2]-bh-dv:content[0]+(ch-bh)/2;
			switch(place){
				case 1:gr=new int[]{by+(bh-gs[1])/2,bx,by+(bh-gs[1])/2+gs[1],bx+gs[0]};tx=bx+gs[0]+4;ty=by+(bh-th)/2;break;
				case 2:gr=new int[]{by+(bh-gs[1])/2,bx+tw+4,by+(bh-gs[1])/2+gs[1],bx+tw+4+gs[0]};tx=bx;ty=by+(bh-th)/2;break;
				case 4:gr=new int[]{by+th+2,bx+(bw-gs[0])/2,by+th+2+gs[1],bx+(bw-gs[0])/2+gs[0]};tx=bx+(bw-tw)/2;ty=by;break;
				case 3:gr=new int[]{by,bx+(bw-gs[0])/2,by+gs[1],bx+(bw-gs[0])/2+gs[0]};tx=bx+(bw-tw)/2;ty=by+gs[1]+2;break;
				default:gr=new int[]{by,bx,by+gs[1],bx+gs[0]};tx=0;ty=0;break;
			}
		}
		int textX=tx,textY=ty+fm.getAscent();int[] inner=content;
		KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb);
		paint(window,r,g->{
			java.awt.Color ink=scheme==null?null:scheme.drawFrameElement(g,KaleidoscopeScheme68k.bevelId(size,value!=0&&(c.behaviour&0x0300)!=0,!on?0:down?2:1),r[0],r[1],r[2],r[3]); // a Kaleidoscope scheme's bevel button (on: toggle/sticky behaviour with a value)
			if(ink==null)Look.button(g,r,kind,state(on,down),value,0);
			g.setFont(f);java.awt.Shape old=g.getClip();g.clipRect(inner[1],inner[0],inner[3]-inner[1],inner[2]-inner[0]);text(g,f,t,textX,textY,!on?INK_OFF:ink!=null?ink.getRGB()&0xffffff:BLACK);g.setClip(old);
			if(menu){int col=on?BLACK:SHADOW;
				if(menuRight)Look.arrow(g,r[3]-8,(r[0]+r[2])/2,4,2,col);else Look.arrow(g,r[3]-9,r[2]-7,3,1,col);}});
		if(gr!=null)plotContent(window,ci,gr,0,transform(c,T_TRANSFORM,down,on));
	}

	/** The Appearance push button (with an icon for 374/375), check box and radio button (368-372). */
	private void drawButton(Def c,int window,int[] r,boolean on,int hilite,int value) throws M68kException {
		int base=c.variant&7,kind=base==1||base==3?1:base==2||base==4?2:0;
		boolean ring=kind==0&&c.bool(T_DEFAULT,false),clear=!ring&&c.ringDrawn;c.ringDrawn=ring;
		int[] area=ring||clear?outset(r,4):r;String t=title(c);Font f=font(c,window,0);FontMetrics fm=Fonts68k.metrics(f);
		byte[] ci=base>=6?c.tag(0,T_CONTENT):null;int[] content=Look.content(r,kind,value,0);
		int icon=ci!=null&&s16(ci,0)!=0?16:0,tw=Fonts68k.width(f,t),y=(r[0]+r[2]-fm.getHeight())/2+fm.getAscent();
		int block=tw+(icon>0?icon+4:0),bx=kind==0?content[1]+(content[3]-content[1]-block)/2:content[1];
		boolean down=on&&hilite!=0;
		KaleidoscopeScheme68k scheme=icon==0?KaleidoscopeScheme68k.active(tb):null; // a Kaleidoscope scheme's push button, check box, radio button
		if(scheme!=null&&kind!=0&&scheme.checkBoxWidth(kind==2)>0){
			paint(window,r,g->{g.setFont(f);scheme.drawCheckOrRadio(g,r[1],r[0],r[3],r[2],t,value,kind==2,down,!on,fm,new java.awt.Color(background(window)));});
			return;
		}
		if(scheme!=null&&kind==0&&scheme.element(KaleidoscopeScheme68k.PUSH)!=null){
			paint(window,ring||clear?outset(r,5):r,g->{
				if(clear)fill(g,outset(r,5),background(window));
				if(ring)scheme.drawDefaultRing(g,r[1],r[0],r[3],r[2],!on);
				g.setFont(f);scheme.drawPushButton(g,r[1],r[0],r[3],r[2],t,down,!on,fm);});
			return;
		}
		paint(window,area,g->{
			if(clear||kind!=0)fill(g,area,background(window)); // the title's background; a default ring taken away
			Look.button(g,r,kind,state(on,down),value,ring?1:0);
			g.setFont(f);text(g,f,t,kind==0&&icon>0&&base==6?bx+icon+4:bx,y,!on?INK_OFF:kind==0&&down?WHITE:BLACK);});
		if(icon>0){int x=base==6?bx:bx+tw+4,iy=(r[0]+r[2])/2-8;plotContent(window,ci,new int[]{iy,x,iy+16,x+16},0,transform(c,T_TRANSFORM,down,on));}
	}

	/**
	 * The disclosure triangle, pointing right (left for 65/67) when closed and down when open, with its title beside it. Only
	 * the triangle and the title are erased, not the whole bounds: Script Editor 1.8's "このスクリプトについて" label lies inside
	 * its triangle's 79-pixel-wide rectangle and is not drawn again when the window is deactivated.
	 */
	private void drawTriangle(Def c,int window,int[] r,boolean on,int hilite,int value) throws M68kException {
		boolean left=(c.variant&1)!=0;int s=Math.min(12,Math.min(r[3]-r[1],r[2]-r[0])),y=(r[0]+r[2]-s)/2,x=left?r[3]-s:r[1];
		String t=title(c);Font f=font(c,window,0);FontMetrics fm=Fonts68k.metrics(f);
		int tw=t.isEmpty()?0:Fonts68k.width(f,t),tx=left?x-4-tw:x+s+4;
		paint(window,r,g->{int bg=background(window);fill(g,new int[]{y,x,y+s,x+s},bg);if(tw>0)fill(g,new int[]{r[0],Math.max(r[1],tx),r[2],Math.min(r[3],tx+tw)},bg);
			KaleidoscopeScheme68k k=KaleidoscopeScheme68k.active(tb);int tid=(value!=0?KaleidoscopeScheme68k.TRIANGLE_DOWN:left?KaleidoscopeScheme68k.TRIANGLE_LEFT:KaleidoscopeScheme68k.TRIANGLE_RIGHT)+(!on?0:hilite==PART_TRIANGLE?2:1);
			if(k==null||!k.drawCentred(g,tid,y,x,y+s,x+s))
			Look.button(g,new int[]{y,x,y+s,x+s},6,state(on,hilite==PART_TRIANGLE),value!=0?1:left?2:0,0);
			g.setFont(f);int base=(r[0]+r[2]-fm.getHeight())/2+fm.getAscent();text(g,f,t,tx,base,on?BLACK:INK_OFF);});
	}

	// ---- tabs

	private String tabName(Def c,int i){byte[] info=c.tag(i,T_TAB_INFO);if(info==null||info.length<5)return "";int n=Math.min(info[4]&255,info.length-5);return tb.decode(Arrays.copyOfRange(info,5,5+n));}
	private int tabIcon(Def c,int i){byte[] info=c.tag(i,T_TAB_INFO);return info==null||info.length<4?0:s16(info,2);}
	private boolean tabEnabled(Def c,int i){byte[] e=c.tag(i,T_TAB_ENABLED);return e==null||e.length==0||e[0]!=0;}
	/** The pane (index 0) and the tabs 1..max: large tabs 24 pixels deep, small 17, laid along the side the procID faces. */
	private int[][] tabLayout(Def c,int window,int[] r) throws M68kException {
		int n=Math.max(0,max(c));boolean small=(c.variant&1)!=0;int dir=(c.variant>>1)&3,deep=small?17:24;
		Font f=font(c,window,small?1:0);int pad=small?8:12,pos=(dir<2?r[1]:r[0])+(small?6:8);
		int[][] out=new int[n+1][];
		for(int i=1;i<=n;i++){int len=Math.max(small?30:40,Fonts68k.width(f,tabName(c,i))+2*pad+(tabIcon(c,i)!=0?20:0));
			switch(dir){
				case 0:out[i]=new int[]{r[0],pos,r[0]+deep,pos+len};break;
				case 1:out[i]=new int[]{r[2]-deep,pos,r[2],pos+len};break;
				case 2:out[i]=new int[]{pos,r[3]-deep,pos+len,r[3]};break;
				default:out[i]=new int[]{pos,r[1],pos+len,r[1]+deep};break;
			}
			pos+=len;}
		out[0]=dir==0?new int[]{r[0]+deep-1,r[1],r[2],r[3]}:dir==1?new int[]{r[0],r[1],r[2]-deep+1,r[3]}:dir==2?new int[]{r[0],r[1],r[2],r[3]-deep+1}:new int[]{r[0],r[1]+deep-1,r[2],r[3]};
		return out;
	}
	private void drawTabs(Def c,int window,int[] r,boolean on,int hilite,int value) throws M68kException {
		int[][] L=tabLayout(c,window,r);boolean small=(c.variant&1)!=0;int dir=(c.variant>>1)&3;Font f=font(c,window,small?1:0);FontMetrics fm=Fonts68k.metrics(f);
		KaleidoscopeScheme68k scheme=dir==0?KaleidoscopeScheme68k.active(tb):null;boolean themed=scheme!=null&&scheme.hasTabs(small);
		paint(window,r,g->{
			fill(g,r,background(window));
			if(themed)scheme.drawTabPane(g,L[0][0],L[0][1],L[0][2],L[0][3],on,small);else Look.tabPane(g,L[0],on?1:0);
			g.setFont(f);
			for(int pass=0;pass<2;pass++)for(int i=1;i<L.length;i++){ // the selected tab last, over its neighbours
				if((i==value)!=(pass==1))continue;boolean enabled=tabEnabled(c,i);
				int style=i==value?(on?3:4):i==hilite&&on?1:!on||!enabled?2:0;
				java.awt.Color schemeInk=themed?scheme.drawTab(g,L[i][1],L[0][0],L[i][3],style,small):null;
				if(!themed)Look.tab(g,L[i],style,dir);
				int[] label=Look.tabLabel(L[i],dir);String name=tabName(c,i);int w=Fonts68k.width(f,name),icon=tabIcon(c,i)!=0?20:0,ink=schemeInk!=null?schemeInk.getRGB()&0xffffff:on&&enabled?BLACK:INK_OFF;
				if(dir<2)text(g,f,name,(label[1]+label[3]-w+icon)/2,(label[0]+label[2]-fm.getHeight())/2+fm.getAscent(),ink);
				else{Graphics2D rg=(Graphics2D)g.create();try{rg.translate((label[1]+label[3])/2,(label[0]+label[2])/2);rg.rotate(dir==2?Math.PI/2:-Math.PI/2);text(rg,f,name,-w/2,fm.getAscent()-fm.getHeight()/2,ink);}finally{rg.dispose();}}
			}});
		for(int i=1;i<L.length;i++){int id=tabIcon(c,i);if(id==0||dir>=2)continue;
			int[] label=Look.tabLabel(L[i],dir);int w=Fonts68k.width(f,tabName(c,i)),x=(label[1]+label[3]-w-20)/2,y=(label[0]+label[2])/2-8;
			plotContent(window,new byte[]{0,1,(byte)(id>>8),(byte)id,0,0},new int[]{y,x,y+16,x+16},0,on&&tabEnabled(c,i)?0:1);}
		// no embedding hierarchy: the controls made after the tabs inside the pane are its subcontrols, drawn over it again
		List<Integer> all=cm.controlsOf(cm.controls.get(c.handle).window);int at=all.indexOf(c.handle);
		for(int i=at-1;i>=0;i--){int h=all.get(i),d=cm.data(h);if(d==0)continue;
			int[] b={(short)memory.readWord(d+8),(short)memory.readWord(d+10),(short)memory.readWord(d+12),(short)memory.readWord(d+14)};
			if(b[0]>=L[0][0]&&b[1]>=L[0][1]&&b[2]<=L[0][2]&&b[3]<=L[0][3])cm.draw(h);}
	}

	// ---- scrolling text box

	private void loadBoxText(Def c,int id) throws M68kException {
		ResourceFile68k.Resource res=id==0?null:tb.resources.find(0x54455854,id); // 'TEXT'
		c.boxText=res==null?new byte[0]:tb.resources.data(res);c.scrollTop=0;
	}
	/** The text area (left of the scroll bar of 432). */
	private static int[] boxArea(Def c,int[] r){return new int[]{r[0]+1,r[1]+1,r[2]-1,c.variant==0?r[3]-15:r[3]-1};}
	private static int[] boxBar(Def c,int[] r){return c.variant==0?new int[]{r[0],r[3]-16,r[2],r[3]}:null;}
	private List<String> boxLines(Def c,int window,int[] r) throws M68kCpuException {int[] a=boxArea(c,r);return wrap(tb.decode(c.boxText==null?new byte[0]:c.boxText),font(c,window,5),a[3]-a[1]-8);}
	private int boxMax(Def c,int window,int[] r) throws M68kCpuException {int[] a=boxArea(c,r);return Math.max(0,boxLines(c,window,r).size()*Fonts68k.metrics(font(c,window,5)).getHeight()+8-(a[2]-a[0]));}
	private void drawScrollText(Def c,int window,int[] r,boolean on,int hilite) throws M68kException {
		int[] a=boxArea(c,r),bar=boxBar(c,r);Font f=font(c,window,5);FontMetrics fm=Fonts68k.metrics(f);List<String> lines=boxLines(c,window,r);int max=boxMax(c,window,r);
		c.scrollTop=Math.max(0,Math.min(max,c.scrollTop));int top=c.scrollTop;
		paint(window,r,g->{fill(g,r,WHITE);frame(g,r,on?BLACK:SHADOW);
			java.awt.Shape old=g.getClip();g.clipRect(a[1],a[0],a[3]-a[1],a[2]-a[0]);g.setFont(f);
			int y=a[0]+4-top+fm.getAscent();for(String line:lines){if(y-fm.getAscent()>=a[2])break;if(y+fm.getDescent()>=a[0])text(g,f,line,a[1]+4,y,on?BLACK:INK_OFF);y+=fm.getHeight();}
			g.setClip(old);
			if(bar!=null)ControlManager68k.drawScrollBar(g,bar[1],bar[0],bar[3],bar[2],top,0,max,hilite,!on);});
	}
	private int boxPart(Def c,int window,int[] r,int h,int v) throws M68kCpuException {
		int[] b=boxBar(c,r);if(b==null||!inside(b,h,v))return 0;int max=boxMax(c,window,r),length=b[2]-b[0],arrow=Math.min(16,length/2),pos=v-b[0];
		if(pos<arrow)return PART_UP;if(pos>=length-arrow)return PART_DOWN;if(max<=0)return 0;
		int[] t=ControlManager68k.thumbRange(length,arrow,0,max,c.scrollTop);return pos<t[0]?PART_PAGE_UP:pos>=t[1]?PART_PAGE_DOWN:PART_INDICATOR;
	}
	private void scrollBox(Def c,int to) throws M68kException {int window=cm.ownerWindow(c.handle);int max=boxMax(c,window,bounds(c));to=Math.max(0,Math.min(max,to));if(to!=c.scrollTop){c.scrollTop=to;cm.draw(c.handle);}}
	private int trackScrollBox(Def c,int part) throws M68kException {
		int window=cm.ownerWindow(c.handle);int[] r=bounds(c);int line=Fonts68k.metrics(font(c,window,5)).getHeight(),view=boxArea(c,r)[2]-boxArea(c,r)[0];
		if(part==PART_INDICATOR){ // the thumb follows the mouse along the bar
			if(!tb.host().isMouseDown())return part;int[] b=boxBar(c,r);int length=b[2]-b[0],arrow=Math.min(16,length/2),max=boxMax(c,window,r),start=c.scrollTop;
			int[] t=ControlManager68k.thumbRange(length,arrow,0,max,start);int travel=Math.max(1,length-2*arrow-(t[1]-t[0])),v0=tb.events.localMouse()[1];
			while(tb.host().isMouseDown()){scrollBox(c,start+(int)Math.round((double)(tb.events.localMouse()[1]-v0)*max/travel));tb.events.idle(1);if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);}
			return part;}
		int step=part==PART_UP?-line:part==PART_DOWN?line:part==PART_PAGE_UP?-Math.max(line,view-line):Math.max(line,view-line);
		int d=rec(c);memory.writeByte(d+17,part);scrollBox(c,c.scrollTop+step);cm.draw(c.handle);long next=System.nanoTime()+300_000_000L;
		while(tb.host().isMouseDown()){int[] m=tb.events.localMouse();boolean in=boxPart(c,window,r,m[0],m[1])==part;
			if(in&&System.nanoTime()>=next){scrollBox(c,c.scrollTop+step);next=System.nanoTime()+60_000_000L;}
			tb.events.idle(1);if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);}
		memory.writeByte(d+17,0);cm.draw(c.handle);return part;
	}
	/** kControlScrollTextBoxAutoScrollProc: after 'stdl' ticks the text moves 'samt' pixels every 'scdl' ticks; at the end it waits and starts over. */
	private void autoScroll(Def c) throws M68kException {
		if(c.variant!=1||!c.bool(T_ANIMATING,true))return;int now=ticks();if(now-c.nextTick<0)return;
		int max=boxMax(c,cm.ownerWindow(c.handle),bounds(c)),delay=c.longv(T_SCROLL_DELAY,120);
		if(c.scrollTop>=max){if(!c.atEnd){c.atEnd=true;c.nextTick=now+delay;return;}c.atEnd=false;c.scrollTop=0;c.nextTick=now+delay;}
		else{c.scrollTop=Math.min(max,c.scrollTop+Math.max(1,c.word(T_SCROLL_AMOUNT,1)));c.nextTick=now+Math.max(1,c.longv(T_SCROLL_BETWEEN,5));}
		cm.draw(c.handle);
	}

	// ---- list box

	private void createList(Def c,int window,int rows,int cols,int cellHeight,int cellWidth,boolean vertical,boolean horizontal,int ldef,boolean grow) throws M68kException {
		int[] r=bounds(c);int view=memory.allocate(16,2),data=view+8;
		try{writeRect(view,new int[]{r[0]+1,r[1]+1,r[2]-1-(horizontal?15:0),r[3]-1-(vertical?15:0)});writeRect(data,new int[]{0,0,rows,cols});
			int[] list={0};inPort(window,()->list[0]=call(4,LNEW,view,4,data,4,(cellHeight<<16)|(cellWidth&0xffff),4,ldef,2,window,4,1,1,grow?1:0,1,horizontal?1:0,1,vertical?1:0,1));
			c.list=list[0];if(c.list!=0)c.put(0,T_LIST,be(c.list));}
		finally{memory.free(view);}
	}
	private void drawListBox(Def c,int window,int[] r,boolean on) throws M68kException {
		boolean ring=c.focused&&on;
		paint(window,outset(r,3),g->{int[] o=outset(r,3);fill(g,o,background(window));
			if(ring){g.setColor(new Color(FOCUS));g.fillRect(o[1],o[0],o[3]-o[1],2);g.fillRect(o[1],o[2]-2,o[3]-o[1],2);g.fillRect(o[1],o[0],2,o[2]-o[0]);g.fillRect(o[3]-2,o[0],2,o[2]-o[0]);}
			frame(g,r,on?BLACK:SHADOW);if(c.list==0)fill(g,new int[]{r[0]+1,r[1]+1,r[2]-1,r[3]-1},WHITE);});
		if(c.list!=0)inPort(window,()->call(0,LUPDATE,memory.readLong(window+24),4,c.list,4)); // the list through its visRgn
	}

	// ---- slider

	/** The slider's geometry: horizontal when wider than tall; the thumb points down/right, up/left (reverse) or nowhere (non-directional). */
	private Slider sliderTrack(Def c,int[] r) throws M68kCpuException {
		int dir=(c.variant&8)!=0?0:(c.variant&4)!=0?1:2;boolean horizontal=r[3]-r[1]>=r[2]-r[0];
		KaleidoscopeScheme68k scheme=KaleidoscopeScheme68k.active(tb);int[] m=scheme==null?null:scheme.sliderMetrics(horizontal,dir); // a scheme's thumb and track caps
		return m==null?new Slider(r,min(c),max(c),value(c),dir):new Slider(r,min(c),max(c),value(c),dir,m[0],m[1],m[2]);
	}
	private int trackSlider(Def c,int part,int action) throws M68kException {
		if(part!=PART_INDICATOR)return trackPart(c,part,action,true); // the track: the action procedure pages
		if(!tb.host().isMouseDown())return part;
		Slider k=sliderTrack(c,bounds(c));boolean live=(c.variant&1)!=0&&action!=0;
		int[] m0=tb.events.localMouse();int grab=(k.horizontal?m0[0]:m0[1])-k.thumbStart(k.value),value=k.value,d=rec(c);
		memory.writeByte(d+17,PART_INDICATOR);cm.draw(c.handle);
		try{
			while(tb.host().isMouseDown()){
				int[] m=tb.events.localMouse();int nv=k.valueAt((k.horizontal?m[0]:m[1])-grab);
				if(nv!=value){value=nv; // live feedback moves the value and calls the action procedure; else a ghost thumb follows the mouse
					if(live){cm.setValue(c.handle,nv);callAction(action,c,PART_INDICATOR);}else{c.ghost=k.thumb(nv);cm.draw(c.handle);}}
				tb.events.idle(1);if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
			}
		}finally{c.ghost=null;memory.writeByte(d+17,0);}
		if(value(c)!=value)cm.setValue(c.handle,value);else cm.draw(c.handle);
		return part;
	}

	// ---- clock

	private java.time.LocalDateTime clockTime(Def c){return java.time.LocalDateTime.now().plusSeconds(c.clockOffset);}
	private String clockText(Def c){
		java.time.LocalDateTime t=clockTime(c);boolean j=japanese();
		String[] patterns=j?new String[]{"H:mm","H:mm:ss","yyyy.M.d","yyyy.M"}:new String[]{"h:mm a","h:mm:ss a","M/d/yy","M/yyyy"}; // time, time with seconds, date, month and year
		return t.format(java.time.format.DateTimeFormatter.ofPattern(patterns[c.variant&3],java.util.Locale.US));
	}
	/** The clock's LongDateRec: era, year, month, day, hour, minute, second, dayOfWeek, dayOfYear, weekOfYear, pm, reserved. */
	private byte[] longDate(Def c){
		java.time.LocalDateTime t=clockTime(c);int[] f={0,t.getYear(),t.getMonthValue(),t.getDayOfMonth(),t.getHour(),t.getMinute(),t.getSecond(),t.getDayOfWeek().getValue()%7+1,t.getDayOfYear(),
			t.get(java.time.temporal.WeekFields.SUNDAY_START.weekOfYear()),t.getHour()>=12?1:0,0,0,0};
		byte[] b=new byte[28];for(int i=0;i<f.length;i++){b[2*i]=(byte)(f[i]>>8);b[2*i+1]=(byte)f[i];}return b;
	}

	// ------------------------------------------------------------------ hit testing and tracking

	private int hit(Def c,int h,int v) throws M68kException {
		int window=cm.ownerWindow(c.handle);int[] r=bounds(c);
		switch(c.kind){
			case BEVEL:return PART_BUTTON;
			case BUTTONS:{int base=c.variant&7;return base>=1&&base<=4?PART_CHECK_BOX:PART_BUTTON;}
			case TRIANGLE:return PART_TRIANGLE;
			case LITTLE_ARROWS:return v<(r[0]+r[2])/2?PART_UP:PART_DOWN;
			case TABS:{int[][] L=tabLayout(c,window,r);for(int i=1;i<L.length;i++)if(inside(L[i],h,v))return tabEnabled(c,i)?i:0;return 0;}
			case GROUP_BOX:{int sub=c.variant&3;if(sub==0||!inside(groupTitle(c,window,r),h,v))return 0;return sub==1?PART_CHECK_BOX:inside(popupBox(c,window,r),h,v)?PART_MENU:0;}
			case IMAGE_WELL:return PART_IMAGE_WELL;
			case EDIT_TEXT:case EDIT_UNICODE:return PART_EDIT_TEXT;
			case ICON:return PART_ICON;
			case PICTURE:return PART_PICTURE;
			case CLOCK:return (value(c)&1)!=0?0:PART_CLOCK; // kControlClockIsDisplayOnly
			case LIST_BOX:return PART_LIST_BOX;
			case USER_PANE:{int proc=c.longv(T_PANE_HIT,0);return proc==0?0:callProc(proc,2,c.handle,4,(v<<16)|(h&0xffff),4);}
			case SLIDER:return sliderTrack(c,r).hit(h,v);
			case SCROLL_TEXT:return boxPart(c,window,r,h,v);
			default:return 0; // static text, separators, placards, headers, chasing arrows, pop-up arrows, radio groups
		}
	}

	private int trackControl(Def c,int h,int v,int modifiers,int action) throws M68kException {
		int part=hit(c,h,v);if(part==0)return 0;int window=cm.ownerWindow(c.handle);
		int[] result={0};inPort(window,()->result[0]=trackIn(c,window,part,h,v,modifiers,action));return result[0];
	}
	private int trackIn(Def c,int window,int part,int h,int v,int modifiers,int action) throws M68kException {
		switch(c.kind){
			case EDIT_TEXT:case EDIT_UNICODE: // the click focuses the field, then TextEdit tracks the selection
				if(!c.focused)cm.setKeyboardFocus(window,c.handle,PART_EDIT_TEXT);
				onWhite(window,()->tb.textEdit.clickFromDialog(c.te,h,v,(modifiers&0x200)!=0));return PART_EDIT_TEXT;
			case LIST_BOX:{if(!c.focused)cm.setKeyboardFocus(window,c.handle,PART_LIST_BOX);if(c.list==0)return part;
				int dbl=call(1,LCLICK,(v<<16)|(h&0xffff),4,modifiers,2,c.list,4);c.put(0,T_DOUBLE_CLICK,new byte[]{(byte)(dbl!=0?1:0)});return dbl!=0?PART_LIST_DOUBLE_CLICK:PART_LIST_BOX;}
			case USER_PANE:{int proc=c.longv(T_PANE_TRACK,0);return proc!=0?callProc(proc,2,c.handle,4,(v<<16)|(h&0xffff),4,action,4):trackPart(c,part,action,false);}
			case BEVEL:return trackBevel(c,part,action);
			case BUTTONS:{int got=trackPart(c,part,0,false);int base=c.variant&7;
				if(got!=0&&base==3)cm.setValue(c.handle,value(c)==1?0:1);else if(got!=0&&base==4)cm.setValue(c.handle,1); // the auto-toggle variants
				return got;}
			case TRIANGLE:{int got=trackPart(c,part,0,false);
				if(got!=0&&(c.variant&2)!=0){c.put(0,T_LAST_VALUE,word(value(c)));cm.setValue(c.handle,value(c)!=0?0:1);} // kControlTriangleAutoToggleProc
				return got;}
			case TABS:{int got=trackPart(c,part,0,false);if(got!=0&&value(c)!=got)cm.setValue(c.handle,got);return got;}
			case LITTLE_ARROWS:return trackPart(c,part,action,true);
			case GROUP_BOX:return part==PART_MENU?trackGroupPopup(c,window):trackPart(c,part,0,false);
			case ICON:case PICTURE:return (c.variant&1)!=0?part:trackPart(c,part,0,false); // the NoTrack variants answer at once
			case CLOCK:return part;
			case SLIDER:return trackSlider(c,part,action);
			case SCROLL_TEXT:return trackScrollBox(c,part);
			default:return trackPart(c,part,action,false);
		}
	}
	/** Classic tracking: the part stays hilited while the mouse is over it (a repeating part calls the action procedure at once
	 *  and then while held); returns the part when the button comes up over it, else 0. */
	private int trackPart(Def c,int part,int action,boolean repeat) throws M68kException {
		int d=rec(c);boolean inside=true;memory.writeByte(d+17,part);cm.draw(c.handle);
		long next=0;if(repeat&&action!=0){callAction(action,c,part);next=System.nanoTime()+300_000_000L;}
		try{
			while(tb.host().isMouseDown()){
				int[] m=tb.events.localMouse();boolean now=hit(c,m[0],m[1])==part;
				if(now!=inside){inside=now;memory.writeByte(d+17,inside?part:0);cm.draw(c.handle);}
				if(inside&&repeat&&action!=0&&System.nanoTime()>=next){callAction(action,c,part);next=System.nanoTime()+60_000_000L;}
				tb.events.idle(1);if(tb.cpu.abortRequested())throw new M68kAbortException(tb.cpu.pc);
			}
		}finally{if(memory.readByte(d+17)!=255)memory.writeByte(d+17,0);}
		cm.draw(c.handle);return inside?part:0;
	}
	/** A bevel button: its menu pops up (the choice checked and kept in 'mval'); else it tracks as a button and toggles or sticks. */
	private int trackBevel(Def c,int part,int action) throws M68kException {
		int menu=c.longv(T_MENU,0);
		if(menu==0){int got=trackPart(c,part,action,false);int b=c.behaviour&0x0300;
			if(got!=0&&b==0x100)cm.setValue(c.handle,value(c)!=0?0:1);else if(got!=0&&b==0x200&&value(c)==0)cm.setValue(c.handle,1); // kControlBehaviorToggles, Sticky
			return got;}
		int d=rec(c);int[] r=bounds(c);memory.writeByte(d+17,PART_MENU);cm.draw(c.handle);
		int[] at=(c.variant&4)!=0?tb.windows.localToGlobal(r[3],r[0]):tb.windows.localToGlobal(r[1],r[2]); // below the button, or at its right (kControlBevelButtonMenuOnRight)
		int old=c.word(T_MENU_VALUE,0),result;
		try{result=call(4,POPUP_MENU_SELECT,menu,4,at[1],2,at[0],2,1,2);}finally{memory.writeByte(d+17,0);}
		cm.draw(c.handle);int item=result&0xffff;if(item==0)return 0;
		boolean multi=(c.behaviour&0x4000)!=0,command=(c.behaviour&0x2000)!=0;
		if(!command){
			if(!multi){if(old>0&&old!=item)call(0,CHECK_ITEM,menu,4,old,2,0,1);call(0,CHECK_ITEM,menu,4,item,2,1,1);}
			else{MenuManager68k.Menu m=tb.menus.read(menu);boolean marked=m!=null&&item<=m.items.size()&&m.items.get(item-1).mark!=0;call(0,CHECK_ITEM,menu,4,item,2,marked?0:1,1);}}
		c.put(0,T_MENU_VALUE,word(item));c.put(0,T_LAST_MENU,word(result>>>16));
		return PART_MENU;
	}
	private int trackGroupPopup(Def c,int window) throws M68kException {
		int menu=c.longv(T_POPUP_MENU,0);if(menu==0)return 0;int[] box=popupBox(c,window,bounds(c));int d=rec(c);
		memory.writeByte(d+17,PART_MENU);cm.draw(c.handle);int[] at=tb.windows.localToGlobal(box[1],box[0]);int result;
		try{result=call(4,POPUP_MENU_SELECT,menu,4,at[1],2,at[0],2,Math.max(1,value(c)),2);}finally{memory.writeByte(d+17,0);}
		int item=result&0xffff;if(item==0){cm.draw(c.handle);return 0;}
		if(max(c)<item)memory.writeWord(d+22,item);cm.setValue(c.handle,item);return PART_MENU;
	}

	// ------------------------------------------------------------------ keys, focus, idle time, activation

	/** The key filter ('fltr', a ControlKeyFilterUPP given the key's code, character and modifiers by address): false when it blocks the key. */
	private boolean filter(Def c,int[] k) throws M68kException {
		int proc=c.longv(T_FILTER,0);if(proc==0)return true;
		int p=memory.allocate(6,2);
		try{memory.writeWord(p,k[0]);memory.writeWord(p+2,k[1]);memory.writeWord(p+4,k[2]);
			int r=callProc(proc,2,c.handle,4,p,4,p+2,4,p+4,4);k[0]=memory.readWord(p);k[1]=memory.readWord(p+2);k[2]=memory.readWord(p+4);return r!=0;} // kControlKeyFilterPassKey
		finally{memory.free(p);}
	}
	private int keyDown(Def c,int keyCode,int charCode,int modifiers) throws M68kException {
		int window=cm.ownerWindow(c.handle);
		switch(c.kind){
			case EDIT_TEXT:case EDIT_UNICODE:{
				int[] k={keyCode,charCode&0xffff,modifiers&0xffff};if(!filter(c,k))return 0;int ch=k[1]&0xff;
				if((k[2]&0x100)!=0||ch==9)return 0; // Command keys and Tab are the program's
				if(c.bool(T_LOCKED,false)&&(ch<0x1c||ch>0x1f))return 0; // a locked field only moves the insertion point
				int key=ch;
				if(c.secret!=null){ // a password field: the clear text is edited alongside the bullets it shows
					int t=memory.readLong(c.te),n=c.secret.length,s=Math.min(memory.readWord(t+32),n),e=Math.max(s,Math.min(memory.readWord(t+34),n));
					if(ch==8){if(e>s)c.secret=splice(c.secret,s,e,new byte[0]);else if(s>0)c.secret=splice(c.secret,s-1,s,new byte[0]);}
					else if(ch==0x7f){if(e>s)c.secret=splice(c.secret,s,e,new byte[0]);else if(s<n)c.secret=splice(c.secret,s,s+1,new byte[0]);}
					else if(ch>=0x20){c.secret=splice(c.secret,s,e,new byte[]{(byte)ch});key=BULLET&0xff;}
					else if(ch<0x1c||ch>0x1f)return 0;}
				int typed=key;onWhite(window,()->tb.textEdit.keyFromDialog(c.te,typed));
				return PART_EDIT_TEXT;}
			case LIST_BOX:{
				int[] k={keyCode,charCode&0xffff,modifiers&0xffff};if(!filter(c,k)||c.list==0)return 0;int ch=k[1]&0xff;
				int row=tb.listManager.selectedRow(c.list),n=tb.listManager.rowCount(c.list);if(n<=0||(ch!=0x1e&&ch!=0x1f))return 0;
				int to=ch==0x1e?Math.max(0,row<0?0:row-1):Math.min(n-1,row+1);inPort(window,()->tb.listManager.selectRow(c.list,to));
				return PART_LIST_BOX;}
			case USER_PANE:{int proc=c.longv(T_PANE_KEY,0);return proc==0?0:callProc(proc,2,c.handle,4,keyCode,2,charCode,2,modifiers,2);}
			default:return 0;
		}
	}
	private int focusControl(Def c,int part) throws M68kException {
		switch(c.kind){
			case EDIT_TEXT:case EDIT_UNICODE:case LIST_BOX:{
				int mine=c.kind==LIST_BOX?PART_LIST_BOX:PART_EDIT_TEXT;boolean want=part>0||part<0&&!c.focused; // one part: "next" from it moves the focus on
				if(want!=c.focused){c.focused=want;
					if(c.te!=0&&!want&&memory.readWord(memory.readLong(c.te)+36)!=0)tb.textEdit.activate(c.te,false);
					cm.draw(c.handle);}
				return want?mine:0;}
			case USER_PANE:{int proc=c.longv(T_PANE_FOCUS,0);if(proc==0)return 0;int got=callProc(proc,2,c.handle,4,part,2);c.focused=got!=0;return got;}
			default:return 0;
		}
	}
	private void idleControl(Def c) throws M68kException {
		switch(c.kind){
			case EDIT_TEXT:case EDIT_UNICODE:if(c.focused&&c.te!=0&&memory.readWord(memory.readLong(c.te)+36)!=0)call(0,TE_IDLE,c.te,4);break; // the caret blinks
			case CHASING_ARROWS:{int now=ticks();if(c.bool(T_ANIMATING,true)&&now-c.nextTick>=0){c.phase=(c.phase+1)&7;c.nextTick=now+5;cm.draw(c.handle);}break;}
			case CLOCK:if((value(c)&2)!=0&&!clockText(c).equals(c.shown))cm.draw(c.handle);break; // kControlClockIsLive
			case SCROLL_TEXT:autoScroll(c);break;
			case USER_PANE:{int proc=c.longv(T_PANE_IDLE,0);if(proc!=0&&(c.features&F_IDLE)!=0)inPort(cm.ownerWindow(c.handle),()->callProc(proc,0,c.handle,4));break;}
			default:break;
		}
	}
	private void activateControl(Def c,boolean on) throws M68kException {
		switch(c.kind){
			case EDIT_TEXT:case EDIT_UNICODE:if(c.te!=0&&c.focused)tb.textEdit.activate(c.te,on);break;
			case LIST_BOX:if(c.list!=0)inPort(cm.ownerWindow(c.handle),()->call(0,LACTIVATE,on?1:0,1,c.list,4));break;
			case USER_PANE:{int proc=c.longv(T_PANE_ACTIVATE,0);if(proc!=0)inPort(cm.ownerWindow(c.handle),()->callProc(proc,0,c.handle,4,on?1:0,1));break;}
			default:break;
		}
	}
	private int featuresOf(Def c){
		switch(c.kind){
			case EDIT_TEXT:case EDIT_UNICODE:return F_FOCUS|F_IDLE|F_ACTIVATE|F_TRACKING|F_DATA|F_FOCUS_ON_CLICK;
			case LIST_BOX:return F_FOCUS|F_ACTIVATE|F_TRACKING|F_DATA|F_FOCUS_ON_CLICK;
			case USER_PANE:return c.features|F_DATA;
			case CHASING_ARROWS:case CLOCK:case SCROLL_TEXT:return F_IDLE|F_DATA;
			case TABS:case GROUP_BOX:case PLACARD:case WINDOW_HEADER:return F_EMBEDDING|F_DATA;
			case RADIO_GROUP:return F_EMBEDDING|F_DATA|F_RADIO;
			case SLIDER:return F_GHOSTING|F_LIVE|F_DATA;
			case STATIC_TEXT:case BUTTONS:return F_DATA|F_BEST_RECT;
			default:return F_DATA;
		}
	}
	/** GetBestControlRect for static text (its lines unwrapped) and the Appearance buttons; null for the others. */
	private int[] best(Def c) throws M68kException {
		int window=cm.ownerWindow(c.handle);int[] r=bounds(c);
		switch(c.kind){
			case STATIC_TEXT:{Font f=font(c,window,0);FontMetrics fm=Fonts68k.metrics(f);byte[] t=c.tag(0,T_TEXT);String[] lines=tb.decode(t==null?new byte[0]:t).split("\r\n|\r|\n",-1);
				int w=0;for(String l:lines)w=Math.max(w,Fonts68k.width(f,l));return new int[]{r[0],r[1],r[0]+lines.length*fm.getHeight(),r[1]+w,-fm.getDescent()};}
			case BUTTONS:{Font f=font(c,window,0);FontMetrics fm=Fonts68k.metrics(f);int w=Fonts68k.width(f,title(c)),base=c.variant&7;
				if(base>=1&&base<=4)return new int[]{r[0],r[1],r[0]+Math.max(16,fm.getHeight()),r[1]+12+5+w+2,-Math.max(2,(16-fm.getHeight())/2+fm.getDescent())};
				return new int[]{r[0],r[1],r[0]+20,r[1]+Math.max(70,w+24+(base>=6?20:0)),-5};}
			default:return null;
		}
	}
	private void disposeControl(Def c) throws M68kException {
		if(c.te!=0){int t=c.te;c.te=0;tb.textEdit.dispose(t);}
		if(c.list!=0&&c.kind==LIST_BOX){int l=c.list;c.list=0;call(0,LDISPOSE,l,4);} // the list box's list goes with it
	}

	// ------------------------------------------------------------------ data tags

	private void newTextEdit(Def c,int window) throws M68kException {
		int[] r=bounds(c);int rect=memory.allocate(8,2);
		try{writeRect(rect,r);int[] h={0};inPort(window,()->h[0]=tb.textEdit.create(rect,rect,false));c.te=h[0];}finally{memory.free(rect);}
		memory.writeLong(memory.readLong(c.te)+82,window); // inPort
		applyTextFont(c,window);
		if((c.variant&2)!=0)c.secret=new byte[0]; // kControlEditTextPasswordProc / kControlEditUnicodeTextPasswordProc
	}
	/** The control's font into its TERec (txFont, txFace, txSize, lineHeight, fontAscent), lines laid out again. */
	private void applyTextFont(Def c,int window) throws M68kException {
		int[] f=fontSpec(c,window,0);int t=memory.readLong(c.te);
		memory.writeWord(t+74,f[0]);memory.writeByte(t+76,f[2]);memory.writeByte(t+77,0);memory.writeWord(t+80,f[1]);
		int[] fi=Fonts68k.fontInfo(font(f));memory.writeWord(t+24,fi[0]+fi[1]+fi[3]);memory.writeWord(t+26,fi[0]);tb.textEdit.recalc(c.te);
	}
	private void setEditText(Def c,byte[] v) throws M68kException {
		if(c.secret!=null){c.secret=v.clone();v=new byte[v.length];Arrays.fill(v,BULLET);}
		tb.textEdit.setText(c.te,v.length>32767?Arrays.copyOf(v,32767):v);
	}

	/** The part a data tag is kept under: the Appearance CDEFs other than tabs ignore the part code (PowerPlant's
	 *  LBevelButton sets its content for kControlButtonPart, 10; 3D Sokoban's toolbar icons were never drawn). */
	private static int tagPart(Def c,int part){return c.kind==TABS?part:0;}

	private int set(Def c,int part,int tag,byte[] v) throws M68kException {
		part=tagPart(c,part);
		int window=cm.ownerWindow(c.handle);
		switch(c.kind){
			case STATIC_TEXT:
				if(tag==T_CFSTRING){String s=v.length<4?null:cfText(be32(v,0));if(s==null)return PARAM_ERR;c.put(0,T_TEXT,tb.encoding.encodeReplacing(s));return NO_ERR;}
				break;
			case EDIT_TEXT:case EDIT_UNICODE:
				if(tag==T_TEXT||tag==T_PASSWORD&&c.secret!=null){setEditText(c,v);return NO_ERR;}
				if(tag==T_CFSTRING){String s=v.length<4?null:cfText(be32(v,0));if(s==null)return PARAM_ERR;setEditText(c,tb.encoding.encodeReplacing(s));return NO_ERR;}
				if(tag==T_SELECTION){if(v.length<4)return PARAM_ERR;int n=memory.readWord(memory.readLong(c.te)+60),s=Math.max(0,Math.min(n,s16(v,0))),e=Math.max(s,Math.min(n,s16(v,2)));
					tb.textEdit.setSelection(c.te,s,e,c.focused);return NO_ERR;}
				if(tag==T_TE)return ERR_DATA_NOT_SUPPORTED;
				if(tag==T_FONT){c.put(0,T_FONT,v);applyTextFont(c,window);return NO_ERR;}
				break;
			case LIST_BOX:if(tag==T_LIST&&v.length>=4)c.list=be32(v,0);break;
			case TABS:
				if(tag==T_TAB_INFO||tag==T_TAB_ENABLED){if(part<1||part>max(c))return ERR_INVALID_PART_CODE;}
				if(tag==T_TAB_INFO&&v.length>=8&&s16(v,0)==1){ // ControlTabInfoRecV1: a CFString name, kept as the version 0 record's Str255
					String s=cfText(be32(v,4));byte[] name=tb.encoding.encodeReplacing(s==null?"":s);int n=Math.min(255,name.length);byte[] info=new byte[260];info[2]=v[2];info[3]=v[3];info[4]=(byte)n;System.arraycopy(name,0,info,5,n);v=info;}
				break;
			case SCROLL_TEXT:if(tag==T_SCROLL_RES&&v.length>=2)loadBoxText(c,s16(v,0));break;
			case BEVEL:if(tag==T_OWNED_MENU&&v.length>=4)c.put(0,T_MENU,v);break;
			case GROUP_BOX:
				if(tag==T_POPUP_MENU&&v.length>=4){MenuManager68k.Menu m=tb.menus.read(be32(v,0));int n=m==null?0:m.items.size();int d=rec(c);
					memory.writeWord(d+20,1);memory.writeWord(d+22,Math.max(1,n));memory.writeWord(d+18,ControlManager68k.clamp(value(c),1,Math.max(1,n)));}
				break;
			case CLOCK:
				if(tag==T_CLOCK_DATE&&v.length>=14){try{java.time.LocalDateTime t=java.time.LocalDateTime.of(s16(v,2),s16(v,4),s16(v,6),s16(v,8),s16(v,10),s16(v,12));
					c.clockOffset=java.time.Duration.between(java.time.LocalDateTime.now(),t).getSeconds();}catch(java.time.DateTimeException e){return PARAM_ERR;}return NO_ERR;}
				break;
			default:break;
		}
		c.put(part,tag,v);return NO_ERR; // every other tag is kept and handed back, as the Appearance CDEFs keep theirs
	}

	private byte[] get(Def c,int part,int tag) throws M68kException {
		part=tagPart(c,part);
		int window=cm.ownerWindow(c.handle);
		switch(c.kind){
			case STATIC_TEXT:
				if(tag==T_TEXT){byte[] t=c.tag(0,T_TEXT);return t==null?new byte[0]:t.clone();}
				if(tag==T_CFSTRING)return cfString(c.tag(0,T_TEXT));
				if(tag==T_TEXT_HEIGHT){int[] r=bounds(c);Font f=font(c,window,0);byte[] t=c.tag(0,T_TEXT);return word(wrap(tb.decode(t==null?new byte[0]:t),f,r[3]-r[1]).size()*Fonts68k.metrics(f).getHeight());}
				break;
			case EDIT_TEXT:case EDIT_UNICODE:
				if(tag==T_TEXT)return tb.textEdit.text(c.te);
				if(tag==T_PASSWORD&&c.secret!=null)return c.secret.clone();
				if(tag==T_CFSTRING)return cfString(c.secret!=null?c.secret:tb.textEdit.text(c.te));
				if(tag==T_SELECTION){int t=memory.readLong(c.te);return new byte[]{(byte)memory.readByte(t+32),(byte)memory.readByte(t+33),(byte)memory.readByte(t+34),(byte)memory.readByte(t+35)};}
				if(tag==T_TE)return be(c.te);
				break;
			case TABS:
				if(tag==T_TAB_RECT){int[] p=tabLayout(c,window,bounds(c))[0];return rectBytes(new int[]{p[0]+1,p[1]+1,p[2]-1,p[3]-1});}
				if(tag==T_TAB_ENABLED&&part>=1&&part<=max(c)&&c.tag(part,tag)==null)return new byte[]{1};
				break;
			case GROUP_BOX:if(tag==T_TITLE_RECT)return rectBytes(groupTitle(c,window,bounds(c)));break;
			case LIST_BOX:if(tag==T_LIST)return be(c.list);break;
			case CLOCK:if(tag==T_CLOCK_DATE)return longDate(c);break;
			case TRIANGLE:if(tag==T_LAST_VALUE&&c.tag(0,tag)==null)return new byte[2];break;
			case CHASING_ARROWS:case SCROLL_TEXT:if(tag==T_ANIMATING&&c.tag(0,tag)==null)return new byte[]{1};break;
			default:break;
		}
		byte[] v=c.tag(part,tag);return v==null?null:v.clone();
	}

	// ------------------------------------------------------------------ the Platinum look

	/** A slider's geometry: the thumb (11 pixels along the track, up to 16 across) travels its length. */
	static final class Slider {
		static final int THUMB=11;
		final int[] r;final int min,max,value,dir;final boolean horizontal;
		/** The thumb's length along the track and thickness, and the inset of its travel at each end (a Kaleidoscope scheme's track caps). */
		final int thumbLength,thumbThickness,inset;
		/** {@code dir}: 0 a plain thumb, 1 pointing up/left, 2 down/right. */
		Slider(int[] r,int min,int max,int value,int dir){this(r,min,max,value,dir,THUMB,16,0);}
		Slider(int[] r,int min,int max,int value,int dir,int thumbLength,int thumbThickness,int inset){this.r=r;this.min=min;this.max=max;this.value=value;this.dir=dir;horizontal=r[3]-r[1]>=r[2]-r[0];this.thumbLength=thumbLength;this.thumbThickness=thumbThickness;this.inset=inset;}
		int start(){return (horizontal?r[1]:r[0])+inset;}
		int travel(){return Math.max(0,(horizontal?r[3]-r[1]:r[2]-r[0])-2*inset-thumbLength);}
		// a vertical slider has its minimum at the bottom (wxWidgets inverts its Carbon sliders for that): Audion's equalizer
		// showed its Rock preset's bass and treble boost as a cut
		int thumbStart(int v){int along=max<=min?0:(int)((long)(Math.max(min,Math.min(max,v))-min)*travel()/((long)max-min));return start()+(horizontal?along:travel()-along);}
		int valueAt(int thumbStart){int t=travel();if(t<=0||max<=min)return min;int along=Math.max(0,Math.min(t,thumbStart-start()));if(!horizontal)along=t-along;return min+(int)Math.round((double)along*((long)max-min)/t);}
		/** The thumb at a value; a pointing thumb sits on the side away from its tick marks. */
		int[] thumb(int v){int s=thumbStart(v),a=horizontal?r[0]:r[1],span=horizontal?r[2]-r[0]:r[3]-r[1],t=Math.min(span,thumbThickness),o=inset>0?0:dir==2?0:dir==1?span-t:(span-t)/2;
			return horizontal?new int[]{a+o,s,a+o+t,s+thumbLength}:new int[]{s,a+o,s+thumbLength,a+o+t};}
		/** The indicator (129), or the page part before (22) or after (23) it. */
		int hit(int h,int v){if(max<=min)return 0;int[] th=thumb(value);if(inside(th,h,v))return PART_INDICATOR;return (horizontal?h<th[1]:v<th[0])?PART_PAGE_UP:PART_PAGE_DOWN;}
	}

	/** Platinum drawing primitives in Java2D; rectangles are window-local {top, left, bottom, right}. */
	static final class Look {
		private Look(){}
		static Color c(int rgb){return new Color(rgb);}
		static void fill(Graphics2D g,int t,int l,int b,int r,int rgb){if(r>l&&b>t){g.setColor(c(rgb));g.fillRect(l,t,r-l,b-t);}}
		static void frame(Graphics2D g,int t,int l,int b,int r,int rgb){if(r>l&&b>t){g.setColor(c(rgb));g.drawRect(l,t,r-l-1,b-t-1);}}
		static void line(Graphics2D g,int x1,int y1,int x2,int y2,int rgb){g.setColor(c(rgb));g.drawLine(x1,y1,x2,y2);}
		/** One pixel just inside the rectangle: top and left {@code light}, bottom and right {@code dark}. */
		static void bevel(Graphics2D g,int t,int l,int b,int r,int light,int dark){if(r-l<2||b-t<2)return;line(g,l,t,r-2,t,light);line(g,l,t,l,b-2,light);line(g,l+1,b-1,r-1,b-1,dark);line(g,r-1,t+1,r-1,b-1,dark);}
		/** A filled triangle pointing left (0), down (1), right (2) or up (3), {@code h} rows from base to tip, centred on (cx, cy). */
		static void arrow(Graphics2D g,int cx,int cy,int h,int orientation,int rgb){
			g.setColor(c(rgb));int o=(h-1)/2;
			for(int i=0;i<h;i++){int half=h-1-i;
				switch(orientation&3){
					case 1:g.drawLine(cx-half,cy-o+i,cx+half,cy-o+i);break;
					case 3:g.drawLine(cx-half,cy-o+h-1-i,cx+half,cy-o+h-1-i);break;
					case 0:g.drawLine(cx-o+h-1-i,cy-half,cx-o+h-1-i,cy+half);break;
					default:g.drawLine(cx-o+i,cy-half,cx-o+i,cy+half);break;
				}
			}
		}
		/** Where a button kind's label goes: push button (0), check box (1), radio button (2), bevel buttons (3 medium, 8 small, 9 large). */
		static int[] content(int[] r,int kind,int value,int adornment){
			switch(kind){
				case 0:return new int[]{r[0]+2,r[1]+4,r[2]-2,r[3]-4};
				case 1:case 2:return new int[]{r[0],r[1]+17,r[2],r[3]};
				default:{int w=(kind==8?1:kind==9?3:2)+1;return new int[]{r[0]+w,r[1]+w,r[2]-w,r[3]-w};}
			}
		}
		/**
		 * A button of a theme button kind in a draw state (0 inactive, 1 active, 2 pressed; little arrows 2/3 press the upper/lower
		 * half): push button (0; adornment 1 = the default ring), check box (1) and radio button (2) with value 0/1/2 (mixed),
		 * bevel buttons (3, 8, 9: sunk in while pressed or on), pop-up button (5), disclosure triangle (6: value 1 down, 2 left,
		 * else right), little arrows (7).
		 */
		static void button(Graphics2D g,int[] r,int kind,int state,int value,int adornment){
			int t=r[0],l=r[1],b=r[2],ri=r[3],w=ri-l,h=b-t;boolean off=state==0,down=state==2||state==3;int ink=off?SHADOW:BLACK;
			if(w<=0||h<=0)return;
			switch(kind){
				case 0:{int arc=Math.min(h,10);
					if((adornment&1)!=0){g.setColor(c(off?LIGHT:MID));g.fillRoundRect(l-4,t-4,w+8,h+8,arc+8,arc+8);g.setColor(c(ink));g.drawRoundRect(l-4,t-4,w+7,h+7,arc+8,arc+8);}
					g.setColor(c(down?PRESSED:off?LIGHT:FACE));g.fillRoundRect(l,t,w,h,arc,arc);
					if(!down&&!off){line(g,l+3,t+1,ri-4,t+1,WHITE);line(g,l+1,t+3,l+1,b-4,WHITE);line(g,l+3,b-2,ri-4,b-2,SHADOW);line(g,ri-2,t+3,ri-2,b-4,SHADOW);}
					g.setColor(c(ink));g.drawRoundRect(l,t,w-1,h-1,arc,arc);break;}
				case 1:{int s=12,y=t+(h-s)/2; // a sunken white box, a check mark or a dash
					fill(g,y,l,y+s,l+s,down?GREY:off?LIGHT:WHITE);bevel(g,y+1,l+1,y+s-1,l+s-1,down?SHADOW:MID,down?GREY:WHITE);frame(g,y,l,y+s,l+s,ink);g.setColor(c(ink));
					if(value==1){g.setStroke(new java.awt.BasicStroke(2f));g.drawLine(l+3,y+s/2,l+s/2-1,y+s-3);g.drawLine(l+s/2-1,y+s-3,l+s-2,y+2);g.setStroke(new java.awt.BasicStroke(1f));}
					else if(value==2)g.fillRect(l+3,y+s/2-1,s-6,2);
					break;}
				case 2:{int s=12,y=t+(h-s)/2;
					g.setColor(c(down?GREY:off?LIGHT:WHITE));g.fillOval(l,y,s,s);g.setColor(c(ink));g.drawOval(l,y,s-1,s-1);
					if(value==1)g.fillOval(l+3,y+3,s-6,s-6);else if(value==2)g.fillRect(l+3,y+s/2-1,s-6,2);
					break;}
				case 3:case 8:case 9:{boolean in=down||value==1;int bw=kind==8?1:kind==9?3:2;
					fill(g,t,l,b,ri,in?GREY:value==2?MID:off?LIGHT:FACE);frame(g,t,l,b,ri,ink);
					if(!off)for(int i=1;i<=bw;i++)bevel(g,t+i,l+i,b-i,ri-i,in?SHADOW:WHITE,in?MID:SHADOW);
					break;}
				case 5:{int arc=Math.min(h,8);g.setColor(c(down?PRESSED:off?LIGHT:FACE));g.fillRoundRect(l,t,w,h,arc,arc);
					if(!down&&!off){line(g,l+2,t+1,ri-3,t+1,WHITE);line(g,l+1,t+2,l+1,b-3,WHITE);}
					g.setColor(c(ink));g.drawRoundRect(l,t,w-1,h-1,arc,arc);
					int col=down?WHITE:off?SHADOW:BLACK;line(g,ri-20,t+3,ri-20,b-4,off?GREY:SHADOW);arrow(g,ri-11,t+h/2-3,3,3,col);arrow(g,ri-11,t+h/2+3,3,1,col);
					break;}
				case 6:{int o=value==1?1:value==2?0:2;arrow(g,l+w/2+(o==2?-1:o==0?1:0),t+h/2+(o==1?-1:0),6,o,off?GREY:down?0x3333aa:BLACK);break;}
				case 7:{int mid=t+h/2;fill(g,t,l,mid,ri,state==2?GREY:off?LIGHT:FACE);fill(g,mid,l,b,ri,state==3?GREY:off?LIGHT:FACE);
					g.setColor(c(ink));g.drawRoundRect(l,t,w-1,h-1,6,6);line(g,l,mid,ri-1,mid,ink);
					int col=off?SHADOW:BLACK;arrow(g,l+w/2,t+(mid-t)/2,3,3,col);arrow(g,l+w/2,mid+(b-mid)/2,3,1,col);break;}
				default:fill(g,t,l,b,ri,FACE);frame(g,t,l,b,ri,ink);break;
			}
		}
		/** A tab's label rectangle, for a tab facing north (0), south (1), east (2) or west (3). */
		static int[] tabLabel(int[] r,int direction){
			switch(direction){case 1:return new int[]{r[0]+3,r[1]+6,r[2]-2,r[3]-6};case 2:return new int[]{r[0]+6,r[1]+3,r[2]-6,r[3]-2};case 3:return new int[]{r[0]+6,r[1]+2,r[2]-6,r[3]-3};default:return new int[]{r[0]+2,r[1]+6,r[2]-3,r[3]-6};}
		}
		/** A tab (style: 0 behind, 1 pressed, 2 behind and inactive, 3 in front, 4 in front and inactive), drawn facing north and turned. */
		static void tab(Graphics2D g,int[] r,int style,int direction){
			boolean side=direction==2||direction==3;int w=side?r[2]-r[0]:r[3]-r[1],h=side?r[3]-r[1]:r[2]-r[0];if(w<=8||h<=2)return;
			BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D t=img.createGraphics();
			try{boolean front=style==3||style==4,off=style==2||style==4;int face=front?LIGHT:style==1?GREY:off?LIGHT:MID,ink=off?SHADOW:BLACK;
				java.awt.Polygon p=new java.awt.Polygon(new int[]{0,0,4,w-5,w-1,w-1},new int[]{h,4,0,0,4,h},6);t.setColor(c(face));t.fillPolygon(p);
				t.setColor(c(ink));t.drawLine(0,4,0,h-1);t.drawLine(0,4,4,0);t.drawLine(4,0,w-5,0);t.drawLine(w-5,0,w-1,4);t.drawLine(w-1,4,w-1,h-1);
				if(!off||front){line(t,1,4,1,h-1,WHITE);line(t,2,3,4,1,WHITE);line(t,5,1,w-6,1,WHITE);line(t,w-2,5,w-2,h-1,front?MID:SHADOW);}
				if(!front)line(t,0,h-1,w-1,h-1,ink);} // a tab behind the pane stands on its edge
			finally{t.dispose();}
			java.awt.geom.AffineTransform at;
			switch(direction){
				case 1:at=new java.awt.geom.AffineTransform(1,0,0,-1,r[1],r[2]);break;
				case 2:at=new java.awt.geom.AffineTransform(0,1,-1,0,r[3],r[0]);break;
				case 3:at=new java.awt.geom.AffineTransform(0,1,1,0,r[1],r[0]);break;
				default:at=java.awt.geom.AffineTransform.getTranslateInstance(r[1],r[0]);break;
			}
			g.drawImage(img,at,null);
		}
		static void tabPane(Graphics2D g,int[] r,int state){fill(g,r[0],r[1],r[2],r[3],LIGHT);frame(g,r[0],r[1],r[2],r[3],state==0?SHADOW:BLACK);bevel(g,r[0]+1,r[1]+1,r[2]-1,r[3]-1,WHITE,state==0?LIGHT:PALE);}
		/** The chasing arrows: two arcs with heads, an eighth of a turn further at each frame. */
		static void chasingArrows(Graphics2D g,int[] r,int index,int state){
			int s=Math.min(r[3]-r[1],r[2]-r[0]);if(s<6)return;double cx=r[1]+(r[3]-r[1])/2.0,cy=r[0]+(r[2]-r[0])/2.0,rad=s/2.0-2;
			g.setColor(c(state==0?GREY:BLACK));g.setStroke(new java.awt.BasicStroke(Math.max(1f,s/8f)));
			for(int k=0;k<2;k++){double a0=-(index&7)*45+k*180;g.draw(new java.awt.geom.Arc2D.Double(cx-rad,cy-rad,2*rad,2*rad,a0,125,java.awt.geom.Arc2D.OPEN));
				double end=Math.toRadians(a0+125),ex=cx+rad*Math.cos(end),ey=cy-rad*Math.sin(end),head=Math.max(2,s/5.0);
				java.awt.geom.Path2D.Double p=new java.awt.geom.Path2D.Double();p.moveTo(ex-Math.sin(end)*head,ey-Math.cos(end)*head);p.lineTo(ex-Math.cos(end)*head*0.8,ey+Math.sin(end)*head*0.8);p.lineTo(ex+Math.cos(end)*head*0.8,ey-Math.sin(end)*head*0.8);p.closePath();g.fill(p);}
			g.setStroke(new java.awt.BasicStroke(1f));
		}
		/** A slider: its sunken groove, tick marks on the side the thumb points to, the thumb (or a dragged thumb's outline). */
		static void slider(Graphics2D g,Slider k,boolean active,boolean pressed,int[] ghost,int ticks){
			int[] th=k.thumb(k.value);int mid=k.horizontal?(th[0]+th[2])/2:(th[1]+th[3])/2,ink=active?BLACK:SHADOW;
			int[] groove=k.horizontal?new int[]{mid-2,k.r[1],mid+3,k.r[3]}:new int[]{k.r[0],mid-2,k.r[2],mid+3};
			fill(g,groove[0],groove[1],groove[2],groove[3],active?GREY:LIGHT);frame(g,groove[0],groove[1],groove[2],groove[3],ink);bevel(g,groove[0]+1,groove[1]+1,groove[2]-1,groove[3]-1,active?SHADOW:LIGHT,active?MID:LIGHT);
			if(ticks>0){int s=k.start()+Slider.THUMB/2,travel=k.travel(),col=active?0x555555:GREY;
				for(int i=0;i<ticks;i++){int p=ticks==1?s:s+(int)((long)travel*i/(ticks-1));
					if(k.horizontal){int y0=k.dir==1?k.r[0]:k.r[2]-5;line(g,p,y0,p,y0+4,col);line(g,p+1,y0,p+1,y0+4,WHITE);}
					else{int x0=k.dir==1?k.r[1]:k.r[3]-5;line(g,x0,p,x0+4,p,col);line(g,x0,p+1,x0+4,p+1,WHITE);}}}
			if(k.max>k.min)sliderThumb(g,th,k.dir,k.horizontal,pressed,!active,false);
			if(ghost!=null)sliderThumb(g,ghost,k.dir,k.horizontal,false,false,true);
		}
		private static void sliderThumb(Graphics2D g,int[] r,int dir,boolean horizontal,boolean pressed,boolean off,boolean outline){
			int t=r[0],l=r[1],b=r[2],ri=r[3];if(ri-l<3||b-t<3)return;java.awt.Polygon p;
			if(dir==0)p=new java.awt.Polygon(new int[]{l,ri-1,ri-1,l},new int[]{t,t,b-1,b-1},4);
			else if(horizontal){int tip=(ri-l)/2;p=dir==2?new java.awt.Polygon(new int[]{l,ri-1,ri-1,l+tip,l},new int[]{t,t,b-1-tip,b-1,b-1-tip},5):new java.awt.Polygon(new int[]{l,l+tip,ri-1,ri-1,l},new int[]{t+tip,t,t+tip,b-1,b-1},5);}
			else{int tip=(b-t)/2;p=dir==2?new java.awt.Polygon(new int[]{l,ri-1-tip,ri-1,ri-1-tip,l},new int[]{t,t,t+tip,b-1,b-1},5):new java.awt.Polygon(new int[]{l+tip,ri-1,ri-1,l+tip,l},new int[]{t,t,b-1,b-1,t+tip},5);}
			if(outline){g.setColor(c(SHADOW));g.drawPolygon(p);return;}
			g.setColor(c(pressed?GREY:off?LIGHT:FACE));g.fillPolygon(p);g.setColor(c(off?SHADOW:BLACK));g.drawPolygon(p);
			if(!off&&!pressed){line(g,l+1,t+1,l+1,b-3,WHITE);line(g,l+1,t+1,ri-3,t+1,WHITE);}
		}
	}
}

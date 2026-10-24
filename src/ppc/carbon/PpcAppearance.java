import java.util.HashMap;
import java.util.Map;

/**
 * Appearance Manager calls CarbonLib (AppearanceLib) serves without 68K inline words: the Platinum drawing primitives
 * (DrawThemeButton, tabs, tracks, scroll bar arrows, window frames and their regions, pop-up and chasing arrows, grow
 * boxes), their geometry and hit tests, theme cursors, theme fonts, backgrounds and drawing states, and the theme
 * collection calls. The pictures come from {@link AppearanceManager68k.Platinum}, drawn into the current port; a program's
 * label, title and erase procs are called as the Appearance Manager calls them, with the theme text colour set.
 * <p>
 * The Appearance 1.1 / Mac OS 8.5-9 extras CarbonLib 1.6 exports (mostly the Appearance control panel's private interface,
 * signatures inferred - see {@link #defineCarbon16}): the user's system, small system and views fonts, font smoothing,
 * control styles and highlight colour (GetThemeFont, UseThemeFont and GetTheme's collection follow them), the theme's one
 * colour variant, scenarios (Get/SetScenario, user scenarios saved by name), theme sounds (no sound track is installed:
 * sounds are off and nothing plays, the preferences are kept), Icon Utilities' label and device calls, the Finder's address,
 * and the desktop: its pattern (DeskCPat/DeskPattern), desktop pictures per screen and monitor desktop lists. The desk itself
 * is the host's, so the calls that would paint it have nothing to draw.
 */
final class PpcAppearance {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcAppearance(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	static final int NO_ERR=0,PARAM_ERR=-50,THEME_BAD_CURSOR_INDEX_ERR=-30565;
	// Mixed Mode procInfos of the Appearance callbacks (Appearance.h)
	static final int ERASE_PROC_INFO=0x00001bc0,BUTTON_DRAW_PROC_INFO=0x0001bec0,TAB_TITLE_DRAW_PROC_INFO=0x00036ac0,
		WINDOW_TITLE_DRAW_PROC_INFO=0x000036c0,THEME_ITERATOR_PROC_INFO=0x00003ed0;

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private AppearanceManager68k am(){return rt.toolbox.appearance;}
	private int[] rect(int p) throws M68kCpuException {return am().rect(p);}
	private void writeRect(int p,int[] r) throws M68kCpuException {if(p==0)return;if(r==null)r=new int[4];for(int i=0;i<4;i++)memory.writeWord(p+2*i,r[i]);}
	private int newRect(int[] r) throws M68kCpuException {int p=rt.allocate(8,2);writeRect(p,r);return p;}
	private static int v(int point){return point>>16;}
	private static int h(int point){return (short)point;}
	private void setRgn(int rgn,int[] r) throws M68kCpuException {if(rgn!=0)rt.toolbox.api.quickDraw().setRegion(rgn,r==null||r[2]<=r[0]||r[3]<=r[1]?QdRegion.empty():QdRegion.rect(r[0],r[1],r[2],r[3]));}
	/** A region's bounding box, or null when it is empty. */
	private int[] rgnBox(int rgn) throws M68kCpuException {
		int p=rgn==0?0:memory.readLong(rgn);if(p==0)return null;
		int[] r=rect(p+2);return r[2]<=r[0]||r[3]<=r[1]?null:r;
	}
	/** Calls a program's UPP (a routine descriptor, or a TVector handed over as a TVECTOR_UPP_TYPE would be). */
	private int callProc(int upp,int procInfo,int... args) throws M68kException {return rt.isRoutineDescriptor(upp)?rt.callUniversal(upp,procInfo,args):rt.cpu.call(upp,args);}
	/** An erase proc over a rectangle (ThemeEraseUPP: bounds, eraseData, depth, isColorDev). */
	private void erase(int proc,int[] r,int eraseData) throws M68kException {
		if(proc==0)return;int p=newRect(r);try{callProc(proc,ERASE_PROC_INFO,p,eraseData,am().depth(),1);}finally{memory.free(p);}
	}
	/** Runs {@code body} with the port's foreground colour set to {@code rgb} (a theme text colour), restoring it afterwards. */
	private void withTextColor(int rgb,Body body) throws M68kException {
		int saved=rt.allocate(6,2),c=rt.allocate(6,2);
		try{rt.toolbox("GetForeColor",saved);memory.writeWord(c,(rgb>>>16&255)*0x101);memory.writeWord(c+2,(rgb>>>8&255)*0x101);memory.writeWord(c+4,(rgb&255)*0x101);rt.toolbox("RGBForeColor",c);body.run();}
		finally{rt.toolbox("RGBForeColor",saved);memory.free(c);memory.free(saved);}
	}
	private interface Body{void run() throws M68kException;}
	private void rgbColor(String call,int rgb) throws M68kException {int c=rt.allocate(6,2);memory.writeWord(c,(rgb>>>16&255)*0x101);memory.writeWord(c+2,(rgb>>>8&255)*0x101);memory.writeWord(c+4,(rgb&255)*0x101);rt.toolbox(call,c);memory.free(c);}

	void define(PpcLibraries l){
		// ---- buttons
		l.register(null,"DrawThemeButton",(r,c)->{ // (bounds, kind, newInfo, prevInfo, eraseProc, labelProc, userData)
			int b=arg(0),kind=arg(1)&0xffff,info=arg(2),eraseProc=arg(4),labelProc=arg(5),userData=arg(6);
			if(b==0||info==0){ret(PARAM_ERR);return;}
			int[] bounds=rect(b);int state=memory.readLong(info),value=memory.readWord(info+4),adornment=memory.readWord(info+6);
			int[] bg=AppearanceManager68k.Platinum.background(bounds,kind,adornment);
			erase(eraseProc,bg,userData);
			am().paint(bg,g->AppearanceManager68k.Platinum.button(g,bounds,kind,state,value,adornment));
			boolean labelled=kind!=4&&kind!=6&&kind!=7&&kind!=11&&kind!=12;
			if(labelProc!=0&&labelled&&(adornment&0x20)==0){ // kThemeAdornmentDrawIndicatorOnly: no label
				int cr=newRect(AppearanceManager68k.Platinum.content(bounds,kind,value,adornment));
				int ink=AppearanceManager68k.Platinum.disabled(state)?0x888888:kind==0&&AppearanceManager68k.Platinum.pressed(state)?0xffffff:0;
				try{withTextColor(ink,()->callProc(labelProc,BUTTON_DRAW_PROC_INFO,cr,kind,info,userData,am().depth(),1));}finally{memory.free(cr);}
			}
			ret(NO_ERR);});
		l.register(null,"GetThemeButtonRegion",(r,c)->{ // (bounds, kind, info, rgn)
			if(arg(0)==0||arg(3)==0){ret(PARAM_ERR);return;}int adornment=arg(2)==0?0:memory.readWord(arg(2)+6);
			setRgn(arg(3),AppearanceManager68k.Platinum.background(rect(arg(0)),arg(1)&0xffff,adornment));ret(NO_ERR);});
		l.register(null,"GetThemeButtonContentBounds",(r,c)->{ // (bounds, kind, info, *outBounds)
			if(arg(0)==0||arg(3)==0){ret(PARAM_ERR);return;}int info=arg(2),value=info==0?0:memory.readWord(info+4),adornment=info==0?0:memory.readWord(info+6);
			writeRect(arg(3),AppearanceManager68k.Platinum.content(rect(arg(0)),arg(1)&0xffff,value,adornment));ret(NO_ERR);});
		l.register(null,"GetThemeButtonBackgroundBounds",(r,c)->{ // (bounds, kind, info, *outBounds)
			if(arg(0)==0||arg(3)==0){ret(PARAM_ERR);return;}int adornment=arg(2)==0?0:memory.readWord(arg(2)+6);
			writeRect(arg(3),AppearanceManager68k.Platinum.background(rect(arg(0)),arg(1)&0xffff,adornment));ret(NO_ERR);});
		// the user's choices (Set... below); the Platinum defaults are kThemeCheckBoxCheckMark, kThemeScrollBarArrowsSingle, kThemeScrollBarThumbNormal
		l.register(null,"GetThemeCheckBoxStyle",(r,c)->{if(arg(0)!=0)memory.writeWord(arg(0),settings.checkBox);ret(NO_ERR);});
		l.register(null,"GetThemeScrollBarArrowStyle",(r,c)->{if(arg(0)!=0)memory.writeWord(arg(0),settings.arrows);ret(NO_ERR);});
		l.register(null,"GetThemeScrollBarThumbStyle",(r,c)->{if(arg(0)!=0)memory.writeWord(arg(0),settings.thumb);ret(NO_ERR);});

		// ---- tabs
		l.register(null,"DrawThemeTab",(r,c)->{ // (rect, style, direction, labelProc, userData)
			if(arg(0)==0){ret(PARAM_ERR);return;}
			int[] b=rect(arg(0));int style=arg(1)&0xffff,direction=arg(2)&0xffff,proc=arg(3),userData=arg(4);
			am().paint(b,g->AppearanceManager68k.Platinum.tab(g,b,style,direction));
			if(proc!=0){int lr=newRect(AppearanceManager68k.Platinum.tabLabel(b,direction));boolean off=style==2||style==4||style==5||style==6;
				try{withTextColor(off?0x888888:0,()->callProc(proc,TAB_TITLE_DRAW_PROC_INFO,lr,style,direction,am().depth(),1,userData));}finally{memory.free(lr);}}
			ret(NO_ERR);});
		l.register(null,"DrawThemeTabPane",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}int[] b=rect(arg(0));int state=arg(1);am().paint(b,g->AppearanceManager68k.Platinum.tabPane(g,b,state));ret(NO_ERR);});
		l.register(null,"GetThemeTabRegion",(r,c)->{if(arg(0)==0||arg(3)==0){ret(PARAM_ERR);return;}setRgn(arg(3),rect(arg(0)));ret(NO_ERR);}); // (rect, style, direction, rgn)

		// ---- tracks: scroll bars, sliders, progress bars
		l.register(null,"DrawThemeTrack",(r,c)->{ // (drawInfo, rgnGhost, eraseProc, eraseData)
			if(arg(0)==0){ret(PARAM_ERR);return;}
			AppearanceManager68k.Platinum.Track k=AppearanceManager68k.Platinum.Track.read(memory,arg(0));int[] ghost=arg(1)==0?null:rgnBox(arg(1));
			erase(arg(2),new int[]{k.top,k.left,k.bottom,k.right},arg(3));
			am().paint(k.top,k.left,k.bottom,k.right,g->AppearanceManager68k.Platinum.track(g,k,ghost,(k.attributes&8)!=0));ret(NO_ERR);});
		l.register(null,"DrawThemeTrackTickMarks",(r,c)->{ // (drawInfo, numTicks, eraseProc, eraseData)
			if(arg(0)==0){ret(PARAM_ERR);return;}
			AppearanceManager68k.Platinum.Track k=AppearanceManager68k.Platinum.Track.read(memory,arg(0));int n=arg(1);
			erase(arg(2),new int[]{k.top,k.left,k.bottom,k.right},arg(3));
			am().paint(k.top,k.left,k.bottom,k.right,g->AppearanceManager68k.Platinum.ticks(g,k,n));ret(NO_ERR);});
		l.register(null,"HitTestThemeTrack",(r,c)->{ // (drawInfo, Point, *partHit): Boolean
			if(arg(0)==0){ret(0);return;}
			int part=AppearanceManager68k.Platinum.Track.read(memory,arg(0)).hit(v(arg(1)),h(arg(1)));
			if(arg(2)!=0)memory.writeWord(arg(2),part);ret(part!=0?1:0);});
		l.register(null,"GetThemeTrackBounds",(r,c)->{if(arg(0)==0||arg(1)==0){ret(PARAM_ERR);return;}writeRect(arg(1),AppearanceManager68k.Platinum.Track.read(memory,arg(0)).trackBounds());ret(NO_ERR);});
		l.register(null,"GetThemeTrackDragRect",(r,c)->{if(arg(0)==0||arg(1)==0){ret(PARAM_ERR);return;}writeRect(arg(1),AppearanceManager68k.Platinum.Track.read(memory,arg(0)).dragRect());ret(NO_ERR);});
		l.register(null,"GetThemeTrackThumbRgn",(r,c)->{ // (drawInfo, rgn): empty when there is no thumb
			if(arg(0)==0||arg(1)==0){ret(PARAM_ERR);return;}AppearanceManager68k.Platinum.Track k=AppearanceManager68k.Platinum.Track.read(memory,arg(0));
			setRgn(arg(1),k.hasThumb()||k.slider()&&k.max>k.min?k.thumb():null);ret(NO_ERR);});
		l.register(null,"GetThemeTrackThumbPositionFromOffset",(r,c)->{ // (drawInfo, Point thumbOffset, *relativePosition)
			if(arg(0)==0||arg(2)==0){ret(PARAM_ERR);return;}AppearanceManager68k.Platinum.Track k=AppearanceManager68k.Platinum.Track.read(memory,arg(0));
			int d=k.horizontal()?h(arg(1)):v(arg(1));memory.writeLong(arg(2),k.relative(k.thumbStart(k.value)+d));ret(NO_ERR);});
		l.register(null,"GetThemeTrackThumbPositionFromRegion",(r,c)->{ // (drawInfo, thumbRgn, *relativePosition)
			if(arg(0)==0||arg(2)==0){ret(PARAM_ERR);return;}AppearanceManager68k.Platinum.Track k=AppearanceManager68k.Platinum.Track.read(memory,arg(0));
			int[] box=rgnBox(arg(1));memory.writeLong(arg(2),box==null?k.relative(k.thumbStart(k.value)):k.relative(k.horizontal()?box[1]:box[0]));ret(NO_ERR);});
		l.register(null,"GetThemeTrackLiveValue",(r,c)->{ // (drawInfo, relativePosition, *value)
			if(arg(0)==0||arg(2)==0){ret(PARAM_ERR);return;}AppearanceManager68k.Platinum.Track k=AppearanceManager68k.Platinum.Track.read(memory,arg(0));
			memory.writeLong(arg(2),k.valueAt(arg(1)));ret(NO_ERR);});
		l.register(null,"DrawThemeScrollBarArrows",(r,c)->{ // (bounds, enableState, pressState, isHoriz, *trackBounds)
			if(arg(0)==0){ret(PARAM_ERR);return;}int[] b=rect(arg(0));int enable=arg(1)&0xff,press=arg(2)&0xff;boolean horizontal=(arg(3)&0xff)!=0;
			am().paint(b,g->AppearanceManager68k.Platinum.scrollArrows(g,b,enable,press,horizontal));
			writeRect(arg(4),AppearanceManager68k.Platinum.scrollTrack(b,horizontal));ret(NO_ERR);});
		l.register(null,"GetThemeScrollBarTrackRect",(r,c)->{ // (bounds, enableState, pressState, isHoriz, *trackBounds)
			if(arg(0)==0||arg(4)==0){ret(PARAM_ERR);return;}writeRect(arg(4),AppearanceManager68k.Platinum.scrollTrack(rect(arg(0)),(arg(3)&0xff)!=0));ret(NO_ERR);});
		l.register(null,"HitTestThemeScrollBarArrows",(r,c)->{ // (bounds, enableState, pressState, isHoriz, Point, *trackBounds, *partcode): Boolean
			if(arg(0)==0){ret(0);return;}int[] b=rect(arg(0));boolean horizontal=(arg(3)&0xff)!=0;
			int part=(arg(1)&0xff)!=0?0:AppearanceManager68k.Platinum.scrollArrowHit(b,horizontal,v(arg(4)),h(arg(4))); // only an active bar's arrows answer
			writeRect(arg(5),AppearanceManager68k.Platinum.scrollTrack(b,horizontal));if(arg(6)!=0)memory.writeWord(arg(6),part);ret(part!=0?1:0);});
		l.register(null,"DrawThemeScrollBarDelimiters",(r,c)->{ // (flavor, contRect, state, attributes)
			if(arg(1)==0){ret(PARAM_ERR);return;}int[] b=rect(arg(1));int state=arg(2),attributes=arg(3);
			if((attributes&1)!=0)am().paint(b,g->AppearanceManager68k.Platinum.delimiters(g,b,state,attributes));ret(NO_ERR);});

		// ---- windows
		l.register(null,"DrawThemeWindowFrame",(r,c)->{ // (flavor, contRect, state, metrics, attributes, titleProc, titleData)
			if(arg(1)==0){ret(PARAM_ERR);return;}
			int flavor=arg(0)&0xffff,state=arg(2),metrics=arg(3),attributes=arg(4),proc=arg(5),data=arg(6);int[] content=rect(arg(1));
			int[] title=(attributes&0x80)!=0?titleRect(flavor,content,attributes,metrics):null;
			am().paint(AppearanceManager68k.Platinum.structure(flavor,content),g->AppearanceManager68k.Platinum.windowFrame(g,flavor,content,state,attributes,title));
			if(proc!=0&&title!=null){int tr=newRect(title);boolean active=state!=0&&state!=7&&state!=8;
				try{withTextColor(active?0:0x888888,()->callProc(proc,WINDOW_TITLE_DRAW_PROC_INFO,tr,am().depth(),1,data));}finally{memory.free(tr);}}
			ret(NO_ERR);});
		l.register(null,"DrawThemeTitleBarWidget",(r,c)->{ // (flavor, contRect, state, metrics, attributes, widget)
			if(arg(1)==0){ret(PARAM_ERR);return;}int flavor=arg(0)&0xffff,widget=arg(5)&0xffff;int[] content=rect(arg(1));boolean pressed=arg(2)==2;
			int[] box=AppearanceManager68k.Platinum.widget(flavor,content,arg(4)|(widget==1?0x18:widget==2?0x40:0x20),widget==6?0:widget);
			if(box!=null)am().paint(box,g->AppearanceManager68k.Platinum.widgetBox(g,box,widget,pressed));ret(NO_ERR);});
		l.register(null,"GetThemeWindowRegion",(r,c)->{ // (flavor, contRect, state, metrics, attributes, winRegion, rgn)
			if(arg(1)==0||arg(6)==0){ret(PARAM_ERR);return;}
			int flavor=arg(0)&0xffff,metrics=arg(3),attributes=arg(4),code=arg(5)&0xffff;int[] content=rect(arg(1));
			int[] m=metrics(metrics);setRgn(arg(6),AppearanceManager68k.Platinum.region(flavor,content,attributes,code,m[1],m[0]));ret(NO_ERR);});
		l.register(null,"GetThemeWindowRegionHit",(r,c)->{ // (flavor, contRect, state, metrics, attributes, Point, *regionHit): Boolean
			if(arg(1)==0){ret(0);return;}
			int code=AppearanceManager68k.Platinum.regionHit(arg(0)&0xffff,rect(arg(1)),arg(4),v(arg(5)),h(arg(5)));
			if(arg(6)!=0&&code>=0)memory.writeWord(arg(6),code);ret(code>=0?1:0);});
		l.register(null,"DrawThemeStandaloneGrowBox",(r,c)->{growBox(true);ret(NO_ERR);}); // (origin, growDirection, isSmall, state)
		l.register(null,"DrawThemeStandaloneNoGrowBox",(r,c)->{growBox(false);ret(NO_ERR);});

		// ---- small pieces
		l.register(null,"DrawThemePopupArrow",(r,c)->{ // (bounds, orientation, size, state, eraseProc, eraseData)
			if(arg(0)==0){ret(PARAM_ERR);return;}int[] b=rect(arg(0));int orientation=arg(1)&0xffff,size=arg(2)&0xffff,state=arg(3);
			erase(arg(4),b,arg(5));am().paint(b,g->AppearanceManager68k.Platinum.popupArrow(g,b,orientation,size,state));ret(NO_ERR);});
		l.register(null,"DrawThemeChasingArrows",(r,c)->{ // (bounds, index, state, eraseProc, eraseData)
			if(arg(0)==0){ret(PARAM_ERR);return;}int[] b=rect(arg(0));int index=arg(1),state=arg(2);
			if(arg(3)!=0)erase(arg(3),b,arg(4));else rt.toolbox("EraseRect",arg(0)); // the previous frame goes first
			am().paint(b,g->AppearanceManager68k.Platinum.chasingArrows(g,b,index,state));ret(NO_ERR);});
		l.register(null,"DrawThemeTickMark",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}int[] b=rect(arg(0));int state=arg(1);am().paint(b,g->AppearanceManager68k.Platinum.tickMark(g,b,state));ret(NO_ERR);});
		l.register(null,"GetThemeTextShadowOutset",(r,c)->{writeRect(arg(2),new int[4]);ret(NO_ERR);}); // Platinum text has no shadow

		// ---- backgrounds, colours, fonts, drawing state
		l.register(null,"ApplyThemeBackground",(r,c)->{ // (kind, bounds, state, depth, isColorDev): the port's background for erasing there
			int kind=arg(0),state=arg(2);boolean off=AppearanceManager68k.Platinum.disabled(state);
			int rgb=kind==1||kind==3||kind==4?0xeeeeee:kind==2&&off?0xeeeeee:0xdddddd; // tab pane and window headers light, placards and group boxes the face grey
			rgbColor("RGBBackColor",rgb);int pat=rt.allocate(8,2);rt.toolbox("BackPat",pat);memory.free(pat); // an all-background pattern
			ret(NO_ERR);});
		l.register(null,"SetThemeTextColorForWindow",(r,c)->{rgbColor("RGBForeColor",(arg(1)&0xff)!=0?0:0x888888);ret(NO_ERR);}); // (window, isActive, depth, isColorDev)
		l.register(null,"UseThemeFont",(r,c)->{ // (fontID, script): the theme font into the current port
			int id=arg(0)&0xffff,script=(short)arg(1);if(id==200){ret(NO_ERR);return;} // kThemeCurrentPortFont
			boolean japanese=script==1||script<0&&rt.toolbox.encoding==StackTextEncoding.JAPANESE;
			ThemeFont f=themeFont(id,japanese);byte[] name=f.name;
			int p=rt.allocate(name.length+2,2),num=rt.allocate(2,2);memory.writeByte(p,name.length);memory.load(p+1,name);
			try{rt.toolbox("GetFNum",p,num);rt.toolbox("TextFont",(short)memory.readWord(num));rt.toolbox("TextSize",f.size);rt.toolbox("TextFace",f.style);}
			finally{memory.free(num);memory.free(p);}
			ret(NO_ERR);});
		l.register(null,"NormalizeThemeDrawingState",(r,c)->{ // pen normal, black on white, an all-background pattern, srcOr text
			rt.toolbox("PenNormal");rgbColor("RGBForeColor",0);rgbColor("RGBBackColor",0xffffff);int pat=rt.allocate(8,2);rt.toolbox("BackPat",pat);memory.free(pat);rt.toolbox("TextMode",1);ret(NO_ERR);});
		l.register(null,"GetThemeDrawingState",(r,c)->{ // an opaque token holding the port's pen, colours and text settings
			if(arg(0)==0){ret(PARAM_ERR);return;}int s=rt.allocate(48,4);memory.writeLong(s,0x74647374); // 'tdst'
			rt.toolbox("GetPenState",s+4);rt.toolbox("GetForeColor",s+22);rt.toolbox("GetBackColor",s+28);
			int port=rt.toolbox.api.currentQuickDrawPort();if(port!=0){memory.writeWord(s+34,memory.readWord(port+68));memory.writeWord(s+36,memory.readByte(port+70));memory.writeWord(s+38,memory.readWord(port+72));memory.writeWord(s+40,memory.readWord(port+74));}
			memory.writeLong(arg(0),s);ret(NO_ERR);});
		l.register(null,"SetThemeDrawingState",(r,c)->{ // (state, disposeNow)
			int s=arg(0);if(s==0||!memory.isAllocated(s)||memory.readLong(s)!=0x74647374){ret(PARAM_ERR);return;}
			rt.toolbox("SetPenState",s+4);rt.toolbox("RGBForeColor",s+22);rt.toolbox("RGBBackColor",s+28);
			rt.toolbox("TextFont",(short)memory.readWord(s+34));rt.toolbox("TextFace",memory.readWord(s+36));rt.toolbox("TextMode",(short)memory.readWord(s+38));rt.toolbox("TextSize",(short)memory.readWord(s+40));
			if((arg(1)&0xff)!=0){memory.writeLong(s,0);memory.free(s);}ret(NO_ERR);});
		l.register(null,"DisposeThemeDrawingState",(r,c)->{int s=arg(0);if(s!=0&&memory.isAllocated(s)&&memory.readLong(s)==0x74647374){memory.writeLong(s,0);memory.free(s);}ret(NO_ERR);});
		l.register(null,"GetThemeMetric",(r,c)->{ // Appearance 1.1 Platinum metrics, matching the pictures drawn here
			int m=arg(0);int v=m>=0&&m<METRICS.length?METRICS[m]:-1;
			if(v<0){ret(PARAM_ERR);return;}if(arg(1)!=0)memory.writeLong(arg(1),v);ret(NO_ERR);});

		// ---- cursors
		l.register(null,"SetThemeCursor",(r,c)->ret(setCursor(arg(0),0)));
		l.register(null,"SetAnimatedThemeCursor",(r,c)->ret(setCursor(arg(0),arg(1))));

		// ---- themes
		l.register(null,"GetTheme",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}ret(collections()?themeCollection(arg(0)):-4);}); // unimpErr without a Collection Manager
		l.register(null,"SetTheme",(r,c)->ret(NO_ERR)); // the Platinum theme stays; accepted without a change
		l.register(null,"IterateThemes",(r,c)->{ // (proc, userData): the one built-in theme
			int proc=arg(0),userData=arg(1);if(proc==0){ret(PARAM_ERR);return;}
			int coll=collections()?rt.cpu.call(carbon("NewCollection")):0;if(coll==0){ret(NO_ERR);return;}
			int name=rt.allocate(256,2);
			try{themeCollection(coll);byte[] n="Apple platinum".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);memory.writeByte(name,n.length);memory.load(name+1,n);
				callProc(proc,THEME_ITERATOR_PROC_INFO,name,0,coll,userData);}
			finally{memory.free(name);rt.cpu.call(carbon("DisposeCollection"),coll);}
			ret(NO_ERR);});
		l.register(null,"IsValidAppearanceFileType",(r,c)->{int t=arg(0);ret(t==0x74686d65||t==0x706c746e||t==0x7363656e||t==0x74736e64?1:0);}); // 'thme' 'pltn' 'scen' 'tsnd'
		defineCarbon16(l);
	}

	/** Title text rectangle for DrawThemeWindowFrame (metrics give its width and height). */
	private int[] titleRect(int flavor,int[] content,int attributes,int metrics) throws M68kCpuException {int[] m=metrics(metrics);return AppearanceManager68k.Platinum.titleText(flavor,content,attributes,m[1],m[0]);}
	/** ThemeWindowMetrics: {titleHeight, titleWidth} (0 when absent). */
	private int[] metrics(int p) throws M68kCpuException {return p==0?new int[2]:new int[]{(short)memory.readWord(p+2),(short)memory.readWord(p+4)};}
	/** DrawThemeStandaloneGrowBox / NoGrowBox(origin, growDirection, isSmall, state): the box GetThemeStandaloneGrowBoxBounds describes. */
	private void growBox(boolean grow) throws M68kException {
		int origin=arg(0),n=(arg(2)&0xff)!=0?12:15,state=arg(3);int[] b={v(origin)-n,h(origin)-n,v(origin),h(origin)};
		am().paint(b,g->AppearanceManager68k.Platinum.growBox(g,b,grow,state));
	}
	private int carbon(String name) throws M68kException {return rt.nativeSymbol("CarbonLib",name,Pef.CLASS_TVECTOR,false);}
	/** Whether Collections can be made: a native NewCollection, or the Collection Manager trap ($ABF6) behind the bridge. */
	private boolean collections(){return rt.libraries.function("CarbonLib","NewCollection")!=null||rt.toolbox.implemented(0xabf6);}

	/** Theme metrics 0..62 (kThemeMetricScrollBarWidth...): 0-7 as PpcCarbon answered them. */
	private static final int[] METRICS={16,11,12,12,3,1,1,4, 4,1,21,8,3,0,1,16,8, 21,20,20,18,10,21,14,14,12,12,22,13, 7,20,16,16,20,16,16,10,12,15,12,25, 18,5,12,4,18,5,12,4,13, 12,10,12,10,8,8,2,2,12,1,1,4,3};

	/** GetTheme: the Platinum theme's settings - the current ones - added to a Collection (through CarbonLib's AddCollectionItem). */
	private int themeCollection(int coll) throws M68kException {Settings s=settings.copy();s.hilite=memory.readBytes(0xda0,6);return themeCollection(coll,s);}
	/** The settings of a scenario as Get/SetTheme's Collection tags hold them (Appearance.h). */
	private int themeCollection(int coll,Settings s) throws M68kException {
		boolean japanese=rt.toolbox.encoding==StackTextEncoding.JAPANESE;
		int add=carbon("AddCollectionItem");
		ThemeFont views=themeFont(s,3,japanese);
		Object[][] items={{"name",pascal(s.name)},{"varn",pascal(VARIANTS[0])},{"thme",pascal("Apple platinum")},{"lgsf",pascal(themeFont(s,0,japanese).name)},{"smsf",pascal(themeFont(s,1,japanese).name)},{"vfnt",pascal(views.name)},
			{"vfsz",new byte[]{(byte)(views.size>>8),(byte)views.size}},{"hcol",s.hilite},{"sbar",new byte[]{0,(byte)s.arrows}},{"sbth",new byte[]{0,(byte)s.thumb}},
			{"snds",new byte[]{(byte)(s.sounds?1:0)}},{"smsk",new byte[]{(byte)(s.soundMask>>>24),(byte)(s.soundMask>>>16),(byte)(s.soundMask>>>8),(byte)s.soundMask}},{"coll",new byte[]{1}},
			{"smoo",new byte[]{(byte)(s.smoothing?1:0)}},{"smos",new byte[]{(byte)(s.smoothingSize>>8),(byte)s.smoothingSize}}};
		int err=NO_ERR;
		for(Object[] it:items){
			byte[] data=(byte[])it[1];
			int p=rt.allocate(Math.max(1,data.length),2);memory.load(p,data);
			try{int e=rt.cpu.call(add,coll,ResourceFile68k.fourcc((String)it[0]),0,data.length,p);if(e!=0&&err==NO_ERR)err=(short)e;}finally{memory.free(p);}
		}
		if(s.user&&err==NO_ERR){int p=rt.allocate(2,2);memory.writeByte(p,1);try{int e=rt.cpu.call(add,coll,ResourceFile68k.fourcc("user"),0,1,p);if(e!=0)err=(short)e;}finally{memory.free(p);}}
		return err;
	}
	private static byte[] pascal(byte[] b){int n=Math.min(255,b.length);byte[] out=new byte[n+1];out[0]=(byte)n;System.arraycopy(b,0,out,1,n);return out;}
	private static byte[] pascal(String s){byte[] b=s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);byte[] out=new byte[b.length+1];out[0]=(byte)b.length;System.arraycopy(b,0,out,1,b.length);return out;}

	// ------------------------------------------------------------------ CarbonLib 1.6: Appearance 1.1 settings, sounds, icons, desktop

	/*
	 * Most of these are the Appearance control panel's private interface, exported by CarbonLib but in no public header; the
	 * signatures are inferred from Appearance.h's Get/SetTheme collection tags and the Mac OS 8.5-9 behaviour: getters take an
	 * output pointer and answer an OSStatus, Count/Is/Are/Can/Has calls answer their value. The settings are kept here for the
	 * life of the program (each program runs in its own VM) and GetTheme, GetThemeFont and UseThemeFont follow them.
	 */

	/** A theme font: its name (Pascal string bytes in the program's encoding), size and style. */
	static final class ThemeFont{final byte[] name;final int size,style;ThemeFont(byte[] name,int size,int style){this.name=name;this.size=size;this.style=style;}}
	/** The user's Appearance choices, as a scenario ('scen') keeps them; null font names and a 0 views size mean the theme's own. */
	private static final class Settings implements Cloneable {
		byte[] system,small,views;int viewsSize;boolean smoothing;int smoothingSize=12;
		int checkBox=1,arrows,thumb; // kThemeCheckBoxCheckMark, kThemeScrollBarArrowsSingle, kThemeScrollBarThumbNormal
		boolean sounds;int soundMask,soundVolume=255,panningSpread;
		/** HiliteRGB of a saved scenario (the live one is in low memory, $DA0). */
		byte[] hilite=DEFAULT_HILITE.clone();
		String name="Mac OS Default";boolean user;
		Settings copy(){try{Settings s=(Settings)super.clone();s.hilite=hilite.clone();return s;}catch(CloneNotSupportedException e){throw new AssertionError(e);}}
	}
	private static final byte[] DEFAULT_HILITE={(byte)0xcc,(byte)0xcc,(byte)0xcc,(byte)0xcc,(byte)0xff,(byte)0xff}; // the lavender InitGraf leaves in HiliteRGB
	private final Settings settings=new Settings();
	/** Scenarios saved with SaveUserScenario, by name. */
	private final Map<String,Settings> userScenarios=new java.util.LinkedHashMap<String,Settings>();
	/** The Platinum theme's colour variants: the one whose accents AppearanceManager68k.Platinum draws. */
	private static final String[] VARIANTS={"Lavender"};
	private static final int FNF_ERR=-43,UNIMP_ERR=-4,PROC_NOT_FOUND=-600,THEME_CONTEXT_TAG=0x74647478,MONITOR_LIST_TAG=0x6d64736c,MONITOR_RECORD_TAG=0x6d647372; // 'tdtx' 'mdsl' 'mdsr'
	private static final java.nio.charset.Charset ISO=java.nio.charset.StandardCharsets.ISO_8859_1;
	private int[] args(int n) throws M68kCpuException {int[] a=new int[n];for(int i=0;i<n;i++)a[i]=rt.arg(i);return a;}
	private byte[] pstring(int p) throws M68kCpuException {return p==0?new byte[0]:memory.readBytes(p+1,memory.readByte(p));}
	private void writePstring(int p,byte[] b) throws M68kCpuException {if(p==0)return;int n=Math.min(255,b.length);memory.writeByte(p,n);memory.load(p+1,java.util.Arrays.copyOf(b,n));}

	/** The theme's font for a ThemeFontID, with the user's system, small system and views fonts in place of the theme's. */
	ThemeFont themeFont(int id,boolean japanese){return themeFont(settings,id,japanese);}
	private static ThemeFont themeFont(Settings s,int id,boolean japanese){
		String[] f=AppearanceManager68k.themeFont(id,japanese);byte[] name=f[0].getBytes(ISO);int size=Integer.parseInt(f[1]),style=Integer.parseInt(f[2]);
		if(id==3){if(s.views!=null)name=s.views;if(s.viewsSize>0)size=s.viewsSize;}               // kThemeViewsFont
		else if(id==1||id==2||id==6||id==106){if(s.small!=null)name=s.small;}                    // small system, small emphasized, label, utility title
		else if(id!=5&&s.system!=null)name=s.system;                                              // the system font's users (not the application font)
		return new ThemeFont(name,size,style);
	}
	/** Whether a font of that name can be used: installed (GetFNum finds a family, or the name is family 0's) or the theme's own. */
	private boolean usableFont(byte[] name,boolean small) throws M68kException {
		if(name.length==0)return false;
		boolean japanese=rt.toolbox.encoding==StackTextEncoding.JAPANESE;
		if(java.util.Arrays.equals(name,AppearanceManager68k.themeFont(small?1:0,japanese)[0].getBytes(ISO)))return true;
		int p=rt.allocate(258,2),num=rt.allocate(2,2),zero=rt.allocate(256,2);
		try{writePstring(p,name);rt.toolbox("GetFNum",p,num);if((short)memory.readWord(num)!=0)return true;
			rt.toolbox("GetFontName",0,zero);return new String(pstring(zero),ISO).equalsIgnoreCase(new String(name,ISO));}
		finally{memory.free(zero);memory.free(num);memory.free(p);}
	}
	private boolean japaneseScript(int script){return script==1||script<0&&rt.toolbox.encoding==StackTextEncoding.JAPANESE;}

	private void defineCarbon16(PpcLibraries l){
		// ---- theme fonts (GetThemeFont replaces PpcCarbon's, registered earlier, so it follows the fonts set here)
		l.register(null,"GetThemeFont",(r,c)->{ // (fontID, script, name, *size, *style)
			int[] a=args(5);int id=a[0]&0xffff;
			if(id==200){int port=rt.toolbox.api.currentQuickDrawPort(); // kThemeCurrentPortFont
				if(port==0){ret(PARAM_ERR);return;}if(a[2]!=0)rt.toolbox("GetFontName",(short)memory.readWord(port+68),a[2]);
				if(a[3]!=0)memory.writeWord(a[3],memory.readWord(port+74));if(a[4]!=0)memory.writeByte(a[4],memory.readByte(port+70));ret(NO_ERR);return;}
			ThemeFont f=themeFont(id,japaneseScript((short)a[1]));writePstring(a[2],f.name);if(a[3]!=0)memory.writeWord(a[3],f.size);if(a[4]!=0)memory.writeByte(a[4],f.style);ret(NO_ERR);});
		l.register(null,"GetSystemFont",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}writePstring(p,themeFont(0,japaneseScript(-1)).name);ret(NO_ERR);}); // (Str255)
		l.register(null,"GetSmallSystemFont",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}writePstring(p,themeFont(1,japaneseScript(-1)).name);ret(NO_ERR);});
		l.register(null,"GetViewsFont",(r,c)->{int[] a=args(2);if(a[0]==0){ret(PARAM_ERR);return;}ThemeFont f=themeFont(3,japaneseScript(-1));writePstring(a[0],f.name);if(a[1]!=0)memory.writeWord(a[1],f.size);ret(NO_ERR);}); // (Str255, *size)
		l.register(null,"SetSystemFont",(r,c)->{byte[] n=pstring(arg(0));if(!usableFont(n,false)){ret(PARAM_ERR);return;}settings.system=n;ret(NO_ERR);}); // (ConstStr255Param): an installed font
		l.register(null,"SetSmallSystemFont",(r,c)->{byte[] n=pstring(arg(0));if(!usableFont(n,true)){ret(PARAM_ERR);return;}settings.small=n;ret(NO_ERR);});
		l.register(null,"SetViewsFont",(r,c)->{int[] a=args(2);byte[] n=pstring(a[0]);int size=(short)a[1]; // (ConstStr255Param, size)
			if(!usableFont(n,true)||size<6||size>72){ret(PARAM_ERR);return;}settings.views=n;settings.viewsSize=size;ret(NO_ERR);});
		l.register(null,"GetThemeFontSmoothing",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeByte(p,settings.smoothing?1:0);ret(NO_ERR);}); // (Boolean*): the preference ('smoo')
		l.register(null,"SetThemeFontSmoothing",(r,c)->{settings.smoothing=(arg(0)&0xff)!=0;ret(NO_ERR);}); // (Boolean)

		// ---- theme control styles and colours: the values GetTheme... report and GetTheme's collection hold
		l.register(null,"SetThemeCheckBoxStyle",(r,c)->{int v=arg(0)&0xffff;if(v>1){ret(PARAM_ERR);return;}settings.checkBox=v;ret(NO_ERR);});
		l.register(null,"SetThemeScrollBarArrowStyle",(r,c)->{int v=arg(0)&0xffff;if(v>1){ret(PARAM_ERR);return;}settings.arrows=v;ret(NO_ERR);});
		l.register(null,"SetThemeScrollBarThumbStyle",(r,c)->{int v=arg(0)&0xffff;if(v>1){ret(PARAM_ERR);return;}settings.thumb=v;ret(NO_ERR);});
		l.register(null,"SetHighlightColor",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.copy(p,0xda0,6);ret(NO_ERR);}); // (const RGBColor*): HiliteRGB
		l.register(null,"SetThemeCursorWithOptions",(r,c)->ret(setCursor(arg(0),0))); // (ThemeCursor, options)
		l.register(null,"CopyThemeIdentifier",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeLong(p,rt.libraries.coreFoundation.newString("com.apple.theme.appearance.platinum"));ret(NO_ERR);});
		l.register(null,"CanThemeDrawOnAllMonitors",(r,c)->ret(1)); // Platinum draws at every depth
		l.register(null,"CreateThemeDrawingContextForPort",(r,c)->{int[] a=args(2); // (port, *context): a token naming the port
			if(a[1]==0){ret(PARAM_ERR);return;}memory.writeLong(a[1],0);if(a[0]==0){ret(PARAM_ERR);return;}
			int t=rt.allocate(8,4);memory.writeLong(t,THEME_CONTEXT_TAG);memory.writeLong(t+4,a[0]);memory.writeLong(a[1],t);ret(NO_ERR);});
		l.register(null,"DisposeThemeDrawingContext",(r,c)->{int t=arg(0);if(t==0||!memory.isAllocated(t)||memory.readLong(t)!=THEME_CONTEXT_TAG){ret(PARAM_ERR);return;}memory.writeLong(t,0);memory.free(t);ret(NO_ERR);});
		l.register(null,"IsThemeCreditDialogAvailable",(r,c)->ret(0)); // the built-in Platinum theme has no credits
		l.register(null,"DisplayThemeCreditDialog",(r,c)->ret(UNIMP_ERR));

		// ---- variants and scenarios
		l.register(null,"CountThemeVariants",(r,c)->ret(VARIANTS.length));
		l.register(null,"GetIndThemeVariant",(r,c)->{int[] a=args(2);int i=a[0]&0xffff; // (index from 1, Str255)
			if(i<1||i>VARIANTS.length){writePstring(a[1],new byte[0]);ret(PARAM_ERR);return;}writePstring(a[1],VARIANTS[i-1].getBytes(ISO));ret(NO_ERR);});
		l.register(null,"GetThemeVariantByName",(r,c)->{int[] a=args(2);String n=new String(pstring(a[0]),ISO); // (name, *index)
			int found=0;for(int i=0;i<VARIANTS.length;i++)if(VARIANTS[i].equalsIgnoreCase(n))found=i+1;
			if(a[1]!=0)memory.writeWord(a[1],found);ret(found==0?PARAM_ERR:NO_ERR);});
		l.register(null,"GetThemeVariantList",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;} // (Handle*): the names as a 'STR#'
			java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();b.write(VARIANTS.length>>8);b.write(VARIANTS.length);for(String v:VARIANTS){byte[] n=pascal(v);b.write(n,0,n.length);}
			memory.writeLong(p,rt.toolbox.api.createHandle(b.toByteArray()));ret(NO_ERR);});
		l.register(null,"IterateThemeVariants",(r,c)->{int[] a=args(2);if(a[0]==0){ret(PARAM_ERR);return;} // (ThemeIteratorUPP, userData): (name, index, NULL, userData)
			int name=rt.allocate(256,2);
			try{for(int i=0;i<VARIANTS.length;i++){writePstring(name,VARIANTS[i].getBytes(ISO));if((callProc(a[0],THEME_ITERATOR_PROC_INFO,name,i+1,0,a[1])&0xff)==0)break;}}finally{memory.free(name);}
			ret(NO_ERR);});
		for(String n:new String[]{"GetScenario","GetAppearance"})l.register(null,n,(r,c)->{int coll=arg(0);if(coll==0){ret(PARAM_ERR);return;}ret(collections()?themeCollection(coll):UNIMP_ERR);}); // (Collection)
		for(String n:new String[]{"SetScenario","SetAppearance"})l.register(null,n,(r,c)->{int coll=arg(0);if(coll==0){ret(PARAM_ERR);return;}ret(collections()?applyCollection(coll):UNIMP_ERR);});
		l.register(null,"SaveUserScenario",(r,c)->{byte[] n=pstring(arg(0));if(n.length==0){ret(PARAM_ERR);return;} // (name): the current settings under that name
			Settings s=settings.copy();s.hilite=memory.readBytes(0xda0,6);s.name=new String(n,ISO);s.user=true;userScenarios.put(s.name,s);settings.name=s.name;settings.user=true;ret(NO_ERR);});
		l.register(null,"DeleteUserScenario",(r,c)->{String n=new String(pstring(arg(0)),ISO);if(userScenarios.remove(n)==null){ret(FNF_ERR);return;}if(settings.user&&settings.name.equals(n)){settings.user=false;settings.name="Mac OS Default";}ret(NO_ERR);});
		l.register(null,"GetUserScenarioFileName",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;} // (Str255): the user scenario in use
			if(!settings.user){writePstring(p,new byte[0]);ret(FNF_ERR);return;}writePstring(p,settings.name.getBytes(ISO));ret(NO_ERR);});
		l.register(null,"IterateThemeScenarios",(r,c)->{int[] a=args(2);if(a[0]==0){ret(PARAM_ERR);return;} // (ThemeIteratorUPP, userData): the built-in scenario, then the user's
			if(!collections()){ret(NO_ERR);return;}
			java.util.List<Settings> all=new java.util.ArrayList<Settings>();all.add(new Settings());all.addAll(userScenarios.values());
			int name=rt.allocate(256,2);
			try{for(int i=0;i<all.size();i++){Settings s=all.get(i);int coll=rt.cpu.call(carbon("NewCollection"));if(coll==0)break;
				try{themeCollection(coll,s);writePstring(name,s.name.getBytes(ISO));if((callProc(a[0],THEME_ITERATOR_PROC_INFO,name,i,coll,a[1])&0xff)==0)break;}
				finally{rt.cpu.call(carbon("DisposeCollection"),coll);}}}
			finally{memory.free(name);}
			ret(NO_ERR);});

		// ---- theme sounds: no sound track is installed, so sounds are off and nothing plays; the preferences are kept
		l.register(null,"AreThemeSoundsEnabled",(r,c)->ret(0));
		l.register(null,"SetThemeSoundsEnabled",(r,c)->{settings.sounds=(arg(0)&0xff)!=0;ret(NO_ERR);}); // the 'snds' preference
		l.register(null,"SetThemeSoundVolume",(r,c)->{settings.soundVolume=arg(0)&0xffff;ret(NO_ERR);});
		l.register(null,"GetThemeSoundMask",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeLong(p,settings.soundMask);ret(NO_ERR);}); // (UInt32*): 'smsk'
		l.register(null,"SetThemeSoundMask",(r,c)->{settings.soundMask=arg(0);ret(NO_ERR);});
		l.register(null,"GetThemeSoundPanningSpread",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeWord(p,settings.panningSpread);ret(NO_ERR);});
		l.register(null,"SetThemeSoundPanningSpread",(r,c)->{settings.panningSpread=arg(0)&0xffff;ret(NO_ERR);});
		l.register(null,"PlayThemeControlSound",(r,c)->ret(NO_ERR)); // sounds are off: Appearance plays nothing then
		l.register(null,"BeginThemeDragIndicatorSound",(r,c)->ret(NO_ERR));
		l.register(null,"GetThemePlaylist",(r,c)->ret(UNIMP_ERR)); // no sound track, so no playlist to hand out (signature unknown: nothing is written)
		l.register(null,"SetThemePlaylist",(r,c)->ret(UNIMP_ERR));

		// ---- icons (Icon Utilities' private calls)
		l.register(null,"GetDeepIconsEnabled",(r,c)->ret(deepIcons?1:0));
		l.register(null,"SetDeepIconsEnabled",(r,c)->deepIcons=(arg(0)&0xff)!=0); // the preference; Icon Services here always plots the deepest icon the port can show
		l.register(null,"GetIconDevice",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeLong(p,iconDevice!=0?iconDevice:rt.toolbox("GetMainDevice"));ret(NO_ERR);}); // (GDHandle*)
		l.register(null,"SetIconDevice",(r,c)->{iconDevice=arg(0);ret(NO_ERR);}); // (GDHandle): NULL = the main device again
		l.register(null,"PlotIconRefInPort",(r,c)->{int[] a=args(6); // (port, rect, align, transform, usageFlags, IconRef): PlotIconRef in that port
			if(a[1]==0||a[5]==0){ret(PARAM_ERR);return;}int port=a[0],old=rt.toolbox.api.currentQuickDrawPort();
			if(port!=0&&port!=old)rt.toolbox("SetPort",port);
			try{ret((short)rt.toolbox("PlotIconRef",a[1],(short)a[2],(short)a[3],a[4],a[5]));}finally{if(port!=0&&port!=old&&old!=0)rt.toolbox("SetPort",old);}});
		l.register(null,"SetLabel",(r,c)->{int[] a=args(3);int n=(short)a[0]; // (labelNumber, const RGBColor*, ConstStr255Param): what GetLabel answers from now on
			if(n<0||n>7||a[1]==0){ret(PARAM_ERR);return;}labels.put(n,new byte[][]{memory.readBytes(a[1],6),pascal(pstring(a[2]))});ret(NO_ERR);});
		l.register(null,"GetLabel",(r,c)->{int[] a=args(3);byte[][] set=labels.get((int)(short)a[0]); // a label SetLabel changed, else the System's (IconDispatch through the bridge)
			if(set==null){ret((short)rt.toolbox("GetLabel",(short)a[0],a[1],a[2]));return;}
			if(a[1]!=0)memory.load(a[1],set[0]);if(a[2]!=0)memory.load(a[2],set[1]);ret(NO_ERR);});
		l.register(null,"GetFinderAddress",(r,c)->ret(finderAddress(arg(0)))); // (AEAddressDesc*): typeProcessSerialNumber of the Finder process

		// ---- the desktop: the desk itself is the host's (each program is its own VM), so drawing calls have nothing to paint
		l.register(null,"GetDesktopPattern",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeLong(p,0);int pp=desktopPatternCopy();memory.writeLong(p,pp);ret(pp==0?-108:NO_ERR);}); // (PixPatHandle*): a copy, the caller's
		l.register(null,"SetDesktopPattern",(r,c)->ret(setDesktopPattern(arg(0)))); // (PixPatHandle): copied, DeskCPat and DeskPattern follow
		l.register(null,"GetDesktopPixPat",(r,c)->ret((memory.readByte(0x20b)&0x80)!=0?memory.readLong(0xcd8):0)); // the desktop's own PixPat (DeskCPat while pCDeskPat says colour)
		l.register(null,"DrawDesktop",(r,c)->ret(NO_ERR));
		l.register(null,"RebuildDesktopRegion",(r,c)->ret(NO_ERR)); // GrayRgn is kept by the Window Manager from the screens
		l.register(null,"GetDesktopPictureForDevice",(r,c)->{int[] a=args(2);if(a[1]==0){ret(PARAM_ERR);return;} // (GDHandle, FSSpec*)
			Monitor m=monitors.get(a[0]==0?rt.toolbox("GetMainDevice"):a[0]);if(m==null||m.picture==null){memory.fill(a[1],70,0);ret(FNF_ERR);return;}memory.load(a[1],m.picture);ret(NO_ERR);});
		l.register(null,"SetDesktopPictureForDevice",(r,c)->{int[] a=args(2);int dev=a[0]==0?rt.toolbox("GetMainDevice"):a[0]; // (GDHandle, const FSSpec*): NULL spec = none
			if(a[1]==0){Monitor m=monitors.get(dev);if(m!=null)m.picture=null;ret(NO_ERR);return;}
			int e=possiblePicture(a[1]);if(e!=NO_ERR){ret(e);return;}monitor(dev).picture=memory.readBytes(a[1],70);ret(NO_ERR);});
		l.register(null,"FileContainsPossibleDesktopPicture",(r,c)->ret(possiblePicture(arg(0))==NO_ERR?1:0)); // (const FSSpec*): Boolean
		// monitor desktop lists: an opaque list of per-screen records {device, picture, alignment}
		l.register(null,"GetCurrentMonitorDesktopList",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeLong(p,newMonitorList(currentMonitors()));ret(NO_ERR);}); // (*list): a copy
		l.register(null,"GetInternalMonitorDesktopList",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;} // (*list): the system's own, kept in step with the screens
			if(internalList==0)internalList=newMonitorList(currentMonitors());else monitorLists.put(internalList,currentMonitors());memory.writeLong(p,internalList);ret(NO_ERR);});
		l.register(null,"SetCurrentMonitorDesktopList",(r,c)->{java.util.List<Monitor> list=monitorLists.get(arg(0));if(list==null){ret(PARAM_ERR);return;}for(Monitor m:list)monitors.put(m.device,m.copy());ret(NO_ERR);});
		l.register(null,"InternalMonitorDesktopListChanged",(r,c)->{java.util.List<Monitor> list=internalList==0?null:monitorLists.get(internalList);if(list!=null)for(Monitor m:list)monitors.put(m.device,m.copy());ret(NO_ERR);});
		l.register(null,"DisposeMonitorDesktopList",(r,c)->{int t=arg(0);if(monitorLists.remove(t)==null){ret(PARAM_ERR);return;}if(t==internalList)internalList=0;memory.writeLong(t,0);memory.free(t);ret(NO_ERR);});
		l.register(null,"RefreshMonitorDesktopList",(r,c)->{int t=arg(0);java.util.List<Monitor> list=monitorLists.get(t);if(list==null){ret(PARAM_ERR);return;} // the screens now: records of gone screens dropped, new ones added
			java.util.List<Monitor> now=new java.util.ArrayList<Monitor>();for(Monitor m:currentMonitors()){Monitor kept=null;for(Monitor o:list)if(o.device==m.device)kept=o;now.add(kept!=null?kept:m);}
			monitorLists.put(t,now);ret(NO_ERR);});
		l.register(null,"CountMonitorDesktopRecordsInList",(r,c)->{java.util.List<Monitor> list=monitorLists.get(arg(0));ret(list==null?0:list.size());});
		l.register(null,"GetMonitorDesktopRecordFromList",(r,c)->{int[] a=args(3); // (list, index from 1, *record): a record of the caller's (a copy)
			java.util.List<Monitor> list=monitorLists.get(a[0]);if(a[2]!=0)memory.writeLong(a[2],0);int i=a[1];
			if(list==null||a[2]==0||i<1||i>list.size()){ret(PARAM_ERR);return;}memory.writeLong(a[2],newMonitorRecord(list.get(i-1).copy()));ret(NO_ERR);});
		l.register(null,"SetMonitorDesktopRecordFromList",(r,c)->{int[] a=args(3);java.util.List<Monitor> list=monitorLists.get(a[0]);Monitor m=monitorRecords.get(a[2]);int i=a[1]; // (list, index, record)
			if(list==null||m==null||i<1||i>list.size()+1){ret(PARAM_ERR);return;}if(i>list.size())list.add(m.copy());else list.set(i-1,m.copy());ret(NO_ERR);});
		l.register(null,"GetMonitorDesktopAlignment",(r,c)->{int[] a=args(2);Monitor m=monitorRecords.get(a[0]);if(m==null||a[1]==0){ret(PARAM_ERR);return;}memory.writeLong(a[1],m.alignment);ret(NO_ERR);}); // 'dpan'
		l.register(null,"SetMonitorDesktopAlignment",(r,c)->{int[] a=args(2);Monitor m=monitorRecords.get(a[0]);if(m==null){ret(PARAM_ERR);return;}m.alignment=a[1];ret(NO_ERR);});
		l.register(null,"MonitorDesktopHasPicture",(r,c)->{Monitor m=monitorRecords.get(arg(0));ret(m!=null&&m.picture!=null?1:0);});
		l.register(null,"SetMonitorDesktopToFile",(r,c)->{int[] a=args(2);Monitor m=monitorRecords.get(a[0]);if(m==null){ret(PARAM_ERR);return;} // (record, const FSSpec*): NULL = no picture
			if(a[1]==0){m.picture=null;ret(NO_ERR);return;}int e=possiblePicture(a[1]);if(e!=NO_ERR){ret(e);return;}m.picture=memory.readBytes(a[1],70);ret(NO_ERR);});
		l.register(null,"CopyMonitorDesktopFile",(r,c)->{int[] a=args(2);Monitor m=monitorRecords.get(a[0]);if(m==null||a[1]==0){ret(PARAM_ERR);return;}
			if(m.picture==null){memory.fill(a[1],70,0);ret(FNF_ERR);return;}memory.load(a[1],m.picture);ret(NO_ERR);});
		l.register(null,"DrawMonitorDesktopFromList",(r,c)->ret(monitorLists.containsKey(arg(0))?NO_ERR:PARAM_ERR)); // nothing of the desk is the program's to paint
		l.register(null,"DrawMonitorDesktopFromListWillTakeTime",(r,c)->ret(0)); // so it never takes time
	}

	private boolean deepIcons=true;private int iconDevice;
	/** Labels SetLabel changed: {RGBColor (6 bytes), Pascal name}. */
	private final Map<Integer,byte[][]> labels=new HashMap<Integer,byte[][]>();

	/** SetScenario / SetAppearance: the settings a Collection's Get/SetTheme tags hold (those it has; fonts must be installed). */
	private int applyCollection(int coll) throws M68kException {
		Settings s=settings.copy();byte[] v;
		if((v=item(coll,"hcol"))!=null&&v.length>=6)s.hilite=java.util.Arrays.copyOf(v,6);else s.hilite=memory.readBytes(0xda0,6);
		if((v=item(coll,"lgsf"))!=null&&v.length>0){byte[] n=java.util.Arrays.copyOfRange(v,1,Math.min(v.length,1+(v[0]&255)));if(!usableFont(n,false))return PARAM_ERR;s.system=n;}
		if((v=item(coll,"smsf"))!=null&&v.length>0){byte[] n=java.util.Arrays.copyOfRange(v,1,Math.min(v.length,1+(v[0]&255)));if(!usableFont(n,true))return PARAM_ERR;s.small=n;}
		if((v=item(coll,"vfnt"))!=null&&v.length>0){byte[] n=java.util.Arrays.copyOfRange(v,1,Math.min(v.length,1+(v[0]&255)));if(!usableFont(n,true))return PARAM_ERR;s.views=n;}
		if((v=item(coll,"vfsz"))!=null&&v.length>=2)s.viewsSize=(short)((v[0]&255)<<8|v[1]&255);
		if((v=item(coll,"sbar"))!=null&&v.length>=2&&(v[1]&255)<=1)s.arrows=v[1]&255;
		if((v=item(coll,"sbth"))!=null&&v.length>=2&&(v[1]&255)<=1)s.thumb=v[1]&255;
		if((v=item(coll,"snds"))!=null&&v.length>=1)s.sounds=v[0]!=0;
		if((v=item(coll,"smsk"))!=null&&v.length>=4)s.soundMask=(v[0]&255)<<24|(v[1]&255)<<16|(v[2]&255)<<8|v[3]&255;
		if((v=item(coll,"smoo"))!=null&&v.length>=1)s.smoothing=v[0]!=0;
		if((v=item(coll,"smos"))!=null&&v.length>=2){int n=(v[0]&255)<<8|v[1]&255;if(n>=12&&n<=24)s.smoothingSize=n;}
		if((v=item(coll,"name"))!=null&&v.length>0)s.name=new String(v,1,Math.min(v.length-1,v[0]&255),ISO);
		s.user=(v=item(coll,"user"))!=null&&v.length>=1&&v[0]!=0;
		memory.load(0xda0,s.hilite);s.hilite=DEFAULT_HILITE.clone();
		Settings old=settings;old.system=s.system;old.small=s.small;old.views=s.views;old.viewsSize=s.viewsSize;old.arrows=s.arrows;old.thumb=s.thumb;old.sounds=s.sounds;old.soundMask=s.soundMask;
		old.smoothing=s.smoothing;old.smoothingSize=s.smoothingSize;old.name=s.name;old.user=s.user;
		return NO_ERR;
	}
	/** A Collection item's bytes (id 0), null when it is absent. */
	private byte[] item(int coll,String tag) throws M68kException {
		int size=rt.allocate(4,4);
		try{memory.writeLong(size,0);int e=(short)rt.toolbox("GetCollectionItem",coll,ResourceFile68k.fourcc(tag),0,size,0);int n=memory.readLong(size);if(e!=0||n<0||n>4096)return null;
			int buf=rt.allocate(Math.max(1,n),4);try{e=(short)rt.toolbox("GetCollectionItem",coll,ResourceFile68k.fourcc(tag),0,size,buf);return e!=0?null:memory.readBytes(buf,Math.min(n,memory.readLong(size)));}finally{memory.free(buf);}}
		finally{memory.free(size);}
	}

	/** GetFinderAddress: the Finder's process (type 'FNDR', creator 'MACS' - a real one or the stand-in GetNextProcess lists) as an address desc. */
	private int finderAddress(int desc) throws M68kException {
		if(desc==0)return PARAM_ERR;memory.writeLong(desc,0x6e756c6c);memory.writeLong(desc+4,0); // typeNull
		int psn=rt.allocate(8,4),info=rt.allocate(64,4);
		try{memory.writeLong(psn,0);memory.writeLong(psn+4,0);
			while((short)rt.toolbox("GetNextProcess",psn)==0){
				memory.fill(info,64,0);memory.writeLong(info,60);
				if((short)rt.toolbox("GetProcessInformation",psn,info)!=0)continue;
				if(memory.readLong(info+16)==0x464e4452&&memory.readLong(info+20)==0x4d414353)return (short)rt.toolbox("AECreateDesc",0x70736e20,psn,8,desc); // 'psn '
			}
			return PROC_NOT_FOUND;}
		finally{memory.free(info);memory.free(psn);}
	}

	// ---- desktop pattern and pictures

	/** A copy of the desktop's pattern: DeskCPat while pCDeskPat ($20B) says colour, else the classic DeskPattern ($A3C) as a pattern PixPat. */
	private int desktopPatternCopy() throws M68kException {
		int out=rt.toolbox("NewPixPat");if(out==0)return 0;int desk=memory.readLong(0xcd8);
		if((memory.readByte(0x20b)&0x80)!=0&&desk!=0){rt.toolbox("CopyPixPat",desk,out);return out;}
		int data=memory.readLong(out);memory.writeWord(data,0);memory.load(data+20,memory.readBytes(0xa3c,8));memory.writeWord(data+14,0xffff); // patType 0, pat1Data
		int patData=memory.readLong(data+6);if(patData!=0){rt.toolbox("SetHandleSize",patData,8);int p=memory.readLong(patData);if(p!=0)memory.copy(0xa3c,p,8);}
		return out;
	}
	/** SetDesktopPattern: a copy becomes DeskCPat (SetDeskCPat), and a pattern PixPat's 8 bytes the classic DeskPattern too. */
	private int setDesktopPattern(int pp) throws M68kException {
		int data=pp==0?0:memory.readLong(pp);if(data==0)return PARAM_ERR;
		int type=(short)memory.readWord(data);if(type<0||type>2)return PARAM_ERR;
		int copy=rt.toolbox("NewPixPat");if(copy==0)return -108;rt.toolbox("CopyPixPat",pp,copy);
		int old=memory.readLong(0xcd8);rt.toolbox("SetDeskCPat",copy);
		if(old!=0&&old==ownedDeskPattern)rt.toolbox("DisposePixPat",old);ownedDeskPattern=copy;
		if(type==0)memory.copy(data+20,0xa3c,8);
		return NO_ERR;
	}
	/** The DeskCPat SetDesktopPattern made (disposed when replaced; one installed by someone else is left alone). */
	private int ownedDeskPattern;
	/** Whether a file can be a desktop picture: a picture file type, or picture data (PICT, JPEG, GIF, PNG, BMP, TIFF) in its data fork. */
	private int possiblePicture(int spec) throws M68kException {
		if(spec==0)return PARAM_ERR;int info=rt.allocate(16,4);
		try{int e=(short)rt.toolbox("FSpGetFInfo",spec,info);if(e!=0)return e;
			int type=memory.readLong(info);
			for(String t:new String[]{"PICT","JPEG","GIFf","PNGf","BMPf","BMP ","TIFF","qtif","8BPS","SGI ","TPIC"})if(type==ResourceFile68k.fourcc(t))return NO_ERR;}
		finally{memory.free(info);}
		int[] err={0};byte[] d=new PpcQuickDrawExtras(rt).dataFork(spec,err);if(d==null)return err[0]!=0?err[0]:PARAM_ERR;
		boolean ok=d.length>=4&&((d[0]&255)==0xff&&(d[1]&255)==0xd8||d[0]=='G'&&d[1]=='I'&&d[2]=='F'&&d[3]=='8'||(d[0]&255)==0x89&&d[1]=='P'&&d[2]=='N'&&d[3]=='G'
			||d[0]=='B'&&d[1]=='M'||d[0]=='I'&&d[1]=='I'&&d[2]==42||d[0]=='M'&&d[1]=='M'&&d[3]==42)||PpcQuickDrawExtras.pictureOffset(d)>=0;
		return ok?NO_ERR:PARAM_ERR;
	}

	/** One screen's desktop: its GDevice, picture file (an FSSpec's 70 bytes, null for none) and picture alignment ('dpan'). */
	private static final class Monitor {
		final int device;byte[] picture;int alignment;Monitor(int device){this.device=device;}
		Monitor copy(){Monitor m=new Monitor(device);m.picture=picture==null?null:picture.clone();m.alignment=alignment;return m;}
	}
	private final Map<Integer,Monitor> monitors=new HashMap<Integer,Monitor>();
	private final Map<Integer,java.util.List<Monitor>> monitorLists=new HashMap<Integer,java.util.List<Monitor>>();
	private final Map<Integer,Monitor> monitorRecords=new HashMap<Integer,Monitor>();
	private int internalList;
	private Monitor monitor(int device){Monitor m=monitors.get(device);if(m==null){m=new Monitor(device);monitors.put(device,m);}return m;}
	/** The screens (the GDevice list) with their current desktop settings, as copies. */
	private java.util.List<Monitor> currentMonitors() throws M68kException {
		java.util.List<Monitor> out=new java.util.ArrayList<Monitor>();
		for(int dev=rt.toolbox("GetDeviceList"),n=0;dev!=0&&n<64;dev=rt.toolbox("GetNextDevice",dev),n++)out.add(monitor(dev).copy());
		if(out.isEmpty()){int main=rt.toolbox("GetMainDevice");if(main!=0)out.add(monitor(main).copy());}
		return out;
	}
	private int newMonitorList(java.util.List<Monitor> list) throws M68kCpuException {int t=rt.allocate(8,4);memory.writeLong(t,MONITOR_LIST_TAG);memory.writeLong(t+4,list.size());monitorLists.put(t,list);return t;}
	private int newMonitorRecord(Monitor m) throws M68kCpuException {int t=rt.allocate(8,4);memory.writeLong(t,MONITOR_RECORD_TAG);memory.writeLong(t+4,m.device);monitorRecords.put(t,m);return t;}

	// ------------------------------------------------------------------ theme cursors

	private final Map<Long,Integer> cursors=new HashMap<Long,Integer>();
	/** The System's animated watch: CURS 4 then -6078..-6072 (its 'acur' -6078). */
	private static final int[] WATCH_FRAMES={4,-6078,-6077,-6076,-6075,-6074,-6073,-6072};
	/** SetThemeCursor / SetAnimatedThemeCursor: the System's 'CURS' where it has one (I-beam, cross, plus, watch), else a built-in picture. */
	private int setCursor(int kind,int step) throws M68kException {
		if(kind<0||kind>17)return THEME_BAD_CURSOR_INDEX_ERR;
		int id=kind==4?1:kind==5?2:kind==6?3:kind==7?WATCH_FRAMES[Math.floorMod(step,WATCH_FRAMES.length)]:0;
		// Without a display there is no cursor to set (the host's cursor conversion needs a toolkit): the record is still made
		boolean headless=java.awt.GraphicsEnvironment.isHeadless();
		if(id!=0){int h=rt.toolbox("GetCursor",id);int p=h==0?0:memory.readLong(h);if(p!=0){if(!headless)rt.toolbox("SetCursor",p);return NO_ERR;}}
		int frame=kind==14?Math.floorMod(step,4):0;long key=(long)kind<<32|frame;
		Integer rec=cursors.get(key);
		if(rec==null){rec=cursorRecord(ThemeCursors.picture(kind==7?0:kind,frame));cursors.put(key,rec);}
		if(!headless)rt.toolbox("SetCursor",rec);
		return NO_ERR;
	}
	/** A Cursor record (data, mask, hotSpot) from a picture: '#' black, '.' white, ' ' transparent; arrow-like pictures get a white outline. */
	private int cursorRecord(ThemeCursors.Picture p) throws M68kCpuException {
		int rec=rt.allocate(68,2);int[] data=new int[16],mask=new int[16];
		for(int y=0;y<16;y++){String row=(p.rows[y]+"                ").substring(0,16);for(int x=0;x<16;x++){char ch=row.charAt(x);if(ch=='#')data[y]|=0x8000>>>x;if(ch!=' ')mask[y]|=0x8000>>>x;}}
		if(p.outline){int[] m=mask.clone();for(int y=0;y<16;y++)for(int dy=-1;dy<=1;dy++){int yy=y+dy;if(yy<0||yy>15)continue;int d=data[yy];m[y]|=(d|d<<1|d>>>1)&0xffff;}mask=m;}
		for(int i=0;i<16;i++){memory.writeWord(rec+2*i,data[i]);memory.writeWord(rec+32+2*i,mask[i]);}
		memory.writeWord(rec+64,p.hotV);memory.writeWord(rec+66,p.hotH);return rec;
	}

	/** Built-in 16 x 16 pictures of the theme cursors the System file has no 'CURS' for. */
	static final class ThemeCursors {
		static final class Picture{final String[] rows;final int hotV,hotH;final boolean outline;Picture(String[] rows,int hotV,int hotH,boolean outline){this.rows=rows;this.hotV=hotV;this.hotH=hotH;this.outline=outline;}}
		private static final String[] ARROW={
			"                "," #              "," ##             "," ###            "," ####           "," #####          "," ######         "," #######        ",
			" ########       "," #####          "," ## ##          "," #   ##         ","     ##         ","      ##        ","      ##        ","                "};
		/** The arrow with a 7 x 7 badge in the bottom-right corner. */
		private static String[] badged(String... badge){
			String[] rows=ARROW.clone();
			for(int i=0;i<badge.length;i++){int y=9+i;rows[y]=(rows[y]+"                ").substring(0,9)+badge[i];}
			return rows;
		}
		static Picture picture(int kind,int frame){
			switch(kind){
				case 1:return new Picture(badged("#######","#..#..#","#..#..#","#######","#..#..#","#..#..#","#######"),1,1,true); // copy: a plus box
				case 2:return new Picture(badged("#######","#..####","#...###","#..#.##","#.#...#","##....#","#######"),1,1,true); // alias: a curved arrow
				case 3:return new Picture(badged("#######","#.....#","#.###.#","#.....#","#.###.#","#.....#","#######"),1,1,true); // contextual menu
				case 8:return new Picture(new String[]{
					"                ","                ","                ","    ## ## ##    ","   #..#..#..##  ","   #........#.# ","    #.........# ","   ##.........# ",
					"  #...........# ","  #...........# ","   #..........# ","    #........#  ","     #.......#  ","      #......#  ","      #......#  ","                "},8,8,false); // closed hand
				case 9:return new Picture(new String[]{
					"       ##       ","   ## #..###    ","  #..##..#..#   ","  #..##..#..# # ","   #..#..#..##.#","   #..#..#..#..#"," ## #.......#..#","#..##..........#",
					"#...#.........# "," #............# ","  #...........# ","  #..........#  ","   #.........#  ","    #.......#   ","     #......#   ","     #......#   "},8,8,false); // open hand
				case 10:case 11:case 12:case 13:return new Picture(new String[]{
					"      ##        ","     #..#       ","     #..#       ","     #..#       ","     #..###     ","     #..#..##   ","  ## #..#..#.#  "," #..##.......#  ",
					" #...#.......#  ","  #..........#  ","   #.........#  ","   #........#   ","    #.......#   ","     #......#   ","     #......#   ","     ########   "},0,6,false); // pointing hand (and the counting hands)
				case 14:return spinner(frame);
				case 15:case 16:case 17:return resize(kind);
				default:return new Picture(ARROW,1,1,true);
			}
		}
		/** The spinning cursor: a disc of alternating quarters, a quarter turn further each frame. */
		private static Picture spinner(int frame){
			String[] rows=new String[16];
			for(int y=0;y<16;y++){StringBuilder sb=new StringBuilder();
				for(int x=0;x<16;x++){double dx=x-7.5,dy=y-7.5,d=Math.sqrt(dx*dx+dy*dy);
					if(d>7.6)sb.append(' ');else if(d>6.4)sb.append('#');
					else{int q=(int)Math.floor((Math.toDegrees(Math.atan2(dy,dx))+360)%360/90);sb.append(((q+frame)&1)==0?'#':'.');}}
				rows[y]=sb.toString();}
			return new Picture(rows,8,8,false);
		}
		/** Resize cursors: two bars with an arrow to the left, the right, or both. */
		private static Picture resize(int kind){
			char[][] g=new char[16][16];for(char[] row:g)java.util.Arrays.fill(row,' ');
			for(int y=3;y<13;y++){g[y][6]='#';g[y][9]='#';}
			boolean left=kind!=16,right=kind!=15;
			if(left){for(int x=1;x<6;x++){g[7][x]='#';g[8][x]='#';}for(int d=1;d<=3;d++){g[7-d][1+d]='#';g[8+d][1+d]='#';}}
			if(right){for(int x=10;x<15;x++){g[7][x]='#';g[8][x]='#';}for(int d=1;d<=3;d++){g[7-d][14-d]='#';g[8+d][14-d]='#';}}
			String[] rows=new String[16];for(int y=0;y<16;y++)rows[y]=new String(g[y]);
			return new Picture(rows,7,7,true);
		}
	}
}

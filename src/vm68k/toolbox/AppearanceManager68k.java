/**
 * The Appearance Manager's trap-level calls (Mac OS 8): ControlDispatch $AA73 and AppearanceDispatch $AA74, selector in D0.W,
 * Pascal arguments on the stack. Controls here have no embedding hierarchy or keyboard focus, so those calls answer
 * plainly (no root control, no focus); visibility and activation map to the classic control record; the theme calls set
 * Platinum colours and draw simple Platinum-like frames with QuickDraw. Programs written for Carbon rely on these (CarbonLib
 * always had the Appearance Manager); Gestalt 'appr' stays undefined for 68K programs (see Toolbox68k.gestaltValue).
 * IconServicesDispatch $AA75 is routed to {@link IconServices68k}. {@link Platinum} draws the theme primitives (buttons, tabs,
 * tracks, window frames...) with Java2D into the current port, for these traps and for the CarbonLib calls in PpcAppearance.
 */
final class AppearanceManager68k {
	private final Toolbox68k tb;
	private final M68kMemory memory;
	final IconServices68k icons;
	AppearanceManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;icons=new IconServices68k(tb);}

	static final int ERR_ROOT_ALREADY_EXISTS=-30585;
	static final int NO_ERR=0,ERR_NO_ROOT_CONTROL=-30586,ERR_DATA_NOT_SUPPORTED=-30581,ERR_UNIMPLEMENTED=-4,THEME_HAS_NO_ACCENTS_ERR=-30564;

	/** Reads the Pascal arguments (sizes 2 or 4, left to right) and pops them; {@code result} bytes stay above them. */
	private int[] pop(M68kCpu cpu,int... sizes) throws M68kCpuException {
		int[] v=new int[sizes.length];int at=cpu.a[7];
		for(int i=sizes.length-1;i>=0;i--){if(sizes[i]==4){v[i]=memory.readLong(at);at+=4;}else{v[i]=(short)memory.readWord(at);at+=2;}}
		cpu.a[7]=at;return v;
	}
	private void result(M68kCpu cpu,int bytes,int value) throws M68kCpuException {if(bytes==4)memory.writeLong(cpu.a[7],value);else if(bytes==2)memory.writeWord(cpu.a[7],value);else memory.writeByte(cpu.a[7],value);}

	private final java.util.Map<String,byte[]> controlData=new java.util.HashMap<String,byte[]>();
	private static String dataKey(int control,int part,int tag){return control+"/"+(part&0xffff)+"/"+tag;}
	private static final int TAG_POPUP_MENU=0x6d68616e,TAG_POPUP_OWNED_MENU=0x6f6d7266,TAG_POPUP_MENU_ID=0x6d6e6964; // 'mhan' 'omrf' 'mnid'
	/** A pop-up's menu handle / ID tags answered from its private data (what the pop-up shows), else null. */
	private byte[] popupMenuData(int control,int tag) throws M68kException {
		if(tag!=TAG_POPUP_MENU&&tag!=TAG_POPUP_MENU_ID||!tb.controls.isPopup(control))return null;
		int menu=tb.controls.popupMenuHandle(control);if(menu==0)return null;
		if(tag==TAG_POPUP_MENU)return new byte[]{(byte)(menu>>>24),(byte)(menu>>>16),(byte)(menu>>>8),(byte)menu};
		int id=memory.readWord(memory.readLong(menu));return new byte[]{(byte)(id>>>8),(byte)id};
	}
	/** kControlPushButtonDefaultTag ('dflt') set true on the control. */
	boolean isDefaultButton(int control){byte[] v=controlData.get(dataKey(control,0,0x64666c74));return v!=null&&v.length>0&&v[0]!=0;}
	/** DisposeControl: the control's data goes with it (the Handle may be reused). */
	void forget(int control){if(!controlData.isEmpty())controlData.keySet().removeIf(k->k.startsWith(control+"/"));}

	boolean dispatch(M68kCpu cpu,int trap) throws M68kException {
		if(trap==0xa75)return icons.dispatch(cpu);
		int selector=cpu.d[0]&0xffff;
		return trap==0xa73?control(cpu,selector):appearance(cpu,selector);
	}

	/**
	 * The embedding hierarchy, kept flat: each window's root control (CreateRootControl), and the container a control was
	 * embedded in (EmbedControl); a control nobody embedded belongs to its window's root, as with kWindowStandardHandler roots.
	 */
	private final java.util.Map<Integer,Integer> rootControls=new java.util.HashMap<Integer,Integer>(),superControls=new java.util.HashMap<Integer,Integer>();
	private int root(int window) throws M68kCpuException {Integer r=rootControls.get(window);return r!=null&&tb.controls.data(r)!=0?r:0;}
	private int superControl(int control) throws M68kCpuException {
		int d=tb.controls.data(control);if(d==0)return 0;
		Integer p=superControls.get(control);if(p!=null&&tb.controls.data(p)!=0)return p;
		int root=root(memory.readLong(d+4));return root==control?0:root;
	}
	/**
	 * Whether an embedder of the control (its container, theirs ...) is hidden: HideControl hides the controls embedded in
	 * the one hidden with it, and ShowControl brings back those that were visible (latent visibility). Iconographer's colour
	 * palette shows one picker pane per tab and hides the others' panes only - the hidden HSV pane's labels were drawn over
	 * the RGB picker's when the tabs redrew their pane.
	 */
	boolean hiddenByEmbedder(int control) throws M68kCpuException {
		for(int p=superControl(control),n=0;p!=0&&n<64;p=superControl(p),n++){int d=tb.controls.data(p);if(d!=0&&memory.readByte(d+16)==0)return true;}
		return false;
	}
	/** The controls embedded in {@code container}, in the window's control-list order. */
	private java.util.List<Integer> subControls(int container) throws M68kCpuException {
		java.util.List<Integer> out=new java.util.ArrayList<Integer>();int d=tb.controls.data(container);if(d==0)return out;
		int window=memory.readLong(d+4);
		for(int h=memory.readLong(window+140),n=0;h!=0&&n<10000;n++){int p=memory.readLong(h);if(p==0)break;if(h!=container&&superControl(h)==container)out.add(h);h=memory.readLong(p);}
		return out;
	}
	/**
	 * What the Appearance CDEFs answer for a standard tag nobody set: its default value (all zero - not default, not cancel,
	 * no font style; kind-specific tags such as a bevel button's menu stay errDataNotSupported). PowerPlant-built programs (ImageViewer-J) ask a new push button for 'dflt' and give up on errors.
	 */
	static byte[] standardTagDefault(int tag){
		switch(tag){
			case 0x64666c74:case 0x636e636c:case 0x696e6465:case 0x616e696d:case 0x6c697665:case 0x6c617374:return new byte[1]; // 'dflt' 'cncl' 'inde' 'anim' 'live' 'last': Boolean
			case 0x666f6e74:return new byte[24];                                                 // 'font': ControlFontStyleRec
			default:return null;
		}
	}
	private boolean control(M68kCpu cpu,int selector) throws M68kException {
		if(tb.controls.appearanceCall(cpu,selector))return true; // controls defined in Java (Appearance kinds, the Data Browser): data tags, keys, focus, idle
		switch(selector){
			case 0x01:{int[] a=pop(cpu,4,4);int w=a[0]; // CreateRootControl(window, *outControl): a user pane over the whole window, the embedder of every other control
				Integer root=rootControls.get(w);if(root!=null&&tb.controls.data(root)!=0){if(a[1]!=0)memory.writeLong(a[1],root);result(cpu,2,ERR_ROOT_ALREADY_EXISTS);return true;}
				int h=tb.controls.create(w,(short)memory.readWord(w+16),(short)memory.readWord(w+18),(short)memory.readWord(w+20),(short)memory.readWord(w+22),new byte[]{0},true,0,0,0,256,0);
				rootControls.put(w,h);if(a[1]!=0)memory.writeLong(a[1],h);result(cpu,2,h==0?-108:NO_ERR);return true;}
			case 0x02:{int[] a=pop(cpu,4,4);int root=root(a[0]);if(a[1]!=0)memory.writeLong(a[1],root);result(cpu,2,root==0?ERR_NO_ROOT_CONTROL:NO_ERR);return true;} // GetRootControl
			case 0x03:{int[] a=pop(cpu,4,4);if(tb.controls.data(a[0])==0||tb.controls.data(a[1])==0){result(cpu,2,-30584);return true;}superControls.put(a[0],a[1]);result(cpu,2,NO_ERR);return true;} // EmbedControl(control, container)
			case 0x04:{int[] a=pop(cpu,4,4);int root=root(a[1]);if(root!=0&&root!=a[0])superControls.put(a[0],root);result(cpu,2,root==0?ERR_NO_ROOT_CONTROL:NO_ERR);return true;} // AutoEmbedControl(control, window)
			case 0x05:{int[] a=pop(cpu,4);int d=tb.controls.data(a[0]);tb.writeBoolean(cpu.a[7],d!=0&&memory.readByte(d+17)!=255);return true;} // IsControlActive
			case 0x06:{int[] a=pop(cpu,4);int d=tb.controls.data(a[0]);tb.writeBoolean(cpu.a[7],d!=0&&memory.readByte(d+16)!=0);return true;} // IsControlVisible
			case 0x07:case 0x08:{int[] a=pop(cpu,4);if(tb.controls.data(a[0])!=0)trap(0xa95d,a[0],4,selector==7?0:255,2);result(cpu,2,NO_ERR);return true;} // Activate/DeactivateControl
			case 0x09:{int[] a=pop(cpu,4,4,4); // FindControlUnderMouse(where, window, *part)
				int slot=memory.allocate(4,4);try{int part=trapResult(0xa96c,2,a[0],4,a[1],4,slot,4);if(a[2]!=0)memory.writeWord(a[2],part);result(cpu,4,part!=0?memory.readLong(slot):0);}finally{memory.free(slot);}
				return true;}
			case 0x0a:{int[] a=pop(cpu,4,4,2,4);result(cpu,2,trapResult(0xa968,2,a[0],4,a[1],4,a[3],4));return true;} // HandleControlClick -> TrackControl
			case 0x0b:{pop(cpu,4,2,2,2);result(cpu,2,0);return true;} // HandleControlKey
			case 0x0c:{pop(cpu,4);return true;} // IdleControls
			case 0x0d:{int[] a=pop(cpu,4,4);if(a[1]!=0)memory.writeLong(a[1],0);result(cpu,2,NO_ERR);return true;} // GetKeyboardFocus
			case 0x0e:{pop(cpu,4,4,2);result(cpu,2,NO_ERR);return true;} // SetKeyboardFocus
			case 0x0f:case 0x10:case 0x19:{pop(cpu,4);result(cpu,2,NO_ERR);return true;} // Advance/Reverse/ClearKeyboardFocus
			case 0x11:{int[] a=pop(cpu,4,4);if(a[1]!=0)memory.writeLong(a[1],0);result(cpu,2,NO_ERR);return true;} // GetControlFeatures
			// Control data: kept per (control, part, tag) and handed back, as the Appearance CDEFs keep theirs; a tag never set
			// answers errDataNotSupported. Acrobat Reader 5.0's ADM Plugin tore down a push button whose SetControlData
			// ('dflt') failed and later used the freed object.
			case 0x12:{int[] a=pop(cpu,4,2,4,4,4);if(tb.controls.data(a[0])==0){result(cpu,2,ERR_DATA_NOT_SUPPORTED);return true;} // SetControlData(control, part, tag, size, data)
				controlData.put(dataKey(a[0],a[1],a[2]),a[4]==0?new byte[Math.max(0,a[3])]:memory.readBytes(a[4],Math.max(0,a[3])));
				if(a[4]!=0&&a[3]>=4&&(a[2]==TAG_POPUP_MENU||a[2]==TAG_POPUP_OWNED_MENU)){int menu=memory.readLong(a[4]); // the pop-up's own menu changes
					tb.controls.setPopupMenu(a[0],menu,menu==0?0:(short)memory.readWord(memory.readLong(menu)));}
				else if(a[4]!=0&&a[3]>=2&&a[2]==TAG_POPUP_MENU_ID){int id=(short)memory.readWord(a[4]); // kControlPopupButtonMenuIDTag: the menu of that ID
					int menu=trapResult(0xa949,4,id,2);if(menu!=0)tb.controls.setPopupMenu(a[0],menu,id);}
				if(PpcRuntime.TRACE)System.err.println("[ppc]   control data "+Integer.toHexString(a[0])+" '"+AppleEvents68k.fourcc(a[2])+"' = "+java.util.Arrays.toString(controlData.get(dataKey(a[0],a[1],a[2]))));
				result(cpu,2,NO_ERR);return true;}
			case 0x13:{int[] a=pop(cpu,4,2,4,4,4,4);byte[] v=popupMenuData(a[0],a[2]);if(v==null)v=controlData.get(dataKey(a[0],a[1],a[2]));if(v==null&&tb.controls.data(a[0])!=0)v=standardTagDefault(a[2]); // GetControlData(control, part, tag, bufferSize, buffer, *actualSize)
				if(v==null){result(cpu,2,ERR_DATA_NOT_SUPPORTED);return true;}
				if(a[4]!=0)memory.load(a[4],java.util.Arrays.copyOf(v,Math.min(v.length,Math.max(0,a[3]))));if(a[5]!=0)memory.writeLong(a[5],v.length);result(cpu,2,NO_ERR);return true;}
			case 0x14:{int[] a=pop(cpu,4,2,4,4);byte[] v=popupMenuData(a[0],a[2]);if(v==null)v=controlData.get(dataKey(a[0],a[1],a[2]));if(v==null&&tb.controls.data(a[0])!=0)v=standardTagDefault(a[2]); // GetControlDataSize(control, part, tag, *size)
				if(v==null){result(cpu,2,ERR_DATA_NOT_SUPPORTED);return true;}if(a[3]!=0)memory.writeLong(a[3],v.length);result(cpu,2,NO_ERR);return true;}
			case 0x15:{int[] a=pop(cpu,4,4);int parent=superControl(a[0]);if(a[1]!=0)memory.writeLong(a[1],parent);result(cpu,2,parent==0?ERR_NO_ROOT_CONTROL:NO_ERR);return true;} // GetSuperControl
			case 0x16:{int[] a=pop(cpu,4,4);if(a[1]!=0)memory.writeWord(a[1],subControls(a[0]).size());result(cpu,2,NO_ERR);return true;} // CountSubControls
			case 0x17:{int[] a=pop(cpu,4,2,4);java.util.List<Integer> subs=subControls(a[0]);int i=(short)a[1]; // GetIndexedSubControl(control, index (1-based), *sub)
				if(i<1||i>subs.size()){if(a[2]!=0)memory.writeLong(a[2],0);result(cpu,2,-50);return true;}if(a[2]!=0)memory.writeLong(a[2],subs.get(i-1));result(cpu,2,NO_ERR);return true;}
			case 0x18:{int[] a=pop(cpu,4);int saved=tb.quickDraw.controlPort; // DrawControlInCurrentPort: Draw1Control into the current port
				tb.quickDraw.controlPort=tb.api.quickDraw().currentPortAddress();
				try{trap(0xa96d,a[0],4);}finally{tb.quickDraw.controlPort=saved;}return true;}
			case 0x1a:{pop(cpu,4,4);result(cpu,2,NO_ERR);return true;} // SetControlSupervisor
			case 0x1b:{int[] a=pop(cpu,4,4,4); // GetBestControlRect(control, *rect, *baseline)
				int d=tb.controls.data(a[0]);if(d!=0&&a[1]!=0)memory.copy(d+8,a[1],8);if(a[2]!=0)memory.writeWord(a[2],0);result(cpu,2,NO_ERR);return true;}
			case 0x1c:{pop(cpu,4,4);result(cpu,2,NO_ERR);return true;} // SetControlFontStyle
			case 0x1d:{pop(cpu,4,2,2);result(cpu,2,NO_ERR);return true;} // SetUpControlBackground
			case 0x1e:{int[] a=pop(cpu,4,2,2); // SetControlVisibility(control, visible, draw): Booleans as words
				int d=tb.controls.data(a[0]);
				if(d!=0){boolean visible=(a[1]&0xff00)!=0;if((a[2]&0xff00)!=0)trap(visible?0xa957:0xa958,a[0],4);else memory.writeByte(d+16,visible?0xff:0);}
				result(cpu,2,NO_ERR);return true;}
			case 0xfffe:{pop(cpu,4,2,4);result(cpu,4,0);return true;} // SendControlMessage
			case 0xffff:{pop(cpu,4,4);result(cpu,2,NO_ERR);return true;} // DumpControlHierarchy
			default:return false;
		}
	}

	private boolean appearance(M68kCpu cpu,int selector) throws M68kException {
		switch(selector){
			case 0x15:case 0x16:result(cpu,4,NO_ERR);return true; // Register/UnregisterAppearanceClient
			case 0xffff:{pop(cpu,4);tb.writeBoolean(cpu.a[7],true);return true;} // IsAppearanceClient
			case 0x01:case 0x02:case 0x03:{int[] a=pop(cpu,2,2,2);int[] rgb=selector==3?textColor(a[0]):brush(a[0]);color(rgb,selector!=2);result(cpu,4,NO_ERR);return true;} // SetThemePen/Background/TextColor
			case 0x04:{int[] a=pop(cpu,4,2,2);int w=a[0];int[] rgb=brush(a[1]); // SetThemeWindowBackground(window, brush, update)
				int old=currentPort();trap(0xa873,w,4);color(rgb,false);if((a[2]&0xff00)!=0)trap(0xa928,w+16,4);trap(0xa873,old,4);
				tb.windows.setThemeBackground(w,brushRgb(a[1]));result(cpu,4,NO_ERR);return true;}
			case 0x05:case 0x06:{int[] a=pop(cpu,4,4);fillFrame(a[0],0xeeee,0x8888);result(cpu,4,NO_ERR);return true;} // DrawThemeWindowHeader / ListViewHeader
			case 0x07:{int[] a=pop(cpu,4,4);fillFrame(a[0],0xdddd,0x8888);result(cpu,4,NO_ERR);return true;} // DrawThemePlacard
			case 0x08:case 0x09:case 0x0a:case 0x22:{int[] a=selector==0x22?pop(cpu,4,4,2):pop(cpu,4,4);frame(a[0],0x7777,selector==9||selector==0xa?-1:0);result(cpu,4,NO_ERR);return true;} // frames: modeless dialog, edit text, list box, generic well
			case 0x0b:{int[] a=pop(cpu,4,2);if((a[1]&0xff00)!=0){frame(a[0],0x6666,-2);frame(a[0],0x6666,-1);}result(cpu,4,NO_ERR);return true;} // DrawThemeFocusRect
			case 0x23:{pop(cpu,4,2);result(cpu,4,NO_ERR);return true;} // DrawThemeFocusRegion
			case 0x0c:case 0x0d:{int[] a=pop(cpu,4,4);frame(a[0],0x9999,0);result(cpu,4,NO_ERR);return true;} // Primary/SecondaryGroup
			case 0x0e:{int[] a=pop(cpu,4,4);separator(a[0]);result(cpu,4,NO_ERR);return true;} // DrawThemeSeparator
			case 0x0f:case 0x10:{pop(cpu,4);tb.writeBoolean(cpu.a[7],false);return true;} // IsWindowCollapsable / IsWindowCollapsed
			case 0x11:{pop(cpu,4,2);result(cpu,4,NO_ERR);return true;} // CollapseWindow
			case 0x12:{pop(cpu,2);result(cpu,4,NO_ERR);return true;} // CollapseAllWindows
			case 0x13:{int[] a=pop(cpu,4,4);if(a[1]!=0)memory.writeLong(a[1],0);result(cpu,4,NO_ERR);return true;} // GetWindowFeatures
			case 0x14:{int[] a=pop(cpu,4,2,4); // GetWindowRegion(window, code, rgn)
				int code=a[1]&0xffff;int rgn=memory.readLong(a[0]+(code==33?118:114));if(rgn!=0&&a[2]!=0)trap(0xa8dc,rgn,4,a[2],4);result(cpu,4,NO_ERR);return true;}
			case 0x1a:{int[] a=pop(cpu,4);if(a[0]!=0)memory.writeWord(a[0],MacAppHost.MENU_BAR_HEIGHT);result(cpu,4,NO_ERR);return true;} // GetThemeMenuBarHeight
			case 0x1f:{int[] a=pop(cpu,4);if(a[0]!=0)memory.writeWord(a[0],9);result(cpu,4,NO_ERR);return true;} // GetThemeMenuSeparatorHeight
			case 0x18:{int[] a=pop(cpu,4,2,4);if(a[0]!=0){int[] r=rect(a[0]);paint(r,g->Platinum.menuBar(g,r));}result(cpu,4,NO_ERR);return true;} // DrawThemeMenuBarBackground(rect, state, attributes)
			case 0x19:{int[] a=pop(cpu,4,4,2,4,4,4); // DrawThemeMenuTitle(menuBarRect, titleRect, state, attributes, titleProc, titleData)
				if(a[1]!=0){int[] r=rect(a[1]);int state=a[2]&0xffff;paint(r,g->Platinum.menuTitle(g,r,state));drawCallback(a[4],a[1],a[5],state);}
				result(cpu,4,NO_ERR);return true;}
			case 0x1b:{int[] a=pop(cpu,4,2);if(a[0]!=0){int[] r=rect(a[0]);paint(r,g->Platinum.menuBackground(g,r));}result(cpu,4,NO_ERR);return true;} // DrawThemeMenuBackground(rect, menuType)
			case 0x1c:{int[] a=pop(cpu,4,2,4);if(a[0]!=0&&a[2]!=0){int[] r=rect(a[0]);tb.api.quickDraw().setRegion(a[2],QdRegion.rect(r[0],r[1],r[2],r[3]));}result(cpu,4,NO_ERR);return true;} // GetThemeMenuBackgroundRegion
			case 0x1d:{int[] a=pop(cpu,4,4,2,2,2,2,4,4); // DrawThemeMenuItem(menuRect, itemRect, virtualTop, virtualBottom, state, itemType, proc, data)
				if(a[1]!=0){int[] r=rect(a[1]);int state=a[4]&0xffff,type=a[5]&0xffff;paint(r,g->Platinum.menuItem(g,r,state,type));drawCallback(a[6],a[1],a[7],state);}
				result(cpu,4,NO_ERR);return true;}
			case 0x1e:{int[] a=pop(cpu,4);if(a[0]!=0){int[] r=rect(a[0]);paint(r,g->Platinum.menuSeparator(g,r));}result(cpu,4,NO_ERR);return true;} // DrawThemeMenuSeparator
			case 0x20:{int[] a=pop(cpu,2,4,4);if(a[1]!=0)memory.writeWord(a[1],0);if(a[2]!=0)memory.writeWord(a[2],0);result(cpu,4,NO_ERR);return true;} // GetThemeMenuItemExtra: no extra room around items
			case 0x21:{int[] a=pop(cpu,4,2);if(a[0]!=0)memory.writeWord(a[0],(a[1]&0xff00)!=0?8:12);result(cpu,4,NO_ERR);return true;} // GetThemeMenuTitleExtra(*width, squished)
			case 0x25:{int[] a=pop(cpu,4);if(a[0]!=0)memory.writeLong(a[0],0);result(cpu,4,THEME_HAS_NO_ACCENTS_ERR);return true;} // GetThemeAccentColors
			case 0x24:{pop(cpu,2,2);tb.writeBoolean(cpu.a[7],true);return true;} // IsThemeInColor
			case 0x26:{pop(cpu,4);result(cpu,4,NO_ERR);return true;} // PlayThemeSound
			case 0x27:{pop(cpu,4);result(cpu,4,NO_ERR);return true;} // BeginThemeDragSound
			case 0x28:result(cpu,4,NO_ERR);return true; // EndThemeDragSound
			default:return false;
		}
	}

	/** Platinum colours (RGB) of the theme brushes 1..51 (Appearance.h kThemeBrush...): light grey dialog, alert, utility
	 *  and control faces; white document and list backgrounds; black frames; the lavender focus and scroll accents. */
	private static final int[] BRUSHES={0,
		0xdddddd,0xdddddd,0xdddddd,0xdddddd,0xdddddd,0xdddddd,0xdddddd,0xdddddd, // dialog, alert, modeless, utility backgrounds (active, inactive)
		0xeeeeee,0xffffff,0xffffff,0xdddddd,0x000000,0x999999,0xffffff,0xffffff, // list sort column, list, icon label, separator, chasing arrows, drag hilite, document, Finder window
		0x000000,0x888888,0x9999ff,0x000000,0xffffff,0x888888,0xff6600,0x000000, // scroll bar delimiters, focus, popup arrow (active, pressed, inactive), coachmark, selected label
		0xdddddd,0xffffff,0x000000,0x888888,0xdddddd,0xeeeeee,0x777777, // static/active area fill, button frame (active, inactive), face (active, inactive, pressed)
		0x888888,0xffffff,0xaaaaaa,0xeeeeee,0xaaaaaa,0xffffff,0xcccccc,0xeeeeee,0x444444,0x999999,0x555555,0x888888, // button shadows/highlights: active, inactive, pressed
		0xffffff,0x888888,0xeeeeee,0xaaaaaa,0xffffcc,0xdddddd,0xdddddd,0xdddddd}; // bevels, notification, movable modal, sheet, drawer
	/** Platinum colour of a theme brush as 16-bit RGB (kThemeBrushBlack -1, kThemeBrushWhite -2). */
	static int[] brush(int b){int v=brushRgb(b);return new int[]{(v>>>16&255)*0x101,(v>>>8&255)*0x101,(v&255)*0x101};}
	static int brushRgb(int b){return b==-1?0:b==-2?0xffffff:b>0&&b<BRUSHES.length?BRUSHES[b]:0xdddddd;}
	static boolean validBrush(int b){return b==-1||b==-2||b>0&&b<BRUSHES.length;}
	/** Theme text colours (Appearance.h kThemeTextColor*): black for active text, grey for inactive, white on the Platinum
	 *  selections and pressed buttons. The constants do not alternate active/inactive past 10 - list views (22) were grey,
	 *  and Sherlock 2 showed its volume list in grey on grey. */
	static int[] textColor(int t){
		switch(t){
			case -2:case 14:case 20:case 32:case 35:case 44:return new int[]{0xffff,0xffff,0xffff};
			case 2:case 4:case 6:case 8:case 10:case 13:case 16:case 19:case 24:case 26:case 28:case 30:case 33:case 36:case 38:case 42:case 43:case 46:
				return new int[]{0x7777,0x7777,0x7777};
			default:return new int[]{0,0,0};
		}
	}

	// ------------------------------------------------------------------ QuickDraw through the traps

	private int scratch;
	private int scratch() throws M68kCpuException {if(scratch==0)scratch=memory.allocate(32,4);return scratch;}
	private void color(int[] rgb,boolean fore) throws M68kException {int s=scratch();memory.writeWord(s,rgb[0]);memory.writeWord(s+2,rgb[1]);memory.writeWord(s+4,rgb[2]);trap(fore?0xaa14:0xaa15,s,4);}
	private int currentPort() throws M68kException {int s=scratch()+8;trap(0xa874,s,4);return memory.readLong(s);}
	/** Fills a rectangle and frames it, keeping the port's colours. */
	private void fillFrame(int rect,int fill,int edge) throws M68kException {
		int port=currentPort();int fore=port+36,back=port+42;byte[] saved=memory.readBytes(fore,12);
		try{color(new int[]{fill,fill,fill},true);trap(0xa8a2,rect,4);color(new int[]{edge,edge,edge},true);trap(0xa8a1,rect,4);}
		finally{memory.load(fore,saved);restoreColors(port);}
	}
	/** Frames a rectangle grown by {@code outset} pixels in a grey. */
	private void frame(int rect,int grey,int outset) throws M68kException {
		int port=currentPort();byte[] saved=memory.readBytes(port+36,12);
		int r=scratch()+16;memory.copy(rect,r,8);
		if(outset!=0){memory.writeWord(r,(short)memory.readWord(r)+outset);memory.writeWord(r+2,(short)memory.readWord(r+2)+outset);memory.writeWord(r+4,(short)memory.readWord(r+4)-outset);memory.writeWord(r+6,(short)memory.readWord(r+6)-outset);}
		try{color(new int[]{grey,grey,grey},true);trap(0xa8a1,r,4);}finally{memory.load(port+36,saved);restoreColors(port);}
	}
	private void separator(int rect) throws M68kException {
		int port=currentPort();byte[] saved=memory.readBytes(port+36,12);
		int top=(short)memory.readWord(rect),left=(short)memory.readWord(rect+2),bottom=(short)memory.readWord(rect+4),right=(short)memory.readWord(rect+6);
		try{color(new int[]{0x8888,0x8888,0x8888},true);
			if(right-left>=bottom-top){trap(0xa893,left,2,top,2);trap(0xa891,right-1,2,top,2);}else{trap(0xa893,left,2,top,2);trap(0xa891,left,2,bottom-1,2);}}
		finally{memory.load(port+36,saved);restoreColors(port);}
	}
	/** Re-applies the port's saved RGB colours (they were restored in the record; QuickDraw's pen follows them). */
	private void restoreColors(int port) throws M68kException {
		int s=scratch();memory.copy(port+36,s,6);trap(0xaa14,s,4);memory.copy(port+42,s,6);trap(0xaa15,s,4);
	}

	private int stub;
	/** Runs a Toolbox trap from Java with Pascal arguments given as value,size pairs (pushed left to right). */
	private void trap(int word,int... valueSize) throws M68kException {trapResult(word,0,valueSize);}
	private int trapResult(int word,int resultBytes,int... valueSize) throws M68kException {
		M68kCpu cpu=tb.cpu;
		if(stub==0)stub=memory.allocate(64,4);
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sp=cpu.a[7];
		try{
			int at=stub+((word&0xff)%8)*8; // a few slots, so a nested call does not overwrite a running stub
			memory.writeWord(at,word);memory.writeWord(at+2,0x4ef9);memory.writeLong(at+4,M68kCpu.RETURN_SENTINEL);
			if(resultBytes>0){cpu.a[7]-=resultBytes;memory.fill(cpu.a[7],resultBytes,0);}
			int slot=cpu.a[7];int bytes=0;
			for(int i=0;i<valueSize.length;i+=2){int v=valueSize[i],size=valueSize[i+1];cpu.a[7]-=size;bytes+=size;if(size==4)memory.writeLong(cpu.a[7],v);else memory.writeWord(cpu.a[7],v);}
			cpu.runUntilSentinel(at,bytes);
			return resultBytes==2?(short)memory.readWord(slot):resultBytes==4?memory.readLong(slot):0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=sp;}
	}

	// ------------------------------------------------------------------ drawing theme pieces into the current port

	/** Java2D drawing in port-local coordinates. */
	interface Painter{void paint(java.awt.Graphics2D g);}
	/** Paints into an image over the local rectangle and blits it into the current port; transparent pixels leave the port alone. */
	void paint(int top,int left,int bottom,int right,Painter p) throws M68kException {
		int w=right-left,h=bottom-top;if(w<=0||h<=0||(long)w*h>16_777_216L)return;
		java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g=img.createGraphics();
		try{g.translate(-left,-top);p.paint(g);}finally{g.dispose();}
		tb.quickDraw.drawImage(img,top,left,bottom,right);tb.quickDraw.flushSurfaces();
	}
	void paint(int[] r,Painter p) throws M68kException {paint(r[0],r[1],r[2],r[3],p);}
	int[] rect(int p) throws M68kCpuException {return new int[]{(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};}
	/** The current port's pixel depth (1 for a basic GrafPort), as the Appearance Manager hands it to drawing callbacks. */
	int depth() throws M68kCpuException {
		int port=tb.api.currentQuickDrawPort();if(port==0)return 1;
		if((memory.readWord(port+6)&0xc000)!=0xc000)return 1;
		int pm=memory.readLong(port+2);int p=pm==0?0:memory.readLong(pm);return p==0?1:Math.max(1,(short)memory.readWord(p+32));
	}
	/** A menu title/item drawing proc (MenuTitleDrawingUPP: bounds, depth, isColorDevice, userData) called with the theme's text colour set: white on the selection, grey when disabled. */
	private void drawCallback(int proc,int bounds,int userData,int state) throws M68kException {
		if(proc==0)return;
		int port=currentPort();byte[] saved=port==0||(memory.readWord(port+6)&0xc000)!=0xc000?null:memory.readBytes(port+36,6); // a CGrafPort's rgbFgColor
		try{if(saved!=null){if(state==1)color(new int[]{0xffff,0xffff,0xffff},true);else if(state==3)color(textColor(2),true);}
			callProc(proc,bounds,4,depth(),2,1,1,userData,4);}
		finally{if(saved!=null){memory.load(port+36,saved);restoreColors(port);}}
	}
	/** Calls a program's Pascal procedure (a 68K ProcPtr or a routine descriptor) with value,size argument pairs (size 1: a Boolean). */
	void callProc(int proc,int... valueSize) throws M68kException {
		M68kCpu cpu=tb.cpu;int[] regs=cpu.a.clone(),dr=cpu.d.clone();int saved=cpu.a[7];int bytes=0;
		try{
			for(int i=0;i+1<valueSize.length;i+=2){int v=valueSize[i],size=valueSize[i+1];
				if(size==4){cpu.pushLong(v);bytes+=4;}else{cpu.a[7]-=2;memory.writeWord(cpu.a[7],size==1?(v&0xff)<<8:v);bytes+=2;}}
			cpu.callPascal(proc,bytes);
		}finally{cpu.a[7]=saved;System.arraycopy(dr,0,cpu.d,0,8);System.arraycopy(regs,0,cpu.a,0,8);}
	}

	/** Name, size and style of a theme font (Appearance 1.1 Platinum; the Japanese system font is Osaka), as PpcCarbon's GetThemeFont answers. */
	static String[] themeFont(int id,boolean japanese){
		String system=japanese?"Osaka":"Charcoal",small=japanese?"Osaka":"Geneva";
		switch(id){
			case 1:case 6:return new String[]{small,"10","0"};           // small system, label
			case 2:return new String[]{small,"10","1"};                   // small emphasized
			case 3:return new String[]{small,japanese?"12":"10","0"};     // views
			case 4:case 107:return new String[]{system,"12","1"};          // emphasized system, alert header
			case 5:return new String[]{japanese?"Osaka":"Geneva","12","0"};// application
			case 106:return new String[]{small,"9","0"};                  // utility window title
			default:return new String[]{system,"12","0"};                 // system, menus, window titles, push buttons
		}
	}

	/**
	 * The Platinum look (Mac OS 8.5 - 9 default theme) of the Appearance Manager's primitives, drawn with Java2D in
	 * port-local coordinates; rectangles are {top, left, bottom, right}. Geometry helpers (content and background bounds,
	 * track and window parts) answer what the drawing uses, so hit tests match the pictures.
	 */
	static final class Platinum {
		private Platinum(){}
		static final int BLACK=0,WHITE=0xffffff,FACE=0xdddddd,LIGHT=0xeeeeee,SHADOW=0x888888,DARK=0x555555,GREY=0x999999,MID=0xcccccc,
			PRESSED=0x777777,ACCENT=0xccccff,ACCENT_SHADOW=0x8888bb,ACCENT_DARK=0x444477,FOCUS=0x6666cc,PROGRESS=0x5555cc,PROGRESS_LIGHT=0x9999ee,HILITE=0x3333aa;
		static java.awt.Color c(int rgb){return new java.awt.Color(rgb);}
		static void fill(java.awt.Graphics2D g,int t,int l,int b,int r,int rgb){if(r>l&&b>t){g.setColor(c(rgb));g.fillRect(l,t,r-l,b-t);}}
		static void frame(java.awt.Graphics2D g,int t,int l,int b,int r,int rgb){if(r>l&&b>t){g.setColor(c(rgb));g.drawRect(l,t,r-l-1,b-t-1);}}
		static void line(java.awt.Graphics2D g,int x1,int y1,int x2,int y2,int rgb){g.setColor(c(rgb));g.drawLine(x1,y1,x2,y2);}
		/** A 1-pixel bevel just inside the rectangle: top and left edges {@code light}, bottom and right {@code dark}. */
		static void bevel(java.awt.Graphics2D g,int t,int l,int b,int r,int light,int dark){
			if(r-l<2||b-t<2)return;line(g,l,t,r-2,t,light);line(g,l,t,l,b-2,light);line(g,l+1,b-1,r-1,b-1,dark);line(g,r-1,t+1,r-1,b-1,dark);
		}
		/** A filled triangle pointing left (0), down (1), right (2) or up (3), {@code h} pixels from base to tip, centred on (cx, cy). */
		static void arrow(java.awt.Graphics2D g,int cx,int cy,int h,int orientation,int rgb){
			g.setColor(c(rgb));int o=(h-1)/2;
			for(int i=0;i<h;i++){int half=h-1-i;
				switch(orientation&3){
					case 1:g.drawLine(cx-half,cy-o+i,cx+half,cy-o+i);break;         // down: the base on top
					case 3:g.drawLine(cx-half,cy-o+h-1-i,cx+half,cy-o+h-1-i);break; // up
					case 0:g.drawLine(cx-o+h-1-i,cy-half,cx-o+h-1-i,cy+half);break; // left
					default:g.drawLine(cx-o+i,cy-half,cx-o+i,cy+half);break;         // right
				}
			}
		}
		static boolean disabled(int state){return state==0||state==7||state==8;}
		static boolean pressed(int state){return state==2||state==3;}
		static int[] inset(int[] r,int dv,int dh){return new int[]{r[0]+dv,r[1]+dh,r[2]-dv,r[3]-dh};}

		// ---- buttons (ThemeButtonKind)

		static int boxSize(int kind){return kind==13||kind==14?10:12;}
		static int bevelWidth(int kind){return kind==8?1:kind==9?3:2;}
		/** GetThemeButtonBackgroundBounds: what the button covers (a default push button's ring and a focus ring lie outside). */
		static int[] background(int[] r,int kind,int adornment){
			int o=0;if(kind==0&&(adornment&1)!=0)o=4;if((adornment&4)!=0)o=Math.max(o,3);
			return inset(r,-o,-o);
		}
		/** GetThemeButtonContentBounds: where a label proc draws. */
		static int[] content(int[] r,int kind,int value,int adornment){
			switch(kind){
				case 0:return new int[]{r[0]+2,r[1]+4,r[2]-2,r[3]-4};
				case 1:case 2:case 13:case 14:{int s=boxSize(kind);return (adornment&0x10)!=0?new int[]{r[0],r[1],r[2],r[3]-s-5}:new int[]{r[0],r[1]+s+5,r[2],r[3]};} // right-to-left: the box on the right
				case 5:return new int[]{r[0]+2,r[1]+7,r[2]-2,r[3]-22};
				case 3:case 8:case 9:case 15:{int w=bevelWidth(kind)+1;return inset(r,w,w);}
				case 10:return new int[]{r[0]+1,r[1]+4,r[2]-1,r[3]-(value!=0?16:4)};
				default:return inset(r,2,2); // arrow, disclosure, little arrows, round buttons
			}
		}
		/** The button's own shape as a picture mask test: round and radio buttons are circles, the others their background rectangle. */
		static void button(java.awt.Graphics2D g,int[] r,int kind,int state,int value,int adornment){
			int t=r[0],l=r[1],b=r[2],ri=r[3],w=ri-l,h=b-t;boolean off=disabled(state),down=pressed(state);int ink=off?SHADOW:BLACK;
			if(w<=0||h<=0)return;
			switch(kind){
				case 0:{ // push button: rounded, a light face with a bevel; pressed dark; the default ring outside
					int arc=Math.min(h,10);
					if((adornment&1)!=0){g.setColor(c(off?LIGHT:MID));g.fillRoundRect(l-4,t-4,w+8,h+8,arc+8,arc+8);g.setColor(c(ink));g.drawRoundRect(l-4,t-4,w+7,h+7,arc+8,arc+8);
						g.setColor(c(WHITE));g.drawLine(l-1,t-3,ri,t-3);}
					g.setColor(c(down?PRESSED:off?LIGHT:FACE));g.fillRoundRect(l,t,w,h,arc,arc);
					if(!down&&!off){line(g,l+3,t+1,ri-4,t+1,WHITE);line(g,l+1,t+3,l+1,b-4,WHITE);line(g,l+3,b-2,ri-4,b-2,SHADOW);line(g,ri-2,t+3,ri-2,b-4,SHADOW);}
					g.setColor(c(ink));g.drawRoundRect(l,t,w-1,h-1,arc,arc);
					break;}
				case 1:case 13:{ // check box: a sunken white box; a check mark or a dash (mixed)
					int s=boxSize(kind),x=(adornment&0x10)!=0?ri-s:l,y=t+(h-s)/2;
					fill(g,y,x,y+s,x+s,down?GREY:off?LIGHT:WHITE);bevel(g,y+1,x+1,y+s-1,x+s-1,down?SHADOW:MID,down?GREY:WHITE);frame(g,y,x,y+s,x+s,ink);
					g.setColor(c(off?SHADOW:BLACK));
					if(value==1){g.setStroke(new java.awt.BasicStroke(2f));g.drawLine(x+3,y+s/2,x+s/2-1,y+s-3);g.drawLine(x+s/2-1,y+s-3,x+s-2,y+2);g.setStroke(new java.awt.BasicStroke(1f));}
					else if(value==2)g.fillRect(x+3,y+s/2-1,s-6,2);
					break;}
				case 2:case 14:{ // radio button
					int s=boxSize(kind),x=(adornment&0x10)!=0?ri-s:l,y=t+(h-s)/2;
					g.setColor(c(down?GREY:off?LIGHT:WHITE));g.fillOval(x,y,s,s);g.setColor(c(ink));g.drawOval(x,y,s-1,s-1);
					g.setColor(c(off?SHADOW:BLACK));
					if(value==1)g.fillOval(x+3,y+3,s-6,s-6);else if(value==2)g.fillRect(x+3,y+s/2-1,s-6,2);
					break;}
				case 3:case 8:case 9:case 15:{ // bevel buttons: on (value 1) or pressed look sunk in
					boolean in=down||value==1;int bw=bevelWidth(kind);
					if(kind==15){g.setColor(c(in?GREY:value==2?MID:off?LIGHT:FACE));g.fillRoundRect(l,t,w,h,8,8);g.setColor(c(ink));g.drawRoundRect(l,t,w-1,h-1,8,8);}
					else{fill(g,t,l,b,ri,in?GREY:value==2?MID:off?LIGHT:FACE);frame(g,t,l,b,ri,ink);}
					if(!off)for(int i=1;i<=bw;i++)bevel(g,t+i,l+i,b-i,ri-i,in?SHADOW:WHITE,in?MID:SHADOW);
					break;}
				case 4:{ // arrow button (a pop-up arrow without text)
					fill(g,t,l,b,ri,down?GREY:off?LIGHT:FACE);frame(g,t,l,b,ri,ink);if(!off&&!down)bevel(g,t+1,l+1,b-1,ri-1,WHITE,SHADOW);
					int cx=l+w/2,cy=t+h/2,col=off?SHADOW:BLACK;
					if((adornment&0x100)!=0){arrow(g,cx,cy-3,3,3,col);arrow(g,cx,cy+3,3,1,col);}
					else arrow(g,cx,cy,4,(adornment&0x40)!=0?0:(adornment&0x200)!=0?3:1,col);
					break;}
				case 5:{ // pop-up button: rounded, the arrow part at the right
					int arc=Math.min(h,8);g.setColor(c(down?PRESSED:off?LIGHT:FACE));g.fillRoundRect(l,t,w,h,arc,arc);
					if(!down&&!off){line(g,l+2,t+1,ri-3,t+1,WHITE);line(g,l+1,t+2,l+1,b-3,WHITE);}
					g.setColor(c(ink));g.drawRoundRect(l,t,w-1,h-1,arc,arc);
					int ax=ri-11,cy=t+h/2,col=down?WHITE:off?SHADOW:BLACK;line(g,ri-20,t+3,ri-20,b-4,off?GREY:SHADOW);
					arrow(g,ax,cy-3,3,3,col);arrow(g,ax,cy+3,3,1,col);
					break;}
				case 6:{ // disclosure triangle: right (0), down (1), left (2)
					int cx=l+w/2,cy=t+h/2;int orientation=value==1?1:value==2?0:2;
					arrow(g,cx+(orientation==2?1:orientation==0?-1:0),cy+(orientation==1?1:0),6,orientation,off?GREY:down?HILITE:BLACK);
					break;}
				case 7:{ // little arrows: two halves, pressed up (2) or down (3)
					int mid=t+h/2;fill(g,t,l,mid,ri,state==2?GREY:off?LIGHT:FACE);fill(g,mid,l,b,ri,state==3?GREY:off?LIGHT:FACE);
					g.setColor(c(ink));g.drawRoundRect(l,t,w-1,h-1,6,6);line(g,l,mid,ri-1,mid,ink);
					int col=off?SHADOW:BLACK;arrow(g,l+w/2,t+(mid-t)/2,3,3,col);arrow(g,l+w/2,mid+(b-mid)/2,3,1,col);
					break;}
				case 10:{ // list header: the sort column darker, with its direction
					fill(g,t,l,b,ri,down?GREY:value!=0?MID:FACE);bevel(g,t,l,b-1,ri,WHITE,SHADOW);line(g,l,b-1,ri-1,b-1,ink);
					if(value!=0)arrow(g,ri-9,t+h/2,4,(adornment&0x100)!=0?3:1,off?SHADOW:BLACK);
					break;}
				case 11:case 12:{ // round buttons
					int s=Math.min(w,h),x=l+(w-s)/2,y=t+(h-s)/2;
					g.setColor(c(down?GREY:off?LIGHT:FACE));g.fillOval(x,y,s,s);if(!down&&!off){g.setColor(c(WHITE));g.drawArc(x+1,y+1,s-3,s-3,90,90);}
					g.setColor(c(ink));g.drawOval(x,y,s-1,s-1);break;}
				default:fill(g,t,l,b,ri,FACE);frame(g,t,l,b,ri,ink);break;
			}
			if((adornment&4)!=0&&!off){g.setColor(c(FOCUS));g.setStroke(new java.awt.BasicStroke(2f));
				if(kind==0)g.drawRoundRect(l-2,t-2,w+3,h+3,12,12);else g.drawRect(l-2,t-2,w+3,h+3);g.setStroke(new java.awt.BasicStroke(1f));}
		}

		// ---- tabs

		/** The tab's label rectangle (what a ThemeTabTitleDrawUPP gets). */
		static int[] tabLabel(int[] r,int direction){
			switch(direction){case 1:return new int[]{r[0]+3,r[1]+6,r[2]-2,r[3]-6};case 2:return new int[]{r[0]+6,r[1]+3,r[2]-6,r[3]-2};case 3:return new int[]{r[0]+6,r[1]+2,r[2]-6,r[3]-3};default:return new int[]{r[0]+2,r[1]+6,r[2]-3,r[3]-6};}
		}
		/** A tab (ThemeTabStyle) drawn as a north tab and turned to face south, east or west. */
		static void tab(java.awt.Graphics2D g,int[] r,int style,int direction){
			boolean side=direction==2||direction==3;int w=side?r[2]-r[0]:r[3]-r[1],h=side?r[3]-r[1]:r[2]-r[0];
			if(w<=8||h<=2)return;
			java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D t=img.createGraphics();try{northTab(t,w,h,style);}finally{t.dispose();}
			java.awt.geom.AffineTransform at;
			switch(direction){
				case 1:at=new java.awt.geom.AffineTransform(1,0,0,-1,r[1],r[2]);break;   // south: flipped
				case 2:at=new java.awt.geom.AffineTransform(0,1,-1,0,r[3],r[0]);break;   // east: the tab's top at the right
				case 3:at=new java.awt.geom.AffineTransform(0,1,1,0,r[1],r[0]);break;    // west
				default:at=java.awt.geom.AffineTransform.getTranslateInstance(r[1],r[0]);break;
			}
			g.drawImage(img,at,null);
		}
		private static void northTab(java.awt.Graphics2D g,int w,int h,int style){
			boolean front=style==3||style==4||style==6,off=style==2||style==4||style==5||style==6,pressed=style==1;
			int face=front?LIGHT:pressed?GREY:off?LIGHT:MID,ink=off?SHADOW:BLACK;
			int bottom=front?h:Math.max(5,h-3); // a tab behind stops at the pane's edge (kThemeTabPaneOverlap); the front one covers it
			java.awt.Polygon p=new java.awt.Polygon(new int[]{0,0,4,w-5,w-1,w-1},new int[]{bottom,4,0,0,4,bottom},6);
			g.setColor(c(face));g.fillPolygon(p);
			g.setColor(c(ink));g.drawLine(0,4,0,bottom-1);g.drawLine(0,4,4,0);g.drawLine(4,0,w-5,0);g.drawLine(w-5,0,w-1,4);g.drawLine(w-1,4,w-1,bottom-1);
			if(!off||front){line(g,1,4,1,bottom-1,WHITE);line(g,2,3,4,1,WHITE);line(g,5,1,w-6,1,WHITE);line(g,w-2,5,w-2,bottom-1,front?MID:SHADOW);}
			if(front){line(g,0,h-1,0,h-1,ink);line(g,w-1,h-1,w-1,h-1,ink);}
		}
		static void tabPane(java.awt.Graphics2D g,int[] r,int state){
			fill(g,r[0],r[1],r[2],r[3],LIGHT);frame(g,r[0],r[1],r[2],r[3],disabled(state)?SHADOW:BLACK);bevel(g,r[0]+1,r[1]+1,r[2]-1,r[3]-1,WHITE,disabled(state)?LIGHT:0xaaaaaa);
		}

		// ---- tracks (ThemeTrackDrawInfo)

		/** A ThemeTrackDrawInfo: kind, bounds, min, max, value, attributes, enableState and the kind's track info. */
		static final class Track {
			int kind,top,left,bottom,right,min,max,value,attributes,enable,press,viewSize,thumbDir,phase;
			static Track read(M68kMemory m,int p) throws M68kCpuException {
				Track t=new Track();t.kind=m.readWord(p);t.top=(short)m.readWord(p+2);t.left=(short)m.readWord(p+4);t.bottom=(short)m.readWord(p+6);t.right=(short)m.readWord(p+8);
				t.min=m.readLong(p+10);t.max=m.readLong(p+14);t.value=m.readLong(p+18);t.attributes=m.readWord(p+26);t.enable=m.readByte(p+28);
				if(t.scrollBar()){t.viewSize=m.readLong(p+30);t.press=m.readByte(p+34);}else if(t.slider()){t.thumbDir=m.readByte(p+30);t.press=m.readByte(p+31);}else t.phase=m.readByte(p+30);
				return t;
			}
			boolean horizontal(){return (attributes&1)!=0;}
			boolean rightToLeft(){return (attributes&2)!=0;}
			boolean scrollBar(){return kind==0||kind==1;}
			boolean slider(){return kind==2||kind==6;}
			boolean active(){return enable==0;}
			int start(){return horizontal()?left:top;}
			int end(){return horizontal()?right:bottom;}
			int thickness(){return horizontal()?bottom-top:right-left;}
			/** Scroll bars: an arrow box at each end, as long as the bar is thick (none with kThemeTrackNoScrollBarArrows). */
			int arrow(){return !scrollBar()||(attributes&0x10)!=0?0:Math.max(0,Math.min(thickness(),(end()-start())/2));}
			int trackStart(){return start()+arrow();}
			int trackEnd(){return end()-arrow();}
			int thumbLength(){int room=Math.max(0,trackEnd()-trackStart());return scrollBar()?Math.min(thickness(),room):slider()?Math.min(kind==6?9:11,room):0;}
			boolean hasThumb(){return (attributes&4)!=0&&max>min&&thumbLength()>0&&(slider()||active());}
			/** Pixels the thumb can travel. */
			int range(){return Math.max(0,trackEnd()-trackStart()-thumbLength());}
			int clamp(int v){return Math.max(min,Math.min(max,v));}
			/** The thumb's start for a value (a right-to-left track runs the other way). */
			int thumbStart(int v){
				long pos=max<=min?0:(long)(clamp(v)-(long)min)*range()/((long)max-min);
				return rightToLeft()?trackEnd()-thumbLength()-(int)pos:trackStart()+(int)pos;
			}
			int[] along(int s,int e){return horizontal()?new int[]{top,s,bottom,e}:new int[]{s,left,e,right};}
			int[] thumb(){int s=thumbStart(value);return along(s,s+thumbLength());}
			/** GetThemeTrackBounds: the part between the arrows (a slider's whole length, a progress bar's inside). */
			int[] trackBounds(){return scrollBar()?along(trackStart(),trackEnd()):slider()?along(start(),end()):new int[]{top+1,left+1,bottom-1,right-1};}
			/** GetThemeTrackDragRect: where the thumb can be dragged. */
			int[] dragRect(){return along(trackStart(),trackEnd());}
			/** Pixels from the track's start to a thumb start (GetThemeTrackThumbPositionFrom...), and back to a value. */
			int relative(int thumbStart){int r=rightToLeft()?trackEnd()-thumbLength()-thumbStart:thumbStart-trackStart();return Math.max(0,Math.min(range(),r));}
			int valueAt(int relative){int r=range();if(r<=0||max<=min)return min;return clamp(min+(int)Math.round((double)Math.max(0,Math.min(r,relative))*((long)max-min)/r));}
			/** HitTestThemeTrack: control part codes (20 up/left arrow, 21 down/right arrow, 22/23 page, 129 thumb), 0 for none. */
			int hit(int v,int h){
				if(v<top||v>=bottom||h<left||h>=right||!active()||(!slider()&&!scrollBar()))return 0;
				int p=horizontal()?h:v;
				if(scrollBar()&&arrow()>0){if(p<start()+arrow())return 20;if(p>=end()-arrow())return 21;}
				if(max<=min)return 0;
				int s=thumbStart(value),e=s+thumbLength();
				if(hasThumb()&&p>=s&&p<e)return 129;
				boolean before=p<s;if(rightToLeft())before=!before;
				return before?22:23;
			}
		}
		/** DrawThemeTrack: scroll bar, slider, progress (determinate, indeterminate) or relevance bar; {@code ghost} is a dragged thumb's outline or null. */
		static void track(java.awt.Graphics2D g,Track k,int[] ghost,boolean ghostOpaque){
			int t=k.top,l=k.left,b=k.bottom,r=k.right;if(r<=l||b<=t)return;
			boolean off=!k.active();int ink=k.enable==3||k.enable==1?SHADOW:BLACK;
			if(k.scrollBar()){
				fill(g,t,l,b,r,off||k.max<=k.min?LIGHT:MID);
				int[] tr=k.trackBounds();if(!off&&k.max>k.min){if(k.horizontal())line(g,tr[1],t+1,tr[3]-1,t+1,SHADOW);else line(g,l+1,tr[0],l+1,tr[2]-1,SHADOW);}
				frame(g,t,l,b,r,ink);
				if(k.arrow()>0)scrollArrows(g,k.along(k.start(),k.end()),k.enable,k.press,k.horizontal());
				if(k.hasThumb()){int[] th=k.thumb();thumb(g,th,(k.press&8)!=0,k.horizontal(),false);}
				if(ghost!=null)thumb(g,ghost,false,k.horizontal(),!ghostOpaque);
			}else if(k.slider()){
				boolean h=k.horizontal();int mid=h?(t+b)/2:(l+r)/2;int[] groove=h?new int[]{mid-2,l,mid+3,r}:new int[]{t,mid-2,b,mid+3};
				if(k.thumbDir==1)groove=h?new int[]{b-7,l,b-2,r}:new int[]{t,r-7,b,r-2};else if(k.thumbDir==2)groove=h?new int[]{t+2,l,t+7,r}:new int[]{t,l+2,b,l+7};
				fill(g,groove[0],groove[1],groove[2],groove[3],off?LIGHT:GREY);frame(g,groove[0],groove[1],groove[2],groove[3],ink);bevel(g,groove[0]+1,groove[1]+1,groove[2]-1,groove[3]-1,off?LIGHT:SHADOW,off?LIGHT:MID);
				if(k.hasThumb())sliderThumb(g,k.thumb(),k.thumbDir,h,(k.press&8)!=0,off);
				if(ghost!=null)sliderThumb(g,ghost,k.thumbDir,h,false,true);
			}else if(k.kind==5){ // relevance: a black bar in a white well
				fill(g,t,l,b,r,WHITE);frame(g,t,l,b,r,ink);
				int filled=k.max<=k.min?0:(int)((long)(r-l-2)*(k.clamp(k.value)-(long)k.min)/((long)k.max-k.min));fill(g,t+1,l+1,b-1,l+1+filled,off?GREY:DARK);
			}else{ // progress bars
				fill(g,t,l,b,r,MID);frame(g,t,l,b,r,ink);bevel(g,t+1,l+1,b-1,r-1,SHADOW,LIGHT);
				boolean h=k.horizontal()||r-l>=b-t;
				if(k.kind==4||k.kind==8){ // indeterminate: diagonal stripes moving with the phase
					java.awt.Shape clip=g.getClip();g.clipRect(l+1,t+1,r-l-2,b-t-2);
					for(int x=l-(b-t)-16+(k.phase&15);x<r+16;x+=16){java.awt.Polygon p=new java.awt.Polygon(new int[]{x,x+8,x+8+(b-t),x+(b-t)},new int[]{b,b,t,t},4);g.setColor(c(off?GREY:PROGRESS));g.fillPolygon(p);}
					g.setClip(clip);
				}else{
					long span=h?r-l-2:b-t-2;int filled=k.max<=k.min?0:(int)(span*(k.clamp(k.value)-(long)k.min)/((long)k.max-k.min));
					if(filled>0){
						if(h){int x0=k.rightToLeft()?r-1-filled:l+1;fill(g,t+1,x0,b-1,x0+filled,off?GREY:PROGRESS);line(g,x0,t+1,x0+filled-1,t+1,off?LIGHT:PROGRESS_LIGHT);}
						else{int y0=b-1-filled;fill(g,y0,l+1,b-1,r-1,off?GREY:PROGRESS);line(g,l+1,y0,l+1,b-2,off?LIGHT:PROGRESS_LIGHT);}
					}
				}
			}
		}
		/** A scroll bar thumb: lavender with grip lines; a ghost is only outlined. */
		static void thumb(java.awt.Graphics2D g,int[] r,boolean pressed,boolean horizontal,boolean ghost){
			int t=r[0],l=r[1],b=r[2],ri=r[3];if(ri-l<3||b-t<3)return;
			if(ghost){frame(g,t,l,b,ri,SHADOW);return;}
			fill(g,t,l,b,ri,pressed?ACCENT_SHADOW:ACCENT);frame(g,t,l,b,ri,BLACK);bevel(g,t+1,l+1,b-1,ri-1,WHITE,ACCENT_SHADOW);
			int cx=(l+ri)/2,cy=(t+b)/2;
			for(int i=-2;i<=2;i+=2){ // three grip lines across the thumb
				if(horizontal){line(g,cx+i,cy-3,cx+i,cy+2,ACCENT_DARK);line(g,cx+i+1,cy-2,cx+i+1,cy+3,WHITE);}
				else{line(g,cx-3,cy+i,cx+2,cy+i,ACCENT_DARK);line(g,cx-2,cy+i+1,cx+3,cy+i+1,WHITE);}
			}
		}
		/** A slider thumb: a bevelled block, pointed toward the ticks for kThemeThumbUpward/Downward. */
		static void sliderThumb(java.awt.Graphics2D g,int[] r,int dir,boolean horizontal,boolean pressed,boolean off){
			int t=r[0],l=r[1],b=r[2],ri=r[3];if(ri-l<3||b-t<3)return;
			int face=pressed?GREY:off?LIGHT:FACE,ink=off?SHADOW:BLACK;
			java.awt.Polygon p;
			if(dir==0)p=new java.awt.Polygon(new int[]{l,ri-1,ri-1,l},new int[]{t,t,b-1,b-1},4);
			else if(horizontal){int tip=(ri-l)/2;p=dir==2?new java.awt.Polygon(new int[]{l,ri-1,ri-1,l+tip,l},new int[]{t,t,b-1-tip,b-1,b-1-tip},5):new java.awt.Polygon(new int[]{l,l+tip,ri-1,ri-1,l},new int[]{t+tip,t,t+tip,b-1,b-1},5);}
			else{int tip=(b-t)/2;p=dir==2?new java.awt.Polygon(new int[]{l,ri-1-tip,ri-1,ri-1-tip,l},new int[]{t,t,t+tip,b-1,b-1},5):new java.awt.Polygon(new int[]{l+tip,ri-1,ri-1,l+tip,l},new int[]{t,t,b-1,b-1,t+tip},5);}
			g.setColor(c(face));g.fillPolygon(p);g.setColor(c(ink));g.drawPolygon(p);
			if(!off&&!pressed){line(g,l+1,t+1,l+1,b-3,WHITE);line(g,l+1,t+1,ri-3,t+1,WHITE);}
		}
		/** DrawThemeTrackTickMarks: evenly spaced ticks on the slider's thumb side (below / right when plain). */
		static void ticks(java.awt.Graphics2D g,Track k,int count){
			if(count<=0||!k.slider())return;
			int half=k.thumbLength()/2,s=k.trackStart()+half,e=k.trackEnd()-half-1;boolean h=k.horizontal();
			int ink=k.active()?DARK:GREY;
			for(int i=0;i<count;i++){int p=count==1?s:s+(int)((long)(e-s)*i/(count-1));
				if(h){int y0=k.thumbDir==1?k.top:k.bottom-5;line(g,p,y0,p,y0+4,ink);line(g,p+1,y0,p+1,y0+4,WHITE);}
				else{int x0=k.thumbDir==1?k.left:k.right-5;line(g,x0,p,x0+4,p,ink);line(g,x0,p+1,x0+4,p+1,WHITE);}
			}
		}
		/** The two arrow boxes of a scroll bar spanning {@code r}; pressState bits 1/2 press the first, 0x20/0x40 the second. */
		static void scrollArrows(java.awt.Graphics2D g,int[] r,int enable,int press,boolean horizontal){
			int t=r[0],l=r[1],b=r[2],ri=r[3];int thick=horizontal?b-t:ri-l,a=Math.max(0,Math.min(thick,(horizontal?ri-l:b-t)/2));if(a<=2)return;
			boolean off=enable!=0;int col=off?SHADOW:BLACK;
			int[][] boxes=horizontal?new int[][]{{t,l,b,l+a},{t,ri-a,b,ri}}:new int[][]{{t,l,t+a,ri},{b-a,l,b,ri}};
			for(int i=0;i<2;i++){int[] x=boxes[i];boolean down=!off&&(i==0?(press&3)!=0:(press&0x60)!=0);
				fill(g,x[0],x[1],x[2],x[3],down?GREY:off?LIGHT:FACE);frame(g,x[0],x[1],x[2],x[3],off?SHADOW:BLACK);if(!down&&!off)bevel(g,x[0]+1,x[1]+1,x[2]-1,x[3]-1,WHITE,SHADOW);
				arrow(g,(x[1]+x[3])/2,(x[0]+x[2])/2,Math.max(2,a/4),horizontal?(i==0?0:2):(i==0?3:1),col);}
		}
		/** The part of a scroll bar between its arrows. */
		static int[] scrollTrack(int[] r,boolean horizontal){
			int thick=horizontal?r[2]-r[0]:r[3]-r[1],len=horizontal?r[3]-r[1]:r[2]-r[0],a=Math.max(0,Math.min(thick,len/2));
			return horizontal?new int[]{r[0],r[1]+a,r[2],r[3]-a}:new int[]{r[0]+a,r[1],r[2]-a,r[3]};
		}
		/** HitTestThemeScrollBarArrows: 20 in the first arrow, 21 in the second, 0 elsewhere. */
		static int scrollArrowHit(int[] r,boolean horizontal,int v,int h){
			if(v<r[0]||v>=r[2]||h<r[1]||h>=r[3])return 0;
			int[] tr=scrollTrack(r,horizontal);int p=horizontal?h:v;
			return p<(horizontal?tr[1]:tr[0])?20:p>=(horizontal?tr[3]:tr[2])?21:0;
		}

		// ---- windows (ThemeWindowType)

		/** Platinum frame widths outside the content: {top, left, bottom, right}. */
		static int[] outsets(int flavor){
			switch(flavor){
				case 0:case 2:case 4:return new int[]{22,6,6,6};   // document, movable dialog/alert: title bar and a grey frame
				case 1:case 3:return new int[]{6,6,7,7};           // modal dialog, alert: outline 6 out, its shadow 1 further right and down
				case 6:return new int[]{1,1,3,3};                  // shadowed dialog
				case 8:return new int[]{14,2,2,2};                 // utility (floating) window
				case 9:return new int[]{2,14,2,2};                 // side utility window: the title bar on the left
				default:return new int[]{1,1,1,1};                 // plain dialog, pop-up, sheet
			}
		}
		static boolean titled(int flavor){return flavor==0||flavor==2||flavor==4||flavor==8||flavor==9;}
		static int[] structure(int flavor,int[] c){int[] o=outsets(flavor);return new int[]{c[0]-o[0],c[1]-o[1],c[2]+o[2],c[3]+o[3]};}
		static int[] titleBar(int flavor,int[] c){
			if(!titled(flavor))return null;int[] s=structure(flavor,c);
			return flavor==9?new int[]{s[0]+1,s[1]+1,s[2]-1,c[1]-1}:new int[]{s[0]+1,s[1]+1,c[0]-1,s[3]-1};
		}
		/** A title bar widget's box (close 0, zoom 1, collapse 2, dirty close 6) when the attributes give the window one; else null. */
		static int[] widget(int flavor,int[] c,int attributes,int widget){
			int[] tb=titleBar(flavor,c);if(tb==null)return null;
			boolean has=widget==0||widget==6?(attributes&0x20)!=0:widget==1?(attributes&0x18)!=0:widget==2&&(attributes&0x40)!=0;
			if(!has)return null;
			boolean small=flavor==8||flavor==9;int s=small?9:13;
			if(flavor==9){int x=tb[1]+(tb[3]-tb[1]-s)/2;int y=widget==0||widget==6?tb[0]+3:widget==2?tb[2]-3-s:tb[2]-3-s-((attributes&0x40)!=0?s+3:0);return new int[]{y,x,y+s,x+s};}
			int y=tb[0]+(tb[2]-tb[0]-s)/2;
			if(widget==0||widget==6){int x=tb[1]+(small?2:7);return new int[]{y,x,y+s,x+s};}
			int right=tb[3]-(small?2:7);
			if(widget==2)return new int[]{y,right-s,y+s,right};
			int x=right-s-((attributes&0x40)!=0?s+4:0);return new int[]{y,x,y+s,x+s};
		}
		/** The title text's rectangle: centred between the widgets, as wide as the metrics say (or the room there is). */
		static int[] titleText(int flavor,int[] c,int attributes,int width,int height){
			int[] tb=titleBar(flavor,c);if(tb==null||flavor==9)return tb;
			int[] close=widget(flavor,c,attributes|0x20,0),zoom=widget(flavor,c,attributes|0x18,1);
			int left=close[3]+6,right=zoom[1]-6;
			int room=Math.max(0,right-left),w=width>0?Math.min(width,room):room,cx=(tb[1]+tb[3])/2;
			int x=Math.max(left,Math.min(cx-w/2,right-w));
			int h=height>0?Math.min(height,tb[2]-tb[0]):tb[2]-tb[0]-2,y=tb[0]+(tb[2]-tb[0]-h)/2;
			return new int[]{y,x,y+h,x+w};
		}
		/** GetThemeWindowRegion's rectangles by WindowRegionCode; null for an empty region. */
		static int[] region(int flavor,int[] c,int attributes,int code,int titleWidth,int titleHeight){
			switch(code){
				case 0:return titleBar(flavor,c);                                          // kWindowTitleBarRgn
				case 1:return titleText(flavor,c,attributes,titleWidth,titleHeight);       // kWindowTitleTextRgn
				case 2:return widget(flavor,c,attributes,0);                               // kWindowCloseBoxRgn
				case 3:return widget(flavor,c,attributes,1);                               // kWindowZoomBoxRgn
				case 5:return titled(flavor)?titleBar(flavor,c):null;                      // kWindowDragRgn
				case 6:return (attributes&1)!=0?new int[]{c[2]-15,c[3]-15,c[2],c[3]}:null; // kWindowGrowRgn
				case 7:return widget(flavor,c,attributes,2);                               // kWindowCollapseBoxRgn
				case 32:return structure(flavor,c);                                        // kWindowStructureRgn
				case 33:case 40:return (attributes&0x100)!=0&&code==33?null:c.clone();     // kWindowContentRgn (empty when collapsed), kWindowGlobalPortRgn
				default:return null;                                                       // proxy icon, update region
			}
		}
		static boolean inside(int[] r,int v,int h){return r!=null&&v>=r[0]&&v<r[2]&&h>=r[1]&&h<r[3];}
		/** GetThemeWindowRegionHit: the region code under a point, or -1 outside the window. */
		static int regionHit(int flavor,int[] c,int attributes,int v,int h){
			for(int code:new int[]{2,3,7,6})if(inside(region(flavor,c,attributes,code,0,0),v,h))return code;
			if(inside(c,v,h))return 33;
			if(inside(structure(flavor,c),v,h))return 5; // the title bar and the Platinum frame drag the window
			return -1;
		}
		/** DrawThemeWindowFrame: outline, grey frame with its bevel, the title bar's stripes and widgets (the content is left alone). */
		static void windowFrame(java.awt.Graphics2D g,int flavor,int[] c,int state,int attributes,int[] title){
			int[] s=structure(flavor,c);boolean active=state!=0&&state!=8&&state!=7;int ink=active?BLACK:SHADOW;
			if(s[2]-s[0]<2||s[3]-s[1]<2)return;
			java.awt.geom.Area frameArea=new java.awt.geom.Area(new java.awt.Rectangle(s[1],s[0],s[3]-s[1],s[2]-s[0]));
			frameArea.subtract(new java.awt.geom.Area(new java.awt.Rectangle(c[1],c[0],c[3]-c[1],c[2]-c[0])));
			java.awt.Shape clip=g.getClip();g.clip(frameArea);
			try{
				if(flavor==6){frame(g,s[0],s[1],s[2]-2,s[3]-2,ink);fill(g,s[0]+2,s[3]-2,s[2],s[3],ink);fill(g,s[2]-2,s[1]+2,s[2],s[3],ink);return;} // outline and a 2-pixel shadow
				if(flavor==1||flavor==3){modalFrame(g,c,active);return;}
				fill(g,s[0],s[1],s[2],s[3],active?FACE:LIGHT);
				frame(g,s[0],s[1],s[2],s[3],ink);
				int[] o=outsets(flavor);
				if(o[1]>2||o[0]>2){bevel(g,s[0]+1,s[1]+1,s[2]-1,s[3]-1,WHITE,active?SHADOW:MID);
					frame(g,c[0]-1,c[1]-1,c[2]+1,c[3]+1,active?DARK:GREY); // the line around the content
					if(o[1]>2)bevel(g,c[0]-2,c[1]-2,c[2]+2,c[3]+2,active?SHADOW:MID,WHITE);}
				int[] tb=titleBar(flavor,c);
				if(tb!=null&&active){ // pinstripes, broken for the title and around the widgets
					boolean side=flavor==9;
					java.awt.geom.Area stripes=new java.awt.geom.Area(new java.awt.Rectangle(tb[1]+3,tb[0]+3,tb[3]-tb[1]-6,tb[2]-tb[0]-6));
					if(title!=null&&!side)stripes.subtract(new java.awt.geom.Area(new java.awt.Rectangle(title[1]-6,tb[0],title[3]-title[1]+12,tb[2]-tb[0])));
					for(int w:new int[]{0,1,2,6}){int[] x=widget(flavor,c,attributes,w);if(x!=null)stripes.subtract(new java.awt.geom.Area(new java.awt.Rectangle(x[1]-3,x[0]-3,x[3]-x[1]+6,x[2]-x[0]+6)));}
					java.awt.Shape inner=g.getClip();g.clip(stripes);
					if(side)for(int x=tb[1]+3;x<tb[3]-3;x+=2){line(g,x,tb[0],x,tb[2],SHADOW);line(g,x+1,tb[0],x+1,tb[2],WHITE);}
					else for(int y=tb[0]+3;y<tb[2]-3;y+=2){line(g,tb[1],y,tb[3],y,SHADOW);line(g,tb[1],y+1,tb[3],y+1,WHITE);}
					g.setClip(inner);
					for(int w:new int[]{0,1,2})if(widget(flavor,c,attributes,w)!=null)widgetBox(g,widget(flavor,c,attributes,w),w==0&&(attributes&0x200)!=0?6:w,false);
				}
				if(tb!=null)line(g,tb[1],flavor==9?tb[0]:tb[2],flavor==9?tb[1]:tb[3],tb[2],active?DARK:GREY);
			}finally{g.setClip(clip);}
		}
		/** The light, middle and dark greys of the modal dialog frame (measured on Mac OS 9). */
		static final int MODAL_LIGHT=0xc6c6c6,MODAL_MID=0xaaaaaa,MODAL_DARK=0x6c6c6c;
		/**
		 * The modal dialog / alert frame (dBoxProc, kWindowModalDialogProc) as a real Mac OS 9 draws it, measured pixel by pixel on
		 * MacPaint 1.5's About box: a black outline 6 pixels out from the content, a 1-pixel black shadow down and right of it (starting
		 * 2 pixels in from the corners), a light grey line inside the outline's top and left, a 2-pixel bevel (mid grey inside, dark
		 * grey outside) inside its bottom and right, and white between the frame and the content.
		 */
		static void modalFrame(java.awt.Graphics2D g,int[] c,boolean active){
			int t=c[0],l=c[1],b=c[2],r=c[3],ink=active?BLACK:SHADOW;
			fill(g,t-5,l-5,b+5,r+5,WHITE);
			frame(g,t-6,l-6,b+6,r+6,ink);
			fill(g,t-4,r+6,b+7,r+7,ink);fill(g,b+6,l-4,b+7,r+7,ink);                       // the shadow
			fill(g,t-5,l-5,t-4,r+5,MODAL_LIGHT);fill(g,t-5,l-5,b+5,l-4,MODAL_LIGHT);        // top and left
			fill(g,t-4,r+4,b+5,r+5,MODAL_DARK);fill(g,b+4,l-4,b+5,r+5,MODAL_DARK);          // the bevel's outer line
			fill(g,t-3,r+3,b+4,r+4,MODAL_MID);fill(g,b+3,l-3,b+4,r+4,MODAL_MID);            // and its inner one
			fill(g,t-4,r+3,t-3,r+4,MODAL_LIGHT);fill(g,b+3,l-4,b+4,l-3,MODAL_LIGHT);        // the corners where light meets the bevel
		}
		/** A title bar widget: a bevelled box; the zoom box holds a smaller box, the collapse box a bar, a dirty close box a dot. */
		static void widgetBox(java.awt.Graphics2D g,int[] r,int widget,boolean pressed){
			int t=r[0],l=r[1],b=r[2],ri=r[3],s=ri-l;
			fill(g,t,l,b,ri,pressed?SHADOW:MID);frame(g,t,l,b,ri,BLACK);if(!pressed)bevel(g,t+1,l+1,b-1,ri-1,WHITE,SHADOW);else bevel(g,t+1,l+1,b-1,ri-1,DARK,GREY);
			int ink=BLACK;
			if(widget==1)frame(g,t+2,l+2,t+2+s/2+1,l+2+s/2+1,ink);
			else if(widget==2){line(g,l+2,t+s/2-1,ri-3,t+s/2-1,ink);line(g,l+2,t+s/2+1,ri-3,t+s/2+1,ink);}
			else if(widget==6){g.setColor(c(ink));g.fillOval(l+s/2-2,t+s/2-2,4,4);}
		}
		/** DrawThemeScrollBarDelimiters: the lines where a document's scroll bars meet its content. */
		static void delimiters(java.awt.Graphics2D g,int[] c,int state,int attributes){
			int ink=disabled(state)?GREY:BLACK;
			line(g,c[3]-16,c[0],c[3]-16,c[2]-1,ink);line(g,c[1],c[2]-16,c[3]-1,c[2]-16,ink);
		}
		/** A standalone grow box (with its ridges) or no-grow box. */
		static void growBox(java.awt.Graphics2D g,int[] r,boolean grow,int state){
			int t=r[0],l=r[1],b=r[2],ri=r[3];boolean off=disabled(state);
			fill(g,t,l,b,ri,off?LIGHT:FACE);frame(g,t,l,b,ri,off?SHADOW:BLACK);if(!off)bevel(g,t+1,l+1,b-1,ri-1,WHITE,SHADOW);
			if(!grow||off)return;
			int n=ri-l;for(int i=4;i<n-3;i+=3){line(g,ri-3,b-3-i+2,ri-3-i+2,b-3,DARK);line(g,ri-3,b-2-i+2,ri-2-i+2,b-3,WHITE);}
		}
		static void popupArrow(java.awt.Graphics2D g,int[] r,int orientation,int size,int state){
			int h=size==0?3:size==1?5:size==2?7:9;int col=state==2?WHITE:disabled(state)?SHADOW:BLACK;
			arrow(g,(r[1]+r[3])/2,(r[0]+r[2])/2,h,orientation==0?0:orientation==1?1:orientation==2?2:3,col);
		}
		/** The chasing arrows: two arcs with heads, turned an eighth further for each index. */
		static void chasingArrows(java.awt.Graphics2D g,int[] r,int index,int state){
			int s=Math.min(r[3]-r[1],r[2]-r[0]);if(s<6)return;int x=r[1]+(r[3]-r[1]-s)/2,y=r[0]+(r[2]-r[0]-s)/2;
			g.setColor(c(disabled(state)?GREY:BLACK));g.setStroke(new java.awt.BasicStroke(Math.max(1f,s/8f)));
			double cx=x+s/2.0,cy=y+s/2.0,rad=s/2.0-2;
			for(int k=0;k<2;k++){
				double a0=-(index&7)*45+k*180;g.draw(new java.awt.geom.Arc2D.Double(cx-rad,cy-rad,2*rad,2*rad,a0,125,java.awt.geom.Arc2D.OPEN));
				double end=Math.toRadians(a0+125),ex=cx+rad*Math.cos(end),ey=cy-rad*Math.sin(end),hx=-Math.sin(end),hy=-Math.cos(end),head=Math.max(2,s/5.0);
				java.awt.geom.Path2D.Double p=new java.awt.geom.Path2D.Double();p.moveTo(ex+hx*head,ey+hy*head);p.lineTo(ex-Math.cos(end)*head*0.8,ey+Math.sin(end)*head*0.8);p.lineTo(ex+Math.cos(end)*head*0.8,ey-Math.sin(end)*head*0.8);p.closePath();g.fill(p);
			}
			g.setStroke(new java.awt.BasicStroke(1f));
		}
		static void tickMark(java.awt.Graphics2D g,int[] r,int state){
			int ink=disabled(state)?GREY:DARK;
			if(r[3]-r[1]>=r[2]-r[0]){line(g,r[1],r[0],r[3]-1,r[0],ink);if(r[2]-r[0]>1)line(g,r[1],r[0]+1,r[3]-1,r[0]+1,WHITE);}
			else{line(g,r[1],r[0],r[1],r[2]-1,ink);if(r[3]-r[1]>1)line(g,r[1]+1,r[0],r[1]+1,r[2]-1,WHITE);}
		}

		// ---- menus

		static void menuBar(java.awt.Graphics2D g,int[] r){fill(g,r[0],r[1],r[2],r[3],FACE);line(g,r[1],r[0],r[3]-1,r[0],WHITE);line(g,r[1],r[2]-1,r[3]-1,r[2]-1,BLACK);}
		static void menuTitle(java.awt.Graphics2D g,int[] r,int state){fill(g,r[0],r[1],r[2]-1,r[3],state==1?HILITE:FACE);}
		static void menuBackground(java.awt.Graphics2D g,int[] r){fill(g,r[0],r[1],r[2],r[3],FACE);frame(g,r[0],r[1],r[2],r[3],BLACK);bevel(g,r[0]+1,r[1]+1,r[2]-1,r[3]-1,WHITE,SHADOW);}
		static void menuItem(java.awt.Graphics2D g,int[] r,int state,int type){
			fill(g,r[0],r[1],r[2],r[3],state==1?HILITE:FACE);
			int t=type&0xff,col=state==1?WHITE:state==3?SHADOW:BLACK;
			if(t==1)arrow(g,r[3]-10,(r[0]+r[2])/2,4,2,col);else if(t==2)arrow(g,(r[1]+r[3])/2,(r[0]+r[2])/2,4,3,col);else if(t==3)arrow(g,(r[1]+r[3])/2,(r[0]+r[2])/2,4,1,col);
		}
		static void menuSeparator(java.awt.Graphics2D g,int[] r){int y=(r[0]+r[2])/2;fill(g,r[0],r[1],r[2],r[3],FACE);line(g,r[1]+1,y-1,r[3]-2,y-1,SHADOW);line(g,r[1]+1,y,r[3]-2,y,WHITE);}
	}
}

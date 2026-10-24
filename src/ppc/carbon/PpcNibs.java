import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Interface Builder nib files as CarbonLib 1.x loaded them on Mac OS 9 (IBCarbonRuntime.h): a ".nib" folder in a bundle's
 * Resources (or a localized ".lproj" folder) whose objects.xib describes windows and menus. CreateNibReference(WithCFBundle)
 * opens one; CreateWindowFromNib makes an invisible window of its class and title with the push buttons, check boxes, radio
 * buttons, pop-up buttons (each with its menu), static texts, edit texts, group boxes, separators and user panes of its
 * root control, each with its ControlID; CreateMenuFromNib makes a menu. Acrobat Reader 5.0's print dialog extension takes
 * its controls from such a window and moves them into the print dialog - without the nib it worked on garbage handles.
 * Only what a 2001 nib holds is read; anything else in a window becomes a user pane.
 */
final class PpcNibs {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcNibs(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	static final int CANT_FIND_NIB=-10960,CANT_FIND_OBJECT=-10962,PARAM_ERR=-50;
	private final Map<Integer,Nib> nibs=new HashMap<Integer,Nib>();
	private int nextMenuId=31000;

	private static final class Nib {
		final Map<String,Element> objects=new HashMap<String,Element>(),named=new HashMap<String,Element>();
	}

	void define(PpcLibraries l){
		l.register(null,"CreateNibReference",(r,c)->ret(open(rt.libraries.coreFoundation.mainBundle().address,arg(0),arg(1))));
		l.register(null,"CreateNibReferenceWithCFBundle",(r,c)->ret(open(arg(0),arg(1),arg(2))));
		l.register(null,"DisposeNibReference",(r,c)->{Nib n=nibs.remove(arg(0));if(n!=null&&memory.isAllocated(arg(0)))memory.free(arg(0));});
		l.register(null,"CreateWindowFromNib",(r,c)->{Nib n=nibs.get(arg(0));int out=arg(2);if(out!=0)memory.writeLong(out,0);
			Element w=n==null?null:n.named.get(text(arg(1)));if(w==null||!"IBCarbonWindow".equals(w.getAttribute("class"))){ret(n==null?PARAM_ERR:CANT_FIND_OBJECT);return;}
			int window=window(n,w);if(out!=0)memory.writeLong(out,window);ret(window==0?-108:0);});
		l.register(null,"CreateMenuFromNib",(r,c)->{Nib n=nibs.get(arg(0));int out=arg(2);if(out!=0)memory.writeLong(out,0);
			Element m=n==null?null:n.named.get(text(arg(1)));if(m==null||!"IBCarbonMenu".equals(m.getAttribute("class"))){ret(n==null?PARAM_ERR:CANT_FIND_OBJECT);return;}
			int menu=menu(m);if(out!=0)memory.writeLong(out,menu);ret(0);});
		l.register(null,"CreateMenuBarFromNib",(r,c)->{if(arg(2)!=0)memory.writeLong(arg(2),0);ret(CANT_FIND_OBJECT);});
		l.register(null,"SetMenuBarFromNib",(r,c)->ret(CANT_FIND_OBJECT));
	}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private String text(int cfString){String s=rt.libraries.coreFoundation.text(cfString);return s==null?"":s;}

	/** CreateNibReferenceWithCFBundle(bundle, name, *outNib). */
	private int open(int bundle,int name,int out) throws M68kException {
		if(out!=0)memory.writeLong(out,0);
		File resources=rt.libraries.coreFoundation.bundleResources(bundle);String base=text(name);
		if(resources==null||base.isEmpty())return CANT_FIND_NIB;
		File xib=find(resources,base+".nib");
		if(xib==null)return CANT_FIND_NIB;
		Nib n=new Nib();
		try{
			Document doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xib);
			NodeList all=doc.getElementsByTagName("object");
			for(int i=0;i<all.getLength();i++){Element e=(Element)all.item(i);if(!e.getAttribute("id").isEmpty())n.objects.put(e.getAttribute("id"),e);}
			NodeList dicts=doc.getElementsByTagName("dictionary");
			for(int i=0;i<dicts.getLength();i++){Element d=(Element)dicts.item(i);if(!"nameTable".equals(d.getAttribute("name")))continue;
				String key=null;for(Node k=d.getFirstChild();k!=null;k=k.getNextSibling()){if(!(k instanceof Element))continue;Element e=(Element)k;
					if(e.getTagName().equals("string"))key=e.getTextContent();
					else if(key!=null){Element target=e.getTagName().equals("reference")?n.objects.get(e.getAttribute("idRef")):e;if(target!=null)n.named.put(key,target);key=null;}}}
		}catch(Exception e){return CANT_FIND_NIB;}
		int ref=rt.allocate(4,4);nibs.put(ref,n);if(out!=0)memory.writeLong(out,ref);
		return 0;
	}
	/** The nib's objects.xib: in Resources, else in a localized folder (the development region's first). */
	private static File find(File resources,String nib){
		File f=new File(new File(resources,nib),"objects.xib");if(f.isFile())return f;
		File[] dirs=resources.listFiles();if(dirs==null)return null;java.util.Arrays.sort(dirs);
		for(String first:new String[]{"Japanese.lproj","ja.lproj","English.lproj","en.lproj"})for(File d:dirs)if(d.getName().equals(first)){f=new File(new File(d,nib),"objects.xib");if(f.isFile())return f;}
		for(File d:dirs)if(d.getName().endsWith(".lproj")){f=new File(new File(d,nib),"objects.xib");if(f.isFile())return f;}
		return null;
	}

	private static Element child(Element e,String name){for(Node k=e.getFirstChild();k!=null;k=k.getNextSibling())if(k instanceof Element&&name.equals(((Element)k).getAttribute("name")))return (Element)k;return null;}
	private static String value(Element e,String name,String dflt){Element c=child(e,name);return c==null?dflt:c.getTextContent().trim();}
	private static int intValue(Element e,String name,int dflt){try{return Integer.parseInt(value(e,name,""+dflt));}catch(NumberFormatException x){return dflt;}}
	/** "top left bottom right". */
	private static int[] rect(Element e,String name){String[] p=value(e,name,"0 0 0 0").split("\\s+");int[] r=new int[4];for(int i=0;i<4&&i<p.length;i++)try{r[i]=Integer.parseInt(p[i]);}catch(NumberFormatException x){}return r;}
	private int rectBlock(int[] r) throws M68kCpuException {int p=rt.allocate(8,2);for(int i=0;i<4;i++)memory.writeWord(p+2*i,r[i]);return p;}
	private int pascal(String s) throws M68kCpuException {byte[] b=rt.libraries.textServices.systemBytes(s);int n=Math.min(255,b.length);int p=rt.allocate(n+2,2);memory.writeByte(p,n);memory.load(p+1,java.util.Arrays.copyOf(b,n));return p;}

	private int window(Nib n,Element w) throws M68kException {
		int[] r=rect(w,"windowRect");int bounds=rectBlock(r),out=rt.allocate(4,4);
		int cls=intValue(w,"carbonWindowClass",6),attributes="TRUE".equals(value(w,"isResizable","FALSE"))?0x10:0;
		try{
			if(rt.cpu.call(rt.nativeSymbol("CarbonLib","CreateNewWindow",Pef.CLASS_TVECTOR,false),new int[]{cls,attributes,bounds,out})!=0)return 0;
			int window=memory.readLong(out);if(window==0)return 0;
			int title=pascal(value(w,"title",""));rt.toolbox("SetWTitle",window,title);memory.free(title);
			Element root=child(w,"rootControl");
			if(root!=null){Element views=child(root,"subviews");if(views!=null)for(Node k=views.getFirstChild();k!=null;k=k.getNextSibling())if(k instanceof Element)control(window,(Element)k);}
			return window;
		}finally{memory.free(bounds);memory.free(out);}
	}
	private void control(int window,Element e) throws M68kException {
		if(e.getTagName().equals("reference")){return;}
		String cls=e.getAttribute("class");int[] r=rect(e,"bounds");String title=value(e,"title","");
		int procId,value=intValue(e,"value",0),min=0,max=1;int menu=0;
		switch(cls){
			case "IBCarbonButton":procId=368;value=0;break;                                   // kControlPushButtonProc
			case "IBCarbonCheckBox":procId=369;max=2;break;                                   // kControlCheckBoxProc (mixed = 2)
			case "IBCarbonRadioButton":procId=370;break;                                      // kControlRadioButtonProc
			case "IBCarbonPopupButton":{Element m=child(e,"menu");menu=m==null?0:menu(m);   // the classic pop-up CDEF: min = menu ID, max = title width
				procId=1008;value=0;min=menu==0?0:(short)memory.readWord(memory.readLong(menu));max=-1;break;}
			case "IBCarbonStaticText":procId=288;value=0;max=0;break;                          // kControlStaticTextProc
			case "IBCarbonEditText":procId=272;value=0;max=0;break;                            // kControlEditTextProc
			case "IBCarbonGroupBox":procId=160;value=0;max=0;break;                            // kControlGroupBoxTextTitleProc
			case "IBCarbonSeparator":procId=144;value=0;max=0;break;                           // kControlSeparatorLineProc
			default:procId=256;value=0;max=0;break;                                            // kControlUserPaneProc
		}
		int rb=rectBlock(r),t=pascal(procId==288||procId==272?"":title);
		boolean hidden="TRUE".equals(value(e,"hidden","FALSE"));
		int h;try{h=rt.toolbox("NewControl",window,rb,t,hidden?0:1,value,min,max,procId,0);}finally{memory.free(rb);memory.free(t);}
		if(h==0)return;
		if(procId==1008)rt.toolbox("SetControlValue",h,1);
		if((procId==288||procId==272)&&!title.isEmpty()){byte[] b=rt.libraries.textServices.systemBytes(title);int p=rt.allocate(Math.max(1,b.length),2);memory.load(p,b);
			try{rt.toolbox("SetControlData",h,0,0x74657874,b.length,p);}finally{memory.free(p);}} // kControlStaticTextTextTag / kControlEditTextTextTag 'text'
		String sig=value(e,"controlSignature",null);
		if(sig!=null||child(e,"controlID")!=null){int id=rt.allocate(8,4);byte[] s=(sig==null?"\0\0\0\0":sig+"    ").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
			for(int i=0;i<4;i++)memory.writeByte(id+i,s[i]);memory.writeLong(id+4,intValue(e,"controlID",0));
			try{rt.cpu.call(rt.nativeSymbol("CarbonLib","SetControlID",Pef.CLASS_TVECTOR,false),new int[]{h,id});}finally{memory.free(id);}}
	}
	/** A menu of the nib's items, in the menu list as a pop-up's or a hierarchical menu's (InsertMenu -1). */
	private int menu(Element m) throws M68kException {
		int id;do{id=nextMenuId++;}while(rt.toolbox("GetMenuHandle",id)!=0&&nextMenuId<32000);
		int t=pascal(value(m,"title",""));int menu;try{menu=rt.toolbox("NewMenu",id,t);}finally{memory.free(t);}
		if(menu==0)return 0;
		Element items=child(m,"items");int n=0;
		if(items!=null)for(Node k=items.getFirstChild();k!=null;k=k.getNextSibling()){if(!(k instanceof Element))continue;Element item=(Element)k;
			boolean separator="TRUE".equals(value(item,"isSeparator","FALSE"));
			int blank=pascal(separator?"(-":" ");try{rt.toolbox("AppendMenu",menu,blank);}finally{memory.free(blank);}n++;
			if(!separator){int p=pascal(value(item,"title",""));try{rt.toolbox("SetMenuItemText",menu,n,p);}finally{memory.free(p);}}}
		rt.toolbox("InsertMenu",menu,-1);
		return menu;
	}
}

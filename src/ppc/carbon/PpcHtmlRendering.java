import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JEditorPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Position;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.ImageView;
import javax.swing.text.html.StyleSheet;

/**
 * The HTML Rendering Library (HTMLRendering.h) and Apple Help (AppleHelp.h) as CarbonLib 1.6 re-exports them.
 *
 * <p>An {@code HRReference} is a small guest block standing for a Java {@link Ref}: a Swing {@link JEditorPane} with an
 * {@link HTMLEditorKit} that is never shown. It parses and lays out the HTML 3.2 the library was made for (the pane works
 * off-screen, also with {@code java.awt.headless=true}); {@code HRDraw}/{@code HRDrawInPort} paint the visible part of the
 * laid-out page plus a drawn Platinum scroll bar (vertical and, for wide pages, horizontal; on/off/auto as
 * {@code HRSetScrollbarState} says) into an image and copy that into the target QuickDraw port (window or GWorld) inside the
 * rendering rect. {@code HRIsHREvent} takes mouse-downs in the content of the hilited window that holds a reference: clicks
 * in the scroll bars scroll (arrows and pages auto-repeat and the thumb tracks while the button is down), a click on a link
 * goes to it the way the library does, through the program's "new URL" callback first.
 *
 * <p>URLs: documents come from file URLs only; a file URL ("file://localhost/Volume/Folder/file.html", as
 * {@link PpcCoreFoundation#fileURLString} writes them) maps to the VM's volumes through PpcCoreFoundation's mapping, after
 * the program's URL-to-FSSpec/FSRef callback had its say. Nothing is fetched from the network (no URL Access underneath):
 * an http/ftp/mailto link is still reported to the program's new-URL callback (programs hand such links to a browser there)
 * and then answered with {@code hrURLNotHandledErr}, the page left as it was. For the same reason remote images, CSS
 * background images and BACKGROUND attributes are not loaded, frames and OBJECT/APPLET content are left out (Swing would
 * fetch or instantiate them), and form controls are drawn as inert boxes. Local images (IMG with a file or relative URL)
 * and linked/imported local style sheets are loaded.
 *
 * <p>The "was URL visited" callback colours visited links; the CFURL/FSRef callback types of Carbon
 * ({@code TVECTOR_UPP_TYPE}: the UPP is the routine itself) have their New/Dispose/Invoke functions here.
 *
 * <p>Apple Help: there is no Help Viewer on this VM, so AHSearch, AHGotoMainTOC, AHGotoPage, AHLookupAnchor and
 * AHGotoApplicationHelp answer {@code kAHInternalErr}.
 */
final class PpcHtmlRendering {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcHtmlRendering(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private PpcCoreFoundation cf(){return rt.libraries.coreFoundation;}

	static final int NO_ERR=0,PARAM_ERR=-50,FNF_ERR=-43,IO_ERR=-36;
	/** MacErrors.h */
	static final int HR_HTML_RENDERING_LIB_NOT_INSTALLED_ERR=-5360,HR_MISCELLANEOUS_EXCEPTION_ERR=-5361,HR_UNABLE_TO_RESIZE_HANDLE_ERR=-5362,HR_URL_NOT_HANDLED_ERR=-5363;
	/** AppleHelp.h */
	static final int K_AH_INTERNAL_ERR=-10790;
	/** kHRRendererHTML32Type 'ht32', the only renderer type there is. */
	static final int K_HR_RENDERER_HTML32_TYPE=0x68743332;
	static final int E_HR_SCROLLBAR_ON=0,E_HR_SCROLLBAR_OFF=1,E_HR_SCROLLBAR_AUTO=2;
	/** URLSourceType */
	static final int K_HR_LOOKING_FOR_HTML_SOURCE=1,K_HR_LOOKING_FOR_IMAGE=2;
	/** uppHRWasURLVisitedProcInfo, uppHRNewURLProcInfo, uppHRURLToFSSpecProcInfo (Pascal) from HTMLRendering.h. */
	static final int UPP_HR_WAS_URL_VISITED=0x3d0,UPP_HR_NEW_URL=0x37f0,UPP_HR_URL_TO_FSSPEC=0xeff0;
	/** The Carbon CFURL/FSRef callbacks have no procInfo in the header (TVECTOR_UPP_TYPE); the same shapes with the C convention. */
	static final int UPP_HR_WAS_CFURL_VISITED=0x3d1,UPP_HR_NEW_CFURL=0x37f1,UPP_HR_URL_TO_FSREF=0xeff1;
	/** HTMLRenderingLib 1.1 final: NumVersion {major BCD, minor|bug, stage, non-release}. */
	static final int LIB_VERSION=0x01108000;
	private static final int SB=16,LINE=16,MARGIN=8;
	private static final int MOUSE_DOWN=1,IN_CONTENT=3;
	private static final int PART_UP=1,PART_DOWN=2,PART_PAGE_UP=3,PART_PAGE_DOWN=4,PART_THUMB=5;
	private static final Color VISITED=new Color(0x55,0x1a,0x8b);

	// ------------------------------------------------------------------ references

	/** One HRReference: where it draws, its page (pane + document), URLs, scrolling and the program's callbacks. */
	private final class Ref {
		final int address;int port,window,control;
		int top,left,bottom,right;boolean active=true,border,growCutout,forceQuickdraw;
		int hState=E_HR_SCROLLBAR_AUTO,vState=E_HR_SCROLLBAR_AUTO,scrollH,scrollV;
		JEditorPane pane;HTMLDocument doc;String url="",base="",title="";File file;
		final Map<String,java.net.URL> images=new HashMap<String,java.net.URL>();
		/** {upp, refCon} of each registered callback, or null. */
		int[] wasVisited,wasCFVisited,newURL,newCFURL,toFSSpec,toFSRef;
		boolean inCallback;
		// layout: document size, visible area (port coordinates), scroll bars {top,left,bottom,right} or null
		int measuredWidth=-1,docW,docH,viewL,viewT,viewW,viewH;int[] vbar,hbar;
		Ref(int address){this.address=address;}
		int maxV(){return Math.max(0,docH-viewH);}
		int maxH(){return Math.max(0,docW-viewW);}
	}
	private final Map<Integer,Ref> refs=new LinkedHashMap<Integer,Ref>();
	private Ref ref(int p){return refs.get(p);}

	// ------------------------------------------------------------------ text, handles, rects

	private StackTextEncoding encoding(){return rt.toolbox.encoding;}
	private String cText(int p) throws M68kCpuException {return p==0?null:encoding().decode(rt.cBytes(p));}
	private int cstr(String s) throws M68kCpuException {byte[] b=encoding().encodeReplacing(s==null?"":s);int p=rt.allocate(b.length+1,4);memory.load(p,b);memory.writeByte(p+b.length,0);return p;}
	/** Puts {@code s} into Handle {@code h} as a C string (sized to the text and its terminator). */
	private int toHandle(int h,String s) throws M68kException {
		if(h==0)return PARAM_ERR;byte[] b=encoding().encodeReplacing(s==null?"":s);
		rt.toolbox("SetHandleSize",h,b.length+1);if(rt.toolbox("GetHandleSize",h)<b.length+1||memory.readLong(h)==0)return HR_UNABLE_TO_RESIZE_HANDLE_ERR;
		int p=memory.readLong(h);memory.load(p,b);memory.writeByte(p+b.length,0);return NO_ERR;
	}
	private void writeRect(int p,int t,int l,int b,int r) throws M68kCpuException {memory.writeWord(p,t);memory.writeWord(p+2,l);memory.writeWord(p+4,b);memory.writeWord(p+6,r);}
	private int call(int proc,int procInfo,int... args) throws M68kException {return rt.isRoutineDescriptor(proc)?rt.callUniversal(proc,procInfo,args):rt.cpu.call(proc,args);}

	// ------------------------------------------------------------------ URLs

	private static final Pattern SCHEME=Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
	private static String stripFragment(String u){int i=u.indexOf('#');return i<0?u:u.substring(0,i);}
	private static String fragment(String u){int i=u.indexOf('#');return i<0?null:u.substring(i+1);}
	private static String scheme(String u){Matcher m=SCHEME.matcher(u);return m.lookingAt()?u.substring(0,m.end()-1).toLowerCase(java.util.Locale.ROOT):"";}
	/** {@code link} resolved against {@code root} (RFC 1808 style; "file://localhost/..." keeps its host, unlike java.net.URI). */
	static String resolve(String root,String link){
		if(link==null||link.isEmpty())return root==null?"":root;
		if(root==null||root.isEmpty()||SCHEME.matcher(link).lookingAt())return link;
		String r=stripFragment(root);
		if(link.startsWith("#"))return r+link;
		int q=r.indexOf('?');if(link.startsWith("?"))return (q<0?r:r.substring(0,q))+link;if(q>=0)r=r.substring(0,q);
		int authority=r.indexOf("://");String prefix,path;
		if(authority>=0){int slash=r.indexOf('/',authority+3);if(slash<0){prefix=r;path="/";}else{prefix=r.substring(0,slash);path=r.substring(slash);}}
		else{int colon=r.indexOf(':');prefix=r.substring(0,colon+1);path=r.substring(colon+1);}
		if(link.startsWith("//"))return (authority>=0?r.substring(0,authority+1):prefix)+link;
		String joined=link.startsWith("/")?link:path.substring(0,path.lastIndexOf('/')+1)+link;
		return prefix+removeDots(joined);
	}
	/** Removes "." and ".." segments from a path (its query and fragment kept as they are). */
	private static String removeDots(String path){
		int cut=path.length();for(char ch:new char[]{'?','#'}){int i=path.indexOf(ch);if(i>=0&&i<cut)cut=i;}
		String p=path.substring(0,cut),rest=path.substring(cut);
		List<String> out=new ArrayList<String>();String[] parts=p.split("/",-1);
		for(int i=0;i<parts.length;i++){String s=parts[i];boolean last=i==parts.length-1;
			if(s.equals(".")){if(last)out.add("");continue;}
			if(s.equals("..")){if(out.size()>1)out.remove(out.size()-1);if(last)out.add("");continue;}
			out.add(s);}
		return String.join("/",out)+rest;
	}
	/** The VM file a file URL names (PpcCoreFoundation's mapping), or null. */
	private File fileOfURL(String url){
		if(url==null||!scheme(url).equals("file"))return null;
		try{return cf().fileOf(cf().new CFURL(url,0));}catch(RuntimeException e){return null;}
	}
	private String urlOfFile(File f){return cf().fileURLString(f,f.isDirectory());}
	/**
	 * The file behind {@code link} (relative to {@code root}): the program's URL-to-FSRef or URL-to-FSSpec callback first (it may
	 * map any scheme to a local copy), then the file URL mapping. Null when there is none.
	 */
	private File locate(Ref f,String root,String link,int sourceType) throws M68kException {
		if(f.toFSRef!=null){int r=cf().newString(root),l=cf().newString(link),ref=rt.allocate(80,4);
			try{if(call(f.toFSRef[0],UPP_HR_URL_TO_FSREF,r,l,ref,sourceType,f.toFSRef[1])==NO_ERR){File file=rt.toolbox.files.fsRefFile(ref);if(file!=null)return file;}}
			finally{cf().release(r);cf().release(l);memory.free(ref);}}
		if(f.toFSSpec!=null){int r=cstr(root),l=cstr(link),spec=rt.allocate(80,4);
			try{if(call(f.toFSSpec[0],UPP_HR_URL_TO_FSSPEC,r,l,spec,sourceType,f.toFSSpec[1])==NO_ERR){File file=null;try{file=rt.toolbox.files.fileForSpec(spec);}catch(java.io.IOException ignored){}if(file!=null)return file;}}
			finally{memory.free(r);memory.free(l);memory.free(spec);}}
		return fileOfURL(resolve(root,link));
	}

	// ------------------------------------------------------------------ callbacks

	/** The program's "new URL" callbacks (CFURL form, then C string form); a non-zero result stops the navigation. */
	private int notifyNewURL(Ref f,String url,String target,boolean addToHistory) throws M68kException {
		if(f.inCallback)return NO_ERR; // a callback that calls HRGoToURL itself is not told again
		f.inCallback=true;
		try{
			if(f.newCFURL!=null){int u=cf().newURL(url,0),t=cf().newString(target==null?"":target);
				try{int err=call(f.newCFURL[0],UPP_HR_NEW_CFURL,u,t,addToHistory?1:0,f.newCFURL[1]);if(err!=NO_ERR)return err;}finally{cf().release(u);cf().release(t);}}
			if(f.newURL!=null){int u=cstr(url),t=cstr(target==null?"":target);
				try{int err=call(f.newURL[0],UPP_HR_NEW_URL,u,t,addToHistory?1:0,f.newURL[1]);if(err!=NO_ERR)return err;}finally{memory.free(u);memory.free(t);}}
			return NO_ERR;
		}finally{f.inCallback=false;}
	}
	/** The program's "was URL visited" answer for {@code url} (false without a callback). */
	private boolean visited(Ref f,String url) throws M68kException {
		if(f.wasCFVisited!=null){int u=cf().newURL(url,0);try{return (call(f.wasCFVisited[0],UPP_HR_WAS_CFURL_VISITED,u,f.wasCFVisited[1])&0xff)!=0;}finally{cf().release(u);}}
		if(f.wasVisited!=null){int u=cstr(url);try{return (call(f.wasVisited[0],UPP_HR_WAS_URL_VISITED,u,f.wasVisited[1])&0xff)!=0;}finally{memory.free(u);}}
		return false;
	}

	// ------------------------------------------------------------------ loading

	private static final Pattern CHARSET=Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)");
	/** HTML bytes as text: a BOM, the META charset, valid UTF-8, else the system script's encoding (Mac Roman / Shift-JIS). */
	private String decode(byte[] b){
		if(b.length>=3&&(b[0]&0xff)==0xef&&(b[1]&0xff)==0xbb&&(b[2]&0xff)==0xbf)return new String(b,3,b.length-3,java.nio.charset.StandardCharsets.UTF_8);
		if(b.length>=2&&((b[0]&0xff)==0xfe&&(b[1]&0xff)==0xff||(b[0]&0xff)==0xff&&(b[1]&0xff)==0xfe))return new String(b,java.nio.charset.StandardCharsets.UTF_16);
		Matcher m=CHARSET.matcher(new String(b,0,Math.min(b.length,4096),java.nio.charset.StandardCharsets.ISO_8859_1));
		if(m.find()){String name=m.group(1).toLowerCase(java.util.Locale.ROOT);
			switch(name){case "x-mac-roman":case "macintosh":case "x-macintosh":name="x-MacRoman";break;case "x-mac-japanese":case "x-sjis":case "sjis":name="windows-31j";break;default:}
			try{return new String(b,java.nio.charset.Charset.forName(name));}catch(RuntimeException ignored){}}
		try{return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();}catch(java.nio.charset.CharacterCodingException notUtf8){}
		return encoding().decode(b);
	}
	private static final Pattern MARKUP=Pattern.compile("(?is)<style\\b[^>]*>.*?</style\\s*>|<[a-z!/][^>]*>"),CSS_URL=Pattern.compile("(?i)url\\s*\\("),BACKGROUND=Pattern.compile("(?i)(\\s)background\\s*=");
	/**
	 * Takes the network out of the markup: CSS url(...) values and BACKGROUND attributes would make Swing load images through
	 * java.net.URL (http included, blocking) behind the program's back. Only tags and STYLE blocks are touched, not the text.
	 */
	static String sanitize(String html){
		Matcher m=MARKUP.matcher(html);StringBuffer sb=new StringBuffer();
		while(m.find()){String t=CSS_URL.matcher(m.group()).replaceAll("x-url(");t=BACKGROUND.matcher(t).replaceAll("$1data-background=");m.appendReplacement(sb,Matcher.quoteReplacement(t));}
		m.appendTail(sb);return sb.toString();
	}

	/** Loads {@code data} as the page of {@code f}: parse, title, base, local images, visited links, a fresh pane. */
	private void load(Ref f,byte[] data,String url,File file) throws M68kException {
		Kit kit=new Kit(f);HTMLDocument doc=(HTMLDocument)kit.createDefaultDocument();
		java.net.URL base0=null;try{if(!url.isEmpty())base0=new java.net.URL(stripFragment(url));}catch(java.net.MalformedURLException ignored){}
		doc.setBase(base0);
		try{kit.read(new java.io.StringReader(sanitize(decode(data))),doc,0);}catch(java.io.IOException|BadLocationException|RuntimeException e){/* what was parsed so far stays */}
		f.doc=doc;f.url=url;f.file=file;
		Object t=doc.getProperty(Document.TitleProperty);f.title=t==null?"":t.toString().trim();
		java.net.URL b=doc.getBase();f.base=b!=null&&b!=base0?b.toString():stripFragment(url); // a BASE tag replaced it
		f.images.clear();resolveImages(f,doc.getDefaultRootElement());
		markVisited(f);
		JEditorPane pane=new JEditorPane();pane.setEditable(false);pane.setMargin(new Insets(MARGIN,MARGIN,MARGIN,MARGIN));pane.setEditorKit(kit);pane.setDocument(doc);
		f.pane=pane;f.measuredWidth=-1;f.scrollH=0;f.scrollV=0;
	}
	/** Finds the file of every IMG (the program's callback, then the file URL) before the views ask for them. */
	private void resolveImages(Ref f,Element e) throws M68kException {
		AttributeSet a=e.getAttributes();
		if(a.getAttribute(StyleConstants.NameAttribute)==HTML.Tag.IMG){Object src=a.getAttribute(HTML.Attribute.SRC);
			if(src!=null&&!f.images.containsKey(src.toString())){File file=locate(f,f.base,src.toString(),K_HR_LOOKING_FOR_IMAGE);
				if(file!=null&&file.isFile())try{f.images.put(src.toString(),file.toURI().toURL());}catch(java.net.MalformedURLException ignored){}}}
		for(int i=0;i<e.getElementCount();i++)resolveImages(f,e.getElement(i));
	}
	/** Colours the links the program's "was URL visited" callback says were visited. */
	private void markVisited(Ref f) throws M68kException {
		if(f.wasVisited==null&&f.wasCFVisited==null)return;
		List<int[]> ranges=new ArrayList<int[]>();
		for(HTMLDocument.Iterator it=f.doc.getIterator(HTML.Tag.A);it.isValid();it.next()){Object href=it.getAttributes().getAttribute(HTML.Attribute.HREF);
			if(href!=null&&visited(f,resolve(f.base,href.toString())))ranges.add(new int[]{it.getStartOffset(),it.getEndOffset()});}
		if(ranges.isEmpty())return;
		SimpleAttributeSet s=new SimpleAttributeSet();f.doc.getStyleSheet().addCSSAttribute(s,CSS.Attribute.COLOR,String.format("#%06x",VISITED.getRGB()&0xffffff));
		for(int[] r:ranges)f.doc.setCharacterAttributes(r[0],r[1]-r[0],s,false);
	}

	/**
	 * Goes to {@code url}: tells the program (new-URL callback), then shows it. The same document with another fragment only
	 * scrolls; file URLs load from the VM's volumes; other schemes are not fetched ({@code hrURLNotHandledErr}).
	 */
	private int goTo(Ref f,String url,boolean addToHistory,boolean forceRefresh,String target) throws M68kException {
		if(url==null)return PARAM_ERR;
		String full=resolve(f.base,url);
		int err=notifyNewURL(f,full,target,addToHistory);if(err!=NO_ERR)return err;
		String frag=fragment(full);
		if(!forceRefresh&&f.doc!=null&&stripFragment(full).equals(stripFragment(f.url))){
			f.url=full;if(frag!=null)scrollToAnchor(f,frag);else{f.scrollV=0;f.scrollH=0;}invalidate(f);return NO_ERR;}
		File file=locate(f,f.base,url,K_HR_LOOKING_FOR_HTML_SOURCE);
		if(file==null&&!scheme(full).equals("file"))return HR_URL_NOT_HANDLED_ERR; // no fetching from the network
		if(file==null||!file.isFile())return FNF_ERR;
		byte[] data;try{data=java.nio.file.Files.readAllBytes(file.toPath());}catch(java.io.IOException|SecurityException e){return IO_ERR;}
		load(f,data,full,file);
		if(frag!=null)scrollToAnchor(f,frag);
		invalidate(f);return NO_ERR;
	}
	/** A page from bytes (HRGoToPtr, HRGoToData): no URL of its own; relative links resolve against a BASE tag in it. */
	private int goToBytes(Ref f,byte[] data) throws M68kException {load(f,data,"",null);invalidate(f);return NO_ERR;}
	/** Scrolls so the A NAME anchor is at the top; false when there is no such anchor. */
	private boolean scrollToAnchor(Ref f,String name) throws M68kException {
		if(f.doc==null||name==null)return false;
		for(HTMLDocument.Iterator it=f.doc.getIterator(HTML.Tag.A);it.isValid();it.next()){Object n=it.getAttributes().getAttribute(HTML.Attribute.NAME);
			if(n!=null&&n.toString().equals(name)){layout(f);
				try{Rectangle2D r=f.pane.modelToView2D(it.getStartOffset());if(r!=null){f.scrollV=Math.max(0,Math.min(f.maxV(),(int)r.getY()));}}catch(BadLocationException|RuntimeException e){return false;}
				return true;}}
		return false;
	}
	/** After a new page: the window's update event redraws it (a GWorld is drawn by the program's HRDrawInPort). */
	private void invalidate(Ref f) throws M68kException {
		if(f.port==0||!rt.toolbox.windows.isWindow(f.port))return;
		int rect=rt.allocate(8,4);try{writeRect(rect,f.top,f.left,f.bottom,f.right);inPort(f.port,()->rt.toolbox("InvalRect",rect));}finally{memory.free(rect);}
	}

	// ------------------------------------------------------------------ layout and drawing

	/** Lays the page out {@code width} wide (wider when it cannot be narrower): sets docW/docH and sizes the pane. */
	private void measure(Ref f,int width){
		width=Math.max(1,width);
		if(f.pane==null){f.docW=width;f.docH=0;f.measuredWidth=width;return;}
		if(f.measuredWidth==width)return;
		try{
			Insets in=f.pane.getInsets();View root=f.pane.getUI().getRootView(f.pane);int ix=in.left+in.right,iy=in.top+in.bottom;
			root.setSize(Math.max(1,width-ix),1<<20);
			int min=(int)Math.ceil(root.getMinimumSpan(View.X_AXIS))+ix,w=Math.max(width,min);
			if(w!=width)root.setSize(w-ix,1<<20);
			int h=(int)Math.ceil(root.getPreferredSpan(View.Y_AXIS))+iy;
			f.pane.setSize(w,h);root.setSize(w-ix,h-iy);f.docW=w;f.docH=h;
		}catch(RuntimeException e){f.docW=width;f.docH=0;}
		f.measuredWidth=width;
	}
	/** Scroll bars (per HRSetScrollbarState), the visible area and the document size for the current rendering rect. */
	private void layout(Ref f){
		int inset=f.border?1:0,cl=f.left+inset,ct=f.top+inset,cw=Math.max(0,f.right-f.left-2*inset),ch=Math.max(0,f.bottom-f.top-2*inset);
		boolean v=f.vState==E_HR_SCROLLBAR_ON,h=f.hState==E_HR_SCROLLBAR_ON;
		for(int pass=0;pass<3;pass++){
			measure(f,cw-(v?SB:0));
			boolean nv=f.vState==E_HR_SCROLLBAR_ON||f.vState==E_HR_SCROLLBAR_AUTO&&f.docH>ch-(h?SB:0);
			boolean nh=f.hState==E_HR_SCROLLBAR_ON||f.hState==E_HR_SCROLLBAR_AUTO&&f.docW>cw-(nv?SB:0);
			if(nv==v&&nh==h)break;v=nv;h=nh;
		}
		f.viewL=cl;f.viewT=ct;f.viewW=Math.max(0,cw-(v?SB:0));f.viewH=Math.max(0,ch-(h?SB:0));
		f.vbar=v&&cw>=SB?new int[]{ct,cl+cw-SB,ct+ch-(h||f.growCutout?SB-1:0),cl+cw}:null;
		f.hbar=h&&ch>=SB?new int[]{ct+ch-SB,cl,ct+ch,cl+cw-(v?SB-1:0)}:null;
		f.scrollV=Math.max(0,Math.min(f.maxV(),f.scrollV));f.scrollH=Math.max(0,Math.min(f.maxH(),f.scrollH));
	}
	/** The rendering rect as an image: the visible part of the page, the scroll bars, the border. */
	private BufferedImage render(Ref f){
		layout(f);
		int w=f.right-f.left,h=f.bottom-f.top;if(w<=0||h<=0||(long)w*h>16_000_000L)return null;
		BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);Graphics2D g=img.createGraphics();
		try{
			g.setColor(Color.WHITE);g.fillRect(0,0,w,h);
			if(f.pane!=null&&f.viewW>0&&f.viewH>0){Graphics2D c=(Graphics2D)g.create();
				try{c.clipRect(f.viewL-f.left,f.viewT-f.top,f.viewW,f.viewH);c.translate(f.viewL-f.left-f.scrollH,f.viewT-f.top-f.scrollV);
					c.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,Fonts68k.textAntialiasHint());f.pane.paint(c);}
				catch(RuntimeException e){/* a view that cannot paint leaves its area white */}finally{c.dispose();}}
			if(f.vbar!=null)ControlManager68k.drawScrollBar(g,f.vbar[1]-f.left,f.vbar[0]-f.top,f.vbar[3]-f.left,f.vbar[2]-f.top,f.scrollV,0,f.maxV(),0,!f.active);
			if(f.hbar!=null)ControlManager68k.drawScrollBar(g,f.hbar[1]-f.left,f.hbar[0]-f.top,f.hbar[3]-f.left,f.hbar[2]-f.top,f.scrollH,0,f.maxH(),0,!f.active);
			if(f.border){g.setColor(Color.BLACK);g.drawRect(0,0,w-1,h-1);}
		}finally{g.dispose();}
		return img;
	}
	private interface Body {void run() throws M68kException;}
	/** Runs {@code body} with {@code port} as the current port (and its device, for a GWorld). */
	private void inPort(int port,Body body) throws M68kException {
		int slot=rt.allocate(8,4);
		try{rt.toolbox("GetGWorld",slot,slot+4);int savedPort=memory.readLong(slot),savedDevice=memory.readLong(slot+4);
			if(savedPort!=port)rt.toolbox("SetGWorld",port,0);
			try{body.run();}finally{if(savedPort!=port&&savedPort!=0)rt.toolbox("SetGWorld",savedPort,savedDevice);}}
		finally{memory.free(slot);}
	}
	/**
	 * Draws the reference into {@code port}. The update region is not used to narrow the drawing: programs pass regions in
	 * local or global coordinates alike, and drawing the whole rendering rect (clipped by the port) is always right.
	 */
	private int draw(Ref f,int port) throws M68kException {
		if(port==0)return PARAM_ERR;
		BufferedImage img=render(f);if(img==null)return NO_ERR;
		inPort(port,()->rt.toolbox.quickDraw.drawImage(img,f.top,f.left,f.bottom,f.right));
		return NO_ERR;
	}

	// ------------------------------------------------------------------ clicks

	/** HRIsHREvent: a mouse-down in a reference's rendering rect (front window) scrolls or follows a link. */
	private boolean isHREvent(int event) throws M68kException {
		if(event==0||memory.readWord(event)!=MOUSE_DOWN||refs.isEmpty())return false;
		int where=memory.readLong(event+10),slot=rt.allocate(4,4);
		try{
			int part=(short)rt.toolbox("FindWindow",where,slot),win=memory.readLong(slot);
			if(part!=IN_CONTENT||win==0||memory.readByte(win+111)==0)return false; // only the hilited (front) window's content
			for(Ref f:new ArrayList<Ref>(refs.values())){
				if(f.port!=win)continue;
				memory.writeLong(slot,where);inPort(win,()->rt.toolbox("GlobalToLocal",slot));
				int v=(short)memory.readWord(slot),h=(short)memory.readWord(slot+2);
				if(v<f.top||v>=f.bottom||h<f.left||h>=f.right)continue;
				click(f,h,v);return true;
			}
			return false;
		}finally{memory.free(slot);}
	}
	private static boolean inside(int[] r,int h,int v){return r!=null&&v>=r[0]&&v<r[2]&&h>=r[1]&&h<r[3];}
	private void click(Ref f,int h,int v) throws M68kException {
		layout(f);
		if(inside(f.vbar,h,v)){track(f,true,h,v);return;}
		if(inside(f.hbar,h,v)){track(f,false,h,v);return;}
		if(h<f.viewL||h>=f.viewL+f.viewW||v<f.viewT||v>=f.viewT+f.viewH)return;
		String[] link=linkAt(f,h-f.viewL+f.scrollH,v-f.viewT+f.scrollV);
		if(link!=null)goTo(f,link[0],true,false,link[1]);
	}
	/** {href, target} of the link under document point (x, y), or null. */
	private String[] linkAt(Ref f,int x,int y){
		if(f.pane==null)return null;
		try{
			int off=f.pane.viewToModel2D(new java.awt.geom.Point2D.Double(x,y));if(off<0)return null;
			for(int c:new int[]{off,off-1}){
				if(c<0||c>=f.doc.getLength())continue;
				Rectangle2D a=f.pane.modelToView2D(c),b=f.pane.modelToView2D(c+1);if(a==null||b==null)continue;
				double right=b.getY()==a.getY()&&b.getX()>a.getX()?b.getX():a.getX()+1;
				if(x<a.getX()||x>=right||y<a.getY()||y>=a.getY()+a.getHeight())continue;
				Object anchor=f.doc.getCharacterElement(c).getAttributes().getAttribute(HTML.Tag.A);
				if(!(anchor instanceof AttributeSet))return null;
				Object href=((AttributeSet)anchor).getAttribute(HTML.Attribute.HREF),target=((AttributeSet)anchor).getAttribute(HTML.Attribute.TARGET);
				return href==null?null:new String[]{href.toString(),target==null?null:target.toString()};
			}
		}catch(BadLocationException|RuntimeException e){return null;}
		return null;
	}
	/** A scroll bar click: arrows and pages repeat while the button is down, the thumb follows the mouse. */
	private void track(Ref f,boolean vertical,int h,int v) throws M68kException {
		int[] bar=vertical?f.vbar:f.hbar;int length=vertical?bar[2]-bar[0]:bar[3]-bar[1],arrow=Math.min(16,length/2),max=vertical?f.maxV():f.maxH();
		if(max<=0||!f.active)return;
		int pos=vertical?v-bar[0]:h-bar[1],value=vertical?f.scrollV:f.scrollH;int[] thumb=ControlManager68k.thumbRange(length,arrow,0,max,value);
		int part=pos<arrow?PART_UP:pos>=length-arrow?PART_DOWN:pos<thumb[0]?PART_PAGE_UP:pos>=thumb[1]?PART_PAGE_DOWN:PART_THUMB;
		int page=Math.max(LINE,(vertical?f.viewH:f.viewW)-LINE);
		int pt=rt.allocate(8,4);
		try{
			if(part==PART_THUMB){
				int grab=pos-thumb[0],travel=Math.max(1,length-2*arrow-(thumb[1]-thumb[0]));
				while((rt.toolbox("StillDown")&0xff)!=0){
					inPort(f.port,()->rt.toolbox("GetMouse",pt));int p=vertical?(short)memory.readWord(pt)-bar[0]:(short)memory.readWord(pt+2)-bar[1];
					int nv=(int)Math.max(0,Math.min(max,(long)(p-grab-arrow)*max/travel));
					if(nv!=(vertical?f.scrollV:f.scrollH)){if(vertical)f.scrollV=nv;else f.scrollH=nv;draw(f,f.port);}
				}
				return;
			}
			int step=part==PART_UP?-LINE:part==PART_DOWN?LINE:part==PART_PAGE_UP?-page:page;
			for(int n=0;;n++){
				int nv=Math.max(0,Math.min(max,(vertical?f.scrollV:f.scrollH)+step));
				if(nv!=(vertical?f.scrollV:f.scrollH)){if(vertical)f.scrollV=nv;else f.scrollH=nv;draw(f,f.port);}
				if((rt.toolbox("StillDown")&0xff)==0)break;
				rt.toolbox("Delay",n==0?12:3,pt+4); // the Control Manager's auto-repeat pace
			}
		}finally{memory.free(pt);}
	}

	// ------------------------------------------------------------------ the Swing side

	/** HTMLEditorKit whose documents load local style sheets only and whose views fetch nothing. */
	private final class Kit extends HTMLEditorKit {
		final Ref ref;final ViewFactory factory;
		Kit(Ref ref){this.ref=ref;factory=new Factory(ref);}
		@Override public Document createDefaultDocument(){
			StyleSheet sheet=new LocalStyleSheet(0),defaults=new StyleSheet();
			defaults.addRule("body { font-family: Serif; font-size: 12pt; color: black }");
			sheet.addStyleSheet(getStyleSheet());sheet.addStyleSheet(defaults);
			HTMLDocument d=new HTMLDocument(sheet);d.setParser(getParser());d.setAsynchronousLoadPriority(-1);d.setTokenThreshold(Integer.MAX_VALUE);
			d.putProperty("IgnoreCharsetDirective",Boolean.TRUE);return d;
		}
		@Override public ViewFactory getViewFactory(){return factory;}
	}
	/** LINK/@import style sheets from the VM's files only (url(...) values were taken out, see {@link #sanitize}). */
	private final class LocalStyleSheet extends StyleSheet {
		final int depth;LocalStyleSheet(int depth){this.depth=depth;}
		@Override public void importStyleSheet(java.net.URL url){
			if(url==null||depth>8)return;
			File file=fileOfURL(url.toString());if(file==null||!file.isFile())return;
			try{String css=CSS_URL.matcher(decode(java.nio.file.Files.readAllBytes(file.toPath()))).replaceAll("x-url(");
				LocalStyleSheet sub=new LocalStyleSheet(depth+1);sub.loadRules(new java.io.StringReader(css),url);addStyleSheet(sub);}
			catch(java.io.IOException|RuntimeException ignored){}
		}
	}
	/** Swing's HTML views, except: images from the files resolved beforehand, no frames/objects/applets, inert form boxes. */
	private final class Factory extends HTMLEditorKit.HTMLFactory {
		final Ref ref;Factory(Ref ref){this.ref=ref;}
		@Override public View create(Element e){
			Object o=e.getAttributes().getAttribute(StyleConstants.NameAttribute);
			if(o==HTML.Tag.IMG)return new LocalImageView(e,ref);
			if(o==HTML.Tag.FRAME||o==HTML.Tag.OBJECT||o==HTML.Tag.APPLET)return new FormBox(e,true);
			if(o==HTML.Tag.INPUT||o==HTML.Tag.SELECT||o==HTML.Tag.TEXTAREA||o==HTML.Tag.ISINDEX)return new FormBox(e,false);
			return super.create(e);
		}
	}
	/** An IMG loaded synchronously from the local file found for it (no file: the alt text box Swing draws for a missing image). */
	private static final class LocalImageView extends ImageView {
		final Ref ref;
		LocalImageView(Element e,Ref ref){super(e);this.ref=ref;setLoadsSynchronously(true);}
		@Override public java.net.URL getImageURL(){Object src=getElement().getAttributes().getAttribute(HTML.Attribute.SRC);return src==null?null:ref.images.get(src.toString());}
	}
	/** A form control drawn as an inert box or button (or nothing: hidden inputs, frames, objects); Swing's would be live components. */
	private static final class FormBox extends View {
		final int kind;String label="";int cols=20,rows=1; // kind 0 nothing, 1 text box, 2 button, 3 check box/radio
		FormBox(Element e,boolean empty){
			super(e);AttributeSet a=e.getAttributes();Object tag=a.getAttribute(StyleConstants.NameAttribute);
			String type=String.valueOf(a.getAttribute(HTML.Attribute.TYPE)).toLowerCase(java.util.Locale.ROOT);Object value=a.getAttribute(HTML.Attribute.VALUE);
			try{Object size=tag==HTML.Tag.TEXTAREA?a.getAttribute(HTML.Attribute.COLS):a.getAttribute(HTML.Attribute.SIZE);if(size!=null)cols=Math.max(1,Math.min(200,Integer.parseInt(size.toString().trim())));
				Object r=a.getAttribute(HTML.Attribute.ROWS);if(tag==HTML.Tag.TEXTAREA&&r!=null)rows=Math.max(1,Math.min(100,Integer.parseInt(r.toString().trim())));}catch(NumberFormatException ignored){}
			Object model=a.getAttribute(StyleConstants.ModelAttribute);
			if(empty||tag==HTML.Tag.INPUT&&(type.equals("hidden")||type.equals("image")))kind=0;
			else if(tag==HTML.Tag.INPUT&&(type.equals("submit")||type.equals("reset")||type.equals("button"))){kind=2;label=value!=null?value.toString():type.equals("reset")?"Reset":type.equals("submit")?"Submit":"";}
			else if(tag==HTML.Tag.INPUT&&(type.equals("checkbox")||type.equals("radio")))kind=3;
			else if(tag==HTML.Tag.SELECT&&model instanceof javax.swing.ComboBoxModel){kind=2;Object s=((javax.swing.ComboBoxModel<?>)model).getSelectedItem();if(s==null&&((javax.swing.ComboBoxModel<?>)model).getSize()>0)s=((javax.swing.ComboBoxModel<?>)model).getElementAt(0);label=s==null?"":s.toString()+"  \u25be";}
			else{kind=1;if(tag==HTML.Tag.SELECT)rows=4;
				if(model instanceof Document){try{Document d=(Document)model;label=d.getText(0,d.getLength());if(type.equals("password"))label=label.replaceAll(".","\u2022");}catch(BadLocationException ignored){}}
				else if(value!=null)label=value.toString();}
		}
		private static final Font FONT=new Font(Font.SANS_SERIF,Font.PLAIN,11);
		@Override public float getPreferredSpan(int axis){
			switch(kind){
				case 1:return axis==X_AXIS?cols*7+6:rows*14+6;
				case 2:{java.awt.Container c=getContainer();int tw=c==null?label.length()*7:c.getFontMetrics(FONT).stringWidth(label);return axis==X_AXIS?tw+20:20;}
				case 3:return 14;
				default:return 0;
			}
		}
		@Override public float getAlignment(int axis){return axis==Y_AXIS?0.8f:0.5f;}
		@Override public void paint(Graphics g,Shape s){
			if(kind==0)return;Rectangle r=s.getBounds();g.setFont(FONT);FontMetrics fm=g.getFontMetrics();
			if(kind==3){g.setColor(Color.WHITE);g.fillRect(r.x+1,r.y+1,12,12);g.setColor(Color.BLACK);g.drawRect(r.x+1,r.y+1,11,11);return;}
			if(kind==2){g.setColor(new Color(0xdd,0xdd,0xdd));g.fillRoundRect(r.x,r.y,r.width-1,r.height-1,8,8);g.setColor(Color.BLACK);g.drawRoundRect(r.x,r.y,r.width-1,r.height-1,8,8);
				g.drawString(label,r.x+(r.width-fm.stringWidth(label))/2,r.y+(r.height-fm.getHeight())/2+fm.getAscent());return;}
			g.setColor(Color.WHITE);g.fillRect(r.x,r.y,r.width,r.height);g.setColor(Color.GRAY);g.drawRect(r.x,r.y,r.width-1,r.height-1);
			g.setColor(Color.BLACK);Shape clip=g.getClip();g.clipRect(r.x+2,r.y+2,r.width-4,r.height-4);int y=r.y+3+fm.getAscent();
			for(String line:label.split("\n",-1)){g.drawString(line,r.x+3,y);y+=fm.getHeight();if(y>r.y+r.height)break;}
			g.setClip(clip);
		}
		@Override public Shape modelToView(int pos,Shape a,Position.Bias b){Rectangle r=a.getBounds();if(pos>=getEndOffset())r.x+=r.width;r.width=0;return r;}
		@Override public int viewToModel(float x,float y,Shape a,Position.Bias[] bias){bias[0]=Position.Bias.Forward;return getStartOffset();}
	}

	// ------------------------------------------------------------------ the library

	/** The Boolean argument {@code i} (a byte in a word). */
	private boolean flag(int i) throws M68kCpuException {return (arg(i)&0xff)!=0;}
	private interface RefCall {int run(Ref f) throws M68kException;}
	/** An OSStatus function whose first argument is the HRReference (paramErr for one that is not). */
	private void fn(PpcLibraries l,String name,RefCall body){l.register(null,name,(r,c)->{Ref f=ref(arg(0));ret(f==null?PARAM_ERR:body.run(f));});}

	void define(PpcLibraries l){
		l.register(null,"HRGetHTMLRenderingLibVersion",(r,c)->{int p=arg(0);if(p==0){ret(PARAM_ERR);return;}memory.writeLong(p,LIB_VERSION);ret(NO_ERR);});
		Body0 newRef=(out,type,port,window)->{
			if(out==0)return PARAM_ERR;memory.writeLong(out,0);
			if(type!=K_HR_RENDERER_HTML32_TYPE)return PARAM_ERR; // only the HTML 3.2 renderer exists
			int p=rt.allocate(16,4);memory.writeLong(p,0x68724866); // 'hrHf'
			Ref f=new Ref(p);f.port=port;f.window=window;
			if(port!=0){f.top=(short)memory.readWord(port+16);f.left=(short)memory.readWord(port+18);f.bottom=(short)memory.readWord(port+20);f.right=(short)memory.readWord(port+22);}
			refs.put(p,f);memory.writeLong(out,p);return NO_ERR;};
		l.register(null,"HRNewReference",(r,c)->{int port=arg(2);ret(newRef.make(arg(0),arg(1),port,port!=0&&rt.toolbox.windows.isWindow(port)?port:0));}); // (hrRef*, rendererType, grafPtr)
		l.register(null,"HRNewReferenceInWindow",(r,c)->{int w=arg(2);ret(w==0?PARAM_ERR:newRef.make(arg(0),arg(1),w,w));}); // a WindowRef is its port here
		l.register(null,"HRDisposeReference",(r,c)->{Ref f=refs.remove(arg(0));if(f==null){ret(PARAM_ERR);return;}f.pane=null;f.doc=null;if(memory.isAllocated(f.address))memory.free(f.address);ret(NO_ERR);});
		l.register(null,"HRFreeMemory",(r,c)->ret(0)); // SInt32 bytes freed: the pages are Java objects, there is no purgeable cache in the guest heap
		l.register(null,"HRScreenConfigurationChanged",(r,c)->{for(Ref f:refs.values())f.measuredWidth=-1;}); // layout does not depend on the screen; relaid out on the next draw anyway
		l.register(null,"HRIsHREvent",(r,c)->ret(isHREvent(arg(0))?1:0));
		fn(l,"HRSetGrafPtr",f->{f.port=arg(1);f.window=f.port!=0&&rt.toolbox.windows.isWindow(f.port)?f.port:0;return NO_ERR;});
		fn(l,"HRSetWindowRef",f->{f.port=arg(1);f.window=arg(1);return NO_ERR;});
		fn(l,"HRSetEmbeddingControl",f->{f.control=arg(1);return NO_ERR;}); // the scroll bars are drawn by the renderer, nothing to embed
		fn(l,"HRActivate",f->{f.active=true;return NO_ERR;});
		fn(l,"HRDeactivate",f->{f.active=false;return NO_ERR;});
		fn(l,"HRDraw",f->draw(f,f.port)); // (hrRef, updateRgnH)
		fn(l,"HRDrawInPort",f->draw(f,arg(2))); // (hrRef, updateRgnH, grafPtr)
		fn(l,"HRSetRenderingRect",f->{int p=arg(1);if(p==0)return PARAM_ERR;f.top=(short)memory.readWord(p);f.left=(short)memory.readWord(p+2);f.bottom=(short)memory.readWord(p+4);f.right=(short)memory.readWord(p+6);return NO_ERR;});
		fn(l,"HRGetRenderedImageSize",f->{int p=arg(1);if(p==0)return PARAM_ERR;layout(f);memory.writeWord(p,Math.min(32767,f.docH));memory.writeWord(p+2,Math.min(32767,f.docW));return NO_ERR;}); // Point: v = height, h = width
		fn(l,"HRGetRenderedImageSize32",f->{layout(f);if(arg(1)!=0)memory.writeLong(arg(1),f.docH);if(arg(2)!=0)memory.writeLong(arg(2),f.docW);return arg(1)==0||arg(2)==0?PARAM_ERR:NO_ERR;}); // (hrRef, height*, width*)
		fn(l,"HRScrollToLocation",f->{int p=arg(1);if(p==0)return PARAM_ERR;layout(f);f.scrollV=Math.max(0,Math.min(f.maxV(),(short)memory.readWord(p)));f.scrollH=Math.max(0,Math.min(f.maxH(),(short)memory.readWord(p+2)));
			memory.writeWord(p,f.scrollV);memory.writeWord(p+2,f.scrollH);invalidate(f);return NO_ERR;}); // the location reached comes back
		fn(l,"HRScrollToImageLocation32",f->{layout(f);f.scrollH=Math.max(0,Math.min(f.maxH(),arg(1)));f.scrollV=Math.max(0,Math.min(f.maxV(),arg(2)));invalidate(f);return NO_ERR;}); // (hrRef, h, v)
		fn(l,"HRForceQuickdraw",f->{f.forceQuickdraw=flag(1);return NO_ERR;}); // all drawing goes through QuickDraw here already
		fn(l,"HRSetScrollbarState",f->{int h=(short)arg(1),v=(short)arg(2);if(h<0||h>2||v<0||v>2)return PARAM_ERR;f.hState=h;f.vState=v;return NO_ERR;});
		fn(l,"HRSetDrawBorder",f->{f.border=flag(1);return NO_ERR;});
		fn(l,"HRSetGrowboxCutout",f->{f.growCutout=flag(1);return NO_ERR;});
		// navigation
		fn(l,"HRGoToFile",f->{int spec=arg(1);if(spec==0)return PARAM_ERR;File file;try{file=rt.toolbox.files.fileForSpec(spec);}catch(java.io.IOException e){file=null;}
			if(file==null||!file.isFile())return FNF_ERR;return goTo(f,urlOfFile(file),flag(2),flag(3),null);}); // (hrRef, fsspec, addToHistory, forceRefresh)
		fn(l,"HRGoToFSRef",f->{File file=arg(1)==0?null:rt.toolbox.files.fsRefFile(arg(1));if(file==null||!file.isFile())return FNF_ERR;return goTo(f,urlOfFile(file),flag(2),flag(3),null);});
		fn(l,"HRGoToURL",f->goTo(f,cText(arg(1)),flag(2),flag(3),null));
		fn(l,"HRGoToCFURL",f->{PpcCoreFoundation.CFURL u=cf().url(arg(1));return u==null?PARAM_ERR:goTo(f,u.absolute(),flag(2),flag(3),null);});
		fn(l,"HRGoToAnchor",f->{String a=cText(arg(1));return a==null?PARAM_ERR:anchor(f,a);});
		fn(l,"HRGoToAnchorCFString",f->{String a=cf().text(arg(1));return a==null?PARAM_ERR:anchor(f,a);});
		fn(l,"HRGoToPtr",f->{int p=arg(1),n=arg(2);if(p==0&&n!=0||n<0)return PARAM_ERR;return goToBytes(f,n==0?new byte[0]:memory.readBytes(p,n));}); // (hrRef, buffer, size, addToHistory, forceRefresh)
		fn(l,"HRGoToData",f->{PpcCoreFoundation.CFData d=cf().object(arg(1),PpcCoreFoundation.CFData.class);return d==null?PARAM_ERR:goToBytes(f,d.bytes(cf()));});
		// what is shown
		fn(l,"HRGetRootURL",f->toHandle(arg(1),rootOf(f)));
		fn(l,"HRGetBaseURL",f->toHandle(arg(1),f.base));
		fn(l,"HRGetHTMLURL",f->toHandle(arg(1),f.url));
		fn(l,"HRGetTitle",f->{int p=arg(1);if(p==0)return PARAM_ERR;memory.load(p,rt.toolbox.readPascalBytesOf(f.title));return NO_ERR;});
		fn(l,"HRGetHTMLFile",f->{int p=arg(1);if(p==0)return PARAM_ERR;memory.fill(p,70,0);if(f.file==null)return FNF_ERR;return rt.toolbox.files.fileManager.writeFSSpec(p,f.file);});
		fn(l,"HRGetRootURLAsCFString",f->outString(arg(1),rootOf(f)));
		fn(l,"HRGetBaseURLAsCFString",f->outString(arg(1),f.base));
		fn(l,"HRGetTitleAsCFString",f->outString(arg(1),f.title));
		fn(l,"HRGetHTMLURLAsCFURL",f->{int p=arg(1);if(p==0)return PARAM_ERR;memory.writeLong(p,0);if(f.url.isEmpty())return FNF_ERR;memory.writeLong(p,cf().newURL(f.url,0));return NO_ERR;}); // no URL (a page from HRGoToPtr): NULL
		fn(l,"HRGetHTMLFileAsFSRef",f->{int p=arg(1);if(p==0)return PARAM_ERR;memory.fill(p,80,0);if(f.file==null||!f.file.exists())return FNF_ERR;rt.toolbox.files.writeFSRef(p,f.file);return NO_ERR;});
		// utilities
		l.register(null,"HRUtilCreateFullURL",(r,c)->{String link=cText(arg(1));ret(link==null&&arg(0)==0?PARAM_ERR:toHandle(arg(2),resolve(cText(arg(0)),link)));});
		l.register(null,"HRUtilGetFSSpecFromURL",(r,c)->{int spec=arg(2);if(spec==0){ret(PARAM_ERR);return;}memory.fill(spec,70,0);String full=resolve(cText(arg(0)),cText(arg(1)));
			if(!scheme(full).equals("file")){ret(PARAM_ERR);return;}File file=fileOfURL(full);ret(file==null?FNF_ERR:rt.toolbox.files.fileManager.writeFSSpec(spec,file));}); // fnfErr with a valid spec for a file not there yet
		l.register(null,"HRUtilGetURLFromFSSpec",(r,c)->{int spec=arg(0);if(spec==0){ret(PARAM_ERR);return;}File file;try{file=rt.toolbox.files.fileForSpec(spec);}catch(java.io.IOException e){file=null;}ret(file==null?FNF_ERR:toHandle(arg(1),urlOfFile(file)));});
		l.register(null,"HRUtilCreateFullCFURL",(r,c)->{int out=arg(2);if(out==0){ret(PARAM_ERR);return;}memory.writeLong(out,0);String full=resolve(cf().text(arg(0)),cf().text(arg(1)));if(full.isEmpty()){ret(PARAM_ERR);return;}memory.writeLong(out,cf().newURL(full,0));ret(NO_ERR);});
		l.register(null,"HRUtilGetFSRefFromURL",(r,c)->{int out=arg(2);if(out==0){ret(PARAM_ERR);return;}memory.fill(out,80,0);String full=resolve(cf().text(arg(0)),cf().text(arg(1)));
			if(!scheme(full).equals("file")){ret(PARAM_ERR);return;}File file=fileOfURL(full);if(file==null||!file.exists()){ret(FNF_ERR);return;}rt.toolbox.files.writeFSRef(out,file);ret(NO_ERR);});
		l.register(null,"HRUtilGetURLFromFSRef",(r,c)->{int out=arg(1);if(out==0){ret(PARAM_ERR);return;}memory.writeLong(out,0);File file=arg(0)==0?null:rt.toolbox.files.fsRefFile(arg(0));if(file==null){ret(FNF_ERR);return;}memory.writeLong(out,cf().newURL(urlOfFile(file),0));ret(NO_ERR);});
		// callbacks: (upp, hrRef, refCon) / (hrRef)
		register(l,"WasURLVisited",(f,v)->f.wasVisited=v);
		register(l,"WasCFURLVisited",(f,v)->f.wasCFVisited=v);
		register(l,"NewURL",(f,v)->f.newURL=v);
		register(l,"NewCFURL",(f,v)->f.newCFURL=v);
		register(l,"URLToFSSpec",(f,v)->f.toFSSpec=v);
		register(l,"URLToFSRef",(f,v)->f.toFSRef=v);
		// TVECTOR_UPP_TYPE callbacks: on PowerPC the UPP is the routine's own TVector
		for(String t:new String[]{"HRNewCFURL","HRURLToFSRef","HRWasCFURLVisited"}){
			l.register(null,"New"+t+"UPP",(r,c)->ret(arg(0)));
			l.register(null,"Dispose"+t+"UPP",(r,c)->{});
		}
		l.register(null,"InvokeHRNewCFURLUPP",(r,c)->ret(call(arg(4),UPP_HR_NEW_CFURL,arg(0),arg(1),arg(2)&0xff,arg(3)))); // (url, targetString, addToHistory, refCon, upp)
		l.register(null,"InvokeHRURLToFSRefUPP",(r,c)->ret(call(arg(5),UPP_HR_URL_TO_FSREF,arg(0),arg(1),arg(2),arg(3)&0xffff,arg(4)))); // (rootString, linkString, fref, urlSourceType, refCon, upp)
		l.register(null,"InvokeHRWasCFURLVisitedUPP",(r,c)->ret(call(arg(2),UPP_HR_WAS_CFURL_VISITED,arg(0),arg(1))&0xff)); // (url, refCon, upp)
		// Apple Help: no Help Viewer on this VM
		for(String n:new String[]{"AHSearch","AHGotoMainTOC","AHGotoPage","AHLookupAnchor","AHGotoApplicationHelp"})l.register(null,n,(r,c)->ret(K_AH_INTERNAL_ERR));
	}
	private interface Body0 {int make(int out,int type,int port,int window) throws M68kException;}
	private interface Setter {void set(Ref f,int[] value);}
	/** HRRegister<X>UPP(upp, hrRef, refCon) and HRUnregister<X>UPP(hrRef). */
	private void register(PpcLibraries l,String kind,Setter s){
		l.register(null,"HRRegister"+kind+"UPP",(r,c)->{Ref f=ref(arg(1));if(f!=null){s.set(f,arg(0)==0?null:new int[]{arg(0),arg(2)});if(kind.contains("Visited")&&f.doc!=null)markVisited(f);}});
		l.register(null,"HRUnregister"+kind+"UPP",(r,c)->{Ref f=ref(arg(0));if(f!=null)s.set(f,null);});
	}
	private int anchor(Ref f,String name) throws M68kException {if(!scrollToAnchor(f,name))return PARAM_ERR;invalidate(f);return NO_ERR;} // no A NAME of that name
	/** The folder URL of the page (its URL up to the last "/"), else its base. */
	private static String rootOf(Ref f){String u=f.url.isEmpty()?f.base:stripFragment(f.url);int q=u.indexOf('?');if(q>=0)u=u.substring(0,q);int s=u.lastIndexOf('/');return s<0?u:u.substring(0,s+1);}
	private int outString(int p,String s) throws M68kException {if(p==0)return PARAM_ERR;memory.writeLong(p,cf().newString(s==null?"":s));return NO_ERR;}
}

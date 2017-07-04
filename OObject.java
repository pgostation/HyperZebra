import java.io.File;
import java.util.ArrayList;
import java.util.TreeSet;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;

public class OObject {
	OObject parent=null;
	String objectType="";
	int id;
	String name="";
	private String text="";
	boolean wrapFlag;
	ArrayList<String> scriptList;
	TreeSet<String> handlerList;
	ArrayList<String>[] stringList;
	ArrayList<TTalk.wordType>[] typeList;
	ArrayList<Integer> handlerLineList;
	private boolean visible=true;
	boolean enabled=true;
	
	int left=0; int top=0;
	int width=0; int height=0;

	public void clean(){
		parent = null;
		//objectType = null;
		scriptList = null;
		stringList = null;
		typeList = null;
		handlerLineList = null;
		handlerList = null;
	}
	
	public int getLeft(){
		return left;
	}
	public int getTop(){
		return top;
	}
	public int getWidth(){
		return width;
	}
	public int getHeight(){
		return height;
	}
	public boolean getVisible(){
		return visible;
	}
	public String getText(){
		return text;
	}
	public String getShortShortName(){
		String sName = "";
		if(objectType.equals("stack")) sName += "stack";
		if(objectType.equals("background")) sName += "bg";
		if(objectType.equals("card")) sName += "cd";
		if(objectType.equals("button")) sName += "btn";
		if(objectType.equals("field")) sName += "fld";
		if(name.equals("")) sName += " id "+id;
		else if(name.length()>9) sName += " \""+name.substring(0,8)+"...\"";
		else sName += " \""+name+"\"";
		return sName;
	}
	public String getShortName(){
		String sName = "";
		if(!objectType.equals("card") && parent!=null){
			if(parent.objectType.equals("card") || parent.objectType.equals("background")){
				sName += parent.CapitalType()+" ";
			}
		}
		if(name.equals("")) sName += CapitalType()+" id "+id;
		else sName += CapitalType()+" \""+name+"\"";
		return sName;
	}
	public String getLongName(){
		if(objectType.equals("stack")){
			//スタックの場合は Stack "スタックのパス"になる
			String path = ((OStack)this).file.getAbsolutePath();
			for(int i=0;i<path.length();i++){
				if(path.charAt(i)==File.separatorChar) path = path.substring(0,i)+":"+path.substring(i+1);
			}
			return "Stack \""+path+"\"";
		}
		if(parent!=null) return getShortName() +" of "+ parent.getShortName();
		return getShortName();
	}
	public String CapitalType(){
		return objectType.substring(0, 1).toUpperCase() + objectType.substring(1).toLowerCase();
	}

	public void setVisible(boolean in) {
		visible = in;
		if(parent!=null && 
				(parent.getClass()==OCard.class || parent.getClass()==OBackground.class)){
			((OCardBase)parent).changed = true;
		}
	}
	public void setText(String in){
		text = in;
	}
	public void setTextInternal(String in){
		text = in;
	}
	@SuppressWarnings("unchecked")
	public void setScript(String str) {
		scriptList.clear();
		
		String[] strArray = str.split("\n");
		for(int i=0; i<strArray.length; i++){
			scriptList.add(strArray[i]);
		}
		
		stringList = new ArrayList[scriptList.size()];
		typeList = new ArrayList[scriptList.size()];
		handlerList = null;
		handlerLineList = null;
		if(getClass()==OStack.class){
			((OStack)this).changed = true;
		}
		else if((getClass()==OCard.class || getClass()==OBackground.class)){
			((OCardBase)this).changed = true;
		}
		else if(parent!=null && 
				(parent.getClass()==OCard.class || parent.getClass()==OBackground.class)){
			((OCardBase)parent).changed = true;
		}
		
		wrapFlag = false;
	}
}





//------------------------------------------

class OHyperCard extends OObject {
	static OHyperCard hc = new OHyperCard();
	
	//メイン
	public OHyperCard() {
    	objectType="hypercard";
	}
}

class OTitlebar extends OObject {
	static OTitlebar titlebar = new OTitlebar();
	
	//メイン
	public OTitlebar() {
    	objectType="titlebar";
	}
}

class OMenubar extends OObject {
	static OMenubar menubar = new OMenubar();
	
	//メイン
	public OMenubar() {
    	objectType="menubar";
	}
}

class OWindow extends OObject {
	static OWindow cdwindow;
	static OWindow msgwindow;
	static ArrayList<OWindow>list = new ArrayList<OWindow>();
	JFrame frame;
	JDialog dlog;
	TMP3 mp3;
	GPictWindow gpw;
	
	//メイン
	public OWindow(JFrame inframe, boolean isCdWindow) {
    	objectType="window";
    	frame = inframe;
    	if(isCdWindow){
    		cdwindow = this;
    		this.name = "cd";
    	}
    	else this.name = inframe.getTitle();
    	
    	list.add(this);
	}

	//メイン(Msg)
	public OWindow(JDialog inframe) {
    	objectType="window";
    	dlog = inframe;
    	this.name = inframe.getTitle();
    	
    	list.add(this);
	}
	
	//メイン(Movieウィンドウ)
	public OWindow(TMP3 in_mp3) {
    	objectType="window";
    	mp3 = in_mp3;
    	this.name = mp3.name;
    	
    	list.add(this);
	}

	public OWindow(GPictWindow in_gpw) {
    	objectType="window";
    	gpw = in_gpw;
    	this.name = gpw.name;
    	
    	list.add(this);
	}
	
	public void Command(String cmdStr) {
		if(mp3!=null){
			if(cmdStr.equalsIgnoreCase("Play")){
				mp3.mp3play();
			}
		}
	}
	
	public void Close() {
		if(mp3!=null){
			mp3.mp3stop();
	    	list.remove(this);
		}
	}
}

class OMsg extends OObject {
	static OMsg msg = new OMsg();
	
	//メイン
	public OMsg() {
    	objectType="msg";
	}
}

class OToolWindow extends OObject {
	static OToolWindow toolwindow = new OToolWindow();
	
	//メイン
	public OToolWindow() {
    	objectType="tool window";
	}
}

class OPicture extends OObject {
	OCardBase parent;
	//メイン
	public OPicture(OCardBase prt) {
    	objectType="picture";
    	parent = prt;
	}
	
	@Override
	public boolean getVisible(){
		return parent.showPict;
	}
	
	@Override
	public void setVisible(boolean in){
		parent.showPict = in;
		if(PCARD.lockedScreen==false){
			parent.stack.pcard.repaint();
		}
	}
}

class OMenu extends OObject {
	JMenu menu;
	
	//メイン
	public OMenu(JMenu menu) {
    	objectType="menu";
    	this.menu = menu;
	}
}


class OMonitor extends OObject {
	static OMonitor monitor = new OMonitor();
	
	//メイン
	public OMonitor() {
    	objectType="monitor";
	}
}

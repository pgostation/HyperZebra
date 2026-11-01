import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.border.LineBorder;


class AuthTool {
	static authtoolInterface tool;

	//static boolean editBackground;はPaintToolのものを流用する
}


interface authtoolInterface{
	public void end();
}


class ButtonTool implements authtoolInterface{

	@Override
	public void end() {
		ButtonGUI.gui.isMouseDown = false;
		if (ButtonGUI.gui.dragTimer != null) ButtonGUI.gui.dragTimer.stop();
		ButtonGUI.gui.removeListenerFromParts();
		PCARDFrame.pc.mainPane.removeMouseListener(ButtonGUI.gui);
		PCARDFrame.pc.mainPane.removeMouseMotionListener(ButtonGUI.gui);
		ButtonGUI.gui.target = null;
		
		PCARDFrame.pc.mainPane.repaint();
	}
}


class FieldTool implements authtoolInterface{

	@Override
	public void end() {
		FieldGUI.gui.isMouseDown = false;
		if (FieldGUI.gui.dragTimer != null) FieldGUI.gui.dragTimer.stop();
		FieldGUI.gui.removeListenerFromParts();
		PCARDFrame.pc.mainPane.removeMouseListener(FieldGUI.gui);
		PCARDFrame.pc.mainPane.removeMouseMotionListener(FieldGUI.gui);
		FieldGUI.gui.target = null;
		
		PCARDFrame.pc.mainPane.repaint();
	}
}


class ButtonGUI implements MouseListener, MouseMotionListener
{
	static ButtonGUI gui = new ButtonGUI();
	int startx;
	int starty;
	int offsetx;
	int offsety;
	int lastx;
	int lasty;
	boolean sizeChange;
	boolean sizeleft;
	boolean sizetop;
	Component target;
	OButton tgtOBtn;
	boolean newButton;
	MyButtonDropListener droplistener = new MyButtonDropListener();
	boolean isMouseDown;
	AuthoringUndo.Snapshot dragBefore;
	javax.swing.Timer dragTimer;
	DropTarget mainpanedrop;
	LineBorder lineBorder = new LineBorder(Color.BLACK);
	
	public void addListenerToParts()
	{
		for(int i=0; i<PCARDFrame.pc.stack.curCard.btnList.size(); i++){
			OButton btn = PCARDFrame.pc.stack.curCard.btnList.get(i);

			if(btn.popup!=null) {
				PCARDFrame.pc.mainPane.remove(btn.popup);
				btn.popup=null;
				btn.btn = new RectButton(btn, PCARDFrame.pc.intl.getDialogText("Popup"));
				btn.btn.setBounds(btn.left,btn.top,btn.width,btn.height);
				btn.btn.setVisible(true);
				PCARDFrame.pc.mainPane.add(btn.btn,0);
			}
			
			btn.addListener(this);
			btn.addMotionListener(this);
			if(btn.getComponent()!=null){
				btn.drop = new DropTarget(btn.getComponent(), droplistener);
			}
			if(btn.style==1||btn.style==2){
				btn.btn.setBorder(lineBorder);
			}
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.bg.btnList.size(); i++){
			OButton btn = PCARDFrame.pc.stack.curCard.bg.btnList.get(i);
			
			if(btn.popup!=null) {
				PCARDFrame.pc.mainPane.remove(btn.popup);
				btn.popup=null;
				btn.btn = new RectButton(btn, btn.getText());
				btn.btn.setBounds(btn.left,btn.top,btn.width,btn.height);
				btn.btn.setVisible(true);
				PCARDFrame.pc.mainPane.add(btn.btn,0);
			}
			
			btn.addListener(this);
			btn.addMotionListener(this);
			if(btn.btn!=null){
				btn.drop = new DropTarget(btn.btn, droplistener);
			}
			if(btn.style==1||btn.style==2){
				btn.btn.setBorder(lineBorder);
			}
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.fldList.size(); i++){
			PCARDFrame.pc.stack.curCard.fldList.get(i).addListener(this);
			PCARDFrame.pc.stack.curCard.fldList.get(i).addMotionListener(this);
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.bg.fldList.size(); i++){
			PCARDFrame.pc.stack.curCard.bg.fldList.get(i).addListener(this);
			PCARDFrame.pc.stack.curCard.bg.fldList.get(i).addMotionListener(this);
		}
		mainpanedrop = new DropTarget(PCARDFrame.pc.mainPane, droplistener);
	}
	
	public void removeListenerFromParts()
	{
		if(PCARD.pc.stack.curCard==null) return; //no card open (the stack closed): no parts to take them off
		for(int i=0; i<PCARDFrame.pc.stack.curCard.btnList.size(); i++){
			OButton btn = PCARDFrame.pc.stack.curCard.btnList.get(i);
			btn.removeListener(this);
			btn.removeMotionListener(this);
			if(btn.drop!=null){
				btn.drop.removeDropTargetListener(ButtonGUI.gui.droplistener);
				btn.drop = null;
			}
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.bg.btnList.size(); i++){
			OButton btn = PCARDFrame.pc.stack.curCard.bg.btnList.get(i);
			btn.removeListener(this);
			btn.removeMotionListener(this);
			if(btn.drop!=null){
				btn.drop.removeDropTargetListener(ButtonGUI.gui.droplistener);
				btn.drop = null;
			}
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.fldList.size(); i++){
			PCARDFrame.pc.stack.curCard.fldList.get(i).removeListener(this);
			PCARDFrame.pc.stack.curCard.fldList.get(i).removeMotionListener(this);
		}
		for(int i=0; i<PCARDFrame.pc.stack.curCard.bg.fldList.size(); i++){
			PCARDFrame.pc.stack.curCard.bg.fldList.get(i).removeListener(this);
			PCARDFrame.pc.stack.curCard.bg.fldList.get(i).removeMotionListener(this);
		}
		if(mainpanedrop!=null){
			mainpanedrop.removeDropTargetListener(ButtonGUI.gui.droplistener);
			mainpanedrop = null;
		}
	}
	
    @Override
	public void mouseClicked(MouseEvent e) {
    }
    
    @Override
	@SuppressWarnings("unchecked")
	public void mousePressed(MouseEvent e) {
		//opt+cmdのピーク中は、ピーク側(btnOutlineListen)にクリックを渡す。
		//ここで処理してしまうと、cmd押下が「新規透明ボタンを作る」操作と解釈され、
		//ボタンツール選択中はピークでスクリプトエディタが開けなかった。
		//修飾キーはこのクリックに合わせる(離したのが別のウインドウで key[] に残っていることがある)。
		//key[]に残っていただけの Command-Option なら、ピークを終えてこのクリックはそのまま扱う。
		GUI.syncModifiers(e);
		if(PCARD.editMode!=0){
			if(GUI.isPeekClick(e)) return;
			GUI.endPeek();
		}
		dragBefore = AuthoringUndo.capture();

    	//dragThread側と全く同じ(MouseInfoベースの)方法でmainPane基準のマウス座標を
    	//求める。計測方法が食い違っていると、選択しただけ(ドラッグなし)でも
    	//dragThreadの最初の1回でボタン/フィールドが数ピクセルずれてしまう。
    	PointerInfo pointerInfo = MouseInfo.getPointerInfo();
    	int x = pointerInfo.getLocation().x-PCARDFrame.pc.mainPane.getX()-PCARDFrame.pc.getLocationOnScreen().x;
    	int y = pointerInfo.getLocation().y-PCARDFrame.pc.mainPane.getY()-PCARDFrame.pc.getLocationOnScreen().y-PCARDFrame.pc.getInsets().top+PCARDFrame.pc.getJMenuBar().getHeight();

    	if(target != null){
    		target.repaint();
    	}

    	newButton = false;
    	if((e.getModifiersEx()&InputEvent.META_DOWN_MASK)>0 ||
    			(e.getModifiersEx()&InputEvent.CTRL_DOWN_MASK)>0){ //新規透明ボタン
    		OCardBase cdbase = PCARDFrame.pc.stack.curCard;
			if(PaintTool.editBackground){
				cdbase = PCARDFrame.pc.stack.curCard.bg;
			}
        	newButton = true;
        	int newid = cdbase.newPartId();
        	OButton obtn = null;
    		obtn = new OButton(cdbase, newid);
			if(obtn != null){
				sizeChange = true;
				obtn.btn = new MyButton(obtn, "");
				((OCardBase)obtn.parent).partsList.add(obtn);
				((OCardBase)obtn.parent).btnList.add(obtn);
				target = obtn.btn;
	        	tgtOBtn = obtn;
				
				//外観変更
				{
					//x,yはmainPane上の実ピクセル(物理)座標。setBoundsは論理座標を
					//受け取ってscale()倍する仕様なので、ここでscale()で割り戻す。
					obtn.btn.setBounds(PCARD.toLogical(x),PCARD.toLogical(y),2,2);
					obtn.style = 1;
					obtn.btn.setContentAreaFilled(false);
					obtn.btn.setBorderPainted(false);
					obtn.btn.setBorder(null);
					PCARDFrame.pc.mainPane.add(obtn.btn, 0);
				}
			}
    	}
    	else if((e.getModifiersEx()&InputEvent.ALT_DOWN_MASK)>0){
    		OCardBase cdbase = PCARDFrame.pc.stack.curCard;
			if(PaintTool.editBackground){
				cdbase = PCARDFrame.pc.stack.curCard.bg;
			}
        	newButton = true;
        	int newid = cdbase.newPartId();
        	OButton obtn = null;
			obtn = new OButton(cdbase, newid);
			((OCardBase)obtn.parent).partsList.add(obtn);
			((OCardBase)obtn.parent).btnList.add(obtn);
			if(obtn != null){
	    		OButton obtn2 = null;
	    		Component target = (Component) e.getSource();
	        	if(target.getClass() == MyButton.class) obtn2 = ((MyButton)target).btnData;
	        	if(target.getClass() == RoundedCornerButton.class) obtn2 = ((RoundedCornerButton)target).btnData;
	        	if(target.getClass() == RectButton.class) obtn2 = ((RectButton)target).btnData;
	        	if(target.getClass() == RoundButton.class) obtn2 = ((RoundButton)target).btnData;
	        	if(target.getClass() == MyRadio.class) obtn2 = ((MyRadio)target).btnData;
	        	if(target.getClass() == MyCheck.class) obtn2 = ((MyCheck)target).btnData;
	        	if(target.getClass() == MyPopup.class) obtn2 = ((MyPopup)target).btnData;
	        	
				obtn.card = obtn2.card;
				obtn.color = obtn2.color;
				obtn.bgColor = obtn2.bgColor;
				obtn.hiliteColor = obtn2.hiliteColor;
				obtn.group = obtn2.group;
				obtn.check_hilite = obtn2.check_hilite;
				obtn.setAutoHilite(obtn2.getAutoHilite());
				if(obtn2.getAutoHilite()){
					obtn.setHilite(false);
				}else{
					obtn.setHilite(obtn2.getHilite());
				}
				obtn.icon = obtn2.icon;
				obtn.left = obtn2.left;
				obtn.top = obtn2.top;
				obtn.width = obtn2.width;
				obtn.height = obtn2.height;
				obtn.sharedHilite = obtn2.sharedHilite;
				obtn.showName = obtn2.showName;
				obtn.style = obtn2.style;
				obtn.textAlign = obtn2.textAlign;
				obtn.textFont = obtn2.textFont;
				obtn.textHeight = obtn2.textHeight;
				obtn.textSize = obtn2.textSize;
				obtn.textStyle = obtn2.textStyle;
				obtn.titleWidth = obtn2.titleWidth;
				
				obtn.parent = obtn2.parent;
				//obtn.id = obtn2;
				obtn.name = obtn2.name;
				obtn.panelId = obtn2.panelId;
				obtn.setText(obtn2.getText());
				obtn.scriptList = (ArrayList<String>)obtn2.scriptList.clone();
				obtn.osaScript = obtn2.osaScript;
				obtn.stringList = new ArrayList[obtn2.scriptList.size()];
				obtn.typeList = new ArrayList[obtn2.scriptList.size()];
				obtn.enabled = obtn2.enabled;
				obtn.setVisible(obtn2.getVisible());
				
				//外観変更
				{
					OButton.buildOButton(obtn);
		        	tgtOBtn = obtn;
					this.target = obtn.getComponent();
					PCARDFrame.pc.mainPane.add(this.target, 0);
				}
			}
    	}
    	else if(e.getSource().getClass() == MyButton.class ||
    			e.getSource().getClass() == RoundedCornerButton.class ||
    			e.getSource().getClass() == RectButton.class ||
    			e.getSource().getClass() == RoundButton.class ||
    			e.getSource().getClass() == MyRadio.class ||
    			e.getSource().getClass() == MyCheck.class ||
    			e.getSource().getClass() == MyPopup.class)
    	{
    		target = (Component) e.getSource();
        	if(target!=null){
        		if((x < target.getX()+8 || x > target.getX()+target.getWidth()-8) &&
        			(y < target.getY()+8 || y > target.getY()+target.getHeight()-8))
        		{
        			sizeChange = true;
        			sizeleft=(x < target.getX()+8);
        			sizetop=(y < target.getY()+8);
        		}
        		else{
        	    	sizeChange = false;
        		}
        	}
    	}
    	else{
    		target = null;
    	}
    	startx = x; //最初の位置を記憶
    	starty = y;
    	//ポインタと部品のオフセットを記憶。x,yと同じ(MouseInfoベースの)座標系で
    	//求めることで、dragThreadの最初の1回でのずれを防ぐ。
    	offsetx = (target!=null) ? x-target.getX() : e.getX();
    	offsety = (target!=null) ? y-target.getY() : e.getY();
    	lastx = x; //サイズの変更用に前回の位置を記憶
    	lasty = y;

		isMouseDown = true;

    	if(target!=null){
		//ドラッグ更新と確定をEDT上で順番に処理し、確定後の座標更新を防ぐ
		if (dragTimer != null) dragTimer.stop();
		dragTimer = new javax.swing.Timer(16, event -> new dragThread().run());
		dragTimer.start();
    	}
    }
    
    @Override
	public void mouseReleased(MouseEvent e) {
		if (dragTimer != null) dragTimer.stop();
		try {

    	/*Rectangle r = ((Component)e.getSource()).getBounds();
    	int x = e.getX()+r.x;
    	int y = e.getY()+r.y;
    	if((Component)e.getSource() == PCARDFrame.pc.getRootPane()){
    		x -= PCARDFrame.pc.toolbar.getTWidth();
    		y -= PCARDFrame.pc.toolbar.getTHeight()+PCARDFrame.pc.getInsets().top+PCARDFrame.pc.getJMenuBar().getHeight();
    	}*/

    	isMouseDown = false;
    	
    	if(target!=null){
    		drawSelectBorder(target);
    		
    		OButton obtn = null;
        	if(target.getClass() == MyButton.class) obtn = ((MyButton)target).btnData;
        	if(target.getClass() == RoundedCornerButton.class) obtn = ((RoundedCornerButton)target).btnData;
        	if(target.getClass() == RectButton.class) obtn = ((RectButton)target).btnData;
        	if(target.getClass() == RoundButton.class) obtn = ((RoundButton)target).btnData;
        	if(target.getClass() == MyRadio.class) obtn = ((MyRadio)target).btnData;
        	if(target.getClass() == MyCheck.class) obtn = ((MyCheck)target).btnData;
        	if(target.getClass() == MyPopup.class) obtn = ((MyPopup)target).btnData;
        	if(obtn!=null){
            	//ダブルクリック判定
            	if (!newButton && e.getClickCount() >= 2){
            		//Shift-ダブルクリックはスクリプトを開く(HyperCard)
            		if(e.isShiftDown() && StackProtection.canOpenScripts()) ScriptEditor.openScriptEditor(PCARDFrame.pc, obtn);
            		else AuthDialog.openAuthDialog(PCARDFrame.pc, "button", obtn);
            	}
            	else{
            		tgtOBtn = obtn;
            		//target.getBounds()はSwing上の実(物理)座標なので、
            		//論理座標であるobtn.left/top/width/heightに書き戻す際はscale()で割る。
					int oldLeft=obtn.left, oldTop=obtn.top;
	        		obtn.left = PCARD.toLogical(target.getBounds().x);
	        		obtn.top = PCARD.toLogical(target.getBounds().y);
	        		obtn.width = (PCARD.toLogical(target.getBounds().x+target.getBounds().width)-PCARD.toLogical(target.getBounds().x));
	        		obtn.height = (PCARD.toLogical(target.getBounds().y+target.getBounds().height)-PCARD.toLogical(target.getBounds().y));
					if(!sizeChange) ((OCardBase)obtn.parent).movePanelPeers(obtn, obtn.left-oldLeft, obtn.top-oldTop);
            	}
            	
            	if(newButton){
        			OCard.reloadCurrentCard();

            		OCardBase cdbase = PCARDFrame.pc.stack.curCard;
        			if(PaintTool.editBackground){
        				cdbase = PCARDFrame.pc.stack.curCard.bg;
        			}
        			OButton newbtn = cdbase.GetBtnbyNum(cdbase.btnList.size());
        			target = newbtn.getComponent();
            		drawSelectBorder(newbtn.getComponent());
            		//HyperCardと同様、作られたボタン自身へ newButton を送る
            		TTalk.CallMessage("newButton", newbtn);
            	}else{
    	    		target.setEnabled(tgtOBtn.enabled);
            	}
        	}

    		if(AuthDialog.authDialog != null){
    			AuthDialog.openAuthDialog(PCARDFrame.pc, "button", obtn);
    		}
    	}else{
    		tgtOBtn = null;
    	}

		} finally { AuthoringUndo.commit(dragBefore); dragBefore = null; }
	}
    
    @Override
	public void mouseEntered(MouseEvent e) {
    }
    @Override
	public void mouseExited(MouseEvent e) {
    }
    
	private class dragThread implements Runnable
	{
		@Override
		public void run(){
			if(isMouseDown){
		        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
		        int x = pointerInfo.getLocation().x-PCARDFrame.pc.mainPane.getX()-PCARDFrame.pc.getLocationOnScreen().x;
		        int y = pointerInfo.getLocation().y-PCARDFrame.pc.mainPane.getY()-PCARDFrame.pc.getLocationOnScreen().y-PCARDFrame.pc.getInsets().top+PCARDFrame.pc.getJMenuBar().getHeight();

		    	if(target != null){
			    	Rectangle newr = target.getBounds();
		    		if(!sizeChange){
			    		//移動
		    			newr.x = x-offsetx;
			    		newr.y = y-offsety;
		    			if(GUI.key[11]>1){ //SHIFT
			    			if(Math.abs(x-startx)<Math.abs(y-starty)){
			    				newr.x = startx-offsetx;
			    			}else{
			    				newr.y = starty-offsety;
			    			}
		    			}
			    		//newrはここまで物理座標。setBoundsは論理座標を受け取ってscale()倍する
			    		//仕様なので、ここでscale()で割り戻してから渡す。
			    		{
							target.setBounds(PCARD.toLogical(newr.x), PCARD.toLogical(newr.y), (PCARD.toLogical(newr.x+newr.width)-PCARD.toLogical(newr.x)), (PCARD.toLogical(newr.y+newr.height)-PCARD.toLogical(newr.y)));
							//所属 Panel はドラッグ中も一緒に動かす。
							OButton moving=null;
							if(target instanceof MyButton) moving=((MyButton)target).btnData;
							else if(target instanceof RoundedCornerButton) moving=((RoundedCornerButton)target).btnData;
							else if(target instanceof RectButton) moving=((RectButton)target).btnData;
							else if(target instanceof RoundButton) moving=((RoundButton)target).btnData;
							else if(target instanceof MyRadio) moving=((MyRadio)target).btnData;
							else if(target instanceof MyCheck) moving=((MyCheck)target).btnData;
							else if(target instanceof MyPopup) moving=((MyPopup)target).btnData;
							if(moving!=null && moving.panelId!=0 &&
								(moving.left!=PCARD.toLogical(newr.x) || moving.top!=PCARD.toLogical(newr.y)))
								moving.setTopLeft(PCARD.toLogical(newr.x),PCARD.toLogical(newr.y));
					}
			    		if(target instanceof MyButton){
			    			((MyButton)target).setAutoHilite(false);
			    		}
		    		}
		    		else{
			    		//サイズ変更
			    		if(sizeleft) {
			    			newr.x += x - lastx;
			    			newr.width -= x - lastx;
			    		}
			    		else{
			    			newr.width += x - lastx;
			    		}
			    		if(sizetop){
				    		newr.height -= y - lasty;
			    			newr.y += y - lasty;
			    		}
			    		else{
				    		newr.height += y - lasty;
			    		}
			    		//newrは物理座標。setBoundsは論理座標を受け取ってscale()倍する仕様。
			    		target.setBounds(PCARD.toLogical(newr.x), PCARD.toLogical(newr.y), (PCARD.toLogical(newr.x+newr.width)-PCARD.toLogical(newr.x)), (PCARD.toLogical(newr.y+newr.height)-PCARD.toLogical(newr.y)));
			    		if(target instanceof MyButton){
			    			((MyButton)target).setAutoHilite(false);
			    		}

			    		OButton obtn = null;
			        	if(target.getClass() == MyButton.class) obtn = ((MyButton)target).btnData;
			        	if(target.getClass() == RoundedCornerButton.class) obtn = ((RoundedCornerButton)target).btnData;
			        	if(target.getClass() == RectButton.class) obtn = ((RectButton)target).btnData;
			        	if(target.getClass() == RoundButton.class) obtn = ((RoundButton)target).btnData;
			        	if(target.getClass() == MyRadio.class) obtn = ((MyRadio)target).btnData;
			        	if(target.getClass() == MyCheck.class) obtn = ((MyCheck)target).btnData;
			        	if(target.getClass() == MyPopup.class) obtn = ((MyPopup)target).btnData;
			        	//obtn.width/heightは論理値なので、物理座標のnewr.width/heightをscale()で割る。
			        	obtn.width = (PCARD.toLogical(newr.x+newr.width)-PCARD.toLogical(newr.x));
			    		obtn.height = (PCARD.toLogical(newr.y+newr.height)-PCARD.toLogical(newr.y));
		    		}
		    	}
	
		    	lasty += (y-lasty);
		    	lastx += (x-lastx);
			}
		}
	}
    
	@Override
	public void mouseDragged(MouseEvent e) {
    	/*Rectangle r = ((Component)e.getSource()).getBounds();
    	int x = e.getX()+r.x;
    	int y = e.getY()+r.y;
    	if((Component)e.getSource() == PCARD.pc.getRootPane()){
    		x -= PCARD.pc.stack.toolbar.getTWidth();
    		y -= PCARD.pc.stack.toolbar.getTHeight()+PCARD.pc.getInsets().top;
    	}
    	
    	if(target != null){
	    	Rectangle newr = target.getBounds();
    		if(!sizeChange){
	    		//移動
    			newr.x = x-offsetx;
	    		newr.y = y-offsety;
    			if((e.getModifiersEx()&InputEvent.SHIFT_DOWN_MASK)>0){
	    			if(Math.abs(x-startx)<Math.abs(y-starty)){
	    				newr.x = startx-offsetx;
	    			}else{
	    				newr.y = starty-offsety;
	    			}
    			}
	    		target.setBounds(newr);
	    		target.setEnabled(false);
    		}
    		else{
	    		//サイズ変更
    			newr.width += x - lastx;
	    		newr.height += y - lasty;
	    		target.setBounds(newr);
	    		target.setEnabled(false);
    		}
    	}

    	lasty += (y-lasty);
    	lastx += (x-lastx);*/
	}
	
	@Override
	public void mouseMoved(MouseEvent e) {
		if(target!=null) drawSelectBorder(target);
	}
	
	public void keyDown(){
		if (!AuthoringUndo.editKeyDown()) return;
		AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

		if(tgtOBtn==null){
			return;
		}
		
		if(GUI.key[20]>0 || GUI.key[21]>0){ //BACKSPACE or DELETE
			GMenuBrowse.removeCardDataForBgPart(tgtOBtn);
			((OCardBase)tgtOBtn.parent).partsList.remove(tgtOBtn);
			((OCardBase)tgtOBtn.parent).btnList.remove(tgtOBtn);
			if(tgtOBtn.getComponent()!=null){
				PCARDFrame.pc.mainPane.remove(tgtOBtn.getComponent());
			}
			target = null;
			tgtOBtn = null;
			
			OCard.reloadCurrentCard();
		}
		else if(GUI.key[14]==0 && (GUI.key[0]>0 || GUI.key[1]>0 || GUI.key[2]>0 || GUI.key[3]>0)){ //ARROW
			if(GUI.key[0]>0){
				tgtOBtn.setTopLeft(tgtOBtn.left, tgtOBtn.top-1);
			}
			else if(GUI.key[1]>0){
				tgtOBtn.setTopLeft(tgtOBtn.left, tgtOBtn.top+1);
			}
			else if(GUI.key[2]>0){
				tgtOBtn.setTopLeft(tgtOBtn.left-1, tgtOBtn.top);
			}
			else if(GUI.key[3]>0){
				tgtOBtn.setTopLeft(tgtOBtn.left+1, tgtOBtn.top);
			}
		}
		else if(GUI.key[14]>0 && (GUI.key[0]>0 || GUI.key[1]>0 || GUI.key[2]>0 || GUI.key[3]>0)){ //ARROW
			if(GUI.key[0]>0 && tgtOBtn.height>0){
				tgtOBtn.setRect(tgtOBtn.left, tgtOBtn.top, 
						tgtOBtn.left+tgtOBtn.width, tgtOBtn.top+tgtOBtn.height-1);
			}
			else if(GUI.key[1]>0){
				tgtOBtn.setRect(tgtOBtn.left, tgtOBtn.top, 
						tgtOBtn.left+tgtOBtn.width, tgtOBtn.top+tgtOBtn.height+1);
			}
			else if(GUI.key[2]>0 && tgtOBtn.width>0){
				tgtOBtn.setRect(tgtOBtn.left, tgtOBtn.top, 
						tgtOBtn.left+tgtOBtn.width-1, tgtOBtn.top+tgtOBtn.height);
			}
			else if(GUI.key[3]>0){
				tgtOBtn.setRect(tgtOBtn.left, tgtOBtn.top, 
						tgtOBtn.left+tgtOBtn.width+1, tgtOBtn.top+tgtOBtn.height);
			}
		}
		else if(GUI.key[14]>0 && (GUI.key['+']>0)){ //テンキーの+はショートカットが効かないのでここに
			OButton obtn = tgtOBtn;
        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)obtn.parent).stepInStack(obtn, true);

			OCard.reloadCurrentCard();
			
			//obtnは生きているがtargetはいなくなるので取り直す
			ButtonGUI.gui.target = obtn.getComponent();
		}
		else if(GUI.key[14]>0 && (GUI.key['-']>0)){ //テンキーの-はショートカットが効かないのでここに
			OButton obtn = tgtOBtn;
        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)obtn.parent).stepInStack(obtn, false);
			
			OCard.reloadCurrentCard();
			
			//obtnは生きているがtargetはいなくなるので取り直す
			ButtonGUI.gui.target = obtn.getComponent();
		}

		} finally { AuthoringUndo.commit(before); }
	}
	
	//以前はPCARDFrame.pc.mainPane.getGraphics()に対して直接描画していたため、
	//部品を移動した際にSwingの通常の再描画で消されず、古い位置の枠が画面に
	//残ってしまっていた。今はMyPanel.paint()がこのメソッドを毎回呼び出して
	//現在位置に枠を描き直す仕組みにしたので、再描画のたびに古い枠は自動的に
	//消える。呼び出し側は「枠を最新状態にしたい」タイミングで再描画を要求する
	//だけでよい。
	static void drawSelectBorder(Component target){
		ensureAntsTimerStarted();
		if(PCARDFrame.pc!=null && PCARDFrame.pc.mainPane!=null){
			PCARDFrame.pc.mainPane.repaint();
		}
	}

	static final float[] ANTS_DASH = {4f, 4f};

	//現在アリの行進アニメーションが進んでいるコマ数(80ms毎に1進む)
	static volatile int antsPhase = 0;
	private static javax.swing.Timer antsTimer;

	//本物のHyperCardのように、ボタン/フィールドツールで選択中の部品の枠を
	//白黒交互の点線(マーチングアンツ)でアニメーションさせるためのタイマー。
	//一度だけ起動し、以後は選択対象が無いときは何もしない(負荷はごく小さい)。
	static synchronized void ensureAntsTimerStarted(){
		if(antsTimer!=null) return;
		antsTimer = new javax.swing.Timer(80, e -> {
			antsPhase++;
			Component t = currentAuthToolTarget();
			if(t!=null && PCARDFrame.pc!=null && PCARDFrame.pc.mainPane!=null){
				PCARDFrame.pc.mainPane.repaint();
			}
		});
		antsTimer.start();
	}

	//現在ボタンツール/フィールドツールで選択されている部品(無ければnull)
	static Component currentAuthToolTarget(){
		if(AuthTool.tool instanceof ButtonTool && ButtonGUI.gui.target!=null){
			return ButtonGUI.gui.target;
		}
		if(AuthTool.tool instanceof FieldTool && FieldGUI.gui.target!=null){
			return FieldGUI.gui.target;
		}
		return null;
	}

	//選択枠(マーチングアンツ)を実際に描画する。MyPanel.paint()から、通常の
	//描画が全て終わった後に毎回呼ばれるので、部品を動かしても古い枠が
	//残らない。
	static void paintAnts(Graphics2D g, Component target){
		if(target==null || !target.isShowing()) return;
		Rectangle r2 = target.getBounds();
		if(r2.width>0) r2.width--;
		if(r2.height>0) r2.height--;
		Stroke oldStroke = g.getStroke();
		Color oldColor = g.getColor();
		float phase = (antsPhase%8);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, ANTS_DASH, phase));
		g.draw(r2);
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, ANTS_DASH, phase+4f));
		g.draw(r2);
		g.setColor(oldColor);
		g.setStroke(oldStroke);
	}

	static Rectangle localOutlineBounds(int componentWidth, int componentHeight){
		return new Rectangle(0, 0, Math.max(0, componentWidth-1), Math.max(0, componentHeight-1));
	}
}


class MyButtonDropListener extends DropTargetAdapter {
	@Override
	public void drop(DropTargetDropEvent e) {
		AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

		try {
			Transferable transfer = e.getTransferable();
			if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
			{
				e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
				@SuppressWarnings("unchecked")
				List<File> fileList = 
					(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));
				for(int i=0; i<fileList.size(); i++){
					String path = fileList.get(i).toString();
					String ext = "";
					BufferedImage bi = null;
					try{
						ext = ".ppm";
						bi = PictureFile.loadPbm(path);
						if(bi==null){
							if(path.lastIndexOf(".")>=0){
								ext = path.substring(path.lastIndexOf("."), path.length());
							}
							else{
								ext = "";
							}
							bi = javax.imageio.ImageIO.read(new File(path));
						}
						if(bi==null){
							ext = ".pict";
							bi = PictureFile.loadPICT(path);
						}
					} catch (IOException e2) {
						e2.printStackTrace();
					}
					if(bi!=null){
						String name = new File(path).getName();
						Pattern p = Pattern.compile("([0-9]{1,6})([^0-9])");
						Matcher m = p.matcher(name);
						int baseid = 1000;
						if(m.find()){
							baseid = Integer.valueOf(m.group(1));
						}
						int iconid = PCARDFrame.pc.stack.rsrc.getNewResourceId("ICON", baseid);
						//ファイルをコピー
						String newFileName = "ICON_"+iconid+ext;
						String newFilePath = PCARDFrame.pc.stack.file.getParent()+File.separatorChar+newFileName;
						FileChannel srcChannel = null;
						FileChannel destChannel = null;
						try {
							srcChannel = new FileInputStream(path).getChannel();
							destChannel = new FileOutputStream(newFilePath).getChannel();
							srcChannel.transferTo(0, srcChannel.size(), destChannel);
						} finally {
							srcChannel.close();
							destChannel.close();
						}
						//リソースに追加
						PCARDFrame.pc.stack.rsrc.addResource(iconid, "ICON", name, newFileName);
						//ボタンのアイコンに設定
						/*Component component = ((DropTarget)e.getSource()).getComponent();
						OButton obtn = ((MyButton)component).btnData;*/
						OCardBase cdbase = PCARDFrame.pc.stack.curCard;
						if(PaintTool.editBackground){
							 cdbase = PCARDFrame.pc.stack.curCard.bg;
						}
						OButton obtn = new OButton(cdbase, cdbase.newPartId()); //a part id of its own (was the icon's id)
						obtn.style = 1;
						obtn.left = PCARD.pc.stack.width/2-32;
						obtn.top = PCARD.pc.stack.height/2-10;
						obtn.btn = new MyButton(obtn, "");
						cdbase.partsList.add(obtn);
						cdbase.btnList.add(obtn);
						if(obtn.width < bi.getWidth()){
							obtn.left -= (bi.getWidth() - obtn.width)/2;
							obtn.width = bi.getWidth();
						}
						int offset = 0;
						//if(obtn.showName) offset = 12;
						if(obtn.height < bi.getHeight()+offset){
							obtn.top -= (bi.getHeight()+offset - obtn.height)/2;
							obtn.height = bi.getHeight() + offset;
						}
						obtn.btn.setBounds(obtn.left, obtn.top, obtn.width, obtn.height);
						obtn.setIcon(iconid);
					}
					else{
			    		new GDialog(PCARDFrame.pc, PCARDFrame.pc.intl.getDialogText("Could't open the file."),null,"OK",null,null);
			    	}
				}
				OCard.reloadCurrentCard();
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		} finally { AuthoringUndo.commit(before); }
	}
}

class FieldGUI implements MouseListener, MouseMotionListener
{
	static FieldGUI gui = new FieldGUI();
	int startx;
	int starty;
	int offsetx;
	int offsety;
	int lastx;
	int lasty;
	boolean sizeChange;
	boolean sizeleft;
	boolean sizetop;
	Component target;
	OField tgtOFld;
	boolean newField;
	MyFieldDropListener droplistener = new MyFieldDropListener();
	boolean isMouseDown;
	AuthoringUndo.Snapshot dragBefore;
	javax.swing.Timer dragTimer;
	LineBorder lineBorder = new LineBorder(Color.BLACK);
	
	public void addListenerToParts()
	{
		for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
			OField fld = PCARD.pc.stack.curCard.fldList.get(i);

			if(fld.scrollPane!=null) {
				PCARDFrame.pc.mainPane.remove(fld.scrollPane);
				fld.scrollPane=null;
				fld.fld = createToolTextArea(fld);
				PCARDFrame.pc.mainPane.add(fld.fld,0);
			}
			
			fld.addListener(this);
			fld.addMotionListener(this);
			
			if(fld.fld!=null){
				fld.drop = new DropTarget(fld.getComponent(), droplistener);
				fld.fld.setFocusable(false);
				fld.fld.setEnabled(false);
				if(fld.style==1||fld.style==2){
					fld.fld.setBorder(lineBorder);
				}
			}
		}
		for(int i=0; i<PCARD.pc.stack.curCard.bg.fldList.size(); i++){
			OField fld = PCARD.pc.stack.curCard.bg.fldList.get(i);

			if(fld.scrollPane!=null) {
				PCARDFrame.pc.mainPane.remove(fld.scrollPane);
				fld.scrollPane=null;
				fld.fld = createToolTextArea(fld);
				PCARDFrame.pc.mainPane.add(fld.fld,0);
			}
			
			fld.addListener(this);
			fld.addMotionListener(this);
			
			if(fld.fld!=null){
				fld.drop = new DropTarget(fld.fld, droplistener);
				fld.fld.setFocusable(false);
				fld.fld.setEnabled(false);
				if(fld.style==1||fld.style==2){
					fld.fld.setBorder(lineBorder);
				}
			}
		}
		for(int i=0; i<PCARD.pc.stack.curCard.btnList.size(); i++){
			PCARD.pc.stack.curCard.btnList.get(i).addListener(this);
			PCARD.pc.stack.curCard.btnList.get(i).addMotionListener(this);
		}
		for(int i=0; i<PCARD.pc.stack.curCard.bg.btnList.size(); i++){
			PCARD.pc.stack.curCard.bg.btnList.get(i).addListener(this);
			PCARD.pc.stack.curCard.bg.btnList.get(i).addMotionListener(this);
		}
	}

	static MyTextArea createToolTextArea(OField fld)
	{
		MyTextArea textArea = new MyTextArea(fld.getText());
		textArea.fldData = fld;
		textArea.setBounds(fld.left, fld.top, fld.width, fld.height);
		textArea.setVisible(fld.isShown());
		return textArea;
	}
	
	public void removeListenerFromParts()
	{
		if(PCARD.pc.stack.curCard==null) return; //no card open (the stack closed): no parts to take them off
		for(int i=0; i<PCARD.pc.stack.curCard.fldList.size(); i++){
			OField fld = PCARD.pc.stack.curCard.fldList.get(i);
			fld.removeListener(this);
			fld.removeMotionListener(this);
			if(fld.drop!=null){
				fld.drop.removeDropTargetListener(FieldGUI.gui.droplistener);
				fld.drop = null;
			}
		}
		for(int i=0; i<PCARD.pc.stack.curCard.bg.fldList.size(); i++){
			OField fld = PCARD.pc.stack.curCard.bg.fldList.get(i);
			fld.removeListener(this);
			fld.removeMotionListener(this);
			if(fld.drop!=null){
				fld.drop.removeDropTargetListener(FieldGUI.gui.droplistener);
				fld.drop = null;
			}
		}
		for(int i=0; i<PCARD.pc.stack.curCard.btnList.size(); i++){
			PCARD.pc.stack.curCard.btnList.get(i).removeListener(this);
			PCARD.pc.stack.curCard.btnList.get(i).removeMotionListener(this);
		}
		for(int i=0; i<PCARD.pc.stack.curCard.bg.btnList.size(); i++){
			PCARD.pc.stack.curCard.bg.btnList.get(i).removeListener(this);
			PCARD.pc.stack.curCard.bg.btnList.get(i).removeMotionListener(this);
		}
	}
	
    public void mouseClicked(MouseEvent e) {
    }
    
    @SuppressWarnings("unchecked")
	public void mousePressed(MouseEvent e) {
		//ボタンツール側と同じ理由で、ピーク中はピーク側にクリックを渡す
		//修飾キーはこのクリックに合わせる(離したのが別のウインドウで key[] に残っていることがある)。
		//key[]に残っていただけの Command-Option なら、ピークを終えてこのクリックはそのまま扱う。
		GUI.syncModifiers(e);
		if(PCARD.editMode!=0){
			if(GUI.isPeekClick(e)) return;
			GUI.endPeek();
		}
		dragBefore = AuthoringUndo.capture();

    	//dragThread側と全く同じ(MouseInfoベースの)方法でmainPane基準のマウス座標を
    	//求める。計測方法が食い違っていると、選択しただけ(ドラッグなし)でも
    	//dragThreadの最初の1回でボタン/フィールドが数ピクセルずれてしまう。
    	PointerInfo pointerInfo = MouseInfo.getPointerInfo();
    	int x = pointerInfo.getLocation().x-PCARD.pc.mainPane.getX()-PCARD.pc.getLocationOnScreen().x;
    	int y = pointerInfo.getLocation().y-PCARD.pc.mainPane.getY()-PCARD.pc.getLocationOnScreen().y-PCARD.pc.getInsets().top+PCARD.pc.getJMenuBar().getHeight();

    	if(target != null){
    		target.repaint();
    	}

    	newField = false;
    	if((e.getModifiersEx()&InputEvent.META_DOWN_MASK)>0 ||
    			(e.getModifiersEx()&InputEvent.CTRL_DOWN_MASK)>0){ //新規フィールド
    		OCardBase cdbase = PCARD.pc.stack.curCard;
			if(PaintTool.editBackground){
				cdbase = PCARD.pc.stack.curCard.bg;
			}
			newField = true;
        	int newid = cdbase.newPartId();
        	OField ofld = null;
    		ofld = new OField(cdbase, newid);
			if(ofld != null){
				sizeChange = true;
				ofld.fld = new MyTextArea("");
				ofld.fld.fldData = ofld;
				((OCardBase)ofld.parent).partsList.add(ofld);
				((OCardBase)ofld.parent).fldList.add(ofld);
				target = ofld.fld;
	        	tgtOFld = ofld;
				
				//外観変更
				{
					//x,yはmainPane上の実ピクセル(物理)座標。setBoundsは論理座標を
					//受け取ってscale()倍する仕様なので、ここでscale()で割り戻す。
					ofld.fld.setBounds(PCARD.toLogical(x),PCARD.toLogical(y),2,2);
					ofld.style = 1;
					ofld.enabled = false;
					ofld.fld.setOpaque(false);
					PCARD.pc.mainPane.add(ofld.fld, 0);
				}
			}
    	}
    	else if((e.getModifiersEx()&InputEvent.ALT_DOWN_MASK)>0){
    		OCardBase cdbase = PCARD.pc.stack.curCard;
			if(PaintTool.editBackground){
				cdbase = PCARD.pc.stack.curCard.bg;
			}
			newField = true;
        	int newid = cdbase.newPartId();
        	OField ofld = null;
			ofld = new OField(cdbase, newid);
			((OCardBase)ofld.parent).partsList.add(ofld);
			((OCardBase)ofld.parent).fldList.add(ofld);
			if(ofld != null){
	    		OField obtn2 = null;
	    		Component target = (Component) e.getSource();
	        	if(target.getClass() == MyTextArea.class) obtn2 = ((MyTextArea)target).fldData;
	        	else if(target.getClass() == MyScrollPane.class) obtn2 = ((MyScrollPane)target).fldData;
	        	ofld.card = obtn2.card;
	        	ofld.color = obtn2.color;
	        	ofld.bgColor = obtn2.bgColor;
	        	ofld.left = obtn2.left;
	        	ofld.top = obtn2.top;
	        	ofld.width = obtn2.width;
	        	ofld.height = obtn2.height;
	        	ofld.style = obtn2.style;
	        	ofld.textAlign = obtn2.textAlign;
	        	ofld.textFont = obtn2.textFont;
	        	ofld.textHeight = obtn2.textHeight;
				ofld.textSize = obtn2.textSize;
				ofld.textStyle = obtn2.textStyle;
				ofld.autoSelect = obtn2.autoSelect;
				ofld.fixedLineHeight = obtn2.fixedLineHeight;
				ofld.autoTab = obtn2.autoTab;
				ofld.dontSearch = obtn2.dontSearch;
				ofld.dontWrap = obtn2.dontWrap;
				ofld.scroll = obtn2.scroll;
				ofld.sharedText = obtn2.sharedText;
				ofld.showLines = obtn2.showLines;
				ofld.hilite = obtn2.hilite;
				ofld.multipleLines = obtn2.multipleLines;
				ofld.wideMargins = obtn2.wideMargins;
				ofld.tableStyle = obtn2.tableStyle;
				ofld.outlineStyle = obtn2.outlineStyle;
				ofld.outlineLevel = obtn2.outlineLevel;
				ofld.columnWidths = obtn2.columnWidths;
				ofld.columnNames = obtn2.columnNames;
				ofld.sortable = obtn2.sortable;
				ofld.gridLines = obtn2.gridLines;
				if(obtn2.styleList!=null){
					ofld.styleList = new ArrayList<styleClass>();
					for(styleClass run : obtn2.styleList)
						ofld.styleList.add(new styleClass(run.textPosition, run.styleId));
				}
				
				ofld.parent = obtn2.parent;
				ofld.name = obtn2.name;
				ofld.panelId = obtn2.panelId;
				ofld.setText(obtn2.getText());
				ofld.scriptList = (ArrayList<String>)obtn2.scriptList.clone();
				ofld.osaScript = obtn2.osaScript;
				ofld.stringList = new ArrayList[obtn2.scriptList.size()];
				ofld.typeList = new ArrayList[obtn2.scriptList.size()];
				ofld.enabled = obtn2.enabled;
				ofld.setVisible(obtn2.getVisible());
				
				//外観変更
				{
					OField.buildOField(ofld);
		        	tgtOFld = ofld;
					this.target = ofld.getComponent();
					PCARD.pc.mainPane.add(this.target, 0);
				}
			}
    	}
    	else if(e.getSource().getClass() == MyTextArea.class ||
    			e.getSource().getClass() == MyScrollPane.class )
    	{
    		target = (Component) e.getSource();
        	if(target!=null){
        		if((x < target.getX()+8 || x > target.getX()+target.getWidth()-8) &&
        			(y < target.getY()+8 || y > target.getY()+target.getHeight()-8))
        		{
        			sizeChange = true;
        			sizeleft=(x < target.getX()+8);
        			sizetop=(y < target.getY()+8);
        		}
        		else{
        	    	sizeChange = false;
        		}
        	}
    	}
    	else{
    		target = null;
    	}
    	startx = x; //最初の位置を記憶
    	starty = y;
    	//ポインタと部品のオフセットを記憶。x,yと同じ(MouseInfoベースの)座標系で
    	//求めることで、dragThreadの最初の1回でのずれを防ぐ。
    	offsetx = (target!=null) ? x-target.getX() : e.getX();
    	offsety = (target!=null) ? y-target.getY() : e.getY();
    	lastx = x; //サイズの変更用に前回の位置を記憶
    	lasty = y;

		isMouseDown = true;

    	if(target!=null){
		//ドラッグ更新と確定をEDT上で順番に処理し、確定後の座標更新を防ぐ
		if (dragTimer != null) dragTimer.stop();
		dragTimer = new javax.swing.Timer(16, event -> new dragThread().run());
		dragTimer.start();
    	}
    }
    
    public void mouseReleased(MouseEvent e) {
		if (dragTimer != null) dragTimer.stop();
		try {

    	/*Rectangle r = ((Component)e.getSource()).getBounds();
    	int x = e.getX()+r.x;
    	int y = e.getY()+r.y;
    	if((Component)e.getSource() == PCARD.pc.getRootPane()){
    		x -= PCARD.pc.toolbar.getTWidth();
    		y -= PCARD.pc.toolbar.getTHeight()+PCARD.pc.getInsets().top+PCARD.pc.getJMenuBar().getHeight();
    	}*/
    	
    	isMouseDown = false;

    	if(target!=null){
    		drawSelectBorder(target);
    		
    		OField ofld = null;
        	if(target.getClass() == MyTextArea.class) ofld = ((MyTextArea)target).fldData;
        	else if(target.getClass() == MyScrollPane.class) ofld = ((MyScrollPane)target).fldData;

        	if(ofld!=null){
            	//ダブルクリック判定
            	if (!newField && e.getClickCount() >= 2){
            		//Shift-ダブルクリックはスクリプトを開く(HyperCard)
            		if(e.isShiftDown() && StackProtection.canOpenScripts()) ScriptEditor.openScriptEditor(PCARD.pc, ofld);
            		else AuthDialog.openAuthDialog(PCARD.pc, "field", ofld);
            	}
            	else{
            		tgtOFld = ofld;
            		//target.getBounds()はSwing上の実(物理)座標なので、
            		//論理座標であるofld.left/top/width/heightに書き戻す際はscale()で割る。
					int oldLeft=ofld.left, oldTop=ofld.top;
					ofld.left = PCARD.toLogical(target.getBounds().x);
            		ofld.top = PCARD.toLogical(target.getBounds().y);
            		ofld.width = (PCARD.toLogical(target.getBounds().x+target.getBounds().width)-PCARD.toLogical(target.getBounds().x));
            		ofld.height = (PCARD.toLogical(target.getBounds().y+target.getBounds().height)-PCARD.toLogical(target.getBounds().y));
					if(!sizeChange) ((OCardBase)ofld.parent).movePanelPeers(ofld, ofld.left-oldLeft, ofld.top-oldTop);
            	}
            	
            	if(newField){
        			OCard.reloadCurrentCard();

            		OCardBase cdbase = PCARD.pc.stack.curCard;
        			if(PaintTool.editBackground){
        				cdbase = PCARD.pc.stack.curCard.bg;
        			}
        			OField newfld = cdbase.GetFldbyNum(cdbase.fldList.size());
        			target = newfld.getComponent();
            		drawSelectBorder(newfld.fld);
            		//作られたフィールド自身へ newField を送る
            		TTalk.CallMessage("newField", newfld);
            	}
        	}

    		if(AuthDialog.authDialog != null){
    			AuthDialog.openAuthDialog(PCARD.pc, "field", ofld);
    		}
    	}else{
    		tgtOFld = null;
    	}

		} finally { AuthoringUndo.commit(dragBefore); dragBefore = null; }
	}
    
    public void mouseEntered(MouseEvent e) {
    }
    public void mouseExited(MouseEvent e) {
    }

	private class dragThread implements Runnable
	{
		public void run(){
			if(isMouseDown){
		        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
		        int x = pointerInfo.getLocation().x-PCARD.pc.mainPane.getX()-PCARD.pc.getLocationOnScreen().x;
		        int y = pointerInfo.getLocation().y-PCARD.pc.mainPane.getY()-PCARD.pc.getLocationOnScreen().y-PCARD.pc.getInsets().top+PCARD.pc.getJMenuBar().getHeight();

		    	if(target != null){
			    	Rectangle newr = target.getBounds();
		    		if(!sizeChange){
			    		//移動
		    			newr.x = x-offsetx;
			    		newr.y = y-offsety;
		    			if(GUI.key[11]>1){ //SHIFT
			    			if(Math.abs(x-startx)<Math.abs(y-starty)){
			    				newr.x = startx-offsetx;
			    			}else{
			    				newr.y = starty-offsety;
			    			}
		    			}
			    		//newrはここまで物理座標。setBoundsは論理座標を受け取ってscale()倍する
			    		//仕様なので、ここでscale()で割り戻してから渡す。
			    		{
							target.setBounds(PCARD.toLogical(newr.x), PCARD.toLogical(newr.y), (PCARD.toLogical(newr.x+newr.width)-PCARD.toLogical(newr.x)), (PCARD.toLogical(newr.y+newr.height)-PCARD.toLogical(newr.y)));
							OField moving=null;
							if(target instanceof MyTextArea) moving=((MyTextArea)target).fldData;
							else if(target instanceof MyScrollPane) moving=((MyScrollPane)target).fldData;
							if(moving!=null && moving.panelId!=0 &&
								(moving.left!=PCARD.toLogical(newr.x) || moving.top!=PCARD.toLogical(newr.y)))
								moving.setTopLeft(PCARD.toLogical(newr.x),PCARD.toLogical(newr.y));
					}
					target.setEnabled(false);
		    		}
		    		else{
			    		//サイズ変更
			    		if(sizeleft) {
			    			newr.x += x - lastx;
			    			newr.width -= x - lastx;
			    		}
			    		else{
			    			newr.width += x - lastx;
			    		}
			    		if(sizetop){
				    		newr.height -= y - lasty;
			    			newr.y += y - lasty;
			    		}
			    		else{
				    		newr.height += y - lasty;
			    		}
			    		//newrは物理座標。setBoundsは論理座標を受け取ってscale()倍する仕様。
			    		target.setBounds(PCARD.toLogical(newr.x), PCARD.toLogical(newr.y), (PCARD.toLogical(newr.x+newr.width)-PCARD.toLogical(newr.x)), (PCARD.toLogical(newr.y+newr.height)-PCARD.toLogical(newr.y)));
			    		target.setEnabled(false);

			    		OField ofld = null;
			        	if(target.getClass() == MyTextArea.class) ofld = ((MyTextArea)target).fldData;
			        	else if(target.getClass() == MyScrollPane.class) ofld = ((MyScrollPane)target).fldData;
			        	//ofld.widthは論理値なので、物理座標のnewr.widthをscale()で割る。
			    		ofld.width = (PCARD.toLogical(newr.x+newr.width)-PCARD.toLogical(newr.x));
		    		}
		    	}

		    	lasty += (y-lasty);
		    	lastx += (x-lastx);
			}
		}
	}
    
	public void mouseDragged(MouseEvent e) {
    	/*Rectangle r = ((Component)e.getSource()).getBounds();
    	int x = e.getX()+r.x;
    	int y = e.getY()+r.y;
    	if((Component)e.getSource() == PCARD.pc.getRootPane()){
    		x -= PCARD.pc.stack.toolbar.getTWidth();
    		y -= PCARD.pc.stack.toolbar.getTHeight()+PCARD.pc.getInsets().top;
    	}
    	
    	if(target != null){
	    	Rectangle newr = target.getBounds();
    		if(!sizeChange){
	    		//移動
    			newr.x = x-offsetx;
	    		newr.y = y-offsety;
    			if((e.getModifiersEx()&InputEvent.SHIFT_DOWN_MASK)>0){
	    			if(Math.abs(x-startx)<Math.abs(y-starty)){
	    				newr.x = startx-offsetx;
	    			}else{
	    				newr.y = starty-offsety;
	    			}
    			}
	    		target.setBounds(newr);
    		}
    		else{
	    		//サイズ変更
    			newr.width += x - lastx;
	    		newr.height += y - lasty;
	    		target.setBounds(newr);
    		}
    	}

    	lasty += (y-lasty);
    	lastx += (x-lastx);*/
	}
	
	public void mouseMoved(MouseEvent e) {
		if(target!=null) drawSelectBorder(target);
	}
	
	public void keyDown(){
		if (!AuthoringUndo.editKeyDown()) return;
		AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

		if(tgtOFld==null){
			return;
		}
		
		if(GUI.key[20]>0 || GUI.key[21]>0){ //BACKSPACE or DELETE
			GMenuBrowse.removeCardDataForBgPart(tgtOFld);
			((OCardBase)tgtOFld.parent).partsList.remove(tgtOFld);
			((OCardBase)tgtOFld.parent).fldList.remove(tgtOFld);
			if(tgtOFld.getComponent()!=null){
				PCARD.pc.mainPane.remove(tgtOFld.getComponent());
			}
			target = null;
			tgtOFld = null;
			
			OCard.reloadCurrentCard();
		}
		else if(GUI.key[14]==0 && (GUI.key[0]>0 || GUI.key[1]>0 || GUI.key[2]>0 || GUI.key[3]>0)){ //ARROW
			if(GUI.key[0]>0){
				tgtOFld.setTopLeft(tgtOFld.left, tgtOFld.top-1);
			}
			else if(GUI.key[1]>0){
				tgtOFld.setTopLeft(tgtOFld.left, tgtOFld.top+1);
			}
			else if(GUI.key[2]>0){
				tgtOFld.setTopLeft(tgtOFld.left-1, tgtOFld.top);
			}
			else if(GUI.key[3]>0){
				tgtOFld.setTopLeft(tgtOFld.left+1, tgtOFld.top);
			}
		}
		else if(GUI.key[14]>0 && (GUI.key[0]>0 || GUI.key[1]>0 || GUI.key[2]>0 || GUI.key[3]>0)){ //ARROW
			if(GUI.key[0]>0 && tgtOFld.height>0){
				tgtOFld.setRect(tgtOFld.left, tgtOFld.top, 
						tgtOFld.left+tgtOFld.width, tgtOFld.top+tgtOFld.height-1);
			}
			else if(GUI.key[1]>0){
				tgtOFld.setRect(tgtOFld.left, tgtOFld.top, 
						tgtOFld.left+tgtOFld.width, tgtOFld.top+tgtOFld.height+1);
			}
			else if(GUI.key[2]>0 && tgtOFld.width>0){
				tgtOFld.setRect(tgtOFld.left, tgtOFld.top, 
						tgtOFld.left+tgtOFld.width-1, tgtOFld.top+tgtOFld.height);
			}
			else if(GUI.key[3]>0){
				tgtOFld.setRect(tgtOFld.left, tgtOFld.top, 
						tgtOFld.left+tgtOFld.width+1, tgtOFld.top+tgtOFld.height);
			}
		}
		else if(GUI.key[14]>0 && (GUI.key['+']>0)){ //テンキーの+はショートカットが効かないのでここに
			OField ofld = tgtOFld;
        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)ofld.parent).stepInStack(ofld, true);

			OCard.reloadCurrentCard();
			
			//ofldは生きているがtargetはいなくなるので取り直す
			FieldGUI.gui.target = ofld.getComponent();
		}
		else if(GUI.key[14]>0 && (GUI.key['-']>0)){ //テンキーの-はショートカットが効かないのでここに
			OField ofld = tgtOFld;
        	//シェイプも含めた重なり順で一つ動かす
						((OCardBase)ofld.parent).stepInStack(ofld, false);
			
			OCard.reloadCurrentCard();
			
			//ofldは生きているがtargetはいなくなるので取り直す
			FieldGUI.gui.target = ofld.getComponent();
		}

		} finally { AuthoringUndo.commit(before); }
	}
	
	static void drawSelectBorder(Component target){
		ButtonGUI.drawSelectBorder(target);
	}
}


class MyFieldDropListener extends DropTargetAdapter {
	public void drop(DropTargetDropEvent e) {
		AuthoringUndo.Snapshot before = AuthoringUndo.capture();
		try {

		try {
			Transferable transfer = e.getTransferable();
			if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
			{
				e.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
				/*@SuppressWarnings("unchecked")
				List<File> fileList = 
					(List<File>) (transfer.getTransferData(DataFlavor.javaFileListFlavor));
				String path = fileList.get(0).toString();
				String ext = "";
				BufferedImage bi = null;
				try{
					ext = ".ppm";
					bi = PictureFile.loadPbm(path);
					if(bi==null){
						if(path.lastIndexOf(".")>=0){
							ext = path.substring(path.lastIndexOf("."), path.length());
						}
						else{
							ext = "";
						}
						bi = javax.imageio.ImageIO.read(new File(path));
					}
					if(bi==null){
						ext = ".pict";
						bi = PictureFile.loadPICT(path);
					}
				} catch (IOException e2) {
					e2.printStackTrace();
				}
				if(bi!=null){
					String name = new File(path).getName();
					Pattern p = Pattern.compile("([0-9]{1,6})([^0-9])");
					Matcher m = p.matcher(name);
					int baseid = 1000;
					if(m.find()){
						baseid = Integer.valueOf(m.group(1));
					}
					int rsrcid = PCARD.pc.stack.rsrc.getNewResourceId("pictfont", baseid);
					//ファイルをコピー
					String newFileName = "PICTFONT_"+rsrcid+ext;
					String newFilePath = PCARD.pc.stack.file.getParent()+File.separatorChar+newFileName;
					FileChannel srcChannel = new FileInputStream(path).getChannel();
					FileChannel destChannel = new FileOutputStream(newFilePath).getChannel();
					try {
						srcChannel.transferTo(0, srcChannel.size(), destChannel);
					} finally {
						srcChannel.close();
						destChannel.close();
					}
					//リソースに追加
					PCARD.pc.stack.rsrc.addResource(rsrcid, "pictfont", name, newFileName);
					//フィールドのフォントに設定
					Component component = ((DropTarget)e.getSource()).getComponent();
					OField ofld = ((MyTextArea)component).fldData;
					ofld.fontPict = bi;
					ofld.textFont = name;
				}
				else*/{
		    		new GDialog(PCARD.pc, PCARD.pc.intl.getDialogText("Could't open the file."),null,"OK",null,null);
		    	}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		} finally { AuthoringUndo.commit(before); }
	}
}

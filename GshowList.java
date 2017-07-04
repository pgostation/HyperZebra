import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import javax.swing.*;

public class GshowList extends JDialog implements ActionListener, MouseListener  {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	static String clicked="";
	static String selectList="";
	
	GshowList(Frame owner, String list, String text, boolean multiSelect, boolean useKey, String[] btnlist, int selectLine, int x, int y) {
		super(owner,true);
		getContentPane().setLayout(null);

		//説明文
		JTextArea area = new JTextArea(text);
		area.setBounds(8,8,304,32);
		area.setMargin(new Insets(0,0,0,0));
		area.setOpaque(false);
		area.setEditable(false);
		area.setFocusable(false);
		getContentPane().add(area);

		//スクロール
		JScrollPane scrl = new JScrollPane();
		scrl.setBounds(8,48,304,264);
		scrl.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrl.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		getContentPane().add(scrl);
		
		//リスト
		MyTextArea area2 = new MyTextArea(list);
		area2.pr_scrl = scrl;
		area2.setMargin(new Insets(0,0,0,0));
		area2.setOpaque(false);
		area2.setEditable(false);
		area2.addMouseListener(this);
		scrl.setViewportView(area2);
		
		//ボタン
		JButton btn=null;
		int i=0;
		for(; i<btnlist.length; i++){
			btn = new JButton(btnlist[i]);
			btn.addActionListener(this);
			getContentPane().add(btn);
			btn.setBounds(370-96/2,290+(i-(btnlist.length-1))*32,96,24);
		}
		if(btn==null){
			btn = new JButton("OK");
			btn.addActionListener(this);
			getContentPane().add(btn);
			btn.setBounds(370-96/2,290+(i-(btnlist.length-1))*32,96,24);
		}
		getRootPane().setDefaultButton(btn);
		
		if(x==0 && y==0){
			setBounds(owner.getX()+owner.getWidth()/2-210,owner.getY()+owner.getHeight()/2-160+12,420,320);
		}else{
			setBounds(owner.getX()+x,owner.getY()+y,420,320);
		}
		setUndecorated(true);//タイトルバー非表示
		setVisible(true);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		clicked=e.getActionCommand();
		this.dispose();
	}
	
    @Override
	public void mouseClicked(MouseEvent e) {
    }
    @Override
	public void mousePressed(MouseEvent e) {
    	MyTextArea fld = (MyTextArea)e.getSource();
    	{
    		int line = (e.getY() + fld.pr_scrl.getVerticalScrollBar().getValue() + fld.getLineHeight()-1)/fld.getLineHeight();
    		System.out.println("getValue="+fld.pr_scrl.getVerticalScrollBar().getValue() );
    		System.out.println("line="+line);
    		int cnt=0;
    		int i;
    		for(i=0; i<fld.getText().length(); i++){
    			if(fld.getText().charAt(i) == '\n'||i==0) {
    				cnt++;
    				if(cnt==line){
    					fld.pr_selLine = line;
    		    		fld.setSelectionStart(i);
    		    		fld.getRootPane().repaint();
    		    		GshowList.selectList = fld.getText().split("\n")[line-1];
    				}
    				if(cnt==line+1){
    					break;
    				}
    			}
    		}
			if(fld.pr_selLine==line){
	    		fld.setSelectionEnd(i);
			}
    	}
    }
    @Override
	public void mouseReleased(MouseEvent e) {
    }
	@Override
	public void mouseEntered(MouseEvent arg0) {
	}
	@Override
	public void mouseExited(MouseEvent arg0) {
	}
}
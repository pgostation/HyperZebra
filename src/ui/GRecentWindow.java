import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;

//「リーセント」メニュー：これまでに移動したカードの履歴を、サムネイルと
//カード名（無ければカードID）で一覧表示する。クリックするとそのカードへ移動する。
public class GRecentWindow extends JFrame {
	private static final long serialVersionUID = 1L;

	public GRecentWindow(PCARDFrame owner) {
		super();
		setTitle(PCARD.pc.intl.getText("Recent"));
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		JPanel grid = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		OStack stack = (owner!=null) ? owner.stack : null;
		if(stack!=null) stack.refreshRecentThumbnail();
		if(stack!=null && !stack.recentCards.isEmpty()){
			//最新に移動したカードを先頭に表示する
			for(int i=stack.recentCards.size()-1; i>=0; i--){
				grid.add(makeEntryPanel(stack.recentCards.get(i)));
			}
		}else{
			grid.add(new JLabel(PCARD.pc.intl.getDialogText("There is no recent card.")));
		}

		JScrollPane scroll = new JScrollPane(grid);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		getContentPane().add(scroll, BorderLayout.CENTER);

		setSize(420, 360);
		setLocationRelativeTo(owner);
		setVisible(true);
	}

	private JPanel makeEntryPanel(OStack.RecentCardEntry entry){
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(4,4,4,4));

		JLabel thumbLabel;
		if(entry.thumbnail!=null){
			thumbLabel = new JLabel(new ImageIcon(entry.thumbnail));
		}else{
			thumbLabel = new JLabel();
			thumbLabel.setOpaque(true);
			thumbLabel.setBackground(Color.LIGHT_GRAY);
		}
		thumbLabel.setPreferredSize(new Dimension(64,48));
		thumbLabel.setMaximumSize(new Dimension(64,48));
		thumbLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
		thumbLabel.setAlignmentX(0.5f);

		JLabel nameLabel = new JLabel(entry.label);
		nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
		nameLabel.setAlignmentX(0.5f);
		nameLabel.setMaximumSize(new Dimension(90, nameLabel.getPreferredSize().height));

		panel.add(thumbLabel);
		panel.add(nameLabel);
		panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		panel.addMouseListener(new MouseAdapter(){
			@Override
			public void mouseClicked(MouseEvent e){
				dispose();
				try{
					TTalk.doScriptforMenu("go to card id "+entry.cardId);
				}catch(xTalkException ex){
					ex.printStackTrace();
				}
			}
		});

		return panel;
	}
}

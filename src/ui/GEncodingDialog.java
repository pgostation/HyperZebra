import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * HyperCardスタックとして書き出すときの文字コードを選ぶダイアログ。
 * (読み込み時はスタック内のテキストから推定するので出さない: StackEncodingDetector)
 */
class GEncodingDialog extends JDialog implements ActionListener {
	private static final long serialVersionUID = 1L;
	private static String clicked = "";
	private final JComboBox<StackTextEncoding> combo;

	private GEncodingDialog(Frame owner, StackTextEncoding current) {
		super(owner, true);
		getContentPane().setLayout(new BorderLayout());

		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		getContentPane().add("North", topPanel);

		String labelText = (owner instanceof PCARDFrame && PCARDFrame.pc != null && PCARDFrame.pc.intl != null) ?
			PCARDFrame.pc.intl.getDialogText("Stack Text Encoding:") : "Stack Text Encoding:";
		JLabel label = new JLabel(labelText);
		label.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 4, 16));
		topPanel.add(label);

		combo = new JComboBox<StackTextEncoding>(StackTextEncoding.values());
		combo.setSelectedItem(current == null ? StackTextEncoding.JAPANESE : current);
		//JComboBoxの高さはAqua L&Fが矢印ボタン用に描画するネイティブ画像の高さに
		//固定されているため、setPreferredSize等で無理に高さを変えると矢印ボタンが
		//枠からはみ出して表示が崩れる。ここでは高さには触れず、周囲を
		//EmptyBorderで囲んで余白を確保するだけにする。
		JPanel comboWrapper = new JPanel(new BorderLayout());
		comboWrapper.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 16, 16, 16));
		comboWrapper.add(combo, BorderLayout.CENTER);
		topPanel.add(comboWrapper);

		JPanel btmPanel = new JPanel();
		getContentPane().add("South", btmPanel);
		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(this);
		btmPanel.add(cancelBtn);
		JButton okBtn = new JButton("OK");
		okBtn.addActionListener(this);
		btmPanel.add(okBtn);
		getRootPane().setDefaultButton(okBtn);

		setUndecorated(true);//タイトルバー非表示
		//固定の低い高さを指定していたためコンボボックスやボタンの表示が欠けていた。
		//pack()で各部品の実際の必要サイズに合わせて高さを決める。
		pack();
		Dimension size = getSize();
		if(size.width < 360) size.width = 360;
		setSize(size);
		java.awt.Rectangle area = GLauncher.dialogArea(owner);
		if(area != null){
			setLocation(area.x + area.width / 2 - size.width / 2,
				area.y + area.height / 2 - size.height / 2);
		}
		else{
			setLocationRelativeTo(null);
		}

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				okBtn.requestFocus();
			}
		});

		GPictWindow.prepareForModalDialog(this);
		setVisible(true);
		GPictWindow.restorePictureWindows();
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		clicked = e.getActionCommand();
		this.dispose();
	}

	/**
	 * 文字コードの選択ダイアログを表示する。
	 * ユーザーがキャンセルした場合はnullを返す。
	 */
	static StackTextEncoding choose(Frame owner, StackTextEncoding current) {
		//-Dhz.stackencoding=ROMAN|JAPANESE|…: unattended runs (stack sweeps) open HyperCard stacks without the chooser
		String forced = System.getProperty("hz.stackencoding");
		if(forced!=null) return StackTextEncoding.valueOf(forced.trim().toUpperCase(java.util.Locale.ROOT));
		clicked = "";
		GEncodingDialog dialog = new GEncodingDialog(owner, current);
		if(!clicked.equals("OK")) return null;
		return (StackTextEncoding) dialog.combo.getSelectedItem();
	}
}

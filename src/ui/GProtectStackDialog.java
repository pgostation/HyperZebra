import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;

/**
 * File > Protect Stack…, as in HyperCard 2: can't modify / can't delete / can't abort / can't peek / private access,
 * the user level limit, and Set Password… / No Password. A stack with a password asks for it first.
 */
class GProtectStackDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	private final OStack stack;
	private final JCheckBox cantModify, cantDelete, cantAbort, cantPeek, privateAccess;
	private final JRadioButton[] levels = new JRadioButton[5];
	private final JLabel passwordState;
	private final JButton noPassword;
	/** null: keep the password as it is; "": remove it; otherwise the new password. */
	private String newPassword;

	static String t(String english){ return StackProtection.text(english); }

	/** Opens the dialog for a stack, after its password when it has one. */
	static void show(OStack stack){
		if(stack==null) return;
		if(stack.getPasswordHash()!=0){
			String typed = StackProtection.askPassword(t("Password for stack")+" \""+StackProtection.displayName(stack)+"\":");
			if(typed==null) return;
			if(!StackProtection.checkPassword(stack.getPasswordHash(), typed, stack.legacyTextEncoding)){
				java.awt.Toolkit.getDefaultToolkit().beep();
				new GDialog(PCARD.pc, t("Wrong password."), null, "OK", null, null);
				return;
			}
			stack.unlockedPassword = typed;
		}
		new GProtectStackDialog(stack).setVisible(true);
	}

	private GProtectStackDialog(OStack stack){
		super(PCARD.pc, t("Protect Stack"), true);
		this.stack = stack;
		JPanel body = new JPanel(new GridLayout(1, 2, 24, 0));
		body.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

		JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
		left.add(new JLabel(t("Protect Stack:")));
		left.add(cantModify = new JCheckBox(t("Can't modify stack"), stack.cantModify));
		left.add(cantDelete = new JCheckBox(t("Can't delete stack"), stack.cantDelete));
		left.add(cantAbort = new JCheckBox(t("Can't abort"), stack.cantAbort));
		left.add(cantPeek = new JCheckBox(t("Can't peek"), stack.cantPeek));
		left.add(privateAccess = new JCheckBox(t("Private Access"), stack.getPrivateAccess()));
		left.add(Box.createVerticalStrut(10));
		JPanel pwButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		pwButtons.setAlignmentX(0);
		JButton setPassword = new JButton(t("Set Password…"));
		setPassword.addActionListener(e -> askNewPassword());
		pwButtons.add(setPassword);
		pwButtons.add(Box.createHorizontalStrut(6));
		noPassword = new JButton(t("No Password"));
		noPassword.addActionListener(e -> { newPassword = ""; refresh(); });
		pwButtons.add(noPassword);
		left.add(pwButtons);
		left.add(Box.createVerticalStrut(4));
		left.add(passwordState = new JLabel());
		body.add(left);

		JPanel right = new JPanel();
		right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
		right.add(new JLabel(t("Limit user level to:")));
		ButtonGroup group = new ButtonGroup();
		int limit = StackProtection.stackLimit(stack);
		for(int i=0; i<5; i++){
			levels[i] = new JRadioButton((i+1)+"  "+t(StackProtection.LEVEL_NAMES[i]), limit==i+1);
			group.add(levels[i]);
			right.add(levels[i]);
		}
		body.add(right);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton cancel = new JButton(t("Cancel"));
		cancel.addActionListener(e -> dispose());
		JButton ok = new JButton("OK");
		ok.addActionListener(e -> { apply(); dispose(); });
		buttons.add(cancel);
		buttons.add(ok);
		getRootPane().setDefaultButton(ok);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(body, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		refresh();
		pack();
		setResizable(false);
		java.awt.Rectangle area = GLauncher.dialogArea(PCARD.pc);
		if(area!=null) setLocation(area.x+area.width/2-getWidth()/2, area.y+area.height/2-getHeight()/2);
		else setLocationRelativeTo(PCARD.pc);
	}

	private boolean hasPassword(){
		return newPassword==null ? stack.getPasswordHash()!=0 : !newPassword.isEmpty();
	}

	private void refresh(){
		boolean has = hasPassword();
		//Private Access はパスワードがあるときだけ(HyperCard)
		privateAccess.setEnabled(has);
		if(!has) privateAccess.setSelected(false);
		noPassword.setEnabled(has);
		passwordState.setText(has ? t("This stack has a password.") : t("This stack has no password."));
	}

	/** Set Password…: the new password twice. An empty one means no password. */
	private void askNewPassword(){
		JPasswordField first = new JPasswordField(20), again = new JPasswordField(20);
		Object[] content = {t("Enter the new password:"), first, t("Type it again to confirm:"), again};
		int r = JOptionPane.showConfirmDialog(this, content, t("Set Password"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if(r!=JOptionPane.OK_OPTION) return;
		String a = new String(first.getPassword()), b = new String(again.getPassword());
		if(!a.equals(b)){
			java.awt.Toolkit.getDefaultToolkit().beep();
			JOptionPane.showMessageDialog(this, t("The passwords don't match."));
			return;
		}
		newPassword = a;
		refresh();
	}

	/** OK: the settings go to the stack, which is saved at once (also when it has just become "can't modify"). */
	private void apply(){
		int level = 5;
		for(int i=0; i<5; i++) if(levels[i].isSelected()) level = i+1;
		applyTo(stack, cantModify.isSelected(), cantDelete.isSelected(), cantAbort.isSelected(), cantPeek.isSelected(),
			privateAccess.isSelected(), level, newPassword);
	}

	/** Applies protection settings and saves the stack; {@code password} null keeps the current one, "" removes it. */
	static void applyTo(OStack stack, boolean cantModify, boolean cantDelete, boolean cantAbort, boolean cantPeek,
			boolean privateAccess, int level, String password){
		boolean wasSealed = StackProtection.sealsXml(stack);
		byte[] oldKey = stack.contentKey;
		if(password!=null) stack.setPassword(password);
		stack.setPrivateAccess(privateAccess);
		boolean sealed = StackProtection.sealsXml(stack);
		//パスワードを外す: 絵を先に元へ戻す(鍵を捨てる前に)
		if(wasSealed && !sealed){
			StackProtection.resealPictures(stack, oldKey, false);
			stack.contentKey = null;
			StackProtection.registerContentKey(stack);
		}
		byte[] key = StackProtection.contentKey(stack);
		stack.cantModify = cantModify;
		stack.cantDelete = cantDelete;
		stack.cantAbort = cantAbort;
		stack.cantPeek = cantPeek;
		stack.userLevel = level;
		stack.changed = true;
		if(stack.saveXML!=null) stack.saveXML.saveStackNow(true); //保存を止める"can't modify"にした直後も、この変更は書き出す
		//パスワードを付けた: 鍵を_stack.xmlに書いてから絵を封印する
		if(sealed) StackProtection.resealPictures(stack, key, true);
		GMenu.enforceUserLevel();
	}
}

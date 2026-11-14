/**
 * What the core asks of a field's view for its text selection (P1 S5): the JTextComponent calls of the same names. The
 * desktop's MyTextArea is one (its Swing methods); the headless core and the browser have PlainFieldView, which keeps the
 * selection of a field's text itself. OField.view() gives it.
 */
interface FieldView {
	int getSelectionStart();
	int getSelectionEnd();
	void setSelectionStart(int position);
	void setSelectionEnd(int position);
	void select(int start, int end);
	int getCaretPosition();
	void setCaretPosition(int position);
	void replaceSelection(String text);
	String getText();
	boolean isFocusOwner();
	boolean requestFocusInWindow();
	void repaint();
}

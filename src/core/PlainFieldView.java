/**
 * A field's text selection without a screen (the headless core and the browser): the positions a JTextComponent keeps
 * (start, end, caret; clamped to the text as Swing clamps them), over the field's own text.
 */
final class PlainFieldView implements FieldView {
	private final OField field;
	private int start, end, caret;
	private boolean focus;

	PlainFieldView(OField field) { this.field = field; }

	private int length() { String t = field.getText(); return t == null ? 0 : t.length(); }
	private int clamp(int p) { return Math.max(0, Math.min(p, length())); }

	@Override public int getSelectionStart() { return Math.min(clamp(start), clamp(end)); }
	@Override public int getSelectionEnd() { return Math.max(clamp(start), clamp(end)); }
	@Override public void setSelectionStart(int p) { start = clamp(p); if (end < start) end = start; caret = end; }
	@Override public void setSelectionEnd(int p) { end = clamp(p); if (start > end) start = end; caret = end; }
	@Override public void select(int s, int e) { start = clamp(s); end = Math.max(start, clamp(e)); caret = end; }
	/** Swing's moveCaretPosition: the selection from mark to dot, the insertion point (caret) at dot - Shift-arrow extends it
	 *  from the end that moves */
	void selectFromMark(int mark, int dot) { start = clamp(Math.min(mark, dot)); end = clamp(Math.max(mark, dot)); caret = clamp(dot); }
	@Override public int getCaretPosition() { return clamp(caret); }
	@Override public void setCaretPosition(int p) { caret = start = end = clamp(p); }
	@Override public void replaceSelection(String text) {
		String t = field.getText() == null ? "" : field.getText();
		int s = getSelectionStart(), e = getSelectionEnd();
		String add = text == null ? "" : text;
		// typing, not a script's put: the field stays open (OField.setText would close it), the style runs follow the change
		boolean open = field == TTalk.openField;
		OField clicked = HZ.clickField;
		OField.typedChangeForNextText(s, e, add.length());
		field.setText(t.substring(0, s) + add + t.substring(e));
		if (open) { TTalk.openField = field; TTalk.openFieldEdited = true; } // typed in: closeField when it closes (GUI_fldDocument)
		if (clicked == field) HZ.clickField = field;
		caret = start = end = s + add.length();
	}
	@Override public String getText() { return field.getText(); }
	@Override public boolean isFocusOwner() { return focus; }
	@Override public boolean requestFocusInWindow() { focus = true; return true; }
	@Override public void repaint() {}
}

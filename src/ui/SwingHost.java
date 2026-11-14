/** The desktop's {@link Host}: each call is the card window's code that the core called directly before (PCARD). */
final class SwingHost extends Host {
	@Override void runOnEDT(Runnable task) { PCARD.runOnEDT(task); }
	@Override void runOnEDTWhenShown(Runnable task) { PCARD.runOnEDTWhenShown(task); }
	@Override void runOnEDTWhenShown(OObject part, int kind, Runnable now, Runnable latest) { PCARD.runOnEDTWhenShown(part, kind, now, latest); }
	@Override void batchOnEDT(Runnable body) { PCARD.batchOnEDT(body); }

	@Override boolean paintToolActive() { return PCARD.pc!=null && PCARD.pc.tool!=null; }
	@Override boolean authToolActive() { return AuthTool.tool!=null; }
	@Override boolean hasCardWindow() { return PCARD.pc!=null; }

	@Override void repaintLogicalRect(int left, int top, int width, int height) { PCARD.repaintLogicalRect(left, top, width, height); }
	@Override double scale() { return PCARD.scale(); }
	@Override int toPhysical(int logical) { return PCARD.toPhysical(logical); }
	@Override int toPhysical(double logical) { return PCARD.toPhysical(logical); }
	@Override java.awt.Rectangle toPhysical(int left, int top, int width, int height) { return PCARD.toPhysical(left, top, width, height); }
	@Override int toLogical(int physical) { return PCARD.toLogical(physical); }
	@Override int toLogicalLength(double physical) { return PCARD.toLogicalLength(physical); }
}

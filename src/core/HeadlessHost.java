/** The {@link Host} of the headless core and the browser build: tasks run at once, no screen, card pixels 1:1. */
class HeadlessHost extends Host {
	@Override void runOnEDT(Runnable task) { task.run(); }
	@Override void runOnEDTWhenShown(Runnable task) { task.run(); }
	@Override void runOnEDTWhenShown(OObject part, int kind, Runnable now, Runnable latest) { now.run(); }
	@Override void batchOnEDT(Runnable body) { body.run(); }

	@Override boolean paintToolActive() { return false; }
	@Override boolean authToolActive() { return false; }

	@Override void repaintLogicalRect(int left, int top, int width, int height) {}
	@Override double scale() { return 1; }
	@Override int toPhysical(int logical) { return logical; }
	@Override int toPhysical(double logical) { return (int) Math.round(logical); }
	@Override java.awt.Rectangle toPhysical(int left, int top, int width, int height) { return new java.awt.Rectangle(left, top, width, height); }
	@Override int toLogical(int physical) { return physical; }
	@Override int toLogicalLength(double physical) { return (int) Math.round(physical); }
	@Override boolean hasCardWindow() { return true; }
}

/** The display zoom's arithmetic (DisplayZoom's, P4: also for the 68K VM's card window where there is no DisplayZoom). */
final class ZoomMath {
	private ZoomMath() {}
	/** The pixel coordinate of logical x at percent (rounded half up). */
	static int scale(int x, int percent) { return (int) Math.floorDiv(2L * x * percent + 100, 200); }
	/** The coordinate x whose scaled pixels [scale(x), scale(x+1)) contain pixel p. */
	static int unscale(int p, int percent) { return (int) Math.floorDiv(200L * p + 99, 2L * percent); }
}

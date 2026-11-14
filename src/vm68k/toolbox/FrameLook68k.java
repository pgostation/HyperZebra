import java.awt.image.BufferedImage;

/** How a window frame looks, in screen pixels (P4: ScreenWindowFrame.Look's methods, for the themes the 68K VM draws). */
interface FrameLook68k {
	/** The frame's widths outside the content: {top, left, bottom, right}. */
	int[] outsets();
	/** The whole frame for content of that size, the content's place left clear (ARGB, structure-sized). */
	BufferedImage paint(int contentWidth, int contentHeight, String title, boolean active);
}

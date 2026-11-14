import java.awt.image.BufferedImage;

/** The Navigator palette (HcNavigatorPalette) as the desktop's WindowUI opens it. */
final class NavigatorPalette {
	private NavigatorPalette() {}

	static final String NAME = HcNavigatorPalette.NAME;

	/** The palette as a PLTE of {@code rsrc} (not added to it): a windoid without title, buttons hilited while pressed. */
	static Rsrc.PlteClass plte(Rsrc rsrc) { return HcNavigatorPalette.plte(rsrc); }

	/** The picture: the cells with their grid lines and a black line drawing in each. */
	static BufferedImage image() {
		BufferedImage bi = new BufferedImage(HcNavigatorPalette.WIDTH, HcNavigatorPalette.HEIGHT, BufferedImage.TYPE_INT_ARGB);
		bi.setRGB(0, 0, bi.getWidth(), bi.getHeight(), HcNavigatorPalette.argb(), 0, bi.getWidth());
		return bi;
	}
}

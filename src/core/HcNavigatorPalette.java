import java.awt.Point;
import java.awt.Rectangle;

/**
 * HyperCard's "Navigator" palette (palette "Navigator", "417,10"): HyperCard keeps it as PLTE 128 in the application, so
 * every stack can open it without having one of its own (MailReader, Colorizing HyperCard's Disable and Enable Demo and
 * ~120 other stacks). The button layout and messages are HyperCard's (PLTE 128: three rows of 23x21 cells from 168,72, each
 * button a doMenu of the Go menu); the picture is HyperZebra's own line drawing of the buttons, black on white. The desktop
 * (NavigatorPalette) and the browser player (PlayerUiHost.paletteCommand) both open it from here.
 */
final class HcNavigatorPalette {
	private HcNavigatorPalette() {}

	static final String NAME = "Navigator";
	static final int WIDTH = 96, HEIGHT = 66;
	/** Where the picture's top left is in the PLTE's coordinates (its buttons are given there). */
	private static final Point ORIGIN = new Point(168, 72);
	/** HyperCard's buttons: top, left, bottom, right and the message. */
	private static final Object[][] BUTTONS = {
		{72, 168, 93, 191, "doMenu \"Back\""}, {72, 192, 93, 215, "doMenu \"Home\""},
		{72, 216, 93, 239, "doMenu \"Help\""}, {72, 240, 93, 263, "doMenu \"Recent\""},
		{94, 168, 115, 191, "doMenu \"First\""}, {94, 192, 115, 215, "doMenu \"Prev\""},
		{94, 216, 115, 239, "doMenu \"Next\""}, {94, 240, 115, 263, "doMenu \"Last\""},
		{116, 168, 137, 204, "doMenu \"Find...\""}, {116, 205, 137, 239, "doMenu \"Message\""},
		{116, 240, 137, 263, "doMenu \"Next window\""},
	};

	/** The palette as a PLTE of {@code rsrc} (not added to it): a windoid without title, buttons hilited while pressed. */
	static Rsrc.PlteClass plte(Rsrc rsrc) {
		Rsrc.PlteClass plte = rsrc.new PlteClass(128, NAME, 2052, true, 0, 128, new Point(ORIGIN));
		for(Object[] b : BUTTONS){
			int top = (Integer)b[0], left = (Integer)b[1], bottom = (Integer)b[2], right = (Integer)b[3];
			plte.objList.add(rsrc.new plteBtnObject(new Rectangle(left, top, right-left, bottom-top), (String)b[4]));
		}
		return plte;
	}

	/** The picture, WIDTH x HEIGHT opaque ARGB: the cells with their grid lines and a line drawing in each (Back, Home, Help,
	 *  Recent / First, Prev, Next, Last / Find, Message, Next Window) */
	static int[] argb() {
		int[] px = new int[WIDTH * HEIGHT];
		for(int y = 0; y < HEIGHT; y++){
			String row = ROWS[y];
			for(int x = 0; x < WIDTH; x++) px[y * WIDTH + x] = (Character.digit(row.charAt(x >> 2), 16) & (8 >> (x & 3))) != 0 ? 0xFF000000 : 0xFFFFFFFF;
		}
		return px;
	}
	/** The picture's rows, a hex digit for each 4 pixels (1 = black) */
	private static final String[] ROWS = {
		"000001000001000001000000",
		"000001000001000001000000",
		"000001000001000001000000",
		"000001001001007c010ffe00",
		"000001002801008201080200",
		"000e010044010101010803c0",
		"000181008201010101080240",
		"000041010101010101080278",
		"000041020081010201080248",
		"000021040041000401080248",
		"0000210bffa1000801080248",
		"008021020081001001080248",
		"0180410200810010010ffe48",
		"038041023881001001010048",
		"07818102288100000101ffc8",
		"0ffe01022881000001002008",
		"078001022881003801003ff8",
		"03800103ff81003801000000",
		"018001000001000001000000",
		"008001000001000001000000",
		"000001000001000001000000",
		"ffffffffffffffffffffffff",
		"000001000001000001000000",
		"000001000001000001000000",
		"000001000001000001000000",
		"000001000001000001000000",
		"000001000001000001000000",
		"030201000401004001008180",
		"030601000c0100600100c180",
		"030e01001c0100700100e180",
		"031e01003c0100780100f180",
		"033e01007c01007c0100f980",
		"037e0100fc01007e0100fd80",
		"033e01007c01007c0100f980",
		"031e01003c0100780100f180",
		"030e01001c0100700100e180",
		"030601000c0100600100c180",
		"030201000401004001008180",
		"000001000001000001000000",
		"000001000001000001000000",
		"000001000001000001000000",
		"000001000001000001000000",
		"000001000001000001000000",
		"ffffffffffffffffffffffff",
		"000000000800000001000000",
		"000000000800000001000000",
		"000000000800000001000000",
		"0000000008000000011ffe00",
		"0003c0000800000001100200",
		"0004200008000000011ffe00",
		"00081000083fffffc1100200",
		"001008000820000041100200",
		"00100800082000804110fff0",
		"001008000820008041108010",
		"001008000827ff804110fff0",
		"000810000820008041108010",
		"0004300008200080411f8010",
		"0003f8000820000041008010",
		"00001c000820000041008010",
		"00000e00083fffffc1008010",
		"000007000800000001008010",
		"00000380080000000100fff0",
		"000001800800000001000000",
		"000000000800000001000000",
		"000000000800000001000000",
		"000000000800000001000000",
	};
}

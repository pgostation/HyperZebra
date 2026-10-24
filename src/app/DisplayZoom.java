import java.awt.RenderingHints;

/**
 * The display zoom setting shared by stacks and the screen of Mac programs: actual size, "up to" 125/150/200 percent
 * (as large as fits the display, not pixel-perfect), or 1x-or-2x (pixel-perfect 2x when it fits, else 1x).
 * A choice is stored as an int: 1 (actual size), 2 (1x or 2x), 125/150/200 (up to that percent).
 */
final class DisplayZoom {
	private DisplayZoom(){}

	static final int ONE=1, ONE_OR_TWO=2, UP_TO_125=125, UP_TO_150=150, UP_TO_200=200;
	/** The choices in the order the pop-ups list them. */
	static final int[] CHOICES = {ONE, UP_TO_125, UP_TO_150, UP_TO_200, ONE_OR_TWO};

	static boolean valid(int choice){
		for(int c: CHOICES) if(c==choice) return true;
		return false;
	}

	/** The English label of a choice (translated by International.dialogText). */
	static String label(int choice){
		switch(choice){
		case UP_TO_125: return "Up to 125%";
		case UP_TO_150: return "Up to 150%";
		case UP_TO_200: return "Up to 200%";
		case ONE_OR_TWO: return "1x or 2x (pixel-perfect)";
		default: return "Actual size";
		}
	}

	/**
	 * The magnification for a choice when the content fits the display up to {@code fit} times: "up to" picks the
	 * largest 5% step that fits (never below 1x), 1x-or-2x is 2 when twice the size fits, else 1.
	 */
	static double factor(int choice, double fit){
		if(choice==ONE_OR_TWO) return fit>=2 ? 2 : 1;
		if(choice<100) return 1;
		double f = Math.min(choice/100.0, fit);
		f = Math.floor(f*20+1e-9)/20;
		return Math.max(1, f);
	}

	/** {@link #factor} in percent (exact: the factors are whole 5% steps). */
	static int percent(int choice, double fit){
		return (int)Math.round(factor(choice, fit)*100);
	}

	/** A coordinate times percent/100, rounded half up (exact integer arithmetic, so it is the inverse of {@link #unscale}). */
	static int scale(int x, int percent){
		return (int)Math.floorDiv(2L*x*percent+100, 200);
	}

	/** The coordinate x whose scaled pixels [scale(x), scale(x+1)) contain pixel p. */
	static int unscale(int p, int percent){
		return (int)Math.floorDiv(200L*p+99, 2L*percent);
	}

	/** How many times a w x h area (plus fixed extra width/height for the window chrome) fits in the display's usable area. */
	static double fit(int w, int h, int extraW, int extraH, java.awt.Rectangle usable){
		if(w<=0 || h<=0 || usable==null) return Double.MAX_VALUE;
		return Math.min((usable.width-extraW)/(double)w, (usable.height-extraH)/(double)h);
	}

	/** Whether a factor is a whole number: such magnifications stay pixel-perfect. */
	static boolean whole(double factor){
		return factor==Math.rint(factor);
	}

	/** Nearest neighbour for whole factors (pixel-perfect), bilinear otherwise. */
	static Object interpolation(double factor){
		return whole(factor) ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
	}

	/** The choice a stored value names (the old 2x on/off settings were 2 and 1). */
	static int parse(String value, int fallback){
		if(value==null) return fallback;
		try{
			int c = Integer.parseInt(value.trim());
			return valid(c) ? c : fallback;
		}catch(NumberFormatException e){
			return fallback;
		}
	}
}

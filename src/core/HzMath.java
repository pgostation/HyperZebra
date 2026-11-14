/**
 * Math functions whose TeaVM versions differ from the JDK's (P1 S10: TeaVM 0.14.1's Math.rint rounds halves away from
 * zero, its IEEEremainder is a truncating remainder, its ulp differs): exact, in plain double arithmetic, so the core gives
 * the JDK's results on the JVM and in the browser alike (tools/wasm-core/MathCheck.java compares them with the JDK's).
 */
final class HzMath {
	private HzMath() {}

	private static final double TWO52 = 4503599627370496.0; // 2^52: from there on every double is whole
	private static final double TWO971 = Double.longBitsToDouble((971L + 1023) << 52);

	/** Math.rint: the nearest whole number, halves to the even one (the rounding of a double addition does that). */
	static double rint(double a) {
		double m = Math.abs(a);
		if (!(m < TWO52)) return a; // NaN, the infinities, whole already
		double r = (m + TWO52) - TWO52;
		return Math.copySign(r, a);
	}

	/** Math.ulp: the distance from |d| to the next larger double. */
	static double ulp(double d) {
		if (Double.isNaN(d)) return d;
		double a = Math.abs(d);
		if (Double.isInfinite(a)) return Double.POSITIVE_INFINITY;
		if (a == Double.MAX_VALUE) return TWO971;
		return Double.longBitsToDouble(Double.doubleToRawLongBits(a) + 1) - a;
	}

	/** C's fmod (Java's % on doubles), exactly: |result| < |y|, the sign of x. Each step subtracts y times a power of two
	 *  no more than twice what is left, which a double subtraction does without rounding (Sterbenz). */
	static double fmod(double x, double y) {
		if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || y == 0) return Double.NaN;
		if (Double.isInfinite(y)) return x;
		double ax = Math.abs(x), ay = Math.abs(y);
		if (ax < ay) return x;
		double t = ay;
		while (t <= ax / 2 && t <= Double.MAX_VALUE / 2) t *= 2;
		while (ax >= ay) {
			while (t > ax) t /= 2;
			ax -= t;
		}
		return Math.copySign(ax, x);
	}

	/** Math.IEEEremainder: x - y*n with n the whole number nearest x/y (halves to even), as fdlibm's remainder. */
	static double IEEEremainder(double x, double y) {
		if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || y == 0) return Double.NaN;
		if (Double.isInfinite(y)) return x;
		double ay = Math.abs(y);
		double r = ay <= Double.MAX_VALUE / 2 ? fmod(x, ay + ay) : x; // now |r| < 2|y|
		if (r == ay || r == -ay) return Math.copySign(0.0, x);
		double ar = Math.abs(r);
		if (ay < 2 * Double.MIN_NORMAL) {
			if (ar + ar > ay) { ar -= ay; if (ar + ar >= ay) ar -= ay; }
		} else {
			double half = 0.5 * ay;
			if (ar > half) { ar -= ay; if (ar >= half) ar -= ay; }
		}
		boolean negative = x < 0 || x == 0 && 1 / x < 0;
		return negative ? -ar : ar;
	}
}

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * Number to text as the JDK writes it, computed from the exact binary value (P1 S10: TeaVM 0.14.1's Double.toString is
 * not always the shortest decimal that reads back, and its DecimalFormat rounds the shortest decimal instead of the
 * value: 0.35 with "0.0" gave 0.4, the JDK 0.3). Only BigInteger and BigDecimal arithmetic, no conversion of TeaVM's.
 * PortablePlatform uses these; JdkPlatform keeps the JDK's (tools/wasm-core/FormatCheck.java compares them).
 */
final class HzNumbers {
	private HzNumbers() {}

	private static final BigInteger FIVE = BigInteger.valueOf(5);

	/** The exact value of a finite double. */
	static BigDecimal exact(double v) {
		long bits = Double.doubleToRawLongBits(v);
		int exp = (int) ((bits >> 52) & 0x7FF);
		long m = bits & ((1L << 52) - 1);
		if (exp == 0) exp = 1; else m |= 1L << 52;
		int e = exp - 1075; // |v| = m * 2^e
		BigDecimal r = e >= 0 ? new BigDecimal(BigInteger.valueOf(m).shiftLeft(e))
			: new BigDecimal(BigInteger.valueOf(m).multiply(FIVE.pow(-e)), -e);
		return bits < 0 ? r.negate() : r;
	}

	/** 2^k exactly. */
	private static BigDecimal pow2(int k) {
		return k >= 0 ? new BigDecimal(BigInteger.ONE.shiftLeft(k)) : new BigDecimal(FIVE.pow(-k), -k);
	}

	/**
	 * The decimal Double.toString chooses (JDK 19 and later): of the decimals that read back as v (its rounding interval,
	 * the ends included when the significand is even), one of the fewest digits, the closest to v, the even one of two.
	 */
	static BigDecimal shortest(double v) {
		BigDecimal fast = quickShortest(Math.abs(v));
		if (fast != null) return v < 0 ? fast.negate() : fast;
		return exactShortest(v);
	}

	/** shortest by the BigDecimal arithmetic alone (the definition; quickShortest gives the same or null) */
	static BigDecimal exactShortest(double v) {
		double a = Math.abs(v);
		long bits = Double.doubleToRawLongBits(a);
		int exp = (int) ((bits >> 52) & 0x7FF);
		long m = bits & ((1L << 52) - 1);
		if (exp == 0) exp = 1; else m |= 1L << 52;
		int e = exp - 1075;
		BigDecimal x = exact(a);
		BigDecimal above = pow2(e - 1);
		BigDecimal below = m == (1L << 52) && exp > 1 ? pow2(e - 2) : above; // a power of two: the gap below is half
		BigDecimal lo = x.subtract(below), hi = x.add(above);
		boolean ends = (m & 1) == 0;
		// the fewest digits: a decimal of p digits in the interval means one of p+1 too, so the length is searched by halves
		int low = 1, high = 17;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (closest(x, mid, lo, hi, ends, null) != null) high = mid; else low = mid + 1;
		}
		// of that length the closest; with one digit enough, two digits count too (JDK: Double.MIN_VALUE is 4.9E-324)
		BigDecimal best = closest(x, low, lo, hi, ends, null);
		if (low == 1) best = closest(x, 2, lo, hi, ends, best);
		return v < 0 ? best.negate() : best;
	}

	// ---- the same answer in double arithmetic, for most numbers a script makes (a JSON 23.5, a price, 0.25): shortest's
	// BigInteger work was most of the time of the web player's `put total into …` with a real number (Nov 14 2026)

	/** 10^0 … 10^22: exact doubles */
	private static final double[] P10 = new double[23];
	/** For k = 1…7: the double nearest 10^-k is above it (else below; never equal) */
	private static final boolean[] TENTH_ABOVE = new boolean[8];
	static {
		double p = 1;
		for (int i = 0; i < P10.length; i++) { P10[i] = p; p *= 10; }
		for (int k = 1; k < TENTH_ABOVE.length; k++)
			TENTH_ABOVE[k] = exact(1 / P10[k]).compareTo(BigDecimal.ONE.movePointLeft(k)) > 0;
	}
	private static final int QUICK_MIN_E10 = -7, QUICK_MAX_E10 = 15, QUICK_DIGITS = 15;

	/** a ≥ 10^k exactly, for k from -7 to 16 */
	private static boolean atLeastTenTo(double a, int k) {
		if (k >= 0) return a >= P10[k];
		double d = 1 / P10[-k]; // the double nearest 10^k (one correctly rounded division); no double lies between the two
		return TENTH_ABOVE[-k] ? a >= d : a > d;
	}

	/**
	 * shortest(a) for a finite a > 0 from 10^-7 to below 10^16 when it is found with doubles alone, else null. A decimal c·10^s
	 * with c < 2^53 and |s| ≤ 22 reads back as a exactly when c*10^s (or c/10^-s), one correctly rounded operation of exact
	 * doubles, is a (Clinger): that is shortest's interval with its ends, half to even. The decimals of p digits taken are the
	 * nearest to a and its neighbours; when two of them read back as a, which one is closest is left to exactShortest. The
	 * scale is what shortest gives: p - 1 - floor(log10 a).
	 */
	static BigDecimal quickShortest(double a) {
		if (!(a >= 1e-7 && a < 1e16)) return null;
		int e10 = (int) Math.floor(Math.log10(a));
		if (e10 < QUICK_MIN_E10 || e10 > QUICK_MAX_E10) return null;
		if (!atLeastTenTo(a, e10)) e10--; // log10 one off near a power of ten
		else if (atLeastTenTo(a, e10 + 1)) e10++;
		if (e10 < QUICK_MIN_E10 || e10 > QUICK_MAX_E10 || !atLeastTenTo(a, e10) || atLeastTenTo(a, e10 + 1)) return null;
		for (int p = 1; p <= QUICK_DIGITS; p++) {
			int s = e10 - p + 1;
			long c = readsBack(a, s);
			if (c == 0) continue;
			if (c < 0) return null; // two decimals of p digits read back as a
			if (p > 1) return BigDecimal.valueOf(c, -s);
			// one digit is enough: the closest of two digits counts too, the even one on a tie (Double.MIN_VALUE is 4.9E-324)
			long c2 = readsBack(a, s - 1);
			if (c2 != c * 10) return null; // another decimal of two digits reads back as a: which is closer is exactShortest's
			long digits = c;
			while (digits % 10 == 0) digits /= 10; // 10 (a carry: 9.97 to one digit) is 1, odd
			return (digits & 1) == 0 ? BigDecimal.valueOf(c2, 1 - s) : BigDecimal.valueOf(c, -s);
		}
		return null;
	}

	/** The c > 0 of the decimals c·10^s near a that read back as a: 0 none, -1 more than one */
	private static long readsBack(double a, int s) {
		double t = s >= 0 ? a / P10[s] : a * P10[-s];
		long r = (long) Math.rint(t), found = 0;
		for (long c = Math.max(1, r - 2); c <= r + 2; c++) {
			double back = s >= 0 ? (double) c * P10[s] : (double) c / P10[-s];
			if (back != a) continue;
			if (found != 0) return -1;
			found = c;
		}
		return found;
	}

	/** Of the decimals of p digits in [lo, hi] (the ends when allowed), the closest to x (ties: the even last digit), or
	 *  {@code than} when that is closer or no such decimal is. The nearest one and its two neighbours are all to look at. */
	private static BigDecimal closest(BigDecimal x, int p, BigDecimal lo, BigDecimal hi, boolean ends, BigDecimal than) {
		BigDecimal r = roundDigits(x, p);
		BigDecimal step = r.ulp();
		BigDecimal best = than, bestDist = than == null ? null : than.subtract(x).abs();
		for (BigDecimal c : new BigDecimal[]{r, r.subtract(step), r.add(step)}) {
			if (c.signum() <= 0) continue;
			int cl = c.compareTo(lo), ch = c.compareTo(hi);
			if (!((cl > 0 || ends && cl == 0) && (ch < 0 || ends && ch == 0))) continue;
			BigDecimal d = c.subtract(x).abs();
			int cmp = bestDist == null ? -1 : d.compareTo(bestDist);
			if (cmp < 0 || cmp == 0 && !c.stripTrailingZeros().unscaledValue().testBit(0)) { best = c; bestDist = d; }
		}
		return best;
	}

	/** Double.toString: the shortest decimal, plain from 10^-3 to below 10^7 ("0.001", "123.0"), else "1.25E-5", "1.0E7". */
	static String doubleToString(double v) {
		if (Double.isNaN(v)) return "NaN";
		if (Double.isInfinite(v)) return v > 0 ? "Infinity" : "-Infinity";
		if (v == 0) return 1 / v < 0 ? "-0.0" : "0.0";
		BigDecimal d = shortest(v).stripTrailingZeros();
		String sign = v < 0 ? "-" : "";
		double a = Math.abs(v);
		if (a >= 1e-3 && a < 1e7) {
			String plain = d.abs().toPlainString();
			return sign + (plain.indexOf('.') < 0 ? plain + ".0" : plain);
		}
		String digits = d.unscaledValue().abs().toString();
		int exp10 = digits.length() - 1 - d.scale();
		return sign + digits.charAt(0) + "." + (digits.length() > 1 ? digits.substring(1) : "0") + "E" + exp10;
	}

	/**
	 * DecimalFormat.format(double) as the JDK's: the shortest decimal (Double.toString's digits) rounded half to even at the
	 * pattern's fraction digits; a tie in those digits goes by the exact value (0.35 is 0.34999... : "0.3" with "0.0").
	 */
	static String format(DecimalFormat f, double v) {
		if (Double.isNaN(v) || Double.isInfinite(v) || v == 0) return f.format(v);
		int n = f.getMaximumFractionDigits();
		BigDecimal s = shortest(v);
		BigDecimal r;
		if (s.scale() <= n) r = s;
		else {
			BigDecimal down = toScale(s, n, RoundingMode.DOWN);
			BigDecimal half = BigDecimal.valueOf(5).movePointLeft(n + 1);
			BigDecimal tie = down.add(half.multiply(BigDecimal.valueOf(s.signum())));
			if (s.compareTo(tie) != 0) r = toScale(s, n, RoundingMode.HALF_EVEN);
			else {
				int c = exact(v).abs().compareTo(tie.abs()); // the digits are a tie: the exact value decides
				r = toScale(s, n, c == 0 ? RoundingMode.HALF_EVEN : c > 0 ? RoundingMode.UP : RoundingMode.DOWN);
			}
		}
		if (r.signum() == 0) return f.format(v < 0 ? -0.0 : 0.0); // the JDK keeps the sign: "-0"
		return f.format(r);
	}

	// rounding done here with BigInteger (TeaVM's BigDecimal.round left an 18-digit whole number unrounded at 17 digits)

	/** x rounded half to even to p significant digits. */
	static BigDecimal roundDigits(BigDecimal x, int p) {
		BigInteger u = x.unscaledValue();
		int len = digits(u);
		if (len <= p) return x;
		int d = len - p;
		return new BigDecimal(divide(u, pow10(d), RoundingMode.HALF_EVEN), x.scale() - d);
	}

	/** x with n digits after the point, rounded (HALF_EVEN, UP: away from zero, DOWN: toward zero). */
	static BigDecimal toScale(BigDecimal x, int n, RoundingMode mode) {
		int sc = x.scale();
		if (sc <= n) return new BigDecimal(x.unscaledValue().multiply(pow10(n - sc)), n);
		return new BigDecimal(divide(x.unscaledValue(), pow10(sc - n), mode), n);
	}

	private static BigInteger[] tens = new BigInteger[0];
	/** 10^k (kept). */
	static synchronized BigInteger pow10(int k) {
		if (k >= tens.length) {
			BigInteger[] t = java.util.Arrays.copyOf(tens, Math.max(k + 1, tens.length * 2));
			for (int i = tens.length; i < t.length; i++) t[i] = i == 0 ? BigInteger.ONE : t[i - 1].multiply(BigInteger.TEN);
			tens = t;
		}
		return tens[k];
	}
	/** The number of decimal digits of |u| (0 has one). */
	static int digits(BigInteger u) {
		int b = u.abs().bitLength();
		if (b == 0) return 1;
		int est = (int) (b * 0.30102999566398114) + 1; // the digits of 2^b: right, or one more than |u| has
		return u.abs().compareTo(pow10(est - 1)) < 0 ? est - 1 : est;
	}

	private static BigInteger divide(BigInteger u, BigInteger by, RoundingMode mode) {
		BigInteger[] qr = u.divideAndRemainder(by);
		BigInteger q = qr[0], r = qr[1];
		if (r.signum() == 0) return q;
		boolean away;
		if (mode == RoundingMode.UP) away = true;
		else if (mode == RoundingMode.DOWN) away = false;
		else {
			int c = r.abs().shiftLeft(1).compareTo(by);
			away = c > 0 || c == 0 && q.testBit(0);
		}
		return away ? q.add(BigInteger.valueOf(u.signum())) : q;
	}
}

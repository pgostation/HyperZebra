/**
 * The local date and time arithmetic the 68K VM did with java.time (which the browser's class library lacks): the same
 * fields, the same checks, the same text.
 */
final class LocalTime68k {
	private LocalTime68k() {}

	/** {year, month 1-12, day, hour, minute, second} now in the default time zone (LocalDateTime.now()). */
	static int[] now() {
		java.util.Calendar c = new java.util.GregorianCalendar();
		return new int[] {c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
			c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND)};
	}
	/** LocalDateTime.now().withNano(0).toString(): 2026-11-09T20:15:03 (the seconds left out when they are 0, as java.time does). */
	static String isoNow() {
		int[] t = now();
		StringBuilder s = new StringBuilder();
		if (t[0] < 0 || t[0] > 9999) s.append(t[0] > 9999 ? "+" : "").append(t[0]); else s.append(String.format("%04d", t[0]));
		s.append('-').append(two(t[1])).append('-').append(two(t[2])).append('T').append(two(t[3])).append(':').append(two(t[4]));
		if (t[5] != 0) s.append(':').append(two(t[5]));
		return s.toString();
	}
	private static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }
	/** LocalDateTime.of(...).toEpochSecond(ZoneOffset.ofTotalSeconds(offset)); IllegalArgumentException for a field out of range. */
	static long epochSecond(int year, int month, int day, int hour, int minute, int second, int offsetSeconds) {
		if (year < -999_999_999 || year > 999_999_999 || month < 1 || month > 12 || day < 1 || day > monthLength(year, month)
				|| hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59 || offsetSeconds < -18 * 3600 || offsetSeconds > 18 * 3600)
			throw new IllegalArgumentException("invalid date-time");
		return daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second - offsetSeconds;
	}
	static int monthLength(int year, int month) {
		switch (month) {
			case 2: return (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
			case 4: case 6: case 9: case 11: return 30;
			default: return 31;
		}
	}
	/** Days from 1970-01-01 to the date (proleptic Gregorian). */
	static long daysFromCivil(long y, int m, int d) {
		y -= m <= 2 ? 1 : 0;
		long era = Math.floorDiv(y, 400);
		long yoe = y - era * 400;
		long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
		long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
		return era * 146097 + doe - 719468;
	}
}

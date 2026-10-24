import java.util.ArrayList;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HyperTalkの日付/時刻表現(the date/the time/convert/is a date)の解析と整形。
 *
 * 対応する形式(英語):
 *   seconds        1904/1/1 0:00からの秒数(the secondsと同じ基準)
 *   dateItems      年,月,日,時,分,秒,曜日(1=日曜)
 *   short date     9/14/26          (the date)
 *   abbrev date    Mon, Sep 14, 2026
 *   long date      Monday, September 14, 2026
 *   short time     6:24 PM          (the time)
 *   long time      6:24:30 PM
 * 日本語環境では the date が "26.09.14"、the long date が "2026年 9月 14日 月曜日" になるので
 * その形式も読み書きする。日付と時刻はスペース区切りで組み合わせられる("1/1/00 3:00 PM")。
 */
class HCDateTime {
	/** 1904/1/1 0:00(UTC)から1970/1/1 0:00(UTC)までの秒数 */
	static final long MAC_EPOCH_OFFSET_SECONDS = 2_082_844_800L;

	static final String[] MONTHS = {"January", "February", "March", "April", "May", "June",
		"July", "August", "September", "October", "November", "December"};
	static final String[] SHORT_MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
		"Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
	static final String[] WEEKDAYS = {"Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"};
	static final String[] SHORT_WEEKDAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
	static final String[] JAPANESE_WEEKDAYS = {"日曜日","月曜日","火曜日","水曜日","木曜日","金曜日","土曜日"};

	private static final Pattern SECONDS = Pattern.compile("^-?[0-9]+$");
	private static final Pattern DATE_ITEMS = Pattern.compile("^-?[0-9]+(\\s*,\\s*-?[0-9]+){5,6}$");
	private static final Pattern TIME_SUFFIX = Pattern.compile(
		"(?:^|\\s+)([0-9]{1,2}):([0-9]{1,2})(?::([0-9]{1,2}))?(?:\\s*([AaPp][Mm]))?$");
	private static final Pattern SLASH_DATE = Pattern.compile("^([0-9]{1,4})/([0-9]{1,2})/([0-9]{1,4})$");
	private static final Pattern DOT_DATE = Pattern.compile("^([0-9]{1,4})\\.([0-9]{1,2})\\.([0-9]{1,4})$");
	private static final Pattern ENGLISH_DATE = Pattern.compile(
		"^(?:([A-Za-z]+),?\\s+)?([A-Za-z]+)\\.?\\s+([0-9]{1,2}),?\\s+([0-9]{1,4})$");
	private static final Pattern JAPANESE_DATE = Pattern.compile(
		"^([0-9]{1,4})年\\s*([0-9]{1,2})月\\s*([0-9]{1,2})日(?:\\s*\\(?([日月火水木金土])曜?日?\\)?)?$");

	static boolean isJapanese(){
		return PCARD.pc!=null && International.sameLanguage(PCARD.pc.lang, "ja");
	}

	//---------------------------------------------------------------- 解析

	/** HyperTalkの日付/時刻テキストをCalendar(ローカル時刻)にする。解釈できなければnull */
	static Calendar parse(String text){
		if(text==null) return null;
		String s = text.trim();
		if(s.isEmpty()) return null;
		Calendar cal = Calendar.getInstance();
		cal.setLenient(true);

		if(SECONDS.matcher(s).matches()){
			try{
				return fromMacSeconds(Long.parseLong(s));
			}catch(NumberFormatException e){
				return null;
			}
		}
		if(DATE_ITEMS.matcher(s).matches()){
			String[] items = s.split("\\s*,\\s*");
			int[] v = new int[items.length];
			try{
				for(int i=0; i<items.length; i++) v[i] = Integer.parseInt(items[i]);
			}catch(NumberFormatException e){
				return null;
			}
			cal.clear();
			cal.set(v[0], v[1]-1, v[2], v[3], v[4], v[5]);
			cal.getTimeInMillis();//lenientな正規化(24時→翌日など)
			return cal;
		}

		//末尾の時刻を切り離す
		String datePart = s;
		int hour=0, minute=0, second=0;
		boolean hasTime = false;
		Matcher tm = TIME_SUFFIX.matcher(s);
		if(tm.find()){
			hasTime = true;
			hour = Integer.parseInt(tm.group(1));
			minute = Integer.parseInt(tm.group(2));
			if(tm.group(3)!=null) second = Integer.parseInt(tm.group(3));
			if(tm.group(4)!=null){
				boolean pm = Character.toUpperCase(tm.group(4).charAt(0))=='P';
				if(hour>12) return null;
				hour = hour%12 + (pm ? 12 : 0);
			}
			if(hour>23 || minute>59 || second>59) return null;
			datePart = s.substring(0, tm.start()).trim();
		}

		int year, month, day;
		if(datePart.isEmpty()){
			if(!hasTime) return null;
			year = cal.get(Calendar.YEAR);
			month = cal.get(Calendar.MONTH)+1;
			day = cal.get(Calendar.DAY_OF_MONTH);
		}
		else{
			int[] ymd = parseDatePart(datePart);
			if(ymd==null) return null;
			year = ymd[0]; month = ymd[1]; day = ymd[2];
		}
		if(month<1 || month>12 || day<1 || day>31) return null;
		cal.clear();
		cal.set(year, month-1, day, hour, minute, second);
		cal.getTimeInMillis();
		return cal;
	}

	/** 日付部分だけを {年,月,日} にする。解釈できなければnull */
	private static int[] parseDatePart(String datePart){
		Matcher m = SLASH_DATE.matcher(datePart);
		if(m.matches()){
			int a = Integer.parseInt(m.group(1));
			int b = Integer.parseInt(m.group(2));
			int c = Integer.parseInt(m.group(3));
			//4桁始まりなら年/月/日、そうでなければ月/日/年
			if(m.group(1).length()==4) return new int[]{a, b, c};
			return new int[]{expandYear(c, m.group(3).length()), a, b};
		}
		m = DOT_DATE.matcher(datePart);
		if(m.matches()){
			//日本語環境の the date は 年.月.日
			return new int[]{expandYear(Integer.parseInt(m.group(1)), m.group(1).length()),
				Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
		}
		m = JAPANESE_DATE.matcher(datePart);
		if(m.matches()){
			return new int[]{expandYear(Integer.parseInt(m.group(1)), m.group(1).length()),
				Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
		}
		m = ENGLISH_DATE.matcher(datePart);
		if(m.matches()){
			int month = monthNumber(m.group(2));
			if(month<0) return null;
			return new int[]{expandYear(Integer.parseInt(m.group(4)), m.group(4).length()),
				month, Integer.parseInt(m.group(3))};
		}
		return null;
	}

	/** 2桁の年は 00〜39 を 2000年代、40〜99 を 1900年代とみなす: the window of the 68K VM's StringToDate, through which
	 *  HyperCard J-2.3 in the VM converts (measured: "1/2/37" → 2037, "1/2/40" → 1940; Caliban's Calendar doc "12/7/41" → 1941).
	 *  It was 50, so convert "12/7/41" gave 2041 here and 1941 in HyperCard. */
	private static int expandYear(int year, int digits){
		if(digits>2) return year;
		return year<40 ? 2000+year : 1900+year;
	}

	private static int monthNumber(String name){
		for(int i=0; i<12; i++){
			if(MONTHS[i].equalsIgnoreCase(name) || SHORT_MONTHS[i].equalsIgnoreCase(name)) return i+1;
		}
		return -1;
	}

	//---------------------------------------------------------------- 整形

	//HyperCardのsecondsはタイムゾーンを持たない「ローカル時刻の壁時計」で
	//1904/1/1 0:00からの秒数を数える(convert "1/1/2000" to secondsは
	//どこで実行しても3029529600)。UTC基準にすると日本では9時間ずれる。
	/** 現在時刻。外部コマンドがSetDateTimeで変えたMacの時計(その実行中だけのずれ、ホストの時計はそのまま)に従う(XTernal CollectionのSetDateTime) */
	static Calendar now(){
		Calendar cal = Calendar.getInstance();
		long offset = Toolbox68k.programClockSeconds();
		if(offset!=0) cal.setTimeInMillis(cal.getTimeInMillis()+offset*1000L);
		return cal;
	}

	static long toMacSeconds(Calendar cal){
		long millis = cal.getTimeInMillis();
		long local = millis + cal.getTimeZone().getOffset(millis);
		return Math.floorDiv(local, 1000L) + MAC_EPOCH_OFFSET_SECONDS;
	}

	static Calendar fromMacSeconds(long seconds){
		Calendar cal = Calendar.getInstance();
		long local = (seconds - MAC_EPOCH_OFFSET_SECONDS) * 1000L;
		//ローカル壁時計→UTC。オフセットは求めたUTC時刻で再評価して夏時間に追従する
		long utc = local - cal.getTimeZone().getOffset(local);
		utc = local - cal.getTimeZone().getOffset(utc);
		cal.setTimeInMillis(utc);
		return cal;
	}

	/** "abbr"/"abbreviated"を"abbrev"に、"date"/"time"だけならshortを補う */
	static String normalizeFormat(String format){
		String f = format.trim().toLowerCase().replaceAll("\\s+", " ");
		if(f.startsWith("abbr ")) f = "abbrev "+f.substring(5);
		else if(f.startsWith("abbreviated ")) f = "abbrev "+f.substring(12);
		if(f.equals("date")) f = "short date";
		if(f.equals("time")) f = "short time";
		if(f.equals("abbrev time")) f = "short time"; //the abbreviated time is the short time (AOL Airports: convert theTime to abbr time)
		if(f.equals("english date")) f = "short date";
		if(f.equals("english time")) f = "short time";
		return f;
	}

	static boolean isFormatName(String format){
		String f = normalizeFormat(format);
		return f.equals("seconds") || f.equals("secs") || f.equals("dateitems") ||
			f.equals("short date") || f.equals("abbrev date") || f.equals("long date") ||
			f.equals("short time") || f.equals("long time");
	}

	/**
	 * Calendarを指定形式の文字列にする。形式は normalizeFormat 済みでなくてもよい。
	 * 未知の形式はnull。
	 */
	static String format(Calendar cal, String format){
		String f = normalizeFormat(format);
		if(f.equals("seconds") || f.equals("secs")){
			return Long.toString(toMacSeconds(cal));
		}
		if(f.equals("dateitems")){
			return cal.get(Calendar.YEAR)+","+(cal.get(Calendar.MONTH)+1)+","+cal.get(Calendar.DAY_OF_MONTH)+","+
				cal.get(Calendar.HOUR_OF_DAY)+","+cal.get(Calendar.MINUTE)+","+cal.get(Calendar.SECOND)+","+
				cal.get(Calendar.DAY_OF_WEEK);
		}
		int year = cal.get(Calendar.YEAR);
		int month = cal.get(Calendar.MONTH)+1;
		int day = cal.get(Calendar.DAY_OF_MONTH);
		int dow = cal.get(Calendar.DAY_OF_WEEK)-1;
		if(f.equals("short date")){
			String yy = Integer.toString(Math.floorMod(year, 100));
			if(yy.length()==1) yy = "0"+yy;
			if(isJapanese()){
				return yy+"."+pad2(month)+"."+pad2(day);
			}
			return month+"/"+day+"/"+yy;
		}
		if(f.equals("abbrev date")){
			return SHORT_WEEKDAYS[dow]+", "+SHORT_MONTHS[month-1]+" "+day+", "+year;
		}
		if(f.equals("long date")){
			if(isJapanese()){
				return year+"年 "+month+"月 "+day+"日 "+JAPANESE_WEEKDAYS[dow];
			}
			return WEEKDAYS[dow]+", "+MONTHS[month-1]+" "+day+", "+year;
		}
		if(f.equals("short time") || f.equals("long time")){
			int hour24 = cal.get(Calendar.HOUR_OF_DAY);
			int hour = hour24%12;
			if(hour==0) hour = 12;
			String text = hour+":"+pad2(cal.get(Calendar.MINUTE));
			if(f.equals("long time")) text += ":"+pad2(cal.get(Calendar.SECOND));
			return text+(hour24>=12 ? " PM" : " AM");
		}
		return null;
	}

	/** "short date and long time"のように複数形式をandで並べたものを整形する。未知の形式はnull */
	static String formatCombined(Calendar cal, ArrayList<String> formats){
		StringBuilder sb = new StringBuilder();
		for(String format : formats){
			String text = format(cal, format);
			if(text==null) return null;
			if(sb.length()>0) sb.append(' ');
			sb.append(text);
		}
		return sb.toString();
	}

	private static String pad2(int value){
		return value<10 ? "0"+value : Integer.toString(value);
	}
}

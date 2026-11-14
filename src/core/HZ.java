/**
 * The state of the HyperTalk core that used to live only on the desktop's card window (PCARD.pc): the current stack and
 * the user interface language catalog. The core reads them here ({@link #stack()}, {@link #intl()}), so it needs no
 * window. On the desktop they stay in the card window's fields (PCARD.pc.stack, PCARD.pc.intl), which {@link #mirror}
 * (set by PCARD's constructor) reads and writes: one place holds each value, however it is set.
 */
final class HZ {
	private HZ() {}

	/** Bumped when a script changes: the JIT's compiled handlers (TalkJit, desktop) are made again. */
	static volatile int scriptEpoch;

	/** The field the selection functions look at (was GUI's). */
	static volatile OField clickField;
	/** The background is being edited (Edit > Background; was PaintTool's). */
	static boolean editBackground = false;
	/** The stack whose handler queued the message being run (StackGuard's; was a field of StackGuard). */
	static volatile OStack queuedBy;
	/** The variables of the handler that called the running external (TXcmd's; was a field of TXcmd). */
	static final ThreadLocal<MemoryData> callVariables = new ThreadLocal<MemoryData>();
	// the headless core's own values (the desktop keeps them in its card window, through the mirror)
	private static OStack stack;
	private static International intl;

	/** The stack in the card window (PCARD.pc.stack on the desktop). */
	static OStack stack() { Mirror m = mirror; return m != null ? m.stack() : stack; }
	/** The user interface language catalog (PCARD.pc.intl on the desktop). */
	static International intl() { Mirror m = mirror; return m != null ? m.intl() : intl; }

	/** The desktop's card window, which holds the stack and catalog there (null in the headless core). */
	interface Mirror { OStack stack(); void setStack(OStack s); International intl(); void setIntl(International i); }
	static Mirror mirror;

	static void setStack(OStack s) { Mirror m = mirror; if (m != null) m.setStack(s); else stack = s; }
	static void setIntl(International i) { Mirror m = mirror; if (m != null) m.setIntl(i); else intl = i; }

	// ---- moved from PCARD (the card window): state of the HyperTalk core ----
	static OStack home;
	static String AppName = "HyperZebra";
	static final String longVersion="3.0.3";
	static final String version=longVersion.substring(0, longVersion.lastIndexOf('.'));
	static boolean useDoubleBuffer = true;//バッファをオフにしてメモリ節約できる？
	//HyperTalkは専用スレッド、描画はEDTで行うため、両スレッド間で必ず最新値を
	//共有する必要がある。
	static volatile boolean lockedScreen=false;
	//visual effect用に、画面遷移前後の状態をオフスクリーンへキャプチャしている間だけtrueに
	//する。lockedScreen中はボタン/フィールドの描画を止めて画面を凍結させるが、この
	//キャプチャ自体はlockedScreen==trueのまま行われるため、キャプチャ中はlockedScreenの
	//チェックを無視して普通に描画させる必要がある(そうしないと、視覚効果の後に一部の
	//部品が真っ白のまま描画されないままになってしまう)。
	static volatile boolean capturingEffect=false;
	static boolean showGroups=false;
	static int visual=0;
	static int toVisual=0; /* to black1 white2 grey3 inverse4 */
	static int visSpd=3; /* very fast1 fast2 normal3 slow4 veryslow5 */
	static int editMode = 0; //0:ブラウジング  1:アウトライン強調
	static int userLevel = 5;

	/**
	 * HyperCard's 8-digit version code, as "the long version" and the items of "the version of stack" report it:
	 * major (2 digits), minor, bug fix, stage (20 development, 40 alpha, 60 beta, 80 final) and release (2 digits).
	 * "2.2.1" → "02218000", "3.0a5" → "03004005", HyperZebra's "3.0.0" → "03008000". A code is returned as it is.
	 */
	static String hcVersionCode(String v) {
		if(v==null) return "00000000";
		v = v.trim();
		if(v.matches("[0-9]{8}")) return v;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?\\s*([dabf])?\\s*(\\d+)?.*").matcher(v);
		if(!m.matches()) return "00000000";
		int major = Integer.parseInt(m.group(1));
		int minor = m.group(2)==null ? 0 : Math.min(9, Integer.parseInt(m.group(2)));
		int fix = m.group(3)==null ? 0 : Math.min(9, Integer.parseInt(m.group(3)));
		String st = m.group(4);
		int stage = st==null ? 0x80 : st.equals("d") ? 0x20 : st.equals("a") ? 0x40 : st.equals("b") ? 0x60 : 0x80;
		int release = m.group(5)==null || st==null ? 0 : Math.min(99, Integer.parseInt(m.group(5)));
		return String.format("%02d%d%d%02X%02d", Math.min(99, major), minor, fix, stage, release);
	}

	// ---- the find command's last match (moved from PCARD) ----
	static int foundIndex = 0;
	static String foundText;
	static String foundObject;
}

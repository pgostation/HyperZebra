/**
 * Big-endian words and longs of the 68K RAM's byte array (P4-1). On the JVM one host load or store each (VarHandle byte
 * array views, VarHandleBytes68k, which HotSpot compiles with a byte swap); where there are no VarHandles (the browser build)
 * byte shifts. Chosen once: the one implementation in use is inlined at M68kMemory's accesses.
 */
abstract class BigEndian68k {
	abstract int word(byte[] b, int a);
	abstract int lng(byte[] b, int a);
	abstract void setWord(byte[] b, int a, int v);
	abstract void setLong(byte[] b, int a, int v);
	/** Clears the mask's bits of a[i], atomically where threads share the array (VarHandle.getAndBitwiseAnd); the bits before. */
	long clearBits(long[] a, int i, long mask) { long old = a[i]; a[i] = old & ~mask; return old; }

	static final BigEndian68k BYTES = choose();

	private static BigEndian68k choose() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new Shifts();
		try { return (BigEndian68k) Class.forName("VarHandleBytes68k").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new Shifts(); }
	}

	/** Without VarHandles: byte by byte */
	static final class Shifts extends BigEndian68k {
		int word(byte[] b, int a) { return ((b[a] & 0xff) << 8) | (b[a + 1] & 0xff); }
		int lng(byte[] b, int a) { return (b[a] << 24) | ((b[a + 1] & 0xff) << 16) | ((b[a + 2] & 0xff) << 8) | (b[a + 3] & 0xff); }
		void setWord(byte[] b, int a, int v) { b[a] = (byte) (v >>> 8); b[a + 1] = (byte) v; }
		void setLong(byte[] b, int a, int v) { b[a] = (byte) (v >>> 24); b[a + 1] = (byte) (v >>> 16); b[a + 2] = (byte) (v >>> 8); b[a + 3] = (byte) v; }
	}
}

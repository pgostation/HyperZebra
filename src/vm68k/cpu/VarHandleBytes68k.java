/** BigEndian68k on the JVM: VarHandle byte array views, one host load or store each where HotSpot compiles them. */
final class VarHandleBytes68k extends BigEndian68k {
	private static final java.lang.invoke.VarHandle BIG_SHORT = java.lang.invoke.MethodHandles.byteArrayViewVarHandle(short[].class, java.nio.ByteOrder.BIG_ENDIAN),
		BIG_INT = java.lang.invoke.MethodHandles.byteArrayViewVarHandle(int[].class, java.nio.ByteOrder.BIG_ENDIAN);
	VarHandleBytes68k() {}
	int word(byte[] b, int a) { return (short) BIG_SHORT.get(b, a) & 0xffff; }
	int lng(byte[] b, int a) { return (int) BIG_INT.get(b, a); }
	void setWord(byte[] b, int a, int v) { BIG_SHORT.set(b, a, (short) v); }
	void setLong(byte[] b, int a, int v) { BIG_INT.set(b, a, v); }
	private static final java.lang.invoke.VarHandle BITS = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
	@Override long clearBits(long[] a, int i, long mask) { return (long) BITS.getAndBitwiseAnd(a, i, ~mask); }
}

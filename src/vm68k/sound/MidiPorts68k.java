/**
 * The modem and printer ports' MIDI as the 68K VM sends and hears it (P4): the desktop's MidiPorts68kHost (found by name)
 * is MidiHost68k (the host's MIDI devices and synthesizer); without it there are no MIDI devices: nothing is played or heard.
 */
class MidiPorts68k {
	static final int MODEM = 0, PRINTER = 1;
	/** Hears MIDI messages arriving at a port. */
	interface Listener { void received(int port, byte[] message); }

	private static final MidiPorts68k IMPL = find();
	private static MidiPorts68k find() {
		// -Dhz.portable68k=true: the browser player's parts on a JVM too (tests of the player's 68K path)
		if (Boolean.getBoolean("hz.portable68k")) return new MidiPorts68k();
		try { return (MidiPorts68k) Class.forName("MidiPorts68kHost").getDeclaredConstructor().newInstance(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return new MidiPorts68k(); }
	}
	void doSend(int port, byte[] message) {}
	void doAddListener(Listener l) {}
	void doRemoveListener(Listener l) {}

	/** Plays a message on the port's output (MidiHost68k.send). */
	static void send(int port, byte[] message) { IMPL.doSend(port, message); }
	static void addListener(Listener l) { IMPL.doAddListener(l); }
	static void removeListener(Listener l) { IMPL.doRemoveListener(l); }
	/** The bytes as hex pairs (traces). */
	static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) { int v = x & 0xff; s.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 15, 16)).append(' '); } return s.toString().trim().toUpperCase(); }
}

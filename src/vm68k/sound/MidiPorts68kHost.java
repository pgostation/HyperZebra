/** {@link MidiPorts68k} on the desktop: MidiHost68k. */
final class MidiPorts68kHost extends MidiPorts68k {
	@Override void doSend(int port, byte[] message) { MidiHost68k.send(port, message); }
	@Override void doAddListener(Listener l) { MidiHost68k.addListener(l); }
	@Override void doRemoveListener(Listener l) { MidiHost68k.removeListener(l); }
}

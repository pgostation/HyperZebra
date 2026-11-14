/**
 * The headless core's message loop: HyperTalk messages queued with TTalk.CallMessage run on the calling thread, through
 * the same dispatch as the desktop's script thread (TTalk.run), until the queue is empty. No script thread is started.
 */
final class TalkPump {
	private TalkPump() {}

	/** Makes the script engine without starting its thread (TTalk.talk), as TTalk.run does before its loop. */
	static TTalk start() { return TTalk.startWithoutThread(); }

	/** Runs queued messages until the queue is empty or max have run; returns how many ran. */
	static int drain(int max) {
		TTalk t = TTalk.talk;
		if (t == null) t = start();
		TTalk.pumpThread = Thread.currentThread(); // the script thread of the headless core
		int n = 0;
		while (n < max) {
			Message msg = TTalk.takeMessage();
			if (msg == null) break;
			t.dispatch(msg);
			n++;
		}
		return n;
	}
}

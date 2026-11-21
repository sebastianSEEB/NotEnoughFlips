package dev.notenoughflips;

/**
 * Hypixel's clock, as seen from this PC. Auction start times are Hypixel's, so a new BIN's hold ends at Hypixel's
 * "start + 20 s": a PC clock that's 1-2 s off would put the countdown 1-2 s off. Every auction API response carries a
 * Date header (whole seconds); each one bounds the offset between the two clocks, and keeping the tightest bounds over
 * many responses (one a second while sniping) pins it to a few tens of milliseconds.
 */
final class ServerClock {
	private ServerClock() {}

	private static long lo = Long.MIN_VALUE, hi = Long.MAX_VALUE, since;
	private static volatile long offset;          // Hypixel time − PC time, ms
	private static volatile boolean known;

	static final long RESET_MS = 10 * 60_000L;    // start over now and then (PC clocks drift)

	/**
	 * One response: sent at sentPc, headers received at recvPc (PC time), Date header = dateMs (Hypixel time, floored to
	 * the second). Hypixel's clock read date..date+999 at some moment between send and receive.
	 */
	static synchronized void sample(long sentPc, long recvPc, long dateMs) {
		if (recvPc < sentPc || recvPc - sentPc > 5_000) return;          // a slow response says little
		long a = dateMs - recvPc, b = dateMs + 999 - sentPc;
		if (recvPc - since > RESET_MS || a > hi || b < lo) {               // first, stale, or the clock jumped
			lo = a;
			hi = b;
			since = recvPc;
		} else {
			lo = Math.max(lo, a);
			hi = Math.min(hi, b);
		}
		offset = lo + (hi - lo) / 2;
		known = true;
	}

	/** Hypixel's time now, in ms (the PC clock until a response has been seen). */
	static long now() {
		return System.currentTimeMillis() + offset;
	}

	static boolean known() {
		return known;
	}

	/** How sure (± ms) the offset is; large until a few responses came in. */
	static synchronized long uncertainty() {
		return known ? (hi - lo) / 2 : Long.MAX_VALUE;
	}

	/** For tests. */
	static synchronized void reset() {
		lo = Long.MIN_VALUE;
		hi = Long.MAX_VALUE;
		since = 0;
		offset = 0;
		known = false;
	}

	static long offset() {
		return offset;
	}
}

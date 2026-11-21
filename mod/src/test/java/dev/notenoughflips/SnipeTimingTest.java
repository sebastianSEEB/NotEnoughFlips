package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Beating the BIN hold by ear: Hypixel's clock, the countdown text and the beat times. */
class SnipeTimingTest {

	@Test
	void hypixelsClockIsPinnedDownFromDateHeaders() {
		ServerClock.reset();
		long trueOffset = 1_734;                            // this PC is 1.734 s behind Hypixel
		long pc = 1_000_000_000L;
		// one response a second, 80 ms round trip, at varying points within Hypixel's second
		for (int i = 0; i < 60; i++) {
			long sent = pc + i * 1_000L + (i * 37) % 1000;
			long recv = sent + 80;
			long serverAtReply = sent + 40 + trueOffset;
			long date = serverAtReply / 1000 * 1000;
			ServerClock.sample(sent, recv, date);
		}
		assertTrue(Math.abs(ServerClock.offset() - trueOffset) <= 60, "offset " + ServerClock.offset());
		assertTrue(ServerClock.uncertainty() <= 60);
		ServerClock.reset();
	}

	@Test
	void countdownText() {
		assertEquals("3.4s", Sniper.countdown(3_350));
		assertEquals("0.1s", Sniper.countdown(20));
		assertEquals("BUY NOW", Sniper.countdown(0));
		assertEquals("BUY NOW", Sniper.countdown(-2_000));
		assertEquals(null, Sniper.countdown(-4_000), "long gone: no countdown");
		assertEquals(null, Sniper.countdown(Long.MIN_VALUE), "unknown listing time");
	}

	@Test
	void beatsAreThreeTicksAndGoAtTheMoment() {
		long[] b = Sniper.beats(12_000, 40);
		assertEquals(4, b.length);
		assertEquals(8_960, b[0]);
		assertEquals(11_960, b[3], "go, played a little early so you hear it on time");
		long[] late = Sniper.beats(1_500, 40);               // alert came late: only what's still ahead
		assertEquals(2, late.length);
		assertEquals(1_460, late[1]);
	}
}

package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The bazaar order-book watch: outbids, undercuts, whether the spread holds, and your time on top. */
class BzWatchTest {

	@Test
	void countsOutbidsAndUndercutsBetweenSnapshots() {
		BzWatch.Book b = new BzWatch.Book();
		long t = 1_000_000L;
		b.observe(t, 100, 110);
		b.observe(t + 30_000, 101, 110);      // higher buy order: outbid
		b.observe(t + 60_000, 101, 109);      // cheaper sell offer: undercut
		b.observe(t + 90_000, 100, 109);      // top order filled or pulled: not an outbid
		b.observe(t + 90_000, 105, 105);      // same snapshot again: ignored
		assertEquals(1, b.outbids.size());
		assertEquals(1, b.undercuts.size());
		assertEquals(90_000, b.watchedMs());
		assertEquals(1, b.watched.size(), "one stretch, not one entry per snapshot");
		assertEquals(30, b.stepSeconds(), 0.5);
	}

	@Test
	void longGapsArentCountedAsWatched() {
		BzWatch.Book b = new BzWatch.Book();
		b.observe(0 + 1, 100, 110);
		b.observe(3_600_000, 120, 130);       // an hour later (game was closed): no outbid counted, no time
		assertEquals(0, b.outbids.size());
		assertEquals(0, b.watchedMs());
	}

	@Test
	void timeOnTop() {
		assertEquals(1.0, BzWatch.onTop(0, 5), 1e-9, "nobody competes");
		assertTrue(BzWatch.onTop(1, 5) > 0.95, "outbid once an hour, checked every 5 min: on top nearly always");
		double busy = BzWatch.onTop(60, 5);   // outbid every minute, you re-check every 5
		assertTrue(busy > 0.15 && busy < 0.25, "about a fifth: " + busy);
		assertTrue(BzWatch.onTop(60, 2) > busy, "checking more often keeps you on top longer");
	}

	@Test
	void aSpreadThatOnlyJustOpenedDoesntHold() {
		List<double[]> sp = List.of(new double[]{0, 0.01}, new double[]{1, 0.01}, new double[]{2, 0.01}, new double[]{3, 0.012}, new double[]{4, 0.10});
		assertEquals(0.2, BzWatch.held(sp, 0.10), 1e-9);
		assertEquals(1.0, BzWatch.held(sp, 0.012), 1e-9);
	}

	@Test
	void notEnoughWatchedTimeChangesNothing() {
		assertEquals(-1, BzWatch.perHour(3, 5 * 60_000L), 1e-9);
		assertEquals(12, BzWatch.perHour(6, 30 * 60_000L), 1e-9);
	}

	@Test
	void everyText() {
		assertEquals("every ~2m", BzWatch.every(30, false));
		assertEquals("every ~40s or faster", BzWatch.every(90, true));
		assertEquals("never", BzWatch.every(0, false));
	}
}

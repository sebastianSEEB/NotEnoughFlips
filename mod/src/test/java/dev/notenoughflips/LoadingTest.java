package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The SkyCofl request line: snipes and hovers go before background work. */
class LoadingTest {

	private record R(String name, CoflClient.Pri pri, long seq) {}

	private static R pick(List<R> rs) {
		return CoflClient.pick(rs, R::pri, R::seq);
	}

	@Test
	void snipeGoesBeforeEverythingQueuedEarlier() {
		List<R> rs = List.of(new R("bg1", CoflClient.Pri.LOW, 1), new R("bg2", CoflClient.Pri.LOW, 2),
				new R("hover", CoflClient.Pri.HIGH, 3), new R("snipe", CoflClient.Pri.URGENT, 4));
		assertEquals("snipe", pick(rs).name());
	}

	@Test
	void hoverGoesBeforeBackgroundAndTheCurrentHoverFirst() {
		List<R> rs = List.of(new R("bg", CoflClient.Pri.LOW, 1), new R("page", CoflClient.Pri.VISIBLE, 2),
				new R("now", CoflClient.Pri.HIGH, 5), new R("passedOver", CoflClient.Pri.HIGH, 4));
		assertEquals("now", pick(rs).name());
		List<R> snipes = List.of(new R("second", CoflClient.Pri.URGENT, 7), new R("first", CoflClient.Pri.URGENT, 6));
		assertEquals("first", pick(snipes).name());
		List<R> bg = List.of(new R("lessImportant", CoflClient.Pri.LOW, 9), new R("mostImportant", CoflClient.Pri.LOW, 8));
		assertEquals("mostImportant", pick(bg).name());
	}

	@Test
	void backgroundLeavesTokensForSnipesAndHovers() {
		assertEquals(1, CoflClient.need(CoflClient.Pri.URGENT));
		assertEquals(1, CoflClient.need(CoflClient.Pri.HIGH));
		assertTrue(CoflClient.need(CoflClient.Pri.LOW) > CoflClient.need(CoflClient.Pri.VISIBLE));
		// a snipe's two requests plus a hover fit in what background work must leave untouched
		assertTrue(CoflClient.BURST - (CoflClient.need(CoflClient.Pri.LOW) - 1) >= 2);
		// worst minute: a full burst plus steady refill stays under SkyCofl's 100 requests/min
		assertTrue(CoflClient.BURST + 60_000.0 / CoflClient.REFILL_MS < 100);
	}

	@Test
	void snipeWaitsLongerWhileItsHistoryIsStillComing() {
		assertFalse(Sniper.giveUp(45_000, "loading"), "a request on its way gets more time");
		assertTrue(Sniper.giveUp(95_000, "loading"));
		assertTrue(Sniper.giveUp(6_000, "empty"), "SkyCofl has no sales: say so quickly");
		assertFalse(Sniper.giveUp(3_000, "empty"));
		assertFalse(Sniper.giveUp(20_000, "failed"));
		assertTrue(Sniper.giveUp(31_000, "none"));
	}

	@Test
	void anItemThatCantBeCheckedIsSaidOnceAndNotCalledBad() {
		assertTrue(Sniper.once("Frozen Scythe|unchecked: x"));
		assertFalse(Sniper.once("Frozen Scythe|unchecked: x"), "same item, same verdict: no second chat line");
		assertTrue(Sniper.once("Amber Power Scroll|unchecked: x"));
		assertEquals("too few sales", Sniper.reason("bad: too few sales"));
		assertEquals("couldn't load", Sniper.reason("unchecked: couldn't load"));
	}
}

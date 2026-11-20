package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Undercut alerts, list price suggestion, trade check and session stats. */
class NewFeaturesTest {

	@Test
	void undercutNeedsARealDifference() {
		assertTrue(Undercut.undercuts(9_900_000, 10_000_000));
		assertFalse(Undercut.undercuts(10_000_000, 10_000_000), "same price is not an undercut");
		assertFalse(Undercut.undercuts(9_999_500, 10_000_000), "a rounding difference is not an undercut");
		assertFalse(Undercut.undercuts(0, 10_000_000));
	}

	@Test
	void listPriceGoesJustUnderTheComparableCopyAndNeverAboveTheNpc() {
		double p = ListPrice.choose(10_000_000, 12_000_000, 8_000_000, 0);
		assertTrue(p < 10_000_000 && p > 9_800_000, "just under the comparable copy, got " + p);
		assertEquals(12_000_000, ListPrice.choose(0, 12_000_000, 8_000_000, 0), 1, "no comparable copy: this copy's value");
		assertTrue(ListPrice.choose(0, 0, 8_000_000, 0) < 8_000_000, "nothing else known: just under the lowest BIN");
		assertEquals(500_000, ListPrice.choose(10_000_000, 0, 0, 500_000), 1, "an NPC sells it for 500k");
		assertEquals(0, ListPrice.choose(0, 0, 0, 0), 0, "nothing known: no suggestion");
	}

	@Test
	void tradeMenuLayout() {
		assertTrue(TradeCheck.isTradeTitle("You                  Steve"));
		assertFalse(TradeCheck.isTradeTitle("Your Bazaar Orders"));
		assertFalse(TradeCheck.isTradeTitle("Auctions Browser"));
		assertEquals(0, TradeCheck.sideOf(0));
		assertEquals(0, TradeCheck.sideOf(3));
		assertEquals(-1, TradeCheck.sideOf(4), "the middle column separates the sides");
		assertEquals(1, TradeCheck.sideOf(5));
		assertEquals(1, TradeCheck.sideOf(44));
		assertEquals(-1, TradeCheck.sideOf(45), "the bottom row is buttons");
	}

	@Test
	void perHourNeedsAQuarterHour() {
		assertEquals(4_000_000, ProfitLog.perHour(1_000_000, 60_000), 1, "1M in the first minute counts as 15 min, not 60M/h");
		assertEquals(5_000_000, ProfitLog.perHour(10_000_000, 2 * 3_600_000L), 1);
	}
}

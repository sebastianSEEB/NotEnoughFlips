package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** Where macros don't look: upgraded copies priced like plain ones, and regular auctions about to end. */
class BeatMacrosTest {

	@Test
	void plainCopiesAreNotUpgradeSnipes() {
		Spec plain = new Spec(0, false, 0, List.of(), Map.of(), new TreeSet<>());
		assertFalse(Sniper.upgraded(plain));
		assertTrue(Sniper.upgraded(new Spec(5, false, 0, List.of(), Map.of(), new TreeSet<>())), "starred");
		assertTrue(Sniper.upgraded(new Spec(0, true, 0, List.of(), Map.of(), new TreeSet<>())), "recombobulated");
		assertTrue(Sniper.upgraded(new Spec(0, false, 0, List.of(), Map.of("ultimate_wise", 5), new TreeSet<>())), "big enchant");
	}

	@Test
	void nextBidIsTheStartOrAFewPercentOverTheTop() {
		assertEquals(5_000_000, EndingSoon.nextBid(5_000_000, 0), 0.5);
		assertEquals(10_250_000, EndingSoon.nextBid(1_000_000, 10_000_000), 0.5);
	}

	@Test
	void bidUpToLeavesYourProfitAfterFees() {
		// sells for 50M: 2 % listing fee + 1 % claim tax = 48.5M back; keep 2M profit → bid at most 46.5M
		double upTo = EndingSoon.bidUpTo(50_000_000, 2_000_000);
		assertEquals(Verdict.netAfterFees(50_000_000) - 2_000_000, upTo, 1);
		assertTrue(upTo < 48_500_001);
		assertTrue(EndingSoon.bidUpTo(1_000_000, 50_000) < 1_000_000 - 50_000, "fees are paid on top of the profit");
	}
}

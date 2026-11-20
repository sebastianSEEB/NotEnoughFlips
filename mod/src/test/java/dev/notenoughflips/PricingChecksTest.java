package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The checks that stop the mod from calling something a deal when it isn't. Each test is an attack or a mistake a
 * player could exploit: shop items listed above the NPC price, a troll listing setting the "plain" price, upgrades
 * that add up on paper but not in the market, table enchants counted as upgrades, cheap items flagged as unreliable.
 */
class PricingChecksTest {

	// ------------------------------------------------------------------ NPC shop prices
	private static Map<String, List<NpcPrices.Way>> shipped() throws Exception {
		try (InputStream in = NpcPrices.class.getResourceAsStream("/assets/notenoughflips/npc-prices.json")) {
			assertNotNull(in, "the NPC price table must ship inside the mod");
			return NpcPrices.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void shippedNpcTableHasCoinShopItems() throws Exception {
		Map<String, List<NpcPrices.Way>> shops = shipped();
		assertTrue(shops.size() > 300, "expected hundreds of NPC shop items, got " + shops.size());
		double stonk = NpcPrices.cheapest(shops.get("STONK_PICKAXE"), id -> 0);
		assertTrue(stonk > 100_000 && stonk < 2_000_000, "Stonk is a coin-only shop item, got " + stonk);
		for (List<NpcPrices.Way> ways : shops.values())
			for (NpcPrices.Way w : ways)
				for (String id : w.ids()) assertFalse(id.startsWith("SKYBLOCK_"), "bits / copper / motes can't be bought with coins: " + id);
	}

	@Test
	void npcPriceIsTheCheapestPriceableWay() {
		List<NpcPrices.Way> ways = List.of(
				new NpcPrices.Way(1_000_000, List.of(), List.of()),                          // coins only
				new NpcPrices.Way(100_000, List.of("ENCHANTED_GOLD"), List.of(10.0)),          // coins + materials
				new NpcPrices.Way(1, List.of("UNTRADEABLE_THING"), List.of(1.0)));            // can't be priced: skipped
		Map<String, Double> bz = Map.of("ENCHANTED_GOLD", 50_000.0);
		assertEquals(600_000, NpcPrices.cheapest(ways, id -> bz.getOrDefault(id, 0.0)), 1e-6);
		assertEquals(0, NpcPrices.cheapest(null, id -> 1), 1e-6, "not sold by an NPC = no cap");
	}

	// ------------------------------------------------------------------ "a copy with the same or better upgrades"
	private static Map<String, Object> ea(int stars, boolean recomb, int hpb, Map<String, Object> ench, Map<String, Object> gems) {
		Map<String, Object> m = new HashMap<>();
		m.put("upgrade_level", stars);
		m.put("rarity_upgrades", recomb ? 1 : 0);
		m.put("hot_potato_count", hpb);
		if (ench != null) m.put("enchantments", ench);
		if (gems != null) m.put("gems", gems);
		return m;
	}

	@Test
	void coverCapsUpgradedCopiesAndNeverComparesAListingWithItself() {
		String k = "TEST_CHESTPLATE";
		Spec mine = Spec.of(ea(9, true, 15, Map.of("ultimate_legion", 5), null));
		MarketBook.begin();
		MarketBook.observeSpec(k, 65_900, Spec.of(ea(0, false, 0, null, null)));                              // plain: doesn't cover
		MarketBook.observeSpec(k, 40_000_000, Spec.of(ea(9, true, 15, Map.of("ultimate_wise", 5), null)));   // other ultimate: doesn't
		MarketBook.observeSpec(k, 90_000_000, Spec.of(ea(10, true, 15, Map.of("ultimate_legion", 5), null))); // better: covers
		MarketBook.observeSpec(k, 125_000_000, Spec.of(ea(9, true, 15, Map.of("ultimate_legion", 5), null))); // the listing itself
		MarketBook.finish(1);
		double[] c = MarketBook.cover(k, mine, 125_000_000d);
		assertNotNull(c);
		assertEquals(90_000_000, c[0], 1e-6, "the 125M copy is worth at most the 90M better one");
	}

	@Test
	void plainCopyIsCappedByTheCheapestCopyOfAnyKind() {
		String k = "TEST_BOW";
		MarketBook.begin();
		MarketBook.observeSpec(k, 50_000, Spec.of(ea(3, false, 0, null, null)));
		MarketBook.observeSpec(k, 20_000_000, Spec.of(ea(0, false, 0, null, null)));   // troll "plain" listing
		MarketBook.finish(2);
		double[] c = MarketBook.cover(k, Spec.of(ea(0, false, 0, null, null)), 5_000_000d);
		assertNotNull(c);
		assertEquals(50_000, c[0], 1e-6);
	}

	@Test
	void gemQualityAndCountersCount() {
		Spec perfect = Spec.of(ea(0, true, 0, null, Map.of("JADE_0", "PERFECT", "AMBER_0", Map.of("quality", "FINE"))));
		Spec flawless = Spec.of(ea(0, true, 0, null, Map.of("JADE_0", "FLAWLESS", "AMBER_0", "FINE")));
		assertTrue(perfect.covers(flawless));
		assertFalse(flawless.covers(perfect));
		Map<String, Object> rich = ea(0, false, 0, null, null), poor = ea(0, false, 0, null, null);
		rich.put("collected_coins", 900_000_000L);
		poor.put("collected_coins", 1_000L);
		assertTrue(Spec.of(rich).covers(Spec.of(poor)));
		assertFalse(Spec.of(poor).covers(Spec.of(rich)), "a Crown of Avarice with fewer coins collected is worth less");
	}

	@Test
	void noSpecsMeansNoCap() {
		assertNull(MarketBook.cover("NEVER_LISTED", Spec.of(ea(0, false, 0, null, null)), null));
	}

	// ------------------------------------------------------------------ what counts as an upgrade
	@Test
	void tableEnchantsAreNotUpgrades() {
		assertFalse(Valuation.paidFor("growth", 5));
		assertFalse(Valuation.paidFor("PROTECTION", 5));
		assertTrue(Valuation.paidFor("PROTECTION", 6), "Protection VI is a book: it counts");
		assertTrue(Valuation.paidFor("ultimate_legion", 1));
		assertTrue(Valuation.paidFor("OVERLOAD", 5), "book-only enchants count (if pricey enough)");
	}

	@Test
	void salesCeilingIgnoresOneOddSale() {
		List<double[]> week = new ArrayList<>();
		for (int i = 0; i < 30; i++) week.add(new double[]{i, 900_000, 1_000_000 + i * 1000, 950_000, 3});
		week.add(new double[]{31, 900_000, 690_000_000, 950_000, 1});   // one fake 690M sale
		double c = Valuation.ceiling(week);
		assertTrue(c < 1_100_000, "ceiling should be the normal top, got " + c);
	}

	// ------------------------------------------------------------------ unreliable history
	@Test
	void cheapItemsAreNeverCalledUnreliable() {
		List<double[]> week = new ArrayList<>();
		for (int i = 0; i < 40; i++) week.add(new double[]{i, 15_000, 44_000, i % 2 == 0 ? 15_000 : 44_000, 5});
		assertFalse(Market.erratic(week).bad(), "a 28k bow swinging 15k-44k is normal");
	}

	@Test
	void wildHistoryOfAPriceyItemIsUnreliable() {
		List<double[]> week = new ArrayList<>();
		for (int i = 0; i < 40; i++) week.add(new double[]{i, 9_000_000, 37_000_000, i % 2 == 0 ? 9_000_000 : 37_000_000, 5});
		assertTrue(Market.erratic(week).bad());
	}

	// ------------------------------------------------------------------ the chart never covers the tooltip
	@Test
	void chartStaysClearOfTheTooltip() {
		int sw = 550, sh = 446;
		int[] box = {398, 68, 176, 166};
		for (int[] tipSize : new int[][]{{500, 340}, {150, 120}, {300, 200}}) {
			int[] tip = PriceChart.tooltipBox(sw, sh, 480, 180, tipSize[0], tipSize[1]);
			int[] spot = PriceChart.place(sw, sh, box, false, 11, tip);
			assertNotNull(spot);
		}
		int[] small = PriceChart.tooltipBox(sw, sh, 480, 180, 150, 120);
		int[] spot = PriceChart.place(sw, sh, box, false, 11, small);
		int h = 14 + 11 + 2 + (spot[3] - 22) + 21 + 20;
		boolean overlap = spot[0] < small[2] && spot[0] + spot[2] > small[0] && spot[1] < small[3] && spot[1] + h > small[1];
		assertFalse(overlap, "chart overlaps a small tooltip");
	}
}

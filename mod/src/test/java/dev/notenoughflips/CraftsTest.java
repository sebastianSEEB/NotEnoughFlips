package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Craft flips: your unlocks, the cheapest ingredients, and what's worth crafting. */
class CraftsTest {

	@Test
	void unlocksAreCheckedAgainstYourProfile() {
		Unlocks.set(Map.of("ENDER_PEARL", 6, "DIAMOND", 9), Map.of("enderman", 5, "zombie", 3), 4,
				Map.of("ender pearl", "ENDER_PEARL", "diamond", "DIAMOND", "gemstone", "GEMSTONE_COLLECTION"));
		assertEquals(Boolean.TRUE, Unlocks.meets("Diamond VIII"));
		assertEquals(Boolean.FALSE, Unlocks.meets("Ender Pearl VIII"), "tier 6 < VIII");
		assertEquals(Boolean.TRUE, Unlocks.meets("Enderman Slayer 5"));
		assertEquals(Boolean.FALSE, Unlocks.meets("Zombie Slayer 4"));
		assertEquals(Boolean.FALSE, Unlocks.meets("HotM 5"));
		assertEquals(Boolean.TRUE, Unlocks.meets("Heart of the Mountain Tier 4"));
		assertEquals(Boolean.FALSE, Unlocks.meets("Gemstone X & HotM 5"), "a missing part fails the whole");
		assertNull(Unlocks.meets("Something Odd Requirement"), "unknown kind: can't tell");
		assertEquals(Boolean.TRUE, Unlocks.meets(null), "no requirement");
		Unlocks.reset();
		assertNull(Unlocks.meets("Diamond I"), "no profile: can't tell");
	}

	@Test
	void romanAndHotm() {
		assertEquals(4, Unlocks.roman("IV"));
		assertEquals(9, Unlocks.roman("IX"));
		assertEquals(14, Unlocks.roman("XIV"));
		assertEquals(1, Unlocks.hotmTier(0));
		assertEquals(5, Unlocks.hotmTier(100_000));
		assertEquals(10, Unlocks.hotmTier(2_000_000));
	}

	@Test
	void profileParsing() {
		JsonObject m = JsonParser.parseString("""
				{"player_data":{"unlocked_coll_tiers":["ENDER_PEARL_1","ENDER_PEARL_7","INK_SACK:3_4","WHEAT_-1"]},
				 "slayer":{"slayer_bosses":{"enderman":{"claimed_levels":{"level_1":true,"level_5":true,"level_7_special":true}}}},
				 "mining_core":{"experience":120000}}""").getAsJsonObject();
		Unlocks.fromProfile(m);
		Unlocks.set(Map.of("ENDER_PEARL", 7, "INK_SACK:3", 4), Map.of("enderman", 7), 5, Map.of("ender pearl", "ENDER_PEARL", "cocoa beans", "INK_SACK:3"));
		assertEquals(Boolean.TRUE, Unlocks.meets("Cocoa Beans IV"));
		assertEquals(Boolean.TRUE, Unlocks.meets("Enderman Slayer 7"));
		Unlocks.reset();
	}

	/** Fake market: bazaar entries and lowest BINs from maps. */
	private static Crafts.Prices prices(Map<String, DataClient.Live> bz, Map<String, Double> lbin, Map<String, double[]> ah) {
		return new Crafts.Prices() {
			public DataClient.Live bazaar(String id) { return bz.get(id); }
			public double npc(String id) { return 0; }
			public double lowestBin(String id) { return lbin.getOrDefault(id, 0.0); }
			public double[] auction(String id) { return ah.get(id); }
		};
	}

	private static DataClient.Live bz(double offer, double order, double week) {
		return new DataClient.Live(offer, order, 0, week, week, 10, 10);
	}

	@Test
	void bazaarCraftThatPays() {
		// 160 diamonds at 10 (tight spread, liquid: buy order at 9.6 + 0.1) → 1 enchanted diamond sold at 2,000
		Map<String, DataClient.Live> m = new HashMap<>();
		m.put("DIAMOND", bz(10, 9.6, 5_000_000));
		m.put("ENCHANTED_DIAMOND", bz(2_000, 1_900, 70_000));
		Crafts.Recipe r = new Crafts.Recipe("ENCHANTED_DIAMOND", 1, List.of(new Crafts.Ing("DIAMOND", 160)), "Diamond IV", false);
		JsonObject o = Crafts.evaluate(r, prices(m, Map.of(), Map.of()), new HashMap<>(), 1e9);
		assertEquals("bazaar", o.get("where").getAsString());
		assertEquals(Math.round(160 * 9.7), o.get("cost").getAsLong(), "buy orders, not instant-buy");
		double net = (2_000 - 0.1) * (1 - Crafts.BZ_TAX);
		assertEquals(Math.round(net - 160 * 9.7), o.get("profit").getAsLong());
		assertEquals("bazaar order", o.getAsJsonArray("ingredients").get(0).getAsJsonObject().get("how").getAsString());
		assertTrue(o.get("profit_per_day").getAsDouble() > 0);
	}

	@Test
	void wideSpreadIngredientsAreBoughtInstantly() {
		Map<String, DataClient.Live> m = new HashMap<>();
		m.put("DIAMOND", bz(10, 7, 5_000_000));                    // order 30 % under: won't fill
		Crafts.Cost c = Crafts.cost("DIAMOND", 1, prices(m, Map.of(), Map.of()), new HashMap<>());
		assertEquals(10, c.unit(), 1e-9);
		assertEquals("bazaar instant buy", c.how());
	}

	@Test
	void noProfitNoFlip() {
		Map<String, DataClient.Live> m = new HashMap<>();
		m.put("DIAMOND", bz(10, 9.6, 5_000_000));
		m.put("ENCHANTED_DIAMOND", bz(1_600, 1_550, 70_000));    // 1,600 × 0.9875 < 1,552 × 1.08
		Crafts.Recipe r = new Crafts.Recipe("ENCHANTED_DIAMOND", 1, List.of(new Crafts.Ing("DIAMOND", 160)), null, false);
		assertNull(Crafts.evaluate(r, prices(m, Map.of(), Map.of()), new HashMap<>(), 1e9));
	}

	@Test
	void auctionCraftNeedsSalesAndNeverSellsAboveThem() {
		Map<String, DataClient.Live> m = new HashMap<>();
		m.put("ENCHANTED_EYE_OF_ENDER", bz(2_000, 1_950, 1_000_000));
		m.put("ENCHANTED_DIAMOND", bz(1_700, 1_680, 1_000_000));
		Crafts.Recipe r = new Crafts.Recipe("ASPECT_OF_THE_END", 1,
				List.of(new Crafts.Ing("ENCHANTED_EYE_OF_ENDER", 32), new Crafts.Ing("ENCHANTED_DIAMOND", 1)), "Ender Pearl VIII", false);
		assertNull(Crafts.evaluate(r, prices(m, Map.of("ASPECT_OF_THE_END", 300_000.0), Map.of()), new HashMap<>(), 1e9),
				"no sales data: not shown");
		JsonObject o = Crafts.evaluate(r, prices(m, Map.of("ASPECT_OF_THE_END", 300_000.0), Map.of("ASPECT_OF_THE_END", new double[]{150_000, 40})),
				new HashMap<>(), 1e9);
		assertEquals("AH", o.get("where").getAsString());
		assertEquals(150_000, o.get("sell").getAsLong(), "capped at its real sale price, not the 300k lowest BIN");
	}

	@Test
	void soulboundOutputsAreSkipped() {
		Map<String, DataClient.Live> m = new HashMap<>();
		m.put("DIAMOND", bz(10, 9.6, 5_000_000));
		m.put("THING", bz(5_000, 4_900, 70_000));
		Crafts.Recipe r = new Crafts.Recipe("THING", 1, List.of(new Crafts.Ing("DIAMOND", 10)), null, true);
		assertNull(Crafts.evaluate(r, prices(m, Map.of(), Map.of()), new HashMap<>(), 1e9));
	}

	@Test
	void shippedRecipesLoad() {
		Map<String, List<Crafts.Recipe>> rs = Crafts.recipes();
		assertTrue(rs.size() > 2000, "recipes shipped: " + rs.size());
		Crafts.Recipe aote = rs.get("ASPECT_OF_THE_END").get(0);
		assertEquals("Ender Pearl VIII", aote.req());
		assertEquals(2, aote.ings().size());
	}

	/** Fake market that also knows what you have. */
	private static Crafts.Prices withStuff(Map<String, DataClient.Live> bz, Map<String, Long> have) {
		return new Crafts.Prices() {
			public DataClient.Live bazaar(String id) { return bz.get(id); }
			public double npc(String id) { return 0; }
			public double lowestBin(String id) { return 0; }
			public double[] auction(String id) { return null; }
			public long have(String id) { return have.getOrDefault(id, 0L); }
		};
	}

	@Test
	void howManyYouCanMakeFromYourStuff() {
		Crafts.Recipe r = new Crafts.Recipe("ENCHANTED_DIAMOND", 1, List.of(new Crafts.Ing("DIAMOND", 160)), null, false);
		assertEquals(3, Crafts.canMake(r, withStuff(Map.of(), Map.of("DIAMOND", 500L))));
		assertEquals(0, Crafts.canMake(r, withStuff(Map.of(), Map.of("DIAMOND", 159L))));
		Crafts.Recipe two = new Crafts.Recipe("X", 2, List.of(new Crafts.Ing("A", 2), new Crafts.Ing("B", 1)), null, false);
		assertEquals(6, Crafts.canMake(two, withStuff(Map.of(), Map.of("A", 7L, "B", 10L))), "3 crafts × 2 made each");
	}

	@Test
	void craftingYourStuffOnlyWhenItBeatsSellingTheMaterials() {
		Map<String, DataClient.Live> m = new HashMap<>();
		m.put("DIAMOND", bz(10, 9.0, 5_000_000));                   // you'd insta-sell yours at 9 (−tax)
		m.put("ENCHANTED_DIAMOND", bz(2_000, 1_900, 70_000));
		Crafts.Recipe r = new Crafts.Recipe("ENCHANTED_DIAMOND", 1, List.of(new Crafts.Ing("DIAMOND", 160)), null, false);
		JsonObject o = Crafts.evaluateOwned(r, withStuff(m, Map.of("DIAMOND", 480L)));
		assertEquals(3, o.get("can_make").getAsLong());
		double mats = 160 * 9.0 * (1 - Crafts.BZ_TAX), net = (2_000 - 0.1) * (1 - Crafts.BZ_TAX);
		assertEquals(Math.round(net - mats), o.get("gain").getAsLong());
		assertEquals(Math.round((net - mats) * 3), o.get("total").getAsLong());
		m.put("ENCHANTED_DIAMOND", bz(1_400, 1_350, 70_000));        // now the materials are worth more as they are
		assertNull(Crafts.evaluateOwned(r, withStuff(m, Map.of("DIAMOND", 480L))), "selling the diamonds pays more");
		assertNull(Crafts.evaluateOwned(r, withStuff(m, Map.of())), "you have none");
	}

	@Test
	void sacksFromTheProfile() {
		Owned.reset();
		Owned.fromProfile(JsonParser.parseString("""
				{"inventory":{"sacks_counts":{"DIAMOND":1200,"ENCHANTED_DIAMOND":3,"COAL":0}}}""").getAsJsonObject());
		assertTrue(Owned.sacksKnown());
		assertEquals(1200, Owned.have("DIAMOND"));
		assertEquals(0, Owned.have("COAL"));
		assertTrue(!Owned.storageKnown(), "no ender chest in the profile: Inventory API is off");
		Owned.reset();
	}

	@Test
	void inventoryBlobsAreCounted() throws Exception {
		// an ender chest / backpack blob: 64 + 10 Diamond, 3 Enchanted Coal, one empty slot
		Map<String, Long> m = new HashMap<>();
		AhScanner.countItems("H4sIAMIxBmsC/+NiYOBkYMzkYmBgYGFkYHXOL80rceBiYC5JTOdi4HetKClKdCwpKcpMKi1JLeZgYMpMYWB38XT09fdzAWphgGnhIloLTAczfh18rn7OHo5+Ia4u8c7+jj4guwA7w4D4qwAAAA==", m);
		assertEquals(74, m.get("DIAMOND"));
		assertEquals(3, m.get("ENCHANTED_COAL"));
		assertEquals(2, m.size());
	}
}

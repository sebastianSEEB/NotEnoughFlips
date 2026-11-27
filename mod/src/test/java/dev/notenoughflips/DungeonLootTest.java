package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class DungeonLootTest {
	private static final Map<String, String> NAMES = Map.of("necron's handle", "NECRON_HANDLE", "recombobulator 3000",
			"RECOMBOBULATOR_3000", "fuming potato book", "FUMING_POTATO_BOOK");
	private static final Function<String, String> BY_NAME = NAMES::get;
	private static final Set<String> MARKET = Set.of("ENCHANTMENT_ULTIMATE_SOUL_EATER_1", "ENCHANTMENT_REJUVENATE_1",
			"ESSENCE_WITHER", "DUNGEON_CHEST_KEY", "RECOMBOBULATOR_3000", "FUMING_POTATO_BOOK");
	private static final Predicate<String> EXISTS = MARKET::contains;
	private static final Map<String, Double> PRICE = Map.of("NECRON_HANDLE", 500_000_000.0, "RECOMBOBULATOR_3000", 6_000_000.0,
			"ESSENCE_WITHER", 3_000.0, "ENCHANTMENT_ULTIMATE_SOUL_EATER_1", 1_000_000.0, "DUNGEON_CHEST_KEY", 700_000.0,
			"FUMING_POTATO_BOOK", 1_200_000.0);
	private static final DungeonLoot.Prices P = id -> PRICE.getOrDefault(id, 0.0);

	@Test
	void namesToIds() {
		assertEquals("ESSENCE_WITHER", DungeonLoot.idFor("Wither Essence", BY_NAME, EXISTS));
		assertEquals("ENCHANTMENT_ULTIMATE_SOUL_EATER_1", DungeonLoot.idFor("Enchanted Book (Soul Eater I)", BY_NAME, EXISTS),
				"ultimate enchants have their own ids");
		assertEquals("ENCHANTMENT_REJUVENATE_1", DungeonLoot.idFor("Rejuvenate I", BY_NAME, EXISTS), "a bare book line");
		assertEquals("NECRON_HANDLE", DungeonLoot.idFor("§6Necron's Handle", BY_NAME, EXISTS));
		assertNull(DungeonLoot.idFor("Something Unknown", BY_NAME, EXISTS));
		assertEquals(List.of("Wither Essence", 1_025), List.of(DungeonLoot.splitQty("Wither Essence x1,025")));
	}

	@Test
	void chestFromLore() {
		DungeonLoot.Chest c = DungeonLoot.fromLore("Bedrock Chest", List.of("", "Contents", "Recombobulator 3000",
				"Wither Essence x20", "Enchanted Book (Soul Eater I)", "", "Cost", "2,000,000 Coins", "", "Click to open!"), P, BY_NAME, EXISTS);
		assertEquals(6_000_000 + 20 * 3_000 + 1_000_000, c.value(), 0.01);
		assertEquals(2_000_000, c.cost(), 0.01);
		assertEquals(0, c.unpriced());
		DungeonLoot.Chest k = DungeonLoot.fromLore("Obsidian Chest", List.of("Contents", "Fuming Potato Book", "Mystery Item",
				"", "Cost", "500,000 Coins", "Dungeon Chest Key"), P, BY_NAME, EXISTS);
		assertTrue(k.needsKey());
		assertEquals(500_000 + 700_000, k.cost(), 0.01, "the key counts as a cost");
		assertEquals(1, k.unpriced(), "unknown loot is flagged, not guessed");
	}

	@Test
	void bestAndKey() {
		DungeonLoot.Chest a = new DungeonLoot.Chest("Bedrock Chest", List.of(), 5_000_000, 2_000_000, false, 0);
		DungeonLoot.Chest b = new DungeonLoot.Chest("Obsidian Chest", List.of(), 2_000_000, 500_000, false, 0);
		DungeonLoot.Chest c = new DungeonLoot.Chest("Wood Chest", List.of(), 10_000, 0, false, 0);
		List<DungeonLoot.Chest> r = DungeonLoot.ranked(List.of(c, b, a));
		assertEquals("Bedrock Chest", r.get(0).name());
		assertEquals("Obsidian Chest", DungeonLoot.withKey(r, 700_000).name(), "1.5M profit beats a 700k key");
		assertNull(DungeonLoot.withKey(r, 2_000_000), "not worth a 2M key");
	}

	@Test
	void mapRuns() {
		byte[] colors = new byte[128 * 128];
		for (int x = 10; x < 20; x++) colors[x + 5 * 128] = (byte) (34 * 4 + 1);   // a 10-pixel wall, normal shade
		List<int[]> runs = DungeonMap.runs(colors, id -> 0x707070);
		assertEquals(1, runs.size());
		assertEquals(10, runs.get(0)[0]);
		assertEquals(5, runs.get(0)[1]);
		assertEquals(10, runs.get(0)[2]);
		assertEquals(0xFF606060, runs.get(0)[3], "0x70 × 220/255 = 96.6, truncated to 0x60");
		assertEquals(0, DungeonMap.argb(3, 0xFFFFFF), "base colour 0 is see-through");
	}
}

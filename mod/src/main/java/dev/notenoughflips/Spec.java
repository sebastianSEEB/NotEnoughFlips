package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The upgrades that make one copy of an item worth more than another, in a form two copies can be compared with:
 * stars, recomb, potato books, gem qualities, ultimate / level 6+ enchants, attributes and cosmetic extras (skin, dye,
 * scrolls…).
 *
 * {@link #covers(Spec)} = "at least as upgraded in every way". If a copy that covers yours is listed for X, nobody
 * pays more than X for yours (they'd buy that one instead), so the cheapest covering listing is a hard upper bound
 * on what a copy is worth, however the upgrades add up on paper.
 */
record Spec(int stars, boolean recomb, int hpb, List<Integer> gems, Map<String, Integer> big, TreeSet<String> extras) {
	static final String[] EXTRA_KEYS = {"skin", "dye_item", "ability_scroll", "ethermerge", "power_ability_scroll", "art_of_war_count",
			"talisman_enrichment", "mana_disintegrator_count", "polarvoid", "wood_singularity_count", "drill_part_engine",
			"drill_part_fuel_tank", "drill_part_upgrade_module", "rod_part", "line", "hook", "sinker", "boosters"};

	/** Counters stored on the item that drive its price (coins collected, kills, crops farmed, charge…): more is worth more. */
	static final String[] COUNTERS = {"collected_coins", "farmed_cultivating", "compact_blocks", "champion_combat_xp", "expertise_kills",
			"hecatomb_s_runs", "thunder_charge", "mined_crops", "toxophilite_combat_xp", "eman_kills", "stats_book", "blocks_walked",
			"bottle_of_jyrre_seconds", "raider_kills", "zombie_kills", "sword_kills", "spider_kills", "dungeon_paper_kills"};

	static int clampInt(double v) {
		return v <= 0 ? 0 : v >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
	}

	/** Gem quality as a number (rough 1 … perfect 5). */
	static int quality(String q) {
		if (q == null) return 0;
		String s = q.toUpperCase(Locale.ROOT);
		if (s.startsWith("PERFECT")) return 5;
		if (s.startsWith("FLAWLESS")) return 4;
		if (s.startsWith("FINE")) return 3;
		if (s.startsWith("FLAWED")) return 2;
		if (s.startsWith("ROUGH")) return 1;
		return 0;
	}

	private static boolean bigEnchant(String k, int lvl) {
		return k.startsWith("ultimate_") || lvl >= 6;
	}

	/** From the raw ExtraAttributes of an AH listing (AhScanner's NBT reader). */
	static Spec of(Map<?, ?> ea) {
		int stars = num(ea.get("upgrade_level"));
		if (stars == 0) stars = num(ea.get("dungeon_item_level"));
		Map<String, Integer> big = new TreeMap<>();
		if (ea.get("enchantments") instanceof Map<?, ?> en) {
			for (Map.Entry<?, ?> e : en.entrySet()) {
				String k = e.getKey().toString().toLowerCase(Locale.ROOT);
				int lvl = num(e.getValue());
				if (bigEnchant(k, lvl)) big.put(k, lvl);
			}
		}
		if (ea.get("attributes") instanceof Map<?, ?> at)
			for (Map.Entry<?, ?> e : at.entrySet()) big.put("attr:" + e.getKey().toString().toLowerCase(Locale.ROOT), num(e.getValue()));
		List<Integer> gems = new ArrayList<>();
		if (ea.get("gems") instanceof Map<?, ?> g) {
			for (Map.Entry<?, ?> e : g.entrySet()) {
				String k = e.getKey().toString();
				if (k.equals("unlocked_slots") || k.endsWith("_gem")) continue;
				Object v = e.getValue();
				String q = v instanceof Map<?, ?> m && m.get("quality") != null ? m.get("quality").toString() : v == null ? null : v.toString();
				int r = quality(q);
				if (r > 0) gems.add(r);
			}
		}
		for (String k : COUNTERS) if (ea.get(k) instanceof Number n && n.doubleValue() > 0) big.put("num:" + k, clampInt(n.doubleValue()));
		TreeSet<String> extras = new TreeSet<>();
		for (String k : EXTRA_KEYS) if (ea.get(k) != null) extras.add(k);
		return make(stars, num(ea.get("rarity_upgrades")) > 0, num(ea.get("hot_potato_count")), gems, big, extras);
	}

	/** From a tooltip item (Valuation's parsed attributes). */
	static Spec of(Valuation.Attrs a) {
		Map<String, Integer> big = new TreeMap<>();
		for (Map.Entry<String, Integer> e : a.enchants.entrySet()) {
			String k = e.getKey().toLowerCase(Locale.ROOT);
			if (bigEnchant(k, e.getValue())) big.put(k, e.getValue());
		}
		for (Map.Entry<String, Integer> e : a.attributes.entrySet()) big.put("attr:" + e.getKey().toLowerCase(Locale.ROOT), e.getValue());
		for (Map.Entry<String, Integer> e : a.counters.entrySet()) if (e.getValue() > 0) big.put("num:" + e.getKey(), e.getValue());
		List<Integer> gems = new ArrayList<>();
		for (String g : a.gems) {
			int r = quality(g);
			if (r > 0) gems.add(r);
		}
		return make(a.stars, a.recomb, a.hpb, gems, big, new TreeSet<>(a.extras));
	}

	static Spec make(int stars, boolean recomb, int hpb, List<Integer> gems, Map<String, Integer> big, TreeSet<String> extras) {
		List<Integer> g = new ArrayList<>(gems);
		g.sort((x, y) -> Integer.compare(y, x));                        // best first
		return new Spec(stars, recomb, hpb, List.copyOf(g), Map.copyOf(big), extras);
	}

	/** Pure: is this copy at least as upgraded as {@code o} in every way that costs coins? */
	boolean covers(Spec o) {
		if (stars < o.stars || o.recomb && !recomb || hpb < o.hpb) return false;
		if (gems.size() < o.gems.size()) return false;
		for (int i = 0; i < o.gems.size(); i++) if (gems.get(i) < o.gems.get(i)) return false;   // both sorted best first
		for (Map.Entry<String, Integer> e : o.big.entrySet()) {
			Integer mine = big.get(e.getKey());
			if (mine == null || mine < e.getValue()) return false;
		}
		return extras.containsAll(o.extras);
	}

	private static int num(Object o) {
		return o instanceof Number n ? n.intValue() : 0;
	}
}

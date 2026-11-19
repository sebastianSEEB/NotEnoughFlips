package dev.notenoughflips;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * What is THIS copy worth? Mirrors market/valuation.py:
 *
 *   value = clean base (fitted from sales) + Σ upgrade price × factor
 *   pets:  value = L1 + (L100 − L1) × XP / XP-to-max  + held item + skin   (per rarity)
 *
 * Upgrades: enchantments (as books), recombobulator (+1 rarity), hot/fuming potato books, stars (real essence
 * cost) and master stars, gemstones, art of war, pet held item and skin. Prices: live bazaar first, then the
 * optional local server's /components, else LocalComponents (live bazaar + lowest BINs from your own AH scan + Hypixel's
 * star costs). The model (clean base) comes from the item doc, else from your own AH scan (MarketBook).
 */
final class Valuation {
	static final String[] TIERS = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY SPECIAL"};
	static final String TIER_BOOST = "PET_ITEM_TIER_BOOST";
	private static final java.util.Set<String> GEM_SLOT_TYPES = java.util.Set.of("RUBY", "AMBER", "TOPAZ", "JADE", "SAPPHIRE",
			"AMETHYST", "JASPER", "OPAL", "ONYX", "AQUAMARINE", "CITRINE", "PERIDOT");

	/** Parsed upgrades of one item. */
	static final class Attrs {
		String itemId;
		final Map<String, Integer> enchants = new LinkedHashMap<>();
		boolean recomb;
		int hpb, stars, aow;
		final List<String> gems = new ArrayList<>();
		boolean pet;
		double petXp;
		String petHeld, petSkin, petTier;
		int petLevel = -1;            // from the name "[Lvl 87] Griffin"
		String rarity;                // shown rarity from the lore (incl. recomb / tier boost)

		boolean hasUpgrades() {
			return !enchants.isEmpty() || recomb || hpb > 0 || stars > 0 || aow > 0 || !gems.isEmpty() || petHeld != null || petSkin != null;
		}
	}

	record Part(String label, double coins) {}

	record Estimate(double value, double base, List<Part> parts, Attrs attrs, String modelType, int nSales, double petFraction) {
		double extras() {
			return value - base;
		}
	}

	private static DataClient data;

	private Valuation() {}

	static void init(DataClient client) {
		data = client;
	}

	// ------------------------------------------------------------------ attributes

	static Attrs parse(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
		if (cd == null) return null;
		CompoundTag t = cd.copyTag();
		Attrs a = new Attrs();
		a.itemId = t.getStringOr("id", "");
		if (a.itemId.isEmpty()) return null;
		CompoundTag ench = t.getCompoundOrEmpty("enchantments");
		for (String k : ench.keySet()) a.enchants.put(k.toUpperCase(Locale.ROOT), ench.getIntOr(k, 0));
		a.recomb = t.getIntOr("rarity_upgrades", 0) > 0;
		a.hpb = t.getIntOr("hot_potato_count", 0);
		a.stars = t.getIntOr("upgrade_level", 0);
		if (a.stars == 0) a.stars = t.getIntOr("dungeon_item_level", 0);
		a.aow = t.getIntOr("art_of_war_count", 0);
		CompoundTag gems = t.getCompoundOrEmpty("gems");
		for (String slot : gems.keySet()) {
			if (slot.equals("unlocked_slots") || slot.endsWith("_gem")) continue;
			String q = gems.getCompoundOrEmpty(slot).getStringOr("quality", "");
			if (q.isEmpty()) q = gems.getStringOr(slot, "");
			if (q.isEmpty()) continue;
			int us = slot.lastIndexOf('_');
			String base = us > 0 ? slot.substring(0, us) : slot;
			String type = GEM_SLOT_TYPES.contains(base) ? base : gems.getStringOr(slot + "_gem", "");
			if (!type.isEmpty()) a.gems.add(q.toUpperCase(Locale.ROOT) + "_" + type.toUpperCase(Locale.ROOT) + "_GEM");
		}
		String info = t.getStringOr("petInfo", "");
		if (!info.isEmpty()) {
			try {
				JsonObject p = JsonParser.parseString(info).getAsJsonObject();
				a.pet = true;
				a.petXp = p.has("exp") ? p.get("exp").getAsDouble() : 0;
				a.petHeld = p.has("heldItem") && !p.get("heldItem").isJsonNull() ? p.get("heldItem").getAsString() : null;
				a.petSkin = p.has("skin") && !p.get("skin").isJsonNull() ? p.get("skin").getAsString() : null;
				a.petTier = p.has("tier") ? p.get("tier").getAsString().toUpperCase(Locale.ROOT) : null;
			} catch (RuntimeException ignored) {
				// malformed petInfo
			}
		}
		String name = stack.getHoverName().getString();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[Lvl (\\d+)]").matcher(name);
		if (m.find()) a.petLevel = Integer.parseInt(m.group(1));
		a.rarity = rarityFromLore(stack);
		return a;
	}

	/** Shown rarity: the last lore line that starts with a rarity word ("LEGENDARY SWORD", "a MYTHIC DUNGEON BOW a"). */
	private static String rarityFromLore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return null;
		List<Component> lines = lore.lines();
		for (int i = lines.size() - 1; i >= 0; i--) {
			String s = lines.get(i).getString().replaceAll("§.", "").trim();
			if (s.startsWith("a ")) s = s.substring(2);                 // recombobulated: obfuscated "a" around it
			for (int k = TIERS.length - 1; k >= 0; k--) {
				if (s.startsWith(TIERS[k]) && (s.length() == TIERS[k].length() || s.charAt(TIERS[k].length()) == ' ')) return TIERS[k];
			}
		}
		return null;
	}

	/** A Tier Boost shows one rarity higher; the pet is priced as its real rarity + the boost item. */
	static String realPetTier(String tier, String held) {
		if (tier == null) return null;
		if (TIER_BOOST.equals(held)) {
			for (int i = 1; i < TIERS.length; i++) if (TIERS[i].equals(tier)) return TIERS[i - 1];
		}
		return tier;
	}

	// ------------------------------------------------------------------ prices

	private static double price(JsonObject comp, String pid) {
		if (pid == null) return 0;
		DataClient.Live l = data == null ? null : data.live(pid);
		if (l != null && l.buy() > 0) return l.buy();
		Double v = HudFormatter.num(HudFormatter.obj(comp, "bazaar"), pid);
		if (v == null || v <= 0) v = HudFormatter.num(HudFormatter.obj(comp, "lbin"), pid);
		return v == null ? 0 : v;
	}

	private static double factor(JsonObject comp, String k, double def) {
		Double v = HudFormatter.num(HudFormatter.obj(comp, "factors"), k);
		return v == null ? def : v;
	}

	static List<Part> components(Attrs a, JsonObject comp) {
		List<Part> out = new ArrayList<>();
		double minEnch = comp.has("min_enchant_value") ? comp.get("min_enchant_value").getAsDouble() : 200_000;
		double ench = 0;
		for (Map.Entry<String, Integer> e : a.enchants.entrySet()) {
			double v = price(comp, "ENCHANTMENT_" + e.getKey() + "_" + e.getValue());
			if (v >= minEnch) ench += v;
		}
		if (ench > 0) out.add(new Part("enchants", ench * factor(comp, "enchant", 0.85)));
		if (a.recomb) out.add(new Part("recomb", price(comp, "RECOMBOBULATOR_3000") * factor(comp, "recomb", 0.85)));
		if (a.hpb > 0) {
			double v = Math.min(a.hpb, 10) * price(comp, "HOT_POTATO_BOOK") * factor(comp, "hpb", 1.0)
					+ Math.max(0, a.hpb - 10) * price(comp, "FUMING_POTATO_BOOK") * factor(comp, "fuming", 0.85);
			out.add(new Part("potato books", v));
		}
		if (a.stars > 0) {
			JsonObject uc = HudFormatter.obj(comp, "upgrade_costs");
			JsonArray tiers = uc != null && uc.has(a.itemId) && uc.get(a.itemId).isJsonArray() ? uc.getAsJsonArray(a.itemId) : new JsonArray();
			double v = 0;
			for (int i = 0; i < Math.min(a.stars, tiers.size()); i++) {
				for (JsonElement ce : tiers.get(i).getAsJsonArray()) {
					JsonObject c = ce.getAsJsonObject();
					double amt = c.has("amount") ? c.get("amount").getAsDouble() : 1;
					if ("ESSENCE".equals(str(c, "type"))) v += amt * price(comp, "ESSENCE_" + str(c, "essence_type"));
					else if (str(c, "item_id") != null) v += amt * price(comp, str(c, "item_id"));
				}
			}
			v *= factor(comp, "stars", 0.9);
			if (tiers.size() > 0 && a.stars > tiers.size()) {
				String[] ms = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};
				for (int i = 0; i < Math.min(a.stars - tiers.size(), 5); i++) v += price(comp, ms[i]) * factor(comp, "master_star", 0.95);
			}
			if (v > 0) out.add(new Part("stars", v));
		}
		double g = 0;
		for (String gem : a.gems) g += price(comp, gem);
		if (g > 0) out.add(new Part("gems", g * factor(comp, "gem", 1.0)));
		if (a.aow > 0) out.add(new Part("art of war", price(comp, "THE_ART_OF_WAR") * factor(comp, "art_of_war", 0.85)));
		if (a.petHeld != null) out.add(new Part(TIER_BOOST.equals(a.petHeld) ? "tier boost" : "pet item",
				price(comp, a.petHeld) * factor(comp, "pet_item", 0.9)));
		if (a.petSkin != null) out.add(new Part("skin", price(comp, "PET_SKIN_" + a.petSkin) * factor(comp, "pet_skin", 0.8)));
		out.removeIf(p -> !(p.coins() > 0));
		return out;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	/** XP progress 0 (level 1) … 1 (max level); the shown rarity sets the XP curve. */
	static double petFraction(Attrs a, String key, JsonObject comp) {
		String[] parts = key.split("_");
		String tier = a.petTier != null ? a.petTier : parts[parts.length - 1];
		String type = key.length() > 4 ? key.substring(4, Math.max(4, key.lastIndexOf('_'))) : "";
		double max = 25_353_230;
		JsonObject xm = HudFormatter.obj(comp, "pet_xp_max");
		Double m = HudFormatter.num(xm, tier);
		if (m != null) max = m;
		if (comp.has("level_200_pets") && comp.get("level_200_pets").isJsonArray()) {
			for (JsonElement e : comp.getAsJsonArray("level_200_pets")) {
				if (e.getAsString().equals(type)) {
					max = comp.has("xp_max_200") ? comp.get("xp_max_200").getAsDouble() : 210_255_385;
				}
			}
		}
		return Math.max(0, Math.min(1, a.petXp / max));
	}

	/** Estimate for this copy, or null when there is no fitted model or no component prices yet. */
	static Estimate estimate(JsonObject doc, String key, Attrs a) {
		if (a == null || data == null) return null;
		JsonObject model = HudFormatter.obj(doc, "market", "model");
		if (model == null && !a.pet) model = MarketBook.model(key);       // plain-copy price from your own AH scan
		JsonObject comp = data.components();
		if (model == null || comp == null) return null;
		List<Part> comps = components(a, comp);
		List<Part> parts = new ArrayList<>();
		double base, frac = 0;
		String type = str(model, "type");
		Double ns = HudFormatter.num(model, "n_sales");
		if ("pet".equals(type) && a.pet) {
			double v1 = HudFormatter.num(model, "v1"), v100 = HudFormatter.num(model, "v100");
			frac = petFraction(a, key, comp);
			base = v1 + (v100 - v1) * frac;
			String real = realPetTier(a.petTier, a.petHeld);
			parts.add(new Part((a.petLevel > 0 ? "lvl " + a.petLevel : "pet by level") + (real != null ? " " + real.toLowerCase(Locale.ROOT) : ""), base));
		} else if ("item".equals(type)) {
			Double b = HudFormatter.num(model, "base");
			if (b == null) return null;
			base = b;
			parts.add(new Part("clean", base));
		} else {
			return null;
		}
		parts.addAll(comps);
		double total = 0;
		for (Part p : parts) total += p.coins();
		// upgrades are only worth what buyers actually pay: never value a copy above what copies of this item
		// really sell for (the top 10 % of recent sale prices). A 3M helmet with an 8M recomb does not sell for 11M.
		double ceiling = NefClient.cofl == null ? 0 : ceiling(NefClient.cofl.weekClean(key, false));
		if (ceiling > 0 && total > ceiling && total > base) {
			parts.add(new Part("but copies sell for at most about", ceiling - total));
			total = Math.max(base, ceiling);
		}
		return new Estimate(total, base, parts, a, type, ns == null ? 0 : ns.intValue(), frac);
	}

	/**
	 * What copies of an item really sell for at the top end: the 90th percentile of the highest sale in each period of
	 * the last 7 days (SkyCofl). With fewer than 20 periods: the 2nd-highest period high (one odd sale doesn't count).
	 * 0 = no sales data yet (no cap).
	 */
	static double ceiling(List<double[]> week) {
		if (week == null) return 0;
		List<Double> highs = new ArrayList<>();
		for (double[] q : week) if (q.length > 4 && q[4] > 0 && q[2] > 0) highs.add(q[2]);
		if (highs.size() < 2) return 0;
		highs.sort(Double::compare);
		if (highs.size() < 20) return highs.get(highs.size() - 2);
		return highs.get((int) Math.floor(0.9 * (highs.size() - 1)));
	}

	/** "  value ≈ 152M = clean 120M + stars 12M + enchants 15M + recomb 5M" (+ rarity line). */
	static List<Component> lines(Estimate e) {
		List<Component> out = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		boolean capped = false;
		for (int i = 0; i < e.parts().size(); i++) {
			Part p = e.parts().get(i);
			if (p.coins() < 0) { capped = true; continue; }        // the "sells for at most" cap, shown at the end
			sb.append(i == 0 ? "" : " + ").append(p.label()).append(' ').append(HudFormatter.fmt(p.coins()));
		}
		if (capped) sb.append(", capped at what copies really sell for");
		out.add(HudFormatter.line("    this copy ≈ ", HudFormatter.GRAY, HudFormatter.fmt(e.value()), HudFormatter.WHITE,
				" = " + sb, HudFormatter.DARK));
		Attrs a = e.attrs();
		String rar = a.pet ? a.petTier : a.rarity;
		if (rar != null) {
			String note = a.pet && TIER_BOOST.equals(a.petHeld) ? " (tier boosted from " + realPetTier(a.petTier, a.petHeld) + ")"
					: !a.pet && a.recomb ? " (recombobulated, +1 rarity)" : "";
			out.add(HudFormatter.line("    rarity " + rar + note + (a.pet && a.petLevel > 0
					? String.format(Locale.ROOT, " · lvl %d, %.0f%% of the way to max", a.petLevel, 100 * e.petFraction()) : ""),
					HudFormatter.DARK));
		}
		return out;
	}
}

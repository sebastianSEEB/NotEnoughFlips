package dev.notenoughflips;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What an NPC sells an item for: nobody pays more than that on the auction house or bazaar, however the listings and
 * sale history look (someone listing shop items above the shop price can't make the mod call them a deal).
 *
 * The shop list ships inside the mod (assets/notenoughflips/npc-prices.json, built by tools/npc_prices.py from the
 * NotEnoughUpdates item repository, MIT). A shop way = coins + ingredients per item; ingredients are priced live
 * (bazaar instant-buy, else the lowest BIN from your AH scan) and the cheapest way wins. A way with an ingredient that
 * can't be priced is skipped.
 */
final class NpcPrices {
	/** One way to get the item from an NPC: coins plus ingredients, per item. */
	record Way(double coins, List<String> ids, List<Double> qty) {}

	private static volatile Map<String, List<Way>> shops;

	private NpcPrices() {}

	private static Map<String, List<Way>> shops() {
		Map<String, List<Way>> s = shops;
		if (s != null) return s;
		synchronized (NpcPrices.class) {
			if (shops != null) return shops;
			Map<String, List<Way>> m = new HashMap<>();
			try (InputStream in = NpcPrices.class.getResourceAsStream("/assets/notenoughflips/npc-prices.json")) {
				if (in != null) m = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (Exception ignored) {
				// no table: no NPC caps
			}
			shops = m;
			return m;
		}
	}

	/** Pure: the shipped JSON → item id → ways. */
	static Map<String, List<Way>> parse(String json) {
		Map<String, List<Way>> m = new HashMap<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonObject sh = root.getAsJsonObject("shops");
		for (String id : sh.keySet()) {
			List<Way> ways = new ArrayList<>();
			for (JsonElement o : sh.getAsJsonArray(id)) {
				JsonArray a = o.getAsJsonArray();
				List<String> ids = new ArrayList<>();
				List<Double> qty = new ArrayList<>();
				for (JsonElement p : a.get(1).getAsJsonArray()) {
					ids.add(p.getAsJsonArray().get(0).getAsString());
					qty.add(p.getAsJsonArray().get(1).getAsDouble());
				}
				ways.add(new Way(a.get(0).getAsDouble(), ids, qty));
			}
			m.put(id, ways);
		}
		return m;
	}

	/**
	 * Pure: the cheapest way to buy one item from an NPC, with {@code price} giving an ingredient's price (≤ 0 = unknown,
	 * that way is skipped). 0 = no NPC sells it (or nothing could be priced).
	 */
	static double cheapest(List<Way> ways, ToDoubleFunction<String> price) {
		if (ways == null) return 0;
		double best = 0;
		for (Way w : ways) {
			double total = w.coins();
			boolean ok = true;
			for (int i = 0; i < w.ids().size(); i++) {
				double p = price.applyAsDouble(w.ids().get(i));
				if (!(p > 0)) { ok = false; break; }
				total += p * w.qty().get(i);
			}
			if (ok && total > 0 && (best == 0 || total < best)) best = total;
		}
		return best;
	}

	/** What an NPC sells one of this item for right now (0 = no NPC sells it). */
	static double price(String key) {
		if (key == null) return 0;
		return cheapest(shops().get(key), NpcPrices::ingredient);
	}

	private static double ingredient(String id) {
		DataClient d = NefClient.data();
		DataClient.Live l = d == null ? null : d.live(id);
		if (l != null && l.buy() > 0) return l.buy();
		MarketBook.Item b = MarketBook.get(id);
		return b != null && b.lowest() > 0 ? b.lowest() : 0;
	}

	/** {@code value} capped at the NPC price (unchanged when no NPC sells it). */
	static double cap(String key, double value) {
		double n = price(key);
		return n > 0 ? Math.min(value, n) : value;
	}
}

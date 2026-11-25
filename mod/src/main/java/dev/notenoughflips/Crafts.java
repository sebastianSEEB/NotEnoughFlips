package dev.notenoughflips;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Craft flips worked out in the mod, every minute, from live prices and the recipes you have unlocked.
 *
 * Recipes: every crafting-table recipe (assets/notenoughflips/recipes.json, from NotEnoughUpdates-REPO, MIT) with its
 * unlock ("Ender Pearl VIII"), checked against your profile by {@link Unlocks}.
 * Costs: each ingredient at its cheapest source: a bazaar buy order (when the spread is tight and it trades enough to
 * fill), otherwise instant-buy, an NPC shop, the lowest BIN, or crafting it (2 levels deep).
 * Revenue: a bazaar output sold with a sell offer at the current cheapest offer (−1.25 % tax); an auction output listed
 * just under the lowest BIN, never above its real sale price, after the listing fee and claim tax.
 * Ranked like the published list: profit per day if you sell ~10 % of the item's daily sales, within your budget.
 * Auction outputs need sales data (SkyCofl, or the 3-hourly published list) before they're shown.
 */
final class Crafts {
	private Crafts() {}

	record Ing(String id, double n) {}

	record Recipe(String out, int makes, List<Ing> ings, String req, boolean soulbound) {}

	static final double BZ_TAX = 0.0125, MIN_MARGIN = 0.08, MAX_MARGIN = 1.5, SHARE = 0.10;

	private static Map<String, List<Recipe>> recipes;
	private static volatile JsonObject list;              // {"updated", "budget", "flips": [...]}, same shape as the published one
	private static volatile long builtAt;
	private static final AtomicBoolean BUSY = new AtomicBoolean();
	private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "NEF craft flips");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	static Map<String, List<Recipe>> recipes() {
		if (recipes == null) recipes = load();
		return recipes;
	}

	private static Map<String, List<Recipe>> load() {
		try (InputStream in = Crafts.class.getResourceAsStream("/assets/notenoughflips/recipes.json")) {
			if (in == null) return Map.of();
			return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (Exception e) {
			System.err.println("[NEF] recipes: " + e);
			return Map.of();
		}
	}

	/** {"recipes": {"OUT": [{"n": 1, "i": [["ING", 32]], "r": "Ender Pearl VIII", "sb": 1}]}} */
	static Map<String, List<Recipe>> parse(JsonObject root) {
		Map<String, List<Recipe>> out = new HashMap<>();
		JsonObject rs = root.getAsJsonObject("recipes");
		if (rs == null) return out;
		for (Map.Entry<String, JsonElement> e : rs.entrySet()) {
			List<Recipe> l = new ArrayList<>();
			for (JsonElement re : e.getValue().getAsJsonArray()) {
				JsonObject o = re.getAsJsonObject();
				List<Ing> ings = new ArrayList<>();
				for (JsonElement ie : o.getAsJsonArray("i")) {
					JsonArray a = ie.getAsJsonArray();
					ings.add(new Ing(a.get(0).getAsString(), a.get(1).getAsDouble()));
				}
				l.add(new Recipe(e.getKey(), o.has("n") ? o.get("n").getAsInt() : 1, ings,
						o.has("r") ? o.get("r").getAsString() : null, o.has("sb")));
			}
			out.put(e.getKey(), l);
		}
		return out;
	}

	/** The live list, or null until the first one is built. */
	static JsonObject list() {
		return list;
	}

	static long builtAt() {
		return builtAt;
	}

	/** Every ~10 s from the client tick: rebuild once a minute, on its own thread. */
	static void tick(NefConfig cfg, DataClient data, double budget) {
		if (cfg == null || data == null || !cfg.craftLive) return;
		if (System.currentTimeMillis() - builtAt < 60_000L || data.bazaarAll().isEmpty()) return;
		if (!BUSY.compareAndSet(false, true)) return;
		WORK.execute(() -> {
			try {
				list = build(data, budget);
				builtAt = System.currentTimeMillis();
			} catch (RuntimeException e) {
				System.err.println("[NEF] craft flips: " + e);
			} finally {
				BUSY.set(false);
			}
		});
	}

	// ---- pricing ----

	/** Where to price things from (live in game; tests pass their own). */
	interface Prices {
		DataClient.Live bazaar(String id);

		double npc(String id);

		double lowestBin(String id);

		/** {sell price ceiling, sales per day} for an auction item, or null when unknown. */
		double[] auction(String id);
	}

	record Cost(double unit, String how) {}

	/** Cheapest way to get one of an ingredient (null = no price): bazaar, NPC, lowest BIN, or crafting it. */
	static Cost cost(String id, int depth, Prices p, Map<String, Cost> memo) {
		String key = id + "#" + depth;
		if (memo.containsKey(key)) return memo.get(key);
		memo.put(key, null);                                        // cycles: no price
		Cost best = null;
		DataClient.Live l = p.bazaar(id);
		if (l != null && l.buy() > 0) {
			// a buy order only fills when the spread is tight and it trades; otherwise you pay the instant-buy price
			best = l.sell() >= 0.9 * l.buy() && l.sellWeek() >= 7 * 24 * 10 ? new Cost(l.sell() + 0.1, "bazaar order")
					: new Cost(l.buy(), "bazaar instant buy");
		}
		double npc = p.npc(id);
		if (npc > 0 && (best == null || npc < best.unit())) best = new Cost(npc, "NPC");
		double lb = p.lowestBin(id);
		if (lb > 0 && (best == null || lb < best.unit())) best = new Cost(lb, "lowest BIN");
		if (depth < 2) {
			for (Recipe r : recipes().getOrDefault(id, List.of())) {
				Double c = craftCost(r, depth + 1, p, memo);
				if (c != null && (best == null || c / r.makes() < best.unit())) best = new Cost(c / r.makes(), "craft");
			}
		}
		memo.put(key, best);
		return best;
	}

	private static Double craftCost(Recipe r, int depth, Prices p, Map<String, Cost> memo) {
		double total = 0;
		for (Ing g : r.ings()) {
			Cost c = cost(g.id(), depth, p, memo);
			if (c == null) return null;
			total += c.unit() * g.n();
		}
		return total;
	}

	/** One craft flip, or null when it doesn't pay / can't be sold / isn't known well enough. */
	static JsonObject evaluate(Recipe r, Prices p, Map<String, Cost> memo, double budget) {
		if (r.soulbound()) return null;
		Double total = craftCost(r, 1, p, memo);
		if (total == null || total <= 0) return null;
		double per = total / r.makes();
		double sell, net, perDay;
		String where;
		DataClient.Live out = p.bazaar(r.out());
		if (out != null && out.buy() > 0) {
			sell = out.buy() - 0.1;                                  // a sell offer at the current cheapest
			net = sell * (1 - BZ_TAX);
			perDay = out.buyWeek() / 7.0;                            // insta-buys a day: what eats sell offers
			where = "bazaar";
		} else {
			double[] ah = p.auction(r.out());
			if (ah == null) return null;
			double lb = p.lowestBin(r.out());
			sell = lb > 0 ? lb * 0.99 : ah[0];
			if (ah[0] > 0) sell = Math.min(sell, ah[0]);           // never above what it actually sells for
			net = Verdict.netAfterFees(sell);
			perDay = ah[1];
			where = "AH";
		}
		double profit = net - per, margin = profit / per;
		if (margin < MIN_MARGIN || margin > MAX_MARGIN || perDay < 1) return null;   // > 150 %: thin or bad data
		double crafts = Math.min(perDay * SHARE, budget / Math.max(per, 1));
		JsonObject o = new JsonObject();
		o.addProperty("item", r.out());
		o.addProperty("where", where);
		o.addProperty("cost", Math.round(per));
		o.addProperty("sell", Math.round(sell));
		o.addProperty("profit", Math.round(profit));
		o.addProperty("margin", Math.round(margin * 1000) / 1000.0);
		o.addProperty("sales_per_day", Math.round(perDay * 10) / 10.0);
		o.addProperty("crafts_per_day", Math.round(crafts * 10) / 10.0);
		o.addProperty("profit_per_day", Math.round(profit * crafts));
		o.addProperty("makes", r.makes());
		if (r.req() != null) o.addProperty("requires", r.req());
		JsonArray parts = new JsonArray();
		for (Ing g : r.ings()) {
			Cost c = cost(g.id(), 1, p, memo);
			JsonObject gi = new JsonObject();
			gi.addProperty("id", g.id());
			gi.addProperty("n", g.n());
			gi.addProperty("unit", Math.round(c.unit() * 10) / 10.0);
			gi.addProperty("how", c.how());
			parts.add(gi);
		}
		o.add("ingredients", parts);
		return o;
	}

	/** The whole list: every recipe priced, best profit per day first, top 150. */
	static JsonObject build(DataClient data, double budget) {
		Map<String, double[]> published = new HashMap<>();             // AH sales data from the published list
		JsonObject pub = data.craftFlips();
		if (pub != null && pub.has("flips"))
			for (JsonElement e : pub.getAsJsonArray("flips")) {
				JsonObject r = e.getAsJsonObject();
				Double spd = HudFormatter.num(r, "sales_per_day"), sell = HudFormatter.num(r, "sell");
				if ("AH".equals(HudFormatter.str(r, "where")) && spd != null && sell != null)
					published.put(HudFormatter.str(r, "item"), new double[]{sell, spd});
			}
		List<String> wanted = new ArrayList<>();
		Prices p = new Prices() {
			public DataClient.Live bazaar(String id) {
				DataClient.Live l = data.bazaarAll().get(id);
				if (l == null) return null;
				String trap = Bazaar.trap(l, data.bazaarNormalCached(id));
				return trap != null && trap.startsWith("pumped") ? null : l;   // pumped prices are no guide
			}

			public double npc(String id) {
				return NpcPrices.price(id);
			}

			public double lowestBin(String id) {
				MarketBook.Item it = MarketBook.get(id);
				return it == null ? 0 : it.lowest();
			}

			public double[] auction(String id) {
				JsonObject d = NefClient.docFor(id, (CoflClient.Pri) null);
				if (d != null && !CoflClient.thin(d)) {
					if (Sniper.manipulated(d)) return null;
					Double fair = Verdict.fair(d);
					double spd = Market.salesPerDay(d);
					return fair == null || spd <= 0 ? null : new double[]{fair, spd};
				}
				double[] pubRow = published.get(id);
				if (pubRow == null) wanted.add(id);
				return pubRow;
			}
		};
		Map<String, Cost> memo = new HashMap<>();
		List<JsonObject> rows = new ArrayList<>();
		for (List<Recipe> rs : recipes().values()) {
			JsonObject best = null;
			for (Recipe r : rs) {
				JsonObject o = evaluate(r, p, memo, budget);
				if (o != null && (best == null || o.get("profit_per_day").getAsDouble() > best.get("profit_per_day").getAsDouble())) best = o;
			}
			if (best != null) {
				Boolean ok = Unlocks.meets(HudFormatter.str(best, "requires"));
				best.addProperty("unlocked", ok == null ? "maybe" : ok ? "yes" : "no");
				rows.add(best);
			}
		}
		// auction outputs nobody has sales for yet: fetch them in the background for the next round
		if (NefClient.cofl != null && !wanted.isEmpty())
			NefClient.cofl.prefetch(wanted.size() > 60 ? wanted.subList(0, 60) : wanted);
		rows.sort((a, b) -> Double.compare(b.get("profit_per_day").getAsDouble(), a.get("profit_per_day").getAsDouble()));
		JsonObject res = new JsonObject();
		res.addProperty("updated", System.currentTimeMillis());
		res.addProperty("budget", budget);
		res.addProperty("live", true);
		JsonArray arr = new JsonArray();
		for (JsonObject r : rows.subList(0, Math.min(150, rows.size()))) {
			r.addProperty("name", Desk.pretty(HudFormatter.str(r, "item")));
			arr.add(r);
		}
		res.add("flips", arr);
		return res;
	}
}

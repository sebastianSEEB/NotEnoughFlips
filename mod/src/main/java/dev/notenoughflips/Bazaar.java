package dev.notenoughflips;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bazaar flip ranking over every product (live quick_status from the public Hypixel API):
 *
 *   buy order at top + 0.1  →  sell offer at cheapest − 0.1  →  profit per unit after the bazaar tax
 *   sized by volume: your buy order fills from instant sells, your offer from instant buys (bazaarSharePercent of each),
 *   as many as fit one round of bazaarRoundHours and your budget → total profit, round time, profit per hour.
 *
 * With forecasting on, the sell offer's price is moved by the published forecast for the time it sells (see
 * {@link Forecasts}), so a product that's about to drop ranks lower and one that's rising (beyond normal error) higher.
 *
 * Skipped: products in a current market event (≥ 6 products crashing / pumping together, see market/bzsignals.py),
 * margins under 2 % or over 100 % (thin or manipulated), fewer than 10 units traded per hour, products
 * whose buy order alone costs more than your budget. Also turns a display name ("Enchanted Diamond") back into the
 * product id for the Bazaar menus and your orders.
 */
final class Bazaar {
	private static volatile List<Market.BzFlip> ranked = List.of();
	private static volatile List<Market.BzFlip> passing = List.of();
	private static volatile java.util.Set<String> repricedIds = java.util.Set.of();
	private static final java.util.Set<String> repricedNow = new java.util.HashSet<>();

	/** The sell offer of this flip is priced at the product's normal 7-day level (its offers are pumped right now). */
	static boolean repriced(String id) {
		return repricedIds.contains(id);
	}   // every flip that passed the checks, any order
	private static volatile long rankedAt;
	private static volatile double rankedBudget = -1;
	private static volatile Map<String, String> prettyNames = Map.of();
	private static volatile int prettyFor = -1;

	private static volatile DataClient data;
	private static volatile boolean forecastOn;

	private Bazaar() {}

	/**
	 * Best bazaar flips for this budget, best profit/hour first. Live: re-ranked on every new bazaar snapshot (every
	 * liveBazaarRefreshSeconds), or every bazaarRefreshMinutes when that's set above 0, or when your budget changes > 10 %.
	 * Only products that buy AND sell fast make it: both sides must fill at ≥ 10 units/h for you after competition, the
	 * slower side must be at least 1/5 of the faster one, and a full round must fit in bazaarRoundHours.
	 */
	static List<Market.BzFlip> ranking(NefConfig cfg, DataClient data, double budget) {
		long now = System.currentTimeMillis();
		Bazaar.data = data;
		Map<String, DataClient.Live> all = data.bazaarAll();
		if (forecastOn != cfg.forecastFlips) {                    // toggled: re-rank now
			forecastOn = cfg.forecastFlips;
			rankedAt = 0;
		}
		boolean budgetMoved = rankedBudget <= 0 || Math.abs(rankedBudget - budget) > rankedBudget * 0.10;
		boolean fresh = cfg.bazaarRefreshMinutes <= 0 ? data.bazaarAt() <= rankedAt : now - rankedAt < cfg.bazaarRefreshMinutes * 60_000L;
		if (!ranked.isEmpty() && !budgetMoved && fresh) return ranked;
		if (all.isEmpty()) return ranked;
		List<Market.BzFlip> out = new ArrayList<>();
		java.util.Set<String> event = eventMembers(data.bzSignals());
		java.util.Set<String> rich = pumped(data.bzSignals());
		int wideQueued = 0;
		for (Map.Entry<String, DataClient.Live> e : all.entrySet()) {
			if (event.contains(e.getKey())) continue;             // part of a market event (crash / pump): old spreads are no guide
			DataClient.Live l = e.getValue();
			double[] normal = data.bazaarNormalCached(e.getKey());
			String why = trap(l, normal);
			boolean repriced = false;
			if (why != null && why.startsWith("pumped") && l.sell() <= normal[1] * 1.5 && l.buy() > normal[0]) {
				// only the sell-offer side jumped: you buy at today's low order price anyway, so price the sale at its
				// normal 7-day level instead of the pumped one (and only keep it if that still pays)
				DataClient.Live n = new DataClient.Live(normal[0], l.sell(), l.ts(), l.buyWeek(), l.sellWeek(), l.offers(), l.orders(),
						l.nearOffers(), l.nearOrders());
				if (trap(n, normal) == null) {
					l = n;
					repriced = true;
					why = null;
				}
			}
			if (why != null) continue;                             // crashing, pulled order, or pumped on both sides
			if (BzWatch.flash(e.getKey())) continue;                // the spread only opened for a moment: gone before you fill
			if (!repriced && rich.contains(e.getKey())) continue;  // priced far above its peers right now: don't buy into it
			double npc = NpcPrices.price(e.getKey());
			if (npc > 0 && l.buy() > npc) continue;                 // an NPC sells it cheaper than your sell offer would be
			Market.BzFlip f = flip(cfg, e.getKey(), l, budget);
			if (f != null && normal == null && f.pct() > cfg.bazaarMaxSpreadPercent / 100.0 && wideQueued++ < 20 && f.unitsPerHour() >= 100)
				data.bazaarNormal(e.getKey());                     // wide spread, no 7-day level yet: fetch it, re-check next refresh
			if (f == null || f.perUnit() <= 0 || f.pct() < 0.02 || f.pct() > cfg.bazaarMaxSpreadPercent / 100.0) continue;
			if (f.unitsPerHour() < 10 || f.qty() < 1 || f.totalProfit() < 1000) continue;
			if (Math.min(f.buyFillPerHour(), f.sellFillPerHour()) < 10 || f.balance() < 0.2) continue;   // must buy AND sell fast
			if (f.roundHours() > Math.max(0.25, cfg.bazaarRoundHours) * 1.05) continue;
			out.add(f);
			if (repriced) repricedNow.add(f.id());
		}
		repricedIds = java.util.Set.copyOf(repricedNow);
		repricedNow.clear();
		out.sort((a, b) -> Double.compare(b.coinsPerHour(), a.coinsPerHour()));
		for (int i = 0; i < Math.min(25, out.size()); i++) data.bazaarSlide(out.get(i).id());   // queue 7-day trends for the top
		for (int i = 0; i < Math.min(40, out.size()); i++) data.bazaarNormal(out.get(i).id());  // and their normal level (trap check)
		passing = List.copyOf(out);
		ranked = out.size() > 50 ? new ArrayList<>(out.subList(0, 50)) : out;
		Map<String, Integer> r = new HashMap<>();
		for (int i = 0; i < ranked.size(); i++) r.put(ranked.get(i).id(), i + 1);
		ranks = r;
		rankedAt = now;
		rankedBudget = budget;
		return ranked;
	}

	/** Sort orders for the Best flips list: id, chip label. "spread" = SkyCofl's public formula, every product. */
	static final String[][] SORTS = {{"hour", "/hour"}, {"profit", "Profit"}, {"pct", "%"}, {"volume", "Volume"}, {"spread", "Spread"}};

	static final double SPREAD_FEE = 0.0125;   // sell-side fee the spread ranking assumes

	/**
	 * One product in the spread ranking. score = (offer − order − offer × 1.25 %) × weekly sales ÷ 168: the whole spread
	 * on every sale of the week, i.e. the most it could make an hour if you got every fill. manipulated = its sell offer
	 * is over 2× its 7-day level (1.5× for items over 7.5M). nef = null when NEF's own list has it too, else why not.
	 */
	record Spread(String id, double order, double offer, double weekly, double score, boolean manipulated, String nef, Market.BzFlip flip) {}

	/** Pure: the spread score per hour. */
	static double spreadScore(double order, double offer, double weekly) {
		if (order <= 0 || offer <= order || weekly <= 0) return 0;
		return (offer - order - offer * SPREAD_FEE) * weekly / 168.0;
	}

	/** Pure: the manipulation flag the spread list uses. */
	static boolean spreadManipulated(double offer, double normalOffer) {
		if (normalOffer <= 0) return false;
		double r = offer / normalOffer;
		return r > 2 || r > 1.5 && offer > 7_500_000;
	}

	private static volatile List<Spread> spreads = List.of();
	private static volatile long spreadsAt = -1;

	/**
	 * Every product ranked by the plain spread formula (the list other bazaar sites and mods show, including the top
	 * entries some of them keep for paying users). Few filters on purpose: only affordable and actually traded. Each
	 * row says whether NEF's stricter list agrees, and why not when it doesn't.
	 */
	static List<Spread> spreadRanking(NefConfig cfg, DataClient data, double budget) {
		List<Market.BzFlip> mine = ranking(cfg, data, budget);
		if (spreadsAt == rankedAt && !spreads.isEmpty()) return spreads;
		java.util.Set<String> ok = new java.util.HashSet<>();
		for (Market.BzFlip f : passing.isEmpty() ? mine : passing) ok.add(f.id());
		List<Spread> out = new ArrayList<>();
		for (Map.Entry<String, DataClient.Live> e : data.bazaarAll().entrySet()) {
			DataClient.Live l = e.getValue();
			double weekly = Math.min(l.buyWeek(), l.sellWeek());
			double sc = spreadScore(l.sell(), l.buy(), weekly);
			if (sc <= 0 || l.buy() > budget) continue;
			double[] normal = data.bazaarNormalCached(e.getKey());
			boolean manip = normal != null && spreadManipulated(l.buy(), normal[0]);
			String why = null;
			if (!ok.contains(e.getKey())) {
				String t = trap(l, normal);
				why = t != null ? t : BzWatch.flash(e.getKey()) ? "the spread only opened briefly"
						: manip ? "price far above its 7-day level" : "too slow to fill for you, or too thin";
			}
			out.add(new Spread(e.getKey(), l.sell(), l.buy(), weekly, sc, manip, why, flip(cfg, e.getKey(), l, budget)));
		}
		out.sort((a, b) -> Double.compare(b.score(), a.score()));
		for (int i = 0; i < Math.min(30, out.size()); i++) data.bazaarNormal(out.get(i).id());   // their 7-day level, for the flag
		spreads = out.size() > 50 ? new ArrayList<>(out.subList(0, 50)) : out;
		spreadsAt = rankedAt;
		return spreads;
	}

	/**
	 * The same safe flips (same checks as {@link #ranking}) in the order you picked: profit per hour (default), most
	 * profit per round, highest margin %, or most coins traded per hour on the bazaar (busiest products first).
	 */
	static List<Market.BzFlip> sorted(NefConfig cfg, DataClient data, double budget, String sort) {
		List<Market.BzFlip> base = ranking(cfg, data, budget);
		if (sort == null || sort.equals("hour")) return base;
		List<Market.BzFlip> out = new ArrayList<>(passing.isEmpty() ? base : passing);
		java.util.Comparator<Market.BzFlip> c = switch (sort) {
			case "profit" -> java.util.Comparator.comparingDouble(Market.BzFlip::totalProfit);
			case "pct" -> java.util.Comparator.comparingDouble(Market.BzFlip::pct);
			case "volume" -> java.util.Comparator.comparingDouble(Bazaar::coinVolume);
			default -> java.util.Comparator.comparingDouble(Market.BzFlip::coinsPerHour);
		};
		out.sort(c.reversed());
		return out.size() > 50 ? new ArrayList<>(out.subList(0, 50)) : out;
	}

	/** Coins traded per hour on the bazaar for this product (units/h × price). */
	static double coinVolume(Market.BzFlip f) {
		return f.marketPerHour() * f.offer();
	}

	/**
	 * Why a product is a trap right now, or null: its instant prices are ≥ 50 % above (pumped) or ≥ 40 % below
	 * (crashing / flash-crash buy orders) its own median of the last 7 days. normal = {median buy, median sell, hours}.
	 */
	static String trap(DataClient.Live l, double[] normal) {
		if (l == null || normal == null || normal[0] <= 0 || normal[1] <= 0) return null;
		if (l.buy() > normal[0] * 1.5 || l.sell() > normal[1] * 1.5)
			return String.format(java.util.Locale.ROOT, "pumped: %.0f%% above its 7-day level", 100 * (Math.max(l.buy() / normal[0], l.sell() / normal[1]) - 1));
		if (l.sell() > 0 && l.sell() < normal[1] * 0.6 || l.buy() > 0 && l.buy() < normal[0] * 0.6)
			return String.format(java.util.Locale.ROOT, "crashing: %.0f%% under its 7-day level", 100 * (1 - Math.min(l.sell() / normal[1], l.buy() / normal[0])));
		// the gap between buy and sell is normally a few %; a sudden wide gap means one side was pulled or walled
		double usual = Math.max(0.005, 1 - normal[1] / normal[0]), now = l.buy() > 0 ? 1 - l.sell() / l.buy() : 0;
		if (now > 0.10 && now > 3 * usual)
			return String.format(java.util.Locale.ROOT, "spread %.0f%% (usually %.0f%%): one side was pulled", 100 * now, 100 * usual);
		return null;
	}

	/** Products the signals call far too expensive right now (high-confidence "sell" / "avoid"). */
	static java.util.Set<String> pumped(com.google.gson.JsonObject sj) {
		java.util.Set<String> out = new java.util.HashSet<>();
		if (sj == null || !sj.has("signals")) return out;
		for (com.google.gson.JsonElement e : sj.getAsJsonArray("signals")) {
			com.google.gson.JsonObject o = e.getAsJsonObject();
			String a = HudFormatter.str(o, "action");
			if (("sell".equals(a) || "avoid".equals(a)) && "high".equals(HudFormatter.str(o, "conf")) && o.has("id"))
				out.add(o.get("id").getAsString());
		}
		return out;
	}

	/** Products in a current market event (Trade Desk → Market → Tips, "!"): kept out of the flip list until it settles. */
	static java.util.Set<String> eventMembers(com.google.gson.JsonObject sj) {
		java.util.Set<String> out = new java.util.HashSet<>();
		if (sj == null || !sj.has("signals")) return out;
		for (com.google.gson.JsonElement e : sj.getAsJsonArray("signals")) {
			com.google.gson.JsonObject o = e.getAsJsonObject();
			if (!"event".equals(HudFormatter.str(o, "type")) || !o.has("members")) continue;
			for (com.google.gson.JsonElement m : o.getAsJsonArray("members")) out.add(m.getAsString());
		}
		return out;
	}

	private static volatile Map<String, Integer> ranks = Map.of();

	/** Rank (1 = best) of a product in the current snapshot, or 0. */
	static int rank(String id) {
		Integer r = id == null ? null : ranks.get(id);
		return r == null ? 0 : r;
	}

	/** When the current snapshot was made (0 = none yet). */
	static long snapshotAt() {
		return rankedAt;
	}

	/** Volume-sized flip for one product with your settings. */
	static Market.BzFlip flip(NefConfig cfg, String id, DataClient.Live l, double budget) {
		DataClient dc = data != null ? data : NefClient.data();
		Forecasts.Side fc = cfg.forecastFlips && dc != null ? dc.forecast(id, "buy") : null;
		double slide = cfg.forecastFlips && dc != null ? dc.bazaarSlide(id) : Double.NaN;
		int orders = l.nearOrders() >= 0 ? l.nearOrders() : l.orders() / 10;         // near-top competitors (book summary)
		int offers = l.nearOffers() >= 0 ? l.nearOffers() : l.offers() / 10;
		return Market.bazaarFlip(id, l.buy(), l.sell(), l.buyWeek(), l.sellWeek(), orders, offers, cfg.bazaarTaxPercent,
				cfg.bazaarSharePercent / 100.0 * BzWatch.factor(id), budget, Math.max(0.25, cfg.bazaarRoundHours), fc, Double.isNaN(slide) ? 0 : slide);
	}

	/** "Enchanted Diamond" (any colour codes, "64x " prefixes, "BUY "/"SELL " prefixes) → ENCHANTED_DIAMOND, or null. */
	static String idForName(String display, DataClient data) {
		if (display == null) return null;
		String n = display.replaceAll("§.", "").trim();
		n = n.replaceFirst("^(?i)(BUY|SELL)\\s+", "").replaceFirst("^[0-9,]+x\\s+", "").trim().toLowerCase(Locale.ROOT);
		if (n.isEmpty()) return null;
		Map<String, DataClient.Live> all = data.bazaarAll();
		Map<String, String> names = data.itemNames();
		if (names != null) {
			String id = names.get(n);
			if (id != null && all.containsKey(id)) return id;
		}
		Map<String, String> pretty = prettyNames;
		if (prettyFor != all.size()) {
			Map<String, String> m = new HashMap<>();
			for (String id : all.keySet()) m.put(Desk.pretty(id).toLowerCase(Locale.ROOT), id);
			prettyNames = pretty = m;
			prettyFor = all.size();
		}
		return pretty.get(n);
	}

	private static volatile Map<String, String> byId = Map.of();
	private static volatile Map<String, String> byIdFor;

	/** Display name of a product id (Hypixel's own name when known). */
	static String name(String id, DataClient data) {
		Map<String, String> names = data.itemNames();
		if (names != null && byIdFor != names) {
			Map<String, String> m = new HashMap<>();
			for (Map.Entry<String, String> e : names.entrySet()) m.putIfAbsent(e.getValue(), titleCase(e.getKey()));
			byId = m;
			byIdFor = names;
		}
		String n = byId.get(id);
		return n != null ? n : Desk.pretty(id);
	}

	private static String titleCase(String lower) {
		StringBuilder sb = new StringBuilder();
		for (String w : lower.split(" ")) {
			if (w.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb.toString();
	}
}

package dev.notenoughflips;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * AH flips found by the mod's own AH scan (every scanMinutes while you play, see AhScanner).
 *
 * While the scan reads every page, each BIN's item is identified (same key as the tooltip) and the cheapest 6 copies
 * of every item are kept. A candidate is an item whose cheapest BIN sits clearly below the next copies:
 *
 * Only IDENTICAL copies are compared (same stars, recomb, reforge, big enchants, gems, attributes, skin…).
 *
 *   reference = min(2nd cheapest, median of the 2nd–5th cheapest)     (needs ≥ 3 identical copies listed)
 *   resale    = just under the reference, never above the item's fair value, never above what the cheapest
 *               copies actually sold for lately (clearing floor, see judge)
 *   profit    = resale after AH fees − price
 *
 * It's shown only if: profit ≥ your minimum (% and coins), the item sells ≥ minSalesPerDay, it should resell within
 * maxHoldHours, nothing flags manipulation, and the price fits your budget. Pets are skipped (level changes the price
 * too much to compare listings). Always check the auction's own tooltip before buying: it values that exact copy.
 */
final class FlipFeed {
	record Candidate(String key, String variant, String name, String uuid, double price, double ref, int listed, long scanAt, Spec spec, Valuation.Attrs attrs) {}

	record Flip(Candidate c, double resale, double net, double profit, double pct, Market.Liquidity liq, double floor,
			double forecast) {}

	record View(List<Flip> flips, int checking, int candidates) {}

	private static final int KEEP = 6;

	private static final class Bucket {
		final double[] p = new double[KEEP];
		final String[] u = new String[KEEP];
		final String[] names = new String[KEEP];
		final Spec[] specs = new Spec[KEEP];
		final Valuation.Attrs[] attrs = new Valuation.Attrs[KEEP];
		int n, listed;

		void add(double price, String uuid, String name) {
			add(price, uuid, name, null, null);
		}

		void add(double price, String uuid, String name, Spec spec, Valuation.Attrs at) {
			listed++;
			if (n == KEEP && price >= p[KEEP - 1]) return;
			int i = n < KEEP ? n++ : KEEP - 1;
			while (i > 0 && p[i - 1] > price) {
				p[i] = p[i - 1];
				u[i] = u[i - 1];
				names[i] = names[i - 1];
				specs[i] = specs[i - 1];
				attrs[i] = attrs[i - 1];
				i--;
			}
			p[i] = price;
			u[i] = uuid;
			names[i] = name;
			specs[i] = spec;
			attrs[i] = at;
		}
	}

	private static Map<String, Bucket> building = new HashMap<>();
	private static volatile List<Candidate> candidates = List.of();
	private static volatile long scanAt;
	private static final Set<String> announced = new HashSet<>();
	private static NefConfig cfg;
	private static long checkedAt;
	private static volatile List<Flip> lastValid = List.of();

	private FlipFeed() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	/** The underpriced-BIN candidates of the last scan (also used for /nef deals). */
	static List<Candidate> candidates() {
		return candidates;
	}

	static long scanAt() {
		return scanAt;
	}

	// ------------------------------------------------------------------ collecting (scan thread)
	static void begin() {
		building = new HashMap<>();
	}

	/** One auction from the scan (not yours). Always feeds the market book; the flip buckets only when feed is on. */
	static void collect(JsonObject a, long now, boolean feed) {
		if (!a.has("bin") || !a.get("bin").getAsBoolean()) return;
		if (a.has("claimed") && a.get("claimed").getAsBoolean()) return;
		if (a.has("end") && a.get("end").getAsLong() <= now) return;
		if (!a.has("item_bytes") || !a.has("starting_bid")) return;
		String[] key = {null};
		int[] count = {1};
		String[] variant = {""};
		Spec[] spec = {null};
		Valuation.Attrs[] attrs = {null};
		try {
			AhScanner.identify(a.get("item_bytes").getAsString(), key, count, variant, spec, attrs);
		} catch (Exception e) {
			return;
		}
		AhMarket.category(key[0], a);                                // item → category, for Bazaar → Market size
		String k = key[0];
		double unit = a.get("starting_bid").getAsDouble() / Math.max(1, count[0]);
		if (unit <= 0) return;
		MarketBook.observe(k, unit, variant[0] == null || variant[0].isEmpty());   // lowest + plain price, kept on your PC
		if (count[0] <= 1) MarketBook.observeSpec(k, unit, spec[0]);
		NpcFlips.observe(a, k, unit, count[0]);                                   // under what an NPC pays?               // for "a copy at least as upgraded is listed for X"
		if (!feed || k == null || k.startsWith("PET") || k.equals("ENCHANTED_BOOK")) return;
		String name = a.has("item_name") ? a.get("item_name").getAsString() : k;
		// only identical copies are compared: a plain one vs a starred/recombed/enchanted one is not a "gap"
		String v = variant[0] == null ? "" : variant[0];
		building.computeIfAbsent(v.isEmpty() ? k : k + "#" + v, x -> new Bucket()).add(unit, a.get("uuid").getAsString(), name, spec[0], count[0] <= 1 ? attrs[0] : null);
	}

	/** End of a scan: rank the gaps and start fetching prices for the best ones. */
	static void finish(long ts) {
		Map<String, double[]> refs = new HashMap<>();                 // for the undervalued-BIN alert (Sniper)
		for (Map.Entry<String, Bucket> e : building.entrySet()) {
			Bucket b = e.getValue();
			if (b.n < 2) continue;
			List<Double> next = new ArrayList<>();
			for (int i = 1; i < Math.min(b.n, 5); i++) next.add(b.p[i]);
			refs.put(e.getKey(), new double[]{b.p[0], Math.min(b.p[1], Market.median(next)), b.listed});
		}
		Sniper.setRefs(refs);
		// have the sales history of every item the sniper could alert on ready before a snipe shows up
		if (NefClient.cofl != null && cfg != null) {
			double min = Math.max(cfg.minItemValue, cfg.snipeMinValue);
			Map<String, Double> listedByKey = new HashMap<>();
			for (Map.Entry<String, double[]> e : refs.entrySet()) {
				double[] r = e.getValue();
				if (r[1] < min || r[2] < 3) continue;
				String k = e.getKey().contains("#") ? e.getKey().substring(0, e.getKey().indexOf('#')) : e.getKey();
				listedByKey.merge(k, r[2], Double::sum);
			}
			List<String> keys = new ArrayList<>(listedByKey.keySet());
			keys.sort((a, b) -> Double.compare(listedByKey.get(b), listedByKey.get(a)));   // most listed (most snipes) first
			NefClient.cofl.prefetch(keys.size() > 400 ? keys.subList(0, 400) : keys);
		}
		// items worth sniping (≥ 3 copies listed, lowest BIN ≥ 500k), most valuable first: their price history is
		// loaded in the background so a hit can be checked instantly instead of waiting for SkyCofl
		Map<String, Double> worth = new HashMap<>();
		for (Map.Entry<String, double[]> e : refs.entrySet()) {
			double[] r = e.getValue();
			if (r[2] < 3 || r[0] < 500_000) continue;
			String k = e.getKey().contains("#") ? e.getKey().substring(0, e.getKey().indexOf('#')) : e.getKey();
			if (Sniper.blocked(k, cfg)) continue;
			worth.merge(k, r[0] * Math.min(r[2], 10), Math::max);
		}
		List<String> warm = new ArrayList<>(worth.keySet());
		warm.sort((x, y) -> Double.compare(worth.get(y), worth.get(x)));
		Sniper.warm(warm.subList(0, Math.min(300, warm.size())));
		List<Candidate> out = new ArrayList<>();
		for (Map.Entry<String, Bucket> e : building.entrySet()) {
			Candidate c = candidate(e.getKey(), e.getValue(), ts);
			if (c != null) out.add(c);
		}
		out.sort((x, y) -> Double.compare(rawProfit(y), rawProfit(x)));
		if (out.size() > 80) out = new ArrayList<>(out.subList(0, 80));
		candidates = out;
		scanAt = ts;
		building = new HashMap<>();
		for (int i = 0; i < Math.min(40, out.size()); i++) NefClient.docFor(out.get(i).key(), true);   // warm prices (SkyCofl is rate limited)
	}

	private static Candidate candidate(String bucketKey, Bucket b, long ts) {
		int hash = bucketKey.indexOf('#');
		String key = hash < 0 ? bucketKey : bucketKey.substring(0, hash);
		String variant = hash < 0 ? "" : bucketKey.substring(hash + 1);
		if (b.n < 3) return null;
		List<Double> next = new ArrayList<>();
		for (int i = 1; i < Math.min(b.n, 5); i++) next.add(b.p[i]);
		double ref = Math.min(b.p[1], Market.median(next));
		if (b.p[0] >= ref * 0.97) return null;
		Candidate c = new Candidate(key, variant, b.names[0], b.u[0], b.p[0], ref, b.listed, ts, b.specs[0], b.attrs[0]);
		double raw = rawProfit(c);
		return raw > 0 && raw / c.price() >= 0.02 ? c : null;
	}

	static double rawProfit(Candidate c) {
		return Verdict.netAfterFees(Market.undercut(c.ref())) - c.price();
	}

	// ------------------------------------------------------------------ what you see
	/** Valid flips for your settings and budget, best profit first. */
	static View view(double budget) {
		List<Flip> out = new ArrayList<>();
		int checking = 0;
		double minPct = cfg.minFlipProfitPercent / 100.0;
		for (Candidate c : candidates) {
			if (c.price() > budget) continue;
			if (c.ref() < cfg.minItemValue) continue;                   // cheap items: not worth your time (your setting)
			JsonObject d = NefClient.docFor(c.key(), false);
			List<double[]> week = NefClient.cofl == null ? null : NefClient.cofl.weekClean(c.key(), true);
			if (d == null || week == null) {
				checking++;
				continue;
			}
			Forecasts.Side fc = cfg.forecastFlips && NefClient.data() != null ? NefClient.data().forecast(c.key(), "main") : null;
			Flip f = judge(c, d, week, fc);
			if (f == null) continue;
			if (f.pct() < minPct || f.profit() < cfg.minFlipProfitCoins) continue;
			if (f.liq().perDay() < cfg.minSalesPerDay || f.liq().etaHours() > cfg.maxHoldHours) continue;
			if (Market.hasSevereFlag(d)) continue;
			out.add(f);
		}
		out.sort((x, y) -> Double.compare(y.profit(), x.profit()));
		lastValid = out;
		return new View(out, checking, candidates.size());
	}

	/**
	 * Pure: what you can really resell for, profit after fees, liquidity. Null when it can't be priced.
	 *
	 * resale = the lowest of
	 *   - just under the next identical copies on the AH (asking prices — they may never sell),
	 *   - the item's fair value (median of recent sales, all copies), and
	 *   - just under the CLEARING FLOOR: the median of the cheapest sale in each period of the last 3 days.
	 * The floor is what the cheapest copies actually sold for. Listings of the same item can sit far above it (and
	 * upgraded copies pull the average sale up), so without it a plain copy looked like a 3M flip that never sells.
	 */
	static Flip judge(Candidate c, JsonObject d, List<double[]> week) {
		return judge(c, d, week, null);
	}

	/**
	 * With a forecast (forecasting on): the sales-based part of the resale (fair value, clearing floor) is moved by the
	 * forecast counted at the time it should sell (see {@link Forecasts#counted}). The other listings' asking price
	 * stays a hard cap: you can't sell above copies that are already listed cheaper.
	 */
	static Flip judge(Candidate c, JsonObject d, List<double[]> week, Forecasts.Side forecast) {
		Double fair = Verdict.fair(d);
		if (fair == null || fair <= 0) return null;
		double floor = clearingFloor(week);
		if (!(floor > 0)) return null;
		if (Market.erratic(week).bad()) return null;                 // coin-transfer / manipulated sale history
		Market.Liquidity liq = Market.liquidity(d, fair);
		double fc = forecast == null ? 0 : Forecasts.counted(forecast, liq.etaHours());
		double resale = Math.min(Market.undercut(c.ref()), Math.min(fair, Market.undercut(floor)) * (1 + fc));
		resale = NpcPrices.cap(c.key(), resale);                      // an NPC sells it for less: nobody pays more
		double net = Verdict.netAfterFees(resale);
		double profit = net - c.price();
		return new Flip(c, resale, net, profit, profit / c.price(), liq, floor, fc);
	}

	/** Median of each period's cheapest sale over the last 3 days (falls back to the whole week); 0 when < 3 periods. */
	static double clearingFloor(List<double[]> week) {
		if (week == null || week.isEmpty()) return 0;
		double last = 0;
		for (double[] p : week) last = Math.max(last, p[0]);
		List<Double> recent = new ArrayList<>(), all = new ArrayList<>();
		for (double[] p : week) {
			if (!(p[1] > 0) || p[4] <= 0) continue;
			all.add(p[1]);
			if (p[0] >= last - 3 * 86_400_000.0) recent.add(p[1]);
		}
		List<Double> use = recent.size() >= 4 ? recent : all;
		return use.size() < 3 ? 0 : Market.median(use);
	}

	static List<Flip> last() {
		return lastValid;
	}

	/** Every ~10 s: announce the best new flips (max 3 per scan). */
	static void tick(double budget) {
		if (cfg == null || !cfg.flipFeed || !cfg.flipAlerts || candidates.isEmpty()) return;
		long now = System.currentTimeMillis();
		if (now - checkedAt < 10_000) return;
		checkedAt = now;
		View v = view(budget);
		int shown = 0;
		for (int i = 0; i < v.flips().size() && shown < 3; i++) {
			Flip f = v.flips().get(i);
			if (f.pct() < Math.max(0.10, cfg.minFlipProfitPercent / 100.0)) continue;
			if (!announced.add(f.c().uuid())) continue;
			shown++;
			Alerts.alert(HudFormatter.line("[NEF] FLIP ", 0x55FF55, f.c().name(), HudFormatter.WHITE,
					"  buy " + HudFormatter.fmt(f.c().price()) + " → resell " + HudFormatter.fmt(f.resale()), HudFormatter.GRAY,
					String.format(Locale.ROOT, "  +%s (%.0f%%)", HudFormatter.fmt(f.profit()), f.pct() * 100), 0x55FF55,
					"  sells " + Market.eta(f.liq().etaHours()) + "  → /nef flip " + (i + 1), HudFormatter.AQUA));
		}
		if (announced.size() > 3000) announced.clear();
	}
}

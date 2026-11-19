package dev.notenoughflips;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Fallback auction prices from SkyCofl (sky.coflnet.com, free public SkyBlock price history; "Prices provided by
 * SkyCofl"). Used when our own data has no or too little history for an item (no local server running, item not
 * traded during the website snapshot). Per item:
 *
 *   GET /api/item/price/{tag}/history/week   [{min, max, avg, volume, time}, …]   → 24 h / 7 d avg, high, low, sold
 *   GET /api/item/price/{tag}/bin            {lowest, uuid, secondLowest}          → lowest BIN, 2nd lowest
 *
 * Pets use tag PET_TYPE with ?Rarity=TIER; enchanted books use ENCHANTMENT_NAME_LEVEL (same as our keys).
 * Requests are spaced 400 ms apart (SkyCofl allows 30 per 10 s) and cached for 10 min (BIN: 3 min).
 * Results are built into a doc with the same shape as our own item docs, so the tooltip, verdict and bid preset work.
 */
final class CoflClient {
	private static final String BASE = "https://sky.coflnet.com/api/item/price/";
	private static final long TTL_MS = 10 * 60_000L, BIN_TTL_MS = 3 * 60_000L;

	private record Entry(long at, JsonArray history, long binAt, JsonObject bin, boolean empty) {}

	private final NefConfig cfg;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private final Map<String, Entry> cache = new ConcurrentHashMap<>();
	private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
	private final ScheduledExecutorService queue = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "NEF-SkyCofl");
		t.setDaemon(true);
		return t;
	});
	private volatile long nextSlot = 0;
	private volatile long blockedUntil = 0;

	CoflClient(NefConfig cfg) {
		this.cfg = cfg;
	}

	/** Pets that level to 200 instead of 100. */
	private static final java.util.Set<String> MAX_200 = java.util.Set.of("GOLDEN_DRAGON", "JADE_DRAGON", "ROSE_DRAGON");

	/**
	 * Level range a pet is compared within (SkyCofl's PetLevel filter): max level exactly, then 95–99 %, 90–94 %,
	 * 80–89 %, 70–79 %, 50–69 %, 30–49 %, 10–29 %, 1–9 % of max. Prices barely move until close to max level, so the
	 * ranges get narrow near the top.
	 */
	static String petRange(int level, int max) {
		if (level >= max) return String.valueOf(max);
		int[] cuts = {95, 90, 80, 70, 50, 30, 10};
		int upper = max - 1;
		for (int c : cuts) {
			int lo = (int) Math.ceil(max * c / 100.0);
			if (level >= lo) return lo + "-" + upper;
			upper = lo - 1;
		}
		return "1-" + upper;
	}

	/** "PET_TIGER_LEGENDARY" + level 97 → "PET_TIGER_LEGENDARY@95-99" (same rarity, same level range), or null. */
	static String petLevelKey(String key, int level) {
		if (key == null || !key.startsWith("PET_") || level <= 0 || key.contains("@")) return null;
		int us = key.lastIndexOf('_');
		String type = us > 4 ? key.substring(4, us) : key.substring(4);
		return key + "@" + petRange(level, MAX_200.contains(type) ? 200 : 100);
	}

	/** Full SkyCofl URL for one of our keys + endpoint ("/history/week", "/bin"); pets get ?Rarity= (+ &PetLevel= for "@lo-hi"). */
	static String url(String key, String endpoint) {
		String levels = null;
		int at = key.indexOf('@');
		if (at > 0) {
			levels = key.substring(at + 1);
			key = key.substring(0, at);
		}
		String tag = key, query = "";
		if (key.startsWith("PET_")) {
			int us = key.lastIndexOf('_');
			String tier = key.substring(us + 1);
			for (String t : Valuation.TIERS) {
				if (t.equals(tier)) {
					tag = key.substring(0, us);
					query = "?Rarity=" + tier;
				}
			}
		}
		if (levels != null && levels.matches("[0-9]+(-[0-9]+)?")) query += (query.isEmpty() ? "?" : "&") + "PetLevel=" + levels;
		// no String.format here: encoded ids contain '%' (e.g. %3A), which format() would misread
		return BASE + URLEncoder.encode(tag, StandardCharsets.UTF_8) + endpoint + query;
	}

	private CompletableFuture<JsonElement> get(String url) {
		long now = System.currentTimeMillis();
		if (now < blockedUntil) return CompletableFuture.failedFuture(new IllegalStateException("rate limited"));
		long slot = Math.max(now, nextSlot);
		nextSlot = slot + 700;                              // ≈ 85 requests/min, under SkyCofl's 100/min
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		queue.schedule(() -> {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", "NotEnoughFlips (Fabric mod)").GET().build();
			http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
				if (err != null) { f.completeExceptionally(err); return; }
				if (resp.statusCode() == 429) {
					blockedUntil = System.currentTimeMillis() + 60_000L;
					f.completeExceptionally(new IllegalStateException("429"));
					return;
				}
				if (resp.statusCode() == 404 || resp.statusCode() == 204) { f.complete(null); return; }
				if (resp.statusCode() != 200) { f.completeExceptionally(new IllegalStateException("HTTP " + resp.statusCode())); return; }
				try {
					f.complete(JsonParser.parseString(resp.body()));
				} catch (RuntimeException e) {
					f.completeExceptionally(e);
				}
			});
		}, slot - now, TimeUnit.MILLISECONDS);
		return f;
	}

	/**
	 * SkyCofl-based doc for an auction item, or null while loading / when SkyCofl has nothing.
	 * @param fetch false = only use what's cached (for the many items the yellow-border check looks at)
	 */
	JsonObject doc(String key, boolean fetch) {
		if (cfg == null || !cfg.coflFallback || key == null) return null;
		Entry e = cache.get(key);
		long now = System.currentTimeMillis();
		boolean stale = e == null || now - e.at() > TTL_MS || (e.bin() != null || !e.empty()) && now - e.binAt() > BIN_TTL_MS;
		if (fetch && stale && inFlight.putIfAbsent(key, true) == null) refresh(key, e);
		if (e == null || e.empty()) return null;
		return build(key, e);
	}

	/**
	 * The last 7 days of SkyCofl sales for an auction item: [time, min, max, avg, volume] per period, oldest first.
	 * Null while loading or when SkyCofl has nothing. Used by the price chart beside the AH.
	 */
	List<double[]> week(String key, boolean fetch) {
		doc(key, fetch);
		Entry e = key == null ? null : cache.get(key);
		if (e == null || e.history() == null) return null;
		List<double[]> pts = points(e);
		pts.sort((a, b) -> Double.compare(a[0], b[0]));
		return pts;
	}

	/**
	 * Like week(), but without the periods that look manipulated (see manipulated()), as long as enough normal
	 * periods are left (≥ 8 and at least 40 % of them). Every price check uses this; the chart shows everything.
	 */
	List<double[]> weekClean(String key, boolean fetch) {
		List<double[]> w = week(key, fetch);
		return w == null ? null : clean(w);
	}

	/** The normal periods of a week, or the whole week when too few would be left to judge on. */
	static List<double[]> clean(List<double[]> pts) {
		boolean[] bad = manipulated(pts);
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < pts.size(); i++) if (!bad[i]) out.add(pts.get(i));
		return out.size() >= 8 && out.size() >= 0.4 * pts.size() ? out : pts;
	}

	/**
	 * Periods that look manipulated ([time, min, max, avg, volume] each):
	 *   price  the period's average is ≥ 40 % above or below the week's typical level AND ≥ 4 robust deviations away
	 *          (median ± 4 × 1.4826 × MAD of the period averages), so normal swings of a volatile item stay in
	 *   burst  far more sales than usual (≥ 5 × the typical period) while the price is ≥ 20 % above typical: a buyout
	 */
	static boolean[] manipulated(List<double[]> pts) {
		boolean[] bad = new boolean[pts.size()];
		if (pts.size() < 6) return bad;
		List<Double> avgs = new ArrayList<>(), vols = new ArrayList<>();
		for (double[] q : pts) { avgs.add(q[3]); if (q[4] > 0) vols.add(q[4]); }
		double med = Market.median(new ArrayList<>(avgs));
		List<Double> dev = new ArrayList<>();
		for (double a : avgs) dev.add(Math.abs(a - med));
		double sigma = 1.4826 * Market.median(dev);
		double volMed = vols.isEmpty() ? 0 : Market.median(vols);
		for (int i = 0; i < pts.size(); i++) {
			double a = pts.get(i)[3], v = pts.get(i)[4];
			boolean price = med > 0 && (a >= med * 1.4 || a <= med * 0.6) && Math.abs(a - med) >= 4 * Math.max(sigma, med * 0.02);
			boolean burst = volMed > 0 && v >= 5 * volMed && a >= med * 1.2;
			bad[i] = price || burst;
		}
		return bad;
	}

	private static List<double[]> points(Entry e) {
		List<double[]> pts = new ArrayList<>();            // [time, min, max, avg, volume]
		if (e.history() == null) return pts;
		for (JsonElement el : e.history()) {
			if (!el.isJsonObject()) continue;
			JsonObject o = el.getAsJsonObject();
			Double avg = HudFormatter.num(o, "avg");
			if (avg == null || avg <= 0) continue;
			Double mn = HudFormatter.num(o, "min"), mx = HudFormatter.num(o, "max"), vol = HudFormatter.num(o, "volume");
			pts.add(new double[]{time(o), mn == null ? avg : mn, mx == null ? avg : mx, avg, vol == null ? 0 : vol});
		}
		return pts;
	}

	private final java.util.Map<String, Long> failedAt = new java.util.concurrent.ConcurrentHashMap<>();

	/** The last history request for this item failed (SkyCofl busy / rate limited); it is retried after a minute. */
	boolean failed(String key) {
		Long t = key == null ? null : failedAt.get(key);
		return t != null && System.currentTimeMillis() - t < 120_000L;
	}

	/** Any other SkyCofl GET through the same rate limiter (so all requests together stay under 100/min). */
	CompletableFuture<JsonElement> raw(String url) {
		return get(url);
	}

	boolean loading(String key) {
		return inFlight.containsKey(key) && !cache.containsKey(key);
	}

	private void refresh(String key, Entry old) {
		long now = System.currentTimeMillis();
		boolean needHistory = old == null || now - old.at() > TTL_MS;
		CompletableFuture<JsonElement> hist = needHistory ? get(url(key, "/history/week"))
				: CompletableFuture.completedFuture(old.history());
		boolean[] histFailed = {false};
		hist.handle((h, err) -> {
					if (err == null) return h;
					histFailed[0] = true;                       // busy / rate limited: keep what we had, retry in a minute
					return old == null ? null : old.history();
				})
				.thenCompose(h -> get(url(key, "/bin")).handle((b, err) -> {
					JsonArray ha = h != null && h.isJsonArray() ? h.getAsJsonArray() : null;
					JsonObject bo = b != null && b.isJsonObject() ? b.getAsJsonObject() : (old == null ? null : old.bin());
					boolean empty = (ha == null || ha.isEmpty()) && (bo == null || HudFormatter.num(bo, "lowest") == null
							|| HudFormatter.num(bo, "lowest") <= 0);
					long at = !needHistory ? old.at()
							: histFailed[0] ? (old != null ? old.at() : System.currentTimeMillis() - TTL_MS + 60_000L)
							: System.currentTimeMillis();
					if (histFailed[0]) failedAt.put(key, System.currentTimeMillis());
					else failedAt.remove(key);
					cache.put(key, new Entry(at, ha, System.currentTimeMillis(), bo, empty && !histFailed[0]));
					return null;
				}))
				.whenComplete((v, err) -> inFlight.remove(key));
	}

	private static long time(JsonObject o) {
		if (!o.has("time") || o.get("time").isJsonNull()) return 0;
		String t = o.get("time").getAsString();
		try {
			return OffsetDateTime.parse(t).toInstant().toEpochMilli();
		} catch (RuntimeException ignored) {
			// no offset: SkyCofl times are UTC
		}
		try {
			return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli();
		} catch (RuntimeException ignored) {
			return Instant.now().toEpochMilli();
		}
	}

	/** Same shape as our published item docs (summary / stats / market), marked source = SkyCofl. */
	private static JsonObject build(String key, Entry e) {
		long now = System.currentTimeMillis();
		List<double[]> raw = points(e);
		List<double[]> pts = clean(raw);                               // prices from the normal periods only
		int dropped = raw.size() - pts.size();
		JsonObject d24 = window(pts, now - 86_400_000L), d7 = window(pts, 0);
		JsonObject r24 = window(raw, now - 86_400_000L), r7 = window(raw, 0);   // what everyone sees, for the warnings
		Double lowest = HudFormatter.num(e.bin(), "lowest"), second = HudFormatter.num(e.bin(), "secondLowest");
		if (lowest != null && lowest <= 0) lowest = null;
		if (second != null && second <= 0) second = null;

		JsonObject summary = new JsonObject();
		if (lowest != null) summary.addProperty("lbin", lowest);
		if (d24 != null) {
			summary.addProperty("sales", d24.get("n").getAsDouble());
			summary.addProperty("avg", d24.get("avg").getAsDouble());
			summary.addProperty("hi", d24.get("hi").getAsDouble());
			summary.addProperty("lo", d24.get("lo").getAsDouble());
		} else {
			summary.addProperty("sales", 0);
		}
		JsonObject stats = new JsonObject();
		if (d24 != null) stats.add("24h", wrap(d24));
		if (d7 != null) stats.add("7d", wrap(d7));

		// fair value: median of the 24 h median sale, the 7 d median sale and the lowest BIN. Medians of the
		// per-period averages, so one 75M sale of a maxed copy can't drag a 400k item's fair value up.
		List<Double> f = new ArrayList<>();
		if (d24 != null) f.add(d24.get("med").getAsDouble());
		if (d7 != null) f.add(d7.get("med").getAsDouble());
		boolean inflated = lowest != null && d7 != null && lowest > d7.get("med").getAsDouble() * 1.6;
		if (lowest != null && !(dropped > 0 && inflated)) f.add(lowest);   // pumped listings don't set the fair value
		JsonObject market = new JsonObject();
		if (!f.isEmpty()) {
			double fair = Market.median(f);
			market.addProperty("fair", fair);
			// after buying the cheapest BIN you compete with the 2nd cheapest
			market.addProperty("resale", second != null ? Math.min(fair, Market.undercut(second)) : fair * 0.97);
		}
		if (d24 != null) market.addProperty("sold_median_72h", d24.get("med").getAsDouble());
		if (d7 != null) market.addProperty("avg_7d", d7.get("med").getAsDouble());
		JsonArray flags = new JsonArray();
		if (r24 != null && r7 != null && r7.get("med").getAsDouble() > 0) {
			double jump = r24.get("med").getAsDouble() / r7.get("med").getAsDouble();
			if (jump >= 1.4) flags.add(flag("spike", 2, String.format(java.util.Locale.ROOT,
					"price jumped %.0f%% vs its 7-day level: possible manipulation, don't buy to flip", (jump - 1) * 100)));
			else if (jump <= 0.65) flags.add(flag("crash", 1, String.format(java.util.Locale.ROOT,
					"price fell %.0f%% vs its 7-day level: may keep falling", (1 - jump) * 100)));
		}
		if (r24 != null && r7 != null) {
			// buyout: far more sales than usual in the last day while the price climbs
			double n24 = r24.get("n").getAsDouble(), nBefore = (r7.get("n").getAsDouble() - n24) / 6.0;
			double jump = r7.get("med").getAsDouble() > 0 ? r24.get("med").getAsDouble() / r7.get("med").getAsDouble() : 1;
			if (nBefore > 0 && n24 >= 3 * nBefore && n24 >= 6 && jump >= 1.2)
				flags.add(flag("buyout", 2, String.format(java.util.Locale.ROOT,
						"%.0f sales in 24h vs ~%.0f/day before while the price rose %.0f%%: looks like a buyout", n24, nBefore, (jump - 1) * 100)));
		}
		if (lowest != null && d7 != null && lowest > d7.get("med").getAsDouble() * 1.6)
			flags.add(flag("lbin_high", 2, "cheapest BIN is far above recent sales: listings look inflated"));
		if (dropped > 0) {
			// manipulated periods were left out of every price above: the warnings stay, but they no longer block the
			// verdict (it is now judged on the normal sales only)
			JsonArray soft = new JsonArray();
			for (var fe : flags) {
				JsonObject fo = fe.getAsJsonObject().deepCopy();
				if (fo.get("level").getAsInt() >= 2) {
					fo.addProperty("level", 1);
					fo.addProperty("text", fo.get("text").getAsString().replace(": possible manipulation, don't buy to flip", "")
							+ " · prices here use only the normal sales");
				}
				soft.add(fo);
			}
			flags = soft;
			flags.add(flag("cleaned", 1, String.format(java.util.Locale.ROOT,
					"%d of %d periods looked manipulated and were left out of the prices", dropped, raw.size())));
			JsonObject cl = new JsonObject();
			cl.addProperty("dropped", dropped);
			cl.addProperty("of", raw.size());
			market.add("clean", cl);
		}
		if (!flags.isEmpty()) market.add("flags", flags);
		JsonObject listed = new JsonObject();
		if (lowest != null) listed.addProperty("lowest", lowest);
		if (second != null) listed.addProperty("second", second);
		market.add("listed", listed);

		JsonObject doc = new JsonObject();
		doc.addProperty("k", key);
		doc.addProperty("kind", "ah");
		doc.addProperty("source", "SkyCofl");
		doc.add("summary", summary);
		doc.add("stats", stats);
		doc.add("market", market);
		return doc;
	}

	private static JsonObject flag(String code, int level, String text) {
		JsonObject o = new JsonObject();
		o.addProperty("code", code);
		o.addProperty("level", level);
		o.addProperty("text", text);
		return o;
	}

	private static JsonObject wrap(JsonObject w) {
		JsonObject o = new JsonObject();
		o.add("sold", w);
		return o;
	}

	/** Volume-weighted avg, median of the period averages, highest max, lowest min and total sold over points since `from`. */
	private static JsonObject window(List<double[]> pts, long from) {
		double sumPv = 0, sumV = 0, sumAvg = 0, hi = 0, lo = Double.MAX_VALUE, since = Double.MAX_VALUE;
		int n = 0;
		List<Double> avgs = new ArrayList<>();
		for (double[] p : pts) {
			if (p[0] < from) continue;
			n++;
			sumAvg += p[3];
			avgs.add(p[3]);
			sumPv += p[3] * p[4];
			sumV += p[4];
			hi = Math.max(hi, p[2]);
			lo = Math.min(lo, p[1]);
			since = Math.min(since, p[0]);
		}
		if (n == 0) return null;
		JsonObject o = new JsonObject();
		o.addProperty("avg", sumV > 0 ? sumPv / sumV : sumAvg / n);
		o.addProperty("med", Market.median(avgs));                 // robust: one outlier period barely moves it
		o.addProperty("hi", hi);
		o.addProperty("lo", lo);
		o.addProperty("n", sumV);
		o.addProperty("since", since);
		return o;
	}

	/** True when our own doc has too little auction history to be useful (e.g. only the website snapshot). */
	static boolean thin(JsonObject d) {
		if (d == null) return true;
		if (!"ah".equals(HudFormatter.str(d, "kind"))) return false;
		Double sales = HudFormatter.num(HudFormatter.obj(d, "summary"), "sales");
		JsonObject w7 = HudFormatter.obj(d, "stats", "7d", "sold");
		Double n7 = HudFormatter.num(w7, "n");
		return (sales == null || sales < 3) && (n7 == null || n7 < 10);
	}
}

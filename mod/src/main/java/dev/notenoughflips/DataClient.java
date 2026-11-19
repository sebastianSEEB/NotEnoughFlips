package dev.notenoughflips;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Where the numbers come from, in order:
 *   1. local server     GET {localServer}/itemdata/KEY        (optional, off by default)
 *   2. SkyCofl          price history and lowest BINs
 *   3. Hypixel API      GET api.hypixel.net/v2/skyblock/bazaar (live bazaar buy/sell, refreshed every minute)
 * Nothing ever blocks the game: callers get whatever is cached and a background refresh starts.
 */
final class DataClient {
	record Doc(long fetchedAt, JsonObject json, String source) {}
	/**
	 * Bazaar quick_status. buy = instant-buy price (cheapest sell offer), sell = instant-sell price (top buy order);
	 * buyWeek / sellWeek = units instantly bought / sold in the last 7 days; offers / orders = how many sell offers /
	 * buy orders are open (competition).
	 */
	/**
	 * @param offers / orders          all sell offers / buy orders on the book
	 * @param nearOffers / nearOrders  the ones within ~0.5 % of the best price: who actually fights you for the top
	 *                                 (−1 = unknown)
	 */
	record Live(double buy, double sell, long ts, double buyWeek, double sellWeek, int offers, int orders, int nearOffers, int nearOrders) {
		Live(double buy, double sell, long ts) {
			this(buy, sell, ts, 0, 0, 0, 0, -1, -1);
		}

		Live(double buy, double sell, long ts, double buyWeek, double sellWeek, int offers, int orders) {
			this(buy, sell, ts, buyWeek, sellWeek, offers, orders, -1, -1);
		}
	}

	private final NefConfig cfg;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private final Map<String, Doc> docs = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private volatile long localDownUntil = 0;
	private volatile long remoteDownUntil = 0;

	private volatile JsonObject components;
	private volatile long componentsFetchedAt = 0;
	private volatile boolean componentsLoading = false;

	private volatile Map<String, Live> bazaar = Map.of();
	private volatile long bazaarFetchedAt = 0;
	private volatile boolean bazaarLoading = false;

	DataClient(NefConfig cfg) {
		this.cfg = cfg;
	}

	/** Cached document for the key (may be null while loading). Starts a refresh if stale. */
	Doc doc(String key) {
		long now = System.currentTimeMillis();
		Doc d = docs.get(key);
		long maxAge = 1000L * (d != null && "local".equals(d.source) ? cfg.localRefreshSeconds : cfg.publishedRefreshSeconds);
		if ((d == null || now - d.fetchedAt > maxAge) && inFlight.add(key)) {
			fetchDoc(key).whenComplete((res, err) -> {
				if (res != null) docs.put(key, res);
				else if (d == null) docs.put(key, new Doc(System.currentTimeMillis(), null, "none"));
				if (docs.size() > 3000) docs.clear();
				inFlight.remove(key);
			});
		}
		return d;
	}

	/** Same as doc() but waits for the network (for the chat command, never on the render thread). */
	CompletableFuture<Doc> docAsync(String key) {
		return fetchDoc(key).thenApply(res -> {
			if (res != null) docs.put(key, res);
			return res;
		});
	}

	/** Fresh lowest BIN for a few items from the optional local server (re-checked every 30 s). Null if it isn't running. */
	CompletableFuture<JsonObject> localLbin(java.util.List<String> keys) {
		if (cfg.localServer == null || cfg.localServer.isBlank() || keys.isEmpty())
			return CompletableFuture.completedFuture(null);
		String q = URLEncoder.encode(String.join(",", keys), StandardCharsets.UTF_8);
		return getJson(cfg.localServer + "/lbin?keys=" + q, 3).handle((j, e) -> e != null ? null : j);
	}

	/** Underpriced BINs from the optional local server's last full AH scan. Null if it isn't running. */
	CompletableFuture<com.google.gson.JsonArray> localDeals() {
		if (cfg.localServer == null || cfg.localServer.isBlank()) return CompletableFuture.completedFuture(null);
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.localServer + "/deals")).timeout(Duration.ofSeconds(3)).GET().build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((resp, e) -> {
			if (e != null || resp.statusCode() != 200) return null;
			JsonElement el = JsonParser.parseString(resp.body());
			return el.isJsonArray() ? el.getAsJsonArray() : null;
		});
	}

	/**
	 * Upgrade prices + valuation constants (market/valuation.py components_doc): local server /components,
	 * else {dataUrl}/components.json. Refreshed every 10 min; null until the first fetch completes.
	 */
	JsonObject components() {
		long now = System.currentTimeMillis();
		if (now - componentsFetchedAt > (components == null ? 30_000L : 600_000L) && !componentsLoading) {
			componentsLoading = true;
			CompletableFuture<JsonObject> local = (cfg.localServer == null || cfg.localServer.isBlank())
					? CompletableFuture.completedFuture(null)
					: getJson(cfg.localServer + "/components", 3).handle((j, e) -> e != null ? null : j);
			local.thenCompose(j -> j != null || cfg.dataUrl == null || cfg.dataUrl.isBlank() ? CompletableFuture.completedFuture(j)
							: getJson(cfg.dataUrl + "/components.json", 15).handle((r, e) -> e != null ? null : r))
					.whenComplete((j, e) -> {
						if (j != null) components = j;
						componentsFetchedAt = System.currentTimeMillis();
						componentsLoading = false;
					});
		}
		return components != null ? components : LocalComponents.get(this);      // built on your PC
	}

	/** Wallet + potential FCF report from an optional local server (/wallet); never from a website. */
	CompletableFuture<Doc> walletReport() {
		CompletableFuture<JsonObject> local = (cfg.localServer == null || cfg.localServer.isBlank())
				? CompletableFuture.completedFuture(null)
				: getJson(cfg.localServer + "/wallet", 3).handle((j, e) -> e != null || j == null || j.entrySet().isEmpty() ? null : j);
		// your positions only ever come from your own game (the mod's AH scan) or your own local server, never a website
		return local.thenApply(j -> j == null ? null : new Doc(System.currentTimeMillis(), j, "your PC"));
	}

	private volatile JsonObject bazaarWatch;
	private volatile long bazaarWatchAt;

	private final Map<String, com.google.gson.JsonArray> bzHist = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * {"items": {ID: [[ts, buy, sell], …]}} for your watched bazaar products: 7 days of hourly prices from the public data
	 * site ({dataUrl}/bz/ID.json, one small file per product). Refreshed every 15 min.
	 */
	JsonObject bazaarHistory() {
		long now = System.currentTimeMillis();
		if (now - bazaarWatchAt > 900_000L && cfg.dataUrl != null && !cfg.dataUrl.isBlank()) {
			bazaarWatchAt = now;
			for (String id : cfg.bazaarWatch) {
				if (!id.matches("[A-Z0-9_:]+")) continue;
				getJson(cfg.dataUrl + "/bz/" + id + ".json", 10).whenComplete((j, e) -> {
					if (j != null && j.has("points") && j.get("points").isJsonArray()) bzHist.put(id, j.getAsJsonArray("points"));
				});
			}
		}
		JsonObject items = new JsonObject();
		bzHist.forEach(items::add);
		JsonObject out = new JsonObject();
		out.add("items", items);
		bazaarWatch = out;
		return out;
	}

	boolean isLoading(String key) {
		return inFlight.contains(key);
	}

	private CompletableFuture<Doc> fetchDoc(String key) {
		String enc = URLEncoder.encode(key, StandardCharsets.UTF_8);
		long now = System.currentTimeMillis();
		CompletableFuture<Doc> local = (cfg.localServer == null || cfg.localServer.isBlank() || now < localDownUntil)
				? CompletableFuture.completedFuture(null)
				: getJson(cfg.localServer + "/itemdata/" + enc, 3).handle((j, e) -> {
					if (e != null) { localDownUntil = System.currentTimeMillis() + 30_000; return null; }
					return j == null ? null : new Doc(System.currentTimeMillis(), j, "local");
				});
		// no local server: item prices come from SkyCofl (CoflClient) and the live Hypixel bazaar
		return local;
	}

	/** Completes with the parsed object, null on 404, exceptionally on network errors. */
	/** GET a public JSON resource (Hypixel resources, data site). */
	CompletableFuture<JsonObject> getJsonPublic(String url, int timeoutSeconds) {
		return getJson(url, timeoutSeconds).handle((j, e) -> e != null ? null : j);
	}

	private final Map<String, double[]> bzWeek = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, Long> bzWeekAt = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * A bazaar product's normal level over the last 7 days, from the data site's hourly history: {median instant-buy,
	 * median instant-sell, hours}. Null until loaded (fetched on first use, then every 30 min).
	 */
	double[] bazaarNormal(String id) {
		if (id == null || !id.matches("[A-Z0-9_:]+")) return null;
		List<double[]> wk = bzSeries.get(id);                          // SkyCofl's week (2 h bars), loaded on your PC
		if (wk == null) bazaarSlide(id);                               // queue it
		if (wk != null && wk.size() >= 12) {
			List<Double> b = new ArrayList<>(), s = new ArrayList<>();
			for (double[] q : wk) { if (q[1] > 0) b.add(q[1]); if (q[2] > 0) s.add(q[2]); }
			if (b.size() >= 12 && s.size() >= 12) return new double[]{Market.median(b), Market.median(s), Math.min(b.size(), s.size())};
		}
		if (cfg.dataUrl == null || cfg.dataUrl.isBlank()) return null;
		long now = System.currentTimeMillis();
		Long at = bzWeekAt.get(id);
		if (at == null || now - at > 1_800_000L) {
			bzWeekAt.put(id, now);
			getJson(cfg.dataUrl + "/bz/" + id + ".json", 10).whenComplete((j, e) -> {
				if (j == null || !j.has("points") || !j.get("points").isJsonArray()) return;
				java.util.List<Double> b = new java.util.ArrayList<>(), s = new java.util.ArrayList<>();
				for (JsonElement el : j.getAsJsonArray("points")) {
					var a = el.getAsJsonArray();
					if (a.size() < 3) continue;
					if (a.get(1).getAsDouble() > 0) b.add(a.get(1).getAsDouble());
					if (a.get(2).getAsDouble() > 0) s.add(a.get(2).getAsDouble());
				}
				if (b.size() >= 12 && s.size() >= 12) bzWeek.put(id, new double[]{Market.median(b), Market.median(s), Math.min(b.size(), s.size())});
			});
		}
		return bzWeek.get(id);
	}

	/** bazaarNormal() without fetching (null when not loaded yet). */
	double[] bazaarNormalCached(String id) {
		if (id == null) return null;
		List<double[]> wk = bzSeries.get(id);
		if (wk != null && wk.size() >= 12) {
			List<Double> b = new ArrayList<>(), s = new ArrayList<>();
			for (double[] q : wk) { if (q[1] > 0) b.add(q[1]); if (q[2] > 0) s.add(q[2]); }
			if (b.size() >= 12 && s.size() >= 12) return new double[]{Market.median(b), Market.median(s), Math.min(b.size(), s.size())};
		}
		return bzWeek.get(id);
	}

	private CompletableFuture<JsonObject> getJson(String url, int timeoutSeconds) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
				.header("User-Agent", "NotEnoughFlips/1.0").GET().build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
			if (resp.statusCode() == 404) return null;
			if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
			JsonElement el = JsonParser.parseString(resp.body());
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		});
	}

	/** Live bazaar quick_status for a product (null if unknown / not loaded yet). */
	Live live(String product) {
		if (!cfg.useHypixelApi) return null;
		long now = System.currentTimeMillis();
		if (now - bazaarFetchedAt > cfg.liveBazaarRefreshSeconds * 1000L && !bazaarLoading) {
			bazaarLoading = true;
			HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.hypixelBazaarUrl)).timeout(Duration.ofSeconds(15))
					.header("User-Agent", "NotEnoughFlips/1.0").header("Accept-Encoding", "gzip").GET().build();
			http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).whenComplete((resp, err) -> {
				try {
					if (err == null && resp.statusCode() == 200) {
						boolean gz = resp.headers().firstValue("Content-Encoding").map(v -> v.contains("gzip")).orElse(false);
						try (InputStream in = gz ? new GZIPInputStream(resp.body()) : resp.body()) {
							bazaar = parseBazaar(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
						}
					}
				} catch (Exception ignored) {
					// keep the previous snapshot
				} finally {
					bazaarFetchedAt = System.currentTimeMillis();
					bazaarLoading = false;
				}
			});
		}
		return bazaar.get(product);
	}

	private static double d(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
	}

	/** When the bazaar snapshot was last fetched (the live flip list re-ranks on every new one). */
	long bazaarAt() {
		return bazaarFetchedAt;
	}

	private final Map<String, double[]> slides = new java.util.concurrent.ConcurrentHashMap<>();   // id → {per hour, fetched at}
	private final java.util.Deque<String> slideQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();
	private volatile long slideSentAt;

	/**
	 * 7-day trend of a product's instant-buy price, as a fraction per hour (regression of log price on time over
	 * SkyCofl's week history), or NaN while unknown. Fetched lazily, one product per 5 s through the same SkyCofl
	 * rate limiter as everything else (sending them separately caused 429s that blanked the price charts), cached 30 min.
	 */
	double bazaarSlide(String id) {
		double[] v = slides.get(id);
		long now = System.currentTimeMillis();
		if ((v == null || now - (long) v[1] > 1_800_000L) && !slideQueue.contains(id)) slideQueue.add(id);
		CoflClient cc = NefClient.cofl;
		if (cc != null && now - slideSentAt > 5000 && !slideQueue.isEmpty()) {   // ≤ 12/min, through SkyCofl's shared limiter
			String next = slideQueue.poll();
			slideSentAt = now;
			cc.raw("https://sky.coflnet.com/api/bazaar/" + next + "/history/week").whenComplete((arr, e) -> {
				if (e != null) return;                               // busy: asked again on the next re-rank
				double slope = Double.NaN;
				if (arr != null && arr.isJsonArray()) {
					slope = slope(arr.getAsJsonArray());
					List<double[]> series = series(arr.getAsJsonArray());
					if (series.size() >= 12) bzSeries.put(next, series);
				}
				slides.put(next, new double[]{slope, System.currentTimeMillis()});
			});
		}
		return v == null ? Double.NaN : v[0];
	}

	private final Map<String, List<double[]>> bzSeries = new java.util.concurrent.ConcurrentHashMap<>();

	/** A product's SkyCofl week as {time ms, instant-buy, instant-sell} (2 h bars), or null until loaded (bazaarSlide queues it). */
	List<double[]> bazaarWeek(String id) {
		return id == null ? null : bzSeries.get(id);
	}

	/** Pure: SkyCofl bazaar history points → {time ms, buy, sell}, oldest first. */
	static List<double[]> series(com.google.gson.JsonArray arr) {
		List<double[]> out = new ArrayList<>();
		for (JsonElement e : arr) {
			try {
				JsonObject o = e.getAsJsonObject();
				double t = java.time.LocalDateTime.parse(o.get("timestamp").getAsString().replace("Z", ""))
						.toEpochSecond(java.time.ZoneOffset.UTC) * 1000.0;
				double b = o.has("buy") && !o.get("buy").isJsonNull() ? o.get("buy").getAsDouble() : 0;
				double s = o.has("sell") && !o.get("sell").isJsonNull() ? o.get("sell").getAsDouble() : 0;
				if (b > 0 || s > 0) out.add(new double[]{t, b, s});
			} catch (RuntimeException ignored) {
				// skip
			}
		}
		out.sort((a, c) -> Double.compare(a[0], c[0]));
		return out;
	}

	/** Pure: least-squares slope of ln(buy) per hour over SkyCofl history points ({timestamp, buy, …}). */
	static double slope(com.google.gson.JsonArray arr) {
		List<double[]> pts = new ArrayList<>();
		for (JsonElement e : arr) {
			try {
				JsonObject o = e.getAsJsonObject();
				double b = o.get("buy").getAsDouble();
				if (b <= 0) continue;
				double t = java.time.LocalDateTime.parse(o.get("timestamp").getAsString().replace("Z", ""))
						.toEpochSecond(java.time.ZoneOffset.UTC) / 3600.0;
				pts.add(new double[]{t, Math.log(b)});
			} catch (RuntimeException ignored) {
				// skip
			}
		}
		if (pts.size() < 12) return Double.NaN;
		double mx = 0, my = 0;
		for (double[] q : pts) { mx += q[0]; my += q[1]; }
		mx /= pts.size();
		my /= pts.size();
		double sxy = 0, sxx = 0;
		for (double[] q : pts) { sxy += (q[0] - mx) * (q[1] - my); sxx += (q[0] - mx) * (q[0] - mx); }
		return sxx > 0 ? sxy / sxx : Double.NaN;
	}

	/** Every bazaar product (refreshes like live()). Empty until the first fetch. */
	Map<String, Live> bazaarAll() {
		live("ENCHANTED_DIAMOND");          // triggers the refresh when stale
		return bazaar;
	}

	private volatile Map<String, Map<String, Forecasts.Side>> forecasts = Map.of();
	private volatile long forecastsAt, forecastsGenerated;
	private volatile boolean forecastsLoading;

	/**
	 * Every item's 1h/6h/24h forecast (see {@link Forecasts}): local server /forecasts, else {dataUrl}/forecasts.json.
	 * Refreshed every 10 min, so new items appear as hourly history builds up. Empty until loaded.
	 */
	Map<String, Map<String, Forecasts.Side>> forecasts() {
		long now = System.currentTimeMillis();
		if (!forecastsLoading && now - forecastsAt > (forecasts.isEmpty() ? 60_000L : 600_000L)) {
			forecastsLoading = true;
			forecastsAt = now;
			CompletableFuture<JsonObject> local = (cfg.localServer == null || cfg.localServer.isBlank())
					? CompletableFuture.completedFuture(null)
					: getJson(cfg.localServer + "/forecasts", 5).handle((j, e) -> e != null || j == null || !j.has("items") ? null : j);
			local.thenCompose(j -> j != null || cfg.dataUrl == null || cfg.dataUrl.isBlank() ? CompletableFuture.completedFuture(j)
							: getJson(cfg.dataUrl + "/forecasts.json", 20).handle((r, e) -> e != null ? null : r))
					.whenComplete((j, e) -> {
						try {
							if (j != null) {
								Map<String, Map<String, Forecasts.Side>> m = Forecasts.parse(j);
								if (!m.isEmpty()) forecasts = m;
								forecastsGenerated = j.has("generated") ? j.get("generated").getAsLong() : 0;
							}
						} catch (RuntimeException ignored) {
							// keep the old ones
						} finally {
							forecastsLoading = false;
						}
					});
		}
		return forecasts;
	}

	/** One series' forecast ("buy"/"sell" for bazaar, "main" for AH), or null. */
	Forecasts.Side forecast(String key, String side) {
		Map<String, Forecasts.Side> m = key == null ? null : forecasts().get(key);
		Forecasts.Side f = m == null ? null : m.get(side);
		if (f == null && "main".equals(side)) f = LocalForecast.forItem(key);   // AH items: made on your PC
		if (f == null && ("buy".equals(side) || "sell".equals(side))) f = LocalForecast.forBazaar(key, side, this);   // bazaar: also on your PC
		return f;
	}

	/** When the loaded forecasts were made (0 = none). */
	long forecastsGenerated() {
		return forecastsGenerated;
	}

	private volatile JsonObject signals;
	private volatile long signalsAt;
	private volatile boolean signalsLoading;

	/**
	 * Bazaar misprices, correlations and suggestions (market/bzsignals.py): an optional local server's /signals when it has
	 * 24 h+ of data, else the GitHub job's {dataUrl}/bz_signals.json (SkyCofl 7-day history, every 2 h). Refreshed
	 * every 10 min; null until loaded.
	 */
	JsonObject bzSignals() {
		long now = System.currentTimeMillis();
		if (!signalsLoading && now - signalsAt > (signals == null ? 60_000L : 600_000L)) {
			signalsLoading = true;
			signalsAt = now;
			CompletableFuture<JsonObject> local = (cfg.localServer == null || cfg.localServer.isBlank())
					? CompletableFuture.completedFuture(null)
					: getJson(cfg.localServer + "/signals", 5).handle((j, e) -> e != null || j == null || !j.has("signals") ? null : j);
			local.thenCompose(j -> j != null || cfg.dataUrl == null || cfg.dataUrl.isBlank() ? CompletableFuture.completedFuture(j)
							: getJson(cfg.dataUrl + "/bz_signals.json", 20).handle((r, e) -> e != null ? null : r))
					.whenComplete((j, e) -> {
						if (j != null && j.has("signals")) signals = j;
						signalsLoading = false;
					});
		}
		return signals;
	}

	private volatile JsonObject craft;
	private volatile long craftAt;

	/** Craft flips published every 3 h by the GitHub job (tools/craft_flips.py). Null until loaded. */
	JsonObject craftFlips() {
		long now = System.currentTimeMillis();
		if (now - craftAt > 600_000L && cfg.dataUrl != null && !cfg.dataUrl.isBlank()) {
			craftAt = now;
			getJson(cfg.dataUrl + "/craft_flips.json", 15).whenComplete((j, e) -> {
				if (j != null) craft = j;
			});
		}
		return craft;
	}

	private volatile Map<String, String> itemNames;
	private volatile boolean namesLoading;
	private volatile long namesAt;

	/**
	 * Display name (lower case, no colour codes) → SkyBlock id, from the public Hypixel items resource. Used to
	 * recognise bazaar products in the Bazaar menus and your Bazaar orders. Null until loaded (fetched once).
	 */
	Map<String, String> itemNames() {
		if (itemNames == null && !namesLoading && System.currentTimeMillis() - namesAt > 300_000L) {
			namesLoading = true;
			namesAt = System.currentTimeMillis();
			getJson("https://api.hypixel.net/v2/resources/skyblock/items", 30).whenComplete((j, e) -> {
				try {
					if (j != null && j.has("items")) {
						Map<String, String> m = new ConcurrentHashMap<>();
						for (JsonElement el : j.getAsJsonArray("items")) {
							JsonObject o = el.getAsJsonObject();
							if (!o.has("id") || !o.has("name")) continue;
							String name = o.get("name").getAsString().replaceAll("§.", "").trim().toLowerCase(java.util.Locale.ROOT);
							m.putIfAbsent(name, o.get("id").getAsString());
						}
						itemNames = m;
					}
				} catch (RuntimeException ignored) {
					// try again later
				} finally {
					namesLoading = false;
				}
			});
		}
		return itemNames;
	}

	private static Map<String, Live> parseBazaar(JsonObject root) {
		long ts = root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : System.currentTimeMillis();
		JsonObject products = root.getAsJsonObject("products");
		Map<String, Live> out = new ConcurrentHashMap<>();
		for (Map.Entry<String, JsonElement> e : products.entrySet()) {
			JsonObject q = e.getValue().getAsJsonObject().getAsJsonObject("quick_status");
			if (q == null) continue;
			JsonObject pr = e.getValue().getAsJsonObject();
			out.put(e.getKey(), new Live(q.get("buyPrice").getAsDouble(), q.get("sellPrice").getAsDouble(), ts,
					d(q, "buyMovingWeek"), d(q, "sellMovingWeek"), (int) d(q, "buyOrders"), (int) d(q, "sellOrders"),
					near(pr.get("buy_summary"), false), near(pr.get("sell_summary"), true)));
		}
		return out;
	}

	/**
	 * Pure: how many orders sit within 0.5 % (at least 0.2 coins) of the best price in an order-book summary.
	 * buy_summary = sell offers (cheapest first), sell_summary = buy orders (highest first). −1 when missing.
	 */
	static int near(JsonElement summary, boolean highestFirst) {
		if (summary == null || !summary.isJsonArray() || summary.getAsJsonArray().isEmpty()) return -1;
		com.google.gson.JsonArray a = summary.getAsJsonArray();
		double best = a.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
		double band = Math.max(best * 0.005, 0.2);
		int n = 0;
		for (JsonElement el : a) {
			JsonObject o = el.getAsJsonObject();
			double p = o.get("pricePerUnit").getAsDouble();
			if (highestFirst ? p < best - band : p > best + band) break;
			n += o.has("orders") ? o.get("orders").getAsInt() : 1;
		}
		return n;
	}
}

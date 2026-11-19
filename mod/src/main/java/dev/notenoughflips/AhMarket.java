package dev.notenoughflips;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * How many coins change hands on the Auction House per day, by category (Trade Desk → Bazaar → Market size).
 *
 * Hypixel's /skyblock/auctions_ended lists every auction that sold in the last ~60 s (price, BIN or not, the item).
 * It is read every 30 s while you play (duplicates dropped), summed per hour and kept for 48 h in
 * config/nef-ah-market.json. "Per day" = the last 24 h when that much was observed; otherwise the observed rate
 * scaled to a day, with how long it was measured. Categories come from the full AH scan (Hypixel's own category per
 * item: weapon, armor, accessories, consumables, blocks, misc).
 */
final class AhMarket {
	/** One hour of sales. */
	static final class Hour {
		double bin, auction;
		int sales;
		int polledMinutes;
		Map<String, Double> byCategory = new HashMap<>();
	}

	record Day(double bin, double auction, int sales, Map<String, Double> byCategory, double hoursMeasured, boolean scaled) {
		double total() {
			return bin + auction;
		}
	}

	private static final Map<String, String> categories = new ConcurrentHashMap<>();
	private static final TreeMap<Long, Hour> hours = new TreeMap<>();
	private static final Set<String> seen = new LinkedHashSet<>();
	private static final Set<Long> polledMinutes = new LinkedHashSet<>();
	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Gson GSON = new Gson();
	private static volatile long lastPoll, lastSave;
	private static volatile boolean busy;

	private AhMarket() {}

	/** From the full AH scan: Hypixel's category for each item. */
	static void category(String key, JsonObject auction) {
		if (key != null && auction.has("category")) categories.put(key, auction.get("category").getAsString().toLowerCase(Locale.ROOT));
	}

	static void init() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
				synchronized (hours) {
					for (Map.Entry<String, JsonElement> e : o.entrySet()) hours.put(Long.parseLong(e.getKey()), GSON.fromJson(e.getValue(), Hour.class));
				}
			}
		} catch (Exception ignored) {
			// start fresh
		}
	}

	/** Called every client tick-second; polls every 30 s in the background. */
	static void tick() {
		long now = System.currentTimeMillis();
		if (busy || now - lastPoll < 30_000) return;
		busy = true;
		lastPoll = now;
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/auctions_ended"))
				.timeout(Duration.ofSeconds(15)).header("User-Agent", "NotEnoughFlips/1.0").GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((resp, err) -> {
			try {
				if (err == null && resp.statusCode() == 200) ingest(JsonParser.parseString(resp.body()).getAsJsonObject(), now);
				if (now - lastSave > 600_000) save();
			} catch (Exception ignored) {
				// skip this round
			} finally {
				busy = false;
			}
		});
	}

	static void ingest(JsonObject o, long now) {
		if (!o.has("auctions")) return;
		long minute = now / 60_000;
		synchronized (hours) {
			Hour mh = hours.computeIfAbsent(now / 3_600_000, k -> new Hour());
			if (polledMinutes.add(minute)) {
				mh.polledMinutes++;
				if (polledMinutes.size() > 200) polledMinutes.remove(polledMinutes.iterator().next());
			}
			for (JsonElement e : o.getAsJsonArray("auctions")) {
				JsonObject a = e.getAsJsonObject();
				String id = a.has("auction_id") ? a.get("auction_id").getAsString() : null;
				if (id == null || !seen.add(id)) continue;
				if (seen.size() > 20_000) seen.remove(seen.iterator().next());
				double price = a.has("price") ? a.get("price").getAsDouble() : 0;
				if (price <= 0) continue;
				long ts = a.has("timestamp") ? a.get("timestamp").getAsLong() : now;
				Hour h = hours.computeIfAbsent(ts / 3_600_000, k -> new Hour());
				boolean bin = a.has("bin") && a.get("bin").getAsBoolean();
				if (bin) h.bin += price;
				else h.auction += price;
				h.sales++;
				String cat = "misc";
				try {
					JsonElement ib = a.get("item_bytes");
					String b64 = ib == null ? null : ib.isJsonPrimitive() ? ib.getAsString()
							: ib.getAsJsonObject().has("data") ? ib.getAsJsonObject().get("data").getAsString() : null;
					if (b64 != null) {
						String[] key = {null};
						int[] count = {1};
						AhScanner.identify(b64, key, count);
						if (key[0] != null) cat = categories.getOrDefault(key[0], key[0].startsWith("PET") ? "pets" : "misc");
					}
				} catch (Exception ignored) {
					// unknown item: misc
				}
				h.byCategory.merge(cat, price, Double::sum);
			}
			while (!hours.isEmpty() && hours.firstKey() < now / 3_600_000 - 48) hours.pollFirstEntry();
		}
	}

	/** Pure: coins per day from hourly buckets. Uses the last 24 h when ≥ 20 h were measured, else scales the rate. */
	static Day day(Map<Long, Hour> hs, long now) {
		long cur = now / 3_600_000;
		double bin = 0, auc = 0, measured = 0;
		int sales = 0;
		Map<String, Double> cats = new HashMap<>();
		for (Map.Entry<Long, Hour> e : hs.entrySet()) {
			if (e.getKey() <= cur - 24) continue;
			Hour h = e.getValue();
			bin += h.bin;
			auc += h.auction;
			sales += h.sales;
			measured += Math.min(60, h.polledMinutes) / 60.0;
			h.byCategory.forEach((k, v) -> cats.merge(k, v, Double::sum));
		}
		if (measured <= 0) return new Day(0, 0, 0, cats, 0, false);
		boolean scaled = measured < 20;
		double f = scaled ? 24.0 / measured : 1.0;
		Map<String, Double> scaledCats = new HashMap<>();
		cats.forEach((k, v) -> scaledCats.put(k, v * f));
		return new Day(bin * f, auc * f, (int) Math.round(sales * f), scaledCats, measured, scaled);
	}

	static Day day() {
		synchronized (hours) {
			return day(new TreeMap<>(hours), System.currentTimeMillis());
		}
	}

	private static void save() throws IOException {
		lastSave = System.currentTimeMillis();
		JsonObject o = new JsonObject();
		synchronized (hours) {
			hours.forEach((k, v) -> o.add(String.valueOf(k), GSON.toJsonTree(v)));
		}
		Files.writeString(file(), o.toString());
	}

	private static Path file() {
		return Accounts.shared("ah-market.json");
	}

	/** Bazaar coins per day per product: (instant buys × buy price + instant sells × sell price) ÷ 7. Pure. */
	static Map<String, Double> bazaarPerDay(Map<String, DataClient.Live> all) {
		Map<String, Double> out = new HashMap<>();
		for (Map.Entry<String, DataClient.Live> e : all.entrySet()) {
			DataClient.Live l = e.getValue();
			double v = (l.buyWeek() * Math.max(0, l.buy()) + l.sellWeek() * Math.max(0, l.sell())) / 7.0;
			if (v > 0) out.put(e.getKey(), v);
		}
		return out;
	}

	static List<Map.Entry<String, Double>> top(Map<String, Double> m, int n) {
		List<Map.Entry<String, Double>> l = new ArrayList<>(m.entrySet());
		l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		return l.subList(0, Math.min(n, l.size()));
	}
}

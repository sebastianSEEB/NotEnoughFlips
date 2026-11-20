package dev.notenoughflips;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the mod's own full AH scan learned about every item, kept on your PC (config/notenoughflips/market-book.json):
 *
 *   lowest   cheapest BIN of any copy (for upgrade parts: pet items, skins, master stars, gems…)
 *   clean    what a plain copy costs: the 2nd cheapest plain BIN, or the median of the next few if that is lower
 *   listed   how many BINs are up
 *
 * This replaces the collector / website data of the private build: the clean price is the base of the
 * "this copy ≈" estimate in tooltips, and the lowest BINs price the upgrades. Refreshed with every AH scan.
 */
final class MarketBook {
	record Item(double lowest, double clean, int listed) {}

	private static final int KEEP = 6;
	private static volatile Map<String, Item> book = Map.of();
	private static volatile long updated;
	private static Map<String, double[]> building = new HashMap<>();   // key → [lowest, listed, plain prices… (KEEP)]
	private static boolean loaded;
	// key → copy spec → {cheapest 3 BINs…, listed}: for "a copy at least as upgraded is listed for X" (memory only)
	private static volatile Map<String, Map<Spec, double[]>> specs = Map.of();
	private static Map<String, Map<Spec, double[]>> buildingSpecs = new HashMap<>();

	private MarketBook() {}

	static synchronized void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path f = Accounts.shared("market-book.json");
			if (!Files.exists(f)) return;
			JsonObject j = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, Item> m = new HashMap<>();
			JsonObject items = j.getAsJsonObject("items");
			for (String k : items.keySet()) {
				var a = items.getAsJsonArray(k);
				m.put(k, new Item(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsInt()));
			}
			book = m;
			updated = j.get("updated").getAsLong();
		} catch (Exception ignored) {
			// start empty; the next scan fills it
		}
	}

	// ------------------------------------------------------------------ scan thread
	static synchronized void begin() {
		building = new HashMap<>();
		buildingSpecs = new HashMap<>();
	}

	/** One BIN from the scan: unit price, and whether this copy is plain (no stars, recomb, big enchants…). */
	static synchronized void observe(String key, double unit, boolean plain) {
		if (key == null || unit <= 0) return;
		double[] b = building.computeIfAbsent(key, k -> {
			double[] n = new double[2 + KEEP];
			n[0] = Double.MAX_VALUE;
			Arrays.fill(n, 2, n.length, Double.MAX_VALUE);
			return n;
		});
		b[0] = Math.min(b[0], unit);
		b[1]++;
		if (!plain || unit >= b[b.length - 1]) return;
		int i = b.length - 1;                                          // insert into the sorted plain prices
		while (i > 2 && b[i - 1] > unit) {
			b[i] = b[i - 1];
			i--;
		}
		b[i] = unit;
	}

	/** One BIN from the scan with its upgrades (non-pets). */
	static synchronized void observeSpec(String key, double unit, Spec spec) {
		if (key == null || spec == null || unit <= 0) return;
		double[] b = buildingSpecs.computeIfAbsent(key, k -> new HashMap<>())
				.computeIfAbsent(spec, k -> new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, 0});
		b[3]++;
		if (unit >= b[2]) return;
		if (unit < b[0]) { b[2] = b[1]; b[1] = b[0]; b[0] = unit; }
		else if (unit < b[1]) { b[2] = b[1]; b[1] = unit; }
		else b[2] = unit;
	}

	/**
	 * Cheapest BIN of a copy at least as upgraded as {@code mine} (see {@link Spec#covers}), and how many such copies
	 * are listed: {price, listed}, or null when none / no scan yet. {@code exclude} = the price of the listing being
	 * looked at, so it isn't compared with itself (one copy at that price is skipped).
	 */
	static double[] cover(String key, Spec mine, Double exclude) {
		Map<Spec, double[]> m = key == null || mine == null ? null : specs.get(key);
		if (m == null) return null;
		List<Double> prices = new ArrayList<>();
		int listed = 0;
		for (Map.Entry<Spec, double[]> e : m.entrySet()) {
			if (!e.getKey().covers(mine)) continue;
			double[] b = e.getValue();
			listed += (int) b[3];
			for (int i = 0; i < 3; i++) if (b[i] < Double.MAX_VALUE) prices.add(b[i]);
		}
		prices.sort(Double::compare);
		if (exclude != null) {
			for (int i = 0; i < prices.size(); i++) {
				if (Math.abs(prices.get(i) - exclude) <= Math.max(1, exclude * 0.0005)) { prices.remove(i); listed--; break; }
			}
		}
		return prices.isEmpty() ? null : new double[]{prices.get(0), Math.max(1, listed)};
	}

	static synchronized void finish(long ts) {
		if (!buildingSpecs.isEmpty()) specs = buildingSpecs;
		buildingSpecs = new HashMap<>();
		Map<String, Item> m = new HashMap<>();
		for (Map.Entry<String, double[]> e : building.entrySet()) {
			double[] b = e.getValue();
			List<Double> plain = new ArrayList<>();
			for (int i = 2; i < b.length; i++) if (b[i] < Double.MAX_VALUE) plain.add(b[i]);
			m.put(e.getKey(), new Item(b[0], clean(plain), (int) b[1]));
		}
		building = new HashMap<>();
		if (m.isEmpty()) return;
		book = m;
		updated = ts;
		save();
	}

	/** Plain-copy price: 2nd cheapest (the cheapest may be a one-off), or the median of the next few if lower. 0 = none. */
	static double clean(List<Double> sortedPlain) {
		if (sortedPlain.isEmpty()) return 0;
		if (sortedPlain.size() == 1) return sortedPlain.get(0);
		List<Double> next = sortedPlain.subList(1, Math.min(sortedPlain.size(), 5));
		return Math.min(sortedPlain.get(1), Market.median(new ArrayList<>(next)));
	}

	private static void save() {
		try {
			StringBuilder sb = new StringBuilder("{\"updated\":").append(updated).append(",\"items\":{");
			boolean first = true;
			for (Map.Entry<String, Item> e : book.entrySet()) {
				if (!first) sb.append(',');
				first = false;
				Item it = e.getValue();
				sb.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":[")
						.append(Math.round(it.lowest())).append(',').append(Math.round(it.clean())).append(',').append(it.listed()).append(']');
			}
			sb.append("}}");
			Files.writeString(Accounts.shared("market-book.json"), sb.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not critical
		}
	}

	// ------------------------------------------------------------------ reading
	static Item get(String key) {
		load();
		return key == null ? null : book.get(key);
	}

	static long updated() {
		load();
		return updated;
	}

	/** Lowest BIN of every item, for upgrade pricing (components "lbin"). */
	static JsonObject lowestAll() {
		load();
		JsonObject o = new JsonObject();
		for (Map.Entry<String, Item> e : book.entrySet()) o.addProperty(e.getKey(), Math.round(e.getValue().lowest()));
		return o;
	}

	/** The "market.model" the tooltip estimate needs, built from the clean price. Null when there is none. */
	static JsonObject model(String key) {
		Item it = get(key);
		if (it == null || it.clean() <= 0) return null;
		JsonObject m = new JsonObject();
		m.addProperty("type", "item");
		m.addProperty("base", it.lowest() > 0 ? Math.min(it.clean(), it.lowest()) : it.clean());
		m.addProperty("n_sales", it.listed());
		m.addProperty("source", "your AH scan");
		return m;
	}
}

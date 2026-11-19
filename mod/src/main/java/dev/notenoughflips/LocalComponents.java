package dev.notenoughflips;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Upgrade prices for the "this copy ≈" estimate, built on your PC (same shape as market/valuation.py components_doc):
 *
 *   bazaar          live instant-buy price of every bazaar product (essences, books, recombs, potato books, gems…)
 *   lbin            lowest BIN of every item from the mod's own AH scan (MarketBook)
 *   upgrade_costs   the essence / item cost of each star, from Hypixel's public items list (refreshed daily)
 *   factors…        how much of an upgrade's price a buyer pays for, plus pet XP tables
 */
final class LocalComponents {
	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static volatile JsonObject cached;
	private static volatile long builtAt;
	private static volatile JsonObject upgradeCosts;
	private static volatile long costsAt;
	private static volatile boolean costsLoading;

	private LocalComponents() {}

	static JsonObject get(DataClient data) {
		long now = System.currentTimeMillis();
		loadCosts();
		if (cached != null && now - builtAt < 60_000L) return cached;
		JsonObject c = new JsonObject();
		JsonObject bz = new JsonObject();
		for (Map.Entry<String, DataClient.Live> e : data.bazaarAll().entrySet())
			if (e.getValue().buy() > 0) bz.addProperty(e.getKey(), Math.round(e.getValue().buy() * 10) / 10.0);
		c.add("bazaar", bz);
		c.add("lbin", MarketBook.lowestAll());
		c.add("upgrade_costs", upgradeCosts == null ? new JsonObject() : upgradeCosts);
		JsonObject f = new JsonObject();
		Object[][] factors = {{"enchant", 0.85}, {"recomb", 0.85}, {"hpb", 1.0}, {"fuming", 0.85}, {"stars", 0.9}, {"master_star", 0.95},
				{"gem", 1.0}, {"art_of_war", 0.85}, {"pet_item", 0.9}, {"pet_skin", 0.8}};
		for (Object[] x : factors) f.addProperty((String) x[0], (double) x[1]);
		c.add("factors", f);
		c.addProperty("min_enchant_value", 200_000);
		JsonObject xp = new JsonObject();
		Object[][] xpMax = {{"COMMON", 5_624_785}, {"UNCOMMON", 8_644_220}, {"RARE", 12_626_665}, {"EPIC", 18_608_500},
				{"LEGENDARY", 25_353_230}, {"MYTHIC", 25_353_230}};
		for (Object[] x : xpMax) xp.addProperty((String) x[0], (int) x[1]);
		c.add("pet_xp_max", xp);
		JsonArray l200 = new JsonArray();
		for (String s : new String[]{"GOLDEN_DRAGON", "JADE_DRAGON", "ROSE_DRAGON"}) l200.add(s);
		c.add("level_200_pets", l200);
		c.addProperty("xp_max_200", 210_255_385);
		JsonArray ms = new JsonArray();
		for (String s : new String[]{"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"}) ms.add(s);
		c.add("master_stars", ms);
		c.addProperty("source", "built on your PC");
		cached = c;
		builtAt = now;
		return c;
	}

	/** Star costs per item id from Hypixel's public items resource (no key), cached on disk for a day. */
	private static void loadCosts() {
		long now = System.currentTimeMillis();
		if (costsLoading || upgradeCosts != null && now - costsAt < 86_400_000L) return;
		costsLoading = true;
		Path file = Accounts.shared("upgrade-costs.json");
		try {
			if (upgradeCosts == null && Files.exists(file)) {
				JsonObject j = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				upgradeCosts = j.getAsJsonObject("costs");
				costsAt = j.get("at").getAsLong();
				if (now - costsAt < 86_400_000L) { costsLoading = false; return; }
			}
		} catch (Exception ignored) {
			// fetch below
		}
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/resources/skyblock/items"))
				.header("User-Agent", "NotEnoughFlips (Fabric mod)").timeout(Duration.ofSeconds(30)).GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
			try {
				if (e == null && r.statusCode() == 200) {
					JsonObject costs = costs(JsonParser.parseString(r.body()).getAsJsonObject());
					upgradeCosts = costs;
					costsAt = System.currentTimeMillis();
					JsonObject out = new JsonObject();
					out.addProperty("at", costsAt);
					out.add("costs", costs);
					Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
				}
			} catch (Exception ignored) {
				// try again later
			} finally {
				costsLoading = false;
			}
		});
	}

	/** Pure: {item id: upgrade_costs} from the items resource. */
	static JsonObject costs(JsonObject resource) {
		JsonObject out = new JsonObject();
		if (!resource.has("items") || !resource.get("items").isJsonArray()) return out;
		for (JsonElement e : resource.getAsJsonArray("items")) {
			JsonObject it = e.getAsJsonObject();
			if (it.has("id") && it.has("upgrade_costs") && it.get("upgrade_costs").isJsonArray())
				out.add(it.get("id").getAsString(), it.get("upgrade_costs"));
		}
		return out;
	}
}

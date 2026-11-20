package dev.notenoughflips;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Your AH positions straight from the Hypixel API, every 15 minutes while you play (and when you open the FCF panel
 * if the last scan is over 2 minutes old). Reads every page of /v2/skyblock/auctions (streamed, gzip), keeps the
 * auctions you created or bid on, identifies each item from its item_bytes (gzip NBT), values it with the same data
 * as the tooltip, and builds the same report as market/fcf.py:
 *
 *   potential FCF = wallet + ongoing sales − ongoing bids + expected sale value (+ outbid refunds)
 *
 * The coins history (Trade Desk → Coins → History) is kept per account in config/notenoughflips/accounts/<uuid>/coins-history.csv (7 days).
 */
final class AhScanner {
	private static final long ON_OPEN_MS = 2 * 60_000L;
	private static volatile boolean feed;
	private static final String API = "https://api.hypixel.net/v2/skyblock/auctions?page=";

	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static volatile boolean running;
	private static volatile long lastStart, lastDone;
	private static volatile JsonObject report;
	private static volatile String status = "";

	private AhScanner() {}

	static JsonObject report() {
		return report;
	}

	/** Another account is playing: drop the last report and scan again for this account. */
	static void reset() {
		report = null;
		lastStart = 0;
		lastDone = 0;
	}

	static long lastDone() {
		return lastDone;
	}

	static String status() {
		return status;
	}

	static boolean running() {
		return running;
	}

	/** Called every client tick (cheap): starts a scan every scanMinutes (default 15) while in a world. */
	static void tick(NefConfig cfg) {
		if (!cfg.modScan && !cfg.flipFeed || running || Minecraft.getInstance().player == null) return;
		if (System.currentTimeMillis() - lastStart >= Math.max(5, cfg.scanMinutes) * 60_000L) start(cfg);
	}

	/** Panel opened or ⟳ clicked: rescan if the last one is older than 2 min. */
	static void poke(NefConfig cfg, boolean force) {
		if (!cfg.modScan && !cfg.flipFeed || running || Minecraft.getInstance().player == null) return;
		if (force || System.currentTimeMillis() - lastStart >= ON_OPEN_MS) start(cfg);
	}

	private static void start(NefConfig cfg) {
		feed = cfg.flipFeed;
		UUID id = Minecraft.getInstance().player.getUUID();
		String me = id.toString().replace("-", "").toLowerCase(Locale.ROOT);
		running = true;
		lastStart = System.currentTimeMillis();
		status = "scanning the AH…";
		Thread t = new Thread(() -> {
			try {
				scan(me);
			} catch (Exception e) {
				status = "AH scan failed: " + e.getMessage();
			} finally {
				running = false;
			}
		}, "Not Enough Flips-AH-scan");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ scan
	static InputStream page(int p) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(API + p)).timeout(Duration.ofSeconds(20))
				.header("Accept-Encoding", "gzip").header("User-Agent", "NotEnoughFlips (Fabric mod)").GET().build();
		HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
		if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " on page " + p);
		InputStream in = resp.body();
		boolean gz = resp.headers().firstValue("Content-Encoding").map(v -> v.contains("gzip")).orElse(false);
		return gz ? new GZIPInputStream(in) : in;
	}

	private static void scan(String me) throws Exception {
		List<JsonObject> mine = new ArrayList<>();
		long now = System.currentTimeMillis();
		MarketBook.begin();
		if (feed) FlipFeed.begin();
		int pages = 1;
		long updated = 0;
		for (int p = 0; p < pages; p++) {
			try (JsonReader r = new JsonReader(new InputStreamReader(page(p), StandardCharsets.UTF_8))) {
				r.beginObject();
				while (r.hasNext()) {
					String name = r.nextName();
					switch (name) {
						case "totalPages" -> pages = r.nextInt();
						case "lastUpdated" -> updated = r.nextLong();
						case "auctions" -> {
							r.beginArray();
							while (r.hasNext()) {
								JsonObject a = JsonParser.parseReader(r).getAsJsonObject();
								if (involves(a, me)) mine.add(a);
								else FlipFeed.collect(a, now, feed);          // every other BIN: market book (+ flip feed)
							}
							r.endArray();
						}
						default -> r.skipValue();
					}
				}
				r.endObject();
			}
			status = "scanning the AH… page " + (p + 1) + "/" + pages;
		}
		build(mine, me, updated > 0 ? updated : System.currentTimeMillis());
		MarketBook.finish(updated > 0 ? updated : System.currentTimeMillis());
		if (feed) FlipFeed.finish(updated > 0 ? updated : System.currentTimeMillis());
		lastDone = System.currentTimeMillis();
		status = "";
	}

	private static String uid(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().replace("-", "").toLowerCase(Locale.ROOT) : "";
	}

	private static boolean involves(JsonObject a, String me) {
		if (a.has("claimed") && a.get("claimed").getAsBoolean()) return false;
		if (me.equals(uid(a, "auctioneer"))) return true;
		if (a.has("bids") && a.get("bids").isJsonArray()) {
			for (JsonElement b : a.getAsJsonArray("bids")) if (me.equals(uid(b.getAsJsonObject(), "bidder"))) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ positions + summary (mirror of market/fcf.py)
	private static double num(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
	}

	private static double claimTax(double v) {
		return v > 1_000_000 ? v * 0.01 : 0;
	}

	/**
	 * What one copy realistically sells for: fair value, but never above just under the cheapest competing BIN
	 * (a level-1 pet's "fair" mixes level-100 sales; the cheapest listing is what you compete with). High-level pets
	 * ([Lvl 80]+) keep their fair value, since the cheapest listings are usually low-level copies.
	 */
	static Double value(String key, String itemName) {
		if (key == null) return null;
		JsonObject d = key.startsWith("PET_") ? NefClient.petDoc(key, petLevel(itemName), true) : null;   // same-level sales
		if (d != null) {
			Double fair = Verdict.fair(d);
			Double lowest = HudFormatter.num(HudFormatter.obj(d, "market", "listed"), "lowest");
			if (lowest == null) lowest = HudFormatter.num(HudFormatter.obj(d, "summary"), "lbin");
			if (fair == null || fair <= 0) return lowest;
			return lowest == null || lowest <= 0 ? fair : Math.min(fair, Market.undercut(lowest));
		}
		d = NefClient.docFor(key, false);
		if (d == null) return null;
		Double fair = Verdict.fair(d);
		Double lowest = HudFormatter.num(HudFormatter.obj(d, "market", "listed"), "lowest");
		if (lowest == null) lowest = HudFormatter.num(HudFormatter.obj(d, "summary"), "lbin");
		if (lowest != null && lowest <= 0) lowest = null;
		if (fair == null || fair <= 0) return lowest;
		if (lowest == null || key.startsWith("PET_") && petLevel(itemName) >= 80) return fair;
		return Math.min(fair, Market.undercut(lowest));
	}

	static int petLevel(String itemName) {
		if (itemName == null) return 0;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[Lvl (\\d+)\\]").matcher(itemName);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private static void build(List<JsonObject> mine, String me, long ts) {
		JsonArray positions = new JsonArray();
		double sales = 0, salesCons = 0, bids = 0, expected = 0, refunds = 0, profitWins = 0;
		int nSales = 0, nWin = 0, nOut = 0, unvalued = 0;
		long now = System.currentTimeMillis();
		for (JsonObject a : mine) {
			String[] key = {null};
			int[] count = {1};
			try {
				identify(a.get("item_bytes").getAsString(), key, count);
			} catch (Exception ignored) {
				// unknown item: still counted, without a value
			}
			if (key[0] != null) NefClient.docFor(key[0], true);      // warm prices for next time / the tooltip
			Double unitVal = value(key[0], a.has("item_name") ? a.get("item_name").getAsString() : null);
			Double val = unitVal == null ? null : unitVal * count[0];
			double top = num(a, "highest_bid_amount");
			JsonObject p = new JsonObject();
			p.addProperty("uuid", a.get("uuid").getAsString());
			p.addProperty("item", a.has("item_name") ? a.get("item_name").getAsString() : key[0]);
			if (key[0] != null) p.addProperty("key", key[0]);
			if (a.has("tier")) p.addProperty("tier", a.get("tier").getAsString());
			p.addProperty("count", count[0]);
			p.addProperty("end", num(a, "end"));
			p.addProperty("ended", num(a, "end") <= now);
			if (val != null) p.addProperty("value", val);
			boolean bin = a.has("bin") && a.get("bin").getAsBoolean();
			if (me.equals(uid(a, "auctioneer"))) {
				double gross;
				String st;
				if (bin) { gross = num(a, "starting_bid"); st = "listed (BIN)"; }
				else if (top > 0) { gross = top; st = "has bids"; }
				else { gross = num(a, "starting_bid"); st = "no bids yet"; }
				double realistic = st.equals("has bids") || val == null ? gross : Math.min(gross, val);
				p.addProperty("role", "sale");
				p.addProperty("bin", bin);
				p.addProperty("status", st);
				p.addProperty("amount", gross);
				p.addProperty("net", gross - claimTax(gross));
				p.addProperty("net_conservative", realistic - claimTax(realistic));
				p.addProperty("overpriced", val != null && gross > val * 1.1 && !st.equals("has bids"));
				sales += gross - claimTax(gross);
				salesCons += realistic - claimTax(realistic);
				nSales++;
			} else {
				double myBid = 0;
				for (JsonElement b : a.getAsJsonArray("bids")) {
					JsonObject bo = b.getAsJsonObject();
					if (me.equals(uid(bo, "bidder"))) myBid = Math.max(myBid, num(bo, "amount"));
				}
				boolean win = myBid >= top - 0.5;
				p.addProperty("role", "bid");
				p.addProperty("bin", false);
				p.addProperty("status", win ? "winning" : "outbid");
				p.addProperty("amount", myBid);
				p.addProperty("top_bid", top);
				if (win) {
					bids += myBid;
					nWin++;
					if (val != null) {
						double net = Verdict.netAfterFees(val);
						p.addProperty("expected_net", net);
						p.addProperty("profit", net - myBid);
						expected += net;
						profitWins += net - myBid;
					} else unvalued++;
				} else {
					refunds += myBid;
					nOut++;
				}
			}
			positions.add(p);
		}
		JsonObject n = new JsonObject();
		n.addProperty("sales", sales);
		n.addProperty("sales_conservative", salesCons);
		n.addProperty("bids", bids);
		n.addProperty("bids_subtracted", false);
		n.addProperty("expected", expected);
		n.addProperty("refunds", refunds);
		double onAh = sales + expected + refunds;
		n.addProperty("on_ah", onAh);
		Double purse = FcfPanel.purse();
		if (purse != null) {
			n.addProperty("wallet", purse);
			n.addProperty("purse", purse);
			n.addProperty("fcf", purse + onAh);
			n.addProperty("fcf_conservative", purse + salesCons + expected + refunds);
		} else {
			n.addProperty("fcf", onAh);
			n.addProperty("fcf_conservative", salesCons + expected + refunds);
		}
		n.addProperty("n_sales", nSales);
		n.addProperty("n_winning", nWin);
		n.addProperty("n_outbid", nOut);
		n.addProperty("unvalued_wins", unvalued);
		n.addProperty("expected_profit_on_wins", profitWins);

		JsonObject f = new JsonObject();
		f.addProperty("updated", ts);
		f.add("now", n);
		f.add("series", history(ts, onAh, purse));
		f.add("positions", sorted(positions));
		JsonObject rep = new JsonObject();
		rep.addProperty("source", "mod AH scan");
		rep.add("fcf", f);
		report = rep;
	}

	private static JsonArray sorted(JsonArray a) {
		List<JsonObject> l = new ArrayList<>();
		for (JsonElement e : a) l.add(e.getAsJsonObject());
		l.sort((x, y) -> {
			int rx = "bid".equals(x.get("role").getAsString()) ? 0 : 1, ry = "bid".equals(y.get("role").getAsString()) ? 0 : 1;
			if (rx != ry) return rx - ry;
			int wx = "winning".equals(x.get("status").getAsString()) ? 0 : 1, wy = "winning".equals(y.get("status").getAsString()) ? 0 : 1;
			if (wx != wy) return wx - wy;
			return Double.compare(num(y, "amount"), num(x, "amount"));
		});
		JsonArray out = new JsonArray();
		l.forEach(out::add);
		return out;
	}

	/** Appends this scan to the 7-day FCF history file and returns the series (ts, on_ah, wallet, fcf). */
	private static JsonArray history(long ts, double onAh, Double purse) {
		Path file = null;
		List<String> lines = new ArrayList<>();
		try {
			file = Accounts.file("coins-history.csv");
			if (Files.exists(file)) lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException ignored) {
			// start fresh
		}
		lines.add(ts + "," + Math.round(onAh) + "," + (purse == null ? "" : String.valueOf(Math.round(purse))));
		long cutoff = ts - 7L * 86_400_000L;
		lines.removeIf(l -> {
			try {
				return Long.parseLong(l.split(",")[0]) < cutoff;
			} catch (RuntimeException e) {
				return true;
			}
		});
		try {
			if (file != null) Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException ignored) {
			// not critical
		}
		JsonArray series = new JsonArray();
		for (String l : lines) {
			String[] p = l.split(",", -1);
			JsonObject o = new JsonObject();
			o.addProperty("ts", Long.parseLong(p[0]));
			o.addProperty("on_ah", Double.parseDouble(p[1]));
			if (p.length > 2 && !p[2].isEmpty()) {
				double w = Double.parseDouble(p[2]);
				o.addProperty("wallet", w);
				o.addProperty("fcf", w + Double.parseDouble(p[1]));
			}
			series.add(o);
		}
		return series;
	}

	// ------------------------------------------------------------------ item_bytes → our price key (same rules as market/items.py)
	static void identify(String b64, String[] key, int[] count) throws IOException {
		identify(b64, key, count, null);
	}

	/**
	 * Same as {@link #identify(String, String[], int[])}; when {@code variant} is given, variant[0] is set to the
	 * upgrades that change what this copy is worth (stars, recomb, potato books, valuable enchants, gems,
	 * attributes, skin/dye, scrolls). Two listings are only comparable when their variants match.
	 */
	static void identify(String b64, String[] key, int[] count, String[] variant) throws IOException {
		identify(b64, key, count, variant, null);
	}

	/** Same, plus spec[0] = this copy's upgrades for "at least as upgraded" comparisons (null for pets / books). */
	static void identify(String b64, String[] key, int[] count, String[] variant, Spec[] spec) throws IOException {
		identify(b64, key, count, variant, spec, null);
	}

	/** Same, plus attrs[0] = the upgrades in the tooltip valuation's form (null for pets / books). */
	static void identify(String b64, String[] key, int[] count, String[] variant, Spec[] spec, Valuation.Attrs[] attrs) throws IOException {
		byte[] raw = Base64.getDecoder().decode(b64);
		Object root;
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(raw)))) {
			int type = in.readByte();
			if (type != 10) return;
			in.readUTF();
			root = readPayload(in, 10);
		}
		Object items = ((Map<?, ?>) root).get("i");
		if (!(items instanceof List<?> l) || l.isEmpty() || !(l.get(0) instanceof Map<?, ?> it)) return;
		if (it.get("Count") instanceof Number c) count[0] = Math.max(1, c.intValue());
		if (!(it.get("tag") instanceof Map<?, ?> tag) || !(tag.get("ExtraAttributes") instanceof Map<?, ?> ea)) return;
		if (!(ea.get("id") instanceof String id) || id.isEmpty()) return;
		key[0] = id;
		if (variant != null) variant[0] = variant(ea);
		if (spec != null && !id.equals("PET") && !id.equals("ENCHANTED_BOOK")) spec[0] = Spec.of(ea);
		if (attrs != null && !id.equals("PET") && !id.equals("ENCHANTED_BOOK")) attrs[0] = Valuation.attrsOf(ea);
		if (id.equals("ENCHANTED_BOOK") && ea.get("enchantments") instanceof Map<?, ?> en && en.size() == 1) {
			Map.Entry<?, ?> e = en.entrySet().iterator().next();
			key[0] = "ENCHANTMENT_" + e.getKey().toString().toUpperCase(Locale.ROOT) + "_" + ((Number) e.getValue()).intValue();
		} else if (id.equals("PET") && ea.get("petInfo") instanceof String info) {
			try {
				JsonObject pet = JsonParser.parseString(info).getAsJsonObject();
				String held = pet.has("heldItem") && !pet.get("heldItem").isJsonNull() ? pet.get("heldItem").getAsString() : null;
				String type = pet.get("type").getAsString().toUpperCase(Locale.ROOT);
				String tier = Valuation.realPetTier(pet.get("tier").getAsString().toUpperCase(Locale.ROOT), held);
				key[0] = "PET_" + type + "_" + tier;
			} catch (RuntimeException ignored) {
				key[0] = "PET";
			}
		}
	}

	/**
	 * Pure: what makes this copy different from a plain one, as a stable string ("" = plain). Kept coarse on purpose:
	 * low enchant levels (I–V, which anyone can apply from the table) and the potato-book count within 1–10 don't split
	 * listings, everything that costs real coins does.
	 */
	static String variant(Map<?, ?> ea) {
		java.util.TreeMap<String, String> v = new java.util.TreeMap<>();
		int stars = intOf(ea.get("upgrade_level"));
		if (stars == 0) stars = intOf(ea.get("dungeon_item_level"));
		if (stars > 0) v.put("stars", String.valueOf(stars));
		if (intOf(ea.get("rarity_upgrades")) > 0) v.put("recomb", "1");
		int hpb = intOf(ea.get("hot_potato_count"));
		if (hpb > 0) v.put("hpb", hpb > 10 ? String.valueOf(hpb) : "10");
		// the reforge is left out: almost every copy has one and most cost little; the clearing floor covers the rest
		if (ea.get("enchantments") instanceof Map<?, ?> en) {
			java.util.TreeMap<String, Integer> big = new java.util.TreeMap<>();
			for (Map.Entry<?, ?> e : en.entrySet()) {
				String k = e.getKey().toString().toLowerCase(Locale.ROOT);
				int lvl = intOf(e.getValue());
				if (k.startsWith("ultimate_") || lvl >= 6) big.put(k, lvl);
			}
			if (!big.isEmpty()) v.put("ench", big.toString());
		}
		if (ea.get("attributes") instanceof Map<?, ?> at && !at.isEmpty()) v.put("attr", new java.util.TreeMap<>(at).toString());
		if (ea.get("gems") instanceof Map<?, ?> g) {
			int n = 0;
			for (Map.Entry<?, ?> e : g.entrySet()) {
				String k = e.getKey().toString();
				if (!k.equals("unlocked_slots") && !k.endsWith("_gem")) n++;
			}
			if (n > 0) v.put("gems", String.valueOf(n));
		}
		for (String k : new String[] {"skin", "dye_item", "ability_scroll", "ethermerge", "power_ability_scroll", "art_of_war_count",
				"talisman_enrichment", "mana_disintegrator_count", "polarvoid", "wood_singularity_count", "drill_part_engine",
				"drill_part_fuel_tank", "drill_part_upgrade_module", "rod_part", "line", "hook", "sinker", "boosters"}) {
			Object o = ea.get(k);
			if (o != null) v.put(k, String.valueOf(o));
		}
		return v.isEmpty() ? "" : v.toString();
	}

	private static int intOf(Object o) {
		return o instanceof Number n ? n.intValue() : 0;
	}

	private static Object readPayload(DataInputStream in, int type) throws IOException {
		switch (type) {
			case 1: return in.readByte();
			case 2: return in.readShort();
			case 3: return in.readInt();
			case 4: return in.readLong();
			case 5: return in.readFloat();
			case 6: return in.readDouble();
			case 7: { int n = in.readInt(); in.skipNBytes(n); return null; }
			case 8: return in.readUTF();
			case 9: {
				int et = in.readByte(), n = in.readInt();
				List<Object> l = new ArrayList<>(Math.max(0, n));
				for (int i = 0; i < n; i++) l.add(readPayload(in, et));
				return l;
			}
			case 10: {
				Map<String, Object> m = new HashMap<>();
				while (true) {
					int t = in.readByte();
					if (t == 0) return m;
					String name = in.readUTF();
					m.put(name, readPayload(in, t));
				}
			}
			case 11: { int n = in.readInt(); in.skipNBytes(4L * n); return null; }
			case 12: { int n = in.readInt(); in.skipNBytes(8L * n); return null; }
			default: throw new IOException("bad NBT tag " + type);
		}
	}

	@SuppressWarnings("unused")
	private static boolean isNull(JsonReader r) throws IOException {
		return r.peek() == JsonToken.NULL;
	}
}

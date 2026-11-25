package dev.notenoughflips;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Which recipes you have: your collection tiers, slayer levels and Heart of the Mountain tier, read from your own
 * SkyBlock profile (the same /nef apikey lookup as your bank, every 5 minutes). A recipe's unlock text ("Ender Pearl
 * VIII", "Enderman Slayer 5", "Gemstone X & HotM 5") is checked against them. Without an API key nothing is known
 * and every recipe counts as "maybe".
 */
final class Unlocks {
	private Unlocks() {}

	private static volatile Map<String, Integer> coll = Map.of();        // collection id → highest unlocked tier
	private static volatile Map<String, Integer> slayer = Map.of();      // zombie, spider, wolf, enderman, blaze, vampire → level
	private static volatile int hotm = -1;
	private static volatile boolean known;
	private static volatile Map<String, String> names = Map.of();        // "ender pearl" → ENDER_PEARL
	private static volatile long namesAt;
	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/** Total HotM experience needed for tiers 1..10. */
	static final long[] HOTM_XP = {0, 3_000, 12_000, 37_000, 97_000, 197_000, 347_000, 557_000, 847_000, 1_247_000};

	static boolean known() {
		return known;
	}

	static void reset() {
		known = false;
		coll = Map.of();
		slayer = Map.of();
		hotm = -1;
	}

	/** Your member entry of the selected profile (from the profiles API). */
	static void fromProfile(JsonObject member) {
		if (member == null) return;
		Map<String, Integer> c = new HashMap<>();
		JsonObject pd = HudFormatter.obj(member, "player_data");
		JsonElement tiers = pd != null && pd.has("unlocked_coll_tiers") ? pd.get("unlocked_coll_tiers")
				: member.get("unlocked_coll_tiers");
		if (tiers != null && tiers.isJsonArray()) {
			for (JsonElement e : tiers.getAsJsonArray()) {
				String s = e.getAsString();
				int k = s.lastIndexOf('_');
				if (k <= 0) continue;
				try {
					int t = Integer.parseInt(s.substring(k + 1));
					c.merge(s.substring(0, k), t, Math::max);
				} catch (NumberFormatException ignored) {
					// not a tier
				}
			}
		}
		Map<String, Integer> sl = new HashMap<>();
		JsonObject bosses = HudFormatter.obj(HudFormatter.obj(member, "slayer"), "slayer_bosses");
		if (bosses != null) {
			for (Map.Entry<String, JsonElement> b : bosses.entrySet()) {
				JsonObject cl = b.getValue().isJsonObject() ? HudFormatter.obj(b.getValue().getAsJsonObject(), "claimed_levels") : null;
				int lv = 0;
				if (cl != null) for (String k : cl.keySet()) {
					Matcher m = Pattern.compile("^level_(\\d+)").matcher(k);
					if (m.find()) lv = Math.max(lv, Integer.parseInt(m.group(1)));
				}
				sl.put(b.getKey(), lv);
			}
		}
		Double xp = HudFormatter.num(HudFormatter.obj(member, "mining_core"), "experience");
		if (xp == null) xp = HudFormatter.num(HudFormatter.obj(HudFormatter.obj(member, "skill_tree"), "experience"), "mining");
		coll = c;
		slayer = sl;
		hotm = xp == null ? -1 : hotmTier(xp);
		known = !c.isEmpty() || !sl.isEmpty();
		loadNames();
	}

	static int hotmTier(double xp) {
		int t = 0;
		for (int i = 0; i < HOTM_XP.length; i++) if (xp >= HOTM_XP[i]) t = i + 1;
		return t;
	}

	/** Collection display names → ids, from Hypixel's public resources (no key needed), once an hour. */
	static void loadNames() {
		long now = System.currentTimeMillis();
		if (!names.isEmpty() && now - namesAt < 3_600_000L || now - namesAt < 60_000L) return;
		namesAt = now;
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/resources/skyblock/collections"))
				.header("User-Agent", "NotEnoughFlips (Fabric mod)").timeout(Duration.ofSeconds(15)).GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
			if (e != null || r.statusCode() != 200) return;
			try {
				names = parseNames(JsonParser.parseString(r.body()).getAsJsonObject());
			} catch (RuntimeException ignored) {
				// try later
			}
		});
	}

	/** Pure: {"collections":{"MINING":{"items":{"ENDER_PEARL":{"name":"Ender Pearl"}}}}} → "ender pearl" → ENDER_PEARL. */
	static Map<String, String> parseNames(JsonObject root) {
		Map<String, String> m = new HashMap<>();
		JsonObject cs = HudFormatter.obj(root, "collections");
		if (cs == null) return m;
		for (Map.Entry<String, JsonElement> cat : cs.entrySet()) {
			JsonObject items = cat.getValue().isJsonObject() ? HudFormatter.obj(cat.getValue().getAsJsonObject(), "items") : null;
			if (items == null) continue;
			for (Map.Entry<String, JsonElement> it : items.entrySet()) {
				JsonObject o = it.getValue().getAsJsonObject();
				if (o.has("name")) m.put(o.get("name").getAsString().toLowerCase(Locale.ROOT), it.getKey());
			}
		}
		return m;
	}

	/** For tests. */
	static void set(Map<String, Integer> collections, Map<String, Integer> slayers, int hotmTier, Map<String, String> collectionNames) {
		coll = collections;
		slayer = slayers;
		hotm = hotmTier;
		names = collectionNames;
		known = true;
	}

	private static final Pattern SLAYER = Pattern.compile("(?i)^(zombie|spider|wolf|enderman|blaze|vampire) slayer (\\d+)$");
	private static final Pattern HOTM = Pattern.compile("(?i)^(?:hotm|heart of the mountain tier) (\\d+)$");
	private static final Pattern COLL = Pattern.compile("^(.+?) ([IVXLC]+)$");

	/**
	 * Whether you meet a recipe's unlock: TRUE, FALSE, or null when it can't be told (no profile yet, or a kind of
	 * requirement it doesn't read). An empty requirement is always met.
	 */
	static Boolean meets(String req) {
		if (req == null || req.isBlank()) return Boolean.TRUE;
		if (!known) return null;
		boolean unsure = false;
		for (String part : req.split("&")) {
			String p = part.trim();
			Matcher m;
			if ((m = SLAYER.matcher(p)).matches()) {
				if (slayer.getOrDefault(m.group(1).toLowerCase(Locale.ROOT), 0) < Integer.parseInt(m.group(2))) return Boolean.FALSE;
			} else if ((m = HOTM.matcher(p)).matches()) {
				if (hotm < 0) unsure = true;
				else if (hotm < Integer.parseInt(m.group(1))) return Boolean.FALSE;
			} else if ((m = COLL.matcher(p)).matches()) {
				String id = names.get(m.group(1).toLowerCase(Locale.ROOT));
				if (id == null) { unsure = true; continue; }
				if (coll.getOrDefault(id, 0) < roman(m.group(2))) return Boolean.FALSE;
			} else unsure = true;
		}
		return unsure ? null : Boolean.TRUE;
	}

	static int roman(String s) {
		Map<Character, Integer> v = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100);
		int total = 0, prev = 0;
		for (int i = s.length() - 1; i >= 0; i--) {
			int x = v.getOrDefault(s.charAt(i), 0);
			total += x < prev ? -x : x;
			prev = Math.max(prev, x);
		}
		return total;
	}
}

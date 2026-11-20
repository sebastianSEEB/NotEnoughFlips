package dev.notenoughflips;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

/**
 * NPC flips: an item listed on the AH (or sold on the bazaar) for less than an NPC pays for it. Buy it and sell it to
 * the NPC: no AH fees, no waiting, no market risk. Found in every full AH scan (DEALS list), live on new listings
 * (chat alert within seconds), and on the bazaar (chat alert, once an hour per product). What an NPC pays comes from
 * Hypixel's public items list (npc_sell_price). Read-only: you buy and sell yourself.
 */
final class NpcFlips {
	record Flip(String uuid, String key, String name, double unit, int count, double npc, double profit) {}

	private static volatile List<Flip> found = List.of();
	private static List<Flip> building = new ArrayList<>();
	private static final Set<String> alerted = ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> bzAlerted = new HashMap<>();
	private static NefConfig cfg;

	private NpcFlips() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	static boolean on() {
		return cfg != null && cfg.npcFlips;
	}

	/** Pure: profit of buying {@code count} at {@code unit} and selling them to an NPC at {@code npc}; 0 when it isn't one. */
	static double profit(double unit, int count, double npc) {
		if (npc <= 0 || unit <= 0 || unit >= npc * 0.98) return 0;     // at least 2 % under, so a price rounding isn't a "flip"
		return (npc - unit) * Math.max(1, count);
	}

	// ------------------------------------------------------------------ full AH scan
	static synchronized void begin() {
		building = new ArrayList<>();
	}

	/** One BIN from the scan. */
	static synchronized void observe(JsonObject a, String key, double unit, int count) {
		if (!on() || key == null) return;
		double npc = LocalComponents.npcSellPrice(key);
		double p = profit(unit, count, npc);
		if (p < cfg.npcFlipMinProfit) return;
		String name = a.has("item_name") ? a.get("item_name").getAsString().replaceAll("§.", "") : Desk.pretty(key);
		building.add(new Flip(a.get("uuid").getAsString(), key, name, unit, count, npc, p));
	}

	static synchronized void finish() {
		List<Flip> b = building;
		b.sort((x, y) -> Double.compare(y.profit(), x.profit()));
		found = List.copyOf(b);
		building = new ArrayList<>();
	}

	static List<Flip> list() {
		return found;
	}

	// ------------------------------------------------------------------ live (new listings)
	static void check(JsonObject a) {
		if (!(on() || PriceAlerts.any()) || !a.has("bin") || !a.get("bin").getAsBoolean() || !a.has("item_bytes") || !a.has("starting_bid") || !a.has("uuid")) return;
		String[] key = {null};
		int[] count = {1};
		try {
			AhScanner.identify(a.get("item_bytes").getAsString(), key, count);
		} catch (Exception e) {
			return;
		}
		if (key[0] == null) return;
		double total = a.get("starting_bid").getAsDouble(), unit = total / Math.max(1, count[0]);
		PriceAlerts.checkListing(key[0], unit, a.get("uuid").getAsString());     // your own "under" price alerts
		if (!on()) return;
		double npc = LocalComponents.npcSellPrice(key[0]);
		double p = profit(unit, count[0], npc);
		if (p < cfg.npcFlipMinProfit || !alerted.add(a.get("uuid").getAsString())) return;
		if (alerted.size() > 5000) alerted.clear();
		String name = a.has("item_name") ? a.get("item_name").getAsString().replaceAll("§.", "") : Desk.pretty(key[0]);
		Alerts.alert(net.minecraft.network.chat.Component.empty().append(HudFormatter.line("[NEF] NPC FLIP ", HudFormatter.GREEN,
				(count[0] > 1 ? count[0] + "x " : "") + name, HudFormatter.WHITE,
				String.format(Locale.ROOT, "  BIN %s, an NPC pays %s: +%s, no fees ", HudFormatter.fmt(total),
						HudFormatter.fmt(npc * count[0]), HudFormatter.fmt(p)), HudFormatter.GRAY))
				.append(HudFormatter.openAuction(a.get("uuid").getAsString())));
	}

	// ------------------------------------------------------------------ bazaar
	/** Every minute: bazaar products whose instant-buy price is under what an NPC pays (one alert per product per hour). */
	static void bazaar(DataClient data) {
		if (!on() || data == null) return;
		long now = System.currentTimeMillis();
		int shown = 0;
		for (Map.Entry<String, DataClient.Live> e : data.bazaarAll().entrySet()) {
			double buy = e.getValue().buy(), npc = LocalComponents.npcSellPrice(e.getKey());
			double each = profit(buy, 1, npc);
			if (each <= 0 || each / buy < 0.05) continue;                 // at least 5 % per item
			Long last = bzAlerted.get(e.getKey());
			if (last != null && now - last < 3_600_000L) continue;
			bzAlerted.put(e.getKey(), now);
			if (shown++ >= 3) continue;
			String name = Bazaar.name(e.getKey(), data);
			Alerts.alert(net.minecraft.network.chat.Component.empty().append(HudFormatter.line("[NEF] NPC FLIP (bazaar) ", HudFormatter.GREEN,
					name, HudFormatter.WHITE, String.format(Locale.ROOT, "  costs %s, an NPC pays %s: +%s each (%.0f%%) ",
							HudFormatter.fmt(buy), HudFormatter.fmt(npc), HudFormatter.fmt(each), 100 * each / buy), HudFormatter.GRAY))
					.append(HudFormatter.openBazaar(name)));
		}
	}
}

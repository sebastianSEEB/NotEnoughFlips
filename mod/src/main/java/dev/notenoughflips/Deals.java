package dev.notenoughflips;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Underpriced BINs found by an optional local server's full AH scan (every 5 min): at least 20 % under fair
 * value, worth 1M+, still profitable after fees. New ones are announced in chat; /nef deals lists
 * them and /nef open <n> opens that auction (you buy it yourself).
 */
final class Deals {
	record Deal(String uuid, String key, double price, int count, double fair, double discount, double profit, long firstSeen) {}

	private static volatile List<Deal> current = List.of();
	private static final Set<String> announced = new HashSet<>();
	private static NefConfig cfg;
	private static DataClient data;
	private static boolean first = true;

	private Deals() {}

	static void start(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "NEF-deals");
			t.setDaemon(true);
			return t;
		});
		ex.scheduleWithFixedDelay(Deals::poll, 20, Math.max(30, cfg.dealPollSeconds), TimeUnit.SECONDS);
	}

	private static final java.util.Map<String, Long> firstSeen = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Underpriced BINs from the mod's own AH scan: the cheapest copy of an item is at least dealMinDiscountPercent under
	 * the next identical copies (same stars, recomb, enchants…), worth at least dealMinValue, and not blocked for snipes.
	 */
	static List<Deal> fromScan() {
		List<Deal> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (FlipFeed.Candidate c : FlipFeed.candidates()) {
			if (c.ref() <= 0 || Sniper.blocked(c.key(), cfg)) continue;
			double discount = 1 - c.price() / c.ref();
			if (discount * 100 < cfg.dealMinDiscountPercent || c.ref() < cfg.dealMinValue) continue;
			long first = firstSeen.computeIfAbsent(c.uuid(), u -> now);
			out.add(new Deal(c.uuid(), c.key(), c.price(), 1, c.ref(), discount, FlipFeed.rawProfit(c), first));
		}
		out.sort((a, b) -> Double.compare(b.profit(), a.profit()));
		if (firstSeen.size() > 5000) firstSeen.clear();
		return out;
	}

	static List<Deal> list() {
		return current;
	}

	private static void poll() {
		try {
			JsonArray arr = data.localDeals().get(10, TimeUnit.SECONDS);
			List<Deal> out = new ArrayList<>();
			if (arr != null) {                                          // optional local server
				for (JsonElement e : arr) {
					JsonObject o = e.getAsJsonObject();
					Deal d = new Deal(o.get("uuid").getAsString(), o.get("item_key").getAsString(), o.get("price").getAsDouble(),
							o.get("count").getAsInt(), o.get("fair").getAsDouble(), o.get("discount").getAsDouble(),
							o.get("profit").getAsDouble(), o.get("first_seen").getAsLong());
					if (d.discount() * 100 >= cfg.dealMinDiscountPercent && d.fair() * d.count() >= cfg.dealMinValue) out.add(d);
				}
			} else {
				out = fromScan();                                       // your own AH scan (every scan, no server needed)
			}
			current = out;
			if (!cfg.dealAlerts) return;
			int shown = 0;
			for (int i = 0; i < out.size(); i++) {
				Deal d = out.get(i);
				if (!announced.add(d.uuid()) || first) continue;          // first poll: just remember what's already there
				if (shown++ >= 3) continue;                                // don't flood chat
				Chat.say(HudFormatter.line("[NEF] DEAL ", 0x55FF55,
						(d.count() > 1 ? d.count() + "x " : "") + d.key(), HudFormatter.WHITE,
						String.format(Locale.ROOT, "  BIN %s (%.0f%% under fair %s)  ", HudFormatter.fmt(d.price()), d.discount() * 100,
								HudFormatter.fmt(d.fair() * d.count())), HudFormatter.GRAY,
						"+" + HudFormatter.fmt(d.profit()) + " after fees", 0x55FF55,
						"  → /nef open " + (i + 1), HudFormatter.AQUA));
			}
			first = false;
			if (announced.size() > 5000) announced.clear();
		} catch (Exception ignored) {
			// local server offline or slow: try again next round
		}
	}
}

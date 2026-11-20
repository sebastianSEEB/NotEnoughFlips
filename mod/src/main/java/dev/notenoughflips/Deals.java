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
			// the next listings can be wishful: never count on more than copies of this item really sell for
			java.util.List<double[]> week = NefClient.cofl == null ? null : NefClient.cofl.weekClean(c.key(), false);
			if (week == null) continue;                               // no sales history loaded yet: can't check it
			com.google.gson.JsonObject doc = NefClient.docFor(c.key(), false);
			if (doc != null && Market.hasSevereFlag(doc)) continue;   // manipulated
			if (doc == null && c.attrs() != null && c.attrs().hasUpgrades()) continue;   // upgraded copy we can't value yet
			double sold = 0, from = now - 86_400_000L;
			for (double[] q : week) if (q[0] >= from) sold += q[4];
			if (sold < Math.max(1, cfg.snipeMinSalesPerDay)) continue;  // barely sells: you'd be stuck holding it
			double ceiling = Valuation.ceiling(week);
			double npc = NpcPrices.price(c.key());                      // an NPC sells it: never worth more than that
			if (npc > 0 && ceiling > 0) ceiling = Math.min(ceiling, npc);
			else if (npc > 0) ceiling = npc;
			double fair = ceiling > 0 ? Math.min(c.ref(), ceiling) : c.ref();
			// "identical" copies leave out small upgrades: if any copy with the same or better upgrades is listed for
			// less, that's the price you'd have to beat when you resell
			double[] cov = MarketBook.cover(c.key(), c.spec(), c.price());
			if (cov != null && cov[0] > 0) fair = Math.min(fair, cov[0]);
			// and never above what the tooltip says this copy is worth (clean price + upgrades, same caps), so a deal
			// here always agrees with the tooltip when you open it
			if (doc != null && c.attrs() != null) {
				try {
					Valuation.Estimate est = Valuation.estimate(doc, c.key(), c.attrs(), c.price());
					if (est != null && est.value() > 0) fair = Math.min(fair, est.value());
				} catch (RuntimeException ignored) {
					// no estimate: the checks above still apply
				}
			}
			double profit = Verdict.netAfterFees(Market.undercut(fair)) - c.price();
			double discount = 1 - c.price() / fair;
			if (profit <= 0 || discount * 100 < cfg.dealMinDiscountPercent || fair < Math.max(cfg.dealMinValue, cfg.minItemValue)) continue;
			long first = firstSeen.computeIfAbsent(c.uuid(), u -> now);
			out.add(new Deal(c.uuid(), c.key(), c.price(), 1, fair, discount, profit, first));
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
						"+" + HudFormatter.fmt(d.profit()) + " after fees", 0x55FF55).append(HudFormatter.openAuction(d.uuid())));
			}
			first = false;
			if (announced.size() > 5000) announced.clear();
		} catch (Exception ignored) {
			// local server offline or slow: try again next round
		}
	}
}

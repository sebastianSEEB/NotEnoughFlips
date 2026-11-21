package dev.notenoughflips;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;

/**
 * Auctions (not BINs) about to end below what the item sells for. Macros race for underpriced BINs; few watch regular
 * auctions, where the cheapest bid simply wins at the end. Every AH scan notes the auctions ending in the next
 * scanMinutes; about two and a half minutes before each one ends, if the item is worth well over the bid, you get a
 * chat line with the most you can bid and still make your profit. Bids can rise after the scan, so it gives a "bid up
 * to" number, not a price: check the current bid in the menu, and never go above it.
 */
final class EndingSoon {
	private EndingSoon() {}

	record Auction(String uuid, String key, String name, double start, double top, long end, int count) {}

	private static NefConfig cfg;
	private static List<Auction> building = new ArrayList<>();
	private static volatile List<Auction> upcoming = List.of();
	private static final Set<String> said = ConcurrentHashMap.newKeySet();

	static final long ALERT_BEFORE_MS = 150_000L, LAST_CALL_MS = 25_000L;

	static void init(NefConfig config) {
		cfg = config;
	}

	static void begin() {
		building = new ArrayList<>();
	}

	/** One auction from the scan (scan thread). Only regular auctions with a real end time ahead. */
	static void observe(JsonObject a, long now) {
		if (cfg == null || !cfg.endingAlerts) return;
		if (a.has("bin") && a.get("bin").getAsBoolean() || a.has("claimed") && a.get("claimed").getAsBoolean()) return;
		if (!a.has("end") || !a.has("item_bytes") || !a.has("uuid")) return;
		long end = a.get("end").getAsLong();
		long horizon = Math.max(5, cfg.scanMinutes) * 60_000L + ALERT_BEFORE_MS;
		if (end - now < LAST_CALL_MS || end - now > horizon) return;
		double start = a.has("starting_bid") ? a.get("starting_bid").getAsDouble() : 0;
		double top = a.has("highest_bid_amount") ? a.get("highest_bid_amount").getAsDouble() : 0;
		if (Math.max(start, top) <= 0) return;
		String[] key = {null};
		int[] count = {1};
		try {
			AhScanner.identify(a.get("item_bytes").getAsString(), key, count);
		} catch (Exception e) {
			return;
		}
		if (key[0] == null || Sniper.blocked(key[0], cfg)) return;
		String name = a.has("item_name") ? a.get("item_name").getAsString().replaceAll("§.", "") : key[0];
		building.add(new Auction(a.get("uuid").getAsString(), key[0], name, start, top, end, Math.max(1, count[0])));
	}

	static void finish() {
		List<Auction> b = building;
		b.sort((x, y) -> Long.compare(x.end(), y.end()));
		upcoming = b;
		Map<String, Boolean> keys = new HashMap<>();                     // fetch their prices in the background now
		for (Auction a : b) keys.put(a.key(), true);
		if (NefClient.cofl != null) NefClient.cofl.prefetch(new ArrayList<>(keys.keySet()));
	}

	/** The next bid Hypixel takes: the starting bid, or a little over the top bid. Pure. */
	static double nextBid(double start, double top) {
		return top <= 0 ? start : Math.ceil(top * 1.025);
	}

	/** The most you can bid and still clear minProfit after fees when it resells at worth. Pure. */
	static double bidUpTo(double worth, double minProfit) {
		return Math.floor(Verdict.netAfterFees(worth) - minProfit);
	}

	/** Every second or so (client thread): alert the auctions entering their last minutes. */
	static void tick() {
		if (cfg == null || !cfg.endingAlerts || Minecraft.getInstance().player == null) return;
		long now = System.currentTimeMillis();
		for (Auction a : upcoming) {
			long left = a.end() - now;
			if (left > ALERT_BEFORE_MS) break;                             // sorted by end time
			if (left < LAST_CALL_MS || said.contains(a.uuid())) continue;
			String verdict = judge(a);
			if (verdict == null) continue;                                 // prices still loading: try again next second
			said.add(a.uuid());
			if (said.size() > 2000) said.clear();
		}
	}

	/** null = prices not loaded yet; "" = not worth it; otherwise the alert was sent. */
	private static String judge(Auction a) {
		JsonObject d = NefClient.docFor(a.key(), CoflClient.Pri.HIGH);
		List<double[]> week = NefClient.cofl == null ? null : NefClient.cofl.weekClean(a.key(), true);
		if (d == null || week == null) return null;
		if (Sniper.manipulated(d) || Market.erratic(week).bad() || Market.salesPerDay(d) < cfg.snipeMinSalesPerDay) return "";
		Double fair = Verdict.fair(d);
		double floor = FlipFeed.clearingFloor(week);
		if (fair == null || fair <= 0 || floor <= 0) return "";
		double worth = Math.min(fair, Market.undercut(floor));
		MarketBook.Item book = MarketBook.get(a.key());
		if (book != null && book.lowest() > 0) worth = Math.min(worth, Market.undercut(book.lowest()));   // a BIN is cheaper
		double npc = NpcPrices.price(a.key());
		if (npc > 0) worth = Math.min(worth, npc);
		worth *= a.count();
		if (worth < Math.max(cfg.minItemValue, cfg.snipeMinValue)) return "";
		double next = nextBid(a.start(), a.top());
		double minProfit = Math.max(cfg.minFlipProfitCoins, next * 0.10);
		double upTo = bidUpTo(worth, minProfit);
		if (upTo < next) return "";
		double profit = Verdict.netAfterFees(worth) - next;
		long secs = Math.max(0, (a.end() - System.currentTimeMillis()) / 1000);
		String left = secs >= 60 ? (secs / 60) + "m " + (secs % 60) + "s" : secs + "s";
		final double sells = worth;
		Minecraft.getInstance().execute(() -> {
			Chat.ping();
			Chat.say(HudFormatter.line("[NEF] ⌛ AUCTION ENDING ", HudFormatter.AQUA, a.name() + (a.count() > 1 ? " ×" + a.count() : "")
							+ " in " + left, HudFormatter.WHITE,
					String.format(Locale.ROOT, ": next bid ≈ %s, sells ≈ %s, +%s after fees. Bid up to %s, no more",
							HudFormatter.fmt(next), HudFormatter.fmt(sells), HudFormatter.fmt(profit), HudFormatter.fmt(upTo)), HudFormatter.GREEN)
					.append(HudFormatter.openAuction(a.uuid())));
		});
		return "sent";
	}
}

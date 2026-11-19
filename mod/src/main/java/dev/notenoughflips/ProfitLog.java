package dev.notenoughflips;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Profit tracker. Logs your buys and sells from Hypixel's chat messages to config/nef-trades.csv (30 days) and
 * matches each sale to the earliest unsold buy of the same item (first in, first out):
 *
 *   AH buy       "You purchased <item> for <n> coins!"
 *   AH sale      "[Auction] <buyer> bought <item> for <n> coins"            (minus listing fee + claim tax)
 *   Bazaar       "[Bazaar] Bought / Sold <q>x <item> for <n> coins!"        (instant)
 *                "[Bazaar] Claimed <q>x <item> worth <n> coins bought for …" (buy order)
 *                "[Bazaar] Claimed <n> coins from selling <q>x <item> at …"  (sell offer)
 *
 * A flip = a sale matched to a recorded buy. Realized profit = what you got − what that quantity cost you.
 */
final class ProfitLog {
	record Trade(long ts, String side, String market, String item, double qty, double coins) {}

	record Flip(long ts, String item, double qty, double cost, double got, String market) {
		double profit() {
			return got - cost;
		}
	}

	record Summary(double profit, int flips, Flip best, int unmatchedSales, List<Flip> recent) {}

	private static final Pattern AH_BUY = Pattern.compile("^You purchased (.+?) for ([0-9][0-9,]*) coins!");
	private static final Pattern AH_SOLD = Pattern.compile("^\\[Auction\\] (.+?) bought (.+?) for ([0-9][0-9,]*) coins");
	private static final Pattern BZ_INSTANT = Pattern.compile("^\\[Bazaar\\] (Bought|Sold) ([0-9][0-9,]*)x (.+?) for ([0-9][0-9,]*(?:\\.[0-9]+)?) coins");
	private static final Pattern BZ_CLAIM_BUY = Pattern.compile("^\\[Bazaar\\] Claimed ([0-9][0-9,]*)x (.+?) worth ([0-9][0-9,]*(?:\\.[0-9]+)?) coins bought");
	private static final Pattern BZ_CLAIM_SELL = Pattern.compile("^\\[Bazaar\\] Claimed ([0-9][0-9,]*(?:\\.[0-9]+)?) coins from selling ([0-9][0-9,]*)x (.+?) at");
	private static final Pattern QTY = Pattern.compile("^([0-9][0-9,]*)x (.+)$");

	private static final List<Trade> trades = new ArrayList<>();
	private static NefConfig cfg;
	private static boolean loaded;

	private ProfitLog() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	private static Path file() {
		return Accounts.file("trades.csv");
	}

	/** Another account is playing: its own trades load from its own file. */
	static synchronized void reset() {
		trades.clear();
		loaded = false;
	}

	private static synchronized void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			long cutoff = System.currentTimeMillis() - 30L * 86_400_000L;
			for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String[] p = l.split(",", 6);
				if (p.length < 6) continue;
				try {
					Trade t = new Trade(Long.parseLong(p[0]), p[1], p[2], p[5], Double.parseDouble(p[3]), Double.parseDouble(p[4]));
					if (t.ts() >= cutoff) trades.add(t);
				} catch (RuntimeException ignored) {
					// skip a bad line
				}
			}
		} catch (Exception ignored) {
			// start empty
		}
	}

	private static synchronized void add(Trade t) {
		load();
		trades.add(t);
		try {
			List<String> lines = new ArrayList<>();
			for (Trade x : trades) {
				lines.add(x.ts() + "," + x.side() + "," + x.market() + "," + x.qty() + "," + Math.round(x.coins()) + ","
						+ x.item().replace(",", " ").replace("\n", " "));
			}
			Files.write(file(), lines, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not critical
		}
	}

	private static double n(String s) {
		return Double.parseDouble(s.replace(",", ""));
	}

	/** Parses one chat line; returns the trade it recorded (or null). Package-private for tests. */
	static Trade parse(String plain, long now) {
		Matcher m = AH_BUY.matcher(plain);
		if (m.find()) {
			String[] q = qty(m.group(1));
			return new Trade(now, "BUY", "AH", q[1], Double.parseDouble(q[0]), n(m.group(2)));
		}
		m = AH_SOLD.matcher(plain);
		if (m.find()) {
			String[] q = qty(m.group(2));
			double gross = n(m.group(3));
			double net = gross - gross * Verdict.listingFee(gross) - (gross > 1e6 ? gross * 0.01 : 0);
			return new Trade(now, "SELL", "AH", q[1], Double.parseDouble(q[0]), net);
		}
		m = BZ_INSTANT.matcher(plain);
		if (m.find()) {
			return new Trade(now, m.group(1).equals("Bought") ? "BUY" : "SELL", "BZ", m.group(3).trim(), n(m.group(2)), n(m.group(4)));
		}
		m = BZ_CLAIM_BUY.matcher(plain);
		if (m.find()) return new Trade(now, "BUY", "BZ", m.group(2).trim(), n(m.group(1)), n(m.group(3)));
		m = BZ_CLAIM_SELL.matcher(plain);
		if (m.find()) return new Trade(now, "SELL", "BZ", m.group(3).trim(), n(m.group(2)), n(m.group(1)));
		return null;
	}

	private static String[] qty(String item) {
		Matcher q = QTY.matcher(item.trim());
		return q.find() ? new String[]{q.group(1).replace(",", ""), q.group(2).trim()} : new String[]{"1", item.trim()};
	}

	static void onChat(String plain) {
		if (cfg == null || !cfg.profitTracker) return;
		Trade t = parse(plain, System.currentTimeMillis());
		if (t == null) return;
		add(t);
		if ("SELL".equals(t.side())) {
			Summary today = summary(startOfDay(0));
			Flip last = today.recent().isEmpty() ? null : today.recent().get(0);
			if (last != null && last.ts() == t.ts()) {
				Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "Flip done: " + last.item() + " ", HudFormatter.WHITE,
						(last.profit() >= 0 ? "+" : "") + HudFormatter.fmt(last.profit()), last.profit() >= 0 ? HudFormatter.GREEN : HudFormatter.RED,
						"  · today " + (today.profit() >= 0 ? "+" : "") + HudFormatter.fmt(today.profit()) + " from " + today.flips() + " flips",
						HudFormatter.GRAY));
			}
		}
	}

	static long startOfDay(int daysAgo) {
		return LocalDate.now().minusDays(daysAgo).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	/** Realized flips with a sale at or after `from` (FIFO over all logged trades), newest first. */
	static synchronized Summary summary(long from) {
		load();
		return summarize(trades, from);
	}

	static Summary summarize(List<Trade> all, long from) {
		Map<String, Deque<double[]>> lots = new HashMap<>();       // item → [qty, unit cost]
		List<Flip> flips = new ArrayList<>();
		int unmatched = 0;
		List<Trade> sorted = new ArrayList<>(all);
		sorted.sort((a, b) -> Long.compare(a.ts(), b.ts()));
		for (Trade t : sorted) {
			String k = t.item().toLowerCase(Locale.ROOT);
			if ("BUY".equals(t.side())) {
				if (t.qty() > 0) lots.computeIfAbsent(k, x -> new ArrayDeque<>()).add(new double[]{t.qty(), t.coins() / t.qty()});
				continue;
			}
			Deque<double[]> q = lots.get(k);
			double need = t.qty(), cost = 0, matched = 0;
			while (q != null && need > 1e-9 && !q.isEmpty()) {
				double[] lot = q.peekFirst();
				double take = Math.min(need, lot[0]);
				cost += take * lot[1];
				matched += take;
				need -= take;
				lot[0] -= take;
				if (lot[0] <= 1e-9) q.pollFirst();
			}
			if (t.ts() < from) continue;
			if (matched <= 0) {
				unmatched++;
				continue;
			}
			double got = t.coins() * matched / t.qty();
			flips.add(new Flip(t.ts(), t.item(), matched, cost, got, t.market()));
		}
		double profit = 0;
		Flip best = null;
		for (Flip f : flips) {
			profit += f.profit();
			if (best == null || f.profit() > best.profit()) best = f;
		}
		List<Flip> recent = new ArrayList<>(flips);
		recent.sort((a, b) -> Long.compare(b.ts(), a.ts()));
		return new Summary(profit, flips.size(), best, unmatched, recent);
	}

	/** Realized profit + flip count for one market ("AH" / "BZ") since `from`. */
	static double[] marketTotal(long from, String market) {
		double p = 0;
		int n = 0;
		for (Flip f : summary(from).recent()) {
			if (!market.equals(f.market())) continue;
			p += f.profit();
			n++;
		}
		return new double[]{p, n};
	}

	/** Once per day on joining: yesterday's result. */
	static void dailySummary() {
		if (cfg == null || !cfg.profitTracker) return;
		String today = LocalDate.now().toString();
		if (today.equals(cfg.lastSummaryDay)) return;
		cfg.lastSummaryDay = today;
		cfg.save();
		Summary y = summarize(snapshot(), startOfDay(1));
		long end = startOfDay(0);
		double profit = 0;
		int n = 0;
		Flip best = null;
		for (Flip f : y.recent()) {
			if (f.ts() >= end) continue;
			profit += f.profit();
			n++;
			if (best == null || f.profit() > best.profit()) best = f;
		}
		if (n == 0) return;
		Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "Yesterday: ", HudFormatter.WHITE,
				(profit >= 0 ? "+" : "") + HudFormatter.fmt(profit), profit >= 0 ? HudFormatter.GREEN : HudFormatter.RED,
				" from " + n + " flips" + (best != null ? ", best: " + best.item() + " +" + HudFormatter.fmt(best.profit()) : ""),
				HudFormatter.GRAY));
	}

	/** Coins you traded (bought + sold) since `from`: {bazaar, auction house}. */
	static double[] volume(long from) {
		double bz = 0, ah = 0;
		for (Trade t : snapshot()) {
			if (t.ts() < from) continue;
			if ("BZ".equals(t.market())) bz += Math.abs(t.coins());
			else ah += Math.abs(t.coins());
		}
		return new double[]{bz, ah};
	}

	private static synchronized List<Trade> snapshot() {
		load();
		return new ArrayList<>(trades);
	}
}

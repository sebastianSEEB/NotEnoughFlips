package dev.notenoughflips;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Watches the bazaar order books on your PC (every bazaar refresh, ~20-30 s while you play) and measures what the weekly
 * numbers can't show:
 *  - how often the top buy order gets outbid, and how often the cheapest sell offer gets undercut;
 *  - whether a product's spread holds, or only opened up for a moment.
 * From that it works out how much of the time your order would actually be on top if you re-check it every few minutes,
 * and the bazaar ranking scales each flip's expected fills by it. Kept for 3 hours, saved between sessions.
 */
final class BzWatch {
	private BzWatch() {}

	static final long WINDOW_MS = 3 * 3_600_000L;      // what the rates are measured over
	static final long MIN_WATCH_MS = 20 * 60_000L;     // less than this: not enough to judge, nothing changes
	static final long GAP_MS = 5 * 60_000L;            // longer gaps between snapshots aren't counted as watched time

	/** One product's book, as seen over the window. */
	static final class Book {
		double lastOrder = -1, lastOffer = -1, avgStep;
		long lastTs;
		final Deque<long[]> watched = new ArrayDeque<>();          // {from, to}: periods covered by consecutive snapshots
		final Deque<Long> outbids = new ArrayDeque<>(), undercuts = new ArrayDeque<>();
		final Deque<double[]> spreads = new ArrayDeque<>();         // {ts, spread fraction}, one every 2 minutes at most

		void observe(long ts, double order, double offer) {
			if (ts <= lastTs) return;                               // same snapshot again
			if (lastTs > 0 && ts - lastTs <= GAP_MS) {
				long[] last = watched.peekLast();
				if (last != null && last[1] == lastTs) last[1] = ts;        // one stretch while snapshots keep coming
				else watched.addLast(new long[]{lastTs, ts});
				avgStep = avgStep <= 0 ? ts - lastTs : avgStep * 0.9 + (ts - lastTs) * 0.1;
				if (order > lastOrder + 1e-9 && lastOrder > 0) outbids.addLast(ts);    // someone placed a higher buy order
				if (offer < lastOffer - 1e-9 && lastOffer > 0) undercuts.addLast(ts);  // someone listed a cheaper sell offer
			}
			if (order > 0 && offer > order && (spreads.isEmpty() || ts - (long) spreads.peekLast()[0] >= 120_000L))
				spreads.addLast(new double[]{ts, (offer - order) / order});
			lastTs = ts;
			lastOrder = order;
			lastOffer = offer;
			trim(ts);
		}

		void trim(long now) {
			long cut = now - WINDOW_MS;
			while (!watched.isEmpty() && watched.peekFirst()[1] < cut) watched.pollFirst();
			if (!watched.isEmpty() && watched.peekFirst()[0] < cut) watched.peekFirst()[0] = cut;
			while (!outbids.isEmpty() && outbids.peekFirst() < cut) outbids.pollFirst();
			while (!undercuts.isEmpty() && undercuts.peekFirst() < cut) undercuts.pollFirst();
			while (!spreads.isEmpty() && spreads.peekFirst()[0] < cut) spreads.pollFirst();
		}

		long watchedMs() {
			long t = 0;
			for (long[] w : watched) t += w[1] - w[0];
			return t;
		}

		/** Mean seconds between snapshots: the fastest rate that can be seen (one change per snapshot). */
		double stepSeconds() {
			return avgStep <= 0 ? 30 : avgStep / 1000.0;
		}
	}

	private static final Map<String, Book> books = new ConcurrentHashMap<>();
	private static NefConfig cfg;
	private static long savedAt;

	static void init(NefConfig config) {
		cfg = config;
		load();
	}

	/** One bazaar snapshot (lastUpdated = ts): top buy order and cheapest sell offer per product. */
	static void observe(String id, long ts, double topOrder, double topOffer) {
		Book b = books.computeIfAbsent(id, k -> new Book());
		synchronized (b) {
			b.observe(ts, topOrder, topOffer);
		}
	}

	/** After a whole snapshot was read. */
	static void snapshotDone() {
		long now = System.currentTimeMillis();
		if (now - savedAt > 5 * 60_000L) {
			savedAt = now;
			save();
		}
	}

	// ---- the measurements ----

	/** Changes per hour over the watched time, or -1 while there isn't enough. */
	static double perHour(int events, long watchedMs) {
		return watchedMs < MIN_WATCH_MS ? -1 : events / (watchedMs / 3_600_000.0);
	}

	/**
	 * Share of the time your order is the best one if you re-check (and re-outbid) every checkMinutes, when the top
	 * changes hands `rate` times an hour: with a mean of 1/rate before you're outbid, you're on top for
	 * τ(1 − e^(−C/τ)) of every C. 1 when nobody competes. Pure.
	 */
	static double onTop(double ratePerHour, double checkMinutes) {
		if (ratePerHour <= 0) return 1;
		double tau = 60.0 / ratePerHour, c = Math.max(0.5, checkMinutes);
		return Math.min(1, tau / c * (1 - Math.exp(-c / tau)));
	}

	/** Part of the watched time the spread was at least half what it is now (1 = it always holds). Pure. */
	static double held(Iterable<double[]> spreads, double now) {
		if (now <= 0) return 1;
		int n = 0, ok = 0;
		for (double[] s : spreads) {
			n++;
			if (s[1] >= now * 0.5) ok++;
		}
		return n == 0 ? 1 : (double) ok / n;
	}

	record Stats(double outbidsPerHour, double undercutsPerHour, double held, double factor, long watchedMs, boolean saturated) {}

	/** What's been measured for a product, or null while it hasn't been watched long enough. */
	static Stats stats(String id) {
		Book b = books.get(id);
		if (b == null) return null;
		synchronized (b) {
			long w = b.watchedMs();
			if (w < MIN_WATCH_MS) return null;
			double ob = perHour(b.outbids.size(), w), uc = perHour(b.undercuts.size(), w);
			double now = b.spreads.isEmpty() ? 0 : b.spreads.peekLast()[1];
			double h = held(b.spreads, now);
			double check = cfg == null ? 5 : cfg.bazaarCheckMinutes;
			double f = Math.min(onTop(ob, check), onTop(uc, check)) * h;
			// changing on (almost) every snapshot: the real rate is at least this, likely more
			double maxRate = 3600.0 / b.stepSeconds();
			boolean sat = Math.max(ob, uc) >= maxRate * 0.8;
			return new Stats(ob, uc, h, Math.max(0.02, f), w, sat);
		}
	}

	/** Multiplier for a flip's expected fills (1 until measured). */
	static double factor(String id) {
		if (cfg != null && !cfg.bazaarBookWatch) return 1;
		Stats s = stats(id);
		return s == null ? 1 : s.factor();
	}

	/** True when the spread only opened briefly: skip it. */
	static boolean flash(String id) {
		if (cfg != null && !cfg.bazaarBookWatch) return false;
		Stats s = stats(id);
		return s != null && s.held() < 0.25;
	}

	/** Short tag for the bazaar list: " · top outbid ~2m" (only when it changes hands often), else "". */
	static String tag(String id) {
		Stats s = stats(id);
		if (s == null || Math.max(s.outbidsPerHour(), s.undercutsPerHour()) < 6) return "";
		return " · top taken " + every(Math.max(s.outbidsPerHour(), s.undercutsPerHour()), s.saturated());
	}

	/** "top buy order outbid every ~2m, cheapest offer undercut every ~40s · spread held 85% of 1.5h", or null. */
	static String describe(String id) {
		Stats s = stats(id);
		if (s == null) {
			Book b = books.get(id);
			return b == null ? null : "order book: watching (" + Math.max(1, b.watchedMs() / 60_000) + " of 20 min)";
		}
		return "buy order outbid " + every(s.outbidsPerHour(), s.saturated()) + ", sell offer undercut " + every(s.undercutsPerHour(), s.saturated())
				+ String.format(Locale.ROOT, " · spread held %d%% of %s · on top ≈ %d%% of the time", Math.round(s.held() * 100),
				Market.dur(s.watchedMs() / 3_600_000.0), Math.round(s.factor() / Math.max(0.01, s.held()) * 100));
	}

	static String every(double perHour, boolean saturated) {
		if (perHour <= 0) return "never";
		double min = 60 / perHour;
		String t = min >= 1 ? "~" + Math.round(min) + "m" : "~" + Math.max(1, Math.round(min * 60)) + "s";
		return (saturated ? "every " + t + " or faster" : "every " + t);
	}

	// ---- disk ----

	private static Path file() {
		return Accounts.shared("bazaar-watch.json");
	}

	private static void save() {
		JsonObject root = new JsonObject();
		long cut = System.currentTimeMillis() - WINDOW_MS;
		for (Map.Entry<String, Book> e : books.entrySet()) {
			Book b = e.getValue();
			JsonObject o = new JsonObject();
			synchronized (b) {
				if (b.lastTs < cut) continue;
				o.addProperty("o", b.lastOrder);
				o.addProperty("f", b.lastOffer);
				o.addProperty("t", b.lastTs);
				JsonArray w = new JsonArray();
				for (long[] x : b.watched) { w.add(x[0]); w.add(x[1]); }
				o.add("w", w);
				JsonArray ob = new JsonArray();
				b.outbids.forEach(ob::add);
				o.add("ob", ob);
				JsonArray uc = new JsonArray();
				b.undercuts.forEach(uc::add);
				o.add("uc", uc);
				JsonArray sp = new JsonArray();
				for (double[] x : b.spreads) { sp.add((long) x[0]); sp.add(Math.round(x[1] * 1e5) / 1e5); }
				o.add("sp", sp);
			}
			root.add(e.getKey(), o);
		}
		try {
			Files.writeString(file(), root.toString());
		} catch (Exception ignored) {
			// not critical
		}
	}

	private static void load() {
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			long now = System.currentTimeMillis();
			Map<String, Book> m = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				Book b = new Book();
				b.lastOrder = o.get("o").getAsDouble();
				b.lastOffer = o.get("f").getAsDouble();
				b.lastTs = o.get("t").getAsLong();
				JsonArray w = o.getAsJsonArray("w");
				for (int i = 0; i + 1 < w.size(); i += 2) b.watched.addLast(new long[]{w.get(i).getAsLong(), w.get(i + 1).getAsLong()});
				for (JsonElement x : o.getAsJsonArray("ob")) b.outbids.addLast(x.getAsLong());
				for (JsonElement x : o.getAsJsonArray("uc")) b.undercuts.addLast(x.getAsLong());
				JsonArray sp = o.getAsJsonArray("sp");
				for (int i = 0; i + 1 < sp.size(); i += 2) b.spreads.addLast(new double[]{sp.get(i).getAsLong(), sp.get(i + 1).getAsDouble()});
				b.trim(now);
				m.put(e.getKey(), b);
			}
			books.putAll(m);
		} catch (Exception e) {
			System.err.println("[NEF] bazaar watch file: " + e);
		}
	}

	/** For tests. */
	static void clear() {
		books.clear();
	}
}

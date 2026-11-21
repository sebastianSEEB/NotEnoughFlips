package dev.notenoughflips;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import net.minecraft.client.Minecraft;

/**
 * Undervalued-BIN alert: a banner at the top of the screen (plus a ping and a chat line) as soon as a BIN that's clearly
 * under the market appears on the AH.
 *
 * How fast: Hypixel publishes the auction list about once a minute, newest first. The mod watches for that update
 * (polling page 0 every second, only around the time it's due) and checks just the new listings, so an alert shows a few
 * seconds after Hypixel publishes it — within about a minute of the seller listing it, the API's limit.
 *
 * What counts as undervalued (same rules as the AH market flips, see FlipFeed):
 *   - compared only with IDENTICAL copies (item + stars, recomb, enchants…) from the last full AH scan,
 *     with at least 3 of them listed;
 *   - at least snipeMinPercent (default 50 %) under the lowest BIN of those copies, i.e. at most half of it;
 *   - resale = just under that copy, never above fair value or what the cheapest copies really sold for lately;
 *   - not manipulated: the item needs real sales history (fetched first, a few seconds), no spike / crash / buyout /
 *     inflated-lowest-BIN flag, and the lowest BIN itself must be within 1.5× of what copies really sell for
 *     (otherwise "half the lowest BIN" is half of a manipulated price);
 *   - profit after AH fees ≥ minFlipProfitCoins and ≥ 5 %; fits your purse.
 * Blacklisted, cheap (< snipeMinValue), slow-selling and erratic-history items are skipped (see BLOCK_CONTAINS).
 * With snipeAutoOpen the auction view opens by itself (/viewauction, at most once per 10 s, not while chat or a sign is open, so it
 * never interrupts typing). Nothing is bought for you: you press Buy Item Right Now and Confirm yourself.
 */
final class Sniper {
	/** status: "ok" (checked: real sales, not manipulated), "checking" (opened at once, history still loading), "bad: …". */
	record Alert(String uuid, String name, String key, long count, double price, double resale, double profit, double pct,
			String status, long at) {
	}

	/**
	 * Built-in blacklist: items whose price depends on something the AH data can't see, or whose markets are known for
	 * fake / coin-transfer sales, so "half the lowest BIN" is usually not a bargain:
	 *   skins, dyes and runes (cosmetic, thin, often manipulated), attribute shards (value = which attribute),
	 *   New Year Cakes (value = year), Midas weapons (value = price paid), potions (value = level / type),
	 *   party and balloon hats (colour / year), cake souls, editions. Pets and enchanted books are skipped anyway.
	 * Plus every item whose sales this week are erratic (Market.erratic), that sells under snipeMinSalesPerDay, or whose
	 * lowest BIN is under snipeMinValue. Your own /nef snipe block|unblock lists go on top.
	 */
	static final String[] BLOCK_CONTAINS = {"SKIN", "DYE_", "RUNE", "ATTRIBUTE_SHARD", "NEW_YEAR_CAKE", "MIDAS_", "POTION",
			"PARTY_HAT", "BALLOON_HAT", "CAKE_SOUL", "EDITION"};

	static boolean blocked(String key, NefConfig cfg) {
		if (key == null) return true;
		if (cfg.snipeBlock.contains(key)) return true;
		if (cfg.snipeAllow.contains(key)) return false;
		if (key.startsWith("PET") || key.equals("ENCHANTED_BOOK")) return true;
		for (String b : BLOCK_CONTAINS) if (key.contains(b)) return true;
		return false;
	}

	/** Cheap first look at a listing: item, identical-copy bucket, and whether it's at most half the lowest BIN. */
	record Pre(String uuid, String key, String bucket, long count, double total, double unit, double[] ref, String name, Spec spec) {}

	/** bucket (item#variant) → {cheapest, reference = min(2nd, median 2nd–5th), listed} from the last full scan. */
	private static volatile Map<String, double[]> refs = Map.of();
	private static final List<Alert> alerts = new ArrayList<>();
	private static final Set<String> seen = new LinkedHashSet<>();
	private static NefConfig cfg;
	private static volatile long lastUpdated, lastPollAt;
	private static Thread thread;
	static final long SHOW_MS = 12_000;

	private Sniper() {}

	static void init(NefConfig config) {
		cfg = config;
		HudHook.register("snipe_banner", (p, size) -> {
			if (BidHelper.currentScreen() == null) paint(p, size[0]);
		});
		thread = new Thread(Sniper::loop, "NEF-sniper");
		thread.setDaemon(true);
		thread.start();
	}

	/** Called by FlipFeed at the end of a full scan. */
	static void setRefs(Map<String, double[]> r) {
		refs = r;
	}

	/** One snipable item: plain copies only, with the price that would trigger an alert. */
	record Watch(String key, double lowest, int listed, double trigger) {}

	/** Everything the sniper watches right now (≥ 3 identical copies listed, worth ≥ snipeMinValue, not blacklisted), most valuable first. */
	static List<Watch> watchList() {
		List<Watch> out = new ArrayList<>();
		double need = Math.max(0, Math.min(0.95, cfg.snipeMinPercent / 100.0));
		for (Map.Entry<String, double[]> e : refs.entrySet()) {
			if (e.getKey().contains("#")) continue;
			double[] r = e.getValue();
			if (r[2] < 3 || r[0] < Math.max(cfg.snipeMinValue, cfg.minItemValue) || blocked(e.getKey(), cfg)) continue;
			double trig = r[0] * (1 - need);
			if (cfg.snipeMaxPrice > 0 && trig > cfg.snipeMaxPrice) continue;
			out.add(new Watch(e.getKey(), r[0], (int) r[2], trig));
		}
		out.sort((a, b) -> Double.compare(b.lowest() * Math.min(b.listed(), 10), a.lowest() * Math.min(a.listed(), 10)));
		return out;
	}

	static boolean ready() {
		return !refs.isEmpty();
	}

	static synchronized Alert latest() {
		return alerts.isEmpty() ? null : alerts.get(alerts.size() - 1);
	}

	static synchronized List<Alert> recent() {
		return new ArrayList<>(alerts);
	}

	// ------------------------------------------------------------------ watching
	private static volatile long seenAt, lastOldAt;            // when we saw the current version appear / the old one last
	private static volatile List<String> warmKeys = List.of();
	private static int warmIdx;
	private static long warmAt;

	/** Items worth sniping, from the last scan: their price history is loaded in the background so a hit is judged at once. */
	static void warm(List<String> keys) {
		warmKeys = keys;
		warmIdx = 0;
	}

	private static void loop() {
		while (true) {
			try {
				Thread.sleep(250);
				if (cfg == null || !(cfg.sniper || Undercut.watching() || NpcFlips.on() || PriceAlerts.any()) || Minecraft.getInstance().player == null) continue;
				long now = System.currentTimeMillis();
				retryPending();
				if (refs.isEmpty()) {                                   // needs one full scan for the reference prices
					if (now - lastPollAt > 5000) {
						lastPollAt = now;
						AhScanner.poke(cfg, false);
					}
					continue;
				}
				warmStep(now);
				// Hypixel republishes about every 60 s. Quiet until ~6 s before the next one is due (timed from when we
				// saw the last one, so your PC clock doesn't matter), then ask every 0.4 s. A request that finds the old
				// version is cut off after its first few bytes, so asking that often costs almost nothing.
				if (seenAt > 0 && now < seenAt + 54_000) continue;
				if (now - lastPollAt < 400) continue;
				lastPollAt = now;
				poll();
			} catch (InterruptedException e) {
				return;
			} catch (Exception ignored) {
				// network hiccup: try again next round
			}
		}
	}

	/** One background history fetch every 4 s for a likely snipe target that isn't cached yet (~15 items a minute). */
	private static void warmStep(long now) {
		if (now - warmAt < 4000 || NefClient.cofl == null) return;
		List<String> keys = warmKeys;
		while (warmIdx < keys.size()) {
			String k = keys.get(warmIdx++);
			if (NefClient.cofl.week(k, false) != null) continue;
			warmAt = now;
			NefClient.cofl.doc(k, CoflClient.Pri.LOW);      // background: never ahead of a snipe or a hover
			return;
		}
	}

	private static void poll() throws Exception {
		String me = Minecraft.getInstance().player.getUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
		long prev = lastUpdated;
		for (int p = 0; p < 4; p++) {
			boolean anyNew = false, fresh = p > 0;
			long cutoff = prev == 0 ? Long.MAX_VALUE : prev - 5_000;
			try (JsonReader r = new JsonReader(new InputStreamReader(AhScanner.page(p), StandardCharsets.UTF_8))) {
				r.beginObject();
				while (r.hasNext()) {
					String name = r.nextName();
					if (name.equals("lastUpdated")) {
						long updated = r.nextLong();
						if (p == 0) {
							long t = System.currentTimeMillis();
							if (updated <= prev) {                         // same version: stop reading, close the stream
								lastOldAt = t;
								return;
							}
							lastUpdated = updated;
							// we only know when Hypixel published if the poll just before still saw the old version;
							// otherwise (start-up, a hiccup) keep polling until we catch the next change exactly
							seenAt = t - lastOldAt < 1500 ? t : 0;
							fresh = true;
							cutoff = prev == 0 ? updated - 70_000 : prev - 5_000;   // first look: the last minute or so
						}
					} else if (name.equals("auctions") && fresh) {
						r.beginArray();
						while (r.hasNext()) {                               // judged while streaming: a hit fires at once
							JsonObject a = JsonParser.parseReader(r).getAsJsonObject();
							long start = a.has("start") ? a.get("start").getAsLong() : 0;
							if (start < cutoff) continue;
							anyNew = true;
							if (!a.has("uuid") || me.equals(a.has("auctioneer") ? a.get("auctioneer").getAsString().replace("-", "") : "")) continue;
							synchronized (Sniper.class) {
								if (!seen.add(a.get("uuid").getAsString())) continue;
								if (seen.size() > 5000) seen.remove(seen.iterator().next());
							}
							Undercut.check(a);                                // someone undercut one of your BINs?
							NpcFlips.check(a);                                // listed under what an NPC pays?
							if (cfg.sniper) handle(a);
						}
						r.endArray();
					} else r.skipValue();
				}
				r.endObject();
			}
			if (!anyNew) return;                                       // newest first: older pages have nothing new
		}
	}

	private static final List<Object[]> pending = new ArrayList<>();       // {Pre, first seen}

	/**
	 * Speed first: a listing at half the lowest BIN or less fires immediately. If its price history is cached it's fully
	 * checked first (a few ms); if not, the alert goes out as "checking…" (auction opens at once) while the history
	 * loads, then turns into ✓ or ✗ (don't buy) within seconds.
	 */
	/** auction uuid → when it was listed (Hypixel lets nobody buy a new BIN for its first 20 s). */
	private static final Map<String, Long> listedAt = new java.util.concurrent.ConcurrentHashMap<>();

	/** Seconds until a new BIN can be bought (0 = now). */
	static long graceLeft(String uuid) {
		long ms = graceMs(uuid);
		return ms <= 0 ? 0 : (ms + 999) / 1000;
	}

	/** ms until a new BIN can be bought (Hypixel's clock: start + the hold); ≤ 0 = buyable; Long.MIN_VALUE = unknown. */
	static long graceMs(String uuid) {
		Long t = uuid == null ? null : listedAt.get(uuid);
		return t == null ? Long.MIN_VALUE : t + cfg.snipeGraceMs - ServerClock.now();
	}

	/** "3.4s", "BUY NOW" (for 3 s after the hold ends), or null. Pure. */
	static String countdown(long ms) {
		if (ms == Long.MIN_VALUE || ms < -3_000) return null;
		if (ms <= 0) return "BUY NOW";
		return String.format(Locale.ROOT, "%.1fs", Math.ceil(ms / 100.0) / 10.0);
	}

	/** When to play the beats, in ms from now: 3 s, 2 s, 1 s before the hold ends, and the "go" at 0 (last). Pure. */
	static long[] beats(long graceMs, long leadMs) {
		java.util.List<Long> out = new java.util.ArrayList<>();
		for (long at : new long[]{graceMs - 3000, graceMs - 2000, graceMs - 1000, graceMs}) {
			long d = at - leadMs;
			if (d >= 0) out.add(d);
		}
		return out.stream().mapToLong(Long::longValue).toArray();
	}

	private static final java.util.concurrent.ScheduledExecutorService BEAT = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "NEF snipe beats");
		t.setDaemon(true);
		t.setPriority(Thread.MAX_PRIORITY);
		return t;
	});
	private static volatile String beatFor;

	/**
	 * Count the hold down by ear: tick, tick, tick, GO, timed to Hypixel's clock. You click on the beat instead of
	 * reacting to it, which is the only way a person gets close to a macro on the first buyable moment.
	 */
	private static void scheduleBeats(Alert al) {
		if (!cfg.snipeBeats || al.uuid().equals(beatFor)) return;
		long g = graceMs(al.uuid());
		if (g == Long.MIN_VALUE || g <= 0) return;
		beatFor = al.uuid();
		long[] at = beats(g, cfg.snipeBeatLeadMs);
		for (int i = 0; i < at.length; i++) {
			boolean go = i == at.length - 1 && at[i] == g - cfg.snipeBeatLeadMs;
			BEAT.schedule(() -> {
				Alert cur = latest();
				if (cur == null || !cur.uuid().equals(al.uuid())) return;     // a newer alert took over
				if (cur.status().startsWith("bad") || cur.status().startsWith("unchecked")) return;
				Chat.beat(go);
			}, at[i], java.util.concurrent.TimeUnit.MILLISECONDS);
		}
	}

	private static void handle(JsonObject a) {
		Pre pre = pre(a, cfg);
		if (pre == null) return;
		if (a.has("start")) {
			listedAt.put(pre.uuid(), a.get("start").getAsLong());
			if (listedAt.size() > 500) listedAt.clear();
		}
		Object v = evaluate(pre, cfg);
		if (v == PENDING) {
			if (NefClient.cofl != null) NefClient.cofl.urgent(pre.key());   // fast lane: history + BIN prices now
			Alert prov = provisional(pre, cfg);
			if (prov == null) return;
			fire(prov);
			synchronized (pending) {
				pending.add(new Object[]{pre, System.currentTimeMillis(), System.currentTimeMillis()});
			}
		} else if (v instanceof Alert al) fire(al);
	}

	/**
	 * Stop waiting for a snipe's sales history: SkyCofl answered with none (after 5 s, in case the BIN prices are still
	 * coming), or nothing came in 30 s, or 90 s while a request is still on its way.
	 */
	static boolean giveUp(long ageMs, String state) {
		if ("empty".equals(state)) return ageMs > 5_000L;
		if ("loading".equals(state)) return ageMs > 90_000L;
		return ageMs > 30_000L;
	}

	/** Re-check alerts that went out as "checking…". */
	private static void retryPending() {
		List<Object[]> todo;
		synchronized (pending) {
			if (pending.isEmpty()) return;
			todo = new ArrayList<>(pending);
			pending.clear();
		}
		long now = System.currentTimeMillis();
		for (Object[] p : todo) {
			Pre pre = (Pre) p[0];
			Object v = evaluate(pre, cfg);
			if (v == PENDING) {
				long age = now - (long) p[1];
				String st = NefClient.cofl == null ? "none" : NefClient.cofl.state(pre.key());
				if (giveUp(age, st)) {
					update(pre.uuid(), "empty".equals(st) || "ready".equals(st) ? "bad: no sales history to check it against"
							: "unchecked: couldn't load its sales history in time, check the price yourself");
					continue;
				}
				if (!"loading".equals(st) && now - (long) p[2] > 5_000L && NefClient.cofl != null) {   // a fetch failed: try again
					p[2] = now;
					NefClient.cofl.urgent(pre.key());
				}
				synchronized (pending) { pending.add(p); }
			} else if (v instanceof Alert al) update(pre.uuid(), "ok", al);
			else update(pre.uuid(), "bad: " + v);
		}
	}

	private static void update(String uuid, String status) {
		update(uuid, status, null);
	}

	private static void update(String uuid, String status, Alert checked) {
		Alert now = null;
		synchronized (Sniper.class) {
			for (int i = 0; i < alerts.size(); i++) {
				Alert old = alerts.get(i);
				if (!old.uuid().equals(uuid)) continue;
				Alert base = checked != null ? checked : old;          // checked numbers (real sale prices) when we have them
				now = new Alert(base.uuid(), base.name(), base.key(), base.count(), base.price(), base.resale(), base.profit(),
						base.pct(), status, old.at());
				alerts.set(i, now);
			}
		}
		if (now == null) return;
		final Alert a = now;
		Minecraft.getInstance().execute(() -> {
			if (a.status().equals("ok") && wantsOpen(a, true)) {        // "after ✓" mode: open now that it's checked
				var scr = BidHelper.currentScreen();
				boolean free = scr == null || scr instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
				if (free) open(a);
				else pendingOpen = new Object[]{a, System.currentTimeMillis()};
			}
			if (a.status().equals("ok")) {
				Chat.say(HudFormatter.line("[NEF] ✓ checked: ", HudFormatter.GREEN, a.name() + " is a real snipe (sales history, not manipulated)"
						+ String.format(Locale.ROOT, ", resell ≈ %s, +%s after fees", HudFormatter.fmt(a.resale()), HudFormatter.fmt(a.profit())), HudFormatter.WHITE)
						.append(HudFormatter.openAuction(a.uuid())));
			} else if (once(a.name() + "|" + a.status())) {         // the same item failing again: no new line
				boolean unchecked = a.status().startsWith("unchecked");
				Chat.say(HudFormatter.line(unchecked ? "[NEF] ? NOT CHECKED " : "[NEF] ✗ DON'T BUY ", unchecked ? HudFormatter.GOLD : HudFormatter.RED,
						a.name() + ": " + reason(a.status()), HudFormatter.WHITE));
			}
		});
	}

	private static final Map<String, Long> said = new java.util.concurrent.ConcurrentHashMap<>();

	/** True the first time in 2 minutes for this item and verdict. */
	static boolean once(String what) {
		long now = System.currentTimeMillis();
		said.values().removeIf(t -> now - t > 120_000L);
		return said.putIfAbsent(what, now) == null;
	}

	/** "bad: too few sales" → "too few sales". */
	static String reason(String status) {
		return status.replaceFirst("^(bad|unchecked): ", "");
	}

	/** judge() result: needs price data first (fetch started), try again shortly. */
	static final Alert PENDING = new Alert("", "", "", 0, 0, 0, 0, 0, "pending", 0);

	/** Any manipulation flag on the item (price spike or crash vs its week, lowest BIN far above sales, a buyout). */
	static boolean manipulated(JsonObject d) {
		if (Market.hasSevereFlag(d)) return true;
		JsonObject m = HudFormatter.obj(d, "market");
		if (m == null || !m.has("flags") || !m.get("flags").isJsonArray()) return false;
		for (var e : m.getAsJsonArray("flags")) {
			String code = e.isJsonObject() ? HudFormatter.str(e.getAsJsonObject(), "code") : null;
			if (code != null && java.util.Set.of("spike", "crash", "lbin_high", "buyout", "manip").contains(code)) return true;
		}
		return false;
	}

	/** Full check (see class doc): an "ok" Alert, null when it simply isn't a snipe… used by tests and the chat path. */
	static Alert judge(JsonObject a, NefConfig cfg) {
		Pre pre = pre(a, cfg);
		if (pre == null) return null;
		Object v = evaluate(pre, cfg);
		return v instanceof Alert al ? al : v == PENDING ? PENDING : null;
	}

	static Pre pre(JsonObject a, NefConfig cfg) {
		if (!a.has("bin") || !a.get("bin").getAsBoolean() || a.has("claimed") && a.get("claimed").getAsBoolean()) return null;
		if (!a.has("item_bytes") || !a.has("starting_bid") || !a.has("uuid")) return null;
		long total = a.get("starting_bid").getAsLong();
		Double purse = FcfPanel.purse();
		if (purse != null && total > purse) return null;
		if (cfg.snipeMaxPrice > 0 && total > cfg.snipeMaxPrice) return null;
		String[] key = {null};
		int[] count = {1};
		String[] variant = {""};
		Spec[] spec = {null};
		try {
			AhScanner.identify(a.get("item_bytes").getAsString(), key, count, variant, spec);
		} catch (Exception e) {
			return null;
		}
		String k = key[0];
		if (blocked(k, cfg)) return null;
		String bucket = variant[0] == null || variant[0].isEmpty() ? k : k + "#" + variant[0];
		double[] r = refs.get(bucket);
		if (r == null || r[2] < 3 || r[0] < Math.max(cfg.snipeMinValue, cfg.minItemValue)) return null;
		long n = Math.max(1, count[0]);
		double unit = (double) total / n;
		double need = Math.max(0, Math.min(0.95, cfg.snipeMinPercent / 100.0));
		if (unit > r[0] * (1 - need)) return null;                    // e.g. 50 % → at most half the lowest BIN
		String name = a.has("item_name") ? a.get("item_name").getAsString().replaceAll("§.", "") : k;
		return new Pre(a.get("uuid").getAsString(), k, bucket, n, total, unit, r, name, n == 1 ? spec[0] : null);
	}

	/** An Alert ("ok"), PENDING (history loading), or a String reason why it's not a buy. */
	static Object evaluate(Pre p, NefConfig cfg) {
		double[] r = p.ref();
		double ref = Math.min(r[0], r[1]);
		JsonObject d = NefClient.docFor(p.key(), true);
		List<double[]> week = NefClient.cofl == null ? null : NefClient.cofl.weekClean(p.key(), true);
		if (d == null || week == null) return PENDING;
		if (manipulated(d)) return "the market is manipulated (spike / crash / buyout / inflated listings)";
		Market.Erratic er = Market.erratic(week);
		if (er.bad()) return "its sales are all over the place (" + HudFormatter.fmt(er.low()) + "–" + HudFormatter.fmt(er.high())
				+ "): coin transfers / manipulation";
		if (Market.salesPerDay(d) < cfg.snipeMinSalesPerDay) return "it barely sells (" + String.format(Locale.ROOT, "%.1f", Market.salesPerDay(d)) + "/day)";
		Double fair = Verdict.fair(d);
		double floor = FlipFeed.clearingFloor(week);
		if (fair == null || fair <= 0 || floor <= 0) return "no real sales to price it against";
		// the "lowest BIN" itself can be the manipulation (cheap copies bought out, the rest relisted high)
		if (r[0] > Math.min(fair, floor) * 1.5) return "the lowest BIN is inflated vs real sales (" + HudFormatter.fmt(Math.min(fair, floor)) + ")";
		double resale = Math.min(Market.undercut(ref), Math.min(fair, Market.undercut(floor)));
		double cov = coverPrice(p);
		if (cov > 0) resale = Math.min(resale, Market.undercut(cov));     // a copy with the same or better upgrades is cheaper
		double npc = NpcPrices.price(p.key());
		if (npc > 0) resale = Math.min(resale, npc);                      // an NPC sells it for less: nobody pays more
		double profit = Verdict.netAfterFees(resale * p.count()) - p.total();
		double pct = profit / p.total();
		if (profit < cfg.minFlipProfitCoins || pct < 0.05) return "too little profit after fees at real sale prices";
		synchronized (Sniper.class) {                                  // the new copy is now the cheapest one
			Map<String, double[]> m = new HashMap<>(refs);
			m.put(p.bucket(), new double[]{p.unit(), ref, r[2] + 1});
			refs = m;
		}
		return new Alert(p.uuid(), p.name(), p.key(), p.count(), p.total(), resale * p.count(), profit, pct, "ok", System.currentTimeMillis());
	}

	/** The instant alert while history loads: resale just under the next copies, still has to clear the profit bar. */
	private static Alert provisional(Pre p, NefConfig cfg) {
		double one = Math.min(p.ref()[0], p.ref()[1]);
		double cov = coverPrice(p);
		if (cov > 0) one = Math.min(one, cov);
		double npc = NpcPrices.price(p.key());
		if (npc > 0) one = Math.min(one, npc);
		double resale = Market.undercut(one) * p.count();
		double profit = Verdict.netAfterFees(resale) - p.total();
		if (profit < cfg.minFlipProfitCoins || profit / p.total() < 0.05) return null;
		return new Alert(p.uuid(), p.name(), p.key(), p.count(), p.total(), resale, profit, profit / p.total(), "checking", System.currentTimeMillis());
	}

	/** Cheapest other BIN of a copy with the same or better upgrades (0 = none / unknown). */
	private static double coverPrice(Pre p) {
		double[] c = p.spec() == null ? null : MarketBook.cover(p.key(), p.spec(), p.unit());
		return c == null ? 0 : c[0];
	}

	private static volatile long autoOpenedAt;

	private static void fire(Alert al) {
		// never post an empty alert (the "history loading" placeholder, or nothing to gain)
		if (al == null || al == PENDING || al.uuid().isEmpty() || !(al.price() > 0) || !(al.profit() > 0)) return;
		synchronized (Sniper.class) {
			alerts.add(al);
			if (alerts.size() > 20) alerts.remove(0);
		}
		scheduleBeats(al);
		Minecraft.getInstance().execute(() -> {
			Chat.ping();
			long now = System.currentTimeMillis();
			var scr = BidHelper.currentScreen();                          // never while typing (chat, signs); menus are fine
			boolean free = scr == null || scr instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
			if (wantsOpen(al, false)) {
				if (free && now - autoOpenedAt > 3_000L) open(al);
				else pendingOpen = new Object[]{al, now};               // chat / sign open: open as soon as it closes
			}
			Chat.say(HudFormatter.line("[NEF] ⚡ UNDERVALUED BIN ", HudFormatter.GOLD, al.name() + " " + HudFormatter.fmt(al.price()), HudFormatter.WHITE,
					String.format(Locale.ROOT, " → resell ≈ %s, +%s (%.0f%%) after fees", HudFormatter.fmt(al.resale()), HudFormatter.fmt(al.profit()), al.pct() * 100),
					HudFormatter.GREEN, al.status().equals("ok") ? "  ✓ checked" : "  checking history… wait for ✓ before you confirm", HudFormatter.GRAY)
					.append(HudFormatter.openAuction(al.uuid())));
		});
	}

	private static volatile Object[] pendingOpen;                   // {Alert, when}
	private static final java.util.Set<String> autoOpened = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** % under the lowest BIN of identical copies (the resale is just under it). */
	static double offPercent(Alert a) {
		double ref = a.resale() / 0.99;
		return ref > 0 ? 100 * (1 - a.price() / ref) : 0;
	}

	/**
	 * Should this alert open the auction by itself? Only with auto-open on, within your limits (how far under the
	 * lowest BIN, minimum profit, maximum price / your purse) and, if you chose "after ✓", once it has been checked.
	 */
	static boolean wantsOpen(Alert a, boolean justChecked) {
		if (!cfg.snipeAutoOpen || a == null || autoOpened.contains(a.uuid())) return false;
		boolean ok = a.status().equals("ok");
		if (a.status().startsWith("bad") || a.status().startsWith("unchecked")) return false;
		if (cfg.snipeOpenChecked && !ok) return false;
		if (!cfg.snipeOpenChecked && justChecked) return false;         // it was opened (or skipped) when it first came in
		if (offPercent(a) + 0.01 < cfg.snipeOpenMinPercent) return false;
		if (a.profit() < cfg.snipeOpenMinProfit) return false;
		Double purse = FcfPanel.purse();
		double max = cfg.snipeOpenMaxPrice > 0 ? cfg.snipeOpenMaxPrice : purse != null ? purse : Double.MAX_VALUE;
		return a.price() <= max;
	}

	private static void open(Alert a) {
		autoOpenedAt = System.currentTimeMillis();
		autoOpened.add(a.uuid());
		if (autoOpened.size() > 500) autoOpened.clear();
		pendingOpen = null;
		Holdings.intend(a.name(), a.key(), "Snipe", a.resale() / Math.max(1, a.count()));
		Cmd.viewAuction(a.uuid());                                     // the BIN view pops up; buying stays your click
	}

	/** Every client tick: an auto-open that had to wait (chat was open) happens once you close it, within 15 s. */
	static void clientTick() {
		Object[] po = pendingOpen;
		if (po == null) return;
		long now = System.currentTimeMillis();
		if (now - (long) po[1] > 15_000L) { pendingOpen = null; return; }
		var scr = BidHelper.currentScreen();
		boolean free = scr == null || scr instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
		if (free && now - autoOpenedAt > 3_000L) open((Alert) po[0]);
	}

	static void openLatest() {
		Alert a = latest();
		if (a != null) {
			Holdings.intend(a.name(), a.key(), "Snipe", a.resale() / Math.max(1, a.count()));
			Cmd.viewAuction(a.uuid());
		}
	}

	// ------------------------------------------------------------------ banner
	/** Top-centre banner while the newest alert is younger than 12 s. Also drawn over menus (click it to open). */
	static void paint(Highlight.Painter p, int screenW) {
		Alert a = latest();
		if (a == null || System.currentTimeMillis() - a.at() > SHOW_MS) return;
		Minecraft mc = Minecraft.getInstance();
		String l1 = "⚡ UNDERVALUED BIN  " + a.name() + (a.count() > 1 ? " ×" + a.count() : "");
		boolean ok = a.status().equals("ok"), checking = a.status().equals("checking");
		boolean unchecked = a.status().startsWith("unchecked");
		String l2 = unchecked ? "? NOT CHECKED: " + reason(a.status())
				: !ok && !checking ? "✗ DON'T BUY: " + reason(a.status())
				: String.format(Locale.ROOT, "%s → resell ≈ %s   +%s (%.0f%%) after fees · %s", HudFormatter.fmt(a.price()),
						HudFormatter.fmt(a.resale()), HudFormatter.fmt(a.profit()), a.pct() * 100,
						ok ? "✓ checked, not manipulated" : "checking history…");
		String cd = countdown(graceMs(a.uuid()));
		String l3 = cd != null && !cd.equals("BUY NOW") ? "Buyable in " + cd + ": mouse on Buy Item Right Now, click on the GO beat"
				: "BUY NOW".equals(cd) ? "BUY NOW: Buy Item Right Now, then Confirm"
				: BidHelper.currentScreen() == null ? "Type /nef snipe to open it (or open any menu and click this)"
				: cfg.snipeAutoOpen && System.currentTimeMillis() - autoOpenedAt < SHOW_MS ? "Opened: press Buy Item Right Now, then Confirm"
				: "Click here to open it";
		int w = Math.max(mc.font.width(l1), Math.max(mc.font.width(l2), mc.font.width(l3))) + 16;
		int x = (screenW - w) / 2, y = 4;
		long age = System.currentTimeMillis() - a.at();
		int border = unchecked ? 0xFFFFAA00 : !ok && !checking ? 0xFFFF5555 : (age / 400) % 2 == 0 ? 0xFFFFAA00 : 0xFFFFD35C;   // blinks; red = don't
		p.fill(x - 1, y - 1, x + w + 1, y + 37, border);
		p.fill(x, y, x + w, y + 36, 0xF0101418);
		p.text(l1, x + 8, y + 3, 0xFFFFAA00);
		p.text(l2, x + 8, y + 14, ok ? 0xFF55FF55 : checking ? 0xFFFFFF55 : 0xFFFF5555);
		p.text(l3, x + 8, y + 25, 0xFFAAAAAA);
		bannerBox = new int[]{x, y, x + w, y + 36};
		bannerAt = System.currentTimeMillis();
	}

	private static volatile Object viewItem;                   // the BIN being shown, and what it's worth flipping
	private static volatile Double viewProfit;
	private static volatile long viewAt, profitSeenAt;

	/**
	 * Hypixel's "BIN Auction View": if the item shown is a real flip (the same verdict as its tooltip: profit after fees,
	 * sells often enough, reliable price, not manipulated — or a ✓ snipe alert), "Buy Item Right Now" gets a thick
	 * blinking green box with the profit next to it; the "Confirm" button that follows gets the same. Nothing for
	 * listings that aren't profitable or are still being checked.
	 */
	static void paintButtons(Highlight.Painter p, net.minecraft.client.gui.screens.Screen s) {
		if (!(s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs)) return;
		if (Guard.paint(p, s)) return;                                 // a trap warning replaces the green highlight
		String t = s.getTitle().getString().toLowerCase(Locale.ROOT);
		boolean view = t.contains("auction view"), confirm = t.contains("confirm");
		if (!view && !confirm) return;
		long now = System.currentTimeMillis();
		Double profit = null;
		if (view) {
			for (net.minecraft.world.inventory.Slot sl : acs.getMenu().slots) {      // the auctioned item: lore has "Buy it now"
				var st = sl.getItem();
				if (st == null || st.isEmpty()) continue;
				var lore = st.get(net.minecraft.core.component.DataComponents.LORE);
				if (lore == null || lore.lines().stream().noneMatch(c -> c.getString().replaceAll("§.", "").startsWith("Buy it now"))) continue;
				if (st != viewItem || now - viewAt > 1000) {                       // re-check once a second
					viewItem = st;
					viewAt = now;
					Alert a = latest();
					boolean alertOk = a != null && now - a.at() < 60_000 && a.status().equals("ok")
							&& st.getHoverName().getString().replaceAll("§.", "").contains(a.name());
					Double v = NefClient.binProfit(st);
					viewProfit = v != null ? v : alertOk ? a.profit() : null;
				}
				profit = viewProfit;
				break;
			}
			if (profit != null) profitSeenAt = now;
		} else if (now - profitSeenAt < 15_000) {
			profit = viewProfit;                                                     // Confirm right after a profitable view
		}
		if (profit == null) return;
		int[] box = GuiPos.box(s);
		int col = (now / 250) % 2 == 0 ? 0xFF55FF55 : 0xFFFFFFFF;
		for (net.minecraft.world.inventory.Slot sl : acs.getMenu().slots) {
			if (sl.getItem() == null || sl.getItem().isEmpty()) continue;
			String n = sl.getItem().getHoverName().getString().replaceAll("§.", "").toLowerCase(Locale.ROOT);
			if (!n.contains("buy item right now") && !n.equals("confirm")) continue;
			int x = box[0] + sl.x, y = box[1] + sl.y;
			for (int k = 1; k <= 3; k++) {
				p.fill(x - k, y - k, x + 16 + k, y - k + 1, col);
				p.fill(x - k, y + 16 + k - 1, x + 16 + k, y + 16 + k, col);
				p.fill(x - k, y - k, x - k + 1, y + 16 + k, col);
				p.fill(x + 16 + k - 1, y - k, x + 16 + k, y + 16 + k, col);
			}
			String label = "+" + HudFormatter.fmt(profit) + " profit";
			int lw = Minecraft.getInstance().font.width(label);
			p.fill(x + 8 - lw / 2 - 3, y + 21, x + 8 + lw / 2 + 3, y + 32, 0xE0103010);
			p.text(label, x + 8 - lw / 2, y + 23, 0xFF55FF55);
			Alert la = latest();
			String cd = view && la != null && now - la.at() < 60_000 ? countdown(graceMs(la.uuid())) : null;
			if (cd != null) {                                                        // the hold, counted down on the button
				boolean go = cd.equals("BUY NOW");
				int cw = Minecraft.getInstance().font.width(cd);
				p.fill(x + 8 - cw / 2 - 3, y - 16, x + 8 + cw / 2 + 3, y - 5, go ? 0xF0105010 : 0xF0402010);
				p.text(cd, x + 8 - cw / 2, y - 14, go ? 0xFF55FF55 : 0xFFFFAA00);
			}
		}
	}

	private static volatile int[] bannerBox;
	private static volatile long bannerAt;

	/** In a menu: a click on the banner opens the auction. True when it was used. */
	static boolean click(double mx, double my) {
		int[] b = bannerBox;
		if (b == null || System.currentTimeMillis() - bannerAt > 500) return false;
		if (mx < b[0] || mx > b[2] || my < b[1] || my > b[3]) return false;
		openLatest();
		return true;
	}
}

package dev.notenoughflips;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Watch list for auctions you bid on: the lowest BIN of each watched item is re-checked every
 * {@code watchRecheckSeconds} (fresh from an optional local server, which re-checks the AH every 30 s;
 * otherwise from the hourly published data) and you get a chat message when it moves enough to
 * change whether your bid is still profitable. Items are added when you click an auction
 * (bid copied), and expire after {@code watchHours}.
 */
final class Watch {
	static final class Entry {
		String key;
		double myBid;
		double maxProfitable;
		long added;
		Double lastLowest;
	}

	private static final Map<String, Entry> entries = new ConcurrentHashMap<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file;
	private static NefConfig cfg;
	private static DataClient data;

	private Watch() {}

	static void start(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
		reload();
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "NEF-watch");
			t.setDaemon(true);
			return t;
		});
		int every = Math.max(10, cfg.watchRecheckSeconds);
		ex.scheduleWithFixedDelay(Watch::recheck, every, every, TimeUnit.SECONDS);
	}

	/** Load the watched auctions of the account that is playing (each account has its own list). */
	static synchronized void reload() {
		entries.clear();
		file = Accounts.file("watch.json");
		try {
			if (Files.exists(file)) {
				List<Entry> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() {}.getType());
				if (list != null) for (Entry e : list) if (e != null && e.key != null) entries.put(e.key, e);
			}
		} catch (Exception e) {
			System.err.println("[NEF] could not read watch list: " + e);
		}
	}

	static void add(String key, double myBid, double maxProfitable, Double lowestNow) {
		Entry e = new Entry();
		e.key = key;
		e.myBid = myBid;
		e.maxProfitable = maxProfitable;
		e.added = System.currentTimeMillis();
		e.lastLowest = lowestNow;
		entries.put(key, e);
		save();
	}

	static boolean remove(String key) {
		boolean had = entries.remove(key) != null;
		save();
		return had;
	}

	static void clear() {
		entries.clear();
		save();
	}

	static List<Entry> list() {
		return new ArrayList<>(entries.values());
	}

	private static void save() {
		try {
			if (file != null) Files.writeString(file, GSON.toJson(new ArrayList<>(entries.values())), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not critical
		}
	}

	/** "[search AH]" link for a watched item. */
	private static net.minecraft.network.chat.MutableComponent ahLink(String key) {
		String name = Desk.pretty(key).replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
		return HudFormatter.link(" [search AH]", HudFormatter.AQUA, "/ahs " + name, "/ahs " + name);
	}

	private static void say(Component c) {
		Chat.say(c);
	}

	private static void recheck() {
		try {
			long now = System.currentTimeMillis();
			entries.values().removeIf(e -> now - e.added > cfg.watchHours * 3_600_000L);
			if (entries.isEmpty()) return;
			List<String> keys = new ArrayList<>(entries.keySet());
			JsonObject fresh = data.localLbin(keys).get(10, TimeUnit.SECONDS);   // null if the optional local server isn't running
			for (String k : keys) {
				Double lowest = null;
				MarketBook.Item book = MarketBook.get(k);
				if (fresh != null && fresh.has(k)) {
					lowest = HudFormatter.num(fresh.getAsJsonObject(k), "lowest");
				} else if (book != null && now - MarketBook.updated() < 20 * 60_000L) {
					lowest = book.lowest();                                // your own AH scan, if it is recent
				} else {
					DataClient.Doc d = data.docAsync(k).get(10, TimeUnit.SECONDS);
					if (d != null && d.json() != null) {
						JsonObject li = HudFormatter.obj(d.json(), "market", "listed");
						lowest = li != null ? HudFormatter.num(li, "lowest") : HudFormatter.num(HudFormatter.obj(d.json(), "summary"), "lbin");
					}
				}
				Entry e = entries.get(k);
				if (e == null || lowest == null) continue;
				check(e, lowest);
			}
			save();
		} catch (Exception ignored) {
			// try again next round
		}
	}

	private static void check(Entry e, double lowest) {
		Double prev = e.lastLowest;
		e.lastLowest = lowest;
		if (prev == null || prev <= 0) return;
		double change = lowest / prev - 1;
		if (Math.abs(change) < 0.02) return;                       // ignore small moves
		double minPct = Math.max(0, cfg.minFlipProfitPercent) / 100.0;
		double maxNow = Math.min(e.maxProfitable * Math.max(1, lowest / prev),
				Verdict.netAfterFees(lowest * 0.99) / (1 + minPct));
		String head = "[NEF] " + e.key + ": ";
		if (maxNow < e.myBid) {
			say(HudFormatter.line(head, HudFormatter.GOLD,
					String.format(Locale.ROOT, "⚠ lowest BIN dropped to %s (was %s). Your bid %s is no longer profitable "
							+ "(max now %s). Don't raise it.", HudFormatter.fmt(lowest), HudFormatter.fmt(prev),
							HudFormatter.fmt(e.myBid), HudFormatter.fmt(maxNow)), HudFormatter.RED).append(ahLink(e.key)));
		} else if (change < 0) {
			say(HudFormatter.line(head, HudFormatter.GOLD,
					String.format(Locale.ROOT, "↓ lowest BIN %s (was %s, %.1f%%). Still profitable up to %s.",
							HudFormatter.fmt(lowest), HudFormatter.fmt(prev), change * 100, HudFormatter.fmt(maxNow)), HudFormatter.YELLOW).append(ahLink(e.key)));
		} else {
			say(HudFormatter.line(head, HudFormatter.GOLD,
					String.format(Locale.ROOT, "↑ lowest BIN %s (was %s, +%.1f%%). More margin: max profitable bid now %s.",
							HudFormatter.fmt(lowest), HudFormatter.fmt(prev), change * 100, HudFormatter.fmt(maxNow)), 0x55FF55).append(ahLink(e.key)));
		}
		e.maxProfitable = Math.max(0, maxNow);
	}
}

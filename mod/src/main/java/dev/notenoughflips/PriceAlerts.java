package dev.notenoughflips;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Your own price alerts: "tell me when Hyperion goes under 600M" or "when Booster Cookie goes over 3M".
 *
 *   /nef alert under 600m hyperion      /nef alert over 3m booster cookie      /nef alert under 50m hand
 *   /nef alert list · /nef alert remove <n> · /nef alert clear
 *
 * Auction items: the lowest BIN (every new listing is checked live for "under", within seconds; the full AH scan
 * every 15 min covers both). Bazaar products: the instant-buy price, checked every 30 s. Each alert fires once, then
 * re-arms when the price moves back past the line (2 %). Kept per account (config/notenoughflips/accounts/<uuid>).
 */
final class PriceAlerts {
	static final class Alert {
		String key, name;
		boolean under;
		double price;
		boolean fired;

		Alert() {}

		Alert(String key, String name, boolean under, double price) {
			this.key = key;
			this.name = name;
			this.under = under;
			this.price = price;
		}

		String describe() {
			return name + (under ? " under " : " over ") + HudFormatter.fmt(price);
		}
	}

	private static final Gson GSON = new Gson();
	private static final Type LIST = new TypeToken<List<Alert>>() {}.getType();
	private static List<Alert> alerts = new ArrayList<>();
	private static Path loadedFrom;

	private PriceAlerts() {}

	private static synchronized List<Alert> all() {
		Path f = Accounts.file("price-alerts.json");
		if (!f.equals(loadedFrom)) {                          // first use, or another account
			loadedFrom = f;
			alerts = new ArrayList<>();
			try {
				if (Files.exists(f)) {
					List<Alert> l = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), LIST);
					if (l != null) alerts = new ArrayList<>(l);
				}
			} catch (Exception ignored) {
				// start empty
			}
		}
		return alerts;
	}

	private static synchronized void save() {
		try {
			Files.writeString(Accounts.file("price-alerts.json"), GSON.toJson(alerts), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not critical
		}
	}

	static synchronized List<Alert> list() {
		return new ArrayList<>(all());
	}

	/** Item id for what you typed: "hand" = the item you're holding, else a display name or an id. Null if unknown. */
	static String resolve(String typed) {
		String t = typed.trim();
		if (t.equalsIgnoreCase("hand")) {
			var p = Minecraft.getInstance().player;
			return p == null ? null : ItemKeys.keyFor(p.getMainHandItem());
		}
		DataClient d = NefClient.data();
		Map<String, String> names = d == null ? null : d.itemNames();
		if (names != null) {
			String id = names.get(t.toLowerCase(Locale.ROOT));
			if (id != null) return id;
		}
		return t.toUpperCase(Locale.ROOT).replace(' ', '_');
	}

	static synchronized String add(String typed, boolean under, double price) {
		String key = resolve(typed);
		if (key == null) return null;
		String name = typed.equalsIgnoreCase("hand") ? Desk.pretty(key) : typed.trim();
		all().add(new Alert(key, name, under, price));
		save();
		return name;
	}

	static synchronized boolean remove(int index) {
		List<Alert> l = all();
		if (index < 0 || index >= l.size()) return false;
		l.remove(index);
		save();
		return true;
	}

	static synchronized void clear() {
		all().clear();
		save();
	}

	/** Pure: has the price crossed the line? */
	static boolean crossed(boolean under, double target, double price) {
		return price > 0 && (under ? price <= target : price >= target);
	}

	/** Pure: back on the other side by 2 %: the alert can fire again. */
	static boolean rearm(boolean under, double target, double price) {
		return price > 0 && (under ? price > target * 1.02 : price < target * 0.98);
	}

	/** The price an alert watches right now: bazaar instant-buy, else the lowest BIN from the last AH scan. 0 = unknown. */
	private static double now(String key) {
		DataClient d = NefClient.data();
		DataClient.Live l = d == null ? null : d.live(key);
		if (l != null && l.buy() > 0) return l.buy();
		MarketBook.Item b = MarketBook.get(key);
		return b == null ? 0 : b.lowest();
	}

	/** Every 30 s (and after each AH scan). */
	static synchronized void check() {
		boolean changed = false;
		for (Alert a : all()) {
			double p = now(a.key);
			if (!a.fired && crossed(a.under, a.price, p)) {
				a.fired = true;
				changed = true;
				fire(a, p, null);
			} else if (a.fired && rearm(a.under, a.price, p)) {
				a.fired = false;
				changed = true;
			}
		}
		if (changed) save();
	}

	/** A new AH listing from the live feed: fires "under" alerts straight away. */
	static void checkListing(String key, double unit, String uuid) {
		List<Alert> hit = new ArrayList<>();
		synchronized (PriceAlerts.class) {
			for (Alert a : all()) {
				if (a.under && !a.fired && a.key.equals(key) && crossed(true, a.price, unit)) {
					a.fired = true;
					hit.add(a);
				}
			}
			if (!hit.isEmpty()) save();
		}
		for (Alert a : hit) fire(a, unit, uuid);
	}

	static boolean any() {
		return !list().isEmpty();
	}

	private static void fire(Alert a, double price, String uuid) {
		boolean bz = NefClient.data() != null && NefClient.data().live(a.key) != null;
		var line = Component.empty().append(HudFormatter.line("[NEF] Price alert: ", HudFormatter.GOLD, a.name, HudFormatter.WHITE,
				" is " + (a.under ? "down to " : "up to ") + HudFormatter.fmt(price) + " (your line: " + HudFormatter.fmt(a.price) + ") ",
				a.under ? HudFormatter.GREEN : HudFormatter.YELLOW));
		if (uuid != null) line.append(HudFormatter.openAuction(uuid));
		else if (bz) line.append(HudFormatter.openBazaar(Bazaar.name(a.key, NefClient.data())));
		else line.append(HudFormatter.link(" [search AH]", HudFormatter.AQUA, "/ahs " + Cmd.clean(a.name), "/ahs " + a.name));
		Alerts.alert(line);
	}
}

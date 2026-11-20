package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;

/**
 * "Your listing got undercut": a chat alert (+ ping) when someone lists a copy with the same or better upgrades for
 * less than your BIN, so yours won't sell until you relist. Checked on every full AH scan (your BINs against every
 * listing) and live on every new listing the sniper's feed sees (within seconds). One alert per cheaper price.
 * Read-only: the mod never relists for you; the alert links the cheaper auction and your own auctions.
 */
final class Undercut {
	/** One of your BIN listings. */
	record Mine(String uuid, String key, String name, double unit, Spec spec) {}

	private static volatile List<Mine> mine = List.of();
	private static final Set<String> alerted = ConcurrentHashMap.newKeySet();
	private static NefConfig cfg;

	private Undercut() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	static boolean watching() {
		return cfg != null && cfg.undercutAlerts && !mine.isEmpty();
	}

	static List<Mine> mine() {
		return mine;
	}

	/** After a full scan: your current BINs; each is checked against everything listed right now. */
	static void setMine(List<Mine> list) {
		mine = List.copyOf(list);
		if (cfg == null || !cfg.undercutAlerts) return;
		for (Mine m : list) {
			double[] c = MarketBook.cover(m.key(), m.spec(), m.unit());
			if (c != null && undercuts(c[0], m.unit())) alert(m, c[0], null);
		}
	}

	/** Pure: does a listing at {@code price} undercut yours at {@code mine}? (more than a rounding difference) */
	static boolean undercuts(double price, double mine) {
		return price > 0 && price < mine * 0.999 - 1;
	}

	/** A brand-new listing from the live feed: does it undercut one of yours? */
	static void check(JsonObject a) {
		List<Mine> list = mine;
		if (list.isEmpty() || cfg == null || !cfg.undercutAlerts) return;
		if (!a.has("bin") || !a.get("bin").getAsBoolean() || !a.has("item_bytes") || !a.has("starting_bid") || !a.has("uuid")) return;
		String[] key = {null};
		int[] count = {1};
		String[] variant = {""};
		Spec[] spec = {null};
		try {
			AhScanner.identify(a.get("item_bytes").getAsString(), key, count, variant, spec);
		} catch (Exception e) {
			return;
		}
		if (key[0] == null || spec[0] == null) return;
		double unit = a.get("starting_bid").getAsDouble() / Math.max(1, count[0]);
		String uuid = a.get("uuid").getAsString();
		for (Mine m : list) {
			if (!m.key().equals(key[0]) || m.uuid().equals(uuid)) continue;
			if (undercuts(unit, m.unit()) && spec[0].covers(m.spec())) alert(m, unit, uuid);
		}
	}

	private static void alert(Mine m, double price, String theirs) {
		if (!alerted.add(m.uuid() + "|" + Math.round(price))) return;
		if (alerted.size() > 2000) alerted.clear();
		String name = m.name() == null ? Desk.pretty(m.key()) : m.name().replaceAll("§.", "");
		net.minecraft.network.chat.MutableComponent line = Component.empty().append(HudFormatter.line("[NEF] Undercut: ", HudFormatter.GOLD, name, HudFormatter.WHITE,
				String.format(Locale.ROOT, " · yours %s, a copy with the same or better upgrades is listed for %s (%.0f%% less) ",
						HudFormatter.fmt(m.unit()), HudFormatter.fmt(price), 100 * (1 - price / m.unit())), HudFormatter.YELLOW));
		if (theirs != null) line.append(HudFormatter.openAuction(theirs));
		line.append(HudFormatter.link(" [your auctions]", HudFormatter.AQUA, "/ah", "Open the AH to relist"));
		Alerts.alert(line);
	}
}

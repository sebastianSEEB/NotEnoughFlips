package dev.notenoughflips;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * What you bought and why. When you act on something the mod suggested (an AH flip, a snipe, a cheap BIN from DEALS,
 * a bazaar flip, or a bazaar mispricing from Tips), the mod remembers the item, why, and the price it expects it to go
 * to. When your purchase shows up in chat (ProfitLog), the two are linked, so the Trade Desk can show for every item
 * you still hold: what you paid, what it's worth now, the target, and how far it has come; and when you sell it,
 * the profit is booked under that reason. Kept per account (accounts/<uuid>/plans.json).
 */
final class Holdings {
	/** source: "AH flip", "Snipe", "Deal", "Bazaar flip", "Mispricing". target = expected unit price when sold. */
	static final class Plan {
		String item, key, source;
		double target;
		long created, buyTs;
	}

	private static final long WAIT_MS = 45 * 60_000L;            // a plan without a buy within 45 min is dropped
	private static final Gson GSON = new Gson();
	private static List<Plan> plans = new ArrayList<>();
	private static String loadedFor;

	private Holdings() {}

	private static synchronized void load() {
		if (Accounts.id().equals(loadedFor)) return;
		loadedFor = Accounts.id();
		plans = new ArrayList<>();
		try {
			var f = Accounts.file("plans.json");
			if (Files.exists(f)) {
				List<Plan> l = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Plan>>() {}.getType());
				if (l != null) plans = new ArrayList<>(l);
			}
		} catch (Exception ignored) {
			// start empty
		}
	}

	private static synchronized void save() {
		long cut = System.currentTimeMillis() - 30L * 86_400_000L;
		plans.removeIf(p -> p.buyTs == 0 ? System.currentTimeMillis() - p.created > WAIT_MS : p.buyTs < cut);
		try {
			Files.writeString(Accounts.file("plans.json"), GSON.toJson(plans), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not critical
		}
	}

	/** You're about to buy this because of `source`, expecting to sell each at `targetUnit`. */
	static synchronized void intend(String itemName, String key, String source, double targetUnit) {
		if (itemName == null || targetUnit <= 0) return;
		load();
		Plan p = new Plan();
		p.item = itemName;
		p.key = key;
		p.source = source;
		p.target = targetUnit;
		p.created = System.currentTimeMillis();
		plans.add(p);
		save();
	}

	static String norm(String s) {
		return s == null ? "" : s.replaceAll("§.", "").replaceAll("[^A-Za-z ]", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}

	private static boolean same(String tradeItem, Plan p) {
		String a = norm(tradeItem), b = norm(p.item), k = p.key == null ? "" : norm(Desk.pretty(p.key));
		return !a.isEmpty() && (a.equals(b) || a.contains(b) && !b.isEmpty() || b.contains(a) || !k.isEmpty() && a.contains(k));
	}

	/** A buy came in (ProfitLog): link it to the newest waiting plan for that item. */
	static synchronized void onBuy(ProfitLog.Trade t) {
		load();
		long now = t.ts();
		Plan best = null;
		for (Plan p : plans) if (p.buyTs == 0 && now - p.created < WAIT_MS && same(t.item(), p)) best = p;
		if (best == null) return;
		best.buyTs = t.ts();
		save();
	}

	/** The plan behind the buy at `buyTs` of `item`, or null (bought without a suggestion). */
	static synchronized Plan forBuy(long buyTs, String item) {
		load();
		for (Plan p : plans) if (p.buyTs == buyTs && same(item, p)) return p;
		return null;
	}

	/** Realized profit and flip count per reason since `from` (sales matched to buys, first in first out). */
	static Map<String, double[]> realizedBySource(long from) {
		Map<String, double[]> out = new HashMap<>();
		for (ProfitLog.Flip f : ProfitLog.summary(from).recent()) {
			Plan p = forBuy(f.buyTs(), f.item());
			String src = p != null ? p.source : "AH".equals(f.market()) ? "Other AH" : "Other bazaar";
			double[] v = out.computeIfAbsent(src, k -> new double[2]);
			v[0] += f.profit();
			v[1]++;
		}
		return out;
	}
}

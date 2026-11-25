package dev.notenoughflips;

import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The SkyBlock calendar and the published seasonality (data/seasonality.json, tools/seasonality.py).
 *
 * A SkyBlock year is 124 real hours, cut into 62 two-hour slots. For every bazaar product with enough history the
 * data job measures the typical price shape of a year and the best buy slot → sell slot, with its gain and win rate
 * tested on years the pick never saw. "ok" = a pattern worth trading: ≥3 % median, ≥70 % of years, clear of the noise.
 */
final class Season {
	private Season() {}

	static final long EPOCH_S = 1_560_275_700L, YEAR_S = 124 * 3600L, SLOT_S = 2 * 3600L;
	static final int BINS = 62;
	static final String[] MONTHS = {"Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
			"Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"};

	/** Slot of the SkyBlock year (0..61) at a real time in ms. Pure. */
	static int bin(long ms) {
		return (int) (Math.floorMod(ms / 1000 - EPOCH_S, YEAR_S) / SLOT_S);
	}

	/** "Autumn 12, year 451" at a real time in ms. Pure. */
	static String date(long ms) {
		long into = Math.floorMod(ms / 1000 - EPOCH_S, YEAR_S);
		long day = into / 1200;                                  // a SkyBlock day is 20 real minutes
		long year = Math.floorDiv(ms / 1000 - EPOCH_S, YEAR_S) + 1;
		return MONTHS[(int) (day / 31)] + " " + (day % 31 + 1) + ", year " + year;
	}

	/** ms from now until slot b next starts (0 when we're in it). Pure. */
	static long until(long now, int b) {
		long into = Math.floorMod(now / 1000 - EPOCH_S, YEAR_S);
		long start = b * SLOT_S;
		if (into >= start && into < start + SLOT_S) return 0;
		return Math.floorMod(start - into, YEAR_S) * 1000;
	}

	/** "3h 20m", "2d 4h". Pure. */
	static String dur(long ms) {
		long m = ms / 60_000;
		if (m < 60) return m + "m";
		long h = m / 60;
		return h < 48 ? h + "h " + (m % 60) + "m" : h / 24 + "d " + (h % 24) + "h";
	}

	static JsonObject json() {
		return NefClient.data() == null ? null : NefClient.data().seasonality();
	}

	/** The published summary of one product, or null. */
	static JsonObject of(String id) {
		JsonObject j = json();
		if (j == null || id == null || !j.has("products")) return null;
		for (JsonElement e : j.getAsJsonArray("products")) {
			JsonObject o = e.getAsJsonObject();
			if (id.equals(HudFormatter.str(o, "id"))) return o;
		}
		return null;
	}

	static String label(int b) {
		JsonObject j = json();
		JsonArray l = j == null ? null : j.getAsJsonArray("labels");
		if (l != null && b >= 0 && b < l.size()) return l.get(b).getAsString();
		long day = b * SLOT_S / 1200;
		return MONTHS[(int) (day / 31)] + " " + (day % 31 + 1);
	}

	/** Events in slot b ("Spooky Festival"), or "". */
	static String events(int b) {
		JsonObject j = json();
		JsonArray ev = j == null ? null : j.getAsJsonArray("events");
		if (ev == null || b < 0 || b >= ev.size()) return "";
		StringBuilder sb = new StringBuilder();
		for (JsonElement e : ev.get(b).getAsJsonArray()) sb.append(sb.length() > 0 ? ", " : "").append(e.getAsString());
		return sb.toString();
	}

	/** The next event from now: {name, ms until}, or null. */
	static Object[] nextEvent(long now) {
		long best = Long.MAX_VALUE;
		String name = null;
		for (int b = 0; b < BINS; b++) {
			String e = events(b);
			if (e.isEmpty()) continue;
			long u = until(now, b);
			if (u < best) { best = u; name = e; }
		}
		return name == null ? null : new Object[]{name, best};
	}

	/** Where a product is in its year right now: "buy now", "sell now", or null. */
	static String now(JsonObject o, long now) {
		if (o == null || !o.has("buy_bin")) return null;
		int b = bin(now), buy = o.get("buy_bin").getAsInt(), sell = o.get("sell_bin").getAsInt();
		if (Math.floorMod(b - buy, BINS) <= 1) return "buy now";
		if (Math.floorMod(b - sell, BINS) <= 1) return "sell now";
		return null;
	}

	/** One tooltip line for a bazaar product with a tradeable pattern, or null. */
	static String tooltip(String id) {
		JsonObject o = of(id);
		if (o == null || !o.has("ok") || !o.get("ok").getAsBoolean()) return null;
		long now = System.currentTimeMillis();
		int buy = o.get("buy_bin").getAsInt(), sell = o.get("sell_bin").getAsInt();
		String state = now(o, now);
		return String.format(Locale.ROOT, "Seasonal: cheapest ~%s, dearest ~%s (%+.0f%%, won %.0f%% of %d years)%s",
				label(buy), label(sell), o.get("gain").getAsDouble() * 100, o.get("win").getAsDouble() * 100, o.get("trades").getAsInt(),
				state != null ? " · " + state.toUpperCase(Locale.ROOT)
						: " · buy in " + dur(until(now, buy)));
	}
}

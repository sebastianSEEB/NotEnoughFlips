package dev.notenoughflips;

import java.util.Locale;

import com.google.gson.JsonObject;

/**
 * The current SkyBlock mayor (Hypixel's public election resource, no key, checked hourly). Derpy quadruples the AH
 * claim tax (1 % → 4 % on sales over 1M), which changes every AH profit, so the fee maths follows it.
 */
final class Mayor {
	private static volatile String name;
	private static volatile long askedAt;

	private Mayor() {}

	static void tick(DataClient data) {
		long now = System.currentTimeMillis();
		if (data == null || now - askedAt < 3_600_000L) return;
		askedAt = now;
		data.getJsonPublic("https://api.hypixel.net/v2/resources/skyblock/election", 15).whenComplete((j, e) -> {
			JsonObject m = j == null ? null : HudFormatter.obj(j, "mayor");
			String n = m == null ? null : HudFormatter.str(m, "name");
			if (n != null) name = n;
		});
	}

	static String name() {
		return name;
	}

	static boolean derpy() {
		return name != null && name.toLowerCase(Locale.ROOT).contains("derpy");
	}

	/** AH claim tax on a sale: 1 % over 1M coins, 4 % while Derpy is mayor. */
	static double claimTax(double sell) {
		return sell > 1e6 ? (derpy() ? 0.04 : 0.01) : 0;
	}
}

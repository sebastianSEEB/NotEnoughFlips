package dev.notenoughflips;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * Your own bank balance, for the Trade Desk and the FCF panel. Uses YOUR Hypixel API key (/nef apikey), asks only
 * api.hypixel.net, every 5 minutes while you play, for the account that is playing. Without a key the desk counts
 * your purse only (read from the sidebar).
 */
final class MyWallet {
	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final long EVERY_MS = 5 * 60_000L;
	private static NefConfig cfg;
	private static volatile JsonObject last;            // {purse, bank, personal_bank, bank_visible, at}
	private static volatile long askedAt;
	private static volatile String error;

	private MyWallet() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	/** Account switched: forget the other account's bank. */
	static void reset() {
		Unlocks.reset();
		Owned.reset();
		last = null;
		askedAt = 0;
		error = null;
	}

	/** Latest {purse, bank, personal_bank, bank_visible, at} or null. */
	static JsonObject get() {
		return last;
	}

	static String error() {
		return error;
	}

	static void tick() {
		if (cfg == null || cfg.hypixelApiKey == null || cfg.hypixelApiKey.isBlank()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		long now = System.currentTimeMillis();
		if (now - askedAt < EVERY_MS) return;
		askedAt = now;
		String uuid = mc.player.getUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuid))
				.header("API-Key", cfg.hypixelApiKey.trim()).header("User-Agent", "NotEnoughFlips (Fabric mod)")
				.timeout(Duration.ofSeconds(15)).GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
			if (e != null) { error = "bank lookup failed"; return; }
			if (r.statusCode() == 403) { error = "Hypixel refused your API key"; return; }
			if (r.statusCode() != 200) { error = "Hypixel API: HTTP " + r.statusCode(); return; }
			try {
				JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
				if (uuid.equals(currentUuid())) {
					JsonObject me = member(root, uuid);
					Unlocks.fromProfile(me);                                     // which recipes you have (craft flips)
					Owned.fromProfile(me);
					FairySouls.fromProfile(me);                                  // souls collected, for the inventory corner                                       // and your sacks / storage
				}
				JsonObject w = parse(root, uuid);
				if (w != null && !w.has("error") && uuid.equals(currentUuid())) {
					w.addProperty("at", System.currentTimeMillis());
					last = w;
					error = null;
				}
			} catch (RuntimeException ex) {
				error = "bank lookup failed";
			}
		});
	}

	private static String currentUuid() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? "" : mc.player.getUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
	}

	/** Your member entry in the selected profile, or null. */
	static JsonObject member(JsonObject profiles, String uuid) {
		if (!profiles.has("profiles") || !profiles.get("profiles").isJsonArray()) return null;
		JsonObject sel = null;
		for (var e : profiles.getAsJsonArray("profiles")) {
			JsonObject p = e.getAsJsonObject();
			if (sel == null || p.has("selected") && p.get("selected").getAsBoolean()) sel = p;
		}
		JsonObject ms = sel == null ? null : HudFormatter.obj(sel, "members");
		return ms != null && ms.has(uuid) ? ms.getAsJsonObject(uuid) : null;
	}

	/** Pure: Bidders.wallet() plus your personal bank (profile.bank_account of your own member entry). */
	static JsonObject parse(JsonObject profiles, String uuid) {
		JsonObject w = Bidders.wallet(profiles, uuid);
		if (w.has("error")) return w;
		double personal = 0;
		for (var e : profiles.getAsJsonArray("profiles")) {
			JsonObject p = e.getAsJsonObject();
			if (!p.has("selected") || !p.get("selected").getAsBoolean()) continue;
			JsonObject m = p.has("members") && p.getAsJsonObject("members").has(uuid) ? p.getAsJsonObject("members").getAsJsonObject(uuid) : null;
			JsonObject prof = m == null ? null : HudFormatter.obj(m, "profile");
			Double pb = prof == null ? null : HudFormatter.num(prof, "bank_account");
			if (pb != null) personal = pb;
		}
		w.addProperty("personal_bank", personal);
		return w;
	}
}

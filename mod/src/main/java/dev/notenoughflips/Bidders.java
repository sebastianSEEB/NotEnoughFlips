package dev.notenoughflips;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * "Who am I bidding against, and how far can they go?" — a panel under Hypixel's Auction View (normal auctions, not BIN).
 *
 * Reads the bidders from the menu (the Bid History item and the "Bidder:" line), then asks the Hypixel API — with your
 * own key (/nef apikey) — what each can bid with: purse + bank of their selected profile. A bidder can raise to their coins plus their current bid (that bid comes back to them when outbid).
 * Net worth would also count items, but items can't be bid with until sold, so coins are the number that matters here.
 * If the bank is hidden (their Banking API is off) only the purse is known: they may have more.
 */
final class Bidders {
	record Bid(String name, double amount) {}

	/** name → JSON from /wealth (or {"error": …}); fetched once per 10 min. */
	private static final Map<String, JsonObject> wealth = new ConcurrentHashMap<>();
	private static final Map<String, Long> asked = new ConcurrentHashMap<>();
	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private static NefConfig cfg;
	private static volatile List<Bid> bids = List.of();
	private static volatile long scannedAt;
	private static volatile Screen scannedFor;

	private static final Pattern BID = Pattern.compile("^(?:Bid|Top bid|Starting bid)\\s*:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*coins?", Pattern.CASE_INSENSITIVE);
	private static final Pattern BY = Pattern.compile("^(?:By|Bidder)\\s*:\\s*(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{1,16})\\b");

	private Bidders() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	static boolean isAuctionView(Screen s) {
		String t = s.getTitle().getString().toLowerCase(Locale.ROOT);
		return t.contains("auction view") && !t.contains("bin");
	}

	/** Pure: bidders in order of appearance with their highest bid, from lore lines (formatting removed). */
	static List<Bid> parse(List<String> lines, String me) {
		Map<String, Double> best = new LinkedHashMap<>();
		double lastAmount = 0;
		for (String raw : lines) {
			String l = raw.replaceAll("§.", "").trim();
			Matcher b = BID.matcher(l);
			if (b.find()) {
				try {
					lastAmount = Double.parseDouble(b.group(1).replace(",", ""));
				} catch (NumberFormatException e) {
					lastAmount = 0;
				}
				continue;
			}
			Matcher by = BY.matcher(l);
			if (by.find()) {
				String name = by.group(1);
				if (me != null && name.equalsIgnoreCase(me)) continue;
				best.merge(name, lastAmount, Math::max);
			}
		}
		List<Bid> out = new ArrayList<>();
		best.forEach((n, a) -> out.add(new Bid(n, a)));
		return out;
	}

	static void scan(Screen s) {
		if (cfg == null || !isAuctionView(s)) return;
		if (!(s instanceof AbstractContainerScreen<?> acs) || !(acs.getMenu() instanceof ChestMenu chest)) return;
		long now = System.currentTimeMillis();
		if (s == scannedFor && now - scannedAt < 1000) return;
		scannedFor = s;
		scannedAt = now;
		List<String> lines = new ArrayList<>();
		List<Slot> slots = chest.slots;
		for (int i = 0; i < Math.min(chest.getRowCount() * 9, slots.size()); i++) {
			ItemStack st = slots.get(i).getItem();
			if (st.isEmpty()) continue;
			ItemLore lore = st.get(DataComponents.LORE);
			if (lore != null) for (Component c : lore.lines()) lines.add(c.getString());
		}
		bids = parse(lines, NefClient.playerName());
		for (Bid b : bids) fetch(b.name());
	}

	private static final Map<String, String> uuids = new ConcurrentHashMap<>();

	/**
	 * Purse + bank of the bidder's selected profile, straight from the Hypixel API with YOUR key (nef.json →
	 * hypixelApiKey, sent only to api.hypixel.net). Name → UUID via Mojang's public profile API. Cached 10 min.
	 */
	private static void fetch(String name) {
		String k = name.toLowerCase(Locale.ROOT);
		long now = System.currentTimeMillis();
		Long t = asked.get(k);
		if (t != null && now - t < (wealth.containsKey(k) && !wealth.get(k).has("error") ? 600_000L : 60_000L)) return;
		asked.put(k, now);
		String key = cfg.hypixelApiKey == null ? "" : cfg.hypixelApiKey.trim();
		if (key.isEmpty()) {
			wealth.put(k, err("add your Hypixel API key: /nef apikey <key>"));
			return;
		}
		if (!name.matches("[A-Za-z0-9_]{1,16}")) return;
		java.util.concurrent.CompletableFuture<String> uuid = uuids.containsKey(k)
				? java.util.concurrent.CompletableFuture.completedFuture(uuids.get(k))
				: http.sendAsync(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
						.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
						.thenApply(r -> r.statusCode() == 200 ? JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString() : null);
		uuid.thenCompose(u -> {
			if (u == null) return java.util.concurrent.CompletableFuture.completedFuture((JsonObject) null);
			uuids.put(k, u);
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + u))
					.header("API-Key", key).timeout(Duration.ofSeconds(15)).GET().build();
			return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
				if (r.statusCode() == 403) return err("Hypixel refused your API key (expired? get a new one)");
				if (r.statusCode() == 429) return err("Hypixel rate limit: try again in a minute");
				if (r.statusCode() != 200) return err("Hypixel API: HTTP " + r.statusCode());
				return wallet(JsonParser.parseString(r.body()).getAsJsonObject(), u);
			});
		}).whenComplete((w, e) -> wealth.put(k, e != null ? err("lookup failed: " + e.getClass().getSimpleName())
				: w == null ? err("player not found") : w));
	}

	/** Pure: purse + bank of the selected profile (the member's own purse; bank shared by the coop). */
	static JsonObject wallet(JsonObject profiles, String uuid) {
		if (!profiles.has("profiles") || !profiles.get("profiles").isJsonArray() || profiles.getAsJsonArray("profiles").isEmpty())
			return err("no SkyBlock profiles");
		JsonObject sel = null;
		for (var e : profiles.getAsJsonArray("profiles")) {
			JsonObject p = e.getAsJsonObject();
			if (sel == null || p.has("selected") && p.get("selected").getAsBoolean()) sel = p;
		}
		JsonObject member = sel.has("members") && sel.getAsJsonObject("members").has(uuid) ? sel.getAsJsonObject("members").getAsJsonObject(uuid) : null;
		double purse = 0;
		if (member != null && member.has("currencies") && member.getAsJsonObject("currencies").has("coin_purse"))
			purse = member.getAsJsonObject("currencies").get("coin_purse").getAsDouble();
		boolean bankVisible = sel.has("banking") && sel.getAsJsonObject("banking").has("balance");
		double bank = bankVisible ? sel.getAsJsonObject("banking").get("balance").getAsDouble() : 0;
		JsonObject o = new JsonObject();
		o.addProperty("purse", purse);
		o.addProperty("bank", bank);
		o.addProperty("bank_visible", bankVisible);
		o.addProperty("total", purse + bank);
		return o;
	}

	private static JsonObject err(String msg) {
		JsonObject o = new JsonObject();
		o.addProperty("error", msg);
		return o;
	}

	/** Coins this bidder can raise to: purse + bank + their current bid (refunded to them when outbid). NaN = unknown. */
	static double capacity(Bid b, JsonObject w) {
		if (w == null || w.has("error") || !w.has("total") || w.get("total").isJsonNull()) return Double.NaN;
		return w.get("total").getAsDouble() + b.amount();
	}

	static void paint(Highlight.Painter p, Screen s) {
		if (cfg == null || !isAuctionView(s) || s != scannedFor) return;
		List<Bid> list = bids;
		int[] box = GuiPos.box(s);
		int x = box[0], y = box[1] + box[3] + 4, w = Math.max(box[2], 220);
		x -= (w - box[2]) / 2;
		var font = net.minecraft.client.Minecraft.getInstance().font;
		List<String[]> rows = new ArrayList<>();            // {text, color}
		double richest = Double.NaN;
		String richestName = null;
		boolean hidden = false;
		if (list.isEmpty()) rows.add(new String[]{"No other bidders yet: the starting bid is enough.", "6E6E6E"});
		for (Bid b : list) {
			JsonObject wj = wealth.get(b.name().toLowerCase(Locale.ROOT));
			double cap = capacity(b, wj);
			String text;
			String col = "404040";
			if (wj == null) text = b.name() + "  bid " + HudFormatter.fmt(b.amount()) + "  · looking up…";
			else if (wj.has("error")) {
				text = b.name() + "  · " + wj.get("error").getAsString();
				col = "6E6E6E";
			} else {
				boolean bankHidden = !wj.has("bank_visible") || !wj.get("bank_visible").getAsBoolean();
				hidden |= bankHidden;
				text = String.format(Locale.ROOT, "%s  bid %s  · can go to %s%s", b.name(), HudFormatter.fmt(b.amount()),
						HudFormatter.fmt(cap), bankHidden ? "+ (bank hidden)" : " (purse " + HudFormatter.fmt(wj.get("purse").getAsDouble()) + " + bank)");
				if (Double.isNaN(richest) || cap > richest) {
					richest = cap;
					richestName = b.name();
				}
			}
			rows.add(new String[]{text, col});
		}
		String head = "Bidders' coins (what they can bid with)";
		String foot = null;
		if (!Double.isNaN(richest)) {
			foot = "Richest: " + richestName + " can go to " + HudFormatter.fmt(richest) + (hidden ? "+" : "")
					+ ". Above that none of them can follow" + (hidden ? " (bank hidden: maybe more)." : ".");
		}
		int lines = rows.size() + 1 + (foot == null ? 0 : 1);
		for (String[] r : rows) w = Math.max(w, font.width(r[0]) + 12);
		if (foot != null) w = Math.max(w, font.width(foot) + 12);
		int h = lines * 10 + 6;
		McStyle.panel(p, x, y, x + w, y + h);
		p.text(head, x + 6, y + 4, McStyle.GOLD);
		int ly = y + 14;
		for (String[] r : rows) {
			p.text(r[0], x + 6, ly, 0xFF000000 | Integer.parseInt(r[1], 16));
			ly += 10;
		}
		if (foot != null) p.text(foot, x + 6, ly, McStyle.GOOD);
	}
}

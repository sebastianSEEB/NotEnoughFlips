package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Trade safety: red warnings on the buttons where the common Auction House and Bazaar traps cost you coins.
 *
 *   AH "BIN Auction View" / "Confirm Purchase"
 *     lookalike     the item's name says one item but its SkyBlock id is another (item-swap / fake-item listings)
 *     overpriced    the price is ≥ 30 % (and ≥ 100k) above what this copy is worth
 *     manipulation  its recent sales look pumped (and not enough normal sales are left to price it)
 *     new item      under 3 days of sales: prices haven't settled
 *   Bazaar product page
 *     Sell Instantly  pays ≥ 30 % under the product's 7-day normal (flash-crash buy orders, panic selling)
 *     Buy Instantly   costs ≥ 40 % over its 7-day normal (pumped)
 *
 * Read-only: it only draws; you decide.
 */
final class Guard {
	record Warn(String title, String text, boolean severe) {}

	private static volatile Object lastStack;
	private static volatile Warn lastWarn;
	private static volatile long lastAt, warnSeenAt;

	private Guard() {}

	// ------------------------------------------------------------------ AH
	/** Warning for a BIN listing (the item shown in the auction view), or null when it looks fine. */
	static Warn bin(ItemStack st) {
		String key = ItemKeys.keyFor(st);
		if (key == null) return null;
		Warn look = lookalike(st, key);
		if (look != null) return look;
		ItemLore lore = st.get(DataComponents.LORE);
		List<Component> lines = new ArrayList<>();
		lines.add(st.getHoverName());
		if (lore != null) lines.addAll(lore.lines());
		Verdict.Listing listing = Verdict.listing(lines, st.getCount());
		JsonObject doc = NefClient.docFor(key, false);
		if (listing == null || doc == null || "bz".equals(HudFormatter.str(doc, "kind"))) return null;
		if (Market.hasSevereFlag(doc))
			return new Warn("Possible manipulation", firstFlag(doc, 2), true);
		Double fair = Verdict.fair(doc);
		Valuation.Estimate est = null;
		try {
			est = Valuation.estimate(doc, key, Valuation.parse(st), listing.price());
		} catch (RuntimeException ignored) {
			// no estimate
		}
		double worth = est != null && est.value() > 0 ? est.value() * st.getCount() : fair == null ? 0 : fair * st.getCount();
		if (worth > 0 && listing.price() > worth * 1.3 && listing.price() - worth > 100_000)
			return new Warn("Overpriced", String.format(Locale.ROOT, "%s is %.0f%% above what it's worth (%s)",
					HudFormatter.fmt(listing.price()), 100 * (listing.price() / worth - 1), HudFormatter.fmt(worth)), true);
		String fresh = firstFlagCode(doc, "new");
		if (fresh != null) return new Warn("New item", fresh, false);
		return null;
	}

	/** Name of a different item in the display name (reforge, stars and symbols removed) → item swap. */
	static Warn lookalike(ItemStack st, String key) {
		if (key.startsWith("PET") || key.startsWith("ENCHANTED_BOOK") || key.contains(";") || key.contains(":")) return null;
		Map<String, String> names = NefClient.data() == null ? null : NefClient.data().itemNames();
		if (names == null) return null;
		String raw = st.getHoverName().getString().replaceAll("§.", "");
		String clean = raw.replaceAll("[^A-Za-z' \\-]", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
		if (clean.isEmpty()) return null;
		String[] w = clean.split(" ");
		for (int drop = 0; drop <= Math.min(2, w.length - 1); drop++) {      // try with the reforge (1-2 words) removed
			String cand = String.join(" ", java.util.Arrays.copyOfRange(w, drop, w.length));
			String id = names.get(cand);
			if (id == null) continue;
			if (id.equals(key) || key.startsWith(id) || id.startsWith(key)) return null;
			return new Warn("Not what it looks like", "Named like " + Desk.pretty(id) + ", but it is " + Desk.pretty(key), true);
		}
		return null;
	}

	private static String firstFlag(JsonObject d, int minLevel) {
		JsonObject m = HudFormatter.obj(d, "market");
		if (m == null || !m.has("flags")) return "";
		for (var e : m.getAsJsonArray("flags")) {
			JsonObject f = e.getAsJsonObject();
			if (f.has("level") && f.get("level").getAsInt() >= minLevel) return HudFormatter.str(f, "text");
		}
		return "";
	}

	private static String firstFlagCode(JsonObject d, String code) {
		JsonObject m = HudFormatter.obj(d, "market");
		if (m == null || !m.has("flags")) return null;
		for (var e : m.getAsJsonArray("flags")) {
			JsonObject f = e.getAsJsonObject();
			if (code.equals(HudFormatter.str(f, "code"))) return HudFormatter.str(f, "text");
		}
		return null;
	}

	/** In the auction view: the warning for the shown BIN (re-checked once a second); in Confirm right after it. */
	static Warn auction(AbstractContainerScreen<?> acs, String title) {
		long now = System.currentTimeMillis();
		if (title.contains("confirm")) return now - warnSeenAt < 15_000 ? lastWarn : null;
		if (!title.contains("auction view")) return null;
		for (Slot sl : acs.getMenu().slots) {
			ItemStack st = sl.getItem();
			if (st == null || st.isEmpty()) continue;
			ItemLore lore = st.get(DataComponents.LORE);
			if (lore == null || lore.lines().stream().noneMatch(c -> c.getString().replaceAll("§.", "").startsWith("Buy it now"))) continue;
			if (st != lastStack || now - lastAt > 1000) {
				lastStack = st;
				lastAt = now;
				lastWarn = bin(st);
			}
			if (lastWarn != null) warnSeenAt = now;
			return lastWarn;
		}
		return null;
	}

	// ------------------------------------------------------------------ AH: your own listing
	private static final java.util.regex.Pattern PRICE_LINE = java.util.regex.Pattern.compile(
			"(?i)(?:item price|price|starting bid)\\s*:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*coins");

	/**
	 * "Create BIN Auction": your price vs the lowest BIN of that item. ≥ 20 % under it (a typo or a misclick costs you
	 * the difference) → red; ≥ 50 % over it → amber (it won't sell). {slot index of the price button, Warn} or null.
	 */
	static Object[] listing(AbstractContainerScreen<?> acs, String title) {
		if (!title.contains("create") || !title.contains("auction")) return null;
		List<Slot> slots = acs.getMenu().slots;
		Double price = null;
		int priceSlot = -1;
		ItemStack item = null;
		for (int i = 0; i < slots.size(); i++) {
			ItemStack st = slots.get(i).getItem();
			if (st == null || st.isEmpty()) continue;
			ItemLore lore = st.get(DataComponents.LORE);
			if (lore != null && price == null) {
				for (Component c : lore.lines()) {
					java.util.regex.Matcher m = PRICE_LINE.matcher(c.getString().replaceAll("§.", ""));
					if (m.find()) { price = Double.parseDouble(m.group(1).replace(",", "")); priceSlot = i; break; }
				}
			}
			if (item == null && i < 54 && ItemKeys.keyFor(st) != null && !st.getHoverName().getString().toLowerCase(Locale.ROOT).contains("auction"))
				item = st;
		}
		if (price == null || item == null || price <= 0) return null;
		String key = ItemKeys.keyFor(item);
		JsonObject doc = NefClient.docFor(key, false);
		Double lowest = doc == null ? null : HudFormatter.num(HudFormatter.obj(doc, "market", "listed"), "lowest");
		MarketBook.Item b = MarketBook.get(key);
		if (b != null && b.lowest() > 0 && (lowest == null || b.lowest() < lowest)) lowest = b.lowest();
		if (lowest == null || lowest <= 0) return null;
		double unit = price / Math.max(1, item.getCount());
		if (unit < lowest * 0.8)
			return new Object[]{priceSlot, new Warn("Priced far too low", String.format(Locale.ROOT,
					"%s is %.0f%% under the lowest BIN (%s). Check the number.", HudFormatter.fmt(price), 100 * (1 - unit / lowest),
					HudFormatter.fmt(lowest * Math.max(1, item.getCount()))), true)};
		if (unit > lowest * 1.5)
			return new Object[]{priceSlot, new Warn("Won't sell at this price", String.format(Locale.ROOT,
					"%s is %.0f%% over the lowest BIN (%s).", HudFormatter.fmt(price), 100 * (unit / lowest - 1),
					HudFormatter.fmt(lowest * Math.max(1, item.getCount()))), false)};
		return null;
	}

	// ------------------------------------------------------------------ Bazaar
	/** {slot index, Warn} for the Sell Instantly / Buy Instantly button of a product page, or null. */
	static Object[] bazaar(AbstractContainerScreen<?> acs, String rawTitle) {
		DataClient data = NefClient.data();
		if (data == null) return null;
		int arrow = rawTitle.lastIndexOf('➜');
		String product = (arrow >= 0 ? rawTitle.substring(arrow + 1) : rawTitle).replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
		Map<String, String> names = data.itemNames();
		String id = names == null ? null : names.get(product);
		if (id == null) return null;
		DataClient.Live l = data.live(id);
		double[] normal = data.bazaarNormal(id);
		if (l == null || normal == null) return null;
		List<Slot> slots = acs.getMenu().slots;
		for (int i = 0; i < slots.size(); i++) {
			ItemStack st = slots.get(i).getItem();
			if (st == null || st.isEmpty()) continue;
			String n = st.getHoverName().getString().replaceAll("§.", "").toLowerCase(Locale.ROOT);
			if (n.equals("sell instantly") && l.sell() > 0 && l.sell() < normal[1] * 0.7)
				return new Object[]{i, new Warn("Bad instant sell", String.format(Locale.ROOT,
						"Pays %s, %.0f%% under its normal %s. Place a sell offer or wait.", HudFormatter.fmt(l.sell()),
						100 * (1 - l.sell() / normal[1]), HudFormatter.fmt(normal[1])), true)};
			if (n.equals("buy instantly") && l.buy() > normal[0] * 1.4)
				return new Object[]{i, new Warn("Pumped price", String.format(Locale.ROOT,
						"Costs %s, %.0f%% over its normal %s. Place a buy order or wait.", HudFormatter.fmt(l.buy()),
						100 * (l.buy() / normal[0] - 1), HudFormatter.fmt(normal[0])), true)};
		}
		return null;
	}

	// ------------------------------------------------------------------ drawing
	/** True when a warning was drawn (the green "profit" highlight is then skipped). */
	static boolean paint(Highlight.Painter p, Screen s) {
		if (!(s instanceof AbstractContainerScreen<?> acs)) return false;
		String raw = s.getTitle().getString(), t = raw.toLowerCase(Locale.ROOT);
		int[] box = GuiPos.box(s);
		Warn w = auction(acs, t);
		if (w != null) {
			for (Slot sl : acs.getMenu().slots) {
				if (sl.getItem() == null || sl.getItem().isEmpty()) continue;
				String n = sl.getItem().getHoverName().getString().replaceAll("§.", "").toLowerCase(Locale.ROOT);
				if (n.contains("buy item right now") || n.equals("confirm")) mark(p, box[0] + sl.x, box[1] + sl.y, w);
			}
			return true;
		}
		Object[] own = listing(acs, t);
		if (own != null && (int) own[0] >= 0) {
			Slot sl = acs.getMenu().slots.get((int) own[0]);
			mark(p, box[0] + sl.x, box[1] + sl.y, (Warn) own[1]);
			return true;
		}
		Object[] bz = bazaar(acs, raw);
		if (bz != null) {
			Slot sl = acs.getMenu().slots.get((int) bz[0]);
			mark(p, box[0] + sl.x, box[1] + sl.y, (Warn) bz[1]);
			return true;
		}
		return false;
	}

	private static void mark(Highlight.Painter p, int x, int y, Warn w) {
		long now = System.currentTimeMillis();
		int col = w.severe() ? ((now / 300) % 2 == 0 ? 0xFFFF5555 : 0xFFAA0000) : 0xFFFFAA00;
		for (int k = 1; k <= 3; k++) {
			p.fill(x - k, y - k, x + 16 + k, y - k + 1, col);
			p.fill(x - k, y + 16 + k - 1, x + 16 + k, y + 16 + k, col);
			p.fill(x - k, y - k, x - k + 1, y + 16 + k, col);
			p.fill(x + 16 + k - 1, y - k, x + 16 + k, y + 16 + k, col);
		}
		var font = Minecraft.getInstance().font;
		String l1 = "⚠ " + w.title(), l2 = w.text();
		int lw = Math.max(font.width(l1), font.width(l2));
		int bx = x + 8 - lw / 2 - 4;
		p.fill(bx, y + 21, bx + lw + 8, y + 43, 0xF0100010);
		p.fill(bx, y + 21, bx + lw + 8, y + 22, col);
		p.text(l1, bx + 4, y + 24, w.severe() ? 0xFFFF5555 : 0xFFFFAA00);
		p.text(l2, bx + 4, y + 33, 0xFFE0E0E0);
	}
}

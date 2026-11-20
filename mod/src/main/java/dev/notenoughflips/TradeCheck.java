package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Trade check: beside Hypixel's player trade menu ("You            Name"), what you give and what you get are worth,
 * and the difference. Your side is the left 4 columns, theirs the right 4. Prices: coins as offered; bazaar items at
 * the instant-sell price (what you'd get for them now); AH items at this copy's value from the tooltip (upgrades and
 * all the same caps), else the cheapest copy listed. Items the mod can't price are counted and shown as "?".
 * Read-only: it never clicks Accept or anything else.
 */
final class TradeCheck {
	private static final Pattern TITLE = Pattern.compile("^You\\s{2,}\\S+");
	private static final Pattern COINS = Pattern.compile("([0-9][0-9,.]*\\s*[kKmMbB]?)\\s*coins", Pattern.CASE_INSENSITIVE);

	record Line(String name, double value) {}           // value < 0 = unknown

	record Side(double total, int unknown, List<Line> lines) {}

	private static NefConfig cfg;
	private static long cachedAt;
	private static Side[] cached;

	private TradeCheck() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	/** Pure: is this the player trade menu? */
	static boolean isTradeTitle(String title) {
		return title != null && TITLE.matcher(title.replaceAll("§.", "").trim()).find();
	}

	static boolean isTrade(Screen s) {
		return s instanceof AbstractContainerScreen<?> && isTradeTitle(s.getTitle().getString());
	}

	/** Pure: which side a chest slot belongs to: 0 = yours (left 4 columns), 1 = theirs (right 4), -1 = neither. */
	static int sideOf(int slot) {
		if (slot < 0 || slot >= 45) return -1;                   // the bottom row holds the buttons
		int col = slot % 9;
		return col <= 3 ? 0 : col >= 5 ? 1 : -1;
	}

	/** Coins in an offered "x coins" item, or null when it isn't one. */
	static Double coins(String name) {
		if (name == null) return null;
		Matcher m = COINS.matcher(name.replaceAll("§.", ""));
		return m.find() ? NefClient.parseAmount(m.group(1).replace(" ", "")) : null;
	}

	/** What one stack is worth to you (all items in it), or -1 when it can't be priced. */
	static double value(ItemStack st) {
		Double c = coins(st.getHoverName().getString());
		String key = ItemKeys.keyFor(st);
		if (key == null) return c != null ? c : -1;
		int n = Math.max(1, st.getCount());
		DataClient d = NefClient.data();
		DataClient.Live l = d == null ? null : d.live(key);
		if (l != null && l.sell() > 0) return l.sell() * n;            // bazaar: what an instant sell gives you
		JsonObject doc = NefClient.docFor(key, true);
		try {
			Valuation.Attrs a = Valuation.parse(st);
			Valuation.Estimate est = doc == null || a == null || a.pet ? null : Valuation.estimate(doc, key, a, null);
			if (est != null && est.value() > 0) return est.value() * n;
		} catch (RuntimeException ignored) {
			// fall through
		}
		Double fair = doc == null ? null : Verdict.fair(doc);
		MarketBook.Item b = MarketBook.get(key);
		double v = fair != null && fair > 0 ? fair : b != null ? b.lowest() : 0;
		if (v <= 0) return -1;
		return NpcPrices.cap(key, v) * n;
	}

	private static Side[] sides(AbstractContainerScreen<?> acs) {
		long now = System.currentTimeMillis();
		if (cached != null && now - cachedAt < 500) return cached;
		List<List<Line>> lines = List.of(new ArrayList<>(), new ArrayList<>());
		double[] tot = new double[2];
		int[] unk = new int[2];
		List<Slot> slots = acs.getMenu().slots;
		for (int i = 0; i < Math.min(45, slots.size()); i++) {
			int side = sideOf(i);
			if (side < 0) continue;
			ItemStack st = slots.get(i).getItem();
			if (st == null || st.isEmpty()) continue;
			String name = st.getHoverName().getString().replaceAll("§.", "").trim();
			if (ItemKeys.keyFor(st) == null && coins(name) == null) continue;  // glass panes, labels
			double v = value(st);
			if (v < 0) unk[side]++;
			else tot[side] += v;
			lines.get(side).add(new Line((st.getCount() > 1 ? st.getCount() + "x " : "") + name, v));
		}
		for (List<Line> l : lines) l.sort((a, b) -> Double.compare(b.value(), a.value()));
		cached = new Side[]{new Side(tot[0], unk[0], lines.get(0)), new Side(tot[1], unk[1], lines.get(1))};
		cachedAt = now;
		return cached;
	}

	static void paint(Screen s, Highlight.Painter p) {
		if (cfg == null || !cfg.tradeCheck || !(s instanceof AbstractContainerScreen<?> acs) || !isTrade(s)) return;
		Side[] sd = sides(acs);
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int[] box = GuiPos.box(s);
		int w = Math.min(170, Math.max(120, box[0] - 8));
		int x = Math.max(2, box[0] - w - 6), y = box[1];
		int rows = Math.min(6, sd[0].lines().size()) + Math.min(6, sd[1].lines().size());
		int h = 16 + 12 + 12 + 14 + rows * 10 + 24;
		McStyle.panel(p, x, y, x + w, y + h);
		int ty = y + 5;
		p.text("Trade check", x + 6, ty, McStyle.GOLD);
		ty += 14;
		double diff = sd[1].total() - sd[0].total();
		String verdict = Math.abs(diff) < Math.max(1, 0.02 * Math.max(sd[0].total(), sd[1].total())) ? "about even"
				: diff > 0 ? "+" + HudFormatter.fmt(diff) + " for you" : HudFormatter.fmt(-diff) + " in their favour";
		p.text(verdict, x + 6, ty, Math.abs(diff) < 0.02 * Math.max(sd[0].total(), sd[1].total()) ? McStyle.TEXT : diff > 0 ? McStyle.GOOD : McStyle.BAD);
		ty += 14;
		ty = side(p, x, ty, w, "You give", sd[0], font);
		ty = side(p, x, ty + 3, w, "You get", sd[1], font);
		if (sd[0].unknown() + sd[1].unknown() > 0)
			p.text(FcfPanel.fit("? = no price yet: check those yourself", w - 12), x + 6, ty + 2, McStyle.DIM);
	}

	private static int side(Highlight.Painter p, int x, int y, int w, String label, Side sd, net.minecraft.client.gui.Font font) {
		String tot = HudFormatter.fmt(sd.total()) + (sd.unknown() > 0 ? " + " + sd.unknown() + " ?" : "");
		p.text(label, x + 6, y, McStyle.TEXT);
		p.text(tot, x + w - 6 - font.width(tot), y, McStyle.TEXT);
		McStyle.divider(p, x + 6, x + w - 6, y + 10);
		y += 13;
		for (int i = 0; i < Math.min(6, sd.lines().size()); i++) {
			Line l = sd.lines().get(i);
			String v = l.value() < 0 ? "?" : HudFormatter.fmt(l.value());
			p.text(FcfPanel.fit(l.name(), w - 18 - font.width(v)), x + 6, y, McStyle.MUTED);
			p.text(v, x + w - 6 - font.width(v), y, l.value() < 0 ? McStyle.WARN : McStyle.MUTED);
			y += 10;
		}
		if (sd.lines().isEmpty()) {
			p.text("nothing yet", x + 6, y, McStyle.DIM);
			y += 10;
		}
		return y;
	}
}

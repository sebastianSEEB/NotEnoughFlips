package dev.notenoughflips;

import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * What to list an item at in Hypixel's "Create BIN Auction" menu: just under the cheapest copy with the same or better
 * upgrades that's listed right now (that's the one buyers compare yours with), never above an NPC's price and never
 * above what the tooltip says this copy is worth when no comparable copy is listed. Shown above the menu, and filled
 * into the price sign when you click the price button (you check it and press Done yourself).
 */
final class ListPrice {
	/** price = per item; why = one short line; total = price × count. */
	record Suggestion(String key, double price, long total, String why) {}

	private static volatile Suggestion current;
	private static volatile long currentAt;
	private static volatile ItemStack lastStack;

	private ListPrice() {}

	/** Pure: the list price from what's known. cover = cheapest comparable copy listed (0 = none), worth = this copy's value. */
	static double choose(double cover, double worth, double lowest, double npc) {
		double p;
		if (cover > 0) p = Market.undercut(cover);
		else if (worth > 0) p = worth;
		else if (lowest > 0) p = Market.undercut(lowest);
		else return 0;
		if (npc > 0) p = Math.min(p, npc);
		return Math.floor(p);
	}

	/** The suggestion for one stack, or null. */
	static Suggestion suggest(ItemStack st) {
		String key = ItemKeys.keyFor(st);
		if (key == null) return null;
		JsonObject doc = NefClient.docFor(key, false);
		Valuation.Attrs a = Valuation.parse(st);
		double worth = 0;
		try {
			Valuation.Estimate est = doc == null ? null : Valuation.estimate(doc, key, a, null);
			if (est != null) worth = est.value();
		} catch (RuntimeException ignored) {
			// no estimate
		}
		double[] cov = a == null || a.pet ? null : MarketBook.cover(key, Spec.of(a), null);
		MarketBook.Item b = MarketBook.get(key);
		double lowest = b != null ? b.lowest() : 0;
		double npc = NpcPrices.price(key);
		double unit = choose(cov == null ? 0 : cov[0], worth, lowest, npc);
		if (unit <= 0) return null;
		String why = cov != null ? "just under the cheapest copy as good as yours (" + HudFormatter.fmt(cov[0]) + ", " + (int) cov[1] + " listed)"
				: worth > 0 ? "what this copy is worth (no comparable copy listed)" : "just under the lowest BIN";
		if (npc > 0 && unit >= Math.floor(npc)) why = "an NPC sells it for " + HudFormatter.fmt(npc) + ": not more than that";
		int n = Math.max(1, st.getCount());
		return new Suggestion(key, unit, (long) Math.floor(unit * n), why);
	}

	/** The item you put into the Create BIN Auction menu, or null. */
	private static ItemStack item(AbstractContainerScreen<?> acs) {
		List<Slot> slots = acs.getMenu().slots;
		for (int i = 0; i < Math.min(54, slots.size()); i++) {
			ItemStack st = slots.get(i).getItem();
			if (st == null || st.isEmpty() || ItemKeys.keyFor(st) == null) continue;
			if (st.getHoverName().getString().toLowerCase(Locale.ROOT).contains("auction")) continue;
			return st;
		}
		return null;
	}

	private static boolean createMenu(Screen s) {
		String t = s.getTitle().getString().toLowerCase(Locale.ROOT);
		return t.contains("create") && t.contains("auction");
	}

	/** Above the Create BIN Auction menu: the suggested price and why. */
	static void paint(Screen s, Highlight.Painter p) {
		if (!(s instanceof AbstractContainerScreen<?> acs) || !createMenu(s)) return;
		ItemStack st = item(acs);
		long now = System.currentTimeMillis();
		Suggestion g;
		if (st != null && st == lastStack && now - currentAt < 1000) g = current;   // same item: reuse for a second
		else {
			g = st == null ? null : suggest(st);
			current = g;
			currentAt = now;
			lastStack = st;
		}
		if (g == null) return;
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int[] box = GuiPos.box(s);
		String l1 = "Suggested BIN: " + String.format(Locale.ROOT, "%,d", g.total()) + " coins";
		String l2 = g.why();
		String l3 = "You get " + HudFormatter.fmt(Verdict.netAfterFees(g.total())) + " after fees · filled in when you click the price";
		int w = Math.max(font.width(l1), Math.max(font.width(l2), font.width(l3))) + 12;
		int x = box[0] + (box[2] - w) / 2, y = Math.max(2, box[1] - 40);
		McStyle.panel(p, x, y, x + w, y + 36);
		p.text(l1, x + 6, y + 4, McStyle.GOLD);
		p.text(l2, x + 6, y + 14, McStyle.MUTED);
		p.text(l3, x + 6, y + 24, McStyle.DIM);
	}

	/** For the price sign: the suggestion from the Create BIN menu you just left (last 2 min), or null. */
	static Suggestion forSign() {
		Suggestion g = current;
		return g != null && System.currentTimeMillis() - currentAt < 120_000 ? g : null;
	}
}

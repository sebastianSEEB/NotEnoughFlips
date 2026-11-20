package dev.notenoughflips;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.component.ItemLore;

/**
 * Yellow border around auctions in the AH that are worth it: underpriced enough to flip for at least
 * minFlipProfitPercent after fees (or worth bidding on), the item sells regularly (>= 3 sales in 24 h)
 * and no manipulation is flagged. Strong deals (>= 20 % under fair value) get a filled glow as well.
 * Drawing only: nothing is clicked or sent.
 */
final class Highlight {
	interface Painter {
		void fill(int x1, int y1, int x2, int y2, int argb);

		void text(String text, int x, int y, int argb);
	}

	private record Cached(int stackId, long at, int level, String name, double price, double profit, double eta, boolean bid, boolean manip) {
		Cached(int stackId, long at) {
			this(stackId, at, 0, null, 0, 0, Double.NaN, false, false);
		}

		Cached(int stackId, long at, boolean manip) {
			this(stackId, at, 0, null, 0, 0, Double.NaN, false, manip);
		}
	}

	private static JsonObject petDocOrWarm(String key, int level, long now) {
		JsonObject d = NefClient.petDoc(key, level, false);
		if (d != null) return d;
		if (now - warmFrom > 20_000) { warmFrom = now; warmed = 0; }
		if (warmed < 12 && CoflClient.petLevelKey(key, level) != null) {
			warmed++;
			return NefClient.petDoc(key, level, true);
		}
		return null;
	}

	private static long warmFrom;
	private static int warmed;

	/** Fetch SkyCofl prices for a few visible items without one (≤ 12 per 20 s), so manipulation shows up while you browse. */
	private static JsonObject docOrWarm(String key, long now) {
		JsonObject d = NefClient.docFor(key, false);
		if (d != null || !cfg.manipMarkers) return d;
		if (now - warmFrom > 20_000) { warmFrom = now; warmed = 0; }
		if (warmed < 12) {
			warmed++;
			return NefClient.docFor(key, true);
		}
		return null;
	}   // level: 0 none, 1 worth it, 2 strong deal

	/** A profitable auction on the AH page you're looking at (for the FLIP panel's "On this page"). */
	record PageFlip(int slot, int rank, String name, double price, double profit, double eta, boolean bid) {}

	private static final Map<Integer, Cached> cache = new HashMap<>();
	private static volatile List<PageFlip> page = List.of();
	private static volatile long pageAt;
	private static NefConfig cfg;
	private static DataClient data;

	private Highlight() {}

	static void init(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	static void paint(Screen screen, Painter p) {
		if (cfg == null || !cfg.highlightDeals || !(screen instanceof AbstractContainerScreen<?> acs)) return;
		if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("auction")) return;
		if (!(acs.getMenu() instanceof ChestMenu chest)) return;
		int rows = chest.getRowCount();
		int left, top;
		long now = System.currentTimeMillis();
		List<Slot> slots = chest.slots;
		int[] box = GuiPos.box(screen);
		left = box[0];
		top = box[1];
		List<PageFlip> found = new java.util.ArrayList<>();
		for (int i = 0; i < Math.min(rows * 9, slots.size()); i++) {
			Slot slot = slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) { cache.remove(i); continue; }
			int id = System.identityHashCode(stack) * 31 + stack.getCount();
			Cached c = cache.get(i);
			if (c == null || c.stackId() != id || now - c.at() > 2000) {
				c = evaluate(stack, id, now);
				cache.put(i, c);
			}
			if (c.manip() && cfg.manipMarkers) {                          // red "!" = looks manipulated
				Slot sl = slot;
				int mx = left + sl.x + 10, my = top + sl.y;
				p.fill(mx, my, mx + 6, my + 9, 0xF0AA0000);
				p.text("!", mx + 1, my, 0xFFFFFFFF);
			}
			if (c.level() == 0) continue;
			found.add(new PageFlip(i, 0, c.name(), c.price(), c.profit(), c.eta(), c.bid()));
			int x = left + slot.x, y = top + slot.y;
			int border = c.level() == 2 ? 0xFFFFD700 : 0xFFFFE066;
			if (c.level() == 2) p.fill(x, y, x + 16, y + 16, 0x50FFD700);      // glow fill for strong deals
			p.fill(x - 1, y - 1, x + 17, y, border);
			p.fill(x - 1, y + 16, x + 17, y + 17, border);
			p.fill(x - 1, y, x, y + 16, border);
			p.fill(x + 16, y, x + 17, y + 16, border);
		}
		found.sort((a, b) -> Double.compare(b.profit(), a.profit()));
		List<PageFlip> ranked = new java.util.ArrayList<>();
		for (int r = 0; r < found.size(); r++) {
			PageFlip f = found.get(r);
			ranked.add(new PageFlip(f.slot(), r + 1, f.name(), f.price(), f.profit(), f.eta(), f.bid()));
			if (r < 5) {                                                    // rank number, matches the FLIP panel
				Slot slot = slots.get(f.slot());
				int x = left + slot.x, y = top + slot.y;
				p.fill(x, y, x + 7, y + 9, 0xE0000000);
				p.text(String.valueOf(r + 1), x + 1, y, 0xFF55FF55);
			}
		}
		page = ranked;
		pageAt = now;
	}

	/**
	 * Bazaar menus: the products that are in the current flip list get a green border and their rank (same number as
	 * in the FLIP panel), so you can see which ones to flip while browsing. Drawing only.
	 */
	static void paintBazaar(Screen screen, Painter p) {
		if (cfg == null || data == null || !(screen instanceof AbstractContainerScreen<?> acs)) return;
		String title = screen.getTitle().getString();
		if (!title.contains("Bazaar") && !title.contains("➜")) return;
		if (!(acs.getMenu() instanceof ChestMenu chest)) return;
		int[] box = GuiPos.box(screen);
		String pending = FlipPanel.pendingId();
		String pendingName = FlipPanel.pendingName();
		boolean productVisible = false;
		List<Slot> slots = chest.slots;
		int n = Math.min(chest.getRowCount() * 9, slots.size());
		int searchSlot = -1, buyOrderSlot = -1;
		for (int i = 0; i < n; i++) {
			ItemStack stack = slots.get(i).getItem();
			if (stack.isEmpty()) continue;
			String low = stack.getHoverName().getString().replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
			if (low.equals("search") || low.startsWith("search ")) searchSlot = i;
			if (low.contains("create buy order")) buyOrderSlot = i;
		}
		// a product page has a "Create Buy Order" button and the product's name in the title
		boolean onProductPage = pendingName != null && buyOrderSlot >= 0
				&& title.toLowerCase(Locale.ROOT).contains(pendingName.toLowerCase(Locale.ROOT));
		for (int i = 0; i < n; i++) {
			Slot slot = slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = stack.getHoverName().getString().replaceAll("§.", "").trim();
			String id = ItemKeys.keyFor(stack);
			if (id == null) id = Bazaar.idForName(name, data);
			int x = box[0] + slot.x, y = box[1] + slot.y;
			if (pending != null && pending.equals(id) && !onProductPage) {        // the product you clicked: open it
				productVisible = true;
				box(p, x, y, 0xFF55FFFF, 2);
				continue;
			}
			int r = Bazaar.rank(id);
			if (r == 0 || r > 20) continue;
			box(p, x, y, r <= 5 ? 0xFF55FF55 : 0xFF2E9E5B, 1);
			int tw = r >= 10 ? 13 : 7;
			p.fill(x, y, x + tw, y + 9, 0xE0000000);
			p.text(String.valueOf(r), x + 1, y, 0xFF55FF55);
		}
		// guide for the product you're flipping: Search → the item → Create Buy Order (drawing only; you click)
		if (pending == null) return;
		Integer step = null;
		if (onProductPage) step = buyOrderSlot;
		else if (!productVisible && searchSlot >= 0) step = searchSlot;
		if (step == null) return;
		Slot s = slots.get(step);
		int x = box[0] + s.x, y = box[1] + s.y;
		box(p, x, y, 0xFF55FF55, 2);
		String hint = onProductPage ? "here, then Custom amount" : "Search → Ctrl+V";
		int tx = Math.min(x + 20, screen.width - Minecraft.getInstance().font.width(hint) - 4);
		p.fill(tx - 2, y + 3, tx + Minecraft.getInstance().font.width(hint) + 2, y + 13, 0xE0000000);
		p.text(hint, tx, y + 4, 0xFF55FF55);
	}

	private static void box(Painter p, int x, int y, int color, int t) {
		p.fill(x - t, y - t, x + 16 + t, y, color);
		p.fill(x - t, y + 16, x + 16 + t, y + 16 + t, color);
		p.fill(x - t, y, x, y + 16, color);
		p.fill(x + 16, y, x + 16 + t, y + 16, color);
	}

	/** Profitable auctions on the AH page currently open (best first), empty when no AH page is open. */
	static List<PageFlip> page() {
		return System.currentTimeMillis() - pageAt < 1500 ? page : List.of();
	}

	private static Cached evaluate(ItemStack stack, int id, long now) {
		try {
			String key = ItemKeys.keyFor(stack);
			if (key == null) return new Cached(id, now);
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) return new Cached(id, now);
			List<Component> lines = lore.lines();
			if (Verdict.ownListing(lines, stack.getCount(), NefClient.playerName()) != null) return new Cached(id, now);
			Verdict.Listing listing = Verdict.listing(lines, stack.getCount());
			if (listing == null) return new Cached(id, now);                // not an auction listing
			Valuation.Attrs attrs = cfg.valueUpgrades ? Valuation.parse(stack) : null;
			JsonObject pet = attrs != null && attrs.pet ? petDocOrWarm(key, attrs.petLevel, now) : null;
			JsonObject dj = pet != null ? pet : docOrWarm(key, now);       // SkyCofl: cached, or a few fetched per page
			if (dj == null) return new Cached(id, now);
			boolean manip = Market.hasSevereFlag(dj);
			if (manip) return new Cached(id, now, true);                 // never a flip border on a manipulated item
			Valuation.Estimate est = pet == null && attrs != null ? Valuation.estimate(dj, key, attrs, listing.price()) : null;
			Verdict.Result r = Verdict.evaluate(dj, listing, null, cfg, est);
			if (r.banner() == null || r.profit() == null) return new Cached(id, now);   // not profitable / not liquid
			Double fair = est != null ? Double.valueOf(est.value()) : Verdict.fair(dj);
			int level = fair != null && listing.price() <= fair * (1 - cfg.dealMinDiscountPercent / 100.0) ? 2 : 1;
			String name = stack.getHoverName().getString().replaceAll("§.", "");
			double eta = r.etaHours() == null ? Double.NaN : r.etaHours();
			return new Cached(id, now, level, name, listing.bin() ? listing.price() : r.suggestedBid(), r.profit(), eta, !listing.bin(), false);
		} catch (RuntimeException e) {
			return new Cached(id, now);
		}
	}

}

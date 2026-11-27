package dev.notenoughflips;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Dungeon reward chests: what's inside each one is worth against what it costs to open. In a run's chest menu (Croesus
 * or the reward room, "Catacombs - Floor VII") every chest gets its profit, the best one is outlined in gold, and the
 * runner-up in aqua when it's still worth it after paying for a Dungeon Chest Key. Inside a single chest ("Bedrock
 * Chest") the title row says what it's worth and whether opening it pays. Only shows numbers; you open chests yourself.
 */
final class DungeonLoot {
	private DungeonLoot() {}

	static final List<String> CHESTS = List.of("Wood Chest", "Gold Chest", "Diamond Chest", "Emerald Chest", "Obsidian Chest",
			"Bedrock Chest");
	/** Loot worth only what an NPC pays for it. */
	static final Set<String> NPC_ONLY = Set.of("STORM_THE_FISH", "MAXOR_THE_FISH", "GOLDOR_THE_FISH", "DUNGEON_DISC_1",
			"DUNGEON_DISC_2", "DUNGEON_DISC_3", "DUNGEON_DISC_4", "DUNGEON_DISC_5");
	static final String KEY = "DUNGEON_CHEST_KEY";

	/** Coins for one of an item when you sell it (after tax), 0 = no price. */
	interface Prices {
		double each(String id);
	}

	record Item(String name, String id, int qty, double each) {
		double total() {
			return each * qty;
		}
	}

	record Chest(String name, List<Item> items, double value, double cost, boolean needsKey, int unpriced) {
		double profit() {
			return value - cost;
		}
	}

	private static final Pattern QTY = Pattern.compile("^(.*?)\\s+x([0-9,]+)$");
	private static final Pattern ESSENCE = Pattern.compile("^(\\w+) Essence$");
	private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\((.+)\\)$");
	private static final Pattern ROMAN_END = Pattern.compile("^(.+?) ([IVXLC]+)$");
	private static final Pattern COINS = Pattern.compile("^([0-9,]+) Coins$");

	/** "Wither Essence x25" → {"Wither Essence", 25}; no suffix → count 1. */
	static Object[] splitQty(String line) {
		Matcher m = QTY.matcher(line.trim());
		if (m.matches()) return new Object[]{m.group(1).trim(), Integer.parseInt(m.group(2).replace(",", ""))};
		return new Object[]{line.trim(), 1};
	}

	/**
	 * Market id for a loot name: essence, enchanted books ("Enchanted Book (Soul Eater I)" or just "Soul Eater I"), or
	 * any item by its display name. Null when unknown.
	 * @param byName lower-case display name → id
	 * @param exists whether an id has a market (to pick ENCHANTMENT_X_n vs ENCHANTMENT_ULTIMATE_X_n)
	 */
	static String idFor(String name, Function<String, String> byName, Predicate<String> exists) {
		String n = name.replaceAll("§.", "").trim();
		Matcher e = ESSENCE.matcher(n);
		if (e.matches()) return "ESSENCE_" + e.group(1).toUpperCase(Locale.ROOT);
		Matcher b = BOOK.matcher(n);
		boolean book = b.matches();
		if (book) n = b.group(1).trim();
		if (!book) {
			String id = byName.apply(n.toLowerCase(Locale.ROOT));
			if (id != null) return id;
		}
		Matcher r = ROMAN_END.matcher(n);
		if (!r.matches()) return null;
		int lvl = Unlocks.roman(r.group(2));
		if (lvl <= 0) return null;
		String ench = r.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
		for (String id : new String[]{"ENCHANTMENT_" + ench + "_" + lvl, "ENCHANTMENT_ULTIMATE_" + ench + "_" + lvl})
			if (exists.test(id)) return id;
		return book ? "ENCHANTMENT_" + ench + "_" + lvl : null;
	}

	/** A chest from its icon's lore: the lines under "Contents", then the coins (and key) under "Cost". */
	static Chest fromLore(String name, List<String> lore, Prices p, Function<String, String> byName, Predicate<String> exists) {
		List<Item> items = new ArrayList<>();
		double value = 0, cost = 0;
		boolean key = false, inCost = false, inContents = false;
		int unpriced = 0;
		for (String raw : lore) {
			String line = raw.replaceAll("§.", "").trim();
			if (line.equals("Contents")) {
				inContents = true;
				continue;
			}
			if (line.equals("Cost")) {
				inCost = true;
				continue;
			}
			if (line.isEmpty()) {
				if (inCost) break;
				continue;
			}
			if (inCost) {
				Matcher c = COINS.matcher(line);
				if (c.matches()) cost += Double.parseDouble(c.group(1).replace(",", ""));
				else if (line.contains("Dungeon Chest Key")) key = true;
			} else if (inContents) {
				Object[] q = splitQty(line);
				String id = idFor((String) q[0], byName, exists);
				double each = id == null ? 0 : p.each(id);
				if (each <= 0) unpriced++;
				Item it = new Item((String) q[0], id, (Integer) q[1], each);
				items.add(it);
				value += it.total();
			}
		}
		if (key) cost += Math.max(0, p.each(KEY));
		return new Chest(name, items, value, cost, key, unpriced);
	}

	/** Best first. */
	static List<Chest> ranked(List<Chest> chests) {
		List<Chest> out = new ArrayList<>(chests);
		out.sort(Comparator.comparingDouble(Chest::profit).reversed());
		return out;
	}

	/** The chest to open with a Dungeon Chest Key too, or null: the runner-up, if it pays after the key. */
	static Chest withKey(List<Chest> ranked, double keyPrice) {
		if (ranked.size() < 2 || ranked.get(0).profit() <= 0) return null;
		Chest second = ranked.get(1);
		return second.profit() - keyPrice > 0 ? second : null;
	}

	// ---- in the game ----

	private static NefConfig cfg;

	static void init(NefConfig config) {
		cfg = config;
	}

	/** What you'd get selling one: bazaar instant-sell after tax, lowest BIN less fees, or NPC for fish and discs. */
	static double priceEach(String id) {
		if (id == null) return 0;
		if (NPC_ONLY.contains(id)) return NpcPrices.price(id);
		DataClient data = NefClient.data();
		if (data != null) {
			DataClient.Live l = data.bazaarAll().get(id);
			if (l != null) {
				if (KEY.equals(id)) return l.buy();                       // a key is something you buy
				double tax = cfg == null ? 1.25 : cfg.bazaarTaxPercent;
				return l.sell() * (1 - tax / 100.0);
			}
		}
		MarketBook.Item it = MarketBook.get(id);
		if (it != null && it.lowest() > 0) return it.lowest() * 0.98;      // listing + claim fees
		double npc = NpcPrices.price(id);
		return Math.max(0, npc);
	}

	private static Function<String, String> byName() {
		DataClient data = NefClient.data();
		var names = data == null ? null : data.itemNames();
		return n -> names == null ? null : names.get(n);
	}

	private static Predicate<String> exists() {
		DataClient data = NefClient.data();
		return id -> data != null && data.bazaarAll().containsKey(id);
	}

	private static List<String> lore(ItemStack st) {
		List<String> out = new ArrayList<>();
		ItemLore lore = st.get(DataComponents.LORE);
		if (lore != null) for (Component c : lore.lines()) out.add(c.getString().replaceAll("§.", ""));
		return out;
	}

	private static String plain(ItemStack st) {
		return st.getHoverName().getString().replaceAll("§.", "").trim();
	}

	private static boolean runMenu(String title) {
		return title.startsWith("Catacombs - Floor") || title.startsWith("Master Catacombs - Floor")
				|| title.startsWith("Master Mode Catacombs - Floor");
	}

	static void paint(Screen s, Highlight.Painter p) {
		if (cfg == null || !cfg.dungeonChestProfit || !(s instanceof AbstractContainerScreen<?> acs) || acs.getMenu() == null) return;
		String title = s.getTitle().getString().replaceAll("§.", "").trim();
		if (runMenu(title)) paintRun(acs, p);
		else if (CHESTS.contains(title)) paintChest(acs, title, p);
	}

	private static int containerSlots(AbstractContainerScreen<?> acs) {
		return Math.max(0, acs.getMenu().slots.size() - 36);               // the rest is your own inventory
	}

	private static void paintRun(AbstractContainerScreen<?> acs, Highlight.Painter p) {
		List<Chest> chests = new ArrayList<>();
		List<int[]> where = new ArrayList<>();
		List<Slot> slots = acs.getMenu().slots;
		Prices pr = DungeonLoot::priceEach;
		var byName = byName();
		var exists = exists();
		for (int i = 0; i < containerSlots(acs); i++) {
			ItemStack st = slots.get(i).getItem();
			if (st == null || st.isEmpty()) continue;
			String name = plain(st);
			if (!CHESTS.contains(name)) continue;
			chests.add(fromLore(name, lore(st), pr, byName, exists));
			where.add(new int[]{slots.get(i).x, slots.get(i).y});
		}
		if (chests.isEmpty()) return;
		int[] box = GuiPos.box(acs);
		List<Chest> ranked = ranked(chests);
		Chest best = ranked.get(0).profit() > 0 ? ranked.get(0) : null;
		double keyPrice = priceEach(KEY);
		Chest second = withKey(ranked, keyPrice);
		for (int i = 0; i < chests.size(); i++) {
			Chest c = chests.get(i);
			int col = c == best ? 0xFFFFAA00 : c == second ? 0xFF55FFFF : 0;
			if (col == 0) continue;
			outline(p, box[0] + where.get(i)[0], box[1] + where.get(i)[1], col);
		}

		// summary, left of the menu
		int w = 150, x = Math.max(2, box[0] - w - 6), y0 = box[1];
		if (box[0] - 8 < 110) {                                            // no room on the left: under the menu
			x = box[0];
			y0 = box[1] + box[3] + 3;
		}
		int h = 16 + ranked.size() * 20 + 24;
		McStyle.panel(p, x, y0, x + w, y0 + h);
		p.text("Chest profit", x + 6, y0 + 5, McStyle.TEXT);
		int y = y0 + 17;
		for (Chest c : ranked) {
			String pf = (c.profit() >= 0 ? "+" : "") + HudFormatter.fmt(c.profit());
			int col = c == best ? 0xFFFFAA00 : c == second ? 0xFF55FFFF : c.profit() > 0 ? McStyle.GOOD : 0xFFFF5555;
			p.text(c.name().replace(" Chest", ""), x + 6, y, 0xFFFFFFFF);
			p.text(pf, x + w - 6 - w(pf), y, col);
			String sub = "worth " + HudFormatter.fmt(c.value()) + " · costs " + HudFormatter.fmt(c.cost())
					+ (c.unpriced() > 0 ? " · " + c.unpriced() + "?" : "");
			p.text(FcfPanel.fit(sub, w - 12), x + 6, y + 9, McStyle.MUTED);
			y += 20;
		}
		String a = best == null ? "None pay: open the free one" : "Open " + best.name().replace(" Chest", "");
		p.text(FcfPanel.fit(a, w - 12), x + 6, y + 1, best == null ? 0xFFFF5555 : 0xFFFFAA00);
		String k = keyPrice <= 0 ? "Key price unknown"
				: second != null ? "+ key (" + HudFormatter.fmt(keyPrice) + "): " + second.name().replace(" Chest", "")
				: "A key (" + HudFormatter.fmt(keyPrice) + ") doesn't pay";
		p.text(FcfPanel.fit(k, w - 12), x + 6, y + 11, second != null ? 0xFF55FFFF : McStyle.MUTED);
	}

	private static void paintChest(AbstractContainerScreen<?> acs, String title, Highlight.Painter p) {
		List<Slot> slots = acs.getMenu().slots;
		double value = 0, cost = 0;
		int unpriced = 0;
		boolean key = false;
		var byName = byName();
		var exists = exists();
		for (int i = 0; i < containerSlots(acs); i++) {
			ItemStack st = slots.get(i).getItem();
			if (st == null || st.isEmpty()) continue;
			String name = plain(st);
			if (name.isEmpty() || st.getHoverName().getString().isBlank()) continue;          // glass panes
			if (name.equals("Open Reward Chest")) {
				for (String line : lore(st)) {
					Matcher c = COINS.matcher(line.trim());
					if (c.matches()) cost += Double.parseDouble(c.group(1).replace(",", ""));
					else if (line.contains("Dungeon Chest Key")) key = true;
				}
				continue;
			}
			if (name.equals("Close") || name.equals("Go Back") || name.contains("Stained Glass") || name.startsWith("Reroll")) continue;
			Object[] q = splitQty(name);
			String id = name.startsWith("Enchanted Book") || ESSENCE.matcher((String) q[0]).matches() ? null : ItemKeys.keyFor(st);
			if (id == null) id = idFor((String) q[0], byName, exists);
			int qty = (Integer) q[1] > 1 ? (Integer) q[1] : st.getCount();
			double each = priceEach(id);
			if (each <= 0) unpriced++;
			value += each * qty;
		}
		if (key) cost += Math.max(0, priceEach(KEY));
		double profit = value - cost;
		int[] box = GuiPos.box(acs);
		String t = "worth " + HudFormatter.fmt(value) + (cost > 0 ? " − " + HudFormatter.fmt(cost) : "") + " = "
				+ (profit >= 0 ? "+" : "") + HudFormatter.fmt(profit) + (profit > 0 ? " ✔" : " ✘") + (unpriced > 0 ? " (" + unpriced + "?)" : "");
		int tw = w(t);
		int tx = box[0] + box[2] - 7 - tw;
		p.fill(tx - 2, box[1] + 4, tx + tw + 2, box[1] + 15, 0xC0000000);
		p.text(t, tx, box[1] + 6, profit > 0 ? McStyle.GOOD : 0xFFFF5555);
	}

	private static void outline(Highlight.Painter p, int x, int y, int col) {
		for (int k = 1; k <= 2; k++) {
			p.fill(x - k, y - k, x + 16 + k, y - k + 1, col);
			p.fill(x - k, y + 16 + k - 1, x + 16 + k, y + 16 + k, col);
			p.fill(x - k, y - k, x - k + 1, y + 16 + k, col);
			p.fill(x + 16 + k - 1, y - k, x + 16 + k, y + 16 + k, col);
		}
	}

	private static int w(String t) {
		return net.minecraft.client.Minecraft.getInstance().font.width(t);
	}
}

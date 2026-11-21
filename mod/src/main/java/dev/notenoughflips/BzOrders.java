package dev.notenoughflips;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Your bazaar orders. Read from Hypixel's "Your Bazaar Orders" menu whenever you open it (name "BUY …" / "SELL …",
 * "Price per unit", "Order/Offer amount", "Filled"), kept in config/nef-bz-orders.json, and compared with the
 * live top order / cheapest offer every 30 s:
 *
 *   buy order   outbid   when someone's buy order is above yours  → re-place at their price + 0.1
 *   sell offer  undercut when someone's offer is below yours       → re-place at their price − 0.1
 *
 * You get one chat alert (+ ping) per order when that happens, and when Hypixel says an order was filled.
 * Read-only: the mod never cancels, creates or claims orders.
 */
final class BzOrders {
	record Order(String side, String id, String name, double price, int amount, int filled, long seen) {
		String key() {
			return side + "|" + id + "|" + price;
		}
	}

	record Status(Order order, String state, Double top) {}     // state: top / outbid / undercut / filled / unknown

	private static final Pattern PRICE = Pattern.compile("Price per unit:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*coins", Pattern.CASE_INSENSITIVE);
	private static final Pattern AMOUNT = Pattern.compile("(?:Order|Offer) amount:\\s*([0-9][0-9,]*)x", Pattern.CASE_INSENSITIVE);
	private static final Pattern FILLED = Pattern.compile("Filled:\\s*([0-9][0-9,.]*[kKmM]?)\\s*/\\s*([0-9][0-9,.]*[kKmM]?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern FILLED_CHAT = Pattern.compile("Your (Buy Order|Sell Offer) for ([0-9][0-9,]*)x (.+?) was filled", Pattern.CASE_INSENSITIVE);

	private static volatile List<Order> orders = new ArrayList<>();
	private static final Map<String, String> lastState = new HashMap<>();
	private static NefConfig cfg;
	private static DataClient data;
	private static long scannedAt, checkedAt;
	private static final Gson GSON = new Gson();

	private BzOrders() {}

	static void init(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
		reload();
	}

	/** Load the bazaar orders of the account that is playing. */
	static synchronized void reload() {
		orders = new ArrayList<>();
		lastState.clear();
		try {
			Path f = file();
			if (Files.exists(f)) {
				List<Order> l = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Order>>() {}.getType());
				if (l != null) orders = new ArrayList<>(l);
			}
		} catch (Exception ignored) {
			// start empty
		}
	}

	private static Path file() {
		return Accounts.file("bz-orders.json");
	}

	private static void save() {
		try {
			Files.writeString(file(), GSON.toJson(orders), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not critical
		}
	}

	static List<Order> list() {
		return orders;
	}

	/**
	 * Coins your bazaar orders hold right now, so FCF doesn't dip when you place one: buy orders at what you pay
	 * (coins locked + items bought but not claimed), sell offers at what you get after tax (items + coins not claimed).
	 */
	static double value() {
		double tax = cfg == null ? 0.0125 : cfg.bazaarTaxPercent / 100.0;
		return value(orders, tax);
	}

	/** Pure: see value(). */
	static double value(List<Order> list, double tax) {
		double v = 0;
		for (Order o : list) {
			if (o.amount() <= 0 || o.price() <= 0) continue;
			v += "BUY".equalsIgnoreCase(o.side()) ? o.amount() * o.price() : o.amount() * o.price() * (1 - tax);
		}
		return v;
	}

	static boolean isOrdersScreen(Screen s) {
		String t = s.getTitle().getString().toLowerCase(Locale.ROOT);
		return t.contains("bazaar orders");
	}

	/** Called while a container screen is drawn: re-reads your orders when it's the "Your Bazaar Orders" menu (1×/s). */
	static void scan(Screen s) {
		if (cfg == null || !cfg.bazaarOrderTracker || !isOrdersScreen(s)) return;
		if (!(s instanceof AbstractContainerScreen<?> acs) || !(acs.getMenu() instanceof ChestMenu chest)) return;
		long now = System.currentTimeMillis();
		if (now - scannedAt < 1000) return;
		scannedAt = now;
		List<Order> found = new ArrayList<>();
		List<Slot> slots = chest.slots;
		for (int i = 0; i < Math.min(chest.getRowCount() * 9, slots.size()); i++) {
			ItemStack st = slots.get(i).getItem();
			if (st.isEmpty()) continue;
			String name = st.getHoverName().getString().replaceAll("§.", "").trim();
			String up = name.toUpperCase(Locale.ROOT);
			String side = up.startsWith("BUY ") ? "BUY" : up.startsWith("SELL ") ? "SELL" : null;
			if (side == null) continue;
			ItemLore lore = st.get(DataComponents.LORE);
			List<String> lines = new ArrayList<>();
			if (lore != null) for (Component c : lore.lines()) lines.add(c.getString().replaceAll("§.", ""));
			Order o = parse(side, name.substring(side.length()).trim(), lines, now);
			if (o != null) found.add(o);
		}
		if (!sameOrders(found, orders)) {
			orders = found;
			save();
		}
	}

	/** Pure parsing (tested): one order from its name and lore lines. */
	static Order parse(String side, String product, List<String> lore, long now) {
		Double price = null;
		int amount = 0, filled = 0;
		for (String l : lore) {
			Matcher m = PRICE.matcher(l);
			if (m.find()) price = Double.parseDouble(m.group(1).replace(",", ""));
			m = AMOUNT.matcher(l);
			if (m.find()) amount = Integer.parseInt(m.group(1).replace(",", ""));
			m = FILLED.matcher(l);
			if (m.find()) {
				filled = (int) amountOf(m.group(1));
				if (amount == 0) amount = (int) amountOf(m.group(2));
			}
		}
		if (price == null) return null;
		String id = data == null ? null : Bazaar.idForName(product, data);
		return new Order(side, id == null ? product.toUpperCase(Locale.ROOT).replace(' ', '_') : id, product, price, amount, filled, now);
	}

	static double amountOf(String s) {
		String t = s.replace(",", "").toLowerCase(Locale.ROOT);
		double mult = t.endsWith("k") ? 1e3 : t.endsWith("m") ? 1e6 : 1;
		if (mult > 1) t = t.substring(0, t.length() - 1);
		return Double.parseDouble(t) * mult;
	}

	private static boolean sameOrders(List<Order> a, List<Order> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			Order x = a.get(i), y = b.get(i);
			if (!x.key().equals(y.key()) || x.filled() != y.filled() || x.amount() != y.amount()) return false;
		}
		return true;
	}

	/** Where each order stands against the live bazaar. */
	static List<Status> status() {
		List<Status> out = new ArrayList<>();
		for (Order o : orders) {
			if (o.amount() > 0 && o.filled() >= o.amount()) {
				out.add(new Status(o, "filled", null));
				continue;
			}
			DataClient.Live l = data == null ? null : data.live(o.id());
			if (l == null) {
				out.add(new Status(o, "unknown", null));
				continue;
			}
			if ("BUY".equals(o.side())) {
				out.add(new Status(o, l.sell() > o.price() + 0.05 ? "outbid" : "top", l.sell()));
			} else {
				out.add(new Status(o, l.buy() < o.price() - 0.05 ? "undercut" : "top", l.buy()));
			}
		}
		return out;
	}

	/** Every 30 s: one alert per order when it gets outbid / undercut. */
	static void check() {
		if (cfg == null || !cfg.bazaarOrderTracker || orders.isEmpty()) return;
		long now = System.currentTimeMillis();
		if (now - checkedAt < 30_000) return;
		checkedAt = now;
		for (Status st : status()) {
			String prev = lastState.put(st.order().key(), st.state());
			if (st.state().equals(prev) || st.top() == null) continue;
			Order o = st.order();
			if ("outbid".equals(st.state())) {
				Alerts.alert(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "Buy order outbid: ", HudFormatter.YELLOW,
						o.name() + " (yours " + HudFormatter.fmt(o.price()) + ", top now " + HudFormatter.fmt(st.top())
								+ ") → re-place at " + HudFormatter.fmt(st.top() + 0.1) + " if it's still a flip", HudFormatter.WHITE)
						.append(HudFormatter.openBazaar(o.name())));
			} else if ("undercut".equals(st.state())) {
				Alerts.alert(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "Sell offer undercut: ", HudFormatter.YELLOW,
						o.name() + " (yours " + HudFormatter.fmt(o.price()) + ", cheapest now " + HudFormatter.fmt(st.top())
								+ ") → re-place at " + HudFormatter.fmt(st.top() - 0.1), HudFormatter.WHITE)
						.append(HudFormatter.openBazaar(o.name())));
			}
		}
	}

	private static final Pattern SETUP_CHAT = Pattern.compile("\\[Bazaar\\] (Buy Order|Sell Offer) Setup! ([0-9][0-9,]*)x (.+?) for ([0-9][0-9,]*(?:\\.[0-9]+)?) coins", Pattern.CASE_INSENSITIVE);
	private static final Pattern CLAIM_BUY_CHAT = Pattern.compile("\\[Bazaar\\] Claimed ([0-9][0-9,]*)x (.+?) worth", Pattern.CASE_INSENSITIVE);
	private static final Pattern CLAIM_SELL_CHAT = Pattern.compile("\\[Bazaar\\] Claimed [0-9][0-9,.]* coins from selling ([0-9][0-9,]*)x (.+?) at", Pattern.CASE_INSENSITIVE);
	private static final Pattern CANCEL_ITEMS_CHAT = Pattern.compile("\\[Bazaar\\] Cancelled! Refunded ([0-9][0-9,]*)x (.+?) from cancelling", Pattern.CASE_INSENSITIVE);
	private static final Pattern CANCEL_COINS_CHAT = Pattern.compile("\\[Bazaar\\] Cancelled! Refunded ([0-9][0-9,.]*) coins from cancelling", Pattern.CASE_INSENSITIVE);

	private static int n(String s) {
		return (int) Math.round(Double.parseDouble(s.replace(",", "")));
	}

	/**
	 * Keeps your orders live from Hypixel's chat, without reopening "Your Bazaar Orders":
	 *   "[Bazaar] Buy Order Setup! 64x Enchanted Diamond for 95,000 coins."     → new order (price = total / amount)
	 *   "[Bazaar] Your Buy Order for 64x Enchanted Diamond was filled!"         → filled, waiting to be claimed
	 *   "[Bazaar] Claimed 64x Enchanted Diamond worth …" / "Claimed … coins from selling 64x …" → gone (or fewer left)
	 *   "[Bazaar] Cancelled! Refunded …"                                          → gone
	 * The menu stays the source of truth: opening it corrects everything (partial fills too).
	 */
	static void onChat(String plain) {
		List<Order> l = new ArrayList<>(orders);
		boolean changed = false;
		long now = System.currentTimeMillis();
		Matcher m = SETUP_CHAT.matcher(plain);
		if (m.find()) {
			String side = m.group(1).toLowerCase(Locale.ROOT).startsWith("buy") ? "BUY" : "SELL";
			int amount = n(m.group(2));
			String name = m.group(3).trim();
			double total = Double.parseDouble(m.group(4).replace(",", ""));
			var names = data == null ? null : data.itemNames();
			String id = names == null ? null : names.get(name.toLowerCase(Locale.ROOT));
			l.add(new Order(side, id == null ? name.toUpperCase(Locale.ROOT).replace(' ', '_') : id, name, amount > 0 ? total / amount : total, amount, 0, now));
			changed = true;
		}
		m = FILLED_CHAT.matcher(plain);
		if (m.find()) {
			String side = m.group(1).toLowerCase(Locale.ROOT).startsWith("buy") ? "BUY" : "SELL";
			String name = m.group(3).trim();
			for (int i = 0; i < l.size(); i++) {
				Order o = l.get(i);
				if (o.side().equals(side) && o.name().equalsIgnoreCase(name) && o.filled() < o.amount()) {
					l.set(i, new Order(o.side(), o.id(), o.name(), o.price(), o.amount(), o.amount(), now));
					changed = true;
					break;
				}
			}
			if (cfg != null && cfg.alertSound) Chat.ping();
		}
		m = CLAIM_BUY_CHAT.matcher(plain);
		if (m.find()) changed |= takeOut(l, "BUY", m.group(2).trim(), n(m.group(1)));
		m = CLAIM_SELL_CHAT.matcher(plain);
		if (m.find()) changed |= takeOut(l, "SELL", m.group(2).trim(), n(m.group(1)));
		m = CANCEL_ITEMS_CHAT.matcher(plain);
		if (m.find()) changed |= takeOut(l, "SELL", m.group(2).trim(), Integer.MAX_VALUE);
		m = CANCEL_COINS_CHAT.matcher(plain);
		if (m.find()) {                                                   // a buy order: find it by the coins refunded
			double coins = Double.parseDouble(m.group(1).replace(",", ""));
			Order best = null;
			double diff = Double.MAX_VALUE;
			for (Order o : l) {
				if (!o.side().equals("BUY")) continue;
				double d = Math.abs(o.price() * (o.amount() - o.filled()) - coins);
				if (d < diff) { diff = d; best = o; }
			}
			if (best != null && diff <= Math.max(1, coins * 0.02)) { l.remove(best); changed = true; }
		}
		if (changed) {
			orders = l;
			save();
		}
	}

	/** Claimed / cancelled qty of an order: fewer left, or gone. */
	private static boolean takeOut(List<Order> l, String side, String name, int qty) {
		for (int i = 0; i < l.size(); i++) {
			Order o = l.get(i);
			if (!o.side().equals(side) || !o.name().equalsIgnoreCase(name)) continue;
			int left = qty == Integer.MAX_VALUE ? 0 : o.amount() - qty;
			if (left <= 0) l.remove(i);
			else l.set(i, new Order(o.side(), o.id(), o.name(), o.price(), left, Math.max(0, o.filled() - qty), o.seen()));
			return true;
		}
		return false;
	}
}

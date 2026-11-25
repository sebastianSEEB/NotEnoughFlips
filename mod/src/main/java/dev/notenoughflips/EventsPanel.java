package dev.notenoughflips;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ChestMenu;

/**
 * "EVENTS" button above your inventory: a collapsible panel on the left with the SkyBlock events coming up (Spooky
 * Festival, Season of Jerry, New Year, Traveling Zoo, election) and what to do about the products whose price follows
 * them: BUY NOW / SELL NOW when a product is in its cheap or dear window, and when the next windows open. Only patterns
 * that held up on years they weren't picked from (Season / tools/seasonality.py). Click a row to search the Bazaar.
 * A chat reminder goes out once when a window opens (seasonAlerts).
 */
final class EventsPanel {
	private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

	/** One prompt: buy / sell now, or a window coming up. */
	record Prompt(String id, String action, long inMs, String text) {}

	private static final int W = 160, BTN_H = 14, ROW = 21;
	private static final List<Hit> hits = new ArrayList<>();
	private static NefConfig cfg;
	private static boolean open;

	private EventsPanel() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	static boolean isOpen() {
		return open;
	}

	static void close() {
		open = false;
	}

	private static boolean active(Screen s) {
		return cfg != null && cfg.eventsButton && !TradeCheck.isTrade(s) && (s instanceof InventoryScreen
				|| s instanceof AbstractContainerScreen<?> acs && acs.getMenu() instanceof ChestMenu);
	}

	private static int w(String t) {
		return Minecraft.getInstance().font.width(t);
	}

	/** Pure: what to do with each tradeable seasonal product now, act-now first, then by how soon. */
	static List<Prompt> prompts(Iterable<JsonElement> products, long now, long horizonMs) {
		List<Prompt> out = new ArrayList<>();
		for (JsonElement e : products) {
			JsonObject o = e.getAsJsonObject();
			if (!o.has("ok") || !o.get("ok").getAsBoolean() || !o.has("buy_bin")) continue;
			String id = HudFormatter.str(o, "id");
			int buy = o.get("buy_bin").getAsInt(), sell = o.get("sell_bin").getAsInt();
			double gain = o.get("gain").getAsDouble();
			String st = Season.now(o, now);
			long toSell = Season.until(now, sell);
			if ("buy now".equals(st)) {
				out.add(new Prompt(id, "BUY", 0, String.format(Locale.ROOT, "sell ~%s (%s) · %+.0f%%",
						Desk.shortDate(Season.label(sell)), Season.dur(toSell).replaceAll(" \\d+m$", ""), gain * 100)));
			} else if ("sell now".equals(st)) {
				out.add(new Prompt(id, "SELL", 0, "yearly high: sell if you hold it"));
			} else {
				long toBuy = Season.until(now, buy);
				if (toBuy <= horizonMs)
					out.add(new Prompt(id, "buy", toBuy, String.format(Locale.ROOT, "buy then, sell ~%s · %+.0f%%",
							Desk.shortDate(Season.label(sell)), gain * 100)));
			}
		}
		out.sort((a, b) -> a.inMs() != b.inMs() ? Long.compare(a.inMs(), b.inMs()) : a.action().compareTo(b.action()));
		return out;
	}

	/** The next n event starts: {name, ms until}. */
	static List<Object[]> upcoming(long now, int n) {
		List<Object[]> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int b = 0; b < Season.BINS; b++) {
			String e = Season.events(b);
			if (e.isEmpty()) continue;
			for (String name : e.split(", ")) {
				if (b > 0 && Season.events(b - 1).contains(name)) continue;       // same event, later slot
				if (!seen.add(name + "@" + b)) continue;
				long in = Season.until(now, b);
				for (int k = b + 1; k < Season.BINS && Season.events(k).contains(name); k++)
					if (Season.until(now, k) == 0) in = 0;                        // in a later slot of the same event
				out.add(new Object[]{name, in});
			}
		}
		out.sort((a, b) -> Long.compare((long) a[1], (long) b[1]));
		return out.size() > n ? out.subList(0, n) : out;
	}

	private static List<Prompt> current() {
		JsonObject sj = Season.json();
		if (sj == null || !sj.has("products")) return List.of();
		return prompts(sj.getAsJsonArray("products"), System.currentTimeMillis(), 12 * 3_600_000L);
	}

	private static String label() {
		long act = current().stream().filter(q -> q.inMs() == 0).count();
		return act > 0 ? "EVENTS " + act : "EVENTS";
	}

	/** Left of the DEALS button, on the row above the inventory. */
	private static int[] button(Screen s) {
		int[] box = GuiPos.box(s);
		String lb = label();
		int bw = w(lb) + 10;
		int right = box[0] - 3 - (s instanceof InventoryScreen && cfg.deskButton ? 34 + 3 : 0)
				- (cfg.dealsButton ? w(Deals.list().isEmpty() ? "DEALS" : "DEALS " + Deals.list().size()) + 10 + 3 : 0);
		int by = Math.max(1, box[1] - BTN_H - 2);
		if (right - bw < 2) {                                                // no room on the row: one row up, over the inventory's left edge
			by = Math.max(1, by - BTN_H - 2);
			return new int[]{box[0], by, box[0] + bw, by + BTN_H};
		}
		return new int[]{right - bw, by, right, by + BTN_H};
	}

	static void paint(Screen s, Highlight.Painter p) {
		if (!active(s)) return;
		hits.clear();
		if (FcfPanel.isOpen() || DealsPanel.isOpen()) open = false;          // they share the left side
		int[] b = button(s);
		McStyle.toggle(p, b[0], b[1], b[2], b[3], label(), open);
		hits.add(new Hit(b[0], b[1], b[2], b[3], () -> {
			open = !open;
			if (open) {
				FcfPanel.close();
				DealsPanel.close();
			}
		}));
		if (!open) return;

		int[] box = GuiPos.box(s);
		int x = Math.max(2, box[0] - W - 6), y0 = box[1], w = Math.min(W, box[0] - 8);
		if (w < 100) return;
		int maxY = s.height - 4;
		long now = System.currentTimeMillis();
		List<Object[]> events = upcoming(now, 4);
		List<Prompt> prompts = current();
		int rows = Math.min(prompts.size(), Math.max(1, (maxY - y0 - 40 - events.size() * 10 - 30) / ROW));
		int h = 18 + events.size() * 10 + 16 + Math.max(1, rows) * ROW + 12;
		McStyle.panel(p, x, y0, x + w, Math.min(maxY, y0 + h));
		hits.add(new Hit(x, y0, x + w, y0 + h, () -> {}));                   // clicks inside never reach the menu
		String today = Season.date(now);
		p.text("Events", x + 6, y0 + 6, McStyle.TEXT);
		String d = today.substring(0, today.indexOf(','));
		p.text(d, x + w - 6 - w(d), y0 + 6, McStyle.MUTED);
		int y = y0 + 18;
		for (Object[] ev : events) {
			long in = (long) ev[1];
			String when = in == 0 ? "now" : "in " + Season.dur(in);
			p.text(FcfPanel.fit((String) ev[0], w - w(when) - 18), x + 8, y, 0xFFFF77FF);
			p.text(when, x + w - 8 - w(when), y, in == 0 ? McStyle.GOOD : McStyle.MUTED);
			y += 10;
		}
		y += 4;
		p.text("What to do", x + 6, y, McStyle.TEXT);
		y += 12;
		if (Season.json() == null) {
			p.text(FcfPanel.fit("Loading price patterns…", w - 12), x + 6, y + 2, McStyle.MUTED);
			y += ROW;
		} else if (prompts.isEmpty()) {
			p.text(FcfPanel.fit("Nothing to buy or sell in the next 12h.", w - 12), x + 6, y + 2, McStyle.MUTED);
			y += ROW;
		}
		for (int i = 0; i < rows; i++) {
			Prompt q = prompts.get(i);
			String name = NefClient.data() == null ? Desk.pretty(q.id()) : Bazaar.name(q.id(), NefClient.data());
			String tag = q.inMs() == 0 ? q.action() + " NOW" : "in " + Season.dur(q.inMs());
			int col = "BUY".equals(q.action()) ? 0xFF55FF55 : "SELL".equals(q.action()) ? 0xFFFFAA00 : McStyle.MUTED;
			McStyle.slot(p, x + 4, y - 2, x + w - 4, y + ROW - 3);
			p.text(FcfPanel.fit(name, w - w(tag) - 18), x + 8, y + 1, 0xFFFFFFFF);
			p.text(tag, x + w - 8 - w(tag), y + 1, col);
			p.text(FcfPanel.fit(q.text(), w - 16), x + 8, y + 10, 0xFFE0E0E0);
			hits.add(new Hit(x + 4, y - 2, x + w - 4, y + ROW - 3, () -> Cmd.bazaar(name)));
			y += ROW;
		}
		String more = "All seasonal items ›";
		p.text(FcfPanel.fit(more, w - 12), x + 6, y + 1, McStyle.BLUE);
		hits.add(new Hit(x + 4, y - 1, x + w - 4, y + 10, () -> Desk.showSeasons(null)));
	}

	static boolean click(Screen s, double mx, double my) {
		if (!active(s)) return false;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (mx >= h.x1() && mx < h.x2() && my >= h.y1() && my < h.y2()) {
				h.action().run();
				return true;
			}
		}
		return false;
	}

	// ---- chat reminders ----

	private static final Set<String> told = new HashSet<>();

	/** Every minute: one chat line when a product's buy or sell window opens. */
	static void tick() {
		if (cfg == null || !cfg.seasonAlerts || Minecraft.getInstance().player == null) return;
		long now = System.currentTimeMillis();
		long year = Math.floorDiv(now / 1000 - Season.EPOCH_S, Season.YEAR_S);
		for (Prompt q : current()) {
			if (q.inMs() != 0) continue;
			if (!told.add(q.id() + "|" + q.action() + "|" + year)) continue;
			String name = NefClient.data() == null ? Desk.pretty(q.id()) : Bazaar.name(q.id(), NefClient.data());
			Minecraft.getInstance().execute(() -> {
				Chat.ping();
				Chat.say(HudFormatter.line("[NEF] ☀ " + ("BUY".equals(q.action()) ? "BUY " : "SELL ") + name + " now",
						"BUY".equals(q.action()) ? HudFormatter.GREEN : HudFormatter.GOLD, ": " + q.text(), HudFormatter.WHITE));
			});
		}
		if (told.size() > 500) told.clear();
	}
}

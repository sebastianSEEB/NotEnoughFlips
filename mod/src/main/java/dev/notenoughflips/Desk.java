package dev.notenoughflips;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Trade Desk: a simple ERP for your SkyBlock trading. The "DESK" button above the inventory (or /nef desk) turns the
 * inventory screen into a full-screen desk with six sections:
 *
 *   Home      key numbers, a to-do list (collect coins, outbid, overpriced listings, losses, tips) and what ends next
 *   Stock     everything you have on the AH, with filters (listed, winning, to collect)
 *   Buying    your bids: your bid, top bid, what the item is worth, profit, time left
 *   Selling   your listings: price, worth, what you get after tax, time left
 *   Finance   where your coins are and will be once everything settles, and the profit you made
 *   Market    bazaar prices (your watch list with 7-day charts) and bazaar tips (misprices, correlations)
 *
 * Clicks inside the desk never reach the inventory. Clicking a row opens that auction with /viewauction; you act yourself.
 */
final class Desk {
	// looks like a normal Minecraft menu: light grey container with bevelled edges, dark text, stone buttons;
	// dark boxes (stat slots, to-do notes) use the game's own chat colours
	private static final int BG = 0xC0101010, PANEL = 0xFFC6C6C6, INK = 0xFF404040, MUTED = 0xFF6E6E6E, LINE = 0xFF9E9E9E,
			ORANGE = 0xFF9C6500, GREEN = 0xFF1F7F1F, RED = 0xFFAA2020, AMBER = 0xFF9C6500, BLUE = 0xFF2A55A8,
			LILAC = 0xFF6E44B4, YELLOW = 0xFF7F7200, CHIP = 0xFF6F6F6F, ZEBRA = 0xFFBDBDBD;
	// chat colours (§a §c §6 §e §7 §f) for text on dark boxes
	private static final int C_GREEN = 0xFF55FF55, C_RED = 0xFFFF5555, C_GOLD = 0xFFFFAA00, C_YELLOW = 0xFFFFFF55,
			C_GRAY = 0xFFAAAAAA, C_WHITE = 0xFFFFFFFF, C_AQUA = 0xFF55FFFF;
	static final String[] DEFAULT_BAZAAR = {"STOCK_OF_STONKS", "ULTIMATE_CARROT_CANDY", "SUPERB_CARROT_CANDY", "GREAT_CARROT_CANDY",
			"SIMPLE_CARROT_CANDY", "PURPLE_CANDY", "GREEN_CANDY", "DARK_CANDY", "ROCK_CANDY"};

	private record Btn(int x1, int y1, int x2, int y2, Runnable action) {}

	/** One to-do on the Home page. level: 0 = problem, 1 = needs you, 2 = good news, 3 = tip. */
	private record Todo(int level, String title, String text, Runnable action) {}

	private static NefConfig cfg;
	private static DataClient data;
	private static boolean on;
	private static String win = "home";
	private static String stockFilter = "all";
	private static String marketView = "prices";
	private static String coinsView = "summary";
	private static boolean biWeek = true;
	private static int page;
	private static String bzSel;
	private static final List<Btn> buttons = new ArrayList<>();

	private Desk() {}

	static void init(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	static boolean isOn() {
		return on;
	}

	static void toggle() {
		on = !on;
		page = 0;
		if (on) FcfPanel.refresh(true);
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static int w(String t) {
		return mc().font.width(t);
	}

	private static void btn(int x1, int y1, int x2, int y2, Runnable r) {
		buttons.add(new Btn(x1, y1, x2, y2, r));
	}

	private static void open(String id) {
		win = id;
		page = 0;
	}

	// ================================================================== entry points
	static void paint(Screen s, Highlight.Painter p) {
		if (!on) return;
		buttons.clear();
		FcfPanel.refresh(false);
		int W = s.width, H = s.height;
		p.fill(0, 0, W, H, BG);
		JsonObject rep = FcfPanel.report();
		JsonObject f = HudFormatter.obj(rep, "fcf");
		JsonObject n = HudFormatter.obj(f, "now");
		List<JsonObject> pos = positions(f);

		int colW = Math.min(118, Math.max(92, W / 6));
		sidebar(p, 0, 0, colW, H, n, pos);
		int deskX = colW + 8, deskW = W - deskX - 6;
		window(p, deskX, 6, deskW, H - 12, f, n, pos);
	}

	/** Returns true when the Trade Desk is open (the click is used here and must not reach the inventory). */
	static boolean click(double mx, double my) {
		if (!on) return false;
		for (int i = buttons.size() - 1; i >= 0; i--) {
			Btn b = buttons.get(i);
			if (mx >= b.x1() && mx < b.x2() && my >= b.y1() && my < b.y2()) {
				b.action().run();
				break;
			}
		}
		return true;
	}

	// ================================================================== data
	private static List<JsonObject> positions(JsonObject f) {
		List<JsonObject> out = new ArrayList<>();
		JsonElement pe = f == null || !f.has("positions") ? null : f.get("positions");
		if (pe != null && pe.isJsonArray()) for (JsonElement e : pe.getAsJsonArray()) out.add(e.getAsJsonObject());
		return out;
	}

	private static double d(JsonObject o, String k) {
		return FcfPanel.d(o, k);
	}

	private static String str(JsonObject o, String k) {
		return o == null ? null : FcfPanel.str(o, k);
	}

	private static String item(JsonObject o) {
		String it = str(o, "item");
		if (it == null) it = str(o, "key");
		return it == null ? "?" : it.replaceAll("§.", "");
	}

	private static boolean sale(JsonObject o) {
		return "sale".equals(str(o, "role"));
	}

	private static boolean winning(JsonObject o) {
		return "winning".equals(str(o, "status"));
	}

	private static boolean outbid(JsonObject o) {
		return "outbid".equals(str(o, "status"));
	}

	private static boolean ended(JsonObject o) {
		return o.has("ended") && o.get("ended").getAsBoolean();
	}

	private static boolean overpriced(JsonObject o) {
		return sale(o) && o.has("overpriced") && o.get("overpriced").getAsBoolean();
	}

	private static boolean toCollect(JsonObject o) {
		return ended(o) || outbid(o);
	}

	/** Coins (or item value) waiting to be claimed for one position. */
	private static double collectAmount(JsonObject o) {
		return outbid(o) ? d(o, "amount") : sale(o) ? d(o, "net") : d(o, "value");
	}

	private static String fmt(double v) {
		return HudFormatter.fmt(v);
	}

	private static String signed(double v) {
		return (v >= 0 ? "+" : "−") + fmt(Math.abs(v));
	}

	private static String ends(JsonObject o) {
		Double e = HudFormatter.num(o, "end");
		if (e == null) return "";
		long mins = (long) ((e - System.currentTimeMillis()) / 60000);
		if (mins <= 0) return "ended";
		return mins < 60 ? mins + "m" : mins < 24 * 60 ? (mins / 60) + "h " + (mins % 60) + "m" : (mins / 1440) + "d " + (mins % 1440) / 60 + "h";
	}

	private static Double walletNow(JsonObject n) {
		Double purse = FcfPanel.purse();
		JsonObject wn = HudFormatter.obj(FcfPanel.report(), "now");
		Double bank = wn == null || HudFormatter.num(wn, "bank") == null ? null : d(wn, "bank") + d(wn, "personal_bank");
		if (purse != null) return purse + (bank == null ? 0 : bank);
		return n == null ? null : HudFormatter.num(n, "wallet");
	}

	private static double onAh(JsonObject n) {
		return n == null ? 0 : d(n, "sales") + d(n, "bids") + d(n, "refunds");
	}

	private static Runnable view(JsonObject o) {
		String uuid = str(o, "uuid");
		return uuid == null ? null : () -> Cmd.viewAuction(uuid);
	}

	// ================================================================== Minecraft-style drawing
	/** A menu container: black edge, white top-left highlight, grey bottom-right shadow (like the inventory). */
	private static void panel(Highlight.Painter p, int x1, int y1, int x2, int y2) {
		p.fill(x1, y1, x2, y2, 0xFF000000);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF555555);
		p.fill(x1 + 1, y1 + 1, x2 - 2, y2 - 2, 0xFFFFFFFF);
		p.fill(x1 + 2, y1 + 2, x2 - 2, y2 - 2, PANEL);
	}

	/** A sunken box like an item slot, filled dark so chat colours read well. */
	private static void slot(Highlight.Painter p, int x1, int y1, int x2, int y2) {
		p.fill(x1, y1, x2, y2, 0xFFFFFFFF);
		p.fill(x1, y1, x2 - 1, y2 - 1, 0xFF373737);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF5A5A5A);
	}

	/** A note in the style of an item tooltip (dark purple background, purple edge). */
	private static void note(Highlight.Painter p, int x1, int y1, int x2, int y2, int edge) {
		p.fill(x1, y1, x2, y2, 0xF0100010);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, 0x505000FF);
		p.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, 0x5028007F);
		p.fill(x1 + 1, y1 + 2, x1 + 2, y2 - 2, 0x505000FF);
		p.fill(x2 - 2, y1 + 2, x2 - 1, y2 - 2, 0x5028007F);
		if (edge != 0) p.fill(x1 + 2, y1 + 2, x1 + 4, y2 - 2, edge);
	}

	/** A stone button. Selected = white frame and yellow text, like a hovered button in the game menus. */
	private static void button(Highlight.Painter p, int x1, int y1, int x2, int y2, String label, boolean sel, Runnable r) {
		p.fill(x1, y1, x2, y2, sel ? 0xFFFFFFFF : 0xFF000000);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF6F6F6F);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, 0xFFA8A8A8);
		p.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, 0xFF4A4A4A);
		p.text(label, x1 + (x2 - x1 - w(label)) / 2, y1 + (y2 - y1 - 8) / 2 + 1, sel ? C_YELLOW : 0xFFE0E0E0);
		if (r != null) btn(x1, y1, x2, y2, r);
	}

	// ================================================================== sidebar (sections)
	/** id, name, one-line description shown next to the title. */
	private static final String[][] SECTIONS = {
			{"home", "Home", "what needs you right now"},
			{"stock", "My Auctions", "everything you have on the auction house"},
			{"buying", "Bids", "auctions you bid on"},
			{"selling", "Listings", "items you are selling"},
			{"holdings", "Bought", "what you bought: paid, now, target, progress"},
			{"finance", "Coins", "your coins and your profit"},
			{"market", "Bazaar", "prices, tips and market size"}};

	private static void sidebar(Highlight.Painter p, int x, int y, int w, int H, JsonObject n, List<JsonObject> pos) {
		int x1 = x + 4, x2 = x + w - 2, y1 = y + 6, y2 = H - 6;
		panel(p, x1, y1, x2, y2);
		int ix = x1 + 7, ly = y1 + 7;
		p.text("Trade Desk", ix, ly, INK);
		button(p, x2 - 16, ly - 3, x2 - 5, ly + 9, "x", false, () -> on = false);
		ly += 15;
		for (String[] sct : SECTIONS) {
			final String id = sct[0];
			button(p, x1 + 5, ly, x2 - 5, ly + 18, sct[1], win.equals(id), () -> open(id));
			int badge = badge(id, n, pos);
			if (badge > 0) {
				String b = String.valueOf(badge);
				int bx = x2 - 6 - w(b) - 4;
				p.fill(bx - 1, ly - 2, bx + w(b) + 3, ly + 9, 0xFF000000);
				p.fill(bx, ly - 1, bx + w(b) + 2, ly + 8, id.equals("market") ? 0xFF2A55A8 : 0xFFAA2020);
				p.text(b, bx + 1, ly, C_WHITE);
			}
			ly += 21;
		}
		// footer: purse + bank, data age
		Double wallet = walletNow(n);
		int fy = y2 - 30;
		slot(p, x1 + 5, fy, x2 - 5, y2 - 6);
		String cash = wallet == null ? "?" : fmt(wallet);
		p.text("Coins", x1 + 9, fy + 4, C_GRAY);
		p.text(cash, x2 - 9 - w(cash), fy + 4, C_GOLD);
		long mins = FcfPanel.fetchedAt() == 0 ? -1 : (long) ((System.currentTimeMillis() - d(HudFormatter.obj(FcfPanel.report(), "fcf"), "updated")) / 60000);
		p.text(FcfPanel.fit(mins < 0 ? "not checked yet" : mins < 1 ? "just now" : mins + " min ago", x2 - x1 - 18), x1 + 9, fy + 14, C_GRAY);
	}

	/** Red number = things that need you (Market: blue = strong tips). */
	private static int badge(String id, JsonObject n, List<JsonObject> pos) {
		return switch (id) {
			case "home" -> (int) todos(n, pos).stream().filter(t -> t.level() <= 1).count();
			case "buying" -> (int) pos.stream().filter(o -> !sale(o) && outbid(o)).count();
			case "selling" -> (int) pos.stream().filter(Desk::overpriced).count();
			case "market" -> strongSignals();
			default -> 0;
		};
	}

	// ================================================================== window frame
	private static void window(Highlight.Painter p, int x, int y, int w, int h, JsonObject f, JsonObject n, List<JsonObject> pos) {
		String title = win, sub = "";
		for (String[] t : SECTIONS) if (t[0].equals(win)) { title = t[1]; sub = t[2]; }
		panel(p, x, y, x + w, y + h);
		p.text(title, x + 8, y + 7, INK);
		p.text(FcfPanel.fit("- " + sub, w - w(title) - 24), x + 12 + w(title), y + 7, MUTED);
		int bx = x + 8, by = y + 22, bw = w - 16, bh = h - 28;
		boolean needsAh = !win.equals("market") && !win.equals("home") && !win.equals("holdings");
		if (needsAh && n == null) {
			p.text(FcfPanel.report() == null ? "Loading your auctions…" : "No auctions found yet. Your auctions are checked every 15 min.", bx, by + 4, MUTED);
			p.text("Tip: open the FLIP panel (press E) to find something to buy or sell.", bx, by + 16, MUTED);
			return;
		}
		switch (win) {
			case "stock" -> stock(p, bx, by, bw, bh, pos, n);
			case "buying" -> buying(p, bx, by, bw, bh, pos);
			case "selling" -> selling(p, bx, by, bw, bh, pos);
			case "holdings" -> holdings(p, bx, by, bw, bh);
			case "finance" -> coins(p, bx, by, bw, bh, f, n, pos);
			case "market" -> market(p, bx, by, bw, bh);
			default -> home(p, bx, by, bw, bh, n, pos);
		}
	}

	// ================================================================== table helper
	/** cols: header text; width share (fractions of the table width); right-aligned or not. */
	private record Col(String head, double share, boolean right) {}

	private record Row(String[] cells, int[] colors, String badge, int badgeColor, Runnable onClick) {}

	private static void table(Highlight.Painter p, int x, int y, int w, int h, Col[] cols, List<Row> rows, String foot) {
		int[] cx = new int[cols.length + 1];
		double acc = 0;
		for (int i = 0; i < cols.length; i++) {
			cx[i] = x + (int) Math.round(acc * w);
			acc += cols[i].share();
		}
		cx[cols.length] = x + w;
		for (int i = 0; i < cols.length; i++) {
			String hd = cols[i].head();
			int tx = cols[i].right() ? cx[i + 1] - 3 - w(hd) : cx[i] + 2;
			p.text(hd, tx, y, INK);
		}
		p.fill(x, y + 10, x + w, y + 11, 0xFF8B8B8B);
		int rowH = 12, avail = h - 14 - (foot != null ? 12 : 0) - 10;
		int per = Math.max(1, avail / rowH), pages = Math.max(1, (rows.size() + per - 1) / per);
		page = Math.min(page, pages - 1);
		int ry = y + 14;
		for (int r = page * per; r < Math.min(rows.size(), (page + 1) * per); r++) {
			Row row = rows.get(r);
			if (r % 2 == 1) p.fill(x, ry - 2, x + w, ry + 10, ZEBRA);
			for (int i = 0; i < cols.length; i++) {
				String c = row.cells()[i] == null ? "" : row.cells()[i];
				int colW = cx[i + 1] - cx[i] - 5;
				int tx = cx[i] + 2;
				if (i == 0 && row.badge() != null) {
					p.fill(tx, ry - 1, tx + 9, ry + 8, row.badgeColor());
					p.text(row.badge(), tx + 2, ry, 0xFFFFFFFF);
					tx += 12;
					colW -= 12;
				}
				c = FcfPanel.fit(c, colW);
				if (cols[i].right()) tx = cx[i + 1] - 3 - w(c);
				p.text(c, tx, ry, row.colors() == null || row.colors()[i] == 0 ? INK : row.colors()[i]);
			}
			if (row.onClick() != null) btn(x, ry - 2, x + w, ry + 10, row.onClick());
			ry += rowH;
		}
		if (rows.isEmpty()) p.text("Nothing here right now.", x + 2, ry + 2, MUTED);
		int fy = y + h - 10;
		if (foot != null) p.text(FcfPanel.fit(foot, w - 50), x, fy, MUTED);
		if (pages > 1) {
			String pg = "< " + (page + 1) + "/" + pages + " >";
			int px = x + w - w(pg);
			p.text(pg, px, fy, BLUE);
			btn(px - 2, fy - 2, px + 8, fy + 9, () -> page = Math.max(0, page - 1));
			btn(px + w(pg) - 10, fy - 2, px + w(pg) + 2, fy + 9, () -> page++);
		}
	}

	private static int chip(Highlight.Painter p, int x, int y, String label, boolean sel, Runnable r) {
		int cw = w(label) + 10;
		button(p, x, y - 3, x + cw, y + 10, label, sel, r);
		return x + cw + 3;
	}

	// ================================================================== Home
	private static void home(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		// key numbers as tiles; click a tile to open the matching section
		Double wallet = walletNow(n);
		double collect = 0;
		for (JsonObject o : pos) if (toCollect(o)) collect += collectAmount(o);
		double today = ProfitLog.summary(ProfitLog.startOfDay(0)).profit();
		Object[][] tiles = {
				{"Coins", wallet == null ? "?" : fmt(wallet), C_GOLD, "finance"},
				{"On the AH", fmt(onAh(n)), C_WHITE, "stock"},
				{"To collect", fmt(collect), collect > 0 ? C_GREEN : C_WHITE, "stock"},
				{"Profit today", signed(today), today >= 0 ? C_GREEN : C_RED, "finance"}};
		int gap = 4, tw = (w - gap * 3) / 4, th = 26;
		for (int i = 0; i < tiles.length; i++) {
			int tx = x + i * (tw + gap);
			slot(p, tx, y, tx + tw, y + th);
			p.text((String) tiles[i][0], tx + 5, y + 4, C_GRAY);
			p.text(FcfPanel.fit((String) tiles[i][1], tw - 10), tx + 5, y + 15, (int) tiles[i][2]);
			final String to = (String) tiles[i][3];
			final boolean collectTile = tiles[i][0].equals("To collect");
			btn(tx, y, tx + tw, y + th, () -> { open(to); if (collectTile) stockFilter = "collect"; });
		}
		int ty = y + th + 8;
		int leftW = (int) (w * 0.62), rx = x + leftW + 10, rw = w - leftW - 10;

		// to-do list
		List<Todo> todos = todos(n, pos);
		long needs = todos.stream().filter(t -> t.level() <= 1).count();
		p.text("To do", x, ty, INK);
		p.text(needs == 0 ? "- nothing urgent" : "- " + needs + " need" + (needs == 1 ? "s" : "") + " you", x + w("To do") + 5, ty, needs == 0 ? GREEN : RED);
		int cy = ty + 12, shown = 0;
		for (Todo t : todos) {
			if (cy > y + h - 24) break;
			int col = t.level() == 0 ? C_RED : t.level() == 1 ? C_GOLD : t.level() == 2 ? C_GREEN : C_AQUA;
			note(p, x, cy, x + leftW, cy + 21, 0);
			p.text(FcfPanel.fit(t.title(), leftW - 20), x + 5, cy + 3, col);
			p.text(FcfPanel.fit(t.text(), leftW - 20), x + 5, cy + 12, C_GRAY);
			if (t.action() != null) {
				p.text(">", x + leftW - 9, cy + 7, C_GRAY);
				btn(x, cy, x + leftW, cy + 21, t.action());
			}
			cy += 24;
			shown++;
		}
		if (shown < todos.size()) p.text("+" + (todos.size() - shown) + " more", x, cy, MUTED);

		// what ends next
		p.text("Ending next", rx, ty, INK);
		List<JsonObject> sorted = new ArrayList<>();
		for (JsonObject o : pos) if (!ended(o) && HudFormatter.num(o, "end") != null) sorted.add(o);
		sorted.sort((a, b) -> Double.compare(d(a, "end"), d(b, "end")));
		int ey = ty + 12;
		if (sorted.isEmpty()) p.text(FcfPanel.fit(n == null ? "Waiting for your auctions…" : "Nothing running.", rw), rx, ey, MUTED);
		for (JsonObject o : sorted) {
			if (ey > y + h - 20) break;
			boolean soon = d(o, "end") - System.currentTimeMillis() < 3_600_000L;
			String what = sale(o) ? "you sell" : winning(o) ? "you win" : "outbid";
			p.text(FcfPanel.fit(item(o), rw), rx, ey, INK);
			String left = ends(o);
			p.text(left, rx, ey + 9, soon ? RED : MUTED);
			p.text(FcfPanel.fit(" · " + what + " · " + fmt(d(o, "amount")), rw - w(left)), rx + w(left), ey + 9, MUTED);
			Runnable v = view(o);
			if (v != null) btn(rx, ey - 1, rx + rw, ey + 18, v);
			ey += 21;
		}
		p.text("Click anything to open it.", x, y + h - 9, MUTED);
	}

	/** Everything that wants attention, most urgent first. Each one opens the auction or section it is about. */
	private static List<Todo> todos(JsonObject n, List<JsonObject> pos) {
		List<Todo> m = new ArrayList<>();
		if (n != null) {
			long stale = (long) ((System.currentTimeMillis() - d(HudFormatter.obj(FcfPanel.report(), "fcf"), "updated")) / 60000);
			if (stale > 45) m.add(new Todo(0, "Auction data is " + stale + " min old", "It refreshes every 15 min, or press ⟳ in the FLIP panel.", null));
		}
		Set<String> known = new HashSet<>();
		for (JsonObject o : pos) {
			String uuid = str(o, "uuid");
			if (uuid != null) known.add(uuid);
			Double pr = HudFormatter.num(o, "profit");
			if (!sale(o) && winning(o) && pr != null && pr < 0)
				m.add(new Todo(0, "Loss on " + item(o) + ": " + signed(pr), "Your bid is higher than it resells for.", view(o)));
			if (!sale(o) && outbid(o))
				m.add(new Todo(1, "Outbid on " + item(o), "Collect your " + fmt(d(o, "amount")) + " back, or bid again.", view(o)));
			else if (ended(o))
				m.add(new Todo(2, sale(o) ? "Sold: " + item(o) : "Won: " + item(o),
						sale(o) ? "Collect " + fmt(d(o, "net")) + " coins." : "Claim your item.", view(o)));
			if (overpriced(o))
				m.add(new Todo(1, "Priced too high: " + item(o), "Listed " + fmt(d(o, "amount")) + ", worth about " + fmt(d(o, "value")) + ".", view(o)));
			Double e = HudFormatter.num(o, "end");
			long now = System.currentTimeMillis();
			if (!sale(o) && winning(o) && e != null && e > now && e - now < 600_000L)
				m.add(new Todo(2, item(o) + " ends in " + ends(o), "You are winning at " + fmt(d(o, "amount")) + ".", view(o)));
		}
		for (OutbidHud.Entry e : OutbidHud.list()) {                   // outbid notices from chat that the last scan hasn't seen
			if (e.uuid() != null && known.contains(e.uuid())) continue;
			m.add(new Todo(1, "Outbid on " + e.item(), "Someone bid " + e.outbidBy() + " coins more.", e.uuid() == null ? null : () -> Cmd.viewAuction(e.uuid())));
		}
		Double wallet = walletNow(n);
		double tied = onAh(n);
		if (wallet != null && tied > 0 && tied / (tied + wallet) > 0.6)
			m.add(new Todo(1, "Most of your coins are tied up", String.format(Locale.ROOT, "%.0f%% is on the AH. Keep some cash for deals.", 100 * tied / (tied + wallet)),
					() -> open("finance")));
		int tips = 0;
		for (JsonObject sg : signalList("all")) {                       // the strongest bazaar tips
			if (tips >= 2 || !"high".equals(str(sg, "conf"))) continue;
			final String id = str(sg, "id");
			m.add(new Todo(3, "Bazaar tip: " + str(sg, "title"), str(sg, "text"), () -> { open("market"); marketView = "tips"; sigSelId = id; }));
			tips++;
		}
		int deals = Deals.list().size();
		if (deals > 0) m.add(new Todo(3, deals + " cheap BIN(s) found", "/nef deals lists them.", null));
		m.sort((a, b) -> Integer.compare(a.level(), b.level()));
		if (m.isEmpty()) m.add(new Todo(2, "All clear", "Nothing needs you right now.", null));
		return m;
	}

	// ================================================================== Stock
	private static void stock(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos, JsonObject n) {
		int cx = x;
		for (String[] f : new String[][]{{"all", "All"}, {"listed", "Listed"}, {"winning", "Winning"}, {"collect", "To collect"}}) {
			final String id = f[0];
			cx = chip(p, cx, y, f[1], stockFilter.equals(id), () -> { stockFilter = id; page = 0; });
		}
		List<JsonObject> sorted = new ArrayList<>(pos);                // soonest to end first (ended ones on top)
		sorted.sort((a, b) -> Double.compare(d(a, "end"), d(b, "end")));
		List<Row> rows = new ArrayList<>();
		for (JsonObject o : sorted) {
			boolean s = sale(o), win = winning(o), col = toCollect(o);
			boolean keep = switch (stockFilter) {
				case "listed" -> s && !ended(o);
				case "winning" -> !s && win;
				case "collect" -> col;
				default -> true;
			};
			if (!keep) continue;
			Double val = HudFormatter.num(o, "value");
			String status = ended(o) ? (s ? "sold" : "won") : s ? "listed" : win ? "winning" : "outbid";
			int sc = col ? GREEN : win ? YELLOW : 0;
			rows.add(new Row(new String[]{item(o), String.valueOf((int) d(o, "count")), fmt(d(o, "amount")), val == null ? "-" : fmt(val), status, ends(o)},
					new int[]{0, 0, 0, 0, sc, 0}, s ? "S" : "B", s ? 0xFFF26A1B : 0xFF3D8BD9, view(o)));
		}
		table(p, x, y + 14, w, h - 14, new Col[]{new Col("Item", 0.37, false), new Col("Qty", 0.07, true), new Col("Price", 0.13, true),
				new Col("Worth", 0.13, true), new Col("Status", 0.15, false), new Col("Ends", 0.15, true)}, rows,
				"S = selling · B = bidding · green = ready to collect · on the AH: " + fmt(onAh(n)));
	}

	// ================================================================== Buying
	private static void buying(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<Row> rows = new ArrayList<>();
		double total = 0;
		for (JsonObject o : pos) {
			if (sale(o)) continue;
			boolean win = winning(o);
			Double pr = HudFormatter.num(o, "profit");
			if (win) total += d(o, "amount");
			rows.add(new Row(new String[]{item(o), fmt(d(o, "amount")), fmt(d(o, "top_bid")), HudFormatter.num(o, "value") == null ? "-" : fmt(d(o, "value")),
					pr == null ? (win ? "?" : "-") : signed(pr), win ? "winning" : "outbid", ends(o)},
					new int[]{0, 0, 0, 0, pr == null ? MUTED : pr >= 0 ? GREEN : RED, win ? GREEN : AMBER, 0}, null, 0, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Item", 0.27, false), new Col("Your bid", 0.12, true), new Col("Top bid", 0.12, true),
				new Col("Worth", 0.11, true), new Col("Profit", 0.12, true), new Col("Status", 0.13, false), new Col("Ends", 0.13, true)}, rows,
				"Winning bids: " + fmt(total) + " · Profit = resale after tax minus your bid");
	}

	// ================================================================== Selling
	private static void selling(Highlight.Painter p, int x, int y, int w, int h, List<JsonObject> pos) {
		List<Row> rows = new ArrayList<>();
		double total = 0;
		for (JsonObject o : pos) {
			if (!sale(o)) continue;
			boolean over = overpriced(o);
			total += d(o, "net");
			boolean bin = o.has("bin") && o.get("bin").getAsBoolean();
			rows.add(new Row(new String[]{item(o), fmt(d(o, "amount")), HudFormatter.num(o, "value") == null ? "-" : fmt(d(o, "value")),
					fmt(d(o, "net")), ended(o) ? "sold" : over ? "too high" : bin ? "BIN" : "auction", ends(o)},
					new int[]{0, 0, 0, 0, ended(o) ? GREEN : over ? RED : MUTED, 0}, null, 0, view(o)));
		}
		table(p, x, y, w, h, new Col[]{new Col("Item", 0.31, false), new Col("Price", 0.12, true), new Col("Worth", 0.12, true),
				new Col("You get", 0.13, true), new Col("Status", 0.17, false), new Col("Ends", 0.15, true)}, rows,
				"If everything sells you get " + fmt(total) + " (after AH tax)");
	}

	// ================================================================== Finance
	private static void finance(Highlight.Painter p, int x, int y, int w, int h, JsonObject n) {
		p.text("Your coins once everything settles", x, y, INK);
		Double wallet = walletNow(n);
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		double w0 = wallet == null ? 0 : wallet, sales = d(n, "sales"), bids = d(n, "bids"), exp = d(n, "expected"), ref = subtract ? 0 : d(n, "refunds");
		double total = w0 + sales - (subtract ? bids : 0) + exp + ref, cons = total - sales + d(n, "sales_conservative");
		Object[][] steps = {{"Coins (purse + bank)", w0, 0}, {"+ Your listings sell", sales, 1}, {"− Bids" + (subtract ? "" : " (already paid)"), -bids, subtract ? 2 : 3},
				{"+ Items you win, resold", exp, 1}, {"+ Outbid coins back", ref, 1}, {"= Total", total, 0}};
		double max = Math.max(1, Math.max(total, w0 + sales + exp + ref));
		int labW = Math.min(130, w / 3), barX = x + labW + 6, barW = w - labW - 60;
		int ry = y + 14;
		double run = 0;
		for (Object[] st : steps) {
			String lab = (String) st[0];
			double v = (double) st[1];
			int kind = (int) st[2];
			double a, b;
			if (kind == 0) { a = 0; b = v; run = v; } else if (kind == 3) { a = run + v; b = run; } else { a = run; b = run + v; run += v; }
			p.text(FcfPanel.fit(lab, labW), x, ry, kind == 0 ? INK : MUTED);
			int x1 = barX + (int) (Math.min(a, b) / max * barW), x2 = barX + (int) (Math.max(a, b) / max * barW);
			int color = kind == 0 ? 0xFF8D8D8D : kind == 1 ? 0xFF4CAF50 : kind == 2 ? 0xFFFF8A3D : 0x80FF8A3D;
			p.fill(x1, ry, Math.max(x1 + 1, x2), ry + 8, color);
			String val = kind == 0 ? fmt(v) : signed(v);
			p.text(val, x + w - w(val), ry, kind == 0 ? INK : v >= 0 ? GREEN : RED);
			ry += 13;
		}
		p.text(FcfPanel.fit("If your listings only sell at what they are worth: " + fmt(cons), w), x, ry + 2, MUTED);
		ry += 22;

		// realised profit
		p.text("Profit you made", x, ry, INK);
		ry += 13;
		Object[][] periods = {{"Today", 0}, {"Last 7 days", 6}, {"Last 30 days", 29}};
		int colW = w / 3;
		for (int i = 0; i < periods.length; i++) {
			ProfitLog.Summary s = ProfitLog.summary(ProfitLog.startOfDay((int) periods[i][1]));
			int tx = x + i * colW;
			slot(p, tx, ry, tx + colW - 4, ry + 26);
			p.text((String) periods[i][0], tx + 5, ry + 4, C_GRAY);
			p.text(signed(s.profit()) + "  (" + s.flips() + " flip" + (s.flips() == 1 ? "" : "s") + ")", tx + 5, ry + 15, s.profit() >= 0 ? C_GREEN : C_RED);
		}
		ry += 32;
		p.text(FcfPanel.fit("Profit = what you sold for after tax, minus what you paid. More: /nef profit", w), x, ry, MUTED);
	}

	// ================================================================== Bought (holdings)
	private static String holdFilter = "all";

	/** Item id for a bought item: the plan's id, else its name looked up (reforge words dropped). */
	private static String idFor(String item, Holdings.Plan plan) {
		if (plan != null && plan.key != null) return plan.key;
		var names = data == null ? null : data.itemNames();
		if (names == null) return null;
		String[] w = Holdings.norm(item).split(" ");
		for (int drop = 0; drop <= Math.min(2, w.length - 1); drop++) {
			String id = names.get(String.join(" ", java.util.Arrays.copyOfRange(w, drop, w.length)));
			if (id != null) return id;
		}
		return null;
	}

	/**
	 * Everything you bought and still hold: what you paid, what it would sell for now, the target (the price the flip,
	 * snipe, deal or mispricing expected), how far it has come (0 % = still at your price, 100 % = target reached) and
	 * the profit if you sold now. Below: profit already made per reason (sold, last 7 days).
	 */
	private static void holdings(Highlight.Painter p, int x, int y, int w, int h) {
		int cx = x;
		for (String[] f : new String[][]{{"all", "All"}, {"ah", "Auction House"}, {"bz", "Bazaar"}, {"mis", "Mispricing"}}) {
			final String id = f[0];
			cx = chip(p, cx, y, f[1], holdFilter.equals(id), () -> { holdFilter = id; page = 0; });
		}
		double tax = cfg.bazaarTaxPercent / 100.0;
		List<Row> rows = new ArrayList<>();
		double open = 0, unreal = 0;
		for (ProfitLog.Lot lot : ProfitLog.openLots()) {
			Holdings.Plan plan = Holdings.forBuy(lot.ts(), lot.item());
			boolean ah = "AH".equals(lot.market());
			boolean keep = switch (holdFilter) {
				case "ah" -> ah;
				case "bz" -> !ah;
				case "mis" -> plan != null && "Mispricing".equals(plan.source);
				default -> true;
			};
			if (!keep) continue;
			String id = idFor(lot.item(), plan);
			Double now = null, target = plan != null ? plan.target : null;
			if (ah) {
				MarketBook.Item b = id == null ? null : MarketBook.get(id);
				JsonObject doc = id == null ? null : NefClient.docFor(id, false);
				Double lowest = b != null ? Double.valueOf(b.lowest()) : doc == null ? null : HudFormatter.num(HudFormatter.obj(doc, "market", "listed"), "lowest");
				if (lowest != null) now = Market.undercut(lowest);
				if (target == null && doc != null) target = Verdict.fair(doc);
			} else if (id != null) {
				DataClient.Live l = data.live(id);
				if (l != null && l.buy() > 0) now = l.buy() - 0.1;                   // a sell offer just under the cheapest
			}
			double cost = lot.unitCost();
			Double netNow = now == null ? null : ah ? Verdict.netAfterFees(now) : now * (1 - tax);
			Double ifSold = netNow == null ? null : (netNow - cost) * lot.qty();
			open += cost * lot.qty();
			if (ifSold != null) unreal += ifSold;
			String prog = "-";
			int pc = MUTED;
			if (now != null && target != null && target > cost * 1.0001) {
				double pr = (now - cost) / (target - cost);
				prog = pr >= 1 ? "✓ " + Math.round(pr * 100) + "%" : Math.round(Math.max(-99, pr * 100)) + "%";
				pc = pr >= 1 ? GREEN : pr >= 0 ? YELLOW : RED;
			}
			long ageH = (System.currentTimeMillis() - lot.ts()) / 3_600_000L;
			String why = plan == null ? (ah ? "AH buy" : "bazaar buy") : plan.source;
			final String name = Holdings.norm(lot.item()).isEmpty() ? lot.item() : lot.item().replaceAll("§.", "");
			rows.add(new Row(new String[]{name, why, fmtQty(lot.qty()), fmt(cost), now == null ? "…" : fmt(now),
					target == null ? "-" : fmt(target), prog, ifSold == null ? "-" : signed(ifSold), ageH < 48 ? ageH + "h" : (ageH / 24) + "d"},
					new int[]{0, MUTED, 0, 0, 0, 0, pc, ifSold == null ? MUTED : ifSold >= 0 ? GREEN : RED, MUTED}, ah ? "A" : "B",
					ah ? 0xFFF26A1B : 0xFF3D8BD9, () -> {
						String clean = name.replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
						if (ah) Cmd.ahSearch(clean); else Cmd.bazaar(clean);
					}));
		}
		StringBuilder made = new StringBuilder("Sold, last 7 days: ");
		var by = Holdings.realizedBySource(ProfitLog.startOfDay(6));
		if (by.isEmpty()) made.append("nothing yet");
		boolean first = true;
		for (var e : by.entrySet()) {
			made.append(first ? "" : " · ").append(e.getKey()).append(' ').append(signed(e.getValue()[0])).append(" (").append((int) e.getValue()[1]).append(')');
			first = false;
		}
		p.text(FcfPanel.fit("In stock " + fmt(open) + " · if sold now " + signed(unreal), w - cx + x - 6), cx + 4, y, unreal >= 0 ? GREEN : RED);
		table(p, x, y + 14, w, h - 14, new Col[]{new Col("Item", 0.25, false), new Col("Why", 0.12, false), new Col("Qty", 0.06, true),
				new Col("Paid", 0.10, true), new Col("Now", 0.10, true), new Col("Goal", 0.10, true), new Col("Done", 0.09, true),
				new Col("If sold", 0.12, true), new Col("Age", 0.06, true)}, rows, made.toString());
	}

	private static String fmtQty(double q) {
		return q == Math.rint(q) ? String.valueOf((long) q) : String.format(Locale.ROOT, "%.1f", q);
	}

	// ================================================================== Coins: tabs
	private static void coins(Highlight.Painter p, int x, int y, int w, int h, JsonObject f, JsonObject n, List<JsonObject> pos) {
		int cx = x;
		for (String[] t : new String[][]{{"summary", "Summary"}, {"breakdown", "Where they are"}, {"history", "History"}, {"report", "Open auctions"}}) {
			final String id = t[0];
			cx = chip(p, cx, y, t[1], coinsView.equals(id), () -> coinsView = id);
		}
		int cy = y + 15, ch = h - 15;
		switch (coinsView) {
			case "breakdown" -> breakdown(p, x, cy, w, ch, n, pos);
			case "history" -> { if (f != null) history(p, x, cy, w, ch, f, n); }
			case "report" -> report(p, x, cy, w, ch, n, pos);
			default -> finance(p, x, cy, w, ch, n);
		}
	}

	private static void history(Highlight.Painter p, int x, int y, int w, int h, JsonObject f, JsonObject n) {
		p.text("Your coins over time", x, y, INK);
		int rx = x + w - w("Last week") - w("Last 24h") - 26;
		rx = chip(p, rx, y, "Last week", biWeek, () -> biWeek = true);
		chip(p, rx, y, "Last 24h", !biWeek, () -> biWeek = false);

		long now = System.currentTimeMillis(), from = now - (biWeek ? 7 : 1) * 86_400_000L;
		List<long[]> fcfPts = new ArrayList<>(), ahPts = new ArrayList<>();
		JsonObject wn = HudFormatter.obj(FcfPanel.report(), "now");
		Double bank = wn == null || HudFormatter.num(wn, "bank") == null ? null : d(wn, "bank") + d(wn, "personal_bank");
		JsonElement se = f.has("series") ? f.get("series") : null;
		if (se != null && se.isJsonArray()) {
			for (JsonElement e : se.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				Double tsD = HudFormatter.num(o, "ts");
				if (tsD == null || tsD < from) continue;
				long ts = tsD.longValue();
				Double fc = HudFormatter.num(o, "fcf"), ah = HudFormatter.num(o, "on_ah");
				if (ah == null && fc != null && HudFormatter.num(o, "wallet") != null) ah = fc - d(o, "wallet");
				if (fc == null && ah != null) {
					Double pu = PurseLog.at(ts);
					if (pu != null) fc = ah + pu + (bank == null ? 0 : bank);
				}
				if (fc != null) fcfPts.add(new long[]{ts, Math.round(fc)});
				if (ah != null) ahPts.add(new long[]{ts, Math.round(ah)});
			}
		}
		Double wallet = walletNow(n);
		if (wallet != null) fcfPts.add(new long[]{now, Math.round(wallet + d(n, "on_ah"))});
		ahPts.add(new long[]{now, Math.round(d(n, "on_ah"))});
		// legend
		int ly = y + 14;
		p.fill(x, ly + 3, x + 16, ly + 6, 0xFF9467BD);
		p.text("All your coins", x + 20, ly, INK);
		p.fill(x + 90, ly + 3, x + 106, ly + 6, 0xFFFF7F0E);
		p.text("On the AH", x + 110, ly, INK);
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (List<long[]> l : List.of(fcfPts, ahPts)) for (long[] q : l) { lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]); }
		if (lo == Long.MAX_VALUE) return;
		long pad = Math.max(1, (hi - lo) / 10);
		lo = Math.max(0, lo - pad);
		hi += pad;
		int gx = x + 34, gy = ly + 14, gw = w - 38, gh = h - (gy - y) - 24;
		for (int k = 0; k <= 4; k++) {
			long v = lo + (hi - lo) * k / 4;
			int yy = gy + gh - (int) ((double) (v - lo) / (hi - lo) * gh);
			p.fill(gx, yy, gx + gw, yy + 1, LINE);
			String t = fmt((double) v);
			p.text(t, gx - 3 - w(t), yy - 4, MUTED);
		}
		p.text(biWeek ? "7 days ago" : "24h ago", gx, gy + gh + 3, MUTED);
		p.text("now", gx + gw - w("now"), gy + gh + 3, MUTED);
		FcfPanel.line(p, ahPts, from, now, lo, hi, gx, gy, gw, gh, 0xFFFF7F0E);
		FcfPanel.line(p, fcfPts, from, now, lo, hi, gx, gy, gw, gh, 0xFF9467BD);
		p.text("Updated every time your auctions are checked", x + (w - w("Updated every time your auctions are checked")) / 2, y + h - 10, BLUE);
	}

	private static void breakdown(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		if (n == null) return;
		p.text("Where your coins are right now", x, y, INK);
		Double purse = FcfPanel.purse();
		JsonObject wn = HudFormatter.obj(FcfPanel.report(), "now");
		double bank = wn == null ? d(n, "bank") : d(wn, "bank") + d(wn, "personal_bank");
		double pu = purse != null ? purse : HudFormatter.num(n, "purse") != null ? d(n, "purse") : 0;
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		String[] labels = {"Purse", "Bank", "Listed items", "In winning bids", "Outbid (to collect)"};
		double[] v = {pu, bank, d(n, "sales"), d(n, "bids"), subtract ? 0 : d(n, "refunds")};
		double total = 0;
		for (double a : v) total += Math.max(0, a);
		int r = Math.min(46, (h - 30) / 2);
		int cx = x + r + 4, cy = y + 16 + r;
		if (total > 0) FcfPanel.pie(p, cx, cy, r, v, total);
		int lx = cx + r + 14, ly = y + 18;
		for (int i = 0; i < labels.length; i++) {
			p.fill(lx, ly + 1, lx + 16, ly + 7, FcfPanel.SLICE[i]);
			p.text(labels[i], lx + 20, ly, INK);
			String val = fmt(v[i]) + String.format(Locale.ROOT, "  %.0f%%", total > 0 ? 100 * Math.max(0, v[i]) / total : 0);
			p.text(val, x + w - w(val), ly, INK);
			ly += 13;
		}
		p.fill(lx, ly, x + w, ly + 1, LINE);
		p.text("Total", lx + 20, ly + 3, INK);
		p.text(fmt(total), x + w - w(fmt(total)), ly + 3, INK);
		ly += 18;
		List<JsonObject> top = new ArrayList<>(pos);
		top.sort((a, b) -> Double.compare(FcfPanel.tied(b), FcfPanel.tied(a)));
		p.text("Biggest positions", lx, ly, MUTED);
		ly += 11;
		for (int i = 0; i < Math.min(4, top.size()) && ly < y + h - 10; i++) {
			JsonObject o = top.get(i);
			String val = fmt(FcfPanel.tied(o));
			p.text(FcfPanel.fit(item(o), x + w - lx - w(val) - 6), lx, ly, INK);
			p.text(val, x + w - w(val), ly, INK);
			Runnable r2 = view(o);
			if (r2 != null) btn(lx, ly - 1, x + w, ly + 9, r2);
			ly += 11;
		}
	}

	private static final String[] CAT_ORDER = {"weapon", "armor", "accessories", "consumables", "blocks", "pets", "misc"};

	/**
	 * Coins traded per day on each market: Bazaar (from its weekly volumes), Auction House split into BIN and normal
	 * auctions (measured from Hypixel's ended-auctions feed while you play, see AhMarket), the AH by category, the
	 * busiest bazaar products, and your own share (your logged trades of the last 24 h).
	 */
	private static void marketSize(Highlight.Painter p, int x, int y, int w, int h) {
		p.text("Coins traded per day on each market", x, y, INK);
		Map<String, Double> bzMap = AhMarket.bazaarPerDay(data.bazaarAll());
		double bz = 0;
		for (double v : bzMap.values()) bz += v;
		AhMarket.Day ah = AhMarket.day();
		double[] v = {bz, ah.bin(), ah.auction()};
		String[] labels = {"Bazaar", "AH – BIN", "AH – auctions"};
		double total = bz + ah.total();
		int r = Math.min(40, (h - 40) / 3);
		int cx = x + r + 4, cy = y + 16 + r;
		if (total > 0) FcfPanel.pie(p, cx, cy, r, v, total);
		int lx = cx + r + 14, ly = y + 16;
		for (int i = 0; i < 3; i++) {
			p.fill(lx, ly + 1, lx + 16, ly + 7, FcfPanel.SLICE[i]);
			p.text(labels[i], lx + 20, ly, INK);
			String val = fmt(v[i]) + "/day" + String.format(Locale.ROOT, "  %.0f%%", total > 0 ? 100 * v[i] / total : 0);
			p.text(val, x + w - w(val), ly, INK);
			ly += 12;
		}
		p.fill(lx, ly, x + w, ly + 1, LINE);
		p.text("Total", lx + 20, ly + 3, INK);
		p.text(fmt(total) + "/day", x + w - w(fmt(total) + "/day"), ly + 3, INK);
		ly += 15;
		String note = ah.hoursMeasured() <= 0 ? "AH: measuring… (Hypixel's sold-auctions feed, every 30 s while you play)"
				: ah.scaled() ? String.format(Locale.ROOT, "AH measured over %.1f h, scaled to a day · %d sales/day", ah.hoursMeasured(), ah.sales())
				: "AH: last 24 h · " + ah.sales() + " sales";
		p.text(FcfPanel.fit(note, x + w - lx), lx, ly, MUTED);
		ly += 11;
		double[] mine = ProfitLog.volume(System.currentTimeMillis() - 86_400_000L);
		String you = String.format(Locale.ROOT, "You (24 h): bazaar %s (%s of it) · AH %s (%s)", fmt(mine[0]),
				share(mine[0], bz), fmt(mine[1]), share(mine[1], ah.total()));
		p.text(FcfPanel.fit(you, x + w - lx), lx, ly, INK);
		// lower half: AH by category | busiest bazaar products
		int by = Math.max(cy + r + 10, ly + 14), half = (w - 10) / 2;
		p.text("AH by category", x, by, INK);
		p.text("Busiest bazaar products", x + half + 10, by, INK);
		int ry = by + 12;
		double catTotal = 0;
		for (double c : ah.byCategory().values()) catTotal += c;
		for (String c : CAT_ORDER) {
			Double cv = ah.byCategory().get(c);
			if (cv == null || cv <= 0 || ry > y + h - 10) continue;
			int bw = (int) ((half - 90) * cv / Math.max(1, catTotal));
			p.fill(x + 62, ry + 1, x + 62 + Math.max(1, bw), ry + 7, 0xFFB07A00);
			p.text(Character.toUpperCase(c.charAt(0)) + c.substring(1), x, ry, INK);
			String val = fmt(cv);
			p.text(val, x + half - w(val), ry, MUTED);
			ry += 10;
		}
		int ry2 = by + 12;
		for (Map.Entry<String, Double> e : AhMarket.top(bzMap, 8)) {
			if (ry2 > y + h - 10) break;
			String val = fmt(e.getValue());
			p.text(FcfPanel.fit(Bazaar.name(e.getKey(), data), half - w(val) - 6), x + half + 10, ry2, INK);
			p.text(val, x + w - w(val), ry2, MUTED);
			ry2 += 10;
		}
	}

	private static String share(double mine, double market) {
		if (market <= 0 || mine <= 0) return "0%";
		double pct = 100 * mine / market;
		return pct >= 0.1 ? String.format(Locale.ROOT, "%.1f%%", pct) : String.format(Locale.ROOT, "%.4f%%", pct);
	}

	/** Income-statement style: what your open positions are worth against what you paid / asked. */
	private static void report(Highlight.Painter p, int x, int y, int w, int h, JsonObject n, List<JsonObject> pos) {
		double bidCost = 0, winValue = 0, listAsk = 0, listValue = 0, listNet = 0, refunds = 0;
		int unvalued = 0;
		for (JsonObject o : pos) {
			if (sale(o)) {
				listAsk += d(o, "amount");
				listNet += d(o, "net");
				listValue += HudFormatter.num(o, "value") == null ? d(o, "amount") : Math.min(d(o, "amount"), d(o, "value"));
			} else if (winning(o)) {
				bidCost += d(o, "amount");
				if (HudFormatter.num(o, "expected_net") == null) unvalued++;
				winValue += d(o, "expected_net");
			} else refunds += d(o, "amount");
		}
		Object[][] lines = {
				{"What your open auctions are worth", null, true},
				{"Items you are winning, resold after tax", winValue, false},
				{"− Cost of winning bids", -bidCost, false},
				{"= Profit on your bids", winValue - bidCost, true},
				{"", null, false},
				{"Your listings at their price", listAsk, false},
				{"− AH tax", listNet - listAsk, false},
				{"− Priced above what they are worth", listValue - listAsk, false},
				{"= What your listings will really bring", listValue - (listAsk - listNet), true},
				{"", null, false},
				{"Outbid coins to collect", refunds, false},
		};
		int ry = y;
		for (Object[] l : lines) {
			String lab = (String) l[0];
			Double v = (Double) l[1];
			boolean bold = (boolean) l[2];
			p.text(lab, x, ry, bold ? INK : MUTED);
			if (v != null) {
				String t = signedIf(v, lab.startsWith("−") || lab.startsWith("="));
				p.text(t, x + w - w(t), ry, bold ? (v >= 0 ? GREEN : RED) : INK);
			}
			if (bold && v != null) p.fill(x, ry - 2, x + w, ry - 1, LINE);
			ry += 12;
		}
		if (unvalued > 0) p.text(unvalued + " winning item(s) have no value estimate yet", x, ry + 4, MUTED);
	}

	private static String signedIf(double v, boolean sign) {
		return sign ? signed(v) : fmt(v);
	}


	// ================================================================== Market
	private static void market(Highlight.Painter p, int x, int y, int w, int h) {
		int strong = strongSignals();
		int cx = chip(p, x, y, "Prices", marketView.equals("prices"), () -> { marketView = "prices"; page = 0; });
		cx = chip(p, cx, y, strong > 0 ? "Tips (" + strong + ")" : "Tips", marketView.equals("tips"), () -> { marketView = "tips"; page = 0; });
		chip(p, cx, y, "Market size", marketView.equals("size"), () -> { marketView = "size"; page = 0; });
		switch (marketView) {
			case "tips" -> signals(p, x, y + 15, w, h - 15);
			case "size" -> marketSize(p, x, y + 15, w, h - 15);
			default -> bazaar(p, x, y + 15, w, h - 15);
		}
	}

	// ---------------------------------------------------------------- bazaar signals (misprices, correlations)
	private static String sigView = "suggest", sigFilter = "all", sigSelId;

	private static List<JsonObject> signalList(String filter) {
		List<JsonObject> out = new ArrayList<>();
		JsonObject sj = data == null ? null : data.bzSignals();
		if (sj == null || !sj.has("signals")) return out;
		for (JsonElement e : sj.getAsJsonArray("signals")) {
			JsonObject o = e.getAsJsonObject();
			String a = str(o, "action");
			boolean keep = switch (filter) {
				case "buy" -> "buy".equals(a) || "prefer".equals(a);
				case "sell" -> "sell".equals(a) || "avoid".equals(a) || "watch".equals(a);
				case "craft" -> "craft".equals(a) || "premium".equals(str(o, "type"));
				default -> true;
			};
			if (keep) out.add(o);
		}
		return out;
	}

	private static int strongSignals() {
		return (int) signalList("all").stream().filter(o -> "high".equals(str(o, "conf"))).count();
	}

	private static final String[][] SIG_TYPES = {
			{"cheap", "B"}, {"rich", "S"}, {"pair", "P"}, {"follow", "F"}, {"craft", "C"}, {"premium", "$"}, {"event", "!"}};

	private static String letter(String type) {
		for (String[] t : SIG_TYPES) if (t[0].equals(type)) return t[1];
		return "?";
	}

	private static int typeColor(String type) {
		return switch (type == null ? "" : type) {
			case "cheap" -> GREEN;
			case "rich" -> RED;
			case "pair" -> BLUE;
			case "follow" -> LILAC;
			case "craft" -> ORANGE;
			case "event" -> 0xFF9B2335;
			default -> 0xFFB8960C;
		};
	}

	private static int confColor(String c) {
		return "high".equals(c) ? GREEN : "medium".equals(c) ? 0xFFB8960C : MUTED;
	}

	/**
	 * How the suggestions did over the last week: each signal re-made from the bars up to that moment only, then
	 * followed forward (tools/backtest_signals.py). "Won" = made money after tax; "Market" = buying anything liquid
	 * at the same moments, for comparison.
	 */
	private static void backtest(Highlight.Painter p, int x, int y, int w, int h) {
		JsonObject bt = data.backtest();
		if (bt == null || !bt.has("groups")) {
			p.text("Loading the backtest… (made with the bazaar signals, every 2 h)", x, y + 2, MUTED);
			return;
		}
		List<Row> rows = new ArrayList<>();
		for (JsonElement e : bt.getAsJsonArray("groups")) {
			JsonObject g = e.getAsJsonObject();
			String type = str(g, "type"), conf = str(g, "conf"), act = str(g, "action"), hz = str(g, "horizon");
			int n = (int) d(g, "n");
			if ("ALL".equals(type) || n < 8 || !("24h".equals(hz) || "48h".equals(hz))) continue;
			String label = switch (type == null ? "" : type) {
				case "cheap" -> "Dropped, buy";
				case "rich" -> "Jumped (sell now?)";
				case "pair" -> "Lagging pair";
				case "follow" -> "Follows a leader";
				case "event" -> "Market event";
				default -> type;
			} + " · " + conf;
			double med = d(g, "median_ret"), won = d(g, "win_rate");
			Double base = HudFormatter.num(g, "baseline_median");
			rows.add(new Row(new String[]{label, hz, String.valueOf(n), Math.round(100 * d(g, "hit_rate")) + "%",
					Math.round(100 * won) + "%", String.format(Locale.ROOT, "%+.1f%%", 100 * med),
					base == null ? "-" : String.format(Locale.ROOT, "%+.1f%%", 100 * base)},
					new int[]{0, MUTED, MUTED, 0, won >= 0.6 ? GREEN : won < 0.45 ? RED : 0, med > 0 ? GREEN : RED, MUTED},
					"buy".equals(act) ? "B" : "S", "buy".equals(act) ? 0xFF3D8BD9 : 0xFFF26A1B, null));
		}
		table(p, x, y, w, h, new Col[]{new Col("Signal", 0.34, false), new Col("Hold", 0.08, true), new Col("Trades", 0.10, true),
				new Col("Hit", 0.10, true), new Col("Won", 0.10, true), new Col("Median", 0.14, true), new Col("Market", 0.14, true)}, rows,
				"Last week, no look-ahead · Hit = reached the target · Won = profit after tax · S rows: + = selling early was right");
	}

	private static void signals(Highlight.Painter p, int x, int y, int w, int h) {
		JsonObject sj = data.bzSignals();
		int cx = chip(p, x, y, "Suggestions", sigView.equals("suggest"), () -> { sigView = "suggest"; page = 0; });
		cx = chip(p, cx, y, "Correlations", sigView.equals("corr"), () -> { sigView = "corr"; page = 0; });
		cx = chip(p, cx, y, "Backtest", sigView.equals("backtest"), () -> { sigView = "backtest"; page = 0; });
		cx += 10;
		if (sigView.equals("backtest")) {
			backtest(p, x, y + 16, w, h - 16);
			return;
		}
		if (sigView.equals("suggest")) {
			for (String[] f : new String[][]{{"all", "All"}, {"buy", "Buy"}, {"sell", "Sell"}, {"craft", "Craft"}}) {
				final String id = f[0];
				cx = chip(p, cx, y, f[1], sigFilter.equals(id), () -> { sigFilter = id; page = 0; });
			}
		}
		if (sj == null) {
			p.text("Loading bazaar signals… (published every 2 h)", x, y + 18, MUTED);
			return;
		}
		long age = (System.currentTimeMillis() - (long) d(sj, "generated")) / 60000;
		String src = (age < 120 ? age + "m" : (age / 60) + "h") + " ago · " + (int) d(sj, "products") + " products · "
				+ (int) d(sj, "pairs") + " pairs";
		p.text(src, x + w - w(src), y, MUTED);
		int ty = y + 14;
		if (sigView.equals("corr")) {
			correlations(p, x, ty, w, h - 14, sj);
			return;
		}
		List<JsonObject> list = signalList(sigFilter);
		JsonObject sel = null;
		for (JsonObject o : list) if (str(o, "id").equals(sigSelId)) sel = o;
		if (sel == null && !list.isEmpty()) {
			sel = list.get(0);
			sigSelId = str(sel, "id");
		}
		int tableH = Math.max(60, (int) (h * 0.48));
		List<Row> rows = new ArrayList<>();
		for (JsonObject o : list) {
			String id = str(o, "id"), conf = str(o, "conf"), type = str(o, "type");
			double edge = d(o, "edge");
			boolean isSel = id.equals(sigSelId);
			rows.add(new Row(new String[]{str(o, "name"), str(o, "action"), fmt(d(o, "price")), fmt(d(o, "target")),
					String.format(Locale.ROOT, "%+.1f%%", edge * 100), conf},
					new int[]{isSel ? ORANGE : 0, 0, 0, 0, edge >= 0 ? GREEN : RED, confColor(conf)},
					letter(type), typeColor(type), () -> sigSelId = id));
		}
		table(p, x, ty, w, tableH, new Col[]{new Col("Product", 0.36, false), new Col("Do", 0.10, false), new Col("Now", 0.13, true),
				new Col("Target", 0.13, true), new Col("Edge", 0.13, true), new Col("Conf.", 0.15, true)}, rows,
				"B cheap · S rich · P pair · F follows · C craft · $ cheaper form · ! market event");
		if (sel != null) signalDetail(p, x, ty + tableH + 4, w, h - tableH - 18, sel, sj);
	}

	/** Explanation, purse-sized quantity, buttons and a 7-day chart (both products of a pair, indexed to the start). */
	private static void signalDetail(Highlight.Painter p, int x, int y, int w, int h, JsonObject o, JsonObject sj) {
		p.fill(x, y - 2, x + w, y - 1, LINE);
		String id = str(o, "id"), name = str(o, "name"), other = str(o, "other"), action = str(o, "action");
		p.text(FcfPanel.fit(str(o, "title"), w), x, y + 1, INK);
		p.text(FcfPanel.fit(str(o, "text"), w), x, y + 11, typeColor(str(o, "type")));
		int ly = y + 22;
		if (o.has("why")) for (JsonElement e : o.getAsJsonArray("why")) {
			p.text(FcfPanel.fit("• " + e.getAsString(), w), x, ly, MUTED);
			ly += 10;
		}
		double price = d(o, "price"), edge = d(o, "edge");
		double budget = FlipPanel.budget();
		double perRound = d(o, "units_h") * cfg.bazaarSharePercent / 100.0 * Math.max(0.25, cfg.bazaarRoundHours);
		long qty = (long) Math.max(0, Math.min(Math.min(perRound, price > 0 ? budget / price : 0), Market.MAX_ORDER));
		String size;
		if ("event".equals(str(o, "type"))) size = "Market event: these prices just changed for a reason. Flip lists ignore the members until it settles.";
		else if ("sell".equals(action)) size = "For what you hold: about " + fmt(price * edge) + " more per unit than its usual price.";
		else if ("prefer".equals(action)) size = "Use this one when you need the item; no flip margin after tax.";
		else if ("watch".equals(action)) size = "Jumps like this usually kept rising for 1-2 days in the backtest: don't buy into it.";
		else size = qty < 1 ? "Your flip budget is below one unit." : String.format(Locale.ROOT, "Size: %,d units (%s of your %s flip budget) → ≈ +%s if it gets back to target",
				qty, fmt(qty * price), fmt(budget), fmt(qty * price * edge));
		p.text(FcfPanel.fit(size, w), x, ly + 1, INK);
		ly += 13;
		int bx = x;
		String b1 = "Open " + name + " in Bazaar";
		button(p, bx, ly - 3, bx + w(b1) + 10, ly + 10, b1, true, () -> {
			on = false;
			if ("buy".equals(action) || "prefer".equals(action)) FlipPanel.pick(id, name, "Mispricing", d(o, "target"));
			else FlipPanel.pick(id, name);
		});
		bx += w(b1) + 13;
		boolean watched = bazaarList().contains(id);
		String b2 = watched ? "Watching" : "+ Watch";
		button(p, bx, ly - 3, bx + w(b2) + 10, ly + 10, b2, false, () -> {
			if (!bazaarList().contains(id)) {
				List<String> l = bazaarList();
				l.add(id);
				cfg.bazaarWatch = l;
				cfg.save();
			}
		});
		bx += w(b2) + 13;
		if (other != null) {
			String on2 = HudFormatter.str(HudFormatter.obj(sj, "names"), other);
			String b3 = "Open " + (on2 == null ? pretty(other) : on2);
			final String oname = on2 == null ? pretty(other) : on2;
			button(p, bx, ly - 3, bx + w(b3) + 10, ly + 10, b3, false, () -> { on = false; FlipPanel.pick(other, oname); });
		}
		ly += 14;
		sparkChart(p, x, ly, w, y + h - ly, sj, id, other);
	}

	private static void sparkChart(Highlight.Painter p, int x, int y, int w, int h, JsonObject sj, String a, String b) {
		if (h < 34) return;
		JsonObject sp = HudFormatter.obj(sj, "spark");
		List<long[]> la = spark(sp, a), lb = b == null ? List.of() : spark(sp, b);
		if (la.isEmpty()) return;
		long from = Long.MAX_VALUE, to = Long.MIN_VALUE, lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (List<long[]> l : List.of(la, lb)) for (long[] q : l) {
			from = Math.min(from, q[0]); to = Math.max(to, q[0]); lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]);
		}
		long pad = Math.max(1, (hi - lo) / 10);
		lo -= pad;
		hi += pad;
		int gx = x + 34, gy = y + 10, gw = w - 38, gh = h - 20;
		p.fill(gx, gy + gh - (int) ((10000.0 - lo) / (hi - lo) * gh), gx + gw, gy + gh - (int) ((10000.0 - lo) / (hi - lo) * gh) + 1, LINE);
		for (int k = 0; k <= 2; k++) {
			long v = lo + (hi - lo) * k / 2;
			int yy = gy + gh - (int) ((double) (v - lo) / (hi - lo) * gh);
			String t = String.format(Locale.ROOT, "%+.0f%%", (v / 10000.0 - 1) * 100);
			p.text(t, gx - 3 - w(t), yy - 4, MUTED);
		}
		JsonObject names = HudFormatter.obj(sj, "names");
		String na = HudFormatter.str(names, a), nb = b == null ? null : HudFormatter.str(names, b);
		p.fill(x, y + 3, x + 12, y + 5, ORANGE);
		p.text((na == null ? pretty(a) : na) + " (price vs 7 days ago)", x + 15, y, INK);
		if (nb != null || b != null) {
			int lx = x + 20 + w((na == null ? pretty(a) : na) + " (price vs 7 days ago)") + 8;
			p.fill(lx, y + 3, lx + 12, y + 5, BLUE);
			p.text(nb == null ? pretty(b) : nb, lx + 15, y, INK);
		}
		FcfPanel.line(p, lb, from, to, lo, hi, gx, gy, gw, gh, BLUE);
		FcfPanel.line(p, la, from, to, lo, hi, gx, gy, gw, gh, ORANGE);
	}

	/** Spark series indexed to 10 000 at its first value (so two products share one % axis). */
	private static List<long[]> spark(JsonObject sp, String id) {
		List<long[]> out = new ArrayList<>();
		if (sp == null || id == null || !sp.has(id) || !sp.get(id).isJsonArray()) return out;
		JsonArray a = sp.getAsJsonArray(id);
		if (a.size() < 3) return out;
		long t0 = a.get(0).getAsLong(), step = a.get(1).getAsLong();
		double first = Double.NaN;
		for (int i = 2; i < a.size(); i++) {
			if (a.get(i).isJsonNull()) continue;
			double v = a.get(i).getAsDouble();
			if (Double.isNaN(first)) first = v;
			if (first > 0) out.add(new long[]{t0 + (i - 2) * step, Math.round(v / first * 10000)});
		}
		return out;
	}

	private static void correlations(Highlight.Painter p, int x, int y, int w, int h, JsonObject sj) {
		JsonObject names = HudFormatter.obj(sj, "names");
		List<Row> rows = new ArrayList<>();
		if (sj.has("correlations")) for (JsonElement e : sj.getAsJsonArray("correlations")) {
			JsonObject c = e.getAsJsonObject();
			String a = str(c, "a"), b = str(c, "b");
			String na = names != null && names.has(a) ? names.get(a).getAsString() : pretty(a);
			String nb = names != null && names.has(b) ? names.get(b).getAsString() : pretty(b);
			boolean lag = d(c, "lag") > 0;
			double hrs = d(sj, "step_h");
			rows.add(new Row(new String[]{na, nb, String.format(Locale.ROOT, "%.2f", d(c, "rho")),
					lag ? nb + " follows ~" + Market.dur(hrs) + " later" : "move together", String.valueOf((int) d(c, "n"))},
					new int[]{0, 0, d(c, "rho") >= 0.8 ? GREEN : 0, lag ? LILAC : MUTED, MUTED}, lag ? "F" : "≈", lag ? LILAC : BLUE,
					() -> { sigView = "suggest"; sigFilter = "all"; sigSelId = a; page = 0; }));
		}
		table(p, x, y, w, h, new Col[]{new Col("Product", 0.28, false), new Col("With", 0.28, false), new Col("ρ", 0.08, true),
				new Col("How", 0.26, false), new Col("Bars", 0.10, true)}, rows,
				"ρ = correlation of price changes (1 = always together). Correlation ≠ cause: use it as a lead.");
	}

	// ---------------------------------------------------------------- bazaar (stocks, candy, …)
	static List<String> bazaarList() {
		List<String> l = cfg.bazaarWatch == null ? new ArrayList<>() : new ArrayList<>(cfg.bazaarWatch);
		if (l.isEmpty()) l.addAll(List.of(DEFAULT_BAZAAR));
		return l;
	}

	private static void bazaar(Highlight.Painter p, int x, int y, int w, int h) {
		List<String> keys = bazaarList();
		if (bzSel == null || !keys.contains(bzSel)) bzSel = keys.get(0);
		int tableH = Math.min((int) (h * 0.62), 26 + keys.size() * 11);
		List<Row> rows = new ArrayList<>();
		for (String k : keys) {
			DataClient.Live live = data.live(k);
			DataClient.Doc doc = data.doc(k);
			JsonObject st = doc == null || doc.json() == null ? null : HudFormatter.obj(doc.json(), "stats", "24h", "sell");
			double buy = live == null ? Double.NaN : live.buy(), sell = live == null ? Double.NaN : live.sell();
			Double avg = HudFormatter.num(st, "avg"), hi = HudFormatter.num(st, "hi"), lo = HudFormatter.num(st, "lo");
			String spread = live == null || buy <= 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", (buy - sell) / buy * 100);
			boolean selRow = k.equals(bzSel);
			int chg = 0;
			if (avg != null && !Double.isNaN(sell)) chg = sell >= avg ? GREEN : RED;
			rows.add(new Row(new String[]{pretty(k), Double.isNaN(sell) ? "…" : fmt(sell), Double.isNaN(buy) ? "…" : fmt(buy),
					avg == null ? "-" : fmt(avg), hi == null ? "-" : fmt(hi), lo == null ? "-" : fmt(lo), spread},
					new int[]{selRow ? ORANGE : 0, chg, 0, 0, GREEN, RED, MUTED}, null, 0, () -> bzSel = k));
		}
		table(p, x, y, w, tableH, new Col[]{new Col("Product", 0.30, false), new Col("Sell", 0.12, true), new Col("Buy", 0.12, true),
				new Col("Avg", 0.12, true), new Col("High", 0.12, true), new Col("Low", 0.12, true), new Col("Gap", 0.10, true)},
				rows, "Sell / Buy = instant prices now · Avg, High, Low = last 24 h · Gap = Buy minus Sell");
		bazaarChart(p, x, y + tableH + 4, w, h - tableH - 4, bzSel);
		final String sel = bzSel, selName = pretty(bzSel);                   // quick link: flip it in the Bazaar
		String b1 = "Flip " + selName + " in Bazaar";
		int bx = x + w - w(b1) - 10;
		button(p, bx, y + tableH + 2, bx + w(b1) + 10, y + tableH + 15, b1, true, () -> { on = false; FlipPanel.pick(sel, selName); });
	}

	static String pretty(String k) {
		StringBuilder sb = new StringBuilder();
		for (String part : k.toLowerCase(Locale.ROOT).split("_")) {
			if (part.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return sb.toString();
	}

	/** 7-day hourly sell + buy price of one bazaar product (published history), plus the live price. */
	private static void bazaarChart(Highlight.Painter p, int x, int y, int w, int h, String key) {
		if (h < 40) return;
		DataClient.Doc doc = data.doc(key);
		p.text(pretty(key) + ": price, last 7 days", x, y, INK);
		p.fill(x + 150, y + 3, x + 166, y + 6, 0xFF2EA44F);
		p.text("Sell", x + 170, y, INK);
		p.fill(x + 196, y + 3, x + 212, y + 6, 0xFF1E74D6);
		p.text("Buy", x + 216, y, INK);
		List<long[]> sell = new ArrayList<>(), buy = new ArrayList<>();
		JsonObject j = doc == null ? null : doc.json();
		Double start = HudFormatter.num(j, "start"), step = HudFormatter.num(j, "step");
		JsonObject series = HudFormatter.obj(j, "series");
		if (start != null && step != null && series != null) {      // hourly history from the data site
			sell = pts(series, "sell", start, step);
			buy = pts(series, "buy", start, step);
		}
		JsonObject hist = HudFormatter.obj(data.bazaarHistory(), "items");
		if (hist != null && hist.has(key) && hist.get(key).isJsonArray()) {   // 15-min points from the GitHub job
			for (JsonElement e : hist.getAsJsonArray(key)) {
				JsonArray r = e.getAsJsonArray();
				long ts = r.get(0).getAsLong();
				buy.add(new long[]{ts, Math.round(r.get(1).getAsDouble())});
				sell.add(new long[]{ts, Math.round(r.get(2).getAsDouble())});
			}
			buy.sort((u, v) -> Long.compare(u[0], v[0]));
			sell.sort((u, v) -> Long.compare(u[0], v[0]));
		}
		DataClient.Live live = data.live(key);
		long now = System.currentTimeMillis();
		if (live != null) {
			sell.add(new long[]{now, Math.round(live.sell())});
			buy.add(new long[]{now, Math.round(live.buy())});
		}
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (List<long[]> l : List.of(sell, buy)) for (long[] q : l) { lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]); }
		if (lo == Long.MAX_VALUE) { p.text("No prices yet.", x, y + 14, MUTED); return; }
		long pad = Math.max(1, (hi - lo) / 10);
		lo = Math.max(0, lo - pad);
		hi += pad;
		long from = now - 7 * 86_400_000L;
		int gx = x + 36, gy = y + 14, gw = w - 40, gh = h - 28;
		for (int k = 0; k <= 3; k++) {
			long v = lo + (hi - lo) * k / 3;
			int yy = gy + gh - (int) ((double) (v - lo) / (hi - lo) * gh);
			p.fill(gx, yy, gx + gw, yy + 1, LINE);
			String t = fmt((double) v);
			p.text(t, gx - 3 - w(t), yy - 4, MUTED);
		}
		p.text("7 days ago", gx, gy + gh + 3, MUTED);
		p.text("now", gx + gw - w("now"), gy + gh + 3, MUTED);
		FcfPanel.line(p, buy, from, now, lo, hi, gx, gy, gw, gh, 0xFF1E74D6);
		FcfPanel.line(p, sell, from, now, lo, hi, gx, gy, gw, gh, 0xFF2EA44F);
	}

	private static List<long[]> pts(JsonObject series, String k, double start, double step) {
		List<long[]> out = new ArrayList<>();
		if (!series.has(k) || !series.get(k).isJsonArray()) return out;
		JsonArray a = series.getAsJsonArray(k);
		for (int i = 0; i < a.size(); i++) {
			JsonElement e = a.get(i);
			if (e == null || e.isJsonNull()) continue;
			out.add(new long[]{(long) (start + i * step), Math.round(e.getAsDouble())});
		}
		return out;
	}
}

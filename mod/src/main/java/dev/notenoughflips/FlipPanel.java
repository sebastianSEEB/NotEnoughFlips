package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ChestMenu;

/**
 * "FLIP" tab on the right of your inventory and every chest menu (Auction House, Bazaar, …). One click shows the
 * flips that fit your purse right now:
 *
 *   AH      on this AH page (numbered, same as the numbers on the slots) + the best BIN flips from the mod's AH scan
 *   Bazaar  your orders (outbid / undercut?) + best buy-order → sell-offer flips, coins per hour
 *   Craft   best craft flips (published every 3 h), with the shopping list
 *   Profit  today / 7 days realized profit from your logged buys and sells
 *
 * First time: a 3-tap setup (budget per flip, risk, max time to sell). Footer: Simple / Pro tooltips, setup, refresh.
 * Clicking only opens an auction (/viewauction) or copies a price to your clipboard: you buy and list yourself.
 */
final class FlipPanel {
	private static final int BTN_W = 30, BTN_H = 14, W = 168, R2 = 21, PAD = 6;   // PAD: inner padding of the panel
	// inventory look (McStyle): grey container, dark text, stone buttons
	private static final int TEXT = McStyle.TEXT, MUTED = McStyle.MUTED, DIM = McStyle.DIM,
			GOOD = McStyle.GOOD, WARN = McStyle.WARN, BAD = McStyle.BAD, GOLD = McStyle.GOLD;
	private static final String[] TABS = {"AH", "Bazaar", "Craft", "Snipe", "Profit"};

	private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

	private static NefConfig cfg;
	private static DataClient data;
	private static boolean open;
	private static int tab;
	private static final List<Hit> hits = new ArrayList<>();
	private static FlipFeed.View view;
	private static long viewAt;

	private FlipPanel() {}

	static void init(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	static boolean isOpen() {
		return open;
	}

	private static boolean active(Screen s) {
		return cfg != null && cfg.flipButton && (s instanceof InventoryScreen
				|| s instanceof AbstractContainerScreen<?> acs && acs.getMenu() instanceof ChestMenu);
	}

	static double budget() {
		return cfg.budget(FcfPanel.purse());
	}

	// ------------------------------------------------------------------ drawing helpers
	private static int width(String t) {
		return Minecraft.getInstance().font.width(t);
	}

	/** A secondary choice (setting, filter, sort): plain text with an underline when selected (McStyle.option). */
	private static void chip(Highlight.Painter p, int x, int y, String t, boolean on, Runnable action) {
		int w = McStyle.option(p, x, y, t, on);
		hits.add(new Hit(x - 2, y, x + w - 2, y + 13, action));
	}

	private static int chipW(String t) {
		return McStyle.optionW(t) - 2;
	}

	/** Small grey label in front of a row of options ("Sort", "HUD"…); returns the x where the options start. */
	private static int optLabel(Highlight.Painter p, int x, int y, String t) {
		p.text(t, x + PAD, y + 2, DIM);
		return x + PAD + Math.max(width(t), width("Sort")) + 6;
	}

	/** Two-line row: name + right-aligned value, then a gray detail line. */
	private static void row(Highlight.Painter p, int x, int y, int w, String name, String right, int rightColor, String detail,
			Runnable action) {
		int rw = width(right);
		p.text(FcfPanel.fit(name, w - rw - 2 * PAD - 4), x + PAD, y, TEXT);
		p.text(right, x + w - rw - PAD, y, rightColor);
		p.text(FcfPanel.fit(detail, w - 2 * PAD), x + PAD, y + 9, MUTED);
		if (action != null) hits.add(new Hit(x, y - 1, x + w, y + R2 - 1, action));
	}

	private static void section(Highlight.Painter p, int x, int y, int w, String t, String right) {
		p.text(t, x + PAD, y, GOLD);
		if (right == null || right.isEmpty()) return;
		String r = FcfPanel.fit(right, Math.max(10, w - width(t) - 14));   // never runs into the title
		p.text(r, x + w - width(r) - PAD, y, DIM);
	}

	// ------------------------------------------------------------------ paint
	static void paint(Screen s, Highlight.Painter p) {
		if (!active(s)) return;
		hits.clear();
		int[] box = GuiPos.box(s);
		int bx = Math.min(box[0] + box[2] + 3, s.width - BTN_W - 2), by = box[1];
		McStyle.toggle(p, bx, by, bx + BTN_W, by + BTN_H, "FLIP", open);
		if (!open) return;

		int w = Math.max(110, Math.min(W, s.width - bx - 2));
		int x = Math.min(bx, s.width - w - 2), y0 = by + BTN_H + 2, maxY = s.height - 4;
		List<Runnable> deferred = new ArrayList<>();
		Highlight.Painter q = new Highlight.Painter() {     // draw after the background (whose height we only know at the end)
			@Override
			public void fill(int x1, int y1, int x2, int y2, int argb) {
				deferred.add(() -> p.fill(x1, y1, x2, y2, argb));
			}

			@Override
			public void text(String t, int tx, int ty, int argb) {
				deferred.add(() -> p.text(t, tx, ty, argb));
			}
		};
		int y = y0 + 4;
		Double purse = FcfPanel.purse();
		section(q, x, y, w, "Flips", (purse != null ? "purse " + HudFormatter.fmt(purse) + " · " : "") + "≤" + HudFormatter.fmt(budget()) + "/flip");
		y += 12;
		if (!cfg.setupDone) {
			y = wizard(q, x, y, w);
		} else {
			// the main tabs share the panel width exactly (no tab ever runs off the edge)
			int avail = w - 2 * PAD - (TABS.length - 1), textW = 0;
			for (String t : TABS) textW += width(t);
			int extra = Math.max(4, (avail - textW) / TABS.length), tx = x + PAD;
			for (int i = 0; i < TABS.length; i++) {
				int t = i, tw = width(TABS[i]) + extra;
				if (i == TABS.length - 1) tw = Math.max(tw, x + w - PAD - tx);
				McStyle.button(q, tx, y, tx + tw, y + 13, TABS[i], tab == i);
				hits.add(new Hit(tx, y, tx + tw, y + 13, () -> tab = t));
				tx += tw + 1;
			}
			y += 19;
			int bottom = maxY - 18;
			y = switch (tab) {
				case 0 -> ah(q, s, x, y, w, bottom);
				case 1 -> bazaar(q, x, y, w, bottom);
				case 2 -> craft(q, x, y, w, bottom);
				case 3 -> snipe(q, x, y, w, bottom);
				default -> profit(q, x, y, w, bottom);
			};
			y = footer(q, x, y + 2, w);
		}
		int h = Math.min(y + 3, maxY) - y0;
		McStyle.panel(p, x, y0, x + w, y0 + h);
		for (Runnable r : deferred) r.run();
		hits.add(new Hit(x, y0, x + w, y0 + h, () -> {}));          // clicks inside the panel never reach the menu
	}

	private static int wizard(Highlight.Painter p, int x, int y, int w) {
		p.text("Quick setup (3 taps)", x + PAD, y, TEXT);
		y += 12;
		p.text("Use per flip, at most:", x + PAD, y, MUTED);
		y += 10;
		int cx = x + PAD;
		for (int pct : new int[]{10, 25, 50}) {
			String t = pct + "% of purse";
			if (cx + chipW(t) > x + w - 2) { cx = x + PAD; y += 14; }
			chip(p, cx, y, t, Math.round(cfg.budgetPercent) == pct, () -> { cfg.budgetPercent = pct; cfg.save(); });
			cx += chipW(t) + 2;
		}
		y += 16;
		p.text("Risk:", x + PAD, y, MUTED);
		y += 10;
		cx = x + PAD;
		for (String r : new String[]{"safe", "normal", "bold"}) {
			String t = Character.toUpperCase(r.charAt(0)) + r.substring(1);
			chip(p, cx, y, t, r.equals(cfg.risk), () -> { cfg.applyRisk(r); cfg.save(); });
			cx += chipW(t) + 2;
		}
		y += 14;
		p.text(FcfPanel.fit(String.format(Locale.ROOT, "≥%.0f%% profit · sells ≥%.0f/day", cfg.minFlipProfitPercent, cfg.minSalesPerDay),
				w - 2 * PAD), x + PAD, y, DIM);
		y += 12;
		p.text("Longest wait to resell:", x + PAD, y, MUTED);
		y += 10;
		cx = x + PAD;
		for (int hrs : new int[]{2, 8, 24}) {
			String t = hrs + "h";
			chip(p, cx, y, t, Math.round(cfg.maxHoldHours) == hrs, () -> { cfg.maxHoldHours = hrs; cfg.save(); });
			cx += chipW(t) + 2;
		}
		y += 18;
		chip(p, x + PAD, y, "Done ✔", true, () -> {
			cfg.setupDone = true;
			cfg.save();
			AhScanner.poke(cfg, false);
		});
		return y + 14;
	}

	private static int footer(Highlight.Painter p, int x, int y, int w) {
		p.fill(x + PAD, y, x + w - 3, y + 1, McStyle.LINE);
		y += 3;
		int cx = optLabel(p, x, y, "View");
		chip(p, cx, y, "Simple", cfg.simpleMode, () -> { cfg.simpleMode = true; cfg.save(); });
		cx += chipW("Simple") + 1;
		chip(p, cx, y, "Pro", !cfg.simpleMode, () -> { cfg.simpleMode = false; cfg.save(); });
		cx += chipW("Pro") + 4;
		chip(p, cx, y, "⚙", false, () -> { cfg.setupDone = false; cfg.save(); });
		cx += chipW("⚙") + 2;
		chip(p, cx, y, "⟳", false, () -> { viewAt = 0; AhScanner.poke(cfg, true); });
		cx += chipW("⟳") + 2;
		chip(p, cx, y, "FC", cfg.forecastFlips, () -> {                // price forecasts in the flip lists on/off
			cfg.forecastFlips = !cfg.forecastFlips;
			cfg.save();
			viewAt = 0;
			Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "Forecasts in flips " + (cfg.forecastFlips ? "ON" : "OFF"),
					HudFormatter.WHITE, cfg.forecastFlips ? " (flips count the expected price at the time you sell)" : " (live prices only)",
					HudFormatter.GRAY));
		});
		return y + 13;
	}

	// ------------------------------------------------------------------ tabs
	private static int ah(Highlight.Painter p, Screen s, int x, int y, int w, int bottom) {
		List<Highlight.PageFlip> page = Highlight.page();
		if (!page.isEmpty()) {
			section(p, x, y, w, "On this page", page.size() + " found");
			y += 11;
			for (int i = 0; i < Math.min(3, page.size()) && y + R2 <= bottom; i++) {
				Highlight.PageFlip f = page.get(i);
				String detail = (f.bid() ? "bid " : "buy ") + HudFormatter.fmt(f.price())
						+ (Double.isNaN(f.eta()) ? "" : " · resells " + Market.eta(f.eta()));
				row(p, x, y, w, f.rank() + " " + f.name(), "+" + HudFormatter.fmt(f.profit()), GOOD, detail, null);
				y += R2;
			}
			y += 2;
		}
		long now = System.currentTimeMillis();
		if (view == null || now - viewAt > 2000) {
			view = FlipFeed.view(budget());
			viewAt = now;
		}
		long at = FlipFeed.scanAt();
		String when = AhScanner.running() ? AhScanner.status().replace("scanning the AH… ", "scan ")
				: at > 0 ? "scan " + Math.max(0, (now - at) / 60000) + "m ago" : "";
		section(p, x, y, w, "Market flips", when);
		y += 11;
		// your floor: items worth less than this are ignored everywhere (flips, deals, snipes, tooltip banners)
		y = limitRow(p, x, y, "min value", new double[]{0, 500_000, 1_000_000, 5_000_000, 10_000_000}, cfg.minItemValue,
				v -> v == 0 ? "any" : HudFormatter.fmt(v), v -> { cfg.minItemValue = v; cfg.save(); viewAt = 0; });
		if (!cfg.flipFeed) {
			p.text(FcfPanel.fit("Feed is off: /nef feed on", w - 2 * PAD), x + PAD, y, MUTED);
			return y + 10;
		}
		if (at == 0) {
			p.text(FcfPanel.fit(AhScanner.running() ? "First AH scan running…" : "Starting the first AH scan…", w - 2 * PAD), x + PAD, y, MUTED);
			AhScanner.poke(cfg, false);
			return y + 10;
		}
		List<FlipFeed.Flip> fl = view.flips();
		if (fl.isEmpty()) {
			p.text(FcfPanel.fit("Nothing fits your budget + risk now", w - 2 * PAD), x + PAD, y, MUTED);
			y += 10;
			if (view.checking() > 0) {
				p.text(FcfPanel.fit("checking prices of " + view.checking() + " more…", w - 2 * PAD), x + PAD, y, DIM);
				y += 10;
			}
			return y;
		}
		for (int i = 0; i < fl.size() && y + R2 <= bottom; i++) {
			FlipFeed.Flip f = fl.get(i);
			String detail = HudFormatter.fmt(f.c().price()) + " → " + HudFormatter.fmt(f.resale()) + " · "
					+ Market.eta(f.liq().etaHours()) + " · " + f.liq().confidence() + Forecasts.tag(f.forecast());
			String uuid = f.c().uuid();
			row(p, x, y, w, (i + 1) + ". " + f.c().name(), "+" + HudFormatter.fmt(f.profit()), GOOD, detail,
					() -> { Holdings.intend(f.c().name(), f.c().key(), "AH flip", f.resale()); Cmd.viewAuction(uuid); });
			y += R2;
		}
		p.text(FcfPanel.fit("click = open it, check the tooltip", w - 2 * PAD), x + PAD, y, DIM);
		return y + 10;
	}

	private static int bazaar(Highlight.Painter p, int x, int y, int w, int bottom) {
		List<BzOrders.Status> orders = BzOrders.status();
		if (!orders.isEmpty()) {
			section(p, x, y, w, "Your orders", orders.size() + "");
			y += 11;
			for (int i = 0; i < Math.min(4, orders.size()) && y + R2 <= bottom; i++) {
				BzOrders.Status st = orders.get(i);
				BzOrders.Order o = st.order();
				String right = switch (st.state()) {
					case "top" -> "✔ top";
					case "outbid" -> "⚠ outbid";
					case "undercut" -> "⚠ undercut";
					case "filled" -> "✔ filled";
					default -> "?";
				};
				int col = st.state().startsWith("top") || "filled".equals(st.state()) ? GOOD : "unknown".equals(st.state()) ? MUTED : WARN;
				String detail = HudFormatter.fmt(o.price()) + (st.top() != null ? " vs " + HudFormatter.fmt(st.top()) : "")
						+ (o.amount() > 0 ? " · " + o.filled() + "/" + o.amount() + " filled" : "");
				final String pname = o.name();
				row(p, x, y, w, o.side() + " " + o.name(), right, col, detail, () -> Cmd.bazaar(pname));   // click: open it in /bz
				y += R2;
			}
			y += 2;
		} else {
			p.text(FcfPanel.fit("Open your Bazaar orders to track", w - 2 * PAD), x + PAD, y, DIM);
			y += 11;
		}
		// the orders HUD (top right, also without the inventory): on/off, width, how many per side
		int hx = optLabel(p, x, y, "HUD");
		chip(p, hx, y, cfg.bzHud ? "on" : "off", cfg.bzHud, () -> { cfg.bzHud = !cfg.bzHud; cfg.save(); });
		hx += chipW(cfg.bzHud ? "on" : "off") + 6;
		for (String[] sz : new String[][]{{"small", "S"}, {"medium", "M"}, {"large", "L"}}) {
			final String id = sz[0];
			chip(p, hx, y, sz[1], id.equals(cfg.bzHudSize), () -> { cfg.bzHudSize = id; cfg.bzHud = true; cfg.save(); });
			hx += chipW(sz[1]) + 2;
		}
		for (int n : new int[]{3, 5, 8, 12}) {
			final int nn = n;
			chip(p, hx, y, String.valueOf(n), cfg.bzHudMax == n, () -> { cfg.bzHudMax = nn; cfg.bzHud = true; cfg.save(); });
			hx += chipW(String.valueOf(n)) + 2;
		}
		y += 15;
		// sort: profit/h (default), most profit per round, margin %, or busiest products (coins traded per hour)
		int sx = optLabel(p, x, y, "Sort");
		for (String[] so : Bazaar.SORTS) {
			final String id = so[0];
			chip(p, sx, y, so[1], id.equals(cfg.bazaarSort), () -> { cfg.bazaarSort = id; cfg.save(); });
			sx += chipW(so[1]) + 2;
		}
		y += 15;
		List<Market.BzFlip> list = Bazaar.sorted(cfg, data, budget(), cfg.bazaarSort);
		double[] today = ProfitLog.marketTotal(ProfitLog.startOfDay(0), "BZ");
		long age = Bazaar.snapshotAt() > 0 ? (System.currentTimeMillis() - Bazaar.snapshotAt()) / 60000 : -1;
		long secs = Bazaar.snapshotAt() > 0 ? (System.currentTimeMillis() - Bazaar.snapshotAt()) / 1000 : -1;
		String right = (secs >= 0 ? (cfg.bazaarRefreshMinutes <= 0 ? "live " + secs + "s" : "upd " + age + "m") : "") + (today[1] > 0 ? " · today " + (today[0] >= 0 ? "+" : "") + HudFormatter.fmt(today[0]) : "");
		section(p, x, y, w, "Best flips", right.trim());
		y += 11;
		if (list.isEmpty()) {
			p.text(FcfPanel.fit(data.bazaarAll().isEmpty() ? "Loading the bazaar…" : "Nothing fits your budget", w - 2 * PAD), x + PAD, y, MUTED);
			return y + 10;
		}
		for (int i = 0; i < list.size() && y + R2 <= bottom; i++) {
			Market.BzFlip f = list.get(i);
			String name = Bazaar.name(f.id(), data);
			String detail = String.format(Locale.ROOT, "%s× %s→%s · +%s · %s", HudFormatter.fmt((double) f.qty()),
					HudFormatter.fmt(f.order()), HudFormatter.fmt(f.offer()), HudFormatter.fmt(f.totalProfit()), Market.dur(f.roundHours()))
					+ Forecasts.tag(f.forecast(), f.adjSource());
			String pc = String.format(Locale.ROOT, "%.1f%%", f.pct() * 100);
			String rt = switch (cfg.bazaarSort == null ? "hour" : cfg.bazaarSort) {
				case "profit" -> "+" + HudFormatter.fmt(f.totalProfit()) + " " + pc;
				case "pct" -> pc + " +" + HudFormatter.fmt(f.totalProfit());
				case "volume" -> HudFormatter.fmt(Bazaar.coinVolume(f)) + "/h " + pc;
				default -> "+" + HudFormatter.fmt(f.profitPerHour()) + "/h";
			};
			row(p, x, y, w, name, rt, GOOD, detail, () -> copyBazaar(f, name));
			y += R2;
		}
		p.text(FcfPanel.fit("click ×3: open/name · amount · price", w - 2 * PAD), x + PAD, y, DIM);
		return y + 10;
	}

	private static String pendingId, pendingName;
	private static int stage;
	private static long stageAt, bzSentAt;

	/** The product you're flipping right now (clicked in the panel in the last 3 min), or null. Used by the Bazaar guide. */
	static String pendingId() {
		return pendingId != null && System.currentTimeMillis() - stageAt < 180_000 ? pendingId : null;
	}

	static String pendingName() {
		return pendingId() != null ? pendingName : null;
	}

	/** Hypixel answered our /bz with something about the cookie: you have no Booster Cookie, use Search instead. */
	static void onChat(String plain) {
		if (System.currentTimeMillis() - bzSentAt > 4000) return;
		if (plain.toLowerCase(Locale.ROOT).contains("cookie")) {
			bzSentAt = 0;
			Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "No Booster Cookie, so /bz can't open it for you. ", HudFormatter.WHITE,
					"At the Bazaar: click Search (green box), paste (Ctrl+V), Done, then the item (green box).", HudFormatter.GRAY));
		}
	}

	/**
	 * Click 1: open the product (/bz <name>: with a Booster Cookie it opens the search directly) and copy its name, so
	 *          without a cookie you click Search at the Bazaar and paste. The Bazaar guide outlines Search → the item →
	 *          Create Buy Order.
	 * Click 2: copy the amount (Custom amount).   Click 3: copy the price (Custom price).
	 * Amount and price are re-computed from the live bazaar at click time.
	 */
	private static void copyBazaar(Market.BzFlip snap, String name) {
		copyBazaar(snap, name, "Bazaar flip", 0);
	}

	/** source / target: why you're buying it and the unit price you expect (0 = the flip's sell offer). */
	private static void copyBazaar(Market.BzFlip snap, String name, String source, double target) {
		long now = System.currentTimeMillis();
		DataClient.Live live = data.live(snap.id());
		Market.BzFlip f = live != null ? Bazaar.flip(cfg, snap.id(), live, budget()) : snap;
		if (f == null || f.qty() < 1) f = snap;
		boolean same = f.id().equals(pendingId) && now - stageAt < 180_000;
		stage = same ? stage % 3 + 1 : 1;
		pendingId = f.id();
		pendingName = name;
		stageAt = now;
		var kb = Minecraft.getInstance().keyboardHandler;
		if (stage == 1) Holdings.intend(name, f.id(), source, target > 0 ? target : f.offer());   // remember why you bought it
		switch (stage) {
			case 1 -> {
				kb.setClipboard(name);
				bzSentAt = now;
				Cmd.bazaar(name);                                // you clicked it: same as typing /bz <name>
				Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, name + " picked. ", HudFormatter.WHITE,
						"In the Bazaar click Search (green box): the name is filled in, press Done → the item (cyan box) → Create Buy Order. "
								+ "Amount and price signs fill themselves too.", HudFormatter.GRAY));
			}
			case 2 -> {
				kb.setClipboard(Long.toString(f.qty()));
				Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "2/3 copied amount " + String.format(Locale.ROOT, "%,d", f.qty()) + ". ",
						HudFormatter.WHITE, "Create Buy Order → Custom amount → Ctrl+V. Click the row again for the price.", HudFormatter.GRAY));
			}
			default -> {
				String price = String.format(Locale.ROOT, "%.1f", f.order());
				kb.setClipboard(price);
				Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "3/3 copied price " + price + ". ", HudFormatter.WHITE,
						"Custom price → Ctrl+V → confirm. When it fills: sell offer at " + HudFormatter.fmt(f.offer()) + " → +"
								+ HudFormatter.fmt(f.totalProfit()) + " total, ~" + Market.dur(f.roundHours()) + ".", HudFormatter.GRAY));
			}
		}
	}

	/** Start the Bazaar click flow (open / copy name → amount → price) for any product, e.g. from Trade Desk → Market → Tips. */
	static void pick(String id, String name) {
		pick(id, name, "Bazaar flip", 0);
	}

	/** pick() for a reason with a target price (bazaar mispricing: the price it should go back to). */
	static void pick(String id, String name, String source, double target) {
		DataClient.Live live = data.live(id);
		Market.BzFlip f = live == null ? null : Bazaar.flip(cfg, id, live, budget());
		if (f == null) f = new Market.BzFlip(id, live == null ? 0 : live.sell() + 0.1, live == null ? 0 : live.buy() - 0.1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
		copyBazaar(f, name, source, target);
	}

	private static int craft(Highlight.Painter p, int x, int y, int w, int bottom) {
		JsonObject cj = data.craftFlips();
		section(p, x, y, w, "Craft flips", cj == null ? "" : "updated " + Math.max(0, (System.currentTimeMillis()
				- (long) FcfPanel.d(cj, "updated")) / 3_600_000L) + "h ago");
		y += 11;
		if (cj == null || !cj.has("flips")) {
			p.text("Loading…", x + PAD, y, MUTED);
			return y + 10;
		}
		double budget = budget();
		JsonArray arr = cj.getAsJsonArray("flips");
		List<JsonObject> fit = new ArrayList<>(), over = new ArrayList<>();
		for (JsonElement e : arr) (FcfPanel.d(e.getAsJsonObject(), "cost") <= budget ? fit : over).add(e.getAsJsonObject());
		boolean overBudget = fit.isEmpty();
		if (overBudget) {                                          // nothing fits: show the cheapest ones anyway
			over.sort((a, b) -> Double.compare(FcfPanel.d(a, "cost"), FcfPanel.d(b, "cost")));
			fit = over.subList(0, Math.min(6, over.size()));
			p.text(FcfPanel.fit("Over your " + HudFormatter.fmt(budget) + "/flip budget:", w - 2 * PAD), x + PAD, y, WARN);
			y += 10;
		}
		for (JsonObject r : fit) {
			if (y + R2 > bottom) break;
			String item = FcfPanel.str(r, "item");
			String name = FcfPanel.str(r, "name");
			String where = FcfPanel.str(r, "where");
			String detail = HudFormatter.fmt(FcfPanel.d(r, "cost")) + " → " + HudFormatter.fmt(FcfPanel.d(r, "sell"))
					+ " " + where + " · +" + HudFormatter.fmt(FcfPanel.d(r, "profit")) + " ea";
			boolean sel = item != null && item.equals(craftSel);
			row(p, x, y, w, (sel ? "▾ " : "▸ ") + (name == null ? item : name), "+" + HudFormatter.fmt(FcfPanel.d(r, "profit_per_day")) + "/d",
					GOOD, detail, () -> {
						craftSel = item != null && item.equals(craftSel) ? null : item;
						if (craftSel != null) craftChat(r);
					});
			y += R2;
			if (sel && r.has("ingredients")) {                     // shopping list: click an ingredient to go and buy it
				for (JsonElement ge : r.getAsJsonArray("ingredients")) {
					if (y + 10 > bottom) break;
					JsonObject g = ge.getAsJsonObject();
					String gid = FcfPanel.str(g, "id"), how = FcfPanel.str(g, "how"), gname = Desk.pretty(gid);
					boolean link = how == null || how.startsWith("bazaar") || how.startsWith("lowest BIN");
					String t = (long) FcfPanel.d(g, "n") + "× " + gname;
					String r2 = HudFormatter.fmt(FcfPanel.d(g, "unit")) + (how == null ? "" : how.startsWith("bazaar") ? " BZ" : how.startsWith("lowest") ? " AH" : " " + how);
					p.text(FcfPanel.fit((link ? "→ " : "   ") + t, w - width(r2) - 16), x + 8, y, link ? McStyle.BLUE : TEXT);
					p.text(r2, x + w - width(r2) - PAD, y, MUTED);
					if (link) hits.add(new Hit(x, y - 1, x + w, y + 9, () -> Cmd.buyIngredient(how, gname)));
					y += 10;
				}
				if (y + 10 <= bottom) {
					String sellName = name == null ? Desk.pretty(item) : name;
					p.text("→ sell: open " + ("AH".equals(where) ? "the AH" : "the Bazaar"), x + 8, y, McStyle.BLUE);
					hits.add(new Hit(x, y - 1, x + w, y + 9, () -> {
						if ("AH".equals(where)) Cmd.ahSearch(sellName); else Cmd.bazaar(sellName);
					}));
					y += 11;
				}
			}
		}
		p.text(FcfPanel.fit("click a craft = what to buy (click to open)", w - 2 * PAD), x + PAD, y, DIM);
		return y + 10;
	}

	private static String craftSel;

	private static void craftChat(JsonObject r) {
		String name = FcfPanel.str(r, "name");
		Chat.say(HudFormatter.line("[NEF] Craft ", HudFormatter.GOLD, name == null ? FcfPanel.str(r, "item") : name, HudFormatter.WHITE,
				": cost " + HudFormatter.fmt(FcfPanel.d(r, "cost")) + " → sell " + HudFormatter.fmt(FcfPanel.d(r, "sell")) + " on the "
						+ FcfPanel.str(r, "where") + " → +" + HudFormatter.fmt(FcfPanel.d(r, "profit")) + " each", HudFormatter.GRAY));
		if (r.has("ingredients")) {
			for (JsonElement e : r.getAsJsonArray("ingredients")) {
				JsonObject g = e.getAsJsonObject();
				String gname = Desk.pretty(FcfPanel.str(g, "id")), how = FcfPanel.str(g, "how");
				var line = HudFormatter.line("   ☐ " + (long) FcfPanel.d(g, "n") + "x " + gname, HudFormatter.WHITE,
						"  " + HudFormatter.fmt(FcfPanel.d(g, "unit")) + " each (" + how + ")  ", HudFormatter.DARK);
				String safe = gname.replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
				if (how == null || how.startsWith("bazaar")) line.append(HudFormatter.link("[open in Bazaar]", HudFormatter.AQUA, "/bz " + safe, "/bz " + safe));
				else if (how.startsWith("lowest BIN")) line.append(HudFormatter.link("[search AH]", HudFormatter.AQUA, "/ahs " + safe, "/ahs " + safe));
				Chat.say(line);
			}
		}
		String req = FcfPanel.str(r, "requires");
		if (req != null) Chat.say(HudFormatter.line("   Requires: " + req, HudFormatter.YELLOW));
	}

	private static int watchPage;

	/**
	 * Snipe tab: the latest alerts (click = open the auction) and every item the sniper is watching with the price that
	 * would trigger it, plus quick settings. Rules are in Sniper's class doc.
	 */
	private static int snipe(Highlight.Painter p, int x, int y, int w, int bottom) {
		section(p, x, y, w, "Snipes", cfg.sniper ? "≤ " + Math.round(100 - cfg.snipeMinPercent) + "% of lowest BIN" : "off");
		y += 12;
		int cx = x + PAD;
		for (int pct : new int[]{25, 35, 50}) {
			String t = pct + "% off";
			chip(p, cx, y, t, Math.round(cfg.snipeMinPercent) == pct, () -> { cfg.snipeMinPercent = pct; cfg.save(); });
			cx += chipW(t) + 2;
		}
		chip(p, cx, y, cfg.sniper ? "on" : "off", cfg.sniper, () -> { cfg.sniper = !cfg.sniper; cfg.save(); });
		y += 14;
		// auto-open: off / at once / after the ✓ check, then its limits
		p.text("Open by itself:", x + PAD, y + 2, MUTED);
		y += 11;
		cx = x + PAD;
		String[][] modes = {{"off", "off"}, {"now", "at once"}, {"checked", "after ✓"}};
		String mode = !cfg.snipeAutoOpen ? "off" : cfg.snipeOpenChecked ? "checked" : "now";
		for (String[] m : modes) {
			final String id = m[0];
			chip(p, cx, y, m[1], mode.equals(id), () -> {
				cfg.snipeAutoOpen = !id.equals("off");
				cfg.snipeOpenChecked = id.equals("checked");
				cfg.save();
			});
			cx += chipW(m[1]) + 2;
		}
		y += 14;
		if (cfg.snipeAutoOpen) {
			y = limitRow(p, x, y, "if ≥", new double[]{40, 50, 60, 70}, cfg.snipeOpenMinPercent, v -> v.intValue() + "%",
					v -> { cfg.snipeOpenMinPercent = v; cfg.save(); });
			y = limitRow(p, x, y, "profit ≥", new double[]{500_000, 1_000_000, 5_000_000, 10_000_000}, cfg.snipeOpenMinProfit,
					HudFormatter::fmt, v -> { cfg.snipeOpenMinProfit = v; cfg.save(); });
			y = limitRow(p, x, y, "price ≤", new double[]{0, 10_000_000, 50_000_000, 200_000_000}, cfg.snipeOpenMaxPrice,
					v -> v == 0 ? "purse" : HudFormatter.fmt(v), v -> { cfg.snipeOpenMaxPrice = v; cfg.save(); });
			y += 2;
		}
		if (!Sniper.ready()) {
			p.text(FcfPanel.fit("Waiting for the first full AH scan (⟳ below)…", w - 2 * PAD), x + PAD, y, DIM);
			return y + 11;
		}
		List<Sniper.Alert> al = Sniper.recent();
		java.util.Collections.reverse(al);
		long now = System.currentTimeMillis();
		if (al.isEmpty()) {
			p.text(FcfPanel.fit("No alerts yet. Big gaps are rare: try 25% off.", w - 2 * PAD), x + PAD, y, DIM);
			y += 11;
		}
		for (int i = 0; i < al.size() && i < 5 && y + 20 <= bottom; i++) {
			Sniper.Alert a = al.get(i);
			boolean ok = a.status().equals("ok"), bad = a.status().startsWith("bad");
			String mark = ok ? "✓ " : bad ? "✗ " : "… ";
			String right = bad ? "skip" : "+" + HudFormatter.fmt(a.profit());
			long ago = (now - a.at()) / 60000;
			String detail = HudFormatter.fmt(a.price()) + " → " + HudFormatter.fmt(a.resale()) + " · " + (ago < 1 ? "just now" : ago + "m ago")
					+ (bad ? " · " + a.status().substring(5) : "");
			row(p, x, y, w, mark + a.name(), right, bad ? BAD : ok ? GOOD : WARN, detail,
					bad ? null : () -> { Holdings.intend(a.name(), a.key(), "Snipe", a.resale() / Math.max(1, a.count())); Cmd.viewAuction(a.uuid()); });
			y += 21;
		}
		List<Sniper.Watch> wl = Sniper.watchList();
		int per = Math.max(1, (bottom - y - 14) / 10);
		int pages = Math.max(1, (wl.size() + per - 1) / per);
		watchPage = Math.min(watchPage, pages - 1);
		section(p, x, y, w, "Watching " + wl.size(), "alert at ≤");
		y += 11;
		for (int i = watchPage * per; i < Math.min(wl.size(), (watchPage + 1) * per); i++) {
			Sniper.Watch wt = wl.get(i);
			String right = HudFormatter.fmt(wt.trigger());
			String name = Bazaar.name(wt.key(), data);
			p.text(FcfPanel.fit(name + "  " + HudFormatter.fmt(wt.lowest()) + " ×" + wt.listed(), w - width(right) - 12), x + PAD, y, TEXT);
			p.text(right, x + w - width(right) - PAD, y, GOOD);
			y += 10;
		}
		if (pages > 1) {
			chip(p, x + PAD, y + 1, "◀", false, () -> watchPage = Math.max(0, watchPage - 1));
			String pg = (watchPage + 1) + "/" + pages;
			p.text(pg, x + PAD + chipW("◀") + 4, y + 2, MUTED);
			chip(p, x + PAD + chipW("◀") + 8 + width(pg), y + 1, "▶", false, () -> watchPage = Math.min(pages - 1, watchPage + 1));
			y += 13;
		}
		return y;
	}

	/** One row of limit chips: label, then one chip per value (the current one selected). */
	private static int limitRow(Highlight.Painter p, int x, int y, String label, double[] values, double current,
			java.util.function.Function<Double, String> fmt, java.util.function.Consumer<Double> set) {
		p.text(label, x + PAD, y + 2, MUTED);
		int cx = x + PAD + Math.max(width("profit ≥"), width(label)) + 4;
		for (double v : values) {
			String t = fmt.apply(v);
			final double val = v;
			chip(p, cx, y, t, Math.abs(current - v) < 1e-6, () -> set.accept(val));
			cx += chipW(t) + 2;
		}
		return y + 14;
	}

	private static int profit(Highlight.Painter p, int x, int y, int w, int bottom) {
		ProfitLog.Summary today = ProfitLog.summary(ProfitLog.startOfDay(0));
		ProfitLog.Summary week = ProfitLog.summary(ProfitLog.startOfDay(6));
		section(p, x, y, w, "Realized profit", null);
		y += 12;
		y = total(p, x, y, w, "Today", today);
		y = total(p, x, y, w, "7 days", week);
		if (week.best() != null) {
			p.text(FcfPanel.fit("Best: " + week.best().item() + " +" + HudFormatter.fmt(week.best().profit()), w - 2 * PAD), x + PAD, y, MUTED);
			y += 11;
		}
		y += 2;
		// AH flips: what you paid, what you got after fees, profit; then AH buys you haven't sold yet
		int cx = optLabel(p, x, y, "Show");
		for (String[] m : new String[][]{{"AH", "AH flips"}, {"ALL", "All flips"}}) {
			final String id = m[0];
			chip(p, cx, y, m[1], id.equals(profitView), () -> profitView = id);
			cx += chipW(m[1]) + 2;
		}
		y += 15;
		boolean ahOnly = "AH".equals(profitView);
		List<ProfitLog.Flip> list = ProfitLog.flips(ProfitLog.startOfDay(29), ahOnly ? "AH" : null);
		long now = System.currentTimeMillis();
		if (list.isEmpty()) {
			p.text(FcfPanel.fit(ahOnly ? "No AH flips yet: buy on the AH, sell it" : "No flips logged yet: buys + sells", w - 2 * PAD), x + PAD, y, DIM);
			p.text(FcfPanel.fit("again, and it shows up here", w - 2 * PAD), x + PAD, y + 9, DIM);
			y += 20;
		}
		for (int i = 0; i < list.size() && y + R2 <= bottom - (ahOnly ? 22 : 0); i++) {
			ProfitLog.Flip f = list.get(i);
			double pct = f.cost() > 0 ? f.profit() / f.cost() * 100 : 0;
			String right = String.format(Locale.ROOT, "%s%s %+.0f%%", f.profit() >= 0 ? "+" : "", HudFormatter.fmt(f.profit()), pct);
			String held = f.buyTs() > 0 ? " · held " + ProfitLog.ago(f.ts() - f.buyTs()) : "";
			String detail = "paid " + HudFormatter.fmt(f.cost()) + " → got " + HudFormatter.fmt(f.got()) + held;
			final String item = f.item().replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
			final boolean ah = "AH".equals(f.market());
			row(p, x, y, w, f.item(), right, f.profit() >= 0 ? GOOD : BAD, detail, () -> { if (ah) Cmd.ahSearch(item); else Cmd.bazaar(item); });
			y += R2;
		}
		if (ahOnly) {
			List<ProfitLog.Lot> open = new ArrayList<>();
			for (ProfitLog.Lot l : ProfitLog.openLots()) if ("AH".equals(l.market())) open.add(l);
			if (!open.isEmpty() && y + 11 + R2 <= bottom) {
				section(p, x, y, w, "Bought, not sold yet", open.size() + "");
				y += 11;
				for (int i = 0; i < open.size() && y + R2 <= bottom; i++) {
					ProfitLog.Lot l = open.get(i);
					final String item = l.item().replaceAll("[^A-Za-z0-9 '()\\-]", "").trim();
					row(p, x, y, w, l.item(), "paid " + HudFormatter.fmt(l.unitCost() * l.qty()), MUTED,
							ProfitLog.ago(now - l.ts()) + " ago · click to see it on the AH", () -> Cmd.ahSearch(item));
					y += R2;
				}
			}
		}
		return y;
	}

	private static String profitView = "AH";

	private static int total(Highlight.Painter p, int x, int y, int w, String label, ProfitLog.Summary s) {
		String v = (s.profit() >= 0 ? "+" : "") + HudFormatter.fmt(s.profit());
		p.text(label, x + PAD, y, TEXT);
		p.text(v, x + 46, y, s.profit() >= 0 ? GOOD : BAD);
		p.text(s.flips() + " flips", x + w - width(s.flips() + " flips") - PAD, y, MUTED);
		return y + 11;
	}

	// ------------------------------------------------------------------ clicks
	/** Returns true if the click hit the FLIP button or panel (the menu underneath never sees it). */
	static boolean click(Screen s, double mx, double my) {
		if (!active(s)) return false;
		int[] box = GuiPos.box(s);
		int bx = Math.min(box[0] + box[2] + 3, s.width - BTN_W - 2), by = box[1];
		if (mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H) {
			open = !open;
			if (open) {
				viewAt = 0;
				AhScanner.poke(cfg, false);
			}
			return true;
		}
		if (!open) return false;
		for (Hit h : new ArrayList<>(hits)) {
			if (mx >= h.x1() && mx <= h.x2() && my >= h.y1() && my <= h.y2()) {
				h.action().run();
				return true;
			}
		}
		return false;
	}

	/** True when (mx, my) is over the open panel: the click must not reach the slots underneath. */
	static boolean covers(Screen s, double mx, double my) {
		if (!open || !active(s)) return false;
		for (Hit h : hits) if (mx >= h.x1() && mx <= h.x2() && my >= h.y1() && my <= h.y2()) return true;
		int[] box = GuiPos.box(s);
		int bx = Math.min(box[0] + box[2] + 3, s.width - BTN_W - 2), by = box[1];
		return mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H;
	}
}

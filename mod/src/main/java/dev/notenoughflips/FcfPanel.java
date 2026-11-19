package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ChestMenu;

/**
 * "FCF" button above your inventory. Click it to open a panel left of the inventory with your potential
 * free cash flow (market/fcf.py):
 *
 *   wallet + ongoing sales − ongoing bids + expected sale value (+ outbid refunds) = potential FCF
 *
 * Wallet uses your live purse from the sidebar plus the bank from the last report. Positions come from your
 * own local server (/wallet), if you run one. Click a position to open that auction (you act yourself).
 */
final class FcfPanel {
	private static final int DESK_W = 34;
	private static final int W = 168, BTN_W = 36, BTN_H = 14, ROW = 9, STEP = 12, POS_ROW = 19, MAX_POS = 8;
	// inventory look (McStyle): grey container, dark text, stone buttons
	private static final int MUTED = McStyle.MUTED, TEXT = McStyle.TEXT,
			POS = 0xFF3D7BD6, NEG = 0xFFD0453E, TOTAL = 0xFF8B8B8B, GOOD = McStyle.GOOD, GOLD = McStyle.GOLD;

	private static NefConfig cfg;
	private static DataClient data;
	private static boolean open;
	private static volatile JsonObject report;
	private static volatile String source = "";
	private static volatile long fetchedAt, requestedAt;
	private static Double livePurse;
	private static long purseAt;
	private static final List<String> rowUuids = new ArrayList<>();
	private static int posTop, posRowH = POS_ROW;
	private static int tab;                     // 0 flow (waterfall), 1 tied up (pie), 2 trend (line)
	private static boolean week = true;         // trend range: 7 days / 24 hours
	private static final String[] TABS = {"Flow", "Tied up", "Trend", "Bazaar"};
	static final int[] SLICE = {0xFF3987E5, 0xFF1BAF7A, 0xFFB07A00, 0xFFB07CF0, 0xFF8A8A8A};

	private FcfPanel() {}

	static void init(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	/** Your inventory, and any chest-style menu (SkyBlock menu, Auction House, …). */
	private static boolean active(Screen s) {
		return cfg != null && cfg.fcfButton && (s instanceof InventoryScreen
				|| s instanceof AbstractContainerScreen<?> acs && acs.getMenu() instanceof ChestMenu);
	}

	private static int left(Screen s) {
		return GuiPos.box(s)[0];
	}

	private static int top(Screen s) {
		return GuiPos.box(s)[1];
	}

	private static int panelX(Screen s) {
		return Math.max(2, left(s) - W - 6);
	}

	// ------------------------------------------------------------------ data
	static void refresh(boolean force) {
		long now = System.currentTimeMillis();
		if (!force && now - requestedAt < 60_000) return;
		requestedAt = now;
		AhScanner.poke(cfg, force);                     // the mod's own AH scan (if the last one is > 2 min old)
		data.walletReport().whenComplete((r, e) -> {
			if (r != null && r.json() != null) {
				report = r.json();
				source = r.source();
				fetchedAt = System.currentTimeMillis();
			}
		});
	}

	static Double purse() {
		long now = System.currentTimeMillis();
		if (now - purseAt > 5000) {
			purseAt = now;
			try {
				Double p = PurseReporter.readPurse();
				if (p != null) {
					livePurse = p;
					PurseLog.record(p);
				}
			} catch (RuntimeException ignored) {
				// sidebar not ready
			}
		}
		return livePurse;
	}

	static double d(JsonObject o, String k) {
		Double v = HudFormatter.num(o, k);
		return v == null ? 0 : v;
	}

	// ------------------------------------------------------------------ drawing
	static void paint(Screen s, Highlight.Painter p) {
		if (!active(s)) return;
		int bx = left(s), by = Math.max(1, top(s) - BTN_H - 2);
		McStyle.button(p, bx, by, bx + BTN_W, by + BTN_H, "FCF", open);
		if (cfg.deskButton && s instanceof InventoryScreen) {
			int ex = left(s) - DESK_W - 3;
			McStyle.button(p, ex, by, ex + DESK_W, by + BTN_H, "DESK", Desk.isOn());
		}
		if (!open) return;
		refresh(false);
		int tx = bx + BTN_W + 3;
		for (int i = 0; i < TABS.length; i++) {
			int tw = Minecraft.getInstance().font.width(TABS[i]) + 10;
			McStyle.button(p, tx, by, tx + tw, by + BTN_H, TABS[i], i == tab);
			tx += tw + 2;
		}

		int x = panelX(s), y = top(s), w = Math.min(W, Math.max(120, left(s) - 8));
		JsonObject rep = report();
		JsonObject f = HudFormatter.obj(rep, "fcf");
		JsonObject n = HudFormatter.obj(f, "now");
		var font = Minecraft.getInstance().font;
		List<Runnable> draw = new ArrayList<>();
		int[] h = {16};

		// wallet: live purse + bank from the last report, else the report's wallet
		Double purse = purse();
		JsonObject wn = HudFormatter.obj(rep, "now");
		Double bank = wn == null ? null : (HudFormatter.num(wn, "bank") == null ? null : d(wn, "bank") + d(wn, "personal_bank"));
		Double repWallet = n == null ? null : HudFormatter.num(n, "wallet");
		Double wallet = purse != null ? Double.valueOf(purse + (bank == null ? 0 : bank)) : repWallet;
		String walletNote = purse != null ? (bank == null ? "purse (live)" : "purse (live) + bank") : repWallet != null ? "purse + bank" : "unknown";

		if (n == null) {
			McStyle.panel(p, x, y, x + w, y + 64);
			p.text("Potential FCF", x + 5, y + 4, GOLD);
			p.text("Wallet " + HudFormatter.fmt(wallet), x + 5, y + 18, TEXT);
			p.text(AhScanner.running() ? fit(AhScanner.status(), w - 10) : rep == null ? "Loading…" : "No positions yet.", x + 5, y + 32, MUTED);
			p.text(fit("The mod scans the AH every 15 min.", w - 10), x + 5, y + 44, MUTED);
			return;
		}
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		double sales = d(n, "sales"), bids = d(n, "bids"), expected = d(n, "expected"), refunds = d(n, "refunds");
		double w0 = wallet == null ? 0 : wallet;
		double fcf = w0 + sales - (subtract ? bids : 0) + expected + refunds;
		double cons = fcf - sales + d(n, "sales_conservative");
		if (tab == 1) {
			paintTied(s, p, x, y, w, f, n, purse, bank, repWallet);
			return;
		}
		if (tab == 2) {
			paintTrend(s, p, x, y, w, f, fcf, bank);
			return;
		}
		if (tab == 3) {
			paintBazaar(s, p, x, y, w);
			return;
		}
		posRowH = POS_ROW;

		// waterfall rows: label, delta, kind (0 total, 1 add, 2 subtract, 3 shown-only)
		record Step(String label, double v, int kind) {}
		List<Step> steps = new ArrayList<>();
		steps.add(new Step("Wallet", w0, 0));
		steps.add(new Step("+ Ongoing sales", sales, 1));
		steps.add(new Step("− Ongoing bids", -bids, subtract ? 2 : 3));
		steps.add(new Step("+ Expected sale value", expected, 1));
		if (!subtract) steps.add(new Step("+ Outbid refunds", refunds, 1));
		steps.add(new Step("= Potential FCF", fcf, 0));
		double max = Math.max(1, Math.max(fcf, w0 + sales + expected + refunds));

		int barX = x + 5, barW = w - 10;
		int ly = y + 16;
		for (Step st : steps) {
			final int ry = ly;
			draw.add(() -> stepText(p, st.label(), st.v(), st.kind(), barX, barW, ry));
			ly += STEP;
			h[0] += STEP;
		}
		// bars under each label (running total, like the dashboard waterfall)
		double run = 0;
		int by2 = y + 16 + ROW;
		List<int[]> bars = new ArrayList<>();
		for (Step st : steps) {
			double a, b;
			if (st.kind() == 0) { a = 0; b = st.v(); run = st.v(); }
			else if (st.kind() == 3) { a = run + st.v(); b = run; }
			else { a = run; b = run + st.v(); run += st.v(); }
			int x1 = barX + (int) Math.round(Math.min(a, b) / max * barW), x2 = barX + (int) Math.round(Math.max(a, b) / max * barW);
			int color = st.kind() == 0 ? TOTAL : st.kind() == 1 ? POS : st.kind() == 2 ? NEG : 0x80D0453E;
			bars.add(new int[]{Math.max(barX, x1), by2, Math.max(x1 + 1, Math.min(barX + barW, x2)), by2 + 2, color});
			by2 += STEP;
		}
		int consX = barX + (int) Math.round(Math.max(0, cons) / max * barW);

		h[0] += 22;                                                 // conservative + summary lines
		JsonElement pe = f.has("positions") ? f.get("positions") : null;
		List<JsonObject> pos = new ArrayList<>();
		int fixed = h[0] + 13 + 12;
		int room = Math.max(1, Math.min(MAX_POS, (s.height - 8 - fixed) / POS_ROW));
		int totalPos = 0;
		if (pe != null && pe.isJsonArray()) for (JsonElement e : pe.getAsJsonArray()) { totalPos++; if (pos.size() < room) pos.add(e.getAsJsonObject()); }
		h[0] += 13 + Math.max(1, pos.size()) * POS_ROW + 12;
		// move the panel up if it would run off the bottom of the screen (everything below is drawn relative to y)
		int shift = Math.max(0, y + h[0] - (s.height - 4));
		if (shift > 0) {
			int ny = Math.max(2, y - shift), dy = ny - y;
			y = ny;
			ly += dy;
			for (int[] b : bars) b[1] += dy;
			for (int[] b : bars) b[3] += dy;
			draw.clear();
			int ry0 = y + 16;
			for (Step st : steps) {
				final int ry = ry0;
				draw.add(() -> stepText(p, st.label(), st.v(), st.kind(), barX, barW, ry));
				ry0 += STEP;
			}
		}
		panelY = y;

		// background
		McStyle.panel(p, x, y, x + w, y + h[0]);
		p.text("Potential FCF", x + 5, y + 4, GOLD);
		String tw = HudFormatter.fmt(fcf);
		p.text(tw, x + w - 5 - font.width(tw), y + 4, GOOD);
		draw.forEach(Runnable::run);
		for (int[] b : bars) p.fill(b[0], b[1], b[2], b[3], b[4]);
		int lastBarY = bars.get(bars.size() - 1)[1];
		p.fill(consX, lastBarY - 2, consX + 1, lastBarY + 4, McStyle.TEXT);       // conservative marker

		int cy = ly;
		p.text(fit("conservative " + HudFormatter.fmt(cons) + "  (┃ marker)", barW), barX, cy, MUTED);
		cy += 10;
		p.text(fit("wallet: " + walletNote + (subtract ? "" : " · bids paid"), barW), barX, cy, MUTED);
		cy += 12;

		// positions
		p.fill(x + 4, cy, x + w - 4, cy + 1, McStyle.LINE);
		p.text(fit("Positions" + (totalPos > pos.size() ? " " + pos.size() + "/" + totalPos : "") + " (click = open)", barW), barX, cy + 3, GOLD);
		cy += 13;
		rowUuids.clear();
		posTop = cy;
		if (pos.isEmpty()) {
			p.text("none in the last AH scan", barX, cy + 2, MUTED);
		}
		for (JsonObject o : pos) {
			String role = str(o, "role"), status = str(o, "status");
			String item = str(o, "item");
			if (item == null) item = str(o, "key");
			item = item == null ? "?" : item.replaceAll("§.", "");

			double amount = d(o, "amount");
			String right;
			int rc;
			if ("sale".equals(role)) {
				right = "→ " + HudFormatter.fmt(d(o, "net"));
				rc = McStyle.BLUE;
			} else if ("winning".equals(status)) {
				Double pr = HudFormatter.num(o, "profit");
				right = pr == null ? "winning" : (pr >= 0 ? "+" : "−") + HudFormatter.fmt(Math.abs(pr));
				rc = pr == null ? TEXT : pr >= 0 ? GOOD : NEG;
			} else {
				right = "refund " + HudFormatter.fmt(amount);
				rc = MUTED;
			}
			p.text(fit(item, barW - font.width(right) - 6), barX, cy + 1, TEXT);
			p.text(right, barX + barW - font.width(right), cy + 1, rc);
			boolean over = o.has("overpriced") && o.get("overpriced").getAsBoolean();
			String sub = ("sale".equals(role) ? "selling " : "bid ") + HudFormatter.fmt(amount) + " · " + status
					+ (over ? " · above value" : "");
			p.text(fit(sub, barW), barX, cy + 10, over ? McStyle.GOLD : MUTED);
			rowUuids.add(str(o, "uuid"));
			cy += POS_ROW;
		}
		long mins = report() == null ? -1 : (System.currentTimeMillis() - d(f, "updated") > 0 ? (long) ((System.currentTimeMillis() - d(f, "updated")) / 60000) : 0);
		p.text(fit(AhScanner.running() ? AhScanner.status() : (mins < 0 ? "" : "scan " + (mins < 1 ? "now" : mins + "m ago") + " · ") + source(), barW - 12), barX,
				y + h[0] - 11, MUTED);
		p.text("⟳", barX + barW - 6, y + h[0] - 11, McStyle.BLUE);
		lastHeight = h[0];
	}

	private static int lastHeight, panelY;

	private static void stepText(Highlight.Painter p, String label, double v, int kind, int x, int w, int y) {
		var font = Minecraft.getInstance().font;
		String val = kind == 0 ? HudFormatter.fmt(v) : (v >= 0 ? "+" : "−") + HudFormatter.fmt(Math.abs(v));
		p.text(label, x, y, kind == 0 ? TEXT : McStyle.TEXT);
		p.text(val, x + w - font.width(val), y, kind == 0 ? TEXT : v >= 0 ? McStyle.BLUE : McStyle.BAD);
	}

	/** Cut text to fit the width, with an ellipsis. */
	static String fit(String t, int maxW) {
		var font = Minecraft.getInstance().font;
		if (font.width(t) <= maxW) return t;
		while (t.length() > 1 && font.width(t + "…") > maxW) t = t.substring(0, t.length() - 1);
		return t + "…";
	}

	static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	/** Returns true if the click hit the button or the panel. */
	static boolean click(Screen s, double mx, double my) {
		if (!active(s)) return false;
		int bx = left(s), by = Math.max(1, top(s) - BTN_H - 2);
		int ex = left(s) - DESK_W - 3;
		if (cfg.deskButton && s instanceof InventoryScreen && mx >= ex && mx <= ex + DESK_W && my >= by && my <= by + BTN_H) {
			Desk.toggle();
			return true;
		}
		if (mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H) {
			open = !open;
			if (open) refresh(true);
			return true;
		}
		if (!open) return false;
		if (my >= by && my <= by + BTN_H) {
			int tx = bx + BTN_W + 3;
			for (int i = 0; i < TABS.length; i++) {
				int tw = Minecraft.getInstance().font.width(TABS[i]) + 10;
				if (mx >= tx && mx <= tx + tw) {
					tab = i;
					rowUuids.clear();
					return true;
				}
				tx += tw + 2;
			}
		}
		int x = panelX(s), y = panelY, w = Math.min(W, Math.max(120, left(s) - 8));
		if (mx < x || mx > x + w || my < y || my > y + lastHeight) return false;
		if (my >= y + lastHeight - 12) {                                  // footer: refresh now
			refresh(true);
			return true;
		}
		if (tab == 2 && my <= y + 14 && mx >= x + w - 40) {               // 24h / 7d toggle
			week = mx >= x + w - 20;
			return true;
		}
		int i = (int) ((my - posTop) / posRowH);
		if (my >= posTop && i >= 0 && i < rowUuids.size() && rowUuids.get(i) != null) {
			Cmd.viewAuction(rowUuids.get(i));               // you asked to open it; you act yourself
		}
		return true;
	}

	// ------------------------------------------------------------------ tied up (pie)
	private static void frame(Highlight.Painter p, int x, int y, int w, int h) {
		McStyle.panel(p, x, y, x + w, y + h);
	}

	private static void paintTied(Screen s, Highlight.Painter p, int x, int y, int w, JsonObject f, JsonObject n,
			Double purse, Double bank, Double repWallet) {
		var font = Minecraft.getInstance().font;
		boolean subtract = n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean();
		double walletPurse = purse != null ? purse : HudFormatter.num(n, "purse") != null ? d(n, "purse") : repWallet != null ? repWallet : 0;
		double walletBank = bank != null ? bank : d(n, "bank");
		String[] labels = {"Purse", "Bank", "Listed items", "In winning bids", "Outbid (to collect)"};
		double[] v = {walletPurse, walletBank, d(n, "sales"), d(n, "bids"), subtract ? 0 : d(n, "refunds")};
		double total = 0;
		for (double a : v) total += Math.max(0, a);
		JsonElement pe = f.has("positions") ? f.get("positions") : null;
		List<JsonObject> pos = new ArrayList<>();
		if (pe != null && pe.isJsonArray()) for (JsonElement e : pe.getAsJsonArray()) pos.add(e.getAsJsonObject());
		pos.sort((a, b) -> Double.compare(tied(b), tied(a)));
		int r = 30, legendH = 5 * 11, top5 = Math.min(5, pos.size());
		int h = 16 + 2 * r + 6 + legendH + 14 + Math.max(1, top5) * 10 + 4;
		y = Math.max(2, Math.min(y, s.height - h - 4));
		frame(p, x, y, w, h);
		p.text("Tied up", x + 5, y + 4, GOLD);
		String tt = HudFormatter.fmt(total);
		p.text(tt, x + w - 5 - font.width(tt), y + 4, TEXT);
		int cx = x + w / 2, cy = y + 16 + r;
		if (total > 0) pie(p, cx, cy, r, v, total);
		int ly = cy + r + 6;
		for (int i = 0; i < labels.length; i++) {
			p.fill(x + 6, ly + 1, x + 12, ly + 7, SLICE[i]);
			p.text(fit(labels[i], w - 70), x + 16, ly, McStyle.TEXT);
			String val = HudFormatter.fmt(v[i]) + String.format(Locale.ROOT, " %3.0f%%", total > 0 ? 100 * Math.max(0, v[i]) / total : 0);
			p.text(val, x + w - 5 - font.width(val), ly, v[i] > 0 ? TEXT : MUTED);
			ly += 11;
		}
		p.fill(x + 4, ly + 1, x + w - 4, ly + 2, McStyle.LINE);
		p.text("Biggest positions (click = open)", x + 5, ly + 4, GOLD);
		ly += 14;
		rowUuids.clear();
		posTop = ly;
		posRowH = 10;
		if (pos.isEmpty()) p.text("none", x + 5, ly, MUTED);
		for (int i = 0; i < top5; i++) {
			JsonObject o = pos.get(i);
			String item = str(o, "item");
			item = item == null ? "?" : item.replaceAll("§.", "");
			String val = HudFormatter.fmt(tied(o));
			int color = "sale".equals(str(o, "role")) ? SLICE[2] : "winning".equals(str(o, "status")) ? SLICE[3] : SLICE[4];
			p.fill(x + 6, ly + 2, x + 9, ly + 6, color);
			p.text(fit(item, w - 24 - font.width(val)), x + 12, ly, McStyle.TEXT);
			p.text(val, x + w - 5 - font.width(val), ly, TEXT);
			rowUuids.add(str(o, "uuid"));
			ly += 10;
		}
		panelY = y;
		lastHeight = h;
	}

	/** Compact bazaar list (stocks, candy, …): sell / buy now and change vs the 24 h average. */
	private static void paintBazaar(Screen s, Highlight.Painter p, int x, int y, int w) {
		var font = Minecraft.getInstance().font;
		List<String> keys = Desk.bazaarList();
		int h = 16 + 10 + keys.size() * 19 + 14;
		y = Math.max(2, Math.min(y, s.height - h - 4));
		frame(p, x, y, w, h);
		p.text("Bazaar", x + 5, y + 4, GOLD);
		String hd = "sell · buy";
		p.text(hd, x + w - 5 - font.width(hd), y + 4, MUTED);
		int ly = y + 18;
		for (String k : keys) {
			DataClient.Live live = data.live(k);
			DataClient.Doc doc = data.doc(k);
			Double avg = doc == null || doc.json() == null ? null : HudFormatter.num(HudFormatter.obj(doc.json(), "stats", "24h", "sell"), "avg");
			p.text(fit(Desk.pretty(k), w - 10), x + 5, ly, McStyle.TEXT);
			String v = live == null ? "…" : HudFormatter.fmt(live.sell()) + " · " + HudFormatter.fmt(live.buy());
			p.text(v, x + w - 5 - font.width(v), ly + 9, TEXT);
			if (live != null && avg != null && avg > 0) {
				double c = (live.sell() / avg - 1) * 100;
				p.text(String.format(Locale.ROOT, "%+.1f%% vs 24h", c), x + 5, ly + 9, c >= 0 ? GOOD : NEG);
			}
			ly += 19;
		}
		p.text(fit("/nef bz add|remove <id>", w - 10), x + 5, ly + 2, MUTED);
		rowUuids.clear();
		panelY = y;
		lastHeight = h;
	}

	/** Coins a position ties up: a listing's net price, or the coins in your bid. */
	static double tied(JsonObject o) {
		return "sale".equals(str(o, "role")) ? d(o, "net") : d(o, "amount");
	}

	/** Filled pie from horizontal spans (one fill per run of same-colour pixels). */
	static void pie(Highlight.Painter p, int cx, int cy, int r, double[] v, double total) {
		double[] ends = new double[v.length];
		double acc = 0;
		for (int i = 0; i < v.length; i++) {
			acc += Math.max(0, v[i]) / total;
			ends[i] = acc * 2 * Math.PI;
		}
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt(r * r - dy * dy + 0.5));
			int runStart = -half, runColor = -1;
			for (int dx = -half; dx <= half + 1; dx++) {
				int color = -1;
				if (dx <= half) {
					double a = Math.atan2(dx, -dy);                    // 0 at 12 o'clock, clockwise
					if (a < 0) a += 2 * Math.PI;
					int k = 0;
					while (k < ends.length - 1 && a > ends[k]) k++;
					color = SLICE[k];
					if (dx * dx + dy * dy >= (r - 1) * (r - 1) || isEdge(a, ends)) color = darker(color);
				}
				if (color != runColor) {
					if (runColor != -1) p.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
					runStart = dx;
					runColor = color;
				}
			}
		}
	}

	private static boolean isEdge(double a, double[] ends) {
		for (int i = 0; i < ends.length - 1; i++) if (ends[i] > 0.001 && ends[i] < 2 * Math.PI - 0.001 && Math.abs(a - ends[i]) < 0.035) return true;
		return false;
	}

	private static int darker(int c) {
		return 0xFF000000 | ((c >> 16 & 0xFF) * 3 / 5) << 16 | ((c >> 8 & 0xFF) * 3 / 5) << 8 | (c & 0xFF) * 3 / 5;
	}

	// ------------------------------------------------------------------ trend (line chart)
	private static void paintTrend(Screen s, Highlight.Painter p, int x, int y, int w, JsonObject f, double fcfNow, Double bank) {
		var font = Minecraft.getInstance().font;
		long now = System.currentTimeMillis(), from = now - (week ? 7 : 1) * 86_400_000L;
		List<long[]> fcfPts = new ArrayList<>(), ahPts = new ArrayList<>();
		JsonElement se = f.has("series") ? f.get("series") : null;
		if (se != null && se.isJsonArray()) {
			for (JsonElement e : se.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				Double tsD = HudFormatter.num(o, "ts");
				if (tsD == null || tsD < from) continue;
				long ts = tsD.longValue();
				Double fc = HudFormatter.num(o, "fcf"), ah = HudFormatter.num(o, "on_ah");
				if (ah == null && fc != null && HudFormatter.num(o, "wallet") != null) ah = fc - d(o, "wallet");
				if (fc == null && ah != null) {                         // add your purse at that time (from the sidebar log)
					Double pu = PurseLog.at(ts);
					if (pu != null) fc = ah + pu + (bank == null ? 0 : bank);
				}
				if (fc != null) fcfPts.add(new long[]{ts, Math.round(fc)});
				if (ah != null) ahPts.add(new long[]{ts, Math.round(ah)});
			}
		}
		fcfPts.add(new long[]{now, Math.round(fcfNow)});
		int ch = 96, h = 16 + 12 + ch + 14 + 3 * 10 + 6;
		y = Math.max(2, Math.min(y, s.height - h - 4));
		frame(p, x, y, w, h);
		p.text("Potential FCF", x + 5, y + 4, GOLD);
		p.text("24h", x + w - 40, y + 4, week ? MUTED : TEXT);
		p.text("7d", x + w - 16, y + 4, week ? TEXT : MUTED);
		if (!week) p.fill(x + w - 40, y + 13, x + w - 40 + font.width("24h"), y + 14, TEXT);
		else p.fill(x + w - 16, y + 13, x + w - 16 + font.width("7d"), y + 14, TEXT);
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
		for (List<long[]> l : List.of(fcfPts, ahPts)) for (long[] q : l) { lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]); }
		long pad = Math.max(1, (hi - lo) / 10);
		lo = Math.max(0, lo - pad);
		hi += pad;
		int gx = x + 32, gy = y + 28, gw = w - 38, gh = ch - 14;
		// grid + y labels
		for (int k = 0; k <= 2; k++) {
			long v = lo + (hi - lo) * k / 2;
			int yy = gy + gh - (int) ((double) (v - lo) / (hi - lo) * gh);
			p.fill(gx, yy, gx + gw, yy + 1, 0xFFA8A8A8);
			String t = HudFormatter.fmt((double) v);
			p.text(t, gx - 3 - font.width(t), yy - 4, MUTED);
		}
		String l0 = week ? "-7d" : "-24h";
		p.text(l0, gx, gy + gh + 3, MUTED);
		p.text("now", gx + gw - font.width("now"), gy + gh + 3, MUTED);
		line(p, ahPts, from, now, lo, hi, gx, gy, gw, gh, 0xFF8A8A8A);
		line(p, fcfPts, from, now, lo, hi, gx, gy, gw, gh, 0xFFB07A00);
		int ly = gy + gh + 14;
		p.fill(x + 6, ly + 3, x + 14, ly + 5, 0xFFB07A00);
		String nowTxt = HudFormatter.fmt(fcfNow);
		p.text("FCF", x + 17, ly, McStyle.TEXT);
		p.text(nowTxt, x + w - 5 - font.width(nowTxt), ly, TEXT);
		ly += 10;
		p.fill(x + 6, ly + 3, x + 14, ly + 5, 0xFF8A8A8A);
		p.text("on the AH", x + 17, ly, McStyle.TEXT);
		String ahTxt = ahPts.isEmpty() ? "-" : HudFormatter.fmt((double) ahPts.get(ahPts.size() - 1)[1]);
		p.text(ahTxt, x + w - 5 - font.width(ahTxt), ly, TEXT);
		ly += 10;
		if (fcfPts.size() > 1) {
			double ch0 = fcfNow - fcfPts.get(0)[1];
			String c = (ch0 >= 0 ? "+" : "−") + HudFormatter.fmt(Math.abs(ch0));
			p.text("change " + (week ? "7d" : "24h"), x + 5, ly, MUTED);
			p.text(c, x + w - 5 - font.width(c), ly, ch0 >= 0 ? GOOD : NEG);
		} else {
			p.text(fit("history builds up every 15 min", w - 10), x + 5, ly, MUTED);
		}
		rowUuids.clear();
		panelY = y;
		lastHeight = h;
	}

	/** 1-px line: vertical fills between consecutive points, column by column. */
	static void line(Highlight.Painter p, List<long[]> pts, long t0, long t1, long lo, long hi,
			int gx, int gy, int gw, int gh, int color) {
		if (pts.isEmpty()) return;
		int prevX = -1, prevY = 0;
		for (long[] q : pts) {
			int px = gx + (int) Math.round((double) (q[0] - t0) / (t1 - t0) * (gw - 1));
			int py = gy + gh - (int) Math.round((double) (q[1] - lo) / (hi - lo) * gh);
			px = Math.max(gx, Math.min(gx + gw - 1, px));
			if (prevX < 0) {
				p.fill(px, py, px + 2, py + 2, color);
			} else {
				int steps = Math.max(1, px - prevX);
				for (int k = 1; k <= steps; k++) {
					int cx = prevX + k, cyA = prevY + (py - prevY) * (k - 1) / steps, cyB = prevY + (py - prevY) * k / steps;
					p.fill(cx, Math.min(cyA, cyB), cx + 1, Math.max(cyA, cyB) + 2, color);
				}
			}
			prevX = px;
			prevY = py;
		}
		p.fill(prevX - 1, prevY - 1, prevX + 2, prevY + 3, color);         // marker on the latest point
	}

	/**
	 * The freshest positions report: the mod's own AH scan, an optional local server (/wallet) or the website's 15-min scan.
	 * The bank balance and the GitHub FCF history are carried over from the downloaded report.
	 */
	static JsonObject report() {
		return withBank(rawReport());
	}

	/** Your bank from your own API key (MyWallet) when the report has none (no local server). */
	private static JsonObject withBank(JsonObject r) {
		JsonObject w = MyWallet.get();
		if (r == null || w == null) return r;
		JsonObject now = HudFormatter.obj(r, "now");
		if (now != null && HudFormatter.num(now, "bank") != null) return r;
		JsonObject out = r.deepCopy();
		JsonObject n = now == null ? new JsonObject() : now.deepCopy();
		n.addProperty("bank", d(w, "bank"));
		n.addProperty("personal_bank", d(w, "personal_bank"));
		out.add("now", n);
		return out;
	}

	private static JsonObject rawReport() {
		JsonObject web = report, mod = AhScanner.report();
		if (mod == null) return web;
		double webTs = d(HudFormatter.obj(web, "fcf"), "updated"), modTs = d(HudFormatter.obj(mod, "fcf"), "updated");
		if (web != null && webTs > modTs) return web;
		JsonObject out = mod.deepCopy();
		if (web != null && web.has("now")) out.add("now", web.get("now"));
		JsonObject wf = HudFormatter.obj(web, "fcf"), of = HudFormatter.obj(out, "fcf");
		if (wf != null && wf.has("series") && of != null && of.has("series")) {   // GitHub history + the mod's own points
			com.google.gson.JsonArray merged = new com.google.gson.JsonArray();
			java.util.List<JsonObject> pts = new java.util.ArrayList<>();
			for (JsonElement e : wf.getAsJsonArray("series")) pts.add(e.getAsJsonObject());
			for (JsonElement e : of.getAsJsonArray("series")) pts.add(e.getAsJsonObject());
			pts.sort((a, b) -> Double.compare(d(a, "ts"), d(b, "ts")));
			pts.forEach(merged::add);
			of.add("series", merged);
		}
		return out;
	}

	static String source() {
		JsonObject mod = AhScanner.report();
		if (mod != null && rawReport() != report) return "this mod";
		return source;
	}

	static long fetchedAt() { return Math.max(fetchedAt, AhScanner.lastDone()); }

	/** Chat version (/nef fcf). */
	static List<net.minecraft.network.chat.Component> chat(JsonObject rep, String src) {
		List<net.minecraft.network.chat.Component> out = new ArrayList<>();
		JsonObject n = HudFormatter.obj(rep, "fcf", "now");
		if (n == null) {
			out.add(HudFormatter.line("[NEF] No FCF data yet: it fills in after the first AH scan (every 15 min, or ⟳ in the FLIP panel).", HudFormatter.GRAY));
			return out;
		}
		out.add(HudFormatter.line("━━ Potential FCF ", HudFormatter.GOLD, HudFormatter.fmt(HudFormatter.num(n, "fcf")), HudFormatter.GREEN,
				"  (conservative " + HudFormatter.fmt(HudFormatter.num(n, "fcf_conservative")) + ", " + src + ")", HudFormatter.DARK));
		out.add(HudFormatter.line(String.format(Locale.ROOT, " wallet %s + sales %s − bids %s%s + expected %s + refunds %s",
				HudFormatter.fmt(HudFormatter.num(n, "wallet")), HudFormatter.fmt(HudFormatter.num(n, "sales")),
				HudFormatter.fmt(HudFormatter.num(n, "bids")),
				n.has("bids_subtracted") && n.get("bids_subtracted").getAsBoolean() ? "" : " (already paid)",
				HudFormatter.fmt(HudFormatter.num(n, "expected")), HudFormatter.fmt(HudFormatter.num(n, "refunds"))), HudFormatter.GRAY));
		out.add(HudFormatter.line(" Open the FCF button above your inventory (E) for the full breakdown.", HudFormatter.DARK));
		return out;
	}
}

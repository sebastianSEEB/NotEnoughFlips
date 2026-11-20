package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * A big 7-day price chart beside the menu for the auction item you're hovering (Auction House, your inventory, any
 * chest). Data: SkyCofl's 7-day sale history (fetched on hover, cached 10 min).
 *
 *   faint band   cheapest → highest sale in each slice of time
 *   line         average sale price
 *   yellow       cheapest BIN now          green dashes   fair value          white   the price of this listing
 *   bars         sales volume
 *
 * The y-axis ignores outliers (5th–95th percentile), so one 75M sale of a maxed copy doesn't flatten the chart; sales
 * above the range show as a red tick at the top. A red banner appears when the item looks manipulated.
 */
final class PriceChart {
	// the mod's panel look (McStyle): dark panel, white text
	private static final int GRID = 0x30FFFFFF, LINE = 0xFF3987E5, BAND = 0x402A6FD6,
			LBIN = 0xFFFFAA00, FAIR = McStyle.GOOD, LISTING = McStyle.TEXT, VOL = 0x882A6FD6, TEXT = McStyle.TEXT, MUTED = McStyle.MUTED,
			BAD = McStyle.BAD;

	private record Hover(String key, String name, Double listing, long at, List<net.minecraft.network.chat.Component> lines) {}

	private static NefConfig cfg;
	private static volatile Hover hover;

	private PriceChart() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	/** Called from the tooltip of an auction item (any screen). */
	static void hovered(String key, String name, Double listingPrice) {
		hovered(key, name, listingPrice, null);
	}

	/** @param lines the tooltip's lines (still being filled): used to keep the chart out of the tooltip's way */
	static void hovered(String key, String name, Double listingPrice, List<net.minecraft.network.chat.Component> lines) {
		hover = new Hover(key, name, listingPrice, System.currentTimeMillis(), lines);
	}

	static void paint(Screen s, Highlight.Painter p) {
		Hover h = hover;
		if (cfg == null || !cfg.priceChart || h == null || !(s instanceof AbstractContainerScreen<?>)) return;
		if (System.currentTimeMillis() - h.at() > 250) return;               // only while you're hovering it
		var font = Minecraft.getInstance().font;
		int[] box = GuiPos.box(s);
		List<double[]> pts = NefClient.cofl == null ? null : NefClient.cofl.week(h.key(), true);
		JsonObject d = NefClient.docFor(h.key(), false);
		if (d == null && NefClient.cofl != null) d = NefClient.cofl.doc(h.key(), false);

		boolean manip = d != null && Market.hasSevereFlag(d);
		String manipText = manip ? firstSevere(d) : null;
		JsonObject cleaned = d == null ? null : HudFormatter.obj(d, "market", "clean");
		int bannerH = manip ? 22 : cleaned != null ? 11 : 0;
		int[] tip = tooltipBox(s, h, font);
		int[] spot = place(s.width, s.height, box, DealsPanel.isOpen(), bannerH, tip);
		int x = spot[0], top = spot[1], w = spot[2], ph = spot[3];
		int height = chartHeight(bannerH, ph);
		McStyle.panel(p, x, top, x + w, top + height);
		if (manip) p.fill(x + 2, top + 2, x + w - 2, top + 3, BAD);
		p.text(FcfPanel.fit(h.name(), w - 60), x + 4, top + 3, TEXT);
		p.text("7 days", x + w - font.width("7 days") - 4, top + 3, MUTED);
		int y = top + 14;
		if (manip) {
			p.fill(x + 2, y, x + w - 2, y + 20, 0x55FF0000);
			p.text("⚠ POSSIBLE MANIPULATION", x + 5, y + 1, BAD);
			p.text(FcfPanel.fit(manipText, w - 10), x + 5, y + 10, BAD);
			y += bannerH;
		} else if (cleaned != null) {
			p.text(FcfPanel.fit("Red = looked manipulated, left out of the prices", w - 10), x + 5, y + 1, BAD);
			y += bannerH;
		}
		int cx = x + 30, cw = w - 34, cy = y + 2, ch = ph - 22;            // plot area (left 30 px: y labels)
		if (pts == null || pts.size() < 2) {
			String why = pts != null ? "not enough sales for a chart"
					: NefClient.cofl != null && NefClient.cofl.failed(h.key()) ? "SkyCofl is busy, retrying in a minute…"
					: "loading 7-day history…";
			p.text(why, x + 6, cy + ch / 2, MUTED);
			return;
		}
		long now = System.currentTimeMillis(), from = now - 7L * 86_400_000L;
		int cols = Math.max(10, cw / 2);
		double[] avg = new double[cols], mn = new double[cols], mx = new double[cols], vol = new double[cols];
		int[] n = new int[cols];
		boolean[] badCol = new boolean[cols];
		boolean[] badPt = CoflClient.manipulated(pts);
		List<Double> all = new ArrayList<>();
		double maxSale = 0;
		for (int pi = 0; pi < pts.size(); pi++) {
			double[] pt = pts.get(pi);
			if (pt[0] < from) continue;
			int c = (int) Math.min(cols - 1, Math.max(0, (pt[0] - from) / (now - from) * cols));
			if (badPt[pi]) badCol[c] = true;
			if (n[c] == 0) { mn[c] = pt[1]; mx[c] = pt[2]; } else { mn[c] = Math.min(mn[c], pt[1]); mx[c] = Math.max(mx[c], pt[2]); }
			avg[c] += pt[3];
			vol[c] += pt[4];
			n[c]++;
			all.add(pt[3]);
			maxSale = Math.max(maxSale, pt[2]);
		}
		if (all.size() < 2) {
			p.text("no sales in the last 7 days", x + 6, cy + ch / 2, MUTED);
			return;
		}
		all.sort(Double::compare);
		double p5 = all.get((int) (all.size() * 0.05)), p95 = all.get(Math.min(all.size() - 1, (int) (all.size() * 0.95)));
		double med = Market.median(all);
		Double lbin = d == null ? null : HudFormatter.num(HudFormatter.obj(d, "market", "listed"), "lowest");
		if (lbin == null && d != null) lbin = HudFormatter.num(HudFormatter.obj(d, "summary"), "lbin");
		Double fair = d == null ? null : Verdict.fair(d);
		double lo = p5, hi = p95;
		for (Double v : new Double[]{lbin, fair, h.listing()}) {
			if (v != null && v > med / 3 && v < med * 3) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
		}
		double pad = Math.max((hi - lo) * 0.08, hi * 0.01);
		lo = Math.max(0, lo - pad);
		hi = hi + pad;
		final double LO = lo, HI = hi;
		java.util.function.DoubleToIntFunction yOf = v -> cy + ch - (int) Math.round((Math.min(HI, Math.max(LO, v)) - LO) / (HI - LO) * ch);

		// grid + y labels
		for (int g = 0; g <= 3; g++) {
			double v = lo + (hi - lo) * g / 3.0;
			int gy = yOf.applyAsInt(v);
			p.fill(cx, gy, cx + cw, gy + 1, GRID);
			String lab = HudFormatter.fmt(v);
			p.text(lab, cx - font.width(lab) - 2, gy - 4, MUTED);
		}
		for (int day = 1; day < 7; day++) {                                   // day separators
			int dx = cx + cw * day / 7;
			p.fill(dx, cy, dx + 1, cy + ch, GRID);
		}
		// band + avg line + outlier ticks
		int prevY = Integer.MIN_VALUE;
		double vmax = 0;
		for (double v : vol) vmax = Math.max(vmax, v);
		for (int c = 0; c < cols; c++) {
			int x1 = cx + c * cw / cols, x2 = Math.max(x1 + 1, cx + (c + 1) * cw / cols);
			if (n[c] == 0) { prevY = Integer.MIN_VALUE; continue; }
			if (cleaned != null && badCol[c]) p.fill(x1, cy, x2, cy + ch, 0x40FF3030);   // left out of the prices
			int yTop = yOf.applyAsInt(mx[c]), yBot = yOf.applyAsInt(mn[c]);
			p.fill(x1, Math.min(yTop, yBot), x2, Math.max(yTop, yBot) + 1, BAND);
			int ya = yOf.applyAsInt(avg[c] / n[c]);
			if (prevY != Integer.MIN_VALUE) p.fill(x1, Math.min(prevY, ya), x1 + 1, Math.max(prevY, ya) + 1, LINE);
			p.fill(x1, ya, x2, ya + 1, LINE);
			prevY = ya;
			if (mx[c] > med * 2 && mx[c] > hi) p.fill(x1, cy - 1, x2, cy + 2, BAD);   // a sale far above the chart
			if (vmax > 0 && vol[c] > 0) {
				int bh = (int) Math.max(1, Math.round(vol[c] / vmax * 14));
				p.fill(x1, cy + ch + 18 - bh, x2, cy + ch + 18, VOL);
			}
		}
		// reference lines
		dashed(p, cx, cw, lbin, yOf, LBIN, med);
		dashed(p, cx, cw, fair, yOf, FAIR, med);
		if (h.listing() != null && h.listing() > med / 3 && h.listing() < med * 3) {
			int ly = yOf.applyAsInt(h.listing());
			p.fill(cx, ly, cx + cw, ly + 1, LISTING);
		}
		// legend / footer
		int fy = cy + ch + 21;
		int lx = x + 4;
		lx = legend(p, lx, fy, LINE, "avg");
		if (lbin != null) lx = legend(p, lx, fy, LBIN, "BIN " + HudFormatter.fmt(lbin));
		if (fair != null) lx = legend(p, lx, fy, FAIR, "fair " + HudFormatter.fmt(fair));
		if (h.listing() != null && lx < x + w - 40) legend(p, lx, fy, LISTING, "this " + HudFormatter.fmt(h.listing()));
		double sold = 0;
		for (double v : vol) sold += v;
		String foot = String.format(Locale.ROOT, "median %s · %s sold · %s", HudFormatter.fmt(med), HudFormatter.fmt(sold),
				maxSale > med * 2 ? "top sale " + HudFormatter.fmt(maxSale) : "SkyCofl");
		p.text(FcfPanel.fit(foot, w - 8), x + 4, fy + 10, MUTED);
	}

	private static void dashed(Highlight.Painter p, int cx, int cw, Double v, java.util.function.DoubleToIntFunction yOf, int color, double med) {
		if (v == null || v <= med / 3 || v >= med * 3) return;
		int y = yOf.applyAsInt(v);
		for (int i = 0; i < cw; i += 6) p.fill(cx + i, y, cx + Math.min(cw, i + 3), y + 1, color);
	}

	private static int legend(Highlight.Painter p, int x, int y, int color, String t) {
		p.fill(x, y + 3, x + 6, y + 5, color);
		p.text(t, x + 8, y, TEXT);
		return x + 8 + Minecraft.getInstance().font.width(t) + 6;
	}

	private static String firstSevere(JsonObject d) {
		JsonObject m = HudFormatter.obj(d, "market");
		if (m != null && m.has("flags") && m.get("flags").isJsonArray()) {
			for (var e : m.getAsJsonArray("flags")) {
				if (!e.isJsonObject()) continue;
				JsonObject f = e.getAsJsonObject();
				if (f.has("level") && f.get("level").getAsInt() >= 2 && !"thin".equals(HudFormatter.str(f, "code")))
					return HudFormatter.str(f, "text");
			}
		}
		return "prices look unusual";
	}

	private static int chartHeight(int bannerH, int ph) {
		return 14 + bannerH + 2 + (ph - 22) + 21 + 20;                   // title, banner, plot, volume, legend + footer
	}

	/**
	 * Where vanilla draws the hovered item's tooltip (DefaultTooltipPositioner: right of the mouse, flipped left when
	 * it doesn't fit, pushed up when it would run off the bottom), with its border: {x1, y1, x2, y2}, or null.
	 */
	private static int[] tooltipBox(Screen s, Hover h, net.minecraft.client.gui.Font font) {
		int mx = GuiPos.mouseX, my = GuiPos.mouseY;
		List<net.minecraft.network.chat.Component> lines = h.lines();
		if (lines == null || lines.isEmpty() || mx < 0) return null;
		int tw = 0;
		for (net.minecraft.network.chat.Component c : List.copyOf(lines)) tw = Math.max(tw, font.width(c.getString()));
		int th = lines.size() * 10;
		return tooltipBox(s.width, s.height, mx, my, tw, th);
	}

	/** Pure: see above. */
	static int[] tooltipBox(int sw, int sh, int mx, int my, int tw, int th) {
		int x = mx + 12, y = my - 12;
		if (x + tw > sw) x = Math.max(mx - 24 - tw, 4);
		if (y + th + 3 > sh) y = sh - th - 3;
		return new int[]{x - 5, y - 5, x + tw + 5, y + th + 5};
	}

	/**
	 * Pure: where the chart goes: {x, top, width, plot height}. As high as possible, beside the menu (left first unless
	 * the DEALS list is there), never over the tooltip: tries the top of the screen, then just above the menu, on both
	 * sides, then a shorter chart; if nothing is free, the spot that covers the least of the tooltip.
	 */
	static int[] place(int sw, int sh, int[] box, boolean dealsOpen, int bannerH, int[] tip) {
		int leftSpace = box[0] - 8, rightSpace = sw - (box[0] + box[2]) - 8;
		boolean leftFirst = !(dealsOpen && rightSpace >= 110) && (leftSpace >= 150 || leftSpace >= rightSpace);
		int[][] sides = new int[2][];
		int lw = Math.min(320, Math.max(110, leftSpace)), rw = Math.min(320, Math.max(110, rightSpace));
		int[] left = {Math.max(2, box[0] - 6 - lw), lw}, right = {Math.min(sw - rw - 2, box[0] + box[2] + 6), rw};
		sides[0] = leftFirst ? left : right;
		sides[1] = leftFirst ? right : left;
		int full = Math.max(92, Math.min(190, sh / 2));
		int[] best = null;
		long bestOverlap = Long.MAX_VALUE;
		for (int ph : new int[]{full, Math.max(70, full * 2 / 3), 60}) {
			int hgt = chartHeight(bannerH, ph);
			for (int[] full2 : sides) {
				// the full-width spot, then the same spot trimmed so it ends before / starts after the tooltip
				java.util.List<int[]> variants = new java.util.ArrayList<>();
				variants.add(full2);
				if (tip != null) {
					int x1 = full2[0], x2 = full2[0] + full2[1];
					if (tip[0] > x1 && tip[0] - 2 - x1 >= 140) variants.add(new int[]{x1, tip[0] - 2 - x1});
					if (tip[2] < x2 && x2 - (tip[2] + 2) >= 140) variants.add(new int[]{tip[2] + 2, x2 - tip[2] - 2});
				}
				for (int[] side : variants) {
					for (int top : new int[]{2, Math.max(2, box[1] - 4), Math.max(2, sh - hgt - 2)}) {
						long ov = tip == null ? 0 : overlap(side[0], top, side[0] + side[1], top + hgt, tip);
						if (ov == 0) return new int[]{side[0], top, side[1], ph};
						if (ov < bestOverlap) { bestOverlap = ov; best = new int[]{side[0], top, side[1], ph}; }
					}
				}
			}
		}
		return best;
	}

	private static long overlap(int x1, int y1, int x2, int y2, int[] r) {
		long w = Math.min(x2, r[2]) - Math.max(x1, r[0]), h = Math.min(y2, r[3]) - Math.max(y1, r[1]);
		return w <= 0 || h <= 0 ? 0 : w * h;
	}

}

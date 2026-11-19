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
	// inventory look (McStyle): grey container, dark text
	private static final int GRID = 0x30000000, LINE = 0xFF2A6FD6, BAND = 0x402A6FD6,
			LBIN = 0xFFB07A00, FAIR = McStyle.GOOD, LISTING = McStyle.TEXT, VOL = 0x882A6FD6, TEXT = McStyle.TEXT, MUTED = McStyle.MUTED,
			BAD = McStyle.BAD;

	private record Hover(String key, String name, Double listing, long at) {}

	private static NefConfig cfg;
	private static volatile Hover hover;

	private PriceChart() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	/** Called from the tooltip of an auction item (any screen). */
	static void hovered(String key, String name, Double listingPrice) {
		hover = new Hover(key, name, listingPrice, System.currentTimeMillis());
	}

	static void paint(Screen s, Highlight.Painter p) {
		Hover h = hover;
		if (cfg == null || !cfg.priceChart || h == null || !(s instanceof AbstractContainerScreen<?>)) return;
		if (System.currentTimeMillis() - h.at() > 250) return;               // only while you're hovering it
		var font = Minecraft.getInstance().font;
		int[] box = GuiPos.box(s);
		int leftSpace = box[0] - 8, rightSpace = s.width - (box[0] + box[2]) - 8;
		int w, x;
		boolean leftBusy = DealsPanel.isOpen() && rightSpace >= 110;           // the DEALS list is on the left: use the right
		if (!leftBusy && (leftSpace >= 150 || leftSpace >= rightSpace)) {    // left of the menu (over the FCF panel)
			w = Math.min(320, Math.max(110, leftSpace));
			x = Math.max(2, box[0] - 6 - w);
		} else {
			w = Math.min(320, Math.max(110, rightSpace));
			x = Math.min(s.width - w - 2, box[0] + box[2] + 6);
		}
		int ph = Math.max(92, Math.min(190, s.height / 2)), top = Math.max(2, box[1] - 4);   // grows with your screen
		List<double[]> pts = NefClient.cofl == null ? null : NefClient.cofl.week(h.key(), true);
		JsonObject d = NefClient.docFor(h.key(), false);
		if (d == null && NefClient.cofl != null) d = NefClient.cofl.doc(h.key(), false);

		boolean manip = d != null && Market.hasSevereFlag(d);
		String manipText = manip ? firstSevere(d) : null;
		JsonObject cleaned = d == null ? null : HudFormatter.obj(d, "market", "clean");
		int bannerH = manip ? 22 : cleaned != null ? 11 : 0;
		int height = 14 + bannerH + 2 + (ph - 22) + 21 + 20;           // title, banner, plot, volume, legend + footer
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
}

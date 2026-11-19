package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auction-house price forecasts made on your PC (the bazaar ones come from the public data site). Same method as
 * market/forecast.py, in short:
 *
 *   1. resample the item's 7-day SkyCofl sales (already loaded for its tooltip / chart) to one value per hour
 *   2. diagnose the pattern: randomness (average hour-to-hour move) and trend (regression slope, |t| ≥ 2 and ≥ 1 % move)
 *   3. try the models that fit the pattern: last value, moving averages, exponential smoothing (α by randomness),
 *      and Holt + regression only when there is a trend
 *   4. score each on its own past forecasts (MAPE per horizon), drop models whose tracking signal is beyond ±4,
 *      keep the best; if every model is biased the result says so and the flip lists ignore it
 */
final class LocalForecast {
	private record Cached(long at, Forecasts.Side side) {}

	private static final Map<String, Cached> cache = new ConcurrentHashMap<>();
	private static final int[] H = {1, 6, 24};
	static final int MIN_POINTS = 24;

	private LocalForecast() {}

	/** Forecast for an AH item from its cached SkyCofl week (no request is made here). Null when there isn't enough data. */
	static Forecasts.Side forItem(String key) {
		if (key == null || NefClient.cofl == null) return null;
		long now = System.currentTimeMillis();
		Cached c = cache.get(key);
		if (c != null && now - c.at() < 30 * 60_000L) return c.side();
		List<double[]> week = NefClient.cofl.weekClean(key, false);
		Forecasts.Side s = week == null ? null : of(hourly(week));
		if (week != null) cache.put(key, new Cached(now, s));
		if (cache.size() > 3000) cache.clear();
		return s;
	}

	/**
	 * Bazaar forecast made on your PC from the product's SkyCofl week ({time, buy, sell} 2 h bars), used when the data
	 * site has none (its hourly history depends on GitHub's scheduler, which can lag by hours). Queues the week if needed.
	 */
	static Forecasts.Side forBazaar(String id, String side, DataClient data) {
		if (id == null || data == null) return null;
		long now = System.currentTimeMillis();
		String ck = id + "|" + side;
		Cached c = cache.get(ck);
		if (c != null && now - c.at() < 30 * 60_000L) return c.side();
		List<double[]> wk = data.bazaarWeek(id);
		if (wk == null) { data.bazaarSlide(id); return null; }
		int col = "buy".equals(side) ? 1 : 2;
		List<double[]> pts = new ArrayList<>();
		for (double[] q : wk) if (q[col] > 0) pts.add(new double[]{q[0], 0, 0, q[col], 1});
		Forecasts.Side s = of(hourly(pts));
		cache.put(ck, new Cached(now, s));
		return s;
	}

	/** [time, min, max, avg, volume] periods → one average per hour, gaps carried forward. */
	static double[] hourly(List<double[]> week) {
		if (week.isEmpty()) return new double[0];
		long h0 = (long) (week.get(0)[0] / 3_600_000L), h1 = (long) (week.get(week.size() - 1)[0] / 3_600_000L);
		int n = (int) Math.min(24 * 8, h1 - h0 + 1);
		long start = h1 - n + 1;
		double[] out = new double[n];
		double[] sum = new double[n];
		int[] cnt = new int[n];
		for (double[] p : week) {
			long h = (long) (p[0] / 3_600_000L);
			if (h < start || h > h1) continue;
			sum[(int) (h - start)] += p[3];
			cnt[(int) (h - start)]++;
		}
		double last = Double.NaN;
		int firstReal = -1;
		for (int i = 0; i < n; i++) {
			if (cnt[i] > 0) { last = sum[i] / cnt[i]; if (firstReal < 0) firstReal = i; }
			out[i] = last;
		}
		if (firstReal < 0) return new double[0];
		double[] trimmed = new double[n - firstReal];
		System.arraycopy(out, firstReal, trimmed, 0, trimmed.length);
		return trimmed;
	}

	// ------------------------------------------------------------------ models
	/** model: 0 naive, 1 moving average (k), 2 exponential smoothing (a), 3 Holt (a, b), 4 linear regression (window k). */
	private record Model(int type, int k, double a, double b, String name) {}

	/** Forecast h hours after index t (using D[0..t]). */
	static double predict(double[] D, int t, int h, int type, int k, double a, double b) {
		switch (type) {
			case 1 -> {
				double s = 0;
				int m = Math.min(k, t + 1);
				for (int i = t - m + 1; i <= t; i++) s += D[i];
				return s / m;
			}
			case 2 -> {
				double f = D[Math.max(0, t - 47)];
				for (int i = Math.max(0, t - 47); i <= t; i++) f = a * D[i] + (1 - a) * f;
				return f;
			}
			case 3 -> {
				int s0 = Math.max(0, t - 47);
				double l = D[s0], tr = s0 + 1 <= t ? D[s0 + 1] - D[s0] : 0;
				for (int i = s0 + 1; i <= t; i++) {
					double pl = l;
					l = a * D[i] + (1 - a) * (l + tr);
					tr = b * (l - pl) + (1 - b) * tr;
				}
				return l + h * tr;
			}
			case 4 -> {
				int m = Math.min(k, t + 1), s0 = t - m + 1;
				double[] ab = regression(D, s0, t);
				return ab[0] + ab[1] * (t - s0 + h);
			}
			default -> {
				return D[t];
			}
		}
	}

	/** Least squares y = a + b·x over D[from..to], x = 0…; returns {a, b, t-stat of b}. */
	static double[] regression(double[] D, int from, int to) {
		int n = to - from + 1;
		if (n < 3) return new double[]{D[to], 0, 0};
		double mx = (n - 1) / 2.0, my = 0;
		for (int i = from; i <= to; i++) my += D[i];
		my /= n;
		double sxy = 0, sxx = 0;
		for (int i = 0; i < n; i++) { sxy += (i - mx) * (D[from + i] - my); sxx += (i - mx) * (i - mx); }
		double b = sxx > 0 ? sxy / sxx : 0, a = my - b * mx, sse = 0;
		for (int i = 0; i < n; i++) { double e = D[from + i] - (a + b * i); sse += e * e; }
		double se = n > 2 && sxx > 0 ? Math.sqrt(sse / (n - 2) / sxx) : 0;
		double t = se > 0 ? b / se : (b == 0 ? 0 : Math.copySign(99, b));
		return new double[]{a, b, t};
	}

	/** The whole method on one hourly series. Null when there are fewer than MIN_POINTS hours. */
	static Forecasts.Side of(double[] D) {
		int n = D.length;
		if (n < MIN_POINTS) return null;
		// pattern
		int w = Math.min(48, n);
		double level = 0, noise = 0;
		for (int i = n - w; i < n; i++) level += D[i];
		level /= w;
		for (int i = n - w + 1; i < n; i++) noise += Math.abs(D[i] - D[i - 1]);
		noise = level > 0 ? noise / (w - 1) / level * 100 : 0;
		String randomness = noise > 2.0 ? "high" : noise < 0.5 ? "low" : "medium";
		double[] reg = regression(D, n - w, n - 1);
		boolean trend = w >= 12 && Math.abs(reg[2]) >= 2 && level > 0 && Math.abs(reg[1]) * w / level >= 0.01;
		double[] alphas = randomness.equals("high") ? new double[]{0.1, 0.2} : randomness.equals("low") ? new double[]{0.5, 0.7} : new double[]{0.2, 0.3, 0.5};
		List<Model> models = new ArrayList<>();
		models.add(new Model(0, 0, 0, 0, "Last period"));
		for (int k : new int[]{3, 6, 12, 24}) models.add(new Model(1, k, 0, 0, k + "-hour moving average"));
		for (double a : alphas) models.add(new Model(2, 0, a, 0, "Exponential smoothing α=" + a));
		if (trend) {
			for (double a : alphas) for (double b : new double[]{0.1, 0.3}) models.add(new Model(3, 0, a, b, "Holt α=" + a + " β=" + b));
			models.add(new Model(4, 24, 0, 0, "24-hour trend line"));
			models.add(new Model(4, 48, 0, 0, "48-hour trend line"));
		}
		// score: one-step tracking signal for bias, MAPE per horizon on the last up-to-72 hours
		int eval = Math.min(72, n - 12);
		Model best = null;
		double[] bestMape = null;
		double bestScore = Double.MAX_VALUE;
		boolean anyUnbiased = false;
		Model fallback = null;
		double[] fallbackMape = null;
		double fallbackScore = Double.MAX_VALUE;
		for (Model m : models) {
			double sumFe = 0, sumAbs = 0;
			int cnt = 0;
			double[] mape = new double[H.length];
			for (int hi = 0; hi < H.length; hi++) {
				int h = H[hi], c2 = 0;
				double s = 0;
				for (int t = n - eval - 1; t + h < n; t++) {
					if (t < 1) continue;
					double f = predict(D, t, h, m.type(), m.k(), m.a(), m.b());
					double y = D[t + h];
					if (y <= 0) continue;
					s += Math.abs(y - f) / y;
					c2++;
					if (h == 1) { sumFe += y - f; sumAbs += Math.abs(y - f); cnt++; }
				}
				mape[hi] = c2 == 0 ? Double.NaN : 100 * s / c2;
			}
			double mad = cnt == 0 ? 0 : sumAbs / cnt;
			double ts = mad > 0 ? sumFe / mad : 0;
			double score = Double.isNaN(mape[0]) ? Double.MAX_VALUE : mape[0] + (Double.isNaN(mape[2]) ? 0 : 0.25 * mape[2]);
			if (score < fallbackScore) { fallbackScore = score; fallback = m; fallbackMape = mape; }
			if (Math.abs(ts) > 4) continue;
			anyUnbiased = true;
			if (score < bestScore) { bestScore = score; best = m; bestMape = mape; }
		}
		boolean biased = !anyUnbiased;
		if (best == null) { best = fallback; bestMape = fallbackMape; }
		if (best == null) return null;
		double[] f = new double[H.length];
		for (int hi = 0; hi < H.length; hi++) f[hi] = predict(D, n - 1, H[hi], best.type(), best.k(), best.a(), best.b());
		for (int hi = 0; hi < H.length; hi++) if (Double.isNaN(bestMape[hi])) bestMape[hi] = hi > 0 ? bestMape[hi - 1] : 0;
		return new Forecasts.Side(D[n - 1], f, bestMape, biased, trend, randomness, false, best.name() + " (on your PC)");
	}
}

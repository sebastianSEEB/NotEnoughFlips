package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * "Is this worth buying / bidding on?" and, for items you own, "what do I list it for?"
 *
 *  Price: read from the hovered auction's own tooltip ("Buy it now", "Top bid", "Starting bid"; per item for stacks).
 *  Fair value: from a local server if you run one (median of clean 72 h sales, cheapest-25 % listings and 7-day average), or SkyCofl
 *              (median of the 24 h / 7 d median sale and the lowest BIN); fallback: median of hourly median sales.
 *  Resale: fair value, capped just under the next-cheapest competing BIN.
 *  Profit: resale after AH fees (BIN listing 1 % < 10M, 2 % 10M-100M, 2.5 % >= 100M; 1 % claim tax above 1M) minus price.
 *  Liquidity: sales per day → how long until it sells (Market.liquidity), plus a high / medium / low confidence.
 *
 *  BIN listing:  PROFIT FLIP if profit >= minFlipProfitPercent, it sells often enough and within your max hold time.
 *  Bid auction:  WORTH BIDDING if the current bid is below the max profitable bid
 *                = resale after fees / (1 + minFlipProfitPercent).
 *  Your item:    SELL: what it's worth, the price to list at (just under the cheapest BIN), what you get after fees.
 *  Simple mode:  only the lines you act on; details (fair-value breakdown, listings, forecasts) in Pro mode.
 */
final class Verdict {
	private static final Pattern PRICE = Pattern.compile("(Buy it now|Top bid|Starting bid):\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*coins",
			Pattern.CASE_INSENSITIVE);

	/** price per item; bin = Buy It Now; bids = an auction that already has bids */
	record Listing(double price, boolean bin, boolean bids) {}

	/** The hovered item is yours (inventory, or your own listing in Manage Auctions). listedAt = your current price, if listed. */
	record Mine(Double listedAt, boolean expired, boolean sold) {
		static final Mine OWNED = new Mine(null, false, false);
	}

	/**
	 * suggestedBid = bid to type in (null when it would exceed the cap); nextBid = lowest valid bid;
	 * bidCap = max bid (% of lowest BIN). Bid fields are only set for bid auctions.
	 * listPrice = what to list your own item at; profit = flip profit after fees; etaHours = expected time to resell.
	 */
	record Result(List<Component> lines, Component banner, Double suggestedBid, Double currentBid, Double maxBid, Double lowestBin,
			Double nextBid, Double bidCap, Double listPrice, Double profit, Double etaHours) {
		Result(List<Component> lines, Component banner) {
			this(lines, banner, null, null, null, null, null, null, null, null, null);
		}

		Result withLines(List<Component> l, Component b) {
			return new Result(l, b, suggestedBid, currentBid, maxBid, lowestBin, nextBid, bidCap, listPrice, profit, etaHours);
		}
	}

	/** Hypixel: the first bid must meet the starting bid; later bids must be at least 2.5 % above the current one. */
	static double nextValidBid(Listing l) {
		double min = l.bids() ? l.price() * 1.025 : l.price();
		double step = min >= 1e6 ? 1000 : min >= 1e4 ? 100 : 1;       // round up to a clean number
		return Math.ceil(min / step) * step;
	}

	private Verdict() {}

	static Listing listing(List<Component> lore, int count) {
		Double bin = null, top = null, start = null;
		for (Component c : lore) {
			Matcher m = PRICE.matcher(c.getString().replaceAll("§.", ""));
			if (!m.find()) continue;
			double v = Double.parseDouble(m.group(2).replace(",", ""));
			switch (m.group(1).toLowerCase(Locale.ROOT)) {
				case "buy it now" -> bin = v;
				case "top bid" -> top = v;
				default -> start = v;
			}
		}
		int n = Math.max(1, count);
		if (bin != null) return new Listing(bin / n, true, false);
		if (top != null) return new Listing(top / n, false, true);
		if (start != null) return new Listing(start / n, false, false);
		return null;
	}

	/**
	 * Your own listing (Manage Auctions shows "Status: …" and no seller; a browse view shows "Seller: you").
	 * @param me your player name (null if unknown)
	 */
	static Mine ownListing(List<Component> lore, int count, String me) {
		boolean status = false, seller = false, ownSeller = false, expired = false, sold = false;
		for (Component c : lore) {
			String t = c.getString().replaceAll("§.", "").trim();
			String low = t.toLowerCase(Locale.ROOT);
			if (low.startsWith("status:")) {
				status = true;
				expired |= low.contains("expired");
				sold |= low.contains("sold");
			}
			if (low.startsWith("seller:")) {
				seller = true;
				if (me != null && !me.isEmpty() && (t.endsWith(" " + me) || t.endsWith(":" + me))) ownSeller = true;
			}
		}
		if (!(status && !seller) && !ownSeller) return null;
		Listing l = listing(lore, count);
		return new Mine(l == null ? null : l.price(), expired, sold);
	}

	static double listingFee(double price) {
		return price >= 100e6 ? 0.025 : price >= 10e6 ? 0.02 : 0.01;
	}

	static double netAfterFees(double sell) {
		return sell * (1 - listingFee(sell) - Mayor.claimTax(sell));       // claim tax 1 % over 1M (4 % under Derpy)
	}

	/** Fallback fair value for an auction item: median of recent hourly medians. */
	static Double fairAuction(JsonObject d) {
		JsonObject series = HudFormatter.obj(d, "series");
		if (series != null && series.has("median") && series.get("median").isJsonArray()) {
			JsonArray a = series.getAsJsonArray("median");
			List<Double> v = new ArrayList<>();
			for (int i = Math.max(0, a.size() - 72); i < a.size(); i++) {
				JsonElement e = a.get(i);
				if (!e.isJsonNull()) v.add(e.getAsDouble());
			}
			if (v.size() >= 5) return Market.median(v);
		}
		Double w7 = HudFormatter.num(HudFormatter.obj(d, "stats", "7d", "sold"), "avg");
		if (w7 != null) return w7;
		Double avg = HudFormatter.num(HudFormatter.obj(d, "summary"), "avg");
		return avg != null ? avg : HudFormatter.num(HudFormatter.obj(d, "summary"), "lbin");
	}

	/** Fair value of an auction item doc (market.fair, else the hourly-median fallback). */
	static Double fair(JsonObject d) {
		Double mf = HudFormatter.num(HudFormatter.obj(d, "market"), "fair");
		return mf != null ? mf : fairAuction(d);
	}

	private static MutableComponent bold(String text, int color) {
		return Component.literal(text).withStyle(ChatFormatting.BOLD).withColor(color);
	}

	/** Lines in Pro mode only. */
	private static void detail(List<Component> out, NefConfig cfg, Component c) {
		if (!cfg.simpleMode) out.add(c);
	}

	static Result evaluate(JsonObject d, Listing listing, Double liveBuy, NefConfig cfg) {
		return evaluate(d, listing, liveBuy, cfg, null, null);
	}

	static Result evaluate(JsonObject d, Listing listing, Double liveBuy, NefConfig cfg, Valuation.Estimate est) {
		return evaluate(d, listing, liveBuy, cfg, est, null);
	}

	/**
	 * @param est  value of this exact copy (enchants, stars, recomb, gems, pet level/rarity…), or null
	 * @param mine non-null when the item is yours: sell advice instead of buy advice
	 */
	static Result evaluate(JsonObject d, Listing listing, Double liveBuy, NefConfig cfg, Valuation.Estimate est, Mine mine) {
		boolean bz = "bz".equals(d.get("kind").getAsString());
		if (!bz && mine != null) return sellAdvice(d, cfg, est, mine);
		List<Component> out = new ArrayList<>();
		Component banner = null;
		Double suggestedBid = null, maxBidOut = null, nextBidOut = null, bidCapOut = null, profitOut = null, etaOut = null;
		JsonObject s = HudFormatter.obj(d, "summary");
		JsonObject market = HudFormatter.obj(d, "market");
		Double riskD = HudFormatter.num(market, "risk");
		int risk = riskD == null ? 0 : riskD.intValue();
		if (!bz && Market.hasSevereFlag(d)) risk = Math.max(risk, 2);
		double minPct = Math.max(0, cfg.minFlipProfitPercent) / 100.0;

		Double price, fair;
		String what, basis;
		if (bz) {
			price = liveBuy != null ? liveBuy : HudFormatter.num(s, "now");
			fair = HudFormatter.num(HudFormatter.obj(d, "stats", "7d", "buy"), "avg");
			if (fair == null) fair = HudFormatter.num(s, "avg");
			what = "Buy price";
			basis = "7-day avg";
		} else {
			price = listing != null ? Double.valueOf(listing.price()) : HudFormatter.num(s, "lbin");
			fair = fair(d);
			what = listing == null ? "Cheapest BIN" : listing.bin() ? "This BIN" : listing.bids() ? "Current bid" : "Starting bid";
			basis = "fair value";
			if (est != null && est.value() > 0 && listing != null) {
				fair = est.value();                                   // what THIS listed copy is worth, upgrades included
				basis = "this copy's value";
			}
		}
		if (price == null || fair == null || fair <= 0) return new Result(out, null);
		// an NPC sells it: nobody pays more than the shop price, whatever the listings and sales say
		double npc = NpcPrices.price(HudFormatter.str(d, "k"));
		boolean npcCapped = npc > 0 && fair > npc && (est == null || est.extras() < 0.05 * est.value());
		if (npcCapped) {
			fair = npc;
			basis = "the NPC shop price";
		}
		// sale history all over the place (coin transfers, 100M "sales" of a cheap item): the fair value is meaningless
		List<double[]> week = bz || NefClient.cofl == null ? null : NefClient.cofl.weekClean(HudFormatter.str(d, "k"), false);
		Market.Erratic er = bz ? null : Market.erratic(week);
		boolean unreliable = er != null && er.bad();
		// the week's sales mix copies with very different upgrades: when copies with the same or better upgrades are
		// listed right now, price against those instead of calling it unreliable
		boolean covered = !bz && listing != null && est != null && est.cover() > 0;
		boolean mixed = unreliable && covered;
		if (mixed) unreliable = false;
		if (unreliable) risk = Math.max(risk, 2);

		double diff = price / fair - 1;
		String pct = String.format(Locale.ROOT, "%.1f%%", Math.abs(diff) * 100);
		String label;
		int color;
		if (!bz && diff <= -(listingFee(fair) + 0.01 + 0.08)) { label = "GREAT BUY"; color = 0x55FF55; }
		else if (diff <= -0.03) { label = "GOOD BUY"; color = 0x55FF55; }
		else if (diff < 0.03) { label = "FAIR PRICE"; color = 0xFFFF55; }
		else if (diff < 0.15) { label = "OVERPRICED"; color = 0xFFAA00; }
		else { label = "BAD BUY"; color = 0xFF5555; }
		if (risk >= 2 && diff < 0.03) { label = "RISKY BUY"; color = 0xFFAA00; }
		if (listing != null && !listing.bin()) { label = "BID CHECK"; color = 0x55FFFF; }
		if (unreliable) { label = "NO RELIABLE PRICE"; color = 0xFFAA00; }

		if (unreliable) {
			// nothing trustworthy to compare with: say so, and show no value / flip numbers at all
			out.add(Component.empty()
					.append(HudFormatter.seg(" ➜ " + label, color))
					.append(HudFormatter.seg(": " + what + " " + HudFormatter.fmt(price) + ", no trustworthy price to compare it with", HudFormatter.GRAY)));
			out.add(HudFormatter.line(String.format(Locale.ROOT, "    ⚠ sales this week go from %s to %s%s (%d sales)",
					HudFormatter.fmt(er.low()), HudFormatter.fmt(er.high()), er.top() > er.high() * 2 ? ", one at " + HudFormatter.fmt(er.top()) : "",
					er.sales()), HudFormatter.YELLOW));
			out.add(HudFormatter.line("      copies with very different upgrades, or coin transfers, and no copy like this one is listed to compare with",
					HudFormatter.YELLOW));
			flags(out, d, bz);
			return new Result(out, null);
		}
		out.add(Component.empty()
				.append(HudFormatter.seg(" ➜ " + label, color))
				.append(HudFormatter.seg(": " + what + " " + HudFormatter.fmt(price) + " is " + pct
						+ (diff < 0 ? " under " : " over ") + basis + " (" + HudFormatter.fmt(fair) + ")", HudFormatter.GRAY)));
		if (npc > 0 && price > npc * 1.02)
			out.add(HudFormatter.line("    ⚠ an NPC sells this for " + HudFormatter.fmt(npc) + ": buy it from the NPC, nobody pays more here",
					HudFormatter.YELLOW));
		if (mixed)
			out.add(HudFormatter.line("    sales this week mix copies with very different upgrades, so this is priced against the "
					+ est.coverListed() + " listed cop" + (est.coverListed() == 1 ? "y" : "ies") + " with the same or better upgrades", HudFormatter.DARK));
		if (!bz) {
			Market.Liquidity liq = Market.liquidity(d, fair);
			etaOut = liq.etaHours();
			boolean liquid = liq.perDay() >= Math.max(0.1, cfg.minSalesPerDay);
			boolean quick = Double.isFinite(liq.etaHours()) && liq.etaHours() <= Math.max(1, cfg.maxHoldHours);
			if (market != null) {
				boolean cofl = "SkyCofl".equals(HudFormatter.str(d, "source"));
				detail(out, cfg, HudFormatter.line(cofl
						? "    fair = median sale 24h " + HudFormatter.fmt(HudFormatter.num(market, "sold_median_72h"))
								+ " · 7d " + HudFormatter.fmt(HudFormatter.num(market, "avg_7d")) + " · lowest BIN (middle of the three)"
						: "    fair = sold " + HudFormatter.fmt(HudFormatter.num(market, "sold_median_72h"))
								+ " · cheapest 25% listed " + HudFormatter.fmt(HudFormatter.num(HudFormatter.obj(market, "listed"), "p25"))
								+ " · 7d avg " + HudFormatter.fmt(HudFormatter.num(market, "avg_7d")), HudFormatter.DARK));
				JsonObject li = HudFormatter.obj(market, "listed");
				if (li != null) {
					Double cnt = HudFormatter.num(li, "count");
					detail(out, cfg, HudFormatter.line("    listed: ", HudFormatter.GRAY, cnt == null ? "" : cnt.intValue() + "", HudFormatter.WHITE,
							"  lowest ", HudFormatter.GRAY, HudFormatter.fmt(HudFormatter.num(li, "lowest")), HudFormatter.YELLOW,
							"  2nd ", HudFormatter.GRAY, HudFormatter.fmt(HudFormatter.num(li, "second")), HudFormatter.WHITE,
							"  median ", HudFormatter.GRAY, HudFormatter.fmt(HudFormatter.num(li, "median")), HudFormatter.WHITE));
				}
			}
			if (est != null) {
				List<Component> vl = Valuation.lines(est);
				for (int i = 0; i < vl.size(); i++) if (i == 0 || !cfg.simpleMode) out.add(vl.get(i));
			}
			Double resale = HudFormatter.num(market, "resale");          // assumes you bought the cheapest BIN
			Double lowest = HudFormatter.num(HudFormatter.obj(market, "listed"), "lowest");
			boolean notCheapest = listing != null && lowest != null && (!listing.bin() || listing.price() > lowest * 1.001);
			boolean upgraded = est != null && (est.attrs().pet || est.extras() > 0.05 * est.value());
			if (covered) {
				// a copy at least as good is listed: you'd have to sell just under it, and never above this copy's value
				resale = Math.min(est.value(), Market.undercut(est.cover()));
			} else if (upgraded && listing != null) {
				// the cheapest BIN is usually a different (clean / lower level) copy: compete with its clean-equivalent
				// price plus this copy's upgrades, never above this copy's value
				double cleanLowest = lowest != null ? lowest : est.base();
				resale = Math.min(est.value(), Market.undercut(Math.max(cleanLowest, est.base())) + est.extras()) * 0.99;
			} else if (notCheapest) {
				resale = Math.min(fair, Market.undercut(lowest));        // the cheapest BIN is still out there to compete with
			} else if (listing != null && est != null && resale != null) {
				resale = Math.min(resale, est.value());
			}
			double sellAt = resale != null ? resale : fair;
			if (npc > 0 && sellAt > npc && (est == null || est.extras() < 0.05 * est.value())) sellAt = npc;
			double floor = week == null ? 0 : FlipFeed.clearingFloor(week);   // what the cheapest copies really sold for
			if (floor > 0 && sellAt > Market.undercut(floor) && !upgraded && !covered) sellAt = Market.undercut(floor);
			double net = netAfterFees(sellAt);

			if (listing != null && !listing.bin()) {
				double maxBid = net / (1 + minPct);
				boolean room = listing.price() < maxBid * 0.95;      // leave room for the next bid step
				detail(out, cfg, HudFormatter.line("    max profitable bid: ", HudFormatter.GRAY, HudFormatter.fmt(maxBid), 0x55FF55,
						String.format(Locale.ROOT, "  (>= %.0f%% profit reselling at %s after fees)", minPct * 100, HudFormatter.fmt(sellAt)),
						HudFormatter.DARK));
				if (room && liquid && risk < 2 && sellAt >= cfg.minItemValue) {
					banner = bold("✔ WORTH BIDDING: bid up to " + HudFormatter.fmt(maxBid)
							+ " → +" + HudFormatter.fmt(net - listing.price()) + " profit at current bid", 0x55FF55);
					out.add(HudFormatter.line("    ✔ worth bidding: +" + HudFormatter.fmt(net - listing.price()) + " at the current bid, +"
							+ HudFormatter.fmt(net - maxBid) + " even at your max bid", 0x55FF55));
				} else if (!room) {
					out.add(HudFormatter.line("    ✘ already bid past the profitable price", HudFormatter.RED));
				}
				maxBidOut = maxBid;
				// preset bid: the lowest valid bid (starting bid, or current bid + 2.5 %), capped at X % of lowest BIN
				Double low = lowest != null ? lowest : HudFormatter.num(s, "lbin");
				nextBidOut = nextValidBid(listing);
				if (low != null) {
					bidCapOut = low * cfg.maxBidPercentOfLowestBin / 100.0;
					boolean fits = nextBidOut <= bidCapOut;
					if (fits) suggestedBid = nextBidOut;
					String capTxt = String.format(Locale.ROOT, "%.0f%% of lowest BIN = %s", cfg.maxBidPercentOfLowestBin, HudFormatter.fmt(bidCapOut));
					if (fits) {
						out.add(HudFormatter.line("    bid to enter: ", HudFormatter.GRAY, HudFormatter.fmt(nextBidOut),
								nextBidOut <= maxBid ? 0x55FF55 : HudFormatter.YELLOW,
								"  (next valid bid; cap " + capTxt + ")" + (cfg.copyBidOnClick ? "  click to copy" : ""), HudFormatter.DARK));
					} else {
						out.add(HudFormatter.line("    ✘ skip: next valid bid " + HudFormatter.fmt(nextBidOut) + " is above your cap (" + capTxt + ")",
								HudFormatter.RED));
					}
				}
				// banner / yellow border only when there's a bid to enter that's within the cap and still profitable
				if (banner != null) {
					if (suggestedBid == null || suggestedBid > maxBid) {
						banner = null;
					} else {
						double limit = bidCapOut != null ? Math.min(bidCapOut, maxBid) : maxBid;
						banner = bold("✔ WORTH BIDDING: bid " + HudFormatter.fmt(suggestedBid) + " (never above " + HudFormatter.fmt(limit)
								+ ") → +" + HudFormatter.fmt(net - suggestedBid) + " profit", 0x55FF55);
						profitOut = net - suggestedBid;
					}
				}
			} else {
				double profit = net - price;
				double ppct = profit / price;
				if (profit > 0) {
					out.add(HudFormatter.line("    flip ≈ ", HudFormatter.GRAY, "+" + HudFormatter.fmt(profit), 0x55FF55,
							String.format(Locale.ROOT, " (%.1f%%) after AH fees, reselling at %s", ppct * 100, HudFormatter.fmt(sellAt))
									+ (floor > 0 && sellAt <= Market.undercut(floor) + 1 ? " (what the cheapest copies really sold for)"
											: resale != null ? " (just under the next-cheapest BIN)" : ""), HudFormatter.DARK));
				} else {
					out.add(HudFormatter.line("    ✘ no flip profit after fees (resale ≈ " + HudFormatter.fmt(sellAt) + ")", HudFormatter.DARK));
				}
				if (ppct >= minPct && liquid && quick && risk < 2 && sellAt >= cfg.minItemValue) {
					banner = bold(String.format(Locale.ROOT, "✔ PROFIT FLIP: %s, resell for +%s (%.0f%%)",
							listing != null ? "buy now" : "cheapest BIN " + HudFormatter.fmt(price),
							HudFormatter.fmt(profit), ppct * 100), 0x55FF55);
					profitOut = profit;
				}
			}
			out.add(liquidityLine(liq, "resell"));
			if (liquid && !quick && Double.isFinite(liq.etaHours()))
				out.add(HudFormatter.line("    ⚠ takes longer to sell than your max hold time (" + (int) cfg.maxHoldHours + "h)", HudFormatter.YELLOW));
		}

		flags(out, d, bz);
		if (!cfg.simpleMode) hints(out, d, bz, diff);
		Double lowestOut = HudFormatter.num(HudFormatter.obj(d, "market", "listed"), "lowest");
		if (lowestOut == null) lowestOut = HudFormatter.num(s, "lbin");
		return new Result(out, banner, suggestedBid, listing != null && !listing.bin() ? listing.price() : null, maxBidOut, lowestOut,
				nextBidOut, bidCapOut, null, profitOut, etaOut);
	}

	static Component liquidityLine(Market.Liquidity liq, String verb) {
		if (!liq.known())
			return HudFormatter.line("    ⚠ no recent sales: hard to " + verb + ", value uncertain", HudFormatter.YELLOW);
		String perDay = liq.perDay() >= 10 ? String.valueOf(Math.round(liq.perDay()))
				: String.format(Locale.ROOT, "%.1f", liq.perDay());
		return HudFormatter.line("    sells " + perDay + "/day · " + verb + " " + Market.eta(liq.etaHours()) + " · confidence ",
				HudFormatter.GRAY, liq.confidence(), Market.confidenceColor(liq.confidence()));
	}

	/** Your own item: what it's worth, what to list it at (just under the cheapest BIN), what you get after fees. */
	private static Result sellAdvice(JsonObject d, NefConfig cfg, Valuation.Estimate est, Mine mine) {
		List<Component> out = new ArrayList<>();
		JsonObject s = HudFormatter.obj(d, "summary");
		Double fair = fair(d);
		double value = est != null && est.value() > 0 ? est.value() : fair != null ? fair : 0;
		if (value <= 0) return new Result(out, null);
		Double lowest = HudFormatter.num(HudFormatter.obj(d, "market", "listed"), "lowest");
		if (lowest == null) lowest = HudFormatter.num(s, "lbin");
		boolean upgraded = est != null && (est.attrs().pet || est.extras() > 0.05 * est.value());
		double ask;
		if (upgraded) {
			double clean = lowest != null ? Math.max(lowest, est.base()) : est.base();
			ask = Math.min(est.value(), Market.undercut(clean) + est.extras());
		} else if (lowest != null && lowest > 0) {
			ask = Math.min(Market.undercut(lowest), value * 1.15);   // never far above what it's really worth
		} else {
			ask = value;
		}
		double net = netAfterFees(ask);
		Market.Liquidity liq = Market.liquidity(d, value);

		out.add(Component.empty()
				.append(HudFormatter.seg(" ➜ SELL", 0x55FFFF))
				.append(HudFormatter.seg(": worth ≈ " + HudFormatter.fmt(value) + " · list at ", HudFormatter.GRAY))
				.append(HudFormatter.seg(HudFormatter.fmt(ask), 0x55FF55))
				.append(HudFormatter.seg(" → you get " + HudFormatter.fmt(net) + " after fees", HudFormatter.GRAY)));
		out.add(liquidityLine(liq, "sells in"));
		if (mine.sold()) {
			out.add(HudFormatter.line("    ✔ sold: claim your coins", 0x55FF55));
		} else if (mine.expired()) {
			out.add(HudFormatter.line("    ⏳ expired: relist at " + HudFormatter.fmt(ask) + (cfg.copyBidOnClick ? " (click to copy)" : ""),
					HudFormatter.YELLOW));
		} else if (mine.listedAt() != null) {
			if (mine.listedAt() > ask * 1.01 && lowest != null && lowest < mine.listedAt())
				out.add(HudFormatter.line("    ⚠ you're listed at " + HudFormatter.fmt(mine.listedAt()) + ", cheapest is now "
						+ HudFormatter.fmt(lowest) + ": relist at " + HudFormatter.fmt(ask) + " to sell", HudFormatter.YELLOW));
			else
				out.add(HudFormatter.line("    ✔ your price " + HudFormatter.fmt(mine.listedAt()) + " is competitive", 0x55FF55));
		} else if (cfg.copyBidOnClick) {
			out.add(HudFormatter.line("    click it in Create Auction to copy the price", HudFormatter.DARK));
		}
		if (est != null) {
			List<Component> vl = Valuation.lines(est);
			for (int i = 0; i < vl.size(); i++) if (i == 0 || !cfg.simpleMode) out.add(vl.get(i));
		}
		flags(out, d, false);
		return new Result(out, null, null, null, null, lowest, null, null, ask, null, liq.etaHours());
	}

	private static void flags(List<Component> out, JsonObject d, boolean bz) {
		JsonObject market = HudFormatter.obj(d, "market");
		if (market == null || !market.has("flags") || !market.get("flags").isJsonArray()) return;
		for (JsonElement e : market.getAsJsonArray("flags")) {
			JsonObject fl = e.getAsJsonObject();
			if ("thin".equals(fl.get("code").getAsString()) && !bz) continue;
			boolean severe = fl.get("level").getAsInt() >= 2;
			out.add(HudFormatter.line("    " + (severe ? "⚠ " : "• ") + fl.get("text").getAsString(),
					severe ? HudFormatter.RED : HudFormatter.YELLOW));
		}
	}

	/** Forecast / season timing hints (Pro mode). */
	private static void hints(List<Component> out, JsonObject d, boolean bz, double diff) {
		JsonObject f = HudFormatter.obj(d, "forecast", bz ? "buy" : "main");
		JsonObject f24 = HudFormatter.obj(f, "h", "24");
		Double last = HudFormatter.num(f, "last");
		if (f24 != null && last != null && last > 0 && HudFormatter.num(f24, "f") != null) {
			double fc = HudFormatter.num(f24, "f") / last - 1;
			if (fc <= -0.03) out.add(HudFormatter.line(String.format(Locale.ROOT, "    ⏳ forecast: %.1f%% lower in 24h, waiting may be cheaper", fc * 100), HudFormatter.AQUA));
			else if (fc >= 0.03 && diff < 0.03) out.add(HudFormatter.line(String.format(Locale.ROOT, "    ↗ forecast: +%.1f%% in 24h, buying now looks good", fc * 100), HudFormatter.AQUA));
		}
		if (d.has("seasons") && d.get("seasons").isJsonArray()) {
			for (JsonElement e : d.getAsJsonArray("seasons")) {
				JsonObject se = e.getAsJsonObject();
				if (!se.get("confirmed").getAsBoolean()) continue;
				long dip = HudFormatter.nextAt(HudFormatter.num(se, "trough_at"), HudFormatter.num(se, "period"));
				double hours = (dip - System.currentTimeMillis()) / 3_600_000.0;
				Double fall = HudFormatter.num(se, "fall_to_trough");
				if (hours >= 1 && hours <= 24 && fall != null && fall <= -0.02) {
					out.add(HudFormatter.line(String.format(Locale.ROOT, "    ⏳ %s dip %s (%.1f%%): consider waiting",
							se.get("label").getAsString().toLowerCase(Locale.ROOT), HudFormatter.until(dip), fall * 100), 0xFF55FF));
					break;
				}
			}
		}
	}
}

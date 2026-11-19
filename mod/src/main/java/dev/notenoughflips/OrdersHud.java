package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;

/**
 * Your bazaar orders in a small box at the top right of the screen, also while you play without the inventory open:
 * buy orders on the left, sell offers on the right, up to bzHudMax each. Toggle with the key (default O, see
 * Controls → Key Binds) or /nef orders; size and count in FLIP → Bazaar or /nef orders size|max.
 *
 *   ✓ top     your order is the best on the book (it fills first)
 *   ↓ outbid  someone's buy order is above yours / ↑ undercut: someone's offer is below yours
 *   filled    Hypixel says it's filled: collect it in /bz → Manage Orders
 *
 * The orders are read from the "Your Bazaar Orders" menu (open it once so the mod can see them).
 */
final class OrdersHud {
	private static NefConfig cfg;
	private static DataClient data;

	private OrdersHud() {}

	static void init(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
		HudHook.register("bz_orders", (p, size) -> {
			if (cfg.bzHud) paint(p, size[0]);
		});
	}

	static int width() {
		return switch (cfg.bzHudSize == null ? "" : cfg.bzHudSize) {
			case "small" -> 180;
			case "large" -> 320;
			default -> 240;
		};
	}

	static void paint(Highlight.Painter p, int screenW) {
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int w = Math.min(width(), screenW - 8), x = screenW - w - 4, y = 4;
		int max = Math.max(1, Math.min(20, cfg.bzHudMax));
		List<BzOrders.Status> buy = new ArrayList<>(), sell = new ArrayList<>();
		for (BzOrders.Status o : BzOrders.status()) (o.order().side().equals("BUY") ? buy : sell).add(o);
		int rows = Math.max(1, Math.min(max, Math.max(buy.size(), sell.size())));
		int h = 16 + rows * 20 + 4;
		McStyle.panel(p, x, y, x + w, y + h);
		int col = (w - 12) / 2, lx = x + 6, rx = x + 6 + col + 4;
		p.text("Buy orders " + buy.size(), lx, y + 5, McStyle.TEXT);
		p.text("Sell offers " + sell.size(), rx, y + 5, McStyle.TEXT);
		p.fill(x + 6 + col + 1, y + 4, x + 6 + col + 2, y + h - 4, McStyle.LINE);
		if (buy.isEmpty() && sell.isEmpty()) {
			p.text(FcfPanel.fit("Open /bz → Manage Orders once", w - 12), lx, y + 18, McStyle.MUTED);
			p.text(FcfPanel.fit("so your orders show here.", w - 12), lx, y + 27, McStyle.MUTED);
			return;
		}
		column(p, font, buy, lx, y + 16, col - 2, max);
		column(p, font, sell, rx, y + 16, col - 2, max);
	}

	private static void column(Highlight.Painter p, net.minecraft.client.gui.Font font, List<BzOrders.Status> list, int x, int y, int w, int max) {
		for (int i = 0; i < Math.min(max, list.size()); i++) {
			BzOrders.Order o = list.get(i).order();
			String st = list.get(i).state();
			String mark = switch (st) {
				case "top" -> "✓";
				case "outbid" -> "↓";
				case "undercut" -> "↑";
				case "filled" -> "●";
				default -> "·";
			};
			int c = switch (st) {
				case "top" -> McStyle.GOOD;
				case "outbid", "undercut" -> McStyle.BAD;
				case "filled" -> McStyle.BLUE;
				default -> McStyle.MUTED;
			};
			int ry = y + i * 20;
			McStyle.slot(p, x, ry, x + w, ry + 18);
			p.text(mark, x + 3, ry + 2, c == McStyle.GOOD ? 0xFF55FF55 : c == McStyle.BAD ? 0xFFFF5555 : c == McStyle.BLUE ? 0xFF55FFFF : 0xFFCCCCCC);
			p.text(FcfPanel.fit(o.name(), w - 14), x + 11, ry + 2, 0xFFFFFFFF);
			String detail = HudFormatter.fmt(o.price()) + " · " + o.filled() + "/" + o.amount()
					+ (st.equals("outbid") ? " · outbid" : st.equals("undercut") ? " · undercut" : st.equals("filled") ? " · collect" : "");
			p.text(FcfPanel.fit(detail, w - 6), x + 3, ry + 10, 0xFFE0E0E0);
		}
		if (list.size() > max) p.text("+" + (list.size() - max) + " more", x + 2, y + max * 20, McStyle.MUTED);
	}
}

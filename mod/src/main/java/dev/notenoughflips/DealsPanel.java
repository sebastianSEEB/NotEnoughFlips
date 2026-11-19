package dev.notenoughflips;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ChestMenu;

/**
 * The "/nef deals" list as a panel on the left of your inventory (or any chest menu): the "DEALS" button above the
 * inventory opens it, and clicking a row opens that auction (/viewauction, the same as /nef open N). You buy yourself.
 */
final class DealsPanel {
	private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

	private static final int W = 160, BTN_H = 14, ROW = 21;
	private static final List<Hit> hits = new ArrayList<>();
	private static NefConfig cfg;
	private static boolean open;
	private static int page;

	private DealsPanel() {}

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
		return cfg != null && cfg.dealsButton && (s instanceof InventoryScreen
				|| s instanceof AbstractContainerScreen<?> acs && acs.getMenu() instanceof ChestMenu);
	}

	private static int w(String t) {
		return Minecraft.getInstance().font.width(t);
	}

	/** The button sits on the row above the inventory, left of DESK / FCF. */
	private static int[] button(Screen s) {
		int[] box = GuiPos.box(s);
		String label = label();
		int bw = w(label) + 10;
		int right = box[0] - 3 - (s instanceof InventoryScreen && cfg.deskButton ? 34 + 3 : 0);
		int by = Math.max(1, box[1] - BTN_H - 2);
		return new int[]{right - bw, by, right, by + BTN_H};
	}

	private static String label() {
		int n = Deals.list().size();
		return n > 0 ? "DEALS " + n : "DEALS";
	}

	static void paint(Screen s, Highlight.Painter p) {
		if (!active(s)) return;
		hits.clear();
		if (FcfPanel.isOpen()) open = false;                          // they share the left side
		int[] b = button(s);
		McStyle.button(p, b[0], b[1], b[2], b[3], label(), open);
		hits.add(new Hit(b[0], b[1], b[2], b[3], () -> {
			open = !open;
			page = 0;
			if (open) FcfPanel.close();
		}));
		if (!open) return;

		int[] box = GuiPos.box(s);
		int x = Math.max(2, box[0] - W - 6), y0 = box[1], w = Math.min(W, box[0] - 8);
		if (w < 100) return;
		int maxY = s.height - 4;
		List<Deals.Deal> list = Deals.list();
		int per = Math.max(1, (maxY - y0 - 50) / ROW);
		int pages = Math.max(1, (list.size() + per - 1) / per);
		page = Math.min(page, pages - 1);
		int shown = Math.min(per, list.size() - page * per);
		int h = 30 + Math.max(1, shown) * ROW + 26;
		McStyle.panel(p, x, y0, x + w, Math.min(maxY, y0 + h));
		hits.add(new Hit(x, y0, x + w, y0 + h, () -> {}));          // clicks inside never reach the menu
		p.text("Cheap BINs", x + 6, y0 + 6, McStyle.TEXT);
		long scan = FlipFeed.scanAt();
		String age = scan == 0 ? "no scan yet" : Math.max(0, (System.currentTimeMillis() - scan) / 60000) + "m ago";
		p.text(age, x + w - 6 - w(age), y0 + 6, McStyle.MUTED);
		p.text(FcfPanel.fit("Click one to open it. You press Buy.", w - 12), x + 6, y0 + 16, McStyle.MUTED);
		int y = y0 + 28;
		if (list.isEmpty()) {
			p.text(FcfPanel.fit("Nothing under " + Math.round(cfg.dealMinDiscountPercent) + "% off right now.", w - 12), x + 6, y + 2, McStyle.MUTED);
			p.text(FcfPanel.fit("The AH is checked every " + Math.max(5, cfg.scanMinutes) + " min.", w - 12), x + 6, y + 11, McStyle.MUTED);
			y += ROW;
		}
		long now = System.currentTimeMillis();
		for (int i = page * per; i < Math.min(list.size(), (page + 1) * per); i++) {
			Deals.Deal d = list.get(i);
			int n = i + 1;
			String name = "#" + n + " " + (d.count() > 1 ? d.count() + "x " : "")
					+ (NefClient.data() == null ? Desk.pretty(d.key()) : Bazaar.name(d.key(), NefClient.data()));
			String right = "+" + HudFormatter.fmt(d.profit());
			long mins = Math.max(0, (now - d.firstSeen()) / 60000);
			String detail = String.format(Locale.ROOT, "BIN %s · %.0f%% off · %s", HudFormatter.fmt(d.price()), d.discount() * 100,
					mins < 1 ? "new" : mins + "m ago");
			McStyle.slot(p, x + 4, y - 2, x + w - 4, y + ROW - 3);
			p.text(FcfPanel.fit(name, w - w(right) - 18), x + 8, y + 1, 0xFFFFFFFF);
			p.text(right, x + w - 8 - w(right), y + 1, 0xFF55FF55);
			p.text(FcfPanel.fit(detail, w - 16), x + 8, y + 10, 0xFFE0E0E0);
			final String uuid = d.uuid(), dname = name.replaceFirst("^#[0-9]+ ", "");
			hits.add(new Hit(x + 4, y - 2, x + w - 4, y + ROW - 3, () -> {
				Holdings.intend(dname, d.key(), "Deal", Market.undercut(d.fair()));
				Cmd.viewAuction(uuid);
			}));
			y += ROW;
		}
		int fy = Math.min(maxY, y0 + h) - 20;
		int bx = x + 6;
		McStyle.button(p, bx, fy, bx + w("Scan now") + 10, fy + 13, "Scan now", false);
		hits.add(new Hit(bx, fy, bx + w("Scan now") + 10, fy + 13, () -> AhScanner.poke(cfg, true)));
		if (pages > 1) {
			String pg = (page + 1) + "/" + pages;
			int px = x + w - 6 - 13 - 4 - w(pg) - 4 - 13;
			McStyle.button(p, px, fy, px + 13, fy + 13, "<", false);
			hits.add(new Hit(px, fy, px + 13, fy + 13, () -> page = Math.max(0, page - 1)));
			p.text(pg, px + 17, fy + 3, McStyle.TEXT);
			int nx = px + 17 + w(pg) + 4;
			McStyle.button(p, nx, fy, nx + 13, fy + 13, ">", false);
			hits.add(new Hit(nx, fy, nx + 13, fy + 13, () -> page = Math.min(pages - 1, page + 1)));
		}
	}

	/** True when the click was on the button or the panel (it must not reach the menu below). */
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
}

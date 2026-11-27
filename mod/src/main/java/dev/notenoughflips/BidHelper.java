package dev.notenoughflips;

import java.util.Locale;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Click an auction on the Auction House -> the preset bid (the lowest valid bid: starting bid, or the
 * current bid + 2.5 %, never above 70 % of the lowest BIN) is copied to your clipboard and shown in chat. You then place the bid yourself (paste it into
 * the bid sign and confirm). The mod never clicks, bids or sends anything to the server:
 * automated bidding is a macro under Hypixel's rules.
 */
final class BidHelper {
	record Hover(String key, Double suggested, double nextBid, double cap, double maxProfitable, Double lowest, long at) {}

	record ListHover(String key, double price, long at) {}

	private static volatile Hover last;
	private static volatile Screen current;

	/** The last screen opened (Minecraft.screen is not public in every version). */
	static Screen currentScreen() {
		return current;
	}
	private static volatile ListHover lastList;

	private BidHelper() {}

	/** Called from the tooltip of a bid auction. */
	static void hovered(String key, Double suggested, double nextBid, double cap, double maxProfitable, Double lowest) {
		last = new Hover(key, suggested, nextBid, cap, maxProfitable, lowest, System.currentTimeMillis());
	}

	/** Called from the tooltip of your own item: what to list it at (copied when you click it in an auction menu). */
	static void hoveredList(String key, double price) {
		lastList = new ListHover(key, price, System.currentTimeMillis());
	}

	static void register(NefConfig config) {
		cfg = config;
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			current = screen;
			ScreenEvents.remove(screen).register(s -> {
				if (current == s) current = null;                   // closed: back in game (the snipe banner moves to the HUD)
			});
			SignFill.signOpened(screen);
			RenderHook.register(screen, (s, painter) -> {
				try {
					Sniper.paintButtons(painter, s);                   // Buy / Confirm outlined right after an alert
					Sniper.paint(painter, s.width);                    // undervalued-BIN banner, on top of any menu
				} catch (RuntimeException e) {
					System.err.println("[NEF] snipe banner: " + e);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !Sniper.click(event.x(), event.y()));
			if (!(screen instanceof AbstractContainerScreen<?>)) return;
			SignFill.containerOpened(screen);
			RenderHook.register(screen, (s, painter) -> {
				try {
					Highlight.paint(s, painter);
					Highlight.paintBazaar(s, painter);
					OutbidHud.paint(s, painter);
				} catch (RuntimeException e) {
					System.err.println("[NEF] overlay: " + e);
				}
				try {
					Bidders.scan(s);
					Bidders.paint(painter, s);
				} catch (RuntimeException e) {
					System.err.println("[NEF] bidders: " + e);
				}
				try {
					BzOrders.scan(s);
					FlipPanel.paint(s, painter);
				} catch (RuntimeException e) {
					System.err.println("[NEF] FLIP panel: " + e);
				}
				try {
					FcfPanel.paint(s, painter);
					DealsPanel.paint(s, painter);
					EventsPanel.paint(s, painter);
					FairySouls.paintCorner(s, painter);
					DungeonLoot.paint(s, painter);
					if (s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) Desk.paint(s, painter);
					DiscordButton.paint(s, painter);
					if (cfg.listPriceFill) ListPrice.paint(s, painter);   // Create BIN Auction: suggested price
					TradeCheck.paint(s, painter);                           // player trades: what each side is worth
				} catch (RuntimeException e) {                 // never let a drawing bug break the inventory
					System.err.println("[NEF] FCF/Trade Desk: " + e);
				}
				try {
					PriceChart.paint(s, painter);              // on top: only while you hover an auction item
				} catch (RuntimeException e) {
					System.err.println("[NEF] chart: " + e);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				try {
					if (FlipPanel.click(s, event.x(), event.y())) return false;      // handled by the FLIP panel
					if (DealsPanel.click(s, event.x(), event.y())) return false;     // handled by the DEALS panel
					if (EventsPanel.click(s, event.x(), event.y())) return false;    // handled by the EVENTS panel
					if (DiscordButton.click(s, event.x(), event.y())) return false;  // opens the Discord invite (game asks first)
				} catch (RuntimeException e) {
					System.err.println("[NEF] FLIP click: " + e);
				}
				return !(s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen && Desk.click(event.x(), event.y()));
			});
			ScreenMouseEvents.afterMouseClick(screen).register((s, event, consumed) -> {
				if (OutbidHud.click(s, event.x(), event.y())) return true;
				if (FcfPanel.click(s, event.x(), event.y())) return true;
				if (cfg.copyBidOnClick) onClick(s);
				return consumed;
			});
		});
	}

	private static NefConfig cfg;

	private static void onClick(Screen screen) {
		ListHover lh = lastList;
		String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		if (lh != null && System.currentTimeMillis() - lh.at() <= 400 && title.contains("auction")
				&& (title.contains("create") || title.contains("manage") || title.contains("your auctions"))) {
			lastList = null;
			long amount = (long) Math.floor(lh.price());
			Minecraft.getInstance().keyboardHandler.setClipboard(Long.toString(amount));
			Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD,
					"Copied list price " + String.format(Locale.ROOT, "%,d", amount) + " for " + lh.key() + ". ", HudFormatter.WHITE,
					"Click the price button, paste it (Ctrl+V) and confirm yourself. You get " + HudFormatter.fmt(Verdict.netAfterFees(amount))
							+ " after fees.", HudFormatter.GRAY));
			return;
		}
		Hover h = last;
		if (h == null || System.currentTimeMillis() - h.at() > 400) return;     // click must be on the auction you hover
		if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("auction")) return;
		last = null;
		Minecraft mc = Minecraft.getInstance();
		if (h.suggested() == null) {
			Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD,
					"Skip " + h.key() + ": the next valid bid " + HudFormatter.fmt(h.nextBid()) + " is above your cap "
							+ HudFormatter.fmt(h.cap()) + " (" + (int) cfg.maxBidPercentOfLowestBin + "% of lowest BIN).", HudFormatter.GRAY));
			return;
		}
		long amount = (long) Math.floor(h.suggested());
		mc.keyboardHandler.setClipboard(Long.toString(amount));
		if (cfg.watchBids) Watch.add(h.key(), amount, h.maxProfitable(), h.lowest());
		boolean profitable = h.suggested() <= h.maxProfitable();
		Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD,
				"Copied bid " + String.format(Locale.ROOT, "%,d", amount) + " for " + h.key() + " (cap " + HudFormatter.fmt(h.cap())
						+ "). Paste it into the bid sign (Ctrl+V) and confirm yourself. ",
				HudFormatter.WHITE,
				profitable ? "✔ under your max profitable bid " + HudFormatter.fmt(h.maxProfitable())
						: "⚠ above your max profitable bid " + HudFormatter.fmt(h.maxProfitable()),
				profitable ? 0x55FF55 : HudFormatter.YELLOW));
		if (cfg.watchBids) {
			Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD,
					"Watching " + h.key() + ": lowest BIN re-checked every " + cfg.watchRecheckSeconds + " s for "
							+ cfg.watchHours + " h (/nef watching, /nef unwatch <item>).", HudFormatter.GRAY));
		}
	}
}
